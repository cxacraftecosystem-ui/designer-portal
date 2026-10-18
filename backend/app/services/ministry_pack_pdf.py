"""THE MINISTRY PACK'S PDF: a one- or two-page summary beside the workbook.

Title, period and scheme; a month-to-date banner when the month is not over; the headline figures;
the top ten states; and the data-protection footer. It names nobody — the workbook carries designer
names, this page carries counts. Fonts come from ``report_pdf.register_fonts`` so a state or scheme
name in an Indian script draws instead of printing boxes.

Synchronous (reportlab); the caller runs it in ``asyncio.to_thread``.
"""

from __future__ import annotations

from datetime import UTC, datetime
from io import BytesIO
from typing import Any

from app.services.ministry_pack_sheets import DATA_PROTECTION_NOTE, indian_number
from app.services.report_model import Script, script_of

PDF_MIME = "application/pdf"

_INK = (0.12, 0.14, 0.16)
_MUTED = (0.42, 0.39, 0.34)
_ACCENT = (0.71, 0.40, 0.11)
_RULE = (0.89, 0.88, 0.85)
_BANNER = (0.98, 0.95, 0.90)

TOP_STATES = 10


def _font_for(fonts: Any, text: str, *, bold: bool = False) -> str:
    """The face for a string: the first non-Latin script in it decides, else the Latin pair."""
    for ch in text:
        script = script_of(ch)
        if script != Script.LATIN:
            return fonts.pick_for(script, bold=bold)
    return fonts.bold if bold else fonts.regular


def _ist_text(moment: Any) -> str:
    from app.services.mailer import format_moment

    if not isinstance(moment, datetime):
        return ""
    return format_moment(moment if moment.tzinfo else moment.replace(tzinfo=UTC))


def build_pack_pdf(summary: dict[str, Any]) -> bytes:
    """Render the summary. ``summary`` keys: ``periodLabel``, ``schemeLabel``, ``snapshotAt``,
    ``monthToDate``, ``revision``, ``counts`` (``compute_counts``), ``byState`` (``by_state``),
    ``cap``, ``planYearLabel``, ``planned``, ``promoted``."""
    from reportlab.lib.pagesizes import A4
    from reportlab.pdfgen import canvas

    from app.services.report_pdf import register_fonts

    fonts = register_fonts()
    buffer = BytesIO()
    pdf = canvas.Canvas(buffer, pagesize=A4, pageCompression=1)
    pdf.setTitle(f"Ministry pack — {summary.get('schemeLabel')} — {summary.get('periodLabel')}")
    pdf.setAuthor("Design Prototype Workshop")
    width, height = A4
    margin = 48
    counts = summary.get("counts") or {}

    def text(
        x: float,
        y: float,
        value: str,
        size: float,
        *,
        bold: bool = False,
        color=_INK,
        right: bool = False,
    ) -> None:
        pdf.setFillColorRGB(*color)
        pdf.setFont(_font_for(fonts, value, bold=bold), size)
        if right:
            pdf.drawRightString(x, y, value)
        else:
            pdf.drawString(x, y, value)

    def rule(y: float) -> None:
        pdf.setStrokeColorRGB(*_RULE)
        pdf.setLineWidth(0.8)
        pdf.line(margin, y, width - margin, y)

    def footer() -> None:
        rule(margin + 30)
        text(margin, margin + 16, DATA_PROTECTION_NOTE[:118], 7.5, color=_MUTED)
        if len(DATA_PROTECTION_NOTE) > 118:
            text(margin, margin + 6, DATA_PROTECTION_NOTE[118:], 7.5, color=_MUTED)

    y = height - margin
    text(margin, y - 4, "MINISTRY PACK", 9, bold=True, color=_ACCENT)
    y -= 30
    text(margin, y, str(summary.get("schemeLabel") or ""), 22, bold=True)
    y -= 22
    text(margin, y, str(summary.get("periodLabel") or ""), 13, color=_MUTED)
    stamp = (
        f"Snapshot {_ist_text(summary.get('snapshotAt'))} · revision {summary.get('revision') or 1}"
    )
    text(width - margin, y, stamp, 8.5, color=_MUTED, right=True)
    y -= 16

    if summary.get("monthToDate"):
        y -= 8
        pdf.setFillColorRGB(*_BANNER)
        pdf.roundRect(margin, y - 22, width - 2 * margin, 26, 5, stroke=0, fill=1)
        text(
            margin + 10,
            y - 13,
            "Month to date — this month is not over; figures cover it up to the snapshot.",
            9.5,
            bold=True,
            color=_ACCENT,
        )
        y -= 32
    if counts.get("truncated"):
        y -= 4
        text(
            margin,
            y - 8,
            f"Capped at {summary.get('cap')} workshops — the scheme has more. Figures cover the rows in the pack.",
            9,
            color=_ACCENT,
        )
        y -= 18

    rule(y)
    y -= 26

    def tiles(title: str, items: list[tuple[str, str]], top: float) -> float:
        text(margin, top, title, 10, bold=True)
        top -= 14
        columns = 4
        tile_w = (width - 2 * margin) / columns
        for index, (label, value) in enumerate(items):
            row, col = divmod(index, columns)
            x = margin + col * tile_w
            ty = top - row * 46
            text(x, ty - 20, value, 18, bold=True)
            text(x, ty - 34, label, 8.5, color=_MUTED)
        rows = (len(items) + columns - 1) // columns
        return top - rows * 46 - 10

    y = tiles(
        "Standing now",
        [
            ("Workshops", indian_number(counts.get("workshops", 0))),
            ("Newly registered", indian_number(counts.get("registered", 0))),
            ("Ongoing", indian_number(counts.get("ongoing", 0))),
            ("Completed", indian_number(counts.get("completed", 0))),
            ("Artisans", indian_number(counts.get("participants", 0))),
            ("Sanctioned (₹)", indian_number(counts.get("sanctionedAmount", "0"))),
        ],
        y,
    )
    y = tiles(
        f"During {summary.get('periodLabel') or 'the period'}",
        [
            ("Registered", indian_number(counts.get("createdInPeriod", 0))),
            ("Last handed in", indian_number(counts.get("handedInInPeriod", 0))),
            ("Approved", indian_number(counts.get("approvedInPeriod", 0))),
            ("Handed on", indian_number(counts.get("handedOnInPeriod", 0))),
        ],
        y,
    )
    if summary.get("planYearLabel"):
        y = tiles(
            f"Annual plan {summary['planYearLabel']}",
            [
                ("Planned", indian_number(summary.get("planned", 0))),
                ("Opened as workshops", indian_number(summary.get("promoted", 0))),
            ],
            y,
        )

    states = list(summary.get("byState") or [])[:TOP_STATES]
    if states:
        rule(y + 4)
        y -= 14
        text(margin, y, "Top states", 10, bold=True)
        y -= 18
        cols = [margin, width - margin - 220, width - margin - 120, width - margin]
        text(cols[0], y, "State", 8.5, bold=True, color=_MUTED)
        text(cols[1] + 60, y, "Workshops", 8.5, bold=True, color=_MUTED, right=True)
        text(cols[2] + 60, y, "Completed", 8.5, bold=True, color=_MUTED, right=True)
        text(cols[3], y, "Artisans", 8.5, bold=True, color=_MUTED, right=True)
        y -= 6
        for entry in states:
            if y < margin + 60:
                footer()
                pdf.showPage()
                y = height - margin
            y -= 16
            text(cols[0], y, str(entry.get("state") or "")[:60], 9.5)
            text(cols[1] + 60, y, indian_number(entry.get("workshops", 0)), 9.5, right=True)
            text(cols[2] + 60, y, indian_number(entry.get("completed", 0)), 9.5, right=True)
            text(cols[3], y, indian_number(entry.get("participants", 0)), 9.5, right=True)

    footer()
    pdf.showPage()
    pdf.save()
    return buffer.getvalue()
