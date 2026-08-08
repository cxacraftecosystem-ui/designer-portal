"""Three call-time concerns every capability shares: a budget, logging once, and not hammering.

WHY A DEADLINE OBJECT RATHER THAN A TIMEOUT ARGUMENT. A single call fans out into stages — read
the input, reach a provider, composite the result — and the budget belongs to the whole call, not
to each stage. Passing one object down means the HTTP request to a hosted provider gets whatever
is left rather than a fresh sixty seconds each time.

WHAT A DEADLINE CANNOT DO. Nothing here can interrupt a native extension mid-inference: once
onnxruntime is inside its own C++ loop, Python does not get control back until it returns. The
deadline therefore bounds what we *start* and detects what overran, and the real defence against
a long local matte is the pixel ceiling in ``settings`` plus running this on the background queue
where a job may take minutes without anybody's HTTP connection being held open. Hosted providers
are genuinely interruptible, because there the budget becomes a socket timeout.
"""

import logging
import time
from dataclasses import dataclass
from collections.abc import Callable
from typing import Any

from app.ai_features.errors import ProviderTimeout
from app.ai_features.types import Capability

logger = logging.getLogger(__name__)

#: Keys already logged in this process. A dormant feature that has just been switched on with a
#: missing dependency would otherwise write the same line for every image in a batch of 925.
_ANNOUNCED: set[str] = set()

#: When each throttled key was last used, on a monotonic clock. Process-global on purpose: the
#: thing being protected is one outbound IP address, and a per-object limiter would let two jobs
#: on the same box each politely wait one second and then fire together.
_LAST_CALL: dict[str, float] = {}


def warn_once(key: str, message: str, *args: Any) -> None:
    """Log a warning the first time a given key is seen in this process.

    Not locked: two threads racing produce at worst a duplicate line, which is a far smaller
    problem than a lock held across a logging call.
    """
    if key in _ANNOUNCED:
        return
    _ANNOUNCED.add(key)
    logger.warning(message, *args)


def reset_warn_once_cache() -> None:
    """Forget what has been announced. For tests, and after a deliberate settings reload."""
    _ANNOUNCED.clear()


def reset_throttle_cache() -> None:
    """Forget when each key was last called. For tests only."""
    _LAST_CALL.clear()


def throttle(
    key: str,
    min_interval: float,
    *,
    deadline: "Deadline | None" = None,
    clock: Callable[[], float] = time.monotonic,
    sleeper: Callable[[float], None] = time.sleep,
) -> float:
    """Wait until at least ``min_interval`` seconds have passed since ``key`` was last used.

    WHY THIS EXISTS AT ALL, given no vendor here demands it. A market-research brief fans out into
    several queries, and a queue worker running a batch of briefs turns that into a burst from one
    address. Search vendors answer bursts with a 429 at best and a block at worst, and the address
    being blocked is shared by everything else on the box. A one-second floor costs nothing on a
    feature that is already queue-shaped and removes the failure mode entirely.

    It is a courtesy floor, NOT rate-limit compliance: the vendor's published quota is the operator's
    responsibility and is documented per provider. Nothing here knows what the account allows.

    The wait is capped by whatever is left of the deadline, so a throttle can never be the thing
    that silently consumes a call's budget — an exhausted budget is then reported by the deadline
    check that follows, which says so in those words.
    """
    if min_interval <= 0:
        return 0.0
    now = clock()
    last = _LAST_CALL.get(key)
    delay = 0.0
    if last is not None:
        delay = max(0.0, min_interval - (now - last))
        if deadline is not None:
            delay = min(delay, deadline.remaining())
        if delay > 0:
            sleeper(delay)
    # Recorded AFTER the wait, so the interval is measured between the calls themselves rather
    # than between the moments we decided to make them.
    _LAST_CALL[key] = clock()
    return delay


@dataclass(frozen=True)
class Deadline:
    """A wall-clock budget for one capability call, started when the call started."""

    budget_seconds: float
    started_at: float

    @classmethod
    def start(cls, budget_seconds: float) -> "Deadline":
        return cls(budget_seconds=budget_seconds, started_at=time.perf_counter())

    @property
    def elapsed_seconds(self) -> float:
        return time.perf_counter() - self.started_at

    @property
    def elapsed_ms(self) -> int:
        return int(self.elapsed_seconds * 1000)

    @property
    def expired(self) -> bool:
        return self.elapsed_seconds >= self.budget_seconds

    def remaining(self, *, minimum: float = 0.0) -> float:
        """Seconds left, floored at ``minimum``. Handed straight to a socket timeout."""
        return max(minimum, self.budget_seconds - self.elapsed_seconds)

    def check(self, stage: str, *, capability: Capability, provider: str) -> None:
        """Refuse to BEGIN ``stage`` if the budget is already gone.

        Only ever called before work, never after it: throwing away a cutout that took seventy
        seconds to compute because the budget was sixty would waste the expensive part and leave
        the caller with nothing. An overrun that has already happened is reported by
        :func:`overrun_note` instead.
        """
        if not self.expired:
            return
        raise ProviderTimeout(
            f"{capability} exceeded its {self.budget_seconds:g}s budget before {stage} "
            f"({self.elapsed_seconds:.1f}s elapsed)",
            capability=capability,
            provider=provider,
            remediation=(
                "Raise AI_FEATURES_TIMEOUT_SECONDS, shrink the image, or run this on the "
                "background queue where a long job is expected."
            ),
        )


def overrun_note(
    deadline: Deadline, *, stage: str, capability: Capability, provider: str
) -> tuple[str, ...]:
    """Record — and announce once — that finished work took longer than its budget.

    The result is still returned. An overrun is a sizing signal for whoever tunes the timeout, not
    a reason to discard a good cutout, and the note travels with the result into whatever the
    queue persists.
    """
    if not deadline.expired:
        return ()
    warn_once(
        f"overrun:{capability}:{provider}",
        "ai_features: %s via %s took %.1fs, over the %.0fs budget — raise "
        "AI_FEATURES_TIMEOUT_SECONDS or send smaller images",
        capability,
        provider,
        deadline.elapsed_seconds,
        deadline.budget_seconds,
    )
    return (
        f"{stage} took {deadline.elapsed_seconds:.1f}s, over the "
        f"{deadline.budget_seconds:g}s budget",
    )
