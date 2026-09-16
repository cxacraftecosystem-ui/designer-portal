"""THE SANCTION IMPORT'S RECONCILIATION — what it settles, what it asks, and what it refuses flat.

NO DATABASE. Every read this module makes is stubbed, which is deliberate rather than convenient:
the failures being guarded against are all failures of JUDGEMENT — did this row need a human, or was
it settled without being asked — and a judgement is checkable without a row anywhere.

══ THE ONE RULE EVERYTHING HERE IS ABOUT ═══════════════════════════════════════════════════════

    Auto-resolve everything mechanical. Ask only where a human could legitimately have meant
    something different. Refuse, without asking, anything that would overturn a decision an
    administrator took on a screen.

**Both halves of that fail silently and in opposite directions**, which is why they are tested as
carefully as each other:

* an importer that asks too little files a ministry workshop under the wrong designer, mints them an
  account and issues a credential, under a 201 that says everything went fine;
* an importer that asks too much produces forty questions an officer answers the way they answer a
  cookie banner — at which point the confirmation step protects nothing at all, and the one row that
  genuinely needed a decision went through with the rest.

So each test below names which of the nineteen enumerated failures it is about, and several assert
the ABSENCE of a question as firmly as others assert its presence.
"""

from __future__ import annotations

import inspect
from datetime import date
from decimal import Decimal
from typing import Any

import pytest

from app.services import sanction_import, sanction_orders
from app.services.sanction_import import READY, REFUSED, REVIEW
from app.services.sanction_orders import Refusal, StandingVerdict
from app.services.sanction_orders_xlsx import ParsedSanctionRow, ParsedSanctionSheet
from app.services.xlsx_table import ParseProblem


class Officer:
    id = "officer-1"
    name = "A. K. Officer"
    email = "officer@ministry.gov.in"
    role = "ASSISTANT_DIRECTOR"


class Account:
    """The smallest thing the reconciliation reads a resolved account off."""

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
    """A stubbed register and roster, with every read this module makes wired to plain dicts.

    ``accounts`` and ``roster`` are keyed on the CANONICAL mailbox, because that is what both rosters
    and the register are keyed on in the real thing — a fixture keyed on the literal address would
    quietly make the dotted-Gmail cases untestable, which is exactly the class of bug this feature
    keeps running into.
    """

    state: dict[str, Any] = {
        "accounts": {},  # canonical mailbox -> Account
        "roster": {},  # canonical mailbox -> RosterRow
        "taken": {},  # folded order key -> refusal sentence
        "refusals": {},  # canonical mailbox -> Refusal
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
    """The one reviewed row, whichever of the three lists it landed in."""
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


# --------------------------------------------------------------------------------------
# The ordinary row — and the rule that it must NOT be asked about
# --------------------------------------------------------------------------------------


async def test_one_name_one_address_and_nobody_on_file_is_recorded_without_a_question(world):
    """FAILURE #6, THE ORDINARY CASE, AND THE MOST IMPORTANT ASSERTION IN THIS FILE.

    A designer with no account and no roster row is not an error — it is the reason the feature
    exists. The order admits them, empanels them, mints the account and issues the link. If this row
    ever starts asking a question, the confirmation step has become a wall in front of the ordinary
    path and officers will click through it, taking the rows that DO matter with them.

    ``problems == []`` is asserted as firmly as the verdict: nothing was assumed, so there is nothing
    to warn about.
    """
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
    """"RAMESH  KUMAR" against a record saying "Ramesh Kumar" is typing, not disagreement.

    An officer asked to adjudicate the capitalisation of a name they typed themselves learns that
    this dialog is noise. Folded — NFKC, whitespace, then case — and silent. What is STORED is never
    the folded form; the record's own spelling wins, because that is the account the order is filed
    under.
    """
    world["accounts"]["rkumar@gmail.com"] = Account("u1", "Ramesh Kumar", "r.kumar@gmail.com")
    answer = await review(world, parsed_row(names=["RAMESH  KUMAR"], emails=["r.kumar@gmail.com"]))
    row = only(answer)
    assert row["verdict"] == READY
    assert row["questions"] == []
    assert warnings_for(answer, 2) == []


async def test_a_team_keeps_the_order_it_was_typed_in_and_the_lead_is_first(world):
    """Position 0 is the lead — the name that reaches the report cover.

    The sheet's order IS the officer's answer to "who leads this", said on the pro-forma's help
    sheet in as many words. A reconciliation that sorted the team would move the name on a ministry
    document without anybody choosing to.
    """
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


# --------------------------------------------------------------------------------------
# Structural: the two cells disagree about shape
# --------------------------------------------------------------------------------------


async def test_two_names_and_three_addresses_goes_to_the_human_with_the_surplus_named(world):
    """FAILURE #2. Pairing "as far as they go" and importing is the defect, not the remedy.

    An extra address could belong at any position; taking the first two pairs and dropping the third
    silently would file work under the right people and lose a designer, and taking three names with
    two addresses would pair somebody with a stranger's mailbox. Both lists travel, paired as far as
    they go, with the surplus in its own field so the officer can see WHERE the two stopped agreeing.
    """
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
    """FAILURE #4. **H, always — even when the name resolves perfectly.**

    A name is not an identity. The cost of being wrong here is a workshop, an account and a
    credential issued to somebody who shares a name with the intended designer, and there is no
    delete on this register. So the roster's answer is offered as a CANDIDATE to accept rather than
    taken: the officer is agreeing to a proposal, which is a different act from not being asked.
    """
    world["roster"]["kavita@gmail.com"] = RosterRow("kavita@gmail.com", "Kavita Rao")
    answer = await review(world, parsed_row(names=["Kavita Rao"], emails=[]))
    row = only(answer)
    assert row["verdict"] == REVIEW
    assert codes(row) == ["no-addresses"]
    assert row["questions"][0]["candidates"] == [
        {"name": "Kavita Rao", "email": "kavita@gmail.com", "source": "roster"}
    ]


async def test_no_addresses_and_no_roster_match_asks_with_nothing_to_offer(world):
    """FAILURE #4's other arm: the question stands, and the candidate list is honestly empty.

    An empty candidate list is not an empty dropdown — it means the answer has to be typed. Inventing
    a near-match to put in it would be the silent pick this whole case exists to prevent.
    """
    answer = await review(world, parsed_row(names=["Somebody New"], emails=[]))
    row = only(answer)
    assert row["verdict"] == REVIEW
    assert codes(row) == ["no-addresses"]
    assert row["questions"][0]["candidates"] == []


async def test_no_names_but_a_known_address_takes_the_name_on_file_and_says_so(world):
    """FAILURE #3, auto-resolved. The address is the identity and the record has the name.

    The warning is not decoration: "we filled this in for you" is exactly the assumption an officer
    should be able to see and disagree with, and ``nameSource`` is what lets the screen mark it.
    """
    world["accounts"]["rkumar@gmail.com"] = Account("u1", "Ramesh Kumar", "r.kumar@gmail.com")
    answer = await review(world, parsed_row(names=[], emails=["r.kumar@gmail.com"]))
    row = only(answer)
    assert row["verdict"] == READY
    assert row["designers"][0]["name"] == "Ramesh Kumar"
    assert row["designers"][0]["nameSource"] == "record"
    assert any("name already on file was used" in text for text in warnings_for(answer, 2))


async def test_no_names_and_an_address_nobody_knows_has_to_ask(world):
    """FAILURE #3's second arm, and the briefing was wrong to call the whole case auto-resolvable.

    A brand-new address is the ORDINARY case on this feature — and it is precisely the case with no
    record to take a name from. ``SanctionOrderCreate.designerName`` is required, so there is
    genuinely nothing to record. Importing under a blank or a derived-from-the-mailbox name would put
    "r.kumar" on a ministry instrument.
    """
    answer = await review(world, parsed_row(names=[], emails=["stranger@gmail.com"]))
    row = only(answer)
    assert row["verdict"] == REVIEW
    assert codes(row) == ["unknown-name"]


async def test_both_cells_empty_is_refused_and_never_offered_for_confirmation(world):
    """FAILURE #5. There is nobody here for the order to authorise, and no question can supply one."""
    answer = await review(world, parsed_row(names=[], emails=[]))
    row = only(answer)
    assert row["verdict"] == REFUSED
    assert "names no designer at all" in row["reason"]
    assert row["questions"] == []


# --------------------------------------------------------------------------------------
# Identity: the name and the address point at different people
# --------------------------------------------------------------------------------------


async def test_a_name_that_merely_differs_from_the_record_is_imported_with_a_warning(world):
    """FAILURE #7. The ADDRESS is the identity; the stored record is not changed.

    'R. Kumar' on the sheet against 'Ramesh Kumar' on file is an abbreviation, and abbreviations are
    not decisions. The order is prepared under the record's own name — putting the sheet's spelling
    on the instrument would make it disagree with the account it is filed under — and the warning
    says both, in the shape ``artisan_import._report_disagreements`` already uses.
    """
    world["accounts"]["rkumar@gmail.com"] = Account("u1", "Ramesh Kumar", "r.kumar@gmail.com")
    answer = await review(world, parsed_row(names=["R. Kumar"], emails=["r.kumar@gmail.com"]))
    row = only(answer)
    assert row["verdict"] == READY
    assert row["questions"] == []
    assert row["designers"][0]["name"] == "Ramesh Kumar"
    assert row["designers"][0]["nameSource"] == "record"
    assert any("Designer name differs" in text for text in warnings_for(answer, 2))


async def test_a_name_and_an_address_naming_two_known_people_goes_to_the_human(world):
    """FAILURE #8 — the signature of a column that has slipped by one row, and the sharpest case.

    Both people exist. Both are plausible. Guessing files a ministry workshop, an account and a
    credential under the wrong one, on a register with no delete. So both candidates travel, each
    labelled with WHICH COLUMN produced it, and the officer chooses.
    """
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
    """FAILURE #9, AND THE NARROWNESS OF #8's TEST IS THE WHOLE POINT OF THIS ONE.

    Here the address resolves to the SAME person whose name is on the sheet, and a second designer
    on the roster happens to share that name. Nothing is in dispute: the address decided, and the
    fact that somebody else is also called Ramesh Kumar is a fact about the roster rather than about
    this row.

    A test for #8 written as "does this name exist elsewhere" would fire on every order naming a
    common name — which is the "asks about everything" failure, and it would have shipped looking
    like caution.
    """
    world["accounts"]["rkumar@gmail.com"] = Account("u1", "Ramesh Kumar", "r.kumar@gmail.com")
    world["roster"]["rkumar@gmail.com"] = RosterRow("r.kumar@gmail.com", "Ramesh Kumar")
    world["roster"]["other@gmail.com"] = RosterRow("other@gmail.com", "Ramesh Kumar")
    answer = await review(world, parsed_row(names=["Ramesh Kumar"], emails=["r.kumar@gmail.com"]))
    row = only(answer)
    assert row["verdict"] == READY
    assert row["questions"] == []
    assert warnings_for(answer, 2) == []


async def test_an_account_with_no_empanelment_is_ordinary_and_not_a_refusal(world):
    """FAILURE #11. Never having been empanelled is the state of most people this feature names.

    Only an empanelment somebody ENDED is refused. ``ensure_empanelled`` writes the missing row
    inside the order's own transaction, which is the whole point of the flow.
    """
    world["accounts"]["rkumar@gmail.com"] = Account("u1", "Ramesh Kumar", "r.kumar@gmail.com")
    answer = await review(world, parsed_row(names=["Ramesh Kumar"], emails=["r.kumar@gmail.com"]))
    row = only(answer)
    assert row["verdict"] == READY
    assert row["designers"][0]["empanelled"] is False
    assert row["designers"][0]["accountExists"] is True


# --------------------------------------------------------------------------------------
# Duplication
# --------------------------------------------------------------------------------------


async def test_the_same_address_twice_in_one_cell_collapses_and_takes_its_name_with_it(world):
    """FAILURE #12, AND THE ASSERTION THAT MATTERS IS THE SECOND ONE.

    Dropping the duplicate ADDRESS while leaving three names behind would offset every pairing below
    it by one — "Ramesh, Ramesh, Kavita" against "r@, r@, k@" would leave Kavita paired with
    Ramesh's mailbox, which is a de-duplication that has quietly become a mis-pairing. The name goes
    with the address it was a duplicate of.
    """
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
    """FAILURE #13. Invisible otherwise, and not cosmetic.

    ``r.kumar@gmail.com`` and ``rkumar+dch@gmail.com`` are one mailbox, one allow-list row, one
    empanelment and one account. Two rows for that account would violate
    ``SanctionOrderDesigner``'s composite primary key at the LAST statement of the order's
    transaction — rolling back an order whose accounts had already been minted.
    """
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
    """FAILURE #14, which is not one. Two sanction orders for one designer is ordinary.

    ``artisan_import`` refuses the second occurrence of a person because a person cannot be recorded
    twice; a SANCTION can name them twice, and an importer that copied that rule would refuse half
    of a real ministry sheet. Nothing is collapsed across rows and no problem is emitted.
    """
    answer = await review(
        world,
        parsed_row(row=2, no="SO/1", names=["Ramesh"], emails=["r@gmail.com"]),
        parsed_row(row=3, no="SO/2", names=["Ramesh"], emails=["r@gmail.com"]),
    )
    assert len(answer["ready"]) == 2
    assert answer["problems"] == []


async def test_a_number_already_in_the_register_is_refused_in_the_registers_own_words(world):
    """FAILURE #15's register half, and the sentence is imported rather than re-worded.

    ``SANCTION_DUPLICATE_TEMPLATE`` names the existing order, its workshop, its designer and the day
    it was recorded, because the officer's next move differs between "I already did this" and "the
    ministry issued two orders with one number". A paraphrase here would be a fourth wording of a
    rule three surfaces already agree on.
    """
    world["taken"]["SO202642"] = "Sanction order SO/2026/42 is already recorded. It opened ..."
    answer = await review(world, parsed_row(names=["Ramesh"], emails=["r@gmail.com"]))
    row = only(answer)
    assert row["verdict"] == REFUSED
    assert row["reason"] == world["taken"]["SO202642"]


# --------------------------------------------------------------------------------------
# The five that are never offered for confirmation
# --------------------------------------------------------------------------------------


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
    """FAILURES #10 and #16-19 — the governing rule, exercised through all four sentences.

    Each of these is a fact about a decision an ADMINISTRATOR took on a screen. A confirmation step
    that could overturn one would make a spreadsheet the senior authority in this product: an
    officer could re-admit somebody who was barred, revive an empanelment that was ended, or name
    themselves, by ticking a box on a dialog.

    So: REFUSED, with no question on the row at all, and the sentence is the one the officer's own
    form would have shown — asserted by identity against the service's constants rather than by
    substring, so a reworded copy in the importer cannot pass.
    """
    world["refusals"]["rkumar@gmail.com"] = Refusal(422, sentence)
    answer = await review(world, parsed_row(names=["Ramesh"], emails=["r.kumar@gmail.com"]))
    row = only(answer)
    assert row["verdict"] == REFUSED
    assert row["reason"] == sentence
    assert row["questions"] == []


async def test_a_standing_refusal_beats_a_question_on_the_same_row(world):
    """R BEATS H, and the precedence is asserted rather than left to the order of two ``if``s.

    This row BOTH has mismatched counts (a question) and names a barred address (a refusal). Offering
    it for confirmation would ask the officer to settle a pairing for an order that can never be
    recorded — and would imply that settling it would be enough.
    """
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


# --------------------------------------------------------------------------------------
# The three facts of the instrument
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("kwargs", "word"),
    [({"when": None}, "no date"), ({"amount": None}, "no amount")],
)
async def test_an_order_with_no_date_or_no_amount_is_refused(world, kwargs, word):
    """The parser says what it could not READ; this says what that means for the INSTRUMENT.

    Two sentences, neither repeating the other, both on the report. "The Sanctioned Amount 'abc' is
    not an amount this can read" tells an officer which cell to fix; "the amount is one of the three
    facts the instrument consists of" tells them why the row is not on the register.
    """
    answer = await review(
        world, parsed_row(names=["Ramesh"], emails=["r@gmail.com"], **kwargs)
    )
    row = only(answer)
    assert row["verdict"] == REFUSED
    assert word in row["reason"]


async def test_a_team_longer_than_the_cap_is_refused_with_the_number_in_the_sentence(world):
    """The team cap is ``MAX_DESIGN_WORKSHOP_VIEWERS`` reached through a second door.

    Every named designer gets a viewer row on the workshop this order opens, so the two bounds are
    one bound. Refused here with a sentence rather than by pydantic at the end of the import, where
    the officer has already agreed to the row.
    """
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


# --------------------------------------------------------------------------------------
# The counts, which must add up on screen
# --------------------------------------------------------------------------------------


async def test_rows_the_parser_dropped_are_still_counted_as_read(world):
    """``rowsRead`` is the SHEET's size and not the size of the three lists.

    A row with no order number, or a second row carrying a number already used, never becomes a
    parsed row — so deriving ``rowsRead`` from ``ready + needsReview + refused`` would under-report
    the file the officer is holding, and the panel's arithmetic would say the sheet was smaller than
    it is. Counted by DISTINCT ROW NUMBER, because one row can carry several problems.
    """
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
    """Every count including the zeroes is drawn, which is the only way the arithmetic is checkable."""
    answer = await review(world, parsed_row(names=["Ramesh"], emails=["r@gmail.com"]))
    assert answer["ready"] and answer["needsReview"] == [] and answer["refused"] == []
    for key in ("sheet", "sourceFilename", "rowsRead", "ready", "needsReview", "refused", "problems"):
        assert key in answer, key


async def test_the_preview_writes_nothing(world):
    """An officer who uploads the wrong file and closes the tab has changed nothing at all.

    Asserted against the one table this module can write — the ledger — because everything else it
    could touch goes through ``create_from_sanction``, which the preview never calls.
    """
    await review(world, parsed_row(names=["Ramesh"], emails=["r@gmail.com"]))
    assert world["db"].sanctionorderimport.written == []


# --------------------------------------------------------------------------------------
# The write
# --------------------------------------------------------------------------------------


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
    """The confirmation screen's positional list, split back into the shape the create door takes.

    Position 0 is the lead everywhere in this feature — the pro-forma's help sheet says so, the
    confirmation screen says so, and THIS is the line that makes it true. A ``sorted()`` or a
    ``set()`` anywhere on this path would move the name that reaches ``<dc:creator>`` on a ministry
    report without anybody choosing to.
    """
    body = confirm_body()
    created = sanction_import._create_body(body.rows[0], sheet_filename="orders.xlsx")
    assert created.designerName == "Kavita Rao"
    assert created.designerEmail == "kavita@gmail.com"
    assert [co.name for co in created.coDesigners] == ["Anil Shah"]
    assert created.sourceFilename == "orders.xlsx"
    assert created.sheetRow == 2


def test_the_import_body_is_the_officers_own_form_plus_two_provenance_columns() -> None:
    """Every bound the form enforces is enforced on an imported order, BY THE SAME DECLARATION.

    An import that could write a value the form refuses would be a second rule for one column — and
    the officer would meet the 422 from pydantic at the end of a two-hundred-row import, after they
    had already agreed to the row on the confirmation screen.
    """
    from app.schemas.sanction_orders import SanctionOrderCreate, SanctionOrderImportRow

    assert issubclass(SanctionOrderImportRow, SanctionOrderCreate)
    extra = set(SanctionOrderImportRow.model_fields) - set(SanctionOrderCreate.model_fields)
    assert extra == {"sourceFilename", "sheetRow"}, extra


async def test_a_refused_row_does_not_cost_the_rows_around_it(world, monkeypatch):
    """**ONE TRANSACTION PER ORDER, AND THIS IS WHAT THAT BUYS.**

    ``apply_parsed_plan`` puts a whole sheet in one transaction and argues for it; this importer does
    the opposite, deliberately. Here the middle row raises the way a real refusal does, and the two
    either side are still recorded — where one transaction for the sheet would have rolled all three
    back over the one.

    The refusal travels VERBATIM onto the report, against its own Excel row.
    """
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
    """The officer chose it, and the report says which Excel row so the sheet can be corrected.

    ``rowsRead = recorded + skipped + refused`` only means anything if a skip is a COUNT rather than
    an absence — and "skipped" and "lost between the two requests" have to be distinguishable.
    """

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


async def test_no_sign_in_links_are_ever_returned_by_an_import(world, monkeypatch):
    """A recorded decision, and the report says how many accounts were made instead.

    Two hundred one-time credentials on one screen changes the SECURITY posture of the feature: the
    link is shown once and cannot be shown again, the officer's clipboard is the only transport, and
    a screen holding two hundred is a screen whose accidental closure strands two hundred designers.
    ``accountsCreated`` is what an officer actually needs — it is how many links they must re-issue.
    """

    async def fake_create(payload: Any, officer: Any) -> dict[str, Any]:
        return {
            "sanctionOrder": {
                "id": "s1",
                "sanctionOrderNo": payload.sanctionOrderNo,
                "designWorkshopId": "w1",
                "designers": [
                    {"designerUserId": "u1", "accountCreated": True},
                    {"designerUserId": "u2", "accountCreated": False},
                ],
            },
            "credentialLink": {"link": "https://example/reset/SECRET"},
            "credentialLinks": [{"link": "https://example/reset/SECRET"}],
        }

    monkeypatch.setattr(sanction_orders, "create_from_sanction", fake_create)
    report = await sanction_import.apply_confirmed_rows(confirm_body(), Officer())
    assert report["accountsCreated"] == 1
    assert report["credentialLinksIssued"] == 0
    assert "SECRET" not in str(report)


async def test_the_ledger_row_records_the_upload_and_never_fails_the_import(world, monkeypatch):
    """``SanctionOrderImport`` is written LAST, and a failure to write it costs nothing.

    The orders are already committed, each in its own transaction. An exception here would answer 500
    to an officer whose orders WERE recorded, and send them to record all of them again into two
    hundred duplicates. So it is logged, ``importId`` is honestly ``null``, and the report still goes
    back.
    """

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


# --------------------------------------------------------------------------------------
# The prose, which is load-bearing here
# --------------------------------------------------------------------------------------


def test_every_enumerated_failure_is_still_documented() -> None:
    """The module header enumerates nineteen failures and each is numbered.

    THAT HEADER IS THE SPECIFICATION and it is the only place the whole rule is written down. A
    future edit that adds a twentieth case to the code and not to the list leaves the next reader
    with an enumeration that quietly is not one — which on this feature means a reviewer who cannot
    tell whether a row that was never asked about was auto-resolved or simply forgotten.
    """
    header = inspect.getdoc(sanction_import) or ""
    for number in range(1, 20):
        assert f"\n{number:2d}. " in header or f"\n{number}. " in header, number
    assert "AUTO-RESOLVE EVERYTHING MECHANICAL" in header
    assert "ONE TRANSACTION PER ORDER" in header


def test_the_divergence_from_the_annual_plan_importer_is_written_down() -> None:
    """Two importers in one repository, and one deliberately does the opposite of the other.

    ``apply_parsed_plan`` puts the whole sheet in one transaction; this loops one per order. Without
    the divergence written down, the next reader sees two importers and assumes one copied the other
    — and "fixes" the loop into a single transaction, at which point one bad row at position 190
    rolls back 189 recorded ministry orders.
    """
    header = inspect.getdoc(sanction_import) or ""
    assert "apply_parsed_plan" in header
    assert "189" in header
