"""THE PLATFORM ALLOW-LIST: who may sign in at all, and the queue of people asking to.

``AccessRoster`` answers one question — *may this email address sign in?* — and it answers it for
EVERY account except the master admin's. That is the whole feature: before this module existed,
``POST /api/auth/login`` minted a token for anybody who could prove an identity, and the Google
branch went further and CREATED an account for any verified Google address on earth.

**READ THIS BEFORE PORTING IT.** The field-repository application (``com.fieldrepository.app``)
needs the same gate and has no equivalent of ``DesignerRoster`` to build it on, so this module is
written to be copied. What a porter must change, and nothing else:

* :func:`normalise_email` is imported from ``app.services.designers`` here because that repository
  already had one canonical lower-caser and two would be one too many. In an application without a
  designer roster, inline it — three lines, and the rule it enforces is in its docstring.
* :func:`designer_empanelment_admits` is the ONE clause in this file that is specific to this
  application: an ACTIVE ``DesignerRoster`` row is accepted as an admission so that an admin who
  empanels a designer has not silently created somebody the gate then refuses. An application with
  no second allow-list deletes that function and its one call site in ``auth.py``.
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

# One lower-caser for the whole repository. See the module docstring for what a porter does with it.
from app.services.designers import normalise_email

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


async def access_row(email: Any) -> Any | None:
    """The allow-list row for an address, or None if this address has never been seen.

    NONE IS NOT "ADMITTED". Every caller treats a missing row as a refusal — that is what makes the
    gate fail CLOSED for an account created by some path that forgot to admit it, and it is why the
    grandfathering in the migration had to be exhaustive rather than merely thorough.
    """
    address = normalise_email(email)
    if not address or len(address) > MAX_EMAIL_LENGTH:
        return None
    return await db.accessroster.find_unique(where={"email": address})


async def designer_empanelment_admits(email: Any) -> bool:
    """Is this address on the DESIGNER roster as an ACTIVE empanelment?

    THE ONE APPLICATION-SPECIFIC CLAUSE IN THIS FILE. ``DesignerRoster`` predates the allow-list and
    means something stronger: an admin has empanelled this person as a designer, possibly before
    they have an account at all. Refusing them here would mean an admin who did everything the
    product asked has produced somebody the sign-in page turns away, with the remedy being to
    approve the same person a second time in a second screen — and the admin has no way to know
    that, because the roster screen shows the row they added, active, admitting nobody.

    Read only when the allow-list has already declined, so the ordinary path costs no extra query.
    """
    address = normalise_email(email)
    if not address:
        return False
    row = await db.designerroster.find_first(where={"email": address, "isActive": True})
    return row is not None


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
    """
    address = normalise_email(email)
    if not address:
        raise ValueError("an allow-list row needs an email address")
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

    existing = await db.accessroster.find_unique(where={"email": address})
    if existing is None:
        return await db.accessroster.create(data={**grant, "email": address, "addedById": actor_id})
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
    """
    old = normalise_email(old_email)
    new = normalise_email(new_email)
    if not new or old == new:
        return
    existing_new = await db.accessroster.find_unique(where={"email": new})
    if existing_new is not None:
        await admit(
            new,
            actor_id=actor_id,
            note="Admitted when an administrator moved an existing account to this address.",
        )
        return
    old_row = await db.accessroster.find_unique(where={"email": old}) if old else None
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
    """
    address = normalise_email(email)
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
