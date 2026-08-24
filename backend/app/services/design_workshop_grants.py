"""A printed JOIN CARD: minting it, redeeming it, revoking it — and the late-comer it must not lose.

=======================================================================================
WHAT THIS MODULE CHANGES ABOUT WHAT A CODE IS WORTH, AND WHAT IT DOES NOT
=======================================================================================

``services/design_workshop_access.py``'s header says this, and every word of it stays true:

    "THE SCANNED CODE IS NOT WHAT PREVENTS THIS, AND MUST NEVER BE SOLD AS IF IT WERE. It is
    tempting to say 'only somebody holding a card can ask, so there is nothing to enumerate'. That
    is false. The four check characters are FNV-1a over the payload, the algorithm ships to every
    browser, and ``frontend/lib/workshopCodes.ts`` states it outright: 'It is a typo detector and
    nothing more. It is not a signature and must never be described as one: the algorithm is in this
    file, so anyone can compute a valid check for any id.'"

That is a statement about the RECORD-NAMING code, ``DPW1:<letter>:<recordId>:CHCK``, and it is as
true today as it was. A record code is a LOCATOR. **THE INVARIANT, WRITTEN HERE BECAUSE THIS IS THE
MODULE MOST LIKELY TO ERODE IT: no endpoint may ever treat "presented a syntactically valid record
code" as grounds for access.** The moment one does, the FNV check becomes a credential and every
browser holds the forgery algorithm. The record path must keep answering the same 404 for a record
the caller may not see.

What this module adds is a DIFFERENT ARTEFACT with a DIFFERENT GRAMMAR::

    DPW2:J:<recordId>.<22-character secret>:CHCK

and its authority is the 110-bit ``secret``, not the check characters — those remain a typo detector
on the new grammar, doing the one job they were always worth. Three deliberate choices in that
string:

* **VERSION 2, not a new letter on version 1.** ``_SUPPORTED_CODE_VERSIONS`` only ever grows, and an
  ALREADY-SHIPPED client meeting a v2 string answers the correct sentence for free — "That card was
  printed against a newer code format (2) than this server reads. Update the app". We get the right
  refusal on every handset in the field without touching one of them.
* **The letter ``J``, and it is deliberately ABSENT from ``TYPE_LETTER``.** A join card is not a
  record: it must never be resolvable by the record-lookup path, and keeping it out of that table
  means no decoder in any of the three clients can resolve it to anything. ``J`` is reserved in a
  comment in :data:`JOIN_LETTER` so nobody spends it on a record type later.
* **FOUR colon-separated parts, with the secret joined to the id by a ``.``** — because every
  existing decoder in three languages expects exactly four, and ``.`` is inside the QR alphanumeric
  character set. Sixty characters total, which fits QR version 4 at error-correction level Q; the
  hand-written encoder in ``DwQrEncode.kt``/``lib/qrEncode.ts`` needs no change, and neither does
  its 24-row block table or its cross-language reference matrices.

=======================================================================================
THERE IS NO SIGNATURE, AND ``JWT_SECRET`` IS NOT THE KEY
=======================================================================================

Unforgeability is 110 bits of CSPRNG output in a UNIQUE-indexed column, not a MAC. A stateless
signed token exists to let a verifier decide WITHOUT server state — but single use, arrival-order
adjudication, the late-comer path and revocation all require server state per card no matter what.
Once the server must load the row to answer at all, putting the same claims in the card too buys
nothing on the server and costs eighty characters of QR, a signing key, a key-distribution story, a
rotation story and two crypto ports.

``JWT_SECRET`` is specifically wrong even if a key were wanted: it is constrained to HMAC by
``_ALLOWED_JWT_ALGORITHMS`` (anything a handset could verify offline must be ASYMMETRIC), offline
verification would mean shipping in an APK the key that mints session tokens for any subject
including the master admin, and ``SECRETS_ENCRYPTION_KEY`` is already derived from it — a third
dependent makes rotation permanently impossible. If a later wave wants offline verification it gets
its own asymmetric keypair, its own key-id, a public-key endpoint and a fail-closed default; the
``DPW2`` version number is the forward door, because a future ``DPW3:J:`` breaks not one card
printed under this design.

=======================================================================================
THE SECRET IS RETURNED ONCE AND IS NEVER STORED
=======================================================================================

:func:`mint_grant` answers with the code. After that call the secret exists on a card, in a
handset, and nowhere else: the database holds ``sha256(secret)`` and the last four characters. A
dump, a replica, a backup or a log line is therefore not a bundle of live keys. **DO NOT LOG A
SECRET, DO NOT PUT ONE IN AN ERROR MESSAGE, AND DO NOT ECHO ONE BACK TO A CALLER WHO DID NOT SEND
IT** — the redaction in :func:`redacted_code` is what the queue stores and it exists for this reason.

=======================================================================================
IDEMPOTENCY, AND WHY IT NEEDS TWO UNIQUE INDEXES IN TWO TABLES
=======================================================================================

``design_workshop_access.py``'s header states the rule this module inherits:

    "One row per (workshop, requester), enforced by a UNIQUE INDEX and not by a check in this file.
    A request typed in a courtyard reaches this server whenever the handset next finds signal, and a
    flaky link retries; a read-then-write here would be two round trips with a window in the middle,
    which is the shape this repository has already shipped a double-filed government record from."

That index is the right idempotency for an ASK and the WRONG one for a SEAT, so there is a second:
``RecordAccessTokenRedemption.@@unique([tokenId, userId])``. Without it, one person with two handsets
spends a multi-use card twice, and a replayed offline delivery spends a single-use card a second
time. Both are needed, they are different indexes, and that is why they are different tables.

The seat itself is allocated by ONE conditional compare-and-swap UPDATE (:func:`_consume_a_seat`),
never by a read-then-write, for exactly the reason quoted above. **SERVER ARRIVAL ORDER AT THAT
STATEMENT IS WHAT DECIDES WHO GETS THE FULL GRANT** — not ``createdAt`` (transaction-start time in
Postgres, identical for two racers) and certainly not the handset's clock.

=======================================================================================
THE UNTRUSTED CLOCK
=======================================================================================

``serverArrivedAt`` IS THE AUTHORITY. The device-reported scan time is EVIDENCE beside it and
nothing in this module compares it to decide anything: ordering by a number a phone's settings
screen can change hands the grant to whoever winds their clock back furthest, which is precisely
the spoof the requirement names.

THE COROLLARY, STATED SO IT IS NEVER A SURPRISE: this makes FIRST-TO-SYNC the winner, not
first-to-scan. "First to scan" would require holding every card unresolved for a settling window —
nobody gets access until it closes — and then adjudicating on a clock nobody can trust. There is no
third option, and requirement 6 is what makes first-to-sync survivable: the late-comer is not
refused, they get a capture-only foothold and their fieldwork is kept.

=======================================================================================
ENUMERATION: WHAT THIS MODULE'S ANSWERS MAY AND MAY NOT SAY
=======================================================================================

``design_workshop_access.py``'s header establishes the rule and the one route that cannot follow it.
Here is where each of these lands:

1. **:func:`mint_grant` FOLLOWS THE ORDINARY 404 RULE.** A caller who may not see the record gets
   ``require_record``'s own "Record not found", exactly as every other read does, so minting is not
   an existence oracle.

2. **:func:`redeem` ANSWERS THE REDEEMER ABOUT THEIR OWN CARD, so it may be specific — but it must
   never become a workshop-existence oracle.** UNKNOWN, REVOKED and BEYOND-GRACE-EXPIRED share ONE
   uniform refusal (:data:`CARD_REFUSED_DETAIL`) that names no workshop and does not say which of
   the three happened. ``ALREADY_SPENT`` and within-grace ``EXPIRED`` answer distinguishably because
   they MUST — they produce the provisional path, and a person needs to be told why they are
   capturing into a workspace that is not yet membership. That is safe for one reason and it is
   THE ENTROPY ALONE: reaching either answer requires presenting a genuine 110-bit secret, so the
   only caller who can tell ALREADY_SPENT from the uniform refusal is one already holding a real
   card for the record they are being told about.

   ⚠ **DO NOT REST THIS ON A RATE LIMIT. THERE ISN'T ONE, AND THE CLAUSE THAT SAID THERE WAS HAS
   BEEN REMOVED FROM THIS FILE AND FROM THE SCHEMA.** ``core/config.py`` states the measured fact
   next to ``scale_rate_limit_enabled``: nothing in this repository rate-limits any endpoint —
   ``app/scale/rate_limit.py`` is dead code whose own flag defaults off, no rate-limit middleware is
   installed in ``main.py``, and nginx carries no ``limit_req``. Against 110 bits of ``secrets``
   output that costs this design nothing, which is why the CONCLUSION stands unchanged. It matters
   because a future reader adding a SIXTH outcome will lean on the argument written here, and an
   argument resting on a limiter that does not exist would license a distinction the entropy does
   not actually pay for. If a sixth outcome is ever wanted, either wire the limiter first or make
   the entropy argument again from scratch for that specific answer.

3. **A REFUSAL WRITES NOTHING.** No redemption row, no request row, no counter moved. A table
   anybody can grow by posting random strings is a denial-of-service with an audit trail attached.

4. **THE GRAMMAR REFUSAL IS DECIDED FROM THE BODY ALONE AND BEFORE ANY DATABASE READ**, the same
   ordering ``file_request`` keeps and for the same reason. ``tests/test_design_workshop_grant_gate``
   asserts it with a tripwire ``db``: "HTTP 422 and the database was never touched". Do not move a
   lookup above :func:`decode_join_code`.

=======================================================================================
GRANTING STILL GOES THROUGH THE VIEWER MECHANISM AND NOWHERE ELSE
=======================================================================================

    "GRANTING GOES THROUGH THE VIEWER MECHANISM AND NOWHERE ELSE. ... there is no second way to
    become a viewer ... If you are about to make this table confer access, don't: that is two places
    to look when somebody has access they should not."

``DesignWorkshopViewer`` IS STILL THE ONLY THING THAT CONFERS ACCESS, and the eligibility rule that
guards it is still ``design_workshop_viewers``' — this module imports that module's own validator
rather than owning a second copy of it. What changed, and it changed because it was a live bug:
**this module now writes its own viewer row instead of calling ``replace_viewers``.**

    ⚠ **DO NOT PUT ``replace_viewers`` BACK HERE.** It is a WHOLE-SET REPLACE: it re-reads the
    workshop's viewers itself, diffs them against the set it was handed, and DELETES the difference.
    Expressing "add this one person" as "here is the whole set I read a moment ago, plus me" is a
    read-then-write with a window in the middle — the exact shape ``design_workshop_access``'s header
    says this repository "has already shipped a double-filed government record from" — and it fails
    in both directions:

    * TWO REDEEMERS OF A MULTI-USE CARD SYNCING AT ONCE, which is the offline-batch case this whole
      feature exists for. Both read an empty set; the first adds itself; the second hands over a set
      that does not contain the first, and ``replace_viewers`` DELETES the row the first one just
      got. The redemption still says ``FULL``, the queue row still says ``GRANTED``, ``tokenId`` is
      still stamped — and that person has no access, in "the state nothing on any screen would ever
      correct" that :func:`decide`'s own comment orders its writes to avoid.
    * A SCAN CONCURRENT WITH AN ADMIN REMOVING SOMEBODY. The admin saves the viewers screen without
      designer X; a redemption whose read still held X puts X back, with ``grantedById`` naming the
      card's issuer and ``tokenId`` NULL — a revocation undone by an unrelated person's scan, and a
      provenance trail that names an issuer for a row no card produced.

    ``_consume_a_seat``'s compare-and-swap serialises the SEAT. Nothing serialises a whole-set
    replace, and nothing here can: the admin viewers screen calls ``replace_viewers`` too, without a
    lock, so no lock taken on this side would close the second case. **The only concurrency-safe way
    to say "add exactly me and change nobody else" is to write exactly that**, which is what
    :func:`_write_the_viewer_row` does — one ``create_many(skip_duplicates=True)`` naming one
    account, inside the same transaction as the redemption receipt and the queue row.

WHAT THAT COSTS, STATED SO NOBODY HAS TO REDISCOVER IT:

* THIS MODULE IS NOW A SECOND WRITER OF ``DesignWorkshopViewer``. It is not a second WAY TO BECOME
  ONE: the table is unchanged, ``has_viewer_grant`` still reads row existence and nothing on it, and
  the eligibility rule is still the one function in ``design_workshop_viewers`` that the admin screen
  uses — imported, never copied, because two copies of a security rule is how a suspended designer
  comes to hold access their next sign-in refuses. ``replace_viewers`` remains the only place a
  viewer row is REMOVED, which is what ``decide``'s 409 and ``revoke_grant`` both point at.
* THE VALIDATION IS NARROWER, AND THAT IS A FIX RATHER THAN A LOSS. ``replace_viewers`` validated the
  whole resulting set, so a colleague's lapsed empanelment refused an unrelated person's induction —
  handled here by turning it into a foothold, but still a refusal for a reason the scanner could not
  act on. Only the REDEEMER is validated now, so reason ``INELIGIBLE`` means what it says: this
  account cannot hold a viewer row. It is checked BEFORE the seat is reached, so an ineligible
  scanner never spends one.

=======================================================================================
PROVISIONAL MEMBERSHIP IS CAPTURE-ONLY, AND IT IS NOT A VIEWER ROW
=======================================================================================

**THIS IS THE DECISION MOST LIKELY TO BE "SIMPLIFIED" INTO A BUG.** ``has_viewer_grant`` reads the
EXISTENCE of a ``DesignWorkshopViewer`` row, and it is consulted from four places — plus two reads
that do not go through it at all (``questionnaire_forms._visible_questionnaire_where`` writes the
relation filter by hand; ``records._design_workshop_media_branches`` follows ``visible_to_clause`` on
the stated instruction that "the day that widens again the audio widens with it"). A ``level`` column
on the viewer table would open every one of those six to an unadjudicated scan until each was
individually taught the difference, and missing one leaks another designer's fieldwork or an
artisan's recorded voice.

So a foothold is a row in ``DesignWorkshopProvisionalMember`` and **nothing that decides read access
consults that table.** To every existing read a provisional member is a stranger. That is the
correct default, and it is the only shape that is safe to land without editing those six modules.
``tests/test_design_workshop_provisional_isolation.py`` is the tripwire.

WHAT THAT MEANS AND WHAT IT DOES NOT, stated plainly because the honest half matters:
:func:`may_capture` below is the predicate a later wave hangs the capture path on. Until the module
that owns ``load_workshop_or_404`` calls it, a provisional member can be RECORDED, can be seen by an
admin, and can be UPGRADED — and cannot yet post stage entries. The foothold and the queue row are
what stop their offline work being orphaned when that wave lands; the alternative was refusing them
now, which is the outcome requirement 6 forbids outright.
"""

import hashlib
import logging
import re
import secrets
from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import HTTPException, status

from app.core.db import db
from app.core.deps import is_admin
from app.services.design_workshop_access import ScannedCodeRefused, add_one_viewer, code_check
from app.services.design_workshop_viewers import (
    _assert_every_id_may_be_granted,
    has_viewer_grant,
)
from app.services.records import plain

logger = logging.getLogger(__name__)


# --------------------------------------------------------------------------------------
# The grammar
# --------------------------------------------------------------------------------------
#
# ⚠ EVERY CONSTANT BELOW HAS TO BE KEPT IN STEP BY HAND with the v1 grammar in
# `services/design_workshop_access.py`, `frontend/lib/workshopCodes.ts` and
# `android/.../data/DwWorkshopCodes.kt` — the same hand-kept relationship every constant in those
# files already has, because no language here can read another's source. What is NOT duplicated is
# `code_check`: it is IMPORTED from the v1 module, because two copies of a check function is how a
# card comes to print one way and validate another.

#: The three characters that say a code belongs to this application at all. Shared with v1.
CODE_NAMESPACE = "DPW"

#: The payload version a join card is written at, and the reason it is 2 rather than 1.
#:
#: A NEW VERSION RATHER THAN A NEW LETTER ON v1 is what buys the right refusal on clients that have
#: already shipped: `_SUPPORTED_CODE_VERSIONS` only ever grows, and an old build meeting this string
#: already answers "That card was printed against a newer code format (2) than this server reads.
#: Update the app" — the correct sentence, on every handset in the field, for free. A new LETTER on
#: v1 would instead have produced "that code points at a different kind of record", which sends
#: somebody looking for a different card.
JOIN_CODE_VERSION = 2

#: **J for Join, AND IT IS DELIBERATELY NOT IN ``TYPE_LETTER``.**
#:
#: A join card is not a record. Keeping this letter out of the record-type table in all three clients
#: is what makes it structurally impossible for the record-lookup path to resolve one — there is no
#: entry for it to find — and it leaves `tests/test_workshop_code_letters.py` and the web enum
#: untouched.
#:
#: ⚠ `J` IS RESERVED. Do not spend it on a record type later; the ten letters in use are
#: A C W D S T Q M G P and `J` now means "this is a credential, not a locator".
JOIN_LETTER = "J"

#: Crockford base32 — no I, L, O or U, the four characters people get wrong reading a code off a
#: card. The same alphabet the check characters use, so a card carries one character set and not two.
_SECRET_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

#: 22 characters of Crockford base32 = **110 bits**, and that number is the whole security argument.
#:
#: It is chosen against a QR budget rather than plucked: `DwQrEncode`/`lib/qrEncode.ts` are
#: alphanumeric-mode only with MAX_VERSION 6, and the cards are printed at error-correction level Q
#: (a fortnight in a workshop), which caps a version-4 33x33 symbol at 67 characters. The whole code
#: is `DPW2:J:` (7) + a 25-character cuid + `.` + 22 + `:CHCK` (5) = 60. One version above today's
#: 29x29 symbol, still printable, still typeable, and the encoder — including its 24-row block table
#: and its cross-language reference matrices — needs NO CHANGE. A 64-byte signature would have been
#: 141 characters, which does not fit at level Q at any version that encoder can draw.
_SECRET_LENGTH = 22

#: Crockford's reading rules, applied to the SECRET and to the CHECK. `U` is deliberately absent from
#: the alphabet and is left to fail rather than guessed at.
#:
#: APPLYING THEM TO THE SECRET IS SAFE AND APPLYING THEM TO THE ID IS NOT, which is the asymmetry
#: `decode_design_workshop_code` already documents: `0` and `o` are both legal in a cuid, so
#: "correcting" one would corrupt an id that was typed correctly. The secret is drawn from this
#: alphabet by construction, so a `0` in it can only ever have been an `O` misread.
_CONFUSABLES = {"I": "1", "L": "1", "O": "0"}

#: The shape of the secret after folding. Exactly the alphabet, exactly the length — a secret one
#: character short is not a near miss, and there is nothing to be tolerant of.
_SECRET_PATTERN = re.compile(f"^[{_SECRET_ALPHABET}]{{{_SECRET_LENGTH}}}$")

#: The shape of an identifier this repository issues, copied from the v1 grammar for the reason that
#: file gives: lower case only, hyphens allowed for the UUID client keys a row carries before it has
#: ever reached the server.
_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{7,63}$")

#: The prefixes a workshop draft carries while it exists ONLY on the device that made it. Refused
#: with their own sentence rather than folded into "malformed", because the honest next action is
#: "ask them to sync their device" and not "read the card again".
_DEVICE_LOCAL_ID_PREFIXES = ("dwlocal-", "local-")

#: Characters that cannot reach Postgres inside an id — NUL, the other control bytes, and a LONE
#: SURROGATE (half an emoji from a phone that truncated a paste), which cannot be encoded to UTF-8 at
#: all and fails inside the driver before Postgres is reached.
#:
#: A THIRD COPY of `design_workshop_viewers._UNSTORABLE_IN_AN_ID`, duplicated rather than imported for
#: the reason the SECOND copy gives in `design_workshop_access`: that name is private to its module,
#: and reaching into somebody else's internals for four bytes of regex couples two files for nothing.
#: The three are used for three different purposes anyway — there it refuses a write and names the
#: offending account, next door it makes an unaskable id take the same silent no-op as any other id
#: that resolves to nothing, and here it makes one take the ordinary 404.
_UNSTORABLE_IN_AN_ID = re.compile(r"[\x00-\x1f\x7f\ud800-\udfff]")

#: Digits, ASCII only. `\d` in Python also matches Devanagari and Arabic-Indic digits where
#: JavaScript's `/^\d+$/` does not — a version written in another script would parse here and be
#: refused by the client, which is a disagreement about what a card says.
_ASCII_DIGITS = re.compile(r"^[0-9]+$")

#: How long a freshly minted card is good for, when the caller does not say.
#:
#: NOT UNLIMITED, and the column is NOT NULL for the same reason: a join card is a bearer credential
#: that spends a fortnight in a workshop and then lives in somebody's bag for ever. A fortnight is
#: the length of the workshop the cards are printed for.
DEFAULT_GRANT_DAYS = 14

#: The longest a card may be minted for. A ceiling and not a policy: an admin who wants a permanent
#: key to a workshop printed on paper should have to mint a new one four times a year, because the
#: alternative is a credential nobody remembers exists.
MAX_GRANT_DAYS = 120

#: **THE OFFLINE SYNC GRACE WINDOW.** An expired card NEVER yields a full grant — expiry is judged
#: by SERVER time at arrival, the same authority that decides order, so a device clock cannot buy an
#: extension. But a genuine scan two days before expiry that syncs three days after it must not be
#: thrown away: within this window it yields a PROVISIONAL foothold and a queue entry with the
#: evidence shown, and beyond it the uniform refusal. A courtyard with no signal for a fortnight is
#: the ordinary case this whole feature exists for.
GRANT_SYNC_GRACE_DAYS = 30

#: How many un-revoked, unexpired, unspent cards a NON-ADMIN issuer may hold outstanding on one
#: record at a time.
#:
#: THE GAP THIS CLOSES, NAMED RATHER THAN LEFT IMPLICIT: single-use bounds one CARD, not one ISSUER.
#: A designer minting fifty single-use cards over a month admits fifty people, which would make the
#: reasoning behind "multi-use is admin-only" quietly false. A small cap keeps the courtyard case
#: that motivated the feature reachable — hand the person beside you a card — without handing a
#: designer the membership power they are denied.
VIEWER_OUTSTANDING_GRANT_LIMIT = 3

#: The ten record types a code can name, in `TYPE_LETTER`'s declaration order, paired with their
#: letters. An ELEVENTH hand-kept copy of that table and the reason
#: `tests/test_design_workshop_grant_tokens.py` pins it: a type added in the browser and forgotten
#: here is a code that encodes and a card that cannot be issued.
RECORD_TYPE_LETTERS: dict[str, str] = {
    "ARTISAN": "A",
    "CRAFT": "C",
    "WORKSHOP": "W",
    "PRODUCT": "D",
    "PROCESS": "S",
    "TOOL": "T",
    "QUESTIONNAIRE": "Q",
    "MEDIA": "M",
    "DESIGN_WORKSHOP": "G",
    "PROTOTYPE": "P",
}

#: The record types a card can actually INDUCT somebody into, today.
#:
#: A FACT ABOUT THE SCHEMA AND NOT A POLICY. Of the ten, only `designWorkshop`
#: (`DesignWorkshopViewer`) and `workshop` (`WorkshopAssignment`) have a per-record membership table
#: at all; the other eight are gated by `records.owned_or_granted_where`, which is ACCOUNT-level, so
#: for them there is nothing to be inducted into and a card could only ever be a pointer.
#:
#: REQUIREMENT 8, READ PRECISELY. The SCANNING EXPERIENCE — back camera, overlay, live detection,
#: offline capture with the scan time recorded, showing a QR — applies to every record type, and
#: none of that is this module's business. The INDUCTION SEMANTICS apply only where a membership
#: exists to be granted. An artisan card confers nothing, so there is nothing for it to be
#: single-use ABOUT, and `mint_grant` refuses the other eight rather than minting a card that
#: silently admits nobody. If record-scoped provisional capture is ever wanted — scan a tool card,
#: capture against that tool without workshop membership — that is a DIFFERENT feature with a
#: different table, and it should be decided on its own rather than smuggled in under this grammar.
MEMBERSHIP_RECORD_TYPES = frozenset({"DESIGN_WORKSHOP"})


def secret_hash(secret: str) -> str:
    """SHA-256 of a secret, hex, lower case — the only form of it this database ever holds.

    A PLAIN DIGEST AND NOT A PASSWORD KDF, deliberately. bcrypt/argon2 exist to make a DICTIONARY
    attack expensive against a low-entropy human-chosen string; this input is 110 bits of CSPRNG
    output, so there is no dictionary, and a per-row salt would destroy the one property the lookup
    needs — that a card can be found by its secret in a single indexed equality probe. Hashing at all
    is what stops a dump being a bundle of live keys; stretching it would buy nothing and cost the
    index.
    """
    return hashlib.sha256(secret.encode("utf-8")).hexdigest()


def mint_secret() -> str:
    """A fresh 110-bit secret in Crockford base32.

    ``secrets.choice`` and not ``random``: this is the credential, and ``random`` is a Mersenne
    twister whose internal state is recoverable from its output. There is no rejection sampling to
    get wrong because the alphabet is exactly 32 characters, so every draw is uniform.
    """
    return "".join(secrets.choice(_SECRET_ALPHABET) for _ in range(_SECRET_LENGTH))


def encode_join_code(record_id: str, secret: str) -> str:
    """The card, as it is printed and as it is scanned.

    UPPER CASE, matching what the clients print: ``workshopCodes.ts`` builds a payload as
    ``${NAMESPACE}${VERSION}:${letter}:${id.toUpperCase()}``, and the QR alphanumeric mode this
    repository's encoder uses has no lower case at all. The decoder folds case back, so a code typed
    off a card under a tin roof reads the same as one scanned.
    """
    prefix = f"{CODE_NAMESPACE}{JOIN_CODE_VERSION}:{JOIN_LETTER}:{record_id.upper()}.{secret}"
    return f"{prefix}:{code_check(prefix)}"


def decode_join_code(raw: str) -> tuple[str, str, str]:
    """Read a scanned join card. Answers ``(record_id, secret, canonical_code)``.

    TOLERANT OF WHAT A HUMAN DOES TO IT, STRICT ABOUT WHAT IT MEANS — the same rule the v1 decoder
    keeps, because the two have to accept the same shapes of paste. Case and whitespace go first,
    including the grouping spaces ``formatWorkshopCodeForPrint`` adds under the QR so that what is
    printed can be typed straight back. Everything after that is exact.

    **NOTHING HERE READS THE DATABASE, AND THAT IS A SECURITY PROPERTY RATHER THAN AN
    OPTIMISATION.** Every refusal below is a statement about the string that was sent, true or false
    before any lookup, which is what makes saying them out loud safe — see the header's ENUMERATION
    section. ``tests/test_design_workshop_grant_gate.py`` stands a tripwire in for ``db`` and asserts
    "422 with the database never touched"; move a lookup above this call and it goes red.

    Every refusal names what to do next, because these sentences reach a designer standing in a
    courtyard, and none of them says anything about which records exist.
    """
    text = re.sub(r"\s+", "", raw or "").upper()
    if not text:
        raise ScannedCodeRefused("Nothing was scanned or typed.")

    parts = text.split(":")
    if len(parts) != 4 or not parts[0].startswith(CODE_NAMESPACE):
        raise ScannedCodeRefused(
            "That is not a workshop join card. Join cards begin “DPW”; a shop barcode, a payment "
            "code or a web address will not let you into a workshop."
        )

    version_text = parts[0][len(CODE_NAMESPACE) :]
    if not _ASCII_DIGITS.match(version_text):
        raise ScannedCodeRefused(
            "That is not a workshop join card. Join cards begin “DPW” followed by a version number."
        )
    version = int(version_text)
    if version != JOIN_CODE_VERSION:
        # Two different sentences, because they send somebody to two different places. A LOWER
        # version is a record tag — the card that NAMES a workshop rather than admitting you to one,
        # which is a thing people will scan here by mistake because it is the card they had before.
        # A HIGHER version is a card printed against a newer format than this server reads, which is
        # the v1 module's own wording and is kept identical so a person meets one sentence.
        if version < JOIN_CODE_VERSION:
            raise ScannedCodeRefused(
                "That is a workshop's own tag, not a join card — it names the workshop but does not "
                "let anybody in. Ask whoever runs the workshop to print you a join card, or send "
                "them the tag and ask to be added."
            )
        raise ScannedCodeRefused(
            f"That card was printed against a newer code format ({version}) than this server reads. "
            f"Update the app, or ask an administrator to add you from the workshop's viewers screen."
        )

    if parts[1] != JOIN_LETTER:
        # OURS AND WELL FORMED, but it points at a record rather than being a join card. Named
        # separately from "not one of ours" because somebody scanning the wrong card off a lanyard
        # needs to be told to find the join card, not that their scanner is broken.
        raise ScannedCodeRefused(
            "That code belongs to this application but is not a join card — it names a record. Scan "
            "the join card you were handed, the one printed to let somebody in."
        )

    body = parts[2]
    if body.count(".") != 1:
        raise ScannedCodeRefused(
            "This join card is damaged or was typed incompletely. Check it against the card, "
            "character by character."
        )
    id_text, secret_text = body.split(".", 1)

    record_id = id_text.lower()
    if record_id.startswith(_DEVICE_LOCAL_ID_PREFIXES):
        # BEFORE the pattern below, which a `dwlocal-`/`local-` id passes perfectly well. Without
        # this the card would decode cleanly and name a record that exists on exactly one handset.
        raise ScannedCodeRefused(
            "That card names a workshop that had not been shared yet when it was printed — it only "
            "ever meant anything on the device that made it. Ask whoever created the workshop to "
            "sync their device and print a fresh card."
        )
    if not _ID_PATTERN.match(record_id):
        raise ScannedCodeRefused(
            "This join card is damaged or was typed incompletely — the identifier in it is not a "
            "whole one. Check it against the card."
        )

    # THE CONFUSABLE FOLD IS APPLIED TO THE SECRET AND NOT TO THE ID. See `_CONFUSABLES`: the secret
    # is drawn from an alphabet with no I, L, O or U, so a `0` in it can only ever be a misread `O`;
    # a cuid legitimately contains both, so folding one would corrupt an id typed correctly.
    secret = "".join(_CONFUSABLES.get(character, character) for character in secret_text)
    if not _SECRET_PATTERN.match(secret):
        raise ScannedCodeRefused(
            "This join card is damaged or was typed incompletely — the part after the full stop is "
            "not a whole one. Read it off the card again, character by character."
        )

    typed_check = "".join(_CONFUSABLES.get(character, character) for character in parts[3])
    prefix = f"{CODE_NAMESPACE}{version}:{JOIN_LETTER}:{id_text}.{secret}"
    if len(typed_check) != 4 or typed_check != code_check(prefix):
        # THE MOST VALUABLE REFUSAL HERE, and the honest limit on it, said in one place so no screen
        # can imply more: the four characters are still FNV-1a and still a TYPO DETECTOR. They catch
        # a card read one character wrong — which is the failure mode a courtyard actually produces —
        # and they CANNOT catch a forgery, because the algorithm ships to every browser. What catches
        # a forgery is the 110-bit secret failing to match a row, and that can only happen ONLINE.
        raise ScannedCodeRefused(
            "This join card does not check out, so one of its characters is wrong. Read it off the "
            "card again, character by character."
        )

    return record_id, secret, f"{prefix}:{typed_check}"


def redacted_code(record_id: str, secret: str) -> str:
    """The form of a join card that is safe to STORE and to show an admin.

    ``DPW2:J:<recordId>.…<last4>:CHCK`` — enough for an admin to match the card in somebody's hand,
    useless if the table it sits in leaks.

    **THIS EXISTS BECAUSE ``DesignWorkshopAccessRequest.scannedCode`` MUST NEVER HOLD A LIVE
    SECRET.** That column's own comment justified storing the whole string with "it carries no
    identity data by construction" — which is TRUE of a v1 record code and FALSE of a join card,
    because a join card is a bearer credential. The comment is corrected in the same change that
    added this function; leaving it standing is how the next reader stores the whole thing.

    THE CHECK CHARACTERS ARE RECOMPUTED OVER THE REDACTED STRING, not carried over from the real
    one. Carrying them over would make the stored string look like a code that fails its own check —
    which is the one refusal in this module people trust — and an admin comparing it against a card
    would conclude the card was damaged.
    """
    prefix = f"{CODE_NAMESPACE}{JOIN_CODE_VERSION}:{JOIN_LETTER}:{record_id.upper()}.…{secret[-4:]}"
    return f"{prefix}:{code_check(prefix)}"


# --------------------------------------------------------------------------------------
# Reading: what level this account holds
# --------------------------------------------------------------------------------------


async def provisional_member(workshop_id: str, user_id: str) -> Any | None:
    """The provisional foothold row for this pair, or ``None``.

    ⚠ **THIS IS NOT A READ GATE AND MUST NEVER BE CALLED FROM ONE.** ``has_viewer_grant`` is the
    only predicate that decides whether an account may READ a design workshop, and it does not — and
    must not — consult this table. A foothold is permission to CAPTURE, once the wave that owns
    ``load_workshop_or_404`` opens that path, and permission to read nothing at all.
    """
    if not workshop_id or not user_id:
        return None
    return await db.designworkshopprovisionalmember.find_unique(
        where={
            "designWorkshopId_userId": {"designWorkshopId": workshop_id, "userId": user_id}
        }
    )


async def may_capture(workshop_id: str, user: Any) -> bool:
    """May this account record ITS OWN fieldwork in this workshop?

    THREE ARMS, AND THE THIRD IS THE NEW ONE: an admin, a full viewer, or a provisional foothold.
    The creator arm is deliberately absent because the caller already holds the workshop row when it
    asks — ``load_workshop_or_404`` compares ``createdById`` itself — and re-reading it here would be
    a second copy of a rule that already has one home.

    **IT IS A WRITE PREDICATE AND NOT A READ PREDICATE, AND THE TWO MUST NOT BE MERGED.** Answering
    ``True`` here says the account may create rows attributed to itself. It says NOTHING about
    reading anybody else's, and the row-level filter that keeps other designers' work out
    (``entry_rows(..., author_id=...)``) is the other half. A wave that hangs a READ on this
    predicate has quietly given a spent-card scanner the whole workshop.

    NOTHING CALLS THIS YET, and that is a wave boundary rather than dead code:
    ``services/design_workshops.py`` is where the capture path lives and it is not this wave's to
    edit. The predicate is written here, beside the table it reads and the reasoning that constrains
    it, so the wave that lands the capture route does not have to re-derive either.
    """
    user_id = getattr(user, "id", "")
    if not user_id:
        return False
    if is_admin(user):
        return True
    if await has_viewer_grant(workshop_id, user_id):
        return True
    return await provisional_member(workshop_id, user_id) is not None


# --------------------------------------------------------------------------------------
# Minting
# --------------------------------------------------------------------------------------


async def _workshop_for_issuer_or_404(workshop_id: str, issuer: Any) -> Any:
    """The workshop this account may print cards for, or the ordinary 404.

    **404 AND NOT 403**, with ``require_record``'s own detail string, exactly as ``load_workshop_or_404``
    does and for the reason argued there: a 403 confirms the id is real, which is the enumeration
    oracle this repository refuses everywhere it can. Minting CAN follow that rule — unlike the ask
    route, whose whole purpose is to be called by somebody who may not see the record — so it does.

    WHO MAY PRINT A CARD: an admin, the creator, or a current FULL viewer. A viewer is included
    because the courtyard case is the entire motivation — the person standing beside you is being
    handed a card by somebody who is already on the workshop, not by an administrator two districts
    away — and it is bounded three ways: single-use only, a cap on outstanding cards
    (:data:`VIEWER_OUTSTANDING_GRANT_LIMIT`), and every card visible with its issuer and revocable on
    the admin's screen. A PROVISIONAL foothold is deliberately NOT enough: somebody whose own
    standing has not been adjudicated must not be able to admit anybody.
    """
    wanted = plain(workshop_id).strip().lower()
    not_found = HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if not wanted or _UNSTORABLE_IN_AN_ID.search(workshop_id):
        # An id that cannot reach Postgres would be a 500 with a DataError in the log. It resolves to
        # nothing, so it gets the answer every other id that resolves to nothing gets.
        raise not_found

    workshop = await db.designworkshop.find_unique(where={"id": wanted})
    if workshop is None or getattr(workshop, "deletedAt", None) is not None:
        # A SOFT-DELETED WORKSHOP CANNOT HAVE CARDS PRINTED FOR IT, matching `file_request`'s refusal
        # to accept new asks about one: what must not happen is a batch of cards admitting people to
        # a record that is on its way out.
        raise not_found

    issuer_id = getattr(issuer, "id", "")
    if not issuer_id:
        raise not_found
    if is_admin(issuer) or workshop.createdById == issuer_id:
        return workshop
    if await has_viewer_grant(wanted, issuer_id):
        return workshop
    raise not_found


async def mint_grant(
    issuer: Any,
    *,
    record_type: str,
    record_id: str,
    max_uses: int | None,
    days_valid: int | None,
    label: str | None,
) -> dict[str, Any]:
    """Print one join card. **Answers with the secret, and this is the only time it ever exists.**

    -- WHAT IS ADMIN-ONLY, AND WHY IT IS NOT A STYLE PREFERENCE --------------------------------

    **A MULTI-USE CARD IS ADMIN-ONLY, UNCONDITIONALLY.** Single use is the default for every other
    issuer and is the database's default too (``RecordAccessToken.maxUses`` is ``@default(1)``), so
    a caller who says nothing gets the safe thing.

    The reasoning, because a reader will otherwise "simplify" it: a designer cannot create a
    workshop — ``can_create_design_workshops`` refuses them, and every route in
    ``design_workshop_viewers.py`` is ``Depends(require_admin)`` — so letting them mint a card that
    admits ARBITRARILY MANY people would hand them exactly the membership power those two rules
    deny. Single use bounds the card to one person, which is the same act as walking somebody over
    and asking an admin to add them, only asynchronous.

    AND THE GAP IN THAT REASONING, NAMED RATHER THAN LEFT FOR SOMEBODY TO FIND: single use bounds
    one CARD, not one ISSUER. Fifty single-use cards over a month admits fifty people. That is what
    :data:`VIEWER_OUTSTANDING_GRANT_LIMIT` is for, and it is a cap on OUTSTANDING cards rather than
    on cards ever minted, because the honest thing to bound is how many unspent keys are loose at
    once.

    -- THE SECOND ADMIN-ONLY RULE, WHICH IS SUBTLER ------------------------------------------------

    ``maxUses=None`` means UNLIMITED and is admin-only for the same reason multi-use is, only more
    so. It is accepted at all because a printed sheet for a fortnight-long workshop with a shifting
    cast is a real thing an administrator wants, and refusing it would be answered by minting twenty
    cards, which is worse: twenty secrets loose instead of one, and no way to close them all at once.

    -- WHAT A CARD MAY BE MINTED FOR ---------------------------------------------------------------

    Only a record type that HAS a membership to grant — today just ``DESIGN_WORKSHOP``; see
    :data:`MEMBERSHIP_RECORD_TYPES` for why that is a fact about the schema and not a policy, and for
    how requirement 8 divides. Refused LOUDLY, with a 422, rather than minted: a card that admits
    nobody is worse than no card, because somebody prints twenty of them and hands them out.
    """
    wanted_type = (record_type or "").strip().upper()
    if wanted_type not in RECORD_TYPE_LETTERS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"A record type is one of {', '.join(sorted(RECORD_TYPE_LETTERS))}.",
        )
    if wanted_type not in MEMBERSHIP_RECORD_TYPES:
        # A statement about the request body — it does not depend on which records exist — so saying
        # it out loud discloses nothing. See the header's ENUMERATION section.
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "A join card can only be printed for a design workshop. Cards for other kinds of "
                "record would not let anybody in: an artisan, a tool or a product is not something "
                "a person is a member of, and access to those is decided per account rather than "
                "per record."
            ),
        )

    issuer_is_admin = is_admin(issuer)
    if max_uses is not None and max_uses < 1:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="A join card is good for at least one person.",
        )
    if not issuer_is_admin and max_uses != 1:
        # THE NON-NEGOTIABLE. Both shapes of "more than one person" are refused here — a count above
        # one and the unlimited NULL — and the sentence says which screen fixes it rather than merely
        # refusing, because the person reading it is standing next to somebody they want to admit.
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Only an administrator can print a card that lets more than one person in. You can "
                "print a card for one person as many times as you need; ask an administrator if you "
                "want a card for a whole group."
            ),
        )

    # THE RECORD IS READ AND THE ISSUER CHECKED BEFORE ANYTHING IS WRITTEN, and the order matters
    # only in that a refusal must not leave a card behind.
    await _workshop_for_issuer_or_404(record_id, issuer)
    issuer_id = getattr(issuer, "id", "")

    wanted_days = DEFAULT_GRANT_DAYS if days_valid is None else days_valid
    if wanted_days < 1 or wanted_days > MAX_GRANT_DAYS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"A join card is valid for between 1 and {MAX_GRANT_DAYS} days.",
        )

    now = datetime.now(UTC)
    record_key = plain(record_id).strip().lower()

    if not issuer_is_admin:
        # THE OUTSTANDING CAP, and it counts what is actually loose rather than what was ever
        # printed: un-revoked, unexpired, and with a seat still on it. A designer who minted three
        # cards last month that were all used can mint again; one holding three unspent keys cannot.
        outstanding = await db.recordaccesstoken.count(
            where={
                "recordType": wanted_type,
                "recordId": record_key,
                "issuedById": issuer_id,
                "revokedAt": None,
                "expiresAt": {"gt": now},
                "usesConsumed": 0,
            }
        )
        if outstanding >= VIEWER_OUTSTANDING_GRANT_LIMIT:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=(
                    f"You already have {VIEWER_OUTSTANDING_GRANT_LIMIT} unused join cards printed "
                    f"for this workshop. Let somebody use one, or ask an administrator to cancel "
                    f"them, before printing another."
                ),
            )

    secret = mint_secret()
    token = await db.recordaccesstoken.create(
        data={
            "recordType": wanted_type,
            "recordId": record_key,
            "secretHash": secret_hash(secret),
            "secretLast4": secret[-4:],
            "issuedById": issuer_id or None,
            "maxUses": max_uses,
            "expiresAt": now + timedelta(days=wanted_days),
            "label": plain(label) if label else None,
        }
    )

    # LOGGED WITHOUT THE SECRET AND WITHOUT THE CODE. The token id is not a credential; the secret
    # is, and a log line is the single easiest place for one to leak into a place nobody thinks of as
    # a database.
    logger.info(
        "join card minted: token=%s recordType=%s record=%s maxUses=%s issuer=%s",
        token.id,
        wanted_type,
        record_key,
        "unlimited" if max_uses is None else max_uses,
        issuer_id or "unknown",
    )
    return {**grant_payload(token), "code": encode_join_code(record_key, secret)}


def grant_payload(row: Any, *, issuer: Any = None) -> dict[str, Any]:
    """One card as the admin's list reads it. **NEVER carries the secret.**

    HAND-PROJECTED rather than encoded over the row, on ``request_payload``'s stated reasoning: an
    encoder that walked the model would put whatever this table gains next onto an
    access-administration screen, and this is the one table in the schema holding a credential.
    ``secretLast4`` is the only part of the secret that appears, which is twenty bits — enough to
    match the card in somebody's hand and useless to a guesser.
    """
    return {
        "id": row.id,
        "recordType": str(getattr(row.recordType, "value", row.recordType)),
        "recordId": row.recordId,
        "secretLast4": row.secretLast4,
        "maxUses": row.maxUses,
        "usesConsumed": row.usesConsumed,
        "expiresAt": row.expiresAt.isoformat() if row.expiresAt else None,
        "revokedAt": row.revokedAt.isoformat() if row.revokedAt else None,
        "label": row.label,
        "createdAt": row.createdAt.isoformat() if row.createdAt else None,
        "issuedBy": (
            None
            if issuer is None
            else {
                "id": row.issuedById,
                "name": getattr(issuer, "name", "") or "",
                "email": getattr(issuer, "email", "") or "",
            }
        ),
    }


#: How many cards one listing returns. A CEILING, NOT A PAGE SIZE, on ``QUEUE_LIMIT``'s reasoning:
#: an unbounded ``find_many`` over a table that only grows is how a screen that worked for two years
#: starts timing out. ``truncated`` says on the wire that the answer was cut, because an admin who
#: cannot see a card cannot revoke it.
GRANT_LIST_LIMIT = 200


async def list_grants(record_id: str, viewer: Any) -> dict[str, Any]:
    """Every join card printed for one workshop, newest first, for whoever may see them.

    THE SAME DOOR AS MINTING, deliberately: if you may print a card you may see the cards that exist,
    because otherwise a designer at the outstanding cap is told to "let somebody use one" with no way
    to find out which. It is the ordinary 404 for anybody else — see
    :func:`_workshop_for_issuer_or_404`.
    """
    await _workshop_for_issuer_or_404(record_id, viewer)
    rows = await db.recordaccesstoken.find_many(
        where={"recordType": "DESIGN_WORKSHOP", "recordId": plain(record_id).strip().lower()},
        include={"issuedBy": True},
        order=[{"createdAt": "desc"}, {"id": "desc"}],
        take=GRANT_LIST_LIMIT + 1,
    )
    truncated = len(rows) > GRANT_LIST_LIMIT
    if truncated:
        rows = rows[:GRANT_LIST_LIMIT]
        logger.warning(
            "the join-card list hit its ceiling of %s rows for record %s; the answer is truncated "
            "and says so",
            GRANT_LIST_LIMIT,
            record_id,
        )
    return {
        "grants": [
            grant_payload(row, issuer=getattr(row, "issuedBy", None)) for row in rows
        ],
        "truncated": truncated,
    }


async def revoke_grant(token_id: str, revoker: Any) -> dict[str, Any]:
    """Cancel one card. Idempotent, and it does NOT remove anybody it already let in.

    THE TWO HALVES ARE SEPARATE ACTIONS ON PURPOSE. Revoking stops the card admitting anybody
    FURTHER; taking access away from somebody it already admitted is the viewers PUT, and it is the
    only place a grant is undone — the same division ``decide``'s 409 already names. A revoke that
    also evicted people would mean an admin cancelling a misprinted batch silently removed the
    colleagues who had legitimately used it.

    ``DesignWorkshopViewer.tokenId`` is what makes the other half possible: "everybody this batch
    let in" is one indexed read, and the admin does it deliberately on the screen that shows them.

    REVOKED AND NOT DELETED. The row is the only record that the card existed, that it was minted by
    somebody, and that it admitted the people whose viewer rows point at it.
    """
    wanted = plain(token_id).strip()
    not_found = HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if not wanted:
        raise not_found
    token = await db.recordaccesstoken.find_unique(where={"id": wanted})
    if token is None:
        raise not_found

    # THE SAME DOOR AS MINTING, over the record the card names. Whoever may print cards for a
    # workshop may cancel them, which is what stops a designer's own misprint needing an admin.
    await _workshop_for_issuer_or_404(token.recordId, revoker)

    if token.revokedAt is None:
        await db.recordaccesstoken.update(
            where={"id": token.id},
            data={"revokedAt": datetime.now(UTC), "revokedById": getattr(revoker, "id", None)},
        )
        logger.info("join card revoked: token=%s by=%s", token.id, getattr(revoker, "id", "?"))
    fresh = await db.recordaccesstoken.find_unique(
        where={"id": token.id}, include={"issuedBy": True}
    )
    if fresh is None:
        raise not_found
    return grant_payload(fresh, issuer=getattr(fresh, "issuedBy", None))


# --------------------------------------------------------------------------------------
# Redeeming
# --------------------------------------------------------------------------------------

#: **THE ONE UNIFORM REFUSAL.** An unknown secret, a revoked card and a card expired beyond the sync
#: grace all answer with this, and nothing in it says which, names a workshop, or admits that a
#: workshop exists.
#:
#: WRITTEN ONCE AND SAID ALWAYS, on ``RECEIVED_DETAIL``'s reasoning: a refusal that varied by outcome
#: is an oracle with a friendly name on it, and the surest way to reintroduce one is to "improve" the
#: copy for a single branch. A forged card and a card from another deployment must be
#: indistinguishable here.
#:
#: IT NAMES THE TWO ROUTES THAT DO NOT NEED THIS CARD, because a refusal that leaves somebody with
#: nothing to do next is how a designer ends a day with fieldwork on a handset and no workshop to
#: put it in.
CARD_REFUSED_DETAIL = (
    "This join card cannot be used. It may have been cancelled, or it may have run out of date — a "
    "card is only good for a few weeks. Ask whoever runs the workshop for a fresh card, or ask an "
    "administrator to add you from the workshop's viewers screen. Everything you have already "
    "recorded on this device stays where it is."
)


class CardRefused(Exception):
    """The card is not one this server will act on, and the caller is told nothing about which case.

    An exception rather than a returned outcome so that no branch of :func:`redeem` can fall through
    into a write by accident. The route turns it into a 403 with :data:`CARD_REFUSED_DETAIL` and
    nothing else — see that constant for why it is one sentence for three causes.
    """


async def _consume_a_seat(token: Any, *, now: datetime) -> int | None:
    """Take one seat off this card, or answer ``False``. **ONE STATEMENT, NO WINDOW.**

    A CONDITIONAL COMPARE-AND-SWAP and deliberately not a read-then-write, for the reason
    ``design_workshop_access.py``'s header gives about the shape that "has already shipped a
    double-filed government record from" this repository. The ``where`` pins ``usesConsumed`` to the
    value that was just read, so:

    * Postgres takes a ROW LOCK for the update. A concurrent redeemer blocks on it, and under READ
      COMMITTED re-evaluates the predicate against the COMMITTED row — where ``usesConsumed`` has
      moved — so it matches zero rows and takes the provisional path.
    * **ARRIVAL ORDER AT THIS STATEMENT IS THEREFORE WHAT DECIDES WHO GETS THE FULL GRANT.** Not
      ``createdAt``, which in Postgres is transaction-start time and can be identical for two
      racers, and certainly not the handset's clock.

    ``RecordAccessToken_within_maxUses_check`` in the migration is the database-level backstop: even
    a future code path that forgot this predicate cannot push ``usesConsumed`` past the ceiling.

    THE CEILING IS EVALUATED IN PYTHON RATHER THAN IN SQL because ``maxUses`` and ``usesConsumed``
    are two columns of one row and Prisma's ``where`` cannot compare them to each other. That is safe
    here and would not be in a read-then-write: the CAS on ``usesConsumed`` is what makes the
    decision atomic regardless of how the comparison was spelled, and the CHECK constraint is what
    makes it true even if this function is ever wrong.

    ANSWERS THE VALUE IT WROTE, or ``None`` if it wrote nothing, and the caller keeps that number
    rather than re-reading ``token.usesConsumed`` later. That is not defensive style: it is what
    makes :func:`_give_the_seat_back` correct without depending on the loaded row being a DETACHED
    snapshot. It is one today, and a client that ever refreshed the object in place would silently
    turn the compensation into a no-op — a failure with no symptom until a card overspent.
    """
    if token.revokedAt is not None:
        return None
    if token.expiresAt is not None and token.expiresAt <= now:
        return None
    seats_before = token.usesConsumed
    if token.maxUses is not None and seats_before >= token.maxUses:
        return None
    updated = await db.recordaccesstoken.update_many(
        where={
            "id": token.id,
            "usesConsumed": seats_before,
            "revokedAt": None,
        },
        data={"usesConsumed": seats_before + 1},
    )
    return seats_before + 1 if updated == 1 else None


async def _give_the_seat_back(token_id: str, *, seats_taken: int) -> None:
    """Undo one :func:`_consume_a_seat` when the grant it was taken for did not happen.

    **WHY A COMPENSATION AND NOT A TRANSACTION, WHICH IS THE ONE STRUCTURAL COMPROMISE IN THIS
    MODULE AND IS WRITTEN DOWN RATHER THAN HIDDEN.**

    The seat is taken by a COMMITTED single statement, because that commit is the whole serialisation
    mechanism: a concurrent redeemer has to see the moved counter to lose the race, and a counter
    still inside an uncommitted transaction is invisible to it. Folding the seat into the grant's
    transaction would mean two racers both reading the old value and both being granted, backstopped
    only by ``RecordAccessToken_within_maxUses_check`` turning the second one into a 500.

    Everything the grant itself writes — the viewer row, the redemption receipt, the queue row, the
    foothold deletion — IS one transaction (:func:`_write_the_viewer_row` and the block that calls
    it), which it could not be while the viewer row went through ``replace_viewers``: that function
    writes through the module-level ``db`` client and Prisma's ``db.tx()`` hands back a DIFFERENT
    client, so a callee holding its own reference is simply not inside it. See the header for why
    ``replace_viewers`` is no longer on this path at all.

    So the order is: TAKE THE SEAT (committed, one statement, which is what serialises two racers),
    then grant in one transaction, then give the seat back if the grant did not happen.

    **WHICH DIRECTION THIS FAILS IN, STATED.** If this process dies between the two, one seat is
    lost: the card admits one FEWER person than it says. That is the safe direction. The alternative
    ordering (grant, then take the seat) fails by granting access nobody paid for.

    **AND BE PRECISE ABOUT WHAT THE PERSON GETS, BECAUSE THE SENTENCE HERE USED TO OVERCLAIM.** It
    said they "are not stranded — they take the provisional path, keep their work, and appear in the
    admin's queue". That is true when the grant transaction ROLLED BACK for a reason this module
    decided (an ineligible account is now caught before the seat is taken at all, so it never reaches
    here). It is NOT true when the grant transaction failed because the DATABASE failed: there is
    then nothing to write a foothold or a queue row WITH, and the honest outcome is a 500 the caller
    retries — which is why this is called from a handler that RE-RAISES rather than falling through
    into the provisional path. Giving the seat back is what makes that retry work: without it a
    single-use card is permanently dead for a grant that never happened. The Android client's
    induction queue is what turns "retry" from an instruction into a thing that happens by itself.

    The compensation is PINNED TO THE VALUE :func:`_consume_a_seat` ACTUALLY WROTE, so a concurrent
    redeemer who has since taken the next seat cannot be robbed of it: no row matches, and nothing
    happens. That is why ``seats_taken`` is passed in rather than recomputed from the loaded row.
    """
    restored = await db.recordaccesstoken.update_many(
        where={"id": token_id, "usesConsumed": seats_taken},
        data={"usesConsumed": seats_taken - 1},
    )
    if not restored:
        logger.warning(
            "a join card's seat could not be given back because the count had already moved "
            "(token=%s); the card will admit one fewer person than it says, which is the safe "
            "direction",
            token_id,
        )


async def _try_to_give_the_seat_back(token_id: str, *, seats_taken: int) -> None:
    """:func:`_give_the_seat_back`, on a path where the database has already failed once.

    THE CALLER IS ABOUT TO RE-RAISE THE ORIGINAL FAILURE and this compensation must not replace it.
    If the grant transaction died because the connection died, this statement dies too, and a
    ``ConnectionError`` raised from inside the handler would hide the exception that actually
    explains the request — leaving whoever reads the log at 3am debugging the compensation instead of
    the fault. The seat is then lost in the safe direction: the card admits one fewer person.
    """
    try:
        await _give_the_seat_back(token_id, seats_taken=seats_taken)
    except Exception:
        # BLE001 is satisfied by the `logger.exception` below rather than by a `noqa`, which is the
        # right way round: the rule wants a catch-all to leave a traceable record, and this one does.
        logger.exception(
            "a join card's seat could not be given back after the grant failed (token=%s); the card "
            "will admit one fewer person than it says, which is the safe direction",
            token_id,
        )


async def _why_the_redeemer_cannot_be_a_viewer(user_id: str) -> str | None:
    """The sentence ``design_workshop_viewers`` would refuse this account with, or ``None``.

    **THE RULE IS IMPORTED AND NEVER COPIED.** ``_assert_every_id_may_be_granted`` is the same
    function the admin viewers screen validates with: it reads the designer roster AND the platform
    allow-list, exempts the break-glass master through ``deps.is_break_glass_master`` itself, and
    writes a sentence naming the screen that fixes each refusal. A second copy of that here is how a
    suspended designer comes to hold a viewer row their next sign-in refuses, which is precisely the
    contradiction its own refusals are worded to prevent.

    IT IS A PRIVATE NAME IN ANOTHER MODULE, AND THAT IS DELIBERATE RATHER THAN CARELESS. This
    module's own ``_UNSTORABLE_IN_AN_ID`` comment argues the opposite way about four bytes of regex —
    "reaching into somebody else's internals … couples two files for nothing" — and the difference is
    what is being reached for. That was a pattern any file can restate correctly. This is a SECURITY
    DECISION with two roster reads and four sentences in it, where the cost of the two copies drifting
    is somebody holding access they cannot sign in to use. Coupling is the cheaper failure. If the
    module that owns it ever gives the check a public name, use that instead and delete this note.

    ASKED BEFORE THE SEAT IS TAKEN, so an account that cannot hold a viewer row never spends one —
    which is why there is no seat to give back on this path.

    422 ONLY. Any other status is a fault rather than an eligibility answer and is left to propagate:
    turning a 500 into ``INELIGIBLE`` would tell somebody their empanelment had lapsed when the
    database was simply unreachable.
    """
    try:
        await _assert_every_id_may_be_granted({user_id})
    except HTTPException as refusal:
        if refusal.status_code != status.HTTP_422_UNPROCESSABLE_ENTITY:
            raise
        return str(refusal.detail)
    return None


async def _write_the_viewer_row(
    tx: Any,
    *,
    workshop_id: str,
    user_id: str,
    token_id: str,
    granted_by_id: str | None,
) -> None:
    """Put exactly this one account on the workshop. **Adds; never removes.**

    ONE ``create_many`` NAMING ONE ACCOUNT, and the header carries the whole argument for why this is
    not ``replace_viewers``: a whole-set replace deletes whatever it did not see, so using it to add
    one person deletes a viewer another redeemer added a moment ago and resurrects one an admin just
    removed. **DO NOT WIDEN THIS TO A SET.** Nothing about a redemption has an opinion on anybody
    else's membership, and a statement that cannot express one cannot get it wrong.

    ``skip_duplicates=True`` FOR THE RACE THAT REMAINS, and it settles the right way. If an admin
    granted this person from the viewers screen in the same second, their row stands and this writes
    nothing — the redemption is still ``FULL``, because the person IS on the workshop, which is the
    only fact the outcome claims. The consequence for requirement 4's trail is worth seeing stated:
    that row keeps the admin's ``grantedById`` and a NULL ``tokenId``, so it reads "an administrator
    added them", which is true. The card's own receipt is the ``RecordAccessTokenRedemption`` row,
    and that is written either way.

    ``tokenId`` IS SET IN THE SAME STATEMENT THAT CREATES THE ROW, which the ``update_many`` this
    replaced could not do: that ran after ``replace_viewers`` and, whenever the row it meant to stamp
    had been deleted by the same call's whole-set diff, silently affected zero rows and reported
    nothing. A column written by the statement that creates the row cannot miss it.

    **THE WRITE ITSELF IS ``design_workshop_access.add_one_viewer`` AND IS DELIBERATELY NOT A SECOND
    COPY OF IT.** :func:`decide` needs exactly the same statement for an admin's grant, and two
    hand-written inserts into the one table that confers access is how the two come to disagree about
    a column. It lives next door rather than here because that module owns the other caller and this
    one already imports from it; putting it in ``design_workshop_viewers`` — where it arguably belongs
    — is a change for whoever owns that file.
    """
    await add_one_viewer(
        tx,
        workshop_id=workshop_id,
        user_id=user_id,
        # REQUIREMENT 4. The card's ISSUER, not the redeemer and not an admin who was never here.
        # See :func:`redeem`'s docstring for what this does and does not prove.
        granted_by_id=granted_by_id,
        token_id=token_id,
    )


async def _file_or_refresh_the_queue_row(
    tx: Any,
    *,
    workshop_id: str,
    user_id: str,
    token_id: str,
    stored_code: str,
    scanned_at: datetime | None,
    now: datetime,
) -> None:
    """Put this person in the queue an admin already works from, or refresh the row they are in.

    THE EXISTING TABLE, THE EXISTING INDEX, THE EXISTING QUEUE. ``status`` stays PENDING and
    ``source`` stays SCAN, so ``queue()`` — ``where={"status": "PENDING"}`` ordered ``createdAt``
    ascending — picks them up with **zero query changes**. That is exactly where requirement 6 wants
    them, and it is why no enum gained a value.

    ``create_many(skip_duplicates=True)`` FIRST, THEN AN UPDATE, and never a read-then-write: the
    unique index is the idempotency and ``create_many`` answers with how many rows it wrote, which is
    how this knows whether a row was already there without a second round trip. That is
    ``file_request``'s own pattern, quoted from its comment: "THE UNIQUE INDEX IS THE IDEMPOTENCY,
    AND ``skip_duplicates`` IS HOW THIS CALL SURVIVES IT."

    **``createdAt`` IS NOT IN THE UPDATE, AND THAT IS THE ANTI-QUEUE-JUMPING RULE.** The header of
    ``design_workshop_access`` states it: restamping a replayed ask "would push the person who asked
    first down a queue ordered oldest-first — the anti-spam rule inverted into a way to jump the
    queue." A second scan is evidence about the same ask; it does not buy a better place in line.

    **THE UPDATE PINS ``status: PENDING``, WHICH IS HOW A REFUSAL OUTRANKS A CARD.** A DENIED row is
    left exactly as it is, because "letting a scan reopen it would put the same card back in an
    admin's queue every time somebody pointed a phone at it" — the same anti-spam rule, and
    requirement 6's "a later valid scan upgrades them" is about a LATE-COMER, not about somebody an
    admin actively refused. A GRANTED row is left alone too: they are already in, and this function
    is only ever reached for somebody who is not.

    Overwriting ``scannedCode``, ``source`` and ``tokenId`` on a repeat is established precedent
    rather than a new liberty — the GRANTED reopen branch in ``file_request`` already overwrites all
    of ``source``, ``scannedCode`` and ``note``. Only ``createdAt`` is sacred.
    """
    created = await tx.designworkshopaccessrequest.create_many(
        data=[
            {
                "designWorkshopId": workshop_id,
                "requestedById": user_id,
                "status": "PENDING",
                "source": "SCAN",
                # REDACTED. Never the live secret — see `redacted_code`.
                "scannedCode": stored_code,
                "tokenId": token_id,
                "scannedAt": scanned_at,
            }
        ],
        skip_duplicates=True,
    )
    if created:
        return
    await tx.designworkshopaccessrequest.update_many(
        where={
            "designWorkshopId": workshop_id,
            "requestedById": user_id,
            "status": "PENDING",
        },
        data={
            "source": "SCAN",
            "scannedCode": stored_code,
            "tokenId": token_id,
            "scannedAt": scanned_at,
            # `createdAt` IS DELIBERATELY ABSENT. See the docstring.
        },
    )


async def redeem(
    user: Any,
    *,
    code: str,
    scanned_at_client: datetime | None = None,
    scanned_at_elapsed_sec: int | None = None,
    synced_at_elapsed_sec: int | None = None,
    boot_id: str | None = None,
    clock_jump_observed: bool = False,
) -> dict[str, Any]:
    """Scan a join card. **This is the induction, and it is equivalent to an admin adding somebody.**

    -- THE ORDER OF THIS FUNCTION IS THE SECURITY ARGUMENT ----------------------------------------

    1. **THE GRAMMAR, FROM THE BODY ALONE, BEFORE ANY DATABASE READ.** :func:`decode_join_code`
       raises ``ScannedCodeRefused`` and the route answers 422. Every one of those refusals is a
       statement about the string that was sent, which is what makes saying it out loud safe.
       ``tests/test_design_workshop_grant_gate.py`` stands a tripwire in for ``db`` and asserts "422
       with the database never touched". **DO NOT MOVE A LOOKUP ABOVE THAT CALL** — it is the same
       instruction ``file_request`` carries, and the same test shape notices.
    2. ``now`` IS TAKEN ONCE, HERE, FROM THE SERVER CLOCK, and everything is judged against it:
       expiry, the grace window, and the arrival stamp on every row written. One value so the rows
       cannot disagree with the decision they record.
    3. The card is found by ``sha256(secret)``. One indexed equality probe, and a wrong guess costs
       exactly what a right one costs.
    4. Already in? Nothing is written and no seat is spent. **A member scanning the card at the wall
       must not burn the workshop's only invitation.**
    5. Redeemed this card before? The first outcome is returned and no seat is spent — the
       ``@@unique([tokenId, userId])`` guarantee. Without it, one person with two handsets spends a
       multi-use card twice.
    6. **THE REDEEMER'S OWN ELIGIBILITY IS CHECKED BEFORE A SEAT IS TAKEN**, through
       ``design_workshop_viewers``' own validator (:func:`_why_the_redeemer_cannot_be_a_viewer`), so
       an account that cannot hold a viewer row never spends one.
    7. A seat is taken by ONE compare-and-swap (:func:`_consume_a_seat`), and if it succeeds the
       viewer row, the receipt, the queue row and the foothold's removal are ONE transaction
       (:func:`_write_the_viewer_row`). **NOT ``replace_viewers``** — the header says at length why
       that whole-set replace deleted other people's access, and it is not a call to restore.
    8. If the seat is gone, or the redeemer is not eligible, or the card synced after it expired,
       **the scanner is NOT refused** — requirement 6 — they get a capture-only foothold and a row
       in the admin's queue.

    -- PROVENANCE, AND THE LIMIT ON IT -----------------------------------------------------------

    The viewer row is written with ``grantedById=token.issuedById``, so
    ``DesignWorkshopViewer.grantedById`` names **whoever minted the card**, and the ``tokenId``
    stamped beside it names **which card**.

    **BE HONEST ABOUT WHAT THAT TRAIL IS WORTH: a card names its ISSUER, not necessarily the person
    who handed it over.** SINGLE-USE is what collapses those two into one fact — one seat, one
    redemption, so issuing and inducting are the same event, and "who inducted you" is answerable.
    A MULTI-USE card deliberately gives that up: it says "somebody holding one of Rekha's cards let
    this person in", and no column anywhere can say who. That is the second honest reason multi-use
    is admin-only.

    -- THE CLOCK ---------------------------------------------------------------------------------

    Everything the handset reports is stored and **nothing the handset reports is compared to decide
    anything.** ``serverArrivedAt`` is the authority; ``scannedAtClient`` is evidence beside it.
    Ordering by a settable number hands the grant to whoever winds their clock back furthest.

    -- WHAT A REFUSAL WRITES ----------------------------------------------------------------------

    Nothing. No redemption row, no queue row, no counter moved. A table anybody can grow by posting
    random strings is a denial-of-service with an audit trail attached.
    """
    # ---- 1. the body, and only the body -------------------------------------------------------
    # THE CANONICAL FORM IS DISCARDED, and deliberately: it is the whole card including the live
    # secret, and the only thing this function ever STORES is `redacted_code`'s output. Binding it to
    # a name here would be one careless log line away from a credential in a file.
    record_id, secret, _canonical = decode_join_code(code)

    # ---- 2. one server clock, for every decision and every row -------------------------------
    now = datetime.now(UTC)

    user_id = getattr(user, "id", "")
    if not user_id:
        # A signed-in identity is required before anything is written. A capture attributed to
        # whoever happens to be signed in at sync time is a worse bug than a refusal.
        raise CardRefused()

    # ---- 3. the card ---------------------------------------------------------------------------
    token = await db.recordaccesstoken.find_unique(
        where={"secretHash": secret_hash(secret)}
    )
    if token is None:
        # A FORGED CARD LANDS HERE, and so does a card from another deployment, and so does a typo
        # the check characters happened not to catch. All three get `CARD_REFUSED_DETAIL` and none of
        # them is written down.
        raise CardRefused()
    if str(getattr(token.recordType, "value", token.recordType)) != "DESIGN_WORKSHOP":
        # A card for a record type whose membership half is not built. Uniform, because saying
        # "that is a tool card" would be a statement about a row rather than about the body.
        raise CardRefused()
    if token.recordId != record_id:
        # THE CARD AND ITS OWN PAYLOAD DISAGREE, which can only happen if somebody assembled a
        # string around a secret they had. Uniform refusal: the secret is genuine, so this is the one
        # case where a distinguishable answer would be a real oracle about another workshop's id.
        raise CardRefused()
    if token.revokedAt is not None:
        # NO OFFLINE REVOCATION IS POSSIBLE, and this is where that is settled instead: a card
        # revoked on Monday keeps producing provisional CAPTURES on offline handsets until they sync,
        # and then this refuses them. That is the honest limit of an online-only check and it is
        # bounded by the foothold being capture-only.
        raise CardRefused()

    expired = token.expiresAt is not None and token.expiresAt <= now
    if expired and token.expiresAt is not None:
        beyond_grace = now - token.expiresAt > timedelta(days=GRANT_SYNC_GRACE_DAYS)
        if beyond_grace:
            raise CardRefused()

    workshop = await db.designworkshop.find_unique(where={"id": token.recordId})
    if workshop is None or getattr(workshop, "deletedAt", None) is not None:
        # THE RECORD IS RE-READ ON EVERY REDEMPTION, and this is what makes the missing foreign key
        # on `RecordAccessToken.recordId` affordable: an orphaned card is inert rather than dangerous
        # because it cannot get past this line.
        raise CardRefused()

    # ---- 4. already in? nothing is written, no seat is spent ----------------------------------
    if (
        workshop.createdById == user_id
        or is_admin(user)
        or await has_viewer_grant(token.recordId, user_id)
    ):
        return {
            "outcome": "ALREADY_A_MEMBER",
            "workshopId": token.recordId,
            "detail": (
                "You are already on this workshop, so the card was not used up. Somebody else can "
                "still use it."
            ),
        }

    # ---- 5. this person, this card, already? -------------------------------------------------
    prior = await db.recordaccesstokenredemption.find_unique(
        where={"tokenId_userId": {"tokenId": token.id, "userId": user_id}}
    )
    if prior is not None:
        # THE REPLAY. An offline delivery arriving twice, or a second handset. The FIRST outcome is
        # returned and `usesConsumed` is untouched — without this, one person spends a multi-use card
        # once per device they own.
        outcome = str(getattr(prior.outcome, "value", prior.outcome))
        return {
            "outcome": outcome,
            "reason": str(getattr(prior.reason, "value", prior.reason)),
            "workshopId": token.recordId,
            "detail": (
                _FULL_DETAIL
                if outcome == "FULL"
                else _PROVISIONAL_DETAIL
            ),
        }

    # READ BEFORE ANYTHING IS WRITTEN, so the log line on the grant path can say whether a card has
    # just superseded an administrator's refusal. It is EVIDENCE FOR A LOG and never a decision: no
    # branch below reads it to choose an outcome, because requirement 6 forbids refusing anybody and
    # a DENIED row must not become a second, quieter way to refuse.
    prior_request = await db.designworkshopaccessrequest.find_unique(
        where={
            "designWorkshopId_requestedById": {
                "designWorkshopId": token.recordId,
                "requestedById": user_id,
            }
        }
    )
    superseded_a_refusal = (
        prior_request is not None
        and str(getattr(prior_request.status, "value", prior_request.status)) == "DENIED"
    )

    stored_code = redacted_code(token.recordId, secret)
    evidence = {
        "scannedAtClient": scanned_at_client,
        "scannedAtElapsedSec": scanned_at_elapsed_sec,
        "syncedAtElapsedSec": synced_at_elapsed_sec,
        "bootId": plain(boot_id)[:200] if boot_id else None,
        "clockJumpObserved": bool(clock_jump_observed),
        "serverArrivedAt": now,
    }

    # ---- 6. the seat, and the grant --------------------------------------------------------
    #
    # THE ORDER IS: CHECK ELIGIBILITY, TAKE THE SEAT, GRANT IN ONE TRANSACTION, GIVE THE SEAT BACK IF
    # THE GRANT DID NOT HAPPEN. `_give_the_seat_back` carries the whole argument for why the seat is
    # a committed statement of its own rather than part of that transaction, and which direction it
    # fails in. ELIGIBILITY COMES FIRST so that an account which cannot hold a viewer row never
    # spends a seat at all.
    if expired:
        # A GENUINE SCAN THAT SYNCED AFTER THE CARD'S DATE, inside the grace window. NEVER a full
        # grant, and the seat is never even reached for: expiry is judged by server arrival, the same
        # authority that decides order, so a device clock cannot buy an extension. But it is not
        # thrown away either — the fieldwork behind it is real.
        reason = "EXPIRED"
    elif (ineligible := await _why_the_redeemer_cannot_be_a_viewer(user_id)) is not None:
        # THIS ACCOUNT CANNOT HOLD A VIEWER ROW — off the designer roster, barred by the platform
        # allow-list, or a role that cannot run a workshop at all. **ASKED BEFORE THE SEAT, so the
        # card is not spent** and can still admit somebody once the roster is fixed. Requirement 6
        # applies here as much as to the late-comer: a refusal in a courtyard about an empanelment
        # nobody present can restore is a refusal for a reason the scanner cannot act on, so it
        # becomes a foothold and a queue row an admin can see.
        #
        # THE SENTENCE IS LOGGED AND NOT RETURNED. It names another screen and, for the role arm, the
        # account's own role; the redeemer gets `_PROVISIONAL_DETAIL` like every other provisional
        # outcome, because a redemption answer that varied with the reason would be a second, quieter
        # refusal — and requirement 6 forbids refusing anybody.
        logger.warning(
            "join card could not grant a viewer row because the redeemer's own account is not "
            "eligible (token=%s workshop=%s user=%s): %s",
            token.id,
            token.recordId,
            user_id,
            ineligible,
        )
        reason = "INELIGIBLE"
    elif (seats_taken := await _consume_a_seat(token, now=now)) is None:
        # THE SEAT WAS GONE. Requirement 6's late-comer: NOT refused.
        reason = "ALREADY_SPENT"
    else:
        try:
            async with db.tx() as tx:
                # THE GRANT ITSELF, AND IT IS ONE TRANSACTION WITH ITS OWN RECEIPT. See
                # `_write_the_viewer_row` for why this is not `replace_viewers`, and the module
                # header for the two ways that call silently destroyed access.
                await _write_the_viewer_row(
                    tx,
                    workshop_id=token.recordId,
                    user_id=user_id,
                    token_id=token.id,
                    granted_by_id=token.issuedById,
                )
                # THE FOOTHOLD GOES WITH THE PROMOTION, and `decide`'s GRANT arm gives the reason
                # this branch has to do it too: "the foothold must go with it or the same person is
                # in two membership tables at once and every screen has to pick one". Without this,
                # scanning a spent card and then a fresh one left `request_payload` reporting
                # `requesterHasAccess: true` AND `requesterIsProvisional: true` for one person —
                # the contradictory pair `requesterIsProvisional`'s own comment says must never
                # occur, and the state that makes `may_capture`'s three arms disagree about which
                # fact admitted somebody.
                #
                # **THIS IS "A LATER VALID SCAN UPGRADES THEM" IN REQUIREMENT 6, HAPPENING WITHOUT
                # AN ADMIN.** Nothing they captured is destroyed: `DwStageEntry` cascades from
                # `DesignWorkshop` and not from the foothold, so their fieldwork survives and becomes
                # readable to them for the first time.
                #
                # UNCONDITIONAL AND IDEMPOTENT — `delete_many` on a pair that usually has no row —
                # rather than read-then-delete, the same call `decide` makes and for the same reason.
                # The ordinary case is somebody who never scanned a spent card at all.
                await tx.designworkshopprovisionalmember.delete_many(
                    where={"designWorkshopId": token.recordId, "userId": user_id}
                )
                await tx.recordaccesstokenredemption.create(
                    data={
                        "tokenId": token.id,
                        "userId": user_id,
                        "outcome": "FULL",
                        "reason": "OK",
                        **evidence,
                    }
                )
                # THE RECEIPT, in the queue an admin already reads, marked GRANTED with `decidedById`
                # left NULL — which is what `tokenId IS NOT NULL` beside it means: a card decided
                # this, not a person.
                await _file_or_refresh_the_queue_row(
                    tx,
                    workshop_id=token.recordId,
                    user_id=user_id,
                    token_id=token.id,
                    stored_code=stored_code,
                    scanned_at=scanned_at_client,
                    now=now,
                )
                # THE ROW IS MARKED GRANTED AND **THE DECISION COLUMNS ARE LEFT ALONE**, which is
                # the same call `file_request`'s reopen branch makes and for the reason it states:
                # they are the only record the previous decision has. `decidedById` staying NULL for
                # a row a card decided — beside `tokenId` naming which card — is what tells an admin
                # that no person answered this.
                #
                # ⚠ AND IT CAN OVERWRITE A **DENIED** ROW, WHICH IS A POLICY EDGE WORTH SEEING
                # STATED. Somebody an admin refused last month, handed a genuine card by a colleague
                # who is already on the workshop, is now a viewer — requirement 2 makes scanning
                # equivalent to an admin's induction, and there is no way to hold a viewer row and a
                # DENIED queue row at once without one of the two screens lying. Leaving the row
                # DENIED beside real access is the exact "lie on the screen" `decide`'s 409 exists to
                # prevent, so the row follows reality.
                #
                # It is NOT silent, and it is NOT the same as a refusal being reopened by ASKING —
                # that is still refused, by the `status: PENDING` pin in
                # `_file_or_refresh_the_queue_row`, on the anti-spam rule the access module's header
                # argues. What crossed the line here is a 110-bit credential minted by somebody
                # entitled to mint it, so the honest response is to record it loudly and keep the
                # refusal's own columns intact, not to pretend it did not happen.
                if superseded_a_refusal:
                    logger.warning(
                        "a join card admitted somebody an administrator had previously REFUSED for "
                        "this workshop (workshop=%s user=%s token=%s issuer=%s); the earlier "
                        "decision's columns are kept on the row",
                        token.recordId,
                        user_id,
                        token.id,
                        token.issuedById or "unknown",
                    )
                await tx.designworkshopaccessrequest.update_many(
                    where={"designWorkshopId": token.recordId, "requestedById": user_id},
                    data={
                        "status": "GRANTED",
                        # THE CARD FIELDS ARE SET HERE TOO, and not only in
                        # `_file_or_refresh_the_queue_row`, because that function pins
                        # `status: PENDING` — which is the anti-spam rule and must stay — so a row
                        # that was DENIED or already GRANTED is untouched by it. Without these four,
                        # such a row would end up reading GRANTED with no card named: the exact
                        # opposite of what `tokenId`'s schema comment promises, since `decidedById`
                        # being NULL is only readable as "a card decided this" when `tokenId` says
                        # WHICH card.
                        "source": "SCAN",
                        "scannedCode": stored_code,
                        "tokenId": token.id,
                        "scannedAt": scanned_at_client,
                        # `createdAt` and the three decision columns are deliberately absent. See the
                        # comment above and `file_request`'s reopen branch: the queue position and the
                        # record of the earlier decision are the two things a re-scan must not erase.
                    },
                )
        except BaseException:
            # **NOT `except HTTPException`, AND THAT NARROWNESS WAS A REAL BUG.** A Prisma or
            # connection failure is not an `HTTPException`, so it used to leave the seat consumed
            # with NO viewer row, NO redemption row, NO queue row and NO foothold: a single-use card
            # permanently dead for a grant that never happened, and nothing anywhere recording that
            # anybody had scanned it.
            #
            # `BaseException` AND NOT `Exception`, deliberately: `asyncio.CancelledError` is a
            # `BaseException`, a client that walks out of signal mid-request cancels this task, and a
            # cancelled grant has spent a seat exactly as a failed one has.
            #
            # RE-RAISED, NEVER FOLDED INTO THE PROVISIONAL PATH. Writing a foothold needs the same
            # database that just refused to write the grant, so "recover by recording something
            # else" is a promise this branch cannot keep — see `_give_the_seat_back`. The honest
            # answer is the 500 and a card that still works, which the Android induction queue
            # retries by itself.
            await _try_to_give_the_seat_back(token.id, seats_taken=seats_taken)
            raise
        logger.info(
            "join card redeemed in full: token=%s user=%s workshop=%s",
            token.id,
            user_id,
            token.recordId,
        )
        return {
            "outcome": "FULL",
            "reason": "OK",
            "workshopId": token.recordId,
            "detail": _FULL_DETAIL,
        }

    # ---- 7. requirement 6: the late-comer is not refused --------------------------------------
    async with db.tx() as tx:
        await tx.designworkshopprovisionalmember.create_many(
            data=[
                {
                    "designWorkshopId": token.recordId,
                    "userId": user_id,
                    "viaTokenId": token.id,
                    "reason": reason,
                    "scannedAtClient": scanned_at_client,
                    "serverArrivedAt": now,
                }
            ],
            # The pair is the primary key, so a second late scan is the intended outcome of this
            # call rather than a 500 on a duplicate key.
            skip_duplicates=True,
        )
        await tx.recordaccesstokenredemption.create(
            data={
                "tokenId": token.id,
                "userId": user_id,
                "outcome": "PROVISIONAL",
                "reason": reason,
                **evidence,
            }
        )
        await _file_or_refresh_the_queue_row(
            tx,
            workshop_id=token.recordId,
            user_id=user_id,
            token_id=token.id,
            stored_code=stored_code,
            scanned_at=scanned_at_client,
            now=now,
        )
    logger.info(
        "join card redeemed provisionally (%s): token=%s user=%s workshop=%s",
        reason,
        token.id,
        user_id,
        token.recordId,
    )
    return {
        "outcome": "PROVISIONAL",
        "reason": reason,
        "workshopId": token.recordId,
        "detail": _PROVISIONAL_DETAIL,
    }


#: What a full induction says. It states the fact and nothing more; there is no "welcome" to be
#: written over a grant that is identical to an admin having ticked a box.
_FULL_DETAIL = (
    "You are on this workshop. The card has been used up, so it will not let anybody else in."
)

#: What a PROVISIONAL foothold says, and every clause of it is load-bearing.
#:
#: IT DOES NOT SAY "REQUEST SENT" AND IT DOES NOT SAY YOU ARE IN. ``unresolvedWorkshopCodeMessage``
#: in ``frontend/lib/workshopCodes.ts`` established that discipline — "AND IT DOES NOT SAY 'REQUEST
#: SENT', because nothing has been sent … any UI over this must not dress that up as a submitted
#: request" — and here something HAS been filed, so this may say an administrator can see it. What it
#: must not do is imply membership.
#:
#: IT PROMISES THE ONE THING REQUIREMENT 6 IS ABOUT: the work is not lost. And it is honest that the
#: workshop's existing content is not readable, because a person who expected to see a colleague's
#: stages and sees nothing will conclude the app is broken and stop using it.
#:
#: ⚠ ANY UI OVER THIS MUST KEEP THE STATE VISIBLY AND PERSISTENTLY PROVISIONAL on every screen it
#: touches, and must never dress it as membership. A person can work for days into a workspace that
#: turns out to be nothing; the design cannot prevent that, only be honest about it.
_PROVISIONAL_DETAIL = (
    "That card had already been used, so you are not on the workshop yet — but nothing you record "
    "is lost. You can keep capturing your own work here and an administrator can see that you "
    "scanned the card; once they confirm you, everything you have recorded is already in place. "
    "Until then you will not see anybody else's stages."
)
