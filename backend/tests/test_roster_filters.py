import logging
import os
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest

from app.core.db import db
from app.core.security import create_access_token

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

ROSTER = "/api/designers/roster"
INSTITUTIONS = "/api/designers/roster/institutions"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    tie_tag = f"Roster Filters Tie {stamp}"

    def email(slug: str) -> str:
        return f"rf-{slug}-{stamp}@example.org".lower()

    people: dict[str, Any] = {}
    rows: dict[str, Any] = {}
    await db.connect()
    try:
        people["admin"] = await db.user.create(
            data={"email": email("admin"), "name": "Roster Filters Admin", "role": "ADMIN"}
        )
        people["inspector"] = await db.user.create(
            data={"email": email("inspector"), "name": "Roster Filters Inspector", "role": "INSPECTOR"}
        )
        people["professor"] = await db.user.create(
            data={"email": email("professor"), "name": "Roster Filters Professor", "role": "PROFESSOR"}
        )

        inspector_role_count = await db.user.count(where={"role": "INSPECTOR"})

        for i in range(3):
            await db.user.create(data={
                "email": email(f"cap-probe-{i}"),
                "name": f"Roster Filters Cap Probe {i} {stamp}",
                "role": "FIELD_CONTRIBUTOR",
            })

        tied_at = datetime(2026, 1, 15, 12, 0, 0, tzinfo=UTC)
        rows["inspector"] = await db.designerroster.create(data={
            "email": email("inspector"),
            "fullName": tie_tag,
            "institution": f"Inst-A-{stamp}",
            "isActive": True,
            "firstSeenAt": tied_at,
            "createdAt": tied_at,
            "addedById": people["admin"].id,
        })
        rows["professor"] = await db.designerroster.create(data={
            "email": email("professor"),
            "fullName": tie_tag,
            "institution": f"Inst-B-{stamp}",
            "isActive": True,
            "firstSeenAt": tied_at,
            "createdAt": tied_at,
            "addedById": people["admin"].id,
        })
        rows["neverSignedIn"] = await db.designerroster.create(data={
            "email": email("never-signed-in"),
            "fullName": tie_tag,
            "institution": None,
            "isActive": True,
            "firstSeenAt": None,
            "createdAt": tied_at,
            "addedById": people["admin"].id,
        })

        rows["suspended"] = await db.designerroster.create(data={
            "email": email("suspended"),
            "fullName": f"Roster Filters Suspended {stamp}",
            "institution": f"Inst-A-{stamp}",
            "isActive": False,
            "revokedAt": tied_at + timedelta(days=1),
            "firstSeenAt": tied_at,
            "createdAt": tied_at + timedelta(days=1),
            "addedById": people["admin"].id,
        })

        rows["blankInstitution"] = await db.designerroster.create(data={
            "email": email("blank-institution"),
            "fullName": f"Roster Filters Blank Institution {stamp}",
            "institution": "",
            "isActive": True,
            "firstSeenAt": tied_at,
            "createdAt": tied_at + timedelta(days=2),
            "addedById": people["admin"].id,
        })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "people": people,
            "rows": rows,
            "stamp": stamp,
            "tie_tag": tie_tag,
            "inspector_role_count": inspector_role_count,
        }


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str = "admin") -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['people'][slug].id)}"}


def _get(client: Any, world: dict[str, Any], path: str, **params: Any) -> Any:
    response = client.get(path, params=params, headers=_headers(world))
    assert response.status_code == 200, response.text
    return response.json()


def _roster(client: Any, world: dict[str, Any], **params: Any) -> dict[str, Any]:
    params.setdefault("search", world["stamp"])
    params.setdefault("pageSize", 100)
    return _get(client, world, ROSTER, **params)


def _emails(payload: dict[str, Any]) -> set[str]:
    return {row["email"] for row in payload["items"]}



async def test_standing_and_activeOnly_disagreement_is_a_422(world, client):
    response = client.get(
        ROSTER,
        params={"activeOnly": "true", "standing": "suspended"},
        headers=_headers(world),
    )
    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert "activeOnly" in detail and "suspended" in detail


async def test_standing_and_activeOnly_agreement_is_not_refused(world, client):
    payload = _roster(client, world, activeOnly="true", standing="active")
    assert world["people"]["inspector"].email in _emails(payload)


async def test_an_unknown_standing_value_is_a_422_naming_the_two_real_values(world, client):
    response = client.get(ROSTER, params={"standing": "revoked"}, headers=_headers(world))
    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert "active" in detail and "suspended" in detail


async def test_activeOnly_true_still_means_standing_active_for_a_client_that_never_changed(
    world, client
):
    payload = _roster(client, world, activeOnly="true")
    emails = _emails(payload)
    assert world["people"]["inspector"].email in emails
    assert world["rows"]["suspended"].email not in emails



async def test_default_designer_roster_lists_suspended(world, client):
    payload = _roster(client, world)
    emails = _emails(payload)
    assert world["rows"]["suspended"].email in emails, (
        "an admin opens this screen holding a message from somebody who cannot sign in; the row "
        "that explains why must be visible without asking for it"
    )
    assert emails == {
        world["people"]["inspector"].email,
        world["people"]["professor"].email,
        world["rows"]["neverSignedIn"].email,
        world["rows"]["suspended"].email,
        world["rows"]["blankInstitution"].email,
    }



async def test_absent_roles_returns_every_tier_including_never_signed_in(world, client):
    payload = _roster(client, world)
    assert world["rows"]["neverSignedIn"].email in _emails(payload)


async def test_roles_filter_matches_by_the_linked_accounts_role_not_a_roster_column(world, client):
    only_inspector = _roster(client, world, roles="INSPECTOR")
    assert _emails(only_inspector) == {world["people"]["inspector"].email}

    both = _roster(client, world, roles="INSPECTOR,PROFESSOR")
    assert _emails(both) == {world["people"]["inspector"].email, world["people"]["professor"].email}

    response = client.get(
        ROSTER,
        params=[("search", world["stamp"]), ("pageSize", "100"),
                ("roles", "INSPECTOR"), ("roles", "PROFESSOR")],
        headers=_headers(world),
    )
    assert response.status_code == 200, response.text
    assert _emails(response.json()) == _emails(both)


async def test_roles_never_signed_in_reserved_token_matches_firstSeenAt_null(world, client):
    payload = _roster(client, world, roles="never-signed-in")
    assert _emails(payload) == {world["rows"]["neverSignedIn"].email}


async def test_an_unknown_roles_value_is_a_422(world, client):
    response = client.get(ROSTER, params={"roles": "NOT_A_ROLE"}, headers=_headers(world))
    assert response.status_code == 422, response.text


async def test_role_match_truncation_is_reported_on_the_wire_and_logged_at_error(
    world, client, monkeypatch, caplog
):
    from app.api.routes import designers as designers_route

    monkeypatch.setattr(designers_route, "ROLE_MATCH_READ_LIMIT", 2)
    with caplog.at_level(logging.ERROR, logger="app.api.routes.designers"):
        response = client.get(
            ROSTER, params={"roles": "FIELD_CONTRIBUTOR"}, headers=_headers(world)
        )
    assert response.status_code == 200, response.text
    assert response.json()["roleMatchTruncated"] is True
    assert any(
        "accounts hold the filtered roles" in record.message for record in caplog.records
    ), "the cut must be loud in the logs even though the API degrades gracefully"


async def test_role_match_at_exactly_the_cap_is_not_truncated_and_still_finds_the_row(
    world, client, monkeypatch
):
    from app.api.routes import designers as designers_route

    actual_count = world["inspector_role_count"]
    assert actual_count >= 1, "world's own inspector fixture guarantees at least one such account"
    monkeypatch.setattr(designers_route, "ROLE_MATCH_READ_LIMIT", actual_count)
    payload = _roster(client, world, roles="INSPECTOR")
    assert payload["roleMatchTruncated"] is False, (
        "a matching-account count exactly equal to the cap must not report a cut"
    )
    assert world["people"]["inspector"].email in _emails(payload), (
        "the boundary case must not ALSO lose the one row it was exactly sized to include — a "
        "truncation flag that stayed False while the row vanished anyway would be a false negative "
        "worse than an honest truncated:true"
    )


async def test_roleMatchTruncated_is_false_when_no_role_filter_was_requested(world, client):
    payload = _roster(client, world)
    assert payload["roleMatchTruncated"] is False



async def test_institution_filter_is_an_exact_match_across_both_standings(world, client):
    payload = _roster(client, world, institutions=f"Inst-A-{world['stamp']}")
    assert _emails(payload) == {
        world["people"]["inspector"].email,
        world["rows"]["suspended"].email,
    }


async def test_institutions_reserved_none_token_matches_null_but_not_empty_string(world, client):
    payload = _roster(client, world, institutions="none")
    emails = _emails(payload)
    assert world["rows"]["neverSignedIn"].email in emails, "NULL must match the reserved token"
    assert world["rows"]["blankInstitution"].email not in emails, (
        "an empty string is not NULL — the two must not be folded into one meaning"
    )


async def test_institutions_endpoint_excludes_null_and_empty_institutions(
    world, client, monkeypatch
):
    from app.api.routes import designers as designers_route

    monkeypatch.setattr(designers_route, "INSTITUTION_LIST_CAP", 1_000_000)
    payload = _get(client, world, INSTITUTIONS)
    items = payload["items"]
    assert "" not in items
    assert None not in items
    assert f"Inst-A-{world['stamp']}" in items
    assert f"Inst-B-{world['stamp']}" in items


async def test_institutions_endpoint_states_truncation_past_its_cap(world, client, monkeypatch):
    from app.api.routes import designers as designers_route

    monkeypatch.setattr(designers_route, "INSTITUTION_LIST_CAP", 1)
    payload = _get(client, world, INSTITUTIONS)
    assert payload["truncated"] is True
    assert len(payload["items"]) == 1
    assert payload["total"] == 1


async def test_a_non_admin_cannot_read_the_institutions_endpoint(world, client):
    response = client.get(INSTITUTIONS, headers=_headers(world, "inspector"))
    assert response.status_code == 403, response.text



async def test_stable_sort_holds_across_a_paged_walk_over_a_shared_sort_key_value(world, client):
    ids: list[str] = []
    for page in (1, 2, 3):
        payload = _roster(
            client, world, search=world["tie_tag"], pageSize=1, page=page, sort="added", dir="desc"
        )
        assert payload["total"] == 3, "the tie_tag must isolate exactly the three tied rows"
        assert len(payload["items"]) == 1
        ids.append(payload["items"][0]["id"])

    assert len(set(ids)) == 3, f"a stable total order must not repeat or skip a row: got {ids}"
    assert set(ids) == {
        world["rows"]["inspector"].id,
        world["rows"]["professor"].id,
        world["rows"]["neverSignedIn"].id,
    }


async def test_an_unknown_sort_value_is_a_422(world, client):
    response = client.get(ROSTER, params={"sort": "notacolumn"}, headers=_headers(world))
    assert response.status_code == 422, response.text


async def test_an_unknown_dir_value_is_a_422(world, client):
    response = client.get(ROSTER, params={"dir": "sideways"}, headers=_headers(world))
    assert response.status_code == 422, response.text



async def test_dateField_firstSeen_range_excludes_the_never_signed_in_row(world, client):
    payload = _roster(
        client,
        world,
        dateField="firstSeen",
        dateFrom="2020-01-01T00:00:00Z",
        dateTo="2030-01-01T00:00:00Z",
    )
    emails = _emails(payload)
    assert world["people"]["inspector"].email in emails
    assert world["rows"]["neverSignedIn"].email not in emails


async def test_an_unknown_dateField_value_is_a_422(world, client):
    response = client.get(ROSTER, params={"dateField": "decided"}, headers=_headers(world))
    assert response.status_code == 422, response.text
