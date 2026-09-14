"""The annual-plan pro-forma and the reader that gets a ministry's directory back out of it.

Every test here is a sentence about something that would otherwise go wrong SILENTLY — with a 201,
with every count adding up, and with nothing on any screen able to show it. The three worst are
first, because they are the three a reviewer should read before anything else in this file:

* a serial number mistaken for a workshop number rewrites every row below an insertion;
* ``03/04/2026`` read month-first schedules a workshop a month out;
* a clip cap looser than the stage-1 FieldSpec it is promoted into writes a column with no stage
  entry behind it, which the designer's first stage-1 save nulls.

openpyxl only. No database, no network, nothing to bring up.
"""

from datetime import date, datetime
from io import BytesIO
from typing import Any
from zipfile import ZipFile

import pytest
from openpyxl import Workbook, load_workbook

from app.services.annual_plan_xlsx import (
    _PRO_FORMA_COLUMNS,
    _TEXT_CAPS,
    MAX_FILTER_NOTE_CHARS,
    MAX_PLAN_ROWS,
    MAX_PLAN_YEAR,
    MIN_PLAN_YEAR,
    AnnualPlanXlsxError,
    build_annual_plan_pro_forma,
    build_annual_plan_workbook,
    export_filename,
    fold_workshop_no,
    parse_annual_plan_workbook,
    plan_year_label,
)
from app.services.stage_schema import ENUMS

HEADINGS = [label for _key, label in _PRO_FORMA_COLUMNS]

def _excel_datetime(year: int, month: int, day: int) -> datetime:
    """A NAIVE ``datetime``, which is what an Excel date cell IS and the only thing openpyxl writes.

    ruff's DTZ001 is right about instants and wrong about spreadsheet dates: openpyxl raises
    ``ValueError: Excel does not support timezones in datetimes`` for a tz-aware value, and a
    workshop planned for 12 March is planned for 12 March in Bhuj and in Delhi alike. Said once,
    here, rather than as a bare ``noqa`` at every fixture that needs a date cell.
    """
    return datetime(year, month, day)  # noqa: DTZ001




def _sheet_bytes(
    rows: list[list[Any]],
    *,
    title: str = "Annual plan",
    details: list[list[Any]] | None = None,
    header: list[Any] | None = None,
) -> bytes:
    """A workbook with *rows* under *header* (the pro-forma headings unless told otherwise)."""
    wb = Workbook()
    ws = wb.active
    ws.title = title
    ws.append(list(header if header is not None else HEADINGS))
    for row in rows:
        ws.append(list(row))
    if details is not None:
        detail_sheet = wb.create_sheet(title="Details")
        for row in details:
            detail_sheet.append(list(row))
    buffer = BytesIO()
    wb.save(buffer)
    return buffer.getvalue()


def _row(**overrides: Any) -> list[Any]:
    """One data row in the pro-forma's column order, keyed by role."""
    values: dict[str, Any] = {key: None for key, _label in _PRO_FORMA_COLUMNS}
    values.update(overrides)
    return [values[key] for key, _label in _PRO_FORMA_COLUMNS]


def _problems(result, severity: str | None = None) -> list:
    return [p for p in result.problems if severity is None or p.severity == severity]


# --------------------------------------------------------------------------------------
# The pro-forma itself
# --------------------------------------------------------------------------------------


def test_the_pro_forma_opens_without_excel_offering_to_repair_it():
    """Every XML part parses, there are three sheets, and the headings are the parser's own.

    "Excel found unreadable content" on a ministry's pro-forma is a support call this product cannot
    answer remotely, and it is produced by exactly one thing: a cell written round ``_put``.
    """
    data = build_annual_plan_pro_forma()
    with ZipFile(BytesIO(data)) as bundle:
        from xml.etree import ElementTree

        for name in bundle.namelist():
            if name.endswith((".xml", ".rels")):
                ElementTree.fromstring(bundle.read(name))
    wb = load_workbook(BytesIO(data))
    assert wb.sheetnames == ["Annual plan", "Details", "How to fill this in"]
    plan = wb["Annual plan"]
    assert [c.value for c in plan[1]][: len(HEADINGS)] == HEADINGS


def test_the_pro_forma_carries_no_example_rows():
    """Nothing under row 1 of 'Annual plan'. A seeded example row is a row somebody uploads.

    The worked example lives on the instructions sheet, where it cannot be imported — that sheet has
    no header row the parser will accept, and the sheet actually named 'Annual plan' wins the pick.
    """
    wb = load_workbook(BytesIO(build_annual_plan_pro_forma()))
    plan = wb["Annual plan"]
    assert plan.max_row == 1, "the pro-forma must be empty under its headings"


def test_the_instructions_sheet_is_not_even_a_candidate_for_the_data_sheet():
    """Uploading the blank pro-forma back is refused for having no ROWS, with NOTHING said about a
    second plausible sheet.

    The instructions sheet carries a worked example of the real table. Written as separate cells,
    its first line IS a header row by every rule ``scan_header`` applies, so it became a second
    candidate and ``pick_sheet`` warned "More than one sheet looked like an annual plan" on every
    upload of a file this app itself wrote. A warning that fires on a CORRECT file is how an
    administrator learns to ignore the panel that also tells them a column of real data was skipped.
    """
    with pytest.raises(AnnualPlanXlsxError) as excinfo:
        parse_annual_plan_workbook(build_annual_plan_pro_forma())
    assert "no rows under it" in str(excinfo.value)

    filled = build_annual_plan_workbook(
        plan_year=2026, rows=[{"workshopNo": "DPW/2026/017", "venue": "DIC Hall"}]
    )
    parsed = parse_annual_plan_workbook(filled)
    assert parsed.sheet == "Annual plan"
    assert parsed.problems == [], "our own file must upload with an empty problem panel"


# --------------------------------------------------------------------------------------
# The round trip
# --------------------------------------------------------------------------------------


def test_an_unfiltered_export_declares_no_filter_and_is_unchanged_by_the_marker():
    """The ordinary whole-year download must be byte-for-byte what it always was.

    The "Filtered view" row is written ONLY when there is a filter. If it were written always, the
    upload's ``withdrawAbsent`` refusal would fire on every file this product has ever handed out,
    the feature would be dead rather than guarded, and somebody would delete the guard rather than
    the cause.
    """
    plain = build_annual_plan_workbook(plan_year=2026, rows=[{"workshopNo": "DPW/2026/017"}])
    assert parse_annual_plan_workbook(plain).filterNote is None

    sheet = load_workbook(BytesIO(plain))["Details"]
    labels = [sheet.cell(row=r, column=1).value for r in range(1, 4)]
    assert labels[0] == "Plan year"
    assert labels[1] is None, "a marker row was written for an export that has no filter"


def test_a_filtered_export_declares_its_filter_and_reads_it_back():
    """**THE ROUND TRIP THE WITHDRAW GUARD IS BUILT ON.**

    An upload sees bytes and a form field and NEVER the query string the download was taken under,
    so the workbook is the only place the fact that this is forty rows of three hundred can survive
    the trip. Without it, `apply_parsed_plan` — which reads the comparison set as the whole year
    unconditionally — treats the two hundred and sixty rows the filter hid as absent, and with the
    box ticked stamps `withdrawnAt` on every one of them.
    """
    data = build_annual_plan_workbook(
        plan_year=2026,
        rows=[{"workshopNo": "DPW/2026/017"}],
        filter_note="Search: 'Bhuj' · State: Gujarat",
    )
    parsed = parse_annual_plan_workbook(data)
    assert parsed.filterNote == "Search: 'Bhuj' · State: Gujarat"
    assert parsed.filterNote == parsed.payload()["filterNote"]
    # THE YEAR STILL READS, which is the thing a second Details row is most likely to break: the
    # marker sits between the year and the note and `_details_plan_year` scans by LABEL, not by row.
    assert parsed.planYear == 2026
    assert parsed.problems == [], "declaring a filter must not warn about anything"
    assert len(parsed.rows) == 1, "and must not cost a data row"

    # AND IT IS READABLE BY THE PERSON WHO OPENS THE FILE, not only by the parser. The note under it
    # is the one that says this sheet cannot be used to withdraw anything.
    details = load_workbook(BytesIO(data))["Details"]
    assert details.cell(row=2, column=1).value == "Filtered view"
    assert "FILTERED VIEW" in str(details.cell(row=3, column=1).value)
    assert "withdraw" in str(details.cell(row=3, column=1).value)


def test_a_declared_filter_is_bounded_before_it_reaches_a_refusal_message():
    """It is untrusted text off an uploaded file and it lands in an HTTP ``detail`` string.

    Nobody's filter sentence is two hundred characters long; a value that is, is somebody's
    paragraph typed into the wrong cell, and the refusal has to stay readable rather than faithful.
    """
    data = build_annual_plan_workbook(
        plan_year=2026, rows=[{"workshopNo": "DPW/2026/017"}], filter_note="x" * 5000
    )
    note = parse_annual_plan_workbook(data).filterNote
    assert note is not None and len(note) == MAX_FILTER_NOTE_CHARS


def test_a_sheet_that_declares_no_filter_is_not_assumed_to_be_partial():
    """``None`` means "this sheet does not SAY it is partial" and nothing stronger.

    A directory typed from scratch in Excel, or a file exported before the marker existed, has no
    Details sheet marker and must go on uploading exactly as it did. The refusal that reads this is
    worded as a refusal of a sheet that SAYS it is partial, never as a promise about every sheet
    that does not.
    """
    wb = Workbook()
    ws = wb.active
    ws.title = "Annual plan"
    ws.append(HEADINGS)
    ws.append(["DPW/2026/017"] + [""] * (len(HEADINGS) - 1))
    buffer = BytesIO()
    wb.save(buffer)
    parsed = parse_annual_plan_workbook(buffer.getvalue(), filename="annual-plan-2026-27.xlsx")
    assert parsed.filterNote is None


def test_a_generated_workbook_parses_back_to_the_rows_that_built_it():
    """Download, change nothing, upload: field for field, the same directory.

    This is the property that makes edit-in-Excel safe to offer at all. If a column can be lost in
    either direction, this feature quietly deletes a ministry's planning every time it is used as
    intended.
    """
    rows = [
        {
            "workshopNo": "DPW/2026/017",
            "plannedTitle": "Kachchh weaving, Bhuj",
            "workshopKind": "DESIGN_PROTOTYPE_DEVELOPMENT",
            "craftName": "Kachchh weaving",
            "clusterName": "Bhujodi",
            "state": "Gujarat",
            "district": "Kachchh",
            "venue": "DIC Hall, Bhuj",
            "plannedStartDate": date(2026, 3, 12),
            "plannedEndDate": date(2026, 3, 26),
            "implementingAgency": "DC Handicrafts",
            "sponsor": "Ministry of Textiles",
            "notes": "Two looms to be moved",
        }
    ]
    data = build_annual_plan_workbook(plan_year=2026, rows=rows)
    parsed = parse_annual_plan_workbook(data)
    assert parsed.planYear == 2026
    assert len(parsed.rows) == 1
    got = parsed.rows[0]
    for key, value in rows[0].items():
        assert getattr(got, key) == value, key


def test_a_generated_workbooks_dates_round_trip_without_an_ambiguity_warning():
    """A file THIS APP WROTE must produce ZERO date warnings when read back.

    The ambiguity warning fires on every text date whose day is at or below twelve — which is most
    of them. If the writer emitted text rather than real date cells, every export/import cycle would
    produce a screenful of warnings about the app's own file, and an administrator would learn that
    the panel means nothing. That is how the ONE warning that matters gets ignored.
    """
    rows = [
        {"workshopNo": f"DPW/2026/{n:03d}", "plannedStartDate": date(2026, 3, n)}
        for n in range(1, 13)
    ]
    parsed = parse_annual_plan_workbook(build_annual_plan_workbook(plan_year=2026, rows=rows))
    assert _problems(parsed, "warning") == []
    assert [r.plannedStartDate for r in parsed.rows] == [date(2026, 3, n) for n in range(1, 13)]


# --------------------------------------------------------------------------------------
# The key
# --------------------------------------------------------------------------------------


def test_a_serial_number_column_is_never_mistaken_for_a_workshop_number():
    """THE MOST DANGEROUS HEADING IN THIS DOCUMENT, in both directions.

    With a real reference column beside it, the serial number is recognised, discarded, and does not
    raise an unknown-column warning. With NO reference column, the sheet is refused WHOLE and the
    refusal names the heading it needs — because keying three hundred rows on 1, 2, 3 means a later
    sheet with one workshop inserted at the top rewrites every row below it with its neighbour's
    data, silently, under a 201.
    """
    header = ["Sl. No.", *HEADINGS]
    rows = [[n, *_row(workshopNo=f"DPW/2026/{n:03d}")] for n in (1, 2, 3)]
    parsed = parse_annual_plan_workbook(_sheet_bytes(rows, header=header))
    assert [r.workshopNo for r in parsed.rows] == ["DPW/2026/001", "DPW/2026/002", "DPW/2026/003"]
    assert [r.workshopNoKey for r in parsed.rows] == [
        "DPW/2026/001",
        "DPW/2026/002",
        "DPW/2026/003",
    ]
    assert not any("not recognised" in p.reason for p in parsed.problems)

    with pytest.raises(AnnualPlanXlsxError) as excinfo:
        parse_annual_plan_workbook(
            _sheet_bytes([[1], [2], [3]], header=["Sl. No.", "Venue"])
        )
    message = str(excinfo.value)
    assert "Workshop No." in message
    assert "serial number" in message


def test_the_same_reference_typed_three_ways_folds_to_one_key():
    """Trailing space, lower case and a pasted non-breaking space are one workshop, not three."""
    assert (
        fold_workshop_no("DPW/2026/017")
        == fold_workshop_no("dpw/2026/017 ")
        == fold_workshop_no("DPW/2026/ 017")
        == "DPW/2026/ 017".replace("/ ", "/ ")  # normalised NBSP becomes one ordinary space
        or fold_workshop_no("DPW/2026/ 017") == "DPW/2026/ 017"
    )
    assert len({fold_workshop_no(v) for v in ("DPW/2026/017", "dpw/2026/017 ", " DPW/2026/017")}) == 1


def test_punctuation_is_not_folded_out_of_a_workshop_number():
    """"DPW/2026/017" and "DPW-2026-017" stay TWO references.

    They are two different strings in the ministry's own document. Folding them together would merge
    two plan rows on a guess, and the merge is unrecoverable: the loser's venue, district and date
    are gone and nothing records that a second row ever existed.
    """
    assert fold_workshop_no("DPW/2026/017") != fold_workshop_no("DPW-2026-017")


def test_a_row_with_no_workshop_number_is_reported_and_is_the_only_row_skipped():
    """The other columns' data is named in the problem, so the row can be found in a re-sorted sheet."""
    rows = [
        _row(workshopNo="DPW/2026/017", venue="DIC Hall, Bhuj"),
        _row(workshopNo=None, venue="Kala Kendra, Jaipur", district="Jaipur"),
        _row(workshopNo="DPW/2026/019"),
    ]
    parsed = parse_annual_plan_workbook(_sheet_bytes(rows))
    assert [r.workshopNo for r in parsed.rows] == ["DPW/2026/017", "DPW/2026/019"]
    errors = _problems(parsed, "error")
    assert len(errors) == 1
    assert errors[0].row == 3
    assert "no Workshop No." in errors[0].reason
    assert "Kala Kendra, Jaipur" in (errors[0].value or "")


def test_two_rows_with_the_same_number_report_both_excel_rows_and_import_one():
    """The in-sheet duplicate is caught HERE, which is what keeps the database write all-or-nothing.

    ``@@unique([planYear, workshopNoKey])`` is the only constraint an upload can fire. Letting it
    fire inside the transaction would roll a whole three-hundred-row directory back over a mistake
    the uploader could have been told about while reading. Both Excel rows are named because the
    uploader has to look at both to decide which is right.
    """
    rows = [
        _row(workshopNo="DPW/2026/017", venue="A"),
        _row(workshopNo="dpw/2026/017 ", venue="B"),
    ]
    parsed = parse_annual_plan_workbook(_sheet_bytes(rows))
    assert len(parsed.rows) == 1
    assert parsed.rows[0].venue == "A"
    errors = _problems(parsed, "error")
    assert len(errors) == 1
    assert errors[0].row == 3
    assert "row 2" in errors[0].reason


# --------------------------------------------------------------------------------------
# Dates
# --------------------------------------------------------------------------------------


def test_a_real_excel_date_is_taken_exactly_as_excel_resolved_it():
    """No rule of ours is applied to a real date cell, and nothing is reported.

    Excel has already turned its serial number into a day. Re-reading the string form of that and
    applying a day-first rule is how a CORRECTLY typed sheet gets corrupted.
    """
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017", plannedStartDate=_excel_datetime(2026, 4, 3))])
    )
    assert parsed.rows[0].plannedStartDate == date(2026, 4, 3)
    assert parsed.problems == []


def test_a_text_date_is_read_day_first_and_the_ambiguity_is_reported():
    """``03/04/2026`` is 3 April here, and the other reading is named in the warning.

    Both readings are valid dates; neither raises; the row imports either way. This is the one case
    where the parser could be silently wrong, so it is the one case the administrator is shown.
    """
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017", plannedStartDate="03/04/2026")])
    )
    assert parsed.rows[0].plannedStartDate == date(2026, 4, 3)
    warnings = _problems(parsed, "warning")
    assert len(warnings) == 1
    assert "2026-04-03" in warnings[0].reason
    assert "2026-03-04" in warnings[0].reason


def test_an_unambiguous_text_date_is_read_without_a_warning():
    """25/12/2026 has only one reading, so saying anything about it would be noise."""
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017", plannedStartDate="25/12/2026")])
    )
    assert parsed.rows[0].plannedStartDate == date(2026, 12, 25)
    assert parsed.problems == []


def test_an_unreadable_date_keeps_the_row_and_reports_the_cell():
    """``"error"`` on a DATE cell means THIS CELL was not stored, not that the row was dropped.

    This is the single place in the parser where ``"error"`` does not mean a lost row, and it is
    deliberate: a planned workshop with a bad date is still a workshop the ministry planned, and
    refusing the row would lose the district and the venue with it.
    """
    parsed = parse_annual_plan_workbook(
        _sheet_bytes(
            [_row(workshopNo="DPW/2026/017", venue="DIC Hall", plannedStartDate="next Tuesday")]
        )
    )
    assert len(parsed.rows) == 1
    assert parsed.rows[0].plannedStartDate is None
    assert parsed.rows[0].venue == "DIC Hall"
    errors = _problems(parsed, "error")
    assert len(errors) == 1
    assert errors[0].row == 2
    assert errors[0].value == "next Tuesday"


def test_an_end_date_before_the_start_date_keeps_both_and_says_so():
    """Both are what the ministry's document SAYS. A parser that drops the one it thinks is wrong has
    destroyed the evidence that the sheet needs correcting."""
    parsed = parse_annual_plan_workbook(
        _sheet_bytes(
            [
                _row(
                    workshopNo="DPW/2026/017",
                    plannedStartDate=_excel_datetime(2026, 4, 20),
                    plannedEndDate=_excel_datetime(2026, 4, 15),
                )
            ]
        )
    )
    assert parsed.rows[0].plannedStartDate == date(2026, 4, 20)
    assert parsed.rows[0].plannedEndDate == date(2026, 4, 15)
    assert any("before the Start Date" in p.reason for p in _problems(parsed, "warning"))


# --------------------------------------------------------------------------------------
# Columns
# --------------------------------------------------------------------------------------


def test_an_unrecognised_column_is_reported_by_name():
    """ONE warning listing every unknown heading — this parser's one strictness over the
    questionnaire's, because a ministry directory's columns are all data and one that was skipped is
    a column somebody filled in and nobody stored, with no symptom anywhere."""
    header = [*HEADINGS, "Sanctioned Amount", "Officer"]
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([[*_row(workshopNo="DPW/2026/017"), "250000", "Shri A"]], header=header)
    )
    warnings = [p for p in parsed.problems if "not recognised" in p.reason]
    assert len(warnings) == 1
    assert "Sanctioned Amount" in warnings[0].reason
    assert "Officer" in warnings[0].reason


def test_blank_padding_columns_are_not_reported_as_unrecognised():
    """Stray formatting out to column BZ must not produce ", , , , , …" on an ordinary upload.

    openpyxl pads every row out to ``ws.max_column``, and a normalised empty heading matches no
    alias — so without the non-empty filter, EVERY ordinary file warns about forty nameless columns.
    That teaches an administrator to ignore this panel, which is the panel that tells them a column
    of real data was skipped.
    """
    wb = Workbook()
    ws = wb.active
    ws.title = "Annual plan"
    ws.append(HEADINGS)
    ws.append(_row(workshopNo="DPW/2026/017"))
    from openpyxl.styles import PatternFill

    ws.cell(row=1, column=60).fill = PatternFill(fill_type="solid", start_color="FFEEEEEE")
    ws.cell(row=1, column=60).value = None
    ws.cell(row=1, column=45).value = "   "
    buffer = BytesIO()
    wb.save(buffer)

    parsed = parse_annual_plan_workbook(buffer.getvalue())
    assert ws.max_column >= 45
    assert [p for p in parsed.problems if "not recognised" in p.reason] == []


def test_renamed_reordered_and_pushed_down_columns_still_parse():
    """The header row is FOUND, not assumed, and columns are located by heading and not by position.

    A ministry sheet has a title block, a logo and a merged banner above its headings, and its
    columns are in whatever order the office that built it liked.
    """
    wb = Workbook()
    ws = wb.active
    ws.title = "Sheet1"
    ws.append(["ANNUAL PLAN OF WORKSHOPS"])
    ws.append([])
    ws.append(["Office of the Development Commissioner (Handicrafts)"])
    ws.append([])
    ws.append(["Venue", "District", "State", "Workshop Number", "Date of Workshop"])
    ws.append(["DIC Hall, Bhuj", "Kachchh", "Gujarat", "DPW/2026/017", _excel_datetime(2026, 3, 12)])
    buffer = BytesIO()
    wb.save(buffer)

    parsed = parse_annual_plan_workbook(buffer.getvalue())
    assert len(parsed.rows) == 1
    row = parsed.rows[0]
    assert row.workshopNo == "DPW/2026/017"
    assert row.state == "Gujarat"
    assert row.district == "Kachchh"
    assert row.venue == "DIC Hall, Bhuj"
    assert row.plannedStartDate == date(2026, 3, 12)
    assert row.sheetRow == 6, "the gutter row number is what the uploader presses Ctrl+G with"


def test_a_blank_spacer_row_is_skipped_in_silence():
    """The ONE silent skip. Ministry directories carry a blank line under every state's block, and a
    problem per blank line buries the two that matter under forty that do not."""
    rows = [
        _row(workshopNo="DPW/2026/017"),
        [None] * len(HEADINGS),
        _row(workshopNo="DPW/2026/018"),
    ]
    parsed = parse_annual_plan_workbook(_sheet_bytes(rows))
    assert len(parsed.rows) == 2
    assert parsed.problems == []


# --------------------------------------------------------------------------------------
# Vocabularies
# --------------------------------------------------------------------------------------


def test_an_unknown_workshop_kind_is_left_null_and_reported():
    """NEVER STORED RAW. The kind is promoted onto ``DesignWorkshop.workshopKind``, which the list
    narrows by and the report cover prints; a kind in the ministry's own words that the registry has
    never heard of would reach a dropdown that cannot show it."""
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017", workshopKind="Capacity Building")])
    )
    assert parsed.rows[0].workshopKind is None
    warnings = _problems(parsed, "warning")
    assert len(warnings) == 1
    for label in ENUMS["WORKSHOP_KIND"].values():
        assert label in warnings[0].reason


@pytest.mark.parametrize(
    "typed,expected",
    [
        ("Design & Prototype Development", "DESIGN_PROTOTYPE_DEVELOPMENT"),
        ("design prototype development", "DESIGN_PROTOTYPE_DEVELOPMENT"),
        ("SKILL_UPGRADATION", "SKILL_UPGRADATION"),
        ("Skill Upgradation", "SKILL_UPGRADATION"),
    ],
)
def test_a_workshop_kind_is_matched_on_its_label_or_its_token(typed: str, expected: str):
    """An office types the printable label; an exported file carries the label too; a hand-edited
    file may carry the token. All three land on the registry's own value."""
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017", workshopKind=typed)])
    )
    assert parsed.rows[0].workshopKind == expected
    assert parsed.problems == []


def test_a_state_spelling_the_list_does_not_know_is_kept_and_reported():
    """STORED AS TYPED and reported, never dropped. A ministry sheet's spelling is evidence, and
    silently blanking it loses the only copy anybody has — the decision ``normalize_state`` argues."""
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017", state="Kutchistan")])
    )
    assert parsed.rows[0].state == "Kutchistan"
    warnings = _problems(parsed, "warning")
    assert len(warnings) == 1
    assert "Kutchistan" in warnings[0].reason


def test_a_known_state_spelling_is_resolved_to_its_canonical_name():
    """"uttar  pradesh" and "Uttar Pradesh" are one state. (The two-letter form "UP" is NOT an alias
    ``normalize_state`` knows — checked on disk 2026-09-13 — so it would be kept as typed and
    reported, which is the honest answer and is covered by the test above.)"""
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017", state="uttar  pradesh", district="Varanasi")])
    )
    assert parsed.rows[0].state == "Uttar Pradesh"
    assert parsed.rows[0].district == "Varanasi"
    assert parsed.problems == []


def test_a_bad_district_is_not_reported_twice_when_the_state_was_the_mistake():
    """One row, one mistake. A district can only be checked against a state the list knows, so
    reporting both makes the panel read as two problems where there is one."""
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017", state="Kutchistan", district="Nowhere")])
    )
    assert len(_problems(parsed, "warning")) == 1


# --------------------------------------------------------------------------------------
# Caps and ceilings
# --------------------------------------------------------------------------------------


def test_no_clip_cap_is_exceeded_by_anything_the_parser_stores():
    """Every stored text field is within its cap, and every clip is reported.

    The cap is not a style choice: a value longer than the stage-1 FieldSpec it is promoted into is
    DROPPED AND ONLY LOGGED by ``validate_entry(enforce_required=False)``, leaving a promoted COLUMN
    with no stage entry behind it — which the designer's first stage-1 save nulls under a 200.
    """
    long = "अ" * 2_500
    parsed = parse_annual_plan_workbook(
        _sheet_bytes(
            [
                _row(
                    workshopNo=long,
                    plannedTitle=long,
                    craftName=long,
                    clusterName=long,
                    # STATE AND DISTRICT ARE IN THIS FIXTURE ON PURPOSE. They are written by
                    # `normalize_state`/`normalize_district`, which return an unrecognised value
                    # TRIMMED BUT NOT BOUNDED — so they bypass the ordinary clip loop entirely and
                    # need their own. This assertion is what found that they did not have one.
                    state=long,
                    district=long,
                    venue=long,
                    implementingAgency=long,
                    sponsor=long,
                    notes=long,
                )
            ]
        )
    )
    row = parsed.rows[0]
    for field_name, cap in _TEXT_CAPS.items():
        value = getattr(row, field_name)
        assert value is not None and len(value) <= cap, field_name
    clipped = [p for p in _problems(parsed, "warning") if "shortened" in p.reason]
    assert len(clipped) == len(_TEXT_CAPS), "one warning per clipped column, and none silent"


def test_the_ceiling_stops_the_read_and_says_so():
    """``MAX_PLAN_ROWS`` rows import, the rest do not, and the refusal names the number and the row
    it stopped at. An unbounded read turns one upload into unbounded work on a 1 GiB box."""
    rows = [_row(workshopNo=f"DPW/2026/{n:04d}") for n in range(MAX_PLAN_ROWS + 5)]
    parsed = parse_annual_plan_workbook(_sheet_bytes(rows))
    assert len(parsed.rows) == MAX_PLAN_ROWS
    errors = _problems(parsed, "error")
    assert len(errors) == 1
    assert str(MAX_PLAN_ROWS) in errors[0].reason


# --------------------------------------------------------------------------------------
# The plan year
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "typed",
    [
        "2026",
        "2026-27",
        "2026-2027",
        "2026/27",
        "FY 2026-27",
        "F.Y. 2026-2027",
        "Financial Year 2026-27",
    ],
)
def test_every_way_a_person_writes_a_financial_year_reads_as_one_int(typed: str):
    """Seven spellings of one year, and none of them is a problem.

    This is one cell on a sheet nobody proof-reads, and getting it wrong files three hundred rows
    under a year the document does not claim — the one mistake a re-upload cannot repair, because
    the corrected sheet lands in the right year and the wrong three hundred stay where they are.
    """
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017")], details=[["Plan year", typed]])
    )
    assert parsed.planYear == 2026
    assert parsed.problems == []


def test_a_plan_year_typed_as_a_number_or_stored_as_a_date_still_reads():
    """Excel turns "2026-27" typed into a General cell into a DATE, every time. The year is still
    recoverable, so it is taken — and said out loud, because the administrator's workbook now shows
    a date where they typed a financial year and they will meet that again next year."""
    numeric = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017")], details=[["Plan year", 2026]])
    )
    assert numeric.planYear == 2026
    assert numeric.problems == []

    dated = parse_annual_plan_workbook(
        _sheet_bytes(
            [_row(workshopNo="DPW/2026/017")], details=[["Plan year", _excel_datetime(2026, 4, 1)]]
        )
    )
    assert dated.planYear == 2026
    warnings = _problems(dated, "warning")
    assert len(warnings) == 1
    assert "as a date" in warnings[0].reason
    assert "2026" in warnings[0].reason


@pytest.mark.parametrize("typed", ["17", "1899", "2500"])
def test_a_plan_year_outside_the_bound_is_refused_with_the_number_in_the_sentence(typed: str):
    """Below the floor somebody typed a row number into the year box; above it, a typo. Either way
    the honest answer is "I do not know which year this is"."""
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017")], details=[["Plan year", typed]])
    )
    assert parsed.planYear is None
    errors = _problems(parsed, "error")
    assert len(errors) == 1
    assert str(MIN_PLAN_YEAR) in errors[0].reason
    assert str(MAX_PLAN_YEAR) in errors[0].reason


@pytest.mark.parametrize("typed", ["2026-29", "2026-2030"])
def test_two_years_that_are_not_one_financial_year_are_refused(typed: str):
    """A financial year runs from one April to the next. Quietly keeping the first half would file a
    three-year programme as a one-year plan."""
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017")], details=[["Plan year", typed]])
    )
    assert parsed.planYear is None
    assert len(_problems(parsed, "error")) == 1


def test_the_filename_is_the_last_fallback_for_the_year_and_the_details_sheet_outranks_it():
    """A sheet that names its own year wins over the filename; a sheet that names none falls back.
    Neither invents one — the route refuses the upload when all three sources are silent."""
    named = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017")], details=[["Plan year", "2026-27"]]),
        filename="AnnualPlan_2019.xlsx",
    )
    assert named.planYear == 2026

    unnamed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017")]), filename="AnnualPlan_2026-27_rev3.xlsx"
    )
    assert unnamed.planYear == 2026

    silent = parse_annual_plan_workbook(_sheet_bytes([_row(workshopNo="DPW/2026/017")]))
    assert silent.planYear is None


@pytest.mark.parametrize("year,label", [(2026, "2026-27"), (2099, "2099-00"), (None, "—")])
def test_the_year_label_is_rendered_and_never_stored(year, label):
    """One integer in the column, one function that prints it. A stored "2026-27" would need a
    normalisation rule spelled twice — the trap the identity keys carry."""
    assert plan_year_label(year) == label


def test_the_export_filename_is_pure_ascii():
    """``xlsx_response`` writes a plain ``filename="…"`` header, which is sufficient precisely
    because nothing here can emit a non-ASCII character. Said out loud so nobody replaces the simple
    header with the RFC 6266 form the record downloads need."""
    name = export_filename(2026)
    assert name == "annual-plan-2026-27.xlsx"
    assert name.encode("ascii")


# --------------------------------------------------------------------------------------
# Whole-file refusals
# --------------------------------------------------------------------------------------


def test_a_non_workbook_is_refused_by_its_magic_bytes_and_not_its_extension():
    """Somebody renamed report.xls to report.xlsx to get past an upload filter. The message has to
    describe the FILE rather than the label somebody put on it, and openpyxl raises no
    ``InvalidFileException`` for a ``BytesIO`` because it has no filename to inspect."""
    ole2 = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + b"\x00" * 128
    with pytest.raises(AnnualPlanXlsxError) as excinfo:
        parse_annual_plan_workbook(ole2, filename="plan.xlsx")
    assert "older .xls format" in str(excinfo.value)


def test_an_empty_upload_is_refused_with_the_pro_forma_named():
    with pytest.raises(AnnualPlanXlsxError) as excinfo:
        parse_annual_plan_workbook(b"")
    assert "annual plan pro-forma" in str(excinfo.value)


def test_a_formula_with_no_cached_result_is_reported_as_a_formula():
    """The ``data_only=True`` blind spot: a workbook written by a script has formulas and no cached
    results, so those cells read as EMPTY. "Row 12 is blank" for a row that visibly contains a
    formula on the uploader's screen is the most confusing thing this parser could say."""
    wb = Workbook()
    ws = wb.active
    ws.title = "Annual plan"
    ws.append(HEADINGS)
    ws.append(_row(workshopNo="DPW/2026/017"))
    start = HEADINGS.index("Start Date") + 1
    ws.cell(row=2, column=start).value = "=TODAY()"
    buffer = BytesIO()
    wb.save(buffer)

    parsed = parse_annual_plan_workbook(buffer.getvalue())
    assert parsed.rows[0].plannedStartDate is None
    warnings = [p for p in _problems(parsed, "warning") if "formula" in p.reason]
    assert len(warnings) == 1
    assert "F9" in warnings[0].reason
