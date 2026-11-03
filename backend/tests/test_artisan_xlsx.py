import re
from io import BytesIO

import pytest
from openpyxl import Workbook, load_workbook

from app.services import questionnaire_xlsx
from app.services.artisan_xlsx import (
    _ARTISAN_COLUMN_ALIASES,
    _COLUMNS,
    _DETAIL_KEYS,
    MAX_ARTISANS,
    ArtisanDefaults,
    ArtisanXlsxError,
    build_artisan_pro_forma,
    parse_artisan_workbook,
    pro_forma_filename,
)
from app.services.xlsx_table import norm_header

AADHAAR_A = "223456789018"
AADHAAR_B = "323456789012"
AADHAAR_C = "423456789019"

AADHAAR_BAD_CHECKSUM = "223456789019"

DEFAULTS = ArtisanDefaults(
    state="Rajasthan", district="Jaipur", place="Bagru", craftName="Block printing"
)

_ORDER = [key for key, _label in _COLUMNS]


def row(**values: object) -> list[object]:
    return [values.get(key, "") for key in _ORDER]


def workbook_with(rows: list[list[object]], *, details: dict[str, str] | None = None) -> bytes:
    wb = load_workbook(BytesIO(build_artisan_pro_forma()))
    ws = wb["Artisans"]
    for r, line in enumerate(rows, start=2):
        for c, value in enumerate(line, start=1):
            ws.cell(row=r, column=c, value=value)
    if details:
        detail_sheet = wb["Details"]
        for label, value in details.items():
            for line in detail_sheet.iter_rows(min_row=1, max_row=20, max_col=1):
                if line[0].value == label:
                    detail_sheet.cell(row=line[0].row, column=2, value=value)
                    break
    buffer = BytesIO()
    wb.save(buffer)
    return buffer.getvalue()


def one_good_row(**overrides: object) -> list[object]:
    base: dict[str, object] = {
        "serialNo": 1,
        "name": "Ramesh Devi",
        "craftName": "Block printing",
        "place": "Bagru",
        "aadhaarNumber": AADHAAR_A,
        "dos": "Wash the cloth before printing",
        "donts": "Do not let the dye dry on the block",
        "state": "Rajasthan",
        "district": "Jaipur",
    }
    base.update(overrides)
    return row(**base)



def test_the_pro_forma_opens_without_repair():
    wb = load_workbook(BytesIO(build_artisan_pro_forma()))
    assert wb.sheetnames == ["Artisans", "Details", "How to fill this in"]


def test_the_pro_forma_carries_no_example_artisans():
    ws = load_workbook(BytesIO(build_artisan_pro_forma()))["Artisans"]
    assert ws.max_row == 1, "something is typed under the headings of the blank pro-forma"
    help_text = "\n".join(
        str(c.value)
        for line in load_workbook(BytesIO(build_artisan_pro_forma()))["How to fill this in"]
        for c in line
        if c.value
    )
    assert "Ramesh Devi" in help_text, "the worked example must exist, just not where it imports"


def test_every_heading_the_pro_forma_prints_is_a_heading_the_parser_recognises():
    ws = load_workbook(BytesIO(build_artisan_pro_forma()))["Artisans"]
    printed = [cell.value for cell in ws[1] if cell.value]
    assert len(printed) == len(_COLUMNS) == 25
    for (key, label), heading in zip(_COLUMNS, printed, strict=True):
        assert heading == label
        assert norm_header(heading) in _ARTISAN_COLUMN_ALIASES[key], heading


def test_no_two_columns_answer_to_the_same_heading():
    seen: dict[str, str] = {}
    for role, aliases in _ARTISAN_COLUMN_ALIASES.items():
        for alias in aliases:
            assert alias not in seen, f"{alias!r} is claimed by both {seen.get(alias)} and {role}"
            seen[alias] = role


def test_the_details_sheets_labels_do_not_collide_with_the_questionnaire_detail_keys():
    ours = {alias for aliases in _DETAIL_KEYS.values() for alias in aliases}
    theirs = {alias for aliases in questionnaire_xlsx._DETAIL_KEYS.values() for alias in aliases}
    assert not (ours & theirs), sorted(ours & theirs)


def test_the_help_sheet_says_the_file_contains_aadhaar_numbers():
    ws = load_workbook(BytesIO(build_artisan_pro_forma()))["How to fill this in"]
    text = " ".join(str(c.value) for line in ws for c in line if c.value)
    assert "This file contains Aadhaar numbers" in text
    assert "regulated personal data" in text
    assert "delete it once the upload" in text
    assert "masked" in text.lower()


def test_the_pro_formas_own_example_numbers_would_pass_their_checksum():
    from app.services.artisan_identity import aadhaar_error, normalize_aadhaar

    ws = load_workbook(BytesIO(build_artisan_pro_forma()))["How to fill this in"]
    numbers = [
        str(c.value)
        for line in ws
        for c in line
        if c.value and str(c.value).replace(" ", "").isdigit() and len(str(c.value)) >= 12
    ]
    assert numbers, "the worked example no longer carries an Aadhaar number"
    for number in numbers:
        assert aadhaar_error(normalize_aadhaar(number)) is None, number


def test_the_pro_forma_is_named_after_the_workshop_when_there_is_one():
    from types import SimpleNamespace

    assert pro_forma_filename(None) == "DesignWorkshop_Artisan_Pro-Forma.xlsx"
    name = pro_forma_filename(SimpleNamespace(title='Bagru "block" printing\nwinter'))
    assert re.fullmatch(
        r"DesignWorkshop_Workshop_\d{4}-\d{2}-\d{2}_Bagru-Block-Printing-Winter_Artisan-List\.xlsx", name
    ), name
    assert '"' not in name and "\n" not in name



def test_a_generated_pro_forma_parses_back_to_the_rows_typed_into_it():
    data = workbook_with(
        [
            one_good_row(
                localName="रमेश देवी",
                gender="F",
                dateOfBirth="1978-03-01",
                phone="9876543210",
                email="ramesh@example.org",
                address="Near the step well",
                village="Bagru Khurd",
                pincode="303007",
                pehchanCardAvailable="Yes",
                pehchanCardNumber="ab12-3456",
                experienceYears=22,
                experienceMonths=6,
                craftStartDate="2002-06-01",
                notes="Teaches at the cluster",
            )
        ]
    )
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    assert parsed.sheet == "Artisans"
    assert parsed.rowsRead == 1
    [artisan] = parsed.artisans
    assert artisan.row == 2
    assert artisan.name == "Ramesh Devi"
    assert artisan.aadhaarNumber == AADHAAR_A
    assert artisan.localName == "रमेश देवी"
    assert artisan.dateOfBirth == "1978-03-01"
    assert artisan.craftStartDate == "2002-06-01"
    assert artisan.pincode == "303007"
    assert artisan.pehchanCardAvailable is True
    assert artisan.pehchanCardNumber == "AB123456", "separators stripped and upper-cased"
    assert artisan.experienceYears == 22
    assert artisan.experienceMonths == 6
    assert artisan.serialNo == 1
    assert [p for p in parsed.problems if p.severity == "error"] == []


def test_renamed_reordered_and_pushed_down_columns_are_still_found():
    wb = Workbook()
    ws = wb.active
    ws.title = "Sheet1"
    ws["A1"] = "ARTISAN LIST — Bagru, winter 2026"
    headings = [
        "AADHAAR NO.",
        "Name of artisan",
        "craft_trade",
        "Native Place",
        "What to do",
        "What not to do",
        "State / UT",
        "Zilla",
    ]
    for c, heading in enumerate(headings, start=1):
        ws.cell(row=4, column=c, value=heading)
    for c, value in enumerate(
        [
            AADHAAR_A,
            "Ramesh Devi",
            "Block printing",
            "Bagru",
            "Wash",
            "No drying",
            "Rajasthan",
            "Jaipur",
        ],
        start=1,
    ):
        ws.cell(row=5, column=c, value=value)
    buffer = BytesIO()
    wb.save(buffer)

    parsed = parse_artisan_workbook(buffer.getvalue(), defaults=DEFAULTS)
    [artisan] = parsed.artisans
    assert artisan.row == 5, "the Excel row gutter number, not the position under the header"
    assert artisan.name == "Ramesh Devi"
    assert artisan.aadhaarNumber == AADHAAR_A
    assert artisan.craftName == "Block printing"
    assert artisan.state == "Rajasthan"
    assert artisan.district == "Jaipur"



def test_a_sheet_with_no_name_column_refuses_the_whole_file():
    wb = Workbook()
    ws = wb.active
    ws["A1"] = "Remarks"
    ws["A2"] = "nothing here"
    buffer = BytesIO()
    wb.save(buffer)
    with pytest.raises(ArtisanXlsxError) as exc:
        parse_artisan_workbook(buffer.getvalue())
    assert "Artisan name" in str(exc.value)
    assert "pro-forma" in str(exc.value)


def test_a_sheet_with_a_name_column_and_no_aadhaar_column_refuses_with_its_own_sentence():
    wb = Workbook()
    ws = wb.active
    ws["A1"] = "Artisan name"
    ws["A2"] = "Ramesh Devi"
    buffer = BytesIO()
    wb.save(buffer)
    with pytest.raises(ArtisanXlsxError) as exc:
        parse_artisan_workbook(buffer.getvalue())
    assert "Aadhaar number" in str(exc.value)
    assert "recorded twice" in str(exc.value)


def test_an_old_xls_gets_the_save_as_message_and_a_pdf_gets_the_other_one():
    ole2 = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + b"\x00" * 64
    with pytest.raises(ArtisanXlsxError) as old:
        parse_artisan_workbook(ole2)
    assert "older .xls format" in str(old.value)
    assert "password" in str(old.value)

    with pytest.raises(ArtisanXlsxError) as pdf:
        parse_artisan_workbook(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
    assert "not an Excel workbook" in str(pdf.value)
    assert "artisan list pro-forma" in str(pdf.value), "the sentence names THIS door's pro-forma"


def test_an_empty_upload_names_the_artisan_pro_forma():
    with pytest.raises(ArtisanXlsxError) as exc:
        parse_artisan_workbook(b"")
    assert "artisan list pro-forma" in str(exc.value)


def test_a_sheet_with_the_right_headings_and_nothing_under_them_says_so():
    with pytest.raises(ArtisanXlsxError) as exc:
        parse_artisan_workbook(build_artisan_pro_forma())
    assert "no artisans under them" in str(exc.value)



def test_an_unreadable_required_column_reports_the_row_and_imports_nothing_for_it():
    data = workbook_with(
        [
            one_good_row(),
            one_good_row(name="Sita Bai", aadhaarNumber=AADHAAR_B, dos=""),
            one_good_row(name="", aadhaarNumber=AADHAAR_C),
        ]
    )
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    assert parsed.rowsRead == 3
    assert [a.name for a in parsed.artisans] == ["Ramesh Devi"]
    errors = {p.row: p.reason for p in parsed.problems if p.severity == "error"}
    assert set(errors) == {3, 4}
    assert "Do's" in errors[3]
    assert "no artisan name" in errors[4]


def test_a_formula_with_no_cached_result_is_reported_as_a_formula_not_as_blank():
    wb = load_workbook(BytesIO(build_artisan_pro_forma()))
    ws = wb["Artisans"]
    for c, value in enumerate(one_good_row(), start=1):
        ws.cell(row=2, column=c, value=value)
    ws.cell(row=3, column=_ORDER.index("name") + 1, value='=B2&" (junior)"')
    ws.cell(row=3, column=_ORDER.index("aadhaarNumber") + 1, value=AADHAAR_B)
    buffer = BytesIO()
    wb.save(buffer)

    parsed = parse_artisan_workbook(buffer.getvalue(), defaults=DEFAULTS)
    reasons = [p.reason for p in parsed.problems if p.row == 3]
    assert any("formula Excel has never calculated" in r for r in reasons), reasons


def test_an_age_column_is_ignored_and_says_so_once_rather_than_once_per_row():
    data = workbook_with(
        [
            one_good_row(age=47, specialisation="dabu"),
            one_good_row(name="Sita Bai", aadhaarNumber=AADHAAR_B, age=51, specialisation="dabu"),
        ]
    )
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    age = [p for p in parsed.problems if "Age is derived" in p.reason]
    spec = [p for p in parsed.problems if "no specialisation column" in p.reason]
    assert len(age) == 1 and len(spec) == 1
    assert age[0].row is None, "a fact about the column, not about a row"
    assert age[0].severity == "warning"
    assert len(parsed.artisans) == 2, "the rows still import"


def test_a_blank_state_falls_back_to_the_workshops_and_says_so():
    data = workbook_with([one_good_row(state="", district="", place="", craftName="")])
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    [artisan] = parsed.artisans
    assert (artisan.state, artisan.district, artisan.place, artisan.craftName) == (
        "Rajasthan",
        "Jaipur",
        "Bagru",
        "Block printing",
    )
    said = " ".join(p.reason for p in parsed.problems if p.severity == "warning")
    for column in ("State", "District", "Place", "Craft"):
        assert f"no {column}" in said, column


def test_an_unresolvable_district_names_the_state_it_was_checked_against():
    data = workbook_with([one_good_row(district="Nowhere")])
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    [problem] = [p for p in parsed.problems if p.severity == "error"]
    assert "Rajasthan" in problem.reason
    assert problem.row == 2


def test_a_state_that_is_not_on_the_list_is_refused_rather_than_stored():
    data = workbook_with([one_good_row(state="Atlantis")])
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    assert any(p.severity == "error" and "Atlantis" in (p.value or "") for p in parsed.problems)


def test_a_bad_pincode_is_a_warning_and_the_row_still_imports():
    data = workbook_with([one_good_row(pincode="12")])
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    [artisan] = parsed.artisans
    assert artisan.pincode == ""
    assert any(p.severity == "warning" and p.row == 2 for p in parsed.problems)


def test_experience_out_of_range_is_dropped_with_a_warning_rather_than_refusing_the_row():
    data = workbook_with([one_good_row(experienceYears=900, experienceMonths=12)])
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    [artisan] = parsed.artisans
    assert artisan.experienceYears is None and artisan.experienceMonths is None
    warnings = [p.reason for p in parsed.problems if p.severity == "warning"]
    assert any("between 0 and 90" in w for w in warnings)
    assert any("between 0 and 11" in w for w in warnings), "11 and not 12"


def test_an_unreadable_date_of_birth_is_a_warning_and_the_row_still_imports():
    data = workbook_with([one_good_row(dateOfBirth="sometime in the seventies")])
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    [artisan] = parsed.artisans
    assert artisan.dateOfBirth == ""
    assert any("not a date this could read" in p.reason for p in parsed.problems)


def test_a_day_first_date_is_read_day_first():
    data = workbook_with([one_good_row(dateOfBirth="01/03/1978")])
    [artisan] = parse_artisan_workbook(data, defaults=DEFAULTS).artisans
    assert artisan.dateOfBirth == "1978-03-01"


def test_a_file_of_more_than_max_artisans_rows_is_capped_and_says_so():
    rows = [
        one_good_row(name=f"Artisan {n}", aadhaarNumber=f"2234567890{n:02d}")
        for n in range(MAX_ARTISANS + 3)
    ]
    parsed = parse_artisan_workbook(workbook_with(rows), defaults=DEFAULTS)
    assert len(parsed.artisans) <= MAX_ARTISANS
    over = [p for p in parsed.problems if p.severity == "error" and str(MAX_ARTISANS) in p.reason]
    assert over, "the rows past the ceiling must say why they were not imported"



def test_an_aadhaar_that_fails_its_checksum_is_reported_with_its_row_number():
    data = workbook_with([one_good_row(aadhaarNumber=AADHAAR_BAD_CHECKSUM)])
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    [problem] = [p for p in parsed.problems if p.severity == "error"]
    assert problem.row == 2
    assert "fails its checksum" in problem.reason, "aadhaar_error's own sentence, verbatim"
    assert parsed.artisans == []


def test_a_reported_aadhaar_is_masked():
    data = workbook_with([one_good_row(aadhaarNumber=AADHAAR_BAD_CHECKSUM)])
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    [problem] = [p for p in parsed.problems if p.severity == "error"]
    assert problem.value is not None and problem.value.startswith("XXXX XXXX")
    assert AADHAAR_BAD_CHECKSUM not in str(parsed.payload())
    assert AADHAAR_BAD_CHECKSUM[:8] not in str(parsed.payload())


def test_a_masked_aadhaar_in_the_upload_is_refused():
    data = workbook_with([one_good_row(aadhaarNumber="XXXX XXXX 9012")])
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    [problem] = [p for p in parsed.problems if p.severity == "error"]
    assert "masked" in problem.reason
    assert parsed.artisans == []


@pytest.mark.parametrize("flag", ["", "No", "Yes"])
def test_a_pehchan_mask_in_the_upload_is_refused(flag):
    data = workbook_with(
        [one_good_row(pehchanCardAvailable=flag, pehchanCardNumber="XXXX XXXX 3456")]
    )
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    errors = [p for p in parsed.problems if p.severity == "error"]
    assert errors and "masked" in errors[0].reason
    assert errors[0].value is None, "a masked card number is not echoed back either"
    assert parsed.artisans == [], (
        "the row was kept after being called an error, which is the third outcome _read_rows' "
        "docstring says does not exist — and it is what makes artisan_import's "
        "created + matched + linked + refused == rowsRead assertion fail"
    )


@pytest.mark.parametrize(
    ("overrides", "expected"),
    [
        ({"state": "Tamil Nady"}, "state"),
        ({"district": "Nowhere At All"}, "district"),
        ({"craftName": ""}, "craft"),
        ({"pehchanCardNumber": "XXXX XXXX 3456"}, "masked"),
    ],
)
def test_a_row_that_produced_an_error_never_becomes_an_artisan(overrides, expected):
    defaults = DEFAULTS if "craftName" not in overrides else ArtisanDefaults(
        state="Rajasthan", district="Jaipur", place="Bagru", craftName=""
    )
    parsed = parse_artisan_workbook(workbook_with([one_good_row(**overrides)]), defaults=defaults)

    assert parsed.rowsRead == 1
    errors = [p for p in parsed.problems if p.severity == "error"]
    assert errors, f"no error was filed for the {expected} case, so this test proves nothing"
    assert parsed.artisans == [], (
        f"the {expected} row was kept after being refused. The report then counts it in BOTH "
        f"artisansCreated and rowsRefused, and the ledger an officer reads months later to answer "
        f"'where did these artisans come from' is permanently wrong about it."
    )


def test_the_refused_rows_error_count_matches_the_rows_the_importer_will_call_refused():
    rows = [
        one_good_row(serialNo=1),
        one_good_row(serialNo=2, name="Sita Bai", aadhaarNumber=AADHAAR_B, state="Tamil Nady"),
        one_good_row(serialNo=3, name="Latha", aadhaarNumber=AADHAAR_C),
    ]
    parsed = parse_artisan_workbook(workbook_with(rows), defaults=DEFAULTS)

    refused = {p.row for p in parsed.problems if p.severity == "error" and p.row is not None}
    assert parsed.rowsRead == 3
    assert len(parsed.artisans) == 2
    assert len(refused) == 1
    assert len(parsed.artisans) + len(refused) == parsed.rowsRead


def test_a_pehchan_number_beside_a_no_is_dropped_and_says_so():
    data = workbook_with([one_good_row(pehchanCardAvailable="No", pehchanCardNumber="AB123456")])
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    [artisan] = parsed.artisans
    assert artisan.pehchanCardAvailable is False
    assert artisan.pehchanCardNumber == ""
    assert any("left out" in p.reason for p in parsed.problems)


def test_a_yes_with_no_number_becomes_a_no_and_says_so():
    data = workbook_with([one_good_row(pehchanCardAvailable="Yes", pehchanCardNumber="")])
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    [artisan] = parsed.artisans
    assert artisan.pehchanCardAvailable is False
    assert any("recorded as No" in p.reason for p in parsed.problems)


def test_an_unanswered_pehchan_flag_is_worked_out_from_the_number():
    data = workbook_with([one_good_row(pehchanCardAvailable="", pehchanCardNumber="AB123456")])
    [artisan] = parse_artisan_workbook(data, defaults=DEFAULTS).artisans
    assert artisan.pehchanCardAvailable is True
    assert artisan.pehchanCardNumber == "AB123456"



def test_the_details_sheet_carries_the_workshop_id_back_out_again():
    from types import SimpleNamespace

    workshop = SimpleNamespace(
        id="cmworkshop00000000000001",
        title="Bagru block printing",
        state="Rajasthan",
        district="Jaipur",
        venue="Bagru",
    )
    wb = load_workbook(BytesIO(build_artisan_pro_forma(workshop=workshop)))
    ws = wb["Artisans"]
    for c, value in enumerate(one_good_row(), start=1):
        ws.cell(row=2, column=c, value=value)
    buffer = BytesIO()
    wb.save(buffer)

    parsed = parse_artisan_workbook(buffer.getvalue(), defaults=DEFAULTS)
    assert parsed.designWorkshopId == "cmworkshop00000000000001"


def test_a_workbook_with_no_details_sheet_is_read_anyway():
    parsed = parse_artisan_workbook(workbook_with([one_good_row()]), defaults=DEFAULTS)
    assert parsed.designWorkshopId is None
    assert len(parsed.artisans) == 1
