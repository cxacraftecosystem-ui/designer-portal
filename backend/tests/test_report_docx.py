"""Package-level checks on the generated .docx.

Word reports every one of the failures below as the same sentence — "the file appears to be
corrupted" — with no indication of which part is wrong, so each check here exists because the
generic message once cost an afternoon:

* a part declared with the wrong content type (the .docx FILE mime on the main document PART),
* a relationship id used in document.xml but never declared in document.xml.rels,
* two adjacent tables, which Word merges silently rather than rejecting,
* a style or numbering id referenced but not defined.

``test_every_block_type_survives_a_round_trip`` is the regression net: it renders one of every
block the model can produce, so a new block type that forgets its trailing paragraph or its
relationship fails here rather than in the field.
"""

import re
import struct
import zipfile
import zlib
from io import BytesIO
from xml.etree import ElementTree as ET

import pytest

from app.services.report_docx import probe_image_size, render_docx
from app.services.report_model import (
    CalloutBlock,
    ChartBlock,
    ChartKind,
    CoverBlock,
    DocumentBuilder,
    ImageBlock,
    ImageGridBlock,
    ImageRef,
    MetricRowBlock,
    PageBreakBlock,
    ParaStyle,
    ReportMeta,
    SignatureBlock,
    SpacerBlock,
    TableBlock,
    TableColumn,
    TocBlock,
    runs_of,
)

W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
CT = "{http://schemas.openxmlformats.org/package/2006/content-types}"
REL = "{http://schemas.openxmlformats.org/package/2006/relationships}"
WML = "application/vnd.openxmlformats-officedocument.wordprocessingml"

EXPECTED_CONTENT_TYPES = {
    "/word/document.xml": f"{WML}.document.main+xml",
    "/word/styles.xml": f"{WML}.styles+xml",
    "/word/numbering.xml": f"{WML}.numbering+xml",
    "/word/settings.xml": f"{WML}.settings+xml",
    "/word/header1.xml": f"{WML}.header+xml",
    "/word/footer1.xml": f"{WML}.footer+xml",
    "/docProps/core.xml": "application/vnd.openxmlformats-package.core-properties+xml",
    "/docProps/app.xml":
        "application/vnd.openxmlformats-officedocument.extended-properties+xml",
}


# --------------------------------------------------------------------------------------
# Fixtures
# --------------------------------------------------------------------------------------


def _png(width: int, height: int, rgb: tuple[int, int, int] = (120, 90, 60)) -> bytes:
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))

    def chunk(tag: bytes, data: bytes) -> bytes:
        payload = tag + data
        return (struct.pack(">I", len(data)) + payload
                + struct.pack(">I", zlib.crc32(payload) & 0xFFFFFFFF))

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


def _jpeg(width: int, height: int) -> bytes:
    """A JPEG header far enough along to carry a real SOF0 frame size."""
    return (
        b"\xff\xd8"
        + b"\xff\xe0" + struct.pack(">H", 16) + b"JFIF\x00" + b"\x01\x01\x00" + b"\x00\x01"
        + b"\x00\x01" + b"\x00\x00"
        + b"\xff\xc0" + struct.pack(">H", 17) + b"\x08"
        + struct.pack(">HH", height, width) + b"\x03" + b"\x01\x11\x00\x02\x11\x01\x03\x11\x01"
        + b"\xff\xd9"
    )


IMAGES = {"p": _png(400, 300), "j": _jpeg(640, 480), "portrait": _png(300, 400)}


def _loader(ref: ImageRef) -> bytes | None:
    return IMAGES.get(ref.source)


def _meta(**kw) -> ReportMeta:
    base = {"title": "Workshop Report", "subtitle": "Bargarh Cluster", "author": "A. Sharma",
                "header_text": "hdr", "footer_text": "ftr", "generated_at": "2026-08-07T00:00:00Z"}
    base.update(kw)
    return ReportMeta(**base)


def _render(build) -> tuple[zipfile.ZipFile, str, list[str]]:
    b = DocumentBuilder(meta=_meta())
    build(b)
    data, dropped = render_docx(b.build(), _loader)
    z = zipfile.ZipFile(BytesIO(data))
    return z, z.read("word/document.xml").decode("utf-8"), dropped


def _all_blocks(b: DocumentBuilder) -> None:
    """One of every block type the model can produce."""
    b.add(CoverBlock(
        title="T", subtitle="S", org_lines=("Ministry",),
        logo=ImageRef("p", 400, 300, mime_type="image/png"),
        hero_image=ImageRef("j", 640, 480, mime_type="image/jpeg"),
        info_rows=(("Craft", "Ikat"), ("Cluster", "Bargarh")),
        footer_lines=("Submitted to DCH",),
    ))
    b.add(TocBlock(depth=3))
    b.heading("Introduction", 1)
    b.para("Lead paragraph.", style=ParaStyle.LEAD)
    b.para("Body with <angle> & \"quote\" and 'apostrophe'.")
    b.para("A quote.", style=ParaStyle.QUOTE)
    b.para("A note.", style=ParaStyle.NOTE)
    b.heading("Detail", 2)
    b.bullets(["one", "two"])
    b.bullets(["first", "second"], ordered=True)
    b.key_values([("Craft", "Ikat"), ("State", "Odisha")], columns=2)
    b.add(TableBlock(
        columns=(TableColumn("Item", 60.0), TableColumn("Amount", 40.0, numeric=True)),
        rows=((runs_of("Yarn"), runs_of("562.50")), (runs_of("Dye"), runs_of("192.00"))),
        total_row=(runs_of("Total"), runs_of("754.50")),
        caption="Table 1",
    ))
    b.add(ImageBlock(image=ImageRef("p", 400, 300, mime_type="image/png"), caption="Fig 1"))
    b.add(ImageGridBlock(
        images=((ImageRef("portrait", 300, 400, rotation_deg=90, mime_type="image/png"), "a"),
                (ImageRef("j", 640, 480, mime_type="image/jpeg"), "b")),
        columns=2, caption="Fig 2",
    ))
    # A chart of each kind. These take the NATIVE path, so they are what makes the package-level
    # checks below cover word/charts/*, word/embeddings/*.xlsx and the "cId" relationships as well
    # as the picture parts. MapBlock is deliberately absent: it needs boundary assets that are not
    # in every checkout, and tests/test_report_figure_rasterisers.py covers it where they are.
    for kind in ChartKind:
        b.add(ChartBlock(kind=kind, series=(("Material", 562.5), ("Labour", 900.0)),
                         title=f"{kind.value} figure", unit="INR", caption="Fig 3"))
    b.add(MetricRowBlock(metrics=(("Designs", "12", ""), ("Cost", "3905", "INR"))))
    b.add(CalloutBlock(kind="WARNING", title="Care", runs=runs_of("watch the weft")))
    b.add(SpacerBlock(height_pct=4.0))
    b.add(PageBreakBlock())
    b.add(SignatureBlock(signatories=(("A", "Designer"), ("B", "Officer"))))


# --------------------------------------------------------------------------------------
# Package structure
# --------------------------------------------------------------------------------------


def test_every_block_type_survives_a_round_trip():
    z, _doc, dropped = _render(_all_blocks)
    assert dropped == []
    assert z.testzip() is None
    for part in z.namelist():
        if part.endswith((".xml", ".rels")):
            ET.fromstring(z.read(part))  # raises on malformed XML


def test_well_known_parts_carry_their_exact_content_type():
    """The .docx FILE mime on the main document PART passes every structural check and is
    rejected by Word with no explanation. This is that regression."""
    z, _doc, _ = _render(_all_blocks)
    ct = ET.fromstring(z.read("[Content_Types].xml"))
    declared = {o.get("PartName"): o.get("ContentType") for o in ct.findall(CT + "Override")}
    for part, expected in EXPECTED_CONTENT_TYPES.items():
        assert declared.get(part) == expected, f"{part} declared as {declared.get(part)!r}"


def test_every_part_has_a_content_type():
    z, _doc, _ = _render(_all_blocks)
    ct = ET.fromstring(z.read("[Content_Types].xml"))
    defaults = {d.get("Extension").lower() for d in ct.findall(CT + "Default")}
    overrides = {o.get("PartName") for o in ct.findall(CT + "Override")}
    for name in z.namelist():
        if name == "[Content_Types].xml" or f"/{name}" in overrides:
            continue
        assert name.rsplit(".", 1)[-1].lower() in defaults, name


def test_every_relationship_reference_resolves():
    z, doc, _ = _render(_all_blocks)
    rels = ET.fromstring(z.read("word/_rels/document.xml.rels"))
    declared = {r.get("Id"): r.get("Target") for r in rels.findall(REL + "Relationship")}
    used = set(re.findall(r'r:(?:id|embed)="([^"]+)"', doc))
    assert used, "the fixture should reference at least the header, footer and images"
    for rid in used:
        assert rid in declared, f"{rid} used in document.xml but not declared"
    for target in declared.values():
        assert f"word/{target}" in z.namelist(), target


def test_relationship_ids_are_unique():
    z, _doc, _ = _render(_all_blocks)
    rels = ET.fromstring(z.read("word/_rels/document.xml.rels"))
    ids = [r.get("Id") for r in rels.findall(REL + "Relationship")]
    assert len(ids) == len(set(ids))


def test_body_ends_with_exactly_one_sectpr():
    z, _doc, _ = _render(_all_blocks)
    body = ET.fromstring(z.read("word/document.xml")).find(W + "body")
    children = list(body)
    assert children[-1].tag == W + "sectPr"
    assert sum(1 for c in children if c.tag == W + "sectPr") == 1


def test_no_two_adjacent_tables():
    """Word merges them silently: the second table's rows appear inside the first."""
    z, _doc, _ = _render(_all_blocks)
    body = ET.fromstring(z.read("word/document.xml")).find(W + "body")
    tags = [c.tag for c in body]
    for i in range(len(tags) - 1):
        assert not (tags[i] == W + "tbl" and tags[i + 1] == W + "tbl"), f"adjacent tbl at {i}"


def test_adjacent_table_guard_actually_fires():
    """Prove the guard is live rather than vacuously true, by defeating the trailing paragraph."""
    from app.services.report_docx import DocxWriter

    b = DocumentBuilder(meta=_meta())
    b.add(TableBlock(columns=(TableColumn("a", 100.0),), rows=((runs_of("x"),),)))
    writer = DocxWriter(b.build(), _loader)
    writer._emit_blocks()
    writer._body = [s for s in writer._body if s.startswith("<w:tbl>")] * 2
    with pytest.raises(ValueError, match="adjacent tables"):
        writer._validate_body()


def test_referenced_styles_and_numbering_are_defined():
    z, doc, _ = _render(_all_blocks)
    styles = ET.fromstring(z.read("word/styles.xml"))
    defined = {s.get(W + "styleId") for s in styles.findall(W + "style")}
    for used in set(re.findall(r'<w:pStyle w:val="([^"]+)"', doc)):
        assert used in defined, used

    numbering = ET.fromstring(z.read("word/numbering.xml"))
    num_ids = {n.get(W + "numId") for n in numbering.findall(W + "num")}
    for used in set(re.findall(r'<w:numId w:val="([^"]+)"', doc)):
        assert used in num_ids, used


def test_header_and_footer_are_wired_to_the_section():
    z, doc, _ = _render(_all_blocks)
    assert "<w:headerReference" in doc and "<w:footerReference" in doc
    # titlePg suppresses both on the cover; without it the running head prints over the
    # ministry line and the cover is numbered as page 1 of the body.
    assert "<w:titlePg/>" in doc
    footer = z.read("word/footer1.xml").decode()
    assert "PAGE" in footer and "NUMPAGES" in footer


def test_toc_is_emitted_as_a_field_and_fields_auto_update():
    z, doc, _ = _render(_all_blocks)
    assert "TOC \\o" in doc
    assert '<w:updateFields w:val="true"/>' in z.read("word/settings.xml").decode()


def test_headings_carry_outline_levels_so_the_toc_can_find_them():
    _z, doc, _ = _render(lambda b: (b.heading("A", 1), b.heading("B", 2)))
    assert '<w:outlineLvl w:val="0"/>' in doc
    assert '<w:outlineLvl w:val="1"/>' in doc


# --------------------------------------------------------------------------------------
# Escaping — the second half of the single door
# --------------------------------------------------------------------------------------


def test_xml_entities_are_escaped_in_body_text():
    z, doc, _ = _render(lambda b: b.para('Weft <must> be "combed" & set'))
    assert "&lt;must&gt;" in doc and "&amp;" in doc and "&quot;" in doc
    # And it still parses, which is the point.
    ET.fromstring(z.read("word/document.xml"))


def test_illegal_codepoints_never_reach_the_part():
    z, _doc, _ = _render(lambda b: b.para("a" + chr(0xD83D) + chr(0x07) + "b"))
    ET.fromstring(z.read("word/document.xml"))


def test_entities_are_escaped_in_headings_captions_and_cells():
    def build(b):
        b.heading("R&D", 1)
        b.add(TableBlock(columns=(TableColumn("A&B", 100.0),),
                         rows=((runs_of("x<y"),),), caption="c&c"))
    z, doc, _ = _render(build)
    ET.fromstring(z.read("word/document.xml"))
    assert "R&amp;D" in doc and "A&amp;B" in doc and "x&lt;y" in doc


def test_metadata_is_escaped():
    b = DocumentBuilder(meta=_meta(title='Ikat & "Bandha"', author="A & B"))
    b.para("x")
    data, _ = render_docx(b.build(), _loader)
    z = zipfile.ZipFile(BytesIO(data))
    ET.fromstring(z.read("docProps/core.xml"))


# --------------------------------------------------------------------------------------
# Images
# --------------------------------------------------------------------------------------


def test_probe_image_size_reads_png_and_jpeg():
    assert probe_image_size(_png(123, 45)) == (123, 45)
    assert probe_image_size(_jpeg(640, 480)) == (640, 480)
    assert probe_image_size(b"not an image") is None


def test_repeated_image_is_embedded_once():
    """A photo used on the prototype page and again in the annexure doubled the file size."""
    def build(b):
        ref = ImageRef("p", 400, 300, mime_type="image/png")
        b.add(ImageBlock(image=ref))
        b.add(ImageBlock(image=ref))
    z, doc, _ = _render(build)
    assert len([n for n in z.namelist() if n.startswith("word/media/")]) == 1
    assert doc.count("<w:drawing>") == 2


def test_unloadable_image_is_dropped_with_a_warning_not_an_exception():
    """A report missing one photo is still worth having in the field."""
    z, doc, dropped = _render(
        lambda b: b.add(ImageBlock(image=ImageRef("nope", 10, 10), caption="gone"))
    )
    assert dropped == ["nope"]
    assert "<w:drawing>" not in doc
    ET.fromstring(z.read("word/document.xml"))


def test_non_image_bytes_are_dropped_rather_than_embedded():
    """Embedding an undecodable part puts a red X in the document, which reads as an app bug."""
    b = DocumentBuilder(meta=_meta())
    b.add(ImageBlock(image=ImageRef("bad", 10, 10)))
    data, dropped = render_docx(b.build(), lambda ref: b"definitely not a picture")
    assert dropped == ["bad"]
    assert "<w:drawing>" not in zipfile.ZipFile(BytesIO(data)).read("word/document.xml").decode()


def test_image_extension_follows_the_magic_bytes_not_the_declared_mime():
    """The media table has held "image/jpeg" for PNG bytes since the client guessed from the
    file extension; trusting the declaration writes a .jpeg part Word cannot decode."""
    z, _doc, _ = _render(
        lambda b: b.add(ImageBlock(image=ImageRef("p", 400, 300, mime_type="image/jpeg")))
    )
    media = [n for n in z.namelist() if n.startswith("word/media/")]
    assert media == ["word/media/image1.png"]


def test_rotated_portrait_keeps_its_aspect_ratio():
    _z, doc, _ = _render(
        lambda b: b.add(ImageBlock(
            image=ImageRef("portrait", 300, 400, rotation_deg=90, mime_type="image/png"),
            width_pct=50.0,
        ))
    )
    cx, cy = (int(v) for v in re.search(r'<wp:extent cx="(\d+)" cy="(\d+)"/>', doc).groups())
    # 300x400 rotated a quarter turn displays as 400x300, i.e. landscape 4:3.
    assert cx / cy == pytest.approx(4 / 3, rel=0.02)


def test_image_never_exceeds_the_text_column():
    _z, doc, _ = _render(
        lambda b: b.add(ImageBlock(image=ImageRef("p", 4000, 300, mime_type="image/png"),
                                   width_pct=100.0))
    )
    cx = int(re.search(r'<wp:extent cx="(\d+)"', doc).group(1))
    # A4 minus two 25 mm margins = 160 mm, at 36000 EMU/mm.
    assert cx <= (160 * 36000) + 1


def test_image_grid_drops_columns_rather_than_shrink_past_legibility():
    """Below ~45 mm a photo stops being evidence. Six requested columns on A4 cannot all fit."""
    _z, doc, _ = _render(
        lambda b: b.add(ImageGridBlock(
            images=tuple((ImageRef("p", 400, 300, mime_type="image/png"), "c") for _ in range(6)),
            columns=6,
        ))
    )
    grid_cols = len(re.search(r"<w:tblGrid>(.*?)</w:tblGrid>", doc).group(1).split("<w:gridCol")) - 1
    assert grid_cols <= 3


# --------------------------------------------------------------------------------------
# Tables
# --------------------------------------------------------------------------------------


def test_table_header_repeats_across_pages_and_cannot_split():
    _z, doc, _ = _render(
        lambda b: b.add(TableBlock(columns=(TableColumn("A", 100.0),), rows=((runs_of("x"),),)))
    )
    # cantSplit must precede tblHeader: CT_TrPr is a sequence, not a set.
    assert "<w:trPr><w:cantSplit/><w:tblHeader/></w:trPr>" in doc


def test_table_borders_precede_layout_in_tblpr():
    _z, doc, _ = _render(
        lambda b: b.add(TableBlock(columns=(TableColumn("A", 100.0),), rows=((runs_of("x"),),)))
    )
    tbl_pr = re.search(r"<w:tblPr>(.*?)</w:tblPr>", doc).group(1)
    assert tbl_pr.index("<w:tblBorders>") < tbl_pr.index("<w:tblLayout")


def test_short_row_is_padded_rather_than_dropping_cells():
    """A ragged row would otherwise produce a table Word renders with a missing column."""
    z, _doc, _ = _render(
        lambda b: b.add(TableBlock(
            columns=(TableColumn("A", 50.0), TableColumn("B", 50.0)),
            rows=((runs_of("only one"),),),
        ))
    )
    body = ET.fromstring(z.read("word/document.xml")).find(W + "body")
    tbl = next(c for c in body if c.tag == W + "tbl")
    for row in tbl.findall(W + "tr"):
        assert len(row.findall(W + "tc")) == 2


def test_table_column_widths_sum_to_the_text_column():
    _z, doc, _ = _render(
        lambda b: b.add(TableBlock(
            columns=(TableColumn("A", 30.0), TableColumn("B", 70.0)),
            rows=((runs_of("x"), runs_of("y")),),
        ))
    )
    grid = re.search(r"<w:tblGrid>(.*?)</w:tblGrid>", doc).group(1)
    widths = [int(w) for w in re.findall(r'<w:gridCol w:w="(\d+)"/>', grid)]
    total = int(re.search(r'<w:tblW w:w="(\d+)"', doc).group(1))
    assert sum(widths) == pytest.approx(total, abs=2)


def test_empty_cell_still_contains_a_paragraph():
    """w:tc must hold at least one block-level element; Word drops the whole row otherwise."""
    z, _doc, _ = _render(
        lambda b: b.add(TableBlock(columns=(TableColumn("A", 100.0),), rows=(((),),)))
    )
    body = ET.fromstring(z.read("word/document.xml")).find(W + "body")
    tbl = next(c for c in body if c.tag == W + "tbl")
    for cell in tbl.iter(W + "tc"):
        assert cell.find(W + "p") is not None


# --------------------------------------------------------------------------------------
# Scripts
# --------------------------------------------------------------------------------------


def test_indic_runs_get_a_complex_script_font_and_latin_runs_do_not():
    """w:cs is the only lever a .docx has over shaping. Tagging a Latin run with it changes
    typeface mid-sentence."""
    _z, doc, _ = _render(lambda b: b.para("Ikat (ସମ୍ବଲପୁରୀ) weave"))
    runs = re.findall(r"<w:r>(.*?)</w:r>", doc, re.DOTALL)
    indic = [r for r in runs if "ସ" in r]
    latin = [r for r in runs if "Ikat" in r]
    assert indic and all('w:cs="Nirmala UI"' in r for r in indic)
    assert latin and all("w:cs=" not in r for r in latin)


def test_restyled_runs_keep_every_property_they_were_not_restyled_for():
    """A quote is italicised and a total row bolded by REBUILDING each run, and for a while both
    rebuilt it positionally — ``Run(r.text, r.bold, True, r.script, r.color)``.

    That was correct until RICH_TEXT inserted ``underline`` and ``strike`` at positions four and
    five, after which the Script landed in ``underline`` (truthy for every script there is) and
    the colour in ``strike``. Every artisan verbatim came out UNDERLINED, every cost sheet's total
    row underlined and struck through, and both lost the ``w:cs`` that is the only thing making an
    Odia quote render as words instead of boxes. Nothing raised and the file opened perfectly.

    ``DocxWriter.kt`` used ``copy()`` and was never wrong, so this was also a DIVERGENCE: the same
    workshop's quote was underlined in the office's download and not on the phone.
    """
    def build(b):
        b.para("Combed weft (ସମ୍ବଲପୁରୀ).", style=ParaStyle.QUOTE)
        b.add(TableBlock(
            columns=(TableColumn("Item", 60.0), TableColumn("Amount", 40.0, numeric=True)),
            rows=((runs_of("Yarn"), runs_of("562.50")),),
            total_row=(runs_of("Total"), runs_of("562.50")),
        ))

    _z, doc, _ = _render(build)
    assert "<w:u " not in doc, "a restyled run picked up an underline it was never given"
    assert "<w:strike/>" not in doc, "a restyled run picked up a strikethrough"
    # And the Indic run inside the quote still carries the complex-script face.
    indic = [r for r in re.findall(r"<w:r>(.*?)</w:r>", doc, re.DOTALL) if "ସ" in r]
    assert indic and all('w:cs="Nirmala UI"' in r for r in indic), \
        "the quote lost its script, so Word shapes an Odia verbatim with the Latin face"


def test_newline_inside_a_run_becomes_a_line_break():
    _z, doc, _ = _render(lambda b: b.para("first\nsecond"))
    assert "<w:br/>" in doc
