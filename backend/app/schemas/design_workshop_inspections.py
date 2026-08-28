"""The one body the inspection scope accepts: the complete set of accounts inspecting one workshop.

There is no add body and no remove body, and that is deliberate rather than unfinished — see
:mod:`app.services.design_workshop_inspectors` for why the write is a whole-set replace.

**AND THERE IS NO BODY AT ALL ON THE INSPECTOR'S OWN SIDE**, which is the thing worth noticing in a
file this short. Every route an INSPECTOR can reach is a GET; this module holds exactly one input
model and it belongs to the ADMIN screen that assigns inspections. If a second class ever appears
here carrying something an inspector POSTs, the scope has stopped being read-only and the header of
the service module is the argument to read before writing it.
"""

from pydantic import BaseModel, Field

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
