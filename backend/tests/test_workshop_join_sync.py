"""Unlinking a record from a workshop has to reach BOTH links, not just the column.

An artisan is attached to a workshop two ways at once: the ``Artisan.workshopId`` column, and the
``WorkshopArtisan`` join row that ``link_workshop_artisan`` mirrors it into so that every query
written against the roster keeps working. Filing an artisan writes both. DETACHING wrote only the
column, and the mirror was then called with the NEW value — a null — which it returns on
immediately, because its contract is "never removes a link".

So the unlink was a lie of exactly the kind ``records.CLEARABLE_KEYS`` was added to end one layer
up: HTTP 200, the artisan's own page redraws as unlinked, and every workshop-scoped list, export
and count still holds them, because those read the join. Re-filing the artisan under a second
workshop then added a row without removing the first, and one artisan was counted in two workshops.

WHY THE ASSERTIONS ARE MADE THROUGH ``GET /artisans?workshopId=``. That filter ORs the column with
the join, so an artisan that has left a workshop is absent from it ONLY IF BOTH links are gone. An
assertion on the column alone would have passed against the broken code — the column half always
worked — and an assertion on the join table alone would not prove the failure a user actually hits.
This is the researcher's screen, asked the researcher's question.

CRAFTS ARE HERE FOR THE SAME DEFECT, AND THEY ARE NOT A COPY FOR SYMMETRY'S SAKE. The audit finding
these tests close names ``PATCH /crafts`` alongside ``PATCH /artisans``, and the craft half stayed
broken for a whole round of fixes after the artisan half was closed: ``sync_workshop_craft`` was
written in ``services/workshop_access.py`` — its docstring even explaining that the one-line swap in
``api/routes/crafts.py`` was owned by another change in flight — and that swap never happened, so the
helper sat in the tree with ZERO callers while the route still called the add-only
``link_workshop_craft``. A test module that covered only artisans is exactly what let that pass for
done. It bites harder for crafts, too: ``craft_workshop_clause``'s docstring records that on the live
repository every craft predates the ``workshopId`` column, so the ``WorkshopCraft`` join is the ONLY
link most crafts have — a stale join row there is not a second opinion, it is the whole answer, and
since ``bucket_workshop_clause`` made that clause the single reading for the crafts table it is now
the answer on search, the map, the completion matrix and the dataset export simultaneously.

Postgres is required: the defect is a row in a join table surviving a request, so the module skips
itself when ``DATABASE_URL`` does not point at a local database, exactly as
``test_design_workshop_viewers`` does.

    docker compose up -d postgres minio          # from the REPOSITORY ROOT, not from backend/
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

import asyncio
import os
import uuid
from datetime import UTC, datetime
from typing import Any

import pytest

from app.core.db import db
from app.core.security import create_access_token, hash_password

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
]

#: One artisan (and one craft) per test, because each of them edits the record it is given and a
#: shared one would make the module order-dependent — a suite that passes alone and fails in CI.
CASES: tuple[str, ...] = ("control", "unlink", "aim", "refile", "untouched")


@pytest.fixture(scope="module")
def world():
    """An admin, two workshops, five artisans and five crafts filed at the first, plus two
    roster-only bystanders (one artisan, one craft).

    Every row is created before the app starts, for the reason ``test_design_workshop_viewers``
    records: the Prisma client is shared with the running app and bound to the TestClient's event
    loop, and touching it from a test's own loop is the kind of cross-loop use that fails
    intermittently rather than honestly.

    THE ARTISANS ARE SEEDED THE WAY ``POST /artisans`` LEAVES THEM — the explicit column AND the
    mirrored join row — rather than through the API, because the create payload demands a valid
    Aadhaar, a craft and a location, none of which this module is about. What is under test is the
    PATCH, and it must find exactly the two links a real create leaves behind.

    ``bystander`` reaches workshop A through a ``WorkshopArtisan`` row and NOTHING else — no column
    — which is the shape ``replace_workshop_artisans`` writes for every artisan picked in the
    workshop form. It is here as the control on the delete's aim: the fix removes one exact
    ``(workshop, artisan)`` pair, so a roster row belonging to a different artisan must survive an
    unlink. Without it, "delete every row for this workshop" would pass this module too.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    tag = uuid.uuid4().hex[:8]

    async def seed() -> dict[str, Any]:
        await db.connect()
        try:
            admin = await db.user.create(data={
                "email": f"join-sync-{tag}@example.org",
                "name": "Join Sync Admin",
                "role": "ADMIN",
                "passwordHash": hash_password("unused"),
            })
            workshop_a = await db.workshop.create(data={
                "title": f"Bagru block printing {tag}",
                "date": datetime(2026, 3, 1, tzinfo=UTC),
                "place": "Bagru",
                "createdById": admin.id,
            })
            workshop_b = await db.workshop.create(data={
                "title": f"Sanganer block printing {tag}",
                "date": datetime(2026, 4, 1, tzinfo=UTC),
                "place": "Sanganer",
                "createdById": admin.id,
            })
            artisans: dict[str, str] = {}
            for case in CASES:
                row = await db.artisan.create(data={
                    "name": f"Sita Devi {case} {tag}",
                    "place": "Bagru",
                    "createdById": admin.id,
                    "workshopId": workshop_a.id,
                })
                await db.workshopartisan.create(
                    data={"workshopId": workshop_a.id, "artisanId": row.id}
                )
                artisans[case] = row.id
            bystander = await db.artisan.create(data={
                "name": f"Roster Only Bystander {tag}",
                "place": "Bagru",
                "createdById": admin.id,
            })
            await db.workshopartisan.create(
                data={"workshopId": workshop_a.id, "artisanId": bystander.id}
            )
            # The craft twin of the five artisans above, seeded the way ``POST /crafts`` leaves a
            # craft: the explicit column AND the mirrored ``WorkshopCraft`` row. ``Craft.name`` is
            # @unique across the whole table, so the per-run ``tag`` is load-bearing here rather
            # than cosmetic — without it a second run of this module 409s on the seed.
            crafts: dict[str, str] = {}
            for case in CASES:
                row = await db.craft.create(data={
                    "name": f"Bandhani {case} {tag}",
                    "place": "Bagru",
                    "createdById": admin.id,
                    "workshopId": workshop_a.id,
                })
                await db.workshopcraft.create(
                    data={"workshopId": workshop_a.id, "craftId": row.id}
                )
                crafts[case] = row.id
            # THE SHAPE THE LIVE REPOSITORY IS FULL OF, and the one that makes the crafts half of
            # this defect worse than the artisans half: a join row and NO column, which is both what
            # the workshop form's "Crafts covered" picker writes (``replace_workshop_crafts``) and
            # what every craft recorded before the column existed looks like. It doubles as the
            # control on the delete's aim — the fix must remove one exact ``(workshop, craft)`` pair,
            # so a different craft's join row has to survive somebody else's unlink.
            craft_bystander = await db.craft.create(data={
                "name": f"Roster Only Craft {tag}",
                "place": "Bagru",
                "createdById": admin.id,
            })
            await db.workshopcraft.create(
                data={"workshopId": workshop_a.id, "craftId": craft_bystander.id}
            )
            return {
                "admin": admin,
                "workshop_a": workshop_a.id,
                "workshop_b": workshop_b.id,
                "artisans": artisans,
                "bystander": bystander.id,
                "crafts": crafts,
                "craft_bystander": craft_bystander.id,
                "tag": tag,
            }
        finally:
            await db.disconnect()

    seeded = asyncio.run(seed())
    with TestClient(app) as client:
        seeded["client"] = client
        yield seeded


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any]) -> dict[str, str]:
    """A bearer token for the fixture's admin.

    Minted rather than obtained by signing in: what is under test is a join row, and routing every
    test through the login gate would make them fail for reasons that have nothing to do with it.
    """
    return {"Authorization": f"Bearer {create_access_token(world['admin'].id)}"}


def _craft_ids_in(world: dict[str, Any], workshop_id: str) -> set[str]:
    """Who ``GET /crafts?workshopId=`` says is in this workshop — column OR ``WorkshopCraft`` row.

    The craft twin of ``_artisan_ids_in``, and asserted through for the same reason: that route's
    ``where`` ORs ``Craft.workshopId`` with ``{"workshops": {"some": …}}``, so a craft that has left
    a workshop disappears from this answer ONLY IF BOTH links are gone. Reading the join table
    directly would prove less — the researcher never looks at a join table — and reading the column
    alone would have passed against the broken route, because the column half always worked.
    """
    response = world["client"].get(
        f"/api/crafts?workshopId={workshop_id}&pageSize=100", headers=_headers(world)
    )
    assert response.status_code == 200, response.text
    return {row["id"] for row in response.json()["items"]}


def _artisan_ids_in(world: dict[str, Any], workshop_id: str) -> set[str]:
    """Who ``GET /artisans?workshopId=`` says is in this workshop — column OR roster row."""
    response = world["client"].get(
        f"/api/artisans?workshopId={workshop_id}&pageSize=100", headers=_headers(world)
    )
    assert response.status_code == 200, response.text
    return {row["id"] for row in response.json()["items"]}


def test_a_filed_artisan_is_in_the_workshop_by_both_readings(world, client):
    """The control, which passes against the old code too — that is what makes it worth keeping.

    It pins that the fixture really does leave both links behind, so a later reading of the tests
    below cannot mistake "the unlink works" for "the link was never there in the first place".
    """
    assert world["artisans"]["control"] in _artisan_ids_in(world, world["workshop_a"])


def test_unlinking_removes_the_artisan_from_the_workshop_and_not_only_from_the_column(world, client):
    """THE REGRESSION. Clearing the column left the mirrored roster row standing.

    A designer files an artisan against the wrong workshop, re-opens the record, picks the blank
    'no workshop' option and saves. Before the fix the response was 200 with a null column and the
    artisan was still returned by this query, so the artisan's own page and every workshop-scoped
    list disagreed with no way to tell which was right.
    """
    artisan_id = world["artisans"]["unlink"]
    assert artisan_id in _artisan_ids_in(world, world["workshop_a"])

    unlink = client.patch(
        f"/api/artisans/{artisan_id}", json={"workshopId": None}, headers=_headers(world)
    )
    assert unlink.status_code == 200, unlink.text
    assert unlink.json()["workshopId"] is None

    assert artisan_id not in _artisan_ids_in(world, world["workshop_a"])


def test_an_unlink_leaves_another_artisans_roster_row_alone(world, client):
    """The delete is aimed at ONE ``(workshop, artisan)`` pair, not at the workshop's roster.

    The bystander reaches workshop A through a roster row and no column at all — the shape the
    workshop form's "linked artisans" picker writes — and a fix that cleared the WORKSHOP's rows
    instead of the RECORD's would silently empty an admin-curated roster on somebody else's edit.
    """
    client.patch(
        f"/api/artisans/{world['artisans']['aim']}",
        json={"workshopId": None},
        headers=_headers(world),
    )
    assert world["bystander"] in _artisan_ids_in(world, world["workshop_a"])


def test_refiling_an_artisan_moves_them_rather_than_counting_them_twice(world, client):
    """Re-filing added a second join row and kept the first, so one artisan sat in two workshops.

    That is the half that corrupts a COUNT rather than a list: neither screen is empty, both are
    plausible, and the workshop the artisan was moved out of quietly keeps them for ever.
    """
    artisan_id = world["artisans"]["refile"]
    moved = client.patch(
        f"/api/artisans/{artisan_id}",
        json={"workshopId": world["workshop_b"]},
        headers=_headers(world),
    )
    assert moved.status_code == 200, moved.text

    assert artisan_id in _artisan_ids_in(world, world["workshop_b"])
    assert artisan_id not in _artisan_ids_in(world, world["workshop_a"])


def test_an_edit_that_never_mentions_the_workshop_keeps_the_link(world, client):
    """A PATCH of an unrelated field arrives with previous == next and must touch nothing.

    The whole fix hangs on comparing the stored workshop with the new one, so the case where they
    are equal is the one that would quietly detach every artisan in the repository if the
    comparison were ever dropped in favour of "always delete, then re-add".
    """
    artisan_id = world["artisans"]["untouched"]
    renamed = client.patch(
        f"/api/artisans/{artisan_id}", json={"place": "Chhipa Mohalla"}, headers=_headers(world)
    )
    assert renamed.status_code == 200, renamed.text
    assert artisan_id in _artisan_ids_in(world, world["workshop_a"])


# --- Crafts: the same defect on the other route -----------------------------------------------
#
# These are not artisan tests with the nouns swapped. ``PATCH /crafts`` kept calling the add-only
# ``link_workshop_craft`` for a full round of fixes AFTER ``sync_workshop_craft`` had been written
# for it, because nothing in the suite asked the question of this route. That is the gap these five
# close, and it is why every one of them goes through the HTTP route rather than calling the helper:
# a unit test of ``sync_workshop_craft`` passes whether or not anybody calls it.


def test_a_filed_craft_is_in_the_workshop_by_both_readings(world, client):
    """The control, which passes against the old code too — that is what makes it worth keeping.

    It pins that the fixture really does leave both links behind, so a later reading of the tests
    below cannot mistake "the unlink works" for "the link was never there in the first place".
    """
    assert world["crafts"]["control"] in _craft_ids_in(world, world["workshop_a"])


def test_unlinking_a_craft_removes_it_from_the_workshop_and_not_only_from_the_column(world, client):
    """THE REGRESSION, craft side. Clearing the column left the mirrored ``WorkshopCraft`` row.

    Against the pre-fix route this assertion FAILS, and by construction rather than by hope:
    ``workshopId`` is in ``records.CLEARABLE_KEYS``, so ``clean_data`` keeps the explicit null and
    ``db.craft.update`` really does clear the column — the response body below is null either way.
    The route then handed that same null to ``link_workshop_craft``, whose first statement is
    ``if not workshop_id: return``. Nothing else in the request touches ``WorkshopCraft``, so the
    seeded join row survived, and ``GET /crafts?workshopId=`` — which ORs the column with the join —
    kept returning this craft. The final assertion is therefore the one line that separates a real
    unlink from a 200 that lied, and it is the line the old code could not satisfy.
    """
    craft_id = world["crafts"]["unlink"]
    assert craft_id in _craft_ids_in(world, world["workshop_a"])

    unlink = client.patch(
        f"/api/crafts/{craft_id}", json={"workshopId": None}, headers=_headers(world)
    )
    assert unlink.status_code == 200, unlink.text
    # The column half — true before the fix as well. Asserted so a failure below is unambiguous:
    # if this passes and the next one fails, the join row is what survived.
    assert unlink.json()["workshopId"] is None

    assert craft_id not in _craft_ids_in(world, world["workshop_a"])


def test_an_unlink_leaves_another_crafts_join_row_alone(world, client):
    """The delete is aimed at ONE ``(workshop, craft)`` pair, not at the workshop's craft list.

    ``craft_bystander`` reaches workshop A through a ``WorkshopCraft`` row and no column at all —
    the shape the workshop form's "Crafts covered" picker writes, and the shape every craft on the
    live repository already has. A fix that cleared the WORKSHOP's rows rather than the RECORD's
    would empty a curated craft list on somebody else's edit, and would still pass the test above.
    """
    client.patch(
        f"/api/crafts/{world['crafts']['aim']}",
        json={"workshopId": None},
        headers=_headers(world),
    )
    assert world["craft_bystander"] in _craft_ids_in(world, world["workshop_a"])


def test_refiling_a_craft_moves_it_rather_than_counting_it_twice(world, client):
    """Re-filing added a second join row and kept the first, so one craft sat in two workshops.

    The half that corrupts a COUNT rather than a list: neither workshop's craft list is empty, both
    are plausible, and the workshop the craft was moved out of quietly keeps it for ever. The
    ``not in workshop_a`` assertion is the one the old route failed — the ``in workshop_b`` half
    passed before the fix too, because adding a link was never the broken direction.
    """
    craft_id = world["crafts"]["refile"]
    moved = client.patch(
        f"/api/crafts/{craft_id}",
        json={"workshopId": world["workshop_b"]},
        headers=_headers(world),
    )
    assert moved.status_code == 200, moved.text

    assert craft_id in _craft_ids_in(world, world["workshop_b"])
    assert craft_id not in _craft_ids_in(world, world["workshop_a"])


def test_an_edit_that_never_mentions_the_crafts_workshop_keeps_the_link(world, client):
    """A PATCH of an unrelated field arrives with previous == next and must touch nothing.

    The whole fix hangs on capturing ``craft.workshopId`` BEFORE the update and comparing it with
    the new one, so the equal case is what would quietly detach every craft in the repository if the
    comparison were ever dropped in favour of "always delete, then re-add" — and, because the join
    is the only link most live crafts have, that would empty the craft side of every workshop.
    """
    craft_id = world["crafts"]["untouched"]
    renamed = client.patch(
        f"/api/crafts/{craft_id}", json={"place": "Chhipa Mohalla"}, headers=_headers(world)
    )
    assert renamed.status_code == 200, renamed.text
    assert craft_id in _craft_ids_in(world, world["workshop_a"])
