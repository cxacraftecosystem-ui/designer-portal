"""The INSPECTOR's read-only surface, and the admin screen that assigns inspections.

Five routes. **Every route an inspector can reach is a GET**, and that is not an accident of what
has been built so far — it is the feature. See ``app/services/design_workshop_inspectors.py`` for
the argument in full; this module is the wire, and the two things it adds are the doors.

=======================================================================================
WHY THIS IS ITS OWN ROUTER ON ITS OWN PREFIX
=======================================================================================

``/design-workshops`` is already shared by two routers and carries ``GET /{workshop_id}``, which
swallows any literal path mounted after it (see the note above ``design_workshop_viewers`` in
``app/api/router.py``). That is the ordering hazard, and it is the lesser of the two reasons.

The deciding reason is the one ``design_ratings`` and ``design_workshop_access`` both give for their
own prefixes: **the caller of every route in this file is, by definition, somebody
``load_workshop_or_404`` turns away.** An inspector is not in ``DESIGN_WORKSHOP_ROLES``, so that
loader 404s them. A route sharing that prefix invites the next reader to "fix" the inconsistency by
widening the shared loader — and widening it grants STAGE WRITES.

THAT LAST CLAUSE USED TO REST ON "``load_workshop_or_404(for_edit=True)`` performs no role check at
all", AND IT NO LONGER DOES (corrected 2026-09-03). Since that date the loader honours a
``DesignWorkshopViewer`` row only for an account inside ``DESIGN_WORKSHOP_ROLES``, so an inspector is
now refused twice over rather than once. The conclusion is unchanged and so is the guard rail:
``for_edit=True`` still carries no role check of its own — all it changes is that a deleted workshop
answers 409 instead of 404 — so the reader who "fixes" the inconsistency by adding INSPECTION_ROLES
to that set, or by hanging a fourth arm off the loader, is granting stage writes and not reads. The
prefix boundary is a guard rail, not a filing decision.

=======================================================================================
THE TWO DOORS
=======================================================================================

* :func:`require_inspector` — the inspector's own read surface. **403 for admins too**, naming the
  route they actually want; ``assert_inspection_surface`` argues why.
* ``Depends(require_admin)`` — the administration of who inspects what. THE INSPECTED MUST NOT
  CHOOSE THE INSPECTOR, so there is no route here through which a designer can add, remove or even
  suggest one, and the workshop's creator gets no say at all.

**REGISTRATION ORDER INSIDE THIS MODULE IS LOAD-BEARING.** ``GET /eligible-inspectors`` is declared
before ``GET /{workshop_id}``, which matches it perfectly well and would answer 404 "Record not
found" — the same trap that once left the admin's designer picker empty on a server where the route
existed. FastAPI matches in declaration order, so the literal path is declared first.
"""

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

# THE STAGE SERIALISER IS IMPORTED RATHER THAN COPIED, and it is a PRIVATE name in another route
# module, which is a smell worth stating out loud instead of quietly living with.
#
# The alternative is worse. ``_stages_payload`` is registry-aware — it reads entity CARDINALITY out
# of the stage registry and has a dedicated branch for the reserved ``_custom`` entity key — and its
# own docstring records what a second implementation gets wrong: the custom row falls through to the
# collection arm and comes back as a phantom repeating entity, on every stage that has custom
# answers, which both clients render as a table of one row nobody can delete. A private import is a
# smaller problem than shipping that bug to a second reader of the same rows, and an inspector
# reading a DIFFERENT shape from the designer who typed it defeats the point of an inspection.
#
# NEITHER OF THESE AUTHORISES ANYTHING. Both are pure serialisers over rows this route has already
# decided the caller may read; the authorisation is `load_inspectable_workshop_or_404`, above them.
# The clean fix is to promote both to ``services/design_workshops`` beside ``workshop_summary``,
# which this wave deliberately does not do because that file is being edited by another workstream.
from app.api.routes.design_workshops import _provenance_maps, _stages_payload
from app.core.db import db
from app.core.deps import get_current_user, require_admin
from app.schemas.design_workshop_inspections import DesignWorkshopInspectorsIn
from app.services.concurrency import gather_reads
from app.services.custom_sections import load_definition_or_empty
from app.services.design_workshop_inspectors import (
    assert_inspection_surface,
    eligible_inspectors,
    inspectable_by_clause,
    inspector_rows,
    load_inspectable_workshop_or_404,
    replace_inspectors,
)
from app.services.design_workshops import entry_rows, workshop_completeness, workshop_summary
from app.services.entry_provenance import resolve_display_names
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import contains
from app.services.stage_schema import registry_version

router = APIRouter(prefix="/design-workshop-inspections", tags=["design-workshops"])


async def require_inspector(current_user: Any = Depends(get_current_user)) -> Any:
    """The inspector's own read surface: the INSPECTOR tier and nobody else.

    A dependency rather than a call inside each handler, so that a route added to this file without
    one is visible as a missing ``Depends`` rather than as a missing line in a body.

    ⚠ **LIVES HERE AND NOT IN ``core/deps.py``**, which is where ``require_admin``,
    ``require_designer`` and the rest of the ladder's doors live, and that asymmetry is temporary
    rather than principled: ``deps.py`` is owned by another workstream in this wave. If you are the
    person consolidating them, move :data:`INSPECTION_ROLES` and this function together — splitting
    them is how "who is an inspector" comes to have two answers.
    """
    assert_inspection_surface(current_user)
    return current_user


async def _workshop_or_404(workshop_id: str) -> Any:
    """The workshop, for an ADMINISTRATOR.

    Deliberately NOT ``load_inspectable_workshop_or_404``: that helper answers "may THIS INSPECTOR
    read it", and every caller of the two administration routes is an admin, for whom the answer is
    always yes — including for a soft-deleted workshop, which an admin has to be able to administer
    in order to restore it with its inspection intact. The sibling viewers router makes the same
    split for the same reason.
    """
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record


# --------------------------------------------------------------------------------------
# The admin's screen: who inspects what
#
# DECLARED FIRST because of the literal path below. See the module docstring.
# --------------------------------------------------------------------------------------


@router.get("/eligible-inspectors")
async def list_eligible_inspectors(
    search: str | None = Query(None, max_length=120),
    _: Any = Depends(require_admin),
) -> dict[str, Any]:
    """The accounts that may be assigned an inspection at all.

    Not the user directory narrowed by the client. The eligible set is a SET of roles and not a rank
    threshold, and it further excludes anyone the platform allow-list has rejected or suspended —
    accounts that cannot sign in, for whom an inspection row would mean this screen saying somebody
    is inspecting while they are shown a refusal at the door. All of it is a rule the client cannot
    see and would drift from within one release.

    ``truncated`` in the answer says the list was cut. Both clients must say so when it is true and
    say nothing when it is false; that is the whole contract, and an empty list with no explanation
    is this repository's most repeated bug class.
    """
    return await eligible_inspectors(search=search)


@router.get("/{workshop_id}/inspectors")
async def list_inspectors(workshop_id: str, _: Any = Depends(require_admin)) -> dict[str, Any]:
    """Everyone assigned to inspect this workshop.

    An empty list means NOBODY IS INSPECTING IT — the literal truth, unlike the viewers list, where
    an empty answer still leaves the creator holding the workshop through ``createdById``. Nobody
    holds an inspection by any route other than a row in this table.
    """
    await _workshop_or_404(workshop_id)
    return {"inspectors": await inspector_rows(workshop_id)}


@router.put("/{workshop_id}/inspectors")
async def set_inspectors(
    workshop_id: str,
    payload: DesignWorkshopInspectorsIn,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Replace the whole inspection set, and answer with it as it now stands.

    **ADMIN ONLY, AND THAT INCLUDES THE WORKSHOP'S OWN CREATOR.** The inspected must not choose the
    inspector; if a designer could put somebody on their own workshop as its inspector — or take
    somebody off it — the inspection is worth nothing. There is deliberately no "suggest an
    inspector" route either, because a suggestion an admin rubber-stamps is the same thing wearing a
    queue.

    REPLACES. There is no add route and no remove route: taking somebody off is sending the list
    without them. So a client that posts only what it just ticked has silently ended everybody
    else's inspection, which is why the body is named for the whole set and why the answer is the
    set as the SERVER now holds it rather than an echo of what was sent — two admins on the same
    screen must not each end up believing their own payload was the outcome.

    Idempotent: saving an unchanged screen writes nothing. An unknown, ineligible, barred or
    already-on-the-workshop id refuses the ENTIRE call with a 422 naming the account and the remedy,
    never a silent skip — see ``services/design_workshop_inspectors``.
    """
    await _workshop_or_404(workshop_id)
    inspectors = await replace_inspectors(
        workshop_id, payload.userIds, assigned_by_id=current_user.id
    )
    return {"inspectors": inspectors}


# --------------------------------------------------------------------------------------
# The inspector's own surface. EVERY ROUTE BELOW IS A GET, AND THAT IS THE FEATURE.
# --------------------------------------------------------------------------------------


@router.get("")
async def list_inspectable_workshops(
    page: int = 1,
    pageSize: int = 20,
    search: str | None = Query(None, max_length=120),
    current_user: Any = Depends(require_inspector),
) -> dict[str, Any]:
    """The design & prototype workshops this inspector has been assigned, newest first.

    **AN INSPECTOR WITH NO INSPECTION ROW SEES AN EMPTY PAGE, AND THAT IS THE WHOLE SCOPE.** There
    is no "all workshops" arm, no rank fallback and no ``createdById`` arm — an inspector creates
    nothing — so this list has exactly one source and there is no second way in to reason about.
    ``tests/test_dw_inspector_scope.py`` asserts the empty case directly, because a scope whose
    zero state is untested is a scope that will quietly widen.

    THE LIST IS HALF THE FEATURE, the same lesson ``design_workshop_viewers`` records: a scope the
    list does not honour tells its holder that a workshop exists (they can open it by id) and
    simultaneously that it does not (it is absent from every list they can reach). Nothing in either
    client navigates to a workshop by typed id.

    ``deletedAt: None`` because a soft-deleted workshop is a 404 for everyone but an admin, and an
    inspector is not an admin and has no restore button. Leaving it out would list workshops the
    detail route then refuses.

    THE SCOPE IS AND-COMPOSED AND THE SEARCH IS NOT. The search box takes ``where["OR"]``; writing
    the scope there too is two assignments to one key, the later silently wins, and either the
    search stops narrowing or the scope vanishes the moment somebody types. Same warning, same
    reason, as ``services/records.owned_or_granted_where``.
    """
    where: dict[str, Any] = {"deletedAt": None}
    term = (search or "").strip()
    if term:
        where["OR"] = [
            {"title": contains(term)},
            {"craftName": contains(term)},
            {"clusterName": contains(term)},
            {"workshopCode": contains(term)},
        ]
    where.setdefault("AND", []).append(inspectable_by_clause(current_user.id))

    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    # Count and page together: neither reads the other, and in series the count was a whole round
    # trip added to every page of an inspector's list — a cross-region one when this was written,
    # a co-located one or two milliseconds since 2026-09-02 (``services/concurrency.py``). One wait
    # instead of two is the claim, and it survives the move. The ORDER is deliberately
    # left exactly as it was rather than routed through ``records.count_and_page``, which would add
    # an ``id`` tiebreak and quietly change which rows land on which page of an existing client.
    total, rows = await gather_reads(
        db.designworkshop.count(where=where),
        db.designworkshop.find_many(
            where=where, skip=skip, take=clean_size, order={"createdAt": "desc"}
        ),
    )
    return page_payload([workshop_summary(r) for r in rows], total, clean_page, clean_size)


@router.get("/{workshop_id}")
async def read_workshop_under_inspection(
    workshop_id: str, current_user: Any = Depends(require_inspector)
) -> dict[str, Any]:
    """One workshop under inspection, with every stage's data and its completeness scores.

    **THE READ-ONLY TWIN OF ``GET /design-workshops/{workshop_id}``, and the differences are the
    point rather than an omission.** What is deliberately absent from this payload:

    * ``transcripts``. The designer's read fills that key from ``owned_or_granted_where``, which
      admits an account below professor only for media it uploaded, media whose owner granted it a
      ``DataAccessGrant``, or media tagged to a workshop it holds through ``DesignWorkshopViewer`` /
      ``createdById``. An inspector holds none of those, so calling it here would cost a query to
      produce an empty list — and, worse, would put this route on the media path at all, so that the
      next person widening that predicate widens this surface without noticing. It is not called.
    * Anything that writes. There is no ``for_edit``, no PATCH twin, no stage save and no report
      route on this prefix. ``load_inspectable_workshop_or_404`` takes no ``for_edit`` parameter, so
      there is no argument this request could carry that turns the read into a write.

    Whether an inspector SHOULD see the workshop's photographs and recordings is an owner's decision
    that has not been made. It is deliberately not made here by accident: today the answer is no,
    stated in one place, rather than yes by inheritance from a predicate written for co-designers.

    Provenance names ARE resolved, because "who wrote this field" is most of what an inspection is
    for, and the ids without them are unreadable. ``resolve_display_names`` is one query for the
    whole workshop and reads only ``User.name``.
    """
    record = await load_inspectable_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)
    definition = await load_definition_or_empty(workshop_id)
    summary = workshop_summary(record)
    summary["stages"] = _stages_payload(entries)
    await resolve_display_names(_provenance_maps(summary["stages"]))
    summary["completeness"] = workshop_completeness(entries, definition=definition)
    summary["schemaVersion"] = registry_version()
    summary["customSchemaVersion"] = definition.version
    # SAID ON THE WIRE RATHER THAN INFERRED FROM THE URL, because both clients will eventually render
    # this payload through the same screen as the designer's read, and a screen that cannot tell the
    # two apart will offer a Save button that the API answers 404 to. One boolean is cheaper than the
    # bug report.
    summary["readOnly"] = True
    return summary
