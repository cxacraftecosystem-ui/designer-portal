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
import io
import json
import logging
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


# ==================================================================================================
# Which kind of link, and who may ask for one (2026-10-09)
# ==================================================================================================


def _account(**fields):
    from types import SimpleNamespace

    base = {
        "id": "acct-target",
        "email": "target@example.org",
        "role": "RESEARCHER",
        "passwordHash": "$2b$12$abcdefghijklmnopqrstuv",
        "firstLoginAt": datetime.now(UTC),
    }
    base.update(fields)
    return SimpleNamespace(**base)


#: Before and after ``credential_links.FIRST_LOGIN_TRACKED_SINCE`` (2026-08-30 17:00 UTC), when
#: ``firstLoginAt`` began to be written.
_BEFORE_THE_STAMP = datetime(2026, 7, 1, tzinfo=UTC)
_AFTER_THE_STAMP = datetime(2026, 10, 1, tzinfo=UTC)


@pytest.mark.parametrize(
    ("fields", "expected"),
    [
        # No password at all: an invitation, as it always was — however old the account.
        ({"passwordHash": None, "firstLoginAt": None}, credential_links.INVITE),
        (
            {"passwordHash": None, "firstLoginAt": None, "createdAt": _BEFORE_THE_STAMP},
            credential_links.INVITE,
        ),
        # A password a provisioner typed, never used: STILL an invitation. The old rule ("INVITE
        # while the hash is NULL") gave this account a two-hour link that died before lunch.
        ({"firstLoginAt": None, "createdAt": _AFTER_THE_STAMP}, credential_links.INVITE),
        (
            {"firstLoginAt": None, "createdAt": credential_links.FIRST_LOGIN_TRACKED_SINCE},
            credential_links.INVITE,
        ),
        # A hand-built row with no zone is read as the UTC every stored stamp is.
        ({"firstLoginAt": None, "createdAt": datetime(2026, 9, 1)}, credential_links.INVITE),  # noqa: DTZ001
        # BUT A NULL firstLoginAt ON AN ACCOUNT FROM BEFORE THE STAMP IS NO EVIDENCE OF ANYTHING —
        # it was in use all summer and has never been signed in since — so it is a reset, two hours,
        # not a three-day key to a live account. And no creation date at all is no evidence either.
        ({"firstLoginAt": None, "createdAt": _BEFORE_THE_STAMP}, credential_links.RESET),
        ({"firstLoginAt": None}, credential_links.RESET),
        # Somebody has signed in: the account is live, and a three-day key to it is too long.
        ({}, credential_links.RESET),
        ({"createdAt": _AFTER_THE_STAMP}, credential_links.RESET),
    ],
)
def test_the_link_purpose_follows_whether_anybody_has_used_the_account(fields, expected):
    assert credential_links.purpose_for(_account(**fields)) == expected


def _actor(role: str, user_id: str = "acct-actor"):
    return _account(id=user_id, email=f"{user_id}@example.org", role=role)


def test_nobody_may_reset_their_own_credentials_through_a_provisioning_door():
    """``POST /auth/change-password`` asks for the current password and spends a guessing budget; a
    link or a PATCH to oneself would skip both. Refused for every tier, the master admin included."""
    from fastapi import HTTPException

    from app.services.account_provisioning import (
        OWN_CREDENTIALS_DETAIL,
        assert_may_reset_credentials,
    )

    for role in ("MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"):
        actor = _actor(role)
        with pytest.raises(HTTPException) as refused:
            assert_may_reset_credentials(actor, actor)
        assert refused.value.status_code == 403
        assert refused.value.detail == OWN_CREDENTIALS_DETAIL


@pytest.mark.parametrize(
    ("actor_role", "target_role", "allowed"),
    [
        # THE TAKEOVER THIS CLOSED: an admin minting a link for the master admin, or for a peer.
        ("ADMIN", "MASTER_ADMIN", False),
        ("ADMIN", "ADMIN", False),
        # Master admins are peers who cannot manage each other.
        ("MASTER_ADMIN", "MASTER_ADMIN", False),
        ("MASTER_ADMIN", "ADMIN", True),
        # A ministry admin provisions strictly below itself, and never an admin.
        ("MINISTRY_ADMIN", "ADMIN", False),
        ("MINISTRY_ADMIN", "MINISTRY_ADMIN", False),
        ("MINISTRY_ADMIN", "REGIONAL_DIRECTOR", True),
        ("MINISTRY_ADMIN", "DESIGNER", True),
        # The tiers that may promote people and may not provision them.
        ("REGIONAL_DIRECTOR", "DESIGNER", False),
        ("ASSISTANT_DIRECTOR", "DESIGNER", False),
        ("PROFESSOR", "RESEARCHER", False),
    ],
)
def test_a_password_link_is_authorised_on_the_target(actor_role, target_role, allowed):
    from fastapi import HTTPException

    from app.services.account_provisioning import assert_may_reset_credentials

    target = _account(id="acct-other", role=target_role)
    if allowed:
        assert_may_reset_credentials(_actor(actor_role), target)
        return
    with pytest.raises(HTTPException) as refused:
        assert_may_reset_credentials(_actor(actor_role), target)
    assert refused.value.status_code == 403


@pytest.mark.parametrize(
    ("issuer_role", "target_role", "still"),
    [
        # Nobody issued it, or the issuer is gone: there is no reach to have outgrown.
        (None, "ADMIN", True),
        # A master admin always may, as the owner ruled — even a link for a fellow master admin.
        ("MASTER_ADMIN", "MASTER_ADMIN", True),
        ("MINISTRY_ADMIN", "RESEARCHER", True),
        # THE PROMOTION: the invitee is now at or above the ministry admin who invited them.
        ("MINISTRY_ADMIN", "MINISTRY_ADMIN", False),
        ("MINISTRY_ADMIN", "ADMIN", False),
        ("ADMIN", "MASTER_ADMIN", False),
        # A sanction officer's first-sign-in link: officers manage nobody by any other door, and
        # the rank comparison is the whole question here.
        ("ASSISTANT_DIRECTOR", "DESIGNER", True),
        ("ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", False),
    ],
)
def test_a_link_is_redeemable_while_its_issuer_could_still_manage_the_account(
    issuer_role, target_role, still
):
    from app.services.account_provisioning import issuer_still_manages

    issuer = None if issuer_role is None else _actor(issuer_role)
    assert issuer_still_manages(issuer, _account(id="acct-other", role=target_role)) is still


@pytest.mark.parametrize(
    ("issued_by", "issuer_role", "ok"),
    [
        (None, None, True),
        ("acct-issuer", "MASTER_ADMIN", True),
        ("acct-issuer", "MINISTRY_ADMIN", False),
        # An id with no row behind it reads as nobody: the foreign key nulls it on delete anyway.
        ("acct-gone", None, True),
    ],
)
async def test_a_link_whose_issuer_was_outranked_reads_as_withdrawn(
    monkeypatch, issued_by, issuer_role, ok
):
    """``auth._link_verdict``, which both the check and the redemption read: the account the link is
    for has been promoted to ADMIN, and the link was issued by ``issuer_role``."""
    from types import SimpleNamespace

    from app.api.routes import auth as auth_routes

    rows = {"acct-target": _account(id="acct-target", role="ADMIN")}
    if issuer_role is not None:
        rows["acct-issuer"] = _actor(issuer_role, user_id="acct-issuer")

    async def _describe(_raw):
        return credential_links.TokenVerdict(
            True, None, "acct-target", credential_links.INVITE, issued_by
        )

    class _Users:
        async def find_unique(self, where):
            return rows.get(where["id"])

    monkeypatch.setattr(auth_routes.credential_links, "describe_token", _describe)
    monkeypatch.setattr(auth_routes, "db", SimpleNamespace(user=_Users()))
    verdict = await auth_routes._link_verdict("a-link")  # noqa: SLF001
    assert verdict.ok is ok
    if not ok:
        assert verdict.reason == credential_links.REVOKED


@pytest.mark.parametrize(
    ("fields", "holds"),
    [
        # The flag on a password: the secret somebody else typed is still the one it signs in with.
        ({"mustChangePassword": True}, True),
        # No flag: the owner chose it, or a provisioner said it was final.
        ({"mustChangePassword": False}, False),
        ({}, False),
        # A flag with no password behind it holds nothing, so there is nothing to carry upward.
        ({"mustChangePassword": True, "passwordHash": None}, False),
    ],
)
def test_a_temporary_password_is_the_flag_on_an_account_that_has_one(fields, holds):
    """Rule 5's question, asked by both doors that raise a role — ``PATCH /api/users/{id}`` and the
    access screen's approval — through one predicate, so the two cannot come to mean two things."""
    from app.services.account_provisioning import holds_a_temporary_password

    assert holds_a_temporary_password(_account(**fields)) is holds


@pytest.mark.parametrize(
    ("fields", "granted", "lifted"),
    [
        # The ordinary approval of somebody who has an account: lifted, and their links withdrawn.
        ({"role": "RESEARCHER"}, "PROFESSOR", True),
        # A flag with no password behind it is no temporary password: lifted like anybody else.
        ({"role": "RESEARCHER", "mustChangePassword": True, "passwordHash": None}, "PROFESSOR", True),
        # Never a demotion, and an approval at the tier the account already holds changes nothing —
        # so neither holds anything back or withdraws anything, flag or no flag.
        ({"role": "PROFESSOR", "mustChangePassword": True}, "RESEARCHER", False),
        ({"role": "RESEARCHER", "mustChangePassword": True}, "RESEARCHER", False),
    ],
)
async def test_the_access_approval_lifts_an_account_and_withdraws_its_links(
    monkeypatch, fields, granted, lifted
):
    """``routes/access._lift_existing_account``, the second door onto a promotion: a lift withdraws
    every outstanding password link AFTER its write, as ``PATCH /api/users/{id}`` does, and answers
    ``None`` — there is nothing for the approval's answer to say."""
    held, written, withdrawn = await _lift(monkeypatch, _account(**fields), granted)
    assert held is None
    assert written == ([{"role": granted}] if lifted else [])
    assert withdrawn == (["acct-target"] if lifted else [])


async def test_the_access_approval_leaves_a_temporary_password_at_its_tier(monkeypatch):
    """Rule 5 at the access screen, which has no password field to set a new one in: the account
    keeps its tier, NOTHING is written to it and no link is touched, and the answer says so — the
    approved address, both tiers by their labels, and the two ways on, the second on the website's
    Users page."""
    from app.services.account_provisioning import APPROVAL_KEEPS_THE_TIER_DETAIL

    flagged = _account(role="RESEARCHER", mustChangePassword=True)
    held, written, withdrawn = await _lift(monkeypatch, flagged, "PROFESSOR")
    assert (written, withdrawn) == ([], [])
    assert held == APPROVAL_KEEPS_THE_TIER_DETAIL.format(
        email="target@example.org", current="Researcher", granted="Professor"
    )
    assert "Users page" in held


async def _lift(monkeypatch, account, granted):
    """Drive ``_lift_existing_account`` over one account; answers (its return, the account writes,
    the accounts whose links were withdrawn)."""
    from types import SimpleNamespace

    from app.api.routes import access as access_routes

    written: list[dict] = []
    withdrawn: list[str] = []

    class _Users:
        async def find_unique(self, where):
            return account if where == {"email": account.email} else None

        async def update(self, where, data):
            assert where == {"id": account.id}
            written.append(data)
            return account

    async def _revoke(user_id):
        assert written, "links were withdrawn before the account was lifted"
        withdrawn.append(user_id)
        return 1

    monkeypatch.setattr(access_routes, "db", SimpleNamespace(user=_Users()))
    monkeypatch.setattr(access_routes, "invalidate_cached_user", lambda _user_id: None)
    monkeypatch.setattr(access_routes.credential_links, "revoke_outstanding", _revoke)
    held = await access_routes._lift_existing_account(account.email, granted)  # noqa: SLF001
    return held, written, withdrawn


def test_the_master_admins_mailbox_is_every_spelling_of_it_and_its_account_only_one(monkeypatch):
    """Nobody but a master admin creates or moves an account onto ANY spelling of the configured
    master's mailbox (``is_master_email``); the account that carries the role, cannot be deleted and
    is protected is the one at the configured address itself (``is_master_address``)."""
    from app.core.config import get_settings
    from app.services.account_provisioning import is_master_address, is_master_email

    monkeypatch.setattr(get_settings(), "master_admin_email", "New.Master@gmail.com")
    for spelling in (
        "new.master@gmail.com",
        "newmaster@gmail.com",
        "newmaster+handover@googlemail.com",
        "NEW.MASTER@GMAIL.COM",
    ):
        assert is_master_email(spelling), spelling
    assert not is_master_email("new.master2@gmail.com")
    assert is_master_address("new.master@gmail.com")
    assert is_master_address(" NEW.MASTER@gmail.com ")
    assert not is_master_address("newmaster@gmail.com")

    monkeypatch.setattr(get_settings(), "master_admin_email", "")
    assert not is_master_email("")
    assert not is_master_email("anybody@gmail.com")
    assert not is_master_address("")


def test_only_an_admin_may_move_a_capability_flag():
    """A ministry admin provisions accounts and grants nothing: a flag that would CHANGE is a 403,
    while an echo of the value the account already holds grants nothing and is let through."""
    from fastapi import HTTPException

    from app.services.account_provisioning import assert_may_grant

    ministry = _actor("MINISTRY_ADMIN")
    with pytest.raises(HTTPException) as refused:
        assert_may_grant(ministry, {"canReview": True})
    assert refused.value.status_code == 403
    assert_may_grant(ministry, {"canReview": False, "canDownloadDataset": False})
    held = _account(canReview=True)
    assert_may_grant(ministry, {"canReview": True}, current=held)
    with pytest.raises(HTTPException):
        assert_may_grant(ministry, {"canReview": False}, current=held)
    assert_may_grant(_actor("ADMIN"), {"canReview": True, "canDownloadDataset": True})


# ==================================================================================================
# One password ceiling, and a sign-in that cannot 500 on a long one
# ==================================================================================================


def test_every_password_field_shares_one_ceiling():
    """A temporary password an administrator can set must be one the change-password form can take
    back as ``currentPassword``; 256 on the admin forms and 200 on the others broke that."""
    from pydantic import ValidationError

    from app.core.security import MAX_PASSWORD_LENGTH
    from app.schemas.auth import ChangePasswordRequest, SetPasswordRequest
    from app.schemas.users import UserCreate, UserUpdate

    assert MAX_PASSWORD_LENGTH == 200
    at_limit = "p" * MAX_PASSWORD_LENGTH
    over = "p" * (MAX_PASSWORD_LENGTH + 1)
    body = {"email": "a@example.org", "name": "A"}
    assert UserCreate(**body, password=at_limit).password == at_limit
    assert UserUpdate(password=at_limit).password == at_limit
    assert ChangePasswordRequest(currentPassword=at_limit, newPassword=at_limit)
    assert SetPasswordRequest(token="t", password=at_limit)
    for build in (
        lambda: UserCreate(**body, password=over),
        lambda: UserUpdate(password=over),
        lambda: ChangePasswordRequest(currentPassword=over, newPassword="long-enough"),
        lambda: ChangePasswordRequest(currentPassword="x", newPassword=over),
        lambda: SetPasswordRequest(token="t", password=over),
    ):
        with pytest.raises(ValidationError):
            build()


def test_the_password_is_kept_exactly_as_typed_and_the_flag_defaults_on():
    from app.schemas.users import UserCreate, UserUpdate

    created = UserCreate(email="a@example.org", name="A", password="  spaced out  ")
    assert created.password == "  spaced out  ", "a password must never be trimmed"
    assert created.mustChangePassword is True
    assert UserUpdate().mustChangePassword is None


def test_the_users_audit_line_can_name_every_field_a_patch_accepts():
    """``update_user``'s audit line picks a changed field's name from ``AUDITED_FIELDS``, a tuple of
    literals, so every word it prints about a password is one the module wrote. A field the PATCH
    accepts and the tuple lacks would vanish from that line without a sound; and the order is the
    one ``sorted`` gave the line when it joined the request's keys, so it reads as it always did."""
    from app.api.routes import users
    from app.schemas.users import UserUpdate

    assert tuple(sorted(UserUpdate.model_fields)) == users.AUDITED_FIELDS


@pytest.mark.parametrize(
    ("found", "expected"),
    [
        # Nobody under another spelling: a new Google account, as before.
        ([], None),
        # A Google account under another spelling is not folded in — LOCAL only, as ruled.
        (["GOOGLE"], None),
        # Exactly one password account on the mailbox: that is the account signed in to.
        (["LOCAL"], 0),
        (["GOOGLE", "LOCAL"], 1),
        # The sweep could not answer: fall back to the old behaviour rather than lock people out.
        (None, None),
    ],
)
async def test_google_signs_in_to_the_one_password_account_on_its_mailbox(
    monkeypatch, found, expected
):
    """D4, the matching rule alone. The database half — the fold over real rows — is
    ``tests/test_account_provisioning.py``; this pins which of the candidates is chosen."""
    from types import SimpleNamespace

    from app.api.routes import auth as auth_routes

    accounts = (
        None
        if found is None
        else [SimpleNamespace(id=f"acct-{n}", authProvider=kind) for n, kind in enumerate(found)]
    )

    async def _on_the_mailbox(_email):
        return accounts

    monkeypatch.setattr(
        auth_routes.access_roster, "accounts_on_the_mailbox_for_sign_in", _on_the_mailbox
    )
    chosen = await auth_routes._local_account_on_the_mailbox("someone@gmail.com")  # noqa: SLF001
    assert chosen is (None if expected is None else accounts[expected])


async def test_two_password_accounts_on_one_mailbox_refuse_the_google_sign_in(monkeypatch):
    from types import SimpleNamespace

    from fastapi import HTTPException

    from app.api.routes import auth as auth_routes

    async def _two(_email):
        return [
            SimpleNamespace(id="a", authProvider="LOCAL"),
            SimpleNamespace(id="b", authProvider="LOCAL"),
        ]

    monkeypatch.setattr(auth_routes.access_roster, "accounts_on_the_mailbox_for_sign_in", _two)
    with pytest.raises(HTTPException) as refused:
        await auth_routes._local_account_on_the_mailbox("someone@gmail.com")  # noqa: SLF001
    assert refused.value.status_code == 409
    assert refused.value.detail == auth_routes.GOOGLE_AMBIGUOUS_ACCOUNT_DETAIL


async def test_the_seed_script_resets_only_the_master_admin_and_every_password_it_writes_is_temporary(
    monkeypatch, capsys
):
    """``scripts/seed_admin.py``'s decision of 2026-10-09, pinned without a database.

    Its password comes from ``ADMIN_PASSWORD`` in a ``.env`` several people can open, so every hash it
    writes is stamped ``passwordSetAt`` and ``mustChangePassword``; resetting an EXISTING master admin
    also ends that account's sessions. And it refuses to touch any other existing account: re-running
    it used to overwrite a live colleague's password and role as a side effect of repairing the
    break-glass.
    """
    from types import SimpleNamespace

    from scripts import seed_admin

    writes: list[tuple[str, str, dict]] = []
    admitted: list[str] = []
    rows = {
        "admin@example.org": SimpleNamespace(id="acct-admin", email="admin@example.org"),
        "master@example.org": SimpleNamespace(id="acct-master", email="master@example.org"),
    }

    class _Users:
        async def find_unique(self, where):
            return rows.get(where["email"])

        async def update(self, where, data):
            writes.append(("update", where["email"], data))
            return rows[where["email"]]

        async def create(self, data):
            writes.append(("create", data["email"], data))
            return SimpleNamespace(id="acct-new", **data)

    async def _admit(email, **_kwargs):
        admitted.append(email)

    monkeypatch.setattr(seed_admin, "db", SimpleNamespace(user=_Users()))
    monkeypatch.setattr(seed_admin.access_roster, "admit", _admit)

    await seed_admin.upsert_admin("admin@example.org", "Admin", "seed-secret-value", "ADMIN")
    assert writes == [] and admitted == [], "an existing non-master account was rewritten"

    await seed_admin.upsert_admin("master@example.org", "Master", "seed-secret-value", "MASTER_ADMIN")
    kind, _email, data = writes[-1]
    assert kind == "update"
    assert data["mustChangePassword"] is True
    assert data["passwordSetAt"] is not None
    assert data["sessionsValidFrom"] is not None

    await seed_admin.upsert_admin("fresh@example.org", "Fresh", "seed-secret-value", "ADMIN")
    kind, _email, data = writes[-1]
    assert kind == "create"
    assert data["mustChangePassword"] is True
    assert data["passwordSetAt"] is not None
    assert admitted == ["master@example.org", "fresh@example.org"]
    assert "seed-secret-value" not in capsys.readouterr().out


def test_a_password_too_long_to_check_is_simply_wrong():
    """passlib raises rather than answering for anything over its 4096-character cap, and the sign-in
    and dataset-token bodies are unbounded on purpose. A megabyte pasted at the front door must be a
    wrong password, not a 500."""
    from app.core.security import hash_password, verify_password

    stored = hash_password("the-real-one")
    assert verify_password("x" * 5000, stored) is False
    assert verify_password("the-real-one", stored) is True


# ==================================================================================================
# The master admin's mailbox, a bar that stays where it was, and one account per mailbox (2026-10-09)
# ==================================================================================================


@pytest.mark.parametrize(
    ("role", "password_hash", "promoted"),
    [
        # A password account somebody else set up at the master's address: never promoted.
        ("DESIGNER", "$2b$12$somebody.elses.password.hash", False),
        ("ADMIN", "$2b$12$somebody.elses.password.hash", False),
        # Already the master admin, seeded with a password or not: the master signing in again.
        ("MASTER_ADMIN", "$2b$12$the.masters.own.password.hash", True),
        # No password at all: only a Google sign-in on this very mailbox made it.
        ("RESEARCHER", None, True),
    ],
)
async def test_the_masters_google_sign_in_promotes_no_account_somebody_else_holds_a_password_to(
    monkeypatch, caplog, role, password_hash, promoted
):
    """R4's defence in depth, without a database. The master's first Google sign-in used to write
    MASTER_ADMIN onto whatever account sat at the literal address — with the password whoever made it
    chose still on it. Now an account that is not already a master admin and holds a password is
    refused (409, naming ``scripts/seed_admin.py``), logged at ERROR, and NOTHING is written to it,
    not even the avatar."""
    import logging
    from types import SimpleNamespace

    from fastapi import HTTPException

    from app.api.routes import auth as auth_routes
    from app.core.config import get_settings

    master = "the.master@example.org"
    monkeypatch.setattr(get_settings(), "master_admin_email", master)
    monkeypatch.setattr(
        auth_routes,
        "verify_google_token",
        lambda _token: {"email": master, "email_verified": True, "name": "The Master"},
    )

    async def _admits(email, *, is_master):
        assert is_master, "the configured master was gated by the allow-list"

    async def _not_rostered(_email):
        return False

    monkeypatch.setattr(auth_routes, "assert_access_admits", _admits)
    monkeypatch.setattr(auth_routes, "roster_allows", _not_rostered)
    monkeypatch.setattr(auth_routes, "invalidate_cached_user", lambda _user_id: None)

    planted = SimpleNamespace(
        id="acct-at-the-masters-address",
        email=master,
        role=role,
        passwordHash=password_hash,
        authProvider="LOCAL" if password_hash else "GOOGLE",
    )
    writes: list[dict] = []

    class _Users:
        async def find_unique(self, where):
            return planted if where == {"email": master} else None

        async def update(self, where, data):
            assert where == {"id": planted.id}
            writes.append(data)
            return SimpleNamespace(**{**vars(planted), **data})

        async def create(self, data):
            raise AssertionError("the master's sign-in created a second account beside the first")

    monkeypatch.setattr(auth_routes, "db", SimpleNamespace(user=_Users()))

    if promoted:
        user, _access = await auth_routes.login_with_google("stand-in-for-a-real-token")
        assert [data.get("role") for data in writes] == ["MASTER_ADMIN"]
        assert user.role == "MASTER_ADMIN"
        return
    with (
        caplog.at_level(logging.ERROR, logger=auth_routes.logger.name),
        pytest.raises(HTTPException) as refused,
    ):
        await auth_routes.login_with_google("stand-in-for-a-real-token")
    assert refused.value.status_code == 409
    assert refused.value.detail == auth_routes.MASTER_ADDRESS_HOLDS_A_PASSWORD_ACCOUNT_DETAIL
    assert "scripts/seed_admin.py" in refused.value.detail
    assert writes == [], "the refused account was written to anyway"
    logged = [r for r in caplog.records if r.levelno == logging.ERROR]
    assert logged and "scripts/seed_admin.py" in logged[-1].getMessage()
    assert planted.id in logged[-1].getMessage()
    assert "somebody.elses" not in logged[-1].getMessage(), "a password hash reached the log"


def _roster_row(email: str, status: str, **columns):
    from types import SimpleNamespace

    blank = dict.fromkeys(("admitRole", "fullName", "notes", "decidedAt", "decidedById"))
    return SimpleNamespace(id=f"row-{email}", email=email, status=status, **{**blank, **columns})


async def _follow(monkeypatch, rows, *, racing=None):
    """Drive ``access_roster.follow_email_change`` over fake rows. ``rows`` is what ``access_row``
    answers per address; ``racing`` is a row a sign-in writes at the new mailbox just before the
    create, which the create then collides with. Answers (the move, the creates, the updates)."""
    from types import SimpleNamespace

    from prisma.errors import UniqueViolationError

    from app.services import access_roster

    answers = dict(rows)
    created: list[dict] = []
    updated: list[tuple[str, dict]] = []

    async def _access_row(email):
        return answers.get(access_roster.canonical_email(email))

    async def _carry(*_args, **_kwargs):
        return False

    class _Rows:
        async def create(self, data):
            if racing is not None:
                answers[racing.email] = racing
                raise UniqueViolationError({"user_facing_error": {"message": "email taken"}})
            created.append(data)
            return SimpleNamespace(id="row-created", **data)

        async def update(self, where, data):
            updated.append((where["id"], data))
            return SimpleNamespace(id=where["id"], **data)

    monkeypatch.setattr(access_roster, "access_row", _access_row)
    monkeypatch.setattr(access_roster, "carry_ended_empanelment", _carry)
    monkeypatch.setattr(access_roster, "db", SimpleNamespace(accessroster=_Rows()))
    moved = await access_roster.follow_email_change(
        "barred.person@gmail.com", "fresh@example.org", actor_id="acct-mover"
    )
    return moved, created, updated


async def test_a_barred_row_is_created_at_a_fresh_mailbox_and_the_old_one_stays_barred(monkeypatch):
    """SEC1. The barred row used to MOVE to the new mailbox, leaving the old one with no row: a
    Google sign-in there was a stranger's, queued PENDING, and an approval let the barred person back
    in. Now the bar is CREATED at the new mailbox — status, who decided and when, the tier and the
    name — and the old row is not written at all."""
    from datetime import UTC, datetime

    from app.services import access_roster

    decided = datetime(2026, 9, 1, tzinfo=UTC)
    old = _roster_row(
        "barredperson@gmail.com",
        "SUSPENDED",
        admitRole="RESEARCHER",
        fullName="Barred Person",
        decidedAt=decided,
        decidedById="acct-the-admin-who-barred",
        notes="Suspended after the audit.",
    )
    moved, created, updated = await _follow(monkeypatch, {"barredperson@gmail.com": old})
    assert moved == access_roster.EmailMove("SUSPENDED", False)
    assert updated == [], "the barred row was moved off the old mailbox"
    assert created == [
        {
            "email": "fresh@example.org",
            "status": "SUSPENDED",
            "decidedAt": decided,
            "decidedById": "acct-the-admin-who-barred",
            "admitRole": "RESEARCHER",
            "fullName": "Barred Person",
            "notes": access_roster.BAR_CARRIED_BY_EMAIL_MOVE_NOTE,
        }
    ]


async def test_a_row_a_sign_in_writes_in_the_race_takes_the_bar_instead(monkeypatch):
    """The create collides with a row a refused Google sign-in queued at the new mailbox a moment
    earlier. That row is the one the gate reads, so it takes the bar — the arm a destination that was
    already there goes through — and the old row is still untouched."""
    from app.services import access_roster

    old = _roster_row("barredperson@gmail.com", "REJECTED", decidedById=None)
    plant = _roster_row("fresh@example.org", "PENDING", notes="Asked to join.")
    moved, created, updated = await _follow(
        monkeypatch, {"barredperson@gmail.com": old}, racing=plant
    )
    assert moved.carried_bar == "REJECTED"
    assert created == []
    [(row_id, data)] = updated
    assert row_id == plant.id, "the bar went somewhere other than the row the gate reads"
    assert data["status"] == "REJECTED"
    assert data["decidedById"] == "acct-mover", "the mover is named only where nobody was"
    assert data["notes"].startswith("Asked to join.")
    assert access_roster.BAR_CARRIED_BY_EMAIL_MOVE_NOTE in data["notes"]


async def test_one_account_per_mailbox_is_asked_of_gmail_alone_and_never_of_the_account_moving(
    monkeypatch,
):
    """SEC1/F3. ``email_in_use`` asks the literal address first and, only on a Gmail domain, every
    spelling of the mailbox — the account being moved excluded, and a lookup that could not finish
    refused (503) rather than read as "free"."""
    from types import SimpleNamespace

    from fastapi import HTTPException

    from app.services import account_provisioning as provisioning

    literal: list[dict] = []
    folded: list[str] = []
    on_the_mailbox: dict[str, list | None] = {
        "aperson@gmail.com": [SimpleNamespace(id="acct-owner")],
        "cut@gmail.com": None,
    }

    class _Users:
        async def find_first(self, where):
            literal.append(where)

    async def _fold(email):
        folded.append(email)
        return on_the_mailbox.get(provisioning.canonical_email(email), [])

    monkeypatch.setattr(provisioning, "db", SimpleNamespace(user=_Users()))
    monkeypatch.setattr(provisioning.access_roster, "accounts_on_the_mailbox_for_sign_in", _fold)

    assert await provisioning.email_in_use("a.person+x@gmail.com") is True
    assert await provisioning.email_in_use("APerson@googlemail.com", except_id="acct-mover") is True
    assert await provisioning.email_in_use("a.person@gmail.com", except_id="acct-owner") is False
    assert await provisioning.email_in_use("a.person@example.org") is False
    assert folded == ["a.person+x@gmail.com", "APerson@googlemail.com", "a.person@gmail.com"], (
        "a non-Gmail address was swept, or a Gmail one was not"
    )
    assert len(literal) == 4, "the literal question stopped being asked first"
    with pytest.raises(HTTPException) as cut:
        await provisioning.email_in_use("cut@gmail.com")
    assert cut.value.status_code == 503
    assert cut.value.detail == provisioning.MAILBOX_UNCHECKED_DETAIL


@pytest.mark.parametrize(
    ("actor_role", "role", "refused"),
    [
        # A ministry admin, whatever the account's role: the move would end an active empanelment.
        ("MINISTRY_ADMIN", "PROFESSOR", True),
        ("MINISTRY_ADMIN", "RESEARCHER", True),
        # An admin's move carries the ending onto it, as documented.
        ("ADMIN", "PROFESSOR", False),
        ("MASTER_ADMIN", "RESEARCHER", False),
    ],
)
async def test_only_an_admin_carries_an_ended_empanelment_onto_an_active_one(
    monkeypatch, actor_role, role, refused
):
    """SEC1/F3. ``follow_email_change`` carries an ended empanelment to the new mailbox whatever the
    account's role, and carrying it ENDS an active empanelment there. Ending one is an admin's act."""
    from fastapi import HTTPException

    from app.services import account_provisioning as provisioning

    async def _not_barred(_email):
        return None

    async def _ended(email):
        return email == "ended@example.org"

    async def _active(email):
        return email == "empanelled@example.org"

    monkeypatch.setattr(provisioning, "barred_status", _not_barred)
    monkeypatch.setattr(provisioning, "empanelment_suspended", _ended)
    monkeypatch.setattr(provisioning, "empanelment_active", _active)
    actor = _actor(actor_role)

    async def move(new_email):
        return await provisioning.assert_not_escaping_a_bar(
            actor, "ended@example.org", new_email, role=role
        )

    # Onto an address with no active empanelment nothing active is ended, so nobody is refused.
    assert await move("nobody@example.org") == (None, False)
    if not refused:
        assert await move("empanelled@example.org") == (None, False)
        return
    with pytest.raises(HTTPException) as stopped:
        await move("empanelled@example.org")
    assert stopped.value.status_code == 409
    assert stopped.value.detail == provisioning.ENDING_AN_EMPANELMENT_BY_MOVING_DETAIL.format(
        email="ended@example.org", destination="empanelled@example.org"
    )


# ==================================================================================================
# What a credential leaves behind in a log or on a terminal (2026-10-09)
# ==================================================================================================

#: Stands in for a set-password link's token — the shape ``credential_links.mint_token`` makes, and
#: distinctive, so finding it in a line is unambiguous.
LINK_TOKEN = "eyJzdWIiOiJhY2N0LTA0NTEifQ.Link-Token-Signature-0451"


def _uvicorn_access_record(path: str, query: bytes) -> logging.LogRecord:
    """The record uvicorn's access logger makes for one request: its own helpers build the arguments,
    in the order and under the format string ``httptools_impl`` and ``h11_impl`` pass to ``info``."""
    from uvicorn.protocols.utils import get_client_addr, get_path_with_query_string

    scope = {
        "client": ("203.0.113.7", 51234),
        "method": "GET",
        "path": path,
        "query_string": query,
        "http_version": "1.1",
    }
    arguments = (
        get_client_addr(scope),
        scope["method"],
        get_path_with_query_string(scope),
        scope["http_version"],
        200,
    )
    return logging.LogRecord(
        "uvicorn.access", logging.INFO, __file__, 0, '%s - "%s %s HTTP/%s" %d', arguments, None
    )


def _access_formatter() -> logging.Formatter:
    """uvicorn's access formatter, with the format uvicorn ships: the line as production writes it."""
    from uvicorn.config import LOGGING_CONFIG
    from uvicorn.logging import AccessFormatter

    return AccessFormatter(LOGGING_CONFIG["formatters"]["access"]["fmt"], use_colors=False)


def test_the_access_line_for_a_link_check_never_carries_the_token():
    """THE FINDING: both clients check a link with ``GET /api/auth/set-password?token=…``, and
    uvicorn's access log wrote that line, token and all, into the service's journal."""
    from app.main import AccessLogRedaction

    record = _uvicorn_access_record("/api/auth/set-password", f"token={LINK_TOKEN}".encode())
    assert AccessLogRedaction().filter(record) is True
    line = _access_formatter().format(record)
    assert LINK_TOKEN not in line
    assert line.endswith(
        '203.0.113.7:51234 - "GET /api/auth/set-password?token=[redacted] HTTP/1.1" 200 OK'
    )


@pytest.mark.parametrize(
    ("path", "query", "logged"),
    [
        # Every name on the list, in any letter case: the value goes, the name and its place stay.
        ("/api/auth/set-password", b"Token=abc", "/api/auth/set-password?Token=[redacted]"),
        (
            "/api/x",
            b"page=2&access_token=a&ID_TOKEN=b&refresh_token=c&code=d&key=e&Password=f&secret=g",
            "/api/x?page=2&access_token=[redacted]&ID_TOKEN=[redacted]&refresh_token=[redacted]"
            "&code=[redacted]&key=[redacted]&Password=[redacted]&secret=[redacted]",
        ),
        # A name Starlette decodes to "token" is the token to the route, so it is to the log too.
        ("/api/auth/set-password", b"%74oken=abc", "/api/auth/set-password?%74oken=[redacted]"),
        # Exact names: a parameter that merely contains one keeps its value.
        (
            "/api/artisans",
            b"pageKey=7&tokenCount=3&monkey=1",
            "/api/artisans?pageKey=7&tokenCount=3&monkey=1",
        ),
        # Nothing to hide: an empty value, a bare name, ordinary parameters, no query at all.
        ("/api/auth/set-password", b"token=&token", "/api/auth/set-password?token=&token"),
        ("/api/artisans", b"page=2&pageSize=50", "/api/artisans?page=2&pageSize=50"),
        ("/api/auth/set-password", b"", "/api/auth/set-password"),
    ],
)
def test_only_a_credentials_value_leaves_the_access_line(path, query, logged):
    from app.main import AccessLogRedaction

    record = _uvicorn_access_record(path, query)
    client, method, _path, http_version, status_code = record.args
    AccessLogRedaction().filter(record)
    assert record.args == (client, method, logged, http_version, status_code)


@pytest.mark.parametrize(
    "args",
    [
        None,
        {"path": f"/api/auth/set-password?token={LINK_TOKEN}"},
        ("GET", f"/api/auth/set-password?token={LINK_TOKEN}"),
        ("203.0.113.7:51234", "GET", b"/api/auth/set-password?token=x", "1.1", 200),
    ],
)
def test_a_record_of_any_other_shape_passes_the_filter_untouched(args):
    """Not uvicorn's access line, so not the filter's to touch: passed on exactly as it came."""
    from app.main import AccessLogRedaction

    record = logging.LogRecord("uvicorn.access", logging.INFO, __file__, 0, "%s", None, None)
    record.args = args
    assert AccessLogRedaction().filter(record) is True
    assert record.args is args


def test_the_filter_never_raises_over_a_line_it_cannot_read(monkeypatch):
    """The filter runs inside the call that starts uvicorn's response, so an exception there would
    fail the request the line describes. Whatever goes wrong, the line passes on as it came."""
    import app.main

    def _unreadable(_path):
        raise RuntimeError("a path the filter cannot read")

    monkeypatch.setattr(app.main, "redact_query_credentials", _unreadable)
    record = _uvicorn_access_record("/api/auth/set-password", b"token=abc")
    arguments = record.args
    assert app.main.AccessLogRedaction().filter(record) is True
    assert record.args is arguments


def test_importing_the_application_puts_the_filter_on_uvicorns_access_logger():
    """WHAT PRODUCTION RUNS: uvicorn imports ``app.main``, then logs through the logger it looks up by
    name. A filter that worked only where somebody remembered to attach it would leave the finding
    open, so the line goes through that logger here, into uvicorn's own formatter."""
    import app.main

    access = logging.getLogger("uvicorn.access")
    assert any(isinstance(each, app.main.AccessLogRedaction) for each in access.filters)

    written = io.StringIO()
    handler = logging.StreamHandler(written)
    handler.setFormatter(_access_formatter())
    level = access.level
    access.addHandler(handler)
    access.setLevel(logging.INFO)
    try:
        # The call uvicorn makes, argument for argument.
        access.info(
            '%s - "%s %s HTTP/%s" %d',
            "203.0.113.7:51234",
            "GET",
            f"/api/auth/set-password?token={LINK_TOKEN}",
            "1.1",
            200,
        )
    finally:
        access.removeHandler(handler)
        access.setLevel(level)
    assert LINK_TOKEN not in written.getvalue()
    assert '"GET /api/auth/set-password?token=[redacted] HTTP/1.1" 200 OK' in written.getvalue()


def test_a_refused_google_credential_is_never_written_to_the_log(monkeypatch, caplog):
    """google-auth's messages quote what they refuse: ``decode_header`` raises exactly what
    ``verify_oauth2_token`` raises for a token with the wrong number of segments, the whole credential
    inside it. The line names the audience and the exception's class, and nothing that was sent."""
    from types import SimpleNamespace

    from fastapi import HTTPException
    from google.auth import jwt as google_jwt

    from app.api.routes import auth as auth_routes

    pasted = "Pasted-Into-The-Google-Field-0451"
    monkeypatch.setattr(
        auth_routes,
        "get_settings",
        lambda: SimpleNamespace(google_client_ids=["web-client", "android-client"]),
    )
    monkeypatch.setattr(
        auth_routes.google_id_token,
        "verify_oauth2_token",
        lambda token, _request, _audience: google_jwt.decode_header(token),
    )
    with (
        caplog.at_level(logging.INFO, logger=auth_routes.logger.name),
        pytest.raises(HTTPException) as refused,
    ):
        auth_routes.verify_google_token(pasted)
    assert refused.value.status_code == 401
    assert pasted not in caplog.text
    assert [
        record.getMessage() for record in caplog.records if record.name == auth_routes.logger.name
    ] == [
        f"Google token rejected for configured audience {audience}: it did not verify "
        "(MalformedError)"
        for audience in ("web-client", "android-client")
    ]


#: What an operator typed where a value goes. Distinctive, so an assertion that it never comes back
#: cannot pass by accident; shaped like the password it most often is.
TYPED = "Typed-On-The-Command-Line-0451"


def _provision_argv(*extra: str, role: str = "RESEARCHER") -> list[str]:
    return [
        "--email", "person@example.org", "--name", "A Person", "--role", role,
        "--actor-email", "admin@example.org", *extra,
    ]


@pytest.mark.parametrize(
    "argv",
    [
        # An abbreviation two options share, carrying a value: argparse quoted the whole argument.
        _provision_argv(f"--a={TYPED}"),
        # A choice the option does not offer: argparse quoted the value back with the choices.
        _provision_argv(role=TYPED),
        # A value given to a flag that takes none.
        _provision_argv(f"--apply={TYPED}"),
        # The shape the script first guarded against: a password passed as an argument.
        _provision_argv("--password", TYPED),
    ],
)
async def test_the_provisioning_script_never_repeats_what_was_typed(argv):
    """``scripts/provision_account.py`` reads its password from ``PROVISION_PASSWORD`` only, and
    argparse quotes a typed value back in most of its complaints; until 2026-10-09 the script caught
    only the unrecognised argument. Refused before anything connects, so no database is needed."""
    from scripts import provision_account

    out, err = io.StringIO(), io.StringIO()
    code = await provision_account.run(argv, environ={}, out=out, err=err)
    assert code == provision_account.EXIT_USAGE
    assert TYPED not in out.getvalue() + err.getvalue()
    assert err.getvalue().endswith(f"error: {provision_account.UNREADABLE_ARGUMENTS}\n")


@pytest.mark.parametrize(
    ("argv", "complaint"),
    [
        (
            ["--email", "person@example.org"],
            "the following arguments are required: --name, --role, --actor-email",
        ),
        (_provision_argv("--actor-email"), "argument --actor-email: expected one argument"),
    ],
)
async def test_the_provisioning_script_still_names_the_option_at_fault(argv, complaint):
    """A complaint that names nothing but the script's own options is repeated word for word: it is
    the one that tells an operator what to fix, and it quotes nothing they typed."""
    from scripts import provision_account

    out, err = io.StringIO(), io.StringIO()
    code = await provision_account.run(argv, environ={}, out=out, err=err)
    assert code == provision_account.EXIT_USAGE
    assert err.getvalue().endswith(f"error: {complaint}\n")
