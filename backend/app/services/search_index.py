"""What one record looks like in the search index: one builder per type (docs/SEARCH.md).

``build(entity_type, entity_id)`` reads one record and returns the ``SearchDocument`` row for it, or
``None`` when the record is gone, soft-deleted or has nothing to index (the indexer then deletes the
document). The indexer (``services/search_indexer.py``) writes what this returns; nothing else
decides what goes into the index.

EVERY BUILDER IS AN ALLOWLIST. A field is in the document only because it is named below. Aadhaar,
Pehchan, phone, e-mail, date of birth and the stated address are never named, and :func:`scrub`
removes anything shaped like an identity number or an e-mail address from the free text that is, so
a phone number typed into a remark does not become searchable either.

``audience`` is ``ALL`` (every signed-in account may read it) or ``DW`` (design-workshop data: read
by professor and above, or by an account the workshop is visible to). ``shareable`` says whether the
record may be shown to the Field Repository at all: it names no artisan, or every artisan it names
is linked across the portals (the designer-side record of the artisan's consent). A record that
names an artisan only as free text is never shareable, and neither is design-workshop data, whose
stage answers name participants as free text.
"""

from __future__ import annotations

import hashlib
import re
from collections.abc import Awaitable, Callable
from datetime import datetime
from typing import Any
from urllib.parse import quote

from app.core.db import db
from app.services import design_workshop_data as dw_data

#: Longest body kept. Far below the 1 MB tsvector ceiling even for a long transcript.
BODY_LIMIT = 100_000

AUDIENCE_ALL = "ALL"
AUDIENCE_DW = "DW"

#: Where each type's read-only view lives on the web. ``{id}`` is the entity id, URL-quoted.
OPEN_PATHS: dict[str, str] = {
    "artisans": "/artisans/{id}",
    "crafts": "/crafts/{id}",
    "clusters": "/design-workshops?cluster={id}",
    "workshops": "/workshops/{id}",
    "designWorkshops": "/design-workshops/{id}",
    "interviews": "/questionnaire/interviews/{id}",
    "transcripts": "/media/{id}",
    "reports": "/design-workshops/{id}",
    "products": "/products/{id}",
    "tools": "/tools/{id}",
    "processes": "/processes/{id}",
    "media": "/media/{id}",
}

#: Questionnaire prompts whose answers are identity, never indexed.
_IDENTITY_PROMPT = re.compile(
    r"aadha+r|pehchan|phone|mobile|e-?mail|date of birth|\bdob\b|address|bank|account|ifsc",
    re.IGNORECASE,
)
#: Identity-shaped text inside free text: e-mail addresses and runs of ten or more digits (phone,
#: Aadhaar, card numbers), spaces and dashes allowed between the digits.
_EMAIL = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+")
_LONG_NUMBER = re.compile(r"(?<!\d)(?:\d[ \-]?){9,}\d(?!\d)")


def open_path(entity_type: str, entity_id: str) -> str:
    return OPEN_PATHS[entity_type].format(id=quote(entity_id, safe=""))


def cluster_key(name: str | None) -> str | None:
    """The cluster's index key: lower case, single spaces, trimmed. Same rule as the SQL
    ``search_cluster_key`` in the migration."""
    key = re.sub(r"\s+", " ", (name or "")).strip().lower()
    return key or None


def scrub(text: str | None) -> str:
    """Free text with e-mail addresses and identity-length numbers taken out."""
    if not text:
        return ""
    return _LONG_NUMBER.sub(" ", _EMAIL.sub(" ", str(text))).strip()


def _join(*parts: Any, sep: str = " · ") -> str:
    return sep.join(str(p).strip() for p in parts if p is not None and str(p).strip())


def _body(*parts: Any) -> str:
    return scrub("\n".join(str(p).strip() for p in parts if p and str(p).strip()))[:BODY_LIMIT]


def _ids(*values: Any) -> list[str]:
    out: list[str] = []
    for value in values:
        if value and str(value) not in out:
            out.append(str(value))
    return out


def content_hash(doc: dict[str, Any]) -> str:
    material = "\x1f".join(
        (doc.get("title") or "", doc.get("subtitle") or "", doc.get("body") or "")
    )
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


async def _all_linked(artisan_ids: list[str]) -> bool:
    """Every named artisan carries a cross-portal link (their recorded consent on this portal)."""
    if not artisan_ids:
        return True
    linked = await db.crossportalartisanlink.count(where={"artisanId": {"in": artisan_ids}})
    return linked == len(artisan_ids)


async def _shareable(artisan_ids: list[str], *, free_text_artisan: bool = False) -> bool:
    if free_text_artisan:
        return False
    return await _all_linked(artisan_ids)


def _place_of(record: Any) -> str | None:
    location = getattr(record, "location", None)
    parts = [getattr(record, "place", None)]
    if location is not None:
        parts += [location.village, location.district, location.state]
    seen: list[str] = []
    for part in parts:
        if part and part.strip() and part.strip() not in seen:
            seen.append(part.strip())
    return ", ".join(seen) or None


def _doc(entity_type: str, entity_id: str, **fields: Any) -> dict[str, Any]:
    doc: dict[str, Any] = {
        "entityType": entity_type,
        "entityId": entity_id,
        "subtitle": None,
        "body": "",
        "place": None,
        "craftIds": [],
        "craftCodes": [],
        "workshopIds": [],
        "artisanIds": [],
        "designWorkshopId": None,
        "audience": AUDIENCE_ALL,
        "shareable": False,
        "openPath": open_path(entity_type, entity_id),
        "recordDate": None,
    }
    doc.update(fields)
    doc["title"] = (scrub(doc.get("title")) or "Untitled")[:500]
    if doc.get("subtitle"):
        doc["subtitle"] = scrub(doc["subtitle"])[:500] or None
    doc["body"] = (doc.get("body") or "")[:BODY_LIMIT]
    return doc


def _codes(*crafts: Any) -> list[str]:
    """Each craft's shared code (C26), and its slug when it is on the master list (2026-10-20)."""
    present = [c for c in crafts if c is not None]
    slugs = (getattr(c, "slug", None) for c in present if getattr(c, "subCategoryId", None))
    return _ids(*(getattr(c, "craftCode", None) for c in present), *slugs)


async def _artisan(entity_id: str) -> dict[str, Any] | None:
    row = await db.artisan.find_unique(
        where={"id": entity_id},
        include={"craft": True, "location": True, "crossPortalLink": True, "workshops": True},
    )
    if row is None:
        return None
    craft = row.craft
    return _doc(
        "artisans",
        row.id,
        title=row.name,
        subtitle=_join(craft.name if craft else None, row.place),
        body=_body(
            row.localName, craft.name if craft else None, craft.localName if craft else None
        ),
        place=_place_of(row),
        craftIds=_ids(row.craftId),
        craftCodes=_codes(craft),
        workshopIds=_ids(row.workshopId, *(link.workshopId for link in row.workshops or [])),
        artisanIds=[row.id],
        designWorkshopId=row.designWorkshopId,
        shareable=row.crossPortalLink is not None,
        recordDate=row.recordedAt or row.createdAt,
    )


async def _craft(entity_id: str) -> dict[str, Any] | None:
    row = await db.craft.find_unique(where={"id": entity_id})
    if row is None:
        return None
    return _doc(
        "crafts",
        row.id,
        title=row.name,
        subtitle=_join(row.localName, row.category),
        body=_body(row.localName, row.description),
        place=row.place,
        craftIds=[row.id],
        craftCodes=_codes(row),
        workshopIds=_ids(row.workshopId),
        shareable=True,
        recordDate=row.recordedAt or row.createdAt,
    )


async def _cluster(entity_id: str) -> dict[str, Any] | None:
    rows = await db.query_raw(
        """
        SELECT "clusterName", "craftName", "state", "district" FROM "DesignWorkshop"
         WHERE "deletedAt" IS NULL AND search_cluster_key("clusterName") = $1
        UNION ALL
        SELECT "clusterName", "craftName", "state", "district" FROM "AnnualPlanEntry"
         WHERE "withdrawnAt" IS NULL AND search_cluster_key("clusterName") = $1
        """,
        entity_id,
    )
    if not rows:
        return None
    names = _ids(*(r.get("clusterName") for r in rows))
    crafts = _ids(*(r.get("craftName") for r in rows))
    places = _ids(*(_join(r.get("district"), r.get("state"), sep=", ") for r in rows))
    craft_rows = (
        await db.craft.find_many(
            where={"OR": [{"name": {"equals": c, "mode": "insensitive"}} for c in crafts]}
        )
        if crafts
        else []
    )
    return _doc(
        "clusters",
        entity_id,
        title=names[0],
        subtitle=_join(*crafts[:3]),
        body=_body(*names[1:], *crafts, *places),
        place=places[0] if places else None,
        craftIds=_ids(*(c.id for c in craft_rows)),
        craftCodes=_codes(*craft_rows),
        shareable=True,
    )


async def _workshop(entity_id: str) -> dict[str, Any] | None:
    row = await db.workshop.find_unique(where={"id": entity_id}, include={"location": True})
    if row is None:
        return None
    kind = str(row.workshopType).replace("_", " ").title() if row.workshopType else None
    return _doc(
        "workshops",
        row.id,
        title=row.title,
        subtitle=_join(kind, row.place),
        body=_body(row.description),
        place=_place_of(row),
        workshopIds=[row.id],
        shareable=True,
        recordDate=row.startDate or row.date,
    )


async def _craft_by_name(name: str | None) -> Any | None:
    if not name or not name.strip():
        return None
    return await db.craft.find_first(
        where={"name": {"equals": name.strip(), "mode": "insensitive"}}
    )


async def _design_workshop(entity_id: str) -> dict[str, Any] | None:
    row = await db.designworkshop.find_unique(where={"id": entity_id})
    if row is None or row.deletedAt is not None:
        return None
    entries = await db.dwstageentry.find_many(
        where={"designWorkshopId": row.id, "deletedAt": None, "searchText": {"not": None}},
        order=[{"stageKey": "asc"}, {"ordinal": "asc"}],
    )
    by_stage: dict[str, list[str]] = {}
    for entry in entries:
        if entry.searchText and entry.searchText.strip():
            by_stage.setdefault(entry.stageKey, []).append(entry.searchText.strip())
    stage_text = [
        f"{dw_data.stage_label(key)}\n" + "\n".join(texts)
        for key, texts in sorted(by_stage.items(), key=lambda kv: dw_data.stage_order(kv[0]))
    ]
    craft = await _craft_by_name(row.craftName)
    return _doc(
        "designWorkshops",
        row.id,
        title=row.title,
        subtitle=_join(row.craftName, row.clusterName, _join(row.district, row.state, sep=", ")),
        body=_body(row.workshopCode, row.clusterName, row.craftName, row.venue, *stage_text),
        place=_join(row.venue, row.district, row.state, sep=", ") or None,
        craftIds=_ids(craft.id if craft else None),
        craftCodes=_codes(craft),
        workshopIds=_ids(row.workshopId),
        designWorkshopId=row.id,
        audience=AUDIENCE_DW,
        shareable=False,
        recordDate=row.startDate or row.createdAt,
    )


async def _interview(entity_id: str) -> dict[str, Any] | None:
    row = await db.questionnaireinterview.find_unique(
        where={"id": entity_id},
        include={"artisans": True, "location": True, "responses": {"include": {"question": True}}},
    )
    # A soft-deleted interview (audit H5) leaves search exactly as a removed one did.
    if row is None or row.deletedAt is not None:
        return None
    answers: list[str] = []
    for response in row.responses or []:
        prompt = response.question.prompt if response.question else ""
        if _IDENTITY_PROMPT.search(prompt or "") or not (response.answerText or "").strip():
            continue
        answers.append(response.answerText.strip())
    artisan_ids = _ids(*(link.artisanId for link in row.artisans or []))
    return _doc(
        "interviews",
        row.id,
        title=row.title,
        subtitle=_join(row.language, row.place),
        body=_body(row.notes, *answers),
        place=_place_of(row),
        workshopIds=_ids(row.workshopId),
        artisanIds=artisan_ids,
        designWorkshopId=row.designWorkshopId,
        shareable=await _shareable(artisan_ids),
        recordDate=row.interviewDate or row.createdAt,
    )


def _media_workshop(row: Any) -> str | None:
    if row.designWorkshopId:
        return row.designWorkshopId
    if (row.linkedRecordType or "") == "designWorkshop" and row.linkedRecordId:
        return row.linkedRecordId
    return None


def _media_kind(row: Any) -> str:
    return str(row.mediaType).split(".")[-1].title() if row.mediaType else "File"


#: The tag a file carries for each record it can hang off (``records.media_relation_data``), for a
#: file whose typed column was never filled.
_MEDIA_TAGS: dict[str, tuple[str, ...]] = {
    "artisan": ("artisan",),
    "product": ("product",),
    "tool": ("tool",),
    "interview": ("questionnaire", "questionnaireinterview"),
}

_MEDIA_INCLUDE: dict[str, Any] = {
    "location": True,
    "craft": True,
    "product": True,
    "tool": True,
    "questionnaireInterview": {"include": {"artisans": True}},
}


def _media_link(row: Any, kind: str, column: str) -> str | None:
    value = getattr(row, column, None)
    if value:
        return str(value)
    if (row.linkedRecordType or "").lower() in _MEDIA_TAGS[kind] and row.linkedRecordId:
        return str(row.linkedRecordId)
    return None


async def _media_artisans(row: Any) -> tuple[list[str], bool]:
    """Every artisan a file names, directly or through the product, tool or interview it hangs
    off, and whether one of them is named only as free text (then it is never shareable)."""
    product = getattr(row, "product", None)
    if product is None and (product_id := _media_link(row, "product", "productId")):
        product = await db.productdocumentation.find_unique(where={"id": product_id})
    tool = getattr(row, "tool", None)
    if tool is None and (tool_id := _media_link(row, "tool", "toolId")):
        tool = await db.tooldocumentation.find_unique(where={"id": tool_id})
    interview = getattr(row, "questionnaireInterview", None)
    interview_artisans: list[str] = []
    if interview is not None:
        interview_artisans = [a.artisanId for a in interview.artisans or []]
    elif interview_id := _media_link(row, "interview", "questionnaireInterviewId"):
        links = await db.questionnaireinterviewartisan.find_many(
            where={"interviewId": interview_id}
        )
        interview_artisans = [a.artisanId for a in links]
    free_text = any(
        bool((getattr(r, "artisanName", None) or "").strip()) and not r.artisanId
        for r in (product, tool)
        if r is not None
    )
    artisan_ids = _ids(
        _media_link(row, "artisan", "artisanId"),
        product.artisanId if product is not None else None,
        tool.artisanId if tool is not None else None,
        *interview_artisans,
    )
    return artisan_ids, free_text


async def _media_common(row: Any) -> dict[str, Any]:
    dw_id = _media_workshop(row)
    artisan_ids, free_text = await _media_artisans(row)
    return {
        "place": _place_of(row),
        "craftIds": _ids(row.craftId),
        "craftCodes": _codes(getattr(row, "craft", None)),
        "workshopIds": _ids(row.workshopId),
        "artisanIds": artisan_ids,
        "designWorkshopId": dw_id,
        "audience": AUDIENCE_DW if dw_id else AUDIENCE_ALL,
        "shareable": False if dw_id else await _shareable(artisan_ids, free_text_artisan=free_text),
        "recordDate": row.recordedAt or row.createdAt,
    }


async def _media(entity_id: str) -> dict[str, Any] | None:
    row = await db.mediafile.find_unique(where={"id": entity_id}, include=_MEDIA_INCLUDE)
    if row is None:
        return None
    return _doc(
        "media",
        row.id,
        title=row.caption or row.originalFilename,
        subtitle=_join(_media_kind(row), row.originalFilename if row.caption else None),
        body=_body(row.originalFilename, row.caption, _media_kind(row)),
        **await _media_common(row),
    )


async def _transcript(entity_id: str) -> dict[str, Any] | None:
    row = await db.mediafile.find_unique(where={"id": entity_id}, include=_MEDIA_INCLUDE)
    if row is None or not (row.transcriptText or "").strip():
        return None
    return _doc(
        "transcripts",
        row.id,
        title=row.caption or row.originalFilename,
        subtitle=_join("Transcript", _media_kind(row)),
        body=_body(
            row.originalFilename, row.caption, row.transcriptSummary, row.transcriptText,
            await _transcript_english(row),
        ),
        **await _media_common(row),
    )


async def _transcript_english(row: Any) -> str | None:
    """The English of the transcript's turns (services/transcript_pipeline), so an English search
    finds a Hindi interview. Only while its words may still leave the server: a withdrawal stops new
    English being indexed, and the embeddings already stored are kept (D32)."""
    try:
        from app.services import transcript_pipeline, transcript_portal

        english = await transcript_pipeline.search_english(row.id)
        if not english:
            return None
        may, _refusal = await transcript_portal.may_send(row)
        return english if may else None
    except Exception:  # noqa: BLE001 - the original transcript is still indexed
        return None


async def _report(entity_id: str) -> dict[str, Any] | None:
    row = await db.dwreportexport.find_unique(
        where={"id": entity_id}, include={"designWorkshop": True}
    )
    if row is None or row.designWorkshop is None or row.designWorkshop.deletedAt is not None:
        return None
    workshop = row.designWorkshop
    return _doc(
        "reports",
        row.id,
        title=row.fileName,
        subtitle=_join((row.format or "").upper(), workshop.title),
        body=_body(workshop.title, row.format, workshop.clusterName, workshop.craftName),
        place=_join(workshop.district, workshop.state, sep=", ") or None,
        designWorkshopId=workshop.id,
        audience=AUDIENCE_DW,
        shareable=False,
        openPath=open_path("reports", workshop.id),
        recordDate=row.generatedAt,
    )


async def _product(entity_id: str) -> dict[str, Any] | None:
    row = await db.productdocumentation.find_unique(
        where={"id": entity_id}, include={"craft": True, "location": True}
    )
    if row is None:
        return None
    artisan_ids = _ids(row.artisanId)
    return _doc(
        "products",
        row.id,
        title=row.productName,
        subtitle=_join(row.craftName, row.artisanName),
        body=_body(row.localName, row.craftName, row.artisanName, row.remarks),
        place=_place_of(row),
        craftIds=_ids(row.craftId),
        craftCodes=_codes(row.craft),
        workshopIds=_ids(row.workshopId),
        artisanIds=artisan_ids,
        designWorkshopId=row.designWorkshopId,
        shareable=await _shareable(
            artisan_ids,
            free_text_artisan=bool((row.artisanName or "").strip()) and not row.artisanId,
        ),
        recordDate=row.recordedAt or row.createdAt,
    )


async def _tool(entity_id: str) -> dict[str, Any] | None:
    row = await db.tooldocumentation.find_unique(
        where={"id": entity_id}, include={"craft": True, "location": True}
    )
    if row is None:
        return None
    artisan_ids = _ids(row.artisanId)
    return _doc(
        "tools",
        row.id,
        title=row.toolkitName,
        subtitle=_join(row.englishName, row.craftName, row.artisanName),
        body=_body(row.localName, row.englishName, row.craftName, row.artisanName, row.remarks),
        place=_place_of(row),
        craftIds=_ids(row.craftId),
        craftCodes=_codes(row.craft),
        workshopIds=_ids(row.workshopId),
        artisanIds=artisan_ids,
        designWorkshopId=row.designWorkshopId,
        shareable=await _shareable(
            artisan_ids,
            free_text_artisan=bool((row.artisanName or "").strip()) and not row.artisanId,
        ),
        recordDate=row.recordedAt or row.createdAt,
    )


async def _process(entity_id: str) -> dict[str, Any] | None:
    row = await db.process.find_unique(
        where={"id": entity_id}, include={"steps": True, "product": True}
    )
    if row is None:
        return None
    steps = sorted(row.steps or [], key=lambda s: s.sortOrder)
    product = row.product
    # A process is its product's: it names the product's artisan and shares only when they do.
    artisan_ids = _ids(product.artisanId if product else None)
    free_text = bool(product and (product.artisanName or "").strip() and not product.artisanId)
    return _doc(
        "processes",
        row.id,
        title=row.name,
        subtitle=_join(
            product.productName if product else None, f"{len(steps)} steps" if steps else None
        ),
        body=_body(*(_join(s.name, s.notes, sep=": ") for s in steps)),
        craftIds=_ids(product.craftId if product else None),
        workshopIds=_ids(row.workshopId),
        artisanIds=artisan_ids,
        designWorkshopId=row.designWorkshopId,
        shareable=await _shareable(artisan_ids, free_text_artisan=free_text),
        recordDate=row.recordedAt or row.createdAt,
    )


BUILDERS: dict[str, Callable[[str], Awaitable[dict[str, Any] | None]]] = {
    "artisans": _artisan,
    "crafts": _craft,
    "clusters": _cluster,
    "workshops": _workshop,
    "designWorkshops": _design_workshop,
    "interviews": _interview,
    "transcripts": _transcript,
    "reports": _report,
    "products": _product,
    "tools": _tool,
    "processes": _process,
    "media": _media,
}


async def build(entity_type: str, entity_id: str) -> dict[str, Any] | None:
    """The document for one record, or ``None`` (delete it). Unknown types build nothing."""
    builder = BUILDERS.get(entity_type)
    if builder is None:
        return None
    doc = await builder(entity_id)
    if doc is None:
        return None
    doc["contentHash"] = content_hash(doc)
    if isinstance(doc.get("recordDate"), datetime):
        doc["recordDate"] = doc["recordDate"].isoformat()
    return doc


async def _media_of(where: list[dict[str, Any]]) -> list[tuple[str, str]]:
    """The media and transcript documents of the files matching any of ``where``."""
    if not where:
        return []
    out: list[tuple[str, str]] = []
    for m in await db.mediafile.find_many(where={"OR": where}):
        out += [("media", m.id), ("transcripts", m.id)]
    return out


def _tagged(kind: str, ids: list[str]) -> list[dict[str, Any]]:
    if not ids:
        return []
    return [{"linkedRecordType": {"in": list(_MEDIA_TAGS[kind])}, "linkedRecordId": {"in": ids}}]


async def dependents_of_artisan(artisan_id: str) -> list[tuple[str, str]]:
    """The records whose ``shareable`` depends on this artisan's link: the records naming them,
    the processes of their products, and every file on them, their products, tools or interviews."""
    products = await db.productdocumentation.find_many(where={"artisanId": artisan_id})
    tools = await db.tooldocumentation.find_many(where={"artisanId": artisan_id})
    interviews = await db.questionnaireinterviewartisan.find_many(where={"artisanId": artisan_id})
    product_ids = [p.id for p in products]
    tool_ids = [t.id for t in tools]
    interview_ids = _ids(*(i.interviewId for i in interviews))
    out: list[tuple[str, str]] = [("products", p) for p in product_ids]
    out += [("tools", t) for t in tool_ids]
    out += [("interviews", i) for i in interview_ids]
    if product_ids:
        processes = await db.process.find_many(where={"productId": {"in": product_ids}})
        out += [("processes", p.id) for p in processes]
    where: list[dict[str, Any]] = [{"artisanId": artisan_id}, *_tagged("artisan", [artisan_id])]
    if product_ids:
        where += [{"productId": {"in": product_ids}}, *_tagged("product", product_ids)]
    if tool_ids:
        where += [{"toolId": {"in": tool_ids}}, *_tagged("tool", tool_ids)]
    if interview_ids:
        where += [
            {"questionnaireInterviewId": {"in": interview_ids}},
            *_tagged("interview", interview_ids),
        ]
    return out + await _media_of(where)


async def dependents_of(entity_type: str, entity_id: str) -> list[tuple[str, str]]:
    """The documents that take their artisans (so their ``shareable``) from this record: an
    artisan's records, a product's processes and files, a tool's or an interview's files."""
    if entity_type == "artisans":
        return await dependents_of_artisan(entity_id)
    if entity_type == "products":
        processes = await db.process.find_many(where={"productId": entity_id})
        return [("processes", p.id) for p in processes] + await _media_of(
            [{"productId": entity_id}, *_tagged("product", [entity_id])]
        )
    if entity_type == "tools":
        return await _media_of([{"toolId": entity_id}, *_tagged("tool", [entity_id])])
    if entity_type == "interviews":
        return await _media_of(
            [{"questionnaireInterviewId": entity_id}, *_tagged("interview", [entity_id])]
        )
    return []
