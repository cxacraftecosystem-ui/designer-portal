from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.responses import Response, StreamingResponse

# _seg (filesystem-safe segment) and _uniq (collision-free path) are SHARED with the data browser
# rather than re-implemented here: both modules emit the same manifest shape into the same
# client-side zip builder, so the two must name and de-duplicate paths identically or the same
# repository would unpack differently depending on which endpoint produced the manifest.
# workshop_reaches_artisan is shared for the same reason one level up — WHICH artisans a workshop
# contains. Restating that rule here is what let the zip and the browser disagree; see its use below.
# manifest_ndjson_response is shared for the same reason and one more: the streamed shape has to be
# byte-identical between the two endpoints or the ONE client-side reader that consumes both
# (WorkshopRepository.readManifest on Android) would need two parsers, which is how the two
# manifests drifted apart the last time they were written separately.
from app.api.routes.data_browser import (
    _seg,
    _uniq,
    manifest_ndjson_response,
    workshop_reaches_artisan,
)
from app.core.db import db
from app.core.deps import can_download_dataset, get_current_user
from app.services.access import owner_download_scope
from app.services.concurrency import gather_reads
from app.services.csv_export import records_to_csv
from app.services.record_fields import info_panel, info_text, interview_label
from app.services.records import owned_or_granted_where
from app.services.s3 import presign_get_url

router = APIRouter(prefix="/export", tags=["export"])

# THE ORDER EVERY CAPPED READ IN THIS MODULE USES, AND IT IS NOT DECORATION. A LIMIT with no ORDER BY
# has no defined row set in Postgres: the surviving rows can change with statistics, with a concurrent
# vacuum, or with an index choice. Two downloads of the same archive a week apart could therefore hold
# two DIFFERENT five thousands, both reporting ``truncated: true``, and a reviewer diffing them would
# conclude records had been deleted. The two CSV routes at the bottom of this file always got this
# right; the manifest's seven reads did not.
#
# THE ``id`` TIEBREAKER IS LOAD-BEARING. ``createdAt`` alone is not unique — the closed viewer-picker
# finding measured 204 accounts sharing one sort key on the live corpus — so a timestamp-only order
# still leaves the cut arbitrary inside a tie. ASCENDING rather than descending so successive exports
# are NESTED as the archive grows (last week's zip is a prefix of this week's) instead of disjoint,
# which is the property an archivist comparing two downloads actually needs.
_EXPORT_ORDER = [{"createdAt": "asc"}, {"id": "asc"}]

# Per-table row cap. The whole repository is pulled into memory here and this runs on a
# single-worker t3.micro, so an unbounded find_many is one bad day away from an OOM; 5000 matches
# the data browser's per-sheet cap (REPORT_TAKE). Hitting any cap raises the response's
# ``truncated`` flag so the client can say so instead of quietly handing over a partial dataset.
EXPORT_TAKE = 5000
# Media rows are the one table that legitimately runs into five figures.
MEDIA_TAKE = 20000


def csv_response(filename: str, body: str) -> Response:
    return Response(
        content=body,
        media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


# ---------------------------------------------------------------------------
# Full-dataset manifest. The client downloads every media object straight from
# S3 (keeping the t3.micro out of the heavy path) and zips them into a directory
# tree:  Workshops/<workshop>/<craft>/<artisan>/{Products/<p>/Processes/<proc>,
# Tools/<t>, Questionnaires/<i>} plus an _Unlinked area so nothing is dropped.
# Each leaf carries the record's media (original, already-nomenclatured filenames)
# and a details.txt. Records with no workshop land under _Unlinked — INCLUDING
# ARTISANS, who for a long time were the one record type this sentence promised for
# and the emit loop did not deliver: see ``placed_artisans`` below. Those get a
# section of their own, _Unlinked/_Artisans, because a PERSON'S folder and the
# _Unlinked/<artisanName> grouping label are different things and must not be able
# to resolve to the same path: see ``UNLINKED_ARTISAN_FOLDER``. A FILE whose
# parent record no longer exists lands there too, under _Detached files: see the
# sweep at the end of ``dataset_manifest``, which is the same defect one level down.
#
# Every details.txt body comes from the shared field registry
# (app/services/record_fields.py), so this manifest, the data browser's info cards and the .xlsx
# report always describe a record with the same fields, the same labels and the same value
# coercion — including the masking the registry applies to an artisan's Aadhaar number.
# ---------------------------------------------------------------------------

# Relation includes chosen so every getter in the registry's spec can resolve: a product's
# workshop title, an interview's artisan names, a process's parent product, and the createdBy the
# CSV's provenance columns report.
_WORKSHOP_INCLUDE = {
    "crafts": {"include": {"craft": True}},
    "artisans": {"include": {"artisan": True}},
}
# `location` carries the artisan's State and Pincode, which the record spec prints into details.txt
# and the workbook; without it those two cells silently fall back to the legacy extraMetadata.
_ARTISAN_INCLUDE = {
    "craft": True,
    "workshops": {"include": {"workshop": True}},
    "location": True,
}
_PRODUCT_INCLUDE = {"workshop": True}
_TOOL_INCLUDE = {"workshop": True}
_PROCESS_INCLUDE = {"steps": True, "product": True}
_INTERVIEW_INCLUDE = {
    "artisans": {"include": {"artisan": True}},
    "responses": {"include": {"question": True}},
}

# A media row is filed under exactly ONE record folder, most specific relation first — the same
# precedence data_browser._media_owner_id uses. A row commonly carries several links at once (a
# product photo that also names its workshop, a typed FK alongside the string tag pair), and
# without a single winner the same object would be zipped into two or three folders.
_MEDIA_FK_SLOTS = (
    ("productId", "product"),
    ("toolId", "tool"),
    ("questionnaireInterviewId", "questionnaire"),
    ("artisanId", "artisan"),
    ("workshopId", "workshop"),
)
# Finer-grained than any FK column, because process/process-step attachments have no FK of their
# own and are carried by the tag pair alone.
_MEDIA_TAG_SLOTS = ("process", "processstep")
# The interview tag is written both ways in the wild; the tree slot is "questionnaire".
_TAG_ALIASES = {"questionnaireinterview": "questionnaire"}

# The craft folder for artisans with no craft on file. Spelled exactly as the data browser labels
# that folder (_craft_folder_entries), so a researcher who downloads the zip after browsing the
# tree finds the same artisan under the same name.
NO_CRAFT_FOLDER = "No craft"

# Where a file whose slot nothing emitted is filed. See the sweep at the bottom of
# ``dataset_manifest`` for why such a file exists at all.
DETACHED_FOLDER = "_Unlinked/_Detached files"

# Where an artisan no workshop reaches is filed, with their own records nested beneath them.
#
# A SECTION OF ITS OWN, BECAUSE THE TWO KINDS OF ``_Unlinked`` FOLDER MEAN DIFFERENT THINGS AND
# COLLIDED WHEN THEY SHARED A NAMESPACE. ``_Unlinked/<name>`` has always been a GROUPING LABEL built
# from ``product.artisanName`` / ``tool.artisanName`` — a denormalised string on the record, never
# resolved to a person — and it is deliberately not uniqued, so that every orphan record naming the
# same string lands in one folder. The artisan folder added later is the opposite: a RECORD, one per
# person, uniqued, carrying that person's details.txt and photographs.
#
# Spelled the same way they overlap on the string, and the flat buckets are emitted AFTER the artisan
# pass, so the overlap resolves the wrong way round: an unlinked artisan called "Kamla Devi" takes
# ``_Unlinked/Kamla Devi``, and then any unattached product whose ``artisanName`` column happens to
# read "Kamla Devi" — a DIFFERENT artisan of the same name, one filed under a workshop, or a typed-in
# string with no artisan row behind it at all — is emitted into that person's folder as
# ``_Unlinked/Kamla Devi/Products/…``. Nothing warns; ``_uniq`` cannot help, because the flat prefix
# never goes through it; and the zip now attributes one person's work to another, which for a
# craft repository is the single worst thing this manifest can get wrong.
#
# The section separates them for good, and does it by moving the NEW folder rather than the old
# bucket: ``_Unlinked/<artisanName>`` is what every zip downloaded before today already spells, and
# the artisan folder has shipped in nothing yet. The leading underscore matches ``_Detached files``
# beside it — in this tree it marks a folder the exporter invented rather than one named after a row.
UNLINKED_ARTISAN_FOLDER = "_Unlinked/_Artisans"

#: The ``media_by`` key the detached sweep parks its rows under so it can hand them to ``add_media``
#: rather than growing a second copy of the URL/presign/skip rule. ``_media_slot`` can never produce
#: it: both halves are truthy there by construction (``tag`` and ``media.linkedRecordId``, or an FK
#: value), so an empty pair collides with no real slot.
_DETACHED_SLOT = ("", "")


def _media_slot(media: Any) -> tuple[str, str] | None:
    """The one ``(record kind, record id)`` slot a media row belongs in, or None when unattached."""
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
    """A record's ``details.txt`` body, straight from the shared field registry."""
    return info_text(info_panel(kind, record))


@router.get("/dataset", response_model=None)
async def dataset_manifest(
    ownerId: str | None = None,
    stream: bool = False,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any] | StreamingResponse:
    """Build the downloadable manifest.

    Without ``ownerId`` this is the whole repository and requires the global dataset-download
    permission. With ``ownerId`` it is scoped to one researcher's data and is authorized by tiered
    data access: an all-data DOWNLOAD+ grant yields everything that owner uploaded; a subset grant
    yields only the granted records. Admins/global downloaders/the owner always get everything.

    Response: ``{files, totalFiles, totalMedia, skippedMedia, truncated}`` — ``truncated`` is true
    when any table hit its row cap OR when a media row could not be addressed at all, so the client
    can warn rather than present a partial zip as complete. ``skippedMedia`` counts the second case.

    ``stream=1`` answers the same entries as NDJSON with the four numbers in headers instead of a
    wrapper object — including ``X-Dataset-Skipped``, because ``skippedMedia`` is this route's and
    has nowhere else to go once the wrapper is gone. Opt-in, because every deployed client reads the
    JSON object; see :func:`app.api.routes.data_browser.manifest_ndjson_response` for the allocation
    failure it exists to stop.
    """
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
        # BOTH HALVES OF THE VISIBILITY ANSWER, STATED AS ONE WAVE — AND IT BUYS NO TRIP TODAY.
        # Only the ``uploadedById`` variant queries: ``records.owned_or_granted_where`` is dictionary
        # work for ``createdById`` and reads the design-workshop tag ids only for the media column,
        # and its own comment forbids the record variant from growing a lookup to match. So this is
        # one query below professor and none at Professor and above, gathered or not. It is written
        # as a pair so that the day the record half does acquire a read it joins this wave instead
        # of adding a round trip nobody notices; the six table reads below are where this route's
        # measured trips actually go.
        rec_where, media_vis = await gather_reads(
            owned_or_granted_where(current_user),
            # Media carries its own owner column, and the repository-wide download is not a licence
            # to read uploads the caller cannot see anywhere else in the app (GET /media, /search
            # and the data browser all filter on uploadedById). Empty — a no-op — for Professor and
            # above.
            owned_or_granted_where(current_user, owner_field="uploadedById"),
        )

    def _in_scope(rtype: str, rid: str) -> bool:
        return scope is None or rid in scope.get(rtype, set())

    # THE SIX TABLE READS GO OUT AS ONE WAVE. They share a WHERE and depend on nothing but it, and
    # this route MEASURED 12.98 round trips (9,105 ms) against production with them in series
    # (docs/SCALABILITY.md §1.2). Six coroutines is inside ``pool_width()`` (10), so ``gather_reads``
    # issues them together rather than falling back to its semaphore and making two waves of it.
    #
    # THE MEDIA READ IS DELIBERATELY NOT IN THIS WAVE and must not be moved into it: ``media_or``
    # below is built out of the ids these six return, so it is a genuine dependency and a second
    # wave is the correct shape, not an oversight.
    #
    # NO EXTRA MEMORY. These six lists were all held simultaneously anyway — ``truncated`` on the
    # very next line reads all six, and the manifest is assembled from all six — so gathering
    # changes when the rows arrive, not how many are resident. The row caps (``EXPORT_TAKE``,
    # ``MEDIA_TAKE``) are still the only thing bounding this route's footprint; see
    # docs/SCALABILITY.md §5.2.
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
            where=rec_where, take=EXPORT_TAKE, include=_INTERVIEW_INCLUDE, order=_EXPORT_ORDER
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
        # Subset grant: keep only the explicitly granted records of each type.
        workshops = [w for w in workshops if _in_scope("workshop", w.id)]
        artisans = [a for a in artisans if _in_scope("artisan", a.id)]
        products = [p for p in products if _in_scope("product", p.id)]
        tools = [t for t in tools if _in_scope("tool", t.id)]
        interviews = [i for i in interviews if _in_scope("questionnaire", i.id)]

    # Media is fetched BY the records that will be emitted rather than by scanning the table: only
    # attached media is ever placed in the tree, so ``where={}`` was a full-table read whose extra
    # rows were all discarded. One OR query, so a row matching several conditions is deduped by id.
    media_or: list[dict[str, Any]] = []
    if ownerId:
        # An owner's dataset must also carry media that OTHER users uploaded onto the owner's
        # in-scope records, not just media the owner uploaded themselves.
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
            # The typed FK alone misses rows attached only by the string tag pair, and the tag pair
            # alone misses rows attached only by the FK (a NULL linkedRecordType) — which is how a
            # whole class of media used to be fetched and then never emitted. Both, always.
            media_or.append(
                {"AND": [{"linkedRecordType": {"in": tags}}, {"linkedRecordId": {"in": ids}}]}
            )
    # Processes and their steps have no FK column on MediaFile; they are tag-only attachments.
    proc_ids = [p.id for p in processes]
    step_ids = [s.id for p in processes for s in (p.steps or [])]
    for tag, ids in (("process", proc_ids), ("processstep", step_ids)):
        if ids:
            media_or.append({"AND": [{"linkedRecordType": tag}, {"linkedRecordId": {"in": ids}}]})
    media: list[Any] = []
    if media_or:
        media_where: dict[str, Any] = {"OR": media_or}
        if media_vis:
            media_where = {"AND": [media_where, media_vis]}
        media = await db.mediafile.find_many(
            where=media_where, take=MEDIA_TAKE, order=_EXPORT_ORDER
        )
        truncated = truncated or len(media) >= MEDIA_TAKE

    # Group media by the single slot it belongs in.
    #
    # A ROW WITH NO SLOT IS NOT A ROW WITH NO BYTES. ``_media_slot`` returns None for a file carrying
    # neither a typed FK nor a tag pair, and until now such a row was dropped on this line — never
    # keyed, never emitted, never counted. It is reachable through the ``{"uploadedById": ownerId}``
    # arm of ``media_or`` above: an owner-scoped export is defined as "everything that owner
    # uploaded", and an upload they never attached to anything is exactly that. Kept aside here and
    # filed by the detached sweep at the bottom.
    media_by: dict[tuple[str, str], list[Any]] = {}
    slotless_media: list[Any] = []
    for m in media:
        slot = _media_slot(m)
        if slot is not None:
            media_by.setdefault(slot, []).append(m)
        else:
            slotless_media.append(m)

    files: list[dict[str, str]] = []
    # Two same-named products (or two photos with the same original filename) used to resolve to
    # the same path and silently overwrite each other when the client zipped them. Folder paths and
    # file paths are de-duplicated separately: a duplicate RECORD has to move its whole subtree
    # ("Chair (2)/..."), while a duplicate FILE only renames itself.
    used_dirs: set[str] = set()
    used_files: set[str] = set()

    # Files this manifest could not address at all: no ``url`` on the row AND no ``objectKey`` to
    # sign one from. Counted rather than passed over in silence — see ``add_media``.
    skipped_media: list[str] = []

    # Which ``media_by`` slots an emit loop actually reached. Written by ``add_media`` and read once,
    # by the detached sweep at the bottom — a slot nobody asked for is a file nobody zipped.
    placed_media_slots: set[tuple[str, str]] = set()

    def add_media(prefix: str, rtype: str, rid: str) -> None:
        placed_media_slots.add((rtype, rid))
        for m in media_by.get((rtype, rid), []):
            leaf = _seg(m.originalFilename, m.id)
            if m.url:
                files.append(
                    {
                        "path": _uniq(f"{prefix}/{leaf}", used_files),
                        "url": m.url,
                    }
                )
                continue
            # A NULL ``url`` USED TO MEAN "SILENTLY NOT IN THE ZIP", and it is not a broken row.
            # ``MediaFile.url`` is nullable and ``s3.public_url_for_key`` returns None whenever
            # neither ``AWS_S3_PUBLIC_BASE_URL`` nor ``AWS_S3_ENDPOINT`` is configured — both
            # optional — and ``complete_media_upload`` stores that None verbatim. Its comment there
            # says the download path "already handles [it] by streaming the bytes", which is true of
            # ``/data/media/{id}/download`` and was false of this manifest: the row was fetched from
            # Postgres, keyed into ``media_by``, and dropped by an ``if m.url`` with no else. The
            # researcher's zip was short by exactly those files, ``totalMedia`` counted only the
            # survivors, and ``truncated`` stayed false — so "the zip is complete" and "N files could
            # not be addressed" rendered as the same sentence.
            #
            # THE FALLBACK IS THE ONE ITS THREE SIBLINGS ALREADY HAVE: ``download_media`` streams
            # from ``objectKey``, ``datasets._presign_media_row`` signs from it, and
            # ``MediaIndex.prefetch`` fetches by it. Signing here also removes the manifest's
            # standing dependence on the bucket being anonymously readable, which is a premise this
            # module never stated and cannot check.
            #
            # A LONG EXPIRY, DELIBERATELY. The default 900s is sized for a single click; this URL
            # goes into a manifest the client works through file by file, and a repository zip of a
            # few thousand objects over a field-office connection outlives fifteen minutes easily. A
            # URL that expires mid-download produces a corrupt archive with no error, which is worse
            # than the narrower window is good. Six hours is the shape of the job, not a guess at a
            # safe number.
            #
            # ``_uniq`` IS CALLED ONLY ON THE BRANCHES THAT EMIT, because it RESERVES the name it
            # returns (lower-cased) in ``used_files``. Reserving a path for a file that is then
            # skipped would push the next genuine file with that name to "photo (2).jpg" — a
            # numbered duplicate with no original beside it, which reads as a lost file.
            #
            # WRAPPED, BECAUSE SIGNING IS THE ONE THING IN THIS FUNCTION THAT CAN RAISE. Every other
            # line here is dictionary work over rows already in memory; ``presign_get_url`` builds a
            # boto3 client and needs credentials and a region. A deployment with no object storage
            # configured at all would have turned a manifest that used to come back short into a 500
            # for the whole archive — trading a quiet defect for a loud one is not a fix. A key we
            # cannot sign is counted exactly like a key that is not there.
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
            # Neither a URL nor a signable key: nothing anywhere can fetch this row's bytes. Say so.
            #
            # ``objectKey`` is ``String @unique`` and NOT NULL on MediaFile, so in practice this line
            # is reached by the SIGNING failure above rather than by a keyless row — the falsy-key
            # test is belt and braces against a future selective read that does not load the column.
            # Either way the row is counted rather than dropped, which is the whole point: the two
            # states a researcher must be able to tell apart are "the zip is complete" and "N files
            # could not be addressed", and before this they rendered as the same sentence.
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
    # THE FOURTH SET, AND ITS ABSENCE WAS A HOLE IN THE ONE PROMISE THIS MANIFEST MAKES. Artisans are
    # emitted in exactly one place — inside ``for ws in workshops:`` — and the ``_Unlinked`` fallbacks
    # below covered products, tools and interviews and NOT artisans. So an artisan no workshop
    # reaches had no folder anywhere in the tree: no details.txt, and none of their own photographs,
    # which were nonetheless FETCHED (``media_or`` includes ``{"artisanId": {"in": ids}}`` over ALL
    # artisans), keyed into ``media_by`` and then thrown away uncounted by ``totalMedia`` and
    # unflagged by ``truncated``. Both ``workshopId`` and ``craftId`` are optional on create and
    # nullable in the schema, so such an artisan is ordinary rather than exotic — and this is the
    # SECOND time this loop has silently dropped people: see the note below about route 1 of
    # ``workshop_reaches_artisan``. That fix corrected which artisans a workshop reaches; it did not
    # give a home to artisans no workshop reaches.
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
        # An interview is identified by the artisans it covers, not its internal title — the
        # registry's title function, so the folder matches what the browser calls it.
        label = _seg(interview_label(interview), interview.id)
        base = _uniq(f"{prefix}/Questionnaires/{label}", used_dirs)
        answers = []
        for r in interview.responses or []:
            q = getattr(r, "question", None)
            prompt = getattr(q, "prompt", r.questionId) if q else r.questionId
            code = getattr(q, "sectionCode", "") if q else ""
            answers.append(f"[{code}] {prompt}\n  -> {r.answerText or ''}\n")
        add_text(base, "answers.txt", _details("interview", interview) + "\n\n" + "".join(answers))
        add_media(base, "questionnaire", interview.id)

    interviews_for_artisan: dict[str, list[Any]] = {}
    for it in interviews:
        for link in it.artisans or []:
            interviews_for_artisan.setdefault(link.artisanId, []).append(it)

    # Products and tools are filed under exactly one (workshop, artisan) folder, so index them by
    # that pair once. The reachability rule below puts far more artisans in each workshop's tree
    # than the WorkshopArtisan-only rule did, and re-scanning both 5000-row lists for every one of
    # them is a needless workshops x artisans x records sweep on a single-worker box.
    products_by_slot: dict[tuple[str, str], list[Any]] = {}
    for product in products:
        if product.workshopId and product.artisanId:
            products_by_slot.setdefault((product.workshopId, product.artisanId), []).append(product)
    tools_by_slot: dict[tuple[str, str], list[Any]] = {}
    for tool in tools:
        if tool.workshopId and tool.artisanId:
            tools_by_slot.setdefault((tool.workshopId, tool.artisanId), []).append(tool)

    for ws in workshops:
        wbase = _uniq(f"Workshops/{_seg(ws.title, ws.id)}", used_dirs)
        add_text(wbase, "details.txt", _details("workshop", ws))
        add_media(wbase, "workshop", ws.id)
        # Which artisans this workshop contains. A workshop reaches its artisans three ways —
        # WorkshopArtisan, Artisan.workshopId, and WorkshopCraft -> Artisan.craftId — and this
        # counted route 1 alone. On the live repository route 1 is EMPTY (artisans reach their
        # workshop through their craft), so every artisan the browser lists under this workshop was
        # silently absent from the zip: no details.txt, none of their own photographs or clips, and
        # their products and tools demoted to _Unlinked. workshop_reaches_artisan is the browser's
        # own predicate (data_browser._linked_artisans runs it as a query), evaluated over the
        # artisan rows already loaded, so the two endpoints cannot answer this question differently.
        ws_artisans = [a for a in artisans if workshop_reaches_artisan(ws, a)]

        # One folder per craft, built the way the browser's _craft_folder_entries builds them: the
        # crafts the workshop declares, unioned with the crafts of the artisans it reaches. An
        # artisan with no craft lands in "No craft" rather than the old "_OtherCrafts" lump, which
        # also swallowed artisans whose craft simply was not on the workshop's declared list.
        craft_names: dict[str, str] = {}
        for link in ws.crafts or []:
            if link.craftId:
                craft = getattr(link, "craft", None)
                craft_names.setdefault(
                    link.craftId, _seg(getattr(craft, "name", None), link.craftId)
                )
        # Declared crafts first (so the zip's folder order matches the workshop's own list), then
        # any further craft an artisan brought with them.
        buckets: dict[str | None, list[Any]] = {craft_id: [] for craft_id in craft_names}
        for artisan in ws_artisans:
            if artisan.craftId and artisan.craftId not in craft_names:
                craft = getattr(artisan, "craft", None)
                craft_names[artisan.craftId] = _seg(getattr(craft, "name", None), artisan.craftId)
            buckets.setdefault(artisan.craftId, []).append(artisan)

        for craft_id, bucket in buckets.items():
            if not bucket:
                continue  # a declared craft no visible artisan practises contributes no files
            cbase = _uniq(f"{wbase}/{craft_names.get(craft_id) or NO_CRAFT_FOLDER}", used_dirs)
            for artisan in bucket:
                placed_artisans.add(artisan.id)
                abase = _uniq(f"{cbase}/{_seg(artisan.name, artisan.id)}", used_dirs)
                add_text(abase, "details.txt", _details("artisan", artisan))
                add_media(abase, "artisan", artisan.id)
                for product in products_by_slot.get((ws.id, artisan.id), []):
                    emit_product(abase, product)
                for tool in tools_by_slot.get((ws.id, artisan.id), []):
                    emit_tool(abase, tool)
                for it in interviews_for_artisan.get(artisan.id, []):
                    emit_interview(abase, it)

    # Anything not attached to a workshop goes here so nothing is lost.
    #
    # ARTISANS FIRST, AND THE ORDER IS LOAD-BEARING. An unlinked artisan is emitted with the same
    # shape as a linked one — details.txt, their own media, then their products, tools and interviews
    # nested beneath through the very same ``emit_*`` helpers — so an archivist opening the zip finds
    # one subtree per person wherever that person sits. Running this pass BEFORE the three flat
    # fallbacks is what puts those records under the artisan they belong to: ``emit_product`` and its
    # siblings record their ids in ``placed_*``, so the loops below skip what has just been nested and
    # a product is never written twice. Reversing these four blocks would scatter one artisan's
    # records across ``_Unlinked/<artisanName>`` buckets with their owner's folder empty beside them.
    #
    # ``_uniq`` on the artisan folder, unlike the flat buckets below, because two DIFFERENT unlinked
    # artisans can share a name and must not have their details.txt and photographs merged into one
    # folder — the folder is a record here, not a grouping label.
    #
    # AND IT SITS IN ``UNLINKED_ARTISAN_FOLDER`` RATHER THAN DIRECTLY UNDER ``_Unlinked`` FOR THE
    # SAME REASON, one level up: ``_uniq`` only keeps this pass's own folders apart, and the flat
    # buckets below never go through it, so while both spellings were ``_Unlinked/<name>`` a stray
    # record carrying a matching ``artisanName`` string was emitted straight into this person's
    # subtree. See the constant for the full argument — the short version is that a grouping label
    # and a person must not be able to resolve to one path.
    products_by_artisan: dict[str, list[Any]] = {}
    for product in products:
        if product.artisanId:
            products_by_artisan.setdefault(product.artisanId, []).append(product)
    tools_by_artisan: dict[str, list[Any]] = {}
    for tool in tools:
        if tool.artisanId:
            tools_by_artisan.setdefault(tool.artisanId, []).append(tool)
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

    # The remaining three, for records whose artisan IS filed under a workshop (or who name no
    # artisan at all) but which are themselves attached to none. The per-artisan grouping prefix is
    # deliberately NOT uniqued — every record of one artisan belongs in the same folder.
    for product in products:
        if product.id not in placed_products:
            emit_product(f"_Unlinked/{_seg(product.artisanName, 'artisan')}", product)
    for tool in tools:
        if tool.id not in placed_tools:
            emit_tool(f"_Unlinked/{_seg(tool.artisanName, 'artisan')}", tool)
    for it in interviews:
        if it.id not in placed_interviews:
            emit_interview("_Unlinked", it)

    # THE LAST SWEEP, AND IT IS THE MEDIA HALF OF THE PROMISE THIS MODULE'S HEADER MAKES.
    #
    # Every emit loop above asks ``media_by`` for the slot of a record it is writing. Nothing asked
    # the opposite question — which slots were never claimed — and the answer was not empty. A media
    # row is keyed by ``_media_slot`` at the id its tag or FK NAMES, not at an id that still exists,
    # so a photograph whose parent has died is fetched from Postgres, keyed at ``("processstep",
    # dead_id)``, and then discarded because ``add_media`` is only ever called with LIVE ids. That is
    # not exotic: ``processes._sync_steps`` hard-deletes every ProcessStep the form did not re-send,
    # so a researcher who opens a process, drops one duplicated step and saves has just detached that
    # step's photographs — no delete of anything else required. The rows keep the ``workshopId`` they
    # inherited at upload, which is precisely why the ``{"workshopId": {"in": ids}}`` arm of
    # ``media_or`` keeps fetching them, and why the waste was invisible: they arrived in memory and
    # left in silence, uncounted by ``totalMedia`` and unflagged by ``truncated``.
    #
    # THIS IS THE SAME DEFECT AS THE UNPLACED ARTISAN ABOVE, one record type down. Both are a loop
    # that emits only what a parent reaches, under a header sentence promising "an _Unlinked area so
    # nothing is dropped". Fixing one shape and not the other is how this module has now been burned
    # three times; if a fourth kind of leaf is ever added, give it a ``placed_*`` set and let this
    # sweep be the thing that proves nothing fell out.
    #
    # THEY ARE EMITTED, NOT MERELY COUNTED. The bytes are in the bucket and the researcher's zip is
    # the archival copy; a folder saying "these files exist and their parent record does not" is a
    # recoverable state, and a file that is simply absent is not. ``_Unlinked/_Detached files`` names
    # exactly that, beside the ``_Unlinked`` folders an archivist is already reading. The recovery
    # path for re-attaching them is ``GET /media/orphans`` plus ``POST /media/{id}/relink``, which
    # now understand tag-only links; this is the export's side of that same repair.
    #
    # ``add_media`` IS REUSED THROUGH A SENTINEL SLOT RATHER THAN RE-SPELLED. Everything that decides
    # how a row becomes a manifest entry — the url, the presigned fallback, the six-hour expiry, the
    # skip counter, the ``_uniq`` reservation discipline — lives in that closure, and a second copy
    # of it here is exactly how the null-url drop it was written to close got written in the first
    # place.
    #
    # NOT RE-SORTED, AND THAT IS THE STABLE CHOICE RATHER THAN THE LAZY ONE. ``media`` was read under
    # ``_EXPORT_ORDER`` and both ``slotless_media`` and each ``media_by`` bucket were appended to in
    # that order, and dicts preserve insertion order — so this folder's contents are already fixed by
    # the same total order the caps depend on, and two downloads a week apart agree. Sorting again
    # here on ``createdAt`` would only re-derive that, and would do it against a column a selective
    # read is not obliged to load.
    #
    # NOT UNDER A SUBSET GRANT, AND THIS CONDITION IS AN ENTITLEMENT CONTROL RATHER THAN A TASTE.
    # On the ``ownerId`` path ``media_vis`` is empty and the media read is widened by
    # ``{"uploadedById": ownerId}`` — EVERY file that owner uploaded, whatever it hangs off. What
    # narrows it back down for a subset grantee is precisely the mechanism this sweep undoes: the
    # files of records outside the grant land in slots no emit loop claims, and are dropped. So on a
    # subset grant an unclaimed slot does not mean "the parent is gone", it means "the parent is not
    # yours", and sweeping those into ``_Unlinked/_Detached files`` would hand a grantee holding two
    # of a researcher's fifty artisans the photography of the other forty-eight.
    #
    # ``scope is None`` is the whole repository export (where ``media_vis`` has already row-gated the
    # media by uploader, so every row in hand is one the caller may take) or an all-data owner export
    # (where every file that owner uploaded belongs in the researcher's dataset by definition) —
    # ``owner_download_scope`` returns None for exactly those, which is why ``_in_scope`` reads the
    # same way. Under a subset grant the detached rows stay dropped and this defect stays open for
    # that one caller; closing it there needs the media read narrowed to the granted records rather
    # than to the owner, which is a change to what ``media_or`` asks for and not to what this sweep
    # does with the answer.
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
    # ``skippedMedia`` IS OR-ED INTO ``truncated`` RATHER THAN REPORTED ALONE. ``truncated``'s
    # documented job (see EXPORT_TAKE above) is "so the client can say so instead of quietly handing
    # over a partial dataset", and a zip short by an unaddressable file is exactly as partial as one
    # short by a capped row — the client's existing warning is already the right sentence. The count
    # travels beside it so a researcher can tell the two states apart: which rows were left out is a
    # different conversation from how many.
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
    """CSV exports are full-dataset downloads — gate them exactly like /export/dataset."""
    if not can_download_dataset(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Dataset download access required to export CSVs. Ask an admin to grant the "
            "dataset-download permission.",
        )


# createdBy feeds the "Created by" provenance column the registry appends to every row; media and
# workshop feed the media columns and the row's workshop title.
_CSV_INCLUDE = {"media": True, "createdBy": True, "workshop": True}


@router.get("/products.csv")
async def export_products(current_user: Any = Depends(get_current_user)) -> Response:
    _require_dataset_download(current_user)
    records = await db.productdocumentation.find_many(
        where=await owned_or_granted_where(current_user),
        include=_CSV_INCLUDE,
        take=EXPORT_TAKE,
        order={"createdAt": "desc"},
    )
    body = records_to_csv("product", records, truncated=len(records) >= EXPORT_TAKE)
    return csv_response("products.csv", body)


@router.get("/tools.csv")
async def export_tools(current_user: Any = Depends(get_current_user)) -> Response:
    _require_dataset_download(current_user)
    records = await db.tooldocumentation.find_many(
        where=await owned_or_granted_where(current_user),
        include=_CSV_INCLUDE,
        take=EXPORT_TAKE,
        order={"createdAt": "desc"},
    )
    body = records_to_csv("tool", records, truncated=len(records) >= EXPORT_TAKE)
    return csv_response("tools.csv", body)
