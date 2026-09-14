"""**THE PRE-SUBMISSION LOOP, ASSERTED WITHOUT A DATABASE.**

The companion to ``test_dw_inspector_scope``, and the division is the one that module's own header
draws: everything here is true of the SOURCE — which value may follow which, what a refusal says,
what a decision writes, which columns the plans name — so it runs in CI, where there is no Postgres.
What only a database can answer (a 422 rather than a 500 on a workshop nobody handed in, a row
written or not written) is asserted over there.

WHAT IS PINNED HERE, AND WHY EACH IS A WAY THIS FEATURE COULD SHIP LOOKING FINISHED WHILE BEING
WRONG.

**THE GRAPH IS THE FIRST TRANSITION RULE THIS PRODUCT HAS EVER HAD.** Until 2026-09-13 any status
could follow any other, so every assertion about an edge is an assertion about behaviour that did
not exist yesterday and that three surfaces now mirror. A token added to the Prisma enum and not to
the graph is unreachable; a token in the graph and not in the pydantic vocabulary is 422'd before
the database ever sees it. Both directions are checked against the .prisma file itself.

**THE ROUND COUNTER IS WRITTEN IN TWO PLACES AND MUST NOT BE WRITTEN TWICE.** ``PATCH /{id}`` and
``save_stage``'s in-transaction header write both enter PRE_SUBMISSION, and both call one function.
If a later edit inlines ``{"increment": 1}`` at either site the two paths drift, and every
``DwInspectionFeedback`` row written afterwards names the wrong cycle — permanently, because the
column is copied and never recomputed. Nothing fails; the designer's "what was sent back in round 3"
panel simply shows the wrong four suggestions.

**THE DECISION CACHE IS CLEARED ON RE-ENTRY, AND THAT IS ONLY SAFE BECAUSE THE REGISTER EXISTS.**
``reviewNotes``/``reviewedById``/``reviewedAt`` are the LAST decision, so a workshop waiting for a
new one must not name an officer who has not taken it. Nothing is lost — every sentence is also a
row in ``DwInspectionFeedback`` — and the tests below pin both halves of that bargain.

**THE RESUBMISSION IS AN ACT OF THE EDITING PARTY, AND ``save_stage`` CANNOT SEE WHO ACTED.** It
took ``user`` for provenance and inferred the rest from having been reached at all — and two
officer-driven callers landed the same day that reach it without either designer gate, so an artisan
upload and a designer reassignment each spent a submission round and cleared the send-back off a
report the designer had not yet read. Section 9 is a CENSUS of the call sites rather than a test of
behaviour, because the failure was a caller nobody counted.

**AND THE LEDGER IS READ BACK.** This repository already carries three write-only tables and
``test_review_rating_ledger.py`` records that a fourth is indefensible. The biconditional at the
foot of this file is copied from there.
"""

import ast
import importlib
import inspect
import re
import subprocess
import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.schemas import design_workshop_review_loop as loop
from app.schemas.design_workshops import DESIGN_WORKSHOP_STATUSES

BACKEND = Path(__file__).resolve().parents[1]
APP = BACKEND / "app"
SCHEMA = BACKEND / "prisma" / "schema.prisma"
MIGRATIONS = BACKEND / "prisma" / "migrations"

#: The three files this wave writes for the loop. Read as TEXT where the assertion is about what the
#: source says rather than about what it does.
LOOP_MODULE = APP / "schemas" / "design_workshop_review_loop.py"

STATUSES = (
    "DRAFT",
    "IN_PROGRESS",
    "COMPLETE",
    "PRE_SUBMISSION",
    "NEEDS_REVISION",
    "SUBMITTED",
    "APPROVED",
    "ARCHIVED",
)


def _enum_members(name: str) -> set[str]:
    """The members of one enum, parsed out of ``schema.prisma`` itself.

    READ OFF THE FILE AND NOT OFF THE GENERATED CLIENT, because the generated client is a build
    artefact that may predate the schema by one ``prisma generate`` — and the failure this is
    guarding against is precisely a token that exists in one place and not the other.
    """
    text = SCHEMA.read_text(encoding="utf-8")
    block = re.search(rf"^enum {name} \{{(.*?)^\}}", text, re.S | re.M)
    assert block, f"enum {name} is not in schema.prisma"
    return {
        line.strip()
        for line in block.group(1).splitlines()
        if line.strip() and not line.strip().startswith(("/", "#", "-"))
    }


# --------------------------------------------------------------------------------------
# 1. The vocabulary, in three places, checked in both directions
# --------------------------------------------------------------------------------------


def test_the_graph_covers_every_status_the_schema_declares():
    """One vocabulary, three declarations, and no one of them may be edited alone.

    ``schema.prisma`` is what Postgres enforces, ``DESIGN_WORKSHOP_STATUSES`` is what the request
    body is validated against, and ``LEGAL_TRANSITIONS`` is what may follow what. A token missing
    from the middle one is answered 422 by pydantic with a list that does not include it — the
    database never sees it and the refusal reads as though this server does not have the feature. A
    token missing from the last one is unreachable rather than free.
    """
    declared = _enum_members("DesignWorkshopStatus")
    assert declared == set(STATUSES)
    assert set(DESIGN_WORKSHOP_STATUSES) == declared
    assert set(loop.LEGAL_TRANSITIONS) == declared
    for token, onward in loop.LEGAL_TRANSITIONS.items():
        assert onward <= declared, f"{token} points at a status the schema does not declare"


def test_draft_is_entry_only():
    """Nothing may move INTO ``DRAFT``.

    It means "nobody has typed into this yet", which a workshop with 22 filled stages cannot
    truthfully claim — and ``save_stage`` advances DRAFT -> IN_PROGRESS exactly once, so a PATCH
    back to it was a word that survived until the next save.
    """
    for token, onward in loop.LEGAL_TRANSITIONS.items():
        assert loop.DRAFT not in onward, f"{token} -> DRAFT is in the graph"


def test_submitted_is_reachable_only_from_approved():
    """SUBMITTED HAS BEEN REDEFINED: it is now the state AFTER a sign-off, not the designer's act.

    The designer's forward act is PRE_SUBMISSION. If a second arm into SUBMITTED ever appears, the
    word means two things again and the workshops list filters on a mixture of them.
    """
    sources = {k for k, v in loop.LEGAL_TRANSITIONS.items() if loop.SUBMITTED in v}
    assert sources == {loop.APPROVED}


def test_approved_is_reachable_only_from_pre_submission():
    """A report is approved out of the queue it was handed into, and from nowhere else."""
    sources = {k for k, v in loop.LEGAL_TRANSITIONS.items() if loop.APPROVED in v}
    assert sources == {loop.PRE_SUBMISSION}


def test_approved_has_no_edge_a_header_patch_may_make():
    """**THE TWO-HOP LAUNDERING PATH, CLOSED AND KEPT CLOSED.**

    ``APPROVED -> ARCHIVED`` and ``ARCHIVED -> PRE_SUBMISSION`` were both ordinary PATCH edges in
    the first draft of this graph. Together they let any designer on the workshop move an approved
    report back into the loop in two requests — spending a submission round, with no ReviewLog row
    anywhere and the decision cache still reading "approved by X on the 11th". Restoring ARCHIVED to
    this row re-opens it.
    """
    assert loop.patchable_from(loop.APPROVED) == frozenset(), (
        "APPROVED has acquired an outward edge a header edit may make. If that is ARCHIVED, the "
        "two-hop path APPROVED -> ARCHIVED -> PRE_SUBMISSION is open again and an approval can be "
        "laundered away with no audit entry."
    )


def test_archived_is_not_reachable_from_approved():
    """Removed from the graph, and NOT relocated into the decision edges.

    Making it a decision would have been the other tempting fix and it is a different rule: it would
    say the sanctioning authority may archive an approved report directly, rather than that the
    report is handed on or the approval withdrawn first.
    """
    assert loop.ARCHIVED not in loop.LEGAL_TRANSITIONS[loop.APPROVED]
    assert (loop.APPROVED, loop.ARCHIVED) not in loop.DECISION_EDGES


# --------------------------------------------------------------------------------------
# 2. The refusals say what to do next
# --------------------------------------------------------------------------------------


def test_the_four_decision_edges_are_refused_to_a_header_patch():
    """A decision is taken on a route that writes its audit row in the same transaction.

    ``by_decision_route=True`` is what makes each edge reachable at all, and it is passed by the
    decision routes and by nobody else. Each refusal names the route, because a client told
    "invalid transition" retries the same body.
    """
    assert len(loop.DECISION_EDGES) == 4
    for current, nxt in loop.DECISION_EDGES:
        refusal = loop.transition_refusal(current, nxt)
        assert refusal, f"{current} -> {nxt} is not refused to a header patch"
        assert "/design-workshop" in refusal, (
            f"the refusal for {current} -> {nxt} does not name the route that owns it"
        )
        assert loop.transition_refusal(current, nxt, by_decision_route=True) is None


def test_every_refusal_names_the_next_move():
    """No refusal may be a bare "not allowed" over the sixty-four ordered pairs."""
    for current in STATUSES:
        for nxt in STATUSES:
            refusal = loop.transition_refusal(current, nxt)
            if refusal is None:
                continue
            assert refusal.strip(), f"{current} -> {nxt} refuses with an empty sentence"
            assert "/design-workshop" in refusal or "only" in refusal, (
                f"{current} -> {nxt}: {refusal!r} tells the caller what is refused and not what to "
                f"do instead"
            )


def test_a_dead_end_refusal_does_not_print_an_empty_list():
    """**THE BRANCH A NAIVE ``', '.join(sorted(...))`` PRODUCES, ASSERTED DIRECTLY.**

    ``APPROVED`` is the one token whose every outward edge is a decision, so the set a refusal would
    list is EMPTY and the sentence comes out as "it can only become ." — a hole, shown to a
    designer, about the status where the answer matters most. What they need to be told is whose
    move it is.
    """
    refusal = loop.transition_refusal(loop.APPROVED, loop.ARCHIVED)
    assert refusal
    assert "can only become ." not in refusal
    assert "can only become ," not in refusal
    assert "/design-workshop-approvals/{id}/hand-on" in refusal
    assert "/design-workshop-approvals/{id}/revise" in refusal


def test_a_no_op_is_never_a_refusal():
    """An empty save is a 200 and the unchanged summary — ``_header_patch_data``'s own rule."""
    for token in STATUSES:
        assert loop.transition_refusal(token, token) is None


def test_an_unknown_current_status_fails_closed():
    """A row written by a newer deployment refuses every move rather than falling through."""
    refusal = loop.transition_refusal("SOMETHING_NEW", loop.APPROVED)
    assert refusal and "does not recognise" in refusal


# --------------------------------------------------------------------------------------
# 3. The header write that enters PRE_SUBMISSION
# --------------------------------------------------------------------------------------


def test_presubmission_header_spends_exactly_one_round():
    """One act, one round — and a re-sent payload spends none.

    ``{}`` for a workshop already in PRE_SUBMISSION is the design-workshop form of the rule
    ``access.REVISION_SKIP_FIELDS`` states for records: infrastructural churn must not read as an
    act.
    """
    assert loop.presubmission_header(loop.NEEDS_REVISION)["submissionRound"] == {"increment": 1}
    assert loop.presubmission_header(loop.PRE_SUBMISSION) == {}


def test_presubmission_header_clears_the_decision_cache():
    """A workshop awaiting a decision must not name an officer who has not taken one.

    All three cache columns, by name, in the same statement as the status. Nothing is lost: every
    sentence they ever held is also a ``DwInspectionFeedback`` row, which is the register the
    designer's panel is built from.
    """
    assert loop.presubmission_header(loop.APPROVED) == {
        "status": "PRE_SUBMISSION",
        "submissionRound": {"increment": 1},
        "reviewNotes": None,
        "reviewedById": None,
        "reviewedAt": None,
    }


def test_the_cleared_keys_are_exactly_the_cached_decision():
    """Derived rather than listed, so a FOURTH cache column cannot be left uncleared.

    ``records.review_update`` is the one function that writes this cache for seven record types now.
    If somebody adds a key to it, this fails until the clearing side is taught about it too.
    """
    from app.services import records

    cleared = set(loop.presubmission_header(loop.NEEDS_REVISION)) - {"status", "submissionRound"}
    cached = set(records.review_update("X", None, "y")) - {"status"}
    assert cleared == cached


# --------------------------------------------------------------------------------------
# 4. The write plans
# --------------------------------------------------------------------------------------


def _plan_kwargs(**overrides):
    base = dict(
        workshop_id="dw_1",
        round=2,
        actor_id="officer_1",
        at=datetime(2026, 9, 13, 10, 0, tzinfo=UTC),
        note="Stage 14's cost sheet does not add up.",
    )
    base.update(overrides)
    return base


def test_an_inspection_decision_cannot_be_written_into_a_stage_entry():
    """**THE REFUSAL THE WHOLE SEPARATION RESTS ON, MADE BY CONSTRUCTION.**

    A suggestion stored as stage data would be attributed to whoever first saved that stage and
    dated to the last edit of any of its forty fields — and it would put an inspector on the one
    write path the fifth access scope exists to keep them off.
    """
    with pytest.raises(loop.InspectionRuleViolation) as raised:
        loop.InspectionWritePlan(
            table="DwStageEntry", operation=loop.Operation.CREATE, data={"data": {}}
        )
    message = str(raised.value)
    for table in loop.WRITABLE_TABLES:
        assert table in message, "the refusal must name where the decision may be written"


def test_an_update_names_one_row_and_a_create_names_none():
    """The two shape refusals. An UPDATE with no ``where`` is an UPDATE of the whole table."""
    with pytest.raises(loop.InspectionRuleViolation):
        loop.InspectionWritePlan(
            table="DesignWorkshop", operation=loop.Operation.UPDATE, data={"status": "X"}
        )
    with pytest.raises(loop.InspectionRuleViolation):
        loop.InspectionWritePlan(
            table="ReviewLog",
            operation=loop.Operation.CREATE,
            data={"recordId": "dw_1"},
            where={"id": "dw_1"},
        )


def test_a_send_back_makes_exactly_three_writes_and_names_one_row():
    """The suggestion, the decision, the audit entry — and the workshop update names its row."""
    plans = loop.send_back_plans(**_plan_kwargs())
    assert [(p.table, p.operation) for p in plans] == [
        ("DwInspectionFeedback", loop.Operation.CREATE),
        ("DesignWorkshop", loop.Operation.UPDATE),
        ("ReviewLog", loop.Operation.CREATE),
    ]
    assert plans.workshop.where == {"id": "dw_1"}
    assert plans.feedback.data["sentBack"] is True, (
        "the row that moved the status must say so; it is what makes 'which of these four "
        "suggestions sent it back' answerable on the designer's screen"
    )


def test_the_workshop_write_is_records_review_update_verbatim():
    """**THE REUSE CLAIM, PINNED.**

    The whole argument for spelling the two new status tokens the way ``RecordStatus`` spells them
    is that ONE function writes this cache for every record type that has one. A hand-written
    seventh copy is a copy that can drift.
    """
    from app.services import records

    plans = loop.send_back_plans(**_plan_kwargs(note="Fix the cost sheet."))
    written = dict(plans.workshop.data)
    theirs = records.review_update("NEEDS_REVISION", "Fix the cost sheet.", "officer_1")
    assert set(written) == {"status", "reviewNotes", "reviewedById", "reviewedAt"}
    assert set(written) == set(theirs)
    written.pop("reviewedAt")
    theirs.pop("reviewedAt")
    assert written == theirs


def test_review_update_is_imported_inside_the_function_and_not_at_module_level():
    """The one import that would make this module unimportable without a generated client.

    ``services/records`` imports ``app.core.db`` on its first line, so a module-level import here
    would drag the Prisma client into a module whose whole point is that it needs none.
    """
    for line in LOOP_MODULE.read_text(encoding="utf-8").splitlines():
        if re.match(r"^\s*from app\.services(\.records)? import", line) and (
            "records" in line or "review_update" in line
        ):
            assert line.startswith((" ", "\t")), (
                f"{line.strip()!r} is at module level; it must be imported inside the one function "
                f"that needs it"
            )


def test_a_round_of_zero_is_refused_by_the_plan():
    """Zero can only mean the copy from the workshop row was skipped.

    The CHECK constraint refuses the same row for the same reason, and a database refusing it
    reaches the officer as a bare 500 about a report they did nothing wrong with.
    """
    with pytest.raises(loop.InspectionRuleViolation) as raised:
        loop.feedback_plan(**_plan_kwargs(round=0))
    message = str(raised.value)
    assert "submissionRound" in message
    assert "DwInspectionFeedback_round_check" in message


def test_the_review_log_row_uses_a_recordstatus_token_that_exists_in_both_enums():
    """``ReviewLog.status`` is ``RecordStatus`` and NOT ``DesignWorkshopStatus``.

    ``NEEDS_REVISION`` and ``APPROVED`` are in both enums — which is exactly why those two spellings
    were chosen — and ``PRE_SUBMISSION`` and ``SUBMITTED`` are not in ``RecordStatus`` at all. **No
    ReviewLog row is ever written for a resubmission:** it is the designer's edit, already recorded
    by the stage rows' own timestamps and provenance, and inventing a ``RecordStatus`` value for it
    would be a schema change to the shared review enum in service of a design-workshop concept. A
    well-meaning "log the resubmission too" raises inside ``db.tx()`` and rolls the decision back
    with it, so the status would appear not to have moved.
    """
    record_status = _enum_members("RecordStatus")
    assert {"NEEDS_REVISION", "APPROVED"} <= record_status
    assert "PRE_SUBMISSION" not in record_status
    assert "SUBMITTED" not in record_status


def _plan_builders() -> list:
    """Every ``*_plans`` builder DECLARED IN THIS MODULE, discovered rather than listed.

    ``fn.__module__ == loop.__name__`` is what keeps an imported helper out: ``vars()`` sees names
    the module imported as well as names it defines, and a sweep that asserted things about another
    module's functions would report a failure in the wrong file.
    """
    return [
        value
        for name, value in vars(loop).items()
        if name.endswith("_plans")
        and callable(value)
        and getattr(value, "__module__", None) == loop.__name__
    ]


def test_every_review_log_status_a_plan_builder_writes_is_a_recordstatus_token():
    """The generalised version of the test above, and the one that catches a SECOND builder.

    ── IT DID NOT CATCH ONE UNTIL 2026-09-14, WHICH IS THE DEFECT THIS DOCSTRING NOW OWNS ───────

    The body read ``builders = [loop.send_back_plans]`` — a hand-written list of exactly one, under
    a sentence promising to catch a second. A hand-written singleton cannot catch a second anything.
    So the coverage this test reported was coverage it did not have: the approvals workstream this
    module names at :190-194 adds ``hand_on_plans``, the natural ``ReviewLog`` spelling for that verb
    is ``SUBMITTED``, and ``RecordStatus`` does not contain it — the insert would raise inside
    ``db.tx()`` and roll the status change back with it, so the officer presses Hand on, gets a 500,
    and the report appears not to have moved. This test would have stayed green through all of it.
    Proven rather than assumed: with a ``hand_on_plans`` writing ``SUBMITTED`` injected into the
    module, the shipped body passed and the derived sweep below fails naming it.

    ── WHAT THE SWEEP COVERS, AND — SAID PLAINLY — WHAT IT STILL DOES NOT ───────────────────

    It covers every ``*_plans`` builder DECLARED IN THIS MODULE, the same shape as
    ``OUTSIDE_INSPECTION_SET`` in ``test_permission_matrix.py`` and as the derived sweeps at :259 and
    :505 of this file, and for the same reason each of those gives: a register written down twice
    goes stale on the second copy.

    **IT CANNOT COVER A BUILDER IN ANOTHER MODULE, AND THE APPROVALS ROUTER IS EXPECTED TO BE ONE.**
    ``vars(loop)`` sees this module and nothing else, so a ``hand_on_plans`` landing in a new
    ``app/schemas/design_workshop_approvals.py`` is outside it — and that is precisely the file
    :190-194 says is coming. This is stated rather than papered over because a test whose stated
    scope is wider than its real one is the thing being fixed here, and repeating that mistake one
    level up would be worse than the original. **The repair that closes the hole whatever module the
    builder lives in is by construction, not by sweeping:** make
    ``InspectionWritePlan.__post_init__`` refuse a ``ReviewLog`` CREATE whose ``status`` is not a
    ``RecordStatus`` token, exactly as it already refuses a table outside ``WRITABLE_TABLES``, an
    UPDATE with no ``where`` and a CREATE that names one. Then no builder anywhere can construct the
    bad plan, this sweep becomes a second line of defence rather than the only one, and the
    approvals workstream inherits the guarantee without having to know this test exists.
    """
    record_status = _enum_members("RecordStatus")
    builders = _plan_builders()
    # AN EMPTY SWEEP MUST NOT PASS. A derived list is only an improvement on a hand-written one while
    # it actually finds something; a rename to `*_plan` would otherwise turn this test into a green
    # assertion about nothing, which is the failure mode it was written to end.
    assert builders, (
        "no `*_plans` builder was found in design_workshop_review_loop; if they were renamed, this "
        "sweep must be renamed with them rather than left passing over an empty list"
    )
    assert loop.send_back_plans in builders, "the sweep no longer finds the builder it was written for"

    for builder in builders:
        # ONLY THE KEYWORDS THIS BUILDER DECLARES. `_plan_kwargs` is `send_back_plans`' signature, and
        # a future builder taking, say, `next_owner_id` would otherwise raise TypeError here — which
        # is a crash rather than an assertion, and reads to whoever hits it as a broken test rather
        # than as a builder this sweep could not check. Filtering makes the mismatch explicit below.
        # `*args`/`**kwargs` are excluded from BOTH halves: they have no default, so counting them
        # as required would report `**extra` as a parameter `_plan_kwargs` failed to supply, and a
        # builder would be refused for the one thing that makes it tolerant.
        named = {
            name: param
            for name, param in inspect.signature(builder).parameters.items()
            if param.kind
            not in (inspect.Parameter.VAR_POSITIONAL, inspect.Parameter.VAR_KEYWORD)
        }
        kwargs = {k: v for k, v in _plan_kwargs().items() if k in named}
        missing = [
            name
            for name, param in named.items()
            if param.default is inspect.Parameter.empty and name not in kwargs
        ]
        assert not missing, (
            f"{builder.__name__} needs {missing}, which `_plan_kwargs` does not supply, so this "
            f"sweep cannot check it. Add those keys to `_plan_kwargs` rather than excluding the "
            f"builder — an unchecked builder is the hole this test exists to close"
        )

        plans = builder(**kwargs)
        log = getattr(plans, "log", None)
        # A bundle with no audit row has nothing for THIS test to check and is not a failure — a
        # send-back writes one, and a future builder for an act that needs no ReviewLog row
        # legitimately would not. It is skipped on the ABSENCE OF `.log`, never on the builder's
        # name: a skip list keyed by name is the hand-written list this test was rewritten to stop
        # being, and it would silently exempt the next builder somebody added to it.
        if log is None:
            continue
        assert log.data["status"] in record_status, (
            f"{builder.__name__} writes a ReviewLog row with a status RecordStatus does not have; "
            f"it raises inside the transaction and rolls the decision back with it"
        )
        assert log.data["recordType"] == "DESIGN_WORKSHOP"


def test_a_blank_note_is_refused():
    """Byte-compatible with the sentence ``review.py`` gives the six record types.

    Whitespace passes the wire model's ``min_length=1`` and is refused here, where the value is
    stripped — which is why neither guard can be the only one.
    """
    for blank in ("", "   ", "\n\t "):
        with pytest.raises(loop.InspectionRuleViolation) as raised:
            loop.send_back_plans(**_plan_kwargs(note=blank))
        assert str(raised.value) == (
            "Comments are required when sending a record back for revision"
        )


def test_a_note_over_the_cap_is_refused():
    """One character over, refused, with the remedy: file the rest as a second suggestion."""
    too_long = "x" * (loop.MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS + 1)
    with pytest.raises(loop.InspectionRuleViolation):
        loop.feedback_plan(**_plan_kwargs(note=too_long))
    fits = "x" * loop.MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS
    assert loop.feedback_plan(**_plan_kwargs(note=fits)).data["note"] == fits


def test_the_wire_cap_and_the_plan_cap_are_the_same_object():
    """Imported rather than restated, the arrangement ``MAX_DESIGN_WORKSHOP_INSPECTORS`` already has.

    Two numbers for one rule is how a 4000-character wire cap comes to sit in front of a 2000-
    character write plan, and the officer's suggestion is refused by a sentence neither file states.
    """
    from app.schemas.design_workshop_inspections import DwInspectionFeedbackIn

    caps = [
        meta.max_length
        for meta in DwInspectionFeedbackIn.model_fields["note"].metadata
        if getattr(meta, "max_length", None) is not None
    ]
    assert caps == [loop.MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS]


def test_an_unknown_stage_key_is_refused_and_an_unknown_field_key_is_not():
    """The asymmetry is deliberate and is stated in both places.

    There are 22 stages and every client draws them from one dump, so a stage key the registry does
    not declare is a client bug. A FIELD key can legitimately come from a client one release ahead,
    and refusing the row would lose the suggestion rather than the typo.
    """
    with pytest.raises(loop.InspectionRuleViolation) as raised:
        loop.feedback_plan(**_plan_kwargs(stage_key="notAStage"))
    assert "registry" in str(raised.value) or "stage list" in str(raised.value)
    plan = loop.feedback_plan(**_plan_kwargs(field_key="notAField"))
    assert plan.data["fieldKey"] == "notAField"
    assert plan.data["stageKey"] is None, "no stage named means the report as a whole"


def test_a_clock_in_the_future_is_refused_rather_than_corrected():
    """A suggestion filed at a moment nobody chose is a record of something that did not happen."""
    at = datetime(2026, 9, 13, 10, 0, tzinfo=UTC)
    with pytest.raises(loop.InspectionRuleViolation) as raised:
        loop.feedback_plan(**_plan_kwargs(at=at, recorded_at=at + timedelta(hours=2)))
    assert "corrected time" in str(raised.value)
    inside = at + loop.MAX_DEVICE_CLOCK_SKEW - timedelta(minutes=1)
    assert loop.feedback_plan(**_plan_kwargs(at=at, recorded_at=inside)).data["recordedAt"] == inside


def test_the_skew_matches_the_other_two_ledgers():
    """One number for one rule across three append-only ledgers, copied rather than imported.

    Copied because those two modules touch the database and this one may not; asserted because a
    copy is what drifts.
    """
    from app.services import design_ratings, dictation_consent

    assert loop.MAX_DEVICE_CLOCK_SKEW == dictation_consent.MAX_DEVICE_CLOCK_SKEW
    assert loop.MAX_DEVICE_CLOCK_SKEW == design_ratings.MAX_DEVICE_CLOCK_SKEW


# --------------------------------------------------------------------------------------
# 5. The columns the plans name actually exist
# --------------------------------------------------------------------------------------


def _model_columns(name: str) -> set[str]:
    text = SCHEMA.read_text(encoding="utf-8")
    block = re.search(rf"^model {name} \{{(.*?)^\}}", text, re.S | re.M)
    assert block, f"model {name} is not in schema.prisma"
    return {
        line.split()[0]
        for line in block.group(1).splitlines()
        if line.strip() and not line.strip().startswith(("/", "@", "}"))
    }


def test_every_column_the_plans_name_exists_in_the_schema():
    """A plan naming a column the model does not have is a 500 at the driver, per decision."""
    plans = loop.send_back_plans(**_plan_kwargs())
    feedback_columns = _model_columns("DwInspectionFeedback")
    assert set(plans.feedback.data) <= feedback_columns
    workshop_columns = _model_columns("DesignWorkshop")
    assert set(plans.workshop.data) <= workshop_columns
    assert set(plans.log.data) <= _model_columns("ReviewLog")


def test_the_feedback_table_declares_the_two_clocks_and_the_restrict_actor():
    """The shape the column comments argue for, asserted against the model rather than the prose."""
    text = SCHEMA.read_text(encoding="utf-8")
    block = re.search(r"^model DwInspectionFeedback \{(.*?)^\}", text, re.S | re.M).group(1)
    assert "recordedAt DateTime?" in block, "the device clock must stay nullable"
    assert "createdAt  DateTime  @default(now())" in block
    assert "onDelete: Restrict" in block, "a named officer's instruction must outlive the account"
    assert "onDelete: Cascade" in block
    assert "@@index([designWorkshopId, createdAt])" in block
    assert "@@index([designWorkshopId, round])" in block
    assert "@@index([actorId])" in block


def test_the_workshop_carries_the_cache_and_the_counter():
    """Four columns, and the counter is an Int and not the peer/pool round enum.

    ``DwReviewRound`` is {PEER, POOL} and means an AUDIENCE. Three clients already match
    exhaustively over its two tokens, so a submission cycle pushed into it breaks all three at once.
    """
    text = SCHEMA.read_text(encoding="utf-8")
    block = re.search(r"^model DesignWorkshop \{(.*?)^\}", text, re.S | re.M).group(1)
    assert "reviewNotes String?" in block
    assert "reviewedById String?" in block
    assert "reviewedAt DateTime?" in block
    assert "submissionRound Int @default(0)" in block
    assert "submissionRound DwReviewRound" not in block


def test_the_queue_index_matches_the_query_the_queue_makes():
    """The composite is on the two columns the approvals queue actually filters by.

    An earlier draft of this wave argued an index on ``(status, deletedAt)`` from a predicate that
    names no status — a queue narrowed by status could not show a report that has been sent back.
    The index that was argued for is deliberately absent from every migration.
    """
    text = SCHEMA.read_text(encoding="utf-8")
    assert "@@index([deletedAt, submissionRound])" in text
    for migration in MIGRATIONS.rglob("migration.sql"):
        assert "DesignWorkshop_status_deletedAt_idx" not in migration.read_text(encoding="utf-8")


# --------------------------------------------------------------------------------------
# 6. The migrations
# --------------------------------------------------------------------------------------

ENUM_MIGRATIONS = (
    MIGRATIONS / "20260913130000_design_workshop_review_statuses" / "migration.sql",
    MIGRATIONS / "20260913130100_review_record_type_design_workshop" / "migration.sql",
)
TABLE_MIGRATION = MIGRATIONS / "20260913130200_dw_inspection_feedback" / "migration.sql"


def _statements(path: Path) -> list[str]:
    """The file's SQL with every comment line stripped."""
    lines = [
        line for line in path.read_text(encoding="utf-8").splitlines() if not line.strip().startswith("--")
    ]
    return [s.strip() for s in "\n".join(lines).split(";") if s.strip()]


def test_the_enum_migrations_are_alter_type_only():
    """**PRISMA RUNS A MIGRATION IN ONE IMPLICIT TRANSACTION.**

    A value added by ``ALTER TYPE ... ADD VALUE`` cannot be USED in the transaction that added it,
    so a DEFAULT, a CHECK, a partial index or a backfill naming one of these tokens fails the deploy
    — after the earlier statements in the file have already been attempted.
    """
    for path in ENUM_MIGRATIONS:
        for statement in _statements(path):
            assert statement.startswith("ALTER TYPE"), f"{path.name} carries {statement[:60]!r}"
            assert "ADD VALUE IF NOT EXISTS" in statement
    table_sql = TABLE_MIGRATION.read_text(encoding="utf-8")
    body = "\n".join(
        line for line in table_sql.splitlines() if not line.strip().startswith("--")
    )
    for token in ("PRE_SUBMISSION", "NEEDS_REVISION", "APPROVED", "DESIGN_WORKSHOP"):
        assert token not in body, (
            f"{token} is USED in the same migration wave that adds it; the deploy fails with "
            f"'unsafe use of new value of enum type'"
        )


def test_the_migration_matches_the_model():
    """The CHECK, the five indexes and the three foreign-key actions, read off the SQL.

    **THE CHECK IS THE ONE THAT CANNOT BE READ ANYWHERE ELSE.** Prisma has no syntax for it, so it
    exists only in this file — a ``prisma migrate dev`` regeneration reproduces the table without it
    and says nothing.
    """
    sql = TABLE_MIGRATION.read_text(encoding="utf-8")
    assert 'CONSTRAINT "DwInspectionFeedback_round_check" CHECK ("round" >= 1)' in sql
    for index in (
        "DwInspectionFeedback_designWorkshopId_createdAt_idx",
        "DwInspectionFeedback_designWorkshopId_round_idx",
        "DwInspectionFeedback_actorId_idx",
        "DesignWorkshop_deletedAt_submissionRound_idx",
        "DesignWorkshop_reviewedById_idx",
    ):
        assert index in sql, f"{index} is in the model and not in the migration"
    assert "ON DELETE SET NULL" in sql
    assert "ON DELETE CASCADE" in sql
    assert "ON DELETE RESTRICT" in sql
    for column in ("reviewNotes", "reviewedById", "reviewedAt", "submissionRound"):
        assert f'ADD COLUMN IF NOT EXISTS "{column}"' in sql


# --------------------------------------------------------------------------------------
# 7. The read
# --------------------------------------------------------------------------------------


def _row(**overrides):
    base = dict(
        id="fb_1",
        designWorkshopId="dw_1",
        round=2,
        stageKey=None,
        fieldKey=None,
        note="Stage 14's cost sheet does not add up.",
        sentBack=True,
        actorId="officer_1",
        actor=SimpleNamespace(name="R. Mahapatra"),
        recordedAt=None,
        createdAt=datetime(2026, 9, 13, 10, 0, tzinfo=UTC),
    )
    base.update(overrides)
    return SimpleNamespace(**base)


def test_feedback_payload_has_the_eleven_keys_the_clients_decode():
    """The wire contract. The TS and Kotlin mirrors are the other half of it."""
    assert set(loop.feedback_payload(_row())) == {
        "id",
        "designWorkshopId",
        "round",
        "stageKey",
        "fieldKey",
        "note",
        "sentBack",
        "actorId",
        "actorName",
        "recordedAt",
        "createdAt",
    }


def test_feedback_payload_is_synchronous_and_reads_the_name_off_the_row():
    """**THE ASSERTION THAT CATCHES A MISSING ``include={"actor": True}``.**

    The name arrives ON THE ROW, which is what keeps this function pure and keeps a panel of
    suggestions from costing one query each. A caller that forgets the include gets None here rather
    than an exception — quiet, and quiet in the direction where every correction is attributed to
    nobody — so the route tests assert a non-null name over a real read.
    """
    assert not inspect.iscoroutinefunction(loop.feedback_payload)
    assert loop.feedback_payload(_row())["actorName"] == "R. Mahapatra"
    without = loop.feedback_payload(SimpleNamespace(id="fb_2"))
    assert without["actorName"] is None
    assert loop.feedback_payload(_row(actor=SimpleNamespace(name="")))["actorName"] == ""
    both = loop.feedback_payload(
        _row(recordedAt=datetime(2026, 9, 1, 8, 0, tzinfo=UTC))
    )
    assert both["recordedAt"] == "2026-09-01T08:00:00+00:00"
    assert both["createdAt"] == "2026-09-13T10:00:00+00:00"


# --------------------------------------------------------------------------------------
# 8. The properties this module is built on, asserted rather than believed
# --------------------------------------------------------------------------------------


def test_the_pure_module_touches_no_database():
    """**PURITY, ASSERTED IN A PROCESS THAT PROVES IT** rather than by reading the imports.

    The claim is that this module can be imported with no generated Prisma client — which is what
    lets ``save_stage`` import it, what keeps the graph assertable in CI, and what makes the
    inside-the-function ``review_update`` import necessary rather than stylistic. A subprocess is
    the only honest way to ask: in this process a dozen other test modules have already imported the
    client.
    """
    for line in LOOP_MODULE.read_text(encoding="utf-8").splitlines():
        # THE IMPORT AND NOT THE WORDS. The module's own header names `app.core.db` in the sentence
        # explaining why it does not import it, and a test that forbade the STRING would forbid the
        # explanation — which is how a file ends up with the rule enforced and the reason deleted.
        assert not re.match(r"^\s*(from|import)\s+app\.core\.db", line), line
        assert not re.match(r"^\s*from app\.core import .*db", line), line
        assert not re.match(r"^\s*from app\.services\.design_workshops import", line), line
    proof = subprocess.run(
        [
            sys.executable,
            "-c",
            "import sys; import app.schemas.design_workshop_review_loop as m; "
            "print(int('prisma' in sys.modules), len(m.LEGAL_TRANSITIONS))",
        ],
        cwd=str(BACKEND),
        capture_output=True,
        text=True,
        timeout=300,
    )
    assert proof.returncode == 0, proof.stderr[-2000:]
    assert proof.stdout.split() == ["0", "8"], (
        f"importing the loop module pulled in the Prisma client: {proof.stdout!r}"
    )


def test_the_loop_module_does_not_name_the_inspection_predicates():
    """It is swept like every other file under ``app/`` and is not one of the three exemptions.

    The fix, if this fails, is to PARAPHRASE — never to add this module to ``THE_FEATURE``, which
    would weaken the sweep for a file that has no business consulting that scope at all.
    """
    from tests.test_dw_inspector_scope_gate import THE_NAMES

    lowered = LOOP_MODULE.read_text(encoding="utf-8").lower()
    for name in THE_NAMES:
        assert name not in lowered, f"{name} is written in {LOOP_MODULE.name}"


def test_the_feedback_ledger_is_not_a_fifth_write_only_table():
    """**READ BACK, OR IT IS A LEDGER NOBODY CAN CONSULT.**

    Copied from ``test_review_rating_ledger``'s biconditional, which names a fourth write-only table
    as indefensible. This wave writes into ``ReviewLog``, which already has that defect and is not
    made worse by one more writer — and the READABLE half of every decision is this table, which
    both detail reads serialise.
    """
    reads = {
        path.name
        for path in APP.rglob("*.py")
        if "dwinspectionfeedback.find_many" in (text := path.read_text(encoding="utf-8"))
        or "dwinspectionfeedback.find_first" in text
    }
    assert reads, (
        "nothing READS the feedback register. It would be the fourth write-only table in this "
        "repository, and the designer's 'what was sent back' panel would have no source."
    )
    # THE WRITE HALF CANNOT BE FOUND BY A TEXT SWEEP, AND SAYING SO IS BETTER THAN A SWEEP THAT
    # QUIETLY FINDS NOTHING. The insert is issued through an `InspectionWritePlan`, whose delegate
    # is resolved from `plan.table.lower()` at run time — deliberately, because that is what lets
    # the same three plans be applied to `db` or to a transaction's client. So the writable half is
    # asserted against the plan module's own list, and that a row actually lands is asserted over a
    # database in `test_dw_inspector_scope`.
    assert "DwInspectionFeedback" in loop.WRITABLE_TABLES


# --------------------------------------------------------------------------------------
# 9. WHO may cause a resubmission — the census the missing one cost us
# --------------------------------------------------------------------------------------
#
# **THE RULE IS ABOUT AN ACTOR, AND ``save_stage`` CANNOT SEE THE ACTOR.** "When a workshop has been
# sent back, the edit IS the resubmission" is true of a DESIGNER's edit. ``save_stage`` takes
# ``user`` for provenance and holds no gate of its own, so for one day it inferred the answer from
# the fact that it had been reached at all, on the strength of a comment asserting that "every
# caller who reaches ``save_stage`` is in that party by construction — the route pairs the designer
# gate with ``load_workshop_or_404(..., for_edit=True)``".
#
# That comment was true of the one caller that existed when it was written and false within the same
# wave. ``services/artisan_import._write_roster`` and
# ``services/design_workshop_oversight.reassign_designer`` both landed, both reached from
# MINISTRY_ADMIN-gated oversight routes, both loaded through ``_workshop_for_assignment_or_404``
# (which is deliberately NOT the designer loader and says so), and both passing the OFFICER as
# ``user``. Both write rows, so ``_content_changed`` was True, so BOTH SILENTLY RESUBMITTED THE
# DESIGNER'S REPORT: status back to PRE_SUBMISSION, ``submissionRound`` spent, the decision cache
# nulled — on a workshop whose corrections were still unmade, before the designer had read what was
# asked for. The half that cannot be undone is the counter: nothing anywhere decrements it, and
# ``DwInspectionFeedback.round`` is copied at write time and never recomputed, so every later
# suggestion names a cycle nobody entered.
#
# The fix is the ``resubmits`` keyword. These tests are the fence, and they are a CENSUS rather than
# a test of behaviour, because the failure mode is a caller nobody looked at: a fourth call site
# appearing is the event to fail on, and only a test that counts call sites can see it. All of it is
# read off the SOURCE, so it runs in CI beside the rest of this file and needs no database.


def _calls_to(name: str) -> dict[str, list[ast.Call]]:
    """Every call to ``name`` anywhere under ``app/``, as ``module path -> [Call node]``.

    BOTH SPELLINGS, because they are the two ways a caller arrives: ``save_stage(...)`` after a
    ``from ... import``, and ``design_workshops.save_stage(...)`` through the module. A census that
    matched only one would have missed exactly the two callers this section exists for — they use
    the second spelling and the route uses the first.
    """
    out: dict[str, list[ast.Call]] = {}
    for path in sorted(APP.rglob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        found = [
            node
            for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and (
                (isinstance(node.func, ast.Name) and node.func.id == name)
                or (isinstance(node.func, ast.Attribute) and node.func.attr == name)
            )
        ]
        if found:
            out[str(path.relative_to(BACKEND)).replace("\\", "/")] = found
    return out


def _keyword(call: ast.Call, name: str):
    for kw in call.keywords:
        if kw.arg == name:
            return kw.value
    return None


def _save_stage_def() -> ast.AsyncFunctionDef:
    tree = ast.parse((APP / "services" / "design_workshops.py").read_text(encoding="utf-8"))
    for node in ast.walk(tree):
        if isinstance(node, ast.AsyncFunctionDef) and node.name == "save_stage":
            return node
    raise AssertionError("services/design_workshops.save_stage is gone")


def test_resubmits_is_keyword_only_and_defaults_to_refusing():
    """The signature, asserted in both halves, because each half prevents a different mistake.

    KEYWORD-ONLY so that a fifth positional argument can never be read as this flag by a caller
    that meant something else — ``save_stage(workshop_id, spec, payload, actor, True)`` would be a
    resubmission nobody typed the word for.

    DEFAULT ``False`` because the two ways of forgetting it are not comparable, and that asymmetry
    is the argument for having a default at all. A caller that SHOULD resubmit and forgets leaves
    the workshop in NEEDS_REVISION, where the designer sees the send-back panel still open and hands
    it back in through ``PATCH /{id}`` — visible, recoverable, on the screen it happened on. A
    caller that should NOT resubmit and forgets spends a round in a counter with no inverse,
    silently, on a report whose corrections are still unmade. The safe value is the one a forgetful
    call site gets.
    """
    node = _save_stage_def()
    names = [arg.arg for arg in node.args.kwonlyargs]
    assert "resubmits" in names, (
        "save_stage lost its `resubmits` keyword. Without it the resubmission arm is back to "
        "inferring the actor from the fact that it was reached, which is what let an officer's "
        "artisan upload spend a designer's submission round."
    )
    default = node.args.kw_defaults[names.index("resubmits")]
    assert isinstance(default, ast.Constant) and default.value is False, (
        "`resubmits` must default to False — a call site that forgets it must not resubmit"
    )
    assert not any(arg.arg == "resubmits" for arg in node.args.args), (
        "`resubmits` must stay keyword-only"
    )


def test_the_resubmission_arm_asks_the_caller_and_not_the_room():
    """The branch that enters PRE_SUBMISSION must test ``resubmits``, not only the status.

    This is the assertion the deleted comment stood in for. ``workshop_status == "NEEDS_REVISION"``
    and ``_content_changed(...)`` are both true of an officer's bookkeeping write; the actor is the
    term that was missing, and a refactor that drops it again would be silent in the same way.
    """
    node = _save_stage_def()
    arms = [
        branch
        for branch in ast.walk(node)
        if isinstance(branch, ast.If)
        and any(
            isinstance(call.func, ast.Attribute) and call.func.attr == "presubmission_header"
            for call in ast.walk(branch)
            if isinstance(call, ast.Call)
        )
    ]
    assert arms, "nothing in save_stage calls presubmission_header any more"
    for arm in arms:
        guarded = {n.id for n in ast.walk(arm.test) if isinstance(n, ast.Name)}
        assert "resubmits" in guarded, (
            "the PRE_SUBMISSION arm is reachable without the caller claiming to be the editing "
            "party. See the paragraph above it: two officer-driven callers reach save_stage."
        )


def test_exactly_three_call_sites_and_only_the_designer_route_claims_the_party():
    """**THE CENSUS.** A fourth caller of ``save_stage`` fails here, which is the whole point.

    The defect this fences was not a wrong line — it was a caller nobody counted. Two service
    callers landed in the same wave that wrote the invariant, and nothing anywhere would have said
    so: ``tests/test_artisan_import.py`` monkeypatches ``save_stage`` out entirely, so the roster
    path has never once touched the real function.

    The classification, and it is a judgement each time rather than a rule:

    ``api/routes/design_workshops.py``          **resubmits=True.** The only caller holding BOTH
                                                gates — ``_require_designer`` and
                                                ``load_workshop_or_404(..., for_edit=True)``, which
                                                IS the workshop's editing party: creator, admin, or
                                                a ``DesignWorkshopViewer`` row.
    ``services/artisan_import.py``              **omitted, so False.** An officer uploading the
                                                artisan list an inspector JUST ASKED FOR. The
                                                designer cannot upload it — that route is
                                                assigner-gated — so this is the ordinary case and
                                                not an edge one.
    ``services/design_workshop_oversight.py``   **omitted, so False.** An administrator naming a
                                                different designer. That module's own header insists
                                                an officer must never hold a viewer row, i.e. the
                                                accounts reaching it are by design outside the
                                                editing party.

    A new caller is a one-line change here and that is the right fix; it just has to be a deliberate
    one, with the question answered out loud.
    """
    sites = _calls_to("save_stage")
    assert sorted(sites) == [
        "app/api/routes/design_workshops.py",
        "app/services/artisan_import.py",
        "app/services/design_workshop_oversight.py",
    ], (
        f"the set of save_stage callers changed: {sorted(sites)}. Decide whether the new one is a "
        "designer editing their own sent-back report (resubmits=True) or somebody else's "
        "bookkeeping write (leave it off), say why in this docstring, and then add it here."
    )
    for path, calls in sites.items():
        for call in calls:
            passed = _keyword(call, "resubmits")
            if path == "app/api/routes/design_workshops.py":
                assert isinstance(passed, ast.Constant) and passed.value is True, (
                    "the designer's stage-save route must state resubmits=True: it is the only "
                    "call site that has established the actor is in the editing party"
                )
            else:
                # OMITTED OR EXPLICITLY FALSE, both accepted. The default IS False, so omitting it
                # is the ordinary spelling — but a call site that wants to say out loud that an
                # officer's bookkeeping write is not a resubmission is saying the right thing, and
                # a fence that failed on the clearer of two correct spellings would be teaching the
                # wrong lesson. What is refused is the value.
                assert passed is None or (
                    isinstance(passed, ast.Constant) and passed.value is False
                ), (
                    f"{path} claims the editing party. An officer's write is not a designer "
                    "answering an inspector — see the census above."
                )


def test_the_promoted_columns_are_written_by_id_and_never_under_the_status_predicate():
    """**THE CONTENT IS NOT GATED ON THE RACE. ONLY THE COUNTER IS.**

    The compare-and-set that keeps ``submissionRound`` from moving twice was scoped to the WHOLE
    header dict for a day, and ``header`` is never just a status: it is ``schemaVersion`` plus every
    one of the fourteen ``PROMOTED_COLUMNS``. So when another writer moved the status first, the
    statement matched zero rows and the designer's corrected craftName/state/district/venue were
    dropped from ``DesignWorkshop`` while that same transaction's ``DwStageEntry`` rows committed —
    200, empty ``errors``, and the workshop list, its filters, search, the officer's oversight queue,
    the analytics rollup and the .xlsx export all left showing the pre-correction value.

    Asserted as SHAPE, over the source, because reproducing it needs two concurrent writers on one
    workshop and a database: an ``update`` whose ``where`` is the id alone, and an ``update_many``
    whose ``where`` carries the status and whose ``data`` is a different dict from the content one.
    """
    node = _save_stage_def()
    writes = [
        call
        for call in ast.walk(node)
        if isinstance(call, ast.Call)
        and isinstance(call.func, ast.Attribute)
        and call.func.attr in {"update", "update_many"}
        and isinstance(call.func.value, ast.Attribute)
        and call.func.value.attr == "designworkshop"
    ]
    plain = [c for c in writes if c.func.attr == "update"]
    guarded = [c for c in writes if c.func.attr == "update_many"]
    assert len(plain) == 1 and len(guarded) == 1, (
        f"save_stage should make exactly two header writes; found {len(plain)} plain and "
        f"{len(guarded)} guarded. One statement carrying both is the merged form that dropped the "
        "promoted columns whenever the predicate missed."
    )

    def _where_keys(call: ast.Call) -> list[str]:
        where = _keyword(call, "where")
        assert isinstance(where, ast.Dict), "the header write's `where` must be a literal dict"
        return [k.value for k in where.keys if isinstance(k, ast.Constant)]

    assert _where_keys(plain[0]) == ["id"], (
        "the content write must be addressed by id alone. Any predicate on it means a designer's "
        "corrections are dropped in silence whenever it misses."
    )
    assert sorted(_where_keys(guarded[0])) == ["id", "status"], (
        "the guarded write must keep its status predicate — it is what stops the round counter "
        "moving twice for one act, and what makes the re-run loop safe"
    )
    content = _keyword(plain[0], "data")
    transition = _keyword(guarded[0], "data")
    assert isinstance(content, ast.Name) and isinstance(transition, ast.Name)
    assert content.id != transition.id, (
        "the two writes must carry different dicts: the header content by id, and the four "
        "transition keys under the predicate"
    )


# --------------------------------------------------------------------------------------
# 10. What this wave deliberately did NOT change
# --------------------------------------------------------------------------------------


def test_revision_skip_fields_is_unchanged_by_this_wave():
    """The nine keys, and NOT the review trio.

    A ``DesignWorkshop`` has no ``RecordRevision`` ledger — ``record_revision`` is called only by the
    six record PATCH routes — so a key added there would skip nothing for a workshop and would
    silently change the meaning of the six record types the set DOES govern.
    """
    from app.services import access

    assert access.REVISION_SKIP_FIELDS == {
        "extraMetadata",
        "location",
        "locationId",
        "updatedAt",
        "createdAt",
        "createdById",
        "recordedAt",
        "recordedTimezone",
        access.MARKER_BODY_KEY,
    }
    assert "reviewNotes" not in access.REVISION_SKIP_FIELDS


def test_records_py_is_unchanged_by_this_wave():
    """``review_update`` gains a seventh caller and no edit; ``resubmit_status`` gains nothing.

    The keyword-only ``pending`` parameter an earlier draft of this design floated for
    ``resubmit_status`` is deliberately not added: a design workshop's flip is written inside
    ``save_stage``'s transaction, not in a PATCH route, because its content is rows.
    """
    from app.services import records

    assert list(inspect.signature(records.review_update).parameters) == [
        "status_value",
        "notes",
        "reviewer_id",
    ]
    assert list(inspect.signature(records.resubmit_status).parameters) == ["record", "user", "data"]


def test_can_edit_others_record_is_still_false_for_an_inspector():
    """Rank 37 edits nobody's record, and filing a suggestion is not editing.

    This is the sentence the whole feature is built around: an inspector writes a NOTE about the
    report and never the report.
    """
    from app.core import deps

    inspector = SimpleNamespace(role="INSPECTOR")
    for creator_role in sorted(deps.ROLE_RANK):
        assert deps.can_edit_others_record(inspector, creator_role) is False, creator_role


def test_the_stage_registry_did_not_move():
    """This feature is a status and a table, not a 23rd stage.

    A 23rd ``StageSpec`` would move ``registry_version()``, which is the refetch signal for a 119 KB
    file compiled into the APK: every handset in the fleet would treat its bundled schema as stale
    and a release would be forced, for a feature whose entire content is who said what, when. If you
    added one anyway, re-dump ``android/app/src/main/assets/design-workshop-schema.json`` with
    ``pathlib.write_text`` (never a stdout redirect — cp1252 dies on the registry's ✓ AFTER
    truncating the file) and run ``test_controlled_vocabularies.py``.
    """
    import json

    from app.services.stage_schema import registry_version

    bundled = json.loads(
        (
            BACKEND.parent / "android/app/src/main/assets/design-workshop-schema.json"
        ).read_text(encoding="utf-8")
    )
    assert bundled["version"] == registry_version()


def test_the_loop_module_is_importable_by_name():
    """A last, boring assertion: the module resolves where every other file expects it.

    ``importlib`` rather than the ``from`` import at the top of this file, so that a rename shows up
    here as one failing test rather than as a collection error over the whole module.
    """
    assert importlib.import_module("app.schemas.design_workshop_review_loop") is loop
