import asyncio
import time
from collections import OrderedDict
from decimal import Decimal, InvalidOperation
from typing import Any

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.config import get_settings
from app.core.db import db
from app.core.security import decode_access_token

# The platform allow-list, consulted on USE of a dataset credential and not only on its issue — see
# the revocation paragraph in the SCOPED TOKENS banner below. Imported here rather than inside the
# function because there is no cycle to avoid: `services/access_roster` reaches for `core.config`,
# `core.db` and `services/designers`, and none of those reaches back for this module.
from app.services import access_roster

bearer_scheme = HTTPBearer(auto_error=False)


def get_value(obj: Any, field: str) -> Any:
    if isinstance(obj, dict):
        return obj.get(field)
    return getattr(obj, field, None)


def role_value(user: Any) -> str:
    role = get_value(user, "role")
    return str(getattr(role, "value", role))


# The seven-tier role ladder, strictly ordered. Higher rank inherits every power of the ranks below
# it; grantable capability booleans can additionally lift a specific power for a lower tier.
#
# SEVEN, AND THIS COMMENT SAID SIX FOR AS LONG AS DESIGNER HAS EXISTED. The tier is right there four
# lines down, with its own explanation of why 35. Miscounting it here is not a typo with no
# consequence: this is the file every permission question in the repository is answered from, and the
# same off-by-one had already propagated into README.md's role table (six rows, no DESIGNER row, in a
# product whose primary user is a designer) and into docs/PERMISSIONS.md, which records having been
# corrected for exactly this once already. Nothing mechanical counts these.
ROLE_RANK: dict[str, int] = {
    "CROWDSOURCE_VOLUNTEER": 10,
    "FIELD_CONTRIBUTOR": 20,
    "RESEARCHER": 30,
    # 35, in the gap the original tens deliberately left. A designer runs a workshop and signs the
    # report; a researcher documents what they find. Inserting the tier here rather than renumbering
    # keeps every stored role and every comparison in this file meaning what it meant before.
    "DESIGNER": 35,
    "PROFESSOR": 40,
    "ADMIN": 50,
    "MASTER_ADMIN": 60,
}

ROLE_LABELS: dict[str, str] = {
    "CROWDSOURCE_VOLUNTEER": "Crowdsource Volunteer",
    "FIELD_CONTRIBUTOR": "Field Contributor",
    "RESEARCHER": "Researcher",
    "DESIGNER": "Designer",
    "PROFESSOR": "Professor",
    "ADMIN": "Admin",
    "MASTER_ADMIN": "Master Admin",
}


def role_rank(user_or_role: Any) -> int:
    """Rank of a user object or a bare role string; unknown roles rank lowest."""
    role = user_or_role if isinstance(user_or_role, str) else role_value(user_or_role)
    return ROLE_RANK.get(str(role), 0)


def has_rank(user: Any, role: str) -> bool:
    return role_rank(user) >= ROLE_RANK[role]


def is_admin(user: Any) -> bool:
    return role_value(user) in {"MASTER_ADMIN", "ADMIN"}


def is_master_admin(user: Any) -> bool:
    return role_value(user) == "MASTER_ADMIN"


def is_break_glass_master(user: Any) -> bool:
    """Is this account the ONE identity the platform allow-list must never be able to bar?

    THE BREAK-GLASS, SPELLED ONCE. ``access_roster`` decides who may sign in, and it is a table
    administrators edit; the argument that made it safe to widen that gate from designers to
    everybody is that there is always one account the gate itself exempts, so a bad UPDATE — or an
    admin barring the master admin on their way out — cannot leave the institution with nobody able
    to reach the roster screen and let people back in. See ``auth.assert_access_admits``.

    TWO CLAUSES, AND THE SECOND IS NOT REDUNDANT. The role is the normal answer; the configured
    ``MASTER_ADMIN_EMAIL`` is the answer on a fresh deployment where the row that carries the role
    has not been seeded yet, or where somebody has demoted it. A break-glass that only works while
    the database already says the right thing is not a break-glass.

    Both sides of the address comparison must be non-empty. An unset ``MASTER_ADMIN_EMAIL`` and a
    user row with no address would otherwise compare equal and exempt an account nobody chose —
    the one direction this predicate must never fail in.

    Lives here rather than in ``auth.py`` because it is now asked at more than one door: the sign-in
    path and ``POST /api/datasets/token``, which mints a thirty-day machine credential and must
    exempt exactly the same account for exactly the same reason. Copied instead of shared, the two
    would drift, and the half that drifted would either lock the break-glass out or open it wider.
    """
    if is_master_admin(user):
        return True
    address = str(get_value(user, "email") or "").strip().lower()
    configured = (get_settings().master_admin_email or "").strip().lower()
    return bool(address) and address == configured


def can_manage_questionnaire(user: Any) -> bool:
    """Edit the questionnaire structure: Professor and above, or an explicit grant."""
    return has_rank(user, "PROFESSOR") or bool(get_value(user, "canManageQuestionnaire"))


def can_manage_crafts(user: Any) -> bool:
    """Create or update a craft: Professor and above, RANK ALONE.

    The ``canManageCrafts`` column is deliberately no longer read. It was a per-user grant a master
    admin could hand to a researcher or below, which is the one clause that let the taxonomy itself
    be written by someone the permission matrix places under it — and a grant that widens a rank
    floor is invisible in the role column, so nobody auditing the user table could see who held it.
    The column stays in the schema (dropping it is neither safe nor reversible, and no live account
    below Professor holds it), simply unread; restoring the old behaviour is putting the clause back.
    """
    return has_rank(user, "PROFESSOR")


#: Who may run a design & prototype workshop. Named explicitly rather than derived from the rank
#: ladder, and that is the whole point — see :func:`can_run_design_workshops`.
DESIGN_WORKSHOP_ROLES = frozenset({"DESIGNER", "ADMIN", "MASTER_ADMIN"})


def can_run_design_workshops(user: Any) -> bool:
    """RUN a design & prototype workshop: open one, fill its 22 stages, create records inside it,
    and generate its report.

    NOT "create one". Starting a NEW workshop is :func:`can_create_design_workshops`, which is a
    strictly narrower set — see there for why the two were split. Everything else a designer has
    ever been able to do is still this predicate and is deliberately unchanged.

    THE ONE CAPABILITY IN THIS FILE THAT IS NOT A RANK THRESHOLD, and it is deliberate. Every
    other predicate here reads "this tier and above", because the ladder is inclusive: a professor
    can do everything a researcher can. This one is a SET — Designer, Admin, Master Admin — which
    means a PROFESSOR cannot run one even though they outrank a designer.

    That is the intended rule rather than an oversight. A design workshop is a fortnight of a
    named designer's work that ends in a document submitted to a ministry under their name, and
    being senior to a designer is not the same thing as being one. Admins are in the set because
    somebody has to be able to correct and administer the records, not because they outrank
    anybody.

    ``frontend/lib/permissions.ts`` carries the identical set and MUST keep carrying it: the UI
    offering what the API refuses is the failure mode this file's own rank table is commented
    about, and a non-monotonic rule is far easier to let drift than a threshold.
    """
    return role_value(user) in DESIGN_WORKSHOP_ROLES


#: Who may bring a NEW design & prototype workshop into existence. A STRICT SUBSET of
#: :data:`DESIGN_WORKSHOP_ROLES` — see :func:`can_create_design_workshops`.
DESIGN_WORKSHOP_CREATOR_ROLES = frozenset({"ADMIN", "MASTER_ADMIN"})


def can_create_design_workshops(user: Any) -> bool:
    """Start a NEW design & prototype workshop.

    ── WHY THIS IS NOT ``can_run_design_workshops`` ─────────────────────────────────────────────
    A DESIGNER may do everything inside a workshop and may no longer open one. Stated as the
    requirement was: "designers cannot create workshops (only admins/master admins can) —
    designers create records under existing workshops."

    The reason is that a design workshop is not a record, it is a CONTAINER for a fortnight of
    them, and it is the unit the ministry indexes, funds and audits. A sanction order authorises a
    workshop in a named cluster; the admin who holds that order is the person who knows a workshop
    exists, and creating one is therefore an administrative act, not a capture act. Left open to
    designers it produced duplicates of the same real workshop under three spellings of its title,
    each holding part of one fortnight's fieldwork, and nothing in the product could merge them.

    ── WHAT A DESIGNER STILL HAS, WHICH IS ALL OF IT BUT THIS ──────────────────────────────────
    Open a workshop they created or were granted; fill all 22 stages; create artisans, products,
    processes, tools and interviews inside it; capture photographs and dictation; generate and
    submit the report. Every one of those is gated by ``can_run_design_workshops`` or by
    ``load_workshop_or_404`` and NONE of them is narrowed by this function. Anybody tightening this
    file should check ``tests/test_design_workshop_gate.py``, which asserts that explicitly: a
    permission change that quietly cost a designer their stage edits would be far worse than this
    rule is worth.

    ``is_admin`` rather than a rank floor at ADMIN, so it reads as the same set the rest of this
    module means by "an admin", and so a PROFESSOR — who is below admin and outside the designer
    set entirely — is refused here for the same reason they are refused everywhere else in the
    design-workshop surface.

    ``frontend/lib/permissions.ts::canCreateDesignWorkshops`` and its
    ``DESIGN_WORKSHOP_CREATOR_ROLES`` carry the identical set and must keep carrying it.
    """
    return is_admin(user)


#: The refusal a designer reads when they try to start a workshop, in ONE place because it is said
#: on three surfaces — this module's 403, the web list page, and the offline draft store's refusal —
#: and a refusal that names a different next move depending on where you met it is not a rule, it is
#: three rumours. ``frontend/lib/permissions.ts::DESIGN_WORKSHOP_CREATE_REFUSAL`` is its twin.
#:
#: IT NAMES THE NEXT MOVE, which is not decoration. Somebody reading this is standing in a courtyard
#: with participants in front of them; "forbidden" tells them to stop working, and the truth is that
#: everything they came to do still works as soon as an admin has opened the workshop.
DESIGN_WORKSHOP_CREATE_REFUSAL = (
    "Only admins and the master admin can start a new design & prototype workshop. Ask an admin to "
    "create it for your cluster and give you access — you can then fill in all 22 stages, add "
    "artisans, products and photographs, and generate the report exactly as before. Any workshop "
    "you already have access to is open to you now."
)


def assert_can_create_design_workshops(user: Any) -> None:
    """Refuse anyone but an admin or the master admin, naming what they can do instead.

    A function rather than only a ``Depends`` because the create route already takes
    ``current_user`` for other reasons and because putting the sentence in one place is the point —
    see :data:`DESIGN_WORKSHOP_CREATE_REFUSAL`.
    """
    if not can_create_design_workshops(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=DESIGN_WORKSHOP_CREATE_REFUSAL,
        )


def can_manage_designer_roster(user: Any) -> bool:
    """Add, suspend and restore designers on the roster that gates their sign-in: Admin and above.

    Read as well as write. The roster is a list of named individuals and their institutional
    standing, so it is not something a peer should be able to browse.
    """
    return is_admin(user)


def can_manage_access_roster(user: Any) -> bool:
    """Decide who may sign in to this application at all: Admin and above.

    THE SAME TIER AS THE DESIGNER ROSTER, and for a stronger version of the same reason. This list
    is every address that may reach the product, so the people who can edit it can lock everybody
    else out — including each other. It is not a professor's job and it is emphatically not a
    designer's.

    Read is gated with write, again like the designer roster: the pending queue is a list of people
    who tried to get in, which is a list of somebody's colleagues, applicants and former staff.
    """
    return is_admin(user)


def can_manage_workshops(user: Any) -> bool:
    """Create or update a workshop: Professor and above, RANK ALONE — see ``can_manage_crafts`` for
    why the ``canManageWorkshops`` grant is no longer consulted."""
    return has_rank(user, "PROFESSOR")


def can_review_record(reviewer: Any, creator_role: Any) -> bool:
    """The peer-review hierarchy: the master admin reviews EVERYONE's work; everyone else may only
    review records whose creator ranks STRICTLY below them (admin reviews everyone beneath,
    professor reviews researchers and below, researcher reviews field contributors and volunteers,
    field contributor reviews volunteers). A record with no creator role on file is treated as a
    researcher's work."""
    if is_master_admin(reviewer):
        return True
    role = getattr(creator_role, "value", creator_role)
    if not role:
        role = "RESEARCHER"
    return role_rank(reviewer) > ROLE_RANK.get(str(role), ROLE_RANK["RESEARCHER"])


def can_edit_others_record(user: Any, creator_role: Any) -> bool:
    """May *user* edit a record created by *creator_role* as fully as its own author can?

    "A professor may edit the data of anyone ranked below them." The comparison for "below" is
    ``can_review_record``'s and nothing else's: a second hand-rolled rank test that disagreed with
    the review ladder by one tier is exactly the shape a privilege bug hides in. It is then narrowed
    to Professor and above, because the review ladder deliberately reaches further down than editing
    is meant to — a field contributor reviews a volunteer's work, but does not get to rewrite it.
    (``services/records.apply_status_policy_update`` composes the same two halves for status
    changes, which is where this pairing comes from.)
    """
    return has_rank(user, "PROFESSOR") and can_review_record(user, creator_role)


async def may_edit_lower_ranked_record(user: Any, creator_id: str | None) -> bool:
    """``can_edit_others_record`` with the creator's role looked up. Costs one query, and only for a
    Professor+ who is neither the record's author nor an admin — everyone else is refused before it."""
    if not creator_id or not has_rank(user, "PROFESSOR"):
        return False
    creator = await db.user.find_unique(where={"id": creator_id})
    return can_edit_others_record(user, get_value(creator, "role") if creator else None)


def can_access_review(user: Any) -> bool:
    """May open the review queue: Field Contributor and above (everyone who has someone beneath
    them on the ladder), or an explicit review grant. Which specific records they may act on is
    decided per record by can_review_record."""
    return has_rank(user, "FIELD_CONTRIBUTOR") or bool(get_value(user, "canReview"))


def can_review(user: Any) -> bool:
    """Back-compat alias for can_access_review — page-level review access, not per-record."""
    return can_access_review(user)


def can_download_dataset(user: Any) -> bool:
    """May download the entire dataset: Professor and above, or an explicit grant."""
    return has_rank(user, "PROFESSOR") or bool(get_value(user, "canDownloadDataset"))


def can_create_records(user: Any) -> bool:
    """May create core records (artisans, products, tools, processes, interviews): Researcher and
    above.

    The two tiers below — Field Contributor and Crowdsource Volunteer — POPULATE records rather than
    open them: attaching media to an existing artisan, answering questions in an existing interview,
    and commenting. Those three paths are the reason the tiers exist and none of them passes through
    here; they are gated by ``get_current_user`` on /media, /questionnaire's response arms, and
    /data-access/comments respectively."""
    return has_rank(user, "RESEARCHER")


# --- The authenticated-identity cache -------------------------------------------------------------
#
# WHY IT EXISTS. ``get_current_user`` runs on every authenticated request, and its one
# ``find_unique`` is one of only three sequential database waves left on a list endpoint like
# ``GET /artisans``. The database is cross-region: that round trip costs 200-400ms before any of the
# request's real work begins, and one dashboard page load pays it once per request it fires in
# parallel. Removing it is roughly a third of the remaining latency of every authenticated endpoint.
#
# WHY THE ROW IS FETCHED AT ALL, WHICH IS THE SAME REASON THE CACHE IS DANGEROUS. The access token
# already carries the user id in ``sub``, and ``create_access_token`` even puts ``email`` and
# ``role`` in it — so a "cache" that simply trusted the token would be free. That is exactly the
# shortcut this must not take. Tokens live for seven days (JWT_EXPIRES_MINUTES) and are never
# revoked, so a role claim minted before a demotion stays valid for a week, and a deleted account
# keeps a working token until it expires. The database read IS the revocation check. Caching it
# shortens the revocation window; trusting the token would remove it.
#
# SO THE WINDOW IS KEPT SHORT AT BOTH ENDS:
#   1. EXPLICIT INVALIDATION. Every write that changes a user's authority or identity calls
#      ``invalidate_cached_user`` — users.py (create/update/delete), auth.py (the Google sign-in
#      upsert, which can set MASTER_ADMIN), scripts/seed_admin.py. In-process, a demotion or a
#      deletion takes effect on the very next request, with no window at all.
#   2. A FIVE-SECOND TTL, which is only the backstop for writes this process cannot see: a psql
#      session, the seed script running as its own process, a second uvicorn worker's memory. Five
#      seconds is chosen to span the burst of parallel requests one page load makes (and the
#      follow-on requests a user makes while reading it) and nothing more; the value is a few
#      seconds rather than a few minutes because every second of it is a second a demoted account
#      keeps a privilege after such a write. AUTH_USER_CACHE_TTL_SECONDS tunes it and
#      AUTH_USER_CACHE_ENABLED=false removes it entirely without a deploy.
#
# A MISS IS NEVER CACHED. "No such user" stays a fresh query every time, so a deleted account 401s
# on every request rather than for a TTL and then not.
#
# SINGLE-FLIGHT, LOCALLY. N requests arriving together on a cold entry run ONE query; the rest await
# it. app/scale/singleflight.py does this well, but that whole package is flag-gated and off by
# default, and importing it here would put an always-on authentication path behind a dormant
# feature (and drag app.scale.flags onto the import graph of every request). Twenty lines that need
# no flag are the cheaper dependency.

_USER_CACHE_INFLIGHT_WAIT_SECONDS = 5.0

# id -> (monotonic expiry, user row). Ordered so eviction is LRU: the oldest touched identity goes
# first when the cap is reached.
_user_cache: "OrderedDict[str, tuple[float, Any]]" = OrderedDict()
# id -> (event loop, future). The loop is stored because a future can only be awaited on the loop
# that created it, and the test suite runs each case in its own ``asyncio.run``.
_user_cache_inflight: dict[str, tuple[Any, "asyncio.Future[Any]"]] = {}
# Bumped by every invalidation. A query that was already in flight when someone revoked a role would
# otherwise be free to write the pre-revocation row back into the cache and undo the invalidation;
# comparing this before and after the query closes that race by declining to store the result.
_user_cache_epoch = 0


class _LeaderGone(Exception):
    """The request that was loading this identity went away before it produced a row."""


def invalidate_cached_user(user_id: str | None) -> None:
    """Forget *user_id*, so the next request re-reads the row. Call after ANY write that changes a
    user's role, grants, email or existence — see the call-site list above."""
    global _user_cache_epoch
    _user_cache_epoch += 1
    if user_id:
        _user_cache.pop(user_id, None)


def clear_user_cache() -> None:
    """Forget every identity. For tests, and for a caller that has changed users in bulk."""
    global _user_cache_epoch
    _user_cache_epoch += 1
    _user_cache.clear()


def _cached_user(user_id: str) -> Any | None:
    entry = _user_cache.get(user_id)
    if entry is None:
        return None
    expires_at, user = entry
    if expires_at <= time.monotonic():
        del _user_cache[user_id]
        return None
    _user_cache.move_to_end(user_id)
    return user


def _store_cached_user(user_id: str, user: Any, ttl: float) -> None:
    _user_cache[user_id] = (time.monotonic() + ttl, user)
    _user_cache.move_to_end(user_id)
    max_entries = max(1, get_settings().auth_user_cache_max_entries)
    while len(_user_cache) > max_entries:
        _user_cache.popitem(last=False)


async def _load_user(user_id: str, ttl: float) -> Any:
    """One ``find_unique`` per identity per cold window, however many requests are waiting on it."""
    loop = asyncio.get_running_loop()
    pending = _user_cache_inflight.get(user_id)
    if pending is not None and pending[0] is loop:
        try:
            # shield: a follower that times out, or whose client hangs up, must not cancel the query
            # the leader is running on everyone's behalf.
            return await asyncio.wait_for(asyncio.shield(pending[1]), _USER_CACHE_INFLIGHT_WAIT_SECONDS)
        except (_LeaderGone, TimeoutError):
            pass  # load it ourselves — exactly what we would have done with no dedupe at all

    future: asyncio.Future[Any] = loop.create_future()
    # Retrieve the outcome on completion so a leader whose followers all went away leaves no
    # "exception was never retrieved" behind. The leader still raises for itself.
    future.add_done_callback(lambda done: done.cancelled() or done.exception())
    _user_cache_inflight[user_id] = (loop, future)
    epoch_at_start = _user_cache_epoch
    try:
        user = await db.user.find_unique(where={"id": user_id})
    except asyncio.CancelledError:
        _user_cache_inflight.pop(user_id, None)
        if not future.done():
            future.set_exception(_LeaderGone())
        raise
    except BaseException as exc:
        _user_cache_inflight.pop(user_id, None)
        if not future.done():
            future.set_exception(exc)
        raise
    _user_cache_inflight.pop(user_id, None)
    if user is not None and epoch_at_start == _user_cache_epoch:
        _store_cached_user(user_id, user, ttl)
    if not future.done():
        future.set_result(user)
    return user


async def resolve_user(user_id: str) -> Any:
    """The user row for *user_id*, from the identity cache when it is warm and enabled."""
    settings = get_settings()
    if not settings.auth_user_cache_enabled:
        return await db.user.find_unique(where={"id": user_id})
    cached = _cached_user(user_id)
    if cached is not None:
        return cached
    return await _load_user(user_id, settings.auth_user_cache_ttl_seconds)


# =================================================================================================
# SCOPED TOKENS
#
# Every token this API has ever issued is a SESSION token: it carries no ``scope`` claim and stands
# for its account in full, which is right for a browser and wrong for a script. ``POST
# /api/datasets/token`` mints the other kind — a long-lived credential a nightly export job can hold
# — and a credential that lives on a build server must be able to do strictly less than the admin
# who created it, or handing it out is the same as handing out the admin's password.
#
# The narrowing is enforced HERE, at the one function every authenticated route in the app depends
# on, rather than by the dataset routes being careful. That direction is the whole point: a scoped
# token is refused by default and admitted only where a dependency names its scope, so a route added
# next year is closed to it without its author having to know scoped tokens exist. Written the other
# way round — trusting each route to check — the read-only promise would have lasted exactly until
# the first route that forgot, and "read-only" would have meant "may DELETE /crafts/{id}".
#
# Tokens are not revocable within their lifetime (there is no token store). What IS revocable is the
# ACCOUNT, and by two separate acts, because two separate tables can end somebody's access:
#
# * its RANK — ``resolve_user`` re-reads the User row on every request behind a 5-second cache, so
#   demoting or deleting the service admin kills every token it ever minted, within 5 seconds; and
# * its PLATFORM ACCESS — ``require_dataset_admin`` re-reads ``AccessRoster`` per request, so
#   suspending or rejecting the account on the access screen stops the token at its next use.
#
# THE SECOND ONE IS NEW AND IT WAS THE GAP. Suspension writes the roster status and never
# ``User.role``, so before it existed the mint gate in ``routes/datasets`` refused a suspended admin
# a NEW token while the thirty-day one they already held kept working to its expiry — an
# administrator pressing Suspend on a departing colleague believed bulk data access was cut, and it
# was not. A gate on issue alone revokes nothing for the life of the credential already out there.
#
# WHAT IS STILL NOT REVOKED, STATED SO NOBODY HAS TO INFER IT: an ordinary SESSION token. It is
# checked by ``get_current_user``, which asks for rank and not for platform access, so a suspended
# account keeps the interactive application until its session token expires (``jwt_expires_minutes``,
# seven days). The allow-list read is paid HERE and not there deliberately: this dependency guards
# the one credential that hands over the whole repository to a machine and is asked a handful of
# times a day, where ``get_current_user`` is on the hot path of every request in the API.
# =================================================================================================

#: Scope claim carried by a dataset token: the bulk read-only export API and nothing else.
DATASET_READ_SCOPE = "dataset:read"

#: The claim scoped tokens are marked with. Absent on every session token, which is what makes the
#: default-deny below backwards compatible: existing tokens are unscoped and stay unrestricted.
TOKEN_SCOPE_CLAIM = "scope"


async def _user_from_bearer(
    credentials: HTTPAuthorizationCredentials | None,
    *,
    allowed_scopes: frozenset[str],
) -> Any:
    """The account behind a bearer token, refusing any scope not in ``allowed_scopes``.

    ``allowed_scopes`` is deliberately a required keyword with no default: adding a scope must be a
    decision taken at each dependency, not something a caller can inherit by omission.
    """
    if not credentials:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing bearer token")
    try:
        payload = decode_access_token(credentials.credentials)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=str(exc)) from exc

    # 403, not 401: the credential is genuine and unexpired: it is simply not admitted HERE. A 401
    # would send an API client off to re-authenticate, which would mint another token of the same
    # scope and fail identically — a retry loop instead of an error message.
    scope = payload.get(TOKEN_SCOPE_CLAIM)
    if scope is not None and str(scope) not in allowed_scopes:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                f"This token is scoped to '{scope}' and cannot be used on this endpoint. "
                "Sign in normally for full API access."
            ),
        )

    user_id = payload.get("sub")
    if not user_id:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token subject")

    user = await resolve_user(user_id)
    if not user:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="User no longer exists")
    return user


async def get_current_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
) -> Any:
    """The signed-in account. Session tokens only — see the scoped-token banner above."""
    return await _user_from_bearer(credentials, allowed_scopes=frozenset())


async def require_dataset_admin(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
) -> Any:
    """Admin credentials for the bulk dataset API, from a session OR a `dataset:read` token.

    ADMIN RANK IS CHECKED AGAINST THE LIVE USER ROW, never against the token's ``role`` claim: a
    dataset token may outlive the admin's tenure, and a role baked into a JWT is a promotion that
    cannot be taken back. ``resolve_user`` has already re-read the row by the time this runs.

    Deliberately stricter than ``require_dataset_downloader`` (Professor and above, or an explicit
    grant), which gates the in-app export surfaces. This API hands over the repository in bulk to a
    non-interactive caller, so it is admin-only exactly as asked.

    **THE PLATFORM ALLOW-LIST IS RE-READ ON EVERY USE, NOT ONLY WHERE THE TOKEN IS MINTED**, and
    that is what makes suspension mean anything here. ``routes/datasets.mint_dataset_token`` runs
    the sign-in gate, so a suspended admin cannot take a NEW credential — but the one they were
    given yesterday is good for thirty days (``dataset_token_expires_minutes``) and no store exists
    to tear it up. An administrator who presses Suspend on a departing colleague is entitled to
    believe bulk data access is cut; a gate that stops at the issuing door leaves it running.

    READ AS A CUT LIST, exactly as ``services/access_roster.barred_emails`` is: REJECTED and
    SUSPENDED are refused, and everything else — including PENDING and an address with no row at
    all — is let through. The stronger "require an ACTIVE row" is deliberately NOT used at this
    door. It belongs at the gate, where a refusal can enqueue the person for an administrator and
    where the empanelment clause can heal a missing row; here there is no person to enqueue and no
    healing to do, so failing closed would only strand a live credential on a state the sign-in
    path would have admitted. The barring states are the two no sign-in heals, and they are the
    only two an administrator ever chose.

    A PURE READ, and that is a security property rather than an economy. The full gate WRITES —
    ``access_roster.record_refused_attempt`` bumps an attempt count and can 503 when the approval
    queue is full — and a cron job polling this API every minute must not be able to inflate an
    administrator's queue, nor to start failing because other people's join requests filled it.

    The master admin is exempt through the same ``is_break_glass_master`` both other doors use: the
    break-glass has to open at every door or it is not one.
    """
    user = await _user_from_bearer(credentials, allowed_scopes=frozenset({DATASET_READ_SCOPE}))
    if not is_admin(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Admin access required for the bulk dataset API.",
        )
    if not is_break_glass_master(user):
        row = await access_roster.access_row(getattr(user, "email", None))
        if access_roster.status_of(row) in access_roster.BARRED:
            # Named plainly, because the caller has already proved it holds this account: the
            # operator reading a cron job's log needs to know the credential was not corrupted and
            # has not expired but was revoked, and which screen restores it. There is no attempt
            # row behind this refusal to send them to, so the sentence is all they get.
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=(
                    "This account's platform access has been withdrawn on the access screen, so "
                    "its dataset credential is refused. Ask an administrator to restore the "
                    "account's access; minting a new token will not help until they do."
                ),
            )
    return user


async def require_admin(current_user: Any = Depends(get_current_user)) -> Any:
    if not is_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin access required")
    return current_user


async def require_master_admin(current_user: Any = Depends(get_current_user)) -> Any:
    if not is_master_admin(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Master admin access required",
        )
    return current_user


async def require_reviewer(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_access_review(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Review access required")
    return current_user


async def require_professor(current_user: Any = Depends(get_current_user)) -> Any:
    if not has_rank(current_user, "PROFESSOR"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Professor access or above required",
        )
    return current_user


async def require_dataset_downloader(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_download_dataset(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Dataset download access required. Ask an admin to grant it.",
        )
    return current_user


def assert_can_create_records(user: Any) -> None:
    """The body-callable half of ``require_record_creator``, for the one route that cannot decide
    from its signature: POST /questionnaire/interviews opens a NEW interview or folds into an
    EXISTING one depending on the artisan set, and only the first of those is a create."""
    if not can_create_records(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Creating records requires Researcher access or above. Field contributors and "
                "volunteers can add media, questionnaire answers, and comments to existing records."
            ),
        )


async def require_record_creator(current_user: Any = Depends(get_current_user)) -> Any:
    assert_can_create_records(current_user)
    return current_user


async def require_questionnaire_manager(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_manage_questionnaire(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Questionnaire management access required",
        )
    return current_user


async def require_craft_manager(current_user: Any = Depends(get_current_user)) -> Any:
    """Gates POST and PATCH /crafts. DELETE is stricter still — ``assert_can_delete``, admin only."""
    if not can_manage_crafts(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Adding or editing a craft requires Professor access or above. Ask a professor or "
                "an admin to add it, then link your records to it."
            ),
        )
    return current_user


async def require_designer_roster_manager(current_user: Any = Depends(get_current_user)) -> Any:
    """Gates every arm of ``/designers/roster``, READ included.

    Read as well as write, unlike most of the ``require_*`` pairs here, because the roster is a
    list of named individuals, their institutions and an admin's private note about the programme
    each was empanelled under. A designer able to GET it would be reading their colleagues'
    standing — and, from ``firstSeenAt``, which of them has stopped using the app.
    """
    if not can_manage_designer_roster(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Managing the designer roster requires Admin access or above.",
        )
    return current_user


async def require_access_manager(current_user: Any = Depends(get_current_user)) -> Any:
    """Gates every arm of ``/access/roster``, READ included — see :func:`can_manage_access_roster`.

    THE ENDPOINTS BEHIND THIS ARE THE ONLY IN-PRODUCT REMEDY FOR A LOCKED-OUT INSTITUTION. If this
    predicate is ever tightened to master-admin-only, the break-glass stops being a break-glass and
    becomes a single point of failure: one account, one forgotten password, nobody gets in again.
    """
    if not can_manage_access_roster(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Managing who may sign in requires Admin access or above.",
        )
    return current_user


async def require_workshop_manager(current_user: Any = Depends(get_current_user)) -> Any:
    """Gates POST and PATCH /workshops. DELETE is admin-only (``assert_can_delete``)."""
    if not can_manage_workshops(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Adding or editing a workshop requires Professor access or above. Ask a professor "
                "or an admin to set it up, then request access to submit records to it."
            ),
        )
    return current_user


def is_empty_value(value: Any) -> bool:
    if value is None:
        return True
    if isinstance(value, str):
        return value.strip() == ""
    if isinstance(value, (list, tuple, set, dict)):
        return len(value) == 0
    return False


def enum_or_raw(value: Any) -> Any:
    return getattr(value, "value", value)


def values_match(current_value: Any, next_value: Any) -> bool:
    current_value = enum_or_raw(current_value)
    next_value = enum_or_raw(next_value)
    if current_value == next_value:
        return True
    try:
        return Decimal(str(current_value)) == Decimal(str(next_value))
    except (InvalidOperation, ValueError):
        return str(current_value) == str(next_value)


def assert_can_contribute_fields(record: Any, user: Any, data: dict[str, Any], owner_field: str = "createdById") -> None:
    if is_admin(user) or get_value(record, owner_field) == get_value(user, "id"):
        return

    # A populated field is locked to non-privileged editors whether they try to CHANGE it or CLEAR it.
    # (The earlier version skipped an incoming empty value, which let anyone blank out a populated field.)
    #
    # WHAT "CLEAR" CAN AND CANNOT MEAN HERE, because the sentence above was read as covering more
    # than it does. ``guard_record_edit`` hands this function the CLEANED payload — its docstring
    # says so — and ``records.clean_data`` has already dropped every ``None`` except the global
    # ``CLEARABLE_KEYS`` and the per-model names the route declared in ``clearable=``. So the two
    # halves are enforced in two different places, and both have to hold for the sentence to be true:
    #
    #   * ``""`` and other empty values always reach this loop, on every field, and are refused here.
    #   * ``None`` reaches this loop only on a name the route declared clearable — where it IS
    #     refused. On any other name the null never gets this far, and nothing is written either, so
    #     the field is still not blanked; it is simply not this function that stopped it.
    #
    # The failure mode to watch for is therefore not a leak but a MISMATCH: a route that adds a name
    # to its ``clearable=`` tuple is also handing this guard a case it did not previously see, and a
    # route that removes one takes that case away. Neither can open a hole in this rule — a name that
    # is not clearable cannot be cleared at all — but a reader tracing "who may blank this column"
    # has to look at the route's tuple as well as at this list.
    locked_fields = [
        field
        for field, next_value in data.items()
        if not is_empty_value(get_value(record, field))
        and (is_empty_value(next_value) or not values_match(get_value(record, field), next_value))
    ]
    if locked_fields:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Only the original contributor or an admin can change or clear populated field(s): {', '.join(sorted(locked_fields))}",
        )


def assert_can_contribute_relation(record: Any, user: Any, populated: bool, field_name: str, owner_field: str = "createdById") -> None:
    if is_admin(user) or get_value(record, owner_field) == get_value(user, "id"):
        return
    if populated:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Only the original contributor or an admin can change populated relation: {field_name}",
        )


def assert_owner_or_admin(record: Any, user: Any, owner_field: str = "createdById") -> None:
    if is_admin(user):
        return
    if get_value(record, owner_field) != get_value(user, "id"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only access records you created",
        )


def assert_admin_or_owner(record: Any, user: Any, owner_field: str = "createdById") -> None:
    assert_owner_or_admin(record, user, owner_field)


def assert_can_delete(user: Any) -> None:
    if not is_admin(user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin access required to delete records")


async def require_designer(current_user: Any = Depends(get_current_user)) -> Any:
    """Gates the designer's own profile, and mirrors what runs a design workshop.

    A designer profile is the name, institution and biography that a workshop report is SUBMITTED
    UNDER. It is only meaningful to somebody who can run a workshop, so the two share one
    predicate rather than drifting apart: an account that cannot start a workshop has no report to
    sign and no reason to be filling in the signature.

    Added because the web client needed to hide the page and could not honestly do so. The route
    was ``get_current_user`` — open to every signed-in account — and a UI guard over an open
    endpoint is a lock on a door with no wall: it hides the link and leaves the URL. Either both
    are gated or neither is, and the profile is the half that should have been gated all along.
    """
    if not can_run_design_workshops(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "A designer profile belongs to the people who run design & prototype workshops — "
                "designers, admins and the master admin."
            ),
        )
    return current_user
