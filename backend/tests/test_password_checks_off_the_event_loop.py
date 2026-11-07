from __future__ import annotations

import asyncio
import time
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from app.api.routes import auth as auth_routes, datasets as dataset_routes
from app.core import security
from app.scale import rate_limit
from app.services import identity

SLOW_CHECK_SECONDS = 0.3


def _app() -> FastAPI:
    app = FastAPI()
    app.include_router(auth_routes.router, prefix="/api")
    app.include_router(dataset_routes.router, prefix="/api")
    return app


def _client(app: FastAPI, address: str = "203.0.113.7") -> httpx.AsyncClient:
    return httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app, client=(address, 44321)), base_url="http://pw.test"
    )


@pytest.fixture(autouse=True)
def _nobody_is_found(monkeypatch: pytest.MonkeyPatch) -> None:
    async def not_found(_raw: Any) -> identity.IdentityLookup:
        return identity.IdentityLookup(identity.NOT_FOUND)

    async def no_user(**_kwargs: Any) -> None:
        return None

    monkeypatch.setattr(auth_routes.identity, "resolve_identifier", not_found)
    monkeypatch.setattr(dataset_routes, "db", SimpleNamespace(user=SimpleNamespace(find_unique=no_user)))
    rate_limit.reset_account_credential_budget()
    yield
    rate_limit.reset_account_credential_budget()


def _slow_checks(monkeypatch: pytest.MonkeyPatch) -> None:
    def slow(_password: bytes, _hashed: bytes) -> bool:
        time.sleep(SLOW_CHECK_SECONDS)
        return False

    monkeypatch.setattr(security.bcrypt, "checkpw", slow)


def _fast_checks(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(security.bcrypt, "checkpw", lambda _password, _hashed: False)


@pytest.mark.parametrize(
    ("path", "body"),
    [
        ("/api/auth/login", {"email": "nobody@example.test", "password": "dummy-pass"}),
        ("/api/datasets/token", {"email": "nobody@example.test", "password": "dummy-pass"}),
    ],
)
async def test_a_password_check_does_not_stall_other_requests(
    monkeypatch: pytest.MonkeyPatch, path: str, body: dict[str, str]
) -> None:
    _slow_checks(monkeypatch)
    async with _client(_app()) as client:
        attempt = asyncio.create_task(client.post(path, json=body))
        started = time.monotonic()
        await asyncio.sleep(0.02)
        waited = time.monotonic() - started
        answer = await attempt

    assert answer.status_code == 401, answer.text
    assert waited < SLOW_CHECK_SECONDS / 2, f"the event loop was held for {waited:.3f}s"


@pytest.mark.parametrize("path", ["/api/auth/login", "/api/datasets/token"])
async def test_rotating_the_identifier_does_not_buy_unlimited_password_checks(
    monkeypatch: pytest.MonkeyPatch, path: str
) -> None:
    _fast_checks(monkeypatch)
    async with _client(_app()) as client:
        verdicts = [
            (
                await client.post(path, json={"email": f"u{n}@example.test", "password": "dummy-pass"})
            ).status_code
            for n in range(80)
        ]

    assert 429 in verdicts, verdicts


async def test_another_network_keeps_its_own_password_budget(monkeypatch: pytest.MonkeyPatch) -> None:
    _fast_checks(monkeypatch)
    app = _app()
    async with _client(app, "203.0.113.7") as noisy:
        for n in range(80):
            await noisy.post("/api/auth/login", json={"email": f"n{n}@example.test", "password": "dummy-pass"})
    async with _client(app, "198.51.100.20") as quiet:
        answer = await quiet.post(
            "/api/auth/login", json={"email": "quiet@example.test", "password": "dummy-pass"}
        )

    assert answer.status_code == 401, answer.text
