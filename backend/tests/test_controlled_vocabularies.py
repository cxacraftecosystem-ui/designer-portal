"""The controlled lists behind stage 5's tools, stage 7's survey places and stage 14's problems.

Three fields in the registry asked a categorical question through a free TEXT box, and the answers
in this repository's own database show exactly what that costs: the eleven stage-5 rows that filled
in ``tool.toolType`` spell six different things, of which "Loom", "Loom accessory", "Hand frame" and
"Hand-turned warping frame" are four names for two categories, so no query can count looms across
two clusters. That is the failure rule 2 of :mod:`app.services.stage_schema` exists to prevent.

WHAT THESE TESTS PIN DOWN IS THE SHAPE OF THE FIX, not merely its presence. Each of the three
fields gained a NEW enum field BESIDE the text one rather than being converted, and the reason is
in ``test_an_existing_free_text_answer_survives_the_new_enum_beside_it``: every value already
stored in those three boxes is a sentence, not a token, and a conversion would have shown the
designer an empty dropdown where their sentence used to be — the stored value being invisible on
screen is how it gets overwritten with nothing.
"""

import json
import os
import pathlib

import pytest

# Importing this module is what installs the twenty-two stages into the registry.
import app.services.stage_definitions  # noqa: F401
from app.services.report_builder import format_value
from app.services.stage_schema import (
    ENUMS,
    REF_SCOPE_ALL,
    REF_SCOPE_WORKSHOP,
    FieldType,
    coerce_value,
    registry_to_dict,
    registry_version,
    stage_by_number,
    validate_entry,
    validate_registry,
)

#: The bundled tier-3 registry a handset runs off before it has ever reached the network.
ANDROID_ASSET = (
    pathlib.Path(__file__).resolve().parents[2]
    / "android" / "app" / "src" / "main" / "assets" / "design-workshop-schema.json"
)


def _entity(stage_number: int, entity_key: str):
    entity = stage_by_number(stage_number).entity(entity_key)
    assert entity is not None, f"stage {stage_number} has no {entity_key!r} entity"
    return entity


# --------------------------------------------------------------------------------------
# The three vocabularies
# --------------------------------------------------------------------------------------


def test_the_registry_is_still_sound():
    """Unique keys, canonical enums, resolvable refs, Basic-tier-only-required — after the edit."""
    problems = validate_registry()
    assert problems == [], "\n".join(problems)


def test_a_tool_has_a_controlled_family_beside_its_free_text_type():
    tool = _entity(5, "tool")

    family = tool.field("toolFamily")
    assert family is not None, "stage 5's tool entity has no controlled tool list"
    assert family.type is FieldType.ENUM
    assert family.enum == "TOOL_TYPE"

    # The free-text box is NOT replaced. It is what carries "Vessel over a firewood hearth" —
    # a real recorded answer no eight-member list can hold — and its key is permanent.
    text = tool.field("toolType")
    assert text is not None and text.type is FieldType.TEXT
    assert not text.deprecated


def test_the_tool_families_are_the_ones_this_repository_actually_documents():
    """Every member traces to a recorded answer or to the field's own help text.

    The list was built from ``select data->>'toolType' … from "DwStageEntry" where
    entityKey='tool'`` and from the ``ToolDocumentation`` table, not from a taxonomy of world
    crafts. Five members generalise the six spellings a designer actually typed: HAND_TOOL from
    "Hand tool", LOOM from "Loom", LOOM_ACCESSORY from "Loom accessory", FRAME from BOTH "Hand
    frame" and "Hand-turned warping frame" — the collapse the whole list exists to make — and
    VESSEL from "Vessel over a firewood hearth". ``ToolDocumentation`` corroborates LOOM alone,
    every row of it being a pit loom.

    KILN and MOULD are the two categories ``toolType``'s own help text has been asking for without
    ever offering: before this change it read, in full, "Hand tool, loom, kiln, mould, and so on."
    OTHER is the escape hatch that keeps the free-text field beside it honest rather than forcing a
    wrong answer, and nothing is guessed at for a craft this database has never documented.
    """
    assert set(ENUMS["TOOL_TYPE"]) == {
        "HAND_TOOL", "LOOM", "LOOM_ACCESSORY", "FRAME", "VESSEL", "KILN", "MOULD", "OTHER",
    }


def test_a_survey_place_is_typed_from_the_vocabulary_the_registry_already_has():
    """Rule 2 is about ONE list, so a survey place's kind is a MARKET_CHANNEL like every other.

    A parallel PLACE_TYPE holding EMPORIUM / RETAILER / WHOLESALER / EXPORTER would fork the token
    space that ``existingProduct.marketChannel`` and ``buyerLink.buyerType`` already share — and
    then "every emporium we surveyed" could not be joined to "every product sold through an
    emporium", which is the entire reason the enum is shared rather than per-stage.
    """
    place = _entity(7, "surveyPlace")

    channel = place.field("placeChannel")
    assert channel is not None, "surveyPlace has no controlled place vocabulary"
    assert channel.type is FieldType.ENUM
    assert channel.enum == "MARKET_CHANNEL"

    text = place.field("placeType")
    assert text is not None and text.type is FieldType.TEXT
    assert not text.deprecated


def test_an_iteration_names_every_problem_area_it_touched_not_just_one():
    """MULTI_ENUM, and the data is why: a third of the recorded answers name two areas.

    "Design and material — motif density and weft composition" and "Material and process —
    padding construction" are two of the six ``problemType`` values this database holds. A single
    select would have made a designer drop half of each, which is a worse record than the free
    text it replaced.
    """
    iteration = _entity(14, "prototypeIteration")

    areas = iteration.field("problemAreas")
    assert areas is not None, "stage 14 has no controlled problem vocabulary"
    assert areas.type is FieldType.MULTI_ENUM
    assert areas.enum == "PROBLEM_TYPE"
    # Closed on purpose: the source document names exactly these three and every recorded answer
    # is a combination of them, so an OTHER member would only invite the free text back in.
    assert set(ENUMS["PROBLEM_TYPE"]) == {"MATERIAL", "PROCESS", "DESIGN"}

    text = iteration.field("problemType")
    assert text is not None and text.type is FieldType.TEXT
    assert not text.deprecated


# --------------------------------------------------------------------------------------
# The migration question
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("stage_number", "entity_key", "text_key", "enum_key", "recorded"),
    [
        # Every one of these strings is a value read out of the live database.
        (5, "tool", "toolType", "toolFamily", "Vessel over a firewood hearth"),
        (5, "tool", "toolType", "toolFamily", "Hand-turned warping frame"),
        (7, "surveyPlace", "placeType", "placeChannel",
         "State handloom emporium and adjoining retail row"),
        (14, "prototypeIteration", "problemType", "problemAreas",
         "Design and material — motif density and weft composition"),
    ],
)
def test_an_existing_free_text_answer_survives_the_new_enum_beside_it(
    stage_number, entity_key, text_key, enum_key, recorded
):
    """THE TEST THAT DECIDED THE SHAPE OF THIS CHANGE.

    A row written last season holds a sentence in the text box. After the edit it must still save
    without an error, still come back byte-identical, and still print — while the same sentence
    offered to the new enum field is refused, which is precisely what would have happened to the
    stored value had the field been converted in place instead of joined.
    """
    entity = _entity(stage_number, entity_key)
    text_spec = entity.field(text_key)
    enum_spec = entity.field(enum_key)

    cleaned, errors = validate_entry(entity, {text_key: recorded}, enforce_required=False)
    assert errors.get(text_key) is None, f"a stored answer was rejected: {errors}"
    assert cleaned[text_key] == recorded
    assert format_value(text_spec, recorded) == recorded

    # And the reason the text field was kept: the enum cannot hold this answer at all.
    value, error = coerce_value(enum_spec, recorded)
    assert value is None and error, "the sentence would have been accepted as a token"


def test_a_token_the_registry_has_never_seen_still_prints_rather_than_vanishing():
    """``enum_label`` falls back to the token, so a phone one release ahead is readable."""
    iteration = _entity(14, "prototypeIteration")
    areas = iteration.field("problemAreas")
    assert format_value(areas, ["MATERIAL", "COSTING"]) == "Material, COSTING"


# --------------------------------------------------------------------------------------
# How wide the two stage-5 pickers cast their net
# --------------------------------------------------------------------------------------


def test_the_tool_picker_reaches_the_whole_documented_catalogue():
    """A tool is a TYPE, not an instance, so the knowledge is worth reusing across clusters.

    Every ``ToolDocumentation`` row in this database belongs to a DIFFERENT Workshop — the row
    count and the distinct-workshopId count have been equal at every check — so a WORKSHOP-scoped
    picker offered a designer exactly one record, and a dropdown with one entry is a dropdown they
    type around, which is the behaviour the picker exists to end. Confirmed against the running
    stack rather than argued from the table: the same picker on the same workshop answers
    ``scopedToWorkshop: true`` with ONE option at WORKSHOP and the whole catalogue at ALL.

    Deliberately no counts in this docstring. The table is seeded continuously — it gained a row
    between two queries run minutes apart while this was being checked — so any figure written
    here is wrong by next week, and an earlier draft of this paragraph had already gone stale. It
    is the one-row-per-workshop SHAPE that carries the argument, and it holds for very nearly
    every design workshop that names a Workshop at all.
    """
    tool_ref = _entity(5, "tool").field("toolRef")
    assert tool_ref.ref_scope == REF_SCOPE_ALL


def test_the_process_picker_stays_inside_the_workshop():
    """Deliberately NOT widened, and this test is here so a later edit has to argue with it.

    A ``Process`` row hangs off a product at one cluster, and the picker's sublabel shows only
    that product's name — no place, no cluster, no artisan. Widening it would put a different
    cluster's dyeing sequence one tap away with nothing on screen to tell the two apart, and the
    stored ref is a join key research follows. A tool is a shared type; a process is not.
    """
    process_ref = _entity(5, "processStep").field("processRef")
    assert process_ref.ref_scope == REF_SCOPE_WORKSHOP


# --------------------------------------------------------------------------------------
# The signal every client refetches on
# --------------------------------------------------------------------------------------


def test_the_bundled_android_asset_matches_the_registry_it_was_dumped_from():
    """Tier 3 of the phone's cache must not disagree with the server about what a stage contains.

    This asset is what a handset handed to a field worker renders its forms from until its first
    successful GET. A stale one is mostly harmless — the first fetch supersedes it — but the
    version string is the signal every client uses to decide whether to refetch at all, so an
    asset whose version matches a registry it no longer equals is a phone that never asks again.

    THIS CHECK IS THE REFETCH SIGNAL AND NOT THE STALENESS CHECK, and reading it as the latter is
    what let three derived fields go missing under a digest that matched character for character.
    See :func:`test_the_bundled_android_asset_is_the_registry_it_claims_to_be` immediately below,
    which is the one that compares the CONTENT.
    """
    assert ANDROID_ASSET.exists(), f"the bundled registry is missing: {ANDROID_ASSET}"
    bundled = json.loads(ANDROID_ASSET.read_text(encoding="utf-8"))
    assert bundled["version"] == registry_version()


def test_the_bundled_android_asset_is_the_registry_it_claims_to_be():
    """The asset is a DUMP of ``registry_to_dict()``, so the only honest check is equality.

    WHY THE VERSION CHECK ABOVE IS NOT ENOUGH, MEASURED RATHER THAN ARGUED. ``registry_version()``
    digests key, type, tier, required, enum NAME, deprecated, derivation and hydration — and
    nothing else, deliberately, because retitling a field must not invalidate every cached draft
    on every phone. ``field_to_dict``/``entity_to_dict``/``stage_to_dict`` serialise a great deal
    more than that. Mutating the live registry one attribute at a time and re-dumping it (run on
    2026-08-08) shows eleven separate changes that move the ASSET and leave the VERSION identical:

        an enum gains a token · a field is relabelled · a field gains help text
        a field gains a maxLength · a field changes reportRole · an entity changes cardinality
        an entity changes parent · an entity changes labelField · a REF picker changes refScope
        a REF picker changes refFilterBy · a stage changes title or becomes optional

    Every one of those is something a handset with no signal renders from. The enum case is this
    module's own subject: a token added to ``TOOL_TYPE`` never reaches a phone that has not yet
    fetched, and the designer standing in the cluster picks from last release's list. The
    cardinality and ``parent`` cases are worse than stale — ``parent`` is what
    ``dwParentGroups`` reads to print a report's sub-headings, and ``refScope`` is what decides
    whether stage 6's picker offers this workshop's eleven artisans or every artisan in the
    country. None of them would have failed the version check.

    THE HISTORY IS EXACTLY THIS SHAPE, ONE ROUND EARLIER. The asset once carried two derived fields
    where the registry had five and its version matched CHARACTER FOR CHARACTER, because the digest
    did not cover derivations; the fix was to widen the digest. Widening it again for labels and
    help text is NOT the fix here — that would re-invalidate every draft on every phone for a
    typo correction, which is precisely what ``registry_version`` refuses to do. The asset is a
    dump, so the asset is compared to the dump.

    Regenerate with the command in ``android/.../data/StageSchema.kt`` when this fails.
    """
    assert ANDROID_ASSET.exists(), f"the bundled registry is missing: {ANDROID_ASSET}"
    bundled = json.loads(ANDROID_ASSET.read_text(encoding="utf-8"))
    live = registry_to_dict()

    # Reported per stage rather than as one 119 KB diff: pytest's assertion rewriting on two
    # dicts this size prints something nobody reads, and "stage 5 differs" is what tells you
    # which edit was not dumped.
    assert sorted(bundled) == sorted(live), "the asset's top-level shape is not the registry's"
    assert bundled["enums"] == live["enums"], (
        "the bundled enums differ from the registry's — a controlled list a phone with no signal "
        "offers is not the list the server validates against"
    )
    assert [s["key"] for s in bundled["stages"]] == [s["key"] for s in live["stages"]], (
        "the asset and the registry do not even carry the same stages, in the same order"
    )
    stale = [
        stage["key"]
        for stage, shipped in zip(live["stages"], bundled["stages"])
        if stage != shipped
    ]
    assert stale == [], f"the bundled asset is stale for these stages: {stale}"
    assert bundled == live


def test_a_new_tool_token_moves_the_asset_and_not_the_version():
    """The guard's own self-check: prove the version string CANNOT see this file's own subject.

    A test that only ever passes proves nothing about what it would catch, and the whole reason
    the content comparison above exists is a claim about what the version check misses. So the
    claim is exercised rather than asserted in a comment: ``TOOL_TYPE`` gains a token — the exact
    edit this module is about, the one that decides what a designer in a cluster can pick from —
    and the digest does not move by a character while the dumped registry does.

    Read the two assertions in the right order. The first is NOT a defect: ``registry_version`` is
    deliberately insensitive to anything that is not a key, type, tier, required flag, enum NAME,
    derivation or hydration, because a version that moved for a token would re-run the draft
    migration on every phone in the field for a vocabulary edit. The defect would be believing
    that check answers "is the bundled asset current", which it cannot, which is the second
    assertion's job.

    The registry is a process-global, so the token is removed in a ``finally``; leaving it behind
    would fail ``test_the_bundled_android_asset_is_the_registry_it_claims_to_be`` in whichever
    order pytest happened to run the two.
    """
    probe = "ZZ_PROBE_NOT_A_REAL_TOOL"
    assert probe not in ENUMS["TOOL_TYPE"]

    before_version = registry_version()
    before_dump = registry_to_dict()
    ENUMS["TOOL_TYPE"][probe] = "Probe token"
    try:
        assert registry_version() == before_version, (
            "the digest moved for a vocabulary edit — if this is deliberate, the note in "
            "registry_version about not invalidating every cached draft needs rewriting"
        )
        assert registry_to_dict() != before_dump, (
            "an enum gained a token and the dumped registry did not change, which would mean the "
            "asset never carries the vocabularies at all"
        )
    finally:
        ENUMS["TOOL_TYPE"].pop(probe, None)

    assert registry_version() == before_version
    assert registry_to_dict() == before_dump


# --------------------------------------------------------------------------------------
# The same migration question, through the real save and the real renderer
# --------------------------------------------------------------------------------------
#
# WHY THIS SECTION EXISTS WHEN ``validate_entry`` IS ALREADY ASSERTED ABOVE. That unit test
# proves the validator accepts last season's sentence; it cannot prove the sentence SURVIVES —
# ``save_stage`` serialises the cleaned row into a JSON column and the report builds its own
# document from what comes back out, and a value can be accepted at the door and still be lost
# on either of those two legs. The claim being made about this change is that a designer who
# opens a stage written before it loses nothing, and the only place that claim is true or false
# is the round trip.
#
# Skipped rather than failed without a local database, following ``test_stage_sync``: the rest
# of this file is pure and runs in a fifth of a second on a laptop in a village, and making the
# whole module need Postgres to say anything at all would be the wrong trade.

def _local_database_url() -> str:
    """The DSN the app itself would connect to, not merely what is exported in this shell.

    ``os.environ["DATABASE_URL"]`` ALONE IS NOT ENOUGH and reading it that way is how this guard
    first shipped silently disabled: ``backend/.env`` is loaded by :mod:`app.core.config`, so a
    module that computes its skip condition before anything has imported the settings sees an
    empty string, marks itself "no local database", and reports two green skips on a machine with
    Postgres running — a test that never runs, dressed as a test that passed. ``test_stage_sync``
    is unaffected only because it happens to import ``app.core.db`` on the lines above its own
    check. Ask the settings object directly and the ordering stops mattering.
    """
    try:
        from app.core.config import get_settings

        return str(get_settings().database_url or "")
    except Exception:      # noqa: BLE001 - no settings at all is simply "not local"
        return os.environ.get("DATABASE_URL", "")


_LOCAL_DB = any(h in _local_database_url() for h in ("localhost", "127.0.0.1"))

needs_db = pytest.mark.skipif(
    not _LOCAL_DB, reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL"
)

#: Read out of ``DwStageEntry`` where ``entityKey='tool'`` — one of the six spellings that made
#: "how many clusters weave on a pit loom" unanswerable, and the one no eight-member list holds.
LEGACY_TOOL_TYPE = "Vessel over a firewood hearth"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def client():
    """A TestClient with a signed-in admin, sharing one Prisma connection with the app."""
    import uuid

    from fastapi.testclient import TestClient

    from app.core.db import db
    from app.core.security import create_access_token, hash_password
    from app.main import app

    await db.connect()
    try:
        user = await db.user.create(data={
            "email": f"vocab-test-{uuid.uuid4().hex[:8]}@example.org",
            "name": "Vocabulary Test",
            "role": "ADMIN",
            "passwordHash": hash_password("unused"),
        })
    finally:
        await db.disconnect()

    with TestClient(app) as c:
        c.headers.update({"Authorization": f"Bearer {create_access_token(subject=user.id)}"})
        yield c


@pytest.mark.anyio
@needs_db
async def test_a_tool_row_written_before_the_enum_existed_still_saves_and_still_prints(client):
    """THE MIGRATION, END TO END: no 422, no silent loss, and the sentence still in the report.

    The row is submitted with ``submit: true`` — the strictest path there is, the one that turns
    on required-field enforcement — because the failure a conversion would have caused is exactly
    a 422 on a box the designer never touched. ``toolFamily`` is left EMPTY on purpose: that is
    the state every one of the eleven existing rows is in, and an enum field that made the row
    unsubmittable until someone re-classified it by hand would block fieldwork to buy tidiness.
    """
    created = client.post("/api/design-workshops", json={"title": "Vocabulary round trip"})
    assert created.status_code == 201, created.text
    workshop_id = created.json()["id"]

    saved = client.put(
        f"/api/design-workshops/{workshop_id}/stages/TRADITIONAL_PROCESS_BASELINE",
        json={
            "entries": [{
                "entityKey": "tool",
                "ordinal": 0,
                "data": {
                    "_clientKey": "legacy-tool",
                    "name": "Dyeing vessel",
                    "toolType": LEGACY_TOOL_TYPE,
                },
            }],
            "submit": True,
        },
    )
    assert saved.status_code == 200, saved.text
    assert saved.json()["errors"] == {}, "a stage written last season was refused at submit"

    rows = client.get(
        f"/api/design-workshops/{workshop_id}/stages/TRADITIONAL_PROCESS_BASELINE"
    ).json()["collections"]["tool"]
    assert [r["toolType"] for r in rows] == [LEGACY_TOOL_TYPE], "the stored sentence changed"

    preview = client.get(f"/api/design-workshops/{workshop_id}/report/preview")
    assert preview.status_code == 200, preview.text
    # Searched over the serialised blocks rather than a known block index: which table the tool
    # lands in is the template's business, and pinning it here would make this test fail the next
    # time a template is retitled — which is not the thing being protected.
    assert LEGACY_TOOL_TYPE in json.dumps(preview.json(), ensure_ascii=False), \
        "the answer was accepted and stored, then dropped out of the report"


@pytest.mark.anyio
@needs_db
async def test_the_same_sentence_offered_to_the_enum_is_refused_without_taking_the_row_with_it(
    client
):
    """WHAT CONVERTING ``toolType`` IN PLACE WOULD HAVE DONE, demonstrated against the real save.

    The token check is not advisory — ``coerce_value`` refuses it — so had the sentence been left
    sitting in a field that became an ENUM, this is the response every affected row would have
    got. The row still saves, and the neighbouring free text still lands, because ``save_stage``
    keeps going past a bad field; but the designer is shown an error on a box they never opened,
    and on the web the converted dropdown would have been drawn EMPTY over their answer.
    """
    created = client.post("/api/design-workshops", json={"title": "Vocabulary counterfactual"})
    workshop_id = created.json()["id"]

    saved = client.put(
        f"/api/design-workshops/{workshop_id}/stages/TRADITIONAL_PROCESS_BASELINE",
        json={
            "entries": [{
                "entityKey": "tool",
                "ordinal": 0,
                "data": {
                    "_clientKey": "counterfactual",
                    "name": "Dyeing vessel",
                    "toolType": LEGACY_TOOL_TYPE,
                    "toolFamily": LEGACY_TOOL_TYPE,
                },
            }],
        },
    )
    assert saved.status_code == 200, saved.text
    assert "toolFamily" in saved.json()["errors"].get("tool[0]", {})

    rows = client.get(
        f"/api/design-workshops/{workshop_id}/stages/TRADITIONAL_PROCESS_BASELINE"
    ).json()["collections"]["tool"]
    assert rows[0]["toolType"] == LEGACY_TOOL_TYPE
    assert not rows[0].get("toolFamily")
