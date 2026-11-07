from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.responses import Response, StreamingResponse

from app.api.routes.data_browser import (
    _DESIGN_WORKSHOP_TAGS,
    _dw_info,
    _dw_rows_text,
    _seg,
    _uniq,
    manifest_ndjson_response,
    workshop_reaches_artisan,
)
from app.core.db import db
from app.core.deps import (
    can_download_dataset,
    can_export_design_workshop_data,
    can_view_design_workshop_data,
    get_current_user,
)
from app.services import answer_drafts, design_workshop_data as dw, naming
from app.services.access import owner_download_scope
from app.services.concurrency import gather_reads
from app.services.csv_export import records_to_csv
from app.services.record_fields import cell, info_panel, info_text, interview_label
from app.services.records import owned_or_granted_where
from app.services.s3 import presign_get_url
from app.services.text_format import by_count, count_noun
from app.services.tool_scope import (
    TOOL_SCOPE_INCLUDE,
    artisan_workshop_ids,
    hide_unseen,
    tool_artisan_ids,
    tool_fits,
    with_tool_scope,
)

router = APIRouter(prefix="/export", tags=["export"])

_EXPORT_ORDER = [{"createdAt": "asc"}, {"id": "asc"}]

EXPORT_TAKE = 5000
MEDIA_TAKE = 20000


UTF8_BOM = "\ufeff"


def csv_response(filename: str, body: str) -> Response:
    return Response(
        content=(body if body.startswith(UTF8_BOM) else UTF8_BOM + body).encode("utf-8"),
        media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": naming.content_disposition(filename)},
    )


_WORKSHOP_INCLUDE = {
    "crafts": {"include": {"craft": True}},
    "artisans": {"include": {"artisan": True}},
}
_ARTISAN_INCLUDE = {
    "craft": True,
    "workshops": {"include": {"workshop": True}},
    "location": True,
}
_PRODUCT_INCLUDE = {"workshop": True}
_TOOL_INCLUDE = TOOL_SCOPE_INCLUDE
_PROCESS_INCLUDE = {"steps": True, "product": True}
_INTERVIEW_INCLUDE = {
    "artisans": {"include": {"artisan": True}},
    "responses": {"include": {"question": True}},
}

_MEDIA_FK_SLOTS = (
    ("productId", "product"),
    ("toolId", "tool"),
    ("questionnaireInterviewId", "questionnaire"),
    ("artisanId", "artisan"),
    ("workshopId", "workshop"),
)
_MEDIA_TAG_SLOTS = ("process", "processstep")
_TAG_ALIASES = {"questionnaireinterview": "questionnaire"}

NO_CRAFT_FOLDER = "No craft"

DETACHED_FOLDER = "_Unlinked/_Detached files"

UNLINKED_ARTISAN_FOLDER = "_Unlinked/_Artisans"

_DETACHED_SLOT = ("", "")


DESIGN_WORKSHOP_FOLDER = "Design workshops"
DW_STAGES_FOLDER = "Stages"

DW_WITHHELD_FILE = "NOT INCLUDED.txt"

_DW_STAGE_SLOT_KIND = "_dwstage"
_DW_LOOSE_SLOT_KIND = "_dwworkshop"


def _dw_workshop_id_of(media: Any) -> str:
    tag = (getattr(media, "linkedRecordType", "") or "").strip()
    if tag in _DESIGN_WORKSHOP_TAGS and getattr(media, "linkedRecordId", None):
        return str(media.linkedRecordId)
    return str(getattr(media, "designWorkshopId", None) or "")


def _dw_answered_stage_keys(entries: list[Any]) -> set[str]:
    keys: set[str] = set()
    for entry in entries:
        found = dw.entity_by_key(str(getattr(entry, "entityKey", "") or ""))
        if found is not None:
            keys.add(found[0].key)
    return keys


def _media_slot(media: Any) -> tuple[str, str] | None:
    tag = (media.linkedRecordType or "").strip().lower()
    if tag in _MEDIA_TAG_SLOTS and media.linkedRecordId:
        return tag, media.linkedRecordId
    for fk, kind in _MEDIA_FK_SLOTS:
        rec_id = getattr(media, fk, None)
        if rec_id:
            return kind, rec_id
    if tag and media.linkedRecordId:
        return _TAG_ALIASES.get(tag, tag), media.linkedRecordId
    return None


def _details(kind: str, record: Any) -> str:
    return info_text(info_panel(kind, record))


@router.get("/dataset", response_model=None)
async def dataset_manifest(
    ownerId: str | None = None,
    stream: bool = False,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any] | StreamingResponse:
    rec_where: dict[str, Any] = {}
    media_vis: dict[str, Any] = {}
    scope: dict[str, set[str]] | None = None
    if ownerId:
        scope = await owner_download_scope(current_user, ownerId)
        rec_where = {"createdById": ownerId}
    elif not can_download_dataset(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Dataset download access required. Ask an admin to grant it, or download a "
            "specific researcher's data you have access to.",
        )
    else:
        rec_where, media_vis = await gather_reads(
            owned_or_granted_where(current_user),
            owned_or_granted_where(current_user, owner_field="uploadedById"),
        )

    def _in_scope(rtype: str, rid: str) -> bool:
        return scope is None or rid in scope.get(rtype, set())

    workshops, artisans, products, tools, interviews, processes = await gather_reads(
        db.workshop.find_many(
            where=rec_where, take=EXPORT_TAKE, include=_WORKSHOP_INCLUDE, order=_EXPORT_ORDER
        ),
        db.artisan.find_many(
            where=rec_where, take=EXPORT_TAKE, include=_ARTISAN_INCLUDE, order=_EXPORT_ORDER
        ),
        db.productdocumentation.find_many(
            where=rec_where, take=EXPORT_TAKE, include=_PRODUCT_INCLUDE, order=_EXPORT_ORDER
        ),
        db.tooldocumentation.find_many(
            where=rec_where, take=EXPORT_TAKE, include=_TOOL_INCLUDE, order=_EXPORT_ORDER
        ),
        db.questionnaireinterview.find_many(
            where={"AND": [rec_where, {"deletedAt": None}]}, take=EXPORT_TAKE, include=_INTERVIEW_INCLUDE, order=_EXPORT_ORDER
        ),
        db.process.find_many(
            where=rec_where, take=EXPORT_TAKE, include=_PROCESS_INCLUDE, order=_EXPORT_ORDER
        ),
    )
    truncated = any(
        len(rows) >= EXPORT_TAKE
        for rows in (workshops, artisans, products, tools, interviews, processes)
    )

    if scope is not None:
        workshops = [w for w in workshops if _in_scope("workshop", w.id)]
        artisans = [a for a in artisans if _in_scope("artisan", a.id)]
        products = [p for p in products if _in_scope("product", p.id)]
        tools = [t for t in tools if _in_scope("tool", t.id)]
        interviews = [i for i in interviews if _in_scope("questionnaire", i.id)]
    tool_viewer = rec_where if ownerId is None else await owned_or_granted_where(current_user)
    await hide_unseen(tools, tool_viewer)
    from app.services import questionnaire_readers

    v2_answers = await questionnaire_readers.session_answers([i.id for i in interviews])

    dw_records: list[Any] = []
    dw_entries: list[Any] = []
    dw_withheld: int | None = None
    if ownerId is None and can_export_design_workshop_data(current_user):
        dw_records = await db.designworkshop.find_many(
            where={"deletedAt": None}, take=EXPORT_TAKE, order=_EXPORT_ORDER
        )
        truncated = truncated or len(dw_records) >= EXPORT_TAKE
        if dw_records:
            dw_entries = await db.dwstageentry.find_many(
                where={"designWorkshopId": {"in": [r.id for r in dw_records]}, "deletedAt": None},
                take=EXPORT_TAKE,
                order=[{"designWorkshopId": "asc"}, {"ordinal": "asc"}, {"id": "asc"}],
            )
            truncated = truncated or len(dw_entries) >= EXPORT_TAKE
    elif ownerId is None and can_view_design_workshop_data(current_user):
        dw_withheld = await db.designworkshop.count(where={"deletedAt": None})

    dw_entries_by_workshop: dict[str, list[Any]] = {}
    for entry in dw_entries:
        dw_entries_by_workshop.setdefault(
            str(getattr(entry, "designWorkshopId", "") or ""), []
        ).append(entry)

    dw_attributions: dict[str, dw.MediaAttribution] = {}
    for record in dw_records:
        dw_attributions.update(
            dw.media_attributions(record, dw_entries_by_workshop.get(record.id, []))
        )

    media_or: list[dict[str, Any]] = []
    if ownerId:
        media_or.append({"uploadedById": ownerId})
    for fk, tags, rows in (
        ("artisanId", ["artisan"], artisans),
        ("productId", ["product"], products),
        ("toolId", ["tool"], tools),
        ("workshopId", ["workshop"], workshops),
        (
            "questionnaireInterviewId",
            ["questionnaire", "questionnaireinterview"],
            interviews,
        ),
    ):
        ids = [r.id for r in rows]
        if ids:
            media_or.append({fk: {"in": ids}})
            media_or.append(
                {"AND": [{"linkedRecordType": {"in": tags}}, {"linkedRecordId": {"in": ids}}]}
            )
    proc_ids = [p.id for p in processes]
    step_ids = [s.id for p in processes for s in (p.steps or [])]
    for tag, ids in (("process", proc_ids), ("processstep", step_ids)):
        if ids:
            media_or.append({"AND": [{"linkedRecordType": tag}, {"linkedRecordId": {"in": ids}}]})
    dw_ids = [record.id for record in dw_records]
    if dw_ids:
        media_or.append(
            {"AND": [{"linkedRecordType": {"in": _DESIGN_WORKSHOP_TAGS}}, {"linkedRecordId": {"in": dw_ids}}]}
        )
        media_or.append({"designWorkshopId": {"in": dw_ids}})
    media: list[Any] = []
    if media_or:
        media_where: dict[str, Any] = {"OR": media_or}
        if media_vis:
            media_where = {"AND": [media_where, media_vis]}
        media = await db.mediafile.find_many(
            where={"AND": [media_where, {"removedAt": None}]}, take=MEDIA_TAKE, order=_EXPORT_ORDER
        )
        truncated = truncated or len(media) >= MEDIA_TAKE

    media_by: dict[tuple[str, str], list[Any]] = {}
    slotless_media: list[Any] = []
    dw_id_set = set(dw_ids)
    for m in media:
        if dw_id_set:
            wid = _dw_workshop_id_of(m)
            if wid in dw_id_set:
                attribution = dw_attributions.get(m.id)
                media_by.setdefault(
                    (_DW_STAGE_SLOT_KIND, f"{wid}:{attribution.stage_key}")
                    if attribution is not None
                    else (_DW_LOOSE_SLOT_KIND, wid),
                    [],
                ).append(m)
                continue
        slot = _media_slot(m)
        if slot is not None:
            media_by.setdefault(slot, []).append(m)
        else:
            slotless_media.append(m)

    files: list[dict[str, str]] = []
    used_dirs: set[str] = set()
    used_files: set[str] = set()

    skipped_media: list[str] = []

    placed_media_slots: set[tuple[str, str]] = set()

    def add_media(prefix: str, rtype: str, rid: str) -> None:
        placed_media_slots.add((rtype, rid))
        for m in media_by.get((rtype, rid), []):
            leaf = _seg(m.originalFilename, m.id)
            if m.objectKey:
                try:
                    signed = presign_get_url(
                        m.objectKey,
                        filename=leaf,
                        mime_type=m.mimeType or "application/octet-stream",
                        expires_in=6 * 3600,
                    )
                except Exception:  # noqa: BLE001 — see above: a signing failure is a skipped file.
                    signed = None
                if signed:
                    files.append({"path": _uniq(f"{prefix}/{leaf}", used_files), "url": signed})
                    continue
            skipped_media.append(m.id)

    def add_text(prefix: str, name: str, content: str) -> None:
        if content.strip():
            files.append({"path": _uniq(f"{prefix}/{name}", used_files), "content": content})

    processes_by_product: dict[str, list[Any]] = {}
    for p in processes:
        processes_by_product.setdefault(p.productId, []).append(p)

    placed_products: set[str] = set()
    placed_tools: set[str] = set()
    placed_interviews: set[str] = set()
    placed_artisans: set[str] = set()

    def emit_product(prefix: str, product: Any) -> None:
        placed_products.add(product.id)
        base = _uniq(f"{prefix}/Products/{_seg(product.productName, product.id)}", used_dirs)
        add_text(base, "details.txt", _details("product", product))
        add_media(base, "product", product.id)
        for proc in processes_by_product.get(product.id, []):
            pbase = _uniq(f"{base}/Processes/{_seg(proc.name, proc.id)}", used_dirs)
            add_text(pbase, "details.txt", _details("process", proc))
            add_media(pbase, "process", proc.id)
            for step in proc.steps or []:
                sbase = _uniq(f"{pbase}/{_seg(step.name, step.id)}", used_dirs)
                add_media(sbase, "processstep", step.id)

    def emit_tool(prefix: str, tool: Any) -> None:
        placed_tools.add(tool.id)
        base = _uniq(f"{prefix}/Tools/{_seg(tool.toolkitName, tool.id)}", used_dirs)
        add_text(base, "details.txt", _details("tool", tool))
        add_media(base, "tool", tool.id)

    def emit_interview(prefix: str, interview: Any) -> None:
        placed_interviews.add(interview.id)
        label = _seg(interview_label(interview), interview.id)
        base = _uniq(f"{prefix}/Questionnaires/{label}", used_dirs)
        answers = []
        for r in interview.responses or []:
            q = getattr(r, "question", None)
            prompt = getattr(q, "prompt", r.questionId) if q else r.questionId
            code = getattr(q, "sectionCode", "") if q else ""
            text = answer_drafts.marked_answer(cell(r.answerText), getattr(r, "answerOrigin", None))
            answers.append(f"[{code}] {prompt}\n  -> {text}\n")
        for a in v2_answers.get(interview.id, []):
            owner = f" ({a['owner']})" if a.get("owner") else ""
            code = a.get("displayCode") or a.get("sectionCode") or ""
            answers.append(f"[{code}] {a['prompt']}{owner}\n  -> {cell(a['text'])}\n")
        add_text(base, "answers.txt", _details("interview", interview) + "\n\n" + "".join(answers))
        add_media(base, "questionnaire", interview.id)

    interviews_for_artisan: dict[str, list[Any]] = {}
    for it in interviews:
        for link in it.artisans or []:
            interviews_for_artisan.setdefault(link.artisanId, []).append(it)

    products_by_slot: dict[tuple[str, str], list[Any]] = {}
    for product in products:
        if product.workshopId and product.artisanId:
            products_by_slot.setdefault((product.workshopId, product.artisanId), []).append(product)
    tools_by_artisan: dict[str, list[Any]] = {}
    tools_by_workshop: dict[str, list[Any]] = {}
    for tool in tools:
        for artisan_id in tool_artisan_ids(tool):
            tools_by_artisan.setdefault(artisan_id, []).append(tool)
        if tool.workshopId:
            tools_by_workshop.setdefault(tool.workshopId, []).append(tool)

    for ws in workshops:
        wbase = _uniq(f"Workshops/{_seg(ws.title, ws.id)}", used_dirs)
        add_text(wbase, "details.txt", _details("workshop", ws))
        add_media(wbase, "workshop", ws.id)
        reached = {a.id for a in artisans if workshop_reaches_artisan(ws, a)}
        orphans = {
            artisan_id
            for tool in tools_by_workshop.get(ws.id, [])
            if reached.isdisjoint(tool_artisan_ids(tool))
            for artisan_id in tool_artisan_ids(tool)
        }
        ws_artisans = [a for a in artisans if a.id in reached or a.id in orphans]
        members = {link.artisanId for link in ws.artisans or []} | {
            a.id for a in ws_artisans if ws.id in artisan_workshop_ids(a)
        }

        craft_names: dict[str, str] = {}
        for link in ws.crafts or []:
            if link.craftId:
                craft = getattr(link, "craft", None)
                craft_names.setdefault(
                    link.craftId, _seg(getattr(craft, "name", None), link.craftId)
                )
        buckets: dict[str | None, list[Any]] = {craft_id: [] for craft_id in craft_names}
        for artisan in ws_artisans:
            if artisan.craftId and artisan.craftId not in craft_names:
                craft = getattr(artisan, "craft", None)
                craft_names[artisan.craftId] = _seg(getattr(craft, "name", None), artisan.craftId)
            buckets.setdefault(artisan.craftId, []).append(artisan)

        for craft_id, bucket in buckets.items():
            if not bucket:
                continue
            cbase = _uniq(f"{wbase}/{craft_names.get(craft_id) or NO_CRAFT_FOLDER}", used_dirs)
            for artisan in bucket:
                placed_artisans.add(artisan.id)
                abase = _uniq(f"{cbase}/{_seg(artisan.name, artisan.id)}", used_dirs)
                add_text(abase, "details.txt", _details("artisan", artisan))
                add_media(abase, "artisan", artisan.id)
                for product in products_by_slot.get((ws.id, artisan.id), []):
                    emit_product(abase, product)
                for tool in tools_by_artisan.get(artisan.id, []):
                    if tool_fits(tool, ws.id, artisan.id, members):
                        emit_tool(abase, tool)
                for it in interviews_for_artisan.get(artisan.id, []):
                    emit_interview(abase, it)

    products_by_artisan: dict[str, list[Any]] = {}
    for product in products:
        if product.artisanId:
            products_by_artisan.setdefault(product.artisanId, []).append(product)
    for artisan in artisans:
        if artisan.id in placed_artisans:
            continue
        abase = _uniq(f"{UNLINKED_ARTISAN_FOLDER}/{_seg(artisan.name, artisan.id)}", used_dirs)
        add_text(abase, "details.txt", _details("artisan", artisan))
        add_media(abase, "artisan", artisan.id)
        for product in products_by_artisan.get(artisan.id, []):
            if product.id not in placed_products:
                emit_product(abase, product)
        for tool in tools_by_artisan.get(artisan.id, []):
            if tool.id not in placed_tools:
                emit_tool(abase, tool)
        for it in interviews_for_artisan.get(artisan.id, []):
            if it.id not in placed_interviews:
                emit_interview(abase, it)

    for product in products:
        if product.id not in placed_products:
            emit_product(f"_Unlinked/{_seg(product.artisanName, 'artisan')}", product)
    for tool in tools:
        if tool.id not in placed_tools:
            emit_tool(f"_Unlinked/{_seg(tool.artisanName, 'artisan')}", tool)
    for it in interviews:
        if it.id not in placed_interviews:
            emit_interview("_Unlinked", it)

    for record in dw_records:
        entries = dw_entries_by_workshop.get(record.id, [])
        grouped, unknown = dw.flatten(record, entries)
        answered = _dw_answered_stage_keys(entries)

        wbase = _uniq(f"{DESIGN_WORKSHOP_FOLDER}/{_seg(record.title, record.id)}", used_dirs)
        add_text(wbase, "details.txt", info_text(_dw_info(record, len(answered), len(entries))))

        notes: list[str] = []
        for item in unknown:
            if item.entity_key == dw.CUSTOM_ENTITY_KEY:
                notes.append(
                    f"{count_noun(item.rows, 'answer')} to this workshop's own designer-written "
                    f"questions {by_count(item.rows, 'is', 'are')} "
                    "not in this archive. Open the workshop in View Data (By design workshop) and "
                    "read designer-questions.txt there."
                )
            else:
                notes.append(
                    f"{item.rows} {'entry' if item.rows == 1 else 'entries'} under "
                    f"'{item.entity_key}' come from a newer version of the form and can't be "
                    "shown here."
                )
        add_text(wbase, "not-shown.txt", "\n".join(notes))

        cited_stages = {
            slot[1].split(":", 1)[1]
            for slot in media_by
            if slot[0] == _DW_STAGE_SLOT_KIND and slot[1].startswith(f"{record.id}:")
        }
        for stage in dw.stages():
            with_rows = [
                (entity, rows)
                for entity in stage.entities
                if (rows := grouped.get(entity.key) or [])
            ]
            has_media = stage.key in cited_stages
            if not with_rows and not has_media:
                continue
            sbase = _uniq(
                f"{wbase}/{DW_STAGES_FOLDER}/"
                f"{_seg(f'{stage.number:02d} {stage.title}', f'Stage {stage.number}')}",
                used_dirs,
            )
            for entity, rows in with_rows:
                add_text(
                    sbase,
                    naming.workshop_filename(record, entity.title or entity.key, "txt", dated=False),
                    _dw_rows_text(entity.title, dw.entity_columns(entity), rows),
                )
            if has_media:
                add_media(sbase, _DW_STAGE_SLOT_KIND, f"{record.id}:{stage.key}")

        add_media(wbase, _DW_LOOSE_SLOT_KIND, record.id)

    if dw_withheld:
        add_text(
            DESIGN_WORKSHOP_FOLDER,
            DW_WITHHELD_FILE,
            f"{count_noun(dw_withheld, 'design workshop')} {by_count(dw_withheld, 'is', 'are')} "
            "not in this "
            "archive.\n\nOnly an admin can download design workshop data. You can read all of it on "
            "screen: View Data, then the By design workshop folder.\n",
        )

    detached: list[Any] = []
    if scope is None:
        detached.extend(slotless_media)
        for slot, rows in media_by.items():
            if slot not in placed_media_slots:
                detached.extend(rows)
    if detached:
        media_by[_DETACHED_SLOT] = detached
        add_media(_uniq(DETACHED_FOLDER, used_dirs), *_DETACHED_SLOT)

    media_count = sum(1 for f in files if "url" in f)
    if stream:
        return manifest_ndjson_response(
            files,
            media_count,
            truncated or bool(skipped_media),
            "dataset.ndjson",
            skipped_media=len(skipped_media),
        )
    return {
        "files": files,
        "totalFiles": len(files),
        "totalMedia": media_count,
        "skippedMedia": len(skipped_media),
        "truncated": truncated or bool(skipped_media),
    }


def _require_dataset_download(current_user: Any) -> None:
    if not can_download_dataset(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Dataset download access required to export CSVs. Ask an admin to grant the "
            "dataset-download permission.",
        )


_CSV_INCLUDE = {"media": {"where": {"removedAt": None}}, "createdBy": True, "workshop": True}


@router.get("/products.csv")
async def export_products(current_user: Any = Depends(get_current_user)) -> Response:
    _require_dataset_download(current_user)
    records = await db.productdocumentation.find_many(
        where=await owned_or_granted_where(current_user),
        include=_CSV_INCLUDE,
        take=EXPORT_TAKE,
        order=_EXPORT_ORDER,
    )
    body = records_to_csv("product", records, truncated=len(records) >= EXPORT_TAKE)
    return csv_response(naming.export_filename("Product", "Export", "csv"), body)


@router.get("/tools.csv")
async def export_tools(current_user: Any = Depends(get_current_user)) -> Response:
    _require_dataset_download(current_user)
    visible = await owned_or_granted_where(current_user)
    records = await db.tooldocumentation.find_many(
        where=visible,
        include=with_tool_scope(_CSV_INCLUDE),
        take=EXPORT_TAKE,
        order=_EXPORT_ORDER,
    )
    await hide_unseen(records, visible)
    body = records_to_csv("tool", records, truncated=len(records) >= EXPORT_TAKE)
    return csv_response(naming.export_filename("Tool", "Export", "csv"), body)


_ARTISAN_CSV_INCLUDE = {
    "craft": True,
    "location": True,
    "createdBy": True,
    "media": {"where": {"removedAt": None}},
    "workshops": {"include": {"workshop": True}},
}


@router.get("/artisans.csv")
async def export_artisans(current_user: Any = Depends(get_current_user)) -> Response:
    _require_dataset_download(current_user)
    records = await db.artisan.find_many(
        where=await owned_or_granted_where(current_user),
        include=_ARTISAN_CSV_INCLUDE,
        take=EXPORT_TAKE,
        order=_EXPORT_ORDER,
    )
    body = records_to_csv("artisan", records, truncated=len(records) >= EXPORT_TAKE)
    return csv_response(naming.export_filename("Artisan", "Export", "csv"), body)


@router.get("/workshops.csv")
async def export_workshops(current_user: Any = Depends(get_current_user)) -> Response:
    _require_dataset_download(current_user)
    records = await db.workshop.find_many(
        where=await owned_or_granted_where(current_user),
        include={"createdBy": True, "media": {"where": {"removedAt": None}}},
        take=EXPORT_TAKE,
        order=_EXPORT_ORDER,
    )
    body = records_to_csv("workshop", records, truncated=len(records) >= EXPORT_TAKE)
    return csv_response(naming.export_filename("Workshop", "Export", "csv"), body)
