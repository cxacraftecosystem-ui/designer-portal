from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import (
    assert_can_contribute_relation,
    assert_can_delete,
    enum_or_raw,
    get_current_user,
    require_record_creator,
)
from app.schemas.records import ProcessCreate, ProcessStepInput, ProcessUpdate
from app.services.access import guard_record_edit, record_revision
from app.services.pagination import normalize_pagination, page_payload
from app.services.record_design_workshop import assert_payload_workshop
from app.services.records import (
    RECORD_STATUSES,
    Relation,
    add_date_range,
    apply_status_policy_create,
    apply_status_policy_update,
    assert_expected_updated_at,
    clean_data,
    client_key_replay,
    client_key_replay_after_violation,
    contains,
    count_and_page,
    enum_filter_or_422,
    hydrate_relations,
    media_url_owners,
    merge_field_provenance,
    prose_contains,
    public_encode,
    require_record,
    resubmit_status,
    take_expected_updated_at,
    viewable_where,
)
from app.services.workshop_access import (
    enforce_workshop_submission,
    pin_pending_if_late,
    stamp_workshop_submission,
)

router = APIRouter(prefix="/processes", tags=["processes"])

# What a process carries on the wire, loaded in one parallel wave (see services/records.py for why).
# The write paths hydrate the row they saved rather than passing an ``include`` — steps are written
# after the process row exists, so there is nothing for a create-time include to load anyway.
RELATIONS = (
    Relation("product", "productdocumentation", "productId"),
    Relation("createdBy", "user", "createdById"),
    Relation("steps", "processstep", "processId", many=True),
    Relation("workshop", "workshop", "workshopId"),
)

# PROCESS'S OWN NULLABLE SCALARS — the names ``clean_data`` must let an explicit ``null`` through for
# on this model, so emptying the notes box on the process form actually empties the column instead of
# answering 200 and keeping the old text.
#
# It holds exactly one name, and it is a module constant anyway rather than a tuple written inline at
# the call: that is what lets a test read the list off the route instead of retyping it, which is the
# difference between pinning what this route declares and pinning what the test itself believes.
# The three sibling record routes each expose the same constant for the same reason.
#
# PER-MODEL AND NOT GLOBAL, for the reason ``clean_data``'s ``clearable`` docstring gives. Only valid
# because ``update_process`` dumps with ``exclude_unset=True``; see the note at that call.
#
# DELIBERATELY ABSENT: ``name``, ``productId``, ``preProcessAvailable``, ``status``, ``recordedAt``
# and ``recordedTimezone`` are NOT NULL; ``workshopId`` is already global; ``extraMetadata`` would be
# inert because ``merge_field_provenance`` reassigns (or pops) that key further down this route, so a
# null could never reach Prisma through it; and ``steps`` is a relation with its own guard and its own
# audit row, excluded from the dump entirely.
#
# ``productId`` IS THE ONE NAME LEAVING IT OUT DOES NOT ACTUALLY KEEP OUT, and that is worth knowing
# before you read the route. It sits in the GLOBAL ``records.CLEARABLE_KEYS`` — correctly, because it
# is a nullable back-reference on the models that merely POINT AT a product — so ``clean_data`` keeps
# an explicit null for it here too, and no per-model tuple can subtract from the global set. On
# ``Process`` the column is NOT NULL, so ``update_process`` refuses that null outright; see the
# branch beside ``require_record``.
_CLEARABLE_COLUMNS = ("notes",)


def _encode_light(process: Any, viewer: Any) -> dict[str, Any]:
    """Encode a process with its steps sorted, without the (heavier) media hydration.

    THE VIEWER IS A PARAMETER RATHER THAN AN OMISSION, and the omission was a real defect: every one
    of these routes used to call ``public_encode(process)`` with no caller at all. ``public_encode``
    reads "no viewer named" as the cheapest safe answer — mask every identity number and withhold
    every media URL, from a MASTER_ADMIN as readily as from a stranger — because that is the answer a
    route reaches by not thinking about it. A process detail is exactly the surface that has to think
    about it: it is the ONLY place the web can get the photographs of a process's steps
    (``ProcessForm`` seeds each step's ``existingMedia`` from ``step.media`` and never re-fetches
    through ``GET /media``), so a URL dropped here is a photograph that no page can open, for the
    person who uploaded it seconds earlier.

    Naming the viewer also lifts the identity mask for the ranks entitled to it (professor and above,
    or the researcher who recorded that particular artisan). That is the SAME policy artisans.py,
    media.py and search.py already apply on their own reads — products, tools and processes were the
    outliers that masked their own author's data back at them.

    No ``media_urls`` argument, and that is deliberate: ``RELATIONS`` declares no media, so the only
    URL decision on this payload would be about nodes that are not in it. The process's real media is
    encoded in ``_hydrate``, which is where the grant set is resolved and spent.
    """
    encoded = public_encode(process, viewer)
    encoded["steps"] = sorted(encoded.get("steps") or [], key=lambda s: s.get("sortOrder", 0))
    return encoded


async def _hydrate(process: Any, viewer: Any) -> dict[str, Any]:
    """Full detail: steps in order, plus media attached to the process and each step.

    Media is linked purely through ``linkedRecordType``/``linkedRecordId`` (``process`` for the
    pre-process clips, ``processstep`` for each step) so no MediaFile foreign keys are needed.

    THE GRANT SET IS RESOLVED HERE AND NOWHERE ELSE in this module, because this is the only encode
    that carries a media node. ``media_url_owners`` is one query and only below professor (it returns
    "all allowed" for professor and above without touching the database), and it is what lets a
    colleague who holds a data-access grant actually OPEN the photograph of a step somebody else
    photographed — the cheap ``viewer``-derived default would hand them the row and withhold the file.

    THE UPLOADER HALF ALONE, AND THIS ROUTE HAS TO ARGUE IT DIFFERENTLY FROM ITS SIBLINGS.
    ``records.media_url_scope`` answers "whose media bytes may travel" in two halves — the uploaders,
    and the design workshops this account may open, whose files are entitled to by the TAG
    ``linkedRecordType="designWorkshop"`` plus the workshop id (``dictation_consent.MEDIA_TAG``). The
    call below asks for the uploader half only and leaves ``public_encode``'s ``media_workshops`` at
    its empty default. That is a DECISION and not an omission; the banner in ``records.py`` records
    what omission cost the transcripts surface once.

    products.py and tools.py settle the same question by their FOREIGN KEY — a row reaches them
    through ``MediaFile.productId``/``toolId``, which ``records.media_relation_data`` writes FROM the
    link type, so the tag on anything they return is the parent's own. **THIS QUERY HAS NO SUCH
    NARROWING AND A READER MUST NOT ASSUME IT DOES**: the statement below filters on ``linkedRecordId``
    ALONE, with no ``linkedRecordType`` clause at all, because a process and its steps are linked
    purely by the tag pair and the ids are already unique. What keeps a workshop attachment out is
    therefore the ID and nothing else — ``lookup_ids`` holds this process's id and its steps', both
    ``@default(cuid())`` on their own tables, and a design-workshop upload carries the
    ``DesignWorkshop``'s id in that column. Two cuids from two tables do not collide, so no row this
    query can return carries the workshop tag, and ``media_workshops`` would be a set nothing here
    could ever be tested against — bought with a second round trip. (That was ~750ms on the
    cross-region link this was written against; co-located and ~1-2ms since 2026-09-02,
    ``services/concurrency.py``. The number moved; "a query issued to build a set nothing can be in"
    is the wrong trade at any latency.)

    THAT IS A NARROWER GUARANTEE THAN THE SIBLINGS' AND IT IS WORTH KNOWING WHICH ONE YOU ARE HOLDING.
    It rests on the id, so it survives an unrelated tag being added to MediaFile — but if this
    ``where`` ever grows a ``linkedRecordType`` clause, or ``lookup_ids`` ever admits an id from
    another table, re-derive it before trusting it. Compare ``search.py``, which reads the
    ``MediaFile`` table itself and does need the second half.
    """
    encoded = _encode_light(process, viewer)
    step_ids = [s["id"] for s in encoded["steps"]]
    lookup_ids = [process.id, *step_ids]
    media = await db.mediafile.find_many(
        where={"linkedRecordId": {"in": lookup_ids}},
        order={"createdAt": "asc"},
    )
    # The uploader half only; ``media_workshops`` stays at its empty default by decision — see "THE
    # UPLOADER HALF ALONE" in the docstring for why no row this query returns can carry the tag.
    media_encoded = public_encode(media, viewer, media_urls=await media_url_owners(viewer))
    by_record: dict[str, list[dict[str, Any]]] = {}
    for item in media_encoded:
        by_record.setdefault(item.get("linkedRecordId"), []).append(item)
    encoded["media"] = by_record.get(process.id, [])
    for step in encoded["steps"]:
        step["media"] = by_record.get(step["id"], [])
    return encoded


@dataclass
class _StepPlan:
    """What a submitted step list would DO to the steps already stored, decided before anything runs.

    The upsert used to decide and write in one pass (``_sync_steps``). It is split in two so the
    authorization check in ``update_process`` can ask the one question that matters — "does this write
    touch a step somebody else already recorded?" — of the very computation that performs the write.
    Any other arrangement lets the guard and the writer drift apart, and a guard that disagrees with
    the writer is worse than no guard, because it reads as protection.
    """

    to_create: list[dict[str, Any]] = field(default_factory=list)
    to_update: list[tuple[str, dict[str, Any]]] = field(default_factory=list)
    removed: list[str] = field(default_factory=list)

    @property
    def touches_existing(self) -> bool:
        """True when the write would CHANGE or DELETE a step that is already stored.

        Adding a step is deliberately NOT "touching": the repository's contribution rule
        (``assert_can_contribute_fields``) is that an empty field may be filled by anyone and a
        populated one is locked, and a step that does not exist yet is the empty field. Making a bare
        ``count() > 0`` the test instead would have been simpler and wrong in a way that breaks the
        product: ``ProcessForm`` re-sends the WHOLE step list on every save, so a contributor filling
        one empty ``notes`` box on a process that happens to have steps would be answered 403 for a
        step list they never edited.
        """
        return bool(self.to_update or self.removed)

    @property
    def writes_anything(self) -> bool:
        return bool(self.to_create or self.to_update or self.removed)


def _plan_steps(process_id: str, existing: list[Any], steps: list[ProcessStepInput]) -> _StepPlan:
    """Decide the creates, updates and deletions for a submitted step list. Writes nothing.

    Batched rather than one statement per step. A process form re-sends its whole step list on every
    save, so an eight-step process cost eight sequential writes plus a delete each for anything
    removed — every one of them its own round trip. Now the new steps go in with a single insert,
    the removed ones leave with a single delete, and the only per-step writes left are the steps
    whose content actually changed, which on a typical save is one or none.

    THE ROUND TRIPS WERE CROSS-REGION WHEN THAT WAS WRITTEN AND ARE CO-LOCATED SINCE 2026-09-02
    (~1-2ms — ``services/concurrency.py``), SO THE SECOND HALF OF THE ARGUMENT NOW CARRIES IT. These
    are WRITES, and since 2026-09-03 they run inside ``update_process``'s transaction: the statement
    count is no longer only wall-clock, it is how long that block holds a connection and how many
    chances it has to outlive its timeout. Sixteen statements to save eight steps is worse there than
    it ever was on the wire.

    New steps need no ids read back: a freshly inserted step can never be in the previously-existing
    set, so what survives the edit is decided entirely by the ids the caller sent.
    """
    by_id = {step.id: step for step in existing}
    keep: set[str] = set()
    plan = _StepPlan()
    for index, step in enumerate(steps):
        order = step.sortOrder or index + 1
        notes = (step.notes or "").strip() or None
        current = by_id.get(step.id) if step.id else None
        if current is not None:
            keep.add(step.id)
            unchanged = (
                current.name == step.name
                and str(enum_or_raw(current.stepType)) == str(step.stepType)
                and current.sortOrder == order
                and current.notes == notes
            )
            if not unchanged:
                plan.to_update.append(
                    (
                        step.id,
                        {
                            "name": step.name,
                            "stepType": step.stepType,
                            "sortOrder": order,
                            "notes": notes,
                        },
                    )
                )
            continue
        plan.to_create.append(
            {
                "processId": process_id,
                "name": step.name,
                "stepType": step.stepType,
                "sortOrder": order,
                "notes": notes,
            }
        )
    plan.removed.extend(step.id for step in existing if step.id not in keep)
    return plan


async def _apply_steps(plan: _StepPlan, *, client: Any = None) -> None:
    """Execute a plan: one insert, one update per genuinely changed step, one delete.

    ``client`` IS THE CALLER'S TRANSACTION AND THE PATCH PATH ALWAYS PASSES IT (2026-09-03). The three
    statements below are not one write: a plan that both deletes a step and inserts a replacement
    used to commit the delete first, so a failure between them destroyed a documented step and its
    photograph links and put nothing back — and the RecordRevision the caller writes AFTER this call
    would then be missing too, leaving the loss with no audit row to reconstruct it from. Prisma's
    ``db.tx()`` hands back a DIFFERENT client, so the transaction cannot be discovered from in here
    and has to arrive as an argument. ``create_process`` deliberately passes nothing: it applies a
    plan against a row inserted one statement earlier, whose step list is empty by construction, so
    there is no existing step for a half-applied plan to destroy.
    """
    writer = db if client is None else client
    if plan.to_create:
        await writer.processstep.create_many(data=plan.to_create)
    for step_id, data in plan.to_update:
        await writer.processstep.update(where={"id": step_id}, data=data)
    if plan.removed:
        await writer.processstep.delete_many(where={"id": {"in": plan.removed}})


def _step_digest(steps: list[Any]) -> list[dict[str, Any]]:
    """The step list as it goes into a RecordRevision: everything needed to put it back.

    Ids included, because the point of the audit row is that an admin can reconstruct WHICH steps
    were there — a list of names cannot be matched back to the photographs that hung off the deleted
    ``processstep`` rows.
    """
    return [
        {
            "id": step.id,
            "name": step.name,
            "stepType": str(enum_or_raw(step.stepType)),
            "sortOrder": step.sortOrder,
            "notes": step.notes,
        }
        for step in sorted(steps, key=lambda s: (s.sortOrder or 0, s.id))
    ]


@router.get("")
async def list_processes(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    productId: str | None = None,
    craftId: str | None = None,
    artisanId: str | None = None,
    workshopId: str | None = None,
    designWorkshopId: str | None = None,
    statusFilter: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    # WHOSE RECORDS. Reading is open to every signed-in account, so "the records I filed" is no
    # longer a side effect of the visibility filter and has to be asked for. Without this the
    # My Activity page had to fetch page 1 of the WHOLE repository and sift it client-side, which
    # silently under-reported the moment the repository outgrew one page.
    createdBy: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    # OR-bearing conditions are collected here and combined under a single top-level "AND" so the
    # free-text search OR and the workshop OR can never overwrite one another — nor the row-visibility
    # filter, which joins the same AND.
    and_filters: list[dict[str, Any]] = []
    vis = await viewable_where(current_user)
    if vis:
        and_filters.append(vis)
    if search:
        # ``notes`` is the process's narrative column and can now hold a rich-text document, so it
        # needs the search helper that knows about the stored shape; ``name`` is single-line.
        where["OR"] = [{"name": contains(search)}, prose_contains("notes", search)]
    if productId:
        where["productId"] = productId
    # The craft/artisan funnel narrows processes through their parent product.
    product_is: dict[str, Any] = {}
    if craftId:
        product_is["craftId"] = craftId
    if artisanId:
        product_is["artisanId"] = artisanId
    if product_is:
        where["product"] = {"is": product_is}
    if designWorkshopId:
        # The design & prototype workshop filter — a plain equality on the column. See
        # `api/routes/artisans.list_artisans` for why it is not an OR and why the reserved word
        # "none" is not accepted on a singular filter.
        where["designWorkshopId"] = designWorkshopId
    if workshopId:
        # Either reading counts: the process's own workshopId column, or its parent product's — which
        # is how every process recorded before the column got its workshop.
        and_filters.append(
            {
                "OR": [
                    {"workshopId": workshopId},
                    {"product": {"is": {"workshopId": workshopId}}},
                ]
            }
        )
    if statusFilter:
        where["status"] = enum_filter_or_422(statusFilter, RECORD_STATUSES)
    if createdBy:
        where["createdById"] = createdBy
    if and_filters:
        where["AND"] = and_filters
    add_date_range(where, "createdAt", dateFrom, dateTo)
    total, items = await count_and_page(
        db.process,
        where=where,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
        relations=RELATIONS,
    )
    # The viewer is named — see ``_encode_light``. No grant lookup here: ``RELATIONS`` carries no
    # media, so the list has no URL to withhold or hand over, and paying ``media_url_owners``'s query
    # on the widest read in this module to decide nothing would be a cost with no reader.
    return page_payload(
        [_encode_light(item, current_user) for item in items], total, page, page_size
    )


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_process(
    payload: ProcessCreate,
    current_user: Any = Depends(require_record_creator),
) -> dict[str, Any]:
    # ── THE IDEMPOTENT REPLAY, AND ON THIS MODEL IT GUARDS CHILDREN AS WELL AS THE ROW ──────────
    #
    # ``products.create_product`` carries the shared argument for the ordering. What is particular to
    # a process is what a second landing used to cost: this route writes ``ProcessStep`` rows AFTER
    # the row itself, so a replayed create produced a second process carrying a second full copy of
    # every step of a making sequence. Answering from the stored row is also the only way to write no
    # steps — ``_apply_steps`` below is reached only past this branch.
    #
    # THE STEPS ARE HYDRATED RATHER THAN PLANNED, so the answer carries the ids the FIRST landing
    # created. That is load-bearing for the caller and not cosmetic: both outboxes read
    # ``steps[].id`` off this response to attach per-step photographs (``createdStepIds`` on the web,
    # ``CreatedRecord.stepIds`` on Android, resolved by ``linkTargetFor``). A replay that answered
    # with no steps would leave every queued step capture with nowhere to attach.
    replayed = await client_key_replay(db.process, payload.clientKey, user_id=current_user.id)
    if replayed is not None:
        await hydrate_relations([replayed], RELATIONS)
        return await _hydrate(replayed, current_user)
    await require_record(db.productdocumentation, payload.productId)
    data = clean_data(payload.model_dump(exclude={"steps"}))
    # Workshop entries: enforce assignment, then flag + pin a late submission for admin approval.
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    # THE DESIGN & PROTOTYPE WORKSHOP is a DIFFERENT SCOPE with different machinery, so it needs
    # its own gate beside the line above rather than instead of it: `workshopId` is
    # `WorkshopAssignment`, `designWorkshopId` is creator / admin / `DesignWorkshopViewer`.
    # `assert_payload_workshop` calls `load_workshop_or_404(for_edit=True)` — the same helper the
    # stage writes and the questionnaire attach use — because filing a record under a workshop
    # puts it inside that workshop's scoped lists and totals, which is a change to somebody
    # else's record. Ungated, any client could post a stranger's workshop id and file into it,
    # which is the hole `_require_attachable_workshop` was written to close one door over.
    await assert_payload_workshop(data, current_user)
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    # After the status policy, so a late submission outranks the submitter's own approval rights.
    pin_pending_if_late(data, current_user, check=check)
    try:
        created = await db.process.create(data=data)
    except Exception as exc:
        # The race the pre-read cannot settle. On this model it is the one that would hurt most —
        # the loser would go on to write a whole second copy of the step list against a second row —
        # so the answer is the WINNER's row, hydrated, and ``_apply_steps`` below is never reached.
        raced = await client_key_replay_after_violation(
            db.process, payload.clientKey, exc, user_id=current_user.id
        )
        if raced is None:
            raise
        await hydrate_relations([raced], RELATIONS)
        return await _hydrate(raced, current_user)
    # A row that was created one statement ago has no steps, so the plan is read off an empty list
    # rather than off a query whose answer is known — and no authorization question arises, because
    # there is nothing of anybody else's here to touch.
    await _apply_steps(_plan_steps(created.id, [], payload.steps))
    # The steps were written after the row, so they are loaded here rather than on the create — and
    # hydrating the row we already hold saves reading it back a second time from another region.
    await hydrate_relations([created], RELATIONS)
    return await _hydrate(created, current_user)


@router.get("/{process_id}")
async def get_process(
    process_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    process = await require_record(db.process, process_id)
    await hydrate_relations([process], RELATIONS)
    return await _hydrate(process, current_user)


@router.patch("/{process_id}")
async def update_process(
    process_id: str,
    payload: ProcessUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    process = await require_record(db.process, process_id)
    # ``notes`` is the ONLY nullable scalar ``Process`` has that a client may set; the list and the
    # reasoning for what is left out of it are at ``_CLEARABLE_COLUMNS`` above.
    #
    # ``exclude_unset=True`` IS THE PRECONDITION OF ``clearable``, not a stylistic choice: it is what
    # makes a present key mean "the caller sent this". Drop it and every optional the client left
    # alone would arrive as ``None`` and be written as an explicit NULL over stored data.
    data = clean_data(
        payload.model_dump(exclude_unset=True, exclude={"steps"}), clearable=_CLEARABLE_COLUMNS
    )
    # The precondition is a question, not a column — taken out of the body here, asked inside the
    # transaction below. See ``records.take_expected_updated_at``.
    expected_updated_at = take_expected_updated_at(data)
    if "productId" in data:
        # AN EXPLICIT ``null`` IS REFUSED HERE RATHER THAN FORWARDED. ``Process.productId`` is NOT
        # NULL — a process is documentation OF a product and cannot be orphaned — but the name is in
        # the global ``records.CLEARABLE_KEYS``, which a per-model ``clearable`` tuple can add to and
        # never subtract from, so the null survives the clean on this route as well. Until this
        # branch existed it fell straight into ``require_record(db.productdocumentation, None)`` — a
        # lookup for a product with no id, whose best case is a 404 blaming a product for not
        # existing when the real fault is that the caller asked to clear a column the model forbids
        # clearing, and whose worst case is a NOT NULL violation on the update below. 422 says what
        # actually happened.
        if data["productId"] is None:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    "A process must belong to a product. Send another product's id to move it, or "
                    "delete the process."
                ),
            )
        await require_record(db.productdocumentation, data["productId"])
    # Moving a record into (or between) workshops is a workshop submission too, so the create-time
    # guard can't be bypassed by PATCHing the workshop in afterwards.
    check = None
    if "workshopId" in data and data.get("workshopId") != process.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    # Same gate on the PATCH, so the create-time check cannot be bypassed by filing the record
    # afterwards. Keyed on PRESENCE, so an edit that does not mention the workshop is not
    # re-validated — a record filed under a workshop the designer was later removed from must
    # still be editable by them.
    await assert_payload_workshop(data, current_user)
    # THE STEP LIST IS A RELATION AND IS GUARDED LIKE ONE, BEFORE ANYTHING IS WRITTEN — AND THE
    # AUDIT ROW COUNTS AS SOMETHING WRITTEN. Until this block existed, any signed-in account — not
    # the creator, not an admin, not a grantee, any account that could log in — could send
    # ``{"steps": []}`` and have every documented step of any process in the repository deleted,
    # answered 200, with no RecordRevision to reconstruct it from. It was reachable without crafting
    # a request: ``ProcessForm`` re-sends the whole payload on save, so a researcher who did not
    # create the process could open it, delete a step and save, and every scalar field would match
    # the stored row so the field guard passed on an empty ``data``.
    #
    # Modelled on ``workshops.update_workshop``, which counts the existing links and calls
    # ``assert_can_contribute_relation`` before ``replace_workshop_artisans``. The difference is the
    # predicate: a workshop's roster is replaced wholesale, whereas a process form re-sends its step
    # list on EVERY save, so "are there any steps" would refuse an ordinary contribution to an
    # untouched list. ``touches_existing`` asks the narrower and truer question — see ``_StepPlan``.
    #
    # PLANNED AND REFUSED AHEAD OF EVERY WRITE, following the rule ``tools.assign_tool_artisans``
    # states out loud: a rejected request must leave no partial state behind. This block used to sit
    # BELOW ``guard_record_edit``, which was far enough up to spare the row and not far enough to
    # spare the ledger: ``guard_record_edit`` ends in ``record_revision``, so a payload mixing one
    # legal field fill with one illegal step deletion answered 403 with the row untouched AND a
    # committed RecordRevision saying the field had been changed. An audit trail that records edits
    # which did not happen is worse than one gap in it — it is read by an admin reconstructing who
    # did what, and it would name an innocent value as the current one and a refused caller as its
    # author. Nothing between here and the row update can refuse: ``apply_status_policy_update``
    # silently DROPS an unauthorised status rather than raising (see its docstring), and
    # ``stamp_workshop_submission``/``pin_pending_if_late``/``resubmit_status``/``merge_field_provenance``
    # only mutate ``data``. If you add a call below that can raise, move it above this block.
    #
    # ── THE OTHER HALF OF THE SAME DEFECT IS CLOSED TOO, AS OF 2026-09-03 ──────────────────────────
    #
    # Everything above is about a REFUSAL landing between the ledger and the row. The mirror case is
    # a FAILURE landing there — P2024 on a cross-region connection pool, a dropped socket, a
    # constraint nothing pre-empted — and no amount of reordering could reach it, because the two
    # writes were two separate commits in a row and the window between them is not code. The block
    # below is now ONE ``db.tx()`` spanning ``guard_record_edit``, the row update, the step plan and
    # the steps' own RecordRevision, so all four land together or none of them do. Both halves of the
    # rule "the ledger never records an edit that did not happen" are now enforced rather than one.
    #
    # THIS PROBE STAYS OUTSIDE THE TRANSACTION AND IT IS NOT AN OVERSIGHT. It is handed ``{}``, so by
    # the paragraph below it writes nothing at all — ``record_revision`` finds no changed field and
    # issues no statement — and it exists precisely to refuse BEFORE any write. Opening a transaction
    # around a call whose whole purpose is to answer without writing would add a round trip to
    # produce the same 403.
    existing_steps: list[Any] = []
    plan: _StepPlan | None = None
    if payload.steps is not None:
        existing_steps = await db.processstep.find_many(where={"processId": process_id})
        plan = _plan_steps(process_id, existing_steps, payload.steps)
        # THE PRIVILEGE QUESTION, ASKED WITH AN EMPTY PAYLOAD ON PURPOSE. The relation guard needs
        # the same verdict ``guard_record_edit`` computes — admin, creator, a professor outranking
        # the author, or an EDIT-tier grantee — and asking any other way here would be a second
        # authorization rule to keep in step with the first. Handing it ``{}`` makes the call pure:
        # ``assert_can_contribute_fields`` iterates an empty dict and can refuse nothing, and
        # ``record_revision`` finds no changed field and writes no row. It costs at most the two
        # grant/rank lookups again, and only on a PATCH that carries steps from an account that is
        # not the creator — the price of a ledger that never records a refused edit.
        if not await guard_record_edit(process, current_user, {}, "process"):
            assert_can_contribute_relation(process, current_user, plan.touches_existing, "steps")
    # NOW the real guard: the locked-field refusal and the scalar revision, at the point where the
    # request can no longer be turned away. ``data`` deliberately EXCLUDES ``steps`` (see the
    # ``model_dump`` above), so every guard that reads ``data`` is blind to the step list by
    # construction and a steps-only PATCH arrives here with ``data == {}`` — which is why the
    # relation needs its own guard above and its own revision below, and why neither can be folded
    # back into ``data``. The return value is that same privilege verdict, already taken above;
    # deliberately not re-bound, so nobody adds a use of it that would read the wrong one.
    async with db.tx() as tx:
        # Before ``guard_record_edit``, which is the first write in this block — a refusal raised
        # after it would leave a committed ledger entry for an edit that was then turned down, which
        # is the very defect the long comment above spends its length on. ``None`` passes and changes
        # nothing, which is every client shipped to date. See ``records.assert_expected_updated_at``.
        #
        # THE STEP PLAN ABOVE HAS WRITTEN NOTHING YET, so refusing here is still clean: ``_plan_steps``
        # is pure and the ``guard_record_edit(…, {})`` probe issues no statement (its own comment says
        # why). ``_apply_steps`` is inside this transaction, below, and goes with the rollback.
        assert_expected_updated_at(process, expected_updated_at)
        await guard_record_edit(process, current_user, data, "process", client=tx)
        await apply_status_policy_update(current_user, process, data)
        # Stamped after the edit guard (the stamp is the API's bookkeeping, never a contributor's
        # edit) and pinned after the status policy, so an already-flagged record cannot be
        # self-approved.
        stamp_workshop_submission(data, check=check, record=process)
        pin_pending_if_late(data, current_user, check=check, record=process)
        merge_field_provenance(data, current_user, previous=process)
        resubmit_status(process, current_user, data)
        if data:
            await tx.process.update(where={"id": process_id}, data=data)
        if plan is not None:
            await _apply_steps(plan, client=tx)
            if plan.writes_anything:
                # FEED THE AUDIT. ``REVISION_SKIP_FIELDS`` does not skip ``steps``; it was simply
                # never handed one, because the step list never travels inside ``data``. The record is
                # passed as a dict holding the BEFORE digest — ``record_revision`` reads its fields
                # through ``get_value``, which takes a mapping — so the one implementation of "diff,
                # encode and append an immutable revision" is reused rather than a second one written
                # here. The re-read costs one query and only on a save that actually changed a step:
                # the new rows' ids exist nowhere else, and an audit row that cannot name what
                # replaced the deleted steps is half a record.
                #
                # READ THROUGH ``tx``, NOT ``db``, AND THAT IS THE WHOLE POINT OF DOING IT HERE
                # (2026-09-03): the rows this re-read is after were inserted by ``_apply_steps`` two
                # lines up, INSIDE this transaction and therefore invisible to any other connection.
                # On the module client it would return the pre-edit list and the audit row would
                # record "steps: unchanged" for the save that replaced them all.
                after = await tx.processstep.find_many(where={"processId": process_id})
                await record_revision(
                    {"id": process_id, "steps": _step_digest(existing_steps)},
                    current_user,
                    {"steps": _step_digest(after)},
                    "process",
                    client=tx,
                )
    hydrated = await db.process.find_unique(where={"id": process_id})
    await hydrate_relations([hydrated], RELATIONS)
    return await _hydrate(hydrated, current_user)


@router.delete("/{process_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_process(process_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.process, process_id)
    await db.process.delete(where={"id": process_id})
