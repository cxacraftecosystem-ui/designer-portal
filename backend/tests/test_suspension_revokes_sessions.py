"""SUSPENDING AN ACCOUNT NOW ENDS THE SESSION IT IS ALREADY IN. Until 2026-09-03 it did not.

**THE DEFECT, IN THE WORDS AN ADMINISTRATOR WOULD USE.** Somebody leaves the institution. An admin
opens ``/admin/access``, presses Suspend, watches the row go SUSPENDED, and tells whoever asked that
that person's access is cut. It was not. Both barring doors on that screen wrote
``AccessRoster.status`` and nothing else, and that column is read on the SIGN-IN path — so the
button stopped the next sign-in and left the browser and the phone the person was already signed in
on working for the rest of their token's life, which is ``JWT_EXPIRES_MINUTES``, seven days by
default. Creating records, reading the repository, exporting. For a week.

Every other revocation in this product is checked per request. This one was checked at a door the
person had already walked through.

**THE FIX IS ONE COLUMN THAT ALREADY EXISTED AND HAD ONE WRITER.** ``User.sessionsValidFrom`` was
added for password-link redemption (``routes/auth.set_password``): a token minted strictly before it
is refused by ``deps._user_from_bearer``, which reads it off the User row it has already loaded to
authenticate the request — no session table, no token store, no extra query on the hot path. The two
barring doors in ``routes/access`` now stamp it too. That is the whole change; everything below is
what it has to keep being true.

WHAT IS PINNED HERE:

1. DELETE (suspend) ends a live session, and it does so on the VERY NEXT REQUEST — which is also the
   test of ``invalidate_cached_user``, because ``_user_from_bearer`` reads that column off whatever
   row the five-second identity cache hands back. A stamp without the invalidation would be a
   revocation that takes effect in five seconds' time, and nothing about the response would say so.
2. REJECT does the same. REJECTED is one of the two BARRED states and bars the address exactly as
   SUSPENDED does; a rule enforced at whichever door somebody happened to look at is not a rule.
3. It is scoped to the person actually barred. Nobody else is signed out.
4. **A ROLE CHANGE IS NOT A REVOCATION.** Losing a tier is not losing access, and signing somebody
   out of their own phone because an admin corrected a dropdown would be worse than the correction.
   Both role-changing doors are exercised: the allow-list's ``admitRole`` and ``PATCH /api/users``.
5. Barring an address with no account behind it is a quiet no-op, not a 500. The allow-list bars
   ADDRESSES and an admin may perfectly well bar somebody who never signed up.
6. The stamp is NOT guarded on the barred-to-barred transition the way the empanelment mirror is,
   and that difference is deliberate rather than an oversight — see the test that pins it.
7. **THE DESIGNER ROSTER IS THE THIRD AND FOURTH BARRING DOOR, FOUND ONE WAVE LATER.**
   ``DELETE /api/designers/roster/{id}`` and a ``PATCH`` carrying ``isActive: false`` end an
   empanelment, and the gate that reads it — ``auth.assert_roster_admits`` — is called from
   ``login_with_google`` and nowhere else. So both of them had the identical defect the allow-list
   screen had: the roster row went inactive, the admin said access was cut, and the designer's
   phone kept working for the rest of the token. Both now stamp.
8. **AND THE STAMP ON THOSE TWO IS GUARDED, WHICH THE ALLOW-LIST'S IS NOT.** A professor or an
   admin who sits on the designer roster because they run workshops keeps their access to the
   product when somebody ends their empanelment — that is ``auth.assert_roster_admits``' own rule —
   so signing them out would be an outage delivered by an unrelated click. The guard is
   ``access_roster.admissions_an_empanelment_carries``, the same predicate that stops the
   cross-roster mirror barring them, and the negative case is pinned below.

NOT PINNED HERE, AND NAMED SO NOBODY READS A GREEN SUITE AS COVERING IT. Every account below is
filed under the same spelling as its roster row, so nothing here exercises the Gmail-alias case.
That case is no longer a GAP: ``routes/access.end_live_sessions`` finds accounts through
``access_roster.accounts_on_the_mailbox``, which canonicalises both sides instead of widening the
``WHERE`` and stamps every spelling of ONE mailbox — closed 2026-09-03, the same day the stamp
itself shipped. What remains unpinned is the sweep WITHDRAWING its answer: when the Gmail sweep is
cut (``access_roster.GMAIL_ACCOUNT_SWEEP_LIMIT``) that lookup returns ``None``, no session is
ended, and the failure is an ERROR log naming the address and the repair —
``scripts/backfill_sessions_valid_from.py``, which is also what repairs rows barred before
2026-09-03.

THE EMPANELMENT DOORS SHOUT ABOUT THAT SAME CUT SWEEP TOO, SINCE 2026-09-03, AND THEY DID NOT
BEFORE. ``admissions_an_empanelment_carries`` answered ``[]`` for a cut sweep — the same value it
gives for a professor whose access rests on something else — so the guard in point 8 read it as
"this person keeps their access" and skipped ``end_live_sessions`` silently, which is the exact
direction that guard was built to shout about. It now answers ``None`` for that case and
``_end_what_the_empanelment_carried`` logs the same ERROR sentence, naming the address and the same
two repairs. Also unpinned here, for the same reason as above: nothing in this module can cut the
sweep without a mailbox that has more spellings than these accounts have.

Postgres is required — every behaviour here is a row deciding an HTTP status — so the module skips
itself when ``DATABASE_URL`` does not point at a local database.

    docker compose up -d postgres minio
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

EVERY TOKEN BELOW IS MINTED DIRECTLY rather than obtained by signing in, matching
``test_platform_access_gate``'s ``_headers``: what is under test is what happens to a token that
already exists, and a helper that signed in first would make each assertion depend on the sign-in
path as well. The one row read that is not an endpoint goes through ``client.portal``, on the loop
that owns the Prisma connection — see ``tests/test_stage_sync.py`` for why awaiting ``db`` from a
test's own loop does not work.
"""

import functools
import uuid
from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password

pytestmark = [needs_db, pytest.mark.anyio]

PASSWORD = "suspension-revokes-sessions-password"

#: The sentence a refused session reads, asserted verbatim. It is deliberately CAUSE-NEUTRAL: this
#: column now has two writers — a password redemption and a suspension — and the sentence it used to
#: carry ("This session ended when the account password was changed") is a lie to the second
#: population, who would go and reset a password that was never wrong. The next sign-in is what can
#: say which, and it does, in the allow-list's own words.
SESSION_ENDED_DETAIL = "This session is no longer valid. Sign in again."

#: slug -> role. Every one gets a ``User`` row AND an ACTIVE allow-list row, so that what these tests
#: observe is the REVOCATION and never the platform gate refusing somebody first.
ACCOUNTS: tuple[tuple[str, str], ...] = (
    ("admin", "ADMIN"),
    ("suspended", "RESEARCHER"),
    ("rejected", "RESEARCHER"),
    ("bystander", "RESEARCHER"),
    # The one the bystander test bars. Its own account rather than a reused one, so that test does
    # not depend on having run after the test that suspends ``suspended``.
    ("collateral", "RESEARCHER"),
    ("demoted", "RESEARCHER"),
    ("relabelled", "DESIGNER"),
    ("twicebarred", "RESEARCHER"),
    # The designer-roster doors. Two designers, one per door, so neither test depends on having run
    # after the other; and one PROFESSOR, who is the negative the guard exists for.
    ("empanelled", "DESIGNER"),
    ("empanelledbypatch", "DESIGNER"),
    ("workshopprofessor", "PROFESSOR"),
)

#: The slugs that also get a ``DesignerRoster`` row — an ACTIVE empanelment, as an admin's earlier
#: click would have left it.
EMPANELLED: tuple[str, ...] = ("empanelled", "empanelledbypatch", "workshopprofessor")

#: ``AccessRoster.admitRole`` where it must NOT be the account's own role.
#:
#: THE PROFESSOR'S ROW SAYS DESIGNER ON PURPOSE, AND THE NEGATIVE BELOW IS WORTH NOTHING WITHOUT IT.
#: ``admissions_an_empanelment_carries`` asks two questions — *was this address admitted AS a
#: designer* and *is the account itself a designer* — and refusing on either one keeps the person's
#: access. Seeding this row as PROFESSOR would make it fail the FIRST test, so the test would pass
#: with the second one deleted, which is the half that actually protects a professor whose stale
#: ``admitRole`` still says DESIGNER (``_lift_existing_account`` and ``PATCH /users/{id}`` both
#: raise ``User.role`` without touching that column — the predicate's own docstring says so).
ADMITTED_AS: dict[str, str] = {"workshopprofessor": "DESIGNER"}


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    """Accounts and their ACTIVE allow-list rows — the state an admin's earlier approvals left."""
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"revoke-{slug}-{stamp}@example.org".lower()

    people: dict[str, Any] = {}
    roster: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role in ACCOUNTS:
            people[slug] = await db.user.create(
                data={
                    "email": address(slug),
                    "name": f"Revocation {slug} {stamp}",
                    "role": role,
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            await db.accessroster.create(
                data={
                    "email": address(slug),
                    "status": "ACTIVE",
                    "admitRole": ADMITTED_AS.get(slug, role),
                    "joinedAt": datetime.now(UTC),
                    "notes": "Seeded by tests/test_suspension_revokes_sessions.py.",
                }
            )
        for slug in EMPANELLED:
            roster[slug] = await db.designerroster.create(
                data={
                    "email": address(slug),
                    "fullName": f"Revocation {slug} {stamp}",
                    "isActive": True,
                    "notes": "Seeded by tests/test_suspension_revokes_sessions.py.",
                }
            )
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "people": people,
            "roster": roster,
            "address": address,
            "stamp": stamp,
        }


@pytest.fixture
def client(world):
    return world["client"]


def _token(world: dict[str, Any], slug: str) -> dict[str, str]:
    """A live session for this account, minted the way ``login`` would have minted it."""
    return {"Authorization": f"Bearer {create_access_token(subject=world['people'][slug].id)}"}


def _me(client: Any, headers: dict[str, str]) -> Any:
    """The cheapest authenticated request there is. It goes through ``get_current_user``, which is
    where the revocation check lives, so any authenticated endpoint would answer the same."""
    return client.get("/api/me", headers=headers)


def _access_row(client: Any, world: dict[str, Any], email: str) -> dict[str, Any]:
    rows = client.get(
        "/api/access/roster",
        params={"search": email, "pageSize": 200},
        headers=_token(world, "admin"),
    )
    assert rows.status_code == 200, rows.text
    found = {row["email"]: row for row in rows.json()["items"]}
    assert email in found, f"no allow-list row for {email}; rows seen: {sorted(found)}"
    return found[email]


def _sessions_valid_from(client: Any, world: dict[str, Any], slug: str) -> Any:
    """The stored column itself, read on the client's own loop.

    No endpoint reports it — deliberately; it is a revocation watermark and not a field a screen has
    any business rendering — and one test below is about the stamp MOVING rather than about a status
    code. ``client.portal.call`` runs the query on the loop that owns the connection; see the module
    docstring and the banner in ``tests/test_stage_sync.py``.
    """
    row = client.portal.call(
        functools.partial(db.user.find_unique, where={"id": world["people"][slug].id})
    )
    return row.sessionsValidFrom


async def test_suspending_an_account_ends_the_session_it_is_already_in(world, client):
    """**THE FINDING, END TO END, THROUGH THE BUTTON AN ADMIN ACTUALLY PRESSES.**

    The session is used BEFORE the suspension for two reasons. It proves the token was good, so the
    401 afterwards cannot be a token that never worked — and it WARMS THE IDENTITY CACHE, which is
    the second half of what this test pins. ``_user_from_bearer`` reads ``sessionsValidFrom`` off the
    row ``resolve_user`` returns, and that row can be a cached one for up to
    ``AUTH_USER_CACHE_TTL_SECONDS``. Without ``invalidate_cached_user`` beside the write, the very
    next request would be answered from the pre-revocation copy and this test would fail — which is
    exactly the failure worth having a test for, because in production it looks like the suspension
    "taking a moment to apply" rather than like a bug.
    """
    email = world["address"]("suspended")
    session = _token(world, "suspended")
    assert _me(client, session).status_code == 200, "the session must be good before it is ended"

    row_id = _access_row(client, world, email)["id"]
    barred = client.delete(f"/api/access/roster/{row_id}", headers=_token(world, "admin"))
    assert barred.status_code == 200, barred.text
    assert barred.json()["status"] == "SUSPENDED"

    refused = _me(client, session)
    assert refused.status_code == 401, (
        "the account was suspended and the session it was already in went on working; an "
        "administrator who pressed Suspend believes access is cut and for seven days it is not: "
        f"{refused.text}"
    )
    assert refused.json()["detail"] == SESSION_ENDED_DETAIL, (
        "the refusal must not name a password change — this person's password was never touched, "
        "and telling them it was sends them to reset something that was not wrong"
    )


async def test_rejecting_a_request_ends_the_session_too(world, client):
    """THE OTHER BARRING DOOR ON THE SAME SCREEN, AND IT MUST NOT BE THE ONE THAT FORGOT.

    REJECTED is one of the two states in ``access_roster.BARRED``; it bars the address from the
    application exactly as a suspension does, and ``deps.require_dataset_admin`` already treats the
    two identically as a cut list. A revocation enforced at whichever door somebody happened to look
    at is the shape of defect this module exists about, one door over.
    """
    email = world["address"]("rejected")
    session = _token(world, "rejected")
    assert _me(client, session).status_code == 200, "the session must be good before it is ended"

    row_id = _access_row(client, world, email)["id"]
    decided = client.post(
        f"/api/access/roster/{row_id}/decision",
        json={"decision": "REJECT"},
        headers=_token(world, "admin"),
    )
    assert decided.status_code == 200, decided.text
    assert decided.json()["status"] == "REJECTED"

    refused = _me(client, session)
    assert refused.status_code == 401, (
        "rejecting somebody barred them from the application and left the session they were "
        f"already in running: {refused.text}"
    )


async def test_barring_one_person_signs_out_nobody_else(world, client):
    """THE SCOPE OF IT. A revocation that reached further than the person barred would be an outage
    delivered by an ordinary administrative click, and nothing on the screen would say so."""
    bystander = _token(world, "bystander")
    assert _me(client, bystander).status_code == 200

    row_id = _access_row(client, world, world["address"]("collateral"))["id"]
    barred = client.delete(f"/api/access/roster/{row_id}", headers=_token(world, "admin"))
    assert barred.status_code == 200, barred.text
    assert barred.json()["status"] == "SUSPENDED"

    assert _me(client, bystander).status_code == 200, (
        "barring one address signed a different account out of the product"
    )


async def test_changing_somebodys_role_is_not_a_revocation(world, client):
    """**LOSING A TIER IS NOT LOSING ACCESS**, and both role-changing doors are asked.

    An admin correcting a dropdown has not decided that this person may no longer be here. Signing
    them out of their own phone for it would be a worse answer than the correction, and it is the
    obvious over-reach for somebody implementing "suspension revokes sessions" in a hurry: the two
    writes sit next to each other in ``routes/access`` and one of them must not learn the other's
    habit. The identity cache is invalidated on a role change instead, which is what makes the
    DEMOTION itself take effect on the very next request without ending anything.
    """
    relabelled = _token(world, "relabelled")
    assert _me(client, relabelled).status_code == 200

    row_id = _access_row(client, world, world["address"]("relabelled"))["id"]
    corrected = client.patch(
        f"/api/access/roster/{row_id}",
        json={"role": "RESEARCHER"},
        headers=_token(world, "admin"),
    )
    assert corrected.status_code == 200, corrected.text
    assert corrected.json()["admitRole"] == "RESEARCHER"
    assert _me(client, relabelled).status_code == 200, (
        "correcting the tier an allow-list row admits somebody at signed them out"
    )

    demoted = _token(world, "demoted")
    assert _me(client, demoted).status_code == 200
    lowered = client.patch(
        f"/api/users/{world['people']['demoted'].id}",
        json={"role": "FIELD_CONTRIBUTOR"},
        headers=_token(world, "admin"),
    )
    assert lowered.status_code == 200, lowered.text
    assert lowered.json()["role"] == "FIELD_CONTRIBUTOR"
    after = _me(client, demoted)
    assert after.status_code == 200, "demoting an account signed it out"
    assert after.json()["role"] == "FIELD_CONTRIBUTOR", (
        "the demotion did not reach the very next request, which is what the identity-cache "
        "invalidation beside that write is for"
    )


async def test_barring_an_address_with_no_account_behind_it_is_quiet(world, client):
    """THE ALLOW-LIST BARS ADDRESSES, NOT ACCOUNTS, AND THE TWO ARE NOT THE SAME SET.

    An admin may bar somebody they invited last week who never signed up. There is nothing to revoke,
    and the lookup answering "no row" must be a no-op rather than a 500 — an endpoint that threw here
    would make the Suspend button fail for exactly the rows it is most often pressed on.
    """
    address = f"revoke-neversignedup-{world['stamp']}@example.org"
    admitted = client.post(
        "/api/access/roster",
        json={"email": address, "role": "RESEARCHER"},
        headers=_token(world, "admin"),
    )
    assert admitted.status_code == 201, admitted.text

    barred = client.delete(
        f"/api/access/roster/{admitted.json()['id']}", headers=_token(world, "admin")
    )
    assert barred.status_code == 200, barred.text
    assert barred.json()["status"] == "SUSPENDED"


async def test_re_barring_an_already_rejected_row_still_ends_the_session(world, client):
    """**THE STAMP IS NOT GUARDED ON THE TRANSITION, UNLIKE THE EMPANELMENT MIRROR BESIDE IT.**

    That guard exists because re-enacting the mirror can UNDO something an administrator did on
    purpose in the meantime — restore an empanelment on the other screen. Ending sessions undoes
    nothing: the only thing a second stamp can do is refuse tokens this row already says must be
    refused. And REJECTED → SUSPENDED is precisely where an earlier bar may predate this change and
    have left a seven-day token alive, so copying the mirror's guard here would be copying a rule
    past the reason for it.

    Asserted on the STORED WATERMARK rather than on a status code, because both stamps refuse the
    same tokens and no response can tell them apart. The read goes through the client's portal — see
    :func:`_sessions_valid_from`.
    """
    email = world["address"]("twicebarred")
    row_id = _access_row(client, world, email)["id"]

    rejected = client.post(
        f"/api/access/roster/{row_id}/decision",
        json={"decision": "REJECT"},
        headers=_token(world, "admin"),
    )
    assert rejected.status_code == 200, rejected.text
    first = _sessions_valid_from(client, world, "twicebarred")
    assert first is not None, "the rejection did not stamp anything, so this test proves nothing"

    suspended = client.delete(f"/api/access/roster/{row_id}", headers=_token(world, "admin"))
    assert suspended.status_code == 200, suspended.text
    assert suspended.json()["status"] == "SUSPENDED", (
        "REJECTED -> SUSPENDED is a real write; if this row did not move, the early return covered "
        "it and the case this test is about was never reached"
    )
    second = _sessions_valid_from(client, world, "twicebarred")
    assert second > first, (
        "the second barring did not re-stamp the revocation watermark, so a row barred before this "
        "change shipped keeps its live sessions however many times an administrator presses the "
        "button"
    )


# --------------------------------------------------------------------------------------
# The OTHER roster: ending an empanelment, through both of its doors
#
# ``auth.assert_roster_admits`` is asked once, by ``login_with_google``. Everything below is about
# the person who has already been asked.
# --------------------------------------------------------------------------------------


async def test_ending_an_empanelment_ends_the_session_it_was_carrying(world, client):
    """**THE SAME DEFECT, ON THE SCREEN NEXT DOOR, THROUGH THE BUTTON AN ADMIN ACTUALLY PRESSES.**

    An admin opens ``/admin/designers``, removes a designer who has left the programme, and watches
    the row go inactive. Before this, that designer's phone went on filing records into the
    repository for the rest of its token — the empanelment gate had already let them in and is never
    asked again. The mirror that suspends their allow-list row shipped first and did not close this:
    ``AccessRoster.status`` is read on the sign-in path too.

    The session is used before the revocation for the two reasons the allow-list test gives: it
    proves the token was good, and it WARMS the identity cache, so a stamp without
    ``invalidate_cached_user`` beside it would leave this green for five seconds and red after.
    """
    session = _token(world, "empanelled")
    assert _me(client, session).status_code == 200, "the session must be good before it is ended"

    ended = client.delete(
        f"/api/designers/roster/{world['roster']['empanelled'].id}",
        headers=_token(world, "admin"),
    )
    assert ended.status_code == 200, ended.text
    assert ended.json()["isActive"] is False

    refused = _me(client, session)
    assert refused.status_code == 401, (
        "the empanelment was ended and the session it was carrying went on working; the admin who "
        "pressed it believes the designer's access is cut and for seven days it is not: "
        f"{refused.text}"
    )
    assert refused.json()["detail"] == SESSION_ENDED_DETAIL


async def test_the_patch_door_onto_the_same_decision_ends_it_too(world, client):
    """``isActive: false`` IS THE SAME ACT AS THE DELETE, AND MUST NOT BE THE DOOR THAT FORGOT.

    Two endpoints end one empanelment — the roster screen sends the DELETE, an admin editing a row
    sends this — and a rule enforced at whichever door somebody happened to look at is the exact
    shape of bug this whole module is about. It is also the door a rushed implementation misses,
    because the mirror call it must sit beside is written differently here (guarded on the
    transition, reading ``updated.email`` rather than ``row.email``).
    """
    session = _token(world, "empanelledbypatch")
    assert _me(client, session).status_code == 200

    ended = client.patch(
        f"/api/designers/roster/{world['roster']['empanelledbypatch'].id}",
        json={"isActive": False},
        headers=_token(world, "admin"),
    )
    assert ended.status_code == 200, ended.text
    assert ended.json()["isActive"] is False

    refused = _me(client, session)
    assert refused.status_code == 401, (
        "ending an empanelment through the PATCH door left the live session running, so the two "
        f"doors onto one decision disagree about what it means: {refused.text}"
    )


async def test_ending_a_professors_empanelment_does_not_sign_the_professor_out(world, client):
    """**THE NEGATIVE THE GUARD EXISTS FOR, AND THE ONE THIS FEATURE COULD DO REAL HARM WITHOUT.**

    A professor or an admin can be on the designer roster because they run workshops. Their place in
    this application does not rest on that empanelment — ``auth.assert_roster_admits`` returns early
    for any role that is not DESIGNER, and says at length why collapsing the two refusals would be
    an outage. So ending their empanelment must leave both their admission and their SESSION alone.

    Their allow-list row is seeded saying ``admitRole: DESIGNER`` on purpose (see ``ADMITTED_AS``):
    that is the stale, ordinary state — nothing keeps that column in step with ``User.role`` — and
    it is what makes this a test of the account-role half of
    ``access_roster.admissions_an_empanelment_carries`` rather than of the easy half.

    Asserted on the WATERMARK as well as on the status code. A 200 alone would still pass if the
    stamp had been written a microsecond after the token's ``iat``, and the failure this pins is not
    "the professor was refused today" but "an administrative click ended every session they hold".
    """
    session = _token(world, "workshopprofessor")
    assert _me(client, session).status_code == 200

    ended = client.delete(
        f"/api/designers/roster/{world['roster']['workshopprofessor'].id}",
        headers=_token(world, "admin"),
    )
    assert ended.status_code == 200, ended.text
    assert ended.json()["isActive"] is False

    assert _me(client, session).status_code == 200, (
        "ending an empanelment signed out a PROFESSOR whose access to this product never rested on "
        "it — the exact outage access_roster.admissions_an_empanelment_carries exists to prevent, "
        "reached through the session column instead of through the allow-list"
    )
    assert _sessions_valid_from(client, world, "workshopprofessor") is None, (
        "no revocation watermark should have been written at all; a stamp that merely happens to "
        "predate this token is a bug waiting for a slower clock"
    )
    assert _access_row(client, world, world["address"]("workshopprofessor"))["status"] == "ACTIVE", (
        "the allow-list row was mirrored too, so the guard is not being asked at all and the "
        "session assertion above proves nothing"
    )
