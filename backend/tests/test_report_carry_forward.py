"""Does a field carried in from a portal record actually PRINT?

THE REQUIREMENT THIS FILE EXISTS FOR, in the owner's own words: a designer maps an artisan, a
product, a process, a tool, a questionnaire, a transcript or a piece of media into a design
workshop, and *"it is okay to have more fields over there, but not less than that."* Everything
else in this repository checks the first half of that journey — that the picker copies the value
onto the stage entry. Nothing checked the second, and the second is the one the sentence is
actually about: the fields have to reach **the report**, because the report is the artefact that
goes to the ministry.

The survey that preceded this work claimed the report needs no work at all for scalars —
``FieldSpec.report_role`` defaults to ``KEY_VALUE``, tables print their overflow fields underneath
each row, and five of the six templates admit every capture tier — so *"reaches the workshop"*
implies *"reaches the report"*. **That claim is true, and this file is what makes it stay true.**
It is one assertion made three ways:

1.  **Structurally**, off the two declaration tables themselves. ``REFERENCE_HYDRATION`` says which
    entity field each carried value lands on; every one of those fields is checked to have a role
    and a shape the renderer can actually print. A widening that lands a value on a
    ``report_role=HIDDEN`` box, or on a ``METRIC`` field of a collection, or as a seventh table
    column in an entity whose six declared widths already sum to 100, fails HERE — at the pair
    that was added, naming it — rather than silently in a sixty-page document nobody diffs.

2.  **End to end**, through the real machinery. A fully populated Artisan, ProductDocumentation,
    ToolDocumentation, Process and Craft are pushed through the real
    ``REFERENCE_MODELS[...].data`` lambdas and the real ``REFERENCE_HYDRATION`` map into real stage
    entries, the report is rendered, and every carried value is asserted to appear in the document
    **as the report formats it** — "₹ 1,250.00", "12 years", "Packaging", "45.7 cm". That last
    clause is the point: a value that prints as a raw enum token or as an unlabelled number has
    reached the report and not reached the reader.

3.  **Per template**, over all six. Three of them print every stage at every tier and carry the
    whole set. The other three do not, for reasons that are decisions rather than defects, and the
    two SILENT ones now raise a warning — which is asserted here too, because a loss the designer
    is told about is a different thing from a loss they are not.

**THE FIXTURES DELIBERATELY REFUSE AN UNKNOWN COLUMN.** ``_SourceRow.__getattr__`` raises instead
of returning ``None``, so widening ``REFERENCE_MODELS[...].data`` to read a new column of the
source table fails this file with a message naming the column. That is not friction for its own
sake: a fixture that quietly answers ``None`` would let a new pair be added, carry nothing, and
pass — which is precisely the failure mode ("not all fields are being carried faithfully") that
this work was commissioned to end.

No database and no network: the builder is driven directly over the registry, the way
``test_report_empty_note`` and ``test_report_rich_text_fields`` drive it.
"""

from __future__ import annotations

from typing import Any

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.design_workshops import (
    _PRODUCT_TYPE_TO_CATEGORY,
    REFERENCE_MODELS,
    ReferencePhoto,
)
from app.services.report_annexures import TranscriptItem, attach_transcripts
from app.services.report_builder import (
    COVER_INFO_ROWS,
    ReferencedRecord,
    ReportBuilder,
    WorkshopData,
    build_report,
    format_value,
)
from app.services.report_model import (
    CoverBlock,
    ImageBlock,
    ImageGridBlock,
    ImageRef,
    KeyValueBlock,
    MetricRowBlock,
    ParagraphBlock,
    ReportMeta,
    TableBlock,
    runs_text,
)
from app.services.report_templates import TEMPLATES, SpecialSection, template
from app.services.stage_schema import (
    ENUMS,
    REFERENCE_HYDRATION,
    Cardinality,
    EntitySpec,
    FieldSpec,
    FieldType,
    ReportRole,
    stages,
)

# --------------------------------------------------------------------------------------
# Reading the two declaration tables
# --------------------------------------------------------------------------------------

#: entity key -> {entity field key: the reference data key it is filled from}. Built by INVERTING
#: ``REFERENCE_HYDRATION``, which is declared the other way round (source key -> target key)
#: because that is the direction hydration copies in. Every test below walks this rather than a
#: list of its own, which is the whole reason a pair added by another lane is covered the moment
#: it is declared.
CARRIED: dict[str, dict[str, str]] = {}
for _path, _mapping in REFERENCE_HYDRATION.items():
    _entity_key, _, _ref_field = _path.partition(".")
    CARRIED.setdefault(_entity_key, {}).update({t: s for s, t in _mapping.items()})

_ENTITIES: dict[str, EntitySpec] = {e.key: e for s in stages() for e in s.entities}
_ENTITY_STAGE: dict[str, str] = {e.key: s.key for s in stages() for e in s.entities}


def _spec(entity_key: str, field_key: str) -> FieldSpec:
    field = _ENTITIES[entity_key].field(field_key)
    assert field is not None, (
        f"{entity_key}.{field_key} is a REFERENCE_HYDRATION target with no field to land on. "
        f"stage_schema.validate_registry refuses this; if you are reading it here the registry "
        f"check was skipped."
    )
    return field


def _hydration_pairs() -> list[tuple[str, str, str]]:
    """``(entity key, entity field key, reference data key)`` for every declared pair."""
    return [(entity_key, target, source)
            for entity_key, fields in CARRIED.items()
            for target, source in fields.items()]


# --------------------------------------------------------------------------------------
# 1. Structural: every carried value lands somewhere the renderer prints
# --------------------------------------------------------------------------------------

#: The roles ``_render_narrative`` and ``_render_table`` between them put on the page for a stage
#: section. Kept as a literal rather than derived, because the point of the test below is to
#: notice when the renderer and the registry stop agreeing — deriving it from the renderer would
#: make the test agree with whatever the renderer happens to do.
_PRINTED_ROLES = frozenset({
    ReportRole.NARRATIVE, ReportRole.KEY_VALUE, ReportRole.TABLE_COLUMN,
    ReportRole.COVER_FIELD, ReportRole.BULLETS,
})


@pytest.mark.parametrize("entity_key,target,source", _hydration_pairs(),
                         ids=lambda v: str(v))
def test_every_carried_field_has_a_role_the_report_prints(entity_key, target, source):
    """A carried value on a box the report never opens is a carry that did not happen.

    ``report_role=HIDDEN`` is the trap this catches, and it is not hypothetical: five of the REF
    fields that DRIVE hydration are themselves HIDDEN (``participant.artisanRef`` and its four
    siblings), correctly — an id is not a thing to print — so HIDDEN is right there in the same
    entity, one line away, when somebody declares the box the value lands on.
    """
    spec = _spec(entity_key, target)
    if spec.type in (FieldType.IMAGE, FieldType.IMAGE_LIST):
        # A PICTURE is placed by ``_images``, which reads the field TYPE and not the role, so the
        # role on an image field is documentation. ``test_a_carried_photograph_is_placed`` covers
        # the actual placement.
        #
        # THIS USED TO SAY "media" AND EXEMPT ALL FIVE MEDIA TYPES, which was true of the role and
        # false about the outcome: ``_images`` filters on IMAGE and IMAGE_LIST, so a carried FILE,
        # AUDIO or VIDEO was placed by nothing and printed by nothing, and this test waved it
        # through. ``format_value`` now prints "1 document attached" for those from their
        # KEY_VALUE role, so the role on them is load-bearing again and the assertion below
        # applies to them exactly as it does to a text field.
        #
        # AND TODAY IT APPLIES TO NO PARAMETER, WHICH IS WHY THE NARROWING IS RECORDED HERE RATHER
        # THAN CLAIMED AS A GUARD. Every media target in ``REFERENCE_HYDRATION`` is ``photo`` ->
        # ``photo`` / ``productPhotos``, and no FILE, AUDIO or VIDEO field is a hydration target at
        # all — so narrowing the exemption changed the outcome for zero cases. It is the shape the
        # rule will have when one is added, not evidence that anything is currently covered.
        return
    if spec.report_role is ReportRole.CAPTION:
        # A caption is printed UNDER ITS PICTURE by ``_images`` and is deliberately withheld from
        # ``_printable`` ("captions are placed with their image, never on their own"), so CAPTION
        # is a printing role — but only when the field it captions actually exists and is media.
        # A caption pointing at nothing is a value with no page to appear on.
        captioned = _ENTITIES[entity_key].field(spec.caption_for)
        assert captioned is not None and captioned.type.is_media, (
            f"{entity_key}.{target} is a CAPTION for {spec.caption_for!r}, which is not a media "
            f"field of this entity, so the carried caption is printed by nothing."
        )
        return
    assert spec.report_role in _PRINTED_ROLES, (
        f"{entity_key}.{target} receives {source!r} from the picker and is declared "
        f"report_role={spec.report_role.value}, which no stage section prints. Give it "
        f"KEY_VALUE (the default) unless it genuinely belongs in a table."
    )


@pytest.mark.parametrize("entity_key,target,source", _hydration_pairs(),
                         ids=lambda v: str(v))
def test_no_carried_field_is_a_metric(entity_key, target, source):
    """METRIC is the one role that is not simply "printed somewhere else".

    ``_render_stage`` draws a ``MetricRowBlock`` from the SINGLETON's METRIC fields and takes
    ``metrics[:4]``; a collection's METRIC fields are asked for by nothing at all. So a carried
    value declared METRIC on a participant, a tool or a product row prints NOWHERE, and one
    declared METRIC as a singleton's fifth prints nowhere either. Neither failure is visible in
    the document — there is simply no line where the value would have been.
    """
    spec = _spec(entity_key, target)
    assert spec.report_role is not ReportRole.METRIC, (
        f"{entity_key}.{target} is declared METRIC. A collection's METRIC fields are never "
        f"rendered and a singleton prints only its first four; use KEY_VALUE."
    )


#: Entities whose declared ``column_width_pct`` values ALREADY do not sum to 100 over the six
#: columns the renderer draws, so ``_render_table`` already discards them and lays the table out
#: proportionally. This is a pre-existing condition and not something a carry introduced — five
#: entities are in this state repository-wide (``sketch`` 88, ``prototype`` 112,
#: ``prototypeValidation`` 86, ``finalProduct`` 120, ``followUp`` 88) and their carefully declared
#: widths have never had any effect. Listed rather than fixed because re-balancing a table's
#: columns changes documents already submitted to a ministry, and that is an editorial change
#: somebody should make deliberately; listed rather than ignored so that the OTHER receiving
#: entities cannot quietly join them. Reported to the backend lane.
_WIDTHS_ALREADY_INERT = frozenset({"prototype"})


@pytest.mark.parametrize("entity_key", sorted(CARRIED))
def test_a_carried_table_keeps_its_declared_column_widths(entity_key):
    """Adding a table column to a receiving entity must not silently re-lay-out a submitted table.

    ``_render_table`` uses the DECLARED ``column_width_pct`` values only when the first six of them
    sum to 100; anything else falls through to a proportional guess that gives free-text columns
    twice the share of a number. Every entity that receives a carried value declares widths summing
    to exactly 100 today, and three of them say so in their own comments — ``tool.toolFamily`` and
    ``processStep.documentedFor`` are both KEY_VALUE rather than a sixth column for this exact
    reason, written down at the field.

    So the widening rule for these tables is: **a new carried field is KEY_VALUE.** A seventh
    TABLE_COLUMN is not lost — ``_render_table`` prints the overflow under each row, which is why
    ``existingProduct.material`` still reaches the page as the seventh of seven — but a new column
    inserted ABOVE an existing one changes which six are drawn, and the sum stops being 100, and a
    table that is already inside documents held by a ministry is quietly re-laid-out.
    """
    if entity_key in _WIDTHS_ALREADY_INERT:
        pytest.skip(f"{entity_key}'s declared widths were already ignored before this work")
    columns = [f for f in _ENTITIES[entity_key].fields
               if f.report_role is ReportRole.TABLE_COLUMN and not f.deprecated]
    declared = sum(c.column_width_pct for c in columns[:6])
    assert not declared or abs(declared - 100.0) < 0.5, (
        f"the six drawn columns of {entity_key} declare widths summing to {declared}, so "
        f"_render_table discards all of them and lays the table out proportionally instead. "
        f"Declare a carried field as KEY_VALUE, or re-balance every width in the entity."
    )


def test_a_carried_enum_can_only_receive_a_token_the_report_can_name():
    """The raw-token failure, pinned at the one table that can produce one.

    ``enum_label`` falls back to printing the STORED TOKEN when it does not recognise it, on
    purpose — a phone one release ahead must not fail an export in the field. That fallback is a
    good rule and a bad outcome: the value it protects reaches a document submitted to a ministry
    reading ``FINISHED_GOOD``.

    Hydration is the one writer that can reach it. Every other route into an ENUM field goes
    through ``coerce_value``, which refuses a token the registry does not declare;
    ``hydrate_entries`` mutates the cleaned payload AFTER that check, so whatever
    ``_PRODUCT_TYPE_TO_CATEGORY`` yields is stored and printed unexamined.

    THIS IS NOT A CHECK THAT EVERY ProductType IS MAPPED. Four of the six are deliberately
    unmapped, with the reason written above the table — ProductType asks what KIND of record this
    is and the registry's category asks what the product IS, and guessing across them would fill a
    ministry report's category column with plausible wrong values. Whether to widen that map is the
    backend lane's decision. What is not anybody's decision is mapping a token to a category the
    report cannot name.
    """
    declared = set(ENUMS["PRODUCT_CATEGORY"])
    produced = {v for v in _PRODUCT_TYPE_TO_CATEGORY.values() if v}
    assert produced <= declared, (
        f"_PRODUCT_TYPE_TO_CATEGORY maps a ProductType onto {sorted(produced - declared)}, which "
        f"is not a PRODUCT_CATEGORY token. enum_label would print it verbatim into the report."
    )
    # And the general form, so a future ENUM-typed carry is covered without another test.
    for entity_key, target, _source in _hydration_pairs():
        spec = _spec(entity_key, target)
        if spec.type in (FieldType.ENUM, FieldType.MULTI_ENUM):
            assert spec.enum in ENUMS, (
                f"{entity_key}.{target} is an ENUM field with no canonical option list, so every "
                f"value hydrated into it prints as its raw token."
            )


#: What a numeric field's LABEL may say instead of declaring a unit. See the test below for why the
#: label and not the help text: ``help`` is published to the form and to the phone and is read by a
#: designer standing in front of the box, and it is printed by no part of the report.
_UNIT_UNKNOWN_MARKERS = ("as recorded", "unit not stated", "unit unknown")


def test_a_carried_measurement_says_what_it_is_measured_in():
    """A number in a government report with no unit beside it is a number nobody can use.

    THE ONE THING THE REPORT PRINTS IS THE LABEL AND THE VALUE. ``format_value`` appends
    ``spec.unit`` to a numeric and nothing else adds one — a table header carries the label alone,
    a key-value pair carries the label alone, and ``FieldSpec.help`` is published to every client
    and printed by no part of this pipeline. So the unit reaches the reader through the unit or
    through the label, and through nothing else.

    That matters twice over on this carry. ``ProductDocumentation.lengthInches`` is INCHES and
    ``existingProduct.lengthCm`` is CENTIMETRES, and the conversion between them is invisible in
    the finished document unless the field says "cm" — a converted number printed bare is
    indistinguishable from an unconverted one, and the officer reading it cannot ask.

    THE SECOND ARM IS FOR A MEASUREMENT WHOSE UNIT GENUINELY IS NOT KNOWN.
    ``ToolDocumentation.height``/``width``/``thickness``/``weight``/``radius`` carry no unit in the
    column, in the record form's labels or on the printed record sheet, so the five
    ``*AsRecorded`` fields declare none — which is the honest declaration, and giving them
    ``unit="cm"`` would turn an unknown into a stated wrong answer. What they must not do is print
    as a bare number under a label a reader will complete for themselves, so the LABEL has to carry
    the doubt. "(as recorded)" does that job weakly — it reads as "recorded faithfully" at least as
    easily as "in an unstated unit" — and the wording is ``stage_definitions``' to choose;
    "(unit not stated)" is what the help text already says and would print the whole fact. Either
    passes here. A bare "Height" would not, and that is the case this test exists to refuse.
    """
    for entity_key, target, source in _hydration_pairs():
        spec = _spec(entity_key, target)
        if spec.type not in (FieldType.INT, FieldType.DECIMAL):
            continue
        if spec.unit:
            printed = format_value(spec, 12)
            assert spec.unit in printed, f"{entity_key}.{target} printed {printed!r}"
            continue
        label = spec.label.casefold()
        assert any(marker in label for marker in _UNIT_UNKNOWN_MARKERS), (
            f"{entity_key}.{target} carries {source!r} as a bare {spec.type.value} with no unit "
            f"and a label ({spec.label!r}) that does not say the unit is unknown, so the report "
            f"prints a number a reader will silently assume a unit for. Declare unit=, or say so "
            f"in the LABEL — the help text is not printed by any report."
        )


# --------------------------------------------------------------------------------------
# 2. End to end: a fully populated source record, through the real tables, into a document
# --------------------------------------------------------------------------------------


class _SourceRow:
    """A row of a portal table, standing in for the Prisma model.

    **IT RAISES ON A COLUMN IT WAS NOT GIVEN, AND THAT IS THE FEATURE.** ``REFERENCE_MODELS[…].data``
    reads attributes off this object; a stand-in that answered ``None`` for anything unknown would
    let a widened lambda read a new column, carry ``None``, print nothing, and pass every assertion
    below — which is the exact shape of the defect this whole file was written for. Failing names
    the column and points at this fixture.
    """

    def __init__(self, **columns: Any) -> None:
        self.__dict__.update(columns)

    def __getattr__(self, name: str) -> Any:  # only reached for a column not supplied
        raise AssertionError(
            f"REFERENCE_MODELS read the column {name!r}, which this fixture does not set. If you "
            f"have just widened a reference model, add {name!r} to the source row in "
            f"tests/test_report_carry_forward.py so the new value is actually asserted on."
        )


def _media(media_type: str, filename: str, purpose: str | None = None) -> _SourceRow:
    """One ``MediaFile`` row as the ``media`` relation hands it to a data lambda.

    All three attributes ``_media_note`` reads are always set, INCLUDING the ones it only reads on
    some models: ``_SourceRow`` raises on a column it was not given, and ``getattr(row, name,
    default)`` does not rescue that (the default only catches ``AttributeError``), which is the
    behaviour that makes this fixture worth having.
    """
    return _SourceRow(mediaType=media_type, originalFilename=filename, extraMetadata=purpose
                      and {"purpose": purpose})


#: The catalogue photograph every record carries, and the one row `_reference_photos` would resolve.
_MEDIA_IMAGE = _media("IMAGE", "saree-on-the-loom.jpg")


#: The five source records, filled in completely. Every column any ``data`` lambda reads is here
#: with a value that is recognisable in a rendered document — no empty strings, no zeroes — so an
#: assertion that the value reached the page cannot pass by coincidence.
def _source_rows() -> dict[str, _SourceRow]:
    return {
        "Artisan": _SourceRow(
            id="artisan-1",
            name="Bhikari Meher",
            localName="ଭିକାରୀ ମେହେର",
            gender="Male",
            phone="9876543210",
            email="bhikari.meher@example.in",
            place="Barpali",
            address="House 44, Weavers' Lane, Barpali",
            aadhaarNumber="234567890123",
            pehchanCardAvailable=True,
            pehchanCardNumber="APC7781234509",
            notes="Works to commission through the cooperative; prefers morning sittings.",
            dos="Address him as Guruji\nAllow time for the tying to be explained",
            donts="Do not photograph the loom shed without asking",
            recordedAt="2025-03-12T09:15:00+05:30",
            # ── THE TWO COLUMNS THAT REPLACED THE LEGACY METADATA BELOW ──────────────────────
            #
            # `Artisan.dateOfBirth` and `Artisan.experienceYears` exist now, and the participant
            # table reads them in preference to `extraMetadata`. A DATE rather than an age: the
            # workshop prints an age and the server derives it, so a stored number cannot go stale.
            #
            # Chosen so the derived age is unambiguous whenever this suite runs — a birthday in
            # January means the age is the same all year, where a September date would make this
            # fixture's assertion depend on the month the test happened to run in.
            dateOfBirth="1971-01-08T00:00:00+05:30",
            # THE JOINING DATE, WHICH OUTRANKS THE NUMBER BELOW IT (2026-08-23). Set here rather
            # than left None because `_SourceRow` raises on a column no fixture supplies precisely
            # so a widened lambda cannot carry a value nothing asserts on: with a date present, the
            # Experience column of the participant table is a DERIVED figure and this document is
            # what proves it prints. January again, and for the reason stated just above: a date
            # whose anniversary falls in January derives to the same number all year, where a
            # September one would make the assertion depend on the month the suite ran in.
            craftStartDate="1994-01-15T00:00:00+05:30",
            # Deliberately DISAGREEING with the date above (a 1994 start is 32 years by 2026, not
            # 31), on the same reasoning as the legacy keys below: matching values would let a reader
            # believe either source was being used.
            experienceYears=31,
            craft=_SourceRow(name="Sambalpuri Ikat"),
            location=_SourceRow(
                village="Barpali", district="Bargarh", state="Odisha", pincode="768029",
                address="Weavers' Lane, Barpali", subjectLatitude=21.1857, subjectLongitude=83.5876,
            ),
            # WHERE THE RECORD WAS MADE, which the roster prints beside WHEN it was made. The picker
            # this feeds is the one declared ALL_SCOPE, so this is routinely a different workshop from
            # the one being written up.
            workshop=_SourceRow(title="Barpali cluster documentation, 2025"),
            # WHAT ELSE IS ON FILE. Only one photograph can ever cross (`_reference_photos` resolves
            # a single IMAGE), so the audio row here is the material `recordMediaNote` exists to
            # mention — and mentioning is all it does: no id from this tuple may reach the entry.
            media=(
                _MEDIA_IMAGE,
                _media("AUDIO", "introduction.m4a"),
            ),
            # THE LEGACY KEYS STAY, and they deliberately DISAGREE with the columns above (27/54 vs
            # 31 and a 1971 birthday). Matching values would let a reader believe either source was
            # being used; disagreeing ones mean the assertions can only pass if the COLUMN won,
            # which is the precedence the readers promise. The metadata fallback itself is covered
            # in tests/test_reference_carry.py, on a row that has no columns.
            extraMetadata={"experienceYears": 27, "age": 54},
        ),
        "ProductDocumentation": _SourceRow(
            id="product-1",
            productName="Sambalpuri Ikat saree",
            localName="ସମ୍ବଲପୁରୀ ଶାଢ଼ୀ",
            productType="PACKAGING",
            craftName="Sambalpuri Ikat",
            place="Barpali",
            rawMaterialsUsed="Mulberry silk, natural indigo",
            mainToolsUsed="Pit loom, warping drum",
            sellingPrice="12500.00",
            costOfMaking="7400.00",
            marketDemand="HIGH",
            productFunctionUse="Worn at weddings and festivals",
            artisanName="Bhikari Meher",
            size="Six yards with a two-foot pallu",
            timeTakenToCompleteProduct="Eighteen days on the loom",
            remarks="Two colourways were documented; the indigo one is shown.",
            recordedAt="2025-03-12T10:40:00+05:30",
            # INCHES on the source. 18 in -> 45.72 cm, which is what the report must print.
            lengthInches="18.00",
            breadthInches="12.00",
            heightInches="2.00",
            # AND HOW THOSE THREE INCH FIGURES CAME TO BE KNOWN, in the shape
            # `records.merge_field_provenance` writes it: `{by, byName, at}` with `method` merged in
            # BESIDE it. Length and breadth were a vision model's reading of a photograph of the
            # saree on a grid sheet; the height was typed. This is what
            # `existingProduct.measurementMethodNote` prints, and it is the reason it exists: without
            # it the document states three centimetre figures under the NAME of whoever saved the
            # record, and a ministry officer reads a machine's estimate as a tape reading.
            extraMetadata={
                "fieldProvenance": {
                    "lengthInches": {"by": "usr_3", "byName": "R. Menon",
                                     "at": "2025-03-12T10:40:00+05:30",
                                     "method": "VISION_MODEL", "methodProvider": "gemini"},
                    "breadthInches": {"by": "usr_3", "byName": "R. Menon",
                                      "at": "2025-03-12T10:40:00+05:30",
                                      "method": "VISION_MODEL", "methodProvider": "gemini"},
                    "heightInches": {"by": "usr_3", "byName": "R. Menon",
                                     "at": "2025-03-12T10:40:00+05:30", "method": "TYPED"},
                },
            },
            # THE RECORD'S OWN STATED ADDRESS AND THE PIN ON THE PRODUCT'S PLACE. The stated strings
            # and the subject pin cross; the device's fix is not on this row at all, which is the
            # honest fixture for a rule whose whole content is that those columns never travel.
            location=_SourceRow(
                village="Barpali", district="Bargarh", state="Odisha", pincode="768029",
                subjectLatitude=21.1857, subjectLongitude=83.5876,
            ),
            media=(_MEDIA_IMAGE, _media("VIDEO", "cutting-down-the-saree.mp4")),
        ),
        "ToolDocumentation": _SourceRow(
            id="tool-1",
            toolkitName="Pit loom",
            localName="ଗାଡ଼ ତନ୍ତ",
            englishName="Throw-shuttle pit loom",
            craftName="Sambalpuri Ikat",
            place="Barpali",
            artisanName="Bhikari Meher",
            material="Sal wood and bamboo",
            processUsedIn="Weaving the tie-dyed warp and weft",
            replacementCost="18500.00",
            yearsInUse=22,
            maker="LOCAL_BLACKSMITH",
            traditionType="TRADITIONAL",
            suggestionsForToolImprovement="A wider reed would let the pallu be woven in one pass.",
            remarks="Rebuilt once after the 2019 flood.",
            recordedAt="2025-03-13T08:05:00+05:30",
            lengthInches="96.00",
            breadthInches="48.00",
            height="150.00",
            width="120.00",
            thickness="6.50",
            weight="88.00",
            radius="14.00",
            # THE METHOD FOR THE TWO COLUMNS THAT CAN CARRY ONE, AND FOR NO OTHERS. Both were
            # computed from marks a person placed on a photograph — deterministic, re-derivable, and
            # printed as "photo measurement" on every record surface, so the workshop prints the same
            # phrase. The five unit-less columns above (`height`, `width`, `thickness`, `weight`,
            # `radius`) get NO stamp and cannot: `measurement_provenance.DIMENSION_FIELDS` is
            # `{lengthInches, breadthInches, heightInches}` and `method_stamps` drops any marker
            # naming a column outside it. So this row is the honest state of a measured tool: two
            # dimensions that state both their unit and their method, five that state neither.
            extraMetadata={
                "fieldProvenance": {
                    "lengthInches": {"by": "usr_4", "byName": "S. Bal",
                                     "at": "2025-03-13T08:05:00+05:30",
                                     "method": "PHOTO_GEOMETRY", "methodTechnique": "SCALE"},
                    "breadthInches": {"by": "usr_4", "byName": "S. Bal",
                                      "at": "2025-03-13T08:05:00+05:30",
                                      "method": "PHOTO_GEOMETRY", "methodTechnique": "SCALE"},
                },
            },
            location=_SourceRow(
                village="Barpali", district="Bargarh", state="Odisha", pincode="768029",
                subjectLatitude=21.1857, subjectLongitude=83.5876,
            ),
            # THE ORDERED MAKING SEQUENCE, plus a grid frame. `ToolForm` renames every capture in the
            # "Process stages" card `STAGE_STEP_n_…` on both the online and the queued path, which is
            # what lets the carried sentence say the sequence is a sequence; the grid frame is a sheet
            # of ruled paper and must not be counted as footage of the tool.
            media=(
                _media("IMAGE", "STAGE_STEP_1_warping.jpg"),
                _media("IMAGE", "STAGE_STEP_2_treadles.jpg"),
                _media("AUDIO", "weaver-explains.m4a"),
                _media("IMAGE", "grid-length.jpg", purpose="MEASUREMENT_GRID"),
            ),
            artisanLinks=(
                _SourceRow(artisan=_SourceRow(name="Bhikari Meher")),
                _SourceRow(artisan=_SourceRow(name="Sanjukta Meher")),
            ),
        ),
        "Process": _SourceRow(
            id="process-1",
            name="Bandha tie-and-dye",
            notes="The warp is tied in bundles and dipped, darkest colour last.",
            preProcessAvailable=True,
            recordedAt="2025-03-14T11:00:00+05:30",
            product=_SourceRow(productName="Sambalpuri Ikat saree"),
            # The pre-process clips the record form makes mandatory once the box is ticked, and one
            # step's own captures — which is what `_process_media_note` counts on both sides.
            media=(_media("VIDEO", "pre-process.mp4"),),
            steps=(
                _SourceRow(sortOrder=1, name="Degumming the silk", stepType="STEP",
                           notes="Boiled with soda ash.", media=(_MEDIA_IMAGE,)),
                _SourceRow(sortOrder=2, name="Tying the bundles", stepType="STEP", notes="",
                           media=()),
                _SourceRow(sortOrder=3, name="Dyeing", stepType="GROUP",
                           notes="Lightest colour first.", media=()),
            ),
        ),
        "Craft": _SourceRow(
            id="craft-1",
            name="Sambalpuri Ikat",
            localName="ସମ୍ବଲପୁରୀ ବନ୍ଧା",
            # The other four things the crafts page collects, all of which now cross into boxes of
            # their own on the cover stage, plus where the craft was documented and what else is
            # attached to the record.
            category="Weaving",
            place="Barpali",
            description="Warp and weft are tied and dyed before the cloth is woven.",
            recordedAt="2024-11-05T10:00:00+05:30",
            workshop=_SourceRow(title="Barpali cluster documentation, 2024"),
            media=(_MEDIA_IMAGE, _media("PDF", "gazetteer-page.pdf")),
        ),
    }


#: The one media id every reference model's photograph resolves to, and the caption a researcher
#: typed against it in the repository — which is itself carried now, onto each gallery's
#: ``*Caption`` field, so the designer is not asked to retype a caption that already exists.
PHOTO_ID = "media-photo-1"
PHOTO_CAPTION = "The saree on the loom, three days before it was cut down."


def _reference_data() -> dict[str, dict[str, Any]]:
    """Each model's carried payload, produced by the REAL ``data`` lambda."""
    rows = _source_rows()
    photo = ReferencePhoto(id=PHOTO_ID, caption=PHOTO_CAPTION)
    return {
        model: REFERENCE_MODELS[model].data(rows[model], photo)
        for model in rows
    }


def _hydrated_rows() -> dict[str, dict[str, Any]]:
    """Each receiving entity's stage-entry data, as ``hydrate_entries`` would have written it.

    The blank-fill arm only, which is what a first save does: the designer picks a record and every
    mapped box that is empty is filled from it. A multi-valued target (``existingProduct``'s gallery
    is seeded from the product's one photograph) takes a list, exactly as hydration does.
    """
    produced = _reference_data()
    ref_field_model = {
        f"{e.key}.{f.key}": f.ref_model
        for e in _ENTITIES.values() for f in e.fields if f.type is FieldType.REF
    }
    out: dict[str, dict[str, Any]] = {}
    for path, mapping in REFERENCE_HYDRATION.items():
        entity_key, _, ref_field = path.partition(".")
        source = produced[ref_field_model[path]]
        row = out.setdefault(entity_key, {})
        # The id stays beside the copy — it is the join key, and the report resolves a photograph
        # through it. See ``REFERENCE_HYDRATION``'s note on why losing either half loses a
        # different half of the record.
        row[ref_field] = f"{ref_field_model[path]}-ref"
        for source_key, target_key in mapping.items():
            value = source.get(source_key)
            if value in (None, ""):
                continue
            spec = _spec(entity_key, target_key)
            row[target_key] = [value] if spec.type.is_multi else value
    return out


def _workshop_data() -> WorkshopData:
    """One workshop whose five carrying stages each hold one fully hydrated row."""
    rows = _hydrated_rows()
    data = WorkshopData(workshop_id="dw-1", title="Design & Prototype Workshop — Sambalpuri Ikat")
    for entity_key, row in rows.items():
        stage_key = _ENTITY_STAGE[entity_key]
        entity = _ENTITIES[entity_key]
        row = dict(row, _entryId=f"entry-{entity_key}")
        if entity.cardinality is Cardinality.SINGLETON:
            data.singletons.setdefault(stage_key, {}).update(row)
        else:
            data.collections.setdefault(stage_key, {}).setdefault(entity_key, []).append(row)
    # A required box the picker does not fill, so the rows are not rejected as unlabelled and the
    # sections render as they would in a real workshop.
    data.collections["PROTOTYPE_DEVELOPMENT"]["prototype"][0].update(
        prototypeCode="PT-01", name="Ikat table runner", materials=["Cotton"],
    )
    data.collections["TRADITIONAL_PROCESS_BASELINE"]["processStep"][0]["stepNumber"] = 1
    # Every referenced record's photograph, exactly as ``load_report_references`` supplies it.
    for model in ("Artisan", "ProductDocumentation", "ToolDocumentation", "Process", "Craft"):
        data.references[f"{model}-ref"] = ReferencedRecord(
            model=model, label=f"{model} record", photo=PHOTO_ID,
            place="Barpali", district="Bargarh", state="Odisha",
        )
    return data


def _resolver(media_id: str) -> ImageRef:
    return ImageRef(source=media_id, width_px=1200, height_px=900, mime_type="image/jpeg")


def _document_text(document: Any) -> str:
    """Everything the document SAYS, whatever kind of block it landed in."""
    parts: list[str] = []
    for block in document.blocks:
        if isinstance(block, ParagraphBlock):
            parts.append(runs_text(block.runs))
        elif isinstance(block, KeyValueBlock):
            parts.extend(f"{label}: {runs_text(value)}" for label, value in block.pairs)
        elif isinstance(block, TableBlock):
            parts.extend(c.header for c in block.columns)
            parts.extend(runs_text(cell) for row in block.rows for cell in row)
        elif isinstance(block, CoverBlock):
            parts.extend(f"{label}: {value}" for label, value in block.info_rows)
            parts.append(block.title)
        elif isinstance(block, MetricRowBlock):
            parts.extend(f"{label} {value} {unit}" for label, value, unit in block.metrics)
        elif isinstance(block, ImageBlock):
            # THE CAPTION IS TEXT AND HAS TO BE COLLECTED HERE. A carried ``*Caption`` field is
            # withheld from ``_printable`` on purpose ("captions are placed with their image, never
            # on their own") and reaches the page only on the picture, so a harness that read only
            # the prose blocks would report a carried caption as missing when it is printed.
            parts.append(block.caption)
        elif isinstance(block, ImageGridBlock):
            parts.extend(caption for _image, caption in block.images)
        else:
            # BULLETS, and anything else that carries a list of runs. ``_render_narrative`` splits a
            # BULLETS value on newlines and semicolons into one item per line, so the stored value
            # is on the page in pieces and never as the string that was stored. The pieces are what
            # a reader sees, so the pieces are what this collects — see ``_reaches`` for the
            # matching half of the same rule.
            items = getattr(block, "items", None)
            if items:
                parts.extend(runs_text(item) if not isinstance(item, str) else item
                             for item in items)
    return "\n".join(parts)


def _reaches(spec: FieldSpec, expected: str, printed: str) -> bool:
    """Whether ``expected`` is on the page, allowing for the ONE role that breaks a value up.

    A BULLETS field is a list: ``_render_narrative`` splits the stored text on newlines (and, for
    the fields written before the rich-text promotion, on semicolons) and emits one item per line,
    so the stored string is never on the page as a string. Every line of it is, which is what a
    reader sees and therefore what "reached the report" means for this role.

    Deliberately not a blanket substring-per-line rule for every role. For a KEY_VALUE or a table
    cell the whole value must be there in one piece — "reached the report" cannot mean "most of the
    words appear somewhere in a sixty-page document", or a truncation defect would pass.
    """
    if spec.report_role is not ReportRole.BULLETS:
        return expected in printed
    lines = [line.strip() for part in expected.split("\n")
             for line in part.split(";") if line.strip()]
    return bool(lines) and all(line in printed for line in lines)


def _image_sources(document: Any) -> set[str]:
    found: set[str] = set()
    for block in document.blocks:
        if isinstance(block, ImageBlock):
            found.add(block.image.source)
        elif isinstance(block, ImageGridBlock):
            found.update(image.source for image, _caption in block.images)
    return found


def _render(template_id: str) -> tuple[Any, list[str]]:
    return build_report(
        _workshop_data(), template_id, _resolver,
        meta=ReportMeta(title="Sambalpuri Ikat", generated_at="2026-08-16T00:00:00Z"),
    )


#: The three templates that print every stage at every tier. "Reaches the workshop implies reaches
#: the report" is a statement about these, and the requirement's *"not less than that"* is
#: checkable against them without qualification.
FULL_TEMPLATES = ("DCH_STANDARD", "DIC_STANDARD", "DETAILED_TECHNICAL")


@pytest.mark.parametrize("template_id", FULL_TEMPLATES)
def test_every_carried_field_reaches_the_document(template_id):
    """THE TEST THE REQUIREMENT ASKS FOR, and the one that makes "without a miss" checkable.

    A fully populated artisan, product, process, tool and craft go in through the real tables; the
    document comes out; every value the tables carried is asserted to be in it, formatted the way
    the report formats it. Not "the key exists on the row" — the string a reader would find.
    """
    document, _warnings = _render(template_id)
    printed = _document_text(document)
    missing: list[str] = []
    for entity_key, row in _hydrated_rows().items():
        for target, value in row.items():
            spec = _ENTITIES[entity_key].field(target)
            if spec is None or spec.type is FieldType.REF or spec.type.is_media:
                continue   # ids never print as text; photographs are asserted separately
            expected = format_value(spec, value)
            if expected and not _reaches(spec, expected, printed):
                missing.append(f"{entity_key}.{target} = {expected!r}")
    assert not missing, (
        f"{template_id} printed no trace of {len(missing)} carried value(s): "
        + "; ".join(missing)
        + f"\n--- what the document actually said ---\n{printed}"
    )


@pytest.mark.parametrize("template_id", FULL_TEMPLATES)
def test_a_carried_photograph_is_placed(template_id):
    """The picture the picker copied over, and the picture it deliberately did not.

    Two routes reach one assertion: ``participant.photo`` and ``tool.photo`` are seeded onto the
    row by hydration, while ``prototype.productRef`` and ``existingProduct.artisanRef`` copy only a
    name, so for those the referenced record's photograph is placed by ``_images``' second pass
    instead. Both end with the file in the document, which is what a reader cares about.

    THIS USED TO SAY "their entities own galleries of the designer's OWN photographs that a seeded
    picture must never overwrite", WHICH IS NOT THE RULE — the same wrong reason was corrected in
    ``report_builder.ReferencedRecord``, in ``design_workshops.load_report_references`` and in
    ``test_report_figures.test_a_ref_to_a_documented_product_pulls_the_catalogue_photograph``, and
    survived here. ``hydrate_entries`` seeds a gallery WHEN EMPTY and never overwrites one, and
    ``existingProduct.productRef`` maps ``photo`` -> ``productPhotos`` on purpose, because the
    documented product's own photograph IS a photograph of the documented product. Only
    ``prototype``'s gallery is left unseeded, for the reason written at ``prototype.productRef``:
    a prototype is defined by how it DIFFERS from the product it was based on.
    """
    document, _warnings = _render(template_id)
    assert PHOTO_ID in _image_sources(document)


def test_a_carried_measurement_prints_its_unit_in_the_document():
    """The inches-to-centimetres conversion has to be VISIBLE, not merely correct.

    A converted number printed bare is indistinguishable from an unconverted one, and the reader of
    a government report has no way to ask. This drives a measured product row through the same
    renderer and asserts the centimetre mark is on the page beside the number.
    """
    data = _workshop_data()
    # 18 inches, converted. The value the backend lane's conversion produces; what is asserted here
    # is only that whatever it produces prints as centimetres.
    data.collections["EXISTING_PRODUCTS_BASELINE"]["existingProduct"][0]["lengthCm"] = 45.72
    builder = ReportBuilder(data, template("DETAILED_TECHNICAL"), _resolver,
                            meta=ReportMeta(title="x", generated_at="2026-08-16T00:00:00Z"))
    printed = _document_text(builder.build())
    assert "45.72 cm" in printed, printed


def test_a_carried_enum_prints_its_label_and_never_its_token():
    """``PACKAGING`` on the wire, "Packaging" on the page."""
    document, _warnings = _render("DETAILED_TECHNICAL")
    printed = _document_text(document)
    assert "Packaging" in printed
    assert "PACKAGING" not in printed


def test_a_carried_price_prints_as_money():
    """``"12500.00"`` is stored as a string and must not print as one."""
    document, _warnings = _render("DETAILED_TECHNICAL")
    assert "₹ 12,500.00" in _document_text(document)


def test_the_process_notes_reach_the_report_as_the_step_description():
    """The carry that turns a one-word row into a paragraph, asserted at the far end.

    ``processStep.processRef`` copies ``Process.notes`` onto ``description`` and
    ``Process.product.productName`` onto ``documentedFor``. The first is the substantive narrative
    of the traditional-process stage; the second is the only thing in the printed document that
    tells "Tie and dye" at Bagru from "Tie and dye" at Bhuj.
    """
    printed = _document_text(_render("DCH_STANDARD")[0])
    assert "darkest colour last" in printed
    assert "Sambalpuri Ikat saree" in printed


# --------------------------------------------------------------------------------------
# 3. Per template: where "carried implies printed" does NOT hold, and what is said about it
# --------------------------------------------------------------------------------------


def test_compact_summary_is_the_one_template_that_drops_a_carried_field_by_tier():
    """The sixth template, and the exception the survey's claim has.

    COMPACT_SUMMARY is the only ``max_tier`` in ``TEMPLATES`` that is not ADVANCED, and almost
    every carried field is Standard — ``FieldSpec.tier`` DEFAULTS to Standard, so a new receiving
    field declared without a tier is invisible in this template the day it is added. That is a
    legitimate editorial choice (the template says "Basic-tier fields only" in its own
    description); what was not legitimate was that it happened in silence.
    """
    assert [t.id for t in TEMPLATES if t.max_tier.rank < 2] == ["COMPACT_SUMMARY"]
    _document, warnings = _render("COMPACT_SUMMARY")
    tier_warnings = [w for w in warnings if "capture tier" in w]
    assert tier_warnings, (
        "COMPACT_SUMMARY dropped Standard-tier fields the designer filled in and said nothing"
    )
    assert "Generate the report with a template that captures every tier" in tier_warnings[0]


@pytest.mark.parametrize("template_id", FULL_TEMPLATES)
def test_a_template_that_admits_every_tier_raises_no_tier_warning(template_id):
    """The other half, and the one that keeps the warning worth reading.

    A warning that fires on every report is a warning nobody reads, including on the report where
    it mattered. Five of the six templates admit every tier, so five of six must stay silent.
    """
    _document, warnings = _render(template_id)
    assert not [w for w in warnings if "capture tier" in w]


def test_the_cover_says_so_when_a_filled_cover_field_fits_nowhere():
    """The ten-row cover cap, and the two templates where it costs the document a fact.

    Stage 1 declares twenty-one COVER_FIELD boxes; the cover table holds ten. Four of the six
    templates also print the WORKSHOP_SETUP stage section, where ``_render_narrative`` prints
    COVER_FIELD as a key-value pair, so the overflow lands a page later. IMPLEMENTING_AGENCY and
    PHOTO_CATALOGUE carry a cover and no stage 1 — so on a fully documented workshop the start and
    end dates, the duration, the designer's institution, the sanction order and its date and the
    workshop code are in the record and in no part of the file.

    The document is not changed here and the reason is in ``_render_cover``: where those rows
    should go is the template's decision, ``TEMPLATES`` is pinned by value against the Kotlin port
    in a fixture that can only be regenerated inside the API container, and the handset caps the
    same list with the same ``take(10)``. What was missing was the sentence.
    """
    data = _workshop_data()
    setup = data.singletons.setdefault("WORKSHOP_SETUP", {})
    setup.update(
        workshopTitle="Design & Prototype Development Workshop",
        schemeName="National Handicrafts Development Programme",
        clusterName="Barpali", state="Odisha", district="Bargarh",
        block="Barpali", village="Barpali", venue="Weavers' Service Centre",
        startDate="2026-02-10", endDate="2026-02-24", durationDays=15,
        designerName="A. Kumar", designerInstitution="NID Ahmedabad",
        implementingAgency="Sambalpuri Bastralaya", sponsor="DC (Handicrafts)",
        sanctionOrderNo="DCH/2026/114", sanctionOrderDate="2026-01-06",
        workshopCode="DW-2026-114",
    )

    def render(template_id: str) -> tuple[Any, list[str]]:
        return build_report(data, template_id, _resolver,
                            meta=ReportMeta(title="Sambalpuri Ikat",
                                            generated_at="2026-08-16T00:00:00Z"))

    document, warnings = render("IMPLEMENTING_AGENCY")
    covers = [b for b in document.blocks if isinstance(b, CoverBlock)]
    assert covers and len(covers[0].info_rows) == COVER_INFO_ROWS
    dropped = [w for w in warnings if "cover field" in w]
    assert dropped, "ten cover rows printed, eleven more filled in, and nothing said"
    assert "Start date" in dropped[0] or "did not fit" in dropped[0]

    # …and the four templates that print stage 1 stay silent, because nothing was lost there.
    for template_id in (*FULL_TEMPLATES, "COMPACT_SUMMARY"):
        _doc, quiet = render(template_id)
        assert not [w for w in quiet if "cover field" in w], template_id


def test_the_three_narrow_templates_drop_carried_stages_by_section_and_not_by_accident():
    """The other three templates print fewer stages, which is a decision and not a defect.

    Stated as a test so the shape of the answer is on the record: IMPLEMENTING_AGENCY reduces the
    baseline to annexures and prints eight stages, PHOTO_CATALOGUE is a buyer-facing catalogue and
    prints three, COMPACT_SUMMARY is a review-meeting handout and prints seven. A carried field
    whose stage is not in the list is absent from that file by the designer's own choice of
    template — visible in the picker, described in the template's description — which is why it
    raises no warning while the tier cap and the cover cap do.
    """
    printed = {t.id: {s.stage_key for s in t.sections if s.stage_key} for t in TEMPLATES}
    for template_id in FULL_TEMPLATES:
        assert {_ENTITY_STAGE[k] for k in CARRIED} <= printed[template_id]
    assert "WORKSHOP_SETUP" not in printed["IMPLEMENTING_AGENCY"]
    assert "WORKSHOP_SETUP" not in printed["PHOTO_CATALOGUE"]
    # The one carrying stage the narrow templates DO keep, and the reason it matters below.
    assert "WORKSHOP_PLAN_PARTICIPANTS_OPENING" in printed["PHOTO_CATALOGUE"]


def test_the_buyer_facing_catalogue_prints_the_roster_at_every_tier():
    """A standing warning about WHERE a widened participant carry ends up.

    PHOTO_CATALOGUE prints three of the twenty-two stages and one of them is the participant
    roster, under the heading "The makers" — at ``max_tier=ADVANCED``, so every field on the row
    prints, including the ones the table cannot hold, which ``_render_table`` writes out under each
    row as key-value pairs. ``participant.phone`` is carried from ``Artisan.phone`` and is in a
    document whose own template comment says it goes to a buyer.

    This is not asserting the current behaviour is wrong; it is making sure the next person to wire
    a government ID or an address onto this entity finds out here that the buyer's catalogue is one
    of the places it will be printed.
    """
    catalogue = template("PHOTO_CATALOGUE")
    assert catalogue.max_tier.rank == 2
    document, _warnings = _render("PHOTO_CATALOGUE")
    printed = _document_text(document)
    # Everything the widened artisan carry now puts in front of a buyer, named one by one so the
    # list is a decision somebody made rather than a consequence nobody looked at. Reported.
    assert "9876543210" in printed                    # phone
    assert "bhikari.meher@example.in" in printed      # email
    assert "House 44, Weavers' Lane, Barpali" in printed
    assert "21.18570, 83.58760" in printed            # the artisan's HOME COORDINATES
    assert "Address him as Guruji" in printed         # the researcher's handling guidance
    assert "prefers morning sittings" in printed      # …and their private note on the record
    # The one identity number in the set is masked before it is ever carried
    # (``records.mask_identity_number``), which is why it is the only one that reads safely here.
    assert "APC7781234509" not in printed


# --------------------------------------------------------------------------------------
# 4. The annexures, which carry their content instead of hydrating it onto a row
# --------------------------------------------------------------------------------------


def test_the_media_annexure_carries_the_picture_and_the_words_under_it():
    """The photographic annexure is walked off the REGISTRY, not off the template's section list.

    ``_render_media_annexure`` loops every stage and every entity of ``stages()`` and asks
    ``_images`` for each row, so a photograph seeded by hydration onto a NEW gallery field reaches
    it the day the field is declared, with no annexure change. Its caption reaches it too, which
    is the half that was worth carrying: ``ReferencePhoto`` now brings the researcher's typed
    caption across with the media id, so the annexure prints the sentence somebody wrote when the
    photograph was taken instead of an empty line under the picture.
    """
    document, _warnings = _render("DETAILED_TECHNICAL")
    grids = [b for b in document.blocks if isinstance(b, ImageGridBlock)]
    assert grids, "DETAILED_TECHNICAL declares ANNEXURE_MEDIA and it drew nothing"
    captions = {caption for grid in grids for _image, caption in grid.images}
    assert PHOTO_ID in {img.source for grid in grids for img, _c in grid.images}
    assert PHOTO_CAPTION in captions, sorted(captions)


def test_every_template_carries_the_questionnaire_and_transcript_annexures():
    """Both annexures are a TOGGLE and not a template choice, and both are tier-blind.

    Which office asked for the report does not change whether the survey's own answers and the
    artisans' recorded words belong in it, so all six templates declare both sections — including
    COMPACT_SUMMARY, whose Basic-tier cap reaches ``_visible`` and therefore the registry fields,
    and reaches neither annexure: ``append_questionnaire_annexure`` and
    ``append_transcript_annexure`` take no tier at all. That asymmetry is correct (an annexure of
    evidence is not a capture tier) and is worth pinning, because it is the one place in this
    pipeline where a Basic template prints a Standard-tier fact.
    """
    for report_template in TEMPLATES:
        specials = {s.special for s in report_template.sections}
        assert SpecialSection.ANNEXURE_QUESTIONNAIRES in specials, report_template.id
        assert SpecialSection.ANNEXURE_TRANSCRIPTS in specials, report_template.id


def test_an_audio_clip_carried_onto_a_stage_field_reaches_the_transcript_annexure():
    """The path a future TRANSCRIPT carry would travel, asserted before anything travels it.

    Transcripts are not hydrated and cannot be: ``workshop_transcripts.audio_references`` walks the
    registry's AUDIO fields over the workshop's own stage entries, and the annexure prints whatever
    that finds. So a portal recording reaches the report by ONE route — a media id landing in an
    AUDIO field of a stage entry — and that route needs no report change whatsoever. This drives it
    end to end so the backend lane can wire a source recording onto, say,
    ``traditionalProcess.artisanAudio`` and know the annexure will print it.
    """
    data = _workshop_data()
    attach_transcripts(data, [TranscriptItem(
        media_id="media-audio-1",
        stage_key="TRADITIONAL_PROCESS_BASELINE", stage_number=5,
        stage_title="Traditional Process, Tools & Raw Materials",
        entity_key="traditionalProcess", field_key="artisanAudio",
        field_label="Artisan’s spoken explanation",
        text="**Speaker 1:** The tying takes longer than the weaving.",
    )])
    builder = ReportBuilder(data, template("COMPACT_SUMMARY"), _resolver,
                            meta=ReportMeta(title="x", generated_at="2026-08-16T00:00:00Z"))
    printed = _document_text(builder.build())
    assert "The tying takes longer than the weaving." in printed, (
        "the transcript annexure is the one section a Basic-tier template still prints in full"
    )
