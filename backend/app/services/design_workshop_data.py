"""Design-workshop stage answers, FLATTENED INTO TABLES — the half of this product View Data
could not see.

══════════════════════════════════════════════════════════════════════════════════════════════
THE GAP THIS CLOSES, MEASURED RATHER THAN ASSERTED
══════════════════════════════════════════════════════════════════════════════════════════════

``grep -c designWorkshop`` answers **zero** in every one of ``api/routes/data_browser.py``,
``api/routes/export.py``, ``api/routes/datasets.py``, ``services/record_fields.py``,
``services/xlsx_report.py`` and ``services/csv_export.py``. So the screen whose whole purpose is
"browse everything we hold" held the seven legacy repository tables and not one field of the
twenty-two-stage record the product is named after — around 523 field specs across 44 entities,
which is the entire deliverable.

Three consequences a researcher actually met:

* The whole-repo archive is named ``design-workshop-dataset.zip`` and contains no design workshop.
  That is not an omission, it is an artefact that misleads by its own filename.
* ``linkedRecordType="designWorkshop"`` is not in ``data_browser._TYPED_TAGS``, so a workshop's
  photographs and recordings DO appear in View Data — stripped of any workshop, stage or field
  identity. The bytes are browsable; what they are evidence OF is not.
* ``stage_schema``'s own module docstring names "the research export (stable keys and units)" as a
  consumer of the registry. Nothing implemented it. There is no CSV or XLSX of stage data anywhere
  in the product; the only outputs are DOCX and PDF, and a report is a template-filtered narrative,
  not a data set.

══════════════════════════════════════════════════════════════════════════════════════════════
WHY THIS MODULE IS PURE, AND WHAT THAT BUYS
══════════════════════════════════════════════════════════════════════════════════════════════

Nothing here touches the database, the request, or the permission ladder. It takes rows that have
already been loaded and returns rows a caller may render, export or count. That split is the same
one ``services/record_fields.py`` makes for the seven legacy tables, and it exists for the same two
reasons: the flattening is the part with the interesting rules, so it is the part that must be
testable without a database; and the AUTHORISATION for design-workshop data is a genuinely open
question (see below) that must not be answered by accident inside a formatter.

**THE ACCESS QUESTION, STATED HERE BECAUSE IT IS THE REASON THIS MODULE STOPS WHERE IT DOES.**
``/data`` is gated on ``require_dataset_downloader`` — PROFESSOR and above, or an explicit
per-account flag. Stage data is gated on ``load_workshop_or_404`` — the creator, an admin, or a
``DesignWorkshopViewer`` grant. And ``DESIGN_WORKSHOP_ROLES`` is a SET that excludes PROFESSOR on
purpose, with a long argument at :func:`app.core.deps.can_run_design_workshops` about seniority not
being the same thing as being a designer. So the audience View Data serves is, today, an audience
that can open zero design workshops, and no predicate in the codebase means "may READ design-workshop
stage data for research". The route that mounts this module has to be given one. It is a genuinely
new capability and not a widening of an existing one: reading a stage answer through a research
surface is not the same act as writing inside somebody's workshop, and conflating the two would
either lock the research surface out of the data it exists for or hand every professor a write
capability the ladder deliberately withholds.

══════════════════════════════════════════════════════════════════════════════════════════════
THE SHAPE: ONE TABLE PER ENTITY, NOT ONE PER STAGE
══════════════════════════════════════════════════════════════════════════════════════════════

A stage is not a table. Stage 13 holds THREE separate collections — the prototypes, their stage
logs, and material usage — and a single sheet for that stage would either repeat one collection's
rows against every row of another or leave most cells blank. TWELVE of the twenty-two stages hold
two or more entities, so this is the ordinary case rather than the corner. The registry already
draws the line — ``EntitySpec.cardinality`` — so the entity is the table, exactly as
``DwStageEntry`` rows are stored per entity.

(This paragraph named stage 11 and "one singleton of stage-level answers AND a collection of
sketches" until 2026-08-31. The argument was right and the example was not: stage 11 has exactly ONE
entity in this registry, ``sketch``, and it is a collection — so there was no singleton to repeat.
Corrected to a stage that really has the shape, and counted rather than asserted:
``Counter(t.stage_number for t in tables())``.)

Column keys are ``entityKey.fieldKey`` and are STABLE: they are the registry's own keys, not the
labels, so a retitled field does not rename a column in somebody's analysis script. The label
travels beside the key as the header. This is the "stable keys and units" the registry docstring
promised.

**EVERY ROW CARRIES THE WORKSHOP'S IDENTITY.** A sketch row that says only "Sketch 3, indigo" is
useless in an export of four hundred workshops. The identity columns are the promoted ones — the
columns that exist precisely so a researcher can filter — plus the ids needed to join back.

══════════════════════════════════════════════════════════════════════════════════════════════
WHAT IS DELIBERATELY NOT HERE
══════════════════════════════════════════════════════════════════════════════════════════════

**NO SECOND VALUE FORMATTER.** ``report_builder.format_value`` already renders one stored value as
one string, handles every ``FieldType``, resolves an ENUM token to its label and flattens rich text
through ``rich_text.to_plain``. It is used by the report, it is tested, and a table cell wants the
same answer. A private re-implementation here would be a third rendering of the same value — and
this repository has already paid for that lesson twice by name (``readableError``, ``useDragReorder``).

**NO MEDIA BYTES AND NO URLs.** A media field flattens to a COUNT and the stored ids. Resolving a
``MediaFile`` to a URL is an entitlement decision made at the encoder (``url`` and ``objectKey`` are
gated server-side), and a formatter that quietly emitted one would hand every researcher a
downloadable link to material whose consent state it never asked about.

**NO TIER FILTERING.** The report caps fields at the chosen template's ``max_tier`` and drops
``report_role=HIDDEN`` entirely — which is correct for a document and wrong for a data set. A
research export that silently omitted the Advanced-tier answers a designer took the trouble to
record would be the same class of failure as a list that quietly stops. Everything stored is
returned; a caller that wants less may narrow.
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from typing import Any

from app.services.report_builder import format_value
from app.services.stage_schema import (
    Cardinality,
    EntitySpec,
    FieldSpec,
    FieldType,
    StageSpec,
    stages,
)

#: The reserved ``entityKey`` under which a workshop's own designer-authored questions are stored.
#:
#: These answers are OUTSIDE the registry by design — the definition lives per workshop in
#: ``DwCustomSection``/``DwCustomField`` — so a browser driven only by ``stages()`` would silently
#: omit every question a designer wrote themselves. That is exactly the silent-emptiness class this
#: repository keeps paying for, so the key is named here and handled explicitly by
#: :func:`custom_rows` rather than skipped.
CUSTOM_ENTITY_KEY = "_custom"


@dataclass(frozen=True)
class Column:
    """One column of one entity table."""

    #: ``entityKey.fieldKey`` — stable across relabelling, and what an analysis script joins on.
    key: str
    #: The registry's label, for a header a person reads.
    label: str
    #: Present for a numeric field that declares one, so a header can read "Length (cm)" and a
    #: reader is never left guessing whether a bare 42 is centimetres or inches.
    unit: str = ""
    #: The registry's own type name, so an exporter can decide alignment or a numeric cast without
    #: importing the registry itself.
    type: str = "TEXT"


@dataclass(frozen=True)
class Table:
    """One entity, as a browsable table."""

    entity_key: str
    #: Sheet/heading name a person reads: the entity's own title.
    title: str
    stage_number: int
    stage_key: str
    stage_title: str
    #: SINGLETON entities hold one row per workshop; COLLECTION entities hold many.
    collection: bool
    columns: tuple[Column, ...]


def _column(entity_key: str, spec: FieldSpec) -> Column:
    return Column(
        key=f"{entity_key}.{spec.key}",
        label=spec.label,
        unit=spec.unit,
        type=spec.type.name,
    )


def entity_columns(entity: EntitySpec) -> tuple[Column, ...]:
    """Every live field of one entity, in declaration order.

    DEPRECATED FIELDS ARE DROPPED and that is the one exclusion this module makes. A deprecated
    field is one the registry has retired; its stored values survive in the JSON — nothing deletes
    them — but a column for it in a research table would invite a reader to analyse a series that
    stopped being collected on a date nothing here can tell them. A caller that genuinely wants the
    historical values can read the raw ``data`` document, which is untouched.

    Declaration order, never alphabetical: the registry's order is the order a designer met the
    questions in, so a table that follows it can be read against the form that produced it.
    """
    return tuple(_column(entity.key, spec) for spec in entity.fields if not spec.deprecated)


def tables() -> tuple[Table, ...]:
    """Every entity in the registry, as a table descriptor.

    Built from ``stages()`` rather than from a hand-written list, so a stage or entity added to the
    registry appears here without anybody remembering to add it. That is the rule this repository
    arrived at the hard way: a register written down twice goes stale, and a data browser that
    silently omits a stage is indistinguishable from a stage nobody filled in.
    """
    out: list[Table] = []
    for stage in stages():
        for entity in stage.entities:
            out.append(
                Table(
                    entity_key=entity.key,
                    title=entity.title,
                    stage_number=stage.number,
                    stage_key=stage.key,
                    stage_title=stage.title,
                    collection=entity.cardinality is Cardinality.COLLECTION,
                    columns=entity_columns(entity),
                )
            )
    return tuple(out)


def entity_by_key(entity_key: str) -> tuple[StageSpec, EntitySpec] | None:
    """The stage and entity a stored row belongs to, or None for a key this build does not know.

    NONE RATHER THAN A RAISE, and the caller must say so on screen rather than dropping the row in
    silence. A ``DwStageEntry`` can legitimately carry an entity key this server has never heard of:
    a handset one release ahead syncs rows written against a newer registry, which
    ``validate_entry``'s own docstring names as a supported caller. Refusing the whole export
    because one row is from the future would lose four hundred workshops over one; dropping it
    quietly would under-report a corpus while looking complete.
    """
    for stage in stages():
        for entity in stage.entities:
            if entity.key == entity_key:
                return stage, entity
    return None


#: The workshop columns every flattened row carries.
#:
#: The promoted columns and nothing else. They exist, per ``schema.prisma``'s own note above
#: ``DesignWorkshop``, precisely because they are "the axes a researcher actually filters and sorts
#: on" — so a row that carries them is filterable without a join, and a row that does not is a
#: fragment. ``workshopId`` is here so a row can be joined back to the record it came from;
#: ``workshopKind`` joined the set on 2026-08-30 with the column.
WORKSHOP_IDENTITY_COLUMNS: tuple[Column, ...] = (
    Column(key="workshop.id", label="Workshop id"),
    Column(key="workshop.title", label="Workshop title"),
    Column(key="workshop.workshopCode", label="Workshop code"),
    Column(key="workshop.workshopKind", label="Type of workshop"),
    Column(key="workshop.scheme", label="Scheme"),
    Column(key="workshop.craftName", label="Craft"),
    Column(key="workshop.clusterName", label="Cluster"),
    Column(key="workshop.state", label="State"),
    Column(key="workshop.district", label="District"),
    Column(key="workshop.venue", label="Venue"),
    Column(key="workshop.startDate", label="Start date", type="DATE"),
    Column(key="workshop.endDate", label="End date", type="DATE"),
    Column(key="workshop.designerName", label="Designer"),
    Column(key="workshop.status", label="Status"),
)


def _iso_date(value: Any) -> str:
    """A date column as ``YYYY-MM-DD``, never a locale-formatted string.

    The one formatting rule this module states for itself, and it is here rather than in
    ``format_value`` because these are COLUMNS on the workshop row rather than registry fields, so
    there is no ``FieldSpec`` to hand that function. ISO because an export is read by a script
    before it is read by a person, and because the repository has already been bitten once by a
    date that meant one day in the browser that wrote it and another in the browser that read it.
    """
    if value is None:
        return ""
    date = getattr(value, "date", None)
    if callable(date):
        return date().isoformat()
    return str(value)


def workshop_identity(record: Any) -> dict[str, str]:
    """The identity cells for one workshop, keyed by :data:`WORKSHOP_IDENTITY_COLUMNS`."""
    return {
        "workshop.id": str(getattr(record, "id", "") or ""),
        "workshop.title": str(getattr(record, "title", "") or ""),
        "workshop.workshopCode": str(getattr(record, "workshopCode", "") or ""),
        "workshop.workshopKind": str(getattr(record, "workshopKind", "") or ""),
        "workshop.scheme": str(getattr(record, "scheme", "") or ""),
        "workshop.craftName": str(getattr(record, "craftName", "") or ""),
        "workshop.clusterName": str(getattr(record, "clusterName", "") or ""),
        "workshop.state": str(getattr(record, "state", "") or ""),
        "workshop.district": str(getattr(record, "district", "") or ""),
        "workshop.venue": str(getattr(record, "venue", "") or ""),
        "workshop.startDate": _iso_date(getattr(record, "startDate", None)),
        "workshop.endDate": _iso_date(getattr(record, "endDate", None)),
        "workshop.designerName": str(getattr(record, "designerName", "") or ""),
        "workshop.status": str(getattr(record, "status", "") or ""),
    }


def _media_cell(value: Any) -> str:
    """A media field as a COUNT and its stored ids — never a URL, never bytes.

    ``format_value`` renders an IMAGE_LIST for a REPORT, where the pictures are placed as figures
    and the string is not the point. A table cell has to say something useful in one line, and the
    two useful facts are how many there are and which rows they are, so a researcher can join to
    ``MediaFile`` themselves under whatever entitlement they hold.

    A ``dwlocal:`` reference is a blob on somebody's phone with no ``MediaFile`` row behind it. It
    is kept and shown as-is rather than dropped: a workshop whose photographs have not synced yet is
    a real and common state, and a cell that silently showed 3 where 5 were attached would misreport
    the corpus in the direction that looks complete.
    """
    if not value:
        return ""
    ids = value if isinstance(value, list) else [value]
    kept = [str(v) for v in ids if v]
    if not kept:
        return ""
    return f"{len(kept)}: " + ", ".join(kept)


def cell(spec: FieldSpec, value: Any) -> str:
    """One stored value as one table cell.

    ``spec.type.is_media`` RATHER THAN A LOCAL SET OF TYPE NAMES, which is what this module used to
    carry (``_MEDIA_TYPES = {"IMAGE", "IMAGE_LIST", "FILE", "AUDIO", "VIDEO"}``). That set was a
    character-for-character copy of the property on ``FieldType`` and had no reason to exist: a
    sixth media type added to the registry would have been rendered by ``format_value`` as an
    ordinary string here — that is, a table cell would have printed a raw media id where every
    other surface prints a count — and nothing would have gone red. The rule this module states
    about ``format_value`` ("no second rendering of the same value") applies just as much to the
    question of WHICH values are media.
    """
    if spec.type.is_media:
        return _media_cell(value)
    return format_value(spec, value)


def entity_rows(
    entity: EntitySpec,
    entries: Iterable[Any],
    identity: dict[str, str],
) -> list[dict[str, str]]:
    """The rows one entity contributes, in stored order.

    ``entries`` is the already-loaded ``DwStageEntry`` rows for THIS entity of THIS workshop. They
    are used in the order given: ``ordinal`` is the single ordering input in this product and the
    caller's query is what applies it, exactly as ``design_workshops.py`` does. Re-sorting here
    would be a second opinion about an order a designer arranged by hand.

    A SINGLETON WITH NO ROW YIELDS NOTHING, not a row of blanks. "This workshop has not answered
    stage 14" and "this workshop answered stage 14 with empty strings" are different facts, and an
    export that renders them identically cannot be used to count coverage — which is most of what a
    research export is for.
    """
    out: list[dict[str, str]] = []
    live = [spec for spec in entity.fields if not spec.deprecated]
    for entry in entries:
        data = getattr(entry, "data", None) or {}
        if not isinstance(data, dict):
            continue
        row = dict(identity)
        row["entry.id"] = str(getattr(entry, "id", "") or "")
        row["entry.ordinal"] = str(getattr(entry, "ordinal", 0) or 0)
        for spec in live:
            row[f"{entity.key}.{spec.key}"] = cell(spec, data.get(spec.key))
        out.append(row)
    return out


@dataclass(frozen=True)
class CustomColumn:
    """One designer-authored question, as a column.

    Its key is the field's stored id and NOT its prompt: two designers write "Dye bath?" and mean
    different questions, and a designer who rewords their own question has not created a new one.
    """

    key: str
    label: str


def custom_columns(definition: Any) -> tuple[CustomColumn, ...]:
    """The columns a workshop's own questions contribute, in the designer's order.

    RETIRED FIELDS ARE KEPT, which is the opposite of the registry rule above, and the asymmetry is
    deliberate. A registry field is deprecated centrally and its column would invite analysis of a
    series that stopped for everybody. A custom field is retired by ONE designer in ONE workshop:
    the answers are theirs, they are the only record of a question nobody else asked, and dropping
    the column would delete the only trace of it from the research surface. It is marked instead.
    """
    out: list[CustomColumn] = []
    for section in getattr(definition, "sections", None) or []:
        for field in getattr(section, "fields", None) or []:
            key = str(getattr(field, "id", "") or "")
            if not key:
                continue
            label = str(getattr(field, "label", "") or key)
            if getattr(field, "retired", False):
                label = f"{label} (retired)"
            out.append(CustomColumn(key=f"{CUSTOM_ENTITY_KEY}.{key}", label=label))
    return tuple(out)


def custom_rows(
    definition: Any,
    entries: Iterable[Any],
    identity: dict[str, str],
) -> list[dict[str, str]]:
    """The rows a workshop's designer-authored answers contribute.

    Values are stringified rather than passed through ``format_value``, because a custom field has
    no ``FieldSpec``: its type vocabulary is ``custom_sections.V1_CUSTOM_TYPES``, which deliberately
    excludes MEDIA, REF, GEO and RICH_TEXT, so everything reaching here is already a scalar or a
    list of them. If that vocabulary ever widens, this function needs the same treatment
    :func:`cell` gives the registry — and the comment is here so the next reader knows it.
    """
    columns = custom_columns(definition)
    out: list[dict[str, str]] = []
    for entry in entries:
        data = getattr(entry, "data", None) or {}
        if not isinstance(data, dict):
            continue
        row = dict(identity)
        row["entry.id"] = str(getattr(entry, "id", "") or "")
        row["entry.ordinal"] = str(getattr(entry, "ordinal", 0) or 0)
        for column in columns:
            raw = data.get(column.key.split(".", 1)[1])
            if raw is None:
                row[column.key] = ""
            elif isinstance(raw, list):
                row[column.key] = ", ".join(str(v) for v in raw)
            elif isinstance(raw, bool):
                row[column.key] = "Yes" if raw else "No"
            else:
                row[column.key] = str(raw)
        out.append(row)
    return out


# ══════════════════════════════════════════════════════════════════════════════════════════════
# THE SEARCH TEXT — one stage row's answers, RENDERED, as one string a ``contains`` can match
# ══════════════════════════════════════════════════════════════════════════════════════════════
#
# ``DwStageEntry.searchText`` is the second half of closing §6.1 of
# ``docs/DECISION-design-workshop-data-in-view-data.md`` ("a search for 'indigo' finds a workshop
# whose title says indigo, and does not find the workshop whose stage 5 dye-bath answer says it").
# The column is maintained by the two writers of ``DwStageEntry.data``; THIS is where its contents
# are decided, and it is here rather than beside the writer for the reason the whole module exists:
# what a research surface says is the part with the interesting rules, so it is the part that must
# be testable with no database.
#
# ── WHY RENDERED AND NOT ``data::text`` ───────────────────────────────────────────────────────
#
# The rejected option in §6.1 was a ``pg_trgm`` GIN index over the raw JSON. Besides needing an
# extension the managed instance may not permit, it indexes the JSON's KEYS and PUNCTUATION beside
# its answers, so a researcher typing a dye name into a box matches on field names and structure.
# Worse, it indexes the STORED TOKEN: a designer picks "Design & Prototype Development" from a
# dropdown and the row holds ``DESIGN_PROTOTYPE_DEVELOPMENT``, so the one string the product itself
# shows that designer would find nothing. Running the values through :func:`cell` — which resolves
# an ENUM token to its label and flattens a rich-text document through ``rich_text.to_plain`` —
# makes the searchable text the text a person has actually READ. That is a better search, not
# merely a cheaper one.
#
# It is also SMALLER than the JSON it is derived from in every case: no keys, no braces, no
# escaping, and only the subset of types below. So the second copy §6.1 objects to costs less
# storage than the column it shadows.


#: The field types whose rendered value goes into ``searchText``, and the whole list.
#:
#: THE RULE IS "WHAT A PERSON WOULD TYPE INTO A SEARCH BOX", applied one type at a time. Every
#: exclusion below is a value that would either match nothing a researcher can type or match
#: everything.
#:
#: * **Media (IMAGE/IMAGE_LIST/FILE/AUDIO/VIDEO) and REF** store an id — a cuid, or a ``dwlocal:``
#:   reference to a blob on somebody's phone. Nobody types a cuid, and a researcher who pastes one
#:   means the media table. The names behind a REF are not lost by this: ``hydrate_entries`` copies
#:   the artisan's name, the craft's name and the product's name onto SIBLING text fields of the
#:   same row at save time, and those are included.
#: * **GEO** is a coordinate pair. **BOOL** renders "Yes"/"No", so including it would make every
#:   workshop in the repository a match for the word "no".
#: * **The numeric and date types** are excluded because their RENDERED form is not what a person
#:   types: ``format_value`` prints 6500 as "₹ 6,500.00" and 2026-02-10 as "10 Feb 2026", so a
#:   column that carried them would look like it covered numbers and dates while failing the two
#:   obvious queries. Saying they are not searched is honest; half-searching them is not. (Matching
#:   them properly is a range filter over a typed column, which is a different feature.)
#:
#: PHONE AND EMAIL ARE EXCLUDED FOR A DIFFERENT AND SHARPER REASON — see
#: :data:`UNSEARCHABLE_FIELD_KEYS` below, which carries the whole argument.
SEARCHABLE_FIELD_TYPES: frozenset[FieldType] = frozenset(
    {
        FieldType.TEXT,
        FieldType.LONG_TEXT,
        FieldType.RICH_TEXT,
        FieldType.ENUM,
        FieldType.MULTI_ENUM,
        FieldType.TAGS,
        FieldType.URL,
    }
)

#: Field keys whose value never enters ``searchText`` whatever their type says.
#:
#: **THIS SET EXISTS SO THAT ONE SENTENCE IN ANOTHER MODULE STAYS TRUE.** The banner above
#: ``access.REVISION_REDACTED_FIELDS`` states, of the artisan contact details and identity numbers
#: that hydration copies onto every participant row: *"a ``DwStageEntry`` is not indexed by identity
#: number, is not what an admin opens when tracing a duplicate … It is a RESIDUE, not a ledger."*
#: That is the entire basis on which the product accepts that clearing ``Artisan.phone`` leaves the
#: number in stage rows it will never re-resolve. A column that made those rows matchable by typing
#: the number would falsify it in one commit — turning a residue into exactly the identity index
#: that comment says does not exist, and handing every professor a reverse lookup ("which workshops
#: is this person in") that no surface in this product offers today.
#:
#: So the two identity keys are named here, and PHONE and EMAIL are left out of
#: :data:`SEARCHABLE_FIELD_TYPES` for the same reason rather than for a rendering one. The identity
#: numbers need naming BY KEY because both are declared TEXT — the type alone cannot see them.
#:
#: RETYPED FROM ``records._IDENTITY_KEYS`` RATHER THAN IMPORTED, because this module is pure and
#: that one opens the database (see the module header). ``artisanCardNo`` is here as well and is not
#: in that constant: the registry stores the Pehchan card's masked carry under its own spelling
#: (``stage_definitions``' ``fromref("artisanCardNo", …)``), which is precisely why ``_redact_
#: sensitive``'s by-name walk does not reach it either. The two lists are pinned together by
#: ``test_design_workshop_search_text.test_every_identity_key_records_knows_is_excluded_here``.
UNSEARCHABLE_FIELD_KEYS: frozenset[str] = frozenset(
    {"aadhaarNumber", "pehchanCardNumber", "artisanCardNo", "phone", "email"}
)

#: What joins one row's answers. A NEWLINE and not a space, so that two adjacent answers cannot form
#: a phrase neither of them contains: "indigo" in one box and "dye" in the next must not make the row
#: a match for "indigo dye". ``contains`` is a substring test and has no word boundaries to lean on.
SEARCH_TEXT_JOIN = "\n"


def _searchable(spec: Any) -> bool:
    """Whether one field's value belongs in ``searchText``. Duck-typed on purpose.

    Takes a ``FieldSpec`` or a ``custom_sections.CustomFieldSpec``: the two share ``key`` and
    ``type`` by deliberate design (see that class's own docstring), and this module stays pure by
    never importing either of the modules that define them.
    """
    return (
        getattr(spec, "type", None) in SEARCHABLE_FIELD_TYPES
        and str(getattr(spec, "key", "")) not in UNSEARCHABLE_FIELD_KEYS
    )


def _joined(values: Iterable[str]) -> str:
    """The non-empty renderings, in field order, de-duplicated, joined.

    DE-DUPLICATED because a stage row repeats itself more than one would think — a hydrated
    reference writes the craft name onto the row that already names the craft — and a column that
    stored "Ikat" nine times would be nine times the bytes for no recall at all. Order is kept
    (``dict.fromkeys``) so the stored string is deterministic: a backfill that produced a different
    string on a second run over unchanged rows would make :func:`entry_search_text` untestable and
    the backfill's "rows touched" count meaningless.
    """
    kept = [text for text in (value.strip() for value in values) if text]
    return SEARCH_TEXT_JOIN.join(dict.fromkeys(kept))


def entry_search_text(entity: EntitySpec, data: Any) -> str:
    """One registry row's answers, rendered, as the string ``GET /search`` matches against.

    DEPRECATED FIELDS ARE INCLUDED, which is the opposite of :func:`entity_rows`' rule for the same
    registry, and the asymmetry is the point. A column in a research table invites analysis of a
    series that stopped for everybody, so a deprecated field earns no column; a stored ANSWER is
    still a thing a designer wrote and a researcher may be looking for, and a search that could not
    find it would be a search that quietly excludes the oldest workshops in the corpus.

    Returns "" for a row with nothing searchable in it, and the writer stores that as NULL — see
    :func:`design_workshops.save_stage`. "No searchable text" and "not yet computed" are different
    facts, but they are indistinguishable to a ``contains`` and the backfill needs the second one to
    be re-runnable, so the column carries NULL for both and neither can ever match.
    """
    values = data if isinstance(data, dict) else {}
    return _joined(
        cell(spec, values.get(spec.key)) for spec in entity.fields if _searchable(spec)
    )


def custom_search_text(specs: Iterable[Any], data: Any) -> str:
    """The same, for the reserved ``_custom`` row: a workshop's OWN questions.

    **THESE ARE THE ANSWERS THAT MOST NEED THIS COLUMN.** A registry field's wording is shared by
    every workshop in the repository, so a researcher can go and read the form to learn what to
    search for. A custom field is one designer's own question, asked in one workshop, in whatever
    words they chose — nobody else can guess the wording, and until this column existed the only way
    to find its answers was to open the workshop that already had to be found some other way.

    NOT ``format_value``, and that is the same call :func:`custom_rows` makes one screen over: a
    custom field has no ``FieldSpec``. Its vocabulary is ``custom_sections.V1_FIELD_TYPES``, which
    excludes MEDIA, REF, GEO and RICH_TEXT, so everything that survives :func:`_searchable` here is
    a string, a list of strings, or an option token — and an option token has to go through the
    field's OWN ``option_label``, because a designer's options are per-workshop rows and
    ``stage_schema.enum_label`` has never heard of them. Rendering ``DESIGN_PROTOTYPE`` where the
    designer wrote "Design prototype" is the exact failure the module banner above rejects
    ``data::text`` for.
    """
    values = data if isinstance(data, dict) else {}
    rendered: list[str] = []
    for spec in specs:
        if not _searchable(spec):
            continue
        raw = values.get(str(getattr(spec, "key", "")))
        if raw is None:
            continue
        label = getattr(spec, "option_label", None)
        if isinstance(raw, list):
            rendered.extend(str(label(v)) if callable(label) else str(v) for v in raw)
        else:
            rendered.append(str(label(raw)) if callable(label) else str(raw))
    return _joined(rendered)


def stage_label(stage_key: str) -> str:
    """``"DESIGN_DEVELOPMENT"`` -> ``"Stage 7: Design development"``, for naming a match.

    A hit on a stage answer that does not say WHICH stage is a hit a researcher cannot act on: the
    workshop has twenty-two of them and the answer is in one. The number leads because that is how
    every other surface in this product orders a fortnight of fieldwork — the report's contents page,
    the tree in View Data, the handset's stage list.

    An unrecognised key is returned AS ITSELF rather than dropped or guessed at. A row can honestly
    carry a stage key this build has never heard of (a phone one release ahead), and the raw key is
    still something a reader can search the registry for; inventing a title for it would not be.
    """
    return next(
        (f"Stage {spec.number}: {spec.title}" for spec in stages() if spec.key == stage_key),
        stage_key,
    )


#: Where an unrecognised stage key sorts. Past 22, so it lands LAST.
#:
#: A row can honestly carry a stage key this build has never heard of, and burying it at the end of a
#: list is better than putting an unrecognisable stage at the top of every result it appears in — the
#: same choice :func:`stage_label` makes when it declines to invent a title for one.
UNKNOWN_STAGE_ORDER = 99


def stage_order(stage_key: str) -> int:
    """The registry's own number for a stage, for sorting a list of :func:`stage_label` strings.

    SORT ON THIS, NEVER ON THE LABEL. The label leads with "Stage 10" and "Stage 2", and as text the
    first sorts before the second — so a workshop that matched in stages 2 and 10 would read in a
    different order from one that matched in 2 and 9, on the same screen, for no reason a reader
    could see.
    """
    return next(
        (spec.number for spec in stages() if spec.key == stage_key), UNKNOWN_STAGE_ORDER
    )


@dataclass(frozen=True)
class UnknownEntity:
    """A stored row this build's registry cannot describe, counted rather than dropped.

    See :func:`entity_by_key` for why this exists at all. It is reported so a caller can print the
    count — "3 rows were written against a newer version of the form and are not shown here" — which
    is rule 10 of the frontend reference applied to an export: a corpus that quietly stops is
    indistinguishable from a corpus that ends there.
    """

    entity_key: str
    rows: int


def flatten(
    record: Any,
    entries: Sequence[Any],
    definition: Any = None,
) -> tuple[dict[str, list[dict[str, str]]], tuple[UnknownEntity, ...]]:
    """One workshop's stage entries, grouped into ``{entityKey: rows}``.

    Returns the unknown-entity tally beside the rows rather than logging it, because the only
    correct place for that fact is on the screen or in the file the reader is looking at.
    """
    identity = workshop_identity(record)
    grouped: dict[str, list[Any]] = {}
    for entry in entries:
        key = str(getattr(entry, "entityKey", "") or "")
        if not key:
            continue
        grouped.setdefault(key, []).append(entry)

    out: dict[str, list[dict[str, str]]] = {}
    unknown: list[UnknownEntity] = []
    for key, rows in grouped.items():
        if key == CUSTOM_ENTITY_KEY:
            if definition is not None:
                out[key] = custom_rows(definition, rows, identity)
            else:
                # THE DEFINITION IS THE ONLY THING THAT CAN NAME THESE COLUMNS, so without it the
                # rows are counted as unreadable rather than emitted with opaque id headers. A
                # caller that wants them must load the workshop's own definition.
                unknown.append(UnknownEntity(entity_key=key, rows=len(rows)))
            continue
        found = entity_by_key(key)
        if found is None:
            unknown.append(UnknownEntity(entity_key=key, rows=len(rows)))
            continue
        _stage, entity = found
        out[key] = entity_rows(entity, rows, identity)
    return out, tuple(unknown)


# ══════════════════════════════════════════════════════════════════════════════════════════════
# WHAT A FILE IS EVIDENCE OF — the media-identity half, added 2026-08-31
# ══════════════════════════════════════════════════════════════════════════════════════════════
#
# THE LEAK THIS CLOSES, MEASURED. ``linkedRecordType="designWorkshop"`` was not in
# ``data_browser._TYPED_TAGS``, so a workshop's photographs and recordings DID appear in View Data —
# in the ``misc`` bucket of the by-uploader taxonomy and in the media sheets of the report — with no
# workshop, no stage and no field beside them. A researcher met a folder of anonymous JPEGs. The
# bytes were browsable; what they were evidence OF was not, which for a research corpus is the same
# as not having them.
#
# WHY THE ANSWER IS COMPUTED FROM THE STAGE ROWS AND NOT READ OFF THE FILE. ``MediaFile`` carries
# the workshop twice (the ``designWorkshop`` tag pair, and the ``designWorkshopId`` column for a
# miscellaneous upload) and carries the STAGE nowhere at all. It cannot: a media field stores its
# ids inside ``DwStageEntry.data``, so the only record that a given file answers stage 11's "Sketch
# photographs" is the stage row itself. Adding a stage column to ``MediaFile`` would be a second
# copy of that fact, written by the upload path, able to disagree with the stage row the moment a
# designer moves a photograph between fields — and this repository has a standing rule against
# exactly that shape (see ``MediaFile.designWorkshopId``'s own note in schema.prisma).
#
# So the index is DERIVED, from rows a caller has already loaded, and it is derived here rather than
# in the route for the same reason everything else in this module is: it is the part with the
# interesting rules, so it is the part that must be testable without a database.


@dataclass(frozen=True)
class MediaAttribution:
    """Which workshop, stage, entity, row and field one media file belongs to.

    EVERY FIELD IS A LABEL A PERSON READS EXCEPT THE KEYS, which are kept beside them because a
    research surface is read by a script before it is read by a person — the same reason
    :class:`Column` carries ``key`` and ``label`` together.
    """

    workshop_id: str
    workshop_title: str
    workshop_code: str
    stage_number: int
    stage_key: str
    stage_title: str
    entity_key: str
    entity_title: str
    #: Which row of a COLLECTION this is — "Sketch 3" rather than "a sketch". 0 for a singleton.
    ordinal: int
    field_key: str
    field_label: str

    @property
    def label(self) -> str:
        """One line naming the file's place in the workshop, for a folder listing or a table cell.

        "Stage 11 - Sketch - #3 - Sketch photographs" — the stage first because that is the axis a
        designer and a report both order the fortnight by, and the ordinal only when there is one,
        because "#1" on a singleton is noise that reads as a real number.
        """
        parts = [f"Stage {self.stage_number}", self.entity_title]
        if self.ordinal:
            parts.append(f"#{self.ordinal + 1}")
        parts.append(self.field_label)
        return " / ".join(parts)


def _media_ids(value: Any) -> list[str]:
    """The stored ids of one media field, single or list, blanks dropped."""
    if not value:
        return []
    raw = value if isinstance(value, list) else [value]
    return [str(v) for v in raw if v]


def _stage_number_of(entity_key: str) -> int:
    """The stage an entity belongs to, or a number past the end for one nothing knows.

    DEPRECATED FIELDS ARE NOT EXCLUDED FROM THE MEDIA INDEX, unlike :func:`entity_columns`. A
    retired field's photographs are still on the disk and still belong to that stage; refusing to
    name them would put them back in the anonymous ``misc`` bucket this whole section exists to
    empty, which is a strictly worse answer than "this came from a question we no longer ask".
    """
    found = entity_by_key(entity_key)
    return found[0].number if found is not None else 10_000


def media_attributions(record: Any, entries: Iterable[Any]) -> dict[str, MediaAttribution]:
    """``{mediaId: what it is evidence of}`` for one workshop's already-loaded stage rows.

    A FILE CITED TWICE KEEPS ITS FIRST CITATION, and that is a decision rather than an accident of
    iteration order. The same photograph legitimately appears in two fields — a prototype shot
    carried forward into stage 15's validation gallery is the ordinary case, not a mistake — and a
    browser has to file it somewhere. Taking the first citation in STAGE ORDER files it where it was
    first captured, which is the answer a designer would give; taking the last would file the
    original under the place it was quoted. A caller needing every citation should build its own
    index from :func:`flatten`; this one exists so a folder and a sheet can name a file's home.

    ``dwlocal:`` references are indexed like any other id. They are blobs on somebody's phone with
    no ``MediaFile`` row yet, so nothing will ever look them up here — but excluding them would mean
    this function quietly disagreed with :func:`_media_cell`, which keeps them for the reason stated
    there, and two functions in one module disagreeing about what a media value contains is how the
    next defect gets in.

    Rows written against an entity this build's registry does not know are SKIPPED — there is no
    ``FieldSpec`` to name a field with, so there is nothing truthful to say about them.
    :func:`flatten` counts those rows for the caller to print; this function does not double-count
    them.
    """
    identity = workshop_identity(record)
    workshop_id = identity["workshop.id"]
    workshop_title = identity["workshop.title"]
    workshop_code = identity["workshop.workshopCode"]

    out: dict[str, MediaAttribution] = {}
    # Stage order, then the caller's own row order. ``entries`` arrives ordered by the query that
    # loaded it (see :func:`entity_rows` on why that order is never second-guessed), so the sort key
    # is the STAGE and nothing else — Python's sort is stable, so rows within one stage stay exactly
    # as given.
    ordered = sorted(
        entries,
        key=lambda entry: _stage_number_of(str(getattr(entry, "entityKey", "") or "")),
    )
    for entry in ordered:
        found = entity_by_key(str(getattr(entry, "entityKey", "") or ""))
        if found is None:
            continue
        stage, entity = found
        data = getattr(entry, "data", None) or {}
        if not isinstance(data, dict):
            continue
        ordinal = int(getattr(entry, "ordinal", 0) or 0)
        for spec in entity.fields:
            if not spec.type.is_media:
                continue
            for media_id in _media_ids(data.get(spec.key)):
                if media_id in out:
                    continue
                out[media_id] = MediaAttribution(
                    workshop_id=workshop_id,
                    workshop_title=workshop_title,
                    workshop_code=workshop_code,
                    stage_number=stage.number,
                    stage_key=stage.key,
                    stage_title=stage.title,
                    entity_key=entity.key,
                    entity_title=entity.title,
                    ordinal=ordinal,
                    field_key=spec.key,
                    field_label=spec.label,
                )
    return out


# ══════════════════════════════════════════════════════════════════════════════════════════════
# WHICH TABLES BECOME SHEETS — and the promise that the ones that do not are still NAMED
# ══════════════════════════════════════════════════════════════════════════════════════════════
#
# 44 ENTITY TABLES IS TOO MANY TABS. The existing workbook has fourteen; forty-four more would be a
# workbook nobody can navigate, and openpyxl's sheet-name rules (31 characters, unique) start
# colliding across entities whose titles share a prefix.
#
# THREE GROUPINGS WERE CONSIDERED AND TWO ARE WRONG:
#
#   * ONE SHEET PER STAGE (22 tabs). Rejected, and it is the one that looks most attractive. A stage
#     is not a table — this module's own header says so, and the reason is structural rather than
#     aesthetic: stage 13 holds THREE collections (prototypes, their stage logs, material usage), so
#     a per-stage sheet either repeats one collection's rows against every row of another or leaves
#     most of every row blank. TWELVE of the twenty-two stages hold two or more entities and
#     twenty-nine of the forty-four entities are collections, so this is the common case, not the
#     corner. (Both figures counted from ``tables()``, not estimated.)
#   * ONE SHEET PER ENTITY, ALL 44, ALWAYS. Rejected: the great majority are empty on any real
#     subtree — a workshop that has reached stage 8 has answered nothing in stages 9 to 22 — so most
#     tabs would be a header row and nothing else, and the reader has to open each one to find out.
#   * ONE SHEET PER ENTITY THAT HAS ROWS, capped, plus an INDEX naming all 44 with their counts.
#     Chosen. Every sheet in the workbook has data in it, the reader learns the shape of the whole
#     registry from one page, and the cap cannot hide anything — because the index lists the
#     entities that did not get a sheet, by name, with their row counts, on the same page.
#
# RULE 10, WHICH IS WHAT THE INDEX IS ACTUALLY FOR: a list that quietly stops is indistinguishable
# from a place with no records. A workbook that silently dropped entity 25 would tell a researcher
# the workshops answered nothing there.

#: How many per-entity sheets one workbook may carry. Sixteen leaves the fourteen legacy sheets, the
#: two design-workshop overview sheets and these inside a thirty-two-tab workbook, which is about
#: the most a person will scroll through. It is a DISPLAY budget and not a data limit: nothing is
#: dropped from :func:`sheet_plan`'s report, only from the tabs.
MAX_ENTITY_SHEETS = 16


@dataclass(frozen=True)
class SheetPlan:
    """Which entity tables become their own sheet, and what the index sheet must therefore say.

    ``omitted`` IS THE POINT OF THE DATACLASS. A plan that returned only ``included`` would let a
    caller render a workbook that stops without saying so, which is the failure this whole section
    is written against — so the two lists come back together and the index sheet is built from both.
    """

    #: Entity keys that get their own sheet, in registry order.
    included: tuple[str, ...]
    #: Entity keys that have rows but did not fit the cap, in registry order. Named on the index.
    omitted: tuple[str, ...]
    #: Entity keys with no rows in this subtree at all. Also named — "0" is a research finding.
    empty: tuple[str, ...]

    @property
    def truncated(self) -> bool:
        """Whether the cap bit, so a screen can render a banner rather than a footnote."""
        return bool(self.omitted)


def sheet_plan(counts: dict[str, int], limit: int = MAX_ENTITY_SHEETS) -> SheetPlan:
    """Split the registry's entities into sheets, overflow and empties.

    ``counts`` is ``{entityKey: rows}`` over whatever subtree the caller loaded; a key the registry
    does not know is IGNORED here rather than sorted into ``empty``, because ``flatten`` already
    reports those as :class:`UnknownEntity` and counting them twice would put the same rows on the
    index sheet under two different explanations.

    THE TAB ORDER IS THE REGISTRY'S, NOT THE ROW COUNTS'. Sorting the tabs by size would put stage
    17 before stage 3 and break the one property that makes a workbook of a fortnight readable —
    that its tabs run in the order the fortnight did. Which entities are IN is decided by row count
    (a fuller table beats an emptier one for a scarce tab); where they then sit is the registry's.
    """
    registry_order = [table.entity_key for table in tables()]
    position = {key: index for index, key in enumerate(registry_order)}
    with_rows = [key for key in registry_order if counts.get(key, 0) > 0]
    empty = tuple(key for key in registry_order if counts.get(key, 0) <= 0)
    kept = set(sorted(with_rows, key=lambda key: (-counts[key], position[key]))[:limit])
    return SheetPlan(
        included=tuple(key for key in with_rows if key in kept),
        omitted=tuple(key for key in with_rows if key not in kept),
        empty=empty,
    )
