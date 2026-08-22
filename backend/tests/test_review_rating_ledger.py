"""The rating ledger's foundations: the registry fields, the table, and the vocabularies between.

``test_design_ratings`` covers the rules the service applies and ``test_design_ratings_api`` covers
them over a real request. This module covers the layer underneath both — the declarations that have
to agree with each other before either of those can be right, across four files in three languages
that no compiler checks against one another:

    stage_definitions.py   the registry fields the feature reads
    stage_schema.py        REVIEW_ROUND, the controlled list both clients render
    schema.prisma          DwReviewRound and the DwReviewRating model
    the migration          the DDL that is what Postgres actually enforces
    design_ratings.py      RatingRound, RATING_DELEGATE and the columns it names

Nothing here needs a database. These are declaration-versus-declaration checks, which is what makes
them the cheap signal: a drift between any two of the five is a runtime failure on a surface a
designer is standing in front of, and every one of them is visible from a text file today.

================================================================================================
THE TWO TESTS IN HERE THAT ARE NOT ABOUT DRIFT
================================================================================================

:func:`test_the_ledger_is_not_a_fourth_write_only_table` is a different kind of check, and it is
the reason this module exists rather than the assertions being scattered into the two suites above.

This repository already carries THREE tables that are written and never read back: ``ReviewLog``
has two create sites and no reader, and ``DwAiLayerDecision`` and ``DwWorkshopConsentDecision``
both say "RECORDED, NOT YET SERVED" in their own docstrings. Each was a deliberate, defensible
omission on the day it landed, and each is still there. A fourth would not be defensible, because
here the ratings ARE the feature — the review tab's default order is the mean of ``score``, so a
ledger with no reader leaves that page sorting by nothing.

So the pairing is PINNED rather than promised: the day any code under ``app/`` creates a row in the
ledger without any code under ``app/`` reading one back, this fails. It is deliberately a biconditional
and not an assertion that a reader exists, so that the constraint is enforceable BEFORE either half
is written and cannot be satisfied by adding the write first and meaning to come back.

:func:`test_the_review_round_key_is_descriptive_and_the_ledger_is_authoritative` is the mirror of
it, and it is here for the same reason. ``sketchReview.reviewRound`` and
``prototypeValidation.reviewRound`` are designer-picked keys that describe a review row; the round a
rating COUNTS under is ``DwReviewRating.round``, which the server sets. Nothing reconciles the two,
which is fine while nothing reads the registry key and is a silent second source of truth the moment
something does — so that test fails at exactly that moment, and says to derive the value instead.
"""

import ast
import pathlib
import re

# Importing this module is what installs the twenty-two stages into the registry.
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
    """Does this module's CODE name `needle` — as opposed to its prose talking about it?

    THE DISTINCTION IS THE WHOLE POINT OF THE TEST BELOW, and a substring scan of the file text
    cannot make it. The hazard being pinned is a module that READS the registry key: a subscript,
    a `.get`, an attribute, a name. A module that EXPLAINS in a comment which key the ledger is
    authoritative over is the opposite of the hazard — it is the reconciliation being written down
    — and failing on it would make the correct move (documenting the sibling key) the move that
    breaks the suite. It did: `design_ratings.POOL_OPENS_WHEN_FIELD`'s note names
    ``sketchReview.reviewRound`` while proving that nothing reads it.

    So this parses instead of grepping. Comments never reach the AST at all, and docstrings are
    dropped by node identity, which leaves exactly the places a key can be USED from: a string
    literal that is not a docstring, an attribute, a plain name, a parameter or keyword name. A
    read hidden in an f-string still lands here, because its literal parts are `Constant` nodes.
    """
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))

    # The docstrings, by node identity rather than by value: a module, class or function whose
    # first statement is a bare string literal. `tree` stays referenced for the whole call, so the
    # ids stay valid.
    prose = set()
    for node in ast.walk(tree):
        if not isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        first = node.body[0] if node.body else None
        if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant):
            if isinstance(first.value.value, str):
                prose.add(id(first.value))

    for node in ast.walk(tree):
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            if id(node) not in prose and needle in node.value:
                return True
        elif isinstance(node, ast.Attribute) and needle in node.attr:
            return True
        elif isinstance(node, ast.Name) and needle in node.id:
            return True
        elif isinstance(node, ast.arg) and needle in node.arg:
            return True
        elif isinstance(node, ast.keyword) and node.arg and needle in node.arg:
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
    """The field names a model block declares, ignoring comments, attributes and relations."""
    names = set()
    for line in block.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(("//", "///", "@@")):
            continue
        names.add(stripped.split()[0])
    return names


# --------------------------------------------------------------------------------------
# The registry half
# --------------------------------------------------------------------------------------


def test_the_registry_is_still_sound():
    """Unique keys, canonical enums, resolvable refs, Basic-tier-only-required — after the edit."""
    problems = validate_registry()
    assert problems == [], "\n".join(problems)


def test_both_review_entities_can_say_which_round_they_belong_to():
    """The distinction the owner asked for: peers in this workshop, or the whole pool.

    On BOTH review entities and not just one. A sketch is reviewed at stage 12 and a prototype at
    stage 15, and a round recorded on one of the two would leave half the feature unable to say
    whose verdict it was carrying.
    """
    for stage_number, entity_key in ((12, "sketchReview"), (15, "prototypeValidation")):
        field = _entity(stage_number, entity_key).field("reviewRound")
        assert field is not None, f"{entity_key} cannot say which round it belongs to"
        assert field.type is FieldType.ENUM
        assert field.enum == "REVIEW_ROUND"
        assert field.tier is Tier.BASIC
        # NOT REQUIRED, and the assertion is the point rather than an accident of the declaration.
        # Only Basic fields MAY be required, and making this one required would put every review
        # row already in the database into a permanently incomplete stage over a question nobody
        # was asked when they filled it in.
        assert not field.required, f"{entity_key}.reviewRound must not be required"


def test_the_review_round_key_is_descriptive_and_the_ledger_is_authoritative():
    """`reviewRound` describes a review ROW. `DwReviewRating.round` decides what a rating COUNTS as.

    THE HAZARD THIS PINS. The two are set by different hands and nothing reconciles them: the
    ledger's column is written server-side from `design_ratings.RatingRound` once `pool_is_open`
    has decided the round, while the registry key is a dropdown a designer picks. A `sketchReview`
    row can therefore say POOL while every ledger row for that sketch says PEER, and that is
    tolerable ONLY while the registry key is descriptive — read by the report builder's generic
    KEY_VALUE path, the same reader every other descriptive key on the entity has, and by nothing
    that makes a decision.

    So the moment any module under ``app/`` starts reading the key, this fails. Not because reading
    it is forbidden, but because at that point it has stopped being a description and become a
    second source of truth, and settled architecture rule 1 applies: it has to be DERIVED at save
    from the round the ledger already knows, the way every other mirrored field is derived, rather
    than trusted from the client. This is the mirror of
    :func:`test_the_ledger_is_not_a_fourth_write_only_table` — that one refuses a write with no
    read, this one refuses a read with no reconciliation — and it is written now, before either
    exists, for the same reason: written afterwards it would arrive too late to force the choice.

    IT SCANS CODE AND NOT PROSE, via :func:`_code_mentions`, and that is a correction rather than a
    convenience. The first draft grepped the file text, so it fired on
    ``design_ratings.POOL_OPENS_WHEN_FIELD``'s note — a comment that names ``sketchReview.reviewRound``
    in order to explain that the ledger, not the key, decides a round. Documenting the very
    reconciliation this test asks for is not the hazard it guards; making that the failing move would
    have taught the next reader to delete the explanation instead of the read.
    """
    readers = []
    for path in sorted(APP.rglob("*.py")):
        if path.name == "stage_definitions.py":
            continue   # the declarations themselves, and the note that explains all of this
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
    """`_ordinal` carries no author and no timestamp, so the override marker has to.

    `entry_provenance.stamp` skips every `_`-prefixed protocol key by name, so a reorder is the one
    edit in this application that nobody signs. Without these two fields a list of ten sketches in
    score order and a list of ten a designer deliberately arranged are the same bytes.
    """
    for stage_number, entity_key in ((11, "sketch"), (13, "prototype")):
        entity = _entity(stage_number, entity_key)

        by = entity.field("rankFixedBy")
        assert by is not None, f"{entity_key} cannot record who fixed its order"
        # TEXT AND NOT REF, and this assertion is load-bearing rather than descriptive. A REF
        # resolves against the five REFERENCE_MODELS or an entity of this workshop, and `User` is
        # in neither set — so a REF here would render a picker with nothing behind it. If somebody
        # later teaches the resolver about accounts, this is the test that should be revisited
        # deliberately rather than the field quietly retyped.
        assert by.type is FieldType.TEXT, "REF cannot point at a User; see the field's own note"
        assert by.tier is Tier.ADVANCED

        at = entity.field("rankFixedAt")
        assert at is not None, f"{entity_key} cannot record when its order was fixed"
        assert at.type is FieldType.DATE
        assert at.tier is Tier.ADVANCED


def test_the_pool_round_opens_on_both_rateable_entities_and_identically():
    """`peerRoundClosedAt` is the finalisation event, and BOTH rateable subjects need one.

    `design_ratings.pool_is_open` reads exactly this key off whichever row it was handed, so a
    rateable entity that does not declare it has no reachable second round at all. That was the
    state of `sketch` until this field was appended to it, and it read as a decision while being an
    omission: `sketchReview.reviewRound` declares the two-token REVIEW_ROUND list on the entity
    whose subject IS a sketch, and `RATEABLE_ENTITIES` names `sketch` beside `prototype`.

    THE TYPE AND TIER ARE ASSERTED TO MATCH, not merely to exist. Two spellings of one gate —
    a DATE on one entity and, say, a BOOL on the other — would make `pool_is_open`'s "a non-empty
    string opens it" rule mean two different things depending on which row it was reading.

    AND THE CARRYING SET IS WALKED OUT OF THE REGISTRY, not written down here. The first draft of
    this test compared `set(RATEABLE_ENTITIES)` against the keys of a dict built from the literal
    pair `((11, "sketch"), (13, "prototype"))`, which is the pair spelled twice and an equality that
    can only fail if `RATEABLE_ENTITIES` itself is edited. It could not see the drift its own
    message names: `peerRoundClosedAt` appended to `prototypeStageLog` — a material line-item — or
    to `sketchReview` would have published rows nobody meant to publish, and that draft passed.
    """
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
    """`sketchReview.rank` never ORDERED anything, and ranking is `DwStageEntry.ordinal`.

    "No consumer" is what an earlier draft of this docstring and of the field's own note said, and
    it was wrong in a way that mattered: `rank` carried no `report_role`, so it took
    `FieldSpec.report_role`'s `KEY_VALUE` default and PRINTED as a "Rank: 3" pair under its review's
    sub-heading in every template except COMPACT_SUMMARY (`ReportTemplate.max_tier` defaults to
    ADVANCED). What it never had was an ORDERING consumer — no client list, no validator, no query
    sorted by it. Deprecating it therefore gives up a printed line, and gives it up silently:
    `report_builder._visible` excludes a deprecated field and `fields_hidden_by_tier` skips
    deprecated fields too, so the "these filled fields were left out" warning does not fire.

    Deprecated rather than deleted, because a key is what a designer's fieldwork is stored under.
    The successor named is the sync protocol's own wire key for the ordinal rather than a field of
    this entity, because the successor genuinely is not a field. The ten rows in the development
    database that carry ranks 1-10 are still not migrated, on the corrected facts and not the wrong
    ones: not one of them carries a `sketchRef` (checked 2026-08-22 against the local Postgres), so
    there is no sketch on the other end of the integer to give the position to.
    """
    field = _entity(12, "sketchReview").field("rank")
    assert field is not None, "the key must survive its retirement; it is never reused"
    assert field.deprecated
    assert field.replaced_by == "_ordinal"


def test_a_retired_field_leaves_the_wire_and_the_live_ones_arrive_on_it():
    """`entity_to_dict` omits a deprecated field, so both forms lose the box in one edit.

    Asserted on the SERIALISED registry and not on the specs, because that is what the browser and
    the handset actually render — and because a deprecation that failed to reach the wire would
    leave a dead input on two clients while every declaration-level test still passed.
    """
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
    """Field order drives the report's table columns, and the clients render it as declared.

    Two independent things break on a reorder, which is why this is pinned rather than trusted:
    `_table_columns` truncates at ``columns[:6]``, so a field inserted ahead of one silently pushes
    a column out of every report table; and ``registry_version()`` SORTS before hashing, so a pure
    reorder does not move the digest — a handset that already fetched would compare versions, see
    agreement, never refetch, and render the old order for ever.

    So the four edited entities are asserted to have gained their new fields ONLY at the end.
    """
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

    # And the report's own columns, named rather than counted. Two entities in this change's blast
    # radius, and they are pinned for two DIFFERENT reasons — an earlier draft of this comment said
    # both had "more than six TABLE_COLUMN fields", which is true of exactly one of them.
    #
    # `sketchReview` has FIVE, so `columns[:6]` truncates nothing today and the assertion is a pin
    # on that staying true: an appended field that gained a TABLE_COLUMN role would make six, and a
    # second would start dropping one off the end of every review table.
    review_columns = [
        f.key for f in _entity(12, "sketchReview").fields
        if f.report_role.value == "TABLE_COLUMN" and not f.deprecated
    ]
    assert review_columns == [
        "sketchRef", "decision", "reason", "artisanComments", "reviewedBy",
    ], review_columns

    # `prototypeValidation` has SEVEN and is therefore already over the cap: `marketSuitability` is
    # truncated out of the table and printed by `_render_narrative` instead. These six are the ones
    # the table keeps, and nothing appended may join or displace them.
    validation_columns = [
        f.key for f in _entity(15, "prototypeValidation").fields
        if f.report_role.value == "TABLE_COLUMN" and not f.deprecated
    ]
    assert validation_columns[:6] == [
        "prototypeRef", "decision", "technicalQuality", "functionality", "aesthetics",
        "craftIntegrity",
    ], validation_columns
    assert validation_columns[6:] == ["marketSuitability"], validation_columns


# --------------------------------------------------------------------------------------
# The vocabulary, across all four declarations of it
# --------------------------------------------------------------------------------------


def test_the_round_vocabulary_is_the_same_in_every_language_that_declares_it():
    """Four declarations, no compiler between them.

    A token Python can produce and Postgres refuses is a 500 on the write path. A token Postgres
    holds and Python cannot name is a crash on the read path. A token the registry offers and
    neither accepts is a dropdown entry that 422s the designer who picks it. All three are one
    equality.
    """
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
    """The CHECK constraint hard-codes 1..5, so the list it came from has to stay 1..5.

    `score` is AVERAGED, which is why the bound is in the database at all: a stray 40 does not look
    wrong in a row, it looks wrong in every ranking that row is part of, and by then nobody is
    looking at rows. The cost of putting it in SQL is that widening the scale is now a migration —
    and this test is what makes that cost visible at the moment somebody widens QUALITY_RATING
    instead of six months later when the constraint starts refusing legitimate scores.
    """
    assert sorted(ENUMS["QUALITY_RATING"]) == ["1", "2", "3", "4", "5"]
    assert (MIN_SCORE, MAX_SCORE) == (1, 5)
    assert '"score" BETWEEN 1 AND 5' in MIGRATION.read_text(encoding="utf-8")


def test_the_service_and_the_schema_agree_about_what_is_rateable():
    """`RATEABLE_ENTITIES` names registry entities, so they have to exist and carry the markers."""
    # `set(...)` around the constant rather than a bare comparison: ruff's SIM300 reads an
    # UPPER_CASE name on the left of `==` as a Yoda condition, and `checks.yml` fails the build on
    # a ruff finding. The call keeps the sentence in reading order and the assertion identical.
    assert set(RATEABLE_ENTITIES) == {"sketch", "prototype"}
    assert _entity(11, "sketch") is not None
    assert _entity(13, "prototype") is not None


# --------------------------------------------------------------------------------------
# The table
# --------------------------------------------------------------------------------------


def test_the_ledger_carries_every_column_the_service_says_it_depends_on():
    """`design_ratings`'s module docstring writes the dependency down; this checks it is true.

    That module may not edit `schema.prisma` — a different agent owns it — so the contract between
    them is prose. Prose drifts; this is what stops it.
    """
    block = _prisma_model_block(RATING_TABLE)
    columns = _prisma_columns(block)
    for column in (
        "designWorkshopId", "stageEntryId", "entityKey", "reviewerId", "round",
        "score", "comment", "suggestion", "ratedAt", "createdAt", "updatedAt",
    ):
        assert column in columns, f"{RATING_TABLE} has no {column!r}"

    # The delegate name Prisma will generate is the model name lower-cased, and the service reaches
    # for it with `getattr(db, RATING_DELEGATE)`. A rename of the model that missed the constant
    # would surface as the ledger's own 503 rather than as an import error, which is the failure
    # this repository is worst at noticing.
    assert RATING_TABLE.lower() == RATING_DELEGATE


def test_one_person_cannot_file_five_ratings_of_the_same_thing_in_one_round():
    """The unique triple, in schema.prisma and in the DDL that is what Postgres enforces.

    Round is IN the key deliberately: a workshop's own designer is also a member of the pool, and
    their peer-round view and their pool-round view are two judgements of two different objects.
    Leaving round out would silently discard the second.
    """
    assert "@@unique([stageEntryId, reviewerId, round])" in _prisma_model_block(RATING_TABLE)
    sql = MIGRATION.read_text(encoding="utf-8")
    assert 'CREATE UNIQUE INDEX IF NOT EXISTS "DwReviewRating_stageEntryId_reviewerId_round_key"' in sql
    assert 'ON "DwReviewRating"("stageEntryId", "reviewerId", "round")' in sql


def test_the_reviewer_is_a_real_account_that_cannot_be_deleted_out_from_under_the_row():
    """RESTRICT, exactly as the two decision tables and `ReviewLog` are.

    This row says a named designer judged a colleague's work; the judgement is shown to admins WITH
    THE NAME ON IT and is counted into a ranking the maker sees. An account deleted out from under
    it would leave a score in a ranking that nobody filed.
    """
    block = _prisma_model_block(RATING_TABLE)
    assert 'reviewer User @relation("DwReviewRatingReviewer"' in block
    assert "onDelete: Restrict" in block
    sql = MIGRATION.read_text(encoding="utf-8")
    assert 'REFERENCES "User"("id")\n      ON DELETE RESTRICT' in sql


def test_the_two_parents_cascade_and_the_soft_delete_is_why_that_is_safe():
    """CASCADE on the workshop and the stage entry, matching every design-workshop child table.

    Safe because both fire on a HARD delete only, and there is no hard delete of either on the
    ordinary path: the API's workshop delete is soft and `save_stage` soft-deletes a removed
    collection row. That second half is asserted here rather than assumed, because if a hard delete
    of a stage entry is ever introduced it silently destroys ratings.
    """
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
    """`ratedAt` is the device's, `createdAt` is the server's, and collapsing them fabricates one.

    A rating typed in a courtyard reaches the server on the next sync, which on this fleet can be a
    fortnight later. `DwWorkshopConsentDecision.recordedAt` makes the same split for the same
    reason, and `design_ratings.rating_plan` orders redeliveries by the device clock — so a
    nullable `ratedAt` is not decoration, it is the input to the idempotency rule.
    """
    block = _prisma_model_block(RATING_TABLE)
    assert re.search(r"^\s*ratedAt\s+DateTime\?", block, re.MULTILINE), (
        "ratedAt must be nullable: a rating filed straight against the server has no second clock"
    )
    assert re.search(r"^\s*createdAt\s+DateTime\s+@default\(now\(\)\)", block, re.MULTILINE)
    assert re.search(r"^\s*updatedAt\s+DateTime\s+@updatedAt", block, re.MULTILINE)


def test_widening_the_device_clock_is_not_the_fix_for_the_replay_rule():
    """`ratedAt` stays on the default millisecond timestamp, and this records why.

    `design_ratings._is_stale_delivery` decides a REPLAY by `incoming <= stored`: the outbox
    redelivers the identical capture, the two clocks are meant to be EQUAL, and the answer is
    "already recorded, write nothing". They are not equal, and
    `test_design_ratings_api.py::test_a_rating_round_trips_and_a_repeated_capture_writes_nothing`
    is red because of it.

    THE OBVIOUS FIX IS THE WRONG ONE AND HAS ALREADY BEEN TRIED HERE. A Python datetime carries
    microseconds and `TIMESTAMP(3)` cannot, so the column looks like the culprit. It is not: the
    Prisma query engine truncates a datetime to milliseconds before Postgres is involved. The
    column was widened to `@db.Timestamp(6)` on 2026-08-22, the migration re-applied, the client
    regenerated and the suite re-run — every row still stored `...053000`, and the test stayed red.
    The truncation is upstream of the column and no column type can undo it.

    So this asserts the column is NOT widened, and points the next person at the comparison
    instead. Delete this test only together with a fix that makes the round-trip test green.
    """
    block = _prisma_model_block(RATING_TABLE)
    assert re.search(r"^\s*ratedAt\s+DateTime\?\s*$", block, re.MULTILINE), (
        "ratedAt has been given an explicit precision. If that was an attempt to make the replay "
        "rule see an exact match, it cannot work: the Prisma engine truncates to milliseconds "
        "before the column is reached. Fix _is_stale_delivery's comparison instead."
    )
    sql = MIGRATION.read_text(encoding="utf-8")
    assert '"ratedAt" TIMESTAMP(3)' in sql, "the DDL and the model disagree about the device clock"


def test_no_index_is_declared_that_nothing_probes():
    """Every index on this table is named against the query or the constraint that uses it.

    The schema argues this rule twice — `DwDictationDailyUsage` ("no second index and no scan") and
    `DwAiLayer`'s account pointers ("indexed for the WRITE they are on the wrong end of") — and it
    is easy to violate here, because the pool round SOUNDS like a cross-workshop read and is not:
    `GET /design-ratings/rounds/{round}` requires a workshopId for BOTH rounds, since the placed
    order is `DwStageEntry.ordinal` and an ordinal orders one collection inside one workshop.

    An earlier draft of this table carried `@@index([round, entityKey, createdAt])` for that
    imagined read. It is asserted ABSENT, so it cannot come back without the query that needs it.
    """
    # Anchored to the start of a stripped line. An earlier draft of this model's comments QUOTED
    # three index declarations in prose — two other tables' and the cross-workshop one this table
    # deliberately does not have — and a bare `re.findall` over the block collected all three as if
    # they were real, which is how the first draft of this test reported five indexes on a table
    # that has two. Those three mentions have since been rewritten without the `@@index` token,
    # because `docs/tools/check-docs.mjs` counts the same way and reported them as declarations in
    # REPO_FACTS. The anchor stays: it is what makes the next prose mention harmless.
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
    """`workshop_ratings` is where={designWorkshopId, entityKey, round} — these three, in order."""
    service = (APP / "services" / "design_ratings.py").read_text(encoding="utf-8")
    match = re.search(
        r"async def workshop_ratings\(.*?find_many\(\s*where=\{(.*?)\}", service, re.DOTALL
    )
    assert match, "workshop_ratings no longer issues the query this index was built for"
    predicate = match.group(1)
    for column in ("designWorkshopId", "entityKey", "round"):
        assert f'"{column}"' in predicate, f"{column} left the round listing's predicate"

    assert "@@index([designWorkshopId, entityKey, round])" in _prisma_model_block(RATING_TABLE)


# --------------------------------------------------------------------------------------
# The rule this repository has broken three times
# --------------------------------------------------------------------------------------


def _ledger_call_sites(pattern: str) -> list[str]:
    """Every file under ``app/`` that reaches the ledger delegate with a matching call."""
    hits = []
    for path in sorted(APP.rglob("*.py")):
        text = path.read_text(encoding="utf-8")
        # The service reaches the delegate through `_ledger()`; anything else would reach it as
        # `db.dwreviewrating`. Both spellings count, because a second module written later would
        # use the direct one and must not escape this check.
        for holder in (r"_ledger\(\)", rf"db\.{RATING_DELEGATE}"):
            if re.search(rf"{holder}\.{pattern}\(", text):
                hits.append(str(path.relative_to(BACKEND)))
                break
    return hits


def test_the_ledger_is_not_a_fourth_write_only_table():
    """A write path without a read path is refused, at the moment the write lands.

    THREE LEDGERS IN THIS REPOSITORY ARE ALREADY IN THAT STATE. `ReviewLog` has two create sites
    and no reader; `DwAiLayerDecision` and `DwWorkshopConsentDecision` both say "RECORDED, NOT YET
    SERVED" in their own docstrings. Each was defensible alone and the pattern is not: the question
    "who rated this, and how" becomes answerable to somebody with psql and not to somebody with the
    screen, and nobody notices because nothing fails.

    A BICONDITIONAL AND NOT AN ASSERTION THAT A READER EXISTS, deliberately. Written the other way
    this test could only be added after both halves were built, which is exactly too late — and it
    would fail on a tree where the table has landed and neither half has, which is a legitimate
    intermediate state. Written this way it is green while the ledger is unused, green while both
    halves stand, and red only in the state this repository keeps ending up in.
    """
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
