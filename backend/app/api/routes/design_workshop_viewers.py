"""Admin routes for deciding who, besides the creator, may see a design & prototype workshop.

Three routes, all admin-only. The rule they enforce — a grant is read plus stage writes, never
delete and never re-granting — and the reasoning behind the whole feature live in
``app/services/design_workshop_viewers.py``; this module is only the wire.

**WHY THIS IS ITS OWN MODULE RATHER THAN THREE MORE ROUTES IN ``design_workshops.py``.** That file
is a thousand lines of one designer's workflow — stages, references, transcripts, reports — every
route of which is reached by the designer running the workshop. These three are reached only by an
administrator, are gated by a different dependency, and share no helper with anything there. Kept
apart, "who may administer viewers" is a question this file answers on its own.

**REGISTRATION ORDER IS LOAD-BEARING, and it is the one thing about this module that can be broken
from outside it.** ``design_workshops.router`` carries ``GET /design-workshops/{workshop_id}``,
which matches ``/design-workshops/eligible-viewers`` perfectly well and answers 404 "Record not
found". So this router MUST be included before that one in ``app/api/router.py`` — a note lives
there too, and ``test_design_workshop_viewers`` asserts the outcome rather than the ordering, so
the failure surfaces as "the picker is empty" instead of a passing suite.
"""

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import require_admin
from app.schemas.design_workshop_viewers import DesignWorkshopViewersIn
from app.services.design_workshop_viewers import (
    eligible_viewers,
    replace_viewers,
    viewer_rows,
)

router = APIRouter(prefix="/design-workshops", tags=["design-workshops"])


async def _workshop_or_404(workshop_id: str) -> Any:
    """The workshop, for an administrator.

    Deliberately NOT ``load_workshop_or_404``: that helper answers "may THIS caller see it", and
    every caller here is already an admin, for whom the answer is always yes — including for a
    soft-deleted workshop, which an admin has to be able to administer in order to restore it with
    its team intact.
    """
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record


@router.get("/eligible-viewers")
async def list_eligible_viewers(
    search: str | None = Query(None, max_length=120),
    _: Any = Depends(require_admin),
) -> dict[str, Any]:
    """The accounts that may be given access to a design workshop at all.

    Not the user directory narrowed by the client. The eligible set is a SET of roles and not a
    rank threshold, and it further excludes anyone who cannot sign in — designers whose EMPANELMENT
    has lapsed, and accounts of any role that the platform ALLOW-LIST has rejected or suspended.
    Two tables, and the second one used to be missing: eligibility was decided from the designer
    roster alone, so a suspended designer was offered here, accepted with a 200 by the PUT, and
    refused at every sign-in. All of it is a rule the client cannot see and would drift from within
    one release. The drift shows up as an admin granting access that the next sign-in refuses, with
    nothing on screen saying why.

    ``search`` matches name OR email, case-insensitively, and is applied by the SERVER inside the
    same query as the eligibility rule. It is not a convenience over a list the client already
    holds: the answer is capped, and the cap is reached on a real repository, so before this
    parameter existed an eligible colleague whose name sorted past the cut could not be reached from
    either client at all — with nothing on screen distinguishing that from never having been
    empanelled. A client that filtered the capped list on its own would search only the part of the
    alphabet that fitted, which is the same bug wearing a search box.

    ``truncated`` in the answer says the list was cut. Both clients must say so when it is true and
    say nothing when it is false; that is the whole contract, and an empty list with no explanation
    is this repository's most repeated bug class.
    """
    return await eligible_viewers(search=search)


@router.get("/{workshop_id}/viewers")
async def list_viewers(workshop_id: str, _: Any = Depends(require_admin)) -> dict[str, Any]:
    """Everyone with a viewer row on this workshop.

    The CREATOR is not in this answer and must not be read as absent from the workshop: their
    access comes from ``createdById``. An empty list means "nobody but the creator".
    """
    await _workshop_or_404(workshop_id)
    return {"viewers": await viewer_rows(workshop_id)}


@router.put("/{workshop_id}/viewers")
async def set_viewers(
    workshop_id: str,
    payload: DesignWorkshopViewersIn,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Replace the whole viewer set, and answer with it as it now stands.

    REPLACES. There is no add route and no remove route: taking somebody off is sending the list
    without them. So a client that posts only what it just ticked has silently revoked everybody
    else, which is why the body is named for the whole set and why the answer is the set as the
    SERVER now holds it rather than an echo of what was sent — two admins on the same screen must
    not each end up believing their own payload was the outcome.

    Idempotent: saving an unchanged screen writes nothing. Naming the creator is harmless. An
    unknown or ineligible id refuses the entire call with a 422 naming the account, never a silent
    skip — see ``services/design_workshop_viewers``.
    """
    record = await _workshop_or_404(workshop_id)
    viewers = await replace_viewers(
        workshop_id,
        payload.userIds,
        creator_id=record.createdById,
        granted_by_id=current_user.id,
    )
    return {"viewers": viewers}
