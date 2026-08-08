"""Stage 19's attendance record: who attended, who was certified, and the evidence for it.

WHAT THIS PROTECTS. Certificates and attendance are BASIC tier — the minimum a report is expected
to carry — and for most of this project's life the delivered artefact was a PHOTOGRAPH of a paper
attendance sheet. A photograph cannot be counted, cannot be cross-checked against the roster the
same workshop built in stage 3, and cannot be printed as a table, so the one BASIC-tier fact the
closing stage exists to record was the one fact the report could not state.

The invariants below are the shape that fixes that, and each of them is a way the fix could be
silently undone by a later edit:

* attendance is keyed to the stage-3 roster BY REFERENCE, never by a retyped name — a typed name
  has no join key, so the report can print it but nothing can count it or reconcile it against the
  participants the workshop actually enrolled;
* the row carries somewhere to put a SIGNATURE, because a signed sheet is what an inspecting
  officer asks for and scanning a paper one back in is how the data got lost in the first place;
* and the signature is NEVER required. A signature pad is unusable to a keyboard-only designer and
  unusable to anyone whose handset digitiser has stopped working in the heat. Attendance is the
  datum; the signature is only evidence for it, so gating the datum behind the pad would be a
  capture form that refuses to record a fact the designer watched happen.
"""

# Importing this module is what installs the twenty-two stages into the registry.
import app.services.stage_definitions  # noqa: F401
from app.services.stage_schema import STAGES, Cardinality, FieldType, ReportRole, Tier

STAGE_KEY = "INSPECTION_CLOSING"
ROSTER_ENTITY = "DwParticipant"


def _entity(stage_key: str, entity_key: str):
    stage = next(s for s in STAGES if s.key == stage_key)
    entity = stage.entity(entity_key)
    assert entity is not None, f"{stage_key} no longer declares a '{entity_key}' entity"
    return entity


def _certificates():
    return _entity(STAGE_KEY, "certificate")


def test_attendance_is_a_collection_so_it_can_be_counted_per_artisan():
    """One row per artisan, not one prose blob per workshop.

    A singleton holding "23 attended" is a number nobody can audit; a collection is what lets the
    report print a table and the research queries ask which artisans came back for a second
    workshop.
    """
    assert _certificates().cardinality is Cardinality.COLLECTION


def test_attendance_references_the_stage_three_roster_rather_than_retyping_names():
    """The join key that makes attendance reconcilable with the participants stage 3 enrolled.

    Stage 3's ``participant`` collection IS the roster. If this reference ever became a TEXT field
    the form would still look right and the report would still print names, but the two lists could
    disagree by a spelling and nothing anywhere would notice.
    """
    field = _certificates().field("participantRef")
    assert field is not None, "the attendance row no longer names the artisan it is about"
    assert field.type is FieldType.REF
    assert field.ref_model == ROSTER_ENTITY
    # The roster this points at has to actually exist, or the picker is a dropdown with no members
    # and a required BASIC field becomes permanently unanswerable.
    roster = _entity("WORKSHOP_PLAN_PARTICIPANTS_OPENING", "participant")
    assert roster.name == ROSTER_ENTITY


def test_attendance_can_be_recorded_without_a_signature():
    """The keyboard-only path, asserted as a rule rather than left to the UI to remember.

    Every field a designer needs in order to say "this artisan attended and was certified" must be
    answerable from a keyboard: a reference picker, a checkbox and a number. If a future edit made
    the signature required, the web form and the phone would both start refusing the submit, and
    the refusal would land on the designers least able to work around it.
    """
    entity = _certificates()
    for key in ("participantRef", "issued"):
        field = entity.field(key)
        assert field is not None, f"attendance lost its '{key}' field"
        assert field.tier is Tier.BASIC, f"{key} is what the row is FOR and must stay BASIC"
    assert entity.field("daysAttended") is not None


def test_the_attendance_row_can_carry_a_signature():
    """Somewhere for the signed evidence to live, captured rather than photographed.

    IMAGE and not FILE: the pad exports a PNG, and an image field is what every client already
    draws a thumbnail for. A FILE field would make the signature an opaque attachment the designer
    cannot see they captured.
    """
    field = _certificates().field("signatureImage")
    assert field is not None, (
        "stage 19's attendance row has nowhere to put a signature, so the only way to evidence "
        "attendance is still a photograph of a paper sheet"
    )
    assert field.type is FieldType.IMAGE


def test_the_signature_is_never_required():
    """See the module docstring: the pad may not gate the datum it is only evidence for."""
    field = _certificates().field("signatureImage")
    assert field is not None
    assert not field.required
    # Belt and braces: `validate_registry` already refuses a required non-BASIC field, so a
    # non-BASIC tier is a second, independent guarantee that this can never become mandatory.
    assert field.tier is not Tier.BASIC


def test_the_signature_is_not_a_report_table_column():
    """A signature is an image; a table column is text in every one of the five renderers.

    Giving this field TABLE_COLUMN would ask the .docx writer, the server PDF writer, the on-device
    Kotlin writer, the browser preview and the editor each to invent a way to draw a picture inside
    a table cell, and they would not invent the same one. It would also break the width budget
    asserted below.
    """
    field = _certificates().field("signatureImage")
    assert field is not None
    assert field.report_role is not ReportRole.TABLE_COLUMN


def test_the_attendance_table_still_fits_its_width_budget():
    """The printed columns sum to 100%, so a new field cannot silently squeeze the table.

    This is the assertion that would have caught the signature being added as a sixth column.
    """
    widths = [
        field.column_width_pct
        for field in _certificates().fields
        if field.report_role is ReportRole.TABLE_COLUMN and not field.deprecated
    ]
    assert widths, "the attendance table prints no columns at all"
    assert abs(sum(widths) - 100.0) < 0.01, f"attendance column widths sum to {sum(widths)}"
