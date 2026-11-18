from __future__ import annotations

from typing import Any

import pytest

from app.services import field_voices_portal as adapter

CONTRACT_TYPED_TRANSCRIPTS = {
    ("TRADITIONAL_PROCESS_BASELINE", "traditionalProcess", "artisanAudio"): "artisanAudioTranscript",
    ("TRADITIONAL_PROCESS_BASELINE", "processStep", "stepAudio"): "stepAudioTranscript",
    ("MARKET_SURVEY_CAPTURE", "surveyResponse", "voiceNote"): "voiceTranscript",
    ("PROTOTYPE_DEVELOPMENT", "prototype", "audioNarration"): "audioTranscript",
    ("SKETCH_REVIEW", "sketchReview", "voiceFeedback"): "voiceFeedbackTranscript",
    ("WORKSHOP_OUTCOMES", "outcomes", "feedbackAudio"): "feedbackTranscript",
}
CONTRACT_NEVER_FIELDS = {
    "workshopPlan.designerSignature", "workshopPlan.designerCv", "certificate.signatureImage",
    "certificate.certificateFile",
}
ONCE_WITHHELD_NOW_SENT = {
    "workshopPlan.designerPhoto", "workshopPlan.openingPhotos", "workshopPlan.eventPhotos", "participant.photo",
    "workshopSetup.coverPhoto", "surveySummary.marketPhotos", "surveyResponse.responsePhotos", "designBrief.moodBoard",
    "closing.closingPhotos", "reportSettings.logo", "followUp.evidencePhotos",
}


def _stage_of(entity: str) -> str:
    return next(s.key for s in adapter.stages() for e in s.entities if e.key == entity)


def test_the_typed_transcripts_and_never_fields_are_the_contracts_and_name_real_fields() -> None:
    assert adapter.TYPED_TRANSCRIPTS == CONTRACT_TYPED_TRANSCRIPTS
    assert {f"{entity}.{field}" for _stage, entity, field in adapter.NEVER_FIELDS} == CONTRACT_NEVER_FIELDS
    specs = adapter._specs()
    for (stage, entity, field), typed in adapter.TYPED_TRANSCRIPTS.items():
        known = {f.key for f in specs[(stage, entity)][1].fields}
        assert field in known and typed in known
    for stage, entity, field in adapter.NEVER_FIELDS | adapter.DESIGNER_PORTRAITS:
        assert field in {f.key for f in specs[(stage, entity)][1].fields}, (stage, entity, field)


def _placements(stage: str, entity: str, data: dict[str, Any], stream: str, participants: dict[str, list[str]] | None = None) -> list[Any]:
    found = adapter._specs()[(stage, entity)]
    row = {"id": "row1", "designWorkshopId": "dw1", "stageKey": stage, "entityKey": entity, "data": data}
    return adapter._row_placements(stream, row, found[0], found[1], participants or {})


@pytest.mark.parametrize("never", sorted(CONTRACT_NEVER_FIELDS))
def test_every_legal_floor_field_is_an_unlisted_placement(never: str) -> None:
    entity, field = never.split(".")
    found = _placements(_stage_of(entity), entity, {field: ["m1"]}, "images")
    assert [(p.media_id, p.field_key, p.listed) for p in found] == [("m1", field, False)]


@pytest.mark.parametrize("sent", sorted(ONCE_WITHHELD_NOW_SENT))
def test_portraits_events_covers_mood_boards_and_logos_are_listed(sent: str) -> None:
    entity, field = sent.split(".")
    found = _placements(_stage_of(entity), entity, {field: ["m1"]}, "images")
    assert [(p.media_id, p.field_key, p.listed) for p in found] == [("m1", field, True)]


def test_the_designers_photograph_credits_the_workshops_designers() -> None:
    found = _placements("WORKSHOP_PLAN_PARTICIPANTS_OPENING", "workshopPlan", {"designerPhoto": "m1", "eventPhotos": ["m2"]}, "images")
    assert [(p.media_id, p.credits_designers) for p in found] == [("m1", True), ("m2", False)]


def test_a_participant_photo_is_credited_to_its_artisan() -> None:
    found = _placements("WORKSHOP_PLAN_PARTICIPANTS_OPENING", "participant", {"photo": "m1", "artisanRef": "a1"}, "images")
    assert [(p.media_id, p.listed, p.artisan_ids) for p in found] == [("m1", True, ("a1",))]


def test_a_prototype_photo_carries_its_row_its_prototype_and_its_artisan_through_the_participant() -> None:
    found = _placements(
        "PROTOTYPE_DEVELOPMENT", "prototype",
        {"name": "Indigo dabu runner", "prototypePhotos": ["m1", "m2"], "prototypePhotosCaption": "Second trial",
         "artisanRef": "participant1", "productRef": "p1"},
        "images", {"participant1": ["a1"]},
    )
    assert [p.media_id for p in found] == ["m1", "m2"]
    first = found[0]
    assert (first.listed, first.stage_number, first.row_label, first.caption, first.prototype_id) == (
        True, 13, "Indigo dabu runner", "Second trial", "row1")
    assert (first.artisan_ids, first.product_ids, first.field_label) == (("a1",), ("p1",), "Prototype photographs")
    assert first.credits_designers is True


def test_an_iteration_points_at_its_prototype() -> None:
    found = _placements("PROTOTYPE_ITERATION", "prototypeIteration", {"beforePhoto": "m1", "prototypeRef": "row9"}, "images")
    assert (found[0].listed, found[0].prototype_id) == (True, "row9")


def test_a_typed_transcript_rides_with_its_recording_only_in_the_transcript_stream() -> None:
    data = {"voiceFeedback": "m1", "voiceFeedbackTranscript": "Keep the border thin.", "sketchRef": "s1"}
    voices = _placements("SKETCH_REVIEW", "sketchReview", data, "transcripts")
    assert [(p.media_id, p.listed, p.typed_transcript, p.row_label) for p in voices] == [
        ("m1", True, "Keep the border thin.", None)
    ]
    assert [(p.listed, p.typed_transcript) for p in _placements("SKETCH_REVIEW", "sketchReview", data, "images")] == [
        (True, None)
    ]


def test_a_survey_voice_note_and_the_closing_video_are_listed() -> None:
    found = _placements("MARKET_SURVEY_CAPTURE", "surveyResponse", {"voiceNote": "m1", "voiceTranscript": "I would pay more"}, "transcripts")
    assert [(p.media_id, p.listed, p.typed_transcript) for p in found] == [("m1", True, "I would pay more")]
    closing = _placements("INSPECTION_CLOSING", "closing", {"summaryVideo": "m2"}, "transcripts")
    assert [(p.media_id, p.listed) for p in closing] == [("m2", True)]


def test_media_inside_rich_text_is_listed() -> None:
    data = {"history": {"blocks": [{"kind": "image", "media": "m1"}]}}
    found = _placements("CLUSTER_CRAFT_BACKGROUND", "clusterBackground", data, "images")
    assert [(p.media_id, p.field_key, p.listed) for p in found] == [("m1", "history", True)]


def test_designer_defined_values_are_listed_placements_named_by_their_stage() -> None:
    row = {"id": "r1", "designWorkshopId": "dw1", "stageKey": "WORKSHOP_OUTCOMES", "data": {"a": "m1", "b": ["m2"]}}
    stage = next(s for s in adapter.stages() if s.key == "WORKSHOP_OUTCOMES")
    found = adapter._custom_placements(row, stage)
    assert [(p.media_id, p.listed) for p in found] == [("m1", True), ("m2", True)]
    first = found[0]
    assert (first.stage_title, first.stage_number) == (stage.title, stage.number)
    assert (first.entity_title, first.field_label) == (adapter.CUSTOM_ENTITY_TITLE, adapter.CUSTOM_FIELD_LABEL)
    assert adapter._custom_placements(row, None)[0].stage_title == "WORKSHOP_OUTCOMES"
