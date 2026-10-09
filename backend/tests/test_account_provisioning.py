"""ACCOUNT PROVISIONING, against a real database: who may create, correct and reset whose account.

The owner's decision of 2026-10-09, end to end through the endpoints that carry it:

1. **THREE TIERS PROVISION, AND ONLY THREE.** MINISTRY_ADMIN, ADMIN and MASTER_ADMIN create password
   accounts that sign in at once; the two directorate tiers below them and PROFESSOR are refused.
2. **THE CEILING AND THE TARGET STAY AS THEY WERE.** A provisioner creates at its own tier and below,
   and touches nobody at or above it — master admins included, who are peers.
3. **WHAT ONLY AN ADMIN DOES STAYS AN ADMIN'S.** A ministry admin may not grant a capability flag or
   overturn an administrator's barring of an address; an admin still may.
4. **THE FLAG.** On at first sign-in unless the provisioner says otherwise; raisable on an existing
   account without touching its password; never on one's own account; never on an account with no
   password; and raising it, or setting somebody's password, ends that person's sessions.
5. **PASSWORD LINKS ARE AUTHORISED ON THE TARGET.** The takeover this closed: any ADMIN could mint a
   link for a MASTER_ADMIN or a peer and own the account. A link is an invitation until the account
   has been used, and cannot be redeemed with the temporary password it exists to replace.
6. **GOOGLE ON A PASSWORD ACCOUNT** keeps the password, the LOCAL provider and the admin-typed name,
   finds the account under another spelling of the mailbox, and refuses rather than guesses when two
   accounts share one — with Postgres doing the Gmail fold, whose answer is held equal to the sweep
   it replaced on the sign-in path. Never for the master admin's mailbox, which nobody but a master
   admin may create or move an account onto under any spelling.
7. **THE OPERATOR SCRIPT** (``scripts/provision_account.py``) obeys the same rules, in both modes.
8. **A BAR STAYS WITH THE ACCOUNT, AND A PROMOTION DOES NOT CARRY A CREDENTIAL UPWARD** (review of
   2026-10-09). A ministry admin cannot move a barred account off its address; an admin can, and the
   bar goes with it. A promotion withdraws the account's links and waits for a temporary password to
   be replaced. A provisioner may withdraw a required change; a ministry admin deletes nobody.
9. **AND THE BAR STAYS ON THE OLD MAILBOX TOO; ONE ACCOUNT PER MAILBOX** (section 14). An admin's
   correction creates the bar at the new address and leaves the old one barred, so a Google sign-in
   there is still refused rather than queued. Nobody creates an account on, or moves one onto, any
   spelling of a Gmail mailbox another account uses; and a ministry admin cannot carry an ended
   empanelment onto an address with an active one, whatever the account's role.

Run the local stack first:

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

THE SHAPE IS ``tests/test_workshop_join_sync.py``'s: rows are seeded in a private event loop, the
script scenarios run in private loops of their own (the script opens and closes the shared client
itself), and only then does the TestClient start — so no database call is ever made while a client is
alive. Tests are sync and talk HTTP.

THE ONE-SECOND WAIT IS DELIBERATE. ``create_access_token`` writes ``iat`` in whole seconds and the
session watermark is stamped to the microsecond, so a token minted in the same second as a watermark
reads as older than it and is refused. Every test that signs in AFTER a watermark-writing request
waits a little over a second first.
"""

import asyncio
import io
import time
import uuid
from datetime import UTC, datetime
from typing import Any
from urllib.parse import parse_qs, urlsplit

import pytest
from conftest import needs_db

from app.api.routes import auth as auth_routes, users as users_routes
from app.core.db import db
from app.core.deps import ACCOUNT_PROVISIONER_REQUIRED_DETAIL, get_settings
from app.core.security import create_access_token, hash_password
from app.services import access_roster, account_provisioning
from app.services.designers import canonical_email

pytestmark = [needs_db]

#: Every seeded account's password.
PASSWORD = "provisioning-seeded-password"
#: What the tests below give the accounts they create through the API.
NEW_PASSWORD = "provisioned-temporary-password"
#: What the operator script is handed through ``PROVISION_PASSWORD``. Distinctive, so an assertion
#: that it never appears in the script's output cannot pass by accident.
SCRIPT_PASSWORD = "Script-Chosen-0451-Password"

SEEDED_NOTE = "Seeded by tests/test_account_provisioning.py."

#: The accounts that act. Every one is admitted and holds PASSWORD. ``flaggedMinistry`` holds a
#: password somebody else chose, which is what the script must refuse as an actor.
ACTORS: tuple[tuple[str, str], ...] = (
    ("master", "MASTER_ADMIN"),
    ("admin", "ADMIN"),
    ("peerAdmin", "ADMIN"),
    ("ministry", "MINISTRY_ADMIN"),
    ("regional", "REGIONAL_DIRECTOR"),
    ("assistant", "ASSISTANT_DIRECTOR"),
    ("professor", "PROFESSOR"),
    ("flaggedMinistry", "MINISTRY_ADMIN"),
)
FLAGGED_ACTORS = frozenset({"flaggedMinistry"})

#: Addresses an administrator barred on the allow-list, with no account behind them.
BARRED: tuple[tuple[str, str], ...] = (
    ("rejected", "REJECTED"),
    ("suspended", "SUSPENDED"),
    ("adminBarred", "SUSPENDED"),
)

#: Accounts an administrator barred — on the allow-list, or by ending a designer empanelment that the
#: allow-list row does not mirror (``admitRole`` is not DESIGNER) — which section 10 tries to move to
#: another address. slug -> (role, allow-list status, allow-list admitRole, empanelment ended).
BARRED_ACCOUNTS: tuple[tuple[str, str, str, str | None, bool], ...] = (
    ("barredMover", "RESEARCHER", "SUSPENDED", "RESEARCHER", False),
    ("rejectedMover", "RESEARCHER", "REJECTED", "RESEARCHER", False),
    ("endedDesigner", "DESIGNER", "ACTIVE", "RESEARCHER", True),
    ("barredMoverAdmin", "RESEARCHER", "SUSPENDED", "RESEARCHER", False),
    # admitRole NULL: every grandfathered account's row, which the mirror never touches either.
    ("endedDesignerAdmin", "DESIGNER", "ACTIVE", None, True),
    # Section 14: the moves that would carry an ended empanelment onto somebody's live one.
    ("endedCoTenant", "DESIGNER", "ACTIVE", "RESEARCHER", True),
    ("endedProfessor", "PROFESSOR", "ACTIVE", "PROFESSOR", True),
    ("endedProfessorAdmin", "PROFESSOR", "ACTIVE", "PROFESSOR", True),
)
#: The PENDING rows a barred person can plant without anybody's help: a refused Google sign-in at
#: a second address writes one (``access_roster.record_refused_attempt``) and creates no account.
PLANTS = ("barredMoverPlant", "barredMoverAdminPlant")

#: Empanelments an administrator granted and nobody ended, at addresses with no account: the
#: destinations section 14 moves an ended empanelment onto, one for each side of the rule.
ACTIVE_EMPANELMENTS = ("activeEmpanelment", "activeEmpanelmentAdmin")

#: The note an administrator wrote on the allow-list row of the live Gmail designer in section 14,
#: which a move onto another spelling of their mailbox used to overwrite.
VICTIM_NOTE = "Empanelled for the Barpali cluster; admitted by hand. Seeded by the provisioning tests."

OWN_CREDENTIALS = account_provisioning.OWN_CREDENTIALS_DETAIL
SESSION_ENDED = "This session is no longer valid. Sign in again."
#: The two refusal sentences the sign-in page reads, written out as ``test_platform_access_gate`` does.
ACCESS_SUSPENDED = "Your access to this application has been suspended. Contact the administrator."
DESIGNER_SUSPENDED = "Your designer access has been suspended. Contact the administrator."
LINK_WITHDRAWN = "This link was withdrawn. Ask the administrator for a new one."


@pytest.fixture(scope="module")
def world():
    """The actors, the barred addresses and accounts, a Google-only account and a handful of
    spellings, with the Gmail-fold parity asked while the seed's connection is open; then every
    operator-script scenario; then the TestClient. See the module docstring for why in that order."""
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        # Lower-cased: ``AccessRoster.email`` is stored lower-cased and a mixed-case fixture address
        # would be a row the gate can never match.
        return f"prov-{slug}-{stamp}@example.org".lower()

    async def seed() -> tuple[dict[str, Any], list[Any]]:
        await db.connect()
        try:
            now = datetime.now(UTC)
            people: dict[str, Any] = {}
            for slug, role in ACTORS:
                people[slug] = await db.user.create(
                    data={
                        "email": address(slug),
                        "name": f"Provisioning {slug} {stamp}",
                        "role": role,
                        "passwordHash": hash_password(PASSWORD),
                        "passwordSetAt": now,
                        "mustChangePassword": slug in FLAGGED_ACTORS,
                        "authProvider": "LOCAL",
                    }
                )
                await db.accessroster.create(
                    data={
                        "email": address(slug),
                        "status": "ACTIVE",
                        "admitRole": role,
                        "joinedAt": now,
                        "notes": SEEDED_NOTE,
                    }
                )
            # A Google-only account: no password, provider GOOGLE, the name Google last sent.
            people["googleOnly"] = await db.user.create(
                data={
                    "email": address("googleOnly"),
                    "name": "Old Google Name",
                    "role": "RESEARCHER",
                    "authProvider": "GOOGLE",
                }
            )
            await db.accessroster.create(
                data={
                    "email": address("googleOnly"),
                    "status": "ACTIVE",
                    "admitRole": "RESEARCHER",
                    "joinedAt": now,
                    "notes": SEEDED_NOTE,
                }
            )
            for slug, state in BARRED:
                await db.accessroster.create(
                    data={
                        "email": address(slug),
                        "status": state,
                        "decidedAt": now,
                        "notes": SEEDED_NOTE,
                    }
                )
            # Empanelments an administrator ended, for addresses with no account yet. The two after
            # the first are the destinations of the PATCH tests in section 6, one for each side of
            # the rule — a move that lands needs an address nobody else has moved onto.
            for slug in ("empSuspended", "empSuspendedMove", "empSuspendedRole", "empSuspendedAdmin"):
                await db.designerroster.create(
                    data={
                        "email": address(slug),
                        "fullName": "Ended Empanelment",
                        "isActive": False,
                        "revokedAt": now,
                        "addedById": people["admin"].id,
                    }
                )
            for slug, role, state, admit_role, ended in BARRED_ACCOUNTS:
                people[slug] = await db.user.create(
                    data={
                        "email": address(slug),
                        "name": f"Barred {slug} {stamp}",
                        "role": role,
                        "passwordHash": hash_password(PASSWORD),
                        "passwordSetAt": now,
                        "mustChangePassword": False,
                        "authProvider": "LOCAL",
                    }
                )
                await db.accessroster.create(
                    data={
                        "email": address(slug),
                        "status": state,
                        **({"admitRole": admit_role} if admit_role else {}),
                        "joinedAt": now,
                        "decidedAt": now,
                        "decidedById": people["admin"].id,
                        "notes": SEEDED_NOTE,
                    }
                )
                if ended:
                    await db.designerroster.create(
                        data={
                            "email": address(slug),
                            "fullName": "Ended Before The Move",
                            "isActive": False,
                            "revokedAt": now,
                            "addedById": people["admin"].id,
                        }
                    )
            for slug in PLANTS:
                await db.accessroster.create(
                    data={
                        "email": address(slug),
                        "status": "PENDING",
                        "requestedAt": now,
                        "attemptCount": 1,
                    }
                )
            for slug in ACTIVE_EMPANELMENTS:
                await db.designerroster.create(
                    data={
                        "email": address(slug),
                        "fullName": "Somebody Else Empanelled",
                        "isActive": True,
                        "addedById": people["admin"].id,
                    }
                )
            # SECTION 14. A researcher an admin SUSPENDED, filed under a dotted Gmail spelling beside
            # an allow-list row under the mailbox — the ordinary state after an admin's correction.
            barred_gmail = f"prov.barred.gmail.{stamp}@gmail.com"
            people["barredGmail"] = await db.user.create(
                data={
                    "email": barred_gmail,
                    "name": f"Barred Gmail {stamp}",
                    "role": "RESEARCHER",
                    "passwordHash": hash_password(PASSWORD),
                    "passwordSetAt": now,
                    "mustChangePassword": False,
                    "authProvider": "LOCAL",
                }
            )
            await db.accessroster.create(
                data={
                    "email": canonical_email(barred_gmail),
                    "status": "SUSPENDED",
                    "admitRole": "RESEARCHER",
                    "fullName": "Barred Gmail Person",
                    "joinedAt": now,
                    "decidedAt": now,
                    "decidedById": people["admin"].id,
                    "notes": SEEDED_NOTE,
                }
            )
            # A LIVE DESIGNER ON A GMAIL MAILBOX, admitted and empanelled by an administrator: the
            # account another one used to be moved onto under a different spelling of the mailbox.
            victim = f"prov.victim.{stamp}@gmail.com"
            people["gmailVictim"] = await db.user.create(
                data={
                    "email": victim,
                    "name": f"Gmail Victim {stamp}",
                    "role": "DESIGNER",
                    "passwordHash": hash_password(PASSWORD),
                    "passwordSetAt": now,
                    "mustChangePassword": False,
                    "authProvider": "LOCAL",
                }
            )
            await db.accessroster.create(
                data={
                    "email": canonical_email(victim),
                    "status": "ACTIVE",
                    "admitRole": "DESIGNER",
                    "joinedAt": now,
                    "decidedAt": now,
                    "decidedById": people["admin"].id,
                    "notes": VICTIM_NOTE,
                }
            )
            await db.designerroster.create(
                data={
                    "email": canonical_email(victim),
                    "fullName": "Gmail Victim",
                    "isActive": True,
                    "addedById": people["admin"].id,
                }
            )
            # TWO PASSWORD ACCOUNTS ON ONE GMAIL MAILBOX, written straight into the table: since
            # 2026-10-09 neither the create nor the move makes such a pair, and the Google sign-in
            # still has to refuse one that is already there (section 8).
            for typed in (f"a.mbig.{stamp}@gmail.com", f"am.big.{stamp}@gmail.com"):
                await db.user.create(
                    data={
                        "email": typed,
                        "name": f"Ambiguous {stamp}",
                        "role": "RESEARCHER",
                        "passwordHash": hash_password(PASSWORD),
                        "passwordSetAt": now,
                        "authProvider": "LOCAL",
                    }
                )
            await db.accessroster.create(
                data={
                    "email": f"ambig{stamp}@gmail.com",
                    "status": "ACTIVE",
                    "admitRole": "RESEARCHER",
                    "joinedAt": now,
                    "notes": SEEDED_NOTE,
                }
            )
            # A LEGACY ACCOUNT STORED IN MIXED CASE, which ``User.email``'s unique index does not
            # equate with its lower-cased spelling (section 13).
            people["legacyCase"] = await db.user.create(
                data={
                    "email": f"Legacy.Case-{stamp}@Example.org",
                    "name": f"Legacy Case {stamp}",
                    "role": "RESEARCHER",
                    "passwordHash": hash_password(PASSWORD),
                    "passwordSetAt": now,
                    "authProvider": "LOCAL",
                }
            )
            # A password account a provisioner made under ANOTHER SPELLING of a master admin's Gmail
            # mailbox, before that master's first Google sign-in (section 12 points
            # MASTER_ADMIN_EMAIL at the dotted spelling for the length of a request or two).
            people["masterVariant"] = await db.user.create(
                data={
                    "email": f"newmaster{stamp}@gmail.com",
                    "name": f"Planted Variant {stamp}",
                    "role": "RESEARCHER",
                    "passwordHash": hash_password(PASSWORD),
                    "passwordSetAt": now,
                    "authProvider": "LOCAL",
                }
            )
            # A PENDING ROW UNDER A LEGACY DOTTED SPELLING, as the queue wrote them before
            # canonicalisation: the operator script's --google-only must write through it (section 9).
            await db.accessroster.create(
                data={
                    "email": f"leg.acy.{stamp}@gmail.com",
                    "status": "PENDING",
                    "requestedAt": now,
                    "attemptCount": 1,
                }
            )
            return people, await _the_mailbox_parity(stamp)
        finally:
            await db.disconnect()

    people, parity = asyncio.run(seed())
    script = _run_the_script_scenarios(address, stamp)
    with TestClient(app) as client:
        yield {
            "client": client,
            "people": people,
            "address": address,
            "stamp": stamp,
            "script": script,
            "parity": parity,
        }


@pytest.fixture
def client(world):
    return world["client"]


# --------------------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------------------


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    """A token minted directly for a seeded actor — the gate under test is not the sign-in."""
    return {"Authorization": f"Bearer {create_access_token(subject=world['people'][slug].id)}"}


def _bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _fresh(world: dict[str, Any], slug: str) -> str:
    return f"prov-new-{slug}-{world['stamp']}@example.org".lower()


def _create(client: Any, world: dict[str, Any], actor: str, email: str, **fields: Any) -> Any:
    body = {
        "email": email,
        "name": fields.pop("name", "Provisioned Person"),
        "password": fields.pop("password", NEW_PASSWORD),
        "role": fields.pop("role", "RESEARCHER"),
        **fields,
    }
    return client.post("/api/users", json=body, headers=_headers(world, actor))


def _login(client: Any, email: str, password: str = NEW_PASSWORD) -> Any:
    return client.post("/api/auth/login", json={"email": email, "password": password})


def _patch(client: Any, world: dict[str, Any], actor: str, user_id: str, **body: Any) -> Any:
    return client.patch(f"/api/users/{user_id}", json=body, headers=_headers(world, actor))


def _link(client: Any, world: dict[str, Any], actor: str, user_id: str) -> Any:
    return client.post(
        "/api/auth/password-links", json={"userId": user_id}, headers=_headers(world, actor)
    )


def _access_rows(client: Any, world: dict[str, Any], term: str) -> dict[str, Any]:
    response = client.get(
        "/api/access/roster",
        params={"search": term, "pageSize": 200},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    return {row["email"]: row for row in response.json()["items"]}


def _accounts(client: Any, world: dict[str, Any], term: str) -> list[dict[str, Any]]:
    response = client.get(
        "/api/users", params={"search": term, "pageSize": 100}, headers=_headers(world, "admin")
    )
    assert response.status_code == 200, response.text
    return response.json()["items"]


def _google(monkeypatch: Any, client: Any, email: str, name: str, picture: str | None = None):
    """The Google branch with only the token verification replaced, as every other suite does."""
    monkeypatch.setattr(
        auth_routes,
        "verify_google_token",
        lambda _token: {"email": email, "email_verified": True, "name": name, "picture": picture},
    )
    return client.post("/api/auth/login", json={"googleIdToken": "stand-in-for-a-real-token"})


async def _the_mailbox_parity(stamp: str) -> list[tuple[str, Any, Any, set[str], set[str]]]:
    """Seed one Gmail mailbox under every kind of spelling, and ask the sweep and its replacement on
    the sign-in path the same questions. Run inside the seed's connection, before any client exists.

    Returns ``(asked, swept ids, folded ids, expected ids, seeded ids)`` per question. The sweep and
    the fold are compared over the WHOLE table, not only these rows: "the same answer" is the claim.
    """
    mailbox = f"parity{stamp}@gmail.com"
    spellings = (
        mailbox,
        f"par.ity{stamp}@gmail.com",
        f"parity{stamp}+tag@gmail.com",
        f"Par.Ity{stamp}+Other@GoogleMail.com",
        f"parity{stamp}@googlemail.com",
        # Padded: ``str.strip`` takes the space off and the SQL fold cannot, which is the arm that
        # hands Python every address it is not proven for.
        f" parity.{stamp}@gmail.com",
        # Four that are NOT this mailbox: another one, another domain, a non-ASCII local part, and
        # an address with two @ whose text before the first one would fold onto a Gmail mailbox.
        f"parity{stamp}x@gmail.com",
        f"parity{stamp}@example.org",
        f"pàrity{stamp}@gmail.com",
        f"x@parity{stamp}@gmail.com",
    )
    seeded: dict[str, str] = {}
    for spelling in spellings:
        row = await db.user.create(
            data={
                "email": spelling,
                "name": f"Parity {stamp}",
                "role": "RESEARCHER",
                "authProvider": "LOCAL",
            }
        )
        seeded[row.id] = spelling
    answers = []
    for asked in (
        mailbox,
        f"par.ity{stamp}@gmail.com",
        f"PARITY{stamp}+zzz@googlemail.com",
        f"parity{stamp}x@gmail.com",
        f"nobody{stamp}@gmail.com",
        f"parity{stamp}@example.org",
    ):
        swept = await access_roster.accounts_on_the_mailbox(asked)
        folded = await access_roster.accounts_on_the_mailbox_for_sign_in(asked)
        expected = {
            row_id
            for row_id, spelling in seeded.items()
            if canonical_email(spelling) == canonical_email(asked)
        }
        answers.append(
            (
                asked,
                None if swept is None else {account.id for account in swept},
                None if folded is None else {account.id for account in folded},
                expected,
                set(seeded),
            )
        )
    return answers


def _run_the_script_scenarios(address: Any, stamp: str) -> dict[str, tuple[int, str, str]]:
    """Every operator-script run the tests below read, each in its own event loop, before any
    TestClient exists. Returns ``(exit status, out, err)``: both of the script's streams are
    collected, so "the password is never printed" is asserted over everything it could have shown a
    terminal, while the summary line is read off ``out`` alone.

    THE STREAMS ARE HANDED TO THE SCRIPT, NOT SWAPPED IN FOR ``sys.stderr``. Prisma starts its query
    engine with ``stderr=sys.stderr``, and a ``StringIO`` has no file descriptor: the first version of
    this helper used ``contextlib.redirect_stderr`` and every connect inside it failed.
    """
    from scripts import provision_account

    def run(argv: list[str], password: str | None = None) -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        environ = {provision_account.ENV_VARIABLE: password} if password else {}
        code = asyncio.run(provision_account.run(argv, environ=environ, out=out, err=err))
        return code, out.getvalue(), err.getvalue()

    def person(slug: str, role: str, actor: str, *extra: str, name: str = "Script Person"):
        email = slug if "@" in slug else address(slug)
        return [
            "--email", email, "--name", name, "--role", role, "--actor-email", address(actor),
            *extra,
        ]

    return {
        "dry": run(person("scriptDry", "RESEARCHER", "admin"), SCRIPT_PASSWORD),
        "made": run(
            person("scriptMade", "ASSISTANT_DIRECTOR", "ministry", "--apply"), SCRIPT_PASSWORD
        ),
        "final": run(
            person("scriptFinal", "RESEARCHER", "admin", "--no-must-change", "--apply"),
            SCRIPT_PASSWORD,
        ),
        "noPassword": run(person("scriptNone", "RESEARCHER", "admin", "--apply")),
        "argvPassword": run(
            person("scriptArgv", "RESEARCHER", "admin", "--password", SCRIPT_PASSWORD, "--apply")
        ),
        "shortPassword": run(person("scriptShort", "RESEARCHER", "admin", "--apply"), "x7Q!"),
        "overCeiling": run(person("scriptOver", "ADMIN", "ministry", "--apply"), SCRIPT_PASSWORD),
        "flaggedActor": run(
            person("scriptFlagged", "RESEARCHER", "flaggedMinistry", "--apply"), SCRIPT_PASSWORD
        ),
        "unknownActor": run(
            person("scriptUnknown", "RESEARCHER", "nobody", "--apply"), SCRIPT_PASSWORD
        ),
        "googleDry": run(person("scriptGoogleDry", "DESIGNER", "admin", "--google-only")),
        "googleApply": run(
            person("scriptGoogle", "RESEARCHER", "admin", "--google-only", "--apply",
                   name="Google Person")
        ),
        "googleMinistry": run(
            person("scriptGoogleMinistry", "RESEARCHER", "ministry", "--google-only", "--apply")
        ),
        "googleExisting": run(
            person(address("professor"), "RESEARCHER", "admin", "--google-only", "--apply")
        ),
        "googleWithPassword": run(
            person("scriptGooglePw", "RESEARCHER", "admin", "--google-only", "--apply"),
            SCRIPT_PASSWORD,
        ),
        # The legacy dotted PENDING row the seed wrote: re-admitted through its own spelling.
        "googleLegacy": run(
            person(f"leg.acy.{stamp}@gmail.com", "RESEARCHER", "admin", "--google-only",
                   "--apply", name="Legacy Person")
        ),
    }


# --------------------------------------------------------------------------------------
# 1. Who may create an account
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("actor", ["ministry", "admin", "master"])
def test_each_provisioning_tier_creates_an_account_that_signs_in_at_once(world, client, actor):
    """THE REQUIREMENT: a ministry admin, an admin and the master admin each create a password
    account, and the person signs in with it immediately — admitted, LOCAL, flagged."""
    email = _fresh(world, f"by-{actor}")
    made = _create(client, world, actor, email)
    assert made.status_code == 201, made.text
    body = made.json()
    assert "passwordHash" not in body
    assert body["authProvider"] == "LOCAL"
    assert body["mustChangePassword"] is True
    assert body["passwordSetAt"] is not None
    assert body["firstLoginAt"] is None

    signed_in = _login(client, email)
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["mustChangePassword"] is True
    assert _access_rows(client, world, email)[email]["status"] == "ACTIVE"


@pytest.mark.parametrize("actor", ["assistant", "regional", "professor"])
def test_the_tiers_that_promote_but_do_not_provision_are_refused(world, client, actor):
    refused = _create(client, world, actor, _fresh(world, f"refused-{actor}"))
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == ACCOUNT_PROVISIONER_REQUIRED_DETAIL


def test_a_ministry_admin_creates_at_its_own_tier_and_below_and_never_above(world, client):
    """THE CEILING IS INCLUSIVE AND IS ``assert_role``'s, unchanged."""
    own = _create(client, world, "ministry", _fresh(world, "own-tier"), role="MINISTRY_ADMIN")
    assert own.status_code == 201, own.text

    admin = _create(client, world, "ministry", _fresh(world, "over-admin"), role="ADMIN")
    assert admin.status_code == 403, admin.text
    assert admin.json()["detail"] == "You can only assign roles at or below your own tier"

    master = _create(client, world, "ministry", _fresh(world, "over-master"), role="MASTER_ADMIN")
    assert master.status_code == 403, master.text
    assert master.json()["detail"] == "Only the master admin can grant master admin"


# --------------------------------------------------------------------------------------
# 2. The first-sign-in flag
# --------------------------------------------------------------------------------------


def test_the_first_sign_in_flag_is_on_unless_the_provisioner_says_otherwise(world, client):
    email = _fresh(world, "final-password")
    made = _create(client, world, "ministry", email, mustChangePassword=False)
    assert made.status_code == 201, made.text
    assert made.json()["mustChangePassword"] is False
    assert made.json()["passwordSetAt"] is not None, "a password was set, so the stamp must be"

    signed_in = _login(client, email)
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["mustChangePassword"] is False


# --------------------------------------------------------------------------------------
# 3. What a ministry admin may not do, and an admin still may
# --------------------------------------------------------------------------------------


def test_a_ministry_admin_cannot_grant_a_capability(world, client):
    granted = _create(client, world, "ministry", _fresh(world, "granted"), canReview=True)
    assert granted.status_code == 403, granted.text
    assert granted.json()["detail"] == account_provisioning.GRANTS_ARE_ADMIN_ONLY_DETAIL

    # Every flag sent, every flag false: nothing is granted, so nothing is refused.
    echoed = _create(
        client,
        world,
        "ministry",
        _fresh(world, "no-grants"),
        **dict.fromkeys(account_provisioning.GRANT_FLAGS, False),
    )
    assert echoed.status_code == 201, echoed.text


def test_a_ministry_admin_cannot_overturn_a_bar(world, client):
    """``admit`` re-activates a barred row, so without this a ministry admin could let back in
    somebody an administrator rejected or suspended — by creating an account for them."""
    for slug in ("rejected", "suspended"):
        refused = _create(client, world, "ministry", world["address"](slug))
        assert refused.status_code == 409, refused.text
        assert "on the access screen" in refused.json()["detail"]

    ended = world["address"]("empSuspended")
    designer = _create(client, world, "ministry", ended, role="DESIGNER")
    assert designer.status_code == 409, designer.text
    assert "empanelment" in designer.json()["detail"]

    # The empanelment only stands in the way of a DESIGNER: at another role the account can sign in.
    researcher = _create(client, world, "ministry", ended, role="RESEARCHER")
    assert researcher.status_code == 201, researcher.text


def test_an_admin_may_still_readmit_a_barred_address(world, client):
    email = world["address"]("adminBarred")
    made = _create(client, world, "admin", email)
    assert made.status_code == 201, made.text
    assert _access_rows(client, world, email)[email]["status"] == "ACTIVE"
    assert _login(client, email).status_code == 200


# --------------------------------------------------------------------------------------
# 4. One account per address
# --------------------------------------------------------------------------------------


def test_an_address_is_one_account_in_any_letter_case(world, client):
    email = _fresh(world, "once")
    assert _create(client, world, "admin", email).status_code == 201
    again = _create(client, world, "admin", email.upper())
    assert again.status_code == 409, again.text
    assert again.json()["detail"] == "Email already exists"


def test_two_requests_racing_for_one_address_still_get_a_409(world, client, monkeypatch):
    """The race the pre-check cannot close — a double-click, two provisioners at once — is settled
    by the database's unique index, and the loser hears what it would have heard a moment later."""
    email = _fresh(world, "race")
    assert _create(client, world, "admin", email).status_code == 201

    async def _lost_the_race(*_args: Any, **_kwargs: Any) -> bool:
        return False

    monkeypatch.setattr(account_provisioning, "email_in_use", _lost_the_race)
    raced = _create(client, world, "admin", email)
    assert raced.status_code == 409, raced.text
    assert raced.json()["detail"] == "Email already exists"


# --------------------------------------------------------------------------------------
# 5. A designer provisioned by a ministry admin
# --------------------------------------------------------------------------------------


def test_a_designer_provisioned_by_a_ministry_admin_is_admitted_and_empanelled(world, client):
    email = _fresh(world, "designer")
    made = _create(client, world, "ministry", email, role="DESIGNER")
    assert made.status_code == 201, made.text

    row = _access_rows(client, world, email)[email]
    assert row["status"] == "ACTIVE"
    assert row["admitRole"] == "DESIGNER"
    assert row["decidedById"] == world["people"]["ministry"].id

    roster = client.get(
        "/api/designers/roster", params={"search": email}, headers=_headers(world, "admin")
    )
    assert roster.status_code == 200, roster.text
    rows = [r for r in roster.json()["items"] if r["email"] == email]
    assert len(rows) == 1 and rows[0]["isActive"] is True
    assert rows[0]["addedById"] == world["people"]["ministry"].id
    assert _login(client, email).status_code == 200


# --------------------------------------------------------------------------------------
# 6. Changing an existing account
# --------------------------------------------------------------------------------------


def _settled_account(client: Any, world: dict[str, Any], slug: str, **fields: Any) -> dict:
    """An account with a password its holder chose (no flag), signed in once."""
    email = _fresh(world, slug)
    made = _create(client, world, "admin", email, mustChangePassword=False, **fields)
    assert made.status_code == 201, made.text
    return made.json()


def test_asking_an_account_to_change_its_password_leaves_the_password_alone(world, client):
    """R2b: "change it at your next sign-in", without choosing a new password for the person — and
    "next" is literal, because raising the flag ends the sessions the account already has."""
    account = _settled_account(client, world, "next-sign-in")
    before = _login(client, account["email"])
    assert before.status_code == 200, before.text
    old_token = before.json()["accessToken"]

    raised = _patch(client, world, "ministry", account["id"], mustChangePassword=True)
    assert raised.status_code == 200, raised.text
    assert raised.json()["mustChangePassword"] is True
    assert raised.json()["passwordSetAt"] == account["passwordSetAt"], "the password was replaced"

    ended = client.get("/api/me", headers=_bearer(old_token))
    assert ended.status_code == 401, ended.text
    assert ended.json()["detail"] == SESSION_ENDED

    time.sleep(1.1)
    after = _login(client, account["email"])  # the SAME password still signs in
    assert after.status_code == 200, after.text
    assert after.json()["user"]["mustChangePassword"] is True


def test_the_flag_cannot_be_raised_on_an_account_with_no_password(world, client):
    """And no answer about such an account sends anybody to a door that refuses it: the link route
    answers a Google-only account 422, so neither refusal may offer a link as the remedy."""
    google_only = world["people"]["googleOnly"].id
    refused = _patch(client, world, "ministry", google_only, mustChangePassword=True)
    assert refused.status_code == 422, refused.text
    assert refused.json()["detail"] == account_provisioning.NO_PASSWORD_TO_CHANGE_DETAIL
    assert "link" not in refused.json()["detail"]
    assert _link(client, world, "ministry", google_only).status_code == 422

    told = client.post(
        "/api/auth/change-password",
        json={"currentPassword": "anything-at-all", "newPassword": "a-new-password-for-it"},
        headers=_headers(world, "googleOnly"),
    )
    assert told.status_code == 400, told.text
    assert told.json()["detail"] == (
        "This account signs in with Google and has no password to change."
    )


@pytest.mark.parametrize("actor", ["ministry", "admin", "master"])
def test_nobody_sets_their_own_password_or_flag_here(world, client, actor):
    """The takeover ``POST /auth/change-password`` exists to prevent: a stolen session setting a
    permanent password with no current password and no guessing budget. Refused BY NAME now, not
    by the accident that used to refuse it."""
    own = world["people"][actor].id
    for body in ({"password": "a-new-password-for-me"}, {"mustChangePassword": True}):
        refused = _patch(client, world, actor, own, **body)
        assert refused.status_code == 403, refused.text
        assert refused.json()["detail"] == OWN_CREDENTIALS


def test_a_password_set_for_somebody_else_is_temporary_and_ends_their_sessions(world, client):
    account = _settled_account(client, world, "reset-for")
    old_token = _login(client, account["email"]).json()["accessToken"]

    reset = _patch(client, world, "admin", account["id"], password="typed-by-an-admin")
    assert reset.status_code == 200, reset.text
    assert reset.json()["mustChangePassword"] is True
    assert client.get("/api/me", headers=_bearer(old_token)).status_code == 401

    # And a provisioner can say the password is final, in the same request.
    final = _patch(
        client,
        world,
        "admin",
        account["id"],
        password="final-by-an-admin",
        mustChangePassword=False,
    )
    assert final.status_code == 200, final.text
    assert final.json()["mustChangePassword"] is False

    time.sleep(1.1)
    assert _login(client, account["email"], "final-by-an-admin").status_code == 200
    assert _login(client, account["email"], "typed-by-an-admin").status_code == 401


def test_a_ministry_admin_cannot_change_a_capability_but_may_echo_one(world, client):
    account = _settled_account(client, world, "grants")
    changed = _patch(client, world, "ministry", account["id"], canReview=True)
    assert changed.status_code == 403, changed.text
    assert changed.json()["detail"] == account_provisioning.GRANTS_ARE_ADMIN_ONLY_DETAIL

    echoed = _patch(client, world, "ministry", account["id"], canReview=False, name="Echoed Name")
    assert echoed.status_code == 200, echoed.text
    assert echoed.json()["canReview"] is False


def test_a_ministry_admin_corrects_a_name_and_an_address(world, client):
    account = _settled_account(client, world, "typo")
    corrected = _fresh(world, "typo-fixed")
    fixed = _patch(
        client, world, "ministry", account["id"], name="Corrected Name", email=corrected
    )
    assert fixed.status_code == 200, fixed.text
    assert fixed.json()["email"] == corrected
    assert _login(client, corrected).status_code == 200, "the allow-list did not follow the address"


def test_a_ministry_admin_cannot_move_an_account_onto_a_barred_address(world, client):
    account = _settled_account(client, world, "move-to-barred")
    refused = _patch(
        client, world, "ministry", account["id"], email=world["address"]("rejected")
    )
    assert refused.status_code == 409, refused.text
    assert "on the access screen" in refused.json()["detail"]


def test_a_ministry_admin_cannot_move_a_designer_onto_an_ended_empanelment(world, client):
    """The create's rule, on the move (2026-10-09). An administrator ended this address's designer
    empanelment, so a DESIGNER account there is refused at every sign-in; a provisioner who is not an
    admin may not put one there by correcting an address any more than by creating it. Only the role
    the account will HOLD matters: a researcher moves there freely, and a request that makes the
    account a designer while moving it is refused like one that already was."""
    ended = world["address"]("empSuspendedMove")
    designer = _settled_account(client, world, "designer-moving", role="DESIGNER")
    refused = _patch(client, world, "ministry", designer["id"], email=ended)
    assert refused.status_code == 409, refused.text
    assert refused.json()["detail"] == (
        account_provisioning.SUSPENDED_EMPANELMENT_MOVE_DETAIL.format(email=ended)
    )
    kept = [row for row in _accounts(client, world, designer["email"]) if row["id"] == designer["id"]]
    assert [row["email"] for row in kept] == [designer["email"]], "the refused move wrote anyway"

    becoming = _settled_account(client, world, "becoming-a-designer")
    both = _patch(
        client,
        world,
        "ministry",
        becoming["id"],
        email=world["address"]("empSuspendedRole"),
        role="DESIGNER",
    )
    assert both.status_code == 409, both.text
    assert "empanelment" in both.json()["detail"]

    researcher = _settled_account(client, world, "researcher-moving")
    moved = _patch(client, world, "ministry", researcher["id"], email=ended)
    assert moved.status_code == 200, moved.text
    assert moved.json()["email"] == ended


def test_an_admin_may_still_move_a_designer_onto_an_ended_empanelment(world, client):
    """Admins keep the power the create gives them, and the move is logged rather than refused."""
    ended = world["address"]("empSuspendedAdmin")
    designer = _settled_account(client, world, "designer-moved-by-admin", role="DESIGNER")
    moved = _patch(client, world, "admin", designer["id"], email=ended)
    assert moved.status_code == 200, moved.text
    assert moved.json()["email"] == ended


def test_a_professor_changes_roles_and_nothing_else(world, client):
    account = _settled_account(client, world, "professor-target")
    renamed = _patch(client, world, "professor", account["id"], name="Renamed By A Professor")
    assert renamed.status_code == 403, renamed.text
    assert "Ministry Admin" in renamed.json()["detail"]

    demoted = _patch(client, world, "professor", account["id"], role="FIELD_CONTRIBUTOR")
    assert demoted.status_code == 200, demoted.text
    assert demoted.json()["role"] == "FIELD_CONTRIBUTOR"


def test_a_provisioner_cannot_touch_an_account_at_or_above_its_tier(world, client):
    refused = _patch(
        client, world, "ministry", world["people"]["peerAdmin"].id, name="Renamed Upwards"
    )
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == "You can only manage users below your own tier"


# --------------------------------------------------------------------------------------
# 7. Password links
# --------------------------------------------------------------------------------------


def test_an_admin_cannot_mint_a_link_for_the_master_admin_or_a_peer(world, client):
    """THE TAKEOVER THIS CHANGE CLOSED. ``set-password`` has no role check by design — the link is
    the whole authority — so a link minted for an account is that account."""
    for target in ("master", "peerAdmin"):
        refused = _link(client, world, "admin", world["people"][target].id)
        assert refused.status_code == 403, refused.text


def test_a_master_admin_mints_a_link_for_an_admin(world, client):
    issued = _link(client, world, "master", world["people"]["peerAdmin"].id)
    assert issued.status_code == 201, issued.text
    assert set(issued.json()) == {"id", "link", "expiresAt", "purpose", "deliveredBy"}


def test_the_link_is_an_invitation_until_the_account_is_used(world, client):
    """A just-provisioned account holds a hash and has never signed in: an invitation, 72 hours.
    Once somebody has signed in, a reset, 2 hours."""
    email = _fresh(world, "invited")
    made = _create(client, world, "ministry", email)
    assert made.status_code == 201, made.text

    first = _link(client, world, "ministry", made.json()["id"])
    assert first.status_code == 201, first.text
    assert first.json()["purpose"] == "INVITE"

    assert _login(client, email).status_code == 200
    second = _link(client, world, "ministry", made.json()["id"])
    assert second.status_code == 201, second.text
    assert second.json()["purpose"] == "RESET"


def test_a_google_account_with_no_password_gets_no_link(world, client):
    refused = _link(client, world, "ministry", world["people"]["googleOnly"].id)
    assert refused.status_code == 422, refused.text
    assert refused.json()["detail"] == account_provisioning.GOOGLE_ONLY_LINK_DETAIL


def test_a_link_for_nobody_is_a_404(world, client):
    assert _link(client, world, "ministry", "no-such-account").status_code == 404


def test_nobody_mints_themselves_a_link(world, client):
    refused = _link(client, world, "admin", world["people"]["admin"].id)
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == OWN_CREDENTIALS


@pytest.mark.parametrize("actor", ["assistant", "regional", "professor"])
def test_tiers_that_cannot_provision_issue_no_links(world, client, actor):
    account = _settled_account(client, world, f"link-by-{actor}")
    refused = _link(client, world, actor, account["id"])
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == ACCOUNT_PROVISIONER_REQUIRED_DETAIL


def test_revoking_a_link_needs_the_same_authority_as_issuing_it(world, client):
    issued = _link(client, world, "master", world["people"]["peerAdmin"].id)
    assert issued.status_code == 201, issued.text
    link_id = issued.json()["id"]

    for actor in ("admin", "ministry"):
        refused = client.post(
            f"/api/auth/password-links/{link_id}/revoke", headers=_headers(world, actor)
        )
        assert refused.status_code == 403, f"{actor}: {refused.text}"

    revoked = client.post(
        f"/api/auth/password-links/{link_id}/revoke", headers=_headers(world, "master")
    )
    assert revoked.status_code == 200, revoked.text
    missing = client.post(
        "/api/auth/password-links/no-such-link/revoke", headers=_headers(world, "master")
    )
    assert missing.status_code == 404, missing.text


def test_a_link_cannot_be_redeemed_with_the_temporary_password(world, client):
    """The forced change exists to retire a password somebody else chose. Redeeming a link with that
    same password would clear the flag and keep the secret, so it is refused — and the link survives
    for the person's real choice."""
    email = _fresh(world, "redeem")
    made = _create(client, world, "ministry", email)
    assert made.status_code == 201, made.text
    issued = _link(client, world, "ministry", made.json()["id"])
    assert issued.status_code == 201, issued.text
    token = parse_qs(urlsplit(issued.json()["link"]).query)["token"][0]

    same = client.post("/api/auth/set-password", json={"token": token, "password": NEW_PASSWORD})
    assert same.status_code == 400, same.text
    chosen = client.post(
        "/api/auth/set-password", json={"token": token, "password": "the-holders-own-choice"}
    )
    assert chosen.status_code == 200, chosen.text

    time.sleep(1.1)
    signed_in = _login(client, email, "the-holders-own-choice")
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["mustChangePassword"] is False


# --------------------------------------------------------------------------------------
# 8. Google sign-in on a password account
# --------------------------------------------------------------------------------------


def test_google_on_a_password_account_keeps_the_password_the_provider_and_the_name(
    world, client, monkeypatch
):
    email = _fresh(world, "google-exact")
    made = _create(client, world, "admin", email, name="Typed By An Admin")
    assert made.status_code == 201, made.text

    picture = "https://example.org/face.png"
    signed_in = _google(monkeypatch, client, email, "Display Name From Google", picture)
    assert signed_in.status_code == 200, signed_in.text
    user = signed_in.json()["user"]
    assert user["id"] == made.json()["id"]
    assert user["name"] == "Typed By An Admin", "Google overwrote the name an administrator typed"
    assert user["authProvider"] == "LOCAL", "Google sign-in converted a password account"
    assert user["mustChangePassword"] is True, (
        "Google sign-in cleared the temporary password's flag"
    )
    assert user["avatarUrl"] == picture
    assert _login(client, email).status_code == 200, "the password stopped working"


def test_google_finds_the_password_account_under_another_spelling_of_the_mailbox(
    world, client, monkeypatch
):
    """The fork this decision exists to stop: an account typed with dots, a Google token without."""
    stamp = world["stamp"]
    typed = f"g.canon.{stamp}@gmail.com"
    mailbox = f"gcanon{stamp}@gmail.com"
    made = _create(client, world, "admin", typed)
    assert made.status_code == 201, made.text

    signed_in = _google(monkeypatch, client, mailbox, "Canon Person")
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["id"] == made.json()["id"], "a second account was created"
    assert signed_in.json()["user"]["email"] == typed
    assert [a for a in _accounts(client, world, mailbox) if a["email"] == mailbox] == []

    # AND THE NEXT SIGN-IN READS NO GMAIL SWEEP. Attaching by id leaves the stored spelling alone,
    # so the literal lookup misses on every sign-in this person ever makes — and each of those used
    # to read every Gmail account's row. Postgres does the fold on this path now.
    original = type(db.user).find_many

    async def _no_sweep(self: Any, *args: Any, **kwargs: Any) -> Any:
        assert "endswith" not in repr(kwargs.get("where")), "a Google sign-in swept every Gmail account"
        return await original(self, *args, **kwargs)

    monkeypatch.setattr(type(db.user), "find_many", _no_sweep)
    again = _google(monkeypatch, client, mailbox, "Canon Person")
    assert again.status_code == 200, again.text
    assert again.json()["user"]["id"] == made.json()["id"]


def test_the_sign_in_fold_answers_exactly_what_the_sweep_answered(world):
    """``access_roster.accounts_on_the_mailbox_for_sign_in`` against the sweep it replaced on the
    sign-in path, over dotted, ``+tag``, ``googlemail.com``, capitalised and padded spellings and
    four near misses — asked in the seed, compared here. Equal over the whole table, and exactly the
    seeded spellings ``canonical_email`` maps onto the mailbox among the seeded rows."""
    for asked, swept, folded, expected, seeded in world["parity"]:
        assert swept is not None and folded is not None, f"{asked}: an answer was withdrawn"
        assert folded == swept, f"{asked}: the fold and the sweep disagree"
        assert folded & seeded == expected, f"{asked}: {folded & seeded} != {expected}"
    asked_for_the_mailbox = world["parity"][0]
    assert len(asked_for_the_mailbox[3]) == 6, "the six spellings of the one mailbox must all match"


def test_two_password_accounts_on_one_mailbox_are_refused_rather_than_guessed(
    world, client, monkeypatch
):
    """The pair is seeded straight into the table, because since 2026-10-09 no door makes one — a
    second account on a mailbox is "Email already exists" (section 14). One already there is still
    refused rather than guessed between."""
    stamp = world["stamp"]
    mailbox = f"ambig{stamp}@gmail.com"

    refused = _google(monkeypatch, client, mailbox, "Ambiguous Person")
    assert refused.status_code == 409, refused.text
    assert "password" in refused.json()["detail"]
    assert [a for a in _accounts(client, world, mailbox) if a["email"] == mailbox] == []


def test_a_google_only_account_signs_in_exactly_as_before(world, client, monkeypatch):
    email = world["address"]("googleOnly")
    signed_in = _google(monkeypatch, client, email, "New Google Name")
    assert signed_in.status_code == 200, signed_in.text
    user = signed_in.json()["user"]
    assert user["name"] == "New Google Name"
    assert user["authProvider"] == "GOOGLE"


# --------------------------------------------------------------------------------------
# 9. The operator script
# --------------------------------------------------------------------------------------


def test_the_script_dry_run_writes_nothing(world, client):
    code, out, err = world["script"]["dry"]
    assert code == 0, out + err
    assert out.startswith("DRY RUN"), out
    assert "Nothing was written" in out
    assert SCRIPT_PASSWORD not in out + err
    assert _accounts(client, world, world["address"]("scriptDry")) == []


def test_the_script_creates_the_account_the_route_would(world, client):
    code, out, err = world["script"]["made"]
    assert code == 0, out + err
    assert out.startswith("CREATED"), out
    assert SCRIPT_PASSWORD not in out + err
    assert out.count("\n") == 1, f"one summary line, not a report: {out!r}"

    email = world["address"]("scriptMade")
    signed_in = _login(client, email, SCRIPT_PASSWORD)
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["role"] == "ASSISTANT_DIRECTOR"
    assert signed_in.json()["user"]["mustChangePassword"] is True
    row = _access_rows(client, world, email)[email]
    assert row["status"] == "ACTIVE"
    assert row["decidedById"] == world["people"]["ministry"].id, "the actor was not recorded"


def test_the_script_can_make_the_password_final(world, client):
    code, out, err = world["script"]["final"]
    assert code == 0, out + err
    signed_in = _login(client, world["address"]("scriptFinal"), SCRIPT_PASSWORD)
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["mustChangePassword"] is False


def test_the_password_comes_from_the_environment_and_is_never_echoed(world, client):
    code, out, err = world["script"]["noPassword"]
    assert code == 2, out + err
    assert "PROVISION_PASSWORD" in out

    code, out, err = world["script"]["argvPassword"]
    assert code == 2, out + err
    assert SCRIPT_PASSWORD not in out + err, "argparse quoted a password typed on the command line"
    assert _accounts(client, world, world["address"]("scriptArgv")) == []

    code, out, err = world["script"]["shortPassword"]
    assert code == 1, out + err
    assert "x7Q!" not in out + err, "the validation message quoted the password"


def test_the_script_refuses_what_the_route_refuses_that_actor(world, client):
    code, out, err = world["script"]["overCeiling"]
    assert code == 1, out + err
    assert "REFUSED (403)" in out and "at or below your own tier" in out

    code, out, err = world["script"]["flaggedActor"]
    assert code == 1, out + err
    assert "own password" in out

    code, out, err = world["script"]["unknownActor"]
    assert code == 1, out + err
    assert "no account has the address" in out

    for slug in ("scriptOver", "scriptFlagged", "scriptUnknown"):
        assert _accounts(client, world, world["address"](slug)) == []


def test_google_only_admits_the_address_and_creates_no_account(world, client, monkeypatch):
    code, out, err = world["script"]["googleDry"]
    assert code == 0, out + err
    assert out.startswith("DRY RUN"), out
    assert world["address"]("scriptGoogleDry") not in _access_rows(
        client, world, world["address"]("scriptGoogleDry")
    )

    code, out, err = world["script"]["googleApply"]
    assert code == 0, out + err
    assert out.startswith("ADMITTED"), out
    email = world["address"]("scriptGoogle")
    row = _access_rows(client, world, email)[email]
    assert row["status"] == "ACTIVE"
    assert row["admitRole"] == "RESEARCHER"
    assert row["fullName"] == "Google Person"
    assert row["decidedById"] == world["people"]["admin"].id
    assert _accounts(client, world, email) == [], "google-only mode created an account"

    signed_in = _google(monkeypatch, client, email, "Google Person")
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["role"] == "RESEARCHER"
    assert signed_in.json()["user"]["authProvider"] == "GOOGLE"


def test_google_only_follows_the_access_screens_gate(world, client):
    code, out, err = world["script"]["googleMinistry"]
    assert code == 1, out + err
    assert "REFUSED (403)" in out

    code, out, err = world["script"]["googleExisting"]
    assert code == 1, out + err
    assert "REFUSED (409)" in out and "already exists" in out

    code, out, err = world["script"]["googleWithPassword"]
    assert code == 2, out + err
    assert SCRIPT_PASSWORD not in out + err


def test_google_only_writes_through_a_legacy_spelling_of_the_mailbox(world, client, monkeypatch):
    """The plan read the row under the typed spelling and wrote under the canonical one, and
    ``admit``'s key list for a canonical address is that address alone: a second row appeared, the
    legacy one still decided the sign-in, and the person stayed "pending" after the script printed
    ADMITTED. One row now, the legacy one, admitted at the tier asked for."""
    stamp = world["stamp"]
    legacy = f"leg.acy.{stamp}@gmail.com"
    code, out, err = world["script"]["googleLegacy"]
    assert code == 0, out + err
    assert out.startswith("ADMITTED"), out
    assert legacy in out, "the line must name the row it wrote"

    rows = [
        row
        for row in _access_rows(client, world, f"{stamp}@gmail.com").values()
        if canonical_email(row["email"]) == f"legacy{stamp}@gmail.com"
    ]
    assert [row["email"] for row in rows] == [legacy], "a second row was written for the mailbox"
    assert rows[0]["status"] == "ACTIVE"
    assert rows[0]["admitRole"] == "RESEARCHER"

    signed_in = _google(monkeypatch, client, legacy, "Legacy Person")
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["role"] == "RESEARCHER"


# --------------------------------------------------------------------------------------
# 10. A bar stays with the account
# --------------------------------------------------------------------------------------


def test_a_ministry_admin_cannot_move_a_suspended_account_onto_a_planted_address(world, client):
    """THE FINDING. An admin suspended this account. The person signed in with Google at a second
    address, which left a PENDING row there and no account, and asked a ministry admin to "correct"
    their address to it. The destination was PENDING, not barred, so the move was let through and the
    plant admitted — the person back in, the bar stranded on an address no account held. Refused
    now, before anything is written, and the person is still refused at the door."""
    mover = world["people"]["barredMover"]
    plant = world["address"]("barredMoverPlant")
    refused = _patch(client, world, "ministry", mover.id, email=plant)
    assert refused.status_code == 409, refused.text
    assert refused.json()["detail"] == account_provisioning.LEAVING_A_BARRED_ADDRESS_DETAIL.format(
        verb="suspended", email=mover.email
    )
    assert _access_rows(client, world, plant)[plant]["status"] == "PENDING", "the plant was admitted"
    kept = [row for row in _accounts(client, world, mover.email) if row["id"] == mover.id]
    assert [row["email"] for row in kept] == [mover.email], "the refused move wrote anyway"

    still = _login(client, mover.email, PASSWORD)
    assert still.status_code == 403, still.text
    assert still.json()["detail"] == ACCESS_SUSPENDED


def test_a_ministry_admin_cannot_move_a_rejected_account_to_a_fresh_address(world, client):
    mover = world["people"]["rejectedMover"]
    refused = _patch(client, world, "ministry", mover.id, email=_fresh(world, "rejected-away"))
    assert refused.status_code == 409, refused.text
    assert refused.json()["detail"] == account_provisioning.LEAVING_A_BARRED_ADDRESS_DETAIL.format(
        verb="rejected", email=mover.email
    )


def test_a_ministry_admin_cannot_move_a_designer_off_an_ended_empanelment(world, client):
    """The variant with no allow-list bar at all. Ending an empanelment mirrors onto the allow-list
    only for a row that admitted the person AS a designer, so this one is still ACTIVE at RESEARCHER
    and only the empanelment gate keeps the designer out. Moved to a fresh address, the next sign-in
    there would have empanelled them afresh."""
    designer = world["people"]["endedDesigner"]
    refused = _patch(client, world, "ministry", designer.id, email=_fresh(world, "ended-away"))
    assert refused.status_code == 409, refused.text
    assert refused.json()["detail"] == (
        account_provisioning.LEAVING_AN_ENDED_EMPANELMENT_DETAIL.format(email=designer.email)
    )
    still = _login(client, designer.email, PASSWORD)
    assert still.status_code == 403, still.text
    assert still.json()["detail"] == DESIGNER_SUSPENDED


def test_an_admin_may_move_a_barred_account_and_the_bar_goes_with_it(world, client):
    """Admins keep the power to correct a barred account's address — and correcting an address is all
    it does: the bar moves with the account. Letting the person back in is the access screen's act,
    or the designer roster's, where it is recorded as one."""
    mover = world["people"]["barredMoverAdmin"]
    plant = world["address"]("barredMoverAdminPlant")
    moved = _patch(client, world, "admin", mover.id, email=plant)
    assert moved.status_code == 200, moved.text
    assert moved.json()["email"] == plant
    row = _access_rows(client, world, plant)[plant]
    assert row["status"] == "SUSPENDED", "the planted row admitted a barred account"
    assert access_roster.BAR_CARRIED_BY_EMAIL_MOVE_NOTE in (row["notes"] or "")
    refused = _login(client, plant, PASSWORD)
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == ACCESS_SUSPENDED

    designer = world["people"]["endedDesignerAdmin"]
    fresh = _fresh(world, "ended-moved-by-admin")
    moved = _patch(client, world, "admin", designer.id, email=fresh)
    assert moved.status_code == 200, moved.text
    refused = _login(client, fresh, PASSWORD)
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == DESIGNER_SUSPENDED, (
        "the sign-in at the new address empanelled a designer whose empanelment had been ended"
    )
    roster = client.get(
        "/api/designers/roster", params={"search": fresh}, headers=_headers(world, "admin")
    )
    assert roster.status_code == 200, roster.text
    assert [r["isActive"] for r in roster.json()["items"] if r["email"] == fresh] == [False]


# --------------------------------------------------------------------------------------
# 11. A promotion does not carry a credential upward
# --------------------------------------------------------------------------------------


def test_a_promotion_withdraws_the_links_a_lower_provisioner_issued(world, client):
    """A ministry admin's invitation, redeemed after an admin made the invitee an ADMIN, would be a
    ministry admin choosing an admin's password. The promotion withdraws it."""
    account = _settled_account(client, world, "promoted-past-its-link")
    issued = _link(client, world, "ministry", account["id"])
    assert issued.status_code == 201, issued.text
    token = parse_qs(urlsplit(issued.json()["link"]).query)["token"][0]

    promoted = _patch(client, world, "admin", account["id"], role="ADMIN")
    assert promoted.status_code == 200, promoted.text
    checked = client.get("/api/auth/set-password", params={"token": token})
    assert checked.json() == {"valid": False, "reason": "revoked", "purpose": None}
    redeemed = client.post(
        "/api/auth/set-password", json={"token": token, "password": "the-issuers-own-choice"}
    )
    assert redeemed.status_code == 400, redeemed.text
    assert redeemed.json()["detail"] == LINK_WITHDRAWN


def test_a_promotion_waits_for_a_temporary_password_to_be_replaced(world, client):
    """The other credential a provisioner holds is the password it typed. While the account still
    has to replace it, a promotion is refused — unless the same request sets a new one."""
    made = _create(client, world, "ministry", _fresh(world, "promoted-while-temporary"))
    assert made.status_code == 201, made.text
    account_id = made.json()["id"]

    refused = _patch(client, world, "admin", account_id, role="PROFESSOR")
    assert refused.status_code == 409, refused.text
    assert refused.json()["detail"] == (
        account_provisioning.PROMOTION_WITH_A_TEMPORARY_PASSWORD_DETAIL
    )
    # Where the remedy lives, by name: the handset's role control carries no password field.
    assert "on the website's Users page" in refused.json()["detail"]
    lowered = _patch(client, world, "admin", account_id, role="FIELD_CONTRIBUTOR")
    assert lowered.status_code == 200, "only a promotion waits; a demotion does not"

    promoted = _patch(
        client, world, "admin", account_id, role="PROFESSOR", password="typed-by-the-promoter"
    )
    assert promoted.status_code == 200, promoted.text
    assert promoted.json()["role"] == "PROFESSOR"
    assert promoted.json()["mustChangePassword"] is True


def _approve(client: Any, world: dict[str, Any], actor: str, email: str, role: str) -> Any:
    """The access screen's APPROVE at ``role``, on the allow-list row of ``email`` — the door that
    also lifts an account which already exists (``routes/access._lift_existing_account``)."""
    row = _access_rows(client, world, email)[email]
    return client.post(
        f"/api/access/roster/{row['id']}/decision",
        json={"decision": "APPROVE", "role": role},
        headers=_headers(world, actor),
    )


def _role_of(client: Any, world: dict[str, Any], account: dict[str, Any]) -> str:
    """The tier the account holds now, read back through the users list."""
    rows = [row for row in _accounts(client, world, account["email"]) if row["id"] == account["id"]]
    assert len(rows) == 1, rows
    return rows[0]["role"]


def test_an_approval_that_lifts_an_account_withdraws_its_links(world, client):
    """THE SECOND DOOR ONTO A PROMOTION. Approving an allow-list row lifts the account behind it,
    and a lift withdraws the account's outstanding links exactly as the PATCH does. The link is the
    MASTER admin's, which the redemption's issuer re-check would accept whatever the account became
    (a master may always manage), so only the withdrawal stops it. An approval that lifts nothing —
    at the tier the account already holds — withdraws nothing."""
    account = _settled_account(client, world, "lifted-past-its-link")
    issued = _link(client, world, "master", account["id"])
    assert issued.status_code == 201, issued.text
    token = parse_qs(urlsplit(issued.json()["link"]).query)["token"][0]

    unchanged = _approve(client, world, "admin", account["email"], "RESEARCHER")
    assert unchanged.status_code == 200, unchanged.text
    assert unchanged.json()["accountPromotionHeld"] is None
    assert client.get("/api/auth/set-password", params={"token": token}).json()["valid"] is True

    lifted = _approve(client, world, "master", account["email"], "ADMIN")
    assert lifted.status_code == 200, lifted.text
    assert lifted.json()["accountPromotionHeld"] is None
    assert _role_of(client, world, account) == "ADMIN"
    checked = client.get("/api/auth/set-password", params={"token": token})
    assert checked.json() == {"valid": False, "reason": "revoked", "purpose": None}


def test_an_approval_leaves_an_account_holding_a_temporary_password_at_its_tier(world, client):
    """Rule 5 at the access screen, which has no password field to set a new one in. A ministry
    admin made this account and typed its password; approving the address at PROFESSOR must not
    carry that password above the ministry admin's reach. The approval stands — refusing it would
    leave the person's ACCESS undecided over a question about their TIER — the account keeps its
    tier, and the answer says so for the screen to show. Once the owner has chosen their own
    password, the same approval lifts it."""
    email = _fresh(world, "approved-while-temporary")
    made = _create(client, world, "ministry", email)
    assert made.status_code == 201, made.text
    account = made.json()
    assert account["mustChangePassword"] is True

    held = _approve(client, world, "admin", email, "PROFESSOR")
    assert held.status_code == 200, held.text
    assert (held.json()["status"], held.json()["admitRole"]) == ("ACTIVE", "PROFESSOR")
    assert held.json()["accountPromotionHeld"] == (
        account_provisioning.APPROVAL_KEEPS_THE_TIER_DETAIL.format(
            email=email, current="Researcher", granted="Professor"
        )
    )
    assert _role_of(client, world, account) == "RESEARCHER"

    signed_in = _login(client, email)
    assert signed_in.status_code == 200, signed_in.text
    changed = client.post(
        "/api/auth/change-password",
        json={"currentPassword": NEW_PASSWORD, "newPassword": "chosen-by-its-own-owner"},
        headers=_bearer(signed_in.json()["accessToken"]),
    )
    assert changed.status_code == 200, changed.text

    lifted = _approve(client, world, "admin", email, "PROFESSOR")
    assert lifted.status_code == 200, lifted.text
    assert lifted.json()["accountPromotionHeld"] is None
    assert _role_of(client, world, account) == "PROFESSOR"


# --------------------------------------------------------------------------------------
# 12. The master admin's mailbox
# --------------------------------------------------------------------------------------


def _as_the_master_mailbox(world: dict[str, Any]) -> tuple[str, str]:
    """MASTER_ADMIN_EMAIL for this section — a dotted Gmail address no account holds — and what to
    restore afterwards. Pointed at for a request or two, never left set."""
    return f"new.master.{world['stamp']}@gmail.com", get_settings().master_admin_email


def test_nobody_but_a_master_admin_puts_an_account_on_any_spelling_of_its_mailbox(world, client):
    stamp = world["stamp"]
    account = _settled_account(client, world, "renamed-towards-the-master")
    configured, previous = _as_the_master_mailbox(world)
    settings = get_settings()
    settings.master_admin_email = configured
    try:
        made = [
            _create(client, world, "ministry", spelling)
            for spelling in (f"newmaster{stamp}+x@gmail.com", f"New.Master.{stamp}@googlemail.com")
        ]
        moved = _patch(
            client, world, "ministry", account["id"], email=f"newmaster{stamp}+y@gmail.com"
        )
    finally:
        settings.master_admin_email = previous
    for refused in (*made, moved):
        assert refused.status_code == 403, refused.text
        assert refused.json()["detail"] == account_provisioning.MASTER_EMAIL_DETAIL


def test_the_master_admins_google_sign_in_never_lands_on_another_spelling(
    world, client, monkeypatch
):
    """A provisioner planted a password account under another spelling of a master's mailbox before
    that master's first Google sign-in. The fold used to find it, and promote it — the provisioner's
    password still on it — to MASTER_ADMIN. The master gets an account of its own at the literal
    address; the planted one keeps the tier it was made at."""
    planted = world["people"]["masterVariant"]
    configured, previous = _as_the_master_mailbox(world)
    settings = get_settings()
    settings.master_admin_email = configured
    try:
        signed_in = _google(monkeypatch, client, configured, "The Real Master")
    finally:
        settings.master_admin_email = previous
    assert signed_in.status_code == 200, signed_in.text
    user = signed_in.json()["user"]
    assert user["id"] != planted.id, "the master's sign-in landed on the planted account"
    assert user["email"] == configured
    assert user["role"] == "MASTER_ADMIN"
    kept = [row for row in _accounts(client, world, planted.email) if row["id"] == planted.id]
    assert [row["role"] for row in kept] == ["RESEARCHER"]


# --------------------------------------------------------------------------------------
# 13. What else a provisioner may, and may not, do
# --------------------------------------------------------------------------------------


def test_a_provisioner_may_withdraw_a_required_change(world, client):
    """``{"mustChangePassword": false}`` on its own, on an account it manages: the owner's ruling. It
    says the password the account holds is final, so it ends no session — the one opened with that
    password is released, as a password set with the flag off would have been."""
    account = _settled_account(client, world, "change-withdrawn")
    reset = _patch(client, world, "ministry", account["id"], password="typed-by-a-ministry-admin")
    assert reset.status_code == 200, reset.text
    assert reset.json()["mustChangePassword"] is True

    time.sleep(1.1)  # past the reset's watermark
    signed_in = _login(client, account["email"], "typed-by-a-ministry-admin")
    assert signed_in.status_code == 200, signed_in.text
    session = _bearer(signed_in.json()["accessToken"])
    held = client.get("/api/users/directory", headers=session)
    assert held.status_code == 401, held.text
    assert held.headers.get("X-Password-Change-Required") == "1"

    withdrawn = _patch(client, world, "ministry", account["id"], mustChangePassword=False)
    assert withdrawn.status_code == 200, withdrawn.text
    assert withdrawn.json()["mustChangePassword"] is False
    assert client.get("/api/users/directory", headers=session).status_code == 200


def test_a_ministry_admin_cannot_delete_an_account_it_manages(world, client):
    """D1: provisioning, and no deletes. Pinned at the route, so swapping ``delete_user``'s gate for
    its neighbour's ``require_account_provisioner`` fails here."""
    account = _settled_account(client, world, "not-deletable")
    refused = client.delete(f"/api/users/{account['id']}", headers=_headers(world, "ministry"))
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == "Admin access required"
    assert [row["id"] for row in _accounts(client, world, account["email"])] == [account["id"]]


def test_a_correction_onto_a_legacy_address_in_another_letter_case_is_a_duplicate(world, client):
    """``User.email`` is unique only as stored, and older rows were not all lower-cased: a lookup by
    the lower-cased address alone would let one mailbox become two accounts."""
    legacy = world["people"]["legacyCase"]
    account = _settled_account(client, world, "onto-a-legacy-address")
    refused = _patch(client, world, "ministry", account["id"], email=legacy.email.lower())
    assert refused.status_code == 409, refused.text
    assert refused.json()["detail"] == "Email already exists"
    kept = [row for row in _accounts(client, world, account["email"]) if row["id"] == account["id"]]
    assert [row["email"] for row in kept] == [account["email"]]


def test_a_correction_that_loses_the_race_is_a_409_not_a_500(world, client, monkeypatch):
    """The race the pre-check cannot close, on the move: the database's unique index refuses, and
    the loser hears the pre-check's answer. Patched where the route looks the name up."""
    holder = _settled_account(client, world, "race-holder")
    mover = _settled_account(client, world, "race-mover")

    async def _lost_the_race(*_args: Any, **_kwargs: Any) -> bool:
        return False

    monkeypatch.setattr(users_routes, "email_in_use", _lost_the_race)
    raced = _patch(client, world, "ministry", mover["id"], email=holder["email"])
    assert raced.status_code == 409, raced.text
    assert raced.json()["detail"] == "Email already exists"
    kept = [row for row in _accounts(client, world, mover["email"]) if row["id"] == mover["id"]]
    assert [row["email"] for row in kept] == [mover["email"]]


# --------------------------------------------------------------------------------------
# 14. A bar stays on the old mailbox, one account per mailbox, and no move ends an
#     empanelment a provisioner could not end (review of 2026-10-09)
# --------------------------------------------------------------------------------------


def _roster_rows_at(client: Any, world: dict[str, Any], email: str) -> list[dict[str, Any]]:
    """The designer roster's rows filed under exactly ``email``, as the roster screen lists them."""
    response = client.get(
        "/api/designers/roster", params={"search": email}, headers=_headers(world, "admin")
    )
    assert response.status_code == 200, response.text
    return [row for row in response.json()["items"] if row["email"] == email]


def test_an_admins_correction_bars_the_new_mailbox_and_leaves_the_old_one_barred(
    world, client, monkeypatch
):
    """SEC1. The barred row used to MOVE to the new address, leaving the old mailbox with no row: a
    Google sign-in there was a stranger's, queued PENDING with no trace of the suspension, and an
    admin approving that request let the barred person back in under a new account. Now the old row
    stays SUSPENDED, a SUSPENDED row is created at the new address saying why — carrying who barred
    them, the tier and the name — and both addresses refuse."""
    mover = world["people"]["barredGmail"]
    old_mailbox = canonical_email(mover.email)
    fresh = _fresh(world, "barred-gmail-corrected")
    moved = _patch(client, world, "admin", mover.id, email=fresh)
    assert moved.status_code == 200, moved.text
    assert moved.json()["email"] == fresh

    stamp_rows = _access_rows(client, world, world["stamp"])
    at_old = [row for email, row in stamp_rows.items() if canonical_email(email) == old_mailbox]
    assert [row["status"] for row in at_old] == ["SUSPENDED"], "the bar left the old mailbox"
    carried = stamp_rows[fresh]
    assert carried["status"] == "SUSPENDED"
    assert carried["notes"] == access_roster.BAR_CARRIED_BY_EMAIL_MOVE_NOTE
    assert carried["decidedById"] == world["people"]["admin"].id, "who barred them was lost"
    assert (carried["admitRole"], carried["fullName"]) == ("RESEARCHER", "Barred Gmail Person")

    # Signing in with Google at the OLD mailbox is still the suspension, and asks to join nothing.
    google = _google(monkeypatch, client, mover.email, "Barred Gmail Person")
    assert google.status_code == 403, google.text
    assert google.json()["detail"] == ACCESS_SUSPENDED
    assert google.headers.get("X-Access-Status") == "SUSPENDED"
    after = _access_rows(client, world, world["stamp"])
    assert [row["status"] for email, row in after.items() if canonical_email(email) == old_mailbox] == [
        "SUSPENDED"
    ], "a refused Google sign-in at the old mailbox queued a fresh request"
    # And the password, at the new address, is refused as it was at the old one.
    at_new = _login(client, fresh, PASSWORD)
    assert at_new.status_code == 403, at_new.text
    assert at_new.json()["detail"] == ACCESS_SUSPENDED


def test_no_account_is_created_on_a_mailbox_another_account_uses(world, client):
    """SEC1/F3. ``User.email`` was compared as typed, so a second account could be made on any other
    spelling of a Gmail mailbox — one inbox, two accounts, the second inheriting the first one's
    admission and empanelment. Refused for every provisioner now, in the duplicate's own words.
    Outside Gmail a dot is an ordinary character, so two such addresses are two people; and an
    account may still be respelled within its OWN mailbox."""
    stamp = world["stamp"]
    first = _create(client, world, "admin", f"one.inbox.{stamp}@gmail.com")
    assert first.status_code == 201, first.text
    for actor, spelling in (
        ("admin", f"oneinbox{stamp}@gmail.com"),
        ("ministry", f"One.Inbox.{stamp}+second@googlemail.com"),
        ("master", f"o.n.e.inbox{stamp}@gmail.com"),
    ):
        again = _create(client, world, actor, spelling)
        assert again.status_code == 409, (actor, again.text)
        assert again.json()["detail"] == "Email already exists"

    for spelling in (f"dot.ted.{stamp}@example.org", f"dotted.{stamp}@example.org"):
        assert _create(client, world, "admin", spelling).status_code == 201, spelling

    respelled = _patch(
        client, world, "ministry", first.json()["id"], email=f"oneinbox{stamp}@gmail.com"
    )
    assert respelled.status_code == 200, "an account was refused its own mailbox"


def test_no_account_is_moved_onto_another_accounts_mailbox_and_its_owner_keeps_everything(
    world, client
):
    """SEC1/F3, the variant the verifier traced. A ministry admin demoted a de-empanelled designer
    and moved them onto the DOTLESS spelling of a live Gmail designer's mailbox in one PATCH: the
    move rewrote the victim's allow-list row and ended the victim's empanelment, and the victim's
    next sign-in was refused. Now it is "Email already exists" before anything is written — for an
    admin too — and the victim keeps their row, their note, their empanelment and their sign-in."""
    victim = world["people"]["gmailVictim"]
    mover = world["people"]["endedCoTenant"]
    dotless = canonical_email(victim.email)
    assert dotless != victim.email, "the fixture must be two spellings of one mailbox"
    for actor in ("ministry", "admin"):
        refused = _patch(client, world, actor, mover.id, role="RESEARCHER", email=dotless)
        assert refused.status_code == 409, (actor, refused.text)
        assert refused.json()["detail"] == "Email already exists"
    kept = [row for row in _accounts(client, world, mover.email) if row["id"] == mover.id]
    assert [(row["email"], row["role"]) for row in kept] == [(mover.email, "DESIGNER")]

    row = _access_rows(client, world, dotless)[dotless]
    assert (row["status"], row["notes"]) == ("ACTIVE", VICTIM_NOTE), "the victim's row was rewritten"
    assert row["decidedById"] == world["people"]["admin"].id
    assert [r["isActive"] for r in _roster_rows_at(client, world, dotless)] == [True]
    assert _login(client, victim.email, PASSWORD).status_code == 200


def test_a_ministry_admin_cannot_end_an_empanelment_by_moving_an_account_onto_it(world, client):
    """SEC1/F3. A move carries an ended empanelment to the new mailbox whatever the account's role,
    and carrying it ENDS an active empanelment there — an admin's, possibly of somebody else.
    Ending one is an admin's act, so a ministry admin moving a PROFESSOR whose old address carries
    an ended empanelment onto an empanelled address is refused, and the empanelment stands. The
    same provisioner moves the same account onto an address nobody is empanelled at."""
    mover = world["people"]["endedProfessor"]
    destination = world["address"]("activeEmpanelment")
    refused = _patch(client, world, "ministry", mover.id, email=destination)
    assert refused.status_code == 409, refused.text
    assert refused.json()["detail"] == (
        account_provisioning.ENDING_AN_EMPANELMENT_BY_MOVING_DETAIL.format(
            email=mover.email, destination=destination
        )
    )
    assert [r["isActive"] for r in _roster_rows_at(client, world, destination)] == [True]
    kept = [row for row in _accounts(client, world, mover.email) if row["id"] == mover.id]
    assert [row["email"] for row in kept] == [mover.email], "the refused move wrote anyway"

    fresh = _fresh(world, "ended-professor-moved")
    moved = _patch(client, world, "ministry", mover.id, email=fresh)
    assert moved.status_code == 200, moved.text


def test_an_admins_same_move_carries_the_ending_onto_the_active_empanelment(world, client):
    """Today's documented behaviour, kept for admins: the move lands and the destination's active
    empanelment is ended, saying why."""
    from app.services.access_roster import EMPANELMENT_CARRIED_BY_EMAIL_MOVE_NOTE

    mover = world["people"]["endedProfessorAdmin"]
    destination = world["address"]("activeEmpanelmentAdmin")
    moved = _patch(client, world, "admin", mover.id, email=destination)
    assert moved.status_code == 200, moved.text
    [row] = _roster_rows_at(client, world, destination)
    assert row["isActive"] is False
    assert EMPANELMENT_CARRIED_BY_EMAIL_MOVE_NOTE in (row["notes"] or "")
