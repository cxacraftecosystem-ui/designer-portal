"""The field registry: one declaration of the 22 workshop stages, read by everything.

The source requirements document defines a Design & Prototype Workshop as 22 stages, each
capturing fields at three tiers — *Basic* (minimum required), *Standard* (desirable for most
workshops) and *Advanced* (where facilities and expertise permit). That is on the order of two
and a half thousand fields. Three surfaces have to agree about every one of them: the capture
form, the validator that decides whether a stage is complete, and the report that prints it.

Writing those out three times, in three languages, would guarantee drift — and writing them as
two and a half thousand database columns would make every schema change a migration. So the
fields are *data*, declared once here:

    STAGES  ->  a form (web + Android)
            ->  validation and the completeness gate
            ->  the report (labels become table headers and key-value captions)
            ->  the research export (stable keys and units)

The storage side follows from that. Each stage's answers live in a ``DwStageEntry`` row whose
``data`` is a JSON object keyed by ``FieldSpec.key``; the handful of fields a researcher will
actually filter and sort on are *also* denormalised onto typed columns of ``DesignWorkshop``
(see ``promoted_columns``). That hybrid is deliberate: a pure-JSON store cannot answer "every
workshop on Ikat in Odisha in 2026" without a table scan, and a pure-column store cannot absorb
a new Standard-tier field without a migration in the middle of a workshop season.

Three rules keep the registry honest, and :func:`validate_registry` (run by the tests and at
import time in debug) enforces all three:

1. **Keys are permanent.** A field key is what the research data is indexed by, what the phone
   wrote into a draft two weeks ago, and what a saved report template refers to. Renaming one
   silently orphans data. Fields are deprecated, never renamed — see :attr:`FieldSpec.replaced_by`.
2. **Every enum has a canonical option list.** Six stages ask for a material and six independent
   authors will spell it six ways; a shared :data:`ENUMS` table is what makes "cotton" the same
   answer in stage 5 and stage 17.
3. **Only Basic-tier fields may be required.** The tiers exist so a workshop held in a village
   without power can still produce a complete report. A required Standard field would make the
   completeness gate unsatisfiable exactly where the app is most needed.
"""

from __future__ import annotations

import math
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from enum import Enum
from typing import Any

from app.services.report_model import clean_text
from app.services.report_theme import ACCENT_PRESET_ENUM, FONT_PRESET_ENUM


class Tier(str, Enum):
    """Which capture tier a field belongs to, straight from the source matrix."""

    BASIC = "BASIC"
    STANDARD = "STANDARD"
    ADVANCED = "ADVANCED"

    @property
    def rank(self) -> int:
        return {"BASIC": 0, "STANDARD": 1, "ADVANCED": 2}[self.value]


class FieldType(str, Enum):
    """How a value is captured, validated, stored and printed.

    The set is deliberately small. Every extra type costs a branch in the web form, a Composable
    on Android, a validator here and a renderer in the report builder, so a new one has to earn
    its place four times over.
    """

    TEXT = "TEXT"                # single line
    LONG_TEXT = "LONG_TEXT"      # textarea; becomes report prose
    # A structured document (app/services/rich_text.py), never HTML. Stored as JSON in the
    # stage entry exactly like any other value, and rendered by every surface from the same
    # block list — see that module for why the storage form is not markup.
    RICH_TEXT = "RICH_TEXT"
    INT = "INT"
    DECIMAL = "DECIMAL"
    MONEY = "MONEY"              # INR, stored as a decimal string
    PERCENT = "PERCENT"
    DATE = "DATE"                # ISO-8601 date, no time
    TIME = "TIME"                # HH:MM
    BOOL = "BOOL"
    ENUM = "ENUM"                # one option from a canonical list
    MULTI_ENUM = "MULTI_ENUM"    # several options from a canonical list
    TAGS = "TAGS"                # free-form list; no canonical list exists
    IMAGE = "IMAGE"              # one media id
    IMAGE_LIST = "IMAGE_LIST"    # gallery
    FILE = "FILE"                # a sanction order, a questionnaire PDF
    AUDIO = "AUDIO"
    VIDEO = "VIDEO"
    GEO = "GEO"                  # {lat, lon, accuracy}
    REF = "REF"                  # a foreign id: an Artisan, a Craft, another stage entry
    URL = "URL"
    PHONE = "PHONE"
    EMAIL = "EMAIL"

    @property
    def is_media(self) -> bool:
        return self in {FieldType.IMAGE, FieldType.IMAGE_LIST, FieldType.FILE,
                        FieldType.AUDIO, FieldType.VIDEO}

    @property
    def is_numeric(self) -> bool:
        return self in {FieldType.INT, FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT}

    @property
    def is_multi(self) -> bool:
        """Whether the stored value is a list."""
        return self in {FieldType.MULTI_ENUM, FieldType.TAGS, FieldType.IMAGE_LIST}


class ReportRole(str, Enum):
    """Where a field lands in the generated report.

    A field with no role is captured and retained but never printed — which is a legitimate
    outcome for internal bookkeeping, and much better than the alternative of printing every
    field and burying the narrative.
    """

    NARRATIVE = "NARRATIVE"          # a prose paragraph
    KEY_VALUE = "KEY_VALUE"          # a label/value pair
    TABLE_COLUMN = "TABLE_COLUMN"    # a column when the entity is a collection
    CAPTION = "CAPTION"              # the caption of the media field beside it
    GALLERY = "GALLERY"              # a photo grid
    COVER_FIELD = "COVER_FIELD"      # a row of the cover-page table
    METRIC = "METRIC"                # a headline number
    BULLETS = "BULLETS"              # a bulleted list
    HIDDEN = "HIDDEN"


class Cardinality(str, Enum):
    SINGLETON = "SINGLETON"      # one per workshop: the stage's own fields
    COLLECTION = "COLLECTION"    # many per workshop: sketches, prototypes, respondents


# How wide a REF field's picker casts its net. Declared as plain strings rather than an Enum
# because the value crosses the wire to three clients and back, and a token is easier to keep
# identical in Kotlin and TypeScript than an enum member is.
#
# WORKSHOP means "only records already linked to the Workshop this design workshop belongs to".
# That is what turns the artisan picker from a scroll through a cluster's several hundred
# documented artisans into the dozen who are actually in the room — which is the difference
# between a dropdown a designer uses and one they give up on and retype around.
#
# ALL means the whole table. Stage 3 is deliberately ALL: it is where the roster is BUILT, so
# narrowing it to the roster would be circular and a designer could never add the artisan who
# turned up on day two.
REF_SCOPE_WORKSHOP = "WORKSHOP"
REF_SCOPE_ALL = "ALL"
REF_SCOPES = frozenset({REF_SCOPE_WORKSHOP, REF_SCOPE_ALL})


@dataclass(frozen=True, slots=True)
class FieldSpec:
    key: str
    label: str
    type: FieldType
    tier: Tier = Tier.STANDARD
    required: bool = False
    help: str = ""
    unit: str = ""
    enum: str = ""               # name of the shared list in ENUMS, for ENUM/MULTI_ENUM
    ref_model: str = ""          # for REF: "Artisan", "Craft", or a Dw entity name
    # THE TWO HALVES OF A CASCADING PICKER, and the reason a designer stops retyping records
    # that are already in the database.
    #
    # ``ref_filter_by`` names another field OF THE SAME ENTITY whose chosen value narrows this
    # one. ``existingProduct.productRef`` filters by ``artisanRef``, so once the artisan is
    # picked the product dropdown holds that artisan's products and nothing else. Without it the
    # product picker on a cluster with three hundred documented products is a list nobody reads
    # to the end, and the designer types the product name in by hand — which is precisely the
    # behaviour that leaves the report with a name and no join key, and the research data with
    # no way to connect a workshop's baseline to the product record it was measured from.
    #
    # ``ref_scope`` is one of :data:`REF_SCOPES`. See the note there for why WORKSHOP and ALL
    # are both needed and which stages want which.
    ref_filter_by: str = ""
    ref_scope: str = ""
    max_length: int = 0
    #: For a multi-valued type (IMAGE_LIST, TAGS, MULTI_ENUM): how many entries it may hold.
    #: 0 means ``DEFAULT_MAX_ITEMS``, which is what every field in the registry uses today —
    #: see that constant for why an unbounded array is a permanent write amplified on every
    #: later read of the stage. On a scalar field it is inert; ``max_length`` is that field's
    #: bound. On a multi field ``max_length`` bounds ONE ENTRY, not the joined string.
    max_items: int = 0
    min_value: float | None = None
    max_value: float | None = None
    report_role: ReportRole = ReportRole.KEY_VALUE
    column_width_pct: float = 0.0   # hint for TABLE_COLUMN; 0 means share the remainder
    caption_for: str = ""        # this field captions that media field
    #: HOW THIS FIELD COMPUTES ITSELF when the designer leaves it blank, and the field keys it
    #: computes FROM. Declared here rather than implemented in a client because several fields
    #: already PROMISED it in their help text — "Leave blank to derive it from the start and end
    #: dates" — and nothing anywhere kept the promise: the value stayed empty, in a report, with
    #: the form having said it would be filled in.
    #:
    #: One declaration, read by the web form, the Android form and the server's own save path, so
    #: the number a designer watches appear is the number that is stored. See ``derive_value``.
    derived_kind: str = ""       # DAYS_BETWEEN | PRODUCT | SUM
    derived_from: tuple[str, ...] = ()
    phase_note: str = ""         # a reviewer comment from the source document
    deprecated: bool = False
    replaced_by: str = ""

    @property
    def is_free_text(self) -> bool:
        return self.type in {FieldType.TEXT, FieldType.LONG_TEXT, FieldType.RICH_TEXT}

    @property
    def is_rich_text(self) -> bool:
        return self.type is FieldType.RICH_TEXT


@dataclass(frozen=True, slots=True)
class EntitySpec:
    """One record shape within a stage.

    A stage's SINGLETON entity holds its one-per-workshop answers. Its COLLECTION entities are
    the repeating records — every sketch, every prototype, every survey respondent — each stored
    as its own row so they can be counted, filtered and joined rather than buried in an array.
    """

    key: str                     # stable storage key, e.g. "sketch"
    name: str                    # PascalCase display/model name, e.g. "DwSketch"
    cardinality: Cardinality
    title: str
    fields: tuple[FieldSpec, ...]
    parent: str = ""             # parent entity key, for nesting (iteration under prototype)
    description: str = ""
    label_field: str = ""        # which field titles a row in a list; defaults to the first text

    @property
    def required_fields(self) -> tuple[FieldSpec, ...]:
        return tuple(f for f in self.fields if f.required and not f.deprecated)

    def field(self, key: str) -> FieldSpec | None:
        return next((f for f in self.fields if f.key == key), None)


@dataclass(frozen=True, slots=True)
class StageSpec:
    number: int                  # 1-22, the source document's own numbering
    key: str                     # UPPER_SNAKE, stable
    title: str
    purpose: str
    entities: tuple[EntitySpec, ...]
    notes: str = ""
    optional_stage: bool = False  # the reviewer marked this one as possibly droppable

    @property
    def singleton(self) -> EntitySpec | None:
        return next((e for e in self.entities if e.cardinality is Cardinality.SINGLETON), None)

    @property
    def collections(self) -> tuple[EntitySpec, ...]:
        return tuple(e for e in self.entities if e.cardinality is Cardinality.COLLECTION)

    def entity(self, key: str) -> EntitySpec | None:
        return next((e for e in self.entities if e.key == key), None)


# --------------------------------------------------------------------------------------
# Canonical enumerations
# --------------------------------------------------------------------------------------

# Shared so that "cotton" recorded against a raw material in stage 5 is the same token as the
# one in a stage 17 cost sheet. Values are UPPER_SNAKE; the label shown to a designer and
# printed in the report is the dict value, because a report that says "TIE_AND_DYE" is not a
# report anyone will submit to a ministry.
ENUMS: dict[str, dict[str, str]] = {
    "YES_NO_PARTIAL": {
        "YES": "Yes",
        "NO": "No",
        "PARTIAL": "Partially",
    },
    "MATERIAL_FAMILY": {
        "COTTON": "Cotton",
        "SILK": "Silk",
        "WOOL": "Wool",
        "JUTE": "Jute",
        "LINEN": "Linen",
        "BLEND": "Blended yarn",
        "BAMBOO": "Bamboo / cane",
        "WOOD": "Wood",
        "CLAY": "Clay / terracotta",
        "METAL": "Metal",
        "STONE": "Stone",
        "LEATHER": "Leather",
        "GRASS": "Grass / fibre",
        "PAPER": "Paper",
        "NATURAL_DYE": "Natural dye",
        "CHEMICAL_DYE": "Chemical dye",
        "OTHER": "Other",
    },
    # The material family's opposite number, and the list stage 5 had been missing.
    #
    # `tool.toolType` beside it has been free TEXT since the registry was written, and the eleven
    # rows in this repository that filled the box in hold six different spellings — "Hand tool",
    # "Loom", "Loom accessory", "Hand frame", "Hand-turned warping frame", "Vessel over a
    # firewood hearth" — of which four are two categories under four names. So "how many clusters
    # weave on a pit loom" was unanswerable across two workshops, which is exactly the failure
    # rule 2 in this module's docstring describes.
    #
    # EVERY MEMBER IS TRACEABLE TO SOMETHING THE REPOSITORY ALREADY HOLDS, not to a taxonomy of
    # world crafts invented at this keyboard: FIVE of them generalise the six spellings a designer
    # actually typed — FRAME covers both "Hand frame" and "Hand-turned warping frame", which is the
    # collapse the whole list exists to make — and KILN and MOULD are the two remaining categories
    # `toolType`'s own help text has been asking for without ever offering, it having read, in full,
    # "Hand tool, loom, kiln, mould, and so on." before this change.
    # Nothing is guessed at for a craft this database has never documented — OTHER, plus the free
    # text field that stays beside this one, carries those without pretending to classify them.
    "TOOL_TYPE": {
        "HAND_TOOL": "Hand tool",
        "LOOM": "Loom",
        "LOOM_ACCESSORY": "Loom accessory / attachment",
        "FRAME": "Frame / drum",
        "VESSEL": "Vessel / vat",
        "KILN": "Kiln / furnace / hearth",
        "MOULD": "Mould / die / block",
        "OTHER": "Other",
    },
    # THE SOURCE RECORD'S OWN QUESTION, WHICH IS NOT THIS REGISTRY'S QUESTION.
    #
    # A mirror of the Prisma `ProductType` enum, member for member, and the only list in `ENUMS`
    # that exists to receive a value from outside rather than to be answered by a designer. It was
    # added because `PRODUCT_CATEGORY` below could not honestly receive four of ProductType's six
    # members: a FINISHED_GOOD may be a saree or a bag, and a SAMPLE is a saree that happens not to
    # be for sale, so `_PRODUCT_TYPE_TO_CATEGORY` maps them to nothing. Before this list existed
    # that meant the record's answer was simply lost at the picker.
    #
    # THE TWO LISTS ARE NOT REDUNDANT AND MUST NOT BE MERGED. "What kind of thing is this record?"
    # and "what IS this product?" are different questions with different answers, and a workshop
    # row now carries both: `existingProduct.recordType` from the record, `existingProduct.category`
    # from the designer (hydrated only for the two tokens that genuinely mean the same thing).
    # Merging them would re-create the guess this arrangement exists to avoid.
    #
    # Keep the tokens identical to `prisma/schema.prisma`'s `enum ProductType`. `test_reference_
    # carry.py` reads the schema file and fails if they drift.
    "PRODUCT_TYPE": {
        "FINISHED_GOOD": "Finished good",
        "SAMPLE": "Sample",
        "RAW_MATERIAL": "Raw material",
        "COMPONENT": "Component",
        "PACKAGING": "Packaging",
        "OTHER": "Other",
    },
    # Who made the tool, mirroring Prisma's `MakerType` member for member — the same arrangement
    # and the same rule as `PRODUCT_TYPE` above.
    #
    # NOT the same question as `tool.source` ("Where obtained"), which is why that box keeps its
    # own free text and is NOT filled in from a chosen record: a loom bought second-hand from a
    # cooperative was made by a carpenter, and answering "where obtained: carpenter" would be a
    # plausible wrong sentence in a submitted report. `ToolDocumentation` has no column for where a
    # tool was obtained, and inventing an answer from the nearest column is exactly the failure the
    # translation tables in `design_workshops` are written to refuse.
    "MAKER_TYPE": {
        "ARTISAN": "The artisan themselves",
        "LOCAL_BLACKSMITH": "Local blacksmith",
        "CARPENTER": "Carpenter",
        "WORKSHOP": "Workshop",
        "FACTORY": "Factory",
        "UNKNOWN": "Not known",
        "OTHER": "Other",
    },
    "PRODUCT_CATEGORY": {
        "APPAREL": "Apparel",
        "SAREE": "Saree",
        "DUPATTA_STOLE": "Dupatta / stole",
        "YARDAGE": "Yardage / fabric",
        "HOME_FURNISHING": "Home furnishing",
        "TABLE_LINEN": "Table linen",
        "FLOOR_COVERING": "Floor covering",
        "ACCESSORY": "Accessory",
        "BAG": "Bag",
        "JEWELLERY": "Jewellery",
        "UTILITY": "Utility item",
        "DECORATIVE": "Decorative object",
        "TOY": "Toy",
        "PACKAGING": "Packaging",
        "OTHER": "Other",
    },
    "TRADITION_TYPE": {
        "TRADITIONAL": "Traditional",
        "CONTEMPORARY": "Contemporary",
        "TRANSITIONAL": "Traditional form, contemporary use",
    },
    # Whether a step is one step in the sequence or a BRACKET around several of them, mirroring
    # Prisma's `ProcessStepType` member for member — the same arrangement as `PRODUCT_TYPE` and
    # `MAKER_TYPE` above, and the labels are the ones the record page's own "Add Another Step"
    # popover prints ("Sequential" / "Group of activities") so the two surfaces read the same words.
    #
    # THE DISTINCTION IS LOAD-BEARING FOR A READER COUNTING STEPS, which is why `_step_lines` appends
    # " (group)" to a documented group's line. Until `processStep.stepType` existed, the only place it
    # landed was inside that flattened string on the HYDRATED singleton: a designer who observed
    # three parallel activities bracketed together at the workshop had no box to say so, and "how
    # many clusters group their dyeing activities" was unanswerable across workshops — the same
    # failure written out above `TOOL_TYPE`.
    #
    # A DESIGNER-ANSWERED FIELD AND NOT A CARRY, deliberately: `processStep.processRef` points at a
    # Process, a Process has MANY steps, and hydration cannot choose which source step a row
    # corresponds to. It is the same reason `processStep.name` receives the process's name and not a
    # step's, and it is why no mapping pair exists for this list.
    "PROCESS_STEP_TYPE": {
        "SEQUENTIAL": "Sequential",
        "GROUP": "Group of activities",
    },
    "MARKET_CHANNEL": {
        "LOCAL_HAAT": "Local haat / weekly market",
        "EMPORIUM": "Government emporium",
        "EXHIBITION": "Exhibition / mela",
        "RETAILER": "Retailer",
        "WHOLESALER": "Wholesaler",
        "EXPORTER": "Exporter",
        "ONLINE": "Online marketplace",
        "DIRECT": "Direct to customer",
        "MASTER_WEAVER": "Through master weaver / MCP",
        "COOPERATIVE": "Cooperative society",
        "OTHER": "Other",
    },
    "RESPONDENT_GROUP": {
        "CONSUMER": "Consumer",
        "RETAILER": "Retailer",
        "WHOLESALER": "Wholesaler",
        "EXPORTER": "Exporter",
        "ARTISAN": "Artisan",
        "MASTER_CRAFTSPERSON": "Master craftsperson (MCP)",
        "EMPORIUM_STAFF": "Emporium staff",
        "DESIGNER": "Designer",
        "OFFICIAL": "Government official",
        "OTHER": "Other",
    },
    "REVIEW_DECISION": {
        "SELECTED": "Selected",
        "REJECTED": "Rejected",
        "REVISE": "Revise and resubmit",
        "PENDING": "Pending review",
    },
    "DEMAND_LEVEL": {
        "HIGH": "High",
        "MEDIUM": "Medium",
        "LOW": "Low",
        "SEASONAL": "Seasonal",
        "UNKNOWN": "Not known",
    },
    "SWOT_KIND": {
        "STRENGTH": "Strength",
        "WEAKNESS": "Weakness",
        "OPPORTUNITY": "Opportunity",
        "THREAT": "Threat",
    },
    "ADOPTION_STATUS": {
        "ADOPTED_IN_PRODUCTION": "Adopted, in regular production",
        "ADOPTED_ON_ORDER": "Adopted, made to order",
        "TRIAL": "Under trial",
        "NOT_ADOPTED": "Not adopted",
        "UNKNOWN": "Not known",
    },
    "FOLLOWUP_INTERVAL": {
        "M3": "3 months",
        "M6": "6 months",
        "M12": "12 months",
        "AD_HOC": "Ad hoc",
    },
    "MEDIA_QUALITY_FLAG": {
        "BLUR": "Blurred",
        "LOW_RESOLUTION": "Resolution too low",
        "OVEREXPOSED": "Overexposed",
        "UNDEREXPOSED": "Underexposed",
        "MISSING_VIEW": "A required view is missing",
        "DUPLICATE": "Duplicate of another file",
        "WRONG_SUBJECT": "Wrong subject",
    },
    "RETENTION_CLASS": {
        "HOT": "Hot — in active use",
        "WARM": "Warm — occasional access",
        "COLD": "Cold — archive only",
    },
    "GI_STATUS": {
        "REGISTERED": "GI registered",
        "APPLIED": "GI application filed",
        "NOT_REGISTERED": "Not GI registered",
        "UNKNOWN": "Not known",
    },
    "EXPORT_FORMAT": {
        "DOCX": "Word (.docx)",
        "PDF": "PDF (.pdf)",
    },
    "REPORT_TEMPLATE": {
        "DCH_STANDARD": "DCH standard workshop report",
        "DIC_STANDARD": "DIC standard workshop report",
        "IMPLEMENTING_AGENCY": "Implementing agency format",
        "COMPACT_SUMMARY": "Compact summary",
        "DETAILED_TECHNICAL": "Detailed technical report",
        "PHOTO_CATALOGUE": "Photo catalogue",
    },
    "PAGE_SIZE": {
        "A4": "A4",
        "LETTER": "Letter",
    },
    # Built from :data:`app.services.report_theme.ACCENT_PRESETS` rather than written out here,
    # because the twelve names and the twelve hex values must not be able to disagree: a swatch
    # labelled "Maroon" that stores a token this registry knows and the theme module does not
    # would validate, save, and then generate a report in the default indigo with no error
    # anywhere. One list, one place. The import is safe in this direction — ``report_theme``
    # imports only ``report_model``, which imports nothing of ours.
    "REPORT_ACCENT_PRESET": dict(ACCENT_PRESET_ENUM),
    # Same rule, same file: one typeface list, read by both forms and the validator.
    "REPORT_FONT": dict(FONT_PRESET_ENUM),
    "SEVERITY": {
        "LOW": "Low",
        "MEDIUM": "Medium",
        "HIGH": "High",
    },
    # What an iteration was fighting, from the source document's own three words: it lists stage
    # 14's Standard capture as "material/process/design problem".
    #
    # CLOSED, WITH NO "OTHER", which is a departure from every open-ended list above and is the
    # honest reading of the requirement: these three are not a sample of the categories, they are
    # the categories, and an OTHER member would simply invite the free text back into the one
    # field the whole point was to make countable. A problem that is genuinely none of the three
    # is described in `problemType` beside it, which is still there and still free text.
    "PROBLEM_TYPE": {
        "MATERIAL": "Material",
        "PROCESS": "Process",
        "DESIGN": "Design",
    },
    "QUALITY_RATING": {
        "1": "1 — Poor",
        "2": "2 — Below average",
        "3": "3 — Acceptable",
        "4": "4 — Good",
        "5": "5 — Excellent",
    },
}


def enum_label(enum_name: str, value: str) -> str:
    """The printable label for a stored token, falling back to the token itself.

    Falling back rather than raising is deliberate: a draft written by a phone one release ahead
    of the server can carry a token this build has never heard of, and printing the raw token in
    the report is better than failing the export a designer is waiting on in the field.
    """
    return ENUMS.get(enum_name, {}).get(value, value)


# --------------------------------------------------------------------------------------
# Promoted columns — the hybrid store's queryable half
# --------------------------------------------------------------------------------------

# Fields copied onto typed columns of DesignWorkshop whenever a stage is saved. These are the
# axes a researcher actually filters and sorts on, and the ones a workshop list has to show
# without opening every JSON document. Everything else stays in the stage's JSON.
#
# Keyed by "entityKey.fieldKey", not by field key alone. Field keys are unique only WITHIN an
# entity — `startDate` is legitimately both the workshop's and a prototype's, `designerName` is
# both the workshop's designer and the person credited on a final product — so an unscoped map
# has two possible sources for one column and will silently pick whichever entity is saved last.
#
# Adding an entry here is a migration; removing one is not. Keep the list short on purpose:
# this is an index, not a second copy of the data.
PROMOTED_COLUMNS: dict[str, str] = {
    "workshopSetup.workshopTitle": "title",
    "workshopSetup.schemeName": "scheme",
    "workshopSetup.craftName": "craftName",
    "workshopSetup.clusterName": "clusterName",
    "workshopSetup.state": "state",
    "workshopSetup.district": "district",
    "workshopSetup.venue": "venue",
    "workshopSetup.startDate": "startDate",
    "workshopSetup.endDate": "endDate",
    "workshopSetup.designerName": "designerName",
    "workshopSetup.implementingAgency": "implementingAgency",
    "workshopSetup.sponsor": "sponsor",
    "workshopSetup.workshopCode": "workshopCode",
}


def promoted_values(entity_key: str, data: dict[str, Any]) -> dict[str, Any]:
    """The typed-column updates implied by saving ``entity_key``'s data.

    Returns ``{column name: value}``. An entity that promotes nothing yields an empty dict, so
    the caller can apply this unconditionally on every stage save.
    """
    out: dict[str, Any] = {}
    for path, column in PROMOTED_COLUMNS.items():
        source_entity, _, field_key = path.partition(".")
        if source_entity == entity_key and field_key in data:
            out[column] = data[field_key]
    return out


# --------------------------------------------------------------------------------------
# Reference hydration — which display fields a chosen record copies onto the row
# --------------------------------------------------------------------------------------

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
#
# ── WHY IT LIVES IN THE REGISTRY AND NOT BESIDE `hydrate_entries` ──────────────────────────
#
# It was declared in ``design_workshops``, next to the code that applies it, which put it out of
# reach of two things that need it. ``validate_registry`` below now refuses a mapping whose
# target is not a field of the entity — a rename that used to hydrate NOTHING, silently, because
# ``hydrate_entries`` skips a target it cannot resolve. And ``field_to_dict`` now PUBLISHES the
# mapping to the clients, so the picker on a handset fills the row in by the server's rule
# instead of by matching key names. Matching names is not a smaller version of this table, it is
# a different and wrong one: on ``existingProduct`` the reference's ``data["name"]`` is the
# ARTISAN's name under ``artisanRef`` and the PRODUCT's name under ``productRef``, and the entity
# has a ``name`` field of its own that means the product — so a name-matching client writes a
# participant's name into a ministry report's product table, and the only-fill-blanks rule then
# refuses to correct it for ever.
REFERENCE_HYDRATION: dict[str, dict[str, str]] = {
    # ── THE CRAFT RECORD, IN FULL ────────────────────────────────────────────────────────────
    #
    # Two of the five things the crafts page collects used to cross. The argument for refusing the
    # other three is written out, and answered, above `REFERENCE_MODELS["Craft"].data` — the short
    # version is that "this value must not overwrite the designer's four cover fields" is a reason
    # for a box of its own, not a reason for silence, and the tool and product mappings had been
    # carrying a record's free-text place beside the workshop's own answers all along.
    #
    # `craftDescription` lands on `documentedCraftNotes` and NOT on stage 4's `craftIntroduction`,
    # for the reason `documentedProcessNotes` exists: `craftIntroduction` is the REQUIRED narrative
    # the designer writes about the cluster's craft as they observed it, and this is what a
    # researcher wrote about the craft months earlier somewhere else. Only-fill-blanks would put the
    # second where the first belongs on every workshop whose designer had not typed yet, and no
    # reader of the .docx could tell which author they were reading.
    "workshopSetup.craftRef": {
        "craftName": "craftName",
        "craftLocalName": "craftLocalName",
        "craftCategory": "craftCategory",
        "craftPlace": "craftPlace",
        "craftDescription": "documentedCraftNotes",
        "craftDocumentedOn": "craftDocumentedOn",
        # WHERE the craft was documented, beside WHEN. `craftRef` is ALL_SCOPE, so a linked craft may
        # belong to another cluster's study — which is legitimate reuse and only readable if it is
        # printed. See the `workshop` include on `REFERENCE_MODELS["Craft"]`.
        "craftDocumentedAtWorkshop": "craftDocumentedAtWorkshop",
        # How much footage the craft record carries. A sentence counting the files, never the ids:
        # `_media_note` gives both reasons (the gallery rule, and per-file entitlement).
        "craftMediaNote": "craftMediaNote",
        "craftPhoto": "craftPhoto",
        "craftPhotoCaption": "craftPhotoCaption",
    },
    # ── THE ARTISAN RECORD, IN FULL ──────────────────────────────────────────────────────────
    #
    # This mapping carried eight of the artisan record's twenty-seven answerable facts, and the
    # nineteen it dropped were not obscure ones: the artisan's address, their email, their PM
    # Vishwakarma card, the do's and don'ts a researcher wrote down about how to work with them,
    # and every part of the stated address except the village. A designer picked the artisan from
    # the picker and then typed those back in from a printout — which is the behaviour the whole
    # reference feature exists to end, arriving one level further in than anybody had looked.
    #
    # Read `REFERENCE_MODELS["Artisan"].data` in `design_workshops` alongside this: it carries the
    # reasoning for the three carries that were decisions rather than transcriptions — the MASKED
    # Pehchan card number, the STATED-not-provenance address and subject pin, and the two fields
    # (`age`, `experienceYears`) that have no column on `Artisan` at all and are therefore blank on
    # every record created since the artisan form stopped writing free metadata.
    #
    # `aadhaarNumber` is the one column deliberately not here in any form. See that same note.
    "participant.artisanRef": {
        "name": "name",
        "localName": "localName",
        "specialisation": "specialisation",
        "experienceYears": "experienceYears",
        "age": "age",
        "gender": "gender",
        "phone": "phone",
        "email": "email",
        "pehchanCardAvailable": "pehchanCardAvailable",
        "pehchanCardNumber": "artisanCardNo",
        "village": "village",
        "state": "state",
        "district": "district",
        "pincode": "pincode",
        "address": "address",
        "subjectLocation": "subjectLocation",
        "notes": "recordNotes",
        "dos": "dos",
        "donts": "donts",
        "documentedOn": "documentedOn",
        # THE OTHER HALF OF `documentedOn`'S OWN JOB. This is the one artisan picker declared
        # ALL_SCOPE — a roster legitimately holds artisans documented at other workshops — so a
        # printed roster could say when a row was documented and never where. See the `workshop`
        # include on `REFERENCE_MODELS["Artisan"]`.
        "documentedAtWorkshop": "documentedAtWorkshop",
        # What the record has attached beyond the single photograph below: audio introductions,
        # video, documents. A sentence and never the ids — see `_media_note` for both reasons.
        "recordMediaNote": "recordMediaNote",
        "photo": "photo",
        "photoCaption": "photoCaption",
    },
    # ── THE TOOL RECORD, IN FULL ─────────────────────────────────────────────────────────────
    #
    # Six of twenty-six. What a documented tool actually holds and this mapping used to drop: its
    # English name, how many years it has been in use, who made it, whether it is traditional or
    # modern, the improvements the artisan suggested for it, the remarks, the craft and place and
    # artisan it was documented against, and all seven of its measurements.
    #
    # `source` ("Where obtained") is NOT in this mapping and must not be added to it. It used to be
    # declared with `fromref()`, whose help text promises the designer it will be filled in from
    # the linked record — and `ToolDocumentation` has no column that answers it, so the promise was
    # never kept and nothing anywhere noticed. The field is now declared with plain `f()`. Filling
    # it from `maker` or from `place` instead would be a plausible wrong sentence in a submitted
    # report; see `ENUMS["MAKER_TYPE"]` for that argument in full.
    "tool.toolRef": {
        "name": "name",
        "localName": "localName",
        "englishName": "englishName",
        "material": "material",
        "usedFor": "usedFor",
        "cost": "cost",
        "yearsInUse": "yearsInUse",
        "maker": "maker",
        "traditionType": "traditionType",
        "craftName": "craftName",
        "place": "place",
        "artisanName": "artisanName",
        "improvements": "improvements",
        "remarks": "remarks",
        "lengthCm": "lengthCm",
        "breadthCm": "breadthCm",
        # HOW THE TWO CONVERTED FIGURES ABOVE WERE ARRIVED AT. A sentence about the RECORD's own
        # columns, not a label on the boxes — see `design_workshops._measurement_method_note` for why
        # only that claim survives the only-fill-blanks rule, and `record_fields.METHOD_CLAUSES` for
        # the two phrases it may use. Without it a vision model's estimate of a tool's length arrived
        # on the entry stamped with the name of whoever saved the record.
        "measurementMethodNote": "measurementMethodNote",
        "heightAsRecorded": "heightAsRecorded",
        "widthAsRecorded": "widthAsRecorded",
        "thicknessAsRecorded": "thicknessAsRecorded",
        "weightAsRecorded": "weightAsRecorded",
        "radiusAsRecorded": "radiusAsRecorded",
        # Every artisan the tool is ASSIGNED to, which the denormalised `artisanName` above cannot
        # answer. See `_linked_artisan_names` for why both are carried.
        # The record's own STATED address, in four boxes of its own. The free-text `place` above
        # is the denormalised column and is unchanged; these are what the record page collects and
        # what a reader needs to see that the thing was documented somewhere other than this
        # cluster. Provenance coordinates never cross — see the model's `include`.
        "recordState": "recordState",
        "recordDistrict": "recordDistrict",
        "recordVillage": "recordVillage",
        "recordPincode": "recordPincode",
        # The SUBJECT pin, which is the half of invariant 4 the stated-address carry left behind.
        # The device's own fix is not here and never will be — see `_subject_point`.
        "subjectLocation": "recordSubjectLocation",
        # The ordered making sequence and everything else on file, as a sentence. Never the ids.
        "recordMediaNote": "recordMediaNote",
        "usedByArtisans": "usedByArtisans",
        "documentedOn": "documentedOn",
        "photo": "photo",
        "photoCaption": "photoCaption",
    },
    # ── THE DOCUMENTED PROCESS AS A WHOLE, WHICH IS NOT A ROW ────────────────────────────────
    #
    # Stage 5's `traditionalProcess` is the one-per-workshop overview above the steps table, and it
    # is the home the note on `processStep.processRef` below identified for the two things a
    # per-step row must not receive: the source process's own ordered sub-steps, and its
    # pre-process flag. That note ends "The right home is the `traditionalProcess` singleton … but
    # a singleton has no ref field to hydrate from" — so the singleton was given one, and this is
    # it. The sequence prints ONCE, where a reader wants it, instead of inside every step or
    # nowhere at all.
    #
    # `notes` lands on `documentedProcessNotes` and NOT on `processOverview`. `processOverview` is
    # a REQUIRED rich-text narrative the designer writes about the cluster's process as they
    # observed it at the workshop; the record's notes are what a researcher wrote about it months
    # earlier somewhere else. Only-fill-blanks would put the second where the first belongs on
    # every workshop whose designer had not typed yet, and no reader of the .docx could tell which
    # they were reading.
    "traditionalProcess.processRef": {
        # How much footage the record carries. A sentence rather than the ids — the gallery rule
        # forbids seeding the designer's own photographs, and the record's files are gated per file.
        "recordMediaNote": "recordMediaNote",
        "name": "documentedProcessName",
        "notes": "documentedProcessNotes",
        "productName": "documentedFor",
        "steps": "documentedSteps",
        "preProcessAvailable": "preProcessAvailable",
        "documentedOn": "documentedOn",
    },
    # THE DOCUMENTED PROCESS IS THE STAGE'S SUBSTANTIVE NARRATIVE, and it used to contribute a
    # single word. The `Process` table holds five things — a name, free-text notes, a
    # pre-process flag, the product it hangs off, and its own sub-steps — and three of the five
    # are copied here:
    #
    #  * `notes` -> `description`. The registry calls that box "What happens", which is what a
    #    process's notes ARE. This is the copy that turns a one-word row into a paragraph.
    #  * `productName` -> `documentedFor`. Pure provenance, and the reason it is not optional:
    #    `processStep.processRef` is deliberately WORKSHOP-scoped (see the note on the field)
    #    because "Tie and dye" at Bagru and "Tie and dye" at Bhuj are two different sequences
    #    under one name, and the picker's only way of telling them apart on screen is the
    #    product name in its sublabel. Copying it is what lets the PRINTED report make the same
    #    distinction the designer made when choosing.
    #
    # The two that are NOT copied, so that the omission is a decision rather than an oversight:
    #
    #  * `steps`. A `Process` owns an ordered list of sub-steps, and `processStep` IS the
    #    workshop's own ordered list of steps — so copying one into a row of the other would
    #    print a whole sequence inside one of its own steps, and print it again on every row
    #    that names the same process.
    #  * `preProcessAvailable`. It is a property of the whole process, not of the step in front
    #    of the reader; "Pre-process available: Yes" under step 3 of 7 answers a question nobody
    #    asked of that row.
    "processStep.processRef": {
        "name": "name",
        "notes": "description",
        "productName": "documentedFor",
    },
    # NOT WIDENED, AND THE REASON IS NOT THE PHOTOGRAPH RULE. `report_builder.ReferencedRecord`
    # explains why hydration must never seed these two entities' galleries; that reasoning covers
    # the photograph and nothing else, so the rest was decided on its own terms:
    #
    #  * `existingProduct.artisanRef` copies the artisan's NAME because the row has a "Made by"
    #    box for it. It copies no village, phone or specialisation because the entity declares no
    #    box for any of them — this row documents a PRODUCT — and the roster row at stage 3
    #    already carries all three against the same artisan. A second copy here could only ever
    #    disagree with the first.
    #  * `prototype.productRef` copies the product's NAME into "Developed from" and stops there.
    #    A prototype is defined by how it DIFFERS from the product it derives from, so the
    #    fields that look symmetrical with `existingProduct` are exactly the ones the workshop
    #    exists to change: `materials` is a required answer about what the prototype is actually
    #    made of, and the product's `price` is a SELLING price with nothing on the prototype to
    #    receive it but `materialCost` and `labourCost`, which are costs. Worse, the
    #    only-fill-blanks rule would leave any of those standing untouched and indistinguishable
    #    from an answer the designer gave.
    "existingProduct.artisanRef": {"name": "artisanName"},
    # ── THE PRODUCT RECORD, IN FULL ──────────────────────────────────────────────────────────
    #
    # Six of twenty-four, and the six were the cheap ones. Everything MEASURABLE about the product
    # — three dimensions, the free-text size, the cost of making, the time to make it, the market
    # demand — stopped at the picker, along with its local name, its craft, its place, the tools
    # it is made with and the researcher's remarks. Stage 17's cost sheet asks for a cost of making
    # that the product record already held.
    #
    # THREE OF THESE PAIRS ARE NOT TRANSCRIPTIONS AND THE COMMENTS ON THEM ARE LOAD-BEARING.
    # `lengthCm`/`widthCm`/`heightCm` come from columns measured in INCHES and are converted; see
    # `_inches_to_cm`, which explains why a silent copy would put "12 cm" in a ministry report for
    # a saree 30.48 cm long and why the only-fill-blanks rule makes that unrecoverable.
    # `recordType` exists because four of ProductType's six members have no honest category.
    # `productionTimeNote` is free text and is deliberately NOT parsed into `productionTimeDays`.
    #
    # `artisanName` HAS TWO WRITERS, and that is the reason it is safe rather than a reason to
    # worry. `existingProduct.artisanRef` above also writes it. The order works out: hydration
    # walks the entity's fields in declaration order, `artisanRef` comes first, and each ref only
    # CLEARS-and-rewrites the row when that ref itself was re-pointed. So whichever picker the
    # designer just changed is the one whose answer lands, and the other pass sees a filled box and
    # leaves it alone. The two can only disagree if the product record's denormalised
    # `artisanName` is stale relative to the artisan record — and `productRef` is filtered by
    # `artisanRef`, so the picker can only ever offer that same artisan's products. Adding it is
    # what fills "Made by" for a designer who picks only the product, which is the common case.
    "existingProduct.productRef": {
        "name": "name",
        "localName": "localName",
        "category": "category",
        "recordType": "recordType",
        "material": "material",
        "mainToolsUsed": "mainToolsUsed",
        "price": "price",
        "costOfMaking": "costOfMaking",
        "marketDemand": "marketDemand",
        "use": "use",
        "craftName": "craftName",
        "place": "place",
        "artisanName": "artisanName",
        "lengthCm": "lengthCm",
        "widthCm": "widthCm",
        "heightCm": "heightCm",
        # HOW THOSE THREE WERE ARRIVED AT. See the same pair on `tool.toolRef` above and
        # `design_workshops._measurement_method_note`. `dimensionsNote` below is free text and carries
        # no method; nothing here invents one for it.
        "measurementMethodNote": "measurementMethodNote",
        "dimensionsNote": "dimensionsNote",
        "productionTimeNote": "productionTimeNote",
        "remarks": "remarks",
        # The record's own STATED address, in four boxes of its own. The free-text `place` above
        # is the denormalised column and is unchanged; these are what the record page collects and
        # what a reader needs to see that the thing was documented somewhere other than this
        # cluster. Provenance coordinates never cross — see the model's `include`.
        "recordState": "recordState",
        "recordDistrict": "recordDistrict",
        "recordVillage": "recordVillage",
        "recordPincode": "recordPincode",
        # The SUBJECT pin. The artisan mapping has carried its own since it was written; this side
        # carried the four stated strings and not the one coordinate that is about the village rather
        # than about the desk. See `_subject_point`.
        "subjectLocation": "recordSubjectLocation",
        # An audio note explaining the piece, a video of it being finished — the record holds them
        # and one IMAGE could not say so. A sentence; see `_media_note`.
        "recordMediaNote": "recordMediaNote",
        "documentedOn": "documentedOn",
        "photo": "productPhotos",
        "photoCaption": "productPhotosCaption",
    },
    "prototype.productRef": {"name": "productName"},
}


def reference_hydration_for(entity_key: str, field_key: str) -> dict[str, str]:
    """The mapping one REF field hydrates through, or an empty dict when it hydrates nothing.

    Empty is the FAIL-CLOSED answer and clients must treat it that way: a field with no entry
    writes nothing, the designer types one box, and the server fills it at save regardless. A
    guessed entry costs a wrong value nobody can see is wrong.
    """
    return REFERENCE_HYDRATION.get(f"{entity_key}.{field_key}", {})


# --------------------------------------------------------------------------------------
# The registry
# --------------------------------------------------------------------------------------

# Populated by stage_definitions.py, which holds the 22 StageSpec declarations. Splitting the
# data out of the machinery keeps this module reviewable: the rules live here, the two and a
# half thousand fields live there.
STAGES: tuple[StageSpec, ...] = ()


def _install(stages: tuple[StageSpec, ...]) -> None:
    """Bind the stage declarations. Called once, from stage_definitions."""
    global STAGES
    STAGES = stages


def stages() -> tuple[StageSpec, ...]:
    """The 22 stages, loading the declarations on first use.

    CALL THIS RATHER THAN IMPORTING ``STAGES``. ``from app.services.stage_schema import STAGES``
    binds the empty tuple that exists at import time, and :func:`_install` rebinds this module's
    global without ever reaching that copy — so the importing module keeps an empty registry for
    the life of the process. Under uvicorn that meant ``GET .../stages/WORKSHOP_SETUP`` answered
    404 "Unknown stage" while ``GET .../schema`` on the very same server returned all 22, which
    is about as confusing as a symptom gets. A function cannot be captured that way.

    ``STAGES`` remains for the declaration site and for tests that import the module rather than
    a name out of it.
    """
    _ensure_installed()
    return STAGES


def _ensure_installed() -> None:
    """Load the stage declarations the first time anything asks for them.

    THE BUG THIS EXISTS FOR. ``STAGES`` starts empty and is filled by importing
    :mod:`app.services.stage_definitions`, which calls :func:`_install`. Nothing in ``app/``
    imported it — the tests did, explicitly, which is exactly why the suite could be entirely
    green while a real server was broken. Under uvicorn the registry stayed empty, so
    ``GET /design-workshops/schema`` answered ``stages: 0``, every client rendered a form with
    no fields, every stage save dropped its whole payload as an unknown stage, and every report
    printed nothing. Nothing raised; the API returned 200 throughout.

    Importing inside the function rather than at module scope is what breaks the cycle:
    ``stage_definitions`` imports this module for its ``FieldSpec``/``EntitySpec`` types, so a
    top-level import here would be circular.
    """
    if not STAGES:
        import app.services.stage_definitions  # noqa: F401  - importing installs the registry


def stage(key: str) -> StageSpec:
    _ensure_installed()
    found = next((s for s in STAGES if s.key == key), None)
    if found is None:
        raise KeyError(f"unknown stage {key!r}")
    return found


def stage_by_number(number: int) -> StageSpec:
    _ensure_installed()
    found = next((s for s in STAGES if s.number == number), None)
    if found is None:
        raise KeyError(f"unknown stage number {number}")
    return found


def all_entities() -> tuple[tuple[StageSpec, EntitySpec], ...]:
    _ensure_installed()
    return tuple((s, e) for s in STAGES for e in s.entities)


# --------------------------------------------------------------------------------------
# Validation of the registry itself
# --------------------------------------------------------------------------------------


def validate_registry() -> list[str]:
    """Return every rule violation in the registry. An empty list means it is sound.

    Run by the tests. The checks are ordered by how expensive the mistake is to discover late:
    a duplicate key silently overwrites research data, while a missing help string is cosmetic.
    """

    _ensure_installed()
    problems: list[str] = []
    seen_stage_keys: set[str] = set()
    seen_stage_numbers: set[int] = set()
    seen_entity_keys: set[str] = set()
    entity_names: set[str] = set()

    for spec in STAGES:
        if spec.key in seen_stage_keys:
            problems.append(f"duplicate stage key {spec.key!r}")
        seen_stage_keys.add(spec.key)
        if spec.number in seen_stage_numbers:
            problems.append(f"duplicate stage number {spec.number}")
        seen_stage_numbers.add(spec.number)
        if not 1 <= spec.number <= 22:
            problems.append(f"stage {spec.key} has number {spec.number}, outside 1-22")

        singletons = [e for e in spec.entities if e.cardinality is Cardinality.SINGLETON]
        if len(singletons) > 1:
            problems.append(f"stage {spec.key} declares more than one SINGLETON entity")

        for entity in spec.entities:
            # Entity keys are globally unique because a stage entry row is addressed by
            # (workshopId, entityKey, ordinal) alone — scoping by stage as well would make the
            # storage key wider for no gain and let one collection be defined twice.
            if entity.key in seen_entity_keys:
                problems.append(f"duplicate entity key {entity.key!r} (stage {spec.key})")
            seen_entity_keys.add(entity.key)
            if entity.name in entity_names:
                problems.append(f"duplicate entity model name {entity.name!r}")
            entity_names.add(entity.name)
            if entity.parent and entity.parent not in seen_entity_keys | {
                e.key for s in STAGES for e in s.entities
            }:
                problems.append(
                    f"entity {entity.key!r} names parent {entity.parent!r}, which does not exist"
                )
            if entity.label_field and entity.field(entity.label_field) is None:
                problems.append(
                    f"entity {entity.key!r} label_field {entity.label_field!r} is not one of "
                    "its fields"
                )

            seen_field_keys: set[str] = set()
            for f in entity.fields:
                where = f"{entity.key}.{f.key}"
                if f.key in seen_field_keys:
                    problems.append(f"duplicate field key {where}")
                seen_field_keys.add(f.key)
                if not f.key or not f.key[0].isalpha():
                    problems.append(f"field key {where} must start with a letter")
                if not f.label:
                    problems.append(f"field {where} has no label")

                # Rule 3: the tiers only work if Basic alone can satisfy the gate.
                if f.required and f.tier is not Tier.BASIC:
                    problems.append(
                        f"field {where} is required but tier {f.tier.value}; only BASIC fields "
                        "may be required, or a workshop without facilities can never complete"
                    )
                # Rule 2: every enum resolves to a shared list.
                if f.type in (FieldType.ENUM, FieldType.MULTI_ENUM):
                    if not f.enum:
                        problems.append(f"field {where} is {f.type.value} but names no enum")
                    elif f.enum not in ENUMS:
                        problems.append(f"field {where} names unknown enum {f.enum!r}")
                elif f.enum:
                    problems.append(f"field {where} names an enum but is {f.type.value}")

                if f.type is FieldType.REF and not f.ref_model:
                    problems.append(f"field {where} is REF but names no ref_model")

                # THE CASCADE HAS TO RESOLVE HERE OR IT RESOLVES NOWHERE.
                #
                # A ``ref_filter_by`` naming a field that does not exist in the entity is
                # invisible at every later point: the registry serialises it happily, the web
                # form reads `row[refFilterBy]`, gets `undefined`, and asks the resolver for
                # every record in the table — so the product picker silently stops being
                # filtered by the artisan and starts offering the whole cluster's catalogue.
                # Nothing errors, nothing logs, and the only symptom is a designer picking the
                # wrong artisan's product into a ministry report. Renaming the field the cascade
                # points at is how it happens, which is exactly the sort of edit that gets made
                # without opening this file, so the registry has to refuse it.
                if f.ref_filter_by:
                    if f.type is not FieldType.REF:
                        problems.append(
                            f"field {where} names ref_filter_by but is {f.type.value}; only a "
                            "REF field is narrowed by another"
                        )
                    elif f.ref_filter_by == f.key:
                        problems.append(f"field {where} names itself as ref_filter_by")
                    elif entity.field(f.ref_filter_by) is None:
                        problems.append(
                            f"field {where} is filtered by {f.ref_filter_by!r}, which is not a "
                            f"field of {entity.key!r}"
                        )
                if f.ref_scope:
                    if f.type is not FieldType.REF:
                        problems.append(
                            f"field {where} names ref_scope but is {f.type.value}"
                        )
                    elif f.ref_scope not in REF_SCOPES:
                        problems.append(
                            f"field {where} names unknown ref_scope {f.ref_scope!r}; expected "
                            f"one of {', '.join(sorted(REF_SCOPES))}"
                        )
                if f.caption_for:
                    target = entity.field(f.caption_for)
                    if target is None:
                        problems.append(
                            f"field {where} captions {f.caption_for!r}, which is not in "
                            f"{entity.key}"
                        )
                    elif not target.type.is_media:
                        problems.append(
                            f"field {where} captions {f.caption_for!r}, which is not a media "
                            "field"
                        )
                if f.deprecated and not f.replaced_by:
                    # A deprecated field with no successor leaves a form with a dead input and
                    # no migration path for the data already stored under it.
                    problems.append(f"field {where} is deprecated with no replaced_by")
                if f.min_value is not None and f.max_value is not None \
                        and f.min_value > f.max_value:
                    problems.append(f"field {where} has min_value above max_value")
                if f.column_width_pct and not 0 < f.column_width_pct <= 100:
                    problems.append(f"field {where} column_width_pct out of range")

    # Rule 1 across entities: every promoted path must resolve to exactly one real field, and
    # no two paths may target the same column — either mistake gives the denormalisation two
    # possible sources, and they will disagree the first time both are edited.
    columns_seen: dict[str, str] = {}
    for path, column in PROMOTED_COLUMNS.items():
        entity_key, _, field_key = path.partition(".")
        entity = next((e for _s, e in all_entities() if e.key == entity_key), None)
        if entity is None:
            problems.append(f"promoted path {path!r} names entity {entity_key!r}, which does "
                            "not exist")
            continue
        if entity.field(field_key) is None:
            problems.append(f"promoted path {path!r} names no field of {entity_key!r}")
        if column in columns_seen:
            problems.append(
                f"promoted column {column!r} is written by both {columns_seen[column]!r} and "
                f"{path!r}"
            )
        columns_seen[column] = path

    # EVERY HYDRATION TARGET MUST BE A REAL FIELD OF A REAL ENTITY, and this check is the only
    # thing standing between a rename and a report with a hole in it. ``hydrate_entries`` looks
    # the target up with ``entity.field(target_key)`` and SKIPS a target it cannot resolve — no
    # error, no log — so a mapping that names a field somebody renamed copies nothing at all, on
    # every save, for ever, and the first symptom is a submitted document whose process table has
    # a name and no description. The source key is deliberately NOT checked: it names a key of a
    # ``REFERENCE_MODELS`` data lambda, which lives with the database code and is the one half of
    # the pair this module must not import.
    for path, mapping in REFERENCE_HYDRATION.items():
        entity_key, _, ref_field_key = path.partition(".")
        entity = next((e for _s, e in all_entities() if e.key == entity_key), None)
        if entity is None:
            problems.append(f"hydration path {path!r} names entity {entity_key!r}, which does "
                            "not exist")
            continue
        ref_field = entity.field(ref_field_key)
        if ref_field is None:
            problems.append(f"hydration path {path!r} names no field of {entity_key!r}")
        elif ref_field.type is not FieldType.REF:
            problems.append(f"hydration path {path!r} names {ref_field.type.value} field "
                            f"{ref_field_key!r}; only a REF field hydrates a row")
        for source_key, target_key in mapping.items():
            if entity.field(target_key) is None:
                problems.append(
                    f"hydration {path}[{source_key!r}] writes {target_key!r}, which is not a "
                    f"field of {entity_key!r}"
                )

    return problems


# --------------------------------------------------------------------------------------
# Value coercion and validation
# --------------------------------------------------------------------------------------

_TRUE = {"true", "yes", "y", "1", "on"}
_FALSE = {"false", "no", "n", "0", "off"}


#: The largest number of metres a GPS accuracy may claim, in metres.
#:
#: Half the earth's circumference: no two points on the surface are further apart than this, so an
#: error bar wider than it excludes nowhere and is not a measurement. The bound exists so that a
#: reading a device could not have produced is refused HERE, with the field's name on it, rather
#: than by the JSON column as a 500 the designer is shown as a lost connection.
_MAX_ACCURACY_M = 20_037_508.0


#: How many entries a multi-valued field may hold when its own ``FieldSpec`` declares no bound.
#:
#: EVERY MULTI-VALUED FIELD USED TO BE UNBOUNDED IN BOTH DIRECTIONS — no cap on the number of
#: entries and no cap on the length of any one of them — because the ``is_multi`` branch of
#: :func:`coerce_value` returns before the scalar-text branch where ``max_length`` is applied. The
#: envelope bounds in ``schemas/design_workshops`` do not help: ``MAX_STAGE_ROWS`` counts rows and
#: ``MAX_FIELD_KEYS`` counts keys per entry, and one KEY may hold an arbitrarily long array.
#: MULTI_ENUM's allow-list does not help either, because duplicates of an allowed token pass the
#: unknown-token check, so ``["COTTON"] * 1_000_000`` was accepted and stored. There is no
#: request-size middleware in ``app/main.py``; only the compression middleware reads
#: content-length.
#:
#: What that costs is not one bad save. An Android build with a bug in its gallery picker, or a
#: retry loop that appends rather than replaces, writes tens of thousands of media ids into one
#: ``stepPhotos`` array; the blob then lands in a jsonb column that ``GET /{id}/stages`` — the
#: feature's most frequent read, made on every stage-index open by every designer who can see the
#: workshop — serialises IN FULL, for ever, with no path that trims it and no error recording it.
#:
#: MEASURED RATHER THAN GUESSED, so the cap cannot refuse an honest answer: the registry declares
#: 35 multi-valued fields (18 IMAGE_LIST, 12 TAGS, 5 MULTI_ENUM); the largest option list any
#: MULTI_ENUM draws on is PRODUCT_CATEGORY at 15 entries, and the largest list in ``ENUMS`` at all
#: is MATERIAL_FAMILY at 17. 200 is more than ten times the widest legitimate "select them all",
#: and far past any gallery a designer photographs onto ONE row of a stage that already admits 500
#: rows. A field that genuinely needs more says so with ``max_items`` on its own ``FieldSpec``.
DEFAULT_MAX_ITEMS = 200

#: How long ONE entry of a multi-valued field may be when the field declares no ``max_length``.
#:
#: The items are media ids (a cuid is 25 characters), enum tokens, and designer-typed tags. 300 is
#: generous for all three and still refuses the megabyte string that reaches the same jsonb column
#: and the same read amplification as an over-long array. Bounded per ITEM rather than over the
#: joined length so the message can name the field and the designer can find the offending entry.
DEFAULT_MAX_ITEM_CHARS = 300


def coerce_value(spec: FieldSpec, raw: Any) -> tuple[Any, str | None]:
    """Coerce one submitted value to its stored form. Returns ``(value, error)``.

    Coercion is forgiving on purpose. Three clients write these values — a web form that sends
    strings, an Android draft that sends typed JSON, and a bulk import — and rejecting "12" for
    an INT because it arrived as a string would fail whole stages on a formatting difference.
    What is *not* forgiven is a value that cannot be read as the declared type at all, because
    that silently becomes a blank cell in a report someone submits to a ministry.

    A blank value is always accepted here and returns ``None``; whether blank is *allowed* is
    :func:`validate_entry`'s question, not this one's.
    """
    if raw is None:
        return None, None
    if isinstance(raw, str) and not raw.strip() and spec.type is not FieldType.LONG_TEXT:
        return None, None

    t = spec.type
    try:
        if t is FieldType.RICH_TEXT:
            # Normalised through the rich-text model rather than stored as the client sent it.
            # That is what stops a client's own editor state — selection ranges, undo history, an
            # unrecognised mark from a newer build — reaching the JSON column and, three months
            # later, the report renderer. A plain string is accepted and read as unformatted
            # prose, which is how a field promoted from LONG_TEXT keeps the text already under it.
            from app.services.rich_text import from_json, is_empty, to_json

            if is_empty(raw):
                return None, None
            return to_json(from_json(raw)), None

        if t.is_multi:
            if not isinstance(raw, (list, tuple)):
                return None, f"{spec.label} must be a list"
            items = [str(v).strip() for v in raw if str(v).strip()]
            # BOTH BOUNDS ARE APPLIED HERE BECAUSE THIS BRANCH RETURNS, and that early return is
            # the whole defect: it never reached the scalar-text branch below, where
            # ``spec.max_length`` lives, so a declared bound on a multi field was a silent no-op
            # and there was no item cap anywhere in the codebase. See DEFAULT_MAX_ITEMS above for
            # what an unbounded array costs on every subsequent read of the stage.
            #
            # A REFUSAL, NOT A TRUNCATION. Silently keeping the first 200 of an array the client
            # believes it stored is the shape of failure this module refuses everywhere else: the
            # designer is told "Stage saved" and the photographs are gone. A refused field is
            # reported per-field in ``errors``, keeps whatever the row already held (``save_stage``
            # restores rejected keys from ``previous``), and does not cost the other twenty fields
            # of the entry.
            limit = spec.max_items or DEFAULT_MAX_ITEMS
            if len(items) > limit:
                return None, f"{spec.label} may hold at most {limit} entries"
            # ``max_length`` PER ITEM, which is the only reading that makes sense for a list and
            # is what a field declaring one would mean by it. No field declares one today, so the
            # default below is what actually bounds every TAGS box in the registry.
            item_limit = spec.max_length or DEFAULT_MAX_ITEM_CHARS
            if any(len(v) > item_limit for v in items):
                return None, (
                    f"{spec.label}: one entry is longer than {item_limit} characters"
                )
            if t is FieldType.MULTI_ENUM:
                allowed = ENUMS.get(spec.enum, {})
                unknown = [v for v in items if v not in allowed]
                if unknown:
                    return None, f"{spec.label}: unknown option(s) {', '.join(unknown)}"
            return items, None

        if t in (FieldType.TEXT, FieldType.LONG_TEXT, FieldType.URL, FieldType.PHONE,
                 FieldType.EMAIL, FieldType.REF, FieldType.IMAGE, FieldType.FILE,
                 FieldType.AUDIO, FieldType.VIDEO):
            # `clean_text` ON THE WAY IN, not only on the way to the renderer.
            #
            # It drops the codepoints no document part and no Postgres text column can carry:
            # the C0 controls, U+FFFE/FFFF and — the one that was actually failing — the
            # surrogate block. A lone surrogate is not exotic: JSON permits it as a bare \\udXXX
            # escape, and ANY client that truncates a string at a UTF-16 index produces one by
            # cutting an emoji or an astral glyph in half. It reached the driver, raised
            # UnicodeEncodeError, and 500'd the WHOLE stage save — which the stage editor reads
            # through `isTransient` as "no connection", so a permanently un-saveable stage looked
            # like bad signal and retried forever with nothing ever told to the designer.
            # `rich_text` has passed every string through this same function since it was written
            # ("a lone surrogate from a phone that cut an emoji in half"), and RICH_TEXT fields
            # were therefore safe while the plain-text ones beside them were not.
            #
            # Dropped rather than rejected, and measured AFTER the drop: the client already lost
            # that character when it cut the pair, so losing one glyph is the honest outcome and
            # a 422 would be a rejection the designer cannot act on — the text looks fine on
            # their screen.
            text = clean_text(str(raw)).strip()
            if not text:
                return None, None
            if spec.max_length and len(text) > spec.max_length:
                return None, f"{spec.label} is longer than {spec.max_length} characters"
            return text, None

        if t is FieldType.ENUM:
            token = str(raw).strip()
            if token not in ENUMS.get(spec.enum, {}):
                return None, f"{spec.label}: {token!r} is not a valid option"
            return token, None

        if t is FieldType.BOOL:
            if isinstance(raw, bool):
                return raw, None
            token = str(raw).strip().lower()
            if token in _TRUE:
                return True, None
            if token in _FALSE:
                return False, None
            return None, f"{spec.label} must be yes or no"

        if t is FieldType.INT:
            value = int(str(raw).strip().replace(",", ""))
            return _range_checked(spec, value)

        if t in (FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT):
            value = float(str(raw).strip().replace(",", "").replace("₹", ""))
            if not math.isfinite(value):
                # `float()` HAPPILY READS "NaN", "Infinity", "inf" AND ANY RUN OF MORE THAN 308
                # DIGITS, and every one of those boxes is a plain <input type="text"> (the web
                # keeps trailing zeros that way), so a designer can type one. `_range_checked`
                # cannot catch them: every comparison against NaN is False, so `nan < 0` passes
                # the min-0 floor, and `inf` passes any floor there is. The consequences differed
                # only in how quietly they failed. MONEY stringifies, so `f"{nan:.2f}"` stored
                # the literal "nan" behind a 200 with `errors: {}` — the designer is told "Stage
                # saved" — and `report_builder.format_value` printed "₹ nan." on the cover
                # preview, in the .docx submitted to the ministry and in the on-device report,
                # while the cost charts silently dropped the row (`_as_number` rejects
                # non-finite) so the totals disagreed with the table with nothing to say why.
                # DECIMAL stores the float raw, so it reached the Postgres JSON column, Prisma
                # refused it, and the WHOLE STAGE SAVE 500'd — which the stage editor reads
                # through `isTransient` as "no connection", so the designer is told to wait for
                # signal while a permanently un-writable value retries forever.
                #
                # One guard, at the single point all three clients validate through.
                return None, f"{spec.label} is not a valid number"
            checked, error = _range_checked(spec, value)
            if error:
                return None, error
            # Money is stored as a string so it survives the JSON round trip without picking up
            # a binary-float artefact: 1250.10 must not come back as 1250.0999999999999.
            if t is FieldType.MONEY:
                return f"{checked:.2f}", None
            return checked, None

        if t is FieldType.DATE:
            from datetime import date

            text = str(raw).strip()[:10]
            date.fromisoformat(text)   # raises if malformed
            return text, None

        if t is FieldType.TIME:
            text = str(raw).strip()
            parts = text.split(":")
            if len(parts) < 2 or not (0 <= int(parts[0]) <= 23 and 0 <= int(parts[1]) <= 59):
                raise ValueError(text)
            return f"{int(parts[0]):02d}:{int(parts[1]):02d}", None

        if t is FieldType.GEO:
            if not isinstance(raw, dict):
                return None, f"{spec.label} must be a coordinate"
            lat = float(raw.get("lat"))
            lon = float(raw.get("lon"))
            # NaN fails this too, and deliberately reads that way round: every comparison
            # against NaN is False, so `not (...)` is True and the coordinate is refused.
            if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                return None, f"{spec.label} is not a valid coordinate"
            out: dict[str, Any] = {"lat": lat, "lon": lon}
            if raw.get("accuracy") is not None:
                accuracy = float(raw["accuracy"])
                # ACCURACY HAD NO CHECK OF ANY KIND while lat and lon had one, so it was the way
                # a non-finite float still got into the JSON column — where Prisma refuses it and
                # the entire stage save comes back as a bare 500 that the stage editor reports to
                # the designer as "no connection". A range as well as a finiteness test, because
                # a negative error bar is not a reading and neither is one larger than the planet.
                if not math.isfinite(accuracy) or not (0 <= accuracy <= _MAX_ACCURACY_M):
                    return None, f"{spec.label}: {raw['accuracy']!r} is not a GPS accuracy"
                out["accuracy"] = accuracy
            return out, None
    except (TypeError, ValueError):
        return None, f"{spec.label} is not a valid {spec.type.value.lower().replace('_', ' ')}"

    return raw, None


def _range_checked(spec: FieldSpec, value: float) -> tuple[Any, str | None]:
    if spec.min_value is not None and value < spec.min_value:
        return None, f"{spec.label} must be at least {spec.min_value:g}"
    if spec.max_value is not None and value > spec.max_value:
        return None, f"{spec.label} must be at most {spec.max_value:g}"
    return value, None


def validate_entry(entity: EntitySpec, data: dict[str, Any], *,
                   enforce_required: bool = True) -> tuple[dict[str, Any], dict[str, str]]:
    """Coerce and validate a whole entry against its entity.

    Returns the cleaned data and a ``{field key: message}`` map of errors. Unknown keys are
    DROPPED rather than rejected — deliberately, and unlike the rest of this codebase, whose
    ``APIModel`` sets ``extra="forbid"``. A phone one release ahead of the server carries fields
    this build has never heard of, and 422-ing the whole stage would mean a designer in a
    village cannot save two weeks of work because of a field they never filled in. The dropped
    keys are returned to the caller to log.

    ``enforce_required`` is off while a stage is a draft and on at submission: the whole point
    of the app is that a stage can be left half-filled overnight.
    """
    cleaned: dict[str, Any] = {}
    errors: dict[str, str] = {}

    for spec in entity.fields:
        if spec.deprecated:
            continue
        raw = data.get(spec.key)
        value, error = coerce_value(spec, raw)
        if error:
            errors[spec.key] = error
            continue
        if value is None or (isinstance(value, list) and not value):
            # BLANK AND DERIVABLE MEANS COMPUTE IT, which is what the field's own help text has
            # been promising. `durationDays` says "Leave blank to derive it from the start and end
            # dates" and nothing derived it — the value simply stayed empty, and the cover page of
            # a submitted report carried a blank where the form had said a number would appear.
            #
            # Derived from the INCOMING row rather than from the stored one, so a designer who
            # changes the end date gets a duration that follows it. The clients compute the same
            # value live from the same declaration (`derive_value`), so what they watch appear is
            # what lands here; this branch is what makes it true for the Android app, for an older
            # build, and for anything talking to the API directly.
            derived = derive_value(spec, data)
            if derived is not None:
                cleaned[spec.key] = derived
                continue
            if enforce_required and spec.required:
                errors[spec.key] = f"{spec.label} is required"
            continue
        cleaned[spec.key] = value

    _check_conditional(entity, cleaned, errors)
    return cleaned, errors


#: Fields that become required once another field on the same entity is filled in, as
#: ``entity key -> (dependent field, the fields that trigger it)``.
#:
#: ONE ENTRY SO FAR, and the shape is deliberately narrow rather than a general rule engine: the
#: registry declares fields, and a conditional requirement is the one thing a flat field list
#: cannot express. ``countOverrideReason``'s help has said "Required if either count above is
#: filled in" since it was written and nothing enforced it, so a designer could state that the
#: workshop produced 24 designs where the record holds 10 and give no reason at all — and that
#: number now WINS on the report's front page (see ``report_builder._output_count``), which is
#: exactly when the reason stops being a nicety.
_CONDITIONALLY_REQUIRED: dict[str, tuple[str, tuple[str, ...]]] = {
    "outcomes": ("countOverrideReason", ("designsCountOverride", "prototypesCountOverride")),
}


def _check_conditional(entity: EntitySpec, cleaned: dict[str, Any],
                       errors: dict[str, str]) -> None:
    """Apply the requirements that depend on another answer, at every tier and on every save.

    NOT gated on ``enforce_required``: unlike a Basic-tier field, which a designer is entitled to
    leave empty overnight, this one is only ever triggered by a value they have just typed. A
    figure that overrides the record needs its reason in the same breath, not at submit time
    three weeks later when the record it disagrees with has moved on.
    """
    rule = _CONDITIONALLY_REQUIRED.get(entity.key)
    if rule is None:
        return
    dependent, triggers = rule
    if cleaned.get(dependent) or dependent in errors:
        return
    if not any(_is_filled(cleaned.get(key)) for key in triggers):
        return
    spec = entity.field(dependent)
    if spec is None:  # pragma: no cover - the registry audit keeps this reachable
        return
    labels = [entity.field(k).label for k in triggers if entity.field(k) is not None]
    errors[dependent] = (
        f"{spec.label} is required once {' or '.join(labels)} is filled in."
    )


# --------------------------------------------------------------------------------------
# Completeness — the "completeness check" the source document asks for at stage 20
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class StageCompleteness:
    stage_key: str
    title: str
    required_total: int
    required_filled: int
    optional_total: int
    optional_filled: int
    collection_counts: dict[str, int]
    missing: tuple[str, ...]      # labels of unfilled required fields

    @property
    def percent(self) -> int:
        """Progress across BASIC-tier fields only — the tier the report actually needs.

        A stage with no required fields at all (stage 22 follow-up, for instance) reads as
        complete rather than as 0%, because dividing by zero to decide whether a designer may
        submit is how a stage becomes permanently unsubmittable.
        """
        if self.required_total == 0:
            return 100
        return round(100 * self.required_filled / self.required_total)

    @property
    def is_complete(self) -> bool:
        return self.required_filled >= self.required_total


def _is_filled(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, dict):
        # A rich-text document of empty paragraphs is not a filled field. An editor that was
        # focused and then left alone still saves {"blocks":[{"kind":"PARAGRAPH","spans":[]}]},
        # and counting that as filled would let a designer submit a report whose introduction is
        # blank while the gate reads 100%.
        if "blocks" in value:
            from app.services.rich_text import is_empty

            return not is_empty(value)
        return bool(value)
    if isinstance(value, (list, tuple)):
        return bool(value)
    return True


def stage_completeness(
    spec: StageSpec,
    singleton: dict[str, Any],
    collections: dict[str, list[dict[str, Any]]],
    *,
    ref_resolves: Callable[[Any], bool] | None = None,
    custom_fields: Sequence[Any] = (),
    custom_values: dict[str, Any] | None = None,
) -> StageCompleteness:
    """Score one stage against its declaration, and against the designer's own questions.

    A COLLECTION entity contributes its required fields once per existing row, and contributes
    nothing when it is empty *unless* the entity itself sits behind a required singleton field —
    an empty sketch list is a legitimate state on day one of a workshop, not an error.

    ``custom_fields`` AND ``custom_values`` ARE THE DESIGNER-DEFINED FIELDS OF THIS STAGE, AND
    THIS IS THE ONE PLACE THEY ARE SCORED. Every other reader — the readiness screen, the stage
    index, the submission gate's echo, the report's completeness annexure and its per-stage export
    warnings — reads this function rather than counting for itself, which is what stops two
    arithmetics appearing inside one document. (Plan §4: a custom required field counts toward the
    percentage, for the workshop that defines it.)

    THEY ARRIVE AS PLAIN DATA AND ARE TYPED ``Any`` ON PURPOSE. The concrete class is
    ``services/custom_sections.CustomFieldSpec``, and that module reads the database — which this
    one must never do, because it is the module the Kotlin and TypeScript ports mirror line for
    line and the module whose digest a bundled 119 KB asset is checked against. What is read here
    is four attributes and nothing else: ``key``, ``label``, ``required`` and ``retired``.

    ``custom_values`` is the ``_custom`` row's ``data`` for this stage — flat, keyed by custom field
    key. **It is scored KEY BY KEY and the container is never tested as a whole**, which is not a
    style preference: :func:`_is_filled` returns ``bool(value)`` for a dict, so a container holding
    twenty blank answers reads as filled and a stage would report itself complete on the strength of
    the container existing.

    ``ref_resolves`` ANSWERS "does this id still point at something?", and without it a required
    REF counts as filled whenever it holds a non-empty string. That is what made one submitted
    document disagree with itself: the completeness annexure read "13. Prototype Development |
    144/144 | 100% | Complete" while eighteen pages earlier the same report printed "Prototype |
    Not recorded." in all 18 prototypeStageLog tables and all 18 materialUsage tables — 36 times,
    identically in the .docx and the PDF. The renderer blanks an id it cannot resolve (a deleted
    parent row) and the scorer never checked, so the two halves of one document counted the same
    field differently.

    Left as ``None`` where nothing can resolve — the stage form scores itself on the phone with no
    database — so the argument is what the REPORT passes, and the report is where the
    contradiction was visible.
    """
    required_total = required_filled = 0
    optional_total = optional_filled = 0
    missing: list[str] = []

    def _counts_as_filled(f: FieldSpec, value: Any) -> bool:
        if not _is_filled(value):
            return False
        if f.type is not FieldType.REF or ref_resolves is None:
            return True
        return bool(ref_resolves(value))

    single = spec.singleton
    if single is not None:
        for f in single.fields:
            if f.deprecated:
                continue
            filled = _counts_as_filled(f, singleton.get(f.key))
            if f.required:
                required_total += 1
                if filled:
                    required_filled += 1
                else:
                    missing.append(f.label)
            else:
                optional_total += 1
                optional_filled += int(filled)

    # THE DESIGNER'S OWN QUESTIONS, SCORED BETWEEN THE STAGE'S SINGLETON AND ITS COLLECTIONS.
    #
    # Between, and not after, so that `missing` reads in the order a designer meets the questions on
    # the form: the stage's own fields, then the block they added to it, then the repeating rows.
    # THE ORDER IS NOT COSMETIC: the phone's report screen prints `missing.take(3)` per stage and the
    # completeness annexure prints the same three in its Outstanding column, so whatever this list
    # puts first is what a designer and a ministry officer actually read.
    #
    # FILED UNDER THE BARE LABEL, like a singleton field and unlike a collection field — which files
    # `f"{entity.title}: {label}"`. That is what makes a duplicate label a definition-time refusal
    # rather than a document that disagrees with itself: two required fields filing the same string
    # collapse into one row through `dict.fromkeys` below while `required_total` still counts two.
    # See `custom_sections.validate_definition`, which refuses exactly the pair that could collapse.
    #
    # A RETIRED FIELD IS SKIPPED, exactly as `if f.deprecated: continue` skips a superseded registry
    # field three lines up and for the same reason: it is no longer asked, so counting it would make
    # a stage permanently incomplete because of a question the designer corrected.
    values = custom_values or {}
    for cf in custom_fields:
        if getattr(cf, "retired", False):
            continue
        filled = _is_filled(values.get(getattr(cf, "key", "")))
        if getattr(cf, "required", False):
            required_total += 1
            if filled:
                required_filled += 1
            else:
                missing.append(str(getattr(cf, "label", "") or getattr(cf, "key", "")))
        else:
            optional_total += 1
            optional_filled += int(filled)

    counts: dict[str, int] = {}
    for entity in spec.collections:
        rows = collections.get(entity.key) or []
        counts[entity.key] = len(rows)
        for row in rows:
            for f in entity.fields:
                if f.deprecated:
                    continue
                filled = _counts_as_filled(f, row.get(f.key))
                if f.required:
                    required_total += 1
                    if filled:
                        required_filled += 1
                    else:
                        missing.append(f"{entity.title}: {f.label}")
                else:
                    optional_total += 1
                    optional_filled += int(filled)

    return StageCompleteness(
        stage_key=spec.key,
        title=spec.title,
        required_total=required_total,
        required_filled=required_filled,
        optional_total=optional_total,
        optional_filled=optional_filled,
        collection_counts=counts,
        missing=tuple(dict.fromkeys(missing)),   # de-duplicated, order preserved
    )


# --------------------------------------------------------------------------------------
# Serialisation for the clients
# --------------------------------------------------------------------------------------


def field_to_dict(f: FieldSpec, entity_key: str = "") -> dict[str, Any]:
    """One field as the clients read it.

    ``entity_key`` is what makes ``refHydration`` emittable at all: the hydration table is keyed
    by ``"entityKey.fieldKey"`` because field keys are unique only within an entity. It is
    optional so a field can still be serialised on its own — a caller that omits it gets a field
    with no mapping, which is the same fail-closed answer as a field that has none.
    """
    out: dict[str, Any] = {
        "key": f.key,
        "label": f.label,
        "type": f.type.value,
        "tier": f.tier.value,
        "required": f.required,
    }
    # Only non-default keys are emitted: the whole registry crosses the wire on every app
    # start, and the empty strings are most of its bulk.
    if f.help:
        out["help"] = f.help
    if f.unit:
        out["unit"] = f.unit
    if f.enum:
        out["enum"] = f.enum
        out["options"] = [{"value": v, "label": lbl} for v, lbl in ENUMS[f.enum].items()]
    if f.ref_model:
        out["refModel"] = f.ref_model
        # Emitted for EVERY ref field, defaulted rather than omitted, because a client that has
        # to supply its own default for the scope is a client that will eventually supply a
        # different one from the server's. The picker sends this value straight back on
        # ``GET .../references``, so server and client agree on how wide the net is by
        # construction instead of by both remembering the same rule.
        out["refScope"] = f.ref_scope or REF_SCOPE_ALL
        # WHICH BOXES THIS PICKER FILLS IN, AND WHICH OF THE RECORD'S VALUES GOES IN EACH.
        #
        # Published rather than left for each client to work out, because the obvious way to work
        # it out is to match key names and that is actively wrong — see the long note above
        # REFERENCE_HYDRATION for the participant's name landing in the product's name box.
        # Omitted entirely when the field hydrates nothing, so a client that reads it can fail
        # closed on the absence instead of guessing.
        hydration = reference_hydration_for(entity_key, f.key)
        if hydration:
            out["refHydration"] = dict(hydration)
    if f.ref_filter_by:
        out["refFilterBy"] = f.ref_filter_by
    if f.max_length:
        out["maxLength"] = f.max_length
    # Emitted on the same "only non-default keys" rule as everything around it, so no field in
    # today's registry emits it and neither the bundled Android asset nor `registry_version()`
    # moves for adding the line. It starts crossing the wire the day a field declares a bound,
    # which is the day a client needs it to stop a designer filling a picker the server will
    # then refuse. Both clients ignore keys they do not know.
    if f.max_items:
        out["maxItems"] = f.max_items
    if f.min_value is not None:
        out["minValue"] = f.min_value
    if f.max_value is not None:
        out["maxValue"] = f.max_value
    if f.report_role is not ReportRole.KEY_VALUE:
        out["reportRole"] = f.report_role.value
    if f.derived_kind:
        out["derivedKind"] = f.derived_kind
        out["derivedFrom"] = list(f.derived_from)
    if f.column_width_pct:
        out["columnWidthPct"] = f.column_width_pct
    if f.caption_for:
        out["captionFor"] = f.caption_for
    if f.deprecated:
        out["deprecated"] = True
        out["replacedBy"] = f.replaced_by
    return out


def entity_to_dict(e: EntitySpec) -> dict[str, Any]:
    return {
        "key": e.key,
        "name": e.name,
        "cardinality": e.cardinality.value,
        "title": e.title,
        "description": e.description,
        "parent": e.parent,
        "labelField": e.label_field,
        "fields": [field_to_dict(f, e.key) for f in e.fields if not f.deprecated],
    }


def stage_to_dict(s: StageSpec) -> dict[str, Any]:
    return {
        "number": s.number,
        "key": s.key,
        "title": s.title,
        "purpose": s.purpose,
        "notes": s.notes,
        "optionalStage": s.optional_stage,
        "entities": [entity_to_dict(e) for e in s.entities],
    }


def registry_to_dict() -> dict[str, Any]:
    """The whole registry, as the clients fetch it from ``GET /design-workshops/schema``.

    The version is derived from the content rather than hand-maintained, because a
    hand-maintained one is a version that stops changing.

    WHAT A CHANGED VERSION ACTUALLY DOES, PER CLIENT — measured on 2026-08-13 rather than
    assumed, because the sentence that used to stand here ("a changed version is what tells an
    Android draft store to run its migration") is FALSE, and it is false about the exact
    mechanism a reviewer auditing this digest would come here to check:

      * THE BROWSER keys its IndexedDB registry store by this string and stores the draft's own
        ``registryVersion`` beside it, so ``stageSpecFor`` can render a stage through the registry
        it was captured against (``frontend/lib/designWorkshopStore.ts``). This is the one client
        that genuinely uses the version as an identity.
      * THE SERVER stamps it onto the workshop row on every stage save, which is what
        ``reportDiff``'s ``schemaVersionChanged`` compares between two report runs.
      * ANDROID DOES NOT MIGRATE ON IT AND HAS NO DRAFT-LEVEL RECORD OF IT. Its draft store's
        ladder is a separate integer, ``WORKSHOP_DRAFT_SCHEMA_VERSION`` (currently 2, stored as
        ``schemaVersion`` in ``draft.json``), which moves only when the ON-DISK DRAFT FORMAT
        changes — never when this digest does. ``StageSchemaStore.store`` computes a "the version
        moved" boolean and its only caller discards it; ``StageSchemaStore.cachedVersion`` has no
        callers at all. A moved digest therefore rewrites the phone's cache file and does nothing
        else: verified on an SM-M325F by pointing its cached registry at a bogus digest and
        re-fetching, after which the draft was byte-for-byte identical (sha256 unchanged), no
        migration ran, nothing was re-keyed, nothing was quarantined and nothing was said.

    That is SAFE rather than lossy, and for reasons that are load-bearing rather than lucky:
    draft values are ``JsonElement`` maps that carry keys this build has never heard of through a
    round trip untouched, ``requiredKeys`` is recomputed from the live registry on every persist
    rather than trusted from disk, and the drift a designer actually needs to hear about is the
    server's own ``droppedKeys`` at save time. Do not add an Android migration keyed to this
    string without first giving a draft somewhere to record which digest it was written against.
    """

    _ensure_installed()
    return {
        "version": registry_version(),
        "enums": {name: [{"value": v, "label": lbl} for v, lbl in options.items()]
                  for name, options in ENUMS.items()},
        "stages": [stage_to_dict(s) for s in STAGES],
    }


def registry_version() -> str:
    """A short stable digest of every key, type, tier, DERIVATION and HYDRATION in the registry.

    Deliberately insensitive to labels and help text: retitling a field must not invalidate
    every cached draft on every phone, but adding, removing or retyping one must.

    THE DERIVATION IS PART OF THE DIGEST BECAUSE IT IS PART OF THE BEHAVIOUR. It was not, once,
    and the failure was silent in the worst way: `android/.../assets/design-workshop-schema.json`
    — the copy a handset runs off before it has ever reached the network — carried two derived
    fields where the registry had five, missing exactly the three cost-sheet ones. Because the
    digest covered key/type/tier/required/enum/deprecated and nothing else, the stale asset's
    version string matched the live registry's CHARACTER FOR CHARACTER, so the staleness check
    that exists precisely to catch this reported agreement. A field that silently stops computing
    is indistinguishable, on the phone, from a field the designer forgot to fill in.

    THE HYDRATION MAPPING IS HERE FOR THE SAME REASON, ONE FEATURE LATER. ``field_to_dict`` now
    publishes :data:`REFERENCE_HYDRATION` as ``refHydration`` so the clients fill a row in by the
    server's rule instead of by matching key names — matching names is what wrote an artisan's
    name into a ministry report's product column. That makes the mapping a client contract, and a
    contract outside the digest is a contract that cannot be re-delivered: correcting a wrong
    mapping touches no key, type, tier or derivation, so the version would not move, the bundled
    asset would stay stale with `test_the_bundled_android_asset_matches_the_registry_it_was_dumped_from`
    reporting agreement (it compares the version, not the content), and a handset that has never
    reached the network would go on hydrating by the mapping the correction was written to end.
    Pinned by ``test_the_version_changes_when_a_hydration_mapping_changes``.
    """

    _ensure_installed()
    import hashlib

    parts: list[str] = []
    for s in STAGES:
        for e in s.entities:
            for f in e.fields:
                # In DECLARATION order, not sorted: the mapping's order is what decides which
                # source key wins if two ever named one target, so a reordering is a change.
                hydration = ",".join(
                    f"{src}>{dst}"
                    for src, dst in reference_hydration_for(e.key, f.key).items()
                )
                parts.append(f"{s.key}.{e.key}.{f.key}:{f.type.value}:{f.tier.value}:"
                             f"{int(f.required)}:{f.enum}:{int(f.deprecated)}:"
                             f"{f.derived_kind}:{','.join(f.derived_from)}:{hydration}")
    digest = hashlib.sha256("|".join(sorted(parts)).encode("utf-8")).hexdigest()
    return digest[:16]


def derive_value(spec: FieldSpec, row: dict[str, Any]) -> Any:
    """What ``spec`` computes to from ``row``, or ``None`` when it cannot be computed.

    THE ONE DEFINITION. `frontend/lib/derivedFields.ts` and the Android renderer are ports of it,
    and the reason all three exist rather than the server alone is that the value has to appear
    while the designer is typing: a duration that only materialises after a save is a number they
    cannot check against the sanction order in front of them.

    Returns None rather than 0 for "not computable". A start date with no end date has no
    duration, and writing 0 would put "0 days" onto a cover page.
    """
    if not spec.derived_kind or not spec.derived_from:
        return None

    if spec.derived_kind == "DAYS_BETWEEN":
        from datetime import date

        try:
            start = date.fromisoformat(str(row.get(spec.derived_from[0]) or "")[:10])
            end = date.fromisoformat(str(row.get(spec.derived_from[1]) or "")[:10])
        except (TypeError, ValueError):
            return None
        # INCLUSIVE of both days: a workshop that runs the 12th to the 14th is three days long,
        # which is what its attendance register and its utilisation certificate both say. The
        # exclusive reading would report two and disagree with every other document in the file.
        days = (end - start).days + 1
        return days if days > 0 else None

    if spec.derived_kind == "PRODUCT":
        total = 1.0
        for key in spec.derived_from:
            raw = row.get(key)
            if raw in (None, ""):
                return None
            try:
                total *= float(str(raw).replace(",", ""))
            except (TypeError, ValueError):
                return None
        # MONEY is stored as a fixed-2 string so it survives the JSON round trip without picking
        # up a binary-float artefact — the same rule ``coerce_value`` follows.
        return f"{total:.2f}" if spec.type is FieldType.MONEY else round(total, 4)

    if spec.derived_kind == "SUM":
        # BLANK MEANS ZERO HERE, and that is the difference from PRODUCT, which returns None the
        # moment one factor is missing. A cost sheet's total is the sum of six heads of which
        # four are optional: requiring all six would mean `totalCost` stayed empty for every
        # workshop that had no packaging or transport cost, which is most of them. But a row with
        # NONE of them filled has no total — it is an empty row, not a zero-rupee product — and
        # printing "₹ 0.00" into a cost sheet a ministry reads is a claim, not a blank.
        total = 0.0
        seen = False
        for key in spec.derived_from:
            raw = row.get(key)
            if raw in (None, ""):
                continue
            try:
                total += float(str(raw).replace(",", ""))
            except (TypeError, ValueError):
                return None
            seen = True
        if not seen:
            return None
        return f"{total:.2f}" if spec.type is FieldType.MONEY else round(total, 4)

    return None
