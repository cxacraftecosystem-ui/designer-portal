from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Any

import pytest

from app.services import sanction_import, sanction_orders
from app.services.sanction_import import READY, REFUSED, REVIEW
from app.services.sanction_orders import Refusal, StandingVerdict
from app.services.sanction_orders_xlsx import ParsedSanctionRow, ParsedSanctionSheet
from app.services.xlsx_table import ParseProblem

REAL_STANDING_VERDICT = sanction_orders.designer_standing_verdict


class Officer:
    id = "officer-1"
    name = "A. K. Officer"
    email = "officer@ministry.gov.in"
    role = "ASSISTANT_DIRECTOR"


class Account:
    def __init__(self, uid: str, name: str, email: str) -> None:
        self.id = uid
        self.name = name
        self.email = email


class RosterRow:
    def __init__(self, email: str, full_name: str | None, *, active: bool = True) -> None:
        self.email = email
        self.fullName = full_name
        self.isActive = active


def parsed_row(
    *,
    row: int = 2,
    no: str = "SO/2026/42",
    names: list[str] | None = None,
    emails: list[str] | None = None,
    when: date | None = date(2026, 4, 3),
    amount: Decimal | None = Decimal("450000.00"),
) -> ParsedSanctionRow:
    return ParsedSanctionRow(
        sheetRow=row,
        sanctionOrderNo=no,
        sanctionOrderKey=no.replace("/", "").replace("-", "").upper(),
        sanctionOrderDate=when,
        sanctionAmount=amount,
        designerNames=list(names or []),
        designerEmails=list(emails or []),
    )


@pytest.fixture
def world(monkeypatch: pytest.MonkeyPatch):
    state: dict[str, Any] = {
        "accounts": {},
        "roster": {},
        "taken": {},
        "refusals": {},
    }

    def canonical(address: str) -> str:
        from app.services.designers import canonical_email

        return canonical_email(address)

    async def fake_standing(address: str, *, officer: Any) -> StandingVerdict:
        key = canonical(address)
        refusal = state["refusals"].get(key)
        return StandingVerdict(
            keys=[address.lower(), key],
            canonical=key,
            allowListRow=None,
            user=None if refusal else state["accounts"].get(key),
            refusal=refusal,
        )

    async def fake_duplicate(order_key: str) -> str | None:
        return state["taken"].get(order_key)

    class FakeRoster:
        async def find_first(self, *, where: dict[str, Any]) -> Any:
            wanted = where.get("email", {})
            keys = wanted.get("in", []) if isinstance(wanted, dict) else [wanted]
            for key in keys:
                row = state["roster"].get(canonical(key))
                if row is not None:
                    return row
            return None

        async def find_many(self, *, where: dict[str, Any], take: int = 0) -> list[Any]:
            names = [
                str(clause["fullName"]["equals"]).casefold() for clause in where.get("OR", [])
            ]
            return [
                row
                for row in state["roster"].values()
                if row.isActive and (row.fullName or "").casefold() in names
            ]

    class FakeImports:
        def __init__(self) -> None:
            self.written: list[dict[str, Any]] = []

        async def create(self, *, data: dict[str, Any]) -> Any:
            self.written.append(data)
            return type("Row", (), {"id": "import-1"})()

    class FakeDb:
        designerroster = FakeRoster()
        sanctionorderimport = FakeImports()

    monkeypatch.setattr(sanction_import, "db", FakeDb)
    monkeypatch.setattr(sanction_orders, "designer_standing_verdict", fake_standing)
    monkeypatch.setattr(sanction_orders, "duplicate_reason", fake_duplicate)
    state["db"] = FakeDb
    return state


async def review(world: dict[str, Any], *rows: ParsedSanctionRow) -> dict[str, Any]:
    sheet = ParsedSanctionSheet(sheet="Sanction orders", sourceFilename="orders.xlsx")
    sheet.rows = list(rows)
    return await sanction_import.review_sheet(sheet, officer=Officer())


def only(answer: dict[str, Any]) -> dict[str, Any]:
    found = [*answer["ready"], *answer["needsReview"], *answer["refused"]]
    assert len(found) == 1, answer
    return found[0]


def codes(row: dict[str, Any]) -> list[str]:
    return [question["code"] for question in row["questions"]]


def warnings_for(answer: dict[str, Any], row: int) -> list[str]:
    return [
        problem["reason"]
        for problem in answer["problems"]
        if problem["row"] == row and problem["severity"] == "warning"
    ]



async def test_one_name_one_address_and_nobody_on_file_is_recorded_without_a_question(world):
    answer = await review(world, parsed_row(names=["Ramesh Kumar"], emails=["r.kumar@gmail.com"]))
    row = only(answer)
    assert row["verdict"] == READY
    assert row["questions"] == []
    assert answer["problems"] == []
    assert row["designers"] == [
        {
            "name": "Ramesh Kumar",
            "email": "r.kumar@gmail.com",
            "canonicalEmail": "rkumar@gmail.com",
            "userId": None,
            "accountExists": False,
            "empanelled": False,
            "nameSource": "sheet",
        }
    ]


async def test_case_and_whitespace_differences_are_folded_and_never_asked_about(world):
    world["accounts"]["rkumar@gmail.com"] = Account("u1", "Ramesh Kumar", "r.kumar@gmail.com")
    answer = await review(world, parsed_row(names=["RAMESH  KUMAR"], emails=["r.kumar@gmail.com"]))
    row = only(answer)
    assert row["verdict"] == READY
    assert row["questions"] == []
    assert warnings_for(answer, 2) == []


async def test_a_team_keeps_the_order_it_was_typed_in_and_the_lead_is_first(world):
    answer = await review(
        world,
        parsed_row(
            names=["Kavita Rao", "Anil Shah", "Ramesh Kumar"],
            emails=["k@gmail.com", "a@gmail.com", "r@gmail.com"],
        ),
    )
    row = only(answer)
    assert row["verdict"] == READY
    assert [d["name"] for d in row["designers"]] == ["Kavita Rao", "Anil Shah", "Ramesh Kumar"]
    assert [d["email"] for d in row["designers"]] == ["k@gmail.com", "a@gmail.com", "r@gmail.com"]



async def test_two_names_and_three_addresses_goes_to_the_human_with_the_surplus_named(world):
    answer = await review(
        world,
        parsed_row(
            names=["Kavita Rao", "Anil Shah"],
            emails=["k@gmail.com", "a@gmail.com", "r@gmail.com"],
        ),
    )
    row = only(answer)
    assert row["verdict"] == REVIEW
    assert codes(row) == ["counts-differ"]
    assert row["unpairedEmails"] == ["r@gmail.com"]
    assert row["unpairedNames"] == []
    assert [d["name"] for d in row["designers"]] == ["Kavita Rao", "Anil Shah"]


async def test_no_addresses_at_all_always_asks_and_pre_fills_from_a_unique_roster_match(world):
    world["roster"]["kavita@gmail.com"] = RosterRow("kavita@gmail.com", "Kavita Rao")
    answer = await review(world, parsed_row(names=["Kavita Rao"], emails=[]))
    row = only(answer)
    assert row["verdict"] == REVIEW
    assert codes(row) == ["no-addresses"]
    assert row["questions"][0]["candidates"] == [
        {"name": "Kavita Rao", "email": "kavita@gmail.com", "source": "roster"}
    ]


async def test_no_addresses_and_no_roster_match_asks_with_nothing_to_offer(world):
    answer = await review(world, parsed_row(names=["Somebody New"], emails=[]))
    row = only(answer)
    assert row["verdict"] == REVIEW
    assert codes(row) == ["no-addresses"]
    assert row["questions"][0]["candidates"] == []


async def test_no_names_but_a_known_address_takes_the_name_on_file_and_says_so(world):
    world["accounts"]["rkumar@gmail.com"] = Account("u1", "Ramesh Kumar", "r.kumar@gmail.com")
    answer = await review(world, parsed_row(names=[], emails=["r.kumar@gmail.com"]))
    row = only(answer)
    assert row["verdict"] == READY
    assert row["designers"][0]["name"] == "Ramesh Kumar"
    assert row["designers"][0]["nameSource"] == "record"
    assert any("name already on file was used" in text for text in warnings_for(answer, 2))


async def test_no_names_and_an_address_nobody_knows_has_to_ask(world):
    answer = await review(world, parsed_row(names=[], emails=["stranger@gmail.com"]))
    row = only(answer)
    assert row["verdict"] == REVIEW
    assert codes(row) == ["unknown-name"]


async def test_both_cells_empty_is_refused_and_never_offered_for_confirmation(world):
    answer = await review(world, parsed_row(names=[], emails=[]))
    row = only(answer)
    assert row["verdict"] == REFUSED
    assert "names no designer at all" in row["reason"]
    assert row["questions"] == []



async def test_a_name_that_merely_differs_from_the_record_is_imported_with_a_warning(world):
    world["accounts"]["rkumar@gmail.com"] = Account("u1", "Ramesh Kumar", "r.kumar@gmail.com")
    answer = await review(world, parsed_row(names=["R. Kumar"], emails=["r.kumar@gmail.com"]))
    row = only(answer)
    assert row["verdict"] == READY
    assert row["questions"] == []
    assert row["designers"][0]["name"] == "Ramesh Kumar"
    assert row["designers"][0]["nameSource"] == "record"
    assert any("Designer name differs" in text for text in warnings_for(answer, 2))


async def test_a_name_and_an_address_naming_two_known_people_goes_to_the_human(world):
    world["accounts"]["kavita@gmail.com"] = Account("u2", "Kavita Rao", "kavita@gmail.com")
    world["roster"]["rkumar@gmail.com"] = RosterRow("r.kumar@gmail.com", "Ramesh Kumar")
    answer = await review(world, parsed_row(names=["Ramesh Kumar"], emails=["kavita@gmail.com"]))
    row = only(answer)
    assert row["verdict"] == REVIEW
    assert codes(row) == ["name-and-address-disagree"]
    sources = {c["source"]: c for c in row["questions"][0]["candidates"]}
    assert sources["address"]["name"] == "Kavita Rao"
    assert sources["name"]["name"] == "Ramesh Kumar"


async def test_a_common_name_alone_does_not_manufacture_a_question(world):
    world["accounts"]["rkumar@gmail.com"] = Account("u1", "Ramesh Kumar", "r.kumar@gmail.com")
    world["roster"]["rkumar@gmail.com"] = RosterRow("r.kumar@gmail.com", "Ramesh Kumar")
    world["roster"]["other@gmail.com"] = RosterRow("other@gmail.com", "Ramesh Kumar")
    answer = await review(world, parsed_row(names=["Ramesh Kumar"], emails=["r.kumar@gmail.com"]))
    row = only(answer)
    assert row["verdict"] == READY
    assert row["questions"] == []
    assert warnings_for(answer, 2) == []


async def test_an_account_with_no_empanelment_is_ordinary_and_not_a_refusal(world):
    world["accounts"]["rkumar@gmail.com"] = Account("u1", "Ramesh Kumar", "r.kumar@gmail.com")
    answer = await review(world, parsed_row(names=["Ramesh Kumar"], emails=["r.kumar@gmail.com"]))
    row = only(answer)
    assert row["verdict"] == READY
    assert row["designers"][0]["empanelled"] is False
    assert row["designers"][0]["accountExists"] is True



async def test_the_same_address_twice_in_one_cell_collapses_and_takes_its_name_with_it(world):
    answer = await review(
        world,
        parsed_row(
            names=["Ramesh", "Ramesh", "Kavita"],
            emails=["r@gmail.com", "r@gmail.com", "k@gmail.com"],
        ),
    )
    row = only(answer)
    assert row["verdict"] == READY
    assert [(d["name"], d["email"]) for d in row["designers"]] == [
        ("Ramesh", "r@gmail.com"),
        ("Kavita", "k@gmail.com"),
    ]
    assert any("named twice" in text for text in warnings_for(answer, 2))


async def test_two_spellings_of_one_gmail_collapse_and_the_warning_names_both(world):
    answer = await review(
        world,
        parsed_row(
            names=["Ramesh Kumar", "R Kumar"],
            emails=["r.kumar@gmail.com", "rkumar+dch@gmail.com"],
        ),
    )
    row = only(answer)
    assert row["verdict"] == READY
    assert len(row["designers"]) == 1
    assert row["designers"][0]["email"] == "r.kumar@gmail.com"
    warning = next(text for text in warnings_for(answer, 2) if "same mailbox" in text)
    assert "rkumar+dch@gmail.com" in warning
    assert "r.kumar@gmail.com" in warning


async def test_the_same_designer_on_two_rows_is_not_a_failure_at_all(world):
    answer = await review(
        world,
        parsed_row(row=2, no="SO/1", names=["Ramesh"], emails=["r@gmail.com"]),
        parsed_row(row=3, no="SO/2", names=["Ramesh"], emails=["r@gmail.com"]),
    )
    assert len(answer["ready"]) == 2
    assert answer["problems"] == []


async def test_a_number_already_in_the_register_is_refused_in_the_registers_own_words(world):
    world["taken"]["SO202642"] = "Sanction order SO/2026/42 is already recorded. It opened ..."
    answer = await review(world, parsed_row(names=["Ramesh"], emails=["r@gmail.com"]))
    row = only(answer)
    assert row["verdict"] == REFUSED
    assert row["reason"] == world["taken"]["SO202642"]



@pytest.mark.parametrize(
    "sentence",
    [
        sanction_orders.SANCTION_EMPANELMENT_ENDED,
        sanction_orders.SANCTION_SELF_NAMED,
        sanction_orders.SANCTION_ADDRESS_BARRED.format(state="suspended"),
        sanction_orders.SANCTION_AMBIGUOUS_ACCOUNTS.format(addresses="a@x.com, b@x.com"),
    ],
)
async def test_a_standing_refusal_refuses_the_row_and_asks_nothing(world, sentence: str):
    world["refusals"]["rkumar@gmail.com"] = Refusal(422, sentence)
    answer = await review(world, parsed_row(names=["Ramesh"], emails=["r.kumar@gmail.com"]))
    row = only(answer)
    assert row["verdict"] == REFUSED
    assert row["reason"] == sentence
    assert row["questions"] == []


@pytest.mark.parametrize(
    "spelling",
    ["new.master@gmail.com", "newmaster@gmail.com", "New.Master+sheet@googlemail.com"],
)
async def test_the_master_admins_mailbox_is_refused_by_the_real_verdict_and_never_confirmed(
    world, monkeypatch, spelling
):
    from app.core.config import get_settings

    monkeypatch.setattr(get_settings(), "master_admin_email", "new.master@gmail.com")
    monkeypatch.setattr(sanction_orders, "designer_standing_verdict", REAL_STANDING_VERDICT)
    answer = await review(world, parsed_row(names=["Somebody Else"], emails=[spelling]))
    row = only(answer)
    assert row["verdict"] == REFUSED
    assert row["reason"] == sanction_orders.SANCTION_MASTER_MAILBOX
    assert row["questions"] == []
    assert answer["ready"] == [] and answer["needsReview"] == []


async def test_a_standing_refusal_beats_a_question_on_the_same_row(world):
    world["refusals"]["barred@gmail.com"] = Refusal(
        422, sanction_orders.SANCTION_ADDRESS_BARRED.format(state="rejected")
    )
    answer = await review(
        world,
        parsed_row(names=["A"], emails=["barred@gmail.com", "b@gmail.com"]),
    )
    row = only(answer)
    assert row["verdict"] == REFUSED
    assert row["questions"] == []



@pytest.mark.parametrize(
    ("kwargs", "word"),
    [({"when": None}, "no date"), ({"amount": None}, "no amount")],
)
async def test_an_order_with_no_date_or_no_amount_is_refused(world, kwargs, word):
    answer = await review(
        world, parsed_row(names=["Ramesh"], emails=["r@gmail.com"], **kwargs)
    )
    row = only(answer)
    assert row["verdict"] == REFUSED
    assert word in row["reason"]


async def test_a_team_longer_than_the_cap_is_refused_with_the_number_in_the_sentence(world):
    from app.schemas.sanction_orders import MAX_SANCTION_DESIGNERS

    count = MAX_SANCTION_DESIGNERS + 1
    answer = await review(
        world,
        parsed_row(
            names=[f"N{i}" for i in range(count)],
            emails=[f"n{i}@gmail.com" for i in range(count)],
        ),
    )
    row = only(answer)
    assert row["verdict"] == REFUSED
    assert str(MAX_SANCTION_DESIGNERS) in row["reason"]



async def test_rows_the_parser_dropped_are_still_counted_as_read(world):
    sheet = ParsedSanctionSheet(sheet="Sanction orders", sourceFilename="orders.xlsx")
    sheet.rows = [parsed_row(row=2, names=["Ramesh"], emails=["r@gmail.com"])]
    sheet.problems = [
        ParseProblem("Sanction orders", 3, "error", "no order number", None),
        ParseProblem("Sanction orders", 3, "error", "and an unreadable date", None),
        ParseProblem("Sanction orders", 4, "error", "duplicate of row 2", None),
        ParseProblem("Sanction orders", 2, "warning", "a warning on a row that DID parse", None),
    ]
    answer = await sanction_import.review_sheet(sheet, officer=Officer())
    assert answer["rowsRead"] == 3, answer["rowsRead"]


async def test_the_three_lists_always_travel_even_when_empty(world):
    answer = await review(world, parsed_row(names=["Ramesh"], emails=["r@gmail.com"]))
    assert answer["ready"] and answer["needsReview"] == [] and answer["refused"] == []
    for key in ("sheet", "sourceFilename", "rowsRead", "ready", "needsReview", "refused", "problems"):
        assert key in answer, key


async def test_the_preview_writes_nothing(world):
    await review(world, parsed_row(names=["Ramesh"], emails=["r@gmail.com"]))
    assert world["db"].sanctionorderimport.written == []



def confirm_body(**over: Any):
    from app.schemas.sanction_orders import SanctionImportConfirm

    base: dict[str, Any] = {
        "sheet": "Sanction orders",
        "sourceFilename": "orders.xlsx",
        "rowsRead": 1,
        "rows": [
            {
                "sheetRow": 2,
                "action": "record",
                "sanctionOrderNo": "SO/2026/42",
                "sanctionOrderDate": "2026-04-03",
                "sanctionAmount": "450000.00",
                "notes": None,
                "designers": [
                    {"name": "Kavita Rao", "email": "kavita@gmail.com"},
                    {"name": "Anil Shah", "email": "anil@gmail.com"},
                ],
            }
        ],
    }
    base.update(over)
    return SanctionImportConfirm(**base)


def test_the_lead_is_element_zero_and_the_rest_become_co_designers() -> None:
    body = confirm_body()
    created = sanction_import._create_body(body.rows[0], sheet_filename="orders.xlsx")
    assert created.designerName == "Kavita Rao"
    assert created.designerEmail == "kavita@gmail.com"
    assert [co.name for co in created.coDesigners] == ["Anil Shah"]
    assert created.sourceFilename == "orders.xlsx"
    assert created.sheetRow == 2


def test_the_import_body_is_the_officers_own_form_plus_two_provenance_columns() -> None:
    from app.schemas.sanction_orders import SanctionOrderCreate, SanctionOrderImportRow

    assert issubclass(SanctionOrderImportRow, SanctionOrderCreate)
    extra = set(SanctionOrderImportRow.model_fields) - set(SanctionOrderCreate.model_fields)
    assert extra == {"sourceFilename", "sheetRow"}, extra


async def test_a_refused_row_does_not_cost_the_rows_around_it(world, monkeypatch):
    from fastapi import HTTPException

    calls: list[str] = []

    async def fake_create(payload: Any, officer: Any) -> dict[str, Any]:
        calls.append(payload.sanctionOrderNo)
        if payload.sanctionOrderNo == "SO/2":
            raise HTTPException(status_code=422, detail="that empanelment has been ended")
        return {
            "sanctionOrder": {
                "id": "s-" + payload.sanctionOrderNo,
                "sanctionOrderNo": payload.sanctionOrderNo,
                "designWorkshopId": "w-" + payload.sanctionOrderNo,
                "designers": [{"designerUserId": "u1", "accountCreated": True}],
            }
        }

    monkeypatch.setattr(sanction_orders, "create_from_sanction", fake_create)

    rows = []
    for index, no in enumerate(("SO/1", "SO/2", "SO/3"), start=2):
        row = confirm_body().rows[0].model_copy(update={"sanctionOrderNo": no, "sheetRow": index})
        rows.append(row)
    body = confirm_body(rowsRead=3)
    body = body.model_copy(update={"rows": rows})

    report = await sanction_import.apply_confirmed_rows(body, Officer())
    assert calls == ["SO/1", "SO/2", "SO/3"]
    assert report["recorded"] == 2
    assert report["refused"] == 1
    assert report["skipped"] == 0
    assert report["rowsRead"] == 3
    assert report["recorded"] + report["skipped"] + report["refused"] == report["rowsRead"]
    verbatim = [p for p in report["problems"] if p["row"] == 3]
    assert verbatim[0]["reason"] == "that empanelment has been ended"
    assert verbatim[0]["severity"] == "error"


async def test_a_skipped_row_is_counted_and_named_and_never_silently_dropped(world, monkeypatch):
    async def fake_create(payload: Any, officer: Any) -> dict[str, Any]:  # pragma: no cover
        raise AssertionError("a skipped row must not reach the create door")

    monkeypatch.setattr(sanction_orders, "create_from_sanction", fake_create)
    body = confirm_body()
    body = body.model_copy(
        update={"rows": [body.rows[0].model_copy(update={"action": "skip"})]}
    )
    report = await sanction_import.apply_confirmed_rows(body, Officer())
    assert report["skipped"] == 1
    assert report["recorded"] == 0
    assert any(p["row"] == 2 and "chose to leave it out" in p["reason"] for p in report["problems"])


async def test_an_import_hands_back_first_sign_in_links_as_the_form_does(world, monkeypatch):
    async def fake_create(payload: Any, officer: Any) -> dict[str, Any]:
        return {
            "sanctionOrder": {
                "id": "s1",
                "sanctionOrderNo": payload.sanctionOrderNo,
                "designWorkshopId": "w1",
                "designers": [
                    {"designerUserId": "u1", "accountCreated": True},
                    {"designerUserId": "u2", "accountCreated": False},
                    {"designerUserId": "u3", "accountCreated": True},
                ],
            },
            "credentialLink": {"link": "https://example/set-password?token=SECRET"},
            "credentialLinks": [
                {
                    "designerUserId": "u1",
                    "designerName": "Asha",
                    "signInEmail": "asha@example.org",
                    "link": {"id": "t1", "link": "https://example/set-password?token=SECRET"},
                    "problem": None,
                },
                {
                    "designerUserId": "u3",
                    "designerName": "Ravi",
                    "signInEmail": "ravi@example.org",
                    "link": None,
                    "problem": "the sign-in link could not be issued",
                },
            ],
        }

    monkeypatch.setattr(sanction_orders, "create_from_sanction", fake_create)
    report = await sanction_import.apply_confirmed_rows(confirm_body(), Officer())
    assert report["accountsCreated"] == 2
    assert report["credentialLinksIssued"] == 1
    created = report["created"][0]
    assert created["sanctionOrder"]["id"] == "s1"
    assert [entry["designerUserId"] for entry in created["credentialLinks"]] == ["u1", "u3"]
    assert created["credentialLinks"][0]["link"]["link"].endswith("SECRET")
    throttled = [p for p in report["problems"] if "could not be issued" in p["reason"]]
    assert len(throttled) == 1 and throttled[0]["severity"] == "warning"
    assert throttled[0]["reason"].startswith("Ravi: ")
    assert "SECRET" not in str(world["db"].sanctionorderimport.written[-1])


async def test_the_ledger_row_records_the_upload_and_never_fails_the_import(world, monkeypatch):
    async def fake_create(payload: Any, officer: Any) -> dict[str, Any]:
        return {
            "sanctionOrder": {
                "id": "s1",
                "sanctionOrderNo": payload.sanctionOrderNo,
                "designWorkshopId": "w1",
                "designers": [],
            }
        }

    monkeypatch.setattr(sanction_orders, "create_from_sanction", fake_create)
    report = await sanction_import.apply_confirmed_rows(confirm_body(), Officer())
    assert report["importId"] == "import-1"
    written = world["db"].sanctionorderimport.written[0]
    assert written["sourceFilename"] == "orders.xlsx"
    assert written["recorded"] == 1
    assert written["uploadedById"] == "officer-1"

    class Exploding:
        async def create(self, *, data: dict[str, Any]) -> Any:
            raise RuntimeError("the ledger table is gone")

    world["db"].sanctionorderimport = Exploding()
    survived = await sanction_import.apply_confirmed_rows(confirm_body(), Officer())
    assert survived["importId"] is None
    assert survived["recorded"] == 1


