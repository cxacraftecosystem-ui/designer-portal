"""The seventeen registry fields that hold a document, a recording or a video, and the one thing
the report owes them.

A .docx cannot embed a sanction order PDF, a fifteen-minute interview or a process video, and
nobody expects it to. What it can do — and for a long time did not — is SAY that one exists. Every
assertion in this file defends that sentence, because its absence had two visible costs and neither
looked like a rendering fault:

  * A designer uploads the ministry's sanction order at stage 1, generates the officer's copy, and
    the file does not mention that a sanction order was attached. The designer cannot tell whether
    the upload failed, the template dropped it, or the report never carried such a thing.
  * The tier warning then actively misled. ``fields_hidden_by_tier`` reads ``_is_filled``, which is
    True for a media field holding ids, so a Compact summary warned "N field(s) recorded in this
    workshop are above Compact summary's capture tier … Generate the report with a template that
    captures every tier to include them", naming "Sanction order document" — while no template
    printed it either. The designer regenerated sixty Advanced-tier pages to recover a field that
    no template in the product could carry.

The line ``report_builder.format_value`` printed for all five media types was ``""`` under the
comment "media never prints as text; it is placed by the image path". ``_images`` is the only image
path and it filters on IMAGE and IMAGE_LIST, so for FILE, AUDIO and VIDEO that comment described a
placement that did not exist.

WHAT IS DELIBERATELY NOT ASSERTED HERE IS A FILENAME. The stored value is a media id and the name
the designer uploaded lives on the ``MediaFile`` row; ``report_builder`` may not query for it,
because it is also the on-device report builder and runs with no network. So the report states the
count and the kind and stops there, which is the whole of what the stage entry itself says.
"""

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.report_builder import (
    ReportBuilder,
    WorkshopData,
    build_report,
    format_value,
)
from app.services.report_model import ImageRef, ReportMeta
from app.services.report_templates import template
from app.services.stage_schema import (
    Cardinality,
    EntitySpec,
    FieldSpec,
    FieldType,
    ReportRole,
    stages,
)


def _entity(stage_key: str, entity_key: str):
    stage = next(s for s in stages() if s.key == stage_key)
    return next(e for e in stage.entities if e.key == entity_key)


def _field(stage_key: str, entity_key: str, field_key: str):
    spec = _entity(stage_key, entity_key).field(field_key)
    assert spec is not None, f"{entity_key}.{field_key} is no longer declared"
    return spec


def _resolver(media_id: str) -> ImageRef:
    return ImageRef(source=media_id, width_px=800, height_px=600, mime_type="image/jpeg")


def _text(document) -> str:
    """Every string the document would print, whatever block carries it."""
    out: list[str] = []

    def harvest(value) -> None:
        if isinstance(value, str):
            out.append(value)
            return
        if isinstance(value, (list, tuple)):
            for item in value:
                harvest(item)
            return
        text = getattr(value, "text", None)
        if isinstance(text, str):
            out.append(text)
        for slot in getattr(value, "__slots__", ()) or ():
            if slot != "text":
                harvest(getattr(value, slot, None))

    for block in document.blocks:
        harvest(block)
    return " | ".join(out)


# --------------------------------------------------------------------------------------
# format_value
# --------------------------------------------------------------------------------------


def test_an_attached_document_says_so_instead_of_printing_nothing():
    spec = _field("WORKSHOP_SETUP", "workshopSetup", "sanctionDocument")
    assert spec.type is FieldType.FILE, "the fixture must stay a FILE field"
    assert format_value(spec, "media-1") == "1 document attached"
    assert format_value(spec, ["media-1", "media-2"]) == "2 documents attached"


def test_a_recording_and_a_video_are_named_as_what_they_are():
    """A designer reading "1 document attached" against "Process video" would think the wrong file
    had been uploaded. The noun costs one dict and is the difference between a line that reassures
    and a line that raises a support question."""
    audio = _field("TRADITIONAL_PROCESS_BASELINE", "traditionalProcess", "artisanAudio")
    video = _field("PROTOTYPE_DEVELOPMENT", "prototype", "processVideo")
    assert format_value(audio, "media-1") == "1 recording attached"
    assert format_value(video, ["media-1", "media-2"]) == "2 videos attached"


def test_an_empty_media_field_still_prints_nothing():
    """Only-what-was-recorded. "0 documents attached" under every unfilled Advanced slot would bury
    the report in negatives, which is the rule ``_printable`` already states for optional fields."""
    spec = _field("WORKSHOP_SETUP", "workshopSetup", "sanctionDocument")
    assert format_value(spec, None) == ""
    assert format_value(spec, []) == ""
    assert format_value(spec, "") == ""


def test_a_photograph_still_prints_no_text_at_all():
    """THE HALF THAT MUST NOT CHANGE. An IMAGE or IMAGE_LIST field really is placed by ``_images``,
    and giving it a stand-in as well would print "1 document attached" directly above the very
    photograph it is talking about, in every gallery in the report."""
    photo = _field("WORKSHOP_SETUP", "workshopSetup", "coverPhoto")
    assert photo.type is FieldType.IMAGE
    assert format_value(photo, "media-1") == ""
    gallery = _field("EXISTING_PRODUCTS_BASELINE", "existingProduct", "productPhotos")
    assert gallery.type is FieldType.IMAGE_LIST
    assert format_value(gallery, ["media-1", "media-2"]) == ""


# --------------------------------------------------------------------------------------
# …and the same thing seen from the finished document
# --------------------------------------------------------------------------------------


def test_the_generated_report_says_a_sanction_order_was_attached():
    """The end-to-end version, because ``format_value`` returning a string is not the same claim as
    a reader finding it in the file. A FILE field is KEY_VALUE by the dataclass default, so it
    reaches the page through ``_printable`` and the per-record key-value grid, under its own label.
    """
    document, _warnings = build_report(
        WorkshopData(
            workshop_id="w1",
            title="Workshop",
            singletons={"WORKSHOP_SETUP": {"workshopTitle": "W", "sanctionDocument": "media-1"}},
        ),
        "DETAILED_TECHNICAL",
        _resolver,
        meta=ReportMeta(title="Workshop", subtitle="Cluster",
                        generated_at="2026-08-07T00:00:00Z"),
    )
    printed = _text(document)
    assert "Sanction order document" in printed
    assert "1 document attached" in printed


#: Every media type that is NOT placed as a picture, DERIVED rather than listed. ``_images`` — the
#: only placement path there is — filters on IMAGE and IMAGE_LIST, so everything else in this set
#: must reach the page as a sentence or reach it not at all.
#:
#: THE SET IS COMPUTED SO THAT A NEW MEMBER JOINS IT BY EXISTING. Written as the literal tuple
#: ``(FILE, AUDIO, VIDEO)``, a sixth media type added to ``FieldType`` tomorrow would be excluded
#: by the filter that was meant to catch it, and the census below would go on passing while the new
#: type printed nothing anywhere — which is the exact hole this file was written about, re-opened
#: by the guard against it.
_UNPLACEABLE_MEDIA = frozenset(
    t for t in FieldType if t.is_media and t not in (FieldType.IMAGE, FieldType.IMAGE_LIST)
)


def test_a_media_field_is_never_a_table_column_whatever_role_it_declares():
    """THE OTHER HALF OF A DIVERGENCE THE HANDSET CLOSED ALONE.

    ``ReportScreen.kt``'s ``renderCollection`` filters ``!isMedia`` out of its columns. Its note on
    the divergence named ``_table_columns`` as the half still open and said the agreement "has to
    be made on the server side first"; that note now records the divergence as closed and points at
    ``_table_columns``' docstring, so those nine words are the only ones still quotable from it and
    they are the Kotlin quoting its own older self.

    TWO EARLIER VERSIONS OF THIS PARAGRAPH PUT WORDS IN THAT FILE'S MOUTH. The first ended the
    Kotlin note at "one of the two, not one each", which is ``docs/AUDIT-2026-08-15.md``'s Remedy
    paragraph and has never been in any file under ``android/``; the second replaced it with a
    sentence beginning "Closing it properly means the two surfaces agreeing on ONE answer", which
    is not in the Kotlin either. Both sent a maintainer grepping ``android/`` for a string no file
    in it contains.

    A picture cannot be a table cell — ``format_value`` prints "" for IMAGE and IMAGE_LIST — so the
    column would have been blank while eating one of the six slots a real answer needed, and the
    two surfaces would have printed different column COUNTS for one workshop.

    The registry declares no media TABLE_COLUMN today, which is why this drives a synthetic entity:
    the guard has to exist BEFORE the field does, or it is written after a submitted document has
    already carried the blank column. Nothing about an existing table's shape changes — no declared
    ``column_width_pct`` anywhere is touched by a filter that removes no field.
    """
    entity = EntitySpec(
        key="probe", name="DwProbe", cardinality=Cardinality.COLLECTION, title="Probe",
        label_field="name",
        fields=(
            FieldSpec(key="name", label="Name", type=FieldType.TEXT,
                      report_role=ReportRole.TABLE_COLUMN),
            FieldSpec(key="photo", label="Photograph", type=FieldType.IMAGE,
                      report_role=ReportRole.TABLE_COLUMN),
            FieldSpec(key="clip", label="Clip", type=FieldType.VIDEO,
                      report_role=ReportRole.TABLE_COLUMN),
        ),
    )
    builder = ReportBuilder(
        WorkshopData(workshop_id="w1", title="Workshop"), template("DETAILED_TECHNICAL"),
        _resolver, meta=ReportMeta(title="Workshop", generated_at="2026-08-19T00:00:00Z"),
    )
    assert [spec.key for spec in builder._table_columns(entity)] == ["name"], (
        "a media-typed field declared TABLE_COLUMN became a column: on A4 that is a blank column "
        "in a six-column budget, and one more column than the same table has on the handset"
    )


def test_every_media_type_that_no_image_path_places_still_prints_a_sentence():
    """THE TYPE-LEVEL HALF, and the one that survives a registry with no such field in it.

    Asked of ``FieldType`` itself rather than of the registry's fields, because the failure this
    file exists about is a hole in ``format_value``'s media branch and a registry that happens to
    declare no VIDEO field this season would hide it. A bare ``FieldSpec`` is enough: the branch
    reads the TYPE and the stored ids and nothing else about the declaration.
    """
    assert _UNPLACEABLE_MEDIA, "no media type falls outside the image path; this file is obsolete"
    for media_type in sorted(_UNPLACEABLE_MEDIA, key=lambda t: t.value):
        spec = FieldSpec(key="probe", label="Probe", type=media_type)
        assert format_value(spec, "media-1"), (
            f"{media_type.value} is a media type that ``_images`` does not place and "
            f"``format_value`` prints nothing for, so a file stored in one of these fields is "
            f"mentioned on no surface of the report"
        )


def test_no_media_field_is_left_with_a_role_that_prints_nothing_for_it():
    """The registry-level half: no field of one of those types is left in a state where nothing
    prints it. Each either declares HIDDEN — a deliberate decision that it is not for the report —
    or resolves through ``format_value`` to a sentence.

    THE HIDDEN ARM IS UNREACHABLE TODAY AND IS KEPT AS A STATEMENT OF THE RULE, not as a live
    branch: while ``format_value`` answers every one of these types, the assertion cannot fail
    whatever role a field declares. What this census actually pins is the COUNT — that the
    seventeen fields the file was written about are all still typed as media rather than having
    been quietly retyped to TEXT — and the type-level test above is where a broken media branch
    goes red. Both are recorded here so the next reader does not mistake this one for the guard.

    Deliberately NOT expressed as a ``validate_registry`` rule. The proposal was to refuse a
    FILE/AUDIO/VIDEO field with a printing ``report_role``, which would fail the registry at import
    for all seventeen of them, because ``ReportRole.KEY_VALUE`` is the ``FieldSpec`` dataclass
    default and not one of these fields declares a role at all. The registry never said "print me";
    it said nothing and the default spoke for it.
    """
    checked = 0
    for stage in stages():
        for entity in stage.entities:
            for spec in entity.fields:
                if spec.type not in _UNPLACEABLE_MEDIA:
                    continue
                checked += 1
                if spec.report_role is ReportRole.HIDDEN:
                    continue
                assert format_value(spec, "media-1"), (
                    f"{entity.key}.{spec.key} is {spec.type.value} with "
                    f"report_role={spec.report_role.value} and prints nothing on any surface: "
                    f"the report would not mention that the file exists"
                )
    assert checked >= 17, (
        f"the registry declares {checked} non-image media fields; this census was written against "
        f"seventeen and a shrinking count means fields were removed rather than covered"
    )
