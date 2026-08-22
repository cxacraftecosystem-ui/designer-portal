import logging
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
    role_rank,
    role_value,
)
from app.core.security import create_access_token, verify_password
from app.schemas.auth import LoginRequest, TokenResponse
from app.services import access_roster
from app.services.designers import mark_roster_seen, roster_allows

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
DESIGNER_SUSPENDED_DETAIL = (
    "Your designer access has been suspended. Contact the administrator."
)

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


def serialize_user(user: Any) -> dict[str, Any]:
    payload = jsonable_encoder(user)
    payload.pop("passwordHash", None)
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
    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid Google ID token") from last_error


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
    name = settings.master_admin_name if role == "MASTER_ADMIN" else id_info.get("name") or email.split("@")[0]
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
        user = await db.user.find_unique(where={"email": payload.email.lower()})
        if not user or not verify_password(payload.password or "", user.passwordHash):
            # UNCHANGED, AND DELIBERATELY SO. An unknown address and a wrong password are one
            # answer, and it is not the "awaiting approval" answer: somebody who mistyped their
            # password must be sent to the password, not to an administrator. The whole point of
            # the distinct refusal below is that it is DISTINCT from this one.
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid email or password",
            )
        # AFTER THE CREDENTIAL, and that ordering is a security property rather than a style
        # choice. It is what keeps the pending refusal reachable only by somebody who has PROVED
        # they hold the account — so a stranger cannot use this endpoint to discover which
        # addresses are waiting on an admin, and cannot write rows into the admin's queue. The
        # master admin is exempt by role AND by configured address, so a break-glass account whose
        # row was never written still gets in.
        access = await assert_access_admits(user.email, is_master=is_break_glass_master(user))

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

    access_token = create_access_token(
        subject=user.id,
        extra_claims={"email": user.email, "role": enum_value(user.role)},
    )
    return {"accessToken": access_token, "tokenType": "bearer", "user": serialize_user(user)}


@router.post("/logout")
async def logout() -> dict[str, bool]:
    return {"ok": True}


@router.get("/me")
async def me(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return serialize_user(current_user)
