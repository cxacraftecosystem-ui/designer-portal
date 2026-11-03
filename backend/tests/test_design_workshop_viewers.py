import json
import os
import uuid
from datetime import UTC, datetime
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

PASSWORD = "viewer-test-password"

STAGE_1 = "WORKSHOP_SETUP"

ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("admin", "ADMIN", "Viewer Admin"),
    ("creator", "ADMIN", "Workshop Creator"),
    ("colleague", "DESIGNER", "Second Designer"),
    ("outsider", "DESIGNER", "Unrelated Designer"),
    ("suspended", "DESIGNER", "Suspended Designer"),
    ("unlisted", "DESIGNER", "Never Empanelled Designer"),
    ("researcher", "RESEARCHER", "Ordinary Researcher"),
    ("professor", "PROFESSOR", "Senior Professor"),
    ("shouty", "DESIGNER", "Shouting Designer"),
)

ROSTER: tuple[tuple[str, bool], ...] = (
    ("creator", True),
    ("colleague", True),
    ("outsider", True),
    ("suspended", False),
    ("shouty", True),
)

ACCESS_STATES: tuple[tuple[str, str, str, bool], ...] = (
    ("accessSuspended", "DESIGNER", "SUSPENDED", True),
    ("accessRejected", "ADMIN", "REJECTED", True),
    ("accessMaster", "MASTER_ADMIN", "SUSPENDED", True),
    ("accessConfiguredMaster", "ADMIN", "SUSPENDED", True),
    ("accessPending", "DESIGNER", "PENDING", True),
    ("accessUnempanelled", "DESIGNER", "SUSPENDED", False),
)

ACCESS_BARRED_SLUGS = ("accessSuspended", "accessRejected")


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    name_token = f"Nabakalebara{uuid.uuid4().hex[:8]}"
    tie_token = f"Sambalpuri{uuid.uuid4().hex[:8]}"

    def address(slug: str) -> str:
        return f"dwviewer-{slug}-{stamp}@example.org".lower()

    def barred_address(slug: str) -> str:
        return f"dwviewer-{slug}-{stamp}@barred.example.org".lower()

    def stored_address(slug: str) -> str:
        return address(slug).upper() if slug == "shouty" else address(slug)

    def display_name(slug: str, name: str) -> str:
        return f"{name} {name_token}" if slug == "outsider" else name

    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role, name in ACCOUNTS:
            people[slug] = await db.user.create(data={
                "email": stored_address(slug),
                "name": display_name(slug, name),
                "role": role,
                "passwordHash": hash_password(PASSWORD),
            })
        tied_slugs: list[str] = []
        for index, letter in enumerate("hgfedcba"):
            slug = f"tied{index}"
            tied_slugs.append(slug)
            people[slug] = await db.user.create(data={
                "id": f"cmtie{stamp}{letter * 12}",
                "email": f"dwviewer-{slug}-{stamp}@tied.example.org",
                "name": f"Tied Name {tie_token}",
                "role": "ADMIN",
                "passwordHash": hash_password(PASSWORD),
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
        for slug, role, state, empanelled in ACCESS_STATES:
            people[slug] = await db.user.create(data={
                "name": f"Allowlist {slug.removeprefix('access')}",
                "email": barred_address(slug),
                "role": role,
                "passwordHash": hash_password(PASSWORD),
            })
            await db.accessroster.create(data={"email": barred_address(slug), "status": state})
            if role == "DESIGNER" and empanelled:
                await db.designerroster.create(data={
                    "email": barred_address(slug),
                    "fullName": f"Roster row for {slug}",
                    "institution": "Directorate of Handicrafts",
                    "isActive": True,
                    "addedById": people["admin"].id,
                })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "people": people,
            "address": address,
            "barred_address": barred_address,
            "stored_address": stored_address,
            "stamp": stamp,
            "name_token": name_token,
            "tie_token": tie_token,
            "tied_slugs": tied_slugs,
        }


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    user = world["people"][slug]
    return {"Authorization": f"Bearer {create_access_token(user.id)}"}


def _make_workshop(world: dict[str, Any], title: str) -> str:
    response = world["client"].post(
        "/api/design-workshops",
        json={"title": title},
        headers=_headers(world, "creator"),
    )
    assert response.status_code == 201, response.text
    return response.json()["id"]


def _grant(world: dict[str, Any], workshop_id: str, slugs: list[str]):
    return world["client"].put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [world["people"][s].id for s in slugs]},
        headers=_headers(world, "admin"),
    )



def test_a_colleague_with_no_grant_still_cannot_open_the_workshop(world, client):
    workshop_id = _make_workshop(world, "Ikat, no grant")
    response = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "outsider"))
    assert response.status_code == 404
    assert response.json()["detail"] == "Record not found"



def test_a_granted_colleague_can_open_the_workshop(world, client):
    workshop_id = _make_workshop(world, "Ikat, granted")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    response = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "colleague"))
    assert response.status_code == 200, response.text
    assert response.json()["id"] == workshop_id


def test_a_granted_colleague_finds_the_workshop_in_the_list(world, client):
    workshop_id = _make_workshop(world, "Ikat, listed")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    response = client.get("/api/design-workshops?pageSize=100", headers=_headers(world, "colleague"))
    assert response.status_code == 200, response.text
    assert workshop_id in [row["id"] for row in response.json()["items"]]


def test_the_grant_scope_survives_a_search(world, client):
    marker = f"Kalamkari{uuid.uuid4().hex[:6]}"
    workshop_id = _make_workshop(world, f"{marker} scoped search")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    found = client.get(
        f"/api/design-workshops?pageSize=100&search={marker}", headers=_headers(world, "colleague")
    )
    assert found.status_code == 200, found.text
    assert [row["id"] for row in found.json()["items"]] == [workshop_id]

    empty = client.get(
        "/api/design-workshops?pageSize=100&search=NoWorkshopIsCalledThis",
        headers=_headers(world, "colleague"),
    )
    assert empty.status_code == 200, empty.text
    assert empty.json()["items"] == []



def test_a_grant_carries_stage_writes(world, client):
    workshop_id = _make_workshop(world, "Ikat, co-written")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    response = client.put(
        f"/api/design-workshops/{workshop_id}/stages/{STAGE_1}",
        json={"entries": [], "replaceCollections": False},
        headers=_headers(world, "colleague"),
    )
    assert response.status_code == 200, response.text


def test_a_grant_does_not_carry_delete(world, client):
    workshop_id = _make_workshop(world, "Ikat, undeletable by guest")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    response = client.delete(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "colleague")
    )
    assert response.status_code == 403, response.text

    still = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "creator"))
    assert still.status_code == 200


def test_a_grant_does_not_carry_the_power_to_re_grant(world, client):
    workshop_id = _make_workshop(world, "Ikat, no onward grants")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    read = client.get(
        f"/api/design-workshops/{workshop_id}/viewers", headers=_headers(world, "colleague")
    )
    assert read.status_code == 403, read.text

    write = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [world["people"]["outsider"].id]},
        headers=_headers(world, "colleague"),
    )
    assert write.status_code == 403, write.text

    rows = client.get(
        f"/api/design-workshops/{workshop_id}/viewers", headers=_headers(world, "admin")
    ).json()["viewers"]
    assert [row["userId"] for row in rows] == [world["people"]["colleague"].id]


def test_the_creator_keeps_their_own_workshop_when_the_viewer_set_is_emptied(world, client):
    workshop_id = _make_workshop(world, "Ikat, emptied")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200
    assert _grant(world, workshop_id, []).status_code == 200

    mine = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "creator"))
    assert mine.status_code == 200

    gone = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "colleague"))
    assert gone.status_code == 404



def test_the_put_replaces_the_whole_set_and_is_idempotent(world, client):
    workshop_id = _make_workshop(world, "Ikat, replaced")

    first = _grant(world, workshop_id, ["colleague", "outsider"])
    assert first.status_code == 200, first.text
    assert sorted(row["userId"] for row in first.json()["viewers"]) == sorted(
        [world["people"]["colleague"].id, world["people"]["outsider"].id]
    )

    again = _grant(world, workshop_id, ["colleague", "outsider"])
    assert again.status_code == 200, again.text
    assert sorted(row["userId"] for row in again.json()["viewers"]) == sorted(
        [world["people"]["colleague"].id, world["people"]["outsider"].id]
    )

    narrowed = _grant(world, workshop_id, ["colleague"])
    assert narrowed.status_code == 200, narrowed.text
    assert [row["userId"] for row in narrowed.json()["viewers"]] == [world["people"]["colleague"].id]


def test_a_viewer_row_names_the_person_it_admits(world, client):
    workshop_id = _make_workshop(world, "Ikat, named")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    rows = client.get(
        f"/api/design-workshops/{workshop_id}/viewers", headers=_headers(world, "admin")
    ).json()["viewers"]
    assert len(rows) == 1
    row = rows[0]
    assert row["userId"] == world["people"]["colleague"].id
    assert row["name"] == "Second Designer"
    assert row["email"] == world["address"]("colleague")
    assert row["role"] == "DESIGNER"
    assert row["grantedAt"]


def test_an_unknown_user_id_is_a_422_that_names_it(world, client):
    workshop_id = _make_workshop(world, "Ikat, bad id")
    bogus = "ckzzzzzzzzzzzzzzzzzzzzzzz"

    response = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [world["people"]["colleague"].id, bogus]},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 422, response.text
    assert bogus in str(response.json()["detail"])

    rows = client.get(
        f"/api/design-workshops/{workshop_id}/viewers", headers=_headers(world, "admin")
    ).json()["viewers"]
    assert rows == []


@pytest.mark.parametrize(
    ("label", "bad_id"),
    [
        ("nul byte", "ck\x00zzzzzzzzzzzzzzzzzzzzzz"),
        ("lone surrogate", "ck\ud800zzzzzzzzzzzzzzzzzzzzzz"),
    ],
)
def test_an_id_postgres_cannot_hold_is_a_422_not_a_500(world, client, label, bad_id):
    workshop_id = _make_workshop(world, f"Ikat, {label}")

    body = json.dumps(
        {"userIds": [world["people"]["colleague"].id, bad_id]}, ensure_ascii=True
    )
    response = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        content=body,
        headers={**_headers(world, "admin"), "Content-Type": "application/json"},
    )
    assert response.status_code == 422, response.text

    rows = client.get(
        f"/api/design-workshops/{workshop_id}/viewers", headers=_headers(world, "admin")
    ).json()["viewers"]
    assert rows == []


@pytest.mark.parametrize(
    "slug",
    [
        "researcher",
        "professor",
        "suspended",
        "unlisted",
    ],
)
def test_an_ineligible_account_is_a_422_that_names_it(world, client, slug):
    workshop_id = _make_workshop(world, f"Ikat, ineligible {slug}")

    response = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [world["people"][slug].id]},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 422, response.text
    detail = str(response.json()["detail"])
    assert world["address"](slug) in detail or world["people"][slug].id in detail


def test_naming_the_creator_is_harmless(world, client):
    workshop_id = _make_workshop(world, "Ikat, creator named")

    response = _grant(world, workshop_id, ["creator", "colleague"])
    assert response.status_code == 200, response.text
    assert [row["userId"] for row in response.json()["viewers"]] == [world["people"]["colleague"].id]

    assert client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "creator")
    ).status_code == 200


def test_the_list_length_is_bounded(world, client):
    workshop_id = _make_workshop(world, "Ikat, unbounded")

    response = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [f"id{n}" for n in range(500)]},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 422, response.text



def _eligible(world: dict[str, Any], **params: Any):
    return world["client"].get(
        "/api/design-workshops/eligible-viewers",
        params=params,
        headers=_headers(world, "admin"),
    )


ELIGIBLE_FIXTURES = 5


def _run_term(world: dict[str, Any]) -> str:
    return f"-{world['stamp']}@example.org"


def test_eligible_viewers_offers_only_accounts_that_could_actually_open_a_workshop(world, client):
    response = _eligible(world, search=_run_term(world))
    assert response.status_code == 200, response.text
    body = response.json()
    offered = {row["id"] for row in body["users"]}

    assert offered == {
        world["people"]["admin"].id,
        world["people"]["creator"].id,
        world["people"]["colleague"].id,
        world["people"]["outsider"].id,
        world["people"]["shouty"].id,
    }
    assert world["people"]["outsider"].id in offered

    assert world["people"]["researcher"].id not in offered
    assert world["people"]["professor"].id not in offered
    assert world["people"]["suspended"].id not in offered
    assert world["people"]["unlisted"].id not in offered

    assert body["truncated"] is False


def test_the_search_matches_a_name_as_well_as_an_email(world, client):
    by_email = _eligible(world, search=_run_term(world))
    assert by_email.status_code == 200, by_email.text
    assert world["people"]["outsider"].id in {row["id"] for row in by_email.json()["users"]}

    by_name = _eligible(world, search=world["name_token"])
    assert by_name.status_code == 200, by_name.text
    assert [row["id"] for row in by_name.json()["users"]] == [world["people"]["outsider"].id]

    shouted = _eligible(world, search=world["name_token"].upper())
    assert shouted.status_code == 200, shouted.text
    assert [row["id"] for row in shouted.json()["users"]] == [world["people"]["outsider"].id]

    nothing = _eligible(world, search=f"no-account-is-called-this-{world['stamp']}")
    assert nothing.status_code == 200, nothing.text
    assert nothing.json()["users"] == []
    assert nothing.json()["truncated"] is False


def test_a_search_term_postgres_cannot_hold_is_an_empty_answer_not_a_500(world, client):
    response = _eligible(world, search=f"\x00{world['name_token']}")
    assert response.status_code == 200, response.text
    assert [row["id"] for row in response.json()["users"]] == [world["people"]["outsider"].id]


def test_the_answer_says_when_the_list_was_cut_and_says_nothing_when_it_was_not(world, client,
                                                                               monkeypatch):
    from app.services import design_workshop_viewers as service

    term = _run_term(world)

    monkeypatch.setattr(service, "ELIGIBLE_VIEWER_LIMIT", 2)
    cut = _eligible(world, search=term)
    assert cut.status_code == 200, cut.text
    assert len(cut.json()["users"]) == 2, "the extra row read to detect the cut must not be served"
    assert cut.json()["truncated"] is True

    monkeypatch.setattr(service, "ELIGIBLE_VIEWER_LIMIT", ELIGIBLE_FIXTURES)
    exactly_full = _eligible(world, search=term)
    assert exactly_full.status_code == 200, exactly_full.text
    assert len(exactly_full.json()["users"]) == ELIGIBLE_FIXTURES
    assert exactly_full.json()["truncated"] is False, (
        "a list that is exactly as long as the ceiling is complete, not truncated"
    )


def test_a_cut_roster_read_is_reported_instead_of_dropping_designers_in_silence(world, client,
                                                                               monkeypatch):
    from app.services import design_workshop_viewers as service

    term = _run_term(world)

    whole = _eligible(world, search=term)
    assert whole.status_code == 200, whole.text
    assert len(whole.json()["users"]) == ELIGIBLE_FIXTURES, whole.text

    monkeypatch.setattr(service, "ACTIVE_ROSTER_READ_LIMIT", 1)
    starved = _eligible(world, search=term)
    assert starved.status_code == 200, starved.text
    body = starved.json()

    assert len(body["users"]) < ELIGIBLE_FIXTURES, (
        "the roster read was capped at one row and the picker answered as though nothing changed"
    )
    assert world["people"]["admin"].id in {row["id"] for row in body["users"]}
    assert body["truncated"] is True


def test_the_picker_is_ordered_by_name_and_both_clients_depend_on_that(world, client):
    response = _eligible(world, search=_run_term(world))
    assert response.status_code == 200, response.text
    names = [row["name"] for row in response.json()["users"]]

    assert names == [
        "Second Designer",
        "Shouting Designer",
        f"Unrelated Designer {world['name_token']}",
        "Viewer Admin",
        "Workshop Creator",
    ], "the picker's order is the only order either client has"


def test_accounts_that_share_a_name_come_back_in_one_stable_order(world, client, monkeypatch):
    from app.core.db import db as prisma

    actions = type(prisma.user)
    captured: dict[str, Any] = {}
    original = actions.find_many

    async def spy(self: Any, *args: Any, **kwargs: Any):
        captured.update(kwargs)
        return await original(self, *args, **kwargs)

    monkeypatch.setattr(actions, "find_many", spy)

    response = _eligible(world, search=world["tie_token"])
    assert response.status_code == 200, response.text
    ids = [row["id"] for row in response.json()["users"]]

    assert set(ids) == {world["people"][slug].id for slug in world["tied_slugs"]}
    assert ids == sorted(ids), "accounts sharing a name must come back in a total, stable order"
    assert captured.get("order") == [{"name": "asc"}, {"id": "asc"}], (
        "the picker must ask Postgres for a TOTAL order: name for the reader, id so the cut is "
        "deterministic when hundreds of accounts share a name"
    )


def test_a_whitespace_only_search_is_the_same_as_no_search_at_all(world, client, monkeypatch):
    from app.services import design_workshop_viewers as service

    monkeypatch.setattr(service, "ELIGIBLE_VIEWER_LIMIT", 100_000)
    expected = {
        world["people"][slug].id
        for slug in ("admin", "creator", "colleague", "outsider", "shouty", *world["tied_slugs"])
    }

    for label, params in (
        ("omitted", {}),
        ("empty", {"search": ""}),
        ("three spaces", {"search": "   "}),
        ("tab and newline", {"search": " \t\n "}),
    ):
        answer = _eligible(world, **params)
        assert answer.status_code == 200, f"{label}: {answer.text}"
        body = answer.json()
        assert expected <= {row["id"] for row in body["users"]}, (
            f"a {label} search was treated as a filter instead of as no search at all"
        )
        assert body["truncated"] is False, label


def test_the_roster_read_cap_is_its_own_number_and_stays_a_backstop():
    from app.services import design_workshop_viewers as service

    assert service.ACTIVE_ROSTER_READ_LIMIT != service.ELIGIBLE_VIEWER_LIMIT, (
        "the roster read cap and the picker's page size are different quantities"
    )
    assert service.ACTIVE_ROSTER_READ_LIMIT >= 10 * service.ELIGIBLE_VIEWER_LIMIT, (
        "a roster read cap within reach of the picker's page size is a working limit, not a backstop"
    )


def test_a_designer_whose_address_is_stored_shouting_is_offered_and_may_be_granted(world, client):
    shouty = world["people"]["shouty"]
    assert shouty.email == world["address"]("shouty").upper(), "the fixture stores it shouting"

    by_roster_form = _eligible(world, search=world["address"]("shouty"))
    assert by_roster_form.status_code == 200, by_roster_form.text
    assert [row["id"] for row in by_roster_form.json()["users"]] == [shouty.id]

    by_stored_form = _eligible(world, search=shouty.email)
    assert [row["id"] for row in by_stored_form.json()["users"]] == [shouty.id]

    workshop_id = _make_workshop(world, "Ikat, shouting address")
    granted = _grant(world, workshop_id, ["shouty"])
    assert granted.status_code == 200, granted.text
    assert [row["userId"] for row in granted.json()["viewers"]] == [shouty.id]


def _barred_term(world: dict[str, Any]) -> str:
    return f"-{world['stamp']}@barred.example.org"


@pytest.mark.parametrize("slug", ACCESS_BARRED_SLUGS)
def test_an_account_the_allow_list_bars_is_neither_offered_nor_granted(world, client, slug):
    person = world["people"][slug]

    offered = _eligible(world, search=_barred_term(world))
    assert offered.status_code == 200, offered.text
    assert person.id not in {row["id"] for row in offered.json()["users"]}
    by_address = _eligible(world, search=person.email)
    assert by_address.json()["users"] == []

    workshop_id = _make_workshop(world, f"Ikat, allow-list {slug}")
    refused = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [person.id]},
        headers=_headers(world, "admin"),
    )
    assert refused.status_code == 422, refused.text
    detail = str(refused.json()["detail"])
    assert person.email in detail

    assert "access" in detail.lower()
    assert "designer roster" not in detail.lower(), (
        "the allow-list refusal must not send an admin to the empanelment screen"
    )


def test_an_empanelled_designer_awaiting_approval_is_still_offered(world, client):
    person = world["people"]["accessPending"]
    offered = _eligible(world, search=_barred_term(world))
    assert offered.status_code == 200, offered.text
    assert person.id in {row["id"] for row in offered.json()["users"]}

    workshop_id = _make_workshop(world, "Ikat, awaiting approval")
    granted = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [person.id]},
        headers=_headers(world, "admin"),
    )
    assert granted.status_code == 200, granted.text
    assert [row["userId"] for row in granted.json()["viewers"]] == [person.id]


def test_the_master_admin_is_exempt_from_the_allow_list_here_too(world, client):
    person = world["people"]["accessMaster"]
    offered = _eligible(world, search=_barred_term(world))
    assert offered.status_code == 200, offered.text
    assert person.id in {row["id"] for row in offered.json()["users"]}

    workshop_id = _make_workshop(world, "Ikat, break-glass master")
    granted = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [person.id]},
        headers=_headers(world, "admin"),
    )
    assert granted.status_code == 200, granted.text
    assert [row["userId"] for row in granted.json()["viewers"]] == [person.id]


def test_the_break_glass_is_the_configured_address_too_and_not_only_the_role(world, client):
    from app.core.config import get_settings

    person = world["people"]["accessConfiguredMaster"]

    barred_offer = _eligible(world, search=_barred_term(world))
    assert barred_offer.status_code == 200, barred_offer.text
    assert person.id not in {row["id"] for row in barred_offer.json()["users"]}

    settings = get_settings()
    previous = settings.master_admin_email
    settings.master_admin_email = person.email.upper()
    try:
        offered = _eligible(world, search=_barred_term(world))
        assert offered.status_code == 200, offered.text
        assert person.id in {row["id"] for row in offered.json()["users"]}, (
            "the picker's WHERE must spell BOTH arms of the break-glass, not just the role"
        )

        workshop_id = _make_workshop(world, "Ikat, configured break-glass")
        granted = client.put(
            f"/api/design-workshops/{workshop_id}/viewers",
            json={"userIds": [person.id]},
            headers=_headers(world, "admin"),
        )
        assert granted.status_code == 200, granted.text
        assert [row["userId"] for row in granted.json()["viewers"]] == [person.id]
    finally:
        settings.master_admin_email = previous


def test_a_designer_refused_by_both_tables_is_told_about_both_screens(world, client):
    person = world["people"]["accessUnempanelled"]

    offered = _eligible(world, search=_barred_term(world))
    assert offered.status_code == 200, offered.text
    assert person.id not in {row["id"] for row in offered.json()["users"]}

    workshop_id = _make_workshop(world, "Ikat, refused twice")
    refused = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [person.id]},
        headers=_headers(world, "admin"),
    )
    assert refused.status_code == 422, refused.text
    detail = str(refused.json()["detail"])
    assert "designer roster" in detail.lower(), detail
    assert "access screen" in detail.lower(), (
        "a doubly-refused account must be told about the allow-list in the SAME response, or the "
        "admin restores the empanelment and comes straight back"
    )


def test_a_role_that_can_never_hold_a_viewer_row_gets_one_sentence_and_not_two(world, client):
    person = world["people"]["researcher"]
    workshop_id = _make_workshop(world, "Ikat, wrong role")
    refused = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [person.id]},
        headers=_headers(world, "admin"),
    )
    assert refused.status_code == 422, refused.text
    detail = str(refused.json()["detail"])
    assert "is a RESEARCHER" in detail, detail
    assert "designer roster" not in detail.lower(), detail
    assert "access screen" not in detail.lower(), detail


def test_the_barred_read_is_a_cut_list_with_a_cap_of_its_own():
    from app.services import access_roster, design_workshop_viewers as service

    assert set(access_roster.BARRED) == {access_roster.REJECTED, access_roster.SUSPENDED}
    assert access_roster.PENDING not in access_roster.BARRED, (
        "a PENDING row is nobody's decision yet, and the sign-in path heals it"
    )
    assert access_roster.ACTIVE not in access_roster.BARRED

    assert access_roster.BARRED_EMAIL_READ_LIMIT != service.ELIGIBLE_VIEWER_LIMIT, (
        "the barred read cap and the picker's page size are different quantities"
    )
    assert access_roster.BARRED_EMAIL_READ_LIMIT >= 10 * service.ELIGIBLE_VIEWER_LIMIT, (
        "a barred read cap within reach of the picker's page size is a working limit, not a "
        "backstop — and cutting THIS read offers somebody an administrator has already refused"
    )


def test_eligible_viewers_is_not_swallowed_by_the_workshop_id_route(world, client):
    response = client.get("/api/design-workshops/eligible-viewers", headers=_headers(world, "admin"))
    assert response.status_code == 200, response.text
    assert "users" in response.json()


@pytest.mark.parametrize("slug", ["colleague", "outsider", "researcher", "professor"])
def test_only_an_admin_may_administer_viewers(world, client, slug):
    workshop_id = _make_workshop(world, f"Ikat, admin only {slug}")

    assert client.get(
        f"/api/design-workshops/{workshop_id}/viewers", headers=_headers(world, slug)
    ).status_code == 403
    assert client.get(
        "/api/design-workshops/eligible-viewers", headers=_headers(world, slug)
    ).status_code == 403
    assert client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": []},
        headers=_headers(world, slug),
    ).status_code == 403



def _make_questionnaire(world: dict[str, Any], workshop_id: str | None, title: str) -> str:
    body: dict[str, Any] = {"title": title}
    if workshop_id:
        body["designWorkshopId"] = workshop_id
    response = world["client"].post(
        "/api/questionnaires", json=body, headers=_headers(world, "creator")
    )
    assert response.status_code == 201, response.text
    return response.json()["id"]


def test_a_granted_colleague_sees_the_workshops_questionnaire_in_the_list(world, client):
    workshop_id = _make_workshop(world, "Questionnaire visibility")
    questionnaire_id = _make_questionnaire(world, workshop_id, "Attached instrument")

    before = client.get("/api/questionnaires", headers=_headers(world, "colleague"))
    assert before.status_code == 200, before.text
    assert questionnaire_id not in {row["id"] for row in before.json()["items"]}

    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    after = client.get("/api/questionnaires", headers=_headers(world, "colleague"))
    assert after.status_code == 200, after.text
    assert questionnaire_id in {row["id"] for row in after.json()["items"]}, (
        "the colleague can open the workshop but its questionnaire is invisible to them"
    )


def test_the_questionnaire_scope_survives_a_search(world, client):
    workshop_id = _make_workshop(world, "Questionnaire search scope")
    questionnaire_id = _make_questionnaire(world, workshop_id, "Barpali dyers instrument")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    found = client.get(
        "/api/questionnaires", params={"search": "Barpali"}, headers=_headers(world, "colleague")
    )
    assert found.status_code == 200, found.text
    assert questionnaire_id in {row["id"] for row in found.json()["items"]}

    missed = client.get(
        "/api/questionnaires", params={"search": "Kanchipuram"}, headers=_headers(world, "colleague")
    )
    assert questionnaire_id not in {row["id"] for row in missed.json()["items"]}


def test_an_unattached_questionnaire_stays_the_owners_alone(world, client):
    workshop_id = _make_workshop(world, "Unattached questionnaire")
    loose_id = _make_questionnaire(world, None, "Nothing to do with the workshop")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    listed = client.get("/api/questionnaires", headers=_headers(world, "colleague"))
    assert loose_id not in {row["id"] for row in listed.json()["items"]}


def test_an_ungranted_designer_still_cannot_see_the_questionnaire(world, client):
    workshop_id = _make_workshop(world, "Questionnaire refusal")
    questionnaire_id = _make_questionnaire(world, workshop_id, "Not yours")

    listed = client.get("/api/questionnaires", headers=_headers(world, "outsider"))
    assert questionnaire_id not in {row["id"] for row in listed.json()["items"]}


def test_mine_only_still_means_mine(world, client):
    workshop_id = _make_workshop(world, "Mine only")
    questionnaire_id = _make_questionnaire(world, workshop_id, "Creator's own")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    mine = client.get(
        "/api/questionnaires", params={"mineOnly": "true"}, headers=_headers(world, "colleague")
    )
    assert mine.status_code == 200, mine.text
    assert questionnaire_id not in {row["id"] for row in mine.json()["items"]}


def test_a_granted_colleague_may_read_the_sittings_and_download_the_workbook(world, client):
    workshop_id = _make_workshop(world, "Sittings and workbook")
    questionnaire_id = _make_questionnaire(world, workshop_id, "With a sitting")
    started = client.post(
        f"/api/questionnaires/{questionnaire_id}/entries",
        json={"respondentName": "Rekha Meher"},
        headers=_headers(world, "creator"),
    )
    assert started.status_code == 201, started.text

    refused = client.get(
        f"/api/questionnaires/{questionnaire_id}/xlsx", headers=_headers(world, "colleague")
    )
    assert refused.status_code == 403

    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    read = client.get(
        f"/api/questionnaires/{questionnaire_id}", headers=_headers(world, "colleague")
    )
    assert read.status_code == 200, read.text
    names = [entry.get("respondentName") for entry in read.json().get("entries", [])]
    assert "Rekha Meher" in names, "the sitting is hidden from a designer working on the workshop"

    allowed = client.get(
        f"/api/questionnaires/{questionnaire_id}/xlsx", headers=_headers(world, "colleague")
    )
    assert allowed.status_code == 200, allowed.text


def test_deleting_the_workshop_ends_the_grants_reach_into_its_questionnaire(world, client):
    workshop_id = _make_workshop(world, "Deleted workshop, live questionnaire")
    questionnaire_id = _make_questionnaire(world, workshop_id, "Survives its workshop")
    started = client.post(
        f"/api/questionnaires/{questionnaire_id}/entries",
        json={"respondentName": "Ramesh Sahu"},
        headers=_headers(world, "creator"),
    )
    assert started.status_code == 201, started.text
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    live = client.get(f"/api/questionnaires/{questionnaire_id}", headers=_headers(world, "colleague"))
    assert "Ramesh Sahu" in [e.get("respondentName") for e in live.json().get("entries", [])]
    assert (
        client.get(
            f"/api/questionnaires/{questionnaire_id}/xlsx", headers=_headers(world, "colleague")
        ).status_code
        == 200
    )

    deleted = client.delete(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "admin")
    )
    assert deleted.status_code == 204, deleted.text

    gone = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "colleague"))
    assert gone.status_code == 404

    read = client.get(f"/api/questionnaires/{questionnaire_id}", headers=_headers(world, "colleague"))
    assert read.status_code == 200, "the FORM stays readable by any designer — that is the policy"
    names = [entry.get("respondentName") for entry in read.json().get("entries", [])]
    assert "Ramesh Sahu" not in names, (
        "a viewer of a DELETED workshop was still handed the respondent's name"
    )

    workbook = client.get(
        f"/api/questionnaires/{questionnaire_id}/xlsx", headers=_headers(world, "colleague")
    )
    assert workbook.status_code == 403, (
        "the lossless workbook of every sitting outlived the workshop the grant was about"
    )


def test_an_outsider_still_cannot_read_the_sittings_or_the_workbook(world, client):
    workshop_id = _make_workshop(world, "Sittings refusal")
    questionnaire_id = _make_questionnaire(world, workshop_id, "Outsider refusal")
    started = client.post(
        f"/api/questionnaires/{questionnaire_id}/entries",
        json={"respondentName": "Sunil Bhoi"},
        headers=_headers(world, "creator"),
    )
    assert started.status_code == 201, started.text

    read = client.get(
        f"/api/questionnaires/{questionnaire_id}", headers=_headers(world, "outsider")
    )
    assert read.status_code == 200, "the FORM stays readable by any designer — that is the policy"
    names = [entry.get("respondentName") for entry in read.json().get("entries", [])]
    assert "Sunil Bhoi" not in names, "an outsider was handed the respondent's name"

    workbook = client.get(
        f"/api/questionnaires/{questionnaire_id}/xlsx", headers=_headers(world, "outsider")
    )
    assert workbook.status_code == 403
