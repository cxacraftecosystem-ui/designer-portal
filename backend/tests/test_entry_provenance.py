from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest

import app.services.stage_definitions  # noqa: F401
from app.services import design_workshops as dw, entry_provenance as ep
from app.services.stage_schema import all_entities

ASHA = SimpleNamespace(id="usr_asha", name="Asha Patel", role="DESIGNER")
RAVI = SimpleNamespace(id="usr_ravi", name="Ravi Kumar", role="DESIGNER")
MEENA = SimpleNamespace(id="usr_meena", name="Meena Iyer", role="RESEARCHER")

T0 = datetime(2026, 3, 1, 9, 0, tzinfo=UTC)
T1 = datetime(2026, 3, 8, 15, 30, tzinfo=UTC)


def _entity(key: str):
    entity = next((e for _stage, e in all_entities() if e.key == key), None)
    assert entity is not None, f"unknown entity {key}"
    return entity



class _Delegate:
    def __init__(self, rows):
        self._rows = rows

    async def find_many(self, where=None, include=None):
        wanted = set((where or {}).get("id", {}).get("in", []))
        return [r for r in self._rows if r.id in wanted]


class _FakeDb:
    def __init__(self, rows_by_delegate):
        self._rows = rows_by_delegate

    def __getattr__(self, name):
        return _Delegate(self._rows.get(name, []))

    async def query_raw(self, _sql, ids):
        return []


def _location_row(**overrides):
    fields = dict(
        state="Odisha", district="Bargarh", village="Barpali", pincode="768029",
        address="Weavers' lane, near the tank", placeName="Kharagpur",
        subjectLatitude=21.2, subjectLongitude=83.6,
        latitude=22.314, longitude=87.311, altitude=32.0, accuracy=26.0,
        capturedAt=datetime(2025, 3, 12, 9, 0),
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


def _artisan_row(**overrides):
    fields = dict(
        id="art_1", name="Latha Devi", localName="ଲତା ଦେବୀ", gender="Female",
        phone="9876500001", email="latha@example.org", place="Barpali",
        aadhaarNumber="234567890123", pehchanCardAvailable=True,
        pehchanCardNumber="PEHCHAN5678", address="House 4, Weavers' lane",
        notes="Prefers morning sessions.", dos="1. Speak in Odia\n2. Show samples",
        donts="1. Do not photograph the loom shed",
        extraMetadata={"experienceYears": 22, "age": 44},
        dateOfBirth=None, experienceYears=None, craftStartDate=None,
        recordedAt=datetime(2025, 3, 12, 9, 0), recordedTimezone="Asia/Kolkata",
        craft=SimpleNamespace(name="Sambalpuri Ikat"), location=_location_row(),
        createdById=MEENA.id,
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


async def _hydrate(monkeypatch, entity_key, sent, *, rows, previous=None, previous_provenance=None):
    monkeypatch.setattr(dw, "db", _FakeDb(rows))
    entry = dw.PendingEntry(
        entity=_entity(entity_key),
        data=dict(sent),
        previous=dict(previous or {}),
        row_id=None,
        ordinal=0,
        client_key="k1",
        previous_provenance=dict(previous_provenance or {}),
    )
    await dw.hydrate_entries([entry])
    return entry


def _merge(entry, user, now=T0):
    return ep.merge_entry_provenance(
        previous=entry.previous_provenance,
        previous_data=entry.previous,
        new_data=entry.data,
        hydrated=entry.hydrated,
        user=user,
        now=now,
    )



async def test_a_hydrated_field_is_attributed_to_the_records_author_not_the_designer(monkeypatch):
    entry = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"}, rows={"artisan": [_artisan_row()]}
    )
    stamps = _merge(entry, ASHA)

    assert entry.data["name"] == "Latha Devi", "hydration itself must still work"
    name = stamps["name"]
    assert name["source"] == ep.SOURCE_REFERENCE
    assert name["by"] == MEENA.id, "the artisan's recorder, not the designer who picked her"
    assert (name["refModel"], name["refId"], name["refKey"]) == ("Artisan", "art_1", "name")

    misattributed = sorted(
        key for key, stamp in stamps.items()
        if key in entry.hydrated and stamp["by"] != MEENA.id
    )
    assert misattributed == []


async def test_the_field_the_designer_typed_is_theirs_even_on_the_same_row(monkeypatch):
    entry = await _hydrate(
        monkeypatch,
        "participant",
        {"artisanRef": "art_1", "phone": "9000011111"},
        rows={"artisan": [_artisan_row()]},
    )
    stamps = _merge(entry, ASHA)

    assert entry.data["phone"] == "9000011111", "the designer's own answer must not be overwritten"
    assert stamps["phone"] == {
        "by": ASHA.id, "byName": ASHA.name, "at": T0.isoformat(), "source": ep.SOURCE_DESIGNER
    }
    assert stamps["village"]["by"] == MEENA.id, "the untouched neighbour is still the record's"


async def test_editing_a_hydrated_field_moves_its_provenance_to_the_editor(monkeypatch):
    first = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"}, rows={"artisan": [_artisan_row()]}
    )
    stored_data, stored_prov = dict(first.data), _merge(first, ASHA)
    assert stored_prov["village"]["by"] == MEENA.id

    second = await _hydrate(
        monkeypatch,
        "participant",
        {**stored_data, "village": "Bargarh Town"},
        rows={"artisan": [_artisan_row()]},
        previous=stored_data,
        previous_provenance=stored_prov,
    )
    after = _merge(second, RAVI, now=T1)

    assert after["village"] == {
        "by": RAVI.id, "byName": RAVI.name, "at": T1.isoformat(), "source": ep.SOURCE_DESIGNER
    }
    assert after["name"]["by"] == MEENA.id, "an untouched field keeps its original author"
    assert after["name"]["at"] == T0.isoformat(), "and its original timestamp — it did not change"


async def test_an_unchanged_field_is_not_re_attributed_to_whoever_saves_next(monkeypatch):
    first = await _hydrate(
        monkeypatch,
        "participant",
        {"artisanRef": "art_1", "phone": "9000011111"},
        rows={"artisan": [_artisan_row()]},
    )
    stored_data, stored_prov = dict(first.data), _merge(first, ASHA)

    resaved = await _hydrate(
        monkeypatch,
        "participant",
        dict(stored_data),
        rows={"artisan": [_artisan_row()]},
        previous=stored_data,
        previous_provenance=stored_prov,
    )
    after = _merge(resaved, RAVI, now=T1)

    assert after == stored_prov, "an unchanged re-save must change nothing at all"
    assert after["phone"]["by"] == ASHA.id


async def test_a_handset_that_has_never_read_the_stage_cannot_erase_its_provenance(monkeypatch):
    first = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"}, rows={"artisan": [_artisan_row()]}
    )
    stored_data, stored_prov = dict(first.data), _merge(first, ASHA)

    phone_sent = {"phone": "9000022222"}
    merged = await _hydrate(
        monkeypatch,
        "participant",
        {**stored_data, **phone_sent},
        rows={"artisan": [_artisan_row()]},
        previous=stored_data,
        previous_provenance=stored_prov,
    )
    after = _merge(merged, RAVI, now=T1)

    assert after["phone"]["by"] == RAVI.id, "the one field the phone actually sent is his"
    reassigned = sorted(
        key for key in stored_prov
        if key != "phone" and after[key]["by"] != stored_prov[key]["by"]
    )
    assert reassigned == [], "a merge save reassigned fields the phone never saw"


async def test_a_field_nobody_recorded_an_author_for_stays_unattributed(monkeypatch):
    legacy = {"name": "Latha Devi", "village": "Barpali"}
    entry = await _hydrate(
        monkeypatch, "participant", dict(legacy), rows={}, previous=legacy, previous_provenance={}
    )
    stamps = _merge(entry, RAVI, now=T1)
    assert stamps == {}, "no stamp may be invented for a value this save did not change"

    edited = await _hydrate(
        monkeypatch,
        "participant",
        {**legacy, "village": "Bargarh Town"},
        rows={},
        previous=legacy,
        previous_provenance={},
    )
    after = _merge(edited, RAVI, now=T1)
    assert list(after) == ["village"]
    assert after["village"]["by"] == RAVI.id


async def test_re_pointing_a_reference_does_not_leave_the_old_records_author_behind(monkeypatch):
    kamla = _artisan_row(
        id="art_2", name="Kamla Bai", location=_location_row(village="Sonepur"),
        createdById=ASHA.id,
    )
    first = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"}, rows={"artisan": [_artisan_row()]}
    )
    stored_data, stored_prov = dict(first.data), _merge(first, ASHA)
    assert stored_prov["village"]["by"] == MEENA.id

    second = await _hydrate(
        monkeypatch,
        "participant",
        {**stored_data, "artisanRef": "art_2"},
        rows={"artisan": [kamla]},
        previous={**stored_data, "artisanRef": "art_1"},
        previous_provenance=stored_prov,
    )
    after = _merge(second, RAVI, now=T1)

    assert second.data["name"] == "Kamla Bai"
    stale = sorted(k for k, s in after.items() if s.get("refId") == "art_1")
    assert stale == [], "no field may still point at the record this row no longer names"
    assert after["name"]["by"] == ASHA.id, "Kamla's recorder, who is a different person"


async def test_a_value_hydration_did_not_write_is_never_stamped(monkeypatch):
    entry = await _hydrate(
        monkeypatch,
        "participant",
        {"artisanRef": "art_1"},
        rows={"artisan": [_artisan_row(
            phone=None, email=None, place=None, location=_location_row(village=None),
        )]},
    )
    stamps = _merge(entry, ASHA)

    for key in ("phone", "email", "village"):
        assert key not in entry.data, f"{key} should not have been written"
        assert key not in stamps, f"{key} was stamped without a value behind it"
    assert set(stamps) <= set(entry.data), "every stamp must have a value behind it"
    assert set(entry.hydrated) <= set(entry.data), "hydration recorded a write it did not make"


def test_the_sync_protocols_own_keys_are_never_stamped():
    stamps = ep.merge_entry_provenance(
        previous={}, previous_data={},
        new_data={"name": "Latha Devi", "_entryId": "ent_1", "_ordinal": 3, "_clientKey": "k1"},
        hydrated={}, user=ASHA, now=T0,
    )
    assert list(stamps) == ["name"]


def test_an_empty_answer_is_not_an_authorship_claim():
    stamps = ep.merge_entry_provenance(
        previous={}, previous_data={},
        new_data={"name": "Latha Devi", "notes": "", "gallery": [], "extra": None},
        hydrated={}, user=ASHA, now=T0,
    )
    assert list(stamps) == ["name"]



async def test_one_designers_correction_does_not_reach_another_designers_workshop(monkeypatch):
    record = _artisan_row()
    before = dict(record.__dict__)

    asha_first = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"}, rows={"artisan": [record]}
    )
    asha_data, asha_prov = dict(asha_first.data), _merge(asha_first, ASHA)
    asha_second = await _hydrate(
        monkeypatch,
        "participant",
        {**asha_data, "village": "Bargarh Town"},
        rows={"artisan": [record]},
        previous=asha_data,
        previous_provenance=asha_prov,
    )
    _merge(asha_second, ASHA, now=T1)

    ravi = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"}, rows={"artisan": [record]}
    )
    ravi_prov = _merge(ravi, RAVI, now=T1)

    assert asha_second.data["village"] == "Bargarh Town"
    assert ravi.data["village"] == "Barpali", "Asha's edit must not reach Ravi's workshop"
    assert ravi_prov["village"]["by"] == MEENA.id, "nor may it move the record's authorship"
    assert record.__dict__ == before, "the canonical row must not be touched by an overlay write"



def test_a_row_written_before_the_column_reads_as_no_answer_rather_than_raising():
    assert ep.entry_provenance(SimpleNamespace(fieldProvenance=None)) == {}
    assert ep.entry_provenance(SimpleNamespace(fieldProvenance=["not", "a", "map"])) == {}
    assert ep.entry_provenance(SimpleNamespace()) == {}
    assert ep.entry_provenance(SimpleNamespace(fieldProvenance={"a": {"by": "u"}})) == {"a": {"by": "u"}}


def test_resolution_is_keyed_by_entry_id_so_a_re_sort_cannot_misalign_it():
    rows = [
        SimpleNamespace(id="ent_b", ordinal=1, fieldProvenance={"name": {"by": "usr_b"}}),
        SimpleNamespace(id="ent_a", ordinal=0, fieldProvenance={"name": {"by": "usr_a"}}),
    ]
    resolved = ep.resolve_entry_provenance(rows)
    assert resolved["ent_a"]["name"]["by"] == "usr_a"
    assert resolved["ent_b"]["name"]["by"] == "usr_b"
    assert ep.resolve_entry_provenance(sorted(rows, key=lambda r: r.ordinal)) == resolved


async def test_display_names_are_filled_in_from_one_query_and_a_deleted_account_keeps_its_id(
    monkeypatch,
):
    calls: list[Any] = []

    class _Users:
        async def find_many(self, where=None):
            calls.append(sorted(where["id"]["in"]))
            return [SimpleNamespace(id=MEENA.id, name="Meena Iyer-Rao")]

    monkeypatch.setattr("app.core.db.db", SimpleNamespace(user=_Users()))
    maps = [
        {"name": {"by": MEENA.id, "byName": None, "source": ep.SOURCE_REFERENCE}},
        {"phone": {"by": ASHA.id, "byName": ASHA.name, "source": ep.SOURCE_DESIGNER}},
        {"village": {"by": "usr_gone", "byName": None, "source": ep.SOURCE_REFERENCE}},
    ]
    await ep.resolve_display_names(maps)

    assert calls == [["usr_gone", MEENA.id]], "one query, and only for the names not already known"
    assert maps[0]["name"]["byName"] == "Meena Iyer-Rao", "the CURRENT name, not the stored one"
    assert maps[1]["phone"]["byName"] == ASHA.name, "an already-named stamp is left alone"
    assert maps[2]["village"] == {"by": "usr_gone", "byName": None, "source": ep.SOURCE_REFERENCE}


async def test_no_query_is_issued_when_every_stamp_already_has_a_name(monkeypatch):
    def _boom(*_args, **_kwargs):  # pragma: no cover - the point is that it is never called
        raise AssertionError("resolve_display_names must not query when nothing is missing")

    monkeypatch.setattr("app.core.db.db", SimpleNamespace(user=SimpleNamespace(find_many=_boom)))
    await ep.resolve_display_names([{"name": {"by": ASHA.id, "byName": ASHA.name}}, {}])



async def test_an_admin_sees_the_workshops_value_beside_the_records_current_one(monkeypatch):
    entry = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"}, rows={"artisan": [_artisan_row()]}
    )
    stamps = _merge(entry, ASHA)
    row = SimpleNamespace(id="ent_1", data=dict(entry.data), fieldProvenance=stamps)

    monkeypatch.setattr(
        "app.core.db.db",
        _FakeDb({"artisan": [_artisan_row(location=_location_row(village="Bargarh Town"))]}),
    )
    picture = await ep.canonical_divergence([row])

    village = picture["ent_1"]["village"]
    assert village == {
        "stored": "Barpali",
        "canonical": "Bargarh Town",
        "diverged": True,
        "recordDeleted": False,
    }
    assert picture["ent_1"]["name"]["diverged"] is False, "an unchanged field must not read as drift"


async def test_a_deleted_record_reads_as_deleted_rather_than_disappearing(monkeypatch):
    entry = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"}, rows={"artisan": [_artisan_row()]}
    )
    row = SimpleNamespace(id="ent_1", data=dict(entry.data), fieldProvenance=_merge(entry, ASHA))

    monkeypatch.setattr("app.core.db.db", _FakeDb({"artisan": []}))
    picture = await ep.canonical_divergence([row])

    assert picture["ent_1"]["name"] == {
        "stored": "Latha Devi", "canonical": None, "diverged": False, "recordDeleted": True
    }


async def test_a_reference_this_build_cannot_resolve_is_not_reported_as_a_deletion(monkeypatch):
    entry = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"}, rows={"artisan": [_artisan_row()]}
    )
    stamps = _merge(entry, ASHA)
    stamps["name"] = {**stamps["name"], "refModel": "ArtisanLegacy"}
    row = SimpleNamespace(id="ent_1", data=dict(entry.data), fieldProvenance=stamps)

    monkeypatch.setattr("app.core.db.db", _FakeDb({"artisan": [_artisan_row()]}))
    picture = await ep.canonical_divergence([row])

    assert picture["ent_1"]["name"] == {
        "stored": "Latha Devi", "canonical": None, "diverged": False, "recordDeleted": False
    }


async def test_a_reference_stamp_with_no_record_id_is_not_reported_as_a_deletion(monkeypatch):
    entry = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"}, rows={"artisan": [_artisan_row()]}
    )
    stamps = _merge(entry, ASHA)
    stamps["name"] = {**stamps["name"], "refId": ""}
    row = SimpleNamespace(id="ent_1", data=dict(entry.data), fieldProvenance=stamps)

    monkeypatch.setattr("app.core.db.db", _FakeDb({"artisan": [_artisan_row()]}))
    picture = await ep.canonical_divergence([row])

    assert picture["ent_1"]["name"]["recordDeleted"] is False
    assert picture["ent_1"]["name"]["diverged"] is False


async def test_the_photograph_is_loaded_so_every_portrait_is_not_reported_as_drift(monkeypatch):
    photo = SimpleNamespace(id="med_1", caption="At her loom, Barpali")

    class _PhotoDb(_FakeDb):
        async def query_raw(self, _sql, ids):
            return [{"parent": "art_1", "id": photo.id, "caption": photo.caption}]

    monkeypatch.setattr(dw, "db", _PhotoDb({"artisan": [_artisan_row()]}))
    entry = dw.PendingEntry(
        entity=_entity("participant"), data={"artisanRef": "art_1"}, previous={},
        row_id=None, ordinal=0, client_key="k1",
    )
    await dw.hydrate_entries([entry])
    assert entry.data.get("photo"), "the fixture must actually hydrate a photograph"
    row = SimpleNamespace(id="ent_1", data=dict(entry.data), fieldProvenance=_merge(entry, ASHA))

    monkeypatch.setattr("app.core.db.db", _PhotoDb({"artisan": [_artisan_row()]}))
    picture = await ep.canonical_divergence([row])

    assert picture["ent_1"]["photo"]["diverged"] is False, (
        "the photograph was compared against a canonical value nobody loaded"
    )
    assert picture["ent_1"]["photo"]["canonical"] == entry.data["photo"]


async def test_a_designer_typed_field_is_not_offered_a_canonical_comparison(monkeypatch):
    row = SimpleNamespace(
        id="ent_1",
        data={"phone": "9000011111"},
        fieldProvenance={
            "phone": {"by": ASHA.id, "byName": ASHA.name, "source": ep.SOURCE_DESIGNER}
        },
    )
    monkeypatch.setattr("app.core.db.db", _FakeDb({}))
    assert await ep.canonical_divergence([row]) == {}



def test_this_feature_did_not_quietly_stop_the_copy():
    from app.services.stage_schema import REFERENCE_HYDRATION

    pairs = sum(len(mapping) for mapping in REFERENCE_HYDRATION.values())
    assert len(REFERENCE_HYDRATION) >= 8, "a hydration mapping was removed"
    assert pairs >= 81, f"reference hydration shrank to {pairs} field-pairs; see the boundary note"


@pytest.mark.parametrize("name", ["SOURCE_REFERENCE", "SOURCE_DESIGNER"])
def test_the_two_sources_are_the_two_sources(name):
    assert getattr(ep, name) in {"reference", "designer"}
