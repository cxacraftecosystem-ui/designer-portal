"""Request bodies for the platform allow-list — who may sign in to this application at all.

NOT ``app/schemas/access.py``, WHICH IS A DIFFERENT SUBJECT WITH A CONFUSINGLY SIMILAR NAME. That
module holds the two *sharing* ladders: researcher-to-researcher data access and admin-to-researcher
workshop access, both of which are about what a signed-in person may reach. This one is about
whether they may sign in at all, which is a question that comes first and is answered by a different
table. Import from the wrong one and the type checker will tell you; put a model in the wrong one
and nobody ever will.

THE EMAIL IS THE ONLY THING A PERSON OUTSIDE THIS FILE CAN PUT IN THE TABLE, and every body here is
written by an administrator. Nothing on this page is reachable by an unauthenticated caller: the one
write a stranger can cause is the PENDING row that ``app/services/access_roster.py`` creates from a
refused sign-in, and that row carries no free text at all. See that module's docstring for why.
"""

from typing import Literal

from pydantic import EmailStr, Field

from app.schemas.common import APIModel


class AccessRosterCreate(APIModel):
    """Admit an address before it has an account — the pre-approval an admin does by hand.

    ACTIVE ON CREATION, unlike the pending rows the login path writes. An admin typing an address
    into this box is approving it; there is nobody else for the request to be routed to, and a form
    that produced a pending entry the same admin then had to approve would be a form that does
    nothing. Use the decision endpoint to work the queue and this one to add somebody outright.

    ``role`` is the tier the account is created at (or lifted to) when the person first signs in.
    Absent means the platform default, which is the lowest rung.
    """

    email: EmailStr
    fullName: str | None = Field(default=None, max_length=180)
    role: str | None = None
    notes: str | None = Field(default=None, max_length=4000)


class AccessRosterUpdate(APIModel):
    """Correct the admin-typed columns of a row. Deliberately CANNOT change ``status``.

    Every status transition goes through the decision endpoint or the suspend endpoint, so that the
    stamps that go with it — who decided, when, and the joining date — are written by one piece of
    code that cannot forget them. A PATCH that could set ``status: ACTIVE`` on its own would be a
    second admission path with no ``joinedAt``, and the admin screen would show a member who never
    joined.
    """

    fullName: str | None = Field(default=None, max_length=180)
    role: str | None = None
    notes: str | None = Field(default=None, max_length=4000)


class AccessDecision(APIModel):
    """An administrator's answer to a pending request.

    ``role`` is honoured only on APPROVE, and only up to the deciding admin's own tier — the same
    rule ``users.assert_role`` applies to creating an account, because approving a request AT a tier
    and creating an account at that tier are the same grant made through two doors.
    """

    decision: Literal["APPROVE", "REJECT"]
    role: str | None = None
    notes: str | None = Field(default=None, max_length=4000)
