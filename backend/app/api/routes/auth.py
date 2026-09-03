import logging
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.encoders import jsonable_encoder
from google.auth.transport import requests as google_requests
from google.oauth2 import id_token as google_id_token

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    ROLE_RANK,
    get_current_user,
    invalidate_cached_user,
    is_break_glass_master,
    require_admin,
    role_rank,
    role_value,
)
from app.core.security import create_access_token, hash_password, verify_password
from app.scale.rate_limit import account_credential_attempt, account_credential_refund
from app.schemas.auth import (
    ChangePasswordRequest,
    IssuePasswordLinkRequest,
    LoginRequest,
    SetPasswordRequest,
    TokenResponse,
)
from app.services import access_roster, credential_links, identity, usage
from app.services.designers import ensure_empanelled, mark_roster_seen, roster_allows

router = APIRouter(prefix="/auth", tags=["auth"])
logger = logging.getLogger(__name__)

# THE MESSAGE A REVOKED DESIGNER READS, and the reason this is a 403 rather than a 401.
#
# A suspended designer who is told "Invalid email or password" will do the only thing that
# message suggests: reset a password that was never wrong. They will do it two or three times,
# then telephone the wrong person about it, and the actual state of affairs — an admin
# deliberately ended their access — is never mentioned anywhere they can see it. The refusal has
# to say what happened AND what to do about it, because the person reading it cannot fix it
# themselves and there is exactly one action that leads anywhere.
DESIGNER_SUSPENDED_DETAIL = "Your designer access has been suspended. Contact the administrator."

# ----------------------------------------------------------------------------------------------
# THE FOUR THINGS A REFUSED SIGN-IN CAN SAY, AND WHY THEY ARE FOUR AND NOT ONE
# ----------------------------------------------------------------------------------------------
#
# The conventional advice is to answer every failed sign-in identically so that an attacker cannot
# learn which addresses have accounts. THAT ADVICE WAS CONSIDERED AND DELIBERATELY OVERRULED for
# this product, by the person who owns it, and the reasoning is worth keeping because the next
# reader's instinct will be to "fix" it:
#
#   * The people these messages are for are field designers and researchers at institutions, and
#     the thing that actually happens to them is a wait for an administrator. Told "invalid email
#     or password", a person waiting on approval resets a password that was never wrong, twice,
#     then telephones somebody who cannot help them. There is no self-service remedy in this
#     product — no registration page, no password-reset email — so a vague refusal leaves them with
#     no next action that exists.
#   * What the widened enumeration surface actually leaks is one bit: "this address is awaiting
#     approval". It does NOT leak the person's name, their role, whether a password was ever set,
#     or anything about any other account, and no code path below adds anything to it. Keep it that
#     way; the bit is the price that was agreed, not a budget to spend.
#   * The wrong-credential answer is UNCHANGED — still 401 "Invalid email or password" — so a
#     mistyped password still reads as a mistyped password, which is the distinction the whole
#     ruling is about.
ACCESS_PENDING_DETAIL = (
    "Your access request is awaiting administrator approval. This is not a password problem — an "
    "administrator has to approve this address before you can sign in."
)
ACCESS_REJECTED_DETAIL = (
    "Your access request was reviewed and not approved. Contact the administrator if you believe "
    "this is a mistake."
)
# Distinct from DESIGNER_SUSPENDED_DETAIL on purpose. That one is about an empanelment ending and
# names the designer roster; this one is about the account being barred from the application. An
# admin who suspends a volunteer must not see them told their "designer access" ended.
ACCESS_SUSPENDED_DETAIL = (
    "Your access to this application has been suspended. Contact the administrator."
)
# THE ONLY HONEST THING TO SAY WHEN THE REQUEST WAS NOT WRITTEN DOWN. Answering "you are awaiting
# approval" to somebody whose row was never created would be a lie with no expiry: no administrator
# will ever see them, and they would wait forever on a queue they are not in.
ACCESS_NOT_RECORDED_DETAIL = (
    "Access requests are temporarily closed because the approval queue is full, so this request "
    "could not be recorded. Contact the administrator directly."
)

# ----------------------------------------------------------------------------------------------
# THE SAME ANSWER, IN A FORM A CLIENT CAN BRANCH ON
# ----------------------------------------------------------------------------------------------
#
# The sentences above are for the person; this header is for the two sign-in screens that have to
# draw something AROUND the sentence. Both of them already changed their chrome on a refusal — the
# Android card replaces its "use Google instead" advice with a filled panel headed "Your access to
# this app has been withdrawn" — and that chrome is now WRONG for four of the five refusals: a
# person waiting on an approval has had nothing withdrawn, and telling them so sends them to argue
# with an administrator about an access they never had.
#
# A HEADER AND NOT A FIELD IN THE BODY, and the reason is a test rather than taste:
# ``tests/test_platform_access_gate.py`` asserts ``set(body) == {"detail"}`` — the refusal body
# leaks NOTHING beyond the sentence, deliberately, and a status field added next to ``detail``
# would be the first crack in a rule the whole feature's privacy argument rests on. The header
# carries no information the sentence does not already state in English; it only saves the clients
# from matching on prose, which is the coupling that breaks the day somebody fixes a typo.
#
# CLIENTS MUST TREAT AN ABSENT HEADER AS "UNCLASSIFIED" AND FALL BACK TO THE SENTENCE. A proxy that
# strips unknown headers, or an older deployment, must degrade to neutral chrome around the
# server's own words — never to the wrong chrome. Both clients do; see ``accessRefusalKind`` in
# frontend/lib/accessRoster.ts and ``AccessRefusal`` in android/…/data/WorkshopRepository.kt.
#
# It must also be listed in ``expose_headers`` on the CORS middleware in app/main.py, or the
# browser hides it from JavaScript while the phone sees it — a divergence that would be invisible
# in every test that does not run in a browser. See the note there.
ACCESS_STATUS_HEADER = "X-Access-Status"
#: The fifth value, for the ONE refusal that is not the allow-list's: an empanelment that ended.
#: Named for the roster it comes from so a client cannot confuse it with ``SUSPENDED``, which is the
#: allow-list barring the account itself.
DESIGNER_SUSPENDED_STATUS = "DESIGNER_SUSPENDED"


def _access_headers(access_status: str) -> dict[str, str]:
    """The one place the refusal header is spelled, so a raise cannot forget its own name."""
    return {ACCESS_STATUS_HEADER: access_status}


# ----------------------------------------------------------------------------------------------
# THE SECOND HEADER: TWO REFUSALS THAT ARE ABOUT THE IDENTIFIER RATHER THAN ABOUT ADMISSION
# ----------------------------------------------------------------------------------------------
#
# ``X-Access-Status`` above answers "where does this address stand with the allow-list". Neither
# of the two refusals below is an allow-list answer — one is "what you typed names two accounts"
# and the other is "this account has never had a password" — and putting them on that header
# would make a client's `accessRefusalKind` switch mean two different kinds of thing. So they get
# their own header, following the identical pattern, for the identical reason.
#
# **THE BODY STILL CARRIES EXACTLY ONE KEY.** ``tests/test_platform_access_gate.py`` asserts
# ``set(body) == {"detail"}`` at :395 and :1148, and the comment on ACCESS_STATUS_HEADER above
# explains that a second field beside ``detail`` "would be the first crack in a rule the whole
# feature's privacy argument rests on". Nothing here adds one. The sentence says the whole thing
# in English; the header only saves the clients from matching on prose.
#
# IT MUST BE IN ``expose_headers`` IN app/main.py, or the browser hides it from JavaScript while
# the phone reads it — the divergence that is invisible to every test not running in a browser.
SIGN_IN_HINT_HEADER = "X-Sign-In-Hint"

#: What was typed names more than one account. See ``services/identity.resolve_identifier``:
#: the two unique indexes make this rare and cannot make it impossible, because a value that is
#: one designer's phone key and another's empanelment key satisfies both of them.
AMBIGUOUS_IDENTIFIER_HINT = "AMBIGUOUS_IDENTIFIER"
AMBIGUOUS_IDENTIFIER_DETAIL = (
    "That number is registered to more than one account. Sign in with your email address "
    "instead, and ask an administrator to correct the duplicate."
)

#: The account exists and has never had a password of its own.
#:
#: **THIS WIDENS THE ENUMERATION SURFACE BY ONE FURTHER BIT AND THAT WAS THE TRADE.** The block
#: above records the owner's ruling that a vague refusal is worse than a narrow leak for this
#: product, because there is no self-service remedy here: told "invalid email or password",
#: somebody whose admin created their account and handed them a link resets a password they never
#: had. The bit leaked is "an account at this identifier has no password" — not the person's name,
#: not their role, not anything about any other account. The wrong-credential answer for an
#: account that DOES hold a password is untouched.
PASSWORD_NOT_SET_HINT = "PASSWORD_NOT_SET"
PASSWORD_NOT_SET_DETAIL = (
    "This account has no password yet. Ask an administrator for a set-password link, or sign "
    "in with Google if that is how the account was created."
)

#: The per-account guessing budget is spent. Deliberately the same shape of sentence as the
#: middleware's, and deliberately NOT a 401 — a 401 here would be charged again by the per-network
#: budget in app/scale/rate_limit.py and the two would compound.
ACCOUNT_THROTTLED_DETAIL = (
    "Too many failed sign-in attempts for this account. Wait a few minutes and try again — "
    "a sign-in that succeeds does not count against this limit."
)


def _hint_headers(hint: str) -> dict[str, str]:
    """The one place the sign-in hint header is spelled, so a raise cannot forget its own name."""
    return {SIGN_IN_HINT_HEADER: hint}


#: The key the usage-consent gate rides back on, beside the account's own columns.
#:
#: Named as a constant because two clients branch on it and a third — the Android settings screen —
#: reads it to decide whether to show a card. A literal retyped in a Kotlin DTO and a TypeScript type
#: is a contract living in three files; this is the one the two of them are copied from.
USAGE_CONSENT_GATE_KEY = "usageConsentGate"


def serialize_user(user: Any) -> dict[str, Any]:
    """The account as every client reads it: every column except the password hash, plus the one
    derived field a client must not derive for itself.

    ── THE USAGE-CONSENT GATE, AND WHY IT IS COMPUTED HERE ─────────────────────────────────────

    The four consent columns (``usageConsent``, ``usageConsentAt``, ``usageConsentBasis``,
    ``usageConsentVersion``) reach every client for free, because this function is
    ``jsonable_encoder`` over the whole row — that is the plumbing fact that makes the whole feature
    cost the sign-in path nothing. What does NOT come for free is the ANSWER a client actually needs:
    *must this person be asked, right now?* That is two facts folded into one — have they agreed, and
    did they agree to the CURRENT text — and the moment the web client and the handset each fold it
    themselves, the two disagree on the first deploy that bumps ``usage.NOTICE_VERSION`` while only
    one of them is updated. So the server folds it, once, and both clients render the boolean.

    ADDED HERE RATHER THAN AT THE TWO CALL SITES, because this function is where **all four doors
    converge**: ``POST /auth/login`` on the password path, the same route on the Google path,
    ``GET /auth/me`` and ``GET /me``. A client that signs in learns the answer; a client that
    refreshes its session learns it again; and neither can be given a session without being told.
    That last part is the point — see :func:`login`, which explains why the gate reports rather than
    refuses.

    IT NEVER RAISES AND NEVER BLOCKS. ``usage.consent_gate`` is a ``getattr`` off the row plus a
    string comparison: no query, no await, nothing that can fail. This function is on the hot path of
    every ``/me`` in the product.
    """
    payload = jsonable_encoder(user)
    payload.pop("passwordHash", None)
    payload[USAGE_CONSENT_GATE_KEY] = usage.consent_gate(user)
    return payload


def enum_value(value: Any) -> str:
    return str(getattr(value, "value", value))


def role_for_email(email: str) -> str:
    settings = get_settings()
    if email.lower() == settings.master_admin_email.lower():
        return "MASTER_ADMIN"
    # New self-registered Google accounts start at the configured signup tier (lowest tier by
    # default) and are elevated by an admin — an unknown Google account no longer becomes a
    # full researcher automatically.
    return settings.default_signup_role


async def assert_access_admits(email: str, *, is_master: bool) -> Any | None:
    """**THE SIGN-IN GATE.** Every account is refused unless the platform allow-list admits it.

    Returns the ``AccessRoster`` row on success (the caller needs it for ``firstSeenAt`` and, on the
    Google path, for the role a brand-new account is created at), or ``None`` for the master admin,
    who is never gated at all.

    APPLIED ON BOTH SIGN-IN PATHS — password and Google. On the password path from :func:`login`,
    after the credential; on the Google path from :func:`login_with_google`, BEFORE anything is
    written, because on that path admission is what decides whether an account comes into existence
    at all. Two call sites rather than one because the two paths genuinely need it at different
    moments; the test module signs in through both and both are asserted, which is what keeps the
    pair honest.

    AND ON THE ONE DOOR THAT IS NOT A SIGN-IN: ``POST /api/datasets/token`` in
    ``routes/datasets.py``, which exchanges the same email and password for a thirty-day machine
    credential. Suspension writes the roster status and never the ``User.role``, so an admin refused
    here at ``/auth/login`` was for a while still able to mint a fresh read token there, renewably,
    for ever — a revoked person holding live data access, through the one endpoint that hands over
    the whole repository. THE RULE THIS ESTABLISHES IS THE ONE TO KEEP: every place that turns a
    proved credential into a token calls this function. A new one that does not is a new way back
    in for somebody an administrator has already shown the door.

    THE PRECONDITION EVERY CALLER OWES IT: the identity must ALREADY be proved — a bcrypt check that
    passed, or a verified Google audience. It is what keeps the pending refusal (and the row
    :func:`access_roster.record_refused_attempt` writes behind it) reachable only by somebody who
    holds the account, so this cannot become a form for enumerating addresses or for filling an
    administrator's queue with them. See that function's docstring, which names the same bound from
    the other end.

    **WHY THE GATE IS NOW EVERYONE, AND WHAT REPLACES THE ARGUMENT THAT USED TO MAKE IT NARROW.**
    ``assert_roster_admits`` below gated only ``role == "DESIGNER"``, under a comment arguing that
    an admin locked out by a table only an admin can edit is an outage with no in-product remedy —
    there would be nobody left able to add the row that lets anybody back in. That argument was
    correct and the risk it names has not gone away; the requirement simply asks for a gate over
    everybody, so the risk has to be carried by something else. **That something is the MASTER_ADMIN
    exemption, and it is the only reason this is safe to widen.** There is always one account that
    reaches the roster screen and lets people back in, and it is exempt HERE, in the gate, rather
    than by a row in the table the gate reads — a break-glass that lives in the same table it is
    protecting against is not a break-glass. Delete the ``is_master`` clause and the first admin who
    fat-fingers their own row takes the whole institution offline with them.

    Fails CLOSED: no row is a refusal, not an admission. That is what makes the exhaustive
    grandfathering in the migration load-bearing rather than merely tidy, and it is why an account
    created by any path that forgets to admit it lands in the pending queue where an admin can see
    it, instead of quietly signing in.
    """
    if is_master:
        return None

    row = await access_roster.access_row(email)
    if access_roster.admits(row):
        return row

    # THE EMPANELMENT CLAUSE. An ACTIVE DesignerRoster row is an administrator's approval of this
    # person, made in a screen that predates the allow-list, and it must not have to be made twice.
    # Self-healing rather than merely permissive: the admission is written to the allow-list on the
    # way through, so the platform roster stays the complete answer to "who may sign in" and the
    # next sign-in takes the fast path above. Only reached when the allow-list has already declined,
    # so an ordinary sign-in still costs exactly one query.
    #
    # NO ROW, OR A ROW STILL WAITING — AND EMPHATICALLY NOT A DECIDED ONE. An admin who sees a
    # request from a designer and answers it by empanelling them on the designer roster has approved
    # them, and PENDING is precisely the state that means "nobody has said otherwise yet". REJECTED
    # and SUSPENDED are answers somebody actually gave about this person's access to the
    # application, and a years-old empanelment row must not overturn them — that would make the
    # allow-list's decisions quietly conditional on a second table the deciding admin never looked
    # at, and the reinstatement would be invisible in both screens.
    waiting = row is None or access_roster.status_of(row) == access_roster.PENDING
    if waiting and await access_roster.designer_empanelment_admits(email):
        return await access_roster.admit(
            email,
            admit_role="DESIGNER",
            note=(
                "Admitted by an active designer-roster empanelment. An administrator empanelled "
                "this address as a designer, which is an approval; this row records that the "
                "platform allow-list agreed with it."
            ),
            decided=False,
        )

    outcome = await access_roster.record_refused_attempt(email, row)
    if outcome == access_roster.REJECTED:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=ACCESS_REJECTED_DETAIL,
            headers=_access_headers(access_roster.REJECTED),
        )
    if outcome == access_roster.SUSPENDED:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=ACCESS_SUSPENDED_DETAIL,
            headers=_access_headers(access_roster.SUSPENDED),
        )
    if outcome == access_roster.NOT_RECORDED:
        # 503 AND NOT 403, because this one is not about the person. Nothing they can do differs
        # from what an administrator has to do, and a monitored 5xx is the only way anybody finds
        # out that the product has stopped being able to accept requests to join.
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=ACCESS_NOT_RECORDED_DETAIL,
            headers=_access_headers(access_roster.NOT_RECORDED),
        )
    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail=ACCESS_PENDING_DETAIL,
        headers=_access_headers(access_roster.PENDING),
    )


async def assert_roster_admits(user: Any) -> None:
    """THE SECOND GATE: a DESIGNER may sign in only while an ACTIVE **empanelment** carries them.

    NARROW ON PURPOSE, AND STILL NARROW AFTER THE PLATFORM GATE ARRIVED. This one is not the
    allow-list — :func:`assert_access_admits` above is, and it gates everybody. This asks the older
    and more specific question: is this person still empanelled as a designer by the institution?
    Two distinct decisions, two distinct remedies, and one sentence each:

    * an admin who revokes an empanelment ends the designer's *standing*, and the person is told
      their designer access was suspended;
    * an admin who bars somebody from the allow-list ends their *access to the application*, and
      the person is told that instead.

    Collapsing the pair would mean revoking an empanelment silently locks the person out of the
    whole product — including a professor or an admin who happens to be on the designer roster
    because they run workshops too, whose account has nothing to do with the empanelment being
    ended. Ranks below designer are not gated by this one for the reason they never were: refusing
    a researcher for not being a designer is not a rule anybody wrote.

    Runs AFTER the platform gate, so a suspended designer who is otherwise admitted still reads the
    empanelment sentence rather than a generic one — the specific answer wins where both apply.

    **AND SINCE THE CROSS-ROSTER MIRROR LANDED, "OTHERWISE ADMITTED" IS DOING MORE WORK IN THAT
    SENTENCE THAN IT USED TO. READ THIS BEFORE CONCLUDING THE GATE IS BROKEN.** An administrator who
    ends an empanelment through ``/admin/designers`` now also suspends the allow-list row that
    admission rested on (``app.services.access_roster.mirror_suspension``, an owner's decision, made
    because the two screens were showing contradictory standing for one person and the workshop
    pickers were offering designers who could not sign in). So for a person whose account IS a
    DESIGNER and whose allow-list row admitted them AS a designer, both gates now refuse, the
    platform one runs first, and they read ``ACCESS_SUSPENDED_DETAIL`` with header ``SUSPENDED``
    rather than this function's pair. That is not a sentence changing its words — all five are
    exactly as they were, and each still fires for the state that produces it — it is one population
    moving from the second refusal to the first, because that population is now genuinely barred
    from the application and telling them otherwise would be the untrue answer.

    **THE PARAGRAPH ABOVE IS STILL LOAD-BEARING, WHICH IS WHY THE MIRROR IS GUARDED AND NOT
    UNCONDITIONAL.** The professor and the admin it names are exactly who
    ``access_roster.admissions_an_empanelment_carries`` refuses to mirror onto: their place in this
    product does not rest on an empanelment, so ending one leaves their access alone and they never
    reach the platform refusal at all. This function keeps its own sentence for them, for anybody
    admitted at another tier, and for every empanelment suspended by a path that predates the
    mirror. Deleting either the guard or this gate on the theory that the mirror has made them
    redundant re-opens the outage this docstring was written about.
    """
    if role_value(user) != "DESIGNER":
        return
    if await roster_allows(getattr(user, "email", None)):
        return
    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail=DESIGNER_SUSPENDED_DETAIL,
        # Its OWN status, never the allow-list's SUSPENDED. The two refusals have two different
        # remedies — "ask to be empanelled again" versus "ask to be let back into the application"
        # — and a client that collapsed them would print the wrong one of the two on the one screen
        # where the reader has no other way to find out what happened.
        headers=_access_headers(DESIGNER_SUSPENDED_STATUS),
    )


def verify_google_token(token: str) -> dict[str, Any]:
    settings = get_settings()
    if not settings.google_client_ids:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Google OAuth is not configured on this server",
        )
    last_error: ValueError | None = None
    for client_id in settings.google_client_ids:
        try:
            return google_id_token.verify_oauth2_token(
                token,
                google_requests.Request(),
                client_id,
            )
        except ValueError as exc:
            last_error = exc
            logger.info("Google token rejected for configured audience %s: %s", client_id, exc)
    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid Google ID token"
    ) from last_error


async def login_with_google(token: str) -> tuple[Any, Any | None]:
    """The Google branch: verify, decide admission, and only then provision.

    Returns ``(user, access_row)``. The allow-list row travels back to :func:`login` rather than
    being read a second time there — one row, read once, so the two reads cannot disagree about
    whether this sign-in was somebody's first.
    """
    id_info = verify_google_token(token)

    if not id_info.get("email") or not id_info.get("email_verified"):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Google account email is not verified",
        )

    email = id_info["email"].lower()
    settings = get_settings()
    role = role_for_email(email)
    name = (
        settings.master_admin_name
        if role == "MASTER_ADMIN"
        else id_info.get("name") or email.split("@")[0]
    )
    avatar_url = id_info.get("picture")

    # ADMISSION IS DECIDED HERE, BEFORE ANY WRITE, AND THIS IS THE BIGGEST BEHAVIOURAL CHANGE IN THE
    # FEATURE. Until this line existed, a verified Google token for ANY address on earth reached the
    # `db.user.create` at the bottom of this function and walked away with an account and a bearer
    # token: the product's sign-up page was Google's, and its allow-list was "has a Google account".
    # An unadmitted address now becomes a PENDING row and a 403 that says so, and NO `User` row is
    # created — which is the point, because a self-provisioned account is not a request an admin can
    # approve or reject, it is a decision already taken.
    #
    # BEFORE, not after, and not folded into `login` with the password path. Every other write in
    # this function — the rename, the avatar, the master-admin elevation, the designer promotion —
    # is a side effect of signing in, and running them first would mean a refused stranger had
    # already edited the database on their way to being told no.
    access = await assert_access_admits(email, is_master=role == "MASTER_ADMIN")
    # The tier a brand-new account is created at, when an admin has chosen one on the allow-list
    # row. NULL there means DEFAULT_SIGNUP_ROLE — the lowest rung, which is this platform's
    # documented default for a new joiner and is what `role_for_email` already returned.
    if role != "MASTER_ADMIN":
        role = access_roster.role_of(access) or role

    # AN ACTIVE ROSTER ROW IS AN INSTRUCTION TO PROVISION A DESIGNER, and this is where it is
    # carried out. An admin empanels somebody who has never opened the app; there is no account
    # to promote yet, and there is no invitation email in this product to send them. What there
    # is, is the moment they first sign in with Google — so the roster row is read here and the
    # account is created at, or lifted to, DESIGNER on the spot. Without this the admin's only
    # remaining move is to wait for the person to sign in as a volunteer, notice it happened, and
    # promote them by hand, which is exactly the manual step the roster exists to remove.
    rostered = await roster_allows(email)

    # ── THE ACCOUNT IS STILL KEYED ON THE EXACT ADDRESS GOOGLE SENT, AND THAT IS A KNOWN GAP ──────
    #
    # Both gates above now ask about the MAILBOX: `access_row` and `roster_allows` look up the
    # literal address AND its Gmail-canonical form in one query, so an admin's dots no longer decide
    # whether somebody may sign in (see `app.services.designers.canonical_email`). `User` is NOT a
    # roster and is deliberately not part of that change — this line is an account-identity lookup,
    # not an admission decision, and widening it would change which existing account a sign-in
    # attaches to, which is not a question Fix 2 was asked to answer.
    #
    # WHAT THAT LEAVES OPEN, so the next reader does not have to find it the hard way: an account
    # created by hand through `POST /api/users` at `sandy.craft3@gmail.com`, whose owner then signs
    # in through Google as `sandycraft3@gmail.com`, is admitted by the gates and then MISSES here —
    # so a second `User` row is created for one person, and their workshops end up split across two
    # accounts. It needs Google's own claim and an admin's typing to disagree, which is why it is
    # rare; it is not impossible, and the fix is a decision about account identity (fold the two, or
    # refuse the second and say so) rather than another canonicalisation call.
    existing = await db.user.find_unique(where={"email": email})
    if existing:
        data = {"name": name, "avatarUrl": avatar_url, "authProvider": "GOOGLE"}
        if role == "MASTER_ADMIN":
            data["role"] = "MASTER_ADMIN"
            data["canManageQuestionnaire"] = True
        elif rostered and role_rank(existing) < ROLE_RANK["DESIGNER"]:
            # PROMOTE ONLY, NEVER DEMOTE. The comparison is strictly-below rather than
            # not-equal for one reason: an admin or a professor whose email is also on the
            # roster (they run workshops too) would otherwise be knocked down to DESIGNER by
            # their own next sign-in — losing their admin rights to a row they added to help
            # somebody else, with the demotion invisible in the login response.
            data["role"] = "DESIGNER"
        updated = await db.user.update(
            where={"email": email},
            data=data,
        )
        # Sign-in is a WRITE to the identity the rest of the app authorises against: it can rename
        # the account and, for the master-admin email, hand it MASTER_ADMIN and
        # canManageQuestionnaire. Drop the cached row so the token minted below is never validated
        # against the pre-login one. (Keyed by id, which is why the update's return value is used.)
        invalidate_cached_user(updated.id)
        return updated, access
    if rostered and ROLE_RANK.get(role, 0) < ROLE_RANK["DESIGNER"]:
        # A brand-new account for an empanelled email. It would otherwise be created at
        # DEFAULT_SIGNUP_ROLE — a crowdsource volunteer — and the designer's first experience of
        # the app an admin invited them to would be a home screen with no way to start a workshop.
        role = "DESIGNER"
    created = await db.user.create(
        data={
            "email": email,
            "name": name,
            "avatarUrl": avatar_url,
            "authProvider": "GOOGLE",
            "role": role,
            "canManageQuestionnaire": role == "MASTER_ADMIN",
        }
    )
    invalidate_cached_user(created.id)
    return created, access


@router.post("/login", response_model=TokenResponse)
async def login(payload: LoginRequest) -> dict[str, Any]:
    # ONE SIGN-IN DOOR, FOR BOTH CREDENTIALS. There used to be a `POST /auth/google` beside this —
    # four lines that rejected a body without a `googleIdToken` and then called straight into here.
    # No client ever called it: `AuthProvider.loginWithGoogle` posts `{ googleIdToken }` to
    # `/auth/login`, and `WorkshopRepositoryApi` declares `@POST("auth/login")` for both the
    # password and the Google sign-in. An uncalled second door is not free — everything below this
    # line is admission policy (the platform allow-list, the designer roster, the "first seen"
    # stamp), and the next refusal or audit write added here would have been added to the door
    # somebody uses and not to the one nobody exercises or tests. So it was removed rather than
    # kept in step by hand. Do not re-add an alias: add the client, not the route.
    if payload.googleIdToken:
        # The allow-list is consulted INSIDE this call, before it writes anything. See its
        # docstring: on the Google path admission is what decides whether an account exists at all,
        # so it cannot be checked out here after the fact.
        user, access = await login_with_google(payload.googleIdToken)
    else:
        # ── THREE IDENTIFIER SPACES IN, ONE ACCOUNT OUT, AND THE GATES BELOW NEVER LEARN ──────
        #
        # `resolve_identifier` reads an email, a phone number or an empanelment number and hands
        # back at most one `User`. From the next line down there is one account and one
        # `user.email`, which is what every gate in this file and in services/designers.py is
        # keyed on — `assert_access_admits`, `access_row`, `roster_allows`, `ensure_empanelled`,
        # `mark_roster_seen`, and both rosters' `@@unique` on email. **Nothing below was widened,
        # reworded or skipped to make a phone login work**: the identifier is resolved to an
        # ACCOUNT first and the account's address is what is gated, exactly as before.
        lookup = await identity.resolve_identifier(payload.email)
        if lookup.outcome == identity.AMBIGUOUS:
            # BEFORE any credential check, and that is the one place in this function where a
            # refusal precedes the password. It has to: there is no account to check a password
            # against, and asking for the password again would loop somebody whose typing is
            # correct. It leaks that two accounts share a number, which is the only fact that
            # makes the sentence actionable.
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail=AMBIGUOUS_IDENTIFIER_DETAIL,
                headers=_hint_headers(AMBIGUOUS_IDENTIFIER_HINT),
            )
        user = lookup.user
        if user is not None and user.passwordHash is None:
            # "HAS NO PASSWORD" AND "SIGNS IN WITH GOOGLE" WERE ONE STATE UNTIL `passwordSetAt`
            # EXISTED, and `verify_password` answers False for both — so both used to read as a
            # mistyped password. See PASSWORD_NOT_SET_DETAIL for the bit this leaks and why the
            # owner's ruling above already accepted that trade.
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail=PASSWORD_NOT_SET_DETAIL,
                headers=_hint_headers(PASSWORD_NOT_SET_HINT),
            )
        # ── THE PER-ACCOUNT GUESSING BUDGET, TAKEN HERE AND AT TWO OTHER DOORS ────────────────
        #
        # THIS BLOCK USED TO BE HEADED "TAKEN HERE AND NOWHERE ELSE", and on 2026-09-03 that
        # stopped being something to be pleased about and started being the finding. Two other
        # places in this API verify a password, and neither of them was charging anything:
        # `routes/datasets.mint_dataset_token` (email + password for a thirty-day read token over
        # the whole repository — a strictly better credential than the one this line protects) and
        # `change_password` at the foot of this file (the CURRENT password, on a stolen session).
        # Both now take and refund the SAME per-account bucket this line does, so an account is one
        # allowance across all three doors rather than three allowances an attacker can pick from.
        # See the second banner in `app/scale/rate_limit.py` for the whole argument.
        #
        # `app/scale/rate_limit.py` is ASGI middleware: it runs before this handler has resolved
        # anything, so it can only key on the network the request came from. That budget stays
        # exactly as it is and is still the right shape for what it does. What it cannot see is
        # the ACCOUNT — and an attacker spread across a botnet, or simply behind a different
        # mobile network each time, never meets it twice for one victim.
        #
        # This is the other half, and it is taken at the first moment the account is known and
        # BEFORE bcrypt, which is the expensive part. Keyed on `user.id`, which is what collapses
        # the three identifier spaces into one bucket: the same account guessed at by email, by
        # phone and by empanelment number spends one budget, not three.
        #
        # Take-then-refund, like the middleware, for the middleware's reason: a hundred parallel
        # guesses would otherwise all pass a check-then-charge before any of them was counted.
        if user is not None:
            allowed, _retry = account_credential_attempt(user.id)
            if not allowed:
                raise HTTPException(
                    status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                    detail=ACCOUNT_THROTTLED_DETAIL,
                )
        if not user or not verify_password(payload.password or "", user.passwordHash):
            # UNCHANGED, AND DELIBERATELY SO. An unknown identifier and a wrong password are one
            # answer, and it is not the "awaiting approval" answer: somebody who mistyped their
            # password must be sent to the password, not to an administrator. The whole point of
            # the distinct refusal below is that it is DISTINCT from this one.
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid email or password",
            )
        # The password was right, so nothing was spent. See the module docstring of
        # app/scale/rate_limit.py: charging attempts rather than failures would lock a whole
        # village office out of an app they are typing the correct password into.
        account_credential_refund(user.id)
        # AFTER THE CREDENTIAL, and that ordering is a security property rather than a style
        # choice. It is what keeps the pending refusal reachable only by somebody who has PROVED
        # they hold the account — so a stranger cannot use this endpoint to discover which
        # addresses are waiting on an admin, and cannot write rows into the admin's queue. The
        # master admin is exempt by role AND by configured address, so a break-glass account whose
        # row was never written still gets in.
        access = await assert_access_admits(user.email, is_master=is_break_glass_master(user))

    # AUTO-EMPANELMENT: SOMEBODY THE ALLOW-LIST ADMITS AS A DESIGNER IS EMPANELLED BY DEFAULT.
    #
    # An admin who admits an address as a designer has empanelled them, and until this line existed
    # the product disagreed: the allow-list promoted the account to DESIGNER and the empanelment
    # gate below then refused it, telling the person their "designer access has been suspended"
    # about an empanelment nobody had ever granted, with the roster screen showing no row at all to
    # explain it. See :func:`app.services.designers.ensure_empanelled`, which will only ever CREATE
    # a row — a suspended one is a revocation and is left exactly where the admin left it.
    #
    # **THIS EXACT PLACE, AND NEITHER EARLIER NOR LATER.**
    #
    # Not earlier, because the role is not settled until here. On the Google branch the account is
    # promoted to DESIGNER *inside* ``login_with_google``, out of ``AccessRoster.admitRole``, so
    # anything upstream of that call reads the role the account held BEFORE this sign-in — a person
    # an admin has just admitted as a designer is still a volunteer at that point and would not be
    # empanelled at all, which is precisely the case this feature exists for. Putting it in the two
    # branches instead would mean writing it twice, and a rule written twice is one door that
    # quietly stops enforcing it; here it covers the password path and the Google path together.
    #
    # Not later, because :func:`assert_roster_admits` on the next line is what refuses a DESIGNER
    # who has no ACTIVE roster row. After it, this call could only ever run for somebody who
    # already had one — dead code that empanels nobody, on the one path where it is needed.
    #
    # And before ``mark_roster_seen`` below, deliberately: a row created here carries a NULL
    # ``firstSeenAt``, so the stamp is written by this very sign-in and means what it says.
    #
    # The two conditions are both required. ``role_value`` and not ``user.role`` for the trap that
    # helper exists for — Prisma hands back an enum member on a live row and a bare string on
    # anything hand-built, and the raw comparison silently answers False for the first of those.
    # ``access_roster.admits(access)`` and not merely "there is a row": a PENDING or SUSPENDED
    # allow-list row is not an admission and must not empanel anybody. Today
    # ``assert_access_admits`` can only have returned an ACTIVE row or None (the master admin, who
    # is never gated and is not a designer), so the second test is belt-and-braces — it is here so
    # that it stays true if that function ever learns to return a row it did not admit.
    if role_value(user) == "DESIGNER" and access_roster.admits(access):
        # No ``actor_id``: nobody administered this. It was derived from the person's own sign-in,
        # and naming an admin who took no action would be a fabricated audit trail.
        await ensure_empanelled(user.email)

    # THE SECOND GATE, and still narrow: this one asks whether a DESIGNER is still empanelled, and
    # answers with the empanelment's own sentence. After the platform gate and after the Google
    # path has had its chance to promote a newly empanelled account — checking earlier would refuse
    # the very designer the roster was added to admit.
    await assert_roster_admits(user)
    # Only now: "first seen" means the first time somebody actually got IN, so a suspended
    # designer's rejected attempt must not consume the stamp an admin reads as "the invitation
    # was accepted". Both rosters carry the stamp, for the same reason, on the same rule.
    await mark_roster_seen(user.email)
    await access_roster.mark_access_seen(access)

    # ── "THE FIRST TIME THIS ACCOUNT GOT IN", WHICH THE TWO ROSTERS ABOVE CANNOT ANSWER ────────
    #
    # Both of those stamp an ADDRESS an administrator invited. This stamps the ACCOUNT, and the
    # two differ for everybody whose account predates the roster row that admits it — which, after
    # the grandfathering migration, is every account that existed before the allow-list shipped.
    # Written once and never moved, so "has this person ever actually used the product" stays
    # answerable.
    #
    # ONE EXTRA WRITE, AND ONLY EVER ONCE PER ACCOUNT. The guard is a `getattr` off the row
    # already in hand, so an account that has signed in before pays nothing at all.
    if getattr(user, "firstLoginAt", None) is None:
        await db.user.update(where={"id": user.id}, data={"firstLoginAt": datetime.now(UTC)})
        invalidate_cached_user(user.id)

    # ── THE USAGE-CONSENT GATE, AT THE ONE POINT BOTH CREDENTIALS JOIN ──────────────────────────
    #
    # Both paths are here and neither is anywhere else: the password branch above proved a bcrypt
    # hash, the Google branch proved an audience inside `login_with_google`, and from this line down
    # there is one `user`. Putting the gate in the two branches instead would mean writing the rule
    # twice, and a rule written twice is one door that quietly stops enforcing it — the same argument
    # the auto-empanelment block twenty lines up makes for its own position.
    #
    # **IT ADMITS THE SIGN-IN AND REPORTS "CONSENT REQUIRED". IT DOES NOT REFUSE, AND THAT IS A
    # DECISION RATHER THAN A SHORTCUT.** The requirement is a blocking agreement — refuse and you
    # cannot use the product — and it is enforced by the CLIENTS, which will not leave the consent
    # screen until `usageConsentGate.required` is false. Four reasons this server reports rather than
    # refuses, in the order they decide it:
    #
    #   1. **A 403 here is a gate nobody can get through.** The only way to record an answer is
    #      `POST /api/usage/consent`, which needs a bearer token. Refuse before minting one and an
    #      un-consented account can never consent: the product would be permanently unusable for
    #      every account that has not answered, which on the day this ships is every account there
    #      has ever been. That alone settles it.
    #   2. **The client cannot SHOW the consent screen without a session.** It has to read the
    #      notice, and — for a person who has already answered an older version — the answer they
    #      previously gave, so the screen can say "this has changed" instead of "please agree".
    #   3. **THE BREAK-GLASS MASTER ADMIN MUST NOT BE REACHABLE BY THIS.** `assert_access_admits`
    #      exempts that account by name (`is_break_glass_master`) because "a break-glass that lives
    #      in the same table it is protecting against is not a break-glass", and the whole argument
    #      for widening the platform gate to everybody rests on there always being one account that
    #      can get in and let people back in. A refusal here would be a SECOND lockout, on a column
    #      no allow-list screen can edit, reachable by a bug in one boolean — and it would need its
    #      own exemption, which is a second break-glass to keep in step with the first. Reporting
    #      needs no exemption at all: the master admin signs in, the gate says `required: true`, and
    #      nothing about their access depends on the answer.
    #   4. **An admin can always undo a bad state**, because there is no bad state to undo. Nothing
    #      here can strand an account: the answer is the account's own to give, at a route that needs
    #      no permission from anybody, and there is no admin-only step in between.
    #
    # WHERE THE ENFORCEMENT ACTUALLY LIVES, said plainly so nobody adds a second copy: in the
    # clients, at the screen. A server-side belt — if one is ever wanted — belongs on the PROTECTED
    # routes as a dependency, never as a refusal at this door, for reason 1.
    #
    # NOTHING IS WRITTEN HERE. Reading the gate is a `getattr` and a string comparison on the row
    # already in hand; the sign-in path pays no query for it, and this line adds no failure mode to
    # a route whose failures lock people out of the product.
    payload = serialize_user(user)
    if payload[USAGE_CONSENT_GATE_KEY]["required"]:
        # At INFO, once per sign-in, because the interesting operational question the day this ships
        # is "how many people are still being asked" — and the answer is otherwise only visible by
        # querying the accounts table.
        logger.info(
            "auth: %s signed in and still owes an answer on usage recording (%s)",
            user.email,
            payload[USAGE_CONSENT_GATE_KEY]["state"],
        )

    access_token = create_access_token(
        subject=user.id,
        extra_claims={"email": user.email, "role": enum_value(user.role)},
    )
    return {"accessToken": access_token, "tokenType": "bearer", "user": payload}


@router.post("/logout")
async def logout() -> dict[str, bool]:
    return {"ok": True}


@router.get("/me")
async def me(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return serialize_user(current_user)


# ==================================================================================================
# PASSWORD LINKS: the whole mechanism, delivered by the administrator copying it
# ==================================================================================================
#
# Owner, 2026-08-30: *"implement all the measures, we will use just admin copies the link for now
# though."* So: a single-use expiring token bound to the account's credential state, revocation, a
# per-account issuing throttle, session revocation on redemption, and a transport behind an
# interface — with the one shipped transport being "hand it to the admin to copy". **No mail
# dependency was added.** See app/services/credential_links.py, which carries the whole argument
# and names what was and was not ported from C:/dev/cxa-cms.


def _link_payload(issued: credential_links.DeliveredLink) -> dict[str, Any]:
    """The answer to an issuing request.

    **THE TOKEN IS NOT A FIELD HERE, AND THAT IS DELIBERATE AND PORTED.** cxa-cms's canonical route
    refuses to return the token separately from the link on the grounds that a credential appearing
    twice in one answer is a credential in two places to keep out of logs. The link contains it;
    nothing needs it twice.
    """
    return {
        "id": issued.id,
        "link": issued.link,
        "expiresAt": issued.expiresAt,
        "purpose": issued.purpose,
        "deliveredBy": issued.deliveredBy,
    }


@router.post("/password-links", status_code=status.HTTP_201_CREATED)
async def issue_password_link(
    payload: IssuePasswordLinkRequest, current_user: Any = Depends(require_admin)
) -> dict[str, Any]:
    """Mint a set-password link for another account. Admin only.

    ADMIN AND NOT MASTER ADMIN, matching ``POST /api/users`` — the account that can CREATE somebody
    with a password of the admin's choosing can obviously hand them a link to change it, and gating
    the safer of the two more tightly would only push admins back to typing passwords for people.

    THE THROTTLE IS PER SUBJECT, NOT PER ADMIN. Redeeming a link revokes the account's sessions, so
    without it an administrator could sign a colleague out of their own laptop as often as they
    could press the button — the gap cxa-cms has and this deliberately does not copy. Two admins
    taking turns is the same harm, which is why the budget belongs to the person being reset.
    """
    target = await db.user.find_unique(where={"id": payload.userId})
    if target is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    try:
        issued = await credential_links.issue_link(user=target, issued_by_id=current_user.id)
    except credential_links.IssueThrottled as exc:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=(
                "Several password links have already been issued for this account. Wait an hour, "
                "or ask the person to use one of them."
            ),
            headers={"retry-after": str(exc.retry_after_minutes * 60)},
        ) from exc
    logger.info(
        "auth: %s issued a %s password link for %s",
        current_user.email,
        issued.purpose,
        target.email,
    )
    return _link_payload(issued)


@router.post("/password-links/{link_id}/revoke")
async def revoke_password_link(
    link_id: str, current_user: Any = Depends(require_admin)
) -> dict[str, bool]:
    """Withdraw a link that has not been used yet — "I pasted that into the wrong window".

    The fingerprint alone cannot answer this: the account's password has not changed, so the token
    still verifies and would go on working until it expired. This is the reason the table exists.
    """
    found = await credential_links.revoke_link(link_id)
    if not found:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Link not found")
    logger.info("auth: %s revoked password link %s", current_user.email, link_id)
    return {"ok": True}


@router.get("/set-password")
async def check_set_password_token(token: str = "") -> dict[str, Any]:
    """Is this link still good? Unauthenticated, because the person cannot sign in — that is the
    whole point of holding one.

    **IT ANSWERS ABOUT THE LINK AND NEVER ABOUT THE ACCOUNT.** No email, no name, no role. A valid
    link is held only by somebody an administrator handed it to, but this endpoint is reachable by
    anybody with a guess, and a body that named the account would turn a forged-token probe into an
    account lookup. The screen has no need for it either: the person knows whose password they are
    setting.

    The reason IS returned, because every one of them has a different next action — expired means
    "ask for another", revoked means "ask the administrator what happened", used means "you already
    set it, go and sign in" — and a single "invalid link" leaves a person with none of them.
    """
    verdict = await credential_links.describe_token(token)
    return {"valid": verdict.ok, "reason": verdict.reason, "purpose": verdict.purpose}


@router.post("/set-password")
async def set_password(payload: SetPasswordRequest) -> dict[str, bool]:
    """Redeem a link. Four checks, and the fingerprint is the one that cannot be skipped.

    ``describe_token`` runs the signature, the shape, the expiry, the row (revoked? already used?)
    AND the credential fingerprint. A caller that skipped the last of those would have built a link
    that works for ever.

    ── WHAT A REDEMPTION WRITES, AND WHY EACH OF THE FIVE IS THERE ───────────────────────────────

    * ``passwordHash`` — the point.
    * ``passwordSetAt`` — so "has never had a password" stays distinguishable from "signs in with
      Google", which is the state this whole column was added for.
    * ``mustChangePassword: False`` — the person has now chosen their own; that is exactly what the
      flag was waiting for.
    * ``sessionsValidFrom`` — **SESSION REVOCATION, PORTED FROM cxa-cms AND THE REASON THE COLUMN
      EXISTS.** The usual reason somebody is resetting is that a session they no longer control is
      live somewhere; leaving it live would make the reset theatre. Every token minted before this
      instant is refused by ``deps._user_from_bearer``.
    * the ``usedAt`` stamp on the row — belt to the fingerprint's braces, and what lets the screen
      say "this link has already been used" instead of a bare refusal.
    """
    verdict = await credential_links.describe_token(payload.token)
    if not verdict.ok:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=_SET_PASSWORD_REFUSALS.get(
                verdict.reason or "", "This password link is not valid."
            ),
        )
    now = datetime.now(UTC)
    await db.user.update(
        where={"id": verdict.user_id},
        data={
            "passwordHash": hash_password(payload.password),
            "passwordSetAt": now,
            "mustChangePassword": False,
            "sessionsValidFrom": now,
        },
    )
    invalidate_cached_user(verdict.user_id)
    await credential_links.mark_used(payload.token)
    logger.info("auth: password set through a %s link for account %s", verdict.purpose, verdict.user_id)
    return {"ok": True}


#: One sentence per refusal, because each has a different next action. Kept beside the route rather
#: than in the service: the service answers WHY in a word a client can branch on, and the words a
#: person reads are this layer's job.
_SET_PASSWORD_REFUSALS = {
    credential_links.MISSING: "This link is incomplete. Open the whole link the administrator sent.",
    credential_links.MALFORMED: "This is not a link this site issued. Ask the administrator for another.",
    credential_links.EXPIRED: "This link has expired. Ask the administrator for a new one.",
    credential_links.REVOKED: "This link was withdrawn. Ask the administrator for a new one.",
    credential_links.SPENT: "This link has already been used. Sign in with the password you set.",
    credential_links.UNKNOWN_ACCOUNT: "This link no longer points at an account.",
}


@router.post("/change-password")
async def change_password(
    payload: ChangePasswordRequest, current_user: Any = Depends(get_current_user)
) -> dict[str, bool]:
    """The signed-in account replacing its own password. The route ``mustChangePassword`` sends
    somebody to.

    THE CURRENT PASSWORD IS REQUIRED EVEN WHEN ``mustChangePassword`` IS SET. That flag means "the
    password you hold was typed for you by an administrator", not "anybody at this keyboard may
    replace it" — and the person always has the password, because they used it to get the token they
    are calling this with.

    AN ACCOUNT WITH NO PASSWORD CANNOT USE THIS ROUTE and is told which route it should use. There
    is nothing to prove here, and accepting an empty current password would turn a stolen Google
    session into a permanent password on the account.

    IT DOES NOT REVOKE SESSIONS, unlike a link redemption, and the difference is who is asking. A
    person changing their own password from inside a session they are using has not lost control of
    anything; signing them out of their own phone for tidiness is a worse answer than leaving it.

    **AND THE PER-ACCOUNT GUESSING BUDGET CLOSES THE OTHER HALF OF THAT SAME ARGUMENT, 2026-09-03.**
    The paragraph above says this route exists so that a stolen session cannot become a permanent
    takeover: the thief holds a token, not the password, so they cannot set a new one. That was only
    half enforced. The token also let them GUESS the current password here, without limit and
    without ever meeting a refusal — the ASGI limiter in ``app/scale/rate_limit.py`` keys anonymous
    callers by address and this route is reached with a bearer token, and there was no per-account
    charge on this path at all. So a stolen session was an offline-speed oracle against one specific
    account's password, running at whatever rate bcrypt allows, with the prize being exactly the
    permanent takeover this route was written to prevent. The same take-then-refund the sign-in door
    uses now sits in front of that check, spending the SAME bucket keyed on ``user.id``: ten wrong
    guesses in five minutes across every door in the API, not ten per door.

    Charged BEFORE ``verify_password`` and refunded the moment it passes, for the reason
    ``routes/auth.login`` gives at length — a check-then-charge would let a hundred parallel guesses
    all pass the check before any of them was counted — and refunded on every outcome that is not a
    wrong password, so somebody who types their own password correctly spends nothing however often
    they change it. The 400 above is not charged either: an account with no password to compare
    against has had nothing guessed at it.
    """
    if current_user.passwordHash is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=(
                "This account has no password to change. Ask an administrator for a "
                "set-password link."
            ),
        )
    allowed, _retry = account_credential_attempt(current_user.id)
    if not allowed:
        # The sign-in door's sentence, deliberately verbatim rather than a second wording of the
        # same fact: it is the same budget, and a person who has just been refused at one door and
        # reads a different explanation at the other has been told there are two limits.
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=ACCOUNT_THROTTLED_DETAIL,
        )
    if not verify_password(payload.currentPassword, current_user.passwordHash):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="Current password is incorrect"
        )
    # The password was right, so nothing was spent — the same refund, on the same rule, as the
    # sign-in path. Everything from here down is a write that cannot be a wrong password.
    account_credential_refund(current_user.id)
    await db.user.update(
        where={"id": current_user.id},
        data={
            "passwordHash": hash_password(payload.newPassword),
            "passwordSetAt": datetime.now(UTC),
            "mustChangePassword": False,
        },
    )
    invalidate_cached_user(current_user.id)
    return {"ok": True}
