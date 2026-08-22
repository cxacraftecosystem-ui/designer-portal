"""Where the server PDF puts things on the page, measured on the FILE and not on the renderer.

Every defect this module was opened for produces a PDF that opens perfectly, raises nothing, and
is wrong in a way only a reader holding the paper can see:

* a table cell longer than the page whose remaining lines were drawn at a NEGATIVE y — outside
  the sheet, while the .docx generated in the same download kept every word;
* a heading that passed its own keepNext check and then walked past the bottom margin;
* a figure title left at the foot of one page and the chart it names moved to the next;
* a contents entry and a running head drawn straight off the edge of the paper.

None of them is visible from any assertion about the model, and none of them is visible from the
extracted TEXT either — the words are all in the file, they are merely not on the page. So the
tests read the drawing operators' COORDINATES back out with ``pypdf``'s text visitor and assert
about geometry: nothing below y=0, nothing past the trim edge, nothing alone at the foot.

``extract_text`` returns one word per piece here, because ``_draw_line`` draws every wrapped token
with its own ``drawString``. That is what makes a per-piece coordinate available at all.
"""

from io import BytesIO

import pytest
from pypdf import PdfReader
from reportlab.pdfbase import pdfmetrics

from app.services.report_model import (
    ChartBlock,
    ChartKind,
    CoverBlock,
    DocumentBuilder,
    HeadingBlock,
    KeyValueBlock,
    ParagraphBlock,
    ReportMeta,
    TableBlock,
    TableColumn,
    runs_of,
)
from app.services.report_pdf import render_pdf

#: One LONG_TEXT answer of the size the registry permits. All thirty LONG_TEXT registry fields
#: carry ``max_length == 0``, so nothing upstream stops a designer writing one this long — and at
#: roughly 2,600 characters in a single cell the row stops fitting on an A4 page.
LONG_ANSWER = "The designer wrote a very long answer in a single long-text field. " * 90


def _meta(**kw) -> ReportMeta:
    base = {"title": "Workshop", "generated_at": "2026-01-01T00:00:00Z"}
    base.update(kw)
    return ReportMeta(**base)


#: The default page's geometry, in points. ``ReportMeta.margin_mm`` is 25 and ``PdfRenderer``
#: holds a further 10 mm below it for the running foot, so ``TEXT_FLOOR`` is the bottom of the
#: text column: every BODY baseline is above it and every piece of running furniture is outside
#: it — the foot and the page number below, the running head above ``MARGIN`` from the top.
#:
#: SEVERAL OF THE TESTS BELOW ARE ONLY LIVE BECAUSE THEY FILTER ON IT. ``show_page_numbers``
#: defaults to True, so "Page 7 of 12" is drawn at y=59.53 on every page from the second onwards
#: — it is below every body baseline and it is on every page. A test that asks "what is the
#: lowest thing on this page" or "is anything drawn on this page" without excluding it is
#: answered by the furniture on every page but the first, and passes on a renderer that is
#: entirely broken.
PT_PER_MM = 72.0 / 25.4
MARGIN = 25.0 * PT_PER_MM
TEXT_FLOOR = MARGIN + 10.0 * PT_PER_MM


class _Piece:
    """One drawn string with the box it occupies, in PDF user space."""

    __slots__ = ("page", "text", "x0", "x1", "y")

    def __init__(self, page: int, text: str, x0: float, x1: float, y: float) -> None:
        self.page = page
        self.text = text
        self.x0 = x0
        self.x1 = x1
        self.y = y


def _measurable_faces() -> dict[str, str]:
    """Every face this process has registered, keyed by every spelling a ``/BaseFont`` can carry.

    A ``/BaseFont`` IS NOT THE NAME ``registerFont`` WAS GIVEN, and it can differ in two unrelated
    ways at once. ReportLab writes a subset as ``/AAAAAA+<face>`` and may append the subset index,
    so the face bound as ``NirmalaUI`` can reach the file as ``AAAAAA+NirmalaUI-0``; and the
    ``<face>`` it writes is the font FILE's PostScript name, NOT the ReportLab name. Measured, not
    assumed: with ``REPORT_PDF_FONT`` pointed at reportlab's bundled Vera, `report_pdf` binds the
    override under the family name ``ReportCustom`` and the file comes back saying
    ``/AAAAAA+BitstreamVeraSans-Roman``.

    THAT SECOND DIFFERENCE IS WHY THIS IS BUILT FROM THE REGISTRY INSTEAD OF BY SURGERY ON THE
    STRING. Stripping the prefix and the index resolves the Windows face whose PostScript name
    happens to equal the family it was bound as, and resolves NOTHING bound through
    ``REPORT_PDF_FONT`` — after which EVERY width in the module became ``len(text) * size * 0.5``.

    WHAT THAT COST, MEASURED RATHER THAN ARGUED, by running the pre-fix module under
    ``REPORT_PDF_FONT`` pointed at Vera: 2 failed, 14 passed. The two failures are the pair that
    compares an x-extent against the text column —
    ``test_a_long_contents_entry_stays_inside_the_text_column`` and
    ``test_a_contents_entry_that_wraps_prints_the_page_its_section_is_on`` — and neither renderer
    nor report was wrong; the guess was. The other fourteen went on passing on measurements that
    were no longer measurements. So the fallback bought nothing in either direction: it turned the
    assertions sensitive enough to notice into false alarms about the RENDERER, and turned the rest
    into green that means nothing. Both halves are worse than a refusal, which is why
    :func:`_registered_name` raises. With the face resolved, the same command is 16 passed.

    A POSTSCRIPT NAME WINS OVER A REGISTERED NAME WHERE THE TWO COLLIDE, which is why the
    PostScript pass runs first. The string in a ``/BaseFont`` was written by whoever wrote the
    file, and what it spells is the FONT FILE's PostScript name — so if one string happens to be
    face A's ReportLab name and face B's PostScript name, the file that carries it means B, and
    resolving it to A would measure the text with the wrong metrics and produce a wrong advance
    width silently. That is the one thing this module is built not to do. Collisions among the
    PostScript names themselves do not need resolving: two registered names whose faces report one
    PostScript name are two bindings of the same file, so their metrics are identical. The
    registered-name entries exist to catch the spellings no face's PostScript name has — chiefly
    the subset-index form, ``NirmalaUI-0`` for the family bound as ``NirmalaUI``.
    """
    index: dict[str, str] = {}
    names = list(pdfmetrics.getRegisteredFontNames())
    for registered in names:
        try:
            face = pdfmetrics.getFont(registered).face
        except Exception:  # noqa: BLE001 - a name the registry lists but cannot resolve
            continue
        ps_name = getattr(face, "name", None)
        if isinstance(ps_name, bytes):
            # TTFontFile keeps the name table's bytes; the PDF carries the same ASCII.
            ps_name = ps_name.decode("latin-1", "replace")
        if ps_name:
            index.setdefault(str(ps_name), registered)
    for registered in names:
        index.setdefault(registered, registered)
    return index


def _registered_name(base_font: str, faces: dict[str, str]) -> str:
    """The name this process can measure with, from the one the PDF file carries.

    Raises rather than returning a sentinel when the face cannot be resolved — see
    :func:`_measurable_faces` for why a fallback here is worse than a failure.
    """
    name = str(base_font).lstrip("/").split("+")[-1]
    candidates = [name]
    head, _, tail = name.rpartition("-")
    if head and tail.isdigit():
        candidates.append(head)
    for candidate in candidates:
        if candidate in faces:
            return faces[candidate]
    raise AssertionError(
        f"cannot measure text drawn in /BaseFont {base_font!r}: no registered face answers to any "
        f"of {candidates}. Registered: {sorted(faces)}. Every assertion in this module is made "
        f"against a measured advance width, so resolve the face — do not let it estimate."
    )


def _pieces(pdf: bytes) -> tuple[list[_Piece], float, float]:
    """Every non-blank string in the file, with its true horizontal extent and its baseline.

    The width is measured with the face the PDF itself names, resolved back to the name this
    process registered it under. NOTHING IS ESTIMATED: a face that will not resolve raises out of
    the visitor and fails the test that asked for the pieces, naming the ``/BaseFont`` it could not
    place. The half-em fallback this replaced is measured in :func:`_measurable_faces` — it did not
    merely weaken the assertions, it made two of them fail on a renderer that was correct.
    """
    reader = PdfReader(BytesIO(pdf))
    # Built once per file rather than per piece: the render has already registered every face by
    # the time the bytes exist, so the registry cannot change underneath the loop.
    faces = _measurable_faces()
    out: list[_Piece] = []
    for number, page in enumerate(reader.pages, 1):

        def visit(text, _cm, tm, font_dict, font_size, number=number):
            if not text.strip():
                return
            # pypdf hands the extractor's line breaks back inside the piece; they are not drawn.
            drawn = text.replace("\n", "").replace("\r", "")
            base = _registered_name(str((font_dict or {}).get("/BaseFont", "")), faces)
            size = font_size or 10.0
            width = pdfmetrics.stringWidth(drawn, base, size)
            out.append(_Piece(number, drawn, tm[4], tm[4] + width, tm[5]))

        page.extract_text(visitor_text=visit)
    box = reader.pages[0].mediabox
    return out, float(box.width), float(box.height)


# --------------------------------------------------------------------------------------
# B1 — a cell taller than the text column
# --------------------------------------------------------------------------------------


def _over_tall_table():
    builder = DocumentBuilder(meta=_meta())
    builder.add(TableBlock(
        columns=(TableColumn(header="Field", width_pct=30.0),
                 TableColumn(header="Value", width_pct=70.0)),
        rows=((runs_of("Notes"), runs_of(LONG_ANSWER)),
              (runs_of("After"), runs_of("the row that follows the long one"))),
    ))
    return builder.build()


def _over_tall_key_values():
    builder = DocumentBuilder(meta=_meta())
    builder.add(KeyValueBlock(pairs=(
        ("Notes", runs_of(LONG_ANSWER)),
        ("After", runs_of("the row that follows the long one")),
    )))
    return builder.build()


@pytest.mark.parametrize("document,label", [
    (_over_tall_table(), "table"),
    (_over_tall_key_values(), "key/value grid"),
])
def test_a_cell_taller_than_the_page_is_not_drawn_off_the_bottom_of_it(document, label):
    """THE SHIP-BLOCKER THIS MODULE WAS OPENED FOR.

    ``_simple_grid`` and ``_block_table.draw_row`` draw a row inside ``_locked`` after a single
    ``_ensure``. That is right for a row that fits: it is what stops a break landing between two
    lines of one cell. But a row TALLER than a page never fits, the lock suppresses every break,
    and the cursor simply kept going negative — 395 of 1,092 pieces below y=0 for the table and
    432 of 1,085 for the grid, the lowest at y=-492.9 against a MediaBox 841.89 high.

    Nothing raises; the file opens; the words are simply drawn outside the paper. The .docx of
    the same document keeps all of them, so a ministry reader comparing the two finds the PDF has
    eaten paragraphs.
    """
    pdf, _dropped = render_pdf(document, lambda _ref: None)
    pieces, _width, _height = _pieces(pdf)
    below = [p for p in pieces if p.y < 0]
    assert not below, (
        f"{len(below)} of {len(pieces)} pieces in the {label} are drawn below y=0, "
        f"the lowest at y={min(p.y for p in below):.1f} — off the paper and lost to the reader"
    )


@pytest.mark.parametrize("document,label", [
    (_over_tall_table(), "table"),
    (_over_tall_key_values(), "key/value grid"),
])
def test_splitting_an_over_tall_cell_keeps_every_word_of_it(document, label):
    """A REFUSAL, NOT A TRUNCATION — the rule ``stage_schema.coerce_value`` states for the whole
    repository. Clipping the overflow would have made the geometry test above pass while losing
    exactly what the defect lost, so the words are counted separately from where they landed."""
    pdf, _dropped = render_pdf(document, lambda _ref: None)
    pieces, _width, _height = _pieces(pdf)
    drawn = " ".join(p.text for p in pieces)
    assert drawn.count("long-text") == LONG_ANSWER.count("long-text"), (
        f"the {label} lost sentences: {drawn.count('long-text')} of "
        f"{LONG_ANSWER.count('long-text')} survived the split"
    )
    assert "the row that follows the long one" in " ".join(drawn.split()), (
        "the row after the over-tall one must still be drawn"
    )


@pytest.mark.parametrize("document,label", [
    (_over_tall_table(), "table"),
    (_over_tall_key_values(), "key/value grid"),
])
def test_an_over_tall_cell_does_not_cost_a_blank_page(document, label):
    """The obvious wrong fix is to turn the page first and split afterwards, which abandons
    whatever was left of the page the row started on. The cut is therefore taken BEFORE the fit
    test, against the space actually remaining."""
    pdf, _dropped = render_pdf(document, lambda _ref: None)
    pieces, _width, _height = _pieces(pdf)
    # THE RUNNING FOOT IS NOT CONTENT. "Page 7 of 12" is drawn on every page from the second on,
    # so counting all pieces makes every page look occupied and only a blank PAGE ONE is
    # detectable — which is the one the implementer's first attempt happened to leave blank.
    # Counting only what is inside the text column asks the question the name asks.
    pages = {p.page for p in pieces if p.y >= TEXT_FLOOR}
    reader = PdfReader(BytesIO(pdf))
    empty = [n for n in range(1, len(reader.pages) + 1) if n not in pages]
    assert not empty, f"the {label} left page(s) {empty} with nothing drawn on them"


# --------------------------------------------------------------------------------------
# A2 — keepNext
# --------------------------------------------------------------------------------------


def _many_headings(count: int = 300):
    """Headings at every possible offset down the page, which is what the defect needs.

    The body paragraphs vary in length on purpose: a heading only orphans when it happens to land
    within its own trailing spacing of the bottom margin, so a document whose sections are all the
    same length either always hits it or never does.
    """
    builder = DocumentBuilder(meta=_meta())
    for i in range(1, count + 1):
        builder.add(HeadingBlock(level=1, runs=runs_of(f"Section marker {i:03d}"),
                                 number=str(i), bookmark=f"h{i}"))
        builder.add(ParagraphBlock(runs=runs_of("body " * (4 + (i * 37) % 117))))
    return builder.build()


def test_no_heading_is_left_alone_at_the_foot_of_a_page():
    """keepNext reserved 17.0 pt for a heading that spends 28.6 pt on its own spacing.

    The reservation was a flat 6 mm, and the 6.5 mm above the text, the 1.2 mm rule beneath it and
    the 2.4 mm after that are all taken AFTER the ``_ensure`` meant to have accounted for them. So
    a heading passed its own fit test and then walked past the bottom margin: 13 of 300 finished
    with less than one body line beneath them, the worst 5.7 pt BELOW the margin, while the
    comment above the reservation asserted it could not happen.

    Measured on the FILE: a heading is orphaned when nothing else is drawn on its page below it.

    THE FLOOR IS BUILT FROM THE TEXT COLUMN ONLY. Built from every extracted piece it was built
    from the running foot, which draws "Page N of M" at y=59.53 below every body baseline on every
    page from the second onwards, so ``lowest_body[page] == heading.y`` was unsatisfiable
    anywhere but page 1 and the metric reported 1 orphan where 11 existed. On four out of five
    arbitrary filler lengths it reported none at all — the test passed on the fully defective
    renderer and survived on this seed by the accident of one orphan landing on the cover page.
    """
    pdf, _dropped = render_pdf(_many_headings(), lambda _ref: None)
    pieces, _width, _height = _pieces(pdf)
    body_pieces = [p for p in pieces if p.y >= TEXT_FLOOR]
    lowest_body: dict[int, float] = {}
    heading_at: dict[str, tuple[int, float]] = {}
    for piece in body_pieces:
        lowest_body[piece.page] = min(lowest_body.get(piece.page, 1e9), piece.y)
    for piece in body_pieces:
        if piece.text.strip() == "marker":
            # "1. Section marker 007" is drawn one token at a time; "marker" appears in the
            # heading and nowhere else in this document.
            heading_at.setdefault(f"{piece.page}:{piece.y:.2f}", (piece.page, piece.y))
    assert len(heading_at) >= 100, "this document must produce a lot of headings to be a test"

    orphans = [
        (page, y) for page, y in heading_at.values()
        if abs(lowest_body[page] - y) < 0.01
    ]
    assert not orphans, (
        f"{len(orphans)} of {len(heading_at)} headings are the last thing on their page, "
        f"with nothing beneath them: {orphans[:5]}"
    )


# --------------------------------------------------------------------------------------
# A3 — the figure title, the contents entry, the running furniture
# --------------------------------------------------------------------------------------


def test_a_figure_title_stays_on_the_page_with_its_picture():
    """``_block_figure``'s comment claimed the title was drawn "inside the same locked region as
    the image below". There was no locked region, and the 6 mm it reserved said nothing about the
    picture — which reserves its own space inside ``_draw_image`` and breaks the page there. 32 of
    80 chart titles were separated from the chart they name, and a figure title is not in the
    contents, so a reader finds a bare label over somebody else's picture."""
    builder = DocumentBuilder(meta=_meta())
    for i in range(1, 81):
        builder.add(ParagraphBlock(runs=runs_of("body " * (4 + (i * 53) % 137))))
        builder.add(ChartBlock(kind=ChartKind.BAR, title=f"Figure {i}: designs by status",
                               series=(("Drafted", 4.0), ("Approved", 7.0), ("Rejected", 2.0)),
                               width_pct=60.0))
    pdf, dropped = render_pdf(builder.build(), lambda _ref: None)
    assert not dropped, f"the charts must actually rasterise for this to be a test: {dropped}"

    pieces, _width, _height = _pieces(pdf)
    # The title's page is where its own word lands; the picture is not text, so the page it went
    # to is read off the image XObjects instead.
    reader = PdfReader(BytesIO(pdf))
    pages_with_pictures = {
        number for number, page in enumerate(reader.pages, 1)
        if any(
            obj.get("/Subtype") == "/Image"
            for obj in (page.get("/Resources", {}).get("/XObject", {}) or {}).values()
        )
    }
    title_pages = {p.page for p in pieces if p.text.strip() == "Figure"}
    assert len(title_pages) > 1, "the document must span pages to be a test"
    stranded = sorted(title_pages - pages_with_pictures)
    assert not stranded, (
        f"page(s) {stranded} carry a figure title with no picture on them"
    )


LONG_SECTION_TITLE = (
    "Prototype development and iterative refinement of the traditional Sambalpuri ikat weave "
    "for contemporary furnishing applications in the Bargarh cluster"
)


def test_a_long_contents_entry_stays_inside_the_text_column():
    """The contents was the one text path in this renderer that never wrapped: a bare
    ``drawString`` of the whole label. The heading above makes a 688.6 pt contents line against a
    453.5 pt column — 164.2 pt past the edge of the paper, taking its own page number with it."""
    from app.services.report_model import TocBlock

    builder = DocumentBuilder(meta=_meta())
    builder.add(TocBlock(title="Contents", depth=1))
    for i in range(1, 6):
        builder.add(HeadingBlock(level=1, runs=runs_of(f"{LONG_SECTION_TITLE} {i}"),
                                 number=str(i), bookmark=f"h{i}"))
        builder.add(ParagraphBlock(runs=runs_of("body " * 60)))
    pdf, _dropped = render_pdf(builder.build(), lambda _ref: None)

    pieces, width, _height = _pieces(pdf)
    margin = 25.0 * 72.0 / 25.4      # ReportMeta.margin_mm default
    over = [p for p in pieces if p.x1 > width - margin + 0.5 or p.x0 < margin - 0.5]
    assert not over, (
        f"{len(over)} piece(s) are drawn outside the text column, the worst reaching "
        f"x={max(p.x1 for p in over):.1f} on a {width:.1f} pt sheet: "
        f"{[(p.text[:30], round(p.x0, 1), round(p.x1, 1)) for p in over[:3]]}"
    )


#: Five real section names of the length stage titles actually reach. Three of the five sit
#: exactly on a wrap boundary between the full text column and a column with the page-number
#: gutter taken out of it, which is what the test below needs and what
#: `test_every_page_number_the_contents_prints_is_the_page_the_section_is_on` in
#: ``test_report_parity`` does not have — its titles are one line and can never wrap differently.
SECTION_TITLES = (
    "Prototype development and iterative refinement of the traditional Sambalpuri ikat weave",
    "Baseline documentation of the cluster's looms, dye vats and finishing sheds",
    "Design intervention workshop with master weavers of the Bargarh handloom cooperative",
    "Market linkage, buyer feedback and the revised costing sheet for the furnishing range",
    "Skill upgradation of young artisans in tie-and-dye layout and graph transfer",
)


def test_a_contents_entry_that_wraps_prints_the_page_its_section_is_on():
    """THE TWO PASSES MUST WRAP THE CONTENTS INTO THE SAME COLUMN, and for a while they did not.

    ``build`` clears ``_heading_pages`` before every measuring pass including the reconciliation
    one, and the contents block is laid out before the first heading fills it again — so a
    contents entry sized its wrap column from a page number that is ALWAYS absent while measuring
    and ALWAYS present while drawing. Measured on the real renderer: 453.5 pt of column while
    measuring against 431.4 pt while drawing, which for a 60-section report put the contents on
    five pages when it was measured and six when it was drawn, and printed a number one page
    short beside all sixty entries. The bookmark outline, built during the drawing pass, stayed
    correct throughout, so nothing on screen suggests it.

    This is the failure the module header, ``_new_page``'s "the two passes must see identical
    geometry" and ``build``'s reconciliation comment all exist to prevent, so it is asserted the
    only way that survives a rewrite: read the number the contents PRINTS off the page, and
    compare it with the page the section was actually drawn on.
    """
    import re

    from app.services.report_model import TocBlock

    count = 60
    builder = DocumentBuilder(meta=_meta())
    builder.add(TocBlock(title="Contents", depth=1))
    for i in range(1, count + 1):
        # The marker is the LAST token of the title, so it is on the entry's last line — which is
        # the line the page number and the dot leader ride on.
        title = f"{SECTION_TITLES[i % len(SECTION_TITLES)]} SEC{i:03d}"
        builder.add(HeadingBlock(level=1, runs=runs_of(title), number=str(i), bookmark=f"h{i}"))
        builder.add(ParagraphBlock(runs=runs_of("body " * (4 + (i * 37) % 117))))
    pdf, _dropped = render_pdf(builder.build(), lambda _ref: None)

    pieces, width, _height = _pieces(pdf)
    marker_at: dict[str, list[tuple[int, float]]] = {}
    for piece in pieces:
        text = piece.text.strip()
        if text.startswith("SEC") and text[3:].isdigit():
            marker_at.setdefault(text, []).append((piece.page, piece.y))
    assert len(marker_at) == count, (
        f"every section must appear once in the contents and once as a heading; "
        f"found {len(marker_at)} markers of {count}"
    )
    # The contents is laid out before the headings and closes with a page break, so a marker's
    # FIRST appearance is its contents entry and its LAST is the heading itself.
    heading_page = {m: max(page for page, _y in at) for m, at in marker_at.items()}
    entry_at = {m: min(at) for m, at in marker_at.items()}
    first_heading_page = min(heading_page.values())
    assert first_heading_page > 1, "the contents must occupy at least one page of its own"

    # The printed number is right-aligned on the entry's last line, at the right edge of the text
    # column — which is what tells it apart from the "Page N of M" in the running foot. That foot
    # is drawn with one `drawRightString` and so extracts as a single piece; `isdigit()` on the
    # whole piece rejects it, and the baseline filter below would reject it a second time.
    numbers = [p for p in pieces
               if p.page < first_heading_page and p.text.strip().isdigit()
               and abs(p.x1 - (width - MARGIN)) < 1.0]
    label_at: dict[int, tuple[int, float]] = {}
    for piece in pieces:
        text = piece.text.strip()
        if piece.page < first_heading_page and re.fullmatch(r"\d+\.", text):
            label_at.setdefault(int(text[:-1]), (piece.page, piece.y))
    wrapped = [i for i, pos in label_at.items()
               if abs(entry_at[f"SEC{i:03d}"][1] - pos[1]) > 0.5]
    assert len(wrapped) >= count // 3, (
        f"only {len(wrapped)} of {count} contents entries wrap onto a second line — with none "
        f"of them wrapping this test cannot see the defect it is here for"
    )

    wrong = []
    for marker, (page, y) in entry_at.items():
        printed = [n for n in numbers if n.page == page and abs(n.y - y) < 0.5]
        assert len(printed) == 1, (
            f"{marker}'s contents entry should carry exactly one page number on its last line, "
            f"found {[n.text for n in printed]}"
        )
        if int(printed[0].text) != heading_page[marker]:
            wrong.append((marker, int(printed[0].text), heading_page[marker]))
    assert not wrong, (
        f"{len(wrong)} of {count} contents entries send the reader to the wrong page "
        f"(marker, printed, actual): {sorted(wrong)[:5]}"
    )


LONG_MINISTRY_LINE = (
    "Office of the Development Commissioner (Handicrafts), Ministry of Textiles, Government of "
    "India — Design and Prototype Development Workshop for the Sambalpuri Bandha Ikat handloom "
    "weaving cluster, Bargarh district, Odisha"
)


def test_a_long_running_head_and_foot_stay_on_the_sheet():
    """Both are free text a designer types into stage 20, and neither was wrapped: the running
    head was drawn from x=-265.1 — 265 pt off the LEFT edge of the paper — and the running foot
    ran to x=860.3 on a 595.3 pt sheet, on every page. The .docx wraps the same line inside its
    header part, so one download carried two different running heads."""
    builder = DocumentBuilder(meta=_meta(header_text=LONG_MINISTRY_LINE,
                                         footer_text=LONG_MINISTRY_LINE))
    for _ in range(20):
        builder.add(ParagraphBlock(runs=runs_of("body " * 120)))
    pdf, _dropped = render_pdf(builder.build(), lambda _ref: None)

    pieces, width, height = _pieces(pdf)
    off = [p for p in pieces if p.x0 < 0 or p.x1 > width or p.y < 0 or p.y > height]
    assert not off, (
        f"{len(off)} piece(s) of running furniture are drawn off the sheet: "
        f"{[(p.text[:30], round(p.x0, 1), round(p.x1, 1)) for p in off[:3]]}"
    )
    # And the words are all still there: wrapping must not become quiet truncation. ASSERTED ON
    # EACH SURFACE SEPARATELY — the head is above the text column and the foot below it, and one
    # assertion over the whole page was satisfied by the head alone, so the foot went untested.
    head, foot = _furniture(pieces, page=2, height=height)
    assert "Bargarh district, Odisha" in head, "the tail of the running HEAD was dropped"
    assert "Bargarh district, Odisha" in foot, "the tail of the running FOOT was dropped"


def _furniture(pieces, *, page: int, height: float) -> tuple[str, str]:
    """The running head's own words and the running foot's own words, on one page.

    The head is drawn above the top margin and the foot below the bottom one; nothing in the text
    column is in either. Separating them is what makes "which end of a clipped line survives" an
    answerable question — asked of the page as a whole, the head answers for the foot.
    """
    def words(chosen) -> str:
        # READING ORDER, not drawing order: the head is stacked bottom-up so that its last kept
        # line sits nearest the rule, which means the file draws it back to front.
        chosen = sorted(chosen, key=lambda p: (-p.y, p.x0))
        return " ".join(" ".join(p.text for p in chosen).split())

    on_page = [p for p in pieces if p.page == page]
    return (words([p for p in on_page if p.y > height - MARGIN]),
            words([p for p in on_page if p.y < MARGIN]))


#: Long enough that neither margin can hold all of it: five lines of head and five of foot fit
#: in a 25 mm margin at 7.8 pt, and this wraps to about ten.
OVERLONG_RUNNING_LINE = " ".join([LONG_MINISTRY_LINE] * 5) + " ENDMARKER"


def test_a_running_line_too_long_for_the_margin_drops_the_same_end_at_both_edges(caplog):
    """BOTH ENDS DROP THE TAIL, and until this test they did not.

    The head stacks upward from its rule, so the natural loop — walk the wrapped lines backwards
    and stop at the sheet edge — kept the LAST lines and threw away the first, which on a
    ministry running head is the organisation that owns the document. The foot, iterating
    forward, dropped its tail. Two ends of the same clipped line in one file is an accident of
    which direction each loop happened to run, not a decision. A running line is read from its
    start, so both keep the opening now.

    The drop is also reported, once per surface: running furniture is a repeated label rather
    than the designer's prose, and a label the paper cannot hold has to be shortened by whoever
    wrote it.
    """
    builder = DocumentBuilder(meta=_meta(header_text=OVERLONG_RUNNING_LINE,
                                         footer_text=OVERLONG_RUNNING_LINE))
    for _ in range(20):
        builder.add(ParagraphBlock(runs=runs_of("body " * 120)))
    with caplog.at_level("WARNING", logger="app.services.report_pdf"):
        pdf, _dropped = render_pdf(builder.build(), lambda _ref: None)

    pieces, _width, height = _pieces(pdf)
    off = [p for p in pieces if p.y < 0 or p.y > height]
    assert not off, f"{len(off)} piece(s) of clipped furniture are still drawn off the sheet"

    head, foot = _furniture(pieces, page=2, height=height)
    assert head and foot, "both running surfaces must draw something"
    for which, drawn in (("head", head), ("foot", foot)):
        assert drawn.startswith("Office of the Development Commissioner"), (
            f"the running {which} dropped the OPENING of the line and kept its tail: "
            f"{drawn[:60]!r}"
        )
        assert "ENDMARKER" not in drawn, (
            f"the running {which} was expected to be clipped for this to be a test"
        )
    clipped = [r for r in caplog.records if "does not fit in the page margin" in r.getMessage()]
    assert len(clipped) == 2, (
        f"the drop must be reported once for the head and once for the foot, not {len(clipped)} "
        f"times: {[r.getMessage()[:70] for r in clipped]}"
    )


def test_a_figure_title_taller_than_the_page_does_not_take_the_lock_with_it():
    """The lock that keeps a figure title with its picture may not become the fourth instance of
    the block that fits on no page.

    Only the PICTURE is capped, at 0.58 of the text column; the title is not, so title plus
    picture can exceed a whole page and the comment that claimed otherwise was asserting a
    guarantee the code did not provide. Chart and map titles are developer-authored constants in
    ``report_builder`` today, which is the only reason this is unreachable rather than shipped —
    the same category as the ``_block_metrics`` note. It is checked instead of claimed: a title
    that cannot share a page with its picture paginates line by line OUTSIDE the lock, which
    separates the title from the chart and keeps every word on the paper.
    """
    builder = DocumentBuilder(meta=_meta())
    builder.add(ChartBlock(kind=ChartKind.BAR, title=LONG_ANSWER,
                           series=(("Drafted", 4.0), ("Approved", 7.0), ("Rejected", 2.0)),
                           width_pct=60.0))
    builder.add(ParagraphBlock(runs=runs_of("after the figure")))
    pdf, dropped = render_pdf(builder.build(), lambda _ref: None)
    assert not dropped, f"the chart must actually rasterise for this to be a test: {dropped}"

    pieces, _width, _height = _pieces(pdf)
    below = [p for p in pieces if p.y < 0]
    assert not below, (
        f"{len(below)} pieces of the figure title are drawn below y=0, the lowest at "
        f"y={min(p.y for p in below):.1f}"
    )
    drawn = " ".join(p.text for p in pieces)
    assert drawn.count("long-text") == LONG_ANSWER.count("long-text"),         "the figure title lost sentences"
    assert "after the figure" in " ".join(drawn.split()),         "the block after the figure must still be drawn"


def test_the_cover_is_still_exactly_one_page():
    """``_cut_row`` refuses to cut inside a ``_locked`` region, and the cover is the region that
    depends on it: it measures its own contents into a page it has budgeted for, and a grid row
    that broke a page there would push "Submitted to …" onto a second, otherwise blank sheet and
    shift every page number in the contents."""
    builder = DocumentBuilder(meta=_meta())
    builder.add(CoverBlock(
        title="Design and Prototype Development Workshop",
        subtitle="Sambalpuri Bandha Ikat, Bargarh",
        org_lines=("Ministry of Textiles",),
        info_rows=tuple((f"Field {i}", f"Value {i}") for i in range(1, 9)),
        footer_lines=("Submitted to the Development Commissioner (Handicrafts)",),
    ))
    builder.add(ParagraphBlock(runs=runs_of("The body starts on page two.")))
    pdf, _dropped = render_pdf(builder.build(), lambda _ref: None)

    pieces, _width, _height = _pieces(pdf)
    body = [p.page for p in pieces if p.text.strip() == "body" or "page" in p.text]
    assert any(p.page == 1 for p in pieces if "Submitted" in p.text), (
        "the closing line of the cover must stay on the cover"
    )
    assert body, "the body must be drawn"
    assert min(body) == 2, "the body must begin on the page after the cover"


# --------------------------------------------------------------------------------------
# The other two locked regions, found by measuring rather than by the audit
# --------------------------------------------------------------------------------------


def test_a_callout_longer_than_a_page_is_continued_rather_than_overrun():
    """The third locked region with the same defect. A tinted callout box is drawn after one
    ``_ensure`` and its words inside a lock, so a body longer than the page put 72 pieces below
    y=0 — measured. No report template emits a ``CalloutBlock`` today, which is the only reason
    this never reached a ministry desk."""
    from app.services.report_model import CalloutBlock

    builder = DocumentBuilder(meta=_meta())
    builder.add(CalloutBlock(kind="INFO", title="Note", runs=runs_of(LONG_ANSWER)))
    builder.add(ParagraphBlock(runs=runs_of("after the callout")))
    pdf, _dropped = render_pdf(builder.build(), lambda _ref: None)

    pieces, _width, _height = _pieces(pdf)
    below = [p for p in pieces if p.y < 0]
    assert not below, f"{len(below)} pieces of the callout are drawn below y=0"
    drawn = " ".join(p.text for p in pieces)
    assert drawn.count("long-text") == LONG_ANSWER.count("long-text"), \
        "the callout lost sentences"
    assert "callout" in " ".join(drawn.split()), "the block after the callout must still be drawn"


def test_a_photo_caption_longer_than_a_page_is_continued_in_its_own_column():
    """THE REACHABLE ONE, and it is not in a table at all.

    A photograph is capped at 0.30 of the text column so the pictures always fit; it is the
    CAPTION that overruns, and all twenty-five caption fields in the registry are TEXT with
    ``max_length == 0``. The lock that keeps a grid row together then suppressed the break the row
    needed: 442 of 1,084 pieces were drawn below the foot of the sheet.
    """
    from app.services.report_model import ImageGridBlock, ImageRef
    from tests.test_report_docx import _png

    png = _png(400, 300)
    builder = DocumentBuilder(meta=_meta())
    builder.add(ImageGridBlock(
        images=((ImageRef("a", 400, 300, mime_type="image/png"), LONG_ANSWER),
                (ImageRef("b", 400, 300, mime_type="image/png"), "a short caption")),
        columns=2,
    ))
    builder.add(ParagraphBlock(runs=runs_of("after the grid")))
    pdf, dropped = render_pdf(builder.build(), lambda _ref: png)
    assert not dropped, f"the photographs must actually decode for this to be a test: {dropped}"

    pieces, _width, _height = _pieces(pdf)
    below = [p for p in pieces if p.y < 0]
    assert not below, f"{len(below)} pieces of the caption are drawn below y=0"
    drawn = " ".join(p.text for p in pieces)
    assert drawn.count("long-text") == LONG_ANSWER.count("long-text"), \
        "the photograph's caption lost sentences"
    assert "grid" in " ".join(drawn.split()), "the block after the grid must still be drawn"
