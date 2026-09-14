"""THE MINISTRY'S SANCTION REGISTER — five typed fields, seven written rows, one transaction.

══ WHAT THIS MODULE IS FOR ═════════════════════════════════════════════════════════════════════

A sanction order is the document that authorises a design & prototype workshop and names its
budget. A ministry officer types five facts — order number, order date, sanctioned amount, the
designer's name and the designer's Gmail address — and this module turns them into everything the
product needs for that designer to start work the same morning:

    1. an ``AccessRoster`` admission, so they may sign in at all
    2. a ``DesignerRoster`` empanelment, so the designer gate does not refuse them at the door
    3. a ``User`` row — **only when no account already answers to that mailbox**
    4. a ``DesignerProfile``, so workshop creation has a profile to read
    5. a ``DesignWorkshop``, created BY the officer and stamped with the registry version
    6. a ``DesignWorkshopViewer`` row, so the named designer can actually open it
    7. the ``SanctionOrder`` row itself, which is the register and the audit trail

and then, outside the transaction, a stage-1 prefill and a first-password link.

══ THE TRANSACTION, AND THE ONE THING ABOUT IT THAT IS EASY TO GET SILENTLY WRONG ══════════════

Rows 1 through 7 are ONE ``db.tx()``. ``db.tx()`` HANDS BACK A DIFFERENT CLIENT — a callee that
goes on writing through the module singleton ``db`` is outside the transaction it appears to be
inside, its writes commit independently, and a rollback leaves precisely the half-state the
transaction was opened to make impossible: an admitted, empanelled account with a workshop that no
longer exists and no sanction order to say why it was made. **Nothing about that failure is loud.**
``db.tx()`` still compiles, still runs, still commits, and the happy path passes every test. So the
five service functions this flow calls have each been given a ``client=`` parameter and each one is
threaded here:

    ``access_roster.admit``                 ``designers.ensure_empanelled``
    ``designers.name_on_the_allow_list``    ``designers.get_or_create_profile``
    ``design_workshops.attach_the_named_designer``

The third of those is the one that looks optional and is not. ``ensure_empanelled`` calls
``name_on_the_allow_list`` to copy the administrator's own spelling of the designer's name onto the
roster row. Read through the module singleton from inside this transaction it cannot see the
``AccessRoster`` row written three statements earlier, answers None, and every sanction-created
``DesignerRoster`` row lands with ``fullName = None``. No error, no rollback, no failing test —
until somebody opens /admin/designers and finds a column of bare email addresses.
``tests/test_sanction_orders.py::test_four_fields_produce_seven_rows`` asserts the name for exactly
that reason.

══ WHAT IS DELIBERATELY OUTSIDE THE TRANSACTION, AND WHY ═══════════════════════════════════════

``seed_designer_prefill`` is up to twenty-two registry validations and a dozen creates behind a
blanket ``except``; its own docstring says prefill never fails the create. Folding it in would hold
the transaction open across a dozen round trips AND put a never-fail block inside an all-or-nothing
one. ``credential_links.issue_link`` writes a ``PasswordResetToken`` and can raise
``IssueThrottled``: a throttle on the fifth account of the morning must not roll back a ministry
sanction order. Both run after the commit; both failing leaves a correct, complete workshop.

══ THE REFUSALS THAT ARE THE WHOLE POINT OF PHASE 0 ════════════════════════════════════════════

``access_roster.admit`` sets ``status: ACTIVE`` UNCONDITIONALLY. Without the barred-address check
below, recording a sanction order would silently overturn an administrator's REJECTED or SUSPENDED
decision — the one thing ``auth.assert_access_admits`` explicitly refuses to do. And
``ensure_empanelled`` never revives a suspended row, so without the ended-empanelment check the
officer would get a 201 and the designer would get a workshop they are refused at the door, reading
*"Your designer access has been suspended"* with no row on any screen to explain it — which is the
incident that whole function exists to prevent.

TWO MORE LANDED ON 2026-09-14, both of them defects this module shipped with, and both of them
silent in the way everything else here is silent:

* ``_refuse_if_the_named_account_cannot_run_the_workshop``. The role decision inside the transaction
  is a RANK test and design-workshop capability is SET membership, so the five roles above
  DESIGNER's rank and outside ``DESIGN_WORKSHOP_ROLES`` — INSPECTOR, PROFESSOR and the three
  directorate tiers — were left as they were and then handed a viewer row that
  ``load_workshop_or_404`` refuses to honour. 201 for the officer, permanent 404 for the designer,
  no screen in the product able to undo it.
* ``_refuse_if_the_officer_named_themselves``. The gate on this route is a rank floor at
  ASSISTANT_DIRECTOR and the 201 hands the caller a working first-password link for the account it
  just minted at role DESIGNER — so an officer naming a second mailbox they control could sign in as
  their own designer and stand on both sides of the instrument.

  **THIS REFUSAL WAS HALF THE LOCK UNTIL 2026-09-14 AND IS NOW THE ONLY ONE ON THIS SIDE.** It used
  to be backed by a second, structural fact — every tier that clears the floor was outside
  ``DESIGN_WORKSHOP_ROLES``, so an officer could not author a stage even if they got in. The three
  directorate tiers joined that set on the owner's ruling, so that sentence is no longer true and
  the officer does not need a puppet: the workshop this transaction opens carries
  ``createdById = officer.id``, which is a way IN through ``load_workshop_or_404`` all by itself.
  What closes it from the other end is
  ``services/design_workshops._refuse_if_the_officer_is_authoring_what_they_sanctioned``, and the two
  are now a pair. Read both before loosening either.

EVERY ONE OF THESE IS A READ (or, for the self-naming one, no read at all), TAKEN BEFORE THE
TRANSACTION OPENS. Do not "optimise" them into it: from in there they would read the rows this
transaction is writing and answer about its own work.

══ THE MONEY ══════════════════════════════════════════════════════════════════════════════════

``Decimal`` in, ``NUMERIC(14,2)`` in the table, a decimal STRING on the wire. Never a float at any
layer. ``jsonable_encoder`` converts ``Decimal`` to ``float`` — measured — which is how
``ProductDocumentation.sellingPrice`` came to be typed ``string | number | null`` on the web and
``Double?`` on Android. :func:`sanction_payload` therefore builds its dict by hand with ``str(...)``
and this module never calls ``jsonable_encoder`` on a sanction row.
"""

from __future__ import annotations

import logging
import re
import secrets
from datetime import UTC, date, datetime, timedelta
from typing import Any

from fastapi import HTTPException, status
from prisma.errors import UniqueViolationError

from app.core.db import db
from app.core.deps import (
    ROLE_RANK,
    can_run_design_workshops,
    has_rank,
    invalidate_cached_user,
    role_rank,
    role_value,
)
from app.core.security import hash_password
from app.services import access_roster, credential_links, designers
from app.services.design_workshops import (
    attach_the_named_designer,
    entry_rows,
    seed_designer_prefill,
)
from app.services.records import decimal_to_string
from app.services.stage_schema import registry_version, stage, stage_completeness

log: logging.Logger = logging.getLogger(__name__)

#: The stage and entity the report's COPY of the sanction number lives on. Named once here so the
#: readiness score, the drift check and the prefill cannot drift apart into three spellings.
STAGE_ONE_KEY = "WORKSHOP_SETUP"
STAGE_ONE_ENTITY = "workshopSetup"


# --------------------------------------------------------------------------------------
# The gate
# --------------------------------------------------------------------------------------


def can_record_sanction_orders(user: Any) -> bool:
    """Record a ministry sanction order, and thereby open the workshop it authorises.

    ── A RANK FLOOR AT ASSISTANT_DIRECTOR (42), AND NOT A SET ────────────────────────────────────

    Every design-workshop gate in ``app/core/deps.py`` is SET MEMBERSHIP (see
    ``DESIGN_WORKSHOP_ROLES``) and this one deliberately is not, because the thing it gates is the
    one genuinely LADDERED act in the product. The three ministry tiers — ASSISTANT_DIRECTOR 42,
    REGIONAL_DIRECTOR 45, MINISTRY_ADMIN 48 — are a chain of seniority within one office, and an
    order an assistant director may record is obviously also recordable by the regional director
    above them. Spelling that as a set would mean re-listing five tokens on every future insert, in
    every mirror, which is exactly the drift ``tests/test_role_ladder_parity.py`` exists to catch.

    WHO IS REFUSED, AND THIS IS THE PART TO READ BEFORE WIDENING IT. PROFESSOR (40), INSPECTOR (37)
    and DESIGNER (35) are all below the floor. A DESIGNER being refused is the point: the whole
    requirement is that the person who does the work does not authorise their own budget.

    ── THIS IS DELIBERATELY NOT ``can_create_design_workshops``, AND THE TWO MUST STAY APART ──────

    ``DESIGN_WORKSHOP_CREATOR_ROLES`` is ``{ADMIN, MASTER_ADMIN}`` and stays that way. It gates the
    "New workshop" BUTTON — an administrative act with no instrument behind it, whose justification
    is that "the admin holding the sanction order is the person who knows a workshop exists". This
    predicate gates recording the INSTRUMENT ITSELF, which carries its own audit trail: a unique
    number, a date, an amount, a named designer and a named officer, in a table nothing inside a
    workshop can write. A REGIONAL_DIRECTOR may therefore initiate a SANCTIONED project and may not
    open an ad-hoc workshop, which is not an inconsistency — it is the difference between spending
    authorised money and creating a container.

    Widening ``DESIGN_WORKSHOP_CREATOR_ROLES`` to the ministry tiers instead was considered and
    rejected: it would hand three new tiers the untracked create, and ``test_design_workshop_gate``
    reads that route's SOURCE precisely so the gate on it cannot quietly become something else.

    ── WHERE THIS LIVES, AND WHY IT IS NOT IN ``deps.py`` ─────────────────────────────────────────

    Every other ``can_*`` predicate in this product lives in ``app/core/deps.py`` and this one would
    belong there too. It is here because ``deps.py`` was owned by another change in flight when this
    landed, and a predicate in the wrong module is a smaller defect than two agents overwriting one
    file. The route module re-exports the FastAPI dependency; ``docs/PERMISSIONS.md`` §5 names this
    function by its real home. **Moving it into ``deps.py`` is a welcome follow-up** — the tests
    import it from here, so the move is one import line plus this paragraph.

    ── WHAT PASSING THIS GATE ACTUALLY BUYS, AND THE ONE THING THAT BOUNDS IT ─────────────────────

    Read this beside the ``ASSISTANT_DIRECTOR`` block in ``app/core/deps.py``, which is where the
    ladder's capabilities are argued, and which this route is a deliberate, named exception to. Three
    of the acts behind this predicate are ones that block lists as things a directorate tier does NOT
    get: an account is minted (``POST /api/users`` is ``require_admin``), an allow-list admission is
    written (``/api/access`` is ``require_access_manager`` = ``is_admin``), and a
    ``DesignWorkshopViewer`` row is granted (``/design-workshops/{id}/viewers`` is ``require_admin``).
    That is the OWNER'S DECISION and it is what the feature is for — a designer who can start the
    same morning, without an officer having to find an admin first — but it is a real widening and
    the ladder's comment has been corrected to say so rather than left claiming otherwise.

    What bounds it is :func:`_refuse_if_the_officer_named_themselves`, plus — since 2026-09-14 — its
    counterpart in ``services/design_workshops``. The 201 here hands the caller a working 72-hour
    first-password link for an account it just created at role DESIGNER. Without that refusal an officer could name a mailbox
    they control, redeem the link, and hold both ends of the instrument — authoring the work as a
    designer and approving it as themselves. The account minted by this flow is therefore always
    SOMEBODY ELSE'S, it is always marked ``SanctionOrder.accountCreated = true``, and the register row
    names the officer who created it in a table nothing inside a workshop can write.

    ``frontend/lib/permissions.ts`` carries the web twin and must keep carrying the same threshold;
    ``tests/test_sanction_order_gate.py`` reads that file as text and fails if it does not.
    """
    return has_rank(user, "ASSISTANT_DIRECTOR")


#: The refusal, in ONE place because it is said on three surfaces — this feature's 403, the route
#: guard's lock panel and the nav's absent entry — and a refusal naming a different next move
#: depending on where you met it is not a rule, it is three rumours. Its twins are the
#: ``/sanction-orders`` row in ``frontend/lib/permissions.ts::ROUTE_GUARDS`` and
#: ``SANCTION_ORDER_REFUSAL`` in ``frontend/lib/sanctionOrders.ts``;
#: ``tests/test_sanction_order_gate.py::test_the_refusal_sentence_is_identical_on_every_surface``
#: reads both files off disk and pins all three byte-for-byte, because the reword-one-forget-the-
#: others failure is the one this repository has shipped most often.
SANCTION_ORDER_REFUSAL = (
    "Recording a sanction order is a ministry officer's act — Assistant Director and above. It "
    "opens a workshop, creates the designer's account and issues their sign-in link, so it is not "
    "something a designer or a professor can do for themselves. Ask the officer who holds the "
    "order to record it; the workshop will appear in your list as soon as they do."
)


# --------------------------------------------------------------------------------------
# The number, and the two spellings of it
# --------------------------------------------------------------------------------------

_NON_ALNUM = re.compile(r"[^A-Za-z0-9]+")


def normalise_sanction_order_no(value: Any) -> str:
    """The DUPLICATE KEY: upper-cased, with every non-alphanumeric character removed.

    ``SO/2026/42``, ``SO-2026-42``, ``so 2026 42`` and ``SO 2026/42`` are four house styles for one
    ministry instrument and Postgres calls them four values. ``sanctionOrderNo`` stores what the
    officer typed because that is what an auditor will search for; THIS is the column the unique
    index that actually bites is on.

    **CHARACTER-FOR-CHARACTER ``identity.normalise_empanelment_no``, AND A SEPARATE FUNCTION ON
    PURPOSE.** That is where this rule was first needed and argued, for empanelment numbers, and
    copying rather than importing it is not an oversight: the two vocabularies must be free to
    diverge — a ministry could start issuing sanction numbers with a meaningful suffix tomorrow —
    and a shared function would make such a divergence an accident that silently re-keys the other
    table. ``tests/test_sanction_orders.py::test_the_sanction_key_normalisation_matches_the_
    empanelment_one`` pins them equal over a table of inputs *today*, so a divergence has to be
    typed on purpose, with the test edited beside it.

    **RETURNS ``""`` FOR A BLANK OR PUNCTUATION-ONLY VALUE, WHERE THE EMPANELMENT TWIN RETURNS
    ``None``.** The difference is deliberate and is the one place the two are not identical: this
    column is NOT NULL, the schema refuses a blank number at the door (``min_length=1``), and a
    ``str | None`` return here would push a ``None`` check into every call site for a case the
    validator has already made unreachable. The parity test therefore compares
    ``normalise_sanction_order_no(x)`` with ``normalise_empanelment_no(x) or ""``.
    """
    return _NON_ALNUM.sub("", str(value or "")).upper()


def tidy_sanction_order_no(value: Any) -> str:
    """What gets STORED: the officer's own spelling, trimmed, internal whitespace runs collapsed.

    Nothing else is touched — not the case, not the separators, not a leading zero. The ministry's
    spelling is the one an auditor holding the paper will type into the search box.
    """
    return " ".join(str(value or "").split())


# --------------------------------------------------------------------------------------
# The notes and the refusals
# --------------------------------------------------------------------------------------

#: The note written onto BOTH roster rows the sanction admits, and the one machine-written note this
#: feature produces. ``designers.DERIVED_EMPANELMENT_NOTE`` is the precedent: a machine may write a
#: note when the note says what the machine did. ``actor_id`` is the OFFICER — an administrator
#: really acted, they just acted through a different door — which is what makes ``addedById`` on
#: /admin/designers name a person rather than nobody.
SANCTION_ADMISSION_NOTE = "Admitted by sanction order {no}, recorded by {officer}."

#: Two refusals about people an administrator has already shown the door, worded per cause with a
#: different next move each, and kept here rather than at the route because both are FACTS ABOUT THE
#: ADDRESS rather than facts about the request.
SANCTION_ADDRESS_BARRED = (
    "This address has been {state} on the platform allow-list. A sanction order cannot let somebody "
    "back in — ask an admin to review the allow-list entry first, then record the order."
)
SANCTION_EMPANELMENT_ENDED = (
    "This designer's empanelment has been ended. A sanction order does not restore it — ask an "
    "admin to restore the roster entry first, then record the order."
)

#: THE THIRD FACT ABOUT THE ADDRESS, and the one that had no refusal at all until 2026-09-14.
#:
#: An account this flow will NOT lift — anything already at DESIGNER's rank or above — keeps the role
#: it has, and :data:`app.core.deps.DESIGN_WORKSHOP_ROLES` is a SET rather than a rank floor, so
#: INSPECTOR (37), PROFESSOR (40) and all three directorate tiers (42/45/48) sit ABOVE the floor and
#: OUTSIDE the set. Naming one of them wrote the viewer row anyway and answered 201; see
#: :func:`_refuse_if_the_named_account_cannot_run_the_workshop` for what that cost.
#:
#: Worded for the OFFICER, who cannot fix it themselves: the next move is an admin on the users
#: screen, or a different designer. It deliberately names the role the account actually holds, because
#: "this person cannot run a workshop" with no reason reads as a bug in the portal rather than as a
#: fact about the account.
SANCTION_DESIGNER_CANNOT_RUN_WORKSHOPS = (
    "{name} ({email}) already has an account on this platform as a {role}, and a {role} cannot run "
    "a design & prototype workshop. A sanction order does not change somebody's role, and a "
    "workshop opened for them would answer “not found” the first time they tried to open it — ask "
    "an admin to move the account to Designer on the users screen and then record the order, or "
    "record it naming the designer who will do the work."
)

#: AUTHOR AND APPROVER ARE NOT ONE PERSON, and this is the only place that rule is enforced rather
#: than merely stated. :data:`SANCTION_ORDER_REFUSAL` already says the act is "not something a
#: designer can do for themselves"; without this refusal an officer could do it for themselves the
#: long way round, by naming a second mailbox they control and redeeming the invite link the 201
#: hands straight back to them. See :func:`_refuse_if_the_officer_named_themselves`.
SANCTION_SELF_NAMED = (
    "A sanction order cannot name the officer recording it as its designer. The instrument "
    "authorises somebody else's work and its whole value as a record is that the person who "
    "approves the money is not the person who spends it. Record the order naming the designer who "
    "will do the work; if you are that designer, ask a colleague at Assistant Director or above to "
    "record it."
)

#: The duplicate. Worded so the officer can tell WHICH of the two situations they are in, because
#: the next move differs: "I already did this" ends here, and "the ministry issued two orders with
#: one number" needs the printed suffix typing in.
SANCTION_DUPLICATE_TEMPLATE = (
    "Sanction order {no} is already recorded. It opened “{title}” for {designer} on {date}. If the "
    "ministry has issued a second order with this number, record it under the number as printed "
    "including its suffix; if this is the same order, open the workshop from the sanction list."
)

#: The Gmail-alias split, met head-on. ``routes/auth.py`` records this as a known, unfixed defect —
#: one person, two ``User`` rows, workshops split between them — and auto-provisioning would make it
#: MORE likely, not less. So this flow refuses rather than guessing: filing a ministry workshop
#: under whichever of two accounts an index happened to return first is not a decision a create
#: route gets to take on an institution's behalf.
SANCTION_AMBIGUOUS_ACCOUNTS = (
    "Two accounts already answer to this mailbox — {addresses}. Recording a sanction order would "
    "have to pick one of them to file the workshop under, and picking wrong puts a ministry "
    "workshop on an account the designer does not sign in to. Ask an admin to merge or correct the "
    "two accounts first, then record the order."
)

#: The 429, worded for an OFFICER rather than for an admin. The users screen's version talks about
#: password links; this one is about a person waiting to be let in.
SANCTION_THROTTLE_DETAIL = (
    "Several sign-in links have already been issued for this designer in the last hour. Wait an "
    "hour, or ask them to use the one they were sent — each one works until it is used."
)

#: Said on the 201 when the link could not be minted, so that a ``credentialLink: null`` is never
#: silently indistinguishable from "this designer already had an account".
SANCTION_LINK_PROBLEM_THROTTLED = (
    "The sanction order is recorded and the workshop is open, but the sign-in link could not be "
    "issued: " + SANCTION_THROTTLE_DETAIL + " Re-issue it from this list when the hour is up."
)


# --------------------------------------------------------------------------------------
# Phase 0 — the reads and the refusals, before anything is written
# --------------------------------------------------------------------------------------


async def _the_allow_list_row_or_refuse_if_barred(email: str) -> Any | None:
    """422 if an administrator has REJECTED or SUSPENDED this address on the allow-list.

    THIS READ IS THE ONLY THING BETWEEN A SANCTION ORDER AND RE-ADMITTING SOMEBODY AN ADMIN THREW
    OUT. ``access_roster.admit`` writes ``status: ACTIVE`` with no branch, so an officer recording
    an order for a barred mailbox would silently overturn a decision somebody took on purpose, on a
    screen that would then show them ACTIVE with a machine-written note. Deleting this check is a
    two-line change with no test failure anywhere near it, which is why
    ``test_a_rejected_address_is_refused_and_nothing_is_written`` asserts the row is STILL REJECTED
    afterwards rather than merely asserting the 422 — a reordering cannot fake that.

    **IT NOW HANDS THE ROW BACK, AND THE CALLER NEEDS IT FOR A SECOND, UNRELATED REASON.** ``admit``
    applies ``admit_role``, ``full_name``, ``note`` and the ``decided`` pair to an EXISTING row
    wholesale — only ``joinedAt`` is carved out — so a sanction order naming somebody who is already
    on the allow-list used to overwrite the tier an admin admitted them at, the name the admin typed,
    the admin's own note and the record of WHICH administrator decided and when. The caller decides
    what to send by looking at this row; see :func:`_an_admission_that_preserves_an_admins_decision`.
    Reading it here rather than a second time costs nothing and keeps the barred test and the
    preservation rule looking at the SAME row — ``access_row`` resolves a two-spelling collision to
    "the row that decides", which is the row ``admit`` will update, and two independent reads could
    in principle resolve to two different rows.

    The window between this read and ``admit``'s own read inside the transaction is a race an admin
    would have to win by editing the same allow-list row in the same second; losing it preserves or
    overwrites one note against a one-second-stale view of the row, which is strictly better than
    the unconditional overwrite it replaces.
    """
    row = await access_roster.access_row(email)
    state = access_roster.status_of(row)
    if state in access_roster.BARRED:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=SANCTION_ADDRESS_BARRED.format(state=state.lower()),
        )
    return row


def _an_admission_that_preserves_an_admins_decision(
    existing: Any | None, *, designer_name: str, note: str
) -> dict[str, Any]:
    """What to hand ``access_roster.admit`` so a sanction order cannot overwrite an admin's decision.

    ── THE DEFECT THIS EXISTS TO PREVENT ─────────────────────────────────────────────────────────

    ``admit`` builds one ``grant`` dict and its update arm copies it WHOLESALE onto an existing row
    (``services/access_roster.py:400-426``); ``joinedAt`` is the only column carved out. This flow
    used to pass ``admit_role="DESIGNER"``, ``full_name=<the name the officer typed into the sanction
    form>``, ``note=<the machine note>`` and ``decided=True`` unconditionally, so recording an order
    for somebody who was ALREADY on the allow-list rewrote four things an administrator owns:

      * ``admitRole`` — a professor admitted at PROFESSOR was reported on /admin/access as a
        DESIGNER, and ``GET /access/roster?roles=PROFESSOR`` (``routes/access.py:332`` builds
        ``{"admitRole": {"in": named}}``) stopped returning them. The column's own comment in
        ``prisma/schema.prisma`` says it LIFTS, NEVER LOWERS; this caller was the one write that
        lowered it;
      * ``fullName`` — the admin's own spelling of the name, replaced by the officer's typing;
      * ``notes`` — an admin's note about WHY this person was admitted, replaced outright, with
        nothing anywhere recording that it had been replaced, and gone from that screen's search
        (``routes/access.py:314-318`` matches on this column);
      * ``decidedAt``/``decidedById`` — the row stopped recording which administrator admitted this
        person and when, and started naming a ministry officer who never saw the request.

    ── WHY THIS SHAPE AND NOT THE OTHER TWO ──────────────────────────────────────────────────────

    The rule applied here is the one :func:`create_from_sanction` already applies to ``User.role``
    inside its transaction and calls out in capitals: LIFT, NEVER LOWER — a sanction order adds what
    is missing and overwrites nothing an administrator decided. Fixing it inside ``admit`` instead was
    rejected: that function is the one write every admitting path in the product shares, and an
    admin approving a pending request on /admin/access is MEANT to set all four of these. The
    asymmetry is not in ``admit``; it is in this caller, which is the only one that hands all four to
    a row that may already carry somebody else's decision. ``routes/access.py:555-559`` is the
    precedent for deciding it at the call site (``payload.role or access_roster.role_of(row)``).

    Passing ``None`` is how a field is left alone: ``admit`` writes ``admit_role`` only ``if
    admit_role``, and ``full_name``/``note`` only ``if ... is not None``. ``decided=False`` omits the
    ``decidedAt``/``decidedById`` pair from the update entirely, which preserves both.

    ── THE NOTE IS APPENDED RATHER THAN DROPPED OR REPLACED ──────────────────────────────────────

    Both facts are worth keeping: an admin's "Admitted 2025-04 on Prof. Rao's request" and "Admitted
    by sanction order SO/2026/42". Replacing loses the first, dropping loses the second, and the
    second is the only thing on the allow-list that explains why the row moved today. The
    containment test makes a retried create idempotent rather than appending the same sentence twice;
    sanction numbers are unique per order (``sanctionOrderKey``), so repeats over time are distinct
    sentences on an untruncated ``String?`` column and not unbounded growth from one order.
    """
    if existing is None:
        # A NEW ROW. Nothing to preserve, and every one of these four is what a new admission means:
        # the tier, the name, why it was written and who decided it.
        return {
            "admit_role": "DESIGNER",
            "full_name": designer_name,
            "note": note,
            "decided": True,
        }

    existing_notes = str(getattr(existing, "notes", None) or "").strip()
    if not existing_notes:
        merged: str | None = note
    elif note in existing_notes:
        merged = None
    else:
        merged = f"{existing_notes} {note}"

    # THE TIER: THE SAME COMPARISON THE ``User.role`` LIFT MAKES, ON THE OTHER ROW. ``admitRole``'s
    # own column comment says it "LIFTS, NEVER LOWERS", and this is the write that has to honour it:
    # a PROFESSOR row (40) keeps PROFESSOR, a RESEARCHER row (30) becomes DESIGNER, and a NULL row —
    # every grandfathered admission — gets one for the first time. Preserving a stored tier
    # unconditionally was the other candidate and it is worse: it would leave the allow-list calling
    # a researcher a researcher on the morning this transaction lifts their ACCOUNT to DESIGNER, so
    # /admin/access and /admin/users would disagree about the same person and
    # ``GET /access/roster?roles=DESIGNER`` would miss them.
    #
    # ``role_of`` AND NOT ``existing.admitRole``, AND THAT IS THE LOAD-BEARING LINE. Prisma hands
    # back an ENUM MEMBER on a live row and a bare string on anything hand-built in a test, and
    # ``role_rank`` takes the string branch only for a ``str``: given the enum member it falls to
    # ``role_value``, which looks for a ``.role`` attribute the enum does not have, reads ``"None"``
    # and answers rank 0. A PROFESSOR row would then LIFT to DESIGNER — the exact overwrite this
    # function exists to stop — and it would do so only in production, because every test object
    # would take the string branch and pass. ``role_of`` normalises both shapes first (and answers
    # None, which ranks 0 and correctly lifts, for a NULL tier).
    stored_tier = access_roster.role_of(existing)
    already_outranks_a_designer = role_rank(stored_tier or "") >= ROLE_RANK["DESIGNER"]
    already_has_a_name = bool(str(getattr(existing, "fullName", None) or "").strip())

    return {
        "admit_role": None if already_outranks_a_designer else "DESIGNER",
        "full_name": None if already_has_a_name else designer_name,
        "note": merged,
        # An UNDECIDED row (the grandfathered admissions, and anything the empanelment clause wrote
        # with ``decided=False``) has no decision to protect, and the officer really did act — which
        # is the argument ``SANCTION_ADMISSION_NOTE`` makes for ``actor_id`` being the officer. A row
        # that already carries a decision keeps it.
        "decided": getattr(existing, "decidedById", None) is None
        and getattr(existing, "decidedAt", None) is None,
    }


def _refuse_if_the_officer_named_themselves(officer: Any, keys: list[str]) -> None:
    """422 when the address on the order is one of the recording officer's own spellings.

    ── WHAT THIS CLOSES ──────────────────────────────────────────────────────────────────────────

    :func:`can_record_sanction_orders` is a rank floor at ASSISTANT_DIRECTOR (42). Until 2026-09-14
    every tier that clears it — 42, 45, 48 — was also OUTSIDE ``DESIGN_WORKSHOP_ROLES`` and therefore
    refused by ``routes/design_workshops._require_designer`` on all eighteen routes it guards; THAT IS
    NO LONGER SO, the three tiers are in the set, and the companion refusal named at the end of this
    docstring is what took over that half of the job. Without this check an officer could route around
    it in one request: name a second mailbox they control, and
    the 201 hands back ``credentialLink.link`` — a working 72-hour first-password URL — for a brand
    new account this transaction created at role DESIGNER, admitted ACTIVE on the allow-list and
    empanelled on the designer roster, with a ``DesignWorkshopViewer`` row on the workshop it just
    opened. ``POST /auth/set-password`` binds nothing to the named designer, so redeeming it is a
    sign-in. The officer then authors the fortnight of fieldwork as the puppet at rank 35 and reviews,
    rewrites and approves it as themselves at 42 — ``can_review_record`` is strictly-below and
    ``can_edit_others_record`` is the PROFESSOR floor AND that, so both hold. No admin is involved at
    any point, and ``POST /api/users`` (``require_admin``) is the door that decision is supposed to
    go through.

    ── WHY A REFUSAL AND NOT A NARROWER GATE ─────────────────────────────────────────────────────

    The three other candidates all cost more than they buy. Withholding ``credentialLink`` from the
    officer breaks the feature's stated transport (there is no mailer in this repository; the
    officer's clipboard IS the delivery, argued at :func:`_issue_first_credential`). Requiring an
    admin-decided allow-list row first makes the same-morning start impossible, which is the whole
    requirement. Narrowing the gate to ADMIN deletes the feature. What is actually wrong is one
    person standing on both sides of a financial instrument, and that is what this refuses.

    ── IT REFUSES EVERY TIER, INCLUDING ADMIN, AND THAT IS DELIBERATE ────────────────────────────

    An admin who is the practising designer of a cluster is a legitimate person — see
    ``design_workshops.assert_every_designer_may_be_named``, which admits ADMIN for exactly that
    reason — and they lose nothing here: ``DESIGN_WORKSHOP_CREATOR_ROLES`` is ``{ADMIN,
    MASTER_ADMIN}``, so an admin can already open a workshop for themselves through the ordinary
    "New workshop" button. What they may not do is issue themselves the money. That is the same
    sentence :data:`SANCTION_ORDER_REFUSAL` and :func:`can_record_sanction_orders` already make about
    a designer ("the person who does the work does not authorise their own budget"); until now it was
    only true of tiers that could not reach the route at all.

    ── BOTH SPELLINGS, BECAUSE ONE IS NOT ENOUGH ─────────────────────────────────────────────────

    Compared over ``email_match_keys`` — the literal lower-cased address AND the canonical mailbox —
    on both sides, so an officer signed in as ``a.k.officer@gmail.com`` cannot name
    ``akofficer+design@gmail.com`` and have it read as a different person. That is precisely the
    aliasing this module already canonicalises everywhere else; a bare string compare here would be a
    guard that any Gmail user can step over by typing one dot.

    AN OFFICER WHOSE ADDRESS CANNOT BE READ REFUSES NOBODY, and the empty test is spelled out rather
    than left to fall out of the intersection. It would fall out — an empty set intersects nothing —
    but "we could not read the recorder's mailbox, so we do not claim they named themselves" is a
    decision about a security check, and a decision that only holds because of how ``set`` behaves is
    one somebody re-derives wrongly the next time this is edited. The designer's own address has
    already been refused by the ``if not keys`` above this call if it is unusable, so a blank on the
    other side is not a way through.

    ── ITS COMPANION, AND WHY THIS ONE IS NO LONGER SUFFICIENT ALONE ────────────────────────────

    ``services/design_workshops._refuse_if_the_officer_is_authoring_what_they_sanctioned``. This
    function stops an officer NAMING a designer they control. It cannot stop an officer who names a
    real designer honestly and then authors the workshop themselves — and since the three directorate
    tiers joined ``DESIGN_WORKSHOP_ROLES`` on 2026-09-14, they can, because step 1.5 of the create
    stamps ``createdById = officer.id`` on the workshop and the creator arm of ``load_workshop_or_404``
    admits the creator with no role test at all. The companion refuses the WRITE on that one workshop
    and leaves the read alone. Neither function is the whole rule; together they are.
    """
    own = set(designers.email_match_keys(getattr(officer, "email", None)))
    if own and own.intersection(keys):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=SANCTION_SELF_NAMED,
        )


def _refuse_if_the_named_account_cannot_run_the_workshop(user: Any | None) -> None:
    """422 when the role this flow will LEAVE the account holding cannot open a design workshop.

    ── THE DEFECT, WHICH WAS SILENT AND HAD NO IN-PRODUCT REMEDY ─────────────────────────────────

    The role decision three statements into the transaction is a RANK test — ``if role_rank(user) <
    ROLE_RANK["DESIGNER"]`` — and design-workshop capability is SET MEMBERSHIP:
    ``DESIGN_WORKSHOP_ROLES`` is ``{DESIGNER, ADMIN, MASTER_ADMIN}``. Those two disagree over exactly
    five roles that sit ABOVE DESIGNER's rank and OUTSIDE the set — INSPECTOR (37), PROFESSOR (40),
    ASSISTANT_DIRECTOR (42), REGIONAL_DIRECTOR (45), MINISTRY_ADMIN (48). Naming one of them left the
    role alone (correctly — lift, never lower) and then wrote them a ``DesignWorkshopViewer`` row
    that cannot work: ``load_workshop_or_404``'s grant arm is ``can_run_design_workshops(user) and
    await has_viewer_grant(...)``, and ``and`` short-circuits to False before the row is read. The
    officer got a 201 and a screen saying the workshop was open with that person named on its stage-1
    cover; that person signed in and got 404 from ``GET /api/design-workshops/{id}``, with no screen
    anywhere saying why.

    AND IT COULD NOT BE UNDONE FROM INSIDE THE PRODUCT. Both sign-in doors are lift-only, so the
    ``admitRole="DESIGNER"`` row never demotes the professor and the 404 never heals; the viewers
    panel refuses them (``design_workshop_viewers._assert_every_id_may_be_granted``), ``reassign_
    designer`` refuses to re-name them, and there is no DELETE on /api/sanction-orders — so an
    undeletable financial register row went on naming a designer the access table refuses.

    ── WHY THIS AND NOT ``assert_every_designer_may_be_named`` ───────────────────────────────────

    That is the rule every OTHER writer of a viewer row calls, and calling it here is the obvious
    fix. It is wrong in this one place, and the reason is worth reading before "tidying" this into
    it. Its three arms ask about rows THIS TRANSACTION IS ABOUT TO WRITE:

      * the role arm would refuse every RESEARCHER and below — the accounts this flow exists to lift
        to DESIGNER, and the case ``test_an_existing_account_is_reused_and_never_duplicated`` pins;
      * the designer-roster arm would refuse an existing DESIGNER who has no empanelment yet, which
        is the row ``ensure_empanelled`` writes four statements later;
      * the allow-list arm is already asked, earlier and with a better sentence, by
        :func:`_the_allow_list_row_or_refuse_if_barred`.

    So the question here is not "may this account be granted a viewer row as it stands today" but
    "will the account this transaction LEAVES BEHIND be able to open what we are about to open for
    it" — and the honest way to ask that is to mirror the lift and then ask the real predicate.

    ── THE PREDICATE IS CALLED, NEVER RESTATED ───────────────────────────────────────────────────

    :func:`app.core.deps.can_run_design_workshops` is the function ``load_workshop_or_404`` itself
    uses. A copied ``role in {...}`` here would be a second spelling of the set that decides access,
    free to drift from the first — which is the ENTIRE defect this function exists to prevent, in a
    new place. The rank half is likewise ``role_rank``/``ROLE_RANK`` and not a literal 35, so if the
    lift below is ever re-spelled this refusal has to be re-spelled with it or it stops matching.
    """
    if user is None:
        # No account yet: the transaction mints one at role DESIGNER, which is in the set.
        return
    if role_rank(user) < ROLE_RANK["DESIGNER"]:
        # The lift below rewrites this account to DESIGNER, which is in the set. Kept as the same
        # comparison the lift uses — see the docstring's last paragraph.
        return
    if can_run_design_workshops(user):
        return
    raise HTTPException(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        detail=SANCTION_DESIGNER_CANNOT_RUN_WORKSHOPS.format(
            name=getattr(user, "name", None) or getattr(user, "email", None) or "That account",
            email=getattr(user, "email", None) or "",
            role=role_value(user),
        ),
    )


async def _refuse_if_empanelment_ended(keys: list[str]) -> None:
    """422 if a ``DesignerRoster`` row for this mailbox exists and is inactive.

    ``ensure_empanelled`` is CREATE-ONLY and never revives a suspended row — deliberately, because
    the roster suspends rather than deletes so that the record of the empanelment survives the
    ending of it. So without this read the officer gets a 201 and the designer gets a workshop they
    are refused at the door, reading "Your designer access has been suspended", with no row on any
    screen to explain it. The direct descendant of the assertion in ``test_platform_access_gate``.
    """
    rows = await db.designerroster.find_many(where={"email": {"in": keys}})
    if any(not bool(getattr(row, "isActive", False)) for row in rows):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=SANCTION_EMPANELMENT_ENDED,
        )


async def _existing_account(keys: list[str]) -> Any | None:
    """The one account that already answers to this mailbox, or None. 409 if there are two.

    **CASE-INSENSITIVE AND OVER BOTH SPELLINGS, WHICH IS WHERE THIS DIFFERS FROM THE SIGN-IN DOOR.**
    ``routes/auth.py`` looks a Google claim up with ``find_unique(where={"email": email})`` on the
    exact address Google sent, and its own comment records the consequence as a known, unfixed
    defect: one person, two ``User`` rows, their workshops split between the two. Auto-provisioning
    makes that MORE likely, not less, so this flow does not copy the shape. ``mode: "insensitive"``
    is the spelling ``/designers/directory`` already uses to join a roster row to an account.

    ``take=3`` rather than 2, so that "two" and "more than two" are distinguishable in the message
    without a second query.
    """
    rows = await db.user.find_many(
        where={"OR": [{"email": {"equals": key, "mode": "insensitive"}} for key in keys]},
        take=3,
    )
    if len(rows) > 1:
        addresses = ", ".join(sorted(str(getattr(row, "email", "") or "") for row in rows))
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=SANCTION_AMBIGUOUS_ACCOUNTS.format(addresses=addresses),
        )
    return rows[0] if rows else None


async def _account_the_register_already_knows(canonical: str, found: Any | None) -> Any | None:
    """The THIRD spelling ``email_match_keys`` cannot see, asked of the register's own column.

    **THIS LOOKS REDUNDANT BESIDE :func:`_existing_account` AND IS NOT.** ``email_match_keys`` knows
    exactly two spellings of a mailbox: the literal one that arrived and the canonical one. It
    cannot see a THIRD — an account created years ago under ``sandy.craft3+dch@gmail.com``, say,
    whose literal form is neither of today's two. ``SanctionOrder.designerEmail`` is written under
    :func:`designers.canonical_email` on every row, so the register itself can answer "which account
    has this MAILBOX been issued orders against before" when the ``User`` table cannot.

    Two outcomes, and neither of them guesses:

    * the register names an account and ``_existing_account`` found none — REUSE it, rather than
      minting a second ``User`` for a mailbox this product has already decided about;
    * the register names a DIFFERENT account from the one found above — refuse, with the same
      sentence the two-account case gets. A ministry workshop filed under whichever of two rows an
      index returned first is not a decision a create route takes on an institution's behalf.

    Deleting this as "already covered" is the tempting edit;
    ``test_a_third_spelling_in_the_register_is_reused_and_never_duplicated`` is what refuses it.
    """
    prior = await db.sanctionorder.find_first(
        where={"designerEmail": canonical}, include={"designerUser": True}
    )
    if prior is None:
        return found
    known = getattr(prior, "designerUser", None)
    if known is None:
        return found
    if found is None:
        return known
    if found.id != known.id:
        addresses = ", ".join(
            sorted({str(found.email or ""), str(getattr(known, "email", "") or "")})
        )
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=SANCTION_AMBIGUOUS_ACCOUNTS.format(addresses=addresses),
        )
    return found


async def _refuse_if_number_taken(key: str) -> None:
    """409 naming the existing order, its workshop, its designer and the day it was recorded.

    The FAST, FRIENDLY half of the duplicate rule. The CORRECT half is the unique index on
    ``sanctionOrderKey``, whose ``UniqueViolationError`` is caught inside the transaction and
    answered with this same sentence — so a losing race rolls the whole thing back and leaves no
    orphan account, no orphan workshop and no half-admission. Both are needed: this one exists so
    the ordinary case reads as a sentence rather than as a constraint name, and that one exists so
    the ordinary case is not the only case that is handled.
    """
    existing = await db.sanctionorder.find_unique(
        where={"sanctionOrderKey": key},
        include={"designWorkshop": True, "designerUser": True},
    )
    if existing is None:
        return
    raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=_duplicate_detail(existing))


def _duplicate_detail(existing: Any) -> str:
    workshop = getattr(existing, "designWorkshop", None)
    designer = getattr(existing, "designerUser", None)
    created = getattr(existing, "createdAt", None)
    return SANCTION_DUPLICATE_TEMPLATE.format(
        no=getattr(existing, "sanctionOrderNo", "") or "",
        title=(getattr(workshop, "title", None) or "a workshop"),
        designer=(
            getattr(designer, "name", None)
            or getattr(existing, "designerEmail", None)
            or "a designer"
        ),
        date=(created.date().isoformat() if isinstance(created, datetime) else "an earlier date"),
    )


# --------------------------------------------------------------------------------------
# The create
# --------------------------------------------------------------------------------------


def _midnight_utc(value: date) -> datetime:
    """A DATE stored in a timestamp column, always at midnight UTC.

    Prisma has no date-only type on this schema, so every date column here is a timestamp and every
    writer has to agree on the time of day. Anything else makes ``sanctionOrderDate`` sort by the
    hour an officer happened to be at their desk, and makes the stage-1 copy — which is a plain
    ``YYYY-MM-DD`` string — disagree with the register in a timezone nobody chose.
    """
    return datetime(value.year, value.month, value.day, tzinfo=UTC)


async def create_from_sanction(payload: Any, officer: Any) -> dict[str, Any]:
    """The whole flow. See the module docstring for the transaction argument.

    Answers the 201 body: ``{sanctionOrder, credentialLink, credentialLinkProblem}``.
    """
    stored_no = tidy_sanction_order_no(payload.sanctionOrderNo)
    key = normalise_sanction_order_no(stored_no)
    if not key:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="A sanction order number needs at least one letter or digit.",
        )

    # ── PHASE 0: EVERY READ AND EVERY REFUSAL, BEFORE ONE ROW IS WRITTEN ──────────────────────
    # The rule the workshop create route states in its own words: a create that cannot honour the
    # body it was given must not half-succeed. Asked after the writes, each of these would leave a
    # committed orphan behind on every retry.
    keys = designers.email_match_keys(payload.designerEmail)
    if not keys:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="That is not an address this system can use.",
        )
    # THE ONE REFUSAL THAT NEEDS NO QUERY, SO IT IS ASKED FIRST. An officer naming their own mailbox
    # is not a fact about the register or the roster — it is a fact about the two addresses in hand.
    _refuse_if_the_officer_named_themselves(officer, keys)
    await _refuse_if_number_taken(key)
    existing_admission = await _the_allow_list_row_or_refuse_if_barred(payload.designerEmail)
    await _refuse_if_empanelment_ended(keys)
    existing_user = await _existing_account(keys)

    canonical = designers.canonical_email(payload.designerEmail)
    existing_user = await _account_the_register_already_knows(canonical, existing_user)
    # ASKED HERE AND NOT INSIDE THE TRANSACTION, and asked of the account the two lookups above
    # finally settled on rather than of the first one found: a 422 raised after the workshop row is
    # written leaves a committed orphan behind on every retry, and this is the refusal that used to
    # be missing altogether — see the function for the 404 that cost.
    _refuse_if_the_named_account_cannot_run_the_workshop(existing_user)
    officer_name = str(getattr(officer, "name", None) or getattr(officer, "email", "") or "")
    note = SANCTION_ADMISSION_NOTE.format(no=stored_no, officer=officer_name)
    designer_name = str(payload.designerName).strip()
    admission = _an_admission_that_preserves_an_admins_decision(
        existing_admission, designer_name=designer_name, note=note
    )

    account_created = False
    secret: str | None = None

    async with db.tx(max_wait=timedelta(seconds=10), timeout=timedelta(seconds=30)) as tx:
        # 1.1 THE ALLOW-LIST. Idempotent: find-then-update-or-create, and ``joinedAt`` is written
        # once so a designer who has been here since 2024 does not read as having joined today.
        # The returned row is deliberately NOT used to derive `User.email`. It carries whatever
        # spelling the allow-list already held — `admit`'s own docstring says "a new row is written
        # under the mailbox; an EXISTING ROW KEEPS THE SPELLING IT HAS" — so reading the address back
        # off it would make the account's address depend on whether a row happened to exist, which is
        # a coin-flip between the literal and the canonical form on exactly the aliased addresses
        # that make the distinction matter.
        #
        # ``**admission`` IS FOUR ARGUMENTS AND EVERY ONE OF THEM IS A DECISION, not a spread taken
        # for brevity: ``admit_role``, ``full_name``, ``note`` and ``decided`` are the four columns
        # this call used to overwrite on an allow-list row an administrator had already decided
        # about. ``joinedAt`` is the only one ``admit`` protects for itself. What is sent, and why
        # each field is sent or withheld, is argued in
        # :func:`_an_admission_that_preserves_an_admins_decision`; it is computed in phase 0 because
        # it depends on a READ of the existing row, which from in here would see this transaction's
        # own work.
        await access_roster.admit(
            payload.designerEmail,
            actor_id=getattr(officer, "id", None),
            client=tx,
            **admission,
        )

        # 1.2 THE EMPANELMENT. Create-only — it answers False where a row already stood and it never
        # revives a suspended one, which phase 0 has already refused. ``swallow_race=False`` because
        # we are INSIDE a transaction: a swallowed ``UniqueViolationError`` here leaves an aborted
        # Postgres transaction and hands the officer an opaque 500 from a write three steps later.
        await designers.ensure_empanelled(
            payload.designerEmail,
            actor_id=getattr(officer, "id", None),
            note=note,
            client=tx,
            swallow_race=False,
        )

        if existing_user is None:
            # ── THE PASSWORD NOBODY EVER SEES, AND WHY THE ACCOUNT IS NOT CREATED THROUGH
            # ── POST /api/users. THREE ROUTES WERE AVAILABLE AND TWO ARE WORSE.
            #
            # (a) POST /api/users. Its body REQUIRES a password and ``APIModel`` forbids extras, so
            #     a sanction flow cannot post without inventing one and cannot add ``invite: true``
            #     without a schema change. It is also ``require_admin``, where ``is_admin`` is the
            #     SET {ADMIN, MASTER_ADMIN} and not a rank, so a MINISTRY_ADMIN at 48 is refused by
            #     it. Calling it from here would mean widening the one route in this product that
            #     mints accounts, for a caller that is not an admin.
            #
            # (b) Adding ``password: str | None`` + ``invite: bool`` to ``UserCreate``. That makes
            #     the password OPTIONAL on the route an administrator uses by hand — a real
            #     loosening of the one door that hands out credentials, bought to serve a caller
            #     that does not use that door.
            #
            # (c) THIS. Mint 32 bytes of ``secrets.token_urlsafe`` here, hash it with the same
            #     ``hash_password`` every other account uses, and never return it, log it or store
            #     it in plaintext. The account is then in EXACTLY the state POST /api/users leaves
            #     one in — a real ``passwordHash``, a real ``passwordSetAt``,
            #     ``mustChangePassword: True`` — which is the state every downstream gate and every
            #     test already understands.
            #
            # WHY NOT LEAVE ``passwordHash`` NULL. ``issue_link`` reads it: NULL means purpose
            # INVITE with a 72-hour TTL, which is the RIGHT purpose here — but a NULL hash also
            # means the account can be signed into by nobody at all if the link is lost. A random
            # hash gives up nothing (it is unguessable by construction, so the account is equally
            # unreachable) and it keeps ``passwordSetAt`` honest as "this account has had a password
            # since the day it was made". The purpose is therefore passed EXPLICITLY below rather
            # than inferred, because a non-NULL hash would otherwise infer RESET and its 2-hour TTL,
            # which is far too short for a link an officer forwards by hand to somebody in the field.
            secret = secrets.token_urlsafe(32)
            user = await tx.user.create(
                data={
                    # ── THE LITERAL LOWER-CASED ADDRESS, NOT THE CANONICAL ONE ────────────────
                    # This is the single most consequential line in the function and it reads as
                    # the wrong choice until you follow it. BOTH sign-in doors look ``User.email``
                    # up LITERALLY: ``identity.resolve_identifier`` does
                    # ``find_unique(where={"email": normalise_email(identifier)})`` and
                    # ``normalise_email`` is only strip-and-lower, while ``login_with_google``
                    # matches on Google's own claim. ``canonical_email`` strips every dot and the
                    # ``+`` suffix from a Gmail local part — so an account stored canonically for
                    # ``sandy.craft3@gmail.com`` 401s on password sign-in AND is MISSED by Google
                    # sign-in, which then mints a SECOND User row. Writing the canonical form here
                    # would manufacture, on every dotted Gmail, the exact split ``routes/auth.py``
                    # records as a known defect.
                    #
                    # The canonical form belongs on the two ROSTER rows (both of which key on the
                    # mailbox) and on ``SanctionOrder.designerEmail``. That the account address and
                    # the register address differ for an aliased Gmail is DELIBERATE, is the same
                    # asymmetry ``POST /api/users`` already creates, and is confusing enough that
                    # the officer's prewritten message names the sign-in address explicitly.
                    "email": designers.normalise_email(payload.designerEmail),
                    "name": designer_name,
                    "passwordHash": hash_password(secret),
                    "passwordSetAt": datetime.now(UTC),
                    "mustChangePassword": True,
                    "role": "DESIGNER",
                    "authProvider": "LOCAL",
                    # Every grant spelled out and every one False, matching ``UserCreate``'s own
                    # defaults. A designer needs none of them, and a sanction order is not the place
                    # to grant a capability.
                    "canManageQuestionnaire": False,
                    "canManageCrafts": False,
                    "canManageWorkshops": False,
                    "canReview": False,
                    "canViewProvenance": False,
                    "canDownloadDataset": False,
                }
            )
            account_created = True
        else:
            user = existing_user
            # ROLE: LIFT, NEVER LOWER. An ADMIN named on a sanction order keeps their tier — the
            # same rule ``routes/auth.py`` and ``routes/access.py`` apply when an allow-list row
            # names a role, and the reason ``test_designer_roster`` has a case for it.
            # ``mustChangePassword`` is deliberately NOT set here: they already have credentials, and
            # setting it would send somebody who signs in every day to a change-password screen
            # because a colleague recorded a sanction order.
            #
            # **THIS COMMENT SAID "AN ADMIN OR A PROFESSOR" UNTIL 2026-09-14 AND THE PROFESSOR HALF
            # WAS THE DEFECT, NOT A FEATURE.** A rank floor is the right test for "do not demote
            # anybody" and the WRONG test for "can this person run the workshop", because
            # ``DESIGN_WORKSHOP_ROLES`` is a set: a professor kept their tier here and was then
            # handed a viewer row nothing would honour. A professor no longer reaches this line at
            # all — ``_refuse_if_the_named_account_cannot_run_the_workshop`` turned that silent 201
            # into a 422 in phase 0, naming the screen that fixes it. So the accounts that survive
            # to here are exactly: below DESIGNER (lifted on the next line), or already inside
            # ``DESIGN_WORKSHOP_ROLES`` (left alone, correctly). Widening the phase-0 refusal without
            # re-reading this comparison re-opens the same hole.
            if role_rank(user) < ROLE_RANK["DESIGNER"]:
                user = await tx.user.update(where={"id": user.id}, data={"role": "DESIGNER"})

        # 1.4 THE PROFILE. An upsert, so this is safe for an account that already has one, and it
        # exists because workshop creation READS the profile — a create path that has to handle
        # "no row yet" as well as "row with no values" is two paths where one will do.
        await designers.get_or_create_profile(user.id, client=tx)

        # 1.5 THE WORKSHOP. ``createdById`` IS THE OFFICER, not the designer: the officer is who
        # brought it into existence, and the designer's access comes from the viewer row below.
        # ``schemaVersion`` is stamped exactly as the admin create route stamps it — the column is
        # nullable, so omitting it fails nothing and leaves sanctioned workshops carrying NULL where
        # every hand-created one carries a version, on a value read straight back out on the
        # provenance surface.
        workshop = await tx.designworkshop.create(
            data={
                # The placeholder. ``DesignWorkshop.title`` is NOT NULL so the create must supply
                # one, and stage 1's ★ ``workshopTitle`` is deliberately NOT seeded from it: seeding
                # would freeze the placeholder into the stage where a later title edit cannot reach
                # it, and would make a required field read as filled when nobody has filled it.
                # Left unseeded, ``workshopTitle`` correctly appears in ``missingMandatory`` and the
                # designer's first stage-1 save promotes the real title over this string.
                "title": f"Sanctioned workshop {stored_no}",
                "templateId": "DCH_STANDARD",
                "createdById": getattr(officer, "id", None),
                "schemaVersion": registry_version(),
                "status": "DRAFT",
            }
        )

        # 1.6 THE VIEWER ROW — the thing that actually lets the designer open it. Idempotent:
        # ``add_one_viewer`` uses ``create_many(skip_duplicates=True)``.
        #
        # ELIGIBILITY IS VALIDATED IN PHASE 0 AND MUST NOT BE RE-ASKED IN HERE. Four refusals, and
        # this comment named only three of them until 2026-09-14 because the fourth did not exist:
        # barred address, ended empanelment, ambiguous account — AND
        # ``_refuse_if_the_named_account_cannot_run_the_workshop``, which is the one that decides
        # whether the row written on the next line can actually do anything. Without it this call
        # wrote a viewer row for an INSPECTOR, a PROFESSOR or any of the three directorate tiers and
        # ``load_workshop_or_404`` refused to honour it — a 201 for the officer and a permanent 404
        # for the designer, undoable from inside the product. If you add a fifth refusal, add it to
        # phase 0 and to this list; a refusal raised from in here rolls back a ministry sanction
        # order at the second-to-last statement, and the officer cannot tell that from a 500.
        #
        # ``assert_every_designer_may_be_named`` STILL MUST NOT BE CALLED — not here and not in phase
        # 0. Two of its three arms ask about the roster rows this transaction is writing: from in
        # here they read through the module singleton, see nothing, and refuse every sanction order
        # ever recorded; from phase 0 they refuse every researcher this flow is about to lift and
        # every designer it is about to empanel. The phase-0 refusal above asks the one question that
        # survives the transaction instead, and says why in its own docstring.
        await attach_the_named_designer(
            workshop.id,
            user.id,
            granted_by_id=getattr(officer, "id", None),
            creator_id=getattr(officer, "id", None),
            client=tx,
        )

        # 1.7 THE REGISTER ITSELF. The unique index on ``sanctionOrderKey`` is the real duplicate
        # guard; the pre-check above is only the friendly half of it.
        try:
            row = await tx.sanctionorder.create(
                data=decimal_to_string(
                    {
                        "sanctionOrderNo": stored_no,
                        "sanctionOrderKey": key,
                        "sanctionOrderDate": _midnight_utc(payload.sanctionOrderDate),
                        "sanctionAmount": payload.sanctionAmount,
                        "designerUserId": user.id,
                        # THE CANONICAL MAILBOX, and not ``user.email``. See the account create
                        # above: for an aliased Gmail those two differ on purpose, and THIS is the
                        # key both rosters were written under.
                        "designerEmail": canonical,
                        "designWorkshopId": workshop.id,
                        "createdById": getattr(officer, "id", None),
                        "accountCreated": account_created,
                        "notes": payload.notes,
                    }
                )
            )
        except UniqueViolationError as exc:
            # THE RACE THE PRE-CHECK CANNOT WIN. Two officers, one number, the same second. The
            # transaction rolls the whole thing back — no orphan account, no orphan workshop, no
            # half-admission — and the loser gets the same sentence the pre-check would have given.
            duplicate = await db.sanctionorder.find_unique(
                where={"sanctionOrderKey": key},
                include={"designWorkshop": True, "designerUser": True},
            )
            detail = (
                _duplicate_detail(duplicate)
                if duplicate is not None
                else f"Sanction order {stored_no} is already recorded."
            )
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=detail) from exc

    # ── PHASE 2: AFTER THE COMMIT. Each of these may fail without undoing the workshop. ────────
    # THE HOUSE RULE, WITHOUT EXCEPTION: every write to a ``User`` row invalidates the cached copy.
    # A designer whose role was just lifted to DESIGNER and whose cached row still says RESEARCHER is
    # refused by the designer gate for as long as the TTL runs, on the morning they were told to
    # start. This is not a database write, so it belongs after the commit and not inside it.
    invalidate_cached_user(user.id)
    await _seed_the_report_copy(workshop, officer, user.id, stored_no, payload.sanctionOrderDate)

    link, problem = await _issue_first_credential(
        user=user, officer=officer, account_created=account_created
    )

    return {
        "sanctionOrder": await sanction_payload(
            row, workshop=workshop, designer=user, officer=officer
        ),
        "credentialLink": link,
        "credentialLinkProblem": problem,
    }


async def _seed_the_report_copy(
    workshop: Any, officer: Any, designer_id: str, stored_no: str, order_date: date
) -> None:
    """Put the sanction number and date into stage 1, ONCE, as the report's own copy.

    OUTSIDE THE TRANSACTION ON PURPOSE — ``seed_designer_prefill``'s own docstring says prefill
    never fails the create, and it is a dozen writes behind a blanket ``except``. A never-fail block
    inside an all-or-nothing one is a contradiction; this way the workshop is already committed when
    it runs, exactly as it is for the admin create path.

    ``extra`` WINS OVER THE PROFILE VALUES, which is what makes these two the officer's facts rather
    than the designer's. It is a COPY and it is allowed to drift: a designer correcting a mistyped
    number on the cover of their own report is doing something legitimate, and the register reports
    the disagreement (``reportCopyMatches``) instead of blocking the edit.
    """
    try:
        await seed_designer_prefill(
            workshop,
            officer,
            designer_id=designer_id,
            extra={
                "sanctionOrderNo": stored_no,
                "sanctionOrderDate": order_date.isoformat(),
            },
        )
    except Exception:  # pragma: no cover - the seed's own contract is that it never fails a create
        log.exception("sanction order %s: stage-1 prefill failed", stored_no)


async def _issue_first_credential(
    *, user: Any, officer: Any, account_created: bool
) -> tuple[dict[str, Any] | None, str | None]:
    """The designer's first sign-in link — and the three cases where there is honestly nothing.

    THE TRANSPORT IS THE OFFICER'S CLIPBOARD AND THE SCREEN SAYS SO. ``credential_links.delivery()``
    hard-returns ``CopyLinkDelivery``, which logs one line WITHOUT the link and answers
    ``"COPY_LINK"``. No mail leaves this server, because there is no mailer in this repository and
    adding one needs a verified sending identity, SES production access, IAM credentials on the API
    box and a DMARC-aligned From domain — an infrastructure ticket, not a line in this change. The
    officer's screen therefore offers the link to copy and a prewritten message to paste, and says
    in words that nothing has been emailed.

    NO LINK IS MINTED, AND THE SCREEN SAYS WHICH:
      * the account already existed — they sign in as they always do, and minting a RESET here would
        sign them out of every device the moment they redeemed it;
      * the account signs in with Google — there is no password to set;
      * the 4-per-hour budget is already spent — the 201 still stands, with a problem sentence and a
        re-issue action, because a throttle must never roll back a ministry sanction order.
    """
    if not account_created:
        return None, None
    if str(getattr(user, "authProvider", "") or "").upper() == "GOOGLE":
        return None, None
    try:
        delivered = await credential_links.issue_link(
            user=user,
            # EXPLICIT, NOT INFERRED. The account was just given a real (random, unguessable) hash,
            # so the default would infer RESET and its 2-hour TTL — far too short for a link an
            # officer forwards by hand to somebody who may be in the field. INVITE is 72 hours and
            # is what this moment actually is: a first credential, not a reset.
            purpose=credential_links.INVITE,
            issued_by_id=getattr(officer, "id", None),
        )
    except credential_links.IssueThrottled:
        return None, SANCTION_LINK_PROBLEM_THROTTLED
    return _link_payload(delivered), None


def _link_payload(delivered: Any) -> dict[str, Any]:
    return {
        "id": delivered.id,
        "link": delivered.link,
        "expiresAt": delivered.expiresAt,
        "purpose": delivered.purpose,
        "deliveredBy": delivered.deliveredBy,
    }


async def reissue_credential_link(row: Any, officer: Any) -> dict[str, Any]:
    """Mint another sign-in link for the designer this order names.

    **THE USER ROW IS RE-READ HERE AND THAT IS NOT BELT-AND-BRACES.** ``issue_link`` binds the token
    to ``user.passwordHash``: minting against a stale object produces a link that is already spent
    and a designer who is told their link is invalid on their first use of it.

    **AND NO ``purpose`` IS PASSED, WHICH IS THE OPPOSITE OF THE CREATION PATH.** There, the account
    had just been given a machine-minted hash nobody holds, so INVITE's 72 hours were right. Here the
    designer may have set a password weeks ago, and forcing INVITE would triple the lifetime of a
    session-revoking credential for an established account. The default — INVITE while the hash is
    NULL, RESET once it is not — is the distinction ``ttl_hours`` exists to draw, and this route
    lets it do its job.

    **WHAT BOUNDS THIS ARM IS IN PHASE 0 OF THE CREATE, WHICH IS WHY THERE IS NO SECOND CHECK HERE.**
    This hands an officer a working sign-in link for whoever the order names. If an officer could
    name themselves, this would be a second, permanent door to the escalation the create refuses —
    author the fieldwork as the puppet designer, approve it as the officer — reopenable at any time
    and long after the 201 is forgotten. :func:`_refuse_if_the_officer_named_themselves` is what
    means no ``SanctionOrder`` row names the officer who recorded it, so this route can only ever
    mint a credential for somebody else. Loosen that refusal and this route loosens with it.

    IT IS NOT SCOPED TO THE OFFICER WHO RECORDED THE ORDER, AND THAT IS A SEPARATE, KNOWN COST. Any
    sanction recorder may re-issue any order's link, so one officer can take over the account of a
    colleague's named designer. Narrowing it to ``createdById`` was considered and is the wrong
    shape: an officer on leave is exactly when the designer's link needs re-issuing, and the answer
    to "who took this credential" is an audit question — the ``PasswordResetToken`` row records
    ``issuedById`` — rather than a reason to strand a designer. Raise it with the owner before
    treating the current behaviour as settled.
    """
    user = await db.user.find_unique(where={"id": row.designerUserId})
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="The designer this order names no longer has an account.",
        )
    if str(getattr(user, "authProvider", "") or "").upper() == "GOOGLE":
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"{user.name or user.email} signs in with Google — there is no password to set, so "
                "there is no sign-in link to issue."
            ),
        )
    try:
        delivered = await credential_links.issue_link(
            user=user, issued_by_id=getattr(officer, "id", None)
        )
    except credential_links.IssueThrottled as exc:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=SANCTION_THROTTLE_DETAIL,
            headers={"retry-after": str(exc.retry_after_minutes * 60)},
        ) from exc
    return _link_payload(delivered)


# --------------------------------------------------------------------------------------
# Reading one back
# --------------------------------------------------------------------------------------


async def _stage_one_state(workshop_id: str) -> tuple[dict[str, Any] | None, Any]:
    """Stage 1's ``workshopSetup`` singleton (or None) and its completeness score.

    ONE SCORER, NEVER TWO. ``stage_completeness`` is the registry's own arithmetic and every reader
    in this product goes through it rather than counting required fields for itself — which is what
    stops two arithmetics appearing inside one document, neither of them wrong enough to fail. The
    officer's list therefore reports the SAME "11 of 13 still needed" the designer's own readiness
    screen reports, because it is literally the same number.
    """
    rows = await entry_rows(workshop_id, stage_key=STAGE_ONE_KEY)
    singleton: dict[str, Any] | None = None
    for row in rows:
        if getattr(row, "entityKey", None) == STAGE_ONE_ENTITY:
            singleton = dict(getattr(row, "data", None) or {})
            break
    score = stage_completeness(stage(STAGE_ONE_KEY), singleton or {}, {})
    return singleton, score


def _report_copy_matches(singleton: dict[str, Any] | None, row: Any) -> bool | None:
    """Does the report's cover still say what the register says? None where there is no cover yet.

    DRIFT IS REPORTED, NOT PREVENTED — see the model comment. Compared through
    :func:`normalise_sanction_order_no` so that a designer who retyped ``SO-2026-42`` as
    ``SO/2026/42`` is not reported as disagreeing with an order they copied correctly.
    """
    if singleton is None:
        return None
    same_no = normalise_sanction_order_no(singleton.get("sanctionOrderNo")) == str(
        getattr(row, "sanctionOrderKey", "") or ""
    )
    stored_date = getattr(row, "sanctionOrderDate", None)
    want = stored_date.date().isoformat() if isinstance(stored_date, datetime) else None
    same_date = str(singleton.get("sanctionOrderDate") or "")[:10] == (want or "")
    return bool(same_no and same_date)


async def sanction_payload(
    row: Any,
    *,
    workshop: Any | None = None,
    designer: Any | None = None,
    officer: Any | None = None,
) -> dict[str, Any]:
    """The wire form of one sanction order. HAND-BUILT, and never ``jsonable_encoder(row)``.

    ``jsonable_encoder`` converts a ``Decimal`` to a ``float`` — measured — so a single "tidy-up"
    refactor to ``jsonable_encoder(row)`` would silently turn a ministry budget into a binary float
    with nothing failing.
    ``tests/test_sanction_orders.py::test_the_amount_never_reaches_the_wire_as_a_float`` reads the
    raw response bytes so that refactor cannot pass.
    """
    workshop = workshop or getattr(row, "designWorkshop", None)
    designer = designer or getattr(row, "designerUser", None)
    officer = officer or getattr(row, "createdBy", None)

    workshop_id = getattr(row, "designWorkshopId", None) or getattr(workshop, "id", None)
    singleton, score = await _stage_one_state(workshop_id)

    order_date = getattr(row, "sanctionOrderDate", None)
    return {
        "id": row.id,
        "sanctionOrderNo": row.sanctionOrderNo,
        "sanctionOrderDate": (
            order_date.date().isoformat() if isinstance(order_date, datetime) else None
        ),
        # A DECIMAL STRING. Never a float, never a number. See the docstring.
        "sanctionAmount": str(row.sanctionAmount),
        "designerUserId": row.designerUserId,
        "designerName": getattr(designer, "name", None) or "",
        "designerEmail": row.designerEmail,
        "accountCreated": bool(row.accountCreated),
        "notes": row.notes,
        "designWorkshopId": workshop_id,
        "workshopTitle": getattr(workshop, "title", None) or "",
        "workshopStatus": str(
            getattr(getattr(workshop, "status", None), "value", None)
            or getattr(workshop, "status", None)
            or "DRAFT"
        ),
        "createdById": row.createdById,
        "createdByName": getattr(officer, "name", None),
        "createdAt": _iso(getattr(row, "createdAt", None)),
        "updatedAt": _iso(getattr(row, "updatedAt", None)),
        "reportCopyMatches": _report_copy_matches(singleton, row),
        "readyForWork": bool(score.is_complete),
        # THE REGISTRY'S OWN LIST OF LABELS, not a count this module made up. See _stage_one_state.
        "missingMandatory": list(score.missing),
    }


def _iso(value: Any) -> str | None:
    return value.isoformat() if isinstance(value, datetime) else None


SANCTION_INCLUDE: dict[str, Any] = {
    "designWorkshop": True,
    "designerUser": True,
    "createdBy": True,
}


async def awaiting_count() -> int:
    """How many recorded orders are still waiting on their designer's stage-1 details.

    THE ONLY NOTIFICATION-SHAPED MECHANISM IN THIS PRODUCT, and it points at the OFFICER rather than
    at the designer — modelled on ``GET /access/roster/pending-count``, which is the same shape and
    the same argument. On the day a sanction is recorded the designer has no session, no app and no
    way in, so a badge they can only see after signing in is a badge about an event that has already
    been overtaken. The officer, on the other hand, can act: chase the designer, or re-issue the
    sign-in link the designer never received.

    Counted by asking the SAME scorer the list asks, one workshop at a time, rather than by a clever
    query. There is no column to index on — readiness lives in stage JSON — and the alternative is a
    second arithmetic that would disagree with the list it badges. If this ever becomes slow the
    answer is a cached column written by the stage save, not a second count written here.

    **IT COUNTS EVERY ORDER AND NOT THE CALLER'S OWN, DELIBERATELY**, and it takes no `officer`
    argument so that nobody can assume otherwise from the signature. The badge answers "how much of
    this office's work is stalled", which is what a regional director opening the screen needs; the
    LIST is where `mine=true` narrows it to one officer's own. A badge scoped one way over a list
    scoped another is how "3 waiting" comes to open onto nothing.
    """
    rows = await db.sanctionorder.find_many(order={"sanctionOrderDate": "desc"})
    awaiting = 0
    for row in rows:
        _singleton, score = await _stage_one_state(row.designWorkshopId)
        if not score.is_complete:
            awaiting += 1
    return awaiting
