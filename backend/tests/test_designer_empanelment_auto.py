import functools
import os
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from typing import Any

import pytest

from app.api.routes import auth as auth_routes
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import access_roster, designers as designers_service
from app.services.designers import DERIVED_EMPANELMENT_NOTE

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

PASSWORD = "empanelment-test-password"
API_PASSWORD = "LocalDev123!"

DESIGNER_SUSPENDED_DETAIL = "Your designer access has been suspended. Contact the administrator."
PENDING_DETAIL = (
    "Your access request is awaiting administrator approval. This is not a password problem — an "
    "administrator has to approve this address before you can sign in."
)
ACCESS_SUSPENDED_DETAIL = (
    "Your access to this application has been suspended. Contact the administrator."
)

ADMIN_NOTE = "Empanelled by hand under the 2026 cluster programme. Do not remove."

ACCOUNTS: tuple[tuple[str, str], ...] = (
    ("admin", "ADMIN"),
    ("newcomer", "DESIGNER"),
    ("revoked", "DESIGNER"),
    ("racer", "DESIGNER"),
)

EMPANELLED: tuple[tuple[str, bool], ...] = (
    ("revoked", False),
    ("racer", True),
)

GMAIL_ADMITTED: tuple[str, ...] = ("handtyped", "revokeddots")

MIRROR_ALIASED: tuple[str, ...] = ("aliased",)


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"empanel-{slug}-{stamp}@example.org".lower()

    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role in ACCOUNTS:
            people[slug] = await db.user.create(data={
                "email": address(slug),
                "name": f"Empanelment {slug} {stamp}",
                "role": role,
                "passwordHash": hash_password(PASSWORD),
            })
            await db.accessroster.create(data={
                "email": address(slug),
                "status": "ACTIVE",
                "admitRole": role,
                "joinedAt": datetime.now(UTC),
                "notes": "Seeded by tests/test_designer_empanelment_auto.py.",
            })
        for slug in GMAIL_ADMITTED:
            await db.accessroster.create(data={
                "email": f"empanel{slug}{stamp}@gmail.com",
                "status": "ACTIVE",
                "admitRole": "DESIGNER",
                "joinedAt": datetime.now(UTC),
                "notes": "Seeded by tests/test_designer_empanelment_auto.py, section 5.",
            })
        for slug in MIRROR_ALIASED:
            await db.accessroster.create(data={
                "email": f"empanel.{slug}.{stamp}@googlemail.com",
                "status": "ACTIVE",
                "admitRole": "DESIGNER",
                "joinedAt": datetime.now(UTC),
                "notes": "Seeded by tests/test_designer_empanelment_auto.py, section 6.",
            })
            await db.designerroster.create(data={
                "email": f"empanel{slug}{stamp}@gmail.com",
                "fullName": f"Aliased roster row for {slug}",
                "isActive": True,
                "revokedAt": None,
                "notes": ADMIN_NOTE,
                "addedById": people["admin"].id,
            })
        for slug, is_active in EMPANELLED:
            await db.designerroster.create(data={
                "email": address(slug),
                "fullName": f"Roster row for {slug}",
                "isActive": is_active,
                "revokedAt": None if is_active else datetime.now(UTC),
                "notes": ADMIN_NOTE,
                "addedById": people["admin"].id,
            })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {"client": client, "people": people, "address": address, "stamp": stamp}


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str = "admin") -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['people'][slug].id)}"}


def _login(client: Any, email: str, password: str = PASSWORD) -> Any:
    return client.post("/api/auth/login", json={"email": email, "password": password})


def _google(client: Any, monkeypatch: Any, email: str) -> Any:
    monkeypatch.setattr(
        auth_routes,
        "verify_google_token",
        lambda _token: {"email": email, "email_verified": True, "name": "Google Person"},
    )
    return client.post("/api/auth/login", json={"googleIdToken": "stand-in-for-a-real-token"})


def _empanelments(client: Any, world: dict[str, Any], term: str | None = None) -> dict[str, Any]:
    response = client.get(
        "/api/designers/roster",
        params={"search": term or world["stamp"], "pageSize": 200},
        headers=_headers(world),
    )
    assert response.status_code == 200, response.text
    return {row["email"]: row for row in response.json()["items"]}


def _rows_spelling(
    client: Any, world: dict[str, Any], *spellings: str, term: str | None = None
) -> list[dict[str, Any]]:
    response = client.get(
        "/api/designers/roster",
        params={"search": term or world["stamp"], "pageSize": 200},
        headers=_headers(world),
    )
    assert response.status_code == 200, response.text
    wanted = set(spellings)
    return [row for row in response.json()["items"] if row["email"] in wanted]


def _access_rows(client: Any, world: dict[str, Any], term: str | None = None) -> dict[str, Any]:
    response = client.get(
        "/api/access/roster",
        params={"search": term or world["stamp"], "pageSize": 200},
        headers=_headers(world),
    )
    assert response.status_code == 200, response.text
    return {row["email"]: row for row in response.json()["items"]}


def _admit(client: Any, world: dict[str, Any], email: str, role: str) -> Any:
    return client.post(
        "/api/access/roster", json={"email": email, "role": role}, headers=_headers(world)
    )


def _fresh(world: dict[str, Any], slug: str) -> str:
    return f"empanel-{slug}-{world['stamp']}@example.org".lower()


def _make_designer_account(client: Any, world: dict[str, Any], email: str, label: str) -> None:
    client.portal.call(
        functools.partial(
            db.user.create,
            data={
                "email": email,
                "name": f"{label} {world['stamp']}",
                "role": "DESIGNER",
                "passwordHash": hash_password(API_PASSWORD),
            },
        )
    )
    client.portal.call(
        functools.partial(
            db.accessroster.create,
            data={
                "email": email,
                "status": "ACTIVE",
                "admitRole": "DESIGNER",
                "joinedAt": datetime.now(UTC),
                "notes": f"Seeded by tests/test_designer_empanelment_auto.py: {label}.",
            },
        )
    )


def _two_sign_ins_at_once(client: Any, email: str) -> list[Any]:
    ready = threading.Barrier(2, timeout=30)

    def attempt() -> Any:
        ready.wait()
        return client.post("/api/auth/login", json={"email": email, "password": API_PASSWORD})

    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(attempt), pool.submit(attempt)]
        return [future.result(timeout=120) for future in futures]



async def test_an_allow_listed_designer_with_no_empanelment_is_empanelled_at_sign_in(
    world, client
):
    email = world["address"]("newcomer")
    assert email not in _empanelments(client, world), (
        "the fixture must NOT have empanelled this account, or this test is asserting nothing about "
        "auto-empanelment and would keep passing if the feature were deleted"
    )

    response = _login(client, email)
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "DESIGNER"

    row = _empanelments(client, world)[email]
    assert row["isActive"] is True
    assert row["revokedAt"] is None, "an active row carrying a revocation date reads as a bug"
    assert row["addedById"] is None, "nobody administered this; naming an admin would be a lie"
    assert row["notes"] == DERIVED_EMPANELMENT_NOTE
    assert row["firstSeenAt"] is not None


async def test_signing_in_again_creates_nothing_and_moves_nothing(world, client):
    email = world["address"]("newcomer")
    before = _empanelments(client, world)[email]
    assert before["firstSeenAt"] is not None, "the test above must have signed this designer in"

    assert _login(client, email).status_code == 200
    after = _empanelments(client, world)[email]

    assert after["id"] == before["id"], "a second row was created for one address"
    assert after["firstSeenAt"] == before["firstSeenAt"]
    assert after["notes"] == before["notes"]


async def test_a_revoked_empanelment_is_never_revived_by_signing_in(world, client):
    email = world["address"]("revoked")
    before = _empanelments(client, world)[email]
    assert before["isActive"] is False, "the fixture must have revoked this one, or this proves nothing"

    response = _login(client, email)
    assert response.status_code == 403, response.text
    assert response.json()["detail"] == DESIGNER_SUSPENDED_DETAIL

    after = _empanelments(client, world)[email]
    assert after["isActive"] is False, (
        "signing in revived a SUSPENDED empanelment; that is an administrator's revocation being "
        "undone silently, which is exactly what the create-only-where-no-row-exists rule prevents"
    )
    assert after["revokedAt"] == before["revokedAt"]
    assert after["notes"] == ADMIN_NOTE
    assert after["firstSeenAt"] is None


class _RosterThatNeverFindsARow:
    def __init__(self, real: Any) -> None:
        self._real = real
        self.reads = 0

    async def find_first(self, *args: Any, **kwargs: Any) -> None:
        self.reads += 1

    async def find_unique(self, *args: Any, **kwargs: Any) -> None:
        self.reads += 1

    def __getattr__(self, name: str) -> Any:
        return getattr(self._real, name)


class _DbWhoseRosterNeverFindsARow:
    def __init__(self, real: Any) -> None:
        self._real = real
        self.designerroster = _RosterThatNeverFindsARow(real.designerroster)

    def __getattr__(self, name: str) -> Any:
        return getattr(self._real, name)


async def test_a_lost_race_is_answered_quietly_instead_of_breaking_the_sign_in(
    world, client, monkeypatch
):
    email = world["address"]("racer")
    before = _empanelments(client, world)[email]
    assert before["isActive"] is True, "the fixture must have empanelled this one"

    blind = _DbWhoseRosterNeverFindsARow(designers_service.db)
    monkeypatch.setattr(designers_service, "db", blind)
    response = _login(client, email)
    assert response.status_code == 200, response.text

    assert blind.designerroster.reads > 0, (
        "the existence read was never served by the stub, so this sign-in never attempted the "
        "create and the losing half of the race was not exercised at all"
    )
    after = _empanelments(client, world)[email]
    assert after["id"] == before["id"], "the race produced a second row for one address"
    assert after["notes"] == ADMIN_NOTE, "the losing create overwrote the administrator's row"


async def test_two_simultaneous_sign_ins_for_one_new_designer_leave_exactly_one_row(world, client):
    email = _fresh(world, "twiceatonce")
    _make_designer_account(client, world, email, "Simultaneous designer")
    assert email not in _empanelments(client, world, term=email), (
        "the account already had an empanelment before either sign-in, so there is no race here"
    )

    first, second = _two_sign_ins_at_once(client, email)
    assert [first.status_code, second.status_code] == [200, 200], (first.text, second.text)

    rows = _rows_spelling(client, world, email, term=email)
    assert len(rows) == 1, (
        f"one address came out of a simultaneous sign-in with {len(rows)} empanelments: "
        f"{[row['email'] for row in rows]}"
    )
    assert rows[0]["notes"] == DERIVED_EMPANELMENT_NOTE
    assert rows[0]["firstSeenAt"] is not None, "both sign-ins succeeded and neither stamped arrival"


async def test_the_losing_half_of_a_race_for_a_new_designer_is_let_in_on_the_winners_row(
    world, client, monkeypatch
):
    email = _fresh(world, "lostrace")
    _make_designer_account(client, world, email, "Racing designer")
    assert email not in _empanelments(client, world, term=email), (
        "the account already had an empanelment, so the winner below creates nothing and the "
        "'losing half' this test replays is not the half it names"
    )

    blind = _DbWhoseRosterNeverFindsARow(designers_service.db)
    monkeypatch.setattr(designers_service, "db", blind)

    winner = _login(client, email, password=API_PASSWORD)
    assert winner.status_code == 200, winner.text
    created = _rows_spelling(client, world, email, term=email)
    assert len(created) == 1, f"the first sign-in wrote {len(created)} rows"

    loser = _login(client, email, password=API_PASSWORD)
    assert loser.status_code == 200, (
        "the designer who lost the race was refused, or told the server broke, over a row that "
        f"exists and admits them: {loser.text}"
    )

    after = _rows_spelling(client, world, email, term=email)
    assert [row["id"] for row in after] == [row["id"] for row in created], (
        "the losing create was not refused by the unique index, so one designer now holds two "
        "empanelments and suspending the visible one leaves the other still admitting them"
    )
    assert blind.designerroster.reads >= 2, (
        f"the stub served {blind.designerroster.reads} existence reads for two sign-ins, so at "
        "least one of them took a path that never attempted the create this test is about"
    )



async def test_admitting_an_address_as_a_designer_empanels_it_immediately(world, client):
    email = _fresh(world, "approved")
    admitted = _admit(client, world, email, "DESIGNER")
    assert admitted.status_code == 201, admitted.text

    row = _empanelments(client, world)[email]
    assert row["isActive"] is True
    assert row["addedById"] == world["people"]["admin"].id
    assert row["firstSeenAt"] is None


async def test_admitting_somebody_who_is_not_a_designer_empanels_nobody(world, client):
    email = _fresh(world, "researcher")
    admitted = _admit(client, world, email, "RESEARCHER")
    assert admitted.status_code == 201, admitted.text
    assert email not in _empanelments(client, world)


async def test_approving_a_waiting_request_at_designer_empanels_them(world, client, monkeypatch):
    email = _fresh(world, "queued")
    refused = _google(client, monkeypatch, email)
    assert refused.status_code == 403, refused.text
    assert _access_rows(client, world, term=email)[email]["status"] == "PENDING"

    row_id = _access_rows(client, world, term=email)[email]["id"]
    decided = client.post(
        f"/api/access/roster/{row_id}/decision",
        json={"decision": "APPROVE", "role": "DESIGNER"},
        headers=_headers(world),
    )
    assert decided.status_code == 200, decided.text

    empanelment = _empanelments(client, world, term=email)[email]
    assert empanelment["isActive"] is True
    assert empanelment["addedById"] == world["people"]["admin"].id
    assert empanelment["firstSeenAt"] is None


async def test_correcting_a_rows_role_up_to_designer_empanels_them(world, client):
    email = _fresh(world, "corrected")
    assert _admit(client, world, email, "RESEARCHER").status_code == 201
    assert email not in _empanelments(client, world), "the control: a researcher is not empanelled"

    row_id = _access_rows(client, world, term=email)[email]["id"]
    corrected = client.patch(
        f"/api/access/roster/{row_id}", json={"role": "DESIGNER"}, headers=_headers(world)
    )
    assert corrected.status_code == 200, corrected.text
    assert _empanelments(client, world, term=email)[email]["isActive"] is True


async def test_editing_a_waiting_rows_role_to_designer_approves_nobody(
    world, client, monkeypatch
):
    email = _fresh(world, "waiting")
    assert _google(client, monkeypatch, email).status_code == 403
    row = _access_rows(client, world, term=email)[email]
    assert row["status"] == "PENDING"

    edited = client.patch(
        f"/api/access/roster/{row['id']}", json={"role": "DESIGNER"}, headers=_headers(world)
    )
    assert edited.status_code == 200, edited.text
    assert edited.json()["status"] == "PENDING", "an edit must not have decided anything"
    assert email not in _empanelments(client, world, term=email), (
        "a PENDING request was empanelled by an edit, which admits it through the designer-roster "
        "clause in the sign-in gate — an approval nobody made"
    )

    still_waiting = _google(client, monkeypatch, email)
    assert still_waiting.status_code == 403, still_waiting.text
    assert still_waiting.json()["detail"] == PENDING_DETAIL


async def test_editing_a_barred_rows_role_to_designer_empanels_nobody(world, client):
    email = _fresh(world, "barred")
    assert _admit(client, world, email, "RESEARCHER").status_code == 201
    row_id = _access_rows(client, world, term=email)[email]["id"]
    suspended = client.delete(f"/api/access/roster/{row_id}", headers=_headers(world))
    assert suspended.status_code == 200, suspended.text
    assert suspended.json()["status"] == "SUSPENDED"

    edited = client.patch(
        f"/api/access/roster/{row_id}", json={"role": "DESIGNER"}, headers=_headers(world)
    )
    assert edited.status_code == 200, edited.text
    assert email not in _empanelments(client, world, term=email), (
        "an address an administrator barred was put on the designer roster as ACTIVE by an edit"
    )



async def test_an_approvals_empanelment_waits_for_the_person_to_actually_arrive(world, client):
    email = _fresh(world, "arrives")
    assert _admit(client, world, email, "DESIGNER").status_code == 201
    granted = _empanelments(client, world, term=email)[email]
    assert granted["firstSeenAt"] is None, "an empanelment nobody has used yet cannot be 'seen'"

    made = client.post(
        "/api/users",
        json={
            "email": email,
            "name": f"Arriving designer {world['stamp']}",
            "password": API_PASSWORD,
            "role": "DESIGNER",
        },
        headers=_headers(world),
    )
    assert made.status_code == 201, made.text
    assert _empanelments(client, world, term=email)[email]["firstSeenAt"] is None, (
        "creating the account is not the designer arriving; only a sign-in is"
    )

    arrived = _login(client, email, password=API_PASSWORD)
    assert arrived.status_code == 200, arrived.text
    seen = _empanelments(client, world, term=email)[email]
    assert seen["firstSeenAt"] is not None
    assert seen["id"] == granted["id"], "the sign-in created a second row instead of using theirs"
    assert seen["addedById"] == world["people"]["admin"].id, (
        "the sign-in overwrote the administrator's empanelment with a derived one"
    )



def _gmail_as_typed(world: dict[str, Any], slug: str) -> str:
    return f"empanel.{slug}.{world['stamp']}@gmail.com"


def _gmail_mailbox(world: dict[str, Any], slug: str) -> str:
    return f"empanel{slug}{world['stamp']}@gmail.com"


async def test_a_designer_admitted_with_gmail_dots_signs_in_without_them(world, client, monkeypatch):
    typed = _gmail_as_typed(world, "gdots")
    mailbox = _gmail_mailbox(world, "gdots")

    admitted = _admit(client, world, typed, "DESIGNER")
    assert admitted.status_code == 201, admitted.text
    assert admitted.json()["email"] == mailbox, (
        "the allow-list stored the spelling that was typed rather than the mailbox it reaches, so "
        "the Google token for this person will match nothing"
    )

    granted = _rows_spelling(client, world, typed, mailbox)
    assert [row["email"] for row in granted] == [mailbox], (
        f"expected one empanelment stored as the mailbox; got {[r['email'] for r in granted]}"
    )
    assert granted[0]["firstSeenAt"] is None, "nobody has signed in yet"

    arrived = _google(client, monkeypatch, mailbox)
    assert arrived.status_code == 200, arrived.text
    assert arrived.json()["user"]["role"] == "DESIGNER", (
        "the allow-list row admitting this mailbox as a DESIGNER was not found from the undotted "
        "spelling, so the account was provisioned at the default tier instead"
    )

    after = _rows_spelling(client, world, typed, mailbox)
    assert [row["email"] for row in after] == [mailbox], (
        "signing in manufactured a second roster row for one mailbox — the duplicate an admin then "
        "cannot reconcile, and which suspending one of would not stop the other admitting them"
    )
    assert after[0]["id"] == granted[0]["id"]
    assert after[0]["addedById"] == world["people"]["admin"].id, (
        "the derived empanelment overwrote the one an administrator actually granted"
    )
    assert after[0]["firstSeenAt"] is not None, (
        "the gate admitted this designer on the canonical key and then stamped nothing, so the "
        "admin who empanelled them reads a blank 'first seen' for somebody using the app right now"
    )


async def test_dots_outside_gmail_are_two_different_people(world, client, monkeypatch):
    typed = f"empanel.dots.{world['stamp']}@example.org"
    collapsed = f"empaneldots{world['stamp']}@example.org"

    admitted = _admit(client, world, typed, "DESIGNER")
    assert admitted.status_code == 201, admitted.text
    assert admitted.json()["email"] == typed, "a dot was removed from an address outside Gmail"
    assert [row["email"] for row in _rows_spelling(client, world, typed, collapsed)] == [typed]

    stranger = _google(client, monkeypatch, collapsed)
    assert stranger.status_code == 403, stranger.text
    assert stranger.json()["detail"] == PENDING_DETAIL, (
        "an address that differs from an admitted one by a dot was let in outside Gmail, where a "
        "dot is an ordinary character in somebody else's local part"
    )
    assert _access_rows(client, world, term=collapsed)[collapsed]["status"] == "PENDING", (
        "the undotted spelling was matched onto the admitted row instead of queueing as itself"
    )
    assert [row["email"] for row in _rows_spelling(client, world, typed, collapsed)] == [typed], (
        "the refused stranger was nonetheless empanelled under their own spelling"
    )

    theirs = _google(client, monkeypatch, typed)
    assert theirs.status_code == 200, theirs.text
    assert theirs.json()["user"]["role"] == "DESIGNER"



def _dotted_roster_row(client: Any, world: dict[str, Any], email: str, *, active: bool) -> Any:
    return client.post(
        "/api/designers/roster",
        json={
            "email": email,
            "fullName": f"Hand-typed roster row {world['stamp']}",
            "notes": ADMIN_NOTE,
            "isActive": active,
        },
        headers=_headers(world),
    )


async def test_a_dotted_gmail_row_is_the_row_the_undotted_sign_in_lands_on(
    world, client, monkeypatch
):
    typed = _gmail_as_typed(world, "handtyped")
    mailbox = _gmail_mailbox(world, "handtyped")

    empanelled = _dotted_roster_row(client, world, typed, active=True)
    assert empanelled.status_code == 201, empanelled.text
    assert empanelled.json()["email"] == mailbox, (
        "POST /api/designers/roster stored what was typed rather than the mailbox it resolves to, "
        "which is the write-side half of the canonicalisation fix regressing"
    )
    before = _rows_spelling(client, world, typed, mailbox)
    assert [row["email"] for row in before] == [mailbox], (
        f"the state under test was not reached; rows present before the sign-in: {before}"
    )

    arrived = _google(client, monkeypatch, mailbox)
    assert arrived.status_code == 200, arrived.text

    after = _rows_spelling(client, world, typed, mailbox)
    assert [row["email"] for row in after] == [mailbox], (
        "signing in on the mailbox created a second empanelment beside the one an administrator "
        "typed; one designer now holds two roster rows, and suspending the row the admin can see "
        f"would leave the other one admitting them: {[r['email'] for r in after]}"
    )
    assert after[0]["addedById"] == world["people"]["admin"].id, (
        "a derived row displaced the administrator's, erasing the record that a person made this "
        "decision — the one thing /admin/designers exists to hold"
    )
    assert after[0]["notes"] == ADMIN_NOTE
    assert after[0]["firstSeenAt"] is not None, (
        "the designer signed in and the administrator's row still reads as never accepted, which "
        "would mean the arrival was stamped on a different row for the same mailbox"
    )


async def test_a_revocation_typed_with_the_dots_is_not_walked_around_by_the_mailbox(
    world, client, monkeypatch
):
    typed = _gmail_as_typed(world, "revokeddots")
    mailbox = _gmail_mailbox(world, "revokeddots")

    revoked = _dotted_roster_row(client, world, typed, active=False)
    assert revoked.status_code == 201, revoked.text
    assert revoked.json()["email"] == mailbox, (
        "POST /api/designers/roster stored what was typed rather than the mailbox it resolves to, "
        "which is the write-side half of the canonicalisation fix regressing"
    )
    assert revoked.json()["isActive"] is False, "the administrator's revocation must be recorded"
    before = _rows_spelling(client, world, typed, mailbox)
    assert [(row["email"], row["isActive"]) for row in before] == [(mailbox, False)], (
        f"the state under test was not reached; rows present before the sign-in: {before}"
    )

    arrived = _google(client, monkeypatch, mailbox)
    assert arrived.status_code == 403, (
        "a designer whose empanelment an administrator revoked signed in, because the revoked row "
        f"was stored under a spelling the gate never looks up: {arrived.text}"
    )
    assert arrived.json()["detail"] == DESIGNER_SUSPENDED_DETAIL
    assert arrived.headers.get(auth_routes.ACCESS_STATUS_HEADER) == "DESIGNER_SUSPENDED", (
        arrived.headers
    )
    assert set(arrived.json()) == {"detail"}

    after = _rows_spelling(client, world, typed, mailbox)
    assert [(row["email"], row["isActive"]) for row in after] == [(mailbox, False)], (
        "the sign-in wrote a second, ACTIVE empanelment for a mailbox an administrator had "
        f"revoked; suspending the visible row again would change nothing: {after}"
    )
    assert after[0]["notes"] == ADMIN_NOTE
    assert after[0]["firstSeenAt"] is None, "a refused attempt is not an arrival"



def _access_id(client: Any, world: dict[str, Any], email: str) -> str:
    rows = _access_rows(client, world, term=email)
    assert email in rows, f"no allow-list row for {email}; rows seen: {sorted(rows)}"
    return rows[email]["id"]


def _roster_id(client: Any, world: dict[str, Any], email: str) -> str:
    rows = _empanelments(client, world, term=email)
    assert email in rows, f"no empanelment for {email}; rows seen: {sorted(rows)}"
    return rows[email]["id"]


def _bar_on_the_allow_list(client: Any, world: dict[str, Any], email: str) -> Any:
    return client.delete(
        f"/api/access/roster/{_access_id(client, world, email)}", headers=_headers(world)
    )


def _end_the_empanelment(client: Any, world: dict[str, Any], email: str) -> Any:
    return client.delete(
        f"/api/designers/roster/{_roster_id(client, world, email)}", headers=_headers(world)
    )


async def test_suspending_an_address_on_the_allow_list_suspends_the_empanelment(world, client):
    email = _fresh(world, "mirrorbar")
    assert _admit(client, world, email, "DESIGNER").status_code == 201
    before = _empanelments(client, world, term=email)[email]
    assert before["isActive"] is True, "the admission must have empanelled them, or this proves it"

    suspended = _bar_on_the_allow_list(client, world, email)
    assert suspended.status_code == 200, suspended.text
    assert suspended.json()["status"] == "SUSPENDED"

    after = _empanelments(client, world, term=email)[email]
    assert after["id"] == before["id"], "the mirror wrote a second row instead of ending this one"
    assert after["isActive"] is False, (
        "an address an administrator barred from the application is still shown as an actively "
        "empanelled designer, and the workshop pickers will go on offering them"
    )
    assert after["revokedAt"] is not None, "a suspended row with no date reads as a screen bug"
    notes = after["notes"] or ""
    assert DERIVED_EMPANELMENT_NOTE in notes, "the row's own account of how it got here was erased"
    assert access_roster.MIRROR_NOTES[access_roster.MIRROR_ACCESS_SUSPENDED] in notes
    assert "No administrator withdrew this empanelment on the designer roster directly" in notes


async def test_rejecting_a_request_suspends_the_empanelment_too(world, client):
    email = _fresh(world, "mirrorreject")
    assert _admit(client, world, email, "DESIGNER").status_code == 201
    assert _empanelments(client, world, term=email)[email]["isActive"] is True

    decided = client.post(
        f"/api/access/roster/{_access_id(client, world, email)}/decision",
        json={"decision": "REJECT"},
        headers=_headers(world),
    )
    assert decided.status_code == 200, decided.text
    assert decided.json()["status"] == "REJECTED"

    after = _empanelments(client, world, term=email)[email]
    assert after["isActive"] is False, "a rejection left an active empanelment standing behind it"
    assert access_roster.MIRROR_NOTES[access_roster.MIRROR_ACCESS_REJECTED] in (after["notes"] or "")


async def test_restoring_platform_access_never_revives_the_empanelment_it_ended(
    world, client, monkeypatch
):
    email = _fresh(world, "mirrorrestore")
    assert _admit(client, world, email, "DESIGNER").status_code == 201
    assert _bar_on_the_allow_list(client, world, email).status_code == 200
    ended = _empanelments(client, world, term=email)[email]
    assert ended["isActive"] is False, "the state under test was not reached"

    readmitted = client.post(
        f"/api/access/roster/{_access_id(client, world, email)}/decision",
        json={"decision": "APPROVE", "role": "DESIGNER"},
        headers=_headers(world),
    )
    assert readmitted.status_code == 200, readmitted.text
    assert readmitted.json()["status"] == "ACTIVE"

    after = _empanelments(client, world, term=email)[email]
    assert after["isActive"] is False, (
        "restoring platform access revived an empanelment an administrator had ended; every "
        "revocation any administrator ever made would come undone the same way"
    )
    assert after["revokedAt"] == ended["revokedAt"]
    assert after["notes"] == ended["notes"]
    refused = _google(client, monkeypatch, email)
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == DESIGNER_SUSPENDED_DETAIL
    assert refused.headers.get(auth_routes.ACCESS_STATUS_HEADER) == "DESIGNER_SUSPENDED"


async def test_ending_an_empanelment_suspends_the_admission_it_carried(world, client):
    email = _fresh(world, "mirrorempanel")
    assert _admit(client, world, email, "DESIGNER").status_code == 201
    before = _access_rows(client, world, term=email)[email]
    assert before["status"] == "ACTIVE"
    assert before["admitRole"] == "DESIGNER"

    ended = _end_the_empanelment(client, world, email)
    assert ended.status_code == 200, ended.text
    assert ended.json()["isActive"] is False

    after = _access_rows(client, world, term=email)[email]
    assert after["id"] == before["id"], "the mirror wrote a second allow-list row"
    assert after["status"] == "SUSPENDED", (
        "the allow-list still admits somebody whose empanelment an administrator ended, so their "
        "next sign-in provisions a DESIGNER account the empanelment gate immediately refuses"
    )
    assert after["joinedAt"] == before["joinedAt"], "a revocation is not a new joining date"
    notes = after["notes"] or ""
    assert access_roster.MIRROR_NOTES[access_roster.MIRROR_EMPANELMENT_ENDED] in notes
    assert "No administrator barred this address on the allow-list directly" in notes


async def test_restoring_an_empanelment_never_revives_the_admission_it_ended(
    world, client, monkeypatch
):
    email = _fresh(world, "mirrorback")
    assert _admit(client, world, email, "DESIGNER").status_code == 201
    roster_id = _roster_id(client, world, email)
    assert _end_the_empanelment(client, world, email).status_code == 200
    barred = _access_rows(client, world, term=email)[email]
    assert barred["status"] == "SUSPENDED", "the state under test was not reached"

    restored = client.patch(
        f"/api/designers/roster/{roster_id}", json={"isActive": True}, headers=_headers(world)
    )
    assert restored.status_code == 200, restored.text
    assert restored.json()["isActive"] is True
    assert restored.json()["revokedAt"] is None

    after = _access_rows(client, world, term=email)[email]
    assert after["status"] == "SUSPENDED", (
        "restoring an empanelment let somebody back into the application whom an administrator had "
        "barred; the designer roster must not be able to overturn the allow-list"
    )
    assert after["decidedAt"] == barred["decidedAt"]
    assert after["notes"] == barred["notes"]
    refused = _google(client, monkeypatch, email)
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == ACCESS_SUSPENDED_DETAIL
    assert refused.headers.get(auth_routes.ACCESS_STATUS_HEADER) == "SUSPENDED"


async def test_the_mirror_finds_an_empanelment_stored_under_a_gmail_alias(world, client):
    alias = f"empanel.aliased.{world['stamp']}@googlemail.com"
    mailbox = f"empanelaliased{world['stamp']}@gmail.com"
    assert alias != mailbox, "the fixture must hold two spellings, or this test proves nothing"
    assert designers_service.canonical_email(alias) == mailbox, (
        "the two fixture rows are not the same mailbox, so there is nothing here to mirror across"
    )

    before = _empanelments(client, world)[mailbox]
    assert before["isActive"] is True

    suspended = client.delete(
        f"/api/access/roster/{_access_rows(client, world)[alias]['id']}", headers=_headers(world)
    )
    assert suspended.status_code == 200, suspended.text
    assert suspended.json()["status"] == "SUSPENDED"

    after = _empanelments(client, world)[mailbox]
    assert after["isActive"] is False, (
        "the empanelment was not found, because it is filed under the other spelling of the same "
        "Gmail mailbox; the two screens now disagree and the mirror reported nothing wrong"
    )
    notes = after["notes"] or ""
    assert ADMIN_NOTE in notes, "the administrator's own note was overwritten by the mirror"
    assert access_roster.MIRROR_NOTES[access_roster.MIRROR_ACCESS_SUSPENDED] in notes


async def test_mirroring_the_same_revocation_twice_moves_nothing(world, client):
    email = _fresh(world, "mirroragain")
    assert _admit(client, world, email, "DESIGNER").status_code == 201
    roster_id = _roster_id(client, world, email)
    assert _end_the_empanelment(client, world, email).status_code == 200
    first = _access_rows(client, world, term=email)[email]
    assert first["status"] == "SUSPENDED"

    restored = client.patch(
        f"/api/designers/roster/{roster_id}", json={"isActive": True}, headers=_headers(world)
    )
    assert restored.status_code == 200, restored.text
    assert _end_the_empanelment(client, world, email).status_code == 200

    second = _access_rows(client, world, term=email)[email]
    assert second["status"] == "SUSPENDED"
    assert second["decidedAt"] == first["decidedAt"], (
        "a second mirrored suspension moved the date this person actually lost their access"
    )
    assert second["notes"] == first["notes"], "the mirrored sentence was appended a second time"

    barred = _fresh(world, "mirroragainbar")
    assert _admit(client, world, barred, "DESIGNER").status_code == 201
    assert _bar_on_the_allow_list(client, world, barred).status_code == 200
    once = _empanelments(client, world, term=barred)[barred]
    assert once["isActive"] is False
    assert _bar_on_the_allow_list(client, world, barred).status_code == 200
    twice = _empanelments(client, world, term=barred)[barred]
    assert twice["revokedAt"] == once["revokedAt"], (
        "a second click on the suspend button moved the date the designer lost their empanelment"
    )
    assert twice["notes"] == once["notes"]


async def test_re_deciding_a_barred_row_does_not_re_end_an_empanelment_an_admin_restored(
    world, client
):
    email = _fresh(world, "mirrorredecide")
    assert _admit(client, world, email, "DESIGNER").status_code == 201
    access_id = _access_id(client, world, email)
    roster_id = _roster_id(client, world, email)

    first = client.post(
        f"/api/access/roster/{access_id}/decision",
        json={"decision": "REJECT"},
        headers=_headers(world),
    )
    assert first.status_code == 200, first.text
    ended = _empanelments(client, world, term=email)[email]
    assert ended["isActive"] is False, "the state under test was not reached"

    restored = client.patch(
        f"/api/designers/roster/{roster_id}", json={"isActive": True}, headers=_headers(world)
    )
    assert restored.status_code == 200, restored.text
    assert restored.json()["isActive"] is True
    assert _access_rows(client, world, term=email)[email]["status"] == "REJECTED", (
        "restoring the empanelment must not have let them back into the application either; if "
        "this fails the asymmetry itself is broken and the rest of this test measures nothing"
    )

    again = client.post(
        f"/api/access/roster/{access_id}/decision",
        json={"decision": "REJECT", "notes": "Second look: the answer stands."},
        headers=_headers(world),
    )
    assert again.status_code == 200, again.text
    after = _empanelments(client, world, term=email)[email]
    assert after["isActive"] is True, (
        "re-deciding a row that was ALREADY barred ended an empanelment an administrator had "
        "deliberately restored; the mirror ran on the state instead of on the transition, at the "
        "one allow-list door that carried no guard"
    )
    assert after["notes"] == restored.json()["notes"], (
        "the restored row's account of itself was rewritten by a mirror that should not have run"
    )

    barred_again = client.delete(f"/api/access/roster/{access_id}", headers=_headers(world))
    assert barred_again.status_code == 200, barred_again.text
    assert barred_again.json()["status"] == "SUSPENDED", (
        "a REJECTED row is not the state the early return covers, so this write really does "
        "happen — which is exactly why the mirror needs a guard of its own here"
    )
    assert _empanelments(client, world, term=email)[email]["isActive"] is True, (
        "recording a rejection as a suspension ended an empanelment an administrator had restored; "
        "this person was already barred before the click, so no access of theirs ended here"
    )


async def test_ending_an_empanelment_leaves_an_admin_alone_when_the_account_is_a_gmail_alias(
    world, client
):
    typed = f"empanel.mirroralias.{world['stamp']}@gmail.com"
    mailbox = f"empanelmirroralias{world['stamp']}@gmail.com"
    assert typed != mailbox, "the fixture must hold two spellings, or this test proves nothing"
    assert designers_service.canonical_email(typed) == mailbox, (
        "the two spellings are not one mailbox, so there is nothing here for the guard to miss"
    )

    made = client.post(
        "/api/users",
        json={
            "email": typed,
            "name": f"Aliased Admin {world['stamp']}",
            "password": API_PASSWORD,
            "role": "DESIGNER",
            "mustChangePassword": False,
        },
        headers=_headers(world),
    )
    assert made.status_code == 201, made.text
    assert made.json()["email"] == typed, (
        "the account is expected to keep the spelling that was typed — that divergence from the "
        "canonicalised rosters is the whole state under test"
    )
    promoted = client.patch(
        f"/api/users/{made.json()['id']}",
        json={"role": "ADMIN", "password": API_PASSWORD, "mustChangePassword": False},
        headers=_headers(world),
    )
    assert promoted.status_code == 200, promoted.text
    assert promoted.json()["role"] == "ADMIN"

    admission = _access_rows(client, world)[mailbox]
    assert admission["status"] == "ACTIVE"
    assert admission["admitRole"] == "DESIGNER", (
        "the promotion is expected NOT to move admitRole; that staleness is why the account test "
        "below is the only thing standing between this administrator and a lockout"
    )

    empanelled = client.post(
        "/api/designers/roster",
        json={"email": typed, "notes": ADMIN_NOTE},
        headers=_headers(world),
    )
    assert empanelled.status_code == 409, (
        "creating the account was expected to empanel this designer, so adding them again must be "
        f"refused as a duplicate rather than accepted: {empanelled.text}"
    )
    rows = _rows_spelling(client, world, typed, mailbox)
    assert [row["email"] for row in rows] == [mailbox], (
        "expected exactly one empanelment, stored under the mailbox, after the account was "
        f"created at DESIGNER; got {[row['email'] for row in rows]}"
    )
    ended = client.delete(
        f"/api/designers/roster/{rows[0]['id']}", headers=_headers(world)
    )
    assert ended.status_code == 200, ended.text
    assert ended.json()["isActive"] is False, "the empanelment itself must still end"

    after = _access_rows(client, world)[mailbox]
    assert after["status"] == "ACTIVE", (
        "ending an empanelment barred an ADMIN from the entire application, because the guard "
        "could not see their account under the other spelling of the same Gmail mailbox and read "
        "that as nobody being there"
    )
    signed_in = _login(client, typed, API_PASSWORD)
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["role"] == "ADMIN"


async def test_a_person_with_no_row_on_the_other_roster_is_a_silent_no_op(world, client):
    lonely_access = _fresh(world, "mirrornodesigner")
    assert _admit(client, world, lonely_access, "RESEARCHER").status_code == 201
    assert lonely_access not in _empanelments(client, world, term=lonely_access), (
        "the control: a researcher is not empanelled, so there is nothing on the other side"
    )
    assert _bar_on_the_allow_list(client, world, lonely_access).status_code == 200
    assert lonely_access not in _empanelments(client, world, term=lonely_access), (
        "the mirror invented a designer-roster row for somebody who was never empanelled"
    )

    lonely_roster = _fresh(world, "mirrornoaccess")
    created = client.post(
        "/api/designers/roster", json={"email": lonely_roster}, headers=_headers(world)
    )
    assert created.status_code == 201, created.text
    assert lonely_roster not in _access_rows(client, world, term=lonely_roster), (
        "the control: empanelling somebody does not write an allow-list row, it is the sign-in that "
        "does; if that changes, this test is asserting nothing"
    )
    ended = client.delete(
        f"/api/designers/roster/{created.json()['id']}", headers=_headers(world)
    )
    assert ended.status_code == 200, ended.text
    assert lonely_roster not in _access_rows(client, world, term=lonely_roster), (
        "the mirror invented an allow-list row, and a SUSPENDED one, for an address no "
        "administrator has ever decided about"
    )


async def test_ending_an_empanelment_leaves_an_admins_access_to_the_product_alone(world, client):
    email = _fresh(world, "mirroradmin")
    made = client.post(
        "/api/users",
        json={
            "email": email,
            "name": f"Runs Workshops Too {world['stamp']}",
            "password": API_PASSWORD,
            "role": "DESIGNER",
            "mustChangePassword": False,
        },
        headers=_headers(world),
    )
    assert made.status_code == 201, made.text
    promoted = client.patch(
        f"/api/users/{made.json()['id']}",
        json={"role": "ADMIN", "password": API_PASSWORD, "mustChangePassword": False},
        headers=_headers(world),
    )
    assert promoted.status_code == 200, promoted.text
    assert promoted.json()["role"] == "ADMIN"
    assert _access_rows(client, world, term=email)[email]["admitRole"] == "DESIGNER", (
        "the promotion is expected NOT to move admitRole — that staleness is the whole danger the "
        "guard is about, and if it were kept in step this test would prove nothing"
    )

    empanelled = client.post(
        "/api/designers/roster",
        json={"email": email, "notes": ADMIN_NOTE},
        headers=_headers(world),
    )
    assert empanelled.status_code == 409, (
        "creating the account at DESIGNER was expected to empanel them, so adding them again must "
        f"be refused as a duplicate: {empanelled.text}"
    )
    rows = _rows_spelling(client, world, email, term=email)
    assert [row["email"] for row in rows] == [email], (
        f"expected exactly one empanelment for this address; got {[r['email'] for r in rows]}"
    )
    ended = client.delete(
        f"/api/designers/roster/{rows[0]['id']}", headers=_headers(world)
    )
    assert ended.status_code == 200, ended.text
    assert ended.json()["isActive"] is False, "the empanelment itself must still end"

    after = _access_rows(client, world, term=email)[email]
    assert after["status"] == "ACTIVE", (
        "ending an empanelment barred an ADMIN from the entire application; that is the outage "
        "auth.assert_roster_admits' docstring is written about, delivered by a screen about "
        "designers"
    )
    signed_in = _login(client, email, API_PASSWORD)
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["role"] == "ADMIN"
