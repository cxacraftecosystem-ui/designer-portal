"""How many bytes one in-process read may claim, asked of the box rather than guessed.

**NOTHING IN THIS BACKEND HAD EVER ASKED.** Every size limit in the tree was a hard-coded
constant — ``MAX_CONVERT_BYTES``, ``WHISPER_MAX_BYTES``, ``ELEVENLABS_MAX_BYTES``,
``DEEPGRAM_MAX_BYTES`` — chosen against a provider's published ceiling or against nothing at all,
and a constant cannot know that the box it is running on is a 1 GiB t3.micro already holding
uvicorn, the Prisma query engine and whatever else is in flight. ``docs/SCALABILITY.md`` §5.1 asks
for "a limit derived from free memory, not a constant", and this module is that derivation.

**WHY NOT ``psutil``.** It is not a dependency of this project, and adding one to read a number the
kernel already publishes as a text file is not a trade worth making on a box this size. Everything
below is the standard library.

**WHAT IT READS, IN ORDER, AND WHY BOTH.**

* ``/proc/meminfo``'s ``MemAvailable`` — the kernel's own estimate of what a new allocation can have
  without pushing the box into swap, which is exactly the question being asked. ``MemFree`` is the
  wrong number: it excludes reclaimable page cache, so a perfectly healthy Linux box reports a few
  megabytes free and a cap derived from it would refuse everything.
* The cgroup's own headroom, when there is a limit. **A container does not get its own
  ``/proc/meminfo``**: a process under a 512 MiB memory cgroup on a 4 GiB host reads the HOST's
  4 GiB there and would allocate its way into an OOM kill on a figure that was never its to spend.
  ``memory.max`` minus ``memory.current`` (cgroup v2, with the v1 spelling as a fallback) is what
  that process may actually still have, so the smaller of the two answers is the honest one. This
  repository ships a container and a Kubernetes manifest, so that is not a hypothetical shape.

Both are absent outside Linux — every development machine in this project is Windows or macOS — and
:func:`available_bytes` answers ``None`` there rather than inventing a figure. :func:`budget_bytes`
then returns the caller's own constant unchanged, so a dev box behaves exactly as it did before this
module existed.
"""

import logging

logger = logging.getLogger(__name__)

#: The kernel's memory summary. A module constant so a test can point it somewhere else.
MEMINFO_PATH = "/proc/meminfo"

#: cgroup v2 first, then v1, as (limit file, usage file). The first readable pair wins.
CGROUP_PATHS: tuple[tuple[str, str], ...] = (
    ("/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory.current"),
    ("/sys/fs/cgroup/memory/memory.limit_in_bytes", "/sys/fs/cgroup/memory/memory.usage_in_bytes"),
)

#: Share of what is genuinely free that ONE read is allowed to claim.
#:
#: A quarter, and the arithmetic behind the fraction rather than the roundness of it: the read is
#: never the only copy. ``requests`` assembles a multipart body as a second contiguous ``bytes``
#: object beside the first, and pydub/ffmpeg decoding compressed audio produces several. Letting one
#: read take half of free memory therefore means taking all of it a moment later, and the box OOMs
#: with the request that caused it looking innocent. A quarter leaves room for the copy the caller is
#: about to make and for the requests already in flight beside it.
DEFAULT_SHARE = 0.25

#: The derived cap is never lower than this, whatever the box says.
#:
#: A momentarily busy box must not start refusing ordinary work. The median object in this
#: repository is 2.01 MiB and p90 is 14.28 MiB (MEASURED, docs/SCALABILITY.md §5.1), so a floor
#: below those would turn a transient memory spike into "this recording is too large" on files that
#: have always worked and will work again in a second. 8 MiB admits the median and most of p90.
DEFAULT_FLOOR = 8 * 1024 * 1024

#: Logged once per process rather than per call — this sits on the download and report paths.
_probe_failure_logged = False


def _read_int(path: str) -> int | None:
    """First whitespace-delimited token of *path* as an int, or ``None`` for anything unreadable.

    ``None`` covers the file being absent (not Linux, or cgroup v1 where v2 was looked for), being
    unreadable, and holding ``max`` — which is how cgroup v2 spells "no limit at all".
    """
    try:
        with open(path, encoding="ascii") as handle:
            token = handle.read(64).split()[0]
    except (OSError, IndexError):
        return None
    try:
        return int(token)
    except ValueError:
        return None


def _meminfo_available_bytes() -> int | None:
    """``MemAvailable`` from ``/proc/meminfo``, in bytes. ``None`` off Linux."""
    try:
        with open(MEMINFO_PATH, encoding="ascii") as handle:
            for line in handle:
                if not line.startswith("MemAvailable:"):
                    continue
                parts = line.split()
                # "MemAvailable:   123456 kB" — the kernel writes kibibytes here and always has.
                if len(parts) >= 2:
                    return int(parts[1]) * 1024
    except (OSError, ValueError):
        return None
    return None


def _cgroup_available_bytes() -> int | None:
    """Headroom left inside this process's memory cgroup, or ``None`` when it has no limit."""
    for limit_path, usage_path in CGROUP_PATHS:
        limit = _read_int(limit_path)
        if limit is None or limit <= 0:
            continue
        # cgroup v1 spells "unlimited" as a number near 2**63. Treat anything implausibly large as
        # no limit rather than as headroom nobody actually has.
        if limit >= 1 << 62:
            continue
        usage = _read_int(usage_path) or 0
        return max(limit - usage, 0)
    return None


def available_bytes() -> int | None:
    """Bytes this process could still allocate, or ``None`` where the box does not say.

    The SMALLER of the kernel's estimate and the cgroup's headroom when both are known — the module
    docstring says why either one alone is wrong. ``None`` means "no source available", which is
    every non-Linux machine, and callers must read it as "derive nothing".
    """
    global _probe_failure_logged
    candidates = [
        value
        for value in (_meminfo_available_bytes(), _cgroup_available_bytes())
        if value is not None
    ]
    if not candidates:
        if not _probe_failure_logged:
            _probe_failure_logged = True
            logger.info(
                "No free-memory source on this platform (%s and the cgroup files are absent); "
                "size caps fall back to their compiled-in constants.",
                MEMINFO_PATH,
            )
        return None
    return min(candidates)


def budget_bytes(ceiling: int, *, share: float = DEFAULT_SHARE, floor: int = DEFAULT_FLOOR) -> int:
    """The largest read to allow right now: never above *ceiling*, never below *floor*.

    ``min(ceiling, share of what is free)`` — the caller's constant stays the hard upper bound, so
    this can only ever make a cap SMALLER than the number that caller already trusted, never larger.
    That ordering is what makes the change safe to deploy: a box with plenty of memory behaves
    exactly as it does today, and a box under pressure refuses sooner instead of dying.

    Returns *ceiling* unchanged when the platform publishes no figure (see :func:`available_bytes`),
    which is every development machine in this project. The floor is itself clamped to *ceiling*, so
    a caller whose ceiling is deliberately tiny — a test lowering it to prove which number the
    comparison reads — is not quietly handed 8 MiB instead.
    """
    available = available_bytes()
    if available is None:
        return ceiling
    derived = int(available * share)
    return max(min(ceiling, derived), min(floor, ceiling))


def describe() -> dict[str, int | None]:
    """What the box currently reports, for a log line or a diagnostic. Never raises."""
    return {
        "availableBytes": available_bytes(),
        "meminfoAvailableBytes": _meminfo_available_bytes(),
        "cgroupAvailableBytes": _cgroup_available_bytes(),
    }
