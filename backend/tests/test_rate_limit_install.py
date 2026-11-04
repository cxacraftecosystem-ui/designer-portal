from collections.abc import Iterator

import httpx
import pytest
from fastapi import FastAPI

from app.core.config import get_settings
from app.scale.rate_limit import RateLimitMiddleware, install_rate_limit

_ORIGIN = "http://localhost:3000"


def _build(**env: str) -> Iterator[FastAPI]:
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
    yield from _build(
        SCALE_RATE_LIMIT_ENABLED="true",
        SCALE_RATE_LIMIT_REQUESTS="1",
        SCALE_RATE_LIMIT_WINDOW_SECONDS="60",
    )


@pytest.fixture(scope="module")
def unlimited_app() -> Iterator[FastAPI]:
    yield from _build(SCALE_RATE_LIMIT_ENABLED="false")


def test_the_limiter_is_mounted_inside_cors_and_outside_the_error_handler(
    limited_app: FastAPI,
) -> None:
    stack = [mw.cls.__name__ for mw in limited_app.user_middleware]

    assert stack == [
        "SelectiveGZipMiddleware",
        "SecurityHeadersMiddleware",
        "CORSMiddleware",
        "UsageEventMiddleware",
        "RateLimitMiddleware",
        "UnhandledErrorMiddleware",
        "AppVersionMiddleware",
        "IdempotencyMiddleware",
    ]


def test_a_fresh_clone_has_no_rate_limit_middleware_at_all(unlimited_app: FastAPI) -> None:
    stack = [mw.cls.__name__ for mw in unlimited_app.user_middleware]

    assert "RateLimitMiddleware" not in stack
    assert stack == [
        "SelectiveGZipMiddleware",
        "SecurityHeadersMiddleware",
        "CORSMiddleware",
        "UsageEventMiddleware",
        "UnhandledErrorMiddleware",
        "AppVersionMiddleware",
        "IdempotencyMiddleware",
    ]


async def test_a_refused_request_still_carries_the_cors_header(limited_app: FastAPI) -> None:
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
    assert refused.headers["x-ratelimit-limit"] == "1"
    assert refused.headers["cache-control"] == "no-store"


class _Recorder:
    def __init__(self) -> None:
        self.added: list[tuple[object, dict[str, object]]] = []

    def add_middleware(self, cls: object, **kwargs: object) -> None:
        self.added.append((cls, kwargs))


def test_the_configured_numbers_are_the_only_two_the_installer_passes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
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
    monkeypatch.setenv("SCALE_RATE_LIMIT_ENABLED", "false")
    get_settings.cache_clear()
    try:
        recorder = _Recorder()
        installed = install_rate_limit(recorder)
    finally:
        get_settings.cache_clear()

    assert installed is False
    assert recorder.added == []
