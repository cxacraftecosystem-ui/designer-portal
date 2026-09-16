"""**THE SIXTH SCOPE: which design & prototype workshops one OFFICER may READ, and read only.**

An OFFICER here is one of three ministry posts that landed on the role ladder on 2026-09-13:
ASSISTANT_DIRECTOR (42), REGIONAL_DIRECTOR (45) and MINISTRY_ADMIN (48). All three sit ABOVE
PROFESSOR (40), which is a first for this ladder and is why the rank consequences are listed at the
foot of this docstring rather than left to be discovered.

This module answers four questions and refuses to answer a fifth:

* **WHO SUPERVISES THIS WORKSHOP** — one Assistant Director and one Regional Director, named per
  workshop, stored in ``DesignWorkshopOversight``.
* **WHICH WORKSHOPS DOES THIS OFFICER SUPERVISE** — the scope clause their own list is built from.
* **WHO IS THE WORKSHOP FOR** — the designer team and which of them the report names
  (:func:`named_designer_rows`, :func:`reassign_designer`, :func:`set_named_designers`).
* **WHO IS ON ITS ARTISAN ROSTER** — and how one of them is taken off again
  (:func:`linked_artisan_rows`, :func:`unlink_artisan_from_workshop`).
* It does NOT answer "may this officer WRITE anything", because the answer is no and there is no
  argument a request could carry that changes it. See the read-only paragraph below — and note that
  **an ASSIGNER is not an OFFICER**: the two write paths above belong to
  :data:`OVERSIGHT_ASSIGNER_ROLES`, and every route an officer can reach is still a GET.

**THE LAST TWO LANDED IN 0.0.12 AND THIS LIST SAID "two questions" BEFORE THEM.** The reason they
landed together is one sentence: assignment on this prefix was ADD-ONLY. It could name a designer,
could not show who else held the workshop, could not take anybody off it, and had no read of the
artisan roster at all — so the screen that decides who a workshop is for could add and could never
correct.

=======================================================================================
WHY A NEW TABLE AND NOT A `capacity` COLUMN ON THE FIFTH SCOPE'S TABLE — SIX REASONS
=======================================================================================

The obvious-looking home for "this officer is attached to this workshop, read-only" is the table
``design_workshop_inspectors`` already owns. It is the wrong home, six times over, and each of
these is sufficient on its own.

**1. THE IMPORT-TIME GUARD WOULD NOT CATCH THE CHANGE, WHICH IS WHY IT MUST NOT BE LEANED ON.**
``design_workshop_inspectors`` raises ``RuntimeError`` at import when ``INSPECTION_ROLES`` and
``deps.DESIGN_WORKSHOP_ROLES`` overlap. ASSISTANT_DIRECTOR and REGIONAL_DIRECTOR are in NEITHER
set, so adding them to ``INSPECTION_ROLES`` leaves that assert green and the API boots. What it
would break is invisible: every AD and RD would silently acquire the whole inspector read surface,
and ``tests/test_dw_inspector_scope_gate.py``'s "everybody else, including admins, 403" would
either go red or, worse, be "fixed".

**2. THAT SET'S OWN DOCSTRING FORBIDS IT IN WRITING.** It argues that the frozenset-of-one is "the
shape rather than an oversight", names admins and professors as deliberate exclusions, and says
adding a member "is one entry here plus a sentence in the refusal below — never a silent widening."

**3. ITS PRIMARY KEY IS ITS IDENTITY AND CANNOT CARRY A CAPACITY.** ``@@id([designWorkshopId,
userId])`` is documented as "The pair IS the identity — one inspection per person per workshop", and
the one lookup that table exists for is a ``find_unique`` on that compound key. Adding a third
column to the key renames the compound key and breaks the lookup.

**4. THE TWO TABLES ANSWER DIFFERENT QUESTIONS WITH DIFFERENT LIFETIMES.** An inspection row is an
ACCESS grant — no status, no level, no revokedAt, the row IS the grant — and removing an inspector
DELETES it, because "a tombstone would record only that an admin changed their mind". An AD/RD
assignment is an ACCOUNTABILITY fact. Folding them means revoking an RD's access erases who
supervised the workshop.

**5. READ-ONLY-BY-CONSTRUCTION WOULD HAVE TO BE ABANDONED.** ``test_dw_inspector_scope_gate.py``
walks the real dependency tree and asserts ``set(route.methods) == {"GET"}`` for every route behind
that feature's door. An officer who may record nothing is not a supervisor; an officer who may
would put a non-GET on that prefix, and that test hard-fails on it BY DESIGN.

**6. ITS "ALREADY ON THIS WORKSHOP" REFUSAL IS WRITTEN FOR A DIFFERENT RELATIONSHIP.** That branch
refuses anybody who is the workshop's creator or holds a viewer row, because "an independent review
by somebody who worked on it is not a review". An Assistant Director is LINE MANAGEMENT, not an
independent examiner. The refusal is reused here (see :func:`assert_may_hold`) because its first
half — you cannot supervise work you did yourself — is right for both, but importing the whole
rule, including that feature's picker query, would start offering Regional Directors in an admin's
*inspector* picker.

=======================================================================================
READ-ONLY IS STRUCTURAL HERE, EXACTLY AS IT IS ONE SCOPE OVER
=======================================================================================

:func:`load_overseen_workshop_or_404` takes NO ``for_edit`` parameter and must never grow one. The
absence is the enforcement: there is no argument an officer's request could carry that turns their
read into a write, because there is no such argument.

**AND AN OVERSIGHT ROW IS NOT A ``DesignWorkshopViewer`` ROW.** This is the one mistake in this
whole feature that would be silent. ``design_workshops.load_workshop_or_404(for_edit=True)`` carries
no role check of its own beyond DESIGN_WORKSHOP_ROLES membership on its grant arm; a viewer row
confers every stage save, the AI-layer accept/withdraw, the dictation consent and the export
ledger. Everything would appear to work; the officer would simply have more power than anyone
intended.

**TWO FUNCTIONS HERE WRITE VIEWER ROWS — :func:`reassign_designer` AND :func:`set_named_designers`
— AND EVERY ACCOUNT EITHER OF THEM NAMES IS A DESIGNER.** (This sentence said "The ONE place" until
0.0.12, when the plural set write landed; it is corrected rather than deleted because the property
it defends is unchanged and is the one that matters.) Neither writes the table directly: both go
through ``design_workshops.attach_the_named_designer`` and
``design_workshop_viewers.remove_one_viewer``, both validate the ADDED ids through
``assert_every_designer_may_be_named`` — the viewers screen's own rule, imported and never copied —
and ``tests/test_workshop_oversight_unit.py`` runs an AST sweep over the three files of this feature
forbidding ``db.designworkshopviewer.*`` outright. **An officer must never appear in that table**,
and ``OVERSIGHT_CAPACITY_ROLES`` plus the empanelment rule are what keep the two sets apart.

=======================================================================================
WHO MAY ASSIGN, AND WHY IT IS NOT "THE MOST SENIOR OFFICER"
=======================================================================================

:data:`OVERSIGHT_ASSIGNER_ROLES` is ``{MINISTRY_ADMIN, ADMIN, MASTER_ADMIN}``. **A REGIONAL
DIRECTOR IS DELIBERATELY NOT IN IT EVEN THOUGH THEY OUTRANK AN ASSISTANT DIRECTOR**, and every rank
instinct is wrong about that line. "The supervised must not choose the supervisor" is the same rule
``design_workshop_inspectors``' header states one rung down as THE INSPECTED MUST NOT CHOOSE THE
INSPECTOR. An RD who should be able to assign is a MINISTRY_ADMIN, which is a role change an admin
makes on ``/users`` and not a widening here.

=======================================================================================
RANK CONSEQUENCES THIS MODULE INHERITS AND DOES NOT CREATE
=======================================================================================

All three tiers clear PROFESSOR (40), and every one of the following is a set-free, RANK-based
predicate elsewhere in the codebase. They are listed because a reader of this file will otherwise
assume the officer surface is the whole of what an officer can do, and it is not:

* ``deps.can_create_records`` (``has_rank(user, "RESEARCHER")``) — all three may create artisans.
  **This is why the spreadsheet importer needs no new record-creation gate.**
* ``records.apply_status_policy_create`` — reads "Professor and above default to APPROVED", so the
  obvious conclusion is that an artisan an officer creates skips review. **MEASURED: IT DOES NOT.**
  That function uses ``setdefault`` and ``ArtisanCreate.status`` is declared ``str = "PENDING"``, so
  the key is already in the dict and the senior branch does nothing. An officer's artisan is created
  PENDING and does enter the review queue. The full argument, and the two-directional test that pins
  it, are in ``services/artisan_import``'s header — read it before changing that schema default.
* ``artisans._may_read_full_aadhaar`` (``has_rank(user, "PROFESSOR")``) — **an officer already
  reads every artisan's unmasked Aadhaar today.** That is load-bearing for the importer's PII
  argument: the pro-forma moves data an officer may already read from one surface to another; it
  does not widen the audience.
* ``deps.can_edit_others_record`` — an officer may rewrite a designer's records. Narrowing that for
  these three tiers is a rank question and belongs to whoever owns the ladder, not here.

=======================================================================================
WHY THERE IS NO `OfficerRoster`
=======================================================================================

``DesignerRoster`` exists because empanelment is a fact about an EMAIL that outlives an account, and
it exists in that shape for three reasons, none of which transfers to an officer.

1. **The row IS the invitation, because there is no mailer.** ``credential_links.delivery()``
   hard-returns ``CopyLinkDelivery``; the transport is an admin copying a URL out of ``/users``. An
   ACTIVE roster row PROMOTES an account to DESIGNER on first Google sign-in without anybody being
   told anything. **``AccessRoster`` already does exactly this job for every other role** — its
   ``admitRole`` column lets an admin admit an address at ``admitRole=REGIONAL_DIRECTOR`` and
   ``login_with_google`` creates the ``User`` at that role. A second roster would be a second answer
   to a question ``AccessRoster`` already answers, and ``access_roster._the_row_that_decides``
   exists precisely because two rows answering one question is a bug shape this codebase has
   already paid for.
2. **``DesignerRoster`` confers a role LIFT that ``AccessRoster`` could not express at the time.**
   Its own schema comment says the platform gate "was deliberately NOT built by widening this table
   because grandfathering every account into it would have handed DESIGNER to every volunteer".
   Nobody is being grandfathered into REGIONAL_DIRECTOR. The lift argument is empty here.
3. **``roster_allows`` gates the DESIGNER sign-in and no other role.** A second empanelment gate
   would add a second refusal sentence, a second suspension mirror, a second admissions sweep and a
   fourth reconciliation script. Four new failure modes bought for a fact nobody has.

An officer has exactly TWO facts: may they sign in at all (``AccessRoster``) and what may they do
(``User.role``). There is no third. **The one genuinely officer-shaped fact — which workshop an AD
or RD covers — is not an email fact**: it is per-workshop, it changes when the officer transfers,
and it must not retroactively re-attribute a workshop that has already been submitted. That is
``DesignWorkshopOversight``, and putting a region on a roster row instead would recreate the defect
``schema.prisma`` records for ``DesignerProfile`` — a report is a historical document, and the link
is copied or pinned per workshop, never resolved through a live roster.
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import HTTPException, status

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import is_break_glass_master, role_value
from app.schemas import design_workshop_review_loop
from app.services import access_roster, design_workshop_viewers, design_workshops
from app.services.records import contains

logger = logging.getLogger(__name__)


# --------------------------------------------------------------------------------------
# Who is an officer, and who may say who supervises what
#
# ⚠ THESE FOUR NAMES BELONG IN ``app/core/deps.py``, BESIDE THE OTHER FOUR DESIGN-WORKSHOP ROLE
# SETS, AND THEY ARE HERE FOR THE SAME TEMPORARY REASON ``require_inspector`` gives for living in
# ``api/routes/design_workshop_inspections`` rather than in ``deps``: that file is owned by another
# workstream in this wave and a cross-agent edit to it silently destroys the other change. If you
# are the person consolidating them, MOVE THE SET AND THE PREDICATE TOGETHER — splitting them is
# how "who is an officer" comes to have two answers.
# --------------------------------------------------------------------------------------


#: The roles that hold a supervisory POST over design workshops, for the directory that lists them.
#:
#: A SET, NOT A RANK FLOOR, and the reason is the same one ``DESIGN_WORKSHOP_ROLES`` gives at
#: length: 42/45/48 sit above PROFESSOR (40), so a floor written as "ASSISTANT_DIRECTOR and above"
#: would silently admit ADMIN and MASTER_ADMIN into a picker of ministry posts — and an admin is an
#: operator of this application, not an officer of the scheme. Two different things, two lists.
#:
#: MINISTRY_ADMIN IS IN THE DIRECTORY AND IS NOT ASSIGNABLE TO A WORKSHOP. It is listed because the
#: directory answers "who are the officers"; it is refused by :data:`OVERSIGHT_CAPACITY_ROLES`
#: below because the requirement names exactly two posts per workshop — an Assistant Director and a
#: Regional Director — and a ministry administrator supervises the scheme rather than one workshop.
OFFICER_ROLES = frozenset({"ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN"})

#: Which role may be named in which capacity on one workshop. ONE ROLE PER CAPACITY, deliberately.
#:
#: A MAP rather than one flat set, because "an AD slot may hold an AD" and "an RD slot may hold an
#: RD" are two rules, and one set would let an officer be filed in the other's slot — which prints
#: the wrong post beside the right name on a document going to a ministry. The refusal in
#: :func:`assert_may_hold` names the capacity AND the role it found, so the remedy is obvious.
#:
#: ITS KEYS ARE THE ENUM'S MEMBERS AND THAT IS CHECKED AT IMPORT, at the foot of this module. A
#: third capacity is an ``ALTER TYPE … ADD VALUE`` in its own migration file plus an entry here;
#: adding one without the other is a capacity the API can store and cannot validate.
OVERSIGHT_CAPACITY_ROLES: dict[str, frozenset[str]] = {
    "ASSISTANT_DIRECTOR": frozenset({"ASSISTANT_DIRECTOR"}),
    "REGIONAL_DIRECTOR": frozenset({"REGIONAL_DIRECTOR"}),
}

#: The two capacities, in the order a screen and a report print them. Derived from the map above so
#: the two cannot disagree about how many there are.
CAPACITIES: tuple[str, ...] = tuple(OVERSIGHT_CAPACITY_ROLES)

#: Who may CHANGE a workshop's oversight, and its designer.
#:
#: MINISTRY_ADMIN plus the two platform tiers. **A REGIONAL_DIRECTOR IS DELIBERATELY NOT HERE EVEN
#: THOUGH THEY OUTRANK AN ASSISTANT DIRECTOR** — see the module header. An RD who should be able to
#: assign is a MINISTRY_ADMIN, which is a role change an admin makes on ``/users``.
OVERSIGHT_ASSIGNER_ROLES = frozenset({"MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"})

#: How many accounts the officer picker will offer in one call.
#:
#: A CEILING, NOT A PAGE SIZE, carrying ``truncated`` on the wire for the reason
#: ``ELIGIBLE_VIEWER_LIMIT`` learned the hard way: its sibling's ceiling WAS reached on a real
#: repository, the cut fell mid-alphabet, and an eligible colleague sorting past it was
#: indistinguishable from one who had never been appointed. Those two states must never look
#: identical, so ``search`` reaches past this and ``truncated`` says the list was cut.
ELIGIBLE_OFFICER_LIMIT = 2000


def is_officer(user: Any) -> bool:
    """Is this account one of the three ministry posts?

    SET MEMBERSHIP, written against the string rather than against ``ROLE_RANK`` so this module is
    correct on a deployment where the three tiers have not been added to the ladder yet: it answers
    False for everybody, which is the fail-closed direction. Same construction, same reason, as
    ``design_workshop_inspectors.is_inspector``.
    """
    return role_value(user) in OFFICER_ROLES if user is not None else False


def can_assign_workshop_oversight(user: Any) -> bool:
    """May this account name the designer, the AD and the RD on a design workshop?"""
    return role_value(user) in OVERSIGHT_ASSIGNER_ROLES if user is not None else False


#: What an account is told when it reaches the officer's read surface without one of the posts.
#:
#: A SENTENCE THAT NAMES THE OTHER DOOR, which is the whole reason this is a constant rather than an
#: inline string: an ADMIN hits this refusal too, and an admin told only "forbidden" on a READ
#: surface will reasonably conclude the deployment is broken.
NOT_AN_OFFICER_DETAIL = (
    "Workshops I monitor belongs to the Assistant Director, Regional Director and Ministry Admin "
    "posts. Designers and admins read design & prototype workshops through /api/design-workshops "
    "instead; a Ministry Admin chooses who monitors a workshop at "
    "/api/design-workshop-oversight/{id}."
)

#: What everybody else is told when they try to CHANGE who supervises a workshop.
OVERSIGHT_ASSIGNER_REFUSAL = (
    "Naming the designer, the Assistant Director and the Regional Director on a design & prototype "
    "workshop is done by a Ministry Admin, an admin or the master admin. An Assistant Director or "
    "Regional Director reads the workshops they have been assigned on Workshops I monitor."
)


def assert_oversight_surface(user: Any) -> None:
    """403 for anybody who is not an officer, INCLUDING ADMINS, and that is deliberate.

    Admitting admins here would mean one of two things and both are worse than a refusal. Scoped by
    THEIR OWN oversight rows, an admin sees an empty list and reads it as a broken feature. Scoped
    by "everything, because they are an admin", this surface silently becomes a second full read of
    every workshop in the repository — a second place to look when somebody has access they should
    not, which is precisely what ``services/design_workshop_access``'s header refuses.

    So the answer is a 403 that names the door they want. Nothing here branches on ``is_admin``, and
    nothing here should: the refusal is identical for an admin, a designer and a volunteer, which is
    what makes it one sentence to reason about rather than three. An admin's half of this feature is
    the ASSIGNMENT screen, which :func:`assert_may_assign_oversight` admits them to.
    """
    if is_officer(user):
        return
    raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=NOT_AN_OFFICER_DETAIL)


def assert_may_assign_oversight(user: Any) -> None:
    """403 for anybody outside :data:`OVERSIGHT_ASSIGNER_ROLES`, a Regional Director included."""
    if can_assign_workshop_oversight(user):
        return
    raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=OVERSIGHT_ASSIGNER_REFUSAL)


# --------------------------------------------------------------------------------------
# Reading: the two questions the enforcement asks
# --------------------------------------------------------------------------------------


async def has_oversight_scope(workshop_id: str, user_id: str) -> bool:
    """May this account READ this workshop on the strength of an oversight row?

    NOT a ``find_unique``, unlike the fifth scope's equivalent, and the difference is the shape of
    the key rather than a preference: that table is keyed ``(workshop, user)`` so the question IS
    its primary key, while this one is keyed ``(workshop, capacity)`` — "is this person on this
    workshop in ANY capacity" is a two-row scan of one workshop's oversight, which is what
    ``find_first`` over the primary key's leading column is.

    ⚠ **THIS IS A READ PREDICATE AND MUST NEVER BE CALLED FROM A WRITE GATE.** If you are about to
    add ``or await has_oversight_scope(...)`` beside a ``has_viewer_grant`` call, stop and read this
    module's header: every one of those sites is on a path that also carries stage writes.
    """
    if not workshop_id or not user_id:
        return False
    row = await db.designworkshopoversight.find_first(
        where={"designWorkshopId": workshop_id, "userId": user_id}
    )
    return row is not None


def oversight_by_clause(user_id: str) -> dict[str, Any]:
    """The oversight list's scope: workshops this account has been assigned to supervise.

    Mirrors ``design_workshop_viewers.visible_to_clause`` in SHAPE so the two cannot drift — the
    same relation-filter idiom, the same composition rule — and differs from it in exactly two ways,
    both deliberate:

    * **There is no ``createdById`` arm.** An officer creates nothing. The sibling's clause is an
      ``OR`` of "mine" and "granted to me"; this one has a single source, which is the point: an
      officer with no row sees nothing at all, and there is no second way in to reason about.
    * **It reads its own relation**, ``oversight``, and never ``viewers``.

    **MUST be AND-composed, never assigned to ``where["OR"]``.** The same warning the sibling
    carries, for the same reason: a search box builds an ``OR`` on that key and the later assignment
    silently wins — which is either a search that stops narrowing or a scope that vanishes the
    moment somebody types.
    """
    return {"oversight": {"some": {"userId": user_id}}}


async def load_overseen_workshop_or_404(workshop_id: str, user: Any) -> Any:
    """The workshop an officer may READ, or 404. **THE READ-ONLY LOADER.**

    Deliberately NOT ``design_workshops.load_workshop_or_404``, and deliberately not a call into it:
    that helper takes ``for_edit`` and is what fourteen write routes pair with ``_require_designer``.
    **This one has no ``for_edit`` parameter and must never grow one.** That absence is the whole
    enforcement.

    It would also simply not work: since 2026-09-03 that loader honours a viewer grant only for an
    account inside ``DESIGN_WORKSHOP_ROLES``, which an officer is outside, so calling it here would
    404 every officer on every workshop they supervise.

    404 AND NOT 403 for a workshop out of scope, matching every other loader in this family: a 403
    would confirm the id exists to exactly the people this is turning away.

    A SOFT-DELETED WORKSHOP IS A 404 HERE, WITH NO 409 ARM. The designer's loader answers 409 to an
    editor so that somebody holding unsent stages is told to ask an admin to restore it — advice
    that presumes there is something to save. An officer has nothing pending and no restore button.

    THE ROLE IS RE-CHECKED HERE even though the routes already stand behind their dependency. Belt
    and braces on purpose: this is the function a future caller will reach for, and a loader that
    trusts its caller's gate is how a scope leaks onto a surface nobody re-read.
    """
    if not is_officer(user):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    if record is None or record.deletedAt is not None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if not await has_oversight_scope(workshop_id, getattr(user, "id", "")):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record


# --------------------------------------------------------------------------------------
# Reading: the two lists the assignment screen renders
# --------------------------------------------------------------------------------------


def _role(user: Any) -> str:
    return role_value(user) if user is not None else ""


def oversight_payload(row: Any) -> dict[str, Any]:
    """One oversight row as the assignment screen reads it.

    ``name``/``email``/``role`` travel WITH the row rather than being joined against a directory the
    screen also holds: an officer whose account has since been suspended is precisely the row an
    administrator most needs to see and act on, and a join against the eligible list would render it
    as a bare cuid.

    ``assignedAt`` is the column and NOT ``createdAt``, which is the whole reason that column
    exists: re-assigning a capacity to a different person UPDATES this row, so ``createdAt`` answers
    "since when has this workshop had an AD" where the question anybody asks is "since when has THIS
    PERSON been its AD".
    """
    user = getattr(row, "user", None)
    assigned_at = getattr(row, "assignedAt", None)
    return {
        "capacity": str(getattr(row.capacity, "value", row.capacity)),
        "userId": row.userId,
        "name": getattr(user, "name", "") or "",
        "email": getattr(user, "email", "") or "",
        "role": _role(user),
        "assignedAt": assigned_at.isoformat() if assigned_at else None,
        "assignedById": getattr(row, "assignedById", None),
    }


async def oversight_rows(workshop_id: str) -> list[dict[str, Any]]:
    """This workshop's oversight, in capacity order — Assistant Director first.

    ORDERED BY THE PYTHON LIST AND NOT BY THE DATABASE, because the order that matters is the one
    :data:`CAPACITIES` declares (the order a report prints the two posts in) and Postgres sorts an
    enum by its declaration order, which is the same thing today and is not the same PROMISE.
    """
    rows = await db.designworkshopoversight.find_many(
        where={"designWorkshopId": workshop_id}, include={"user": True}
    )
    by_capacity = {str(getattr(r.capacity, "value", r.capacity)): r for r in rows}
    return [oversight_payload(by_capacity[c]) for c in CAPACITIES if c in by_capacity]


async def officer_directory(search: str | None = None) -> dict[str, Any]:
    """The accounts that hold one of the three ministry posts.

    MODELLED ON ``design_workshop_inspectors.eligible_inspectors`` AND NOT ON
    ``designers.designer_directory``, which is the choice that matters: the designer directory
    carries empanelment standing off ``DesignerRoster``, and an officer has no empanelment — see
    the module header. One role clause, one cut list, no roster.

    THE PLATFORM ALLOW-LIST STILL APPLIES, because it gates every role: an account the allow-list
    has REJECTED or SUSPENDED cannot sign in, so offering it here would mean naming an officer whose
    next sign-in is refused, with nothing on screen saying why. It is read as a CUT LIST and never
    as a guest list.

    **THE TWO ``OR``S ARE AND-COMPOSED, NEVER ASSIGNED TO THE SAME KEY.** The eligibility clause and
    the search clause both want ``where["OR"]``, and the later assignment silently wins; if that is
    the search, the ROLE clause is gone and this picker offers every account in the repository.

    ``capacities`` ON EACH ROW IS WHAT LETS THE PICKER GREY A MINISTRY_ADMIN OUT rather than letting
    the PUT 422 them. A directory that offers a choice the write refuses is a directory that teaches
    people to distrust it.
    """
    barred = await access_roster.barred_emails()

    clauses: list[dict[str, Any]] = [{"role": {"in": sorted(OFFICER_ROLES)}}]
    if barred:
        # THE BREAK-GLASS, SPELLED HERE BECAUSE A ``WHERE`` CANNOT CALL A PYTHON FUNCTION. Kept in
        # step with ``deps.is_break_glass_master`` BY HAND, and with BOTH of its arms: the role, and
        # the configured address, which exists for the deployment where the row carrying the role
        # has not been seeded or somebody has demoted it. Spelling only the role half is exactly how
        # the viewer picker's copy came to be silently narrowed.
        #
        # The master admin is not in ``OFFICER_ROLES``, so this arm is unreachable through the
        # clause above. It is written anyway: an exemption that is missing on the day the role set
        # changes is worse than one that is inert.
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
        # hidden" would change on refresh.
        order=[{"name": "asc"}, {"id": "asc"}],
        take=ELIGIBLE_OFFICER_LIMIT + 1,
    )
    truncated = len(users) > ELIGIBLE_OFFICER_LIMIT
    users = users[:ELIGIBLE_OFFICER_LIMIT]
    if truncated:
        # Logged as well as reported: the log names the term that was too broad, which the response
        # cannot, and it is what an operator reads when somebody says "I cannot find her".
        logger.warning(
            "officer-directory hit its ceiling of %s accounts (search=%r); the answer is truncated "
            "and says so, and the caller can narrow it",
            ELIGIBLE_OFFICER_LIMIT,
            term,
        )
    return {
        "users": [
            {
                "id": u.id,
                "name": u.name,
                "email": u.email,
                "role": _role(u),
                "capacities": capacities_for(_role(u)),
            }
            for u in users
        ],
        "truncated": truncated,
    }


def capacities_for(role: str) -> list[str]:
    """Which slots this role may be filed in. Empty for a MINISTRY_ADMIN, which is the point."""
    return [c for c, roles in OVERSIGHT_CAPACITY_ROLES.items() if role in roles]


# --------------------------------------------------------------------------------------
# Writing: validate everything, then apply the whole set
# --------------------------------------------------------------------------------------


async def assert_may_hold(workshop_id: str, wanted: dict[str, str]) -> None:
    """422 naming the offending account and the capacity, never a silent skip.

    ``wanted`` is ``{capacity: userId}`` for the capacities this request is SETTING (an unassign
    carries no id and has nothing to validate).

    FOUR REFUSALS, in this order:

    1. **No such account.** Asked before anything else.
    2. **Wrong role for the capacity.** The sentence names what the account IS *and* which slot it
       was offered for, because those are two different mistakes with one remedy. **IT DOES NOT
       STACK** — its only remedy is picking somebody else, and appending "and they are also
       suspended" to a designer who can never hold this post is a second errand attached to a
       refusal that already ends the matter.
    3. **Barred by the platform allow-list.** Asked of EXACTLY the addresses named here rather than
       of the capped ``barred_emails`` read, because a refusal has to be able to promise it is
       complete and that one has a ceiling. STACKS.
    4. **Already on this workshop as its creator or a co-designer.** STACKS. An officer who ran the
       workshop cannot be the officer who supervises it — the same rule the inspection tier states
       one rung down, and the reason ``_accounts_already_on_the_workshop`` is IMPORTED rather than
       restated: it is a two-query membership question with an exact answer, and a second copy here
       would be free to forget the creator arm.

       **WHAT IT DOES NOT COVER, STATED SO IT IS A KNOWN GAP AND NOT AN OVERSIGHT.** That helper
       reads the creator and the viewers and nothing else, so one person may today be a workshop's
       Regional Director AND its inspector, and nothing refuses it. Both roles are read-only, so
       nothing is corrupted; what is lost is the independence the inspector tier exists to
       guarantee. Closing it means a new exported predicate inside ``design_workshop_inspectors``
       — the only module permitted to read that table — and it is an owner call.

    VALIDATION RUNS TO COMPLETION BEFORE ANY WRITE. One bad id refuses the whole call rather than
    applying the good half: somebody who named two officers and is shown one has been told nothing
    about which failed or why, and a partially applied assignment looks like it worked.
    """
    # IMPORTED INSIDE THE FUNCTION so this module's import graph does not depend on the fifth
    # scope's at load time. The helper is a private name in another access module, which is a
    # coupling worth stating out loud rather than quietly living with — the same trade
    # ``design_workshop_grants`` makes for ``_assert_every_id_may_be_granted`` and
    # ``design_workshop_inspections`` makes for ``_stages_payload``. A second copy of a two-query
    # membership question is how the creator arm comes to be forgotten in one of them.
    from app.services.design_workshop_inspectors import _accounts_already_on_the_workshop

    ids = {uid for uid in wanted.values() if uid}
    if not ids:
        return

    users = await db.user.find_many(where={"id": {"in": sorted(ids)}})
    by_id = {u.id: u for u in users}

    unknown = sorted(_displayable(uid) for uid in ids if uid not in by_id)
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
    for capacity in CAPACITIES:
        uid = wanted.get(capacity)
        if not uid:
            continue
        user = by_id[uid]
        role = _role(user)
        if role not in OVERSIGHT_CAPACITY_ROLES[capacity]:
            refusals.append(
                f"{user.name} ({user.email}) is a {role}, and the {_label(capacity)} of a design & "
                f"prototype workshop must be {_article(capacity)} {_label(capacity)}. A Ministry "
                f"Admin supervises the scheme rather than one workshop and cannot be named in "
                f"either slot."
            )
            # AND NOTHING FURTHER ABOUT THIS ACCOUNT — see the docstring.
            continue
        if not is_break_glass_master(user) and _normalised(user.email) in barred:
            refusals.append(
                f"{user.name} ({user.email}) is barred by the platform access list, so they cannot "
                f"sign in at all. Clear that on the access screen first; an oversight row on its "
                f"own would leave this screen saying they are monitoring while they are shown a "
                f"refusal at the door."
            )
        if uid in on_the_workshop:
            refusals.append(
                f"{user.name} ({user.email}) is already on this workshop as its creator or a "
                f"co-designer, so they cannot also supervise it — nobody supervises their own "
                f"work. Take them off the workshop's viewers first if they have genuinely moved "
                f"from running it to monitoring it."
            )

    if refusals:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=" ".join(refusals) + " Nothing was changed.",
        )


_CAPACITY_LABELS = {
    "ASSISTANT_DIRECTOR": "Assistant Director",
    "REGIONAL_DIRECTOR": "Regional Director",
}


def _label(capacity: str) -> str:
    return _CAPACITY_LABELS.get(capacity, capacity)


def _article(capacity: str) -> str:
    """ "an Assistant Director" / "a Regional Director" — one character, and a reader notices it."""
    return "an" if _label(capacity)[:1].upper() in "AEIOU" else "a"


#: Characters that cannot reach Postgres inside an id, and that no id this repository issues holds.
#:
#: NUL is refused by a ``text`` comparison outright (SQLSTATE 22021), and a LONE SURROGATE — half an
#: emoji from a phone that truncated it — cannot be encoded to UTF-8 at all, so it fails inside the
#: driver before Postgres is reached. Either one turns the ``find_many`` above into a bare 500 whose
#: body names the exception class, where the honest answer is the "no account exists with this id"
#: every other unmatchable id already gets.
#:
#: THE WHOLE CLASS IS UNREACHABLE FROM THE HTTP DOOR TODAY and the guard is kept anyway:
#: ``DesignWorkshopOversightIn`` bounds both ids at 64 characters but does not restrict their
#: alphabet, and a schema is one edit away from a service that assumed one.
_UNSTORABLE_IN_AN_ID = __import__("re").compile(r"[\x00-\x1f\x7f\ud800-\udfff]")


def _displayable(user_id: str) -> str:
    """An id safe to put in a refusal message, and in whatever reads the log after it."""
    return _UNSTORABLE_IN_AN_ID.sub("", user_id)


def _normalised(email: Any) -> str:
    """Lower-cased and stripped, matching what ``access_roster`` answers with."""
    return str(email or "").strip().lower()


async def apply_oversight(
    workshop_id: str,
    *,
    wanted: dict[str, str | None],
    assigned_by_id: str,
) -> list[dict[str, Any]]:
    """Set this workshop's oversight to ``wanted``, and answer with it as the SERVER now holds it.

    ``wanted`` is ``{capacity: userId or None}`` and carries ONLY the capacities this request named.
    An omitted capacity is left exactly as it stands; an explicit ``None`` deletes the row. Those
    are three different requests — set, leave, unassign — and the caller distinguishes the first two
    with ``model_fields_set``, which is the same ``exclude_unset`` discipline ``ArtisanUpdate``
    documents.

    THE ANSWER IS THE SET AS THE SERVER NOW HOLDS IT, NEVER AN ECHO OF WHAT WAS SENT. Two
    administrators on one screen must not each end up believing their own payload was the outcome —
    the same rule the inspector roster's PUT states, for the same reason.

    RE-ASSIGNING A CAPACITY IS AN UPDATE, NOT A SECOND ROW, and that is the primary key doing it:
    ``(designWorkshopId, capacity)``. ``assignedAt`` is restamped and ``createdAt`` is not, so "since
    when has THIS PERSON been its AD" stays answerable.

    IDEMPOTENT: naming the officer who already holds the capacity writes nothing at all, so
    re-saving an unchanged screen does not restamp ``assignedAt`` — which matters because that
    timestamp is the only answer anybody has to "how long has this workshop been supervised".

    ── ALL OF IT OR NONE OF IT ──────────────────────────────────────────────────────────────────

    **THE LOOP IS ONE TRANSACTION, AND IT IS ONE BECAUSE A HALF-APPLIED ASSIGNMENT IS A SILENT
    ACCESS CHANGE UNDER A 500.** ``wanted`` carries BOTH capacities whenever a client sends both —
    the schema's own docstring says "THE TWO TRAVEL TOGETHER" and the route builds the dict from
    ``model_fields_set`` — so this loop routinely issues two independent statements. Statement by
    statement against the module singleton, each one autocommits: an AD swap that lands followed by
    an RD create that raises (the account hard-deleted between :func:`assert_may_hold` and here and
    the ``userId`` FK refusing it, a dropped connection, a concurrent save colliding on the
    ``(designWorkshopId, capacity)`` primary key) answers the administrator a 500 saying nothing
    happened while the AD has in fact CHANGED AND COMMITTED. That is not a cosmetic loss: an
    oversight row IS the read scope — ``load_overseen_workshop_or_404`` is built from it — so the
    officer named in the half that landed silently gained read access to every stage of the
    workshop and the officer they replaced silently lost it, with the screen still showing the pair
    it had before and the response saying the call failed.

    ``assert_may_hold`` STAYS OUTSIDE, and deliberately: it reads the account rows and the roster
    through the module singleton, and ``attach_the_named_designer``'s docstring sets out what
    happens to a read like that issued from inside a transaction that has just written what it is
    reading. Validation to completion, then the writes; the route already has that order.

    **UPSERT RATHER THAN CREATE-OR-UPDATE, which closes the one race a transaction does not.** The
    branch that used to stand here read ``held`` and then chose ``create`` or ``update`` from it,
    so two administrators saving the same screen in the same second both read "no row" and the
    second ``create`` died on the primary key — a 500 for the ordinary case of two people doing
    their job at once, which is the failure ``replace_viewers`` names beside its own
    ``skip_duplicates``. The upsert asks the database, which is the only reader that cannot be
    stale. ``createdAt`` is untouched on the update branch, so "since when has THIS PERSON been its
    AD" stays answerable exactly as before.
    """
    existing = await db.designworkshopoversight.find_many(where={"designWorkshopId": workshop_id})
    held = {str(getattr(r.capacity, "value", r.capacity)): r for r in existing}

    async with db.tx() as tx:
        for capacity, user_id in wanted.items():
            current = held.get(capacity)
            key = {
                "designWorkshopId_capacity": {
                    "designWorkshopId": workshop_id,
                    "capacity": capacity,
                }
            }
            if not user_id:
                if current is not None:
                    # DELETED, not revoked. There is no decision here to audit: an administrator
                    # changed their mind about who supervises a piece of work, and a tombstone would
                    # record only that. The same call the inspector roster makes, for the same
                    # stated reason.
                    await tx.designworkshopoversight.delete_many(
                        where={"designWorkshopId": workshop_id, "capacity": capacity}
                    )
                continue
            if current is not None and current.userId == user_id:
                continue  # idempotent — see the docstring
            await tx.designworkshopoversight.upsert(
                where=key,
                data={
                    "create": {
                        "designWorkshopId": workshop_id,
                        "capacity": capacity,
                        "userId": user_id,
                        "assignedById": assigned_by_id,
                    },
                    "update": {
                        "userId": user_id,
                        "assignedById": assigned_by_id,
                        "assignedAt": _now(),
                    },
                },
            )
    return await oversight_rows(workshop_id)


def _now() -> Any:
    from datetime import UTC, datetime

    return datetime.now(UTC)


# --------------------------------------------------------------------------------------
# The designer arm — drives the existing machinery, adds no second source of truth
# --------------------------------------------------------------------------------------


#: The statuses a designer reassignment is refused on, and the sentence that names the remedy.
#:
#: A FILED REPORT NAMES A DESIGNER. Silently swapping it re-attributes a filed document — the
#: workshop's cover page, its stage-1 header and the promoted ``designerName`` column every list
#: filters on all change under a 200 reading "Saved". Reopening the workshop is a deliberate act an
#: administrator takes with their eyes open, and it leaves a trace; this does not.
#:
#: ⚠ **DERIVED FROM THE REVIEW LOOP, AND IT IS DERIVED BECAUSE THE HAND-TYPED VERSION WENT STALE
#: THE DAY IT WAS WRITTEN.** Until this line was corrected the set was the literal
#: ``{"SUBMITTED", "ARCHIVED"}`` — the whole of "filed" under the FIVE-token vocabulary this
#: product had before 2026-09-13. That wave redefined ``SUBMITTED`` to mean "the APPROVED report
#: has been handed on", reachable only through ``(APPROVED, SUBMITTED)`` in
#: ``design_workshop_review_loop.DECISION_EDGES``, whose approvals router is not built yet — and it
#: made ``PRE_SUBMISSION`` the designer's hand-in. So the guard was protecting a state nothing
#: could reach while ``PRE_SUBMISSION`` and ``NEEDS_REVISION``, the two states in which officers
#: are actually holding the report, walked straight through it. Two concrete consequences, both
#: silent: a report under inspection had its authorship rewritten under the officers reading it,
#: and on a sent-back report the ``save_stage`` calls below trip
#: ``design_workshops.py``'s ``NEEDS_REVISION and _content_changed`` arm, which applies
#: ``presubmission_header`` — an administrative designer change silently RESUBMITTED the report,
#: spent a submission round (permanently mis-stamping every later ``DwInspectionFeedback.round``,
#: which is copied at write time and never recomputed) and erased which officer sent it back.
#:
#: ``UNDER_REVIEW`` is imported rather than restated so a ninth status cannot be added to the loop
#: without this set moving with it. What is left OUT is the whole argument: DRAFT, IN_PROGRESS and
#: COMPLETE are the states in which nobody outside the workshop is holding the report.
_CLOSED_STATUSES = frozenset(
    design_workshop_review_loop.UNDER_REVIEW
    | {
        design_workshop_review_loop.APPROVED,
        design_workshop_review_loop.SUBMITTED,
        design_workshop_review_loop.ARCHIVED,
    }
)


def _prefill_targets() -> dict[str, tuple[str, str]]:
    """``field key -> (stageKey, entityKey)``, read out of the registry at call time.

    **DERIVED, NEVER HAND-TYPED.** ``designers.PREFILL_MAP`` names twenty-one registry field keys
    and says nothing about which stage each lands on; splitting that list by hand into "the stage-1
    ones" and "the stage-3 ones" would be a second copy of a table
    ``tests/test_designer_prefill_contract.py`` already tests for drift, and the way it would fail
    is a profile column that silently stops reaching a report when somebody moves a field between
    stages.

    ``setdefault`` so the FIRST stage declaring a key wins, which is the same rule the seed applies
    by iterating the registry in order.
    """
    from app.services.stage_schema import stages

    out: dict[str, tuple[str, str]] = {}
    for spec in stages():
        for entity in spec.entities:
            for field in entity.fields:
                out.setdefault(field.key, (spec.key, entity.key))
    return out


async def reassign_designer(workshop: Any, user_id: str, *, actor: Any) -> dict[str, Any]:
    """Name a different designer on this workshop, and move their details with them.

    ── WHAT THIS DOES, IN ORDER, AND WHY EACH STEP IS WHERE IT IS ────────────────────────────────

    **1. Eligibility BEFORE any write.** ``assert_every_designer_may_be_named`` reads the DESIGNER
    empanelment roster AND the platform allow-list and answers with a sentence naming the screen
    that fixes each refusal. Its own docstring records why it is called before the write: a 422
    raised afterwards leaves a committed orphan behind on every retry.

    **2. A WORKSHOP WHOSE REPORT HAS BEEN FILED IS REFUSED**, 422, naming the status and the
    remedy. That is PRE_SUBMISSION, NEEDS_REVISION, APPROVED, SUBMITTED and ARCHIVED — see
    :data:`_CLOSED_STATUSES`, which is derived from the review loop rather than typed out, and
    which carries the whole account of what the hand-typed version cost. The check itself is
    :func:`assert_the_report_is_not_filed`, shared with :func:`set_named_designers` so that the
    plural door cannot grow a second, drifting copy of the one guard this module most needs to be
    right.

    **3. The viewer row**, through ``attach_the_named_designer`` — which is ``add_one_viewer`` and
    emphatically not ``replace_viewers``: the whole-set replace deletes whatever it did not read, so
    using it to add one person deletes a viewer row a concurrent join-card redemption created.
    **This is the ONE place this module writes a viewer row, and the account it names is a
    DESIGNER.** An officer must never appear in that table; see the module header.

    **3b. THE OUTGOING DESIGNER'S ROW IS TAKEN OFF, AND UNTIL THIS STEP EXISTED "NAME A DIFFERENT
    DESIGNER" WAS REALLY "NAME AN ADDITIONAL ONE".** ``add_one_viewer`` adds and never removes, and
    nothing here removed either — so the designer the ministry had just taken off the workshop kept
    a viewer row, and ``load_workshop_or_404(for_edit=True)`` admits a viewer row without asking
    whether its holder is the NAMED designer. They could go on saving stages, replacing
    photographs, recording dictation consent and pushing the report into PRE_SUBMISSION on a
    workshop that was no longer theirs. Worse, nothing could report it or undo it from here: the
    only viewer removal in the backend is ``replace_viewers`` behind ``require_admin`` = {ADMIN,
    MASTER_ADMIN}, and a MINISTRY_ADMIN — who is inside ``OVERSIGHT_ASSIGNER_ROLES`` and performs
    exactly this act — is outside that set, while ``read_workshop_oversight`` returns no viewer
    list for them to look at. Three separate pieces of copy promised a replacement
    (``DesignWorkshopDesignerIn``'s "Somebody who wants that is replacing the designer, which is
    this route", the route's own name, and the panel's "Currently: …"), and the code performed an
    addition.

    **WHO "THE OUTGOING DESIGNER" IS, AND WHY THE TEST IS THE NAME.** ``DesignWorkshop`` has no
    designer-id column — who a workshop is FOR is the promoted ``designerName`` plus a viewer row,
    which is the arrangement the "why there is no ``designer`` capacity" note below defends. So the
    outgoing lead is resolved the way the screen resolves them: the viewer whose OWN prefill would
    have written the name the workshop currently carries. That is narrow on purpose. A co-designer,
    a redeemed join card and a granted access request all leave viewer rows this must not touch,
    and an ambiguous answer — two viewers who would write the same name, or a ``designerName``
    nobody's profile matches — removes NOBODY and is reported instead of guessed. ``removedDesigner``
    and ``stillHaveAccess`` in the answer are what make the residue visible to the one screen the
    assigner can open; a silent stale grant was the whole defect.

    **4 AND 5. The profile values and the stage write**, both inside
    :func:`move_the_leads_profile_onto_the_workshop` — the lead's ``DesignerProfile`` minted if it
    does not exist, read through ``prefill_from_profile(user_id)`` and never
    ``user_id or actor.id``, and written through ``save_stage`` with ``merge=True``. That function
    carries the three defects it is written against, because it is now called from TWO places: here,
    and from :func:`set_named_designers` whenever a set write moves the lead. It answers with the
    stage keys it wrote, which is what ``stagesWritten`` below is.

    ── WHY THERE IS NO `designer` MEMBER OF `DwOversightCapacity` ────────────────────────────────
    Who a workshop is FOR already has an owner: the promoted ``DesignWorkshop.designerName`` column,
    the viewer row, and the stage 1 / stage 3 copy. A capacity row saying "the designer is X" beside
    a column saying "the designer is Y" is two answers to one question, and the report prints the
    column.
    """
    assert_the_report_is_not_filed(workshop)

    await design_workshops.assert_every_designer_may_be_named({user_id})

    # READ BEFORE THE WRITE, so the outgoing lead is resolved against the viewer set as it stood
    # BEFORE the incoming designer was added to it. Read after, the new row is in the set and the
    # only thing keeping it out of the removal candidates is the id test below — a guard that holds
    # today and that nobody would think to preserve. See step 3b.
    creator_id = str(getattr(workshop, "createdById", "") or "")
    outgoing = await _the_designer_being_replaced(workshop, incoming_id=user_id)

    await design_workshops.attach_the_named_designer(
        workshop.id,
        user_id,
        granted_by_id=getattr(actor, "id", ""),
        creator_id=creator_id,
    )
    if outgoing is not None:
        # AFTER the add and never before it. A workshop must never pass through a moment with no
        # designer on it at all: a failure in the gap would leave the outgoing designer removed,
        # the incoming one not yet granted, and the only account able to repair it an ADMIN the
        # assigner cannot escalate to from this screen.
        await design_workshop_viewers.remove_one_viewer(workshop.id, outgoing["userId"])

    stages_written = await move_the_leads_profile_onto_the_workshop(
        workshop.id, user_id, actor=actor
    )

    record = await db.designworkshop.find_unique(where={"id": workshop.id})
    # THE SURVIVING SET IS READ BACK RATHER THAN COMPUTED FROM WHAT WE JUST DID, for the reason
    # ``apply_oversight`` gives about its own answer: two administrators on one screen must not each
    # end up believing their own payload was the outcome. It is also the only way the one screen the
    # assigner can open can say who ELSE still holds write access to this workshop — a co-designer,
    # a redeemed join card, a granted access request — none of which this route removes.
    surviving = [
        row
        for row in await design_workshop_viewers.viewer_rows(workshop.id)
        if row["userId"] != user_id
    ]
    return {
        "designerId": user_id,
        "designerName": getattr(record, "designerName", None),
        "stagesWritten": stages_written,
        # WHO LOST ACCESS, AND WHO STILL HAS IT. ``None`` here means "nobody was identifiable as
        # the outgoing lead", which is a different fact from "nobody had access" — the list below
        # is what says which.
        "removedDesigner": outgoing,
        "stillHaveAccess": surviving,
    }


async def set_named_designers(
    workshop: Any, *, user_ids: list[str], lead_user_id: str | None, actor: Any
) -> dict[str, Any]:
    """Set the whole team a workshop is FOR, and answer with it as the SERVER now holds it.

    ══ WHY THIS EXISTS: ASSIGNMENT HERE WAS ADD-ONLY, AND THAT WAS THREE GAPS AT ONCE ═════════════

    Until 0.0.12 this prefix could name ONE designer and nothing else. A workshop is run by two
    designers alongside a master craftsperson and a reviewing officer; every one of them needs the
    22 stages, and a Ministry Admin could grant access to exactly one of them. Worse, they could not
    take it AWAY: the only viewer removal in the backend reachable from any screen was
    ``replace_viewers`` behind ``require_admin`` = {ADMIN, MASTER_ADMIN}, which is a set a Ministry
    Admin is outside — so the officer who performed a replacement had no route anywhere that could
    withdraw a co-designer's access, and no screen that would even have shown it to them.
    ``remove_one_viewer``'s own docstring names that defect verbatim; this is the second half of it.

    ══ A WHOLE-SET BODY, AND EMPHATICALLY NOT A WHOLE-SET WRITE ═══════════════════════════════════

    ⚠ **``replace_viewers`` IS FORBIDDEN FROM THIS FEATURE AND IS NOT CALLED HERE.**
    ``services/design_workshop_grants.py`` is a FOURTH writer of ``DesignWorkshopViewer`` — a
    redeemed join card and a granted access request both mint rows — so a whole-set replace deletes
    a row a concurrent redemption created in the same second, on a table where a deleted row IS the
    loss of access. This diffs the body against the rows that exist and then calls
    ``attach_the_named_designer`` (which is ``add_one_viewer``) per ADDED id and
    ``remove_one_viewer`` per REMOVED id — the same refusal ``attach_the_named_designers``' own
    docstring makes, for the same reason.

    ══ THE ORDER, AND WHY EACH STEP IS WHERE IT IS ════════════════════════════════════════════════

    **1. A FILED REPORT IS REFUSED FIRST**, through :func:`assert_the_report_is_not_filed` — the
    SAME guard :func:`reassign_designer` uses, sharing :data:`_CLOSED_STATUSES` rather than
    re-deriving it. A hand-typed copy of that set has already cost this repository a report under
    inspection having its authorship rewritten and a sent-back report being silently re-submitted,
    burning a round and permanently mis-stamping every later ``DwInspectionFeedback.round``.

    **2. THE CREATOR IS DROPPED FROM THE WANTED SET, NOT REFUSED.** Their access comes from
    ``createdById``; ``attach_the_named_designer`` refuses to mint a second, redundant source of
    truth for it, and ``_deduplicate`` drops them on the viewers PUT for the same stated reason. A
    body that names them is a no-op about them, never an error.

    **3. "NOBODY IS THE DESIGNER" IS STILL NOT AN EXPRESSIBLE STATE**, and this door does not become
    the way to reach it. ``DesignWorkshopDesignerIn``'s docstring is the argument — the promoted
    ``designerName`` column would have to be blanked, which is the write ``_coerce_promoted`` exists
    to stop happening by accident. So two refusals, both 422, both naming the remedy: an empty set
    on a workshop that names a designer, and a set that drops the LEAD without ``leadUserId``
    naming their replacement. **Removing a CO-designer is a different act and is always allowed** —
    their name is on no document, and this is the whole gap the feature was missing.

    **4. ELIGIBILITY IS ASKED OF THE ADDED IDS ONLY, AND BEFORE ANY WRITE.** ``added`` goes through
    ``assert_every_designer_may_be_named`` — one call for the whole set, so the 422 names every
    account it objected to and the officer makes one trip rather than N.
    ⚠ **REMOVALS ARE DELIBERATELY NOT VALIDATED.** ``remove_one_viewer`` says why in as many words:
    refusing to REMOVE somebody because their empanelment has lapsed strands access precisely on the
    accounts it is most urgent to take it away from.

    **5. ADD, THEN REMOVE, THEN MOVE THE LEAD'S PROFILE.** Adds first so the workshop never passes
    through a moment with no designer on it at all — the same ordering, and the same sentence,
    :func:`reassign_designer` carries. The prefill/stage-save arm runs **only when the lead actually
    changes**: adding a co-designer must not rewrite stage 1 and must not restamp the report's
    cover, and a save that moved the lead every time would make "add Rekha" quietly re-attribute the
    document.

    ══ NOT A TRANSACTION, AND THAT IS INHERITED RATHER THAN CHOSEN ════════════════════════════════

    ``attach_the_named_designers``' docstring argues at length why viewer rows are not transactional
    with their surroundings, and ``save_stage`` is a dozen writes of its own. Wrapping this loop in
    a ``db.tx()`` would silently reverse both decisions and would issue ``assert_every_designer_may_
    be_named``'s roster reads through a client that has just written what they read. The exposure is
    stated rather than hidden: a driver fault between the third add and the fourth answers 500 with
    three rows written, which is visible on the next read of this same screen and repairable from
    it. ``apply_oversight`` IS transactional because its two statements are an ACCESS SWAP under one
    button; this one is a set of independent grants.

    Answers with the set as the server now holds it — never an echo of the payload — for the reason
    every sibling write on this prefix gives: two administrators on one screen must not each end up
    believing their own body was the outcome.
    """
    assert_the_report_is_not_filed(workshop)

    workshop_id = getattr(workshop, "id", "")
    creator_id = str(getattr(workshop, "createdById", "") or "")

    # Blanks dropped, duplicates collapsed, the creator dropped — see step 2. Order is preserved
    # because the FIRST named designer is promoted to lead on a workshop that has never had one,
    # exactly as ``named_designer_team`` promotes the first ticked on the create doors.
    wanted: list[str] = []
    seen: set[str] = set()
    for raw in user_ids:
        candidate = (raw or "").strip()
        if not candidate or candidate == creator_id or candidate in seen:
            continue
        seen.add(candidate)
        wanted.append(candidate)

    lead = (lead_user_id or "").strip() or None
    if lead and lead not in seen:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "The designer named to lead this workshop is not among the designers it is for. "
                "A lead who is not on the workshop would have their name on its report and no way "
                "to open it. Tick them in the list as well, or choose a lead from the list. "
                "Nothing was changed."
            ),
        )

    held_rows = await design_workshop_viewers.viewer_rows(workshop_id)
    held = {row["userId"] for row in held_rows}
    current_lead = await _the_lead_among(workshop, held_rows)
    current_lead_id = current_lead["userId"] if current_lead else None

    added = [uid for uid in wanted if uid not in held]
    removed_rows = [row for row in held_rows if row["userId"] not in seen]

    designer_named = bool(str(getattr(workshop, "designerName", "") or "").strip())
    if designer_named and not wanted:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "Removing a workshop's designer altogether is not something this product can "
                "express: its report already carries their name, and leaving the workshop with "
                "nobody named would blank the cover page. Name the designer it is for instead — "
                "replacing them takes the outgoing designer's access away in the same act. "
                "Nothing was changed."
            ),
        )
    if current_lead_id is not None and current_lead_id not in seen and not lead:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"{current_lead.get('name') or current_lead.get('email') or 'That designer'} is the "
                f"designer this workshop's report names, so taking them off it means naming who "
                f"leads it instead rather than leaving it with nobody. Choose the designer whose "
                f"name the report should carry and save again. Nothing was changed."
            ),
        )

    if added:
        await design_workshops.assert_every_designer_may_be_named(set(added))

    for user_id in added:
        await design_workshops.attach_the_named_designer(
            workshop_id,
            user_id,
            granted_by_id=getattr(actor, "id", ""),
            creator_id=creator_id,
        )
    for row in removed_rows:
        # AFTER every add and never before. A workshop must never pass through a moment with no
        # designer on it at all: a failure in the gap would leave the outgoing designer removed, the
        # incoming one not yet granted, and the only account able to repair it an ADMIN the assigner
        # cannot escalate to from this screen.
        await design_workshop_viewers.remove_one_viewer(workshop_id, row["userId"])

    stages_written: list[str] = []
    if lead and lead != current_lead_id:
        stages_written = await move_the_leads_profile_onto_the_workshop(
            workshop_id, lead, actor=actor
        )
    elif not designer_named and not current_lead_id and wanted:
        # THE FIRST DESIGNER ON A WORKSHOP THAT HAS NEVER NAMED ONE, and without this branch the
        # ordinary case would be silently half-done: the grant lands, the report still names
        # whoever opened the workshop, and the only detector is a human reading the cover. The first
        # of the set is promoted, which is what ``named_designer_team`` does on both create doors
        # when a body names a team and no lead.
        stages_written = await move_the_leads_profile_onto_the_workshop(
            workshop_id, wanted[0], actor=actor
        )

    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    return {
        # READ BACK, NEVER COMPUTED FROM WHAT WE JUST DID — see the closing paragraph. It is also
        # the only way this screen can show a row a concurrent join-card redemption added while the
        # officer was choosing.
        "designers": await named_designer_rows(record),
        "designerName": getattr(record, "designerName", None),
        "stagesWritten": stages_written,
        # WHO LOST ACCESS, NAMED. The rows are the BASELINE snapshot rather than a re-read, because
        # by now they are gone and a re-read could only answer with their absence.
        "removedDesigners": [
            {
                "userId": row["userId"],
                "name": row.get("name") or "",
                "email": row.get("email") or "",
                "role": row.get("role") or "",
            }
            for row in removed_rows
        ],
    }


def assert_the_report_is_not_filed(workshop: Any) -> None:
    """422 if this workshop's report has been handed in. :data:`_CLOSED_STATUSES` is the rule.

    **EXTRACTED IN 0.0.12 BECAUSE A SECOND WRITE PATH NEEDED IT, AND A HAND-TYPED SECOND COPY OF
    THIS GUARD IS EXACTLY WHAT COST THIS REPOSITORY A RE-ATTRIBUTED REPORT ONCE ALREADY** — read
    :data:`_CLOSED_STATUSES`, which carries the whole account. :func:`reassign_designer` and
    :func:`set_named_designers` both call this; neither re-derives the set and neither re-words the
    sentence, so the two doors cannot tell a ministry administrator two different stories about one
    report.

    ``_LABELS`` AND NOT ``.title()``. A private name in another module, imported for the same reason
    ``assert_every_designer_may_be_named`` imports one: it is the ONE place this product decides how
    a status token is spoken, and the alternative is a second copy that drifts. ``.title()`` stood
    here until this line was corrected and rendered the token that matters most as "Pre_Submission"
    — a column name shown to a ministry administrator, in a sentence whose whole job is to say what
    is true of their report.
    """
    workshop_status = str(getattr(workshop, "status", "DRAFT") or "DRAFT")
    if workshop_status not in _CLOSED_STATUSES:
        return
    spoken = design_workshop_review_loop._LABELS.get(workshop_status, workshop_status)
    raise HTTPException(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        detail=(
            f"This workshop is {spoken}, and its report already names a designer. Changing it "
            f"now would re-attribute a filed document. Withdraw it from inspection first — or "
            f"reopen it — if the designer on it is genuinely wrong."
        ),
    )


async def move_the_leads_profile_onto_the_workshop(
    workshop_id: str, user_id: str, *, actor: Any
) -> list[str]:
    """Copy the LEAD designer's profile into the stages the registry says it belongs on.

    Answers with the stage keys that were written, sorted — which is what both callers hand back to
    the client as ``stagesWritten``.

    **EXTRACTED IN 0.0.12, AND THE EXTRACTION IS THE WHOLE POINT RATHER THAN TIDYING.**
    :func:`set_named_designers` performs the same act whenever the lead changes, and the two
    defects this block is written against are both defects of a SECOND COPY:

    **1. ``prefill_from_profile(user_id)`` — NEVER ``user_id or actor.id``.**
    ``seed_designer_prefill``'s docstring names that one plausible extra word as the defect: an
    administrator who picked a designer off a list and got their OWN name back into a ministry
    report, with completeness scoring 100% and the only detector being a human reading the cover.
    ``tests/test_workshop_oversight_unit.py`` asserts it against this function's source, which is
    why the call must stay spelled out here rather than behind a parameter.

    ⚠ **2. THE PROFILE ROW IS MINTED FIRST, AND THAT IS NOT A TIDY-UP.** ``prefill_from_profile``
    returns ``{}`` OUTRIGHT for an account with no ``DesignerProfile`` row — its account-name
    fallback for ``designerName`` lives after that early return and is unreachable from there.
    Profile rows are created lazily, by ``GET /designers/profile`` or by the sanction flow, so a
    designer minted through ``POST /api/users`` who has never opened their own profile screen has
    none. On the CREATE path an empty designer block is honest. **On THIS path it is not a blank.**
    Nothing empties the block: ``by_entity`` comes back empty, the loop below writes no stage at
    all, and the promoted ``designerName`` column KEEPS THE PREVIOUS DESIGNER'S NAME — so the route
    answers 200 with ``stagesWritten: []`` while the cover page, the .docx ``dc:creator`` and every
    list that sorts on that column go on attributing the workshop to the designer who was just
    replaced. ``get_or_create_profile`` is the same call ``sanction_orders.create_from_sanction``
    makes before it seeds, for the same reason, and the empty row it upserts is enough for the
    account-name fallback to fire.

    **3. The write goes through ``save_stage`` and NOT through a third ``dwstageentry.create``.**
    Four things a direct write would silently lose: the promoted-column recompute (so
    ``designerName`` lands on the column correctly), ``searchText`` (NULL means "not computed yet"
    and can never match, so the row would be invisible to search for the workshop's whole life), the
    version guard, and provenance attribution. ``tests/test_design_workshop_search_text.py`` fails
    on a third writer of that table and **this module deliberately adds none**.

    ``merge=True`` IS MANDATORY. A wholesale replace would delete every other field the designer has
    typed into stage 1 — and ``_coerce_promoted`` nulls a promoted column whose contributing entity
    came back blank, so the craft name, the cluster, the state, the district and both dates would go
    with it, under a 200 reading "Stage saved".

    ``replaceCollections=False`` so no collection is swept, and ``submit=False`` because a
    required-field 422 raised by an assignment is a stage nobody can fix from this screen.
    """
    from app.schemas.design_workshops import StageEntryIn, StageSaveIn
    from app.services.designers import PREFILL_MAP, get_or_create_profile, prefill_from_profile
    from app.services.stage_schema import stages

    await get_or_create_profile(user_id)
    values = await prefill_from_profile(user_id)
    targets = _prefill_targets()
    # Grouped by the stage and entity the REGISTRY says each key belongs to, so one save per stage
    # and never one per field.
    by_entity: dict[tuple[str, str], dict[str, Any]] = {}
    for _column, field_key in PREFILL_MAP:
        if field_key not in values:
            continue
        target = targets.get(field_key)
        if target is None:
            # A target the registry no longer declares. ``test_designer_prefill_contract`` fails on
            # this in CI; here it is skipped rather than raised, because an assignment that 500s on
            # a stale mapping is worse than one that lands the twenty keys it could resolve.
            logger.warning("designer prefill target %r is not in the stage registry", field_key)
            continue
        by_entity.setdefault(target, {})[field_key] = values[field_key]

    by_key = {s.key: s for s in stages()}
    for (stage_key, entity_key), data in sorted(by_entity.items()):
        spec = by_key.get(stage_key)
        if spec is None:  # pragma: no cover - the registry disagreeing with itself
            continue
        await design_workshops.save_stage(
            workshop_id,
            spec,
            StageSaveIn(
                entries=[StageEntryIn(entityKey=entity_key, data=data, merge=True)],
                replaceCollections=False,
                emptiedEntities=[],
                submit=False,
            ),
            actor,
        )
    return sorted({stage for stage, _entity in by_entity})


async def _the_designer_being_replaced(workshop: Any, *, incoming_id: str) -> dict[str, Any] | None:
    """The viewer row this reassignment is replacing, or ``None`` when that cannot be answered.

    **THE MATCH IS THE NAME BECAUSE THERE IS NO ID TO MATCH.** ``DesignWorkshop`` carries
    ``designerName`` and a set of viewer rows and nothing that says which of those rows is the lead;
    that is the arrangement :func:`reassign_designer`'s closing note defends ("a capacity row saying
    the designer is X beside a column saying the designer is Y is two answers to one question"). So
    the outgoing lead is resolved as the viewer whose own profile WOULD WRITE the name the workshop
    currently carries — the same value ``prefill_from_profile`` put there when they were named.

    **AMBIGUITY REMOVES NOBODY.** Two viewers whose prefills answer the same name, a blank
    ``designerName``, or a name no viewer's profile accounts for all return ``None``. Deleting a
    viewer row is an access revocation, and a revocation made on a guess is worse than a stale grant
    that the caller is TOLD about — which is what ``stillHaveAccess`` is for.

    **THE CREATOR IS NEVER A CANDIDATE** and cannot be: their access is ``createdById`` and they
    hold no viewer row (``attach_the_named_designer`` refuses to write one, ``_deduplicate`` drops
    them on the viewers PUT). The id test below is belt-and-braces for a row an older deployment
    may have written before that rule held.

    ``prefill_from_profile`` AND NOT ``user.name``, because ``designerName`` is promoted from the
    profile's ``displayName`` when there is one — a designer whose account name is "R. Mohanty" and
    whose Designer Page says "Rashmi Mohanty" has the second on the cover, and matching the first
    would find nobody and remove nobody on every workshop they have ever led.
    """
    rows = await design_workshop_viewers.viewer_rows(workshop.id)
    return await _the_lead_among(workshop, rows, exclude={incoming_id})


async def _the_lead_among(
    workshop: Any, rows: list[dict[str, Any]], *, exclude: set[str] | None = None
) -> dict[str, Any] | None:
    """Which of these viewer rows is the workshop's LEAD designer, or ``None`` when it is not knowable.

    **ONE RULE, TWO READERS, AND THAT IS WHY IT IS A FUNCTION.**
    :func:`_the_designer_being_replaced` asks it to decide whose access a reassignment takes away;
    :func:`named_designer_rows` asks it to draw ``isLead`` on the assignment screen. A second copy
    would be a screen that marks one person as the lead and a write that removes another, which is
    the worst possible pair of answers to one question.

    The match is THE NAME because there is no id to match, and that is the arrangement
    :func:`reassign_designer`'s closing note defends. ``prefill_from_profile`` AND NOT ``user.name``,
    because ``designerName`` is promoted from the profile's ``displayName`` when there is one — a
    designer whose account name is "R. Mohanty" and whose Designer Page says "Rashmi Mohanty" has the
    second on the cover, and matching the first would find nobody on every workshop they have led.

    **AMBIGUITY ANSWERS NOBODY.** Two viewers whose prefills answer the same name, a blank
    ``designerName``, or a name no viewer's profile accounts for all return ``None``. On the removal
    side a revocation made on a guess is worse than a stale grant the caller is TOLD about; on the
    display side an ``isLead`` stamped on a guess is a screen asserting something it does not know.

    **THE CREATOR IS NEVER A CANDIDATE** and cannot be: their access is ``createdById`` and they
    hold no viewer row (``attach_the_named_designer`` refuses to write one, ``_deduplicate`` drops
    them on the viewers PUT). The id test is belt-and-braces for a row an older deployment may have
    written before that rule held.

    ``exclude`` is the removal side's extra guard — the INCOMING designer, whose row this same call
    may have just created. It is a parameter rather than an assumption because the display side has
    nobody to exclude, and a shared helper that silently skipped an id would be answering a
    different question for each caller.

    ⚠ COST: one ``prefill_from_profile`` per viewer row. A workshop has a handful; the cap is
    ``MAX_DESIGN_WORKSHOP_VIEWERS`` (100), and the loop stops at nothing. If this ever becomes the
    slow half of the oversight read, the fix is a lead column on ``DesignWorkshopViewer`` — a
    migration and a written decision, not a cache here.
    """
    from app.services.designers import prefill_from_profile

    wanted = str(getattr(workshop, "designerName", "") or "").strip().casefold()
    if not wanted:
        return None
    creator_id = str(getattr(workshop, "createdById", "") or "")
    skip = {creator_id} | (exclude or set())

    matches: list[dict[str, Any]] = []
    for row in rows:
        if row["userId"] in skip:
            continue
        values = await prefill_from_profile(row["userId"])
        theirs = str(values.get("designerName") or row.get("name") or "").strip().casefold()
        if theirs and theirs == wanted:
            matches.append(row)
    return matches[0] if len(matches) == 1 else None


async def named_designer_rows(workshop: Any) -> list[dict[str, Any]]:
    """Who may open this workshop, and which of them the report names. **THE READ THE SCREEN OWED.**

    Five keys per row — ``userId``, ``name``, ``email``, ``role``, ``isLead`` — over
    ``design_workshop_viewers.viewer_rows``, which is the SERVICE and never
    ``db.designworkshopviewer.*``: ``tests/test_workshop_oversight_unit.py`` runs an AST sweep over
    the three oversight files forbidding the latter, and the reason is in this module's header.

    ── WHY THIS EXISTS AT ALL ────────────────────────────────────────────────────────────────────

    Until 0.0.12 ``GET /design-workshop-oversight/{id}`` answered ``designerName`` — a STRING — and
    no ids at all, so the one screen that decides who a workshop is for could not see who currently
    holds it. The assigner could add and could not compare, could not pre-tick a picker and could
    not take anybody off. The information was on the wire exactly once, in the ``PUT …/designer``
    RESPONSE (``removedDesigner``/``stillHaveAccess``), i.e. only to somebody who had already
    changed something.

    ── AND WHY IT IS NOT ``GET /design-workshops/{id}/viewers`` WIDENED ──────────────────────────

    That route is ``require_admin`` and widening it would widen the table that confers STAGE
    WRITES — ``load_workshop_or_404(for_edit=True)`` reads the same relation. A read on THIS prefix
    is the smaller blast radius and is the same trade ``GET /design-workshop-oversight/designers``
    already documents: two doors, one query, two payloads.

    **THE CREATOR IS NOT IN HERE**, because they hold no viewer row — their access is
    ``createdById``. So an empty list means "nobody but whoever opened it", never "nobody at all",
    and the screen over this has to say so. ``viewer_rows``' own docstring makes the same point.
    """
    rows = await design_workshop_viewers.viewer_rows(getattr(workshop, "id", ""))
    lead = await _the_lead_among(workshop, rows)
    lead_id = lead["userId"] if lead else None
    return [
        {
            "userId": row["userId"],
            "name": row.get("name") or "",
            "email": row.get("email") or "",
            "role": row.get("role") or "",
            # A BOOLEAN AND NOT AN ABSENCE. Every row carries the key, so a client can tell "this
            # person is not the lead" from "this server does not answer the question" — the second
            # of which is what an omitted key on a stale deployment means.
            "isLead": row["userId"] == lead_id,
        }
        for row in rows
    ]


# --------------------------------------------------------------------------------------
# The artisan roster — the one assignment on this screen that had no read and no removal
# --------------------------------------------------------------------------------------

#: How many roster rows one read answers with.
#:
#: A CEILING WITH A SENTENCE ON THE WIRE, exactly as :data:`ELIGIBLE_OFFICER_LIMIT` carries one. The
#: pro-forma is fifteen rows of typing and a workshop's roster is fifteen to forty people, so this is
#: not a limit anybody meets by working — but a list that quietly stops is indistinguishable from a
#: workshop with nothing on it, which is this repository's most repeated bug class, and the ``take``
#: is what bounds the work one request can ask for.
ROSTER_TAKE = 200


async def linked_artisan_rows(workshop_id: str) -> dict[str, Any]:
    """The artisans filed against this workshop, newest first, plus whether the list was cut.

    ══ WHY THIS READ EXISTS, AND WHY IT IS NOT ``GET /artisans?designWorkshopId=`` ═════════════════

    That filter exists (``routes/artisans.py``) and **no client anywhere reads it**, so the roster
    an officer has just imported has never been visible on any screen in this product — which is why
    the removal gap below went unnoticed: there was no list to remove anybody FROM. Pointing this
    panel at that route instead would have put the oversight screen on a general record endpoint
    whose payload, whose visibility clause and whose PII rules are owned by another feature and
    change for reasons that have nothing to do with supervision.

    ══ SIX KEYS, AND THE ABSENCES ARE THE POINT ═══════════════════════════════════════════════════

    ``id``, ``name``, ``place``, ``craftName``, ``status``, ``createdAt``. **NO ``aadhaarNumber``, NO
    ``pehchanCardNumber``, NO ``phone``, NO ``address``, NO ``dateOfBirth``.** An officer already
    reads unmasked Aadhaar through ``GET /artisans/{id}`` — ``artisans._may_read_full_aadhaar`` is
    ``has_rank(user, "PROFESSOR")`` and all three ministry posts clear it — so this is not a
    capability they lack. It is the frontend contract's own rule: *never render a regulated identity
    number in a list, a card or an export view.* This panel answers "who is on this workshop", and
    the answer to that question does not need a single regulated column. Same construction, same
    argument, as ``assignable_designers_payload``: two doors, one table, two payloads.

    ``status`` IS CARRIED because an imported artisan is created PENDING and enters the review queue
    — the header of this module measures that and says why — so an officer looking at a roster of
    fifteen PENDING rows is looking at the normal state of a fresh import and must not read it as a
    fault.
    """
    rows = await db.artisan.find_many(
        where={"designWorkshopId": workshop_id},
        include={"craft": True},
        order={"createdAt": "desc"},
        take=ROSTER_TAKE + 1,
    )
    truncated = len(rows) > ROSTER_TAKE
    rows = rows[:ROSTER_TAKE]
    return {
        "artisans": [
            {
                "id": row.id,
                "name": row.name,
                "place": row.place,
                "craftName": getattr(getattr(row, "craft", None), "name", None),
                "status": str(getattr(row.status, "value", row.status)),
                "createdAt": row.createdAt.isoformat() if row.createdAt else None,
            }
            for row in rows
        ],
        # REPORTED RATHER THAN INFERRED FROM THE LENGTH, for the reason the designer directory gives
        # beside its own flag: a client inferring "cut" from a hard-coded copy of the ceiling is
        # sound only while every filter is inside the query, and is exactly what breaks the day one
        # is not.
        "truncated": truncated,
    }


async def unlink_artisan_from_workshop(workshop_id: str, artisan_id: str) -> bool:
    """Take one artisan off this workshop's roster. Answers whether a row actually changed.

    ══ IT UNFILES, IT DOES NOT DELETE, AND THOSE ARE NOT NEARLY THE SAME ACT ═══════════════════════

    ``Artisan.designWorkshopId`` is a NULLABLE FK with ``onDelete: SetNull`` — a link, not
    ownership — so clearing it leaves the artisan's record, its photographs, its products, its tools
    and its interviews exactly as they were. That is the whole of what an officer is entitled to
    change here: they did not author the record, ``Artisan.createdBy`` is ``Restrict`` and is
    untouched, and a roster correction must never be a way to destroy a regulated person-record.
    ``schemas/records.assert_payload_workshop`` already documents the same clearing on the artisan
    form — *"an explicit ``None`` unfiles the record and is always allowed"* — so this is that rule
    reached from the screen that actually holds the roster.

    ⚠ **THE STAGE-3 PARTICIPANT ROW IS DELIBERATELY LEFT STANDING, AND THE SCREEN SAYS SO.**
    ``services/artisan_import`` writes a second thing per imported artisan: a ``DwStageEntry`` row
    under ``WORKSHOP_PLAN_PARTICIPANTS_OPENING`` / ``participant``. Deleting it from here would be a
    STAGE WRITE performed by an officer — on a report that may be in PRE_SUBMISSION under an
    inspector's eye, through the same ``save_stage`` path whose ``NEEDS_REVISION`` arm silently
    RE-SUBMITS a sent-back report and burns a round. This module refuses stage writes except the one
    the lead's prefill needs, and it is not about to grow a second for a roster tidy-up. **These are
    two deletions and nothing links them**; the participant row belongs to the designer's stage 3
    and is removed there. Whether it SHOULD be removed with the link is a product decision that has
    not been taken, so the honest answer is to do one thing, say what was not done, and let the
    officer act.

    ``update_many`` AND NOT ``update``: the ``designWorkshopId`` predicate is what stops an artisan
    being unfiled from a workshop they were never on — a bare ``update`` on the primary key would
    clear the link to a DIFFERENT workshop if a stale screen sent the wrong pair. It also answers a
    COUNT, so "that artisan is already off this roster" is ``False`` rather than a 500 from
    ``RecordNotFoundError``.

    NO TOMBSTONE, for the reason every removal in this feature gives: there is no decision here to
    audit beyond the fact of the change, and ``DwArtisanImport`` — the one durable ledger in this
    feature — already records how each row arrived.
    """
    changed = await db.artisan.update_many(
        where={"id": artisan_id, "designWorkshopId": workshop_id},
        data={"designWorkshopId": None},
    )
    return bool(changed)


# THE INVARIANT, CHECKED AT IMPORT RATHER THAN HOPED FOR. If the capacity map and the Postgres enum
# ever disagree, one capacity becomes storable and unvalidatable — a row on a workshop that neither
# the AD lookup nor the RD lookup finds, which is the exact failure the enum was chosen to prevent.
# Failing at import is the right blast radius: the API does not boot, rather than booting with a
# capacity that means nothing.
#
# The enum's members are read off the generated Prisma client rather than retyped, so this is an
# assertion about the SCHEMA and not this file agreeing with itself. A deployment whose client
# predates the migration simply skips the check — an absent enum is a missing ``prisma generate``,
# which every query in this module will report far more loudly than an assert here could.
try:  # pragma: no cover - an environment check, not a runtime state
    from prisma.enums import DwOversightCapacity as _Capacity

    _DECLARED = {member.value for member in _Capacity}
except Exception:  # noqa: BLE001 - see above
    _DECLARED = set(OVERSIGHT_CAPACITY_ROLES)
if set(OVERSIGHT_CAPACITY_ROLES) != _DECLARED:  # pragma: no cover
    raise RuntimeError(
        "OVERSIGHT_CAPACITY_ROLES and the DwOversightCapacity enum must name the same capacities; "
        f"the enum has {sorted(_DECLARED)} and this module has "
        f"{sorted(OVERSIGHT_CAPACITY_ROLES)}. A third capacity is an ALTER TYPE in its own "
        "migration file PLUS an entry here — see the header of this module."
    )
