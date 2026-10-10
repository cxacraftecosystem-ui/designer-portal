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
whose value is the subject, and the header is read back — with no ``TestClient`` anywhere, which
turned out NOT to be what decides how this module may reach the database. The next section says what
does.

The competing writer in the last test is INJECTED at ``hydrate_entries``, exactly as
``test_stage_version_guard`` injects its one: that await sits after the read the plan was built from
and before the transaction that applies it, which is the window, and injecting there is the only way
to land in it deterministically rather than by racing two clients and hoping.

── HOW THIS MODULE REACHES THE DATABASE, AND THE THREE CURES THAT WERE NOT ONE ────────────────────

Every test is SYNC. Its database work — the precondition row, ``save_stage``, the read-back — is one
coroutine handed to :func:`_in_a_private_loop`, which runs it under ``asyncio.run`` between a blind
``db.connect()`` and a ``db.disconnect()`` in a ``finally``; ``people`` seeds its three accounts the
same way and returns them as values. That is the shape ``conftest.py`` names under "HOW A
DATABASE-BACKED MODULE IS SUPPOSED TO REACH THE DATABASE", and this module is its shortest example.

UNTIL 2026-10-09 IT SAID THE OPPOSITE — no ``TestClient``, so it "owned its event loop" and could
await ``db`` freely from an async ``connection`` fixture and async tests — and CI disagreed with
``RuntimeError: <asyncio.locks.Event ...> is bound to a different event loop``, raised inside the
Prisma client's httpx pool when a test's first query reused the keep-alive connection the fixture's
``db.connect()`` had opened on ANOTHER loop. Three cures were tried and recorded here. An
``opened_here`` guard and then a ``SELECT 1`` probe were both aimed at a connection supposedly
inherited from a neighbouring module, and changed nothing. FUNCTION scope did cure what it was aimed
at — a module-scoped fixture's loop against the function-scoped tests' — but only for as long as
pytest-asyncio was the plugin running the fixture: green on the CI of 2026-09-21, then red, all four
tests, on every CI run from 2026-10-01 on — PR #24's run 37916529287 among them — with no commit in
between.

The real split was between two plugins, not two scopes. pytest-asyncio (``asyncio_mode = "auto"``)
runs every async test on its own loop; an async fixture in a ``pytest.mark.anyio`` module goes to
whichever of the two plugins pytest registered LAST, and anyio runs it on a loop of its own. Which
one that is comes from an unsorted directory listing on the runner, and pytest prints it:
``plugins: anyio-4.14.2, asyncio-1.4.0`` on the last green main run, ``plugins: asyncio-1.4.0,
anyio-4.14.2`` on every red one. A probe run in the fixture's loop could see none of that, and no
scope changes which plugin owns the fixture. ``conftest.py`` records the measurement and the local
reproduction.

This shape is immune by construction rather than by luck: there is no async fixture for either
plugin to take and no async test for one to run, and ``asyncio.run`` makes a loop nothing else
shares and closes it, with the connection it opened, before anything else runs. If this module is
ever given a ``TestClient``, keep every database call out of the time a client is alive — the longer
form ``conftest.py`` points at in ``test_sanction_orders.py``.

    docker compose up -d postgres            # from the REPOSITORY ROOT, not from backend/
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

A client generated before 2026-09-13 has neither the NEEDS_REVISION status nor the review columns, so
every fixture here fails inside the driver rather than at an assertion. ``prisma generate`` is the fix.
"""

import asyncio
import uuid
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import needs_db

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.core.security import hash_password
from app.schemas.design_workshops import StageEntryIn, StageSaveIn
from app.services import design_workshops as service

SETUP_STAGE = "WORKSHOP_SETUP"
SETUP_ENTITY = "workshopSetup"

#: The sentence an inspector left on the way out, and the columns that cache it. Named once so the
#: assertions below cannot drift into agreeing with whatever the code happened to write.
NOTE = "Stage 7's cost table does not add up."


def _in_a_private_loop[T](work: Callable[[], Awaitable[T]]) -> T:
    """Run database work with ``db`` connected, in an event loop that exists for that call alone.

    ``asyncio.run`` creates the loop and closes it on the way out, and the connection is opened and
    closed inside it, so nothing the work touched — the query engine, the httpx pool in front of it,
    the events its streams wait on — can be reached from any other loop afterwards. The connect is
    BLIND and the disconnect UNCONDITIONAL, which is right here and only here: no other loop holds
    ``db`` while this one runs, and the next call connects afresh. It replaces the old
    ``connection`` fixture's ``opened_here`` bookkeeping: inside a loop of its own there is nobody
    else's connection to preserve.
    """

    async def connected() -> T:
        await db.connect()
        try:
            return await work()
        finally:
            await db.disconnect()

    return asyncio.run(connected())


@pytest.fixture
def people() -> dict[str, Any]:
    """A designer, an inspector and an officer. Three accounts because all three appear in a header.

    The INSPECTOR is the one ``reviewedById`` names, so it has to be a real row: the column is a
    foreign key with ``onDelete: SetNull``, and a fabricated id would fail the insert rather than the
    assertion.

    SYNC, seeded in an ``asyncio.run`` of its own and returned as values — Prisma's model objects
    hold no client and no connection, so they mean the same thing in the test's loop. FUNCTION-
    SCOPED, so every test mints its own accounts as well as its own workshop and no test can see a
    row another one wrote.
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

    async def seed() -> dict[str, Any]:
        return {
            "designer": await account("designer", "DESIGNER", "Asha Patel"),
            "inspector": await account("inspector", "INSPECTOR", "Ravi Nair"),
            # MINISTRY_ADMIN and not ADMIN, deliberately: it is the role the two officer-driven
            # callers are actually gated on (`OVERSIGHT_ASSIGNER_ROLES`). It USED to be outside
            # `DESIGN_WORKSHOP_ROLES` too — the original defect was an account that could not run a
            # design workshop at all resubmitting its report — and it joined that set on 2026-09-14.
            # The test is unaffected: what it is about is the RESUBMISSION counter, and the officer
            # is still the officer.
            "officer": await account("officer", "MINISTRY_ADMIN", "A Ministry Officer"),
        }

    return _in_a_private_loop(seed)


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
def test_an_officers_write_stores_the_content_and_does_not_hand_the_report_back_in(people):
    """**THE DEFECT, EXACTLY AS THE OFFICER PERFORMS IT.**

    An inspector sends the report back asking for the artisan list to be completed. The designer
    cannot upload it — that route is assigner-gated — so the Ministry Admin does, and the roster write
    goes through ``save_stage``. Before the fix that upload flipped the status, spent round 3 and
    cleared the send-back off the header, with the cost table still wrong and the designer not yet
    having opened the report.

    BOTH HALVES ARE ASSERTED, and the second is the one that makes this a fix rather than a refusal:
    the officer's write must still STORE. Suppressing the resubmission is not suppressing the save.
    """

    async def scenario() -> None:
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
            "an officer's bookkeeping write handed the designer's report back in. See the "
            "`resubmits` keyword: the actor is the caller's to declare, not save_stage's to infer."
        )
        assert after.submissionRound == 2, (
            "a submission round was spent by somebody who was not answering the inspector. Nothing "
            "decrements this counter, and DwInspectionFeedback.round is copied from it and never "
            "recomputed, so every later suggestion would name a cycle nobody entered."
        )
        assert after.reviewNotes == NOTE, "what was asked for was cleared off the header"
        assert after.reviewedById == people["inspector"].id, (
            "who asked for it was cleared off the header"
        )
        assert after.reviewedAt is not None
        # AND THE WRITE ITSELF LANDED. The officer's row is stored and the promoted columns moved
        # with it; what was suppressed is the status transition alone.
        assert after.craftName == "Sambalpuri Ikat"
        assert after.title == "Bargarh ikat cover"

    _in_a_private_loop(scenario)


@needs_db
def test_the_designers_edit_is_the_resubmission(people):
    """The rule itself, which the fix must not have broken: the designer's edit hands it back in.

    ``resubmits=True`` is stated by ``api/routes/design_workshops.save_stage_data`` and by nothing
    else — it is the only call site holding both the designer gate and ``for_edit=True``, which is
    what establishes the actor is one of this workshop's editing party.
    """

    async def scenario() -> None:
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
        assert after.submissionRound == 3, (
            "the edit IS the resubmission and spends exactly one round"
        )
        # THE DECISION CACHE IS CLEARED, AND THAT IS ONLY SAFE BECAUSE THE REGISTER EXISTS: every
        # sentence these columns held is also a DwInspectionFeedback row, and the designer's panel
        # is built from the rows and never from the cache.
        assert after.reviewNotes is None
        assert after.reviewedById is None
        assert after.reviewedAt is None

    _in_a_private_loop(scenario)


@needs_db
def test_a_save_that_changed_nothing_does_not_spend_a_round(people):
    """The fourth refusal: A SAVE THAT WROTE NOTHING IS NOT AN EDIT.

    A form opened and saved, an offline outbox replaying a byte-identical body, a save whose every
    row lost the version race. Asserted here with a real second save of a stored payload, because
    ``_content_changed`` on its own cannot show that the plan the transaction sees is the one that
    reaches the gate.
    """

    async def scenario() -> None:
        workshop = await _sent_back(
            people, status="IN_PROGRESS", submissionRound=0, reviewNotes=None
        )
        payload = {"workshopTitle": "Bargarh ikat cover", "craftName": "Sambalpuri Ikat"}
        await service.save_stage(
            workshop.id,
            _spec(SETUP_STAGE),
            _setup_payload(payload),
            people["designer"],
            resubmits=True,
        )
        await db.designworkshop.update(
            where={"id": workshop.id},
            data={"status": "NEEDS_REVISION", "submissionRound": 2, "reviewNotes": NOTE},
        )

        await service.save_stage(
            workshop.id,
            _spec(SETUP_STAGE),
            _setup_payload(payload),
            people["designer"],
            resubmits=True,
        )

        after = await _header(workshop.id)
        assert str(after.status) == "NEEDS_REVISION", (
            "re-sending the stored answers read as a resubmission. An offline replay is the most "
            "ordinary path this table has."
        )
        assert after.submissionRound == 2
        assert after.reviewNotes == NOTE

    _in_a_private_loop(scenario)


# --------------------------------------------------------------------------------------
# 2. What the compare-and-set may cover
# --------------------------------------------------------------------------------------


@needs_db
def test_a_status_flip_inside_the_window_costs_the_counter_and_not_the_corrections(
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

    async def scenario() -> None:
        workshop = await _sent_back(people, craftName="Sambalpuri Ikat")
        original = service.hydrate_entries
        landed: list[str] = []

        async def wrapper(pending: Any, **kwargs: Any) -> Any:
            result = await original(pending, **kwargs)
            if not landed:
                landed.append(workshop.id)
                # THE OTHER REQUEST, COMMITTING FIRST. Written exactly as the resubmission arm
                # writes it, predicate included, so this stands in for a real second save rather
                # than for a hand-made state nothing produces. ONCE — save_stage re-runs this whole
                # block on a version conflict, and a competitor that fired every attempt would
                # model no machine.
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
            "the designer's correction was dropped from the header because the status predicate "
            "missed. The compare-and-set belongs on the transition keys alone — the promoted "
            "columns must be written by id."
        )
        assert after.title == "Bandhej cover"
        assert after.schemaVersion is not None, (
            "schemaVersion rode with the promoted columns in the merged statement and was lost "
            "with them"
        )
        assert str(after.status) == "PRE_SUBMISSION"
        assert after.submissionRound == 3, (
            "the round moved twice for one act. The predicate on the transition write is what "
            "makes the loser of this race write nothing, and it must stay."
        )

    _in_a_private_loop(scenario)
