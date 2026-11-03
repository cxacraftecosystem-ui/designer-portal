import os
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest
from fastapi.testclient import TestClient

from app.core.db import db
from app.core.deps import ROLE_RANK
from app.core.security import create_access_token, hash_password
from app.main import app

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

PASSWORD = "access-roster-filter-test-password"

ANCHOR = datetime(2024, 3, 1, tzinfo=UTC)

DATE_ROW_SPECS: tuple[tuple[str, str, int], ...] = (
    ("date-added", "createdAt", 1),
    ("date-requested", "requestedAt", 2),
    ("date-decided", "decidedAt", 3),
    ("date-joined", "joinedAt", 4),
    ("date-firstseen", "firstSeenAt", 5),
)
_DATE_ROW_OFFSET = {slug: offset for slug, _column, offset in DATE_ROW_SPECS}

DATE_FIELD_TOKENS: tuple[tuple[str, str], ...] = (
    ("added", "date-added"),
    ("requested", "date-requested"),
    ("decided", "date-decided"),
    ("joined", "date-joined"),
    ("firstSeen", "date-firstseen"),
)

TIEBREAK_INSTANT = datetime(2023, 6, 15, 9, 0, tzinfo=UTC)


@pytest.fixture(scope="module")
async def world():
    stamp = uuid.uuid4().hex[:8]

    def email(slug: str) -> str:
        group, sep, suffix = slug.partition("-")
        tail = f"-{suffix}" if sep else ""
        return f"rf-{group}-{stamp}{tail}@example.org".lower()

    rows: dict[str, Any] = {}
    tie_marker = f"tiebreak-marker-{stamp}"
    await db.connect()
    try:
        admin = await db.user.create(
            data={
                "email": email("admin"),
                "name": f"Roster Filter Admin {stamp}",
                "role": "ADMIN",
                "passwordHash": hash_password(PASSWORD),
            }
        )

        status_specs = (
            ("status-pending", "PENDING", None),
            ("status-active", "ACTIVE", "DESIGNER"),
            ("status-rejected", "REJECTED", "ADMIN"),
            ("status-suspended", "SUSPENDED", "RESEARCHER"),
        )
        for slug, row_status, admit_role in status_specs:
            rows[slug] = await db.accessroster.create(
                data={
                    "email": email(slug),
                    "status": row_status,
                    "admitRole": admit_role,
                }
            )

        for slug, column, offset in DATE_ROW_SPECS:
            rows[slug] = await db.accessroster.create(
                data={"email": email(slug), column: ANCHOR + timedelta(days=offset)}
            )

        for slug in ("tie-a", "tie-b"):
            rows[slug] = await db.accessroster.create(
                data={
                    "email": email(slug),
                    "createdAt": TIEBREAK_INSTANT,
                    "notes": tie_marker,
                }
            )
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "admin": admin,
            "rows": rows,
            "stamp": stamp,
            "email": email,
            "tie_marker": tie_marker,
        }


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any]) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['admin'].id)}"}


def _roster(client: Any, world: dict[str, Any], **params: Any) -> dict[str, Any]:
    response = client.get("/api/access/roster", params=params, headers=_headers(world))
    assert response.status_code == 200, response.text
    return response.json()


def _emails(payload: dict[str, Any]) -> set[str]:
    return {row["email"] for row in payload["items"]}



async def test_a_single_status_value_behaves_exactly_as_it_did_before_status_became_plural(
    world, client
):
    stamp = world["stamp"]

    upper = _roster(client, world, status="PENDING", search=f"rf-status-{stamp}")
    assert _emails(upper) == {world["email"]("status-pending")}
    assert {row["status"] for row in upper["items"]} == {"PENDING"}

    lower = _roster(client, world, status="pending", search=f"rf-status-{stamp}")
    assert _emails(lower) == _emails(upper)

    response = client.get(
        "/api/access/roster", params={"status": "PENDNIG"}, headers=_headers(world)
    )
    assert response.status_code == 422, response.text
    assert "PENDING" in response.json()["detail"]


async def test_status_accepts_both_the_repeated_and_comma_joined_spelling_for_multiple_values(
    world, client
):
    stamp = world["stamp"]
    expected = {world["email"]("status-pending"), world["email"]("status-suspended")}

    response = client.get(
        "/api/access/roster",
        params=[
            ("status", "PENDING"),
            ("status", "SUSPENDED"),
            ("search", f"rf-status-{stamp}"),
        ],
        headers=_headers(world),
    )
    assert response.status_code == 200, response.text
    repeated = response.json()
    assert _emails(repeated) == expected

    comma = _roster(client, world, status="PENDING,SUSPENDED", search=f"rf-status-{stamp}")
    assert _emails(comma) == expected



async def test_default_query_includes_every_status_and_every_role_by_absence(world, client):
    payload = _roster(client, world, search=f"rf-status-{world['stamp']}")
    items = payload["items"]
    assert {row["status"] for row in items} == {"PENDING", "ACTIVE", "REJECTED", "SUSPENDED"}
    assert {row["admitRole"] for row in items} == {None, "DESIGNER", "ADMIN", "RESEARCHER"}


async def test_role_match_truncated_is_always_false_on_this_route(world, client):
    stamp = world["stamp"]
    plain = _roster(client, world, search=stamp)
    assert plain["roleMatchTruncated"] is False

    filtered = _roster(client, world, search=f"rf-status-{stamp}", roles="ADMIN,DESIGNER")
    assert filtered["roleMatchTruncated"] is False



async def test_roles_filter_with_the_reserved_default_token(world, client):
    stamp = world["stamp"]
    search = f"rf-status-{stamp}"

    only_default = _roster(client, world, search=search, roles="default")
    assert _emails(only_default) == {world["email"]("status-pending")}

    named_and_default = _roster(client, world, search=search, roles="default,DESIGNER")
    assert _emails(named_and_default) == {
        world["email"]("status-pending"),
        world["email"]("status-active"),
    }

    named_only = _roster(client, world, search=search, roles="ADMIN")
    assert _emails(named_only) == {world["email"]("status-rejected")}


async def test_all_named_roles_ticked_excludes_the_reserved_default_row_unlike_absence(
    world, client
):
    stamp = world["stamp"]
    search = f"rf-status-{stamp}"
    all_named_tiers = ",".join(sorted(ROLE_RANK))

    named_rows = {
        world["email"]("status-active"),
        world["email"]("status-rejected"),
        world["email"]("status-suspended"),
    }
    default_row = world["email"]("status-pending")

    absent = _roster(client, world, search=search)
    assert _emails(absent) == named_rows | {default_row}, (
        "absent `roles` is the control: it must include the default-tier row beside the three "
        "named ones, or the fixture itself is not exercising what this test needs"
    )

    all_ticked = _roster(client, world, search=search, roles=all_named_tiers)
    assert _emails(all_ticked) == named_rows, (
        "ticking every NAMED tier must still exclude the reserved-default row; if it does not, "
        "the reserved `default` token is unreachable in practice and every default-tier admission "
        "on the real roster would vanish from any 'select all' state a client ever produces"
    )
    assert default_row not in _emails(all_ticked)
    assert _emails(all_ticked) != _emails(absent), (
        "the two requests must produce DIFFERENT row counts, or 'everything ticked' and 'nothing "
        "ticked' are indistinguishable on the wire, which is the one state rule (i) forbids"
    )



@pytest.mark.parametrize("token,row_slug", DATE_FIELD_TOKENS)
async def test_each_date_field_filters_on_its_own_column(world, client, token, row_slug):
    target = ANCHOR + timedelta(days=_DATE_ROW_OFFSET[row_slug])
    payload = _roster(
        client,
        world,
        search=f"rf-date-{world['stamp']}",
        dateField=token,
        dateFrom=(target - timedelta(hours=1)).isoformat(),
        dateTo=(target + timedelta(hours=1)).isoformat(),
    )
    assert _emails(payload) == {world["email"](row_slug)}


async def test_an_out_of_range_date_field_is_a_422_naming_the_valid_five(world, client):
    response = client.get(
        "/api/access/roster", params={"dateField": "revoked"}, headers=_headers(world)
    )
    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    for token in ("added", "decided", "firstSeen", "joined", "requested"):
        assert token in detail, detail



async def test_sort_by_email_orders_the_page_and_defaults_to_ascending(world, client):
    search = f"rf-status-{world['stamp']}"
    expected_asc = [
        world["email"]("status-active"),
        world["email"]("status-pending"),
        world["email"]("status-rejected"),
        world["email"]("status-suspended"),
    ]

    ascending = _roster(client, world, search=search, sort="email")
    assert [row["email"] for row in ascending["items"]] == expected_asc

    descending = _roster(client, world, search=search, sort="email", dir="desc")
    assert [row["email"] for row in descending["items"]] == list(reversed(expected_asc))


async def test_an_unknown_sort_token_is_a_422_naming_the_real_ones(world, client):
    response = client.get("/api/access/roster", params={"sort": "urgency"}, headers=_headers(world))
    assert response.status_code == 422, response.text
    assert "added" in response.json()["detail"]


async def test_an_unknown_dir_token_is_a_422(world, client):
    response = client.get("/api/access/roster", params={"dir": "sideways"}, headers=_headers(world))
    assert response.status_code == 422, response.text
    assert "asc" in response.json()["detail"] and "desc" in response.json()["detail"]



async def test_two_rows_sharing_a_sort_key_are_neither_repeated_nor_skipped_across_pages(
    world, client
):
    tie_ids = {world["rows"]["tie-a"].id, world["rows"]["tie-b"].id}

    def _page(number: int) -> dict[str, Any]:
        return _roster(client, world, search=world["tie_marker"], pageSize=1, page=number)

    first = _page(1)
    second = _page(2)
    assert first["total"] == 2
    assert second["total"] == 2
    assert len(first["items"]) == 1
    assert len(second["items"]) == 1
    assert {first["items"][0]["id"], second["items"][0]["id"]} == tie_ids

    repeat = _page(1)
    assert repeat["items"][0]["id"] == first["items"][0]["id"]
