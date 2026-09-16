"""The officer's sanction-order bodies — THREE FACTS OF THE INSTRUMENT AND THE PEOPLE IT NAMES.

══ IT WAS "FIVE FIELDS AND A NOTE" UNTIL 0.0.12, AND THE SHORTNESS IS STILL THE POINT ═══════════

Order number, order date and sanctioned amount are what the ministry ISSUED, and they are three
boxes because that is the whole instrument. What changed is the other half: the form named exactly
one designer, and a sanction order is routinely issued for a team. :class:`SanctionOrderCreate` now
carries the lead as the two scalars it always did and the rest in ``coDesigners``.

**THE LEAD IS STILL TWO REQUIRED SCALARS AND NOT ELEMENT 0 OF A LIST**, which is a decision worth
reading before "tidying" it into one collection. ``APIModel`` is ``extra="forbid"``, so a body that
moved ``designerName``/``designerEmail`` into a list would 422 every deployed client at once; and a
single list would need a rule saying the lead is whichever element is first, which is a rule to get
wrong rather than a shape that cannot be. A body with no ``coDesigners`` is byte-for-byte the body
the officer's form has always sent.

══ WHY THIS MODULE IS SEPARATE FROM ``app.schemas.users`` ══════════════════════════════════════

The obvious implementation of "record a sanction order and create the designer's account" is to
post ``UserCreate`` and then post the order. It is not available and it should not be made
available. ``UserCreate.password`` is ``Field(min_length=8, max_length=256)`` with NO default
(``app/schemas/users.py``) and ``APIModel`` is ``extra="forbid"`` (``app/schemas/common.py``), so a
sanction body can neither omit a password nor add ``invite: true``; and ``POST /api/users`` is
``require_admin``, where ``is_admin`` is the SET ``{ADMIN, MASTER_ADMIN}`` and not a rank, so a
MINISTRY_ADMIN at 48 is refused by it outright. Loosening either — making the password optional on
the one route an administrator uses by hand, or widening the one route in this product that mints
accounts — is a real cost paid to serve a caller that does not use that door. The sanction service
mints its own unguessable password instead; the argument is written out at
``app/services/sanction_orders.py::create_from_sanction``.

══ THE MONEY IS A ``Decimal`` HERE AND A ``str`` ON THE WAY OUT, AND NEITHER IS EVER A ``float`` ══

``fastapi.encoders.jsonable_encoder`` converts ``decimal.Decimal`` to ``float`` — measured in this
repository's own venv on pydantic 2.13.5 / fastapi 0.141.1. That defect has already shipped here:
``ProductDocumentation.sellingPrice`` is ``Decimal? @db.Decimal(12, 2)``, the TypeScript type is
``sellingPrice?: string | number | null`` (a union that exists because nobody could say which
arrives) and the Android DTO decodes it as ``Double?``. A rupee amount is a binary float on every
handset in the field today. The route therefore builds its payload BY HAND with ``str(...)`` and
never calls ``jsonable_encoder`` on a sanction row;
``tests/test_sanction_orders.py::test_the_amount_never_reaches_the_wire_as_a_float`` reads the raw
response bytes so that a future refactor to ``jsonable_encoder(row)`` fails loudly instead of
quietly converting a ministry budget.
"""

from datetime import date
from decimal import Decimal
from typing import Literal

from pydantic import EmailStr, Field

from app.schemas.common import APIModel
from app.schemas.design_workshop_viewers import MAX_DESIGN_WORKSHOP_VIEWERS
from app.services.sanction_orders_xlsx import MAX_SANCTION_ROWS

#: How many designers one sanction order may name, lead included.
#:
#: IMPORTED AND NOT RETYPED. Every named designer is granted a ``DesignWorkshopViewer`` row on the
#: workshop this order opens, so this bound and ``MAX_DESIGN_WORKSHOP_VIEWERS`` are the same bound
#: reached by two doors: a number written here would be free to drift above the cap the viewers PUT
#: enforces, and an order naming 120 designers would then be refused half-way through its own
#: transaction by a rule nobody on this screen had been told about. The web mirrors it as
#: ``MAX_NAMED_DESIGNERS``.
MAX_SANCTION_DESIGNERS = MAX_DESIGN_WORKSHOP_VIEWERS


class SanctionOrderCoDesigner(APIModel):
    """One designer an order names besides the lead.

    A NAME AND AN ADDRESS, and deliberately not a ``userId``. The whole reason this feature exists
    is that the designer is usually NOT here yet — no account, no empanelment, no row to point an id
    at — and a body that could only name existing accounts would be a body that cannot express the
    ordinary case. Where the officer picked a real account the client sends that account's own name
    and address, and the service resolves it back to the same row through
    :func:`app.services.sanction_orders.resolve_named_designer`, which is the same resolution it
    performs for an address typed by hand. One path, not two.

    The bounds mirror :class:`SanctionOrderCreate`'s lead scalars exactly — 160 and ``EmailStr`` —
    because these are the same two facts about a different person, and a co-designer whose name may
    be longer than the lead's is a column that will one day be clipped by whichever door it came in
    through.
    """

    name: str = Field(min_length=1, max_length=160)
    email: EmailStr


class SanctionOrderCreate(APIModel):
    """The whole officer form: the three facts of the instrument, the team, and a note.

    ``APIModel`` is ``extra="forbid"``, so a client that adds a key nobody declared is a 422 naming
    it rather than a silently ignored field — which is what stops a future "and also set the state"
    from being added on one client and dropped on the server.

    THAT IS ALSO WHAT KEEPS THE IMPORT'S PROVENANCE OFF THIS DOOR. ``SanctionOrder.sourceFilename``
    and ``sheetRow`` are written by the bulk importer through
    :class:`SanctionOrderImportRow`, which subclasses this; an officer's form cannot claim an order
    came off a sheet, because the two columns are not fields here and ``extra="forbid"`` refuses
    them by name.
    """

    sanctionOrderNo: str = Field(min_length=1, max_length=120)
    sanctionOrderDate: date
    #: NEVER ``float``; see the module docstring. ``max_digits``/``decimal_places`` reject a
    #: 15-digit or a 3-paise value at the door with the field named, instead of at the table's
    #: ``CHECK`` with a 500 — and ``gt=0`` is the Pydantic half of that same CHECK, which exists in
    #: both places deliberately: a validator protects one door, a constraint protects the table.
    sanctionAmount: Decimal = Field(gt=0, max_digits=14, decimal_places=2)
    designerName: str = Field(min_length=1, max_length=160)
    #: ``EmailStr``, like ``UserCreate.email``. The service canonicalises it through
    #: ``designers.canonical_email`` before it reaches either roster and writes the LITERAL
    #: lower-cased form onto ``User.email``; this validator only asks whether it is an address at
    #: all. The two spellings differ for a dotted Gmail and that is deliberate — see the service.
    designerEmail: EmailStr
    notes: str | None = Field(default=None, max_length=2000)
    #: ── THE REST OF THE TEAM, POSITIONS 1..N ────────────────────────────────────────────────────
    #:
    #: Empty by default, and empty is not a degraded body: one designer is the ordinary sanction
    #: order and the list exists for the ones that are not. The two scalars above are position 0 and
    #: must NOT be repeated here — the service collapses a repeat rather than refusing it (the rule
    #: ``namedDesignerTeam`` already applies on the two other create doors), but a client that sends
    #: the lead twice is a client whose author believed something untrue about this field.
    #:
    #: ``max_length`` is one SHORT of the team cap, because the lead is the other one. Bounded at
    #: the schema rather than in the service for the reason every list bound in this product is:
    #: without it one request decides how much work the server does, and this one mints accounts.
    coDesigners: list[SanctionOrderCoDesigner] = Field(
        default_factory=list, max_length=MAX_SANCTION_DESIGNERS - 1
    )


class SanctionOrderUpdate(APIModel):
    """THE THREE FACTS OF THE ORDER ITSELF ARE NOT HERE, AND THAT IS THE POINT.

    A sanction order is an instrument: its number, date and amount are what the ministry issued, and
    a portal that lets an officer edit them is a portal whose register cannot be audited. A number
    typed wrong is corrected the way a wrongly-typed allow-list row is — an admin acts, with a record
    of it. ``notes`` is the one admin-typed column and it is the only thing this body moves.

    ``notes`` is also, today, the only place a WITHDRAWN sanction order can be recorded, because
    there is no ``cancelledAt`` and no delete. That is a known gap and an open question for the
    owner rather than an oversight; see the header of ``app/api/routes/sanction_orders.py``.
    """

    notes: str | None = Field(default=None, max_length=2000)


# --------------------------------------------------------------------------------------
# The bulk import
# --------------------------------------------------------------------------------------


class SanctionOrderImportRow(SanctionOrderCreate):
    """One order the importer is about to record, with the line of the sheet it came off.

    NOT A ROUTE BODY AND NEVER MOUNTED ON ONE. It subclasses the officer's form so that every bound
    the form enforces — the 120-character number, ``gt=0`` and ``decimal_places=2`` on the money,
    ``EmailStr``, the 160-character names, the team cap — is enforced on an imported order too,
    by the same declaration rather than by a second copy of it in the parser. An import that could
    write a value the form refuses would be a second rule for one column.

    ``sourceFilename``/``sheetRow`` are the two things a hand-typed order cannot have. They are
    diagnostics and not keys: nothing is ever looked up by them, and their whole job is to let an
    officer holding the workbook find the line an order came off. An authenticated officer posting
    the confirm body by hand could of course put anything in them; that is worth knowing and is not
    worth a guard, because the officer is the person the column records.
    """

    sourceFilename: str | None = Field(default=None, max_length=260)
    #: The 1-BASED EXCEL GUTTER ROW — the number in the grey margin, which is what an administrator
    #: presses Ctrl+G and types. Never a 0-based index into the parsed list: those differ by the
    #: header row plus every blank spacer above it, and a report that named the wrong line is worse
    #: than one that named none.
    sheetRow: int | None = Field(default=None, ge=1)


class SanctionImportDesignerIn(APIModel):
    """One designer on one row of the confirmation, as the officer resolved them."""

    name: str = Field(min_length=1, max_length=160)
    email: EmailStr


class SanctionImportRowIn(APIModel):
    """One sheet row, with what the officer decided to do about it.

    ``action`` IS AN EXPLICIT WORD AND NOT AN OMISSION. A confirm body that expressed "skip" by
    leaving the row out would make "the officer chose to skip row 14" indistinguishable from "row 14
    was lost between the two requests", and the report has to be able to say which — ``skipped`` is
    one of the four counts that must visibly add up.

    **WHAT AN OFFICER MAY EDIT HERE IS THE PAIRING AND NOTHING ELSE.** The number, the date and the
    amount travel back unchanged because they are what the ministry issued;
    :class:`SanctionOrderUpdate` already refuses to let an officer edit those on a row that exists,
    and an importer that let them be edited on the way IN would be a second rule for one column. The
    server re-validates them through :class:`SanctionOrderImportRow` regardless, so a client that
    altered them buys nothing the form would not also have allowed.
    """

    sheetRow: int = Field(ge=1)
    action: Literal["record", "skip"]
    sanctionOrderNo: str = Field(min_length=1, max_length=120)
    sanctionOrderDate: date
    sanctionAmount: Decimal = Field(gt=0, max_digits=14, decimal_places=2)
    notes: str | None = Field(default=None, max_length=2000)
    #: THE LEAD IS ELEMENT 0 HERE, which is the one place in this feature it is positional rather
    #: than scalar — because this list is the officer's answer to "who does this row name, in what
    #: order", and splitting it into a lead pair plus a remainder would make the confirmation screen
    #: draw two controls for one question. The service splits it back apart on the way in.
    designers: list[SanctionImportDesignerIn] = Field(
        default_factory=list, max_length=MAX_SANCTION_DESIGNERS
    )


class SanctionImportConfirm(APIModel):
    """The second POST, which carries no file.

    ══ STATELESS, AND THAT IS A RECORDED DECISION RATHER THAN A SHORTCUT ═══════════════════════════

    The alternative was a short-TTL token minted by the upload and quoted here, binding the officer's
    decisions to one parse. It was rejected because **this repository holds no server-side
    inter-request state anywhere**: the only short-TTL-token precedent is ``PasswordResetToken``,
    which stores a SHA-256 DIGEST rather than the token and is therefore a poor template for
    "remember this parse for ten minutes". A token would also have needed a table, a sweeper and a
    failure mode ("your upload expired, please do it again") on top of a feature whose whole point is
    to save an officer from re-doing work.

    So the client sends the resolved rows back and the server re-runs every check that does not need
    the file. WHAT THAT COSTS, SAID PLAINLY: a stale browser tab can post a resolution built against
    a register that has moved on. It is not silent — the duplicate-number check, the barred-address
    check and the empanelment check all run again per row, so a row that has gone stale is REFUSED
    and named in the report rather than written wrongly. What is genuinely lost is the guarantee that
    the sheet the officer looked at is the sheet these numbers came from; the filename and the row
    numbers travel with the body so the report can still say which line each order was.

    ``rowsRead`` IS ECHOED BACK BY THE CLIENT AND IS THE SHEET'S COUNT, NOT ``len(rows)``. Both are
    reported, and the report's arithmetic is ``rowsRead = recorded + skipped + refused``. An echo an
    officer could alter is not a control; it is what lets the panel say "the sheet had 200 rows and
    this request accounted for 198", which is the one way a client bug that dropped two rows between
    the two POSTs can be seen at all.
    """

    sheet: str | None = Field(default=None, max_length=120)
    sourceFilename: str | None = Field(default=None, max_length=260)
    rowsRead: int = Field(default=0, ge=0)
    #: ── THE REFUSALS THAT CANNOT TRAVEL, AND WHY THIS COUNT HAS TO EXIST ────────────────────────
    #:
    #: Some rows are refused at PREVIEW time for reasons that make them inexpressible in
    #: :class:`SanctionImportRowIn` at all: ``sanctionOrderDate`` is a required ``date`` and
    #: ``sanctionAmount`` is ``gt=0``, so a row whose date cell could not be read has no legal shape
    #: in this body. It is not that the client chose not to send them — it CANNOT.
    #:
    #: Without this number the report's arithmetic silently stops summing: ``rowsRead = recorded +
    #: skipped + refused`` would be short by exactly the rows nobody could describe, on a panel whose
    #: whole value is that an officer can check it. So the client says how many it is not sending and
    #: the server adds them to its own refusals.
    #:
    #: IT IS A CLIENT-SUPPLIED NUMBER AND THAT IS ACCEPTED RATHER THAN OVERLOOKED — the same as
    #: ``rowsRead`` above, for the same reason: it is an ECHO, not a control. It cannot cause a row to
    #: be recorded, cannot cause one to be skipped, and cannot reach a database column. The worst a
    #: wrong value does is make the panel's own sum visibly disagree, which is the one failure this
    #: field exists to make visible in the first place.
    refusedBeforeConfirm: int = Field(default=0, ge=0)
    rows: list[SanctionImportRowIn] = Field(
        default_factory=list, max_length=MAX_SANCTION_ROWS
    )
