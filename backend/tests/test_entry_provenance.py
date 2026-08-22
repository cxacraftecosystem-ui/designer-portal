"""WHO LAST SET **THIS** FIELD — the rule, driven through the real merge and the real hydration.

The requirement these tests pin, in the product owner's words: "provenance stays with the ORIGINAL
author unless a field is edited, in which case that field's provenance moves to the editor", over
records that are shared between designers with a single canonical copy rather than a duplicate per
designer.

Three things make that non-trivial on a ``DwStageEntry`` and each has tests here:

1. **A stage entry holds two kinds of value that look identical once stored.** 81 field-pairs are
   COPIED onto these rows from shared records by ``hydrate_entries``; a hydrated village and a
   typed village are the same string in ``data``. So the tests below drive the REAL hydration
   rather than hand-building a ``hydrated`` map — the only moment the two are distinguishable is
   the moment hydration writes, and a test that skipped it would pass while the feature was blind.
2. **Two designers share one set of rows** (``DesignWorkshopViewer``), so ``createdById`` — who
   created the ROW — stops being the truth as soon as the second designer touches it.
3. **Rows that predate the column exist**, and the honest answer for them is "not recorded". The
   tempting backfill is fabrication on a document submitted to a ministry.

The per-reader assertions — that every surface which serves a stage entry actually resolves the
overlay — live in ``test_entry_provenance_readers.py``, one test per reader, deliberately not
folded into a single generic check.

Nothing here touches a database.
"""

from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest

# Importing this module is what installs the twenty-two stages into the registry.
import app.services.stage_definitions  # noqa: F401
from app.services import design_workshops as dw, entry_provenance as ep
from app.services.stage_schema import all_entities

ASHA = SimpleNamespace(id="usr_asha", name="Asha Patel", role="DESIGNER")
RAVI = SimpleNamespace(id="usr_ravi", name="Ravi Kumar", role="DESIGNER")
#: The RESEARCHER who recorded the shared artisan record. Never a designer on the workshop — that
#: separation is the whole point: their name has to reach a stage entry they never opened.
MEENA = SimpleNamespace(id="usr_meena", name="Meena Iyer", role="RESEARCHER")

T0 = datetime(2026, 3, 1, 9, 0, tzinfo=UTC)
T1 = datetime(2026, 3, 8, 15, 30, tzinfo=UTC)


def _entity(key: str):
    # ``all_entities`` yields (stage, entity) pairs — the stage is what makes an entity key
    # locatable, and the registry is deliberately the only place that mapping lives.
    entity = next((e for _stage, e in all_entities() if e.key == key), None)
    assert entity is not None, f"unknown entity {key}"
    return entity


# --------------------------------------------------------------------------------------
# A Prisma stand-in, enough for hydration
# --------------------------------------------------------------------------------------


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

    async def query_raw(self, _sql, ids):  # the reference photo lookup
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
    """A shared, canonical artisan record — recorded by MEENA, not by either designer.

    THE COLUMN LIST IS THE SAME ONE ``test_reference_carry`` USES, on purpose: that file's whole
    job is proving this shape matches ``prisma/schema.prisma`` column for column, so borrowing it
    means these tests cannot pass against an artisan that no longer resembles a real one.
    ``createdById`` is the one addition, and it is the field this module is about.
    """
    fields = dict(
        id="art_1", name="Latha Devi", localName="ଲତା ଦେବୀ", gender="Female",
        phone="9876500001", email="latha@example.org", place="Barpali",
        aadhaarNumber="234567890123", pehchanCardAvailable=True,
        pehchanCardNumber="PEHCHAN5678", address="House 4, Weavers' lane",
        notes="Prefers morning sessions.", dos="1. Speak in Odia\n2. Show samples",
        donts="1. Do not photograph the loom shed",
        extraMetadata={"experienceYears": 22, "age": 44},
        dateOfBirth=None, experienceYears=None,
        recordedAt=datetime(2025, 3, 12, 9, 0), recordedTimezone="Asia/Kolkata",
        craft=SimpleNamespace(name="Sambalpuri Ikat"), location=_location_row(),
        createdById=MEENA.id,
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


async def _hydrate(monkeypatch, entity_key, sent, *, rows, previous=None, previous_provenance=None):
    """Run one entry through the REAL hydration and return the finished PendingEntry."""
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


# --------------------------------------------------------------------------------------
# The rule itself
# --------------------------------------------------------------------------------------


async def test_a_hydrated_field_is_attributed_to_the_records_author_not_the_designer(monkeypatch):
    """**THE CENTRAL CLAIM: provenance stays with the ORIGINAL author.**

    Asha opens stage 3, picks Latha Devi out of the artisan picker, and saves. Twenty-two fields land
    on the row without Asha typing one of them. Every one of those is Meena's work — she recorded
    the artisan — and attributing them to Asha would be the system claiming a designer documented a
    person they only selected from a dropdown.

    The ``refModel``/``refId``/``refKey`` triple is asserted too, because it is what makes the admin
    comparison in ``canonical_divergence`` possible at all: without it a stored value can never be
    matched back to the column it was copied from.
    """
    entry = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"}, rows={"artisan": [_artisan_row()]}
    )
    stamps = _merge(entry, ASHA)

    assert entry.data["name"] == "Latha Devi", "hydration itself must still work"
    name = stamps["name"]
    assert name["source"] == ep.SOURCE_REFERENCE
    assert name["by"] == MEENA.id, "the artisan's recorder, not the designer who picked her"
    assert (name["refModel"], name["refId"], name["refKey"]) == ("Artisan", "art_1", "name")

    # Not one hydrated field may be credited to the designer.
    misattributed = sorted(
        key for key, stamp in stamps.items()
        if key in entry.hydrated and stamp["by"] != MEENA.id
    )
    assert misattributed == []


async def test_the_field_the_designer_typed_is_theirs_even_on_the_same_row(monkeypatch):
    """**AND THE OTHER HALF: a value the designer supplied is the designer's.**

    The same save carries both kinds. Asha types the phone number the artisan gave her in the room —
    which differs from the record — and picks the artisan for everything else. One row, two authors,
    and the answer has to be per FIELD or it is not an answer at all.

    ``hydrate_entries`` only fills BLANKS, so the typed phone survives; this test would also catch a
    regression in that rule, because a hydrated phone would be stamped to Meena.
    """
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
    """**"...unless a field is edited, in which case that field's provenance moves to the editor."**

    The full two-save story, which is the one the requirement describes and the one no single-save
    test can reach: Asha picks the artisan on Monday (village hydrates as Meena's), Ravi corrects the
    village on the following Monday because the cluster moved. Ravi owns the village from then on;
    Meena keeps the twenty-one fields nobody touched.

    Ravi is a DIFFERENT designer from the one who created the row, which is the ordinary case on a
    shared workshop and the exact case ``createdById`` cannot describe.
    """
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
    """**A SAVE IS NOT AN EDIT.**

    Both clients send the whole stage back on every save, so the overwhelming majority of keys in
    any payload are unchanged. If the merge stamped what it was SENT rather than what CHANGED, the
    first co-designer to open a stage and press save would become the author of every field in it,
    and the provenance panel would show one name for a record three people built.
    """
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
    """**THE MERGE SAVE, WHICH IS THE RISKIEST THING A CLIENT DOES TO THIS COLUMN.**

    ``merge: true`` means, in ``StageEntryIn``'s own words, "I am sending every key I HAVE, not every
    key there IS" — a phone that has never downloaded a stage holds a blank form, and without the
    merge branch a single typed field would delete every other answer the office had written. That
    branch is what this test rides: ``save_stage`` computes ``clean = {**previous, **clean}`` BEFORE
    building the ``PendingEntry``, so the keys the phone never saw arrive in ``new_data`` looking
    exactly like keys it deliberately re-sent.

    The failure available here is severe and silent: if provenance were computed from what the
    client SENT rather than from what CHANGED, one offline phone syncing one field would reassign
    authorship of the whole participant row to its owner — including the fields that belong to a
    researcher who recorded the artisan two years ago. Nothing would raise, and the panel would
    look completely healthy.
    """
    first = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"}, rows={"artisan": [_artisan_row()]}
    )
    stored_data, stored_prov = dict(first.data), _merge(first, ASHA)

    # What `save_stage` hands the PendingEntry for a merge save: the phone sent only `phone`, and
    # the previous row's keys were folded in underneath it.
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
    """**THE ROWS THAT PREDATE THE COLUMN ARE LEFT HONEST.**

    Every stage entry written before ``fieldProvenance`` existed holds values whose author nobody
    recorded. The available backfill — credit the row's ``createdById``, or credit whoever saves
    next — would put a name against a value that person may never have seen, on a document that is
    submitted to a ministry, and it would be indistinguishable on screen from a real attribution.

    So an unchanged field with no carried stamp gets none, for ever, until somebody actually edits
    it. The second half of this test is the "until": the moment Ravi changes it, it becomes his,
    which is what stops the absence from being permanent.
    """
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
    """**THE CLEARING RULE HAS A PROVENANCE HALF, AND IT HAD TO BE WRITTEN TOO.**

    ``hydrate_entries`` already clears every mapped field when a row is re-pointed at a DIFFERENT
    record — its own comment explains the year-long defect where artisan B's name sat beside artisan
    A's phone number in a ministry report. The same defect exists one level down: if the stamps were
    not cleared alongside the values, the new record's fields would carry the OLD record's author,
    and a blank the designer then filled in would be credited to a researcher who never saw it.

    Here the row is re-pointed from Sita (recorded by Meena) to Kamla (recorded by Asha), and every
    surviving stamp must name Asha.
    """
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
    """**THE MAP MAY NEVER CLAIM AUTHORSHIP OF A FIELD THAT STAYED BLANK.**

    ``hydrate_entries`` writes nothing for a column the record leaves empty, and drops a value
    ``coerce_value`` rejects — a product type that is not one of the workshop's categories, a number
    that will not parse — leaving the field blank for the designer to answer. A provenance map built
    from the MAPPING rather than from what was actually WRITTEN would say "Meena set this" over an
    empty box, and the panel would show an author for a field with no value in it.

    Driving the real hydration is what makes this checkable: the stamp set and the written set are
    produced by the same pass, so they cannot disagree. The final assertion is the invariant and the
    named keys are the illustration — a thinly documented artisan is an ordinary record, not a
    contrived one.

    ``place=None`` is set alongside the blank location because the Artisan reference model reads
    ``village`` as ``location.village or r.place``: a blank location alone still hydrates the
    free-text place, which is correct behaviour and would have made this test assert a fiction.
    """
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
    """``_entryId``, ``_ordinal`` and ``_clientKey`` are transport, not workshop data.

    They arrive inside the same dict as the answers and are echoed back on the next save. Stamping
    them would put three phantom rows in every provenance panel, each naming a designer as the
    author of a cuid the server generated.
    """
    stamps = ep.merge_entry_provenance(
        previous={}, previous_data={},
        new_data={"name": "Latha Devi", "_entryId": "ent_1", "_ordinal": 3, "_clientKey": "k1"},
        hydrated={}, user=ASHA, now=T0,
    )
    assert list(stamps) == ["name"]


def test_an_empty_answer_is_not_an_authorship_claim():
    """A blank box has no author. Stamping one would mean "Asha set this to nothing", which reads on
    screen exactly like "Asha wrote an answer here" and is the opposite of what happened."""
    stamps = ep.merge_entry_provenance(
        previous={}, previous_data={},
        new_data={"name": "Latha Devi", "notes": "", "gallery": [], "extra": None},
        hydrated={}, user=ASHA, now=T0,
    )
    assert list(stamps) == ["name"]


# --------------------------------------------------------------------------------------
# Isolation: one designer's edit is invisible to another
# --------------------------------------------------------------------------------------


async def test_one_designers_correction_does_not_reach_another_designers_workshop(monkeypatch):
    """**THE CANONICAL ROW IS NEVER MUTATED BY AN OVERLAY WRITE, AND THE OVERLAY IS PER WORKSHOP.**

    Asha's workshop and Ravi's workshop both hydrate the same shared artisan. Asha corrects the
    village on her stage entry. Ravi's entry — hydrated from the same record — must still show the
    record's village, and the record itself must be byte-identical to what it was before.

    This is the isolation the requirement asks for ("only the changed fields stored individually for
    the particular designers who make the edits"), expressed at the boundary this data model
    actually has: the per-WORKSHOP stage entry. The isolation is real and it costs the shared record
    nothing, which is what makes a second, private overlay unnecessary — see the module docstring of
    ``services/entry_provenance`` under "THE PRIVATE OVERLAY" for the reading that was NOT built and
    the two written policies it would contradict.
    """
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


# --------------------------------------------------------------------------------------
# Reading it back
# --------------------------------------------------------------------------------------


def test_a_row_written_before_the_column_reads_as_no_answer_rather_than_raising():
    """``None`` and ``{}`` are the same answer to a reader, and a non-dict is discarded.

    A reader that receives ``None`` where it expects a map raises inside a report render — halfway
    through generating a .docx — rather than at the boundary, which is the worst place for it. The
    non-dict arm covers a hand-edited row or a restored dump; nothing this codebase writes can
    produce one, and that is exactly why it must not propagate if it appears.
    """
    assert ep.entry_provenance(SimpleNamespace(fieldProvenance=None)) == {}
    assert ep.entry_provenance(SimpleNamespace(fieldProvenance=["not", "a", "map"])) == {}
    assert ep.entry_provenance(SimpleNamespace()) == {}
    assert ep.entry_provenance(SimpleNamespace(fieldProvenance={"a": {"by": "u"}})) == {"a": {"by": "u"}}


def test_resolution_is_keyed_by_entry_id_so_a_re_sort_cannot_misalign_it():
    """Three readers sort the same rows three different ways; a positional map would be wrong on at
    least one of them and nothing would raise.

    ``_stages_payload`` sorts collection rows by ``_ordinal`` AFTER grouping,
    ``assemble_workshop_data`` sorts BEFORE, and the phone sorts its own draft. The failure a
    positional map produces is the worst kind available here — one participant's edits shown against
    another participant's name, in a table that is the proof of who attended.
    """
    rows = [
        SimpleNamespace(id="ent_b", ordinal=1, fieldProvenance={"name": {"by": "usr_b"}}),
        SimpleNamespace(id="ent_a", ordinal=0, fieldProvenance={"name": {"by": "usr_a"}}),
    ]
    resolved = ep.resolve_entry_provenance(rows)
    assert resolved["ent_a"]["name"]["by"] == "usr_a"
    assert resolved["ent_b"]["name"]["by"] == "usr_b"
    # And re-sorting the input cannot change the answer.
    assert ep.resolve_entry_provenance(sorted(rows, key=lambda r: r.ordinal)) == resolved


async def test_display_names_are_filled_in_from_one_query_and_a_deleted_account_keeps_its_id(
    monkeypatch,
):
    """``byName`` is resolved on READ, so a renamed researcher is shown under their current name.

    Storing it at write time would freeze the name as it was on the day each field was hydrated, and
    would cost a User query inside a save a designer is waiting on with one bar of signal.

    A stamp whose ``by`` names a deleted account keeps its id and gets no name. Dropping the stamp
    instead would erase the fact that the field WAS attributed, which is the more useful half of
    what is left.
    """
    calls: list[Any] = []

    class _Users:
        async def find_many(self, where=None):
            calls.append(sorted(where["id"]["in"]))
            return [SimpleNamespace(id=MEENA.id, name="Meena Iyer-Rao")]  # she married

    monkeypatch.setattr("app.core.db.db", SimpleNamespace(user=_Users()))
    maps = [
        {"name": {"by": MEENA.id, "byName": None, "source": ep.SOURCE_REFERENCE}},
        {"phone": {"by": ASHA.id, "byName": ASHA.name, "source": ep.SOURCE_DESIGNER}},
        {"village": {"by": "usr_gone", "byName": None, "source": ep.SOURCE_REFERENCE}},
    ]
    await ep.resolve_display_names(maps)

    # Sorted, so the query is stable across runs — and asserted sorted here for the same reason.
    assert calls == [["usr_gone", MEENA.id]], "one query, and only for the names not already known"
    assert maps[0]["name"]["byName"] == "Meena Iyer-Rao", "the CURRENT name, not the stored one"
    assert maps[1]["phone"]["byName"] == ASHA.name, "an already-named stamp is left alone"
    assert maps[2]["village"] == {"by": "usr_gone", "byName": None, "source": ep.SOURCE_REFERENCE}


async def test_no_query_is_issued_when_every_stamp_already_has_a_name(monkeypatch):
    """A workshop whose fields were all typed by its own designers pays nothing for this feature.

    The designer branch of the merge keeps the name it already holds, so the common case — a stage
    with no reference fields — must not reach the database at all.
    """
    def _boom(*_args, **_kwargs):  # pragma: no cover - the point is that it is never called
        raise AssertionError("resolve_display_names must not query when nothing is missing")

    monkeypatch.setattr("app.core.db.db", SimpleNamespace(user=SimpleNamespace(find_many=_boom)))
    await ep.resolve_display_names([{"name": {"by": ASHA.id, "byName": ASHA.name}}, {}])


# --------------------------------------------------------------------------------------
# The admin picture
# --------------------------------------------------------------------------------------


async def test_an_admin_sees_the_workshops_value_beside_the_records_current_one(monkeypatch):
    """**"ADMINS AND MASTER ADMINS HAVE ACCESS TO ALL OF IT" — the half nobody else can see.**

    Every designer on the workshop already sees the per-field stamps. What only an admin gets is the
    comparison: this workshop stored Barpali, the artisan record now says Bargarh Town. That is not
    derivable from anything the other readers return, because once hydrated a copied value is an
    ordinary string in ``data``; it takes the ``reference`` stamp, which names the record and the
    column, plus a live read.

    DIVERGENCE IS NOT AN ERROR and this test asserts that shape deliberately: the workshop is a dated
    observation and is supposed to keep what the designer saw. ``diverged: true`` is a fact for an
    admin to look at, never a thing to correct automatically.
    """
    entry = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"}, rows={"artisan": [_artisan_row()]}
    )
    stamps = _merge(entry, ASHA)
    row = SimpleNamespace(id="ent_1", data=dict(entry.data), fieldProvenance=stamps)

    # The record has since been corrected — the ordinary life of a shared, living row.
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
    """The case reference hydration exists for, reported rather than omitted.

    The workshop still holds what the designer saw on the day — that is the entire argument for
    copying — and the honest rendering is "the record this came from no longer exists". Omitting the
    field instead would make a deleted record look identical to a field that was never hydrated,
    which is the one distinction an auditor is here to make.
    """
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
    """**"DELETED" IS A CLAIM ABOUT A RECORD, NOT ABOUT THIS BUILD'S REGISTRY.**

    The gathering pass only queries a stamp whose ``refModel`` is in ``REFERENCE_MODELS``; the
    per-field pass did not apply the same test, so a stamp naming a model this build no longer knows
    found nothing and was reported ``recordDeleted: True``. The screen renders that as "the record
    this came from no longer exists" — a deletion that never happened, about a record nobody looked
    for, sending an admin to search an archive for something that is sitting there under a name the
    code has stopped recognising.

    NOT REACHABLE TODAY and deliberately tested anyway: ``hydrate_entries`` only stamps when
    ``spec.ref_model in REFERENCE_MODELS``, so nothing writes such a stamp now. It becomes reachable
    the day a model is renamed or dropped from that table while stamps written under the old name
    remain — which is exactly the aged archive this endpoint exists to be read against, and exactly
    when nobody will be looking for a fresh bug in it.
    """
    entry = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"}, rows={"artisan": [_artisan_row()]}
    )
    stamps = _merge(entry, ASHA)
    # A stamp from a build whose registry named this model. The row and its value are untouched.
    stamps["name"] = {**stamps["name"], "refModel": "ArtisanLegacy"}
    row = SimpleNamespace(id="ent_1", data=dict(entry.data), fieldProvenance=stamps)

    monkeypatch.setattr("app.core.db.db", _FakeDb({"artisan": [_artisan_row()]}))
    picture = await ep.canonical_divergence([row])

    # Neither answer is invented: the value is still reported, and both flags say "nothing was
    # compared" rather than one of them claiming something the lookup never established.
    assert picture["ent_1"]["name"] == {
        "stored": "Latha Devi", "canonical": None, "diverged": False, "recordDeleted": False
    }


async def test_a_reference_stamp_with_no_record_id_is_not_reported_as_a_deletion(monkeypatch):
    """The other half of the gathering pass's guard: ``and record_id``.

    A ``reference`` stamp naming a model but no record was queried for nothing and then reported as
    a deleted record, for the same reason and with the same wrong sentence.
    """
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
    """**A FALSE POSITIVE THAT WOULD HAVE MADE THE WHOLE DIVERGENCE REPORT UNREADABLE.**

    ``photo`` and ``photoCaption`` are in ``participant.artisanRef``'s mapping and they do NOT come
    from a Prisma column — ``REFERENCE_MODELS[...].data`` receives them as a second argument, loaded
    by ``_reference_photos`` in a query of its own. ``canonical_divergence`` originally called that
    lambda with ``photo=None``, which is cheaper and wrong: the canonical value computed for every
    portrait in the archive was ``None`` while the stored value was a media id, so EVERY artisan with
    a photograph was reported to an admin as having diverged from their record.

    An audit report that flags everything flags nothing — the reviewer stops reading it, and the one
    genuine divergence in a season is lost in nine hundred false ones. So the photo lookup is paid
    for, once per model, exactly as hydration pays for it.
    """
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
    """There is nothing to compare it to, and inventing one would be a lie.

    A value the designer typed has no canonical counterpart — the field is not in any hydration
    mapping for that key on that row, or the designer overrode it. Reporting ``canonical: null`` for
    it would read as "the record says nothing here", which is a claim about the record this function
    has not made and cannot make.
    """
    row = SimpleNamespace(
        id="ent_1",
        data={"phone": "9000011111"},
        fieldProvenance={
            "phone": {"by": ASHA.id, "byName": ASHA.name, "source": ep.SOURCE_DESIGNER}
        },
    )
    monkeypatch.setattr("app.core.db.db", _FakeDb({}))
    assert await ep.canonical_divergence([row]) == {}


# --------------------------------------------------------------------------------------
# The boundary with reference hydration, held mechanically
# --------------------------------------------------------------------------------------


def test_this_feature_did_not_quietly_stop_the_copy():
    """**THE BOUNDARY, MADE INTO A TEST RATHER THAN LEFT AS A PARAGRAPH.**

    The requirement behind ``entry_provenance`` is "do not duplicate the record per designer".
    ``REFERENCE_HYDRATION`` exists to duplicate exactly that, 81 field-pairs of it, because a
    workshop report is a historical document and a submitted .docx must not be rewritten by a later
    correction to a live record. Both are right, for different objects, and the module docstring of
    ``services/entry_provenance`` says where the line runs: the VALUE is copied and stays copied,
    only AUTHORSHIP is attributed.

    The cheapest wrong turn available to a future change is "we have provenance now, so we can stop
    copying and resolve through ``refId`` at render time". This test is what that change trips over.
    The count is asserted as a floor, not an equality, so ADDING carries — which
    ``test_reference_carry.py`` exists to encourage — never fails it.
    """
    from app.services.stage_schema import REFERENCE_HYDRATION

    pairs = sum(len(mapping) for mapping in REFERENCE_HYDRATION.values())
    assert len(REFERENCE_HYDRATION) >= 8, "a hydration mapping was removed"
    assert pairs >= 81, f"reference hydration shrank to {pairs} field-pairs; see the boundary note"


def test_the_module_records_the_boundary_and_what_was_not_built():
    """The two paragraphs a reviewer is sent to must actually be there.

    Prose tests are usually a smell. This one earns its place because the module's whole reason for
    existing is that two written policies over the same data point in opposite directions, and the
    ONLY thing standing between a later maintainer and quietly deleting one of them is that
    docstring. A rewrite that drops it should fail something.
    """
    doc = ep.__doc__ or ""
    assert "THE BOUNDARY" in doc
    assert "REFERENCE_HYDRATION" in doc
    assert "THE PRIVATE OVERLAY" in doc
    assert "viewable_where" in doc, "the already-built sharing half must stay named"
    assert "merge_field_provenance" in doc, "and so must the already-built record provenance"


@pytest.mark.parametrize("name", ["SOURCE_REFERENCE", "SOURCE_DESIGNER"])
def test_the_two_sources_are_the_two_sources(name):
    """Exactly two, and their values are wire format: both clients switch on them, and the admin
    endpoint filters on ``reference``. Renaming one is an API change, not a refactor."""
    assert getattr(ep, name) in {"reference", "designer"}
