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
PROFESSOR, at rank 40, cannot open a design workshop today: ``deps.DESIGN_WORKSHOP_ROLES`` is the
designer, the three directorate tiers and the two admin tiers — a set PROFESSOR and INSPECTOR are
both outside — ``_require_designer`` stands in front of eighteen routes, and
``load_ratable_workshop_or_404`` 404s anybody outside the set before it looks at anything.

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
hazard exactly where it was.

**AND SINCE 2026-10-09 THE SEPARATION IS PER WORKSHOP, NOT PER ROLE SET.** An import-time check used
to refuse to boot if ``INSPECTION_ROLES`` ever overlapped ``DESIGN_WORKSHOP_ROLES``, which made "an
inspector is never a designer" true by keeping the two populations apart. The owner's ruling that
MINISTRY_ADMIN, ADMIN and MASTER_ADMIN may be APPOINTED inspector of a workshop ends that: those three
are designers by role and inspectors by appointment. The property survives as rules about ONE
workshop, written once in ``services/design_workshop_posts``: nobody inspects a workshop they
authored, nobody both supervises and inspects one, a viewer row is refused to anybody inspecting it,
and ``load_workshop_or_404(for_edit=True)`` refuses every write by somebody holding an inspection row
on that workshop — through the admin arm too. ``for_edit=True`` still grants writes to whoever it
admits, which is exactly why that refusal sits inside it.

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

So this scope's predicate is **never** added to it as a way IN. An inspector reads through
:func:`load_inspectable_workshop_or_404` in this module, which is called from
``api/routes/design_workshop_inspections.py`` and nowhere else, and which returns a workshop for
READ. **It has no ``for_edit`` parameter, and adding one is the single change this file refuses.**
The one place an inspection row meets a write is as a way OUT: :func:`inspection_holders_among`
tells ``load_workshop_or_404(for_edit=True)`` to REFUSE an inspector who could otherwise write (an
administrator serving as one), and that function answers who is inspecting and grants nothing.

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

**THE OVERSIGHT ASSIGNERS ONLY** — ``require_workshop_assigner`` = ``OVERSIGHT_ASSIGNER_ROLES`` =
``{MINISTRY_ADMIN, ADMIN, MASTER_ADMIN}`` — and the argument is stronger here than the one that
makes the viewers screen admin-only. That module's reason is handover — an owner who chooses their
own readers leaves their workshop's access frozen when they go. This module's reason is the point of
the tier:

    **THE INSPECTED MUST NOT CHOOSE THE INSPECTOR.** If a designer could put somebody on their own
    workshop as its inspector, or take somebody off it, the inspection is worth nothing. That is not
    a workflow preference; it is the entire value of an independent review, and it is why
    ``replace_inspectors`` is reached only through ``Depends(require_workshop_assigner)`` and why
    the workshop's creator gets no say at all — not even a "suggest an inspector" route.

⚠ **THE GATE WAS ``Depends(require_admin)`` UNTIL 0.0.12 AND THIS MODULE WENT ON SAYING SO IN FOUR
PLACES.** The three administration routes moved to ``require_workshop_assigner`` on the owner's
ruling; the argument for the move is written out in full at
``api/routes/design_workshop_inspections.py`` under "THE TWO DOORS" and is not restated here. A
REGIONAL_DIRECTOR is still refused outright, because ``OVERSIGHT_ASSIGNER_ROLES`` excludes them for
the same reason one rung up — the supervised must not choose the supervisor.

**WHO MAY BE APPOINTED WIDENED ON 2026-10-09, AND WHO MAY APPOINT DID NOT.** The INSPECTOR tier still
holds inspections, and now so may a MINISTRY_ADMIN, an ADMIN and the MASTER_ADMIN, by appointment to
one workshop at a time (:data:`INSPECTION_HOLDER_ROLES`). An appointer is never their own appointee —
naming yourself is refused — and the rules that keep an inspection independent are about the
workshop rather than the role: see ``services/design_workshop_posts``.

The stale sentences were worth correcting rather than leaving as a nit, because this is the module
somebody opens to AUDIT that gate. Read as written, an auditor either signs off on a widening they
never actually reviewed, or "restores" ``require_admin`` on the routes to match the documentation —
silently taking the inspector panel on /officers away from the tier the owner had just given it to.

Two refusals follow from it, and both are enforced rather than documented:

1. An account outside ``OVERSIGHT_ASSIGNER_ROLES`` calling the administration routes gets a 403 from
   ``require_workshop_assigner``.
2. **An account that authored the workshop cannot inspect it.** Anybody holding a
   ``DesignWorkshopViewer`` row for the same workshop, or who has written its stages, is refused by
   name with a 409 — see :func:`_assert_every_id_may_inspect`. **The creator is NOT refused for being
   the creator** (until 2026-10-09 they were): administrators and sanctioning officers open
   workshops as an administrative act, so ``createdById`` says nothing about who did the work.

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
  a harmless no-op, because they already hold the access being granted. Here the creator is an
  ordinary candidate: opening a workshop is administration, so an administrator who opened one and
  wrote none of it may inspect it, and one who did write it is refused by name for THAT.

Current as of 2026-08-27. Re-check the claims about the write path with::

    grep -n "for_edit" backend/app/services/design_workshops.py
    grep -rnE "has_viewer_grant|visible_to_clause" backend/app
"""

import logging
import re
from collections.abc import Iterable
from typing import Any

from fastapi import HTTPException, status

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import is_break_glass_master, role_value
from app.services import access_roster, design_workshop_posts as posts
from app.services.concurrency import gather_reads
from app.services.records import contains

logger = logging.getLogger(__name__)


#: THE TIER WHOSE JOB IS INSPECTING. A SET, not a rank floor.
#:
#: A frozenset of ONE, and that is the shape rather than an oversight. Every design-workshop gate in
#: this product is set membership — ``DESIGN_WORKSHOP_ROLES``, ``can_run_design_workshops`` — and a
#: rank floor written here would mean "INSPECTOR and everything above it", which is PROFESSOR, ADMIN
#: and MASTER_ADMIN. A floor would silently answer a product question nobody asked.
#:
#: **IT IS NOT WHO MAY HOLD AN INSPECTION ANY MORE** — that is :data:`INSPECTION_HOLDER_ROLES`, below,
#: since 2026-10-09. This set stays the tier: the role an inspection is the whole job of, and the one
#: the web and Android clients mirror for that tier's own screens.
#:
#: **PROFESSOR IS IN NEITHER SET.** A professor cannot open a design workshop today, and giving them a
#: door through this table would be a new product decision wearing an implementation detail.
#:
#: STILL DISJOINT FROM ``DESIGN_WORKSHOP_ROLES`` — the inspector tier writes no workshop by role —
#: but that is no longer what keeps an inspector from writing what they inspect. That is per
#: workshop now, in ``services/design_workshop_posts``, because the holder set below overlaps the
#: designer set on purpose.
INSPECTION_ROLES = frozenset({"INSPECTOR"})

#: WHO MAY HOLD A ``DesignWorkshopInspector`` ROW: the tier above, plus the three administrator tiers
#: by appointment (owner's decision, 2026-10-09).
#:
#: An administrator holds an inspection the way an inspector does — a row on ONE workshop, made by
#: somebody else — and while they hold it they read that workshop through this module's loader and
#: cannot write it by any route, the admin arm of ``load_workshop_or_404`` included. The rules that
#: keep the inspection independent (nobody inspects what they authored, or what they also supervise,
#: or by naming themselves) are in ``services/design_workshop_posts``.
INSPECTION_HOLDER_ROLES = INSPECTION_ROLES | posts.SERVING_ADMIN_ROLES

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
    """Is this account the inspector TIER?

    SET MEMBERSHIP, deliberately, for the reason the module docstring gives at length. Written
    against the string rather than against ``ROLE_RANK`` so that this module is correct on a
    deployment where the tier has not been added to the ladder yet: it simply answers False for
    everybody, which is the fail-closed direction.

    Not the question the surface asks any more — that is :func:`may_hold_an_inspection`.
    """
    return _role(user) in INSPECTION_ROLES


def may_hold_an_inspection(user: Any) -> bool:
    """May this account's role hold an inspection row — the tier, or an administrator serving as one?

    THE ROLE FIRST, THE ROW SECOND, everywhere this scope is read: a row whose holder's role has since
    moved outside :data:`INSPECTION_HOLDER_ROLES` is honoured nowhere, the same fail-closed rule
    ``load_workshop_or_404`` applies to a viewer row.
    """
    return _role(user) in INSPECTION_HOLDER_ROLES


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
    that trusts its caller's gate is how a scope leaks onto a surface nobody re-read. Since
    2026-10-09 the role is any of :data:`INSPECTION_HOLDER_ROLES`, and the ROW is what decides which
    workshops: an administrator reads here exactly the workshops they were appointed to inspect.
    """
    if not may_hold_an_inspection(user):
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


async def eligible_inspectors(
    search: str | None = None, *, exclude_user_id: str | None = None
) -> dict[str, Any]:
    """The accounts that may be assigned an inspection at all.

    ONE ROLE SET AND ONE ROSTER, which is the whole difference from ``eligible_viewers``. That
    function reads two rosters folded in opposite directions because it offers DESIGNERs, whose
    empanelment gates their sign-in. An inspector is not empanelled to run anything, so
    ``DesignerRoster`` is not consulted — requiring a row there would refuse every inspector there
    will ever be.

    THE ROLE SET IS :data:`INSPECTION_HOLDER_ROLES` since 2026-10-09: the inspector tier and the three
    administrator tiers, offered side by side because the write accepts them side by side. A picker
    that offered fewer than the write takes is the defect ``eligible_viewers`` was fixed for.

    ``exclude_user_id`` IS THE PERSON DOING THE APPOINTING, left out because the write refuses anybody
    naming themselves — a picker offering a row the save will refuse teaches people to distrust it.
    The ministry dashboard's directory read passes nothing: nobody is appointing there.

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

    clauses: list[dict[str, Any]] = [{"role": {"in": sorted(INSPECTION_HOLDER_ROLES)}}]
    if exclude_user_id:
        clauses.append({"id": {"not": exclude_user_id}})
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
        # REACHABLE since 2026-10-09: the master admin may hold an inspection, so a suspended
        # allow-list row must not take the break-glass account off this picker.
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

    THE OVERSIGHT ASSIGNERS ONLY (``require_workshop_assigner`` =
    ``{MINISTRY_ADMIN, ADMIN, MASTER_ADMIN}``), enforced by the route — it was ``require_admin``
    until 0.0.12. The inspected must not choose the inspector — see the module docstring for why
    that is the whole value of the tier and not a workflow preference, and why widening the gate to
    the tier that appoints a workshop's designer and its two officers did not weaken it.

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

    THE PRESENT SET IS READ BEFORE VALIDATING, so that naming yourself is refused only as an ACT:
    an administrator another administrator appointed may re-save the panel with themselves still on
    it, and may not add themselves to it.

    **NOR TAKE THEMSELVES OFF IT (2026-10-09).** A save that would delete the caller's own row is
    refused whole, 409 (``posts.self_release_refusal``), before anything is validated or written:
    an inspector who could release themselves could then write the workshop their post forbids them
    to, and the deleted row would be the only record they ever held it. Another assigner removing
    them is the ordinary save, and works.
    """
    wanted = _deduplicate(user_ids)

    existing = await db.designworkshopinspector.find_many(where={"designWorkshopId": workshop_id})
    held = {row.userId for row in existing}
    removed = sorted(held - wanted)
    added = sorted(wanted - held)
    if assigned_by_id in removed:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=posts.self_release_refusal({posts.INSPECTOR}),
        )
    await _assert_every_id_may_inspect(
        workshop_id,
        wanted,
        appointing=assigned_by_id if assigned_by_id not in held else None,
    )

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
    back harmlessly. Here the creator is a candidate like anybody else and goes through
    :func:`_assert_every_id_may_inspect`, which refuses them only if they AUTHORED the workshop.
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


async def _assert_every_id_may_inspect(
    workshop_id: str, user_ids: set[str], *, appointing: str | None = None
) -> None:
    """Refuse the whole set, naming every offending account, never a silent skip.

    FOUR REFUSALS:

    1. **No such account.** 422, asked before anything else, and ids holding unstorable characters
       are held back from the query rather than crashing it — they cannot appear in ``by_id``, so
       they fall into this same refusal through one message and one code path.
    2. **Wrong role.** 422. Not in :data:`INSPECTION_HOLDER_ROLES`. The sentence names what the
       account IS, whose only remedy is picking somebody else, so nothing stacks after it.
    3. **Barred by the platform allow-list.** 422. An account that cannot sign in at all. Asked of
       EXACTLY the addresses named here rather than of the capped ``barred_emails`` read, because a
       refusal has to be able to promise it is complete and that one has a ceiling.
    4. **THE WORKSHOP'S OWN SEPARATION OF DUTIES**, 409, from ``services/design_workshop_posts``:
       somebody who AUTHORED this workshop (holds a viewer row on it or wrote its stages — never
       merely created it), who is its Assistant or Regional Director, or who is ``appointing``
       themselves. An independent review by somebody who worked on the thing, or who supervises it,
       is not a review.

    ``appointing`` is the account making the request, passed only when it is not already on the
    panel: naming yourself is refused as an act, not as a state somebody else put you in.

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

    barred, authored, held = await gather_reads(
        access_roster.barred_among([u.email for u in users]),
        posts.authorship_among(workshop_id, by_id),
        posts.supervisory_posts_among(workshop_id, by_id),
    )

    refusals: list[str] = []
    separation: list[str] = []
    for uid in sorted(user_ids):
        user = by_id[uid]
        role = _role(user)
        if role not in INSPECTION_HOLDER_ROLES:
            refusals.append(
                f"{user.name} ({user.email}) is a {role}, and only an Inspector / Reviewer, a "
                f"Ministry Admin, an admin or the master admin can be assigned an inspection."
            )
            # AND NOTHING FURTHER ABOUT THIS ACCOUNT, unlike the branches below, which stack. Those
            # name a state an administrator can change, so an admin deserves the whole list before
            # they walk to another screen. This one names what the account IS; appending "and they
            # are also suspended" to a designer who can never hold an inspection is a second errand
            # attached to the one refusal whose only remedy is picking somebody else.
            continue
        if not is_break_glass_master(user) and _normalised(user.email) in barred:
            refusals.append(
                f"{user.name} ({user.email}) is barred by the platform access list, so they cannot "
                f"sign in at all. Restore their access on the access screen first."
            )
        separation.extend(
            posts.separation_refusals(
                person=f"{user.name} ({user.email})",
                post=posts.INSPECTOR,
                standing=posts.Standing(
                    posts=held.get(uid, frozenset()), authored=authored.get(uid, frozenset())
                ),
                self_appointed=uid == appointing,
            )
        )

    posts.raise_refusals(refusals, separation)


def _normalised(email: Any) -> str:
    """Lower-cased and stripped, matching what ``access_roster`` answers with.

    Spelled here rather than importing ``designers.normalise_email``, because that module is the
    DESIGNER empanelment roster and this one deliberately does not depend on it — see the module
    docstring. Four characters of duplication is cheaper than an import that suggests an inspector
    has an empanelment.
    """
    return str(email or "").strip().lower()


async def inspection_holders_among(workshop_id: str, user_ids: Iterable[str]) -> set[str]:
    """Which of these accounts hold an inspection row on this workshop. One indexed read.

    ⚠ **A REFUSAL INPUT AND NEVER A GRANT.** It is what ``services/design_workshop_posts`` asks when
    it has to know who is inspecting a workshop in order to say NO — to a write
    (``load_workshop_or_404(for_edit=True)``), to a viewer row, or to a second post on the same
    workshop. It answers about the accounts it is handed and decides nothing. The module header's
    rule stands beside it unchanged: nothing outside this feature reads this table to let anybody IN,
    and the sweep in ``tests/test_dw_inspector_scope_gate.py`` is why the table is read here and not
    there.
    """
    ids = sorted({uid for uid in user_ids if uid and not _UNSTORABLE_IN_AN_ID.search(uid)})
    if not workshop_id or not ids:
        return set()
    rows = await db.designworkshopinspector.find_many(
        where={"designWorkshopId": workshop_id, "userId": {"in": ids}}
    )
    return {row.userId for row in rows}


async def inspected_workshop_ids(user_id: str) -> set[str]:
    """Every workshop this account holds an inspection row on. One indexed read (``userId``).

    ⚠ **A REFUSAL INPUT AND NEVER A GRANT**, exactly as :func:`inspection_holders_among` beside it.
    ``design_workshop_posts.supervisory_workshops_of`` asks it so that a write over the rows of many
    workshops at once — the unfiled records' bulk map — can leave out the rows of the workshops this
    account inspects and so may not write. It answers about the account it is handed and decides
    nothing; nothing reads it to let anybody IN.
    """
    if not user_id or _UNSTORABLE_IN_AN_ID.search(user_id):
        return set()
    rows = await db.designworkshopinspector.find_many(where={"userId": user_id})
    return {row.designWorkshopId for row in rows}


# --------------------------------------------------------------------------------------
# The refusal everybody else gets on the inspector's own surface
# --------------------------------------------------------------------------------------


#: What an account is told when it reaches the inspector's read surface with a role that can hold no
#: inspection.
#:
#: A SENTENCE THAT NAMES THE OTHER DOOR, which is the whole reason this is a constant rather than an
#: inline string: a designer told only "forbidden" on a READ surface will reasonably conclude the
#: deployment is broken. Naming the door they want costs one clause and saves a support call.
NOT_AN_INSPECTOR_DETAIL = (
    "This page is for inspectors. Sign out and back in, or ask an administrator."
)


def assert_inspection_surface(user: Any) -> None:
    """403 for anybody whose role can hold no inspection; everybody else is scoped by their ROWS.

    ADMINS WERE REFUSED HERE UNTIL 2026-10-09, on the argument that an admin scoped by their own
    inspection rows would see an empty list and read it as a broken feature. The owner's ruling
    answered that argument: an administrator may now HOLD inspection rows, by appointment, so their
    list is the workshops they were appointed to inspect — empty until somebody appoints them, which
    is the truth rather than a defect. What this surface still must never become is a second full
    read of every workshop "because they are an admin", and it does not: nothing here or in
    :func:`load_inspectable_workshop_or_404` branches on ``is_admin``. The row decides, for an admin
    exactly as for an inspector.
    """
    if may_hold_an_inspection(user):
        return
    raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=NOT_AN_INSPECTOR_DETAIL)


# THE IMPORT-TIME DISJOINTNESS CHECK THAT STOOD HERE IS GONE (2026-10-09), and deliberately. It
# refused to boot if ``INSPECTION_ROLES`` ever overlapped ``deps.DESIGN_WORKSHOP_ROLES``, which kept
# "an inspector never writes what they inspect" true by keeping the two POPULATIONS apart. The owner's
# ruling puts the three administrator tiers in both on purpose — designers by role, inspectors by
# appointment — so the property is now enforced per WORKSHOP, where it was always meant: see
# ``services/design_workshop_posts`` for the rules and the 403 inside ``load_workshop_or_404``.
