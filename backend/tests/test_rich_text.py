"""The rich-text document: the round trip, the sanitising, and the mapping into report blocks.

Every assertion here is about a way formatted prose could be silently lost or silently corrupted
between the editor a designer types into and the .docx an officer opens. The module's own
docstring explains why the storage form is a structured document rather than HTML; these tests
are what stop somebody deciding otherwise later.
"""

import pytest

from app.services.report_model import (
    Align,
    BulletListBlock,
    HeadingBlock,
    ImageRef,
    ParagraphBlock,
    ParaStyle,
    Script,
)
from app.services.rich_text import (
    EMPTY,
    MAX_BLOCKS,
    MAX_DOCUMENT_CHARS,
    BlockKind,
    Mark,
    RichBlock,
    RichDoc,
    RichSpan,
    from_json,
    from_plain,
    is_empty,
    plain_runs,
    summary,
    to_json,
    to_plain,
    to_preview_json,
    to_report_blocks,
)


def _doc(*blocks: RichBlock) -> RichDoc:
    return RichDoc(blocks=tuple(blocks))


def _para(text: str, *marks: Mark, align: Align = Align.LEFT) -> RichBlock:
    return RichBlock(spans=(RichSpan(text, frozenset(marks)),), align=align)


# --------------------------------------------------------------------------------------
# Round trip
# --------------------------------------------------------------------------------------


def test_a_document_survives_a_json_round_trip_exactly():
    original = _doc(
        RichBlock(kind=BlockKind.HEADING, level=2, spans=(RichSpan("Findings"),)),
        RichBlock(spans=(
            RichSpan("The cluster "),
            RichSpan("cannot", frozenset({Mark.BOLD, Mark.UNDERLINE})),
            RichSpan(" absorb the order."),
        ), align=Align.JUSTIFY),
        RichBlock(kind=BlockKind.BULLET_ITEM, spans=(RichSpan("Yarn supply"),)),
        RichBlock(kind=BlockKind.ORDERED_ITEM, level=1, spans=(RichSpan("Hold a clinic"),)),
        RichBlock(kind=BlockKind.QUOTE, spans=(RichSpan("It is quicker to tie.", frozenset({Mark.ITALIC})),)),
    )
    assert from_json(to_json(original)) == original


def test_json_omits_defaults_to_keep_the_column_small():
    payload = to_json(_doc(_para("plain")))
    block = payload["blocks"][0]
    assert "align" not in block and "level" not in block
    assert block["spans"] == [{"text": "plain"}]


def test_every_mark_survives():
    for mark in Mark:
        doc = _doc(_para("x", mark))
        assert from_json(to_json(doc)).blocks[0].spans[0].marks == frozenset({mark})


# --------------------------------------------------------------------------------------
# Parsing is forgiving, because three clients write these
# --------------------------------------------------------------------------------------


def test_a_plain_string_reads_as_an_unformatted_document():
    """THE MIGRATION CASE. Promoting a field from LONG_TEXT to RICH_TEXT must not blank the prose
    already stored under it."""
    doc = from_json("First line.\n\nSecond line.")
    assert [b.text for b in doc.blocks] == ["First line.", "Second line."]
    assert all(b.kind is BlockKind.PARAGRAPH for b in doc.blocks)


def test_a_bare_block_list_is_accepted():
    doc = from_json([{"kind": "PARAGRAPH", "spans": [{"text": "hi"}]}])
    assert doc.blocks[0].text == "hi"


def test_a_bare_string_inside_the_block_list_becomes_a_paragraph():
    assert from_json({"blocks": ["loose"]}).blocks[0].text == "loose"


def test_nothing_shaped_like_a_document_produces_an_empty_one():
    for junk in (None, {}, {"blocks": None}, {"blocks": []}):
        assert from_json(junk).is_empty


def test_a_scalar_is_preserved_as_text_rather_than_discarded():
    """Forgiving on purpose, and the same rule ``clean_text`` follows.

    A number where a document was expected is a client bug, but the value is still something a
    designer typed, and silently dropping it loses data to fix a formatting mistake. It reads as
    an unformatted paragraph, which is exactly what a LONG_TEXT field would have stored.
    """
    assert from_json(17).blocks[0].text == "17"


def test_an_unknown_mark_is_dropped_not_fatal():
    """A phone one release ahead may apply a mark this server has never heard of. Losing the mark
    is cosmetic; losing the paragraph is data loss."""
    doc = from_json({"blocks": [{"kind": "PARAGRAPH", "spans": [
        {"text": "kept", "marks": ["BOLD", "SPARKLES"]}]}]})
    assert doc.blocks[0].spans[0].marks == frozenset({Mark.BOLD})
    assert doc.blocks[0].text == "kept"


def test_an_unknown_block_kind_degrades_to_a_paragraph():
    doc = from_json({"blocks": [{"kind": "CAROUSEL", "spans": [{"text": "x"}]}]})
    assert doc.blocks[0].kind is BlockKind.PARAGRAPH


def test_an_unknown_alignment_degrades_to_left():
    doc = from_json({"blocks": [{"kind": "PARAGRAPH", "align": "SIDEWAYS",
                                 "spans": [{"text": "x"}]}]})
    assert doc.blocks[0].align is Align.LEFT


def test_levels_are_clamped_to_their_legal_range():
    heading = from_json({"blocks": [{"kind": "HEADING", "level": 99, "spans": [{"text": "h"}]}]})
    assert heading.blocks[0].level == 4
    item = from_json({"blocks": [{"kind": "BULLET_ITEM", "level": 99, "spans": [{"text": "i"}]}]})
    assert item.blocks[0].level == 3
    # A heading with no level is a level 1, not a level 0 that no renderer has a style for.
    bare = from_json({"blocks": [{"kind": "HEADING", "spans": [{"text": "h"}]}]})
    assert bare.blocks[0].level == 1


def test_a_paragraph_never_keeps_a_level():
    doc = from_json({"blocks": [{"kind": "PARAGRAPH", "level": 3, "spans": [{"text": "x"}]}]})
    assert doc.blocks[0].level == 0


# --------------------------------------------------------------------------------------
# Sanitising — the single door still applies
# --------------------------------------------------------------------------------------


def test_codepoints_xml_cannot_carry_are_stripped():
    """A lone surrogate makes word/document.xml not well-formed, and Word refuses the whole file
    rather than dropping the character."""
    doc = from_json({"blocks": [{"kind": "PARAGRAPH",
                                 "spans": [{"text": "a" + chr(0xD83D) + chr(0x07) + "b"}]}]})
    assert doc.blocks[0].text == "ab"


def test_an_astral_character_survives():
    doc = from_json({"blocks": [{"kind": "PARAGRAPH", "spans": [{"text": "thread \U0001F9F5"}]}]})
    assert doc.blocks[0].text == "thread \U0001F9F5"


def test_a_newline_inside_a_span_becomes_a_space():
    """One block must render as one paragraph. A newline smuggled into a span would make it two
    in some renderers and one in others."""
    doc = from_json({"blocks": [{"kind": "PARAGRAPH", "spans": [{"text": "a\nb"}]}]})
    assert doc.blocks[0].text == "a b"


def test_an_oversized_document_is_truncated_rather_than_accepted():
    huge = {"blocks": [{"kind": "PARAGRAPH", "spans": [{"text": "x" * 50_000}]}
                       for _ in range(20)]}
    doc = from_json(huge)
    # The budget bounds the CONTENT. `doc.text` additionally joins blocks with newlines, so it is
    # a handful of separator characters longer — bounding the separators too would mean a
    # paragraph count changing the character budget, which is the wrong thing to make depend on
    # the wrong thing.
    content = sum(len(span.text) for block in doc.blocks for span in block.spans)
    assert content <= MAX_DOCUMENT_CHARS
    assert len(doc.text) <= MAX_DOCUMENT_CHARS + len(doc.blocks)


def test_the_block_count_is_bounded():
    doc = from_json({"blocks": [{"kind": "PARAGRAPH", "spans": [{"text": "x"}]}
                                for _ in range(MAX_BLOCKS + 500)]})
    assert len(doc.blocks) <= MAX_BLOCKS


# --------------------------------------------------------------------------------------
# Emptiness — what the completeness gate reads
# --------------------------------------------------------------------------------------


def test_an_editor_that_was_focused_and_left_alone_is_empty():
    """It still saves a document with one empty paragraph. Counting that as a filled required
    field would let a designer submit a report whose introduction is blank at 100%."""
    assert is_empty({"blocks": [{"kind": "PARAGRAPH", "spans": []}]})
    assert is_empty({"blocks": [{"kind": "PARAGRAPH", "spans": [{"text": "   "}]}]})
    assert is_empty(None)
    assert is_empty("")
    assert not is_empty({"blocks": [{"kind": "PARAGRAPH", "spans": [{"text": "real"}]}]})


def test_the_registry_treats_a_blank_rich_document_as_unfilled():
    from app.services.stage_schema import _is_filled

    assert not _is_filled({"blocks": [{"kind": "PARAGRAPH", "spans": []}]})
    assert _is_filled({"blocks": [{"kind": "PARAGRAPH", "spans": [{"text": "real"}]}]})
    # A non-rich dict — a GEO value — is still judged on being non-empty.
    assert _is_filled({"lat": 21.3, "lon": 83.6})


def test_coercion_normalises_through_the_model():
    """What the client sent is never what is stored: its editor state, its selection and any mark
    from a newer build are dropped at the door rather than three months later in a renderer."""
    from app.services.stage_schema import FieldSpec, FieldType, coerce_value

    spec = FieldSpec(key="intro", label="Introduction", type=FieldType.RICH_TEXT)
    value, error = coerce_value(spec, {
        "blocks": [{"kind": "PARAGRAPH", "spans": [{"text": "hi", "marks": ["BOLD", "NOPE"]}]}],
        "selection": {"anchor": 3},
    })
    assert error is None
    assert value == {"blocks": [{"kind": "PARAGRAPH", "spans": [{"text": "hi", "marks": ["BOLD"]}]}]}

    blank, error = coerce_value(spec, {"blocks": [{"kind": "PARAGRAPH", "spans": []}]})
    assert blank is None and error is None


# --------------------------------------------------------------------------------------
# Plain text
# --------------------------------------------------------------------------------------


def test_plain_text_keeps_list_markers():
    """This string is what a CSV export and a search index receive. A bulleted list flattened to
    bare lines reads as one run-on sentence."""
    doc = _doc(
        _para("Recommendations"),
        RichBlock(kind=BlockKind.BULLET_ITEM, spans=(RichSpan("Hold a clinic"),)),
        RichBlock(kind=BlockKind.ORDERED_ITEM, spans=(RichSpan("First"),)),
        RichBlock(kind=BlockKind.ORDERED_ITEM, spans=(RichSpan("Second"),)),
    )
    assert to_plain(doc) == "Recommendations\n• Hold a clinic\n1. First\n2. Second"


def test_ordered_numbering_restarts_after_a_paragraph():
    doc = _doc(
        RichBlock(kind=BlockKind.ORDERED_ITEM, spans=(RichSpan("a"),)),
        _para("interruption"),
        RichBlock(kind=BlockKind.ORDERED_ITEM, spans=(RichSpan("b"),)),
    )
    assert to_plain(doc).endswith("1. b")


def test_summary_is_one_line_and_bounded():
    doc = from_plain("A long introduction. " * 40)
    text = summary(doc, limit=60)
    assert "\n" not in text and len(text) <= 60


# --------------------------------------------------------------------------------------
# Into the report
# --------------------------------------------------------------------------------------


def test_consecutive_list_items_merge_into_one_block():
    """A .docx list is a run of paragraphs sharing a numbering id. One block per item restarts the
    numbering at every line — a five-point recommendation printed as "1. 1. 1. 1. 1."."""
    doc = _doc(
        RichBlock(kind=BlockKind.ORDERED_ITEM, spans=(RichSpan("one"),)),
        RichBlock(kind=BlockKind.ORDERED_ITEM, spans=(RichSpan("two"),)),
        RichBlock(kind=BlockKind.ORDERED_ITEM, spans=(RichSpan("three"),)),
    )
    blocks = to_report_blocks(to_json(doc))
    assert len(blocks) == 1
    assert isinstance(blocks[0], BulletListBlock)
    assert blocks[0].ordered is True
    assert len(blocks[0].items) == 3


def test_switching_list_kind_starts_a_new_block():
    doc = _doc(
        RichBlock(kind=BlockKind.BULLET_ITEM, spans=(RichSpan("a"),)),
        RichBlock(kind=BlockKind.ORDERED_ITEM, spans=(RichSpan("b"),)),
    )
    blocks = to_report_blocks(to_json(doc))
    assert [b.ordered for b in blocks] == [False, True]


def test_marks_reach_the_report_runs():
    doc = _doc(RichBlock(spans=(
        RichSpan("plain "),
        RichSpan("bold", frozenset({Mark.BOLD})),
        RichSpan(" and "),
        RichSpan("under", frozenset({Mark.UNDERLINE})),
    )))
    block = to_report_blocks(to_json(doc))[0]
    by_text = {r.text.strip(): r for r in block.runs}
    assert by_text["bold"].bold and not by_text["bold"].underline
    assert by_text["under"].underline and not by_text["under"].bold


def test_a_span_mixing_scripts_becomes_two_runs_keeping_its_marks():
    """A designer who bolds a phrase containing English and Odia has produced one span that must
    become two runs, or half of it renders as boxes."""
    doc = _doc(RichBlock(spans=(RichSpan("Ikat ସମ", frozenset({Mark.BOLD})),)))
    runs = to_report_blocks(to_json(doc))[0].runs
    assert len(runs) == 2
    assert {r.script for r in runs} == {Script.LATIN, Script.ODIA}
    assert all(r.bold for r in runs)


def test_alignment_reaches_the_paragraph():
    block = to_report_blocks(to_json(_doc(_para("centred", align=Align.CENTER))))[0]
    assert isinstance(block, ParagraphBlock) and block.align is Align.CENTER


def test_a_heading_becomes_a_heading_block_with_a_legal_bookmark():
    doc = _doc(RichBlock(kind=BlockKind.HEADING, level=3, spans=(RichSpan("Detail"),)))
    block = to_report_blocks(to_json(doc))[0]
    assert isinstance(block, HeadingBlock) and block.level == 3
    assert not block.bookmark[0].isdigit() and len(block.bookmark) <= 40


def test_a_quote_becomes_a_quote_styled_paragraph():
    doc = _doc(RichBlock(kind=BlockKind.QUOTE, spans=(RichSpan("said the weaver"),)))
    assert to_report_blocks(to_json(doc))[0].style is ParaStyle.QUOTE


def test_empty_blocks_are_dropped_and_do_not_split_a_list():
    doc = _doc(
        RichBlock(kind=BlockKind.BULLET_ITEM, spans=(RichSpan("a"),)),
        _para("   "),
        RichBlock(kind=BlockKind.BULLET_ITEM, spans=(RichSpan("b"),)),
    )
    blocks = to_report_blocks(to_json(doc))
    assert len(blocks) == 1 and len(blocks[0].items) == 2


def test_an_empty_document_renders_no_blocks():
    assert to_report_blocks(None) == []
    assert to_report_blocks(to_json(EMPTY)) == []


def test_plain_runs_flattens_for_a_table_cell():
    """A cell holds runs, not blocks. The structure is deliberately lost; the marks are not."""
    doc = _doc(
        _para("Line one"),
        RichBlock(kind=BlockKind.BULLET_ITEM, spans=(RichSpan("point", frozenset({Mark.BOLD})),)),
    )
    runs = plain_runs(to_json(doc))
    text = "".join(r.text for r in runs)
    assert "Line one" in text and "•" in text and "point" in text
    assert any(r.bold for r in runs)


def test_plain_runs_never_returns_empty():
    """A renderer given an empty run tuple draws nothing and the cell collapses; report_docx
    requires at least one block-level element in a w:tc."""
    assert plain_runs(None) is not None
    assert isinstance(plain_runs(None), tuple)


# --------------------------------------------------------------------------------------
# The preview projection
# --------------------------------------------------------------------------------------


def test_preview_resolves_marks_to_booleans():
    """The browser must never have to interpret the mark vocabulary itself — that is the one place
    a fifth renderer could start to drift."""
    doc = _doc(RichBlock(spans=(RichSpan("x", frozenset({Mark.BOLD, Mark.STRIKETHROUGH})),)))
    span = to_preview_json(to_json(doc))[0]["spans"][0]
    assert span["bold"] is True and span["strike"] is True
    assert span["italic"] is False and span["underline"] is False


# --------------------------------------------------------------------------------------
# Raised and lowered text
#
# The rule these pin is the one that lets five renderers agree without any of them thinking: a span
# carrying both marks is resolved HERE, once, in favour of superscript. `<w:vertAlign>` takes one
# value and emitting it twice is schema-invalid, so a writer handed an ambiguous run would either
# produce a file Word refuses or silently pick a side — and two writers picking different sides is
# the same document printing "m²" on one surface and "m₂" on the other.
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("mark,expected", [
    (Mark.SUPERSCRIPT, "superscript"),
    (Mark.SUBSCRIPT, "subscript"),
])
def test_a_vertical_mark_reaches_the_report_run(mark, expected):
    doc = _doc(RichBlock(spans=(RichSpan("2", frozenset({mark})),)))
    run = to_report_blocks(to_json(doc))[0].runs[0]
    assert getattr(run, expected) is True
    # And nothing else moved: a run that is raised is not also lowered.
    assert getattr(run, "subscript" if expected == "superscript" else "superscript") is False


def test_a_span_claiming_both_vertical_marks_resolves_to_superscript():
    """A hand-written or migrated value can say both; no editor here can produce one. Resolving it
    in the model rather than in each writer is what stops the .docx and the two PDFs disagreeing."""
    doc = _doc(RichBlock(spans=(RichSpan("x", frozenset({Mark.SUPERSCRIPT, Mark.SUBSCRIPT})),)))
    run = to_report_blocks(to_json(doc))[0].runs[0]
    assert run.superscript is True
    assert run.subscript is False
    # The preview is handed the same resolved pair, so the browser never has to know the rule.
    span = to_preview_json(to_json(doc))[0]["spans"][0]
    assert span["superscript"] is True and span["subscript"] is False


def test_a_superscript_reaches_the_generated_docx_and_the_generated_pdf():
    """The whole point of the mark, asserted on the FILES rather than on the model.

    A mark that exists in the document and not in the file is a control whose result vanishes
    between the screen a designer approved and the copy a ministry receives — which is the failure
    every rule in `rich_text.py` is arranged around. The .docx is checked on its XML, because
    ``w:vertAlign`` either is in the run's properties or the run prints on the baseline. The .pdf
    can only be checked for having been produced: a raised glyph is a drawing operation, not an
    attribute, so there is nothing in the bytes to name — but a renderer that could not carry the
    flag would raise here rather than draw it flat, which is the failure worth catching.
    """
    import zipfile
    from io import BytesIO

    from app.services.report_docx import render_docx
    from app.services.report_model import DocumentBuilder, ReportMeta
    from app.services.report_pdf import render_pdf

    doc = _doc(RichBlock(spans=(
        RichSpan("Handloom, 4.5 m"),
        RichSpan("2", frozenset({Mark.SUPERSCRIPT})),
        RichSpan(" of it, dyed in H"),
        RichSpan("2", frozenset({Mark.SUBSCRIPT})),
        RichSpan("O, "),
        RichSpan("check this figure", frozenset({Mark.HIGHLIGHT})),
        RichSpan("."),
    )))

    def _document():
        builder = DocumentBuilder(meta=ReportMeta(title="v", generated_at="2026-01-01T00:00:00Z"))
        for block in to_report_blocks(to_json(doc)):
            builder.add(block)
        return builder.build()

    data, _dropped = render_docx(_document(), lambda _ref: None)
    xml = zipfile.ZipFile(BytesIO(data)).read("word/document.xml").decode()
    assert '<w:vertAlign w:val="superscript"/>' in xml
    assert '<w:vertAlign w:val="subscript"/>' in xml
    # A NAME and not a hex, because ST_HighlightColor is a closed enumeration; a writer that put
    # "FFFF00" here would produce a run Word silently drops the formatting of.
    assert '<w:highlight w:val="yellow"/>' in xml

    pdf, _missing = render_pdf(_document(), lambda _ref: None)
    assert pdf.startswith(b"%PDF")


def test_a_highlight_reaches_the_report_run_and_the_preview():
    doc = _doc(RichBlock(spans=(RichSpan("check this", frozenset({Mark.HIGHLIGHT})),)))
    assert to_report_blocks(to_json(doc))[0].runs[0].highlight is True
    assert to_preview_json(to_json(doc))[0]["spans"][0]["highlight"] is True


def test_a_vertical_mark_survives_the_json_round_trip():
    original = _doc(RichBlock(spans=(
        RichSpan("H"),
        RichSpan("2", frozenset({Mark.SUBSCRIPT})),
        RichSpan("O"),
    )))
    assert from_json(to_json(original)) == original


# --------------------------------------------------------------------------------------
# Inline photographs
# --------------------------------------------------------------------------------------


def test_an_image_block_round_trips_with_its_id_and_width():
    original = _doc(RichBlock(kind=BlockKind.IMAGE, media="med_9", width_pct=45.0,
                              spans=(RichSpan("Detail of the seam"),)))
    assert from_json(to_json(original)) == original


def test_an_image_with_no_media_id_is_dropped():
    """Both parsers drop it, so keeping it on either side would put a block into the document that
    prints nothing and still answers "filled" to the completeness gate."""
    assert from_json({"blocks": [{"kind": "IMAGE", "spans": [{"text": "orphan caption"}]}]}) == EMPTY


def test_an_image_is_filled_even_with_no_caption():
    """The gate reads `is_empty`. Judging a photograph on its text would tell a designer they had
    left a required narrative blank while a picture of the loom sat in it."""
    doc = _doc(RichBlock(kind=BlockKind.IMAGE, media="med_9"))
    assert doc.is_empty is False
    assert is_empty(to_json(doc)) is False


@pytest.mark.parametrize("kind", list(BlockKind))
def test_every_block_kind_is_renderable_by_every_consumer(kind):
    """A kind the report builder cannot map is a paragraph that silently disappears."""
    # TABLE is the one kind whose content is NOT in `spans` — it lives in `rows`, because a grid
    # is not a run of text. Building it like the others produces a table with no cells, which
    # correctly renders nothing, and the assertion below would then be testing the fixture rather
    # than the renderer.
    if kind is BlockKind.TABLE:
        block = RichBlock(kind=kind, rows=(((RichSpan("Head"),), (RichSpan("Amount"),)),
                                           ((RichSpan("Yarn"),), (RichSpan("242.50"),))))
    elif kind is BlockKind.IMAGE:
        # An IMAGE's content is a media ID, not text, and it renders only when a resolver can turn
        # that id into an ImageRef — so the fixture supplies both. Building it like the others
        # produces a block with no media, which is correctly dropped, and the assertion below would
        # then be testing the fixture rather than the renderer.
        block = RichBlock(kind=kind, media="med_1", spans=(RichSpan("Loom, day 4"),))
    else:
        block = RichBlock(kind=kind, level=1, spans=(RichSpan("content"),))

    payload = to_json(_doc(block))
    resolve = (lambda _id: ImageRef(source="med_1", width_px=800, height_px=600,
                                    mime_type="image/jpeg")) if kind is BlockKind.IMAGE else None
    assert to_report_blocks(payload, resolve_media=resolve),         f"{kind.value} produced no report block"
    assert to_plain(payload).strip()
    assert to_preview_json(payload)
