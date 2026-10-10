"""A TOKEN python-jose MINTED IS STILL ACCEPTED, AND PyJWT MINTS THE SAME BYTES.

On 2026-10-09 ``app/core/security.py`` moved from python-jose to PyJWT. Every browser and handset in
the field holds a bearer token, so the swap had to be invisible in BOTH directions: tokens minted
before the deploy must keep working after it, and — for a rollback — tokens minted after it must be
ones the old code accepts. The strongest form of that is byte identity, and that is what is held here.

The three tokens below were MINTED BY THE OLD CODE — commit 4b1bc44's ``create_access_token`` under
python-jose 3.5.0 — at the frozen instant :data:`MINTED_AT`, with a lifetime of a century so this
test never ages out. They cover the three shapes in use: a session (email, role and the ``cred``
password fingerprint), a dataset token (``scope``), and a pre-2026-10-09 session with no ``cred``.
"""

from datetime import UTC, datetime
from types import SimpleNamespace

import pytest

from app.core import security

SECRET = "compat-harness-not-a-secret-0123456789abcdef"
MINTED_AT = datetime(2026, 10, 9, 0, 0, 0, 123456, tzinfo=UTC)
CENTURY_MINUTES = 100 * 365 * 24 * 60

JOSE_TOKENS = {
    "session": (
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJjbWFiYzEyM2RlZjQ1NiIsImlhdCI6MTc5MTUwNDAwMC"
        "wiZXhwIjo0OTQ1MTA0MDAwLCJlbWFpbCI6ImRlc2lnbmVyQGV4YW1wbGUub3JnIiwicm9sZSI6IkRFU0lHTkVSIiwi"
        "Y3JlZCI6ImZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZm"
        "ZmZmZmYifQ.P0blwdoAVLdX21wQAAEIpfT0W32cfqOVOb89uC5t0TE"
    ),
    "dataset": (
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJjbWFiYzEyM2RlZjQ1NiIsImlhdCI6MTc5MTUwNDAwMC"
        "wiZXhwIjo0OTQ1MTA0MDAwLCJzY29wZSI6ImRhdGFzZXQ6cmVhZCIsImVtYWlsIjoiZGVzaWduZXJAZXhhbXBsZS5v"
        "cmciLCJjcmVkIjoiZWVlZWVlZWVlZWVlZWVlZWVlZWVlZWVlZWVlZWVlZWVlZWVlZWVlZWVlZWVlZWVlZWVlZWVlZW"
        "VlZWVlZWVlZSJ9.8wPbxJ_y3RcXDT6kLae8ULGRzAzfP5fo4vhJ2V79XkQ"
    ),
    "legacy-no-cred": (
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJjbWFiYzEyM2RlZjQ1NiIsImlhdCI6MTc5MTUwNDAwMC"
        "wiZXhwIjo0OTQ1MTA0MDAwfQ.DvyeRjYQkMRLVcadHGPYebt5OH3wFS5Hl_f0uIySUZY"
    ),
}

#: What the old code's decode_access_token returned for each, in claim order.
JOSE_CLAIMS = {
    "session": {
        "sub": "cmabc123def456",
        "iat": 1791504000,
        "exp": 4945104000,
        "email": "designer@example.org",
        "role": "DESIGNER",
        "cred": "f" * 64,
    },
    "dataset": {
        "sub": "cmabc123def456",
        "iat": 1791504000,
        "exp": 4945104000,
        "scope": "dataset:read",
        "email": "designer@example.org",
        "cred": "e" * 64,
    },
    "legacy-no-cred": {"sub": "cmabc123def456", "iat": 1791504000, "exp": 4945104000},
}


class _Frozen(datetime):
    @classmethod
    def now(cls, tz=None):
        return MINTED_AT


@pytest.fixture
def harness_secret(monkeypatch):
    settings = SimpleNamespace(
        jwt_secret=SECRET, jwt_algorithm="HS256", jwt_expires_minutes=CENTURY_MINUTES
    )
    monkeypatch.setattr(security, "get_settings", lambda: settings)


@pytest.mark.parametrize("kind", sorted(JOSE_TOKENS))
def test_a_token_jose_minted_decodes_to_the_same_claims(harness_secret, kind):
    claims = security.decode_access_token(JOSE_TOKENS[kind])
    assert claims == JOSE_CLAIMS[kind]
    assert list(claims) == list(JOSE_CLAIMS[kind])


def test_pyjwt_mints_the_bytes_jose_minted(harness_secret, monkeypatch):
    """Same second, same inputs, same string — header, claim order, timestamps and HMAC alike."""
    monkeypatch.setattr(security, "datetime", _Frozen)
    minted = {
        "session": security.create_access_token(
            subject="cmabc123def456",
            extra_claims={"email": "designer@example.org", "role": "DESIGNER"},
            credential="f" * 64,
        ),
        "dataset": security.create_access_token(
            subject="cmabc123def456",
            extra_claims={"scope": "dataset:read", "email": "designer@example.org"},
            expires_minutes=CENTURY_MINUTES,
            credential="e" * 64,
        ),
        "legacy-no-cred": security.create_access_token(subject="cmabc123def456"),
    }
    assert minted == JOSE_TOKENS


@pytest.mark.parametrize(
    "token",
    [
        # An unsigned `alg: none` token carrying a valid-looking payload, and junk. The 2026-10-09
        # comparison went further — seventeen hand-built tokens (HS512 under the HS256 secret, a
        # forged payload, a wrong secret, no `exp`, no `sub`, a non-string `sub`, an unexpected
        # `aud`, a future `nbf`, a non-string `jti`, …) got identical verdicts from jose and PyJWT in
        # both directions; these are the cheap ones to keep running.
        "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJzdWIiOiJhY2N0LTEiLCJpYXQiOjE3OTE1MDQwMDAsImV4cCI6NDk0NTEwNDAwMH0.",
        "not-a-token",
        "",
    ],
)
def test_what_jose_refused_is_refused(harness_secret, token):
    with pytest.raises(ValueError, match="Invalid or expired token"):
        security.decode_access_token(token)


def test_a_future_iat_is_still_accepted_as_jose_accepted_it(harness_secret, monkeypatch):
    """PyJWT's own `iat` check refuses a token issued "in the future"; jose never did, and a box whose
    clock steps backwards after a sign-in must not sign that person out. Only integrality is kept."""
    import jwt

    now = int(datetime.now(UTC).timestamp())
    future = jwt.encode({"sub": "acct-1", "iat": now + 3600, "exp": now + 7200}, SECRET, algorithm="HS256")
    assert security.decode_access_token(future)["iat"] == now + 3600
    not_integral = jwt.encode({"sub": "acct-1", "iat": "soon", "exp": now + 7200}, SECRET, algorithm="HS256")
    with pytest.raises(ValueError, match="Invalid or expired token"):
        security.decode_access_token(not_integral)


def test_an_expired_token_is_refused(harness_secret):
    import jwt

    now = int(datetime.now(UTC).timestamp())
    expired = jwt.encode({"sub": "acct-1", "iat": now - 7200, "exp": now - 3600}, SECRET, algorithm="HS256")
    with pytest.raises(ValueError, match="Invalid or expired token"):
        security.decode_access_token(expired)
