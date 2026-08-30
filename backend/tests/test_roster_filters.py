"""``GET /designers/roster``'s requirement-30 filter and sort grammar, and the institutions
endpoint that feeds its institution picker — DROPDOWN_DESIGN section 4, the designer roster's
half. ``GET /access/roster`` carries the access-roster half and is not this module's concern.

Four things are pinned here, each one DROPDOWN_DESIGN §4.6 states as a binding rule and each one a
way this screen would otherwise lie to the admin reading it:

**(i) EMPTY MEANS EVERYTHING, BY ABSENCE.** No ``roles`` at all returns every standing tier
including the reserved ``never-signed-in`` row; naming a tier narrows to exactly that tier.

**(ii) SUSPENDED ROWS ARE LISTED BY DEFAULT.** An admin opens this screen holding a message from
somebody who cannot sign in, and the row that explains why must be visible without asking for it.

**(iii) ANY CAP OR TRUNCATION IS STATED ON THE WIRE, WITH THE NUMBER.** The role filter's own
account read can be cut, and so can the institution vocabulary's read — both say so rather than
silently shortening the answer, and the role cut is loud enough to reach the log at ERROR, because
it is a filter silently missing people rather than a merely long list.

**(iv) FILTERING HAPPENS IN THE QUERY, NEVER IN PYTHON AFTER THE FETCH.** Every test below reads
the ``where`` this route builds only indirectly, through the rows Postgres actually returns — the
same discipline ``services/designers.py`` names for the directory's own cap: a post-take drop
reports a page that is the right SIZE while quietly missing whoever the extra clause excluded.

Postgres is required — every assertion below is about rows a live database actually returns for a
given ``where`` and ``order``, not about Python parsing in isolation (``enum_filter_list_or_422``'s
own parsing rules are pinned in isolation by ``tests/test_record_filters.py`` and are not repeated
here). The module skips itself when ``DATABASE_URL`` does not point at a local database, exactly as
``test_designer_roster.py`` does.

    docker compose up -d postgres minio
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

EVERY ROW THIS MODULE CREATES CARRIES A PER-RUN STAMP, in its email and usually its ``fullName`` or
``institution`` too, and every list assertion narrows through ``?search=<stamp>`` (or a stamped
``fullName`` substring, for the stable-sort test) rather than reading the whole table. This is a
SHARED development database several agents may be writing to at once — DesignerRoster.email is
unique, so fixed addresses would collide across runs, and an unscoped row count would be wrong the
moment any other test's fixture data is sitting in the same table.
"""

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
    """One admin, three linked accounts of distinct roles, and five roster rows exercising every
    corner of §4's grammar: two standings, a shared-institution pair, a null institution, a blank
    (not null) institution written directly to prove the endpoint excludes it defensively, a
    never-signed-in row, and a three-way tie on ``createdAt`` for the stable-sort walk.

    Built exactly once per module run, against the shared Prisma client, for
    ``test_designer_roster.py``'s stated reason: that client is bound to the ``TestClient``'s event
    loop, and touching it from a test's own loop fails intermittently rather than honestly.
    """
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

        # THE LIVE COUNT OF EVERY ``INSPECTOR`` ACCOUNT IN THE DATABASE, TAKEN HERE AND NOT INSIDE A
        # TEST BODY. `test_role_match_at_exactly_the_cap_is_not_truncated_and_still_finds_the_row`
        # needs to monkeypatch `ROLE_MATCH_READ_LIMIT` to a value EXACTLY equal to how many accounts
        # the role query will actually find, to construct the one boundary the truncation test above
        # cannot reach (it only proves the cap is honoured once CROSSED). A literal `1` there would
        # assume this fixture's own inspector is the only such account in a database this module's
        # own docstring calls SHARED — wrong the moment another agent's fixture also holds the role.
        # Counting it live, right here, is the fix: it is exact regardless of what else exists.
        #
        # TAKEN ON THIS CONNECTION, FOR THE SAME REASON AS THE CAP-PROBE ACCOUNTS BELOW. A raw
        # `await db.…` from inside an `@pytest.mark.anyio` test body runs on that test's OWN fresh
        # event loop rather than the one this fixture's shared Prisma client is bound to — the
        # cap-probe comment below documents the `RuntimeError` that produces for a write; for a read
        # awaited through the SAME shared client it was observed here as an `httpx.WriteTimeout`
        # instead (the request is issued against a transport registered to a loop that is not the
        # one running it, so it hangs until the client's own timeout fires rather than failing
        # immediately) — a slower failure hiding the same cause. Measuring it here, on the fixture's
        # own loop, and handing the number to the test rather than the counting call itself is what
        # keeps the test on the safe side of that trap.
        inspector_role_count = await db.user.count(where={"role": "INSPECTOR"})

        # Three accounts of a role nothing else in this fixture uses, existing ONLY so
        # `test_role_match_truncation_is_reported_on_the_wire_and_logged_at_error` can monkeypatch
        # `ROLE_MATCH_READ_LIMIT` down to 2 and cross it. Created here, in the fixture's own already-
        # connected setup, rather than inside that test's body: the shared Prisma client is bound to
        # THIS event loop, established before `TestClient(app)` starts, and a raw `await db.…create`
        # from inside an `@pytest.mark.anyio` test function runs on that test's OWN fresh loop —
        # `RuntimeError: … is bound to a different event loop`, discovered by running this exact
        # test. No `DesignerRoster` row points at any of them, so they are invisible to every other
        # test in this module; they exist purely to be counted by the role filter's first query.
        for i in range(3):
            await db.user.create(data={
                "email": email(f"cap-probe-{i}"),
                "name": f"Roster Filters Cap Probe {i} {stamp}",
                "role": "FIELD_CONTRIBUTOR",
            })

        # The tie group: three ACTIVE rows sharing one explicit `createdAt`, so the default
        # `sort=added` order has a real tie to break — not a hoped-for one. Tagged with `tie_tag` in
        # `fullName` so the stable-sort test can isolate exactly these three via `search`, regardless
        # of how many other stamped rows this fixture also creates.
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
        # No matching `User` row at all — an admin empanelled this address and it has never signed
        # up. `firstSeenAt` stays NULL, which is the `never-signed-in` reserved token's whole basis.
        rows["neverSignedIn"] = await db.designerroster.create(data={
            "email": email("never-signed-in"),
            "fullName": tie_tag,
            "institution": None,
            "isActive": True,
            "firstSeenAt": None,
            "createdAt": tied_at,
            "addedById": people["admin"].id,
        })

        # NOT in the tie group (a distinct `createdAt`), NOT active, and it shares `Inst-A-{stamp}`
        # with the inspector row above — the row rule (ii) exists for, and the row that proves the
        # institution filter is not silently narrowed to active rows only.
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

        # `institution` written as a literal empty string, direct to the database — bypassing this
        # route's own `_clean`, which never produces one — so the institutions endpoint's exclusion
        # is proven at the query, not merely inferred from "the app never writes one".
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
    """This run's roster rows, narrowed by ``?search=<stamp>`` unless the caller overrides it."""
    params.setdefault("search", world["stamp"])
    params.setdefault("pageSize", 100)
    return _get(client, world, ROSTER, **params)


def _emails(payload: dict[str, Any]) -> set[str]:
    return {row["email"] for row in payload["items"]}


# --------------------------------------------------------------------------------------
# 1. `activeOnly` and `standing` — kept, added, and refused when they disagree
# --------------------------------------------------------------------------------------


async def test_standing_and_activeOnly_disagreement_is_a_422(world, client):
    """DROPDOWN_DESIGN §4.1: a deliberate 422, not a silent pick-one."""
    response = client.get(
        ROSTER,
        params={"activeOnly": "true", "standing": "suspended"},
        headers=_headers(world),
    )
    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert "activeOnly" in detail and "suspended" in detail


async def test_standing_and_activeOnly_agreement_is_not_refused(world, client):
    """The two ask the same question when they agree, and agreeing is not an error."""
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
    """``activeOnly`` alone, with no ``standing`` at all, is unchanged: the suspended row drops out."""
    payload = _roster(client, world, activeOnly="true")
    emails = _emails(payload)
    assert world["people"]["inspector"].email in emails
    assert world["rows"]["suspended"].email not in emails


# --------------------------------------------------------------------------------------
# 2. Rule (ii): suspended rows are listed BY DEFAULT
# --------------------------------------------------------------------------------------


async def test_default_designer_roster_lists_suspended(world, client):
    payload = _roster(client, world)
    emails = _emails(payload)
    assert world["rows"]["suspended"].email in emails, (
        "an admin opens this screen holding a message from somebody who cannot sign in; the row "
        "that explains why must be visible without asking for it"
    )
    # And every other stamped row is there too — this is "nothing was narrowed", not "suspended
    # rows happen to survive some other filter".
    assert emails == {
        world["people"]["inspector"].email,
        world["people"]["professor"].email,
        world["rows"]["neverSignedIn"].email,
        world["rows"]["suspended"].email,
        world["rows"]["blankInstitution"].email,
    }


# --------------------------------------------------------------------------------------
# 3. Rule (i): `roles` — absent means every tier, and the two-query shape narrows correctly
# --------------------------------------------------------------------------------------


async def test_absent_roles_returns_every_tier_including_never_signed_in(world, client):
    payload = _roster(client, world)
    assert world["rows"]["neverSignedIn"].email in _emails(payload)


async def test_roles_filter_matches_by_the_linked_accounts_role_not_a_roster_column(world, client):
    """`DesignerRoster` has no role column at all — this is the two-query shape from the ground up:
    which accounts hold the requested role, folded into which roster rows carry that email."""
    only_inspector = _roster(client, world, roles="INSPECTOR")
    assert _emails(only_inspector) == {world["people"]["inspector"].email}

    both = _roster(client, world, roles="INSPECTOR,PROFESSOR")
    assert _emails(both) == {world["people"]["inspector"].email, world["people"]["professor"].email}

    # Repeated-parameter spelling is the same request as the comma-joined one (DROPDOWN_DESIGN
    # §4.1, "two spellings, both accepted").
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
    """A fixture past ``ROLE_MATCH_READ_LIMIT`` sets ``roleMatchTruncated`` and logs at ERROR — the
    cap is driven to a small number rather than by writing fifty thousand accounts, the same trade
    ``test_the_directory_cap_is_spent_on_designers_the_roster_still_admits`` makes in
    ``test_designer_roster.py``. The three matching accounts themselves are ``world``'s "cap probe"
    rows — see the fixture for why they are created there and not here.
    """
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
    """The boundary the truncation test above cannot reach. ``take = ROLE_MATCH_READ_LIMIT + 1``
    means a matching-account count of EXACTLY the cap must read all of it and report
    ``roleMatchTruncated: False`` honestly — ``len(accounts) > ROLE_MATCH_READ_LIMIT`` is
    ``cap > cap``, which is ``False`` — never a false positive that tells an admin their filter is
    unreliable when it is not, and never a false negative that silently drops the one row this
    exact count was sized for.

    THE CAP COMES FROM ``world["inspector_role_count"]``, MEASURED ONCE IN THE FIXTURE, NOT FROM A
    LIVE QUERY HERE. See that fixture's comment for why: this module's own docstring says the
    database is SHARED and other agents may be writing to it concurrently, so a hard-coded cap next
    to a hard-coded "there is exactly one INSPECTOR account" would be exactly the kind of assumption
    that makes a test flaky the day something else in the same database also holds that role — and a
    live ``await db.user.count(...)`` called from THIS function body would run on pytest-anyio's own
    fresh event loop rather than the one the shared ``db`` client is bound to, which is the same
    cross-loop trap the fixture's cap-probe comment documents for a write and which surfaces here,
    for a read, as an ``httpx.WriteTimeout`` instead of a clean ``RuntimeError`` — confirmed by
    actually hitting it before this test was rewritten to take the count from the fixture instead.
    """
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


# --------------------------------------------------------------------------------------
# 4. Institution: exact match, the reserved `none` token, and the vocabulary endpoint
# --------------------------------------------------------------------------------------


async def test_institution_filter_is_an_exact_match_across_both_standings(world, client):
    """``Inst-A-{stamp}`` is shared by an ACTIVE row and a SUSPENDED one — proving the institution
    filter narrows independently of standing rather than silently implying `standing=active`."""
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

    # Driven far above anything this database could hold, so this test's assertions cannot be made
    # flaky by the institution-vocabulary truncation the next test deliberately provokes.
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
    """Gated identically to the list itself — same names, same audience.

    The fixture's INSPECTOR account is the probe: INSPECTOR outranks DESIGNER on the rank ladder
    but is nowhere near ``can_manage_designer_roster``'s ADMIN-and-above floor, so this is also a
    check that the gate is the SET it claims to be rather than a rank threshold that happens to
    exclude ordinary designers today.
    """
    response = client.get(INSTITUTIONS, headers=_headers(world, "inspector"))
    assert response.status_code == 403, response.text


# --------------------------------------------------------------------------------------
# 5. Sort: stable across a paged walk over a shared sort key
# --------------------------------------------------------------------------------------


async def test_stable_sort_holds_across_a_paged_walk_over_a_shared_sort_key_value(world, client):
    """Three rows share one explicit ``createdAt`` — a real tie, not a hoped-for one. Without the
    ``id`` tiebreak ``count_and_page`` applies, Postgres is free to break that tie differently on
    each of these three requests, and a one-row-per-page walk would be the shape most likely to
    reveal it: a repeated row, a skipped one, or both.
    """
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


# --------------------------------------------------------------------------------------
# 6. Date range: one column, over the three this roster actually has
# --------------------------------------------------------------------------------------


async def test_dateField_firstSeen_range_excludes_the_never_signed_in_row(world, client):
    """A range on `firstSeenAt` can only ever include rows that HAVE one — `firstSeenAt IS NULL`
    fails every ``gte``/``lte`` comparison in SQL's three-valued logic, so this is also a check
    that the never-signed-in row is not silently swept in by a wide-open range."""
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
