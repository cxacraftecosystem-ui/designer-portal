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

LONG_ANSWER = "The designer wrote a very long answer in a single long-text field. " * 90


def _meta(**kw) -> ReportMeta:
    base = {"title": "Workshop", "generated_at": "2026-01-01T00:00:00Z"}
    base.update(kw)
    return ReportMeta(**base)


PT_PER_MM = 72.0 / 25.4
MARGIN = 25.0 * PT_PER_MM
TEXT_FLOOR = MARGIN + 10.0 * PT_PER_MM


class _Piece:
    __slots__ = ("page", "text", "x0", "x1", "y")

    def __init__(self, page: int, text: str, x0: float, x1: float, y: float) -> None:
        self.page = page
        self.text = text
        self.x0 = x0
        self.x1 = x1
        self.y = y


def _measurable_faces() -> dict[str, str]:
    index: dict[str, str] = {}
    names = list(pdfmetrics.getRegisteredFontNames())
    for registered in names:
        try:
            face = pdfmetrics.getFont(registered).face
        except Exception:  # noqa: BLE001 - a name the registry lists but cannot resolve
            continue
        ps_name = getattr(face, "name", None)
        if isinstance(ps_name, bytes):
            ps_name = ps_name.decode("latin-1", "replace")
        if ps_name:
            index.setdefault(str(ps_name), registered)
    for registered in names:
        index.setdefault(registered, registered)
    return index


def _registered_name(base_font: str, faces: dict[str, str]) -> str:
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
    reader = PdfReader(BytesIO(pdf))
    faces = _measurable_faces()
    out: list[_Piece] = []
    for number, page in enumerate(reader.pages, 1):

        def visit(text, _cm, tm, font_dict, font_size, number=number):
            if not text.strip():
                return
            drawn = text.replace("\n", "").replace("\r", "")
            base = _registered_name(str((font_dict or {}).get("/BaseFont", "")), faces)
            size = font_size or 10.0
            width = pdfmetrics.stringWidth(drawn, base, size)
            out.append(_Piece(number, drawn, tm[4], tm[4] + width, tm[5]))

        page.extract_text(visitor_text=visit)
    box = reader.pages[0].mediabox
    return out, float(box.width), float(box.height)



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
    pdf, _dropped = render_pdf(document, lambda _ref: None)
    pieces, _width, _height = _pieces(pdf)
    pages = {p.page for p in pieces if p.y >= TEXT_FLOOR}
    reader = PdfReader(BytesIO(pdf))
    empty = [n for n in range(1, len(reader.pages) + 1) if n not in pages]
    assert not empty, f"the {label} left page(s) {empty} with nothing drawn on them"



def _many_headings(count: int = 300):
    builder = DocumentBuilder(meta=_meta())
    for i in range(1, count + 1):
        builder.add(HeadingBlock(level=1, runs=runs_of(f"Section marker {i:03d}"),
                                 number=str(i), bookmark=f"h{i}"))
        builder.add(ParagraphBlock(runs=runs_of("body " * (4 + (i * 37) % 117))))
    return builder.build()


def test_no_heading_is_left_alone_at_the_foot_of_a_page():
    pdf, _dropped = render_pdf(_many_headings(), lambda _ref: None)
    pieces, _width, _height = _pieces(pdf)
    body_pieces = [p for p in pieces if p.y >= TEXT_FLOOR]
    lowest_body: dict[int, float] = {}
    heading_at: dict[str, tuple[int, float]] = {}
    for piece in body_pieces:
        lowest_body[piece.page] = min(lowest_body.get(piece.page, 1e9), piece.y)
    for piece in body_pieces:
        if piece.text.strip() == "marker":
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



def test_a_figure_title_stays_on_the_page_with_its_picture():
    builder = DocumentBuilder(meta=_meta())
    for i in range(1, 81):
        builder.add(ParagraphBlock(runs=runs_of("body " * (4 + (i * 53) % 137))))
        builder.add(ChartBlock(kind=ChartKind.BAR, title=f"Figure {i}: designs by status",
                               series=(("Drafted", 4.0), ("Approved", 7.0), ("Rejected", 2.0)),
                               width_pct=60.0))
    pdf, dropped = render_pdf(builder.build(), lambda _ref: None)
    assert not dropped, f"the charts must actually rasterise for this to be a test: {dropped}"

    pieces, _width, _height = _pieces(pdf)
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
    from app.services.report_model import TocBlock

    builder = DocumentBuilder(meta=_meta())
    builder.add(TocBlock(title="Contents", depth=1))
    for i in range(1, 6):
        builder.add(HeadingBlock(level=1, runs=runs_of(f"{LONG_SECTION_TITLE} {i}"),
                                 number=str(i), bookmark=f"h{i}"))
        builder.add(ParagraphBlock(runs=runs_of("body " * 60)))
    pdf, _dropped = render_pdf(builder.build(), lambda _ref: None)

    pieces, width, _height = _pieces(pdf)
    margin = 25.0 * 72.0 / 25.4
    over = [p for p in pieces if p.x1 > width - margin + 0.5 or p.x0 < margin - 0.5]
    assert not over, (
        f"{len(over)} piece(s) are drawn outside the text column, the worst reaching "
        f"x={max(p.x1 for p in over):.1f} on a {width:.1f} pt sheet: "
        f"{[(p.text[:30], round(p.x0, 1), round(p.x1, 1)) for p in over[:3]]}"
    )


SECTION_TITLES = (
    "Prototype development and iterative refinement of the traditional Sambalpuri ikat weave",
    "Baseline documentation of the cluster's looms, dye vats and finishing sheds",
    "Design intervention workshop with master weavers of the Bargarh handloom cooperative",
    "Market linkage, buyer feedback and the revised costing sheet for the furnishing range",
    "Skill upgradation of young artisans in tie-and-dye layout and graph transfer",
)


def test_a_contents_entry_that_wraps_prints_the_page_its_section_is_on():
    import re

    from app.services.report_model import TocBlock

    count = 60
    builder = DocumentBuilder(meta=_meta())
    builder.add(TocBlock(title="Contents", depth=1))
    for i in range(1, count + 1):
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
    heading_page = {m: max(page for page, _y in at) for m, at in marker_at.items()}
    entry_at = {m: min(at) for m, at in marker_at.items()}
    first_heading_page = min(heading_page.values())
    assert first_heading_page > 1, "the contents must occupy at least one page of its own"

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
    head, foot = _furniture(pieces, page=2, height=height)
    assert "Bargarh district, Odisha" in head, "the tail of the running HEAD was dropped"
    assert "Bargarh district, Odisha" in foot, "the tail of the running FOOT was dropped"


def _furniture(pieces, *, page: int, height: float) -> tuple[str, str]:
    def words(chosen) -> str:
        chosen = sorted(chosen, key=lambda p: (-p.y, p.x0))
        return " ".join(" ".join(p.text for p in chosen).split())

    on_page = [p for p in pieces if p.page == page]
    return (words([p for p in on_page if p.y > height - MARGIN]),
            words([p for p in on_page if p.y < MARGIN]))


OVERLONG_RUNNING_LINE = " ".join([LONG_MINISTRY_LINE] * 5) + " ENDMARKER"


def test_a_running_line_too_long_for_the_margin_drops_the_same_end_at_both_edges(caplog):
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



def test_a_callout_longer_than_a_page_is_continued_rather_than_overrun():
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
