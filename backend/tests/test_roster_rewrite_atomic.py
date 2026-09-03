"""A workshop's roster is rewritten whole, or not at all.

``replace_workshop_artisans`` and ``replace_workshop_crafts`` rewrite a roster as a ``delete_many``
followed by a ``create_many`` — two statements instead of forty-one, which is why they exist and is
a good reason. Until 2026-09-03 they were also two SEPARATE COMMITS, and ``update_workshop`` ran
both of them back to back after the workshop's own update: FOUR commits for one save, with a failure
window between every adjacent pair.

WHAT WALKED THROUGH THAT WINDOW, and it is a data-loss bug rather than a tidiness one:

* A failure between the delete and the create committed THE WIPE AND NOTHING ELSE. Forty artisans an
  administrator had picked were gone; the save reported an error, so the one person who knew what
  the roster held believed nothing had been saved at all. The join rows are the only record of a
  roster, so there is nothing to reconstruct it from — and the reflex that has hidden this for so
  long is that the form is usually still on screen, so the retry puts it back.
* Two administrators saving one workshop at once interleaved into a 500 on
  ``@@unique([workshopId, artisanId])``: A deletes, B deletes, A inserts, B inserts. Unlike
  ``data_access._upsert_grant``, which meets the same shape, this insert has no ``skip_duplicates``,
  so the loser got "Something went wrong on the server" for a save that was merely concurrent.
* And the workshop's own row, its audit row and the two rosters could each land without the others,
  so a save could leave the new dates beside the old roster, or a wiped artisan roster beside an
  intact craft one.

HOW THE ROLLBACK IS OBSERVED. A transaction that works and a transaction that is not there look
identical whenever nothing fails, so each test below breaks ONE statement in the middle of the
rewrite and asserts that the rest of the save did not survive it. The break is applied at the PRISMA
ACTION CLASS and not on the module-level ``db``, which is the whole trick: ``db.tx()`` hands back a
DIFFERENT client, so a patch on ``db`` would not be inside the transaction and these tests would
measure nothing.

THE LAST TEST IS A SOURCE-LEVEL GUARD AND IT IS NOT PADDING. The way this fix comes undone is not a
reverted line — it is somebody adding a statement to one of the helpers that writes through the
module singleton while sitting inside the ``async with``. That failure has NO symptom: the code
reads as though it is in the transaction and commits outside it. A behavioural test cannot see it;
reading the function can.

These need Postgres: what is under test is which rows survive a failed request. The module skips
itself through the shared gate when ``DATABASE_URL`` does not point at a local database.

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

import inspect
import re
import uuid
from datetime import UTC, datetime

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password

pytestmark = [needs_db, pytest.mark.anyio]


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def env():
    """A professor who owns the workshop, three artisans and three crafts to put on it.

    THE PROFESSOR IS THE WORKSHOP'S CREATOR on purpose. ``update_workshop`` gates on
    ``resolve_workshop_access`` at CONTRIBUTE and exempts the creator, so ownership keeps every
    assertion below about the transaction rather than about the workshop-access ladder — which has
    its own files.

    The workshop, artisans and crafts are inserted directly rather than posted, because
    ``WorkshopCreate`` and ``ArtisanCreate`` both demand a location (and the artisan an Aadhaar),
    and none of that plumbing changes what a half-committed roster rewrite looks like. Rows are
    created here rather than inside a test because the Prisma client is shared with the running app
    and bound to the TestClient's event loop; touching it from a test's own loop is the kind of
    cross-loop use that fails intermittently rather than honestly.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        professor = await db.user.create(
            data={
                "email": f"roster-prof-{stamp}@example.org",
                "name": "Roster Professor",
                "role": "PROFESSOR",
                "passwordHash": hash_password("unused"),
            }
        )
        workshop = await db.workshop.create(
            data={
                "title": f"Roster workshop {stamp}",
                "date": datetime.now(UTC),
                "place": "Bagru",
                "createdById": professor.id,
            }
        )
        artisans = [
            (
                await db.artisan.create(
                    data={
                        "name": f"Roster Artisan {index} {stamp}",
                        "place": "Bagru",
                        "createdById": professor.id,
                    }
                )
            ).id
            for index in range(3)
        ]
        crafts = [
            (
                await db.craft.create(
                    data={
                        "name": f"Roster Craft {index} {stamp}",
                        "createdById": professor.id,
                    }
                )
            ).id
            for index in range(3)
        ]
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "prof": {"Authorization": f"Bearer {create_access_token(subject=professor.id)}"},
            "workshop_id": workshop.id,
            "artisans": artisans,
            "crafts": crafts,
        }


def _save(env, body: dict):
    return env["client"].patch(
        f"/api/workshops/{env['workshop_id']}", json=body, headers=env["prof"]
    )


def _workshop(env) -> dict:
    response = env["client"].get(f"/api/workshops/{env['workshop_id']}", headers=env["prof"])
    assert response.status_code == 200, response.text
    return response.json()


def _roster(env) -> set[str]:
    """The artisan ids the workshop's own page reports, read through the route a client reads."""
    return {link["artisanId"] for link in _workshop(env).get("artisans") or []}


def _craft_roster(env) -> set[str]:
    return {link["craftId"] for link in _workshop(env).get("crafts") or []}


@pytest.fixture
def full_roster(env):
    """Put all three artisans and all three crafts on the workshop, and prove they are there.

    Every test below starts from a POPULATED roster, because the defect is that a rewrite DESTROYS
    what is already there — starting from an empty one would make "the roster survived" true for
    free. Re-established per test rather than once for the module: a test that failed to roll back
    would otherwise leave the next one measuring nothing.
    """
    response = _save(env, {"artisanIds": env["artisans"], "craftIds": env["crafts"]})
    assert response.status_code == 200, response.text
    assert _roster(env) == set(env["artisans"])
    assert _craft_roster(env) == set(env["crafts"])
    return env


# --------------------------------------------------------------------------------------
# 1. A failure between the delete and the create
# --------------------------------------------------------------------------------------


def test_a_failed_insert_does_not_commit_the_wipe_that_preceded_it(full_roster, monkeypatch):
    """THE DATA LOSS, in the shape that reaches production.

    The roster holds three artisans. A save narrows it to one, so the rewrite deletes all three and
    inserts one — and the insert fails. Before the transaction, the workshop came back with an EMPTY
    roster and a 500, and the three links were gone with nothing anywhere holding their ids.
    """
    env = full_roster

    from prisma.actions import WorkshopArtisanActions

    async def _boom(self, *args, **kwargs):  # noqa: ANN001 - mirrors the generated `create_many`
        raise RuntimeError("the roster could not be re-inserted")

    monkeypatch.setattr(WorkshopArtisanActions, "create_many", _boom)

    response = _save(env, {"artisanIds": [env["artisans"][0]]})
    assert response.status_code == 500, response.text

    monkeypatch.undo()
    assert _roster(env) == set(env["artisans"]), (
        "the delete committed without its insert: the roster was wiped by a failed save"
    )


def test_the_craft_roster_has_the_same_protection(full_roster, monkeypatch):
    """The twin, and it matters MORE than the artisan one. ``craft_workshop_clause`` records that on
    the live repository almost every craft predates the ``workshopId`` column, so these join rows are
    not a second opinion about which workshop a craft belongs to — they are the only one."""
    env = full_roster

    from prisma.actions import WorkshopCraftActions

    async def _boom(self, *args, **kwargs):  # noqa: ANN001 - mirrors the generated `create_many`
        raise RuntimeError("the craft roster could not be re-inserted")

    monkeypatch.setattr(WorkshopCraftActions, "create_many", _boom)

    response = _save(env, {"craftIds": [env["crafts"][0]]})
    assert response.status_code == 500, response.text

    monkeypatch.undo()
    assert _craft_roster(env) == set(env["crafts"])


def test_a_failed_roster_write_also_rolls_back_the_row_and_its_audit_row(full_roster, monkeypatch):
    """ONE TRANSACTION, NOT THREE SMALL ONES. The save changes a field, rewrites the artisan roster
    and rewrites the craft roster; a failure in the LAST of those must take the first two with it.

    Without that, a save reported as failed leaves the workshop's notes changed, its artisan roster
    replaced and its craft roster wiped — three different states of doneness in one record, and a
    RecordRevision claiming the note was edited on a save the administrator was told had failed."""
    env = full_roster
    before = _workshop(env)

    from prisma.actions import WorkshopCraftActions

    async def _boom(self, *args, **kwargs):  # noqa: ANN001 - mirrors the generated `create_many`
        raise RuntimeError("the craft roster could not be re-inserted")

    monkeypatch.setattr(WorkshopCraftActions, "create_many", _boom)

    response = _save(
        env,
        {
            "notes": "A note recorded alongside a roster rewrite that fails.",
            "artisanIds": [env["artisans"][0]],
            "craftIds": [env["crafts"][0]],
        },
    )
    assert response.status_code == 500, response.text

    monkeypatch.undo()
    after = _workshop(env)
    assert after["notes"] == before["notes"], "the scalar half of a failed save was committed"
    assert _roster(env) == set(env["artisans"]), "the artisan roster was rewritten by a failed save"
    assert _craft_roster(env) == set(env["crafts"])

    revisions = env["client"].get(
        f"/api/data-access/revisions?recordType=workshop&recordId={env['workshop_id']}",
        headers=env["prof"],
    )
    assert revisions.status_code == 200, revisions.text
    assert not [
        row
        for row in revisions.json()
        if row["changes"].get("notes", {}).get("new")
        == "A note recorded alongside a roster rewrite that fails."
    ], "the ledger kept a row for a save that was rolled back"


# --------------------------------------------------------------------------------------
# 2. The rewrite that is supposed to happen still happens
# --------------------------------------------------------------------------------------


def test_a_successful_rewrite_replaces_the_roster_wholesale(full_roster):
    """The control. Every assertion above is also satisfied by a route that stopped writing rosters
    altogether, which would be a worse bug than the one being fixed."""
    env = full_roster

    response = _save(env, {"artisanIds": env["artisans"][:1], "craftIds": env["crafts"][:2]})
    assert response.status_code == 200, response.text
    assert _roster(env) == {env["artisans"][0]}
    assert _craft_roster(env) == set(env["crafts"][:2])


def test_saving_the_same_roster_twice_running_is_not_a_conflict(full_roster):
    """THE CONCURRENT-SAVE SHAPE, reduced to the part a single-threaded test can actually pin.

    A genuine race needs two requests in flight at once, which a TestClient cannot produce and which
    would be timing-dependent if it could. What IS deterministic — and what the unique index
    punishes — is a rewrite meeting rows that already carry the exact pairs it is about to insert.
    Re-sending an unchanged roster does precisely that, and it must be a 200 rather than a 500 on a
    duplicate key. If this ever goes red, ``delete_many`` and ``create_many`` have stopped seeing
    each other's work, which is the same fault the race exposes from the other side."""
    env = full_roster

    for _attempt in range(2):
        response = _save(env, {"artisanIds": env["artisans"], "craftIds": env["crafts"]})
        assert response.status_code == 200, response.text
    assert _roster(env) == set(env["artisans"])
    assert _craft_roster(env) == set(env["crafts"])


# --------------------------------------------------------------------------------------
# 3. The guard against the silent way this comes undone
# --------------------------------------------------------------------------------------


def test_neither_roster_helper_can_write_through_the_module_client():
    """A WRITE THAT LOOKS TRANSACTIONAL AND IS NOT HAS NO OTHER SYMPTOM, which is why this is a test
    and not a comment.

    Prisma's ``db.tx()`` hands back a DIFFERENT client. A statement inside these helpers written as
    ``db.workshopartisan...`` rather than through the injected ``writer`` sits visually inside the
    caller's ``async with`` and commits outside it — the roster half of the save then survives a
    rollback, silently, on a path that reads correctly. No behavioural test can catch that: on the
    happy path the rows land either way.

    Matched with ``\\s`` and never a literal newline, so the assertion means the same thing whichever
    way this file's line endings are checked out.
    """
    from app.api.routes import workshops

    for helper in (workshops.replace_workshop_artisans, workshops.replace_workshop_crafts):
        source = inspect.getsource(helper)
        body = source.split('"""')[-1]
        assert not re.search(r"\bawait\s+db\s*\.", body), (
            f"{helper.__name__} writes through the module-level db client; it must use the "
            f"`writer` bound from its `client` argument, or the caller's transaction is a lie"
        )
        assert re.search(r"writer\s*=\s*db\s+if\s+client\s+is\s+None\s+else\s+client", body), (
            f"{helper.__name__} no longer resolves its writer from `client`"
        )
