import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.services.record_filters import (
    PLACED_TYPES,
    RECORD_TYPES,
    build_record_wheres,
    enum_filter_list_or_422,
    resolve_types,
    tool_craft_clause,
)


class FakeUser:
    def __init__(self, role: str = "CROWDSOURCE", user_id: str = "u1"):
        self.role = role
        self.id = user_id


ADMIN = FakeUser(role="ADMIN", user_id="admin")
VOLUNTEER = FakeUser()


def test_resolve_types_accepts_both_spellings_clients_use():
    assert resolve_types(None) == set(RECORD_TYPES)
    assert resolve_types([]) == set(RECORD_TYPES)
    assert resolve_types(["   "]) == set(RECORD_TYPES)
    assert resolve_types(["artisans", "media"]) == {"artisans", "media"}
    assert resolve_types(["artisans,media"]) == {"artisans", "media"}


def test_resolve_types_refuses_a_name_it_does_not_know():
    with pytest.raises(HTTPException) as error:
        resolve_types(["artisan"])
    assert error.value.status_code == 422
    assert "artisan" in str(error.value.detail)


def test_every_bucket_is_returned_even_when_no_filter_touches_it():
    wheres = asyncio.run(build_record_wheres(ADMIN))
    assert set(wheres) == set(RECORD_TYPES)


def test_reading_the_repository_is_open_to_every_rank():
    for user in (VOLUNTEER, ADMIN):
        wheres = asyncio.run(build_record_wheres(user))
        for bucket in RECORD_TYPES:
            assert wheres[bucket] == {}, f"{bucket} narrowed a plain read for {user.role}"


def test_a_free_text_query_is_the_only_thing_a_plain_search_adds():
    wheres = asyncio.run(build_record_wheres(VOLUNTEER, q="bagru"))
    for bucket in RECORD_TYPES:
        where = wheres[bucket]
        assert "OR" in where, f"{bucket} lost its text search"
        assert "AND" not in where, f"{bucket} carries an empty AND"


def test_the_download_predicate_still_narrows_below_professor(monkeypatch):
    from app.services import records

    calls: list[str] = []
    WORKSHOP_ARM = {"linkedRecordType": "designWorkshop", "linkedRecordId": {"in": ["dw-1"]}}

    async def _fake_branches(user_id: str) -> list[dict[str, Any]]:
        calls.append(user_id)
        return [WORKSHOP_ARM]

    monkeypatch.setattr(records, "_design_workshop_media_branches", _fake_branches)

    record_where = asyncio.run(records.owned_or_granted_where(VOLUNTEER))
    assert record_where["OR"][0] == {"createdById": "u1"}
    assert record_where["OR"][1]["createdBy"]["is"]["dataAccessAsOwner"]["some"] == {
        "granteeId": "u1",
        "status": "GRANTED",
    }
    assert len(record_where["OR"]) == 2
    assert calls == [], "the record variant paid for a workshop lookup it has no use for"

    media = asyncio.run(records.owned_or_granted_where(VOLUNTEER, owner_field="uploadedById"))
    assert media["OR"][0] == {"uploadedById": "u1"}
    assert "uploadedBy" in media["OR"][1]
    assert media["OR"][2] == WORKSHOP_ARM, "the co-designer's recordings arm is gone"
    assert calls == ["u1"]

    assert asyncio.run(records.owned_or_granted_where(ADMIN)) == {}
    assert asyncio.run(records.owned_or_granted_where(ADMIN, owner_field="uploadedById")) == {}
    assert calls == ["u1"]


def test_the_workshop_arm_is_the_tag_pair_and_is_absent_when_there_is_nothing_to_add(monkeypatch):
    from app.services import records
    from app.services.design_workshop_viewers import visible_to_clause
    from app.services.dictation_consent import MEDIA_TAG

    class _DB:
        def __init__(self, rows: list[Any]) -> None:
            self.calls: list[dict[str, Any]] = []
            self.designworkshop = self
            self._rows = rows

        async def find_many(self, **kwargs: Any) -> list[Any]:
            self.calls.append(kwargs)
            return self._rows

    fake = _DB([SimpleNamespace(id="dw-1"), SimpleNamespace(id="dw-2")])
    monkeypatch.setattr(records, "db", fake)

    branches = asyncio.run(records._design_workshop_media_branches("u1"))
    assert branches == [{"linkedRecordType": MEDIA_TAG, "linkedRecordId": {"in": ["dw-1", "dw-2"]}}]
    assert fake.calls[0]["where"] == {"deletedAt": None, **visible_to_clause("u1")}
    assert "viewers" in str(fake.calls[0]["where"]), "a co-designer is a viewer row, not the creator"

    empty = _DB([])
    monkeypatch.setattr(records, "db", empty)
    assert asyncio.run(records._design_workshop_media_branches("u1")) == []

    unused = _DB([SimpleNamespace(id="dw-1")])
    monkeypatch.setattr(records, "db", unused)
    assert asyncio.run(records._design_workshop_media_branches("")) == []
    assert unused.calls == []


def test_mine_means_mine_at_every_rank():
    from app.services.records import own_rows_where

    assert asyncio.run(own_rows_where(VOLUNTEER)) == {"createdById": "u1"}
    assert asyncio.run(own_rows_where(ADMIN)) == {"createdById": "admin"}
    assert asyncio.run(own_rows_where(ADMIN, owner_field="uploadedById")) == {"uploadedById": "admin"}


def test_place_reaches_the_buckets_that_have_the_column_and_no_others():
    wheres = asyncio.run(build_record_wheres(ADMIN, place="Bagru"))
    for bucket in PLACED_TYPES:
        assert wheres[bucket]["place"] == {"contains": "Bagru", "mode": "insensitive"}
    assert "place" not in wheres["media"]


def test_filters_compose_rather_than_replace_one_another():
    when = datetime(2026, 7, 1, tzinfo=UTC)
    wheres = asyncio.run(build_record_wheres(
        ADMIN, q="cane", craft_id="c1", place="Bareilly", date_from=when
    ))
    tools = wheres["tools"]
    assert "OR" in tools and tool_craft_clause(["c1"]) in tools["AND"]
    assert tools["place"] == {"contains": "Bareilly", "mode": "insensitive"}
    assert tools["createdAt"] == {"gte": when}


def test_the_workshop_date_range_falls_back_to_the_legacy_column():
    when = datetime(2026, 7, 1, tzinfo=UTC)
    wheres = asyncio.run(build_record_wheres(ADMIN, date_from=when))
    clause = wheres["workshops"]["AND"][-1]
    assert clause == {"OR": [{"startDate": {"gte": when}}, {"startDate": None, "date": {"gte": when}}]}


def test_artisans_are_inside_the_date_range_too():
    when = datetime(2026, 7, 1, tzinfo=UTC)
    wheres = asyncio.run(build_record_wheres(ADMIN, date_from=when))
    assert wheres["artisans"]["createdAt"] == {"gte": when}


def test_a_nul_byte_in_the_query_is_stripped_not_rejected():
    wheres = asyncio.run(build_record_wheres(ADMIN, q="bag\x00ru"))
    assert wheres["artisans"]["OR"][0]["name"]["contains"] == "bagru"



def test_an_unknown_status_is_a_422_that_names_the_values_rather_than_a_500():
    from app.services.records import RECORD_STATUSES, enum_filter_or_422

    assert enum_filter_or_422("APPROVED", RECORD_STATUSES) == "APPROVED"
    for bad in ("draft", "in_progress", "ALL", "NOPE", ""):
        with pytest.raises(HTTPException) as raised:
            enum_filter_or_422(bad, RECORD_STATUSES)
        assert raised.value.status_code == 422
        assert "APPROVED" in raised.value.detail
        assert "status must be one of" in raised.value.detail


def test_the_field_name_travels_into_the_message():
    from app.services.records import MEDIA_TYPES, enum_filter_or_422

    with pytest.raises(HTTPException) as raised:
        enum_filter_or_422("image", MEDIA_TYPES, field="mediaType")
    assert raised.value.detail.startswith("mediaType must be one of")


MIXED_VOCABULARY = frozenset({"ADMIN", "DESIGNER", "MASTER_ADMIN", "default"})


def test_the_list_filter_accepts_both_spellings_clients_use():
    assert enum_filter_list_or_422(["ADMIN", "DESIGNER"], MIXED_VOCABULARY, field="roles") == {
        "ADMIN",
        "DESIGNER",
    }
    assert enum_filter_list_or_422(["ADMIN,DESIGNER"], MIXED_VOCABULARY, field="roles") == {
        "ADMIN",
        "DESIGNER",
    }
    assert enum_filter_list_or_422(
        ["ADMIN", "DESIGNER,default"], MIXED_VOCABULARY, field="roles"
    ) == {"ADMIN", "DESIGNER", "default"}


def test_absent_empty_and_all_blank_are_all_do_not_filter():
    for absent in (None, [], [""], ["   "], [","], [",,"], ["", " , "]):
        assert enum_filter_list_or_422(absent, MIXED_VOCABULARY, field="roles") is None, absent

    for raw in (None, [], [",,"], ["ADMIN"], ["ADMIN,ADMIN"], ["default"]):
        resolved = enum_filter_list_or_422(raw, MIXED_VOCABULARY, field="roles")
        assert resolved is None or resolved, raw


def test_an_unknown_token_is_a_422_that_names_the_valid_values():
    with pytest.raises(HTTPException) as raised:
        enum_filter_list_or_422(["ADMIN", "DESIGNR"], MIXED_VOCABULARY, field="roles")
    assert raised.value.status_code == 422
    assert "DESIGNR" in raised.value.detail
    assert "roles must be one of" in raised.value.detail
    for member in MIXED_VOCABULARY:
        assert member in raised.value.detail, member

    assert "Unknown roles value: DESIGNR." in raised.value.detail

    with pytest.raises(HTTPException) as raised:
        enum_filter_list_or_422(["ZZZ,AAA"], MIXED_VOCABULARY, field="roles")
    assert "Unknown roles values: AAA, ZZZ." in raised.value.detail


def test_the_field_name_travels_into_the_plural_message_too():
    from app.services.records import RECORD_STATUSES

    with pytest.raises(HTTPException) as raised:
        enum_filter_list_or_422(["ALL"], RECORD_STATUSES, field="status")
    assert raised.value.detail.startswith("Unknown status value: ALL.")
    assert "status must be one of" in raised.value.detail


def test_a_token_repeated_across_the_two_spellings_narrows_once():
    assert enum_filter_list_or_422(["ADMIN", "ADMIN,admin"], MIXED_VOCABULARY, field="roles") == {
        "ADMIN"
    }
    assert enum_filter_list_or_422(
        ["ADMIN,DESIGNER", "DESIGNER,ADMIN"], MIXED_VOCABULARY, field="roles"
    ) == {"ADMIN", "DESIGNER"}


def test_surrounding_whitespace_does_not_make_a_second_token():
    assert enum_filter_list_or_422([" ADMIN "], MIXED_VOCABULARY, field="roles") == {"ADMIN"}
    assert enum_filter_list_or_422(["ADMIN, DESIGNER"], MIXED_VOCABULARY, field="roles") == {
        "ADMIN",
        "DESIGNER",
    }
    assert enum_filter_list_or_422(["ADMIN", " ADMIN"], MIXED_VOCABULARY, field="roles") == {
        "ADMIN"
    }
    assert enum_filter_list_or_422(["  MASTER_ADMIN  "], MIXED_VOCABULARY, field="roles") == {
        "MASTER_ADMIN"
    }
    assert enum_filter_list_or_422(
        [" National Institute of Design "], frozenset({"National Institute of Design"}),
        field="institutions",
    ) == {"National Institute of Design"}


def test_the_case_handling_is_resolve_types_case_handling():
    types = frozenset(RECORD_TYPES)
    for raw in (
        ["artisans", "media"],
        ["artisans,media"],
        ["ARTISANS"],
        ["Artisans", "MEDIA"],
        [" artisans , MEDIA "],
        ["artisans", "artisans"],
        ["artisans,media,tools,products,workshops"],
    ):
        assert enum_filter_list_or_422(raw, types, field="types") == resolve_types(raw), raw

    for bad in (["artisan"], ["artisans", "artisan"]):
        with pytest.raises(HTTPException):
            resolve_types(bad)
        with pytest.raises(HTTPException):
            enum_filter_list_or_422(bad, types, field="types")

    for absent in (None, [], ["   "]):
        assert resolve_types(absent) == set(RECORD_TYPES)
        assert enum_filter_list_or_422(absent, types, field="types") is None


def test_the_fold_returns_the_vocabularys_spelling_and_never_the_clients():
    from app.core.deps import ROLE_RANK

    vocabulary = frozenset(ROLE_RANK) | {"default"}
    assert enum_filter_list_or_422(["admin"], vocabulary, field="roles") == {"ADMIN"}
    assert enum_filter_list_or_422(["DEFAULT"], vocabulary, field="roles") == {"default"}
    assert enum_filter_list_or_422(["Master_Admin"], vocabulary, field="roles") == {"MASTER_ADMIN"}

    for member in vocabulary:
        for spelling in (member, member.lower(), member.upper(), f"  {member}  "):
            resolved = enum_filter_list_or_422([spelling], vocabulary, field="roles")
            assert resolved == {member}, spelling
            assert resolved <= vocabulary, spelling


def test_the_plural_accepts_a_lower_case_enum_value_where_the_singular_refuses_it():
    from app.services.records import RECORD_STATUSES, enum_filter_or_422

    with pytest.raises(HTTPException):
        enum_filter_or_422("draft", RECORD_STATUSES)
    assert enum_filter_list_or_422(["draft"], RECORD_STATUSES, field="status") == {"DRAFT"}
    assert enum_filter_list_or_422(["draft,PENDING"], RECORD_STATUSES, field="status") == {
        "DRAFT",
        "PENDING",
    }


def test_a_vocabulary_that_folds_onto_itself_is_a_server_error_not_a_silent_winner():
    from app.core.deps import ROLE_RANK

    broken = frozenset({"DEFAULT", "default", "ADMIN"})
    with pytest.raises(ValueError, match="differ only by case"):
        enum_filter_list_or_422(["ADMIN"], broken, field="roles")

    assert enum_filter_list_or_422(
        ["default"], frozenset(ROLE_RANK) | {"default"}, field="roles"
    ) == {"default"}


def test_a_nul_byte_never_reaches_an_equality_filter():
    from app.services.records import plain

    assert plain("\x00") == ""
    assert plain("Odi\x00sha") == "Odisha"
    assert plain("\x01\x02Bargarh\x1f") == "Bargarh"
    assert plain("a\tb\nc\rd") == "a\tb\nc\rd"
    assert plain("ସମ୍ବଲପୁରୀ") == "ସମ୍ବଲପୁରୀ", "a stripper that ate real text would be worse"


def test_no_route_still_puts_a_raw_filter_string_into_an_enum_column():
    import pathlib
    import re

    routes = pathlib.Path(__file__).resolve().parents[1] / "app" / "api" / "routes"
    raw = re.compile(r'where\[\s*"(status|mediaType)"\s*\]\s*=\s*(statusFilter|mediaType)\b')
    guards = ("assert_status_value", "_status_or_422", "enum_filter_or_422")

    offenders = []
    for path in sorted(routes.glob("*.py")):
        lines = path.read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            if not raw.search(line):
                continue
            start = i
            while start > 0 and not lines[start].lstrip().startswith(("def ", "async def ")):
                start -= 1
            scope = "\n".join(lines[start:i])
            if not any(guard in scope for guard in guards):
                offenders.append(f"{path.name}:{i + 1}")

    assert not offenders, (
        "these assign an unvalidated query string to an enum column, which is a 500:\n"
        + "\n".join(offenders)
    )



def test_a_wildcard_typed_into_a_search_box_is_matched_rather_than_honoured():
    from app.services.records import contains

    assert contains("_")["contains"] == r"\_"
    assert contains("%")["contains"] == r"\%"
    assert contains("_designer")["contains"] == r"\_designer"
    assert contains("first_last@org")["contains"] == r"first\_last@org"
    assert contains("100%")["contains"] == r"100\%"

    assert contains("bagru")["contains"] == "bagru"
    assert contains("Ramesh Kumar")["contains"] == "Ramesh Kumar"


def test_the_backslash_is_escaped_first_or_the_escape_escapes_itself():
    from app.services.records import contains

    assert contains("\\")["contains"] == r"\\"
    assert contains(r"\%")["contains"] == r"\\\%"
    backslash = chr(92)
    assert contains(backslash + "%")["contains"] == backslash * 2 + backslash + "%"


def test_the_control_byte_strip_still_runs_and_composes_with_the_escape():
    from app.services.records import contains

    assert contains("bag\x00ru")["contains"] == "bagru"
    assert contains("first_last\x00@org")["contains"] == r"first\_last@org"
    assert contains("two\nlines")["contains"] == "two\nlines"


def test_an_equality_filter_is_deliberately_NOT_escaped():
    from app.services.records import plain

    assert plain("A_P") == "A_P"
    assert plain("100%") == "100%"
    assert plain("back" + chr(92) + "slash") == "back" + chr(92) + "slash"
    assert plain("A\x00P") == "AP"


def test_the_search_route_carries_the_escape_all_the_way_into_the_where():
    wheres = asyncio.run(build_record_wheres(ADMIN, q="first_last@org"))
    assert wheres["artisans"]["OR"][0]["name"]["contains"] == r"first\_last@org"


def test_no_route_still_hand_rolls_a_contains_filter():
    import ast
    import pathlib

    backend = pathlib.Path(__file__).resolve().parents[1] / "app"

    def _hand_rolled_lines(source: str) -> list[int]:
        found: set[int] = set()
        for node in ast.walk(ast.parse(source)):
            if isinstance(node, ast.Dict):
                if any(isinstance(k, ast.Constant) and k.value == "contains" for k in node.keys):
                    found.add(node.lineno)
            elif isinstance(node, ast.Subscript):
                index = node.slice
                if isinstance(index, ast.Constant) and index.value == "contains":
                    found.add(node.lineno)
            elif isinstance(node, ast.Call):
                func = node.func
                name = func.id if isinstance(func, ast.Name) else None
                if name == "dict" and any(kw.arg == "contains" for kw in node.keywords):
                    found.add(node.lineno)
        return sorted(found)

    ALLOWED: set[str] = set()

    offenders = []
    for path in sorted([*(backend / "api" / "routes").glob("*.py"), *(backend / "services").glob("*.py")]):
        if path.name == "records.py" and path.parent.name == "services":
            continue
        if f"{path.parent.name}/{path.name}" in ALLOWED:
            continue
        for lineno in _hand_rolled_lines(path.read_text(encoding="utf-8")):
            offenders.append(f"{path.parent.name}/{path.name}:{lineno}")

    assert not offenders, (
        "these compose a Prisma `contains` filter by hand, so the NUL strip and the LIKE escape in "
        "`records.contains` never run for them — a pasted control byte is a 500 and a typed `_` "
        "silently widens the result:\n" + "\n".join(offenders)
    )
