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
    return datetime(year, month, day)  # noqa: DTZ001




def _sheet_bytes(
    rows: list[list[Any]],
    *,
    title: str = "Annual plan",
    details: list[list[Any]] | None = None,
    header: list[Any] | None = None,
) -> bytes:
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
    values: dict[str, Any] = {key: None for key, _label in _PRO_FORMA_COLUMNS}
    values.update(overrides)
    return [values[key] for key, _label in _PRO_FORMA_COLUMNS]


def _problems(result, severity: str | None = None) -> list:
    return [p for p in result.problems if severity is None or p.severity == severity]



def test_the_pro_forma_opens_without_excel_offering_to_repair_it():
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
    wb = load_workbook(BytesIO(build_annual_plan_pro_forma()))
    plan = wb["Annual plan"]
    assert plan.max_row == 1, "the pro-forma must be empty under its headings"


def test_the_instructions_sheet_is_not_even_a_candidate_for_the_data_sheet():
    with pytest.raises(AnnualPlanXlsxError) as excinfo:
        parse_annual_plan_workbook(build_annual_plan_pro_forma())
    assert "no rows under it" in str(excinfo.value)

    filled = build_annual_plan_workbook(
        plan_year=2026, rows=[{"workshopNo": "DPW/2026/017", "venue": "DIC Hall"}]
    )
    parsed = parse_annual_plan_workbook(filled)
    assert parsed.sheet == "Annual plan"
    assert parsed.problems == [], "our own file must upload with an empty problem panel"



def test_an_unfiltered_export_declares_no_filter_and_is_unchanged_by_the_marker():
    plain = build_annual_plan_workbook(plan_year=2026, rows=[{"workshopNo": "DPW/2026/017"}])
    assert parse_annual_plan_workbook(plain).filterNote is None

    sheet = load_workbook(BytesIO(plain))["Details"]
    labels = [sheet.cell(row=r, column=1).value for r in range(1, 4)]
    assert labels[0] == "Plan year"
    assert labels[1] is None, "a marker row was written for an export that has no filter"


def test_a_filtered_export_declares_its_filter_and_reads_it_back():
    data = build_annual_plan_workbook(
        plan_year=2026,
        rows=[{"workshopNo": "DPW/2026/017"}],
        filter_note="Search: 'Bhuj' · State: Gujarat",
    )
    parsed = parse_annual_plan_workbook(data)
    assert parsed.filterNote == "Search: 'Bhuj' · State: Gujarat"
    assert parsed.filterNote == parsed.payload()["filterNote"]
    assert parsed.planYear == 2026
    assert parsed.problems == [], "declaring a filter must not warn about anything"
    assert len(parsed.rows) == 1, "and must not cost a data row"

    details = load_workbook(BytesIO(data))["Details"]
    assert details.cell(row=2, column=1).value == "Filtered view"
    assert "FILTERED VIEW" in str(details.cell(row=3, column=1).value)
    assert "withdraw" in str(details.cell(row=3, column=1).value)


def test_a_declared_filter_is_bounded_before_it_reaches_a_refusal_message():
    data = build_annual_plan_workbook(
        plan_year=2026, rows=[{"workshopNo": "DPW/2026/017"}], filter_note="x" * 5000
    )
    note = parse_annual_plan_workbook(data).filterNote
    assert note is not None and len(note) == MAX_FILTER_NOTE_CHARS


def test_a_sheet_that_declares_no_filter_is_not_assumed_to_be_partial():
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
    rows = [
        {"workshopNo": f"DPW/2026/{n:03d}", "plannedStartDate": date(2026, 3, n)}
        for n in range(1, 13)
    ]
    parsed = parse_annual_plan_workbook(build_annual_plan_workbook(plan_year=2026, rows=rows))
    assert _problems(parsed, "warning") == []
    assert [r.plannedStartDate for r in parsed.rows] == [date(2026, 3, n) for n in range(1, 13)]



def test_a_serial_number_column_is_never_mistaken_for_a_workshop_number():
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
    assert (
        fold_workshop_no("DPW/2026/017")
        == fold_workshop_no("dpw/2026/017 ")
        == fold_workshop_no("DPW/2026/ 017")
        == "DPW/2026/ 017".replace("/ ", "/ ")
        or fold_workshop_no("DPW/2026/ 017") == "DPW/2026/ 017"
    )
    assert len({fold_workshop_no(v) for v in ("DPW/2026/017", "dpw/2026/017 ", " DPW/2026/017")}) == 1


def test_punctuation_is_not_folded_out_of_a_workshop_number():
    assert fold_workshop_no("DPW/2026/017") != fold_workshop_no("DPW-2026-017")


def test_a_row_with_no_workshop_number_is_reported_and_is_the_only_row_skipped():
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



def test_a_real_excel_date_is_taken_exactly_as_excel_resolved_it():
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017", plannedStartDate=_excel_datetime(2026, 4, 3))])
    )
    assert parsed.rows[0].plannedStartDate == date(2026, 4, 3)
    assert parsed.problems == []


def test_a_text_date_is_read_day_first_and_the_ambiguity_is_reported():
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017", plannedStartDate="03/04/2026")])
    )
    assert parsed.rows[0].plannedStartDate == date(2026, 4, 3)
    warnings = _problems(parsed, "warning")
    assert len(warnings) == 1
    assert "2026-04-03" in warnings[0].reason
    assert "2026-03-04" in warnings[0].reason


def test_an_unambiguous_text_date_is_read_without_a_warning():
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017", plannedStartDate="25/12/2026")])
    )
    assert parsed.rows[0].plannedStartDate == date(2026, 12, 25)
    assert parsed.problems == []


def test_an_unreadable_date_keeps_the_row_and_reports_the_cell():
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



def test_an_unrecognised_column_is_reported_by_name():
    header = [*HEADINGS, "Sanctioned Amount", "Officer"]
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([[*_row(workshopNo="DPW/2026/017"), "250000", "Shri A"]], header=header)
    )
    warnings = [p for p in parsed.problems if "not recognised" in p.reason]
    assert len(warnings) == 1
    assert "Sanctioned Amount" in warnings[0].reason
    assert "Officer" in warnings[0].reason


def test_blank_padding_columns_are_not_reported_as_unrecognised():
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
    rows = [
        _row(workshopNo="DPW/2026/017"),
        [None] * len(HEADINGS),
        _row(workshopNo="DPW/2026/018"),
    ]
    parsed = parse_annual_plan_workbook(_sheet_bytes(rows))
    assert len(parsed.rows) == 2
    assert parsed.problems == []



def test_an_unknown_workshop_kind_is_left_null_and_reported():
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
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017", workshopKind=typed)])
    )
    assert parsed.rows[0].workshopKind == expected
    assert parsed.problems == []


def test_a_state_spelling_the_list_does_not_know_is_kept_and_reported():
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017", state="Kutchistan")])
    )
    assert parsed.rows[0].state == "Kutchistan"
    warnings = _problems(parsed, "warning")
    assert len(warnings) == 1
    assert "Kutchistan" in warnings[0].reason


def test_a_known_state_spelling_is_resolved_to_its_canonical_name():
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017", state="uttar  pradesh", district="Varanasi")])
    )
    assert parsed.rows[0].state == "Uttar Pradesh"
    assert parsed.rows[0].district == "Varanasi"
    assert parsed.problems == []


def test_a_bad_district_is_not_reported_twice_when_the_state_was_the_mistake():
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017", state="Kutchistan", district="Nowhere")])
    )
    assert len(_problems(parsed, "warning")) == 1



def test_no_clip_cap_is_exceeded_by_anything_the_parser_stores():
    long = "अ" * 2_500
    parsed = parse_annual_plan_workbook(
        _sheet_bytes(
            [
                _row(
                    workshopNo=long,
                    plannedTitle=long,
                    craftName=long,
                    clusterName=long,
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
    rows = [_row(workshopNo=f"DPW/2026/{n:04d}") for n in range(MAX_PLAN_ROWS + 5)]
    parsed = parse_annual_plan_workbook(_sheet_bytes(rows))
    assert len(parsed.rows) == MAX_PLAN_ROWS
    errors = _problems(parsed, "error")
    assert len(errors) == 1
    assert str(MAX_PLAN_ROWS) in errors[0].reason



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
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017")], details=[["Plan year", typed]])
    )
    assert parsed.planYear == 2026
    assert parsed.problems == []


def test_a_plan_year_typed_as_a_number_or_stored_as_a_date_still_reads():
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
    parsed = parse_annual_plan_workbook(
        _sheet_bytes([_row(workshopNo="DPW/2026/017")], details=[["Plan year", typed]])
    )
    assert parsed.planYear is None
    assert len(_problems(parsed, "error")) == 1


def test_the_filename_is_the_last_fallback_for_the_year_and_the_details_sheet_outranks_it():
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
    assert plan_year_label(year) == label


def test_the_export_filename_is_pure_ascii():
    name = export_filename(2026)
    assert name == "DesignWorkshop_Annual-Plan_FY2026-27_Export.xlsx"
    assert name.encode("ascii")



def test_a_non_workbook_is_refused_by_its_magic_bytes_and_not_its_extension():
    ole2 = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + b"\x00" * 128
    with pytest.raises(AnnualPlanXlsxError) as excinfo:
        parse_annual_plan_workbook(ole2, filename="plan.xlsx")
    assert "older .xls format" in str(excinfo.value)


def test_an_empty_upload_is_refused_with_the_pro_forma_named():
    with pytest.raises(AnnualPlanXlsxError) as excinfo:
        parse_annual_plan_workbook(b"")
    assert "annual plan pro-forma" in str(excinfo.value)


def test_a_formula_with_no_cached_result_is_reported_as_a_formula():
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
