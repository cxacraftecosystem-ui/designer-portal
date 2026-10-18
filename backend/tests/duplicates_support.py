"""Seeds and state snapshots shared by the duplicate-artisan suites.

Everything here talks to the database through the module ``db``, and is only ever called inside an
``asyncio.run`` of a coroutine that connects and disconnects it (see tests/conftest.py, "HOW A
DATABASE-BACKED MODULE IS SUPPOSED TO REACH THE DATABASE").
"""

from __future__ import annotations

import json
import os
import uuid
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

from prisma import Json

from app.core.db import db
from app.core.security import hash_password
from app.services import aadhaar_fingerprint, artisan_merge

_URL = os.environ.get("DATABASE_URL", "")
LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

NO_PEER = SimpleNamespace(cross_portal_identity_enabled=False, artisan_duplicate_hold=True)


def tag() -> str:
    return uuid.uuid4().hex[:8]


def fp(seed: str) -> str:
    """A fingerprint-shaped value (64 hex). Never a real Aadhaar's."""
    return (uuid.uuid5(uuid.NAMESPACE_URL, seed).hex * 2)[:64]


async def admin(t: str, role: str = "ADMIN") -> Any:
    return await db.user.create(
        data={
            "email": f"dupes-{role.lower()}-{t}@example.org",
            "name": f"Dupes {role.title()} {t}",
            "role": role,
            "passwordHash": hash_password("unused"),
        }
    )


async def location(state: str = "Rajasthan", district: str = "Jaipur", village: str | None = None) -> Any:
    return await db.location.create(
        data={"latitude": 26.8, "longitude": 75.5, "state": state, "district": district, "village": village}
    )


async def artisan(user: Any, name: str, **extra: Any) -> Any:
    data: dict[str, Any] = {"name": name, "place": extra.pop("place", "Bagru"), "createdById": user.id}
    loc = extra.pop("location", None)
    if loc is not None:
        data["locationId"] = loc.id
    meta = extra.pop("extraMetadata", None)
    if meta is not None:
        data["extraMetadata"] = Json(meta)
    data.update(extra)
    # The fingerprint and its last four are "both or neither" (Artisan_aadhaarFingerprint_pair).
    if data.get("aadhaarFingerprint") and not data.get("aadhaarLast4"):
        data["aadhaarLast4"] = "9012"
    return await db.artisan.create(data=data)


async def make_pair(a_id: str, b_id: str, score: float = 0.9) -> Any:
    from app.services import artisan_duplicates as d

    result = d.Score(score, d.band_for(score) or "POSSIBLE", (("NAME", "Names 90% alike"),))
    pair_id = await d.upsert_within(a_id, b_id, result, source="SCAN")
    return await db.artisanduplicatecandidate.find_unique(where={"id": pair_id})


def sides(pair: Any, a_id: str) -> str:
    """Which side letter (A/B) the artisan ``a_id`` is on in ``pair``."""
    return "A" if pair.artisanAId == a_id else "B"


async def rich_world(user: Any, t: str) -> dict[str, Any]:
    """Two records of one person (``keep`` and ``gone``) with every kind of row naming ``gone``,
    including the collisions each unique table can produce."""
    from app.api.routes.questionnaire import artisan_set_key

    loc = await location()
    keep = await artisan(
        user, f"Lakshmi Devi {t}", location=loc, notes="Keeps the old blocks.",
        createdAt=datetime.now(UTC) - timedelta(days=30),
        extraMetadata={"specialisation": "Dabu", "fieldProvenance": {"name": {"by": user.id}}},
    )
    gone = await artisan(
        user, f"Laxmi Devi {t}", location=loc, phone="9829012345", notes="Prints dabu on Tuesdays.",
        pehchanCardAvailable=True, pehchanCardNumber=f"RJ{t}",
        aadhaarFingerprint=fp(t), aadhaarLast4="9012",
        extraMetadata={"age": "about 50", "fieldProvenance": {"phone": {"by": "someone"}}},
    )
    other = await artisan(user, f"Bystander {t}", location=loc)
    craft = await db.craft.create(data={"name": f"Dabu {t}"})
    product = await db.productdocumentation.create(data={
        "craftName": craft.name, "place": "Bagru", "artisanName": gone.name, "productName": "Bedsheet",
        "createdById": user.id, "artisanId": gone.id,
    })
    tool = await db.tooldocumentation.create(data={
        "craftName": craft.name, "place": "Bagru", "artisanName": gone.name, "toolkitName": "Block",
        "createdById": user.id, "artisanId": gone.id,
    })
    tool2 = await db.tooldocumentation.create(data={
        "craftName": craft.name, "place": "Bagru", "artisanName": keep.name, "toolkitName": "Rack",
        "createdById": user.id,
    })
    media = await db.mediafile.create(data={
        "originalFilename": "a.jpg", "mediaType": "IMAGE", "mimeType": "image/jpeg", "sizeBytes": 10,
        "bucket": "b", "objectKey": f"media/{t}/a.jpg", "uploadedById": user.id, "artisanId": gone.id,
        "linkedRecordType": "artisan", "linkedRecordId": gone.id,
    })
    w1 = await db.workshop.create(data={"title": f"W1 {t}", "date": datetime.now(UTC), "place": "Bagru", "createdById": user.id})
    w2 = await db.workshop.create(data={"title": f"W2 {t}", "date": datetime.now(UTC), "place": "Bagru", "createdById": user.id})
    for wid, aid in ((w1.id, keep.id), (w1.id, gone.id), (w2.id, gone.id)):
        await db.workshopartisan.create(data={"workshopId": wid, "artisanId": aid})
    await db.toolartisan.create(data={"toolId": tool2.id, "artisanId": keep.id})
    await db.toolartisan.create(data={"toolId": tool2.id, "artisanId": gone.id})
    await db.toolartisan.create(data={"toolId": tool.id, "artisanId": gone.id})
    w3 = await db.workshop.create(data={"title": f"W3 {t}", "date": datetime.now(UTC), "place": "Bagru", "createdById": user.id})
    i_gone = await db.questionnaireinterview.create(data={
        "title": f"Interview gone {t}", "createdById": user.id, "workshopId": w3.id,
        "artisanSetKey": artisan_set_key([gone.id], workshop_id=w3.id, design_workshop_id=None),
    })
    i_both = await db.questionnaireinterview.create(data={
        "title": f"Interview both {t}", "createdById": user.id, "workshopId": w2.id,
        "artisanSetKey": artisan_set_key([keep.id, gone.id, other.id], workshop_id=w2.id, design_workshop_id=None),
    })
    for iid, aid in ((i_gone.id, gone.id), (i_both.id, keep.id), (i_both.id, gone.id), (i_both.id, other.id)):
        await db.questionnaireinterviewartisan.create(data={"interviewId": iid, "artisanId": aid})
    # `sortOrder` is unique across the register, so each run takes its own pair of slots.
    slot = 1_000_000 + int(t[:6], 16) * 2
    s1 = await db.questionnairesection.create(data={"code": f"Z{t}1", "title": "S1", "sortOrder": slot})
    s2 = await db.questionnairesection.create(data={"code": f"Z{t}2", "title": "S2", "sortOrder": slot + 1})
    await db.questionnairesectionstatus.create(data={"artisanId": keep.id, "sectionId": s1.id, "status": "DONE", "setById": user.id})
    await db.questionnairesectionstatus.create(data={"artisanId": gone.id, "sectionId": s1.id, "status": "SKIPPED", "setById": user.id})
    await db.questionnairesectionstatus.create(data={"artisanId": gone.id, "sectionId": s2.id, "status": "DONE", "setById": user.id})
    await db.recordrevision.create(data={"recordType": "artisan", "recordId": gone.id, "editedById": user.id, "changes": Json({"phone": {"old": None, "new": "(value recorded)"}})})
    await db.reviewlog.create(data={"recordType": "ARTISAN", "recordId": gone.id, "status": "APPROVED", "reviewerId": user.id})
    await db.entrycomment.create(data={"recordType": "artisan", "recordId": gone.id, "authorId": user.id, "body": "Check the photo"})
    grantee = await admin(t + "g", role="RESEARCHER")
    g1 = await db.dataaccessgrant.create(data={"ownerId": user.id, "granteeId": grantee.id})
    g2 = await db.dataaccessgrant.create(data={"ownerId": user.id, "granteeId": (await admin(t + "h", role="RESEARCHER")).id})
    for gid, aid in ((g1.id, keep.id), (g1.id, gone.id), (g2.id, gone.id)):
        await db.dataaccessscopeitem.create(data={"grantId": gid, "recordType": "artisan", "recordId": aid})
    await db.assignedtask.create(data={"title": f"Legacy {t}", "assigneeId": grantee.id, "createdById": user.id, "recordType": "artisan", "recordId": gone.id})
    await db.assignedtask.create(data={"title": f"List {t}", "assigneeId": grantee.id, "createdById": user.id, "artisanIds": [gone.id, other.id]})
    await db.assignedtask.create(data={"title": f"Both {t}", "assigneeId": grantee.id, "createdById": user.id, "artisanIds": [keep.id, gone.id]})
    await db.recordaccesstoken.create(data={
        "recordType": "ARTISAN", "recordId": gone.id, "secretHash": f"h{t}", "secretLast4": "abcd",
        "expiresAt": datetime.now(UTC) + timedelta(days=30),
    })
    dw = await db.designworkshop.create(data={"title": f"DW {t}", "createdById": user.id})
    p_entry = await db.dwstageentry.create(data={"designWorkshopId": dw.id, "stageKey": "WORKSHOP_PLAN", "entityKey": "participant", "data": Json({"artisanRef": gone.id, "name": gone.name})})
    e_entry = await db.dwstageentry.create(data={"designWorkshopId": dw.id, "stageKey": "BASELINE", "entityKey": "existingProduct", "data": Json({"artisanRef": gone.id})})
    return {
        "keep": keep, "gone": gone, "other": other, "product": product, "tool": tool, "tool2": tool2,
        "media": media, "w1": w1, "w2": w2, "i_gone": i_gone, "i_both": i_both, "s1": s1, "s2": s2,
        "dw": dw, "p_entry": p_entry, "e_entry": e_entry, "craft": craft,
    }


def _clean(value: Any) -> Any:
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except ValueError:
            return value
    if isinstance(value, dict):
        return {k: _clean(v) for k, v in value.items() if k not in ("updatedAt", "version")}
    return value


async def state(ids: list[str], dw_ids: list[str]) -> dict[str, Any]:
    """Every row naming any of ``ids``, as comparable JSON (updatedAt and version left out)."""
    out: dict[str, Any] = {}
    rows = await db.query_raw('SELECT to_jsonb(a) AS r FROM "Artisan" a WHERE a."id" = ANY($1::text[]) ORDER BY a."id"', ids)
    out["Artisan"] = [_clean(r["r"]) for r in rows]
    for spec in artisan_merge.MERGE_REPOINT:
        rows = await db.query_raw(
            f'SELECT to_jsonb(t) AS r FROM "{spec.table}" t WHERE t."{spec.column}" = ANY($1::text[]) AND {spec.where}', ids
        )
        cleaned = [_clean(r["r"]) for r in rows]
        if spec.table == "RecordRevision":
            cleaned = [r for r in cleaned if not ({"merge", "mergeUndone"} & set((r.get("changes") or {}).keys()))]
        out[f"{spec.table}.{spec.column}"] = sorted(cleaned, key=lambda r: json.dumps(r, sort_keys=True, default=str))
    rows = await db.query_raw('SELECT to_jsonb(t) AS r FROM "AssignedTask" t WHERE t."artisanIds" && $1::text[]', ids)
    out["AssignedTask.artisanIds"] = sorted((_clean(r["r"]) for r in rows), key=lambda r: r["id"])
    rows = await db.query_raw('SELECT to_jsonb(t) AS r FROM "DwStageEntry" t WHERE t."designWorkshopId" = ANY($1::text[])', dw_ids)
    out["DwStageEntry"] = sorted((_clean(r["r"]) for r in rows), key=lambda r: r["id"])
    rows = await db.query_raw(
        'SELECT DISTINCT q."id", q."artisanSetKey" FROM "QuestionnaireInterview" q JOIN "QuestionnaireInterviewArtisan" l ON l."interviewId" = q."id" WHERE l."artisanId" = ANY($1::text[]) ORDER BY q."id"', ids
    )
    out["QuestionnaireInterview"] = rows
    rows = await db.query_raw('SELECT to_jsonb(t) AS r FROM "CrossPortalArtisanLink" t WHERE t."artisanId" = ANY($1::text[])', ids)
    out["CrossPortalArtisanLink"] = sorted((_clean(r["r"]) for r in rows), key=lambda r: r["artisanId"])
    return out


__all__ = ["aadhaar_fingerprint"]
