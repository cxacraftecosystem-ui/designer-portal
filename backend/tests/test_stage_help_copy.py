"""The stage form's per-field copy: one short line of help, and the rest behind the "i".

Owner ruling, 2026-10-17 (audit DW-14): a field's ``help`` is the single line under its box and
says only what the person needs to fill it. Anything else that is genuinely useful goes in
``details``, which both clients draw behind a collapsed info disclosure beside the label. Before
this, 143 of 232 help strings ran past 140 characters (the longest 568), and on a phone a stage
read as text with occasional boxes.

Pure: reads the registry and nothing else, so it needs no database.
"""

from __future__ import annotations

import re

from app.services import stage_definitions  # noqa: F401  (installs the registry)
from app.services.stage_schema import all_entities, registry_to_dict

#: The ceiling the test holds. The copy pass aimed at 90; 120 leaves room for one longer line
#: without letting a paragraph back in.
HELP_MAX = 120

#: A sentence ends at ". ", "? " or "! " (or the same mid-string before a capital). A help line may
#: use ";" or a dash to join two clauses, but never two sentences.
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
    # A floor, so a broken walk cannot pass by measuring nothing.
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
    """The phrases the old copy used to narrate the app itself, refused by name — on the line
    under the box AND behind the "i", which is still copy a person reads."""
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
    """The sentence every hydrated field carries behind its "i" says only what changes the
    reader's action: where the value came from, and that editing it here is local."""
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
    # Omitted, never emitted empty — the registry crosses the wire on every app start.
    assert not any(f.get("details") == "" for f in fields)


def test_the_aadhaar_box_keeps_its_privacy_meaning() -> None:
    """Shortened, never dropped: the box says only four digits are kept, and the "i" says the rest."""
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
    """A stage's ``purpose`` is the line under its title — one sentence, not a paragraph above the
    first box (it ran to 237 characters, six lines at 390px)."""
    stages = registry_to_dict()["stages"]
    assert len(stages) > 20
    long = {s["key"]: len(s["purpose"]) for s in stages if len(s["purpose"]) > 100}
    assert long == {}
    multi = [s["key"] for s in stages if _SENTENCE_BREAK.search(s["purpose"].strip())]
    assert multi == []
