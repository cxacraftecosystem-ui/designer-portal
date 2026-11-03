import os
import uuid
from datetime import UTC, datetime
from typing import Any

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.api.routes import auth as auth_routes
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services.designers import canonical_email, email_match_keys
from app.services.rich_text import to_plain

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

PASSWORD = "roster-test-password"

STAGE_1 = "WORKSHOP_SETUP"
STAGE_3 = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"

SUSPENDED_DETAIL = "Your designer access has been suspended. Contact the administrator."

PROFILE_NAME = "Meera Kanungo"
PROFILE_INSTITUTION = "NIFT Bhubaneswar"
PROFILE_BIOGRAPHY = "Twelve years of ikat and tie-and-dye across western Odisha."
PROFILE_YEARS = 12

ADMIN_PROFILE_NAME = "Sunil Patnaik"
ADMIN_PROFILE_INSTITUTION = "Directorate of Handicrafts, Bhubaneswar"
ADMIN_PROFILE_BIOGRAPHY = "Twenty years administering cluster development programmes."
ADMIN_PROFILE_YEARS = 20

ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("admin", "ADMIN", "Roster Admin"),
    ("professor", "PROFESSOR", "Senior Professor"),
    ("adminSuspended", "ADMIN", "Admin With A Suspended Row"),
    ("adminRostered", "ADMIN", "Admin On The Roster"),
    ("active", "DESIGNER", "Empanelled Designer"),
    ("barren", "DESIGNER", "Designer With No Profile"),
    ("colleague", "DESIGNER", "Another Designer"),
    ("suspended", "DESIGNER", "Suspended Designer"),
    ("unlisted", "DESIGNER", "Never Empanelled Designer"),
    ("volunteer", "CROWDSOURCE_VOLUNTEER", "Empanelled Volunteer"),
    ("stranger", "CROWDSOURCE_VOLUNTEER", "Ordinary Volunteer"),
    ("capSuspended", "DESIGNER", "Cap Probe {stamp} A Suspended"),
    ("capActive", "DESIGNER", "Cap Probe {stamp} Z Active"),
)

ROSTER: tuple[tuple[str, bool], ...] = (
    ("active", True),
    ("barren", True),
    ("colleague", True),
    ("suspended", False),
    ("adminSuspended", False),
    ("adminRostered", True),
    ("volunteer", True),
    ("newcomer", True),
    ("capSuspended", False),
    ("capActive", True),
)


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"roster-{slug}-{stamp}@example.org".lower()

    def mailbox(slug: str) -> str:
        return f"roster{slug}{stamp}@gmail.com".lower()

    def alias(slug: str) -> str:
        return f"roster.{slug}.{stamp}@googlemail.com".lower()

    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role, name in ACCOUNTS:
            people[slug] = await db.user.create(data={
                "email": address(slug),
                "name": name.format(stamp=stamp),
                "role": role,
                "passwordHash": hash_password(PASSWORD),
            })
            await db.accessroster.create(data={
                "email": address(slug),
                "status": "ACTIVE",
                "joinedAt": datetime.now(UTC),
                "notes": "Seeded by tests/test_designer_roster.py; this module is about the "
                         "designer empanelment gate, not the platform allow-list.",
            })
        for slug, is_active in ROSTER:
            await db.designerroster.create(data={
                "email": address(slug),
                "fullName": f"Roster row for {slug}",
                "institution": "Directorate of Handicrafts",
                "isActive": is_active,
                "revokedAt": None if is_active else datetime.now(UTC),
                "addedById": people["admin"].id,
            })
        for slug, email in (("gCanonical", mailbox), ("gLegacy", alias), ("gCollision", mailbox)):
            await db.accessroster.create(data={
                "email": email(slug),
                "status": "ACTIVE",
                "admitRole": "DESIGNER",
                "joinedAt": datetime.now(UTC),
                "notes": "Seeded by tests/test_designer_roster.py section 6 (Gmail aliasing).",
            })
        for slug, email, is_active in (
            ("gCanonical", mailbox, True),
            ("gLegacy", alias, True),
            ("gCollision", mailbox, True),
            ("gCollision", alias, False),
        ):
            await db.designerroster.create(data={
                "email": email(slug),
                "fullName": f"Gmail roster row for {slug}",
                "isActive": is_active,
                "revokedAt": None if is_active else datetime.now(UTC),
                "addedById": people["admin"].id,
            })
        await db.designerprofile.create(data={
            "user": {"connect": {"id": people["active"].id}},
            "displayName": PROFILE_NAME,
            "institution": PROFILE_INSTITUTION,
            "biography": PROFILE_BIOGRAPHY,
            "experienceYears": PROFILE_YEARS,
        })
        await db.designerprofile.create(data={
            "user": {"connect": {"id": people["admin"].id}},
            "displayName": ADMIN_PROFILE_NAME,
            "institution": ADMIN_PROFILE_INSTITUTION,
            "biography": ADMIN_PROFILE_BIOGRAPHY,
            "experienceYears": ADMIN_PROFILE_YEARS,
        })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "people": people,
            "address": address,
            "mailbox": mailbox,
            "alias": alias,
            "stamp": stamp,
        }


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['people'][slug].id)}"}


def _login(client: Any, email: str, password: str = PASSWORD) -> Any:
    return client.post("/api/auth/login", json={"email": email, "password": password})


def _google(client: Any, monkeypatch: Any, email: str, name: str = "Google Person") -> Any:
    monkeypatch.setattr(
        auth_routes,
        "verify_google_token",
        lambda _token: {"email": email, "email_verified": True, "name": name},
    )
    return client.post("/api/auth/login", json={"googleIdToken": "stand-in-for-a-real-token"})


def _roster_rows(client: Any, world: dict[str, Any]) -> dict[str, Any]:
    response = client.get(
        "/api/designers/roster",
        params={"search": world["stamp"], "pageSize": 100},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    return {row["email"]: row for row in response.json()["items"]}


def directory_rows(client: Any, world: dict[str, Any], **params: Any) -> dict[str, Any]:
    response = client.get(
        "/api/designers/directory",
        params={"search": world["stamp"], **params},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    return {row["email"]: row for row in response.json()}


def _role_only_user(role: str) -> Any:
    from types import SimpleNamespace

    return SimpleNamespace(role=role)



async def test_an_active_roster_row_lets_a_designer_in(world, client):
    response = _login(client, world["address"]("active"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "DESIGNER"


async def test_a_suspended_designer_is_told_they_are_suspended(world, client):
    response = _login(client, world["address"]("suspended"))
    assert response.status_code == 403, response.text
    assert response.json()["detail"] == SUSPENDED_DETAIL


async def test_a_designer_who_was_never_empanelled_is_empanelled_by_the_allow_list(world, client):
    email = world["address"]("unlisted")
    assert email not in _roster_rows(client, world), (
        "the fixture must NOT have empanelled this account, or this test proves nothing"
    )

    response = _login(client, email)
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "DESIGNER"

    row = _roster_rows(client, world)[email]
    assert row["isActive"] is True
    assert row["addedById"] is None
    assert "automatically" in (row["notes"] or ""), row["notes"]


async def test_a_wrong_password_is_still_a_401_on_an_empanelled_account(world, client):
    response = _login(client, world["address"]("active"), password="not-the-password")
    assert response.status_code == 401, response.text
    assert response.json()["detail"] == "Invalid email or password"


async def test_an_admin_with_no_roster_row_is_never_gated(world, client):
    response = _login(client, world["address"]("admin"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "ADMIN"


async def test_an_admin_with_a_suspended_roster_row_still_signs_in(world, client):
    response = _login(client, world["address"]("adminSuspended"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "ADMIN"


async def test_a_volunteer_is_not_gated_by_the_designer_roster(world, client):
    response = _login(client, world["address"]("stranger"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "CROWDSOURCE_VOLUNTEER"


async def test_the_google_path_is_gated_too(world, client, monkeypatch):
    response = _google(client, monkeypatch, world["address"]("suspended"))
    assert response.status_code == 403, response.text
    assert response.json()["detail"] == SUSPENDED_DETAIL


async def test_first_seen_is_stamped_on_a_successful_sign_in_and_not_on_a_refused_one(
    world, client
):
    assert _login(client, world["address"]("active")).status_code == 200
    assert _login(client, world["address"]("suspended")).status_code == 403

    rows = _roster_rows(client, world)
    assert rows[world["address"]("active")]["firstSeenAt"] is not None
    assert rows[world["address"]("suspended")]["firstSeenAt"] is None


async def test_the_first_seen_stamp_records_the_first_sign_in_not_the_latest(world, client):
    first = _roster_rows(client, world)[world["address"]("active")]["firstSeenAt"]
    assert first is not None, "the earlier test in this module already signed this designer in"
    assert _login(client, world["address"]("active")).status_code == 200
    assert _roster_rows(client, world)[world["address"]("active")]["firstSeenAt"] == first



async def test_a_rostered_email_with_no_account_is_created_as_a_designer(
    world, client, monkeypatch
):
    response = _google(client, monkeypatch, world["address"]("newcomer"), name="Brand New")
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "DESIGNER"


async def test_an_existing_account_below_designer_is_not_promoted_by_signing_in(
    world, client, monkeypatch
):
    response = _google(client, monkeypatch, world["address"]("volunteer"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "CROWDSOURCE_VOLUNTEER"


async def test_an_admin_on_the_roster_is_never_demoted_by_signing_in(world, client, monkeypatch):
    response = _google(client, monkeypatch, world["address"]("adminRostered"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "ADMIN"


async def test_google_sign_in_without_a_roster_row_promotes_nobody(world, client, monkeypatch):
    response = _google(client, monkeypatch, world["address"]("stranger"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "CROWDSOURCE_VOLUNTEER"



async def test_a_designer_cannot_read_the_roster(world, client):
    response = client.get("/api/designers/roster", headers=_headers(world, "active"))
    assert response.status_code == 403, response.text


async def test_deleting_a_roster_entry_suspends_it_and_keeps_the_record(world, client):
    email = f"roster-revoked-{world['stamp']}@example.org"
    created = client.post(
        "/api/designers/roster",
        json={
            "email": email,
            "fullName": "Empanelled Then Revoked",
            "notes": "Empanelled under NHDP 2026.",
        },
        headers=_headers(world, "admin"),
    )
    assert created.status_code == 201, created.text
    roster_id = created.json()["id"]

    first = client.delete(f"/api/designers/roster/{roster_id}", headers=_headers(world, "admin"))
    assert first.status_code == 200, first.text
    assert first.json()["isActive"] is False
    assert first.json()["revokedAt"] is not None
    assert first.json()["notes"] == "Empanelled under NHDP 2026.", "the record must survive"

    again = client.delete(f"/api/designers/roster/{roster_id}", headers=_headers(world, "admin"))
    assert again.json()["revokedAt"] == first.json()["revokedAt"], (
        "a second suspension must not move the date the designer actually lost access"
    )
    assert email in _roster_rows(client, world), "a suspension is not a deletion"


async def test_re_empanelling_an_existing_email_is_a_409_naming_the_row(world, client):
    email = f"roster-dupe-{world['stamp']}@example.org"
    first = client.post(
        "/api/designers/roster", json={"email": email}, headers=_headers(world, "admin")
    )
    assert first.status_code == 201, first.text

    clash = client.post(
        "/api/designers/roster",
        json={"email": email.replace("roster", "Roster").upper()},
        headers=_headers(world, "admin"),
    )
    assert clash.status_code == 409, clash.text
    assert "already on the roster" in clash.json()["detail"], "the answer must say WHICH entry"
    assert "@" in clash.json()["detail"], "named by its address"


async def test_suspending_a_row_ends_access_and_restoring_gives_it_back(world, client):
    roster_id = _roster_rows(client, world)[world["address"]("barren")]["id"]

    assert client.delete(
        f"/api/designers/roster/{roster_id}", headers=_headers(world, "admin")
    ).status_code == 200
    refused = _login(client, world["address"]("barren"))
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == SUSPENDED_DETAIL

    restored = client.patch(
        f"/api/designers/roster/{roster_id}",
        json={"isActive": True},
        headers=_headers(world, "admin"),
    )
    assert restored.status_code == 200, restored.text
    assert restored.json()["revokedAt"] is None
    assert _login(client, world["address"]("barren")).status_code == 200


async def test_the_directory_hides_a_suspended_designer_unless_asked(world, client):
    def directory(**params: Any) -> dict[str, Any]:
        response = client.get(
            "/api/designers/directory",
            params={"search": world["stamp"], **params},
            headers=_headers(world, "admin"),
        )
        assert response.status_code == 200, response.text
        return {row["email"]: row for row in response.json()}

    default = directory()
    assert world["address"]("active") in default
    assert world["address"]("suspended") not in default
    assert default[world["address"]("admin")]["canSignIn"] is True

    shown = directory(includeSuspended=True)
    assert shown[world["address"]("suspended")]["canSignIn"] is False


async def test_the_directory_cap_is_spent_on_designers_the_roster_still_admits(
    world, client, monkeypatch
):
    from app.services import designers as designers_service

    monkeypatch.setattr(designers_service, "DIRECTORY_TAKE", 1)
    probe = f"Cap Probe {world['stamp']}"

    admitted = directory_rows(client, world, search=probe)
    assert list(admitted) == [world["address"]("capActive")], (
        "the one row the cap allowed was spent on a suspended designer and then thrown away, so "
        "an eligible designer past the cut was never read"
    )

    shown = directory_rows(client, world, search=probe, includeSuspended=True)
    assert list(shown) == [world["address"]("capSuspended")]
    assert shown[world["address"]("capSuspended")]["canSignIn"] is False


async def test_the_directory_leaves_out_a_professor_the_viewer_write_would_refuse(world, client):
    from app.api.routes.designers import WORKSHOP_CAPABLE_ROLES
    from app.core.deps import ROLE_RANK, can_run_design_workshops

    listed = directory_rows(client, world, includeSuspended=True)
    assert world["address"]("professor") not in listed, (
        "a professor was offered as somebody an admin may hand a workshop to; the viewer write "
        "answers 422 to exactly that grant"
    )
    assert world["address"]("active") in listed
    assert world["address"]("admin") in listed

    for role in ROLE_RANK:
        expected = can_run_design_workshops(_role_only_user(role))
        assert (role in WORKSHOP_CAPABLE_ROLES) is expected, (
            f"{role}: the directory's role set and can_run_design_workshops disagree — the "
            "capability is a frozenset, never a rank floor"
        )



async def test_a_new_profile_cannot_be_created_without_an_empanelment_number(world, client):
    refused = client.put(
        "/api/designers/me/profile",
        json={"institution": "NID Ahmedabad", "biography": "Bamboo and cane, Assam."},
        headers=_headers(world, "colleague"),
    )
    assert refused.status_code == 422, refused.text
    assert "empanelment number" in refused.json()["detail"].lower()

    stored = client.get("/api/designers/me/profile", headers=_headers(world, "colleague"))
    assert stored.status_code == 200, stored.text
    assert stored.json()["institution"] is None


async def test_an_empty_save_is_not_a_creation_and_is_not_refused(world, client):
    response = client.put(
        "/api/designers/me/profile", json={}, headers=_headers(world, "colleague")
    )
    assert response.status_code == 200, response.text


async def test_a_designer_may_write_their_own_profile(world, client):
    response = client.put(
        "/api/designers/me/profile",
        json={
            "institution": "NID Ahmedabad",
            "biography": "Bamboo and cane, Assam.",
            "empanelmentNo": "DES/2026/0801",
        },
        headers=_headers(world, "colleague"),
    )
    assert response.status_code == 200, response.text
    assert response.json()["institution"] == "NID Ahmedabad"
    assert response.json()["empanelmentNo"] == "DES/2026/0801"
    assert response.json()["empanelmentNoMissing"] is False


async def test_a_profile_that_already_has_content_may_save_without_an_empanelment_number(
    world, client
):
    target = world["people"]["active"].id
    response = client.put(
        f"/api/designers/{target}/profile",
        json={"department": "Textile Design"},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    assert response.json()["empanelmentNoMissing"] is True, (
        "the save was allowed but the banner would not be drawn, so nobody is ever asked"
    )


async def test_a_designer_may_not_write_a_colleagues_profile(world, client):
    target = world["people"]["active"].id
    response = client.put(
        f"/api/designers/{target}/profile",
        json={"biography": "Words this designer never wrote."},
        headers=_headers(world, "colleague"),
    )
    assert response.status_code == 404, response.text
    assert response.json()["detail"] == "Record not found"

    stored = client.get(f"/api/designers/{target}/profile", headers=_headers(world, "admin"))
    assert stored.status_code == 200, stored.text
    assert stored.json()["biography"] == PROFILE_BIOGRAPHY, "the refusal must also not have written"


async def test_a_forbidden_profile_is_indistinguishable_from_a_missing_one(world, client):
    denied = client.get(
        f"/api/designers/{world['people']['active'].id}/profile",
        headers=_headers(world, "colleague"),
    )
    missing = client.get(
        "/api/designers/there-is-no-user-with-this-id/profile",
        headers=_headers(world, "colleague"),
    )
    assert denied.status_code == missing.status_code == 404
    assert denied.json()["detail"] == missing.json()["detail"]


async def test_an_admin_may_write_a_designers_profile(world, client):
    target = world["people"]["colleague"].id
    response = client.put(
        f"/api/designers/{target}/profile",
        json={"empanelmentNo": "EMP/2026/0042", "empanelmentDate": "2026-03-14"},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    assert response.json()["empanelmentNo"] == "EMP/2026/0042"
    assert response.json()["empanelmentDate"].startswith("2026-03-14")
    assert response.json()["institution"] == "NID Ahmedabad", (
        "an admin correcting two fields must not blank the nineteen the designer typed"
    )


async def test_the_profile_belongs_to_the_people_who_run_workshops(world, client):
    admin = client.get("/api/designers/me/profile", headers=_headers(world, "admin"))
    assert admin.status_code == 200, admin.text
    assert admin.json()["userId"] == world["people"]["admin"].id

    designer = client.get("/api/designers/me/profile", headers=_headers(world, "active"))
    assert designer.status_code == 200, designer.text

    refused = client.get("/api/designers/me/profile", headers=_headers(world, "stranger"))
    assert refused.status_code == 403, refused.text
    assert "designer" in refused.json()["detail"].lower()



@pytest.fixture
def workshop_of(world, client):
    def create(
        creator_slug: str,
        title: str,
        grant_to: str | list[str] | None = None,
        designer_for: str | None = None,
        expect: int = 201,
    ) -> str | None:
        body: dict[str, Any] = {"title": title}
        if designer_for:
            body["designerUserId"] = world["people"][designer_for].id
        response = client.post(
            "/api/design-workshops", json=body, headers=_headers(world, creator_slug)
        )
        assert response.status_code == expect, response.text
        if expect != 201:
            return None
        workshop_id = response.json()["id"]
        if grant_to:
            slugs = [grant_to] if isinstance(grant_to, str) else grant_to
            granted = client.put(
                f"/api/design-workshops/{workshop_id}/viewers",
                json={"userIds": [world["people"][slug].id for slug in slugs]},
                headers=_headers(world, "admin"),
            )
            assert granted.status_code == 200, granted.text
        return workshop_id

    return create


def _singleton(client: Any, world: dict[str, Any], slug: str, workshop_id: str, stage: str) -> Any:
    response = client.get(
        f"/api/design-workshops/{workshop_id}/stages/{stage}", headers=_headers(world, slug)
    )
    assert response.status_code == 200, response.text
    return response.json()["singleton"]


async def test_a_new_workshop_starts_with_the_creators_profile_in_stages_1_and_3(
    world, client, workshop_of
):
    workshop_id = workshop_of("admin", "Prefilled workshop", grant_to="active")

    stage_1 = _singleton(client, world, "active", workshop_id, STAGE_1)
    assert stage_1["designerName"] == ADMIN_PROFILE_NAME
    assert stage_1["designerInstitution"] == ADMIN_PROFILE_INSTITUTION

    stage_3 = _singleton(client, world, "active", workshop_id, STAGE_3)
    assert to_plain(stage_3["designerProfile"]) == ADMIN_PROFILE_BIOGRAPHY
    assert stage_3["designerExperience"] == ADMIN_PROFILE_YEARS, "an INT field must arrive as an int"


async def test_a_workshop_opened_for_a_NAMED_designer_carries_the_DESIGNERS_details(
    world, client, workshop_of
):
    workshop_id = workshop_of("admin", "Opened for a named designer", designer_for="active")

    stage_1 = _singleton(client, world, "active", workshop_id, STAGE_1)
    assert stage_1["designerName"] == PROFILE_NAME
    assert stage_1["designerName"] != ADMIN_PROFILE_NAME, (
        "the admin who opened this workshop has a profile of their own, and that profile is what "
        "used to reach the cover of a report submitted to a ministry"
    )
    assert stage_1["designerInstitution"] == PROFILE_INSTITUTION

    stage_3 = _singleton(client, world, "active", workshop_id, STAGE_3)
    assert to_plain(stage_3["designerProfile"]) == PROFILE_BIOGRAPHY
    assert stage_3["designerExperience"] == PROFILE_YEARS, "an INT field must arrive as an int"

    header = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "active")
    )
    assert header.status_code == 200, header.text
    assert header.json()["designerName"] == PROFILE_NAME, (
        "``designerName`` is a promoted column, so this is what the workshop LIST and the report "
        "cover read — a fix that reached the stage entry and not the column would leave both "
        "naming somebody who has nothing to do with this workshop"
    )


async def test_a_workshop_that_names_no_designer_still_carries_the_ADMINS_details(
    world, client, workshop_of
):
    workshop_id = workshop_of("admin", "Opened for nobody in particular", grant_to="active")
    stage_1 = _singleton(client, world, "active", workshop_id, STAGE_1)

    assert stage_1["designerName"] == ADMIN_PROFILE_NAME
    assert stage_1["designerName"] != PROFILE_NAME, (
        "the designer who will run this workshop is 'active', and with nobody named on the create "
        "their name is NOT what was seeded"
    )
    header = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "active")
    )
    assert header.status_code == 200, header.text
    assert header.json()["designerName"] == ADMIN_PROFILE_NAME, (
        "and it reaches the promoted column, so it is what the list and the report cover show"
    )


async def test_a_named_designer_with_no_profile_leaves_the_boxes_EMPTY_on_a_real_database(
    world, client, workshop_of
):
    workshop_id = workshop_of("admin", "Named a designer with no profile", designer_for="barren")

    assert _singleton(client, world, "barren", workshop_id, STAGE_1) == {}
    assert _singleton(client, world, "barren", workshop_id, STAGE_3) == {}

    header = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "barren")
    )
    assert header.status_code == 200, header.text
    assert header.json()["designerName"] is None, (
        "the promoted column must be empty too — a blank the completeness score and the report "
        f"warnings can both see, never {ADMIN_PROFILE_NAME}, whose profile was one ``or`` away"
    )


async def test_naming_a_designer_the_roster_no_longer_admits_refuses_the_WHOLE_create(
    world, client, workshop_of
):
    title = f"Refused before it existed {world['stamp']}"
    assert workshop_of("admin", title, designer_for="suspended", expect=422) is None

    listed = client.get(
        "/api/design-workshops", params={"search": title}, headers=_headers(world, "admin")
    )
    assert listed.status_code == 200, listed.text
    assert listed.json()["total"] == 0, (
        "the refused create left a workshop behind, so the eligibility check has moved below "
        "db.designworkshop.create: every retry of a mis-picked designer now costs an orphan draft "
        "that nothing in the list can tell from a workshop somebody meant to open"
    )


async def test_naming_one_designer_and_granting_a_team_seeds_only_the_NAMED_designers_details(
    world, client, workshop_of
):
    workshop_id = workshop_of(
        "admin",
        "Named one designer, granted a team",
        grant_to=["active", "colleague", "barren"],
        designer_for="active",
    )

    for slug in ("colleague", "barren"):
        seen = _singleton(client, world, slug, workshop_id, STAGE_1)
        assert seen["designerName"] == PROFILE_NAME, f"{slug} was granted but reads {seen}"
        assert seen["designerInstitution"] == PROFILE_INSTITUTION, (
            "a grant is not a nomination: three people can open this workshop and exactly one of "
            "them is the designer it was opened for"
        )


async def test_the_prefilled_designer_name_reaches_the_promoted_column(
    world, client, workshop_of
):
    workshop_id = workshop_of("admin", "Promoted prefill", grant_to="active")
    header = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "active")
    )
    assert header.status_code == 200, header.text
    assert header.json()["designerName"] == ADMIN_PROFILE_NAME
    assert header.json()["title"] == "Promoted prefill", (
        "seeding a promoted column must not overwrite the title the designer just typed"
    )


async def test_a_creator_with_no_profile_gets_an_empty_stage_one(world, client, workshop_of):
    workshop_id = workshop_of("adminRostered", "No profile workshop", grant_to="barren")
    assert _singleton(client, world, "barren", workshop_id, STAGE_1) == {}
    assert _singleton(client, world, "barren", workshop_id, STAGE_3) == {}


async def test_the_prefill_is_a_copy_and_a_later_profile_edit_never_reaches_it(
    world, client, workshop_of
):
    earlier = workshop_of("admin", "Ran from NIFT", grant_to="active")
    assert _singleton(client, world, "active", earlier, STAGE_1)["designerInstitution"] == (
        ADMIN_PROFILE_INSTITUTION
    )

    moved = client.put(
        "/api/designers/me/profile",
        json={"institution": "NID Ahmedabad"},
        headers=_headers(world, "admin"),
    )
    assert moved.status_code == 200, moved.text

    later = workshop_of("admin", "Ran from NID", grant_to="active")
    assert _singleton(client, world, "active", later, STAGE_1)["designerInstitution"] == (
        "NID Ahmedabad"
    )
    assert _singleton(client, world, "active", earlier, STAGE_1)["designerInstitution"] == (
        ADMIN_PROFILE_INSTITUTION
    ), "the earlier workshop must still name the institution it was actually run from"



def test_gmail_dots_are_not_part_of_the_address():
    assert canonical_email("sandy.craft3@gmail.com") == "sandycraft3@gmail.com"
    assert canonical_email("s.a.n.d.y.c.r.a.f.t.3@gmail.com") == "sandycraft3@gmail.com"
    assert canonical_email("sandycraft3@gmail.com") == "sandycraft3@gmail.com"


def test_a_plus_tag_is_not_part_of_the_address():
    assert canonical_email("sandycraft3+ministry@gmail.com") == "sandycraft3@gmail.com"
    assert canonical_email("sandy.craft3+dpw.2026@gmail.com") == "sandycraft3@gmail.com"
    assert canonical_email("sandycraft3+a+b@gmail.com") == "sandycraft3@gmail.com"


def test_googlemail_is_gmail():
    assert canonical_email("sandycraft3@googlemail.com") == "sandycraft3@gmail.com"
    assert canonical_email("sandy.craft3+work@googlemail.com") == "sandycraft3@gmail.com"


def test_a_non_gmail_address_keeps_every_dot_and_every_tag_it_was_given():
    assert canonical_email("a.sharma@nift.ac.in") == "a.sharma@nift.ac.in"
    billing = "accounts+billing@handicrafts.nic.in"
    assert canonical_email(billing) == billing
    assert canonical_email("  A.Sharma@NIFT.ac.in  ") == "a.sharma@nift.ac.in"
    assert canonical_email("a.b@notgmail.com") == "a.b@notgmail.com"
    assert canonical_email("a.b@c@gmail.com") == "a.b@c@gmail.com"


def test_nothing_canonicalises_to_nothing():
    assert canonical_email(None) == ""
    assert canonical_email("") == ""
    assert canonical_email("   ") == ""
    assert email_match_keys(None) == []
    assert email_match_keys("  ") == []
    assert canonical_email("...@gmail.com") == "...@gmail.com"
    assert canonical_email("+tag@gmail.com") == "+tag@gmail.com"


def test_the_match_keys_are_the_literal_first_and_the_mailbox_second():
    assert email_match_keys("sandy.craft3@gmail.com") == [
        "sandy.craft3@gmail.com",
        "sandycraft3@gmail.com",
    ]
    assert email_match_keys("sandycraft3@gmail.com") == ["sandycraft3@gmail.com"]
    assert email_match_keys("a.sharma@nift.ac.in") == ["a.sharma@nift.ac.in"]
    assert len(email_match_keys("s.a.n.d.y+x@googlemail.com")) == 2


async def test_an_aliased_sign_in_reaches_the_row_stored_as_the_mailbox(world, client, monkeypatch):
    response = _google(client, monkeypatch, world["alias"]("gCanonical"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "DESIGNER"

    row = _roster_rows(client, world)[world["mailbox"]("gCanonical")]
    assert row["firstSeenAt"] is not None, (
        "the gate admitted this designer on the canonical key and then stamped nothing, so the "
        "roster screen will report an invitation that never arrived for somebody who is using the "
        "app right now"
    )
    assert world["alias"]("gCanonical") not in _roster_rows(client, world)
    assert response.json()["user"]["email"] == world["alias"]("gCanonical")


async def test_a_row_written_before_canonicalisation_still_admits_its_own_spelling(
    world, client, monkeypatch
):
    response = _google(client, monkeypatch, world["alias"]("gLegacy"))
    assert response.status_code == 200, response.text
    assert response.json()["user"]["role"] == "DESIGNER"

    rows = _roster_rows(client, world)
    assert rows[world["alias"]("gLegacy")]["firstSeenAt"] is not None
    assert world["mailbox"]("gLegacy") not in rows, (
        "signing in created a canonical twin of a row that was already admitting this person, so "
        "the roster screen now shows one designer twice and a later suspension can miss one of them"
    )


async def test_a_revocation_is_not_walked_around_by_a_second_spelling(world, client, monkeypatch):
    response = _google(client, monkeypatch, world["alias"]("gCollision"))
    assert response.status_code == 403, response.text
    assert response.json()["detail"] == SUSPENDED_DETAIL
    assert response.headers.get("X-Access-Status") == "DESIGNER_SUSPENDED"

    rows = _roster_rows(client, world)
    assert rows[world["alias"]("gCollision")]["isActive"] is False, (
        "the refused sign-in revived the revocation it was refused by"
    )
    assert rows[world["mailbox"]("gCollision")]["isActive"] is True


async def test_admitting_an_aliased_address_stores_the_mailbox_on_both_rosters(world, client):
    typed = f"new.admit.{world['stamp']}@googlemail.com"
    stored = f"newadmit{world['stamp']}@gmail.com"
    assert canonical_email(typed) == stored

    response = client.post(
        "/api/access/roster",
        json={"email": typed, "role": "DESIGNER"},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 201, response.text
    assert response.json()["email"] == stored

    rows = _roster_rows(client, world)
    assert stored in rows, "the approval empanelled nobody under the address the gate will read"
    assert typed not in rows


async def test_adding_a_gmail_equivalent_address_is_a_409_that_says_so(world, client):
    first = f"clash{world['stamp']}@gmail.com"
    again = f"cl.ash{world['stamp']}+desk@googlemail.com"
    assert canonical_email(again) == first

    created = client.post(
        "/api/access/roster",
        json={"email": first, "role": "DESIGNER"},
        headers=_headers(world, "admin"),
    )
    assert created.status_code == 201, created.text

    clash = client.post(
        "/api/access/roster",
        json={"email": again, "role": "DESIGNER"},
        headers=_headers(world, "admin"),
    )
    assert clash.status_code == 409, clash.text
    detail = clash.json()["detail"]
    assert first in detail and again in detail, (
        f"the refusal named neither what the admin typed nor what is stored: {detail}"
    )
