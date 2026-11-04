from __future__ import annotations

from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from typing import Any

from fastapi import HTTPException, status

from app.core.db import db
from app.core.deps import has_rank, role_value
from app.services.address import INDIAN_STATES_AND_UNION_TERRITORIES, normalize_state
from app.services.annual_plan_xlsx import (
    MAX_PLAN_ROWS,
    ParsedAnnualPlan,
    ParsedPlanRow,
    fold_workshop_no,
    plan_year_label,
)
from app.services.design_workshops import named_designer_team, open_design_workshop
from app.services.stage_schema import enum_label

__all__ = [
    "ANNUAL_PLAN_REFUSAL",
    "COMPARED_FIELDS",
    "MAX_PLAN_ROWS",
    "MAX_REPORTED_CHANGES",
    "apply_parsed_plan",
    "can_manage_annual_plan",
    "can_read_annual_plan",
    "entry_payload",
    "plan_scope",
    "plan_year_label",
    "promote_entry",
    "promotion_title",
    "reinstate_entry",
    "set_regional_states",
    "standing_of",
    "withdraw_entry",
]


ANNUAL_PLAN_REFUSAL = (
    "The annual plan of workshops is managed by the ministry administrator and above. Ask them to "
    "upload or correct the directory."
)


def can_manage_annual_plan(user: Any) -> bool:
    return has_rank(user, "MINISTRY_ADMIN")


REGIONAL_PLAN_ROLE = "REGIONAL_DIRECTOR"


def can_read_annual_plan(user: Any) -> bool:
    return can_manage_annual_plan(user) or role_value(user) == REGIONAL_PLAN_ROLE


async def plan_scope(user: Any) -> list[str] | None:
    if can_manage_annual_plan(user):
        return None
    if role_value(user) != REGIONAL_PLAN_ROLE:
        return []
    rows = await db.regionaldirectorstate.find_many(where={"userId": str(user.id)})
    return sorted({str(row.state) for row in rows})


async def coverage_rows(client: Any, user: Any, *, take: int) -> list[Any]:
    states = await plan_scope(user)
    if states is not None and not states:
        return []
    where: dict[str, Any] = {"withdrawnAt": None}
    if states is not None:
        where["state"] = {"in": states}
    return await client.annualplanentry.find_many(where=where, take=take, order={"id": "asc"})


def in_scope(row: Any, scope: list[str] | None) -> bool:
    if scope is None:
        return True
    return getattr(row, "state", None) in set(scope)


REGIONAL_TARGET_REFUSAL = (
    "States are assigned to Regional Directors only. This account is not a Regional Director, so "
    "the annual plan has no state of theirs to narrow to."
)


def _unknown_states_detail(unknown: list[str]) -> str:
    names = ", ".join(f"'{name}'" for name in unknown)
    verb = (
        "is not an Indian state or union territory"
        if len(unknown) == 1
        else "are not Indian states or union territories"
    )
    return f"{names} {verb}. Choose from the state list."


async def regional_directors() -> list[dict[str, Any]]:
    users = await db.user.find_many(
        where={"role": REGIONAL_PLAN_ROLE}, order=[{"name": "asc"}, {"id": "asc"}]
    )
    ids = [str(user.id) for user in users]
    rows = (
        await db.regionaldirectorstate.find_many(where={"userId": {"in": ids}}) if ids else []
    )
    states: dict[str, list[str]] = {}
    for row in rows:
        states.setdefault(str(row.userId), []).append(str(row.state))
    return [
        {
            "id": str(user.id),
            "name": getattr(user, "name", None) or "",
            "email": getattr(user, "email", None) or "",
            "states": sorted(states.get(str(user.id), [])),
        }
        for user in users
    ]


async def set_regional_states(target: Any, states: list[str], *, actor: Any) -> list[str]:
    if role_value(target) != REGIONAL_PLAN_ROLE:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=REGIONAL_TARGET_REFUSAL
        )
    wanted: list[str] = []
    unknown: list[str] = []
    for raw in states:
        name = normalize_state(raw)
        if name is None:
            continue
        if name not in INDIAN_STATES_AND_UNION_TERRITORIES:
            unknown.append(name)
        elif name not in wanted:
            wanted.append(name)
    if unknown:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=_unknown_states_detail(unknown)
        )
    async with db.tx() as tx:
        await tx.regionaldirectorstate.delete_many(where={"userId": str(target.id)})
        if wanted:
            await tx.regionaldirectorstate.create_many(
                data=[
                    {
                        "userId": str(target.id),
                        "state": name,
                        "assignedById": getattr(actor, "id", None),
                    }
                    for name in wanted
                ]
            )
    return sorted(wanted)



def standing_of(row: Any) -> str:
    if getattr(row, "withdrawnAt", None):
        return "WITHDRAWN"
    if getattr(row, "designWorkshopId", None):
        return "PROMOTED"
    return "PLANNED"


def _iso(value: Any) -> str | None:
    if value is None:
        return None
    return value.isoformat()


def entry_payload(row: Any, *, sanction: dict[str, Any] | None = None) -> dict[str, Any]:
    return {
        "id": row.id,
        "planYear": row.planYear,
        "planYearLabel": plan_year_label(row.planYear),
        "workshopNo": row.workshopNo,
        "plannedTitle": row.plannedTitle,
        "workshopKind": row.workshopKind,
        "workshopKindLabel": (
            enum_label("WORKSHOP_KIND", row.workshopKind) if row.workshopKind else None
        ),
        "craftName": row.craftName,
        "craftId": getattr(row, "craftId", None),
        "clusterName": row.clusterName,
        "state": row.state,
        "district": row.district,
        "venue": row.venue,
        "plannedStartDate": _iso(row.plannedStartDate),
        "plannedEndDate": _iso(row.plannedEndDate),
        "implementingAgency": row.implementingAgency,
        "sponsor": row.sponsor,
        "notes": row.notes,
        "standing": standing_of(row),
        "revision": row.revision,
        "sheetRow": row.sheetRow,
        "sourceFilename": row.sourceFilename,
        "withdrawnAt": _iso(row.withdrawnAt),
        "designWorkshopId": row.designWorkshopId,
        "designWorkshopTitle": getattr(getattr(row, "designWorkshop", None), "title", None),
        "promotedAt": _iso(row.promotedAt),
        "sanction": sanction,
        "createdAt": _iso(row.createdAt),
        "updatedAt": _iso(row.updatedAt),
    }


COMPARED_FIELDS: tuple[str, ...] = (
    "workshopNo",
    "plannedTitle",
    "workshopKind",
    "craftName",
    "clusterName",
    "state",
    "district",
    "venue",
    "plannedStartDate",
    "plannedEndDate",
    "implementingAgency",
    "sponsor",
    "notes",
)

FIELD_LABELS: dict[str, str] = {
    "workshopNo": "Workshop No.",
    "plannedTitle": "Workshop Title",
    "workshopKind": "Type of Workshop",
    "craftName": "Craft",
    "clusterName": "Cluster",
    "state": "State",
    "district": "District",
    "venue": "Venue",
    "plannedStartDate": "Start Date",
    "plannedEndDate": "End Date",
    "implementingAgency": "Implementing Agency",
    "sponsor": "Sponsoring Body",
    "notes": "Remarks",
}

MAX_REPORTED_CHANGES = 500

PROMOTED_CHANGE_REASON = (
    "This workshop has already been opened as a design & prototype workshop. The plan has been "
    "corrected; the workshop itself has not been touched. Correct it on the workshop's own screen."
)

ABSENT_KEPT_REASON = (
    "Not in this sheet. Left in the plan — tick 'withdraw the workshops missing from this sheet' "
    "to withdraw it."
)

ABSENT_PROMOTED_REASON = (
    "Not in this sheet, and it has already been opened as a workshop, so it was left in the plan "
    "whatever the withdraw box said. Cancel the workshop itself if it is not going ahead."
)

CONCURRENT_UPLOAD_REFUSAL = (
    "Somebody else was uploading this year's plan at the same moment, so nothing was written. "
    "Nothing has been half-saved. Upload the same file again — a directory that is already correct "
    "is left exactly as it is."
)


def _as_date(value: Any) -> date | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    return None


def _midnight(value: date | None) -> datetime | None:
    if value is None:
        return None
    return datetime(value.year, value.month, value.day, tzinfo=UTC)


def _differs(field: str, stored: Any, incoming: Any) -> bool:
    if field in ("plannedStartDate", "plannedEndDate"):
        return _as_date(stored) != _as_date(incoming)
    return (stored or None) != (incoming or None)


def _printable(field: str, value: Any) -> str | None:
    if value is None:
        return None
    if field in ("plannedStartDate", "plannedEndDate"):
        parsed = _as_date(value)
        return parsed.isoformat() if parsed else None
    if field == "workshopKind":
        return enum_label("WORKSHOP_KIND", str(value))
    return str(value)


def _create_data(row: ParsedPlanRow, *, plan_year: int, actor_id: str, filename: str | None):
    return {
        "planYear": plan_year,
        "workshopNo": row.workshopNo,
        "workshopNoKey": row.workshopNoKey,
        "plannedTitle": row.plannedTitle,
        "workshopKind": row.workshopKind,
        "craftName": row.craftName,
        "clusterName": row.clusterName,
        "state": row.state,
        "district": row.district,
        "venue": row.venue,
        "plannedStartDate": _midnight(row.plannedStartDate),
        "plannedEndDate": _midnight(row.plannedEndDate),
        "implementingAgency": row.implementingAgency,
        "sponsor": row.sponsor,
        "notes": row.notes,
        "sheetRow": row.sheetRow,
        "sourceFilename": filename,
        "createdById": actor_id,
        "updatedById": actor_id,
    }


def _with_craft(data: dict[str, Any], crafts_by_name: dict[str, Any]) -> dict[str, Any]:
    matched = crafts_by_name.get(str(data.get("craftName") or ""))
    if matched is not None:
        data["craftId"] = matched.id
    return data


async def _write_entry_update(tx: Any, entry_id: str, data: dict[str, Any]) -> None:
    await tx.annualplanentry.update(where={"id": entry_id}, data=data)


async def apply_parsed_plan(
    parsed: ParsedAnnualPlan,
    *,
    plan_year: int,
    actor_id: str,
    source_filename: str | None,
    withdraw_absent: bool,
) -> dict[str, Any]:
    incoming: dict[str, ParsedPlanRow] = {row.workshopNoKey: row for row in parsed.rows}

    existing = await db.annualplanentry.find_many(where={"planYear": plan_year})
    by_key = {row.workshopNoKey: row for row in existing}
    from app.services import craft_master

    crafts_by_name = await craft_master.match_names(db, [row.craftName for row in incoming.values()])

    to_create: list[ParsedPlanRow] = []
    updates: list[tuple[Any, dict[str, Any], bool]] = []
    changes: list[dict[str, Any]] = []
    changes_truncated = False
    unchanged = 0
    reinstated = 0
    updated_after_promotion = 0

    for key, row in incoming.items():
        stored = by_key.get(key)
        if stored is None:
            to_create.append(row)
            continue

        promoted = bool(stored.designWorkshopId)
        data: dict[str, Any] = {}
        row_changed = False
        for field_name in COMPARED_FIELDS:
            new_value = getattr(row, field_name)
            old_value = getattr(stored, field_name)
            if not _differs(field_name, old_value, new_value):
                continue
            row_changed = True
            if field_name == "craftName":
                matched = crafts_by_name.get(str(new_value or ""))
                data["craftId"] = matched.id if matched is not None else None
            data[field_name] = (
                _midnight(new_value)
                if field_name in ("plannedStartDate", "plannedEndDate")
                else new_value
            )
            if len(changes) >= MAX_REPORTED_CHANGES:
                changes_truncated = True
            else:
                changes.append(
                    {
                        "workshopNo": row.workshopNo,
                        "sheetRow": row.sheetRow,
                        "field": field_name,
                        "fieldLabel": FIELD_LABELS[field_name],
                        "from": _printable(field_name, old_value),
                        "to": _printable(field_name, new_value),
                        "reason": PROMOTED_CHANGE_REASON if promoted else None,
                    }
                )

        reinstating = stored.withdrawnAt is not None
        if not row_changed and not reinstating:
            unchanged += 1
            continue

        if reinstating:
            reinstated += 1
            data["withdrawnAt"] = None
            data["withdrawnById"] = None
        if row_changed and promoted:
            updated_after_promotion += 1

        data["sheetRow"] = row.sheetRow
        data["sourceFilename"] = source_filename
        data["updatedById"] = actor_id
        data["revision"] = {"increment": 1}
        updates.append((stored, data, reinstating))

    absent_rows = [row for key, row in by_key.items() if key not in incoming]
    absent_promoted = [row for row in absent_rows if row.designWorkshopId]
    absent_plain = [row for row in absent_rows if not row.designWorkshopId and not row.withdrawnAt]

    now = datetime.now(UTC)
    try:
        async with db.tx(max_wait=timedelta(seconds=10), timeout=timedelta(seconds=60)) as tx:
            if to_create:
                await tx.annualplanentry.create_many(
                    data=[
                        _with_craft(
                            _create_data(
                                row,
                                plan_year=plan_year,
                                actor_id=actor_id,
                                filename=source_filename,
                            ),
                            crafts_by_name,
                        )
                        for row in to_create
                    ]
                )
            for stored, data, _reinstating in updates:
                await _write_entry_update(tx, stored.id, data)
            if withdraw_absent and absent_plain:
                await tx.annualplanentry.update_many(
                    where={"id": {"in": [row.id for row in absent_plain]}},
                    data={"withdrawnAt": now, "withdrawnById": actor_id},
                )
    except Exception as exc:
        if _is_unique_violation(exc):
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT, detail=CONCURRENT_UPLOAD_REFUSAL
            ) from exc
        raise

    return {
        "planYear": plan_year,
        "planYearLabel": plan_year_label(plan_year),
        "sheet": parsed.sheet,
        "sourceFilename": source_filename,
        "rowsRead": len(incoming),
        "created": len(to_create),
        "updated": len(updates),
        "unchanged": unchanged,
        "reinstated": reinstated,
        "withdrawn": len(absent_plain) if withdraw_absent else 0,
        "absent": len(absent_rows),
        "absentPromoted": len(absent_promoted),
        "updatedAfterPromotion": updated_after_promotion,
        "withdrawAbsentRequested": withdraw_absent,
        "changes": changes,
        "changesTruncated": changes_truncated,
        "absentRows": [
            {
                "workshopNo": row.workshopNo,
                "promoted": bool(row.designWorkshopId),
                "reason": ABSENT_PROMOTED_REASON if row.designWorkshopId else ABSENT_KEPT_REASON,
            }
            for row in absent_rows
        ],
        "problems": [problem.payload() for problem in parsed.problems],
    }


def _is_unique_violation(exc: BaseException) -> bool:
    from prisma.errors import PrismaError, UniqueViolationError

    if isinstance(exc, UniqueViolationError):
        return True
    return isinstance(exc, PrismaError) and "unique" in str(exc).lower()



async def withdraw_entry(entry: Any, *, actor_id: str) -> Any:
    if entry.designWorkshopId:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"'{entry.workshopNo}' has already been opened as a workshop, so it cannot be "
                "withdrawn from the plan. Cancel the workshop itself if it is not going ahead."
            ),
        )
    if entry.withdrawnAt:
        return entry
    return await db.annualplanentry.update(
        where={"id": entry.id},
        data={"withdrawnAt": datetime.now(UTC), "withdrawnById": actor_id, "updatedById": actor_id},
    )


async def reinstate_entry(entry: Any, *, actor_id: str) -> Any:
    if not entry.withdrawnAt:
        return entry
    return await db.annualplanentry.update(
        where={"id": entry.id},
        data={"withdrawnAt": None, "withdrawnById": None, "updatedById": actor_id},
    )


def promotion_title(entry: Any) -> str:
    if entry.plannedTitle:
        return str(entry.plannedTitle)[:220]
    where = " · ".join(
        part for part in (entry.craftName, entry.district or entry.state) if part
    )
    title = f"{entry.workshopNo} — {where}" if where else str(entry.workshopNo)
    return title[:220]


def plan_stage_one_values(entry: Any) -> dict[str, Any]:
    start = _as_date(entry.plannedStartDate)
    end = _as_date(entry.plannedEndDate)
    seeded = {
        key: value
        for key, value in (
            ("workshopCode", entry.workshopNo),
            ("workshopKind", entry.workshopKind),
            ("craftRef", getattr(entry, "craftId", None)),
            ("craftName", entry.craftName),
            ("clusterName", entry.clusterName),
            ("state", entry.state),
            ("district", entry.district),
            ("venue", entry.venue),
            ("implementingAgency", entry.implementingAgency),
            ("sponsor", entry.sponsor),
            ("startDate", start.isoformat() if start else None),
            ("endDate", end.isoformat() if end else None),
        )
        if value
    }
    return seeded  # noqa: RET504 - the name is what the promotion test reads


async def promote_entry(
    entry: Any,
    *,
    actor: Any,
    designer_id: str | None,
    designer_ids: list[str],
    template_id: str = "DCH_STANDARD",
) -> Any:
    if entry.designWorkshopId:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"'{entry.workshopNo}' has already been opened as a workshop. Open that workshop "
                "instead of creating a second one for the same planned entry."
            ),
        )
    if entry.withdrawnAt:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"'{entry.workshopNo}' was withdrawn from the "
                f"{plan_year_label(entry.planYear)} plan. Reinstate it first if the workshop is "
                "going ahead after all."
            ),
        )

    seeded = plan_stage_one_values(entry)

    columns: dict[str, Any] = {
        "title": promotion_title(entry),
        "templateId": template_id,
        **{
            key: value
            for key, value in (
                ("workshopKind", entry.workshopKind),
                ("craftId", getattr(entry, "craftId", None)),
                ("craftName", entry.craftName),
                ("clusterName", entry.clusterName),
                ("state", entry.state),
                ("district", entry.district),
                ("startDate", entry.plannedStartDate),
                ("endDate", entry.plannedEndDate),
            )
            if value
        },
    }

    lead_id, granted_ids = named_designer_team(designer_id, designer_ids)

    record = await open_design_workshop(
        actor=actor,
        columns=columns,
        designer_id=lead_id,
        designer_ids=granted_ids,
        seeded=seeded,
    )

    stamped = await db.annualplanentry.update_many(
        where={"id": entry.id, "designWorkshopId": None},
        data={
            "designWorkshopId": record.id,
            "promotedAt": datetime.now(UTC),
            "promotedById": actor.id,
            "updatedById": actor.id,
        },
    )
    if not stamped:
        await db.designworkshop.update(
            where={"id": record.id}, data={"deletedAt": datetime.now(UTC)}
        )
        winner = await db.annualplanentry.find_unique(where={"id": entry.id})
        opened = getattr(winner, "designWorkshopId", None)
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"'{entry.workshopNo}' was opened as a workshop a moment ago — by somebody else, or "
                f"by a retry of this same request — so a second one was not created. Open "
                + (f"workshop {opened}" if opened else "the workshop it became")
                + " instead."
            ),
        )
    return record


SANCTION_PICK_PROMOTED = (
    "Already opened as a workshop. Each planned workshop takes one sanction order."
)
SANCTION_PICK_WITHDRAWN = "Withdrawn from the plan. Ask the Ministry Admin to reinstate it first."
SANCTION_PICK_UNKNOWN = (
    "That planned workshop is not in the annual plan. Look it up by its number again."
)

SANCTION_CANDIDATE_TAKE = 20


def _pick_refusal(row: Any) -> str | None:
    standing = standing_of(row)
    if standing == "PROMOTED":
        return SANCTION_PICK_PROMOTED
    if standing == "WITHDRAWN":
        return SANCTION_PICK_WITHDRAWN
    return None


def sanction_candidate_payload(row: Any) -> dict[str, Any]:
    refusal = _pick_refusal(row)
    return {
        "id": row.id,
        "planYear": row.planYear,
        "planYearLabel": plan_year_label(row.planYear),
        "workshopNo": row.workshopNo,
        "plannedTitle": row.plannedTitle,
        "workshopKindLabel": (
            enum_label("WORKSHOP_KIND", row.workshopKind) if row.workshopKind else None
        ),
        "craftName": row.craftName,
        "clusterName": row.clusterName,
        "state": row.state,
        "district": row.district,
        "plannedStartDate": _iso(row.plannedStartDate),
        "plannedEndDate": _iso(row.plannedEndDate),
        "standing": standing_of(row),
        "pickable": refusal is None,
        "reason": refusal,
    }


async def sanction_pick_scope(user: Any) -> list[str] | None:
    if role_value(user) == REGIONAL_PLAN_ROLE:
        return await plan_scope(user)
    return None


async def sanction_candidates(
    workshop_no: str, *, scope: list[str] | None = None
) -> dict[str, Any]:
    key = fold_workshop_no(workshop_no)
    if not key or scope == []:
        return {"items": [], "truncated": False}
    where: dict[str, Any] = {"workshopNoKey": key}
    if scope is not None:
        where["state"] = {"in": list(scope)}
    rows = await db.annualplanentry.find_many(
        where=where,
        order=[{"planYear": "desc"}, {"id": "asc"}],
        take=SANCTION_CANDIDATE_TAKE + 1,
    )
    return {
        "items": [sanction_candidate_payload(row) for row in rows[:SANCTION_CANDIDATE_TAKE]],
        "truncated": len(rows) > SANCTION_CANDIDATE_TAKE,
    }


async def plan_row_for_sanction(entry_id: str, *, scope: list[str] | None = None) -> Any:
    row = await db.annualplanentry.find_unique(where={"id": entry_id})
    if row is None or not in_scope(row, scope):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=SANCTION_PICK_UNKNOWN
        )
    refusal = _pick_refusal(row)
    if refusal is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail=f"'{row.workshopNo}': {refusal}"
        )
    return row


async def claim_for_sanction(
    entry_id: str, workshop_id: str, *, actor_id: str | None, client: Any
) -> None:
    stamped = await client.annualplanentry.update_many(
        where={"id": entry_id, "designWorkshopId": None, "withdrawnAt": None},
        data={
            "designWorkshopId": workshop_id,
            "promotedAt": datetime.now(UTC),
            "promotedById": actor_id,
            "updatedById": actor_id,
        },
    )
    if not stamped:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                "That planned workshop was opened a moment ago by somebody else, so this order "
                "was not recorded. Look the number up again."
            ),
        )


async def planned_rows_for_workshops(workshop_ids: list[str]) -> dict[str, dict[str, Any]]:
    ids = sorted({wid for wid in workshop_ids if wid})
    if not ids:
        return {}
    rows = await db.annualplanentry.find_many(where={"designWorkshopId": {"in": ids}})
    return {
        str(row.designWorkshopId): {
            "id": row.id,
            "workshopNo": row.workshopNo,
            "planYear": row.planYear,
            "planYearLabel": plan_year_label(row.planYear),
            "plannedTitle": row.plannedTitle,
        }
        for row in rows
    }


async def sanctions_for_rows(rows: list[Any]) -> dict[str, dict[str, Any]]:
    by_workshop = {str(row.designWorkshopId): row.id for row in rows if row.designWorkshopId}
    if not by_workshop:
        return {}
    orders = await db.sanctionorder.find_many(
        where={"designWorkshopId": {"in": list(by_workshop)}}
    )
    out: dict[str, dict[str, Any]] = {}
    for order in orders:
        entry_id = by_workshop.get(str(order.designWorkshopId))
        if entry_id is None:
            continue
        order_date = getattr(order, "sanctionOrderDate", None)
        out[entry_id] = {
            "id": order.id,
            "sanctionOrderNo": order.sanctionOrderNo,
            "sanctionOrderDate": (
                order_date.date().isoformat() if isinstance(order_date, datetime) else None
            ),
            "sanctionAmount": f"{Decimal(str(order.sanctionAmount)):.2f}",
        }
    return out


async def live_entries_for_year(plan_year: int, workshop_kind: str, *, take: int) -> list[Any]:
    return list(
        await db.annualplanentry.find_many(
            where={"planYear": plan_year, "workshopKind": workshop_kind, "withdrawnAt": None},
            order=[{"workshopNoKey": "asc"}],
            take=take,
        )
    )
