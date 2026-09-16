"""The "Type of workshop" list: the seed, the admin gate, the permanent key, and the delete refusal.

FIVE THINGS ARE PINNED HERE, and every one of them is a way a record ends up pointing at the wrong
workshop or a vocabulary quietly loses a member.

**THE SIX SEEDED TYPES EXIST AND ONE OF THEM ROUTES AT `DesignWorkshop`.** The whole of slice B's
picker rests on `routesToDesignWorkshop`: it is what decides whether the workshop a researcher
chooses is written to ``Record.designWorkshopId`` or to ``Record.workshopId``. A seed that shipped
with the flag on the wrong row — or on none — would file every design & prototype record against the
legacy table, silently, with nothing on any screen to say so.

**EVERY `DesignWorkshop.workshopKind` VALUE IN THE DATABASE RESOLVES TO A LABEL.** That is why the
seed uses the registry's own six keys. A token with no row renders as a workshop with no type, which
is indistinguishable from a workshop whose type was never answered.

**A DESIGNER IS REFUSED AT THE ROUTE, NOT MERELY IN THE UI.** Every write here is
``require_admin``. A designer who types the URL meets a 403 from the server; the hidden button on the
admin screen is a courtesy, never the boundary.

**A LABEL EDIT CANNOT MOVE A KEY.** The key is stored in ``DesignWorkshop.workshopKind``, in
``AnnualPlanEntry.workshopKind``, inside stage documents and in every export ever taken, and none of
those is reachable from this router. ``WorkshopTypeOptionUpdate`` does not carry the field and
``APIModel`` forbids extras, so the attempt is a 422 rather than a silent no-op — the difference
between an administrator learning the rule and an administrator believing a rename worked.

**A TYPE WITH WORKSHOPS UNDER IT CANNOT BE DELETED, AND THE REFUSAL COUNTS THEM.** There is no
foreign key onto this table — a record stores the WORKSHOP, and the workshop knows its own type — so
Postgres cannot make this refusal for us. The 409 names how many workshops are in the way and names
deactivation as the remedy, because an administrator told only "no" tries again tomorrow.

Postgres is required — the behaviour under test is rows in one table deciding an HTTP status — so the
module skips itself when ``DATABASE_URL`` does not point at a local database, exactly as
``test_designer_roster`` does.

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

import os
import uuid
from contextlib import suppress
from datetime import UTC, datetime
from typing import Any

import pytest

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services.stage_schema import ENUMS

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

PASSWORD = "workshop-types-test-password"

#: The key the seed marks as routing at ``DesignWorkshop``. Asserted verbatim rather than imported
#: from the migration, which is a .sql file nothing can import — and that is the point: this is the
#: one string slice B's picker depends on existing, and a test that read it from the same place the
#: code does would pass with the seed deleted.
DESIGN_KEY = "DESIGN_PROTOTYPE_DEVELOPMENT"

#: Accounts the module needs, and what each of them is here to prove.
ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("admin", "ADMIN", "Workshop Types Admin"),
    # THE TIER THAT MUST BE REFUSED. A designer runs the workshops these types classify and is
    # exactly the person who would reasonably expect to manage the list — which is why the refusal
    # is worth a test rather than an assumption.
    ("designer", "DESIGNER", "Workshop Types Designer"),
    # Outranks a designer, still not an admin. Here so the 403 is demonstrably about `require_admin`
    # (a SET of two roles) and not about a rank floor somewhere below it.
    ("professor", "PROFESSOR", "Workshop Types Professor"),
)


def _stamped_key(stamp: str, suffix: str) -> str:
    """A key nothing else in the database holds, in the shape the API's ``_KEY_PATTERN`` demands.

    TAKES THE STAMP RATHER THAN THE ``world`` DICT so the fixture can call it while it is still
    building that dict — the two design workshops it creates have to be filed under the same tokens
    the tests will later mint types for. :func:`_key` is the same function for callers that already
    have a ``world``.
    """
    return f"TEST_{suffix}_{stamp}".upper()


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    """Accounts and one disposable workshop type, created before the app starts.

    The rows are made here rather than inside a test because the Prisma client is shared with the
    running app and is bound to the TestClient's event loop; touching it from a test's own loop is
    the kind of cross-loop use that fails intermittently rather than honestly. Same arrangement, same
    reason, as ``tests/test_designer_roster.py``.

    EVERY KEY AND EVERY ADDRESS CARRIES A PER-RUN STAMP. ``WorkshopTypeOption.key`` is UNIQUE, so a
    fixed key would pass on a clean database and fail on the second run — the sort of flake that gets
    "fixed" by dropping the database, which throws away whatever it was protecting.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    people: dict[str, Any] = {}
    made: list[str] = []
    workshops: dict[str, str] = {}

    def address(slug: str) -> str:
        return f"wtypes-{slug}-{stamp}@example.org".lower()

    await db.connect()
    try:
        for slug, role, name in ACCOUNTS:
            people[slug] = await db.user.create(
                data={
                    "email": address(slug),
                    "name": name,
                    "role": role,
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            # The platform allow-list admits every account this module creates, so nothing here is
            # decided by `assert_access_admits`. See tests/test_designer_roster.py's fixture, which
            # carries the same obligation and the same note.
            await db.accessroster.create(
                data={
                    "email": address(slug),
                    "status": "ACTIVE",
                    "notes": "Seeded by tests/test_workshop_types.py; this module is about the "
                    "workshop-type vocabulary, not the platform allow-list.",
                }
            )

        # ── THE TWO DESIGN WORKSHOPS THE DELETE REFUSAL IS TESTED AGAINST ────────────────────────
        #
        # MADE HERE AND NOT INSIDE THE TESTS THAT USE THEM. The shared Prisma client is bound to the
        # TestClient's event loop from the moment the app's lifespan connects it, and an async test
        # runs on anyio's own loop — so a `db.designworkshop.create` inside a test is cross-loop use,
        # which fails intermittently rather than honestly. `tests/test_designer_roster.py`'s fixture
        # carries the same rule and the same sentence.
        #
        # THEY ARE FILED UNDER KEYS THAT DO NOT EXIST YET, AND THAT IS NOT A TRICK. There is no
        # foreign key from `DesignWorkshop.workshopKind` onto the type table — that is the whole
        # reason the delete refusal has to be written by hand — so a workshop can be filed under a
        # token before, after, or entirely without a type carrying it. The tests below create the
        # matching type through the API and then try to delete it.
        for slug, kind_suffix, deleted in (("inUse", "INUSE", False), ("trashed", "TRASHED", True)):
            workshop = await db.designworkshop.create(
                data={
                    "title": f"Workshop pinning the delete refusal ({slug}) {stamp}",
                    "workshopKind": _stamped_key(stamp, kind_suffix),
                    "createdById": people["admin"].id,
                    # A workshop in the trash is NOT in use, and the type it points at must still be
                    # deletable — see the test that pins it.
                    "deletedAt": datetime.now(UTC) if deleted else None,
                }
            )
            workshops[slug] = workshop.id
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "people": people,
            "stamp": stamp,
            "made": made,
            "workshops": workshops,
        }

    # The disposable rows this run created, removed so a hundred runs do not leave a hundred types in
    # a list an administrator reads — this table is SIX rows and is drawn in a dropdown, which the
    # roster this fixture is modelled on is not. Deliberately NOT the six seeded rows, which are
    # production data.
    #
    # BEST EFFORT, AND FAILING IT MUST NOT FAIL THE RUN. The TestClient's lifespan has already
    # disconnected the shared client by this point, so this reconnects; if that cannot be done (a
    # database stopped between the last test and here) the assertions have all been made and there is
    # nothing left to report. A cleanup that could redden a green run would be a cleanup people
    # delete.
    with suppress(Exception):
        if not db.is_connected():
            await db.connect()
        try:
            for key in made:
                await db.workshoptypeoption.delete_many(where={"key": key})
            for workshop_id in workshops.values():
                await db.designworkshop.delete_many(where={"id": workshop_id})
            for slug, _role, _name in ACCOUNTS:
                await db.accessroster.delete_many(where={"email": address(slug)})
                await db.user.delete_many(where={"email": address(slug)})
        finally:
            await db.disconnect()


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    """A bearer token for one of the fixture's accounts.

    Minted directly rather than obtained by signing in: every route under test is below the login, so
    a helper that signed in first would make each of these tests depend on the sign-in gate as well.
    """
    return {"Authorization": f"Bearer {create_access_token(subject=world['people'][slug].id)}"}


def _key(world: dict[str, Any], suffix: str) -> str:
    """A key nothing else in the database holds, in the shape the API's ``_KEY_PATTERN`` demands."""
    return _stamped_key(world["stamp"], suffix)


def _make(client: Any, world: dict[str, Any], *, key: str, **overrides: Any) -> dict[str, Any]:
    """Create a type through the API and register it for cleanup.

    ``key`` IS REQUIRED AND HAS NO DEFAULT. A shared default would be a 409 waiting for the second
    test that leaned on it — and the failure would read as a broken create route rather than as two
    tests colliding, on a module whose whole subject is what a taken key does.
    """
    body = {"key": key, "label": "A test type", **overrides}
    response = client.post("/api/workshop-types", json=body, headers=_headers(world, "admin"))
    assert response.status_code == 201, response.text
    world["made"].append(key)
    return response.json()


def _by_key(client: Any, world: dict[str, Any]) -> dict[str, dict[str, Any]]:
    response = client.get(
        "/api/workshop-types",
        params={"includeInactive": "true"},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    return {row["key"]: row for row in response.json()}


# --------------------------------------------------------------------------------------
# 1. The seed
# --------------------------------------------------------------------------------------


async def test_the_six_registry_tokens_all_have_a_type_row(world, client):
    """Every `WORKSHOP_KIND` token resolves to a label.

    THE OVERLAP IS PINNED, NOT EQUALITY. The two vocabularies answer different questions and are
    allowed to diverge — an administrator may add a type the registry has never heard of, and that is
    the feature — so this asserts that the registry's six are all PRESENT, and deliberately does not
    assert that the type list holds nothing else. Asserting equality would fail the first time
    somebody used the screen for what it is for.
    """
    rows = _by_key(client, world)
    missing = [token for token in ENUMS["WORKSHOP_KIND"] if token not in rows]
    assert not missing, (
        f"{missing} are WORKSHOP_KIND tokens with no WorkshopTypeOption row, so a workshop already "
        "filed under one of them renders with no type at all. Seed them; see "
        "prisma/migrations/20260916160000_workshop_type_options."
    )


async def test_exactly_the_design_prototype_type_routes_at_the_design_workshop_table(world, client):
    """The flag slice B's picker reads, on the row it belongs on.

    This is the single most consequential row in the table: it is what sends a chosen workshop to
    `designWorkshopId` instead of `workshopId`. The assertion is over the SEEDED rows only —
    `routesToDesignWorkshop` is deliberately not constrained to one row in the database (the ministry
    can announce a second design-workshop-backed programme) so this pins what was SHIPPED rather than
    an invariant that does not exist.
    """
    rows = _by_key(client, world)
    seeded = {token: rows[token] for token in ENUMS["WORKSHOP_KIND"] if token in rows}
    routing = {key for key, row in seeded.items() if row["routesToDesignWorkshop"]}
    assert routing == {DESIGN_KEY}, (
        "Exactly one seeded type must route at the DesignWorkshop table, and it must be "
        f"{DESIGN_KEY}. Found: {sorted(routing)}."
    )


async def test_the_seeded_labels_are_the_registry_s_own_words(world, client):
    """A workshop filed under a token reads the same on both screens.

    The registry's labels are what a designer sees inside stage 1; these are what a researcher sees
    on a record form. Two spellings of one category is how somebody comes to believe they are two
    categories — and the seed is the only moment the two are guaranteed to agree, because nothing
    synchronises them afterwards and nothing should.
    """
    rows = _by_key(client, world)
    for token, label in ENUMS["WORKSHOP_KIND"].items():
        assert rows[token]["label"] == label, (
            f"{token} reads {rows[token]['label']!r} on the record form and {label!r} inside stage 1."
        )


# --------------------------------------------------------------------------------------
# 2. The gate
# --------------------------------------------------------------------------------------


async def test_every_signed_in_account_may_READ_the_list(world, client):
    """The picker on every record form draws this, so a researcher must be able to read it.

    A list gated to admins would be a dropdown with no members for everybody else — and the failure
    would look like a broken form rather than like a permission.
    """
    for slug in ("designer", "professor", "admin"):
        response = client.get("/api/workshop-types", headers=_headers(world, slug))
        assert response.status_code == 200, f"{slug}: {response.text}"
        assert len(response.json()) >= len(ENUMS["WORKSHOP_KIND"])


async def test_a_designer_may_not_create_a_type_and_is_refused_by_the_SERVER(world, client):
    """403 at the route. The hidden button on the admin screen is not the boundary.

    A designer is the tier most likely to try: they run the workshops these types classify. The
    refusal has to be the server's, because a client-side guard that only hides a link is one typed
    URL away from nothing at all.
    """
    response = client.post(
        "/api/workshop-types",
        json={"key": _key(world, "FORBIDDEN"), "label": "Should never exist"},
        headers=_headers(world, "designer"),
    )
    assert response.status_code == 403, response.text


async def test_a_professor_outranks_a_designer_and_is_still_refused(world, client):
    """`require_admin` is a SET ({ADMIN, MASTER_ADMIN}), not a rank floor.

    Without this test a gate written as `hasRank(user, "PROFESSOR")` would pass every other assertion
    in this file, and the vocabulary every record form reads would be editable by forty accounts
    instead of by two.
    """
    response = client.post(
        "/api/workshop-types",
        json={"key": _key(world, "PROFESSOR"), "label": "Should never exist"},
        headers=_headers(world, "professor"),
    )
    assert response.status_code == 403, response.text


async def test_a_designer_may_not_edit_or_delete_a_type(world, client):
    """The other two verbs, because a gate is only as good as its least-guarded route."""
    created = _make(client, world, key=_key(world, "GATED"))
    patch = client.patch(
        f"/api/workshop-types/{created['id']}",
        json={"label": "Renamed by somebody who may not"},
        headers=_headers(world, "designer"),
    )
    assert patch.status_code == 403, patch.text
    delete = client.delete(
        f"/api/workshop-types/{created['id']}", headers=_headers(world, "designer")
    )
    assert delete.status_code == 403, delete.text


# --------------------------------------------------------------------------------------
# 3. The permanent key, and the label edit that must always be safe
# --------------------------------------------------------------------------------------


async def test_editing_a_label_is_safe_and_never_touches_the_key(world, client):
    """THE COMMON CASE. An administrator corrects a spelling; nothing else moves.

    The key is the token stored on every workshop filed under this type. If a label edit could move
    it, the ordinary act of fixing a typo would orphan every one of them — and nothing on the screen
    would say so.
    """
    created = _make(client, world, key=_key(world, "LABEL"), label="Mispelt Label")
    response = client.patch(
        f"/api/workshop-types/{created['id']}",
        json={"label": "Correctly Spelt Label"},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    updated = response.json()
    assert updated["label"] == "Correctly Spelt Label"
    assert updated["key"] == created["key"], "A label edit moved the key."
    assert updated["routesToDesignWorkshop"] == created["routesToDesignWorkshop"], (
        "A label edit changed where records under this type save. `exclude_unset` is what stops an "
        "absent field being written as a null; see routes/workshop_types.update_workshop_type."
    )
    assert updated["isActive"] == created["isActive"]


async def test_an_attempt_to_rename_a_key_is_REFUSED_rather_than_ignored(world, client):
    """422, naming the field — not a 200 that quietly dropped it.

    The difference is the whole point: a silent drop leaves an administrator believing the rename
    worked, and the next person to look at the exports finds a token nobody recognises.
    """
    created = _make(client, world, key=_key(world, "IMMUTABLE"))
    response = client.patch(
        f"/api/workshop-types/{created['id']}",
        json={"key": _key(world, "SOMETHINGELSE"), "label": "Still here"},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 422, response.text
    assert "key" in response.text

    # And the stored row is untouched — the refusal rolled the whole request back, label included.
    rows = _by_key(client, world)
    assert created["key"] in rows
    assert rows[created["key"]]["label"] == created["label"]


async def test_a_key_that_is_already_taken_answers_409_and_not_500(world, client):
    """The unique index, converted in place.

    Without the conversion the driver's UniqueViolationError reaches main.py's catch-all, which logs
    a stack trace and answers "Something went wrong on the server" — telling an administrator the
    server is broken when the truth is that the token is taken and retrying cannot help.
    """
    created = _make(client, world, key=_key(world, "TAKEN"))
    response = client.post(
        "/api/workshop-types",
        json={"key": created["key"], "label": "A second type under one key"},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 409, response.text
    assert created["key"] in response.json()["detail"]


async def test_a_malformed_key_is_refused_with_a_sentence_a_person_can_act_on(world, client):
    """Lower case, spaces and hyphens are all ways a token differs invisibly from a stored one."""
    for bad in ("design prototype", "design-prototype", "designPrototype", "1_LEADING_DIGIT"):
        response = client.post(
            "/api/workshop-types",
            json={"key": bad, "label": "Should never exist"},
            headers=_headers(world, "admin"),
        )
        assert response.status_code == 422, f"{bad!r} was accepted: {response.text}"
        # The refusal names the shape AND gives an example. "must match ^[A-Z][A-Z0-9_]*$" is a
        # sentence that sends an administrator to ask somebody else.
        assert "DESIGN_PROTOTYPE_DEVELOPMENT" in response.text


# --------------------------------------------------------------------------------------
# 4. Retiring, which is the remedy the delete refusal names
# --------------------------------------------------------------------------------------


async def test_a_retired_type_leaves_the_picker_and_stays_on_the_admin_screen(world, client):
    """Deactivating is the safe half of the pair: out of every dropdown, nothing else touched.

    Both halves are asserted, because either one alone is a different feature. Absent from the
    default list is what makes retiring MEAN anything; present in the admin's list is what makes it
    REVERSIBLE — a row an administrator cannot see is a row they cannot put back.
    """
    created = _make(client, world, key=_key(world, "RETIRE"))
    response = client.patch(
        f"/api/workshop-types/{created['id']}",
        json={"isActive": False},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    assert response.json()["isActive"] is False

    offered = client.get("/api/workshop-types", headers=_headers(world, "designer")).json()
    assert created["key"] not in {row["key"] for row in offered}, (
        "A retired type is still being offered in the picker."
    )
    assert created["key"] in _by_key(client, world), (
        "A retired type has vanished from the admin screen, so nobody can put it back."
    )


# --------------------------------------------------------------------------------------
# 5. The delete, and the refusal that is the whole point of it
# --------------------------------------------------------------------------------------


async def test_a_type_nothing_uses_is_deleted_outright(world, client):
    """The ordinary case: a type added by mistake, removed. No ceremony, no tombstone.

    Making THIS a two-step retirement would leave the list carrying somebody's typo forever, which is
    its own kind of unusable.
    """
    created = _make(client, world, key=_key(world, "UNUSED"))
    response = client.delete(
        f"/api/workshop-types/{created['id']}", headers=_headers(world, "admin")
    )
    assert response.status_code == 204, response.text
    assert created["key"] not in _by_key(client, world)


async def test_a_type_with_a_workshop_under_it_is_REFUSED_and_the_refusal_COUNTS_them(
    world, client
):
    """THE TRAP THIS MODULE EXISTS FOR.

    There is no foreign key onto the type table — a record stores the WORKSHOP, and the workshop
    knows its own type — so Postgres cannot refuse this for us. A delete that went through would
    leave the workshop holding a token nothing can resolve: it would render as a workshop with no
    type, indistinguishable from one whose type was never answered, and the only surviving record of
    the category would be in an export somebody took last year.

    THREE THINGS ARE ASSERTED ABOUT THE REFUSAL, AND THE NUMBER IS THE ONE THAT MATTERS. "Cannot
    delete: in use" is a refusal an administrator cannot act on. The count tells them the size of
    what they are about to break, and the remedy tells them what to do instead — and the remedy is
    the sentence that decides whether they try again tomorrow.
    """
    # The workshop is the fixture's — already filed under this key before the type existed, which the
    # absent foreign key makes not merely possible but the ordinary case on a live database.
    created = _make(client, world, key=_key(world, "INUSE"), label="A type in use")

    response = client.delete(
        f"/api/workshop-types/{created['id']}", headers=_headers(world, "admin")
    )
    assert response.status_code == 409, response.text
    detail = response.json()["detail"]
    assert "1 design workshop" in detail, f"The refusal does not count the workshops: {detail!r}"
    assert "Deactivate" in detail, f"The refusal does not name the remedy: {detail!r}"
    assert created["label"] in detail, f"The refusal does not name the type: {detail!r}"

    # AND THE ROW SURVIVED. A refusal that answered 409 after deleting would be the worst of both.
    assert created["key"] in _by_key(client, world)

    # THE REMEDY THE REFUSAL NAMED ACTUALLY WORKS, asserted here rather than taken on trust: a
    # refusal that recommends an action the API then rejects is worse than one that recommends
    # nothing, because the administrator has now been sent round a loop.
    retire = client.patch(
        f"/api/workshop-types/{created['id']}",
        json={"isActive": False},
        headers=_headers(world, "admin"),
    )
    assert retire.status_code == 200, retire.text
    assert retire.json()["isActive"] is False


async def test_a_SOFT_DELETED_workshop_does_not_hold_a_type_hostage(world, client):
    """A workshop in the trash is not in use.

    `DesignWorkshop` is never hard-deleted and every read in the product filters `deletedAt: null`,
    so counting deleted rows would refuse the delete of a type whose only workshops are in the
    bin — and there would be no way to tell the administrator which, because nothing lists them
    outside the admin hub's recovery card. The refusal would be unanswerable rather than merely
    strict.
    """
    # The fixture's soft-deleted workshop is already filed under this key.
    created = _make(client, world, key=_key(world, "TRASHED"))
    response = client.delete(
        f"/api/workshop-types/{created['id']}", headers=_headers(world, "admin")
    )
    assert response.status_code == 204, response.text


# --------------------------------------------------------------------------------------
# 6. Ordering
# --------------------------------------------------------------------------------------


async def test_reorder_moves_the_whole_list_in_one_request(world, client):
    """One request, not N, because a reorder half-applied is an order nobody chose.

    The ids sent are renumbered from the top in the order given; rows not named keep their position.
    The assertion below is deliberately RELATIVE — "a is before b" — rather than against absolute
    `sortOrder` values, so it survives the seed's numbering changing.
    """
    first = _make(client, world, key=_key(world, "ORDA"), label="Order probe A")
    second = _make(client, world, key=_key(world, "ORDB"), label="Order probe B")

    response = client.patch(
        "/api/workshop-types/reorder",
        json={"ids": [second["id"], first["id"]]},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    positions = {row["key"]: row["sortOrder"] for row in response.json()}
    assert positions[second["key"]] < positions[first["key"]]


async def test_reorder_refuses_an_id_this_server_does_not_have(world, client):
    """Skipping an unknown id would renumber the rows around it and answer 200 — an order the
    administrator did not ask for, with nothing on screen to say why."""
    created = _make(client, world, key=_key(world, "ORDMISSING"))
    response = client.patch(
        "/api/workshop-types/reorder",
        json={"ids": [created["id"], "not-an-id-anything-here-holds"]},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 404, response.text


async def test_the_reorder_path_is_not_swallowed_by_the_id_route(world, client):
    """⚠ `PATCH /{type_id}` matches `/reorder` perfectly well, with `type_id="reorder"`.

    Registered the wrong way round, this endpoint answers 404 "Workshop type not found" on a server
    where it exists — the same hazard `api/router.py` records for `/design-workshops` and its literal
    paths, one level down. The test is the thing that stops somebody tidying the routes into
    alphabetical order.
    """
    response = client.patch(
        "/api/workshop-types/reorder",
        json={"ids": []},
        headers=_headers(world, "admin"),
    )
    # 422 from the body (`min_length=1`), NOT 404 from `_require_type("reorder")`. Either way it
    # proves the literal path won the match; the point is that it is not the id route's 404.
    assert response.status_code == 422, response.text
