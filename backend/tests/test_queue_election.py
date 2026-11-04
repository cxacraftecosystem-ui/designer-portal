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



def test_the_first_claimant_always_wins(lock_path):
    handle = media_queue.acquire_queue_worker_lock(lock_path)
    try:
        assert handle is not None
    finally:
        if hasattr(handle, "close"):
            handle.close()


@needs_flock
def test_a_second_claimant_is_refused(lock_path):
    first = media_queue.acquire_queue_worker_lock(lock_path)
    try:
        assert first is not None
        assert media_queue.acquire_queue_worker_lock(lock_path) is None
    finally:
        first.close()


@needs_flock
def test_the_lock_is_released_when_the_handle_is_closed(lock_path):
    first = media_queue.acquire_queue_worker_lock(lock_path)
    first.close()
    second = media_queue.acquire_queue_worker_lock(lock_path)
    try:
        assert second is not None
    finally:
        second.close()


@needs_flock
def test_the_lock_file_records_the_holding_pid(lock_path):
    handle = media_queue.acquire_queue_worker_lock(lock_path)
    try:
        assert Path(lock_path).read_text(encoding="utf-8").strip().isdigit()
    finally:
        handle.close()


def test_the_default_path_is_host_wide_and_not_per_directory(lock_path):
    assert Path(media_queue.QUEUE_LOCK_PATH).is_absolute()



def test_the_worker_exits_non_zero_when_it_loses(monkeypatch):
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
    from app import worker

    async def _drain() -> None:
        return None

    monkeypatch.setattr(worker, "acquire_queue_worker_lock", lambda *a, **k: object())
    monkeypatch.setattr(worker, "_run", _drain)
    worker.main()



def _source(relative: str) -> str:
    return (BACKEND_ROOT / relative).read_text(encoding="utf-8")


@pytest.mark.parametrize("module", ["app/main.py", "app/worker.py"])
def test_both_entry_points_import_the_shared_helper(module):
    source = _source(module)
    assert re.search(
        r"from\s+app\.services\.media_queue\s+import\s+[^\n\r]*acquire_queue_worker_lock", source
    ), f"{module} must take the election from services/media_queue, not define its own"
    assert re.search(r"acquire_queue_worker_lock\s*\(", source)


@pytest.mark.parametrize("module", ["app/main.py", "app/worker.py"])
def test_neither_entry_point_keeps_a_private_copy(module):
    source = _source(module)
    assert not re.search(r"def\s+_?acquire_queue_worker_lock\s*\(", source)
    assert not re.search(r"^\s*import\s+fcntl", source, re.MULTILINE)
    assert "flock(" not in source


def test_the_web_process_only_asks_when_the_flag_is_on():
    source = _source("app/main.py")
    assert re.search(
        r"if\s+settings\.media_queue_worker_enabled:\s+queue_lock\s*=\s*"
        r"acquire_queue_worker_lock\(\)",
        source,
    )


def test_the_flag_now_defaults_off():
    from app.core.config import Settings

    assert Settings.model_fields["media_queue_worker_enabled"].default is False


def test_the_election_is_awaitable_from_neither_side():
    assert not asyncio.iscoroutinefunction(media_queue.acquire_queue_worker_lock)
