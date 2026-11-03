import logging
from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import Field

from app.core.db import db
from app.core.deps import (
    get_current_user,
    get_value,
    invalidate_cached_user,
    require_person_usage_reader,
    require_usage_reader,
)
from app.schemas.common import APIModel
from app.services import usage
from app.services.pagination import normalize_pagination, page_payload

router = APIRouter(prefix="/usage", tags=["usage"])
logger = logging.getLogger(__name__)


USAGE_READ_TEMPLATES: frozenset[str] = frozenset(
    {
        "/usage/me",
        "/usage/me/trail",
        "/usage/routes",
        "/usage/timeline",
        "/usage/latency",
        "/usage/clients",
        "/usage/screens",
        "/usage/collection",
        "/usage/consent",
        "/usage/consent/notice",
        "/usage/consent/withdraw",
        "/usage/accounts/{user_id}/trail",
    }
)


UNRECORDED_TEMPLATES: frozenset[str] = (
    frozenset({"/health", "/health/ready"}) | USAGE_READ_TEMPLATES
)


def _as_utc(moment: datetime) -> datetime:
    return moment if moment.tzinfo is not None else moment.replace(tzinfo=UTC)


def _window(raw_from: datetime, raw_to: datetime) -> tuple[datetime, datetime]:
    since, until = _as_utc(raw_from), _as_utc(raw_to)
    if since >= until:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=(
                f"The start date must be before the end date ({since.date().isoformat()} to "
                f"{until.date().isoformat()})."
            ),
        )
    if until - since > timedelta(days=usage.MAX_RANGE_DAYS):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=(
                f"Choose a period of {usage.MAX_RANGE_DAYS} days or fewer. This one is "
                f"{(until - since).days} days."
            ),
        )
    return since, until


def _window_block(since: datetime, until: datetime) -> dict[str, Any]:
    return {
        "from": since,
        "to": until,
        "days": round((until - since).total_seconds() / 86400, 2),
        "maxDays": usage.MAX_RANGE_DAYS,
        "interval": "[from, to)",
        "naiveDatesReadAs": "UTC",
    }


@router.get("/me")
async def my_usage(
    raw_from: datetime = Query(..., alias="from"),
    raw_to: datetime = Query(..., alias="to"),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    since, until = _window(raw_from, raw_to)
    user_id = str(get_value(current_user, "id") or "")
    try:
        report = await usage.usage_for_user(user_id, since, until)
    except usage.UsageRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    return {
        "userId": report["userId"],
        "window": _window_block(since, until),
        "requests": report["requests"],
        "routes": report["routes"],
        "collection": _collection_summary(),
        "notes": [
            "A screen here is one kind of page or action in the app, so one page you see can count "
            "as more than one screen.",
            "Times are measured from when a request reaches us until we answer, and leave out your "
            "connection and your device.",
        ],
    }


@router.get("/routes")
async def usage_by_route(
    raw_from: datetime = Query(..., alias="from"),
    raw_to: datetime = Query(..., alias="to"),
    template: list[str] | None = Query(None),
    page: int = Query(1, ge=1),
    pageSize: int = Query(usage.MAX_TEMPLATES_PER_QUERY, ge=1, le=usage.MAX_TEMPLATES_PER_QUERY),
    _: Any = Depends(require_usage_reader),
) -> dict[str, Any]:
    since, until = _window(raw_from, raw_to)

    if template:
        wanted = sorted({value.strip() for value in template if value and value.strip()})
        source = "requested"
        if not wanted:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=(
                    "'template' was given but every value was blank. Name at least one route "
                    "template, or omit the parameter to page through every mounted route."
                ),
            )
    else:
        wanted = sorted(usage.known_templates() - UNRECORDED_TEMPLATES)
        source = "mounted"

    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    window = wanted[skip : skip + clean_size]

    routes: list[dict[str, Any]] = []
    if window:
        try:
            report = await usage.usage_for_routes(window, since, until)
        except usage.UsageRuleViolation as exc:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
        routes = report["routes"]

    payload = page_payload(routes, len(wanted), clean_page, clean_size)
    payload["window"] = _window_block(since, until)
    payload["routeSource"] = source
    payload["limits"] = {
        "maxWindowDays": usage.MAX_RANGE_DAYS,
        "maxRoutesPerRequest": usage.MAX_TEMPLATES_PER_QUERY,
        "minimumIdentifiedUsers": usage.MIN_IDENTIFIED_USERS_FOR_ROUTE,
    }
    payload["totalsForThisPage"] = _page_totals(routes)
    payload["notMeasured"] = sorted(UNRECORDED_TEMPLATES)
    payload["notes"] = [
        (
            f"A screen used by fewer than {usage.MIN_IDENTIFIED_USERS_FOR_ROUTE} people in this "
            f"period is hidden and shown as a dash, so that no one can be singled out. A dash is "
            f"not a zero."
        ),
        (
            "A screen used only by people who are not signed in, such as the sign-in page, is "
            "shown in full."
        ),
        (
            "The totals at the top add up the screens on this page only, leaving out hidden ones. "
            "They are not totals for the whole platform."
        ),
        (
            "Times are measured from when a request reaches us until we answer. They leave out the "
            "connection and the device, so a screen that looks fast here can still be slow on a "
            "weak signal."
        ),
    ]
    return payload


def _page_totals(routes: list[dict[str, Any]]) -> dict[str, Any]:
    counted = [entry for entry in routes if not entry.get("withheld")]
    return {
        "routes": len(counted),
        "routesWithheld": len(routes) - len(counted),
        "requests": sum(int(entry.get("requests") or 0) for entry in counted),
        "ok": sum(int(entry.get("ok") or 0) for entry in counted),
        "clientErrors": sum(int(entry.get("clientErrors") or 0) for entry in counted),
        "serverErrors": sum(int(entry.get("serverErrors") or 0) for entry in counted),
    }


def _collection_summary() -> dict[str, Any]:
    plan = usage.collection_plan(usage.UsageConsent.NOT_RECORDED)
    return {
        "unaskedPolicy": usage.DEFAULT_UNASKED_COLLECTION.value,
        "attributesUnaskedRequests": plan.attribute,
        "explanation": plan.reason,
    }


_collects = usage.collects


@router.get("/collection")
async def collection_method(_: Any = Depends(require_usage_reader)) -> dict[str, Any]:
    stats = usage.buffer_stats()
    return {
        "collects": _collects(),
        "doesNotCollect": usage.does_not_collect(),
        "notMeasured": sorted(UNRECORDED_TEMPLATES),
        "consent": {
            "unaskedPolicy": usage.DEFAULT_UNASKED_COLLECTION.value,
            "options": [option.value for option in usage.UnaskedCollection],
            "flowExists": True,
            "noticeVersion": usage.NOTICE_VERSION,
            "bases": [option.value for option in usage.UsageConsentBasis],
            "askedAt": (
                "Everyone is asked when they sign in, on the website and the Android app, and again "
                "whenever the notice changes. Agreeing is required to sign in, so each answer is "
                "saved with where it was given: at sign-in, or by choice in Settings."
            ),
            "withdrawalCosts": (
                "Withdrawing costs nothing: it does not sign anyone out or take anything away."
            ),
            "explanation": _collection_summary()["explanation"],
            "consentStateWritten": (
                "Each entry is marked with the answer it was recorded under. Entries recorded "
                "before a person agreed are marked as unanswered."
            ),
            "refusalCost": (
                "People who decline are not recorded at all, not even without a name. These figures "
                "therefore cover only people who did not decline; say so when you quote them."
            ),
            "document": "docs/DECISION-usage-consent-at-sign-in.md",
            "priorDocument": "docs/DECISION-usage-consent-default.md",
        },
        "readableBy": usage.readable_by(),
        "limits": {
            "maxWindowDays": usage.MAX_RANGE_DAYS,
            "maxRoutesPerRequest": usage.MAX_TEMPLATES_PER_QUERY,
            "minimumIdentifiedUsers": usage.MIN_IDENTIFIED_USERS_FOR_ROUTE,
            "maxTimelineBuckets": usage.MAX_TIMELINE_BUCKETS,
            "maxTrailRows": usage.MAX_TRAIL_ROWS,
            "rowsPerWrite": usage.FLUSH_ROWS,
            "flushIntervalSeconds": usage.FLUSH_INTERVAL_SECONDS,
            "bufferCeiling": usage.BUFFER_CEILING,
        },
        "losses": {
            "scope": (
                "These counts come from one of the copies of the service that answer requests, "
                "since it last started. Each copy keeps its own counts, so they are not totals for "
                "the whole platform."
            ),
            "buffered": stats["buffered"],
            "written": stats["written"],
            "droppedAtCeiling": stats["dropped"],
            "abandonedAfterFailedWrites": stats["abandoned"],
            "failedFlushes": stats["failedFlushes"],
            "explanation": (
                "Entries are saved in batches. 'abandonedAfterFailedWrites' counts entries lost "
                "because saving failed twice in a row; 'droppedAtCeiling' counts the oldest entries "
                "let go when more than 5,000 were waiting to be saved. They are different losses, "
                "so do not add them together."
            ),
        },
        "knownLimitations": [
            "Times are not what people waited for: they are not user-perceived latency, and leave "
            "out the connection and the device. A fast time here can still be a slow screen in "
            "the field.",
            "A screen here is not a feature: one page a person sees can count as more than one "
            "screen.",
            "The count of people includes only people who agreed to be recorded and were signed "
            "in, so it is a minimum and never a headcount.",
            "Recording started on 30 August 2026. There is nothing from before then.",
            "Only requests that reached us are counted. Pages opened from the device's memory and "
            "work saved offline are not.",
            "Requests for addresses that do not exist are counted under '<unmatched>' and are not "
            "listed as a screen.",
            "Entries recorded before 30 August 2026 are marked as unanswered, because nobody had "
            "been asked yet. Figures that span that date mix the two.",
            "A person's activity is deleted when they withdraw, so figures that included it can "
            "change afterwards.",
        ],
        "retention": usage.retention_note(),
        "document": "docs/METHODOLOGY-usage-instrumentation.md",
    }



class UsageConsentIn(APIModel):
    decision: str
    basis: str
    noticeVersion: str = Field(min_length=1, max_length=64)
    recordedAt: datetime | None = None
    note: str | None = Field(default=None, max_length=500)


class UsageConsentWithdrawIn(APIModel):
    noticeVersion: str = Field(min_length=1, max_length=64)
    recordedAt: datetime | None = None
    note: str | None = Field(default=None, max_length=500)


def _consent_enum(raw: str, kind: type, *, what: str) -> Any:
    try:
        return kind(str(raw or "").strip().upper())
    except ValueError as exc:
        allowed = ", ".join(member.value for member in kind)
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"{raw!r} is not one of the {what} this server records. Send one of: {allowed}.",
        ) from exc


@router.get("/consent/notice")
async def consent_notice() -> dict[str, Any]:
    return usage.consent_notice()


@router.get("/consent")
async def my_consent(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    user_id = str(get_value(current_user, "id") or "")
    history = await usage.consent_history(user_id)
    return {
        "userId": user_id,
        "consent": usage.consent_record(current_user),
        "gate": usage.consent_gate(current_user),
        "notice": usage.consent_notice(),
        "decisions": [usage.consent_decision_payload(row) for row in history],
    }


async def _record_consent(
    *,
    current_user: Any,
    decision: usage.UsageConsent,
    basis: usage.UsageConsentBasis,
    notice_version: str,
    recorded_at: datetime | None,
    note: str | None,
) -> dict[str, Any]:
    user_id = str(get_value(current_user, "id") or "")
    try:
        outcome = await usage.record_consent(
            user_id=user_id,
            decision=decision,
            basis=basis,
            notice_version=notice_version,
            recorded_at=recorded_at,
            note=note,
        )
    except usage.UsageRuleViolation as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    invalidate_cached_user(user_id)
    account = await db.user.find_unique(where={"id": user_id})
    subject = account if account is not None else current_user

    body: dict[str, Any] = {
        "userId": user_id,
        "consent": usage.consent_record(subject),
        "gate": usage.consent_gate(subject),
        "decisions": [
            usage.consent_decision_payload(row) for row in await usage.consent_history(user_id)
        ],
    }
    if outcome.withdrawal is not None:
        body["withdrawal"] = {
            "bufferedDropped": outcome.withdrawal.buffered_dropped,
            "storedDeleted": outcome.withdrawal.stored_deleted,
            "storedDeleteRan": outcome.withdrawal.stored_delete_ran,
            "explanation": (
                "Recording has stopped, and everything recorded about you has been deleted."
                if outcome.withdrawal.stored_delete_ran
                else (
                    "Recording has stopped, but what was already recorded about you couldn't be "
                    "deleted. Nothing more is being recorded. Ask an administrator to finish "
                    "deleting it."
                )
            ),
        }
    return body


@router.post("/consent")
async def record_my_consent(
    payload: UsageConsentIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    decision = _consent_enum(payload.decision, usage.UsageConsent, what="answers")
    basis = _consent_enum(payload.basis, usage.UsageConsentBasis, what="circumstances")
    return await _record_consent(
        current_user=current_user,
        decision=decision,
        basis=basis,
        notice_version=payload.noticeVersion,
        recorded_at=payload.recordedAt,
        note=payload.note,
    )


@router.post("/consent/withdraw")
async def withdraw_my_consent(
    payload: UsageConsentWithdrawIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    return await _record_consent(
        current_user=current_user,
        decision=usage.UsageConsent.REFUSED,
        basis=usage.UsageConsentBasis.OFFERED_IN_SETTINGS,
        notice_version=payload.noticeVersion,
        recorded_at=payload.recordedAt,
        note=payload.note,
    )



def _requested_templates(template: list[str] | None) -> list[str]:
    if template:
        wanted = sorted({value.strip() for value in template if value and value.strip()})
        if not wanted:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=(
                    "'template' was given but every value was blank. Name at least one route "
                    "template, or omit the parameter to report on the first "
                    f"{usage.MAX_TEMPLATES_PER_QUERY} mounted routes."
                ),
            )
        if len(wanted) > usage.MAX_TEMPLATES_PER_QUERY:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=(
                    f"At most {usage.MAX_TEMPLATES_PER_QUERY} route templates may be named in one "
                    f"request; this one named {len(wanted)}. That bound is an index strategy rather "
                    f"than a preference: the IN list becomes a bitmap of index probes, which is "
                    f"cheap while it is short."
                ),
            )
        return wanted
    mounted = sorted(usage.known_templates() - UNRECORDED_TEMPLATES)[
        : usage.MAX_TEMPLATES_PER_QUERY
    ]
    if not mounted:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=(
                "Usage figures aren't available right now. Please try again later."
            ),
        )
    return mounted


def _template_block(wanted: list[str], named: bool) -> dict[str, Any]:
    mounted = usage.known_templates() - UNRECORDED_TEMPLATES
    return {
        "templates": wanted,
        "source": "requested" if named else "mounted",
        "count": len(wanted),
        "mountedTotal": len(mounted),
        "notIncluded": max(0, len(mounted) - len(wanted)) if not named else 0,
        "maxPerRequest": usage.MAX_TEMPLATES_PER_QUERY,
    }


_WITHHELD_NOTE = (
    "Any figure with fewer than {floor} people behind it is hidden, so that no one can be singled "
    "out. A hidden figure is shown as a gap or a dash, never as a zero."
)

_SERVER_TIME_NOTE = (
    "Times are measured from when a request reaches us until we answer. They leave out the "
    "connection and the device, so a screen that looks fast here can still be slow on a weak signal."
)


@router.get("/timeline")
async def usage_over_time(
    raw_from: datetime = Query(..., alias="from"),
    raw_to: datetime = Query(..., alias="to"),
    bucket: str = Query("day"),
    template: list[str] | None = Query(None),
    _: Any = Depends(require_usage_reader),
) -> dict[str, Any]:
    since, until = _window(raw_from, raw_to)
    wanted = _requested_templates(template)
    try:
        report = await usage.usage_timeline(wanted, since, until, bucket=bucket)
    except usage.UsageRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    return {
        "window": _window_block(since, until),
        "bucket": report["bucket"],
        "scope": _template_block(wanted, bool(template)),
        "series": report["series"],
        "limits": {
            "maxWindowDays": usage.MAX_RANGE_DAYS,
            "maxBuckets": usage.MAX_TIMELINE_BUCKETS,
            "maxRoutesPerRequest": usage.MAX_TEMPLATES_PER_QUERY,
            "minimumIdentifiedUsers": usage.MIN_IDENTIFIED_USERS_FOR_ROUTE,
        },
        "notes": [
            _WITHHELD_NOTE.format(floor=usage.MIN_IDENTIFIED_USERS_FOR_ROUTE),
            "A period with no requests is shown as zero. A hidden period is different, and is "
            "shown as a gap.",
            "The error rate is the share of requests that failed. It is left blank where there "
            "were no requests.",
            "Days and hours are in UTC, not local time, and each is labelled by when it starts.",
            _SERVER_TIME_NOTE,
        ],
    }


@router.get("/latency")
async def usage_latency(
    raw_from: datetime = Query(..., alias="from"),
    raw_to: datetime = Query(..., alias="to"),
    template: list[str] | None = Query(None),
    _: Any = Depends(require_usage_reader),
) -> dict[str, Any]:
    since, until = _window(raw_from, raw_to)
    wanted = _requested_templates(template)
    try:
        report = await usage.usage_latency(wanted, since, until)
    except usage.UsageRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    return {
        "window": _window_block(since, until),
        "scope": _template_block(wanted, bool(template)),
        "percentiles": report["percentiles"],
        "routes": report["routes"],
        "limits": {
            "maxWindowDays": usage.MAX_RANGE_DAYS,
            "maxRoutesPerRequest": usage.MAX_TEMPLATES_PER_QUERY,
            "minimumIdentifiedUsers": usage.MIN_IDENTIFIED_USERS_FOR_ROUTE,
        },
        "notes": [
            _WITHHELD_NOTE.format(floor=usage.MIN_IDENTIFIED_USERS_FOR_ROUTE),
            "A screen nobody used in this period has no times to show. That is different from a "
            "hidden screen.",
            "These times are worked out from every request in the period, so they cannot be "
            "worked out from the averages in the by-screen table.",
            _SERVER_TIME_NOTE,
        ],
    }


@router.get("/clients")
async def usage_by_client(
    raw_from: datetime = Query(..., alias="from"),
    raw_to: datetime = Query(..., alias="to"),
    template: list[str] | None = Query(None),
    _: Any = Depends(require_usage_reader),
) -> dict[str, Any]:
    since, until = _window(raw_from, raw_to)
    wanted = _requested_templates(template)
    try:
        report = await usage.usage_clients(wanted, since, until)
    except usage.UsageRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    unlabelled = next(
        (
            row
            for row in report["clients"]
            if row["clientApp"] == usage.DEFAULT_CLIENT_APP and (row.get("requests") or 0) > 0
        ),
        None,
    )
    return {
        "window": _window_block(since, until),
        "scope": _template_block(wanted, bool(template)),
        "clients": report["clients"],
        "header": usage.CLIENT_APP_HEADER,
        "known": sorted(usage.CLIENT_APPS),
        "fallback": usage.DEFAULT_CLIENT_APP,
        "limits": {
            "maxWindowDays": usage.MAX_RANGE_DAYS,
            "maxRoutesPerRequest": usage.MAX_TEMPLATES_PER_QUERY,
            "minimumIdentifiedUsers": usage.MIN_IDENTIFIED_USERS_FOR_ROUTE,
        },
        "notes": [
            _WITHHELD_NOTE.format(floor=usage.MIN_IDENTIFIED_USERS_FOR_ROUTE),
            (
                f"'{usage.DEFAULT_CLIENT_APP}' counts requests that did not say which app sent them. "
                "They come from the website and the Android app."
            )
            if unlabelled is not None
            else (
                f"'{usage.DEFAULT_CLIENT_APP}' counts requests that did not say which app sent them."
            ),
            "Do not add up the people counts across apps: someone who uses both the website and "
            "the Android app is counted in each row, so adding them counts that person twice.",
            _SERVER_TIME_NOTE,
        ],
    }


@router.get("/screens")
async def busiest_and_slowest_screens(
    raw_from: datetime = Query(..., alias="from"),
    raw_to: datetime = Query(..., alias="to"),
    template: list[str] | None = Query(None),
    limit: int = Query(10, ge=1, le=usage.MAX_TEMPLATES_PER_QUERY),
    _: Any = Depends(require_usage_reader),
) -> dict[str, Any]:
    since, until = _window(raw_from, raw_to)
    wanted = _requested_templates(template)
    try:
        report = await usage.usage_for_routes(wanted, since, until)
    except usage.UsageRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    routes = report["routes"]
    rankable = [row for row in routes if not row.get("withheld")]
    busiest = sorted(
        (row for row in rankable if (row.get("requests") or 0) > 0),
        key=lambda row: (-int(row.get("requests") or 0), str(row.get("routeTemplate") or "")),
    )[:limit]
    slowest = sorted(
        (row for row in rankable if row.get("avgDurationMs") is not None),
        key=lambda row: (
            -int(row.get("avgDurationMs") or 0),
            str(row.get("routeTemplate") or ""),
        ),
    )[:limit]

    return {
        "window": _window_block(since, until),
        "scope": _template_block(wanted, bool(template)),
        "limit": limit,
        "busiest": busiest,
        "slowest": slowest,
        "withheld": {
            "routes": sum(1 for row in routes if row.get("withheld")),
            "explanation": (
                f"Screens used by fewer than {usage.MIN_IDENTIFIED_USERS_FOR_ROUTE} people are left "
                f"out of both lists."
            ),
        },
        "limits": {
            "maxWindowDays": usage.MAX_RANGE_DAYS,
            "maxRoutesPerRequest": usage.MAX_TEMPLATES_PER_QUERY,
            "minimumIdentifiedUsers": usage.MIN_IDENTIFIED_USERS_FOR_ROUTE,
        },
        "notes": [
            "These lists rank the screens included above, not every screen in the app.",
            "The slowest list is ranked by average time, which can hide occasional slow requests. "
            "The response-time chart shows those.",
            _WITHHELD_NOTE.format(floor=usage.MIN_IDENTIFIED_USERS_FOR_ROUTE),
            _SERVER_TIME_NOTE,
        ],
    }



def _trail_notes() -> list[str]:
    return [
        "This is a list of requests in the order they arrived, not a total.",
        "Only requests that reached us are here. Pages opened from the device's memory and work "
        "saved offline are not, so an empty list does not mean nothing was done.",
        "Requests are listed only from when the person agreed to be recorded.",
        _SERVER_TIME_NOTE,
    ]


@router.get("/me/trail")
async def my_trail(
    raw_from: datetime = Query(..., alias="from"),
    raw_to: datetime = Query(..., alias="to"),
    limit: int = Query(usage.MAX_TRAIL_ROWS, ge=1, le=usage.MAX_TRAIL_ROWS),
    offset: int = Query(0, ge=0),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    since, until = _window(raw_from, raw_to)
    user_id = str(get_value(current_user, "id") or "")
    try:
        report = await usage.trail_for_user(
            user_id, since, until, limit=limit, offset=offset
        )
    except usage.UsageRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    return {
        "userId": user_id,
        "window": _window_block(since, until),
        "limit": report["limit"],
        "offset": report["offset"],
        "maxRows": usage.MAX_TRAIL_ROWS,
        "events": report["events"],
        "consent": usage.consent_record(current_user),
        "collection": _collection_summary(),
        "gate": usage.consent_gate(current_user),
        "notes": _trail_notes(),
    }


@router.get("/accounts/{user_id}/trail")
async def account_trail(
    user_id: str,
    raw_from: datetime = Query(..., alias="from"),
    raw_to: datetime = Query(..., alias="to"),
    limit: int = Query(usage.MAX_TRAIL_ROWS, ge=1, le=usage.MAX_TRAIL_ROWS),
    offset: int = Query(0, ge=0),
    reader: Any = Depends(require_person_usage_reader),
) -> dict[str, Any]:
    since, until = _window(raw_from, raw_to)
    subject_id = str(user_id or "").strip()
    subject = await db.user.find_unique(where={"id": subject_id}) if subject_id else None
    if subject is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No account with id {subject_id!r}.",
        )

    state = usage.resolve_consent(subject)
    if state is not usage.UsageConsent.GRANTED:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                (
                    "This person declined to have their use of the platform recorded, so there is "
                    "nothing to show."
                )
                if state is usage.UsageConsent.REFUSED
                else (
                    "This person hasn't answered yet, so nothing recorded carries their name. They "
                    "will be asked when they next sign in."
                )
            ),
        )

    try:
        report = await usage.trail_for_user(
            subject_id, since, until, limit=limit, offset=offset
        )
    except usage.UsageRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    logger.info(
        "usage: %s read the request trail of %s for %s to %s (%s row(s))",
        get_value(reader, "id"),
        subject_id,
        since.isoformat(),
        until.isoformat(),
        len(report["events"]),
    )

    return {
        "userId": subject_id,
        "window": _window_block(since, until),
        "limit": report["limit"],
        "offset": report["offset"],
        "maxRows": usage.MAX_TRAIL_ROWS,
        "events": report["events"],
        "subjectConsent": usage.consent_record(subject),
        "readBy": get_value(reader, "id"),
        "notes": [
            "This is one person's activity, not a total. Only the master admin can see it, and "
            "only while that person agrees to be recorded. Each view is logged with who looked, "
            "whose activity it was and the period.",
            "Each entry shows the answer it was recorded under, which can differ from the person's "
            "answer today.",
            "Small numbers are not hidden here, because this view is about one named person.",
            *_trail_notes(),
        ],
    }
