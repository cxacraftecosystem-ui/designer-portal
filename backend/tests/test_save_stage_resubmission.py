"""**WHO hands a sent-back report back in, and what the header write is not allowed to lose.**

The database half of the pre-submission loop's resubmission rule. ``test_design_workshop_review_loop``
is the source half — the call-site census, the signature, the shape of the two header writes — and
runs in CI with no Postgres. What only a database can answer is here: whether a row moved, whether a
counter with no inverse was spent, and whether a column survived a predicate that missed.

── THE TWO DEFECTS THIS MODULE EXISTS FOR, BOTH SHIPPED ON 2026-09-13 AND BOTH SILENT ─────────────

**1. ANY CALLER OF ``save_stage`` COULD RESUBMIT THE REPORT.** "When a workshop has been sent back,
the edit IS the resubmission" is a rule about a DESIGNER's edit, and ``save_stage`` cannot see who
edited: it takes ``user`` for provenance and carries no gate. For a day it inferred the answer from
having been reached at all, on the strength of a comment claiming every caller paired the designer
gate with ``load_workshop_or_404(..., for_edit=True)``. Two officer-driven callers landed in the same
wave — the artisan-roster upload and the designer reassignment, both MINISTRY_ADMIN-gated — and each
of them, on a workshop an inspector had just sent back, flipped the status to PRE_SUBMISSION, spent a
``submissionRound`` and nulled ``reviewNotes``/``reviewedById``/``reviewedAt``, before the designer
had read what was asked for. **The round is the half that cannot be undone**: nothing anywhere
decrements it, and ``DwInspectionFeedback.round`` is copied at write time and never recomputed, so
every later suggestion names a cycle nobody entered.

**2. THE COMPARE-AND-SET WAS SCOPED TO THE WHOLE HEADER.** The predicate that stops the round counter
moving twice (``status: NEEDS_REVISION``) was folded into the ONE ``update_many`` that also carried
``schemaVersion`` and all fourteen ``PROMOTED_COLUMNS``. When another writer moved the status first
the statement matched zero rows and wrote NOTHING — so the designer's corrected craft name, cluster,
state, district, venue and dates were dropped from ``DesignWorkshop`` while that same transaction's
``DwStageEntry`` rows committed. 200, empty ``errors``, and the workshop list, its filters, search,
the officer's oversight queue, the analytics rollup and the .xlsx export all left showing the value
the officer had asked to have corrected.

── WHY THIS DRIVES ``save_stage`` DIRECTLY AND NOT THE HTTP ROUTE ─────────────────────────────────

NEEDS_REVISION IS NOT REACHABLE FROM A HEADER PATCH — that is the point of the transition graph: it
is a decision edge, written only by ``POST /design-workshop-inspections/{id}/send-back``, which needs
an INSPECTOR account and an inspection assignment. Standing that whole scope up here would make this
module a second copy of ``test_dw_inspector_scope``'s fixtures, and the thing under test is not the
inspector's door. So the precondition is written as a row, the function is called with the argument
whose value is the subject, and the header is read back. The consequence is the loop rule in reverse:
this module owns its event loop (there is no ``TestClient``), so it awaits ``db`` freely — and it must
not be given a ``TestClient`` later without revisiting that.

The competing writer in the last test is INJECTED at ``hydrate_entries``, exactly as
``test_stage_version_guard`` injects its one: that await sits after the read the plan was built from
and before the transaction that applies it, which is the window, and injecting there is the only way
to land in it deterministically rather than by racing two clients and hoping.

    docker compose up -d postgres            # from the REPOSITORY ROOT, not from backend/
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

A client generated before 2026-09-13 has neither the NEEDS_REVISION status nor the review columns, so
every fixture here fails inside the driver rather than at an assertion. ``prisma generate`` is the fix.
"""

import uuid
from contextlib import suppress
from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import needs_db

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.core.security import hash_password
from app.schemas.design_workshops import StageEntryIn, StageSaveIn
from app.services import design_workshops as service

#: ``anyio`` for the reason every database module in this directory uses it: the connection fixture
#: is module-scoped and async, which needs one loop for the whole module.
pytestmark = pytest.mark.anyio

SETUP_STAGE = "WORKSHOP_SETUP"
SETUP_ENTITY = "workshopSetup"

#: The sentence an inspector left on the way out, and the columns that cache it. Named once so the
#: assertions below cannot drift into agreeing with whatever the code happened to write.
NOTE = "Stage 7's cost table does not add up."


@pytest.fixture
def anyio_backend():
    return "asyncio"


# ⚠ FUNCTION-SCOPED, NOT MODULE-SCOPED, AND THAT IS THE FIX FOR THE CROSS-LOOP FAILURES.
#
# `pyproject.toml` sets `asyncio_mode = "auto"` (pytest-asyncio) AND this module marks itself
# `pytest.mark.anyio`. Two async plugins are therefore live at once, and a MODULE-scoped async
# fixture ends up in a different event loop from the FUNCTION-scoped tests that use it. The symptom
# is exactly what CI reported: the first test fails with "bound to a different event loop" and the
# rest with "Event loop is closed", because the engine belongs to a loop that has already finished.
#
# Two earlier attempts missed this by looking at the wrong thing. The first assumed the module
# INHERITED a bad connection from a neighbour and added an `opened_here` guard; the second probed
# the inherited connection with `SELECT 1` and rebuilt on failure. Both went to CI and both came
# back with the three failures unchanged — which was the evidence that the loop mismatch is INSIDE
# this module, not handed to it. A liveness probe run in the fixture's loop cannot say anything
# about the tests' loop.
#
# Function scope costs three account rows per test instead of three per module, and buys the one
# thing that has to be true: the fixture and the test that uses it run in the same loop, whichever
# plugin ends up owning it. The tests are independent — every one mints its own accounts and its own
# workshop — so nothing was shared that this breaks.
#
# The convention this directory is migrating to (a SYNC fixture and `asyncio.run`, see
# `test_workshop_join_sync.py`) sidesteps the plugins entirely and remains the better answer for a
# module that also drives a `TestClient`. This one drives none, so it does not need that shape.
@pytest.fixture
async def connection():
    """One Prisma connection for the module — it does not close one it did not open, and it does
    not TRUST one it did not open either.

    ``db`` is a process-wide singleton shared with every other test module in the run. A blind
    ``connect()``/``disconnect()`` pair here would close the connection an earlier module is still
    using if it ran first, which is why ``opened_here`` exists at all.

    ── THE PROBE, AND EXACTLY HOW WELL IT IS ESTABLISHED ────────────────────────────────────────

    THE REPORTED SYMPTOM. This module has been seen failing a full-suite run at fixture setup with
    ``RuntimeError: <asyncio.locks.Event ...> is bound to a different event loop`` and
    ``RuntimeError: Event loop is closed`` — every test in the module erroring at once. The reading
    that fits is that it INHERITED a connected singleton whose engine belongs to an earlier
    module’s event loop: this module starts no ``TestClient``, so ``opened_here`` is False, it
    opens nothing of its own, and the first ``await`` lands on whatever it was handed.

    WHAT IS ACTUALLY PROVEN, from the installed client’s source (prisma 0.15.0):

    * ``Client.is_connected()`` is ``self._internal_engine is not None`` (``_base_client.py:180``)
      — an OBJECT check, not a liveness check. It cannot see which loop the engine was built in,
      so it cannot distinguish a usable inherited connection from an unusable one.
    * ``Client.disconnect()`` clears ``_internal_engine`` BEFORE awaiting ``engine.aclose()``
      (``_base_client.py:447``), so the client’s state is cleared even when that ``aclose()``
      raises. That is why the failure below is suppressed rather than handled.
    * ``Client.connect()`` then finds ``_internal_engine is None`` and builds a NEW engine
      (``_base_client.py:429``), in whichever loop is running at the time.

    So the suppressed-disconnect/reconnect pair reliably REPLACES an inherited engine with one this
    module built, which is why ``opened_here`` is then set to True: what we were declining to close
    no longer exists, and closing what we did build leaves the singleton as the next module expects.

    ⚠ WHAT IS **NOT** PROVEN, AND IS RECORDED HERE RATHER THAN IMPLIED AWAY. The symptom above was
    NOT reproduced on the machine this guard was written on, and the guard was therefore never
    observed to cure it. Two things got in the way, both measured: (1) that checkout was ten
    migrations behind, so every test here died earlier, in ``people``, on
    ``FieldNotFoundError: Could not find field at createOneUser.data.role`` — MINISTRY_ADMIN is
    added by ``20260913100200_ministry_admin_role``, which had not been applied; and (2) a
    synthetic module that deliberately left ``db`` connected across a module boundary did NOT
    produce the RuntimeError — the inherited engine went on working. So the leak that produces the
    reported failure is something more specific than "a module left the singleton connected", and
    it has not been named yet. Do not read this block as a diagnosis.

    WHAT THAT MAKES THIS: a cheap, well-understood seatbelt, not a fix. It costs one ``SELECT 1``
    on a healthy inherited connection and changes nothing about one. The module that leaves the
    singleton connected is still the thing to find and fix — see ``test_workshop_join_sync.py`` and
    ``test_sanction_orders.py`` for the convention this directory is migrating to (a SYNC module
    fixture, ``asyncio.run(seed())`` with connect+disconnect in a ``finally``, and only then a
    ``TestClient``); 44 modules here still hold the older shape, an ASYNC module fixture that both
    awaits ``db`` and holds a ``TestClient``.
    """
    # ── ALWAYS BUILD OUR OWN ENGINE. NO BORROWING, NO PROBE. ─────────────────────────────────────
    #
    # The probe this replaced ran `SELECT 1` on an inherited connection and rebuilt only if that
    # failed. It went to CI and the three cross-loop failures came back unchanged, so whatever this
    # module inherits does not fail a `SELECT 1` in the FIXTURE's loop and still fails in the
    # TESTS'. A liveness check in the wrong loop proves nothing about the right one.
    #
    # Unconditional is both simpler and sound, and it is sound for a reason rather than by luck:
    # pytest tears a module's fixtures down before the next module's run, so when this fixture opens
    # there is no other module still using the singleton. Anything still attached to it is a leak,
    # and the correct response to a leak is to replace it, not to test it.
    #
    # `disconnect()` clears `_internal_engine` BEFORE awaiting `engine.aclose()` (prisma 0.15.0,
    # _base_client.py:447), so the state is cleared even when that `aclose()` raises against a dead
    # loop — which is exactly why it is suppressed rather than handled. `connect()` then finds None
    # and builds a fresh engine in THIS loop (:429). We always own what we hand out, so the teardown
    # is unconditional too and the next module inherits a closed singleton.
    with suppress(Exception):
        await db.disconnect()
    await db.connect()
    try:
        yield db
    finally:
        with suppress(Exception):
            await db.disconnect()


@pytest.fixture
async def people(connection):
    """A designer, an inspector and an officer. Three accounts because all three appear in a header.

    The INSPECTOR is the one ``reviewedById`` names, so it has to be a real row: the column is a
    foreign key with ``onDelete: SetNull``, and a fabricated id would fail the insert rather than the
    assertion.
    """
    stamp = uuid.uuid4().hex[:8]

    async def account(slug: str, role: str, name: str) -> Any:
        return await db.user.create(
            data={
                "email": f"resubmit-{slug}-{stamp}@example.org",
                "name": name,
                "role": role,
                "passwordHash": hash_password("unused"),
            }
        )

    return {
        "designer": await account("designer", "DESIGNER", "Asha Patel"),
        "inspector": await account("inspector", "INSPECTOR", "Ravi Nair"),
        # MINISTRY_ADMIN and not ADMIN, deliberately: it is the role the two officer-driven callers
        # are actually gated on (`OVERSIGHT_ASSIGNER_ROLES`). It USED to be outside
        # `DESIGN_WORKSHOP_ROLES` too — the original defect was an account that could not run a
        # design workshop at all resubmitting its report — and it joined that set on 2026-09-14.
        # The test is unaffected: what it is about is the RESUBMISSION counter, and the officer is
        # still the officer.
        "officer": await account("officer", "MINISTRY_ADMIN", "A Ministry Officer"),
    }


def _spec(key: str) -> Any:
    from app.services.stage_schema import stages

    spec = next((s for s in stages() if s.key == key), None)
    assert spec is not None, f"{key} is no longer in the registry"
    return spec


def _setup_payload(data: dict[str, Any]) -> StageSaveIn:
    """A stage-1 save. ``workshopSetup`` is the singleton all fourteen promoted columns come from."""
    return StageSaveIn(
        entries=[StageEntryIn(entityKey=SETUP_ENTITY, ordinal=0, data=data)],
        replaceCollections=False,
        emptiedEntities=[],
        submit=False,
    )


async def _sent_back(people: dict[str, Any], **overrides: Any) -> Any:
    """A workshop in the state an inspector's send-back leaves: NEEDS_REVISION, round 2, cache set.

    Written as a row rather than reached through the send-back route for the reason in the module
    docstring. The ROUND IS 2 AND NOT 1 on purpose — a counter that must not move is only observable
    once it holds a value nothing else would have produced.
    """
    data: dict[str, Any] = {
        "title": "Bargarh ikat 2026",
        "createdById": people["designer"].id,
        "status": "NEEDS_REVISION",
        "submissionRound": 2,
        "reviewNotes": NOTE,
        "reviewedById": people["inspector"].id,
        "reviewedAt": datetime.now(UTC),
    }
    data.update(overrides)
    return await db.designworkshop.create(data=data)


async def _header(workshop_id: str) -> Any:
    return await db.designworkshop.find_unique(where={"id": workshop_id})


# --------------------------------------------------------------------------------------
# 1. WHO may resubmit
# --------------------------------------------------------------------------------------


@needs_db
async def test_an_officers_write_stores_the_content_and_does_not_hand_the_report_back_in(people):
    """**THE DEFECT, EXACTLY AS THE OFFICER PERFORMS IT.**

    An inspector sends the report back asking for the artisan list to be completed. The designer
    cannot upload it — that route is assigner-gated — so the Ministry Admin does, and the roster write
    goes through ``save_stage``. Before the fix that upload flipped the status, spent round 3 and
    cleared the send-back off the header, with the cost table still wrong and the designer not yet
    having opened the report.

    BOTH HALVES ARE ASSERTED, and the second is the one that makes this a fix rather than a refusal:
    the officer's write must still STORE. Suppressing the resubmission is not suppressing the save.
    """
    workshop = await _sent_back(people)
    result = await service.save_stage(
        workshop.id,
        _spec(SETUP_STAGE),
        _setup_payload({"workshopTitle": "Bargarh ikat cover", "craftName": "Sambalpuri Ikat"}),
        people["officer"],
    )
    assert result["errors"] == {}, result["errors"]

    after = await _header(workshop.id)
    assert str(after.status) == "NEEDS_REVISION", (
        "an officer's bookkeeping write handed the designer's report back in. See the `resubmits` "
        "keyword: the actor is the caller's to declare, not save_stage's to infer."
    )
    assert after.submissionRound == 2, (
        "a submission round was spent by somebody who was not answering the inspector. Nothing "
        "decrements this counter, and DwInspectionFeedback.round is copied from it and never "
        "recomputed, so every later suggestion would name a cycle nobody entered."
    )
    assert after.reviewNotes == NOTE, "what was asked for was cleared off the header"
    assert after.reviewedById == people["inspector"].id, "who asked for it was cleared off the header"
    assert after.reviewedAt is not None
    # AND THE WRITE ITSELF LANDED. The officer's row is stored and the promoted columns moved with
    # it; what was suppressed is the status transition alone.
    assert after.craftName == "Sambalpuri Ikat"
    assert after.title == "Bargarh ikat cover"


@needs_db
async def test_the_designers_edit_is_the_resubmission(people):
    """The rule itself, which the fix must not have broken: the designer's edit hands it back in.

    ``resubmits=True`` is stated by ``api/routes/design_workshops.save_stage_data`` and by nothing
    else — it is the only call site holding both the designer gate and ``for_edit=True``, which is
    what establishes the actor is one of this workshop's editing party.
    """
    workshop = await _sent_back(people)
    await service.save_stage(
        workshop.id,
        _spec(SETUP_STAGE),
        _setup_payload({"workshopTitle": "Bargarh ikat cover", "craftName": "Sambalpuri Ikat"}),
        people["designer"],
        resubmits=True,
    )

    after = await _header(workshop.id)
    assert str(after.status) == "PRE_SUBMISSION"
    assert after.submissionRound == 3, "the edit IS the resubmission and spends exactly one round"
    # THE DECISION CACHE IS CLEARED, AND THAT IS ONLY SAFE BECAUSE THE REGISTER EXISTS: every
    # sentence these columns held is also a DwInspectionFeedback row, and the designer's panel is
    # built from the rows and never from the cache.
    assert after.reviewNotes is None
    assert after.reviewedById is None
    assert after.reviewedAt is None


@needs_db
async def test_a_save_that_changed_nothing_does_not_spend_a_round(people):
    """The fourth refusal: A SAVE THAT WROTE NOTHING IS NOT AN EDIT.

    A form opened and saved, an offline outbox replaying a byte-identical body, a save whose every
    row lost the version race. Asserted here with a real second save of a stored payload, because
    ``_content_changed`` on its own cannot show that the plan the transaction sees is the one that
    reaches the gate.
    """
    workshop = await _sent_back(people, status="IN_PROGRESS", submissionRound=0, reviewNotes=None)
    payload = {"workshopTitle": "Bargarh ikat cover", "craftName": "Sambalpuri Ikat"}
    await service.save_stage(
        workshop.id, _spec(SETUP_STAGE), _setup_payload(payload), people["designer"], resubmits=True
    )
    await db.designworkshop.update(
        where={"id": workshop.id},
        data={"status": "NEEDS_REVISION", "submissionRound": 2, "reviewNotes": NOTE},
    )

    await service.save_stage(
        workshop.id, _spec(SETUP_STAGE), _setup_payload(payload), people["designer"], resubmits=True
    )

    after = await _header(workshop.id)
    assert str(after.status) == "NEEDS_REVISION", (
        "re-sending the stored answers read as a resubmission. An offline replay is the most "
        "ordinary path this table has."
    )
    assert after.submissionRound == 2
    assert after.reviewNotes == NOTE


# --------------------------------------------------------------------------------------
# 2. What the compare-and-set may cover
# --------------------------------------------------------------------------------------


@needs_db
async def test_a_status_flip_inside_the_window_costs_the_counter_and_not_the_corrections(
    people, monkeypatch
):
    """**THE SECOND DEFECT, END TO END: the predicate misses and the content must still land.**

    The designer's laptop saves stage 1 with the corrected craft name while their phone's outbox
    flushes another stage's edit. Both read ``NEEDS_REVISION``; the phone commits first and moves the
    row to PRE_SUBMISSION. The laptop's header write then runs under ``status: NEEDS_REVISION`` and
    matches nothing.

    What the predicate is FOR is the counter: ``submissionRound`` moves with ``{"increment": 1}``,
    a read-modify-write, and two writers each applying it would move it by two for one act. What it
    must NOT cover is ``schemaVersion`` and the fourteen promoted columns, which is what the merged
    single statement did — dropping the correction from the header while its ``DwStageEntry`` row
    committed, so stage 1 said Bandhej and every list, filter, search bucket, oversight queue and
    export said Sambalpuri Ikat, for the life of the workshop or until somebody saved stage 1 again.

    The competitor is injected at ``hydrate_entries`` — the await after the read the plan was built
    from and before the transaction that applies it — which is how ``test_stage_version_guard``
    lands in this same window deterministically instead of racing two clients and hoping.
    """
    workshop = await _sent_back(people, craftName="Sambalpuri Ikat")
    original = service.hydrate_entries
    landed: list[str] = []

    async def wrapper(pending: Any, **kwargs: Any) -> Any:
        result = await original(pending, **kwargs)
        if not landed:
            landed.append(workshop.id)
            # THE OTHER REQUEST, COMMITTING FIRST. Written exactly as the resubmission arm writes it,
            # predicate included, so this stands in for a real second save rather than for a
            # hand-made state nothing produces. ONCE — save_stage re-runs this whole block on a
            # version conflict, and a competitor that fired every attempt would model no machine.
            await db.designworkshop.update_many(
                where={"id": workshop.id, "status": "NEEDS_REVISION"},
                data={
                    "status": "PRE_SUBMISSION",
                    "submissionRound": {"increment": 1},
                    "reviewNotes": None,
                    "reviewedById": None,
                    "reviewedAt": None,
                },
            )
        return result

    monkeypatch.setattr(service, "hydrate_entries", wrapper)

    result = await service.save_stage(
        workshop.id,
        _spec(SETUP_STAGE),
        _setup_payload({"workshopTitle": "Bandhej cover", "craftName": "Bandhej"}),
        people["designer"],
        resubmits=True,
    )
    assert landed, "the competing write never fired; this test proved nothing"
    assert result["errors"] == {}, result["errors"]

    after = await _header(workshop.id)
    assert after.craftName == "Bandhej", (
        "the designer's correction was dropped from the header because the status predicate missed. "
        "The compare-and-set belongs on the transition keys alone — the promoted columns must be "
        "written by id."
    )
    assert after.title == "Bandhej cover"
    assert after.schemaVersion is not None, (
        "schemaVersion rode with the promoted columns in the merged statement and was lost with them"
    )
    assert str(after.status) == "PRE_SUBMISSION"
    assert after.submissionRound == 3, (
        "the round moved twice for one act. The predicate on the transition write is what makes the "
        "loser of this race write nothing, and it must stay."
    )
