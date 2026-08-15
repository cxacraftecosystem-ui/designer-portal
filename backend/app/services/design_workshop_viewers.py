"""Who, besides the person who pressed "create", may open one design & prototype workshop.

THE FAILURE THIS ENDS. ``load_workshop_or_404`` admitted ``createdById`` and admins and nobody
else, so a design workshop — a 22-stage record holding a fortnight of fieldwork — was visible to
exactly one account. A real Design & Prototype Development Workshop is run by two designers
alongside a master craftsperson and a reviewing officer, all of whom have to read the same stages;
stage 1 captures ``designerName`` as free TEXT while access was decided solely by who happened to
press the button. The second designer could not open the workshop at all, and when a designer left
mid-season there was no handover short of an admin editing the database by hand.

**WHAT A GRANT CONFERS, STATED ONCE, HERE.** A viewer row carries READ and STAGE WRITES. It does
NOT carry DELETE and it does NOT carry RE-GRANTING. A co-designer is in the workshop to do
fieldwork, not to destroy the record and not to hand out access; both of those stay with the admins
and the creator, exactly where they were. The two refusals are enforced by the routes that already
own them — ``assert_can_delete`` on the delete path, ``require_admin`` on every route in
``api/routes/design_workshop_viewers.py`` — so widening the LOAD is what widens read and writes, and
nothing here widens the other two. See ``tests/test_design_workshop_viewers.py``.

**ADMINISTRATION IS ADMIN-ONLY, INCLUDING FOR THE CREATOR**, and that is the rule here most likely
to be argued with. Letting the owner choose their own readers sounds reasonable right up to the
moment the owner leaves: their workshop's access then freezes in whatever state they left it, which
is the handover problem this module exists to solve, reintroduced one level up. An admin's grant has
an administrator behind it who is still here.

**WHAT WAS BORROWED FROM ``WorkshopAssignment``, AND WHAT DELIBERATELY WAS NOT.** The shape is the
same and intentionally so: one row per (record, user), admin-only administration, and a whole-set
PUT that replaces the roster so that removing somebody is sending the list without them. The
request/approve LIFECYCLE is not borrowed — there is no ``status``, no ``requestedById``, no
``decidedAt``. That vocabulary exists on the sibling table because a researcher may ASK for a
workshop and be refused, and DENIED/REVOKED rows are kept so that a refusal cannot be quietly
re-requested around. Nothing asks for a design workshop: an admin decides who is on the team. Adding
states nobody can enter would leave those columns permanently equal to ``GRANTED`` and invite the
next reader to go looking for the request queue that feeds them.

Removing a viewer therefore DELETES the row rather than revoking it — the one place this departs
from the sibling table's "nothing is ever deleted". A grant here carries no decision to audit: it
never refused anybody and was never asked for, so a tombstone would record only that an admin
changed their mind about a colleague.

**ELIGIBILITY IS A SET, NOT A RANK, AND A SUSPENDED DESIGNER IS THE TRAP.** ``DESIGN_WORKSHOP_ROLES``
is Designer/Admin/Master Admin — a PROFESSOR cannot run a workshop despite outranking a designer —
and on top of that a DESIGNER whose ``DesignerRoster`` row is missing or suspended cannot sign in at
all (see ``services/designers.roster_allows``). Offering such an account in the picker would let an
admin grant access that the next sign-in refuses: the admin's screen would say the designer is in,
the designer would see a refusal, and nothing anywhere would connect the two. So they are excluded
from the offer AND refused by the write, because a picker is a suggestion and the write is the rule.
"""

import logging
import re
from typing import Any

from fastapi import HTTPException, status

from app.core.db import db
from app.core.deps import DESIGN_WORKSHOP_ROLES
from app.services.designers import normalise_email
from app.services.records import contains

logger = logging.getLogger(__name__)

#: How many accounts the picker will offer in one call.
#:
#: A CEILING, NOT A PAGE SIZE. It exists because an unbounded ``find_many`` on a table that only
#: ever grows is how a picker that worked for two years starts timing out.
#:
#: **IT IS REACHED, AND THE ASSUMPTION THAT IT WOULD NOT BE WAS WRONG.** This note used to read "a
#: few dozen accounts in a real deployment … deliberately far above anything an institution will
#: reach". Measured: 1344 admins and 1282 accounts the roster admits, so the eligible set passed
#: 2000 and the cut fell in the middle of the alphabet. Ordered by name and truncated, every
#: eligible account sorting past the cut was simply absent from both clients, and an admin looking
#: for a colleague could not tell that from the colleague never having been empanelled. Those two
#: states must never look identical.
#:
#: So the ceiling stays and the two things it was missing are now here: ``search`` reaches accounts
#: beyond it (see :func:`eligible_viewers`) and ``truncated`` says on the wire that the list was
#: cut. Raising this number would only move the cut — it would ship 10000 rows to a handset and
#: still hide whoever sorts 10001st. The ceiling was never the defect; having no way past it was.
ELIGIBLE_VIEWER_LIMIT = 2000

#: How many active roster rows :func:`active_roster_emails` will read.
#:
#: **A DIFFERENT QUANTITY FROM ``ELIGIBLE_VIEWER_LIMIT``, and sharing that constant was a latent
#: defect rather than a tidy reuse.** The roster read is not a page of results being shown to
#: anybody: its emails are folded into the user query's ``WHERE``, so a roster row that falls off
#: the end does not truncate a list — it makes an eligible designer VANISH from the picker, as
#: though they had never been empanelled. At 1282 active rows the shared 2000 was not being hit, so
#: nothing was wrong yet and nothing would have said so: that read carried no warning at all.
#:
#: This is therefore a backstop against an unbounded read and not a working limit, set far above
#: any plausible roster — a national programme that empanels this many designers has outgrown
#: folding their addresses into one ``IN`` list, which is the honest thing for the log to say. It is
#: NOT left uncapped: an uncapped read of a table that only grows has no failure signal at all, it
#: merely gets slower until something times out. Hitting this is logged at ERROR and reported on the
#: wire as ``truncated``, because a picker missing eligible people is exactly what that flag means.
ACTIVE_ROSTER_READ_LIMIT = 50_000


def _role(user: Any) -> str:
    """The role as a plain string, whether Prisma handed back an enum or a str."""
    value = getattr(user, "role", None)
    return str(getattr(value, "value", value) or "")


# --------------------------------------------------------------------------------------
# Reading: the two questions the enforcement asks
# --------------------------------------------------------------------------------------


async def has_viewer_grant(workshop_id: str, user_id: str) -> bool:
    """May this account open this workshop on the strength of a grant?

    A primary-key lookup, not a scan: ``@@id([designWorkshopId, userId])`` is exactly this
    question, which is why the join table has no synthetic id. Called on every read of a design
    workshop by somebody who is neither its creator nor an admin, so it has to stay one indexed
    hit — and it is only ever reached AFTER those two cheaper checks have failed.
    """
    if not workshop_id or not user_id:
        return False
    row = await db.designworkshopviewer.find_unique(
        where={"designWorkshopId_userId": {"designWorkshopId": workshop_id, "userId": user_id}}
    )
    return row is not None


def visible_to_clause(user_id: str) -> dict[str, Any]:
    """The list endpoint's scope: workshops this account created, OR was let into.

    **MUST be AND-composed, never assigned to ``where["OR"]``.** The search box already builds an
    ``OR`` on that key, so writing this one there too is two assignments to the same dict entry and
    the later one silently wins — which is either a search that stops narrowing or a grant that
    vanishes the moment somebody types. ``services/records.owned_or_granted_where`` carries the
    same warning for the same reason; the caller nests this under ``where["AND"]``.
    """
    return {
        "OR": [
            {"createdById": user_id},
            {"viewers": {"some": {"userId": user_id}}},
        ]
    }


# --------------------------------------------------------------------------------------
# Reading: the two lists the admin screen renders
# --------------------------------------------------------------------------------------


def viewer_payload(row: Any) -> dict[str, Any]:
    """One viewer row as the admin screen reads it.

    ``name``/``email``/``role`` travel WITH the row rather than being joined against a directory
    the screen also holds. A viewer whose account has since dropped off the eligible list — a
    designer suspended last month — is precisely the row an admin most needs to see and act on, and
    a join against the eligible list would render it as a bare cuid.
    """
    user = getattr(row, "user", None)
    return {
        "userId": row.userId,
        "name": getattr(user, "name", "") or "",
        "email": getattr(user, "email", "") or "",
        "role": _role(user),
        "grantedAt": row.createdAt.isoformat() if getattr(row, "createdAt", None) else None,
    }


async def viewer_rows(workshop_id: str) -> list[dict[str, Any]]:
    """Every account with a viewer row on this workshop, oldest grant first.

    The creator is NOT in here and must not be added: they hold the workshop through
    ``createdById``, a different clause entirely. An empty list therefore means "nobody but the
    creator", never "nobody at all", and any screen over it has to say so.
    """
    rows = await db.designworkshopviewer.find_many(
        where={"designWorkshopId": workshop_id},
        include={"user": True},
        order={"createdAt": "asc"},
    )
    return [viewer_payload(row) for row in rows]


async def eligible_viewers(search: str | None = None) -> dict[str, Any]:
    """The accounts that may be given a viewer row at all — see the module docstring.

    Four fields and no more. The caller is choosing a reader and has no business receiving the
    capability flags or the auth provider that ``serialize_user`` would hand it.

    THE ROSTER IS READ FIRST AND FOLDED INTO THE WHERE, rather than fetching users and filtering
    them afterwards, and the difference is not a micro-optimisation. Filtering after the ``take``
    applies the limit to the WRONG set: the query returns the first N accounts of the eligible
    ROLES, the suspended designers among them are then dropped, and the answer is some arbitrary
    number below N with eligible people beyond the cut never considered at all. An admin would see
    a picker missing colleagues who are perfectly eligible, with the number of them varying by how
    many suspended designers happened to sort early. Folding the roster in means the cap applies to
    accounts that are already eligible, so it can only ever truncate a genuinely long tail.

    **``search`` IS FOLDED INTO THE SAME WHERE, FOR THE SAME REASON, AND THE ARGUMENT ABOVE APPLIES
    TO IT WITH THE FORCE OF A PROOF.** Searching after the ``take`` would search the first 2000
    names of the alphabet and nothing else, so the parameter added to reach past the ceiling would
    stop at exactly the ceiling — typing a late-sorting colleague's surname would return nothing
    while the account sat eligible in the table. That is the bug this function is being fixed for,
    reproduced one layer up and harder to see, because an empty result reads as "no such person".

    It matches ``name`` OR ``email``, case-insensitively: an admin hunting for a colleague knows one
    or the other and should not have to guess which field the picker indexes. Through
    ``records.contains``, which strips the control bytes a ``text`` comparison cannot hold — the
    same class of failure ``_UNSTORABLE_IN_AN_ID`` closes below, and ``?search=%00`` would otherwise
    be a 500 from a query parameter.

    **THE TWO ``OR``S ARE AND-COMPOSED, NEVER ASSIGNED TO THE SAME KEY** — the collision
    :func:`visible_to_clause` warns about, and the consequence here is worse than a widened list.
    Eligibility is an ``OR`` and so is the search; writing both to ``where["OR"]`` lets the later
    one silently win, and if that is the search then the roles clause is GONE — the picker would
    offer researchers, professors and suspended designers, which is a grant the next sign-in
    refuses. A widened picker on this screen hands somebody a fortnight of another team's fieldwork.

    Answers ``{"users": [...], "truncated": bool}``. ``truncated`` is the field the old code could
    not write — its warning said outright that the wire had nowhere to say "there are more", which
    is why a cut list was only ever visible in a log nobody reads. It is exact rather than guessed:
    ``take`` is one more row than is returned, so a set of exactly ``ELIGIBLE_VIEWER_LIMIT`` reports
    ``False`` honestly instead of crying truncation, and no second ``COUNT`` is paid — the same
    trick, for the same reason, as the reference picker in ``services/design_workshops``, whose
    ``truncated`` this deliberately matches in name so both clients already know the word.
    """
    admitted, roster_truncated = await active_roster_emails()

    clauses: list[dict[str, Any]] = [
        {
            "OR": [
                # Admins are not roster-gated at all — deliberately, and for the same reason
                # ``roster_allows`` is not consulted for them at sign-in: an admin empanelled years
                # ago and later suspended must not lose the ability to administer anything.
                {"role": {"in": ["ADMIN", "MASTER_ADMIN"]}},
                # ``mode: "insensitive"`` because ``admitted`` is lower-cased and ``User.email`` is
                # NOT — see :func:`active_roster_emails`. Without it this comparison hides an
                # eligible designer whose address happens to be stored shouting, while
                # ``_designers_the_roster_still_admits`` on the write path normalises both sides and
                # accepts them: the picker would refuse to offer an account the PUT would take,
                # which is this module's own defect (absent reads as ineligible) one field along.
                {"AND": [{"role": "DESIGNER"}, {"email": {"in": admitted, "mode": "insensitive"}}]},
            ]
        }
    ]
    term = (search or "").strip()
    if term:
        clauses.append({"OR": [{"name": contains(term)}, {"email": contains(term)}]})

    users = await db.user.find_many(
        where={"AND": clauses},
        # ORDERED BY NAME, AND THEN BY ID SO THE ORDER IS TOTAL. Both halves are load-bearing.
        #
        # The name sort is what makes a picker readable and it is what both clients rely on:
        # ``android/…/data/DesignWorkshopViewers.dwViewerChoices`` deliberately does NOT re-sort,
        # because Kotlin's ``sortedBy`` compares UTF-16 code units and disagrees with Postgres's
        # collation, so the server's order is the only order either screen has.
        #
        # The id is the TIEBREAKER, and without it a name is not a unique sort key: this table holds
        # hundreds of accounts sharing one display name, so which of them fall inside
        # ``ELIGIBLE_VIEWER_LIMIT`` would be Postgres's choice and could differ between two identical
        # requests. That turns "who is hidden" into something that changes on refresh — the same
        # invisible-colleague failure this function was fixed for, in a form no search term can be
        # relied on to reach. ``tests/test_design_workshop_viewers`` pins both halves.
        order=[{"name": "asc"}, {"id": "asc"}],
        take=ELIGIBLE_VIEWER_LIMIT + 1,
    )
    truncated = len(users) > ELIGIBLE_VIEWER_LIMIT
    users = users[:ELIGIBLE_VIEWER_LIMIT]
    if truncated:
        # Still logged as well as reported. The log is what an operator reads when an admin says
        # "I cannot find her" — it names the term that was too broad, which the response cannot.
        logger.warning(
            "eligible-viewers hit its ceiling of %s accounts (search=%r); the answer is truncated "
            "and says so, and the caller can narrow it",
            ELIGIBLE_VIEWER_LIMIT,
            term,
        )
    return {
        "users": [{"id": u.id, "name": u.name, "email": u.email, "role": _role(u)} for u in users],
        # OR-ed, not overwritten: a cut roster means eligible DESIGNERs are missing from this answer
        # even when the list itself is short, which is precisely what this flag tells the client.
        "truncated": truncated or roster_truncated,
    }


async def active_roster_emails() -> tuple[list[str], bool]:
    """Every lower-cased email the roster currently admits, and whether that read was cut short.

    PUBLIC, AND NAMED WITHOUT THE UNDERSCORE IT CARRIED, because it has a second caller now:
    ``routes/designers.designer_directory`` folds the same admitted set into its own user query.
    That endpoint used to read 500 users and drop the suspended ones in Python afterwards, which
    spends the cap on rows it then discards — the same "filter after the take" defect this
    function exists to keep out of ``eligible_viewers``. One implementation of "who does the
    roster still admit", read the same way by both, is the point; a second copy would be free to
    forget the lower-casing or the cap and nothing would say so.

    The whole active roster, because it IS the small table here — one row per empanelled designer —
    whereas ``User`` holds every account the repository has ever had. Reading it in full is one
    query and lets the eligibility rule become part of the user query's WHERE rather than a pass
    over its results; see :func:`eligible_viewers` for why that ordering matters.

    **WHY THIS RETURNS A FLAG, AND WHY THE CAP IS ITS OWN NUMBER.** This read used to borrow
    ``ELIGIBLE_VIEWER_LIMIT`` and say nothing when it hit it — the picker's page size used as a
    roster read cap, two different quantities that happened to share a constant. The failure that
    hides behind that is silent in a way the picker's is not: these emails go into the user query's
    ``WHERE``, so a dropped roster row does not shorten a list, it removes an eligible designer from
    it with no log line anywhere and no test failing. See ``ACTIVE_ROSTER_READ_LIMIT``. The flag
    goes back to the caller because that removal is invisible from the outside, and the one honest
    thing to do with it is admit on the wire that the answer is incomplete.
    """
    rows = await db.designerroster.find_many(
        where={"isActive": True}, take=ACTIVE_ROSTER_READ_LIMIT + 1
    )
    truncated = len(rows) > ACTIVE_ROSTER_READ_LIMIT
    if truncated:
        rows = rows[:ACTIVE_ROSTER_READ_LIMIT]
        # ERROR, not warning, and louder than the picker's: the picker's truncation is a long list
        # the caller can narrow, this one is eligible designers absent from every search the caller
        # can type. It cannot be worked around from the client side.
        # Named for the ROSTER READ and not for one of its callers. It said "eligible-viewers read
        # only part of it" when the picker was the only caller; ``routes/designers`` now folds the
        # same set into the directory query, and a log line naming the wrong endpoint sends whoever
        # is holding the page at 3am to read the wrong module.
        logger.error(
            "the active designer roster exceeds %s rows, so only part of it was read; designers "
            "past that cut are missing from the viewer picker AND the designer directory, for "
            "every search either of them can make",
            ACTIVE_ROSTER_READ_LIMIT,
        )
    return sorted({normalise_email(row.email) for row in rows}), truncated


async def _designers_the_roster_still_admits(users: list[Any]) -> set[str]:
    """The lower-cased emails of the DESIGNERs among ``users`` who can actually sign in.

    ONE query for the whole batch rather than one per designer. Used by the WRITE path, where the
    set of ids is small and already known, so asking about exactly those emails is cheaper than
    reading the whole roster.

    Admins are not looked up at all, deliberately — the roster gates designers only, and an admin
    who was empanelled years ago and later suspended must not lose the ability to administer
    anything (see the same rule in ``tests/test_designer_roster``).
    """
    emails = [normalise_email(u.email) for u in users if _role(u) == "DESIGNER"]
    if not emails:
        return set()
    rows = await db.designerroster.find_many(where={"email": {"in": emails}, "isActive": True})
    return {normalise_email(row.email) for row in rows}


# --------------------------------------------------------------------------------------
# Writing: validate everything, then replace the whole set
# --------------------------------------------------------------------------------------


async def replace_viewers(
    workshop_id: str, user_ids: list[str], *, creator_id: str, granted_by_id: str
) -> list[dict[str, Any]]:
    """Make the viewer set exactly ``user_ids``, and answer with it.

    VALIDATION RUNS TO COMPLETION BEFORE ANY WRITE. One bad id refuses the whole call rather than
    applying the good half: an admin who ticked four designers and is shown three has been told
    nothing about which one failed or why, and a partially applied access change is the worst of
    both — it looks like it worked.

    Idempotent by construction. Only the difference is written, so re-saving an unchanged screen
    touches no rows and does not restamp ``createdAt`` — which matters because ``grantedAt`` is the
    only answer anybody has to "how long has this person been on this workshop".
    """
    wanted = _deduplicate(user_ids, creator_id)
    await _assert_every_id_may_be_granted(wanted)

    existing = await db.designworkshopviewer.find_many(where={"designWorkshopId": workshop_id})
    held = {row.userId for row in existing}

    removed = sorted(held - wanted)
    added = sorted(wanted - held)

    if removed:
        # DELETED, not revoked — see the module docstring. There is no decision here to audit.
        await db.designworkshopviewer.delete_many(
            where={"designWorkshopId": workshop_id, "userId": {"in": removed}}
        )
    if added:
        await db.designworkshopviewer.create_many(
            data=[
                {"designWorkshopId": workshop_id, "userId": uid, "grantedById": granted_by_id}
                for uid in added
            ],
            # Two admins saving the same screen at the same moment must not turn into a 500 on a
            # duplicate key. The pair is the primary key, so "already granted" is the intended
            # outcome of this call anyway.
            skip_duplicates=True,
        )
    return await viewer_rows(workshop_id)


def _deduplicate(user_ids: list[str], creator_id: str) -> set[str]:
    """The intended set, with blanks and the creator dropped.

    THE CREATOR IS A NO-OP RATHER THAN AN ERROR, and is dropped HERE — before validation — on
    purpose. Their access comes from ``createdById``, so a row for them would be a second,
    redundant source of truth for access they already hold, and one an admin could "remove" from
    the screen without anything changing. Dropping them before the eligibility check also means a
    creator who has since been suspended from the roster cannot make the whole save 422: their
    standing is simply not this list's business.

    A screen that renders the creator alongside the viewers and posts the lot back is the obvious
    client to write, so this has to be harmless rather than merely documented.
    """
    return {uid.strip() for uid in user_ids if uid and uid.strip()} - {creator_id}


#: Characters that cannot reach Postgres inside an id, and that no id this repository issues holds.
#:
#: NUL is refused by a ``text`` comparison outright (SQLSTATE 22021, "invalid byte sequence for
#: encoding UTF8"), and a LONE SURROGATE — half an emoji from a phone that truncated it — cannot be
#: encoded to UTF-8 at all, so it fails inside the driver before Postgres is even reached. Either one
#: turned the ``find_many`` below into a bare 500 whose body names the exception class
#: (``DataError`` / ``UnicodeEncodeError``) and whose log carries a stack trace for every attempt —
#: where the honest answer is the "no account exists with this id" this function already gives to
#: every other id that cannot match.
#:
#: ``services/records.plain`` STRIPS these same characters rather than refusing, and the difference
#: is deliberate: that is a filter, where a researcher who pasted a stray control character out of a
#: PDF wants their search to run. This is a WRITE naming accounts, and this module's rule is that one
#: bad id refuses the whole call and says which one.
_UNSTORABLE_IN_AN_ID = re.compile(r"[\x00-\x1f\x7f\ud800-\udfff]")


def _displayable(user_id: str) -> str:
    """An id safe to put in a refusal message, and in whatever reads the log after it.

    Only ever applied to an id that is ALREADY being refused, so nothing downstream depends on it
    round-tripping; what it prevents is a raw NUL or half a surrogate pair travelling out in the
    response body and into the operator's log on its way.
    """
    return _UNSTORABLE_IN_AN_ID.sub("", user_id)


async def _assert_every_id_may_be_granted(user_ids: set[str]) -> None:
    """422 naming the offending account, never a silent skip. See the module docstring."""
    if not user_ids:
        return

    # ASKED BEFORE THE QUERY, because these ids are what makes the query itself fail — see
    # ``_UNSTORABLE_IN_AN_ID``. Holding them back is not a silent skip: they cannot appear in
    # ``by_id``, so they fall into the same "no account exists" refusal as any other id with nothing
    # behind it, through one message and one code path.
    lookup = sorted(uid for uid in user_ids if not _UNSTORABLE_IN_AN_ID.search(uid))
    users = await db.user.find_many(where={"id": {"in": lookup}}) if lookup else []
    by_id = {u.id: u for u in users}

    unknown = sorted(_displayable(uid) for uid in user_ids if uid not in by_id)
    if unknown:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "No account exists with "
                + ("these ids: " if len(unknown) > 1 else "this id: ")
                + ", ".join(unknown)
                + ". Nothing was changed."
            ),
        )

    allowed = await _designers_the_roster_still_admits(users)
    refusals: list[str] = []
    for uid in sorted(user_ids):
        user = by_id[uid]
        role = _role(user)
        if role not in DESIGN_WORKSHOP_ROLES:
            refusals.append(
                f"{user.name} ({user.email}) is a {role} and cannot run a design & prototype "
                f"workshop, so a viewer row would give them access their account refuses."
            )
        elif role == "DESIGNER" and normalise_email(user.email) not in allowed:
            refusals.append(
                f"{user.name} ({user.email}) is not on the ACTIVE designer roster, so they cannot "
                f"sign in at all. Restore their roster entry first; a viewer row on its own would "
                f"leave this screen saying they have access while they are shown a refusal."
            )
    if refusals:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=" ".join([*refusals, "Nothing was changed."]),
        )
