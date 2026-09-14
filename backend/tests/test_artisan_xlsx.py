"""The artisan pro-forma and the parser that reads it back.

**NO DATABASE.** Everything here is openpyxl and two pure modules, which is deliberate and is the
reason ``artisan_xlsx`` takes an :class:`ArtisanDefaults` value object rather than a workshop row:
the rules being tested are about REGULATED DATA, and a test that needs a database is a test that
gets skipped on the machine where somebody is changing the code.

Every test is named as the sentence it asserts.
"""

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

#: Three checksum-valid Aadhaar numbers. **INVENTED AND VERIFIED AGAINST THE VERHOEFF ROUTINE**,
#: never copied from anywhere: the whole point of the column is that a wrong digit is refused, so a
#: test fixture that fails its own checksum would make every assertion below pass for the wrong
#: reason.
AADHAAR_A = "223456789018"
AADHAAR_B = "323456789012"
AADHAAR_C = "423456789019"

#: One that is well-formed in every way EXCEPT its checksum — twelve digits, first digit in 2-9.
AADHAAR_BAD_CHECKSUM = "223456789019"

DEFAULTS = ArtisanDefaults(
    state="Rajasthan", district="Jaipur", place="Bagru", craftName="Block printing"
)

_ORDER = [key for key, _label in _COLUMNS]


def row(**values: object) -> list[object]:
    """One sheet row in the pro-forma's own column order."""
    return [values.get(key, "") for key in _ORDER]


def workbook_with(rows: list[list[object]], *, details: dict[str, str] | None = None) -> bytes:
    """The real pro-forma with rows typed into it, which is what an office actually uploads."""
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


# --------------------------------------------------------------------------------------
# The artefact
# --------------------------------------------------------------------------------------


def test_the_pro_forma_opens_without_repair():
    """Excel must not offer to recover the file this application just produced.

    ``xlsx_report``'s header records three separate ways ordinary field text made a download open
    with "We found a problem with some content", and ``_put`` is the door that fixed all three. A
    round trip through ``load_workbook`` is the cheapest assertion that this writer went through it.
    """
    wb = load_workbook(BytesIO(build_artisan_pro_forma()))
    assert wb.sheetnames == ["Artisans", "Details", "How to fill this in"]


def test_the_pro_forma_carries_no_example_artisans():
    """The working sheet is EMPTY under the headings, and the worked example is on the help sheet.

    Example rows under the headings read well and import badly: the office adds their list below the
    examples and uploads a roster whose first two artisans are somebody the app invented, with no
    way to work out where they came from. The questionnaire pro-forma records having been fixed for
    exactly this.
    """
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
    """The file this module writes must be readable by the parser in the same module.

    Asserted over the ARTEFACT rather than over the two name tables, because the failure this
    prevents is about the printed string: a heading whose normalised form no alias holds is a column
    an office fills in and the app silently ignores — discovered after the typing, not before.
    """
    ws = load_workbook(BytesIO(build_artisan_pro_forma()))["Artisans"]
    printed = [cell.value for cell in ws[1] if cell.value]
    assert len(printed) == len(_COLUMNS) == 25
    for (key, label), heading in zip(_COLUMNS, printed, strict=True):
        assert heading == label
        assert norm_header(heading) in _ARTISAN_COLUMN_ALIASES[key], heading


def test_no_two_columns_answer_to_the_same_heading():
    """Matching is EXACT within a role, so one spelling shared by two roles is one column lost.

    Which of the two wins is dict order, which is not a thing anybody should have to reason about.
    """
    seen: dict[str, str] = {}
    for role, aliases in _ARTISAN_COLUMN_ALIASES.items():
        for alias in aliases:
            assert alias not in seen, f"{alias!r} is claimed by both {seen.get(alias)} and {role}"
            seen[alias] = role


def test_the_details_sheets_labels_do_not_collide_with_the_questionnaire_detail_keys():
    """Two features write a sheet called "Details" and read one back by matching column A.

    A label here that normalised to one of the questionnaire's aliases would mean an artisan
    pro-forma uploaded to the questionnaire door quietly setting that questionnaire's title or
    description — and the reverse. The two tables must share no spelling at all.
    """
    ours = {alias for aliases in _DETAIL_KEYS.values() for alias in aliases}
    theirs = {alias for aliases in questionnaire_xlsx._DETAIL_KEYS.values() for alias in aliases}
    assert not (ours & theirs), sorted(ours & theirs)


def test_the_help_sheet_says_the_file_contains_aadhaar_numbers():
    """The PII warning is IN THE ARTEFACT, because the file outlives the page.

    Somebody opens this workbook three weeks later on a different machine, having never seen the
    screen it came from. Four claims have to survive that: it is regulated, do not circulate it,
    delete it afterwards, and never paste a masked number back in.
    """
    ws = load_workbook(BytesIO(build_artisan_pro_forma()))["How to fill this in"]
    text = " ".join(str(c.value) for line in ws for c in line if c.value)
    assert "This file contains Aadhaar numbers" in text
    assert "regulated personal data" in text
    assert "delete it once the upload" in text
    assert "masked" in text.lower()


def test_the_pro_formas_own_example_numbers_would_pass_their_checksum():
    """An example number the app would refuse teaches the wrong thing.

    The Help sheet prints two Aadhaar numbers in its worked example. If either failed the Verhoeff
    check, the first thing anybody copying the example would see is a refusal.
    """
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
    """Four of these in one folder called "pro-forma (3).xlsx" tells nobody which is which.

    And the name reaches a ``Content-Disposition`` header, so a quote or a newline in a workshop
    title has to be collapsed rather than escaped.
    """
    from types import SimpleNamespace

    assert pro_forma_filename(None) == "artisan-list-pro-forma.xlsx"
    name = pro_forma_filename(SimpleNamespace(title='Bagru "block" printing\nwinter'))
    assert name == "artisan-list-Bagru-block-printing-winter.xlsx"
    assert '"' not in name and "\n" not in name


# --------------------------------------------------------------------------------------
# The round trip
# --------------------------------------------------------------------------------------


def test_a_generated_pro_forma_parses_back_to_the_rows_typed_into_it():
    """Identity. The writer and the reader are two halves of one contract."""
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
    """Column order is not fixed, header spelling is not fixed, and row 1 is not fixed.

    This is the whole difference between a feature an office uses and one they abandon after the
    first upload: somebody puts a letterhead across the top, drags Aadhaar to the front, and types
    "Name of artisan" because that is what their own list says.
    """
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


# --------------------------------------------------------------------------------------
# Refusing the whole file
# --------------------------------------------------------------------------------------


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
    """Two refusals, two remedies.

    Telling somebody looking at a sheet full of names that "no sheet has an Artisan name column"
    would send them to fix the one thing that is already right.
    """
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
    """The message is chosen from the FILE'S OWN MAGIC BYTES, never from the extension.

    Somebody who renamed ``report.xls`` to ``report.xlsx`` to get past an upload filter has an .xls
    with an .xlsx name, and "the upload may have been cut short" sends them to re-download a file
    that was never the problem.
    """
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


# --------------------------------------------------------------------------------------
# Never drop a row
# --------------------------------------------------------------------------------------


def test_an_unreadable_required_column_reports_the_row_and_imports_nothing_for_it():
    """A row this cannot read is REPORTED, never dropped.

    A silent drop means somebody uploads fifteen artisans, sees thirteen, and has no way to find out
    which two went missing or why.
    """
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
    """A workbook LibreOffice or a generator wrote has formulas and no cached values.

    Reporting "row 3 is blank" for a row that visibly reads ``=B3&" Devi"`` on the uploader's screen
    is the single most confusing thing this parser could say.
    """
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
    """Age has nowhere to go on an artisan record, and a column silently ignored is the worst answer.

    ONCE PER FILE: the fact is about the COLUMN, and fifteen identical warnings is a report nobody
    reads to the bottom of.
    """
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
    """Applied, and REPORTED. An office that left the column blank on fifteen rows made one
    decision, and being told which value was used is what lets them notice if it is wrong."""
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
    """Several district names are used by two states at once, so the state is the whole context."""
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
    """``require_location`` deliberately does not demand a pincode — it cannot be answered from
    memory in a village with no signal, which is the test every mandatory field here has to pass."""
    data = workbook_with([one_good_row(pincode="12")])
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    [artisan] = parsed.artisans
    assert artisan.pincode == ""
    assert any(p.severity == "warning" and p.row == 2 for p in parsed.problems)


def test_experience_out_of_range_is_dropped_with_a_warning_rather_than_refusing_the_row():
    """Somebody who typed 900 in the years column has a typo in ONE cell.

    Refusing the row would lose the twenty-four fields they got right, and the create would refuse
    the value anyway if it travelled — ``ArtisanCreate`` bounds the same 0..90 and 0..11.
    """
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
    """``01/03/1978`` is the first of March, which is what an Indian office writes."""
    data = workbook_with([one_good_row(dateOfBirth="01/03/1978")])
    [artisan] = parse_artisan_workbook(data, defaults=DEFAULTS).artisans
    assert artisan.dateOfBirth == "1978-03-01"


def test_a_file_of_more_than_max_artisans_rows_is_capped_and_says_so():
    """The ceiling fires, and the rows past it are REFUSED rather than silently dropped.

    Compared against the LIVE constant rather than a literal, so lowering it cannot leave this test
    green while asserting nothing.
    """
    rows = [
        one_good_row(name=f"Artisan {n}", aadhaarNumber=f"2234567890{n:02d}")
        for n in range(MAX_ARTISANS + 3)
    ]
    parsed = parse_artisan_workbook(workbook_with(rows), defaults=DEFAULTS)
    assert len(parsed.artisans) <= MAX_ARTISANS
    over = [p for p in parsed.problems if p.severity == "error" and str(MAX_ARTISANS) in p.reason]
    assert over, "the rows past the ceiling must say why they were not imported"


# --------------------------------------------------------------------------------------
# The identity columns
# --------------------------------------------------------------------------------------


def test_an_aadhaar_that_fails_its_checksum_is_reported_with_its_row_number():
    data = workbook_with([one_good_row(aadhaarNumber=AADHAAR_BAD_CHECKSUM)])
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    [problem] = [p for p in parsed.problems if p.severity == "error"]
    assert problem.row == 2
    assert "fails its checksum" in problem.reason, "aadhaar_error's own sentence, verbatim"
    assert parsed.artisans == []


def test_a_reported_aadhaar_is_masked():
    """**THE PII ASSERTION.** A diagnostic nobody reads as a data path is still a data path.

    Asserted over the WHOLE payload rather than over one field: the digits must appear nowhere —
    not in a reason, not in a value, not in a sheet name.
    """
    data = workbook_with([one_good_row(aadhaarNumber=AADHAAR_BAD_CHECKSUM)])
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    [problem] = [p for p in parsed.problems if p.severity == "error"]
    assert problem.value is not None and problem.value.startswith("XXXX XXXX")
    assert AADHAAR_BAD_CHECKSUM not in str(parsed.payload())
    assert AADHAAR_BAD_CHECKSUM[:8] not in str(parsed.payload())


def test_a_masked_aadhaar_in_the_upload_is_refused():
    """A number with X's in it was copied off a screen, not read off a card."""
    data = workbook_with([one_good_row(aadhaarNumber="XXXX XXXX 9012")])
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    [problem] = [p for p in parsed.problems if p.severity == "error"]
    assert "masked" in problem.reason
    assert parsed.artisans == []


@pytest.mark.parametrize("flag", ["", "No", "Yes"])
def test_a_pehchan_mask_in_the_upload_is_refused(flag):
    """``pehchan_error`` ACCEPTS ``XXXX XXXX 3456`` — fourteen alphanumerics, no checksum to fail.

    That is exactly how a mask once got stored over a real card number, so the guard is here rather
    than relying on a validator that cannot see the problem.

    **THE ``parsed.artisans == []`` ASSERTION IS THE POINT OF THIS TEST AND IT WAS MISSING.** This
    test's NAME said the row was refused and its body only checked that a problem had been filed;
    the row was in fact kept, and there is no backstop below the parser for this one —
    ``ArtisanCreate.reconcile_pehchan`` resolves an unanswered flag to False and accepts the record
    — so the artisan WAS created with the card number silently dropped while the officer was told
    the row had been refused. Re-uploading the corrected sheet does not repair it either: the row
    matches on Aadhaar and takes the branch whose own message is "the existing record was not
    changed and was not re-filed".

    ALL THREE FLAG STATES, because only ``Yes`` was ever caught downstream (that branch demands a
    number) and the two that were not are the ones an office actually produces: the flag column left
    alone, or answered No, with the number pasted off a screen beside it.
    """
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
    """**THE INVARIANT ``_read_rows`` DECLARES, ASSERTED RATHER THAN DESCRIBED.**

    "A row is either an artisan or a ``ParseProblem`` with ``severity='error'``; there is no third
    outcome." Four helpers used to file an error and fall through — ``_read_place`` twice,
    ``_read_craft`` and ``_read_pehchan`` — because the ``problem`` closure only appends and the row
    was appended unconditionally afterwards. ``ParseProblem``'s contract is that ``"error"`` means
    NOTHING WAS STORED FOR THAT ROW, and ``artisan_import`` counts on it literally:
    ``refused = len({p.row for p in problems if p.severity == "error"})`` followed by an assertion
    that ``created + matched + linked + refused == rowsRead``.

    FOUR CASES AND NOT ONE, so that closing the branch with a real scar behind it (the masked card
    number) cannot leave the other three open. The craft case uses a defaults value with no craft,
    because the workshop's own craft is the documented fallback for a blank column.
    """
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
    """The arithmetic ``artisan_import`` asserts, measured on the parser's own output.

    ``rowsRead == len(artisans) + len({distinct rows with an error})``. DISTINCT rows, because one
    row can carry two errors — a bad state AND the "choose a state before the district" that follows
    from it — which is exactly why the importer counts a SET and why this test does too.
    """
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
    """``reconcile_pehchan``'s third branch, applied here so the report can EXPLAIN it."""
    data = workbook_with([one_good_row(pehchanCardAvailable="No", pehchanCardNumber="AB123456")])
    parsed = parse_artisan_workbook(data, defaults=DEFAULTS)
    [artisan] = parsed.artisans
    assert artisan.pehchanCardAvailable is False
    assert artisan.pehchanCardNumber == ""
    assert any("left out" in p.reason for p in parsed.problems)


def test_a_yes_with_no_number_becomes_a_no_and_says_so():
    """Yes-with-no-number is the one combination nothing downstream can interpret."""
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


# --------------------------------------------------------------------------------------
# The Details sheet
# --------------------------------------------------------------------------------------


def test_the_details_sheet_carries_the_workshop_id_back_out_again():
    """The guard that stops fifteen people's records being filed under a stranger's project."""
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
    """An office that built the list from scratch has no Details sheet, and that is normal."""
    parsed = parse_artisan_workbook(workbook_with([one_good_row()]), defaults=DEFAULTS)
    assert parsed.designWorkshopId is None
    assert len(parsed.artisans) == 1
