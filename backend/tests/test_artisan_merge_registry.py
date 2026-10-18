"""Every table that can name an artisan is accounted for by the merge.

Parses ``prisma/schema.prisma`` (no database, no Prisma import of the client) and fails when a
field pointing at ``Artisan``, or a table carrying ``recordType``/``recordId`` (or the media tag's
``linkedRecordType``/``linkedRecordId``), is in neither ``artisan_merge.MERGE_REPOINT``,
``SPECIAL_REPOINT`` nor ``NOT_REPOINTED``. A table added later (an in-app notification naming a
record, say) fails here until somebody decides what a merge does to it.
"""

from __future__ import annotations

import pathlib
import re

from app.services import artisan_merge

SCHEMA = (pathlib.Path(__file__).resolve().parents[1] / "prisma" / "schema.prisma").read_text(encoding="utf-8")


def _models() -> dict[str, list[str]]:
    models: dict[str, list[str]] = {}
    for match in re.finditer(r"^model (\w+) \{(.*?)^\}", SCHEMA, re.MULTILINE | re.DOTALL):
        lines = [line.split("//")[0].strip() for line in match.group(2).splitlines()]
        models[match.group(1)] = [line for line in lines if line and not line.startswith("@@")]
    return models


def _covered() -> set[str]:
    covered = {f"{spec.table}.{spec.column}" for spec in artisan_merge.MERGE_REPOINT}
    covered |= set(artisan_merge.SPECIAL_REPOINT)
    return covered


def _exempt(model: str) -> bool:
    return model in artisan_merge.NOT_REPOINTED


def test_every_foreign_key_to_artisan_is_in_the_registry():
    missing = []
    for model, lines in _models().items():
        if model == "Artisan":
            continue
        for line in lines:
            relation = re.match(r"(\w+)\s+Artisan\??\s+@relation\((.*)\)", line)
            if not relation:
                continue
            fields = re.search(r"fields:\s*\[(\w+)\]", relation.group(2))
            if not fields:
                continue
            key = f"{model}.{fields.group(1)}"
            if key not in _covered() and not _exempt(model):
                missing.append(key)
    assert not missing, f"not handled by the artisan merge: {missing}"


def test_every_polymorphic_record_table_is_in_the_registry():
    missing = []
    for model, lines in _models().items():
        names = {line.split()[0] for line in lines}
        for type_col, id_col in (("recordType", "recordId"), ("linkedRecordType", "linkedRecordId")):
            if type_col in names and id_col in names:
                key = f"{model}.{id_col}"
                if key not in _covered() and not _exempt(model):
                    missing.append(key)
    assert not missing, f"record-typed tables not handled by the artisan merge: {missing}"


def test_artisan_id_arrays_are_in_the_registry():
    missing = []
    for model, lines in _models().items():
        for line in lines:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "String[]" and "rtisan" in parts[0]:
                key = f"{model}.{parts[0]}"
                if key not in _covered() and not _exempt(model):
                    missing.append(key)
    assert not missing, f"artisan id arrays not handled by the artisan merge: {missing}"


def test_the_registry_names_real_tables_and_columns():
    models = _models()
    for spec in artisan_merge.MERGE_REPOINT:
        assert spec.table in models, spec.table
        names = {line.split()[0] for line in models[spec.table]}
        for column in (spec.column, *spec.key, *spec.unique_with) + ((spec.name_column,) if spec.name_column else ()):
            assert column in names, f"{spec.table}.{column}"
    for model in artisan_merge.NOT_REPOINTED:
        assert model in models, model
