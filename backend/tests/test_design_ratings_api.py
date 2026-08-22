"""The rating API against a real Postgres and a real request: who is refused, and how.

``test_design_ratings`` covers the RULES as pure functions. This module covers the thing a pure
function cannot: that the rules are actually WIRED — that a request from an outsider is turned away
by the server rather than by a predicate nobody calls, that the refusal is a 404 and not a 403, and
that the sentence it carries is the same one a genuinely missing id gets.

Postgres is required, so the module skips itself when ``DATABASE_URL`` does not point at a local
database, exactly as ``test_design_workshop_viewers`` does::

    docker compose up -d postgres minio          # from the REPOSITORY ROOT, not from backend/
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

================================================================================================
WHY THIS MODULE IS IN TWO HALVES, AND WHAT THE SECOND HALF IS WAITING FOR
================================================================================================

``DwReviewRating`` is added to ``prisma/schema.prisma`` by the agent who owns that file, in the same
wave as this API. Its migration has since LANDED and is applied on the local database; what is
still behind is the GENERATED CLIENT, so ``db.dwreviewrating`` does not exist on this build and no
row can be written until somebody runs ``prisma generate``. That is a real state of the tree and it
is stated rather than worked around:

* **The refusal half runs today, unconditionally.** Every access decision this API makes is reached
  BEFORE the ledger is touched — ``load_subject``, ``resolve_access`` and
  ``load_ratable_workshop_or_404`` read ``DwStageEntry``, ``DesignWorkshop`` and
  ``DesignWorkshopViewer``, all of which exist — so the whole permission matrix is exercised end to
  end against real rows. That is the half of this surface that can hurt somebody, and it is not
  skipped.
* **The round-trip half is marked ``needs_ledger``** and skips with a sentence naming exactly what
  is missing. A skipped test and a test that was never written must never look the same, which is
  the argument ``conftest`` makes at length for the database gate; this is the same argument one
  table down.

:func:`admitted` is what joins the two: a caller who PASSES the access rules must reach the ledger,
which is a 200 where the table exists and the ledger's own 503 where it does not. So the admission
side is asserted today as well, and tightens by itself the moment the migration lands — no edit,
and no test quietly asserting less than it says.

**AND ONE ASSERTION HERE IS NOT ABOUT ACCESS AT ALL.**
:func:`test_the_registry_still_declares_what_this_api_assumes_about_it` pins the two facts the
service reads out of the registry — that ``sketch`` and ``prototype`` exist, and that
``peerRoundClosedAt`` is declared on EXACTLY those two and on nothing else. Those are what the pool
gate is built on, and if the registry moves under them the failure would otherwise be a pool round
that silently never opens.

**THE SKETCH USED TO CARRY NO SUCH FIELD, and this module said so.** It was declared on
``prototype`` alone, which made "a sketch has no pool round" a fact about the registry rather than
a decision anybody took — while ``sketchReview.reviewRound`` and ``RATEABLE_ENTITIES`` both went on
assuming a sketch could reach one. The field is now declared on ``sketch`` too, blank on every
existing row, so the gate is the same per-piece date on either kind of subject and this module
tests it in both directions on both.
"""

import os
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services.design_ratings import POOL_OPENS_WHEN_FIELD, RATEABLE_ENTITIES, RATING_DELEGATE
from prisma import Json

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

#: Whether the rating table exists in this build's generated client. Read once, here, so every
#: guarded test skips for the same stated reason instead of erroring on a missing attribute.
HAS_LEDGER = hasattr(db, RATING_DELEGATE)

needs_ledger = pytest.mark.skipif(
    not HAS_LEDGER,
    reason=(
        f"db.{RATING_DELEGATE} is not in this build's Prisma client. RUN `prisma generate` — the "
        f"table itself is already there: prisma/migrations/20260822120000_dw_review_rating_ledger "
        f"is applied on the local database, and only the generated client is behind. (If a "
        f"deployment is missing the TABLE too, `prisma migrate deploy` first.) The ACCESS rules in "
        f"this module still run — they are decided before the ledger is read."
    ),
)

PASSWORD = "rating-test-password"

#: The stages and entities the ratings point at. Named rather than discovered, so a registry
#: reshuffle fails this module loudly instead of quietly testing nothing.
PROTOTYPE_STAGE = "PROTOTYPE_DEVELOPMENT"
PROTOTYPE_ENTITY = "prototype"
SKETCH_STAGE = "SKETCH_DEVELOPMENT"
SKETCH_ENTITY = "sketch"

#: slug -> (role, display name).
#:
#: ``creator`` IS AN ADMIN because only admins may START a design workshop
#: (``can_create_design_workshops``); ``author`` is the DESIGNER who enters the pieces and is
#: therefore the person "their own records" is about. ``researcher`` is here to prove that the ROLE
#: gate beats MEMBERSHIP: they are given a viewer row directly, which the admin API would refuse,
#: because a grant that somehow existed must still not admit them to this surface.
ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("admin", "ADMIN", "Rating Admin"),
    ("creator", "ADMIN", "Workshop Creator"),
    ("author", "DESIGNER", "Prototype Author"),
    ("peer", "DESIGNER", "Workshop Peer"),
    ("outsider", "DESIGNER", "Unrelated Designer"),
    ("researcher", "RESEARCHER", "Granted Researcher"),
)

#: The prototypes: ``(ordinal, name, opened to the pool)``.
#:
#: THE ORDINALS DISAGREE WITH THE ORDER THEY ARE CREATED IN, which is what lets the ranking
#: assertions tell a list that was sorted from a list that merely came back in insertion order.
#:
#: EXACTLY ONE IS OPEN TO THE POOL, and that is the point of the table. The gate is per piece
#: (``prototype.peerRoundClosedAt``), so a workshop with all its prototypes open, or none, cannot
#: tell a gate that is holding from one that was never written.
PROTOTYPES: tuple[tuple[int, str, bool], ...] = (
    (2, "Kansa bowl", False),
    (0, "Brass lamp", True),
    (1, "Bell-metal tray", False),
)


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    """One workshop, its pieces and its team — plus a second that has opened nothing — created
    before the app starts.

    **BUILT WITH PRISMA RATHER THAN THROUGH THE STAGE API, deliberately.** What is under test is the
    rating rules, and two of the facts they turn on cannot be set through the stage routes at all: a
    viewer row for a RESEARCHER (the admin API refuses one, and refusing it is a different module's
    assertion) and a piece whose ``createdById`` is a specific designer. Driving those through the
    API would make every test here depend on the create gate, the eligibility rule and the stage
    validator as well, and a failure in any of them would surface as a rating bug.

    Made in the fixture rather than inside a test because the Prisma client is shared with the
    running app and bound to the TestClient's event loop; touching it from a test's own loop is the
    kind of cross-loop use that fails intermittently rather than honestly.

    THE TWO SKETCHES ARE NOT DECORATION, AND THEY DISAGREE WITH EACH OTHER ON PURPOSE. Since
    ``peerRoundClosedAt`` is declared on ``sketch`` as well, a sketch's pool round is gated by the
    same per-piece date a prototype's is — so one sketch here is dated and one is not, and both sit
    beside an OPENED prototype. Without the undated one the gate could be missing entirely and
    nothing would notice; without the dated one, "a sketch is refused" would prove only that
    sketches are refused wholesale, which is the behaviour this field was added to end.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    people: dict[str, Any] = {}
    prototypes: dict[int, str] = {}

    await db.connect()
    try:
        for slug, role, name in ACCOUNTS:
            people[slug] = await db.user.create(data={
                "email": f"dwrating-{slug}-{stamp}@example.org",
                "name": f"{name} {stamp}",
                "role": role,
                "passwordHash": hash_password(PASSWORD),
            })
        workshop = await db.designworkshop.create(data={
            "title": f"Dhokra ratings {stamp}",
            "status": "IN_PROGRESS",
            "createdById": people["creator"].id,
        })
        for index, (ordinal, name, opened) in enumerate(PROTOTYPES):
            data: dict[str, Any] = {"prototypeCode": f"P{index + 1}", "name": name}
            if opened:
                data[POOL_OPENS_WHEN_FIELD] = "2026-08-10"
            row = await db.dwstageentry.create(data={
                "designWorkshopId": workshop.id,
                "stageKey": PROTOTYPE_STAGE,
                "entityKey": PROTOTYPE_ENTITY,
                "ordinal": ordinal,
                "data": Json(data),
                "createdById": people["author"].id,
            })
            prototypes[ordinal] = row.id
        sketch = await db.dwstageentry.create(data={
            "designWorkshopId": workshop.id,
            "stageKey": SKETCH_STAGE,
            "entityKey": SKETCH_ENTITY,
            "ordinal": 0,
            "data": Json({"sketchNo": "S1", "name": "Rim study"}),
            "createdById": people["author"].id,
        })
        opened_sketch = await db.dwstageentry.create(data={
            "designWorkshopId": workshop.id,
            "stageKey": SKETCH_STAGE,
            "entityKey": SKETCH_ENTITY,
            "ordinal": 1,
            "data": Json({
                "sketchNo": "S2",
                "name": "Lamp elevation",
                POOL_OPENS_WHEN_FIELD: "2026-08-12",
            }),
            "createdById": people["author"].id,
        })
        for slug in ("author", "peer", "researcher"):
            await db.designworkshopviewer.create(data={
                "designWorkshopId": workshop.id,
                "userId": people[slug].id,
                "grantedById": people["admin"].id,
            })
        # A SECOND WORKSHOP THAT HAS OPENED NOTHING. Its only job is to keep "a pool ranking with
        # nothing opened is a 404 and not an empty 200" testable: the main workshop above now has
        # a dated sketch as well as an undated one, on purpose, so it can no longer be the
        # collection a stranger sees nothing in.
        quiet_workshop = await db.designworkshop.create(data={
            "title": f"Dhokra ratings quiet {stamp}",
            "status": "IN_PROGRESS",
            "createdById": people["creator"].id,
        })
        for ordinal, name in ((0, "Undated study"), (1, "Second undated study")):
            await db.dwstageentry.create(data={
                "designWorkshopId": quiet_workshop.id,
                "stageKey": SKETCH_STAGE,
                "entityKey": SKETCH_ENTITY,
                "ordinal": ordinal,
                "data": Json({"sketchNo": f"Q{ordinal + 1}", "name": name}),
                "createdById": people["author"].id,
            })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "people": people,
            "workshop": workshop.id,
            "prototypes": prototypes,
            "sketch": sketch.id,
            "openedSketch": opened_sketch.id,
            "quietWorkshop": quiet_workshop.id,
            "stamp": stamp,
        }


@pytest.fixture
def client(world):
    return world["client"]


def headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    """A bearer token for one of the fixture's accounts.

    Minted directly rather than obtained by signing in, because the roster gate lives on the LOGIN
    path only and what is under test here is the rating rules. A helper that signed in first would
    make every one of these tests depend on the empanelment tables as well.
    """
    return {"Authorization": f"Bearer {create_access_token(world['people'][slug].id)}"}


#: The prototype that HAS been declared finished, and one that has not. Named, because "ordinal 0"
#: says nothing at a call site and the difference between the two is the whole pool gate.
OPENED = 0
STILL_IN_PEER_REVIEW = 1
ALSO_IN_PEER_REVIEW = 2


def prototype(world: dict[str, Any], ordinal: int = OPENED) -> str:
    return world["prototypes"][ordinal]


def refused(response) -> None:
    """The one refusal this API makes, asserted in full.

    Both halves matter. The STATUS must be 404 and not 403, so a caller cannot learn which cuids
    exist by watching the code change; and the DETAIL must be the same string a genuinely missing
    id gets, because a refusal that varies its wording by reason is the same disclosure arriving one
    sentence later. ``services/records.require_record`` is the rule being followed.
    """
    assert response.status_code == 404, response.text
    assert response.json()["detail"] == "Record not found"


def admitted(response) -> None:
    """The caller PASSED the access rules and reached the ledger.

    Two acceptable outcomes, and which one appears is a fact about THIS BUILD rather than about the
    caller: 200 where ``db.dwreviewrating`` exists, and the ledger's own 503 where the generated
    client has no such delegate. Both prove the access decision went the caller's way, which is what
    this module is for; neither can be reached by anybody the rules turn away, because every refusal
    in this API is decided before the ledger is touched.

    Written this way rather than as a bare ``!= 404`` so that the assertion TIGHTENS on its own the
    moment ``prisma generate`` is run, instead of continuing to accept a 503 that would by then be a
    real failure.
    """
    expected = 200 if HAS_LEDGER else 503
    assert response.status_code == expected, response.text


# ------------------------------------------------------------------------------------------
# What this API assumes about the registry
# ------------------------------------------------------------------------------------------


def test_the_registry_still_declares_what_this_api_assumes_about_it():
    """Two facts the pool gate is built on, pinned against the live registry.

    ``RATEABLE_ENTITIES`` must name real entities, or every subject lookup 404s and the whole
    feature is silently dead. And ``peerRoundClosedAt`` must be declared on EXACTLY the rateable
    entities — no fewer and no more.

    THE EQUALITY IS THE ASSERTION, and both directions of it have already been wrong. Declared on
    too FEW, a subject that can be rated has no way ever to reach its second round: that was the
    state of the registry until ``sketch`` was given the field, and nothing failed, because a round
    that never opens looks exactly like a round nobody has opened yet. Declared on too MANY, the
    gate opens on rows nobody meant to publish — a ``prototypeStageLog`` carrying the key would be
    a material line-item offered to the whole country.

    NO ``world`` FIXTURE, DELIBERATELY. This is a pure registry assertion — it reads
    :func:`all_entities` and nothing else — and taking the module-scoped fixture made it connect to
    Postgres and build two workshops, six users and seven stage rows it never touched. The normal
    state of a development machine here is Docker down, where every DB-backed test errors or skips:
    the load-bearing pin for the pool gate was therefore the one that did not run precisely when
    the cheap checks were all that ran. `test_review_rating_ledger` walks the same two sets from the
    registry as well, and the duplication is wanted rather than tolerated — that module is the
    declaration-versus-declaration suite, this one is what this API assumes, and either could be
    edited without the other.
    """
    from app.services.stage_schema import all_entities

    declared = {entity.key: {f.key for f in entity.fields} for _stage, entity in all_entities()}
    assert set(declared) >= RATEABLE_ENTITIES, (
        f"the registry no longer declares {RATEABLE_ENTITIES - set(declared)}"
    )
    carries_the_switch = {
        key for key, fields in declared.items() if POOL_OPENS_WHEN_FIELD in fields
    }
    assert carries_the_switch == set(RATEABLE_ENTITIES), (
        f"{POOL_OPENS_WHEN_FIELD} is declared on {sorted(carries_the_switch)}, but the rateable "
        f"entities are {sorted(RATEABLE_ENTITIES)}. A rateable entity without the switch can "
        f"never open its pool round; a non-rateable one with it is a row nobody meant to publish"
    )


# ------------------------------------------------------------------------------------------
# The refusals. These run whether or not the ledger table exists.
# ------------------------------------------------------------------------------------------


def test_an_unrelated_designer_cannot_read_a_peer_rounds_ledger(world, client):
    """The control, and the assertion this whole surface stands on.

    A designer with no grant on the workshop is not a peer. 404 rather than 403 deliberately: this
    test would pass just as well against an implementation that had no rating feature at all, which
    is exactly what makes it worth keeping — it says the widening is a widening of one clause and
    not the removal of a check.
    """
    refused(
        client.get(
            f"/api/design-ratings/subjects/{prototype(world)}?round=PEER",
            headers=headers(world, "outsider"),
        )
    )


def test_an_unfinished_prototype_has_no_pool_round_for_a_stranger(world, client):
    """*"the whole pool of designers ONCE PROTOTYPES ARE FINALISED"*, per prototype.

    Both of the pieces whose ``peerRoundClosedAt`` is blank are refused, while a sibling in the same
    workshop is open — which is exactly what a workshop-level flag could not express, and what the
    registry field's own note refuses: *"A workshop-level flag would open the pool round on nine
    unfinished prototypes the day the tenth was done."*
    """
    for ordinal in (STILL_IN_PEER_REVIEW, ALSO_IN_PEER_REVIEW):
        refused(
            client.get(
                f"/api/design-ratings/subjects/{prototype(world, ordinal)}?round=POOL",
                headers=headers(world, "outsider"),
            )
        )


def test_an_undated_sketch_has_no_pool_round_even_beside_an_opened_prototype(world, client):
    """A sketch whose ``peerRoundClosedAt`` is blank is still in peer review, like any other piece.

    Asserted in a workshop that HAS an opened prototype AND an opened sketch, so the refusal cannot
    be explained by the workshop being closed to the pool — there is no such thing as a workshop
    being closed to the pool, and this is the test that says so.

    THIS USED TO PASS FOR A DIFFERENT REASON: the registry gave a sketch no such field at all, so
    every sketch was refused wholesale and this assertion could not tell a gate that was holding
    from a subject the feature had forgotten. Its sibling below is what makes it mean something.
    """
    refused(
        client.get(
            f"/api/design-ratings/subjects/{world['sketch']}?round=POOL",
            headers=headers(world, "outsider"),
        )
    )


def test_a_researchers_viewer_grant_does_not_admit_them_to_this_surface(world, client):
    """MEMBERSHIP IS NOT ENOUGH; THE ROLE GATE COMES FIRST.

    This account holds a real ``DesignWorkshopViewer`` row — inserted directly, because the admin
    API would refuse to create one — and is still turned away, in both rounds and on the piece that
    IS open to the pool. Without this account the role gate could be deleted and the suite would
    stay green, since every other refusal here is explained by the absence of a grant.
    """
    for ordinal in (OPENED, STILL_IN_PEER_REVIEW):
        for round_ in ("PEER", "POOL"):
            refused(
                client.get(
                    f"/api/design-ratings/subjects/{prototype(world, ordinal)}?round={round_}",
                    headers=headers(world, "researcher"),
                )
            )


def test_an_unknown_subject_is_the_same_refusal_as_a_forbidden_one(world, client):
    """A cuid that names nothing, asked for by an ADMIN who may see everything.

    Same status, same sentence. If a missing id answered differently from a hidden one, the pair of
    responses would be a working existence oracle over the whole archive for any account with a
    login.
    """
    refused(
        client.get(
            "/api/design-ratings/subjects/ckmissingmissingmissing?round=PEER",
            headers=headers(world, "admin"),
        )
    )


def test_a_designer_cannot_rate_their_own_prototype(world, client):
    """403 here, and it is the one deliberate exception to this file's 404 rule.

    The caller demonstrably knows this record exists — they made it — so there is nothing left to
    disclose, and a 404 would send a designer hunting for a prototype that is on their own screen.
    See ``design_ratings.SELF_RATING_IS_REFUSED``, which is a named owner call.
    """
    response = client.post(
        "/api/design-ratings",
        json={"subjectId": prototype(world), "round": "PEER", "score": 5},
        headers=headers(world, "author"),
    )
    assert response.status_code == 403, response.text


def test_the_workshop_creator_may_rate_a_piece_they_did_not_draw(world, client):
    """THE OTHER SIDE OF THE SAME REFUSAL, and the bug a review found on this API.

    ``creator`` made the WORKSHOP; ``author`` drew the prototypes inside it. The self-rating rule is
    about the second, and reading it off the first locked the creator out of every piece in their
    own workshop — with the 403 above, telling an admin that somebody else's prototype was "your own
    record". Because only an ADMIN may start a workshop
    (``deps.can_create_design_workshops``), that was every workshop's own admin.

    This runs TODAY: the access decision is reached before the ledger, so :func:`admitted` accepts
    the 200 the write gets once the client is generated and the ledger's own 503 until then. It
    cannot be reached at all by anybody the rules refuse.
    """
    admitted(
        client.post(
            "/api/design-ratings",
            json={"subjectId": prototype(world), "round": "PEER", "score": 4},
            headers=headers(world, "creator"),
        )
    )


def test_an_outsider_writing_a_rating_is_refused_before_anything_is_written(world, client):
    """The write path's access check, which must run in front of the ledger and not behind it."""
    refused(
        client.post(
            "/api/design-ratings",
            json={
                "subjectId": prototype(world, STILL_IN_PEER_REVIEW),
                "round": "POOL",
                "score": 5,
            },
            headers=headers(world, "outsider"),
        )
    )


def test_an_outsider_cannot_rank_a_peer_round_they_are_not_in(world, client):
    refused(
        client.get(
            f"/api/design-ratings/rounds/PEER?workshopId={world['workshop']}"
            f"&entityKey={PROTOTYPE_ENTITY}",
            headers=headers(world, "outsider"),
        )
    )


def test_a_pool_ranking_with_nothing_opened_is_the_same_404_as_a_missing_workshop(world, client):
    """A collection where NOTHING has been dated, asked for by a stranger: 404, not an empty 200.

    A route that answered 200-with-no-items for every workshop id that exists and 404 for every one
    that does not is an enumeration oracle over the archive, which is the whole reason this API
    answers 404 rather than 403 in the first place.

    IT ASKS A SECOND WORKSHOP, and it has to. This used to be the main workshop's sketch collection,
    which had nothing opened for the structural reason that no sketch COULD be opened. Now that
    ``sketch`` carries ``peerRoundClosedAt`` the main workshop deliberately holds one dated sketch
    and one undated one, so its sketch collection is no longer the empty-to-a-stranger case — and a
    test that quietly became "a stranger sees the one open row" would have stopped asserting this
    at all. ``quietWorkshop`` exists to keep the case rather than to lose it.
    """
    refused(
        client.get(
            f"/api/design-ratings/rounds/POOL?workshopId={world['quietWorkshop']}"
            f"&entityKey={SKETCH_ENTITY}",
            headers=headers(world, "outsider"),
        )
    )


def test_an_unknown_round_is_a_422_and_names_the_two_that_exist(world, client):
    """422 rather than 404, and nothing is disclosed by it: an unknown round is a statement about
    the REQUEST, not about a record the caller may or may not be allowed to see."""
    response = client.get(
        f"/api/design-ratings/subjects/{prototype(world)}?round=FINAL",
        headers=headers(world, "admin"),
    )
    assert response.status_code == 422, response.text
    assert "PEER" in response.json()["detail"]
    assert "POOL" in response.json()["detail"]


def test_a_child_row_of_a_prototype_is_not_a_thing_that_gets_ranked(world, client):
    """Stage 13 also holds ``prototypeStageLog`` and ``materialUsage``.

    They are parts of a prototype, not competitors of one, and a ranking that mixed them would put
    material line-items in a list of designs with nothing on screen to tell them apart.
    """
    response = client.get(
        f"/api/design-ratings/rounds/PEER?workshopId={world['workshop']}"
        f"&entityKey=prototypeStageLog",
        headers=headers(world, "admin"),
    )
    assert response.status_code == 422, response.text


def test_an_anonymous_caller_reaches_nothing(world, client):
    """Also the guard that this router is MOUNTED.

    A route that does not exist answers 404, which every ``refused`` assertion in this module would
    happily accept — so a missing ``include_router`` would leave the refusal half green and prove
    nothing. An unauthenticated request to a mounted route answers 401 or 403 and to an unmounted
    one answers 404, which is the difference this test is here to catch.
    """
    for path in (
        f"/api/design-ratings/subjects/{prototype(world)}",
        f"/api/design-ratings/rounds/PEER?workshopId={world['workshop']}",
    ):
        assert client.get(path).status_code in (401, 403), path


# ------------------------------------------------------------------------------------------
# The admissions. Also unconditional — see :func:`admitted`.
# ------------------------------------------------------------------------------------------


def test_a_granted_peer_reaches_their_workshops_peer_round(world, client):
    admitted(
        client.get(
            f"/api/design-ratings/subjects/{prototype(world)}?round=PEER",
            headers=headers(world, "peer"),
        )
    )


def test_an_unrelated_designer_reaches_an_OPENED_prototypes_pool_round(world, client):
    """LEVEL 2, admitted. The same account refused above on its unfinished siblings.

    The pair is the point: one registry field of difference between two rows in ONE workshop,
    opposite answers. Either assertion alone would pass against a gate that was stuck open or stuck
    shut.
    """
    admitted(
        client.get(
            f"/api/design-ratings/subjects/{prototype(world, OPENED)}?round=POOL",
            headers=headers(world, "outsider"),
        )
    )


def test_a_finished_sketch_opens_to_the_pool_exactly_as_a_prototype_does(world, client):
    """The other direction on a SKETCH, and the whole point of declaring the switch there.

    An unrelated designer — no grant, no membership, refused this workshop's undated sketch two
    rows above — reaches a sketch somebody in the workshop has dated. ``sketchReview.reviewRound``
    and ``RATEABLE_ENTITIES`` have always assumed this request could succeed; until
    ``peerRoundClosedAt`` was appended to ``sketch`` it could not, and nothing failed to say so.
    """
    admitted(
        client.get(
            f"/api/design-ratings/subjects/{world['openedSketch']}?round=POOL",
            headers=headers(world, "outsider"),
        )
    )


def test_the_pool_being_open_on_a_piece_does_not_open_the_workshop(world, client):
    """A finished prototype is not a public workshop.

    The pool round is a wider AUDIENCE for one row, not a back door into the workshop's own internal
    review and emphatically not a widening of ``load_workshop_or_404``, which would have carried
    stage WRITES with it. Both halves are asserted: the peer round of the very piece they may rate,
    and the workshop itself.
    """
    refused(
        client.get(
            f"/api/design-ratings/subjects/{prototype(world, OPENED)}?round=PEER",
            headers=headers(world, "outsider"),
        )
    )
    refused(
        client.get(
            f"/api/design-workshops/{world['workshop']}",
            headers=headers(world, "outsider"),
        )
    )


def test_a_pool_ranking_shows_a_stranger_the_opened_piece_only(world, client):
    """The narrowing happens in the LIST, so this is where it has to be checked.

    A stranger ranking the pool round sees one row where a member sees three. Skipped until the
    ledger exists only because the body cannot be read before then; the ADMISSION half of it is
    already covered by the 404 test above.
    """
    body = client.get(
        f"/api/design-ratings/rounds/POOL?workshopId={world['workshop']}"
        f"&entityKey={PROTOTYPE_ENTITY}",
        headers=headers(world, "outsider"),
    )
    admitted(body)
    if not HAS_LEDGER:
        return
    assert [row["subjectId"] for row in body.json()["items"]] == [prototype(world, OPENED)]


def test_an_admin_reaches_every_round_of_every_piece(world, client):
    for ordinal in (OPENED, STILL_IN_PEER_REVIEW, ALSO_IN_PEER_REVIEW):
        for round_ in ("PEER", "POOL"):
            admitted(
                client.get(
                    f"/api/design-ratings/subjects/{prototype(world, ordinal)}?round={round_}",
                    headers=headers(world, "admin"),
                )
            )


# ------------------------------------------------------------------------------------------
# The round trip. Skipped until DwReviewRating exists — see the module docstring.
# ------------------------------------------------------------------------------------------


def _rate(world, slug: str, subject: str, score: int, *, rated_at=None, round_: str = "PEER"):
    body: dict[str, Any] = {
        "subjectId": subject,
        "round": round_,
        "score": score,
        "comment": "Rim is heavy",
        "suggestion": "Thin the rim by 2 mm",
    }
    if rated_at is not None:
        body["ratedAt"] = rated_at.isoformat()
    return world["client"].post(
        "/api/design-ratings", json=body, headers=headers(world, slug)
    )


@needs_ledger
def test_a_rating_round_trips_and_a_repeated_capture_writes_nothing(world, client):
    """The offline case, end to end: the outbox delivers the same capture twice.

    The second delivery must answer ``replayed: true`` with the stored row and leave exactly one
    rating behind. This repository has already shipped a double-filed record from an outbox that
    sent twice, which is why this is a round-trip test and not only a plan assertion.
    """
    subject = prototype(world, STILL_IN_PEER_REVIEW)
    moment = datetime.now(UTC) - timedelta(days=2)
    first = _rate(world, "peer", subject, 4, rated_at=moment)
    assert first.status_code == 200, first.text
    assert first.json()["replayed"] is False

    second = _rate(world, "peer", subject, 4, rated_at=moment)
    assert second.status_code == 200, second.text
    assert second.json()["replayed"] is True
    assert second.json()["rating"]["id"] == first.json()["rating"]["id"]

    ledger = client.get(
        f"/api/design-ratings/subjects/{subject}?round=PEER",
        headers=headers(world, "admin"),
    ).json()
    assert ledger["summary"]["ratingCount"] == 1


@needs_ledger
def test_a_stale_delivery_does_not_undo_an_amendment(world, client):
    """THE TUNNEL, end to end.

    Rate 5, amend to 3, then let the ORIGINAL capture arrive again. The stored score must still be
    3. Trusting arrival order makes that last delivery an UPDATE which silently restores the 5.
    """
    subject = prototype(world, ALSO_IN_PEER_REVIEW)
    original = datetime.now(UTC) - timedelta(days=3)
    amendment = datetime.now(UTC) - timedelta(days=2)
    assert _rate(world, "peer", subject, 5, rated_at=original).status_code == 200
    assert _rate(world, "peer", subject, 3, rated_at=amendment).status_code == 200

    late = _rate(world, "peer", subject, 5, rated_at=original)
    assert late.status_code == 200, late.text
    assert late.json()["replayed"] is True

    ledger = client.get(
        f"/api/design-ratings/subjects/{subject}?round=PEER",
        headers=headers(world, "admin"),
    ).json()
    assert ledger["summary"]["score"] == 3
    assert ledger["summary"]["ratingCount"] == 1


@needs_ledger
def test_a_peer_is_sent_the_average_and_their_own_row_and_no_other_row(world, client):
    """The owner's sentence, over the wire, with two reviewers on one prototype.

    The peer sees a count of 2 — they need the aggregate to rank — and exactly one ROW, their own.
    The other designer's row is absent from the response, not present and blanked: a hidden column
    is not a control, and a client cannot render what it was never sent.
    """
    subject = prototype(world, OPENED)
    assert _rate(world, "peer", subject, 5).status_code == 200
    assert _rate(world, "creator", subject, 3).status_code == 200

    body = client.get(
        f"/api/design-ratings/subjects/{subject}?round=PEER",
        headers=headers(world, "peer"),
    ).json()
    assert body["summary"]["ratingCount"] == 2
    assert body["summary"]["score"] == 4
    assert body["canReadLedger"] is False
    assert [row["mine"] for row in body["ratings"]] == [True]


@needs_ledger
def test_the_records_author_reads_the_whole_ledger_and_an_admin_sees_the_names(world, client):
    """"Designers see the same for their own records only", and the admin audit view beside it.

    The author of the prototype gets both rows; the admin gets both rows WITH names on them. The
    pool half of the identity question is the owner's call and lives in
    ``design_ratings.POOL_RATINGS_NAME_THEIR_RATER`` — asserted in both positions by
    ``test_design_ratings``, which can flip it without a database.
    """
    subject = prototype(world, OPENED)
    # WRITES ITS OWN ROWS RATHER THAN LEANING ON THE TEST ABOVE, which happens to leave the same
    # two. A module whose assertions depend on the order pytest happens to collect it in is the
    # suite that passes alone and fails in CI; re-posting the same two ratings is idempotent by
    # construction here — same reviewers, same round, same subject is an amendment of one row each.
    assert _rate(world, "peer", subject, 5).status_code == 200
    assert _rate(world, "creator", subject, 3).status_code == 200

    author_view = client.get(
        f"/api/design-ratings/subjects/{subject}?round=PEER",
        headers=headers(world, "author"),
    ).json()
    assert author_view["canReadLedger"] is True
    assert author_view["namesShown"] is True
    assert len(author_view["ratings"]) == 2

    admin_view = client.get(
        f"/api/design-ratings/subjects/{subject}?round=PEER",
        headers=headers(world, "admin"),
    ).json()
    assert {row["reviewerId"] for row in admin_view["ratings"]} == {
        world["people"]["peer"].id,
        world["people"]["creator"].id,
    }


@needs_ledger
def test_the_ranked_list_carries_both_orders(world, client):
    """*"sorted by score by default, with the designer having the final say"*.

    The list comes back in the designer's PLACED order — ``DwStageEntry.ordinal``, which the drag
    handles and the up/down arrows already write — and every row states where the scores alone would
    have put it. A client that only ever saw the sorted order could not show a designer that they
    had overruled the scores, which is the judgement the owner asked to have recorded.
    """
    items = client.get(
        f"/api/design-ratings/rounds/PEER?workshopId={world['workshop']}"
        f"&entityKey={PROTOTYPE_ENTITY}",
        headers=headers(world, "admin"),
    ).json()["items"]

    # THE PLACED ORDER IS EXACT, because it is a property of the fixture alone: three rows whose
    # ordinals disagree with the order they were inserted in.
    assert [row["placedPosition"] for row in items] == [1, 2, 3]
    assert [row["ordinal"] for row in items] == [0, 1, 2]

    # THE DEFAULT ORDER IS ASSERTED AS AN INVARIANT AGAINST THE SCORES IN THE SAME RESPONSE, and
    # deliberately not against numbers written here. The first spelling of this test hard-coded the
    # positions that the ratings left by the tests ABOVE happened to produce, which made the module
    # depend on the order pytest collected it in — the failure mode that passes alone and fails in
    # CI. Read this way it still catches every wiring mistake it was written for: a default order
    # that is not a permutation, one that does not follow the score, or one that promotes an unrated
    # piece above a rated one.
    assert sorted(row["defaultPosition"] for row in items) == [1, 2, 3]
    by_default = [row["score"] for row in sorted(items, key=lambda r: r["defaultPosition"])]
    rated = [score for score in by_default if score is not None]
    assert rated == sorted(rated, reverse=True), by_default
    assert all(score is None for score in by_default[len(rated):]), by_default


@needs_ledger
def test_a_peer_and_a_pool_rating_of_one_prototype_are_two_separate_judgements(world, client):
    """``round`` is part of the unique key, so the pool round does not overwrite the peer round.

    Two different audiences answering at two different times, and the model's own docstring says so:
    *"A workshop's own designer is also a member of the pool, and their peer-round view of a
    prototype and their pool-round view of the finished thing are two different judgements."*
    """
    subject = prototype(world, OPENED)

    def summary(round_: str, slug: str = "admin"):
        return client.get(
            f"/api/design-ratings/subjects/{subject}?round={round_}",
            headers=headers(world, slug),
        ).json()["summary"]

    # BEFORE AND AFTER, rather than against a number written here. What this test claims is that a
    # POOL rating does not disturb the PEER round — which is a statement about the DIFFERENCE, and
    # asserting it that way costs this module no dependence on which other tests have already rated
    # this piece.
    peer_before = summary("PEER")
    assert _rate(world, "outsider", subject, 2, round_="POOL").status_code == 200

    assert summary("PEER") == peer_before
    assert summary("POOL")["score"] == 2
    assert summary("POOL")["ratingCount"] == 1
