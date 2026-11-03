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



def test_the_stage_table_is_not_writable():
    with pytest.raises(LayerRuleViolation) as refused:
        LayerWritePlan(table=STAGE_TABLE, operation=Operation.CREATE, data={"data": {}})
    message = str(refused.value)
    assert STAGE_TABLE in message
    assert "compared" in message and "derived" in message


def test_the_executor_has_no_entry_for_the_stage_table():
    with pytest.raises(LayerRuleViolation):
        _writable_model(STAGE_TABLE)
    assert STAGE_TABLE not in WRITABLE_TABLES
    assert set(WRITABLE_TABLES) == {"DwAiLayer", "DwAiLayerDecision"}


def test_this_module_never_writes_a_stage_entry():
    source = (BACKEND / "app" / "services" / "ai_layers.py").read_text(encoding="utf-8")
    assert "db.dwstageentry" not in source.lower()


def _referenced_names(fn) -> set[str]:
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

    assert "dwstageentry" in _referenced_names(routes.report_history)


def test_the_layer_table_cannot_name_a_place_in_stage_data():
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
        statement = next(
            (s for s in sql.splitlines() if f'CONSTRAINT "{constraint}"' in s and "ADD" in s), ""
        )
        assert statement, f"{constraint} is not added by the migration at all"
        assert "ON DELETE CASCADE" in statement, f"{constraint} disagrees with schema.prisma"


def test_the_migration_enforces_the_source_pair_in_the_database():
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
    names = set()
    for line in block.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(("//", "///", "@@")):
            continue
        names.add(stripped.split()[0])
    return names



def test_creating_a_layer_is_always_an_insert():
    plan = _create()
    assert plan.table == "DwAiLayer"
    assert plan.operation is Operation.CREATE
    assert plan.where is None


def test_no_update_this_module_can_produce_touches_content_or_provenance():
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
    for broken in (
        _row(sourceMediaId=None, sourceLayerId=None),
        _row(sourceMediaId="med_1", sourceLayerId="lyr_0"),
    ):
        with pytest.raises(LayerRuleViolation):
            source_of(broken)



@pytest.mark.parametrize("blank", ["", "   ", None])
def test_a_layer_with_no_named_model_is_refused(blank):
    with pytest.raises(LayerRuleViolation) as refused:
        _create(model_id=blank)
    assert UNRECORDED in str(refused.value)

    with pytest.raises(LayerRuleViolation):
        _create(provider=blank)


def test_unrecorded_is_a_value_and_not_a_null():
    data = _create(provider=UNRECORDED, model_id=UNRECORDED).data
    assert data["provider"] == UNRECORDED
    assert data["modelId"] == UNRECORDED
    assert data["provider"] is not None


def test_the_time_the_model_ran_is_never_invented():
    assert _create().data["producedAt"] is None
    ran_at = datetime(2026, 3, 4, 11, 20, tzinfo=UTC)
    assert _create(produced_at=ran_at).data["producedAt"] == ran_at


def test_language_multi_survives_verbatim():
    assert _create(language="multi").data["language"] == "multi"
    assert _create(language="  ").data["language"] is None


def test_the_tier_is_recorded_and_is_not_a_ranking():
    assert _create(tier=AiTier.TIER_1).data["tier"] == "TIER_1"
    assert AiTier.TIER_2.number == 2
    assert {t.value for t in AiTier} == {"TIER_1", "TIER_2", "TIER_3"}



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
    with pytest.raises(LayerRuleViolation):
        _create(source=LayerSource.layer("lyr_1", LayerKind.RAW_TRANSCRIPT))
    with pytest.raises(LayerRuleViolation):
        _create(kind=LayerKind.SUMMARY, source=LayerSource.media("med_1"), text="A summary.")
    with pytest.raises(LayerRuleViolation):
        _create(kind=LayerKind.TAGS, source=LayerSource.media("med_1"), payload=["dabu"])


def test_a_derived_layer_must_name_its_parent_s_kind():
    with pytest.raises(LayerRuleViolation) as refused:
        _create(kind=LayerKind.SUMMARY, source=LayerSource.layer("lyr_1"), text="A summary.")
    assert "kind" in str(refused.value)


def test_every_kind_can_be_created_somehow():
    for kind in LayerKind:
        assert (
            kind in MEDIA_ROOTED_KINDS
            or kind in TEXT_ROOTED_KINDS
            or ALLOWED_PARENTS.get(kind)
        ), kind
        assert kind in TEXT_KINDS or kind in STRUCTURED_KINDS, kind


def test_the_verbs_sit_where_the_law_says_and_nowhere_else():
    note = LayerSource.supplied_text("dabu paste — gum, clay, 3 days")

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

    with pytest.raises(LayerRuleViolation) as no_expanding_an_artisan:
        _create(
            kind=LayerKind.EXPANDED,
            source=LayerSource.layer("lyr_1", LayerKind.RAW_TRANSCRIPT),
            text="The artisan explained at length…",
        )
    assert "EXPANDED" in str(no_expanding_an_artisan.value)

    with pytest.raises(LayerRuleViolation):
        _create(
            kind=LayerKind.SUBTITLES,
            source=LayerSource.layer("lyr_1", LayerKind.RAW_TRANSCRIPT),
            text="x",
            payload={"cues": []},
        )
    with pytest.raises(LayerRuleViolation):
        _create(
            kind=LayerKind.TRANSLATION,
            source=LayerSource.layer("lyr_1", LayerKind.TRANSLATION),
            text="x",
            source_language="en",
            target_language="hi",
        )
    with pytest.raises(LayerRuleViolation):
        _create(
            kind=LayerKind.SUMMARY,
            source=LayerSource.layer("lyr_1", LayerKind.EXPANDED),
            text="x",
        )
    with pytest.raises(LayerRuleViolation):
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
    with pytest.raises(LayerRuleViolation):
        LayerSource(kind=SourceKind.SUPPLIED_TEXT, id="lyr_1", text="a note")
    with pytest.raises(LayerRuleViolation):
        LayerSource(kind=SourceKind.LAYER, id="lyr_1", text="a note")


def test_a_note_longer_than_the_bound_is_refused_and_never_clipped():
    with pytest.raises(LayerRuleViolation) as refused:
        _create(
            kind=LayerKind.PROOFREAD,
            source=LayerSource.supplied_text("x" * (MAX_SOURCE_TEXT_CHARS + 1)),
            text="X.",
        )
    assert str(MAX_SOURCE_TEXT_CHARS) in str(refused.value).replace(",", "")


def test_an_over_long_note_already_stored_is_still_readable():
    row = _row(
        kind="PROOFREAD",
        sourceMediaId=None,
        sourceText="x" * (MAX_SOURCE_TEXT_CHARS + 500),
        text="X.",
    )
    assert layer_payload(row)["source"]["kind"] == "SUPPLIED_TEXT"


def test_a_translation_must_name_both_ends():
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
    with pytest.raises(LayerRuleViolation):
        _create(
            kind=LayerKind.PROOFREAD,
            source=LayerSource.supplied_text("a note"),
            text="A note.",
            target_language="en",
        )



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
    from prisma import Json

    wrapped = _json_ready("DwAiLayer", {"text": "x", "payload": ["dabu", "indigo"]})
    assert isinstance(wrapped["payload"], Json)
    assert wrapped["text"] == "x"
    absent = _json_ready("DwAiLayer", {"payload": None})
    assert isinstance(absent["payload"], Json)
    assert "payload" not in _json_ready("DwAiLayer", {"text": "x"})
    assert _json_ready("DwAiLayerDecision", {"note": "x"}) == {"note": "x"}


def test_prose_may_carry_structure_beside_it():
    turns = [{"speaker": 1, "text": "The dabu paste…"}]
    assert _create(payload=turns).data["payload"] == turns



def test_a_refined_transcript_is_not_registered_as_a_raw_one():
    raw, cleaned = transcript_rungs(
        transcript_text="**Interviewer:** How is the paste mixed?\n**Artisan:** With gum and clay.",
        transcript_summary="dabu paste gum clay mixing haan ji",
    )
    assert raw == "dabu paste gum clay mixing haan ji"
    assert cleaned == (
        "**Interviewer:** How is the paste mixed?\n**Artisan:** With gum and clay."
    )


def test_an_unrefined_transcript_is_one_rung_despite_the_formatting_header():
    raw, cleaned = transcript_rungs(
        transcript_text="Transcript\n\ndabu paste gum clay",
        transcript_summary="dabu paste gum clay",
    )
    assert raw == "dabu paste gum clay"
    assert cleaned is None


def test_the_decision_never_consults_todays_transcription_mode():
    import inspect

    assert set(inspect.signature(transcript_rungs).parameters) == {
        "transcript_text",
        "transcript_summary",
    }
    reached = _referenced_names(transcript_rungs)
    for settings_name in ("load_app_settings", "transcription_mode", "get_settings", "db"):
        assert settings_name not in reached


def test_a_row_with_no_provider_text_is_the_weakest_claim_and_not_a_confident_one():
    assert transcript_rungs(transcript_text="Some text", transcript_summary=None) == (
        "Some text",
        None,
    )
    assert transcript_rungs(transcript_text="Some text", transcript_summary="   ") == (
        "Some text",
        None,
    )


def test_a_person_s_correction_is_indistinguishable_from_a_model_s_rewrite():
    raw, cleaned = transcript_rungs(
        transcript_text="**Interviewer:** How is the paste mixed?\n**Artisan:** With gum and clay.",
        transcript_summary="dabu paste gum clay mixing haan ji",
    )
    assert raw == "dabu paste gum clay mixing haan ji"
    assert cleaned == (
        "**Interviewer:** How is the paste mixed?\n**Artisan:** With gum and clay."
    )
    assert len(transcript_rungs(transcript_text="a", transcript_summary="b")) == 2


def test_a_recording_with_nothing_transcribed_is_refused_with_the_next_move():
    with pytest.raises(LayerRuleViolation) as refused:
        transcript_rungs(transcript_text=None, transcript_summary=None)
    assert "transcripts screen" in str(refused.value)



def test_a_new_layer_is_inert():
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
    assert list(plans) == [plans.layer, plans.decision]


def test_an_unsigned_acceptance_is_not_an_acceptance():
    with pytest.raises(LayerRuleViolation):
        acceptance_plan(_row(), actor_id="", at=AT)


def test_a_second_acceptance_cannot_overwrite_the_first_person_s_name():
    with pytest.raises(LayerRuleViolation) as refused:
        acceptance_plan(_row(acceptedAt=AT, acceptedById="usr_2"), actor_id="usr_3", at=AT)
    assert "withdraw" in str(refused.value).lower()


def test_withdrawing_clears_the_state_and_keeps_the_history():
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



def test_acceptance_is_explicit_on_the_wire():
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
    assert payload["sourceLanguage"] is None
    assert payload["targetLanguage"] is None
    assert not [key for key in payload if "_" in key]


def test_a_list_carries_a_preview_and_not_the_transcript():
    long_text = "word " * 400
    payload = layer_payload(_row(text=long_text))
    assert "text" not in payload
    assert payload["textChars"] == len(long_text)
    assert len(payload["preview"]) <= PREVIEW_CHARS + 1
    assert payload["preview"].endswith("…")

    full = layer_payload(_row(text=long_text), include_text=True)
    assert full["text"] == long_text


def test_the_preview_is_one_line_because_a_transcript_starts_with_a_speaker_label():
    payload = layer_payload(_row(text="**Interviewer:**\n\nHow is the dabu paste mixed?"))
    assert payload["preview"] == "**Interviewer:** How is the dabu paste mixed?"


def test_a_malformed_row_renders_with_no_source_rather_than_taking_out_the_list():
    payload = layer_payload(_row(sourceMediaId=None, sourceLayerId=None))
    assert payload["source"] is None
    assert payload["id"] == "lyr_1"



def test_the_chain_walk_finds_the_recording_a_layer_stands_on():
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
    assert set(media_roots(rows).values()) == {None}


def test_a_withheld_layer_keeps_its_provenance_and_loses_every_form_of_its_content():
    withheld = layer_payload(
        _row(text="The dabu paste is mixed with gum and clay.", payload=[{"speaker": 1}]),
        include_text=True,
        text_withheld=True,
    )
    assert withheld["textWithheld"] is True
    assert "text" not in withheld
    assert withheld["preview"] is None
    assert withheld["payload"] is None
    assert withheld["textChars"] is None

    assert withheld["tier"] == "TIER_3"
    assert withheld["modelId"] == "nova-3"
    assert withheld["source"] == {"kind": SourceKind.MEDIA.value, "id": "med_1", "text": None}


def test_a_withheld_layer_loses_the_words_a_verb_was_given_as_well_as_the_ones_it_produced():
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
    assert layer_payload(_row())["textWithheld"] is False


def test_the_list_route_withholds_the_text_of_a_recording_this_caller_may_not_read(monkeypatch):
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
            clause = where["AND"][0] if "AND" in where else where
            return [
                SimpleNamespace(id=i) for i in clause["id"]["in"] if i == "med_mine"
            ]

    monkeypatch.setattr(routes, "load_workshop_or_404", _workshop)
    monkeypatch.setattr(routes.ai_layers, "workshop_layers", _layers)
    monkeypatch.setattr(routes, "db", SimpleNamespace(mediafile=_MediaTable()))

    from app.services import records as records_service

    async def _no_workshop_arm(_user_id: str) -> list:
        return []

    monkeypatch.setattr(records_service, "_design_workshop_media_branches", _no_workshop_arm)

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
        assert items[hidden]["tier"] == "TIER_3"
        assert items[hidden]["modelId"] == "nova-3"


def test_the_kind_narrowing_used_by_the_list_never_raises_on_a_bad_row():
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



def test_deleting_a_layer_touches_one_row_and_two_columns():
    row = _row()
    plan = deletion_plan(row, actor_id="usr_2", at=AT)

    assert plan.table == "DwAiLayer"
    assert plan.operation is Operation.UPDATE
    assert plan.where == {"id": "lyr_1"}
    assert plan.data == {"deletedAt": AT, "deletedById": "usr_2"}

    named = set(plan.data) | set(plan.where)
    assert "sourceMediaId" not in named
    assert "sourceLayerId" not in named
    assert row.sourceMediaId == "med_1"
    assert row.deletedAt is None


def test_deletion_is_soft_so_a_declined_suggestion_stays_on_record():
    plan = deletion_plan(_row(), actor_id="usr_2", at=AT)
    assert plan.data["deletedAt"] == AT
    assert plan.operation is Operation.UPDATE


def test_deleting_a_layer_that_others_were_derived_from_is_refused_with_the_count():
    derived = [
        _row(id="lyr_2", kind="CLEANED_TRANSCRIPT", sourceMediaId=None, sourceLayerId="lyr_1")
    ]
    with pytest.raises(LayerRuleViolation) as refused:
        deletion_plan(_row(), actor_id="usr_2", at=AT, derived=derived)
    assert "1 layer" in str(refused.value)
    assert "CLEANED_TRANSCRIPT" in str(refused.value)


def test_the_refusal_survives_a_derived_row_whose_kind_is_not_in_the_vocabulary():
    broken = [_row(id="lyr_2", kind="NOT_A_KIND", sourceMediaId=None, sourceLayerId="lyr_1")]
    with pytest.raises(LayerRuleViolation) as refused:
        deletion_plan(_row(), actor_id="usr_2", at=AT, derived=broken)
    assert "1 layer" in str(refused.value)


def test_a_layer_whose_derived_rows_are_all_deleted_may_go():
    gone = [_row(id="lyr_2", sourceMediaId=None, sourceLayerId="lyr_1", deletedAt=AT)]
    assert deletion_plan(_row(), actor_id="usr_2", at=AT, derived=gone).where == {"id": "lyr_1"}


def test_deleting_a_deleted_layer_is_refused_rather_than_stamped_twice():
    with pytest.raises(LayerRuleViolation):
        deletion_plan(_row(deletedAt=AT), actor_id="usr_2", at=AT)



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
    assert duplicate_of(
        [_row(**{field: value})],
        source=LayerSource.media("med_1"),
        kind=LayerKind.RAW_TRANSCRIPT,
        tier=AiTier.TIER_3,
        provider="deepgram",
        model_id="nova-3",
    ) is None


def test_a_declined_layer_does_not_block_registering_it_again():
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
        {"sourceMediaId": None, "sourceLayerId": None},
        {"kind": "NOT_A_KIND"},
        {"tier": "TIER_9"},
    ],
)
def test_a_malformed_stored_row_does_not_block_the_designer_in_front_of_us(broken):
    assert duplicate_of(
        [_row(**broken)],
        source=LayerSource.media("med_1"),
        kind=LayerKind.RAW_TRANSCRIPT,
        tier=AiTier.TIER_3,
        provider="deepgram",
        model_id="nova-3",
    ) is None



class _DatabaseTouched(Exception):
    pass


class _Tripwire:
    def __getattr__(self, name: str):
        raise _DatabaseTouched(name)


_CALLER: dict[str, object] = {"user": None}


def _person(role: str):
    return SimpleNamespace(id="usr_1", email="x@example.test", name="Test", role=role)


@pytest.fixture
def api(monkeypatch):
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
    outcome = api(role, method, path, body)
    assert outcome.reached is False
    assert outcome.status_code == 403


@pytest.mark.parametrize(("method", "path", "body"), LAYER_WRITES)
def test_a_designer_reaches_the_handler_and_then_the_workshop_check(api, method, path, body):
    assert api("DESIGNER", method, path, body).reached is True


@pytest.mark.parametrize("role", ["RESEARCHER", "DESIGNER"])
def test_the_layer_list_is_gated_by_the_workshop_and_not_by_the_role(api, role):
    assert api(role, "GET", "/design-workshops/wsp_1/ai-layers", None).reached is True



def test_the_kind_vocabulary_is_the_plan_s_chains_plus_the_verbs_and_nothing_else():
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
    from app.services.report_ai_layers import _KIND_TITLES, kind_title

    for kind in LayerKind:
        assert kind.value in _KIND_TITLES, f"{kind.value} has no annexure heading"
        assert any(
            word in kind_title(kind.value).lower() for word in ("ai", "automatic", "machine")
        ), kind


def test_the_python_vocabulary_and_the_database_enums_agree():
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
