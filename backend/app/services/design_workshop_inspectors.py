"""**THE FIFTH SCOPE: which design & prototype workshops one INSPECTOR may READ, and read only.**

An INSPECTOR is the tier between DESIGNER (35) and PROFESSOR (40): somebody who INSPECTS and
REVIEWS a designer's work without running workshops themselves. The canonical enum value is
``INSPECTOR`` and not ``REVIEWER``, deliberately — "review" already names a different, RELATIONAL
concept in this codebase (``deps.can_review_record`` is held from FIELD_CONTRIBUTOR upward and
means "may review anyone ranked strictly below me"), and one word meaning two things is how a
permission bug hides. The UI label is "Inspector / Reviewer" so users see both words.

=======================================================================================
THE TRAP THIS MODULE EXISTS TO WALK AROUND
=======================================================================================

**EVERY DESIGN-WORKSHOP GATE IN THIS PRODUCT IS SET MEMBERSHIP, NOT A RANK FLOOR.** That is why
PROFESSOR, at rank 40, cannot open a design workshop today: ``deps.DESIGN_WORKSHOP_ROLES`` is
``frozenset({"DESIGNER", "ADMIN", "MASTER_ADMIN"})``, ``_require_designer`` stands in front of
eighteen routes, and ``load_ratable_workshop_or_404`` 404s anybody outside the set before it looks
at anything.

So inserting a rank between 35 and 40 buys the new tier **zero** workshop authority — exactly
PROFESSOR's position — and no test fails to say so. The rank is the easy half. This module is the
other half: the SCOPE. An inspector holds no workshop authority from their rank and gets everything
they have from a row in ``DesignWorkshopInspector``.

=======================================================================================
READ-ONLY IS STRUCTURAL HERE. IT IS NOT A FLAG, A TIER OR A POLICY NOTE.
=======================================================================================

**THIS IS THE MOST IMPORTANT PARAGRAPH IN THE FILE AND IT MUST NOT BE "SIMPLIFIED".**

The obvious way to build this is a ``DesignWorkshopViewer`` row, or a ``level`` column on one. It
was designed and rejected, because **a viewer row confers STAGE WRITES**:
``design_workshops.load_workshop_or_404(..., for_edit=True)`` admits the creator, an admin, or a
viewer grantee, and that one helper is what FOURTEEN write routes pair with ``_require_designer``,
what the export ledger stands behind ALONE, and what the report route stands behind alone. A
predicate added to it is a WRITE grant whatever it is named.

**THIS PARAGRAPH USED TO SAY THAT HELPER "performs no role check whatsoever — the creator, an admin,
or ANY viewer grantee passes", AND THAT HALF IS NOW WRONG (corrected 2026-09-03).** It role-gates its
GRANT arm: a viewer row is honoured only for an account inside ``DESIGN_WORKSHOP_ROLES``. That change
does not weaken one word of the argument above — it strengthens it in one direction and leaves the
hazard exactly where it was. ``INSPECTION_ROLES`` is disjoint from that set BY THE IMPORT-TIME
INVARIANT at the foot of this file, so today an inspector holding a viewer row would be refused by
the loader's own role clause as well as by this module's structure. That is a second line, not the
structure: it holds only for as long as the two sets stay disjoint, and the whole reason this scope
is a separate table is that a rule which depends on somebody remembering a set membership is the
rule that lapses. ``for_edit=True`` still carries no role check of its own, so widening the set is
still a write grant.

FOURTEEN, AND NOT THE EIGHTEEN THIS SENTENCE FIRST SAID. Eighteen is a true count of a DIFFERENT
set — every route ``_require_designer`` guards, which is what the paragraph above uses it for —
and it includes two GET allowance probes that write nothing and never touch this loader. Counted
here by walking ``api/routes/design_workshops.py`` for ``@router.<verb>`` blocks that contain BOTH
``_require_designer(current_user)`` and ``load_workshop_or_404(..., for_edit=True)``: nine do it in
the handler (``POST /{id}/dictate``, ``POST /{id}/dictation-consent``, ``PATCH /{id}``,
``PUT /{id}/stages/{key}``, ``PUT /{id}/custom-sections``, ``POST /{id}/ai-layers``, and the
accept / unaccept / delete trio on ``/{id}/ai-layers/{layer_id}``), and five more inherit the pair
from ``_verb_gate``, which calls both itself — proofread, expand, translate, caption, subtitles.
The router has 22 non-GET routes of which 16 are gated at all (11 directly, 5 through the verb
gate); ``tests/test_design_workshop_gate.py`` pins those three numbers, so eighteen could not have
been a count of writes. The two gated writes that are NOT in the fourteen are ``POST /ocr/identity``
and ``POST /ocr/identity/retention``, which have no workshop to load.

So this scope's predicate is **never** added to it. An inspector reads through
:func:`load_inspectable_workshop_or_404` in this module, which is called from
``api/routes/design_workshop_inspections.py`` and nowhere else, and which returns a workshop for
READ. **It has no ``for_edit`` parameter, and adding one is the single change this file refuses.**
There is no code path on which an inspection row and a write meet, so there is no check anybody can
forget to write.

The precedent is ``DesignWorkshopProvisionalMember``, whose schema comment makes the mirror-image
argument: a separate table that nothing existing consults, so its holder is a stranger to every
READ gate. This is the same construction pointed the other way — a stranger to every WRITE gate.
``tests/test_dw_inspector_scope_gate.py`` asserts it against the source rather than trusting it.

=======================================================================================
WHAT AN INSPECTION ROW DOES **NOT** CARRY
=======================================================================================

A scope whose limits are untested is a scope that will quietly widen, so each of these has an
assertion behind it in ``tests/test_dw_inspector_scope_gate.py``:

* **No stage writes.** ``PUT /design-workshops/{id}/stages/{key}`` — the whole 22-stage fortnight —
  is ``_require_designer`` plus ``load_workshop_or_404``. An inspector fails both.
* **No report generation.** ``POST /design-workshops/{id}/report`` is open to anyone who can READ
  the workshop *through that loader*, which an inspector cannot.
* **No dictation consent.** Taking the artisan's Tier-3 answer down is the designer's act, sitting
  with them; ``POST /{id}/dictation-consent`` is ``_require_designer`` + ``for_edit=True``.
* **No AI-layer acceptance**, registration, withdrawal or decline, and none of the five AI verbs.
* **No delete and no restore** (``assert_can_delete`` / ``require_admin``).
* **No re-granting.** An inspector cannot put another inspector — or a viewer — on anything.
* **No custom sections, no export-ledger row, no questionnaire writes.**
* **No media, and this one is worth reading twice.** The "recordings of a workshop I may open" arm
  of ``records._design_workshop_media_branches`` is keyed on ``DesignWorkshopViewer`` and
  ``createdById`` through ``design_workshop_viewers.visible_to_clause``. An inspector holds
  neither, so the artisan's recorded voice, the photographs and the transcripts are **not** in this
  grant's gift — and ``owned_or_granted_where`` hands an inspector nothing extra either, because
  that function's free pass starts at ``has_rank(user, "PROFESSOR")``, rank 40, above this tier.
  Whether an inspector SHOULD see a workshop's photographs is an owner's decision that has not been
  made; it is deliberately not made here by accident.
* **No questionnaire responses**, for the same structural reason:
  ``questionnaire_forms._visible_questionnaire_where`` writes ``viewers: {some: {userId}}`` by hand.

=======================================================================================
WHO MAY CREATE AN INSPECTION, AND THE HONEST REFUSAL WHEN THEY MAY NOT
=======================================================================================

**ADMIN ONLY**, and the argument is stronger here than the one that makes the viewers screen
admin-only. That module's reason is handover — an owner who chooses their own readers leaves their
workshop's access frozen when they go. This module's reason is the point of the tier:

    **THE INSPECTED MUST NOT CHOOSE THE INSPECTOR.** If a designer could put somebody on their own
    workshop as its inspector, or take somebody off it, the inspection is worth nothing. That is not
    a workflow preference; it is the entire value of an independent review, and it is why
    ``replace_inspectors`` is reached only through ``Depends(require_admin)`` and why the workshop's
    creator gets no say at all — not even a "suggest an inspector" route.

Two refusals follow from it, and both are enforced rather than documented:

1. A non-admin calling the administration routes gets a 403 from ``require_admin``.
2. **An account that is on the workshop cannot inspect it.** The creator, and anybody holding a
   ``DesignWorkshopViewer`` row for the same workshop, is refused by name — see
   :func:`_assert_every_id_may_inspect`. Today the role sets make that nearly unreachable
   (``INSPECTION_ROLES`` and ``DESIGN_WORKSHOP_ROLES`` are disjoint, asserted below and in the
   tests), but "nearly" is doing real work: a DESIGNER holding a viewer row who is later PROMOTED
   to INSPECTOR would otherwise become eligible to inspect the very workshop they worked on. Role
   changes are not hypothetical and nothing else in the codebase would notice.

=======================================================================================
WHAT WAS BORROWED FROM ``design_workshop_viewers``, AND WHAT DELIBERATELY WAS NOT
=======================================================================================

BORROWED, because two spellings of one rule is how the two drift apart: the whole-set PUT that
replaces the roster; validation that runs to completion before any write, so one bad id refuses the
whole call naming the account; the control-character guard on ids; the platform allow-list read as
a CUT LIST and never as a guest list; the ``truncated`` contract on the picker; the row being the
grant with no status column; DELETE rather than revoke on removal.

NOT BORROWED:

* **The DESIGNER empanelment roster.** ``DesignerRoster`` gates a DESIGNER's sign-in and says who
  is empanelled to run workshops. An inspector is not empanelled to run anything, so requiring a
  roster row would refuse every inspector there will ever be. The PLATFORM allow-list still applies
  — it gates every role — which is why ``access_roster`` is imported here and ``designers`` is not.
* **The predicate names.** :func:`has_inspection_scope` and :func:`inspectable_by_clause`, never
  ``has_viewer_grant`` / ``visible_to_clause``. The names differ so that a future
  ``from app.services.design_workshop_inspectors import visible_to_clause`` cannot be written by
  autocomplete into ``records._design_workshop_media_ids``, which follows the viewer clause on its
  own written instruction "so the day that widens again the audio widens with it". Following THIS
  clause there would hand an inspector the artisan's recorded voice.
* **The creator being silently dropped from the set.** ``_deduplicate`` there removes the creator as
  a harmless no-op, because they already hold the access being granted. That silence is right there
  and wrong here: naming the creator asks for a designer to inspect their own work, which is a
  MISTAKE an admin needs to be told about rather than a no-op.

Current as of 2026-08-27. Re-check the claims about the write path with::

    grep -n "for_edit" backend/app/services/design_workshops.py
    grep -rnE "has_viewer_grant|visible_to_clause" backend/app
"""

import logging
import re
from typing import Any

from fastapi import HTTPException, status

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import DESIGN_WORKSHOP_ROLES, is_break_glass_master, role_value
from app.services import access_roster
from app.services.records import contains

logger = logging.getLogger(__name__)


#: The roles that may hold a ``DesignWorkshopInspector`` row. A SET, not a rank floor.
#:
#: A frozenset of ONE, and that is the shape rather than an oversight. Every design-workshop gate in
#: this product is set membership — ``DESIGN_WORKSHOP_ROLES``, ``can_run_design_workshops`` — and a
#: rank floor written here would mean "INSPECTOR and everything above it", which is PROFESSOR, ADMIN
#: and MASTER_ADMIN. Two of those already see every workshop by a shorter route and the third
#: deliberately sees none; a floor would silently answer a product question nobody asked.
#:
#: **ADMINS ARE NOT IN IT**, and that is the interesting exclusion. An admin reads every workshop
#: through ``/api/design-workshops`` already, so an inspection row for one would be a second,
#: strictly weaker source of access to the same thing — the "two places to look when somebody has
#: access they should not" that ``services/design_workshop_access`` refuses in its header.
#:
#: **PROFESSOR IS NOT IN IT EITHER.** A professor cannot open a design workshop today, and giving
#: them a door through this table would be a new product decision wearing an implementation detail.
#: If the owner wants one, it is one entry here plus a sentence in the refusal below — never a
#: silent widening.
#:
#: DISJOINT FROM ``DESIGN_WORKSHOP_ROLES`` BY CONSTRUCTION, which is what makes "an inspector can
#: never also be a viewer of the same workshop" true rather than hoped for. Checked at import time
#: at the foot of this module, and asserted again in ``tests/test_dw_inspector_scope_gate.py``.
INSPECTION_ROLES = frozenset({"INSPECTOR"})

#: How many inspectors one workshop may be given in a single call.
#:
#: An inspection panel is one person, occasionally two, so 25 is not a limit anybody meets by
#: working. It is here for the reason ``MAX_DESIGN_WORKSHOP_VIEWERS`` is: the validation below reads
#: every named account out of the user table before it writes anything, so an unbounded list makes
#: the cost of one request the caller's to choose. Lower than the viewers' 100 because the two are
#: different quantities — that one holds a field TEAM, this one holds examiners.
MAX_DESIGN_WORKSHOP_INSPECTORS = 25

#: How many accounts the inspector picker will offer in one call.
#:
#: A CEILING, NOT A PAGE SIZE, and it carries ``truncated`` on the wire for the reason
#: ``ELIGIBLE_VIEWER_LIMIT`` learned the hard way: its sibling's ceiling WAS reached on a real
#: repository, the cut fell mid-alphabet, and an eligible colleague sorting past it was
#: indistinguishable from one who had never been empanelled. Those two states must never look
#: identical, so ``search`` reaches past this and ``truncated`` says the list was cut.
ELIGIBLE_INSPECTOR_LIMIT = 2000


def _role(user: Any) -> str:
    """The role as a plain string, whether Prisma handed back an enum or a str."""
    return role_value(user) if user is not None else ""


def is_inspector(user: Any) -> bool:
    """Is this account the inspector tier?

    SET MEMBERSHIP, deliberately, for the reason the module docstring gives at length. Written
    against the string rather than against ``ROLE_RANK`` so that this module is correct on a
    deployment where the tier has not been added to the ladder yet: it simply answers False for
    everybody, which is the fail-closed direction.
    """
    return _role(user) in INSPECTION_ROLES


# --------------------------------------------------------------------------------------
# Reading: the two questions the enforcement asks
# --------------------------------------------------------------------------------------


async def has_inspection_scope(workshop_id: str, user_id: str) -> bool:
    """May this account READ this workshop on the strength of an inspection row?

    A primary-key lookup, not a scan: ``@@id([designWorkshopId, userId])`` is exactly this question,
    which is why the table has no synthetic id.

    ⚠ **THIS IS A READ PREDICATE AND MUST NEVER BE CALLED FROM A WRITE GATE.** It is the mirror of
    ``design_workshop_grants.may_capture``'s warning pointed the other way: that one is a WRITE
    predicate that must never gate a read; this is a READ predicate that must never gate a write. If
    you are about to add ``or await has_inspection_scope(...)`` beside one of the four
    ``has_viewer_grant`` call sites, stop and read this module's header — every one of those sites
    is on a path that also carries stage writes.
    """
    if not workshop_id or not user_id:
        return False
    row = await db.designworkshopinspector.find_unique(
        where={"designWorkshopId_userId": {"designWorkshopId": workshop_id, "userId": user_id}}
    )
    return row is not None


def inspectable_by_clause(user_id: str) -> dict[str, Any]:
    """The inspection list's scope: workshops this account has been assigned to inspect.

    Mirrors ``design_workshop_viewers.visible_to_clause`` in SHAPE so the two cannot drift — the
    same relation-filter idiom, the same composition rule — and differs from it in exactly two ways,
    both deliberate:

    * **There is no ``createdById`` arm.** An inspector creates nothing. The sibling's clause is an
      ``OR`` of "mine" and "granted to me"; this one has a single source, which is the point: an
      inspector with no row sees nothing at all, and there is no second way in to reason about.
    * **It reads a different relation.** ``inspectors``, never ``viewers``.

    **MUST be AND-composed, never assigned to ``where["OR"]``.** The same warning the sibling
    carries, for the same reason: a search box builds an ``OR`` on that key and the later assignment
    silently wins — which is either a search that stops narrowing or a scope that vanishes the
    moment somebody types. The caller nests this under ``where["AND"]``.

    ⚠ **DO NOT IMPORT THIS INTO ``records`` OR ``questionnaire_forms``.**
    ``records._design_workshop_media_ids`` follows the VIEWER clause on its own written instruction
    ("so the day that widens again the audio widens with it"), and
    ``questionnaire_forms._visible_questionnaire_where`` writes that relation filter by hand.
    Teaching either of them about this clause hands an inspector the artisan's recorded voice and
    the respondents' answers — the one thing this scope is built not to carry.
    """
    return {"inspectors": {"some": {"userId": user_id}}}


async def load_inspectable_workshop_or_404(workshop_id: str, user: Any) -> Any:
    """The workshop an inspector may READ, or 404. **THE READ-ONLY LOADER.**

    Deliberately NOT ``design_workshops.load_workshop_or_404``, and deliberately not a call into
    it: that helper takes ``for_edit`` and is what fourteen write routes pair with
    ``_require_designer`` (counted at the top of this module, where the fourteen are named).
    **This one has no ``for_edit`` parameter and must never grow one.** That
    absence is the whole enforcement — there is no argument an inspector's request could carry that
    turns this read into a write, because there is no such argument.

    404 AND NOT 403 for a workshop out of scope, matching every other loader here: a 403 would
    confirm the id exists to exactly the people this is turning away, which for a research data set
    keyed by cuid is a small but free leak. The detail string is the same "Record not found" the
    sibling uses, so an inspector, a designer and a stranger cannot tell each other's refusals
    apart.

    A SOFT-DELETED WORKSHOP IS A 404 HERE, WITH NO 409 ARM. The sibling answers 409 to an editor so
    that a designer holding unsent stages is told to ask an admin to restore it — advice that
    presumes there is something to save. An inspector has nothing pending and no restore button, so
    the honest answer is that there is nothing to inspect.

    THE ROLE IS RE-CHECKED HERE even though the routes already stand behind ``require_inspector``.
    Belt and braces on purpose: this is the function a future caller will reach for, and a loader
    that trusts its caller's gate is how a scope leaks onto a surface nobody re-read.
    """
    if not is_inspector(user):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    if record is None or record.deletedAt is not None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if not await has_inspection_scope(workshop_id, getattr(user, "id", "")):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record


# --------------------------------------------------------------------------------------
# Reading: the two lists the admin screen renders
# --------------------------------------------------------------------------------------


def inspector_payload(row: Any) -> dict[str, Any]:
    """One inspection row as the admin screen reads it.

    ``name``/``email``/``role`` travel WITH the row rather than being joined against a directory the
    screen also holds — the sibling's reasoning, unchanged: an inspector whose account has since
    been suspended is precisely the row an admin most needs to see and act on, and a join against
    the eligible list would render it as a bare cuid.

    ``assignedAt`` and not ``grantedAt``: nothing was granted to anybody. An admin assigned an
    examiner to a piece of work, which is why the column beside it is ``assignedById``.
    """
    user = getattr(row, "user", None)
    return {
        "userId": row.userId,
        "name": getattr(user, "name", "") or "",
        "email": getattr(user, "email", "") or "",
        "role": _role(user),
        "assignedAt": row.createdAt.isoformat() if getattr(row, "createdAt", None) else None,
    }


async def inspector_rows(workshop_id: str) -> list[dict[str, Any]]:
    """Every account assigned to inspect this workshop, oldest assignment first.

    An empty list means NOBODY IS INSPECTING THIS WORKSHOP, and unlike the viewers list that is the
    literal truth rather than a half-answer — there is no creator quietly holding the access off to
    one side. A screen over this may say "not under inspection" and be right.
    """
    rows = await db.designworkshopinspector.find_many(
        where={"designWorkshopId": workshop_id},
        include={"user": True},
        order={"createdAt": "asc"},
    )
    return [inspector_payload(row) for row in rows]


async def eligible_inspectors(search: str | None = None) -> dict[str, Any]:
    """The accounts that may be assigned an inspection at all.

    ONE ROLE AND ONE ROSTER, which is the whole difference from ``eligible_viewers``. That function
    reads two rosters folded in opposite directions because it offers DESIGNERs, whose empanelment
    gates their sign-in. An inspector is not empanelled to run anything, so ``DesignerRoster`` is
    not consulted — requiring a row there would refuse every inspector there will ever be.

    THE PLATFORM ALLOW-LIST STILL APPLIES, because it gates every role: an account the allow-list
    has REJECTED or SUSPENDED cannot sign in, so offering it here would mean an admin assigning an
    inspection that the next sign-in refuses, with nothing on screen saying why. It is read as a CUT
    LIST and never as a guest list — see ``access_roster.barred_emails`` for why requiring admission
    would hide people the sign-in path self-heals.

    **THE TWO ``OR``S ARE AND-COMPOSED, NEVER ASSIGNED TO THE SAME KEY.** The eligibility clause and
    the search clause both want ``where["OR"]``, and the later assignment silently wins; if that is
    the search, the ROLE clause is gone and this picker offers every account in the repository.

    ``search`` is applied by the SERVER inside the same query as the eligibility rule, for the
    reason the sibling was fixed for twice: filtering after the ``take`` searches only the part of
    the alphabet that fitted, so the parameter added to reach past the ceiling would stop at exactly
    the ceiling. ``truncated`` says the list was cut; a client that shows a cut list without saying
    so is this repository's most repeated bug class.
    """
    barred = await access_roster.barred_emails()

    clauses: list[dict[str, Any]] = [{"role": {"in": sorted(INSPECTION_ROLES)}}]
    if barred:
        # THE BREAK-GLASS, SPELLED HERE BECAUSE A ``WHERE`` CANNOT CALL A PYTHON FUNCTION. Kept in
        # step with ``deps.is_break_glass_master`` BY HAND, and with BOTH of its arms: the role, and
        # the configured address, which exists for the deployment where the row carrying the role
        # has not been seeded or somebody has demoted it. Spelling only the role half is exactly how
        # the sibling's copy came to be silently narrowed.
        #
        # Only when the setting is actually set. An empty configured address compared against a
        # ``User.email`` that some row holds empty would exempt an account nobody chose.
        #
        # The master admin is not in ``INSPECTION_ROLES`` today, so this arm is unreachable through
        # the clause above. It is written anyway: an exemption that is missing on the day the role
        # set changes is worse than one that is inert.
        #
        # ``mode: "insensitive"`` because ``barred`` is lower-cased and ``User.email`` is not, so a
        # case-sensitive NOT-IN would quietly fail to exclude an account stored shouting — the one
        # direction this clause must never fail in.
        configured = (get_settings().master_admin_email or "").strip().lower()
        exemptions: list[dict[str, Any]] = [{"role": "MASTER_ADMIN"}]
        if configured:
            exemptions.append({"email": {"equals": configured, "mode": "insensitive"}})
        clauses.append({"OR": [*exemptions, {"email": {"not_in": barred, "mode": "insensitive"}}]})

    term = (search or "").strip()
    if term:
        # Through ``records.contains``, which strips the control bytes a ``text`` comparison cannot
        # hold: ``?search=%00`` would otherwise be a 500 raised from a query parameter.
        clauses.append({"OR": [{"name": contains(term)}, {"email": contains(term)}]})

    users = await db.user.find_many(
        where={"AND": clauses},
        # NAME THEN ID, so the sort key is TOTAL. Without the tiebreaker, which accounts fall inside
        # the ceiling is Postgres's choice and can differ between two identical requests — "who is
        # hidden" would change on refresh. The sibling pins both halves for the same reason.
        order=[{"name": "asc"}, {"id": "asc"}],
        take=ELIGIBLE_INSPECTOR_LIMIT + 1,
    )
    truncated = len(users) > ELIGIBLE_INSPECTOR_LIMIT
    users = users[:ELIGIBLE_INSPECTOR_LIMIT]
    if truncated:
        # Logged as well as reported: the log names the term that was too broad, which the response
        # cannot, and it is what an operator reads when an admin says "I cannot find her".
        logger.warning(
            "eligible-inspectors hit its ceiling of %s accounts (search=%r); the answer is "
            "truncated and says so, and the caller can narrow it",
            ELIGIBLE_INSPECTOR_LIMIT,
            term,
        )
    return {
        "users": [{"id": u.id, "name": u.name, "email": u.email, "role": _role(u)} for u in users],
        "truncated": truncated,
    }


# --------------------------------------------------------------------------------------
# Writing: validate everything, then replace the whole set
# --------------------------------------------------------------------------------------


async def replace_inspectors(
    workshop_id: str, user_ids: list[str], *, assigned_by_id: str
) -> list[dict[str, Any]]:
    """Make the inspection set for this workshop exactly ``user_ids``, and answer with it.

    ADMIN ONLY, enforced by the route. The inspected must not choose the inspector — see the module
    docstring for why that is the whole value of the tier and not a workflow preference.

    VALIDATION RUNS TO COMPLETION BEFORE ANY WRITE. One bad id refuses the whole call rather than
    applying the good half: an admin who named two inspectors and is shown one has been told nothing
    about which failed or why, and a partially applied access change is the worst of both — it looks
    like it worked.

    Idempotent by construction. Only the difference is written, so re-saving an unchanged screen
    touches no rows and does not restamp ``createdAt`` — which matters because ``assignedAt`` is the
    only answer anybody has to "how long has this workshop been under inspection".

    REMOVING AN INSPECTOR DELETES THE ROW rather than revoking it, matching ``DesignWorkshopViewer``
    and for the sharper version of its reason: this row carries no decision to audit, because nobody
    ever asked for it and nobody was ever refused. A tombstone would record only that an admin
    changed their mind about who should examine a piece of work.
    """
    wanted = _deduplicate(user_ids)
    await _assert_every_id_may_inspect(workshop_id, wanted)

    existing = await db.designworkshopinspector.find_many(where={"designWorkshopId": workshop_id})
    held = {row.userId for row in existing}

    removed = sorted(held - wanted)
    added = sorted(wanted - held)

    if removed:
        # DELETED, not revoked — see the docstring. There is no decision here to audit.
        await db.designworkshopinspector.delete_many(
            where={"designWorkshopId": workshop_id, "userId": {"in": removed}}
        )
    if added:
        await db.designworkshopinspector.create_many(
            data=[
                {"designWorkshopId": workshop_id, "userId": uid, "assignedById": assigned_by_id}
                for uid in added
            ],
            # Two admins saving the same screen at the same moment must not turn into a 500 on a
            # duplicate key. The pair is the primary key, so "already assigned" is the intended
            # outcome of this call anyway.
            skip_duplicates=True,
        )
    return await inspector_rows(workshop_id)


def _deduplicate(user_ids: list[str]) -> set[str]:
    """The intended set, with blanks dropped — AND NOTHING ELSE DROPPED.

    Deliberately narrower than ``design_workshop_viewers._deduplicate``, which also removes the
    workshop's creator so that a screen rendering the creator alongside the viewers can post the lot
    back harmlessly. That silence is right there and wrong here: naming the creator as an inspector
    asks for a designer to inspect their own work, which is a MISTAKE an admin needs to be told
    about rather than a no-op. It is refused by name in :func:`_assert_every_id_may_inspect`.
    """
    return {uid.strip() for uid in user_ids if uid and uid.strip()}


#: Characters that cannot reach Postgres inside an id, and that no id this repository issues holds.
#:
#: NUL is refused by a ``text`` comparison outright (SQLSTATE 22021), and a LONE SURROGATE — half an
#: emoji from a phone that truncated it — cannot be encoded to UTF-8 at all, so it fails inside the
#: driver before Postgres is even reached. Either one turns the ``find_many`` below into a bare 500
#: whose body names the exception class and whose log carries a stack trace for every attempt, where
#: the honest answer is the "no account exists with this id" every other unmatchable id already
#: gets.
#:
#: Copied from ``design_workshop_viewers`` rather than imported: reaching across two access modules
#: for a private name to save four lines couples the refusal wording of one screen to the other's.
#: The behaviour is asserted here in this module's own tests.
_UNSTORABLE_IN_AN_ID = re.compile(r"[\x00-\x1f\x7f\ud800-\udfff]")


def _displayable(user_id: str) -> str:
    """An id safe to put in a refusal message, and in whatever reads the log after it.

    Only ever applied to an id that is ALREADY being refused, so nothing downstream depends on it
    round-tripping; what it prevents is a raw NUL or half a surrogate pair travelling out in the
    response body and into an operator's log on its way.
    """
    return _UNSTORABLE_IN_AN_ID.sub("", user_id)


async def _assert_every_id_may_inspect(workshop_id: str, user_ids: set[str]) -> None:
    """422 naming the offending account, never a silent skip.

    FOUR REFUSALS, and the last one is this module's own rather than the sibling's:

    1. **No such account.** Asked before anything else, and ids holding unstorable characters are
       held back from the query rather than crashing it — they cannot appear in ``by_id``, so they
       fall into this same refusal through one message and one code path.
    2. **Wrong role.** Not in :data:`INSPECTION_ROLES`. The sentence names what the account IS,
       whose only remedy is picking somebody else, so nothing stacks after it.
    3. **Barred by the platform allow-list.** An account that cannot sign in at all. Asked of
       EXACTLY the addresses named here rather than of the capped ``barred_emails`` read, because a
       refusal has to be able to promise it is complete and that one has a ceiling.
    4. **ALREADY ON THIS WORKSHOP** — the creator, or the holder of a ``DesignWorkshopViewer`` row.
       **This refusal exists nowhere else in the codebase and it is the point of the tier.** An
       independent review by somebody who worked on the thing is not a review. Today the two role
       sets are disjoint so this is nearly unreachable, but "nearly" is doing real work: a DESIGNER
       holding a viewer row who is later promoted to INSPECTOR becomes eligible for exactly this,
       and nothing else in the codebase would notice.

    Branches 3 and 4 STACK — an independent ``if`` each, never an ``elif`` — because they name
    different remedies on different screens, and an admin told only about the first will fix it,
    save again, and only then learn about the second. Branch 2 does not stack, for the reason above.
    """
    if not user_ids:
        return

    # ASKED BEFORE THE QUERY, because these ids are what makes the query itself fail — see
    # ``_UNSTORABLE_IN_AN_ID``. Holding them back is not a silent skip: they cannot appear in
    # ``by_id``, so they fall into the same "no account exists" refusal as any other id with nothing
    # behind it, through one message and one code path.
    lookup = sorted(uid for uid in user_ids if not _UNSTORABLE_IN_AN_ID.search(uid))
    users = await db.user.find_many(where={"id": {"in": lookup}}) if lookup else []
    by_id = {u.id: u for u in users}

    unknown = sorted(_displayable(uid) for uid in user_ids if uid not in by_id)
    if unknown:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "No account exists with "
                + ("these ids: " if len(unknown) > 1 else "this id: ")
                + ", ".join(unknown)
                + ". Nothing was changed."
            ),
        )

    barred = await access_roster.barred_among([u.email for u in users])
    on_the_workshop = await _accounts_already_on_the_workshop(workshop_id, set(by_id))

    refusals: list[str] = []
    for uid in sorted(user_ids):
        user = by_id[uid]
        role = _role(user)
        if role not in INSPECTION_ROLES:
            refusals.append(
                f"{user.name} ({user.email}) is a {role}, and only the Inspector / Reviewer tier "
                f"can be assigned an inspection. An inspection is READ-ONLY by construction, so a "
                f"row here would give this account nothing that its own role does not already "
                f"decide."
            )
            # AND NOTHING FURTHER ABOUT THIS ACCOUNT, unlike the two branches below, which stack.
            # Those name a state an administrator can change, so an admin deserves the whole list
            # before they walk to another screen. This one names what the account IS; appending "and
            # they are also suspended" to a designer who can never hold an inspection is a second
            # errand attached to the one refusal whose only remedy is picking somebody else.
            continue
        if not is_break_glass_master(user) and _normalised(user.email) in barred:
            refusals.append(
                f"{user.name} ({user.email}) is barred by the platform access list, so they cannot "
                f"sign in at all. Clear that on the access screen first; an inspection row on its "
                f"own would leave this screen saying they are inspecting while they are shown a "
                f"refusal at the door."
            )
        if uid in on_the_workshop:
            refusals.append(
                f"{user.name} ({user.email}) is already on this workshop as its creator or a "
                f"co-designer, so they cannot be its inspector — an independent review by somebody "
                f"who worked on it is not a review. Take them off the workshop's viewers first if "
                f"they have genuinely moved from running it to inspecting it."
            )

    if refusals:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=" ".join(refusals) + " Nothing was changed.",
        )


def _normalised(email: Any) -> str:
    """Lower-cased and stripped, matching what ``access_roster`` answers with.

    Spelled here rather than importing ``designers.normalise_email``, because that module is the
    DESIGNER empanelment roster and this one deliberately does not depend on it — see the module
    docstring. Four characters of duplication is cheaper than an import that suggests an inspector
    has an empanelment.
    """
    return str(email or "").strip().lower()


async def _accounts_already_on_the_workshop(workshop_id: str, candidates: set[str]) -> set[str]:
    """Which of ``candidates`` already hold this workshop as its creator or as a viewer.

    ONE QUERY EACH, and only over the handful of ids actually named in the PUT — never a scan of
    either table. The creator is read off the workshop row; the viewers are asked for by id, so the
    answer is exact rather than capped, which is what lets the refusal above promise it is complete.

    Returns a SET so the caller can stack this refusal with the allow-list one. An empty set is the
    ordinary answer and costs two indexed lookups.
    """
    if not candidates:
        return set()
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    creator = {getattr(record, "createdById", None)} & candidates if record else set()
    viewers = await db.designworkshopviewer.find_many(
        where={"designWorkshopId": workshop_id, "userId": {"in": sorted(candidates)}}
    )
    return {uid for uid in creator if uid} | {row.userId for row in viewers}


# --------------------------------------------------------------------------------------
# The refusal everybody else gets on the inspector's own surface
# --------------------------------------------------------------------------------------


#: What an account is told when it reaches the inspector's read surface without the tier.
#:
#: A SENTENCE THAT NAMES THE OTHER DOOR, which is the whole reason this is a constant rather than an
#: inline string: an ADMIN hits this refusal too, and an admin told only "forbidden" on a READ
#: surface will reasonably conclude the deployment is broken. It is not — they read every workshop
#: through ``/api/design-workshops`` already, and this surface is scoped by inspection rows they do
#: not and should not hold. Naming the door they want costs one clause and saves a support call.
NOT_AN_INSPECTOR_DETAIL = (
    "The inspection surface belongs to the Inspector / Reviewer tier. Designers and admins read "
    "design & prototype workshops through /api/design-workshops instead; an admin can see who is "
    "inspecting a workshop at /api/design-workshop-inspections/{id}/inspectors."
)


def assert_inspection_surface(user: Any) -> None:
    """403 for anybody who is not an inspector, INCLUDING ADMINS, and that is deliberate.

    Admitting admins here would mean one of two things and both are worse than a refusal. Scoped by
    THEIR OWN inspection rows, an admin sees an empty list and reads it as a broken feature. Scoped
    by "everything, because they are an admin", this surface silently becomes a second full read of
    every workshop in the repository — a second place to look when somebody has access they should
    not, which is precisely what ``services/design_workshop_access``'s header refuses.

    So the answer is a 403 that names the door they want. Nothing here branches on ``is_admin``, and
    nothing here should: the refusal is identical for an admin, a designer and a volunteer, which is
    what makes it one sentence to reason about rather than three.
    """
    if is_inspector(user):
        return
    raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=NOT_AN_INSPECTOR_DETAIL)


# THE INVARIANT, CHECKED AT IMPORT RATHER THAN HOPED FOR. If these two sets ever overlap, one
# account becomes eligible to hold BOTH a viewer row (which carries stage WRITES: the loader admits
# a grantee and ``for_edit=True`` adds no role check of its own — and note that since 2026-09-03 the
# loader honours that grant only for an account inside DESIGN_WORKSHOP_ROLES, which is to say only
# for exactly the accounts this line is keeping out of INSPECTION_ROLES) and an inspection row
# (read-only) on the same workshop — the contradiction this whole module is built to prevent. That
# role clause is a SECOND line and not a replacement for this one: it is true only while these two
# sets are disjoint, which is the thing being asserted here. Failing at import
# is the right blast radius: the API does not boot, rather than booting with a scope that means two
# things. ``tests/test_dw_inspector_scope_gate.py`` asserts it again where a reader will find it.
_OVERLAP = INSPECTION_ROLES & DESIGN_WORKSHOP_ROLES
if _OVERLAP:  # pragma: no cover - a configuration error, not a runtime state
    raise RuntimeError(
        "INSPECTION_ROLES and deps.DESIGN_WORKSHOP_ROLES must stay disjoint; they overlap on "
        f"{sorted(_OVERLAP)}. See the header of app/services/design_workshop_inspectors.py."
    )
