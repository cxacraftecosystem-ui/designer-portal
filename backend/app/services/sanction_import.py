from __future__ import annotations

import logging
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from typing import Any

from fastapi import HTTPException

from app.core.db import db
from app.schemas.sanction_orders import (
    MAX_SANCTION_DESIGNERS,
    SanctionImportConfirm,
    SanctionImportRowIn,
    SanctionOrderCoDesigner,
    SanctionOrderImportRow,
)
from app.services import designers, sanction_orders
from app.services.sanction_orders_xlsx import (
    MAX_SANCTION_ROWS,
    ParsedSanctionRow,
    ParsedSanctionSheet,
    fold_name,
    fold_order_no,
)
from app.services.xlsx_table import ParseProblem

log: logging.Logger = logging.getLogger(__name__)

__all__ = [
    "MAX_UPLOAD_BYTES",
    "apply_confirmed_rows",
    "review_sheet",
]

MAX_UPLOAD_BYTES = 4 * 1024 * 1024

READY = "ready"
REVIEW = "review"
REFUSED = "refused"



@dataclass
class ProposedDesigner:
    name: str
    email: str
    canonical: str
    userId: str | None = None
    accountExists: bool = False
    empanelled: bool = False
    nameSource: str = "sheet"

    def payload(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "email": self.email,
            "canonicalEmail": self.canonical,
            "userId": self.userId,
            "accountExists": self.accountExists,
            "empanelled": self.empanelled,
            "nameSource": self.nameSource,
        }


@dataclass
class RowQuestion:
    code: str
    question: str
    candidates: list[dict[str, Any]] = field(default_factory=list)

    def payload(self) -> dict[str, Any]:
        return {"code": self.code, "question": self.question, "candidates": self.candidates}


@dataclass
class ReviewedRow:
    sheetRow: int
    sanctionOrderNo: str
    sanctionOrderDate: date | None
    sanctionAmount: Decimal | None
    notes: str | None
    verdict: str
    designers: list[ProposedDesigner] = field(default_factory=list)
    questions: list[RowQuestion] = field(default_factory=list)
    reason: str | None = None
    unpairedNames: list[str] = field(default_factory=list)
    unpairedEmails: list[str] = field(default_factory=list)

    def payload(self) -> dict[str, Any]:
        return {
            "sheetRow": self.sheetRow,
            "sanctionOrderNo": self.sanctionOrderNo,
            "sanctionOrderDate": (
                self.sanctionOrderDate.isoformat() if self.sanctionOrderDate else None
            ),
            "sanctionAmount": (str(self.sanctionAmount) if self.sanctionAmount is not None else None),
            "notes": self.notes,
            "verdict": self.verdict,
            "reason": self.reason,
            "designers": [designer.payload() for designer in self.designers],
            "questions": [question.payload() for question in self.questions],
            "unpairedNames": list(self.unpairedNames),
            "unpairedEmails": list(self.unpairedEmails),
        }



def _problem(
    row: int | None, severity: str, reason: str, sheet: str | None, value: str | None = None
) -> ParseProblem:
    return ParseProblem(sheet or "", row, severity, reason, value)


async def _roster_rows_by_name(names: list[str]) -> dict[str, list[Any]]:
    wanted = [name for name in names if name][:_NAME_LOOKUP_CAP]
    if not wanted:
        return {}
    rows = await db.designerroster.find_many(
        where={
            "isActive": True,
            "OR": [{"fullName": {"equals": name, "mode": "insensitive"}} for name in wanted],
        },
        take=_NAME_LOOKUP_CAP * 4,
    )
    index: dict[str, list[Any]] = {}
    for row in rows:
        folded = fold_name(getattr(row, "fullName", None))
        if folded:
            index.setdefault(folded, []).append(row)
    return index


_NAME_LOOKUP_CAP = 400


def _pair_cells(
    row: ParsedSanctionRow, sheet: str | None, problems: list[ParseProblem]
) -> tuple[list[tuple[str, str]], list[str], list[str], list[RowQuestion]]:
    names = list(row.designerNames)
    emails = list(row.designerEmails)
    questions: list[RowQuestion] = []

    aligned = len(names) == len(emails) and bool(emails)
    kept_emails: list[str] = []
    kept_names: list[str] = []
    seen: dict[str, str] = {}
    for index, address in enumerate(emails):
        mailbox = designers.canonical_email(address)
        if not mailbox:
            kept_emails.append(address)
            if aligned:
                kept_names.append(names[index])
            continue
        if mailbox in seen:
            first = seen[mailbox]
            problems.append(
                _problem(
                    row.sheetRow,
                    "warning",
                    (
                        f"'{address}' and '{first}' are the same mailbox, so this order names that "
                        "designer once. The first spelling was kept."
                        if first.lower() != address.lower()
                        else f"'{address}' is named twice on this row; it was recorded once."
                    ),
                    sheet,
                    address,
                )
            )
            continue
        seen[mailbox] = address
        kept_emails.append(address)
        if aligned:
            kept_names.append(names[index])
    if aligned:
        names = kept_names
    emails = kept_emails

    if not names and not emails:
        return [], [], [], questions

    if not emails:
        questions.append(
            RowQuestion(
                "no-addresses",
                (
                    "This row names "
                    + _english_list(names)
                    + " but gives no email address. A name is not an identity — two designers share "
                    "a name more often than this register would like — so the address has to be "
                    "confirmed before an account is created or a workshop is opened."
                ),
            )
        )
        return [(name, "") for name in names], [], [], questions

    if not names:
        return [("", address) for address in emails], [], [], questions

    if len(names) != len(emails):
        paired = min(len(names), len(emails))
        questions.append(
            RowQuestion(
                "counts-differ",
                (
                    f"This row names {len(names)} "
                    f"{'designer' if len(names) == 1 else 'designers'} and gives {len(emails)} "
                    f"{'address' if len(emails) == 1 else 'addresses'}. The first {paired} of each "
                    "have been paired in the order they were typed; which designer the rest belong "
                    "to cannot be worked out from the sheet."
                ),
            )
        )
        return (
            list(zip(names[:paired], emails[:paired], strict=True)),
            names[paired:],
            emails[paired:],
            questions,
        )

    return list(zip(names, emails, strict=True)), [], [], questions


def _english_list(items: list[str]) -> str:
    clean = [item for item in items if item]
    if not clean:
        return "nobody"
    if len(clean) == 1:
        return clean[0]
    return ", ".join(clean[:-1]) + " and " + clean[-1]


def _candidate(name: str | None, email: str | None, *, source: str) -> dict[str, Any]:
    return {"name": name or "", "email": email or "", "source": source}


async def _review_one_row(
    row: ParsedSanctionRow,
    *,
    sheet: str | None,
    officer: Any,
    by_name: dict[str, list[Any]],
    problems: list[ParseProblem],
    standing: dict[str, Any],
    roster_rows: dict[str, Any],
) -> ReviewedRow:
    reviewed = ReviewedRow(
        sheetRow=row.sheetRow,
        sanctionOrderNo=row.sanctionOrderNo,
        sanctionOrderDate=row.sanctionOrderDate,
        sanctionAmount=row.sanctionAmount,
        notes=row.notes,
        verdict=READY,
    )

    def refuse(reason: str) -> ReviewedRow:
        reviewed.verdict = REFUSED
        reviewed.reason = reason
        reviewed.questions = []
        problems.append(_problem(row.sheetRow, "error", reason, sheet, row.sanctionOrderNo))
        return reviewed

    if row.sanctionOrderDate is None:
        return refuse(
            f"Sanction order '{row.sanctionOrderNo}' has no date that could be read, so it cannot "
            "be recorded. The date is one of the three facts the instrument consists of."
        )
    if row.sanctionAmount is None:
        return refuse(
            f"Sanction order '{row.sanctionOrderNo}' has no amount that could be read, so it cannot "
            "be recorded. The amount is one of the three facts the instrument consists of."
        )

    taken = await sanction_orders.duplicate_reason(fold_order_no(row.sanctionOrderNo))
    if taken is not None:
        return refuse(taken)

    pairs, unpaired_names, unpaired_emails, questions = _pair_cells(row, sheet, problems)
    reviewed.unpairedNames = unpaired_names
    reviewed.unpairedEmails = unpaired_emails
    reviewed.questions = questions

    if not pairs:
        return refuse(
            f"Sanction order '{row.sanctionOrderNo}' names no designer at all — both the name and "
            "the address column are empty. An order authorises somebody's work; there is nobody "
            "here for it to authorise."
        )

    if len(pairs) > MAX_SANCTION_DESIGNERS:
        return refuse(
            f"Sanction order '{row.sanctionOrderNo}' names {len(pairs)} designers, and one order "
            f"can name at most {MAX_SANCTION_DESIGNERS} — the same number of people one workshop "
            "can be opened for. Split the work across two orders."
        )

    assumed_names: list[str] = []

    for name, address in pairs:
        if not address:
            matches = by_name.get(fold_name(name), [])
            question = reviewed.questions[0] if reviewed.questions else None
            if question is not None and question.code == "no-addresses":
                question.candidates.extend(
                    _candidate(
                        getattr(match, "fullName", None),
                        getattr(match, "email", None),
                        source="roster",
                    )
                    for match in matches
                )
            reviewed.designers.append(ProposedDesigner(name=name, email="", canonical=""))
            continue

        verdict = standing.get(designers.canonical_email(address))
        if verdict is None:
            verdict = await sanction_orders.designer_standing_verdict(address, officer=officer)
            standing[verdict.canonical] = verdict

        if verdict.refusal is not None:
            return refuse(verdict.refusal.detail)

        account = verdict.user
        if verdict.canonical in roster_rows:
            roster = roster_rows[verdict.canonical]
        else:
            roster = await db.designerroster.find_first(where={"email": {"in": verdict.keys}})
            roster_rows[verdict.canonical] = roster
        empanelled = bool(roster is not None and getattr(roster, "isActive", False))

        on_file = (
            str(getattr(account, "name", "") or "").strip()
            or str(getattr(roster, "fullName", "") or "").strip()
        )
        proposed = ProposedDesigner(
            name=name,
            email=address,
            canonical=verdict.canonical,
            userId=getattr(account, "id", None),
            accountExists=account is not None,
            empanelled=empanelled,
        )

        if not name:
            if on_file:
                proposed.name = on_file
                proposed.nameSource = "record"
                assumed_names.append(on_file)
            else:
                reviewed.questions.append(
                    RowQuestion(
                        "unknown-name",
                        (
                            f"No name was given for {address}, and this address has never been used "
                            "on this platform, so there is no record to take a name from. A "
                            "sanction order has to name the designer it was issued to."
                        ),
                    )
                )
        elif on_file and fold_name(on_file) != fold_name(name):
            others = [
                match
                for match in by_name.get(fold_name(name), [])
                if designers.canonical_email(getattr(match, "email", None)) != verdict.canonical
            ]
            if others:
                reviewed.questions.append(
                    RowQuestion(
                        "name-and-address-disagree",
                        (
                            f"This row pairs the name '{name}' with the address {address}, but that "
                            f"address belongs to {on_file} and '{name}' is somebody else on the "
                            "designer roster. A column that has slipped by one row looks exactly "
                            "like this. Which of them is this order for?"
                        ),
                        candidates=[
                            _candidate(on_file, address, source="address"),
                            *(
                                _candidate(
                                    getattr(match, "fullName", None),
                                    getattr(match, "email", None),
                                    source="name",
                                )
                                for match in others
                            ),
                        ],
                    )
                )
            else:
                problems.append(
                    _problem(
                        row.sheetRow,
                        "warning",
                        (
                            f"Designer name differs: this sheet says '{name}' and the stored record "
                            f"for {address} says '{on_file}'. The address is what identifies a "
                            f"designer, so the order was prepared for {on_file} and the stored "
                            "record was not changed."
                        ),
                        sheet,
                        name,
                    )
                )
                proposed.name = on_file
                proposed.nameSource = "record"

        reviewed.designers.append(proposed)

    if assumed_names:
        problems.append(
            _problem(
                row.sheetRow,
                "warning",
                (
                    "No designer name was given, so the name already on file was used: "
                    f"{_english_list(assumed_names)}."
                ),
                sheet,
                None,
            )
        )

    if reviewed.questions:
        reviewed.verdict = REVIEW
    return reviewed


async def review_sheet(
    parsed: ParsedSanctionSheet, *, officer: Any
) -> dict[str, Any]:
    problems = list(parsed.problems)
    names_to_look_up: list[str] = []
    for row in parsed.rows:
        names_to_look_up.extend(row.designerNames)
    distinct = list(dict.fromkeys(name for name in names_to_look_up if name))
    by_name = await _roster_rows_by_name(distinct)
    if len(distinct) > _NAME_LOOKUP_CAP:
        problems.append(
            _problem(
                None,
                "warning",
                (
                    f"This sheet names {len(distinct)} different designers, and names were looked up "
                    f"against the roster for the first {_NAME_LOOKUP_CAP} of them. Rows below that "
                    "were still checked by email address, which is what identifies a designer; what "
                    "was not checked is whether a NAME on those rows belongs to somebody else."
                ),
                parsed.sheet,
            )
        )

    standing: dict[str, Any] = {}
    roster_rows: dict[str, Any] = {}

    reviewed: list[ReviewedRow] = []
    for row in parsed.rows:
        reviewed.append(
            await _review_one_row(
                row,
                sheet=parsed.sheet,
                officer=officer,
                by_name=by_name,
                problems=problems,
                standing=standing,
                roster_rows=roster_rows,
            )
        )

    return {
        "sheet": parsed.sheet,
        "sourceFilename": parsed.sourceFilename,
        "rowsRead": parsed.rowCount + _rows_the_parser_dropped(problems, parsed),
        "ready": [row.payload() for row in reviewed if row.verdict == READY],
        "needsReview": [row.payload() for row in reviewed if row.verdict == REVIEW],
        "refused": [row.payload() for row in reviewed if row.verdict == REFUSED],
        "problems": [problem.payload() for problem in problems],
    }


def _rows_the_parser_dropped(problems: list[ParseProblem], parsed: ParsedSanctionSheet) -> int:
    parsed_rows = {row.sheetRow for row in parsed.rows}
    dropped = {
        problem.row
        for problem in problems
        if problem.severity == "error" and problem.row and problem.row not in parsed_rows
    }
    return len(dropped)



def _create_body(row: SanctionImportRowIn, *, sheet_filename: str | None) -> SanctionOrderImportRow:
    lead, *rest = row.designers
    return SanctionOrderImportRow(
        sanctionOrderNo=row.sanctionOrderNo,
        sanctionOrderDate=row.sanctionOrderDate,
        sanctionAmount=row.sanctionAmount,
        designerName=lead.name,
        designerEmail=lead.email,
        notes=row.notes,
        coDesigners=[SanctionOrderCoDesigner(name=co.name, email=co.email) for co in rest],
        sourceFilename=sheet_filename,
        sheetRow=row.sheetRow,
    )


async def apply_confirmed_rows(body: SanctionImportConfirm, officer: Any) -> dict[str, Any]:
    recorded: list[dict[str, Any]] = []
    problems: list[ParseProblem] = []
    skipped = 0
    refused = body.refusedBeforeConfirm
    accounts_created = 0
    links_issued = 0

    for row in body.rows[:MAX_SANCTION_ROWS]:
        if row.action == "skip":
            skipped += 1
            problems.append(
                _problem(
                    row.sheetRow,
                    "warning",
                    (
                        f"Sanction order '{row.sanctionOrderNo}' was not recorded because you chose "
                        "to leave it out. Correct the row in the sheet and upload again if it "
                        "should be on the register."
                    ),
                    body.sheet,
                    row.sanctionOrderNo,
                )
            )
            continue

        if not row.designers:
            refused += 1
            problems.append(
                _problem(
                    row.sheetRow,
                    "error",
                    (
                        f"Sanction order '{row.sanctionOrderNo}' was sent for recording with no "
                        "designer on it, so nothing was written. An order authorises somebody's "
                        "work."
                    ),
                    body.sheet,
                    row.sanctionOrderNo,
                )
            )
            continue

        try:
            created = await sanction_orders.create_from_sanction(
                _create_body(row, sheet_filename=body.sourceFilename), officer
            )
        except HTTPException as exc:
            refused += 1
            problems.append(
                _problem(
                    row.sheetRow,
                    "error",
                    str(exc.detail),
                    body.sheet,
                    row.sanctionOrderNo,
                )
            )
            continue
        except Exception:
            refused += 1
            log.exception(
                "sanction import: row %s (%s) failed and was not recorded",
                row.sheetRow,
                row.sanctionOrderNo,
            )
            problems.append(
                _problem(
                    row.sheetRow,
                    "error",
                    (
                        f"Sanction order '{row.sanctionOrderNo}' couldn't be recorded because "
                        "something went wrong. Nothing was written for this row. Every other row in "
                        "this upload was handled independently; try this one on the form above."
                    ),
                    body.sheet,
                    row.sanctionOrderNo,
                )
            )
            continue

        order = created["sanctionOrder"]
        minted = [
            designer
            for designer in order.get("designers", [])
            if designer.get("accountCreated")
        ]
        accounts_created += len(minted)
        links = list(created.get("credentialLinks") or [])
        links_issued += sum(1 for entry in links if entry.get("link"))
        for entry in links:
            if entry.get("problem"):
                problems.append(
                    _problem(
                        row.sheetRow,
                        "warning",
                        f"{entry.get('designerName') or entry.get('signInEmail')}: "
                        f"{entry['problem']}",
                        body.sheet,
                        row.sanctionOrderNo,
                    )
                )
        recorded.append(
            {
                "sheetRow": row.sheetRow,
                "sanctionOrderId": order["id"],
                "sanctionOrderNo": order["sanctionOrderNo"],
                "designWorkshopId": order["designWorkshopId"],
                "designers": order.get("designers", []),
                "accountsCreated": len(minted),
                "sanctionOrder": order,
                "credentialLinks": links,
            }
        )

    ledger = await _write_the_ledger_row(
        body,
        officer=officer,
        recorded=len(recorded),
        skipped=skipped,
        refused=refused,
        problems=problems,
    )

    return {
        "sheet": body.sheet,
        "sourceFilename": body.sourceFilename,
        "rowsRead": body.rowsRead,
        "recorded": len(recorded),
        "skipped": skipped,
        "refused": refused,
        "accountsCreated": accounts_created,
        "credentialLinksIssued": links_issued,
        "created": recorded,
        "importId": ledger,
        "problems": [problem.payload() for problem in problems],
    }


async def _write_the_ledger_row(
    body: SanctionImportConfirm,
    *,
    officer: Any,
    recorded: int,
    skipped: int,
    refused: int,
    problems: list[ParseProblem],
) -> str | None:
    try:
        row = await db.sanctionorderimport.create(
            data={
                "sourceFilename": body.sourceFilename,
                "sheetName": body.sheet,
                "rowsRead": body.rowsRead,
                "recorded": recorded,
                "skipped": skipped,
                "refused": refused,
                "problems": [problem.payload() for problem in problems],
                "uploadedById": getattr(officer, "id", None),
            }
        )
    except Exception:
        log.exception(
            "sanction import: the ledger row could not be written. %s orders WERE recorded.",
            recorded,
        )
        return None
    return str(getattr(row, "id", "")) or None
