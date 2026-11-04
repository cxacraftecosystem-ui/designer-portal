from __future__ import annotations

import csv
import io
import logging
from types import SimpleNamespace
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status

from app.core.db import db
from app.core.deps import (
    MINISTRY_DASHBOARD_REFUSAL,
    can_download_dataset,
    can_see_ministry_dashboard,
    get_current_user,
)
from app.services import (
    design_workshop_oversight as officers,
    ministry_dashboard as register,
    naming,
    report_delivery,
)
from app.services.concurrency import gather_reads
from app.services.design_workshop_inspectors import INSPECTION_ROLES
from app.services.design_workshops import stage_writers, workshop_summary
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import contains
from app.services.text_format import by_count, count_noun

router = APIRouter(prefix="/ministry-dashboard", tags=["ministry"])

logger = logging.getLogger(__name__)


MAX_PAGE_SIZE = 100

MAX_SEARCH = 120


async def require_ministry_dashboard_reader(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_see_ministry_dashboard(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail=MINISTRY_DASHBOARD_REFUSAL
        )
    return current_user


def _search_clause(term: str | None) -> dict[str, Any] | None:
    clean = (term or "").strip()
    if not clean:
        return None
    return {
        "OR": [
            {"title": contains(clean)},
            {"craftName": contains(clean)},
            {"clusterName": contains(clean)},
            {"workshopCode": contains(clean)},
        ]
    }


def _design_where(
    current_user: Any,
    search: str | None,
    standing: str | None,
    attention: str | None = None,
    state: str | None = None,
) -> dict[str, Any]:
    where: dict[str, Any] = {"deletedAt": None}

    search_clause = _search_clause(search)
    if search_clause:
        where["OR"] = search_clause["OR"]

    scope = register.scope_clause(current_user)
    if scope is not None:
        where.setdefault("AND", []).append(scope)
    standing_clause = register.standing_clause(standing)
    if standing_clause is not None:
        where.setdefault("AND", []).append(standing_clause)
    attention_clause = register.attention_clause(attention)
    if attention_clause is not None:
        where.setdefault("AND", []).append(attention_clause)
    clean_state = (state or "").strip()
    if clean_state:
        where.setdefault("AND", []).append(
            {"state": None} if clean_state == "none" else {"state": clean_state}
        )
    return where


_DESIGN_ORDER = [{"updatedAt": "desc"}, {"createdAt": "desc"}, {"id": "asc"}]


async def _design_rows(
    current_user: Any,
    *,
    where: dict[str, Any],
    skip: int,
    take: int,
) -> tuple[int, list[dict[str, Any]]]:
    total, records = await gather_reads(
        db.designworkshop.count(where=where),
        db.designworkshop.find_many(where=where, skip=skip, take=take, order=_DESIGN_ORDER),
    )
    ids = [str(record.id) for record in records]

    async def _progress_or_none() -> dict[str, dict[str, Any]] | None:
        try:
            return await register.progress_for(ids)
        except Exception:
            logger.exception("ministry dashboard: scoring %d workshop(s) failed", len(ids))
            return None

    async def _beneficiaries_or_none() -> dict[str, int] | None:
        try:
            return await register.beneficiary_counts(ids)
        except Exception:
            logger.exception("ministry dashboard: counting artisans on %d workshop(s) failed", len(ids))
            return None

    async def _received_or_empty() -> dict[str, dict[str, Any]]:
        try:
            return await report_delivery.received_for(ids)
        except Exception:
            logger.exception("ministry dashboard: reading deliveries for %d workshop(s) failed", len(ids))
            return {}

    scored_map, counted_map, received = await gather_reads(
        _progress_or_none(), _beneficiaries_or_none(), _received_or_empty()
    )
    progress: dict[str, dict[str, Any]] = scored_map or {}
    beneficiaries: dict[str, int] | None = counted_map

    rows: list[dict[str, Any]] = []
    for index, record in enumerate(records):
        workshop_id = str(record.id)
        summary = workshop_summary(record)
        summary["progress"] = progress.get(
            workshop_id,
            register.unscored(
                "capped" if index >= register.PROGRESS_SCORE_CAP else "unreadable"
            ),
        )
        summary["beneficiaries"] = beneficiaries.get(workshop_id, 0) if beneficiaries is not None else None
        summary["standingGroup"] = register.group_of(summary.get("status"))
        summary["received"] = received.get(workshop_id)
        rows.append(summary)
    return total, rows


@router.get("/design-workshops")
async def list_design_workshops(
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=MAX_PAGE_SIZE),
    search: str | None = Query(None, max_length=MAX_SEARCH),
    standing: str | None = Query(None, max_length=32),
    attention: str | None = Query(None, max_length=32),
    state: str | None = Query(None, max_length=80),
    current_user: Any = Depends(require_ministry_dashboard_reader),
) -> dict[str, Any]:
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    where = _design_where(current_user, search, standing, attention, state)
    setup_capped = False
    if (attention or "").strip() == "setupIncomplete":
        ids, setup_capped = await register.setup_incomplete_ids(
            _design_where(current_user, search, standing, None, state)
        )
        where.setdefault("AND", []).append({"id": {"in": ids}})
    total, rows = await _design_rows(current_user, where=where, skip=skip, take=clean_size)

    payload = page_payload(rows, total, clean_page, clean_size)
    payload["scope"] = "estate" if register.sees_whole_estate(current_user) else "posted"
    payload["scopeLabel"] = register.scope_label(current_user)
    payload["standingVocabulary"] = "designWorkshopLifecycle"
    payload["standingGroups"] = {
        name: list(members) for name, members in register.STANDING_GROUPS.items()
    }
    payload["progressScoreCap"] = register.PROGRESS_SCORE_CAP
    payload["attention"] = (attention or "").strip() or None
    payload["attentionCapped"] = setup_capped
    return payload


@router.get("/workshops")
async def list_other_workshops(
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=MAX_PAGE_SIZE),
    search: str | None = Query(None, max_length=MAX_SEARCH),
    standing: str | None = Query(None, max_length=32),
    _: Any = Depends(require_ministry_dashboard_reader),
) -> dict[str, Any]:
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)

    where: dict[str, Any] = {}
    clean_search = (search or "").strip()
    if clean_search:
        where["OR"] = [
            {"title": contains(clean_search)},
            {"place": contains(clean_search)},
            {"description": contains(clean_search)},
        ]
    clean_standing = (standing or "").strip().upper()
    if clean_standing in _RECORD_STATUSES:
        where.setdefault("AND", []).append({"status": clean_standing})

    total, records = await gather_reads(
        db.workshop.count(where=where),
        db.workshop.find_many(
            where=where,
            skip=skip,
            take=clean_size,
            order=[
                {"startDate": "desc"},
                {"date": "desc"},
                {"createdAt": "desc"},
                {"id": "asc"},
            ],
        ),
    )
    ids = [str(record.id) for record in records]
    beneficiaries = await register.legacy_beneficiary_counts(ids)

    rows = [_legacy_row(record, beneficiaries) for record in records]
    payload = page_payload(rows, total, clean_page, clean_size)
    payload["scope"] = "estate"
    payload["scopeLabel"] = (
        "Every recorded workshop on the platform. Recorded workshops carry no supervising officer, "
        "so unlike the design & prototype tab this list is not narrowed by your postings."
    )
    payload["standingVocabulary"] = "recordStatus"
    payload["standingGroups"] = {status_name: [status_name] for status_name in _RECORD_STATUSES}
    return payload


_RECORD_STATUSES: tuple[str, ...] = (
    "DRAFT",
    "PENDING",
    "APPROVED",
    "REJECTED",
    "NEEDS_REVISION",
)


def _legacy_row(record: Any, beneficiaries: dict[str, int]) -> dict[str, Any]:
    workshop_id = str(record.id)
    return {
        "id": workshop_id,
        "title": record.title,
        "workshopType": record.workshopType,
        "place": record.place,
        "date": record.date,
        "startDate": record.startDate,
        "endDate": record.endDate,
        "status": record.status,
        "beneficiaries": beneficiaries.get(workshop_id, 0),
        "createdAt": record.createdAt,
        "updatedAt": record.updatedAt,
    }


@router.get("/summary")
async def register_summary(
    current_user: Any = Depends(require_ministry_dashboard_reader),
) -> dict[str, Any]:
    scope = register.scope_clause(current_user)
    base: dict[str, Any] = {"deletedAt": None}
    if scope is not None:
        base["AND"] = [scope]

    names = list(register.STANDING_GROUPS)
    counts = await gather_reads(
        db.designworkshop.count(where=base),
        *(
            db.designworkshop.count(
                where={**base, "status": {"in": list(register.STANDING_GROUPS[name])}}
            )
            for name in names
        ),
        db.workshop.count(where={}),
    )
    total = int(counts[0])
    grouped = {name: int(counts[index + 1]) for index, name in enumerate(names)}

    queried = [key for key in register.ATTENTION_KEYS if register.attention_clause(key) is not None]
    answers = await gather_reads(
        *(
            db.designworkshop.count(where={"AND": [base, register.attention_clause(key)]})
            for key in queried
        ),
        register.state_breakdown(base),
        register.sanctioned_total(base),
        register.setup_incomplete_ids(base),
    )
    by_state, sanctioned, setup = answers[len(queried)], answers[len(queried) + 1], answers[-1]
    attention = {key: int(answers[index]) for index, key in enumerate(queried)}
    attention["setupIncomplete"] = len(setup[0])

    return {
        "designWorkshops": {
            "total": total,
            **grouped,
            "unclassified": max(0, total - sum(grouped.values())),
            "scope": "estate" if register.sees_whole_estate(current_user) else "posted",
            "scopeLabel": register.scope_label(current_user),
            "attention": attention,
            "setupIncompleteCapped": bool(setup[1]),
            "byState": by_state,
            "sanctioned": sanctioned,
        },
        "otherWorkshops": {
            "total": int(counts[-1]),
            "scope": "estate",
            "scopeLabel": (
                "Every recorded workshop on the platform. Recorded workshops carry no supervising "
                "officer, so this count is not narrowed by your postings."
            ),
        },
        "scope": "estate" if register.sees_whole_estate(current_user) else "posted",
        "scopeLabel": register.scope_label(current_user),
    }


EXPORT_TAKE = 5000

_DESIGN_EXPORT_COLUMNS: tuple[str, ...] = (
    "Workshop code",
    "Title",
    "Kind",
    "Craft",
    "Cluster",
    "State",
    "District",
    "Venue",
    "Start date",
    "End date",
    "Designer",
    "Standing",
    "Standing group",
    "Artisans",
    "Stages complete",
    "Stages total",
    "Required fields answered",
    "Required fields asked",
    "Progress %",
    "Last updated",
)


def _csv_response(filename: str, rows: list[list[str]], columns: tuple[str, ...]) -> Response:
    buffer = io.StringIO()
    writer = csv.writer(buffer)
    writer.writerow(list(columns))
    for row in rows:
        writer.writerow(row)
    return Response(
        content=buffer.getvalue(),
        media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


def _truncation_row(count: int, columns: tuple[str, ...]) -> list[str]:
    note = f"Note: capped at {count} rows — the full data set has more."
    return [note] + [""] * (len(columns) - 1)


def _stamp(value: Any) -> str:
    if value is None:
        return ""
    date_part = getattr(value, "date", None)
    if callable(date_part) and getattr(value, "hour", None) is not None:
        return value.isoformat()
    if hasattr(value, "isoformat"):
        return value.isoformat()
    return str(value)


def _day(value: Any) -> str:
    if value is None:
        return ""
    date_part = getattr(value, "date", None)
    if callable(date_part):
        return date_part().isoformat()
    if hasattr(value, "isoformat"):
        return value.isoformat()
    return str(value)


def _text(value: Any) -> str:
    if value is None:
        return ""
    return str(value)


@router.get("/design-workshops.csv")
async def export_design_workshops_csv(
    search: str | None = Query(None, max_length=MAX_SEARCH),
    standing: str | None = Query(None, max_length=32),
    current_user: Any = Depends(require_ministry_dashboard_reader),
) -> Response:
    where = _design_where(current_user, search, standing)
    records = await db.designworkshop.find_many(
        where=where, take=EXPORT_TAKE, order=_DESIGN_ORDER
    )
    ids = [str(record.id) for record in records]
    progress, beneficiaries = await gather_reads(
        register.progress_for(ids), register.beneficiary_counts(ids)
    )

    rows: list[list[str]] = []
    for record in records:
        workshop_id = str(record.id)
        summary = workshop_summary(record)
        scored = progress.get(workshop_id)
        rows.append(
            [
                _text(summary.get("workshopCode")),
                _text(summary.get("title")),
                _text(summary.get("workshopKind")),
                _text(summary.get("craftName")),
                _text(summary.get("clusterName")),
                _text(summary.get("state")),
                _text(summary.get("district")),
                _text(summary.get("venue")),
                _text(summary.get("startDate")),
                _text(summary.get("endDate")),
                _text(summary.get("designerName")),
                _text(summary.get("status")),
                _text(register.group_of(summary.get("status"))),
                _text(beneficiaries.get(workshop_id, 0)),
                _text(scored["stagesComplete"]) if scored else "not scored in this export",
                _text(scored["stagesTotal"]) if scored else "",
                _text(scored["requiredFilled"]) if scored else "",
                _text(scored["requiredTotal"]) if scored else "",
                _text(scored["percent"]) if scored else "",
                _text(summary.get("updatedAt")),
            ]
        )

    rows.append(
        [
            f"Scope: {register.scope_label(current_user)}",
            *([""] * (len(_DESIGN_EXPORT_COLUMNS) - 1)),
        ]
    )
    if (search or "").strip() or register.standing_clause(standing) is not None:
        rows.append(
            [
                "Note: this file was taken under a filter and is not the whole register.",
                *([""] * (len(_DESIGN_EXPORT_COLUMNS) - 1)),
            ]
        )
    if len(records) >= EXPORT_TAKE:
        rows.append(_truncation_row(len(records), _DESIGN_EXPORT_COLUMNS))

    return _csv_response(
        naming.export_filename(
            "Ministry", "Export", "csv", subject="Design Prototype Workshops"
        ),
        rows,
        _DESIGN_EXPORT_COLUMNS,
    )


_OTHER_EXPORT_COLUMNS: tuple[str, ...] = (
    "Title",
    "Kind",
    "Place",
    "Date",
    "Start date",
    "End date",
    "Status",
    "Artisans",
    "Recorded on",
)


@router.get("/workshops.csv")
async def export_other_workshops_csv(
    search: str | None = Query(None, max_length=MAX_SEARCH),
    standing: str | None = Query(None, max_length=32),
    _: Any = Depends(require_ministry_dashboard_reader),
) -> Response:
    where: dict[str, Any] = {}
    clean_search = (search or "").strip()
    if clean_search:
        where["OR"] = [
            {"title": contains(clean_search)},
            {"place": contains(clean_search)},
            {"description": contains(clean_search)},
        ]
    clean_standing = (standing or "").strip().upper()
    if clean_standing in _RECORD_STATUSES:
        where.setdefault("AND", []).append({"status": clean_standing})

    records = await db.workshop.find_many(
        where=where,
        take=EXPORT_TAKE,
        order=[{"startDate": "desc"}, {"date": "desc"}, {"createdAt": "desc"}, {"id": "asc"}],
    )
    beneficiaries = await register.legacy_beneficiary_counts([str(r.id) for r in records])

    rows = [
        [
            _text(record.title),
            _text(record.workshopType),
            _text(record.place),
            _day(record.date),
            _day(record.startDate),
            _day(record.endDate),
            _text(record.status),
            _text(beneficiaries.get(str(record.id), 0)),
            _stamp(record.createdAt),
        ]
        for record in records
    ]

    rows.append(
        [
            "Scope: every recorded workshop on the platform. Recorded workshops carry no supervising "
            "officer, so this file is not narrowed by the reader's postings.",
            *([""] * (len(_OTHER_EXPORT_COLUMNS) - 1)),
        ]
    )
    if clean_search or clean_standing in _RECORD_STATUSES:
        rows.append(
            [
                "Note: this file was taken under a filter and is not the whole register.",
                *([""] * (len(_OTHER_EXPORT_COLUMNS) - 1)),
            ]
        )
    if len(records) >= EXPORT_TAKE:
        rows.append(_truncation_row(len(records), _OTHER_EXPORT_COLUMNS))

    return _csv_response(
        naming.export_filename("Ministry", "Export", "csv", subject="Other Workshops"),
        rows,
        _OTHER_EXPORT_COLUMNS,
    )


@router.get("/entitlements")
async def download_entitlements(
    current_user: Any = Depends(require_ministry_dashboard_reader),
) -> dict[str, Any]:
    return {
        "beneficiaries": bool(can_download_dataset(current_user)),
        "beneficiariesRefusal": (
            "Downloading the beneficiary list needs dataset-download access — Professor and above, "
            "or an explicit grant. Ask an admin to grant it."
        ),
        "coverageMap": True,
    }



def _people_scope(current_user: Any) -> str:
    return "estate" if register.sees_whole_estate(current_user) else "posted"


async def _people_progress_or_none(
    workshop_ids: list[str], register_name: str
) -> dict[str, dict[str, Any]] | None:
    try:
        return await register.progress_for(workshop_ids)
    except Exception:
        logger.exception(
            "ministry dashboard: scoring %d workshop(s) for the %s register failed",
            len(workshop_ids),
            register_name,
        )
        return None


def _people_envelope(
    ordered: list[dict[str, Any]],
    *,
    current_user: Any,
    noun: str,
    page: int,
    page_size: int,
    skip: int,
    scan: dict[str, Any],
    withheld: int,
    scoring_read: bool,
    includes_unposted: bool,
    unposted_note: str,
) -> dict[str, Any]:
    payload = page_payload(ordered[skip : skip + page_size], len(ordered), page, page_size)
    payload["scope"] = _people_scope(current_user)
    payload["scopeLabel"] = register.people_scope_label(
        current_user, noun=noun, includes_unposted=includes_unposted
    )
    payload["standingVocabulary"] = "designWorkshopLifecycle"
    payload["standingGroups"] = {
        name: list(members) for name, members in register.STANDING_GROUPS.items()
    }
    payload["scan"] = scan
    payload["progressScoreCap"] = register.PROGRESS_SCORE_CAP
    payload["progressRead"] = scoring_read
    payload["progressNote"] = (
        None
        if scoring_read
        else (
            "Progress couldn't be read just now, so no percentage below is a measurement. Each "
            "entry says so instead of showing 0%."
        )
    )
    payload["withheldAccounts"] = withheld
    payload["withheldAccountsNote"] = (
        None
        if withheld == 0
        else (
            f"{count_noun(withheld, 'administrator account')} on these workshops "
            f"{by_count(withheld, 'is', 'are')} not named here. "
            f"{by_count(withheld, 'Its', 'Their')} "
            "workshops are still counted in the totals above."
        )
    )
    payload["includesUnpostedAccounts"] = includes_unposted
    payload["unpostedAccountsNote"] = unposted_note
    return payload


@router.get("/designers")
async def list_designers(
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=MAX_PAGE_SIZE),
    standing: str | None = Query(None, max_length=32),
    current_user: Any = Depends(require_ministry_dashboard_reader),
) -> dict[str, Any]:
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    where = _design_where(current_user, None, standing)
    total_in_scope, records = await register.people_spine(where)
    workshop_ids = [str(record.id) for record in records]

    async def _roster_or_none() -> tuple[list[Any], bool] | None:
        try:
            return await register.empanelled_designer_accounts()
        except Exception:
            logger.exception("ministry dashboard: reading the empanelled designer roster failed")
            return None

    async def _representation_or_none() -> dict[str, Any] | None:
        try:
            return await register.roster_representation()
        except Exception:
            logger.exception("ministry dashboard: measuring roster representation failed")
            return None

    creator_ids = {str(getattr(record, "createdById", "") or "") for record in records} - {""}
    links, creators, authored, scored_map, roster, representation = await gather_reads(
        register.designer_links(workshop_ids),
        register.creator_accounts(records),
        stage_writers(workshop_ids, creator_ids),
        _people_progress_or_none(workshop_ids, "designer"),
        _roster_or_none(),
        _representation_or_none(),
    )

    scoring_read = scored_map is not None
    progress: dict[str, dict[str, Any]] = scored_map or {}
    by_workshop = {str(record.id): record for record in records}

    rows: dict[str, dict[str, Any]] = {}
    withheld: set[str] = set()
    counted: set[tuple[str, str]] = set()

    def _person(user: Any) -> dict[str, Any] | None:
        user_id = str(getattr(user, "id", "") or "")
        if not user_id:
            return None
        if register.is_withheld_person(user):
            withheld.add(user_id)
            return None
        row = rows.get(user_id)
        if row is None:
            row = rows[user_id] = register.new_person_row(user)
            row["workshopsCreated"] = 0
            row["workshopsNamedOn"] = 0
        return row

    def _fold(user: Any, record: Any, *, created: bool) -> None:
        row = _person(user) if user is not None else None
        if row is None or record is None:
            return
        row["workshopsCreated" if created else "workshopsNamedOn"] += 1
        pair = (str(row["id"]), str(record.id))
        if pair in counted:
            return
        counted.add(pair)
        register.count_workshop(row, record)
        register.add_score(row, progress.get(str(record.id)))

    for record in records:
        creator_id = str(getattr(record, "createdById", "") or "")
        if (str(record.id), creator_id) in authored:
            _fold(creators.get(creator_id), record, created=True)
    for link in links:
        _fold(
            getattr(link, "user", None),
            by_workshop.get(str(getattr(link, "designWorkshopId", "") or "")),
            created=False,
        )

    roster_users, roster_truncated = roster if roster is not None else ([], False)
    for user in roster_users:
        _person(user)

    for row in rows.values():
        register.close_person_progress(row, scoring_read=scoring_read)

    payload = _people_envelope(
        register.order_people(list(rows.values())),
        current_user=current_user,
        noun="designer",
        page=clean_page,
        page_size=clean_size,
        skip=skip,
        scan=register.scan_report(total_in_scope, len(records)),
        withheld=len(withheld),
        scoring_read=scoring_read,
        includes_unposted=roster is not None,
        unposted_note=(
            "Empanelled designers holding no workshop in this scope are listed with a measured zero, "
            "so somebody who has been given nothing is visible rather than absent."
            if roster is not None
            else "The designer roster couldn't be read just now, so a designer with no workshop "
            "in this scope may be missing from this list."
        ),
    )
    payload["unpostedAccountsTruncated"] = roster_truncated

    if representation is None:
        payload["rosterRepresentation"] = None
        payload["rosterRepresentationNote"] = (
            "How much of the empanelled roster this register can show could not be measured for "
            "this request. The count above may therefore be smaller than the roster."
        )
    else:
        payload["rosterRepresentation"] = representation
        payload["rosterRepresentationNote"] = register.roster_representation_note(
            representation, int(payload.get("total") or 0)
        )
    return payload


@router.get("/officers")
async def list_officers(
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=MAX_PAGE_SIZE),
    standing: str | None = Query(None, max_length=32),
    current_user: Any = Depends(require_ministry_dashboard_reader),
) -> dict[str, Any]:
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    where = _design_where(current_user, None, standing)
    total_in_scope, records = await register.people_spine(where)
    workshop_ids = [str(record.id) for record in records]

    offered = register.may_read_account_directories(current_user)

    async def _directory_or_none() -> dict[str, Any] | None:
        if not offered:
            return None
        try:
            return await register.officer_accounts()
        except Exception:
            logger.exception("ministry dashboard: reading the officer directory failed")
            return None

    links, scored_map, directory = await gather_reads(
        register.oversight_links(workshop_ids),
        _people_progress_or_none(workshop_ids, "officer"),
        _directory_or_none(),
    )

    scoring_read = scored_map is not None
    progress: dict[str, dict[str, Any]] = scored_map or {}
    by_workshop = {str(record.id): record for record in records}

    rows: dict[str, dict[str, Any]] = {}
    withheld: set[str] = set()

    def _person(user: Any) -> dict[str, Any] | None:
        user_id = str(getattr(user, "id", "") or "")
        if not user_id:
            return None
        if register.is_withheld_person(user):
            withheld.add(user_id)
            return None
        row = rows.get(user_id)
        if row is None:
            row = rows[user_id] = register.new_person_row(user)
            row["byCapacity"] = dict.fromkeys(officers.CAPACITIES, 0)
            row["unknownCapacity"] = 0
        return row

    for link in links:
        record = by_workshop.get(str(getattr(link, "designWorkshopId", "") or ""))
        row = _person(getattr(link, "user", None))
        if row is None or record is None:
            continue
        capacity = getattr(link, "capacity", None)
        capacity_name = str(getattr(capacity, "value", capacity) or "")
        if capacity_name in row["byCapacity"]:
            row["byCapacity"][capacity_name] += 1
        else:
            row["unknownCapacity"] += 1
        register.count_workshop(row, record)
        register.add_score(row, progress.get(str(record.id)))

    for entry in (directory or {}).get("users", []):
        if entry.get("role") not in officers.DIRECTORATE_POST_ROLES:
            continue
        _person(
            SimpleNamespace(
                id=entry.get("id"),
                name=entry.get("name"),
                email=entry.get("email"),
                role=entry.get("role"),
            )
        )

    for row in rows.values():
        register.close_person_progress(row, scoring_read=scoring_read)

    payload = _people_envelope(
        register.order_people(list(rows.values())),
        current_user=current_user,
        noun="officer",
        page=clean_page,
        page_size=clean_size,
        skip=skip,
        scan=register.scan_report(total_in_scope, len(records)),
        withheld=len(withheld),
        scoring_read=scoring_read,
        includes_unposted=directory is not None,
        unposted_note=(
            "Assistant Directors and Regional Directors posted to nothing in this scope are listed "
            "with a measured zero. An administrator, including a Ministry Administrator, is listed "
            "only where a workshop names them."
            if directory is not None
            else (
                "This list shows only the officers posted to workshops you can see, so an officer "
                "with no posting here is not listed."
                if not offered
                else "The list of ministry posts couldn't be read just now, so an officer posted "
                "to nothing may be missing from this list."
            )
        ),
    )
    payload["unpostedAccountsTruncated"] = bool((directory or {}).get("truncated", False))
    payload["capacities"] = list(officers.CAPACITIES)
    return payload


@router.get("/inspectors")
async def list_inspectors(
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=MAX_PAGE_SIZE),
    standing: str | None = Query(None, max_length=32),
    current_user: Any = Depends(require_ministry_dashboard_reader),
) -> dict[str, Any]:
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    where = _design_where(current_user, None, standing)
    total_in_scope, records = await register.people_spine(where)
    workshop_ids = [str(record.id) for record in records]

    offered = register.may_read_account_directories(current_user)

    async def _feedback_or_none() -> dict[str, dict[str, int]] | None:
        try:
            return await register.inspection_feedback_counts(workshop_ids)
        except Exception:
            logger.exception(
                "ministry dashboard: counting inspection feedback on %d workshop(s) failed",
                len(workshop_ids),
            )
            return None

    async def _directory_or_none() -> dict[str, Any] | None:
        if not offered:
            return None
        try:
            return await register.inspector_accounts()
        except Exception:
            logger.exception("ministry dashboard: reading the eligible-inspector directory failed")
            return None

    links, scored_map, feedback, directory = await gather_reads(
        register.inspector_links(workshop_ids),
        _people_progress_or_none(workshop_ids, "inspector"),
        _feedback_or_none(),
        _directory_or_none(),
    )

    scoring_read = scored_map is not None
    progress: dict[str, dict[str, Any]] = scored_map or {}
    feedback_read = feedback is not None
    filed = (feedback or {}).get("filed", {})
    sent_back = (feedback or {}).get("sentBack", {})
    by_workshop = {str(record.id): record for record in records}

    rows: dict[str, dict[str, Any]] = {}
    withheld: set[str] = set()

    def _person(user: Any) -> dict[str, Any] | None:
        user_id = str(getattr(user, "id", "") or "")
        if not user_id:
            return None
        if register.is_withheld_person(user):
            withheld.add(user_id)
            return None
        row = rows.get(user_id)
        if row is None:
            row = rows[user_id] = register.new_person_row(user)
            row["feedbackFiled"] = filed.get(user_id, 0) if feedback_read else None
            row["sendBacks"] = sent_back.get(user_id, 0) if feedback_read else None
        return row

    for link in links:
        record = by_workshop.get(str(getattr(link, "designWorkshopId", "") or ""))
        row = _person(getattr(link, "user", None))
        if row is None or record is None:
            continue
        register.count_workshop(row, record)
        register.add_score(row, progress.get(str(record.id)))

    for entry in (directory or {}).get("users", []):
        if entry.get("role") not in INSPECTION_ROLES:
            continue
        _person(
            SimpleNamespace(
                id=entry.get("id"),
                name=entry.get("name"),
                email=entry.get("email"),
                role=entry.get("role"),
            )
        )

    for row in rows.values():
        register.close_person_progress(row, scoring_read=scoring_read)

    ordered = register.order_people(list(rows.values()))
    payload = _people_envelope(
        ordered,
        current_user=current_user,
        noun="inspector",
        page=clean_page,
        page_size=clean_size,
        skip=skip,
        scan=register.scan_report(total_in_scope, len(records)),
        withheld=len(withheld),
        scoring_read=scoring_read,
        includes_unposted=directory is not None,
        unposted_note=(
            "Inspectors who hold no inspection in this scope are listed with a measured zero. An "
            "administrator may also be appointed to inspect a workshop, and is listed only where "
            "an inspection names them."
            if directory is not None
            else (
                "This list shows only the inspectors assigned to workshops you can see, so an "
                "inspector with no assignment here is not listed."
                if not offered
                else "The list of inspectors couldn't be read just now, so an inspector with no "
                "assignment may be missing from this list."
            )
        ),
    )
    payload["unpostedAccountsTruncated"] = bool((directory or {}).get("truncated", False))
    payload["feedbackRead"] = feedback_read
    payload["feedbackFiledTotal"] = sum(filed.values()) if feedback_read else None
    payload["feedbackAttributed"] = (
        sum(int(row["feedbackFiled"] or 0) for row in ordered) if feedback_read else None
    )
    payload["feedbackByAccountsNotListed"] = (
        max(0, int(payload["feedbackFiledTotal"]) - int(payload["feedbackAttributed"]))
        if feedback_read
        else None
    )
    payload["feedbackNote"] = (
        (
            "Suggestion counts cover the workshops read for this register only. Suggestions "
            "filed by people this list does not name — anyone who is not an inspector on those "
            "workshops, and administrators — are counted separately, and that count is usually "
            "zero."
        )
        if feedback_read
        else (
            "Inspection feedback couldn't be counted just now, so these counts are empty. "
            "Suggestions may still have been filed."
        )
    )
    return payload
