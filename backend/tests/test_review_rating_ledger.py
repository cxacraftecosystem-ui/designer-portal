import ast
import pathlib
import re

import app.services.stage_definitions  # noqa: F401
from app.services.design_ratings import (
    MAX_SCORE,
    MIN_SCORE,
    POOL_OPENS_WHEN_FIELD,
    RATEABLE_ENTITIES,
    RATING_DELEGATE,
    RATING_TABLE,
    RatingRound,
)
from app.services.stage_schema import (
    ENUMS,
    FieldType,
    Tier,
    all_entities,
    registry_to_dict,
    stage_by_number,
    validate_registry,
)

BACKEND = pathlib.Path(__file__).resolve().parents[1]
SCHEMA_PRISMA = BACKEND / "prisma" / "schema.prisma"
MIGRATION = (
    BACKEND / "prisma" / "migrations" / "20260822120000_dw_review_rating_ledger" / "migration.sql"
)
APP = BACKEND / "app"


def _entity(stage_number: int, entity_key: str):
    entity = stage_by_number(stage_number).entity(entity_key)
    assert entity is not None, f"stage {stage_number} has no {entity_key!r} entity"
    return entity


def _code_mentions(path: pathlib.Path, needle: str) -> bool:
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))

    prose = set()
    for node in ast.walk(tree):
        if not isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        first = node.body[0] if node.body else None
        if (
            isinstance(first, ast.Expr)
            and isinstance(first.value, ast.Constant)
            and isinstance(first.value.value, str)
        ):
            prose.add(id(first.value))

    for node in ast.walk(tree):
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            text = None if id(node) in prose else node.value
        elif isinstance(node, ast.Attribute):
            text = node.attr
        elif isinstance(node, ast.Name):
            text = node.id
        elif isinstance(node, (ast.arg, ast.keyword)):
            text = node.arg
        else:
            text = None
        if text and needle in text:
            return True
    return False


def _prisma_model_block(name: str) -> str:
    text = SCHEMA_PRISMA.read_text(encoding="utf-8")
    match = re.search(rf"^model {name} \{{(.*?)^\}}", text, re.DOTALL | re.MULTILINE)
    assert match, f"model {name} is not in schema.prisma"
    return match.group(1)


def _prisma_enum_members(name: str) -> set[str]:
    text = SCHEMA_PRISMA.read_text(encoding="utf-8")
    match = re.search(rf"^enum {name} \{{(.*?)^\}}", text, re.DOTALL | re.MULTILINE)
    assert match, f"enum {name} is not in schema.prisma"
    return {
        line.strip()
        for line in match.group(1).splitlines()
        if line.strip() and not line.strip().startswith(("//", "///"))
    }


def _prisma_columns(block: str) -> set[str]:
    names = set()
    for line in block.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(("//", "///", "@@")):
            continue
        names.add(stripped.split()[0])
    return names



def test_the_registry_is_still_sound():
    problems = validate_registry()
    assert problems == [], "\n".join(problems)


def test_both_review_entities_can_say_which_round_they_belong_to():
    for stage_number, entity_key in ((12, "sketchReview"), (15, "prototypeValidation")):
        field = _entity(stage_number, entity_key).field("reviewRound")
        assert field is not None, f"{entity_key} cannot say which round it belongs to"
        assert field.type is FieldType.ENUM
        assert field.enum == "REVIEW_ROUND"
        assert field.tier is Tier.BASIC
        assert not field.required, f"{entity_key}.reviewRound must not be required"


def test_the_review_round_key_is_descriptive_and_the_ledger_is_authoritative():
    readers = []
    for path in sorted(APP.rglob("*.py")):
        if path.name == "stage_definitions.py":
            continue
        if _code_mentions(path, "reviewRound"):
            readers.append(str(path.relative_to(BACKEND)))

    assert not readers, (
        "`reviewRound` is a designer-picked registry key with no reconciliation against "
        "`DwReviewRating.round`, and these modules now read it: "
        + ", ".join(readers)
        + ".\n"
        "If the round has to be known server-side, derive it at save from the ledger's round "
        "instead of trusting the key — see the field's note in stage_definitions.py. If this "
        "reader genuinely only prints the key, name it here and say so."
    )


def test_a_deliberate_order_is_distinguishable_from_a_default_one():
    for stage_number, entity_key in ((11, "sketch"), (13, "prototype")):
        entity = _entity(stage_number, entity_key)

        by = entity.field("rankFixedBy")
        assert by is not None, f"{entity_key} cannot record who fixed its order"
        assert by.type is FieldType.TEXT, "REF cannot point at a User; see the field's own note"
        assert by.tier is Tier.ADVANCED

        at = entity.field("rankFixedAt")
        assert at is not None, f"{entity_key} cannot record when its order was fixed"
        assert at.type is FieldType.DATE
        assert at.tier is Tier.ADVANCED


def test_the_pool_round_opens_on_both_rateable_entities_and_identically():
    for stage_number, entity_key in ((11, "sketch"), (13, "prototype")):
        field = _entity(stage_number, entity_key).field(POOL_OPENS_WHEN_FIELD)
        assert field is not None, f"a {entity_key} cannot be declared finished"
        assert field.type is FieldType.DATE
        assert field.tier is Tier.ADVANCED

    carries_the_switch = {
        entity.key
        for _stage, entity in all_entities()
        if any(f.key == POOL_OPENS_WHEN_FIELD for f in entity.fields)
    }
    assert carries_the_switch == set(RATEABLE_ENTITIES), (
        f"{POOL_OPENS_WHEN_FIELD} is declared on {sorted(carries_the_switch)} but the rateable set "
        f"is {sorted(RATEABLE_ENTITIES)}. A rateable entity without the switch has no reachable "
        f"second round; a non-rateable one with it is a row nobody meant to publish"
    )


def test_the_dead_rank_column_is_retired_and_its_key_is_not_reused():
    field = _entity(12, "sketchReview").field("rank")
    assert field is not None, "the key must survive its retirement; it is never reused"
    assert field.deprecated
    assert field.replaced_by == "_ordinal"


def test_a_retired_field_leaves_the_wire_and_the_live_ones_arrive_on_it():
    live = registry_to_dict()
    fields: dict[str, list[str]] = {}
    for stage in live["stages"]:
        for entity in stage["entities"]:
            fields[entity["key"]] = [f["key"] for f in entity["fields"]]

    assert "rank" not in fields["sketchReview"], "the retired column is still on the wire"
    assert "reviewRound" in fields["sketchReview"]
    assert "reviewRound" in fields["prototypeValidation"]
    for key in ("rankFixedBy", "rankFixedAt", "peerRoundClosedAt"):
        assert key in fields["sketch"]
        assert key in fields["prototype"]


def test_nothing_was_inserted_ahead_of_an_existing_field():
    for stage_number, entity_key, appended in (
        (11, "sketch", ["rankFixedBy", "rankFixedAt", "peerRoundClosedAt"]),
        (12, "sketchReview", ["reviewRound"]),
        (13, "prototype", ["rankFixedBy", "rankFixedAt", "peerRoundClosedAt"]),
        (15, "prototypeValidation", ["reviewRound"]),
    ):
        keys = [f.key for f in _entity(stage_number, entity_key).fields]
        assert keys[-len(appended):] == appended, (
            f"{entity_key}'s new fields are not the last ones declared"
        )

    review_columns = [
        f.key for f in _entity(12, "sketchReview").fields
        if f.report_role.value == "TABLE_COLUMN" and not f.deprecated
    ]
    assert review_columns == [
        "sketchRef", "decision", "reason", "artisanComments", "reviewedBy",
    ], review_columns

    validation_columns = [
        f.key for f in _entity(15, "prototypeValidation").fields
        if f.report_role.value == "TABLE_COLUMN" and not f.deprecated
    ]
    assert validation_columns[:6] == [
        "prototypeRef", "decision", "technicalQuality", "functionality", "aesthetics",
        "craftIntegrity",
    ], validation_columns
    assert validation_columns[6:] == ["marketSuitability"], validation_columns



def test_the_round_vocabulary_is_the_same_in_every_language_that_declares_it():
    registry = set(ENUMS["REVIEW_ROUND"])
    assert registry == {"PEER", "POOL"}
    assert registry == _prisma_enum_members("DwReviewRound"), (
        "the registry's REVIEW_ROUND and the DwReviewRound Postgres enum have drifted"
    )
    assert registry == {r.value for r in RatingRound}, (
        "the registry's REVIEW_ROUND and design_ratings.RatingRound have drifted"
    )
    assert "CREATE TYPE \"DwReviewRound\" AS ENUM ('PEER', 'POOL')" in MIGRATION.read_text(
        encoding="utf-8"
    ), "the migration creates a different set of tokens from the one schema.prisma declares"


def test_the_score_range_in_sql_is_the_scale_the_registry_offers():
    assert sorted(ENUMS["QUALITY_RATING"]) == ["1", "2", "3", "4", "5"]
    assert (MIN_SCORE, MAX_SCORE) == (1, 5)
    assert '"score" BETWEEN 1 AND 5' in MIGRATION.read_text(encoding="utf-8")


def test_the_service_and_the_schema_agree_about_what_is_rateable():
    assert set(RATEABLE_ENTITIES) == {"sketch", "prototype"}
    assert _entity(11, "sketch") is not None
    assert _entity(13, "prototype") is not None



def test_the_ledger_carries_every_column_the_service_says_it_depends_on():
    block = _prisma_model_block(RATING_TABLE)
    columns = _prisma_columns(block)
    for column in (
        "designWorkshopId", "stageEntryId", "entityKey", "reviewerId", "round",
        "score", "comment", "suggestion", "ratedAt", "createdAt", "updatedAt",
    ):
        assert column in columns, f"{RATING_TABLE} has no {column!r}"

    assert RATING_TABLE.lower() == RATING_DELEGATE


def test_one_person_cannot_file_five_ratings_of_the_same_thing_in_one_round():
    assert "@@unique([stageEntryId, reviewerId, round])" in _prisma_model_block(RATING_TABLE)
    sql = MIGRATION.read_text(encoding="utf-8")
    assert 'CREATE UNIQUE INDEX IF NOT EXISTS "DwReviewRating_stageEntryId_reviewerId_round_key"' in sql
    assert 'ON "DwReviewRating"("stageEntryId", "reviewerId", "round")' in sql


def test_the_reviewer_is_a_real_account_that_cannot_be_deleted_out_from_under_the_row():
    block = _prisma_model_block(RATING_TABLE)
    assert 'reviewer User @relation("DwReviewRatingReviewer"' in block
    assert "onDelete: Restrict" in block
    sql = MIGRATION.read_text(encoding="utf-8")
    assert 'REFERENCES "User"("id")\n      ON DELETE RESTRICT' in sql


def test_the_two_parents_cascade_and_the_soft_delete_is_why_that_is_safe():
    block = _prisma_model_block(RATING_TABLE)
    assert 'designWorkshop DesignWorkshop @relation("DesignWorkshopReviewRatings"' in block
    assert 'stageEntry     DwStageEntry   @relation("DwStageEntryReviewRatings"' in block
    assert block.count("onDelete: Cascade") == 2

    hard_deletes = [
        path
        for path in APP.rglob("*.py")
        if re.search(r"db\.dwstageentry\.delete", path.read_text(encoding="utf-8"))
    ]
    assert hard_deletes == [], (
        "a stage entry is now hard-deleted somewhere, which CASCADEs its ratings away: "
        f"{[str(p) for p in hard_deletes]}"
    )


def test_both_clocks_are_kept_because_they_answer_different_questions():
    block = _prisma_model_block(RATING_TABLE)
    assert re.search(r"^\s*ratedAt\s+DateTime\?", block, re.MULTILINE), (
        "ratedAt must be nullable: a rating filed straight against the server has no second clock"
    )
    assert re.search(r"^\s*createdAt\s+DateTime\s+@default\(now\(\)\)", block, re.MULTILINE)
    assert re.search(r"^\s*updatedAt\s+DateTime\s+@updatedAt", block, re.MULTILINE)


def test_widening_the_device_clock_is_not_the_fix_for_the_replay_rule():
    block = _prisma_model_block(RATING_TABLE)
    assert re.search(r"^\s*ratedAt\s+DateTime\?\s*$", block, re.MULTILINE), (
        "ratedAt has been given an explicit precision. If that was an attempt to make the replay "
        "rule see an exact match, it cannot work: the Prisma engine truncates to milliseconds "
        "before the column is reached. Fix _is_stale_delivery's comparison instead."
    )
    sql = MIGRATION.read_text(encoding="utf-8")
    assert '"ratedAt" TIMESTAMP(3)' in sql, "the DDL and the model disagree about the device clock"


def test_no_index_is_declared_that_nothing_probes():
    block = _prisma_model_block(RATING_TABLE)
    declared = {
        match.group(1)
        for match in (
            re.match(r"@@index\(\[([^\]]+)\]\)", line.strip()) for line in block.splitlines()
        )
        if match
    }
    assert declared == {"designWorkshopId, entityKey, round", "reviewerId"}, declared


def test_the_round_listing_index_is_the_query_the_service_actually_issues():
    service = (APP / "services" / "design_ratings.py").read_text(encoding="utf-8")
    match = re.search(
        r"async def workshop_ratings\(.*?find_many\(\s*where=\{(.*?)\}", service, re.DOTALL
    )
    assert match, "workshop_ratings no longer issues the query this index was built for"
    predicate = match.group(1)
    for column in ("designWorkshopId", "entityKey", "round"):
        assert f'"{column}"' in predicate, f"{column} left the round listing's predicate"

    assert "@@index([designWorkshopId, entityKey, round])" in _prisma_model_block(RATING_TABLE)



def _ledger_call_sites(pattern: str) -> list[str]:
    hits = []
    for path in sorted(APP.rglob("*.py")):
        text = path.read_text(encoding="utf-8")
        for holder in (r"_ledger\(\)", rf"db\.{RATING_DELEGATE}"):
            if re.search(rf"{holder}\.{pattern}\(", text):
                hits.append(str(path.relative_to(BACKEND)))
                break
    return hits


def test_the_ledger_is_not_a_fourth_write_only_table():
    writers = _ledger_call_sites(r"(?:create|update|upsert|delete|create_many)")
    readers = _ledger_call_sites(r"(?:find_many|find_first|find_unique|count|group_by|aggregate)")

    if writers and not readers:
        raise AssertionError(
            "The rating ledger is written and never read back, which is the fourth write-only "
            "ledger this repository was told not to build.\n"
            f"  writes: {writers}\n"
            "  reads : none\n"
            "The read path ships in the same wave as the write path. If the read cannot ship, "
            "the table should not have."
        )
