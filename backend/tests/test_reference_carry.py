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
    "locationId": "participant.village/state/district/pincode/address/subjectLocation",
}
ARTISAN_NOT_CARRIED = {
    "aadhaarNumber": (
        "identity. Masked on every exported surface by policy (records.mask_identity_number, and "
        "the scar in record_fields.py:270-283). It is the deduplication key; 'XXXX XXXX 9012' in a "
        "participant table answers no question a design report asks, and the artisan is already "
        "identified by name, Pehchan card and phone."
    ),
    "status": "bookkeeping — the source record's moderation state, not a fact about the artisan",
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
    "address": "participant.address (fallback when Artisan.address is empty)",
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
        "arrive in the report's product gallery as a picture of a ruler. The catalogue shot is "
        "carried through _reference_photos."
    ),
    "measurementAnalysis": (
        "machine output. report_templates.py:69-84 requires a reader to be able to tell model "
        "prose from an author's, and this has no such treatment — it would need the naming "
        "ANNEXURE_AI_LAYERS gets."
    ),
    "measurementAnalysisStatus": "queue state of the above",
    "status": "bookkeeping",
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
    "measurementImageId": "a working image from the measurement pipeline; see the product note",
    "measurementAnalysis": "machine output; see the product note",
    "measurementAnalysisStatus": "queue state of the above",
    "status": "bookkeeping",
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
    "status": "bookkeeping",
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
        address="Weavers' lane, near the tank", placeName="Kharagpur",
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

    async def query_raw(self, _sql, ids):
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
# To retire an entry: rebalance that entity's first six widths to 100 and delete its line.
_WIDTHS_THAT_DO_NOT_GOVERN = {
    "sketch": 88.0,
    "prototype": 112.0,
    "prototypeValidation": 86.0,
    "finalProduct": 120.0,
    "followUp": 88.0,
}


def _governing_widths(entity):
    """The widths ``report_builder._render_table`` would actually read, and their sum.

    ``_table_columns`` caps at six, so a seventh declared column contributes NOTHING to the sum and
    prints as a key-value line beneath each row instead. ``existingProduct`` declares seven and its
    first six add to exactly 100, which is somebody having done this arithmetic on purpose; two of
    the entities in the exemption above declare seven and did not.
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
    with the sum each reaches.

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
            assert abs(total - recorded) < 0.5, (
                f"{entity.key}'s widths have moved from {recorded} to {total} while still not "
                "governing — rebalance them to 100 rather than to another number nothing reads"
            )
    assert drifted == [], (
        "these tables' declared widths have stopped governing, so report_builder is laying them "
        f"out proportionally and the percentages beside their fields do nothing: {drifted}"
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
