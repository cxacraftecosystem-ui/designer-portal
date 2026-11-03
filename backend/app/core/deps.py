import asyncio
import time
from collections import OrderedDict
from decimal import Decimal, InvalidOperation
from typing import Any

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from starlette.requests import HTTPConnection

from app.core.config import get_settings
from app.core.db import db
from app.core.security import CREDENTIAL_CLAIM, decode_access_token

from app.services import access_roster, credential_links

from app.services.usage import USAGE_CONSENT_KEY, USAGE_USER_ID_KEY, resolve_consent

bearer_scheme = HTTPBearer(auto_error=False)


def get_value(obj: Any, field: str) -> Any:
    if isinstance(obj, dict):
        return obj.get(field)
    return getattr(obj, field, None)


def role_value(user: Any) -> str:
    role = get_value(user, "role")
    return str(getattr(role, "value", role))


ROLE_RANK: dict[str, int] = {
    "CROWDSOURCE_VOLUNTEER": 10,
    "FIELD_CONTRIBUTOR": 20,
    "RESEARCHER": 30,
    "DESIGNER": 35,
    "INSPECTOR": 37,
    "PROFESSOR": 40,
    "ASSISTANT_DIRECTOR": 42,
    "REGIONAL_DIRECTOR": 45,
    "MINISTRY_ADMIN": 48,
    "ADMIN": 50,
    "MASTER_ADMIN": 60,
}

ROLE_LABELS: dict[str, str] = {
    "CROWDSOURCE_VOLUNTEER": "Crowdsource Volunteer",
    "FIELD_CONTRIBUTOR": "Field Contributor",
    "RESEARCHER": "Researcher",
    "DESIGNER": "Designer",
    "INSPECTOR": "Inspector / Reviewer",
    "PROFESSOR": "Professor",
    "ASSISTANT_DIRECTOR": "Assistant Director",
    "REGIONAL_DIRECTOR": "Regional Director",
    "MINISTRY_ADMIN": "Ministry Admin",
    "ADMIN": "Admin",
    "MASTER_ADMIN": "Master Admin",
}


def role_rank(user_or_role: Any) -> int:
    role = user_or_role if isinstance(user_or_role, str) else role_value(user_or_role)
    return ROLE_RANK.get(str(role), 0)


def has_rank(user: Any, role: str) -> bool:
    return role_rank(user) >= ROLE_RANK[role]


def is_admin(user: Any) -> bool:
    return role_value(user) in {"MASTER_ADMIN", "ADMIN"}


def is_master_admin(user: Any) -> bool:
    return role_value(user) == "MASTER_ADMIN"


CONTENT_ADMIN_ROLES = frozenset({"ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"})


def is_content_admin(user: Any) -> bool:
    return role_value(user) in CONTENT_ADMIN_ROLES


CONTENT_DELETE_REFUSAL = "Only an admin can delete this."


def is_break_glass_master(user: Any) -> bool:
    if is_master_admin(user):
        return True
    return is_configured_master_admin(user)


def is_configured_master_admin(user: Any) -> bool:
    address = str(get_value(user, "email") or "").strip().lower()
    configured = (get_settings().master_admin_email or "").strip().lower()
    return bool(address) and address == configured


def can_manage_questionnaire(user: Any) -> bool:
    return has_rank(user, "PROFESSOR") or bool(get_value(user, "canManageQuestionnaire"))


def can_manage_crafts(user: Any) -> bool:
    return has_rank(user, "PROFESSOR")


DESIGN_WORKSHOP_ROLES = frozenset(
    {
        "DESIGNER",
        "MINISTRY_ADMIN",
        "REGIONAL_DIRECTOR",
        "ASSISTANT_DIRECTOR",
        "ADMIN",
        "MASTER_ADMIN",
    }
)


def can_run_design_workshops(user: Any) -> bool:
    return role_value(user) in DESIGN_WORKSHOP_ROLES


DESIGN_WORKSHOP_CREATOR_ROLES = frozenset({"ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"})


def can_create_design_workshops(user: Any) -> bool:
    return role_value(user) in DESIGN_WORKSHOP_CREATOR_ROLES


DESIGN_WORKSHOP_CREATE_REFUSAL = (
    "Only admins, the master admin and the Ministry Admin can start a new design & prototype "
    "workshop. Ask an admin to "
    "create it for your cluster and give you access — you can then fill in all 22 stages, add "
    "artisans, products and photographs, and generate the report exactly as before. Any workshop "
    "you already have access to is open to you now."
)


def assert_can_create_design_workshops(user: Any) -> None:
    if not can_create_design_workshops(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=DESIGN_WORKSHOP_CREATE_REFUSAL,
        )


DESIGN_WORKSHOP_DATA_VIEW_ROLES = frozenset(
    {
        "PROFESSOR",
        "ASSISTANT_DIRECTOR",
        "REGIONAL_DIRECTOR",
        "MINISTRY_ADMIN",
        "ADMIN",
        "MASTER_ADMIN",
    }
)

DESIGN_WORKSHOP_DATA_EXPORT_ROLES = frozenset({"ADMIN", "MASTER_ADMIN"})


def can_view_design_workshop_data(user: Any) -> bool:
    return role_value(user) in DESIGN_WORKSHOP_DATA_VIEW_ROLES


def can_export_design_workshop_data(user: Any) -> bool:
    return is_admin(user)


MINISTRY_DASHBOARD_ROLES = frozenset(
    {"ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN", "MASTER_ADMIN"}
)

MINISTRY_DASHBOARD_REFUSAL = (
    "For Assistant Directors, Regional Directors, Ministry Admins and the master admin. "
    "Admins: use Cross-workshop analytics. Designers: use Design workshops."
)


def can_see_ministry_dashboard(user: Any) -> bool:
    return role_value(user) in MINISTRY_DASHBOARD_ROLES


def can_manage_designer_roster(user: Any) -> bool:
    return is_admin(user)


def can_manage_access_roster(user: Any) -> bool:
    return is_admin(user)


ACCOUNT_PROVISIONER_ROLES = frozenset({"MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"})


def can_provision_accounts(user: Any) -> bool:
    return role_value(user) in ACCOUNT_PROVISIONER_ROLES


APPROVAL_AUTHORITY_ROLES = frozenset({"MINISTRY_ADMIN", "MASTER_ADMIN"})

APPROVAL_AUTHORITY_REFUSAL = (
    "Reports to approve belongs to the Ministry Admin and the master admin: they approve a design "
    "workshop's report, send it back or withdraw an approval, and hand it on to the office. Assistant "
    "and Regional Directors read the workshops they are named on in Workshops I monitor, and "
    "inspectors file their corrections in Workshops to inspect."
)


def can_approve_design_workshops(user: Any) -> bool:
    return role_value(user) in APPROVAL_AUTHORITY_ROLES if user is not None else False


def can_read_usage(user: Any) -> bool:
    return is_admin(user)


def can_read_person_usage(user: Any) -> bool:
    return is_master_admin(user)


def can_manage_workshops(user: Any) -> bool:
    return has_rank(user, "PROFESSOR")


def can_review_record(reviewer: Any, creator_role: Any) -> bool:
    if is_master_admin(reviewer):
        return True
    role = getattr(creator_role, "value", creator_role)
    if not role:
        role = "RESEARCHER"
    return role_rank(reviewer) > ROLE_RANK.get(str(role), ROLE_RANK["RESEARCHER"])


def can_edit_others_record(user: Any, creator_role: Any) -> bool:
    return has_rank(user, "PROFESSOR") and can_review_record(user, creator_role)


async def may_edit_lower_ranked_record(user: Any, creator_id: str | None) -> bool:
    if not creator_id or not has_rank(user, "PROFESSOR"):
        return False
    creator = await db.user.find_unique(where={"id": creator_id})
    return can_edit_others_record(user, get_value(creator, "role") if creator else None)


def can_access_review(user: Any) -> bool:
    return has_rank(user, "FIELD_CONTRIBUTOR") or bool(get_value(user, "canReview"))


def can_review(user: Any) -> bool:
    return can_access_review(user)


def can_download_dataset(user: Any) -> bool:
    return has_rank(user, "PROFESSOR") or bool(get_value(user, "canDownloadDataset"))


def can_create_records(user: Any) -> bool:
    return has_rank(user, "RESEARCHER")


_USER_CACHE_INFLIGHT_WAIT_SECONDS = 5.0

_user_cache: OrderedDict[str, tuple[float, Any]] = OrderedDict()
_user_cache_inflight: dict[str, tuple[Any, asyncio.Future[Any]]] = {}
_user_cache_epoch = 0


class _LeaderGone(Exception):
    pass


def invalidate_cached_user(user_id: str | None) -> None:
    global _user_cache_epoch
    _user_cache_epoch += 1
    if user_id:
        _user_cache.pop(user_id, None)


def clear_user_cache() -> None:
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
    loop = asyncio.get_running_loop()
    pending = _user_cache_inflight.get(user_id)
    if pending is not None and pending[0] is loop:
        try:
            return await asyncio.wait_for(
                asyncio.shield(pending[1]), _USER_CACHE_INFLIGHT_WAIT_SECONDS
            )
        except (_LeaderGone, TimeoutError):
            pass

    future: asyncio.Future[Any] = loop.create_future()
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
    settings = get_settings()
    if not settings.auth_user_cache_enabled:
        return await db.user.find_unique(where={"id": user_id})
    cached = _cached_user(user_id)
    if cached is not None:
        return cached
    return await _load_user(user_id, settings.auth_user_cache_ttl_seconds)


DATASET_READ_SCOPE = "dataset:read"

TOKEN_SCOPE_CLAIM = "scope"

SESSION_ENDED_DETAIL = "This session is no longer valid. Sign in again."


def password_credential(user: Any) -> str:
    return credential_links.credential_fingerprint(get_value(user, "passwordHash"))


async def _user_from_bearer(
    credentials: HTTPAuthorizationCredentials | None,
    *,
    allowed_scopes: frozenset[str],
) -> Any:
    if not credentials:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Sign in to continue.")
    try:
        payload = decode_access_token(credentials.credentials)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=str(exc)) from exc

    scope = payload.get(TOKEN_SCOPE_CLAIM)
    if scope is not None and str(scope) not in allowed_scopes:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "This sign-in can only be used to download datasets. Sign in with your email "
                "and password to use the rest of the app."
            ),
        )

    user_id = payload.get("sub")
    if not user_id:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail=SESSION_ENDED_DETAIL
        )

    user = await resolve_user(user_id)
    if not user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="This account no longer exists. Sign in again."
        )

    revoked_before = getattr(user, "sessionsValidFrom", None)
    if revoked_before is not None:
        issued_at = payload.get("iat")
        cutoff = revoked_before.timestamp()
        if not isinstance(issued_at, int | float) or issued_at < cutoff:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail=SESSION_ENDED_DETAIL
            )

    if CREDENTIAL_CLAIM in payload:
        presented = payload[CREDENTIAL_CLAIM]
        if not (
            isinstance(presented, str)
            and presented.isascii()
            and credential_links.fingerprint_matches(get_value(user, "passwordHash"), presented)
        ):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail=SESSION_ENDED_DETAIL
            )
    return user


PASSWORD_CHANGE_REQUIRED_HEADER = "X-Password-Change-Required"

PASSWORD_CHANGE_REQUIRED_DETAIL = "Choose a new password to continue."

PASSWORD_CHANGE_ALLOWED_ROUTES: frozenset[tuple[str, str]] = frozenset(
    {
        ("GET", "/api/me"),
        ("GET", "/api/auth/me"),
        ("POST", "/api/auth/change-password"),
        ("POST", "/api/auth/logout"),
        ("GET", "/api/usage/consent"),
        ("POST", "/api/usage/consent"),
        ("GET", "/api/app/release/latest"),
    }
)


def password_change_pending(user: Any) -> bool:
    if not get_value(user, "mustChangePassword"):
        return False
    if not get_value(user, "passwordHash"):
        return False
    return not is_configured_master_admin(user)


def _route_path(connection: HTTPConnection) -> str:
    path = str(connection.scope.get("path") or "")
    root = str(connection.scope.get("root_path") or "")
    if root and path.startswith(root) and path[len(root) : len(root) + 1] in ("/", ""):
        return path[len(root) :] or "/"
    return path


def refuse_while_password_change_pending(user: Any, connection: HTTPConnection) -> None:
    if not password_change_pending(user):
        return
    method = str(connection.scope.get("method") or "").upper()
    if (method, _route_path(connection)) in PASSWORD_CHANGE_ALLOWED_ROUTES:
        return
    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail=PASSWORD_CHANGE_REQUIRED_DETAIL,
        headers={PASSWORD_CHANGE_REQUIRED_HEADER: "1"},
    )


async def get_current_user(
    connection: HTTPConnection,
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
) -> Any:
    user = await _user_from_bearer(credentials, allowed_scopes=frozenset())
    refuse_while_password_change_pending(user, connection)
    setattr(connection.state, USAGE_USER_ID_KEY, get_value(user, "id"))
    setattr(connection.state, USAGE_CONSENT_KEY, resolve_consent(user))
    from app.services.app_versions import note_user

    note_user(connection, getattr(user, "id", None))
    return user


async def require_dataset_admin(
    connection: HTTPConnection,
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
) -> Any:
    user = await _user_from_bearer(credentials, allowed_scopes=frozenset({DATASET_READ_SCOPE}))
    refuse_while_password_change_pending(user, connection)
    if not is_admin(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only admins can download datasets.",
        )
    if not is_break_glass_master(user):
        row = await access_roster.access_row(getattr(user, "email", None))
        if access_roster.status_of(row) in access_roster.BARRED:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=(
                    "This account's access has been withdrawn, so it can't download datasets. "
                    "Ask an administrator to restore the account's access."
                ),
            )
    return user


async def require_admin(current_user: Any = Depends(get_current_user)) -> Any:
    if not is_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin access required")
    return current_user


ACCOUNT_PROVISIONER_REQUIRED_DETAIL = (
    "Creating accounts and resetting passwords requires Ministry Admin access or above."
)


async def require_account_provisioner(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_provision_accounts(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail=ACCOUNT_PROVISIONER_REQUIRED_DETAIL
        )
    return current_user


async def require_approving_authority(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_approve_design_workshops(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=APPROVAL_AUTHORITY_REFUSAL)
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


async def require_usage_reader(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_read_usage(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Only admins can see platform usage. Yours is in Settings."
            ),
        )
    return current_user


async def require_person_usage_reader(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_read_person_usage(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Only the master admin can see one person's activity. Admins can see platform "
                "totals, and yours is in Settings."
            ),
        )
    return current_user


def assert_can_create_records(user: Any) -> None:
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
    if not can_manage_designer_roster(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Managing the designer roster requires Admin access or above.",
        )
    return current_user


async def require_access_manager(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_manage_access_roster(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Managing who may sign in requires Admin access or above.",
        )
    return current_user


async def require_workshop_manager(current_user: Any = Depends(get_current_user)) -> Any:
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


def assert_can_contribute_fields(
    record: Any, user: Any, data: dict[str, Any], owner_field: str = "createdById"
) -> None:
    if is_content_admin(user) or get_value(record, owner_field) == get_value(user, "id"):
        return

    locked_fields = [
        field
        for field, next_value in data.items()
        if not is_empty_value(get_value(record, field))
        and (is_empty_value(next_value) or not values_match(get_value(record, field), next_value))
    ]
    if locked_fields:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Only the person who recorded it or an admin can change or clear these filled-in fields: {', '.join(sorted(locked_fields))}",
        )


def assert_can_contribute_relation(
    record: Any, user: Any, populated: bool, field_name: str, owner_field: str = "createdById"
) -> None:
    if is_content_admin(user) or get_value(record, owner_field) == get_value(user, "id"):
        return
    if populated:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Only the person who recorded it or an admin can change this filled-in link: {field_name}",
        )


def assert_owner_or_admin(record: Any, user: Any, owner_field: str = "createdById") -> None:
    if is_content_admin(user):
        return
    if get_value(record, owner_field) != get_value(user, "id"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only access records you created",
        )


def assert_admin_or_owner(record: Any, user: Any, owner_field: str = "createdById") -> None:
    assert_owner_or_admin(record, user, owner_field)


def assert_can_delete(user: Any) -> None:
    if not is_content_admin(user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=CONTENT_DELETE_REFUSAL)


async def require_content_admin(current_user: Any = Depends(get_current_user)) -> Any:
    if not is_content_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin access required")
    return current_user


async def require_designer(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_run_design_workshops(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "A designer profile belongs to the people who run design & prototype workshops — "
                "designers, the Assistant Director, Regional Director and Ministry Admin posts, "
                "admins and the master admin."
            ),
        )
    return current_user
