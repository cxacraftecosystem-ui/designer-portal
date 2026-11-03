import logging

logger = logging.getLogger(__name__)

MEMINFO_PATH = "/proc/meminfo"

CGROUP_PATHS: tuple[tuple[str, str], ...] = (
    ("/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory.current"),
    ("/sys/fs/cgroup/memory/memory.limit_in_bytes", "/sys/fs/cgroup/memory/memory.usage_in_bytes"),
)

DEFAULT_SHARE = 0.25

DEFAULT_FLOOR = 8 * 1024 * 1024

_probe_failure_logged = False


def _read_int(path: str) -> int | None:
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
    try:
        with open(MEMINFO_PATH, encoding="ascii") as handle:
            for line in handle:
                if not line.startswith("MemAvailable:"):
                    continue
                parts = line.split()
                if len(parts) >= 2:
                    return int(parts[1]) * 1024
    except (OSError, ValueError):
        return None
    return None


def _cgroup_available_bytes() -> int | None:
    for limit_path, usage_path in CGROUP_PATHS:
        limit = _read_int(limit_path)
        if limit is None or limit <= 0:
            continue
        if limit >= 1 << 62:
            continue
        usage = _read_int(usage_path) or 0
        return max(limit - usage, 0)
    return None


def available_bytes() -> int | None:
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
    available = available_bytes()
    if available is None:
        return ceiling
    derived = int(available * share)
    return max(min(ceiling, derived), min(floor, ceiling))


def describe() -> dict[str, int | None]:
    return {
        "availableBytes": available_bytes(),
        "meminfoAvailableBytes": _meminfo_available_bytes(),
        "cgroupAvailableBytes": _cgroup_available_bytes(),
    }
