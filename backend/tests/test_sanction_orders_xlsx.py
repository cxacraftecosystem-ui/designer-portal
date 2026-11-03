from __future__ import annotations

import inspect
import io
from datetime import date
from decimal import Decimal

import pytest
from openpyxl import Workbook, load_workbook

from app.services import sanction_orders_xlsx as reader
from app.services.sanction_orders_xlsx import (
    MAX_DESIGNERS_PER_ROW,
    MAX_SANCTION_ROWS,
    SanctionXlsxError,
    build_sanction_pro_forma,
    fold_name,
    fold_order_no,
    parse_sanction_workbook,
    split_list_cell,
)
from app.services.xlsx_table import FormulaCell, ParseProblem, norm_header

HEADINGS = [
    "Sanction Order No.",
    "Sanction Date",
    "Sanctioned Amount",
    "Designer Name(s)",
    "Designer Email(s)",
    "Remarks",
]


def sheet_of(rows: list[tuple], *, headings: list[str] | None = None) -> bytes:
    wb = Workbook()
    ws = wb.active
    ws.title = reader.SHEET_ORDERS
    for index, label in enumerate(headings or HEADINGS, start=1):
        ws.cell(row=1, column=index, value=label)
    for r, values in enumerate(rows, start=2):
        for c, value in enumerate(values, start=1):
            ws.cell(row=r, column=c, value=value)
    buffer = io.BytesIO()
    wb.save(buffer)
    return buffer.getvalue()


def problems_on(parsed, row: int, severity: str | None = None) -> list[ParseProblem]:
    return [
        problem
        for problem in parsed.problems
        if problem.row == row and (severity is None or problem.severity == severity)
    ]



def test_every_pro_forma_heading_is_one_this_reader_finds() -> None:
    for role, label in reader._PRO_FORMA_COLUMNS:
        assert norm_header(label) in reader._COLUMN_ALIASES[role], (role, label, norm_header(label))


def test_the_pro_forma_has_no_row_anybody_could_upload() -> None:
    wb = load_workbook(io.BytesIO(build_sanction_pro_forma()))
    assert wb.sheetnames == [reader.SHEET_ORDERS, reader.SHEET_HELP]
    orders = wb[reader.SHEET_ORDERS]
    assert [cell.value for cell in orders[1]] == HEADINGS
    assert orders.max_row == 1, [
        [cell.value for cell in row] for row in orders.iter_rows(min_row=2)
    ]


def test_the_instructions_sheet_is_not_even_a_candidate_for_the_data_sheet() -> None:
    wb = load_workbook(io.BytesIO(build_sanction_pro_forma()))
    every_alias = {alias for aliases in reader._COLUMN_ALIASES.values() for alias in aliases}
    for row in wb[reader.SHEET_HELP].iter_rows():
        for cell in row:
            assert norm_header(cell.value) not in every_alias, cell.value


def test_the_pro_forma_round_trips_through_its_own_reader() -> None:
    wb = load_workbook(io.BytesIO(build_sanction_pro_forma()))
    ws = wb[reader.SHEET_ORDERS]
    ws.append(["SO/2026/42", date(2026, 4, 3), 450000, "Ramesh Kumar", "r.kumar@gmail.com", "ok"])
    buffer = io.BytesIO()
    wb.save(buffer)

    parsed = parse_sanction_workbook(buffer.getvalue(), filename="orders.xlsx")
    assert parsed.sheet == reader.SHEET_ORDERS
    assert parsed.sourceFilename == "orders.xlsx"
    assert len(parsed.rows) == 1
    row = parsed.rows[0]
    assert row.sanctionOrderNo == "SO/2026/42"
    assert row.sanctionOrderDate == date(2026, 4, 3)
    assert row.sanctionAmount == Decimal("450000.00")
    assert row.designerNames == ["Ramesh Kumar"]
    assert row.designerEmails == ["r.kumar@gmail.com"]
    assert row.notes == "ok"
    assert problems_on(parsed, 2) == []



def test_no_clip_cap_exceeds_the_field_it_is_written_into() -> None:
    from app.schemas.sanction_orders import SanctionOrderCreate

    for role, cap in reader._TEXT_CAPS.items():
        field_name = reader._CAP_MIRRORS[role]
        field = SanctionOrderCreate.model_fields[field_name]
        bounds = [
            meta.max_length
            for meta in field.metadata
            if getattr(meta, "max_length", None) is not None
        ]
        assert bounds, field_name
        assert cap <= bounds[0], (role, field_name, cap, bounds[0])


def test_the_in_file_fold_is_the_registers_own_fold() -> None:
    from app.services.sanction_orders import normalise_sanction_order_no

    for spelling in (
        "SO/2026/42",
        "SO-2026-42",
        "so 2026 42",
        "  so/2026/42  ",
        "So.2026.42",
        "",
        "///",
        "2026",
    ):
        assert fold_order_no(spelling) == normalise_sanction_order_no(spelling), spelling



@pytest.mark.parametrize(
    ("cell", "expected"),
    [
        (450000, Decimal("450000.00")),
        (450000.10, Decimal("450000.1")),
        ("450000.00", Decimal("450000.00")),
        ("6,25,000/-", Decimal("625000.00")),
        ("Rs. 1,00,000", Decimal("100000.00")),
        ("₹ 45,000.50", Decimal("45000.50")),
        ("INR 45000", Decimal("45000.00")),
    ],
)
def test_the_amount_survives_every_spelling_an_office_uses(cell, expected) -> None:
    problems: list[ParseProblem] = []
    assert reader.read_amount(cell, sheet="s", row=2, problems=problems) == expected
    assert [p for p in problems if p.severity == "error"] == []


def test_a_numeric_cell_never_becomes_a_binary_float() -> None:
    problems: list[ParseProblem] = []
    got = reader.read_amount(0.1, sheet="s", row=2, problems=problems)
    assert got == Decimal("0.1")
    assert str(got) == "0.1"
    assert got != Decimal(0.1)  # noqa: RUF032


def test_more_than_two_decimal_places_is_rounded_and_said_rather_than_refused() -> None:
    problems: list[ParseProblem] = []
    assert reader.read_amount("100.005", sheet="s", row=2, problems=problems) == Decimal("100.00")
    assert len(problems) == 1
    assert problems[0].severity == "warning"
    assert "two decimal places" in problems[0].reason


@pytest.mark.parametrize("cell", ["abc", "45000 to 50000", "forty five thousand"])
def test_an_amount_with_no_single_figure_in_it_is_refused_and_not_guessed(cell) -> None:
    problems: list[ParseProblem] = []
    assert reader.read_amount(cell, sheet="s", row=2, problems=problems) is None
    assert problems and problems[0].severity == "error"


def test_zero_and_negative_amounts_are_refused() -> None:
    for cell in (0, "0.00", -5):
        problems: list[ParseProblem] = []
        assert reader.read_amount(cell, sheet="s", row=2, problems=problems) is None
        assert problems and problems[0].severity == "error"


def test_a_formula_with_no_cached_result_is_an_error_and_not_an_empty_cell() -> None:
    problems: list[ParseProblem] = []
    assert reader.read_amount(FormulaCell("=B2*2"), sheet="s", row=2, problems=problems) is None
    assert problems[0].severity == "error"
    assert "formula" in problems[0].reason



def test_a_comma_is_the_only_separator() -> None:
    problems: list[ParseProblem] = []
    got = split_list_cell(
        "Ramesh Kumar, Kavita Rao ,Anil Shah",
        sheet="s",
        row=2,
        column_label="Designer Name(s)",
        problems=problems,
    )
    assert got == ["Ramesh Kumar", "Kavita Rao", "Anil Shah"]
    assert problems == []


@pytest.mark.parametrize("cell", ["Ramesh\nKavita", "Ramesh;Kavita", "Ramesh\r\nKavita"])
def test_a_line_break_or_a_semicolon_is_reported_and_never_split_on(cell) -> None:
    problems: list[ParseProblem] = []
    got = split_list_cell(
        cell, sheet="s", row=2, column_label="Designer Name(s)", problems=problems
    )
    assert got == ["Ramesh Kavita"]
    assert len(problems) == 1
    assert problems[0].severity == "warning"
    assert "line break or a semicolon" in problems[0].reason


def test_an_empty_segment_is_dropped_and_counted_before_anything_compares_the_counts() -> None:
    problems: list[ParseProblem] = []
    assert split_list_cell(
        "Ramesh, , Kavita", sheet="s", row=2, column_label="Designer Name(s)", problems=problems
    ) == ["Ramesh", "Kavita"]
    assert problems[0].severity == "warning"
    assert "empty" in problems[0].reason

    trailing: list[ParseProblem] = []
    assert split_list_cell(
        "a@x.com,b@x.com,", sheet="s", row=2, column_label="Designer Email(s)", problems=trailing
    ) == ["a@x.com", "b@x.com"]
    assert trailing and trailing[0].severity == "warning"


def test_whitespace_of_every_invisible_kind_is_folded_silently() -> None:
    problems: list[ParseProblem] = []
    got = split_list_cell(
        "Ramesh \u200b Kumar ,\u3000Kavita Rao",
        sheet="s",
        row=2,
        column_label="Designer Name(s)",
        problems=problems,
    )
    assert got == ["Ramesh Kumar", "Kavita Rao"]
    assert problems == []


def test_a_cell_with_a_whole_column_pasted_into_it_is_cut_and_said() -> None:
    problems: list[ParseProblem] = []
    got = split_list_cell(
        ",".join(f"n{i}" for i in range(MAX_DESIGNERS_PER_ROW + 5)),
        sheet="s",
        row=2,
        column_label="Designer Name(s)",
        problems=problems,
    )
    assert len(got) == MAX_DESIGNERS_PER_ROW
    assert problems and problems[0].severity == "error"


def test_the_two_columns_keep_their_positions_through_the_reader() -> None:
    parsed = parse_sanction_workbook(
        sheet_of(
            [
                (
                    "SO/1",
                    "03/04/2026",
                    "1000",
                    "Kavita Rao, Anil Shah, Ramesh Kumar",
                    "k@x.com, a@x.com, r@x.com",
                    None,
                )
            ]
        )
    )
    row = parsed.rows[0]
    assert row.designerNames == ["Kavita Rao", "Anil Shah", "Ramesh Kumar"]
    assert row.designerEmails == ["k@x.com", "a@x.com", "r@x.com"]


def test_fold_name_is_for_comparison_and_never_for_storage() -> None:
    assert fold_name("RAMESH  KUMAR") == fold_name("Ramesh Kumar")
    assert fold_name("Ramesh Kumar") == fold_name("ramesh kumar")
    parsed = parse_sanction_workbook(
        sheet_of([("SO/1", "03/04/2026", "1000", "RAMESH  KUMAR", "r@x.com", None)])
    )
    assert parsed.rows[0].designerNames == ["RAMESH KUMAR"]



def test_a_row_with_no_order_number_is_reported_and_not_dropped_in_silence() -> None:
    parsed = parse_sanction_workbook(
        sheet_of(
            [
                ("SO/1", "03/04/2026", "1000", "A", "a@x.com", None),
                (None, "04/04/2026", "2000", "B", "b@x.com", None),
            ]
        )
    )
    assert [row.sanctionOrderNo for row in parsed.rows] == ["SO/1"]
    assert problems_on(parsed, 3, "error"), parsed.problems


def test_two_rows_with_one_number_name_each_other() -> None:
    parsed = parse_sanction_workbook(
        sheet_of(
            [
                ("SO/2026/42", "03/04/2026", "1000", "A", "a@x.com", None),
                ("SO-2026-42", "04/04/2026", "2000", "B", "b@x.com", None),
            ]
        )
    )
    assert len(parsed.rows) == 1
    problem = problems_on(parsed, 3, "error")[0]
    assert "row 2 of this sheet" in problem.reason


def test_a_blank_spacer_row_is_the_one_silent_skip() -> None:
    parsed = parse_sanction_workbook(
        sheet_of(
            [
                ("SO/1", "03/04/2026", "1000", "A", "a@x.com", None),
                (None, None, None, None, None, None),
                ("SO/2", "04/04/2026", "2000", "B", "b@x.com", None),
            ]
        )
    )
    assert [row.sanctionOrderNo for row in parsed.rows] == ["SO/1", "SO/2"]
    assert problems_on(parsed, 3) == []


def test_a_row_whose_date_or_amount_is_unreadable_is_still_returned() -> None:
    parsed = parse_sanction_workbook(
        sheet_of([("SO/1", "not a date", "abc", "A", "a@x.com", None)])
    )
    assert len(parsed.rows) == 1
    assert parsed.rows[0].sanctionOrderDate is None
    assert parsed.rows[0].sanctionAmount is None
    assert len(problems_on(parsed, 2, "error")) == 2


def test_a_sheet_beyond_the_row_ceiling_is_cut_and_says_where() -> None:
    rows = [(f"SO/{i}", "03/04/2026", "1000", "A", f"a{i}@x.com", None) for i in range(MAX_SANCTION_ROWS + 3)]
    parsed = parse_sanction_workbook(sheet_of(rows))
    assert len(parsed.rows) == MAX_SANCTION_ROWS
    ceiling = [p for p in parsed.problems if "more than" in p.reason and p.severity == "error"]
    assert ceiling, parsed.problems
    assert str(MAX_SANCTION_ROWS) in ceiling[0].reason


def test_an_unrecognised_heading_is_named_and_the_padding_is_not() -> None:
    parsed = parse_sanction_workbook(
        sheet_of(
            [("SO/1", "03/04/2026", "1000", "A", "a@x.com", None, None)],
            headings=[*HEADINGS, "District Office"],
        )
    )
    warnings = [p for p in parsed.problems if p.severity == "warning" and p.row == 1]
    assert len(warnings) == 1
    assert "District Office" in warnings[0].reason
    assert ", ," not in warnings[0].reason


def test_a_workbook_with_no_order_number_column_is_refused_whole() -> None:
    with pytest.raises(SanctionXlsxError) as caught:
        parse_sanction_workbook(sheet_of([("x",)], headings=["Something Else"]))
    assert "Sanction Order No." in str(caught.value)


def test_an_empty_upload_is_refused_with_a_sentence_and_not_a_stack_trace() -> None:
    with pytest.raises(SanctionXlsxError):
        parse_sanction_workbook(b"")


def test_a_sheet_with_the_headings_and_no_rows_is_refused_whole() -> None:
    with pytest.raises(SanctionXlsxError) as caught:
        parse_sanction_workbook(sheet_of([]))
    assert "no rows under it" in str(caught.value)



def test_a_text_date_is_read_day_first_and_says_so_when_both_readings_are_possible() -> None:
    parsed = parse_sanction_workbook(
        sheet_of([("SO/1", "03/04/2026", "1000", "A", "a@x.com", None)])
    )
    assert parsed.rows[0].sanctionOrderDate == date(2026, 4, 3)
    warnings = problems_on(parsed, 2, "warning")
    assert warnings and "Sanction Date" in warnings[0].reason
    assert "Start Date" not in warnings[0].reason


def test_the_date_rule_is_imported_rather_than_copied() -> None:
    from app.services import annual_plan_xlsx

    assert reader.read_plan_date is annual_plan_xlsx.read_plan_date


def test_this_module_never_imports_the_sanction_service() -> None:
    source = inspect.getsource(reader)
    assert "from app.services.sanction_orders import" not in source
    assert "from app.services import sanction_orders" not in source
