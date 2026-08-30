"""The designer roster, the designer profile, and the picker an admin assigns a workshop from.

THREE FAMILIES, AND THE FIRST TWO ANSWER TO DIFFERENT PEOPLE.

* ``/designers/roster`` — ADMIN ONLY, read and write. The empanelment list that gates sign-in
  for anyone holding the DESIGNER role. See ``app/services/designers.py`` for why this is a
  separate fact from ``User.role``, and ``app/api/routes/auth.py`` for the gate itself.
* ``/designers/me/profile`` and ``/designers/{user_id}/profile`` — the designer's own standing
  details, written by the owner or by an admin and by nobody else. This is the text that gets
  printed in a report, so a designer able to edit a colleague's copy could put words into a
  document submitted to a ministry under that colleague's name.
* ``/designers/directory`` — who an admin may hand a workshop to.

**DELETE ON THE ROSTER IS A SUSPENSION.** It sets ``isActive=false`` and stamps ``revokedAt``;
it never removes the row. The roster is the RECORD that somebody was empanelled, and that record
outlives their access to the app: an audit two years later asks who was recognised under which
programme, and a deleted row answers "nobody". Restoring is ``PATCH`` with ``isActive: true``,
which is also why a DELETE here answers 200 with the suspended row rather than 204 with nothing —
there is something left to show.

**ROUTE ORDER IS LOAD-BEARING.** ``/me/profile`` and ``/directory`` are declared before
``/{user_id}/profile``. FastAPI matches in declaration order, so the other way round the literal
paths would be swallowed by the parameterised one and ``GET /designers/me/profile`` would look up
a user whose id is the string "me" and 404 forever.
"""

import asyncio
import logging
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import (
    DESIGN_WORKSHOP_ROLES,
    ROLE_RANK,
    is_admin,
    require_designer,
    require_designer_roster_manager,
    role_value,
)
from app.schemas.designers import (
    DesignerProfileUpdate,
    DesignerRosterCreate,
    DesignerRosterUpdate,
)
from app.services.design_workshop_viewers import active_roster_emails
from app.services.designers import (
    canonical_email,
    email_match_keys,
    get_or_create_profile,
    normalise_email,
    profile_payload,
    roster_payload,
    update_profile,
)
from app.services.pagination import normalize_pagination, page_payload
from app.services.record_filters import enum_filter_list_or_422
from app.services.records import (
    add_date_range,
    attach_location,
    contains,
    count_and_page,
    enum_filter_or_422,
)

router = APIRouter(prefix="/designers", tags=["designers"])

logger = logging.getLogger(__name__)

# The roles that may run a design workshop, and therefore the accounts this directory offers.
#
# IMPORTED FROM THE ONE SET, NEVER RE-DERIVED FROM THE RANK LADDER. This list used to read
# ``[role for role, rank in ROLE_RANK.items() if rank >= ROLE_RANK["DESIGNER"]]`` under a comment
# arguing that deriving it meant "a tier inserted above DESIGNER next year does not quietly drop
# out of every admin's picker". That argument is exactly backwards for this one capability, and
# the ladder already contains the counter-example: PROFESSOR is 40 and DESIGNER is 35, so the
# derivation returned ['DESIGNER', 'PROFESSOR', 'ADMIN', 'MASTER_ADMIN'] and this endpoint listed
# professors as workshop-capable while ``design_workshop_viewers`` refuses a professor's viewer
# grant with an all-or-nothing 422 that discards the whole PUT body.
#
# ``can_run_design_workshops`` is THE ONE PREDICATE IN deps.py THAT IS A SET RATHER THAN A RANK
# THRESHOLD — read its docstring for why a professor outranking a designer is not the same thing
# as being one. A second, rank-derived copy of a non-monotonic rule is not a copy at all; it is a
# different rule that agrees today only by accident. If professors are ever admitted, change
# ``DESIGN_WORKSHOP_ROLES`` and every surface follows; do NOT reintroduce a threshold here.
#
# ``sorted`` because a frozenset has no order and this goes into a query's ``IN`` list — a stable
# order keeps the emitted SQL identical between processes, which is what makes a query plan and a
# test assertion reproducible.
WORKSHOP_CAPABLE_ROLES = sorted(DESIGN_WORKSHOP_ROLES)

#: How many directory rows one request will read. Named rather than inlined because two clients
#: hard-code the same number to decide whether the answer was cut — ``DIRECTORY_CAP`` in
#: ``frontend/app/(protected)/admin/designers/page.tsx`` and ``DESIGNER_DIRECTORY_CAP`` in
#: ``android/…/DesignerRosterScreen.kt`` — and a length inference is only sound while every filter
#: this endpoint applies is inside the query. See :func:`designer_directory`.
DIRECTORY_TAKE = 500

# Identical text for "you may not see this profile" and "no such user", because they are answered
# with the same status and must not be told apart. See :func:`_assert_may_touch_profile`.
_PROFILE_NOT_FOUND = "Record not found"


# --------------------------------------------------------------------------------------
# The roster's filter and sort grammar — requirement 30, DROPDOWN_DESIGN section 4.
#
# Matched, deliberately, to ``access.list_access_roster``'s naming (swap ``ACCESS_`` for
# ``DESIGNER_``) so a reader who has already puzzled out one roster's grammar recognises the other
# on sight rather than re-deriving it. Where the two rosters differ, the constant below says so and
# says why; where they agree the docstring points at the sibling rather than repeating it.
# --------------------------------------------------------------------------------------

#: The two standings ``standing`` may name. Single-valued and checked with
#: :func:`app.services.records.enum_filter_or_422` rather than compared by hand, so an unrecognised
#: spelling — ``Active``, ``ACTIVE``, ``revoked`` — is a 422 naming the two real values instead of a
#: filter that silently matches nothing. There is no multi-select here the way there is on
#: ``roles``/``institutions``/the allow-list's ``status``: a roster row is either active or it is
#: not, so ticking "both" has nothing to mean beyond not filtering at all, which absence already
#: spells.
DESIGNER_STANDING_TOKENS: frozenset[str] = frozenset({"active", "suspended"})

#: The reserved ``roles`` token meaning "this email has never signed in" — ``firstSeenAt IS NULL``.
#:
#: NAMED ``never-signed-in`` AND NOT ``no-account``, and the difference is the whole point
#: (DROPDOWN_DESIGN §4.4). "Has no account" is not a fact ``DesignerRoster`` stores or could answer
#: without an unbounded ``NOT IN`` over every account the repository has ever had, and it would
#: still be WRONG for a provisioned account that has simply never opened the app. ``firstSeenAt`` is
#: the one column this screen exists to show — "so an admin can see which invitations are
#: outstanding rather than guessing" (``schema.prisma`` on ``DesignerRoster.firstSeenAt``) — so the
#: reserved token asks the question this table can actually answer.
ROLE_NEVER_SIGNED_IN = "never-signed-in"

#: The eight-tier role ladder plus :data:`ROLE_NEVER_SIGNED_IN`. Built from ``ROLE_RANK`` rather than
#: written out a second time — matching ``access.ACCESS_ROLE_FILTER_TOKENS`` — so a tier inserted
#: into or removed from the ladder changes this filter's vocabulary for free and the two roster
#: routes cannot quietly disagree about how many tiers exist. Pinned against drift by the same
#: ``tests/test_role_ladder_parity.py`` sweep that pins the allow-list's copy.
DESIGNER_ROLE_FILTER_TOKENS: frozenset[str] = frozenset(ROLE_RANK) | {ROLE_NEVER_SIGNED_IN}

#: How many ``User`` rows the role filter's FIRST query — see :func:`list_roster` — will read before
#: it gives up and reports the cut.
#:
#: A DIFFERENT QUANTITY FROM A PAGE SIZE, for ``design_workshop_viewers``'s
#: ``ACTIVE_ROSTER_READ_LIMIT`` reason. ``DesignerRoster`` carries no role column and no relation to
#: ``User`` at all — its only ``User`` relation is ``addedBy``, the ADMIN who added the row, never
#: the designer the row is ABOUT (``schema.prisma`` on ``DesignerRoster``) — so "filter the roster by
#: tier" can only be answered by first resolving which accounts hold the requested roles and folding
#: THEIR emails into the roster query's ``WHERE``. An account that falls off the end of THAT read
#: does not shorten a page the way a page-size cap would: it makes a matching DESIGNER VANISH from
#: EVERY page of this filter, silently, exactly as though they had never been empanelled at all. A
#: backstop against an unbounded read, not a working limit — hitting it is logged at ERROR (louder
#: than a merely-long list gets) and reported on the wire as ``roleMatchTruncated``.
#:
#: MUST MATCH ``android/…/RosterWire.kt``'s ``ROLE_MATCH_READ_LIMIT`` BYTE FOR BYTE. That client
#: prints this exact number in the sentence it shows an admin when the flag is set; a value that
#: drifted between the two would put a wrong figure on screen about a cut the server actually made.
ROLE_MATCH_READ_LIMIT = 50_000

#: ``dateField`` -> the column it selects. THREE COLUMNS, NOT THE ALLOW-LIST'S FIVE: this roster has
#: no ``requestedAt`` and no ``decidedAt`` because there is no request here for an admin to decide —
#: empanelling somebody is a single act, not a queue. One range per request, never several stacked at
#: once (DROPDOWN_DESIGN §4.1, "one date range per request, not five"); see
#: ``access.ACCESS_DATE_COLUMNS`` for the fuller reasoning, which applies unchanged.
DESIGNER_DATE_COLUMNS: dict[str, str] = {
    "added": "createdAt",
    "firstSeen": "firstSeenAt",
    "revoked": "revokedAt",
}

#: ``sort`` -> the column it orders by. Kept beside :data:`DESIGNER_SORT_DEFAULT_DIR` — the other
#: half of DROPDOWN_DESIGN §4.3's table — rather than merged into one dict of tuples, for
#: ``access.ACCESS_SORT_COLUMNS``'s reason: "which column" and "which way does it point by default"
#: are answered at two different call sites below and read better kept apart.
DESIGNER_SORT_COLUMNS: dict[str, str] = {
    "added": "createdAt",
    "email": "email",
    "name": "fullName",
    "institution": "institution",
    "firstSeen": "firstSeenAt",
    "revoked": "revokedAt",
}

#: The direction each ``sort`` token points when the caller names ``sort`` and says nothing about
#: ``dir`` — NOT one global default; see ``access.ACCESS_SORT_DEFAULT_DIR``, which this table follows
#: unchanged. ``firstSeen`` defaults to ``desc`` so an OUTSTANDING invitation (``firstSeenAt IS
#: NULL``, which Postgres sorts FIRST on ``desc``) floats to the top by default — exactly what
#: Android's now-deleted device-side sort did on purpose ("who have I added who has not turned up"),
#: now true on every page rather than only whichever page happened to load first. ``email``,
#: ``name`` and ``institution`` default to ``asc`` because there is no "newest" reading of an
#: address, a typed name, or an institution string.
DESIGNER_SORT_DEFAULT_DIR: dict[str, str] = {
    "added": "desc",
    "email": "asc",
    "name": "asc",
    "institution": "asc",
    "firstSeen": "desc",
    "revoked": "desc",
}

#: What ``sort`` means when the caller sends neither ``sort`` nor ``dir`` at all. Matches
#: ``access.ACCESS_DEFAULT_SORT`` and this route's own behaviour before this grammar existed —
#: ``createdAt desc`` was the one order the old hand-rolled query ever built, so a caller that never
#: sends ``sort`` sees no change at all. DROPDOWN_DESIGN §6 Q5 rules that Android's device-side
#: "outstanding invitations first" reordering does NOT become this default: it survives as
#: ``sort=firstSeen&dir=desc`` instead, one tap away and — unlike the deleted device sort — correct
#: across every page rather than only whichever 500 rows happened to arrive first.
DESIGNER_DEFAULT_SORT = "added"

#: Reserved ``institutions`` token for "rows with no institution at all" — ``institution IS NULL``.
#: The ``record_filters.UNASSIGNED_WORKSHOP`` precedent, restated for this column: without it,
#: ticking every institution the picker offers would silently exclude every row that has none, which
#: is the opposite of what "I ticked everything I can see" should mean. See
#: :func:`_resolve_institutions` for the one way "none" is not perfectly safe on THIS particular
#: column, and why that risk is accepted rather than designed away.
INSTITUTION_NONE = "none"

#: How many distinct institutions ``GET /designers/roster/institutions`` will read before it stops.
#: Read one past this and trim, in the ``GET /tasks/options`` manner (``tasks.py`` — "read one past
#: the cap, so the flag is exact"), so a vocabulary of exactly the cap reports ``truncated: false``
#: honestly and no second ``COUNT(DISTINCT …)`` is ever paid.
INSTITUTION_LIST_CAP = 200


# --------------------------------------------------------------------------------------
# The roster
# --------------------------------------------------------------------------------------


@router.get("/roster")
async def list_roster(
    page: int = Query(1, ge=1),
    pageSize: int = Query(50, ge=1, le=200),
    search: str | None = None,
    activeOnly: bool = False,
    standing: str | None = Query(default=None),
    roles: list[str] | None = Query(default=None),
    institutions: list[str] | None = Query(default=None),
    dateField: str | None = Query(default=None),
    dateFrom: datetime | None = Query(default=None),
    dateTo: datetime | None = Query(default=None),
    sort: str | None = Query(default=None),
    dir: str | None = Query(default=None),
    _: Any = Depends(require_designer_roster_manager),
) -> dict[str, Any]:
    """The empanelment list, suspended rows included by default, with requirement 30's full filter
    and sort grammar — the designer roster's half of DROPDOWN_DESIGN section 4, matched to
    ``access.list_access_roster``'s shape so an admin who has learned one roster screen already knows
    the other, and so a client component built for one envelope works unmodified on both.

    SUSPENDED ROWS ARE SHOWN BY DEFAULT, and that default is the point of the whole screen — the one
    fact this docstring has carried since before requirement 30 and which requirement 30 must not be
    allowed to quietly narrow. An admin arrives here holding a message from a designer who cannot
    sign in, and the row that explains why is exactly the one a tidied-up default would hide: they
    would re-add the address, hit the 409 the unique index raises, and have no explanation visible
    anywhere in the product for what is actually refusing their colleague.

    ``activeOnly`` IS KEPT, BYTE-IDENTICAL, FOR A CLIENT THAT HAS NOT BEEN REBUILT. ``standing`` is
    the new spelling of the same question — ``active`` is what ``activeOnly=true`` always meant, and
    ``suspended`` is the question ``activeOnly`` could never ask on its own — and SENDING BOTH IS A
    422 WHEN THEY DISAGREE, never a silent pick-one (DROPDOWN_DESIGN §4.1). A request naming both,
    and disagreeing, is far more likely a stale query string built from two different filter states
    than a deliberate instruction, and guessing which of the two the caller "really" meant is exactly
    the kind of silent narrowing this whole document exists to rule out. Agreeing combinations
    (``activeOnly=true&standing=active``) are not refused — they ask the same question twice and get
    the same answer.

    ``roles`` FILTERS BY THE LINKED ACCOUNT'S ROLE, THROUGH A SECOND QUERY, BECAUSE THERE IS NO ROLE
    COLUMN TO FILTER (DROPDOWN_DESIGN §4.4). ``DesignerRoster`` carries no role and no user relation
    at all — its only ``User`` relation is ``addedBy``, the ADMIN who added the row, never the
    designer the row is about — so "filter by tier" means resolving which accounts hold the requested
    roles FIRST and folding their emails into THIS query's ``WHERE``, exactly as
    ``design_workshop_viewers.active_roster_emails`` folds the designer roster into the
    eligible-viewers query rather than filtering that query's result. See
    :data:`ROLE_MATCH_READ_LIMIT` for the cap on that first read and ``roleMatchTruncated`` in the
    response for what a caller is told when it is hit — hit, that flag means MATCHING DESIGNERS ARE
    MISSING FROM EVERY PAGE of this filter, silently, which is why it is logged at ERROR rather than
    the WARNING a merely-long list would get. ``GET /designers/directory`` cannot serve this filter in
    this route's place: it filters to :data:`WORKSHOP_CAPABLE_ROLES` only, so a roster row whose
    account is a RESEARCHER or an INSPECTOR is invisible to it, and routing this filter through it
    would answer "no designers hold that tier" about rows that plainly exist.

    THE RESERVED ``roles`` TOKEN IS :data:`ROLE_NEVER_SIGNED_IN`, ANSWERABLE FROM THIS TABLE ALONE.
    "Has no account" is not a fact ``DesignerRoster`` stores and cannot be answered without an
    unbounded ``NOT IN`` over every account the repository has ever had; "has never signed in" is
    exactly ``firstSeenAt IS NULL``, the column this whole screen exists to show. IF NO ROLE FILTER IS
    REQUESTED THE SECOND QUERY NEVER RUNS AT ALL — it is a cost paid by the one filter that needs it,
    never by every page load.

    ``institutions`` IS A MULTI-SELECT OVER A SERVED VOCABULARY, EXACT-MATCHED, NEVER CASE-FOLDED
    (DROPDOWN_DESIGN §4.5). ``DesignerRoster.institution`` is free text an admin typed, not a closed
    enum, so it is deliberately NOT routed through
    :func:`app.services.record_filters.enum_filter_list_or_422` — see :func:`_resolve_institutions`
    for the full reasoning and the one cost that decision accepts. The reserved token
    :data:`INSTITUTION_NONE` covers rows with no institution at all, on the same precedent as
    ``roles``' reserved token above. The vocabulary itself is served by
    :func:`list_roster_institutions` below and is deliberately NOT offered on the allow-list —
    ``AccessRoster`` has no institution column, and a join to this table by email would silently
    narrow that screen to the subset of pending strangers who ALSO happen to be empanelled designers,
    which is precisely the population its own widest default exists to keep visible.

    ONE DATE RANGE PER REQUEST, OVER THREE COLUMNS RATHER THAN THE ALLOW-LIST'S FIVE: this roster has
    no ``requestedAt`` and no ``decidedAt``, because there is no request here for an admin to decide —
    empanelling somebody is a single act, not a queue. See :data:`DESIGNER_DATE_COLUMNS`.

    ``sort``/``dir`` ARE STABLE ACROSS A PAGED WALK BY CONSTRUCTION, THE SAME WAY AND FOR THE SAME
    REASON AS THE ALLOW-LIST: every order this route can build is handed to
    :func:`app.services.records.count_and_page`, which appends the ``id`` tiebreak on the way
    through, so there is no explicit ``with_id_tiebreak`` call in this function to forget — see that
    pair's docstrings for why an ``ORDER BY`` with no unique column silently repeats and skips rows
    across an offset-paged walk, and why that is not hypothetical on this table: ``createdAt`` is
    what this route's own default sorts by, it has no unique index, and nothing stops two rows
    sharing a timestamp to the microsecond. :data:`DESIGNER_SORT_DEFAULT_DIR` is what a caller gets
    when it names ``sort`` and says nothing about ``dir`` — a per-column default, never one global
    setting; see its docstring for ``firstSeen``'s deliberate ``desc``.

    ``roleMatchTruncated`` RIDES THIS ENVELOPE EVEN WHEN IT IS ``False``, MATCHING THE ALLOW-LIST'S
    OWN ENVELOPE EXACTLY (``access.list_access_roster``, which sends the same key always-``False`` for
    the opposite reason: its role filter is a real column and can never truncate, while this route's
    genuinely can). One shared shape means a client component built once can render a cut notice for
    either roster without first working out which one it is looking at.

    EVERY FILTER ABOVE IS ASSEMBLED INTO ``where`` BEFORE THE ONE CALL TO ``count_and_page``, and
    nothing after that call inspects or drops a row. A post-fetch filter would report a page that is
    the right SIZE while having silently discarded whoever an extra clause excluded — a
    complete-looking list that is missing people, the same failure ``designer_directory``'s own
    docstring names for a dropped ``take``, one layer up from a filter instead of a limit.
    """
    where: dict[str, Any] = {}

    # ── STANDING: `activeOnly` untouched, `standing` is the new grammar for the same question ──────
    if standing:
        standing = enum_filter_or_422(standing, DESIGNER_STANDING_TOKENS, field="standing")
    implied_standing = "active" if activeOnly else None
    if standing and implied_standing and standing != implied_standing:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"activeOnly=true and standing={standing!r} disagree about which designers to "
                "include: activeOnly=true means standing='active'. Send only one of the two — a "
                "request naming both, and disagreeing, is almost certainly a stale filter state "
                "rather than a deliberate choice, and guessing which one you meant is worse than "
                "asking."
            ),
        )
    effective_standing = standing or implied_standing
    if effective_standing == "active":
        where["isActive"] = True
    elif effective_standing == "suspended":
        where["isActive"] = False

    if search:
        token = search.strip()
        # ``records.contains``, not a hand-rolled filter: control bytes stripped (a pasted NUL was a
        # bare 500 from this box) and LIKE metacharacters escaped, so pasting a full address to find
        # one designer narrows the list instead of widening it on ``_``.
        where["OR"] = [
            {"email": contains(token)},
            {"fullName": contains(token)},
            {"institution": contains(token)},
        ]
        #  ^ THE ONE `OR` KEY IS TAKEN, by search. Every clause below AND-composes into
        #    ``where["AND"]`` and is never assigned to ``where["OR"]`` directly — two assignments to
        #    one dict key is not a merge, it is the second one silently overwriting the first, which
        #    would make a role or institution filter combined with a search term quietly stop
        #    searching. See ``access.list_access_roster`` for the identical warning.

    # ── ROLES: the two-query shape, copying `active_roster_emails` exactly (DROPDOWN_DESIGN §4.4) ──
    wanted_roles = enum_filter_list_or_422(roles, DESIGNER_ROLE_FILTER_TOKENS, field="roles")
    role_match_truncated = False
    if wanted_roles is not None:
        role_arms: list[dict[str, Any]] = []
        named_roles = sorted(wanted_roles - {ROLE_NEVER_SIGNED_IN})
        if named_roles:
            accounts = await db.user.find_many(
                where={"role": {"in": named_roles}}, take=ROLE_MATCH_READ_LIMIT + 1
            )
            role_match_truncated = len(accounts) > ROLE_MATCH_READ_LIMIT
            if role_match_truncated:
                accounts = accounts[:ROLE_MATCH_READ_LIMIT]
                # ERROR, not warning: this is a role filter silently missing people, which is worse
                # than no filter at all, and it must be loud in the logs even though the API itself
                # degrades gracefully (a truncated-but-honest answer rather than an unbounded read).
                logger.error(
                    "more than %s accounts hold the filtered roles, so only part of that set was "
                    "read; designer roster rows whose account fell past that cut are missing from "
                    "this list for every filter that names those roles",
                    ROLE_MATCH_READ_LIMIT,
                )
            # ``mode: "insensitive"`` because ``DesignerRoster.email`` is lower-cased on write and
            # ``User.email`` is not — the same clause ``designer_directory`` uses below, for the
            # same reason: without it an account stored shouting matches no roster row and the
            # designer silently vanishes from a role-filtered list.
            emails = sorted({normalise_email(a.email) for a in accounts})
            role_arms.append({"email": {"in": emails, "mode": "insensitive"}})
        if ROLE_NEVER_SIGNED_IN in wanted_roles:
            role_arms.append({"firstSeenAt": None})
        # ``wanted_roles`` is a non-empty subset of ``DESIGNER_ROLE_FILTER_TOKENS`` whenever it is
        # not ``None`` (``enum_filter_list_or_422``'s own guarantee), so one of the two branches
        # above always ran and ``role_arms`` is never actually empty here — the fallback is
        # defensive rather than reachable, matching DROPDOWN_DESIGN §4.4's own worked example.
        where.setdefault("AND", []).append({"OR": role_arms or [{"id": {"in": []}}]})

    # ── INSTITUTION: free text, exact match, the reserved `none` token (DROPDOWN_DESIGN §4.5) ──────
    resolved_institutions = _resolve_institutions(institutions)
    if resolved_institutions is not None:
        names, include_none = resolved_institutions
        institution_arms: list[dict[str, Any]] = []
        if names:
            institution_arms.append({"institution": {"in": names}})
        if include_none:
            institution_arms.append({"institution": None})
        where.setdefault("AND", []).append({"OR": institution_arms or [{"id": {"in": []}}]})

    if dateField:
        # A NAMED, VALIDATED COLUMN, not a raw dict subscript: ``DESIGNER_DATE_COLUMNS[dateField]``
        # with no check in front of it would answer an unrecognised token with a Python ``KeyError``
        # — an unhandled 500 — rather than the 422 naming the three valid values.
        column = DESIGNER_DATE_COLUMNS[
            enum_filter_or_422(dateField, frozenset(DESIGNER_DATE_COLUMNS), field="dateField")
        ]
        add_date_range(where, column, dateFrom, dateTo)

    sort_token = enum_filter_or_422(
        sort or DESIGNER_DEFAULT_SORT, frozenset(DESIGNER_SORT_COLUMNS), field="sort"
    )
    dir_token = enum_filter_or_422(
        dir or DESIGNER_SORT_DEFAULT_DIR[sort_token], frozenset({"asc", "desc"}), field="dir"
    )

    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    total, rows = await count_and_page(
        db.designerroster,
        where=where,
        skip=skip,
        take=clean_size,
        order={DESIGNER_SORT_COLUMNS[sort_token]: dir_token},
    )
    return page_payload([roster_payload(r) for r in rows], total, clean_page, clean_size) | {
        # See the docstring: ``True`` only when the roles query above actually hit its cap. Sent
        # even when ``False`` (and even when ``roles`` was never asked for at all) so both rosters
        # answer one envelope shape and a shared client component never has to know which one it is
        # rendering before deciding whether a cut notice applies.
        "roleMatchTruncated": role_match_truncated,
    }


@router.get("/roster/institutions")
async def list_roster_institutions(
    _: Any = Depends(require_designer_roster_manager),
) -> dict[str, Any]:
    """The institution vocabulary behind the roster's institution filter (DROPDOWN_DESIGN §4.5).

    DECLARED HERE, RIGHT AFTER ``list_roster`` AND BEFORE THE ``/roster/{roster_id}`` ROUTES BELOW,
    for the module docstring's own rule: FastAPI matches in declaration order, and a literal path
    segment has to be declared before any parameterised sibling that could otherwise swallow it. This
    route collides with nothing today — the roster's ``{roster_id}`` routes are ``PATCH``/``DELETE``,
    not ``GET`` — but it is placed defensively, the way ``access.pending_request_count`` is, so that
    stays true the day a ``GET /roster/{roster_id}`` is added.

    GATED IDENTICALLY TO THE LIST ITSELF: ``require_designer_roster_manager``. The names on this
    endpoint are the same names ``GET /roster`` prints — an institution an admin typed against a
    real designer — so a caller who may not read the roster may not read its vocabulary either.

    ``DesignerRoster.institution`` IS FREE TEXT, SO AN EXACT-MATCH FILTER IS ONLY USABLE BEHIND A
    PICKER OF THE VALUES THAT ACTUALLY EXIST — this endpoint is that picker's data. Built from
    ``distinct=["institution"]`` rather than a hand-rolled ``GROUP BY`` or a Python ``set()`` over
    every row: the whole roster is about 1,300 rows today
    (``design_workshop_viewers.py`` — "about 1,300 designer rows"), but a distinct-in-the-query read
    is the one that stays cheap as that number grows, and it is what
    ``20260829120000_roster_filter_indexes``'s ``DesignerRoster_institution_idx`` was added to serve
    as an index-only scan.

    NULL AND THE EMPTY STRING ARE BOTH EXCLUDED, IN THE QUERY. ``NULL`` is the ordinary "nobody typed
    an institution" state and is what the roster's own reserved ``none`` filter token
    (:data:`INSTITUTION_NONE`) already covers without needing a row here — offering it a second time
    as a literal vocabulary entry would give the picker two rows that mean the same thing, one of them
    unlabelled. An empty string should not occur (``designers._clean`` stores an all-whitespace value
    as ``NULL`` rather than as ``""``), but the exclusion is defensive: a blank institution offered as
    a real, tickable option would be a filter row that narrows to nothing whenever anybody chooses it.

    TRUNCATED AT :data:`INSTITUTION_LIST_CAP`, READ ONE PAST IT SO THE FLAG IS EXACT — the
    ``GET /tasks/options`` manner, and the same trick ``active_roster_emails`` and ``eligible_viewers``
    use for the same reason: a vocabulary of EXACTLY the cap must report ``truncated: false`` honestly
    rather than crying wolf, and no second ``COUNT(DISTINCT …)`` is ever paid to get that exactness.
    Logged at WARNING rather than the role filter's ERROR: an institution past the cut is merely
    unreachable through THIS picker (the free-text search box on the roster itself still finds it),
    where a role-filter cut silently removes a matching DESIGNER from every page of a filtered list.

    ``total`` IS THE COUNT OF ``items``, NOT A SECOND, SEPARATE CORPUS COUNT — there is deliberately
    no ``SELECT COUNT(DISTINCT institution)`` here for the same reason ``eligible_viewers`` pays no
    second count for its own ``truncated``: the one number this endpoint needs to be honest about is
    whether it was cut, and that is answered for free by the ``+1`` read.
    """
    rows = await db.designerroster.find_many(
        where={"AND": [{"institution": {"not": None}}, {"institution": {"not": ""}}]},
        distinct=["institution"],
        order=[{"institution": "asc"}],
        take=INSTITUTION_LIST_CAP + 1,
    )
    names = [row.institution for row in rows]
    truncated = len(names) > INSTITUTION_LIST_CAP
    if truncated:
        names = names[:INSTITUTION_LIST_CAP]
        logger.warning(
            "more than %s distinct institutions are on the designer roster, so the institution "
            "filter's vocabulary was cut; an institution past that cut cannot be ticked in the "
            "picker until this cap is raised, though it is still reachable through the roster's "
            "own free-text search",
            INSTITUTION_LIST_CAP,
        )
    return {"items": names, "total": len(names), "truncated": truncated}


@router.post("/roster", status_code=status.HTTP_201_CREATED)
async def add_to_roster(
    payload: DesignerRosterCreate,
    current_user: Any = Depends(require_designer_roster_manager),
) -> dict[str, Any]:
    """Empanel an email.

    No user account is required and none is created. The row exists so that the account can
    provision itself: the first time that address signs in through Google, ``auth.py`` promotes
    it to DESIGNER. That is how an admin empanels somebody who has never opened the app.

    A duplicate is a 409 NAMING THE EXISTING ROW, not a 500 from the unique index and not a
    silent overwrite. The common way to arrive here is an admin re-empanelling a designer they
    suspended in March: overwriting would erase the note recording the original empanelment —
    the one thing the row exists to preserve — so the answer says where the row is and that
    restoring it is a PATCH.

    **STORED UNDER THE MAILBOX, NOT UNDER WHATEVER WAS TYPED — AND THE DUPLICATE TEST ASKS ABOUT
    THE MAILBOX TOO, FOR THE SAME REASON ``access.add_to_access_roster`` DOES.** This used to store
    ``normalise_email(payload.email)`` — lower-cased and trimmed, dots and all — which is exactly
    the gap ``test_a_dotted_gmail_row_is_the_row_the_undotted_sign_in_lands_on`` and
    ``test_a_revocation_typed_with_the_dots_is_not_walked_around_by_the_mailbox``
    (``tests/test_designer_empanelment_auto.py``) were written to demand closed: Google is the only
    sign-in path a designer has, Google always presents the undotted mailbox, and
    ``email_match_keys`` derives exactly one key from an address that already IS the mailbox — so a
    row an admin typed here with the dots left in was invisible to ``ensure_empanelled``'s
    existence check. The consequence was not a refusal anybody noticed: a second, ACTIVE row was
    silently created beside the administrator's on the designer's next sign-in, and where the
    administrator's row was a REVOCATION, the new row admitted a designer whose empanelment had
    been deliberately withdrawn — the exact "a revocation comes undone, quietly, one login at a
    time" failure the create-only rule in :func:`app.services.designers.ensure_empanelled` exists
    to prevent, arriving through the one input that rule cannot inspect. Storing the canonical form
    here is what stops the next row an admin types from being unmatchable in the first place,
    matching what ``access.add_to_access_roster`` already does for the allow-list. The duplicate
    check reads both spellings (:func:`email_match_keys`) rather than the canonical form alone, so
    it still catches a row written before this fix landed — the backfill for those is
    ``scripts/backfill_email_canonicalisation.py``.
    """
    email = canonical_email(payload.email)
    existing = await db.designerroster.find_first(
        where={"email": {"in": email_match_keys(payload.email)}}
    )
    if existing is not None:
        typed = normalise_email(payload.email)
        stored = normalise_email(existing.email)
        same_mailbox = (
            ""
            if stored == typed
            else (
                f" You typed {typed} and the row is stored as {stored}; Google delivers both to "
                "the same mailbox, so this application treats them as one address."
            )
        )
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"{stored} is already on the roster"
                f"{'' if existing.isActive else ' (suspended)'}. "
                f"Update or restore roster entry {existing.id} instead of adding it again."
                f"{same_mailbox}"
            ),
        )
    row = await db.designerroster.create(
        data={
            "email": email,
            "fullName": _clean(payload.fullName),
            "institution": _clean(payload.institution),
            "notes": _clean(payload.notes),
            "isActive": payload.isActive,
            # Stamped on creation when the row starts suspended, so `revokedAt` is never null on a
            # row that cannot sign in — the roster screen reads that pair together and a suspended
            # row with no date reads as a bug in the screen rather than as a deliberate state.
            "revokedAt": None if payload.isActive else datetime.now(UTC),
            "addedById": current_user.id,
        }
    )
    return roster_payload(row)


@router.patch("/roster/{roster_id}")
async def update_roster_entry(
    roster_id: str,
    payload: DesignerRosterUpdate,
    _: Any = Depends(require_designer_roster_manager),
) -> dict[str, Any]:
    """Correct a roster row, or restore a suspended one with ``isActive: true``.

    Restoring CLEARS ``revokedAt``. Leaving the old timestamp behind would produce a row that is
    active and carries a revocation date, and the next admin reading it has no way to tell
    whether the person may sign in — the flag or the date, and they disagree.
    """
    row = await db.designerroster.find_unique(where={"id": roster_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")

    values = payload.model_dump(exclude_unset=True)
    data: dict[str, Any] = {}
    if "email" in values:
        # CANONICAL ON THIS WRITE TOO — see the identical note on ``add_to_roster`` above. A
        # correction typed here can plant the same unmatchable, dotted row a fresh admission could,
        # and the clash check reads both spellings (``email_match_keys``) so it still catches a row
        # — this one included, if it predates the fix — stored under the other one.
        email = canonical_email(values["email"])
        if email and email != row.email:
            clash = await db.designerroster.find_first(
                where={"email": {"in": email_match_keys(values["email"])}}
            )
            if clash is not None:
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail=f"{normalise_email(clash.email)} is already on the roster as entry {clash.id}.",
                )
            data["email"] = email
    for key in ("fullName", "institution", "notes"):
        if key in values:
            data[key] = _clean(values[key])
    if "isActive" in values and values["isActive"] is not None:
        data["isActive"] = bool(values["isActive"])
        data["revokedAt"] = None if data["isActive"] else datetime.now(UTC)
    if not data:
        return roster_payload(row)
    return roster_payload(await db.designerroster.update(where={"id": roster_id}, data=data))


@router.delete("/roster/{roster_id}")
async def suspend_roster_entry(
    roster_id: str, _: Any = Depends(require_designer_roster_manager)
) -> dict[str, Any]:
    """SUSPEND, never delete. See the module docstring.

    Idempotent: suspending an already-suspended row keeps the ORIGINAL ``revokedAt``, because
    that date is the answer to "when did this designer lose access", and a second click on the
    button would otherwise move it to today and destroy it.
    """
    row = await db.designerroster.find_unique(where={"id": roster_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if not row.isActive and row.revokedAt is not None:
        return roster_payload(row)
    updated = await db.designerroster.update(
        where={"id": roster_id},
        data={"isActive": False, "revokedAt": row.revokedAt or datetime.now(UTC)},
    )
    return roster_payload(updated)


# --------------------------------------------------------------------------------------
# The directory — declared BEFORE /{user_id}/profile, see the module docstring
# --------------------------------------------------------------------------------------


@router.get("/directory")
async def designer_directory(
    search: str | None = None,
    includeSuspended: bool = False,
    _: Any = Depends(require_designer_roster_manager),
) -> list[dict[str, Any]]:
    """The accounts an admin may hand a workshop to, with each one's standing attached.

    SUSPENDED DESIGNERS ARE HIDDEN BY DEFAULT, and that is the useful part. Assigning a
    fortnight of fieldwork to somebody whose roster row was revoked last month produces a
    workshop nobody can open: the assignment succeeds, the designer's next sign-in is refused,
    and the gap is discovered when the report is due. ``includeSuspended=true`` shows them,
    marked, for an admin who is deciding whom to restore.

    ADMINS AND THE MASTER ADMIN are listed unconditionally — they can run a workshop and the
    roster does not gate them, which is the whole reason the two facts are kept apart.

    A PROFESSOR IS NOT LISTED AT ALL, even though they outrank a designer. This paragraph used to
    say "accounts above DESIGNER are listed unconditionally", which was the rank ladder's answer
    and not this capability's: ``deps.can_run_design_workshops`` is a SET, and the viewer write
    refuses a professor with a 422 that discards the entire request body. The docstring said one
    thing, the server did another, and the next person to build the picker this endpoint's name
    promises would have shipped the docstring's version. See ``WORKSHOP_CAPABLE_ROLES`` above.
    """
    # EVERY FILTER GOES IN THE ``WHERE``, AND THE SUSPENSION FILTER MOST OF ALL.
    #
    # The roster check used to run in Python after the read (``continue`` in the loop below), so
    # the ``take`` was spent on rows that were then thrown away: twenty suspended designers
    # sorting inside the first 500 came back as 480 rows, with eligible designers past the cut
    # never considered at all. Both clients infer "the list was cut" from ``len(rows) >= 500``
    # (``DIRECTORY_CAP`` on the web, ``DESIGNER_DIRECTORY_CAP`` on Android) and a post-take drop
    # is exactly what breaks that inference — it reports a COMPLETE list that is missing people.
    # Filtering in the query makes the cap apply to rows that are already eligible, which is what
    # makes the length honest again. This is the same defect, in the same shape, that
    # ``eligible_viewers`` was fixed for on 2026-08-13.
    clauses: list[dict[str, Any]] = [{"role": {"in": WORKSHOP_CAPABLE_ROLES}}]
    if not includeSuspended:
        # The flag is discarded deliberately: this route answers a bare JSON array, so there is
        # nowhere on the wire to say the roster read itself was cut. ``active_roster_emails``
        # already logs that case at ERROR, which is the whole reason it returns the flag rather
        # than swallowing it — see the follow-up note about giving this endpoint an envelope.
        admitted, _roster_read_was_cut = await active_roster_emails()
        clauses.append(
            {
                "OR": [
                    # Admins are not roster-gated at any point, the same rule ``roster_allows``
                    # applies at sign-in: an admin empanelled years ago and later suspended must not
                    # lose the ability to administer anything.
                    {"role": {"in": ["ADMIN", "MASTER_ADMIN"]}},
                    # ``mode: "insensitive"`` because ``admitted`` is lower-cased and ``User.email``
                    # is not — an address stored shouting would otherwise match no roster row and the
                    # designer would vanish from a directory the roster admits.
                    {
                        "AND": [
                            {"role": "DESIGNER"},
                            {"email": {"in": admitted, "mode": "insensitive"}},
                        ]
                    },
                ]
            }
        )
    if search:
        token = search.strip()
        # AND-COMPOSED WITH THE CLAUSE ABOVE, NEVER ASSIGNED TO THE SAME ``OR`` KEY. Two ORs
        # written to ``where["OR"]`` let the later one win, and if that is the search then the
        # eligibility clause is gone and the directory offers suspended designers to the one
        # caller that asked not to see them.
        #
        # ``records.contains`` rather than the raw filter this used to compose. This picker searches
        # the same two User columns as the viewer picker, whose measured numbers are in the
        # ``contains`` docstring (``search=_designer`` returned 635 accounts holding no underscore
        # at all), and it was left behind when that sweep ran.
        clauses.append(
            {
                "OR": [
                    {"name": contains(token)},
                    {"email": contains(token)},
                ]
            }
        )
    users = await db.user.find_many(
        where={"AND": clauses},
        # The id is the TIEBREAKER and it is load-bearing on a capped read: display names are not
        # unique in this table, so with ``name`` alone which rows fall inside the 500 is Postgres's
        # choice and can differ between two identical requests — "who is missing" changing on
        # refresh, which no search term can be relied on to reach.
        order=[{"name": "asc"}, {"id": "asc"}],
        take=DIRECTORY_TAKE,
    )
    if not users:
        return []

    # Two lookups for the whole page rather than two per row: a directory of eighty designers
    # rendered one query at a time is a hundred and sixty sequential round trips on a
    # cross-region database, which is several seconds before the picker opens.
    emails = sorted({u.email for u in users})
    ids = sorted({u.id for u in users})
    roster_rows, profiles = await asyncio.gather(
        db.designerroster.find_many(where={"email": {"in": emails}}),
        db.designerprofile.find_many(where={"userId": {"in": ids}}),
    )
    # KEYED LOWER-CASED ON BOTH SIDES. ``DesignerRoster.email`` is normalised on the way in and
    # ``User.email`` is not, so an exact-string key silently misses the roster row of anybody whose
    # account address is stored with a capital — and a DESIGNER with no roster row found reads as
    # suspended, which is the one verdict this payload exists to report.
    by_email = {r.email.lower(): r for r in roster_rows}
    by_user = {p.userId: p for p in profiles}

    out: list[dict[str, Any]] = []
    for user in users:
        roster = by_email.get((user.email or "").lower())
        profile = by_user.get(user.id)
        gated = role_value(user) == "DESIGNER"
        can_sign_in = bool(roster and roster.isActive) if gated else True
        # A BACKSTOP, NOT THE FILTER. Since the roster fold moved into the WHERE above this is
        # unreachable on the ``includeSuspended=false`` arm, and it must stay that way: put the
        # suspension test back here as the only filter and the cap starts being spent on rows that
        # are discarded again. It is kept because a mismatch between the two would otherwise ship a
        # suspended designer to a picker, and silence is the wrong failure for that.
        if gated and not can_sign_in and not includeSuspended:
            continue
        out.append(
            {
                "id": user.id,
                "name": user.name,
                "email": user.email,
                "role": role_value(user),
                "institution": (
                    getattr(profile, "institution", None) or getattr(roster, "institution", None)
                ),
                "rosterId": getattr(roster, "id", None),
                "rosterActive": bool(roster and roster.isActive),
                # What the picker should actually disable a row on. `rosterActive` is a fact about a
                # table; this is the answer to the question being asked, and for a professor or an
                # admin with no roster row at all the two deliberately differ.
                "canSignIn": can_sign_in,
                "firstSeenAt": (
                    roster.firstSeenAt.isoformat()
                    if roster is not None and roster.firstSeenAt
                    else None
                ),
                "hasProfile": profile is not None,
            }
        )
    return out


# --------------------------------------------------------------------------------------
# The profile
# --------------------------------------------------------------------------------------


@router.get("/me/profile")
async def get_my_profile(current_user: Any = Depends(require_designer)) -> dict[str, Any]:
    """The signed-in account's own profile, created empty on first read.

    GATED AT ``can_run_design_workshops`` — Designer, Admin, Master Admin — and this paragraph
    replaces one that said the opposite. It used to read "any role, deliberately", on the argument
    that whoever fills in for an absent designer signs the report the same way and needs the same
    details on file. Half of that argument survives and is why ADMIN is in the set. What does not
    survive is a crowdsource volunteer or a researcher holding a designer profile: it is the name,
    institution and biography a report is SUBMITTED UNDER, and an account that cannot start a
    workshop has no report to sign.

    A PROFESSOR is deliberately outside the set even though they outrank a designer — see
    ``deps.can_run_design_workshops`` for why that one capability is a set rather than a rank
    threshold. If that turns out to be wrong for professors standing in, change the SET, not this
    route; the two must not disagree.
    """
    return profile_payload(await get_or_create_profile(current_user.id))


@router.put("/me/profile")
async def put_my_profile(
    payload: DesignerProfileUpdate, current_user: Any = Depends(require_designer)
) -> dict[str, Any]:
    """Save one's own profile. Fields absent from the body keep their stored value.

    Note what this does NOT do: it does not touch any workshop already created. The profile is
    copied into a workshop's stages once, when the workshop is created — see
    ``prefill_from_profile`` for why a report must not change under a designer who changes
    institution.

    ── THE ADDRESS GOES THROUGH THE ONE HELPER THE SIX RECORD ROUTES ALREADY USE ─────────────

    ``attach_location`` is called between the dump and the write, in the same position it occupies in
    ``artisans.create_artisan``/``update_artisan`` and in media, products, tools, questionnaire and
    workshops. It creates the ``Location`` row from the body's ``location`` object and replaces that
    key with a ``locationId``; the profile becomes the seventh owner of that table rather than a
    seventh way of writing down an address.

    IT DOES NOTHING WHEN THE BODY CARRIES NO LOCATION — its first line is ``if location:`` — so a
    designer who saves their phone number writes no ``Location`` row, keeps the one they had, and is
    never handed a coordinate the server invented. Nothing on this path geocodes, and nothing on this
    path reads a device fix: the profile's address is a STATED address, and the only coordinate that
    can reach it is one the person at the keyboard deliberately supplied. See
    ``DesignerProfileUpdate.location`` for why that rule cannot be enforced on this side of the wire.
    """
    values = payload.model_dump(exclude_unset=True)
    values = await attach_location(values)
    return profile_payload(await update_profile(current_user.id, values))


@router.get("/{user_id}/profile")
async def get_designer_profile(
    user_id: str, current_user: Any = Depends(require_designer)
) -> dict[str, Any]:
    """Another account's profile — the owner or an admin, and 404 for everybody else."""
    await _assert_may_touch_profile(user_id, current_user)
    return profile_payload(await get_or_create_profile(user_id))


@router.put("/{user_id}/profile")
async def put_designer_profile(
    user_id: str,
    payload: DesignerProfileUpdate,
    current_user: Any = Depends(require_designer),
) -> dict[str, Any]:
    """Write another account's profile — the owner or an admin, and NOBODY else.

    A designer must not be able to edit another designer's biography. The text saved here is
    printed verbatim in a report that goes out under that person's name, so this is not a matter
    of tidiness: it is one designer being able to put words in another's mouth on a document
    submitted to a ministry.

    The address is attached exactly as it is on ``PUT /me/profile`` — see that handler for why, and
    for why an admin's two-key PUT that never mentions a location leaves the stored one alone.
    """
    await _assert_may_touch_profile(user_id, current_user)
    values = payload.model_dump(exclude_unset=True)
    values = await attach_location(values)
    return profile_payload(await update_profile(user_id, values))


# --------------------------------------------------------------------------------------
# Private helpers
# --------------------------------------------------------------------------------------


async def _assert_may_touch_profile(user_id: str, current_user: Any) -> None:
    """Owner or admin, or a 404 — never a 403.

    404 AND NOT 403, matching ``load_workshop_or_404``. A 403 would confirm that the id belongs to
    a real account, so a designer with a list of cuids could enumerate the staff by watching which
    ones answer 403 and which answer 404. Both cases carry the identical detail string for the
    same reason: "you may not see this" and "there is no such user" have to be indistinguishable
    or the distinction is the leak.

    The authority check runs BEFORE the existence query, so a stranger's probe costs no round trip
    at all.
    """
    if user_id != current_user.id and not is_admin(current_user):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=_PROFILE_NOT_FOUND)
    target = await db.user.find_unique(where={"id": user_id})
    if target is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=_PROFILE_NOT_FOUND)


def _clean(value: Any) -> Any:
    """Trim a roster string, storing an all-whitespace value as NULL rather than as " "."""
    if isinstance(value, str):
        return value.strip() or None
    return value


def _resolve_institutions(raw: list[str] | None) -> tuple[list[str], bool] | None:
    """Parse ``institutions`` into ``(exact names, include-no-institution)``, or ``None`` meaning
    "every institution — do not filter".

    THE SAME TWO SPELLINGS AS EVERY OTHER MULTI-VALUED FILTER ON THIS WIRE — repeated parameters and
    one comma-joined value — because DROPDOWN_DESIGN §4.1's table names ``institutions`` "repeated OR
    comma" beside ``roles`` and the allow-list's ``status``, and Android's own ``institutionList``
    (``ui/RosterFilters.kt``) already sends the comma-joined form for every tick past the first. A
    server that understood only repeated parameters would answer that client's multi-institution
    filter as though only the LAST tick had been made — silently narrower than what was asked for,
    which is exactly the failure this document's parsing-rules section exists to rule out.

    **DELIBERATELY NOT ``record_filters.enum_filter_list_or_422``, AND THIS IS THE ONE PLACE IN THE
    APP WHERE THAT CALL IS DECLINED.** ``DesignerRoster.institution`` is free text an admin typed,
    not a closed vocabulary — there is no fixed set of "valid" institutions to check a token against,
    so the 422 that helper raises for an unrecognised value has nothing correct to say here: EVERY
    string is a priori a valid institution, including one the picker has never offered because
    :data:`INSTITUTION_LIST_CAP` truncated the vocabulary read. Reaching for that helper anyway would
    also fold case against a "vocabulary" of admin-typed strings, silently merging "NID Ahmedabad"
    and "NID ahmedabad" into whichever one happened to be checked first — precisely the collision
    that helper's own docstring names THIS column as the worked example of.

    **THE COMMA SPLIT IS A KNOWN, ACCEPTED, DOCUMENTED COST — NOT AN OVERSIGHT.** An institution typed
    as "National Institute of Design, Ahmedabad" contains the separator this function splits on, so
    ticking that row in the picker sends a value that becomes TWO tokens here, and the resulting
    ``IN`` list names two institutions that do not exist — a silent, empty-but-well-formed result
    rather than a crash. ``enum_filter_list_or_422``'s own docstring names this exact scenario as
    failure mode 1 of reaching for it over free text, and names two ways clear of it: a wire that
    carries one whole value per repeated parameter with no separator and no fold, or a served
    vocabulary of STABLE TOKENS rather than of the display strings themselves. Both are real redesigns
    of a wire this route already has a committed caller for — Android's ``institutionList`` always
    comma-joins, and §4.1's table says "repeated OR comma" for this exact parameter — so the cost is
    accepted here rather than designed around: refusing to split at all would break every
    multi-institution filter Android already sends, a certain regression traded for an unlikely one.
    An institution name with a literal comma in it is rare on this roster's real data, and remains
    reachable through the roster's own free-text search box even when it cannot be ticked in the
    picker — the same trade-off DROPDOWN_DESIGN §6 Q2 makes for search-box diacritics, stated here
    rather than hidden.

    ``None`` (absent, empty, or all-blank) means DO NOT FILTER, and is deliberately not an empty list
    — the same rule ``record_filters.resolve_workshop_ids`` states for workshop ids and
    ``enum_filter_list_or_422`` states for enums: a cleared multi-select must mean "everything" by
    writing NO key into the ``where`` at all, never by asking for a list of every institution that
    happens to exist right now, which silently becomes a stale, narrower answer the moment a new
    institution is added to the roster.

    THE RESERVED TOKEN IS :data:`INSTITUTION_NONE`, covering ``institution IS NULL`` — without it,
    ticking every institution the picker offers would silently drop every row that has none, the
    ``UNASSIGNED_WORKSHOP`` failure one column over.
    """
    if not raw:
        return None
    wanted = [part.strip() for value in raw for part in str(value).split(",") if part.strip()]
    if not wanted:
        return None
    include_none = INSTITUTION_NONE in wanted
    # ``dict.fromkeys`` rather than ``set(...)``: it keeps first-occurrence order, which is not
    # load-bearing for correctness (the clause built from this is an unordered ``IN`` list either
    # way) but is what makes a repeated request log identically twice, which matters when someone is
    # diffing two requests to see what changed.
    names = list(dict.fromkeys(value for value in wanted if value != INSTITUTION_NONE))
    return names, include_none
