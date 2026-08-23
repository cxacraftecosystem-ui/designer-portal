"""WHERE A SKETCH AND A PROTOTYPE LAND IN THE PRINTED REPORT — pinned, because the mapping is
already right and nothing was watching it.

── WHY THIS FILE EXISTS AT ALL ────────────────────────────────────────────────────────────────────

The "Sketches and Prototypes" page (``frontend/components/sketches/``) captures four things: the
photograph of the sheet, traced line art, a 3D model file and a turn of photographs. Every one of
them already has a home in the registry and a path onto the page, and an audit of the surface found
no missing mapping to invent — which is the most dangerous possible finding, because a correct
mapping that nothing asserts is one refactor away from being a silent hole. THE FOUR HOLES THIS
REPOSITORY HAS ALREADY SHIPPED IN THIS EXACT SHAPE, each of which looked like a rendering fault
rather than a lost field:

  * ``format_value``'s media branch returned "" for FILE, AUDIO and VIDEO under a comment claiming
    they were "placed by the image path", which does not place them — see
    ``tests/test_report_attachments.py``. A sanction order was attached and the officer's copy did
    not mention that a sanction order existed.
  * CARDS presentation printed no TABLE_COLUMN field, so a sketch's number, category and expected
    price were captured, counted as complete, and printed nowhere — the defect
    ``ReportBuilder._render_narrative``'s docstring was written about, in the very entity this file
    covers.
  * ``sketchReview.rank`` was printed for two seasons by the ``report_role`` DEFAULT while a comment
    beside it said it was not.
  * A media-typed field declared TABLE_COLUMN would have eaten one of six column slots and printed
    a blank cell.

So this file states the mapping as assertions rather than as prose: what prints as a picture, what
prints as a sentence, which templates carry these two stages at all, and — the census at the bottom
— that NO field of a sketch or a prototype reaches no section. A new field added to either entity
without a report home fails :func:`test_no_field_of_a_sketch_or_a_prototype_reaches_no_section`.

── WHAT IS DELIBERATELY NOT ASSERTED ──────────────────────────────────────────────────────────────

A FILENAME. ``format_value`` prints a count and a noun for every non-image attachment, on purpose:
the stored value is a media id, the name the designer typed lives on the ``MediaFile`` row, and this
module is also the on-device report builder and may not query for one. "1 document attached" is the
whole of what the entry itself says, and the UPLOAD tab's own copy tells the designer so before they
choose the file (``frontend/components/sketches/upload/PrototypeModelField.tsx``).

THE REVIEW LEDGER, which reaches no section and is the one real gap on this surface. Scores,
assessments and suggestions live in ``DwReviewRating`` — a real table with a real FK to the reviewer
— and no report code reads it: ``report_builder`` has no reference to it and ``SpecialSection`` has
no member for it. That is an owner decision rather than an oversight to paper over here (a new
``SpecialSection`` has to be added to the Kotlin port and moves
``android/app/src/test/resources/report_templates_pin.json``, which can only be regenerated inside
the API container), and it is NOT pinned as an expectation in either direction: a test asserting the
absence would cement it, and a test asserting the presence would be red on purpose. What IS pinned
below is the part of review the report does carry — the arrangement and the stamp that says who
settled it.

Nothing here touches a database.
"""

from types import SimpleNamespace

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.design_workshops import assemble_workshop_data
from app.services.report_builder import ReportBuilder, WorkshopData, build_report, format_value
from app.services.report_model import ImageBlock, ImageGridBlock, ImageRef, ReportMeta
from app.services.report_templates import TEMPLATES, Presentation, template
from app.services.stage_schema import FieldType, ReportRole, Tier, stages

#: The two entities the page writes into, and the stages they are declared on — RESOLVED FROM THE
#: REGISTRY, never from a stage number, exactly as `frontend/components/sketches/stageRows.ts` does
#: it. Stage 11 and stage 13 are facts about today's registry; "the stage that declares `sketch`" is
#: the thing both the page and the report actually mean.
SKETCH = "sketch"
PROTOTYPE = "prototype"


def _stage_of(entity_key: str) -> str:
    found = [s.key for s in stages() for e in s.entities if e.key == entity_key]
    assert found, f"no stage declares {entity_key}"
    assert len(found) == 1, (
        f"{entity_key} is declared on {found}; the sketches page resolves its stage by asking which "
        f"stage declares this entity, and that question needs exactly one answer"
    )
    return found[0]


def _entity(entity_key: str):
    stage_key = _stage_of(entity_key)
    stage = next(s for s in stages() if s.key == stage_key)
    return next(e for e in stage.entities if e.key == entity_key)


def _field(entity_key: str, field_key: str):
    spec = _entity(entity_key).field(field_key)
    assert spec is not None, f"{entity_key}.{field_key} is no longer declared"
    return spec


def _meta() -> ReportMeta:
    return ReportMeta(title="Workshop", subtitle="Cluster", generated_at="2026-08-24T00:00:00Z")


class _Resolver:
    """An image resolver that RECORDS WHAT IT WAS ASKED FOR.

    Which is the only way to assert the negative half of this mapping: that a FILE field's media id
    is never handed to the image path. A resolver that merely answered would let a builder that
    tried to place a 3D model as a picture pass, because the placement would fail silently on the
    ``None`` and print nothing — which is indistinguishable from the correct behaviour by text alone.
    """

    def __init__(self) -> None:
        self.asked: list[str] = []

    def __call__(self, media_id: str) -> ImageRef:
        self.asked.append(media_id)
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


def _placed_images(document) -> list[str]:
    """The media ids the document actually draws, in the order it draws them."""
    out: list[str] = []
    for block in document.blocks:
        if isinstance(block, ImageBlock):
            out.append(block.image.source)
        elif isinstance(block, ImageGridBlock):
            out.extend(image.source for image, _caption in block.images)
    return out


def _report(collections: dict, template_id: str = "DETAILED_TECHNICAL"):
    resolver = _Resolver()
    document, warnings = build_report(
        WorkshopData(
            workshop_id="w1",
            title="Workshop",
            singletons={"WORKSHOP_SETUP": {"workshopTitle": "W"}},
            collections=collections,
        ),
        template_id,
        resolver,
        meta=_meta(),
    )
    return document, warnings, resolver


# --------------------------------------------------------------------------------------
# 1. The four things the page captures, and what becomes of each
# --------------------------------------------------------------------------------------


def test_the_source_photograph_of_a_sketch_is_printed_as_a_picture():
    """``sketch.image`` — the one required media field on this surface.

    GALLERY is asserted as well as the type, because the two are not interchangeable: ``_images``
    filters on TYPE (so the picture is placed whatever role it declares) while
    ``fields_hidden_by_tier`` and the annexure read the role. A photograph retyped or a role changed
    to HIDDEN both take it off the page, and only one of them would show up as a missing picture.
    """
    spec = _field(SKETCH, "image")
    assert spec.type is FieldType.IMAGE
    assert spec.report_role is ReportRole.GALLERY
    assert spec.required, "the sketch photograph is the required half of this surface"
    assert spec.tier is Tier.BASIC, "a photograph above BASIC would leave the Compact summary"

    document, _warnings, resolver = _report({
        _stage_of(SKETCH): {SKETCH: [{
            "_entryId": "ent_s1", "sketchNo": "S-1", "name": "Bandha runner",
            "image": "med_photo", "imageCaption": "The sheet on the courtyard table",
        }]}
    })
    assert "med_photo" in _placed_images(document), (
        "the photograph of the sheet is the picture the report is FOR; it reached no page"
    )
    assert "The sheet on the courtyard table" in _text(document), (
        "the caption is a CAPTION-role field and is placed with its image, never on its own"
    )
    assert resolver.asked.count("med_photo") >= 1


def test_the_traced_line_art_is_named_in_the_document_and_never_placed_as_a_picture():
    """``sketch.lineArtFile`` — a FILE, so a sentence and not a plate.

    This is the half the UPLOAD tab's export copy gets wrong today and the reason that copy is
    reported rather than trusted: choosing PNG over SVG in the tracing panel changes nothing about
    what the officer sees, because BOTH land in this FILE field. Pinning the sentence here is what
    makes that a copy defect rather than a mapping one.
    """
    spec = _field(SKETCH, "lineArtFile")
    assert spec.type is FieldType.FILE
    assert format_value(spec, "med_art") == "1 document attached"

    document, _warnings, resolver = _report({
        _stage_of(SKETCH): {SKETCH: [{
            "_entryId": "ent_s1", "sketchNo": "S-1", "name": "Bandha runner",
            "lineArtFile": "med_art",
        }]}
    })
    printed = _text(document)
    assert spec.label in printed, f"{spec.label!r} is not on the page under any heading"
    assert "1 document attached" in printed
    assert "med_art" not in _placed_images(document)
    assert "med_art" not in resolver.asked, (
        "a FILE id was handed to the image resolver: a .svg or .glb offered to the picture path "
        "prints nothing and hides the sentence that was working"
    )


def test_the_3d_model_is_named_in_the_document_and_never_placed_as_a_picture():
    """``prototype.modelFile`` — the field ``PrototypeModelField`` spends its screen explaining."""
    spec = _field(PROTOTYPE, "modelFile")
    assert spec.type is FieldType.FILE
    assert format_value(spec, ["med_glb", "med_stl"]) == "2 documents attached"

    document, _warnings, resolver = _report({
        _stage_of(PROTOTYPE): {PROTOTYPE: [{
            "_entryId": "ent_p1", "prototypeCode": "P-1", "name": "Table runner",
            "modelFile": "med_glb",
        }]}
    })
    printed = _text(document)
    assert spec.label in printed
    assert "1 document attached" in printed
    assert "med_glb" not in resolver.asked


def test_the_turntable_is_the_one_prototype_attachment_that_prints_as_pictures():
    """``prototype.turntablePhotos`` — an IMAGE_LIST, and the reason the panel leads with it.

    The whole argument of the UPLOAD tab's prototype half is that this field reaches the printed page
    as pictures while the model file beside it reaches it as a count. That claim is made to a
    designer at the moment they are choosing what to attach, so it is asserted here rather than
    trusted: if this field were ever retyped or dropped, the advice on that screen would become a
    lie, and the page would still render.
    """
    spec = _field(PROTOTYPE, "turntablePhotos")
    assert spec.type is FieldType.IMAGE_LIST
    assert format_value(spec, ["med_1", "med_2"]) == "", "a picture never prints as text as well"

    document, _warnings, _resolver = _report({
        _stage_of(PROTOTYPE): {PROTOTYPE: [{
            "_entryId": "ent_p1", "prototypeCode": "P-1", "name": "Table runner",
            "turntablePhotos": ["med_f1", "med_f2", "med_f3"],
        }]}
    })
    placed = _placed_images(document)
    for frame in ("med_f1", "med_f2", "med_f3"):
        assert frame in placed, f"{frame} was captured as a turntable frame and printed nowhere"


# --------------------------------------------------------------------------------------
# 2. Which templates carry these two stages at all
# --------------------------------------------------------------------------------------


def _carrying(stage_key: str) -> set[str]:
    return {
        t.id for t in TEMPLATES
        if any(section.stage_key == stage_key for section in t.sections)
    }


@pytest.mark.parametrize("entity_key", [SKETCH, PROTOTYPE])
def test_the_two_stages_are_carried_by_the_three_templates_that_print_the_whole_workshop(entity_key):
    """PINNED BOTH WAYS, because both directions have already gone wrong somewhere in this module.

    The three standard templates carry these stages through ``_standard_sections()``; the other
    three do not, and each absence is a decision with a reason on it: ``IMPLEMENTING_AGENCY`` prints
    outcomes and final documentation for the agency's own file, ``PHOTO_CATALOGUE`` is buyer-facing
    and prints finished products, and ``COMPACT_SUMMARY`` is a few pages for a review meeting.

    THE TIER IS THE SECOND HALF AND THE EASIER ONE TO LOSE. Three of the four fields this surface
    writes are ADVANCED, so a carrying template whose ``max_tier`` dropped to STANDARD would keep
    the section, keep the heading, and silently print no line art, no model file and no turntable.
    ``COMPACT_SUMMARY`` is exactly that template — ``max_tier=Tier.BASIC`` — which is why it not
    carrying the stages at all is the honest state rather than a gap.
    """
    stage_key = _stage_of(entity_key)
    assert _carrying(stage_key) == {"DCH_STANDARD", "DIC_STANDARD", "DETAILED_TECHNICAL"}
    for template_id in _carrying(stage_key):
        assert template(template_id).max_tier is Tier.ADVANCED, (
            f"{template_id} carries {stage_key} at a tier that drops its ADVANCED fields: the "
            f"section would print with the line art, the model and the turntable missing"
        )


@pytest.mark.parametrize("entity_key", [SKETCH, PROTOTYPE])
def test_the_two_stages_are_printed_as_cards_so_every_row_can_carry_its_own_photograph(entity_key):
    """CARDS, not TABLE, and it is the presentation that makes the pictures possible.

    ``_render_table`` draws one row per record; a photograph cannot be a table cell (``format_value``
    prints "" for IMAGE and a media field is filtered out of ``_table_columns`` altogether). CARDS
    is what gives each sketch and each prototype a sub-heading of its own with its images under it —
    and, since the fix its own docstring records, prints every TABLE_COLUMN field as a key-value
    pair too, so nothing is lost by not drawing the table.
    """
    stage_key = _stage_of(entity_key)
    for t in TEMPLATES:
        for section in t.sections:
            if section.stage_key == stage_key:
                assert section.presentation is Presentation.CARDS, (
                    f"{t.id} prints {stage_key} as {section.presentation.value}: a presentation "
                    f"that draws no per-record gallery loses every photograph on this surface"
                )
                assert section.include_photos, (
                    f"{t.id} prints {stage_key} with photographs switched off"
                )


# --------------------------------------------------------------------------------------
# 3. The arrangement — the one product of the REVIEW tab the report DOES carry
# --------------------------------------------------------------------------------------


def _entry(entry_id: str, stage_key: str, entity_key: str, data: dict, ordinal: int):
    return SimpleNamespace(
        id=entry_id, stageKey=stage_key, entityKey=entity_key, ordinal=ordinal,
        data=dict(data), fieldProvenance={}, clientKey=None, deletedAt=None,
    )


def test_the_designers_arrangement_is_the_order_the_report_prints_them_in():
    """THE RANKING IS ``DwStageEntry.ordinal`` AND NOTHING ELSE, END TO END.

    The REVIEW tab has no reorder endpoint on purpose: dragging a card writes the ordinal through the
    ordinary stage save, so the arrangement a workshop settled on IS the order of the cards in the
    submitted document. That is two functions apart — ``assemble_workshop_data`` sorts, the builder
    preserves — and neither says so about the other, so it is asserted through both at once with the
    rows handed over in the WRONG order, which is the state a database read gives (``find_many``
    returns rows unordered).
    """
    stage_key = _stage_of(SKETCH)
    entries = [
        _entry("ent_c", stage_key, SKETCH, {"sketchNo": "S-3", "name": "Third sketch"}, ordinal=2),
        _entry("ent_a", stage_key, SKETCH, {"sketchNo": "S-1", "name": "First sketch"}, ordinal=0),
        _entry("ent_b", stage_key, SKETCH, {"sketchNo": "S-2", "name": "Second sketch"}, ordinal=1),
    ]
    data = assemble_workshop_data(SimpleNamespace(id="w1", title="Workshop"), entries)
    assert [row["name"] for row in data.rows(stage_key, SKETCH)] == [
        "First sketch", "Second sketch", "Third sketch",
    ]

    document, _warnings = build_report(data, "DETAILED_TECHNICAL", _Resolver(), meta=_meta())
    printed = _text(document)
    first, second, third = (
        printed.index("First sketch"), printed.index("Second sketch"), printed.index("Third sketch")
    )
    assert first < second < third, (
        "the report re-ordered the designers' arrangement: the ordinal is the ONLY record that an "
        "order was deliberate, and a document that prints it in another order says nothing about it"
    )


@pytest.mark.parametrize("entity_key", [SKETCH, PROTOTYPE])
def test_the_document_says_who_settled_the_order_and_when(entity_key):
    """``rankFixedBy`` / ``rankFixedAt`` — blank means the computed score still governs.

    These two fields ARE the difference between ten pieces in score order and ten pieces somebody
    arranged, because the ordinal alone carries no author (``entry_provenance.stamp`` skips the
    ``_``-prefixed protocol keys by name). A report that printed the arrangement and not the stamp
    would show a deliberate order with nothing to say it was one.
    """
    stage_key = _stage_of(entity_key)
    by = _field(entity_key, "rankFixedBy")
    at = _field(entity_key, "rankFixedAt")
    assert by.report_role is ReportRole.KEY_VALUE and at.report_role is ReportRole.KEY_VALUE

    document, _warnings, _resolver = _report({
        stage_key: {entity_key: [{
            "_entryId": "ent_1", "name": "Arranged piece",
            "rankFixedBy": "Meena Iyer", "rankFixedAt": "2026-08-20",
        }]}
    })
    printed = _text(document)
    assert "Meena Iyer" in printed
    assert "20 Aug 2026" in printed


# --------------------------------------------------------------------------------------
# 4. The census: no field of either entity reaches no section
# --------------------------------------------------------------------------------------


def _probe_value(spec, marker: str):
    """A filled value for one field, and the string the document must then contain.

    Returns ``(value, expected_text_or_None)``. ``None`` means "assert the LABEL reached the page
    instead of the value" — which is the right question for a type whose printed form is not a
    string this test chose (a BOOL prints "Yes", an ENUM prints a canonical label, a REF prints
    whatever the reference resolution makes of an id).
    """
    t = spec.type
    if t is FieldType.RICH_TEXT:
        return {"blocks": [{"kind": "PARAGRAPH", "spans": [{"text": marker}]}]}, marker
    if t in (FieldType.TEXT, FieldType.LONG_TEXT, FieldType.URL, FieldType.PHONE, FieldType.EMAIL):
        return marker, marker
    if t is FieldType.TAGS:
        return [marker], marker
    if t is FieldType.MULTI_ENUM:
        return [marker], None
    if t is FieldType.ENUM:
        return marker, None
    if t is FieldType.BOOL:
        return True, None
    if t is FieldType.INT:
        return 7, None
    if t in (FieldType.DECIMAL, FieldType.PERCENT):
        return 7.5, None
    if t is FieldType.MONEY:
        return "1234", None
    if t is FieldType.DATE:
        return "2026-08-20", None
    if t is FieldType.TIME:
        return "10:30", None
    if t is FieldType.GEO:
        return {"lat": 20.5, "lon": 84.5}, None
    if t is FieldType.REF:
        return "ent_other", None
    if t in (FieldType.IMAGE, FieldType.IMAGE_LIST):
        return ([marker] if t is FieldType.IMAGE_LIST else marker), None
    if t.is_media:
        return marker, None
    raise AssertionError(f"{t.value} has no probe value; add one rather than skipping the field")


@pytest.mark.parametrize("entity_key", [SKETCH, PROTOTYPE])
def test_no_field_of_a_sketch_or_a_prototype_reaches_no_section(entity_key):
    """EVERY field of these two entities is printed by the archival template, or declares HIDDEN.

    ── WHY A CENSUS AND NOT A LIST ────────────────────────────────────────────────────────────────

    A list of the four fields the sketches page writes would pass for ever while a fifth field added
    beside them printed nowhere. This asks the registry what a sketch and a prototype HAVE, fills
    every one of them, renders the template that claims to print "everything captured, including the
    Advanced tier", and requires each field to have left a mark. It is the assertion form of
    ``_render_narrative``'s own rule: "Every role the registry can carry must be printed by every
    presentation, or the presentation is a filter on the designer's work rather than a layout of it."

    ── THE THREE ARMS, AND WHY THE FIRST TWO ARE NOT THE SAME QUESTION ────────────────────────────

    A picture is asked for by MEDIA ID, because a placed photograph prints no text at all — a
    label-based check would fail on the one field class that is working hardest. A caption is asked
    for by its TEXT, because ``_printable`` deliberately never prints a caption's label: it is
    placed under the image it belongs to. Everything else is asked for by LABEL, which is what a
    key-value pair, a table header and a narrative sub-heading all carry, and by its VALUE too where
    the value is a string this test chose rather than a canonical form the registry owns.

    ── HIDDEN IS AN ANSWER, AND IT IS ALLOWED HERE ────────────────────────────────────────────────

    ``sketch.supersedesSketch`` and ``prototype.productRef`` declare HIDDEN, and both are right to:
    an external REF prints a cuid, and the workshop-internal one exists so the report can find the
    record it points at rather than to be printed. What this census refuses is the SILENT version of
    that — a field that prints nowhere because nothing prints its role, with no declaration saying
    so. A deprecated field is skipped for the same reason ``_visible`` skips it.

    ── THE EXACT EDGE OF THE PIN, MEASURED BY MUTATION (adversarial review, 2026-08-24) ──────────

    Flipping ``sketch.sketchNo``'s ``report_role`` on the live registry and re-running this test gave
    the following. The measurement is recorded here rather than left in a review comment because it
    is the only statement of what this test does NOT catch:

        baseline PASS · HIDDEN **PASS** · NARRATIVE PASS · KEY_VALUE PASS · TABLE_COLUMN PASS
        COVER_FIELD PASS · BULLETS PASS · CAPTION FAIL · GALLERY FAIL · METRIC FAIL

    So this test is a pin against a field that reaches no section BY ACCIDENT, and it is deliberately
    not a pin against one removed BY DECLARATION: HIDDEN is an answer, and the census's whole
    argument is that a declaration with a reason beside it is the acceptable form of "prints
    nowhere". The three FAILs are the roles whose printing depends on something other than a role —
    a caption needs a media field to sit under, a gallery needs a media type, a metric needs a
    number — and they fail here because the probe cannot satisfy them, not because the census is
    stricter about them.

    WHAT THAT LEAVES COVERED ANYWAY, so nobody reads the paragraph above as a hole in the four fields
    the sketches page actually writes: ``sketch.image``, ``sketch.lineArtFile``,
    ``prototype.modelFile`` and ``prototype.turntablePhotos`` are each pinned by a dedicated test at
    the top of this module which RENDERS the template and looks for the picture or the sentence, so
    declaring any of them HIDDEN fails there rather than here. A golden table of every declared role
    was considered and rejected: it would freeze declarations this module deliberately leaves to the
    registry, and the argument for a role belongs beside the field.
    """
    entity = _entity(entity_key)
    stage_key = _stage_of(entity_key)
    row: dict[str, object] = {"_entryId": "ent_probe"}
    expected: dict[str, tuple[object, object]] = {}
    for spec in entity.fields:
        if spec.deprecated or spec.report_role is ReportRole.HIDDEN:
            continue
        marker = f"Zqx{spec.key}"
        value, text = _probe_value(spec, marker)
        row[spec.key] = value
        expected[spec.key] = (spec, text)

    document, _warnings, resolver = _report({stage_key: {entity_key: [row]}})
    printed = _text(document)
    placed = set(_placed_images(document)) | set(resolver.asked)

    unprinted: list[str] = []
    for key, (spec, text) in expected.items():
        if spec.type in (FieldType.IMAGE, FieldType.IMAGE_LIST):
            if f"Zqx{key}" not in placed:
                unprinted.append(f"{key} ({spec.type.value}, no picture placed)")
            continue
        if spec.report_role is ReportRole.CAPTION:
            if f"Zqx{key}" not in printed:
                unprinted.append(f"{key} (CAPTION, text not on the page)")
            continue
        if spec.label not in printed and (text is None or str(text) not in printed):
            unprinted.append(f"{key} ({spec.type.value}/{spec.report_role.value})")

    assert not unprinted, (
        f"{entity_key} fields captured on the sketches and prototypes page that reach no section of "
        f"the archival report: {unprinted}. Give each one a printing report_role, or declare HIDDEN "
        f"with the reason, so that 'captured' and 'printed' cannot silently disagree"
    )


def test_a_media_field_of_either_entity_is_never_a_table_column():
    """The column budget, asked of these two entities specifically.

    ``tests/test_report_attachments.py`` pins the RULE against a synthetic entity; this pins the two
    real entities the sketches page writes into. A media field declared TABLE_COLUMN would print a
    blank cell — ``format_value`` returns "" for IMAGE and ``_table_columns`` filters media out — and
    would eat one of the six slots on A4 that a craft name needs.
    """
    builder = ReportBuilder(
        WorkshopData(workshop_id="w1", title="Workshop"), template("DETAILED_TECHNICAL"),
        _Resolver(), meta=_meta(),
    )
    for entity_key in (SKETCH, PROTOTYPE):
        entity = _entity(entity_key)
        for spec in builder._table_columns(entity):
            assert not spec.type.is_media, f"{entity_key}.{spec.key} became a media table column"
        assert len(builder._table_columns(entity)) <= 6
