"""That the limiter is actually IN the application, and in the one position that works.

THE DEFECT THIS FILE EXISTS TO STOP HAPPENING TWICE. ``app/scale/`` shipped as a complete, tested,
documented package with a full row of ``SCALE_*`` settings behind it — and ``install_rate_limit``
was called from nowhere. ``SCALE_RATE_LIMIT_ENABLED=true`` on a box did nothing at all, and there
was no assertion anywhere that could tell the difference. ``tests/test_rate_limit.py`` measures
what the middleware DOES; nothing there would have failed while it was mounted on no application.
This file is the other half: it builds the real ``create_app()`` and looks at the stack.

WHY THE POSITION IS ASSERTED AND NOT JUST THE PRESENCE. Starlette runs the most recently added
middleware OUTERMOST, so ``user_middleware[0]`` is the outer layer and each later entry is further
in. The limiter must land between ``CORSMiddleware`` and ``UnhandledErrorMiddleware``:

* INSIDE CORS, or a 429 reaches the browser without ``access-control-allow-origin``, ``fetch``
  rejects, and the web app reports "Failed to fetch" — a rate limit that is invisible as a rate
  limit, which is the exact confusion ``UnhandledErrorMiddleware`` was written to end.
* OUTSIDE the router, or a refused request has already cost a route resolution, a dependency and a
  database query by the time it is refused, and the limiter protects nothing it was installed for.

Both are asserted below on the real stack rather than described in a comment, because a comment
cannot notice the day somebody moves a line.

IT IS NOT FAST — it calls ``create_app()``, which imports the whole router. ``test_schema_
conditional_get.py`` measures that import at several seconds on this machine and warns against
quoting a wall figure; the two applications built here are module-scoped so it is paid twice, once
per configuration, and never per test. NOTHING TOUCHES A DATABASE: an ASGI transport never runs the
app's lifespan, and the only route these tests call is one that does not exist.
"""

from collections.abc import Iterator

import httpx
import pytest
from fastapi import FastAPI

from app.core.config import get_settings
from app.scale.rate_limit import RateLimitMiddleware, install_rate_limit

#: The origin the fixtures configure, so the CORS assertion below is about a named value rather than
#: about whatever ``BACKEND_CORS_ORIGINS`` happens to hold on the machine running the suite.
_ORIGIN = "http://localhost:3000"


def _build(**env: str) -> Iterator[FastAPI]:
    """Build the real application under a temporary environment, then put the settings back.

    ``get_settings`` is ``lru_cache``d, so setting a variable is not enough on its own — the cache
    has to be dropped before the build and again afterwards, or every test module collected after
    this one inherits a ``Settings`` object that says the rate limiter is on.
    """
    with pytest.MonkeyPatch.context() as patch:
        patch.setenv("BACKEND_CORS_ORIGINS", _ORIGIN)
        for name, value in env.items():
            patch.setenv(name, value)
        get_settings.cache_clear()
        from app.main import create_app

        yield create_app()
    get_settings.cache_clear()


@pytest.fixture(scope="module")
def limited_app() -> Iterator[FastAPI]:
    """The application as a deployment with the flag on would run it, allowance of ONE request.

    One rather than the shipped 120 so a test can reach the refusal in two calls. The number is
    itself part of the assertion: it arrives through ``SCALE_RATE_LIMIT_REQUESTS``, so a 429 that
    reports ``x-ratelimit-limit: 1`` proves the settings really do reach the middleware.
    """
    yield from _build(
        SCALE_RATE_LIMIT_ENABLED="true",
        SCALE_RATE_LIMIT_REQUESTS="1",
        SCALE_RATE_LIMIT_WINDOW_SECONDS="60",
    )


@pytest.fixture(scope="module")
def unlimited_app() -> Iterator[FastAPI]:
    """A fresh clone: the flag off, so the middleware is not in the stack at all."""
    yield from _build(SCALE_RATE_LIMIT_ENABLED="false")


def test_the_limiter_is_mounted_inside_cors_and_outside_the_error_handler(
    limited_app: FastAPI,
) -> None:
    """The whole stack, in outermost-first order, asserted as a sequence rather than a membership.

    Reading it: gzip has to be outermost (it must see the finished body and own ``content-length``),
    the security headers next so they land on preflights too, then CORS, then the usage recorder
    (inside CORS so nothing it does can land between a response and its access-control header, and
    outside the router because `scope["route"]` does not exist until the router has matched — see
    the class docstring and the registration comment in ``app/main.py``), then the limiter, then the
    error handler, then the router. Asserting the entire list rather than only the limiter's
    neighbours is deliberate — it is the version of this test that fails when somebody adds a
    seventh middleware in the wrong place, instead of silently continuing to pass.
    """
    stack = [mw.cls.__name__ for mw in limited_app.user_middleware]

    assert stack == [
        "SelectiveGZipMiddleware",
        "SecurityHeadersMiddleware",
        "CORSMiddleware",
        "UsageEventMiddleware",
        "RateLimitMiddleware",
        "UnhandledErrorMiddleware",
    ]


def test_a_fresh_clone_has_no_rate_limit_middleware_at_all(unlimited_app: FastAPI) -> None:
    """Off means ABSENT, not present-and-returning-early.

    A disabled middleware is still a coroutine and a stack frame on every request the server ever
    handles. ``install_rate_limit`` returns without touching the app, so the default configuration
    has the stack it had before this feature existed — and that claim is worth an assertion because
    it is the one a reviewer is asked to take on trust when the flag ships off.
    """
    stack = [mw.cls.__name__ for mw in unlimited_app.user_middleware]

    assert "RateLimitMiddleware" not in stack
    assert stack == [
        "SelectiveGZipMiddleware",
        "SecurityHeadersMiddleware",
        "CORSMiddleware",
        "UsageEventMiddleware",
        "UnhandledErrorMiddleware",
    ]


async def test_a_refused_request_still_carries_the_cors_header(limited_app: FastAPI) -> None:
    """THE REASON THE POSITION IS WHAT IT IS, asserted end to end on the real stack.

    Two requests to a route that does not exist: the first spends the allowance of one and comes
    back 404 from the router, the second never reaches the router at all. The second must carry
    ``access-control-allow-origin`` (it travelled back out through CORS) and the standard security
    headers (it travelled back out through those too). Without the first of those the browser turns
    a rate limit into "Failed to fetch"; without the second, a 429 would be the one response this
    API serves with no CSP.

    THIS TEST OWNS THE ALLOWANCE. ``limited_app`` is module-scoped and its bucket refills at one
    token per sixty seconds, so a second test that made requests through it would see whatever this
    one left behind. Anything new that needs to send a request wants its own fixture.
    """
    transport = httpx.ASGITransport(app=limited_app, client=("203.0.113.55", 40001))
    async with httpx.AsyncClient(transport=transport, base_url="http://api.test") as client:
        headers = {"Origin": _ORIGIN}
        spent = await client.get("/api/no-such-route", headers=headers)
        refused = await client.get("/api/no-such-route", headers=headers)

    assert spent.status_code == 404
    assert refused.status_code == 429
    assert refused.headers["access-control-allow-origin"] == _ORIGIN
    assert refused.headers["content-security-policy"]
    assert refused.headers["x-content-type-options"] == "nosniff"
    # The allowance came from SCALE_RATE_LIMIT_REQUESTS, so this is the settings plumbing as much as
    # the header: a middleware built from the field defaults would report 120 here.
    assert refused.headers["x-ratelimit-limit"] == "1"
    assert refused.headers["cache-control"] == "no-store"


class _Recorder:
    """Stands in for the app, so ``install_rate_limit`` can be inspected without building one."""

    def __init__(self) -> None:
        self.added: list[tuple[object, dict[str, object]]] = []

    def add_middleware(self, cls: object, **kwargs: object) -> None:
        self.added.append((cls, kwargs))


def test_the_configured_numbers_are_the_only_two_the_installer_passes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The two SCALE_* numbers reach the middleware, and the credential budget is NOT among them.

    Pinning the kwargs exactly is the point. The sign-in failure budget is a pair of constants in
    ``app/scale/rate_limit.py`` on purpose — it is part of the limiter rather than a switch for an
    optional layer, and two more environment variables would be two more numbers a deployment could
    set without anybody reasoning about them. If somebody promotes them to settings, this assertion
    is where that decision has to be made deliberately rather than by adding a line.
    """
    monkeypatch.setenv("SCALE_RATE_LIMIT_ENABLED", "true")
    monkeypatch.setenv("SCALE_RATE_LIMIT_REQUESTS", "7")
    monkeypatch.setenv("SCALE_RATE_LIMIT_WINDOW_SECONDS", "11")
    get_settings.cache_clear()
    try:
        recorder = _Recorder()
        installed = install_rate_limit(recorder)
    finally:
        get_settings.cache_clear()

    assert installed is True
    assert len(recorder.added) == 1
    cls, kwargs = recorder.added[0]
    assert cls is RateLimitMiddleware
    assert kwargs == {"requests": 7, "window_seconds": 11.0}


def test_the_installer_reports_that_it_installed_nothing_when_the_flag_is_off(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The return value is what ``app/scale/selfcheck.py`` reads on the box, to name live layers.

    A version that returned True unconditionally would make the self-check — the tool whose entire
    job is telling an operator what is actually running — confidently wrong.
    """
    monkeypatch.setenv("SCALE_RATE_LIMIT_ENABLED", "false")
    get_settings.cache_clear()
    try:
        recorder = _Recorder()
        installed = install_rate_limit(recorder)
    finally:
        get_settings.cache_clear()

    assert installed is False
    assert recorder.added == []
