"""EVERY FIELD A PICKED RECORD HOLDS REACHES THE WORKSHOP, AND THIS IS WHERE THAT IS CHECKABLE.

The requirement in the user's words: "it is okay to have more fields over there, but not less than
that. Ensure that all those fields are taken in faithfully, without a miss."

That is a claim about two tables — ``design_workshops.REFERENCE_MODELS[model].data`` (which source
column becomes which data key) and ``stage_schema.REFERENCE_HYDRATION`` (which data key lands on
which entity field) — plus the ``FieldSpec`` declarations that receive them. Before this file the
two tables carried 32 field-pairs between five models, and NOTHING anywhere compared them to the
source records: a Prisma column that reached nothing looked exactly like a Prisma column somebody
had decided not to carry, and a mapping key that no lambda produced looked exactly like one that
worked.

So the tests below are not "does hydration still work" — ``test_reference_resolver.py`` owns that,
against a live database. These are the FIDELITY tests, and they are deliberately shaped so that a
column added to ``prisma/schema.prisma`` next year fails one of them:

1. **Coverage.** Every column of ``Artisan``, ``ProductDocumentation``, ``ToolDocumentation``,
   ``Process``/``ProcessStep`` and ``Craft`` is named in an explicit list here — either as CARRIED,
   with the entity field it lands on, or as NOT CARRIED with the reason. A column that appears in
   the schema and in neither list fails :func:`test_no_source_column_is_unaccounted_for`. That is
   the "without a miss" guarantee made mechanical.
2. **Round trip.** A fully populated source record of each type goes through the REAL
   ``hydrate_entries`` — the same clearing rule, the same ``coerce_value`` — and every declared
   target must arrive non-empty.
3. **The three landmines**, each with its own test: the inches→centimetre conversion, the enum
   translation tables being TOTAL over their Prisma enums, and the identity number that must
   arrive masked.
4. **The two declarations that drift**: hydration source keys against the data lambdas, and the
   ``fromref()`` help-text marker against the mapping. Both had already drifted when this was
   written; neither had a guard.

Nothing here touches a database. ``hydrate_entries`` is driven through a fake Prisma client, which
is what lets a fully populated record be described in one readable literal instead of assembled
across a module-scoped fixture.
"""

import re
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
from typing import NamedTuple

import pytest

# Importing this module is what installs the twenty-two stages into the registry.
import app.services.stage_definitions  # noqa: F401
from app.services import design_workshops as dw
from app.services.records import derive_age
from app.services.stage_schema import (
    ENUMS,
    REFERENCE_HYDRATION,
    FieldType,
    all_entities,
    validate_registry,
)

SCHEMA = Path(__file__).resolve().parents[1] / "prisma" / "schema.prisma"


# --------------------------------------------------------------------------------------
# Reading the source of truth: the Prisma schema itself
# --------------------------------------------------------------------------------------


def _schema_text() -> str:
    return SCHEMA.read_text(encoding="utf-8")


def _block(kind: str, name: str) -> str:
    """The body of one ``model`` or ``enum`` declaration, comments stripped.

    Parsed out of the file rather than read off the generated Prisma client, deliberately: the
    client is regenerated from this file, so a column added and not yet generated would make these
    tests pass against a schema that no longer matches. The file IS the change under review.
    """
    text = _schema_text()
    start = re.search(rf"^{kind}\s+{name}\s*\{{", text, re.MULTILINE)
    assert start, f"{kind} {name} is not in {SCHEMA.name}"
    depth, index = 0, start.end() - 1
    for index in range(start.end() - 1, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                break
    body = text[start.end(): index]
    return re.sub(r"//[^\n]*|///[^\n]*", "", body)


def _columns(model: str) -> set[str]:
    """The SCALAR columns of a model — relations, ids and index directives excluded.

    A relation is recognised by its type being another model (upper-case first letter and not one
    of the Prisma scalars), which is the same rule the generator uses.
    """
    scalars = {"String", "Int", "Float", "Boolean", "DateTime", "Decimal", "Json", "BigInt", "Bytes"}
    enums = set(re.findall(r"^enum\s+(\w+)\s*\{", _schema_text(), re.MULTILINE))
    out: set[str] = set()
    for line in _block("model", model).splitlines():
        line = line.strip()
        if not line or line.startswith("@@"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        name, type_name = parts[0], parts[1].rstrip("?[]")
        if not name[0].islower():
            continue
        if type_name in scalars or type_name in enums:
            out.add(name)
    return out


def _relations(model: str) -> set[str]:
    """The RELATION fields of a model — exactly the ones :func:`_columns` throws away.

    Written because `_columns`' own docstring ("relations, ids and index directives excluded") is
    the ledger's one structural blind spot, and something real was sitting in it:
    `ToolDocumentation.artisanLinks` — the whole tool-assignment feature, with a form, four routes
    and a data-browser filter behind it — reached no workshop and no report, and
    `test_no_source_column_is_unaccounted_for` could not see it to say so. Same rule as `_columns`,
    read the other way round: a field whose type is another model.
    """
    scalars = {"String", "Int", "Float", "Boolean", "DateTime", "Decimal", "Json", "BigInt", "Bytes"}
    enums = set(re.findall(r"^enum\s+(\w+)\s*\{", _schema_text(), re.MULTILINE))
    out: set[str] = set()
    for line in _block("model", model).splitlines():
        line = line.strip()
        if not line or line.startswith("@@"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        name, type_name = parts[0], parts[1].rstrip("?[]")
        if not name[0].islower():
            continue
        if type_name not in scalars and type_name not in enums:
            out.add(name)
    return out


def _enum_members(name: str) -> set[str]:
    return {m.strip() for m in _block("enum", name).splitlines() if m.strip()}


# --------------------------------------------------------------------------------------
# The coverage ledger — the evidence that nothing was missed
# --------------------------------------------------------------------------------------
#
# READ THIS BEFORE ADDING A COLUMN TO ANY OF THE FIVE MODELS. Every scalar column of every model a
# REF field can point at appears below exactly once: in CARRIED with the workshop field it reaches,
# or in NOT_CARRIED with the reason it does not. A new column belongs in one of the two lists, and
# `test_no_source_column_is_unaccounted_for` is what makes that not optional.
#
# The reasons in NOT_CARRIED are not decoration. Four categories recur and each is a real decision:
#
#  * "bookkeeping" — the source record's own lifecycle (status, review audit, created/updated
#    timestamps, the id of the user who typed it). These describe the RECORD, not the artisan or
#    the product, and a report about a workshop has no question they answer.
#  * "join key" — a foreign key whose VALUE reaches the workshop through the relation it names.
#  * "provenance, not stated" — the `Location` model's own docstring: latitude/longitude/altitude/
#    accuracy/capturedAt are a fix of the desk the record was typed at, 1,500 km from the village
#    on the same row on every live record. Copying them would put a report's map pin on a desk.
#  * "identity" — see `aadhaarNumber`, which is the one column here refused on policy grounds.
#  * "a form default, not an answer" — a Prisma enum member that is ALSO the column's `@default`
#    and has no blank alternative in any record form. `ProductType.OTHER`, `MarketDemand.UNKNOWN`
#    and `MakerType.UNKNOWN` are the three, and the argument is written out above the translation
#    tables in `design_workshops`. THE COLUMNS ARE STILL CARRIED and stay in the CARRIED lists —
#    it is the individual MEMBER that translates to nothing, so the box arrives blank for the
#    designer to answer instead of pre-answered on nobody's behalf. `productType` keeps even the
#    unmappable member, on `existingProduct.recordType`; `marketDemand` and `maker` have no such
#    second box, so their default token reaches nothing at all, which is the honest state.
#
# RELATIONS ARE LEDGERED SEPARATELY, BELOW, AND THAT IS THE ONE BLIND SPOT THIS FILE HAD.
# `_columns` is scalars-only by design, so `test_no_source_column_is_unaccounted_for` structurally
# cannot see a relation — and `ToolDocumentation.artisanLinks`, the entire tool-assignment feature,
# sat in exactly that gap: built end to end (a form, four routes, a data-browser filter) and
# reaching no workshop and no report, with the mechanism that promises "nothing was missed" unable
# to say so. `RELATION_LEDGER` closes it; see `test_no_source_relation_is_unaccounted_for`.

# `status` IS NOT BOOKKEEPING, AND IT USED TO BE FILED AS IF IT WERE — in a line beside
# `createdAt`, `updatedAt` and `createdById`. `enum RecordStatus { DRAFT PENDING APPROVED REJECTED
# NEEDS_REVISION }` is a named reviewer's verdict on whether the record is fit to be used;
# `NEEDS_REVISION`'s schema comment reads "Reviewer sent the record back with comments
# (reviewNotes); creator edits and resubmits". Filing that as a timestamp is how a REJECTED
# ToolDocumentation came to sit in the stage-5 picker looking exactly like an approved one, and to
# hydrate all twenty-four of its fields into a document handed to a ministry officer.
#
# IT IS STILL NOT CARRIED, and the reason is the opposite of "it does not matter". It is MUTABLE
# and a hydrated value is a PERMANENT copy: a tool picked while PENDING is approved the following
# week, and a frozen "Pending review" in the report would then be a false statement about a named
# reviewer's decision — worse than the silence it replaced. The verdict is surfaced where it is
# LIVE instead, in the picker's sublabel at the moment of choosing. See
# `design_workshops._review_flag` and
# `test_a_reviewers_verdict_is_visible_while_choosing_and_is_never_copied_onto_the_entry`.
_MUTABLE_VERDICT = (
    "a reviewer's verdict, and MUTABLE — not bookkeeping. Frozen into a permanent copy it would "
    "assert a stale verdict for ever, so it is shown live in the picker's sublabel "
    "(design_workshops._review_flag) and never written onto an entry."
)

ARTISAN_CARRIED = {
    "name": "participant.name",
    "localName": "participant.localName",
    "gender": "participant.gender",
    "phone": "participant.phone",
    "email": "participant.email",
    "place": "participant.village",            # the fallback when Location.village is empty
    "pehchanCardAvailable": "participant.pehchanCardAvailable",
    "pehchanCardNumber": "participant.artisanCardNo",   # MASKED — see the identity test
    "address": "participant.address",
    "notes": "participant.recordNotes",
    "dos": "participant.dos",
    "donts": "participant.donts",
    "recordedAt": "participant.documentedOn",
    # Added 2026-08-16 with the columns themselves. `dateOfBirth` reaches `participant.age` and NOT
    # a date field, because the workshop asks for an age and the age is DERIVED here — see
    # `records.derive_age` and the note on the column: an age stored is wrong within a year.
    "dateOfBirth": "participant.age (derived, never stored)",
    "experienceYears": "participant.experienceYears",
    # Still carried, and now only as the FALLBACK for records written before the two columns above
    # existed. The migration copied every clean numeric value across and deliberately left the ones
    # it could not parse ("30+", "about 30") in the JSON rather than guessing at them.
    "extraMetadata": "participant.experienceYears + participant.age + specialisation (legacy)",
    "craftId": "participant.specialisation",
    "locationId": "participant.village/state/district/pincode/subjectLocation",
}
ARTISAN_NOT_CARRIED = {
    "aadhaarNumber": (
        "identity. Masked on every exported surface by policy (records.mask_identity_number, and "
        "the scar in record_fields.py:270-283). It is the deduplication key; 'XXXX XXXX 9012' in a "
        "participant table answers no question a design report asks, and the artisan is already "
        "identified by name, Pehchan card and phone."
    ),
    "status": _MUTABLE_VERDICT,
    "reviewNotes": "bookkeeping — internal moderation",
    "reviewedById": "bookkeeping — internal moderation",
    "reviewedAt": "bookkeeping — internal moderation",
    "recordedTimezone": "the date is carried as a bare DATE, so the zone has nothing to qualify",
    "createdAt": "bookkeeping",
    "updatedAt": "bookkeeping",
    "createdById": "bookkeeping — the researcher who typed the record",
    "workshopId": "join key — the design workshop already knows which workshop it belongs to",
}

LOCATION_CARRIED = {
    "state": "participant.state",
    "district": "participant.district",
    "village": "participant.village",
    "pincode": "participant.pincode",
    "subjectLatitude": "participant.subjectLocation",
    "subjectLongitude": "participant.subjectLocation",
}
LOCATION_NOT_CARRIED = {
    "latitude": "provenance, not stated — the desk the record was typed at",
    "longitude": "provenance, not stated",
    "altitude": "provenance, not stated",
    "accuracy": "provenance, not stated",
    "capturedAt": "provenance, not stated",
    "placeName": "provenance, not stated — derived from the device fix, not from the artisan",
    # MOVED HERE FROM CARRIED, and it sat one line above `placeName` contradicting it. It read
    # "participant.address (fallback when Artisan.address is empty)", and that fallback printed the
    # reverse-geocoded street of the DESK under an artisan's name, beside a village, district and
    # state 1,500 km away. `Location`'s own docstring, `LocationInput` and `LocationFields.tsx`
    # (which labels the box "GPS address") all file it in the PROVENANCE group beside `placeName`.
    "address": (
        "provenance, not stated — the address of the desk the record was typed at, derived from "
        "the same device fix placeName is. Artisan.address is the STATED one and still carries."
    ),
    "extraMetadata": "bookkeeping",
    "createdAt": "bookkeeping",
    "updatedAt": "bookkeeping",
}

PRODUCT_CARRIED = {
    "productName": "existingProduct.name",
    "localName": "existingProduct.localName",
    "productType": "existingProduct.recordType (+ .category for the two mappable tokens)",
    "craftName": "existingProduct.craftName",
    "place": "existingProduct.place",
    "artisanName": "existingProduct.artisanName",
    "rawMaterialsUsed": "existingProduct.material",
    "mainToolsUsed": "existingProduct.mainToolsUsed",
    "productFunctionUse": "existingProduct.use",
    "sellingPrice": "existingProduct.price",
    "costOfMaking": "existingProduct.costOfMaking",
    "marketDemand": "existingProduct.marketDemand",
    "lengthInches": "existingProduct.lengthCm (x2.54)",
    "breadthInches": "existingProduct.widthCm (x2.54)",
    "heightInches": "existingProduct.heightCm (x2.54)",
    "size": "existingProduct.dimensionsNote",
    "timeTakenToCompleteProduct": "existingProduct.productionTimeNote",
    "remarks": "existingProduct.remarks",
    "recordedAt": "existingProduct.documentedOn",
    "artisanId": "join key — existingProduct.artisanRef, which fills artisanName",
    "craftId": "carried by value as craftName",
    "locationId": "carried by value as place",
}
PRODUCT_NOT_CARRIED = {
    "measurementImageId": (
        "a working image from the grid-measurement pipeline, not a catalogue photograph. It would "
        "arrive in the report's product gallery as a picture of a ruler. This entry used to end "
        "'The catalogue shot is carried through _reference_photos', which was FALSE about the "
        "tree it was written in: _reference_photos took the OLDEST image and both record forms "
        "upload the grid frames first, so the grid shot reached the gallery anyway, by the path "
        "this entry named as the safe one. Refusing the column achieved nothing until "
        "_reference_photos learned to sort a MEASUREMENT_GRID-marked row last — which is what "
        "now makes the sentence true. See test_the_measurement_grid_shot_is_not_the_records_"
        "photograph."
    ),
    "measurementAnalysis": (
        "machine output. report_templates.py:69-84 requires a reader to be able to tell model "
        "prose from an author's, and this has no such treatment — it would need the naming "
        "ANNEXURE_AI_LAYERS gets."
    ),
    "measurementAnalysisStatus": "queue state of the above",
    "status": _MUTABLE_VERDICT,
    "reviewNotes": "bookkeeping",
    "reviewedById": "bookkeeping",
    "reviewedAt": "bookkeeping",
    "extraMetadata": "bookkeeping — EXIF written programmatically by the record form",
    "recordedTimezone": "the date is carried as a bare DATE",
    "createdAt": "bookkeeping",
    "updatedAt": "bookkeeping",
    "createdById": "bookkeeping",
    "workshopId": "join key",
}

TOOL_CARRIED = {
    "toolkitName": "tool.name",
    "localName": "tool.localName",
    "englishName": "tool.englishName",
    "processUsedIn": "tool.usedFor",
    "material": "tool.material",
    "replacementCost": "tool.cost",
    "yearsInUse": "tool.yearsInUse",
    "maker": "tool.maker",
    "traditionType": "tool.traditionType",
    "craftName": "tool.craftName",
    "place": "tool.place",
    "artisanName": "tool.artisanName",
    "suggestionsForToolImprovement": "tool.improvements",
    "remarks": "tool.remarks",
    "lengthInches": "tool.lengthCm (x2.54)",
    "breadthInches": "tool.breadthCm (x2.54)",
    "height": "tool.heightAsRecorded (source states no unit)",
    "width": "tool.widthAsRecorded (source states no unit)",
    "thickness": "tool.thicknessAsRecorded (source states no unit)",
    "weight": "tool.weightAsRecorded (source states no unit)",
    "radius": "tool.radiusAsRecorded (source states no unit)",
    "recordedAt": "tool.documentedOn",
    "artisanId": "carried by value as artisanName",
    "craftId": "carried by value as craftName",
    "locationId": "carried by value as place",
}
TOOL_NOT_CARRIED = {
    "measurementImageId": (
        "a working image from the measurement pipeline; see the product note, including why that "
        "note's claim about _reference_photos was false until the MEASUREMENT_GRID marker existed"
    ),
    "measurementAnalysis": "machine output; see the product note",
    "measurementAnalysisStatus": "queue state of the above",
    "status": _MUTABLE_VERDICT,
    "reviewNotes": "bookkeeping",
    "reviewedById": "bookkeeping",
    "reviewedAt": "bookkeeping",
    "extraMetadata": "bookkeeping",
    "recordedTimezone": "the date is carried as a bare DATE",
    "createdAt": "bookkeeping",
    "updatedAt": "bookkeeping",
    "createdById": "bookkeeping",
    "workshopId": "join key",
}

PROCESS_CARRIED = {
    "name": "processStep.name + traditionalProcess.documentedProcessName",
    "notes": "processStep.description + traditionalProcess.documentedProcessNotes",
    "preProcessAvailable": "traditionalProcess.preProcessAvailable",
    "recordedAt": "traditionalProcess.documentedOn",
    "productId": "processStep.documentedFor + traditionalProcess.documentedFor (the product name)",
}
PROCESS_NOT_CARRIED = {
    "status": _MUTABLE_VERDICT,
    "reviewNotes": "bookkeeping",
    "reviewedById": "bookkeeping",
    "reviewedAt": "bookkeeping",
    "extraMetadata": "bookkeeping",
    "recordedTimezone": "the date is carried as a bare DATE",
    "createdAt": "bookkeeping",
    "updatedAt": "bookkeeping",
    "createdById": "bookkeeping",
    "workshopId": "join key",
}

PROCESS_STEP_CARRIED = {
    "name": "traditionalProcess.documentedSteps (one line per step)",
    "notes": "traditionalProcess.documentedSteps",
    "stepType": "traditionalProcess.documentedSteps ('(group)' marker)",
    "sortOrder": "traditionalProcess.documentedSteps (the line's number)",
}
PROCESS_STEP_NOT_CARRIED = {
    "processId": "join key",
    "createdAt": "bookkeeping",
    "updatedAt": "bookkeeping",
}

CRAFT_CARRIED = {
    "name": "workshopSetup.craftName",
    "localName": "workshopSetup.craftLocalName",
}
CRAFT_NOT_CARRIED = {
    "category": "no counterpart on the cover page; stage 4 asks the craft's story in prose instead",
    "description": "stage 4's craftIntroduction is a RICH_TEXT narrative the designer writes",
    "place": (
        "stage 1 asks state/district/block/village as four REQUIRED cover fields. One free-text "
        "place cannot answer them and would disagree with them."
    ),
    "extraMetadata": "bookkeeping",
    "recordedAt": "bookkeeping — the cover carries the WORKSHOP's dates, not the craft record's",
    "recordedTimezone": "bookkeeping",
    "createdAt": "bookkeeping",
    "updatedAt": "bookkeeping",
    "createdById": "bookkeeping",
    "workshopId": "join key",
}

# ── THE RELATION LEDGER — the blind spot, ledgered ────────────────────────────────────────────
#
# Same contract as the scalar lists above and for the same reason: a relation that reaches nothing
# must be a decision somebody wrote down, not something nobody looked at. Every relation of every
# model a REF field can point at appears here exactly once, with what happens to it.
#
# THE WORDS THAT RECUR:
#  * "read by the picker" — the relation is in `REFERENCE_MODELS[…].include` and a lambda reads it.
#  * "reached by its own picker" — the related records are chosen through a REF field of their own,
#    so pulling them in through this side would print the same rows twice.
#  * "bookkeeping" / "join key" — as in the scalar lists.
#  * "NOT BUILT" — the honest answer, and the one this ledger exists to make sayable.
RELATION_LEDGER = {
    "Artisan": {
        "craft": "read by the picker — participant.specialisation, via include={'craft': True}",
        "location": (
            "read by the picker — the STATED address columns and the subject pin, via "
            "include={'location': True}. The PROVENANCE half of the same row does not cross."
        ),
        "createdBy": "bookkeeping — the researcher who typed the record",
        "workshop": "join key — the design workshop already knows which workshop it belongs to",
        "workshops": "join key — the WorkshopArtisan reading of 'was at this workshop'",
        "products": "reached by its own picker (existingProduct.productRef)",
        "tools": "reached by its own picker (tool.toolRef)",
        "toolLinks": "the tool-assignment join; see ToolDocumentation.artisanLinks below",
        "media": "one photograph per record, through _reference_photos",
        "questionnaireInterviews": "a different feature; the report reaches questionnaires directly",
        "sectionStatuses": "bookkeeping — questionnaire progress",
    },
    "Location": {
        "artisans": "the back-reference of the relation the Artisan picker includes",
        "workshops": "join key",
        "products": "back-reference",
        "tools": "back-reference",
        "media": "back-reference",
        "questionnaireInterviews": "a different feature",
    },
    "ProductDocumentation": {
        "artisan": (
            "NOT INCLUDED, deliberately: every value the picker needs is on the denormalised "
            "scalar columns (artisanName, craftName, place). include={'artisan': True} used to be "
            "declared here and no lambda ever read it — a join per picker keystroke for nothing."
        ),
        "craft": "carried by value as craftName",
        "workshop": "join key",
        "location": (
            "NOT INCLUDED, and NOT a tidy-up to make. _reference_place prefers location.village "
            "over the free-text place and runs at REPORT time, so turning this on would change the "
            "place string printed in documents already submitted. See ReferenceModel.include."
        ),
        "createdBy": "bookkeeping",
        "media": "one photograph per record, through _reference_photos (media_field='productId')",
        "mediaProcessingJobs": "queue state; see measurementAnalysisStatus in the scalar list",
        "processes": "reached by its own picker (processStep.processRef / traditionalProcess)",
    },
    "ToolDocumentation": {
        "artisan": "NOT INCLUDED — see the identical note on ProductDocumentation.artisan",
        "craft": "carried by value as craftName",
        "workshop": "join key",
        "location": "NOT INCLUDED — see the identical note on ProductDocumentation.location",
        "createdBy": "bookkeeping",
        "media": "one photograph per record, through _reference_photos (media_field='toolId')",
        "mediaProcessingJobs": "queue state",
        "artisanLinks": (
            "NOT BUILT, and this entry is the reason this ledger exists. ToolArtisan is the whole "
            "tool-assignment feature — ToolAssignmentSection.tsx, four routes in tools.py, a "
            "data_browser filter — and none of it reaches a workshop or a report: tool.toolRef "
            "prints 'Documented for: <the one denormalised artisanName>' and the artisans a "
            "researcher assigned by hand appear nowhere. Carrying them is a PRODUCT decision and "
            "not a bug fix, and it is not cheap: it needs a bounded 'assignedArtisans' join, a new "
            "fromref() declared KEY_VALUE and never a sixth TABLE_COLUMN (the tool table's five "
            "widths already sum to exactly 100), a label saying 'as documented' because the value "
            "is frozen at pick time, a registry_version() bump, a regenerated "
            "design-workshop-schema.json and the matching pair in DW_REFERENCE_HYDRATION."
        ),
    },
    "Process": {
        "product": "read by the picker — processStep.documentedFor, via include={'product': True}",
        "createdBy": "bookkeeping",
        "workshop": "join key",
        "steps": (
            "read by the picker — _step_lines flattens the ordered sequence onto "
            "traditionalProcess.documentedSteps, via include={'steps': True}"
        ),
    },
    "ProcessStep": {
        "process": "join key — the step rows are reached through Process.steps",
    },
    "Craft": {
        "createdBy": "bookkeeping",
        "workshop": "join key",
        "artisans": "reached by its own picker (participant.artisanRef)",
        "products": "reached by its own picker",
        "tools": "reached by its own picker",
        "media": "media_field='craftId', but no Craft mapping carries a photograph",
        "workshops": "join key — the WorkshopCraft reading of workshop scope",
    },
}


LEDGER = [
    ("Artisan", ARTISAN_CARRIED, ARTISAN_NOT_CARRIED),
    ("Location", LOCATION_CARRIED, LOCATION_NOT_CARRIED),
    ("ProductDocumentation", PRODUCT_CARRIED, PRODUCT_NOT_CARRIED),
    ("ToolDocumentation", TOOL_CARRIED, TOOL_NOT_CARRIED),
    ("Process", PROCESS_CARRIED, PROCESS_NOT_CARRIED),
    ("ProcessStep", PROCESS_STEP_CARRIED, PROCESS_STEP_NOT_CARRIED),
    ("Craft", CRAFT_CARRIED, CRAFT_NOT_CARRIED),
]


@pytest.mark.parametrize(("model", "carried", "refused"), LEDGER, ids=[m for m, _c, _r in LEDGER])
def test_no_source_column_is_unaccounted_for(model, carried, refused):
    """THE "WITHOUT A MISS" GUARANTEE, MADE MECHANICAL.

    A column exists in the Prisma schema and is not in either list => this fails, naming the
    column. Somebody then either carries it or writes down why not. The failure is the point: the
    previous state of this feature was one where a dropped column was indistinguishable from a
    considered omission, and every one of the nineteen artisan fields that reached nothing looked
    like somebody's decision until they were enumerated.
    """
    columns = _columns(model) - {"id"}
    accounted = set(carried) | set(refused)
    missing = sorted(columns - accounted)
    assert not missing, (
        f"{model} has columns this repository has never decided about: {missing}. Add each to "
        f"{model.upper()}_CARRIED with the workshop field it reaches, or to _NOT_CARRIED with the "
        "reason it does not."
    )
    stale = sorted(accounted - columns)
    assert not stale, f"{model} no longer has these columns: {stale}"


@pytest.mark.parametrize("model", sorted(RELATION_LEDGER), ids=sorted(RELATION_LEDGER))
def test_no_source_relation_is_unaccounted_for(model):
    """THE ONE SHAPE OF OMISSION THE SCALAR LEDGER IS STRUCTURALLY UNABLE TO SEE.

    `_columns` excludes relations by design, so `test_no_source_column_is_unaccounted_for` — the
    mechanism whose docstring promises "a column exists in the Prisma schema and is not in either
    list => this fails" — was blind to every many-to-many in the schema. `ToolDocumentation`
    `artisanLinks` sat in that blind spot for the whole life of the tool-assignment feature: built,
    shipped, reaching no workshop and no report, and looking to this file exactly like something
    that had been considered.

    A relation added to any of these seven models now fails here until somebody writes down what
    happens to it — including, and especially, "nothing".

    IT IS A TRIPWIRE AND IT PASSES BY CONSTRUCTION TODAY, which is worth stating so that a green run
    is not read as evidence that anything in this lane works. Nothing it asserts can be broken by a
    source change; it fires on the next SCHEMA change. Its negative control is therefore on itself:
    deleting ``artisanLinks`` from ``RELATION_LEDGER["ToolDocumentation"]`` must fail it, which is
    what shows ``_relations`` really parses a many-to-many out of ``schema.prisma`` rather than
    quietly returning an empty set and passing every model.
    """
    declared = _relations(model)
    accounted = set(RELATION_LEDGER[model])
    missing = sorted(declared - accounted)
    assert not missing, (
        f"{model} has relations this repository has never decided about: {missing}. Add each to "
        f"RELATION_LEDGER[{model!r}] with what reaches the workshop through it, or with the reason "
        "nothing does."
    )
    stale = sorted(accounted - declared)
    assert not stale, f"{model} no longer has these relations: {stale}"


@pytest.mark.parametrize(("model", "carried", "refused"), LEDGER, ids=[m for m, _c, _r in LEDGER])
def test_no_column_is_both_carried_and_refused(model, carried, refused):
    overlap = sorted(set(carried) & set(refused))
    assert not overlap, f"{model}: {overlap} appear in both lists"


def test_the_ledger_names_real_workshop_fields():
    """A CARRIED entry that names a field the registry does not have is a lie in the evidence.

    Parsed loosely — the values are prose that may name two targets or add a parenthetical — so
    this checks every ``entity.field`` token it can find rather than the whole string.
    """
    known = {f"{entity.key}.{spec.key}"
             for _s, entity in all_entities() for spec in entity.fields}
    for model, carried, _refused in LEDGER:
        for column, target in carried.items():
            for token in re.findall(r"\b([a-z]\w*)\.(\w+)\b", target):
                path = f"{token[0]}.{token[1]}"
                # Only check tokens that look like entity.field, not "x2.54" or file names.
                if token[0] in {e.key for _s, e in all_entities()}:
                    assert path in known, (
                        f"{model}.{column} claims to reach {path}, which is not in the registry"
                    )


# --------------------------------------------------------------------------------------
# The two tables must agree with each other, and with the fromref() marker
# --------------------------------------------------------------------------------------


def test_the_registry_is_sound():
    assert validate_registry() == []


def test_every_mapping_source_exists_and_every_produced_key_lands():
    """THE GUARD ``validate_registry`` IS STRUCTURALLY UNABLE TO BE.

    ``stage_schema`` must not import ``design_workshops``, so it checks hydration TARGETS and says
    in a comment that it cannot check SOURCES. The consequence was ``ProductDocumentation.data``
    producing ``localName`` for years, no mapping reading it, and ``existingProduct`` having no box
    for it — three omissions that each looked like one of the other two's job. A typo on the source
    side had exactly the same signature and would have been permanent.
    """
    assert dw.validate_reference_carry() == []


def test_a_field_that_promises_to_fill_itself_in_actually_does():
    """``fromref()`` IS A FOURTH DECLARATION OF THE HYDRATION TABLE AND IT HAD ALREADY DRIFTED.

    ``fromref`` appends the FROM_REF sentence — "Filled in from the linked record when one is
    chosen" — to a field's help text, and that sentence ships to the browser and to the bundled
    Android asset. ``validate_registry`` cannot see it: it is help text. So the marker and the
    mapping were free to disagree, and they did, in both directions at once — ``tool.source``
    claimed a carry that no mapping provided, and ``existingProduct.category`` was hydrated while
    saying nothing at all.

    Asserted in BOTH directions because the two failures cost different things. A field that
    promises and does not deliver leaves the designer waiting for a box to fill itself; a field
    that delivers without promising has the designer type over a value the picker is about to
    supply, and only-fill-blanks then makes the designer's version permanent, which is fine — but
    they were never told the record had an answer.
    """
    from app.services.stage_definitions import FROM_REF

    promises: set[str] = set()
    for _stage, entity in all_entities():
        for spec in entity.fields:
            if FROM_REF in spec.help:
                promises.add(f"{entity.key}.{spec.key}")

    delivers: set[str] = set()
    for path, mapping in REFERENCE_HYDRATION.items():
        entity_key = path.partition(".")[0]
        delivers.update(f"{entity_key}.{target}" for target in mapping.values())

    assert sorted(promises - delivers) == [], (
        "these fields tell the designer they fill in from the linked record and no mapping "
        "writes them"
    )
    assert sorted(delivers - promises) == [], (
        "these fields are filled in from the linked record and their help text does not say so"
    )


# --------------------------------------------------------------------------------------
# LANDMINE 1 — the source measures in inches and the workshop declares centimetres
# --------------------------------------------------------------------------------------


def test_inches_become_centimetres_and_the_factor_is_exact():
    """A SILENT COPY WOULD PUT "12 cm" IN A GOVERNMENT REPORT FOR A SAREE 30.48 cm LONG.

    The inch has been DEFINED as 25.4 mm since 1959, so 2.54 is exact and this is a pin, not an
    approximation check. Rounded to two places to match the source columns, which are
    ``Decimal(10, 2)``.
    """
    assert dw._inches_to_cm(12) == 30.48
    assert dw._inches_to_cm("1") == 2.54
    assert dw._inches_to_cm(None) is None
    assert dw._inches_to_cm("not a number") is None


def test_the_source_columns_say_inches_and_the_target_fields_say_centimetres():
    """The two halves of the reason the conversion has to exist, asserted rather than assumed.

    If somebody ever renames the Prisma columns to plain ``length``, or drops ``unit="cm"`` off the
    workshop fields, the conversion becomes wrong in a way no other test would notice.
    """
    for model in ("ProductDocumentation", "ToolDocumentation"):
        assert "lengthInches" in _columns(model)
        assert "breadthInches" in _columns(model)

    for entity_key, field_keys in (
        ("existingProduct", ("lengthCm", "widthCm", "heightCm")),
        ("tool", ("lengthCm", "breadthCm")),
    ):
        for field_key in field_keys:
            spec = _field(entity_key, field_key)
            assert spec.unit == "cm", f"{entity_key}.{field_key} no longer declares centimetres"


def test_the_tool_measurements_whose_unit_nobody_knows_do_not_claim_one():
    """``height``/``width``/``thickness``/``weight``/``radius`` carry NO unit on the tool record.

    The Prisma columns have no suffix, the record form labels them with the bare words, and the
    record sheet prints them bare. Declaring ``unit="cm"`` on the receiving fields would convert an
    unknown into a stated wrong answer — which is worse than the blank it replaces, and is the
    same class of failure as skipping the conversion above.
    """
    for field_key in ("heightAsRecorded", "widthAsRecorded", "thicknessAsRecorded",
                      "weightAsRecorded", "radiusAsRecorded"):
        spec = _field("tool", field_key)
        assert spec.unit == "", f"tool.{field_key} claims a unit the source record does not state"
        assert "unit" in spec.help.lower(), f"tool.{field_key} does not warn the designer"


# --------------------------------------------------------------------------------------
# LANDMINE 2 — the enum translation tables must be TOTAL over their Prisma enums
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("prisma_enum", "table"),
    [
        ("ProductType", dw._PRODUCT_TYPE_TO_CATEGORY),
        ("ProductType", dw._PRODUCT_TYPE_TO_MEMBER),
        ("MarketDemand", dw._MARKET_DEMAND_TO_DEMAND_LEVEL),
        ("TraditionType", dw._TRADITION_TYPE_TO_TRADITION),
        ("MakerType", dw._MAKER_TYPE_TO_MAKER),
    ],
)
def test_every_translation_table_names_every_member_of_its_prisma_enum(prisma_enum, table):
    """A PARTIAL MAP DEGRADES SILENTLY INTO A STALE MAP.

    ``_PRODUCT_TYPE_TO_CATEGORY`` held two of ProductType's six members and ``.get()`` returned
    None for the rest. That was the right behaviour and it was invisible: nothing distinguished
    "FINISHED_GOOD deliberately has no category" from "somebody forgot FINISHED_GOOD", and nothing
    at all would have distinguished either from "a seventh member shipped last week". A member with
    no honest destination is now spelled ``None``, and this test is what makes the schema and the
    tables move together.
    """
    members = _enum_members(prisma_enum)
    assert set(table) == members, (
        f"the translation table for {prisma_enum} is out of step with prisma/schema.prisma: "
        f"missing {sorted(members - set(table))}, unknown {sorted(set(table) - members)}. Spell an "
        "unmappable member None, with the reason, rather than leaving it out."
    )


@pytest.mark.parametrize(
    ("prisma_enum", "registry_enum"),
    [("ProductType", "PRODUCT_TYPE"), ("MakerType", "MAKER_TYPE")],
)
def test_the_mirrored_registry_lists_match_their_prisma_enums(prisma_enum, registry_enum):
    """``PRODUCT_TYPE`` and ``MAKER_TYPE`` exist to RECEIVE a source value, so they must mirror it.

    Every other list in ``ENUMS`` is a question a designer answers and is free to have its own
    vocabulary. These two are the source record's own answer arriving intact, and a token the
    source can produce but the registry does not know would be dropped by ``coerce_value`` —
    silently, because a rejected hydration looks exactly like an unfilled column.
    """
    assert set(ENUMS[registry_enum]) == _enum_members(prisma_enum)


def test_every_translated_target_can_actually_hold_what_the_table_produces():
    """The other end of the same check: a translation is useless if ``coerce_value`` refuses it.

    ``TraditionType.MODERN`` -> ``CONTEMPORARY`` is a translation and not a passthrough precisely
    because the raw token is not in ``TRADITION_TYPE``; this asserts the translated one is.
    """
    for table, registry_enum in (
        (dw._PRODUCT_TYPE_TO_CATEGORY, "PRODUCT_CATEGORY"),
        (dw._PRODUCT_TYPE_TO_MEMBER, "PRODUCT_TYPE"),
        (dw._MARKET_DEMAND_TO_DEMAND_LEVEL, "DEMAND_LEVEL"),
        (dw._TRADITION_TYPE_TO_TRADITION, "TRADITION_TYPE"),
        (dw._MAKER_TYPE_TO_MAKER, "MAKER_TYPE"),
    ):
        for source, target in table.items():
            if target is None:
                continue
            assert target in ENUMS[registry_enum], (
                f"{source} translates to {target}, which is not in ENUMS[{registry_enum!r}] and "
                "would be dropped by coerce_value without a word"
            )


def test_an_unmapped_token_is_logged_and_not_raised(caplog):
    """"Refuse loudly" is a TEST failure, not a 500 in a designer's face.

    The loud refusal is ``test_every_translation_table_names_every_member_of_its_prisma_enum``
    above, which fails before a build leaves the machine. At runtime an unmapped token must cost
    one blank field, not the whole picker: fifty rows are read in one call and one of them holding
    a token added last week must not lose the other forty-nine.
    """
    import logging

    with caplog.at_level(logging.ERROR):
        assert dw._translated(dw._MAKER_TYPE_TO_MAKER, "A_TOKEN_FROM_THE_FUTURE") is None
    assert "A_TOKEN_FROM_THE_FUTURE" in caplog.text
    # An absent value is a different thing and says nothing.
    caplog.clear()
    assert dw._translated(dw._MAKER_TYPE_TO_MAKER, None) is None
    assert caplog.text == ""


# --------------------------------------------------------------------------------------
# LANDMINE 3 — a source that no longer writes, and an identity number that must be masked
# --------------------------------------------------------------------------------------


def test_the_pehchan_card_number_arrives_masked_and_the_aadhaar_does_not_arrive_at_all():
    """THE DEFECT THIS REPOSITORY HAS ALREADY PAID FOR ONCE, ON A NEW SURFACE.

    ``record_fields.py`` carries the note: "The card number used to print verbatim here while the
    Aadhaar beside it was masked, so a full PM Vishwakarma ID reached every grantee, dataset
    downloader and reviewer — a rule that held on the API responses and nowhere else." A design
    workshop is exactly such a surface: its stage reads do NOT pass through
    ``records._redact_sensitive``, and a ``DesignWorkshopViewer`` is a grantee. The copy is also
    permanent by design, so an unmasked number could never be walked back.
    """
    row = _artisan_row()
    data = dw.REFERENCE_MODELS["Artisan"].data(row, None)

    assert data["pehchanCardNumber"] == "XXXX XXXX 5678"
    assert row.pehchanCardNumber not in str(data), "the bare card number is in the carried data"
    assert "aadhaar" not in " ".join(data).lower(), "the Aadhaar number must not be carried at all"
    assert row.aadhaarNumber not in str(data)


def test_experience_and_age_now_come_from_columns_with_the_legacy_metadata_behind_them():
    """THE GAP THIS TEST WAS WRITTEN TO PIN IS CLOSED, and this is what replaced it.

    It used to assert that ``Artisan`` had NO ``experienceYears`` and NO ``age`` column, that both
    were read only from the ``extraMetadata`` spellings researchers used before the record registry
    existed, and that both were therefore blank on every artisan created after ``ArtisanForm``
    stopped writing free metadata. Its own docstring named the assertion that should fail the day
    somebody did the migration. That day was 2026-08-16.

    The consequence it was pinning was not abstract: ``participant.age`` and
    ``participant.experienceYears`` are ``fromref`` fields whose help text promises a designer the
    picker fills them in, and ``experienceYears`` is a TABLE_COLUMN — so an imported artisan arrived
    with both boxes blank, an artisan added from inside the workshop had nowhere to record them, and
    the blank printed in the participant table of every submitted report.

    Four assertions, and the last two are the ones that keep the migration honest:

    1. both columns exist;
    2. when a row carries them, the COLUMNS win — a stale legacy value cannot shadow a current one;
    3. when a row does not (every record written before the migration could parse it), the legacy
       metadata is still read, so the oldest and best-documented artisans do not go blank;
    4. AGE IS DERIVED FROM THE DATE and not stored, which is why the column is a birthday. An age
       written down is wrong within a year and nothing in this system would ever say so.
    """
    columns = _columns("Artisan")
    assert "experienceYears" in columns, "the migration is the point of this test"
    assert "dateOfBirth" in columns
    assert "age" not in columns, (
        "an age COLUMN is the mistake this design exists to avoid: it is wrong within a year of "
        "being written and nothing would ever notice. Store the date, derive the age."
    )

    # 2. The columns win over the legacy metadata, which is deliberately set to DIFFERENT values.
    documented = _artisan_row(
        dateOfBirth=datetime(1971, 1, 8), experienceYears=31,
        extraMetadata={"experienceYears": 22, "age": 44},
    )
    carried = dw.REFERENCE_MODELS["Artisan"].data(documented, None)
    assert carried["experienceYears"] == 31, "the column must outrank the legacy metadata"
    assert carried["age"] != 44, "age must be derived from dateOfBirth, not read from metadata"
    assert carried["age"] == derive_age(datetime(1971, 1, 8))

    # 3. A row from before the migration still answers, out of the metadata.
    legacy = dw.REFERENCE_MODELS["Artisan"].data(_artisan_row(), None)
    assert legacy["experienceYears"] == 22
    assert legacy["age"] == 44

    # 4. And a row with neither says nothing, rather than inventing a zero.
    silent = dw.REFERENCE_MODELS["Artisan"].data(
        _artisan_row(extraMetadata={"mediaExif": [{"lat": 1}]}), None
    )
    assert silent["experienceYears"] is None
    assert silent["age"] is None


def test_the_device_fix_never_reaches_the_workshop_and_the_subject_pin_does():
    """``Location``'s own docstring, asserted: every live record's fix is the desk it was typed at.

    Copying ``latitude``/``longitude`` would put a report's map pin 1,500 km from the village named
    on the same row. Only the pin a researcher deliberately dropped on the SUBJECT's place crosses.
    """
    data = dw.REFERENCE_MODELS["Artisan"].data(_artisan_row(), None)
    assert data["subjectLocation"] == {"lat": 21.2, "lon": 83.6}
    assert "accuracy" not in data["subjectLocation"], "a hand-dropped pin has no error bar"

    no_pin = _artisan_row(location=_location_row(subjectLatitude=None, subjectLongitude=None))
    assert dw.REFERENCE_MODELS["Artisan"].data(no_pin, None)["subjectLocation"] is None


async def test_the_gps_address_never_becomes_the_artisans_stated_address(monkeypatch):
    """THE FOURTH LINE OF THE ROSTER USED TO CONTRADICT THE THREE ABOVE IT.

    `participant.address` read `r.address or _rel(r, "location", "address")`. `Location.address` is
    a PROVENANCE column by three independent declarations — the `Location` docstring, `LocationInput`
    and `LocationFields.tsx`, which renders it in a box labelled "GPS address" inside a panel
    captioned "Provenance, not an address. These values say where the device was" — and it is
    derived from the same device fix `placeName` is, which this file already refused on exactly
    that ground.

    So an artisan whose own Address box a researcher left blank, while letting the device fill the
    GPS one (which is automatic), printed in a submitted report as "Village: Barpali / District:
    Bargarh / State: Odisha / Address: <a street in Kharagpur, West Bengal>". Only-fill-blanks then
    made it permanent: no re-pick could clear it, and nothing on the page said the value was
    machine-derived.
    """
    no_stated_address = _artisan_row(
        address=None,
        location=_location_row(address="Plot 14, campus road, Kharagpur"),
    )
    data = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"},
        rows={"artisan": [no_stated_address]},
    )
    assert "address" not in data, (
        f"the desk's address reached the participant roster as {data.get('address')!r}"
    )
    assert "Kharagpur" not in str(data), (
        "nothing derived from the device fix may cross — placeName is refused for the same reason"
    )
    # The three STATED columns beside it still do cross, which is the half that must not be lost.
    assert data["village"] == "Barpali"
    assert data["district"] == "Bargarh"
    assert data["state"] == "Odisha"

    # And the artisan's OWN address is unaffected: it is a stated column and it still carries.
    stated = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"}, rows={"artisan": [_artisan_row()]},
    )
    assert stated["address"] == "House 4, Weavers' lane"


# --------------------------------------------------------------------------------------
# The round trip: a fully populated record through the REAL hydrate_entries
# --------------------------------------------------------------------------------------


def _field(entity_key: str, field_key: str):
    entity = next(e for _s, e in all_entities() if e.key == entity_key)
    spec = entity.field(field_key)
    assert spec is not None, f"{entity_key}.{field_key} is not in the registry"
    return spec


def _entity(entity_key: str):
    return next(e for _s, e in all_entities() if e.key == entity_key)


def _location_row(**overrides):
    fields = dict(
        state="Odisha", district="Bargarh", village="Barpali", pincode="768029",
        # A DESK-SHAPED VALUE ON PURPOSE. This used to read "Weavers' lane, near the tank" — a
        # subject-shaped address on a PROVENANCE row — which made every assertion about it read
        # as though the two groups of Location columns said the same kind of thing. `placeName`
        # below is Kharagpur against an Odisha village, ~1,500 km, which is the state of every
        # live row and is the whole reason neither of these two crosses.
        address="Plot 14, campus road, Kharagpur", placeName="Kharagpur",
        subjectLatitude=21.2, subjectLongitude=83.6,
        latitude=22.314, longitude=87.311, altitude=32.0, accuracy=26.0,
        capturedAt=datetime(2025, 3, 12, 9, 0),
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


def _artisan_row(**overrides):
    fields = dict(
        id="art_1", name="Latha Devi", localName="ଲତା ଦେବୀ", gender="Female",
        phone="9876500001", email="latha@example.org", place="Barpali",
        aadhaarNumber="234567890123", pehchanCardAvailable=True,
        pehchanCardNumber="PEHCHAN5678", address="House 4, Weavers' lane",
        notes="Prefers morning sessions.", dos="1. Speak in Odia\n2. Show samples",
        donts="1. Do not photograph the loom shed",
        extraMetadata={"experienceYears": 22, "age": 44},
        # DEFAULTED TO None so this row is the LEGACY case: no columns, values only in metadata.
        # That keeps every assertion written before the columns existed meaningful — they are now
        # assertions that the fallback still works for the old records, which is the half of the
        # old behaviour that had to survive. A row WITH the columns is built explicitly by the test
        # that covers precedence.
        dateOfBirth=None, experienceYears=None,
        recordedAt=datetime(2025, 3, 12, 9, 0), recordedTimezone="Asia/Kolkata",
        craft=SimpleNamespace(name="Sambalpuri Ikat"), location=_location_row(),
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


def _product_row(**overrides):
    fields = dict(
        id="prd_1", craftName="Sambalpuri Ikat", place="Barpali", artisanName="Latha Devi",
        productName="Sambalpuri saree", localName="ବନ୍ଧ ଶାଢ଼ୀ", productType="SAMPLE",
        timeTakenToCompleteProduct="about three weeks", size="6 yards, single width",
        lengthInches=216, breadthInches=45, heightInches=2,
        costOfMaking=2600, sellingPrice=4500, marketDemand="HIGH",
        rawMaterialsUsed="Cotton yarn", mainToolsUsed="Pit loom, bobbin winder",
        productFunctionUse="Daily and festive wear", remarks="Second-quality weft in one panel.",
        recordedAt=datetime(2025, 4, 2, 11, 0),
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


def _tool_row(**overrides):
    fields = dict(
        id="tul_1", craftName="Sambalpuri Ikat", place="Barpali", artisanName="Latha Devi",
        toolkitName="Pit loom", localName="ଖଡ଼ି", englishName="Pit treadle loom",
        processUsedIn="Weaving", material="Teak and bamboo", yearsInUse=18,
        height=180, width=120, lengthInches=96, breadthInches=48,
        thickness=4.5, weight=95, radius=6,
        maker="CARPENTER", traditionType="HYBRID", replacementCost=12000,
        suggestionsForToolImprovement="A higher bench would ease the back.",
        remarks="Rebuilt in 2019.", recordedAt=datetime(2025, 4, 3, 8, 30),
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


def _process_row(**overrides):
    fields = dict(
        id="prc_1", name="Tie and dye", notes="Yarn is tied in sections, dyed, untied, washed.",
        preProcessAvailable=True, recordedAt=datetime(2025, 4, 4, 7, 0),
        product=SimpleNamespace(productName="Sambalpuri saree"),
        steps=[
            SimpleNamespace(name="Washing", stepType="SEQUENTIAL", sortOrder=2, notes=None),
            SimpleNamespace(name="Tying", stepType="SEQUENTIAL", sortOrder=0,
                            notes="Cotton thread, section by section"),
            SimpleNamespace(name="Dyeing", stepType="GROUP", sortOrder=1, notes=None),
        ],
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


def _craft_row(**overrides):
    """The fifth reference model's row fixture, and the one that was missing.

    `Craft` carries two columns and both print on the COVER PAGE of every submitted report, which
    made it the highest-visibility carry of the five and the only one with no database-free round
    trip: `craftLocalName` was asserted by no backend test at all, and Craft's only executing round
    trip lived in `test_reference_resolver.py` behind a Postgres skip that nobody's machine
    satisfies. See `test_a_documented_craft_reaches_the_cover_page_whole`.
    """
    fields = dict(id="crf_1", name="Sambalpuri Ikat", localName="ସମ୍ବଲପୁରୀ ବନ୍ଧ")
    fields.update(overrides)
    return SimpleNamespace(**fields)


class _Delegate:
    def __init__(self, rows):
        self._rows = rows

    async def find_many(self, where=None, include=None):
        wanted = set((where or {}).get("id", {}).get("in", []))
        return [r for r in self._rows if r.id in wanted]


class _FakeDb:
    """Enough Prisma client for ``hydrate_entries``: one delegate per model and ``query_raw``.

    Driving the REAL function rather than reimplementing its copy loop is the whole point: the
    clearing rule, the only-fill-blanks rule and ``coerce_value`` are what decide whether a carried
    value survives, and a test that skipped them would pass while the feature was broken.
    """

    def __init__(self, rows_by_delegate, photos):
        self._rows = rows_by_delegate
        self._photos = photos

    def __getattr__(self, name):
        return _Delegate(self._rows.get(name, []))

    async def query_raw(self, _sql, ids, *_binds):
        # ``*_binds`` AND NOT A FIXED ARITY, because pinning the call shape here is a cost paid by
        # tests that have nothing to say about it: binding the measurement-grid marker as `$2` — the
        # tidier form of the interpolation `_reference_photos` guards instead, and the one its guard
        # says to move to — turned eighteen hydration tests red on the argument count alone. Two
        # more stubs in `test_entry_provenance.py` are still on the fixed shape and are why that
        # move has not happened. What a bind MEANS is `_CapturingDb`'s job; here the rows come from
        # `photos` and the statement is never read.
        return [
            {"parent": parent, "id": media_id, "caption": caption}
            for parent, (media_id, caption) in self._photos.items()
            if parent in ids
        ]


async def _hydrate(monkeypatch, entity_key, sent, *, rows, photos=None, previous=None):
    """Run one entry through the real hydration and return the stored ``data``."""
    monkeypatch.setattr(dw, "db", _FakeDb(rows, photos or {}))
    entry = dw.PendingEntry(
        entity=_entity(entity_key), data=dict(sent), previous=dict(previous or {}),
        row_id=None, ordinal=0, client_key="k1",
    )
    await dw.hydrate_entries([entry])
    return entry.data


def _targets(path: str) -> set[str]:
    return set(REFERENCE_HYDRATION[path].values())


async def test_a_fully_documented_artisan_arrives_whole(monkeypatch):
    """EVERY declared target of ``participant.artisanRef`` is non-empty. That is the requirement.

    Written as "no target is missing" rather than as twenty-two individual assertions so that a
    pair added to the mapping is covered the moment it is added, without anybody remembering to
    extend this test.
    """
    data = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"},
        rows={"artisan": [_artisan_row()]},
        photos={"art_1": ("med_1", "At her loom, Barpali")},
    )
    blank = sorted(t for t in _targets("participant.artisanRef") if not data.get(t))
    assert blank == [], f"a fully documented artisan left these boxes empty: {blank}"

    # And the values are the record's, not merely present.
    assert data["name"] == "Latha Devi"
    assert data["email"] == "latha@example.org"
    assert data["artisanCardNo"] == "XXXX XXXX 5678"
    assert data["pehchanCardAvailable"] is True
    assert data["district"] == "Bargarh" and data["state"] == "Odisha"
    assert data["pincode"] == "768029"
    assert data["address"] == "House 4, Weavers' lane"
    assert data["subjectLocation"] == {"lat": 21.2, "lon": 83.6}
    assert data["dos"].startswith("1. Speak in Odia")
    assert data["recordNotes"] == "Prefers morning sessions."
    assert data["documentedOn"] == "2025-03-12"
    assert data["photo"] == "med_1"
    assert data["photoCaption"] == "At her loom, Barpali"


async def test_a_fully_documented_product_arrives_whole_and_in_centimetres(monkeypatch):
    data = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row()]},
        photos={"prd_1": ("med_2", "Front, natural light")},
    )
    # `category` is the one declared target a SAMPLE cannot fill — that refusal is the subject of
    # its own test below, and it is what `recordType` exists to compensate for.
    blank = sorted(t for t in _targets("existingProduct.productRef")
                   if not data.get(t) and t != "category")
    assert blank == [], f"a fully documented product left these boxes empty: {blank}"

    assert data["lengthCm"] == pytest.approx(548.64)   # 216 in
    assert data["widthCm"] == pytest.approx(114.3)     # 45 in, breadth -> width
    assert data["heightCm"] == pytest.approx(5.08)
    assert data["price"] == "4500.00" and data["costOfMaking"] == "2600.00"
    assert data["marketDemand"] == "HIGH"
    assert data["recordType"] == "SAMPLE"
    assert data["dimensionsNote"] == "6 yards, single width"
    assert data["productionTimeNote"] == "about three weeks"
    assert data["productPhotos"] == ["med_2"]
    assert data["productPhotosCaption"] == "Front, natural light"


async def test_a_product_type_with_no_honest_category_leaves_the_category_blank(monkeypatch):
    """The decision the in-code comment argues for, still holding after the widening.

    A SAMPLE is not a kind of product; it is a saree that happens not to be for sale. Guessing
    would fill a ministry report's Category column with plausible wrong values. The record's own
    answer is not lost — it lands on ``recordType``, which is the whole reason that box exists.
    """
    data = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row(productType="FINISHED_GOOD")]},
    )
    assert "category" not in data
    assert data["recordType"] == "FINISHED_GOOD"

    mapped = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row(productType="PACKAGING")]},
    )
    assert mapped["category"] == "PACKAGING" and mapped["recordType"] == "PACKAGING"


async def test_an_enum_member_that_is_only_ever_a_form_default_is_not_carried_as_an_answer(
    monkeypatch,
):
    """"THE RESEARCHER CHOSE THIS" AND "NOBODY WAS ASKED" WERE THE SAME STORED TOKEN.

    Three Prisma columns declare a member as their `@default` and no record form offers a blank
    alternative: `productType ProductType @default(OTHER)`, `marketDemand MarketDemand
    @default(UNKNOWN)` and `maker MakerType @default(UNKNOWN)`. `ProductForm.tsx` and `ToolForm.tsx`
    render each select with `defaultValue={initial?.x ?? "<DEFAULT>"}` over a list with no empty
    member and submit `requiredText(form, "x") || "<DEFAULT>"`; the handset builds the same
    dropdowns with `includeNone = false`. So an untouched form stores the token and nothing can
    separate it from a deliberate pick.

    Two of the three PRINT. `existingProduct.category` is one of the six columns the Existing
    products table lays out, so every uncategorised product asserted "Other" in a ministry report;
    `existingProduct.marketDemand` has the DEFAULT report_role — KEY_VALUE — so "Market demand: Not
    known" stood in the per-row extras beneath that table. Only-fill-blanks then made both look
    answered to the designer as well.

    The record's own answer is not lost: `existingProduct.recordType` still receives OTHER through
    `_PRODUCT_TYPE_TO_MEMBER`, which is the entire reason that box exists.
    """
    untouched = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [
            _product_row(productType="OTHER", marketDemand="UNKNOWN")
        ]},
    )
    assert "category" not in untouched, (
        f"an unanswered product type asserted a category: {untouched.get('category')!r}"
    )
    assert "marketDemand" not in untouched, (
        f"an unanswered market demand arrived as {untouched.get('marketDemand')!r}"
    )
    assert untouched["recordType"] == "OTHER", "the record's own answer must still cross"

    untouched_tool = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"},
        rows={"tooldocumentation": [_tool_row(maker="UNKNOWN")]},
    )
    assert "maker" not in untouched_tool, (
        f"an unanswered maker arrived as {untouched_tool.get('maker')!r}"
    )

    # AND THE OTHER HALF, which is what stops this becoming "blank everything vague". A token a
    # researcher had to open the dropdown to reach is an answer and still crosses — including
    # MakerType.OTHER, which reads like MarketDemand.UNKNOWN and is nothing like it, because
    # nothing on the tool form defaults to it.
    chosen = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [
            _product_row(productType="PACKAGING", marketDemand="SEASONAL")
        ]},
    )
    assert chosen["category"] == "PACKAGING" and chosen["marketDemand"] == "SEASONAL"
    chosen_tool = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"},
        rows={"tooldocumentation": [_tool_row(maker="OTHER")]},
    )
    assert chosen_tool["maker"] == "OTHER"


async def test_a_fully_documented_tool_arrives_whole(monkeypatch):
    data = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"},
        rows={"tooldocumentation": [_tool_row()]},
        photos={"tul_1": ("med_3", "The loom in the shed")},
    )
    blank = sorted(t for t in _targets("tool.toolRef") if not data.get(t))
    assert blank == [], f"a fully documented tool left these boxes empty: {blank}"

    assert data["maker"] == "CARPENTER"
    assert data["traditionType"] == "TRANSITIONAL", "HYBRID must be translated, not dropped"
    assert data["lengthCm"] == pytest.approx(243.84)
    assert data["breadthCm"] == pytest.approx(121.92)
    assert data["heightAsRecorded"] == 180 and data["weightAsRecorded"] == 95
    assert data["yearsInUse"] == 18
    assert data["photoCaption"] == "The loom in the shed"


async def test_where_a_tool_was_obtained_is_not_guessed_from_who_made_it(monkeypatch):
    """``tool.source`` promised a carry it never had. It now promises nothing and delivers nothing.

    Demoted rather than mapped: ``ToolDocumentation`` has no column for where a tool was obtained,
    and answering "where obtained: carpenter" from ``maker`` would be a plausible wrong sentence in
    a submitted report.
    """
    from app.services.stage_definitions import FROM_REF

    assert FROM_REF not in _field("tool", "source").help
    assert "source" not in _targets("tool.toolRef")

    data = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"}, rows={"tooldocumentation": [_tool_row()]}
    )
    assert "source" not in data


async def test_a_documented_process_reaches_the_overview_whole_including_its_sub_steps(
    monkeypatch,
):
    """THE SUB-STEPS REACHED NOTHING ANYWHERE, and this is where they land.

    ``processStep.processRef`` refuses ``steps`` and ``preProcessAvailable`` for good reasons —
    a whole sequence printed inside one of its own steps, repeated on every row naming the same
    process. Those reasons are about the ROW. The stage-5 singleton is one per workshop, so the
    sequence prints once, above the steps table.
    """
    data = await _hydrate(
        monkeypatch, "traditionalProcess", {"processRef": "prc_1"},
        rows={"process": [_process_row()]},
    )
    blank = sorted(t for t in _targets("traditionalProcess.processRef") if not data.get(t))
    assert blank == [], f"a fully documented process left these boxes empty: {blank}"

    assert data["documentedProcessName"] == "Tie and dye"
    assert data["documentedFor"] == "Sambalpuri saree"
    assert data["preProcessAvailable"] is True
    lines = data["documentedSteps"].split("\n")
    assert lines == [
        "1. Tying — Cotton thread, section by section",
        "2. Dyeing (group)",
        "3. Washing",
    ], "the sub-steps must arrive in sortOrder, numbered by position, with notes and group marks"


def test_a_step_with_two_notes_is_still_one_bullet_in_the_report():
    """THREE DOCUMENTED STEPS USED TO PRINT AS FIVE BULLETS, TWO OF THEM UNNUMBERED.

    `documentedSteps` is a LONG_TEXT with `report_role=BULLETS`, and the renderer's pre-promotion
    path for one of those replaces every semicolon with a newline and then splits on newlines,
    one bullet per piece; the Android report screen is a port of the same line. `_step_lines`
    embedded the note verbatim and `.strip()` cannot touch a newline INSIDE a string, so a step
    whose note came from `MultiNoteInput` — which joins a researcher's several notes with a
    blank line, on the web form and on the handset alike — split at the blank line and printed
    its second note as its own bullet, with no number in
    front of it. `DocumentBuilder.bullets` drops the empty item between them, so there was not even
    a gap to notice. A ministry officer counted more steps than the researcher documented.

    THE ASSERTION THAT MATTERS IS THE LAST ONE: it re-runs the renderer's own split over the value
    and requires the count to be unchanged. Anything else would pass while the report still broke.
    """
    process = _process_row(steps=[
        SimpleNamespace(name="Tying", stepType="SEQUENTIAL", sortOrder=0,
                        notes="Use cotton thread\n\nKnots must be tight"),
        SimpleNamespace(name="Dyeing", stepType="GROUP", sortOrder=1, notes=None),
        SimpleNamespace(name="Washing", stepType="SEQUENTIAL", sortOrder=2,
                        notes="Rinse in the tank; then dry in shade"),
    ])
    value = dw._step_lines(process)
    lines = value.split("\n")
    assert lines == [
        "1. Tying — Use cotton thread · Knots must be tight",
        "2. Dyeing (group)",
        "3. Washing — Rinse in the tank · then dry in shade",
    ], "one documented step must be one line, whatever its note contains"

    rendered = [piece.strip() for piece in value.replace(";", "\n").split("\n")]
    assert len([piece for piece in rendered if piece]) == 3, (
        f"report_builder would print {rendered} — a bullet per piece, and the unnumbered ones read "
        "to a ministry officer as steps in the sequence"
    )


async def test_the_overview_narrative_the_designer_writes_is_never_overwritten(monkeypatch):
    """``processOverview`` is the DESIGNER's required narrative about what they observed.

    The process record's notes are what a researcher wrote months earlier somewhere else. Landing
    the second in the first would put two authors in one box on every workshop whose designer had
    not typed yet, and no reader of the .docx could tell which they were reading.
    """
    assert "processOverview" not in _targets("traditionalProcess.processRef")
    data = await _hydrate(
        monkeypatch, "traditionalProcess", {"processRef": "prc_1"},
        rows={"process": [_process_row()]},
    )
    assert "processOverview" not in data


async def test_a_process_step_row_still_receives_only_the_three_it_should(monkeypatch):
    """The narrow mapping stays narrow. Widening it re-creates the defect the singleton avoids."""
    assert _targets("processStep.processRef") == {"name", "description", "documentedFor"}
    data = await _hydrate(
        monkeypatch, "processStep", {"stepNumber": 1, "processRef": "prc_1"},
        rows={"process": [_process_row()]},
    )
    assert "documentedSteps" not in data and "preProcessAvailable" not in data


async def test_a_documented_craft_reaches_the_cover_page_whole(monkeypatch):
    """THE COVER PAGE'S CARRY WAS THE ONE NOBODY EXECUTED.

    `workshopSetup.craftName` is `required=True` and `report_role=COVER`, and `craftLocalName` is
    COVER beside it — the craft's name in the local script, printed on the front of every document
    handed to a ministry officer. Both were pinned only by STATIC checks: the mapping table, the
    `fromref` marker and the browser mirror. Nothing executed them. So a `coerce_value` change, a
    `_reference_data` change or a rename of the target would have left the whole developer-machine
    suite green while the local-script name went blank on the cover — and the more severe variant
    is a strict save refused on stage 1 for a box the picker was supposed to fill.

    Written in the same generic shape as its four siblings, so a pair added to the mapping is
    covered the moment it is added.

    WHAT IT PROVES AND WHAT IT DOES NOT, because "it passed" means less here than elsewhere in this
    file. It pins no behaviour this lane INTRODUCED — the craft carry already worked — so reverting
    any source change made alongside it leaves this green. What it does is execute a path that was
    only ever checked statically: its negative control is on the declarations it reads, and
    deleting the ``craftLocalName`` pair from ``REFERENCE_HYDRATION["workshopSetup.craftRef"]``, or
    renaming the target field, fails it here rather than on a ministry's cover page. Do not
    "strengthen" it by asserting on something this lane changed; it is a coverage test and saying so
    is the honest version.
    """
    data = await _hydrate(
        monkeypatch, "workshopSetup", {"craftRef": "crf_1"}, rows={"craft": [_craft_row()]},
    )
    blank = sorted(t for t in _targets("workshopSetup.craftRef") if not data.get(t))
    assert blank == [], f"a documented craft left these cover-page boxes empty: {blank}"

    assert data["craftName"] == "Sambalpuri Ikat"
    assert data["craftLocalName"] == "ସମ୍ବଲପୁରୀ ବନ୍ଧ"


async def test_re_pointing_at_a_thinly_documented_record_clears_all_of_the_new_fields(
    monkeypatch,
):
    """THE WIDENING MULTIPLIES THE COST OF THE HALF-REWRITE BUG BY THREE.

    ``hydrate_entries`` clears every mapped single-value target before copying, because a row that
    named artisan A and now names artisan B must not keep A's phone, face and village. That rule
    was written when the mapping held eight pairs; it now holds twenty-two, so a regression would
    strand fourteen more of one person's answers under another person's name. Asserted against a
    record that answers almost nothing, which is the only shape that exposes it.
    """
    thin = _artisan_row(
        id="art_2", name="Sita Bai", localName=None, gender=None, phone=None, email=None,
        aadhaarNumber=None, pehchanCardNumber=None, address=None, notes=None, dos=None,
        donts=None, extraMetadata={}, craft=None, location=None, place="Kutch",
    )
    filled = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"},
        rows={"artisan": [_artisan_row(), thin]},
        photos={"art_1": ("med_1", "At her loom")},
    )
    assert filled["phone"] and filled["photo"]

    re_pointed = await _hydrate(
        monkeypatch, "participant", {**filled, "artisanRef": "art_2"},
        rows={"artisan": [_artisan_row(), thin]},
        photos={"art_1": ("med_1", "At her loom")},
        previous={"artisanRef": "art_1"},
    )
    assert re_pointed["name"] == "Sita Bai"
    assert re_pointed["village"] == "Kutch", "what the new record DOES say still overwrites"
    stale = sorted(
        t for t in _targets("participant.artisanRef")
        if t not in {"name", "village", "pehchanCardAvailable", "documentedOn"}
        and re_pointed.get(t)
    )
    assert stale == [], f"these still hold the previous artisan's answers: {stale}"


async def test_the_designers_own_answers_survive_the_widening(monkeypatch):
    """Only-fill-blanks, across twenty-two pairs instead of eight.

    A picker that reverted a correction on every save would be worse than retyping, because the
    designer watches the value change back and has no way to make it stick. The widening triples
    the number of boxes that rule protects.
    """
    typed = {
        "artisanRef": "art_1", "name": "Latha (Ammaji)", "email": "ammaji@example.org",
        "district": "Bolangir", "dos": "1. Ask before recording",
    }
    data = await _hydrate(
        monkeypatch, "participant", typed, rows={"artisan": [_artisan_row()]},
        previous={"artisanRef": "art_1"},
    )
    for key, value in typed.items():
        assert data[key] == value, f"{key} was reverted to the record's answer"
    assert data["state"] == "Odisha", "the blanks are still filled in"


# --------------------------------------------------------------------------------------
# The photograph's caption, which used to stop at the join
# --------------------------------------------------------------------------------------


def test_every_carried_photograph_carries_its_caption():
    """A gallery without its caption asks the designer to describe a photograph they never took.

    Each of the three models with a ``media_field`` must map BOTH halves, and the caption target
    must be the registry's declared caption for that gallery — a caption landing anywhere else
    would print detached from its picture.
    """
    for path, photo_target in (
        ("participant.artisanRef", "photo"),
        ("tool.toolRef", "photo"),
        ("existingProduct.productRef", "productPhotos"),
    ):
        entity_key = path.partition(".")[0]
        mapping = REFERENCE_HYDRATION[path]
        assert mapping.get("photo") == photo_target
        caption_target = mapping.get("photoCaption")
        assert caption_target, f"{path} carries a photograph and not its caption"
        spec = _field(entity_key, caption_target)
        assert spec.caption_for == photo_target, (
            f"{entity_key}.{caption_target} is not declared as the caption of {photo_target}"
        )


class _CapturingDb:
    """Enough Prisma client to see the statement ``_reference_photos`` actually sends.

    THE ASSERTION HAS TO BE ON THE SQL AND NOT ON A RESULT, and that is worth saying out loud.
    Which MediaFile row wins is decided by Postgres — ``DISTINCT ON`` over a computed sort key —
    and there is no database on a developer machine. Re-implementing the selection in Python to
    "test" it would test the re-implementation, which is exactly the toothless shape this file
    warns about elsewhere. So what is pinned here is the CONTRACT the statement expresses: the
    marker the three uploading clients write, the transitional clause for rows written before it,
    and — the part that is easy to get wrong — that the exclusion is a SORT KEY and not a WHERE,
    so a record whose only image is a grid shot still gets a picture.

    WHAT THIS CANNOT SEE, STATED SO NOBODY READS MORE INTO A GREEN RUN THAN IS THERE. ``query_raw``
    records the string and answers ``[]``; nothing here parses it. As of 2026-08-20 the statement
    has NEVER been executed against a Postgres by any suite on this machine, and the reason is NOT
    that the DB-backed tests are skipped — this paragraph said that, and it is the comfortable
    version. ``backend/.env`` names ``127.0.0.1:55442``, ``conftest`` publishes it and prints
    "database: local DSN resolved — database-backed tests WILL run", and
    ``test_reference_resolver.py``'s ``pytestmark`` skips only a NON-local DSN — so those tests are
    SELECTED and then fail to connect, because the compose stack is down. Either way nothing sends
    this SQL to a server, and a syntax error, a bad CTE/``DISTINCT ON`` interaction or a type error
    in the boolean expression would ship with this file green.

    It was read closely instead: ``extraMetadata`` is JSONB in the init
    migration so ``->>`` on a non-object answers NULL rather than raising, ``originalFilename`` is
    NOT NULL so ``is_grid`` can never be NULL and sort ahead of a real candidate on NULLS LAST, and
    ``DISTINCT ON`` permits ordering by a CTE column absent from the select list. Reading is the
    whole of the evidence. Replace this paragraph with "executed on <date> against <version>" the
    first time an ``EXPLAIN`` of it runs, and not before.

    ``*args`` rather than a fixed arity, and ``sql`` starts EMPTY on purpose: the marker guard in
    ``_reference_photos`` is asserted by proving no statement was sent at all, so a stub that
    recorded nothing until the call succeeded could not tell "refused" from "sent and ignored".
    """

    def __init__(self):
        self.sql = ""
        self.args = ()

    async def query_raw(self, sql, *args):
        self.sql = sql
        self.args = args
        return []


@pytest.mark.parametrize(
    ("model", "column"),
    [("ToolDocumentation", "toolId"), ("ProductDocumentation", "productId")],
)
async def test_the_measurement_grid_shot_is_not_the_records_photograph(monkeypatch, model, column):
    """THE MINISTRY REPORT PRINTED A SHEET OF GRAPH PAPER CAPTIONED AS THE TOOL.

    ``_reference_photos`` picks the OLDEST IMAGE on the parent, and its docstring justified the
    ``createdAt, id`` tiebreak only as STABILITY — it never claimed the row was a picture of the
    subject. Both record forms await their grid-measurement uploads FIRST, before the numbered
    process captures and before the batch of field photographs, so on any measured product or tool
    the oldest image is deterministically the calibration frame. That id and its caption are what
    ``photo``/``photoCaption`` carried, into ``tool.photo`` and into ``existingProduct.productPhotos``
    — both declared ``report_role=GALLERY`` — so the .docx handed to an officer showed a ruled
    measurement sheet captioned "Length & breadth grid (measurement) for Pit loom" while the
    catalogue photographs sat one row later in the same table, unused.

    ``PRODUCT_NOT_CARRIED["measurementImageId"]`` claimed the opposite in this very file ("The
    catalogue shot is carried through _reference_photos"), which is why nobody looked: refusing the
    column achieved nothing while the same image reached the same gallery by the path the refusal
    named as safe.

    THIS QUERY IS ALSO ON THE RENDER PATH, so the fix is not confined to rows saved after it:
    ``load_report_references`` calls it through ``_load_one_reference_model`` and
    ``ReferencedRecord.photo`` re-resolves on every generate. A row hydrated BEFORE the fix keeps
    the frozen grid id in its gallery while the reference now answers the catalogue id, and
    ``report_builder.ReportBuilder._images`` dedupes by media id, so that row prints BOTH. Pinning
    that pairing is report_builder's to do — see ``_reference_photos``' docstring, which names it
    as an open decision rather than a surprise.
    """
    fake = _CapturingDb()
    monkeypatch.setattr(dw, "db", fake)
    await dw._reference_photos(dw.REFERENCE_MODELS[model], ["rec_1"])
    sql = fake.sql

    assert f'm."{column}"' in sql, "the statement must still group by this model's parent key"

    # 1. THE MARKER, WHICH IS A CONTRACT WITH THREE UPLOADING CLIENTS. The two web record forms and
    # the handset's grid section write extraMetadata.purpose = "MEASUREMENT_GRID"; if this spelling
    # is ever "tidied" on one side the report silently goes back to printing the grid sheet.
    assert dw.MEASUREMENT_GRID_PURPOSE == "MEASUREMENT_GRID"
    assert '"extraMetadata"' in sql and "'purpose'" in sql
    assert "'MEASUREMENT_GRID'" in sql

    # 1b. AND IT IS INTERPOLATED INTO RAW SQL, WHICH IS WHY IT IS VETTED FIRST. The statement was
    # interpolating two values and validating one — the column name through `_PHOTO_PARENT_COLUMNS`,
    # the marker not at all — and "it is a module constant" is a property of today's code that no
    # test held. This pins the guard: a marker carrying a quote must never reach the statement,
    # whatever future call site supplies it.
    with pytest.raises(ValueError, match="measurement-grid marker"):
        bad = _CapturingDb()
        monkeypatch.setattr(dw, "db", bad)
        monkeypatch.setattr(dw, "MEASUREMENT_GRID_PURPOSE", "GRID' OR TRUE--")
        await dw._reference_photos(dw.REFERENCE_MODELS[model], ["rec_1"])
    assert bad.sql == "", "the unvetted marker reached the statement before the guard ran"

    # 2. THE TRANSITIONAL CLAUSE, for the rows already in the table when the marker shipped. A
    # caption or a filename is a string a researcher can also type, which is why it is the fallback
    # and not the rule — and why it may be deleted once the pre-marker rows are gone.
    assert "grid (measurement) for" in sql
    assert "'grid-%'" in sql and "'measure-grid-%'" in sql

    # 3. NOT A `WHERE`. A product whose ONLY image is a grid shot must still get a picture rather
    # than a blank gallery, so nothing about the grid may narrow the candidate set.
    where = sql[sql.index("WHERE"):sql.index(") SELECT DISTINCT ON (parent)")]
    assert "MEASUREMENT_GRID" not in where and "grid-" not in where and "$2" not in where, (
        f"the grid rule leaked into the WHERE clause, so a grid-only record now hydrates a blank "
        f"gallery: {where}"
    )
    assert "is_grid" not in where, f"the computed flag must stay a sort key: {where}"

    # 4. AND THE DIRECTION, WHICH IS THE ENTIRE FIX AND THE ONE THING POSITION ALONE CANNOT PIN.
    # Postgres sorts false before true, so `is_grid ASC` is what puts the grid frame LAST. This
    # block used to assert only that is_grid outranked created_at and that the clause ended
    # "created_at ASC, id ASC" — both of which `is_grid DESC` also satisfies, and DESC is the exact
    # inversion that makes the graph paper win on every measured record deterministically, i.e. the
    # original defect promoted from an accident of upload order to a rule. The whole clause is
    # pinned rather than a substring, so the stability tiebreak this docstring argues for — two
    # renders of one report cannot swap photographs — is pinned in the same line.
    order = sql[sql.rindex("ORDER BY"):].strip()
    assert order == "ORDER BY parent, is_grid ASC, created_at ASC, id ASC", (
        f"the grid frame no longer sorts last, or the deterministic tiebreak is gone: {order}"
    )


def test_a_reviewers_verdict_is_visible_while_choosing_and_is_never_copied_onto_the_entry():
    """A REJECTED RECORD LOOKED EXACTLY LIKE AN APPROVED ONE IN THE PICKER.

    `reference_options` has no `status` clause, deliberately — the pooling philosophy documented
    above `records.viewable_where` is that every signed-in account may read every row, and a
    rejected tool's measurements are still the measurements that were recorded. What was missing was
    that the designer could not SEE the verdict while choosing, so a tool a reviewer had rejected as
    a duplicate hydrated all twenty-four of its fields into a document handed to a ministry officer
    with nothing on the page or in the picker to distinguish it.

    SHOWN, NOT CARRIED. `status` is mutable and a hydrated value is a permanent copy: a tool picked
    while PENDING is approved the following week, and a frozen "Pending review" would then be a
    false statement about a named reviewer's decision. The sublabel is recomposed on every picker
    call, so it is always the current verdict, and nothing about it is written onto the entry.
    """
    spec = dw.REFERENCE_MODELS["ToolDocumentation"]

    assert "Rejected" in spec.sublabel(_tool_row(status="REJECTED"))
    assert "Sent back" in spec.sublabel(_tool_row(status="NEEDS_REVISION"))
    assert "Awaiting review" in spec.sublabel(_tool_row(status="PENDING"))

    # APPROVED is the ordinary case and says nothing: a badge on every row would hide the four
    # rows that are not approved, which is the whole point of the badge.
    approved = spec.sublabel(_tool_row(status="APPROVED"))
    assert approved == spec.sublabel(_tool_row()), "an approved record must read as it always did"
    assert "Rejected" not in approved and "review" not in approved

    # The verdict reaches the picker and nothing else: it is not a key of the carried data, and
    # `status` stays in every model's NOT_CARRIED list.
    assert "status" not in dw.REFERENCE_MODELS["ToolDocumentation"].data(_tool_row(), None)
    assert "REJECTED" not in str(
        dw.REFERENCE_MODELS["ToolDocumentation"].data(_tool_row(status="REJECTED"), None)
    )


def test_no_reference_model_joins_a_relation_none_of_its_lambdas_reads():
    """A JOIN PER PICKER KEYSTROKE FOR A VALUE NOBODY LOOKS AT, ON A ~756 ms LINK.

    `ProductDocumentation` and `ToolDocumentation` both declared `include={"artisan": True}` while
    their label, sublabel and data lambdas read only the denormalised scalars (`r.artisanName`,
    `r.craftName`, `r.place`) — there is no `_rel(r, "artisan", …)` anywhere in either. The include
    was issued on every `reference_options` call, every `hydrate_entries` save and every
    `load_report_references`, and discarded.

    THE OBVIOUS "FIX" IS THE DANGEROUS ONE AND THIS TEST PINS AGAINST IT TOO. Swapping in
    `include={"location": True}` — because `_reference_place` reads `row.location` — would change
    the place string PRINTED IN DOCUMENTS ALREADY SUBMITTED: `_reference_place` prefers
    `location.village` over the free-text `place`, and `load_report_references` runs at REPORT time,
    not at save time. That is the one thing the never-re-resolve rule exists to forbid.
    """
    assert dw.REFERENCE_MODELS["ProductDocumentation"].include == {}
    assert dw.REFERENCE_MODELS["ToolDocumentation"].include == {}

    # The two that DO declare one are read, which is what makes the rule a rule and not a ban.
    assert dw.REFERENCE_MODELS["Artisan"].include == {"craft": True, "location": True}
    assert dw.REFERENCE_MODELS["Process"].include == {"product": True, "steps": True}


def test_the_photograph_lookup_carries_nothing_a_media_entitlement_would_gate():
    """``ReferencePhoto`` holds an id and a caption and must not grow a URL or an object key.

    ``records._MEDIA_TAKEABLE_KEYS`` withholds ``url``/``objectKey``/transcripts behind an
    entitlement, and ``s3.public_url_for_key`` makes the key equivalent to the file. The workshop
    resolves its own media through ``media_resolver``, which applies that gate; a URL smuggled
    along this path would go round it.
    """
    assert {f.name for f in dw.ReferencePhoto.__dataclass_fields__.values()} == {"id", "caption"}


# --------------------------------------------------------------------------------------
# What the widening must not have broken
# --------------------------------------------------------------------------------------


# ──────────────────────────────────────────────────────────────────────────────────────────────
# THE FORMATTED-PROSE COLUMN, WHICH IS A JSON DOCUMENT IN A ``String?`` BOX
# ──────────────────────────────────────────────────────────────────────────────────────────────
#
# Eight of the columns the data lambdas read accept rich text and store it, as JSON, inside the
# ``String?`` column that used to hold a paragraph — ``rich_text``'s module banner lists them and
# ``records.prose_contains`` exists because the search had to be taught the same thing. Every
# hydration target opposite one of them is a TEXT or LONG_TEXT box, ``coerce_value``'s text branch
# passes a string through ``clean_text`` unchanged, and ``report_builder.format_value`` only unwraps
# a document for a RICH_TEXT field, where the value is a dict.
#
# So the JSON was copied onto the stage entry verbatim and printed verbatim — a
# ``{"blocks": …}`` string in a table column of a document submitted to a ministry — and every
# emptiness check upstream read that JSON-shaped string as a filled field, so nothing anywhere
# reported a problem. ``design_workshops._reference_data`` is the flattening; these are its guards.


def _formatted(*paragraphs: str) -> str:
    """A column value exactly as a record form's rich-text editor writes a FORMATTED one.

    THE FIRST SPAN CARRIES A MARK, AND WITHOUT IT THIS HELPER TESTS NOTHING. ``to_stored_text``
    stores an UNFORMATTED document as ordinary prose — ``_is_unformatted`` is a whitelist of
    "nothing interesting is set", and a document of bare paragraphs passes it — so a helper that
    built two plain paragraphs returned a plain string, and every assertion below that a value "is
    not JSON" held for a value that was never JSON in the first place. The bold run is what makes
    the encoder take the ``json.dumps`` branch, which is the state a researcher's column is in
    after they press **B**, and the only state in which the defect these tests exist for can
    happen at all.

    ``test_a_formatted_column_is_json_in_the_database_so_the_premise_holds`` is the guard on
    exactly that, and it is not decoration: it caught this helper being toothless.
    """
    from app.services import rich_text

    blocks = [
        {
            "kind": "PARAGRAPH",
            "spans": [{"text": text, "marks": ["BOLD"]} if index == 0 else {"text": text}],
        }
        for index, text in enumerate(paragraphs)
    ]
    stored = rich_text.to_stored_text(rich_text.from_json({"blocks": blocks}))
    assert stored is not None
    return stored


def test_a_formatted_column_is_json_in_the_database_so_the_premise_holds():
    """The tests below would be worthless if the storage were not what they claim it is."""
    from app.services import rich_text

    stored = _formatted("Tied with cotton thread", "then dipped in indigo.")
    assert stored.startswith("{")
    assert "blocks" in stored
    assert rich_text.stored_text_document(stored) is not None


@pytest.mark.parametrize(
    ("model", "delegate", "row_builder", "column", "entity_key", "ref_key", "target"),
    [
        # ``processStep.description`` is the worst of them: the registry labels it "What happens"
        # and it is a TABLE COLUMN of the traditional-process table.
        ("Process", "process", "_process_row", "notes",
         "processStep", "processRef", "description"),
        ("Process", "process", "_process_row", "notes",
         "traditionalProcess", "processRef", "documentedProcessNotes"),
        # ``existingProduct.material`` is a TABLE COLUMN too, 16% wide.
        ("ProductDocumentation", "productdocumentation", "_product_row", "rawMaterialsUsed",
         "existingProduct", "productRef", "material"),
        ("ProductDocumentation", "productdocumentation", "_product_row", "remarks",
         "existingProduct", "productRef", "remarks"),
        ("ProductDocumentation", "productdocumentation", "_product_row", "mainToolsUsed",
         "existingProduct", "productRef", "mainToolsUsed"),
        ("ProductDocumentation", "productdocumentation", "_product_row", "productFunctionUse",
         "existingProduct", "productRef", "use"),
        ("ToolDocumentation", "tooldocumentation", "_tool_row", "remarks",
         "tool", "toolRef", "remarks"),
        ("ToolDocumentation", "tooldocumentation", "_tool_row", "suggestionsForToolImprovement",
         "tool", "toolRef", "improvements"),
        ("Artisan", "artisan", "_artisan_row", "notes",
         "participant", "artisanRef", "recordNotes"),
    ],
)
async def test_a_formatted_record_column_arrives_as_prose_and_never_as_json(
    monkeypatch, model, delegate, row_builder, column, entity_key, ref_key, target
):
    """Every rich-text-capable source column, through the REAL hydration, into its real target.

    Parametrised over the pairs rather than written once, because the thing that goes wrong is a
    SINGLE pair being missed — and the fix is a wrapper over the whole payload precisely so that no
    pair can be. If a column is promoted to rich text later, add its row here; if the wrapper is
    ever narrowed to a named list of keys, one of these fails.
    """
    stored = _formatted("Tied with cotton thread", "then dipped in indigo.")
    row = globals()[row_builder](**{column: stored})
    sent = {ref_key: row.id}
    if entity_key == "processStep":
        sent["stepNumber"] = 1

    data = await _hydrate(monkeypatch, entity_key, sent, rows={delegate: [row]})

    landed = data.get(target)
    assert landed, f"{model}.{column} reached {entity_key}.{target} as nothing at all"
    assert "blocks" not in landed, (
        f"{model}.{column} reached {entity_key}.{target} as raw JSON: {landed!r}. "
        "design_workshops._reference_data is what flattens it; see its docstring."
    )
    assert landed == "Tied with cotton thread\nthen dipped in indigo."


def test_an_unformatted_column_leaves_the_flattening_boundary_untouched():
    """The identity half, and it is the half that decides whether the fix is safe to ship.

    ``plain_from_stored`` returns a plain string as the SAME OBJECT rather than round-tripping it
    through ``from_plain``/``to_plain``, which would strip each line, collapse runs of blank lines
    and drop trailing whitespace. Applying that to the repository would silently reformat every
    note, remark and address in it — a diff nobody asked for, across data this app's users are the
    custodians of rather than the authors of.

    ASSERTED AT ``_reference_data`` AND NOT AFTER ``hydrate_entries``, which is the correction this
    test needed. A hydrated value has also been through ``coerce_value``, whose text branch has
    normalised these columns since long before any of this — so "byte for byte" is a claim about
    the FLATTENING BOUNDARY and never was one about the stored entry. Asserting it downstream tests
    somebody else's behaviour and fails for a reason that has nothing to do with the fix, which is
    exactly what the first version of this test did.
    """
    prose = "Dyed twice.  Second dip is  longer.\n"
    row = _process_row(notes=prose)
    data = dw._reference_data(dw.REFERENCE_MODELS["Process"], row, None)
    assert data["notes"] == prose
    assert data["notes"] is row.notes, (
        "a plain string must come back as the same object, not as one that survived a round trip"
    )


async def test_the_subject_pin_is_not_flattened_into_a_string(monkeypatch):
    """THE REGRESSION THE FIX ITSELF NEARLY SHIPPED, and the reason its guard is one line long.

    ``rich_text.plain_from_stored`` treats a ``dict`` as a document it does not need to detect:
    ``to_plain({"lat": …, "lon": …})`` finds no ``blocks`` key, answers ``EMPTY``, and returns "".
    ``participant.subjectLocation`` is exactly such a dict — the pin a researcher dropped on the
    artisan's own place — so a wrapper applied to every value regardless of type would have blanked
    it on every pick, which is the same class of silent loss the wrapper was written to end.
    ``_reference_data`` therefore flattens ``str`` values and nothing else.
    """
    row = _artisan_row(notes=_formatted("Prefers the afternoon."))
    data = await _hydrate(
        monkeypatch, "participant", {"artisanRef": row.id}, rows={"artisan": [row]}
    )
    assert data["recordNotes"] == "Prefers the afternoon."
    assert isinstance(data.get("subjectLocation"), dict), (
        "the artisan's own pin must survive the prose flattening as the point it is"
    )
    assert data["subjectLocation"]["lat"]
    assert data["subjectLocation"]["lon"]


def test_no_new_table_column_was_added_to_a_table_whose_widths_are_already_full():
    """Six declared widths summing to 100 is a table already in submitted documents.

    ``report_builder._table_columns`` caps at six and falls back to proportional widths when the
    declared ones do not add up, which silently re-lays-out a table somebody has already printed
    and filed. Every field this lane added is KEY_VALUE for that reason, and the report prints
    those in the per-row block beneath the table, so nothing is lost by it.
    """
    for entity_key in ("participant", "tool", "processStep"):
        entity = _entity(entity_key)
        widths = [f.column_width_pct for f in entity.fields
                  if f.report_role.value == "TABLE_COLUMN" and not f.deprecated]
        assert sum(widths) <= 100.0 + 1e-6, (
            f"{entity_key}'s table column widths now sum to {sum(widths)}"
        )


# Entities whose declared TABLE_COLUMN widths do NOT govern their table today, with the sum they
# actually reach. ``_render_table`` uses the declared widths only when they add up to 100 (within
# half a point) and otherwise lays the table out proportionally — free text gets twice the share of
# a number or a date — so every percentage written beside a field of these five is a dead
# declaration that reads as a decision.
#
# Twenty-four of the twenty-nine tabular entities DO add up, which is what makes these five drift
# rather than design. Two of them are over: somebody added a column and did not rebalance.
#
# THEY ARE RECORDED RATHER THAN REBALANCED, and that is a deliberate refusal. Making a sum reach
# 100 does not tidy a number up — it switches a table from one layout to another, in documents that
# have been printed and filed, which is the exact consequence the note above ``participant``'s
# address fields refuses to incur. Which of the two layouts is right is a judgement about the
# printed page and belongs to whoever owns it, not to a test and not to a passing edit. What a test
# CAN do is stop the set growing and stop any of these drifting further, which is what
# :func:`test_the_tables_whose_declared_widths_govern_still_do` does.
#
# THE SAME FIVE ENTITIES ARE WHERE THE TWO SURFACES DRAW DIFFERENT TABLES, and that is one fact and
# not two, which is why the handset's answer is recorded on the SAME line as the declared sum rather
# than in a second table that can drift out of step with this one. `ReportScreen.tableColumns` on the
# phone NORMALISES the declared hints — scale them to 100, last column absorbs the residue — where
# `_render_table` DISCARDS them and re-weights 2.0 for free text against 1.0 for everything else. The
# two agree wherever the declared sum is already 100, which is twenty-four of the twenty-nine tabular
# entities; for these five the same workshop's .docx paginates and wraps differently depending on
# which machine built it. `handset` below is what the phone lays out today, to 4 decimal places.
#
# To retire an entry: rebalance that entity's first six widths to 100 and delete its line — both
# halves of it, because at 100 the two rules coincide and there is nothing left to record.
class _NonGoverning(NamedTuple):
    declared_sum: float
    handset: tuple[float, ...]


_WIDTHS_THAT_DO_NOT_GOVERN = {
    "sketch": _NonGoverning(88.0, (13.6364, 27.2727, 20.4545, 20.4545, 18.1818)),
    "prototype": _NonGoverning(112.0, (10.7143, 21.4286, 14.2857, 17.8571, 25.0, 10.7143)),
    "prototypeValidation": _NonGoverning(
        86.0, (23.2558, 16.2791, 15.1163, 15.1163, 15.1163, 15.1163)
    ),
    "finalProduct": _NonGoverning(120.0, (10.0, 20.0, 15.0, 18.3333, 20.0, 16.6667)),
    "followUp": _NonGoverning(88.0, (22.7273, 13.6364, 15.9091, 22.7273, 13.6364, 11.3636)),
}


def _governing_widths(entity):
    """The widths ``report_builder._render_table`` would actually read, and their sum.

    ``_table_columns`` caps at six, so a seventh declared column contributes NOTHING to the sum and
    prints as a key-value line beneath each row instead. ``existingProduct`` declares seven and its
    first six add to exactly 100, which is somebody having done this arithmetic on purpose; two of
    the entities in the exemption above declare seven and did not.

    THIS IS A TRANSCRIPTION AND IT DELIBERATELY OMITS TWO OF ``_table_columns``' FILTERS, so read
    the vectors below as recorded AT FULL TIER and not as tier-independent. ``_table_columns``
    filters ``self._visible(f)`` — ``not deprecated`` AND ``spec.tier.rank <= template.max_tier.rank``
    — and ``not f.type.is_media``; this function keeps only the ``not deprecated`` half. Both
    omissions are no-ops as the registry stands: no field is declared both TABLE_COLUMN and
    media-typed (``test_a_media_field_is_never_a_table_column_whatever_role_it_declares`` drives a
    synthetic entity precisely because none exists), and the tier filter drops nothing under a
    template that admits every tier. A template with a LOWER ``max_tier`` would drop columns and
    re-weight the survivors, and this parity test cannot see that — so a divergence introduced at
    BASIC would pass here. Widen the transcription, not the exemption list, if that day comes.
    """
    columns = [f for f in entity.fields
               if f.report_role.value == "TABLE_COLUMN" and not f.deprecated][:6]
    return columns, sum(f.column_width_pct for f in columns)


def test_the_tables_whose_declared_widths_govern_still_do():
    """A declared width that stops being read is a layout decision silently discarded.

    THE BLIND SPOT THIS CLOSES. The guard beside it checks three entities and asks only that their
    widths do not EXCEED 100 — so a table that drifted DOWN to 88 passed it while being laid out by
    the proportional fallback, and the twenty-six entities it does not name were never checked at
    all. Five of them are in that state right now and are listed in ``_WIDTHS_THAT_DO_NOT_GOVERN``
    with the sum each reaches — and, on the same line, the layout the handset draws for it instead,
    which :func:`test_the_two_surfaces_lay_out_the_same_table_the_same_way` pins.

    Every other tabular entity must either declare nothing (proportional by design) or add up.
    """
    drifted = []
    for _stage, entity in all_entities():
        columns, total = _governing_widths(entity)
        if not columns or not total:
            # No table, or no width declared anywhere on it — proportional by design, which is a
            # legitimate choice and not something to assert about.
            continue
        governs = abs(total - 100.0) < 0.5
        recorded = _WIDTHS_THAT_DO_NOT_GOVERN.get(entity.key)
        if governs:
            assert recorded is None, (
                f"{entity.key} now adds up to 100 and its declared widths govern again — "
                "delete its line from _WIDTHS_THAT_DO_NOT_GOVERN"
            )
            continue
        if recorded is None:
            drifted.append(f"{entity.key} (sums to {total})")
        else:
            assert abs(total - recorded.declared_sum) < 0.5, (
                f"{entity.key}'s widths have moved from {recorded.declared_sum} to {total} while "
                "still not governing — rebalance them to 100 rather than to another number "
                "nothing reads"
            )
    assert drifted == [], (
        "these tables' declared widths have stopped governing, so report_builder is laying them "
        f"out proportionally and the percentages beside their fields do nothing: {drifted}"
    )


def _server_table_widths(columns):
    """The widths ``report_builder._render_table`` hands ``TableBlock``, as it computes them.

    A transcription of that branch and not a call into it, because ``_render_table`` needs a
    ``ReportBuilder``, a ``TemplateSection`` and rows to reach the four lines that matter. It is
    six lines long and pinned by :func:`test_the_two_surfaces_lay_out_the_same_table_the_same_way`
    against the Android rule; if it ever stops matching the source, that test is the one that has
    to be re-read rather than repaired.
    """
    declared = sum(c.column_width_pct for c in columns)
    if declared and abs(declared - 100.0) < 0.5:
        return [c.column_width_pct for c in columns]
    # Free text gets twice the share of a number or a date, and every declared width is discarded.
    weights = [2.0 if c.is_free_text else 1.0 for c in columns]
    widths = [100.0 * w / sum(weights) for w in weights]
    widths[-1] += 100.0 - sum(widths)
    return widths


def _handset_table_widths(columns):
    """The widths ``ReportScreen.tableColumns`` hands ``TableBlock`` on the phone, in Python.

    NORMALISE AND ABSORB, which is a different rule and not a rounding difference: a declared width
    above zero is a HINT that is kept and scaled, an unhinted column takes an even share of the
    remainder with a 4% floor, the vector is scaled to 100 and the last column absorbs the residue.
    Nothing is ever discarded, which is exactly where it parts company with the server.
    """
    hinted = sum(c.column_width_pct for c in columns)
    unhinted = sum(1 for c in columns if c.column_width_pct <= 0)
    share = max((100.0 - hinted) / unhinted, 4.0) if unhinted else 0.0
    widths = [c.column_width_pct if c.column_width_pct > 0 else share for c in columns]
    scaled = [w * 100.0 / sum(widths) for w in widths]
    scaled[-1] = 100.0 - sum(scaled[:-1])
    return scaled


def test_the_two_surfaces_lay_out_the_same_table_the_same_way():
    """ONE WORKSHOP, ONE REGISTRY, TWO .docx FILES THAT PAGINATE DIFFERENTLY.

    The server and the handset both draw the same six columns from the same registry and then
    compute their widths by DIFFERENT RULES — ``_render_table`` discards the declared widths and
    re-weights free text at 2.0, ``ReportScreen.tableColumns`` keeps them and normalises. Where the
    declared sum is already 100 the two coincide exactly, and for twenty-four of the twenty-nine
    tabular entities it is. For the other five the same stage prints at one width at the office and
    another in the field, and until this test existed that divergence was written down only in a
    KDoc on ``tableColumns`` — so nothing failed when it widened, and nothing failed when a sixth
    entity joined the five.

    THIS IS THE SAFE HALF AND IT IS DELIBERATELY NOT THE FIX. Converging costs one surface the
    layout of documents already printed and filed whichever direction it goes, so which rule wins
    is the owner of the printed page's call and needs a changelog note. What a test can do is fail
    the moment the set changes or a recorded vector moves, which is what this does. The six-column
    cap is the part the two surfaces DO agree on and must keep agreeing on — ``_governing_widths``
    takes the same first six as ``_table_columns`` and ``tableColumns``' callers, AT FULL TIER;
    its docstring names the two filters it omits and why they are no-ops today.
    """
    disagreed = []
    for _stage, entity in all_entities():
        columns, declared = _governing_widths(entity)
        if not columns or not declared:
            continue
        server = _server_table_widths(columns)
        handset = _handset_table_widths(columns)
        recorded = _WIDTHS_THAT_DO_NOT_GOVERN.get(entity.key)
        if recorded is None:
            if any(abs(a - b) > 0.1 for a, b in zip(server, handset, strict=True)):
                disagreed.append(
                    f"{entity.key}: server {[round(x, 4) for x in server]} vs handset "
                    f"{[round(x, 4) for x in handset]}"
                )
            continue
        # A recorded divergence is pinned to the number it actually reaches, both sides. Widening
        # it is the failure this exists to catch: "these five differ" is not a fact anybody can act
        # on, "prototype's first column moved from 10.7 to 6.0 on the phone" is.
        assert len(recorded.handset) == len(columns), (
            f"{entity.key} now draws {len(columns)} columns and _WIDTHS_THAT_DO_NOT_GOVERN records "
            f"{len(recorded.handset)} — re-record the handset vector beside its declared sum"
        )
        assert all(abs(a - b) < 0.01 for a, b in zip(recorded.handset, handset, strict=True)), (
            f"{entity.key}'s handset layout has moved to {[round(x, 4) for x in handset]}; the "
            f"office still draws {[round(x, 4) for x in server]}, so the gap between the two "
            "documents has changed size. Re-record it here or close it on the printed page."
        )
        assert any(abs(a - b) > 0.1 for a, b in zip(server, handset, strict=True)), (
            f"{entity.key}'s two surfaces now agree — rebalance is done, so delete its line from "
            "_WIDTHS_THAT_DO_NOT_GOVERN"
        )
    assert disagreed == [], (
        "these entities' tables are now laid out differently by the server and the handset, and "
        "the divergence is not recorded in _WIDTHS_THAT_DO_NOT_GOVERN, so one workshop's report "
        f"wraps different cells depending on which machine built it: {disagreed}"
    )


def test_the_hydration_table_grew_and_no_pair_was_lost():
    """The 32 pairs that already worked are still there, plus the ones this lane added.

    Spot-checked rather than pinned as a whole: a full snapshot would have to be edited on every
    legitimate widening, which is how a pin becomes something people update without reading.
    """
    for path, source, target in (
        ("workshopSetup.craftRef", "craftName", "craftName"),
        ("participant.artisanRef", "specialisation", "specialisation"),
        ("tool.toolRef", "usedFor", "usedFor"),
        ("processStep.processRef", "notes", "description"),
        ("existingProduct.artisanRef", "name", "artisanName"),
        ("existingProduct.productRef", "price", "price"),
        ("prototype.productRef", "name", "productName"),
    ):
        assert REFERENCE_HYDRATION[path][source] == target

    pairs = sum(len(m) for m in REFERENCE_HYDRATION.values())
    assert pairs >= 81, (
        f"the carry is down to {pairs} field-pairs; it was 32 before this lane and 81 after, and "
        "a drop means a mapping was removed rather than a source column disappearing"
    )
