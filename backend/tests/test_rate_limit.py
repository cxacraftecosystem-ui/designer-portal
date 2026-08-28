"""The rate limiter's behaviour, at the ASGI layer, with no application and no database.

WHY THIS FILE EXISTS AT ALL. ``app/scale/rate_limit.py`` shipped complete and was never installed:
``install_rate_limit`` was called from nowhere, so ``SCALE_RATE_LIMIT_ENABLED=true`` on a box changed
nothing, and no test would have noticed either way. It is wired into ``create_app`` now
(``tests/test_rate_limit_install.py`` pins that end of it), and a limiter nobody has measured is a
limiter nobody will dare tune — the numbers get raised "to be safe" until they stop limiting
anything, or lowered until a designer in a village is locked out of their own workshop. Everything
below is the arithmetic somebody will want to argue with, written down so the argument is cheap.

THE TWO ALLOWANCES ARE DIFFERENT MECHANISMS AND THE TESTS ARE GROUPED THAT WAY.

* The GENERAL allowance is a courtesy backstop for a 1 GiB box, keyed by bearer-token digest when
  there is one so a signed-in designer's phone and laptop share one bucket.
* The CREDENTIAL allowance covers the two doors that turn a password into a token, is keyed by
  ADDRESS ONLY, and counts FAILURES rather than attempts — a 200 or a 403 is refunded. That refund
  is the whole safety argument for a ceiling as low as twenty, because this product's designers work
  from offices and mobile networks where many people share one public address, and a budget that
  counted successful sign-ins would let one office lock itself out of an app it is typing the right
  password into. Half of the tests below are about the refund for that reason.

NOTHING HERE BUILDS THE APPLICATION. The middleware is wrapped around a stub that answers whatever
status the test asks for, which is what makes it possible to assert "a 403 costs nothing" without a
database, a user row or a bcrypt hash. The cost of that choice is that these tests cannot see the
middleware ORDER or the CORS header on a 429 — those need the real stack, and they live in
``tests/test_rate_limit_install.py``.
"""

import httpx
import pytest

from app.scale import rate_limit
from app.scale.rate_limit import (
    _CREDENTIAL_REFUSAL,
    _GENERIC_REFUSAL,
    RateLimitMiddleware,
    _TokenBuckets,
)

# A general allowance high enough to be invisible, for the tests that are about the credential
# budget. Without it a test that fires a hundred sign-ins would trip the OTHER limiter and pass for
# the wrong reason.
_UNLIMITED = 10_000


@pytest.fixture(autouse=True)
def _no_shared_window(monkeypatch: pytest.MonkeyPatch) -> None:
    """Force the in-process bucket, whatever the developer's ``.env`` says.

    ``shared_window_hit`` returns None unless the response cache is on AND its backend is Redis, so
    on a fresh clone this fixture changes nothing. It exists for the machine where somebody left
    ``SCALE_CACHE_BACKEND=redis`` in ``backend/.env``: there the limiter would try to open a socket
    and every assertion below would be about a different code path from the one it names.
    """

    async def never_shared(_key: str, _window: float) -> int | None:
        return None

    monkeypatch.setattr(rate_limit, "shared_window_hit", never_shared)


def _stub(status_code: int = 200) -> object:
    """An ASGI app that answers every request with ``status_code`` and an empty JSON body."""

    async def app(scope: object, receive: object, send: object) -> None:
        await send(
            {
                "type": "http.response.start",
                "status": status_code,
                "headers": [(b"content-type", b"application/json")],
            }
        )
        await send({"type": "http.response.body", "body": b"{}"})

    return app


def _client(
    inner: object,
    *,
    requests: int = _UNLIMITED,
    window_seconds: float = 60.0,
    credential_failures: int = 3,
    credential_window_seconds: float = 300.0,
    address: str = "203.0.113.7",
) -> httpx.AsyncClient:
    limited = RateLimitMiddleware(
        inner,
        requests=requests,
        window_seconds=window_seconds,
        credential_failures=credential_failures,
        credential_window_seconds=credential_window_seconds,
    )
    return httpx.AsyncClient(
        transport=httpx.ASGITransport(app=limited, client=(address, 44321)),
        base_url="http://rl.test",
    )


# =================================================================================================
# The general allowance
# =================================================================================================


async def test_a_burst_up_to_the_limit_is_allowed_and_the_next_request_is_refused() -> None:
    """The bucket's capacity IS the limit, so the first N are instantaneous.

    That is the property that makes 120/60s safe for a dashboard page load, which fires several
    requests at once: the sustained rate is capped, the burst is not.
    """
    async with _client(_stub(), requests=3, window_seconds=60.0) as client:
        first = [(await client.get("/api/anything")).status_code for _ in range(3)]
        refused = await client.get("/api/anything")

    assert first == [200, 200, 200]
    assert refused.status_code == 429
    assert refused.json()["detail"] == _GENERIC_REFUSAL


async def test_the_refusal_carries_retry_after_the_limit_and_no_store() -> None:
    """What a client can actually act on, and the one header that stops the refusal being cached.

    ``cache-control: no-store`` is not decoration. A 429 is about this instant, not about this URL;
    CloudFront sits in front of this API and a cached refusal would go on being served after the
    allowance had refilled, turning a momentary limit into an outage for that path.
    """
    async with _client(_stub(), requests=1, window_seconds=60.0) as client:
        await client.get("/api/anything")
        refused = await client.get("/api/anything")

    assert refused.status_code == 429
    assert refused.headers["x-ratelimit-limit"] == "1"
    assert refused.headers["x-ratelimit-remaining"] == "0"
    assert refused.headers["cache-control"] == "no-store"
    # Always at least one second: `int(retry_after) + 1`, so a client that sleeps for exactly the
    # advertised time never arrives a fraction of a second early and is refused twice.
    assert int(refused.headers["retry-after"]) >= 1
    assert refused.json()["retryAfterSeconds"] == int(refused.headers["retry-after"])


async def test_the_health_probes_are_never_limited() -> None:
    """A 429 to CloudFront's origin check or to the uptime monitor reads as an outage.

    Both probe on a fixed cadence and neither carries a token, so they land in the same anonymous
    bucket as everything else from that address — which is exactly how a limiter takes a box out of
    rotation while the box is perfectly healthy.
    """
    async with _client(_stub(), requests=1, window_seconds=60.0) as client:
        verdicts = [(await client.get("/health")).status_code for _ in range(5)]
        ready = [(await client.get("/health/ready")).status_code for _ in range(5)]

    assert verdicts == [200] * 5
    assert ready == [200] * 5


async def test_a_cors_preflight_is_never_limited() -> None:
    """A rate-limited OPTIONS breaks the web app completely, and reports it as a CORS failure.

    The browser never sends the real request, so the user sees "blocked by CORS policy" with no 429
    anywhere — a limit that was meant to slow one caller down instead makes the site look broken to
    everyone on that address.
    """
    async with _client(_stub(), requests=1, window_seconds=60.0) as client:
        verdicts = [(await client.request("OPTIONS", "/api/anything")).status_code for _ in range(5)]

    assert verdicts == [200] * 5


async def test_two_bearer_tokens_get_two_allowances() -> None:
    """One signed-in user is one bucket, and two users on one office connection are two.

    This is the reason identity prefers the token over the address at all: keyed by address, a
    shared connection would give a whole team a single allowance between them.
    """
    async with _client(_stub(), requests=2, window_seconds=60.0) as client:
        one = {"Authorization": "Bearer token-for-meera"}
        two = {"Authorization": "Bearer token-for-arjun"}
        spent = [(await client.get("/api/anything", headers=one)).status_code for _ in range(2)]
        meera_refused = await client.get("/api/anything", headers=one)
        arjun = await client.get("/api/anything", headers=two)

    assert spent == [200, 200]
    assert meera_refused.status_code == 429
    assert arjun.status_code == 200


async def test_the_left_most_forwarded_address_is_the_identity() -> None:
    """``X-Forwarded-For`` is browser -> CloudFront -> nginx -> here, so the client is left-most.

    Reading it right-most-first would charge every anonymous request in the deployment to
    CloudFront's address — one bucket for the entire internet, which is a limiter that either
    refuses everybody or nobody.
    """
    async with _client(_stub(), requests=1, window_seconds=60.0) as client:
        chain = {"X-Forwarded-For": "198.51.100.4, 70.132.0.1"}
        other = {"X-Forwarded-For": "198.51.100.9, 70.132.0.1"}
        assert (await client.get("/api/anything", headers=chain)).status_code == 200
        again = await client.get("/api/anything", headers=chain)
        neighbour = await client.get("/api/anything", headers=other)

    assert again.status_code == 429
    assert neighbour.status_code == 200


async def test_a_shared_window_overrides_the_local_bucket_when_one_exists(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """With Redis configured the count comes from the shared window, not from this process.

    Pinned because the branch is invisible on a fresh clone — ``shared_window_hit`` answers None
    until the cache is on AND its backend is Redis — and because getting it backwards is the failure
    where two workers each grant the full allowance and the limit is quietly double what it says.
    """
    counts = iter([1, 2, 99])

    async def shared(_key: str, _window: float) -> int:
        return next(counts)

    monkeypatch.setattr(rate_limit, "shared_window_hit", shared)

    async with _client(_stub(), requests=50, window_seconds=60.0) as client:
        # A local bucket of 50 would allow all three; the shared count of 99 is what refuses the
        # third, so this cannot pass by accident on the in-process path.
        verdicts = [(await client.get("/api/anything")).status_code for _ in range(3)]

    assert verdicts == [200, 200, 429]


# =================================================================================================
# The credential doors: POST /api/auth/login and POST /api/datasets/token
# =================================================================================================


@pytest.mark.parametrize("path", ["/api/auth/login", "/api/datasets/token"])
async def test_wrong_passwords_close_the_door_after_the_budget_is_spent(path: str) -> None:
    """Guessing is what the budget counts, and both credential doors draw on the same one."""
    async with _client(_stub(401), credential_failures=3) as client:
        guesses = [(await client.post(path)).status_code for _ in range(3)]
        refused = await client.post(path)

    assert guesses == [401, 401, 401]
    assert refused.status_code == 429
    assert refused.json()["detail"] == _CREDENTIAL_REFUSAL
    # The credential ceiling, not the general one — a limit header that reported 10,000 here would
    # tell an operator reading the logs that the wrong limiter fired.
    assert refused.headers["x-ratelimit-limit"] == "3"


async def test_the_two_doors_share_one_budget() -> None:
    """An attacker who exhausts ``/auth/login`` cannot carry on at ``/datasets/token``.

    They are two spellings of "turn this password into a token" against the same account table, so
    separate budgets would simply double the allowance for anybody who noticed.
    """
    async with _client(_stub(401), credential_failures=2) as client:
        assert (await client.post("/api/auth/login")).status_code == 401
        assert (await client.post("/api/auth/login")).status_code == 401
        assert (await client.post("/api/datasets/token")).status_code == 429


async def test_a_successful_sign_in_never_spends_the_budget() -> None:
    """THE PROPERTY THE WHOLE DESIGN RESTS ON, and the reason the ceiling can be as low as twenty.

    Fifty successful sign-ins from one address, against a budget of three. If the budget counted
    attempts rather than failures, a village office or a carrier-grade NAT — dozens to thousands of
    subscribers sharing one public address — would lock itself out of an app everybody is typing the
    correct password into, which is worse than having no limiter at all.
    """
    async with _client(_stub(200), credential_failures=3) as client:
        verdicts = [(await client.post("/api/auth/login")).status_code for _ in range(50)]

    assert verdicts == [200] * 50


async def test_the_awaiting_approval_refusal_is_not_charged_either() -> None:
    """A 403 is reached only AFTER bcrypt has passed, so it is not a guess.

    ``auth.assert_access_admits`` answers 403 for awaiting-approval / rejected / suspended, and it
    is called after the password check precisely so a stranger cannot use it as an oracle. Charging
    it would spend the budget on the one caller who has PROVED the account is theirs — a designer
    waiting on an administrator, retrying every few minutes, would end up locked out by the sign-in
    protection instead of merely being told to wait.
    """
    async with _client(_stub(403), credential_failures=3) as client:
        verdicts = [(await client.post("/api/auth/login")).status_code for _ in range(20)]

    assert verdicts == [403] * 20


async def test_a_malformed_sign_in_body_is_not_charged() -> None:
    """422 is FastAPI refusing the request shape; nobody guessed anything.

    The same refund covers a 500: if the server fell over while checking a password, the person on
    the other end has enough problems without also being told to wait five minutes.
    """
    async with _client(_stub(422), credential_failures=3) as client:
        verdicts = [(await client.post("/api/auth/login")).status_code for _ in range(20)]

    assert verdicts == [422] * 20


async def test_rotating_the_bearer_token_does_not_buy_a_fresh_sign_in_allowance() -> None:
    """The credential budget is keyed by ADDRESS, deliberately, unlike everything else here.

    An ``Authorization`` header on a sign-in request is attacker-controlled input. Charged per token
    digest, a script would send a fresh random one with every guess and mint itself an unlimited
    number of full buckets — the budget would be perfectly enforced and completely useless.
    """
    async with _client(_stub(401), credential_failures=3) as client:
        guesses = [
            (
                await client.post("/api/auth/login", headers={"Authorization": f"Bearer junk-{n}"})
            ).status_code
            for n in range(3)
        ]
        refused = await client.post(
            "/api/auth/login", headers={"Authorization": "Bearer junk-fresh"}
        )

    assert guesses == [401, 401, 401]
    assert refused.status_code == 429


async def test_a_401_on_an_ordinary_route_costs_nothing() -> None:
    """The failure budget is for the sign-in doors, not for every expired session in the fleet.

    Every protected route in this API answers 401 to a stale token, and a phone that wakes up after
    a fortnight offline fires a handful of them before it refreshes. Charging those would close the
    sign-in door for the whole address at the exact moment somebody needs to sign in again.
    """
    async with _client(_stub(401), credential_failures=3) as client:
        stale = [(await client.get("/api/design-workshops")).status_code for _ in range(20)]
        # And the sign-in door is still open afterwards: 401 (the stub's answer), not 429.
        signin = await client.post("/api/auth/login")

    assert stale == [401] * 20
    assert signin.status_code == 401


async def test_the_general_allowance_still_applies_to_the_sign_in_path() -> None:
    """Sign-in gets BOTH limiters, so requests that never reach a 401 are not free.

    Without this, an attacker could hammer the sign-in route with bodies that answer 422 — refunded
    by the credential budget every time — and pay nothing at all for the load. The refusal has to be
    the GENERAL sentence: a caller who has not failed a single password should not be told they have
    made too many failed sign-in attempts.
    """
    async with _client(
        _stub(422), requests=2, window_seconds=60.0, credential_failures=50
    ) as client:
        allowed = [(await client.post("/api/auth/login")).status_code for _ in range(2)]
        refused = await client.post("/api/auth/login")

    assert allowed == [422, 422]
    assert refused.status_code == 429
    assert refused.json()["detail"] == _GENERIC_REFUSAL


# =================================================================================================
# The bucket arithmetic itself
# =================================================================================================


def test_a_refund_returns_a_token_without_exceeding_the_capacity() -> None:
    """Refunding more than was taken must not inflate the bucket above its ceiling.

    ``min(capacity, tokens + 1)`` is what stops a path that refunds twice — a middleware bug, a
    response that somehow starts twice — from handing an identity a permanently oversized allowance.
    """
    buckets = _TokenBuckets(capacity=2, refill_per_second=0.0001)
    assert buckets.take("ip:a") == (True, 0.0)
    buckets.refund("ip:a")
    buckets.refund("ip:a")
    buckets.refund("ip:a")
    assert [buckets.take("ip:a")[0] for _ in range(3)] == [True, True, False]


def test_a_refund_does_not_resurrect_an_evicted_identity() -> None:
    """An identity that fell out of the bounded table has no debt to forgive.

    The table is bounded because a caller who varies their identity every request is the very shape
    of abuse this exists to survive; a refund that re-inserted an evicted key would let that caller
    grow it from the other end, through a code path the eviction in ``take`` never sees.
    """
    buckets = _TokenBuckets(capacity=2, refill_per_second=0.0001)
    before = buckets.tracked()
    buckets.refund("ip:never-seen")
    assert buckets.tracked() == before == 0


def test_an_exhausted_bucket_reports_how_long_to_wait() -> None:
    """``retry_after`` is derived from the refill rate, not guessed.

    One token per second here, so a bucket that is empty by exactly one token asks for about one
    second — which is what makes the ``retry-after`` header on the 429 something a client can obey
    rather than a number to ignore.
    """
    buckets = _TokenBuckets(capacity=1, refill_per_second=1.0)
    assert buckets.take("ip:a") == (True, 0.0)
    allowed, retry_after = buckets.take("ip:a")
    assert allowed is False
    assert 0.0 < retry_after <= 1.0
