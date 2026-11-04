from __future__ import annotations

from contextlib import contextmanager
from typing import Any

from app.services import stage_schema
from app.services.stage_schema import (
    FieldType,
    TextFormat,
    all_entities,
    registry_version,
    stages,
)


@contextmanager
def _swapped_attrs(spec: Any, **attrs: Any):
    before = {name: getattr(spec, name) for name in attrs}
    for name, value in attrs.items():
        object.__setattr__(spec, name, value)
    try:
        yield
    finally:
        for name, value in before.items():
            object.__setattr__(spec, name, value)


def _a_plain_text_field() -> Any:
    return next(
        f
        for _stage, entity in all_entities()
        for f in entity.fields
        if f.type is FieldType.TEXT and f.text_format is TextFormat.NONE
    )



def test_the_version_is_the_same_string_every_time():
    first = registry_version()
    assert registry_version() == first
    assert len(first) == 16


def test_the_memoised_answer_is_the_answer_a_cold_call_computes():
    warm = registry_version()
    before = stage_schema._VERSION_MEMO
    try:
        stage_schema._VERSION_MEMO = None
        assert registry_version() == warm
    finally:
        stage_schema._VERSION_MEMO = before


def test_a_repeat_call_does_not_re_digest(monkeypatch):
    calls: list[int] = []
    original = stage_schema._registry_digest

    def counted(fingerprint: Any) -> str:
        calls.append(1)
        return original(fingerprint)

    monkeypatch.setattr(stage_schema, "_registry_digest", counted)
    monkeypatch.setattr(stage_schema, "_VERSION_MEMO", None)
    first = registry_version()
    second = registry_version()
    third = registry_version()
    assert first == second == third
    assert len(calls) == 1, (
        f"the digest was recomputed {len(calls)} times for three calls; the memo is not being hit"
    )



def test_an_in_place_attribute_change_still_moves_the_version():
    baseline = registry_version()
    with _swapped_attrs(_a_plain_text_field(), text_format=TextFormat.PINCODE):
        assert registry_version() != baseline, (
            "a field gained a format in place and the memo answered with the old digest — which is "
            "exactly the stale-asset-reports-agreement failure the version string exists to prevent"
        )
    assert registry_version() == baseline, "the registry was restored and the digest did not follow"


def test_a_rebuilt_fields_tuple_still_moves_the_version():
    from dataclasses import replace as dataclass_replace

    baseline = registry_version()
    original = next(
        f for _stage, entity in all_entities() for f in entity.fields if f.derived_kind
    )
    holder = next(e for _stage, e in all_entities() if original in e.fields)
    before = holder.fields
    object.__setattr__(
        holder,
        "fields",
        tuple(
            dataclass_replace(original, derived_kind="") if f is original else f for f in before
        ),
    )
    try:
        assert registry_version() != baseline, (
            "a derivation was removed and the digest did not move; a client holding the old one "
            "would never be told to refetch, and the field simply stops computing on the handset"
        )
    finally:
        object.__setattr__(holder, "fields", before)
    assert registry_version() == baseline


def test_a_replaced_hydration_table_still_moves_the_version(monkeypatch):
    baseline = registry_version()
    original = stage_schema.REFERENCE_HYDRATION
    path = "processStep.processRef"
    retargeted = dict(original)
    retargeted[path] = {**original[path], "notes": "problems"}
    monkeypatch.setattr(stage_schema, "REFERENCE_HYDRATION", retargeted)
    assert registry_version() != baseline
    monkeypatch.undo()
    assert registry_version() == baseline


def test_reinstalling_the_registry_moves_the_version():
    baseline = registry_version()
    before = stages()
    try:
        stage_schema._install(before[:1])
        assert registry_version() != baseline
    finally:
        stage_schema._install(before)
    assert registry_version() == baseline



def test_the_digest_reads_nothing_but_its_fingerprint():
    captured = stage_schema._registry_fingerprint()
    frozen = stage_schema._registry_digest(captured)
    live = registry_version()
    assert frozen == live

    with _swapped_attrs(_a_plain_text_field(), text_format=TextFormat.PINCODE):
        assert stage_schema._registry_digest(captured) == frozen, (
            "the digest of a captured fingerprint moved when the live registry did, so it is "
            "reading something the memo key does not cover"
        )
        assert registry_version() != live, "the live version should have moved; the memo is stale"

    assert registry_version() == live
