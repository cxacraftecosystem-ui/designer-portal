"""``GET /access/roster``'s requirement-30 filter and sort contract.

This route had exactly one caller-visible filter before this change — ``?status=`` — and both
clients have been sending it since the endpoint existed (Android's ``WorkshopRepositoryApi.kt``
``accessRoster`` call; the web's ``listAccessRoster``). What is pinned here is that the six new
parameters (``status`` becoming plural, plus ``roles``, ``dateField``, ``dateFrom``, ``dateTo``,
``sort``, ``dir``) are ADDITIVE rather than a rewrite of what already worked, and that the four
binding rules DROPDOWN_DESIGN §4.6 states as testable sentences are the route's actual DEFAULT
behaviour rather than something a caller has to opt into:

**A SINGLE ``status`` VALUE IS BYTE-IDENTICAL TO BEFORE THIS ROUTE COULD TAKE MORE THAN ONE.**
``status`` moved from a scalar checked by hand to a list run through
:func:`app.services.record_filters.enum_filter_list_or_422`, and the whole point of that helper is
that a single value still produces ``{"status": {"in": ["PENDING"]}}`` — the same rows Postgres
returns for the old ``{"status": "PENDING"}``, in the same order. Neither client has to change a
line to keep working through this rollout.

**EMPTY MEANS EVERYTHING, BY ABSENCE — never by an all-ticked state.** A request with no ``status``
lists pending, rejected and suspended rows beside active ones, because this route's own reason for
existing is helping an admin find the row refusing somebody; a request with no ``roles`` lists every
tier, including rows admitted at the platform default (``admitRole IS NULL``, the reserved
``"default"`` token no eight-tier picker without a ninth row could otherwise reach).

**``dateField`` PICKS ONE OF FIVE COLUMNS, AND A BAD TOKEN IS A 422 NAMING ALL FIVE**, never a
``KeyError`` turned into a bare 500.

**TWO ROWS SHARING A SORT KEY ARE NEITHER REPEATED NOR SKIPPED ACROSS A PAGED WALK.** This is not a
contrived edge case on this table: the migration that grandfathered the pre-existing allow-list onto
it inserted several hundred real rows with one ``CURRENT_TIMESTAMP``, so the default sort alone has
a large tie group on the live database. ``count_and_page`` appends the ``id`` tiebreak on the way
through, replacing the hand-rolled ``asyncio.gather`` this route used to run its own copy of the same
fix on — and the point under test is that the MOVE kept the guarantee, not merely that the guarantee
exists in the abstract (``with_id_tiebreak`` has its own pure-function tests in
``test_record_write_path.py``; this file tests that the route actually uses it).

**``roleMatchTruncated`` IS ALWAYS ``False`` ON THIS ROUTE.** ``admitRole`` is a real column on
``AccessRoster``, unlike the designer roster's role filter (which reads a second table through a
capped query — DROPDOWN_DESIGN §4.4), so there is nothing here to truncate; the flag rides the
envelope regardless so both rosters answer one shape.

Postgres is required — every behaviour here is a row a paged, filtered query either does or does not
return — so the module skips itself when ``DATABASE_URL`` does not point at a local database, exactly
as ``test_platform_access_gate`` does.

    docker compose up -d postgres minio
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

Every row this module needs is created directly against the database rather than through the
route's own write endpoints, because the columns under test — ``requestedAt``, ``decidedAt``,
``firstSeenAt``, and a ``createdAt`` shared on purpose by two rows — are never settable through
``POST /access/roster`` and a fixture that went through it could not put a row in a controlled past.
Every assertion, in turn, is made by reading the route back through ``GET /access/roster`` and never
by awaiting the ``db`` singleton after the ``TestClient`` exists — the singleton is connected on the
event loop the app's own lifespan owns, and a test awaiting it on pytest's loop fails with "bound to
a different event loop" rather than with anything to do with the rule under test. See the same note
in ``test_designer_empanelment_auto.py``.
"""

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

# Long enough for LoginRequest's min_length=8 were this ever used to sign in, which it is not —
# every request in this module carries a bearer token minted directly, matching
# ``test_platform_access_gate``'s ``_headers`` for the same reason: the gate under test here is
# ``require_access_manager``, not the login endpoint, and signing in first would make every
# assertion below depend on a path this module is not about.
PASSWORD = "access-roster-filter-test-password"

#: A fixed instant well outside any real test run's clock, so the five date-range tests below can
#: build a tight range around a KNOWN value instead of racing the wall clock. Timezone-aware, as
#: every stamp in this codebase's database is (``DTZ`` is part of the lint gate for exactly that).
ANCHOR = datetime(2024, 3, 1, tzinfo=UTC)

#: slug -> (the column DROPDOWN_DESIGN §4.1 maps its ``dateField`` token to, days after ANCHOR).
#: Shared between the fixture that writes these rows and the parametrised test that builds a range
#: around each one, so the two cannot silently drift apart into testing the wrong column.
DATE_ROW_SPECS: tuple[tuple[str, str, int], ...] = (
    ("date-added", "createdAt", 1),
    ("date-requested", "requestedAt", 2),
    ("date-decided", "decidedAt", 3),
    ("date-joined", "joinedAt", 4),
    ("date-firstseen", "firstSeenAt", 5),
)
_DATE_ROW_OFFSET = {slug: offset for slug, _column, offset in DATE_ROW_SPECS}

#: ``dateField`` wire token -> the fixture row it must isolate. A SEPARATE table from
#: ``DATE_ROW_SPECS`` rather than one column reused for both, so a test that gets
#: ``app.api.routes.access.ACCESS_DATE_COLUMNS`` wrong (the token pointed at the wrong column) fails
#: LOUDLY instead of coincidentally matching the right row anyway.
DATE_FIELD_TOKENS: tuple[tuple[str, str], ...] = (
    ("added", "date-added"),
    ("requested", "date-requested"),
    ("decided", "date-decided"),
    ("joined", "date-joined"),
    ("firstSeen", "date-firstseen"),
)

#: A fixed instant two rows share on purpose — the tie-break fixture. Deliberately a DIFFERENT
#: instant from ``ANCHOR`` and its offsets above, so a bug that widened a date-range query too far
#: could not accidentally pull these two rows into a date-field test's result set.
TIEBREAK_INSTANT = datetime(2023, 6, 15, 9, 0, tzinfo=UTC)


@pytest.fixture(scope="module")
async def world():
    """One admin account, four rows spanning every status and three named tiers plus the reserved
    default one, five rows each stamping exactly one of the five filterable date columns, and two
    rows sharing one ``createdAt`` for the tiebreak walk.

    Every email is stamped with a per-run UUID fragment AND grouped by a distinguishing prefix
    (``rf-status-``, ``rf-date-``) so a single ``search=`` term can scope a query to exactly one
    group of this fixture's own rows without also catching a hundred other runs' leftovers on a
    database nobody truncates between suites — the same device ``test_platform_access_gate`` uses
    with its own ``stamp``.
    """
    stamp = uuid.uuid4().hex[:8]

    def email(slug: str) -> str:
        # THE STAMP SITS RIGHT AFTER THE GROUP NAME, NOT AFTER THE WHOLE SLUG — on purpose, and it
        # is what makes a GROUP-LEVEL search term possible at all. ``slug`` is always
        # ``"<group>-<suffix>"`` (``"status-pending"``, ``"date-added"``) or a bare group with no
        # suffix (``"admin"``); putting the suffix BEFORE the stamp, as a naive
        # ``f"rf-{slug}-{stamp}@..."`` would, buries the stamp behind a different string per row
        # ("status-pending-<stamp>" vs "status-active-<stamp>"), so a query for
        # ``search=f"rf-status-{stamp}"`` — meant to catch every row in the group — matches NONE of
        # them, because the suffix sits in between and breaks the substring. Emitting
        # ``rf-<group>-<stamp>-<suffix>`` instead makes ``f"rf-{group}-{stamp}"`` a genuine prefix of
        # every row in that group, so one search term can select "every status row this run made"
        # without also catching another run's leftovers (which do not share this run's stamp) or a
        # sibling group's rows (which do not share this run's group name).
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

        # status-pending/-active/-rejected/-suspended: one row per AccessStatus member, and three
        # different admitRole values (one of them NULL) so the same four rows also carry the roles
        # fixture — status and admitRole are independent columns and a test of one must not need a
        # row the other test group does not otherwise care about.
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
    """A bearer token minted directly for the admin fixture account — see the module docstring for
    why this module never signs in."""
    return {"Authorization": f"Bearer {create_access_token(subject=world['admin'].id)}"}


def _roster(client: Any, world: dict[str, Any], **params: Any) -> dict[str, Any]:
    response = client.get("/api/access/roster", params=params, headers=_headers(world))
    assert response.status_code == 200, response.text
    return response.json()


def _emails(payload: dict[str, Any]) -> set[str]:
    return {row["email"] for row in payload["items"]}


# --------------------------------------------------------------------------------------
# 1. `status` — the single-value case is unchanged, and the plural grammar is additive
# --------------------------------------------------------------------------------------


async def test_a_single_status_value_behaves_exactly_as_it_did_before_status_became_plural(
    world, client
):
    """``?status=PENDING`` is the exact shape Android has always sent. Folding it through the new
    multi-valued helper must not change which rows come back, whether the value is upper-cased as
    the wire vocabulary spells it or lower-cased as the OLD route's ``.strip().upper()`` also
    accepted, and a typo must still be a 422 naming the real values rather than a silently empty
    page."""
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
    """The two spellings ``resolve_types``/``resolve_workshop_ids`` already established: repeated
    ``status=`` parameters and one comma-joined value must answer the SAME question, because the web
    and Android build query strings differently and a filter that quietly covered everything because
    it was spelled the other way would look exactly like the filter not working."""
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


# --------------------------------------------------------------------------------------
# 2. The binding rules: absence means everything, and rejected/suspended stay visible
# --------------------------------------------------------------------------------------


async def test_default_query_includes_every_status_and_every_role_by_absence(world, client):
    """RULE (i) and RULE (ii) together, over one request with neither ``status`` nor ``roles`` set.
    Pending, rejected and suspended rows all appear — this route's whole reason for existing is
    finding the row refusing somebody — and the reserved-default-tier row (``admitRole IS NULL``)
    appears beside three named tiers, because there is no query-string spelling of "everything"
    other than leaving the parameter off."""
    payload = _roster(client, world, search=f"rf-status-{world['stamp']}")
    items = payload["items"]
    assert {row["status"] for row in items} == {"PENDING", "ACTIVE", "REJECTED", "SUSPENDED"}
    assert {row["admitRole"] for row in items} == {None, "DESIGNER", "ADMIN", "RESEARCHER"}


async def test_role_match_truncated_is_always_false_on_this_route(world, client):
    """``admitRole`` is a real column here, so a role filter is one clause in the same query and
    never truncates — but the key is sent on EVERY response regardless, always ``false``, so both
    roster envelopes share one shape (DROPDOWN_DESIGN §4.2)."""
    stamp = world["stamp"]
    plain = _roster(client, world, search=stamp)
    assert plain["roleMatchTruncated"] is False

    filtered = _roster(client, world, search=f"rf-status-{stamp}", roles="ADMIN,DESIGNER")
    assert filtered["roleMatchTruncated"] is False


# --------------------------------------------------------------------------------------
# 3. `roles` — the reserved `default` token
# --------------------------------------------------------------------------------------


async def test_roles_filter_with_the_reserved_default_token(world, client):
    """``?roles=default`` means ``admitRole IS NULL`` — the platform-default tier no NAMED option
    can reach — exercised beside a named-tier request so both are proven to run through the SAME
    ``OR`` clause rather than two code paths that could quietly drift apart."""
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
    """RULE (i)'s SHARPEST EDGE, DROPDOWN_DESIGN §4.6:
    ``test_all_eight_ticked_is_not_the_same_request_as_none_ticked``. An ALL-TICKED multi-select
    must not become a second spelling of "no filter", even when "all" means every NAMED tier there
    is — that is exactly the ``SearchableSelect`` "Select all N" failure §2.1's E2 names, reached
    here from the query-string side rather than the picker side, and it is precisely why
    ``admitRole IS NULL`` was given a NINTH, reserved token (``"default"``) instead of being left to
    fall out of "tick everything". A picker wired wrong would make the two requests below answer
    identically; this fixture is built so that a fourth row — ``status-pending``, admitted at the
    platform default — exists ONLY to be the row that tells the two requests apart.

    Built from ``ROLE_RANK`` rather than the eight tiers typed out by hand a second time, so this
    test cannot go quietly stale the day a ninth tier lands — the exact drift ``deps.py``'s own long
    comment above ``ROLE_RANK`` warns has already happened twice in this repository's prose, one
    file further out here.
    """
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


# --------------------------------------------------------------------------------------
# 4. `dateField` / `dateFrom` / `dateTo` — one range, on one of the five columns
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("token,row_slug", DATE_FIELD_TOKENS)
async def test_each_date_field_filters_on_its_own_column(world, client, token, row_slug):
    """``dateField=<token>`` narrows to the ONE ``AccessRoster`` column DROPDOWN_DESIGN §4.1 maps it
    to and to no other. A tight range around one row's stamped instant returns exactly that row
    among this fixture's five date-marked rows — proving ``ACCESS_DATE_COLUMNS`` picks the column
    its own name promises rather than, say, always filtering ``createdAt`` regardless of the token,
    which every OTHER row here would also satisfy at its own (very different, real "now") creation
    time if the mapping were wrong."""
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
    """An unrecognised ``dateField`` is a 422 naming the five real tokens, never the ``KeyError``
    (turned into a bare 500) that a raw ``ACCESS_DATE_COLUMNS[dateField]`` subscript with no check
    in front of it would produce. ``"revoked"`` is deliberately a REAL ``dateField`` token on the
    OTHER roster (``GET /designers/roster``) — the plausible mistake of reusing that route's
    vocabulary here, not a nonsense string nobody would ever type."""
    response = client.get(
        "/api/access/roster", params={"dateField": "revoked"}, headers=_headers(world)
    )
    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    for token in ("added", "decided", "firstSeen", "joined", "requested"):
        assert token in detail, detail


# --------------------------------------------------------------------------------------
# 5. `sort` / `dir` — actually reorders the page, and defaults per column
# --------------------------------------------------------------------------------------


async def test_sort_by_email_orders_the_page_and_defaults_to_ascending(world, client):
    """``sort=email`` with no ``dir`` uses ``ACCESS_SORT_DEFAULT_DIR``'s ``asc`` for it — there is
    no "newest" reading of an address — and actually reorders the page rather than being accepted
    and ignored. Explicit ``dir=desc`` reverses it, proving the direction is read and not merely
    validated."""
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


# --------------------------------------------------------------------------------------
# 6. The stable secondary sort — a regression test for the move off `asyncio.gather`
# --------------------------------------------------------------------------------------


async def test_two_rows_sharing_a_sort_key_are_neither_repeated_nor_skipped_across_pages(
    world, client
):
    """Two rows share the exact same ``createdAt`` on purpose. This is not a contrived edge case on
    this table: the migration that grandfathered the pre-existing allow-list did the identical thing
    to several hundred real rows in one ``CURRENT_TIMESTAMP`` statement. Without a total order,
    Postgres is free to break the tie differently between the request for page 1 and the request for
    page 2, and a row that changes side of the cut is handed over twice or never handed over at all
    — SILENTLY, because each page still has the right SIZE either way.

    Walked with ``pageSize=1`` under the route's default ``sort=added``, this proves
    ``count_and_page``'s tiebreak survived the move off the hand-rolled ``asyncio.gather`` this
    route used to run its own copy of the same fix through. The specific id that wins the tie is
    NOT asserted — that is a detail of Postgres's collation on this column, not of the contract —
    only that the two pages are the two DIFFERENT rows and nothing else (no repeat, no skip), and
    that asking for page 1 twice lands on the same row both times (FIXED, not merely correct once).
    """
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
