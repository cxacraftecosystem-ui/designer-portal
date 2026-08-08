"""Stage 20's answers actually change the report.

Every setting pinned here was, until this file existed, **stored and ignored**: the form wrote it,
the API validated it, Postgres kept it, and not one line of the pipeline ever read it back. A
designer who turned "Number the headings" on, or "Include the photographic annexure" off, got a
report identical to the one they would have got having touched nothing — with no error, no warning
and no way to tell the switch was inert. That is a worse failure than an absent feature, because
an absent feature is visible and a silent one gets blamed on the designer.

So these tests are deliberately written as *differences*, not as absolute assertions. Each one
builds the same workshop twice — once with the setting and once without — and requires the two
documents to differ in the one specific way the setting promises. An absolute assertion ("the
document has no TOC") can pass against a pipeline that has simply lost the ability to draw one;
a difference cannot.

The final test is the one that matters most for trust: **absent means untouched**. A workshop
saved before any of these toggles existed must still produce exactly the report it always did.
"""

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.report_builder import WorkshopData, build_report
from app.services.report_model import (
    HeadingBlock,
    ImageRef,
    ReportMeta,
    TocBlock,
)
from app.services.report_templates import (
    SpecialSection,
    apply_report_settings,
    template as get_template,
)

TEMPLATE_ID = "DETAILED_TECHNICAL"


def _meta(**kw) -> ReportMeta:
    base = {"title": "Workshop", "subtitle": "Cluster", "generated_at": "2026-08-07T00:00:00Z"}
    base.update(kw)
    return ReportMeta(**base)


def _resolver(media_id: str) -> ImageRef:
    return ImageRef(source=media_id, width_px=800, height_px=600, mime_type="image/jpeg")


def _data(settings: dict | None = None) -> WorkshopData:
    """A workshop with enough in it that the sections under test are not empty-skipped."""
    return WorkshopData(
        workshop_id="w1",
        title="Design & Prototype Development Workshop",
        singletons={
            "WORKSHOP_SETUP": {
                "workshopTitle": "Design & Prototype Development Workshop",
                "craftName": "Sambalpuri Bandha",
                "clusterName": "Barpali",
                "state": "Odisha",
                "district": "Bargarh",
            },
            "CLUSTER_CRAFT_BACKGROUND": {
                "history": "Barpali has woven bandha since the nineteenth century.",
            },
            **({"REPORT_GENERATION": settings} if settings else {}),
        },
    )


def _build(settings: dict | None, **kw):
    template = apply_report_settings(get_template(TEMPLATE_ID), settings, **kw)
    document, _ = build_report(_data(settings), TEMPLATE_ID, _resolver,
                               meta=_meta(), template=template)
    return document


def _specials(template_id: str = TEMPLATE_ID) -> set:
    return {s.special for s in get_template(template_id).sections if s.special}


# --------------------------------------------------------------------------------------
# The section toggles
# --------------------------------------------------------------------------------------

def test_table_of_contents_can_be_turned_off():
    assert SpecialSection.TOC in _specials(), "the fixture template must carry a TOC to remove"

    with_toc = _build(None)
    without = _build({"includeTableOfContents": False})

    assert any(isinstance(b, TocBlock) for b in with_toc.blocks)
    assert not any(isinstance(b, TocBlock) for b in without.blocks)


def test_media_annexure_can_be_turned_off():
    kept = apply_report_settings(get_template(TEMPLATE_ID), {"includeMediaAnnexure": True})
    dropped = apply_report_settings(get_template(TEMPLATE_ID), {"includeMediaAnnexure": False})

    assert any(s.special is SpecialSection.ANNEXURE_MEDIA for s in kept.sections)
    assert not any(s.special is SpecialSection.ANNEXURE_MEDIA for s in dropped.sections)


def test_completeness_annexure_can_be_turned_off():
    dropped = apply_report_settings(
        get_template(TEMPLATE_ID), {"includeCompletenessAnnexure": False}
    )
    assert not any(s.special is SpecialSection.COMPLETENESS for s in dropped.sections)


def test_excluded_stages_removes_exactly_those_stages():
    shaped = apply_report_settings(
        get_template(TEMPLATE_ID), {"excludedStages": ["CLUSTER_CRAFT_BACKGROUND"]}
    )
    keys = {s.stage_key for s in shaped.sections if s.stage_key}

    assert "CLUSTER_CRAFT_BACKGROUND" not in keys
    # and nothing else went with it
    original = {s.stage_key for s in get_template(TEMPLATE_ID).sections if s.stage_key}
    assert keys == original - {"CLUSTER_CRAFT_BACKGROUND"}


def test_an_unknown_excluded_stage_is_ignored_rather_than_fatal():
    """A phone one release ahead may name a stage this build has never heard of."""
    shaped = apply_report_settings(
        get_template(TEMPLATE_ID), {"excludedStages": ["NO_SUCH_STAGE", ""]}
    )
    assert len(shaped.sections) == len(get_template(TEMPLATE_ID).sections)


# --------------------------------------------------------------------------------------
# Presentation
# --------------------------------------------------------------------------------------

def test_number_headings_reaches_the_headings():
    """The number is a field on the block, not a prefix on the text.

    ``HeadingBlock.number`` is precomputed by the builder and rendered by each renderer, so the
    runs are byte-identical either way and comparing heading TEXT proves nothing — which is the
    trap this test fell into on its first draft and the reason the comment is here.
    """
    def numbers(doc):
        return [b.number for b in doc.blocks if isinstance(b, HeadingBlock)]

    numbered = numbers(_build({"numberHeadings": True}))
    plain = numbers(_build({"numberHeadings": False}))

    assert any(numbered), "numberHeadings=true produced no numbered heading at all"
    assert not any(plain), "numberHeadings=false still numbered the headings"
    # Same document, same headings — only the numbering differs.
    assert len(numbered) == len(plain)


def test_photo_columns_reaches_every_section():
    shaped = apply_report_settings(get_template(TEMPLATE_ID), {"photoColumns": 4})
    assert {s.photo_columns for s in shaped.sections} == {4}


def test_photo_columns_is_clamped_rather_than_trusted():
    """An older phone's registry may not carry the 1..4 bounds; zero would divide by zero."""
    for raw, expected in ((0, 1), (99, 4), ("3", 3), ("nonsense", None), (None, None)):
        shaped = apply_report_settings(get_template(TEMPLATE_ID), {"photoColumns": raw})
        if expected is None:
            assert shaped.sections == get_template(TEMPLATE_ID).sections
        else:
            assert {s.photo_columns for s in shaped.sections} == {expected}


def test_photographs_off_is_an_absolute_veto():
    shaped = apply_report_settings(get_template(TEMPLATE_ID), {"includePhotographs": False})
    assert not any(s.include_photos for s in shaped.sections)


def test_photographs_on_does_not_force_photos_into_a_section_that_excludes_them():
    """ON must mean "do not override the template", not "put photographs everywhere".

    A template that deliberately keeps photographs out of, say, a cost annexure is making a
    layout decision the designer's blanket "include photographs" was never asked about.
    """
    base = get_template(TEMPLATE_ID)
    shaped = apply_report_settings(base, {"includePhotographs": True})
    assert [s.include_photos for s in shaped.sections] == [s.include_photos for s in base.sections]


def test_the_request_beats_the_saved_setting():
    """One report without photographs, from settings that say to include them, without a save."""
    shaped = apply_report_settings(
        get_template(TEMPLATE_ID), {"includePhotographs": True}, include_photographs=False
    )
    assert not any(s.include_photos for s in shaped.sections)


# --------------------------------------------------------------------------------------
# The guarantee that lets this ship
# --------------------------------------------------------------------------------------

def test_absent_settings_leave_the_template_identical():
    """The whole feature must be invisible to a workshop that never opened stage 20.

    ``is`` and not ``==``: the template must be returned untouched, not rebuilt equal. Rebuilding
    would be correct today and would quietly become a per-request copy of six templates later.
    """
    base = get_template(TEMPLATE_ID)
    assert apply_report_settings(base, None) is base
    assert apply_report_settings(base, {}) is base


def test_a_setting_nobody_answered_changes_nothing():
    """Keys present but empty are "not answered", not "no"."""
    base = get_template(TEMPLATE_ID)
    shaped = apply_report_settings(base, {"numberHeadings": None, "includeTableOfContents": ""})

    assert shaped.number_headings == base.number_headings
    assert any(s.special is SpecialSection.TOC for s in shaped.sections)


def test_shaping_never_mutates_the_shared_template():
    """One designer's choices must not leak into every other report in the process."""
    base = get_template(TEMPLATE_ID)
    before = (base.number_headings, len(base.sections),
              tuple(s.photo_columns for s in base.sections))

    apply_report_settings(base, {
        "numberHeadings": False, "photoColumns": 4, "includeTableOfContents": False,
        "excludedStages": ["CLUSTER_CRAFT_BACKGROUND"],
    })

    after = (base.number_headings, len(base.sections),
             tuple(s.photo_columns for s in base.sections))
    assert before == after
    assert get_template(TEMPLATE_ID) is base


# --------------------------------------------------------------------------------------
# The request overrides, through render_report itself
# --------------------------------------------------------------------------------------

class _Record:
    """The handful of columns ``report_meta`` reads off a DesignWorkshop row."""

    id = "w1"
    title = "Design & Prototype Development Workshop"
    craftName = "Sambalpuri Bandha"
    clusterName = "Barpali"
    state = "Odisha"
    designerName = "Ananya Mohapatra"
    implementingAgency = "Sambalpuri Bastralaya"
    workshopCode = "SBB/DPDW/2025-26/BRP-04"


class _Resolver:
    """Geometry for any id, bytes for none — no image is referenced by this fixture."""

    def ref(self, media_id):
        return None

    def blob(self, image):
        return None

    def prefetch(self, wanted):
        return None


class _Options:
    """A ReportGenerateIn as the web report page actually fills it in."""

    templateId = TEMPLATE_ID
    formats = ("DOCX",)
    pageSize = "LETTER"
    headerText = "Sambalpuri Bandha — Barpali"
    footerText = "DCH standard · SBB/DPDW/2025-26/BRP-04"
    themeAccent = None
    includePhotographs = None
    includeTranscripts = None
    record = False


def test_render_report_accepts_the_page_size_header_and_footer_overrides():
    """THE 500 THAT REACHED A DESIGNER AS "you have no connection".

    ``render_report`` rebuilt its ReportMeta with ``ReportMeta(**{**meta.__dict__, ...})``. But
    ReportMeta is ``@dataclass(frozen=True, slots=True)`` and a slotted instance HAS NO
    ``__dict__``, so every one of those three lines raised AttributeError the moment the request
    carried the field it guards. The web report page sends all three as soon as stage 20 has a
    page size, a running header or a running footer saved — so the designers who filled the
    settings in most carefully were the only ones who could never download a report.

    Nothing caught it because no test called ``render_report`` at all, and the web client reports
    any 5xx as a probable network fault, so the screen blamed the connection while the server was
    up and answering.
    """
    from app.services.design_workshops import render_report

    blob, warnings, page_count = render_report(
        _data(None), TEMPLATE_ID, _Resolver(), _Record(), "DOCX", _Options()
    )

    assert blob[:2] == b"PK", "a .docx is a zip; this is the real file, not an error page"
    assert len(blob) > 1000
    assert isinstance(warnings, list)
    assert page_count is None, "page counting is a PDF-only concern"


def test_render_report_survives_a_page_size_it_does_not_recognise():
    """A phone one release ahead may send a paper size this build has never heard of."""
    from app.services.design_workshops import render_report

    class Odd(_Options):
        pageSize = "FOOLSCAP"

    blob, _, _ = render_report(_data(None), TEMPLATE_ID, _Resolver(), _Record(), "DOCX", Odd())
    assert blob[:2] == b"PK"


def test_render_report_needs_no_options_at_all():
    """The Android app and any direct API caller send none of them."""
    from app.services.design_workshops import render_report

    blob, _, _ = render_report(_data(None), TEMPLATE_ID, _Resolver(), _Record(), "DOCX", None)
    assert blob[:2] == b"PK"


# --------------------------------------------------------------------------------------
# The typeface
# --------------------------------------------------------------------------------------

def test_the_chosen_typeface_reaches_the_docx():
    """The .docx is the file that is submitted, so this is the one that has to be true."""
    import zipfile
    from io import BytesIO

    from app.services.design_workshops import render_report

    class Serif(_Options):
        fontPreset = "GEORGIA"

    blob, warnings, _ = render_report(
        _data(None), TEMPLATE_ID, _Resolver(), _Record(), "DOCX", Serif()
    )
    xml = zipfile.ZipFile(BytesIO(blob)).read("word/styles.xml").decode("utf-8")
    assert "Georgia" in xml, "the chosen family is not in the document's styles"
    assert not any("typeface" in w for w in warnings), "the .docx honours it, so it must not warn"


def test_a_pdf_says_so_rather_than_quietly_using_another_face():
    """A PDF must embed a face that can draw Odia, Devanagari and the rupee sign, and that is
    chosen from what the server has. Substituting in silence would hand the designer two files
    that look nothing alike and no reason why."""
    from app.services.design_workshops import render_report

    class Serif(_Options):
        fontPreset = "GEORGIA"

    _, warnings, _ = render_report(
        _data(None), TEMPLATE_ID, _Resolver(), _Record(), "PDF", Serif()
    )
    assert any("Georgia" in w for w in warnings), (
        "the PDF cannot honour the typeface and must say so — a silent substitution is exactly "
        "the trap the stored-and-ignored settings were, arrived at from the other direction"
    )


def test_no_typeface_chosen_means_no_warning_and_no_change():
    """The guarantee that lets this ship: silent where nobody asked."""
    from app.services.design_workshops import render_report

    for fmt in ("DOCX", "PDF"):
        _, warnings, _ = render_report(
            _data(None), TEMPLATE_ID, _Resolver(), _Record(), fmt, _Options()
        )
        assert not any("typeface" in w for w in warnings), fmt


def test_an_unknown_typeface_is_ignored_rather_than_guessed():
    """A newer client's font name must not silently restyle a ministry's report."""
    from app.services.report_theme import resolve_font

    assert resolve_font("FRAKTUR", {}) is None
    assert resolve_font(None, {"fontPreset": "FRAKTUR"}) is None


def test_a_missing_map_is_not_reported_as_a_missing_photograph():
    """THE MESSAGE THAT SENT DESIGNERS HUNTING FOR A PHOTO THAT NEVER EXISTED.

    Every dropped figure came back as "N photograph(s) could not be included in the file", and a
    renderer drops three different things. When the deployed image shipped without the boundary
    geometry, the whole of section 6 — "Where the workshop was held and where its artisans live"
    — printed as a heading followed immediately by section 7, and the only signal was that
    sentence, on workshops that had no photographs at all. A photograph is a fetch the designer
    can retry; a map is geometry missing on the server, and they must be told which they have.
    """
    from app.services.design_workshops import _dropped_warnings

    assert _dropped_warnings([]) == []

    only_map = _dropped_warnings(["map:india"])
    assert len(only_map) == 1
    assert "locator map" in only_map[0]
    assert "photograph" not in only_map[0].lower()

    mixed = " | ".join(_dropped_warnings([
        "map:india", "chart:DONUT", "figure:2", "cmsik2jg8000eh8xc1lcy661a",
    ]))
    assert "locator map" in mixed
    assert "2 figure(s)" in mixed, "a chart placed by either path is a figure, not a photograph"
    assert "1 photograph(s)" in mixed, "and a real media id must still be reported as one"


def test_a_photograph_that_did_not_arrive_is_still_called_a_photograph():
    """The half that must not regress: the original message was right for the original case."""
    from app.services.design_workshops import _dropped_warnings

    said = _dropped_warnings(["media-1", "media-2"])
    assert said == ["2 photograph(s) could not be included in the file."]


def test_the_stage_20_template_picker_actually_chooses_the_template():
    """"Report template" is REQUIRED and BASIC — the completeness gate demands it and the
    annexure counts it as satisfied — and both report routes resolved the template as
    `payload/query templateId or record.templateId`, skipping the saved answer entirely. A
    designer worked through 22 stages, reached stage 20, picked "Photo catalogue" because the
    form insisted, generated the report and got the DCH standard one. Nothing said the field was
    inert.
    """
    from app.services.design_workshops import resolve_template_id

    class Record:
        templateId = "DCH_STANDARD"

    assert resolve_template_id(None, {"templateId": "PHOTO_CATALOGUE"}, Record()) == \
        "PHOTO_CATALOGUE"
    # The request still wins, so one file can be produced from another template without editing
    # the saved answer — the precedence every other stage-20 setting uses.
    assert resolve_template_id("COMPACT_SUMMARY", {"templateId": "PHOTO_CATALOGUE"}, Record()) == \
        "COMPACT_SUMMARY"
    # And a workshop that never opened stage 20 prints exactly the report it always did.
    assert resolve_template_id(None, {}, Record()) == "DCH_STANDARD"
    assert resolve_template_id(None, None, Record()) == "DCH_STANDARD"


def test_a_template_id_this_build_does_not_know_is_ignored_rather_than_obeyed():
    """`get_template` falls back to the DCH standard for anything it does not recognise, so
    passing an unknown token through would turn "this build has not heard of that template" into
    "your chosen template is the default", with no way to tell the two apart. Same rule as
    `fontPreset` and `themeAccent`."""
    from app.services.design_workshops import resolve_template_id

    class Record:
        templateId = "PHOTO_CATALOGUE"

    assert resolve_template_id(None, {"templateId": "FROM_A_NEWER_CLIENT"}, Record()) == \
        "PHOTO_CATALOGUE"
    assert resolve_template_id("ALSO_UNKNOWN", {}, Record()) == "PHOTO_CATALOGUE"
    assert resolve_template_id(None, {"templateId": "  "}, Record()) == "PHOTO_CATALOGUE"


# --------------------------------------------------------------------------------------
# The cover, which is where five stage-20 answers were supposed to land
# --------------------------------------------------------------------------------------


def _cover(settings: dict, **data_kw):
    """The CoverBlock of a report whose stage 20 says ``settings``."""
    from app.services.design_workshops import report_meta
    from app.services.report_builder import build_report
    from app.services.report_model import CoverBlock

    class Record:
        id = "w1"
        title = "DPDW Barpali Jan-26"
        templateId = TEMPLATE_ID
        craftName = "Sambalpuri Bandha"
        clusterName = "Barpali"
        state = "Odisha"
        designerName = "A Designer"
        implementingAgency = ""
        workshopCode = "WS-1"

    assert not data_kw, "the helper takes settings only"
    document, _warnings = build_report(
        _data(settings), TEMPLATE_ID, _resolver,
        meta=report_meta(Record(), TEMPLATE_ID, settings),
    )
    return next(b for b in document.blocks if isinstance(b, CoverBlock))


def test_the_organisation_line_is_printed_above_the_title_as_its_help_says():
    """`organisationLine`'s own help text is "Printed above the title on the cover." and the
    cover read `self.template.organisation` — the template's own constant — so the box did
    nothing at all. A designer typed their institute's name and the cover printed the ministry's,
    with nothing anywhere to say the field was inert."""
    cover = _cover({"organisationLine": "Sambalpuri Weavers Cooperative, Barpali"})
    assert any("Sambalpuri Weavers Cooperative" in line for line in cover.org_lines)


def test_the_letterhead_reaches_the_cover_and_is_bounded():
    """Stored and printed nowhere. Bounded because a pasted signature block would otherwise push
    the report's title off its own cover."""
    cover = _cover({"letterheadText": "\n".join(f"Line {i}" for i in range(1, 10))})
    lines = [line for line in cover.org_lines if line.startswith("Line ")]
    assert lines[:3] == ["Line 1", "Line 2", "Line 3"]
    assert len(lines) <= 6


def test_the_addressee_and_the_submission_date_are_on_the_cover():
    """The one page whose job is to say who the report is for said nothing about who it is for."""
    cover = _cover({"submittedTo": "Development Commissioner (Handicrafts)",
                    "submissionDate": "2031-03-04"})
    assert any(
        "Submitted to Development Commissioner (Handicrafts) on 04 Mar 2031" in line
        for line in cover.footer_lines
    )


def test_either_half_of_the_submission_line_stands_alone():
    from app.services.report_builder import _submission_line

    assert _submission_line({"submittedTo": "DC (H)"}) == "Submitted to DC (H)"
    assert _submission_line({"submissionDate": "2031-03-04"}) == "Submitted on 04 Mar 2031"
    assert _submission_line({}) == ""


def test_the_uploaded_logo_reaches_the_cover():
    """Declared, uploaded, stored — and never resolved, so every cover carried no mark at all."""
    cover = _cover({"logo": "media-logo-1"})
    assert cover.logo is not None
    assert cover.logo.source == "media-logo-1"


def test_a_workshop_that_never_opened_stage_20_gets_the_cover_it_always_got():
    """The guarantee that lets this ship: silent where nobody asked."""
    cover = _cover({})
    assert cover.logo is None
    assert cover.org_lines[0] == "Government of India • Ministry of Textiles"
    assert not any(line.startswith("Submitted") for line in cover.footer_lines)
