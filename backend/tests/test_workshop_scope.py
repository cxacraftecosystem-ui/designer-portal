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



def test_absent_means_every_workshop_not_none_of_them():
    assert resolve_workshop_ids(None) is None
    assert resolve_workshop_ids([]) is None
    assert resolve_workshop_ids(["  "]) is None
    assert resolve_workshop_ids([""]) is None


def test_both_spellings_clients_build_are_accepted():
    assert resolve_workshop_ids(["w1", "w2"]) == (["w1", "w2"], False)
    assert resolve_workshop_ids(["w1,w2"]) == (["w1", "w2"], False)
    assert resolve_workshop_ids(["w1, w2", "w3"]) == (["w1", "w2", "w3"], False)


def test_ids_are_deduplicated_in_first_seen_order():
    assert resolve_workshop_ids(["w2", "w1", "w2"]) == (["w2", "w1"], False)


def test_the_unassigned_sentinel_is_split_out_rather_than_treated_as_an_id():
    assert resolve_workshop_ids([UNASSIGNED_WORKSHOP]) == ([], True)
    assert resolve_workshop_ids(["w1", UNASSIGNED_WORKSHOP]) == (["w1"], True)
    assert resolve_workshop_ids([f"w1,{UNASSIGNED_WORKSHOP},w2"]) == (["w1", "w2"], True)



def test_a_foreign_key_table_filters_on_workshop_id():
    assert workshop_clause(["w1", "w2"], False) == {"workshopId": {"in": ["w1", "w2"]}}


def test_the_workshop_table_filters_on_its_own_primary_key():
    assert workshop_clause(["w1"], False, is_workshop_table=True) == {"id": {"in": ["w1"]}}


def test_unassigned_widens_a_foreign_key_table_and_empties_the_workshop_table():
    assert workshop_clause(["w1"], True) == {
        "OR": [{"workshopId": {"in": ["w1"]}}, {"workshopId": None}]
    }
    assert workshop_clause([], True) == {"workshopId": None}
    assert workshop_clause([], True, is_workshop_table=True) is None
    assert workshop_clause([], False) is None


def test_a_single_branch_is_written_flat_rather_than_as_a_one_armed_or():
    assert "OR" not in workshop_clause(["w1"], False)



def test_no_workshop_scope_leaves_every_bucket_untouched():
    wheres = asyncio.run(build_record_wheres(USER))
    assert all(wheres[bucket] == {} for bucket in RECORD_TYPES)


def test_the_scope_reaches_every_bucket_with_the_right_column():
    wheres = asyncio.run(build_record_wheres(USER, workshop_ids=["w1"]))
    for bucket in ("products", "tools", "media"):
        assert wheres[bucket]["AND"] == [{"workshopId": {"in": ["w1"]}}], bucket
    assert wheres["workshops"]["AND"] == [{"id": {"in": ["w1"]}}]


def test_the_artisans_bucket_gets_all_three_readings_not_just_the_column():
    wheres = asyncio.run(build_record_wheres(USER, workshop_ids=["w1"]))
    assert wheres["artisans"]["AND"] == [artisan_workshop_clause(["w1"], False)]
    assert wheres["artisans"]["AND"] != [{"workshopId": {"in": ["w1"]}}]


def test_an_impossible_selection_is_written_as_impossible_rather_than_dropped():
    wheres = asyncio.run(build_record_wheres(USER, workshop_ids=[UNASSIGNED_WORKSHOP]))
    assert wheres["workshops"]["AND"] == [{"id": {"in": []}}]
    assert wheres["artisans"]["AND"] == [artisan_workshop_clause([], True)]



def test_the_dispatcher_gives_each_table_its_own_reading():
    assert bucket_workshop_clause("artisans", ["w1"], False) == artisan_workshop_clause(["w1"], False)
    assert bucket_workshop_clause("artisan", ["w1"], False) == artisan_workshop_clause(["w1"], False)
    assert bucket_workshop_clause("crafts", ["w1"], False) == craft_workshop_clause(["w1"], False)
    assert bucket_workshop_clause("craft", ["w1"], False) == craft_workshop_clause(["w1"], False)
    assert bucket_workshop_clause("products", ["w1"], False) == {"workshopId": {"in": ["w1"]}}
    assert bucket_workshop_clause("workshops", ["w1"], False) == {"id": {"in": ["w1"]}}
    assert bucket_workshop_clause("self", ["w1"], False) == {"id": {"in": ["w1"]}}


_MATCHES_EVERYTHING = (None, {}, {"AND": []}, {"NOT": []})

_MATCHES_NOTHING = {"id": {"in": []}}


def test_an_unsatisfiable_scope_narrows_to_nothing_rather_than_quietly_widening_to_everything():
    assert all(shape is not None for shape in _MATCHES_EVERYTHING[1:])

    for bucket in ("artisans", "crafts", "products", "tools", "media", "workshops", "self"):
        clause = bucket_workshop_clause(bucket, [], False)
        assert clause not in _MATCHES_EVERYTHING, f"{bucket} dropped its filter"
        assert clause == _MATCHES_NOTHING, bucket

    for bucket in ("workshops", "self"):
        clause = bucket_workshop_clause(bucket, [], True)
        assert clause not in _MATCHES_EVERYTHING, f"{bucket} dropped its filter"
        assert clause == _MATCHES_NOTHING, bucket

    unknown = bucket_workshop_clause("questionnaireInterviews", ["w1"], False)
    assert unknown not in _MATCHES_EVERYTHING
    assert unknown == {"workshopId": {"in": ["w1"]}}
    assert bucket_workshop_clause("questionnaireInterviews", [], False) == _MATCHES_NOTHING


def test_the_scope_composes_with_a_free_text_search_rather_than_replacing_it():
    wheres = asyncio.run(build_record_wheres(USER, q="bagru", workshop_ids=["w1"]))
    for bucket in RECORD_TYPES:
        where = wheres[bucket]
        assert "OR" in where, f"{bucket} lost its text search"
        assert where["AND"], f"{bucket} lost its workshop scope"


def test_the_scope_composes_with_the_workshop_date_range():
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
