"""The three bodies the join-card routes accept: mint one, redeem one, and the offline evidence.

A FOURTH SHARING VOCABULARY, AND IT IS DELIBERATELY NOT FOLDED INTO ``schemas/design_workshop_access``.
That module's own header explains why it is not in ``schemas/access.py`` — "the OBJECT is different"
— and the same argument separates this one from it, one level down. Those two bodies describe an ASK
and an ANSWER: a person requesting, an administrator deciding. These describe a CREDENTIAL being
minted and presented. ``DesignWorkshopAccessRequestIn`` sitting beside ``JoinCardRedeemIn`` in one
file is an invitation to import the wrong one, and nothing would fail until somebody's scan was
filed as a request that an admin then had to answer by hand.

**THE ONE RULE THAT IS DIFFERENT HERE FROM EVERY OTHER SCHEMA IN THIS DIRECTORY: one field on this
wire is a live credential.** ``JoinCardRedeemIn.code`` carries a 110-bit secret. Pydantic will put a
rejected value into a 422 body, so the caps below are set to make the ordinary shapes of paste pass
rather than to validate the grammar — the grammar is checked in
``services/design_workshop_grants.decode_join_code``, which refuses without echoing what it was
sent. **DO NOT ADD A REGEX ``pattern`` TO THAT FIELD**: a pattern failure is reported by Pydantic
with the offending input inside it, which would put whole join cards into 422 bodies, into access
logs, and into whatever aggregates them.
"""

from datetime import datetime

from pydantic import BaseModel, Field

#: The longest ``code`` this body will carry.
#:
#: A CAP AND NOT A VALIDATION, for the same reason ``MAX_SCANNED_CODE`` in the sibling module is one:
#: an unbounded field is a free way to make the server do work. The figure it has to clear is the
#: PRINTED form. The longest card is ``DPW2:J:`` (7) + the 64-character ceiling ``_ID_PATTERN``
#: allows + ``.`` + a 22-character secret + ``:CHCK`` (5) = 99 characters, and
#: ``formatWorkshopCodeForPrint`` breaks a code into groups of four separated by spaces, so the
#: longest thing somebody can paste is 99 + 24 spaces = 123. 300 clears that with room to spare, and
#: it is the same order of magnitude as the 200 next door so nobody has to learn two numbers.
MAX_JOIN_CODE = 300

#: The longest label an admin may put on a printed batch ("stage-4 batch, 20 cards printed 24 Aug").
#: Short on purpose: it is a line in a list, not a note, and the notes field lives on the request.
MAX_LABEL = 200

#: The longest ``bootId`` accepted. Android's is a UUID; the cap is a guard on an unbounded string
#: rather than a statement about the format, because a handset one release ahead must not have its
#: whole redemption refused over an identifier the server only ever stores and shows.
MAX_BOOT_ID = 200


class JoinCardMintIn(BaseModel):
    """Print one join card for one record.

    ``maxUses`` DEFAULTS TO 1 AND THE DEFAULT IS THE SAFE ONE — the same value
    ``RecordAccessToken.maxUses`` carries in the database, so a client that says nothing and a
    database that is asked nothing agree. **Anything other than 1, INCLUDING the ``null`` that means
    unlimited, is refused for a non-admin in ``mint_grant``** and not here: the schema cannot see the
    actor's role, and putting half the rule in a validator would leave two places to look. See that
    function for why the rule exists at all — a designer cannot create a workshop, so a card that
    admits arbitrarily many people would hand them the membership power that refusal denies.

    ``recordType`` is a plain string validated against the enum in the service rather than a Python
    ``Enum`` here, matching how ``DesignWorkshopAccessDecisionIn.status`` is handled and for the
    stated reason: keeping the check beside the rule that acts on it stops the two lists drifting.

    ``daysValid`` is optional and defaults to a fortnight — the length of the workshop the cards are
    printed for. It cannot be omitted INTO nullability: a card with no end date is a permanent key to
    a workshop, printed on paper, that nobody remembers exists, which is why the column is NOT NULL.
    """

    recordType: str = Field(default="DESIGN_WORKSHOP", min_length=1, max_length=40)
    recordId: str = Field(min_length=1, max_length=200)
    maxUses: int | None = Field(default=1, ge=1, le=1000)
    daysValid: int | None = Field(default=None, ge=1, le=365)
    label: str | None = Field(default=None, max_length=MAX_LABEL)


class JoinCardRedeemIn(BaseModel):
    """Present one join card. **``code`` is a live credential — read the module header.**

    -- THE FIVE EVIDENCE FIELDS, AND WHY NONE OF THEM DECIDES ANYTHING -------------------------

    Every one of them is written down and none of them is compared to decide an outcome.
    ``serverArrivedAt`` — the moment this body reaches the server — is the authority for expiry and
    for who was first, because ordering by a number a phone's settings screen can change hands the
    grant to whoever winds their clock back furthest.

    * ``scannedAt`` — the handset's WALL CLOCK at the moment of the scan. Untrusted, stored, shown to
      an admin beside the arrival time so a human can weigh it.
    * ``scannedAtElapsedSec`` / ``syncedAtElapsedSec`` — Android's ``SystemClock.elapsedRealtime``,
      which is MONOTONIC and cannot be wound back without root. Their difference is how long ago the
      scan really happened, from which the server can derive a clock-independent estimate
      (``serverArrivedAt - (syncedAtElapsedSec - scannedAtElapsedSec)``). **This is the only
      device-reported time worth anything**, and it is still evidence rather than authority.
    * ``bootId`` — because a REBOOT resets the monotonic clock and would otherwise make the estimate
      above silently nonsense. Two elapsed readings are only comparable within one boot, and this is
      what makes that checkable rather than assumed.
    * ``clockJumpObserved`` — the handset saying ``ACTION_TIME_CHANGED`` fired between the scan and
      this call. It is not an accusation: a phone that found a mobile network after two days offline
      legitimately jumps. It is a flag on a queue row so an admin is not surprised.

    ALL OPTIONAL, because the web client has no monotonic clock to report and an online scan has
    nothing to reconstruct. **Absent evidence is honest; a zero would not be**, which is why none of
    these has a default of 0.
    """

    code: str = Field(min_length=1, max_length=MAX_JOIN_CODE)
    scannedAt: datetime | None = Field(default=None)
    scannedAtElapsedSec: int | None = Field(default=None, ge=0)
    syncedAtElapsedSec: int | None = Field(default=None, ge=0)
    bootId: str | None = Field(default=None, max_length=MAX_BOOT_ID)
    clockJumpObserved: bool = Field(default=False)
