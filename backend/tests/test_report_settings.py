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
    inert_section_toggles,
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


def test_turning_a_section_on_that_the_template_has_not_got_says_so():
    settings = {"includeCompletenessAnnexure": True, "includeMediaAnnexure": True}
    standard = get_template("DCH_STANDARD")
    assert not any(s.special is SpecialSection.COMPLETENESS for s in standard.sections)

    shaped = apply_report_settings(standard, settings)
    assert not any(s.special is SpecialSection.COMPLETENESS for s in shaped.sections), (
        "the toggle started INSERTING sections, which the Android port does not do"
    )

    said = inert_section_toggles(standard, settings)
    assert len(said) == 2
    joined = " ".join(said)
    assert "Include the completeness annexure" in joined
    assert "Include the photographic annexure" in joined
    assert standard.name in joined, "a designer must be told which template cannot carry it"

    assert inert_section_toggles(get_template(TEMPLATE_ID), settings) == ()
    assert inert_section_toggles(standard, {"includeCompletenessAnnexure": False}) == ()
    assert inert_section_toggles(standard, None) == ()


def test_excluded_stages_removes_exactly_those_stages():
    shaped = apply_report_settings(
        get_template(TEMPLATE_ID), {"excludedStages": ["CLUSTER_CRAFT_BACKGROUND"]}
    )
    keys = {s.stage_key for s in shaped.sections if s.stage_key}

    assert "CLUSTER_CRAFT_BACKGROUND" not in keys
    original = {s.stage_key for s in get_template(TEMPLATE_ID).sections if s.stage_key}
    assert keys == original - {"CLUSTER_CRAFT_BACKGROUND"}


def test_an_unknown_excluded_stage_is_ignored_rather_than_fatal():
    shaped = apply_report_settings(
        get_template(TEMPLATE_ID), {"excludedStages": ["NO_SUCH_STAGE", ""]}
    )
    assert len(shaped.sections) == len(get_template(TEMPLATE_ID).sections)


def test_number_headings_reaches_the_headings():
    def numbers(doc):
        return [b.number for b in doc.blocks if isinstance(b, HeadingBlock)]

    numbered = numbers(_build({"numberHeadings": True}))
    plain = numbers(_build({"numberHeadings": False}))

    assert any(numbered), "numberHeadings=true produced no numbered heading at all"
    assert not any(plain), "numberHeadings=false still numbered the headings"
    assert len(numbered) == len(plain)


def test_photo_columns_reaches_every_section():
    shaped = apply_report_settings(get_template(TEMPLATE_ID), {"photoColumns": 4})
    assert {s.photo_columns for s in shaped.sections} == {4}


def test_photo_columns_is_clamped_rather_than_trusted():
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
    base = get_template(TEMPLATE_ID)
    shaped = apply_report_settings(base, {"includePhotographs": True})
    assert [s.include_photos for s in shaped.sections] == [s.include_photos for s in base.sections]


def test_the_request_beats_the_saved_setting():
    shaped = apply_report_settings(
        get_template(TEMPLATE_ID), {"includePhotographs": True}, include_photographs=False
    )
    assert not any(s.include_photos for s in shaped.sections)


def test_absent_settings_leave_the_template_identical():
    base = get_template(TEMPLATE_ID)
    assert apply_report_settings(base, None) is base
    assert apply_report_settings(base, {}) is base


def test_a_setting_nobody_answered_changes_nothing():
    base = get_template(TEMPLATE_ID)
    shaped = apply_report_settings(base, {"numberHeadings": None, "includeTableOfContents": ""})

    assert shaped.number_headings == base.number_headings
    assert any(s.special is SpecialSection.TOC for s in shaped.sections)


def test_shaping_never_mutates_the_shared_template():
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


class _Record:
    id = "w1"
    title = "Design & Prototype Development Workshop"
    craftName = "Sambalpuri Bandha"
    clusterName = "Barpali"
    state = "Odisha"
    designerName = "Ananya Mohapatra"
    implementingAgency = "Sambalpuri Bastralaya"
    workshopCode = "SBB/DPDW/2025-26/BRP-04"


class _Resolver:
    def ref(self, media_id):
        return None

    def blob(self, image):
        return None

    def prefetch(self, wanted):
        return None


class _Options:
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
    from app.services.design_workshops import render_report

    blob, warnings, page_count = render_report(
        _data(None), TEMPLATE_ID, _Resolver(), _Record(), "DOCX", _Options()
    )

    assert blob[:2] == b"PK", "a .docx is a zip; this is the real file, not an error page"
    assert len(blob) > 1000
    assert isinstance(warnings, list)
    assert page_count is None, "page counting is a PDF-only concern"


def test_render_report_survives_a_page_size_it_does_not_recognise():
    from app.services.design_workshops import render_report

    class Odd(_Options):
        pageSize = "FOOLSCAP"

    blob, _, _ = render_report(_data(None), TEMPLATE_ID, _Resolver(), _Record(), "DOCX", Odd())
    assert blob[:2] == b"PK"


def test_render_report_needs_no_options_at_all():
    from app.services.design_workshops import render_report

    blob, _, _ = render_report(_data(None), TEMPLATE_ID, _Resolver(), _Record(), "DOCX", None)
    assert blob[:2] == b"PK"


def test_the_chosen_typeface_reaches_the_docx():
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
    from app.services.design_workshops import render_report

    for fmt in ("DOCX", "PDF"):
        _, warnings, _ = render_report(
            _data(None), TEMPLATE_ID, _Resolver(), _Record(), fmt, _Options()
        )
        assert not any("typeface" in w for w in warnings), fmt


def test_an_unknown_typeface_is_ignored_rather_than_guessed():
    from app.services.report_theme import resolve_font

    assert resolve_font("FRAKTUR", {}) is None
    assert resolve_font(None, {"fontPreset": "FRAKTUR"}) is None


def test_a_missing_map_is_not_reported_as_a_missing_photograph():
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
    assert "2 figures could not be drawn" in mixed, "a chart placed by either path is a figure, not a photograph"
    assert "1 photograph could not be included" in mixed, "and a real media id must still be reported as one"


def test_a_photograph_that_did_not_arrive_is_still_called_a_photograph():
    from app.services.design_workshops import _dropped_warnings

    said = _dropped_warnings(["media-1", "media-2"])
    assert said == ["2 photographs could not be included in the file."]


def test_the_stage_20_template_picker_actually_chooses_the_template():
    from app.services.design_workshops import resolve_template_id

    class Record:
        templateId = "DCH_STANDARD"

    assert resolve_template_id(None, {"templateId": "PHOTO_CATALOGUE"}, Record()) == \
        "PHOTO_CATALOGUE"
    assert resolve_template_id("COMPACT_SUMMARY", {"templateId": "PHOTO_CATALOGUE"}, Record()) == \
        "COMPACT_SUMMARY"
    assert resolve_template_id(None, {}, Record()) == "DCH_STANDARD"
    assert resolve_template_id(None, None, Record()) == "DCH_STANDARD"


def test_a_template_id_this_build_does_not_know_is_ignored_rather_than_obeyed():
    from app.services.design_workshops import resolve_template_id

    class Record:
        templateId = "PHOTO_CATALOGUE"

    assert resolve_template_id(None, {"templateId": "FROM_A_NEWER_CLIENT"}, Record()) == \
        "PHOTO_CATALOGUE"
    assert resolve_template_id("ALSO_UNKNOWN", {}, Record()) == "PHOTO_CATALOGUE"
    assert resolve_template_id(None, {"templateId": "  "}, Record()) == "PHOTO_CATALOGUE"



def _cover(settings: dict, **data_kw):
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
    cover = _cover({"organisationLine": "Sambalpuri Weavers Cooperative, Barpali"})
    assert any("Sambalpuri Weavers Cooperative" in line for line in cover.org_lines)


def test_the_letterhead_reaches_the_cover_and_is_bounded():
    cover = _cover({"letterheadText": "\n".join(f"Line {i}" for i in range(1, 10))})
    lines = [line for line in cover.org_lines if line.startswith("Line ")]
    assert lines[:3] == ["Line 1", "Line 2", "Line 3"]
    assert len(lines) <= 6


def test_the_addressee_and_the_submission_date_are_on_the_cover():
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
    cover = _cover({"logo": "media-logo-1"})
    assert cover.logo is not None
    assert cover.logo.source == "media-logo-1"


def test_a_workshop_that_never_opened_stage_20_gets_the_cover_it_always_got():
    cover = _cover({})
    assert cover.logo is None
    assert cover.org_lines[0] == "Government of India • Ministry of Textiles"
    assert not any(line.startswith("Submitted") for line in cover.footer_lines)



def test_asking_for_two_formats_is_refused_rather_than_half_served():
    import pytest
    from pydantic import ValidationError

    from app.schemas.design_workshops import ReportGenerateIn

    with pytest.raises(ValidationError) as raised:
        ReportGenerateIn(formats=["DOCX", "PDF"])
    assert "Choose one format at a time" in str(raised.value)

    assert ReportGenerateIn(formats=["PDF"]).formats == ["PDF"]
    assert ReportGenerateIn(formats=["docx"]).formats == ["DOCX"]
    assert ReportGenerateIn(formats=["DOCX", "docx"]).formats == ["DOCX"]
    assert ReportGenerateIn().formats == ["DOCX"], "the default is still one .docx"
