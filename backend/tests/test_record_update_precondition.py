"""``expectedUpdatedAt`` — the precondition that stops a queued correction overwriting in silence.

WHAT THIS DEFENDS
==================

A queued correction replays a WHOLE create-shaped body through the record's PATCH route with no
precondition of any kind. Android's ``offlineSavedMessage`` documents what that costs, in the file
itself:

    "`writeFromEntry` replays it as a whole create-shaped body through
     `updateArtisan`/`updateProduct`/… with no version and no `If-Match`, so a correction composed on
     the bus and drained hours later overwrites anything anybody else changed in between, field for
     field, with nobody told."

and it names its own closing condition, which is this field:

    "Closing it properly needs the record's version as the queued write's precondition, exactly as
     the custom questionnaire's write does. Until then this sentence is the whole of the warning."

WHAT IS PINNED HERE
====================

* an ABSENT precondition passes — the behaviour of every client shipped to date, which is why no
  fielded 0.0.7 APK and no cached web bundle can be refused by this change;
* a MATCHING one passes, including across the serialisation precision a real round trip loses;
* a STALE one is a 409 whose detail is one quotable sentence plus both timestamps;
* the field never reaches the database as a column, on any of the six routes;
* the refusal is raised ABOVE the ledger write, so a turned-down edit leaves no audit row claiming it
  happened.

WHY A TIMESTAMP HERE WHEN ``DwStageEntry`` WAS DELIBERATELY GIVEN A COUNTER
===========================================================================

``20260903090000_dw_stage_entry_version`` chose an integer and its reasoning is sound: *"two writes
inside one millisecond are indistinguishable through it."* That argument is accepted and this is
still a timestamp, for reasons about these six tables rather than about timestamps — ``updatedAt``
already exists on all six and is already in every response; the window here is HOURS (a correction
composed in a courtyard, drained on the bus home) rather than the sub-second read/write gap inside
``save_stage``; and a counter is six more columns plus a bump every one of six routes must remember
for ever, where a bump one writer forgets is a guard that silently stops guarding. The full argument
is in ``records.EXPECTED_UPDATED_AT_TOLERANCE``. This is a NARROWING, not a promise, and the tests
below say so where they touch the boundary.

NO DATABASE. The decision is pure and the routes' use of it is a question of ORDER, which a fake
delegate answers better than Postgres can: a fake can be made to explode if the ledger is written
before the refusal.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from app.schemas.records import (
    ArtisanUpdate,
    CraftUpdate,
    ProcessUpdate,
    ProductUpdate,
    ToolUpdate,
    WorkshopUpdate,
)
from app.services import records as records_service

#: The six PATCH bodies that carry the precondition. Six and not four: the key answers a question
#: about an EDIT, and all six record types accept a queued correction — unlike ``clientKey``, which
#: answers a question about a CREATE and is only on the four the identity keys do not already guard.
UPDATE_SCHEMAS = (
    ArtisanUpdate,
    CraftUpdate,
    WorkshopUpdate,
    ProductUpdate,
    ToolUpdate,
    ProcessUpdate,
)

STORED_AT = datetime(2026, 9, 3, 10, 0, 0, tzinfo=UTC)


def _record(updated_at: Any = STORED_AT) -> Any:
    """A stored row carrying exactly what the precondition reads off it."""
    return SimpleNamespace(id="rec-1", updatedAt=updated_at)


# --------------------------------------------------------------------------------------
# The schemas
# --------------------------------------------------------------------------------------


def test_all_six_update_schemas_accept_the_precondition_and_default_it_to_absent():
    for schema in UPDATE_SCHEMAS:
        model = schema()
        assert model.expectedUpdatedAt is None, schema.__name__
        # And it must be genuinely UNSET rather than merely None, because every one of these routes
        # dumps with ``exclude_unset=True`` — an "unset" that arrived as an explicit key would put
        # ``expectedUpdatedAt`` into ``data`` on every PATCH in the app.
        assert "expectedUpdatedAt" not in model.model_dump(exclude_unset=True), schema.__name__


def test_the_precondition_parses_the_timestamp_the_api_itself_emits():
    # Not a free-text string: it is compared as a moment, so it is parsed as one. The value a client
    # echoes back is the one this API sent, which ``jsonable_encoder`` renders in ISO-8601 with an
    # offset.
    parsed = ProductUpdate(expectedUpdatedAt="2026-09-03T10:00:00+00:00")
    assert parsed.expectedUpdatedAt == STORED_AT
    # And the "Z" spelling a browser's ``toISOString`` produces is the same moment.
    assert ProductUpdate(expectedUpdatedAt="2026-09-03T10:00:00Z").expectedUpdatedAt == STORED_AT


def test_an_unparseable_precondition_is_refused_rather_than_ignored():
    # A 422 naming the field, not a silent pass. Ignoring it would answer 200 to a caller who
    # believes their edit was guarded — the "exit zero is not evidence" shape, wearing a precondition.
    with pytest.raises(ValidationError, match="expectedUpdatedAt"):
        ProductUpdate(expectedUpdatedAt="last tuesday")


# --------------------------------------------------------------------------------------
# Taking it out of the body — it is a question, not a column
# --------------------------------------------------------------------------------------


def test_the_precondition_is_popped_out_of_the_body_before_anything_reads_it():
    """It is not a column on any of these six tables, and everything downstream treats ``data`` as
    columns: ``guard_record_edit`` diffs it into a ``RecordRevision``, ``merge_field_provenance``
    stamps a contributor against every key it holds, and Prisma is finally handed it. A field that
    survived into any one of those would be an audit entry for an edit nobody made, a provenance
    stamp on a field that does not exist, or a 500 naming a column that has never existed.
    """
    data: dict[str, Any] = {"notes": "corrected", "expectedUpdatedAt": STORED_AT}
    taken = records_service.take_expected_updated_at(data)
    assert taken == STORED_AT
    assert data == {"notes": "corrected"}, "the key is gone, and nothing else moved"


def test_taking_an_absent_precondition_answers_none_and_leaves_the_body_alone():
    data: dict[str, Any] = {"notes": "corrected"}
    assert records_service.take_expected_updated_at(data) is None
    assert data == {"notes": "corrected"}


def test_a_non_datetime_that_somehow_reached_the_body_is_dropped_rather_than_compared():
    # Pydantic has already refused anything unparseable, so this cannot arrive through a route. It is
    # dropped rather than trusted because the alternative — comparing a string to a datetime — is a
    # TypeError inside a transaction, which reaches a designer as a bare 500 on a save.
    data: dict[str, Any] = {"expectedUpdatedAt": "2026-09-03T10:00:00Z"}
    assert records_service.take_expected_updated_at(data) is None
    assert "expectedUpdatedAt" not in data, "still popped, so it can never reach Prisma"


# --------------------------------------------------------------------------------------
# The comparison
# --------------------------------------------------------------------------------------


def test_no_precondition_passes_which_is_every_client_shipped_to_date():
    """THE COMPATIBILITY CLAIM, AND IT IS THE ONLY ONE THAT DECIDES WHETHER THIS IS SAFE TO SHIP.

    A handset that has been out of coverage for a fortnight is running 0.0.7 and sends nothing here.
    A browser holding a cached bundle sends nothing here. Neither can be refused by this function,
    however stale their correction is — which means the change adds a guard without taking away a
    single save that works today. Only a caller that OPTS IN by sending the value can meet the 409.
    """
    records_service.assert_expected_updated_at(_record(), None)


def test_an_exact_match_passes():
    records_service.assert_expected_updated_at(_record(), STORED_AT)


def test_a_precision_loss_a_real_round_trip_produces_still_matches():
    """The failure this tolerance exists to prevent, spelled as the case that produces it.

    Prisma maps ``DateTime`` to Postgres ``timestamp(3)``; a browser round-tripping the value through
    ``Date`` lands on milliseconds; a handset through ``java.time.Instant`` keeps what it was given.
    So a true match can arrive a fraction of a second away from the stored value — and reporting THAT
    as a conflict would park a designer's queued correction behind a comparison they cannot see,
    cannot fix and did not cause, on a device with no signal.
    """
    stored = datetime(2026, 9, 3, 10, 0, 0, 123456, tzinfo=UTC)
    truncated_to_milliseconds = datetime(2026, 9, 3, 10, 0, 0, 123000, tzinfo=UTC)
    records_service.assert_expected_updated_at(_record(stored), truncated_to_milliseconds)
    # And a whole second either way, which is the stated size of the margin.
    records_service.assert_expected_updated_at(_record(stored), stored - timedelta(seconds=1))
    records_service.assert_expected_updated_at(_record(stored), stored + timedelta(seconds=1))


def test_a_naive_timestamp_is_read_as_utc_rather_than_refused():
    # Every value this can be compared against was produced by this API, which encodes UTC. Refusing
    # a correction over a missing "Z" would lose fieldwork to punctuation.
    records_service.assert_expected_updated_at(_record(), STORED_AT.replace(tzinfo=None))
    # And the mirror: a stored value without a zone (a driver or a fixture that hands one back naive)
    # is read the same way rather than raising a TypeError inside the transaction.
    records_service.assert_expected_updated_at(_record(STORED_AT.replace(tzinfo=None)), STORED_AT)


def test_a_stale_precondition_is_a_409_carrying_one_quotable_sentence_and_both_times():
    """THE SHAPE OF THE REFUSAL, WHICH IS READ BY A PERSON STANDING IN A COURTYARD.

    Android's ``outboxConflictSentence`` embeds the server's ``detail`` VERBATIM between its own
    clauses, so this has to read as one self-contained sentence in the middle of a paragraph — not a
    heading, and not an instruction competing with the remedy that sentence already gives ("Open the
    clashing record, make the change there, then discard this entry").

    Both timestamps are returned because the designer's next act is to compare, and a refusal that
    says only "it changed" gives them nothing to compare against.
    """
    stale = STORED_AT - timedelta(hours=2)
    with pytest.raises(HTTPException) as caught:
        records_service.assert_expected_updated_at(_record(), stale)
    assert caught.value.status_code == 409
    detail = caught.value.detail
    assert detail["code"] == "record_changed"
    assert detail["message"] == "Someone else changed this record after this edit was composed."
    # One sentence. A tray row that has to be read twice is a tray row that gets read none, and the
    # button under it deletes the only copy of a day's fieldwork.
    assert detail["message"].count(".") == 1
    assert detail["expectedUpdatedAt"] == stale.isoformat()
    assert detail["currentUpdatedAt"] == STORED_AT.isoformat()


def test_the_code_tells_this_409_apart_from_the_identity_and_name_clashes():
    """Three routes can now answer 409 for two different reasons, and a client must not have to guess.

    ``artisans.py`` answers ``artisan_identity_conflict`` for a taken Aadhaar and ``crafts.py``
    answers a taken name; both are "somebody else's record is in the way". This one is "the record
    you are editing has moved on". The status is deliberately the same — both are answered 409, both
    are parked by the same outbox machinery, and both end in the designer comparing two versions with
    their own eyes — so ``code`` is what separates them for anything that needs to.
    """
    with pytest.raises(HTTPException) as caught:
        records_service.assert_expected_updated_at(_record(), STORED_AT - timedelta(days=1))
    assert caught.value.detail["code"] == "record_changed"
    assert caught.value.detail["code"] != "artisan_identity_conflict"


def test_a_row_with_no_stored_timestamp_passes_rather_than_inventing_a_refusal():
    # Nothing to compare against is not evidence of a conflict. Refusing here would park a correction
    # over the server's own gap — a designer's fieldwork held for a reason that is not about their
    # record at all.
    records_service.assert_expected_updated_at(_record(None), STORED_AT)
    records_service.assert_expected_updated_at(SimpleNamespace(id="rec-1"), STORED_AT)


def test_the_tolerance_is_documented_as_a_narrowing_and_not_as_a_promise():
    """The boundary this guard deliberately does NOT close, asserted so nobody reads it as closed.

    A competing write inside the tolerance passes unnoticed — which is exactly today's behaviour and
    therefore not a regression, and it is the trade ``EXPECTED_UPDATED_AT_TOLERANCE`` makes on
    purpose: a FALSE CONFLICT costs a designer their queued fieldwork, and a false pass costs nothing
    that is not already being lost. Anyone who needs the stronger guarantee wants the counter
    ``DwStageEntry.version`` took, and that is a separate change with its own migration.
    """
    assert timedelta(seconds=1) == records_service.EXPECTED_UPDATED_AT_TOLERANCE
    inside = STORED_AT - timedelta(milliseconds=900)
    records_service.assert_expected_updated_at(_record(), inside)  # passes, knowingly
    outside = STORED_AT - timedelta(milliseconds=1100)
    with pytest.raises(HTTPException):
        records_service.assert_expected_updated_at(_record(), outside)


# --------------------------------------------------------------------------------------
# The order: the refusal is above the ledger on all six routes
# --------------------------------------------------------------------------------------


def test_every_update_route_asks_before_it_writes_the_audit_row():
    """A refusal raised after ``guard_record_edit`` would leave a committed ledger entry for an edit
    that was then turned down — the exact defect the 2026-09-03 transaction wrapping was written to
    end, arrived at from the other side. Raising above it means the rollback takes the entry with it.

    Read off the source rather than driven, because the thing being asserted is a LINE ORDER inside a
    transaction, and a fake that could observe it would have to reimplement ``db.tx``. This is the
    same treatment ``outbox-drain-triage-unit.spec.ts`` gives the web outbox's cross-tab lock: a
    weaker assertion than a drive, and far better than the nothing that would otherwise stand here.
    """
    from pathlib import Path

    routes = Path(__file__).resolve().parents[1] / "app" / "api" / "routes"
    for module, guard in (
        ("artisans.py", 'guard_record_edit(artisan'),
        ("crafts.py", 'guard_record_edit(craft'),
        ("products.py", 'guard_record_edit(product'),
        ("tools.py", 'guard_record_edit(tool'),
        ("processes.py", 'guard_record_edit(process, current_user, data'),
        # ``workshops.py`` writes its ledger through ``record_revision`` directly on the privileged
        # branch, so the anchor is the ``db.tx()`` the pair of them live in.
        ("workshops.py", "privileged = access.at_least"),
    ):
        source = (routes / module).read_text(encoding="utf-8")
        assert "take_expected_updated_at(data)" in source, module
        asked = source.index("assert_expected_updated_at(")
        wrote = source.index(guard)
        assert asked < wrote, f"{module}: the precondition must be asked above the ledger write"


def test_every_update_route_takes_the_key_out_of_the_body():
    """If any route forgot the pop, its very first correction carrying a precondition would reach
    Prisma as an unknown column — a bare 500 on a save, naming nothing a designer could act on.
    """
    from pathlib import Path

    routes = Path(__file__).resolve().parents[1] / "app" / "api" / "routes"
    for module in ("artisans.py", "crafts.py", "products.py", "tools.py", "processes.py", "workshops.py"):
        source = (routes / module).read_text(encoding="utf-8")
        popped = source.index("take_expected_updated_at(data)")
        asked = source.index("assert_expected_updated_at(")
        assert popped < asked, f"{module}: pop it out of the body before asking the question"
