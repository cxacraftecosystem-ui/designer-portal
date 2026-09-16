"""Request bodies for ``/api/workshop-types`` — the administrator-managed "Type of workshop" list.

THREE BODIES, AND THE SHAPE OF THEM IS THE FEATURE. ``WorkshopTypeOptionCreate`` accepts a ``key``
because minting a type is the one moment a key is decided; ``WorkshopTypeOptionUpdate`` does NOT,
because editing a label is the common case and it must never be able to touch the token that other
tables already hold. ``WorkshopTypeOptionReorder`` moves rows and touches nothing else.

── WHY THE UPDATE BODY CANNOT REACH ``key``, WRITTEN OUT ONCE HERE SO NOBODY ADDS IT BACK ─────────

A type's ``key`` is not an identifier of this table's own row — that is ``id``. It is a token WRITTEN
INTO OTHER TABLES AND OTHER DOCUMENTS, and this table is the only place the token has a label:

  * ``DesignWorkshop.workshopKind`` holds it, promoted out of stage 1 of a 22-stage questionnaire.
    Counted 2026-09-16: 24 of 13,871 rows hold ``DESIGN_PROTOTYPE_DEVELOPMENT`` and the rest are
    NULL ("not yet stated"). Re-count with::

        SELECT "workshopKind", count(*) FROM "DesignWorkshop" GROUP BY 1;

  * The annual plan directory's own rows hold it too, parsed out of the ministry's own annual sheet
    — 50 of 3,528 on the same date, by the same query against that table. NAMED HERE BY DESCRIPTION
    AND NOT BY ITS TABLE, deliberately: ``tests/test_annual_plan_is_not_a_workshop.py`` censuses
    every module under ``app/`` that spells that table's name and holds the set to an allow-list, so
    that a module cannot come to depend on the plan without somebody saying so. This file only
    DESCRIBES where the token travels; it issues no query, imports nothing and joins to nothing, and
    a prose mention is not the kind of dependence that guard exists to catch. The canonical statement
    of the relationship is in ``prisma/schema.prisma`` beside the column itself, which that census
    never opens. (``api/routes/workshop_types.py`` IS on the allow-list, and belongs there — it
    genuinely counts those rows before letting a type be deleted.)
  * The stage document itself holds it, inside ``DwStageEntry.data``, where nothing in this codebase
    rewrites a stored answer after the fact.
  * Every dataset export that has ever been taken holds it.

None of those is reachable from this router and none of them SHOULD be: a rename that had to rewrite
four places to stay true is a rename that will one day rewrite three of them. So the key is decided
once, at creation, and after that only the label moves. An administrator who genuinely needs a
different token creates a new type and deactivates the old one, which leaves both tokens resolvable —
which is exactly what the workshops already filed under the old one need.

THE PYDANTIC MODEL IS THE ENFORCEMENT AND NOT A CONVENIENCE. ``APIModel`` is ``extra="forbid"``, so a
client that sends ``{"label": "…", "key": "…"}`` to the PATCH is answered 422 naming the field rather
than having the key silently dropped — the difference between an administrator learning the rule and
an administrator believing a rename worked.

── ``sortOrder`` IS ON THE UPDATE BODY *AND* HAS ITS OWN ROUTE, WHICH IS NOT DUPLICATION ──────────

``WorkshopTypeOptionUpdate.sortOrder`` sets ONE row's position, which is what a form with a number
box in it does. ``WorkshopTypeOptionReorder`` sets EVERY row's position in one request, which is what
a list with up and down arrows does — and it has to be one request, because a reorder expressed as
six PATCHes is six chances to be interrupted halfway and leave the list in an order nobody chose.
They are two different actions with the same units, exactly as ``DELETE`` and ``PATCH {isActive}``
below are two different actions with the same effect on a dropdown.
"""

from __future__ import annotations

import re
from typing import Annotated

from pydantic import Field, field_validator

from app.schemas.common import APIModel

#: What a key may be made of, and it is the shape of the tokens already in the database:
#: ``DESIGN_PROTOTYPE_DEVELOPMENT``, ``SKILL_UPGRADATION``, ``OTHER``. Upper case, digits and
#: underscores, starting with a letter.
#:
#: NARROW ON PURPOSE, AND THE NARROWNESS IS THE POINT rather than tidiness. This token is compared
#: for equality against values stored in three other tables and in every export ever taken, so the
#: ways it can differ invisibly are what matters: a trailing space, a lower-case letter, a hyphen
#: where an underscore was meant. ``coerce_value`` in the registry compares ``WORKSHOP_KIND`` tokens
#: the same way — exact string equality, no folding — so a key that differs by a space is a key that
#: matches nothing, forever, with nothing on screen to say why.
_KEY_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]*$")

#: The refusal, worded for the administrator typing into the box rather than for the developer who
#: wrote the regex. It says what is allowed AND gives an example, because "must match
#: ^[A-Z][A-Z0-9_]*$" is a sentence that sends a reader to ask somebody else.
KEY_SHAPE_MESSAGE = (
    "A type's key is a token other tables already store, so it may only use capital letters, "
    "digits and underscores, and must start with a letter — for example "
    "DESIGN_PROTOTYPE_DEVELOPMENT. It is permanent once created; the label above it is the part "
    "you can change later."
)


class WorkshopTypeOptionCreate(APIModel):
    """Mint a type. The ONE request that decides a key.

    ``routesToDesignWorkshop`` DEFAULTS TO FALSE, AND THAT IS THE SAFE DIRECTION rather than an
    arbitrary default. TRUE means "a workshop chosen under this type is written to
    ``Record.designWorkshopId``", i.e. it points the picker at the 22-stage design workshop table.
    An administrator who adds "Cluster Development (Phase II)" without understanding the flag gets a
    type backed by the ordinary ``Workshop`` table, where a mis-filed record is visible on the
    workshop list and can be re-pointed; the other default would file it against a design workshop it
    has no business being attached to.
    """

    key: Annotated[str, Field(min_length=1, max_length=64)]
    label: Annotated[str, Field(min_length=1, max_length=120)]
    sortOrder: int = Field(default=0, ge=0, le=100000)
    isActive: bool = True
    routesToDesignWorkshop: bool = False

    @field_validator("key")
    @classmethod
    def _key_shape(cls, value: str) -> str:
        # Stripped BEFORE the pattern is tested, so "  OTHER  " pasted out of a spreadsheet is
        # accepted as OTHER rather than refused with a message about capital letters — the refusal
        # would be true and would name the wrong problem.
        token = value.strip()
        if not _KEY_PATTERN.match(token):
            raise ValueError(KEY_SHAPE_MESSAGE)
        return token

    @field_validator("label")
    @classmethod
    def _label_is_not_blank(cls, value: str) -> str:
        # ``min_length=1`` alone passes a single space, and a type whose label is a space is a row
        # the dropdown draws as an empty line — pickable, invisible, and impossible to describe to
        # whoever has to fix it.
        label = value.strip()
        if not label:
            raise ValueError(
                "A type needs a label — it is what an administrator reads in the list."
            )
        return label


class WorkshopTypeOptionUpdate(APIModel):
    """Correct a type. **Every field here is optional and ``key`` is deliberately not among them.**

    Applied with ``exclude_unset=True``, so an absent key keeps the stored value and only the fields
    a client actually sent are written. That is what makes "edit the label" a one-field request that
    cannot disturb the ordering or the routing flag by echoing them back stale.

    ``isActive`` IS HERE, AND IT IS THE REMEDY THE DELETE REFUSAL NAMES. Setting it false retires a
    type from every picker while leaving every workshop filed under it exactly where it is, which is
    the whole reason a type in use must not be deleted. See ``routes/workshop_types.py``.
    """

    label: Annotated[str, Field(min_length=1, max_length=120)] | None = None
    sortOrder: Annotated[int, Field(ge=0, le=100000)] | None = None
    isActive: bool | None = None
    routesToDesignWorkshop: bool | None = None

    @field_validator("label")
    @classmethod
    def _label_is_not_blank(cls, value: str | None) -> str | None:
        if value is None:
            return None
        label = value.strip()
        if not label:
            raise ValueError(
                "A type needs a label — it is what an administrator reads in the list."
            )
        return label


class WorkshopTypeOptionReorder(APIModel):
    """The whole list's order, in one request.

    A LIST OF IDS AND NOT A LIST OF ``{id, sortOrder}`` PAIRS. The position IS the index in the
    array, so the client cannot send an order that contradicts itself (two rows at 30, a gap at 20)
    and the server does not have to decide what such a request meant. It is the same argument
    ``useDragReorder`` makes on the web client for committing an ARRANGEMENT rather than a pair of
    indices.

    PARTIAL LISTS ARE ACCEPTED, and that is deliberate rather than lax: the ids sent are renumbered
    from the top in the order given, and any row not named keeps the position it has. A client that
    sends every row gets a total reorder; a client that sends two gets a swap. What it must never do
    is *delete* by omission, and it cannot — nothing here writes anything but ``sortOrder``.
    """

    ids: Annotated[list[Annotated[str, Field(max_length=64)]], Field(min_length=1, max_length=200)]
