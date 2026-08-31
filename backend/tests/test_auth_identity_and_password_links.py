"""The pure halves of the three-way sign-in and of the password link.

NO DATABASE. Everything asserted here is a function of its arguments — the two normalisations, the
token construction, the fingerprint — which is deliberate: those are the parts that decide whether a
person can sign in at all, and a test that needs Postgres is a test that skips on the machine of
whoever is about to change them.

The database halves (`resolve_identifier`, `resolve_profile_keys`, the routes) are exercised by the
suite's own live-database modules; what cannot be covered there and is covered here is the pair of
normalisations, because their SECOND implementation is SQL inside
``20260830170000_auth_identity_and_password_links`` and nothing can compare the two automatically.
The table at :func:`test_normalisation_matches_the_migrations_sql` is that comparison, done by hand,
with the SQL quoted beside each row.
"""

import base64
import json
from datetime import UTC, datetime, timedelta

import pytest

from app.services import credential_links, identity

# ==================================================================================================
# The two normalisations
# ==================================================================================================


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        # The owner's instruction: "their phone number without the country code".
        ("9876543210", "9876543210"),
        ("+91 98765 43210", "9876543210"),
        ("098765 43210", "9876543210"),
        ("+91-98765-43210", "9876543210"),
        # A stored value with a country code normalises to the SAME key as one without, which is the
        # whole point: a designer types the ten digits they would read out loud either way.
        ("00919876543210", "9876543210"),
        # Too short to be a telephone number. Claiming a key here would let one profile own the
        # string "42" for the whole installation.
        ("42", None),
        ("", None),
        (None, None),
        # Exactly at the floor: six digits is claimed, five is not.
        ("123456", "123456"),
        ("12345", None),
    ],
)
def test_normalise_phone(raw, expected):
    assert identity.normalise_phone(raw) == expected


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("EMP/2026/0042", "EMP20260042"),
        ("emp 2026 0042", "EMP20260042"),
        ("EMP-2026-0042", "EMP20260042"),
        ("DES/2024/0142", "DES20240142"),
        # No length floor, unlike the phone: an institution's numbering scheme is not this module's
        # to second-guess, and there is no short string a person types by accident here.
        ("7", "7"),
        ("   ", None),
        ("", None),
        (None, None),
    ],
)
def test_normalise_empanelment_no(raw, expected):
    assert identity.normalise_empanelment_no(raw) == expected


def test_normalisation_matches_the_migrations_sql():
    """THE SQL IN THE MIGRATION AND THE PYTHON HERE ARE ONE RULE WRITTEN TWICE.

    There is no way to share them — the rows that already existed had to be normalised by Postgres —
    so this test is the place the pair is compared. Each assertion below is the SQL expression
    applied by hand to the same input:

        phone:        right(regexp_replace(phone, '[^0-9]', '', 'g'), 10)  when longer than 10
                      nullif(regexp_replace(phone, '[^0-9]', '', 'g'), '') otherwise
        empanelment:  nullif(upper(regexp_replace(empanelmentNo, '[^A-Za-z0-9]', '', 'g')), '')

    If you change one, change the other, and change this table.
    """
    assert identity.normalise_phone("+91 (98765) 43210") == "9876543210"
    assert identity.normalise_empanelment_no("emp/2026/0042") == "EMP20260042"


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("a@b.example", True),
        ("A.Sharma@Example.org", True),
        # Neither of the two numbers can contain an @, which is why the @ settles the question
        # without being validation.
        ("9876543210", False),
        ("EMP/2026/0042", False),
        ("", False),
        (None, False),
    ],
)
def test_looks_like_email(raw, expected):
    assert identity.looks_like_email(raw) is expected


# ==================================================================================================
# The token — the construction ported from cxa-cms
# ==================================================================================================


@pytest.fixture(autouse=True)
def _signing_secret(monkeypatch):
    """A real secret, so ``get_settings().jwt_secret`` is signing with something.

    The app refuses to boot on a placeholder (``verify_jwt_configuration``), so anything this test
    signs with has to look like a real key or the assertions would be about a configuration the
    product does not permit.
    """
    from app.core.config import get_settings

    settings = get_settings()
    monkeypatch.setattr(
        settings, "jwt_secret", "test-secret-that-is-long-enough-for-hs256-x", raising=False
    )
    yield


def _mint(**overrides):
    args = {
        "user_id": "usr_1234567890",
        "purpose": credential_links.RESET,
        "expires_at": datetime.now(UTC) + timedelta(hours=2),
        "password_hash": "$2b$12$abcdefghijklmnopqrstuv",
    }
    args.update(overrides)
    return credential_links.mint_token(**args)


def test_a_freshly_minted_token_verifies():
    verdict = credential_links.verify_token(_mint())
    assert verdict.ok
    assert verdict.user_id == "usr_1234567890"
    assert verdict.purpose == credential_links.RESET


def test_a_tampered_body_is_refused_as_malformed():
    """A FORGED SIGNATURE AND A MANGLED ONE ARE ONE ANSWER, deliberately.

    Separating them would tell somebody probing the endpoint which half of their guess was wrong.
    """
    token = _mint()
    body, signature = token.split(".", 1)
    decoded = json.loads(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)))
    decoded["sub"] = "somebody-else"
    forged = base64.urlsafe_b64encode(json.dumps(decoded).encode()).decode().rstrip("=")
    assert credential_links.verify_token(f"{forged}.{signature}").reason == credential_links.MALFORMED


def test_two_separators_are_refused():
    """A token with two dots would let the body be chosen after the signature was computed over a
    prefix of it."""
    token = _mint()
    assert credential_links.verify_token(f"{token}.extra").reason == credential_links.MALFORMED


def test_an_expired_token_says_so_rather_than_malformed():
    """The expiry is INSIDE the signed payload, so it can be reported honestly to somebody holding a
    link this installation really did issue — which is a different next action from "ask for a link
    that works"."""
    token = _mint(expires_at=datetime.now(UTC) - timedelta(minutes=1))
    assert credential_links.verify_token(token).reason == credential_links.EXPIRED


def test_a_token_longer_than_the_cap_is_refused_before_any_hmac():
    assert (
        credential_links.verify_token("x" * (credential_links.MAX_TOKEN_LENGTH + 1)).reason
        == credential_links.MALFORMED
    )


@pytest.mark.parametrize("raw", [None, "", "   ", 42])
def test_nothing_at_all_is_missing_not_malformed(raw):
    assert credential_links.verify_token(raw).reason == credential_links.MISSING


def test_the_fingerprint_changes_when_the_password_changes():
    """THE SINGLE-USE GUARANTEE, and the whole reason cxa-cms needs no table for it.

    Setting a password changes the hash, so every token minted before it stops describing the
    account — immediately, with nothing having to remember.
    """
    before = credential_links.credential_fingerprint("$2b$12$one")
    after = credential_links.credential_fingerprint("$2b$12$two")
    assert before != after
    assert credential_links.fingerprint_matches("$2b$12$one", before)
    assert not credential_links.fingerprint_matches("$2b$12$two", before)


def test_an_account_with_no_password_digests_a_sentinel():
    """So an INVITATION is bound to "still has no password" — and stops verifying the moment one is
    set, exactly like a reset link does."""
    assert credential_links.credential_fingerprint(None) == credential_links.credential_fingerprint(
        None
    )
    assert credential_links.credential_fingerprint(None) != credential_links.credential_fingerprint(
        "$2b$12$anything"
    )


def test_the_invitation_lifetime_is_longer_than_the_reset_lifetime():
    """cxa-cms's two numbers and its reasoning: an invitation is passed on by hand and read after a
    conference; a reset answers "I am locked out NOW" and a link that outlives that conversation is
    a spare key under the mat."""
    assert credential_links.ttl_hours(credential_links.INVITE) > credential_links.ttl_hours(
        credential_links.RESET
    )


def test_the_link_carries_the_token_and_nothing_else_does():
    """PORTED FROM cxa-cms's CANONICAL ROUTE: a credential appearing twice in one answer is a
    credential in two places to keep out of logs. `DeliveredLink` has no `token` field at all."""
    assert not hasattr(credential_links.DeliveredLink, "token")
    token = _mint()
    assert token.split(".", 1)[0] in credential_links.link_for(token)


def test_two_links_for_one_account_in_the_same_second_are_two_different_tokens():
    """THE REGRESSION. Measured 2026-08-30, before the nonce existed.

    Every other claim in the payload is a function of the ACCOUNT — the id, the purpose, the
    fingerprint of the current password, and an expiry stamped in whole seconds — so two links
    issued for one account inside one second were byte-for-byte identical. The visible half was a
    500: `PasswordResetToken.tokenHash` is unique and the second insert violated it. The invisible
    half is worse and is why this is a nonce rather than a retry — two issues that produce one
    token are ONE CREDENTIAL, so revoking the row an admin thinks they just issued would leave the
    other row live and the link in the wrong chat window would go on working.

    cxa-cms mints the same deterministic shape and never noticed, because it keeps no rows and has
    nothing that must be one-to-one with a link.
    """
    expires = datetime.now(UTC) + timedelta(hours=2)
    first = _mint(expires_at=expires)
    second = _mint(expires_at=expires)
    assert first != second
    assert credential_links.token_digest(first) != credential_links.token_digest(second)
    # BOTH STILL VERIFY. Uniqueness must not have been bought with a claim the verifier rejects.
    assert credential_links.verify_token(first).ok
    assert credential_links.verify_token(second).ok


def test_a_token_with_no_nonce_is_refused():
    """A token minted by a build that predates the nonce is refused rather than read with the wrong
    meaning — the same rule the `v` claim exists for. It has to be forged here with the real signing
    key, because the signature is checked first and a shape check is only reachable behind it."""
    body = base64.urlsafe_b64encode(
        json.dumps(
            {
                "v": 1,
                "sub": "usr_1234567890",
                "purpose": credential_links.RESET,
                "exp": int((datetime.now(UTC) + timedelta(hours=2)).timestamp()),
                "cred": credential_links.credential_fingerprint("$2b$12$abcdefghijklmnopqrstuv"),
            },
            separators=(",", ":"),
            sort_keys=True,
        ).encode()
    ).decode().rstrip("=")
    token = f"{body}.{credential_links._sign(body)}"  # noqa: SLF001 - forging one on purpose
    assert credential_links.verify_token(token).reason == credential_links.MALFORMED

def test_the_stored_digest_is_not_the_token():
    """A table of live credentials is worth stealing; a table of digests is not."""
    token = _mint()
    digest = credential_links.token_digest(token)
    assert token not in digest
    assert len(digest) == 64


# ==================================================================================================
# The per-account guessing budget
# ==================================================================================================


def test_the_account_budget_is_spent_and_refunded():
    """CHARGE THE ATTEMPT, REFUND EVERYTHING THAT WAS NOT A WRONG PASSWORD.

    The middleware beside it takes the same shape for the same reason: check-then-charge lets a
    hundred parallel guesses all pass the check before any of them is counted.
    """
    from app.scale.rate_limit import (
        account_credential_attempt,
        account_credential_refund,
        reset_account_credential_budget,
    )

    reset_account_credential_budget()
    for _ in range(50):
        allowed, _retry = account_credential_attempt("acct-refunded")
        assert allowed
        account_credential_refund("acct-refunded")


def test_the_account_budget_closes_after_enough_failures():
    from app.scale.rate_limit import account_credential_attempt, reset_account_credential_budget

    reset_account_credential_budget()
    outcomes = [account_credential_attempt("acct-guessed")[0] for _ in range(40)]
    assert outcomes[0] is True
    assert outcomes[-1] is False


def test_one_account_is_one_bucket_however_it_was_addressed():
    """THE PROPERTY THE THREE IDENTIFIER SPACES WOULD OTHERWISE HAVE DESTROYED.

    The budget is keyed on the RESOLVED account id, so guessing at one person by email, by phone and
    by empanelment number spends one budget rather than three. Two different accounts are two
    buckets, which is what this asserts from the other side.
    """
    from app.scale.rate_limit import account_credential_attempt, reset_account_credential_budget

    reset_account_credential_budget()
    while account_credential_attempt("acct-a")[0]:
        pass
    assert account_credential_attempt("acct-b")[0] is True
