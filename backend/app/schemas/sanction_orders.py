"""The officer's sanction-order bodies — FIVE FIELDS AND A NOTE, and the shortness is the point.

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

from pydantic import EmailStr, Field

from app.schemas.common import APIModel


class SanctionOrderCreate(APIModel):
    """The whole officer form. FIVE FIELDS AND A NOTE.

    ``APIModel`` is ``extra="forbid"``, so a client that adds a sixth key is a 422 naming it rather
    than a silently ignored field — which is what stops a future "and also set the state" from being
    added on one client and dropped on the server.
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
