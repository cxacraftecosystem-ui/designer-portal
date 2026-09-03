"""The host-wide media-queue election, and the half of it that arbitrated nothing.

================================================================================================
THE DEFECT: THE LOCK EXISTED, AND THE ONE CONTENTION IT IS NAMED FOR WAS THE ONE IT DID NOT COVER
================================================================================================

``_acquire_queue_worker_lock`` lived in ``app/main.py``, private to the web process, and was
consulted by the uvicorn lifespan alone. The only claimant it could ever turn away was therefore a
SECOND UVICORN WORKER — and there is no second uvicorn worker: the Dockerfile pins ``--workers 1``,
with a comment recording the outage ``--workers 2`` caused.

Meanwhile the drain that actually runs in production, ``app/worker.py``'s
``fieldrepo-queue.service``, took the lock into account not at all. So two drains on one box — the
exact thing the lock is for — was uncontested: an operator who started the queue unit on a box whose
API still had ``MEDIA_QUEUE_WORKER_ENABLED`` true got two of them, both selecting on
``status: QUEUED``.

**AND ``_lock_job`` DOES NOT SAVE IT.** Its compare-and-set defends the INSTANT of claiming, so the
two drains claim DIFFERENT jobs, honestly, and both then run ffmpeg and paid provider calls on a box
sized for one — which is the CPU/RAM saturation that made ordinary API requests slow enough for
CloudFront's origin-response timeout to fire (HTTP 504). Production escaped by convention, because
DEPLOY_AWS.md has the web box ship ``MEDIA_QUEUE_WORKER_ENABLED=false``; a convention is not a
guard, which is why the flag's default has been turned off as well.

WHY TWO ACQUISITIONS IN ONE PROCESS IS A HONEST TEST OF THIS. ``flock`` locks the OPEN FILE
DESCRIPTION, not the process: each ``open`` creates a distinct description, so the second
``LOCK_EX | LOCK_NB`` fails with ``EWOULDBLOCK`` exactly as it would from a second process. That is
a property of ``flock`` rather than an accident — POSIX record locks (``fcntl.lockf``) would have
GRANTED the second acquisition and made the whole election silently useless in-process — and it is
what lets this suite prove the election without spawning anything.

The lock file is a temp path per test, deliberately: the real one is host-wide, so a suite that used
it would contend with whatever drain the developer has running and fail by environment.
"""

import asyncio
import importlib.util
import re
from pathlib import Path

import pytest

from app.services import media_queue

HAS_FLOCK = importlib.util.find_spec("fcntl") is not None

needs_flock = pytest.mark.skipif(
    not HAS_FLOCK,
    reason="POSIX advisory locks only; on Windows the helper grants unconditionally by design",
)

BACKEND_ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture
def lock_path(tmp_path) -> str:
    return str(tmp_path / "media-queue.lock")


# --------------------------------------------------------------------------------------
# 1. The election itself
# --------------------------------------------------------------------------------------


def test_the_first_claimant_always_wins(lock_path):
    """True on every platform, and the half that must not regress: an election that refused everyone
    would be a queue that never drains, which is a worse outage than the one it prevents."""
    handle = media_queue.acquire_queue_worker_lock(lock_path)
    try:
        assert handle is not None
    finally:
        if hasattr(handle, "close"):
            handle.close()


@needs_flock
def test_a_second_claimant_is_refused(lock_path):
    """THE DEFECT, IN ONE LINE. Before this, ``app/worker.py`` never asked this question at all, so
    the answer — whatever it was — arbitrated only between uvicorn workers that do not exist."""
    first = media_queue.acquire_queue_worker_lock(lock_path)
    try:
        assert first is not None
        assert media_queue.acquire_queue_worker_lock(lock_path) is None
    finally:
        first.close()


@needs_flock
def test_the_lock_is_released_when_the_handle_is_closed(lock_path):
    """Which is why both callers hold the handle for the process lifetime rather than dropping it:
    a restarted queue unit must be able to win the election its predecessor held, immediately, and
    not wait out a stale lock file."""
    first = media_queue.acquire_queue_worker_lock(lock_path)
    first.close()
    second = media_queue.acquire_queue_worker_lock(lock_path)
    try:
        assert second is not None
    finally:
        second.close()


@needs_flock
def test_the_lock_file_records_the_holding_pid(lock_path):
    """The only breadcrumb an operator staring at a refused unit has. It is written after the lock
    is taken, so the file never names a process that lost."""
    handle = media_queue.acquire_queue_worker_lock(lock_path)
    try:
        assert Path(lock_path).read_text(encoding="utf-8").strip().isdigit()
    finally:
        handle.close()


def test_the_default_path_is_host_wide_and_not_per_directory(lock_path):
    """The election is between PROCESSES ON ONE BOX, so the path must not be relative to whatever
    directory a unit happened to start in — two drains launched from two working directories would
    each take "their" lock and neither would learn about the other."""
    assert Path(media_queue.QUEUE_LOCK_PATH).is_absolute()


# --------------------------------------------------------------------------------------
# 2. The standalone worker now refuses to be the second drain
# --------------------------------------------------------------------------------------


def test_the_worker_exits_non_zero_when_it_loses(monkeypatch):
    """EXIT, NOT IDLE, NOT RUN ANYWAY. ``Restart=always`` brings the unit straight back, so a losing
    process becomes a visible restart loop in ``systemctl status`` carrying the reason — which is a
    report an operator can act on. Idling would leave a unit reporting "active (running)" while
    draining nothing; running anyway is the defect."""
    from app import worker

    drained: list[str] = []

    async def _never() -> None:  # pragma: no cover - the assertion is that this is not reached
        drained.append("drained")

    monkeypatch.setattr(worker, "acquire_queue_worker_lock", lambda *a, **k: None)
    monkeypatch.setattr(worker, "_run", _never)
    with pytest.raises(SystemExit) as excinfo:
        worker.main()
    assert excinfo.value.code == 1
    assert drained == []


def test_the_worker_drains_when_it_wins(monkeypatch):
    """The control. The election must not have turned the production queue off."""
    from app import worker

    drained: list[str] = []

    async def _drain() -> None:
        drained.append("drained")

    class _Handle:
        def __init__(self) -> None:
            self.closed = False

        def close(self) -> None:
            self.closed = True

    handle = _Handle()
    monkeypatch.setattr(worker, "acquire_queue_worker_lock", lambda *a, **k: handle)
    monkeypatch.setattr(worker, "_run", _drain)
    worker.main()
    assert drained == ["drained"]
    assert handle.closed, "the lock must be released on the way out, or a restart cannot win it"


def test_the_worker_releases_the_lock_even_when_the_drain_raises(monkeypatch):
    """A crash that kept the file locked would be indistinguishable from a live drain to the process
    systemd starts two seconds later, so the restart would refuse itself for ever."""
    from app import worker

    async def _boom() -> None:
        raise RuntimeError("engine gone")

    class _Handle:
        def __init__(self) -> None:
            self.closed = False

        def close(self) -> None:
            self.closed = True

    handle = _Handle()
    monkeypatch.setattr(worker, "acquire_queue_worker_lock", lambda *a, **k: handle)
    monkeypatch.setattr(worker, "_run", _boom)
    with pytest.raises(RuntimeError):
        worker.main()
    assert handle.closed


def test_the_worker_survives_a_lock_object_with_no_close(monkeypatch):
    """The no-``fcntl`` branch returns a bare sentinel, so the teardown has to tolerate one. This is
    the developer-machine path and it must not turn a clean shutdown into a traceback."""
    from app import worker

    async def _drain() -> None:
        return None

    monkeypatch.setattr(worker, "acquire_queue_worker_lock", lambda *a, **k: object())
    monkeypatch.setattr(worker, "_run", _drain)
    worker.main()


# --------------------------------------------------------------------------------------
# 3. Both entry points take the SAME lock
# --------------------------------------------------------------------------------------
#
# Asserted against the source rather than by importing and calling, because what has to hold is that
# there is ONE helper and two callers of it. Two correct-looking private copies — which is what the
# tree had — pass every behavioural test and still elect nothing.


def _source(relative: str) -> str:
    return (BACKEND_ROOT / relative).read_text(encoding="utf-8")


@pytest.mark.parametrize("module", ["app/main.py", "app/worker.py"])
def test_both_entry_points_import_the_shared_helper(module):
    """``\\s`` and never a literal newline: this repository's files are checked out with CRLF on the
    machine the suite runs on, so a pattern anchored on ``\\n`` matches nothing and the test passes
    by accident."""
    source = _source(module)
    assert re.search(
        r"from\s+app\.services\.media_queue\s+import\s+[^\n\r]*acquire_queue_worker_lock", source
    ), f"{module} must take the election from services/media_queue, not define its own"
    assert re.search(r"acquire_queue_worker_lock\s*\(", source)


@pytest.mark.parametrize("module", ["app/main.py", "app/worker.py"])
def test_neither_entry_point_keeps_a_private_copy(module):
    """The shape of the original defect: a lock helper that only one process knows about. A ``def``
    of one in either file — or an ``import fcntl`` / ``flock`` call, which is what such a copy is
    made of — means the election has been forked again. Prose mentioning ``fcntl`` is fine and
    expected; what must not reappear is the machinery."""
    source = _source(module)
    assert not re.search(r"def\s+_?acquire_queue_worker_lock\s*\(", source)
    assert not re.search(r"^\s*import\s+fcntl", source, re.MULTILINE)
    assert "flock(" not in source


def test_the_web_process_only_asks_when_the_flag_is_on():
    """Unchanged behaviour on the uvicorn side, pinned because the flag's DEFAULT moved: draining
    inside the web process is now an explicit opt-in, and the lifespan must still gate on it rather
    than take the lock unconditionally and become the thing that refuses the queue unit."""
    source = _source("app/main.py")
    assert re.search(
        r"if\s+settings\.media_queue_worker_enabled:\s+queue_lock\s*=\s*"
        r"acquire_queue_worker_lock\(\)",
        source,
    )


def test_the_flag_now_defaults_off():
    """The second half of the same fix. The default was ``True`` — fail-open around a paid provider
    call — so any box that started both the API and the queue unit without naming the variable ran
    two drains. The EC2 unit ships ``false`` explicitly, so production is unaffected."""
    from app.core.config import Settings

    assert Settings.model_fields["media_queue_worker_enabled"].default is False


def test_the_election_is_awaitable_from_neither_side():
    """It is deliberately synchronous: it runs once, at startup, before any event loop work matters,
    and making it a coroutine would invite somebody to call it per drain pass — at which point the
    handle would be dropped and the lock released on every iteration."""
    assert not asyncio.iscoroutinefunction(media_queue.acquire_queue_worker_lock)
