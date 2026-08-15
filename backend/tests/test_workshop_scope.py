"""The workshop scope in the shared filter vocabulary.

The workshop is the unit the fieldwork happens in, so "the records from these workshops" is where
every cross-workshop conclusion starts. It lives in ``record_filters`` rather than on whichever screen
needed it first, and these are the rules the map, the search box, the completion matrix and the
consolidated questionnaire all depend on agreeing about.
"""

import asyncio
from datetime import UTC

from app.services.record_filters import (
    RECORD_TYPES,
    UNASSIGNED_WORKSHOP,
    artisan_workshop_clause,
    bucket_workshop_clause,
    build_record_wheres,
    craft_workshop_clause,
    resolve_workshop_ids,
    workshop_clause,
)


class FakeUser:
    def __init__(self, role: str = "RESEARCHER", user_id: str = "u1"):
        self.role = role
        self.id = user_id


USER = FakeUser()


# --- Parsing --------------------------------------------------------------------------------


def test_absent_means_every_workshop_not_none_of_them():
    # THE distinction this parser exists for. "The control is at its default" and "the user asked for
    # nothing" must not be spelled the same way, or the map opens empty for everybody.
    assert resolve_workshop_ids(None) is None
    assert resolve_workshop_ids([]) is None
    assert resolve_workshop_ids(["  "]) is None
    assert resolve_workshop_ids([""]) is None


def test_both_spellings_clients_build_are_accepted():
    # The web joins with commas; Android repeats the parameter. A scope that quietly covered
    # everything because it was spelled the other way would look exactly like the control not working.
    assert resolve_workshop_ids(["w1", "w2"]) == (["w1", "w2"], False)
    assert resolve_workshop_ids(["w1,w2"]) == (["w1", "w2"], False)
    assert resolve_workshop_ids(["w1, w2", "w3"]) == (["w1", "w2", "w3"], False)


def test_ids_are_deduplicated_in_first_seen_order():
    assert resolve_workshop_ids(["w2", "w1", "w2"]) == (["w2", "w1"], False)


def test_the_unassigned_sentinel_is_split_out_rather_than_treated_as_an_id():
    assert resolve_workshop_ids([UNASSIGNED_WORKSHOP]) == ([], True)
    assert resolve_workshop_ids(["w1", UNASSIGNED_WORKSHOP]) == (["w1"], True)
    assert resolve_workshop_ids([f"w1,{UNASSIGNED_WORKSHOP},w2"]) == (["w1", "w2"], True)


# --- The clause -----------------------------------------------------------------------------


def test_a_foreign_key_table_filters_on_workshop_id():
    assert workshop_clause(["w1", "w2"], False) == {"workshopId": {"in": ["w1", "w2"]}}


def test_the_workshop_table_filters_on_its_own_primary_key():
    # A workshop row IS the workshop. Filtering Workshop.workshopId is filtering a column that does
    # not exist, which Prisma answers with an error rather than an empty result.
    assert workshop_clause(["w1"], False, is_workshop_table=True) == {"id": {"in": ["w1"]}}


def test_unassigned_widens_a_foreign_key_table_and_empties_the_workshop_table():
    assert workshop_clause(["w1"], True) == {
        "OR": [{"workshopId": {"in": ["w1"]}}, {"workshopId": None}]
    }
    assert workshop_clause([], True) == {"workshopId": None}
    # A workshop cannot be unassigned from itself, so "unassigned only" matches no workshop row.
    assert workshop_clause([], True, is_workshop_table=True) is None
    assert workshop_clause([], False) is None


def test_a_single_branch_is_written_flat_rather_than_as_a_one_armed_or():
    # Same query to Postgres, a far more readable one in a log.
    assert "OR" not in workshop_clause(["w1"], False)


# --- Composition into the bucket wheres -----------------------------------------------------


def test_no_workshop_scope_leaves_every_bucket_untouched():
    wheres = asyncio.run(build_record_wheres(USER))
    assert all(wheres[bucket] == {} for bucket in RECORD_TYPES)


def test_the_scope_reaches_every_bucket_with_the_right_column():
    wheres = asyncio.run(build_record_wheres(USER, workshop_ids=["w1"]))
    # The three buckets whose only link to a workshop IS the column.
    for bucket in ("products", "tools", "media"):
        assert wheres[bucket]["AND"] == [{"workshopId": {"in": ["w1"]}}], bucket
    # ...and the workshops bucket on its own id.
    assert wheres["workshops"]["AND"] == [{"id": {"in": ["w1"]}}]


def test_the_artisans_bucket_gets_all_three_readings_not_just_the_column():
    # THE REGRESSION. This bucket was narrowed by the bare column while `GET /artisans`, the dataset
    # export and the questionnaire index narrowed the same question with `artisan_workshop_clause`.
    # An artisan linked only by a WorkshopArtisan roster row — which the workshop form's "linked
    # artisans" picker writes WITHOUT touching the column — or only by having sat in an interview
    # taken at the workshop was therefore listed by Browse artisans and missing from Search and the
    # map under the identical scope tick, with nothing on either screen to say which was right.
    #
    # Asserted against the helper rather than against a literal, deliberately: the point of the fix
    # is that there is now ONE reading, so a fourth way of belonging to a workshop must move both
    # sides of this comparison together or neither.
    wheres = asyncio.run(build_record_wheres(USER, workshop_ids=["w1"]))
    assert wheres["artisans"]["AND"] == [artisan_workshop_clause(["w1"], False)]
    assert wheres["artisans"]["AND"] != [{"workshopId": {"in": ["w1"]}}]


def test_an_impossible_selection_is_written_as_impossible_rather_than_dropped():
    # "Unassigned only" cannot match a workshop row. Leaving that bucket unfiltered would show every
    # workshop in the repository under a scope that excludes them all.
    wheres = asyncio.run(build_record_wheres(USER, workshop_ids=[UNASSIGNED_WORKSHOP]))
    assert wheres["workshops"]["AND"] == [{"id": {"in": []}}]
    # The artisans bucket keeps the helper's own spelling of "unassigned" — a one-armed OR on the
    # null column, NOT the bare `{"workshopId": None}` this loop used to write. Same rows, but it
    # comes from the one helper, which is the property being pinned.
    assert wheres["artisans"]["AND"] == [artisan_workshop_clause([], True)]


# --- The dispatcher -------------------------------------------------------------------------


def test_the_dispatcher_gives_each_table_its_own_reading():
    # One entry point, so a caller with a table name in hand cannot pick the wrong reading. Crafts
    # are here even though no search/map bucket carries them: `datasets` narrows a craft table by
    # this same question, and the answer must not depend on which screen asked.
    assert bucket_workshop_clause("artisans", ["w1"], False) == artisan_workshop_clause(["w1"], False)
    assert bucket_workshop_clause("artisan", ["w1"], False) == artisan_workshop_clause(["w1"], False)
    assert bucket_workshop_clause("crafts", ["w1"], False) == craft_workshop_clause(["w1"], False)
    assert bucket_workshop_clause("craft", ["w1"], False) == craft_workshop_clause(["w1"], False)
    assert bucket_workshop_clause("products", ["w1"], False) == {"workshopId": {"in": ["w1"]}}
    assert bucket_workshop_clause("workshops", ["w1"], False) == {"id": {"in": ["w1"]}}
    assert bucket_workshop_clause("self", ["w1"], False) == {"id": {"in": ["w1"]}}


#: The predicate shapes Prisma reads as NO CONSTRAINT AT ALL — every row matches. `None` is the
#: historical one (`workshop_clause` returns it for a selection that cannot narrow a table); `{}` is
#: what a helper produces the moment it collapses an empty branch list into "nothing to say"; an
#: empty `AND`/`NOT` list is Prisma's own spelling of "no conditions". Any of these coming back from
#: the dispatcher is a DROPPED FILTER, and a dropped filter WIDENS — it shows the whole repository
#: under a scope that excludes all of it, dressed as a correct answer.
_MATCHES_EVERYTHING = (None, {}, {"AND": []}, {"NOT": []})

#: The only predicate that provably matches no row of any table: `id IN ()`. This is what
#: `bucket_workshop_clause` substitutes, and asserting the exact value (rather than merely
#: "something truthy") is what keeps a future "simplification" from swapping in one of the shapes
#: above and still passing.
_MATCHES_NOTHING = {"id": {"in": []}}


def test_an_unsatisfiable_scope_narrows_to_nothing_rather_than_quietly_widening_to_everything():
    # WHY THIS TEST WAS REWRITTEN. It used to read `assert bucket_workshop_clause(...) is not None`,
    # which does not exercise the failure mode the dispatcher exists to prevent: three of the four
    # shapes in `_MATCHES_EVERYTHING` are not None and every one of them is the dropped filter. The
    # line below is that demonstration, kept executable so nobody has to take it on trust — if the
    # dispatcher ever returned `{}`, the old assertion passed and the whole repository leaked.
    assert all(shape is not None for shape in _MATCHES_EVERYTHING[1:])

    # THE DIRECTION MATTERS AND IT IS ASYMMETRIC. Too-narrow shows a user an empty screen, which
    # they report. Too-wide shows them plausible rows from workshops their scope excluded, which
    # nobody reports because nothing looks wrong. So every unsatisfiable selection below must come
    # back as the impossible predicate, never as an absent one.

    # (a) An EMPTY ID LIST with no "unassigned" arm: nothing was selected that any row could match.
    for bucket in ("artisans", "crafts", "products", "tools", "media", "workshops", "self"):
        clause = bucket_workshop_clause(bucket, [], False)
        assert clause not in _MATCHES_EVERYTHING, f"{bucket} dropped its filter"
        assert clause == _MATCHES_NOTHING, bucket

    # (b) THE IMPOSSIBLE SELECTION — "unassigned only" against the workshops table. This is the real
    # case, not a hypothetical: `workshop_clause` genuinely returns None here (a workshop cannot be
    # unassigned from itself), so this is the branch where the substitution actually fires.
    for bucket in ("workshops", "self"):
        clause = bucket_workshop_clause(bucket, [], True)
        assert clause not in _MATCHES_EVERYTHING, f"{bucket} dropped its filter"
        assert clause == _MATCHES_NOTHING, bucket

    # (c) AN UNKNOWN BUCKET NAME — a table this dispatcher has never heard of, or a typo, or a
    # `DatasetSpec.workshop_filter` value added later. It must not fall through to "no filter".
    # With a real selection it still narrows (by the bare column, the only reading available for a
    # table with no entry in `_WIDE_WORKSHOP_CLAUSES`); with nothing selected it is impossible.
    unknown = bucket_workshop_clause("questionnaireInterviews", ["w1"], False)
    assert unknown not in _MATCHES_EVERYTHING
    assert unknown == {"workshopId": {"in": ["w1"]}}
    assert bucket_workshop_clause("questionnaireInterviews", [], False) == _MATCHES_NOTHING


def test_the_scope_composes_with_a_free_text_search_rather_than_replacing_it():
    # THE regression this guards. The search OR is assigned to `where["OR"]`; if the workshop clause
    # ever landed on the same key, a text search inside a workshop would return the whole repository.
    wheres = asyncio.run(build_record_wheres(USER, q="bagru", workshop_ids=["w1"]))
    for bucket in RECORD_TYPES:
        where = wheres[bucket]
        assert "OR" in where, f"{bucket} lost its text search"
        assert where["AND"], f"{bucket} lost its workshop scope"


def test_the_scope_composes_with_the_workshop_date_range():
    # The workshops bucket is the only one that already writes an AND of its own (its startDate
    # fallback), so it is the one where an assignment rather than an append would silently drop a
    # filter. Both clauses have to survive.
    from datetime import datetime

    wheres = asyncio.run(
        build_record_wheres(
            USER, date_from=datetime(2026, 7, 1, tzinfo=UTC), workshop_ids=["w1"]
        )
    )
    clauses = wheres["workshops"]["AND"]
    assert len(clauses) == 2
    assert {"id": {"in": ["w1"]}} in clauses
    assert any("OR" in clause for clause in clauses), "the startDate fallback went missing"


def test_the_scope_composes_with_every_other_filter_at_once():
    from datetime import datetime

    wheres = asyncio.run(
        build_record_wheres(
            USER,
            q="cane",
            craft_id="c1",
            place="Bareilly",
            date_from=datetime(2026, 7, 1, tzinfo=UTC),
            workshop_ids=["w1", UNASSIGNED_WORKSHOP],
        )
    )
    tools = wheres["tools"]
    assert tools["craftId"] == "c1"
    assert tools["place"] == {"contains": "Bareilly", "mode": "insensitive"}
    assert tools["createdAt"] == {"gte": datetime(2026, 7, 1, tzinfo=UTC)}
    assert "OR" in tools
    assert tools["AND"] == [
        {"OR": [{"workshopId": {"in": ["w1"]}}, {"workshopId": None}]}
    ]
