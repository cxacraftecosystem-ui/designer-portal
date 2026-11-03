import asyncio
import logging
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import (
    ROLE_LABELS,
    ROLE_RANK,
    assert_can_delete,
    get_current_user,
    get_value,
    is_content_admin,
    is_empty_value,
    is_master_admin,
    require_content_admin,
    role_rank,
    role_value,
)
from app.schemas.tasks import TaskBatchCreate, TaskCreate, TaskUpdate
from app.services.concurrency import gather_reads
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import clean_data, contains, public_encode

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/tasks", tags=["tasks"])

TASK_STATUSES = {"OPEN", "IN_PROGRESS", "DONE", "CANCELLED"}
LIVE_STATUSES = {"OPEN", "IN_PROGRESS"}

INCLUDE = {"assignee": True, "createdBy": True, "workshop": True}

VIEWS = {"assigned", "created", "all"}

RECORD_TYPE_ORDER = ["artisan", "product", "process", "tool", "questionnaire", "media"]
RECORD_TYPES = set(RECORD_TYPE_ORDER)

RECORD_TYPE_LABELS: dict[str, tuple[str, str]] = {
    "artisan": ("artisan", "artisans"),
    "product": ("product", "products"),
    "process": ("process", "processes"),
    "tool": ("tool", "tools"),
    "questionnaire": ("questionnaire interview", "questionnaire interviews"),
    "media": ("media file", "media files"),
}

DERIVED_TASK_LIMIT = 150
DERIVED_CONCURRENCY = 8

ROLLUP_SCAN_LIMIT = 2000

TASK_OPTION_USER_LIMIT = 500
TASK_OPTION_WORKSHOP_LIMIT = 200
TASK_OPTION_ARTISAN_LIMIT = 500



async def require_task(task_id: str) -> Any:
    task = await db.assignedtask.find_unique(where={"id": task_id}, include=INCLUDE)
    if not task:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Task not found")
    return task


def assert_status_value(value: str) -> None:
    if value not in TASK_STATUSES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"status must be one of {sorted(TASK_STATUSES)}",
        )


def assignable_or_refuse(assigner: Any, assignee: Any | None) -> Any:
    if not assignee:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Assignee not found")
    if assignee.id == get_value(assigner, "id"):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="You cannot assign a task to yourself",
        )
    if not is_master_admin(assigner) and role_rank(assignee) >= role_rank(assigner):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only assign tasks to people with a role below your own",
        )
    return assignee


async def assert_assignable(assigner: Any, assignee_id: str) -> Any:
    return assignable_or_refuse(assigner, await db.user.find_unique(where={"id": assignee_id}))


async def assert_all_assignable(assigner: Any, assignee_ids: list[str]) -> None:
    rows = await db.user.find_many(where={"id": {"in": assignee_ids}})
    by_id = {row.id: row for row in rows}
    for assignee_id in assignee_ids:
        assignable_or_refuse(assigner, by_id.get(assignee_id))


def has_task_authority(user: Any, tasks: Any) -> bool:
    if is_master_admin(user):
        return True
    uid = get_value(user, "id")
    return all(get_value(task, "createdById") == uid for task in tasks)


def assert_task_authority(user: Any, tasks: Any, *, detail: str) -> None:
    if not has_task_authority(user, tasks):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=detail)


def dedupe(values: list[str] | None) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for value in values or []:
        clean = (value or "").strip()
        if clean and clean not in seen:
            seen.add(clean)
            out.append(clean)
    return out


def normalize_record_types(values: list[str] | None) -> list[str]:
    requested = {value.strip().lower() for value in (values or []) if value and value.strip()}
    unknown = sorted(requested - RECORD_TYPES)
    if unknown:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Unknown recordTypes {unknown}; expected any of {RECORD_TYPE_ORDER}",
        )
    return [kind for kind in RECORD_TYPE_ORDER if kind in requested]


@dataclass(slots=True)
class ResolvedScope:
    workshop: Any | None = None
    recordTypes: list[str] = field(default_factory=list)
    artisans: list[Any] = field(default_factory=list)
    sections: list[Any] = field(default_factory=list)
    targetCount: int | None = None

    @property
    def artisanIds(self) -> list[str]:
        return [artisan.id for artisan in self.artisans]

    @property
    def sectionIds(self) -> list[str]:
        return [section.id for section in self.sections]


async def resolve_scope(
    *,
    workshop_id: str | None,
    record_types: list[str] | None,
    artisan_ids: list[str] | None,
    section_ids: list[str] | None,
    target_count: int | None,
    require_work: bool = True,
) -> ResolvedScope:
    workshop = None
    if workshop_id:
        workshop = await db.workshop.find_unique(where={"id": workshop_id})
        if not workshop:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Workshop not found")

    types = normalize_record_types(record_types)

    wanted_artisans = dedupe(artisan_ids)
    artisans: list[Any] = []
    if wanted_artisans:
        artisans = await db.artisan.find_many(
            where={"id": {"in": wanted_artisans}}, order={"name": "asc"}
        )
        missing = sorted(set(wanted_artisans) - {artisan.id for artisan in artisans})
        if missing:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"Unknown artisanIds: {missing}",
            )

    wanted_sections = dedupe(section_ids)
    sections: list[Any] = []
    if wanted_sections:
        sections = await db.questionnairesection.find_many(
            where={"id": {"in": wanted_sections}}, order={"sortOrder": "asc"}
        )
        missing = sorted(set(wanted_sections) - {section.id for section in sections})
        if missing:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"Unknown sectionIds: {missing}",
            )

    if require_work and not types and not sections:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "A task needs work in it: pass recordTypes "
                f"(any of {RECORD_TYPE_ORDER}) and/or sectionIds."
            ),
        )

    return ResolvedScope(
        workshop=workshop,
        recordTypes=types,
        artisans=artisans,
        sections=sections,
        targetCount=target_count,
    )



def _and_list(items: list[str]) -> str:
    if not items:
        return ""
    if len(items) == 1:
        return items[0]
    return f"{', '.join(items[:-1])} and {items[-1]}"


def scope_title(scope: ResolvedScope) -> str:
    parts: list[str] = []
    if scope.recordTypes:
        plural = scope.targetCount != 1
        labels = [RECORD_TYPE_LABELS[kind][1 if plural else 0] for kind in scope.recordTypes]
        count = f"{scope.targetCount} " if scope.targetCount else ""
        parts.append(f"Record {count}{_and_list(labels)}")
    if scope.sections:
        codes = ", ".join(section.code for section in scope.sections)
        noun = "section" if len(scope.sections) == 1 else "sections"
        head = "Questionnaire" if not parts else "questionnaire"
        parts.append(f"{head} {noun} {codes}")

    title = " + ".join(parts) or "Field task"
    names = [artisan.name for artisan in scope.artisans]
    if len(names) == 1:
        title += f" for {names[0]}"
    elif len(names) == 2:
        title += f" for {names[0]} and {names[1]}"
    elif names:
        title += f" for {len(names)} artisans"
    if scope.workshop is not None and len(title) + len(scope.workshop.title) + 3 <= 300:
        title += f" ({scope.workshop.title})"
    return title[:300]



async def _count_records(
    kind: str, assignee_id: str, workshop_id: str | None, artisan_ids: list[str]
) -> int:
    in_artisans: dict[str, Any] | None = {"in": artisan_ids} if artisan_ids else None

    if kind == "artisan":
        where: dict[str, Any] = {"createdById": assignee_id}
        if workshop_id:
            where["OR"] = [
                {"workshopId": workshop_id},
                {"workshops": {"some": {"workshopId": workshop_id}}},
            ]
        if in_artisans:
            where["id"] = in_artisans
        return await db.artisan.count(where=where)

    if kind in {"product", "tool"}:
        delegate = db.productdocumentation if kind == "product" else db.tooldocumentation
        where = {"createdById": assignee_id}
        if workshop_id:
            where["workshopId"] = workshop_id
        if in_artisans:
            where["artisanId"] = in_artisans
        return await delegate.count(where=where)

    if kind == "process":
        where = {"createdById": assignee_id}
        if workshop_id:
            where["workshopId"] = workshop_id
        if in_artisans:
            where["product"] = {"is": {"artisanId": in_artisans}}
        return await db.process.count(where=where)

    if kind == "media":
        where = {"uploadedById": assignee_id}
        if workshop_id:
            where["workshopId"] = workshop_id
        if in_artisans:
            where["artisanId"] = in_artisans
        return await db.mediafile.count(where=where)

    if kind == "questionnaire":
        where = {"createdById": assignee_id}
        if workshop_id:
            where["workshopId"] = workshop_id
        if in_artisans:
            where["artisans"] = {"some": {"artisanId": in_artisans}}
        where["deletedAt"] = None
        return await db.questionnaireinterview.count(where=where)

    return 0


async def _count_sections(
    assignee_id: str, workshop_id: str | None, artisan_ids: list[str], section_ids: list[str]
) -> int:
    where: dict[str, Any] = {
        "answeredById": assignee_id,
        "question": {"is": {"sectionId": {"in": section_ids}}},
    }
    where["interview"] = {"is": {"deletedAt": None}}
    if workshop_id:
        where["interview"] = {"is": {"workshopId": workshop_id, "deletedAt": None}}
    responses = await db.questionnaireresponse.find_many(
        where=where,
        include={"question": True, "interview": {"include": {"artisans": True}}},
    )

    scope = set(artisan_ids)
    pairs: set[tuple[str, str]] = set()
    plain_sections: set[str] = set()
    for response in responses:
        if is_empty_value(response.answerText):
            continue
        section_id = get_value(response.question, "sectionId")
        if not section_id or section_id not in section_ids:
            continue
        if not scope:
            plain_sections.add(section_id)
            continue
        for link in get_value(response.interview, "artisans") or []:
            if link.artisanId in scope:
                pairs.add((link.artisanId, section_id))
    from app.services import questionnaire_readers

    for subject_key, section_id in await questionnaire_readers.covered_pairs(
        assignee_id, section_ids, workshop_id=workshop_id
    ):
        if not scope:
            plain_sections.add(section_id)
        elif subject_key.startswith("A:") and subject_key[2:] in scope:
            pairs.add((subject_key[2:], section_id))
    return len(pairs) if scope else len(plain_sections)


def _derived_target(task: Any) -> int | None:
    total = 0
    if task.recordTypes:
        if not task.targetCount:
            return None
        total += task.targetCount
    if task.sectionIds:
        total += len(task.sectionIds) * max(1, len(task.artisanIds or []))
    return total or None


def _record_key(task: Any, kind: str, artisan_key: tuple) -> tuple:
    return ("record", kind, task.assigneeId, task.workshopId, artisan_key)


def _section_key(task: Any, artisan_key: tuple) -> tuple:
    return (
        "sections",
        task.assigneeId,
        task.workshopId,
        artisan_key,
        tuple(sorted(task.sectionIds)),
    )


async def derive_progress(tasks: list[Any]) -> dict[str, dict[str, Any]]:
    if not tasks or len(tasks) > DERIVED_TASK_LIMIT:
        return {}

    jobs: dict[tuple, None] = {}
    for task in tasks:
        artisan_key = tuple(sorted(task.artisanIds or []))
        for kind in task.recordTypes or []:
            if kind in RECORD_TYPES:
                jobs[_record_key(task, kind, artisan_key)] = None
        if task.sectionIds:
            jobs[_section_key(task, artisan_key)] = None
    if not jobs:
        return {}

    semaphore = asyncio.Semaphore(DERIVED_CONCURRENCY)

    async def run(key: tuple) -> int:
        async with semaphore:
            if key[0] == "record":
                return await _count_records(key[1], key[2], key[3], list(key[4]))
            return await _count_sections(key[1], key[2], list(key[3]), list(key[4]))

    keys = list(jobs)
    results = await asyncio.gather(*(run(key) for key in keys), return_exceptions=True)
    counts: dict[tuple, int | None] = {}
    for key, result in zip(keys, results, strict=True):
        if isinstance(result, BaseException):
            logger.warning("Derived task progress failed for %s: %s", key, result)
            counts[key] = None
        else:
            counts[key] = result

    derived: dict[str, dict[str, Any]] = {}
    for task in tasks:
        artisan_key = tuple(sorted(task.artisanIds or []))
        breakdown: dict[str, int] = {}
        total: int | None = 0
        for kind in task.recordTypes or []:
            if kind not in RECORD_TYPES:
                continue
            value = counts.get(_record_key(task, kind, artisan_key))
            if value is None:
                total = None
                continue
            breakdown[kind] = value
            if total is not None:
                total += value
        if task.sectionIds:
            value = counts.get(_section_key(task, artisan_key))
            if value is None:
                total = None
            else:
                breakdown["sections"] = value
                if total is not None:
                    total += value
        derived[task.id] = {
            "derivedCount": total,
            "derivedTarget": _derived_target(task),
            "derivedBreakdown": breakdown,
        }
    return derived



def progress_percent(task: Any) -> int | None:
    task_status = get_value(task, "status")
    if task_status == "DONE":
        return 100
    if task_status == "CANCELLED":
        return 0
    target = get_value(task, "targetCount")
    if target:
        reported = min(get_value(task, "progressCount") or 0, target)
        return min(100, round(100 * reported / target))
    return None


def is_overdue(task: Any) -> bool:
    due = get_value(task, "dueAt")
    if not due or get_value(task, "status") not in LIVE_STATUSES:
        return False
    if due.tzinfo is None:
        due = due.replace(tzinfo=UTC)
    return due < datetime.now(UTC)


async def _none() -> list[Any]:
    return []


async def load_scope_lookups(tasks: list[Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    artisan_ids: set[str] = set()
    section_ids: set[str] = set()
    for task in tasks:
        artisan_ids.update(task.artisanIds or [])
        section_ids.update(task.sectionIds or [])
    artisans, sections = await gather_reads(
        db.artisan.find_many(where={"id": {"in": sorted(artisan_ids)}}) if artisan_ids else _none(),
        db.questionnairesection.find_many(where={"id": {"in": sorted(section_ids)}})
        if section_ids
        else _none(),
    )
    return (
        {artisan.id: artisan for artisan in artisans},
        {section.id: section for section in sections},
    )


def user_brief(user: Any) -> dict[str, Any] | None:
    if user is None:
        return None
    role = role_value(user)
    return {
        "id": get_value(user, "id"),
        "name": get_value(user, "name"),
        "email": get_value(user, "email"),
        "role": role,
        "roleLabel": ROLE_LABELS.get(role, role),
    }


def serialize_task(
    task: Any,
    artisan_map: dict[str, Any],
    section_map: dict[str, Any],
    derived: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    payload = public_encode(task)
    payload["workshopTitle"] = get_value(get_value(task, "workshop"), "title")
    payload["artisans"] = [
        {
            "id": artisan_id,
            "name": artisan_map[artisan_id].name,
            "place": artisan_map[artisan_id].place,
        }
        for artisan_id in (task.artisanIds or [])
        if artisan_id in artisan_map
    ]
    payload["sections"] = sorted(
        (
            {
                "id": section_id,
                "code": section_map[section_id].code,
                "title": section_map[section_id].title,
                "sortOrder": section_map[section_id].sortOrder,
            }
            for section_id in (task.sectionIds or [])
            if section_id in section_map
        ),
        key=lambda item: item["sortOrder"],
    )
    payload["recordTypeLabels"] = [
        RECORD_TYPE_LABELS[kind][1] for kind in (task.recordTypes or []) if kind in RECORD_TYPES
    ]
    payload["percentComplete"] = progress_percent(task)
    payload["isOverdue"] = is_overdue(task)
    numbers = derived.get(task.id) or {}
    payload["derivedCount"] = numbers.get("derivedCount")
    payload["derivedTarget"] = numbers.get("derivedTarget", _derived_target(task))
    payload["derivedBreakdown"] = numbers.get("derivedBreakdown", {})
    return payload


async def _empty_derived() -> dict[str, dict[str, Any]]:
    return {}


async def serialize_tasks(
    tasks: list[Any],
    with_derived: bool = True,
    lookups: tuple[dict[str, Any], dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    if lookups is not None:
        artisan_map, section_map = lookups
        derived = await derive_progress(tasks) if with_derived else {}
    else:
        (artisan_map, section_map), derived = await gather_reads(
            load_scope_lookups(tasks),
            derive_progress(tasks) if with_derived else _empty_derived(),
        )
    return [serialize_task(task, artisan_map, section_map, derived) for task in tasks]


def batch_summary(tasks: list[Any], serialized: list[dict[str, Any]]) -> dict[str, Any]:
    head = tasks[0]
    head_payload = serialized[0]
    counts = dict.fromkeys(("OPEN", "IN_PROGRESS", "DONE", "CANCELLED"), 0)
    for task in tasks:
        counts[task.status] = counts.get(task.status, 0) + 1

    target = head.targetCount
    if target:
        denominator = target * len(tasks)
        numerator = sum(min(task.progressCount or 0, target) for task in tasks)
    else:
        denominator = len(tasks)
        numerator = counts["DONE"]

    derived_values = [item.get("derivedCount") for item in serialized]
    known = [value for value in derived_values if value is not None]

    return {
        "batchId": head.batchId,
        "key": head.batchId or f"task:{head.id}",
        "title": head.title,
        "description": head.description,
        "dueAt": public_encode(head.dueAt),
        "createdAt": public_encode(head.createdAt),
        "createdBy": user_brief(get_value(head, "createdBy")),
        "workshopId": head.workshopId,
        "workshopTitle": head_payload.get("workshopTitle"),
        "recordTypes": head.recordTypes or [],
        "recordTypeLabels": head_payload.get("recordTypeLabels", []),
        "artisans": head_payload.get("artisans", []),
        "sections": head_payload.get("sections", []),
        "targetCount": target,
        "assigneeCount": len(tasks),
        "statusCounts": counts,
        "doneCount": counts["DONE"],
        "openCount": counts["OPEN"] + counts["IN_PROGRESS"],
        "overdueCount": sum(1 for item in serialized if item.get("isOverdue")),
        "reportedTotal": sum(task.progressCount or 0 for task in tasks),
        "derivedTotal": sum(known) if known else None,
        "percentComplete": round(100 * numerator / denominator) if denominator else 0,
        "assignees": [
            {
                "taskId": task.id,
                "user": user_brief(get_value(task, "assignee")),
                "status": task.status,
                "progressCount": task.progressCount or 0,
                "derivedCount": item.get("derivedCount"),
                "percentComplete": item.get("percentComplete"),
                "completedAt": public_encode(task.completedAt),
            }
            for task, item in zip(tasks, serialized, strict=True)
        ],
    }



@router.post("", status_code=status.HTTP_201_CREATED)
async def create_task(
    payload: TaskCreate, current_user: Any = Depends(require_content_admin)
) -> dict[str, Any]:
    await assert_assignable(current_user, payload.assigneeId)
    scope = await resolve_scope(
        workshop_id=payload.workshopId,
        record_types=payload.recordTypes,
        artisan_ids=payload.artisanIds,
        section_ids=payload.sectionIds,
        target_count=payload.targetCount,
        require_work=False,
    )
    data = clean_data(payload.model_dump(exclude_unset=True), title_case=False)
    data["recordTypes"] = scope.recordTypes
    data["artisanIds"] = scope.artisanIds
    data["sectionIds"] = scope.sectionIds
    data["createdById"] = current_user.id
    created = await db.assignedtask.create(data=data, include=INCLUDE)
    return (await serialize_tasks([created]))[0]


@router.post("/batch", status_code=status.HTTP_201_CREATED)
async def create_task_batch(
    payload: TaskBatchCreate, current_user: Any = Depends(require_content_admin)
) -> dict[str, Any]:
    assignee_ids = dedupe(payload.assigneeIds)
    if not assignee_ids:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="assigneeIds must contain at least one user",
        )

    scope = await resolve_scope(
        workshop_id=payload.workshopId,
        record_types=payload.recordTypes,
        artisan_ids=payload.artisanIds,
        section_ids=payload.sectionIds,
        target_count=payload.targetCount,
        require_work=True,
    )
    await assert_all_assignable(current_user, assignee_ids)

    title = (payload.title or "").strip() or scope_title(scope)
    batch_id = f"batch_{uuid4().hex}"
    base: dict[str, Any] = {
        "title": title,
        "description": payload.description,
        "dueAt": payload.dueAt,
        "workshopId": scope.workshop.id if scope.workshop else None,
        "recordTypes": scope.recordTypes,
        "artisanIds": scope.artisanIds,
        "sectionIds": scope.sectionIds,
        "targetCount": scope.targetCount,
        "batchId": batch_id,
        "createdById": current_user.id,
    }

    await db.assignedtask.create_many(
        data=[{**base, "assigneeId": assignee_id} for assignee_id in assignee_ids]
    )
    rows = await db.assignedtask.find_many(where={"batchId": batch_id}, include=INCLUDE)
    by_assignee = {row.assigneeId: row for row in rows}
    created = [by_assignee[aid] for aid in assignee_ids if aid in by_assignee]
    if len(created) != len(assignee_ids):
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=(
                "Some assignments may already be saved. Refresh the list before trying again."
            ),
        )

    tasks = await serialize_tasks(
        created,
        with_derived=False,
        lookups=(
            {artisan.id: artisan for artisan in scope.artisans},
            {section.id: section for section in scope.sections},
        ),
    )
    return {
        "batchId": batch_id,
        "title": title,
        "created": len(tasks),
        "batch": batch_summary(created, tasks),
        "tasks": tasks,
    }



@router.get("")
async def list_tasks(
    current_user: Any = Depends(get_current_user),
    view: str = Query("assigned"),
    statusFilter: str | None = Query(None, alias="status"),
    workshopId: str | None = Query(None),
    assigneeId: str | None = Query(None),
    batchId: str | None = Query(None),
    withDerived: bool = Query(True),
    withStatusCounts: bool = Query(False),
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    if view not in VIEWS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"view must be one of {sorted(VIEWS)}",
        )
    where: dict[str, Any] = {}
    if view == "assigned":
        where["assigneeId"] = current_user.id
    else:
        if not is_content_admin(current_user):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN, detail="Admin access required"
            )
        if view == "created":
            where["createdById"] = current_user.id
        if assigneeId:
            where["assigneeId"] = assigneeId
    if statusFilter:
        assert_status_value(statusFilter)
        where["status"] = statusFilter
    if workshopId:
        where["workshopId"] = workshopId
    if batchId:
        where["batchId"] = batchId

    page, page_size, skip = normalize_pagination(page, pageSize)
    reads: list[Any] = [
        db.assignedtask.count(where=where),
        db.assignedtask.find_many(
            where=where,
            skip=skip,
            take=page_size,
            include=INCLUDE,
            order=[{"dueAt": "asc"}, {"createdAt": "desc"}, {"id": "desc"}],
        ),
    ]
    if withStatusCounts:
        unfiltered = {key: value for key, value in where.items() if key != "status"}
        reads.append(db.assignedtask.group_by(by=["status"], count=True, where=unfiltered))
    total, tasks, *grouped = await gather_reads(*reads)
    items = await serialize_tasks(tasks, with_derived=withDerived)
    payload = page_payload(items, total, page, page_size)
    if withStatusCounts:
        counts = dict.fromkeys(sorted(TASK_STATUSES), 0)
        for group in grouped[0]:
            counts[str(group["status"])] = group["_count"]["_all"]
        payload["statusCounts"] = counts
    return payload


@router.get("/batches")
async def list_task_batches(
    current_user: Any = Depends(require_content_admin),
    view: str = Query("all"),
    workshopId: str | None = Query(None),
    batchId: str | None = Query(None),
    assigneeId: str | None = Query(None),
    statusFilter: str | None = Query(None, alias="status"),
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    if view not in {"created", "all"}:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="view must be one of ['all', 'created']",
        )
    where: dict[str, Any] = {}
    if view == "created":
        where["createdById"] = current_user.id
    if workshopId:
        where["workshopId"] = workshopId
    if batchId:
        where["batchId"] = batchId
    if statusFilter:
        assert_status_value(statusFilter)
    if assigneeId or statusFilter:
        member_where = dict(where)
        if assigneeId:
            member_where["assigneeId"] = assigneeId
        if statusFilter:
            member_where["status"] = statusFilter
        matches = await db.assignedtask.find_many(
            where=member_where,
            take=ROLLUP_SCAN_LIMIT,
            order=[{"createdAt": "desc"}, {"id": "desc"}],
        )
        batch_ids = sorted({task.batchId for task in matches if task.batchId})
        loose_ids = sorted({task.id for task in matches if not task.batchId})
        if not batch_ids and not loose_ids:
            return page_payload([], 0, page, pageSize)
        where = {"OR": [{"batchId": {"in": batch_ids}}, {"id": {"in": loose_ids}}]}

    rows = await db.assignedtask.find_many(
        where=where,
        include=INCLUDE,
        take=ROLLUP_SCAN_LIMIT,
        order=[{"createdAt": "desc"}, {"id": "desc"}],
    )
    grouped: dict[str, list[Any]] = {}
    for task in rows:
        grouped.setdefault(task.batchId or f"task:{task.id}", []).append(task)

    keys = sorted(grouped, key=lambda key: max(t.createdAt for t in grouped[key]), reverse=True)
    total = len(keys)
    page, page_size, skip = normalize_pagination(page, pageSize)
    window = keys[skip : skip + page_size]

    visible = [task for key in window for task in grouped[key]]
    serialized = {item["id"]: item for item in await serialize_tasks(visible)}
    items = [
        batch_summary(grouped[key], [serialized[task.id] for task in grouped[key]])
        for key in window
    ]
    return page_payload(items, total, page, page_size)


@router.get("/progress")
async def task_progress(
    _: Any = Depends(require_content_admin),
    workshopId: str | None = Query(None),
    assigneeId: str | None = Query(None),
    includeFinished: bool = Query(True),
) -> dict[str, Any]:
    workshop = None
    if workshopId:
        workshop = await db.workshop.find_unique(where={"id": workshopId})
        if not workshop:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Workshop not found")

    where: dict[str, Any] = {}
    if workshopId:
        where["workshopId"] = workshopId
    if assigneeId:
        where["assigneeId"] = assigneeId
    if not includeFinished:
        where["status"] = {"in": sorted(LIVE_STATUSES)}

    rows = await db.assignedtask.find_many(
        where=where,
        include=INCLUDE,
        take=ROLLUP_SCAN_LIMIT,
        order=[{"createdAt": "desc"}, {"id": "desc"}],
    )
    serialized = {item["id"]: item for item in await serialize_tasks(rows)}

    by_user: dict[str, list[Any]] = {}
    for task in rows:
        by_user.setdefault(task.assigneeId, []).append(task)

    assignees: list[dict[str, Any]] = []
    for tasks in by_user.values():
        items = [serialized[task.id] for task in tasks]
        counts = dict.fromkeys(("OPEN", "IN_PROGRESS", "DONE", "CANCELLED"), 0)
        for task in tasks:
            counts[task.status] = counts.get(task.status, 0) + 1
        target_total = sum(task.targetCount or 0 for task in tasks)
        reported_total = sum(
            min(task.progressCount or 0, task.targetCount)
            if task.targetCount
            else (task.progressCount or 0)
            for task in tasks
        )
        known = [item["derivedCount"] for item in items if item.get("derivedCount") is not None]
        if target_total:
            percent = round(100 * min(reported_total, target_total) / target_total)
        else:
            percent = round(100 * counts["DONE"] / len(tasks)) if tasks else 0
        assignees.append(
            {
                "user": user_brief(get_value(tasks[0], "assignee")),
                "taskCount": len(tasks),
                "statusCounts": counts,
                "openCount": counts["OPEN"] + counts["IN_PROGRESS"],
                "overdueCount": sum(1 for item in items if item.get("isOverdue")),
                "targetTotal": target_total or None,
                "reportedTotal": reported_total,
                "derivedTotal": sum(known) if known else None,
                "percentComplete": percent,
                "tasks": items,
            }
        )
    assignees.sort(
        key=lambda row: (
            -row["openCount"],
            -row["taskCount"],
            (row["user"] or {}).get("name") or "",
        )
    )

    return {
        "workshopId": workshopId,
        "workshopTitle": get_value(workshop, "title"),
        "assigneeCount": len(assignees),
        "taskCount": len(rows),
        "doneCount": sum(1 for task in rows if task.status == "DONE"),
        "openCount": sum(1 for task in rows if task.status in LIVE_STATUSES),
        "overdueCount": sum(1 for item in serialized.values() if item.get("isOverdue")),
        "truncated": len(rows) >= ROLLUP_SCAN_LIMIT,
        "assignees": assignees,
    }


@router.get("/options")
async def task_options(
    current_user: Any = Depends(require_content_admin),
    workshopId: str | None = Query(None),
    search: str | None = Query(None, max_length=120),
) -> dict[str, Any]:
    term = (search or "").strip()

    user_clauses: list[dict[str, Any]] = [{"id": {"not": get_value(current_user, "id")}}]
    if not is_master_admin(current_user):
        user_clauses.append(
            {"role": {"in": [r for r, rank in ROLE_RANK.items() if rank < role_rank(current_user)]}}
        )
    if term:
        user_clauses.append({"OR": [{"name": contains(term)}, {"email": contains(term)}]})

    workshop_where: dict[str, Any] = {}
    if term:
        workshop_where["OR"] = [{"title": contains(term)}, {"place": contains(term)}]

    artisan_clauses: list[dict[str, Any]] = []
    if workshopId:
        artisan_clauses.append(
            {
                "OR": [
                    {"workshopId": workshopId},
                    {"workshops": {"some": {"workshopId": workshopId}}},
                ]
            }
        )
    if term:
        artisan_clauses.append({"OR": [{"name": contains(term)}, {"place": contains(term)}]})
    artisan_where: dict[str, Any] = {"AND": artisan_clauses} if artisan_clauses else {}

    users, workshops, artisans, sections = await gather_reads(
        db.user.find_many(
            where={"AND": user_clauses},
            order=[{"name": "asc"}, {"id": "asc"}],
            take=TASK_OPTION_USER_LIMIT + 1,
        ),
        db.workshop.find_many(
            where=workshop_where,
            order=[{"date": "desc"}, {"id": "asc"}],
            take=TASK_OPTION_WORKSHOP_LIMIT + 1,
        ),
        db.artisan.find_many(
            where=artisan_where,
            order=[{"name": "asc"}, {"id": "asc"}],
            take=TASK_OPTION_ARTISAN_LIMIT + 1,
        ),
        db.questionnairesection.find_many(where={"isActive": True}, order={"sortOrder": "asc"}),
    )

    assignees_truncated = len(users) > TASK_OPTION_USER_LIMIT
    assignable = [user_brief(user) for user in users[:TASK_OPTION_USER_LIMIT]]
    workshops_truncated = len(workshops) > TASK_OPTION_WORKSHOP_LIMIT
    workshops = workshops[:TASK_OPTION_WORKSHOP_LIMIT]
    artisans_truncated = len(artisans) > TASK_OPTION_ARTISAN_LIMIT
    artisans = artisans[:TASK_OPTION_ARTISAN_LIMIT]

    if assignees_truncated or workshops_truncated or artisans_truncated:
        logger.warning(
            "task options hit a picker ceiling (search=%r, workshopId=%r): assignees=%s "
            "workshops=%s artisans=%s",
            term,
            workshopId,
            assignees_truncated,
            workshops_truncated,
            artisans_truncated,
        )

    return {
        "recordTypes": [
            {
                "value": kind,
                "label": RECORD_TYPE_LABELS[kind][0],
                "pluralLabel": RECORD_TYPE_LABELS[kind][1],
            }
            for kind in RECORD_TYPE_ORDER
        ],
        "assignees": assignable,
        "workshops": [
            {"id": w.id, "title": w.title, "place": w.place, "date": public_encode(w.date)}
            for w in workshops
        ],
        "artisans": [{"id": a.id, "name": a.name, "place": a.place} for a in artisans],
        "assigneesTruncated": assignees_truncated,
        "workshopsTruncated": workshops_truncated,
        "artisansTruncated": artisans_truncated,
        "sections": [
            {"id": s.id, "code": s.code, "title": s.title, "sortOrder": s.sortOrder}
            for s in sections
        ],
    }


@router.get("/{task_id}")
async def get_task(task_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    task = await require_task(task_id)
    if (
        task.assigneeId != current_user.id
        and task.createdById != current_user.id
        and not is_content_admin(current_user)
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the assignee, the creator, or an admin can view this task",
        )
    return (await serialize_tasks([task]))[0]



@router.patch("/{task_id}")
async def update_task(
    task_id: str,
    payload: TaskUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    task = await require_task(task_id)
    data = payload.model_dump(exclude_unset=True)
    for field_name in (
        "title",
        "assigneeId",
        "status",
        "progressCount",
        "recordTypes",
        "artisanIds",
        "sectionIds",
    ):
        if field_name in data and data[field_name] is None:
            data.pop(field_name)
    new_status = data.get("status")
    if new_status is not None:
        assert_status_value(new_status)

    if has_task_authority(current_user, [task]):
        if data.get("assigneeId") and data["assigneeId"] != task.assigneeId:
            await assert_assignable(current_user, data["assigneeId"])
    elif task.assigneeId == current_user.id:
        extra_fields = set(data) - {"status", "progressCount"}
        if extra_fields:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="As the assignee you can only update the task's status and progress.",
            )
        if new_status == "CANCELLED":
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the task creator or an admin can cancel a task",
            )
    else:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Only the assignee, the admin who created this task, or the master admin "
                "can update this task"
            ),
        )

    if {"workshopId", "recordTypes", "artisanIds", "sectionIds", "targetCount"} & set(data):
        scope = await resolve_scope(
            workshop_id=data.get("workshopId", task.workshopId),
            record_types=data.get("recordTypes", task.recordTypes),
            artisan_ids=data.get("artisanIds", task.artisanIds),
            section_ids=data.get("sectionIds", task.sectionIds),
            target_count=data.get("targetCount", task.targetCount),
            require_work=bool(task.recordTypes or task.sectionIds),
        )
        if "recordTypes" in data:
            data["recordTypes"] = {"set": scope.recordTypes}
        if "artisanIds" in data:
            data["artisanIds"] = {"set": scope.artisanIds}
        if "sectionIds" in data:
            data["sectionIds"] = {"set": scope.sectionIds}

    target = data.get("targetCount", task.targetCount)
    if "progressCount" in data:
        reported = max(0, int(data["progressCount"]))
        data["progressCount"] = min(reported, target) if target else reported

    if new_status is not None:
        if new_status == "DONE":
            if task.status != "DONE":
                data["completedAt"] = datetime.now(UTC)
            if target and "progressCount" not in data and (task.progressCount or 0) < target:
                data["progressCount"] = target
        else:
            data["completedAt"] = None

    if not data:
        return (await serialize_tasks([task]))[0]
    updated = await db.assignedtask.update(where={"id": task_id}, data=data, include=INCLUDE)
    return (await serialize_tasks([updated]))[0]


@router.delete("/batch/{batch_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_task_batch(
    batch_id: str, current_user: Any = Depends(require_content_admin)
) -> None:
    tasks = await db.assignedtask.find_many(where={"batchId": batch_id})
    if not tasks:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Batch not found")
    await db.assignedtask.delete_many(where={"batchId": batch_id})
    _audit_withdrawal(current_user, tasks, scope=f"batch={batch_id}")


def _audit_withdrawal(user: Any, tasks: Any, *, scope: str) -> None:
    logger.warning(
        "AUDIT task withdrawal: %s by=%s count=%s assignees=%s",
        scope,
        get_value(user, "email") or get_value(user, "id"),
        len(tasks),
        ",".join(sorted({get_value(task, "assigneeId") or "?" for task in tasks})),
    )


@router.delete("/{task_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_task(task_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    task = await require_task(task_id)
    await db.assignedtask.delete(where={"id": task_id})
    _audit_withdrawal(current_user, [task], scope=f"task={task_id}")
