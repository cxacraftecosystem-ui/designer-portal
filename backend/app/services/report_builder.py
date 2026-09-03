"""Turns captured workshop data into a :class:`ReportDocument`, driven by the field registry.

This is the join between the three declarations that already exist:

    stage_schema.STAGES     what a field IS      (key, type, tier, report role)
    report_templates        what a report SHOWS  (which stages, in what order, how)
    workshop data           what was CAPTURED

and it is deliberately *generic*. There is no per-stage code in this module. A stage is printed
by walking its :class:`EntitySpec`s and dispatching on each field's
:class:`~app.services.stage_schema.ReportRole` — which means a field added to the registry
tomorrow appears in every one of the six templates, in both file formats, on both surfaces,
with no change here. The alternative, twenty-two hand-written section renderers, is twenty-two
places for a new field to be forgotten, and the forgetting is silent: the report simply omits
it and nobody notices until an officer asks where the cost sheet went.

The one thing this module owns outright is *editorial judgement* — the rules about what makes a
readable government report rather than a data dump:

- An empty optional field prints nothing. An empty *required* field prints "Not recorded.", so
  a gap in the record is visible as a gap rather than as an absence.
- A collection with no rows prints a single italic line saying so, not an empty table with a
  header, because a header over nothing reads as a rendering fault.
- Long text becomes prose paragraphs under their own sub-heading; short values become a
  key-value grid. Mixing the two in one block is what made the first drafts unreadable.
- A photo is never printed without its caption when the registry declares one, and never
  printed at all when the template says the audience does not want photographs.

THE TWO EXCEPTIONS TO "no per-stage code", both named rather than scattered, because a reader who
believes the first paragraph and then meets ``"COSTING_MARKET_LINKAGE"`` in the middle of a method
will assume the rule was abandoned:

- :data:`MAP_VENUE_STAGE` and the constants beside it. A map of "where the workshop was and where
  its people came from" is a statement about two specific stages, and no ``ReportRole`` could
  express it.
- :data:`FIGURES`. Which measure is worth a chart is editorial judgement about that stage's
  meaning — cost by head, prototypes by decision — and it cannot be derived from a field's type.

Both are DECLARATIONS at module scope, so a stage rename is one edit in one place rather than a
figure that silently stops being drawn. That silence is why they are not inlined: nobody notices a
missing illustration until the ministry asks for it.
"""

from __future__ import annotations

import math
import re
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from typing import Any

from app.services import rich_text
from app.services.report_ai_layers import ai_layers_of, append_ai_layer_annexure
from app.services.report_annexures import append_transcript_annexure, transcripts_of
from app.services.report_custom_sections import (
    append_custom_section,
    custom_scoring,
    custom_section_of,
    sections_hidden_by_tier,
)
from app.services.report_model import (
    Align,
    ChartBlock,
    ChartKind,
    CoverBlock,
    DocumentBuilder,
    ImageBlock,
    ImageGridBlock,
    ImageRef,
    KeyValueBlock,
    MapBlock,
    MapPoint,
    MapPointKind,
    MetricRowBlock,
    PageBreakBlock,
    ParaStyle,
    ReportDocument,
    ReportMeta,
    ReportTheme,
    Run,
    SignatureBlock,
    TableBlock,
    TableColumn,
    TocBlock,
    clean_text,
    runs_of,
)
from app.services.report_questionnaires import (
    append_questionnaire_annexure,
    questionnaires_of,
)
from app.services.report_templates import (
    Presentation,
    ReportTemplate,
    SpecialSection,
    TemplateSection,
    template as get_template,
)
from app.services.stage_schema import (
    Cardinality,
    EntitySpec,
    FieldSpec,
    FieldType,
    ReportRole,
    StageSpec,
    enum_label,
    reference_hydration_for,
    stage_completeness,
    stages,
)

# Resolves a stored media id to something a renderer can embed. Returning None is normal — a
# photo that failed to sync is not an error, it is a gap the report should survive.
MediaResolver = Callable[[str], ImageRef | None]


@dataclass(frozen=True, slots=True)
class ReferencedRecord:
    """The little the report needs to know about a record a REF field points AT.

    A REF field stores an id and nothing else. The display fields a designer chose are COPIED onto
    the stage entry at save time — ``design_workshops.REFERENCE_HYDRATION`` explains at length why
    a submitted report must never re-resolve a name through a live table — so for prose this
    builder needs no lookup at all and deliberately performs none.

    Two things hydration does not put on every row that needs them, and this is what carries
    those:

    * THE PHOTOGRAPH OF A RECORD WHOSE MAPPING SEEDS NONE. ``participant.artisanRef`` seeds a
      participant's ``photo`` and ``existingProduct.productRef`` seeds ``productPhotos``, so for
      those the frozen copy is already on the row and ``_images`` dedupes by media id.
      ``prototype.productRef`` and ``existingProduct.artisanRef`` map ``{"name": ...}`` and nothing
      else, and ``workshopSetup.craftRef`` maps only the craft's two names — for those three this
      carrier is the only way the picture reaches the page at all. Without it the report described
      a prototype of a documented product with the product's photograph nowhere in it, while the
      picture sat in the media table one join away.

      ``craftRef`` is easy to miss in that list and was missed: ``Craft`` is the one model whose
      ``data`` lambda publishes the label column under a name of its own, so it also needs an entry
      in :data:`_REFERENCE_NAME_SOURCES` before its borrowed photograph can be captioned from the
      frozen copy.

      THE REASON IS NOT "HYDRATION MUST NEVER SEED A GALLERY", WHICH IS WHAT THESE LINES USED TO
      SAY AND IS NOT THE RULE. ``design_workshops.hydrate_entries`` states the real one: a gallery
      is SEEDED WHEN EMPTY and never overwritten. ``existingProduct.productPhotos`` is seeded from
      the documented product's own photograph deliberately — its declaration in
      ``stage_definitions`` spends a sentence on it, so that a designer who is not told does not
      add the same picture a second time. Only ``prototype``'s gallery is left unseeded, for the
      separate reason written at ``prototype.productRef``: a prototype is defined by how it differs
      from the product it was based on. So the circulating rule "hydration must not seed the
      galleries of existingProduct / prototype" holds today for ``prototype`` ALONE, and anybody
      "restoring" it by deleting the ``photo`` -> ``productPhotos`` mapping would cost every new
      product row the catalogue photograph its own help text promises, while only-fill-blanks
      leaves every row saved before the deletion holding theirs — an inconsistency that appears
      nowhere in the report and cannot be seen without diffing two rows.
    * WHERE AN ARTISAN LIVED, FOR ROWS SAVED BEFORE THE MAPPING WIDENED. This bullet used to say
      "No participant field holds a district: the roster records a village as free text". That is
      no longer true, and reading it as true is what kept the map wrong:
      ``REFERENCE_HYDRATION["participant.artisanRef"]`` copies ``village``, ``district``,
      ``state``, ``pincode`` and ``address`` onto the roster row at save time, and ``participant``
      declares all five. :meth:`ReportBuilder._artisan_points` therefore reads the ROW's frozen
      copy first, and ``place``/``district``/``state`` below are the fallback for rows hydrated
      before that widening and for rows whose ref was never picked. Do not delete them — those
      rows would drop off the map — and do not promote them back to first choice: preferring the
      live record is exactly what let a ministry's map and the participant table two pages earlier
      disagree about where one artisan lives.

    ``label`` REACHES PAPER. It is the caption under a borrowed photograph, so it is not internal
    bookkeeping: :meth:`ReportBuilder._reference_caption` prefers the row's own frozen name where
    hydration put one there, and falls back to this only where it did not.

    Every field is optional and an absent entry is ordinary — a roster row typed by hand on day two
    has no artisan record behind it at all, and the report must be exactly as complete as it can be
    rather than failing because one of thirty rows was not picked from a list.
    """

    model: str = ""  # the REFERENCE_MODELS key: "Artisan", "ProductDocumentation", …
    label: str = ""  # what the picker showed: the artisan's or the product's name
    photo: str = ""  # ONE media id, resolvable through the same MediaResolver as any other
    place: str = ""  # the free text the record states: "Barpali, Bargarh, Odisha"
    district: str = ""
    state: str = ""


#: model -> the extra hydration SOURCE keys under which that model publishes its DISPLAY NAME.
#: ``"name"`` is always accepted and is not repeated here; this table is only for a model whose
#: ``data`` lambda calls the label column something else.
#:
#: ONE MODEL NEEDS IT AND IT IS THE ONE THAT REACHES THE COVER PAGE.
#: ``design_workshops.REFERENCE_MODELS["Craft"].data`` emits ``{"craftName": r.name,
#: "craftLocalName": r.localName}`` while its ``label`` is ``r.name`` — the same column under a
#: different key, because stage 1's cover asks for "Craft name" and the mapping is one-to-one with
#: the boxes it fills rather than with the record's column names. ``Craft`` also declares
#: ``media_field="craftId"``, so a craft photograph really does reach :meth:`ReportBuilder._images`'
#: second pass; matching the literal ``"name"`` alone left that one picture captioned from the LIVE
#: record while ``workshopSetup.craftName`` — a COVER_FIELD — printed the frozen copy. One page,
#: two names for one craft, which is the exact failure :meth:`ReportBuilder._reference_caption` was
#: written to close.
#:
#: Declared here rather than derived because this module is also the on-device report builder and
#: may not import ``design_workshops`` (which queries). ``test_report_figures`` walks
#: ``REFERENCE_MODELS`` and fails if a model with a ``media_field`` publishes its name under a key
#: no mapping here accepts, so the next renamed key fails in the suite and not on a ministry's
#: cover page.
_REFERENCE_NAME_SOURCES: dict[str, tuple[str, ...]] = {"Craft": ("craftName",)}


@dataclass
class WorkshopData:
    """Everything the builder needs about one workshop, already loaded.

    The shape is the same on both surfaces: the server assembles it from ``DwStageEntry`` rows,
    the phone from its local draft JSON. Keeping one shape is what lets the on-device report and
    the server report be the same document rather than two similar ones.
    """

    workshop_id: str
    title: str
    # stage key -> the singleton entity's data
    singletons: dict[str, dict[str, Any]] = field(default_factory=dict)
    # stage key -> entity key -> rows, already in sort order
    collections: dict[str, dict[str, list[dict[str, Any]]]] = field(default_factory=dict)
    # Referenced record id -> what the report knows about it. Loaded once by the caller for every
    # id any REF field in the record holds; empty is a supported state and simply means the map
    # loses its artisan pins and a REF-only photograph is not placed. Never a reason to fail.
    references: dict[str, ReferencedRecord] = field(default_factory=dict)
    #: ``"State|District" -> (lat, lon)`` for every district this repository can place, loaded by
    #: the caller exactly as ``references`` is.
    #:
    #: THIS IS WHAT MAKES THE MAP WORK OUTSIDE THE CURATED TOWNS. ``place_atlas`` is a hand-checked
    #: table of a few dozen craft towns; anywhere it does not name, every artisan used to fold onto
    #: the state capital and the map asserted they all came from the capital city. These anchors
    #: cover all 795 districts in ``address.DISTRICTS_BY_STATE`` by NAME, positioned from the real
    #: pins the repository already holds (``geography.DistrictAnchors``), so a workshop in a state
    #: nobody has curated still draws its artisans in their own districts.
    #:
    #: Passed as plain data rather than as a ``DistrictAnchors`` object because this module is also
    #: the on-device report builder: it may not query, and it may not import a class that does.
    district_points: dict[str, tuple[float, float]] = field(default_factory=dict)
    generated_at: str = ""
    generated_by: str = ""
    #: ``entryId -> {fieldKey: stamp}`` — who last set each field, for every row that reached this
    #: builder. Keyed by entry id and NOT nested inside ``singletons``/``collections`` because those
    #: dicts are handed to the renderers as the field values themselves; a stamp map inside one
    #: would be walked as a field and printed as a cell.
    #:
    #: THE REPORT DOES NOT PRINT IT TODAY AND THAT IS THE POINT OF CARRYING IT ANYWAY. The .docx is
    #: a dated observation and prints what was captured; attribution is an editorial decision for a
    #: template, not a property of the data. But the builder is also the on-device report, and a
    #: field the server resolves and the phone does not is exactly how the two documents drift —
    #: so it is loaded on both sides now, while the shape is one line, rather than retrofitted onto
    #: two builders later. ``services/entry_provenance`` says what a stamp means.
    field_provenance: dict[str, dict[str, Any]] = field(default_factory=dict)

    def singleton(self, stage_key: str) -> dict[str, Any]:
        return self.singletons.get(stage_key) or {}

    def provenance(self, entry_id: Any) -> dict[str, Any]:
        """Who last set each field of one row. ``{}`` for a row nobody recorded authorship for.

        Takes the ``_entryId`` the renderers already carry, so a section that wants to attribute a
        value asks with the id it is holding rather than tracking a parallel index.
        """
        if not entry_id or not isinstance(entry_id, str):
            return {}
        return self.field_provenance.get(entry_id) or {}

    def rows(self, stage_key: str, entity_key: str) -> list[dict[str, Any]]:
        return (self.collections.get(stage_key) or {}).get(entity_key) or []

    def value(self, stage_key: str, key: str, default: Any = None) -> Any:
        return self.singleton(stage_key).get(key, default)

    def reference(self, ref_id: Any) -> ReferencedRecord | None:
        """The record one REF value points at, or None when nothing was loaded for it."""
        if not ref_id or not isinstance(ref_id, str):
            return None
        return self.references.get(ref_id)


# --------------------------------------------------------------------------------------
# Value formatting
# --------------------------------------------------------------------------------------


def _group_indian(digits: str) -> str:
    """Group a digit string the Indian way: 12,34,567 rather than 1,234,567.

    Not a cosmetic choice. Every cost sheet in this report is read by an officer who writes
    lakhs and crores, and a Western-grouped figure is misread at a glance — which for a number
    that becomes a sanctioned amount is a real error, not a stylistic one.
    """
    if len(digits) <= 3:
        return digits
    head, tail = digits[:-3], digits[-3:]
    parts: list[str] = []
    while len(head) > 2:
        parts.insert(0, head[-2:])
        head = head[:-2]
    if head:
        parts.insert(0, head)
    return ",".join([*parts, tail])


#: A generated record id, as opposed to something a person typed into a reference field.
#:
#: Prisma's ``cuid`` is ``c`` followed by 24 lower-case alphanumerics, and every id in this system
#: is one. The test is deliberately broader than that exact shape — long, unbroken, no capitals and
#: no punctuation — because the question being asked is not "is this a cuid" but "would a reader
#: recognise this as a name". "SK-01", "Runner v2" and "प्रोटोटाइप 3" all fail it and are printed;
#: "cmsik2jg8000eh8xc1lcy661a" passes it and is not.
_OPAQUE_ID = re.compile(r"^[a-z0-9]{16,}$")


def _looks_like_an_id(text: str) -> bool:
    return bool(_OPAQUE_ID.match(text.strip()))


def format_value(spec: FieldSpec, value: Any) -> str:
    """Render one stored value as the report should print it."""
    if value is None or value == "" or value == []:
        return ""
    t = spec.type

    if t is FieldType.RICH_TEXT:
        # THE MARKS ARE DROPPED HERE ON PURPOSE, and only here. This function's contract is one
        # string, so a rich-text value reaching it must be flattened; the formatted path is
        # ``rich_text.to_report_blocks`` and the in-a-table-cell path is ``rich_text.plain_runs``,
        # both of which the renderers below reach for before they ever call this.
        #
        # What this branch actually prevents is far worse than lost bold. A RICH_TEXT value is a
        # dict, and without this it fell through to ``clean_text``, which stringifies whatever it
        # is given — so the report printed the literal text
        # ``{'blocks': [{'kind': 'PARAGRAPH', 'spans': [{'text': 'The cluster …'}]}]}``
        # into a document submitted to a ministry, and every emptiness check above read that
        # JSON-shaped string as a filled field, so nothing anywhere reported a problem.
        return rich_text.to_plain(value)
    if t is FieldType.ENUM:
        return enum_label(spec.enum, str(value))
    if t is FieldType.MULTI_ENUM:
        # A RECORD-BACKED MULTI_ENUM HAS NO ENUM TABLE TO LOOK ITS TOKENS UP IN. (2026-09-03)
        #
        # ``enum_label`` falls back to the raw token when the list does not name it, and for a
        # field whose options come from ``ToolDocumentation`` the list is EMPTY — so every token
        # would fall back, and this function would quietly print a column of cuids into a
        # ministry's document. Joining them plainly is the honest floor and is what the field's
        # own predecessor did: these two boxes were TAGS until 2026-09-03 and every value stored
        # under them before that is a tool NAME, which prints correctly here and nowhere else.
        #
        # THE RESOLVED ANSWER IS ``ReportBuilder._value``'s, one layer up, and it is the one a
        # generated report actually uses: it has ``load_report_references`` in hand and turns an
        # id into the record's label, printing this string only for the tokens it cannot resolve.
        # This branch is the answer for every other caller — completeness, search text, the
        # on-device mirror — none of which can reach the repository.
        if spec.ref_model:
            return ", ".join(str(v) for v in value)
        return ", ".join(enum_label(spec.enum, str(v)) for v in value)
    if t is FieldType.TAGS:
        return ", ".join(str(v) for v in value)
    if t is FieldType.BOOL:
        return "Yes" if value else "No"
    # THE NUMERIC BRANCHES GO THROUGH ``_as_number``, which rejects NaN and the infinities as
    # well as the unparseable — a bare ``float()`` reads all three happily. ``coerce_value`` now
    # refuses them on the way in, and this is the second lock, for the rows already stored before
    # it did: MONEY keeps its value as a string, so a stage saved with "NaN" holds the literal
    # "nan" and printed as "₹ nan." on the cover preview, in the .docx submitted to a ministry
    # and in the on-device report. The charts have always dropped those rows (``_as_number`` is
    # what they use), so the table and the figure beside it disagreed with nothing to say why;
    # falling through to ``clean_text`` prints the stored text as the unreadable thing it is
    # rather than dressing it up as an amount, and now both surfaces agree it is not a number.
    if t is FieldType.MONEY:
        amount = _as_number(value)
        if amount is None:
            return clean_text(value)
        whole, _, frac = f"{amount:.2f}".partition(".")
        sign = "-" if whole.startswith("-") else ""
        return f"{sign}₹ {_group_indian(whole.lstrip('-'))}.{frac}"
    if t is FieldType.PERCENT:
        percent = _as_number(value)
        return f"{percent:g}%" if percent is not None else clean_text(value)
    if t in (FieldType.INT, FieldType.DECIMAL):
        number = _as_number(value)
        if number is None:
            return clean_text(value)
        text = f"{number:.0f}" if t is FieldType.INT else f"{number:g}"
        if abs(number) >= 10000:
            whole, _, frac = f"{abs(number):.2f}".partition(".")
            text = ("-" if number < 0 else "") + _group_indian(whole)
            if t is not FieldType.INT and frac.rstrip("0"):
                text += "." + frac.rstrip("0")
        return f"{text} {spec.unit}".strip() if spec.unit else text
    if t is FieldType.DATE:
        return _format_date(str(value))
    if t is FieldType.GEO:
        if isinstance(value, dict):
            return f"{float(value.get('lat', 0)):.5f}, {float(value.get('lon', 0)):.5f}"
        return clean_text(value)
    if t in (FieldType.IMAGE, FieldType.IMAGE_LIST):
        return ""  # a picture never prints as text; it is placed by ``ReportBuilder._images``
    if t.is_media:
        # FILE, AUDIO AND VIDEO HAVE NO IMAGE PATH TO BE PLACED BY, and this branch used to claim
        # for all five media types that "it is placed by the image path". ``_images`` is the only
        # placement path there is and it filters on IMAGE and IMAGE_LIST, so the eight FILE, five
        # AUDIO and four VIDEO fields the registry declares fell through here to "" and printed on
        # no surface whatsoever. A designer attached the ministry's sanction order at stage 1 and
        # the .docx the officer received did not mention that a sanction order existed. The tier
        # warning then made it worse rather than better: ``fields_hidden_by_tier`` reads
        # ``_is_filled``, which is True for a media field holding ids, so a Compact summary named
        # "Sanction order document" in "generate the report with a template that captures every
        # tier to include them" — and the Advanced templates printed nothing for it either. The
        # designer regenerated sixty pages to recover a field no template could carry.
        #
        # A COUNT AND A NOUN, NOT A FILENAME. The stored value is a media id; the name the designer
        # uploaded lives on the ``MediaFile`` row, and this module may not query for it — it is
        # also the on-device report builder, which has no network. So the line says the honest
        # minimum, which is the whole of what the entry itself states: that an attachment exists
        # and how many. Naming the file needs the resolver widened to carry filenames and is
        # tracked as its own change; saying nothing at all was the defect.
        count = len(_media_ids(value))
        if not count:
            return ""
        singular, plural = {
            FieldType.AUDIO: ("recording", "recordings"),
            FieldType.VIDEO: ("video", "videos"),
        }.get(t, ("document", "documents"))
        return f"{count} {singular if count == 1 else plural} attached"

    text = clean_text(value)
    return f"{text} {spec.unit}".strip() if spec.unit and text else text


_MONTHS = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")


def _format_date(iso: str) -> str:
    """``2026-02-10`` -> ``10 Feb 2026``. Unparseable input is printed verbatim."""
    parts = iso.strip()[:10].split("-")
    if len(parts) != 3:
        return clean_text(iso)
    try:
        year, month, day = int(parts[0]), int(parts[1]), int(parts[2])
        return f"{day:02d} {_MONTHS[month - 1]} {year}"
    except (ValueError, IndexError):
        return clean_text(iso)


#: How long a heading derived from free text may be. A section heading is a line of a printed
#: contents page and a PDF bookmark; past this it stops being a title.
_HEADING_CHARS = 80


def _heading_summary(text: str) -> str:
    """A free-text answer reduced to something that can be a HEADING.

    ``text[:80]`` is what this used to be — a raw character cut with no word boundary and no
    ellipsis — and 35 of the 394 headings in one generated report were exactly eighty characters
    ending mid-word: "15.1. SK-01 is taken forward as the first prototype. It is the only drawing
    on the she". These are section headings in a document submitted to a ministry, and they
    propagate: the same string is printed, listed in the PDF contents with a dot leader and a page
    number after it, and written into the PDF's bookmark outline.

    THE FIRST SENTENCE FIRST, because a designer's first sentence is almost always the summary —
    "SK-01 is taken forward as the first prototype." is a heading, and the paragraph that follows
    it is not. Only when there is no sentence short enough does it fall back to a cut, and then on
    a WORD boundary with an ellipsis, so a reader can see that the title is an extract rather than
    a sentence somebody left unfinished.
    """
    collapsed = " ".join(text.split())
    if not collapsed:
        return ""
    # The first sentence, if it can be one. Also '?' and '!' — a research note may open with a
    # question — but never a decimal point or an abbreviation, hence the space-or-end test.
    for i, character in enumerate(collapsed):
        if character in ".?!" and (i + 1 == len(collapsed) or collapsed[i + 1] == " "):
            sentence = collapsed[: i + 1]
            if len(sentence) <= _HEADING_CHARS:
                return sentence
            break
    if len(collapsed) <= _HEADING_CHARS:
        return collapsed
    cut = collapsed[: _HEADING_CHARS - 1]
    spaced = cut.rsplit(" ", 1)[0] if " " in cut else cut
    return f"{spaced.rstrip(' ,;:-')}…"


def _submission_line(settings: Mapping[str, Any]) -> str:
    """ "Submitted to X on 4 March 2031" — stage 20's two addressee fields, on the cover.

    Both were stored and printed nowhere. A designer filled in "Submitted to" and a submission
    date, and the generated document named neither: the one page of the report whose job is to
    say who it is for said nothing about who it is for. Rendered as one line because they are one
    fact, and each half stands alone when the other is blank.
    """
    to = clean_text(settings.get("submittedTo")).strip()
    on = clean_text(settings.get("submissionDate")).strip()
    when = _format_date(on) if on else ""
    if to and when:
        return f"Submitted to {to} on {when}"
    if to:
        return f"Submitted to {to}"
    return f"Submitted on {when}" if when else ""


def _media_ids(value: Any) -> list[str]:
    if not value:
        return []
    if isinstance(value, str):
        return [value]
    if isinstance(value, (list, tuple)):
        return [str(v) for v in value if v]
    return []


# --------------------------------------------------------------------------------------
# Geography — turning a typed address into a pin
# --------------------------------------------------------------------------------------
#
# WHERE THE MAP'S STAGE DATA LIVES, declared once. The rest of this module has no per-stage code
# and this is the one place that needs some: a map of "where the workshop was and where its people
# came from" is a statement about two specific stages, and there is no registry role that could
# say so. Keeping the keys in three named constants means a stage rename is one edit here rather
# than a figure that silently stops being drawn — which is the worst possible failure for a
# picture, because nobody notices a missing illustration until the ministry asks for it.
MAP_VENUE_STAGE = "WORKSHOP_SETUP"
MAP_ROSTER_STAGE = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"
MAP_ROSTER_ENTITY = "participant"

#: The roster fields that carry an artisan's OWN stated home, joined into one string in the order
#: an address is written — the village first, then the district that disambiguates it — so the
#: atlas's longest-run matching and the district-anchor loop both see the whole address at once.
#: The same join :meth:`ReportBuilder._venue_point` makes for the venue, for the same reason.
#:
#: PRIMARY, AND THIS COMMENT USED TO SAY THE OPPOSITE ("Read only when the ``Artisan`` record
#: behind the row supplied nothing"), which was an accurate description of a wrong precedence.
#: These are the frozen copies ``REFERENCE_HYDRATION["participant.artisanRef"]`` writes at save
#: time and the ones the participant table beside the map prints. Resolving the LIVE record first
#: is what let one document say an artisan lives in Bargarh in its table and pin them somewhere
#: else on the map above it, after a researcher corrected the record post-submission.
#:
#: ``block`` used to sit between these two and is not a participant field at all — it belongs to
#: ``workshopSetup`` — so a third of this tuple had never read anything but ``None``. Nothing
#: catches that at import: ``validate_registry`` lives in ``stage_schema``, which this module
#: imports, so it cannot see this constant without an import cycle. A test asserts it instead.
MAP_ROSTER_PLACE_KEYS: tuple[str, ...] = ("village", "district")

#: The roster field holding the artisan's stated STATE. Read separately and deliberately NOT
#: appended to the tuple above: a state is disambiguation for a place name, not a place name, and
#: a bare "Odisha" handed to the geocoder as though a designer had typed it in the village box
#: drops a pin on the capital and calls it somebody's home.
MAP_ROSTER_STATE_KEY = "state"

#: The roster field holding the pin a researcher dropped on the artisan's OWN place. Hydrated from
#: the ``Artisan``'s ``Location.subjectLatitude/subjectLongitude`` at save time, so what is read
#: here is the row's frozen copy and not a live lookup.
#:
#: THE STATED PIN, NOT THE CAPTURED ONE, and the distinction is the whole reason the column exists.
#: ``design_workshops._subject_point`` will not let ``latitude``/``longitude`` cross into a stage
#: entry, because on this database those are the desk the record was typed at — routinely ~1,500
#: km from the village named on the same row. A map drawn from those would put every artisan in
#: the office.
MAP_ROSTER_PIN_KEY = "subjectLocation"


@dataclass(frozen=True, slots=True)
class _Located:
    """One resolved position, carrying the name of the place that was actually found.

    ``label`` is the atlas's own label and NOT the text that was searched, which is the difference
    between a pin that says "Barpali" and a pin that says "Barpali" while sitting on Bhubaneswar.
    When only the state resolved, the label is the state, so the pin never claims to know more
    than it does — the overstatement ``place_atlas.Precision`` exists to prevent.

    ONE PIN IS BUILT WITHOUT AN ATLAS AND IS THE EXCEPTION TO THAT SENTENCE: the surveyed pin
    :meth:`ReportBuilder._artisan_points` makes from ``participant.subjectLocation``. A coordinate
    a researcher dropped carries no name at all, so the row's own stated place is the only honest
    label available — the alternative is a nameless pin or the artisan's personal name printed on
    a map. It is still held to the atlas's GRAMMAR (the most specific single part, never the joined
    address), so the figure does not mix two kinds of label; the reasoning is at the construction.
    """

    lat: float
    lon: float
    state: str
    label: str
    #: False when only the state resolved and the pin is sitting on the state capital, which can be
    #: several hundred kilometres from the village that was typed. Carried so the caption can say
    #: so once, in words, rather than leaving a reader to measure a pin against a district they
    #: know and conclude the map is wrong.
    precise: bool = True


def _geocode(
    text: Any, state_hint: Any = "", district_points: dict[str, tuple[float, float]] | None = None
) -> _Located | None:
    """Place one typed address, or return None when nothing in it is placeable.

    Two sources, in falling order of precision: the curated town/district atlas, then the state
    seat table. NEITHER TOUCHES THE DATABASE and neither is allowed to, because this module is
    also the on-device report builder — the phone generates the same document from its local draft
    with no network at all, and a geocoder that needed a query would mean the offline report
    quietly lost its only figure.

    The state is appended to the search text rather than searched separately so the atlas's own
    longest-run matching can use it as disambiguation: "Akola" is a Dabu-printing village in
    Rajasthan and a city in Maharashtra 900 km away, and the state is the only thing in the record
    that tells them apart.
    """
    from app.services.address import canonical_state
    from app.services.geography import state_seat
    from app.services.place_atlas import Precision, resolve_place

    local = clean_text(text).strip()
    state_text = clean_text(state_hint).strip()
    joined = ", ".join(part for part in (local, state_text) if part)
    if joined:
        found = resolve_place(joined).place
        if found is not None:
            # THE ATLAS ALSO ANSWERS "ONLY THE STATE", and that answer is not a precise one. When
            # nothing in the string is a town or a district it knows, ``resolve_place`` still
            # returns a Place — a STATE-precision one, sitting on the capital and labelled with
            # the state — which is the same standing-in that the ``state_seat`` fallback below
            # performs. Reading it as precise was a real overstatement with two visible
            # consequences: the caption's one sentence explaining why a pin may be hundreds of
            # kilometres from the village never printed, and every unresolvable village in one of
            # the nine states the atlas covers produced a confidently-drawn pin on the capital
            # while the identical village in a tenth state was honestly labelled. A map that is
            # candid about half its pins and silent about the other half is worse than one that is
            # candid about none, because a reader who checks two of them concludes the notes mean
            # something.
            precise = found.precision is not Precision.STATE
            if precise:
                return _Located(
                    found.latitude,
                    found.longitude,
                    canonical_state(found.state) or found.state,
                    found.label,
                    precise=precise,
                )
            # A STATE-precision hit is the atlas saying "I recognised only the state", so the
            # district table below gets its turn before we settle for the capital.

    # THE DISTRICT ANCHORS — the step that makes this work outside the curated towns.
    #
    # `place_atlas` is a few dozen craft towns checked by eye, and everywhere it does not name, this
    # function used to go straight to the state capital: twenty artisans of a Bargarh cluster folded
    # onto Bhubaneswar and the map asserted they came from the capital. `address` knows all 795
    # districts of India BY NAME, and `geography.DistrictAnchors` positions them from the real pins
    # this repository already holds — so any record whose text names its district resolves, in any
    # state, with no curation at all.
    #
    # Precise, and honestly so: a district anchor is a real position for a real administrative unit,
    # not a capital city standing in for one. The town atlas still wins where it has an entry,
    # because a town is finer than a district.
    if district_points:
        from app.services.address import canonical_district

        state_key = canonical_state(state_text) or state_text
        if state_key:
            # Try the longest trailing runs of the text first, so "Barpali, Bargarh" finds Bargarh
            # rather than failing on the whole string.
            parts = [p.strip() for p in local.replace(";", ",").split(",") if p.strip()]
            for candidate in [local, *reversed(parts)]:
                district = canonical_district(state_key, candidate)
                if not district:
                    continue
                point = district_points.get(f"{state_key}|{district}")
                if point:
                    return _Located(point[0], point[1], state_key, district, precise=True)

    if joined:
        found = resolve_place(joined).place
        if found is not None:
            return _Located(
                found.latitude,
                found.longitude,
                canonical_state(found.state) or found.state,
                found.label,
                precise=False,
            )

    # Nothing in the text was a place this build knows, so fall back to the state — which is a
    # coordinate hundreds of kilometres from the village in the worst case, and is therefore
    # labelled with the STATE's name rather than the village's.
    canonical = canonical_state(state_text) or canonical_state(local)
    if canonical:
        seat = state_seat(canonical)
        if seat is not None:
            return _Located(seat[1], seat[2], canonical, canonical, precise=False)
    return None


def _geo_point(value: Any) -> tuple[float, float] | None:
    """A stored GEO value as ``(lat, lon)``, or None for anything that is not a real fix.

    ``0, 0`` is rejected along with the unparseable. It is the Gulf of Guinea, it is what a form
    that never obtained a fix writes, and ``report_map`` would drop it off the edge of India
    anyway — but rejecting it here means the venue falls back to its typed address and gets a pin
    in the right state, instead of the report losing the venue entirely.
    """
    if not isinstance(value, dict):
        return None
    try:
        lat, lon = float(value.get("lat")), float(value.get("lon"))  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return None
    if abs(lat) < 0.0001 and abs(lon) < 0.0001:
        return None
    return (lat, lon)


@dataclass
class _MapFacts:
    """One contribution to the map: its pins, the states they fell in, and what did not resolve.

    The counts travel with the pins because the CAPTION is half the figure. A map showing four
    pins for a workshop of thirty artisans is not wrong, but a reader who cannot see that
    twenty-six addresses failed to resolve will read it as a workshop of four — so the caption
    says so, and it can only say so if the arithmetic survives the walk that produced the pins.
    """

    points: list[MapPoint] = field(default_factory=list)
    states: set[str] = field(default_factory=set)
    placed: int = 0  # rows that produced a position
    total: int = 0  # rows considered
    approximate: int = 0  # positions that are a state capital standing in for an unknown place

    def merge(self, other: _MapFacts) -> _MapFacts:
        return _MapFacts(
            points=self.points + other.points,
            states=self.states | other.states,
            placed=self.placed + other.placed,
            total=self.total + other.total,
            approximate=self.approximate + other.approximate,
        )


def _fold_points(found: list[tuple[str, _Located]], kind: MapPointKind) -> list[MapPoint]:
    """Collapse pins that share a coordinate into one point carrying a count.

    Six weavers from Barpali resolve to one coordinate, and six pins drawn on that coordinate are
    one pin as far as any reader can tell — so a map of thirty participants from five districts
    looked like a map of five participants. Folding them is both honest and legible, which is the
    entire reason :attr:`MapPoint.count` exists.

    Rounded to five decimals — about a metre — because two lookups of the same place return bytewise
    identical floats and two different places never land within a metre of each other.
    """
    folded: dict[tuple[float, float], tuple[str, int]] = {}
    for label, place in found:
        key = (round(place.lat, 5), round(place.lon, 5))
        existing = folded.get(key)
        folded[key] = (existing[0] if existing else label, (existing[1] if existing else 0) + 1)
    return [
        MapPoint(label=label, lat=lat, lon=lon, kind=kind, count=count)
        for (lat, lon), (label, count) in folded.items()
    ]


# --------------------------------------------------------------------------------------
# Infographics — the arithmetic behind a figure, and the rules about when there isn't one
# --------------------------------------------------------------------------------------
#
# ONE RULE GOVERNS EVERY FUNCTION BELOW: a figure is drawn from what was recorded or it is not
# drawn. There is no default series, no placeholder and no zero-filled category, because a chart
# is read as a finding. A pie showing "0 selected, 0 rejected, 0 pending" is not an empty figure,
# it is a claim that nothing was selected — and the reader has no way to tell that apart from a
# stage nobody filled in. The report says nothing instead, and the completeness annexure already
# says why.

#: Below this many categories a figure is a number drawn large. One bar labelled "Sketches: 12"
#: carries exactly what the metric row above it already carries, at the cost of a third of a page,
#: and a single-slice donut is a filled circle. Two is the point at which a picture starts to say
#: something prose does not.
MIN_CHART_CATEGORIES = 2

#: Band widths for the price histogram, in rupees, smallest first. A ladder rather than
#: ``max/6`` so the boundaries are numbers a person would choose: a cost sheet binned at
#: "₹ 0–1,383" is arithmetically correct and unreadable.
_PRICE_STEPS: tuple[float, ...] = (
    100.0,
    250.0,
    500.0,
    1000.0,
    2500.0,
    5000.0,
    10000.0,
    25000.0,
    50000.0,
    100000.0,
)

#: How many bands a price histogram may have. Six is what fits across a text column with the
#: labels still legible at the body size.
_MAX_PRICE_BANDS = 6

#: EVERY FIGURE THE REPORT CAN CONTAIN: id -> (the stage whose data it is about, the
#: :class:`ReportBuilder` method that builds it). The id is a public token — a template names it
#: in a :class:`~app.services.report_templates.TemplateSection`, so it crosses a module boundary
#: and must stay stable. ``tests/test_report_figures.py`` fails the build if a template names one
#: that is not here, which is the only way a typo in a template could otherwise announce itself:
#: as a figure that silently never printed, in a document nobody proof-reads against the data.
#:
#: A stage absent from this table has no figure, which is most of them. A chart is worth a third
#: of a page and has to earn it.
FIGURES: dict[str, tuple[str, str]] = {
    "OUTPUT_COUNTS": ("WORKSHOP_OUTCOMES", "_chart_output_counts"),
    "PROTOTYPE_STATUS": ("WORKSHOP_OUTCOMES", "_chart_prototype_status"),
    # THE SURVEY IS THE STAGE THAT COLLECTS A DISTRIBUTION, and until these two entries existed it
    # owned no figure — it reached the reader as a table of rows and nothing else, while stage 9's
    # price bands, SWOT and design direction all cite it as their evidence.
    #
    # PLACED BY OWNERSHIP, NOT BY A TEMPLATE EDIT. ``_charts_for`` matches a stage section against
    # this table's second column and ``TemplateSection.include_figures`` defaults to True, so naming
    # the stage here is the whole of the placement. Counted 2026-08-28 over ``report_templates
    # .TEMPLATES``: of the six templates, four carry a ``MARKET_SURVEY_CAPTURE`` section and all four
    # leave ``include_figures`` at its default (DCH_STANDARD, DIC_STANDARD, IMPLEMENTING_AGENCY —
    # where it is Annexure C — and DETAILED_TECHNICAL), so all four gain both figures with no edit to
    # ``report_templates`` and no move of the by-value ``report_templates_pin.json`` fixture. The two
    # that do not print the stage — COMPACT_SUMMARY and the buyer-facing PHOTO_CATALOGUE — gain
    # nothing, which is right for the catalogue for the reason its cost section already gives.
    "SURVEY_RESPONDENTS": ("MARKET_SURVEY_CAPTURE", "_chart_survey_respondents"),
    "SURVEY_PRICE_EXPECTATIONS": ("MARKET_SURVEY_CAPTURE", "_chart_survey_price_expectations"),
    "COST_BY_HEAD": ("COSTING_MARKET_LINKAGE", "_chart_cost_by_head"),
    "PRICE_BANDS": ("COSTING_MARKET_LINKAGE", "_chart_price_bands"),
    "ADOPTION": ("POST_WORKSHOP_FOLLOWUP", "_chart_adoption"),
}


def _as_number(value: Any) -> float | None:
    """A stored value as a finite float, or None for anything that is not one.

    ``bool`` is rejected before the ``float`` call: it is an ``int`` subclass, so a BOOL field
    would otherwise contribute 1.0 to a cost total. NaN and the infinities are rejected because
    they survive ``float()`` happily and then poison every sum they touch — one unparseable cell
    turning an entire cost breakdown into a chart of NaN bars.
    """
    if value is None or isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if math.isnan(number) or math.isinf(number):
        return None
    return number


def _price_bands(values: list[float]) -> list[tuple[str, float]]:
    """Bin prices into readable bands. Returns ``[]`` when there is no distribution to show.

    Returns nothing rather than one full band when every product carries the same price, which is
    a real state — a cluster that agreed one price for the whole range — and which draws as a
    single bar carrying no information the price column does not already carry.
    """
    positive = sorted(value for value in values if value > 0)
    if len(positive) < 3:
        return []
    top = positive[-1]
    if top <= positive[0]:
        return []
    # The narrowest ladder step that keeps the histogram within its band budget. The final
    # fallback is the widest step, which cannot itself divide by zero because every entry in
    # ``_PRICE_STEPS`` is positive — the guard that matters, since ``top`` is caller data.
    step = next((s for s in _PRICE_STEPS if top / s <= _MAX_PRICE_BANDS), _PRICE_STEPS[-1])
    counts: dict[int, int] = {}
    for value in positive:
        counts[int(value // step)] = counts.get(int(value // step), 0) + 1
    return [
        (
            f"{_group_indian(f'{index * step:.0f}')}–{_group_indian(f'{(index + 1) * step - 1:.0f}')}",
            float(count),
        )
        for index, count in sorted(counts.items())
    ]


#: How many stage-1 rows the cover table holds. A cover table longer than this stops being a
#: cover — but stage 1 declares twenty-one COVER_FIELD boxes, so the cap bites on any properly
#: documented workshop. Named rather than inlined because two things now depend on the same
#: number: the block that is drawn, and the warning that says what fell off it. See the block at
#: the end of :meth:`ReportBuilder._render_cover` for which templates that costs and why the
#: answer is a sentence rather than a second table.
COVER_INFO_ROWS = 10


# --------------------------------------------------------------------------------------
# The builder
# --------------------------------------------------------------------------------------


class ReportBuilder:
    def __init__(
        self,
        data: WorkshopData,
        template: ReportTemplate,
        resolve_media: MediaResolver,
        *,
        meta: ReportMeta,
        theme: ReportTheme | None = None,
    ) -> None:
        self.data = data
        self.template = template
        self.resolve_media = resolve_media
        # The caller's theme where it gave one, the template's otherwise. Resolved here rather
        # than at every use so no section can be drawn in the template's colour while the rest of
        # the document is drawn in the designer's.
        self.doc = DocumentBuilder(meta=meta, theme=theme or template.theme)
        self._stages = {s.key: s for s in stages()}
        # Figure ids already printed. See :meth:`_figure` for why a figure is drawn at most once
        # per document even when two sections of the template both ask for it.
        self._drawn: set[str] = set()
        # entry id -> (the entity it belongs to, its row), for resolving a reference that points
        # at another record of THIS workshop. Built once: a cost sheet table resolves its product
        # ref on every row, and re-walking the collections each time is quadratic in a stage that
        # can carry two hundred lines.
        self._entities: dict[str, EntitySpec] = {e.key: e for s in stages() for e in s.entities}
        # entity key -> the stage that declares it, so a CHILD collection can reach its parent's
        # rows. Its parent is not always in its own stage — stage 14's iterations hang off stage
        # 13's prototypes — and entity keys are globally unique, which ``stage_schema.validate``
        # enforces rather than leaves to convention.
        self._entity_stage: dict[str, str] = {e.key: s.key for s in stages() for e in s.entities}
        self._rows_by_id: dict[str, tuple[EntitySpec | None, dict[str, Any]]] = {}
        for entities in data.collections.values():
            for entity_key, rows in entities.items():
                entity = self._entities.get(entity_key)
                for row in rows:
                    entry_id = row.get("_entryId")
                    if isinstance(entry_id, str) and entry_id:
                        self._rows_by_id[entry_id] = (entity, row)
        self._label_cache: dict[str, str] = {}
        #: The stage-1 cover fields this document filled in and then did NOT print anywhere —
        #: written by :meth:`_render_cover`, read by :func:`build_report` for a warning. See
        #: :data:`COVER_INFO_ROWS` for the loss, and ``ref_resolves`` for why the answer is taken
        #: off the builder AFTER the render rather than recomputed beside it: a second copy of
        #: "what did the cover print" is exactly how two statements about one document come apart.
        self.cover_fields_dropped: tuple[str, ...] = ()
        #: Photographs this template's ``max_photos`` cap kept out, by STAGE KEY — written by
        #: :meth:`_note_photographs_over_cap`, read by :func:`build_report` for a warning. Off the
        #: builder after the render for the reason ``cover_fields_dropped`` above is: a second copy
        #: of "what did the cap drop" is how two statements about one document come apart.
        self._photographs_over_cap: dict[str, int] = {}
        #: The stage whose section is being rendered. The ONE piece of "where am I" state on this
        #: builder, and it exists so a capped photograph can be reported against a stage the
        #: designer can go and open — see :meth:`_note_photographs_over_cap`.
        self._stage_key: str = ""

    # -- tier and emptiness -----------------------------------------------------------

    def _visible(self, spec: FieldSpec) -> bool:
        """Whether the template's tier admits this field at all."""
        return not spec.deprecated and spec.tier.rank <= self.template.max_tier.rank

    def _is_filled(self, spec: FieldSpec, row: dict[str, Any]) -> bool:
        """Whether this field of this row HAS something a report could have printed.

        Asked of a field the tier cap has already excluded, so it cannot go through ``_printable``
        — that skips an invisible field before it ever looks at the value. It asks the two
        questions the printing paths ask, and no third one. THE OLD SPLIT WAS "MEDIA VS
        EVERYTHING ELSE" AND THAT IS NO LONGER THE SHAPE OF THE REPORT: ``_images`` filters on
        IMAGE and IMAGE_LIST, so those two are the only types placed as pictures, while FILE,
        AUDIO and VIDEO are printed from ``_value`` like any text field — ``format_value``'s media
        branch turns their ids into "2 documents attached". The two questions still give the same
        answer for those three by construction, because that branch counts the very ``_media_ids``
        this one reads, which is why they stay on the id side of the test rather than paying for a
        second format.

        ``_value`` is the other question, and it is what turns a dead reference into a blank
        rather than a cuid. A field that would have rendered as nothing anyway is not a loss and
        must not be counted as one — a "fields were left out" warning that fires on empty boxes is
        a warning designers learn to ignore.
        """
        if spec.type.is_media:
            return bool(_media_ids(row.get(spec.key)))
        return bool(self._value(spec, row))

    def fields_hidden_by_tier(self) -> list[tuple[StageSpec, list[str]]]:
        """The filled fields this template's ``max_tier`` kept out of the document, by stage.

        **THE OTHER HALF OF A WARNING THAT ONLY EVER COVERED THE DESIGNER'S OWN QUESTIONS.**
        ``build_report`` already tells a designer when their own custom section asks only questions
        above the template's capture tier, and the argument written there applies verbatim to the
        two and a half thousand fields of the REGISTRY: COMPACT_SUMMARY is the one template in
        ``TEMPLATES`` whose ``max_tier`` is not ADVANCED, it describes itself as "Basic-tier fields
        only", and every Standard and Advanced answer a designer typed is dropped from it by
        ``_visible`` without one word anywhere saying so. A designer who fills in an artisan's
        village, phone and specialisation — all Standard, all of them copied in from the artisan
        record by the picker — generates a Compact summary and gets a file with none of them, and
        is told nothing. That is precisely the failure ``apply_report_settings``' docstring names:
        an absent feature is obvious and a silent one is a bug the designer blames themselves for.

        A FACT ABOUT THIS WORKSHOP AND NOT ABOUT THE REGISTRY. Only fields that were actually
        FILLED IN count, which is why this is a method on the builder rather than a comparison of
        two tiers: "COMPACT_SUMMARY omits 1,900 Standard fields" is true of every report ever
        generated from it and tells a designer nothing about their own. "17 fields you recorded are
        not in this file" is a thing they can act on, and the action — generate it as
        DCH_STANDARD — is named in the warning.

        ``report_role=HIDDEN`` fields are skipped: they print at no tier, so the cap took nothing
        away and naming them would blame the template for a decision the registry made. Nothing
        else is filtered — a CAPTION or a METRIC left out by the cap is as absent as a key-value
        pair, and the designer typed it either way.

        Returns ``[(stage, [field labels])]`` in the registry's own stage order, and only for the
        stages the template actually prints. A field left out because the template does not carry
        its stage AT ALL is a different and visible thing: the template's section list is a
        decision the designer made when they chose it and can read in its description, while the
        tier cap is invisible from the picker.
        """
        out: list[tuple[StageSpec, list[str]]] = []
        for spec in stages():
            if self.template.section_for(spec.key) is None:
                continue
            lost: list[str] = []
            for entity in spec.entities:
                rows = (
                    [self.data.singleton(spec.key)]
                    if entity.cardinality is Cardinality.SINGLETON
                    else self.data.rows(spec.key, entity.key)
                )
                for field_spec in entity.fields:
                    if self._visible(field_spec) or field_spec.deprecated:
                        continue
                    if field_spec.report_role is ReportRole.HIDDEN:
                        continue
                    if any(self._is_filled(field_spec, row) for row in rows):
                        lost.append(field_spec.label)
            if lost:
                out.append((spec, lost))
        return out

    def photographs_over_cap(self) -> list[tuple[StageSpec, int]]:
        """The photographs this template's ``max_photos`` kept out of the document, by stage.

        AFTER THE RENDER AND OFF THE BUILDER, for the reason :meth:`fields_hidden_by_tier` and
        ``cover_fields_dropped`` are: the placement paths are what actually dropped these pictures,
        and a count recomputed beside the warning would be a second copy of the cap arithmetic. Two
        statements about one document that are free to disagree is the defect the two
        custom-section warnings already taught this module.

        Empty for five of the six templates — ``max_photos`` is 0 everywhere in ``TEMPLATES``
        except COMPACT_SUMMARY's final-products section, and ``apply_report_settings`` cannot set
        it — so no existing report gains a warning unless a cap really did bite.
        """
        return [
            (spec, self._photographs_over_cap[spec.key])
            for spec in stages()
            if self._photographs_over_cap.get(spec.key)
        ]

    def attachments_named_but_not_carried(self) -> list[tuple[StageSpec, int]]:
        """The files this report NAMES and does not CONTAIN, counted by stage.

        WHAT AN OFFICER READS, AND WHAT IS TRUE. ``format_value``'s media branch prints "1 document
        attached" against the field's own label, and that line ended a real defect: a designer
        attached the ministry's sanction order at stage 1 and the .docx the officer received did not
        mention that a sanction order existed. What it did not end is the reading that line invites
        in a document submitted to a ministry, which is *a document is attached to this report*. It
        is not. Neither writer can draw a PDF, a fifteen-minute recording or a process video;
        ``_images`` — the only placement path there is — filters on IMAGE and IMAGE_LIST; and
        ``ANNEXURE_MEDIA`` gathers through ``_images``, so the contact sheet cannot carry one
        either. The bytes stay in the workshop record, and until this method existed no surface
        said so.

        THE REGISTRY IS NOT WHERE THAT IS DECIDED, WHICH IS THE HALF THIS DOCSTRING KEEPS GETTING
        WRONG. It used to end by saying the registry's help text on ``designerCv`` promised the
        opposite in as many words. It did, it no longer does — that help text now says the report
        NAMES the file rather than carrying it — and pointing at it was the mistake even while the
        claim was accurate, because it aims the next reader at an instance instead of at the rule.
        The rule is the SHAPE: a claim about what a report CONTAINS cannot be verified from any
        client, and both the web form and the handset render their help straight off the published
        registry, so the same wrong sentence can be written in ``stage_definitions`` any number of
        times without a single surface disagreeing with it. The authority is here, in
        ``report_annexures`` (transcripts only) and in ``report_templates`` (which records the
        refusal of a FILE annexure as a decision, with its two reasons). Correct the help text
        against those three; do not maintain a list of the help texts that have gone wrong. The
        count is the argument. ``report_annexures`` and ``report_custom_sections`` both open by
        recording "three surfaces told the designer the office's copy would carry it";
        ``stage_definitions`` records at ``surveyDocument`` that the identical false claim was made
        three times in one wave; and correcting the two sentences in THIS module is the fourth pass
        over the same claim in a day. Every one of those is a client-facing string, and no client
        can check any of them. That is what recording the instance rather than the shape costs.

        SO IT IS SAID BESIDE THE DOWNLOAD AND NOT IN THE DOCUMENT. The file is honest about what it
        holds — it says an attachment exists and how many — and the person who has to act is
        the designer, on the day, whose action is to send those files with it. Writing the note into
        the .docx would break the rule every warning here is under.

        THIS IS NOT AN ARGUMENT THAT THE BYTES SHOULD BE EMBEDDED. That is a change to five
        renderers, two of which run on a handset with no network, and a new ``SpecialSection`` for
        them is a decision ``report_templates`` has already recorded against itself with its
        reasons. Neither is a builder change. Naming the loss is.

        COUNTED IN FILES AND NOT IN FIELDS, because a field holding three recordings is three
        things for somebody to go and find. Only fields the tier ADMITS and the registry does not
        HIDE are counted: one above the cap is already named by :meth:`fields_hidden_by_tier`, and a
        HIDDEN one is nowhere in the document for this sentence to be about. The two warnings are
        disjoint by construction and neither double-counts the other.

        AUDIO IS COUNTED EVEN WHEN ITS TRANSCRIPT IS PRINTED, and the sentence this feeds says "the
        files themselves" for exactly that reason. A transcribed recording reaches the back of the
        report as WORDS through ``append_transcript_annexure``; the recording is still not in the
        file, and somebody comparing the annexure against the tape still has to be sent the tape.
        Excluding it would have made this count depend on a stage-20 toggle and on whether the
        media queue had finished, which is two more ways for one document to make two claims.
        """
        out: list[tuple[StageSpec, int]] = []
        for spec in stages():
            if self.template.section_for(spec.key) is None:
                continue
            named = 0
            for entity in spec.entities:
                rows = (
                    [self.data.singleton(spec.key)]
                    if entity.cardinality is Cardinality.SINGLETON
                    else self.data.rows(spec.key, entity.key)
                )
                for field_spec in entity.fields:
                    # IMAGE and IMAGE_LIST are excluded because they ARE carried: they are the two
                    # types ``_images`` places. DERIVED from that filter rather than written out as
                    # (FILE, AUDIO, VIDEO) so that a sixth media type added to ``FieldType``
                    # tomorrow joins this count by existing — the same rule, for the same set,
                    # that ``tests/test_report_attachments.py`` states about its own census.
                    if not field_spec.type.is_media:
                        continue
                    if field_spec.type in (FieldType.IMAGE, FieldType.IMAGE_LIST):
                        continue
                    if not self._visible(field_spec):
                        continue
                    if field_spec.report_role is ReportRole.HIDDEN:
                        continue
                    named += sum(len(_media_ids(row.get(field_spec.key))) for row in rows)
            if named:
                out.append((spec, named))
        return out

    # -- references -------------------------------------------------------------------

    def _ref_label(self, ref_id: Any, _seen: frozenset[str] = frozenset()) -> str:
        """What a REF value should PRINT: the name of the thing it points at, never its id.

        THIS IS WHY A COST SHEET USED TO BE HEADED `cmsik2jg8000eh8xc1lcy661a`. Eleven REF fields
        in the registry are table columns or key-value pairs, and five entities name a REF as
        their ``label_field`` — which is the right design (a cost sheet IS labelled by its
        product, a review by its sketch) — but ``format_value`` has no REF branch, so every one of
        them fell through to ``clean_text`` and printed the raw cuid into a document submitted to
        a ministry.

        Two kinds of reference resolve here, and they resolve differently:

        * A ``Dw…`` model points at another ROW OF THIS WORKSHOP, which is already loaded. It is
          found by ``_entryId`` and labelled by its own entity's ``label_field``.
        * Anything else is an external record — an artisan, a documented product — whose label the
          caller loaded into ``WorkshopData.references``.

        RESOLUTION IS RECURSIVE, because the labels chain: a cost sheet's label is its
        ``productRef``, and a final product's label is its ``name``. ``_seen`` breaks a cycle
        rather than trusting the data not to contain one — two rows referring to each other is a
        stack overflow in the middle of generating a report, and the data comes from a phone.
        """
        if not isinstance(ref_id, str) or not ref_id:
            return ""
        if ref_id in self._label_cache:
            return self._label_cache[ref_id]
        if ref_id in _seen:
            return ""

        label = ""
        found = self._rows_by_id.get(ref_id)
        if found is not None:
            entity, row = found
            if entity is not None:
                label = self._row_label_text(entity, row, _seen | {ref_id})
        if not label:
            record = self.data.reference(ref_id)
            if record is not None:
                label = record.label

        # Cached even when empty: an unresolvable id is looked up once per document rather than
        # once per row, and an empty answer is a real answer — the row it named was deleted.
        self._label_cache[ref_id] = label
        return label

    def _row_label_text(
        self, entity: EntitySpec, row: dict[str, Any], _seen: frozenset[str] = frozenset()
    ) -> str:
        """The label field's printed value, following a REF label through to a name."""
        if entity.label_field:
            spec = entity.field(entity.label_field)
            if spec is not None:
                if spec.type is FieldType.REF:
                    text = self._ref_label(row.get(spec.key), _seen)
                else:
                    text = format_value(spec, row.get(spec.key))
                    # An entity whose `label_field` holds an opaque id — stage 21's
                    # `mediaQualityFlag` labels its rows by `mediaId` — must not head its cards
                    # with a cuid. Falling through to the free-text scan below finds the flag's
                    # own note instead, which is what a reader can actually use.
                    if spec.type is FieldType.TEXT and _looks_like_an_id(text):
                        text = ""
                if text:
                    return text
        for spec in entity.fields:
            if spec.is_free_text and self._visible(spec):
                text = format_value(spec, row.get(spec.key))
                if text:
                    return _heading_summary(text)
        return ""

    def _value(self, spec: FieldSpec, row: dict[str, Any]) -> str:
        """One stored value as the report prints it, with a reference shown as its name."""
        if spec.type is FieldType.REF:
            raw = row.get(spec.key)
            label = self._ref_label(raw)
            if label:
                return label
            # Nothing resolved. What is printed now depends on WHAT the field is holding, and both
            # answers matter:
            #
            #   an opaque id  -> nothing. The row it named was deleted after this one cited it, and
            #                    a bare cuid in a ministry's table is worse than a visible gap. The
            #                    gap is what `_printable` turns into "Not recorded." when the field
            #                    is required, which is the honest report of what happened.
            #   anything else -> itself. A REF may hold text a designer typed by hand — a sketch
            #                    number like "SK-01", a value migrated before the picker existed —
            #                    and blanking that would silently drop a field somebody filled in.
            #                    `test_no_presentation_silently_drops_a_filled_field` is the guard.
            text = format_value(spec, raw)
            return "" if _looks_like_an_id(text) else text

        # ── THE SAME TWO RULES, OVER A LIST OF THEM: A RECORD-BACKED MULTI_ENUM ──────────────
        #
        # ``processStep.toolsUsed`` and ``prototype.toolsUsed`` were TAGS until 2026-09-03 and are
        # now a multi-select over documented tools, so ONE stored array can hold both shapes at
        # once: ids the new picker wrote, and the tool names a designer typed into the old box —
        # including on a 0.0.7 handset that is still typing them today. Printing either one wrong
        # is a defect with a reader:
        #
        #   an id that resolves  -> the record's label, which is the whole point of the promotion.
        #   an id that does not  -> nothing. The tool record was deleted after this step cited it,
        #                           and a bare cuid in a ministry's table is worse than a gap —
        #                           the REF branch above argues this at length and this is that
        #                           argument, unchanged.
        #   anything else        -> itself, verbatim. "pit loom" is a designer's own word for
        #                           their own fieldwork and blanking it would be the silent drop
        #                           ``test_no_presentation_silently_drops_a_filled_field`` forbids.
        #
        # ORDER IS THE STORED ORDER. A list of tools is not sorted anywhere else and re-ordering
        # it here would make the table disagree with the form the designer filled in.
        if spec.type is FieldType.MULTI_ENUM and spec.ref_model:
            printed: list[str] = []
            for token in row.get(spec.key) or []:
                text = str(token).strip()
                if not text:
                    continue
                label = self._ref_label(text)
                if label:
                    printed.append(label)
                elif not _looks_like_an_id(text):
                    printed.append(text)
            return ", ".join(printed)

        text = format_value(spec, row.get(spec.key))
        # THE SAME RULE, FOR A FIELD THAT IS NOT A REF BUT HOLDS AN ID ANYWAY.
        #
        # The guard above was written for REF and stopped there, so it missed the case that reaches
        # a reader: stage 21's `mediaQualityFlag.mediaId` is declared TEXT — there is no `Media`
        # ref_model to point at — and it is that entity's `label_field`. A designer flags a blurred
        # photograph and the submitted document grows a "Media quality flags" table whose File
        # column, and whose card headings, read `cmsjb6qaq01ar4otfh1p0hm1a`. The argument written
        # four lines up applies verbatim and does not depend on the field's declared type: a bare
        # cuid in a ministry's table is worse than a visible gap, because the gap is legible as
        # missing while the cuid looks like an answer.
        #
        # Only for a value that is ENTIRELY an opaque id. Free text that merely contains one keeps
        # every character — a note reading "duplicate of cmsjb6q…" is a designer's own words about
        # their own fieldwork, and silently truncating it would be the drop
        # `test_no_presentation_silently_drops_a_filled_field` exists to forbid.
        return "" if spec.type is FieldType.TEXT and _looks_like_an_id(text) else text

    def _printable(
        self, entity: EntitySpec, row: dict[str, Any], roles: set[ReportRole]
    ) -> list[tuple[FieldSpec, str]]:
        out: list[tuple[FieldSpec, str]] = []
        for spec in entity.fields:
            if not self._visible(spec) or spec.report_role not in roles:
                continue
            if spec.caption_for:
                continue  # captions are placed with their image, never on their own
            text = self._value(spec, row)
            if text:
                out.append((spec, text))
            elif spec.required and self.template.show_empty_note:
                # A missing REQUIRED field is information: it says the record is incomplete.
                # A missing optional one is not, and printing "Not recorded." for every
                # unfilled Advanced field would bury the report in negatives.
                out.append((spec, "Not recorded."))
        return out

    # -- media ------------------------------------------------------------------------

    def _image_sources(
        self,
        entity: EntitySpec,
        row: dict[str, Any],
    ) -> dict[str, tuple[FieldSpec, str]]:
        """Every image id this row can show, mapped to THE FIELD THAT CLAIMED IT and its caption.

        TWO SOURCES, in this order: the row's own media fields, then the photograph of any record
        a REF field on the row points at. The second one is the whole of "a photograph appears
        beside the thing it is a photograph of" for the half of the registry where the picture was
        never copied onto the row.

        The designer's own photographs come FIRST and the reference's is an addition, never a
        substitute. A prototype whose maker shot four progress photographs must not lead with a
        catalogue picture of the product it was based on — the report is about the workshop, and
        the borrowed image is context.

        WHY THE FIELD IS CARRIED OUT OF HERE, which is the whole of what changed when this was
        split out of :meth:`_images`. ONE dict per ENTITY is what deduplicates a participant's
        photograph against the artisan record it was copied from, and it has to stay entity-wide or
        that picture prints twice. But a dict that then FORGOT which field each id came from is what
        merged stage 4's three declared galleries — the cluster's photographs, the traditional
        motifs and the contemporary ones — into one undifferentiated grid. Keeping the field is
        what lets :meth:`_image_groups` name each gallery while the dedupe stays where it was.
        """
        # (media id -> (the field that claimed it, its caption)), first claim wins,
        # insertion-ordered. Deduplicating by ID rather than by field is what keeps a participant's
        # photograph from printing twice: hydration already copied the artisan's picture onto
        # ``participant.photo`` at save time, so the ``artisanRef`` beside it resolves to the very
        # same media row.
        wanted: dict[str, tuple[FieldSpec, str]] = {}

        # PASS ONE, the row's own media fields. Separate passes rather than one walk of the field
        # list, because the registry's field ORDER decides nothing here and would otherwise decide
        # everything: ``prototype.productRef`` is declared five fields above ``prototypePhotos``,
        # so a single pass led a prototype's sub-section with a catalogue photograph of somebody
        # else's product and buried the four progress shots the artisan actually took.
        for spec in entity.fields:
            if not self._visible(spec) or spec.type not in (FieldType.IMAGE, FieldType.IMAGE_LIST):
                continue
            caption_spec = next((f for f in entity.fields if f.caption_for == spec.key), None)
            caption = format_value(caption_spec, row.get(caption_spec.key)) if caption_spec else ""
            for media_id in _media_ids(row.get(spec.key)):
                wanted.setdefault(media_id, (spec, caption))

        # PASS TWO, the photograph of whatever each REF points at.
        for spec in entity.fields:
            if not self._visible(spec) or spec.type is not FieldType.REF:
                continue
            reference = self.data.reference(row.get(spec.key))
            if reference is None or not reference.photo:
                continue
            wanted.setdefault(
                reference.photo, (spec, self._reference_caption(entity, spec, row, reference))
            )
        return wanted

    def _images(
        self, entity: EntitySpec, row: dict[str, Any], *, limit: int = 0
    ) -> list[tuple[ImageRef, str]]:
        """Every resolvable image on a row, paired with its caption, in the registry's own order.

        THE FLAT VIEW, and it is kept for the three callers that want a row's PICTURES rather than
        its galleries: the cover's hero photograph (one image, whichever is declared first), the
        photographic annexure's contact sheet, and the GALLERY presentation, which pools a whole
        COLLECTION onto one plate and so has no single field to name a grid after. Everything that
        draws a gallery beside the record it belongs to goes through :meth:`_image_groups`.

        ``limit`` STILL BREAKS OUT OF THE RESOLVE LOOP instead of slicing afterwards, and that is
        load-bearing rather than a micro-optimisation. ``design_workshops.media_resolver`` records
        every id it was asked for and could not answer, and the route turns that record into a
        "photograph(s) could not be read" warning beside the download — so resolving ids the
        document was never going to print would grow that warning on reports that suffered no such
        loss. PHOTO_CATALOGUE is where it would show: its cover asks for one hero image and its
        sections print no stage 1 at all, so the rest of stage 1's pictures are never asked for.

        A CAPTION FALLS BACK TO ITS FIELD'S LABEL. Every ``*Caption`` box in the registry is
        optional and a blank one used to print a photograph with nothing underneath it — a
        designer's passport portrait, copied onto stage 3 by ``designers.PREFILL_MAP`` without
        anybody choosing it, arrived in the middle of the workshop plan at 62% of the page width
        with no word anywhere saying what it was. The field's own label is the honest minimum and
        the registry has already written it.

        HERE IT FALLS BACK ON EVERY PICTURE, which is where this view and :meth:`_image_groups`
        differ and why. These captions are the only place a name can go: a contact sheet and a
        pooled catalogue draw one grid over pictures from many fields and many records, so the grid
        cannot be named after any of them. A named gallery CAN be, and is, so it does not repeat
        that name under each of its own photographs.
        """
        found: list[tuple[ImageRef, str]] = []
        for media_id, (spec, caption) in self._image_sources(entity, row).items():
            ref = self.resolve_media(media_id)
            if ref is None:
                continue
            found.append((ref, caption or spec.label))
            if limit and len(found) >= limit:
                break
        return found

    def _image_groups(
        self, entity: EntitySpec, row: dict[str, Any], *, cap: int = 0
    ) -> list[tuple[str, list[tuple[ImageRef, str]]]]:
        """A row's pictures as the PLATES they should be drawn as: ``[(grid caption, images)]``.

        WHAT THIS ENDS. ``clusterBackground`` declares three galleries — the cluster's
        photographs, the traditional motifs and the contemporary ones — and one merged grid drew
        them as six interchangeable pictures. Three costs, all of them in the delivered file: uneven
        galleries straddled grid rows, so row two read "third cluster photograph | first traditional
        motif"; there was no heading and no rule anywhere between one gallery and the next; and
        because all three ``*Caption`` boxes are optional, a reader with them left blank had NOTHING
        on the page to tell a motif that has been woven for two centuries from one drawn last week.
        In a document a ministry reads as the evidence for a design intervention, that distinction
        is most of what stage 4 is for.

        ``ImageGridBlock.caption`` was already the answer and was already drawn by ALL FIVE
        renderers — the server .docx writer, the server .pdf writer, the web preview and both
        on-device Kotlin writers. The builder simply never filled it, so this reaches every surface
        at once and needs no new block type, which is what a new plate would have cost: five
        implementations that must agree line-for-line, plus the 485 KB Kotlin template pin.

        A FIELD HOLDING SEVERAL PHOTOGRAPHS IS A GALLERY AND GETS ITS OWN NAMED GRID. A field
        holding ONE holds one picture, and the fields that hold one picture SHARE a plate. That
        second half is not tidiness, it is what keeps this change inside the defect it was reported
        for: ``prototypeIteration`` declares ``beforePhoto`` and ``afterPhoto``, and a before and an
        after belong side by side — splitting them into two half-page pictures stacked one above
        the other is a different document, and no defect asked for it. So is ``existingProduct``'s
        front, back and detail view, which is a plate of three views and was never three galleries.
        Each of those pictures now carries its own field's label wherever its caption box is blank,
        so the plate says which view is which, and not one thing about its layout moves.

        THE BORROWED PHOTOGRAPH OF WHATEVER A REF POINTS AT IS ALWAYS ONE PICTURE, so it lands on
        that shared plate captioned with the referenced record's frozen name, exactly as it was
        before — see :meth:`_reference_caption` for why the field's label ("Artisan") is the
        RELATIONSHIP and not the caption, and why it is only ever the last resort.

        ``cap`` IS THE TEMPLATE'S ``max_photos``, AND IT IS APPLIED PER PLATE rather than across the
        row. Across the row it truncated an insertion-ordered walk, so the galleries declared LAST
        lost every photograph they held while the first kept all of its own: three cluster
        photographs and three of each kind of motif, capped at four, printed four cluster
        photographs and erased both motif galleries entirely. Per plate, the loss falls on the
        gallery that caused it. Whatever it drops is COUNTED — see
        :meth:`_note_photographs_over_cap` — because a photograph dropped in silence is exactly
        the omission rule 10 exists to forbid.
        """
        claims: dict[str, list[tuple[str, FieldSpec, str]]] = {}
        order: list[str] = []
        for media_id, (spec, caption) in self._image_sources(entity, row).items():
            if spec.key not in claims:
                claims[spec.key] = []
                order.append(spec.key)
            claims[spec.key].append((media_id, spec, caption))

        # The plates in the registry's own declaration order, with the shared plate of
        # single-photograph fields sitting where the FIRST of them was declared — so the pictures
        # still run down the page in the order their fields do, and no photograph moves past a
        # gallery it used to come before.
        plates: list[tuple[str, list[tuple[str, FieldSpec, str]]]] = []
        shared: int | None = None
        for key in order:
            claimed = claims[key]
            if len(claimed) > 1:
                plates.append((claimed[0][1].label, claimed))
                continue
            if shared is None:
                shared = len(plates)
                plates.append(("", []))
            plates[shared][1].extend(claimed)

        groups: list[tuple[str, list[tuple[ImageRef, str]]]] = []
        for grid_caption, claimed in plates:
            images: list[tuple[ImageRef, str]] = []
            over = 0
            for media_id, spec, caption in claimed:
                if cap and len(images) >= cap:
                    # COUNTED AND NOT RESOLVED. The resolver records every id it was asked for and
                    # could not answer, and the route warns about it, so asking it for a picture
                    # this template has already decided not to print would manufacture a second,
                    # different loss out of this one.
                    over += 1
                    continue
                ref = self.resolve_media(media_id)
                if ref is None:
                    continue
                # A PLATE'S NAME IS NOT REPEATED UNDER EVERY PICTURE ON IT. The field's label
                # falls in only where the plate has no name of its own — the shared plate, whose
                # pictures come from several fields and where the label under each one is the only
                # thing that says which is which. On a named gallery the grid caption has already
                # said it once, and saying it again eight times is the "Cluster photographs"
                # printed nine times that the first draft of this change produced.
                images.append((ref, caption or ("" if grid_caption else spec.label)))
            if over:
                self._note_photographs_over_cap(over)
            if images:
                groups.append((grid_caption, images))
        return groups

    def _note_photographs_over_cap(self, count: int) -> None:
        """Record photographs the template's ``max_photos`` kept out, against the stage they are in.

        RULE 10, and the reason this is a counter on the builder rather than a note written into the
        document: the loss belongs to the act of generating and not to the report. An officer
        opening the .docx next month must not find a sentence about what was missing on the day —
        which is the rule :func:`build_report` states for every one of its warnings.

        IT QUALIFIES AS A WARNING AT ALL because it is a loss the designer CANNOT SEE from the
        picker, which is the test ``build_report`` sets for adding one. "One photograph per
        prototype" is in COMPACT_SUMMARY's own description; the number is not, no other template
        names it, stage 20 cannot change it, and nothing on the page said that nine photographs of
        a finished product became six.

        Attributed to the STAGE being rendered, because that is the only thing a designer can act
        on — they go and look at that stage. ``_stage_key`` is set by :meth:`_render_stage` and is
        the one piece of "where am I" state on this builder; the cover's hero photograph and the
        photographic annexure both go through :meth:`_images`, which takes no cap, so neither can
        reach this counter and file a loss against whichever stage happened to be rendered last.
        """
        if count > 0 and self._stage_key:
            self._photographs_over_cap[self._stage_key] = (
                self._photographs_over_cap.get(self._stage_key, 0) + count
            )

    def _reference_caption(
        self, entity: EntitySpec, spec: FieldSpec, row: dict[str, Any], reference: ReferencedRecord
    ) -> str:
        """What to print under a photograph borrowed from the record a REF field points at.

        THE ROW'S OWN FROZEN NAME FIRST. ``reference.label`` is
        ``REFERENCE_MODELS[model].label(row)`` evaluated against the record AS IT STANDS TODAY, and
        every external REF in the registry is ``report_role=HIDDEN`` with none named as a
        ``label_field`` — so this caption was the single place in a submitted report where a live
        re-resolved name reached paper. The visible failure had one page carrying both answers: a
        prototype's sub-section printing "Developed from: Sambalpuri Saree" out of the frozen
        ``productName`` hydration copied at save time, and, directly beneath it, the borrowed
        catalogue photograph captioned "Sambalpuri Ikat Saree — revised 2027" because somebody
        renamed the product record after the workshop closed. One product, two names, and nothing
        on the page to say which the workshop actually worked from.

        Generic, with no per-entity code: the hydration mapping already declares which box on THIS
        row the referenced record's display name was copied into, so the caption asks it. Where the
        mapping seeds no name — and where it seeded one into a box the designer left the picker to
        fill and it never arrived — ``reference.label`` is still the right caption and is used, for
        the reason the line it replaced gave: the field's label is the RELATIONSHIP and not the
        subject, so "Artisan" under a photograph is a category where "Bhikari Meher" is a caption.
        The field label remains the last resort for a record whose label never loaded at all.

        WHICH SOURCE KEY HOLDS THE NAME IS NOT ALWAYS ``"name"`` — see
        :data:`_REFERENCE_NAME_SOURCES`. Matching that literal alone missed ``Craft``, which is the
        one model whose frozen name reaches the COVER PAGE.
        """
        sources = ("name", *_REFERENCE_NAME_SOURCES.get(reference.model, ()))
        for source, target in reference_hydration_for(entity.key, spec.key).items():
            if source not in sources:
                continue
            target_spec = entity.field(target)
            if target_spec is None:
                continue
            frozen = self._value(target_spec, row)
            if frozen:
                return frozen
        return reference.label or spec.label

    # -- entity renderers -------------------------------------------------------------

    def _render_narrative(
        self, entity: EntitySpec, row: dict[str, Any], level: int, *, skip: set[str] | None = None
    ) -> bool:
        """Long-text fields as prose under their own sub-headings; the rest as a grid.

        ``skip`` names field keys already printed elsewhere — the columns of the table this
        record is a row of, when the caller is :meth:`_render_table`.

        TABLE_COLUMN fields are rendered here as key-value pairs, which is what makes CARDS
        presentation lossless. Omitting them was a real defect and a silent one: a designer
        filled in a sketch's number, category and expected price, the stage read as 100%
        complete, and none of the three appeared anywhere in the report, because the only role
        that ever printed them was a table this presentation does not draw. Every role the
        registry can carry must be printed by every presentation, or the presentation is a
        filter on the designer's work rather than a layout of it.
        """
        wrote = False
        skip = skip or set()

        def printable(*roles: ReportRole) -> list[tuple[FieldSpec, str]]:
            return [
                (s, v) for s, v in self._printable(entity, row, set(roles)) if s.key not in skip
            ]

        narrative = printable(ReportRole.NARRATIVE)
        pairs = printable(ReportRole.KEY_VALUE, ReportRole.COVER_FIELD, ReportRole.TABLE_COLUMN)
        bullets = printable(ReportRole.BULLETS)

        if pairs:
            # Built as runs rather than handed to ``DocumentBuilder.key_values`` so a RICH_TEXT
            # field in the grid keeps its marks. A key-value cell holds runs and cannot hold a
            # block, which is the same constraint a table cell is under and gets the same answer.
            self.doc.add(
                KeyValueBlock(
                    pairs=tuple((s.label, self._cell_runs(s, row, v)) for s, v in pairs),
                    columns=2,
                )
            )
            wrote = True
        for spec, text in narrative:
            if spec.is_rich_text:
                # THE ONLY PATH THAT KEEPS THE FORMATTING. A designer who bolded three product
                # names and numbered five recommendations wrote structure, not decoration, and
                # flattening it here would have made the rich-text editor a more expensive
                # textarea. ``to_report_blocks`` returns the same block types the interpreter
                # emits by hand, so both writers already know how to print every one of them.
                blocks = rich_text.to_report_blocks(
                    row.get(spec.key), resolve_media=self.resolve_media
                )
                if blocks:
                    if len(text) > 160 or len(blocks) > 1:
                        self.doc.heading(
                            spec.label, min(4, level + 1), numbered=self.template.number_headings
                        )
                    else:
                        self.doc.para(f"{spec.label}:")
                    for block in blocks:
                        self.doc.add(block)
                    wrote = True
                    continue
                # No blocks and yet ``_printable`` gave us something to print means the field is
                # REQUIRED and unfilled, and ``text`` is the "Not recorded." note. Falling through
                # rather than skipping is the difference between a gap the reader can see and one
                # they cannot — which is the entire reason the note exists.
            if spec.type is FieldType.LONG_TEXT and len(text) > 160:
                self.doc.heading(
                    spec.label, min(4, level + 1), numbered=self.template.number_headings
                )
                self.doc.para(text)
            else:
                self.doc.para(f"{spec.label}: {text}")
            wrote = True
        for spec, text in bullets:
            self.doc.heading(spec.label, min(4, level + 1), numbered=self.template.number_headings)
            if spec.is_rich_text:
                # A BULLETS field is a list the designer actually built in the editor: each item is
                # its own BULLET_ITEM or ORDERED_ITEM block, and ``to_report_blocks`` merges the run
                # of them into one list block carrying the right ``ordered`` flag. Rendering it that
                # way is what makes "One deliverable per line" mean a NUMBERED list when the
                # designer numbered it, rather than always the bulleted one the split below emits.
                #
                # It also keeps the marks. The split path cannot: it works from ``text``, which is
                # already flattened by ``format_value``, so a bolded product name inside an item
                # was lost before this branch existed.
                blocks = rich_text.to_report_blocks(
                    row.get(spec.key), resolve_media=self.resolve_media
                )
                if blocks:
                    for block in blocks:
                        self.doc.add(block)
                    wrote = True
                    continue
                # Empty and yet printable means the field is REQUIRED and unfilled, and ``text`` is
                # the "Not recorded." note — which must still print, for the same reason it does in
                # the narrative branch above.
            # THE PRE-PROMOTION PATH, still reached by a plain LONG_TEXT bullets field and by a
            # rich field whose value is a bare string written before the promotion. Semicolons are
            # treated as line breaks because that is how the fields were filled in for two seasons.
            self.doc.bullets([p.strip() for p in text.replace(";", "\n").split("\n")])
            wrote = True
        return wrote

    def _cell_runs(self, spec: FieldSpec, row: dict[str, Any], text: str) -> tuple[Run, ...]:
        """One value as the runs a cell holds — the only place a rich-text field loses structure.

        A table cell and a key-value cell both hold runs, so a RICH_TEXT field inside one keeps
        its bold and its italics and loses its paragraph breaks to single spaces. That trade is
        made in ``rich_text.plain_runs`` and documented there; what matters here is that the
        alternative was ``runs_of(str(the stored dict))``, which printed the document's own JSON
        into the cell.

        An EMPTY rich value falls through to ``text`` deliberately: ``_printable`` substitutes
        "Not recorded." for an unfilled required field, and routing that through ``plain_runs``
        would replace the note with a blank cell — turning a visible gap into an invisible one,
        which is the exact failure the note exists to prevent.
        """
        if spec.is_rich_text and not rich_text.is_empty(row.get(spec.key)):
            return rich_text.plain_runs(row.get(spec.key))
        return runs_of(text)

    def _table_columns(self, entity: EntitySpec) -> list[FieldSpec]:
        """Which fields become table columns, capped so the table stays legible.

        Six columns on A4 is about the limit before a cell is too narrow to hold a craft name.
        Fields beyond that are not lost — :meth:`_render_table` prints the overflow underneath
        each row as a key-value line — but the *table* keeps its shape.

        A MEDIA FIELD IS NEVER A COLUMN, WHATEVER ROLE IT DECLARES, which is the half of a
        cross-surface divergence the handset had already closed alone. ``ReportScreen.kt``'s
        ``renderCollection`` filters ``!isMedia`` out of its columns. The note above that filter
        used to record the divergence as OPEN and said the agreement "has to be made on the server
        side first"; it now records it as closed and cites this docstring back by name. This is
        that server-side half.

        THE CITATION IN THESE LINES HAS ALREADY BEEN WRONG TWICE, WHICH IS WHY IT IS SPELLED OUT.
        First they ended the Android note at "one of the two, not one each" — a phrase no file
        under ``android/`` has ever contained. It is ``docs/AUDIT-2026-08-15.md``'s Remedy
        paragraph: "if the media exclusion is wanted, add it to the server instead — one of the
        two, not one each". Correcting that, they then attributed to the Kotlin a sentence
        beginning "Closing it properly means the two surfaces agreeing on ONE answer", which is not
        in it either. The only words of that note quotable as the Kotlin's are the ones quoted
        above, and the Kotlin is itself quoting its own older self when it writes them. Quote the
        file you name, or name the file you are quoting.

        A picture cannot be a table cell:
        ``format_value`` prints "" for IMAGE and IMAGE_LIST (they are placed by :meth:`_images`,
        which reads the TYPE and never the role, so the photograph still reaches the page) and
        "2 documents attached" for FILE/AUDIO/VIDEO, so the column would be blank or a count while
        eating one of the six slots a real answer needed, and the two surfaces would print
        different column COUNTS for one workshop.

        Latent today and deliberately closed anyway: the registry declares no media TABLE_COLUMN,
        so this filter changes no existing table's shape — which is also why it can be added
        without touching anybody's declared ``column_width_pct``.
        """
        columns = [
            f
            for f in entity.fields
            if self._visible(f) and f.report_role is ReportRole.TABLE_COLUMN and not f.type.is_media
        ]
        return columns[:6]

    def _render_table(
        self, entity: EntitySpec, rows: list[dict[str, Any]], section: TemplateSection, level: int
    ) -> bool:
        columns = self._table_columns(entity)
        if not columns:
            # No field was declared a column, so this collection has no tabular shape. Cards
            # are the honest fallback; an empty table would be a lie about the data.
            return self._render_cards(entity, rows, section, level)

        declared = sum(c.column_width_pct for c in columns)
        if declared and abs(declared - 100.0) < 0.5:
            widths = [c.column_width_pct for c in columns]
        else:
            # Give free-text columns twice the share of a number or a date: a craft name needs
            # the room, a quantity does not.
            weights = [2.0 if c.is_free_text else 1.0 for c in columns]
            total = sum(weights)
            widths = [100.0 * w / total for w in weights]
            widths[-1] += 100.0 - sum(widths)  # absorb the rounding; the model demands exactly 100

        table_rows: list[tuple[tuple[Any, ...], ...]] = []
        for row in rows:
            # THE EMPTY NOTE HAS TO COME FROM ``_printable``, AND THIS CALL SITE USED TO SKIP IT.
            # Every other presentation asks ``_printable`` for its text, so an unfilled REQUIRED
            # field printed "Not recorded." in prose and in the key-value grid — and a table asked
            # ``_value`` directly, which answers "" for the same field, so the identical gap in the
            # identical record was stated in one presentation and silently blank in the other. It is
            # the table that matters most: the case that shows it worst is eighteen prototype rows
            # with one required column left blank on several of them, and a reviewing officer reading
            # a blank cell has no way to tell "not answered" from "not applicable".
            #
            # ``_cell_runs``'s own docstring has asserted for a long time that "``_printable``
            # substitutes 'Not recorded.' for an unfilled required field" and that routing that
            # through ``plain_runs`` "would replace the note with a blank cell — turning a visible
            # gap into an invisible one". That was a true statement about a note this path never
            # handed it: the guard was correct and unreachable. Now it is reached.
            #
            # Keyed by field key rather than zipped, because ``_printable`` legitimately returns
            # FEWER entries than there are columns — an unfilled OPTIONAL column is absent from it,
            # which is exactly the distinction the note exists to draw — and a positional pairing
            # would then shift every remaining cell one column to the left.
            printable = {
                spec.key: text
                for spec, text in self._printable(entity, row, {ReportRole.TABLE_COLUMN})
            }
            table_rows.append(
                tuple(
                    # `_value` and not `format_value`: eight of the eleven REF fields the registry
                    # prints are TABLE_COLUMNs, so this is the call site that put raw cuids into the
                    # prototype, cost sheet and follow-up tables. It stays as the fallback so a column
                    # ``_printable`` declines to speak for — an unfilled optional one, or a caption
                    # field it deliberately withholds — renders exactly as it did before.
                    self._cell_runs(spec, row, printable.get(spec.key) or self._value(spec, row))
                    for spec in columns
                )
            )

        self.doc.add(
            TableBlock(
                columns=tuple(
                    TableColumn(
                        header=spec.label,
                        width_pct=w,
                        numeric=spec.type.is_numeric,
                        align=Align.RIGHT if spec.type.is_numeric else Align.LEFT,
                    )
                    for spec, w in zip(columns, widths)
                ),
                rows=tuple(table_rows),
                caption=entity.title,
            )
        )

        # Everything the table could not carry: the columns that overflowed the six-column cap,
        # AND every other role. Restricting this to NARRATIVE and TABLE_COLUMN silently dropped
        # each row's KEY_VALUE and BULLETS fields — the mirror of the defect in CARDS, and the
        # same consequence: a field the designer filled in that the report never shows.
        column_keys = {c.key for c in columns}
        for index, row in enumerate(rows, start=1):
            has_extra = any(
                s.key not in column_keys
                for s, _v in self._printable(
                    entity,
                    row,
                    {
                        ReportRole.NARRATIVE,
                        ReportRole.TABLE_COLUMN,
                        ReportRole.KEY_VALUE,
                        ReportRole.COVER_FIELD,
                        ReportRole.BULLETS,
                    },
                )
            )
            plates = (
                self._image_groups(entity, row, cap=section.max_photos)
                if section.include_photos
                else []
            )
            if not has_extra and not plates:
                continue
            self.doc.heading(
                self._row_label(entity, row, index),
                min(4, level + 1),
                numbered=self.template.number_headings,
            )
            self._place_image_groups(plates, section)
            self._render_narrative(entity, row, level + 1, skip=column_keys)
        return True

    def _render_cards(
        self, entity: EntitySpec, rows: list[dict[str, Any]], section: TemplateSection, level: int
    ) -> bool:
        """One sub-section per record: heading, photographs, then its fields."""
        for index, row in enumerate(rows, start=1):
            self.doc.heading(
                self._row_label(entity, row, index),
                min(4, level + 1),
                numbered=self.template.number_headings,
            )
            if section.include_photos:
                self._place_image_groups(
                    self._image_groups(entity, row, cap=section.max_photos), section
                )
            self._render_narrative(entity, row, level + 1)
        return bool(rows)

    def _parent_groups(
        self,
        entity: EntitySpec,
        rows: list[dict[str, Any]],
    ) -> list[tuple[str, list[dict[str, Any]]]] | None:
        """A child collection split into the parent records its rows belong to, in the parent's
        order — or ``None`` when this entity has no parent at all.

        ``None`` is what keeps the eighteen stages that declare no parent rendering exactly as
        they did before this method existed: the caller falls straight through to the same one
        call it always made.

        WHAT THIS PRINTS THAT NOTHING PRINTED BEFORE. ``EntitySpec.parent`` is declared in the
        registry, validated by ``stage_schema.validate`` and shipped to every client, and until
        now no renderer read it. So stage 17 printed "Cost sheets" as one table and then "Material
        cost lines" as a SECOND flat table holding every line of every sheet interleaved — and
        ``costSheetRef``, the field that says which line belongs to which sheet, is
        ``report_role=HIDDEN``, so the submitted document contained no way whatsoever to tell
        which materials cost which product. That is the one question a cost sheet exists to
        answer, in a document an officer reads as the basis of a sanctioned amount.

        THE LINK IS FOUND BY MODEL, NOT BY FIELD NAME. The child's REF field whose ``ref_model``
        is the parent entity's ``name`` is the link; matching a key like ``costSheetRef`` would be
        a spelling convention nothing enforces, while model names are validated unique. A parent
        with no such field is a registry mistake and not a document to mangle — the collection is
        printed flat, exactly as before, rather than grouped by a guess.
        """
        parent = self._entities.get(entity.parent) if entity.parent else None
        if parent is None:
            return None
        link = next(
            (f for f in entity.fields if f.type is FieldType.REF and f.ref_model == parent.name),
            None,
        )
        if link is None:
            return None

        buckets: dict[str, list[dict[str, Any]]] = {}
        for row in rows:
            ref = row.get(link.key)
            buckets.setdefault(ref if isinstance(ref, str) and ref else "", []).append(row)

        # THE PARENT'S OWN ORDER, taken from the parent collection rather than from the children.
        # The groups then run down the page in the same sequence as the rows of the table above
        # them; ordering by the children would list the sheets in whatever order somebody happened
        # to type their first line, and a reader comparing the two tables would have to search.
        #
        # ``_row_label`` with the parent's own index is the same call the parent's section makes
        # for its own sub-headings, so a group is headed by the exact words the cost sheet above
        # it is headed by — including the "Cost sheets 2" fallback when a sheet names no product.
        groups: list[tuple[str, list[dict[str, Any]]]] = []
        parent_rows = self.data.rows(self._entity_stage.get(parent.key, ""), parent.key)
        for index, parent_row in enumerate(parent_rows, start=1):
            # A PARENT WITH NO ID OF ITS OWN CLAIMS NOTHING, because the orphan bucket is keyed by
            # the empty string. A sheet that has not synced yet carries no ``_entryId``, and
            # popping "" for it would print every line that names NO sheet at all as that one
            # sheet's breakdown — the same fabrication this method exists to prevent, reached from
            # the other end. ``cost_integrity.analyse_cost_integrity`` guards the identical join
            # the same way, and the two must agree: a total the integrity check calls
            # unattributed cannot appear in the report as some product's material cost.
            entry_id = parent_row.get("_entryId")
            taken = buckets.pop(entry_id, None) if isinstance(entry_id, str) and entry_id else None
            if taken:
                groups.append((self._row_label(parent, parent_row, index), taken))

        orphans = buckets.pop("", [])
        for ref, taken in buckets.items():
            # An id this record does not hold. It still labels itself when the caller loaded the
            # record it names; when it does not, the row it named was deleted after these lines
            # cited it, which is the same state as naming nothing.
            label = self._ref_label(ref)
            if label:
                groups.append((label, taken))
            else:
                orphans.extend(taken)
        if orphans:
            # AN ORPHAN IS PRINTED, LAST, UNDER A HEADING THAT SAYS SO. Filing it under a parent
            # it does not belong to would be a fabrication, and dropping it would delete
            # fieldwork somebody did — a line that cost real money, missing from a total.
            groups.append((f"No {link.label.lower()} recorded", orphans))
        return groups

    def _render_rows(
        self,
        entity: EntitySpec,
        rows: list[dict[str, Any]],
        section: TemplateSection,
        presentation: Presentation,
        level: int,
    ) -> bool:
        """One run of rows, in the presentation the template asked for.

        Split out of :meth:`_render_stage` so a whole collection and one PARENT GROUP of that
        collection reach the page by the identical path. A grouped stage rendered by a second
        path would be a second layout to keep in step with every field the registry gains, and
        the forgetting is silent — which is the failure the module docstring opens with.
        """
        if presentation is Presentation.TABLE:
            return self._render_table(entity, rows, section, level)
        if presentation is Presentation.CARDS:
            return self._render_cards(entity, rows, section, level)
        if presentation is Presentation.GALLERY:
            # THE ONE PLATE THAT CROSSES ROWS, and so the one that is still capped across a whole
            # COLLECTION rather than per gallery field: a pooled catalogue of every record's
            # photographs has no single field to name a grid after, so it stays one uncaptioned
            # grid and its cap stays what it always was. What it never did was SAY what it dropped.
            every = [img for row in rows for img in self._images(entity, row)]
            shown = every[: section.max_photos or len(every)]
            self._note_photographs_over_cap(len(every) - len(shown))
            self._place_images(shown, section)
            return True
        wrote = False
        for row in rows:
            wrote |= self._render_narrative(entity, row, level)
        return wrote

    def _row_label(self, entity: EntitySpec, row: dict[str, Any], index: int) -> str:
        """A human title for one record: its label field, else its first filled text field.

        Five entities name a REF as their label — a cost sheet is titled by its product, a review
        by its sketch — so this goes through `_row_label_text`, which follows the reference to a
        name instead of printing the id it holds.
        """
        return self._row_label_text(entity, row) or f"{entity.title} {index}"

    def _place_images(self, images: list[tuple[ImageRef, str]], section: TemplateSection) -> None:
        if not images:
            return
        if len(images) == 1:
            ref, caption = images[0]
            self.doc.add(ImageBlock(image=ref, width_pct=62.0, caption=caption))
            return
        self.doc.add(
            ImageGridBlock(
                images=tuple(images),
                columns=max(1, min(4, section.photo_columns)),
            )
        )

    def _place_image_groups(
        self, groups: list[tuple[str, list[tuple[ImageRef, str]]]], section: TemplateSection
    ) -> None:
        """One plate per group, each grid named by the gallery field its pictures came from.

        :meth:`_place_images` WITH A CAPTION, and deliberately nothing else. The block types, the
        62% width for a lone photograph and the column clamp are the ones every report already
        used, so no renderer learns a new shape and none of them can drift apart over this.

        The caption is empty for the shared plate of single-photograph fields, where the pictures
        come from several fields and each one carries its own — see :meth:`_image_groups`.
        """
        for caption, images in groups:
            if not images:
                continue
            if len(images) == 1:
                # A LONE PICTURE HAS NO GRID CAPTION TO SIT UNDER, so a named plate that a cap cut
                # down to one photograph puts its name on the picture instead of losing it.
                ref, image_caption = images[0]
                self.doc.add(
                    ImageBlock(image=ref, width_pct=62.0, caption=image_caption or caption)
                )
                continue
            self.doc.add(
                ImageGridBlock(
                    images=tuple(images),
                    columns=max(1, min(4, section.photo_columns)),
                    caption=caption,
                )
            )

    # -- the map ------------------------------------------------------------------------

    def _venue_point(self, setup: dict[str, Any]) -> _MapFacts:
        """The workshop venue, from its measured fix if there is one and its address if not.

        THE MEASURED FIX WINS AND IS NEVER AVERAGED WITH ANYTHING. ``venueLocation`` is a
        coordinate somebody stood at and captured; the address is a name looked up in a table. A
        report that quietly preferred the lookup would move the venue to the district headquarters
        for every workshop held in a village — which is most of them.
        """
        label = next(
            (
                clean_text(setup.get(key)).strip()
                for key in ("venue", "village", "block", "district")
                if clean_text(setup.get(key)).strip()
            ),
            "",
        )
        fix = _geo_point(setup.get("venueLocation"))
        if fix is not None:
            return _MapFacts(
                points=[
                    MapPoint(
                        label=label or "Workshop venue",
                        lat=fix[0],
                        lon=fix[1],
                        kind=MapPointKind.VENUE,
                    )
                ],
                placed=1,
                total=1,
            )

        # Everything the record says about where the venue is, in one string, so the atlas's
        # longest-run matching sees the village AND the district AND the state at once.
        address = ", ".join(
            text
            for text in (
                clean_text(setup.get(key)).strip()
                for key in ("venue", "village", "block", "district")
            )
            if text
        )
        if not address:
            # A workshop that has named its STATE and nothing else has said where in the country
            # it happened and not where it was held. Dropping a venue pin on the state capital for
            # that would put the workshop in a city nobody mentioned; the tinted state carries
            # everything the record actually says, and the map is drawn with no pins at all.
            return _MapFacts(total=1)
        located = _geocode(address, setup.get("state"), self.data.district_points)
        if located is None:
            return _MapFacts(total=1)
        return _MapFacts(
            points=[
                MapPoint(
                    label=located.label or label or "Workshop venue",
                    lat=located.lat,
                    lon=located.lon,
                    kind=MapPointKind.VENUE,
                )
            ],
            states={located.state} if located.state else set(),
            placed=1,
            total=1,
            approximate=0 if located.precise else 1,
        )

    def _artisan_points(self, state_hint: Any) -> _MapFacts:
        """Where each participating artisan lives, folded into one pin per place.

        THE ROW'S OWN FROZEN COPY DECIDES, AND THE LIVE ``Artisan`` RECORD IS ONLY THE LEGACY
        FALLBACK. It was the other way round, on a stated reason that had gone false: "the roster
        itself records a village as free text and no district at all".
        ``REFERENCE_HYDRATION["participant.artisanRef"]`` copies ``village``, ``district``,
        ``state``, ``pincode``, ``address`` and ``subjectLocation`` onto the roster row at save
        time, and ``participant`` declares every one of them — so the frozen, provenance-correct
        answer the participant table beside this map prints was sitting on the row and was used
        only as second choice.

        That is the one place a submitted report re-resolved a live row, and it showed. A
        researcher corrects an artisan's ``Location`` in June, or merges the record into a
        duplicate and deletes it; the February report is regenerated; the roster TABLE still says
        Bargarh because that is the frozen copy, and the map above it pins the artisan somewhere
        else. One document, two answers about where one person lives, with nothing on the page to
        say why. It does not even take an edit: hydration is only-fill-blanks, so a designer who
        OVERTYPES the roster row's district got the same disagreement on the day they typed it.

        ONE SOURCE PER ROW, NEVER A MIX. A row that states a place AND the state it is in is read
        entirely from itself; a row missing either half is read entirely from the reference. Taking
        the text from one and the state from the other would put the live record back in the path
        by the side door: an edit to the artisan's state would still move a pin whose village came
        from the frozen copy.

        ASKING FOR THE STATE AS WELL IS NOT PEDANTRY, IT IS THE ROWS THE MAPPING WIDENED AROUND.
        ``village`` has been copied down since the initial commit and ``district``/``state``
        only much later, so a row saved in between states a village and nothing that can place it —
        and while this gate asked ``if not text`` alone, those rows were geocoded against the
        WORKSHOP's state and landed on the wrong capital. The comment on the gate carries the
        measurement.

        THE STATED SUBJECT PIN WINS OVER ANY NAME LOOKUP, the way ``_venue_point``'s measured fix
        wins over the venue's address. ``subjectLocation`` is the pin a researcher dropped on the
        artisan's own place; geocoding the district name instead drew them on a district centroid
        or a state capital and then counted them in the caption's "approximate" sentence, exactly
        as if no pin had ever been dropped. It is read from the ROW and never re-derived through
        :attr:`WorkshopData.references`, which would re-open the defect above, and it is the
        STATED pin rather than the device fix — see :data:`MAP_ROSTER_PIN_KEY`.

        The reference is still reached through the SAME resolver the picker uses
        (``design_workshops.REFERENCE_MODELS``), loaded once by the caller into
        :attr:`WorkshopData.references` — this module must not and does not query.

        A ROW THAT STATES NO PLACE AT ALL CONTRIBUTES NO PIN. Falling back to the workshop's own
        state for those would draw a pin at the state capital for every artisan whose address was
        never recorded, and thirty such pins stacked on Bhubaneswar is a map that says "artisans
        came from across Odisha" about a record that says nothing whatsoever. The count of
        unplaced rows goes in the caption instead, where it reads as the gap it is.
        """
        entity = self._entity(MAP_ROSTER_STAGE, MAP_ROSTER_ENTITY)
        if entity is None:
            return _MapFacts()
        ref_keys = [
            f.key for f in entity.fields if f.type is FieldType.REF and f.ref_model == "Artisan"
        ]

        from app.services.address import canonical_state

        rows = self.data.rows(MAP_ROSTER_STAGE, MAP_ROSTER_ENTITY)
        found: list[tuple[str, _Located]] = []
        facts = _MapFacts(total=len(rows))
        for row in rows:
            # The row's whole stated address in one string, joined rather than "first non-empty":
            # a bare village with no district beside it is what the atlas is worst at, and taking
            # only the most specific key would have placed pins WORSE than the live lookup did.
            stated = [clean_text(row.get(key)).strip() for key in MAP_ROSTER_PLACE_KEYS]
            text = ", ".join(part for part in stated if part)
            # The most specific part the row states, kept beside the joined string for the pin
            # LABEL — see the surveyed-pin branch below, which is the one pin on this map that is
            # not named by the atlas. ``MAP_ROSTER_PLACE_KEYS`` is ordered village-then-district,
            # so the first non-empty element is the finest thing the row says.
            place_label = next((part for part in stated if part), "")
            state = clean_text(row.get(MAP_ROSTER_STATE_KEY)).strip()
            if not text or not state:
                # LEGACY ONLY — AND "LEGACY" IS TWO ROW SHAPES, NOT ONE. This gate read ``if not
                # text``, on the belief that a pre-widening row had no stated address at all. It
                # had part of one: ``"village": "village"`` has been in
                # ``REFERENCE_HYDRATION["participant.artisanRef"]`` since the initial commit, while
                # ``district`` and ``state`` were added much later — so a row saved in between
                # carries a village and NOTHING to place it with. Those rows stayed on the
                # row-first branch, never reached the reference, and had the WORKSHOP's state
                # handed to the geocoder as though the artisan had named it: a Bargarh weaver in a
                # Rajasthan workshop pinned on Jaipur, ~1,300 km out, with Odisha not tinted at
                # all. A row carrying MORE frozen data drew a worse pin than one carrying none.
                #
                # A row that has not frozen its state has not frozen its address, so it is read
                # from the record like any other pre-widening row.
                #
                # THIS DOES NOT REOPEN THE NEVER-RE-RESOLVE DEFECT. Every row hydrated since the
                # mapping widened carries ``state`` beside ``village`` and so never gets here; the
                # row-first branch still owns every row a current save produced.
                reference = next(
                    (
                        r
                        for r in (self.data.reference(row.get(key)) for key in ref_keys)
                        if r is not None
                    ),
                    None,
                )
                if reference is not None:
                    ref_place = clean_text(reference.place).strip()
                    ref_district = clean_text(reference.district).strip()
                    # THE PLACE FIRST AND THE DISTRICT AFTER IT, which is the order
                    # :data:`MAP_ROSTER_PLACE_KEYS` states, the order :meth:`_venue_point` joins
                    # in, and — until this line was corrected — the one order this branch did not
                    # use. It joined ``(ref_district, ref_place)``, and the order is not
                    # cosmetic: ``place_atlas.resolve_place`` scans longest runs first and
                    # LEFTMOST WINS AT EQUAL WIDTH, so with the district in front, any district
                    # that is ALSO a curated town outranks the village beside it. "Bargarh,
                    # Barpali" resolved to Bargarh town, ~16 km from Barpali and labelled as the
                    # wrong place, while the identical address on a post-widening row went down
                    # the branch above as "Barpali, Bargarh" and resolved to Barpali. One map,
                    # two answers for one village, decided by when the row happened to be saved.
                    ref_text = ", ".join(part for part in (ref_place, ref_district) if part)
                    # BOTH VALUES MOVE TOGETHER OR NEITHER DOES, per "one source per row" above:
                    # disambiguating "Bhuj, Kachchh" against a state the row froze separately is
                    # how a village lands 900 km away. And only when the record actually states a
                    # place — an ``Artisan`` with an empty ``Location`` is not a second source, and
                    # blanking the row's own village against it would drop a pin the row could
                    # still have placed.
                    if ref_text:
                        text, state = ref_text, clean_text(reference.state).strip()
                        place_label = ref_place or ref_district
            if not state:
                # The workshop's own state, and last. It is a hint for disambiguating a village
                # name, never a position: a row that states nothing still contributes no pin.
                state = clean_text(state_hint).strip()

            fix = _geo_point(row.get(MAP_ROSTER_PIN_KEY))
            if fix is not None:
                # PRECISE BY CONSTRUCTION, so it is counted in ``placed`` and never in
                # ``approximate`` — describing a surveyed pin with the caption's "drawn at its
                # state capital" sentence would be a straight falsehood. The label is what the row
                # SAYS the place is; a coordinate carries no name, and printing the artisan's own
                # name on a map pin is not the same figure.
                #
                # ONE LABEL GRAMMAR PER FIGURE. Every other pin here is named by the atlas, which
                # returns a single token ("Barpali"), so this one takes the most specific part the
                # row stated rather than the joined address it was geocoded from — a map that says
                # "Barpali" for five artisans and "Barpali, Bargarh" for the sixth reads as two
                # kinds of pin and invites a reader to look for the distinction.
                #
                # The state falls back to the UNCANONICALISED spelling exactly as ``_geocode``
                # does, so the one pin built without the atlas does not disagree with every other
                # pin about what a state is. ``canonical_state`` alone dropped a spelling it does
                # not recognise to "", which ``_render_map`` then filters out of ``highlight``
                # entirely — a pin drawn on an untinted region with nothing anywhere saying why.
                # Carried through, ``report_map._tint_states`` cannot seed it either, but it
                # returns the name in ``missed`` and the figure prints "Not tinted: …" under
                # itself. An admission on the picture beats a silent blank on the half of a map a
                # reader trusts most.
                located = _Located(
                    lat=fix[0],
                    lon=fix[1],
                    state=canonical_state(state) or state,
                    label=place_label or "Artisan's home",
                )
            else:
                if not text:
                    continue
                located = _geocode(text, state, self.data.district_points)
                if located is None:
                    continue
            found.append((located.label, located))
            facts.placed += 1
            if located.state:
                facts.states.add(located.state)
            if not located.precise:
                facts.approximate += 1
        facts.points = _fold_points(found, MapPointKind.ARTISAN)
        return facts

    def _render_map(self, section: TemplateSection) -> None:
        """The workshop's map, once stage 1 says which part of the country this is.

        THE GATE IS A STATE OR A DISTRICT AND NOTHING ELSE. A map of India with no idea which bit
        of it the workshop happened in is not a figure, it is a decoration; but a workshop that has
        named its state has said enough for the tinted region alone to be worth printing, even
        before a single address resolves. That is why the block is emitted with an empty point
        tuple rather than skipped — ``report_map.render_map_png`` draws that case deliberately, and
        a figure that vanished whenever the village names were unfamiliar would read to a designer
        as a broken renderer rather than as unresolvable data.
        """
        setup = self.data.singleton(MAP_VENUE_STAGE)
        state = clean_text(setup.get("state")).strip()
        district = clean_text(setup.get("district")).strip()
        if not state and not district:
            return

        venue = self._venue_point(setup)
        artisans = self._artisan_points(state)
        facts = venue.merge(artisans)

        from app.services.address import canonical_state

        # Every state a pin actually landed in, PLUS the one stage 1 typed. Both halves matter: a
        # workshop whose artisans all came from the next state over must tint both, and a workshop
        # whose addresses resolved to nothing at all must still tint its own — that tinted region
        # is the entire content of the figure in the empty case.
        highlight = set(facts.states)
        if state:
            highlight.add(canonical_state(state) or state)

        # The venue sentence names the place THE RECORD names, not the place the atlas resolved.
        # A reader who typed "Barpali" and reads "Workshop venue at Odisha" concludes the report
        # lost their answer; the pin's own label still says Odisha, and the sentence below says
        # once, plainly, why a pin might not sit where they expect it.
        stated_venue = ", ".join(
            text
            for text in (
                clean_text(setup.get(key)).strip() for key in ("venue", "village", "district")
            )
            if text
        )
        sentences: list[str] = []
        if venue.points and stated_venue:
            sentences.append(f"Workshop venue: {stated_venue}.")
        if artisans.placed:
            sentences.append(
                f"Home places of {artisans.placed} of {artisans.total} participating artisans, "
                f"at {len(artisans.points)} location(s)."
            )
        if artisans.total > artisans.placed:
            sentences.append(
                f"{artisans.total - artisans.placed} participant(s) recorded no address that "
                f"could be placed."
            )
        if facts.approximate:
            sentences.append(
                "A place this atlas cannot resolve is drawn at its state capital, and its pin is "
                "labelled with the state rather than with the place."
            )
        if not facts.points:
            sentences.append(
                "No address in the record could be resolved to a position; the map shows the "
                "region only."
            )

        if section.page_break_before:
            self.doc.add(PageBreakBlock())
        self.doc.heading(
            section.heading or "Workshop location and participants' origins",
            1,
            numbered=self.template.number_headings,
        )
        if section.intro:
            self.doc.para(section.intro, style=ParaStyle.LEAD)
        self.doc.add(
            MapBlock(
                caption=" ".join(sentences),
                points=tuple(facts.points),
                highlight=frozenset(h for h in highlight if h),
            )
        )

    # -- stage --------------------------------------------------------------------------

    def _render_stage(self, spec: StageSpec, section: TemplateSection) -> None:
        # WHERE A CAPPED PHOTOGRAPH IS REPORTED FROM — see
        # :meth:`_note_photographs_over_cap`. Set before the emptiness check so a stage that
        # renders nothing cannot leave the previous stage's key standing.
        self._stage_key = spec.key
        singleton_data = self.data.singleton(spec.key)
        has_rows = any(self.data.rows(spec.key, e.key) for e in spec.collections)
        has_any = bool(singleton_data) or has_rows
        if not has_any and section.omit_if_empty:
            return

        if section.page_break_before:
            self.doc.add(PageBreakBlock())
        self.doc.heading(section.heading or spec.title, 1, numbered=self.template.number_headings)
        if section.intro:
            self.doc.para(section.intro, style=ParaStyle.LEAD)

        wrote = False
        single = spec.singleton
        if single is not None and singleton_data:
            wrote |= self._render_narrative(single, singleton_data, 1)
            metrics = self._printable(single, singleton_data, {ReportRole.METRIC})
            if metrics:
                self.doc.add(
                    MetricRowBlock(metrics=tuple((s.label, v, s.unit) for s, v in metrics[:4]))
                )
                wrote = True
            if section.include_photos:
                plates = self._image_groups(single, singleton_data, cap=section.max_photos)
                if plates:
                    self._place_image_groups(plates, section)
                    wrote = True

        for entity in spec.collections:
            if section.entities and entity.key not in section.entities:
                continue
            rows = self.data.rows(spec.key, entity.key)
            if not rows:
                if not section.omit_if_empty:
                    self.doc.heading(entity.title, 2, numbered=self.template.number_headings)
                    self.doc.para(f"No {entity.title.lower()} were recorded.", style=ParaStyle.NOTE)
                continue
            if len(spec.collections) > 1:
                self.doc.heading(entity.title, 2, numbered=self.template.number_headings)
            presentation = section.presentation
            if presentation is Presentation.AUTO:
                presentation = (
                    Presentation.TABLE if self._table_columns(entity) else Presentation.CARDS
                )
            groups = self._parent_groups(entity, rows)
            if groups is None:
                wrote |= self._render_rows(entity, rows, section, presentation, 1)
                continue
            # A CHILD COLLECTION IS PRINTED UNDER ITS PARENTS, one sub-heading each — see
            # :meth:`_parent_groups` for the document that could not be read without it. The
            # heading sits one level below whatever named this collection, which is the entity
            # heading above when the stage has several collections and the stage's own heading
            # when it has one, so the group never outranks the thing it is part of.
            group_level = 3 if len(spec.collections) > 1 else 2
            for title, group_rows in groups:
                self.doc.heading(title, group_level, numbered=self.template.number_headings)
                wrote |= self._render_rows(entity, group_rows, section, presentation, group_level)

        if section.include_figures:
            # AFTER the stage's own content, never before it. A figure is a summary of the table
            # above it, and a reader who meets the picture first reads the numbers in the table as
            # a breakdown of the chart rather than as the record the chart was derived from.
            for chart in self._charts_for(spec.key):
                self.doc.add(chart)
                wrote = True

        if not wrote and self.template.show_empty_note:
            self.doc.para("Not recorded.", style=ParaStyle.NOTE)

    # -- the infographics ---------------------------------------------------------------

    def _entity(self, stage_key: str, entity_key: str) -> EntitySpec | None:
        stage = self._stages.get(stage_key)
        if stage is None:
            return None
        return next((e for e in stage.entities if e.key == entity_key), None)

    def _label_of(self, stage_key: str, entity_key: str, field_key: str, fallback: str) -> str:
        """A field's registry label, so a figure's axis and its table's header cannot disagree.

        Hard-coding "Material" beside a column the registry calls "Material cost" is the kind of
        difference nobody reads as a bug and everybody reads as two different measures.
        """
        entity = self._entity(stage_key, entity_key)
        spec = entity.field(field_key) if entity else None
        return spec.label if spec else fallback

    def _output_count(self, stage_key: str, entity_key: str, override_key: str = "") -> int:
        """How many of something this report states there were.

        ONE FUNCTION, because the number has to be the same everywhere it appears. The metric row
        on the front page derived its counts from the rows, the yield chart derived its own, and
        stage 18's ``designsCountOverride`` was printed raw forty pages later — so one document
        said "Sketches 10" at the front and "Number of designs (override) 24" in the middle, with
        the reason under the second one. An officer reading it cannot tell which to quote.

        THE OVERRIDE WINS, which is what the stage note already said it was for: a designer
        records 24 designs because only 18 sketches were ever photographed into the record, and
        the count they state is the finding. It is stated ONCE now — the override fields are
        ``report_role=HIDDEN`` — and ``countOverrideReason`` is printed beside it as the caption,
        so the explanation travels with the number instead of sitting forty pages away.
        """
        rows = len(self.data.rows(stage_key, entity_key))
        if not override_key:
            return rows
        stated = _as_number(self.data.value("WORKSHOP_OUTCOMES", override_key))
        if stated is None or stated < 0:
            return rows
        return int(stated)

    def _count_override_reason(self) -> str:
        """Why the stated counts differ from the records, if the designer said."""
        outcomes = self.data.singleton("WORKSHOP_OUTCOMES")
        if not any(
            _as_number(outcomes.get(key)) is not None
            for key in ("designsCountOverride", "prototypesCountOverride")
        ):
            return ""
        return clean_text(outcomes.get("countOverrideReason")).strip()

    def _chart_output_counts(self) -> ChartBlock | None:
        """Sketches against prototypes against final products — the workshop's yield.

        Through ``_output_count``, so this figure and the metric row on the front page state the
        same numbers. They used to derive independently while stage 18's overrides printed raw
        beside them, which is how one report came to carry two different figures for one measure.
        """
        counts = (
            (
                "Sketches",
                self._output_count("SKETCH_DEVELOPMENT", "sketch", "designsCountOverride"),
            ),
            (
                "Prototypes",
                self._output_count("PROTOTYPE_DEVELOPMENT", "prototype", "prototypesCountOverride"),
            ),
            ("Final products", self._output_count("FINAL_PROTOTYPE_DOCUMENTATION", "finalProduct")),
        )
        series = [(label, float(n)) for label, n in counts if n]
        if len(series) < MIN_CHART_CATEGORIES:
            return None
        reason = self._count_override_reason()
        return ChartBlock(
            kind=ChartKind.BAR,
            series=tuple(series),
            title="Designs, prototypes and final products",
            caption=reason or "Counted from the records in this report, not typed.",
        )

    def _chart_prototype_status(self) -> ChartBlock | None:
        """How the review dispositioned each prototype: selected, revised, rejected, pending."""
        entity = self._entity("PROTOTYPE_VALIDATION", "prototypeValidation")
        spec = entity.field("decision") if entity else None
        if spec is None:
            return None
        tally: dict[str, int] = {}
        for row in self.data.rows("PROTOTYPE_VALIDATION", "prototypeValidation"):
            token = clean_text(row.get("decision")).strip()
            if not token:
                continue
            tally[enum_label(spec.enum, token)] = tally.get(enum_label(spec.enum, token), 0) + 1
        if len(tally) < MIN_CHART_CATEGORIES:
            return None
        return ChartBlock(
            kind=ChartKind.DONUT,
            series=tuple((label, float(count)) for label, count in tally.items()),
            title="Prototypes by review decision",
            caption=f"{sum(tally.values())} prototype(s) reviewed.",
        )

    def _survey_responses(self) -> list[dict[str, Any]]:
        """Stage 8's response rows. One accessor so both survey figures read the same set."""
        return self.data.rows("MARKET_SURVEY_CAPTURE", "surveyResponse")

    def _unentered_responses(self) -> int:
        """How many responses the summary CLAIMS were collected beyond the rows actually entered.

        ``surveySummary.responsesCollected`` is a number the designer types; the rows beneath it are
        what they entered. The two disagree routinely and legitimately — a hundred people answered
        in a market, twelve were written up — and a figure drawn from the twelve is a figure about
        twelve people. Stating the gap in the caption is the whole difference between "this is what
        the survey found" and "this is what was typed in", and a reader cannot recover it from the
        picture. Zero when the summary states nothing, or states no more than the rows carry: a
        stated count BELOW the row count is the designer's number being stale, not work skipped, so
        it is not reported as a gap.
        """
        stated = _as_number(self.data.value("MARKET_SURVEY_CAPTURE", "responsesCollected"))
        if stated is None or stated < 0:
            return 0
        return max(0, int(stated) - len(self._survey_responses()))

    def _chart_survey_respondents(self) -> ChartBlock | None:
        """Who the survey actually asked: stage 8's responses tallied by respondent group.

        THE FIRST FIGURE DRAWN FROM THE SURVEY, and it answers the question the survey table raises
        and never answers — thirty rows of free text, and no way to see that twenty-six of them are
        consumers and the retailer view rests on one person. Every later stage leans on this stage:
        stage 9's price bands, its SWOT and its design direction all cite "the survey".

        REGISTRY ORDER, NOT COUNT ORDER, for ``_chart_adoption``'s reason one step further on.
        ``RESPONDENT_GROUP`` is declared consumer first, then the trade (retailer, wholesaler,
        exporter), then the makers, then the institutions — so the same picture has the same shape in
        every report and two workshops can be compared by eye. Sorting by count would reshuffle the
        axis for every workshop, which is the one property that makes that comparison possible.
        Tokens the registry does not know are printed after it, in the order they were met, rather
        than dropped — a phone one release ahead can store one, and ``enum_label`` already prefers
        the raw token to failing an export a designer is waiting on in the field.

        A GROUP NOBODY SURVEYED IS ABSENT, NOT ZERO. "Exporter 0" beside four real bars is read as
        "exporters were asked and had nothing to say"; what the record says is that none were met.
        """
        entity = self._entity("MARKET_SURVEY_CAPTURE", "surveyResponse")
        spec = entity.field("respondentGroup") if entity else None
        if spec is None:
            return None
        from app.services.stage_schema import ENUMS

        known = list(ENUMS.get(spec.enum, {}))
        tally: dict[str, int] = {}
        unstated = 0
        for row in self._survey_responses():
            token = clean_text(row.get("respondentGroup")).strip()
            if not token:
                unstated += 1
                continue
            tally[token] = tally.get(token, 0) + 1
        # Registry order first, then anything stored that the registry has never heard of, in the
        # order it was met — ``dict`` preserves insertion, so ``tally`` IS that order.
        order = [t for t in known if t in tally] + [t for t in tally if t not in known]
        series = [(enum_label(spec.enum, token), float(tally[token])) for token in order]
        if len(series) < MIN_CHART_CATEGORIES:
            return None
        plotted = sum(tally.values())
        caption = f"{plotted} response(s) plotted, from the rows entered at this workshop."
        # EVERY ROW THIS FIGURE DID NOT COUNT IS NAMED. A tally that quietly stops short is
        # indistinguishable from a survey that reached fewer people than it did.
        if unstated:
            caption += f" {unstated} more recorded no respondent group and could not be plotted."
        unentered = self._unentered_responses()
        if unentered:
            caption += (
                f" The survey summary states {plotted + unstated + unentered} response(s) "
                f"collected, so {unentered} were never entered as rows and are in no figure here."
            )
        return ChartBlock(
            kind=ChartKind.HORIZONTAL_BAR,
            series=tuple(series),
            title="Survey responses by respondent group",
            unit="responses",
            caption=caption,
        )

    def _chart_survey_price_expectations(self) -> ChartBlock | None:
        """What the people surveyed said they would pay, banded.

        DELIBERATELY NOT ``PRICE_BANDS``, which bins the ``expectedPrice`` on stage 17's cost
        sheets — what the workshop means to charge. This bins stage 8's ``priceExpectation`` — what
        a buyer standing in a market said they would pay. The gap between the two pictures is the
        single most useful thing this report can show a designer, and it only exists because they
        are two figures rather than one merged distribution. Their titles say which is which; both
        carry the count their band heights are built from.

        Through ``_price_bands``, the same binner stage 17's figure uses, so the two are comparable
        band for band. A MONEY value reaches this builder as a fixed-2 decimal STRING and never as a
        number: ``stage_schema.coerce_value`` stores ``f"{checked:.2f}"`` so the value survives the
        JSON round trip without picking up a binary-float artefact (1250.10 coming back as
        1250.0999999999999). ``_as_number`` parses it and rejects NaN, the infinities and ``bool``.
        """
        responses = self._survey_responses()
        prices: list[float] = []
        for row in responses:
            amount = _as_number(row.get("priceExpectation"))
            if amount is not None and amount > 0:
                prices.append(amount)
        bands = _price_bands(prices)
        if len(bands) < MIN_CHART_CATEGORIES:
            return None
        caption = (
            f"{len(prices)} of {len(responses)} response(s) stated a price expectation, in rupees."
        )
        unentered = self._unentered_responses()
        if unentered:
            caption += (
                f" A further {unentered} response(s) the summary counts were never entered as rows."
            )
        return ChartBlock(
            kind=ChartKind.BAR,
            series=tuple(bands),
            title="What respondents said they would pay",
            unit="responses",
            caption=caption,
        )

    def _chart_cost_by_head(self) -> ChartBlock | None:
        """Where the money went, summed across every cost sheet in the record.

        Only heads that carry a figure appear. A cost breakdown listing "Transport ₹ 0" beside four
        real heads is read as "transport was free", when what the record says is that nobody
        entered it — and on a document that becomes a sanctioned amount, the difference between
        those two readings is the whole point of the sheet.
        """
        heads = (
            "materialCost",
            "labourCost",
            "packagingCost",
            "finishingCost",
            "transportCost",
            "overheadCost",
        )
        totals = dict.fromkeys(heads, 0.0)
        for row in self.data.rows("COSTING_MARKET_LINKAGE", "costSheet"):
            for key in heads:
                amount = _as_number(row.get(key))
                if amount is not None and amount > 0:
                    totals[key] += amount
        series = [
            (self._label_of("COSTING_MARKET_LINKAGE", "costSheet", key, key), value)
            for key, value in totals.items()
            if value > 0
        ]
        if len(series) < MIN_CHART_CATEGORIES:
            return None
        return ChartBlock(
            kind=ChartKind.HORIZONTAL_BAR,
            series=tuple(series),
            title="Cost by head, all products",
            unit="INR",
            caption="Summed across every cost sheet recorded at this workshop.",
        )

    def _chart_price_bands(self) -> ChartBlock | None:
        """How many products fall in each price band — the range the workshop actually produced."""
        prices: list[float] = []
        for row in self.data.rows("COSTING_MARKET_LINKAGE", "costSheet"):
            amount = next(
                (
                    value
                    for value in (
                        _as_number(row.get("expectedPrice")),
                        _as_number(row.get("retailPrice")),
                    )
                    if value is not None and value > 0
                ),
                None,
            )
            if amount is not None:
                prices.append(amount)
        bands = _price_bands(prices)
        if len(bands) < MIN_CHART_CATEGORIES:
            return None
        return ChartBlock(
            kind=ChartKind.BAR,
            series=tuple(bands),
            title="Products by price band",
            unit="products",
            caption=f"{len(prices)} product(s) with a recorded price, in rupees.",
        )

    def _chart_adoption(self) -> ChartBlock | None:
        """Products still being made at three, six and twelve months after the workshop.

        The interval order comes from the registry's own enum rather than from sorting the labels,
        because "M12" sorts before "M3" and a follow-up line that goes 3 → 12 → 6 months reads as a
        collapse and a recovery that never happened.
        """
        entity = self._entity("POST_WORKSHOP_FOLLOWUP", "followUp")
        interval_spec = entity.field("interval") if entity else None
        if interval_spec is None:
            return None
        from app.services.stage_schema import ENUMS

        # ``ENUMS`` is a plain dict and Python preserves insertion order, so the declaration order
        # in the registry IS the chronological order — which is the property this figure needs and
        # the reason it is not sorted.
        order = [token for token in ENUMS.get(interval_spec.enum, {}) if token != "AD_HOC"]
        adopted = {"ADOPTED_IN_PRODUCTION", "ADOPTED_ON_ORDER"}
        tally: dict[str, int] = {}
        seen: set[str] = set()
        for row in self.data.rows("POST_WORKSHOP_FOLLOWUP", "followUp"):
            token = clean_text(row.get("interval")).strip()
            if token not in order:
                continue
            seen.add(token)
            if clean_text(row.get("adoptionStatus")).strip() in adopted:
                tally[token] = tally.get(token, 0) + 1
        # Only intervals that were actually visited. A twelve-month column on a workshop that ran
        # four months ago is not "zero adoption", it is a visit that has not happened yet.
        series = [
            (enum_label(interval_spec.enum, token), float(tally.get(token, 0)))
            for token in order
            if token in seen
        ]
        if len(series) < MIN_CHART_CATEGORIES or sum(v for _l, v in series) <= 0:
            return None
        return ChartBlock(
            kind=ChartKind.LINE,
            series=tuple(series),
            title="Products still in production at follow-up",
            unit="products",
            caption="Counted from follow-up visits that were actually made.",
        )

    def _figure(self, figure_id: str) -> ChartBlock | None:
        """Build one figure by id, once per document.

        ONCE is the point of the ``_drawn`` set. A template may place a figure explicitly through a
        ``CHART`` section AND carry the stage section the same figure belongs to, and printing the
        cost breakdown twice in one report makes a reader hunt for the difference between the two
        pictures. The first place the template asks for it wins, which makes the running order the
        thing that decides — exactly as it does for every other section.
        """
        entry = FIGURES.get(figure_id)
        if entry is None or figure_id in self._drawn:
            return None
        block = getattr(self, entry[1])()
        # A builder returns None when the record cannot fill the figure; the total guard is the
        # belt to that braces. A series that survived every rule above and still sums to zero must
        # not be drawn: a pie of zeros is a division by zero waiting for the first renderer that
        # forgets to check, and a bar chart of zeros is a flat line presented as a finding.
        if block is None or block.total <= 0:
            return None
        self._drawn.add(figure_id)
        return block

    def _charts_for(self, stage_key: str) -> list[ChartBlock]:
        """Every not-yet-drawn figure this stage's data can honestly support."""
        blocks = [
            self._figure(figure_id)
            for figure_id, (owner, _method) in FIGURES.items()
            if owner == stage_key
        ]
        return [block for block in blocks if block is not None]

    def _render_charts(self, section: TemplateSection) -> None:
        """A ``CHART`` section: the figures the template asked for, under one heading.

        Nothing at all is emitted when the data supports none of them — not the heading, not an
        empty frame, not a note. A heading over nothing is the single most common way a generated
        report looks broken, and this section exists precisely for records that may have no
        figures in them.
        """
        wanted = section.figures or tuple(FIGURES)
        blocks = [block for block in (self._figure(f) for f in wanted) if block is not None]
        if not blocks:
            return
        if section.page_break_before:
            self.doc.add(PageBreakBlock())
        self.doc.heading(
            section.heading or "The workshop in figures", 1, numbered=self.template.number_headings
        )
        if section.intro:
            self.doc.para(section.intro, style=ParaStyle.LEAD)
        for block in blocks:
            self.doc.add(block)

    # -- special sections ---------------------------------------------------------------

    def _render_cover(self, section: TemplateSection) -> None:
        setup = self.data.singleton("WORKSHOP_SETUP")
        spec = self._stages.get("WORKSHOP_SETUP")
        rows: list[tuple[str, str]] = []
        if spec and spec.singleton:
            for f in spec.singleton.fields:
                if f.report_role is ReportRole.COVER_FIELD and self._visible(f):
                    text = format_value(f, setup.get(f.key))
                    if text:
                        rows.append((f.label, text))

        settings = self.data.singleton("REPORT_GENERATION")

        hero = None
        if spec and spec.singleton and section.include_photos:
            images = self._images(spec.singleton, setup, limit=1)
            if images:
                hero = images[0][0]
        # THE LOGO STAGE 20 ASKS FOR, which was declared, uploaded, stored — and never resolved,
        # so the cover of every report carried the template's constant text and no mark at all.
        # It has to be in the media resolver's prefetch set as well or this resolves to None and
        # the block is silently dropped; see `design_workshops.media_resolver`, which adds it.
        logo_ids = _media_ids(settings.get("logo"))
        logo = self.resolve_media(logo_ids[0]) if logo_ids else None

        # ``meta.organisation`` AND NOT ``template.organisation``. The template's constant is the
        # last of three, and `report_meta` has already applied the precedence: the stage-20
        # ``organisationLine`` — whose own help text says "Printed above the title on the cover."
        # — then the workshop's implementing agency, then the template's. Reading the template
        # directly here is what made that field inert: a designer typed their institute's name,
        # the cover printed the ministry's, and nothing anywhere said the box did nothing.
        org = self.doc.meta.organisation or self.template.organisation
        # The letterhead is the block of address lines an institution puts above its own name.
        # Printed under the org line, one paragraph per line, capped so a pasted signature block
        # cannot push the title off the page.
        letterhead = [
            line.strip()
            for line in clean_text(settings.get("letterheadText")).split("\n")
            if line.strip()
        ][:6]
        self.doc.add(
            CoverBlock(
                title=self.doc.meta.title,
                subtitle=self.doc.meta.subtitle,
                org_lines=tuple(
                    x for x in ("Government of India • Ministry of Textiles", org, *letterhead) if x
                ),
                logo=logo,
                hero_image=hero,
                info_rows=tuple(rows[:COVER_INFO_ROWS]),
                footer_lines=tuple(
                    x
                    for x in (
                        _submission_line(settings),
                        (
                            f"Generated on {_format_date(self.doc.meta.generated_at[:10])}"
                            if self.doc.meta.generated_at
                            else ""
                        ),
                    )
                    if x
                ),
            )
        )

        # ── the cover fields this template will print NOWHERE, recorded for a warning ──────────
        #
        # THE COVER CAP IS EDITORIAL AND THE SILENCE AROUND IT IS NOT. Stage 1 declares twenty-one
        # COVER_FIELD boxes and a filled-in workshop fills most of them, so `rows` regularly runs
        # past `COVER_INFO_ROWS` — and the overflow is dropped here with nothing said. For four of
        # the six templates that costs nothing, because they also print the WORKSHOP_SETUP stage
        # section and `_render_narrative` prints COVER_FIELD there as a key-value pair, so every
        # overflowed value appears a page later. For IMPLEMENTING_AGENCY and PHOTO_CATALOGUE,
        # which carry a cover and no stage 1, the overflow appears NOWHERE IN THE DOCUMENT: on a
        # fully documented workshop that is the start and end dates, the duration, the designer's
        # institution, the sanction order and its date, and the workshop code, typed by a designer
        # and absent from the file with no gap visible where they should be.
        #
        # NOT FIXED BY PRINTING THEM ANYWAY, and the reason is written into this pipeline twice
        # over. Where they would go is an editorial decision belonging to the template — and
        # `report_templates.TEMPLATES` is pinned by value against the Kotlin port in a 485 KB
        # fixture that can only be regenerated inside the API container, so the natural fix (give
        # IMPLEMENTING_AGENCY a "Workshop particulars" annexure, exactly as it already reduces the
        # cluster and survey background to Annexures B and C) has to ship with that pin. The
        # handset's `ReportScreen.kt` caps the same list with the same `infoRows.take(10)`, so a
        # server-only change here would make one workshop's report differ between the two surfaces
        # — the divergence this whole port exists to end.
        #
        # So the fix is the SENTENCE, which is what was actually missing. `build_report` turns this
        # into a warning beside the download, exactly as it already does for a designer's own
        # section that the template's capture tier left out: an absent feature is obvious and a
        # silent one is a bug the designer blames themselves for.
        if len(rows) > COVER_INFO_ROWS and self.template.section_for("WORKSHOP_SETUP") is None:
            self.cover_fields_dropped = tuple(label for label, _v in rows[COVER_INFO_ROWS:])

    def _render_summary_metrics(self, section: TemplateSection) -> None:
        """Headline counts, derived from the records unless stage 18 states otherwise.

        The source document asks stage 18 for "no. of designs; no. of prototypes". Deriving them
        means the report and the data can never disagree — a hand-typed count is a second source
        of truth that goes stale the moment one more sketch is added — so derivation is the
        default and the stage's OVERRIDE, when a designer filled one in, is the exception the
        stage exists to allow. See ``_output_count``: whichever it is, the whole document says
        the same number, and the designer's reason is printed under it as the metric's unit line
        rather than forty pages away beside a raw field label.
        """
        metrics: list[tuple[str, str, str]] = []
        counts = (
            ("Artisans", "WORKSHOP_PLAN_PARTICIPANTS_OPENING", "participant", ""),
            ("Sketches", "SKETCH_DEVELOPMENT", "sketch", "designsCountOverride"),
            ("Prototypes", "PROTOTYPE_DEVELOPMENT", "prototype", "prototypesCountOverride"),
            ("Final products", "FINAL_PROTOTYPE_DOCUMENTATION", "finalProduct", ""),
        )
        reason = self._count_override_reason()
        for label, stage_key, entity_key, override_key in counts:
            n = self._output_count(stage_key, entity_key, override_key)
            if n:
                metrics.append((label, str(n), ""))
        if not metrics:
            return
        if section.heading:
            self.doc.heading(section.heading, 1, numbered=self.template.number_headings)
        self.doc.add(MetricRowBlock(metrics=tuple(metrics[:4])))
        # UNDER THE ROW, not in the metric's unit slot: that slot is drawn inline after the big
        # number at 9.5 pt and a sentence there reads as a unit of measurement. A note directly
        # beneath is where an officer looks for the qualification on a headline figure.
        if reason:
            self.doc.para(f"Stated counts: {reason}", style=ParaStyle.NOTE)

    def _render_signatures(self, section: TemplateSection) -> None:
        setup = self.data.singleton("WORKSHOP_SETUP")
        closing = self.data.singleton("INSPECTION_CLOSING")
        signatories: list[tuple[str, str]] = []
        designer = clean_text(setup.get("designerName"))
        if designer:
            signatories.append((designer, "Designer"))
        agency = clean_text(setup.get("implementingAgency"))
        if agency:
            signatories.append((agency, "Implementing Agency"))
        officer = clean_text(closing.get("inspectingOfficer"))
        if officer:
            signatories.append((officer, "Inspecting Officer"))
        if not signatories:
            return
        if section.page_break_before:
            self.doc.add(PageBreakBlock())
        self.doc.heading(
            section.heading or "Certification", 1, numbered=self.template.number_headings
        )
        self.doc.para(
            "Certified that the workshop was conducted and the prototypes documented above were "
            "developed during the period stated on the cover of this report."
        )
        self.doc.add(SignatureBlock(signatories=tuple(signatories)))

    def _render_media_annexure(self, section: TemplateSection) -> None:
        """Every photograph in the record, in stage order, as a contact sheet."""
        gathered: list[tuple[ImageRef, str]] = []
        for spec in stages():
            for entity in spec.entities:
                if entity.cardinality is Cardinality.SINGLETON:
                    sources = [self.data.singleton(spec.key)]
                else:
                    sources = self.data.rows(spec.key, entity.key)
                for row in sources:
                    for ref, caption in self._images(entity, row):
                        gathered.append((ref, caption or spec.title))
        if not gathered:
            return
        if section.page_break_before:
            self.doc.add(PageBreakBlock())
        self.doc.heading(
            section.heading or "Photographic record", 1, numbered=self.template.number_headings
        )
        self.doc.add(ImageGridBlock(images=tuple(gathered), columns=3))

    def ref_resolves(self, value: Any) -> bool:
        """Whether a REF's stored id still points at a record this report can name.

        THE SCORER AND THE RENDERER MUST AGREE. Without this, the annexure read "13. Prototype
        Development | 144/144 | 100% | Complete" while eighteen pages earlier the same document
        printed "Prototype | Not recorded." thirty-six times, for the very field it had counted
        as filled — the renderer blanks an id whose row was deleted and the scorer only checked
        that the string was non-empty. One submitted document, two answers about one field.

        It goes through ``_ref_label``, which is cached, so scoring a stage costs no more lookups
        than printing it already did.
        """
        return bool(self._ref_label(value))

    def _render_completeness(self, section: TemplateSection) -> None:
        """What the record does and does not contain — the stage 20 completeness check."""
        rows: list[tuple[Any, ...]] = []
        for spec in stages():
            # THE DESIGNER'S OWN REQUIRED FIELDS ARE COUNTED HERE TOO, and they have to be. This
            # table and the readiness screen both read `stage_completeness`; if the annexure scored
            # only the registry fields, a workshop whose stage 13 carries three unanswered custom
            # requirements would print "100% | Complete" against a screen that says three things are
            # outstanding — one document, two arithmetics, which is the exact defect the
            # `ref_resolves` argument beside this one was added to end.
            custom_fields, custom_values = custom_scoring(self.data, spec.key)
            score = stage_completeness(
                spec,
                self.data.singleton(spec.key),
                {e.key: self.data.rows(spec.key, e.key) for e in spec.collections},
                ref_resolves=self.ref_resolves,
                custom_fields=custom_fields,
                custom_values=custom_values,
            )
            rows.append(
                (
                    runs_of(f"{spec.number}. {spec.title}"),
                    runs_of(f"{score.required_filled}/{score.required_total}"),
                    runs_of(f"{score.percent}%"),
                    runs_of("Complete" if score.is_complete else ", ".join(score.missing[:3])),
                )
            )
        if not rows:
            return
        self.doc.heading(
            section.heading or "Data completeness", 1, numbered=self.template.number_headings
        )
        self.doc.add(
            TableBlock(
                columns=(
                    TableColumn("Stage", 40.0),
                    TableColumn("Required fields", 15.0, numeric=True),
                    TableColumn("Complete", 12.0, numeric=True),
                    TableColumn("Outstanding", 33.0),
                ),
                rows=tuple(rows),
            )
        )

    # -- entry point ---------------------------------------------------------------------

    def build(self) -> ReportDocument:
        for section in self.template.sections:
            if section.special is SpecialSection.COVER:
                self._render_cover(section)
            elif section.special is SpecialSection.TOC:
                self.doc.add(TocBlock(depth=3))
            elif section.special is SpecialSection.SUMMARY_METRICS:
                self._render_summary_metrics(section)
            elif section.special is SpecialSection.SIGNATURES:
                self._render_signatures(section)
            elif section.special is SpecialSection.ANNEXURE_MEDIA:
                self._render_media_annexure(section)
            elif section.special is SpecialSection.ANNEXURE_TRANSCRIPTS:
                # WHAT THIS ENDS. Everything else about this annexure has been finished and tested
                # for months: `workshop_transcripts` enqueues the AUDIO, the media queue writes
                # `MediaFile.transcriptText`, `attach_report_transcripts` loads the items onto
                # `data` under the `includeTranscripts` toggle and raises the "still being
                # transcribed" warnings, and `report_annexures` turns them into blocks. Only this
                # branch was missing, so `append_transcript_annexure` was a definition with no call
                # site and EVERY report ever generated dropped the annexure in silence — while the
                # handset's export screen told the designer, in three separate places
                # (`ReportSettings.UNSUPPORTED_SECTIONS`, `STAGE_20_SETTINGS` and the cover's
                # provenance line), that "the office's copy of this report will carry them". It did
                # not. A designer chased a document that no branch of this codebase produced.
                #
                # ONE CALL AND NOTHING ELSE. The heading wording, the index table, the provenance
                # line and the truncation note stay `report_annexures`', so this branch cannot grow
                # a second opinion about any of them. With no transcripts attached — the toggle
                # off, or nothing transcribed yet — it appends nothing at all, not even the page
                # break, so every existing template still renders byte-for-byte as it did.
                append_transcript_annexure(
                    self.doc,
                    transcripts_of(self.data),
                    heading=section.heading,
                    numbered=self.template.number_headings,
                    page_break_before=section.page_break_before,
                )
            elif section.special is SpecialSection.ANNEXURE_QUESTIONNAIRES:
                # THE ONLY PLACE QUESTIONNAIRE DATA ENTERS A REPORT. Before this branch existed it
                # entered by no path at all: `questionnaire` appeared nowhere in this module or in
                # report_templates, and no REF field, no hydration mapping and no template section
                # reached `QuestionnaireFormAnswer` — so the report described a survey (stages 7 and
                # 8 are about nothing else) whose recorded answers sat in a table it never opened.
                #
                # ONE CALL AND NOTHING ELSE, exactly as the transcript branch above. The heading
                # wording, the index table, the provenance lines and the truncation notes stay
                # `report_questionnaires`', so this branch cannot grow a second opinion about any of
                # them. With nothing attached — no questionnaire on this workshop, or none of them
                # answered — it appends nothing at all, not even the page break, so every existing
                # template still renders byte-for-byte as it did.
                append_questionnaire_annexure(
                    self.doc,
                    questionnaires_of(self.data),
                    heading=section.heading,
                    numbered=self.template.number_headings,
                    page_break_before=section.page_break_before,
                )
            elif section.special is SpecialSection.ANNEXURE_AI_LAYERS:
                # THE ONE PLACE MODEL PROSE MAY ENTER A GENERATED DOCUMENT. Plan §3 rule 4: the
                # report prints the ACCEPTED layer and names it as such, because an AI-cleaned
                # passage in a government document must be identifiable as one.
                #
                # ONE CALL AND NOTHING ELSE, exactly as the two annexure branches above. The
                # heading wording, the index table, the provenance line and the truncation note
                # stay `report_ai_layers`', so this branch cannot grow a second opinion about any
                # of them — and in particular cannot grow its own idea of what "accepted" means.
                # That definition lives in `ai_layers.accepted_layers` and is checked again inside
                # `append_ai_layer_annexure`; a third opinion here is how a layer nobody signed for
                # would reach a ministry officer.
                #
                # With nothing attached — nobody accepted anything, or the report was not asked for
                # the annexure — it appends nothing at all, not even the page break. No template in
                # `TEMPLATES` carries this section, so every existing template renders exactly as it
                # did before this branch existed; it is reached only when `apply_report_settings`
                # spliced the section in because a caller passed `include_ai_layers=True`.
                append_ai_layer_annexure(
                    self.doc,
                    ai_layers_of(self.data),
                    heading=section.heading,
                    numbered=self.template.number_headings,
                    page_break_before=section.page_break_before,
                )
            elif section.special is SpecialSection.CUSTOM_SECTION:
                # THE ONLY PLACE A DESIGNER'S OWN QUESTIONS ENTER A REPORT. Before this branch
                # existed they entered by no path at all: the 22 stages are Python literals, so a
                # question a designer added in the field was either typed into a free-text notes box
                # where nothing could count it, or not recorded.
                #
                # ONE CALL AND NOTHING ELSE, exactly as the three annexure branches above. The
                # heading, the "Not recorded." rule for an unanswered required question and the
                # marker on a question that was reworded after it had been answered all stay
                # `report_custom_sections`', so this branch cannot grow a second opinion about any
                # of them — and in particular cannot grow its own idea of WHERE a section prints.
                # That decision belongs to `apply_report_settings`, which is the single arbiter of
                # the running order and was made one because three call sites used to disagree.
                #
                # With the named section not attached — a definition that changed between the two
                # loads, or a section retired while the report was being generated — it appends
                # nothing at all, not even the page break. No template in `TEMPLATES` carries this
                # section, so every existing template renders exactly as it did before this branch
                # existed.
                #
                # THE TIER CAP IS PASSED FOR THE SAME REASON `numbered` IS: this branch must not
                # grow a second opinion, and the template's `max_tier` is a decision the template
                # already made for every registry field through `_visible`. It did not reach a
                # designer's own questions, and the gap was visible in one document: COMPACT_SUMMARY
                # describes itself as "Basic-tier fields only, one photograph per prototype" and is
                # the only template in `TEMPLATES` whose `max_tier` is not ADVANCED, so it correctly
                # suppressed every Standard and Advanced REGISTRY field and then printed the
                # designer's Standard-tier answers underneath in full. One report, two rules, one
                # declared attribute.
                #
                # `.rank` and not the `Tier` itself, because `report_custom_sections` is pure and
                # transliterable into the phone's renderer and must not import the registry — see
                # `_TIER_RANK` there for the argument and for the test that pins the two ladders
                # together.
                append_custom_section(
                    self.doc,
                    custom_section_of(self.data, section.custom_key),
                    heading=section.heading,
                    numbered=self.template.number_headings,
                    page_break_before=section.page_break_before,
                    max_tier_rank=self.template.max_tier.rank,
                )
            elif section.special is SpecialSection.COMPLETENESS:
                self._render_completeness(section)
            elif section.special is SpecialSection.MAP:
                self._render_map(section)
            elif section.special is SpecialSection.CHART:
                self._render_charts(section)
            elif section.special is SpecialSection.ACKNOWLEDGEMENT:
                text = self.data.value("INTRODUCTORY_ADMIN_DOCUMENTATION", "acknowledgement")
                if clean_text(text).strip():
                    self.doc.heading(
                        section.heading or "Acknowledgement",
                        1,
                        numbered=self.template.number_headings,
                    )
                    self.doc.para(text)
            elif section.stage_key:
                spec = self._stages.get(section.stage_key)
                if spec is not None:
                    self._render_stage(spec, section)
        return self.doc.build()


def build_report(
    data: WorkshopData,
    template_id: str,
    resolve_media: MediaResolver,
    *,
    meta: ReportMeta,
    theme: ReportTheme | None = None,
    template: ReportTemplate | None = None,
) -> tuple[ReportDocument, list[str]]:
    """Build the report document for one workshop under one template.

    Returns the document and any warnings — a substituted template, a stage whose required fields
    are unfilled, REGISTRY FIELDS AND COVER FIELDS THIS TEMPLATE LEFT OUT, PHOTOGRAPHS A TEMPLATE'S
    CAP KEPT OUT, ATTACHED FILES THE REPORT NAMES AND DOES NOT CONTAIN, and a designer's own section
    this template's capture tier left out — which the caller shows beside the download rather than
    writing into the file. A warning belongs to the act of generating, not to the document: the
    officer who opens the .docx next month should not find a note about what was missing on the day.

    ALL BUT THE FIRST ARE HERE AND NOT IN THE LOADER because this is the only place that knows both
    halves: the data attached to ``data`` and the SHAPED ``template`` that decided which of it could
    print. See the blocks that raise them, at the end of this function.

    WHAT THEY ALL SHARE, and it is the rule for adding another: each names a loss the designer
    CANNOT SEE from the picker. A template printing three of the twenty-two stages is a decision
    they made and can read in the template's own description; a Basic-tier cap silently dropping
    every Standard answer they typed, a ten-row cover table silently dropping the eleventh field, a
    photograph cap silently shortening a gallery, and a .docx that says "1 document attached" about
    a file it does not contain, are not. Warn about the invisible ones only — a warning that fires
    on every report is one nobody reads, including on the report where it mattered.

    ``theme`` overrides the template's palette for this one document, exactly as ``meta``
    overrides its page size and running furniture, and for the same reason: a designer trying
    three accent colours before submitting should not have to save stage 20 three times. It is
    optional and defaults to the template's own, so nothing that does not ask for a colour gets
    one. Build it with ``report_theme.theme_from_accent`` and never by hand — a ReportTheme
    assembled field by field is how an unreadable table header reaches a ministry.

    ``template`` is the same override one level up: the ALREADY-SHAPED template, as
    ``report_templates.apply_report_settings`` returned it once the designer's stage-20 answers
    were folded in. It is passed rather than re-derived here because this module must not read
    stage 20 — it is handed a ``WorkshopData`` and knows nothing about requests or saved
    settings — and because the preview and the download have to be shaped identically or the
    designer approves one document and submits a different one. ``template_id`` is still required
    and still names the template for the substitution warning below.
    """
    if template is None:
        template = get_template(template_id)
    warnings: list[str] = []
    if template.id != template_id and template_id:
        warnings.append(
            f"Template {template_id!r} is no longer available; the report was generated with "
            f"{template.name!r} instead."
        )

    builder = ReportBuilder(data, template, resolve_media, meta=meta, theme=theme)
    document = builder.build()

    for spec in stages():
        if not template.section_for(spec.key):
            continue
        custom_fields, custom_values = custom_scoring(data, spec.key)
        score = stage_completeness(
            spec,
            data.singleton(spec.key),
            {e.key: data.rows(spec.key, e.key) for e in spec.collections},
            # The same two arguments the annexure passes, for the same reason: this warning and that
            # table must not be able to disagree about one stage of one workshop.
            custom_fields=custom_fields,
            custom_values=custom_values,
            # The BUILDER's resolver, after the document is built, so its label cache is warm and
            # so this warning says exactly what the completeness annexure inside the document
            # says. A stage whose required REF points at a deleted row now reports "1 required
            # field(s) not recorded" the way stages 11, 12, 15, 19 and 22 already do, instead of
            # being the one stage that is silently 100% complete and prints "Not recorded."
            # thirty-six times.
            ref_resolves=builder.ref_resolves,
        )
        if not score.is_complete and score.missing:
            warnings.append(
                f"Stage {spec.number} ({spec.title}): {len(score.missing)} required field(s) "
                f"not recorded — {', '.join(score.missing[:4])}"
                + ("…" if len(score.missing) > 4 else "")
            )

    # ── the designer's own sections THIS TEMPLATE could not print ──────────────────────────────
    #
    # THE OTHER HALF OF A WARNING WHOSE FIRST HALF LIVES IN THE LOADER, and the reason this is here
    # rather than beside it. `design_workshops.attach_report_custom_sections` already names the
    # sections that print nothing because nothing was recorded in them — it asks the renderer's own
    # `has_content`, which is `section_prints` with every tier admitted, and that is the only cap it
    # can honestly ask: it runs BEFORE `apply_report_settings`, which is what splices these sections
    # in and cannot do so until it has been handed the definition that load produces. No template
    # ever reaches it.
    #
    # THE GAP THAT LEFT, WHICH IS THIS BLOCK'S WHOLE REASON. Two fixes landed on this render in the
    # same afternoon: one gave `append_custom_section` the template's tier cap, so a designer's
    # Standard-tier question stopped printing under COMPACT_SUMMARY ("Basic-tier fields only" — the
    # one non-ADVANCED `max_tier` in `TEMPLATES`); the other re-pointed that loader warning at
    # `has_content`, because it had been firing for exactly the sections the renderer DOES print.
    # Each was right alone. Together they opened the quiet direction: a section whose every answered
    # question is above the cap is skipped by the renderer and is invisible to a cap-blind warning,
    # so the designer submits a .docx with their own block silently missing and is told nothing.
    # The loud direction — warned about but present — is what the second fix closed. Both are the
    # same defect: the document and its own warning list telling a ministry two stories about one
    # section.
    #
    # ASKED THROUGH `sections_hidden_by_tier`, WHICH IS `section_prints` TWICE, so this cannot drift
    # from what the branch above actually did. Do not "simplify" it into a tier comparison here: a
    # second copy of "did this section print" is precisely how these two came apart. The two
    # warnings are disjoint by construction (`fields_at` is monotone in the cap, so a section that
    # prints nothing at ALL_TIERS prints nothing at any cap) — a section is named once or not at
    # all, never twice in one download.
    #
    # WALKED OFF `template.sections` AND NOT OFF THE ATTACHED TUPLE, because it must report what the
    # `CUSTOM_SECTION` branch above was asked to draw and didn't. A caller that passes no shaped
    # template — every test that builds a bare `TEMPLATES` entry, and the 38 pinned
    # `apply_report_settings` cases — carries no CUSTOM_SECTION at all and gets an empty list here,
    # so no existing report gains a warning it did not have.
    # ── the REGISTRY fields this template's capture tier left out ──────────────────────────────
    #
    # THE SAME WARNING AS THE ONE BELOW, FOR THE OTHER NINETY-NINE PERCENT OF THE FIELDS. The block
    # under this one has told a designer for a while that their OWN section was above the
    # template's tier; nothing told them that the artisan's village, phone and specialisation the
    # picker copied in off the artisan record — Standard, every one of them — were dropped from a
    # Compact summary too. One report, two rules, and the designer only ever heard about one.
    #
    # ASKED OF THE BUILDER AFTER THE RENDER, exactly as ``ref_resolves`` is and for the same
    # reason: ``_visible`` is the one decision that actually kept the fields out, and a tier
    # comparison recomputed here would be a second copy of it — which is how the two custom-section
    # warnings came apart in the first place. Five of the six templates admit every tier, so
    # ``fields_hidden_by_tier`` returns an empty list for them and no existing report gains a
    # warning it did not have.
    lost_by_tier = builder.fields_hidden_by_tier()
    if lost_by_tier:
        total = sum(len(labels) for _spec, labels in lost_by_tier)
        # NAMED BY STAGE AND NOT BY FIELD. Seventeen field labels in a header is a wall a designer
        # scrolls past; "stages 3, 6 and 13" is where they go and look. The first four, because the
        # sentence has to stay a sentence.
        where = ", ".join(f"stage {spec.number}" for spec, _labels in lost_by_tier[:4])
        warnings.append(
            f"{total} field(s) recorded in this workshop are above {template.name}'s capture tier "
            f"({template.max_tier.value.title()}) and are not in this file — {where}"
            + ("…" if len(lost_by_tier) > 4 else "")
            + ". Generate the report with a template that captures every tier to include them."
        )

    # ── the photographs a template's photograph cap kept out ──────────────────────────────
    #
    # RULE 10, AND IT PASSES THE TEST THIS DOCSTRING SETS FOR A NEW WARNING: a loss the designer
    # cannot see from the picker. COMPACT_SUMMARY's description says "one photograph per prototype";
    # it does not say six, no other template caps anything, stage 20's photograph settings cannot
    # reach the number, and until this block a capped gallery simply came out shorter with nothing
    # anywhere — in the file, beside the download, or on the handset — saying that it had.
    #
    # WORTH THE LINE BECAUSE OF WHAT THE CAP USED TO TAKE. Applied across a whole row it truncated
    # an insertion-ordered walk, so the galleries a stage declared LAST lost every photograph they
    # held: stage 4's two motif galleries vanished entirely behind the cluster's own photographs,
    # which is not a shorter report but a different claim about the craft. ``_image_groups`` now
    # caps each gallery separately; this says what that still costs.
    #
    # ASKED OF THE BUILDER AFTER THE RENDER, exactly as ``fields_hidden_by_tier`` is and for the
    # same reason: the placement paths are what dropped the pictures. Five of the six templates set
    # no cap at all, so this is empty for them and no existing report gains a warning it did not
    # have.
    over_cap = builder.photographs_over_cap()
    if over_cap:
        total = sum(count for _spec, count in over_cap)
        where = ", ".join(f"stage {spec.number}" for spec, _count in over_cap[:4])
        warnings.append(
            f"{total} photograph(s) recorded in this workshop did not fit {template.name}'s "
            f"photograph cap and are not in this file — {where}"
            + ("…" if len(over_cap) > 4 else "")
            + ". Generate the report with a template that prints every photograph to include them."
        )

    # ── the attached files this report names and does not contain ────────────────────────
    #
    # THE SECOND HALF OF THE FIX THAT MADE AN ATTACHMENT VISIBLE AT ALL. ``format_value`` prints "1
    # document attached" under the field's own label, and that ended a .docx which did not mention
    # that the ministry's own sanction order had been attached. What it did not end is the reading
    # that line invites in a document submitted to a ministry, which is *a document is attached to
    # this report*: neither writer can draw a PDF, a recording or a video, ``_images`` places IMAGE
    # and IMAGE_LIST only, and ``ANNEXURE_MEDIA`` gathers through ``_images`` — so the bytes are in
    # the workshop record and nowhere else.
    #
    # THIS WARNING IS THE AUTHORITY ON THAT, ALONG WITH ``report_annexures`` AND
    # ``report_templates``, AND THE REGISTRY IS NOT. These lines used to finish by naming the
    # registry's ``designerCv`` help text as promising an annexure no branch here produces. That
    # was true when written and is not now (the help text says the report names the file rather
    # than carrying it), but the reason to stop naming it is not that it was fixed: what a report
    # CONTAINS is unobservable from any client, so a help string can promise an annexure for as
    # long as nobody reads these three modules, and citing whichever help string was wrong last
    # sends the next reader to the surface that cannot answer the question. Cite the shape, not the
    # instance — see ``ReportBuilder.attachments_named_but_not_carried``, which carries it in full.
    #
    # BESIDE THE DOWNLOAD AND NOT IN THE FILE, under the rule this whole list is under. The document
    # is honest about what it holds; it is the designer, on the day, who has to send the files with
    # it, and it is the designer this sentence is addressed to. See
    # ``ReportBuilder.attachments_named_but_not_carried`` for why this is a sentence rather than an
    # embedded object or a new annexure, neither of which is a builder change.
    attachments = builder.attachments_named_but_not_carried()
    if attachments:
        total = sum(count for _spec, count in attachments)
        where = ", ".join(f"stage {spec.number}" for spec, _count in attachments[:4])
        warnings.append(
            f"{total} attached file(s) are named in this report but the files themselves are not "
            f"inside it — {where}"
            + ("…" if len(attachments) > 4 else "")
            + ". A report file cannot carry a document, a recording or a video; send them "
            "alongside it."
        )

    # ── the cover fields that fell off the ten-row cover table ─────────────────────────────────
    #
    # SEE THE BLOCK AT THE END OF ``_render_cover``, which decides this and says at length why the
    # answer is a sentence rather than a second table. In short: the cap is editorial and right,
    # four of the six templates print the overflow in their stage-1 section anyway, and for the two
    # that do not the values are in the record and nowhere in the document. Only those two ever
    # reach this branch, so no existing report gains a warning it did not have.
    if builder.cover_fields_dropped:
        names = ", ".join(builder.cover_fields_dropped[:4])
        warnings.append(
            f"{len(builder.cover_fields_dropped)} cover field(s) did not fit the cover table and "
            f"the {template.name} template prints no workshop-setup section to carry them, so "
            f"they are not in this file: {names}"
            + ("…" if len(builder.cover_fields_dropped) > 4 else "")
            + "."
        )

    hidden = sections_hidden_by_tier(
        [
            custom_section_of(data, section.custom_key)
            for section in template.sections
            if section.special is SpecialSection.CUSTOM_SECTION
        ],
        template.max_tier.rank,
    )
    if hidden:
        # NAMED, AND WITH THE REASON, for the reason the loader's twin gives: a block the designer
        # added themselves and then finds missing from a sixty-page document reads as a bug in the
        # app. Saying which one, and that the TEMPLATE left it out rather than the feature failing,
        # is the difference between "the export is broken" and "generate this one as
        # DETAILED_TECHNICAL" — which is a thing the designer can actually do about it.
        titles = sorted(item.title for item in hidden)
        warnings.append(
            f"{len(hidden)} of this workshop's own section(s) ask only questions above "
            f"{template.name}'s capture tier ({template.max_tier.value.title()}) and are not in "
            f"this file: " + ", ".join(titles[:4]) + ("…" if len(titles) > 4 else "")
        )

    return document, warnings
