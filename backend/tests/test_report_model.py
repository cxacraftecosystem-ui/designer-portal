"""The two invariants report_model exists to enforce: the single door, and relative sizes.

Every assertion here corresponds to a way a generated report has broken or could break. If one
of these fails, some caller has started building blocks by hand instead of through the
constructors, which is exactly the drift the module's docstring warns about.
"""

import pytest

from app.services.report_model import (
    Align,
    DocumentBuilder,
    ImageRef,
    ParaStyle,
    ReportMeta,
    Script,
    TableBlock,
    TableColumn,
    clean_text,
    collect_images,
    dominant_script,
    runs_of,
    runs_text,
    split_by_script,
)

# --------------------------------------------------------------------------------------
# clean_text: the single door
# --------------------------------------------------------------------------------------


def test_clean_text_drops_lone_surrogate():
    """A phone that cut an emoji in half produces one of these; XML admits no such codepoint."""
    assert clean_text("ab" + chr(0xD83D) + "cd") == "abcd"


def test_clean_text_drops_c0_controls_but_keeps_tab_and_newline():
    assert clean_text("a" + chr(0x07) + "b") == "ab"
    assert clean_text("a\tb\nc") == "a\tb\nc"


def test_clean_text_normalises_line_endings():
    assert clean_text("a\r\nb\rc" + chr(0x2028) + "d") == "a\nb\nc\nd"


def test_clean_text_keeps_astral_plane():
    """Emoji above the BMP are legal XML and appear in real field notes."""
    assert clean_text("thread \U0001F9F5") == "thread \U0001F9F5"


def test_clean_text_none_is_empty_not_the_word_none():
    """An unfilled optional field must leave a blank cell, never print "None"."""
    assert clean_text(None) == ""


def test_clean_text_bool_reads_as_yes_no():
    """bool is an int subclass; str() would put "True" into a government report."""
    assert clean_text(True) == "Yes"
    assert clean_text(False) == "No"


def test_clean_text_numbers_stringify():
    assert clean_text(0) == "0"
    assert clean_text(3.5) == "3.5"


# --------------------------------------------------------------------------------------
# Script detection: the reason craft names are not boxes
# --------------------------------------------------------------------------------------


def test_split_by_script_separates_latin_from_indic():
    spans = split_by_script("Ikat (ସମ୍ବଲପୁରୀ) weave")
    assert [s for _text, s in spans] == [Script.LATIN, Script.ODIA, Script.LATIN]
    assert "".join(t for t, _ in spans) == "Ikat (ସମ୍ବଲପୁରୀ) weave"


def test_split_by_script_keeps_indic_digits_with_their_script():
    """Devanagari digits are category Nd but live in the Devanagari block; handing them to a
    neighbouring Latin run prints them as tofu."""
    spans = split_by_script("कपड़ा ०१२ cloth")
    assert spans[0][1] is Script.DEVANAGARI
    assert "०१२" in spans[0][0]


def test_split_by_script_back_fills_leading_punctuation():
    spans = split_by_script("(ସମ୍ବଲପୁରୀ) x")
    assert spans[0][1] is Script.ODIA


def test_split_by_script_does_not_split_on_currency_or_digits():
    assert len(split_by_script("Rs 1,250.00")) == 1


def test_split_by_script_empty():
    assert split_by_script("") == []


def test_dominant_script_ignores_latin_padding():
    assert dominant_script("Ikat (ସମ୍ବଲପୁରୀ)") is Script.ODIA
    assert dominant_script("Ikat") is Script.LATIN


def test_runs_of_cleans_and_splits():
    runs = runs_of("Ikat " + chr(0x07) + "(ସମ୍ବଲପୁରୀ)", bold=True)
    assert all(r.bold for r in runs)
    assert runs_text(runs) == "Ikat (ସମ୍ବଲପୁରୀ)"


def test_runs_of_blank_is_no_runs():
    assert runs_of("") == ()
    assert runs_of(None) == ()


# --------------------------------------------------------------------------------------
# Relative sizing
# --------------------------------------------------------------------------------------


def test_table_column_widths_must_sum_to_100():
    with pytest.raises(ValueError, match="sum to 100"):
        TableBlock(columns=(TableColumn("a", 60.0), TableColumn("b", 58.0)), rows=())


def test_table_column_widths_accept_rounding_slack():
    TableBlock(
        columns=(TableColumn("a", 33.3), TableColumn("b", 33.3), TableColumn("c", 33.4)),
        rows=(),
    )


def test_table_with_no_columns_is_allowed():
    """A stage whose collection is empty renders an empty table rather than raising."""
    TableBlock(columns=(), rows=())


# --------------------------------------------------------------------------------------
# ImageRef geometry
# --------------------------------------------------------------------------------------


def test_image_rotation_swaps_display_dimensions():
    portrait = ImageRef("x", width_px=480, height_px=640, rotation_deg=90)
    assert portrait.display_width_px == 640
    assert portrait.display_height_px == 480


def test_image_aspect_never_zero_or_raising():
    """An unknown-size photo must still occupy a sane box; a zero would divide-by-zero in
    both renderers' fit calculations."""
    assert ImageRef("x").aspect == pytest.approx(4 / 3)
    assert ImageRef("x", width_px=0, height_px=100).aspect == pytest.approx(4 / 3)


# --------------------------------------------------------------------------------------
# DocumentBuilder
# --------------------------------------------------------------------------------------


def _builder() -> DocumentBuilder:
    return DocumentBuilder(meta=ReportMeta(title="T"))


def test_heading_numbers_reset_hierarchically():
    b = _builder()
    b.heading("A", 1)
    b.heading("A1", 2)
    b.heading("A2", 2)
    b.heading("A2a", 3)
    b.heading("B", 1)
    assert [blk.number for blk in b._blocks] == ["1", "1.1", "1.2", "1.2.1", "2"]


def test_heading_bookmarks_are_unique_and_word_legal():
    b = _builder()
    b.heading("Materials", 2)
    b.heading("Materials", 2)
    marks = [blk.bookmark for blk in b._blocks]
    assert len(set(marks)) == 2
    for m in marks:
        assert not m[0].isdigit()
        assert len(m) <= 40
        assert all(c.isalnum() or c == "_" for c in m)


def test_unnumbered_heading_has_no_number():
    b = _builder()
    b.heading("Annexure", 1, numbered=False)
    assert b._blocks[0].number == ""


def test_para_skips_blank_values():
    """An optional Standard-tier field left empty must not leave a blank line in the report."""
    b = _builder()
    b.para("")
    b.para("   ")
    b.para(None)
    assert b._blocks == []


def test_para_splits_on_blank_line():
    b = _builder()
    b.para("one\n\ntwo")
    assert len(b._blocks) == 2
    assert runs_text(b._blocks[1].runs) == "two"


def test_bullets_drop_empty_items_and_whole_block_when_all_empty():
    b = _builder()
    b.bullets(["a", "", None, "b"])
    assert len(b._blocks[0].items) == 2
    b2 = _builder()
    b2.bullets(["", None])
    assert b2._blocks == []


def test_key_values_skip_empty_pairs_by_default():
    b = _builder()
    b.key_values([("Craft", "Ikat"), ("GI status", None), ("Cluster", "")])
    assert len(b._blocks[0].pairs) == 1


def test_key_values_can_keep_empties():
    b = _builder()
    b.key_values([("Craft", "Ikat"), ("GI status", None)], skip_empty=False)
    assert len(b._blocks[0].pairs) == 2


def test_build_freezes_and_collects_images():
    b = _builder()
    from app.services.report_model import ImageBlock, ImageGridBlock

    shared = ImageRef("same", 100, 100)
    b.add(ImageBlock(image=shared))
    b.add(ImageGridBlock(images=((shared, "a"), (ImageRef("other", 10, 10), "b"))))
    doc = b.build()
    assert isinstance(doc.blocks, tuple)
    # Deduplicated by source, in first-use order: a photo reused in an annexure must not be
    # embedded twice.
    assert [i.source for i in doc.images] == ["same", "other"]


def test_collect_images_includes_cover_art():
    from app.services.report_model import CoverBlock

    cover = CoverBlock(title="t", logo=ImageRef("logo"), hero_image=ImageRef("hero"))
    assert [i.source for i in collect_images((cover,))] == ["logo", "hero"]


def test_builder_warnings_are_cleaned():
    b = _builder()
    b.warn("bad" + chr(0x07) + " photo")
    assert b.build().warnings == ("bad photo",)


def test_para_style_and_align_pass_through():
    b = _builder()
    b.para("x", style=ParaStyle.QUOTE, align=Align.CENTER)
    assert b._blocks[0].style is ParaStyle.QUOTE
    assert b._blocks[0].align is Align.CENTER
