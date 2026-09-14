"""The bodies the inspection scope accepts: the admin's inspector set, and an officer's note.

There is no add body and no remove body for the inspector set, and that is deliberate rather than
unfinished — see :mod:`app.services.design_workshop_inspectors` for why the write is a whole-set
replace.

**"AND THERE IS NO BODY AT ALL ON THE INSPECTOR'S OWN SIDE" — CORRECTED 2026-09-13, NOT DELETED.**

This module's header used to say that, and went on: *"Every route an INSPECTOR can reach is a GET;
this module holds exactly one input model and it belongs to the ADMIN screen that assigns
inspections. If a second class ever appears here carrying something an inspector POSTs, the scope
has stopped being read-only and the header of the service module is the argument to read before
writing it."* Two such classes now exist, the service module's header WAS read before they were
written, and the sentence is corrected rather than removed because the property it was defending is
the one that still holds and is the whole point of the tier:

    AN INSPECTOR STILL CANNOT TOUCH THE DESIGNER'S CONTENT.

:class:`DwInspectionFeedbackIn` and :class:`DwInspectionSendBackIn` carry A NOTE and nothing else —
no stage data, no entry id, no status. The routes that accept them write one row of
``DwInspectionFeedback``, three cache columns on the workshop and one audit entry, and
``InspectionWritePlan`` refuses every other table BY CONSTRUCTION. The six write doors on
``/design-workshops`` still answer 403 to an INSPECTOR before the database, and the inspector's
read-only loader still takes no argument that turns a read into a write. What changed is that an
inspection is now a read AND A NOTE, which is what the owner's requirement asks for: until this
landed an inspector could record nothing at all, which made the tier a viewer with extra steps.

TWO CLASSES AND NOT ONE WITH A ``sendBack`` BOOLEAN, for the record page's own rule — "ONE BUTTON
PER STATUS, NEVER ONE BUTTON PER INTENTION". Adding a suggestion to an open round and sending a
report back to its designers are different acts with different consequences, and a boolean on one
body is how the second happens by accident.
"""

from pydantic import BaseModel, Field

from app.schemas.design_workshop_review_loop import MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS
from app.services.design_workshop_inspectors import MAX_DESIGN_WORKSHOP_INSPECTORS


class DesignWorkshopInspectorsIn(BaseModel):
    """PUT the full set of accounts inspecting this design workshop (replaces the existing set).

    ``userIds`` alone, matching ``DesignWorkshopViewersIn`` — there is no level to send, because an
    inspection is one thing and not a ladder. It is READ, and the absence of a level column is what
    stops somebody adding a rung to it later.

    An EMPTY list is a legitimate body meaning "nobody is inspecting this workshop", not a missing
    field, so it defaults to empty rather than being required. Unlike the viewers list, an empty
    answer here is the literal truth: there is no creator quietly holding the access off to one side.

    The cap is imported from the service rather than restated, so the number the wire enforces and
    the number the validation reasons about cannot drift apart.
    """

    userIds: list[str] = Field(default_factory=list, max_length=MAX_DESIGN_WORKSHOP_INSPECTORS)


class DwInspectionFeedbackIn(BaseModel):
    """POST one correction suggestion about a workshop under inspection.

    ``note`` IS REQUIRED AND NEVER BLANK, and ``min_length=1`` is not the whole of that rule: a body
    of three spaces passes it and is refused in the service, with the sentence
    ``api/routes/review.py`` already gives the six record types when a send-back carries no comment.
    Both halves are deliberate — the wire refuses the empty string cheaply, and the service refuses
    whitespace where the value is stripped, so neither can be the only guard.

    THE CAP IS IMPORTED FROM THE PLAN MODULE rather than restated, so the number the wire enforces
    and the number the write plan reasons about cannot drift apart. That is the arrangement
    ``MAX_DESIGN_WORKSHOP_INSPECTORS`` has with the service module above.

    ``stageKey`` IS OPTIONAL AND NULL IS A REAL ANSWER — most suggestions are about the report as a
    whole. It is validated against the stage registry before the write; ``fieldKey`` deliberately is
    not, because an officer can legitimately name a field a client one release ahead is showing and
    refusing the row would lose the suggestion rather than the typo.

    ``recordedAt`` IS THE DEVICE'S OWN MOMENT, as an ISO string, for an officer who wrote the
    suggestion in a courtyard a fortnight before the handset synced. Null when the suggestion was
    filed straight against this server, where ``createdAt`` is the same moment and copying it here
    would later read as "a device reported this". A clock more than the shared skew ahead of this
    server is REFUSED rather than corrected — see the plan module.
    """

    note: str = Field(min_length=1, max_length=MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS)
    stageKey: str | None = Field(default=None, max_length=64)
    fieldKey: str | None = Field(default=None, max_length=120)
    recordedAt: str | None = Field(default=None, max_length=40)


class DwInspectionSendBackIn(DwInspectionFeedbackIn):
    """POST the suggestion that SENDS THE REPORT BACK to its designers (status NEEDS_REVISION).

    **THE SAME FIELDS, AND THE SUBCLASS IS THE POINT RATHER THAN A SHORTCUT.** A send-back files a
    correction suggestion like any other — it is the one carrying ``sentBack: true`` — so a second
    hand-written body with the same four fields could drift from this one in exactly the direction
    that matters: a cap, or a mandatory note, enforced on one route and not the other.

    WHAT MAKES IT A DIFFERENT ROUTE IS THE CONSEQUENCE, NOT THE SHAPE. This one moves a status,
    writes a ReviewLog row and puts a fortnight of somebody's work back on their desk. It is
    refused unless the workshop is in Pre-submission, and the comment is mandatory, for the reason
    ``api/routes/review.py`` states: a send-back with no sentence tells a designer only that a
    fortnight of work is wrong.
    """
