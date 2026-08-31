"""The design-workshop research flattener — the rules that decide what a research table SAYS.

Every assertion here is about a decision that is invisible on screen and expensive to get wrong:
whether an unanswered stage is a blank row or no row, whether a retired question keeps its column,
whether a row written by a newer client is dropped or counted. None of them needs a database, which
is why ``services/design_workshop_data.py`` is pure — see that module's own header for the split.
"""

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


# --------------------------------------------------------------------------------------
# The table descriptors
# --------------------------------------------------------------------------------------


def test_every_entity_in_the_registry_becomes_a_table():
    """Derived from ``stages()``, never a hand-written list.

    The failure this prevents is the one this repository keeps paying for: a register written down
    twice goes stale, and a data browser that silently omits a stage is indistinguishable from a
    stage nobody filled in. A stage or entity added to the registry has to appear here without
    anybody remembering.
    """
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
    """A retitled field must not rename a column in somebody's analysis script.

    This is the whole of "stable keys and units" — the phrase ``stage_schema``'s own module
    docstring uses about the research export it promised and nothing implemented. The label travels
    beside the key as the header, so the person and the script each get what they need.
    """
    setup = next(t for t in dwd.tables() if t.entity_key == "workshopSetup")
    kind = next(c for c in setup.columns if c.key == "workshopSetup.workshopKind")
    assert kind.label == "Type of workshop"
    assert kind.type == "ENUM"


def test_a_declared_unit_reaches_the_column():
    """A bare 42 in a length column is unreadable; "Length (cm)" is not.

    ``sketch.lengthCm`` declares ``unit="cm"``. The unit is carried rather than concatenated into
    the label, so an exporter may put it in a second header row, in the cell, or nowhere.
    """
    sketch = next(t for t in dwd.tables() if t.entity_key == "sketch")
    length = next(c for c in sketch.columns if c.key == "sketch.lengthCm")
    assert length.unit == "cm"


def test_deprecated_fields_get_no_column():
    """A deprecated field's stored values survive; its COLUMN does not.

    Nothing deletes a deprecated field's data, and a caller reading the raw ``data`` document still
    sees it. What a research table must not do is invite analysis of a series that stopped being
    collected on a date the table cannot state.
    """
    entity = stage_by_number(11).entity("sketch")
    assert entity is not None
    live = {c.key for c in dwd.entity_columns(entity)}
    for spec in entity.fields:
        if spec.deprecated:
            assert f"sketch.{spec.key}" not in live


def test_the_two_fields_added_on_2026_08_30_are_columns():
    """The owner's stage-4 gallery and stage-11 flag reach the research surface with no extra work.

    That is the point of deriving the tables from the registry: a field declared once is browsable,
    exportable and countable without a second declaration anywhere.
    """
    cluster = next(t for t in dwd.tables() if t.entity_key == "clusterBackground")
    assert "clusterBackground.lostCraftPhotos" in {c.key for c in cluster.columns}
    sketch = next(t for t in dwd.tables() if t.entity_key == "sketch")
    assert "sketch.isTentative" in {c.key for c in sketch.columns}


# --------------------------------------------------------------------------------------
# The cells
# --------------------------------------------------------------------------------------


def test_a_media_field_is_a_count_and_its_ids_never_a_url():
    """Resolving a media id to a URL is an ENTITLEMENT decision, made at the encoder.

    ``MediaFile.url`` and ``objectKey`` are gated server-side precisely so a caller who may not have
    the bytes never receives a link to them. A formatter that emitted one would hand every reader of
    a research export a download for material whose consent state it never asked about.
    """
    entity = stage_by_number(4).entity("clusterBackground")
    assert entity is not None
    spec = next(f for f in entity.fields if f.key == "lostCraftPhotos")
    assert dwd.cell(spec, ["m1", "m2", "m3"]) == "3: m1, m2, m3"
    assert dwd.cell(spec, []) == ""
    assert dwd.cell(spec, None) == ""
    assert "http" not in dwd.cell(spec, ["m1"])


def test_an_unsynced_local_media_reference_is_kept_and_counted():
    """A ``dwlocal:`` id is a blob on somebody's phone with no ``MediaFile`` row behind it.

    Dropping it would report 3 photographs where 5 are attached — an under-count in the direction
    that looks complete, which is the worst direction for a corpus figure to be wrong in.
    """
    entity = stage_by_number(4).entity("clusterBackground")
    assert entity is not None
    spec = next(f for f in entity.fields if f.key == "lostCraftPhotos")
    assert dwd.cell(spec, ["m1", "dwlocal:abc"]) == "2: m1, dwlocal:abc"


def test_an_enum_cell_is_the_label_and_not_the_token():
    """A research table that says ``DESIGN_PROTOTYPE_DEVELOPMENT`` is a table somebody has to decode.

    This is ``report_builder.format_value`` doing its job — reused rather than re-implemented, which
    is why an enum, a rich-text document and a boolean all come out right without this module
    knowing how any of them are stored.
    """
    entity = stage_by_number(1).entity("workshopSetup")
    assert entity is not None
    spec = next(f for f in entity.fields if f.key == "workshopKind")
    assert dwd.cell(spec, "DESIGN_PROTOTYPE_DEVELOPMENT") == "Design & Prototype Development"


# --------------------------------------------------------------------------------------
# The rows
# --------------------------------------------------------------------------------------


def test_every_row_carries_the_workshop_identity():
    """A sketch row that says only "Sketch 3, indigo" is useless in an export of four hundred
    workshops."""
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
    """"Not answered" and "answered with empty strings" are different facts.

    An export that renders them identically cannot be used to count coverage, which is most of what
    a research export is for.
    """
    entity = stage_by_number(11).entity("sketch")
    assert entity is not None
    assert dwd.entity_rows(entity, [], dwd.workshop_identity(_workshop())) == []


def test_rows_are_used_in_the_order_given_and_never_re_sorted():
    """``ordinal`` is the single ordering input in this product and the caller's query applies it.

    Re-sorting here would be a second opinion about an order a designer arranged by hand — and on
    stage 11 that order is now also what tentative-first partitioning is layered on.
    """
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
    """A handset one release ahead is a supported caller — ``validate_entry``'s own docstring says so.

    Refusing the whole export because one row is from the future would lose four hundred workshops
    over one. Dropping it quietly would under-report a corpus while looking complete. So it is
    counted, and the caller is expected to print the count.
    """
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


# --------------------------------------------------------------------------------------
# The designer's own questions
# --------------------------------------------------------------------------------------


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
    """Two designers write "Dye bath?" and mean different questions.

    And a designer who rewords their own question has not created a new one. The id is the only
    stable name a custom column has.
    """
    definition = _Definition([_CustomSection([_CustomField(id="f_1", label="Dye bath?")])])
    assert dwd.custom_columns(definition) == (
        dwd.CustomColumn(key="_custom.f_1", label="Dye bath?"),
    )


def test_a_RETIRED_custom_field_keeps_its_column_and_is_marked():
    """The opposite of the registry rule directly above, and the asymmetry is the point.

    A registry field is deprecated centrally, so its column would invite analysis of a series that
    stopped for everybody. A custom field is retired by ONE designer in ONE workshop: the answers
    are theirs, they are the only record of a question nobody else asked, and dropping the column
    would delete the only trace of it from the research surface.
    """
    definition = _Definition([_CustomSection([_CustomField(id="f_1", label="Dye bath?", retired=True)])])
    assert dwd.custom_columns(definition) == (
        dwd.CustomColumn(key="_custom.f_1", label="Dye bath? (retired)"),
    )


def test_custom_answers_without_their_definition_are_counted_as_unreadable():
    """The definition is the only thing that can NAME these columns.

    Emitting them under opaque ids would produce a table whose headers are cuids — worse than
    saying, in a number the caller can print, that N rows could not be described.
    """
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
    # And it still carries the workshop identity, exactly as a registry row does.
    assert grouped[dwd.CUSTOM_ENTITY_KEY][0]["workshop.craftName"] == "Ikat"


def test_a_custom_boolean_and_a_custom_list_are_readable_in_a_cell():
    """``V1_CUSTOM_TYPES`` excludes MEDIA, REF, GEO and RICH_TEXT, so everything reaching here is a
    scalar or a list of them — which is why this path needs no ``FieldSpec``."""
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
