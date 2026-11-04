from __future__ import annotations

from dataclasses import dataclass
from types import SimpleNamespace
from typing import Any

from app.services import design_workshop_data as dwd
from app.services.stage_schema import stage_by_number


def _entry(entity_key: str, data: dict[str, Any], *, entry_id: str = "e1", ordinal: int = 0) -> Any:
    return SimpleNamespace(id=entry_id, entityKey=entity_key, data=data, ordinal=ordinal)


def _workshop() -> Any:
    return SimpleNamespace(
        id="dw_1",
        title="Ikat in Barpali",
        workshopCode="DCH/2026/017",
        workshopKind="DESIGN_PROTOTYPE_DEVELOPMENT",
        scheme="NHDP",
        craftName="Ikat",
        clusterName="Barpali",
        state="Odisha",
        district="Bargarh",
        venue="Weavers' Service Centre",
        startDate=None,
        endDate=None,
        designerName="Rekha Sahu",
        status="IN_PROGRESS",
    )



def test_every_entity_in_the_registry_becomes_a_table():
    from app.services.stage_schema import stages

    expected = {entity.key for stage in stages() for entity in stage.entities}
    assert {table.entity_key for table in dwd.tables()} == expected


def test_a_table_knows_which_stage_it_came_from_and_whether_it_repeats():
    sketch = next(t for t in dwd.tables() if t.entity_key == "sketch")
    assert sketch.stage_number == 11
    assert sketch.collection is True

    setup = next(t for t in dwd.tables() if t.entity_key == "workshopSetup")
    assert setup.stage_number == 1
    assert setup.collection is False


def test_column_keys_are_the_registry_keys_and_never_the_labels():
    setup = next(t for t in dwd.tables() if t.entity_key == "workshopSetup")
    kind = next(c for c in setup.columns if c.key == "workshopSetup.workshopKind")
    assert kind.label == "Type of workshop"
    assert kind.type == "ENUM"


def test_a_declared_unit_reaches_the_column():
    sketch = next(t for t in dwd.tables() if t.entity_key == "sketch")
    length = next(c for c in sketch.columns if c.key == "sketch.lengthCm")
    assert length.unit == "cm"


def test_deprecated_fields_get_no_column():
    entity = stage_by_number(11).entity("sketch")
    assert entity is not None
    live = {c.key for c in dwd.entity_columns(entity)}
    for spec in entity.fields:
        if spec.deprecated:
            assert f"sketch.{spec.key}" not in live


def test_the_two_fields_added_on_2026_08_30_are_columns():
    cluster = next(t for t in dwd.tables() if t.entity_key == "clusterBackground")
    assert "clusterBackground.lostCraftPhotos" in {c.key for c in cluster.columns}
    sketch = next(t for t in dwd.tables() if t.entity_key == "sketch")
    assert "sketch.isTentative" in {c.key for c in sketch.columns}



def test_a_media_field_is_a_count_and_its_ids_never_a_url():
    entity = stage_by_number(4).entity("clusterBackground")
    assert entity is not None
    spec = next(f for f in entity.fields if f.key == "lostCraftPhotos")
    assert dwd.cell(spec, ["m1", "m2", "m3"]) == "3: m1, m2, m3"
    assert dwd.cell(spec, []) == ""
    assert dwd.cell(spec, None) == ""
    assert "http" not in dwd.cell(spec, ["m1"])


def test_an_unsynced_local_media_reference_is_kept_and_counted():
    entity = stage_by_number(4).entity("clusterBackground")
    assert entity is not None
    spec = next(f for f in entity.fields if f.key == "lostCraftPhotos")
    assert dwd.cell(spec, ["m1", "dwlocal:abc"]) == "2: m1, dwlocal:abc"


def test_an_enum_cell_is_the_label_and_not_the_token():
    entity = stage_by_number(1).entity("workshopSetup")
    assert entity is not None
    spec = next(f for f in entity.fields if f.key == "workshopKind")
    assert dwd.cell(spec, "DESIGN_PROTOTYPE_DEVELOPMENT") == "Design & Prototype Development"



def test_every_row_carries_the_workshop_identity():
    entity = stage_by_number(11).entity("sketch")
    assert entity is not None
    rows = dwd.entity_rows(
        entity,
        [_entry("sketch", {"sketchNo": "3", "name": "Border repeat"})],
        dwd.workshop_identity(_workshop()),
    )
    assert len(rows) == 1
    assert rows[0]["workshop.id"] == "dw_1"
    assert rows[0]["workshop.craftName"] == "Ikat"
    assert rows[0]["workshop.workshopKind"] == "DESIGN_PROTOTYPE_DEVELOPMENT"
    assert rows[0]["sketch.name"] == "Border repeat"


def test_an_unanswered_entity_yields_NO_ROW_rather_than_a_row_of_blanks():
    entity = stage_by_number(11).entity("sketch")
    assert entity is not None
    assert dwd.entity_rows(entity, [], dwd.workshop_identity(_workshop())) == []


def test_rows_are_used_in_the_order_given_and_never_re_sorted():
    entity = stage_by_number(11).entity("sketch")
    assert entity is not None
    rows = dwd.entity_rows(
        entity,
        [
            _entry("sketch", {"name": "second"}, entry_id="b", ordinal=1),
            _entry("sketch", {"name": "first"}, entry_id="a", ordinal=0),
        ],
        dwd.workshop_identity(_workshop()),
    )
    assert [r["sketch.name"] for r in rows] == ["second", "first"]


def test_a_row_written_against_a_newer_registry_is_COUNTED_not_dropped():
    grouped, unknown = dwd.flatten(
        _workshop(),
        [
            _entry("sketch", {"name": "known"}),
            _entry("somethingFromTheFuture", {"x": 1}, entry_id="f1"),
            _entry("somethingFromTheFuture", {"x": 2}, entry_id="f2"),
        ],
    )
    assert "sketch" in grouped
    assert unknown == (dwd.UnknownEntity(entity_key="somethingFromTheFuture", rows=2),)



@dataclass
class _CustomField:
    id: str
    label: str
    retired: bool = False


@dataclass
class _CustomSection:
    fields: list[_CustomField]


@dataclass
class _Definition:
    sections: list[_CustomSection]


def test_a_custom_column_is_keyed_by_id_and_never_by_the_prompt():
    definition = _Definition([_CustomSection([_CustomField(id="f_1", label="Dye bath?")])])
    assert dwd.custom_columns(definition) == (
        dwd.CustomColumn(key="_custom.f_1", label="Dye bath?"),
    )


def test_a_RETIRED_custom_field_keeps_its_column_and_is_marked():
    definition = _Definition([_CustomSection([_CustomField(id="f_1", label="Dye bath?", retired=True)])])
    assert dwd.custom_columns(definition) == (
        dwd.CustomColumn(key="_custom.f_1", label="Dye bath? (retired)"),
    )


def test_custom_answers_without_their_definition_are_counted_as_unreadable():
    grouped, unknown = dwd.flatten(_workshop(), [_entry(dwd.CUSTOM_ENTITY_KEY, {"f_1": "Indigo"})])
    assert dwd.CUSTOM_ENTITY_KEY not in grouped
    assert unknown == (dwd.UnknownEntity(entity_key=dwd.CUSTOM_ENTITY_KEY, rows=1),)


def test_custom_answers_with_their_definition_are_flattened():
    definition = _Definition([_CustomSection([_CustomField(id="f_1", label="Dye bath?")])])
    grouped, unknown = dwd.flatten(
        _workshop(),
        [_entry(dwd.CUSTOM_ENTITY_KEY, {"f_1": "Indigo, unbleached"})],
        definition=definition,
    )
    assert unknown == ()
    assert grouped[dwd.CUSTOM_ENTITY_KEY][0]["_custom.f_1"] == "Indigo, unbleached"
    assert grouped[dwd.CUSTOM_ENTITY_KEY][0]["workshop.craftName"] == "Ikat"


def test_a_custom_boolean_and_a_custom_list_are_readable_in_a_cell():
    definition = _Definition(
        [_CustomSection([_CustomField(id="b", label="Dyed?"), _CustomField(id="l", label="Colours")])]
    )
    grouped, _ = dwd.flatten(
        _workshop(),
        [_entry(dwd.CUSTOM_ENTITY_KEY, {"b": True, "l": ["indigo", "madder"]})],
        definition=definition,
    )
    row = grouped[dwd.CUSTOM_ENTITY_KEY][0]
    assert row["_custom.b"] == "Yes"
    assert row["_custom.l"] == "indigo, madder"
