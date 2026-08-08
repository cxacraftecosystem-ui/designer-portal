"""The one body this feature accepts: the complete set of accounts that may see one workshop.

There is no add body and no remove body, and that is deliberate rather than unfinished — see
:mod:`app.services.design_workshop_viewers` for why the write is a whole-set replace.
"""

from pydantic import BaseModel, Field

#: How many viewers one workshop may be given in a single call.
#:
#: A Design & Prototype Development Workshop is run by four people — two designers, a master
#: craftsperson and a reviewing officer — so a hundred is not a limit anybody will meet by working.
#: It is here because every other list on this wire carries one (``MAX_ASSIGNEES`` in schemas/tasks,
#: ``WORKSHOP_REQUEST_MAX`` in schemas/access) and an unbounded list is a free way to make the
#: server do arbitrary work: the validation below reads every id out of the user table before it
#: writes anything, so the cost of one request is the caller's to choose unless it is capped here.
MAX_DESIGN_WORKSHOP_VIEWERS = 100


class DesignWorkshopViewersIn(BaseModel):
    """PUT the full set of accounts that may see this design workshop (replaces the existing set).

    ``userIds`` alone, matching ``WorkshopAssignmentIn`` — there is no level to send, because a
    grant here is one thing and not a ladder (read plus stage writes, never delete and never
    re-granting). An EMPTY list is a legitimate body meaning "nobody but the creator", not a
    missing field, so it defaults to empty rather than being required.
    """

    userIds: list[str] = Field(default_factory=list, max_length=MAX_DESIGN_WORKSHOP_VIEWERS)
