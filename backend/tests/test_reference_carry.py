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
# The two modules the measurement-method carry has to agree with, imported so the assertions read
# their real values rather than a copy: `record_fields.METHOD_CLAUSES` is the two phrases every
# record surface already prints, and `measurement_provenance.DIMENSION_FIELDS` is the closed set of
# columns a method can be stamped on at all.
from app.services import measurement_provenance, record_fields
from app.services.records import derive_age, derive_experience_years
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
    # Added 2026-08-23 with the column, on the owner's instruction that experience become a derived
    # field with a date of joining the craft behind it. TWO TARGETS, and the pair is the point: the
    # DERIVED number is what the participant table's Experience column prints, and the DATE crosses
    # as well — unlike `dateOfBirth` above, which reaches the report only as its derived number
    # because the participant entity has no birthday box. A derived figure in a document a ministry
    # officer reads whose basis is stated nowhere is worse than one that names it.
    "craftStartDate": "participant.craftStartDate + participant.experienceYears (derived)",
    # STILL CARRIED, and now the SECOND of three answers rather than the only one. The derived value
    # from `craftStartDate` outranks it where a row has one; every row written before 2026-08-23 has
    # none — the migration deliberately refuses to guess — so this column is what they all still
    # print. See the precedence written out in `REFERENCE_MODELS["Artisan"].data`.
    "experienceYears": "participant.experienceYears (the stated number, behind the derivation)",
    # Still carried, and now only as the FALLBACK for records written before the two columns above
    # existed. The migration copied every clean numeric value across and deliberately left the ones
    # it could not parse ("30+", "about 30") in the JSON rather than guessing at them.
    "extraMetadata": "participant.experienceYears + participant.age + specialisation (legacy)",
    "craftId": "participant.specialisation",
    "locationId": "participant.village/state/district/pincode/subjectLocation",
    # MOVED HERE FROM NOT_CARRIED, where it read "join key — the design workshop already knows which
    # workshop it belongs to". That was true of the DESIGN workshop and false of this column:
    # `participant.artisanRef` is the one artisan picker declared ALL_SCOPE, so a roster legitimately
    # holds artisans documented at another cluster's workshop years earlier, and `documentedOn`
    # answered when while nothing answered where. The TITLE crosses, through the `workshop` relation;
    # the id itself still does not.
    "workshopId": "participant.documentedAtWorkshop (Workshop.title, via the relation)",
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
}

# ONE LEDGER ROW, THREE REFERENCING MODELS, AND THE TARGETS ARE NAMED IN FULL FOR A REASON. `Location`
# hangs off `Artisan`, `ProductDocumentation` and `ToolDocumentation`, and this list is parameterised
# by MODEL, so it cannot be split per referencing model without inventing a model name `_columns`
# could not resolve. What it can do is name every box each column reaches: while these values read
# "participant.state" alone, the product and tool carries landed, the SUBJECT PIN did not, and this
# file said nothing either way — the tripwire was blind to a missing target on two of the three
# models because the column was already spoken for by the third.
LOCATION_CARRIED = {
    "state": "participant.state + existingProduct.recordState + tool.recordState",
    "district": "participant.district + existingProduct.recordDistrict + tool.recordDistrict",
    "village": "participant.village + existingProduct.recordVillage + tool.recordVillage",
    "pincode": "participant.pincode + existingProduct.recordPincode + tool.recordPincode",
    "subjectLatitude": (
        "participant.subjectLocation + existingProduct.recordSubjectLocation "
        "+ tool.recordSubjectLocation"
    ),
    "subjectLongitude": (
        "participant.subjectLocation + existingProduct.recordSubjectLocation "
        "+ tool.recordSubjectLocation"
    ),
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
    # MOVED HERE FROM NOT_CARRIED, where it read "bookkeeping — EXIF written programmatically by the
    # record form". The EXIF summary is still not carried and never will be; what this column ALSO
    # holds is `fieldProvenance`, and inside that the `method` stamp `records.merge_field_provenance`
    # writes on each dimension. Filing the whole column as bookkeeping is how the one fact on it that
    # a ministry officer needs — that a length is a vision model's estimate off a grid photograph and
    # not a tape reading — stayed on the record while the number crossed. See
    # `design_workshops._measurement_method_note`.
    "extraMetadata": "existingProduct.measurementMethodNote (fieldProvenance.method only)",
    "size": "existingProduct.dimensionsNote",
    "timeTakenToCompleteProduct": "existingProduct.productionTimeNote",
    "remarks": "existingProduct.remarks",
    "recordedAt": "existingProduct.documentedOn",
    "artisanId": "join key — existingProduct.artisanRef, which fills artisanName",
    "craftId": "carried by value as craftName",
    "locationId": (
        "existingProduct.place (denormalised) + recordState/recordDistrict/recordVillage/"
        "recordPincode + recordSubjectLocation. Provenance columns never cross — see LOCATION_*"
    ),
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
    # MOVED HERE FROM NOT_CARRIED, where it read "bookkeeping". Same reason as the product's, and
    # sharper here: five of this model's seven measurements state no unit either, so the tool table
    # was the one place a reader had nothing at all to go on. Only `fieldProvenance.method` crosses,
    # and only for the two INCH columns — `measurement_provenance.DIMENSION_FIELDS` never stamps the
    # five unit-less ones. See `design_workshops._measurement_method_note`.
    "extraMetadata": "tool.measurementMethodNote (fieldProvenance.method only)",
    "height": "tool.heightAsRecorded (source states no unit)",
    "width": "tool.widthAsRecorded (source states no unit)",
    "thickness": "tool.thicknessAsRecorded (source states no unit)",
    "weight": "tool.weightAsRecorded (source states no unit)",
    "radius": "tool.radiusAsRecorded (source states no unit)",
    "recordedAt": "tool.documentedOn",
    "artisanId": "carried by value as artisanName",
    "craftId": "carried by value as craftName",
    "locationId": (
        "tool.place (denormalised) + recordState/recordDistrict/recordVillage/recordPincode "
        "+ recordSubjectLocation. Provenance columns never cross — see LOCATION_*"
    ),
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
    # ── FOUR ENTRIES MOVED OUT OF NOT_CARRIED, AND THE REFUSALS THEY REPLACE ARE WORTH KNOWING ───
    #
    # `category`, `description` and `place` were refused on the argument that "stage 1 already asks
    # all three of its own questions and asks them better". The answer, written out in full above
    # `REFERENCE_MODELS["Craft"].data`, is that a value which must not overwrite the designer's own
    # cover fields needs a BOX OF ITS OWN rather than silence — which is what the tool and product
    # mappings had been doing with their own free-text `place` all along. `recordedAt` was filed as
    # bookkeeping, which confused the WORKSHOP's dates (on the cover) with the provenance of the
    # source record (every other reference model carries its own).
    "category": "workshopSetup.craftCategory",
    "description": "workshopSetup.documentedCraftNotes",
    "place": "workshopSetup.craftPlace",
    "recordedAt": "workshopSetup.craftDocumentedOn",
    # `craftRef` is ALL_SCOPE, so a linked craft may belong to another cluster's study years earlier;
    # `craftDocumentedOn` answers when and this answers under whose study. The TITLE crosses, through
    # the `workshop` relation — the id does not.
    "workshopId": "workshopSetup.craftDocumentedAtWorkshop (Workshop.title, via the relation)",
}
CRAFT_NOT_CARRIED = {
    "extraMetadata": "bookkeeping",
    "recordedTimezone": "the date is carried as a bare DATE, so the zone has nothing to qualify",
    "createdAt": "bookkeeping",
    "updatedAt": "bookkeeping",
    "createdById": "bookkeeping",
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
        "workshop": (
            "read by the picker — participant.documentedAtWorkshop takes Workshop.title, via "
            "include={'workshop': True}. The EXPLICIT column and not the WorkshopArtisan join, "
            "which cannot answer 'which one documented it'."
        ),
        "workshops": "join key — the WorkshopArtisan reading of 'was at this workshop'",
        "products": "reached by its own picker (existingProduct.productRef)",
        "tools": "reached by its own picker (tool.toolRef)",
        "toolLinks": "the tool-assignment join; see ToolDocumentation.artisanLinks below",
        "media": (
            "one photograph per record, through _reference_photos — AND counted, by type, into the "
            "sentence participant.recordMediaNote carries, via include={'media': True}. The ids "
            "never cross: see _media_note."
        ),
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
        # REWRITTEN, AND THE OLD TEXT IS WORTH KNOWING BECAUSE IT WAS RIGHT ABOUT THE MECHANISM. It
        # read "NOT INCLUDED, and NOT a tidy-up to make", on the ground that `_reference_place`
        # prefers `location.village` over the free-text place and runs at REPORT time. That hazard is
        # real and is now closed by a condition rather than by the accident of an unloaded relation:
        # `_reference_place` returns the denormalised `place` for every model except `Artisan`, so
        # this include changes what SAVE-time hydration can offer and nothing about what an
        # already-submitted document prints.
        "location": (
            "read by the picker — the STATED address columns and the SUBJECT pin, via "
            "include={'location': True}, into existingProduct.recordState/recordDistrict/"
            "recordVillage/recordPincode/recordSubjectLocation. The PROVENANCE half of the same row "
            "(latitude/longitude/altitude/accuracy/capturedAt/placeName/address) never crosses; "
            "_reference_place is guarded on the model name so RENDER-time behaviour is unchanged."
        ),
        "createdBy": "bookkeeping",
        "media": (
            "one photograph per record, through _reference_photos (media_field='productId') — AND "
            "counted, by type, into existingProduct.recordMediaNote via include={'media': True}. A "
            "sentence, never the ids: see _media_note."
        ),
        "mediaProcessingJobs": "queue state; see measurementAnalysisStatus in the scalar list",
        "processes": "reached by its own picker (processStep.processRef / traditionalProcess)",
    },
    "ToolDocumentation": {
        "artisan": "NOT INCLUDED — see the identical note on ProductDocumentation.artisan",
        "craft": "carried by value as craftName",
        "workshop": "join key",
        "location": "read by the picker — see the identical note on ProductDocumentation.location",
        "createdBy": "bookkeeping",
        # REWRITTEN OFF "one photograph per record", which was the whole answer here and was the gap:
        # the tool record's media card is mounted TWICE — the ordered "Process stages" sequence
        # (archived as STAGE_STEP_1, STAGE_STEP_2, …) and general "Tool media" — so a tool documented
        # as a nine-photograph sequence, or explained on video, reached the workshop and the report as
        # one still with nothing admitting the rest existed.
        "media": (
            "one photograph per record, through _reference_photos (media_field='toolId') — AND "
            "counted into tool.recordMediaNote via include={'media': True}, which also names the "
            "numbered making sequence by its STAGE_STEP_ prefix. A sentence, never the ids."
        ),
        "mediaProcessingJobs": "queue state",
        # BUILT, AND THIS ENTRY USED TO BE THE REASON THIS LEDGER EXISTS. It read "NOT BUILT" and
        # listed what carrying the assignment would cost; every line of that list has since landed —
        # the bounded join, the KEY_VALUE fromref, the frozen-at-pick-time label, the version bump and
        # the regenerated asset. Kept rather than shortened because the shape of the omission is the
        # thing this ledger is for: a whole feature (ToolAssignmentSection, four routes in tools.py, a
        # data_browser filter) reaching no report, invisible to a scalar-column check.
        "artisanLinks": (
            "read by the picker — every assigned artisan's name, newline-separated, into "
            "tool.usedByArtisans (report_role=BULLETS), via "
            "include={'artisanLinks': {'include': {'artisan': True}}}. See _linked_artisan_names for "
            "why it is ordered by name and de-duplicated, and why 'documented for' and 'used by' are "
            "two boxes rather than one."
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
        "workshop": (
            "read by the picker — workshopSetup.craftDocumentedAtWorkshop takes Workshop.title, via "
            "include={'workshop': True}. Deliberately the EXPLICIT column and not the WorkshopCraft "
            "join below: a many-to-many cannot answer which workshop documented the craft."
        ),
        "artisans": "reached by its own picker (participant.artisanRef)",
        "products": "reached by its own picker",
        "tools": "reached by its own picker",
        # REWRITTEN: the photograph half of this stopped being true when the Craft lambda started
        # reading the `photo` argument it had been throwing away, and the rest of what a craft carries
        # (video, audio notes, a scanned gazetteer page) now reaches the cover stage as a count.
        "media": (
            "one photograph per record, through _reference_photos (media_field='craftId'), into "
            "workshopSetup.craftPhoto/craftPhotoCaption — AND counted into "
            "workshopSetup.craftMediaNote via include={'media': True}. MediaFile.craftId is indexed."
        ),
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

    THREE MORE LANDED 2026-08-23 WITH ``Artisan.craftStartDate``, which puts a THIRD source in front
    of the two above for experience and makes the order itself the thing worth pinning:

    5. a row with a joining date derives its experience FROM THE DATE, outranking the stated column;
    6. A ROW WITH THE COLUMN AND NO DATE STILL PRINTS THE COLUMN — which is the assertion that says
       nothing currently right was blanked in order to make room for the derivation, and it is the
       one this change could most plausibly have broken. Every row in the live table is this shape;
    7. an unusable joining date (in the future, or deriving outside the 0..90 the workshop's own
       field declares) falls THROUGH to the column rather than refusing the row or printing a
       number the participant table would then reject on a box nobody typed in.
    """
    columns = _columns("Artisan")
    assert "experienceYears" in columns, "the migration is the point of this test"
    assert "dateOfBirth" in columns
    assert "craftStartDate" in columns, "the 20260823093000 migration is the point of 5-7 below"
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

    # 5. THE DATE OUTRANKS THE NUMBER. All three sources are present and all three disagree, so this
    #    can only pass on the derivation: "years since a stated start" is right on the day it is
    #    printed, where a stated number is right on `recordedAt` and decays from then on.
    joined = _artisan_row(
        craftStartDate=datetime(1994, 3, 12), experienceYears=31,
        extraMetadata={"experienceYears": 22, "age": 44},
    )
    from_date = dw.REFERENCE_MODELS["Artisan"].data(joined, None)
    assert from_date["experienceYears"] == derive_experience_years(datetime(1994, 3, 12))
    assert from_date["experienceYears"] not in (31, 22), (
        "the joining date must outrank both the stated column and the legacy metadata"
    )
    # And the date itself crosses, so the report can say what that number is worked out from.
    assert from_date["craftStartDate"] == "1994-03-12"

    # 6. NOTHING CURRENTLY RIGHT IS BLANKED. Every row in the live table has no joining date — the
    #    migration adds the column and deliberately refuses to backfill one — so every one of them
    #    reaches this branch and prints exactly what it printed before the column existed.
    stated_only = dw.REFERENCE_MODELS["Artisan"].data(
        _artisan_row(experienceYears=31, extraMetadata={"experienceYears": 22}), None
    )
    assert stated_only["experienceYears"] == 31
    assert stated_only["craftStartDate"] is None

    # 7. AN UNUSABLE DATE FALLS THROUGH RATHER THAN WINNING OR REFUSING. A future date derives to a
    #    negative number and a 1900 date derives past 90, which is outside the bounds
    #    `participant.experienceYears` declares — and `validate_entry` re-coerces every field on
    #    every save, so a hydrated out-of-range number becomes a refused answer on a box the
    #    designer never touched. Dropping it leaves the stated column readable instead.
    for unusable in (datetime(2099, 1, 1), datetime(1900, 1, 1)):
        fell_through = dw.REFERENCE_MODELS["Artisan"].data(
            _artisan_row(craftStartDate=unusable, experienceYears=31), None
        )
        assert derive_experience_years(unusable) is None
        assert fell_through["experienceYears"] == 31, unusable


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


def _media_row(**overrides):
    """One ``MediaFile`` row as the ``media`` relation hands it to a data lambda.

    Only the three attributes ``_media_note`` reads: the type, the original filename (which is what
    carries ``ToolForm``'s ``STAGE_STEP_n`` naming of an ordered making sequence) and
    ``extraMetadata``, where the measurement-grid marker lives. A row is deliberately NOT a full
    MediaFile: everything else on that model is entitlement-gated and must never reach a stage entry,
    so a fixture that carried it would make a leak look normal.
    """
    fields = dict(id="med_x", mediaType="IMAGE", originalFilename="loom.jpg", extraMetadata=None)
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
        # `craftStartDate` joins them on the same terms and for the same reason: NULL is the state
        # of every row that existed before it was added, so this fixture is the legacy case and the
        # derived branch is built explicitly by the test that covers the precedence.
        dateOfBirth=None, experienceYears=None, craftStartDate=None,
        recordedAt=datetime(2025, 3, 12, 9, 0), recordedTimezone="Asia/Kolkata",
        craft=SimpleNamespace(name="Sambalpuri Ikat"), location=_location_row(),
        # WHERE the record was made, which `documentedOn` cannot say. `participant.artisanRef` is
        # ALL_SCOPE, so this is routinely a different cluster's workshop from the one being written.
        workshop=SimpleNamespace(title="Sambalpuri Ikat cluster survey, Barpali"),
        # THE SPOKEN INTRODUCTION IS THE POINT OF THIS FIXTURE. The record form's media card asks for
        # "images, audio introductions, videos, and documents" by name, and `_reference_photos`
        # resolves one IMAGE — so the audio row here is the material that used to reach nothing.
        media=[
            _media_row(id="med_a1"),
            _media_row(id="med_a2", mediaType="AUDIO", originalFilename="introduction.m4a"),
        ],
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
        # THE STATED ADDRESS AND THE SUBJECT PIN, both of which this fixture lacked while the mapping
        # declared five targets for them — so "a fully documented product arrives whole" was asserting
        # over a row that was not fully documented and the five boxes read as legitimately empty.
        location=_location_row(),
        # HOW EACH DIMENSION CAME TO BE KNOWN, in the exact shape `records.merge_field_provenance`
        # writes: the {by, byName, at} of whoever pressed Save, with `method` merged in BESIDE it
        # for a dimension column rather than replacing it. This fixture is deliberately the
        # THREE-WAY case — a vision model read the length off a grid photograph, the breadth was
        # computed from marks a person placed on a photograph, the height was typed — because that
        # is the case where a single trailing clause would overstate the machine's part and the note
        # has to say WHICH number each method produced. `sellingPrice` carries the plain stamp with
        # no method, which is what every non-dimension column on a real row looks like.
        extraMetadata={
            "fieldProvenance": {
                "lengthInches": {"by": "usr_7", "byName": "R. Menon",
                                 "at": "2025-04-02T11:00:00+00:00",
                                 "method": "VISION_MODEL", "methodProvider": "gemini",
                                 "methodModelId": "gemini-2.5-flash-lite",
                                 "methodConfidence": 0.8},
                "breadthInches": {"by": "usr_7", "byName": "R. Menon",
                                  "at": "2025-04-02T11:00:00+00:00",
                                  "method": "PHOTO_GEOMETRY", "methodTechnique": "SCALE"},
                "heightInches": {"by": "usr_7", "byName": "R. Menon",
                                 "at": "2025-04-02T11:00:00+00:00", "method": "TYPED"},
                "sellingPrice": {"by": "usr_7", "byName": "R. Menon",
                                 "at": "2025-04-02T11:00:00+00:00"},
            },
        },
        media=[
            _media_row(id="med_p1"),
            _media_row(id="med_p2", mediaType="VIDEO", originalFilename="finishing.mp4"),
        ],
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
        location=_location_row(),
        # ONE STAMPED DIMENSION AND ONE EXPLICITLY UNRECORDED, which is the state of the fleet: a
        # client that has not implemented its half of the marker makes `method_stamps` write
        # UNRECORDED rather than nothing, and UNRECORDED must print nothing at a reader — see
        # `record_fields.METHOD_CLAUSES`. The five unit-less columns (`height`, `width`,
        # `thickness`, `weight`, `radius`) carry NO stamp at all and cannot: they are outside
        # `measurement_provenance.DIMENSION_FIELDS`, so `method_stamps` drops any marker naming one.
        extraMetadata={
            "fieldProvenance": {
                "lengthInches": {"by": "usr_9", "byName": "S. Bal",
                                 "at": "2025-04-03T08:30:00+00:00",
                                 "method": "VISION_MODEL", "methodProvider": "gemini"},
                "breadthInches": {"by": "usr_9", "byName": "S. Bal",
                                  "at": "2025-04-03T08:30:00+00:00", "method": "UNRECORDED"},
            },
        },
        # The record page's media card is mounted twice: the ordered "Process stages" sequence, whose
        # captures ToolForm renames STAGE_STEP_n, and general footage. Both shapes are here, plus a
        # measurement-grid frame, which is a sheet of ruled paper and not footage of the tool.
        media=[
            _media_row(id="med_t1", originalFilename="STAGE_STEP_1_warping.jpg"),
            _media_row(id="med_t2", originalFilename="STAGE_STEP_2_treadles.jpg"),
            _media_row(id="med_t3", mediaType="AUDIO", originalFilename="weaver-explains.m4a"),
            _media_row(id="med_t4", originalFilename="grid-length.jpg",
                       extraMetadata={"purpose": "MEASUREMENT_GRID"}),
        ],
        artisanLinks=[SimpleNamespace(artisan=SimpleNamespace(name="Latha Devi")),
                      SimpleNamespace(artisan=SimpleNamespace(name="Bhima Meher"))],
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


def _process_row(**overrides):
    fields = dict(
        id="prc_1", name="Tie and dye", notes="Yarn is tied in sections, dyed, untied, washed.",
        preProcessAvailable=True, recordedAt=datetime(2025, 4, 4, 7, 0),
        product=SimpleNamespace(productName="Sambalpuri saree"),
        # THE PRE-PROCESS CLIPS THE RECORD FORM MAKES MANDATORY, plus one step's own captures. The
        # fixture carried neither, so `recordMediaNote` — a declared target of this mapping — was
        # blank for a row this file describes as fully documented, and the sibling assertion that no
        # declared target is empty was reading it as a legitimate absence.
        media=[_media_row(id="med_pr1", mediaType="VIDEO", originalFilename="pre-process.mp4")],
        steps=[
            SimpleNamespace(name="Washing", stepType="SEQUENTIAL", sortOrder=2, notes=None,
                            media=[]),
            SimpleNamespace(name="Tying", stepType="SEQUENTIAL", sortOrder=0,
                            notes="Cotton thread, section by section",
                            media=[_media_row(id="med_ps1")]),
            SimpleNamespace(name="Dyeing", stepType="GROUP", sortOrder=1, notes=None, media=[]),
        ],
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


def _craft_row(**overrides):
    """The fifth reference model's row fixture, and the one that was missing.

    `Craft`'s two names print on the COVER PAGE of every submitted report, which made it the
    highest-visibility carry of the five and the only one with no database-free round trip:
    `craftLocalName` was asserted by no backend test at all, and Craft's only executing round trip
    lived in `test_reference_resolver.py` behind a Postgres skip that nobody's machine satisfies.
    See `test_a_documented_craft_reaches_the_cover_page_whole`.

    EVERY COLUMN THE CRAFTS PAGE COLLECTS IS HERE NOW, and the fixture used to hold two of them —
    which is why the sibling test could assert "no declared target is blank" while five of the eight
    targets were empty for a reason the fixture, not the code, was responsible for. A row that is not
    fully documented cannot pin what a fully documented one carries.
    """
    fields = dict(
        id="crf_1", name="Sambalpuri Ikat", localName="ସମ୍ବଲପୁରୀ ବନ୍ଧ",
        category="Weaving", place="Barpali",
        description="Warp and weft are tied and dyed before the cloth is woven.",
        recordedAt=datetime(2024, 11, 5, 10, 0),
        workshop=SimpleNamespace(title="Sambalpuri Ikat cluster survey, Barpali"),
        media=[
            _media_row(id="med_c1"),
            _media_row(id="med_c2", mediaType="AUDIO", originalFilename="elder-account.m4a"),
            _media_row(id="med_c3", mediaType="PDF", originalFilename="gazetteer-page.pdf"),
        ],
    )
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

    THE JOINING DATE IS SUPPLIED HERE AND NOWHERE ELSE IN THIS FILE, and the asymmetry is worth a
    sentence. ``_artisan_row`` defaults ``dateOfBirth``/``experienceYears``/``craftStartDate`` to
    None so that it stands for a row written before those columns existed — which is every row in
    the live table — and the first two still fill their boxes from the legacy ``extraMetadata``
    spellings behind them. ``craftStartDate`` has no such fallback and cannot have one: no legacy
    key ever held a joining date, which is exactly why the migration refuses to invent one. So a
    "fully documented" artisan has to be told the date, and this test is what says the box is wired
    rather than merely declared.
    """
    data = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"},
        rows={"artisan": [_artisan_row(craftStartDate=datetime(1994, 3, 12))]},
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


#: The exact sentences the two fixtures above must produce. Written out rather than rebuilt from
#: the table, so a change to the grammar has to be typed here as well as read there.
_PRODUCT_METHOD_NOTE = (
    "On the product record: length (vision model estimate), breadth (photo measurement)"
)
_TOOL_METHOD_NOTE = "On the tool record: length (vision model estimate)"


async def test_the_measurement_method_crosses_with_the_number_it_qualifies(monkeypatch):
    """A HYDRATED CENTIMETRE FIGURE NO LONGER ATTRIBUTES A MACHINE'S GUESS TO A PERSON.

    THE DEFECT. `records.merge_field_provenance` stamps `method: "VISION_MODEL"` beside
    `{by, byName, at}` on a dimension a vision model estimated off a photograph of the object on a
    grid sheet, and `record_fields.dims_with_method` prints that on the record sheet, every .xlsx
    sheet and both CSV exports. Hydration copied the NUMBER and left the stamp on the record — while
    `hydrate_entries` stamped the entry with `HydrationSource(author_id=row.createdById)`, whose NAME
    both field-provenance views render. So the workshop entry, and then the .docx a ministry officer
    reads, asserted that a named human had measured a number a model guessed: the exact defect
    `services/measurement_provenance` exists to end, one layer further out.

    WHY IT NAMES THE RECORD AND NOT THE BOX. Hydration only fills BLANKS, so a designer who measured
    the saree themselves keeps their own length beside a hydrated width. A per-dimension label would
    then sit over the designer's own figure and call it a model's estimate — a new false claim in
    place of the old one. The sentence is about the RECORD's columns, so it stays true whatever the
    designer typed, and it says BREADTH because the product record's column is `breadthInches`.

    WHY IT NAMES BOTH METHODS SEPARATELY. The product fixture's length is a vision model's and its
    breadth is arithmetic over marks somebody placed; one trailing clause would overstate the
    machine's part on a number a person produced. `dims_with_method` splits its own cell for the
    same reason.
    """
    product = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row()]},
    )
    assert product["measurementMethodNote"] == _PRODUCT_METHOD_NOTE
    # The height was TYPED, so it is absent from the sentence rather than described.
    assert "height" not in product["measurementMethodNote"]

    tool = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"},
        rows={"tooldocumentation": [_tool_row()]},
    )
    assert tool["measurementMethodNote"] == _TOOL_METHOD_NOTE
    # UNRECORDED prints nothing, so the breadth is unqualified rather than described as unknown.
    assert "breadth" not in tool["measurementMethodNote"]


async def test_a_record_whose_dimensions_were_all_typed_says_nothing_at_all(monkeypatch):
    """TYPED AND UNRECORDED ARE SILENT HERE FOR THE REASON THEY ARE SILENT ON THE RECORD SHEET.

    Every row written before `measurement_provenance` existed carries UNRECORDED, including the ones
    a model produced, so appending "method not recorded" to most of the database would be noise that
    trains a reader to skip the clause on the one row where it matters — `record_fields.METHOD_CLAUSES`
    argues both at length. A blank box is the honest rendering of a legacy row, and this asserts the
    workshop agrees with the record sheet about that rather than inventing a third answer.
    """
    typed = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row(extraMetadata={"fieldProvenance": {
            "lengthInches": {"by": "usr_7", "method": "TYPED"},
            "breadthInches": {"by": "usr_7", "method": "UNRECORDED"},
            "heightInches": {"by": "usr_7"},
        }})]},
    )
    assert "measurementMethodNote" not in typed, (
        "a record whose dimensions were typed, unrecorded or unstamped must leave this box blank"
    )

    legacy = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row(extraMetadata=None)]},
    )
    assert "measurementMethodNote" not in legacy, (
        "a row with no fieldProvenance blob at all predates the record half and states nothing"
    )


async def test_the_note_never_names_a_dimension_that_has_no_number_to_qualify(monkeypatch):
    """A CLAUSE OVER A BLANK BOX IS A CLAIM ABOUT NOTHING.

    A stamp can outlive its value: `merge_field_provenance` only ever ADDS to the blob, so a
    researcher who fills in a length, saves, then clears it leaves the method behind. Naming it
    would put "length (vision model estimate)" on an entry whose Length box the record left empty —
    and the designer would then type their own tape reading under a sentence crediting a model.
    """
    data = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row(lengthInches=None)]},
    )
    assert "lengthCm" not in data, "the fixture must actually leave the length box empty"
    assert data["measurementMethodNote"] == "On the product record: breadth (photo measurement)"


def test_the_workshop_prints_the_record_sheets_own_two_phrases_and_not_a_third_vocabulary():
    """ONE STAMP MUST NOT BE DESCRIBED IN TWO VOCABULARIES ON TWO SURFACES.

    `record_fields.METHOD_CLAUSES`'s own comment calls this "a cross-surface contract" and names the
    drift it guards against. The workshop note IMPORTS that dict rather than restating it, so this
    test pins the two phrases themselves — the thing a future surface has to be written against —
    and pins that the note is built out of them rather than out of a paraphrase.
    """
    assert record_fields.METHOD_CLAUSES == {
        "VISION_MODEL": "vision model estimate",
        "PHOTO_GEOMETRY": "photo measurement",
    }, (
        "these two phrases are printed by the record sheet, every .xlsx sheet, both CSV exports and "
        "now the workshop report. Rewording one rewords all four; rewording it in one place only is "
        "how one stamp comes to be described in two vocabularies"
    )
    for clause in record_fields.METHOD_CLAUSES.values():
        assert clause in _PRODUCT_METHOD_NOTE or clause in _TOOL_METHOD_NOTE

    # TYPED and UNRECORDED are absent by design, not by omission — see the dict's own comment.
    assert "TYPED" not in record_fields.METHOD_CLAUSES
    assert measurement_provenance.UNRECORDED not in record_fields.METHOD_CLAUSES


def test_the_method_carry_covers_exactly_the_columns_a_method_can_be_stamped_on():
    """A PAIR FOR A COLUMN NOTHING STAMPS WOULD BE A SENTENCE ABOUT A STAMP THAT IS NEVER WRITTEN.

    `measurement_provenance.method_stamps` drops a marker naming anything outside
    `DIMENSION_FIELDS`, and that set is `{lengthInches, breadthInches, heightInches}`. So the tool's
    five unit-less columns (`height`, `width`, `thickness`, `weight`, `radius`) can never carry a
    method — that module says so itself under WHAT THE RECORD HALF STILL CANNOT REACH, and names the
    tool's missing `heightInches` column as the reason an accepted vision-model tool height is
    recorded as nothing. This is the assertion that keeps `_METHOD_CARRIED_DIMENSIONS` honest in both
    directions: nothing outside the stampable set, and every payload key it names is really hydrated.
    """
    table = dw._METHOD_CARRIED_DIMENSIONS
    assert set(table) <= set(dw.REFERENCE_MODELS)

    for model, pairs in table.items():
        columns = {column for _payload, column in pairs}
        assert columns <= measurement_provenance.DIMENSION_FIELDS, (
            f"{model} pairs a method against {sorted(columns - measurement_provenance.DIMENSION_FIELDS)}, "
            f"which method_stamps drops — the note would describe a stamp nothing writes"
        )
        # And the payload keys are the ones the mapping actually carries, so a renamed target
        # cannot leave the note qualifying a dimension the entry never receives.
        carried = {
            source
            for path, mapping in REFERENCE_HYDRATION.items()
            if _field(*path.split(".", 1)).ref_model == model
            for source in mapping
        }
        payload_keys = {payload for payload, _column in pairs}
        assert payload_keys <= carried, (
            f"{model} names payload keys {sorted(payload_keys - carried)} that no mapping hydrates"
        )

    # The five unit-less tool columns are named here so that adding one is a deliberate act.
    tool_columns = {column for _payload, column in table["ToolDocumentation"]}
    assert tool_columns.isdisjoint({"height", "width", "thickness", "weight", "radius"})
    assert "heightInches" not in tool_columns, "ToolDocumentation has no heightInches column"


async def test_the_method_note_is_recomputable_by_the_divergence_path(monkeypatch):
    """`canonical_divergence` resolves the canonical value by calling `spec.data` AGAIN, with two
    arguments — so a key it cannot reproduce is reported to an admin as diverged on every audit, for
    ever. `_media_note`'s docstring records what that already cost once ("EVERY artisan with a
    photograph read as diverged") and an audit that flags everything flags nothing.

    The note is therefore built inside the data lambda and off the row, never inside
    `hydrate_entries` where there is no row in hand. This asserts the property directly: the same
    row, resolved twice by the two paths, gives the same sentence.
    """
    row = _product_row()
    canonical = dw.REFERENCE_MODELS["ProductDocumentation"].data(row, None)
    hydrated = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [row]},
    )
    assert canonical["measurementMethodNote"] == hydrated["measurementMethodNote"]
    assert canonical["measurementMethodNote"] == _PRODUCT_METHOD_NOTE


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
        photos={"crf_1": ("med_c1", "The loom shed at Barpali")},
    )
    blank = sorted(t for t in _targets("workshopSetup.craftRef") if not data.get(t))
    assert blank == [], f"a documented craft left these cover-page boxes empty: {blank}"

    assert data["craftName"] == "Sambalpuri Ikat"
    assert data["craftLocalName"] == "ସମ୍ବଲପୁରୀ ବନ୍ଧ"
    # The four that landed earlier in this session, executed rather than declared.
    assert data["craftCategory"] == "Weaving"
    assert data["craftPlace"] == "Barpali"
    assert data["craftDocumentedOn"] == "2024-11-05"
    assert data["craftPhoto"] == "med_c1"
    # And the two this lane adds: under whose study, and what else is on file.
    assert data["craftDocumentedAtWorkshop"] == "Sambalpuri Ikat cluster survey, Barpali"
    assert data["craftMediaNote"] == (
        "Attached to the craft record: 1 photograph, 1 audio note, 1 document."
    )


async def test_where_a_record_was_documented_crosses_and_the_join_key_does_not(monkeypatch):
    """THE HALF OF ``documentedOn``'S OWN SENTENCE THAT HAD NO FIELD.

    ``participant.artisanRef`` and ``workshopSetup.craftRef`` are the two pickers declared
    ALL_SCOPE, so a roster row and a cover page may both legitimately be filled from a record made
    at a different cluster's workshop years earlier. ``documentedOn`` answers WHEN — its own comment
    says the point is to "tell a roster row filled from a 2023 survey from one filled last week" —
    and nothing answered WHERE, which is the reader's next question and the FIRST thing the record
    page asks.

    THE TITLE CROSSES AND THE ID DOES NOT, which is the same rule as every other by-value carry in
    this table: a report is a historical document and must still print the workshop's name when the
    workshop row has been renamed, merged or deleted.
    """
    roster = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"},
        rows={"artisan": [_artisan_row()]},
    )
    assert roster["documentedAtWorkshop"] == "Sambalpuri Ikat cluster survey, Barpali"
    assert "wsh_" not in str(roster), "the workshop id is a join key and must not be copied"

    # NULLABLE, AND THE COMMON CASE. `Artisan.workshopId` is optional and the artisans documented
    # before the column existed carry the WorkshopArtisan join instead, so an unset relation must
    # leave the box blank rather than reaching for the join or inventing a name.
    unlinked = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"},
        rows={"artisan": [_artisan_row(workshop=None)]},
    )
    assert "documentedAtWorkshop" not in unlinked


async def test_what_a_record_has_on_file_is_stated_and_never_copied(monkeypatch):
    """``_reference_photos`` RESOLVES ONE IMAGE, AND THE REST OF THE RECORD'S MEDIA SAID NOTHING.

    A researcher who recorded an artisan's spoken introduction — which the record form's media card
    asks for by name — or filmed a product being finished, produced material a designer standing in
    the room would want, and no box on the row could say it existed. So a reader of the printed
    roster or product table could not know to ask for it.

    WHAT IS ASSERTED IS THE SHAPE OF THE CARRY AS MUCH AS ITS PRESENCE: a SENTENCE crosses and no
    media id does. Both refusals are load-bearing and neither is squeamishness —
    ``hydrate_entries`` seeds a gallery only when it is empty because the designer's own workshop
    photographs live there, and a referenced record's files are entitlement-gated per file, which
    ``_reference_photos`` resolves for exactly one image and no more.
    """
    roster = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"},
        rows={"artisan": [_artisan_row()]},
    )
    assert roster["recordMediaNote"] == (
        "Attached to the artisan record: 1 photograph, 1 audio note."
    )
    # The ids of the counted rows are nowhere on the entry. `photo` is the ONE image
    # `_reference_photos` resolves, and no photos were offered to this call, so any med_… string
    # appearing here would be the count leaking the files it is deliberately not carrying.
    assert "med_a1" not in str(roster) and "med_a2" not in str(roster)

    product = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row()]},
    )
    assert product["recordMediaNote"] == (
        "Attached to the product record: 1 photograph, 1 video."
    )

    # NOTHING ATTACHED SAYS NOTHING, rather than "0 files". A box reading zero in a submitted report
    # is a claim about the record; a blank one is the absence of a claim, and hydration only fills
    # blanks, so a wrong sentence here would be permanent.
    silent = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row(media=[])]},
    )
    assert "recordMediaNote" not in silent


async def test_a_tools_numbered_making_sequence_is_named_and_a_grid_frame_is_not_counted(
    monkeypatch,
):
    """TWO MEDIA CARDS ON ONE RECORD PAGE, AND ONE STILL IMAGE REACHING THE REPORT.

    The tool form mounts ``MediaCaptureField`` twice: "Process stages", whose captures it renames
    ``STAGE_STEP_1``, ``STAGE_STEP_2``, … so they archive in order, and "Tool media" for video and
    audio notes. A tool whose making was documented as a numbered sequence therefore reached the
    workshop, and a ministry report, as a single photograph with nothing admitting the rest existed —
    and the relation ledger's own entry for it said "one photograph per record" as though that were
    the whole answer.

    AND A SHEET OF RULED PAPER IS NOT FOOTAGE OF THE TOOL. The grid-measurement frame carries
    ``extraMetadata.purpose = "MEASUREMENT_GRID"`` — the same three-surface marker
    ``_reference_photos`` sorts LAST rather than counting as the record's photograph — so counting it
    would overstate by one on exactly the tools that were measured most carefully.
    """
    data = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"}, rows={"tooldocumentation": [_tool_row()]},
    )
    assert data["recordMediaNote"] == (
        "Attached to the tool record: 2 photographs, 1 audio note, "
        "of which 2 document the making in order."
    )

    # ONE capture in the sequence, because a sentence in a ministry report reads as prose and not as
    # a count: "of which 1 document the making" is the kind of thing a reader notices instead of the
    # thing it is telling them.
    single = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"},
        rows={"tooldocumentation": [_tool_row(media=[
            _media_row(id="med_s1", originalFilename="STAGE_STEP_1_warping.jpg"),
        ])]},
    )
    assert single["recordMediaNote"] == (
        "Attached to the tool record: 1 photograph, of which 1 documents the making in order."
    )

    only_grid = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"},
        rows={"tooldocumentation": [_tool_row(media=[
            _media_row(id="med_g1", originalFilename="grid-length.jpg",
                       extraMetadata={"purpose": "MEASUREMENT_GRID"}),
        ])]},
    )
    assert "recordMediaNote" not in only_grid, (
        "a record whose only file is a measurement grid carries no footage of the tool"
    )
    assert dw.MEASUREMENT_GRID_PURPOSE == "MEASUREMENT_GRID", (
        "the marker is written by three uploading clients; a tidied spelling silently starts "
        "counting ruled paper as a photograph of the tool"
    )


def _widest_media_note(digits: int) -> str:
    """``_media_note``'s widest sentence when every count is ``digits`` digits wide.

    Every type word present and the numbered clause present, which is the only shape in which all
    six of the sentence's integers are printed at once.
    """
    n = 10 ** digits - 1
    rows = (
        [_media_row(mediaType="IMAGE", originalFilename="STAGE_STEP_1_x.jpg")] * n
        + [_media_row(mediaType="VIDEO")] * n
        + [_media_row(mediaType="AUDIO")] * n
        + [_media_row(mediaType="PDF")] * n
        + [_media_row(mediaType="OTHER")] * n
    )
    note = dw._media_note("product", rows, numbered_prefix="STAGE_STEP_")
    assert note is not None
    return note


def test_the_media_note_cannot_overrun_the_bound_its_field_declares():
    """``coerce_value`` REFUSES an over-length value rather than truncating it, so the bound is real.

    A refused hydration is not a small thing: the field stays blank, the designer is shown an error
    on a box they never touched, and the sentence that was supposed to tell a reader the footage
    exists says nothing at all. The four notes this function fills declare ``max_length=200``.

    ── A FORMULA AND NOT ONE MEASUREMENT, WHICH IS THE POINT OF THIS VERSION ──────────────────────
    This test used to build a note at 9999 files per type, measure 152 characters and assert
    152 <= 200. That pinned a HYPOTHESIS — "no record will ever hold more than 9999 files of one
    type" — dressed as a bound, and the sentence it called "the longest ``_media_note`` can build" is
    only the longest at four digits. The sentence prints SIX integers (five type words plus the
    numbered-making clause), so it grows by exactly six characters per digit of width. That slope is
    MEASURED below rather than assumed, and the bound is then asserted at a width no media table can
    reach instead of at a width somebody guessed was enough.

    Measured on this tree: 134 characters at one digit, 152 at four, 158 at five, 164 at six — i.e.
    ``6 * digits + 128``. At TWELVE digits per count that is exactly 200, the declared bound, and at
    thirteen it breaches — so the note fits until a single record holds 10^12 files of one type. The
    guard is real (an over-length value is REFUSED, not truncated) and it is unreachable, which is
    what a bound should say. Note that the twelve-digit assertion below is therefore EXACT rather
    than slack: lowering ``max_length`` by one character fails it, which is the intended sensitivity.
    """
    widths = {d: len(_widest_media_note(d)) for d in (1, 2, 3, 4, 5)}
    slopes = {widths[d + 1] - widths[d] for d in (1, 2, 3, 4)}
    assert slopes == {6}, (
        f"the sentence grew by {sorted(slopes)} characters per digit, not 6. Six integers are "
        f"printed in it; a different slope means a count was added or removed, and the bound below "
        f"is extrapolated from this slope, so it has to be re-derived rather than nudged"
    )
    intercept = widths[1] - 6

    # Twelve digits: 999,999,999,999 files of ONE type on ONE record. `_media_note`'s own docstring
    # calls a roster of forty long-documented artisans the worst case in the repository.
    unreachable = 6 * 12 + intercept
    for entity_key, field_key in (
        ("participant", "recordMediaNote"),
        ("existingProduct", "recordMediaNote"),
        ("tool", "recordMediaNote"),
        ("workshopSetup", "craftMediaNote"),
    ):
        bound = _field(entity_key, field_key).max_length
        assert bound and unreachable <= bound, (
            f"{entity_key}.{field_key} declares max_length={bound}. `_media_note` builds "
            f"6 * digits + {intercept} characters, so coerce_value would REFUSE it at "
            f"{(bound - intercept) // 6 + 1} digits per count"
        )


def test_the_process_media_note_has_its_own_bound_and_its_own_grammar():
    """``traditionalProcess.recordMediaNote`` is filled by ``_process_media_note``, not ``_media_note``.

    THE TEST ABOVE USED TO ASSERT THIS BOX AGAINST THE OTHER FUNCTION'S LONGEST OUTPUT, which was
    harmless over-strictness with a real hole in it: the one note of the five whose grammar is NOT
    the one being measured was the one left unmeasured. This function prints a total and a per-step
    breakdown ("N on the process itself, N across N step(s)") off an entirely different sentence,
    counts no media TYPES, and — see the paragraph naming it in ``_media_note``'s docstring — does
    not skip a measurement-grid frame, because nothing can attach one to a process record.

    THREE integers, so the sentence is fixed text plus however many digits those three occupy.
    Measured on this tree: 73 characters of fixed text, so breaching 200 needs 127 digits spread
    across three counts.
    """
    def note(own: int, steps: int, per_step: int) -> str:
        process = SimpleNamespace(
            media=[_media_row()] * own,
            steps=[SimpleNamespace(media=[_media_row()] * per_step)] * steps,
        )
        built = dw._process_media_note(process)
        assert built is not None
        return built

    # (own, steps, per-step) -> how many digits the three PRINTED integers occupy in total. The
    # middle one is the sum across steps, so it is wider than either input.
    cases = {
        (9, 9, 9): 1 + 2 + 1,               # own=9, per_step=81, covered=9
        (99, 99, 99): 2 + 4 + 2,            # own=99, per_step=9801, covered=99
        (999, 999, 999): 3 + 6 + 3,         # own=999, per_step=998001, covered=999
    }
    fixed = {len(note(*args)) - digits for args, digits in cases.items()}
    assert len(fixed) == 1, (
        f"the fixed text measured {sorted(fixed)} characters at three different widths, so the "
        f"sentence is no longer 'fixed text plus the digits of three integers' and the bound below "
        f"has to be re-derived"
    )
    fixed_chars = fixed.pop()

    bound = _field("traditionalProcess", "recordMediaNote").max_length
    # 30 digits across three counts is already past anything a bigint column could hold.
    assert bound and fixed_chars + 30 <= bound, (
        f"traditionalProcess.recordMediaNote declares max_length={bound} and "
        f"`_process_media_note` spends {fixed_chars} characters before printing a single digit, "
        f"leaving {bound - fixed_chars} for three counts — tight enough for coerce_value to REFUSE "
        f"a real process record's note"
    )


async def test_the_pin_on_the_subjects_place_now_crosses_for_all_three_models(monkeypatch):
    """INVARIANT 4 HAS TWO HALVES AND ONLY THE ARTISAN HONOURED BOTH.

    The device's fix never crosses as an address — on this database every one of those fixes is the
    desk the record was typed at, routinely 1,500 km from the village named on the same row — and the
    STATED columns and the SUBJECT pin do. The product and tool carries landed the stated strings and
    left the one coordinate that is genuinely about the village reaching nothing, because neither
    entity declared a GEO field at all.

    The GEO type is also the requirement-(b) answer for this box on both surfaces: it renders as the
    same map picker the record page mounts, so nothing client-side is needed.
    """
    product = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row()]},
    )
    tool = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"}, rows={"tooldocumentation": [_tool_row()]},
    )
    for data in (product, tool):
        assert data["recordSubjectLocation"] == {"lat": 21.2, "lon": 83.6}
        assert "accuracy" not in data["recordSubjectLocation"], (
            "a hand-dropped pin has no error bar, and `coerce_value` keeps the key optional so "
            "that 'somebody pointed at this' and 'a device measured this' stay distinguishable"
        )
        # THE DESK'S OWN FIX, WHICH IS THE POINT OF THE INVARIANT. The fixture's Location carries a
        # real one (Kharagpur, against an Odisha village) and none of it may appear anywhere.
        for provenance in ("22.314", "87.311", "Kharagpur", "Plot 14"):
            assert provenance not in str(data), (
                f"{provenance!r} is provenance — the desk the record was typed at — and must never "
                "cross as part of an address"
            )

    half = _location_row(subjectLatitude=21.2, subjectLongitude=None)
    assert dw._subject_point(half) is None, "half a coordinate is not a coordinate"


async def test_a_field_promoted_to_rich_text_still_receives_the_records_prose(monkeypatch):
    """THE PROMOTION THAT GIVES A DESIGNER THE EDITOR MUST NOT COST THEM THE CARRIED TEXT.

    Seven hydration targets moved from TEXT/LONG_TEXT to RICH_TEXT so that the workshop offers the
    same control the record page offers for the same fact — the record forms call these their
    narrative boxes and give every one of them the full editor. The registry documents the promotion
    as supported and backfill-free, on the grounds that ``coerce_value`` reads a plain string as
    unformatted prose; this executes it, because "documented as safe" and "safe" are different
    claims and the failure mode is a paragraph a researcher wrote arriving blank.

    ``_reference_data`` flattens the source's own formatting on the way across (that is what stopped
    ``{"blocks":…}`` printing into a ministry table), so what must survive is the TEXT.
    """
    from app.services import rich_text

    product = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row()]},
    )
    tool = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"}, rows={"tooldocumentation": [_tool_row()]},
    )
    process = await _hydrate(
        monkeypatch, "traditionalProcess", {"processRef": "prc_1"},
        rows={"process": [_process_row()]},
    )
    for entity_key, data, field_key, expected in (
        ("existingProduct", product, "material", "Cotton yarn"),
        ("existingProduct", product, "mainToolsUsed", "Pit loom, bobbin winder"),
        ("existingProduct", product, "use", "Daily and festive wear"),
        ("existingProduct", product, "remarks", "Second-quality weft in one panel."),
        ("tool", tool, "improvements", "A higher bench would ease the back."),
        ("tool", tool, "remarks", "Rebuilt in 2019."),
        ("traditionalProcess", process, "documentedProcessNotes",
         "Yarn is tied in sections, dyed, untied, washed."),
    ):
        spec = _field(entity_key, field_key)
        assert spec.type is FieldType.RICH_TEXT, f"{entity_key}.{field_key} is the field under test"
        stored = data.get(field_key)
        assert stored, f"{entity_key}.{field_key} arrived empty after the promotion"
        assert rich_text.to_plain(rich_text.from_json(stored)).strip() == expected


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
        # THIN MEANS THIN, INCLUDING THE RELATIONS. An artisan typed in during a workshop has no
        # `workshopId` (the column is nullable) and nothing attached, and both have to be spelled
        # here: the default fixture now carries a workshop and two files, so inheriting them would
        # have left this test asserting that a well-documented record clears a well-documented one.
        workshop=None, media=[],
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

    THE OBVIOUS "FIX" WAS THE DANGEROUS ONE AND THIS TEST USED TO PIN AGAINST IT, by asserting that
    `ProductDocumentation` and `ToolDocumentation` declared NO include at all. The hazard it was
    guarding is real: `_reference_place` prefers `location.village` over the free-text `place` and
    runs at REPORT time, so switching that relation on would have changed the place string printed in
    documents already submitted. What has changed is not the hazard but the mechanism — that function
    is now guarded on the MODEL NAME, so it returns the denormalised `place` for everything except an
    artisan whether or not the relation is loaded, and the stated address reaches the workshop through
    the data lambdas at SAVE time into boxes of its own instead.

    SO THE RULE IS "EVERY DECLARED RELATION HAS A NAMED READER", not "declare none". This table is
    that list, and it is the maintenance cost on purpose: an include added without a reader — a join
    on every picker keystroke, every save and every report for a value nobody looks at — fails here.
    """
    readers = {
        "Artisan": {
            "craft": "participant.specialisation",
            "location": "the STATED address columns and the subject pin",
            "workshop": "participant.documentedAtWorkshop (Workshop.title)",
            "media": "participant.recordMediaNote, counted by type in _media_note",
        },
        "ProductDocumentation": {
            "location": "existingProduct.recordState/District/Village/Pincode/SubjectLocation",
            "media": "existingProduct.recordMediaNote",
        },
        "ToolDocumentation": {
            "location": "tool.recordState/District/Village/Pincode/SubjectLocation",
            "media": "tool.recordMediaNote, including the STAGE_STEP_ making sequence",
            "artisanLinks": "tool.usedByArtisans, via _linked_artisan_names",
        },
        "Process": {
            "product": "processStep.documentedFor / traditionalProcess.documentedFor",
            "steps": "traditionalProcess.documentedSteps, via _step_lines",
            # NO `media` ROW, AND ITS ABSENCE IS THE POINT. `_process_media_note` is still called by
            # the data lambda — dropping the key would break the promise
            # `test_a_field_that_promises_to_fill_itself_in_actually_does` checks — but it now returns
            # None for every process, because `MediaFile` has no `processId` and so `Process` has no
            # `media` relation to join. The include that claimed otherwise made Prisma refuse the
            # whole query and 500'd both process pickers until 2026-08-23. THIS TEST IS WHAT CAUGHT
            # THE HALF-FIX: removing the include without removing this row left a reader named for a
            # join that no longer happens. Restore both together, or neither — the price of the
            # `processId` foreign key is written out above that include.
        },
        "Craft": {
            "media": "workshopSetup.craftMediaNote",
            "workshop": "workshopSetup.craftDocumentedAtWorkshop (Workshop.title)",
        },
    }
    for model, spec in dw.REFERENCE_MODELS.items():
        assert set(spec.include or {}) == set(readers.get(model, {})), (
            f"REFERENCE_MODELS[{model!r}].include and this test disagree about which relations are "
            "joined. An include with no reader is a join issued on every picker keystroke for a "
            "value nobody looks at; name the reader here, or drop the include."
        )


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
# hydration target opposite one of them WAS a TEXT or LONG_TEXT box, ``coerce_value``'s text branch
# passes a string through ``clean_text`` unchanged, and ``report_builder.format_value`` only unwraps
# a document for a RICH_TEXT field, where the value is a dict.
#
# So the JSON was copied onto the stage entry verbatim and printed verbatim — a
# ``{"blocks": …}`` string in a table column of a document submitted to a ministry — and every
# emptiness check upstream read that JSON-shaped string as a filled field, so nothing anywhere
# reported a problem. ``design_workshops._reference_data`` is the flattening; these are its guards.
#
# SEVEN OF THOSE TARGETS ARE NOW RICH_TEXT THEMSELVES, which does not weaken the flattening and does
# not change what it is for. It was promoted so the workshop offers the editor the record page offers
# for the same fact; the carried value still arrives FLATTENED and is re-read as unformatted prose, so
# the researcher's marks are still lost across the join (recovering them is a change to
# ``_reference_data`` and is not this lane's). What the promotion changes is only the STORED SHAPE on
# the workshop side, and the parametrised test below keys its assertion on the target's declared type
# for exactly that reason.


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

    ── THE ASSERTION IS KEYED ON THE TARGET'S TYPE, AND IT DID NOT USED TO BE ────────────────────
    It read ``"blocks" not in landed`` for every pair, on the premise — stated in the block comment
    above and in ``_reference_data``'s docstring — that "every hydration target opposite one of them
    is a TEXT or LONG_TEXT box". Seven of these targets have since been promoted to RICH_TEXT so the
    workshop offers the same editor the record page offers, and for those the stored value is a
    document BY DESIGN: ``coerce_value``'s RICH_TEXT branch normalises it through the rich-text model
    and ``report_builder.format_value`` unwraps it by TYPE, so a dict there prints as prose and is not
    the defect. The defect is a JSON-shaped STRING sitting in a plain-text box, which is still exactly
    what the other two pairs must never see.

    So both arms assert the same thing about what a reader ends up with — the researcher's words, no
    braces — and neither one weakens: a RICH target that received the raw JSON as a STRING would fail
    the plain-text comparison below, because ``from_json`` of that string reads the braces as prose.
    """
    stored = _formatted("Tied with cotton thread", "then dipped in indigo.")
    row = globals()[row_builder](**{column: stored})
    sent = {ref_key: row.id}
    if entity_key == "processStep":
        sent["stepNumber"] = 1

    data = await _hydrate(monkeypatch, entity_key, sent, rows={delegate: [row]})

    landed = data.get(target)
    assert landed, f"{model}.{column} reached {entity_key}.{target} as nothing at all"
    if _field(entity_key, target).type is FieldType.RICH_TEXT:
        from app.services import rich_text

        assert isinstance(landed, dict), (
            f"{entity_key}.{target} is RICH_TEXT, so coerce_value must have normalised the carried "
            f"prose into a document; it stored {landed!r}"
        )
        assert rich_text.to_plain(rich_text.from_json(landed)).strip() == (
            "Tied with cotton thread\nthen dipped in indigo."
        )
        return
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

    # THE FLOOR IS MEASURED, NOT REMEMBERED. It said 81 until 2026-08-22, which was the count at
    # some earlier point in the same lane — the table had already grown past it, so the assertion
    # was passing with 26 pairs of slack and would have gone on passing through the removal of a
    # quarter of the carry. A floor with slack in it is a floor that is not holding anything up.
    # Re-measure before raising it again:
    #   PYTHONUTF8=1 .venv/Scripts/python.exe -c "import app.services.stage_definitions; \
    #     from app.services.stage_schema import REFERENCE_HYDRATION; \
    #     print(sum(len(m) for m in REFERENCE_HYDRATION.values()))"
    pairs = sum(len(m) for m in REFERENCE_HYDRATION.values())
    assert pairs >= 107, (
        f"the carry is down to {pairs} field-pairs; it was 32 before this lane and 107 as measured "
        "on 2026-08-22, and a drop means a mapping was removed rather than a source column "
        "disappearing"
    )

def test_no_reference_model_asks_prisma_for_a_media_relation_its_delegate_does_not_have():
    """A ``media`` include is only legal on a delegate whose model really declares the relation.

    THIS IS THE CLASS BEHIND A LIVE 500, and the instance is worth stating because the comment that
    should have prevented it was already there and already correct. ``REFERENCE_MODELS["Process"]``
    opened with "``MediaFile`` has no ``processId``" — true — and then asked for
    ``include={"media": True}`` on both ``Process`` and ``ProcessStep``. Prisma refuses that query
    outright (``UnknownRelationalFieldError``), so BOTH process pickers 500'd for every designer on
    every save of stage 5 until 2026-08-23.

    ``MediaFile`` reaches a record two different ways and only one of them is a relation: typed
    foreign keys (``artisanId``, ``craftId``, ``workshopId``, ``productId``, ``toolId``) which give
    the model a ``media MediaFile[]`` back-relation, and the polymorphic
    ``linkedRecordType``/``linkedRecordId`` pair, which gives it nothing Prisma can include. A
    process's footage exists — ``ProcessForm`` writes it as ``linkedRecordType: "process"`` and
    ``"processstep"`` — it simply cannot be reached by an include.

    So this reads the schema rather than a hand-kept list: every delegate that declares
    ``media MediaFile[]`` may ask for it, and nothing else may. Adding a sixth model with a media
    include and no back-relation fails here instead of in production.
    """
    schema = SCHEMA.read_text(encoding="utf-8")

    # Models whose Prisma block declares the back-relation, keyed the way `delegate` spells them.
    with_relation = {
        name.lower()
        for name, body in re.findall(r"^model\s+(\w+)\s*\{(.*?)^\}", schema, re.DOTALL | re.MULTILINE)
        if re.search(r"^\s+media\s+MediaFile\[\]", body, re.MULTILINE)
    }
    assert "artisan" in with_relation, (
        "sanity check failed: Artisan should declare `media MediaFile[]`, so either the regex or "
        f"the schema changed shape. Parsed: {sorted(with_relation)}"
    )

    offenders = []
    for model_name, spec in dw.REFERENCE_MODELS.items():
        include = getattr(spec, "include", None) or {}
        if not _asks_for_media(include):
            continue
        if spec.delegate.lower() not in with_relation:
            offenders.append(f"{model_name} (delegate={spec.delegate!r})")

    assert not offenders, (
        "these reference models ask Prisma for a `media` relation their delegate does not declare, "
        f"which makes the whole picker query raise UnknownRelationalFieldError: {offenders}. Either "
        "give the model a real foreign key on MediaFile (a migration plus a backfill from the "
        "existing linkedRecordType tags — the cost is written out above REFERENCE_MODELS['Process']"
        "'s include) or drop the include. It cannot be replaced by a media QUERY: see the TRIED AND "
        "REFUSED section of _reference_media_note."
    )


def _asks_for_media(include: dict) -> bool:
    """True when ``include`` asks for ``media`` at the top level or nested under a relation."""
    for key, value in include.items():
        if key == "media" and value:
            return True
        if isinstance(value, dict) and _asks_for_media(value.get("include") or {}):
            return True
    return False
