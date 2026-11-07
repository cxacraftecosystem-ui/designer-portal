import httpx
import pytest

from app.core.security import create_access_token
from app.scale import rate_limit
from app.scale.rate_limit import (
    _CREDENTIAL_REFUSAL,
    _GENERIC_REFUSAL,
    RateLimitMiddleware,
    _TokenBuckets,
)

_UNLIMITED = 10_000

_SPOOFED = "198.51.100.{n}, 203.0.113.50, 70.132.0.1"


@pytest.fixture(autouse=True)
def _no_shared_window(monkeypatch: pytest.MonkeyPatch) -> None:
    async def never_shared(_key: str, _window: float) -> int | None:
        return None

    monkeypatch.setattr(rate_limit, "shared_window_hit", never_shared)


def _stub(status_code: int = 200) -> object:
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



async def test_a_burst_up_to_the_limit_is_allowed_and_the_next_request_is_refused() -> None:
    async with _client(_stub(), requests=3, window_seconds=60.0) as client:
        first = [(await client.get("/api/anything")).status_code for _ in range(3)]
        refused = await client.get("/api/anything")

    assert first == [200, 200, 200]
    assert refused.status_code == 429
    assert refused.json()["detail"] == _GENERIC_REFUSAL


async def test_the_refusal_carries_retry_after_the_limit_and_no_store() -> None:
    async with _client(_stub(), requests=1, window_seconds=60.0) as client:
        await client.get("/api/anything")
        refused = await client.get("/api/anything")

    assert refused.status_code == 429
    assert refused.headers["x-ratelimit-limit"] == "1"
    assert refused.headers["x-ratelimit-remaining"] == "0"
    assert refused.headers["cache-control"] == "no-store"
    assert int(refused.headers["retry-after"]) >= 1
    assert refused.json()["retryAfterSeconds"] == int(refused.headers["retry-after"])


async def test_the_health_probes_are_never_limited() -> None:
    async with _client(_stub(), requests=1, window_seconds=60.0) as client:
        verdicts = [(await client.get("/health")).status_code for _ in range(5)]
        ready = [(await client.get("/health/ready")).status_code for _ in range(5)]

    assert verdicts == [200] * 5
    assert ready == [200] * 5


async def test_a_cors_preflight_is_never_limited() -> None:
    async with _client(_stub(), requests=1, window_seconds=60.0) as client:
        verdicts = [(await client.request("OPTIONS", "/api/anything")).status_code for _ in range(5)]

    assert verdicts == [200] * 5


async def test_two_bearer_tokens_get_two_allowances() -> None:
    async with _client(_stub(), requests=2, window_seconds=60.0) as client:
        one = {"Authorization": f"Bearer {create_access_token(subject='meera')}"}
        two = {"Authorization": f"Bearer {create_access_token(subject='arjun')}"}
        spent = [(await client.get("/api/anything", headers=one)).status_code for _ in range(2)]
        meera_refused = await client.get("/api/anything", headers=one)
        arjun = await client.get("/api/anything", headers=two)

    assert spent == [200, 200]
    assert meera_refused.status_code == 429
    assert arjun.status_code == 200


async def test_the_hop_counted_forwarded_address_is_the_identity() -> None:
    async with _client(_stub(), requests=1, window_seconds=60.0, address="127.0.0.1") as client:
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
    counts = iter([1, 2, 99])

    async def shared(_key: str, _window: float) -> int:
        return next(counts)

    monkeypatch.setattr(rate_limit, "shared_window_hit", shared)

    async with _client(_stub(), requests=50, window_seconds=60.0) as client:
        verdicts = [(await client.get("/api/anything")).status_code for _ in range(3)]

    assert verdicts == [200, 200, 429]



@pytest.mark.parametrize("path", ["/api/auth/login", "/api/datasets/token"])
async def test_wrong_passwords_close_the_door_after_the_budget_is_spent(path: str) -> None:
    async with _client(_stub(401), credential_failures=3) as client:
        guesses = [(await client.post(path)).status_code for _ in range(3)]
        refused = await client.post(path)

    assert guesses == [401, 401, 401]
    assert refused.status_code == 429
    assert refused.json()["detail"] == _CREDENTIAL_REFUSAL
    assert refused.headers["x-ratelimit-limit"] == "3"


async def test_the_two_doors_share_one_budget() -> None:
    async with _client(_stub(401), credential_failures=2) as client:
        assert (await client.post("/api/auth/login")).status_code == 401
        assert (await client.post("/api/auth/login")).status_code == 401
        assert (await client.post("/api/datasets/token")).status_code == 429


async def test_a_successful_sign_in_never_spends_the_budget() -> None:
    async with _client(_stub(200), credential_failures=3) as client:
        verdicts = [(await client.post("/api/auth/login")).status_code for _ in range(50)]

    assert verdicts == [200] * 50


async def test_the_awaiting_approval_refusal_is_not_charged_either() -> None:
    async with _client(_stub(403), credential_failures=3) as client:
        verdicts = [(await client.post("/api/auth/login")).status_code for _ in range(20)]

    assert verdicts == [403] * 20


async def test_a_malformed_sign_in_body_is_not_charged() -> None:
    async with _client(_stub(422), credential_failures=3) as client:
        verdicts = [(await client.post("/api/auth/login")).status_code for _ in range(20)]

    assert verdicts == [422] * 20


async def test_rotating_the_bearer_token_does_not_buy_a_fresh_sign_in_allowance() -> None:
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
    async with _client(_stub(401), credential_failures=3) as client:
        stale = [(await client.get("/api/design-workshops")).status_code for _ in range(20)]
        signin = await client.post("/api/auth/login")

    assert stale == [401] * 20
    assert signin.status_code == 401


async def test_the_general_allowance_still_applies_to_the_sign_in_path() -> None:
    async with _client(
        _stub(422), requests=2, window_seconds=60.0, credential_failures=50
    ) as client:
        allowed = [(await client.post("/api/auth/login")).status_code for _ in range(2)]
        refused = await client.post("/api/auth/login")

    assert allowed == [422, 422]
    assert refused.status_code == 429
    assert refused.json()["detail"] == _GENERIC_REFUSAL



def test_a_refund_returns_a_token_without_exceeding_the_capacity() -> None:
    buckets = _TokenBuckets(capacity=2, refill_per_second=0.0001)
    assert buckets.take("ip:a") == (True, 0.0)
    buckets.refund("ip:a")
    buckets.refund("ip:a")
    buckets.refund("ip:a")
    assert [buckets.take("ip:a")[0] for _ in range(3)] == [True, True, False]


def test_a_refund_does_not_resurrect_an_evicted_identity() -> None:
    buckets = _TokenBuckets(capacity=2, refill_per_second=0.0001)
    before = buckets.tracked()
    buckets.refund("ip:never-seen")
    assert buckets.tracked() == before == 0


def test_an_exhausted_bucket_reports_how_long_to_wait() -> None:
    buckets = _TokenBuckets(capacity=1, refill_per_second=1.0)
    assert buckets.take("ip:a") == (True, 0.0)
    allowed, retry_after = buckets.take("ip:a")
    assert allowed is False
    assert 0.0 < retry_after <= 1.0


async def test_rotating_the_left_most_forwarded_address_does_not_buy_a_fresh_allowance() -> None:
    async with _client(_stub(), requests=2, window_seconds=60.0, address="127.0.0.1") as client:
        verdicts = [
            (
                await client.get(
                    "/api/anything", headers={"X-Forwarded-For": _SPOOFED.format(n=n)}
                )
            ).status_code
            for n in range(1, 6)
        ]

    assert verdicts[:2] == [200, 200]
    assert 429 in verdicts[2:], verdicts


async def test_rotating_the_left_most_forwarded_address_does_not_buy_fresh_sign_in_guesses() -> None:
    async with _client(_stub(401), credential_failures=3, address="127.0.0.1") as client:
        verdicts = [
            (
                await client.post(
                    "/api/auth/login", headers={"X-Forwarded-For": _SPOOFED.format(n=n)}
                )
            ).status_code
            for n in range(1, 6)
        ]

    assert verdicts[:3] == [401, 401, 401]
    assert 429 in verdicts[3:], verdicts


async def test_unverified_bearer_strings_do_not_buy_fresh_allowances() -> None:
    async with _client(_stub(), requests=2, window_seconds=60.0) as client:
        verdicts = [
            (await client.get("/api/anything", headers={"Authorization": f"Bearer junk-{n}"})).status_code
            for n in range(5)
        ]

    assert verdicts[:2] == [200, 200]
    assert 429 in verdicts[2:], verdicts
