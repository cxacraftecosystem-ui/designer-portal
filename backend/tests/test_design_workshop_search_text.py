from __future__ import annotations

import ast
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services import design_workshop_data as dwd
from app.services.records import _IDENTITY_KEYS
from app.services.stage_schema import EntitySpec, FieldType, stages

APP = Path(__file__).resolve().parents[1] / "app"


def _entity(key: str) -> EntitySpec:
    found = dwd.entity_by_key(key)
    assert found is not None, f"{key} is not in this build's registry"
    return found[1]



def test_a_typed_answer_is_findable_by_the_words_the_designer_typed():
    text = dwd.entry_search_text(
        _entity("workshopSetup"),
        {"workshopTitle": "Indigo dyeing at Barpali", "venue": "Weavers' Service Centre"},
    )
    assert "Indigo dyeing at Barpali" in text
    assert "Weavers' Service Centre" in text


def test_an_enum_is_stored_as_its_LABEL_and_not_as_the_token():
    text = dwd.entry_search_text(
        _entity("workshopSetup"), {"workshopKind": "DESIGN_PROTOTYPE_DEVELOPMENT"}
    )
    assert "DESIGN_PROTOTYPE_DEVELOPMENT" not in text
    assert text
    label = next(
        spec for spec in _entity("workshopSetup").fields if spec.key == "workshopKind"
    )
    from app.services.stage_schema import enum_label

    assert enum_label(label.enum, "DESIGN_PROTOTYPE_DEVELOPMENT") in text


def test_rich_text_is_flattened_to_the_words_inside_it():
    entity = _entity("introduction")
    spec = next(f for f in entity.fields if f.type is FieldType.RICH_TEXT)
    doc = {"blocks": [{"kind": "PARAGRAPH", "spans": [{"text": "The cluster dyes with indigo"}]}]}
    text = dwd.entry_search_text(entity, {spec.key: doc})
    assert text == "The cluster dyes with indigo"


def test_media_references_never_reach_the_column():
    text = dwd.entry_search_text(
        _entity("workshopSetup"),
        {"workshopTitle": "Ikat", "coverPhoto": "cmedia123abc", "sanctionDocument": "dwlocal:xyz"},
    )
    assert text == "Ikat"


def test_numbers_and_dates_are_left_out_because_the_rendering_is_not_what_a_person_types():
    text = dwd.entry_search_text(
        _entity("workshopSetup"),
        {"workshopTitle": "Ikat", "startDate": "2026-02-10", "durationDays": 14},
    )
    assert text == "Ikat"


def test_the_column_is_deduplicated_and_deterministic():
    data = {"workshopTitle": "Ikat", "craftName": "Ikat", "clusterName": "Barpali"}
    first = dwd.entry_search_text(_entity("workshopSetup"), data)
    assert first == dwd.entry_search_text(_entity("workshopSetup"), data)
    assert first.count("Ikat") == 1


def test_a_row_with_nothing_text_shaped_renders_the_empty_string():
    assert dwd.entry_search_text(_entity("workshopSetup"), {"durationDays": 14}) == ""
    assert dwd.entry_search_text(_entity("workshopSetup"), None) == ""
    assert dwd.entry_search_text(_entity("workshopSetup"), "not a dict") == ""



def test_a_participants_contact_details_are_never_searchable():
    participant = _entity("participant")
    text = dwd.entry_search_text(
        participant,
        {
            "name": "Rekha Sahu",
            "phone": "9876543210",
            "email": "rekha@example.org",
            "aadhaarNumber": "XXXX XXXX 9012",
            "artisanCardNo": "XXXX XXXX 3456",
        },
    )
    assert "Rekha Sahu" in text, "the NAME is what a researcher searches for and must stay"
    for secret in ("9876543210", "rekha@example.org", "9012", "3456"):
        assert secret not in text


def test_every_identity_key_records_knows_about_is_excluded_here():
    assert set(_IDENTITY_KEYS) <= dwd.UNSEARCHABLE_FIELD_KEYS


def test_the_pehchan_carry_is_excluded_under_the_key_the_registry_actually_uses():
    assert "artisanCardNo" in dwd.UNSEARCHABLE_FIELD_KEYS
    assert "artisanCardNo" not in set(_IDENTITY_KEYS)


def test_no_contact_typed_field_anywhere_in_the_registry_is_searchable():
    contact = {FieldType.PHONE, FieldType.EMAIL}
    for stage in stages():
        for entity in stage.entities:
            for spec in entity.fields:
                if spec.type in contact:
                    assert not dwd._searchable(spec), f"{entity.key}.{spec.key}"



def _custom_spec(**kwargs: Any) -> Any:
    base = {
        "key": "q1",
        "type": FieldType.TEXT,
        "option_label": None,
    }
    base.update(kwargs)
    return SimpleNamespace(**base)


def test_a_designers_own_question_is_searchable():
    specs = [_custom_spec(key="dyeBath"), _custom_spec(key="looms", type=FieldType.INT)]
    text = dwd.custom_search_text(specs, {"dyeBath": "Indigo, three dips", "looms": 12})
    assert text == "Indigo, three dips"


def test_a_custom_choice_is_stored_as_the_designers_own_label():
    spec = _custom_spec(
        key="finish",
        type=FieldType.ENUM,
        option_label=lambda value: {"NAT": "Natural dye"}.get(str(value), str(value)),
    )
    assert dwd.custom_search_text([spec], {"finish": "NAT"}) == "Natural dye"


def test_a_custom_multi_choice_renders_every_option_it_holds():
    spec = _custom_spec(
        key="dyes",
        type=FieldType.MULTI_ENUM,
        option_label=lambda value: {"IND": "Indigo", "MAD": "Madder"}.get(str(value), str(value)),
    )
    text = dwd.custom_search_text([spec], {"dyes": ["IND", "MAD"]})
    assert "Indigo" in text and "Madder" in text


def test_a_custom_field_named_like_an_identity_number_is_still_excluded():
    assert dwd.custom_search_text([_custom_spec(key="phone")], {"phone": "9876543210"}) == ""



def test_a_stage_is_named_with_its_number_first():
    first = stages()[0]
    assert dwd.stage_label(first.key) == f"Stage {first.number}: {first.title}"


def test_an_unknown_stage_key_is_returned_as_itself():
    assert dwd.stage_label("STAGE_FROM_THE_FUTURE") == "STAGE_FROM_THE_FUTURE"


_KNOWN_STAGE_ENTRY_WRITERS = frozenset(
    {
        "services/design_workshops.py:seed_designer_prefill",
        "services/design_workshops.py:save_stage.write_everything",
        "api/routes/design_workshop_copy.py:confirm_copied_fields",
    }
)

_WRITE_METHODS = frozenset({"create", "create_many", "update", "update_many", "upsert", "delete_many"})


def _stage_entry_write_sites() -> set[str]:
    def walk(node: ast.AST, scope: list[str], rel: str, sites: set[str]) -> None:
        for child in ast.iter_child_nodes(node):
            if isinstance(child, ast.FunctionDef | ast.AsyncFunctionDef):
                walk(child, [*scope, child.name], rel, sites)
                continue
            if (
                isinstance(child, ast.Call)
                and isinstance(child.func, ast.Attribute)
                and child.func.attr in _WRITE_METHODS
                and isinstance(child.func.value, ast.Attribute)
                and child.func.value.attr == "dwstageentry"
            ):
                sites.add(f"{rel}:{'.'.join(scope) or '<module>'}")
            walk(child, scope, rel, sites)

    sites: set[str] = set()
    for path in sorted(APP.rglob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        walk(tree, [], path.relative_to(APP).as_posix(), sites)
    return sites


def test_no_third_writer_of_a_stage_entry_has_appeared():
    found = _stage_entry_write_sites()
    assert found == _KNOWN_STAGE_ENTRY_WRITERS, (
        "A writer of DwStageEntry has been added or moved. Every writer that touches `data` must "
        "write `searchText` in the same statement - see the register above this test.\n"
        f"  added:   {sorted(found - _KNOWN_STAGE_ENTRY_WRITERS)}\n"
        f"  missing: {sorted(_KNOWN_STAGE_ENTRY_WRITERS - found)}"
    )


def test_both_clients_cap_the_named_stages_at_the_same_number():
    root = APP.parents[1]
    web = (root / "frontend" / "app" / "(protected)" / "search" / "page.tsx").read_text(
        encoding="utf-8"
    )
    android = (
        root
        / "android"
        / "app"
        / "src"
        / "main"
        / "java"
        / "com"
        / "designprototype"
        / "workshop"
        / "ui"
        / "SearchScreen.kt"
    ).read_text(encoding="utf-8")
    assert "const MATCHED_IN_SHOWN = 3;" in web
    assert "internal const val MATCHED_IN_SHOWN = 3" in android


def test_both_writers_name_the_column_in_their_own_source():
    source = (APP / "services" / "design_workshops.py").read_text(encoding="utf-8")
    tree = ast.parse(source)
    for name in ("seed_designer_prefill", "save_stage"):
        node = next(
            n
            for n in ast.walk(tree)
            if isinstance(n, ast.FunctionDef | ast.AsyncFunctionDef) and n.name == name
        )
        body = ast.get_source_segment(source, node) or ""
        assert '"searchText"' in body, f"{name} writes `data` and no longer writes `searchText`"
