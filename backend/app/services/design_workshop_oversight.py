from __future__ import annotations

import logging
from typing import Any

from fastapi import HTTPException, status

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import is_break_glass_master, role_value
from app.schemas import design_workshop_review_loop
from app.services import (
    access_roster,
    design_workshop_posts as posts,
    design_workshop_viewers,
    design_workshops,
)
from app.services.concurrency import gather_reads
from app.services.records import contains

logger = logging.getLogger(__name__)



OFFICER_ROLES = frozenset({"ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN"})

ASSISTANT_DIRECTOR_HOLDER_ROLES = frozenset({"ASSISTANT_DIRECTOR"}) | posts.SERVING_ADMIN_ROLES

REGIONAL_DIRECTOR_HOLDER_ROLES = frozenset({"REGIONAL_DIRECTOR"}) | posts.SERVING_ADMIN_ROLES

OVERSIGHT_CAPACITY_ROLES: dict[str, frozenset[str]] = {
    "ASSISTANT_DIRECTOR": ASSISTANT_DIRECTOR_HOLDER_ROLES,
    "REGIONAL_DIRECTOR": REGIONAL_DIRECTOR_HOLDER_ROLES,
}

OVERSIGHT_HOLDER_ROLES = frozenset().union(*OVERSIGHT_CAPACITY_ROLES.values())

DIRECTORATE_POST_ROLES = OFFICER_ROLES - posts.SERVING_ADMIN_ROLES

CAPACITIES: tuple[str, ...] = tuple(OVERSIGHT_CAPACITY_ROLES)

OVERSIGHT_ASSIGNER_ROLES = frozenset({"MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"})

ELIGIBLE_OFFICER_LIMIT = 2000


def is_officer(user: Any) -> bool:
    return role_value(user) in OFFICER_ROLES if user is not None else False


def may_hold_oversight(user: Any) -> bool:
    return role_value(user) in OVERSIGHT_HOLDER_ROLES if user is not None else False


def can_assign_workshop_oversight(user: Any) -> bool:
    return role_value(user) in OVERSIGHT_ASSIGNER_ROLES if user is not None else False


NOT_AN_OFFICER_DETAIL = (
    "This page is for Assistant Directors and Regional Directors. Sign out and back in, or ask an "
    "administrator."
)

OVERSIGHT_ASSIGNER_REFUSAL = (
    "Naming the designer, the Assistant Director and the Regional Director on a design & prototype "
    "workshop is done by a Ministry Admin, an admin or the master admin. An Assistant Director or "
    "Regional Director reads the workshops they have been assigned on Workshops I monitor."
)


def assert_oversight_surface(user: Any) -> None:
    if may_hold_oversight(user):
        return
    raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=NOT_AN_OFFICER_DETAIL)


def assert_may_assign_oversight(user: Any) -> None:
    if can_assign_workshop_oversight(user):
        return
    raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=OVERSIGHT_ASSIGNER_REFUSAL)



async def has_oversight_scope(workshop_id: str, user_id: str) -> bool:
    if not workshop_id or not user_id:
        return False
    row = await db.designworkshopoversight.find_first(
        where={"designWorkshopId": workshop_id, "userId": user_id}
    )
    return row is not None


def oversight_by_clause(user_id: str) -> dict[str, Any]:
    return {"oversight": {"some": {"userId": user_id}}}


async def load_overseen_workshop_or_404(workshop_id: str, user: Any) -> Any:
    if not may_hold_oversight(user):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    if record is None or record.deletedAt is not None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if not await has_oversight_scope(workshop_id, getattr(user, "id", "")):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record



def _role(user: Any) -> str:
    return role_value(user) if user is not None else ""


def oversight_payload(row: Any) -> dict[str, Any]:
    user = getattr(row, "user", None)
    assigned_at = getattr(row, "assignedAt", None)
    return {
        "capacity": str(getattr(row.capacity, "value", row.capacity)),
        "userId": row.userId,
        "name": getattr(user, "name", "") or "",
        "email": getattr(user, "email", "") or "",
        "role": _role(user),
        "assignedAt": assigned_at.isoformat() if assigned_at else None,
        "assignedById": getattr(row, "assignedById", None),
    }


async def oversight_rows(workshop_id: str) -> list[dict[str, Any]]:
    rows = await db.designworkshopoversight.find_many(
        where={"designWorkshopId": workshop_id}, include={"user": True}
    )
    by_capacity = {str(getattr(r.capacity, "value", r.capacity)): r for r in rows}
    return [oversight_payload(by_capacity[c]) for c in CAPACITIES if c in by_capacity]


async def officer_directory(
    search: str | None = None, *, exclude_user_id: str | None = None
) -> dict[str, Any]:
    barred = await access_roster.barred_emails()

    clauses: list[dict[str, Any]] = [{"role": {"in": sorted(OVERSIGHT_HOLDER_ROLES)}}]
    if exclude_user_id:
        clauses.append({"id": {"not": exclude_user_id}})
    if barred:
        configured = (get_settings().master_admin_email or "").strip().lower()
        exemptions: list[dict[str, Any]] = [{"role": "MASTER_ADMIN"}]
        if configured:
            exemptions.append({"email": {"equals": configured, "mode": "insensitive"}})
        clauses.append({"OR": [*exemptions, {"email": {"not_in": barred, "mode": "insensitive"}}]})

    term = (search or "").strip()
    if term:
        clauses.append({"OR": [{"name": contains(term)}, {"email": contains(term)}]})

    users = await db.user.find_many(
        where={"AND": clauses},
        order=[{"name": "asc"}, {"id": "asc"}],
        take=ELIGIBLE_OFFICER_LIMIT + 1,
    )
    truncated = len(users) > ELIGIBLE_OFFICER_LIMIT
    users = users[:ELIGIBLE_OFFICER_LIMIT]
    if truncated:
        logger.warning(
            "officer-directory hit its ceiling of %s accounts (search=%r); the answer is truncated "
            "and says so, and the caller can narrow it",
            ELIGIBLE_OFFICER_LIMIT,
            term,
        )
    return {
        "users": [
            {
                "id": u.id,
                "name": u.name,
                "email": u.email,
                "role": _role(u),
                "capacities": capacities_for(_role(u)),
            }
            for u in users
        ],
        "truncated": truncated,
    }


def capacities_for(role: str) -> list[str]:
    return [c for c, roles in OVERSIGHT_CAPACITY_ROLES.items() if role in roles]



async def assert_may_hold(
    workshop_id: str, wanted: dict[str, str | None], *, appointing: str | None = None
) -> None:
    ids = {uid for uid in wanted.values() if uid}
    if not ids:
        return

    users = await db.user.find_many(where={"id": {"in": sorted(ids)}})
    by_id = {u.id: u for u in users}

    unknown = sorted(_displayable(uid) for uid in ids if uid not in by_id)
    if unknown:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "No account exists with "
                + ("these ids: " if len(unknown) > 1 else "this id: ")
                + ", ".join(unknown)
                + ". Nothing was changed."
            ),
        )

    present, barred, authored, held = await gather_reads(
        db.designworkshopoversight.find_many(where={"designWorkshopId": workshop_id}),
        access_roster.barred_among([u.email for u in users]),
        posts.authorship_among(workshop_id, by_id),
        posts.supervisory_posts_among(workshop_id, by_id),
    )
    current = {str(getattr(row.capacity, "value", row.capacity)): row.userId for row in present}
    after = {
        capacity: holder
        for capacity, holder in {**current, **wanted}.items()
        if holder and capacity in OVERSIGHT_CAPACITY_ROLES
    }

    refusals: list[str] = []
    separation: list[str] = []
    for capacity in CAPACITIES:
        uid = wanted.get(capacity)
        if not uid:
            continue
        user = by_id[uid]
        role = _role(user)
        if role not in OVERSIGHT_CAPACITY_ROLES[capacity]:
            refusals.append(
                f"{user.name} ({user.email}) is a {role}, and the {_label(capacity)} of a design & "
                f"prototype workshop must be {_article(capacity)} {_label(capacity)}, a Ministry "
                f"Admin, an admin or the master admin."
            )
            continue
        if not is_break_glass_master(user) and _normalised(user.email) in barred:
            refusals.append(
                f"{user.name} ({user.email}) is barred by the platform access list, so they cannot "
                f"sign in at all. Restore their access on the access screen first."
            )
        standing = posts.Standing(
            posts=frozenset({c for c, holder in after.items() if holder == uid})
            | (held.get(uid, frozenset()) & {posts.INSPECTOR}),
            authored=authored.get(uid, frozenset()),
        )
        separation.extend(
            posts.separation_refusals(
                person=f"{user.name} ({user.email})",
                post=capacity,
                standing=standing,
                self_appointed=uid == appointing and current.get(capacity) != uid,
            )
        )

    posts.raise_refusals(refusals, separation)


_CAPACITY_LABELS = {
    "ASSISTANT_DIRECTOR": "Assistant Director",
    "REGIONAL_DIRECTOR": "Regional Director",
}


def _label(capacity: str) -> str:
    return _CAPACITY_LABELS.get(capacity, capacity)


def _article(capacity: str) -> str:
    return "an" if _label(capacity)[:1].upper() in "AEIOU" else "a"


_UNSTORABLE_IN_AN_ID = __import__("re").compile(r"[\x00-\x1f\x7f\ud800-\udfff]")


def _displayable(user_id: str) -> str:
    return _UNSTORABLE_IN_AN_ID.sub("", user_id)


def _normalised(email: Any) -> str:
    return str(email or "").strip().lower()


async def apply_oversight(
    workshop_id: str,
    *,
    wanted: dict[str, str | None],
    assigned_by_id: str,
) -> list[dict[str, Any]]:
    existing = await db.designworkshopoversight.find_many(where={"designWorkshopId": workshop_id})
    held = {str(getattr(r.capacity, "value", r.capacity)): r for r in existing}
    released = sorted(
        capacity
        for capacity, user_id in wanted.items()
        if held.get(capacity) is not None
        and held[capacity].userId == assigned_by_id
        and user_id != assigned_by_id
    )
    if released:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail=posts.self_release_refusal(released)
        )

    async with db.tx() as tx:
        for capacity, user_id in wanted.items():
            current = held.get(capacity)
            key = {
                "designWorkshopId_capacity": {
                    "designWorkshopId": workshop_id,
                    "capacity": capacity,
                }
            }
            if not user_id:
                if current is not None:
                    await tx.designworkshopoversight.delete_many(
                        where={"designWorkshopId": workshop_id, "capacity": capacity}
                    )
                continue
            if current is not None and current.userId == user_id:
                continue
            await tx.designworkshopoversight.upsert(
                where=key,
                data={
                    "create": {
                        "designWorkshopId": workshop_id,
                        "capacity": capacity,
                        "userId": user_id,
                        "assignedById": assigned_by_id,
                    },
                    "update": {
                        "userId": user_id,
                        "assignedById": assigned_by_id,
                        "assignedAt": _now(),
                    },
                },
            )
    return await oversight_rows(workshop_id)


def _now() -> Any:
    from datetime import UTC, datetime

    return datetime.now(UTC)



_CLOSED_STATUSES = frozenset(
    design_workshop_review_loop.UNDER_REVIEW
    | {
        design_workshop_review_loop.APPROVED,
        design_workshop_review_loop.SUBMITTED,
        design_workshop_review_loop.ARCHIVED,
    }
)


def _prefill_targets() -> dict[str, tuple[str, str]]:
    from app.services.stage_schema import stages

    out: dict[str, tuple[str, str]] = {}
    for spec in stages():
        for entity in spec.entities:
            for field in entity.fields:
                out.setdefault(field.key, (spec.key, entity.key))
    return out


async def reassign_designer(workshop: Any, user_id: str, *, actor: Any) -> dict[str, Any]:
    assert_the_report_is_not_filed(workshop)

    await design_workshops.assert_every_designer_may_be_named(
        {user_id}, workshop_id=workshop.id, appointing=getattr(actor, "id", None)
    )

    creator_id = str(getattr(workshop, "createdById", "") or "")
    outgoing = await _the_designer_being_replaced(workshop, incoming_id=user_id)

    await design_workshops.attach_the_named_designer(
        workshop.id,
        user_id,
        granted_by_id=getattr(actor, "id", ""),
        creator_id=creator_id,
    )
    if outgoing is not None:
        await design_workshop_viewers.remove_one_viewer(workshop.id, outgoing["userId"])

    stages_written = await move_the_leads_profile_onto_the_workshop(
        workshop.id, user_id, actor=actor
    )

    record = await db.designworkshop.find_unique(where={"id": workshop.id})
    surviving = [
        row
        for row in await design_workshop_viewers.viewer_rows(workshop.id)
        if row["userId"] != user_id
    ]
    return {
        "designerId": user_id,
        "designerName": getattr(record, "designerName", None),
        "stagesWritten": stages_written,
        "removedDesigner": outgoing,
        "stillHaveAccess": surviving,
    }


async def set_named_designers(
    workshop: Any, *, user_ids: list[str], lead_user_id: str | None, actor: Any
) -> dict[str, Any]:
    assert_the_report_is_not_filed(workshop)

    workshop_id = getattr(workshop, "id", "")
    creator_id = str(getattr(workshop, "createdById", "") or "")

    wanted: list[str] = []
    seen: set[str] = set()
    for raw in user_ids:
        candidate = (raw or "").strip()
        if not candidate or candidate == creator_id or candidate in seen:
            continue
        seen.add(candidate)
        wanted.append(candidate)

    lead = (lead_user_id or "").strip() or None
    if lead and lead not in seen:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "The designer named to lead this workshop is not among the designers it is for. "
                "A lead who is not on the workshop would have their name on its report and no way "
                "to open it. Tick them in the list as well, or choose a lead from the list. "
                "Nothing was changed."
            ),
        )

    held_rows = await design_workshop_viewers.viewer_rows(workshop_id)
    held = {row["userId"] for row in held_rows}
    current_lead = await _the_lead_among(workshop, held_rows)
    current_lead_id = current_lead["userId"] if current_lead else None

    added = [uid for uid in wanted if uid not in held]
    removed_rows = [row for row in held_rows if row["userId"] not in seen]

    designer_named = bool(str(getattr(workshop, "designerName", "") or "").strip())
    if current_lead_id is not None and current_lead_id not in seen and not lead and wanted:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"{current_lead.get('name') or current_lead.get('email') or 'That designer'} is the "
                f"designer this workshop's report names, so taking them off while other designers "
                f"remain means saying which of the others leads it instead. Choose the designer "
                f"whose name the report should carry and save again — or take every designer off "
                f"the workshop, which clears the name from its cover as well. Nothing was changed."
            ),
        )

    if added:
        await design_workshops.assert_every_designer_may_be_named(
            set(added), workshop_id=workshop_id, appointing=getattr(actor, "id", None)
        )

    for user_id in added:
        await design_workshops.attach_the_named_designer(
            workshop_id,
            user_id,
            granted_by_id=getattr(actor, "id", ""),
            creator_id=creator_id,
        )
    for row in removed_rows:
        await design_workshop_viewers.remove_one_viewer(workshop_id, row["userId"])

    stages_written: list[str] = []
    if designer_named and not wanted:
        stages_written = await take_the_designers_name_off_the_workshop(workshop_id, actor=actor)
    elif lead and lead != current_lead_id:
        stages_written = await move_the_leads_profile_onto_the_workshop(
            workshop_id, lead, actor=actor
        )
    elif not designer_named and not current_lead_id and wanted:
        stages_written = await move_the_leads_profile_onto_the_workshop(
            workshop_id, wanted[0], actor=actor
        )

    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    return {
        "designers": await named_designer_rows(record),
        "designerName": getattr(record, "designerName", None),
        "stagesWritten": stages_written,
        "removedDesigners": [
            {
                "userId": row["userId"],
                "name": row.get("name") or "",
                "email": row.get("email") or "",
                "role": row.get("role") or "",
            }
            for row in removed_rows
        ],
    }


def assert_the_report_is_not_filed(workshop: Any) -> None:
    workshop_status = str(getattr(workshop, "status", "DRAFT") or "DRAFT")
    if workshop_status not in _CLOSED_STATUSES:
        return
    spoken = design_workshop_review_loop._LABELS.get(workshop_status, workshop_status)
    if workshop_status == design_workshop_review_loop.APPROVED:
        remedy = (
            "It has been approved, so ask the Ministry Admin to withdraw the approval first if "
            "the designer on it is genuinely wrong."
        )
    elif (
        workshop_status == design_workshop_review_loop.SUBMITTED
        and getattr(workshop, "handedOnAt", None) is not None
    ):
        remedy = (
            "It has been handed on to the office, so ask the Ministry Admin to return it first if "
            "the designer on it is genuinely wrong."
        )
    elif workshop_status in design_workshop_review_loop.UNDER_REVIEW:
        remedy = "Withdraw it from inspection first if the designer on it is genuinely wrong."
    else:
        remedy = "Reopen it first if the designer on it is genuinely wrong."
    raise HTTPException(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        detail=(
            f"This workshop is {spoken}, and its report already names a designer. Changing it "
            f"now would re-attribute a filed document. {remedy}"
        ),
    )


async def move_the_leads_profile_onto_the_workshop(
    workshop_id: str, user_id: str, *, actor: Any
) -> list[str]:
    from app.schemas.design_workshops import StageEntryIn, StageSaveIn
    from app.services.designers import PREFILL_MAP, get_or_create_profile, prefill_from_profile
    from app.services.stage_schema import stages

    await get_or_create_profile(user_id)
    values = await prefill_from_profile(user_id)
    targets = _prefill_targets()
    by_entity: dict[tuple[str, str], dict[str, Any]] = {}
    for _column, field_key in PREFILL_MAP:
        if field_key not in values:
            continue
        target = targets.get(field_key)
        if target is None:
            logger.warning("designer prefill target %r is not in the stage registry", field_key)
            continue
        by_entity.setdefault(target, {})[field_key] = values[field_key]

    by_key = {s.key: s for s in stages()}
    for (stage_key, entity_key), data in sorted(by_entity.items()):
        spec = by_key.get(stage_key)
        if spec is None:  # pragma: no cover - the registry disagreeing with itself
            continue
        await design_workshops.save_stage(
            workshop_id,
            spec,
            StageSaveIn(
                entries=[StageEntryIn(entityKey=entity_key, data=data, merge=True)],
                replaceCollections=False,
                emptiedEntities=[],
                submit=False,
            ),
            actor,
        )
    return sorted({stage for stage, _entity in by_entity})


async def take_the_designers_name_off_the_workshop(workshop_id: str, *, actor: Any) -> list[str]:
    from app.schemas.design_workshops import StageEntryIn, StageSaveIn
    from app.services.stage_schema import stages

    target = _prefill_targets().get("designerName")
    spec = {s.key: s for s in stages()}.get(target[0]) if target else None
    if target is None or spec is None:  # pragma: no cover - the registry disagreeing with itself
        logger.warning(
            "no stage declares designerName; workshop %s keeps the name on its cover", workshop_id
        )
        return []
    stage_key, entity_key = target

    rows = [
        row
        for row in await design_workshops.entry_rows(workshop_id, stage_key=stage_key)
        if row.entityKey == entity_key
    ]
    sentinel = design_workshops.singleton_client_key(stage_key)
    row = next((r for r in rows if r.clientKey == sentinel), None) or next(iter(rows), None)
    held: dict[str, Any] = dict(getattr(row, "data", None) or {})

    if not str(held.get("designerName") or "").strip():
        await db.designworkshop.update(where={"id": workshop_id}, data={"designerName": None})
        return []

    kept = {key: value for key, value in held.items() if key != "designerName"}
    await design_workshops.save_stage(
        workshop_id,
        spec,
        StageSaveIn(
            entries=[StageEntryIn(entityKey=entity_key, data=kept, merge=False)],
            replaceCollections=False,
            emptiedEntities=[],
            submit=False,
        ),
        actor,
    )
    return [stage_key]


async def _the_designer_being_replaced(workshop: Any, *, incoming_id: str) -> dict[str, Any] | None:
    rows = await design_workshop_viewers.viewer_rows(workshop.id)
    return await _the_lead_among(workshop, rows, exclude={incoming_id})


async def _the_lead_among(
    workshop: Any, rows: list[dict[str, Any]], *, exclude: set[str] | None = None
) -> dict[str, Any] | None:
    from app.services.designers import prefill_from_profile

    wanted = str(getattr(workshop, "designerName", "") or "").strip().casefold()
    if not wanted:
        return None
    creator_id = str(getattr(workshop, "createdById", "") or "")
    skip = {creator_id} | (exclude or set())

    matches: list[dict[str, Any]] = []
    for row in rows:
        if row["userId"] in skip:
            continue
        values = await prefill_from_profile(row["userId"])
        theirs = str(values.get("designerName") or row.get("name") or "").strip().casefold()
        if theirs and theirs == wanted:
            matches.append(row)
    return matches[0] if len(matches) == 1 else None


async def designer_prefill_for_workshop(workshop: Any) -> tuple[str | None, dict[str, Any]]:
    from app.services.designers import prefill_from_profile

    rows = await design_workshop_viewers.viewer_rows(getattr(workshop, "id", ""))
    lead = await _the_lead_among(workshop, rows)
    creator_id = str(getattr(workshop, "createdById", "") or "") or None
    if lead is not None:
        user_id: str | None = lead["userId"]
    else:
        others = [row["userId"] for row in rows if row["userId"] != creator_id]
        user_id = others[0] if len(others) == 1 else creator_id
    if not user_id:
        return None, {}
    return user_id, await prefill_from_profile(user_id) or {}


async def named_designer_rows(workshop: Any) -> list[dict[str, Any]]:
    rows = await design_workshop_viewers.viewer_rows(getattr(workshop, "id", ""))
    lead = await _the_lead_among(workshop, rows)
    lead_id = lead["userId"] if lead else None
    return [
        {
            "userId": row["userId"],
            "name": row.get("name") or "",
            "email": row.get("email") or "",
            "role": row.get("role") or "",
            "isLead": row["userId"] == lead_id,
        }
        for row in rows
    ]


ROSTER_TAKE = 200


async def linked_artisan_rows(workshop_id: str, offset: int = 0) -> dict[str, Any]:
    offset = max(0, int(offset or 0))
    rows = await db.artisan.find_many(
        where={"designWorkshopId": workshop_id},
        include={"craft": True},
        order=[{"createdAt": "desc"}, {"id": "asc"}],
        skip=offset,
        take=ROSTER_TAKE + 1,
    )
    truncated = len(rows) > ROSTER_TAKE
    rows = rows[:ROSTER_TAKE]
    total = await db.artisan.count(where={"designWorkshopId": workshop_id})
    return {
        "total": total,
        "offset": offset,
        "nextOffset": offset + len(rows) if truncated else None,
        "artisans": [
            {
                "id": row.id,
                "name": row.name,
                "place": row.place,
                "craftName": getattr(getattr(row, "craft", None), "name", None),
                "status": str(getattr(row.status, "value", row.status)),
                "createdAt": row.createdAt.isoformat() if row.createdAt else None,
            }
            for row in rows
        ],
        "truncated": truncated,
    }


async def unlink_artisan_from_workshop(workshop_id: str, artisan_id: str) -> bool:
    changed = await db.artisan.update_many(
        where={"id": artisan_id, "designWorkshopId": workshop_id},
        data={"designWorkshopId": None},
    )
    return bool(changed)


try:  # pragma: no cover - an environment check, not a runtime state
    from prisma.enums import DwOversightCapacity as _Capacity

    _DECLARED = {member.value for member in _Capacity}
except Exception:  # noqa: BLE001 - see above
    _DECLARED = set(OVERSIGHT_CAPACITY_ROLES)
if set(OVERSIGHT_CAPACITY_ROLES) != _DECLARED:  # pragma: no cover
    raise RuntimeError(
        "OVERSIGHT_CAPACITY_ROLES and the DwOversightCapacity enum must name the same capacities; "
        f"the enum has {sorted(_DECLARED)} and this module has "
        f"{sorted(OVERSIGHT_CAPACITY_ROLES)}. A third capacity is an ALTER TYPE in its own "
        "migration file PLUS an entry here — see the header of this module."
    )
