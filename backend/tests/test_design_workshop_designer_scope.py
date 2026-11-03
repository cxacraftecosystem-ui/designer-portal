import os
import uuid
from typing import Any

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.core.security import create_access_token, hash_password

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

PASSWORD = "designer-scope-password"

STAGE_1 = "WORKSHOP_SETUP"

NO_SUCH_WORKSHOP = "cmnosuchworkshopatall000000"

ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("admin", "ADMIN", "Scope Admin"),
    ("otherAdmin", "ADMIN", "Uninvolved Admin"),
    ("master", "MASTER_ADMIN", "Scope Master Admin"),
    ("lead", "DESIGNER", "Lead Designer"),
    ("co", "DESIGNER", "Co Designer"),
    ("stranger", "DESIGNER", "Unticked Designer"),
    ("revoked", "DESIGNER", "Revoked Designer"),
    ("suspendedAdmin", "ADMIN", "Suspended Admin"),
)

ACCESS_STATES: tuple[tuple[str, str], ...] = (("suspendedAdmin", "SUSPENDED"),)

ROSTER: tuple[tuple[str, bool], ...] = (
    ("lead", True),
    ("co", True),
    ("stranger", True),
    ("revoked", False),
)

PROFILES: tuple[tuple[str, str, str], ...] = (
    ("lead", "Meera Kanungo", "NIFT Bhubaneswar"),
    ("co", "Rukmini Behera", "Sambalpur Handloom Cooperative"),
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
        return f"dwscope-{slug}-{stamp}@example.org".lower()

    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role, name in ACCOUNTS:
            people[slug] = await db.user.create(data={
                "email": address(slug),
                "name": name,
                "role": role,
                "passwordHash": hash_password(PASSWORD),
            })
        for slug, is_active in ROSTER:
            await db.designerroster.create(data={
                "email": address(slug),
                "fullName": f"Roster row for {slug}",
                "institution": "Directorate of Handicrafts",
                "isActive": is_active,
                "addedById": people["admin"].id,
            })
        for slug, state in ACCESS_STATES:
            await db.accessroster.create(data={"email": address(slug), "status": state})
        for slug, display_name, institution in PROFILES:
            await db.designerprofile.create(data={
                "userId": people[slug].id,
                "displayName": display_name,
                "institution": institution,
            })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {"client": client, "people": people, "stamp": stamp, "address": address}


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    user = world["people"][slug]
    return {"Authorization": f"Bearer {create_access_token(user.id)}"}


def _ids(world: dict[str, Any], slugs: list[str]) -> list[str]:
    return [world["people"][s].id for s in slugs]


def _create_as(world: dict[str, Any], slug: str, title: str, **body: Any):
    return world["client"].post(
        "/api/design-workshops",
        json={"title": title, **body},
        headers=_headers(world, slug),
    )


def _create(world: dict[str, Any], title: str, **body: Any):
    return _create_as(world, "admin", title, **body)


def _make(world: dict[str, Any], title: str, **body: Any) -> str:
    response = _create(world, title, **body)
    assert response.status_code == 201, response.text
    return response.json()["id"]


def _list_ids(client: Any, world: dict[str, Any], slug: str, **params: Any) -> list[str]:
    response = client.get(
        "/api/design-workshops",
        params={"pageSize": 100, **params},
        headers=_headers(world, slug),
    )
    assert response.status_code == 200, response.text
    return [row["id"] for row in response.json()["items"]]


def _viewer_ids(client: Any, world: dict[str, Any], workshop_id: str) -> set[str]:
    response = client.get(
        f"/api/design-workshops/{workshop_id}/viewers", headers=_headers(world, "admin")
    )
    assert response.status_code == 200, response.text
    return {row["userId"] for row in response.json()["viewers"]}



def test_ticking_two_designers_puts_BOTH_of_them_on_the_workshop(world, client):
    marker = f"Bomkai{uuid.uuid4().hex[:8]}"
    workshop_id = _make(
        world,
        f"{marker} two designers",
        designerUserIds=_ids(world, ["lead", "co"]),
    )

    assert _viewer_ids(client, world, workshop_id) == set(_ids(world, ["lead", "co"]))
    for slug in ("lead", "co"):
        read = client.get(
            f"/api/design-workshops/{workshop_id}", headers=_headers(world, slug)
        )
        assert read.status_code == 200, f"{slug} could not open the workshop: {read.text}"
        assert workshop_id in _list_ids(client, world, slug, search=marker), (
            f"{slug} holds a row and can open the workshop by id, but it is missing from their "
            "list — which is the state where a colleague is told the workshop both exists and does "
            "not, and there is no screen in either client that shows them a workshop they cannot "
            "enumerate"
        )


def test_the_LEAD_is_whose_name_reaches_the_report_and_the_co_designers_does_not(world, client):
    workshop_id = _make(
        world,
        "Ikat, lead named",
        designerUserId=world["people"]["lead"].id,
        designerUserIds=_ids(world, ["co", "lead"]),
    )

    read = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "admin"))
    assert read.status_code == 200, read.text
    assert read.json()["designerName"] == "Meera Kanungo", (
        "the workshop's promoted designer name is not the LEAD's; with several designers ticked "
        "the server has started guessing which one to print on a ministry document"
    )
    assert _viewer_ids(client, world, workshop_id) == set(_ids(world, ["lead", "co"]))


def test_a_body_that_ticks_names_but_names_no_lead_seeds_the_FIRST_TICKED(world, client):
    workshop_id = _make(
        world,
        "Ikat, no lead named",
        designerUserIds=_ids(world, ["co", "lead"]),
    )
    read = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "admin"))
    assert read.status_code == 200, read.text
    assert read.json()["designerName"] == "Rukmini Behera"


def test_naming_the_admin_THEMSELVES_writes_no_viewer_row(world, client):
    workshop_id = _make(
        world,
        "Ikat, admin is the designer",
        designerUserIds=_ids(world, ["admin", "lead"]),
    )
    assert _viewer_ids(client, world, workshop_id) == set(_ids(world, ["lead"]))
    assert client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "admin")
    ).status_code == 200


def test_one_ineligible_id_anywhere_in_the_list_refuses_the_WHOLE_create(world, client):
    title = f"Ikat, refused {uuid.uuid4().hex[:8]}"
    response = _create(world, title, designerUserIds=_ids(world, ["lead", "revoked"]))
    assert response.status_code == 422, response.text
    assert "Revoked Designer" in response.text, (
        "the refusal must NAME the account it objected to; 'one of these is ineligible' sends the "
        "admin back to a picker with nothing to act on"
    )

    left_behind = _list_ids(client, world, "admin", search=title)
    assert left_behind == [], (
        "the create was refused but a workshop row was written anyway, so every retry leaves "
        "another orphan draft behind"
    )


def test_the_allow_list_arm_of_the_rule_also_refuses_a_ticked_account(world, client):
    title = f"Ikat, barred admin {uuid.uuid4().hex[:8]}"
    response = _create(world, title, designerUserIds=_ids(world, ["lead", "suspendedAdmin"]))
    assert response.status_code == 422, response.text
    assert "Suspended Admin" in response.text
    assert _list_ids(client, world, "admin", search=title) == []


def test_the_eligibility_rule_does_not_adjudicate_the_CREATORS_OWN_standing(world, client):
    response = _create_as(
        world,
        "suspendedAdmin",
        "Ikat, opened by a barred admin",
        designerUserIds=_ids(world, ["suspendedAdmin"]),
    )
    assert response.status_code == 201, response.text
    workshop_id = response.json()["id"]
    assert _viewer_ids(client, world, workshop_id) == set(), (
        "a viewer row was written for the creator, which is a second and redundant source of truth "
        "for access they already hold through createdById"
    )



def test_an_unticked_designer_cannot_LIST_the_workshop(world, client):
    workshop_id = _make(world, "Ikat, unlisted to strangers", designerUserIds=_ids(world, ["lead"]))
    assert workshop_id not in _list_ids(client, world, "stranger")


def test_an_unticked_designer_cannot_READ_the_workshop_by_id(world, client):
    workshop_id = _make(world, "Ikat, unreadable to strangers", designerUserIds=_ids(world, ["lead"]))
    response = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "stranger")
    )
    assert response.status_code == 404, response.text
    assert response.json()["detail"] == "Record not found"


def test_the_refusal_is_BYTE_IDENTICAL_to_an_id_that_does_not_exist(world, client):
    workshop_id = _make(world, "Ikat, indistinguishable", designerUserIds=_ids(world, ["lead"]))

    refused = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "stranger")
    )
    absent = client.get(
        f"/api/design-workshops/{NO_SUCH_WORKSHOP}", headers=_headers(world, "stranger")
    )
    assert (refused.status_code, refused.json()) == (absent.status_code, absent.json()), (
        "a designer who is not on a workshop can now tell it apart from a workshop that does not "
        "exist, which turns every join card into an existence oracle"
    )


def test_a_search_cannot_reach_past_the_scope(world, client):
    marker = f"Kalamkari{uuid.uuid4().hex[:8]}"
    workshop_id = _make(world, f"{marker} scoped", designerUserIds=_ids(world, ["lead"]))

    assert workshop_id in _list_ids(client, world, "lead", search=marker)
    assert _list_ids(client, world, "stranger", search=marker) == []


def test_mineOnly_is_not_a_way_around_the_scope(world, client):
    workshop_id = _make(world, "Ikat, mine only", designerUserIds=_ids(world, ["lead"]))
    assert _list_ids(client, world, "lead", mineOnly=True) == []
    assert workshop_id in _list_ids(client, world, "lead")


def test_a_designer_off_the_workshop_cannot_reach_its_STAGES_either(world, client):
    workshop_id = _make(world, "Ikat, staged", designerUserIds=_ids(world, ["lead"]))
    response = client.get(
        f"/api/design-workshops/{workshop_id}/stages", headers=_headers(world, "stranger")
    )
    assert response.status_code == 404, response.text
    assert response.json()["detail"] == "Record not found"



@pytest.mark.parametrize("slug", ["otherAdmin", "master"])
def test_an_admin_who_is_a_stranger_to_the_workshop_still_sees_it(world, client, slug):
    marker = f"Pattachitra{uuid.uuid4().hex[:8]}"
    workshop_id = _make(world, f"{marker} admin-visible", designerUserIds=_ids(world, ["lead"]))

    read = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, slug))
    assert read.status_code == 200, read.text
    assert workshop_id in _list_ids(client, world, slug, search=marker)


def test_the_master_admin_sees_a_workshop_with_no_designers_on_it_at_all(world, client):
    marker = f"Dhokra{uuid.uuid4().hex[:8]}"
    workshop_id = _make(world, f"{marker} nobody named yet")
    assert _viewer_ids(client, world, workshop_id) == set()
    assert workshop_id in _list_ids(client, world, "master", search=marker)
    assert client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "master")
    ).status_code == 200
    assert _list_ids(client, world, "stranger", search=marker) == []



def test_the_old_single_designer_body_still_grants_and_still_seeds(world, client):
    workshop_id = _make(world, "Ikat, old wire", designerUserId=world["people"]["lead"].id)

    assert _viewer_ids(client, world, workshop_id) == set(_ids(world, ["lead"]))
    read = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "lead"))
    assert read.status_code == 200, read.text
    assert read.json()["designerName"] == "Meera Kanungo"


def test_a_body_that_names_nobody_is_still_a_legal_create(world, client):
    response = _create(world, "Ikat, nobody named")
    assert response.status_code == 201, response.text
    assert response.json()["designerName"] is None, (
        "with nobody named, the designer box must be EMPTY — a blank the completeness score and the "
        "report warnings can both see, never the admin's name, which no check in the product can"
    )



def test_a_ticked_designer_can_adopt_their_offline_fieldwork_into_the_admins_workshop(
    world, client
):
    marker = f"Sambalpuri{uuid.uuid4().hex[:8]}"
    workshop_id = _make(world, f"{marker} for adoption", designerUserIds=_ids(world, ["lead"]))

    assert workshop_id in _list_ids(client, world, "lead", search=marker), (
        "the workshop is absent from the designer's own scoped list, so the adoption picker cannot "
        "offer it and a fortnight of offline fieldwork has nowhere to land"
    )

    patched = client.patch(
        f"/api/design-workshops/{workshop_id}",
        json={"notes": "Typed in the room, before there was any signal."},
        headers=_headers(world, "lead"),
    )
    assert patched.status_code == 200, patched.text
    assert patched.json()["notes"] == "Typed in the room, before there was any signal."

    staged = client.put(
        f"/api/design-workshops/{workshop_id}/stages/{STAGE_1}",
        json={"entries": [], "replaceCollections": False},
        headers=_headers(world, "lead"),
    )
    assert staged.status_code == 200, staged.text


def test_an_unticked_designer_cannot_adopt_anything_into_it(world, client):
    marker = f"Sambalpuri{uuid.uuid4().hex[:8]}"
    workshop_id = _make(world, f"{marker} not for adoption", designerUserIds=_ids(world, ["lead"]))

    assert _list_ids(client, world, "stranger", search=marker) == []

    patched = client.patch(
        f"/api/design-workshops/{workshop_id}",
        json={"notes": "Filed against somebody else's cluster."},
        headers=_headers(world, "stranger"),
    )
    assert patched.status_code == 404, patched.text
    assert patched.json()["detail"] == "Record not found"

    staged = client.put(
        f"/api/design-workshops/{workshop_id}/stages/{STAGE_1}",
        json={"entries": [], "replaceCollections": False},
        headers=_headers(world, "stranger"),
    )
    assert staged.status_code == 404, staged.text
    assert staged.json()["detail"] == "Record not found"

    read = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "lead"))
    assert read.status_code == 200, read.text
    assert read.json()["notes"] is None
