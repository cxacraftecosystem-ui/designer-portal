from __future__ import annotations

import re

from app.services import stage_definitions  # noqa: F401  (installs the registry)
from app.services.stage_schema import all_entities, registry_to_dict

HELP_MAX = 120

_SENTENCE_BREAK = re.compile(r"[.!?…][\"”’)]?\s+\S")


def _helps() -> list[tuple[str, str]]:
    return [
        (f"{entity.key}.{field.key}", field.help)
        for _stage, entity in all_entities()
        for field in entity.fields
        if field.help
    ]


def test_every_help_line_is_one_short_sentence() -> None:
    helps = _helps()
    assert len(helps) > 100

    too_long = {path: len(text) for path, text in helps if len(text) > HELP_MAX}
    assert too_long == {}, f"help over {HELP_MAX} characters — move the rest to details: {too_long}"

    multi = [path for path, text in helps if _SENTENCE_BREAK.search(text.strip())]
    assert multi == [], f"help with more than one sentence — move the rest to details: {multi}"

    newlines = [path for path, text in helps if "\n" in text]
    assert newlines == []


def _details() -> list[tuple[str, str]]:
    return [
        (f"{entity.key}.{field.key}", field.details)
        for _stage, entity in all_entities()
        for field in entity.fields
        if field.details
    ]


def test_help_never_explains_the_software() -> None:
    banned = (
        "this device",
        "the server",
        "is stored on this entry",
        "previewable in the app",
        "previews in the app",
        "before it uploads",
        "before upload",
        "vision model",
        "older rows",
        "saved since",
        "when the stage is saved",
        "typed here earlier",
        "doesn’t change that record",
    )
    texts = _helps() + _details()
    assert len(_details()) > 20
    hits = [
        (path, phrase)
        for path, text in texts
        for phrase in banned
        if phrase in text.lower()
    ]
    assert hits == []


def test_the_reference_note_is_one_short_clause() -> None:
    from app.services.stage_definitions import FROM_REF

    assert len(FROM_REF) <= 80
    assert not _SENTENCE_BREAK.search(FROM_REF)


def test_details_reach_the_wire_only_when_declared() -> None:
    fields = [
        field
        for stage in registry_to_dict()["stages"]
        for entity in stage["entities"]
        for field in entity["fields"]
    ]
    with_details = [f for f in fields if "details" in f]
    assert with_details, "no field publishes details"
    assert all(isinstance(f["details"], str) and f["details"].strip() for f in with_details)
    assert not any(f.get("details") == "" for f in fields)


def test_the_aadhaar_box_keeps_its_privacy_meaning() -> None:
    spec = next(
        field
        for _stage, entity in all_entities()
        if entity.key == "participant"
        for field in entity.fields
        if field.key == "aadhaarNumber"
    )
    assert "last four digits" in spec.help
    assert "XXXX XXXX" in spec.details
    assert "Pehchan" in spec.details


def test_every_stage_opens_with_one_line() -> None:
    stages = registry_to_dict()["stages"]
    assert len(stages) > 20
    long = {s["key"]: len(s["purpose"]) for s in stages if len(s["purpose"]) > 100}
    assert long == {}
    multi = [s["key"] for s in stages if _SENTENCE_BREAK.search(s["purpose"].strip())]
    assert multi == []
