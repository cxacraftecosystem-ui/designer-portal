from __future__ import annotations

from typing import Any

from app.core.db import db
from app.core.deps import is_admin, role_value
from app.services import (
    custom_sections,
    design_workshop_inspectors as inspector_service,
    design_workshop_oversight as officer_service,
    designers,
)
from app.services.concurrency import gather_reads
from app.services.design_workshop_oversight import oversight_by_clause
from app.services.design_workshops import workshop_completeness
from app.services.stage_schema import stages
from app.services.text_format import by_count, count_noun

STANDING_GROUPS: dict[str, tuple[str, ...]] = {
    "registered": ("DRAFT",),
    "ongoing": ("IN_PROGRESS", "NEEDS_REVISION", "PRE_SUBMISSION"),
    "completed": ("COMPLETE", "SUBMITTED", "APPROVED", "ARCHIVED"),
}

ALL_STANDINGS: tuple[str, ...] = (
    "DRAFT",
    "IN_PROGRESS",
    "COMPLETE",
    "PRE_SUBMISSION",
    "NEEDS_REVISION",
    "SUBMITTED",
    "APPROVED",
    "ARCHIVED",
)


def _assert_partition() -> None:
    grouped: list[str] = [status for members in STANDING_GROUPS.values() for status in members]
    duplicates = {status for status in grouped if grouped.count(status) > 1}
    if duplicates:
        raise RuntimeError(
            f"ministry dashboard standing groups double-count {sorted(duplicates)} — "
            "a workshop would be counted in two buckets of one summary"
        )
    missing = set(ALL_STANDINGS) - set(grouped)
    if missing:
        raise RuntimeError(
            f"ministry dashboard standing groups do not cover {sorted(missing)} — "
            "a workshop in that state would be unreachable from every filter on the screen"
        )
    unknown = set(grouped) - set(ALL_STANDINGS)
    if unknown:
        raise RuntimeError(
            f"ministry dashboard standing groups name {sorted(unknown)}, which is not a "
            "DesignWorkshopStatus"
        )


_assert_partition()


def standing_clause(group: str | None) -> dict[str, Any] | None:
    members = STANDING_GROUPS.get((group or "").strip().lower())
    return {"status": {"in": list(members)}} if members else None



def sees_whole_estate(user: Any) -> bool:
    return is_admin(user) or role_value(user) == "MINISTRY_ADMIN"


def scope_clause(user: Any) -> dict[str, Any] | None:
    if sees_whole_estate(user):
        return None
    return oversight_by_clause(str(getattr(user, "id", "") or ""))


def scope_label(user: Any) -> str:
    if sees_whole_estate(user):
        return "Every workshop on the platform."
    return (
        "The workshops you were named on as Assistant Director or Regional Director. A Ministry "
        "Administrator posts an officer to a workshop on Workshop oversight; until they have, there "
        "is nothing here to read."
    )


PROGRESS_SCORE_CAP = 100


async def load_definitions(workshop_ids: list[str]) -> dict[str, custom_sections.CustomDefinition]:
    if not workshop_ids:
        return {}
    sections = await db.dwcustomsection.find_many(
        where={"designWorkshopId": {"in": workshop_ids}}, order={"sortOrder": "asc"}
    )
    if not sections:
        return {}
    field_rows = await db.dwcustomfield.find_many(
        where={"sectionId": {"in": [s.id for s in sections]}}, order={"sortOrder": "asc"}
    )
    fields_by_section: dict[str, list[Any]] = {}
    for row in field_rows:
        fields_by_section.setdefault(str(row.sectionId), []).append(
            custom_sections._field_from_row(row)
        )

    sections_by_workshop: dict[str, list[Any]] = {}
    for row in sections:
        sections_by_workshop.setdefault(str(row.designWorkshopId), []).append(
            custom_sections._section_from_row(row, fields_by_section.get(str(row.id), []))
        )

    return {
        workshop_id: custom_sections.CustomDefinition(
            sections=tuple(specs),
            version=custom_sections.custom_schema_version(tuple(specs)),
        )
        for workshop_id, specs in sections_by_workshop.items()
    }


def _roll_up(completeness: dict[str, Any]) -> dict[str, Any]:
    scores = list(completeness.values())
    if not scores:
        return {
            "percent": None,
            "stagesTotal": len(stages()),
            "stagesComplete": None,
            "requiredTotal": None,
            "requiredFilled": None,
        }
    required_total = sum(int(score["requiredTotal"]) for score in scores)
    required_filled = sum(int(score["requiredFilled"]) for score in scores)
    return {
        "percent": 100 if required_total == 0 else round(100 * required_filled / required_total),
        "stagesTotal": len(scores),
        "stagesComplete": sum(1 for score in scores if score["isComplete"]),
        "requiredTotal": required_total,
        "requiredFilled": required_filled,
    }


def unscored(reason: str) -> dict[str, Any]:
    return {
        "percent": None,
        "stagesTotal": len(stages()),
        "stagesComplete": None,
        "requiredTotal": None,
        "requiredFilled": None,
        "definitionRead": False,
        "unscoredReason": reason,
    }


async def progress_for(workshop_ids: list[str]) -> dict[str, dict[str, Any]]:
    ids = workshop_ids[:PROGRESS_SCORE_CAP]
    if not ids:
        return {}

    entries = await db.dwstageentry.find_many(
        where={"designWorkshopId": {"in": ids}, "deletedAt": None}, order={"ordinal": "asc"}
    )
    definitions = await load_definitions(ids)

    by_workshop: dict[str, list[Any]] = {workshop_id: [] for workshop_id in ids}
    for row in entries:
        bucket = by_workshop.get(str(row.designWorkshopId))
        if bucket is not None:
            bucket.append(row)

    out: dict[str, dict[str, Any]] = {}
    for workshop_id in ids:
        definition = definitions.get(workshop_id, custom_sections.EMPTY_DEFINITION)
        rolled = _roll_up(
            workshop_completeness(by_workshop.get(workshop_id, []), definition=definition)
        )
        rolled["definitionRead"] = True
        rolled["customSchemaVersion"] = definition.version
        out[workshop_id] = rolled
    return out



def _group_count(row: Any) -> int:
    count = row.get("_count") if isinstance(row, dict) else None
    if isinstance(count, dict):
        return int(count.get("_all") or 0)
    return int(count or 0)


async def beneficiary_counts(workshop_ids: list[str]) -> dict[str, int]:
    if not workshop_ids:
        return {}
    groups = await db.artisan.group_by(
        by=["designWorkshopId"],
        count=True,
        where={"designWorkshopId": {"in": workshop_ids}},
    )
    out: dict[str, int] = {}
    for row in groups:
        key = row.get("designWorkshopId") if isinstance(row, dict) else None
        if not key:
            continue
        out[str(key)] = _group_count(row)
    return out


async def legacy_beneficiary_counts(workshop_ids: list[str]) -> dict[str, int]:
    if not workshop_ids:
        return {}
    groups = await db.workshopartisan.group_by(
        by=["workshopId"], count=True, where={"workshopId": {"in": workshop_ids}}
    )
    out: dict[str, int] = {}
    for row in groups:
        key = row.get("workshopId") if isinstance(row, dict) else None
        if not key:
            continue
        out[str(key)] = _group_count(row)
    return out


def group_of(status: str | None) -> str | None:
    for name, members in STANDING_GROUPS.items():
        if status in members:
            return name
    return None



PEOPLE_WORKSHOP_SCAN = 500

PEOPLE_SCAN_ORDER: list[dict[str, str]] = [
    {"updatedAt": "desc"},
    {"createdAt": "desc"},
    {"id": "asc"},
]

WITHHELD_PERSON_ROLES: tuple[str, ...] = tuple(designers.NEVER_ROSTER_GATED_ROLES)


def people_scope_label(user: Any, *, noun: str, includes_unposted: bool) -> str:
    if sees_whole_estate(user):
        head = (
            f"Every {noun} on a design & prototype workshop on the platform. Every figure counts "
            "workshops, not people."
        )
        tail = (
            f" It also names every {noun} account holding nothing at all, with a measured zero, so "
            "somebody who has been given no work is visible rather than absent."
            if includes_unposted
            else " An account with no workshop at all does not appear here."
        )
        return head + tail
    head = (
        f"The {noun}s on the workshops you were named on as Assistant Director or Regional "
        "Director. The counts beside each name are counts WITHIN your postings rather than on the "
        "platform. A Ministry Administrator posts an officer to a workshop on Workshop oversight; "
        "until they have, there is nothing here to read."
    )
    tail = (
        f" The list also names {noun}s holding nothing in your postings, with a measured zero, so "
        "somebody who has been given no work is visible rather than absent."
        if includes_unposted
        else f" A {noun} working solely on workshops you were not posted to is absent from this "
        "list entirely."
    )
    return head + tail


def scan_report(total_in_scope: int, read: int) -> dict[str, Any]:
    truncated = read < total_in_scope
    return {
        "workshopsInScope": total_in_scope,
        "workshopsRead": read,
        "workshopScanCap": PEOPLE_WORKSHOP_SCAN,
        "truncated": truncated,
        "truncationNote": (
            (
                f"Built from the {read} most recently updated workshops of {total_in_scope} in "
                f"scope — this register scans at most {PEOPLE_WORKSHOP_SCAN}. Somebody whose "
                "workshops are all older than that is missing from this list, and every count "
                "below is a count within the workshops that were read."
            )
            if truncated
            else None
        ),
    }


async def people_spine(
    where: dict[str, Any], *, take: int = PEOPLE_WORKSHOP_SCAN
) -> tuple[int, list[Any]]:
    total, records = await gather_reads(
        db.designworkshop.count(where=where),
        db.designworkshop.find_many(where=where, take=take, order=PEOPLE_SCAN_ORDER),
    )
    return int(total), list(records)


def is_withheld_person(user: Any) -> bool:
    return role_value(user) in WITHHELD_PERSON_ROLES


def new_person_row(user: Any) -> dict[str, Any]:
    row = designers.assignable_designers_payload([user])[0]
    row.update(
        {
            "workshops": 0,
            "registered": 0,
            "ongoing": 0,
            "completed": 0,
            "unclassifiedStanding": 0,
            "workshopsScored": 0,
            "percent": None,
            "stagesComplete": None,
            "stagesTotal": None,
            "requiredTotal": None,
            "requiredFilled": None,
            "progressReason": None,
        }
    )
    return row


def count_workshop(row: dict[str, Any], record: Any) -> None:
    row["workshops"] += 1
    group = group_of(getattr(record, "status", None))
    row["unclassifiedStanding" if group is None else group] += 1


def add_score(row: dict[str, Any], scored: dict[str, Any] | None) -> None:
    if not scored or scored.get("requiredTotal") is None:
        return
    row["workshopsScored"] += 1
    for key in ("requiredTotal", "requiredFilled", "stagesComplete", "stagesTotal"):
        row[key] = int(row[key] or 0) + int(scored.get(key) or 0)


def close_person_progress(row: dict[str, Any], *, scoring_read: bool) -> None:
    if int(row["workshops"]) == 0:
        row["percent"] = None
        row["progressReason"] = "noWorkshops"
        return
    if not scoring_read:
        row["percent"] = None
        row["progressReason"] = "unreadable"
        return
    if row["workshopsScored"] == 0:
        row["percent"] = None
        row["progressReason"] = "capped"
        return
    required_total = int(row["requiredTotal"] or 0)
    row["percent"] = (
        100 if required_total == 0 else round(100 * int(row["requiredFilled"] or 0) / required_total)
    )
    row["progressReason"] = None


def order_people(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return sorted(
        rows,
        key=lambda row: (
            -int(row["workshops"]),
            (row.get("name") or "").casefold(),
            str(row["id"]),
        ),
    )



async def designer_links(workshop_ids: list[str]) -> list[Any]:
    if not workshop_ids:
        return []
    return await db.designworkshopviewer.find_many(
        where={"designWorkshopId": {"in": workshop_ids}}, include={"user": True}
    )


async def creator_accounts(records: list[Any]) -> dict[str, Any]:
    ids = sorted({str(getattr(record, "createdById", "") or "") for record in records} - {""})
    if not ids:
        return {}
    users = await db.user.find_many(where={"id": {"in": ids}})
    return {str(user.id): user for user in users}


async def oversight_links(workshop_ids: list[str]) -> list[Any]:
    if not workshop_ids:
        return []
    return await db.designworkshopoversight.find_many(
        where={"designWorkshopId": {"in": workshop_ids}}, include={"user": True}
    )


async def inspector_links(workshop_ids: list[str]) -> list[Any]:
    if not workshop_ids:
        return []
    return await db.designworkshopinspector.find_many(
        where={"designWorkshopId": {"in": workshop_ids}}, include={"user": True}
    )


def _counts_by_key(groups: Any, key: str) -> dict[str, int]:
    out: dict[str, int] = {}
    for row in groups or []:
        identifier = row.get(key) if isinstance(row, dict) else None
        if not identifier:
            continue
        out[str(identifier)] = _group_count(row)
    return out


async def inspection_feedback_counts(workshop_ids: list[str]) -> dict[str, dict[str, int]]:
    if not workshop_ids:
        return {"filed": {}, "sentBack": {}}
    where: dict[str, Any] = {"designWorkshopId": {"in": workshop_ids}, "byApprovingAuthority": False}
    filed, sent_back = await gather_reads(
        db.dwinspectionfeedback.group_by(by=["actorId"], count=True, where=where),
        db.dwinspectionfeedback.group_by(
            by=["actorId"], count=True, where={**where, "sentBack": True}
        ),
    )
    return {
        "filed": _counts_by_key(filed, "actorId"),
        "sentBack": _counts_by_key(sent_back, "actorId"),
    }



def may_read_account_directories(user: Any) -> bool:
    return sees_whole_estate(user)


async def officer_accounts() -> dict[str, Any]:
    return await officer_service.officer_directory()


async def inspector_accounts() -> dict[str, Any]:
    return await inspector_service.eligible_inspectors()


async def roster_representation() -> dict[str, Any]:
    from app.services.design_workshop_viewers import active_roster_emails

    admitted, roster_cut = await active_roster_emails()
    eligible_roles = [designers.ROSTER_GATED_ROLE]
    if not admitted:
        return {
            "rosterAdmitted": 0,
            "rosterWithoutAccount": 0,
            "rosterOtherRole": 0,
            "rosterReadTruncated": roster_cut,
        }

    accounts = await db.user.find_many(
        where={"email": {"in": admitted, "mode": "insensitive"}},
        take=designers.DIRECTORY_TAKE,
    )
    matched = {str(getattr(user, "email", "") or "").strip().lower() for user in accounts}
    matched.discard("")
    other_role = sum(1 for user in accounts if role_value(user) not in eligible_roles)

    return {
        "rosterAdmitted": len(admitted),
        "rosterWithoutAccount": max(0, len(admitted) - len(matched)),
        "rosterOtherRole": other_role,
        "rosterReadTruncated": roster_cut or len(accounts) >= designers.DIRECTORY_TAKE,
    }


def roster_representation_note(report: dict[str, Any], listed: int) -> str | None:
    missing = int(report.get("rosterWithoutAccount") or 0)
    other = int(report.get("rosterOtherRole") or 0)
    if missing == 0 and other == 0:
        return None

    parts: list[str] = []
    if missing:
        parts.append(
            f"{count_noun(missing, 'empanelled designer')} {by_count(missing, 'has', 'have')} not "
            "created an account yet, so this register "
            "cannot show them — there is no role, no workshop and no progress to report against an "
            "invitation."
        )
    if other:
        parts.append(
            f"{other} further empanelled address(es) hold an account under a role this register "
            "does not list, which includes any platform administrator."
        )
    parts.append(
        f"{listed} of {int(report.get('rosterAdmitted') or 0)} empanelled designers are listed above."
    )
    if report.get("rosterReadTruncated"):
        parts.append(
            "The roster read was cut at its own ceiling, so these figures are a floor rather than a "
            "total."
        )
    return " ".join(parts)


async def empanelled_designer_accounts() -> tuple[list[Any], bool]:
    users = await designers.workshop_capable_accounts(
        search=None, include_suspended=False, include_admins=False
    )
    return list(users), len(users) >= designers.DIRECTORY_TAKE


ATTENTION_KEYS: tuple[str, ...] = ("awaitingApproval", "returned", "overdue", "setupIncomplete")

OPEN_STATUSES: tuple[str, ...] = (*STANDING_GROUPS["registered"], *STANDING_GROUPS["ongoing"])

SETUP_SCAN_CAP = 2000


def _today_midnight() -> Any:
    from datetime import UTC, datetime

    now = datetime.now(UTC)
    return datetime(now.year, now.month, now.day, tzinfo=UTC)


def attention_clause(key: str | None) -> dict[str, Any] | None:
    clean = (key or "").strip()
    if clean == "awaitingApproval":
        return {"status": "SUBMITTED"}
    if clean == "returned":
        return {"status": "NEEDS_REVISION"}
    if clean == "overdue":
        return {"endDate": {"lt": _today_midnight()}, "status": {"in": list(OPEN_STATUSES)}}
    return None


async def setup_incomplete_ids(base: dict[str, Any]) -> tuple[list[str], bool]:
    from app.services.stage_schema import stage, stage_completeness

    where = {"AND": [base, {"status": {"in": list(OPEN_STATUSES)}}]}
    records = await db.designworkshop.find_many(
        where=where, order={"id": "asc"}, take=SETUP_SCAN_CAP + 1
    )
    capped = len(records) > SETUP_SCAN_CAP
    ids = [str(record.id) for record in records[:SETUP_SCAN_CAP]]
    if not ids:
        return [], capped
    rows = await db.dwstageentry.find_many(
        where={"designWorkshopId": {"in": ids}, "stageKey": "WORKSHOP_SETUP", "deletedAt": None},
        order={"ordinal": "asc"},
    )
    singletons: dict[str, dict[str, Any]] = {}
    for row in rows:
        if row.designWorkshopId in singletons or row.entityKey != "workshopSetup":
            continue
        singletons[row.designWorkshopId] = dict(row.data or {})
    spec = stage("WORKSHOP_SETUP")
    incomplete = [
        workshop_id
        for workshop_id in ids
        if not stage_completeness(spec, singletons.get(workshop_id) or {}, {}).is_complete
    ]
    return incomplete, capped


async def state_breakdown(base: dict[str, Any]) -> list[dict[str, Any]]:
    groups = await db.designworkshop.group_by(by=["state"], where=base, count=True)
    out = [
        {
            "state": group.get("state") or None,
            "count": _group_count(group),
        }
        for group in groups
    ]
    return sorted(out, key=lambda row: (-row["count"], row["state"] is None, row["state"] or ""))


async def sanctioned_total(base: dict[str, Any]) -> dict[str, Any]:
    from decimal import Decimal

    orders = await db.sanctionorder.find_many(where={"designWorkshop": {"is": base}})
    total = sum((Decimal(str(order.sanctionAmount)) for order in orders), Decimal(0))
    return {"orders": len(orders), "amount": f"{total:.2f}"}
