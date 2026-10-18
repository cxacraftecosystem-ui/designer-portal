"""Merge two records of one artisan, keep everything either held, and undo it within 30 days.

AN ADMIN ACTION, NEVER AUTOMATIC. ``POST /artisan-duplicates/{id}/merge`` with a survivor and a side
per field. One transaction (30 s) does, in order:

1. lock both rows, check ``expectedUpdatedAt`` and the blockers;
2. snapshot the record that will be deleted (``to_jsonb`` of the row, minus ``aadhaarNumber``,
   which a merge refuses to touch while it is populated) and the survivor's columns;
3. clear the deleted record's unique keys, then write the chosen values onto the survivor;
4. re-point every row that names the deleted record (:data:`MERGE_REPOINT`) with raw ``UPDATE``
   ``... RETURNING``, logging every moved key in ``ArtisanMerge.moves``. Where a unique key would
   collide, the deleted record's row is deleted and snapshotted (the survivor's row wins);
5. the Field Repository link (this portal only): the peer is asked BEFORE the transaction, and the
   two calls are reversed if the transaction fails;
6. a ``RecordRevision`` on the survivor naming the merge and the side taken for each field;
7. hard-delete the merged row (its duplicate pairs cascade away).

After the commit the survivor is re-scored.

A MERGED ID KEEPS WORKING. :func:`resolve` follows ``ArtisanMerge.mergedId`` -> ``survivorId``
(skipping undone merges, up to five hops): ``GET /artisans/{id}`` answers 404 ``artisan_merged`` with
the survivor's id, and :func:`redirect_payload` / :func:`redirect_ids` rewrite a write that still
names the old id (an offline queue, a stale picker), so it lands on the survivor instead of failing.

UNDO puts the deleted row back with its own id from the snapshot, sets each survivor column back
only where it still holds what the merge wrote (anything edited since is kept and reported), moves
back only the logged rows still pointing at the survivor, re-inserts deleted collision rows whose
key is still free, restores the design-workshop refs (bumping their versions) and reverses the
Field Repository link move. After 30 days :func:`maybe_purge` clears the snapshot, the move log and
the link note; the merge record and its redirect stay forever.

``tests/test_artisan_merge_registry.py`` parses ``schema.prisma`` and fails when a table that points
at Artisan, or carries ``recordType``/``recordId``, is in neither :data:`MERGE_REPOINT` nor
:data:`NOT_REPOINTED`.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import HTTPException

from app.core.db import db
from app.services import aadhaar_fingerprint, artisan_duplicates
from app.services.rich_text import (
    RichDoc,
    from_plain,
    from_stored_text,
    stored_text_document,
    to_json,
    to_plain,
)
from prisma import Json

logger = logging.getLogger("app.artisan_merge")

UNDO_DAYS = artisan_duplicates.UNDO_DAYS
TX_TIMEOUT = timedelta(seconds=30)
TX_MAX_WAIT = timedelta(seconds=10)
RESOLVE_MAX_HOPS = 5

DW_ARTISAN_ENTITIES = ("participant", "existingProduct")

# ── The registry ──────────────────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class Repoint:
    """One column that can name an artisan. ``key`` identifies a row apart from that column (text
    columns); ``unique_with`` are the other columns of a unique key the column is part of;
    ``where`` narrows a polymorphic table to artisan rows; ``name_column`` is a printed copy of the
    artisan's name kept in step (its old value is logged)."""

    table: str
    column: str
    key: tuple[str, ...] = ("id",)
    where: str = "TRUE"
    unique_with: tuple[str, ...] = ()
    name_column: str | None = None
    note: str = ""


MERGE_REPOINT: tuple[Repoint, ...] = (
    Repoint("ProductDocumentation", "artisanId", name_column="artisanName"),
    Repoint("ToolDocumentation", "artisanId", name_column="artisanName"),
    Repoint("MediaFile", "artisanId"),
    Repoint(
        "MediaFile",
        "linkedRecordId",
        where="lower(coalesce(t.\"linkedRecordType\", '')) = 'artisan'",
        note="the tag a media row was captured against",
    ),
    Repoint("WorkshopArtisan", "artisanId", key=("workshopId",), unique_with=("workshopId",)),
    Repoint("ToolArtisan", "artisanId", unique_with=("toolId",)),
    Repoint(
        "QuestionnaireInterviewArtisan",
        "artisanId",
        key=("interviewId",),
        unique_with=("interviewId",),
        note="artisanSetKey is recomputed for every interview touched",
    ),
    Repoint("QuestionnaireSectionStatus", "artisanId", unique_with=("sectionId",)),
    Repoint("RecordRevision", "recordId", where="lower(t.\"recordType\") = 'artisan'"),
    Repoint("ReviewLog", "recordId", where="t.\"recordType\"::text = 'ARTISAN'"),
    Repoint("EntryComment", "recordId", where="lower(t.\"recordType\") = 'artisan'"),
    Repoint(
        "DataAccessScopeItem",
        "recordId",
        where="lower(t.\"recordType\") = 'artisan'",
        unique_with=("grantId", "recordType"),
    ),
    Repoint("AssignedTask", "recordId", where="lower(coalesce(t.\"recordType\", '')) = 'artisan'"),
    Repoint("RecordAccessToken", "recordId", where="t.\"recordType\"::text = 'ARTISAN'"),
)

#: Handled by dedicated code in this module rather than by the generic repoint.
SPECIAL_REPOINT = {
    "AssignedTask.artisanIds": "array of ids; replaced in place, de-duplicated, old list logged",
    "DwStageEntry.data.artisanRef": "participant and existingProduct entries; version bumped",
    "CrossPortalArtisanLink.artisanId": "moved with the Field Repository told first",
}

#: Tables that name an artisan and are deliberately not re-pointed, and why.
NOT_REPOINTED = {
    "CrossPortalAccessLog": "an access log is a fact about what happened to that id",
    "AadhaarLegalHold": "a hold on either record blocks the merge (aadhaar_legal_hold)",
    "ArtisanDuplicateCandidate": "the merged record's pairs cascade away with it",
    "ArtisanMerge": "the merge record itself",
}


def _q(name: str) -> str:
    return '"' + name.replace('"', "") + '"'


# ── Fields ────────────────────────────────────────────────────────────────────────────────────

AADHAAR_VERIFY_COLUMNS = (
    "aadhaarVerifyStatus",
    "aadhaarVerifyMethod",
    "aadhaarVerifiedAt",
    "aadhaarVerifyLast4",
    "aadhaarVerifyName",
    "aadhaarVerifyBirthYear",
    "aadhaarVerifyDob",
    "aadhaarVerifyGender",
    "aadhaarVerifySignedAt",
    "aadhaarVerifyTxn",
    "aadhaarVerifyNameMatch",
    "aadhaarVerifyById",
    "aadhaarVerifyDecidedById",
    "aadhaarVerifyDecidedAt",
)


@dataclass(frozen=True)
class FieldGroup:
    key: str
    label: str
    columns: tuple[str, ...]
    both: bool = False
    locked: bool = False


FIELD_GROUPS: tuple[FieldGroup, ...] = (
    FieldGroup("name", "Name", ("name",)),
    FieldGroup("localName", "Local name", ("localName",)),
    FieldGroup("gender", "Gender", ("gender",)),
    FieldGroup("phone", "Phone", ("phone",)),
    FieldGroup("email", "Email", ("email",)),
    FieldGroup("place", "Place", ("place",)),
    FieldGroup("dateOfBirth", "Date of birth", ("dateOfBirth",)),
    FieldGroup("address", "Address", ("address",), both=True),
    FieldGroup("notes", "Notes", ("notes",), both=True),
    FieldGroup("dos", "Do's", ("dos",), both=True),
    FieldGroup("donts", "Don'ts", ("donts",), both=True),
    FieldGroup("experience", "Experience", ("craftStartDate", "experienceYears", "experienceMonths")),
    FieldGroup("craftId", "Craft", ("craftId",)),
    FieldGroup("location", "Location", ("locationId",)),
    FieldGroup("workshopId", "Workshop", ("workshopId",)),
    FieldGroup("designWorkshopId", "Design workshop", ("designWorkshopId",)),
    FieldGroup("status", "Review status", ("status", "reviewNotes", "reviewedById", "reviewedAt")),
    FieldGroup(
        "aadhaar",
        "Aadhaar",
        ("aadhaarFingerprint", "aadhaarLast4", *AADHAAR_VERIFY_COLUMNS),
        locked=True,
    ),
    FieldGroup("pehchan", "Pehchan card", ("pehchanCardAvailable", "pehchanCardNumber"), locked=True),
)
FIELD_KEYS = {g.key: g for g in FIELD_GROUPS}
UNIQUE_KEYS = ("aadhaarFingerprint", "aadhaarNumber", "pehchanCardNumber")
_STATUS_RANK = {"APPROVED": 4, "PENDING": 3, "NEEDS_REVISION": 2, "DRAFT": 1, "REJECTED": 0}


def _empty(value: Any) -> bool:
    if value is None:
        return True
    if isinstance(value, str):
        if not value.strip():
            return True
        doc = stored_text_document(value)
        return doc is not None and doc.is_empty
    return False


def _group_empty(group: FieldGroup, row: dict[str, Any]) -> bool:
    if group.key == "pehchan":
        return _empty(row.get("pehchanCardNumber"))
    if group.key == "aadhaar":
        return _empty(row.get("aadhaarFingerprint")) and _empty(row.get("aadhaarVerifyStatus"))
    if group.key == "status":
        return _empty(row.get("status"))
    return all(_empty(row.get(col)) for col in group.columns)


def _group_value(group: FieldGroup, row: dict[str, Any]) -> tuple[Any, ...]:
    return tuple(row.get(col) for col in group.columns)


def _side(letter: str, a: dict[str, Any], b: dict[str, Any]) -> dict[str, Any]:
    return a if letter == "A" else b


def _other(letter: str) -> str:
    return "B" if letter == "A" else "A"


def default_choice(group: FieldGroup, survivor: str, a: dict[str, Any], b: dict[str, Any]) -> str:
    """The side a field takes when the admin leaves it alone."""
    keep, other = _side(survivor, a, b), _side(_other(survivor), a, b)
    if group.locked:
        return survivor if not _group_empty(group, keep) else (
            _other(survivor) if not _group_empty(group, other) else survivor
        )
    if group.key == "status":
        rk = _STATUS_RANK.get(str(keep.get("status")), -1)
        ro = _STATUS_RANK.get(str(other.get("status")), -1)
        return survivor if rk >= ro else _other(survivor)
    if _group_empty(group, keep):
        return survivor if _group_empty(group, other) else _other(survivor)
    if group.both and not _group_empty(group, other) and _group_value(group, keep) != _group_value(group, other):
        return "BOTH"
    return survivor


def _options(group: FieldGroup, survivor: str, a: dict[str, Any], b: dict[str, Any]) -> list[str]:
    if group.locked:
        return [default_choice(group, survivor, a, b)]
    return ["A", "B", "BOTH"] if group.both else ["A", "B"]


def _join_text(first: Any, second: Any) -> Any:
    if _empty(first):
        return second
    if _empty(second):
        return first
    if first == second:
        return first
    da, db_ = stored_text_document(str(first)), stored_text_document(str(second))
    if da is not None or db_ is not None:
        doc = RichDoc(blocks=(da or from_plain(first)).blocks + (db_ or from_plain(second)).blocks)
        return json.dumps(to_json(doc), ensure_ascii=False)
    return f"{first}\n{second}"


def _deep_merge(keep: Any, other: Any) -> Any:
    """Survivor keys win; keys only the other record has are added; nested dicts merge the same way."""
    if not isinstance(keep, dict):
        return keep if keep is not None else other
    if not isinstance(other, dict):
        return keep
    out = dict(keep)
    for key, value in other.items():
        if key not in out:
            out[key] = value
        else:
            out[key] = _deep_merge(out[key], value)
    return out


def plan_writes(
    survivor: str, choices: dict[str, str], a: dict[str, Any], b: dict[str, Any]
) -> tuple[dict[str, Any], dict[str, str]]:
    """The columns to write onto the survivor, and the side each field took. Pure."""
    keep, other = _side(survivor, a, b), _side(_other(survivor), a, b)
    taken: dict[str, str] = {}
    writes: dict[str, Any] = {}
    for group in FIELD_GROUPS:
        choice = choices.get(group.key) or default_choice(group, survivor, a, b)
        taken[group.key] = choice
        if choice == "BOTH":
            first, second = a, b
            for col in group.columns:
                joined = _join_text(first.get(col), second.get(col))
                if joined != keep.get(col):
                    writes[col] = joined
            continue
        source = _side(choice, a, b)
        if source is keep:
            continue
        for col in group.columns:
            if source.get(col) != keep.get(col):
                writes[col] = source.get(col)
    meta = _deep_merge(keep.get("extraMetadata"), other.get("extraMetadata"))
    if isinstance(meta, dict):
        provenance_keep = (keep.get("extraMetadata") or {}).get("fieldProvenance") if isinstance(keep.get("extraMetadata"), dict) else None
        provenance_other = (other.get("extraMetadata") or {}).get("fieldProvenance") if isinstance(other.get("extraMetadata"), dict) else None
        provenance = dict(provenance_keep) if isinstance(provenance_keep, dict) else {}
        for group in FIELD_GROUPS:
            if taken[group.key] != _other(survivor):
                continue
            for col in group.columns:
                stamp = provenance_other.get(col) if isinstance(provenance_other, dict) else None
                if stamp is not None:
                    provenance[col] = stamp
                else:
                    provenance.pop(col, None)
        if provenance or "fieldProvenance" in meta:
            meta["fieldProvenance"] = provenance
    if meta != keep.get("extraMetadata"):
        writes["extraMetadata"] = meta
    return writes, taken


def validate_choices(
    choices: dict[str, str], survivor: str, a: dict[str, Any], b: dict[str, Any]
) -> None:
    for key, value in choices.items():
        group = FIELD_KEYS.get(key)
        if group is None:
            raise HTTPException(status_code=422, detail={"code": "merge_bad_choice", "message": f"Unknown field: {key}.", "field": key})
        options = _options(group, survivor, a, b)
        if value not in options:
            message = (
                f"{group.label} comes from whichever record holds it."
                if group.locked
                else f"{group.label} can be {', '.join(options)}."
            )
            raise HTTPException(
                status_code=422,
                detail={"code": "merge_locked_choice" if group.locked else "merge_bad_choice", "message": message, "field": key},
            )


# ── Reading rows ──────────────────────────────────────────────────────────────────────────────


def _jsonish(value: Any) -> Any:
    if isinstance(value, str):
        try:
            return json.loads(value)
        except ValueError:
            return value
    return value


async def _rows(client: Any, ids: list[str], *, lock: bool = False) -> dict[str, dict[str, Any]]:
    sql = 'SELECT to_jsonb(a) AS "row" FROM "Artisan" a WHERE a."id" = ANY($1::text[])'
    if lock:
        sql += " ORDER BY a.\"id\" FOR UPDATE"
    rows = await client.query_raw(sql, ids)
    out: dict[str, dict[str, Any]] = {}
    for row in rows:
        data = _jsonish(row["row"])
        if isinstance(data, dict):
            data["extraMetadata"] = _jsonish(data.get("extraMetadata"))
            out[data["id"]] = data
    return out


def _parse_ts(value: Any) -> datetime | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=UTC)
    text = str(value).replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


def _same_instant(a: Any, b: Any) -> bool:
    pa, pb = _parse_ts(a), _parse_ts(b)
    if pa is None or pb is None:
        return pa is pb
    return abs((pa - pb).total_seconds()) < 0.001


# ── Redirects ─────────────────────────────────────────────────────────────────────────────────


async def resolve(artisan_id: str | None, *, client: Any = None) -> str | None:
    """The survivor a merged id now lives on, or None when ``artisan_id`` was never merged (or the
    merge was undone). Follows a chain of up to five merges."""
    if not artisan_id:
        return None
    reader = client or db
    current = artisan_id
    moved = False
    for _ in range(RESOLVE_MAX_HOPS):
        row = await reader.artisanmerge.find_first(where={"mergedId": current, "undoneAt": None})
        if row is None:
            break
        current = row.survivorId
        moved = True
    return current if moved else None


async def merged_among(ids: list[str], *, client: Any = None) -> set[str]:
    """Which of ``ids`` were merged away (and not undone). One read."""
    wanted = sorted({i for i in ids if isinstance(i, str) and i})
    if not wanted:
        return set()
    reader = client or db
    rows = await reader.artisanmerge.find_many(where={"mergedId": {"in": wanted}, "undoneAt": None})
    return {row.mergedId for row in rows}


async def redirect_ids(ids: list[str] | None, *, client: Any = None) -> list[str] | None:
    """``ids`` with every merged id replaced by its survivor, order kept, duplicates dropped."""
    if not ids:
        return ids
    merged = await merged_among(ids, client=client)
    if not merged:
        return ids
    out: list[str] = []
    for value in ids:
        target = await resolve(value, client=client) if value in merged else None
        final = target or value
        if final not in out:
            out.append(final)
    return out


async def redirect_payload(data: dict[str, Any] | None, *, client: Any = None) -> dict[str, Any] | None:
    """Rewrite ``artisanId``, ``artisanIds`` and an artisan-tagged ``linkedRecordId`` in place."""
    if not isinstance(data, dict):
        return data
    if isinstance(data.get("artisanId"), str):
        target = await resolve(data["artisanId"], client=client)
        if target:
            data["artisanId"] = target
    if isinstance(data.get("artisanIds"), list):
        data["artisanIds"] = await redirect_ids(data["artisanIds"], client=client)
    if str(data.get("linkedRecordType") or "").lower() == "artisan" and isinstance(data.get("linkedRecordId"), str):
        target = await resolve(data["linkedRecordId"], client=client)
        if target:
            data["linkedRecordId"] = target
    return data


def merged_detail(survivor_id: str) -> dict[str, Any]:
    return {
        "code": "artisan_merged",
        "message": "This artisan was merged into another record.",
        "survivorId": survivor_id,
    }


# ── Blockers and warnings ─────────────────────────────────────────────────────────────────────


def _interview_scope(row: Any) -> tuple[str | None, str | None]:
    return getattr(row, "workshopId", None), getattr(row, "designWorkshopId", None)


async def _interview_plan(client: Any, merged_id: str, survivor_id: str) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """The new ``artisanSetKey`` of every interview the merged record sits in, and the collisions."""
    from app.api.routes.questionnaire import artisan_set_key

    links = await client.questionnaireinterviewartisan.find_many(where={"artisanId": merged_id})
    interview_ids = sorted({link.interviewId for link in links})
    if not interview_ids:
        return [], []
    interviews = await client.questionnaireinterview.find_many(
        where={"id": {"in": interview_ids}}, include={"artisans": True}
    )
    plan: list[dict[str, Any]] = []
    for interview in interviews:
        ids = [survivor_id if link.artisanId == merged_id else link.artisanId for link in interview.artisans or []]
        workshop_id, design_workshop_id = _interview_scope(interview)
        new_key = artisan_set_key(ids, workshop_id=workshop_id, design_workshop_id=design_workshop_id)
        plan.append({"id": interview.id, "title": interview.title, "from": interview.artisanSetKey, "to": new_key})
    collisions: list[dict[str, Any]] = []
    moving = {p["id"] for p in plan}
    seen: dict[str, dict[str, Any]] = {}
    for entry in plan:
        key = entry["to"]
        if not key:
            continue
        if key in seen:
            collisions.append({"sourceId": entry["id"], "targetId": seen[key]["id"], "title": entry["title"], "targetTitle": seen[key]["title"]})
            continue
        seen[key] = entry
        holder = await client.questionnaireinterview.find_unique(where={"artisanSetKey": key})
        if holder is not None and holder.id not in moving:
            collisions.append({"sourceId": entry["id"], "targetId": holder.id, "title": entry["title"], "targetTitle": holder.title})
    return plan, collisions


async def blockers_for(
    client: Any, a: dict[str, Any], b: dict[str, Any], survivor: str, *, pair_id: str | None = None
) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    fa, fb = a.get("aadhaarFingerprint"), b.get("aadhaarFingerprint")
    if fa and fb and fa != fb:
        out.append({"code": "different_aadhaar", "message": "The two records hold different Aadhaar numbers."})
    pa, pb = a.get("pehchanCardNumber"), b.get("pehchanCardNumber")
    if pa and pb and pa != pb:
        out.append({"code": "different_pehchan", "message": "The two records hold different Pehchan card numbers."})
    if aadhaar_fingerprint.LEGACY_COLUMN_PRESENT and (a.get("aadhaarNumber") or b.get("aadhaarNumber")):
        out.append({
            "code": "aadhaar_backfill_pending",
            "message": "An Aadhaar number on one record is still waiting for the fingerprint backfill.",
        })
    holds = await client.aadhaarlegalhold.find_many(
        where={"recordId": {"in": [a["id"], b["id"]]}, "releasedAt": None}
    )
    if any(str(h.recordType).lower() == "artisan" for h in holds):
        out.append({"code": "aadhaar_legal_hold", "message": "One of these records is under an Aadhaar legal hold."})
    keep, gone = (a, b) if survivor == "A" else (b, a)
    _, collisions = await _interview_plan(client, gone["id"], keep["id"])
    for clash in collisions:
        out.append({
            "code": "interview_set_collision",
            "message": (
                f"“{clash['title']}” would cover the same artisans as “{clash['targetTitle']}”. "
                "Merge the two interviews first."
            ),
            "action": {
                "kind": "merge_interviews",
                "sourceId": clash["sourceId"],
                "targetId": clash["targetId"],
                **({"duplicatePairId": pair_id} if pair_id else {}),
            },
        })
    links = await client.crossportalartisanlink.find_many(where={"artisanId": {"in": [a["id"], b["id"]]}})
    remotes = {link.remoteArtisanId for link in links}
    if len(links) == 2 and len(remotes) == 2:
        out.append({
            "code": "cross_portal_conflict",
            "message": "The two records are linked to different Field Repository artisans.",
        })
    return out


async def warnings_for(client: Any, a_id: str, b_id: str) -> list[dict[str, Any]]:
    rows = await client.query_raw(
        """SELECT e."designWorkshopId" AS "id", w."title" AS "title"
           FROM "DwStageEntry" e JOIN "DesignWorkshop" w ON w."id" = e."designWorkshopId"
           WHERE e."entityKey" = 'participant' AND e."deletedAt" IS NULL
             AND e."data"->>'artisanRef' IN ($1, $2)
           GROUP BY e."designWorkshopId", w."title"
           HAVING count(DISTINCT e."data"->>'artisanRef') = 2""",
        a_id,
        b_id,
    )
    return [
        {
            "code": "duplicate_participant",
            "message": f"Both are participants in ‘{row['title']}’. Remove one row there after merging.",
            "designWorkshopId": row["id"],
        }
        for row in rows
    ]


# ── Preview ───────────────────────────────────────────────────────────────────────────────────


async def related_counts(client: Any, artisan_id: str) -> dict[str, int]:
    rows = await client.query_raw(
        """SELECT
          (SELECT count(*) FROM "MediaFile" m WHERE m."artisanId" = $1
             OR (lower(coalesce(m."linkedRecordType", '')) = 'artisan' AND m."linkedRecordId" = $1)) AS "media",
          (SELECT count(*) FROM "ProductDocumentation" p WHERE p."artisanId" = $1) AS "products",
          (SELECT count(*) FROM "ToolDocumentation" t WHERE t."artisanId" = $1
             OR EXISTS (SELECT 1 FROM "ToolArtisan" x WHERE x."toolId" = t."id" AND x."artisanId" = $1)) AS "tools",
          (SELECT count(*) FROM "WorkshopArtisan" w WHERE w."artisanId" = $1) AS "workshops",
          (SELECT count(DISTINCT e."designWorkshopId") FROM "DwStageEntry" e
             WHERE e."entityKey" = ANY($2::text[]) AND e."deletedAt" IS NULL AND e."data"->>'artisanRef' = $1) AS "designWorkshops",
          (SELECT count(*) FROM "QuestionnaireInterviewArtisan" q WHERE q."artisanId" = $1) AS "interviews",
          (SELECT count(*) FROM "EntryComment" c WHERE lower(c."recordType") = 'artisan' AND c."recordId" = $1) AS "comments",
          (SELECT count(*) FROM "RecordRevision" r WHERE lower(r."recordType") = 'artisan' AND r."recordId" = $1) AS "revisions",
          (SELECT count(*) FROM "ReviewLog" l WHERE l."recordType"::text = 'ARTISAN' AND l."recordId" = $1) AS "reviewLogs"
        """,
        artisan_id,
        list(DW_ARTISAN_ENTITIES),
    )
    row = rows[0] if rows else {}
    return {key: int(row.get(key) or 0) for key in (
        "media", "products", "tools", "workshops", "designWorkshops", "interviews", "comments", "revisions", "reviewLogs"
    )}


def default_survivor(a: dict[str, Any], b: dict[str, Any], ca: dict[str, int], cb: dict[str, int]) -> str:
    ta, tb = sum(ca.values()), sum(cb.values())
    if ta != tb:
        return "A" if ta > tb else "B"
    da, db_ = _parse_ts(a.get("createdAt")), _parse_ts(b.get("createdAt"))
    if da and db_ and db_ < da:
        return "B"
    return "A"


async def _display_maps(client: Any, rows: list[dict[str, Any]]) -> dict[str, dict[str, str]]:
    def ids(col: str) -> list[str]:
        return sorted({r.get(col) for r in rows if r.get(col)})

    crafts = await client.craft.find_many(where={"id": {"in": ids("craftId")}}) if ids("craftId") else []
    locations = await client.location.find_many(where={"id": {"in": ids("locationId")}}) if ids("locationId") else []
    workshops = await client.workshop.find_many(where={"id": {"in": ids("workshopId")}}) if ids("workshopId") else []
    dws = await client.designworkshop.find_many(where={"id": {"in": ids("designWorkshopId")}}) if ids("designWorkshopId") else []

    def where_of(loc: Any) -> str:
        parts = [getattr(loc, k, None) for k in ("village", "district", "state")]
        text = ", ".join(p for p in parts if p)
        return text or getattr(loc, "placeName", None) or getattr(loc, "address", None) or "Location on file"

    return {
        "craftId": {c.id: c.name for c in crafts},
        "locationId": {loc.id: where_of(loc) for loc in locations},
        "workshopId": {w.id: w.title for w in workshops},
        "designWorkshopId": {d.id: d.title for d in dws},
    }


def _display(group: FieldGroup, row: dict[str, Any], maps: dict[str, dict[str, str]]) -> str | None:
    if group.key == "aadhaar":
        return aadhaar_fingerprint.mask_last4(row.get("aadhaarLast4")) or (
            "Verified" if row.get("aadhaarVerifyStatus") else None
        )
    if group.key == "pehchan":
        if row.get("pehchanCardNumber"):
            return "Card number on file"
        return "No card" if row.get("pehchanCardAvailable") is False else None
    if group.key == "experience":
        parts = []
        if row.get("experienceYears") is not None:
            parts.append(f"{row['experienceYears']} years")
        if row.get("experienceMonths"):
            parts.append(f"{row['experienceMonths']} months")
        if not parts and row.get("craftStartDate"):
            parts.append(f"Since {str(row['craftStartDate'])[:10]}")
        return " ".join(parts) or None
    if group.key == "status":
        value = row.get("status")
        return str(value).replace("_", " ").capitalize() if value else None
    col = group.columns[0]
    value = row.get(col)
    if value is None or value == "":
        return None
    if col in maps:
        return maps[col].get(value) or "On file"
    if col == "dateOfBirth":
        return str(value)[:10]
    if group.both or col in ("name", "localName", "place"):
        return to_plain(from_stored_text(value)) or None
    return str(value)


def _side_summary(row: dict[str, Any], maps: dict[str, dict[str, str]]) -> dict[str, Any]:
    return {
        "id": row["id"],
        "updatedAt": row.get("updatedAt"),
        "createdAt": row.get("createdAt"),
        "name": row.get("name"),
        "place": row.get("place"),
        "craft": maps["craftId"].get(row.get("craftId") or ""),
        "status": row.get("status"),
    }


async def load_pair(pair_id: str, *, client: Any = None) -> Any:
    reader = client or db
    pair = await reader.artisanduplicatecandidate.find_unique(where={"id": pair_id})
    if pair is None:
        raise HTTPException(status_code=404, detail="Pair not found")
    return pair


async def preview(pair: Any) -> dict[str, Any]:
    """Everything the merge screen draws for a WITHIN pair (a CROSS_PORTAL pair is filled in by
    the route, which owns the live Field Repository lookup)."""
    base = {
        "id": pair.id,
        "kind": str(getattr(pair.kind, "value", pair.kind)),
        "score": pair.score,
        "band": pair.band,
        "reasons": _jsonish(pair.reasons) or [],
        "status": str(getattr(pair.status, "value", pair.status)),
    }
    ids = [pair.artisanAId] + ([pair.artisanBId] if pair.artisanBId else [])
    rows = await _rows(db, ids)
    a = rows.get(pair.artisanAId)
    if a is None:
        raise HTTPException(status_code=404, detail="Pair not found")
    maps = await _display_maps(db, list(rows.values()))
    links = await db.crossportalartisanlink.find_many(where={"artisanId": {"in": ids}})
    link_of = {link.artisanId: {"remoteArtisanId": link.remoteArtisanId} for link in links}
    if base["kind"] != "WITHIN":
        return {**base, "a": _side_summary(a, maps), "b": None, "crossPortal": {"a": link_of.get(a["id"]), "b": None}}
    b = rows.get(pair.artisanBId or "")
    if b is None:
        raise HTTPException(status_code=404, detail="Pair not found")
    ca, cb = await related_counts(db, a["id"]), await related_counts(db, b["id"])
    survivor = default_survivor(a, b, ca, cb)
    fields = []
    for group in FIELD_GROUPS:
        entry: dict[str, Any] = {
            "key": group.key,
            "label": group.label,
            "a": _display(group, a, maps),
            "b": _display(group, b, maps),
            "default": default_choice(group, survivor, a, b),
            "options": _options(group, survivor, a, b),
            "defaults": {"A": default_choice(group, "A", a, b), "B": default_choice(group, "B", a, b)},
        }
        if group.locked:
            entry["locked"] = True
        fields.append(entry)
    return {
        **base,
        "a": _side_summary(a, maps),
        "b": _side_summary(b, maps),
        "defaultSurvivor": survivor,
        "fields": fields,
        "related": {"a": ca, "b": cb},
        "blockers": await blockers_for(db, a, b, survivor, pair_id=pair.id),
        "warnings": await warnings_for(db, a["id"], b["id"]),
        "crossPortal": {"a": link_of.get(a["id"]), "b": link_of.get(b["id"])},
    }


# ── The merge ─────────────────────────────────────────────────────────────────────────────────


async def _snapshot_collisions(tx: Any, spec: Repoint, merged: str, survivor: str) -> list[dict[str, Any]]:
    if not spec.unique_with:
        return []
    t = _q(spec.table)
    col = _q(spec.column)
    same = " AND ".join(f's.{_q(c)} = t.{_q(c)}' for c in spec.unique_with)
    s_where = spec.where.replace("t.", "s.")
    rows = await tx.query_raw(
        f'SELECT to_jsonb(t) AS "row" FROM {t} t WHERE t.{col} = $1 AND {spec.where} '
        f'AND EXISTS (SELECT 1 FROM {t} s WHERE s.{col} = $2 AND {s_where} AND {same})',
        merged,
        survivor,
    )
    snaps = [_jsonish(r["row"]) for r in rows]
    for snap in snaps:
        key_where = " AND ".join(f'{_q(k)} = ${i + 2}' for i, k in enumerate(spec.key))
        await tx.execute_raw(
            f'DELETE FROM {t} WHERE {col} = $1 AND {key_where}', merged, *[snap[k] for k in spec.key]
        )
    return snaps


async def _repoint(tx: Any, spec: Repoint, merged: str, survivor: str, survivor_name: str) -> list[dict[str, Any]]:
    t = _q(spec.table)
    col = _q(spec.column)
    keys = ", ".join(f'o.{_q(k)}' for k in spec.key)
    join = " AND ".join(f't.{_q(k)} = o.{_q(k)}' for k in spec.key)
    name_select = f', o.{_q(spec.name_column)} AS "__name"' if spec.name_column else ""
    name_old = f', t.{_q(spec.name_column)}' if spec.name_column else ""
    name_set = f', {_q(spec.name_column)} = $3' if spec.name_column else ""
    params: list[Any] = [survivor, merged] + ([survivor_name] if spec.name_column else [])
    sql = (
        f"WITH o AS (SELECT {', '.join(f't.{_q(k)}' for k in spec.key)}{name_old} FROM {t} t "
        f"WHERE t.{col} = $2 AND {spec.where} FOR UPDATE) "
        f"UPDATE {t} SET {col} = $1{name_set} FROM o WHERE {join.replace('t.', t + '.')} "
        f"AND {t}.{col} = $2 RETURNING {keys}{name_select}"
    )
    rows = await tx.query_raw(sql, *params)
    moved = []
    for row in rows:
        entry: dict[str, Any] = {"key": {k: row[k] for k in spec.key}}
        if spec.name_column:
            entry["name"] = row.get("__name")
        moved.append(entry)
    return moved


async def _repoint_task_arrays(tx: Any, merged: str, survivor: str) -> list[dict[str, Any]]:
    rows = await tx.query_raw(
        'SELECT "id", "artisanIds" FROM "AssignedTask" WHERE $1 = ANY("artisanIds") FOR UPDATE', merged
    )
    moved = []
    for row in rows:
        old = list(_jsonish(row["artisanIds"]) or [])
        new: list[str] = []
        for value in old:
            final = survivor if value == merged else value
            if final not in new:
                new.append(final)
        await tx.execute_raw('UPDATE "AssignedTask" SET "artisanIds" = $2::text[] WHERE "id" = $1', row["id"], new)
        moved.append({"id": row["id"], "from": old, "to": new})
    return moved


async def _repoint_stage_entries(tx: Any, merged: str, survivor: str) -> list[dict[str, Any]]:
    rows = await tx.query_raw(
        """UPDATE "DwStageEntry"
           SET "data" = jsonb_set("data", '{artisanRef}', to_jsonb($1::text)),
               "version" = "version" + 1, "updatedAt" = (now() AT TIME ZONE 'utc')
           WHERE "entityKey" = ANY($3::text[]) AND "data"->>'artisanRef' = $2
           RETURNING "id" """,
        survivor,
        merged,
        list(DW_ARTISAN_ENTITIES),
    )
    return [{"id": r["id"]} for r in rows]


async def _recompute_set_keys(tx: Any, interview_ids: list[str]) -> list[dict[str, Any]]:
    from app.api.routes.questionnaire import artisan_set_key

    changed = []
    if not interview_ids:
        return changed
    interviews = await tx.questionnaireinterview.find_many(
        where={"id": {"in": sorted(set(interview_ids))}}, include={"artisans": True}
    )
    # Two passes so a key can move between two interviews of the same merge without a transient clash.
    plans = []
    for interview in interviews:
        workshop_id, design_workshop_id = _interview_scope(interview)
        new_key = artisan_set_key(
            [link.artisanId for link in interview.artisans or []],
            workshop_id=workshop_id,
            design_workshop_id=design_workshop_id,
        )
        if new_key != interview.artisanSetKey:
            plans.append((interview.id, interview.artisanSetKey, new_key))
    for interview_id, _, _ in plans:
        await tx.execute_raw('UPDATE "QuestionnaireInterview" SET "artisanSetKey" = NULL WHERE "id" = $1', interview_id)
    for interview_id, old, new in plans:
        await tx.execute_raw('UPDATE "QuestionnaireInterview" SET "artisanSetKey" = $2 WHERE "id" = $1', interview_id, new)
        changed.append({"id": interview_id, "from": old, "to": new})
    return changed


async def _write_survivor(tx: Any, survivor_id: str, writes: dict[str, Any]) -> None:
    if not writes:
        await tx.execute_raw(
            'UPDATE "Artisan" SET "updatedAt" = (now() AT TIME ZONE \'utc\') WHERE "id" = $1', survivor_id
        )
        return
    allowed = {c for g in FIELD_GROUPS for c in g.columns} | {"extraMetadata"} | set(UNIQUE_KEYS)
    cols = [c for c in writes if c in allowed]
    sets = ", ".join(f'{_q(c)} = r.{_q(c)}' for c in cols)
    await tx.execute_raw(
        f'UPDATE "Artisan" SET {sets}, "updatedAt" = (now() AT TIME ZONE \'utc\') '
        f'FROM jsonb_populate_record(NULL::"Artisan", $2::jsonb) r WHERE "Artisan"."id" = $1',
        survivor_id,
        json.dumps({c: writes[c] for c in cols}, default=str),
    )


def _http(code: int, detail: Any) -> HTTPException:
    return HTTPException(status_code=code, detail=detail)


async def merge(
    pair: Any,
    *,
    survivor: str,
    choices: dict[str, str],
    expected_updated_at: dict[str, Any] | None,
    reason: str | None,
    user: Any,
    settings: Any,
) -> dict[str, Any]:
    if str(getattr(pair.kind, "value", pair.kind)) != "WITHIN" or not pair.artisanBId:
        raise _http(422, {"code": "merge_cross_portal", "message": "Records in two portals are linked, never merged."})
    if survivor not in ("A", "B"):
        raise _http(422, {"code": "merge_bad_choice", "message": "Choose which record to keep."})
    a_id, b_id = pair.artisanAId, pair.artisanBId
    keep_id, gone_id = (a_id, b_id) if survivor == "A" else (b_id, a_id)

    current = await _rows(db, [a_id, b_id])
    if a_id not in current or b_id not in current:
        raise _http(409, {"code": "record_changed", "message": "One of these records no longer exists."})
    validate_choices(choices, survivor, current[a_id], current[b_id])

    # The Field Repository first (step 5): a link the deleted record holds moves to the survivor.
    links = {
        link.artisanId: link
        for link in await db.crossportalartisanlink.find_many(where={"artisanId": {"in": [a_id, b_id]}})
    }
    cross: dict[str, Any] | None = None
    gone_link, keep_link = links.get(gone_id), links.get(keep_id)
    if gone_link is not None and keep_link is not None and gone_link.remoteArtisanId != keep_link.remoteArtisanId:
        raise _http(409, {"code": "merge_blocked", "blockers": [{"code": "cross_portal_conflict", "message": "The two records are linked to different Field Repository artisans."}]})
    if gone_link is not None:
        cross = {
            "from": gone_id,
            "to": keep_id,
            "remoteArtisanId": gone_link.remoteArtisanId,
            "matchedBy": gone_link.matchedBy,
            "mode": "dropped" if keep_link is not None else "moved",
            "link": {
                "matchedBy": gone_link.matchedBy,
                "consentRecordedById": gone_link.consentRecordedById,
                "consentRecordedAt": gone_link.consentRecordedAt.isoformat() if gone_link.consentRecordedAt else None,
                "remoteAcknowledgedAt": gone_link.remoteAcknowledgedAt.isoformat() if gone_link.remoteAcknowledgedAt else None,
                "lastRefreshedAt": gone_link.lastRefreshedAt.isoformat() if gone_link.lastRefreshedAt else None,
            },
        }
        if cross["mode"] == "moved":
            outcome = await _peer_move_link(settings, gone_link.remoteArtisanId, gone_id, keep_id, gone_link.matchedBy, user)
            if outcome == "withdrawn":
                cross["mode"] = "withdrawn"

    warnings = await warnings_for(db, a_id, b_id)
    try:
        result = await _merge_tx(
            pair, a_id, b_id, survivor, keep_id, gone_id, choices, expected_updated_at, reason, user, cross
        )
    except Exception:
        if cross and cross["mode"] == "moved":
            await _peer_move_link(settings, cross["remoteArtisanId"], keep_id, gone_id, cross["matchedBy"], user, compensating=True)
        raise
    if cross and cross["mode"] == "dropped":
        await _peer_unlink(settings, cross["remoteArtisanId"], gone_id, user)
    await artisan_duplicates.rescore_artisan(keep_id)
    result["warnings"] = warnings
    return result


async def _peer_move_link(
    settings: Any, remote_id: str, from_id: str, to_id: str, matched_by: str, user: Any, *, compensating: bool = False
) -> str:
    """Tell the Field Repository the link now names ``to_id``: link the new id, then unlink the old.

    Returns "moved" or "withdrawn" (the artisan withdrew consent there). Raises 503 when the peer
    cannot be reached, unless compensating (then it logs and carries on)."""
    from app.services import cross_portal
    from app.services.cross_portal_client import PeerUnavailable, peer_call

    requested_by = str(getattr(user, "id", "") or "system:artisan-merge")
    try:
        if not cross_portal.active(settings, needs_url=True):
            raise PeerUnavailable("not configured")
        code, _ = await peer_call(
            settings,
            "POST",
            f"/artisans/{remote_id}/links",
            body={"remoteArtisanId": to_id, "matchedBy": matched_by},
            requested_by=requested_by,
        )
        if code == 409:
            await peer_call(settings, "DELETE", f"/artisans/{remote_id}/links/{from_id}", requested_by=requested_by)
            return "withdrawn"
        if code != 200:
            raise PeerUnavailable(f"status {code}")
        await peer_call(settings, "DELETE", f"/artisans/{remote_id}/links/{from_id}", requested_by=requested_by)
    except PeerUnavailable as exc:
        if compensating:
            logger.error("artisan merge: could not reverse the Field Repository link move: %s", exc)
            return "moved"
        raise _http(503, {"code": "peer_unavailable", "message": "The Field Repository can't be reached right now. Try again."}) from None
    return "moved"


async def _peer_unlink(settings: Any, remote_id: str, artisan_id: str, user: Any) -> None:
    from app.services.cross_portal_client import PeerUnavailable, peer_call

    try:
        await peer_call(
            settings,
            "DELETE",
            f"/artisans/{remote_id}/links/{artisan_id}",
            requested_by=str(getattr(user, "id", "") or "system:artisan-merge"),
        )
    except PeerUnavailable:
        logger.warning("artisan merge: the Field Repository unlink of a merged record will be retried by refresh")


async def _merge_tx(
    pair: Any,
    a_id: str,
    b_id: str,
    survivor: str,
    keep_id: str,
    gone_id: str,
    choices: dict[str, str],
    expected_updated_at: dict[str, Any] | None,
    reason: str | None,
    user: Any,
    cross: dict[str, Any] | None,
) -> dict[str, Any]:
    now = datetime.now(UTC)
    async with db.tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
        # 1. Lock, check the precondition and the blockers.
        rows = await _rows(tx, [a_id, b_id], lock=True)
        if a_id not in rows or b_id not in rows:
            raise _http(409, {"code": "record_changed", "message": "One of these records no longer exists."})
        a, b = rows[a_id], rows[b_id]
        if expected_updated_at:
            for side, row in (("a", a), ("b", b)):
                want = expected_updated_at.get(side)
                if want and not _same_instant(want, row.get("updatedAt")):
                    raise _http(409, {"code": "record_changed", "message": "One of these records changed. Review it again."})
        blockers = await blockers_for(tx, a, b, survivor, pair_id=pair.id)
        if blockers:
            raise _http(409, {"code": "merge_blocked", "blockers": blockers})
        keep, gone = (a, b) if survivor == "A" else (b, a)

        # 2. Snapshot.
        writes, taken = plan_writes(survivor, choices, a, b)
        snapshot: dict[str, Any] = {
            "merged": {k: v for k, v in gone.items() if k != "aadhaarNumber"},
            "survivorBefore": {c: keep.get(c) for c in writes},
        }

        # 3. Free the unique keys, then write the survivor.
        await tx.execute_raw(
            'UPDATE "Artisan" SET "aadhaarFingerprint" = NULL, "aadhaarNumber" = NULL, '
            '"pehchanCardNumber" = NULL WHERE "id" = $1',
            gone_id,
        )
        await _write_survivor(tx, keep_id, writes)
        after = (await _rows(tx, [keep_id]))[keep_id]
        snapshot["survivorAfter"] = {c: after.get(c) for c in writes}
        survivor_name = after.get("name") or keep.get("name") or ""

        # 4. Re-point.
        moves: dict[str, Any] = {"repoint": {}, "deleted": {}}
        for spec in MERGE_REPOINT:
            label = f"{spec.table}.{spec.column}"
            deleted = await _snapshot_collisions(tx, spec, gone_id, keep_id)
            if deleted:
                moves["deleted"][label] = deleted
            moved = await _repoint(tx, spec, gone_id, keep_id, survivor_name)
            if moved:
                moves["repoint"][label] = moved
        moves["taskArrays"] = await _repoint_task_arrays(tx, gone_id, keep_id)
        moves["stageEntries"] = await _repoint_stage_entries(tx, gone_id, keep_id)
        touched_interviews = [m["key"]["interviewId"] for m in moves["repoint"].get("QuestionnaireInterviewArtisan.artisanId", [])]
        touched_interviews += [d["interviewId"] for d in moves["deleted"].get("QuestionnaireInterviewArtisan.artisanId", [])]
        moves["interviewKeys"] = await _recompute_set_keys(tx, touched_interviews)

        # 5. The local half of the link move.
        if cross is not None:
            if cross["mode"] == "moved":
                await tx.execute_raw(
                    'UPDATE "CrossPortalArtisanLink" SET "artisanId" = $1 WHERE "artisanId" = $2', keep_id, gone_id
                )
            else:
                await tx.execute_raw('DELETE FROM "CrossPortalArtisanLink" WHERE "artisanId" = $1', gone_id)

        # 6. The merge record, and the revision on the survivor.
        merge_data: dict[str, Any] = {
            "survivorId": keep_id,
            "mergedId": gone_id,
            "mergedById": getattr(user, "id", None),
            "reason": (reason or None),
            "choices": Json(taken),
            "snapshot": Json(snapshot),
            "moves": Json(moves),
            "undoUntil": now + timedelta(days=UNDO_DAYS),
        }
        if cross:
            merge_data["crossPortal"] = Json(cross)
        record = await tx.artisanmerge.create(data=merge_data)
        await tx.recordrevision.create(
            data={
                "recordType": "artisan",
                "recordId": keep_id,
                "editedById": getattr(user, "id", None),
                "changes": Json({"merge": {"mergeId": record.id, "mergedId": gone_id, "fields": taken}}),
            }
        )

        # 7. Delete the merged record.
        await tx.execute_raw('DELETE FROM "Artisan" WHERE "id" = $1', gone_id)

    return {
        "merge": {
            "id": record.id,
            "survivorId": keep_id,
            "mergedId": gone_id,
            "undoUntil": record.undoUntil,
        },
        "survivorId": keep_id,
    }


# ── Undo ──────────────────────────────────────────────────────────────────────────────────────


def _values_equal(a: Any, b: Any) -> bool:
    if a == b:
        return True
    pa, pb = _parse_ts(a) if isinstance(a, str) else None, _parse_ts(b) if isinstance(b, str) else None
    return pa is not None and pb is not None and pa == pb


async def undo(merge_id: str, *, user: Any, settings: Any) -> dict[str, Any]:
    record = await db.artisanmerge.find_unique(where={"id": merge_id})
    if record is None:
        raise _http(404, "Merge not found")
    if record.undoneAt is not None:
        raise _http(409, {"code": "merge_undo_expired", "message": "This merge was already undone."})
    now = datetime.now(UTC)
    if record.snapshot is None or record.snapshotPurgedAt is not None or _parse_ts(record.undoUntil) < now:
        raise _http(409, {"code": "merge_undo_expired", "message": "Merges can be undone for 30 days."})
    later = await db.artisanmerge.find_first(where={"mergedId": record.survivorId, "undoneAt": None})
    if later is not None:
        raise _http(409, {"code": "merge_undo_later_first", "message": "The kept record was merged again. Undo that merge first."})
    snapshot = _jsonish(record.snapshot) or {}
    moves = _jsonish(record.moves) or {}
    cross = _jsonish(record.crossPortal)
    merged_row = snapshot.get("merged") or {}
    keep_id, gone_id = record.survivorId, record.mergedId

    survivor_now = (await _rows(db, [keep_id])).get(keep_id)
    if survivor_now is None:
        raise _http(409, {"code": "merge_undo_conflict", "message": "The kept record no longer exists."})

    if cross and cross.get("mode") == "moved":
        await _peer_move_link(settings, cross["remoteArtisanId"], keep_id, gone_id, cross.get("matchedBy") or "AADHAAR", user)

    kept_edits: list[str] = []
    skipped: list[str] = []
    try:
        async with db.tx(max_wait=TX_MAX_WAIT, timeout=TX_TIMEOUT) as tx:
            current = (await _rows(tx, [keep_id], lock=True))[keep_id]
            before = snapshot.get("survivorBefore") or {}
            after = snapshot.get("survivorAfter") or {}
            revert: dict[str, Any] = {}
            for col, wrote in after.items():
                if _values_equal(current.get(col), wrote):
                    revert[col] = before.get(col)
                else:
                    kept_edits.append(col)
            await _write_survivor(tx, keep_id, revert)
            # The restored record's identity keys must be free.
            fp, pc = merged_row.get("aadhaarFingerprint"), merged_row.get("pehchanCardNumber")
            clauses = []
            if fp:
                clauses.append({"aadhaarFingerprint": fp})
            if pc:
                clauses.append({"pehchanCardNumber": pc})
            if clauses:
                holder = await tx.artisan.find_first(where={"OR": clauses})
                if holder is not None:
                    raise _http(409, {"code": "merge_undo_conflict", "message": "Another record now holds this Aadhaar or Pehchan number."})
            # 1. The deleted row, with its own id.
            await tx.execute_raw(
                'INSERT INTO "Artisan" SELECT * FROM jsonb_populate_record(NULL::"Artisan", $1::jsonb)',
                json.dumps(merged_row, default=str),
            )
            # 3. Move back what still points at the survivor; re-insert collision rows.
            by_label = {f"{s.table}.{s.column}": s for s in MERGE_REPOINT}
            for label, moved in (moves.get("repoint") or {}).items():
                spec = by_label.get(label)
                if spec is None:
                    continue
                await _move_back(tx, spec, moved, keep_id, gone_id)
            for label, rows in (moves.get("deleted") or {}).items():
                spec = by_label.get(label)
                if spec is None:
                    continue
                for row in rows:
                    inserted = await tx.query_raw(
                        f'INSERT INTO {_q(spec.table)} SELECT * FROM jsonb_populate_record(NULL::{_q(spec.table)}, $1::jsonb) '
                        "ON CONFLICT DO NOTHING RETURNING 1 AS ok",
                        json.dumps(row, default=str),
                    )
                    if not inserted:
                        skipped.append(label)
            for task in moves.get("taskArrays") or []:
                await tx.execute_raw(
                    'UPDATE "AssignedTask" SET "artisanIds" = $2::text[] WHERE "id" = $1 AND "artisanIds" = $3::text[]',
                    task["id"],
                    task["from"],
                    task["to"],
                )
            # 4. Design-workshop refs back, versions bumped.
            entry_ids = [e["id"] for e in moves.get("stageEntries") or []]
            if entry_ids:
                await tx.execute_raw(
                    """UPDATE "DwStageEntry"
                       SET "data" = jsonb_set("data", '{artisanRef}', to_jsonb($1::text)),
                           "version" = "version" + 1, "updatedAt" = (now() AT TIME ZONE 'utc')
                       WHERE "id" = ANY($3::text[]) AND "data"->>'artisanRef' = $2""",
                    gone_id,
                    keep_id,
                    entry_ids,
                )
            interviews = [i["id"] for i in moves.get("interviewKeys") or []]
            interviews += [m["key"]["interviewId"] for m in (moves.get("repoint") or {}).get("QuestionnaireInterviewArtisan.artisanId", [])]
            interviews += [d["interviewId"] for d in (moves.get("deleted") or {}).get("QuestionnaireInterviewArtisan.artisanId", [])]
            await _recompute_set_keys(tx, interviews)
            # 5. The link.
            if cross:
                link = cross.get("link") or {}
                if cross.get("mode") == "moved":
                    await tx.execute_raw(
                        'UPDATE "CrossPortalArtisanLink" SET "artisanId" = $1 WHERE "artisanId" = $2 AND "remoteArtisanId" = $3',
                        gone_id,
                        keep_id,
                        cross["remoteArtisanId"],
                    )
                elif cross.get("mode") == "dropped":
                    await tx.crossportalartisanlink.create(
                        data={
                            "artisanId": gone_id,
                            "remoteArtisanId": cross["remoteArtisanId"],
                            "matchedBy": link.get("matchedBy") or cross.get("matchedBy") or "AADHAAR",
                            "consentRecordedById": link.get("consentRecordedById"),
                            "consentRecordedAt": _parse_ts(link.get("consentRecordedAt")) or now,
                            "remoteAcknowledgedAt": None,
                            "lastRefreshedAt": _parse_ts(link.get("lastRefreshedAt")),
                        }
                    )
            # 6. Revisions on both records, and the merge marked undone.
            for record_id in (keep_id, gone_id):
                await tx.recordrevision.create(
                    data={
                        "recordType": "artisan",
                        "recordId": record_id,
                        "editedById": getattr(user, "id", None),
                        "changes": Json({"mergeUndone": {"mergeId": record.id, "keptSurvivorEdits": kept_edits}}),
                    }
                )
            updated = await tx.artisanmerge.update(
                where={"id": record.id}, data={"undoneAt": now, "undoneById": getattr(user, "id", None)}
            )
    except Exception:
        if cross and cross.get("mode") == "moved":
            await _peer_move_link(settings, cross["remoteArtisanId"], gone_id, keep_id, cross.get("matchedBy") or "AADHAAR", user, compensating=True)
        raise
    await artisan_duplicates.rescore_artisan(gone_id)
    await artisan_duplicates.rescore_artisan(keep_id)
    return {
        "merge": merge_payload(updated),
        "restoredArtisanId": gone_id,
        "keptSurvivorEdits": sorted(set(kept_edits)),
        "skippedRows": sorted(set(skipped)),
    }


async def _move_back(tx: Any, spec: Repoint, moved: list[dict[str, Any]], keep_id: str, gone_id: str) -> None:
    if not moved:
        return
    t = _q(spec.table)
    col = _q(spec.column)
    if spec.name_column:
        for entry in moved:
            key_where = " AND ".join(f'{_q(k)} = ${i + 4}' for i, k in enumerate(spec.key))
            await tx.execute_raw(
                f'UPDATE {t} SET {col} = $1, {_q(spec.name_column)} = $3 WHERE {col} = $2 AND {key_where}',
                gone_id,
                keep_id,
                entry.get("name"),
                *[entry["key"][k] for k in spec.key],
            )
        return
    if len(spec.key) == 1:
        k = spec.key[0]
        await tx.execute_raw(
            f'UPDATE {t} SET {col} = $1 WHERE {col} = $2 AND {_q(k)} = ANY($3::text[])',
            gone_id,
            keep_id,
            [entry["key"][k] for entry in moved],
        )
        return
    for entry in moved:  # pragma: no cover - every registry key is one column today
        key_where = " AND ".join(f'{_q(k)} = ${i + 3}' for i, k in enumerate(spec.key))
        await tx.execute_raw(
            f'UPDATE {t} SET {col} = $1 WHERE {col} = $2 AND {key_where}',
            gone_id,
            keep_id,
            *[entry["key"][k] for k in spec.key],
        )


# ── Listing, purge ────────────────────────────────────────────────────────────────────────────


def merge_payload(record: Any) -> dict[str, Any]:
    return {
        "id": record.id,
        "survivorId": record.survivorId,
        "mergedId": record.mergedId,
        "undoUntil": record.undoUntil,
        "undoneAt": record.undoneAt,
    }


def can_undo(record: Any, *, now: datetime | None = None) -> bool:
    now = now or datetime.now(UTC)
    return (
        record.undoneAt is None
        and record.snapshot is not None
        and record.snapshotPurgedAt is None
        and _parse_ts(record.undoUntil) is not None
        and _parse_ts(record.undoUntil) > now
    )


def merged_name(record: Any) -> str | None:
    snapshot = _jsonish(record.snapshot)
    if not isinstance(snapshot, dict):
        return None
    merged = snapshot.get("merged")
    return merged.get("name") if isinstance(merged, dict) else None


_DAY = timedelta(days=1)
_next_purge: datetime | None = None


async def purge(*, now: datetime | None = None) -> int:
    """Clear the snapshot, the move log and the link note of every merge past its undo window."""
    now = now or datetime.now(UTC)
    return await db.execute_raw(
        """UPDATE "ArtisanMerge"
           SET "snapshot" = NULL, "moves" = NULL, "crossPortal" = NULL, "snapshotPurgedAt" = $1::timestamp
           WHERE "snapshotPurgedAt" IS NULL AND "undoUntil" < $1::timestamp""",
        now.astimezone(UTC).replace(tzinfo=None).isoformat(),
    )


async def maybe_purge(settings: Any, *, now: datetime | None = None) -> int | None:
    """At most once a day per process, from the queue worker."""
    global _next_purge
    now = now or datetime.now(UTC)
    if _next_purge is not None and now < _next_purge:
        return None
    _next_purge = now.replace(microsecond=0) + _DAY
    return await purge(now=now)
