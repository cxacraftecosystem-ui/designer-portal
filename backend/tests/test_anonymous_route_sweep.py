import re
from typing import Any

import pytest
from conftest import needs_db

import app.services.stage_definitions  # noqa: F401  - installs the registry the router imports
from app.main import app as application

pytestmark = [needs_db]

REFUSALS = frozenset({401, 403})

METHODS = ("get", "put", "post", "delete", "patch", "head", "options", "trace")

PROBE_SEGMENT = "anonymous-route-sweep"

PUBLIC_ALLOWLIST: dict[tuple[str, str], str] = {
    ("POST", "/api/auth/login"): "the sign-in door: it is what a caller has no token BEFORE.",
    ("POST", "/api/auth/logout"): "clears the client's own storage; there is no server-side session.",
    ("GET", "/api/auth/set-password"): "a link holder has no session by definition; the token is the credential.",
    ("POST", "/api/auth/set-password/check"): "the GET's question and answer, the token moved off the request line.",
    ("POST", "/api/auth/set-password"): "same: redeeming the link is how the account gets a password at all.",
    ("POST", "/api/auth/forgot-password"): "a person who forgot their password has no session; one neutral answer for every address.",
    ("POST", "/api/datasets/token"): "mints the machine token FROM a password; there is nothing to present.",
    ("POST", "/api/auth/two-factor/verify"): "the sign-in's second step: the challenge token in the body is the credential.",
    ("GET", "/api/public/census"): "aggregate counts on a public landing page; nothing is attached to a person.",
    ("GET", "/api/usage/consent/notice"): "the consent text itself, read before there is any account to gate.",
    ("GET", "/api/app/download"): "a browser link to an object that is public anyway.",
    ("POST", "/api/report-deliveries/lookup"): "the office holding an e-mailed report link has no account; the link is the credential.",
    ("POST", "/api/report-deliveries/download"): "same: it hands back a five-minute signed URL for that one file.",
    ("GET", "/api/report-share/{token}"): "a shared report cover: the link is the credential, it expires, can be revoked and logs every open.",
    ("POST", "/api/report-schedule/unsubscribe/lookup"): "an e-mailed unsubscribe link works without signing in; the token is the credential.",
    ("POST", "/api/report-schedule/unsubscribe"): "same: it only pauses the one schedule that token names.",
    ("GET", "/health"): "CloudFront origin check and the uptime monitor; a 401 here reads as an outage.",
    ("GET", "/health/ready"): "the same, plus the database probe alerting is meant to watch.",
}


@pytest.fixture(autouse=True)
def _aadhaar_routes_switched_on(monkeypatch):
    from app.core.config import get_settings

    monkeypatch.setattr(get_settings(), "aadhaar_offline_verification_enabled", True)
    monkeypatch.setattr(get_settings(), "aadhaar_online_auth_enabled", True)
    monkeypatch.setattr(get_settings(), "ministry_exports_enabled", True)


@pytest.fixture(autouse=True)
def _cross_portal_search_switched_on(monkeypatch):
    from app.core.config import get_settings

    monkeypatch.setattr(get_settings(), "cross_portal_search_enabled", True)
    monkeypatch.setattr(get_settings(), "cross_portal_token", "s" * 40)
    monkeypatch.setattr(get_settings(), "aadhaar_fingerprint_key", "h" * 40)
    monkeypatch.setattr(get_settings(), "field_repository_api_url", "https://peer.invalid/api")


@pytest.fixture(scope="module")
def client():
    from fastapi.testclient import TestClient

    with TestClient(application) as test_client:
        yield test_client


def _operations() -> list[tuple[str, str]]:
    document = application.openapi()
    found = [
        (method.upper(), path)
        for path, item in document["paths"].items()
        for method in METHODS
        if method in item
    ]
    assert len(found) > 100, (
        f"the sweep found only {len(found)} operations, which is not this application: the OpenAPI "
        "document is not being generated (see test_auth_route_surface for the app.routes trap) and "
        "this module would pass while checking almost nothing"
    )
    return sorted(found)


def _probe(client: Any, method: str, path: str) -> int:
    filled = re.sub(r"\{[^}]+\}", PROBE_SEGMENT, path)
    return client.request(method, filled).status_code


def test_no_route_outside_the_allowlist_answers_a_stranger(client):
    open_doors: list[str] = []
    for method, path in _operations():
        if (method, path) in PUBLIC_ALLOWLIST:
            continue
        status_code = _probe(client, method, path)
        if status_code not in REFUSALS:
            open_doors.append(f"{method} {path} -> {status_code}")

    assert not open_doors, (
        "these operations answered a caller with NO Authorization header instead of refusing:\n  "
        + "\n  ".join(open_doors)
        + "\n\nEither the route is missing an auth dependency — this repository has shipped 'a UI "
        "guard over an open endpoint' twice, and the client hiding the button is not a guard — or "
        "it is genuinely public, in which case add it to PUBLIC_ALLOWLIST in this file WITH THE "
        "SENTENCE that justifies it. A 422 in this list means the handler's own parameters were "
        "reached, which a gated route cannot do."
    )


def test_the_allowlist_names_only_routes_that_exist(client):
    published = set(_operations())
    stale = sorted(
        f"{method} {path}" for method, path in PUBLIC_ALLOWLIST if (method, path) not in published
    )
    assert not stale, (
        "PUBLIC_ALLOWLIST exempts operations this application no longer publishes:\n  "
        + "\n  ".join(stale)
        + "\n\nIf the endpoint moved, move the entry and re-read its sentence; if it is gone, delete "
        "the entry rather than leaving an exemption nobody can trace to a decision."
    )


def test_the_allowlisted_endpoints_answer_anonymously(client):
    status_code = _probe(client, "GET", "/api/usage/consent/notice")
    assert status_code == 200, (
        "the deliberately public consent notice did not answer a caller with no credentials, so "
        "this harness cannot distinguish an open endpoint from a closed one and the sweep above "
        f"proves nothing: got {status_code}"
    )
