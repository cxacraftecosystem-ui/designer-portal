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
    assert _certificates().cardinality is Cardinality.COLLECTION


def test_attendance_references_the_stage_three_roster_rather_than_retyping_names():
    field = _certificates().field("participantRef")
    assert field is not None, "the attendance row no longer names the artisan it is about"
    assert field.type is FieldType.REF
    assert field.ref_model == ROSTER_ENTITY
    roster = _entity("WORKSHOP_PLAN_PARTICIPANTS_OPENING", "participant")
    assert roster.name == ROSTER_ENTITY


def test_attendance_can_be_recorded_without_a_signature():
    entity = _certificates()
    for key in ("participantRef", "issued"):
        field = entity.field(key)
        assert field is not None, f"attendance lost its '{key}' field"
        assert field.tier is Tier.BASIC, f"{key} is what the row is FOR and must stay BASIC"
    assert entity.field("daysAttended") is not None


def test_the_attendance_row_can_carry_a_signature():
    field = _certificates().field("signatureImage")
    assert field is not None, (
        "stage 19's attendance row has nowhere to put a signature, so the only way to evidence "
        "attendance is still a photograph of a paper sheet"
    )
    assert field.type is FieldType.IMAGE


def test_the_signature_is_never_required():
    field = _certificates().field("signatureImage")
    assert field is not None
    assert not field.required
    assert field.tier is not Tier.BASIC


def test_the_signature_is_not_a_report_table_column():
    field = _certificates().field("signatureImage")
    assert field is not None
    assert field.report_role is not ReportRole.TABLE_COLUMN


def test_the_attendance_table_still_fits_its_width_budget():
    widths = [
        field.column_width_pct
        for field in _certificates().fields
        if field.report_role is ReportRole.TABLE_COLUMN and not field.deprecated
    ]
    assert widths, "the attendance table prints no columns at all"
    assert abs(sum(widths) - 100.0) < 0.01, f"attendance column widths sum to {sum(widths)}"
