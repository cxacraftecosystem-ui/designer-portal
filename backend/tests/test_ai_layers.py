"""The layering law, pinned: provenance, acceptance, and the door AI output must never go through.

Step 2 of ``docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md`` §5. What is protected here is the half of
this feature that would be wrong in the same way on every workshop.

**NO DATABASE AND NO NETWORK. NOTHING IN THIS FILE SKIPS.** Every rule under test is decided by a
pure function in ``app.services.ai_layers`` that returns a *plan* instead of performing a write, so
the rules can be asserted on a laptop with no Postgres and no generated Prisma client. That is
deliberate: this repository's history says the untestable half is the half that is wrong, and a
provenance rule that is only exercised by a round-trip script somebody runs occasionally is a
provenance rule nobody is enforcing.

Three groups of test do run an event loop, and none of them reaches a database: the permission
tests drive the real router over HTTP with ``db`` replaced by a tripwire, and
``test_the_list_route_withholds_the_text_of_a_recording_this_caller_may_not_read`` awaits the list
route directly with the workshop load, the layer read and the media table replaced — because
wiring three correct pieces together in the wrong order is exactly how the gate they enforce was
missing in the first place.

The five rules, and what would break in the field if each stopped holding:

1. **Every layer is a row, never an edit.** If a layer could be updated with new model output, the
   second run of a summariser would silently replace an artisan's account of their own work with a
   different paraphrase, and nothing on the page would say it had happened. So the only content
   write this module can express is a CREATE, and the only columns any UPDATE it can produce may
   touch are the four acceptance and deletion stamps — asserted by enumeration, not by reading.
2. **Every layer carries its provenance.** Without the model id, a systematic error found in six
   months — a provider swapping a checkpoint, a quantization that mangles Odia numerals — cannot be
   traced to the material it damaged. A blank provider or model is refused; ``UNRECORDED`` in that
   word is the only way to say "nobody wrote it down", and it has to be typed deliberately.
3. **A layer is inert until a person accepts it**, and the acceptance carries who and when. An
   unsigned acceptance is refused, a second acceptance cannot overwrite the first person's name, and
   a withdrawal keeps the log — because a report generated while the layer was accepted has already
   gone to a directorate naming it as accepted, and that document must stay explicable afterwards.
4. **The report prints the accepted layer and names it as such.** The rendering is another lane's
   work; what is pinned here is the contract it renders from — every payload carries an explicit
   ``accepted`` boolean, so "no acceptance recorded" can never be mistaken for "accepted, timestamp
   missing", and only one of those two may be printed.
5. **Deleting a derived layer never touches its source.** Asserted as a shape: one plan, one row id,
   two columns, and no mention of either source column anywhere in it.

And a sixth rule that is older than this table and that the first draft of the feature broke: **a
copy of a transcript is still the transcript.** Who may read the CONTENT of a recording is decided
per file by ``owned_or_granted_where(user, owner_field="uploadedById")`` and not by who may open the
workshop — those two sets differ, because a ``DesignWorkshopViewer`` grant carries read and stage
writes and says nothing about media. Storing a copy in a new table does not create a new right to
read it, so the chain walk (``media_root``) and the withholding it feeds (``layer_payload``'s
``text_withheld``) are covered here as closely as the five.

And the property AI breaks, which is the whole reason the layers live in a table of their own: the
same audio through Tier 1 on a phone and Tier 3 in the cloud produces different text, legitimately
and for ever, so no AI-produced value may feed a field that is compared across surfaces or any
derived or computed field. ``test_the_stage_table_is_not_writable`` and its neighbours push on that
door from three directions — the plan constructor, the executor's dispatch, and the shape of the
table itself in schema.prisma.
"""

import re
from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.services.ai_layers import (
    ALLOWED_PARENTS,
    MAX_SOURCE_TEXT_CHARS,
    MEDIA_ROOTED_KINDS,
    PREVIEW_CHARS,
    STAGE_TABLE,
    STRUCTURED_KINDS,
    TEXT_KINDS,
    TEXT_ROOTED_KINDS,
    UNRECORDED,
    WRITABLE_TABLES,
    AiTier,
    Decision,
    LayerKind,
    LayerRuleViolation,
    LayerSource,
    LayerWritePlan,
    Operation,
    SourceKind,
    _json_ready,
    _writable_model,
    acceptance_plan,
    decision_payload,
    deletion_plan,
    duplicate_of,
    is_kind,
    layer_create_plan,
    layer_payload,
    media_roots,
    source_of,
    transcript_rungs,
    withdrawal_plan,
)

BACKEND = Path(__file__).resolve().parents[1]
SCHEMA_PRISMA = BACKEND / "prisma" / "schema.prisma"
MIGRATION = BACKEND / "prisma" / "migrations" / "20260811090000_dw_ai_layers" / "migration.sql"

AT = datetime(2026, 8, 11, 9, 30, tzinfo=UTC)


def _create(**overrides):
    """A valid Tier 3 raw transcript registration, with one thing changed at a time by the caller."""
    kwargs = {
        "workshop_id": "wsp_1",
        "kind": LayerKind.RAW_TRANSCRIPT,
        "tier": AiTier.TIER_3,
        "source": LayerSource.media("med_1"),
        "provider": "deepgram",
        "model_id": "nova-3",
        "text": "The dabu paste is mixed with gum and clay.",
        "created_by_id": "usr_1",
    }
    kwargs.update(overrides)
    return layer_create_plan(**kwargs)


def _row(**overrides):
    """A stored layer as Prisma hands it back — a plain object, read only through getattr."""
    fields = {
        "id": "lyr_1",
        "designWorkshopId": "wsp_1",
        "kind": "RAW_TRANSCRIPT",
        "tier": "TIER_3",
        "sourceMediaId": "med_1",
        "sourceLayerId": None,
        "provider": "deepgram",
        "modelId": "nova-3",
        "modelVersion": None,
        "language": "multi",
        "producedAt": None,
        "text": "The dabu paste is mixed with gum and clay.",
        "payload": None,
        "acceptedAt": None,
        "acceptedById": None,
        "createdAt": AT,
        "createdById": "usr_1",
        "deletedAt": None,
        "deletedById": None,
    }
    fields.update(overrides)
    return SimpleNamespace(**fields)


# --------------------------------------------------------------------------------------
# The door: no AI value may reach a field that is compared across surfaces or derived
# --------------------------------------------------------------------------------------


def test_the_stage_table_is_not_writable():
    """A write plan cannot name ``DwStageEntry``, so no code path from here can reach stage data.

    THIS IS THE TEST THE WHOLE FEATURE TURNS ON. The project's core invariant is that the handset
    and the server agree — the compensated sums, the DwPy helpers, the hydration mirror all exist to
    enforce it. AI output cannot have that property: the same audio through Tier 1 on a phone and
    Tier 3 in the cloud differs legitimately and for ever. The day a model's text is allowed into
    ``DwStageEntry.data``, the first cross-surface parity test to fail will be blamed on a bug that
    is actually the design.

    The refusal is in the constructor rather than in a review comment, so opening this door means
    deleting a check and failing this test, which is a visible act in a diff.
    """
    with pytest.raises(LayerRuleViolation) as refused:
        LayerWritePlan(table=STAGE_TABLE, operation=Operation.CREATE, data={"data": {}})
    message = str(refused.value)
    assert STAGE_TABLE in message
    # The sentence has to say WHY, not just no: whoever hits this is about to argue with it.
    assert "compared" in message and "derived" in message


def test_the_executor_has_no_entry_for_the_stage_table():
    """Even a plan that somehow carried the name would have nowhere to be applied.

    The two guards are independent on purpose. ``LayerWritePlan`` refuses at construction; this
    refuses at dispatch, before the Prisma client is touched at all — so the refusal is a sentence
    on a machine with no generated client, and a future edit that loosens one guard still meets the
    other.
    """
    with pytest.raises(LayerRuleViolation):
        _writable_model(STAGE_TABLE)
    assert STAGE_TABLE not in WRITABLE_TABLES
    assert set(WRITABLE_TABLES) == {"DwAiLayer", "DwAiLayerDecision"}


def test_this_module_never_writes_a_stage_entry():
    """No call site in ``ai_layers`` touches the stage table, whatever the plans allow.

    A source scan for the CALL rather than for the name, because the name appears throughout the
    module's prose — every one of those mentions is an explanation of why this line must stay false.
    """
    source = (BACKEND / "app" / "services" / "ai_layers.py").read_text(encoding="utf-8")
    assert "db.dwstageentry" not in source.lower()


def _referenced_names(fn) -> set[str]:
    """Every name and attribute a function's CODE mentions, ignoring its prose.

    Over the syntax tree rather than over the text, and that is not fastidiousness — the first
    version of this scan searched the raw source and failed on the docstrings, which name
    ``refine_transcript_text`` and ``AppSetting.transcriptionMode`` precisely in order to explain why
    the code must never reach them. A guard that cannot tell an explanation from a call would push
    the next author towards deleting the explanation.
    """
    import ast
    import inspect
    import textwrap

    tree = ast.parse(textwrap.dedent(inspect.getsource(fn)))
    names: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Attribute):
            names.add(node.attr)
        elif isinstance(node, ast.Name):
            names.add(node.id)
    return names


def test_no_route_in_the_layer_family_calls_a_provider():
    """Step 2 adds provenance and NOT writing. The five routes must reach no model, ever.

    Asserted against what the routes' code REFERS TO rather than against a promise in a docstring,
    because ``services/ai.py`` is imported by this very module — the dictation endpoint uses it — and
    is therefore one keystroke away from every function in it. Generation arrives in steps 7 and 8 of
    the plan, behind acceptance and gated on measured memory.
    """
    from app.api.routes import design_workshops as routes

    forbidden = {
        "transcribe_audio_bytes",
        "refine_transcript_text",
        "analyze_measurement_image",
        "analyze_measurement_image_bytes",
        "read_identity_card",
        "dwstageentry",
        "save_stage",
    }
    for fn in (
        routes.list_ai_layers,
        routes.register_ai_layer,
        routes.accept_ai_layer,
        routes.unaccept_ai_layer,
        routes.delete_ai_layer,
    ):
        reached = forbidden & _referenced_names(fn)
        assert not reached, f"{fn.__name__} reaches {', '.join(sorted(reached))}"

    # THE POSITIVE CONTROL, without which the loop above proves nothing: a scan that silently
    # matched nothing would pass just as happily. `report_history` sits in the same module and
    # legitimately reads `db.dwstageentry`, so seeing it there is proof this test can fail.
    assert "dwstageentry" in _referenced_names(routes.report_history)


def test_the_layer_table_cannot_name_a_place_in_stage_data():
    """``DwAiLayer`` declares no column that could target a stage, an entity or a field.

    THE OTHER WAY THE DOOR OPENS, and it opens in schema.prisma rather than in Python. A column
    called ``fieldKey`` or ``entityKey`` on this table would be an invitation: it would answer "which
    box does this layer belong in", and the next feature would answer it by writing the model's text
    into that box. There is nowhere to put the target, so the question cannot be asked.

    The provenance columns are checked in the same pass, because rule 2 can be broken by DELETION as
    easily as the layering rule can be broken by addition — a schema edit that dropped ``modelId``
    would leave every future error untraceable and would otherwise pass every other test here.
    """
    block = _prisma_model_block("DwAiLayer")
    columns = _prisma_columns(block)

    for forbidden in ("stageKey", "entityKey", "fieldKey", "entryId", "ordinal"):
        assert forbidden not in columns, (
            f"DwAiLayer.{forbidden} would let a layer name a place inside stage data, which is "
            f"where a later feature would write model text into a field compared across surfaces"
        )
    for required in ("kind", "tier", "provider", "modelId", "modelVersion", "language",
                     "producedAt", "sourceMediaId", "sourceLayerId", "acceptedAt", "acceptedById",
                     "deletedAt"):
        assert required in columns, f"DwAiLayer lost {required}, which rule 2 or rule 3 needs"


def test_the_two_source_pointers_are_deleted_the_same_way():
    """Both source FKs must be Cascade, or an ordinary media delete becomes a 500 for the admin.

    THE FAILURE THIS PINS, WHICH THE FIRST DRAFT SHIPPED. ``sourceLayerId`` was ``onDelete:
    Restrict``, on the reasoning that nothing in this codebase hard-deletes a layer. Nothing does —
    but ``DELETE /api/media/{id}`` hard-deletes a MEDIA file (``db.mediafile.delete``), and
    ``sourceMediaId`` is Cascade, so deleting a recording deletes the raw rung standing on it. On a
    default REFINED_TRANSLATED deployment a registration writes a cleaned rung standing on THAT, and
    its Restrict refused the cascade inside Postgres — where ``delete_media`` has no handler for a
    foreign-key violation, so the admin got "Something went wrong on the server. The error has been
    logged." for deleting a file. It is the same defect ``api/routes/users.py`` already had to grow
    an except clause for, and this table is not entitled to plant it in another lane's endpoint.

    Asserted on both actions together, because it is their DISAGREEMENT that is the bug: whichever
    way a later change takes them, a chain must be deletable in one move or not at all.
    """
    block = _prisma_model_block("DwAiLayer")
    for field in ("sourceMedia", "sourceLayer"):
        line = next(
            (raw for raw in block.splitlines() if raw.strip().startswith(f"{field} ")), ""
        )
        assert "onDelete: Cascade" in line, (
            f"DwAiLayer.{field} is not Cascade; a MediaFile delete then fails inside Postgres and "
            f"reaches the admin as an unexplained 500"
        )

    sql = MIGRATION.read_text(encoding="utf-8")
    for constraint in ("DwAiLayer_sourceMediaId_fkey", "DwAiLayer_sourceLayerId_fkey"):
        # A default rather than a bare `next(...)`, so a renamed or deleted constraint fails as an
        # assertion naming it instead of as a StopIteration nobody can read.
        statement = next(
            (s for s in sql.splitlines() if f'CONSTRAINT "{constraint}"' in s and "ADD" in s), ""
        )
        assert statement, f"{constraint} is not added by the migration at all"
        assert "ON DELETE CASCADE" in statement, f"{constraint} disagrees with schema.prisma"


def test_the_migration_enforces_the_source_pair_in_the_database():
    """The CHECK constraints are in the migration, not only in the service.

    They cover the rows this API never sees — a backfill run by hand, a fixture loaded into a test
    database — and Prisma cannot express either of them, so nothing but this test would notice if a
    later migration dropped them.
    """
    sql = MIGRATION.read_text(encoding="utf-8")
    assert 'CONSTRAINT "DwAiLayer_source_is_exactly_one"' in sql
    assert 'num_nonnulls("sourceMediaId", "sourceLayerId") = 1' in sql
    assert 'CONSTRAINT "DwAiLayer_has_content"' in sql


def _prisma_model_block(name: str) -> str:
    text = SCHEMA_PRISMA.read_text(encoding="utf-8")
    match = re.search(rf"^model {name} \{{(.*?)^\}}", text, re.DOTALL | re.MULTILINE)
    assert match, f"model {name} is not in schema.prisma"
    return match.group(1)


def _prisma_columns(block: str) -> set[str]:
    """The field names a Prisma model block declares, ignoring comments and attributes."""
    names = set()
    for line in block.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(("//", "///", "@@")):
            continue
        names.add(stripped.split()[0])
    return names


# --------------------------------------------------------------------------------------
# Rule 1: every layer is a row, never an edit
# --------------------------------------------------------------------------------------


def test_creating_a_layer_is_always_an_insert():
    plan = _create()
    assert plan.table == "DwAiLayer"
    assert plan.operation is Operation.CREATE
    assert plan.where is None


def test_no_update_this_module_can_produce_touches_content_or_provenance():
    """The only columns any UPDATE here may write are the four acceptance and deletion stamps.

    ENUMERATED RATHER THAN ARGUED. Every function in the module that yields an UPDATE is called
    below and its data keys are unioned; if a later change adds a "re-run this layer" path that
    writes ``text`` in place, this union grows and the test fails. That is the failure mode rule 1
    exists to prevent — the second run of a summariser overwriting the first, with no row left
    saying what the first one said.
    """
    accepted = acceptance_plan(_row(), actor_id="usr_2", at=AT)
    withdrawn = withdrawal_plan(_row(acceptedAt=AT, acceptedById="usr_2"), actor_id="usr_2")
    deleted = deletion_plan(_row(), actor_id="usr_2", at=AT)

    written: set[str] = set()
    for plan in (accepted.layer, withdrawn.layer, deleted):
        assert plan.operation is Operation.UPDATE
        assert plan.where == {"id": "lyr_1"}
        written |= set(plan.data)

    assert written == {"acceptedAt", "acceptedById", "deletedAt", "deletedById"}


def test_a_created_layer_records_its_source_as_exactly_one_pointer():
    from_media = _create().data
    assert from_media["sourceMediaId"] == "med_1"
    assert from_media["sourceLayerId"] is None

    from_layer = _create(
        kind=LayerKind.CLEANED_TRANSCRIPT,
        source=LayerSource.layer("lyr_1", LayerKind.RAW_TRANSCRIPT),
        text="Cleaned.",
    ).data
    assert from_layer["sourceLayerId"] == "lyr_1"
    assert from_layer["sourceMediaId"] is None


def test_a_source_that_is_both_or_neither_is_refused_on_the_way_out_of_the_database():
    """A stored row with two sources or none cannot be printed or trusted, and says so.

    Defensive against the rows the CHECK constraint exists for: a hand-run backfill, or a database
    restored from before the constraint. Treating a null-and-null row as media-sourced — which is
    what a bare ``getattr`` would do — would put a layer with no evidence behind it into an annexure.
    """
    for broken in (
        _row(sourceMediaId=None, sourceLayerId=None),
        _row(sourceMediaId="med_1", sourceLayerId="lyr_0"),
    ):
        with pytest.raises(LayerRuleViolation):
            source_of(broken)


# --------------------------------------------------------------------------------------
# Rule 2: provenance, and the honest unknown
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("blank", ["", "   ", None])
def test_a_layer_with_no_named_model_is_refused(blank):
    """The refusal names both ways out, because the caller has to choose one deliberately."""
    with pytest.raises(LayerRuleViolation) as refused:
        _create(model_id=blank)
    assert UNRECORDED in str(refused.value)

    with pytest.raises(LayerRuleViolation):
        _create(provider=blank)


def test_unrecorded_is_a_value_and_not_a_null():
    """The common case on day one, and it must be stored as a statement rather than as an absence.

    ``media_queue._transcript_write`` stores the transcript, its summary, its status and its error —
    and not which of the four providers in ``services/ai.py`` produced it. So every transcript
    already in the archive has a provider nobody wrote down. A null would read as "none"; the word
    reads as what it is.
    """
    data = _create(provider=UNRECORDED, model_id=UNRECORDED).data
    assert data["provider"] == UNRECORDED
    assert data["modelId"] == UNRECORDED
    assert data["provider"] is not None


def test_the_time_the_model_ran_is_never_invented():
    """``producedAt`` stays null when it is not known, rather than defaulting to now().

    A transcript the queue produced last March and registered as a layer today would otherwise carry
    today's date as a statement about when the model ran — a fabricated fact of exactly the kind
    rule 2 exists to prevent. ``createdAt`` answers the other question and is the row's own default.
    """
    assert _create().data["producedAt"] is None
    ran_at = datetime(2026, 3, 4, 11, 20, tzinfo=UTC)
    assert _create(produced_at=ran_at).data["producedAt"] == ran_at


def test_language_multi_survives_verbatim():
    """'multi' is an answer, not a placeholder: Deepgram Nova-3 is called with ``language=multi``
    precisely because a workshop is Hindi code-switched with English mid-sentence. A column that
    only admitted one BCP-47 tag would force a lie onto most of the archive."""
    assert _create(language="multi").data["language"] == "multi"
    assert _create(language="  ").data["language"] is None


def test_the_tier_is_recorded_and_is_not_a_ranking():
    """Tier is which MACHINE ran the model, and the plan's §2.1 safeguard depends on it being on the
    row: a 4 GB handset and a 12 GB flagship will not run the same tier, so the fleet produces two
    classes of record and only this column tells them apart on the page.

    (This said "a 4 GB M32" until 2026-08-12, when the M32 was attached and asked: it reports
    5,927,968,768 bytes — 5.52 GiB — so it is not a 4 GB phone and never was. The argument is about
    two device classes and does not need that handset to be one of them; see
    docs/DEVICE-TIER-MEASUREMENT.md.)"""
    assert _create(tier=AiTier.TIER_1).data["tier"] == "TIER_1"
    assert AiTier.TIER_2.number == 2
    # Not to be confused with stage_schema.Tier (BASIC/STANDARD/ADVANCED), which is about fields.
    assert {t.value for t in AiTier} == {"TIER_1", "TIER_2", "TIER_3"}


# --------------------------------------------------------------------------------------
# The chains: what may sit above what
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("kind", "parent"),
    [
        (LayerKind.CLEANED_TRANSCRIPT, LayerKind.RAW_TRANSCRIPT),
        (LayerKind.SUMMARY, LayerKind.CLEANED_TRANSCRIPT),
        (LayerKind.SUMMARY, LayerKind.RAW_TRANSCRIPT),
        (LayerKind.STRUCTURED_TEXT, LayerKind.OCR_TEXT),
        (LayerKind.TAGS, LayerKind.RAW_TRANSCRIPT),
        (LayerKind.METADATA, LayerKind.SUMMARY),
    ],
)
def test_the_plan_s_chains_are_accepted(kind, parent):
    plan = _create(
        kind=kind,
        source=LayerSource.layer("lyr_1", parent),
        text="Some text." if kind in TEXT_KINDS else None,
        payload=["dabu", "indigo"] if kind in STRUCTURED_KINDS else None,
    )
    assert plan.data["kind"] == kind.value


@pytest.mark.parametrize(
    ("kind", "parent"),
    [
        (LayerKind.SUMMARY, LayerKind.OCR_TEXT),
        (LayerKind.CLEANED_TRANSCRIPT, LayerKind.SUMMARY),
        (LayerKind.STRUCTURED_TEXT, LayerKind.RAW_TRANSCRIPT),
    ],
)
def test_a_rung_that_cannot_sit_there_is_refused_by_name(kind, parent):
    with pytest.raises(LayerRuleViolation) as refused:
        _create(kind=kind, source=LayerSource.layer("lyr_1", parent), text="x", payload={"a": 1})
    assert kind.value in str(refused.value)


def test_a_raw_rung_must_come_from_media_and_the_rungs_above_it_must_not():
    """The two ends of both chains, refused in both directions.

    A RAW_TRANSCRIPT derived from another layer would be a transcript of a transcript. A SUMMARY
    derived straight from a media file would print a conclusion with nothing underneath it that a
    reader could check — which is the whole reason the extractive verbs are made to sit on a text
    rung rather than on the recording.
    """
    with pytest.raises(LayerRuleViolation):
        _create(source=LayerSource.layer("lyr_1", LayerKind.RAW_TRANSCRIPT))
    with pytest.raises(LayerRuleViolation):
        _create(kind=LayerKind.SUMMARY, source=LayerSource.media("med_1"), text="A summary.")
    with pytest.raises(LayerRuleViolation):
        _create(kind=LayerKind.TAGS, source=LayerSource.media("med_1"), payload=["dabu"])


def test_a_derived_layer_must_name_its_parent_s_kind():
    """Because what may sit above a rung depends entirely on which rung it is, and the create path
    holds the parent row anyway. The read path does NOT know it — there is no ``sourceLayerKind``
    column, deliberately, since a copy of the parent's kind is a second place for it to be wrong."""
    with pytest.raises(LayerRuleViolation) as refused:
        _create(kind=LayerKind.SUMMARY, source=LayerSource.layer("lyr_1"), text="A summary.")
    assert "kind" in str(refused.value)


def test_every_kind_can_be_created_somehow():
    """No kind is declared and then unreachable.

    A vocabulary with a value nothing can produce is a heading the annexure will never print and a
    reader will go looking for. Every kind is media-rooted, has a parent set, or may stand on words
    the caller supplied — and every kind carries one of the two sorts of content.
    """
    for kind in LayerKind:
        assert (
            kind in MEDIA_ROOTED_KINDS
            or kind in TEXT_ROOTED_KINDS
            or ALLOWED_PARENTS.get(kind)
        ), kind
        assert kind in TEXT_KINDS or kind in STRUCTURED_KINDS, kind


def test_the_verbs_sit_where_the_law_says_and_nowhere_else():
    """The five verb kinds, each accepted in its own place and refused in the others.

    ONE TEST FOR THE WHOLE SET because the rules only make sense against each other. Read together
    they are the argument: an expansion may stand on the designer's own words and on nothing else,
    subtitles may stand on a recording and on nothing else, and a translation may stand on anything
    somebody actually said but never on another translation.
    """
    note = LayerSource.supplied_text("dabu paste — gum, clay, 3 days")

    # Accepted.
    assert _create(kind=LayerKind.EXPANDED, source=note, text="The dabu paste is mixed…")
    assert _create(kind=LayerKind.PROOFREAD, source=note, text="Dabu paste — gum, clay, 3 days.")
    assert _create(
        kind=LayerKind.PROOFREAD,
        source=LayerSource.layer("lyr_1", LayerKind.RAW_TRANSCRIPT),
        text="The dabu paste is mixed with gum and clay.",
    )
    assert _create(
        kind=LayerKind.TRANSLATION,
        source=LayerSource.layer("lyr_1", LayerKind.RAW_TRANSCRIPT),
        text="The dabu paste is mixed with gum and clay.",
        source_language="or",
        target_language="en",
    )
    assert _create(kind=LayerKind.CAPTION, source=LayerSource.media("med_1"), text="A block printer…")
    assert _create(
        kind=LayerKind.SUBTITLES,
        source=LayerSource.media("med_1"),
        text="The dabu paste…",
        payload={"cues": [{"start": 0.0, "end": 2.0, "text": "The dabu paste…"}]},
    )

    # Refused, each for its own stated reason.
    with pytest.raises(LayerRuleViolation) as no_expanding_an_artisan:
        _create(
            kind=LayerKind.EXPANDED,
            source=LayerSource.layer("lyr_1", LayerKind.RAW_TRANSCRIPT),
            text="The artisan explained at length…",
        )
    assert "EXPANDED" in str(no_expanding_an_artisan.value)

    with pytest.raises(LayerRuleViolation):
        # Subtitles have to come from the audio: the timings exist nowhere else, and a subtitle rung
        # standing on a transcript would have to invent when each line was spoken.
        _create(
            kind=LayerKind.SUBTITLES,
            source=LayerSource.layer("lyr_1", LayerKind.RAW_TRANSCRIPT),
            text="x",
            payload={"cues": []},
        )
    with pytest.raises(LayerRuleViolation):
        # No pivot translation.
        _create(
            kind=LayerKind.TRANSLATION,
            source=LayerSource.layer("lyr_1", LayerKind.TRANSLATION),
            text="x",
            source_language="en",
            target_language="hi",
        )
    with pytest.raises(LayerRuleViolation):
        # Nothing may be derived from an expansion.
        _create(
            kind=LayerKind.SUMMARY,
            source=LayerSource.layer("lyr_1", LayerKind.EXPANDED),
            text="x",
        )
    with pytest.raises(LayerRuleViolation):
        # A caption is about a photograph, not about supplied words.
        _create(kind=LayerKind.CAPTION, source=note, text="A block printer…")


def test_a_supplied_source_carries_the_words_and_names_no_row():
    plan = _create(
        kind=LayerKind.PROOFREAD,
        source=LayerSource.supplied_text("  the dabu paist is mixt  "),
        text="The dabu paste is mixed.",
    )
    assert plan.data["sourceText"] == "the dabu paist is mixt"
    assert plan.data["sourceMediaId"] is None
    assert plan.data["sourceLayerId"] is None


def test_a_supplied_source_that_names_a_row_as_well_is_refused():
    """Both would be a row claiming two origins, which the database's CHECK also refuses — two guards
    for one rule, because the constraint gives a 500 with a Postgres message and this gives a
    sentence a client can act on."""
    with pytest.raises(LayerRuleViolation):
        LayerSource(kind=SourceKind.SUPPLIED_TEXT, id="lyr_1", text="a note")
    with pytest.raises(LayerRuleViolation):
        LayerSource(kind=SourceKind.LAYER, id="lyr_1", text="a note")


def test_a_note_longer_than_the_bound_is_refused_and_never_clipped():
    """A proofread of the first ten pages of a twelve-page note, recorded as a proofread of the note,
    is a layer whose source is not what it says it is."""
    with pytest.raises(LayerRuleViolation) as refused:
        _create(
            kind=LayerKind.PROOFREAD,
            source=LayerSource.supplied_text("x" * (MAX_SOURCE_TEXT_CHARS + 1)),
            text="X.",
        )
    assert str(MAX_SOURCE_TEXT_CHARS) in str(refused.value).replace(",", "")


def test_an_over_long_note_already_stored_is_still_readable():
    """The bound is a CREATE-time policy and is deliberately not enforced when reading a row back.

    Enforced in ``LayerSource.__post_init__`` it would run over stored rows too — ``source_of``
    builds one from every row it reads — so lowering the bound one day would blank the "made from"
    line of every layer written under the old one, in an annexure whose whole purpose is showing what
    a passage was made from.
    """
    row = _row(
        kind="PROOFREAD",
        sourceMediaId=None,
        sourceText="x" * (MAX_SOURCE_TEXT_CHARS + 500),
        text="X.",
    )
    assert layer_payload(row)["source"]["kind"] == "SUPPLIED_TEXT"


def test_a_translation_must_name_both_ends():
    """Rule 2 for the one verb where a single language is not a provenance record.

    "In English" says nothing about whether the artisan spoke Odia, Hindi or both — which is exactly
    what a reader checking a translated passage against the original has to know first.
    """
    with pytest.raises(LayerRuleViolation) as no_target:
        _create(
            kind=LayerKind.TRANSLATION,
            source=LayerSource.layer("lyr_1", LayerKind.RAW_TRANSCRIPT),
            text="x",
            source_language="or",
        )
    assert "INTO" in str(no_target.value)

    with pytest.raises(LayerRuleViolation) as no_source_language:
        _create(
            kind=LayerKind.TRANSLATION,
            source=LayerSource.layer("lyr_1", LayerKind.RAW_TRANSCRIPT),
            text="x",
            target_language="en",
        )
    assert "FROM" in str(no_source_language.value)

    # `multi` is a real answer on the way IN — these interviews code-switch mid-sentence — and not a
    # request anybody can act on on the way OUT.
    assert _create(
        kind=LayerKind.TRANSLATION,
        source=LayerSource.layer("lyr_1", LayerKind.RAW_TRANSCRIPT),
        text="x",
        source_language="multi",
        target_language="en",
    )
    with pytest.raises(LayerRuleViolation):
        _create(
            kind=LayerKind.TRANSLATION,
            source=LayerSource.layer("lyr_1", LayerKind.RAW_TRANSCRIPT),
            text="x",
            source_language="or",
            target_language="multi",
        )


def test_a_translation_writes_its_target_into_the_plain_language_column_too():
    """So a client or a query that knows only about ``language`` is not silently wrong about a
    translation — the two are the same fact and must not be able to disagree."""
    plan = _create(
        kind=LayerKind.TRANSLATION,
        source=LayerSource.layer("lyr_1", LayerKind.RAW_TRANSCRIPT),
        text="The dabu paste is mixed with gum and clay.",
        source_language="or",
        target_language="en",
    )
    assert plan.data["language"] == "en"
    assert plan.data["sourceLanguage"] == "or"
    assert plan.data["targetLanguage"] == "en"


def test_only_a_translation_may_carry_a_language_pair():
    """A caption with a target language would be claiming a translation happened; a proofread with
    one would be claiming the language changed, which is the single thing a proofread promises not
    to do."""
    with pytest.raises(LayerRuleViolation):
        _create(
            kind=LayerKind.PROOFREAD,
            source=LayerSource.supplied_text("a note"),
            text="A note.",
            target_language="en",
        )


# --------------------------------------------------------------------------------------
# Content: a provenance record for nothing is not worth a row
# --------------------------------------------------------------------------------------


def test_a_prose_kind_with_no_prose_is_refused():
    with pytest.raises(LayerRuleViolation):
        _create(text=None)
    with pytest.raises(LayerRuleViolation):
        _create(text="   ")


def test_a_structured_kind_with_no_payload_is_refused():
    with pytest.raises(LayerRuleViolation):
        _create(
            kind=LayerKind.TAGS,
            source=LayerSource.layer("lyr_1", LayerKind.RAW_TRANSCRIPT),
            text="dabu, indigo",
            payload=None,
        )


def test_a_payload_is_wrapped_for_the_driver_and_so_is_an_absent_one():
    """A raw list or dict reaching a Prisma Json column is a 500, not a 422 — the driver raises
    rather than validating, so a perfectly good set of tags would come back as "something went wrong
    on the server". ``design_workshops._json`` carries the same note for the stage table.

    **AND SO IS A None, WHICH THIS TEST USED TO ASSERT THE OPPOSITE OF.** It read

        assert _json_ready("DwAiLayer", {"payload": None})["payload"] is None

    and it passed, for as long as the module existed, while every prose layer registration answered
    500. A bare ``None`` is not "SQL NULL" to this driver, it is ``payload: null`` in the query, and
    the query engine refuses ``null`` for a nullable ``Json`` column outright
    (``MissingRequiredValueError``). ``layer_create_plan`` puts ``"payload": payload`` in
    unconditionally with a ``None`` default, so that was every CLEANED_TRANSCRIPT, TRANSLATION,
    PROOFREAD and EXPANDED row there has ever been.

    The old assertion is why nothing caught it: it pinned the exact value that cannot be written, and
    no test in this file reaches a database, so the one thing that would have failed never ran. The
    refusal was measured directly against this deployment's Postgres before this test was rewritten.
    """
    from prisma import Json

    wrapped = _json_ready("DwAiLayer", {"text": "x", "payload": ["dabu", "indigo"]})
    assert isinstance(wrapped["payload"], Json)
    assert wrapped["text"] == "x"
    # An ABSENT payload is still wrapped, because `null` is the one value the driver will not carry.
    absent = _json_ready("DwAiLayer", {"payload": None})
    assert isinstance(absent["payload"], Json)
    # A column this table does not declare as Json is untouched, and so is a key that is not there:
    # wrapping an absent key would turn "leave it alone" into "set it to null" on an update.
    assert "payload" not in _json_ready("DwAiLayer", {"text": "x"})
    assert _json_ready("DwAiLayerDecision", {"note": "x"}) == {"note": "x"}


def test_prose_may_carry_structure_beside_it():
    """Scribe v2 diarizes up to 32 speakers, and those turns belong with the text they came from
    rather than in a second row somebody has to keep in step with it."""
    turns = [{"speaker": 1, "text": "The dabu paste…"}]
    assert _create(payload=turns).data["payload"] == turns


# --------------------------------------------------------------------------------------
# What a stored transcript actually IS, which is not always what its column is called
# --------------------------------------------------------------------------------------


def test_a_refined_transcript_is_not_registered_as_a_raw_one():
    """THE DEFAULT DEPLOYMENT'S COMMON CASE, and the one that would have printed a lie.

    ``AppSetting.transcriptionMode`` defaults to REFINED_TRANSLATED, under which ``media_queue``
    passes the provider's text through ``ai.refine_transcript_text`` — a rewrite AND a translation
    into English — stores that in ``transcriptText``, and keeps the provider's own words in
    ``transcriptSummary``. An annexure heading reading "Raw transcript" over an English paraphrase
    of what an Odia-speaking artisan said is precisely the failure rule 4 exists to prevent.
    """
    raw, cleaned = transcript_rungs(
        transcript_text="**Interviewer:** How is the paste mixed?\n**Artisan:** With gum and clay.",
        transcript_summary="dabu paste gum clay mixing haan ji",
    )
    assert raw == "dabu paste gum clay mixing haan ji"
    assert cleaned == (
        "**Interviewer:** How is the paste mixed?\n**Artisan:** With gum and clay."
    )


def test_an_unrefined_transcript_is_one_rung_despite_the_formatting_header():
    """``ai._transcription_result`` wraps the provider's text as "Transcript\\n\\n…" — a header and
    nothing else. A naive inequality between the two columns would call every unrefined row
    "cleaned" and invent a second layer describing a model run that never happened."""
    raw, cleaned = transcript_rungs(
        transcript_text="Transcript\n\ndabu paste gum clay",
        transcript_summary="dabu paste gum clay",
    )
    assert raw == "dabu paste gum clay"
    assert cleaned is None


def test_the_decision_never_consults_todays_transcription_mode():
    """Decided from the row's own evidence, because the mode in force TODAY says nothing about the
    mode in force when a transcript from March was written. Asserted as an absence: the function
    takes two strings and nothing else, so there is no setting it could read."""
    import inspect

    assert set(inspect.signature(transcript_rungs).parameters) == {
        "transcript_text",
        "transcript_summary",
    }
    reached = _referenced_names(transcript_rungs)
    for settings_name in ("load_app_settings", "transcription_mode", "get_settings", "db"):
        assert settings_name not in reached


def test_a_row_with_no_provider_text_is_the_weakest_claim_and_not_a_confident_one():
    """A transcript with no summary — written before the pair existed, or typed by a person against
    a clip that was never transcribed — cannot be classified, because the comparison is the only
    evidence of rewriting. One rung, which is the weakest claim available rather than a confident
    wrong one. What provenance goes on it is the CALLER's to state and this function neither sets
    nor sees it: an earlier version of this docstring said the row "carries UNRECORDED", which is
    only true when the body names nothing."""
    assert transcript_rungs(transcript_text="Some text", transcript_summary=None) == (
        "Some text",
        None,
    )
    assert transcript_rungs(transcript_text="Some text", transcript_summary="   ") == (
        "Some text",
        None,
    )


def test_a_person_s_correction_is_indistinguishable_from_a_model_s_rewrite():
    """Two rungs mean "this is not the provider's own words" and NOT "a model wrote this".

    ``POST /media/{id}/transcript`` replaces ``transcriptText`` and leaves ``transcriptSummary``
    exactly as the queue wrote it (``api/routes/media.py:641``), so a transcript a designer typed or
    corrected by hand produces the same shape as ``ai.refine_transcript_text``'s rewrite, and no
    column on ``MediaFile`` records who or what last wrote that text. This function therefore cannot
    tell them apart, and neither may the caller: registering the second rung with a named model
    would print a designer's own corrections in a government annexure as AI-cleaned prose, which is
    rule 4's failure with the sides swapped. The register route passes UNRECORDED for both the
    provider and the model on this rung for exactly this reason.
    """
    raw, cleaned = transcript_rungs(
        transcript_text="**Interviewer:** How is the paste mixed?\n**Artisan:** With gum and clay.",
        transcript_summary="dabu paste gum clay mixing haan ji",
    )
    assert raw == "dabu paste gum clay mixing haan ji"
    assert cleaned == (
        "**Interviewer:** How is the paste mixed?\n**Artisan:** With gum and clay."
    )
    # The split is all this returns: two strings, and no third value naming an author — there is
    # nothing on the row it could be read from, so there is nowhere for the caller to get one wrong.
    assert len(transcript_rungs(transcript_text="a", transcript_summary="b")) == 2


def test_a_recording_with_nothing_transcribed_is_refused_with_the_next_move():
    with pytest.raises(LayerRuleViolation) as refused:
        transcript_rungs(transcript_text=None, transcript_summary=None)
    assert "transcripts screen" in str(refused.value)


# --------------------------------------------------------------------------------------
# Rule 3: acceptance is a transition with an audit, not a boolean
# --------------------------------------------------------------------------------------


def test_a_new_layer_is_inert():
    """No argument to the create path can set an acceptance, and none of its columns appear."""
    data = _create().data
    assert "acceptedAt" not in data
    assert "acceptedById" not in data


def test_accepting_records_who_and_when_and_appends_to_the_log():
    plans = acceptance_plan(_row(), actor_id="usr_2", at=AT, note="Read against the recording.")
    assert plans.layer.data == {"acceptedAt": AT, "acceptedById": "usr_2"}
    assert plans.decision.table == "DwAiLayerDecision"
    assert plans.decision.operation is Operation.CREATE
    assert plans.decision.data == {
        "layerId": "lyr_1",
        "decision": Decision.ACCEPTED.value,
        "note": "Read against the recording.",
        "actorId": "usr_2",
    }
    # Both writes travel together — iterating the pair is how the route applies them.
    assert list(plans) == [plans.layer, plans.decision]


def test_an_unsigned_acceptance_is_not_an_acceptance():
    with pytest.raises(LayerRuleViolation):
        acceptance_plan(_row(), actor_id="", at=AT)


def test_a_second_acceptance_cannot_overwrite_the_first_person_s_name():
    """The one fact about an accepted layer a reader of a submitted report may need to trace."""
    with pytest.raises(LayerRuleViolation) as refused:
        acceptance_plan(_row(acceptedAt=AT, acceptedById="usr_2"), actor_id="usr_3", at=AT)
    assert "withdraw" in str(refused.value).lower()


def test_withdrawing_clears_the_state_and_keeps_the_history():
    """A report generated while the layer was accepted named it as accepted, and that document does
    not change because somebody changed their mind on the 11th. The log is what still explains it —
    so a withdrawal writes a second decision row rather than deleting the first."""
    plans = withdrawal_plan(
        _row(acceptedAt=AT, acceptedById="usr_2"), actor_id="usr_3", note="Invented a village name."
    )
    assert plans.layer.data == {"acceptedAt": None, "acceptedById": None}
    assert plans.decision.data["decision"] == Decision.WITHDRAWN.value
    assert plans.decision.data["actorId"] == "usr_3"
    assert plans.decision.data["note"] == "Invented a village name."


def test_withdrawing_something_nobody_accepted_says_what_to_do_instead():
    with pytest.raises(LayerRuleViolation) as refused:
        withdrawal_plan(_row(), actor_id="usr_2")
    assert "delete" in str(refused.value).lower()


def test_a_deleted_layer_can_be_neither_accepted_nor_withdrawn():
    deleted = _row(deletedAt=AT, acceptedAt=AT, acceptedById="usr_2")
    with pytest.raises(LayerRuleViolation):
        acceptance_plan(_row(deletedAt=AT), actor_id="usr_2", at=AT)
    with pytest.raises(LayerRuleViolation):
        withdrawal_plan(deleted, actor_id="usr_2")


# --------------------------------------------------------------------------------------
# Rule 4: the payload the report and the acceptance screen read
# --------------------------------------------------------------------------------------


def test_acceptance_is_explicit_on_the_wire():
    """"No acceptance recorded" and "accepted, timestamp missing" must not look alike, because only
    one of the two may be printed into a government document."""
    assert layer_payload(_row())["accepted"] is False
    accepted = layer_payload(_row(acceptedAt=AT, acceptedById="usr_2"))
    assert accepted["accepted"] is True
    assert accepted["acceptedAt"] == AT.isoformat()
    assert accepted["acceptedById"] == "usr_2"


def test_the_payload_carries_the_whole_provenance_in_camel_case():
    payload = layer_payload(_row(modelVersion="2026-02", producedAt=AT))
    assert payload["kind"] == "RAW_TRANSCRIPT"
    assert payload["tier"] == "TIER_3"
    assert payload["provider"] == "deepgram"
    assert payload["modelId"] == "nova-3"
    assert payload["modelVersion"] == "2026-02"
    assert payload["language"] == "multi"
    assert payload["producedAt"] == AT.isoformat()
    assert payload["source"] == {"kind": SourceKind.MEDIA.value, "id": "med_1", "text": None}
    # The translation pair is on EVERY payload and null on everything that is not a translation, so a
    # client never has to read `kind` before it knows whether a key exists.
    assert payload["sourceLanguage"] is None
    assert payload["targetLanguage"] is None
    # snake_case in Python, camelCase on the wire — the house rule, and clients depend on it.
    assert not [key for key in payload if "_" in key]


def test_a_list_carries_a_preview_and_not_the_transcript():
    """A workshop can hold twenty-five interviews; an hour of speech is tens of kilobytes. A list
    that carried every layer's full text would be megabytes on one bar of signal, and unread."""
    long_text = "word " * 400
    payload = layer_payload(_row(text=long_text))
    assert "text" not in payload
    assert payload["textChars"] == len(long_text)
    assert len(payload["preview"]) <= PREVIEW_CHARS + 1  # + the ellipsis
    assert payload["preview"].endswith("…")

    full = layer_payload(_row(text=long_text), include_text=True)
    assert full["text"] == long_text


def test_the_preview_is_one_line_because_a_transcript_starts_with_a_speaker_label():
    """The stored form carries ``**Interviewer:**`` labels on their own line, so a preview that kept
    the newlines would show a list of rows all reading "**Interviewer:**" and distinguishing
    nothing."""
    payload = layer_payload(_row(text="**Interviewer:**\n\nHow is the dabu paste mixed?"))
    assert payload["preview"] == "**Interviewer:** How is the dabu paste mixed?"


def test_a_malformed_row_renders_with_no_source_rather_than_taking_out_the_list():
    """One bad row must not hide the other twenty-four transcripts a designer came to look at."""
    payload = layer_payload(_row(sourceMediaId=None, sourceLayerId=None))
    assert payload["source"] is None
    assert payload["id"] == "lyr_1"


# --------------------------------------------------------------------------------------
# The media gate: a copy of a transcript is still the transcript
# --------------------------------------------------------------------------------------


def test_the_chain_walk_finds_the_recording_a_layer_stands_on():
    """Which recording a layer came from is what decides who may read its text, so the walk from a
    summary down to the audio has to arrive — through as many rungs as the chain has."""
    raw = _row(id="lyr_1", sourceMediaId="med_1")
    cleaned = _row(id="lyr_2", kind="CLEANED_TRANSCRIPT", sourceMediaId=None, sourceLayerId="lyr_1")
    summary = _row(id="lyr_3", kind="SUMMARY", sourceMediaId=None, sourceLayerId="lyr_2")
    assert media_roots([raw, cleaned, summary]) == {
        "lyr_1": "med_1",
        "lyr_2": "med_1",
        "lyr_3": "med_1",
    }


@pytest.mark.parametrize(
    ("name", "rows"),
    [
        ("parent missing", [_row(id="lyr_2", sourceMediaId=None, sourceLayerId="lyr_gone")]),
        ("self reference", [_row(id="lyr_2", sourceMediaId=None, sourceLayerId="lyr_2")]),
        (
            "two rows naming each other",
            [
                _row(id="lyr_2", sourceMediaId=None, sourceLayerId="lyr_3"),
                _row(id="lyr_3", sourceMediaId=None, sourceLayerId="lyr_2"),
            ],
        ),
        ("no source at all", [_row(id="lyr_2", sourceMediaId=None, sourceLayerId=None)]),
    ],
)
def test_a_chain_that_cannot_be_walked_names_no_recording(name, rows):
    """Every one of these answers None, and None means WITHHOLD at the call site.

    None of these shapes can be written through this API — the CHECK constraint refuses the last,
    and nothing here writes a cycle — but the foreign key on ``sourceLayerId`` points at this same
    table, so Postgres would accept a row naming itself if somebody wrote one by hand. An entitlement
    check that fell back to "allow" when the evidence ran out would leak exactly the row that was
    tampered with, and an unbounded walk would spin inside a request a designer is waiting on.
    """
    assert set(media_roots(rows).values()) == {None}


def test_a_withheld_layer_keeps_its_provenance_and_loses_every_form_of_its_content():
    """THE LEAK THIS CLOSES. A layer's text is a stored copy of a transcript, and a transcript is the
    CONTENT of a recording — gated per file by ``owned_or_granted_where(user,
    owner_field="uploadedById")`` on the stage read, the transcripts endpoint and the annexure. Who
    may open a workshop is a different set: a ``DesignWorkshopViewer`` grant carries read and stage
    writes and says nothing about media. Serving this table on the workshop gate alone handed a
    granted colleague the full text of a recording ``GET /design-workshops/{id}/transcripts``
    refuses them.

    All four shapes of the content go together, because a 280-character opening is not less of a
    transcript for being short and a diarized payload is the same speech in another arrangement.
    """
    withheld = layer_payload(
        _row(text="The dabu paste is mixed with gum and clay.", payload=[{"speaker": 1}]),
        include_text=True,
        text_withheld=True,
    )
    assert withheld["textWithheld"] is True
    assert "text" not in withheld
    assert withheld["preview"] is None
    assert withheld["payload"] is None
    # Null and not 0: "there is nothing to read" is a different fact, and the false one.
    assert withheld["textChars"] is None

    # And the provenance stays, which is why the row is withheld rather than omitted — plan §2.1
    # says a reviewer must be able to tell a cloud transcript from a device-guessed one on the page.
    assert withheld["tier"] == "TIER_3"
    assert withheld["modelId"] == "nova-3"
    # The source POINTER stays — it is not the recording's content, and a reviewer tracing a layer
    # back needs it. Any supplied WORDS on it go with the rest of the content; on a media-rooted row
    # there are none, which is what this null says.
    assert withheld["source"] == {"kind": SourceKind.MEDIA.value, "id": "med_1", "text": None}


def test_a_withheld_layer_loses_the_words_a_verb_was_given_as_well_as_the_ones_it_produced():
    """``source.text`` is content and is withheld with the rest of it.

    A supplied-text source is the passage a proofread or an expansion was run over, carried on the
    row because there is no other row holding it. On a supplied-text chain the gate never fires — the
    words are the caller's own and there is no recording to be entitled to (``ChainRoot``) — so this
    covers the shape that DOES reach here: a row whose ``sourceText`` was written by a backfill onto
    a chain the walk could not resolve, which fails closed. Serving the passage while withholding the
    correction of it would hand over the same words in a slightly worse spelling.
    """
    withheld = layer_payload(
        _row(
            kind="PROOFREAD",
            sourceMediaId=None,
            sourceText="the dabu paist is mixt with gum",
            text="The dabu paste is mixed with gum.",
        ),
        text_withheld=True,
    )
    assert withheld["source"] == {"kind": "SUPPLIED_TEXT", "id": None, "text": None}
    assert withheld["preview"] is None


def test_every_payload_states_whether_its_text_was_withheld():
    """On every row, not only the withheld ones: a client must render "you cannot read this" from a
    stated fact rather than infer it from an empty preview — the same reason ``accepted`` is an
    explicit boolean beside a nullable timestamp."""
    assert layer_payload(_row())["textWithheld"] is False


def test_the_list_route_withholds_the_text_of_a_recording_this_caller_may_not_read(monkeypatch):
    """The gate WIRED UP, not only its decision function — the half that is usually wrong.

    The route is driven directly with the workshop load, the layer read and the media table replaced,
    so the thing actually under test is what this file cannot otherwise reach: that the chain walk,
    the entitlement query and ``layer_payload``'s withholding are joined to each other in the right
    order. ``owned_or_granted_where`` is NOT stubbed — the real predicate runs and the fake media
    table answers the composed ``where``, so a future change that dropped the AND-composition and
    handed back every id would fail here.

    The shape is the one that leaks: two recordings in one workshop, one uploaded by this caller and
    one not, and a cleaned rung standing on the one they may not read — because the derived row is
    where a chain walk that stopped at the first hop would quietly let the text through.
    """
    import asyncio

    from app.api.routes import design_workshops as routes

    mine = _row(id="lyr_1", sourceMediaId="med_mine", text="My recording, transcribed.")
    theirs = _row(id="lyr_2", sourceMediaId="med_theirs", text="Their recording, transcribed.")
    derived = _row(
        id="lyr_3",
        kind="CLEANED_TRANSCRIPT",
        sourceMediaId=None,
        sourceLayerId="lyr_2",
        text="Their recording, tidied up.",
    )

    async def _workshop(workshop_id, user, **kwargs):
        return SimpleNamespace(id=workshop_id)

    async def _layers(workshop_id, *, include_deleted=False):
        assert include_deleted is True, "the chain walk needs the deleted rungs too"
        return [mine, theirs, derived]

    class _MediaTable:
        async def find_many(self, where):
            # The caller's entitlement arrives AND-composed under the id list, exactly as
            # `load_transcript_items` composes it. Honouring only the ids inside that composition is
            # what makes this fake able to fail: a route that stopped composing would ask for a bare
            # id list and get everything.
            clause = where["AND"][0] if "AND" in where else where
            return [
                SimpleNamespace(id=i) for i in clause["id"]["in"] if i == "med_mine"
            ]

    monkeypatch.setattr(routes, "load_workshop_or_404", _workshop)
    monkeypatch.setattr(routes.ai_layers, "workshop_layers", _layers)
    monkeypatch.setattr(routes, "db", SimpleNamespace(mediafile=_MediaTable()))

    payload = asyncio.run(
        routes.list_ai_layers(
            "wsp_1",
            kind=None,
            includeText=True,
            includeDeleted=False,
            current_user=_person("DESIGNER"),
        )
    )
    items = {item["id"]: item for item in payload["items"]}
    assert items["lyr_1"]["textWithheld"] is False
    assert items["lyr_1"]["text"] == "My recording, transcribed."

    for hidden in ("lyr_2", "lyr_3"):
        assert items[hidden]["textWithheld"] is True, hidden
        assert "text" not in items[hidden]
        assert items[hidden]["preview"] is None
        assert items[hidden]["textChars"] is None
        # Listed, not omitted: the provenance is not the recording's content, and it is what a
        # reviewer opens this screen for.
        assert items[hidden]["tier"] == "TIER_3"
        assert items[hidden]["modelId"] == "nova-3"


def test_the_kind_narrowing_used_by_the_list_never_raises_on_a_bad_row():
    """The list narrows in Python because the chain walk needs every rung loaded, so this comparison
    meets whatever is stored. A row whose kind is outside the vocabulary must drop out of the filter,
    not empty the screen with a lookup error."""
    assert is_kind(_row(), LayerKind.RAW_TRANSCRIPT) is True
    assert is_kind(_row(), LayerKind.SUMMARY) is False
    assert is_kind(_row(kind="NOT_A_KIND"), LayerKind.RAW_TRANSCRIPT) is False


def test_a_decision_is_serialised_for_the_client_too():
    row = SimpleNamespace(
        id="dec_1", layerId="lyr_1", decision="ACCEPTED", note=None,
        actorId="usr_2", createdAt=AT,
    )
    assert decision_payload(row) == {
        "id": "dec_1",
        "layerId": "lyr_1",
        "decision": "ACCEPTED",
        "note": None,
        "actorId": "usr_2",
        "createdAt": AT.isoformat(),
    }


# --------------------------------------------------------------------------------------
# Rule 5: deleting a derived layer never touches its source
# --------------------------------------------------------------------------------------


def test_deleting_a_layer_touches_one_row_and_two_columns():
    """The proof of rule 5 is the SHAPE of the write, not a sentence promising it.

    One plan, so nothing else can be written in the same breath. A ``where`` naming the layer's own
    id, so no other row is reachable. Two columns, so the source pointers are not merely left alone
    by today's code but are outside what this write can express. A later change that "also tidies up
    the recording" fails here rather than deleting the evidence a transcript was made from.
    """
    row = _row()
    plan = deletion_plan(row, actor_id="usr_2", at=AT)

    assert plan.table == "DwAiLayer"
    assert plan.operation is Operation.UPDATE
    assert plan.where == {"id": "lyr_1"}
    assert plan.data == {"deletedAt": AT, "deletedById": "usr_2"}

    # Neither source column is named anywhere in the write, in either half of it.
    named = set(plan.data) | set(plan.where)
    assert "sourceMediaId" not in named
    assert "sourceLayerId" not in named
    # And the row handed in is untouched: the plan is a description, not a mutation.
    assert row.sourceMediaId == "med_1"
    assert row.deletedAt is None


def test_deletion_is_soft_so_a_declined_suggestion_stays_on_record():
    """The row is the only record that a model proposed something and a person said no."""
    plan = deletion_plan(_row(), actor_id="usr_2", at=AT)
    assert plan.data["deletedAt"] == AT
    assert plan.operation is Operation.UPDATE  # never a DELETE


def test_deleting_a_layer_that_others_were_derived_from_is_refused_with_the_count():
    """Deleting a raw transcript out from under a cleaned one would leave the cleaned layer
    describing something no screen will show — the opposite of the traceability this exists for."""
    derived = [
        _row(id="lyr_2", kind="CLEANED_TRANSCRIPT", sourceMediaId=None, sourceLayerId="lyr_1")
    ]
    with pytest.raises(LayerRuleViolation) as refused:
        deletion_plan(_row(), actor_id="usr_2", at=AT, derived=derived)
    # The count AND what they are: the person reading this is looking at a list of headings, not at
    # a table of cuids, and "1 layer(s)" alone does not tell them which line to deal with first.
    assert "1 layer" in str(refused.value)
    assert "CLEANED_TRANSCRIPT" in str(refused.value)


def test_the_refusal_survives_a_derived_row_whose_kind_is_not_in_the_vocabulary():
    """Naming the kinds must not become a new way to 500 in the middle of a refusal.

    The database's enum column should make this impossible; the point is that the refusal is still a
    sentence if it ever is not, rather than an unhandled lookup inside the error path itself.
    """
    broken = [_row(id="lyr_2", kind="NOT_A_KIND", sourceMediaId=None, sourceLayerId="lyr_1")]
    with pytest.raises(LayerRuleViolation) as refused:
        deletion_plan(_row(), actor_id="usr_2", at=AT, derived=broken)
    assert "1 layer" in str(refused.value)


def test_a_layer_whose_derived_rows_are_all_deleted_may_go():
    gone = [_row(id="lyr_2", sourceMediaId=None, sourceLayerId="lyr_1", deletedAt=AT)]
    assert deletion_plan(_row(), actor_id="usr_2", at=AT, derived=gone).where == {"id": "lyr_1"}


def test_deleting_a_deleted_layer_is_refused_rather_than_stamped_twice():
    """A second deletion stamp would overwrite when the layer was actually declined."""
    with pytest.raises(LayerRuleViolation):
        deletion_plan(_row(deletedAt=AT), actor_id="usr_2", at=AT)


# --------------------------------------------------------------------------------------
# Duplicates: what is one, and what is emphatically not
# --------------------------------------------------------------------------------------


def test_the_same_registration_twice_is_a_duplicate():
    existing = [_row()]
    found = duplicate_of(
        existing,
        source=LayerSource.media("med_1"),
        kind=LayerKind.RAW_TRANSCRIPT,
        tier=AiTier.TIER_3,
        provider="deepgram",
        model_id="nova-3",
    )
    assert found is existing[0]


@pytest.mark.parametrize(
    ("field", "value"),
    [("tier", "TIER_1"), ("modelId", "indic-conformer"), ("provider", "sherpa-onnx")],
)
def test_a_different_machine_or_model_is_not_a_duplicate(field, value):
    """THE CASE THE WHOLE PLAN TURNS ON. Tier 1 on a phone and Tier 3 in the cloud produce different
    text from the same audio, legitimately and for ever, and BOTH must exist so a reviewer can see
    which one a report printed. A unique index on (source, kind) would have forbidden exactly that,
    which is why there is none and why this is decided in code where the reason can be written down.
    """
    assert duplicate_of(
        [_row(**{field: value})],
        source=LayerSource.media("med_1"),
        kind=LayerKind.RAW_TRANSCRIPT,
        tier=AiTier.TIER_3,
        provider="deepgram",
        model_id="nova-3",
    ) is None


def test_a_declined_layer_does_not_block_registering_it_again():
    """Re-registering something that was declined is a deliberate act, and the old row stays as the
    record that it was declined."""
    assert duplicate_of(
        [_row(deletedAt=AT)],
        source=LayerSource.media("med_1"),
        kind=LayerKind.RAW_TRANSCRIPT,
        tier=AiTier.TIER_3,
        provider="deepgram",
        model_id="nova-3",
    ) is None


@pytest.mark.parametrize(
    "broken",
    [
        {"sourceMediaId": None, "sourceLayerId": None},  # no usable source
        {"kind": "NOT_A_KIND"},                          # outside the vocabulary
        {"tier": "TIER_9"},                              # outside the vocabulary
    ],
)
def test_a_malformed_stored_row_does_not_block_the_designer_in_front_of_us(broken):
    """It cannot be compared, so it is skipped and logged rather than refusing the whole create.

    The last two shapes should be impossible — both columns are Postgres enums — and are covered
    because the alternative when they do occur is a bare ValueError out of an enum lookup, which
    reaches the designer as "something went wrong on the server" while they are trying to register a
    perfectly good transcript.
    """
    assert duplicate_of(
        [_row(**broken)],
        source=LayerSource.media("med_1"),
        kind=LayerKind.RAW_TRANSCRIPT,
        tier=AiTier.TIER_3,
        provider="deepgram",
        model_id="nova-3",
    ) is None


# --------------------------------------------------------------------------------------
# The gates: the existing ones, on the right routes
#
# Borrowed wholesale from tests/test_permission_matrix.py, including its reasoning: the real router
# is mounted and driven over HTTP with ``db`` replaced by a tripwire that raises the moment anything
# reads a delegate off it. That makes the two outcomes unambiguous with no schema and no database —
# a refusal is a 403 with the tripwire never touched (so the gate fired before any work), and an
# authorisation is the tripwire raising (so every guard passed and the handler body began).
# "Not a 403" would also pass for a route that 404s for an unrelated reason; these two cannot.
# --------------------------------------------------------------------------------------


class _DatabaseTouched(Exception):
    """The route's guards all passed and its body started working."""


class _Tripwire:
    def __getattr__(self, name: str):
        raise _DatabaseTouched(name)


_CALLER: dict[str, object] = {"user": None}


def _person(role: str):
    return SimpleNamespace(id="usr_1", email="x@example.test", name="Test", role=role)


@pytest.fixture
def api(monkeypatch):
    """The design-workshop router, mounted with every module's ``db`` rebound to the tripwire.

    The modules do ``from app.core.db import db``, so each holds its OWN reference and patching the
    source alone would miss all of them — including ``app.services.ai_layers``. Rebinding by
    identity finds every one already imported.
    """
    import sys

    import httpx
    from fastapi import FastAPI

    import app.core.db as core_db
    from app.api.routes import design_workshops as routes
    from app.core import deps

    tripwire = _Tripwire()
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", tripwire)
    for module in list(sys.modules.values()):
        if getattr(module, "__name__", "").startswith("app.") and getattr(module, "db", None) is real_db:
            monkeypatch.setattr(module, "db", tripwire)

    app = FastAPI()
    app.include_router(routes.router, prefix="/api")
    app.dependency_overrides[deps.get_current_user] = lambda: _CALLER["user"]

    def call(role: str, method: str, path: str, body=None):
        import asyncio

        _CALLER["user"] = _person(role)

        async def run():
            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(transport=transport, base_url="http://layers.test") as c:
                response = await c.request(method, f"/api{path}", json=body)
            payload = response.json() if response.content else {}
            detail = payload.get("detail", payload) if isinstance(payload, dict) else payload
            return SimpleNamespace(reached=False, status_code=response.status_code, detail=str(detail))

        try:
            return asyncio.run(run())
        except _DatabaseTouched:
            return SimpleNamespace(reached=True, status_code=None, detail="")

    yield call
    _CALLER["user"] = None


LAYER_WRITES = [
    ("POST", "/design-workshops/wsp_1/ai-layers", {"sourceMediaId": "med_1"}),
    ("POST", "/design-workshops/wsp_1/ai-layers/lyr_1/accept", None),
    ("POST", "/design-workshops/wsp_1/ai-layers/lyr_1/unaccept", None),
    ("DELETE", "/design-workshops/wsp_1/ai-layers/lyr_1", None),
]


@pytest.mark.parametrize(("method", "path", "body"), LAYER_WRITES)
@pytest.mark.parametrize("role", ["RESEARCHER", "PROFESSOR"])
def test_only_the_designer_set_may_write_a_layer(api, role, method, path, body):
    """``_require_designer``, on every write, and PROFESSOR is the account that proves it is a SET.

    ``DESIGN_WORKSHOP_ROLES`` is {DESIGNER, ADMIN, MASTER_ADMIN}. A PROFESSOR sits at rank 40, ABOVE
    DESIGNER's 35, so every "this tier and above" spelling of the rule lets them in and the set does
    not — a researcher alone cannot prove the distinction, because the ladder refuses them too.

    The tripwire proves the refusal came from the gate rather than from a stray 403 later on: the
    handler never read a delegate off the database.
    """
    outcome = api(role, method, path, body)
    assert outcome.reached is False
    assert outcome.status_code == 403


@pytest.mark.parametrize(("method", "path", "body"), LAYER_WRITES)
def test_a_designer_reaches_the_handler_and_then_the_workshop_check(api, method, path, body):
    """Past ``_require_designer``, every write meets ``load_workshop_or_404`` — the workshop's own
    access check, which is where being the creator, an admin or a granted viewer is decided. No new
    gate is invented for layers; the first thing the handler does is read the workshop."""
    assert api("DESIGNER", method, path, body).reached is True


@pytest.mark.parametrize("role", ["RESEARCHER", "DESIGNER"])
def test_the_layer_list_is_gated_by_the_workshop_and_not_by_the_role(api, role):
    """No designer gate on the LIST, deliberately, and consistently with the transcripts endpoint
    beside it: a layer is derived from a recording the caller can already reach, and the provenance —
    which tier, which model, accepted by whom — is what a reviewing officer opens the screen for.
    WHO may read stays ``load_workshop_or_404``'s decision, which is why both roles reach the handler
    here rather than being refused: the refusal, when it comes, is about the workshop and not about
    the rank."""
    assert api(role, "GET", "/design-workshops/wsp_1/ai-layers", None).reached is True


# --------------------------------------------------------------------------------------
# The vocabulary itself
# --------------------------------------------------------------------------------------


def test_the_kind_vocabulary_is_the_plan_s_chains_plus_the_verbs_and_nothing_else():
    """Closed on purpose. A kind is what the annexure prints as a layer's NAME, so a value added
    without a heading to go with it is an unknown heading in a submitted document.

    The first seven are the plan's two chains and its two extractive verbs. The second five are the
    verbs the user asked for, added by migration 20260812150000 — and the test below this one is
    what makes "without a heading" impossible rather than merely discouraged.
    """
    assert {k.value for k in LayerKind} == {
        "RAW_TRANSCRIPT",
        "CLEANED_TRANSCRIPT",
        "SUMMARY",
        "OCR_TEXT",
        "STRUCTURED_TEXT",
        "TAGS",
        "METADATA",
        "PROOFREAD",
        "EXPANDED",
        "TRANSLATION",
        "CAPTION",
        "SUBTITLES",
    }


def test_every_kind_has_a_heading_that_says_a_machine_made_it():
    """Rule 4, enforced across the two modules rather than inside one of them.

    ``report_ai_layers`` falls back to "Machine-generated text" for a kind it has never heard of,
    which is the right answer for a NEWER SERVER's value reaching an older build — and it would
    silently absorb a kind added in this same commit with no heading written for it, so every layer
    of that kind would print under a generic title nobody chose. This is the pairing that stops that:
    the vocabulary and the headings move together or the suite fails.
    """
    from app.services.report_ai_layers import _KIND_TITLES, kind_title

    for kind in LayerKind:
        assert kind.value in _KIND_TITLES, f"{kind.value} has no annexure heading"
        assert any(
            word in kind_title(kind.value).lower() for word in ("ai", "automatic", "machine")
        ), kind


def test_the_python_vocabulary_and_the_database_enums_agree():
    """Three enums declared twice — once in Python, once in Postgres — and they must not drift.

    A value Python can produce and Postgres refuses is a 500 on the write path; a value Postgres
    holds and Python cannot name is a crash on the read path, in the report builder, mid-render.
    """
    schema = SCHEMA_PRISMA.read_text(encoding="utf-8")
    for enum_name, members in (
        ("DwAiLayerKind", {k.value for k in LayerKind}),
        ("DwAiTier", {t.value for t in AiTier}),
        ("DwAiDecision", {d.value for d in Decision}),
    ):
        match = re.search(rf"^enum {enum_name} \{{(.*?)^\}}", schema, re.DOTALL | re.MULTILINE)
        assert match, f"enum {enum_name} is not in schema.prisma"
        declared = {line.strip() for line in match.group(1).splitlines() if line.strip()
                    and not line.strip().startswith("//")}
        assert declared == members, f"{enum_name} has drifted from its Python mirror"
