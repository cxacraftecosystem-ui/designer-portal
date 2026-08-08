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
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass, field as dataclass_field, replace
from datetime import UTC, datetime
from typing import Any

from fastapi import HTTPException, status

from app.core.db import db
from app.core.deps import is_admin
from app.services import rich_text
from app.services.address import DISTRICTS_BY_STATE
from app.services.design_workshop_viewers import has_viewer_grant
from app.services.designers import prefill_from_profile
from app.services.report_annexures import annexure_warnings, attach_transcripts
from app.services.report_builder import ReferencedRecord, WorkshopData, build_report
from app.services.report_docx import render_docx
from app.services.report_model import ImageRef, PageSize, ReportMeta
from app.services.report_pdf import render_pdf
from app.services.report_templates import apply_report_settings, template as get_template
from app.services.report_theme import resolve_accent, resolve_font, theme_from_accent
from app.services.stage_schema import (
    PROMOTED_COLUMNS,
    REF_SCOPE_ALL,
    REF_SCOPE_WORKSHOP,
    REF_SCOPES,
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

    A soft-deleted workshop is a 404 for everyone but an admin, who needs to be able to find it
    in order to restore it. Returning 403 instead of 404 for a workshop the caller may not see
    would confirm that the id exists, which for a research data set keyed by cuid is a small
    but free leak.

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
    if record.deletedAt is not None and not admin:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if (
        record.createdById != user.id
        and not admin
        and not await has_viewer_grant(workshop_id, user.id)
    ):
        # Still 404 and still the same detail string, deliberately. Widening WHO may enter must not
        # change what a stranger is told: a 403 here would confirm the id exists to exactly the
        # people the clause is turning away.
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if for_edit and record.deletedAt is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This workshop is deleted. Restore it before editing.",
        )
    return record


async def entry_rows(workshop_id: str, *, stage_key: str | None = None) -> list[Any]:
    where: dict[str, Any] = {"designWorkshopId": workshop_id, "deletedAt": None}
    if stage_key:
        where["stageKey"] = stage_key
    return await db.dwstageentry.find_many(where=where, order={"ordinal": "asc"})


def workshop_summary(record: Any) -> dict[str, Any]:
    """The workshop header as the clients read it."""
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
        data=lambda r, _photo: {"name": r.name},
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


# WHICH FIELDS A CHOSEN RECORD WRITES ONTO THE ENTRY, keyed by "entityKey.refFieldKey" and
# mapping the reference's own data keys to the entity's field keys. The two vocabularies are
# deliberately not assumed to match: a tool's `usedFor` comes from `processUsedIn`, a product's
# single photograph seeds a gallery, and a participant's `specialisation` is really the craft
# they are documented under.
#
# THIS IS DENORMALISATION, ON PURPOSE, AND IT IS NOT A CACHE.
#
# A workshop report is a historical document. It is generated months after the workshop, often
# years after, and submitted to an office that keeps it. The artisan record it was built from is
# live data in a different part of this system: it gets corrected, merged into a duplicate
# discovered later, or deleted outright when a researcher cleans up a double entry. If the
# report resolved the name through the id at render time, every one of those perfectly ordinary
# edits would silently rewrite a submitted document — and a deletion would render it as a blank
# cell in a participant table, which is worse than useless because the table is the proof of who
# attended.
#
# So the name is COPIED onto the entry at save time and the report prints the copy. The id stays
# beside it and is never removed: it is the join key, and it is what makes "every workshop this
# artisan has attended" and "did the products we prototyped in 2026 still sell in 2028"
# answerable at all. The copy is what the document says; the id is what the research follows.
# Losing either one loses a different half of the record.
REFERENCE_HYDRATION: dict[str, dict[str, str]] = {
    "workshopSetup.craftRef": {
        "craftName": "craftName",
        "craftLocalName": "craftLocalName",
    },
    "participant.artisanRef": {
        "name": "name",
        "localName": "localName",
        "specialisation": "specialisation",
        "experienceYears": "experienceYears",
        "gender": "gender",
        "phone": "phone",
        "village": "village",
        "photo": "photo",
    },
    "tool.toolRef": {
        "name": "name",
        "localName": "localName",
        "material": "material",
        "usedFor": "usedFor",
        "cost": "cost",
        "photo": "photo",
    },
    "processStep.processRef": {"name": "name"},
    "existingProduct.artisanRef": {"name": "artisanName"},
    "existingProduct.productRef": {
        "name": "name",
        "category": "category",
        "material": "material",
        "price": "price",
        "use": "use",
        "photo": "productPhotos",
    },
    "prototype.productRef": {"name": "productName"},
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


async def _reference_photos(spec: ReferenceModel, ids: list[str]) -> dict[str, str]:
    """One photograph per record, in one query rather than one per row."""
    if not spec.media_field or not ids:
        return {}
    rows = await db.mediafile.find_many(
        where={"mediaType": "IMAGE", spec.media_field: {"in": ids}},
        order={"createdAt": "asc"},
        take=len(ids) * 4,
    )
    out: dict[str, str] = {}
    for row in rows:
        parent = getattr(row, spec.media_field, None)
        if parent and parent not in out:
            out[str(parent)] = row.id
    return out


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
      rewritten. Leaving the old artisan's name beside the new artisan's id is the one outcome
      worse than either alternative: the report and the research data would then name two
      different people for the same row, and nothing on screen would say which was meant.
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


async def seed_designer_prefill(record: Any, user: Any) -> Any:
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
    """
    try:
        values = await prefill_from_profile(user.id)
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


async def save_stage(workshop_id: str, spec: StageSpec, payload: Any, user: Any) -> dict[str, Any]:
    """Write one stage, returning what was stored, what failed validation and what was dropped."""
    # SOFT-DELETED ROWS ARE INCLUDED HERE, and that is the whole point of not filtering on
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
    existing = await db.dwstageentry.find_many(
        where={"designWorkshopId": workshop_id, "stageKey": spec.key}
    )
    header_row = await db.designworkshop.find_unique(where={"id": workshop_id})
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
    stored: dict[str, Any] = {}
    touched_ids: set[str] = set()
    touched_entities: set[str] = set()
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

    for index, entry in enumerate(payload.entries):
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

        row = None
        if entry.entryId:
            row = by_id.get(entry.entryId)
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

        pending.append(PendingEntry(
            entity=entity, data=clean, previous=previous,
            row_id=row.id if row is not None else None,
            ordinal=ordinal, client_key=client_key,
        ))
        if client_key:
            claimed_client_keys.add((entity.key, client_key))
        bucket = stored.setdefault(entity.key, [])
        bucket.append(clean)

    # The chosen references write their display fields onto the entries here, BEFORE anything is
    # serialised for the database and before the promoted columns are read out of them. `clean`
    # is mutated in place, so the same dict reaches the row, the promoted columns and the
    # `stored` block echoed back to the client — the form shows what was actually written rather
    # than what it sent, which is how a designer sees that picking the artisan filled the row in.
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
        sweep_entities = (touched_entities | set(payload.emptiedEntities)) & collection_keys
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
    completeness = workshop_completeness(rows).get(spec.key)
    # AN AUDIO FIELD ON A STAGE IS A RECORDING, AND A RECORDING GETS TRANSCRIBED. This is the one
    # line that puts a workshop clip into exactly the pipeline an interview recording has always
    # used — the same job table, the same off-peak window, the same provider chain and backoff. It
    # is here, on the save, rather than at upload time because the upload does not yet know the
    # clip is workshop audio: the phone records into the generic media route and only this write
    # says "that file is the artisan's spoken explanation for stage 5". It is idempotent (a clip
    # that has a transcript, or a job already queued, is skipped) and it never raises, so a queue
    # that is unavailable cannot fail a designer's stage save. See services/workshop_transcripts.py.
    queued = await enqueue_stage_transcriptions(rows, user.id)
    return {
        "stageKey": spec.key,
        "saved": len(creates) + len(updates),
        "created": len(creates),
        "updated": len(updates),
        "removed": len(removed),
        "errors": errors,
        "droppedKeys": sorted(set(dropped)),
        "completeness": completeness,
        "transcriptionsQueued": len(queued),
        "schemaVersion": registry_version(),
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


def workshop_completeness(entries: list[Any]) -> dict[str, Any]:
    """Score every stage from the entry rows, in one pass."""
    singletons: dict[str, dict[str, Any]] = {}
    collections: dict[str, dict[str, list[dict[str, Any]]]] = {}
    cardinality = {e.key: e.cardinality for s in stages() for e in s.entities}

    for row in entries:
        data = dict(row.data or {})
        if cardinality.get(row.entityKey) is Cardinality.SINGLETON:
            singletons[row.stageKey] = data
        else:
            collections.setdefault(row.stageKey, {}).setdefault(row.entityKey, []).append(data)

    out: dict[str, Any] = {}
    for spec in stages():
        score = stage_completeness(
            spec, singletons.get(spec.key, {}), collections.get(spec.key, {})
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

    One ``find_many`` per model, not one per row: a roster of forty artisans is one query. Failure
    of any single model is swallowed to a log line rather than raised, because a report is the end
    of two weeks of fieldwork and losing it entirely over a picture that could not be joined is
    the wrong trade — the map loses pins, the caption says how many, and the document still prints.
    """
    wanted = reference_ids(entries)
    out: dict[str, ReferencedRecord] = {}
    for model, ids in wanted.items():
        spec = REFERENCE_MODELS[model]
        try:
            rows = await getattr(db, spec.delegate).find_many(
                where={"id": {"in": sorted(ids)}}, include=spec.include or None
            )
            photos = await _reference_photos(spec, [r.id for r in rows])
        except Exception:
            # Blind, and it has to be: Prisma raises a different class for a delegate whose table
            # has been renamed, a connection that dropped mid-report and a record whose include
            # no longer matches the schema, and the answer to all three is the same. One
            # unjoinable model must not lose a report that is the end of two weeks of fieldwork.
            logger.exception("Could not load %s references for a workshop report", model)
            continue
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
    """

    def __init__(self, refs: dict[str, ImageRef], keys: dict[str, str]) -> None:
        self._refs = refs
        self._keys = keys
        self._blobs: dict[str, bytes | None] = {}

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


async def media_resolver(entries: list[Any], *, extra_ids: Iterable[str] = ()) -> MediaIndex:
    """One query for every image in the record.

    ``extra_ids`` are photographs that belong to a record the report REFERENCES rather than to a
    field of the record itself — an artisan's portrait behind a roster row, the catalogue picture
    of the product a prototype was based on. They cannot be found by walking the registry's media
    fields because they are not IN the entries at all; :func:`attach_report_references` is what
    discovers them, and passing them here is what stops the builder placing an image the resolver
    has never heard of and the renderer therefore drops without a word.
    """
    ids = _media_ids(entries) | {str(i) for i in extra_ids if i}
    if not ids:
        return MediaIndex({}, {})

    rows = await db.mediafile.find_many(where={"id": {"in": sorted(ids)}})
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
    return MediaIndex(refs, keys)


def _int_or_zero(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


async def attach_report_transcripts(
    data: WorkshopData, entries: list[Any], *, requested: bool | None = None
) -> list[str]:
    """Load the workshop's transcripts onto ``data`` when this report is meant to carry them.

    Returns the warnings to show beside the download — a recording still being transcribed is a
    gap in an annexure the designer explicitly asked for, and they should hear about it from the
    generator rather than by counting paragraphs in a 60-page document.

    Reads the toggle in the same order the whole report pipeline reads everything else: the request
    wins if it said anything, the saved stage-20 settings otherwise, and OFF when neither spoke.
    Nothing is loaded at all in the off case, so a deployment that never turns this on does not pay
    a query for it.
    """
    if not wants_transcripts(requested, data.singleton("REPORT_GENERATION")):
        return []
    items = await load_transcript_items(entries)
    if not items:
        return []
    attach_transcripts(data, items)
    return annexure_warnings(items)


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
    """
    from app.services.geography import DistrictAnchors, district_key

    anchors = DistrictAnchors()
    anchors.seed_from_atlas()
    rows = await db.location.find_many(
        where={"subjectLatitude": {"not": None}, "district": {"not": None}},
        order={"id": "asc"},
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
