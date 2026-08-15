"""Database work for the design-workshop API: loading, saving stages, and rendering reports.

The routes stay thin; everything that touches Prisma or composes the report pipeline lives
here, matching the repository's split between ``api/routes`` and ``services``.

Three things in this module are worth reading before changing it.

**Saving a stage is a diff, not a truncate-and-insert.** The obvious implementation — delete
every row of the entity, then insert what arrived — loses the row ids, which are what a photo's
caption and a prototype's iteration history point at, and it burns a new id every time a
designer fixes a typo. :func:`save_stage` matches incoming entries to existing rows by
``entryId`` and then by ``clientKey``, updates those, inserts what is new, and soft-deletes only
what the client actually removed.

**``clientKey`` is what makes an offline sync idempotent.** The phone creates a row in a village
with no signal and gives it a UUID. Two days later it syncs. Without a stable client-side key
the server has no way to tell "this is the row you already have" from "this is a new row", and
every reconnect duplicates the whole collection — which is the single most common way an
offline-first app corrupts its own data.

**Media is resolved in one pass, not per photo.** A 26-page report references forty images
across twenty stages; resolving each one as the builder reaches it would be forty awaits inside
a synchronous render. Every media id in the record is collected first, looked up in one query,
and handed to the renderer as a plain dict lookup.

**References are SELECTED and then COPIED.** A designer does not retype an artisan who is
already in the database: :func:`reference_options` serves the picker, and :func:`hydrate_entries`
copies the chosen record's display fields onto the stage entry as it is saved. The two halves
are one feature and neither works alone — see the long note above ``REFERENCE_HYDRATION``.
"""

import logging
from collections.abc import Awaitable, Callable, Iterable, Mapping
from dataclasses import dataclass, field as dataclass_field, replace
from datetime import UTC, datetime
from typing import Any

from fastapi import HTTPException, status

from app.core.db import db
from app.core.deps import is_admin
from app.services import custom_sections, dictation_consent, rich_text
from app.services.address import DISTRICTS_BY_STATE
from app.services.concurrency import gather_reads
from app.services.design_workshop_viewers import has_viewer_grant
from app.services.designers import prefill_from_profile
from app.services.report_annexures import annexure_warnings, attach_transcripts
from app.services.report_builder import ReferencedRecord, WorkshopData, build_report
from app.services.report_custom_sections import (
    CustomReportField,
    CustomSectionItem,
    attach_custom_sections,
    custom_sections_of,
)
from app.services.report_docx import render_docx
from app.services.report_model import ImageRef, PageSize, ReportMeta
from app.services.report_pdf import render_pdf
from app.services.report_questionnaires import attach_questionnaires, questionnaire_warnings
from app.services.report_templates import apply_report_settings, template as get_template
from app.services.report_theme import resolve_accent, resolve_font, theme_from_accent
from app.services.stage_schema import (
    PROMOTED_COLUMNS,
    REF_SCOPE_ALL,
    REF_SCOPE_WORKSHOP,
    REF_SCOPES,
    # THE HYDRATION TABLE MOVED INTO THE REGISTRY and is re-exported here under the name it has
    # always had, because that is where every reader of this feature — the report builder's own
    # docstring, the research note, three test modules — has been told to look. It moved so that
    # `validate_registry` can refuse a mapping whose target field does not exist and
    # `field_to_dict` can publish the mapping to the clients; the note above its declaration says
    # why both matter. Nothing about WHEN it is applied changed: `hydrate_entries` below is still
    # the only thing that writes it, and still writes it only at save time.
    REFERENCE_HYDRATION,
    Cardinality,
    EntitySpec,
    FieldType,
    StageSpec,
    all_entities,
    coerce_value,
    promoted_values,
    registry_version,
    stage_completeness,
    stages,
    validate_entry,
)
from app.services.workshop_transcripts import (
    audio_references,
    enqueue_stage_transcriptions,
    load_transcript_items,
    wants_transcripts,
)

logger = logging.getLogger(__name__)

# --------------------------------------------------------------------------------------
# Loading
# --------------------------------------------------------------------------------------


async def load_workshop_or_404(workshop_id: str, user: Any, *, for_edit: bool = False) -> Any:
    """Fetch a workshop the caller may see, or raise.

    A soft-deleted workshop is a 404 to READ for everyone but an admin, who needs to be able to
    find it in order to restore it. To EDIT (``for_edit=True``) it is a 409 for anybody the
    grants above admit — which is the whole point of the ordering below, and is what makes the
    one sentence a designer can act on reachable. Returning 403 instead of 404 for a workshop the
    caller may not see would confirm that the id exists, which for a research data set keyed by
    cuid is a small but free leak.

    THREE WAYS IN, not two. The creator, an admin, and — since the workshop stopped being a
    one-person record — anybody an admin has given a ``DesignWorkshopViewer`` row. A real Design &
    Prototype Development Workshop is run by two designers alongside a master craftsperson and a
    reviewing officer; before the grant existed, this function told every one of them but the
    creator that a fortnight of their own fieldwork did not exist, and a designer leaving mid-season
    took the record with them.

    The grant is checked LAST and only when the two cheap comparisons have both failed, so the
    ordinary read — a designer opening their own workshop — costs exactly what it did before.

    What a grant buys stops here, at the LOAD: read, and the stage writes that go through this same
    helper. It is not delete and it is not re-granting, both of which are gated separately and
    deliberately were not widened — see ``app/services/design_workshop_viewers.py``.
    """
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    admin = is_admin(user)
    if (
        record.createdById != user.id
        and not admin
        and not await has_viewer_grant(workshop_id, user.id)
    ):
        # Still 404 and still the same detail string, deliberately. Widening WHO may enter must not
        # change what a stranger is told: a 403 here would confirm the id exists to exactly the
        # people the clause is turning away.
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    # WHO-MAY-ENTER IS ANSWERED BEFORE IS-IT-DELETED, AND THAT ORDER IS THE FIX RATHER THAN THE
    # STYLE. The deleted check used to run FIRST, so a non-admin got 404 before the `for_edit`
    # 409 below could ever be reached — and the only accounts that reach this helper with
    # `for_edit=True` are designers, who are not admins. The 409 was therefore dead code for
    # every caller who could hit the condition.
    #
    # What that cost is on the client. `frontend/lib/designWorkshopStore.ts` rethrows 409 out of
    # the stage arm precisely so the workshop-level catch can print the one correct sentence
    # ("Ask an admin to restore it, then sync again"); a 404 is not rethrown, so an admin
    # soft-deleting a duplicate while a designer's laptop held unsent stages stamped EVERY one of
    # them permanent with "it will keep being refused until the answer that caused it is
    # corrected — this is not a connection problem". One red line per stage, sending the designer
    # to audit answers that nothing had objected to, and the sentence that would have told them
    # what actually happened was unreachable.
    #
    # NOTHING IS DISCLOSED BY THE SWAP. The 409 is only reachable once the caller has already
    # proved they are the creator, an admin, or the holder of a `DesignWorkshopViewer` grant —
    # people who may see that this workshop exists. A stranger still gets the same 404 with the
    # same detail string, and a REVOKED grantee gets it too, because the grant check above fails
    # for them before this line. It costs one extra `has_viewer_grant` round trip in the deleted
    # case only, which is a case nobody is in twice.
    #
    # THE READ PATH IS UNCHANGED: `for_edit` is false there, so a deleted workshop still answers
    # 404 to everyone but an admin, who needs to be able to find it in order to restore it.
    if record.deletedAt is not None:
        # No second authorisation test here: reaching this line already means creator, admin or
        # grantee, because the clause above turned everybody else away with a 404.
        if for_edit:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="This workshop is deleted. Restore it before editing.",
            )
        if not admin:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail="Record not found"
            )
    return record


async def entry_rows(workshop_id: str, *, stage_key: str | None = None) -> list[Any]:
    where: dict[str, Any] = {"designWorkshopId": workshop_id, "deletedAt": None}
    if stage_key:
        where["stageKey"] = stage_key
    return await db.dwstageentry.find_many(where=where, order={"ordinal": "asc"})


def workshop_summary(record: Any) -> dict[str, Any]:
    """The workshop header as the clients read it.

    A HAND-WRITTEN DICT AND NOT A DUMP OF THE ROW, which is why a new column reaches a client only by
    being named here. That is deliberate — the row carries columns no client has any business reading —
    and it is also the trap: a column added to ``schema.prisma`` and not to this dict is invisible on
    every surface, and looks from the outside exactly like a column that is never written.
    """
    return {
        "id": record.id,
        "title": record.title,
        "templateId": record.templateId,
        "status": record.status,
        "workshopCode": record.workshopCode,
        "scheme": record.scheme,
        "craftName": record.craftName,
        "clusterName": record.clusterName,
        "state": record.state,
        "district": record.district,
        "venue": record.venue,
        "startDate": record.startDate.date().isoformat() if record.startDate else None,
        "endDate": record.endDate.date().isoformat() if record.endDate else None,
        "designerName": record.designerName,
        "implementingAgency": record.implementingAgency,
        "sponsor": record.sponsor,
        "notes": record.notes,
        "workshopId": record.workshopId,
        "createdById": record.createdById,
        "createdAt": record.createdAt.isoformat() if record.createdAt else None,
        "updatedAt": record.updatedAt.isoformat() if record.updatedAt else None,
        "deletedAt": record.deletedAt.isoformat() if record.deletedAt else None,
        # Tier 3 consent: may this workshop's recordings leave the device? Three keys — the answer, the
        # moment the ARTISAN gave it, and who took it down. The acceptor's display NAME is deliberately
        # not here: this dict is serialised once per row by the paged list, and resolving a name would
        # put a query per workshop into it to print something the list does not show. The single-record
        # read adds `dictationConsentByName`. See services/dictation_consent.py.
        **dictation_consent.consent_keys(record),
    }


# --------------------------------------------------------------------------------------
# References: the pickers, and the copy that outlives what they point at
# --------------------------------------------------------------------------------------

# How many options one picker call may return, and the ceiling a client may ask for. A mature
# cluster has several hundred documented artisans and more products than that, and serving the
# lot would send a megabyte of JSON to a phone on one bar of signal so a designer could scroll
# past four hundred names. The picker is a SEARCH box, not a list: `search` narrows, and
# `truncated` tells the client to say "keep typing" rather than to present a partial list as if
# it were the whole one.
REFERENCE_LIMIT_DEFAULT = 50
REFERENCE_LIMIT_MAX = 200


def _meta(row: Any) -> dict[str, Any]:
    value = getattr(row, "extraMetadata", None)
    return value if isinstance(value, dict) else {}


def _meta_value(meta: dict[str, Any], *keys: str) -> Any:
    """The first of several spellings a researcher's metadata may have used.

    Age and years of experience were collected as free metadata long before this registry
    existed, under whichever key the form of the day happened to use. Reading one spelling would
    blank the field for every record entered under another, and a blank experience column in a
    participant table reads as "we did not ask" rather than as "we looked in the wrong place".
    """
    for key in keys:
        value = meta.get(key)
        if value not in (None, ""):
            return value
    return None


def _rel(row: Any, relation: str, attribute: str) -> Any:
    """An attribute of an included relation, or None when the relation was not loaded."""
    related = getattr(row, relation, None)
    return getattr(related, attribute, None) if related is not None else None


def _money(value: Any) -> str | None:
    """A Prisma Decimal as the two-place string a MONEY field is stored as.

    Money crosses this boundary as a string for the same reason it is stored as one: a float
    round trip turns 1250.10 into 1250.0999999999999, and a cost sheet that prints that has lost
    the argument before anyone reads the total.
    """
    if value is None:
        return None
    try:
        return f"{float(value):.2f}"
    except (TypeError, ValueError):
        return None


def _joined(*parts: Any) -> str:
    return " · ".join(str(p).strip() for p in parts if p not in (None, "") and str(p).strip())


# ProductType and PRODUCT_CATEGORY answer two different questions and only two of their tokens
# mean the same thing. ProductType asks what KIND OF THING a documented record is — a finished
# good, a sample, a raw material — while the workshop registry's category asks what the product
# IS: a saree, a floor covering, a bag. Guessing across them would fill a ministry report's
# category column with plausible, wrong values that nobody would think to check, so only the two
# genuine matches are mapped and everything else arrives blank for the designer to choose.
_PRODUCT_TYPE_TO_CATEGORY: dict[str, str] = {
    "PACKAGING": "PACKAGING",
    "OTHER": "OTHER",
}


def _enum_token(value: Any) -> str:
    """The bare token of a Prisma enum, which arrives as either a str or an enum member."""
    return str(getattr(value, "value", value) or "")


@dataclass(frozen=True, slots=True)
class ReferenceModel:
    """One external record type a REF field may point at.

    ``data`` is the important one: it is what hydrates into a stage entry when a designer picks
    this record, and it is the same dict the picker hands the client so the row fills in the
    instant it is chosen rather than after a round trip.
    """

    delegate: str                                   # the attribute on the Prisma client
    label: Callable[[Any], str]
    sublabel: Callable[[Any], str]
    data: Callable[[Any, str | None], dict[str, Any]]
    order: dict[str, str]
    search_fields: tuple[str, ...]
    include: dict[str, Any] = dataclass_field(default_factory=dict)
    # None when the model has no notion of a workshop, in which case a WORKSHOP-scoped field
    # falls back to the whole table rather than to nothing.
    workshop_where: Callable[[str], dict[str, Any]] | None = None
    # The column that narrows this model to one artisan, for the cascading pickers.
    artisan_field: str = ""
    # The MediaFile foreign key naming this model, for the one photograph the picker shows.
    media_field: str = ""


def _artisan_workshop_where(workshop_id: str) -> dict[str, Any]:
    # BOTH READINGS OF "AT THIS WORKSHOP". An artisan reaches a workshop either through the
    # explicit column on their record or through the WorkshopArtisan join, and the two are kept
    # in lock-step but neither is complete on its own for rows written before the column
    # existed. Reading one of them would leave a designer staring at a picker that does not
    # contain the artisan sitting in front of them, and they would type the name in — which is
    # the exact behaviour this whole feature exists to end.
    return {"OR": [{"workshopId": workshop_id},
                   {"workshops": {"some": {"workshopId": workshop_id}}}]}


REFERENCE_MODELS: dict[str, ReferenceModel] = {
    "Artisan": ReferenceModel(
        delegate="artisan",
        include={"craft": True, "location": True},
        order={"name": "asc"},
        search_fields=("name", "localName", "place"),
        workshop_where=_artisan_workshop_where,
        media_field="artisanId",
        label=lambda r: str(r.name or ""),
        sublabel=lambda r: _joined(_rel(r, "craft", "name"), r.place),
        data=lambda r, photo: {
            "name": r.name,
            "localName": r.localName,
            # The craft is the closest thing the artisan table has to a specialisation, and it
            # is what a researcher typed into the free metadata before the relation existed.
            "specialisation": (_rel(r, "craft", "name")
                               or _meta_value(_meta(r), "specialisation", "specialization")),
            "experienceYears": _meta_value(
                _meta(r), "experienceYears", "experience", "yearsOfExperience"
            ),
            "gender": r.gender,
            "phone": r.phone,
            # The STATED village, never the provenance placeName: see the long note above the
            # Location model for why the two are not the same answer. `place` is the free-text
            # fallback the researchers were using before the stated-address columns existed.
            "village": _rel(r, "location", "village") or r.place,
            "photo": photo,
        },
    ),
    "ProductDocumentation": ReferenceModel(
        delegate="productdocumentation",
        include={"artisan": True},
        order={"productName": "asc"},
        search_fields=("productName", "localName", "artisanName", "craftName"),
        workshop_where=lambda wid: {"workshopId": wid},
        artisan_field="artisanId",
        media_field="productId",
        label=lambda r: str(r.productName or ""),
        sublabel=lambda r: _joined(r.artisanName, r.craftName, _money(r.sellingPrice)),
        data=lambda r, photo: {
            "name": r.productName,
            "localName": r.localName,
            "category": _PRODUCT_TYPE_TO_CATEGORY.get(_enum_token(r.productType)),
            "material": r.rawMaterialsUsed,
            "price": _money(r.sellingPrice),
            "use": r.productFunctionUse,
            "artisanName": r.artisanName,
            "photo": photo,
        },
    ),
    "ToolDocumentation": ReferenceModel(
        delegate="tooldocumentation",
        include={"artisan": True},
        order={"toolkitName": "asc"},
        search_fields=("toolkitName", "localName", "englishName", "artisanName"),
        workshop_where=lambda wid: {"workshopId": wid},
        artisan_field="artisanId",
        media_field="toolId",
        label=lambda r: str(r.toolkitName or ""),
        sublabel=lambda r: _joined(r.englishName, r.artisanName, r.place),
        data=lambda r, photo: {
            "name": r.toolkitName,
            "localName": r.localName,
            "material": r.material,
            "usedFor": r.processUsedIn,
            "cost": _money(r.replacementCost),
            "photo": photo,
        },
    ),
    "Process": ReferenceModel(
        delegate="process",
        include={"product": True},
        order={"name": "asc"},
        search_fields=("name",),
        workshop_where=lambda wid: {"workshopId": wid},
        label=lambda r: str(r.name or ""),
        sublabel=lambda r: _joined(_rel(r, "product", "productName")),
        # THREE KEYS, NOT ONE, and the third is what the sublabel above already shows.
        #
        # This lambda used to return the name alone, which made the traditional-process stage —
        # one of the report's substantive narrative sections — the thinnest of the five reference
        # models by an order of magnitude: eight fields reach a participant row, six a tool row,
        # six an existing-product row, and one reached a process step. The `Process` table holds
        # notes and hangs off a product; both are copied. Which of the model's columns land on
        # which box, and why `steps` and `preProcessAvailable` deliberately land nowhere, is
        # written out above `REFERENCE_HYDRATION["processStep.processRef"]` in `stage_schema`,
        # because that is where the pairing is declared and a reason belongs beside the decision
        # it explains rather than beside the value it reads.
        data=lambda r, _photo: {
            "name": r.name,
            "notes": r.notes,
            "productName": _rel(r, "product", "productName"),
        },
    ),
    "Craft": ReferenceModel(
        delegate="craft",
        order={"name": "asc"},
        search_fields=("name", "localName", "category"),
        workshop_where=lambda wid: {
            "OR": [{"workshopId": wid}, {"workshops": {"some": {"workshopId": wid}}}]
        },
        media_field="craftId",
        label=lambda r: str(r.name or ""),
        sublabel=lambda r: _joined(r.category, r.place),
        data=lambda r, _photo: {"craftName": r.name, "craftLocalName": r.localName},
    ),
}


def _dw_entity(model: str) -> EntitySpec | None:
    """The registry entity a ``Dw…`` ref_model names, if it is one."""
    return next((e for _s, e in all_entities() if e.name == model), None)


async def reference_options(record: Any, model: str, *, scope: str = REF_SCOPE_ALL,
                            filter_by: str | None = None, search: str | None = None,
                            limit: int = REFERENCE_LIMIT_DEFAULT) -> dict[str, Any]:
    """The options one REF picker shows, for the workshop ``record``.

    ``scope`` is the field's own :data:`REF_SCOPES` value, sent back by the client so the server
    and the form cannot disagree about how wide the net is. ``filter_by`` is the value of the
    field named by ``ref_filter_by`` — the chosen artisan, for a product picker.
    """
    if scope not in REF_SCOPES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"scope must be one of {', '.join(sorted(REF_SCOPES))}",
        )
    take = max(1, min(int(limit or REFERENCE_LIMIT_DEFAULT), REFERENCE_LIMIT_MAX))

    entity = _dw_entity(model)
    if entity is not None:
        if filter_by:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"{model} cannot be filtered by another record",
            )
        return await _in_record_options(record, entity, search, take)

    spec = REFERENCE_MODELS.get(model)
    if spec is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"Unknown reference model {model!r}; expected one of "
                f"{', '.join(sorted(REFERENCE_MODELS))} or an entity of this workshop"
            ),
        )

    clauses: list[dict[str, Any]] = []
    # SCOPE FALLS BACK RATHER THAN EMPTYING THE PICKER. A design workshop need not be linked to
    # a Workshop record — the link is optional and is frequently made days after the capture
    # starts — and a WORKSHOP-scoped picker on an unlinked workshop would be permanently empty
    # with nothing on screen to explain why. The response says which of the two happened, so the
    # form can label the list "all documented artisans" instead of pretending it narrowed one.
    scoped = False
    if scope == REF_SCOPE_WORKSHOP and spec.workshop_where and record.workshopId:
        clauses.append(spec.workshop_where(str(record.workshopId)))
        scoped = True

    filtered = False
    if filter_by:
        if not spec.artisan_field:
            # A filter this model cannot honour is reported rather than ignored. Silently
            # dropping it would serve the whole table to a picker the designer believes is
            # narrowed to one artisan, and the wrong product would be chosen without a hint.
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"{model} cannot be filtered by another record",
            )
        artisan_id = await _artisan_id_behind(str(record.id), str(filter_by))
        if artisan_id is None:
            # The filter names a roster entry that was typed in by hand, so there is no artisan
            # record and therefore no documented products to attribute to them. An empty list is
            # the honest answer; falling back to every product would invite the designer to
            # attach another artisan's work to this one.
            return _reference_payload(model, scope, scoped, True, [], truncated=False)
        clauses.append({spec.artisan_field: artisan_id})
        filtered = True

    term = (search or "").strip()
    if term:
        clauses.append({
            "OR": [{f: {"contains": term, "mode": "insensitive"}} for f in spec.search_fields]
        })

    where: dict[str, Any] = {"AND": clauses} if clauses else {}
    # take + 1, not a second COUNT: every search here is a case-insensitive `contains`, which
    # Postgres runs as ILIKE '%…%' and no index can answer, so counting the matches costs a
    # second scan of the largest table in the database to learn one boolean.
    rows = await getattr(db, spec.delegate).find_many(
        where=where, order=spec.order, take=take + 1, include=spec.include or None
    )
    truncated = len(rows) > take
    rows = rows[:take]

    photos = await _reference_photos(spec, [r.id for r in rows])
    options = [
        {
            "id": row.id,
            "label": spec.label(row),
            "sublabel": spec.sublabel(row),
            "data": {k: v for k, v in spec.data(row, photos.get(row.id)).items()
                     if v not in (None, "")},
        }
        for row in rows
    ]
    return _reference_payload(model, scope, scoped, filtered, options, truncated=truncated)


def _reference_payload(model: str, scope: str, scoped: bool, filtered: bool,
                       options: list[dict[str, Any]], *, truncated: bool) -> dict[str, Any]:
    return {
        "model": model,
        "scope": scope,
        "scopedToWorkshop": scoped,
        "filtered": filtered,
        "truncated": truncated,
        "options": options,
    }


async def _artisan_id_behind(workshop_id: str, candidate: str) -> str | None:
    """The Artisan id a ``filterBy`` value stands for, following a roster entry if that is what
    it is.

    TWO KINDS OF ID REACH THIS PARAMETER and the form has no business knowing the difference.
    At stage 6 the artisan picker holds an ``Artisan`` id straight from the table. At stage 13
    the maker is chosen from the ROSTER, so the same-named ``artisanRef`` holds a
    ``DwParticipant`` entry id instead — and the product cascade hangs off it either way. Making
    the client resolve that would put a rule about the registry's internals into three
    codebases; resolving it here costs one indexed primary-key read.

    Returns None when the value is a roster entry with no artisan behind it, which is a real and
    ordinary state: an artisan who walked in on day two and was typed in by hand.
    """
    row = await db.dwstageentry.find_unique(where={"id": candidate})
    if row is None:
        return candidate
    if row.designWorkshopId != workshop_id:
        # An id from another workshop is not a mistake worth a 403 — it is a stale form — but it
        # must not be followed, or one workshop's roster would filter another's products.
        return None
    linked = (row.data or {}).get("artisanRef")
    return str(linked) if linked else None


#: The parent foreign keys :func:`_reference_photos` is allowed to group by.
#:
#: It interpolates the column name into SQL, so the name must come from here and not from the
#: caller. Every value is also a ``media_field`` in :data:`REFERENCE_MODELS`; the guard below is
#: what makes adding a model there fail loudly rather than interpolate an unreviewed name.
_PHOTO_PARENT_COLUMNS = frozenset({"artisanId", "craftId", "productId", "toolId"})


async def _reference_photos(spec: ReferenceModel, ids: list[str]) -> dict[str, str]:
    """One photograph per record, in one query rather than one per row.

    ONE PER PARENT, NOT ONE BUDGET SHARED BETWEEN THEM. This used to be a plain ``find_many`` with
    ``order={"createdAt": "asc"}, take=len(ids) * 4`` — a single ceiling across every id — so a
    roster of forty artisans read at most 160 image rows for all forty, and a handful of
    long-documented artisans carrying twenty or thirty pictures each consumed the lot. The artisans
    whose photographs were taken most recently then hydrated with ``photo`` empty, the report
    printed a roster with faces missing for people whose portraits sat one join away, and re-saving
    the stage never fixed it because the same rows won the budget every time. Nothing warned
    anybody, which is why it survived: the document renders cleanly either way.

    ``DISTINCT ON`` is what Postgres offers for exactly this shape and it is one round trip, which
    matters because all three callers — the reference picker, ``hydrate_entries`` on the save path,
    and the report's ``load_report_references`` — are on a link measured at 756ms a hop. The
    ``createdAt, id`` tiebreak keeps the answer STABLE: two records photographed in the same second
    would otherwise swap portraits between two renders of the same report.
    """
    if not spec.media_field or not ids:
        return {}
    column = spec.media_field
    if column not in _PHOTO_PARENT_COLUMNS:
        # Never reached from REFERENCE_MODELS as it stands. It is here so that a model added with a
        # new media_field fails loudly on the first call instead of interpolating an unreviewed
        # name into the statement below.
        raise ValueError(f"Unsupported reference photo column: {column}")
    rows = await db.query_raw(
        f'SELECT DISTINCT ON (m."{column}") m."{column}" AS parent, m."id" AS id '
        f'FROM "MediaFile" m '
        f'WHERE m."mediaType" = \'IMAGE\'::"MediaType" AND m."{column}" = ANY($1::text[]) '
        f'ORDER BY m."{column}", m."createdAt" ASC, m."id" ASC',
        ids,
    )
    return {str(row["parent"]): str(row["id"]) for row in rows if row.get("parent")}


async def _in_record_options(record: Any, entity: EntitySpec, search: str | None,
                             take: int) -> dict[str, Any]:
    """Options for a ref that points INSIDE this workshop — a sketch, a prototype, a roster row.

    Always scoped to the workshop whatever the field's declared scope says, because there is no
    other reading of a ``DwSketch`` reference: the sketches of a different workshop are not
    candidates for this one's prototypes, and offering them would produce a report whose
    prototype table cites drawings that appear nowhere in it.
    """
    rows = await db.dwstageentry.find_many(
        where={"designWorkshopId": record.id, "entityKey": entity.key, "deletedAt": None},
        order={"ordinal": "asc"},
    )
    label_key = entity.label_field or next(
        (f.key for f in entity.fields if f.is_free_text), ""
    )
    # A second printable field, so two prototypes both called "Bag" are told apart by their code.
    sub_key = next(
        (f.key for f in entity.fields
         if f.key != label_key and f.is_free_text and not f.deprecated), ""
    )
    term = (search or "").strip().casefold()

    options: list[dict[str, Any]] = []
    for row in rows:
        data = dict(row.data or {})
        label = str(data.get(label_key) or "").strip() or entity.title
        sublabel = str(data.get(sub_key) or "").strip() if sub_key else ""
        if term and term not in label.casefold() and term not in sublabel.casefold():
            continue
        options.append({"id": row.id, "label": label, "sublabel": sublabel, "data": {}})

    truncated = len(options) > take
    return _reference_payload(entity.name, REF_SCOPE_WORKSHOP, True, False,
                              options[:take], truncated=truncated)


@dataclass(slots=True)
class PendingEntry:
    """One entry of a stage save, between validation and the write.

    It exists so hydration can happen ONCE for the whole payload — a stage 3 save carries thirty
    participants, and looking each artisan up as its row is reached would be thirty round trips
    inside a request a designer is waiting on with one bar of signal.
    """

    entity: EntitySpec
    data: dict[str, Any]          # the cleaned values, mutated in place by hydration
    previous: dict[str, Any]      # what the row held before this save; empty for a new row
    row_id: str | None
    ordinal: int
    client_key: str | None


def _has_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, tuple, dict)):
        return bool(value)
    return True


async def hydrate_entries(entries: list[PendingEntry]) -> None:
    """Copy each chosen reference's display fields onto the entry that names it.

    See the long note above :data:`REFERENCE_HYDRATION` for why the copy exists at all. This
    function is only about WHEN it is written, and there is exactly one case in which it
    overwrites something:

    * A row that already named a DIFFERENT record and now names this one has every mapped field
      rewritten — INCLUDING the fields the new record leaves blank, which are cleared rather than
      left holding the previous record's answer. Leaving the old artisan's name beside the new
      artisan's id is the one outcome worse than either alternative: the report and the research
      data would then name two different people for the same row, and nothing on screen would say
      which was meant. Half-rewriting is that same outcome one field at a time, and it is the
      shape the failure actually took for a year: the id and the name moved, the phone number and
      the photograph did not.
    * EVERYWHERE ELSE ONLY BLANKS ARE FILLED — including on a brand-new row. What the client
      sent is what the designer typed or accepted in the room: a name the artisan prefers, a
      village the master record has wrong, a price agreed on the day. Treating a first save as a
      "change" and overwriting it would mean the picker silently reverted every correction a
      designer made after choosing from it, which is a worse failure than retyping, because the
      designer watches the value change back and has no way to make it stick.

    A gallery is never overwritten, only seeded when empty: the documented product's photograph
    is a starting point, and replacing the photographs a designer took at the workshop with it
    would destroy the only copy of them that exists.

    A reference whose record has been deleted hydrates nothing and is left exactly as it is —
    which is the whole point of having copied the fields in the first place.
    """
    wanted: dict[str, set[str]] = {}
    for item in entries:
        for spec in item.entity.fields:
            if spec.type is not FieldType.REF or spec.ref_model not in REFERENCE_MODELS:
                continue
            if f"{item.entity.key}.{spec.key}" not in REFERENCE_HYDRATION:
                continue
            ref_id = item.data.get(spec.key)
            if ref_id:
                wanted.setdefault(spec.ref_model, set()).add(str(ref_id))
    if not wanted:
        return

    resolved: dict[str, dict[str, dict[str, Any]]] = {}
    for model, ids in wanted.items():
        model_spec = REFERENCE_MODELS[model]
        rows = await getattr(db, model_spec.delegate).find_many(
            where={"id": {"in": sorted(ids)}}, include=model_spec.include or None
        )
        photos = await _reference_photos(model_spec, [r.id for r in rows])
        resolved[model] = {
            row.id: model_spec.data(row, photos.get(row.id)) for row in rows
        }

    for item in entries:
        for spec in item.entity.fields:
            mapping = REFERENCE_HYDRATION.get(f"{item.entity.key}.{spec.key}")
            if not mapping:
                continue
            ref_id = item.data.get(spec.key)
            if not ref_id:
                continue
            source = resolved.get(spec.ref_model, {}).get(str(ref_id))
            if source is None:
                continue
            was = str(item.previous.get(spec.key) or "")
            replaced = bool(was) and was != str(ref_id)
            if replaced:
                # THE PREVIOUS RECORD'S VALUES ARE CLEARED FIRST, BEFORE ANYTHING IS COPIED IN.
                #
                # The overwrite promised by this function's docstring — "a row that already named a
                # DIFFERENT record and now names this one has every mapped field rewritten" — used
                # to be applied field by field inside the loop below, which skips a source value
                # that is blank. So it rewrote only the fields the NEW record happens to have
                # filled in, and every field the new record leaves blank kept the OLD record's
                # answer. Pick a fully documented artisan, notice the phone belongs to a different
                # Sita Devi, re-point the row at a thinly documented one, and the row is stored as
                # artisan B's name and craft beside artisan A's phone, village, gender and
                # PHOTOGRAPH — with `artisanRef` naming B, so nothing can ever re-resolve it. That
                # participant table goes into a .docx submitted to a ministry.
                #
                # Clearing here rather than writing blanks in the loop because "the new record has
                # nothing to say about this field" and "the new record says it is empty" are the
                # same thing on the wire: `REFERENCE_MODELS[...].data` returns None for a column
                # nobody filled in, and `coerce_value` would reject the None anyway.
                #
                # THE MULTI ARM IS DELIBERATELY EXEMPT, matching the gallery rule below: a gallery
                # is seeded when empty and never overwritten, because it holds the photographs the
                # designer took at the workshop and there is no second copy of those anywhere.
                #
                # This is the rule the handset already applies — see `hydrationPatch` in
                # DwReferenceField.kt, whose comment is "Leaving it would attach the old artisan's
                # phone number to the new artisan's name" — so the server is being brought into
                # line with the client that got it right, not inventing a policy.
                for target_key in mapping.values():
                    target = item.entity.field(target_key)
                    if target is None or target.type.is_multi:
                        continue
                    # Deprecated targets are cleared too, even though the loop below refuses to
                    # WRITE to them. Refusing to put new data into a retired field is not a reason
                    # to keep a different person's data there: the value still travels in `data`.
                    item.data.pop(target_key, None)
            for source_key, target_key in mapping.items():
                value = source.get(source_key)
                if value in (None, ""):
                    continue
                target = item.entity.field(target_key)
                if target is None or target.deprecated:
                    continue
                existing = item.data.get(target_key)
                if _has_value(existing) and (not replaced or target.type.is_multi):
                    continue
                if target.type.is_multi and not isinstance(value, (list, tuple)):
                    value = [value]
                # Through the registry's own coercion, so a money string lands two-place, an
                # integer arrives as an integer, and a value the target field cannot legally
                # hold — a product type that is not one of the workshop's categories — is
                # dropped rather than written. A rejected hydration leaves the field blank for
                # the designer to answer, which is recoverable; a token no client can render is
                # not.
                cleaned, error = coerce_value(target, value)
                if error or cleaned is None:
                    continue
                item.data[target_key] = cleaned


# --------------------------------------------------------------------------------------
# Prefill: the designer's profile, copied into a brand-new workshop
# --------------------------------------------------------------------------------------


async def seed_designer_prefill(
    record: Any, user: Any, *, extra: Mapping[str, Any] | None = None
) -> Any:
    """Start a new workshop with the creator's profile already in stage 1 and stage 3.

    Returns the workshop header, updated if a promoted column was seeded, so the caller can
    serialise it without a second read.

    **WRITTEN AS ORDINARY STAGE ENTRIES, WHICH IS THE WHOLE DESIGN.** The alternative — teaching
    the report builder to fall back to the profile when ``designerName`` is blank — would put a
    special case into the one component that must stay a pure function of the registry and the
    data, and it would put it in three places, because the phone renders the same report offline
    from its own copy of that builder. Here the values are simply *there*: the form shows them,
    the designer can correct them for this workshop only, the completeness score counts them, the
    .docx prints them, and nothing anywhere knows a profile was involved.

    **AND THEY ARE COPIES.** See ``designers.prefill_from_profile``: a report is a historical
    document, and a designer who moves institution in 2027 must not retroactively change the
    workshop they ran in 2026.

    Prefill NEVER fails the create. A designer who cannot start a workshop because the seeding of
    a convenience value went wrong has lost the whole feature to help with; the workshop is
    already committed by the time this runs, and an empty stage 1 is a minor inconvenience the
    designer can type past.

    ``extra`` IS THE CREATE FORM'S OWN ANSWERS, AND IT IS SEEDED HERE RATHER THAN IN A SECOND
    WRITE BECAUSE THEY LAND IN THE SAME ENTITY — ``workshopSetup`` holds ``designerName`` and
    ``craftName`` alike, and two creates for one singleton would be two rows where the matcher
    expects one.

    ``POST /design-workshops`` accepts craft, cluster, state, district and the two dates, and used
    to copy them straight onto the ``DesignWorkshop`` COLUMNS with no stage entry behind them —
    making the create route a second writer of columns whose single writer is supposed to be
    ``promoted_values``. The FIRST stage-1 save then erased every one of them: ``touched_entities``
    gains ``workshopSetup`` for any entry naming it, answered or not; the web sends a read stage's
    singleton whether or not it holds anything; and ``_coerce_promoted`` nulls a promoted column
    of a touched entity whose value is blank. A designer who typed "Ikat / Barpali / Odisha /
    Bargarh" into the create form, opened stage 1, typed the venue and pressed Save watched
    craftName, clusterName, state, district, startDate, endDate, scheme, implementingAgency,
    sponsor and workshopCode all go to NULL under a 200 reading "Stage saved" — the workshop then
    invisible to every list filter and search on craft, state, district and date, and showing "—"
    in the list's own columns, for the whole fortnight of capture, repairing itself only when
    stage 1 was finally completed.

    Seeding the ENTRY closes both halves and adds no third writer: the columns now have something
    behind them, stage 1 opens with those boxes already filled in (nothing else fills them — the
    stage form does not read the header), and a later save merges with them rather than
    contradicting them. ``extra`` wins over a profile value of the same key, because it is what
    the designer typed thirty seconds ago; the two sets do not overlap today.
    """
    try:
        values: dict[str, Any] = {
            **(await prefill_from_profile(user.id)), **dict(extra or {})
        }
        if not values:
            return record
        header: dict[str, Any] = {}
        for spec in stages():
            for entity in spec.entities:
                if entity.cardinality is not Cardinality.SINGLETON:
                    continue
                # WHICH STAGE EACH KEY BELONGS TO IS ASKED OF THE REGISTRY, never hard-coded.
                # Writing "WORKSHOP_SETUP"/"workshopSetup" here would mean that the day somebody
                # moves the designer block into its own stage — a plausible edit, the registry is
                # explicitly designed to be reorganised — prefill would silently write entries
                # under a stage key no form reads, and every designer would quietly go back to
                # retyping their biography with nothing in the logs to say why.
                known = {f.key for f in entity.fields if not f.deprecated}
                subset = {k: v for k, v in values.items() if k in known}
                if not subset:
                    continue
                clean, _errors = validate_entry(entity, subset, enforce_required=False)
                if not clean:
                    continue
                await db.dwstageentry.create(data={
                    "designWorkshopId": record.id,
                    "stageKey": spec.key,
                    "entityKey": entity.key,
                    "ordinal": 0,
                    "data": _json(clean),
                    "createdById": user.id,
                })
                # Only columns this subset actually filled, and only the string-valued ones.
                # `_coerce_promoted` is deliberately NOT reused: it nulls every promoted column of
                # a touched entity, which here would blank the workshop's own title on creation.
                for column, value in promoted_values(entity.key, clean).items():
                    # THE TWO DATE COLUMNS ARE NAMED AND SKIPPED, and they are named rather than
                    # inferred because a DATE field coerces to an ISO *string* ("2026-08-15") —
                    # which sails through `isinstance(value, str)` and lands on a Prisma DateTime
                    # column as text. That is a driver error, not a validation one: it would be
                    # swallowed by the `except` below and leave a workshop whose stage entries
                    # were half seeded and whose header was not written at all. `_coerce_promoted`
                    # parses them for the stage-save path; here there is nothing to parse for,
                    # because the create route wrote both columns from the same request through
                    # `_parse_date` before calling us. Only `extra` can put a date in reach — the
                    # profile carries none — so this guard arrived with it.
                    if column in ("startDate", "endDate"):
                        continue
                    if isinstance(value, str) and value:
                        header[column] = value[:220]
        if header:
            return await db.designworkshop.update(where={"id": record.id}, data=header)
        return record
    except Exception:
        # Anything from a Prisma error to a registry key that has been renamed out from under the
        # profile map. The designer gets an empty stage 1 instead of a 500 on a workshop that was
        # already created — which, without this, would leave an orphan draft behind on every
        # retry until somebody noticed the list filling up with untitled duplicates.
        logger.exception("Designer prefill failed for workshop %s", getattr(record, "id", None))
        return record


# --------------------------------------------------------------------------------------
# Saving a stage
# --------------------------------------------------------------------------------------


def refused_answer_count(errors: Mapping[str, Any]) -> int:
    """How many ANSWERS one save refused. The number both surfaces must show, computed once, here.

    ── WHY THE SERVER COUNTS THIS AT ALL ──────────────────────────────────────────────────────────
    ``errors`` is two levels deep — ``{scope: {field: message}}``, where a scope is ``entityKey`` for
    a singleton, ``entityKey[i]`` for a collection row, or the reserved custom container. A nested map
    with no total in it can be counted two ways, and the two clients picked one each:

    * the web read ``Object.keys(saved.errors ?? {}).length`` — the number of SCOPES — and printed it
      as "The server refused N answer(s)";
    * Android built one ``DwStageRefusal`` per (scope, field) pair and printed ``refusals.size`` — the
      number of FIELDS.

    One stage entry with three bad fields is therefore "1 answer" on the web and "3 answers" on the
    handset, off the same response body, and both sentences use the word *answer*. A designer working
    across a laptop and a phone is told two different things about one save, and neither surface is
    lying about what it counted.

    **FIELDS IS THE RIGHT READING AND SCOPES IS NOT A DEFENSIBLE ONE.** An answer is what a designer
    typed into one box; a scope is a row of the form, and a row is not an answer. The remedy the
    sentence sends them to — "open the stage to see which fields are marked" — is per-field too, so
    the count and the instruction disagreed on the web whenever any row held more than one bad value.

    It is returned as ``refusedAnswers`` rather than left to be derived because the shape is what
    invited the disagreement: a client that has to compute a headline number from a nested map will
    compute whichever one its author read first, and no amount of documenting ``errors`` prevents
    that. ``errors`` keeps its exact shape — both clients need it to mark the individual boxes, and
    Android's ``unplaced`` valve depends on being able to see a scope it cannot place.

    A NON-MAPPING VALUE COUNTS AS ONE, not as its length. Every writer of ``errors`` today puts a
    ``dict[str, str]`` there, so the branch is unreachable; it exists because ``len()`` of a string
    would silently return a character count, and "the server refused 47 answers" for one refused
    field is the kind of number nobody can trace back to a bug.
    """
    return sum(
        len(fields) if isinstance(fields, Mapping) else 1 for fields in errors.values()
    )


async def save_stage(workshop_id: str, spec: StageSpec, payload: Any, user: Any) -> dict[str, Any]:
    """Write one stage, returning HOW MUCH was stored, what failed validation and what was dropped.

    **IT DOES NOT RETURN THE STORED VALUES THEMSELVES, and this sentence used to say it did.**
    There was a `stored` dict built here for every non-`_custom` entry of every save, two write
    sites and no read site, dropped on the floor at the return — while this docstring, the
    comments beside the two write sites and the route's own docstring all described a response
    field that had never been on the wire. Android had already MEASURED the absence and written
    it down as fact (`DwStageRefusal.kt`: "The save response does not carry it — measured",
    listing the same key set), and built its three-state `DwHeld.UNRECORDED` around the gap.

    The reply carries counts, refusals and drift — `saved`, `created`, `updated`, `removed`,
    `errors`, `refusedAnswers`, `droppedKeys`, `droppedCustomKeys`, `completeness`,
    `transcriptionsQueued`, `transcriptionConsentRefusal`, `schemaVersion`,
    `customSchemaVersion` — and a client that needs the values back reads
    `GET /{id}/stages/{key}`. That IS a real cost: hydration can legitimately change what was
    written (a MONEY value normalised, an ENUM token the target field will not admit dropped, a
    photograph resolved by `_reference_photos`), so the form goes on showing the client's guess
    until somebody makes that second request. Echoing the values back instead is an additive
    change both clients would ignore safely, and it is written up as a follow-up rather than
    done here for two reasons: `stored` carries no row identity, so a client cannot address it
    to a row without `_clientKey`/`_entryId` being put back beside each entry (and a CREATE has
    no id until after the transaction), and it doubles the response of every stage save for a
    fleet that is often on one bar. What is NOT defensible is code and prose disagreeing about
    the wire, which is what this deletion ended. Do not reintroduce the variable without
    returning it.
    """
    # THE DESIGNER'S OWN QUESTIONS, LOADED BEFORE ANYTHING IS VALIDATED, because the answers to them
    # arrive in the same payload as the registry ones and are validated in the same pass.
    #
    # NOT `load_definition_or_empty`. A definition this server could not read would make every
    # custom key in the payload an unknown key — dropped, reported as drift, and the designer's
    # fieldwork gone with a 200 beside it. Failing the save is the honest outcome: the phone retries,
    # and nothing is lost. The read paths make the opposite choice, deliberately, and say so.
    #
    # GATHERED WITH THE OTHER TWO READS RATHER THAN AWAITED IN FRONT OF THEM. The three are
    # independent — the definition, this stage's rows, the workshop header — and the database is in
    # another region: one round trip measured 756ms against tables whose server-side time is
    # 0.04-0.24ms, so a third sequential read would have added most of a second to EVERY stage save
    # on the fleet, including for the workshops with no custom section at all, which is nearly all of
    # them. This is a write a designer is standing there waiting for. See `services/concurrency`.
    #
    # SOFT-DELETED ROWS ARE INCLUDED IN `existing`, and that is the whole point of not filtering on
    # deletedAt. The unique index is (designWorkshopId, entityKey, clientKey) and does NOT carry
    # deletedAt, so a soft-deleted row still occupies its client key. Matching only live rows
    # made the matcher blind to it and the save fell through to an INSERT, which the index
    # refused — surfacing as a bare 500 that failed the ENTIRE stage.
    #
    # The path is not exotic. A designer deletes a sketch and undoes it; or a phone that never
    # received the acknowledgement for a sync replays the queue it still holds. Either way the
    # correct behaviour is to RESURRECT the row the client is re-asserting, which is also what
    # keeps the sketch's id — and so the prototypes and reviews that reference it — intact.
    # Inserting a fresh row would have orphaned every one of those references.
    definition, existing, header_row = await gather_reads(
        custom_sections.load_definition(workshop_id),
        db.dwstageentry.find_many(
            where={"designWorkshopId": workshop_id, "stageKey": spec.key}
        ),
        db.designworkshop.find_unique(where={"id": workshop_id}),
    )
    custom_specs = definition.fields_for(spec.key)
    workshop_status = str(getattr(header_row, "status", "DRAFT") or "DRAFT")
    live = [row for row in existing if row.deletedAt is None]
    by_id = {row.id: row for row in live}
    # Keyed over ALL rows, live or not; a live row wins a collision because it is written last.
    by_client_key = {
        (row.entityKey, row.clientKey): row
        for row in sorted(existing, key=lambda r: r.deletedAt is None)
        if row.clientKey
    }

    entities = {e.key: e for e in spec.entities}
    errors: dict[str, Any] = {}
    dropped: list[str] = []
    touched_ids: set[str] = set()
    touched_entities: set[str] = set()
    # ENTITIES WHOSE ENTRIES IN THIS PAYLOAD SAID `merge: true`, HELD SEPARATELY FROM
    # `touched_entities` BECAUSE THEY ANSWER OPPOSITE QUESTIONS.
    #
    # `touched_entities` means "the payload named this entity", and it ARMS the sweep. `merge`
    # means, in `StageEntryIn`'s own words, "I am sending every key I HAVE, not every key there
    # IS" — set "when, and only when, the client knows it has not seen the server's copy". A
    # payload can assert both at once, and until this set existed the server obeyed the second
    # and ignored the first: `entry.merge` was read at exactly one place (the key-level merge
    # below) and had no bearing on which ROWS survived.
    #
    # That combination is not hypothetical. It is the shipped Android BLOCKER of 2026-08-13
    # ("the handset's whole sweep gate was spelled as silence"): the phone sent `merge: true`
    # with `replaceCollections` omitted, the absent key defaulted to TRUE, and three rows the
    # phone had never downloaded were soft-deleted under a 200 reporting `removed: 3`. That
    # client bug was closed by removing a kotlinx default. The server-side contradiction that
    # turned it into row deletion was not, and the same shape is reachable from an older build,
    # a script, or any direct caller that sets merge and leaves `replaceCollections` alone.
    #
    # See the sweep below for why an `emptiedEntities` name still wins: that is a deliberate
    # statement about a whole collection, not an inference drawn from silence.
    merged_entities: set[str] = set()
    # Client keys claimed by an earlier entry of THIS payload, so a client that generated the
    # same id twice collides here rather than inside the unique index as a 500.
    claimed_client_keys: set[tuple[str, str]] = set()
    promoted: dict[str, Any] = {}

    creates: list[dict[str, Any]] = []
    updates: list[tuple[str, dict[str, Any]]] = []
    # Validation, then hydration, then the write — in that order and in three passes, not one.
    # Hydration reads the artisan and product tables, so doing it inside the loop would issue
    # one query per row of a thirty-row participant list. Promotion has to come after it,
    # because stage 1's craft picker is what fills in the craftName that PROMOTED_COLUMNS
    # copies onto the workshop header: computing the promoted values first wrote the pre-picker
    # blank into the column, and the workshop list showed no craft for a record whose stage 1
    # plainly named one.
    pending: list[PendingEntry] = []

    # THE RESERVED CONTAINER, HANDLED BEFORE THE ENTITY LOOP AND NOT INSIDE IT.
    #
    # `_custom` is not a registry entity and never will be — that is the whole design (see
    # `services/custom_sections`): it is a `DwStageEntry` row of its own, one per (workshop, stage),
    # whose `data` is the designer's answers keyed by their own field keys. Handling it here rather
    # than teaching the loop below about it keeps three things true at once: `promoted_values` and
    # `hydrate_entries` never see it, `dropped` never gains its entity key, and the collection sweep
    # cannot reach it because `collection_keys` is derived from `spec.entities`.
    #
    # THE INVARIANT THAT MUST NOT BE WIDENED: the sweep is `(touched_entities | emptiedEntities) &
    # collection_keys`, and `_custom` can never be in `collection_keys`. A later change that widened
    # that set to "every entityKey the workshop has rows for" would soft-delete a workshop's entire
    # custom record on the next save that did not mention it — the same shape as the incident
    # recorded below, where four cost sheets, two buyer links and six prototypes went.
    custom_entry = next(
        (e for e in payload.entries if e.entityKey == custom_sections.CUSTOM_ENTITY_KEY), None
    )
    custom_row = next(
        (r for r in live if r.entityKey == custom_sections.CUSTOM_ENTITY_KEY), None
    ) or next(
        (r for r in existing if r.entityKey == custom_sections.CUSTOM_ENTITY_KEY), None
    )
    # EVERY DECISION ABOUT THE CONTAINER IS MADE IN ONE PURE CALL — what to store, what to refuse
    # and what to report — so the combination of merge, rejected-value preservation and the submit
    # gate is covered by plain pytest with no Postgres, which is where the subtlety actually lives.
    custom_write = custom_sections.plan_custom_write(
        custom_specs,
        # None and {} are different instructions: no entry at all writes no row (which is what a
        # client one release behind sends), while an empty container is a designer clearing every
        # answer and IS written.
        sent=None if custom_entry is None else (custom_entry.data or {}),
        previous=dict(custom_row.data or {}) if custom_row is not None else {},
        merge=bool(custom_entry is not None and custom_entry.merge),
        submit=payload.submit,
    )
    custom_dropped = list(custom_write.dropped)
    custom_to_store = custom_write.data

    if custom_write.errors:
        # Under the reserved key itself, which preserves the existing error shape — `entity.key` for
        # a singleton, `f"{entity.key}[{index}]"` for a collection row — so both clients' existing
        # error rendering works unchanged. There is no collision with a core field key because the
        # bucket is separate.
        errors[custom_sections.CUSTOM_ENTITY_KEY] = custom_write.errors

    for index, entry in enumerate(payload.entries):
        if entry.entityKey == custom_sections.CUSTOM_ENTITY_KEY:
            # Not appended to `dropped`: this is not an entity this build does not know, it is the
            # reserved one, and reporting it as drift would fire "this phone is running a newer
            # field registry than the server" on every save of every workshop that has a custom
            # section — destroying the one drift signal this repository has.
            continue
        entity = entities.get(entry.entityKey)
        if entity is None:
            # An entity this build does not know: recorded, not fatal. See the module docstring
            # of app/schemas/design_workshops.py for why a stage sync must never 422 wholesale.
            dropped.append(entry.entityKey)
            continue
        touched_entities.add(entity.key)

        known = {f.key for f in entity.fields}
        # Underscore-prefixed keys are the sync protocol's own — _clientKey, _entryId, _ordinal
        # — not workshop data. Reporting them as dropped fields would put a line in every
        # response for something working exactly as designed, and would train whoever reads
        # droppedKeys to ignore it, which is the one thing it must not become: it is how a
        # server notices a phone is running a newer registry than it is.
        dropped.extend(
            f"{entity.key}.{k}" for k in entry.data
            if k not in known and not k.startswith("_")
        )

        clean, entry_errors = validate_entry(
            entity, entry.data, enforce_required=payload.submit
        )
        if entry_errors:
            key = entity.key if entity.cardinality is Cardinality.SINGLETON \
                else f"{entity.key}[{index}]"
            errors[key] = entry_errors
            # Keep going. A stage with one bad number still saves its other twenty fields; the
            # alternative loses everything the designer typed because of one typo.

        if entry.merge:
            merged_entities.add(entity.key)

        row = None
        if entry.entryId:
            row = by_id.get(entry.entryId)
            # THE ENTRY ID ARM IS GUARDED THE WAY THE OTHER TWO ALREADY WERE, and it was the only
            # one that was not. `by_client_key` is keyed by `(entityKey, clientKey)` and the
            # singleton fallback filters on `r.entityKey == entity.key`; `by_id` is keyed by row
            # id ALONE, over every live row of the stage whatever entity it belongs to.
            #
            # WRONG ENTITY. Stage 5 declares `processStep`, `tool` and `rawMaterial`. A caller
            # that lets an `_entryId` cross collections — a bulk import, a repair script, a draft
            # migration that re-keys rows — sends a `tool` entry carrying a `processStep` row's
            # id, and nothing downstream recovers: the UPDATE writes `data`, `ordinal` and
            # `deletedAt` and never `entityKey`, so the process-step row keeps its entity while
            # its answers become a tool's, `validate_entry` cannot object because it validated
            # against the entity the PAYLOAD named, and the real tool row — named by nothing in
            # `touched_ids` — is soft-deleted by the sweep as a row the client no longer has. One
            # 200 reading `saved: 1, removed: 1`, one row of fieldwork replaced by another
            # entity's answers and one deleted, and the corrupted row then prints in the
            # process-step table of the .docx with a tool's fields in it.
            #
            # TWICE IN ONE PAYLOAD. Two entries carrying one id both resolved through `by_id`,
            # both became `(row_id, …)` update tuples, and the transaction applied them in order
            # so the SECOND entry's data won wholesale — one row destroyed, `saved: 2` reported,
            # nothing in `dropped` and nothing in `errors`. `_clientKey` has been guarded against
            # exactly this since the duplicate-key 500 (three lines below); `entryId` has no
            # unique index to fail loudly, so it lost the answers quietly instead. The path is
            # ordinary the moment anything copies a row's `data` to make a second row: `_entryId`
            # is put INTO that data by the stage read and by `assemble_workshop_data`.
            #
            # `touched_ids` IS THE CLAIM SET and no second set is kept, because it is populated
            # at exactly one place — the match below — and therefore holds precisely the rows
            # earlier entries of this payload have already taken. That also catches the mixed
            # case (entry 1 matched a row by client key, entry 2 names the same row by id),
            # which a set of only-entryId claims would miss.
            #
            # BOTH ARMS FALL THROUGH RATHER THAN FAILING THE ENTRY. Setting `row = None` sends it
            # to the clientKey lookup and then to a create, so the designer's answers are still
            # written — under their real identity, or as a new row — which is the same recovery
            # the duplicate-clientKey branch chose and for the same reason. The refusal is
            # reported in `dropped`, which is the channel both clients already render.
            if row is not None and row.entityKey != entity.key:
                dropped.append(
                    f"{entity.key}._entryId={entry.entryId} (belongs to {row.entityKey})"
                )
                row = None
            elif row is not None and row.id in touched_ids:
                dropped.append(f"{entity.key}._entryId={entry.entryId} (duplicate in payload)")
                row = None
        client_key = str(entry.data["_clientKey"]) if entry.data.get("_clientKey") else None
        if row is None and client_key:
            row = by_client_key.get((entity.key, client_key))
            if row is None and (entity.key, client_key) in claimed_client_keys:
                # A SECOND entry in THIS payload already claimed that key. Two rows cannot share
                # one client key — the unique index says so — and letting this one through
                # produced a raw 500 that failed the whole stage. The row is still saved, under
                # no client key, so the designer's work survives; the collision is reported so a
                # client generating duplicate ids can be found and fixed.
                dropped.append(f"{entity.key}._clientKey={client_key} (duplicate in payload)")
                client_key = None
        if row is None and entity.cardinality is Cardinality.SINGLETON:
            # A stage's singleton is unique by entity, so a live one is preferred and a
            # soft-deleted one is resurrected rather than duplicated.
            row = next((r for r in live if r.entityKey == entity.key), None) or \
                next((r for r in existing if r.entityKey == entity.key), None)

        ordinal = entry.ordinal if entry.ordinal is not None else index
        if entity.cardinality is Cardinality.SINGLETON:
            ordinal = 0

        previous: dict[str, Any] = {}
        if row is not None:
            touched_ids.add(row.id)
            previous = dict(row.data or {})
            if entry_errors:
                # A REJECTED FIELD MUST NOT DESTROY THE VALUE ALREADY STORED UNDER IT.
                #
                # `validate_entry` omits a field it could not read, so writing `clean` wholesale
                # deleted the good value the designer had saved earlier: type "6500", save,
                # later fat-finger "65OO", and the price is not merely un-updated, it is GONE —
                # while the response reports a validation error, which reads as "your edit was
                # rejected", not as "your earlier answer was deleted". The rejected keys keep
                # whatever the row already held; every other key on the entry still saves,
                # because losing twenty good fields to one typo is the failure this branch was
                # written to avoid in the first place.
                for bad_key in entry_errors:
                    if bad_key in previous:
                        clean[bad_key] = previous[bad_key]

        if entry.merge and previous:
            # "I AM SENDING EVERY KEY I HAVE, NOT EVERY KEY THERE IS."
            #
            # A client that has never downloaded this stage holds a blank form, and a blank form
            # is indistinguishable on the wire from a stage somebody deliberately emptied. Both
            # clients show a banner saying so and both promised that what is left blank would not
            # overwrite an answer recorded elsewhere; without this branch they could not keep it,
            # because the update below replaces a singleton's `data` WHOLESALE. Typing one field
            # into an unread stage deleted every other field the office had written — in place,
            # with no RecordRevision to recover it — and `_coerce_promoted` then nulled
            # craftName, state, district and the dates along with it, so the workshop fell out of
            # every filter and the report cover printed blank.
            #
            # Applied HERE, before `pending`, so the merged dict is the one that reaches BOTH the
            # row and the promoted columns — two readers that must not disagree about what was
            # just written. (There was a third, a `stored` echo block; it was never returned and
            # has been deleted. See this function's docstring.)
            #
            # `clean` wins every key it holds: this fills the gaps in what the client sent, it
            # never overrides it. An empty string the designer actually typed is a value and stays.
            clean = {**previous, **clean}

        pending.append(PendingEntry(
            entity=entity, data=clean, previous=previous,
            row_id=row.id if row is not None else None,
            ordinal=ordinal, client_key=client_key,
        ))
        if client_key:
            claimed_client_keys.add((entity.key, client_key))

    # The chosen references write their display fields onto the entries here, BEFORE anything is
    # serialised for the database and before the promoted columns are read out of them. `clean`
    # is mutated in place, so the same dict reaches the row and the promoted columns.
    #
    # NOTE THAT THE CLIENT DOES NOT SEE THE RESULT OF THIS until it reads the stage back: the
    # save response carries counts, not values (see the docstring). Hydration is exactly where
    # that costs something, because coercion can change what the client sent.
    await hydrate_entries(pending)

    for item in pending:
        promoted.update(promoted_values(item.entity.key, item.data))
        if item.row_id is not None:
            # deletedAt is cleared unconditionally: the client is asserting this row exists, and
            # for a row that was never deleted writing None over None costs nothing. Clearing it
            # only when the row looked deleted would mean reading a value that another request
            # may have changed between the SELECT above and this UPDATE.
            updates.append((
                item.row_id,
                {"data": _json(item.data), "ordinal": item.ordinal, "deletedAt": None},
            ))
        else:
            creates.append({
                "designWorkshopId": workshop_id,
                "stageKey": spec.key,
                "entityKey": item.entity.key,
                "ordinal": item.ordinal,
                "data": _json(item.data),
                "clientKey": item.client_key,
                "createdById": user.id,
            })

    # THE CUSTOM ROW'S OWN WRITE, built exactly like a singleton's and deliberately reusing all
    # three of the rules the loop above applies — because the row's keys are TOP LEVEL, which is the
    # single property that made this design cheaper than nesting the container inside a core entry:
    #
    #   * the rejected-value preservation loop works verbatim. Typing "65OO" over a saved "6500"
    #     must not delete the good value, and the keys it indexes `previous` by are the same keys;
    #   * the shallow `{**previous, **clean}` merge is already correct for a never-read client,
    #     where a nested container would have needed a recursive merge written for one key;
    #   * `MAX_FIELD_KEYS` already bounds it, where a nested object is one key and could have
    #     carried ten thousand.
    #
    # A soft-deleted custom row is RESURRECTED rather than duplicated, exactly as a singleton is:
    # the row is addressed by (workshop, stage, `_custom`) and there is only ever one of it. A
    # payload that carried no custom entry writes nothing here at all — which is what makes a client
    # one release behind harmless rather than destructive.
    if custom_to_store is not None:
        if custom_row is not None:
            updates.append((
                custom_row.id,
                {"data": _json(custom_to_store), "ordinal": 0, "deletedAt": None},
            ))
        else:
            creates.append({
                "designWorkshopId": workshop_id,
                "stageKey": spec.key,
                "entityKey": custom_sections.CUSTOM_ENTITY_KEY,
                "ordinal": 0,
                "data": _json(custom_to_store),
                "clientKey": None,
                "createdById": user.id,
            })

    # Rows the client no longer has, in the entities it actually sent. Restricting the sweep to
    # touched entities is what keeps a web form editing one collection from deleting another.
    removed: list[str] = []
    if payload.replaceCollections:
        # THE SWEEP IS SCOPED BY WHAT THE PAYLOAD NAMES — the entities it sent rows for, plus the
        # ones it explicitly says it has emptied. Nothing else.
        #
        # It was briefly scoped by the STAGE SPEC instead, so `replaceCollections` swept every
        # collection the stage declares whether or not the payload had ever mentioned it. That
        # was written for a real failure (below) and caused a worse one: a caller that sent one
        # entity's rows silently soft-deleted every OTHER collection of the same stage. PUT
        # COSTING_MARKET_LINKAGE with a single costSheet row — `replaceCollections` omitted, and
        # the schema default is TRUE — deleted every buyerLink, costMaterialLine and
        # costLabourLine on the server; the response said `removed: 1` and the UI said the stage
        # was saved. The flagship seeded workshop lost its four cost sheets, its two buyer links
        # and its six prototypes that way, and its report printed 28 unattributed material lines
        # with no cost sheet, no product, no total and no price. A payload that never named an
        # entity must never be able to delete it — that is the contract `StageSaveIn` documents.
        #
        # THE FAILURE THE SPEC-WIDE SWEEP WAS WRITTEN FOR is still fixed, by `emptiedEntities`.
        # `touched_entities` is only ever populated from the entries actually sent, so a
        # collection with ZERO incoming rows is invisible in `entries`: the web builds them from
        # `collections[entity.key] ?? []` and the phone from `.orEmpty()`. That is exactly the
        # shape both clients send once the designer deletes the LAST row of a collection, and it
        # used to report `removed: 0` while the rows stayed alive — back on the next load, and
        # printed in the .docx handed to the ministry. With no per-row delete endpoint no client
        # action could remove them. A client that empties a collection now NAMES it, which the
        # web already tracks per entity as `removedFrom` and only has to send.
        #
        # A singleton is never swept whichever list names it: it is updated in place or created,
        # never deleted by omission — otherwise saving a stage's collection would erase its
        # narrative.
        collection_keys = {
            e.key for e in spec.entities if e.cardinality is Cardinality.COLLECTION
        }
        # AN ENTITY WHOSE OWN ENTRIES SAID `merge: true` IS NOT SWEPT, because the payload is then
        # making two contradictory claims about it and only one of them can be honoured: "I have
        # not seen the server's copy" (the entry) and "delete every row I did not name" (the
        # absent `replaceCollections`, whose default is TRUE). Obeying the destructive half of a
        # contradiction is how three rows a phone had never downloaded were soft-deleted under a
        # 200. See `merged_entities` above for the incident.
        #
        # `emptiedEntities` IS SUBTRACTED BACK IN AFTERWARDS AND THAT ORDER MATTERS. Naming a
        # collection as emptied is an explicit statement about the whole of it — the web tracks it
        # per entity as `removedFrom` and the phone as `emptiedEntities` — and it is the ONLY way
        # a client can delete the last row of a collection, there being no per-row delete
        # endpoint. A client that both empties a collection and merges it is not contradicting
        # itself; it is saying "I hold none of these, and I mean none".
        sweep_entities = (
            (touched_entities - merged_entities) | set(payload.emptiedEntities)
        ) & collection_keys
        # Over `live`, not `existing`: a row that was already soft-deleted must not be counted
        # as newly removed, or every save would report a growing `removed` tally for rows
        # deleted weeks ago and rewrite their deletedAt to today, destroying the record of when
        # the designer actually removed them.
        for row in live:
            if row.entityKey not in sweep_entities:
                continue
            if row.id in touched_ids:
                continue
            entity = entities.get(row.entityKey)
            if entity is None or entity.cardinality is Cardinality.SINGLETON:
                continue
            removed.append(row.id)

    async with db.tx() as tx:
        # One transaction, because a 22-stage submit is a many-statement write and a failure
        # halfway through would otherwise leave a stage that is neither the old data nor the
        # new. Nothing else in this repository uses db.tx() yet; this is the first place that
        # genuinely needs it.
        for row_id, data in updates:
            await tx.dwstageentry.update(where={"id": row_id}, data=data)
        for data in creates:
            await tx.dwstageentry.create(data=data)
        if removed:
            await tx.dwstageentry.update_many(
                where={"id": {"in": removed}}, data={"deletedAt": datetime.now(UTC)}
            )
        header: dict[str, Any] = {"schemaVersion": registry_version()}
        header.update(_coerce_promoted(promoted, touched_entities))
        # DRAFT is the ONLY status a stage save advances, and it advances it exactly once. The
        # record now has content, and a list that still calls it a draft after two weeks of
        # capture is misleading — but forcing IN_PROGRESS unconditionally silently demoted a
        # workshop the designer had marked COMPLETE, or SUBMITTED, or ARCHIVED, every time
        # anybody touched a stage. Correcting a typo in a submitted report should not un-submit
        # it. The later statuses are the designer's to set, through PATCH, and only theirs.
        if workshop_status == "DRAFT":
            header["status"] = "IN_PROGRESS"
        await tx.designworkshop.update(where={"id": workshop_id}, data=header)

    rows = await entry_rows(workshop_id, stage_key=spec.key)
    completeness = workshop_completeness(rows, definition=definition).get(spec.key)
    # AN AUDIO FIELD ON A STAGE IS A RECORDING, AND A RECORDING GETS TRANSCRIBED. This is the one
    # line that puts a workshop clip into exactly the pipeline an interview recording has always
    # used — the same job table, the same off-peak window, the same provider chain and backoff. It
    # is here, on the save, rather than at upload time because the upload does not yet know the
    # clip is workshop audio: the phone records into the generic media route and only this write
    # says "that file is the artisan's spoken explanation for stage 5". It is idempotent (a clip
    # that has a transcript, or a job already queued, is skipped) and it never raises, so a queue
    # that is unavailable cannot fail a designer's stage save. See services/workshop_transcripts.py.
    #
    # AND IT IS GATED ON THE ARTISAN'S CONSENT, which is what `design_workshop_id` is doing here. Until
    # this argument existed, this line reported `transcriptionsQueued: 1` on a workshop whose
    # `dictationConsent` was NOT_RECORDED — in the same minute that `POST /{id}/dictate` refused the
    # same workshop with a 409 and the sentence "nobody has recorded yet whether recordings from this
    # workshop may be sent". The id is right here in the path, so nothing needs resolving: it is handed
    # down and `enqueue_media_processing_jobs` refuses any clip whose workshop has not answered GRANTED.
    queued = await enqueue_stage_transcriptions(
        rows, user.id, viewer=user, design_workshop_id=workshop_id
    )
    # WHY NOTHING WAS QUEUED, WHEN THE REASON IS A PERSON'S DECISION RATHER THAN A NIGHT NOT YET COME.
    #
    # `transcriptionsQueued: 0` is ambiguous by construction — it is also what a re-save of an
    # already-queued stage reports, which is the common case and is fine. Silence is not fine when the
    # cause is the consent gate: the recording sits on the transcripts screen with no text, and
    # `report_annexures.missing_transcript_note` counts it among the clips that "have no transcript
    # yet", so a designer waits for a night that never arrives and reports the feature as broken. It is
    # not broken; nobody has asked the artisan. That is a sentence, and it belongs on the screen the
    # designer is looking at when they attach the recording.
    #
    # ASKED ONLY WHEN THIS STAGE ACTUALLY HOLDS AUDIO, so no read is spent on the twenty stages that
    # carry none, and no message is shown to a designer who was not trying to transcribe anything.
    consent_note = ""
    if audio_references(rows):
        verdict = await dictation_consent.workshop_send_verdict(workshop_id, about=spec.key)
        if not verdict.may_send:
            consent_note = verdict.refusal or ""
    return {
        "stageKey": spec.key,
        "saved": len(creates) + len(updates),
        "created": len(creates),
        "updated": len(updates),
        "removed": len(removed),
        "errors": errors,
        # THE HEADLINE NUMBER, COUNTED ONCE ON THE SERVER SO THE TWO SURFACES CANNOT DISAGREE ABOUT
        # IT. `errors` is a nested map and both clients were deriving their own total from it — the
        # web counting scopes, Android counting fields — so one refused stage entry with three bad
        # values was "1 answer" on a laptop and "3 answers" on the phone. See `refused_answer_count`
        # for why fields is the right reading. `errors` is unchanged and stays the authority on WHICH
        # box is marked; this is only the total, which nothing else in the response carried.
        "refusedAnswers": refused_answer_count(errors),
        "droppedKeys": sorted(set(dropped)),
        # CUSTOM DRIFT GETS ITS OWN FIELD AND ITS OWN SENTENCE, AND NEVER `droppedKeys`.
        #
        # `droppedKeys` is the only client/server registry-drift signal this repository has, and both
        # clients render it in exactly those words: "this phone is running a newer field registry
        # than the server". A custom key the server's definition does not carry is a DIFFERENT fact
        # with a different remedy — the definition was edited, not the app — and feeding it into that
        # signal would fire the banner on every save of every workshop that has a custom section.
        # The people who read that banner would learn to ignore it, which is precisely what the note
        # above the dropped-key computation says must never happen.
        #
        # Safe on every client that has never heard of it: Android decodes with
        # `ignoreUnknownKeys = true` and the web reads only the keys it names.
        "droppedCustomKeys": sorted(set(custom_dropped)),
        "completeness": completeness,
        "transcriptionsQueued": len(queued),
        # The gate's own sentence when this workshop's recordings may not be sent, and "" otherwise.
        #
        # A STRING AND NOT A BOOLEAN, for the reason `dictation_consent.gate_refusal` gives: the two
        # states that refuse have different next moves — one is answered by asking the artisan, the
        # other only by the artisan changing their mind — and a flag would collapse them into one
        # message a client would have to invent. Empty rather than absent so a client can branch on
        # truthiness, and additive so every shipped build ignores it (Android decodes with
        # `ignoreUnknownKeys = true`; the web reads only the keys it names).
        "transcriptionConsentRefusal": consent_note,
        "schemaVersion": registry_version(),
        # The digest of the definition this save was validated against, so a client can tell its
        # cached copy is stale without a second request. Empty for a workshop that has none, which
        # is a different fact from "I hold nothing" and is why it is not omitted.
        "customSchemaVersion": definition.version,
    }


def _json(data: dict[str, Any]) -> Any:
    """Wrap a dict for a Prisma Json column.

    A raw dict reaching a Json column is a 500, not a 422 — the driver raises rather than
    validating — so every write goes through here.
    """
    from prisma import Json

    return Json(data)


def _coerce_promoted(promoted: dict[str, Any], touched_entities: set[str]) -> dict[str, Any]:
    """Convert promoted values to the column types ``DesignWorkshop`` declares.

    A column whose source entity was part of this save but whose value is now blank is set back
    to NULL rather than skipped. Skipping it made a promoted column write-once: a designer who
    typed the wrong sanction number, cleared the field and saved found the list still showing
    the wrong number, with the JSON and the column permanently disagreeing about the same fact —
    the exact drift the promoted columns' single-writer rule exists to prevent.

    Columns whose entity was NOT in this payload are left alone entirely: saving stage 13 must
    not blank the cover fields that stage 1 owns.

    ``title`` IS THE ONE EXCEPTION, and not out of taste: it is the only promoted column
    ``DesignWorkshop`` declares NOT NULL, so nulling it is not a blank cell, it is a Prisma
    ``MissingRequiredValueError`` — a bare 500 that fails THE ENTIRE STAGE SAVE. Every ordinary
    path reaches it. A designer opens stage 1 on a workshop created from the title box, fills in
    the craft and the cluster, and saves before typing the workshop title into the stage's own
    field: `workshopTitle` is blank, `title` is set to NULL, and two dozen filled-in fields are
    lost to a 500 with no indication which of them caused it. The workshop keeps the title it
    has until the stage supplies another one.
    """
    out: dict[str, Any] = {}
    for path, column in PROMOTED_COLUMNS.items():
        entity_key = path.partition(".")[0]
        if entity_key not in touched_entities:
            continue
        value = promoted.get(column)
        if value in (None, ""):
            if column == "title":
                continue
            out[column] = None
        elif column in ("startDate", "endDate"):
            try:
                out[column] = datetime.fromisoformat(str(value)[:10]).replace(tzinfo=UTC)
            except ValueError:
                out[column] = None
        else:
            out[column] = str(value)[:220]
    return out


# --------------------------------------------------------------------------------------
# Completeness
# --------------------------------------------------------------------------------------


def workshop_completeness(
    entries: list[Any], *, definition: Any = None
) -> dict[str, Any]:
    """Score every stage from the entry rows, in one pass.

    ``definition`` is this workshop's custom sections, as ``custom_sections.load_definition``
    returns them. **Optional, and its absence means "this workshop has no designer-defined fields"
    rather than "do not count them"** — which is true of every workshop that has never had one, and
    is what lets the five call sites be converted without a flag day. Every call site that can reach
    the definition passes it; a caller that could not would silently report a stage as complete that
    the submit gate then refuses, so there is deliberately no third state here to get wrong.
    """
    singletons: dict[str, dict[str, Any]] = {}
    collections: dict[str, dict[str, list[dict[str, Any]]]] = {}
    # THE RESERVED ROW IS PICKED OUT HERE, and this is the third of the four places the design's
    # price is paid. Without it the `_custom` row falls into the `else` branch below and is scored
    # as a COLLECTION row of an entity the registry does not know — which is harmless in that the
    # collection loop only walks `spec.collections`, and wrong in that the answers would then be
    # counted by nothing at all.
    custom_values: dict[str, dict[str, Any]] = {}
    cardinality = {e.key: e.cardinality for s in stages() for e in s.entities}

    for row in entries:
        data = dict(row.data or {})
        if row.entityKey == custom_sections.CUSTOM_ENTITY_KEY:
            custom_values[row.stageKey] = data
        elif cardinality.get(row.entityKey) is Cardinality.SINGLETON:
            singletons[row.stageKey] = data
        else:
            collections.setdefault(row.stageKey, {}).setdefault(row.entityKey, []).append(data)

    fields_by_stage = definition.fields_by_stage() if definition is not None else {}

    out: dict[str, Any] = {}
    for spec in stages():
        score = stage_completeness(
            spec, singletons.get(spec.key, {}), collections.get(spec.key, {}),
            custom_fields=fields_by_stage.get(spec.key, ()),
            custom_values=custom_values.get(spec.key, {}),
        )
        out[spec.key] = {
            "stageKey": score.stage_key,
            "number": spec.number,
            "title": score.title,
            "requiredTotal": score.required_total,
            "requiredFilled": score.required_filled,
            "optionalTotal": score.optional_total,
            "optionalFilled": score.optional_filled,
            "percent": score.percent,
            "isComplete": score.is_complete,
            "collectionCounts": score.collection_counts,
            "missing": list(score.missing),
        }
    return out


# --------------------------------------------------------------------------------------
# Reports
# --------------------------------------------------------------------------------------


def assemble_workshop_data(record: Any, entries: list[Any]) -> WorkshopData:
    """Turn stage entry rows into the shape the report builder reads.

    The same shape the phone assembles from its local draft, which is what makes the on-device
    report and the server report the same document rather than two similar ones.
    """
    cardinality = {e.key: e.cardinality for s in stages() for e in s.entities}
    singletons: dict[str, dict[str, Any]] = {}
    collections: dict[str, dict[str, list[dict[str, Any]]]] = {}

    for row in sorted(entries, key=lambda r: r.ordinal):
        data = dict(row.data or {})
        # THE ROW'S OWN ID TRAVELS WITH IT, under the same `_`-prefixed name the stage GET uses.
        #
        # Without it the builder cannot resolve an intra-workshop reference, and eleven REF fields
        # that the report prints as table columns printed a raw cuid instead of a name: a cost
        # sheet headed `cmsik2jg8000eh8xc1lcy661a` rather than "Bandha table runner". The target of
        # every one of those refs is another row of THIS workshop and is already in `entries` — the
        # only thing missing was the key to match it by. The registry never declares a field
        # beginning with `_`, so this cannot collide with captured data and is ignored by every
        # renderer that walks the field list.
        data["_entryId"] = row.id
        if row.entityKey == custom_sections.CUSTOM_ENTITY_KEY:
            # THE RESERVED ROW BELONGS IN NEITHER BUCKET, and this is the fourth of the four places
            # the design's price is paid. Left to fall through it would land in `collections` as a
            # collection of an entity the registry does not know — never printed, because
            # `_render_stage` walks `spec.collections`, and never scored, because the completeness
            # loop does the same, so a designer's answers would be silently absent from the document
            # they were captured for. They reach the report on their own attribute instead, paired
            # with the definition that says what each key was asking: see
            # `attach_report_custom_sections`, which reads these same rows.
            continue
        if cardinality.get(row.entityKey) is Cardinality.SINGLETON:
            singletons[row.stageKey] = data
        else:
            collections.setdefault(row.stageKey, {}).setdefault(row.entityKey, []).append(data)

    return WorkshopData(
        workshop_id=record.id,
        title=record.title,
        singletons=singletons,
        collections=collections,
        generated_at=datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
    )


def reference_ids(entries: list[Any]) -> dict[str, set[str]]:
    """``model -> the ids that model's REF fields hold``, found through the registry.

    Pure, and separated from the loading below so it can be tested without a database. Walking
    the registry rather than scanning every value for something cuid-shaped is the same rule
    :func:`_media_ids` follows and for the same reason: a new REF field is picked up the day it
    is declared, and a text field that happens to contain an id is never mistaken for one.

    Only the external models in :data:`REFERENCE_MODELS` are collected. A ``Dw…`` ref_model points
    at another entry OF THIS SAME WORKSHOP — ``prototype.sketchRef`` at a sketch — and that record
    is already in ``entries``; looking it up as though it were an artisan would query a delegate
    that does not exist.
    """
    ref_keys: dict[str, dict[str, str]] = {}
    for spec in stages():
        for entity in spec.entities:
            keys = {f.key: f.ref_model for f in entity.fields
                    if f.type is FieldType.REF and f.ref_model in REFERENCE_MODELS}
            if keys:
                ref_keys[entity.key] = keys

    found: dict[str, set[str]] = {}
    for row in entries:
        keys = ref_keys.get(row.entityKey)
        if not keys:
            continue
        data = row.data or {}
        for key, model in keys.items():
            value = data.get(key)
            if isinstance(value, str) and value:
                found.setdefault(model, set()).add(value)
    return found


def _reference_place(row: Any) -> tuple[str, str, str]:
    """``(place, district, state)`` for one referenced record, from wherever it states them.

    THE STATED ADDRESS, NEVER THE PROVENANCE COORDINATE. ``Location`` carries both — where the
    device was when the record was captured, and where the subject says they live — and the two
    are routinely a hundred kilometres apart, because a researcher interviews six artisans in one
    afternoon at a cooperative hall. Reading the fix would draw every one of those six on the
    hall, and the map would report a workshop whose artisans all live in the same village.

    ``place`` is the free-text fallback the artisan table carried before the stated-address
    columns existed, and it is still the only thing filled in on the older half of the corpus.
    """
    location = getattr(row, "location", None)
    village = str(getattr(location, "village", "") or "") if location is not None else ""
    district = str(getattr(location, "district", "") or "") if location is not None else ""
    state = str(getattr(location, "state", "") or "") if location is not None else ""
    return (village or str(getattr(row, "place", "") or ""), district, state)


async def _load_one_reference_model(
    spec: ReferenceModel, ids: set[str], model: str
) -> tuple[list[Any], dict[str, str]]:
    """One reference model's rows and their photographs, or ``([], {})`` if it could not be read.

    Split out of :func:`load_report_references` so the three models can be gathered. The failure
    boundary is deliberately HERE, around one model's pair of reads, exactly where the loop it
    replaced put it.
    """
    try:
        rows = await getattr(db, spec.delegate).find_many(
            where={"id": {"in": sorted(ids)}}, include=spec.include or None
        )
        return rows, await _reference_photos(spec, [r.id for r in rows])
    except Exception:
        # Blind, and it has to be: Prisma raises a different class for a delegate whose table
        # has been renamed, a connection that dropped mid-report and a record whose include
        # no longer matches the schema, and the answer to all three is the same. One
        # unjoinable model must not lose a report that is the end of two weeks of fieldwork.
        logger.exception("Could not load %s references for a workshop report", model)
        return [], {}


async def load_report_references(entries: list[Any]) -> dict[str, ReferencedRecord]:
    """Everything the report needs about the records its REF fields point AT, in one query each.

    Two facts, and neither of them can come from the stage entry itself:

    * THE PHOTOGRAPH OF A RECORD HYDRATION DOES NOT SEED. ``prototype.productRef`` and
      ``existingProduct.artisanRef`` copy a name and nothing else, deliberately, because the
      entities they write onto own a gallery of the designer's photographs that a seeded picture
      would overwrite. Without this load the report described a prototype of a documented product
      with the product's photograph nowhere in it, one join away in the media table.
    * WHERE AN ARTISAN LIVES. No roster field holds a district — the participant row records a
      village as free text — so the map of who came from where cannot be drawn from the entries.

    One ``find_many`` per model, not one per row: a roster of forty artisans is one query. The
    three models are then GATHERED rather than walked in sequence — see the comment below. Failure
    of any single model is swallowed to a log line rather than raised, because a report is the end
    of two weeks of fieldwork and losing it entirely over a picture that could not be joined is
    the wrong trade — the map loses pins, the caption says how many, and the document still prints.
    """
    wanted = reference_ids(entries)
    out: dict[str, ReferencedRecord] = {}
    # THE THREE MODELS ARE GATHERED, NOT LOOPED. Each is two dependent reads (the rows, then one
    # photograph per row) and nothing in one model's pair informs another's, so sequentially this
    # was up to six round trips at ~756ms each on the cross-region link this deployment runs
    # (`services/concurrency`) for work the graph allows in two. The `try/except` stays INSIDE the
    # per-model coroutine, which is what keeps the documented guarantee intact: one unjoinable
    # model still loses only its own references, never the report.
    models = sorted(wanted)
    results = await gather_reads(
        *(_load_one_reference_model(REFERENCE_MODELS[model], wanted[model], model)
          for model in models)
    )
    for model, (rows, photos) in zip(models, results, strict=True):
        spec = REFERENCE_MODELS[model]
        for row in rows:
            place, district, state = _reference_place(row)
            out[str(row.id)] = ReferencedRecord(
                model=model,
                label=spec.label(row),
                photo=str(photos.get(row.id) or ""),
                place=place,
                district=district,
                state=state,
            )
    return out


async def attach_report_references(data: WorkshopData, entries: list[Any]) -> list[str]:
    """Load the referenced records onto ``data`` and return the media ids they contributed.

    The ids come BACK rather than being fetched here because they have to reach
    :func:`media_resolver`, and a photograph the builder places but the resolver never looked up
    resolves to None and is silently dropped — which is exactly the "the picture is in the
    database and not in the report" failure this whole path exists to end. Returning them makes
    the dependency visible at the one call site instead of hiding it in two functions that have
    to be kept in step.
    """
    data.references = await load_report_references(entries)
    return [r.photo for r in data.references.values() if r.photo]


def workshop_media_ids(entries: list[Any]) -> set[str]:
    """Every media id this workshop's stage entries reference. The public name for :func:`_media_ids`.

    **A NAME AND NOT A SECOND IMPLEMENTATION**, added when the AI verbs needed to answer "is this
    photograph one of THIS workshop's" before spending provider credit on it. The route could not
    import ``_media_ids`` — a leading underscore across a module boundary is a promise being broken —
    and it must not walk the registry itself, because a second walker is a second thing to forget
    when a media field is added. The plan's §4 records what five independent media walkers already
    cost this repository; this is deliberately not a sixth.
    """
    return _media_ids(entries)


def _media_ids(entries: list[Any]) -> set[str]:
    """Every media id referenced anywhere in the record, found through the registry.

    Walking the registry rather than scanning every value for something id-shaped means a new
    media field is picked up automatically, and a text field that happens to contain a cuid is
    not mistaken for a photograph.
    """
    media_keys: dict[str, set[str]] = {}
    # RICH_TEXT fields carry media too, and NOT in the field's own value: a designer who places a
    # photograph inside a narrative puts its id in an IMAGE block, several levels down in the
    # document JSON. Walking only the media-typed fields would miss every one of them, the
    # resolver would answer None, and `to_report_blocks` would drop the picture — silently, in
    # exactly the way a placed-in-the-prose photograph is most likely to be noticed missing.
    rich_keys: dict[str, set[str]] = {}
    for spec in stages():
        for entity in spec.entities:
            keys = {f.key for f in entity.fields if f.type.is_media}
            if keys:
                media_keys[entity.key] = keys
            rich = {f.key for f in entity.fields if f.is_rich_text}
            if rich:
                rich_keys[entity.key] = rich

    found: set[str] = set()
    for row in entries:
        data = row.data or {}
        for key in media_keys.get(row.entityKey, ()):
            value = data.get(key)
            if isinstance(value, str) and value:
                found.add(value)
            elif isinstance(value, (list, tuple)):
                found.update(str(v) for v in value if v)
        for key in rich_keys.get(row.entityKey, ()):
            found.update(rich_text.media_ids(data.get(key)))
    return found


class MediaIndex:
    """Every image the record references, resolved once.

    Two lookups, both pure by the time a renderer sees them: ``ref(media_id)`` gives the
    geometry, ``blob(ImageRef)`` gives the bytes. Both are plain dict reads, because the
    renderers are deliberately synchronous — that is what lets the Kotlin port be a
    transliteration rather than a reimplementation — and forty awaits cannot happen inside one.

    The object keys are held rather than the bytes until :meth:`prefetch` is called, so a
    template that excludes photographs never pays to download forty of them.

    ``withheld`` is every media id the record NAMED that the lookup did not hand back — either the
    row is gone or the caller is not entitled to that uploader's files. The renderer cannot tell
    the difference and neither can this class; what matters is that the caller turns the count into
    a warning instead of printing a report that is quietly short of photographs.
    """

    def __init__(
        self,
        refs: dict[str, ImageRef],
        keys: dict[str, str],
        *,
        withheld: tuple[str, ...] = (),
    ) -> None:
        self._refs = refs
        self._keys = keys
        self._blobs: dict[str, bytes | None] = {}
        self.withheld = withheld

    def ref(self, media_id: str) -> ImageRef | None:
        return self._refs.get(media_id)

    def blob(self, image: ImageRef) -> bytes | None:
        return self._blobs.get(image.source)

    def prefetch(self, wanted: tuple[ImageRef, ...]) -> None:
        """Download the bytes of exactly the images the built document referenced.

        Synchronous, and called from inside ``asyncio.to_thread`` along with the render itself.
        ``get_object_bytes`` is a blocking boto3 call, so running it on the worker thread is
        both correct and what the rest of this codebase does with S3 reads.
        """
        from app.services.s3 import get_object_bytes

        for image in wanted:
            if image.source in self._blobs:
                continue
            key = self._keys.get(image.source)
            if not key:
                self._blobs[image.source] = None
                continue
            try:
                self._blobs[image.source] = get_object_bytes(key)
            except Exception:  # noqa: BLE001 - one unreadable photo must not fail the export
                # Boto3 raises a different class for a missing key, a permission problem and a
                # timeout, and the answer to all three is the same: leave the picture out and
                # let the caller report it as a warning. A designer waiting in a field for a
                # report does not benefit from an exception naming the S3 error code.
                self._blobs[image.source] = None


async def media_resolver(
    entries: list[Any], *, viewer: Any, extra_ids: Iterable[str] = ()
) -> MediaIndex:
    """One query for every image in the record the caller is entitled to be handed.

    ``extra_ids`` are photographs that belong to a record the report REFERENCES rather than to a
    field of the record itself — an artisan's portrait behind a roster row, the catalogue picture
    of the product a prototype was based on. They cannot be found by walking the registry's media
    fields because they are not IN the entries at all; :func:`attach_report_references` is what
    discovers them, and passing them here is what stops the builder placing an image the resolver
    has never heard of and the renderer therefore drops without a word.

    ``viewer`` IS REQUIRED, AND THAT IS THE FIX FOR A REAL LEAK. The ids come from stage data,
    which is whatever a client wrote there: ``GET /api/media`` hands every signed-in account the id
    of every photograph in the repository while deliberately stripping the URL, so pasting a
    stranger's id into an IMAGE field and pressing "Generate report" used to put their photograph —
    an artisan's portrait, a photographed Aadhaar card — into the .docx that came back. Embedding
    the bytes in a document the caller downloads IS taking the file, so the predicate is the one
    every other download surface uses (``export.py``, ``data_browser.py``):
    ``owned_or_granted_where(user, owner_field="uploadedById")``. It is a keyword with NO default
    so that a future call site cannot resolve media by forgetting to ask who is asking.

    The filter is AND-composed under the id list rather than merged into it, because it is an
    ``OR`` of its own and a flat merge would let it overwrite the ids.
    """
    from app.services.records import owned_or_granted_where

    ids = _media_ids(entries) | {str(i) for i in extra_ids if i}
    if not ids:
        return MediaIndex({}, {})

    where: dict[str, Any] = {"id": {"in": sorted(ids)}}
    entitled = await owned_or_granted_where(viewer, owner_field="uploadedById")
    if entitled:
        where = {"AND": [where, entitled]}
    rows = await db.mediafile.find_many(where=where)
    refs: dict[str, ImageRef] = {}
    keys: dict[str, str] = {}
    for row in rows:
        # Only pictures. A PDF or an audio file linked to a stage is real data, but embedding
        # it as an image would put a broken frame in the report.
        if str(getattr(row, "mediaType", "")) not in ("IMAGE", "MediaType.IMAGE"):
            continue
        extra = row.extraMetadata or {}
        rotation = _int_or_zero(extra.get("orientationDegrees") or extra.get("rotation"))
        refs[row.id] = ImageRef(
            source=row.id,
            width_px=_int_or_zero(extra.get("width")),
            height_px=_int_or_zero(extra.get("height")),
            rotation_deg=rotation if rotation in (90, 180, 270) else 0,
            mime_type=row.mimeType or "image/jpeg",
        )
        keys[row.id] = row.objectKey
    # Asked for and not returned: deleted, or another uploader's file this caller may not take.
    # One query cannot tell those apart and a second one would cost a cross-region round trip on
    # the path `_report_inputs` exists to keep short, so the caller's warning names neither.
    return MediaIndex(refs, keys, withheld=tuple(sorted(ids - {row.id for row in rows})))


def _int_or_zero(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


async def attach_report_transcripts(
    data: WorkshopData, entries: list[Any], *, viewer: Any, requested: bool | None = None
) -> list[str]:
    """Load the workshop's transcripts onto ``data`` when this report is meant to carry them.

    Returns the warnings to show beside the download — a recording still being transcribed is a
    gap in an annexure the designer explicitly asked for, and they should hear about it from the
    generator rather than by counting paragraphs in a 60-page document.

    Reads the toggle in the same order the whole report pipeline reads everything else: the request
    wins if it said anything, the saved stage-20 settings otherwise, and OFF when neither spoke.
    Nothing is loaded at all in the off case, so a deployment that never turns this on does not pay
    a query for it.

    ``viewer`` gates which recordings may be read at all — see :func:`media_resolver` for why an
    AUDIO id on a stage is not proof the caller may have the file. A clip that is refused is
    reported as a warning rather than dropped, because an annexure two recordings short looks
    exactly like two recordings that were never made.
    """
    if not wants_transcripts(requested, data.singleton("REPORT_GENERATION")):
        return []
    items = await load_transcript_items(entries, viewer=viewer)
    warnings: list[str] = []
    refused = len(audio_references(entries)) - len(items)
    if refused > 0:
        warnings.append(
            f"{refused} recording(s) attached to this workshop could not be included in the "
            "transcript annexure: they were uploaded by another account, or the file is gone."
        )
    if not items:
        return warnings
    attach_transcripts(data, items)
    return warnings + annexure_warnings(items)


async def attach_report_ai_layers(
    data: WorkshopData,
    workshop_id: str,
    *,
    viewer: Any,
    readable_media: Callable[[set[str]], Awaitable[set[str]]],
    requested: bool = False,
) -> list[str]:
    """Load this workshop's ACCEPTED AI layers onto ``data``. Returns the warnings to show.

    NOTHING IS LOADED UNLESS THE REPORT WAS ASKED FOR THE ANNEXURE, so a deployment that never turns
    this on never pays a query for it — the same rule :func:`attach_report_transcripts` follows, and
    for the same reason.

    ── ``viewer`` IS NOT OPTIONAL, AND ITS ABSENCE WAS A LIVE DISCLOSURE DEFECT ──────────────────

    A layer's text is a COPY OF A TRANSCRIPT, and a transcript is the CONTENT of a recording. Who may
    read one is decided **per file** by ``owned_or_granted_where(user, owner_field="uploadedById")``
    and NOT by who may open the workshop — and those two sets genuinely differ, because a
    ``DesignWorkshopViewer`` grant carries read and stage writes and says nothing whatever about
    media. ``ai_layers.accepted_layers`` states in capitals that its read is not entitlement-filtered
    and that its caller must be.

    This function had no ``viewer`` at all and copied ``row.text`` verbatim. So: designer A uploads
    the interviews and accepts their layers; designer B holds only a viewer grant and no data-access
    grant from A. On the AI-layers screen every row comes back ``textWithheld``. ``GET
    /design-workshops/{id}/transcripts`` refuses B the same text. **And B could tick "Include
    machine-assisted text", generate, and receive the complete transcripts in a .docx they keep — in
    the very same file whose transcript annexure correctly omitted them and said so.**

    ``readable_media`` is passed IN rather than imported because the predicate lives in the routes
    module beside the endpoint that already applies it (``_readable_media_ids``); one definition,
    two callers, and this service keeps its rule about not importing the API layer.

    **PROVENANCE SURVIVES WITHHOLDING; ONLY THE CONTENT GOES.** That is the whole reason a withheld
    layer is not simply dropped. Which tier and which model produced a passage, and who accepted it,
    is exactly what a reviewer needs and is not the recording's content — and a layer silently absent
    from an annexure is indistinguishable from a layer nobody ever made.

    THE FILTER IS ``ai_layers.accepted_layers`` AND NOT A ``where`` WRITTEN HERE. That function is
    the single definition of what "accepted" means, and it says why in its own docstring: the day
    acceptance grows a condition — an expiry, a second signature, a withdrawal that must be honoured
    mid-render — a report carrying its own copy of the rule would go on printing by the old one and
    nothing would say so. **It is now actually called.** It was not, for a day: this function read
    ``workshop_layers`` and re-derived acceptance from ``acceptedAt``, so five files described a
    single definition that nothing used and the guarantee was documentation. The live set decides
    what prints; the full set is still read, because the warnings need it.

    THE WARNINGS ARE COMPUTED FROM EVERY LIVE LAYER, not from the accepted ones, which is why this
    reads the workshop twice-over rather than only the accepted set. A layer left out for want of an
    acceptance is the one omission that looks like a bug to the designer who turned the annexure on:
    they read the summary on the acceptance screen an hour ago and it is missing from a sixty-page
    document. That sentence is the whole reason this function is not three lines.

    NO PROVENANCE IS INVENTED HERE. ``provider`` and ``modelId`` are NOT NULL columns that may hold
    the literal ``UNRECORDED``, because the media queue has never persisted which of its four
    providers produced a transcript; the renderer turns that into "the model was not recorded" and
    this function passes it through untouched.
    """
    if not requested:
        return []
    from app.services.ai_layers import (
        LayerRuleViolation,
        accepted_layers,
        chain_roots,
        media_ids_to_check,
        source_of,
        workshop_layers,
    )
    from app.services.report_ai_layers import (
        AiLayerItem,
        annexure_warnings as ai_warnings,
        attach_ai_layers,
    )

    try:
        rows = await workshop_layers(workshop_id)
        # THE SINGLE DEFINITION OF "ACCEPTED", ASKED RATHER THAN RE-DERIVED. Membership of this set
        # is what decides whether a layer may print; `acceptedAt` on the row decides only what the
        # warnings say about it.
        printable = {
            str(getattr(row, "id", "") or "")
            for row in await accepted_layers(workshop_id)
        }
    except Exception:
        # Blind, exactly as :func:`attach_report_questionnaires` is blind, and for its reason: one
        # unreadable annexure must not take away a designer's ability to generate any report at all.
        logger.exception("ai layers could not be loaded for workshop %s", workshop_id)
        return [
            "The machine-assisted text could not be read for this report, so the annexure is not "
            "included. Everything else in this document is unaffected."
        ]

    # WHAT EACH LAYER ULTIMATELY STANDS ON, walked through the chain by `chain_roots`, because a
    # SUMMARY three rungs up is still the content of the audio at the bottom of it.
    #
    # `chain_roots` AND NOT `media_roots`, and the difference is a defect this loader would otherwise
    # have shipped the day the verbs landed. `media_roots` answers None for "no recording", which had
    # one meaning — the chain is broken, so withhold — and now has two: a PROOFREAD or an EXPANDED of
    # a designer's own note stands on words, not on a recording, and has no media entitlement to
    # check. Read through the old function every one of those would be withheld from the designer who
    # wrote the note, in their own report, with the annexure printing "the text of this passage is
    # not printed in this copy" over a paragraph they typed themselves.
    roots = chain_roots(rows)
    wanted = media_ids_to_check(roots)
    try:
        readable = await readable_media(wanted) if wanted else set()
    except Exception:
        # FAIL CLOSED. An entitlement check that could not run is not permission — the content is
        # withheld and the provenance still prints, which is the same answer a refused check gives.
        logger.exception("media entitlement could not be resolved for workshop %s", workshop_id)
        readable = set()

    items: list[AiLayerItem] = []
    withheld_count = 0
    for row in rows:
        layer_id = str(getattr(row, "id", "") or "")
        source_kind = source_id = source_text = ""
        try:
            stored = source_of(row)
            source_kind, source_id = stored.kind.value, stored.id
            source_text = stored.text or ""
        except LayerRuleViolation:
            # A row whose source cannot be read still prints, with the origin left blank. Dropping
            # it would be worse: it is accepted text that a person put their name to, and a reader
            # who cannot trace it is better served than one who never learns it is there.
            pass
        # A layer whose root could not be established at all is withheld, not printed. That is the
        # fail-closed direction, and `ChainRoot.withheld_from` is the single place the whole gate is
        # decided so this loader and the list endpoint cannot answer it differently.
        root = roots.get(layer_id)
        text_withheld = root is None or root.withheld_from(readable)
        if text_withheld:
            withheld_count += 1
        items.append(AiLayerItem(
            layer_id=layer_id,
            kind=str(getattr(row, "kind", "") or ""),
            tier=str(getattr(row, "tier", "") or ""),
            provider=str(getattr(row, "provider", "") or ""),
            model_id=str(getattr(row, "modelId", "") or ""),
            model_version=str(getattr(row, "modelVersion", "") or ""),
            language=str(getattr(row, "language", "") or ""),
            source_language=str(getattr(row, "sourceLanguage", "") or ""),
            target_language=str(getattr(row, "targetLanguage", "") or ""),
            produced_at=_iso_or_blank(getattr(row, "producedAt", None)),
            # `printable`, not `acceptedAt is not None`. See the docstring.
            accepted=layer_id in printable,
            accepted_at=_iso_or_blank(getattr(row, "acceptedAt", None)),
            accepted_by=_acceptor_name(row),
            accepted_by_id=str(getattr(row, "acceptedById", "") or ""),
            source_kind=source_kind,
            source_id=source_id,
            # THE NOTE A VERB WAS RUN OVER IS THE "MADE FROM" EVIDENCE FOR THAT LAYER, so it reaches
            # the page as the source LABEL — there is no row for a reader to look up instead. It goes
            # with the content when the text is withheld, for `layer_payload`'s reason: serving the
            # passage while withholding the correction of it hands over the same words in a slightly
            # worse spelling.
            source_label="" if text_withheld else source_text,
            text="" if text_withheld else str(getattr(row, "text", "") or ""),
            text_withheld=text_withheld,
        ))

    attach_ai_layers(data, items)
    warnings = ai_warnings(items)
    # NAMED, NEVER SILENT — the same rule `attach_report_transcripts` follows one function above for
    # exactly this case. A designer who cannot read a colleague's recordings should learn that from
    # the generator, not by counting headings in a sixty-page document.
    printable_withheld = sum(
        1 for item in items if item.text_withheld and item.accepted
    )
    if printable_withheld:
        warnings.append(
            f"{printable_withheld} accepted machine-assisted passage(s) are named in the annexure "
            "with their model and provenance, but their text is not printed: they stand on "
            "recordings uploaded by another account. Ask whoever uploaded them for access to their "
            "media, or ask them to generate this report."
        )
    return warnings


def _acceptor_name(row: Any) -> str:
    """The acceptor's display name when the row carried the relation, else "".

    RESOLVED FROM AN INCLUDE, NOT FROM A SECOND QUERY PER LAYER. ``accepted_layers`` and
    ``workshop_layers`` may or may not have been asked to include the relation; when they were not,
    this returns "" and :attr:`AiLayerItem.acceptor` prints the account id **labelled as an account
    id**. Both are honest; only a bare unlabelled cuid is not, and that is what the annexure printed
    for a day beneath a lead paragraph promising a named person.
    """
    accepted_by = getattr(row, "acceptedBy", None)
    if accepted_by is None:
        return ""
    for attribute in ("fullName", "name", "email"):
        value = getattr(accepted_by, attribute, None)
        if value and str(value).strip():
            return str(value).strip()
    return ""


def _iso_or_blank(value: Any) -> str:
    """A datetime as an ISO string, or "" when it was never recorded.

    Blank rather than a fabricated ``now()``: ``producedAt`` is null precisely when nobody knows
    when the model ran — a transcript produced last March and registered as a layer today would
    otherwise carry today's date, which is the invented fact rule 2 exists to prevent.
    """
    if value is None:
        return ""
    return value.isoformat() if hasattr(value, "isoformat") else str(value)


async def attach_report_custom_sections(
    data: WorkshopData, entries: list[Any], workshop_id: str
) -> list[str]:
    """Load this workshop's own sections and their answers onto ``data``. Returns the warnings.

    THIS FUNCTION LOADS; IT DOES NOT PLACE. Where each section prints is decided by
    ``report_templates.apply_report_settings``, which is the single arbiter of the running order and
    was made one because three call sites used to decide for themselves and disagreed. It reads the
    sections back off ``data`` through ``custom_sections_of`` — the same tuple attached here, so the
    template and the renderer cannot be looking at two different definitions of one workshop.

    NO TOGGLE TO CONSULT, and for ``attach_report_questionnaires``' reason rather than by omission:
    a designer who added a question to this workshop and answered it has already opted in twice
    over. There is nothing here that could happen TO a report — unlike a transcript, which the media
    queue produces from a recording made for some other purpose, or an annexure of model prose.

    THE ANSWERS ARE READ FROM ``entries`` AND NOT LOADED AGAIN. They are already in hand — the
    ``_custom`` row of each stage is an ordinary ``DwStageEntry`` that ``entry_rows`` returned with
    everything else — so the only query this function makes is for the DEFINITION, which is the half
    that says what each stored key was asking.

    A DEFINITION THAT COULD NOT BE READ COSTS THE SECTIONS AND NOT THE REPORT. That is the opposite
    of the choice ``save_stage`` makes with the same loader, and the two are deliberately different:
    a save that silently dropped a designer's answers as unknown keys would lose fieldwork, while a
    report is the end of two weeks of it and must not be refused over an appendix. The designer is
    told either way.
    """
    definition = await custom_sections.load_definition_or_empty(workshop_id)
    if definition.is_empty:
        return []

    values_by_stage: dict[str, dict[str, Any]] = {
        row.stageKey: dict(row.data or {})
        for row in entries
        if row.entityKey == custom_sections.CUSTOM_ENTITY_KEY
    }

    items: list[CustomSectionItem] = []
    for section in definition.sections:
        item = CustomSectionItem(
            key=section.key,
            title=section.title,
            stage_key=section.stage_key,
            description=section.description,
            sort_order=section.sort_order,
            fields=tuple(
                CustomReportField(
                    key=f.key,
                    label=f.label,
                    type=f.type.value,
                    unit=f.unit,
                    options=tuple((o.value, o.display) for o in f.options),
                    required=f.required,
                    # THE DESIGNER'S CHOICE OF CAPTURE TIER, WHICH THIS LOOP USED TO DROP.
                    #
                    # `CustomFieldSpec.tier` is a real choice in the section editor ("Which
                    # capture tier this question belongs to"), it is validated, it is stored and
                    # it is serialised to both clients — and six attributes were copied here and
                    # this was not, so every custom question arrived at the renderer carrying the
                    # BASIC default and was printed by every template. Every REGISTRY field
                    # passes `ReportBuilder._visible` (`spec.tier.rank <= template.max_tier.rank`);
                    # a designer's own question passed no gate at all. COMPACT_SUMMARY is the only
                    # template whose `max_tier` is not ADVANCED, and its own description promises
                    # "Basic-tier fields only, one photograph per prototype" — so it correctly
                    # suppressed every Standard and Advanced registry field and then printed the
                    # designer's Standard-tier answers underneath in full. One document, two
                    # rules, one declared attribute.
                    #
                    # `.value` AND NOT THE ENUM, because `CustomReportField` is a flat value
                    # object with no server type inside it — the phone builds the same shape from
                    # its own cached definition, and a renderer that could reach `Tier` would not
                    # be transliterable. See that class's docstring.
                    #
                    # THE SCORER IS UNAFFECTED and that is checked rather than assumed:
                    # `custom_scoring` hands `stage_completeness` every field of the stage with no
                    # tier test, deliberately, because completeness is a fact about the workshop
                    # and not about the file being generated. Only the RENDERER filters.
                    tier=f.tier.value,
                    # A RETIRED SECTION'S FIELDS ARE RETIRED, whatever their own flag says, and this
                    # is not cosmetic. A section's flag and its fields' flags are two facts, and only
                    # one of them answers "is this asked" for a field under a section nobody is being
                    # asked — a row retired by hand, restored from a backup, or written by a build
                    # before `plan_definition`'s section RETIRE carried a RETIRE plan for every live
                    # field under it, which is what makes this belt and braces rather than the only
                    # guard. The completeness annexure re-scores every stage through
                    # `custom_scoring`, which reads exactly this flag. Passing the field's own flag
                    # through made the annexure count a required question of a section nobody is
                    # being asked, so a document printed "3 required fields outstanding" for a stage
                    # the readiness screen and `workshop_completeness` both called complete: one
                    # workshop, two
                    # arithmetics, which is the defect this repository has already shipped once.
                    # Marked here rather than in the loader because it is the REPORT's reading of a
                    # retired section: the answers still print, under the wording they were given,
                    # and the marker beside them says they are no longer asked.
                    retired=f.retired or section.retired,
                )
                for f in section.fields
            ),
            values=values_by_stage.get(section.stage_key, {}),
        )
        if section.retired and not item.answered_count:
            # A retired section nobody ever answered is not evidence of anything, and printing an
            # empty heading for every block a designer thought better of would fill a submitted
            # report with the history of its own form.
            #
            # ASKED THROUGH `answered_count` AND NOT THROUGH THE TRUTHINESS OF THE STORED VALUES.
            # The first version tested `values.get(key)` directly, which reads a recorded ZERO as no
            # answer — so a retired "How many looms?" answered "0", which is a finding and a
            # perfectly ordinary one in a cluster where the looms have been sold, was dropped out of
            # the document altogether. `answered_count` is `_has_answer`, which this module uses for
            # every other decision about the same values.
            continue
        items.append(item)

    attach_custom_sections(data, items)
    warnings: list[str] = []
    # THE WARNING ASKS THE RENDERER'S OWN QUESTION AND NOT A SECOND ONE THAT LOOKS LIKE IT.
    #
    # Everything in `items` is attached and spliced into the template unconditionally; whether a
    # section PRINTS is decided solely by `append_custom_section`, which appends nothing when
    # `has_content` is false. `has_content` is true for an answered section OR one with a live
    # required field — deliberately, so an unanswered required question prints "Not recorded."
    # and its absence is visible in the document.
    #
    # This list used to be `not item.answered_count and any(not f.retired …)`, which is true for
    # EXACTLY the class `has_content` prints: zero answers plus at least one live required field.
    # So a designer who added "Dye bath log" with one required question and did not reach it got
    # a .docx containing the heading and "Dye source — Not recorded.", beside a warning saying
    # "1 of this workshop's own section(s) … are not in this file: Dye bath log". They then
    # either hunt for a bug that is not there, or submit the file believing the ministry's copy
    # does not carry the empty block. The warning's whole purpose, per the note below, is the
    # difference between "the feature is broken" and "we did not get to those questions" — and
    # as written it manufactured the first.
    #
    # `has_content` is a property of `CustomSectionItem`, which is already the type of everything
    # in `items`, so this is the renderer's answer rather than a copy of its rule. Do not
    # "simplify" it back into a hand-rolled test: a second copy of the rule is how the two came
    # apart in the first place.
    #
    # IT IS THE TIER-BLIND READING (`has_content` == `has_content_at(ALL_TIERS)`) BECAUSE THIS
    # FUNCTION HAS NO TEMPLATE TO ASK. `apply_report_settings` is what splices these sections in
    # and it can only do that once it has been handed the definition this load produces, so the
    # template is resolved above us and never reaches here. The one case the two still part on is
    # a section whose every question is above COMPACT_SUMMARY's cap: the renderer prints nothing
    # and this warning says nothing. That is the quiet direction of the two, and closing it means
    # threading `max_tier` down from `_report_inputs` — written up rather than done, because the
    # loader taking a rendering argument is a design change and not a fix.
    unanswered = [item for item in items if not item.has_content]
    if unanswered:
        # NAMED RATHER THAN SILENT, for the reason the AI annexure's warning gives: a block the
        # designer added themselves and then finds missing from a sixty-page document reads as a
        # bug in the app. Saying which one, and that it is empty rather than lost, is the difference
        # between "the feature is broken" and "we did not get to those questions".
        warnings.append(
            f"{len(unanswered)} of this workshop's own section(s) have no answers recorded and are "
            f"not in this file: "
            + ", ".join(sorted(item.title for item in unanswered)[:4])
            + ("…" if len(unanswered) > 4 else "")
        )
    return warnings


async def attach_report_questionnaires(data: WorkshopData, workshop_id: str) -> list[str]:
    """Load this workshop's own questionnaires onto ``data``. Returns the warnings to show.

    NO TOGGLE TO CONSULT, unlike :func:`attach_report_transcripts`, and that is the design decision
    rather than a missing parameter. A transcript is produced automatically by the media queue from a
    recording made for some other purpose, so an annexure of them is something that could happen TO a
    designer — hence ``includeTranscripts``. A questionnaire sitting has no such path: the designer
    built the form, attached it to THIS workshop from a dropdown, and typed the answers in.
    **Attaching it is the opt-in**, and ``PATCH {isActive: false}`` or detaching it is the way out.
    See ``report_questionnaires``' module docstring for the argument in full.

    THE QUERY IS PAID ONLY WHEN THE TEMPLATE DRAWS THE SECTION — the caller checks that, exactly as
    it already does for the map's district anchors, because five of six templates carrying a section
    is not the same as six.

    A questionnaire attached but never answered raises a warning rather than printing an empty
    heading: the designer chose that form for this workshop and would otherwise have to notice the
    shortfall themselves in a sixty-page document.
    """
    from app.services.questionnaire_forms import report_items

    try:
        items = await report_items(workshop_id)
    except Exception:
        # Blind, and for the reason ``_load_one_reference_model`` is blind: one unreadable annexure
        # must not lose a report that is the end of two weeks of fieldwork. The designer is told.
        logger.exception("Could not load questionnaires for workshop %s", workshop_id)
        return [
            "The questionnaire annexure could not be loaded and was left out of this report."
        ]
    if not items:
        return []
    attach_questionnaires(data, items)
    return questionnaire_warnings(items)


def resolve_template_id(requested: Any, settings: Mapping[str, Any] | None, record: Any) -> str:
    """Which template this report is built from: the request, then stage 20, then the header.

    STAGE 20'S OWN PICKER WAS READ BY NOTHING. "Report template" is required and BASIC, so the
    completeness gate demands it and the annexure counts it as satisfied — and both the preview
    and the generate route resolved the template as ``payload/query templateId or
    record.templateId``, skipping the answer entirely. A designer worked through 22 stages,
    reached stage 20, chose "Photo catalogue" because the form insisted, generated the report and
    got the DCH standard one. Nothing anywhere said the field was inert.

    Same precedence as every other stage-20 setting — see ``resolve_accent`` and
    ``wants_transcripts``: the request wins when it says anything, so a designer can produce one
    file from a different template without editing their saved answer; the saved answer applies
    otherwise; and where neither speaks the workshop header's own ``templateId`` stands, which is
    what keeps a record that never opened stage 20 printing exactly the report it always did.

    AN UNKNOWN ID IS IGNORED RATHER THAN OBEYED, for the reason ``fontPreset`` and ``themeAccent``
    are: a token from a newer client must not silently produce a different document, and
    ``get_template`` falls back to the DCH standard for anything it does not know — which would
    turn "this build has not heard of that template" into "your chosen template is the default"
    with no way to tell the two apart.
    """
    from app.schemas.design_workshops import REPORT_TEMPLATE_IDS

    for candidate in (requested, (settings or {}).get("templateId")):
        token = str(candidate).strip() if candidate not in (None, "") else ""
        if token in REPORT_TEMPLATE_IDS:
            return token
    return str(getattr(record, "templateId", "") or "")


def report_meta(record: Any, template_id: str,
                settings: Mapping[str, Any] | None = None) -> ReportMeta:
    """The cover page and the running furniture.

    ``settings`` is the stage-20 ``reportSettings`` entry. Every value it can carry is an OVERRIDE
    of something derived from the record, and each one falls back to exactly what this function
    returned before the overrides existed — so a workshop that never opened stage 20 gets the same
    cover it always got.

    These were stored and ignored for the same reason the section toggles were: the form wrote
    them, the database kept them, and no line of the pipeline ever read them. "Report title" was
    the worst of them. A designer whose workshop is recorded as "DPDW Barpali Jan-26" and who
    typed a proper title for the submitted document watched the report print the internal
    shorthand on its cover, every time, with no indication that the field they filled in was
    inert.
    """
    template = get_template(template_id)
    s = settings or {}

    def text(key: str) -> str:
        raw = s.get(key)
        return str(raw).strip() if raw not in (None, "") else ""

    subtitle_parts = [p for p in (record.craftName, record.clusterName, record.state) if p]
    derived_subtitle = (
        " — ".join([*subtitle_parts[:1], ", ".join(subtitle_parts[1:])])
        if len(subtitle_parts) > 1 else (subtitle_parts[0] if subtitle_parts else "")
    )

    # The page size is an enum on the wire and a free string in the JSON column, so an unknown
    # token falls back rather than raising: a report that prints on the wrong paper is a nuisance,
    # and a report that refuses to print because a phone sent "A4 " is a lost afternoon.
    page_size = template.page_size
    if text("pageSize"):
        try:
            page_size = PageSize(text("pageSize"))
        except ValueError:
            pass

    return ReportMeta(
        title=text("reportTitle") or record.title,
        subtitle=text("reportSubtitle") or derived_subtitle,
        author=record.designerName or "",
        organisation=text("organisationLine") or record.implementingAgency or template.organisation,
        template_id=template.id,
        template_name=template.name,
        workshop_id=record.workshopCode or record.id,
        generated_at=datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
        page_size=page_size,
        header_text=text("headerText")
        or " — ".join(p for p in (record.craftName, record.clusterName) if p),
        footer_text=text("footerText") or f"{template.name} · {record.workshopCode or record.id}",
    )


def render_report(data: WorkshopData, template_id: str, resolver: Any, record: Any,
                  fmt: str, options: Any) -> tuple[bytes, list[str], int | None]:
    """Build and render, synchronously. The route calls this inside ``asyncio.to_thread``.

    Loading the media BYTES happens here too, and only for the images the document actually
    references — a template that excludes photographs must not pay to fetch forty of them.
    """
    settings = data.singleton("REPORT_GENERATION")

    # ``replace`` and NOT ``ReportMeta(**{**meta.__dict__, ...})``.
    #
    # ReportMeta is a frozen dataclass with ``slots=True``, and a slotted instance HAS NO
    # ``__dict__`` — the attribute lookup raises AttributeError. So each of these three lines was
    # an unconditional 500 the moment the request carried the field it guards, and the report page
    # sends all three as soon as stage 20 has a page size, a running header or a running footer
    # saved. The designers who hit it were the ones who had filled the settings in most carefully.
    #
    # It reached a person as a lie, which is the part worth remembering: ``isTransient`` in the web
    # client counts any 5xx as "probably the network", so the screen said the DOCX "cannot be
    # generated without a connection" while the server was up, answering, and failing on this line.
    # The designer was sent to look at their signal. See ``e2e/report-download.spec.ts``.
    meta = report_meta(record, template_id, settings)
    if options is not None and getattr(options, "pageSize", None):
        try:
            meta = replace(meta, page_size=PageSize(options.pageSize))
        except ValueError:
            pass
    if options is not None and getattr(options, "headerText", None):
        meta = replace(meta, header_text=options.headerText)
    if options is not None and getattr(options, "footerText", None):
        meta = replace(meta, footer_text=options.footerText)

    # THE COLOUR, RESOLVED THE SAME WAY THE PAPER IS: the request wins, then the saved stage-20
    # answer, and where neither spoke the template's own palette is left exactly as it was — which
    # is what keeps this feature from silently restyling every report a deployment has ever
    # generated. ``theme_from_accent`` derives the other seven colours and cannot raise, so a
    # malformed hex costs the designer their chosen colour and nothing else; the report is still
    # produced, in the indigo it has always been produced in.
    template = get_template(template_id)
    accent = resolve_accent(
        getattr(options, "themeAccent", None) if options is not None else None,
        settings,
    )
    theme = theme_from_accent(accent, base=template.theme) if accent else template.theme

    # THE TYPEFACE, resolved the same way a third time. It reaches the .docx and not the .pdf —
    # `report_docx` writes the family into `w:rFonts` and Word resolves it, while `report_pdf`
    # must embed a face that can actually draw Odia, Devanagari and the rupee sign and so chooses
    # by probing the filesystem. Substituting silently there would hand a designer two files that
    # look nothing alike, so the mismatch is reported as a warning further down instead.
    fonts = resolve_font(
        getattr(options, "fontPreset", None) if options is not None else None,
        settings,
    )
    if fonts:
        theme = replace(theme, heading_font=fonts[0], body_font=fonts[1])

    # THE SHAPE, resolved the same way again: which sections survive, whether photographs print
    # and how many to a row, whether headings are numbered. Same precedence, same "silent where
    # nobody asked" guarantee. ``includePhotographs`` is the one the request can also speak to,
    # because ReportGenerateIn has carried that flag since before stage 20 existed.
    template = apply_report_settings(
        template,
        settings,
        include_photographs=(getattr(options, "includePhotographs", None)
                             if options is not None else None),
        # THE ONE SECTION THIS FUNCTION ADDS RATHER THAN REMOVES, and the only one driven by the
        # request alone. It is not in any template and has no stage-20 answer behind it: an annexure
        # of machine-written text is a decision made per document, by the person who is about to
        # hand that document to somebody. See `SpecialSection.ANNEXURE_AI_LAYERS`.
        include_ai_layers=(getattr(options, "includeAiLayers", None)
                           if options is not None else None),
        # THE DESIGNER'S OWN SECTIONS, READ BACK OFF THE DATA THEY WERE LOADED ONTO rather than
        # loaded a second time. One definition reaches both the template and the renderer, so the
        # section this places after stage 13 is the same section the builder then draws — two loads
        # could straddle a definition edit and leave a heading with nothing under it.
        #
        # NO TOGGLE AND NO REQUEST FLAG. A workshop with no custom sections passes an empty tuple
        # and gets the identity return, so every existing report — and every one of the 38 pinned
        # `apply_report_settings` cases in the 485 KB Kotlin fixture — is untouched.
        custom_sections=custom_sections_of(data),
    )

    document, warnings = build_report(data, template_id, resolver.ref, meta=meta, theme=theme,
                                      template=template)

    # Only now, when the document is built, is it known which images it actually contains — a
    # template that excludes photographs must not pay to download forty of them.
    resolver.prefetch(document.images)

    if fmt == "PDF":
        blob, dropped = render_pdf(document, resolver.blob)
        page_count = _pdf_page_count(blob)
    else:
        blob, dropped = render_docx(document, resolver.blob)
        page_count = None

    warnings.extend(_dropped_warnings(dropped))
    if fmt == "PDF":
        warnings.extend(_font_warnings())
    if fonts and fmt == "PDF":
        # Said plainly, and only for the format it is true of. A designer who picks Garamond and
        # opens a PDF set in something else will otherwise conclude the setting is broken — which
        # is the same trap the seven stored-and-ignored settings were, arrived at from the other
        # direction. The .docx in the same download IS in their typeface.
        warnings.append(
            f"The PDF is set in the server's own typeface, not {fonts[1]}. A PDF must embed a "
            f"face that can draw Odia, Devanagari and the rupee sign, and that is chosen from "
            f"what is installed on the server. The Word document does use {fonts[1]}."
        )
    return blob, warnings, page_count


def _font_warnings() -> list[str]:
    """Tell the designer what this PDF will print as empty boxes, BEFORE they attach it.

    A codepoint the bound face has no glyph for is drawn as .notdef — a box — and nothing raises,
    nothing is logged per request and the file opens perfectly. The deployed image shipped with
    no fonts installed at all, so it fell through to ReportLab's vendored Vera and one workshop's
    167-page report carried 1,031 boxes: the craft's name in Odia on page 1, "unit realisation
    stands at □2,800 to □6,500" on page 14, and a ten-item quality checklist on page 134 in which
    every line began with a box instead of a tick. The .docx of the SAME workshop carried all 501
    rupee signs, 46 ticks, 14 crosses and 467 Odia codepoints correctly. Six warnings came back
    on that request and not one of them was about fonts.

    Said as a warning rather than a refusal, and for the same reason the map is: a designer with a
    submission deadline needs the document. But they must be told, because this is a defect they
    can act on — the .docx in the same download is correct, and it is the file to send.
    """
    from app.services.report_pdf import register_fonts

    fonts = register_fonts()
    said: list[str] = []
    if fonts.missing_glyphs:
        listed = ", ".join(f"{character} — {purpose}" for character, purpose in
                           fonts.missing_glyphs)
        said.append(
            f"This PDF prints empty boxes where these characters belong: {listed}. The server has "
            f"no font that can draw them. The Word document in the same download is correct."
        )
    if fonts.missing_scripts:
        # NAMED, because "no Indic font" was true of a server that drew Devanagari perfectly and
        # printed every Odia craft name as boxes — one Noto text face carries one script, and the
        # designer needs to know whether theirs is the one that is missing.
        scripts = ", ".join(s.value.title() for s in fonts.missing_scripts)
        said.append(
            f"This PDF prints empty boxes for text in these scripts: {scripts}. The server has no "
            f"font for them, so a craft name in the local language will not be legible. The Word "
            f"document in the same download is correct."
        )
    return said


def _dropped_warnings(dropped: list[str]) -> list[str]:
    """Say WHICH KIND of thing is missing from the file, because they are not the same problem.

    Every one of these used to come back as "N photograph(s) could not be included in the file",
    and a renderer drops three quite different things. A photograph is a fetch that failed and
    the designer can re-upload it. A locator MAP is missing geometry on the server, which no
    designer can do anything about and which they must not go looking for a photo to explain —
    that message sent people hunting for a missing image on workshops that have no photographs at
    all, while the actual hole was a numbered section printed empty. A CHART is a figure the
    rasteriser could not draw, which means the section's numbers are in the table and not in the
    picture beside it.

    The ids are the ones the two writers append: ``map:india``, ``chart:<kind>``, ``figure:N``
    (a chart placed by the PDF's figure path) and, for a photograph, the media id itself.
    """
    if not dropped:
        return []
    said: list[str] = []
    if any(item.startswith("map:") for item in dropped):
        said.append(
            "The locator map could not be drawn, so the section that places the workshop and its "
            "artisans is empty in this file. Nothing is wrong with the workshop's data — the "
            "boundary geometry is missing on the server."
        )
    figures = sum(1 for item in dropped if item.startswith(("chart:", "figure:")))
    if figures:
        said.append(
            f"{figures} figure(s) could not be drawn. Their numbers are still in the tables."
        )
    photographs = sum(
        1 for item in dropped if not item.startswith(("map:", "chart:", "figure:"))
    )
    if photographs:
        said.append(f"{photographs} photograph(s) could not be included in the file.")
    return said


def _pdf_page_count(blob: bytes) -> int | None:
    """Count pages without a PDF library, by counting page objects in the trailer.

    ReportLab already knows the number, but it is not exposed on the returned bytes; parsing it
    back out is cheaper than threading the count through two renderers whose only difference
    would then be this one return value.
    """
    count = blob.count(b"/Type /Page") + blob.count(b"/Type/Page")
    pages_obj = blob.count(b"/Type /Pages") + blob.count(b"/Type/Pages")
    total = count - pages_obj
    return total if total > 0 else None


async def attach_district_anchors(data: WorkshopData) -> int:
    """Give the report builder a position for every district this repository can place.

    THE MAP'S SECOND DEAD HALF. ``WorkshopData.references`` was declared and read and never
    constructed, which cost the map its artisan pins; this is the same shape of gap one level
    down. ``place_atlas`` is a hand-checked table of a few dozen craft towns, so anywhere it does
    not name — which is most of India — every artisan folded onto the state capital and the figure
    asserted that a Bargarh cluster came from Bhubaneswar. It rendered cleanly, which is exactly
    why it survived.

    ``geography.DistrictAnchors`` already solves this for ``/map``: it seeds from the atlas and
    then learns each district's position from every pinned ``Location`` the repository holds, and
    ``address.DISTRICTS_BY_STATE`` names all 795 districts so any of them can be matched. All that
    was missing was handing the result to the report.

    Flattened to a plain dict here rather than passed as the object, because ``report_builder`` is
    ALSO the on-device builder: it may not query and may not import something that can. Returns
    the number of districts placed, which the caller can log.

    CAPPED, THE WAY ``/map`` IS. There is no index that can serve this predicate — the schema
    refuses one on ``district`` on purpose (``prisma/schema.prisma``) — so the read is a scan whose
    cost is the size of the archive rather than the size of the workshop. ``/map`` answers a
    20,000-pin repository with a coarser map; before this cap the report path answered it by
    materialising 20,000 Prisma models on the single-worker box, which ``api/routes/export.py``
    describes as one unbounded ``find_many`` away from an OOM. Degrading is the right failure: an
    anchor is a district's AVERAGE position, so the districts that lose rows off the end of the cap
    keep the atlas seed or a slightly coarser learned point, and none of them loses its pin.

    The caller is expected to skip this load entirely when the template draws no map — four of the
    six do not. See ``_report_inputs``.
    """
    from app.services.geography import MAX_ANCHOR_ROWS, DistrictAnchors, district_key

    anchors = DistrictAnchors()
    anchors.seed_from_atlas()
    rows = await db.location.find_many(
        where={"subjectLatitude": {"not": None}, "district": {"not": None}},
        # A STABLE ORDER, because the read is capped: without one, two reports generated a minute
        # apart could learn from two different slices and place the same district differently.
        order={"id": "asc"},
        take=MAX_ANCHOR_ROWS,
    )
    anchors.learn(rows or [])

    points: dict[str, tuple[float, float]] = {}
    for state, districts in DISTRICTS_BY_STATE.items():
        for district in districts:
            point = anchors.anchor(state, district)
            if point:
                points[district_key(state, district)] = point
    data.district_points = points
    return len(points)
