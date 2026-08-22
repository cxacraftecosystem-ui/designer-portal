"""The two bodies the design-workshop access queue accepts: an ask, and an admin's answer.

A THIRD SHARING SYSTEM, AND IT IS DELIBERATELY NOT FOLDED INTO ``schemas/access.py``. That module's
header names two — data access (researcher-to-researcher) and workshop access (admin-to-researcher,
over the repository's ``Workshop``) — and says they share a request/decide vocabulary so the UIs
read alike. This one shares the vocabulary too and is kept apart because the OBJECT is different: a
``DesignWorkshop`` is a separate table with separate access rules, and the two "workshop" nouns
have already produced one scanned card that opened the wrong kind of record
(``frontend/lib/workshopCodes.ts`` on why ``workshop`` and ``designWorkshop`` have separate code
letters). ``WorkshopAccessRequestIn`` living beside a ``DesignWorkshopAccessRequestIn`` in one file
is an invitation to import the wrong one, and nothing would fail until an admin was looking at a
queue of the wrong workshops.

WHAT IS BORROWED FROM ``WorkshopAccessRequestIn``, AND WHAT IS NOT. The note, the decision status
and the decision note are the same fields doing the same jobs. The MULTI-SELECT is not: that body
takes ``workshopIds`` because a researcher joining a project needs a whole season at once, whereas a
design workshop is met ONE AT A TIME by scanning the card in front of you. A list here would have no
way to carry the per-workshop scanned code that is the entire point of the ask.
"""

from pydantic import BaseModel, Field

#: The longest ``scannedCode`` this body will carry.
#:
#: A cap and not a validation: the grammar is checked in ``services/design_workshop_access``, which
#: refuses anything that is not a whole ``DPW1:G:<id>:<check>``. This is here for the same reason
#: every other list and string on this wire carries one — an unbounded field is a free way to make
#: the server do work — and it is set well above the longest code the encoder can produce. That is
#: ``DPW1:G:`` (7) plus the 64-character ceiling ``ID_PATTERN`` allows plus ``:CHCK`` (5) = 76
#: characters. The figure the cap actually has to clear is the PRINTED form, though, not that one:
#: ``formatWorkshopCodeForPrint`` breaks the code into groups of four separated by spaces, so the
#: longest thing somebody can paste is 76 + 18 spaces = 94 characters, which the decoder strips
#: before it parses. 200 clears that with room to spare.
MAX_SCANNED_CODE = 200

#: The longest note either side may attach. The same 2000 as every note in ``schemas/access.py``,
#: so a client that already caps one box does not have to learn a second number.
MAX_NOTE = 2000


class DesignWorkshopAccessRequestIn(BaseModel):
    """A designer asks to be let into ONE design workshop.

    ``workshopId`` is required even when ``scannedCode`` is present and carries the same id, and
    that redundancy is deliberate rather than sloppy. It keeps the id the caller BELIEVES it is
    asking about separate from the id the code DECODES to, so the two can be compared — a client
    that scanned one card and posted another workshop's id is a bug worth a 422 rather than a
    silently redirected request. Both clients know the id at the moment they call: they have just
    decoded it.

    ``scannedCode`` is optional because the manual path is real — a designer typing the code off a
    card under a tin roof, or being told the identifier by the person beside them — and refusing
    those asks would leave exactly the people this feature is for with no way through. Its presence
    or absence is what the stored ``source`` records, and an admin reading the queue can tell them
    apart. It is EVIDENCE and never authorisation; see the service module for why treating a valid
    check as proof of anything would be a mistake.
    """

    workshopId: str = Field(min_length=1, max_length=200)
    scannedCode: str | None = Field(default=None, max_length=MAX_SCANNED_CODE)
    note: str | None = Field(default=None, max_length=MAX_NOTE)


class DesignWorkshopAccessDecisionIn(BaseModel):
    """An admin answers one request: GRANTED or DENIED.

    ``status`` is validated in the service against the two tokens an admin may choose, not here.
    PENDING is a state a row STARTS in and not a decision anybody makes, so it is not accepted, and
    keeping the check beside the rule that acts on it stops the two lists drifting — the same
    division ``schemas/access.py`` makes for the workshop ladder.
    """

    status: str = Field(min_length=1, max_length=40)
    note: str | None = Field(default=None, max_length=MAX_NOTE)
