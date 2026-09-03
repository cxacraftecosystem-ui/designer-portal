"""``registry_version()`` is memoised, and the memo cannot answer with a digest that has gone stale.

WHAT THIS DEFENDS
==================

The digest used to be re-derived from scratch on every call — 640 field specs formatted into part
strings, sorted, and hashed — and the call sites are not occasional: workshop detail, the stage
list, every report row, and TWICE per stage save, one of those inside the open transaction where the
cost is held write locks on a request a designer is waiting on.

**THE OBVIOUS MEMOISATION IS WRONG HERE AND THIS FILE IS WHERE THAT IS PROVED.** Computing the
digest at install time, or wrapping the function in ``lru_cache`` and clearing it from ``_install``,
assumes the registry only ever changes through ``_install``. It does not: it is module-level state,
and ``tests/test_stage_schema.py`` reaches into it three different ways that never go near that
function — ``object.__setattr__`` on a frozen, slotted ``FieldSpec``; a rebuilt ``EntitySpec.fields``
tuple; a monkeypatched ``REFERENCE_HYDRATION``. Each of those tests exists because a version string
that FAILS TO MOVE is how a stale bundled Android asset reports agreement with a registry it no
longer matches, and every one of them would have gone green against an install-keyed cache holding
the pre-mutation answer.

So the memo is keyed on the registry's CONTENT (``_registry_fingerprint``), and the digest is
computed from that fingerprint and from nothing else (``_registry_digest``). The second half is what
makes the first half safe rather than hopeful: an input cannot be added to the digest without being
added to the key, because the key is all the digest can see. The tests below pin the memo's speed,
its invisibility, and — the important one — that structural property directly.

No database, and no registry state left behind: every mutation here is undone in a ``finally``, for
the reason ``_swapped_field`` gives in the sibling file. A leaked mutation surfaces as an unrelated
failure somewhere else entirely.
"""

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
    """Change attributes ON THE LIVE SPEC and always put them back.

    The same escape hatch ``tests/test_stage_schema.py`` uses, and deliberately the same one: it is
    the mutation style an install-keyed cache could not see, so testing the memo against anything
    gentler would test nothing.
    """
    before = {name: getattr(spec, name) for name in attrs}
    for name, value in attrs.items():
        object.__setattr__(spec, name, value)
    try:
        yield
    finally:
        for name, value in before.items():
            object.__setattr__(spec, name, value)


def _a_plain_text_field() -> Any:
    """A TEXT field carrying no format, so giving it one is a real perturbation."""
    return next(
        f
        for _stage, entity in all_entities()
        for f in entity.fields
        if f.type is FieldType.TEXT and f.text_format is TextFormat.NONE
    )


# --------------------------------------------------------------------------------------
# The memo is invisible
# --------------------------------------------------------------------------------------


def test_the_version_is_the_same_string_every_time():
    first = registry_version()
    assert registry_version() == first
    assert len(first) == 16


def test_the_memoised_answer_is_the_answer_a_cold_call_computes():
    """The digest is a WIRE VALUE, and a refactor that changed it would stale every handset.

    ``android/.../assets/design-workshop-schema.json`` carries a copy of this string and
    ``test_the_bundled_android_asset_matches_the_registry_it_was_dumped_from`` compares the two, so
    the memo earning its keep is worth nothing if it changed what is being memoised. This asserts
    the two paths agree: the value the memo hands back, and the value computed with the memo emptied.
    """
    warm = registry_version()
    before = stage_schema._VERSION_MEMO
    try:
        stage_schema._VERSION_MEMO = None
        assert registry_version() == warm
    finally:
        stage_schema._VERSION_MEMO = before


def test_a_repeat_call_does_not_re_digest(monkeypatch):
    """The point of the change, stated as behaviour rather than as a timing.

    A measurement would be flaky on a shared runner; "the expensive half ran once" is exact.
    """
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


# --------------------------------------------------------------------------------------
# The memo cannot go stale — the three mutation styles that would have defeated a cache
# --------------------------------------------------------------------------------------


def test_an_in_place_attribute_change_still_moves_the_version():
    """The mutation an install-keyed cache is blind to, and the one that would have shipped.

    ``FieldSpec`` is frozen and slotted, so ``object.__setattr__`` writes the slot directly: no
    ``__setattr__`` hook fires, no container's identity changes, and ``_install`` is never called.
    A format is BEHAVIOUR — it decides what a save refuses — so a handset that does not know a field
    gained one goes on accepting values the server now rejects. The digest has to move.
    """
    baseline = registry_version()
    with _swapped_attrs(_a_plain_text_field(), text_format=TextFormat.PINCODE):
        assert registry_version() != baseline, (
            "a field gained a format in place and the memo answered with the old digest — which is "
            "exactly the stale-asset-reports-agreement failure the version string exists to prevent"
        )
    assert registry_version() == baseline, "the registry was restored and the digest did not follow"


def test_a_rebuilt_fields_tuple_still_moves_the_version():
    """The second style: the entity's ``fields`` tuple is replaced, the specs inside it are not."""
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
    """The third style: a module global is rebound under the function's feet.

    ``REFERENCE_HYDRATION`` is published to the clients as ``refHydration``, so it is a contract, and
    correcting a wrong mapping touches no key, type, tier or derivation. It has to be in the key for
    the same reason it is in the digest.
    """
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
    """The case the memo was NOT keyed on, which falls out of keying it on the content instead.

    ``_install`` rebinds ``STAGES``, so a smaller registry is a different fingerprint and nothing has
    to remember to clear anything. Written as its own test because "the mechanism happens to cover
    it" is precisely the kind of claim that stops being true quietly.
    """
    baseline = registry_version()
    before = stages()
    try:
        stage_schema._install(before[:1])
        assert registry_version() != baseline
    finally:
        stage_schema._install(before)
    assert registry_version() == baseline


# --------------------------------------------------------------------------------------
# The structural property that makes the key complete rather than merely current
# --------------------------------------------------------------------------------------


def test_the_digest_reads_nothing_but_its_fingerprint():
    """**THE ONE THAT KEEPS THE MEMO HONEST AS THE DIGEST GROWS.**

    ``registry_version``'s docstring records the digest being widened four separate times — a
    derivation, a mask flag, a text format, a hydration mapping, an item floor — each time because
    something behavioural had been left out and a stale client could not tell. The memo is only as
    complete as its key, so a fifth widening that read the registry directly instead of reading the
    fingerprint would produce a key that no longer covers the digest, and the version would go stale
    for exactly the kind of change it exists to signal.

    That cannot happen while this holds: the digest of a CAPTURED fingerprint is unchanged by any
    mutation of the live registry. If ``_registry_digest`` ever reaches past its argument, this
    fails on the day it is written rather than on the day a handset is wrong.
    """
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
