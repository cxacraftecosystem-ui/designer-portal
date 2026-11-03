from collections.abc import Sequence
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from typing import Any, NamedTuple

from fastapi import HTTPException, status
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.user_fields import SECRET_USER_FIELDS
from app.services import aadhaar_fingerprint
from app.services.artisan_identity import mask_aadhaar
from app.services.concurrency import gather_reads

from app.services.dictation_consent import MEDIA_TAG
from app.services.measurement_provenance import MARKER_BODY_KEY, method_stamps
from app.services.rich_text import search_needles
from app.services.text_format import title_case_fields
from prisma import Json

_SENSITIVE_KEYS = frozenset(SECRET_USER_FIELDS)

_AADHAAR_SENTINEL = "aadhaarVerifyStatus"
_AADHAAR_RESULT_KEYS = (
    "aadhaarVerifyStatus", "aadhaarVerifyMethod", "aadhaarVerifiedAt", "aadhaarVerifyLast4",
    "aadhaarVerifyName", "aadhaarVerifyBirthYear", "aadhaarVerifyDob", "aadhaarVerifyGender",
    "aadhaarVerifySignedAt", "aadhaarVerifyTxn", "aadhaarVerifyNameMatch", "aadhaarVerifyById",
    "aadhaarVerifyDecidedById", "aadhaarVerifyDecidedAt",
)

_IDENTITY_KEYS = ("aadhaarNumber", "pehchanCardNumber")

_MEDIA_URL_KEYS = ("url", "publicUrl", "objectKey")

_TRANSCRIPT_KEYS = ("transcriptText", "transcriptSummary")

_MEDIA_TAKEABLE_KEYS = (*_MEDIA_URL_KEYS, *_TRANSCRIPT_KEYS)

_MEDIA_MARKER = "objectKey"


def _sign_media_url(node: dict[str, Any], ttl_seconds: int) -> bool:
    object_key = node.get(_MEDIA_MARKER)
    stored = node.get("url")
    if not object_key or not isinstance(stored, str) or not stored:
        return False
    from app.api.routes.media import signed_read_headers
    from app.services.s3 import presign_get_url

    mime_type, disposition = signed_read_headers(node.get("mimeType"))
    try:
        node["url"] = presign_get_url(
            str(object_key),
            filename=str(node.get("originalFilename") or object_key),
            mime_type=mime_type,
            expires_in=ttl_seconds,
            disposition=disposition,
        )
    except Exception:  # noqa: BLE001 — see FAILURE above: fall back to the stored URL, never 500.
        return False
    return True


ALL_MEDIA_URLS = None


class _Unset:
    def __repr__(self) -> str:  # pragma: no cover - debugging aid only
        return "<unset>"


_UNSET = _Unset()


def presigned_read_ttl() -> int | None:
    from app.core.config import get_settings

    try:
        settings = get_settings()
    except Exception:  # noqa: BLE001 — see above: no environment means no change in behaviour.
        return None
    if not getattr(settings, "media_presigned_reads", False):
        return None
    ttl = int(getattr(settings, "media_presigned_read_ttl_seconds", 0) or 0)
    return ttl if ttl > 0 else None


def signed_only_ttl() -> int:
    from app.core.config import get_settings

    try:
        ttl = int(getattr(get_settings(), "media_presigned_read_ttl_seconds", 0) or 0)
    except Exception:  # noqa: BLE001 — no environment: the documented default.
        ttl = 0
    return ttl if ttl > 0 else 900


def derive_age(date_of_birth: Any, *, on: datetime | None = None) -> int | None:
    if not date_of_birth:
        return None
    if isinstance(date_of_birth, str):
        try:
            date_of_birth = datetime.fromisoformat(date_of_birth.replace("Z", "+00:00"))
        except ValueError:
            return None
    born = getattr(date_of_birth, "date", lambda: date_of_birth)()
    today = (on or datetime.now(UTC)).date()
    years = today.year - born.year - ((today.month, today.day) < (born.month, born.day))
    return years if 0 <= years <= 130 else None


def derive_experience_years(craft_start: Any, *, on: datetime | None = None) -> int | None:
    if not craft_start:
        return None
    if isinstance(craft_start, str):
        try:
            craft_start = datetime.fromisoformat(craft_start.replace("Z", "+00:00"))
        except ValueError:
            return None
    started = getattr(craft_start, "date", lambda: craft_start)()
    today = (on or datetime.now(UTC)).date()
    years = today.year - started.year - ((today.month, today.day) < (started.month, started.day))
    return years if 0 <= years <= 90 else None


def mask_identity_number(value: Any) -> Any:
    return mask_aadhaar(value)


_NESTED_IDENTITY_MASK = "XXXX XXXX XXXX"


_PROVENANCE_STAMP_KEYS = frozenset(
    {
        "by",
        "byName",
        "at",
        "method",
        "methodProvider",
        "methodModelId",
        "methodConfidence",
        "methodTechnique",
    }
)


def _mask_identity_node(node: Any) -> Any:
    from app.services.access import (
        REDACTED_CHANGE_KEYS,
        REDACTED_PLACEHOLDER_PAIRS,
        redacted_placeholder,
    )

    if isinstance(node, dict):
        keys = set(node)
        if (
            keys == REDACTED_CHANGE_KEYS
            and node.get("redacted") is True
            and (node.get("old"), node.get("new")) in REDACTED_PLACEHOLDER_PAIRS
        ):
            return node
        if keys <= REDACTED_CHANGE_KEYS and ("old" in keys or "new" in keys):
            return redacted_placeholder(node.get("old"), node.get("new"))
        if keys and keys <= _PROVENANCE_STAMP_KEYS:
            return node
    return _NESTED_IDENTITY_MASK


def _redact_sensitive(
    value: Any,
    viewer_id: str | None,
    unmasked: bool,
    media_urls: set[str] | None = None,
    media_workshops: frozenset[str] = frozenset(),
    presign_ttl: int | None = None,
    signed_only: bool = False,
) -> Any:
    if isinstance(value, dict):
        for key in _SENSITIVE_KEYS:
            value.pop(key, None)
        if _AADHAAR_SENTINEL in value:
            for key in _AADHAAR_RESULT_KEYS:
                value.pop(key, None)
        if (
            aadhaar_fingerprint.FINGERPRINT_FIELD in value
            or aadhaar_fingerprint.LAST4_FIELD in value
        ) and not isinstance(
            value.get(aadhaar_fingerprint.LAST4_FIELD, value.get(aadhaar_fingerprint.FINGERPRINT_FIELD)),
            (dict, list),
        ):
            aadhaar_fingerprint.public_view(value)
        if not unmasked and not (viewer_id and value.get("createdById") == viewer_id):
            for key in _IDENTITY_KEYS:
                if key in value:
                    column = value[key]
                    value[key] = (
                        _mask_identity_node(column)
                        if isinstance(column, (dict, list))
                        else mask_identity_number(column)
                    )
        if _MEDIA_MARKER in value:
            if (
                media_urls is not None
                and value.get("uploadedById") not in media_urls
                and not (
                    value.get("linkedRecordType") == MEDIA_TAG
                    and value.get("linkedRecordId") in media_workshops
                )
            ):
                for key in _MEDIA_TAKEABLE_KEYS:
                    value.pop(key, None)
            elif presign_ttl is not None:
                signed = _sign_media_url(value, presign_ttl)
                if signed_only:
                    for key in _MEDIA_URL_KEYS:
                        if key != "url" or not signed:
                            value.pop(key, None)
        for nested in value.values():
            _redact_sensitive(
                nested,
                viewer_id,
                unmasked,
                media_urls,
                media_workshops,
                presign_ttl,
                signed_only,
            )
    elif isinstance(value, list):
        for item in value:
            _redact_sensitive(
                item, viewer_id, unmasked, media_urls, media_workshops, presign_ttl, signed_only
            )
    return value


def public_encode(
    obj: Any,
    viewer: Any = None,
    *,
    media_urls: Any = _UNSET,
    media_workshops: frozenset[str] = frozenset(),
    signed_only: bool = False,
) -> Any:
    from app.core.deps import get_value, has_rank

    encoded = jsonable_encoder(obj)
    presign_ttl = presigned_read_ttl()
    if signed_only and presign_ttl is None:
        presign_ttl = signed_only_ttl()
    if viewer is None:
        allowed: set[str] | None = set() if media_urls is _UNSET else media_urls
        return _redact_sensitive(
            encoded,
            viewer_id=None,
            unmasked=False,
            media_urls=allowed,
            media_workshops=media_workshops,
            presign_ttl=presign_ttl,
            signed_only=signed_only,
        )

    viewer_id = get_value(viewer, "id")
    if media_urls is _UNSET:
        if has_rank(viewer, "PROFESSOR"):
            allowed = ALL_MEDIA_URLS
        else:
            allowed = {viewer_id} if viewer_id else set()
    else:
        allowed = media_urls
    return _redact_sensitive(
        encoded,
        viewer_id=viewer_id,
        unmasked=has_rank(viewer, "PROFESSOR"),
        media_urls=allowed,
        media_workshops=media_workshops,
        presign_ttl=presign_ttl,
        signed_only=signed_only,
    )


async def media_url_scope(viewer: Any) -> tuple[set[str] | None, frozenset[str]]:
    from app.core.deps import get_value

    uploaders = await media_url_owners(viewer)
    if uploaders is ALL_MEDIA_URLS:
        return ALL_MEDIA_URLS, frozenset()
    viewer_id = get_value(viewer, "id")
    if not viewer_id:
        return uploaders, frozenset()
    return uploaders, frozenset(await _design_workshop_media_ids(viewer_id))


async def media_url_owners(viewer: Any) -> set[str] | None:
    from app.core.db import db
    from app.core.deps import get_value, has_rank

    if has_rank(viewer, "PROFESSOR"):
        return ALL_MEDIA_URLS
    viewer_id = get_value(viewer, "id")
    if not viewer_id:
        return set()
    grants = await db.dataaccessgrant.find_many(where={"granteeId": viewer_id, "status": "GRANTED"})
    return {viewer_id, *(grant.ownerId for grant in grants)}


def to_json(value: Any) -> Any:
    if isinstance(value, (dict, list)):
        return Json(value)
    return value


def jsonify_metadata(data: dict[str, Any], *fields: str) -> dict[str, Any]:
    keys = fields or ("extraMetadata", "measurementAnalysis", "result")
    for key in keys:
        if key in data and isinstance(data[key], (dict, list)):
            data[key] = Json(data[key])
    return data


CLEARABLE_KEYS = frozenset(
    {
        "workshopId",
        "designWorkshopId",
        "craftId",
        "artisanId",
        "productId",
        "toolId",
        "processId",
        "locationId",
        "questionnaireInterviewId",
        "aadhaarNumber",
        "pehchanCardNumber",
    }
)


TITLE_CASE_FIELDS = frozenset(
    {
        "name",
        "craftName",
        "artisanName",
        "productName",
        "toolkitName",
        "englishName",
        "title",
        "place",
        "placeName",
        "village",
        "district",
        "state",
    }
)


def clean_data(
    data: dict[str, Any],
    *,
    title_case: bool = True,
    clearable: Sequence[str] | frozenset[str] = (),
) -> dict[str, Any]:
    allowed = CLEARABLE_KEYS | frozenset(clearable) if clearable else CLEARABLE_KEYS
    cleaned = {key: value for key, value in data.items() if value is not None or key in allowed}
    return title_case_fields(cleaned, TITLE_CASE_FIELDS) if title_case else cleaned


def decimal_to_string(data: dict[str, Any]) -> dict[str, Any]:
    converted: dict[str, Any] = {}
    for key, value in data.items():
        if isinstance(value, Decimal):
            converted[key] = str(value)
        elif isinstance(value, dict):
            converted[key] = decimal_to_string(value)
        else:
            converted[key] = value
    return converted


_STATED_ADDRESS_FROM_META: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("district", ("district",)),
    ("village", ("village",)),
    ("subjectLatitude", ("subjectLatitude", "artisanLatitude")),
    ("subjectLongitude", ("subjectLongitude", "artisanLongitude")),
)


def lift_stated_address(location_data: dict[str, Any]) -> dict[str, Any]:
    meta = location_data.get("extraMetadata")
    if not isinstance(meta, dict):
        return location_data
    for column, keys in _STATED_ADDRESS_FROM_META:
        if location_data.get(column) not in (None, ""):
            continue
        for key in keys:
            value = meta.get(key)
            if value not in (None, ""):
                location_data[column] = value
                break
    return location_data


async def resolve_craft_id(
    data: dict[str, Any], current_user: Any, *, stored: Any | None = None, strict: bool = False
) -> dict[str, Any]:
    del current_user
    from app.services import craft_master

    return await craft_master.resolve_record_craft(db, data, stored=stored, strict=strict)


async def attach_location(data: dict[str, Any]) -> dict[str, Any]:
    location = data.pop("location", None)
    if location:
        location_data = location.model_dump() if hasattr(location, "model_dump") else dict(location)
        location_data = lift_stated_address(location_data)
        created = await db.location.create(data=jsonify_metadata(clean_data(location_data)))
        data["locationId"] = created.id
    return data


_UNSEARCHABLE = {c: None for c in range(32) if c not in (9, 10, 13)}


_LIKE_METACHARACTERS = ("\\", "%", "_")


def contains(value: str) -> dict[str, Any]:
    escaped = value.translate(_UNSEARCHABLE)
    for character in _LIKE_METACHARACTERS:
        escaped = escaped.replace(character, "\\" + character)
    return {"contains": escaped, "mode": "insensitive"}


def prose_contains(field: str, value: str) -> dict[str, Any]:
    needles = search_needles(value)
    if len(needles) == 1:
        return {field: contains(needles[0])}
    return {"OR": [{field: contains(needle)} for needle in needles]}


def plain(value: str) -> str:
    return value.translate(_UNSEARCHABLE)


RECORD_STATUSES = frozenset({"DRAFT", "PENDING", "APPROVED", "REJECTED", "NEEDS_REVISION"})
MEDIA_TYPES = frozenset({"IMAGE", "VIDEO", "AUDIO", "PDF", "DOCUMENT", "OTHER"})


def enum_filter_or_422(value: str, allowed: frozenset[str], *, field: str = "status") -> str:
    if value not in allowed:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"{field} must be one of {', '.join(sorted(allowed))}",
        )
    return value



async def viewable_where(user: Any, owner_field: str = "createdById") -> dict[str, Any]:
    return {}


async def owned_or_granted_where(user: Any, owner_field: str = "createdById") -> dict[str, Any]:
    from app.core.deps import get_value, has_rank

    if has_rank(user, "PROFESSOR"):
        return {}
    uid = get_value(user, "id")
    owner_relation = owner_field.removesuffix("Id")
    branches: list[dict[str, Any]] = [
        {owner_field: uid},
        {
            owner_relation: {
                "is": {"dataAccessAsOwner": {"some": {"granteeId": uid, "status": "GRANTED"}}}
            }
        },
    ]
    if owner_field == "uploadedById":
        branches.extend(await _design_workshop_media_branches(uid))
    return {"OR": branches}


async def _design_workshop_media_branches(user_id: str) -> list[dict[str, Any]]:
    ids = await _design_workshop_media_ids(user_id)
    if not ids:
        return []
    return [{"linkedRecordType": MEDIA_TAG, "linkedRecordId": {"in": ids}}]


async def _design_workshop_media_ids(user_id: str) -> list[str]:
    from app.services.design_workshop_viewers import visible_to_clause

    if not user_id:
        return []
    rows = await db.designworkshop.find_many(
        where={"deletedAt": None, **visible_to_clause(user_id)}
    )
    return [row.id for row in rows]


async def own_rows_where(user: Any, owner_field: str = "createdById") -> dict[str, Any]:
    from app.core.deps import get_value

    return {owner_field: get_value(user, "id")}


def apply_status_policy_create(user: Any, data: dict[str, Any]) -> dict[str, Any]:
    from app.core.deps import has_rank

    if has_rank(user, "PROFESSOR"):
        data.setdefault("status", "APPROVED")
    else:
        data["status"] = "PENDING"
    return data


async def apply_status_policy_update(
    user: Any, record: Any, data: dict[str, Any]
) -> dict[str, Any]:
    from app.core.deps import can_review_record, enum_or_raw, get_value, has_rank

    if "status" not in data:
        return data
    new_status = str(enum_or_raw(data["status"]))
    current = str(enum_or_raw(get_value(record, "status")))
    if new_status != current and has_rank(user, "PROFESSOR"):
        creator_id = get_value(record, "createdById")
        if creator_id is not None and creator_id == get_value(user, "id"):
            return data
        creator = await db.user.find_unique(where={"id": creator_id}) if creator_id else None
        if can_review_record(user, get_value(creator, "role") if creator else None):
            return data
    data.pop("status", None)
    return data


def add_date_range(
    where: dict[str, Any], field: str, date_from: datetime | None, date_to: datetime | None
) -> None:
    range_filter: dict[str, Any] = {}
    if date_from:
        range_filter["gte"] = date_from
    if date_to:
        range_filter["lte"] = date_to
    if range_filter:
        where[field] = range_filter


async def require_record(delegate: Any, record_id: str) -> Any:
    record = await delegate.find_unique(where={"id": record_id})
    if not record:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record


async def require_live_record(delegate: Any, record_id: str) -> Any:
    record = await require_record(delegate, record_id)
    if getattr(record, "deletedAt", None) is not None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record


CLIENT_KEY_FIELD = "clientKey"


def is_client_key_violation(error: Exception) -> bool:
    text = str(error)
    return "unique" in text.lower() and CLIENT_KEY_FIELD in text


async def client_key_replay(
    delegate: Any,
    client_key: str | None,
    *,
    user_id: str,
    include: dict[str, Any] | None = None,
) -> Any | None:
    if not client_key:
        return None
    kwargs: dict[str, Any] = {"where": {CLIENT_KEY_FIELD: client_key}}
    if include:
        kwargs["include"] = include
    existing = await delegate.find_unique(**kwargs)
    if existing is None:
        return None
    if getattr(existing, "createdById", None) != user_id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="That record belongs to another account.",
        )
    return existing


async def client_key_replay_after_violation(
    delegate: Any,
    client_key: str | None,
    error: Exception,
    *,
    user_id: str,
    include: dict[str, Any] | None = None,
) -> Any | None:
    if not client_key or not is_client_key_violation(error):
        return None
    return await client_key_replay(delegate, client_key, user_id=user_id, include=include)


EXPECTED_UPDATED_AT_FIELD = "expectedUpdatedAt"

EXPECTED_UPDATED_AT_TOLERANCE = timedelta(seconds=1)


def take_expected_updated_at(data: dict[str, Any]) -> datetime | None:
    value = data.pop(EXPECTED_UPDATED_AT_FIELD, None)
    return value if isinstance(value, datetime) else None


def assert_expected_updated_at(record: Any, expected: datetime | None) -> None:
    if expected is None:
        return
    stored = getattr(record, "updatedAt", None)
    if not isinstance(stored, datetime):
        return
    if expected.tzinfo is None:
        expected = expected.replace(tzinfo=UTC)
    if stored.tzinfo is None:
        stored = stored.replace(tzinfo=UTC)
    if abs(stored - expected) <= EXPECTED_UPDATED_AT_TOLERANCE:
        return
    raise _record_changed(expected, stored)


async def claim_expected_updated_at(delegate: Any, record: Any, expected: datetime | None) -> None:
    if expected is None:
        return
    stored = getattr(record, "updatedAt", None)
    if not isinstance(stored, datetime):
        return
    if expected.tzinfo is None:
        expected = expected.replace(tzinfo=UTC)
    if stored.tzinfo is None:
        stored = stored.replace(tzinfo=UTC)
    if _to_millisecond(stored) != _to_millisecond(expected):
        raise _record_changed(expected, stored)
    claimed = await delegate.update_many(
        where={"id": record.id, "updatedAt": stored}, data={"updatedAt": datetime.now(UTC)}
    )
    if claimed:
        return
    current = await delegate.find_unique(where={"id": record.id})
    now_stored = getattr(current, "updatedAt", None)
    raise _record_changed(expected, now_stored if isinstance(now_stored, datetime) else stored)


def _to_millisecond(value: datetime) -> datetime:
    return value.replace(microsecond=value.microsecond - value.microsecond % 1000)


def _record_changed(expected: datetime, stored: datetime) -> HTTPException:
    if stored.tzinfo is None:
        stored = stored.replace(tzinfo=UTC)
    return HTTPException(
        status_code=status.HTTP_409_CONFLICT,
        detail={
            "code": "record_changed",
            "message": "Someone else changed this record after this edit was composed.",
            "expectedUpdatedAt": expected.isoformat(),
            "currentUpdatedAt": stored.isoformat(),
        },
    )


EXPECTED_UPDATED_AT_MODELS = frozenset(
    {
        "artisan",
        "craft",
        "productdocumentation",
        "tooldocumentation",
        "process",
        "workshop",
        "questionnaireinterview",
    }
)


async def lock_and_assert_expected_updated_at(
    client: Any,
    model: str,
    record: Any,
    expected: datetime | None,
) -> None:
    if expected is None:
        return
    if model not in EXPECTED_UPDATED_AT_MODELS:
        raise ValueError(f"{model} is not a model with an expectedUpdatedAt precondition")
    assert_expected_updated_at(record, expected)
    read = getattr(record, "updatedAt", None)
    if not isinstance(read, datetime):
        return
    claimed = await getattr(client, model).update_many(
        where={"id": record.id, "updatedAt": read},
        data={"updatedAt": datetime.now(UTC)},
    )
    if not claimed:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={
                "code": "record_changed",
                "message": "Someone else changed this record after this edit was composed.",
                "expectedUpdatedAt": expected.isoformat(),
                "currentUpdatedAt": None,
            },
        )



class Relation(NamedTuple):
    field: str
    model: str
    key: str
    many: bool = False
    include: dict[str, Any] | None = None


def include_of(relations: Sequence[Relation]) -> dict[str, Any]:
    return {rel.field: ({"include": rel.include} if rel.include else True) for rel in relations}


async def hydrate_relations(rows: Sequence[Any], relations: Sequence[Relation]) -> None:
    if not rows or not relations:
        return

    planned: list[tuple[Relation, Any]] = []
    for rel in relations:
        if rel.many:
            ids = {row.id for row in rows}
        else:
            ids = {getattr(row, rel.key, None) for row in rows} - {None}
        if not ids:
            for row in rows:
                setattr(row, rel.field, [] if rel.many else None)
            continue
        args: dict[str, Any] = {"where": {(rel.key if rel.many else "id"): {"in": sorted(ids)}}}
        if rel.include:
            args["include"] = rel.include
        planned.append((rel, getattr(db, rel.model).find_many(**args)))

    if not planned:
        return
    fetched = await gather_reads(*(coro for _, coro in planned))

    for (rel, _), children in zip(planned, fetched):
        if rel.many:
            grouped: dict[Any, list[Any]] = {}
            for child in children:
                grouped.setdefault(getattr(child, rel.key, None), []).append(child)
            for row in rows:
                setattr(row, rel.field, grouped.get(row.id, []))
        else:
            by_id = {child.id: child for child in children}
            for row in rows:
                setattr(row, rel.field, by_id.get(getattr(row, rel.key, None)))


def with_id_tiebreak(order: Any) -> list[dict[str, Any]]:
    clauses = [dict(clause) for clause in (order if isinstance(order, list) else [order])]
    if any("id" in clause for clause in clauses):
        return clauses
    last = clauses[-1] if clauses else {}
    direction = next(iter(last.values()), "asc") if last else "asc"
    return [*clauses, {"id": direction if direction in ("asc", "desc") else "asc"}]


async def count_and_page(
    delegate: Any,
    *,
    where: dict[str, Any],
    skip: int,
    take: int,
    order: Any,
    relations: Sequence[Relation] = (),
) -> tuple[int, list[Any]]:
    total, items = await gather_reads(
        delegate.count(where=where),
        delegate.find_many(where=where, skip=skip, take=take, order=with_id_tiebreak(order)),
    )
    await hydrate_relations(items, relations)
    return total, items


PROVENANCE_SKIP_FIELDS = {
    "extraMetadata",
    "location",
    "locationId",
    "createdById",
    "createdAt",
    "updatedAt",
    "reviewedById",
    "reviewNotes",
    "reviewedAt",
    "recordedAt",
    "recordedTimezone",
    "measurementAnalysis",
    "measurementAnalysisStatus",
    "measurementImageId",
    CLIENT_KEY_FIELD,
    MARKER_BODY_KEY,
    aadhaar_fingerprint.LAST4_FIELD,
}

_EXTRA_NOT_CARRIED = frozenset({"workshopSubmission", "fieldProvenance"})


def merge_field_provenance(
    new_data: dict[str, Any], user: Any, previous: Any | None = None
) -> None:
    from app.core.deps import get_value, is_empty_value, values_match

    previous_extra = get_value(previous, "extraMetadata") if previous is not None else None
    if not isinstance(previous_extra, dict):
        previous_extra = {}
    incoming_extra = new_data.get("extraMetadata")
    incoming_extra = incoming_extra if isinstance(incoming_extra, dict) else {}

    base_extra: dict[str, Any] = {
        key: value for key, value in previous_extra.items() if key not in _EXTRA_NOT_CARRIED
    }
    base_extra.update(
        {key: value for key, value in incoming_extra.items() if key != "fieldProvenance"}
    )

    stamps = method_stamps(new_data.pop(MARKER_BODY_KEY, None), fields=new_data.keys())

    provenance: dict[str, Any] = {}
    if isinstance(previous_extra.get("fieldProvenance"), dict):
        provenance = dict(previous_extra["fieldProvenance"])

    stamp = {
        "by": get_value(user, "id"),
        "byName": get_value(user, "name"),
        "at": datetime.now(UTC).isoformat(),
    }

    for field, value in new_data.items():
        if field in PROVENANCE_SKIP_FIELDS or is_empty_value(value):
            continue
        previous_value = get_value(previous, field) if previous is not None else None
        if (
            previous is None
            or is_empty_value(previous_value)
            or not values_match(previous_value, value)
        ):
            key = (
                aadhaar_fingerprint.NUMBER_FIELD
                if field == aadhaar_fingerprint.FINGERPRINT_FIELD
                else field
            )
            provenance[key] = stamp | stamps.get(field, {})

    if provenance:
        base_extra["fieldProvenance"] = provenance
    if base_extra:
        new_data["extraMetadata"] = Json(base_extra)
    else:
        new_data.pop("extraMetadata", None)


def resubmit_status(record: Any, user: Any, data: dict[str, Any]) -> dict[str, Any]:
    from app.core.deps import get_value

    if "status" in data:
        return data
    current = get_value(record, "status")
    if str(getattr(current, "value", current)) != "NEEDS_REVISION":
        return data
    creator_id = get_value(record, "createdById")
    if creator_id is None or creator_id != get_value(user, "id"):
        return data
    data["status"] = "PENDING"
    return data


def review_update(status_value: str, notes: str | None, reviewer_id: str) -> dict[str, Any]:
    return {
        "status": status_value,
        "reviewNotes": notes,
        "reviewedById": reviewer_id,
        "reviewedAt": datetime.now(UTC),
    }


def relation_filter(field: str, value: str | None) -> dict[str, Any]:
    return {field: value} if value else {}


def media_relation_data(record_type: str | None, record_id: str | None) -> dict[str, Any]:
    if not record_type or not record_id:
        return {}
    normalized = record_type.lower()
    field_map = {
        "artisan": "artisanId",
        "craft": "craftId",
        "workshop": "workshopId",
        "product": "productId",
        "tool": "toolId",
        "questionnaire": "questionnaireInterviewId",
        "questionnaireinterview": "questionnaireInterviewId",
    }
    field = field_map.get(normalized)
    return {field: record_id} if field else {}
