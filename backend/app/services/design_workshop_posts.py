from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from typing import Any

from fastapi import HTTPException, status

from app.core.db import db
from app.core.deps import role_value
from app.services.concurrency import gather_reads

SERVING_ADMIN_ROLES = frozenset({"MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"})

ASSISTANT_DIRECTOR = "ASSISTANT_DIRECTOR"
REGIONAL_DIRECTOR = "REGIONAL_DIRECTOR"
INSPECTOR = "INSPECTOR"
DESIGNER = "DESIGNER"

OVERSIGHT_POSTS = frozenset({ASSISTANT_DIRECTOR, REGIONAL_DIRECTOR})

SUPERVISORY_POSTS = OVERSIGHT_POSTS | {INSPECTOR}

POST_LABELS = {
    ASSISTANT_DIRECTOR: "Assistant Director",
    REGIONAL_DIRECTOR: "Regional Director",
    INSPECTOR: "inspector",
    DESIGNER: "designer",
}

DESIGNER_ACCESS = "DESIGNER_ACCESS"
STAGE_WRITES = "STAGE_WRITES"

_SPOKEN_ORDER = (ASSISTANT_DIRECTOR, REGIONAL_DIRECTOR, INSPECTOR, DESIGNER)


@dataclass(frozen=True)
class Standing:
    posts: frozenset[str] = frozenset()
    authored: frozenset[str] = frozenset()


def _labels(posts: Iterable[str]) -> str:
    held = set(posts)
    named = [POST_LABELS[post] for post in _SPOKEN_ORDER if post in held]
    if len(named) <= 1:
        return "".join(named)
    return ", ".join(named[:-1]) + " and " + named[-1]


def _as(post: str) -> str:
    return f"its {POST_LABELS[post]}"


def _evidence(authored: frozenset[str]) -> str:
    if authored >= {DESIGNER_ACCESS, STAGE_WRITES}:
        return "holds designer access to this workshop and has written its stages"
    if DESIGNER_ACCESS in authored:
        return "holds designer access to this workshop"
    return "has written this workshop's stages"


def separation_refusals(
    *, person: str, post: str, standing: Standing, self_appointed: bool
) -> list[str]:
    refusals: list[str] = []
    if self_appointed:
        refusals.append(
            f"{person} is the account making this appointment, and nobody appoints themselves to a "
            f"workshop: another administrator has to name them {_as(post)}."
        )
    others = standing.posts - {post}
    if post in SUPERVISORY_POSTS:
        if standing.authored:
            refusals.append(
                f"{person} {_evidence(standing.authored)}, so they cannot be {_as(post)}: nobody "
                f"inspects or supervises work they authored. Creating a workshop is not authoring "
                f"it; holding designer access to it or writing its stages is."
            )
        if post in OVERSIGHT_POSTS and others & OVERSIGHT_POSTS:
            refusals.append(
                f"{person} would be both the Assistant Director and the Regional Director of this "
                f"workshop. One person cannot hold both posts on one workshop."
            )
        if post == INSPECTOR and others & OVERSIGHT_POSTS:
            refusals.append(
                f"{person} is this workshop's {_labels(others & OVERSIGHT_POSTS)}, so they cannot "
                f"also inspect it. One person cannot both supervise a workshop and inspect it."
            )
        if post in OVERSIGHT_POSTS and INSPECTOR in others:
            refusals.append(
                f"{person} inspects this workshop, so they cannot also be {_as(post)}. One person "
                f"cannot both supervise a workshop and inspect it."
            )
    elif post == DESIGNER and standing.posts & SUPERVISORY_POSTS:
        refusals.append(
            f"{person} is this workshop's {_labels(standing.posts & SUPERVISORY_POSTS)}, so they "
            f"cannot also be given designer access to it: whoever inspects or supervises a workshop "
            f"does not write it. Take them off that post first if they are to work on it instead."
        )
    return refusals


def write_refusal(posts: Iterable[str]) -> str:
    return (
        f"You are this workshop's {_labels(posts)}, so you can read it but not change it: whoever "
        f"inspects or supervises a workshop does not write it. Ask whoever made the appointment to "
        f"take you off that post if you need to work on it."
    )


def self_release_refusal(posts: Iterable[str]) -> str:
    return (
        f"You are this workshop's {_labels(posts)}, and nobody takes themselves off a post: another "
        f"administrator has to take you off. Nothing was changed."
    )


def _worked_on(authored: frozenset[str]) -> str:
    if authored >= {DESIGNER_ACCESS, STAGE_WRITES}:
        return "you hold designer access to it and have written its stages"
    if DESIGNER_ACCESS in authored:
        return "you hold designer access to it"
    return "you have written its stages"


def approval_refusals(*, posts: Iterable[str], authored: Iterable[str]) -> list[str]:
    held = frozenset(posts)
    evidence = frozenset(authored)
    refusals: list[str] = []
    if evidence:
        refusals.append(
            f"You worked on this workshop — {_worked_on(evidence)} — so you cannot decide on its "
            f"report: nobody approves their own work."
        )
    if INSPECTOR in held:
        refusals.append(
            "You inspect this workshop, so you cannot decide on its report: the officer who inspects "
            "a report is not the one who signs it off."
        )
    return refusals


async def decision_refusal(workshop_id: str, user: Any) -> str | None:
    user_id = str(getattr(user, "id", "") or "")
    if not workshop_id or not user_id:
        return None
    held, authored = await gather_reads(
        supervisory_posts_among(workshop_id, [user_id]),
        authorship_among(workshop_id, [user_id]),
    )
    refusals = approval_refusals(
        posts=held.get(user_id, frozenset()), authored=authored.get(user_id, frozenset())
    )
    return " ".join(refusals) if refusals else None


def raise_refusals(account_refusals: list[str], separation: list[str]) -> None:
    unique = list(dict.fromkeys(separation))
    if account_refusals:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=" ".join([*account_refusals, *unique, "Nothing was changed."]),
        )
    if unique:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=" ".join([*unique, "Nothing was changed."]),
        )



def could_hold_a_supervisory_post(user: Any) -> bool:
    from app.services.design_workshop_inspectors import INSPECTION_HOLDER_ROLES
    from app.services.design_workshop_oversight import OVERSIGHT_HOLDER_ROLES

    return role_value(user) in (INSPECTION_HOLDER_ROLES | OVERSIGHT_HOLDER_ROLES)


async def refuse_a_holders_write(workshop_id: str, user: Any) -> None:
    if not could_hold_a_supervisory_post(user):
        return
    user_id = str(getattr(user, "id", "") or "")
    held = (await supervisory_posts_among(workshop_id, [user_id])).get(user_id)
    if held:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=write_refusal(held))


async def supervisory_posts_among(
    workshop_id: str, user_ids: Iterable[str]
) -> dict[str, frozenset[str]]:
    from app.services.design_workshop_inspectors import inspection_holders_among

    ids = sorted({uid for uid in user_ids if uid})
    if not workshop_id or not ids:
        return {}
    rows, inspecting = await gather_reads(
        db.designworkshopoversight.find_many(
            where={"designWorkshopId": workshop_id, "userId": {"in": ids}}
        ),
        inspection_holders_among(workshop_id, ids),
    )
    held: dict[str, set[str]] = {}
    for row in rows:
        held.setdefault(row.userId, set()).add(str(getattr(row.capacity, "value", row.capacity)))
    for uid in inspecting:
        held.setdefault(uid, set()).add(INSPECTOR)
    return {uid: frozenset(posts) for uid, posts in held.items()}


async def authorship_among(
    workshop_id: str, user_ids: Iterable[str]
) -> dict[str, frozenset[str]]:
    from app.services.design_workshop_viewers import viewer_ids_among
    from app.services.design_workshops import stage_writers

    ids = sorted({uid for uid in user_ids if uid})
    if not workshop_id or not ids:
        return {}
    viewers, writers = await gather_reads(
        viewer_ids_among(workshop_id, ids), stage_writers([workshop_id], ids)
    )
    evidence: dict[str, set[str]] = {}
    for uid in viewers:
        evidence.setdefault(uid, set()).add(DESIGNER_ACCESS)
    for _workshop, uid in writers:
        evidence.setdefault(uid, set()).add(STAGE_WRITES)
    return {uid: frozenset(kinds) for uid, kinds in evidence.items()}


_FILED_RECORD_TABLES: dict[str, str] = {
    "artisan": "artisan",
    "product": "productdocumentation",
    "tool": "tooldocumentation",
    "questionnaire": "questionnaireinterview",
    "process": "process",
    "media": "mediafile",
}

_LINK_SPELLINGS: dict[str, str] = {"questionnaireinterview": "questionnaire", "misc": "media"}

_TYPED_PARENTS: dict[str, str] = {
    "artisanId": "artisan",
    "productId": "product",
    "toolId": "tool",
    "questionnaireInterviewId": "questionnaire",
}


def _media_bearing_fields() -> tuple[list[str], list[str]]:
    from app.services.stage_schema import stages

    entity_keys: set[str] = set()
    field_keys: set[str] = set()
    for spec in stages():
        for entity in spec.entities:
            keys = {f.key for f in entity.fields if f.type.is_media or f.is_rich_text}
            if keys:
                entity_keys.add(entity.key)
                field_keys |= keys
    return sorted(entity_keys), sorted(field_keys)


async def stage_media_workshop_ids(media_id: str) -> set[str]:
    media_id = str(media_id or "").strip()
    if not media_id:
        return set()
    entity_keys, field_keys = _media_bearing_fields()
    if not entity_keys:
        return set()
    rows = await db.query_raw(
        'SELECT DISTINCT e."designWorkshopId" AS id FROM "DwStageEntry" e '
        'WHERE e."deletedAt" IS NULL AND e."entityKey" = ANY($2::text[]) AND EXISTS ('
        '  SELECT 1 FROM jsonb_each(e."data") kv WHERE kv.key = ANY($3::text[])'
        "  AND jsonb_path_exists(kv.value, '$.** ? (@ == $id)', jsonb_build_object('id', $1::text)))",
        media_id,
        entity_keys,
        field_keys,
    )
    return {str(row["id"]) for row in rows if row.get("id")}


async def _ai_layer_workshop_ids(media_id: str) -> set[str]:
    if not media_id:
        return set()
    rows = await db.dwailayer.find_many(where={"sourceMediaId": media_id, "deletedAt": None})
    return {str(row.designWorkshopId) for row in rows if getattr(row, "designWorkshopId", None)}


async def link_filing(link_type: str | None, record_id: str | None) -> set[str]:
    kind = str(link_type or "").strip().lower()
    kind = _LINK_SPELLINGS.get(kind, kind)
    record_id = str(record_id or "").strip()
    if kind == "processstep" and record_id:
        step = await db.processstep.find_unique(where={"id": record_id})
        kind, record_id = "process", str(getattr(step, "processId", None) or "")
    table = _FILED_RECORD_TABLES.get(kind)
    if table is None or not record_id:
        return set()
    row = await getattr(db, table).find_unique(where={"id": record_id})
    if row is None:
        return set()
    found = {str(getattr(row, "designWorkshopId", None) or "")}
    if table == "mediafile":
        from app.services.dictation_consent import tagged_workshop_id

        found.add(tagged_workshop_id(row) or "")
    return {workshop_id for workshop_id in found if workshop_id}


async def media_design_workshop_ids(media: Any) -> set[str]:
    from app.services.dictation_consent import tagged_workshop_id

    media_id = str(getattr(media, "id", "") or "")
    found = {str(getattr(media, "designWorkshopId", None) or ""), tagged_workshop_id(media) or ""}
    links: set[tuple[str, str]] = set()
    for column, kind in _TYPED_PARENTS.items():
        parent = str(getattr(media, column, None) or "").strip()
        if parent:
            links.add((kind, parent))
    tag = str(getattr(media, "linkedRecordType", None) or "").strip().lower()
    tagged = str(getattr(media, "linkedRecordId", None) or "").strip()
    if tag and tagged:
        links.add((_LINK_SPELLINGS.get(tag, tag), tagged))
    answers = await gather_reads(
        stage_media_workshop_ids(media_id),
        _ai_layer_workshop_ids(media_id),
        *(link_filing(kind, parent) for kind, parent in sorted(links)),
    )
    for workshop_ids in answers:
        found |= workshop_ids
    return {workshop_id for workshop_id in found if workshop_id}


async def refuse_a_holders_media_write(
    media: Any, user: Any, *, relinked_to: tuple[str, str] | None = None
) -> None:
    if not could_hold_a_supervisory_post(user):
        return
    reads = [media_design_workshop_ids(media)]
    if relinked_to is not None:
        reads.append(link_filing(*relinked_to))
    workshop_ids: set[str] = set().union(*await gather_reads(*reads))
    for workshop_id in sorted(workshop_ids):
        await refuse_a_holders_write(workshop_id, user)



async def supervisory_workshops_of(user: Any) -> set[str]:
    if not could_hold_a_supervisory_post(user):
        return set()
    from app.services.design_workshop_inspectors import inspected_workshop_ids

    user_id = str(getattr(user, "id", "") or "")
    if not user_id:
        return set()
    overseen, inspected = await gather_reads(
        db.designworkshopoversight.find_many(where={"userId": user_id}),
        inspected_workshop_ids(user_id),
    )
    return {str(row.designWorkshopId) for row in overseen if row.designWorkshopId} | set(inspected)


async def staffing_held_by(user_id: str) -> dict[str, dict[str, list[dict[str, str]]]]:
    from app.services.design_workshop_inspectors import inspected_live_workshop_ids

    if not user_id:
        return {}
    overseen, inspected = await gather_reads(
        db.designworkshopoversight.find_many(
            where={"userId": user_id, "designWorkshop": {"is": {"deletedAt": None}}}
        ),
        inspected_live_workshop_ids(user_id),
    )
    held: dict[str, dict[str, list[dict[str, str]]]] = {}
    for row in overseen:
        entry = held.setdefault(str(row.designWorkshopId), {"oversight": [], "inspectors": []})
        entry["oversight"].append(
            {"capacity": str(getattr(row.capacity, "value", row.capacity)), "userId": user_id}
        )
    for workshop_id in inspected:
        entry = held.setdefault(str(workshop_id), {"oversight": [], "inspectors": []})
        entry["inspectors"].append({"userId": user_id})
    return held


_STAGED_AMONG_SQL = (
    "SELECT DISTINCT m.id AS id FROM unnest($1::text[]) AS m(id) WHERE EXISTS ("
    '  SELECT 1 FROM "DwStageEntry" e'
    '  WHERE e."designWorkshopId" = ANY($2::text[]) AND e."deletedAt" IS NULL'
    '  AND e."entityKey" = ANY($3::text[]) AND EXISTS ('
    '    SELECT 1 FROM jsonb_each(e."data") kv WHERE kv.key = ANY($4::text[])'
    "    AND jsonb_path_exists(kv.value, '$.** ? (@ == $id)', jsonb_build_object('id', m.id))))"
)


async def _staged_among(media_ids: list[str], workshop_ids: list[str]) -> set[str]:
    if not media_ids or not workshop_ids:
        return set()
    entity_keys, field_keys = _media_bearing_fields()
    if not entity_keys:
        return set()
    rows = await db.query_raw(_STAGED_AMONG_SQL, media_ids, workshop_ids, entity_keys, field_keys)
    return {str(row["id"]) for row in rows if row.get("id")}


async def _links_filed_under(
    links: set[tuple[str, str]], workshop_ids: set[str]
) -> set[tuple[str, str]]:
    from app.services.dictation_consent import tagged_workshop_id

    by_kind: dict[str, set[str]] = {}
    for kind, record_id in links:
        by_kind.setdefault(kind, set()).add(record_id)
    steps_of: dict[str, set[str]] = {}
    step_ids = by_kind.pop("processstep", set())
    if step_ids:
        for step in await db.processstep.find_many(where={"id": {"in": sorted(step_ids)}}):
            process_id = str(getattr(step, "processId", None) or "")
            if process_id:
                steps_of.setdefault(process_id, set()).add(str(step.id))
                by_kind.setdefault("process", set()).add(process_id)
    asked = [(kind, ids) for kind, ids in sorted(by_kind.items()) if kind in _FILED_RECORD_TABLES]
    answers = await gather_reads(
        *(
            getattr(db, _FILED_RECORD_TABLES[kind]).find_many(where={"id": {"in": sorted(ids)}})
            for kind, ids in asked
        )
    )
    filed: set[tuple[str, str]] = set()
    for (kind, _ids), rows in zip(asked, answers, strict=True):
        for row in rows:
            named = {str(getattr(row, "designWorkshopId", None) or "")}
            if kind == "media":
                named.add(tagged_workshop_id(row) or "")
            if named & workshop_ids:
                filed.add((kind, str(row.id)))
                filed.update(("processstep", step) for step in steps_of.get(str(row.id), ()))
    return filed


async def media_held_among(media_rows: Iterable[Any], workshop_ids: Iterable[str]) -> set[str]:
    from app.services.dictation_consent import tagged_workshop_id

    held = {str(workshop_id) for workshop_id in workshop_ids if workshop_id}
    rows = [row for row in media_rows if str(getattr(row, "id", "") or "")]
    if not held or not rows:
        return set()
    found: set[str] = set()
    owners: dict[tuple[str, str], set[str]] = {}
    for row in rows:
        media_id = str(row.id)
        named = {str(getattr(row, "designWorkshopId", None) or ""), tagged_workshop_id(row) or ""}
        if named & held:
            found.add(media_id)
            continue
        for column, kind in _TYPED_PARENTS.items():
            parent = str(getattr(row, column, None) or "").strip()
            if parent:
                owners.setdefault((kind, parent), set()).add(media_id)
        tag = str(getattr(row, "linkedRecordType", None) or "").strip().lower()
        tagged = str(getattr(row, "linkedRecordId", None) or "").strip()
        if tag and tagged:
            owners.setdefault((_LINK_SPELLINGS.get(tag, tag), tagged), set()).add(media_id)
    pending = sorted(str(row.id) for row in rows if str(row.id) not in found)
    if not pending:
        return found
    workshops = sorted(held)
    staged, layers, filed = await gather_reads(
        _staged_among(pending, workshops),
        db.dwailayer.find_many(
            where={
                "sourceMediaId": {"in": pending},
                "deletedAt": None,
                "designWorkshopId": {"in": workshops},
            }
        ),
        _links_filed_under(set(owners), held),
    )
    found |= staged
    found |= {str(layer.sourceMediaId) for layer in layers if layer.sourceMediaId}
    for link in filed:
        found |= owners.get(link, set())
    return found
