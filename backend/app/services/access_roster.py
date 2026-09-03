"""THE PLATFORM ALLOW-LIST: who may sign in at all, and the queue of people asking to.

``AccessRoster`` answers one question — *may this email address sign in?* — and it answers it for
EVERY account except the master admin's. That is the whole feature: before this module existed,
``POST /api/auth/login`` minted a token for anybody who could prove an identity, and the Google
branch went further and CREATED an account for any verified Google address on earth.

**READ THIS BEFORE PORTING IT.** The field-repository application (``com.fieldrepository.app``)
needs the same gate and has no equivalent of ``DesignerRoster`` to build it on, so this module is
written to be copied. What a porter must change, and nothing else:

* :func:`normalise_email`, :func:`canonical_email`, :func:`email_match_keys` and the
  :data:`~app.services.designers.GMAIL_DOMAINS` set they are all written against are imported from
  ``app.services.designers`` here because that repository already had one canonical lower-caser and
  two would be one too many. In an application without a designer roster, inline all four — they
  are short, and the rules they enforce are in their docstrings. Do not drop the Gmail pair as
  incidental to the roster: they are about the ALLOW-LIST as much as about empanelment, and an
  application whose only sign-in path is Google has the same one-mailbox-two-keys hole this one had.
* :func:`designer_empanelment_admits` and :func:`mirror_suspension` are the TWO clauses in this
  file that are specific to this application, and they are the two halves of one relationship with
  a second allow-list. The first accepts an ACTIVE ``DesignerRoster`` row as an admission, so that
  an admin who empanels a designer has not silently created somebody the gate then refuses. The
  second is the reverse and is a WRITE: a revocation on either roster is mirrored onto the other, so
  the two screens cannot go on showing contradictory standing for one person. An application with no
  second allow-list deletes both functions, their call sites in ``auth.py`` and in the two roster
  route modules, and the ``roster_allows``, ``suspend_empanelment`` and
  ``note_recording_a_consequence`` imports they delegate to — those three are the designer roster
  reaching in here and go out with the clauses, unlike the four spelling imports above them, which
  stay.
* Everything else — the four states, the two distinct refusals, the identity-proof precondition on
  the write, the dedupe, the cap, the rejected-stays-rejected rule — is the feature, and changing
  any of it changes what the user asked for.

**THE WRITE IN HERE IS CAUSED BY AN UNAUTHENTICATED REQUEST, AND THAT IS BOUNDED FOUR WAYS.**
Anyone on the internet can POST to the login endpoint, and on a default deployment nothing outside
this module counts how often, so this module supplies its own bound and must keep supplying it.

**CORRECTED 2026-08-27, CONCLUSION UNCHANGED.** The parenthesis here used to read *"there is no
rate limit, no captcha and no lockout anywhere in this codebase (``app/scale/rate_limit.py``
exists but ``install_rate_limit`` is never called from ``create_app``, and nginx carries no
``limit_req``)"*. Its middle clause is now false: ``app/main.py`` imports the function at module
level and ``create_app`` calls ``install_rate_limit(app)``. What did NOT change is the
conclusion, because the limiter is OPT-IN and off by default:
``Settings.scale_rate_limit_enabled`` is ``Field(default=False,
alias="SCALE_RATE_LIMIT_ENABLED")``, and ``install_rate_limit`` returns ``False`` without
calling ``add_middleware`` at all when the flag is off. A fresh clone, and every box that has
not set ``SCALE_RATE_LIMIT_ENABLED=true``, still reaches the login endpoint unbounded.
Turned ON, the two credential doors get a second and much tighter allowance spent only by
401s (``_CREDENTIAL_FAILURES = 20`` in ``_CREDENTIAL_WINDOW_SECONDS = 300``, over
``_CREDENTIAL_PREFIXES = ("/api/auth/login", "/api/datasets/token")``, all in
``app/scale/rate_limit.py``) — a real bound, but not one any reader may assume is on.
The ``limit_req`` clause is neither true nor false here: no nginx configuration is tracked in
this repository at all (``git ls-files | grep -i nginx`` answered nothing on 2026-08-27), and
the only ``limit_req`` anywhere in the tree is this sentence and its siblings in prose.
The four bounds below are what holds either way:

1. **A row is only ever created for a PROVEN identity.** The caller must have passed a bcrypt check
   against an existing account, or presented a Google ID token that verified against a configured
   audience. An anonymous caller cannot enqueue an address they do not control. This is enforced by
   the CALL SITES of the gate, not by this module: :func:`record_refused_attempt` is reached only
   through ``auth.assert_access_admits``, and every endpoint that calls THAT does so after its
   credential check — the two login branches, and ``POST /api/datasets/token``, which mints the
   machine credential. Add a caller that reaches the gate before proving the identity and that is
   the property you have deleted; the number of callers was never what protected it.
2. **One address is one row.** ``email`` is unique and a repeat attempt is an UPDATE bumping
   :attr:`attemptCount`. Somebody hammering the form produces a rising number, not a queue.
3. **Nothing an attacker controls is stored beyond the address.** No display name from the Google
   profile, no user agent, no free text. ``fullName`` and ``notes`` are admin-typed only. The
   admin's queue is a list of email addresses and integers, so there is nowhere in it to write a
   sentence that says "click here to verify your account".
4. **A ceiling on the queue**, :data:`PENDING_CAP_SETTING`. Past it, new rows are refused and the
   caller is told the request could not be recorded and to contact an administrator directly — an
   honest refusal, unlike answering "you are awaiting approval" to somebody no admin will ever see.
   Existing rows still update, so nobody already in the queue is affected by the ceiling.

**A REJECTED PERSON DOES NOT RE-QUEUE.** :func:`record_refused_attempt` bumps the count on a
REJECTED row and leaves the status alone. The alternative — reopening the request on the next
attempt — makes an admin's decision unenforceable: the person retries, the row returns to PENDING,
and the queue the admin is supposed to work fills back up with the entries they just cleared. The
attempt count is still recorded, and rises, which is the signal an admin can act on if somebody is
genuinely trying to get their rejection reconsidered. Only an admin can move REJECTED anywhere else.
"""

import logging
from datetime import UTC, datetime
from typing import Any

from app.core.config import get_settings
from app.core.db import db

# One lower-caser, and one Gmail canonicaliser, for the whole repository. See the module docstring
# for what a porter does with them — and note that the LAST THREE are a different KIND of import
# from the four above them: they are the designer roster itself, reached for by the two
# application-specific clauses, and they leave with those clauses.
#
# THE COUNT IN THIS COMMENT WAS WRONG AND IT WAS THE KIND OF WRONG A PORTER ACTS ON. It read "the
# last two" against "the first four" while the list held six alphabetically sorted names, so the
# cut it describes falls between ``normalise_email`` and ``note_recording_a_consequence`` — which
# leaves ``note_recording_a_consequence`` on the STAYING side, and that is the sentence a mirrored
# revocation writes about itself, with no meaning at all in an application that has no mirror. A
# porter following this line keeps a helper whose only caller they have just deleted. The module
# docstring above has always said three and three; this line now agrees with it, and both now
# count ``GMAIL_DOMAINS`` among the spelling imports that stay.
from app.services.designers import (
    GMAIL_DOMAINS,
    canonical_email,
    email_match_keys,
    normalise_email,
    note_recording_a_consequence,
    roster_allows,
    suspend_empanelment,
)

logger = logging.getLogger(__name__)

#: The environment key that caps the pending queue. Named here so the setting, the refusal message
#: and the admin count endpoint all point at the same knob.
PENDING_CAP_SETTING = "ACCESS_PENDING_MAX"

#: RFC 5321's maximum address length. An address longer than this cannot be a real mailbox, and the
#: point of the bound is that nothing arbitrary-length reaches the column: this is the only
#: attacker-influenced value stored anywhere in the table.
MAX_EMAIL_LENGTH = 254

# The four states, spelled once. Compared against ``str(row.status)`` values, which are plain
# strings from Prisma's generated enum — see :func:`status_of` for why the comparison is never made
# against the raw attribute.
ACTIVE = "ACTIVE"
PENDING = "PENDING"
REJECTED = "REJECTED"
SUSPENDED = "SUSPENDED"

#: What :func:`record_refused_attempt` reports when the request could NOT be written down — the
#: queue is full, or the address is not storable. A fifth outcome rather than a fourth state,
#: because nothing was persisted: there is no row in this condition, which is exactly why the person
#: has to be told something different from "you are in the queue".
NOT_RECORDED = "NOT_RECORDED"

#: The states this table BARS an address in: an administrator looked at it and said no. PENDING is
#: emphatically not among them — see :func:`barred_emails`.
BARRED = (REJECTED, SUSPENDED)


def status_of(row: Any) -> str:
    """The row's status as a plain string.

    Prisma hands back an enum member on a live row and a bare string on anything hand-built in a
    test, and ``row.status == "PENDING"`` silently answers False for the first of those — an
    equality test that fails OPEN in a permission check, which is the direction that lets somebody
    in. The same trap ``deps.role_value`` exists for, in the same shape.
    """
    if row is None:
        return ""
    status = getattr(row, "status", None)
    return str(getattr(status, "value", status) or "")


def admits(row: Any) -> bool:
    """Does this row let its address sign in? ACTIVE and nothing else."""
    return status_of(row) == ACTIVE


def role_of(row: Any) -> str | None:
    """``admitRole`` as a plain string, or None. See :func:`status_of` for why not the attribute."""
    if row is None:
        return None
    role = getattr(row, "admitRole", None)
    if role is None:
        return None
    return str(getattr(role, "value", role)) or None


def pending_cap() -> int:
    return max(1, int(get_settings().access_pending_max))


def _the_row_that_decides(rows: list[Any], literal: str) -> Any:
    """Which of two rows for ONE mailbox answers the gate. THE REFUSAL WINS.

    Reached only where a Gmail mailbox has rows under two spellings — ``sandy.craft3@gmail.com`` and
    ``sandycraft3@gmail.com``, say, because one was typed by an admin before this canonicalisation
    existed and the other written by the pending queue after it. ``email`` is unique on the table
    and :func:`email_match_keys` yields at most two keys, so this is choosing between at most two
    rows and never paginating anything.

    **A BARRED ROW BEATS AN ADMITTING ONE, ALWAYS, AND THAT DIRECTION IS THE WHOLE FUNCTION.** The
    two orderings are not symmetrical mistakes. Preferring the ACTIVE row would mean an admin
    who rejected or suspended somebody has their decision quietly overturned by a second spelling of
    the same address — the person signs in, and the only trace is a row on a screen nobody has a
    reason to open. Preferring the barred row refuses somebody who may in fact be entitled to be
    here, which is a visible, complainable, five-minute fix by the same administrator on the same
    screen. This module fails closed everywhere else for exactly that asymmetry (a missing row is a
    refusal; see :func:`access_row`), and a collision is not the place to start failing open.

    Below that, the row spelled the way the caller actually spelled it wins, because that is the row
    an admin searching for the address they were given will find and edit. The two tests together
    are total over the reachable cases: with two keys, every matched row equals one of them, so at
    most one row can fail the second test and a tie is not reachable.
    """
    return min(
        rows,
        key=lambda row: (
            0 if status_of(row) in BARRED else 1,
            0 if normalise_email(row.email) == literal else 1,
        ),
    )


async def access_row(email: Any) -> Any | None:
    """The allow-list row for a MAILBOX, or None if this address has never been seen.

    NONE IS NOT "ADMITTED". Every caller treats a missing row as a refusal — that is what makes the
    gate fail CLOSED for an account created by some path that forgot to admit it, and it is why the
    grandfathering in the migration had to be exhaustive rather than merely thorough.

    **ONE ``IN`` OVER BOTH SPELLINGS, WHICH IS WHY THIS IS NO LONGER A ``find_unique``.** Google is
    the only sign-in path most people here have and it treats Gmail dots and ``+tags`` as noise, so
    the address arriving at this gate and the address an admin typed onto the allow-list can be the
    same mailbox under two different strings — and a ``find_unique`` on the literal one answers
    None, which every caller reads as "never seen" and refuses. See
    :func:`app.services.designers.email_match_keys` for why the literal spelling stays in the list
    (the rows already in the table are stored under it) and why this is one query and not two.

    A row found by the canonical key is returned exactly as a row found by the literal one: the
    same object, to the same callers, with no marker distinguishing them. There is deliberately no
    such thing as a second-class admission here — the two strings are one mailbox or they are not,
    and having decided they are, a sign-in through either must behave identically or the difference
    becomes a bug report nobody can reproduce.
    """
    keys = [key for key in email_match_keys(email) if len(key) <= MAX_EMAIL_LENGTH]
    if not keys:
        return None
    rows = await db.accessroster.find_many(where={"email": {"in": keys}})
    if not rows:
        return None
    if len(rows) == 1:
        return rows[0]
    return _the_row_that_decides(rows, keys[0])


async def designer_empanelment_admits(email: Any) -> bool:
    """Is this address on the DESIGNER roster as an ACTIVE empanelment?

    THE ONE APPLICATION-SPECIFIC CLAUSE IN THIS FILE. ``DesignerRoster`` predates the allow-list and
    means something stronger: an admin has empanelled this person as a designer, possibly before
    they have an account at all. Refusing them here would mean an admin who did everything the
    product asked has produced somebody the sign-in page turns away, with the remedy being to
    approve the same person a second time in a second screen — and the admin has no way to know
    that, because the roster screen shows the row they added, active, admitting nobody.

    Read only when the allow-list has already declined, so the ordinary path costs no extra query.

    **DELEGATED RATHER THAN SPELLED AGAIN, AND THAT CHANGED WHEN THE GMAIL KEY ARRIVED.** This was
    a three-line copy of :func:`app.services.designers.roster_allows` — the same query asking the
    same question of the same table — and a copy was tolerable while the question was one equality
    test. It stopped being tolerable the moment the answer needed a two-key ``IN`` and a rule for
    what a suspended twin means, because two implementations of "is this mailbox empanelled" that
    can drift apart are not a duplication smell, they are an authentication bug waiting for somebody
    to update one of them: one gate would admit a revoked designer that the other refuses, and which
    one you got would depend on whether the allow-list happened to have a row. It stays a NAMED
    function here because the porter's note above tells an application without a designer roster to
    delete this clause, and a name is what makes that a deletion rather than an excavation.
    """
    return await roster_allows(email)


#: How many barred addresses :func:`barred_emails` will read.
#:
#: A BACKSTOP AGAINST AN UNBOUNDED READ, not a page size, and its own number rather than a borrowed
#: one for the reason ``design_workshop_viewers.ACTIVE_ROSTER_READ_LIMIT`` is its own number: these
#: are two different quantities that would only look tidy sharing a constant. The roster read's cap
#: bounds a set that admits; this one bounds a set that REFUSES, and the two fail in opposite
#: directions — the first hides eligible people, this one exposes barred ones. A cut list of
#: admitted addresses is a colleague an admin cannot find; a cut list of barred addresses is a
#: revoked colleague quietly offered back. That is why hitting this is logged at ERROR in words that
#: say so, and why the write path refuses the same accounts independently instead of trusting this.
#:
#: REJECTED and SUSPENDED rows are never deleted (a rejection that can be re-requested around is not
#: a rejection), so this set only ever grows, and the ceiling has to sit far above any plausible
#: programme rather than at a number somebody expects to reach.
BARRED_EMAIL_READ_LIMIT = 50_000


async def barred_emails() -> list[str]:
    """Every lower-cased address the allow-list currently REFUSES — REJECTED or SUSPENDED.

    **A CUT LIST, AND THE DIRECTION IS THE WHOLE POINT.** Callers use this to EXCLUDE people, never
    to require them. There is no relation between ``AccessRoster`` and ``User`` — they meet on the
    email column and nothing enforces the join — and the sign-in path SELF-HEALS an address that has
    no row or a PENDING one, admitting it on the strength of an active designer empanelment (see
    :func:`designer_empanelment_admits`). A caller that instead required an ACTIVE row would drop
    exactly those designers: people who can sign in perfectly well, absent from a picker with
    nothing on screen to say why. Excluding the barred cannot make that mistake, because a REJECTED
    or SUSPENDED row is the one state no sign-in heals.

    PENDING IS NOT BARRED, for the same reason stated the other way round: nobody has decided about
    a pending address yet, and an empanelled designer sitting on one is admitted the moment they
    sign in. Refusing them here would refuse somebody the product is about to let in.

    Emails only. This is folded into another table's ``WHERE`` and the caller has no business
    receiving the attempt counts, the admin's private notes or who decided what.
    """
    rows = await db.accessroster.find_many(
        where={"status": {"in": list(BARRED)}}, take=BARRED_EMAIL_READ_LIMIT + 1
    )
    if len(rows) > BARRED_EMAIL_READ_LIMIT:
        rows = rows[:BARRED_EMAIL_READ_LIMIT]
        # ERROR, and worded for what it actually costs: past this cut the answer stops being a
        # complete list of who is barred, so a caller filtering on it will OFFER somebody an
        # administrator has already refused. Whoever reads this at 3am needs to know that the
        # failure is an over-permissive screen and not a short one.
        logger.error(
            "the allow-list holds more than %s barred addresses, so only part of that set was "
            "read; any screen filtering on it may now OFFER an address an administrator has "
            "rejected or suspended, and only the write path will refuse them",
            BARRED_EMAIL_READ_LIMIT,
        )
    return sorted({normalise_email(row.email) for row in rows if normalise_email(row.email)})


async def barred_among(emails: list[str]) -> set[str]:
    """Which of exactly these addresses the allow-list refuses. For the WRITE path.

    The narrow counterpart to :func:`barred_emails`, and narrow on purpose: a write already knows
    which addresses it is about, so reading the whole barred set to check three of them is the
    "filter after the take" mistake in another shape — and, unlike the list, this answer has no cap
    to be cut by, which is what a refusal has to be able to promise. ONE query for the batch, the
    same shape as ``design_workshop_viewers._designers_the_roster_still_admits``.

    Same rule as the list: REJECTED or SUSPENDED only, never PENDING. Lower-cased on both sides,
    because ``AccessRoster.email`` is stored lower-cased and ``User.email`` is not.
    """
    wanted = sorted({normalise_email(e) for e in emails if normalise_email(e)})
    if not wanted:
        return set()
    rows = await db.accessroster.find_many(
        where={"AND": [{"email": {"in": wanted}}, {"status": {"in": list(BARRED)}}]}
    )
    return {normalise_email(row.email) for row in rows}


async def pending_count() -> int:
    """How many requests are waiting. THE NOTIFICATION, in the only channel either application
    has — there is no email sender and no push transport in this codebase, so "notify the admins"
    is a number on a screen they already open. Indexed on ``status``; this is read on page load."""
    return await db.accessroster.count(where={"status": PENDING})


async def admit(
    email: Any,
    *,
    admit_role: str | None = None,
    actor_id: str | None = None,
    full_name: str | None = None,
    note: str | None = None,
    decided: bool = True,
) -> Any:
    """Put an address on the allow-list, or return an already-ACTIVE row to ACTIVE.

    The one write that grants access, used by every path that can grant it: an admin approving a
    pending request, an admin creating an account by hand (creating somebody's account IS approving
    them — an admin who then had to approve their own new user in a second screen would rightly
    file that as a bug), and the designer-empanelment clause above.

    ``joinedAt`` IS WRITTEN ONCE AND NEVER MOVED. It is the "date of joining the platform" the
    requirement asks the admin screen to show, and a restore after a suspension must not reset it —
    somebody who joined in 2024, lost access for a month and was let back in has still been here
    since 2024, and an admin reading a joining date of last Tuesday would draw the wrong conclusion
    about every record that person created. The ``or`` in the update arm is that rule.

    ``decided`` is false only for the machine-made admissions (the empanelment clause), so that
    ``decidedById`` never claims an administrator reviewed something no administrator saw.

    **A NEW ROW IS WRITTEN UNDER THE MAILBOX; AN EXISTING ROW KEEPS THE SPELLING IT HAS.** The
    lookup covers both spellings, so an admin admitting ``sandy.craft3@gmail.com`` when the queue
    already holds ``sandycraft3@gmail.com`` UPDATES that row rather than creating a second one —
    without which this function would quietly manufacture the very collision the canonicalisation
    exists to prevent, and the pending request the admin thought they were approving would still be
    sitting in the queue afterwards. The found row's ``email`` is deliberately left alone: rewriting
    the address on somebody's row as a side effect of an unrelated approval changes what an admin
    sees on the screen with nothing to say why, and moving an address between rows is
    :func:`follow_email_change`'s job, where it is the thing being asked for.
    """
    keys = email_match_keys(email)
    if not keys:
        raise ValueError("an allow-list row needs an email address")
    address = canonical_email(email)
    now = datetime.now(UTC)
    grant: dict[str, Any] = {"status": ACTIVE, "joinedAt": now}
    if admit_role:
        grant["admitRole"] = admit_role
    if full_name is not None:
        grant["fullName"] = full_name
    if note is not None:
        grant["notes"] = note
    if decided:
        grant["decidedAt"] = now
        grant["decidedById"] = actor_id

    matched = await db.accessroster.find_many(where={"email": {"in": keys}})
    if not matched:
        return await db.accessroster.create(data={**grant, "email": address, "addedById": actor_id})
    # THE ROW THE GATE WILL READ, and not merely the first row the index hands back. Where a mailbox
    # somehow has rows under both spellings, :func:`access_row` answers with the barred one, so an
    # admin who admitted the OTHER one would watch this call succeed and the person be refused
    # anyway — an approval that visibly took effect on the screen and changed nothing at the door.
    # Admitting whichever row decides the gate is what makes "I have admitted them" true.
    existing = _the_row_that_decides(matched, keys[0])
    update = dict(grant)
    # Written once; see the docstring. The read-then-write is safe here because both branches are
    # admin actions on one row, not a contended login path.
    update["joinedAt"] = existing.joinedAt or now
    return await db.accessroster.update(where={"id": existing.id}, data=update)


async def follow_email_change(old_email: Any, new_email: Any, *, actor_id: str | None) -> None:
    """Move an admission when an admin changes an account's address.

    THE ALLOW-LIST IS KEYED BY EMAIL, so an admin who corrects a typo in somebody's address has,
    without this, just locked them out: the new address has no row, a missing row is a refusal, and
    the person's next sign-in puts them in the pending queue as though they were a stranger. The
    admin has no reason to connect the two — they edited a name field, not a permission.

    The ROW IS MOVED rather than a second one created, so ``joinedAt``, the attempt history and the
    name of the admin who admitted them follow the person instead of being stranded on an address
    nobody uses. If the new address somehow already has a row (they had asked to join under it, say)
    that row wins and is admitted, because it is the one the gate will actually read.

    **"CHANGED" IS DECIDED ON THE MAILBOX, WHICH IS WHY THE EARLY RETURN COMPARES CANONICAL FORMS.**
    An admin who tidies ``sandycraft3@gmail.com`` into ``sandy.craft3@gmail.com`` on somebody's
    account has not moved them anywhere — Google delivers both to the same inbox and the gate reads
    both as one key — so there is nothing to follow, and treating it as a move would rewrite a live
    allow-list row for no reason and reset nothing but the reader's confidence in the screen.
    """
    new = canonical_email(new_email)
    old_keys = email_match_keys(old_email)
    new_keys = email_match_keys(new_email)
    if not new or canonical_email(old_email) == new:
        return
    existing_new = await db.accessroster.find_first(where={"email": {"in": new_keys}})
    if existing_new is not None:
        await admit(
            new,
            actor_id=actor_id,
            note="Admitted when an administrator moved an existing account to this address.",
        )
        return
    old_row = (
        await db.accessroster.find_first(where={"email": {"in": old_keys}}) if old_keys else None
    )
    if old_row is None:
        await admit(
            new,
            actor_id=actor_id,
            note="Admitted when an administrator set this address on an existing account.",
        )
        return
    await db.accessroster.update(where={"id": old_row.id}, data={"email": new})


async def record_refused_attempt(email: Any, row: Any | None) -> str:
    """Write down that a PROVEN identity was refused, and report what to tell them.

    Returns one of :data:`PENDING`, :data:`REJECTED`, :data:`SUSPENDED` or :data:`NOT_RECORDED`.

    **CALLED FROM EXACTLY ONE FUNCTION**, ``auth.assert_access_admits`` — and that function is now
    reached from more than one endpoint, which is the sentence this docstring used to get wrong. It
    said "exactly one place" while the gate was only on the login path; ``POST /api/datasets/token``
    now goes through the same gate, because minting a thirty-day machine credential is the other way
    a password becomes a token and a suspended admin was walking out of it with one.

    SO THE BOUND IS NOT "ONE CALLER", IT IS "EVERY CALLER HAS ALREADY PROVED THE IDENTITY": a bcrypt
    check that passed, or a Google ID token that verified against a configured audience. Every bound
    described in this module's docstring depends on THAT and not on the number of call sites — a new
    caller reaching here before verifying a credential would turn its endpoint into a form for
    writing arbitrary addresses into an admin's queue, however many callers there are. Route the new
    caller through ``assert_access_admits`` after its credential check, never at this function
    directly.

    **THE QUEUED ROW IS KEYED ON THE MAILBOX.** Two refused attempts from ``sandycraft3@gmail.com``
    and ``sandy.craft3+phone@googlemail.com`` are one person asking twice, and writing them as two
    rows would give an administrator two entries to decide about for one applicant, of which
    approving either leaves the other in the queue for ever. The row this function does NOT have to
    look for is one already stored under the other spelling: ``row`` is passed in by
    ``assert_access_admits``, which got it from :func:`access_row`, which searched both.
    """
    address = canonical_email(email)
    now = datetime.now(UTC)

    if row is not None:
        # THE STATUS IS NOT TOUCHED. A rejected person stays rejected and a suspended person stays
        # suspended; only the count and the stamp move. See the module docstring.
        await db.accessroster.update(
            where={"id": row.id},
            data={"attemptCount": {"increment": 1}, "lastAttemptAt": now},
        )
        return status_of(row)

    if not address or len(address) > MAX_EMAIL_LENGTH:
        # Unreachable through either login branch as they stand (one address comes from a row in
        # `User`, the other from a signed Google token), and written anyway because this is the
        # only attacker-influenced value that reaches the table.
        logger.warning("refusing to record an access request for an unusable address")
        return NOT_RECORDED

    waiting = await pending_count()
    if waiting >= pending_cap():
        # LOUD, because this is the state in which the product stops being able to tell an
        # administrator that somebody wants in. It is a capacity condition, not a decision about
        # the person, and the message they get says so.
        logger.error(
            "the pending access queue holds %s requests, at or past the %s cap, so a new request "
            "from a verified identity was NOT recorded; an administrator must clear the queue "
            "before anybody new can ask to join",
            waiting,
            pending_cap(),
        )
        return NOT_RECORDED

    # UPSERT RATHER THAN CREATE, for the race the read above cannot close: two sign-in attempts from
    # the same new address, milliseconds apart, both find no row. A bare create would answer the
    # second one with a unique-violation 500 — the person is told the server is broken when the
    # truth is that their request was recorded twice over.
    created = await db.accessroster.upsert(
        where={"email": address},
        data={
            "create": {
                "email": address,
                "status": PENDING,
                "requestedAt": now,
                "attemptCount": 1,
                "lastAttemptAt": now,
            },
            "update": {"attemptCount": {"increment": 1}, "lastAttemptAt": now},
        },
    )
    return status_of(created)


async def mark_access_seen(row: Any) -> None:
    """Stamp ``firstSeenAt`` the first time an admitted address actually gets in.

    Written once, guarded in the WHERE rather than in Python, so two simultaneous sign-ins cannot
    race each other into overwriting it — ``designers.mark_roster_seen``'s rule, for its reason: the
    column answers "did the person we approved ever arrive", and a value that moved on every login
    would silently become "when did they last log in" while still being read as the first.
    """
    if row is None or getattr(row, "firstSeenAt", None) is not None:
        return
    await db.accessroster.update_many(
        where={"id": row.id, "firstSeenAt": None},
        data={"firstSeenAt": datetime.now(UTC)},
    )


# ══════════════════════════════════════════════════════════════════════════════════════════════
# THE CROSS-ROSTER MIRROR: a revocation on either roster reaches the other
# ══════════════════════════════════════════════════════════════════════════════════════════════
#
# THE SECOND OF THE TWO CLAUSES IN THIS FILE THAT ARE SPECIFIC TO THIS APPLICATION — see the module
# docstring, and :func:`designer_empanelment_admits`, which is the first and is this one's mirror
# image in the reading direction.
#
# **THE DEFECT THIS CLOSES IS A RECORD ONE, NOT A BYPASS, AND SAYING SO IS THE POINT.** Before this
# block, suspending an allow-list row wrote ``AccessRoster`` and returned, and suspending an
# empanelment wrote ``DesignerRoster`` and returned. Nobody could sign in around either: ``auth.py``
# asks both gates and each refuses on its own answer. What the product had instead was two admin
# screens showing contradictory standing for one person, with the refusal sentence correct and
# neither screen able to explain it — an admin approving somebody at DESIGNER on the allow-list
# would watch that screen say ACTIVE while the person read "Your designer access has been
# suspended", about a revocation the approving admin never saw. And one live consequence beyond the
# record: ``/designers/directory`` and the workshop pickers filter on ``DesignerRoster.isActive``
# and not on the allow-list, so an address an administrator barred from the whole application was
# still being OFFERED as somebody to hand a fortnight of fieldwork to.

#: THE THREE ACTS THAT END A STANDING, AND THEREFORE THE THREE CAUSES THAT MIRROR. Named constants
#: rather than free text at the call sites, so that the four callers cannot describe the same act
#: three different ways on the row an admin later reads — the failure ``DERIVED_EMPANELMENT_NOTE``
#: exists to prevent, in the suspending direction.
#:
#: **THERE IS DELIBERATELY NO FOURTH CAUSE MEANING "RESTORED".** That is not an oversight and it is
#: not a gap for somebody to fill in: see :func:`mirror_suspension` and, at greater length,
#: :func:`app.services.designers.ensure_empanelled`. A restoring cause would be the upsert that
#: function refuses, re-entered through a new door.
MIRROR_ACCESS_SUSPENDED = "ACCESS_SUSPENDED"
MIRROR_ACCESS_REJECTED = "ACCESS_REJECTED"
MIRROR_EMPANELMENT_ENDED = "EMPANELMENT_ENDED"

#: What a mirrored row says about itself, one sentence per cause. Two clauses each, on
#: :data:`app.services.designers.DERIVED_EMPANELMENT_NOTE`'s pattern: what happened, and then the
#: part no column can carry — that NOBODY performed this act on the roster the row is on. An admin
#: reading ``/admin/designers`` has to be able to tell a revocation somebody made there from one
#: that arrived as a consequence, and ``addedById``/``decidedById`` cannot tell them: those name
#: whoever granted the standing, and are NULL on a derived row and NULL again on a row whose
#: creating administrator has since been deleted.
#:
#: KEPT IN ONE TABLE, both directions together, because they are two halves of one rule and the way
#: they fail is by drifting into two voices — one saying "automatically", the other reading like
#: somebody's decision — on two screens an administrator reads side by side.
MIRROR_NOTES: dict[str, str] = {
    MIRROR_ACCESS_SUSPENDED: (
        "Empanelment suspended automatically because an administrator suspended this address on "
        "the platform allow-list, ending its access to the application. No administrator withdrew "
        "this empanelment on the designer roster directly, and restoring the allow-list row will "
        "not restore it — that is a separate decision, taken here."
    ),
    MIRROR_ACCESS_REJECTED: (
        "Empanelment suspended automatically because an administrator rejected this address's "
        "request to access the application on the platform allow-list. No administrator withdrew "
        "this empanelment on the designer roster directly, and approving the allow-list row will "
        "not restore it — that is a separate decision, taken here."
    ),
    MIRROR_EMPANELMENT_ENDED: (
        "Access suspended automatically because an administrator ended this address's designer "
        "empanelment, which is the basis on which it was admitted to the application. No "
        "administrator barred this address on the allow-list directly, and restoring the "
        "empanelment will not restore this — that is a separate decision, taken here."
    ),
}


def _account_role(user: Any) -> str:
    """``User.role`` as a plain string. :func:`status_of`'s trap, on a third column.

    Prisma hands back an enum member on a live row and a bare string on anything hand-built, and
    ``user.role == "DESIGNER"`` silently answers False for the first of those — an equality test
    that here decides whether somebody keeps their access to the whole product.

    **NOT ``app.core.deps.role_value``, WHICH IS THE SAME FOUR LINES, AND FOR TWO REASONS THAT ARE
    BOTH HARD.** ``app/core/deps.py`` imports this module at module level, so importing it back
    would be a circular import — a real one, that fails at start-up rather than at a call. And this
    module is written to be COPIED into an application that has no FastAPI dependency layer at all
    (see the porter's note at the top): a service reaching into the request-scoped dependency module
    for a four-line enum reader is a dependency the porter would have to excise by hand. Its two
    neighbours :func:`status_of` and :func:`role_of` are the same reader on two other columns for
    the same reason; a third is this module's own precedent, not a new duplication.
    """
    if user is None:
        return ""
    role = getattr(user, "role", None)
    return str(getattr(role, "value", role) or "")


#: How many accounts the Gmail sweep in :func:`_accounts_on_the_mailbox` will read before it
#: refuses to answer at all.
#:
#: A BACKSTOP AGAINST AN UNBOUNDED READ, on :data:`BARRED_EMAIL_READ_LIMIT`'s pattern — and its own
#: number, and its own behaviour at the cut, because the two lists decide opposite things. That one
#: bounds a list used to EXCLUDE people, so a cut list over-permits, and it is reported and then
#: used. This one bounds the read that decides whether somebody keeps their access to the whole
#: product, and an account past the cut is precisely the administrator the sweep exists to find. So
#: hitting it does not SHORTEN the answer, it WITHDRAWS it: the caller declines, nothing is
#: mirrored, and the ERROR line names the address that was left un-mirrored, so the pair is settled
#: by a human instead of by a bar nobody checked.
#:
#: Far above any plausible deployment. This is one institution's portal — ``GET /api/users`` caps
#: its own page at 500 rows and the directory read at 500 more — and this reads the subset of that
#: table sitting on two consumer domains.
GMAIL_ACCOUNT_SWEEP_LIMIT = 20_000


async def _accounts_on_the_mailbox(keys: list[str], mailbox: str) -> list[Any] | None:
    """Every ``User`` whose address IS this mailbox, HOWEVER IT IS SPELLED. ``None`` = cannot say.

    The second test in :func:`admissions_an_empanelment_carries` — the one that keeps a professor or
    an admin who runs workshops too from being locked out of the product when somebody ends their
    empanelment — and the reason it is a function rather than the one-line ``IN`` it used to be.

    **THE ONE-LINE VERSION WAS AN ADMINISTRATOR LOCKOUT, REACHED BY A DOT.** ``email_match_keys``
    answers *"every stored spelling of this mailbox"* for the two ROSTER tables, which are
    canonicalised, and it does that by adding the canonical form to whatever it was given. Asked
    about an address that ALREADY IS the mailbox it yields exactly one key — its own docstring and
    :func:`app.services.designers.suspend_empanelment` both say so. ``User.email`` is not
    canonicalised, deliberately and by name: ``auth.login_with_google`` argues that account identity
    was left out of the Gmail fold on purpose, ``POST /api/users`` stores ``payload.email.lower()``
    with the dots an admin typed, and :func:`follow_email_change` — the path an admin uses to
    CORRECT somebody's address — moves the allow-list row onto the mailbox and leaves the account
    spelled as typed. So the ordinary, product-as-designed state is an account at ``a.b@gmail.com``
    beside roster rows at ``ab@gmail.com``, and a key list built from the roster side cannot reach
    it. The lookup matched nothing, the caller read that as "there is no account", its rule for an
    absent account is to mirror — and ending a designer's empanelment SUSPENDED an ADMIN's
    allow-list row and barred them from the entire application, from a screen about designers, with
    the guard written to prevent exactly that reporting nothing at all.
    ``test_ending_an_empanelment_leaves_an_admin_alone_when_the_account_is_a_gmail_alias`` is that
    sequence, through the endpoints that produce it.

    **SO THE QUESTION IS ASKED THE ONLY WAY IT CAN BE ANSWERED EXACTLY: CANONICALISE BOTH SIDES.**
    There is no ``WHERE`` this client can express that folds an address stored with dots onto the
    mailbox — the fold is a function of the value rather than a prefix of it, and no index answers
    it — so the candidate set is narrowed by the one thing SQL can decide (the domain is one Google
    serves that mailbox from) and :func:`canonical_email` decides the rest in Python. That is exact:
    it finds every spelling, including a ``+tag``, including ``googlemail.com``, including one
    nobody has thought of, because it applies the same fold the gates apply.

    **THE EXACT LOOKUP IS KEPT AND RUNS FIRST**, and not as belt-and-braces: for every address that
    is not a Gmail mailbox — every institutional domain in this product — it is the WHOLE answer.
    The key list really is exhaustive there, because dots are ordinary characters outside
    :data:`~app.services.designers.GMAIL_DOMAINS` and no other spelling can be the same mailbox, so
    the sweep is skipped entirely and an ``@example.org`` empanelment costs exactly the one query it
    always did.

    ``mode: "insensitive"`` on both reads because ``User.email`` is not lower-cased either — the
    same reason the designer directory's role filter carries it. Without it an account stored
    shouting would not be found, which is this whole docstring's failure in its smaller and older
    form: an ADMIN spelled ``A.Sharma@Example.org`` barred by somebody ending an empanelment they
    had nothing to do with.

    **RETURNS ``None`` RATHER THAN A SHORT LIST WHEN THE SWEEP IS CUT.** A truncated answer here is
    not a smaller answer, it is a WRONG one in the single direction that matters: the account it did
    not read is the one that would have refused the mirror. The caller declines on ``None``, so an
    over-large user table costs an un-mirrored pair — the state the product was in before the mirror
    existed, visible on two screens and repairable by
    ``scripts/backfill_roster_suspension_mirror.py`` with a human reading the plan first — instead
    of a bar this function never verified.

    Reads whole ``User`` rows because this client has no field projection. Nothing leaves the
    process, and only the address and the role are looked at.
    """
    accounts = await db.user.find_many(where={"email": {"in": keys, "mode": "insensitive"}})
    if mailbox.partition("@")[2] not in GMAIL_DOMAINS:
        return accounts
    swept = await db.user.find_many(
        where={
            "OR": [
                {"email": {"endswith": f"@{domain}", "mode": "insensitive"}}
                for domain in sorted(GMAIL_DOMAINS)
            ]
        },
        take=GMAIL_ACCOUNT_SWEEP_LIMIT + 1,
    )
    if len(swept) > GMAIL_ACCOUNT_SWEEP_LIMIT:
        logger.error(
            "the cross-roster mirror could not check whether %r belongs to somebody who is not a "
            "designer: more than %s accounts sit on the Gmail domains, so the sweep that finds an "
            "account filed under another spelling of one mailbox was CUT and its answer cannot be "
            "trusted. NOTHING WAS MIRRORED for this address, deliberately — barring somebody on a "
            "cut list is how an administrator gets locked out of the product. Raise "
            "GMAIL_ACCOUNT_SWEEP_LIMIT, then settle the pair with "
            "scripts/backfill_roster_suspension_mirror.py",
            mailbox,
            GMAIL_ACCOUNT_SWEEP_LIMIT,
        )
        return None
    # KEYED BY ID SO THE TWO READS CANNOT DOUBLE-COUNT ONE ACCOUNT. They overlap by construction
    # whenever the mailbox is spelled canonically, and the ``any`` in the caller would not care, but
    # a list claiming two accounts where there is one is a list the next reader has to re-derive.
    found = {account.id: account for account in accounts}
    for account in swept:
        if canonical_email(account.email) == mailbox:
            found[account.id] = account
    return list(found.values())


async def accounts_on_the_mailbox(email: Any) -> list[Any] | None:
    """Every ``User`` this address could mean, HOWEVER IT IS SPELLED. ``None`` = cannot say.

    :func:`_accounts_on_the_mailbox` with the two arguments derived rather than passed in — read
    that function for the whole argument, the Gmail fold, the sweep limit and why a cut sweep
    withdraws its answer instead of shortening it. This exists so a caller outside this module can
    ask the question without rebuilding the key list and the canonical form, which is the pair a
    second copy would get subtly wrong (the length filter, in particular, is not decoration: an
    over-long key would be handed to a ``VARCHAR`` comparison).

    **EXPORTED FOR ``routes/access.end_live_sessions`` (2026-09-03), AND THE FAILURE DIRECTION IS
    NOT THE SAME THERE AS IT IS HERE.** Inside this module a ``None`` means "do not mirror", which
    leaves two rosters disagreeing — visible, repairable, nobody barred by an unverified answer. At
    the revocation door it means "these sessions may still be live", which is the UNSAFE direction,
    so that caller logs rather than shrugs. The distinction belongs to the callers; the read is one
    implementation because "which accounts are this mailbox" must not have two answers.
    """
    keys = [key for key in email_match_keys(email) if len(key) <= MAX_EMAIL_LENGTH]
    if not keys:
        return []
    return await _accounts_on_the_mailbox(keys, canonical_email(email))


async def admissions_an_empanelment_carries(email: Any) -> list[Any] | None:
    """The ACTIVE allow-list rows whose admission RESTS on this mailbox's designer empanelment.

    ``None`` = THE SWEEP COULD NOT ANSWER, and it is a different fact from ``[]``. See the section
    at the foot of this docstring; every caller that only mirrors may keep treating the two the
    same, and the one caller that REVOKES may not.

    The guard on the empanelment-to-allow-list direction of the mirror, and the reason that
    direction is safe at all. Answers ``[]`` — mirror nothing — for everybody whose place in this
    application has some other basis. Exported rather than private because
    ``scripts/backfill_roster_suspension_mirror.py`` has to bucket rows by exactly this test: a
    backfill whose plan and whose write ask different questions prints a report that is fiction.

    **AN EMPANELMENT ENDING MUST NOT END A PROFESSOR'S ACCESS TO THE PRODUCT, AND THAT IS AN
    OWNER-APPROVED RULE, NOT A PREFERENCE.** ``auth.assert_roster_admits`` says it outright: the two
    refusals are two decisions with two remedies, and "collapsing the pair would mean revoking an
    empanelment silently locks the person out of the whole product — including a professor or an
    admin who happens to be on the designer roster because they run workshops too, whose account has
    nothing to do with the empanelment being ended". That gate protects them by returning early for
    any role that is not DESIGNER. A SUSPENDED allow-list row has no such exemption — it refuses
    everybody — so the mirror has to carry the exemption itself, and this is it.

    TWO TESTS, AND THEY ANSWER TWO DIFFERENT QUESTIONS:

    1. **``admitRole`` IS DESIGNER.** That column is the answer to *on what basis was this address
       let into the application*. A row saying DESIGNER says: because they are a designer. A row
       saying RESEARCHER, or PROFESSOR, or nothing at all (NULL is the platform default, the lowest
       rung — see the column's own note in schema.prisma) says the person is here on a basis the
       empanelment has nothing to do with, and ending the empanelment must not end it.
    2. **THE ACCOUNT, IF THERE IS ONE, IS ITSELF A DESIGNER** — which is ``assert_roster_admits``'
       own test, deliberately, so that the mirror bars exactly the set of people the empanelment
       gate already refuses and not one person more. It is a SECOND question because ``admitRole``
       records what a row admitted somebody AS at the time and nothing keeps it in step afterwards:
       ``_lift_existing_account`` raises ``User.role`` without touching it, and ``PATCH /users/{id}``
       changes the role without touching it either. So an ADMIN can perfectly well be carrying a row
       that still says DESIGNER, and barring them on the strength of it is the exact outage the
       paragraph above forbids.

       **IT IS ASKED OF THE MAILBOX AND NOT OF THE KEY LIST, WHICH IS NOT THE SAME QUESTION AND WAS
       AN ADMINISTRATOR LOCKOUT UNTIL IT WAS.** :func:`_accounts_on_the_mailbox` is why; the failure
       is worth stating here because this is the line that decides it. Test 1 is EXPECTED to be
       stale — that is the paragraph above — so this test is the entire exemption, and it used to be
       a plain ``IN`` over ``email_match_keys``. Both roster tables are canonicalised and
       ``User.email`` deliberately is not (``auth.login_with_google`` says so in as many words;
       ``POST /api/users`` stores what was typed; ``follow_email_change`` moves the allow-list row to
       the mailbox and leaves the account's spelling alone), and ``email_match_keys`` derives exactly
       ONE key from an address that already IS the mailbox. So for an ADMIN filed under
       ``a.b@gmail.com`` beside roster rows filed under ``ab@gmail.com`` — an ordinary consequence of
       an administrator correcting an address — the lookup matched nothing, that read as *"there is
       no account"*, the rule below says an absent account is a mirror, and ending somebody else's
       empanelment barred an administrator from the entire product. Every step defensible; the sum
       of them the one outage this function exists to prevent.

    **NO ACCOUNT AT ALL IS A MIRROR, NOT A REFUSAL TO MIRROR.** An empanelment can be granted before
    the person has ever opened the app — that is what the roster is for — and an ACTIVE row admitting
    at DESIGNER will provision the account AT DESIGNER on their first Google sign-in. Leaving that
    admission standing after an admin has ended the empanelment would hand somebody a brand-new
    DESIGNER account that the empanelment gate then refuses: requirement 28's original bug, rebuilt.

    **PENDING ROWS ARE NOT TOUCHED, AND THE OMISSION IS THE SAME RULE AS EVERYWHERE ELSE IN THIS
    MODULE.** PENDING means nobody has decided. Moving one to SUSPENDED would be deciding a request
    no administrator answered, in the barring direction — which is what :func:`record_refused_attempt`
    refuses to do from the other end and what :func:`barred_emails` refuses to read as a bar. A
    PENDING person whose empanelment ends simply stops being admitted by the empanelment clause in
    ``auth.assert_access_admits`` and goes on reading the queue's own sentence, which is true.

    **THE FAILURE DIRECTION, STATED.** Every test here fails towards NOT mirroring, and that is the
    safe way round: an un-mirrored pair is exactly the state the product was in before this block
    existed — two screens disagreeing, both gates still refusing correctly — which is visible and
    fixable in five minutes by the same administrator on the same screen. A wrongly mirrored pair is
    somebody locked out of the entire application by a click nobody connected to them.

    **``None`` IS "I COULD NOT CHECK" AND ``[]`` IS "NOBODY", AND CONFLATING THEM COST THE ONE ALARM
    THIS PAIR HAS (2026-09-03).** Both used to be spelled ``[]``. For the MIRROR that is harmless
    and remains so — declining to mirror on an unverified answer is the safe direction, and
    :func:`_bar_an_ended_empanelment` still treats the two identically on purpose.

    It is not harmless for the REVOCATION door. ``routes/designers._end_what_the_empanelment_carried``
    reads this to decide whether to end the sessions the empanelment was carrying, and a cut sweep
    made that read ``[]`` — "this person keeps their access" — so ``end_live_sessions`` was skipped
    SILENTLY, in exactly the direction :func:`accounts_on_the_mailbox`'s own note says the callers
    must not shrug at: an administrator has been told access is cut while a live token may run to
    its expiry. ``end_live_sessions`` already shouts about the identical condition when it reaches
    the sweep itself; with the two answers merged here it was never reached to shout. Distinguishing
    them is what lets that door log the same ERROR, name the address, and name the repair.
    """
    keys = [key for key in email_match_keys(email) if len(key) <= MAX_EMAIL_LENGTH]
    if not keys:
        return []
    rows = await db.accessroster.find_many(where={"email": {"in": keys}, "status": ACTIVE})
    admitted_as_a_designer = [row for row in rows if role_of(row) == "DESIGNER"]
    if not admitted_as_a_designer:
        return []
    accounts = await _accounts_on_the_mailbox(keys, canonical_email(email))
    if accounts is None:
        # THE SWEEP COULD NOT ANSWER, SO NEITHER CAN THIS. It has already said so at ERROR and named
        # the repair; declining here is what turns "I could not check" into an un-mirrored pair
        # rather than into a bar nobody verified. See :data:`GMAIL_ACCOUNT_SWEEP_LIMIT`.
        #
        # ``None`` AND NOT ``[]`` SINCE 2026-09-03. Every mirroring caller reads both as falsy and
        # behaves exactly as before; the revocation door reads the difference and shouts. See the
        # last section of this docstring.
        return None
    if any(_account_role(account) != "DESIGNER" for account in accounts):
        # ANY, not ALL. Two spellings of one Gmail mailbox can hold two accounts, and if either of
        # them is something other than a designer then somebody's access to this application does
        # not rest on the empanelment. Refusing on the stronger of the two answers is the same
        # fail-closed reading ``roster_allows`` gives a pair of rows that disagree. The helper above
        # is what makes this sentence true of the second spelling as well as the first — until it
        # existed, the query could not see that account and this line could not refuse on it.
        return []
    return admitted_as_a_designer


async def _bar_an_ended_empanelment(email: Any, *, actor_id: str | None, because: str) -> int:
    """Suspend the allow-list rows an ended empanelment was carrying. The write half of the guard.

    Reached only from :func:`mirror_suspension`; the decision about WHICH rows is
    :func:`admissions_an_empanelment_carries`', and is made there so that the backfill can ask the
    same question without reproducing it.

    Written the way ``access.suspend_access_entry`` writes the same transition, statement for
    statement, so that a row an administrator suspends by hand and a row this mirror suspends are
    the same shape afterwards and no screen has to tell them apart by their columns. In particular
    ``decidedAt`` and ``decidedById`` are KEPT where they already exist: that pair records who last
    decided anything about this row, and the mirror is not somebody deciding — the administrator's
    decision was next door. Where they are empty (a grandfathered row nobody ever decided) the
    acting admin's id is written, because they are the nearest thing to an answer there is and an
    empty pair on a suspended row reads as a bug in the screen.

    Idempotent in the WHERE, for :func:`app.services.designers.suspend_empanelment`'s reason: the
    filter names the status the row must still be in, so a direct suspension landing between the
    read and this write leaves that admin's own ``decidedAt`` standing rather than being rewritten.
    """
    rows = await admissions_an_empanelment_carries(email)
    if not rows:
        # ``None`` (the sweep could not answer) AND ``[]`` (nobody's admission rests on it) SHARE
        # THIS BRANCH DELIBERATELY. The mirror's failure direction is "do not bar", and both answers
        # mean the same thing to it: there is nothing here it is willing to suspend. Only the
        # revocation door in ``routes/designers`` needs to tell them apart, because only there does
        # a declined answer leave a live session behind. (2026-09-03)
        return 0
    now = datetime.now(UTC)
    barred = 0
    for row in rows:
        barred += await db.accessroster.update_many(
            where={"id": row.id, "status": ACTIVE},
            data={
                "status": SUSPENDED,
                "decidedAt": row.decidedAt or now,
                "decidedById": row.decidedById or actor_id,
                "notes": note_recording_a_consequence(row.notes, because),
            },
        )
    return barred


async def mirror_suspension(email: Any, cause: str, *, actor_id: str | None = None) -> int:
    """**THE ONE PLACE A REVOCATION ON EITHER ROSTER REACHES THE OTHER.** Suspending only, ever.

    Returns how many rows on the OTHER roster this call actually suspended: 0 where that person has
    no row there, 0 where their row is already suspended, and 0 where the mirror declined or failed.

    Four call sites, all of them administrator write paths, all passing one of the three
    :data:`MIRROR_NOTES` causes:

    ==================================================  ================================
    ``access.decide_access_request``, the REJECT arm     :data:`MIRROR_ACCESS_REJECTED`
    ``access.suspend_access_entry`` (``DELETE``)         :data:`MIRROR_ACCESS_SUSPENDED`
    ``designers.update_roster_entry`` (``isActive`` off) :data:`MIRROR_EMPANELMENT_ENDED`
    ``designers.suspend_roster_entry`` (``DELETE``)      :data:`MIRROR_EMPANELMENT_ENDED`
    ==================================================  ================================

    **ONE FUNCTION AND NOT TWO, WHICH IS THE WHOLE ARRANGEMENT.** Both directions of a mirror
    written by hand in two places will diverge — one of them will learn about Gmail spellings, or
    about idempotence, or about not reviving anything, and the other will not — and a mirror that
    holds in one direction only is worse than no mirror, because an administrator comes to rely on
    it. That is precisely the class of bug that produced the empanelment gap this whole family of
    functions exists to close: two rosters, one rule, written down once on one side. So the rule is
    written here once, and the per-table writes it dispatches to are each themselves single
    implementations, kept beside the other writers of the table they touch.

    **IT LIVES IN THIS MODULE BECAUSE THE IMPORT GRAPH LEAVES NO CHOICE, AND THAT IS WORTH STATING
    SO NOBODY TRIES THE OTHER ARRANGEMENT.** This module already imports from
    ``app.services.designers``, so ``designers`` cannot import ``access_roster``: a helper that
    writes ``AccessRoster`` cannot live beside ``ensure_empanelled``. The routes are leaves and may
    import either.

    ── RULE 1, AND EVERY OTHER RULE IN THIS FUNCTION IS SUBORDINATE TO IT ─────────────────────────

    **SUSPENSION PROPAGATES. REACTIVATION NEVER DOES, IN EITHER DIRECTION.** Restoring an allow-list
    row does not restore an empanelment and restoring an empanelment does not restore an admission.
    There is no cause in :data:`MIRROR_NOTES` that means "restored", no branch below that could take
    one, and no caller on any restoring path.

    The argument is :func:`app.services.designers.ensure_empanelled`'s, in full, and it applies
    unchanged: suspension is a DELIBERATE REVOCATION — both rosters suspend rather than delete
    precisely so the record of the standing survives the ending of it — and reviving a suspended row
    from the other table would silently undo every revocation any administrator has ever made, at
    the moment somebody restored an unrelated standing next door, with nothing on either screen to
    say it happened. An administrator who ended an empanelment in March must not have it handed back
    because a different administrator un-barred that address in July for a different reason. Those
    are two decisions, and only an admin may make the second, on the screen where it is visible as
    an act somebody took.

    **AND THE ASYMMETRY IS DELIBERATE RATHER THAN INCIDENTAL.** The two directions are not
    symmetrical mistakes, which is why one of them is built and the other is refused:

    * **Reactivation GRANTS** standing an administrator removed. It is silent, it is permanent until
      somebody notices, and the person who triggers it is the revoked party or an admin acting on an
      unrelated matter.
    * **Suspension REMOVES** standing, at the moment an administrator deliberately removed the
      matching standing next door. It fails closed. It cannot undo anybody's decision; its worst
      case is a revocation enacted more broadly than intended, which is visible on the screen that
      records it and reversible by the same admin in the same session.

    This is exactly :func:`_the_row_that_decides`' asymmetry — *"a visible, complainable, five-minute
    fix by the same administrator on the same screen"* against a decision quietly overturned —
    stated for writes instead of for reads.

    **THE PRICE, WHICH THE OWNER WAS TOLD AND WHICH THE SCREENS OWE A SENTENCE.** An administrator
    who bars somebody by mistake and immediately un-bars them has permanently ended that person's
    empanelment. The remedy is one click on the other roster; the failure is that nobody knows to
    make it. Both restore paths, and both suspend confirmations, owe a line naming what else is
    about to change and what will not come back. That is client work and is named in this change's
    report rather than done here.

    ── RULE 6: THIS MUST NEVER BREAK THE ADMINISTRATOR'S PRIMARY ACTION ───────────────────────────

    **EVERY FAILURE IS SWALLOWED AND LOGGED AT ERROR, AND THE MIRROR IS NOT IN THE PRIMARY WRITE'S
    TRANSACTION.** Both halves of that are decisions, so both are argued:

    *Not in the transaction.* The obvious objection is that a primary write which commits without
    its mirror leaves the two rosters disagreeing — which is true, and is EXACTLY the state the
    product was in before this block existed. It is a stale record and not a bypass: ``auth.py``
    asks both gates and each refuses on its own answer, so the un-mirrored half still refuses the
    person wherever the administrator actually barred them. Rolling the administrator's suspension
    back because the mirror could not run would instead report a failure for an action that
    succeeded, on the one screen whose job is ending somebody's access, and the admin's reasonable
    next move — click it again — is the move that does nothing. There is a mechanical reason on top
    of the argument: Prisma's ``db.tx()`` hands back a DIFFERENT client, and every writer in this
    family (``admit``, ``ensure_empanelled``, ``suspend_empanelment``, the two route handlers)
    writes through the module-level ``db`` singleton, so a callee holding its own reference is
    simply not inside a caller's transaction — see the same note on
    ``design_workshop_grants._grant_by_token``. Joining one would mean threading a transaction
    client through five functions, where the way a missed one fails is SILENTLY writing outside the
    transaction it appears to be in.

    *Swallowed.* These call sites are administrator writes and one of them is reachable on a path
    that ends in a token being minted; an unhandled driver error here reaches the catch-all in
    ``app/main.py`` and the administrator is told the server broke about a suspension that in fact
    took effect. So the mirror cannot raise. It is logged at ERROR — with the address and the
    direction, in words that say the two rosters are now inconsistent and name the script that
    repairs them — because a swallowed failure nobody can see is the same defect one layer down.

    A BAD ``cause`` IS THE ONE THING THAT DOES RAISE, and deliberately: it can only be a broken call
    site, never a runtime condition, since all four callers pass a module constant. Answering it
    with a silent no-op would be a mirror that stopped mirroring and said nothing — the failure
    every line above is written to prevent.
    """
    if cause not in MIRROR_NOTES:
        raise ValueError(f"unknown cross-roster suspension cause {cause!r}")
    because = MIRROR_NOTES[cause]
    try:
        if cause == MIRROR_EMPANELMENT_ENDED:
            changed = await _bar_an_ended_empanelment(email, actor_id=actor_id, because=because)
        else:
            # The allow-list barred the address, so the empanelment goes with it. NO GUARD ON THIS
            # DIRECTION, and its absence is a decision: being barred from the application is
            # strictly wider than losing an empanelment, so there is no role, rank or account state
            # for which an ACTIVE empanelment still means anything after it. The guard exists on the
            # other direction only because that one is the narrower revocation trying to enact the
            # wider one — see :func:`admissions_an_empanelment_carries`.
            changed = await suspend_empanelment(email, because=because)
    # A BARE ``except Exception`` ON PURPOSE, AND IT CARRIES NO SUPPRESSION COMMENT: BLE001 already
    # accepts a blind catch whose handler logs the traceback, which is exactly the shape argued for
    # in the docstring — this must never break the administrator's own write, and it must never be
    # silent about not having run. (An added suppression would be flagged by RUF100 as dead.)
    except Exception:
        logger.exception(
            "the cross-roster suspension mirror FAILED for %r after a %s: the two rosters now "
            "disagree about this address, one of them still showing an active standing for "
            "somebody an administrator has revoked. The administrator's own action DID take "
            "effect. Repair with scripts/backfill_roster_suspension_mirror.py",
            normalise_email(email),
            cause,
        )
        return 0
    if changed:
        logger.info(
            "mirrored a %s onto %s row(s) for %r on the other roster",
            cause,
            changed,
            normalise_email(email),
        )
    return changed


def access_payload(row: Any) -> dict[str, Any]:
    """One allow-list row as the admin screens read it, on both clients."""
    return {
        "id": row.id,
        "email": row.email,
        "fullName": row.fullName,
        "status": status_of(row),
        "admitRole": role_of(row),
        # The requirement's "date of joining the platform". Named ``joinedAt`` on the wire too, so
        # the column an admin is looking at and the field the client renders share one name.
        "joinedAt": _iso(row.joinedAt),
        "requestedAt": _iso(row.requestedAt),
        "attemptCount": row.attemptCount,
        "lastAttemptAt": _iso(row.lastAttemptAt),
        "decidedAt": _iso(row.decidedAt),
        "decidedById": row.decidedById,
        "firstSeenAt": _iso(row.firstSeenAt),
        "notes": row.notes,
        "createdAt": _iso(row.createdAt),
        "updatedAt": _iso(row.updatedAt),
        "addedById": row.addedById,
    }


def _iso(value: Any) -> str | None:
    return value.isoformat() if isinstance(value, datetime) else None
