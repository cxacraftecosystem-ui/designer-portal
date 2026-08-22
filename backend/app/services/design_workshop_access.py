"""A designer asking to be let into a design & prototype workshop, and an admin answering.

THE GAP THIS CLOSES. The owner's rule is that a designer may only PARTICIPATE in a workshop, and
only once they are on that workshop's list of designers. Both halves are already built and neither
is weakened here: ``can_create_design_workshops`` refuses a designer the create, and every route in
``api/routes/design_workshop_viewers.py`` is ``Depends(require_admin)``. What follows from those
two, and had no answer, is that a designer standing beside the person who made the workshop —
holding the card that person just printed — could not ask. There was no endpoint at all. The web
client already knows it: ``unresolvedWorkshopCodeMessage`` in ``frontend/lib/workshopCodes.ts``
returns a sentence telling that designer to send the code to an admin and ask, and the comment above
that ``return`` explains why — "AND IT DOES NOT SAY 'REQUEST SENT', because nothing has been sent …
any UI over this must not dress that up as a submitted request". A previous wave deliberately shipped
no client-side "pending" state rather than tell somebody their request was queued somewhere no admin
would ever look, and named this module as what it needed.

**GRANTING GOES THROUGH THE VIEWER MECHANISM AND NOWHERE ELSE.** :func:`decide` calls
``design_workshop_viewers.replace_viewers``; there is no second way to become a viewer and nothing
here is ever consulted when deciding access. ``load_workshop_or_404`` still asks ``has_viewer_grant``
and only that, so a GRANTED row in this queue is a RECEIPT for a write that happened in the other
table — never a source of truth about who may read a workshop. If you are about to make this table
confer access, don't: that is two places to look when somebody has access they should not.

WHAT THAT REUSE INHERITS, STATED BECAUSE IT IS SURPRISING THE FIRST TIME. ``replace_viewers``
validates the WHOLE resulting set and refuses the entire call with a 422 if any account in it is
ineligible — a designer whose empanelment lapsed last month, an account the platform allow-list has
suspended. So granting a request on a workshop that already has such a viewer is refused, and the
422 names a colleague the admin did not touch. That is not a defect introduced here: it is exactly
what the viewers screen already does when an admin presses Save without changing anything, and the
remedy the message gives (restore the roster entry, or the allow-list row) is the same one. The
alternative — a private insert that skipped the validation — is the second way to become a viewer
this module exists not to create.

=======================================================================================
ENUMERATION: WHAT WAS DECIDED, AND WHY IT IS NOT THE 404 EVERY OTHER ROUTE USES
=======================================================================================

THE RULE THIS REPOSITORY FOLLOWS is that a record the caller may not have answers 404 with the same
detail string a genuinely missing one gets, so nobody can enumerate the repository by asking about
random ids. ``services/records.require_record`` is the one-line helper every route reaches for —
missing id, "Record not found", 404 — and ``load_workshop_or_404`` is where the rule is argued in
full, refusing an ungranted caller with that same 404 rather than a 403 that would confirm the id is
real. Every admin-facing function in this module follows it exactly.

**THE REQUEST ROUTE CANNOT.** Its entire purpose is to be called by somebody who, by construction,
may not see the record — so "404 if you may not see it" would refuse every legitimate ask, and
"201 if it exists, 404 if it does not" is an existence oracle with a friendly name on it. This is
the most obvious enumeration surface in the application and it is worth being explicit about what
was chosen:

1. **THE ANSWER IS UNIFORM.** :func:`file_request` returns ``None`` in every case and the route
   answers 202 with one fixed body. A row was filed; a row was already there and was left alone; a
   settled grant was reopened; the caller already had access so nothing was filed; the workshop is
   soft-deleted; the id names nothing at all; the id cannot even be stored — all seven produce the
   same bytes. Nothing in the response distinguishes them, and no message is phrased as though it
   did.

2. **THERE IS NO REQUESTER-FACING READ, AND THAT IS A DESIGN DECISION RATHER THAN AN OMISSION.** A
   "my requests" endpoint would hand back rows only for ids that resolved, which is the oracle put
   back one call later. If one is ever wanted, it has to answer for ids that resolved to nothing
   too, which means storing asks against workshops that do not exist — read this paragraph before
   building it.

3. **THE SCANNED CODE IS NOT WHAT PREVENTS THIS, AND MUST NEVER BE SOLD AS IF IT WERE.** It is
   tempting to say "only somebody holding a card can ask, so there is nothing to enumerate". That is
   false. The four check characters are FNV-1a over the payload, the algorithm ships to every
   browser, and ``frontend/lib/workshopCodes.ts`` states it outright: "It is a typo detector and
   nothing more. It is not a signature and must never be described as one: the algorithm is in this
   file, so anyone can compute a valid check for any id." A valid code is EVIDENCE for the admin
   reading the queue and nothing else. The uniform answer is the whole mechanism.

4. **CODE VALIDATION IS SAFE TO REFUSE LOUDLY** — with a 422 — because it depends only on the
   request body. A malformed code, a code for another kind of record, or a code naming a different
   workshop than the body does are all statements about what was sent, and saying so discloses
   nothing about which ids exist.

   AND IT IS PINNED, not merely written down. ``tests/test_design_workshop_access_gate.py`` asserts
   that a bad code answers 422 **with the database never touched** — a tripwire stands in for ``db``
   and raises on the first delegate anybody reads. A test over a real database cannot see this: the
   two 422s look identical. Move a lookup above the code check and that module goes red.

5. **WHAT IS NOT CLAIMED.** This is not constant-time. The filing branch issues more queries than
   the no-op branches, so a caller with a stopwatch and a quiet server may be able to distinguish
   them statistically. Closing that would mean doing the same work in every branch, including
   inserting rows for ids that name nothing, and that trade — a growable table written by anybody,
   to defeat a timing signal on cuid-keyed ids that are not guessable in the first place — was
   judged the wrong one. What is removed is the free, reliable oracle; a timing side channel
   remains and is written down here rather than left for somebody to discover.

=======================================================================================
IDEMPOTENCY
=======================================================================================

One row per (workshop, requester), enforced by a UNIQUE INDEX and not by a check in this file. A
request typed in a courtyard reaches this server whenever the handset next finds signal, and a
flaky link retries; a read-then-write here would be two round trips with a window in the middle,
which is the shape this repository has already shipped a double-filed government record from.

A REPLAY IS A NO-OP AND DOES NOT MOVE ``createdAt``. That is the opposite of what
``POST /workshops/access-requests`` does — its long block comment argues for refreshing the clock on
a re-request so the queue re-ranks it — and the difference is deliberate. There, a fresh ask after a
refusal is genuinely a new request. Here the second delivery is the SAME ask, and restamping it
would push the person who asked first down a queue ordered oldest-first: the anti-spam rule inverted
into a way to jump the queue.

A DENIED ROW IS NOT REOPENED BY ASKING AGAIN, and this too departs from the sibling route on
purpose. A refusal here means "you are not on this team"; letting a scan reopen it would put the
same card back in an admin's queue every time somebody pointed a phone at it, which is precisely the
spam requirement 3 names. Reversing a refusal is an admin action — :func:`decide` accepts a DENIED
row and can grant it.

WHAT IS *NOT* BOUNDED, SAID OUT LOUD. The unique index caps the rows one account can file PER
WORKSHOP at one; it does not cap how many workshops one account may ask about. Nothing is written
for an id that names nothing, so the table cannot be grown by guessing — a caller can only reach as
many rows as there are workshops they can name, and naming a workshop is most of what having access
to it takes. A per-account ceiling was considered and left out: enforcing one would mean either a
different answer once it was reached (the oracle this route is built to avoid) or a silent drop (a
designer told their ask was received when it was thrown away, which is the dishonesty the previous
wave refused to ship). If the queue ever does need a ceiling, it belongs on the ADMIN read, where
saying "truncated" costs nothing — and :data:`QUEUE_LIMIT` is already that.

A GRANTED ROW WHOSE ACCESS HAS SINCE BEEN TAKEN AWAY *IS* REOPENED, and it is the one case that
moves ``createdAt``. Being removed from a workshop and scanning the card again is a genuinely new
ask, it cannot be produced by a designer alone (an admin had to take them off), and without this
branch that person could never ask again through any surface. The decision columns are KEPT through
the reopen — the same call ``POST /workshops/access-requests`` was corrected to make, for the same
reason: they are the only record the previous decision has, and ``status`` being PENDING is what
tells an admin it has not been decided again yet.
"""

import logging
import re
from datetime import UTC, datetime
from typing import Any

from fastapi import HTTPException, status

from app.core.db import db
from app.core.deps import is_admin
from app.services.design_workshop_viewers import has_viewer_grant, replace_viewers
from app.services.records import plain

logger = logging.getLogger(__name__)

#: The two answers an admin may give. PENDING is a state a row STARTS in, not a decision anybody
#: makes, so it is deliberately absent — an admin who wants a request back in the queue has not made
#: a decision, they have changed their mind about making one, and there is no screen for that.
DECISIONS = ("GRANTED", "DENIED")

#: What ``GET`` accepts for its ``statusFilter``. ``ALL`` widens to the whole history for auditing,
#: matching ``GET /workshops/access-requests`` so an admin meets one vocabulary and not two.
STATUS_FILTERS = ("PENDING", "GRANTED", "DENIED", "ALL")

#: Characters that cannot reach Postgres inside an id — NUL, the other control bytes, and a LONE
#: SURROGATE (half an emoji from a phone that truncated it), which cannot be encoded to UTF-8 at all
#: and fails inside the driver before Postgres is reached.
#:
#: A SECOND COPY of ``design_workshop_viewers._UNSTORABLE_IN_AN_ID``, duplicated rather than imported
#: because that name is private to that module and reaching into it would couple this file to
#: somebody else's internals for four bytes of regex. The two are used for opposite purposes anyway:
#: there it refuses a write and names the offending account, here it makes an unaskable id fall into
#: the same silent no-op as any other id that resolves to nothing — see the enumeration note in the
#: header, which is why this cannot be a 422 the way it is over there.
_UNSTORABLE_IN_AN_ID = re.compile(r"[\x00-\x1f\x7f\ud800-\udfff]")


# --------------------------------------------------------------------------------------
# The scanned code: a NARROW Python port of frontend/lib/workshopCodes.ts
# --------------------------------------------------------------------------------------
#
# WHY THERE IS A PORT AT ALL. Storing whatever string the client sent and calling it "the scanned
# code" would prove nothing to the admin reading it — a field nobody validates is a field a client
# bug fills with the workshop id, and the queue would then show a "SCAN" that never happened. The
# requirement is that an admin can see the request came from a real code and not a guessed id, and
# that is only true if the server is the one deciding.
#
# WHY IT IS NARROW. This decodes DESIGN-WORKSHOP codes and refuses every other letter. The full
# grammar has ten record types and a table of letters that must agree character-for-character across
# three clients; a third partial copy of that table is how a letter comes to mean one thing here and
# another there, and the browser file's own header warns about exactly that. Refusing an unknown
# letter cannot resolve to the wrong record — only to no record — so the narrow port is the safe
# half to take. If a second caller ever needs the whole grammar, lift this into its own module and
# port the table properly, with a test that pins it against the TypeScript one.
#
# ⚠ EVERY CONSTANT BELOW IS A COPY OF ONE IN `frontend/lib/workshopCodes.ts` AND HAS TO BE KEPT IN
# STEP WITH IT BY HAND — the same hand-kept relationship the Kotlin port has, because no language
# here can read another's source. The exception is `_ASCII_DIGITS`, which exists only because
# Python's `\d` is wider than JavaScript's; see the note on it.

#: The three characters that say a code belongs to this application at all.
_CODE_NAMESPACE = "DPW"

#: Every payload version this build can read. Only ever ADDED to: a card printed today must still
#: scan in five years, and a NEWER card met by an older server has to say so rather than read as
#: damaged. Nothing here WRITES a code — only the clients print them — so there is no "version we
#: write" constant to keep beside it.
_SUPPORTED_CODE_VERSIONS = frozenset({1})

#: desiGn workshop. ``W`` is the repository ``Workshop``, which is a DIFFERENT TABLE with different
#: access rules, and tags reading ``DPW1:W:…`` are tied to craft-documentation records today. The
#: two nouns have already produced one scanned card that opened the wrong kind of record; that is
#: why they have separate letters and why this module accepts exactly one of them.
_DESIGN_WORKSHOP_LETTER = "G"

#: Crockford base32 — no I, L, O or U, the characters people get wrong reading a code off a card.
_CHECK_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
_CHECK_LENGTH = 4

#: Crockford's reading rules, applied to the CHECK ONLY. ``U`` is deliberately absent from the
#: alphabet and is left to fail rather than guessed at. The ID is NOT corrected this way: ``0`` and
#: ``o`` are both legal in a cuid, so "correcting" one would corrupt an id that was typed correctly.
_CHECK_CONFUSABLES = {"I": "1", "L": "1", "O": "0"}

#: The shape of an identifier this repository issues: a cuid, or the UUID client key a row carries
#: before it has ever reached the server. Lower case only, hyphens allowed for UUIDs.
_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{7,63}$")

#: The prefixes a workshop draft carries while it exists ONLY on the device that made it — the web
#: client mints ``dwlocal-<uuid>`` and Android mints ``local-<uuid>``, and the two have simply never
#: agreed. Neither ever becomes a server id: the create route allocates a fresh cuid and discards
#: the draft key. So a code naming one can never resolve here, and it is refused with its own
#: sentence rather than folded into "malformed", because the honest next action is "ask them to sync
#: their device" and not "read the card again".
_DEVICE_LOCAL_ID_PREFIXES = ("dwlocal-", "local-")

#: Digits, ASCII only. ``\d`` in a Python pattern also matches Devanagari and Arabic-Indic digits,
#: where JavaScript's ``/^\d+$/`` does not — a version written in another script would parse here
#: and be refused by the client, which is a disagreement about what a card says.
_ASCII_DIGITS = re.compile(r"^[0-9]+$")


class ScannedCodeRefused(Exception):
    """The code in the body is not a readable design-workshop code.

    An exception rather than a returned refusal, unlike the browser's ``DecodeResult``, because the
    caller here is one request carrying one code — there is no sheet of thirty cards where a single
    bad row must not empty the page. The route turns it into a 422; see the header for why saying so
    loudly is safe.
    """

    def __init__(self, detail: str) -> None:
        super().__init__(detail)
        self.detail = detail


def code_check(prefix: str) -> str:
    """The four check characters for a payload prefix — everything before the final separator.

    FNV-1a over the ASCII bytes, of which the low 20 bits become four base32 characters. A PORT of
    ``workshopCodeCheck``, and it has to agree character for character or every real scan is refused
    as damaged.

    THE JAVASCRIPT SPELLS THE MULTIPLY AS FIVE SHIFTS AND A SUM; THIS SPELLS IT AS A MULTIPLY, AND
    THEY ARE THE SAME NUMBER. ``1 + 2 + 16 + 128 + 256 + 16777216 = 16777619`` — the FNV prime — and
    the shifts are there because JavaScript's ``*`` produces a double whose low bits stop being exact
    past 2^53. Python's integers are exact, so the mask below is the only wrap needed. The signed
    ``<<``/unsigned ``>>> 0`` mixture in the original changes intermediate values by whole multiples
    of 2^32 and cannot change the result modulo 2^32.
    """
    value = 0x811C9DC5
    for character in prefix:
        value ^= ord(character)
        value = (value * 16777619) & 0xFFFFFFFF
    low = value & 0xFFFFF
    return "".join(
        _CHECK_ALPHABET[(low >> shift) & 31]
        for shift in range((_CHECK_LENGTH - 1) * 5, -1, -5)
    )


def decode_design_workshop_code(raw: str) -> tuple[str, str]:
    """Read a scanned design-workshop code. Answers ``(workshop_id, canonical_code)``.

    TOLERANT OF WHAT A HUMAN DOES TO IT, STRICT ABOUT WHAT IT MEANS — the browser's rule, kept
    because the two have to accept the same strings. Case and whitespace go first, including the
    grouping spaces ``formatWorkshopCodeForPrint`` adds under the QR so that what is printed can be
    typed straight back. Everything after that is exact.

    The canonical form is returned rather than the input so that what is STORED is one spelling: two
    admins comparing a queue row against a card must not be reading a lower-cased, space-separated
    paste in one row and a bare scan in the next.

    Every refusal names what to do next, because these sentences reach a designer standing in a
    courtyard. None of them says anything about which workshops exist — see the header.
    """
    text = re.sub(r"\s+", "", raw or "").upper()
    if not text:
        raise ScannedCodeRefused("Nothing was scanned or typed.")

    parts = text.split(":")
    if len(parts) != 4 or not parts[0].startswith(_CODE_NAMESPACE):
        raise ScannedCodeRefused(
            "That is not a workshop card or tag. Workshop codes begin “DPW”; a shop barcode, a "
            "payment code or a web address will not open a record here."
        )

    version_text = parts[0][len(_CODE_NAMESPACE) :]
    if not _ASCII_DIGITS.match(version_text):
        raise ScannedCodeRefused(
            "That is not a workshop card or tag. Workshop codes begin “DPW” followed by a version "
            "number."
        )
    version = int(version_text)
    if version not in _SUPPORTED_CODE_VERSIONS:
        # Its own answer rather than "malformed": the card is fine and this server is old, and
        # "update the app" and "the tag is damaged" send a designer to two different places.
        raise ScannedCodeRefused(
            f"That card was printed against a newer code format ({version}) than this server reads. "
            f"Update the app, or ask an administrator to add you from the workshop's viewers screen."
        )

    if parts[1] != _DESIGN_WORKSHOP_LETTER:
        # NAMED SEPARATELY FROM "not a workshop code", because it is a code and it is ours — it just
        # points at an artisan, a tool or a prototype. Somebody scanning the wrong card off a lanyard
        # needs to be told to find the workshop's card, not that their scanner is broken.
        raise ScannedCodeRefused(
            "That code belongs to this application but does not name a design workshop — it points "
            "at a different kind of record. Scan the workshop's own card, the one the person who "
            "created it printed."
        )

    workshop_id = parts[2].lower()
    if workshop_id.startswith(_DEVICE_LOCAL_ID_PREFIXES):
        # BEFORE the pattern below, which a ``dwlocal-``/``local-`` id passes perfectly well. Without
        # this the code would decode cleanly and be filed as a request against a workshop that
        # exists on exactly one handset and nowhere else — a queue entry no admin could ever act on.
        raise ScannedCodeRefused(
            "That code names a workshop that had not been shared yet when it was written down — it "
            "only ever meant anything on the device that made it. Ask whoever created the workshop "
            "to sync their device and send you the code it prints then."
        )
    if not _ID_PATTERN.match(workshop_id):
        raise ScannedCodeRefused(
            "This code is damaged or was typed incompletely — the identifier in it is not a whole "
            "one. Check it against the card."
        )

    typed_check = "".join(_CHECK_CONFUSABLES.get(character, character) for character in parts[3])
    prefix = f"{_CODE_NAMESPACE}{version}:{parts[1]}:{parts[2]}"
    if len(typed_check) != _CHECK_LENGTH or typed_check != code_check(prefix):
        # The most valuable refusal here, for the reason the browser gives: a code one character out
        # is not a near miss, it is a different record or no record at all, and nothing downstream
        # could tell.
        raise ScannedCodeRefused(
            "This code does not check out, so one of its characters is wrong. Read it off the card "
            "again, character by character — a single wrong character points at a different "
            "workshop, and nothing later would notice."
        )

    return workshop_id, f"{prefix}:{typed_check}"


# --------------------------------------------------------------------------------------
# Filing an ask
# --------------------------------------------------------------------------------------


async def file_request(
    user: Any, *, workshop_id: str, scanned_code: str | None, note: str | None
) -> None:
    """Record that this account wants into this workshop. Answers NOTHING about what happened.

    THE ``None`` RETURN IS THE ANTI-ENUMERATION MECHANISM, not an oversight — read the header before
    changing it to something informative. A row was filed, a row already existed, a settled grant
    was reopened, the caller already had access, the workshop is soft-deleted, the id names nothing,
    the id cannot even be stored: all seven leave here identically, and the route answers one fixed
    body for all of them.

    THE ONE THING THAT IS SAID OUT LOUD is :class:`ScannedCodeRefused`, and the ORDER below is what
    keeps that safe. It is raised only by the code check, which reads nothing and has nothing but a
    string fold ahead of it: every refusal it can produce is a statement about the request body, true
    or false before the database is consulted. Every branch after it is silent. Do not move a
    database read above that block — ``tests/test_design_workshop_access_gate.py`` is what notices.
    """
    # ONE FOLDED ID, COMPUTED ONCE AND USED FOR BOTH THE COMPARISON AND THE LOOKUP — and the
    # ``.lower()`` is the load-bearing part rather than tidiness. **THE IDENTIFIER PRINTED ON A CARD
    # IS UPPER CASE**: ``workshopCodes.ts`` builds the payload as
    # ``${NAMESPACE}${VERSION}:${letter}:${id.toUpperCase()}``, so a designer reading one off a card
    # under a tin roof — the MANUAL path ``schemas/design_workshop_access`` exists to keep open —
    # posts ``workshopId`` in upper case. Folding it here and not there is how that ask used to be
    # thrown away: the comparison agreed (both decoders lower-case ``parts[2]``) and the lookup then
    # searched for an upper-case cuid, found nothing, and took the silent no-op exit — a 202 telling
    # somebody an administrator could see their request when nothing had been written. This module's
    # own anti-enumeration design is what made that unfindable: every branch answers identical bytes,
    # so neither the requester, the admin nor the log could ever tell the difference.
    #
    # Folding is safe because the id grammar is lower-case-only in all three clients — ``_ID_PATTERN``
    # is ``^[a-z0-9][a-z0-9-]{7,63}$``, matching the browser's and Kotlin's — so no identifier this
    # repository issues can be changed by it.
    wanted = plain(workshop_id).strip().lower()

    code = None
    if scanned_code is not None and scanned_code.strip():
        decoded_id, code = decode_design_workshop_code(scanned_code)
        if decoded_id != wanted:
            # A statement about the body and not about the database — the two ids disagree whether
            # or not either names a real workshop. Loud, because a client that scanned one card and
            # posted another id is a bug that would otherwise file requests against whatever the
            # screen happened to be showing.
            raise ScannedCodeRefused(
                "The code that was scanned names a different workshop from the one this request is "
                "for. Scan the card again from the workshop you mean to join."
            )

    # THE GUARD READS THE RAW VALUE ON PURPOSE. ``plain`` strips ``range(32)`` less tab/LF/CR, so a
    # lone surrogate — half an emoji from a handset that truncated a paste — survives it and fails
    # inside the driver rather than in Postgres.
    if not wanted or _UNSTORABLE_IN_AN_ID.search(workshop_id):
        # An id that cannot reach Postgres would be a 500 with a DataError in the log, and an
        # authenticated caller could fill the error log at will. It resolves to nothing, so it takes
        # the same silent exit as any other id that resolves to nothing.
        return

    workshop = await db.designworkshop.find_unique(where={"id": wanted})
    if workshop is None or getattr(workshop, "deletedAt", None) is not None:
        # A SOFT-DELETED WORKSHOP IS NOT ASKABLE. An admin can still restore it and put a team on it
        # from the viewers screen; what must not happen is a queue filling with requests to join
        # records that are on their way out, which an admin would have to work through to discover.
        return

    user_id = getattr(user, "id", "")
    if not user_id:
        return
    if (
        workshop.createdById == user_id
        or is_admin(user)
        or await has_viewer_grant(wanted, user_id)
    ):
        # NOTHING IS FILED FOR SOMEBODY WHO IS ALREADY IN. The ordinary way this happens is a race
        # rather than a mistake: a designer asks by scanning while the admin is granting them from
        # the viewers screen, or an offline ask syncs a week after it was answered in person. An
        # admin working the queue must not be handed decisions that are already made.
        return

    now = datetime.now(UTC)
    source = "SCAN" if code else "MANUAL"
    clean_note = plain(note) if note else None

    # THE UNIQUE INDEX IS THE IDEMPOTENCY, AND ``skip_duplicates`` IS HOW THIS CALL SURVIVES IT.
    # Two syncs arriving together are one row and no 500 on a duplicate key; the second one simply
    # creates nothing. ``create_many`` answers with how many rows it wrote, which is how the branch
    # below knows a row was already there without a second read on the ordinary path.
    created = await db.designworkshopaccessrequest.create_many(
        data=[
            {
                "designWorkshopId": wanted,
                "requestedById": user_id,
                "status": "PENDING",
                "source": source,
                "scannedCode": code,
                "note": clean_note,
            }
        ],
        skip_duplicates=True,
    )
    if created:
        return

    # A ROW ALREADY EXISTS. Exactly one shape of repeat ask reopens it: a GRANT whose access has
    # since been taken away. See the header for why a PENDING replay must not restamp the clock and
    # why a DENIED row must not be reopened by asking — both of those are the no-op this falls
    # through to. ``status: GRANTED`` is in the WHERE rather than read first, so two syncs racing
    # here settle to the same row rather than to an error, and the decision columns are deliberately
    # NOT cleared: they are the only record the previous decision has, and PENDING beside them is
    # what says it has not been decided again yet.
    reopened = await db.designworkshopaccessrequest.update_many(
        where={
            "designWorkshopId": wanted,
            "requestedById": user_id,
            "status": "GRANTED",
        },
        data={
            "status": "PENDING",
            "source": source,
            "scannedCode": code,
            "note": clean_note,
            # THE ONE PLACE THIS CLOCK MOVES. Being removed from a workshop and scanning again is a
            # genuinely new ask, and the queue is ordered oldest-first, so it belongs at the back
            # rather than in the position of an ask that was answered months ago.
            "createdAt": now,
        },
    )
    if reopened:
        logger.info(
            "design-workshop access request reopened: a previously granted viewer has asked again "
            "after their grant was removed (workshop=%s)",
            wanted,
        )


# --------------------------------------------------------------------------------------
# The admin queue
# --------------------------------------------------------------------------------------


def _enum(value: Any) -> str | None:
    """A Prisma enum as a plain string, whether the client handed back an enum or a str."""
    if value is None:
        return None
    return str(getattr(value, "value", value))


def _iso(value: Any) -> str | None:
    return value.isoformat() if value is not None else None


def request_payload(row: Any, *, has_access: bool) -> dict[str, Any]:
    """One queue row as the admin screen reads it.

    HAND-PROJECTED rather than ``public_encode``d over the row and its relations, and the narrowness
    is the point: this answer carries two accounts and a workshop, and an encoder that walked the
    relations would put whatever those models happen to gain next into an access-administration
    screen. Four fields per person, the workshop's identifying fields, and nothing else.

    ``requesterHasAccess`` is computed by the caller and travels with the row because the admin's
    decision depends on it and no join can express it — access is the creator column OR an admin
    role OR a viewer row, three different sources. A PENDING request from somebody who already has
    access is the ordinary outcome of a race, and an admin needs to see that rather than granting it
    again; a GRANTED request from somebody who has NONE is a grant that was later revoked, which is
    the one thing in this queue that looks alarming and usually is not.
    """
    workshop = getattr(row, "designWorkshop", None)
    requester = getattr(row, "requestedBy", None)
    decider = getattr(row, "decidedBy", None)
    return {
        "id": row.id,
        "workshop": {
            "id": row.designWorkshopId,
            "title": getattr(workshop, "title", "") or "",
            # The human-facing code off stage 1, not the cuid: an admin recognises "DPW/OD/2026/14"
            # and does not recognise a cuid. Null until stage 1 has been saved.
            "workshopCode": getattr(workshop, "workshopCode", None),
        },
        "requestedBy": {
            "id": row.requestedById,
            "name": getattr(requester, "name", "") or "",
            "email": getattr(requester, "email", "") or "",
            "role": _enum(getattr(requester, "role", None)) or "",
        },
        "status": _enum(row.status),
        "source": _enum(row.source),
        # THE EVIDENCE, VERBATIM. An admin comparing this against the card in front of them is the
        # whole reason it is stored, and it carries no identity data by construction.
        "scannedCode": row.scannedCode,
        "note": row.note,
        "createdAt": _iso(row.createdAt),
        "decidedAt": _iso(row.decidedAt),
        "decisionNote": row.decisionNote,
        "decidedBy": (
            None
            if decider is None
            else {
                "id": row.decidedById,
                "name": getattr(decider, "name", "") or "",
                "email": getattr(decider, "email", "") or "",
            }
        ),
        "requesterHasAccess": has_access,
    }


#: What the queue read joins. Fixed here so the list and the single-row answer after a decision
#: cannot come to carry different fields — a screen that re-renders one row from a narrower payload
#: blanks the columns the join filled.
_QUEUE_INCLUDE = {"designWorkshop": True, "requestedBy": True, "decidedBy": True}

#: How many requests one listing returns.
#:
#: A CEILING, NOT A PAGE SIZE, and the same reasoning ``ELIGIBLE_VIEWER_LIMIT`` carries: an
#: unbounded ``find_many`` over a table that only grows is how a screen that worked for two years
#: starts timing out. ``statusFilter=ALL`` is the read that will reach it first, because it is the
#: whole history rather than the open queue. ``truncated`` says on the wire that the answer was cut,
#: because a queue silently missing its oldest entries is people waiting for access nobody can see
#: they asked for — the failure this feature exists to end, reintroduced by a limit.
QUEUE_LIMIT = 500


async def queue(status_filter: str = "PENDING") -> dict[str, Any]:
    """The requests an admin works from: PENDING by default, oldest first.

    OLDEST FIRST, matching ``GET /workshops/access-requests``. A queue ordered newest-first is one
    where the person who has waited longest is on the last page, which is how requests sit
    unanswered for a week.

    CROSS-WORKSHOP, and deliberately not nested under one workshop's id. Opening each workshop's
    screen in turn to find out whether anybody is waiting is the same failure by another route: an
    admin has no reason to open a workshop nobody has asked about, so per-workshop queues are
    queues nobody reads.
    """
    where: dict[str, Any] = {}
    if status_filter != "ALL":
        where["status"] = status_filter

    rows = await db.designworkshopaccessrequest.find_many(
        where=where,
        include=_QUEUE_INCLUDE,
        order=[{"createdAt": "asc"}, {"id": "asc"}],
        take=QUEUE_LIMIT + 1,
    )
    truncated = len(rows) > QUEUE_LIMIT
    if truncated:
        rows = rows[:QUEUE_LIMIT]
        logger.warning(
            "the design-workshop access queue hit its ceiling of %s rows (statusFilter=%s); the "
            "answer is truncated and says so",
            QUEUE_LIMIT,
            status_filter,
        )

    access = await _access_by_pair(rows)
    return {
        "requests": [
            request_payload(row, has_access=(row.designWorkshopId, row.requestedById) in access)
            for row in rows
        ],
        "truncated": truncated,
    }


async def _access_by_pair(rows: list[Any]) -> set[tuple[str, str]]:
    """Which (workshop, requester) pairs in this page already have access, in ONE query.

    Not one probe per row: a page of five hundred requests would be five hundred cross-region round
    trips on a screen an admin opens to answer one of them. The viewer rows for the workshops named
    here are read together and the pairs assembled in Python.

    THREE SOURCES OF ACCESS, NOT ONE, and missing any of them makes this field lie in the direction
    that matters. A viewer row is the ordinary case; the CREATOR holds the workshop through
    ``createdById`` and has no viewer row at all (``viewer_rows`` says so in its own docstring); and
    an ADMIN reaches every workshop through ``is_admin`` regardless of either. The role is read off
    the requester the query already joined, so it costs nothing.

    :func:`decide` ASKS THIS TOO, over its single row, rather than writing the predicate a second
    time — which is what it used to do, with two of the three sources, so that the 409 guard and the
    ``requesterHasAccess`` it answered with could and did disagree. Anything that needs "does this
    person already have access" belongs here; do not grow a second copy.
    """
    if not rows:
        return set()
    workshop_ids = sorted({row.designWorkshopId for row in rows})
    viewer_rows = await db.designworkshopviewer.find_many(
        where={"designWorkshopId": {"in": workshop_ids}}
    )
    granted = {(row.designWorkshopId, row.userId) for row in viewer_rows}

    for row in rows:
        workshop = getattr(row, "designWorkshop", None)
        requester = getattr(row, "requestedBy", None)
        is_creator = getattr(workshop, "createdById", None) == row.requestedById
        is_administrator = _enum(getattr(requester, "role", None)) in {"ADMIN", "MASTER_ADMIN"}
        if is_creator or is_administrator:
            granted.add((row.designWorkshopId, row.requestedById))
    return granted


# --------------------------------------------------------------------------------------
# Answering one
# --------------------------------------------------------------------------------------


async def decide(
    request_id: str, *, decision: str, note: str | None, admin: Any
) -> dict[str, Any]:
    """Grant or refuse one request, and answer with the row as it now stands.

    404 AND NOT 403 for a request id that does not exist, with ``require_record``'s own detail
    string, exactly as every other read in this repository does. The uniform answer the REQUEST route
    gives is a departure forced by that route's purpose (see the header); nothing here needs it,
    because every caller is already an admin for whom the answer to "may I see this" is always yes.
    The lookup is spelled out rather than delegated to that helper for one reason: it needs
    :data:`_QUEUE_INCLUDE`, which the helper's two-line signature has no room for, and the join is
    what lets the 409 guard below ask :func:`_access_by_pair` instead of guessing at the answer
    itself.

    GRANTING GOES THROUGH ``replace_viewers``, which is the only way to become a viewer. The set is
    read, the requester is added to it, and the whole set is written back — so a grant here is
    identical to an admin ticking one more box on the viewers screen, including its validation and
    including the 422 it raises when some OTHER account in the set has since become ineligible (the
    header explains why that is inherited rather than worked around).

    A SOFT-DELETED WORKSHOP CAN STILL BE DECIDED HERE, although :func:`file_request` will not accept
    a NEW ask about one. That asymmetry is deliberate and it matches the viewers screen, whose
    ``_workshop_or_404`` deliberately admits a deleted workshop so that an admin can restore it with
    its team intact. The two rules point the same way: do not fill a queue with asks about records on
    their way out, and do not strand an ask that was already waiting when somebody pressed delete.

    THE VIEWER ROW IS WRITTEN BEFORE THIS ROW IS MARKED, and the order is deliberate. If the second
    write fails the request stays PENDING over a grant that already happened, and the retry is
    harmless — ``replace_viewers`` is idempotent. The other order would mark a request GRANTED over
    access nobody has, which is the state nothing on any screen would ever correct.
    """
    if decision not in DECISIONS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"A decision is one of {', '.join(DECISIONS)}.",
        )

    row = await db.designworkshopaccessrequest.find_unique(
        where={"id": plain(request_id).strip()}, include=_QUEUE_INCLUDE
    )
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")

    workshop = getattr(row, "designWorkshop", None)
    if workshop is None:
        # Unreachable through the FK's CASCADE, which takes the request with the workshop. Answered
        # rather than asserted because a hand-run DELETE is a thing that happens, and a 500 on an
        # admin screen is a worse way to find out.
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")

    if decision == "GRANTED":
        # The current set, read as bare ids rather than through ``viewer_rows``: that helper joins
        # the user table to build an admin screen's payload, and none of those fields is wanted here.
        existing = await db.designworkshopviewer.find_many(
            where={"designWorkshopId": row.designWorkshopId}
        )
        wanted = sorted({viewer.userId for viewer in existing} | {row.requestedById})
        await replace_viewers(
            row.designWorkshopId,
            wanted,
            creator_id=workshop.createdById,
            granted_by_id=getattr(admin, "id", None),
        )
    # THE SAME QUESTION THE QUEUE ANSWERS, ASKED THE SAME WAY — one predicate and not two, and asked
    # only where it is used, which is why it hangs off the ``elif`` rather than being computed above.
    # Access has THREE sources (the creator column, an admin role, a viewer row) and this guard used
    # to compute two of them by hand while ``requesterHasAccess`` in the very same response came from
    # :func:`_access_by_pair`, which computes all three. A designer promoted to ADMIN after asking was
    # therefore refusable: DENIED written, 200 returned, and ``requesterHasAccess: true`` in the body
    # that said it — the response contradicting itself about the one fact this 409 exists to protect.
    # Two hand-written copies of a three-source rule is how that happened, so there is now one, and
    # the guard and the field cannot disagree. ``tests/test_design_workshop_access_decide_guard.py``
    # pins all three arms without a database.
    elif (row.designWorkshopId, row.requestedById) in await _access_by_pair([row]):
        # REFUSING SOMEBODY WHO IS ALREADY IN WOULD BE A LIE ON THE SCREEN. This row would read
        # DENIED while the person kept reading the workshop, and an admin who pressed Refuse would
        # reasonably believe they had taken the access away. Removing a viewer is the viewers PUT
        # and only the viewers PUT — there is one way in and one way out — so this says which screen
        # rather than doing half of it here.
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                "This person can already open the workshop, so refusing the request would change "
                "nothing they can see. Take them off the workshop's viewers screen if you mean to "
                "remove their access; that is the only place a grant is undone."
            ),
        )

    await db.designworkshopaccessrequest.update(
        where={"id": row.id},
        data={
            "status": decision,
            "decidedById": getattr(admin, "id", None),
            "decidedAt": datetime.now(UTC),
            "decisionNote": plain(note) if note else None,
        },
    )
    fresh = await db.designworkshopaccessrequest.find_unique(
        where={"id": row.id}, include=_QUEUE_INCLUDE
    )
    if fresh is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    access = await _access_by_pair([fresh])
    return request_payload(
        fresh, has_access=(fresh.designWorkshopId, fresh.requestedById) in access
    )
