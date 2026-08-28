"""One registry describing how every record type is presented, everywhere.

Before this module the same field list was written out four separate times per record
type — the browser's info panel, the browser's report sheet, export.py's ``details.txt``
and csv_export.py's CSV columns — so adding a column meant editing four places and
silently under-reporting in the three you forgot.

ALL FOUR now derive from the ``RecordSpec`` entries below: the data browser's ``/data/tree``
info panels and generated ``details.txt``, every sheet in the ``/data/report`` workbook, the
``details.txt`` files inside the ``/export/dataset`` zip, and the ``/export/products.csv`` /
``/export/tools.csv`` downloads. A column added here reaches every one of them, and — just as
importantly — the masking applied here (an artisan's Aadhaar and Pehchan card numbers) can no
longer be missed by a surface that built its own field list.

Three presentations are built from one spec:

- :func:`info_panel` — ``{title, fields:[{label,value}]}`` with empty values dropped.
  Used for the data browser's record card and for generated ``details.txt`` bodies.
- :func:`record_table` — a rectangular ``{columns, rows}`` grid that keeps every column
  even when a cell is blank, so a folder can render its records as a real table.
- :func:`sheet_columns` / :func:`sheet_row` — the same grid shaped for an .xlsx sheet,
  with provenance (status / created by / created on) and the media columns appended.

A spec's ``fields`` are the human-meaningful record fields. Provenance and media are
added by the table/sheet builders rather than the spec, because the info panel
deliberately omits them.

Value coercion lives here too (:func:`num`, :func:`money`, :func:`dims`, :func:`date_str`,
:func:`enum_label`) — and with it :func:`dims_with_method`, which says in the dimensions cell itself
when a number was a vision model's estimate rather than a tape reading. It lives here rather than in
the provenance panel precisely BECAUSE of the four-surface rule above: the panel is gated on
``canViewProvenance``, and the people who most need to know a costed number is an estimate are the
researcher, the reviewer and the ministry officer, none of whom hold that permission.

Prisma ``Decimal`` columns arrive as objects that stringify with trailing zeros, and Prisma enums
stringify as ``MediaType.AUDIO``; both are normalised before they ever reach a cell.

And so does the RICH-TEXT read boundary. The larger free-text columns can now hold a formatted
document rather than a bare paragraph, and :func:`cell` flattens it — one call, at the one place
every value in every one of the four surfaces above passes through. Its docstring says what breaks
without it.
"""

from __future__ import annotations

from collections.abc import Callable, Iterable
from dataclasses import dataclass
from typing import Any

from app.services.artisan_identity import mask_aadhaar
from app.services.records import derive_age, derive_experience_years
from app.services.rich_text import plain_from_stored

# ---------------------------------------------------------------------------
# Value coercion
# ---------------------------------------------------------------------------


def ev(value: Any) -> Any:
    """Prisma enum -> its plain string value (``MediaType.AUDIO`` -> ``AUDIO``)."""
    return getattr(value, "value", value)


def num(value: Any) -> str | None:
    """Decimal/number -> compact string without trailing zeros ("1500.00" -> "1500")."""
    if value is None:
        return None
    text = str(value)
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text or None


def money(value: Any) -> str | None:
    parsed = num(value)
    return f"₹{parsed}" if parsed is not None else None


def dims(*parts: Any) -> str | None:
    values = [num(p) for p in parts if p is not None]
    values = [v for v in values if v]
    return " x ".join(values) if values else None


def date_str(value: Any) -> str | None:
    if value is None:
        return None
    try:
        return value.strftime("%d %b %Y")
    except (AttributeError, ValueError):
        return str(value)


def enum_label(value: Any) -> str | None:
    """Enum -> "Local Blacksmith". The UNKNOWN/OTHER defaults carry no information, so
    they are dropped rather than rendered as a meaningless cell."""
    raw = str(ev(value) or "").strip()
    if not raw or raw.upper() in ("UNKNOWN", "OTHER"):
        return None
    return raw.replace("_", " ").title()


def human_size(value: Any) -> str:
    if value is None:
        return ""
    size = float(int(value))
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if size < 1024 or unit == "TB":
            return f"{int(size)} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024
    return ""  # pragma: no cover - the TB branch always returns


def cell(value: Any) -> str:
    """Any value -> a trimmed string safe to drop straight into a table cell.

    THE FLATTENING IS NOT DECORATION — IT IS WHY THE RICH-TEXT FEATURE IS SAFE. The larger record
    fields (artisan notes and do's/don'ts, product remarks and materials, tool remarks and usage,
    process notes) now accept bold, lists and tables from the web and Android editors, and they are
    stored inside the SAME ``String?`` columns as before: no migration, no column type change, no
    second column. ``rich_text.plain_from_stored`` is what tells a formatted value apart from the
    prose sitting in every other row and renders it back down to the words a person wrote.

    Every export surface in this repository funnels through this one function — the data browser's
    info card, the ``/data/report`` workbook, the ``details.txt`` inside the dataset zip, and the
    ``/export/products.csv`` and ``/export/tools.csv`` downloads. Remove the call below and every
    one of them starts emitting ``{"blocks":[{"kind":"PARAGRAPH",…`` into files that go to a
    ministry. Nothing raises when that happens, which is exactly why the guard belongs here, at the
    chokepoint, rather than at four call sites where the fifth one would be added without it.

    A plain string is returned unchanged by ``plain_from_stored`` — by identity, not by round trip —
    so the existing corpus renders byte-for-byte as it did before this line existed.
    """
    return "" if value is None else str(plain_from_stored(value)).strip()


def _first_answer(*values: Any) -> Any:
    """The first value that is not None. NOT `or`: 0 and "" are answers, not absences."""
    return next((v for v in values if v is not None), None)


def meta_of(record: Any) -> dict[str, Any]:
    meta = getattr(record, "extraMetadata", None)
    return meta if isinstance(meta, dict) else {}


def meta_val(meta: dict[str, Any], *keys: str) -> Any:
    """First populated scalar stored under any of the given extraMetadata keys."""
    for key in keys:
        value = meta.get(key)
        if isinstance(value, (str, int, float)) and str(value).strip():
            return value
    return None


#: How a stored measurement method reads in a cell somebody costs a production run from.
#:
#: ONLY THE TWO METHODS THAT CHANGE WHAT A READER MAY DO WITH THE NUMBER APPEAR HERE. ``TYPED`` is
#: what the overwhelming majority of dimensions in this repository are and needs no ceremony — a cell
#: reading "8.5 (typed)" tells a reader nothing they did not assume. ``UNRECORDED`` is deliberately
#: silent too, and that is the sharper decision: every row written before ``measurement_provenance``
#: existed carries it, so appending "method not recorded" to most of the database would be noise that
#: trains readers to skip the clause on the one row where it matters. The honest rendering of a legacy
#: row is the bare number. ``services/measurement_provenance`` argues both, and ``aiLayers.ts``'s
#: refusal to print the token ``UNRECORDED`` at a reader is the same rule one lane over.
#:
#: THE WORDS ARE A CROSS-SURFACE CONTRACT, AND THE OTHER TWO SURFACES HAVE NOT WRITTEN THEIR HALF YET.
#: When the web and the handset grow a method label of their own, it must print these same two phrases
#: (sentence-capitalised), or the record sheet and the form describe one stamp in two vocabularies —
#: the drift ``designworkshop/FieldProvenance.tsx`` calls "a requirement rather than a nicety" for its
#: own attribution sentence. This block is the source those two must be written against.
#:
#: A THIRD SURFACE NOW READS THIS DICT RATHER THAN RESTATING IT, WHICH IS WHY THE NAME LOST ITS
#: UNDERSCORE. ``design_workshops._measurement_method_note`` imports it to build the sentence that
#: carries a record's measurement method onto a workshop entry — the layer where the false human
#: attribution had survived, because hydration copied the NUMBER and the stamp stayed on the record.
#: It is imported and not transcribed on the reasoning this comment already gives: a second spelling
#: of these two phrases is how the record sheet and the workshop report come to describe one stamp in
#: two vocabularies. The workshop's own note is a statement about the RECORD's columns ("On the
#: product record: length, breadth (vision model estimate)"), so the phrase does the same work in
#: both places and must be the same phrase.
#:
#: An earlier draft of this comment named ``methodLabel`` as an existing web symbol that had to be kept
#: in sync. It exists nowhere in the repository, and a reader who greps for it concludes the contract
#: is already held up at both ends. A named symbol reads as a promise that something is there; write
#: the phrases down as the thing to be matched, not as a thing already matching.
METHOD_CLAUSES = {
    "VISION_MODEL": "vision model estimate",
    "PHOTO_GEOMETRY": "photo measurement",
}


def field_method(record: Any, column: str) -> str | None:
    """The method stamped on one column by ``records.merge_field_provenance``, or None.

    Reads the stamp rather than the value: ``extraMetadata.fieldProvenance[column]["method"]``, which
    is where the record half writes it and the only place it exists — there is no method column and
    this change deliberately did not add one.
    """
    provenance = meta_of(record).get("fieldProvenance")
    if not isinstance(provenance, dict):
        return None
    stamp = provenance.get(column)
    if not isinstance(stamp, dict):
        return None
    method = stamp.get("method")
    return method if isinstance(method, str) else None


def dims_with_method(record: Any, *columns: str) -> str | None:
    """``dims(...)`` plus a clause naming any dimension in the cell a machine produced.

    **THIS IS THE WHOLE READ SIDE OF THE MEASUREMENT RECORD HALF, AND IT IS NOT COSMETIC.** The
    provenance panel that shows who stamped what is gated on ``adminMode || canViewProvenance``, so if
    the method were shown only there, the researcher who accepted the number, the reviewer who
    approves the record and the officer reading the export would all still read an unqualified
    measurement. This cell is not gated: it reaches the data browser's info panel, every .xlsx sheet
    and the ``/export/products.csv`` / ``/export/tools.csv`` downloads through the one registry, so a
    clause added here is visible to everybody entitled to see the record at all — which is the correct
    audience for "this number is an estimate".

    THE CLAUSE NAMES WHICH NUMBER WHEN THE NUMBERS DISAGREE. A trailing "(vision model estimate)" on a
    cell whose breadth was typed off a tape would overstate the machine's part, so the initials of the
    columns are printed instead — "8.5 x 4 (L: vision model estimate)". The initial is derived from
    the column name rather than transcribed, so a fourth dimension column added later cannot get the
    wrong letter. Only when every printed number shares one machine method does the clause collapse to
    the short form, because then there is nothing to distinguish.
    """
    # ``is not None`` and a truthy ``num`` — the same two conditions :func:`dims` applies, spelled out
    # so a zero-length dimension is printed by one and counted by the other rather than falling
    # between them.
    printed = [
        (column, raw)
        for column in columns
        if (raw := getattr(record, column, None)) is not None and num(raw)
    ]
    # The numbers still come from :func:`dims`, so the "8.5 x 4" join has one definition and this
    # helper only ever adds to it.
    text = dims(*(raw for _, raw in printed))
    if not text:
        return None

    labelled = [
        (column, clause)
        for column, _ in printed
        if (clause := METHOD_CLAUSES.get(field_method(record, column) or ""))
    ]
    if not labelled:
        return text
    if len(labelled) == len(printed) and len({clause for _, clause in labelled}) == 1:
        return f"{text} ({labelled[0][1]})"

    grouped: dict[str, list[str]] = {}
    for column, clause in labelled:
        # "lengthInches" -> "L". Derived, so the letters cannot drift from the column list.
        grouped.setdefault(clause, []).append(column.removesuffix("Inches")[:1].upper())
    parts = [f"{', '.join(initials)}: {clause}" for clause, initials in grouped.items()]
    return f"{text} ({'; '.join(parts)})"


def _rel(record: Any, relation: str, attr: str) -> Any:
    """``record.<relation>.<attr>`` when the relation was included, else None."""
    return getattr(getattr(record, relation, None), attr, None)


def _workshop_titles(record: Any, relation: str = "workshops") -> str | None:
    """Join the titles behind a many-to-many workshop link table."""
    titles = [
        getattr(getattr(link, "workshop", None), "title", None)
        for link in getattr(record, relation, None) or []
    ]
    joined = ", ".join(t for t in titles if t)
    return joined or None


def artisan_names(interview: Any) -> list[str]:
    names: list[str] = []
    for link in getattr(interview, "artisans", None) or []:
        name = (getattr(getattr(link, "artisan", None), "name", None) or "").strip()
        if name:
            names.append(name)
    return names


def interview_label(interview: Any) -> str:
    """An interview is identified by the artisans it covers, not its internal title."""
    names = artisan_names(interview)
    if names:
        return ", ".join(names)
    return (getattr(interview, "title", None) or "").strip() or "Interview"


# ---------------------------------------------------------------------------
# Spec model
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class FieldSpec:
    label: str
    get: Callable[[Any], Any]


@dataclass(frozen=True)
class RecordSpec:
    """How one record type is titled, coloured and described."""

    kind: str
    """Stable slug: workshop, craft, artisan, product, process, tool, interview."""
    label: str
    """Singular human noun, used as the first table column ("Artisan")."""
    plural: str
    """Sheet name / folder heading ("Artisans")."""
    color: str
    """Brand colour for the sheet tab and the UI pill."""
    title: Callable[[Any], str]
    """The record's display name."""
    fields: tuple[FieldSpec, ...]

    def field_pairs(self, record: Any) -> list[tuple[str, Any]]:
        return [(f.label, f.get(record)) for f in self.fields]


def _f(label: str, get: Callable[[Any], Any]) -> FieldSpec:
    return FieldSpec(label=label, get=get)


# ---------------------------------------------------------------------------
# The registry
# ---------------------------------------------------------------------------

WORKSHOP = RecordSpec(
    kind="workshop",
    label="Workshop",
    plural="Workshops",
    color="#5B21B6",
    title=lambda w: (getattr(w, "title", None) or "").strip() or "Workshop",
    fields=(
        _f("Place", lambda w: w.place),
        _f("Start date", lambda w: date_str(w.startDate or w.date)),
        _f("End date", lambda w: date_str(w.endDate)),
        _f("Description", lambda w: w.description),
        _f("Notes", lambda w: w.notes),
    ),
)

CRAFT = RecordSpec(
    kind="craft",
    label="Craft",
    plural="Crafts",
    color="#7C3AED",
    title=lambda c: (getattr(c, "name", None) or "").strip() or "Craft",
    fields=(
        _f("Local name", lambda c: getattr(c, "localName", None)),
        _f("Category", lambda c: c.category),
        _f("Place", lambda c: c.place),
        _f("Description", lambda c: c.description),
        _f("Workshops", _workshop_titles),
    ),
)

ARTISAN = RecordSpec(
    kind="artisan",
    label="Artisan",
    plural="Artisans",
    color="#0E7490",
    title=lambda a: (getattr(a, "name", None) or "").strip() or "Artisan",
    fields=(
        _f("Local name", lambda a: a.localName),
        _f("Workshop", _workshop_titles),
        _f("Craft", lambda a: _rel(a, "craft", "name")),
        # The village named in the artisan's stated address, falling back to the free-text `place`
        # box the researchers used instead while there was no column. That box holds compound
        # answers — "Bagru, Jaipur, Rajasthan", "Jaipur, Sanganeri, Rajasthan" — precisely because
        # it was carrying the village, the district and the state at once; it keeps printing
        # verbatim rather than being split, since "Rudraprayag, Dehradun" names two districts of
        # Uttarakhand and no parser can say which one the researcher meant.
        _f("Village/Place", lambda a: _rel(a, "location", "village") or a.place),
        # Pointed at the real column by 20260727120000_location_stated_address. This label has been
        # shipped and user-visible since this registry was written — the data browser's info card
        # and record table, every details.txt in /export/dataset, the artisan sheet of the
        # /data/report workbook — and it has been BLANK on every one of the sixteen artisans,
        # because it read an extraMetadata key whose only writer was deleted from the artisan form
        # and never replaced. The metadata fallback stays for the same reason State's does: it costs
        # nothing and it is the only thing that would print for a record that predates the column.
        _f(
            "District",
            lambda a: _rel(a, "location", "district") or meta_val(meta_of(a), "district"),
        ),
        # State and pincode became real columns on the shared Location model, but the artisans
        # recorded before that kept their state as free text in extraMetadata. Reading the column
        # first and falling back to the metadata is what lets both generations print in one sheet;
        # dropping the fallback would blank the historical rows, and keeping only the fallback would
        # blank every row entered from the new dropdown. ``_rel`` answers None when the location
        # relation was not included, so a caller that loads artisans without it degrades to exactly
        # the old behaviour rather than erroring.
        _f("State", lambda a: _rel(a, "location", "state") or meta_val(meta_of(a), "state")),
        _f("Address", lambda a: a.address),
        _f(
            "Pincode",
            lambda a: _rel(a, "location", "pincode")
            or meta_val(meta_of(a), "pincode", "pinCode", "postalCode"),
        ),
        _f("Phone", lambda a: a.phone),
        _f("Email", lambda a: a.email),
        # Aadhaar and the Pehchan (PM Vishwakarma) card are regulated personal data and this spec
        # feeds every SHARED surface — the data browser, the .xlsx workbook, generated details.txt,
        # the details.txt inside a grantable /export/dataset zip. Only the last four characters go
        # out, which is enough to confirm the right person and useless as an identifier. The full
        # numbers stay readable through the artisan's own record for the people entitled to them.
        #
        # BOTH numbers, through the same function. The card number used to print verbatim here while
        # the Aadhaar beside it was masked, so a full PM Vishwakarma ID reached every grantee,
        # dataset downloader and reviewer — a rule that held on the API responses
        # (``records.mask_identity_number``, which is this same ``mask_aadhaar``) and nowhere else.
        # A registry that disagrees with the API about which numbers are secret is the whole reason
        # this module exists.
        _f("Aadhaar number", lambda a: mask_aadhaar(a.aadhaarNumber)),
        _f(
            "Artisan Pehchan Card",
            lambda a: "Yes" if a.pehchanCardAvailable else "No",
        ),
        _f("Pehchan card number", lambda a: mask_aadhaar(a.pehchanCardNumber)),
        # DATE OF BIRTH IS PRINTED AND AGE IS DERIVED BESIDE IT, in that order, because they are two
        # different kinds of statement: the date is what the artisan told a researcher and does not
        # change, and the age is a fact about today that this sheet computes each time it is drawn.
        # Printing only the age would put a number on a record sheet that is quietly wrong from the
        # next birthday onwards — which is what this sheet did for years, reading a legacy
        # `extraMetadata` key no form had written since the raw JSON textarea was removed.
        _f("Date of birth", lambda a: getattr(a, "dateOfBirth", None)),
        # `is not None` and NOT `or`: a derived 0 is a real answer and `or` would read it
        # as absent and print a stale metadata value instead.
        _f("Age", lambda a: _first_answer(
            derive_age(getattr(a, "dateOfBirth", None)),
            meta_val(meta_of(a), "age"),
        )),
        _f("Gender", lambda a: a.gender),
        # THE JOIN DATE IS PRINTED AND THE EXPERIENCE IS DERIVED BESIDE IT, in that order, for the
        # same reason the date of birth and the age are printed that way six lines up: they are two
        # different kinds of statement. The date is what the artisan told a researcher and does not
        # change; the number is a fact about today that this sheet works out each time it is drawn.
        _f("Practising since", lambda a: getattr(a, "craftStartDate", None)),
        _f(
            "Experience (years)",
            # THREE SOURCES, THE SAME THREE THE WORKSHOP READS, IN THE SAME ORDER — the derived
            # value from the join date, then the stated column, then the legacy metadata. Written
            # here through `_first_answer` because this one lambda feeds the data browser's info
            # card, the /data/report workbook, `details.txt` inside the dataset zip and the
            # /export CSVs: four surfaces that would otherwise disagree with the participant table
            # about one artisan's experience, and disagree only for the rows that have a join date.
            #
            # `_first_answer` and NOT `or`: zero years is a real answer (a first-month apprentice)
            # and `or` would read it as absent and print a staler value instead. The legacy branch
            # is the migration's deliberate refusal to guess at "30+" and "about 30", and those
            # rows are the oldest and best documented — it must not be dropped.
            lambda a: _first_answer(
                derive_experience_years(getattr(a, "craftStartDate", None)),
                getattr(a, "experienceYears", None),
                meta_val(meta_of(a), "experienceYears", "experience", "yearsOfExperience"),
            ),
        ),
        _f("Do's", lambda a: a.dos),
        _f("Don'ts", lambda a: a.donts),
        _f("Notes", lambda a: a.notes),
    ),
)

PRODUCT = RecordSpec(
    kind="product",
    label="Product",
    plural="Products",
    color="#B45309",
    title=lambda p: (getattr(p, "productName", None) or "").strip() or "Product",
    fields=(
        _f("Local name", lambda p: p.localName),
        _f("Workshop", lambda p: _rel(p, "workshop", "title")),
        _f("Craft", lambda p: p.craftName),
        _f("Artisan", lambda p: p.artisanName),
        _f("Place", lambda p: p.place),
        _f("Type", lambda p: enum_label(p.productType)),
        # Named columns rather than values, because the cell now says HOW each number was measured
        # and the method is stamped per column in ``extraMetadata.fieldProvenance``. See
        # :func:`dims_with_method`.
        _f(
            "Dimensions (LxBxH in)",
            lambda p: dims_with_method(p, "lengthInches", "breadthInches", "heightInches"),
        ),
        _f("Size", lambda p: p.size),
        _f("Time to complete", lambda p: p.timeTakenToCompleteProduct),
        _f("Cost of making", lambda p: money(p.costOfMaking)),
        _f("Selling price", lambda p: money(p.sellingPrice)),
        _f("Market demand", lambda p: enum_label(p.marketDemand)),
        _f("Raw materials", lambda p: p.rawMaterialsUsed),
        _f("Main tools", lambda p: p.mainToolsUsed),
        _f("Function/use", lambda p: p.productFunctionUse),
        _f("Remarks", lambda p: p.remarks),
    ),
)

TOOL = RecordSpec(
    kind="tool",
    label="Tool",
    plural="Tools",
    color="#15803D",
    title=lambda t: (getattr(t, "toolkitName", None) or "").strip() or "Tool",
    fields=(
        _f("Local name", lambda t: t.localName),
        _f("English name", lambda t: t.englishName),
        _f("Workshop", lambda t: _rel(t, "workshop", "title")),
        _f("Craft", lambda t: t.craftName),
        _f("Artisan", lambda t: t.artisanName),
        _f("Place", lambda t: t.place),
        _f("Usage", lambda t: t.processUsedIn),
        _f("Material", lambda t: t.material),
        _f("Years in use", lambda t: t.yearsInUse),
        _f(
            "Dimensions (LxBxH in)",
            lambda t: dims_with_method(t, "lengthInches", "breadthInches", "heightInches"),
        ),
        # THIS CELL WAS "Dimensions (LxB in)" AND THE PARAGRAPH HERE SAID ``ToolDocumentation`` HAS
        # NO ``heightInches``, UNTIL 2026-08-27. The old text is quoted rather than deleted because
        # of what it was doing while it stood: it read as a settled limitation of the design, and it
        # told anybody arriving to finish the tool half that an accepted vision-model tool height
        # "is recorded as nothing" and that the remedy was an unmade owner decision. It read —
        #
        #     *"``ToolDocumentation`` has no ``heightInches``: the reading lands in the plain
        #     ``height`` column, which is not in ``measurement_provenance.DIMENSION_FIELDS`` …
        #     Widening ``DIMENSION_FIELDS`` to ``height`` is the repo owner's call and is not free:
        #     ``width`` beside it is a plain typed input."*
        #
        # — and it was the last copy of a sentence the rest of the repository had already retracted
        # (``routes/tools.py``, ``measurement_provenance``, ``design_workshops``), which is exactly
        # the trap those retractions were written to close. The owner made the call and it was not a
        # widening: ``ToolDocumentation.heightInches`` landed beside its two siblings (additive
        # migration ``20260827120000_tool_height_inches``), ``DIMENSION_FIELDS`` already named all
        # three, and both clients propose into it — ``ToolForm``'s ``MEASURE_COLUMNS`` and Android's
        # ``TOOL_MEASURE_DIMENSIONS``. So this cell is the triple the product's has always been.
        # Re-check with ``grep -n "heightInches" backend/prisma/schema.prisma``: three lines on
        # 2026-08-27 — the product's column, the tool's, and a doc comment above the tool's.
        #
        # THE HEIGHT BELOW IS A DIFFERENT COLUMN FROM THE THIRD NUMBER IN THE CELL ABOVE, which is
        # why both print and neither is redundant. ``height`` is the OLD unit-less column, kept
        # because rows already hold values in it and nothing in the database can say what unit those
        # are in. It is outside ``DIMENSION_FIELDS`` and stays an ordinary typed input, so it prints
        # bare and carries no method clause, exactly as ``width``/``thickness``/``weight``/``radius``
        # do.
        _f("Height", lambda t: num(t.height)),
        _f("Width", lambda t: num(t.width)),
        _f("Thickness", lambda t: num(t.thickness)),
        _f("Weight", lambda t: num(t.weight)),
        _f("Radius", lambda t: num(t.radius)),
        _f("Cost", lambda t: money(t.replacementCost)),
        _f("Maker", lambda t: enum_label(t.maker)),
        _f("Tradition", lambda t: enum_label(t.traditionType)),
        _f("Improvement suggestions", lambda t: t.suggestionsForToolImprovement),
        _f("Remarks", lambda t: t.remarks),
    ),
)

PROCESS = RecordSpec(
    kind="process",
    label="Process",
    plural="Processes",
    color="#4338CA",
    title=lambda pr: (getattr(pr, "name", None) or "").strip() or "Process",
    fields=(
        _f("Product", lambda pr: _rel(pr, "product", "productName")),
        _f("Artisan", lambda pr: _rel(pr, "product", "artisanName")),
        _f("Craft", lambda pr: _rel(pr, "product", "craftName")),
        _f(
            "Pre-process available",
            lambda pr: "Yes" if getattr(pr, "preProcessAvailable", False) else None,
        ),
        _f("Steps", lambda pr: len(getattr(pr, "steps", None) or []) or None),
        _f("Notes", lambda pr: pr.notes),
    ),
)

INTERVIEW = RecordSpec(
    kind="interview",
    label="Interview",
    plural="Questionnaires",
    color="#BE185D",
    title=interview_label,
    fields=(
        _f("Artisans", lambda i: ", ".join(artisan_names(i)) or None),
        _f("Location", lambda i: i.place),
        _f("Language", lambda i: i.language),
        _f("Recorded at", lambda i: date_str(i.interviewDate or i.recordedAt)),
        _f("Notes", lambda i: i.notes),
    ),
)

SPECS: dict[str, RecordSpec] = {
    spec.kind: spec for spec in (WORKSHOP, CRAFT, ARTISAN, PRODUCT, TOOL, PROCESS, INTERVIEW)
}

# Colours for the sheets that are not a single record type.
MEDIA_COLOR = "#334155"
TRANSCRIPT_COLOR = "#334155"
OVERVIEW_COLOR = "#5B21B6"


def spec_for(kind: str) -> RecordSpec | None:
    return SPECS.get(kind)


# ---------------------------------------------------------------------------
# Presentations
# ---------------------------------------------------------------------------

PROVENANCE_COLUMNS = ("Status", "Created by", "Created on")

# Every sheet/table carries its record's media inline so a reader never has to cross-
# reference the Media sheet to find out what was captured for a row.
MEDIA_COLUMNS = ("Media count", "Media files", "Media URLs")


def created_by(record: Any) -> str:
    return getattr(getattr(record, "createdBy", None), "name", None) or ""


def provenance_row(record: Any) -> list[str]:
    return [
        cell(enum_label(getattr(record, "status", None))),
        created_by(record),
        cell(date_str(getattr(record, "createdAt", None))),
    ]


def media_row(media: Iterable[Any]) -> list[str]:
    """The three media cells for one record: how many, their names, their URLs."""
    items = list(media or [])
    if not items:
        return ["0", "", ""]
    names = [cell(getattr(m, "originalFilename", None)) or cell(m.id) for m in items]
    urls = [cell(getattr(m, "url", None)) for m in items]
    return [str(len(items)), " | ".join(n for n in names if n), " | ".join(u for u in urls if u)]


def info_panel(kind: str, record: Any) -> dict[str, Any] | None:
    """``{title, fields}`` with blank values dropped — the browser's record card."""
    spec = SPECS.get(kind)
    if spec is None:
        return None
    fields: list[dict[str, str]] = []
    for label, value in spec.field_pairs(record):
        text = cell(value)
        if text:
            fields.append({"label": label, "value": text})
    return {"title": spec.title(record), "fields": fields}


def info_text(info: dict[str, Any] | None) -> str:
    """Render an info panel as ``details.txt`` content (title line + "Label: value")."""
    if not info:
        return ""
    lines = [str(info.get("title") or "").strip()]
    lines.extend(f"{f['label']}: {f['value']}" for f in info.get("fields") or [])
    return "\n".join(line for line in lines if line)


def sheet_columns(kind: str) -> list[str]:
    """Full column list for a record type's sheet or in-folder table."""
    spec = SPECS[kind]
    return [
        spec.label,
        *(f.label for f in spec.fields),
        *PROVENANCE_COLUMNS,
        *MEDIA_COLUMNS,
    ]


def sheet_row(kind: str, record: Any, media: Iterable[Any] = ()) -> list[str]:
    """One rectangular row matching :func:`sheet_columns` exactly."""
    spec = SPECS[kind]
    return [
        cell(spec.title(record)),
        *(cell(value) for _, value in spec.field_pairs(record)),
        *provenance_row(record),
        *media_row(media),
    ]


def record_table(
    kind: str,
    records: Iterable[Any],
    media_by_record: dict[str, list[Any]] | None = None,
) -> dict[str, Any]:
    """A rectangular ``{kind, label, color, columns, rows}`` grid for a set of records.

    Unlike :func:`info_panel` this keeps every column even when a cell is empty, so the
    browser can render it as a real table rather than a definition list.
    """
    spec = SPECS[kind]
    lookup = media_by_record or {}
    rows = [sheet_row(kind, record, lookup.get(record.id, [])) for record in records]
    return {
        "kind": kind,
        "label": spec.plural,
        "color": spec.color,
        "columns": sheet_columns(kind),
        "rows": rows,
    }
