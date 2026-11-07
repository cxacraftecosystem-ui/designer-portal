import asyncio
import io
import json
from collections.abc import AsyncIterator, Callable
from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from fastapi.responses import StreamingResponse

from app.api.routes.auth import assert_access_admits
from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    DATASET_READ_SCOPE,
    get_value,
    is_admin,
    is_break_glass_master,
    is_master_admin,
    password_change_pending,
    password_credential,
    require_dataset_admin,
)
from app.core.security import create_access_token, verify_password_or_decoy
from app.scale.rate_limit import (
    account_credential_attempt,
    account_credential_refund,
    address_credential_attempt,
)
from app.schemas.auth import DatasetTokenRequest
from app.services import csv_safe, naming, two_factor
from app.services.concurrency import gather_reads
from app.services.csv_export import record_media
from app.services.feedback_vocabulary import label_for
from app.services.identity import identifier_key, normalise_email
from app.services.media_naming import display_filename
from app.services.pagination import normalize_pagination, page_payload
from app.services.record_fields import sheet_columns, sheet_row
from app.services.record_filters import (
    artisan_workshop_clause,
    craft_workshop_clause,
    resolve_workshop_ids,
    tool_workshop_clause,
    workshop_clause,
)
from app.services.records import ALL_MEDIA_URLS, public_encode
from app.services.s3 import presign_get_url
from app.services.tool_scope import with_tool_scope

router = APIRouter(prefix="/datasets", tags=["datasets"])

STREAM_BATCH = 200

PRESIGN_TTL_SECONDS = 6 * 60 * 60


class Dataset:
    def __init__(
        self,
        *,
        name: str,
        delegate: str,
        label: str,
        description: str,
        kind: str | None,
        include: dict[str, Any],
        owner_field: str = "createdById",
        workshop_filter: str = "column",
        media_tags: tuple[str, ...] = (),
        flat_columns: tuple[tuple[str, Callable[[Any], Any]], ...] = (),
    ) -> None:
        self.name = name
        self.delegate = delegate
        self.label = label
        self.description = description
        self.kind = kind
        self.include = include
        self.owner_field = owner_field
        self.workshop_filter = workshop_filter
        self.media_tags = media_tags
        self.flat_columns = flat_columns

    @property
    def model(self) -> Any:
        return getattr(db, self.delegate)


def _person(row: Any, relation: str) -> str:
    return get_value(get_value(row, relation), "name") or ""


def _person_email(row: Any, relation: str) -> str:
    return get_value(get_value(row, relation), "email") or ""


DATASETS: dict[str, Dataset] = {
    "workshops": Dataset(
        name="workshops",
        delegate="workshop",
        label="Workshops",
        description="Field workshops: dates, place, linked artisans and crafts.",
        kind="workshop",
        include={
            "location": True,
            "createdBy": True,
            "media": {"where": {"removedAt": None}},
            "artisans": {"include": {"artisan": True}},
            "crafts": {"include": {"craft": True}},
        },
        workshop_filter="self",
    ),
    "crafts": Dataset(
        name="crafts",
        delegate="craft",
        label="Crafts",
        description="The craft vocabulary artisans, products and tools are classified against.",
        kind="craft",
        include={
            "workshop": True,
            "workshops": {"include": {"workshop": True}},
            "createdBy": True,
            "media": {"where": {"removedAt": None}},
        },
        workshop_filter="craft",
    ),
    "artisans": Dataset(
        name="artisans",
        delegate="artisan",
        label="Artisans",
        description="Artisan records, their craft, their workshops and their stated address.",
        kind="artisan",
        include={
            "craft": True,
            "location": True,
            "createdBy": True,
            "media": {"where": {"removedAt": None}},
            "workshops": {"include": {"workshop": True}},
        },
        workshop_filter="artisan",
    ),
    "products": Dataset(
        name="products",
        delegate="productdocumentation",
        label="Products",
        description="Product documentation: materials, dimensions, pricing and process links.",
        kind="product",
        include={"workshop": True, "createdBy": True, "media": {"where": {"removedAt": None}}, "location": True},
    ),
    "tools": Dataset(
        name="tools",
        delegate="tooldocumentation",
        label="Tools",
        description=(
            "Tool and toolkit documentation. A workshop filter matches the tool's own workshop "
            "and every workshop its linked artisans belong to."
        ),
        kind="tool",
        include=with_tool_scope({"createdBy": True, "media": {"where": {"removedAt": None}}, "location": True}),
        workshop_filter="tool",
    ),
    "processes": Dataset(
        name="processes",
        delegate="process",
        label="Processes",
        description="Step-by-step making processes, with their ordered steps.",
        kind="process",
        include={"steps": True, "product": True, "createdBy": True},
        media_tags=("process",),
    ),
    "interviews": Dataset(
        name="interviews",
        delegate="questionnaireinterview",
        label="Questionnaire interviews",
        description="Interviews with their answers and the artisans they cover.",
        kind="interview",
        include={
            "createdBy": True,
            "media": {"where": {"removedAt": None}},
            "artisans": {"include": {"artisan": True}},
            "responses": {"include": {"question": True}},
        },
    ),
    "feedback": Dataset(
        name="feedback",
        delegate="feedback",
        label="App feedback (satisfaction survey)",
        description=(
            "One standing row per account: an overall 1-5 rating, six per-aspect sub-ratings and "
            "five free-text prompts, revised in place. For the grievance/suggestion register see "
            "the feedback-reports dataset."
        ),
        kind=None,
        include={"user": True},
        owner_field="userId",
        workshop_filter="none",
        flat_columns=(
            ("User", lambda r: _person(r, "user")),
            ("Email", lambda r: _person_email(r, "user")),
            ("Role given", lambda r: get_value(r, "role")),
            ("Overall", lambda r: get_value(r, "rating")),
            ("Ease of use", lambda r: get_value(r, "easeOfUse")),
            ("Reliability", lambda r: get_value(r, "reliability")),
            ("Performance", lambda r: get_value(r, "performance")),
            ("Design", lambda r: get_value(r, "design")),
            ("Features", lambda r: get_value(r, "features")),
            ("Would recommend", lambda r: get_value(r, "recommend")),
            ("Likes most", lambda r: get_value(r, "likeMost")),
            ("To improve", lambda r: get_value(r, "improve")),
            ("Bugs", lambda r: get_value(r, "bugs")),
            ("Feature requests", lambda r: get_value(r, "featureRequests")),
            ("General comments", lambda r: get_value(r, "comment")),
            ("First given", lambda r: get_value(r, "createdAt")),
            ("Last updated", lambda r: get_value(r, "updatedAt")),
        ),
    ),
    "feedback-reports": Dataset(
        name="feedback-reports",
        delegate="feedbackreport",
        label="Feedback reports (grievances, suggestions, recommendations)",
        description=(
            "One row per thing somebody reported, never overwritten: its kind, severity, the area "
            "it is about, what was written, the app and version it came from, and the whole "
            "redressal trail - who acknowledged it, who resolved it, when, and what they said back."
        ),
        kind=None,
        include={"user": True, "acknowledgedBy": True, "resolvedBy": True},
        owner_field="userId",
        workshop_filter="none",
        flat_columns=(
            ("Reported by", lambda r: _person(r, "user")),
            ("Email", lambda r: _person_email(r, "user")),
            ("Kind", lambda r: label_for("kind", get_value(r, "kind"))),
            ("Severity", lambda r: label_for("severity", get_value(r, "severity"))),
            ("Area", lambda r: label_for("area", get_value(r, "area"))),
            ("Subject", lambda r: get_value(r, "subject")),
            ("Details", lambda r: get_value(r, "details")),
            ("Status", lambda r: label_for("status", get_value(r, "status"))),
            ("App", lambda r: label_for("client", get_value(r, "client"))),
            ("App version", lambda r: get_value(r, "clientVersion")),
            ("Platform", lambda r: get_value(r, "platform")),
            ("Screen", lambda r: get_value(r, "pagePath")),
            ("Acknowledged by", lambda r: _person(r, "acknowledgedBy")),
            ("Acknowledged at", lambda r: get_value(r, "acknowledgedAt")),
            ("Resolved by", lambda r: _person(r, "resolvedBy")),
            ("Resolved at", lambda r: get_value(r, "resolvedAt")),
            ("Response to reporter", lambda r: get_value(r, "responseNote")),
            ("Reported at", lambda r: get_value(r, "createdAt")),
            ("Last changed", lambda r: get_value(r, "updatedAt")),
        ),
    ),
    "media": Dataset(
        name="media",
        delegate="mediafile",
        label="Media files",
        description=(
            "Every uploaded photograph, recording, video and document, with the record it is "
            "attached to. Pass presign=true for time-limited direct download URLs."
        ),
        kind=None,
        include={"uploadedBy": True},
        owner_field="uploadedById",
    ),
}


def _dataset_or_404(name: str) -> Dataset:
    dataset = DATASETS.get(name)
    if dataset is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Unknown dataset '{name}'. Available: {', '.join(sorted(DATASETS))}.",
        )
    return dataset


def _build_where(
    dataset: Dataset,
    *,
    workshop_ids: list[str] | None,
    created_by: str | None,
    updated_since: datetime | None,
    created_since: datetime | None,
) -> dict[str, Any]:
    clauses: list[dict[str, Any]] = []

    resolved = resolve_workshop_ids(workshop_ids)
    if resolved is not None:
        ids, include_unassigned = resolved
        if dataset.workshop_filter == "none":
            pass
        elif dataset.workshop_filter == "artisan":
            clauses.append(artisan_workshop_clause(ids, include_unassigned))
        elif dataset.workshop_filter == "craft":
            clauses.append(craft_workshop_clause(ids, include_unassigned))
        elif dataset.workshop_filter == "tool":
            clauses.append(tool_workshop_clause(ids, include_unassigned))
        else:
            clause = workshop_clause(
                ids, include_unassigned, is_workshop_table=(dataset.workshop_filter == "self")
            )
            clauses.append(clause if clause is not None else {"id": {"in": []}})

    if dataset.delegate == "mediafile":
        clauses.append({"removedAt": None})
    if created_by:
        clauses.append({dataset.owner_field: created_by})
    if created_since:
        clauses.append({"createdAt": {"gte": created_since}})
    if updated_since:
        clauses.append({"updatedAt": {"gte": updated_since}})

    return {"AND": clauses} if clauses else {}


def _presign_media_row(row: Any) -> dict[str, Any] | None:
    object_key = get_value(row, "objectKey")
    if not object_key:
        return None
    filename = display_filename(row)
    mime_type = get_value(row, "mimeType") or "application/octet-stream"
    try:
        url = presign_get_url(
            object_key,
            filename=str(filename),
            mime_type=str(mime_type),
            expires_in=PRESIGN_TTL_SECONDS,
        )
    except Exception:  # noqa: BLE001 - a storage misconfiguration must not 500 the whole index
        return None
    return {"downloadUrl": url, "downloadUrlExpiresIn": PRESIGN_TTL_SECONDS}


def _encode_rows(
    rows: list[Any], *, unmasked_viewer: Any | None, presign: bool
) -> list[dict[str, Any]]:
    encoded = public_encode(rows, unmasked_viewer, media_urls=ALL_MEDIA_URLS)
    if presign:
        for row, payload in zip(rows, encoded):
            patch = _presign_media_row(row)
            if patch:
                payload.update(patch)
    return encoded


async def _stream_rows(dataset: Dataset, where: dict[str, Any]) -> AsyncIterator[list[Any]]:
    last_id: str | None = None
    while True:
        page_where = where
        if last_id is not None:
            page_where = (
                {"AND": [where, {"id": {"gt": last_id}}]} if where else {"id": {"gt": last_id}}
            )
        rows = await dataset.model.find_many(
            where=page_where,
            include=dataset.include,
            take=STREAM_BATCH,
            order={"id": "asc"},
        )
        if not rows:
            return
        yield rows
        if len(rows) < STREAM_BATCH:
            return
        last_id = get_value(rows[-1], "id")


async def _media_by_tag(dataset: Dataset, rows: list[Any]) -> dict[str, list[Any]]:
    if not dataset.media_tags or not rows:
        return {}
    ids = [rid for rid in (get_value(r, "id") for r in rows) if rid]
    if not ids:
        return {}
    media = await db.mediafile.find_many(
        where={
            "AND": [
                {"linkedRecordType": {"in": list(dataset.media_tags)}},
                {"linkedRecordId": {"in": ids}},
                {"removedAt": None},
            ]
        }
    )
    index: dict[str, list[Any]] = {}
    for item in media:
        owner = get_value(item, "linkedRecordId")
        if owner:
            index.setdefault(owner, []).append(item)
    return index


def _csv_columns(kind: str) -> list[str]:
    return ["ID", *sheet_columns(kind)]


def _flat_columns(dataset: Dataset) -> list[str]:
    return ["ID", *(header for header, _ in dataset.flat_columns)]


def csv_header(dataset: Dataset) -> list[str] | None:
    if dataset.kind is not None:
        return _csv_columns(dataset.kind)
    if dataset.flat_columns:
        return _flat_columns(dataset)
    return None


def _csv_shape(dataset: Dataset) -> tuple[str | None, list[str]]:
    columns = csv_header(dataset)
    if columns is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"The '{dataset.name}' dataset has no CSV form: it is not a record type the shared "
                f"field registry describes and it declares no columns of its own. Use "
                f"/api/datasets/{dataset.name}.ndjson instead."
            ),
        )
    return dataset.kind, columns



_TOKEN_THROTTLED_DETAIL = (
    "Too many failed credential attempts for this account. Wait a few minutes and try again — "
    "a correct password does not count against this limit."
)

DATASET_TOKEN_NEEDS_OWN_PASSWORD_DETAIL = (
    "This account must choose its own password before it can be issued a dataset token. Sign in to "
    "the web app, set a new password when asked, then request the token again with that password."
)


DATASET_TOKEN_NEEDS_CODE_DETAIL = (
    "This account uses two-step verification. Send the 6-digit code from your authenticator app as "
    "twoFactorCode."
)


@router.post("/token")
async def mint_dataset_token(payload: DatasetTokenRequest) -> dict[str, Any]:
    if payload.googleIdToken:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Dataset tokens are issued from an email and password, not a Google ID token.",
        )

    email = normalise_email(payload.email)
    user = await db.user.find_unique(where={"email": email}) if email else None
    if user is not None:
        allowed, _retry = account_credential_attempt(user.id)
    else:
        allowed, _retry = address_credential_attempt(identifier_key(payload.email))
    if not allowed:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=_TOKEN_THROTTLED_DETAIL,
        )
    if not verify_password_or_decoy(payload.password or "", user.passwordHash if user else None) or not user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid email or password"
        )
    account_credential_refund(user.id)
    if not is_admin(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only admins can issue a dataset download link.",
        )
    await assert_access_admits(user.email, is_master=is_break_glass_master(user))
    if password_change_pending(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=DATASET_TOKEN_NEEDS_OWN_PASSWORD_DETAIL,
        )
    if getattr(user, "twoFactorEnabled", False):
        if not payload.twoFactorCode:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail=DATASET_TOKEN_NEEDS_CODE_DETAIL
            )
        try:
            accepted = await two_factor.check_second_factor(user, payload.twoFactorCode)
        except two_factor.SecondFactorLocked as locked:
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail=two_factor.TOO_MANY_ATTEMPTS_DETAIL,
            ) from locked
        if not accepted:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail=two_factor.WRONG_CODE_DETAIL
            )
        user = await db.user.find_unique(where={"id": user.id}) or user

    minutes = get_settings().dataset_token_expires_minutes
    token = create_access_token(
        subject=user.id,
        extra_claims={"scope": DATASET_READ_SCOPE, "email": user.email},
        expires_minutes=minutes,
        credential=password_credential(user),
    )
    return {
        "accessToken": token,
        "tokenType": "bearer",
        "scope": DATASET_READ_SCOPE,
        "expiresInMinutes": minutes,
        "account": {
            "id": user.id,
            "email": user.email,
            "role": str(getattr(user.role, "value", user.role)),
        },
        "usage": "Send as: Authorization: Bearer <accessToken>",
    }


@router.get("")
async def dataset_catalogue(
    request: Request,
    workshopIds: list[str] | None = Query(None),
    createdBy: str | None = None,
    updatedSince: datetime | None = None,
    createdSince: datetime | None = None,
    current_user: Any = Depends(require_dataset_admin),
) -> dict[str, Any]:
    names = sorted(DATASETS)
    wheres = {
        name: _build_where(
            DATASETS[name],
            workshop_ids=workshopIds,
            created_by=createdBy,
            updated_since=updatedSince,
            created_since=createdSince,
        )
        for name in names
    }
    counts = await gather_reads(*(DATASETS[name].model.count(where=wheres[name]) for name in names))

    query = request.url.query
    suffix = f"?{query}" if query else ""
    base = request.url.path.rstrip("/")

    entries = []
    for name, count in zip(names, counts):
        dataset = DATASETS[name]
        entry: dict[str, Any] = {
            "name": name,
            "label": dataset.label,
            "description": dataset.description,
            "rows": count,
            "json": f"{base}/{name}{suffix}",
            "ndjson": f"{base}/{name}.ndjson{suffix}",
            "csv": f"{base}/{name}.csv{suffix}" if csv_header(dataset) else None,
            "columns": csv_header(dataset),
        }
        entries.append(entry)

    return {
        "datasets": entries,
        "totalRows": sum(counts),
        "filters": {
            "workshopIds": (
                "Repeated or comma-joined workshop ids. Absent means every workshop; the reserved "
                "word 'none' means records not linked to any workshop."
            ),
            "createdBy": "A user id. Narrows to the rows that account created (uploaded, for media).",
            "createdSince": "ISO-8601 timestamp. Rows created at or after it.",
            "updatedSince": "ISO-8601 timestamp. Rows changed at or after it — for incremental mirrors.",
            "presign": "media only. true swaps in time-limited direct download URLs.",
            "includeIdentityNumbers": (
                "Master admin only. true returns unmasked artisan identity numbers; they are masked "
                "on every surface by default."
            ),
        },
        "applied": {
            "workshopIds": workshopIds,
            "createdBy": createdBy,
            "createdSince": createdSince,
            "updatedSince": updatedSince,
        },
        "notes": [
            "Every stream sends X-Dataset-Total before the body: compare it with what you received "
            "to prove the download is complete.",
            "The .ndjson and .csv routes have NO row cap and stream in constant memory; the paged "
            "JSON route is capped at 100 rows a page like every other list in this API.",
        ],
        "authenticatedAs": get_value(current_user, "email"),
    }


def _identity_viewer(current_user: Any, include_identity_numbers: bool) -> Any | None:
    if not include_identity_numbers:
        return None
    if not is_master_admin(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Master admin access is required to export unmasked identity numbers. Omit "
                "includeIdentityNumbers to receive the masked values."
            ),
        )
    return current_user


def _stream_headers(total: int, filename: str) -> dict[str, str]:
    return {
        "X-Dataset-Total": str(total),
        "Content-Disposition": naming.content_disposition(filename),
    }


@router.get("/{dataset_name}.ndjson")
async def stream_dataset_ndjson(
    dataset_name: str,
    workshopIds: list[str] | None = Query(None),
    createdBy: str | None = None,
    updatedSince: datetime | None = None,
    createdSince: datetime | None = None,
    presign: bool = False,
    includeIdentityNumbers: bool = False,
    current_user: Any = Depends(require_dataset_admin),
) -> StreamingResponse:
    dataset = _dataset_or_404(dataset_name)
    viewer = _identity_viewer(current_user, includeIdentityNumbers)
    where = _build_where(
        dataset,
        workshop_ids=workshopIds,
        created_by=createdBy,
        updated_since=updatedSince,
        created_since=createdSince,
    )
    sign = presign and dataset.name == "media"
    total = await dataset.model.count(where=where)

    async def body() -> AsyncIterator[bytes]:
        async for rows in _stream_rows(dataset, where):
            encoded = await asyncio.to_thread(
                _encode_rows, rows, unmasked_viewer=viewer, presign=sign
            )
            chunk = "".join(
                json.dumps(payload, ensure_ascii=False, default=str) + "\n" for payload in encoded
            )
            yield chunk.encode("utf-8")

    return StreamingResponse(
        body(),
        media_type="application/x-ndjson",
        headers=_stream_headers(total, f"{dataset.name}.ndjson"),
    )


@router.get("/{dataset_name}.csv")
async def stream_dataset_csv(
    dataset_name: str,
    workshopIds: list[str] | None = Query(None),
    createdBy: str | None = None,
    updatedSince: datetime | None = None,
    createdSince: datetime | None = None,
    current_user: Any = Depends(require_dataset_admin),
) -> StreamingResponse:
    dataset = _dataset_or_404(dataset_name)
    kind, columns = _csv_shape(dataset)
    where = _build_where(
        dataset,
        workshop_ids=workshopIds,
        created_by=createdBy,
        updated_since=updatedSince,
        created_since=createdSince,
    )
    total = await dataset.model.count(where=where)

    async def body() -> AsyncIterator[bytes]:
        buffer = io.StringIO()
        writer = csv_safe.writer(buffer)

        def drain() -> bytes:
            chunk = buffer.getvalue()
            buffer.seek(0)
            buffer.truncate(0)
            return chunk.encode("utf-8")

        writer.writerow(columns)
        yield drain()
        uses_tags = bool(dataset.media_tags)
        async for rows in _stream_rows(dataset, where):
            tagged = await _media_by_tag(dataset, rows) if uses_tags else {}
            for record in rows:
                row_id = get_value(record, "id")
                if kind is None:
                    values = [getter(record) for _, getter in dataset.flat_columns]
                    writer.writerow([row_id, *("" if v is None else str(v) for v in values)])
                    continue
                media = tagged.get(row_id, []) if uses_tags else record_media(record)
                writer.writerow([row_id, *sheet_row(kind, record, media)])
            yield drain()

    return StreamingResponse(
        body(),
        media_type="text/csv; charset=utf-8",
        headers=_stream_headers(
            total, naming.export_filename("Dataset", "Export", "csv", subject=dataset.name)
        ),
    )


@router.get("/{dataset_name}")
async def read_dataset_page(
    dataset_name: str,
    workshopIds: list[str] | None = Query(None),
    createdBy: str | None = None,
    updatedSince: datetime | None = None,
    createdSince: datetime | None = None,
    presign: bool = False,
    includeIdentityNumbers: bool = False,
    page: int = Query(1, ge=1),
    pageSize: int = Query(100, ge=1, le=100),
    current_user: Any = Depends(require_dataset_admin),
) -> dict[str, Any]:
    dataset = _dataset_or_404(dataset_name)
    viewer = _identity_viewer(current_user, includeIdentityNumbers)
    clean_page, page_size, skip = normalize_pagination(page, pageSize)
    where = _build_where(
        dataset,
        workshop_ids=workshopIds,
        created_by=createdBy,
        updated_since=updatedSince,
        created_since=createdSince,
    )
    total, rows = await gather_reads(
        dataset.model.count(where=where),
        dataset.model.find_many(
            where=where, include=dataset.include, skip=skip, take=page_size, order={"id": "asc"}
        ),
    )
    items = _encode_rows(rows, unmasked_viewer=viewer, presign=presign and dataset.name == "media")
    return page_payload(items, total, clean_page, page_size)
