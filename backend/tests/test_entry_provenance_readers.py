"""EVERY READER RESOLVES THE OVERLAY — asserted once per reader, deliberately not once in general.

Copy-on-write is only correct if every reader resolves it. A reader that is missed serves the
canonical answer while the screen next to it serves the designer's, and nothing raises: both look
like data. So this file is shaped by that failure mode rather than by convenience — there is one
test per surface, each calling that surface's own entry point, even though three of them happen to
share ``_stages_payload`` today. A generic test over the shared serialiser would pass on the day a
route stopped using it, which is exactly the day it would start being wrong.

── THE INVENTORY, AND WHY HALF OF IT NEEDS NO RESOLUTION ──────────────────────────────────────────

The surfaces that read a record in this repository are: the API list and detail routes, the data
browser (``/data/tree``, ``/data/report``), the CSV exports, the .xlsx workbook, the dataset zip and
NDJSON/CSV stream, the report builder (.docx/.pdf), and the Android offline store. They divide in
two, and the division is the whole finding:

**THE RECORD TABLES HAVE NO OVERLAY TO RESOLVE, AND THAT IS NOT AN OMISSION.** ``Artisan``,
``ProductDocumentation``, ``ToolDocumentation``, ``Process``, ``Craft`` and ``Workshop`` are already
single canonical rows shared by everyone: ``records.viewable_where`` returns ``{}``, so every
signed-in account reads the same row, and there is no per-designer duplicate of one anywhere in the
schema. An edit mutates that row in place, records the changed fields per editor in
``RecordRevision``, and moves per-field authorship with ``records.merge_field_provenance``. There is
therefore no second value for a reader to miss — every one of those surfaces reads THE row — and
building a resolution layer over them would have been a second sharing system beside a working one.
:func:`test_no_record_reader_can_serve_a_stale_value_because_there_is_no_overlay` and
:func:`test_reading_the_repository_is_open_for_every_role` are the guards that keep that true, and
they are what a future change introducing a private per-designer record value would trip over.

**THE STAGE ENTRIES ARE WHERE THE COPY LIVES**, because ``hydrate_entries`` copies 81 field-pairs
off those shared records onto each workshop's rows. Those are the readers below.

**THE DATA BROWSER IS NOW ON BOTH SIDES OF THAT DIVISION**, as of 2026-08-31: it still reads the
record tables (no overlay), and it also reads ``DwStageEntry`` for the design-workshop taxonomy and
sheets. It is classified EXEMPT and FENCED under "Reader 9" — it serves values and names no author —
rather than being left off the inventory, which is what the division above would have implied.

Nothing here touches a database.
"""

import tokenize
from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.api.routes import (
    data_browser as browser,
    design_workshop_inspections as inspections,
    design_workshops as routes,
)
from app.services import (
    design_ratings as dr,
    design_workshop_data as dwdata,
    design_workshops as dw,
    entry_provenance as ep,
)

ASHA = SimpleNamespace(id="usr_asha", name="Asha Patel", role="DESIGNER")
MEENA = SimpleNamespace(id="usr_meena", name="Meena Iyer", role="RESEARCHER")
ADMIN = SimpleNamespace(id="usr_admin", name="Root", role="MASTER_ADMIN")
#: Rank 37, added 2026-08-27. Reads a workshop it is not a member of and cannot write to it, which
#: is exactly why its provenance has to resolve: an inspector is reading somebody ELSE's work, so
#: "who wrote this field" is the whole of what they are looking at.
INSPECTOR = SimpleNamespace(id="usr_ravi", name="Ravi Nair", role="INSPECTOR")

#: One hydrated field (the artisan's recorder owns it) and one typed field (the designer owns it),
#: which is the pair every reader has to carry intact. A reader that drops either half is broken in
#: a way a single-stamp fixture would not show.
STAMPS = {
    "name": {
        "by": MEENA.id, "byName": None, "at": "2026-03-01T09:00:00+00:00",
        "source": ep.SOURCE_REFERENCE, "refModel": "Artisan", "refId": "art_1", "refKey": "name",
    },
    "phone": {
        "by": ASHA.id, "byName": ASHA.name, "at": "2026-03-08T15:30:00+00:00",
        "source": ep.SOURCE_DESIGNER,
    },
}

#: The stages and entities used throughout, all REAL registry keys — so a rename in the registry
#: fails these tests rather than letting them pass against a fiction.
#:
#: ``WORKSHOP_SETUP`` is stage 1 and declares only a singleton. ``WORKSHOP_PLAN_PARTICIPANTS_OPENING``
#: is stage 3 and is where ``participant`` lives, which is the collection that receives the artisan
#: hydration this whole feature is about. ``SKETCH_DEVELOPMENT`` is used as the never-saved stage
#: because it declares no singleton at all — eight of the twenty-two do not, and that is the shape a
#: client most easily gets wrong.
SINGLETON_STAGE = "WORKSHOP_SETUP"
COLLECTION_STAGE = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"
UNSAVED_STAGE = "SKETCH_DEVELOPMENT"


def _row(entry_id, stage, entity, data, stamps, ordinal=0):
    return SimpleNamespace(
        id=entry_id, stageKey=stage, entityKey=entity, ordinal=ordinal,
        data=dict(data), fieldProvenance=dict(stamps), clientKey=None,
        createdById=ASHA.id, deletedAt=None,
        createdAt=datetime(2026, 3, 1, tzinfo=UTC), updatedAt=datetime(2026, 3, 8, tzinfo=UTC),
    )


def _entries():
    return [
        _row("ent_setup", SINGLETON_STAGE, "workshopSetup", {"venue": "Barpali"},
             {"venue": STAMPS["phone"]}),
        _row("ent_p1", COLLECTION_STAGE, "participant",
             {"name": "Sita Devi", "phone": "9000011111"}, STAMPS, ordinal=0),
        _row("ent_p2", COLLECTION_STAGE, "participant",
             {"name": "Kamla Bai"}, {"name": STAMPS["name"]}, ordinal=1),
    ]


class _Users:
    """The single name lookup ``resolve_display_names`` makes, recorded so it can be asserted."""

    def __init__(self):
        self.calls: list[list[str]] = []

    async def find_many(self, where=None):
        self.calls.append(sorted(where["id"]["in"]))
        return [SimpleNamespace(id=MEENA.id, name=MEENA.name)]


@pytest.fixture
def reader(monkeypatch):
    """Everything the three stage routes touch besides the lines under test.

    ``workshop_summary`` is stubbed rather than fed a manufactured record: this file is about
    provenance, and a column added to the workshop header should not break it. The provenance lines
    themselves are NOT stubbed anywhere — every test below runs the real ``_stages_payload``,
    ``_provenance_maps`` and ``resolve_display_names``.
    """
    users = _Users()
    entries = _entries()
    monkeypatch.setattr("app.core.db.db", SimpleNamespace(user=users))

    async def _load(*_a, **_k):
        return SimpleNamespace(id="dw_1", title="Bagru 2026", deletedAt=None)

    async def _rows(_workshop_id, *, stage_key=None):
        return [r for r in entries if stage_key is None or r.stageKey == stage_key]

    async def _definition(*_a, **_k):
        return SimpleNamespace(version="v1", sections=(), fields_by_stage={}, fields=())

    async def _transcripts(*_a, **_k):
        return {}

    monkeypatch.setattr(routes, "load_workshop_or_404", _load)
    monkeypatch.setattr(routes, "entry_rows", _rows)
    monkeypatch.setattr(routes, "load_custom_definition_or_empty", _definition)
    monkeypatch.setattr(routes, "_transcripts_payload", _transcripts)
    monkeypatch.setattr(routes, "workshop_summary", lambda record: {"id": record.id})
    monkeypatch.setattr(routes, "workshop_completeness", lambda *_a, **_k: {})
    return SimpleNamespace(users=users, entries=entries)


def _assert_resolved(bucket_provenance, *, where):
    """The two halves that must survive any reader: the stamps, and the resolved display name.

    Asserting the NAME and not merely the stamp is what catches the reader that serialises
    provenance but forgets to await ``resolve_display_names`` — the failure that produces a panel
    reading "set by (unknown)" for every hydrated field while looking completely healthy.
    """
    assert bucket_provenance["name"]["by"] == MEENA.id, f"{where}: the record's author was lost"
    assert bucket_provenance["name"]["byName"] == MEENA.name, f"{where}: name was not resolved"
    assert bucket_provenance["phone"]["by"] == ASHA.id, f"{where}: the designer's edit was lost"


# --------------------------------------------------------------------------------------
# Reader 1-3: the three stage routes
# --------------------------------------------------------------------------------------


async def test_the_workshop_detail_route_resolves_the_overlay(reader):
    """``GET /design-workshops/{id}`` — the read the web opens a workshop with."""
    out = await routes.get_design_workshop("dw_1", current_user=ASHA)
    stages = out["stages"]
    _assert_resolved(
        stages[COLLECTION_STAGE]["provenance"]["collections"]["participant"]["ent_p1"],
        where="GET /design-workshops/{id}",
    )
    assert stages[SINGLETON_STAGE]["provenance"]["singleton"]["venue"]["by"] == ASHA.id
    assert reader.users.calls == [[MEENA.id]], "one lookup for the whole workshop, not one per row"


async def test_the_stage_list_route_resolves_the_overlay(reader):
    """``GET /design-workshops/{id}/stages`` — the read the handset syncs a whole workshop with."""
    out = await routes.list_stages("dw_1", current_user=ASHA)
    _assert_resolved(
        out["stages"][COLLECTION_STAGE]["provenance"]["collections"]["participant"]["ent_p1"],
        where="GET /design-workshops/{id}/stages",
    )


async def test_the_single_stage_route_resolves_the_overlay(reader):
    """``GET /design-workshops/{id}/stages/{key}`` — the read a stage form opens with.

    This is the route a designer hits most, and it is the one that takes a different path through
    ``_stages_payload`` (it indexes one stage out of the result), so its own assertion is not
    redundant with the two above.
    """
    out = await routes.get_stage("dw_1", COLLECTION_STAGE, current_user=ASHA)
    _assert_resolved(
        out["provenance"]["collections"]["participant"]["ent_p1"],
        where="GET /design-workshops/{id}/stages/{key}",
    )


async def test_a_stage_nobody_has_saved_yet_still_carries_the_bucket(reader):
    """The empty fallback has the same shape as a populated one.

    A client that reads ``provenance`` unconditionally — which both of ours now do — would otherwise
    crash on the one state every workshop passes through on its first day.
    """
    out = await routes.get_stage("dw_1", UNSAVED_STAGE, current_user=ASHA)
    assert out["provenance"] == {"singleton": {}, "collections": {}, "custom": {}}


async def test_every_collection_row_is_addressed_by_its_own_entry_id(reader):
    """Two participants, two stamp maps, matched by id rather than by position.

    The failure a positional map produces is one participant's edits shown against another
    participant's name — in a table that is the proof of who attended a workshop.
    """
    out = await routes.get_stage("dw_1", COLLECTION_STAGE, current_user=ASHA)
    by_entry = out["provenance"]["collections"]["participant"]
    assert set(by_entry) == {"ent_p1", "ent_p2"}
    assert "phone" in by_entry["ent_p1"] and "phone" not in by_entry["ent_p2"]


async def test_the_provenance_bucket_is_beside_the_data_and_never_inside_it(reader):
    """A reserved key inside ``data`` would be reported in ``droppedKeys`` on every single save.

    That field is the one drift signal this repository has — it exists to say "this phone is running
    a newer field registry than the server" — and firing it on every save of every workshop would
    train whoever reads it to ignore it. It would also be echoed straight back into ``data`` by both
    clients on the next save, so the stamps would end up stored as workshop answers.
    """
    out = await routes.get_stage("dw_1", COLLECTION_STAGE, current_user=ASHA)
    for row in out["collections"]["participant"]:
        assert "provenance" not in row
        assert not any(k.startswith("fieldProvenance") for k in row)


# --------------------------------------------------------------------------------------
# Reader 4: the report builder (.docx / .pdf, server and on-device)
# --------------------------------------------------------------------------------------


def test_the_report_builder_resolves_the_overlay():
    """``assemble_workshop_data`` — what every generated document is built from.

    The report does not PRINT attribution today, and carrying it anyway is deliberate: this builder
    is also the on-device report, and a field the server resolves and the phone does not is exactly
    how the two documents drift apart. Loading it while the shape is one line is cheaper than
    retrofitting two builders later.

    The ``_custom`` row is asserted because it is excluded from both data buckets by design — its
    answers reach the report on their own attribute — and keying provenance by entry id is what
    keeps it covered anyway. A bucket-shaped map would have silently dropped it, making the custom
    section the only part of the document with no answer to "who wrote this".
    """
    entries = _entries() + [
        _row("ent_custom", SINGLETON_STAGE, "_custom", {"dyesrc": "Indigo"},
             {"dyesrc": STAMPS["phone"]})
    ]
    record = SimpleNamespace(id="dw_1", title="Bagru 2026")
    data = dw.assemble_workshop_data(record, entries)

    assert data.provenance("ent_p1")["name"]["by"] == MEENA.id
    assert data.provenance("ent_p1")["phone"]["by"] == ASHA.id
    assert data.provenance("ent_custom")["dyesrc"]["by"] == ASHA.id, "the custom row was dropped"
    assert data.provenance("no_such_row") == {}
    assert data.provenance(None) == {}


def test_the_report_load_path_resolves_display_names():
    """``_report_inputs`` awaits the name lookup, and it does so OUTSIDE the gathered waves.

    Its own comment says the three waves may be gathered only because they write DIFFERENT
    attributes of ``data``; a load that also mutated ``field_provenance`` inside a ``gather`` is the
    one shape that is not safe there. Read from the source rather than executed because the
    surrounding function is five queries deep in template resolution — what needs pinning is the
    ORDER, and the order is a property of the text.
    """
    source = Path(routes.__file__).read_text(encoding="utf-8")
    body = source.split("async def _report_inputs(", 1)[1]
    resolve_at = body.index("resolve_display_names(data.field_provenance")
    gather_at = body.index("gather_reads(")
    assert resolve_at < gather_at, "the name lookup must be awaited before the gathered waves"


# --------------------------------------------------------------------------------------
# Reader 5: the admin view
# --------------------------------------------------------------------------------------


async def test_an_admin_sees_both_the_stamps_and_the_canonical_values(reader, monkeypatch):
    """``GET /design-workshops/{id}/provenance`` — "admins and master admins have access to all".

    Two things are asserted together because the endpoint is only worth having if both are true: the
    per-field authorship (which designers also see), AND the canonical comparison (which only this
    route can produce, because once hydrated a copied value is an ordinary string in ``data``).

    ``createdById`` is asserted BESIDE the per-field answer rather than instead of it. It is still a
    true fact — somebody started this row — and showing both is what makes visible the thing the
    feature exists for: a row created by one designer whose fields now belong to other people.
    """
    async def _divergence(rows):
        assert [r.id for r in rows] == ["ent_setup", "ent_p1", "ent_p2"]
        return {"ent_p1": {"name": {
            "stored": "Sita Devi", "canonical": "Sita Devi Meher",
            "diverged": True, "recordDeleted": False,
        }}}

    monkeypatch.setattr(routes, "canonical_divergence", _divergence)
    out = await routes.workshop_provenance("dw_1", current_user=ADMIN)

    row = next(e for e in out["entries"] if e["entryId"] == "ent_p1")
    _assert_resolved(row["fields"], where="GET /design-workshops/{id}/provenance")
    assert row["canonical"]["name"]["diverged"] is True
    assert row["createdById"] == ASHA.id
    assert row["stageKey"] == COLLECTION_STAGE and row["entityKey"] == "participant"


async def test_a_designer_is_refused_the_cross_record_view_with_a_sentence(reader):
    """The stage reads are unaffected; only the canonical comparison is admin-only.

    That comparison crosses out of the workshop into the shared record tables and reports one
    account's data next to another's, which is the line ``is_admin`` draws everywhere else in this
    module. The refusal names the next move, because a designer who hits it needs to know whether to
    ask somebody or to give up.
    """
    with pytest.raises(HTTPException) as exc:
        await routes.workshop_provenance("dw_1", current_user=ASHA)
    assert exc.value.status_code == 403
    assert "master admin" in exc.value.detail
    assert exc.value.detail.strip().endswith(".")


# --------------------------------------------------------------------------------------
# Reader 6: the Android offline store reads the same wire shape
# --------------------------------------------------------------------------------------


async def test_the_wire_shape_the_handset_syncs_is_the_one_it_parses(reader):
    """The phone's stage sync reads ``GET /{id}/stages``; this pins the exact keys it decodes.

    The handset is a reader in this inventory and it is the one that cannot be fixed by a redeploy —
    a build in a cluster with no signal keeps whatever shape it shipped with. So the three key names
    ``DwStageProvenanceDto`` decodes are asserted here, on the server, against the payload the
    server actually emits. If either side is renamed, this fails on the server before a handset in
    the field ever sees it.
    """
    out = await routes.list_stages("dw_1", current_user=ASHA)
    bucket = out["stages"][COLLECTION_STAGE]["provenance"]
    assert set(bucket) == {"singleton", "collections", "custom"}
    stamp = bucket["collections"]["participant"]["ent_p1"]["name"]
    assert set(stamp) >= {"by", "byName", "at", "source"}
    assert stamp["source"] in {"reference", "designer"}


# --------------------------------------------------------------------------------------
# Reader 7: the rating ledger — the one entry on the classified list that is EXEMPT
# --------------------------------------------------------------------------------------
#
# ``services/design_ratings`` reads ``DwStageEntry`` twice and resolves nothing, and the tripwire
# below lists it for that reason. THE EXEMPTION IS NOT AN OMISSION AND IT IS ALSO NOT FREE, so it
# gets tests of its own — not tests that it resolves the overlay, but tests of the BOUNDARY the
# exemption was granted inside. The full argument is above ``design_ratings.load_subject``; the two
# halves that matter here are:
#
#   * WHAT IT TAKES OFF A ROW IS A LABEL AND A GATE. The label is the same object the intra-workshop
#     REF picker is already exempt for — one display string off ``label_field``, attributing nothing
#     — and the gate (``peerRoundClosedAt``) is read to decide whether a round is open and then
#     never served to anybody. A THIRD field off ``data`` is the change that makes all of that false,
#     so that is what these tests fail on.
#   * RESOLVING PROVENANCE HERE WOULD BE THE WORSE OPTION, not merely the more expensive one. The
#     POOL round is read by designers ``load_workshop_or_404`` turns away, and this module withholds
#     identities from them on purpose (``POOL_RATINGS_NAME_THEIR_RATER`` is False). A stamp carries
#     ``by`` and ``byName``, so serving one here would export the researcher who recorded a value,
#     and the designer who typed over it, to accounts with no grant on the workshop.
#
# Nothing here needs a database either: both loaders are run against a recorded stub, exactly as
# ``_Users`` above stands in for the one name lookup.

#: The sketch both loaders are run over. REAL stage-11 ``sketch`` keys, so a rename in the registry
#: fails these tests instead of letting them pass against a fiction — checked mechanically by
#: :func:`test_the_keys_this_fence_is_built_on_are_real_registry_fields`.
#:
#: NO ``stageKey``, deliberately: neither loader looks at one. They scope by ``entityKey``, and that
#: scoping is itself asserted below.
SKETCH_DATA = {
    "sketchNo": "SK-07",
    "name": "Kalamkari tote",
    "targetMarket": "Urban gifting",
    "materials": ["Indigo cotton"],
    dr.POOL_OPENS_WHEN_FIELD: "2026-05-04",
}

#: The values in ``SKETCH_DATA`` that are neither the label nor the gate. One of these reaching a
#: caller is the widening this whole section exists to catch.
#:
#: ``sketchNo`` is NOT in here even though it is not the label today: ``_entry_label`` names it as
#: the fallback for a sketch saved in a hurry with a number and no name, so it is a label candidate
#: by declaration and pinning its absence would fail on a legitimate edit to that chain.
NOT_THE_LABEL = ("Urban gifting", "Indigo cotton")

#: The workshop row ``load_subject`` re-reads and ``load_subjects`` is handed. Its creator is a
#: DIFFERENT account from the row's, because ``RatingSubject`` keeps the two apart and a fixture
#: where they coincide cannot show that it does.
RATED_WORKSHOP = SimpleNamespace(id="dw_1", createdById=MEENA.id, deletedAt=None)


def _sketch_row(entry_id, *, ordinal=0):
    """One rateable row, STAMPED — which is the whole point of the fixture.

    A row with an empty ``fieldProvenance`` could not tell "this reader dropped the stamps" from
    "there were no stamps to carry", and it is the first of those two that these tests are about.
    """
    return SimpleNamespace(
        id=entry_id, entityKey="sketch", designWorkshopId="dw_1", ordinal=ordinal,
        data=dict(SKETCH_DATA), clientKey=None, createdById=ASHA.id, deletedAt=None,
        fieldProvenance={
            "name": STAMPS["name"], dr.POOL_OPENS_WHEN_FIELD: STAMPS["phone"],
        },
    )


class _Entries:
    """The stage-entry delegate, recording every where-clause so the SCOPE can be asserted too.

    Canned answers rather than a re-implementation of Prisma's filtering, for ``_Users``' reason:
    what is under test is the call this module makes and what it does with the row it gets back,
    and a hand-written query engine in a fixture is a second thing that can be wrong.
    """

    def __init__(self, rows):
        self.rows = list(rows)
        self.unique_calls: list[dict] = []
        self.many_calls: list[tuple[dict, dict]] = []

    async def find_unique(self, where=None):
        self.unique_calls.append(dict(where or {}))
        wanted = (where or {}).get("id")
        return next((row for row in self.rows if row.id == wanted), None)

    async def find_many(self, where=None, order=None):
        self.many_calls.append((dict(where or {}), dict(order or {})))
        return list(self.rows)


@pytest.fixture
def ratings_db(monkeypatch):
    """``design_ratings.db``, stubbed. Returns the recorder so the calls can be read back."""
    entries = _Entries([_sketch_row("ent_sk1"), _sketch_row("ent_sk2", ordinal=1)])

    async def _workshop(where=None):
        return RATED_WORKSHOP if (where or {}).get("id") == RATED_WORKSHOP.id else None

    monkeypatch.setattr(dr, "db", SimpleNamespace(
        dwstageentry=entries, designworkshop=SimpleNamespace(find_unique=_workshop),
    ))
    return entries


def _assert_only_a_label_and_a_gate(subject, *, where):
    """The fence itself, in the shape ``_assert_resolved`` has for the readers that do resolve.

    Everything at once, because the exemption needs all of it to stay true: the label is the label
    field, the gate was read, ``RatingSubject`` has not grown an attribute, and NOTHING ELSE off the
    row came along — no third answer, and no stamp carried half-way. A stamp arriving here
    unresolved would be the "set by (unknown)" panel ``_assert_resolved`` describes, on a payload
    that goes to strangers.

    The attribute-set assertion is the one that catches the widening most likely to actually happen
    — a fourth fact read off ``data`` and hung on the dataclass — and the value scan behind it is
    for the sneakier version, where an attribute that already exists starts carrying an answer.
    """
    assert subject.label == SKETCH_DATA["name"], f"{where}: the label is not the label field"
    assert subject.pool_open is True, f"{where}: the pool gate was not read"
    assert set(type(subject).__dataclass_fields__) == {
        "entry_id", "entity_key", "workshop_id", "pool_open", "label", "ordinal",
        "author_id", "workshop_author_id",
    }, f"{where}: RatingSubject grew an attribute — is it a stage-entry field?"
    served = repr(subject)
    for value in NOT_THE_LABEL:
        assert value not in served, (
            f"{where}: {value!r} came off the stage row with no provenance. This reader is exempt "
            "for carrying a label and a gate only — see above design_ratings.load_subject."
        )
    assert "fieldProvenance" not in served and "byName" not in served, (
        f"{where}: a stamp was carried without being resolved"
    )


async def test_the_rating_ledger_takes_a_label_and_a_gate_and_no_third_field(ratings_db):
    """``design_ratings.load_subject`` — the read behind every single-subject rating route."""
    subject = await dr.load_subject("ent_sk1")
    assert subject is not None
    _assert_only_a_label_and_a_gate(subject, where="load_subject")
    # THE ROW-LEVEL FACTS IT DOES KEEP, and they are not field provenance: ``createdById`` says who
    # started the row, which is what the self-rating refusal and "their own record" read. Both are
    # kept apart on purpose — see the RatingSubject docstring — and neither reaches the wire, which
    # is what the payload test below asserts.
    assert subject.author_id == ASHA.id
    assert subject.workshop_author_id == MEENA.id
    assert ratings_db.unique_calls[0] == {"id": "ent_sk1"}


async def test_the_ranked_list_loader_takes_a_label_and_a_gate_and_no_third_field(ratings_db):
    """``design_ratings.load_subjects`` — the read behind the ranked list, its own entry point.

    Asserted separately from ``load_subject`` for this file's stated reason: a generic test over
    whichever helper they happen to share today would pass on the day one of them stopped sharing
    it. It also pins the SCOPE, because "not soft-deleted" and "this entity" are the two clauses
    that decide which rows a pool reader can reach at all.
    """
    subjects = await dr.load_subjects("dw_1", "sketch", RATED_WORKSHOP)
    assert [s.entry_id for s in subjects] == ["ent_sk1", "ent_sk2"]
    for subject in subjects:
        _assert_only_a_label_and_a_gate(subject, where="load_subjects")
    where, order = ratings_db.many_calls[0]
    assert where == {"designWorkshopId": "dw_1", "entityKey": "sketch", "deletedAt": None}
    assert order == {"ordinal": "asc"}


def test_no_stage_entry_field_and_no_identity_reaches_the_ranked_payload():
    """``ranked_payload`` — the wire shape, fenced at its WIDEST.

    ``show_ordinal=True`` is the disclosure the payload only makes to the workshop's own party and
    admins, so it is the version to pin: if the biggest payload carries no stage-entry answer and
    names nobody, no narrower one can.
    """
    subject = dr.RatingSubject(
        entry_id="ent_sk1", entity_key="sketch", workshop_id="dw_1", pool_open=True,
        label=SKETCH_DATA["name"], ordinal=3, author_id=ASHA.id, workshop_author_id=MEENA.id,
    )
    payload = dr.ranked_payload(dr.rank([subject], [])[0], mine=None, show_ordinal=True)
    assert set(payload) == {
        "subjectId", "entityKey", "label", "workshopId", "score", "ratingCount",
        "defaultPosition", "placedPosition", "myRating", "ordinal",
    }
    served = repr(payload)
    for value in NOT_THE_LABEL:
        assert value not in served, f"{value!r} reached a pool reader with no provenance"
    # NOBODY IS NAMED. The row's author is a permission input, not a disclosure — and a provenance
    # stamp would be a disclosure, to accounts holding no grant on this workshop.
    assert ASHA.id not in served and MEENA.id not in served
    assert "provenance" not in served and "byName" not in served


def test_the_keys_this_fence_is_built_on_are_real_registry_fields():
    """``SKETCH_DATA`` is stage 11's ``sketch``, not a plausible-looking invention.

    Without this, a registry rename would leave the three tests above passing over a fixture whose
    keys no longer exist anywhere — green, and measuring nothing.
    """
    from app.services.stage_schema import all_entities

    sketch = next(entity for _stage, entity in all_entities() if entity.key == "sketch")
    keys = {f.key for f in sketch.fields}
    assert set(SKETCH_DATA) <= keys, f"not registry fields: {sorted(set(SKETCH_DATA) - keys)}"
    assert sketch.label_field == "name", "the label this reader prints is no longer the label field"
    assert dr.POOL_OPENS_WHEN_FIELD in keys, "the pool gate is not declared on a sketch"


# --------------------------------------------------------------------------------------
# The other half of the inventory: the record tables, which have no overlay
# --------------------------------------------------------------------------------------


async def test_reading_the_repository_is_open_for_every_role():
    """``viewable_where`` is empty for everyone, which is what makes the records genuinely SHARED.

    This is the guard on the finding that made most of this feature unnecessary: the sharing half
    already exists. If a future change narrows reading per designer, the copy-on-write model
    underneath these tests changes shape — a designer would then have rows they cannot see at all,
    and "the canonical value" would stop being a single answer — so it must not happen quietly.

    Every rank is checked, including the authentication floor, because the policy is stated as "for
    every signed-in account" and a narrowing would most plausibly arrive at the bottom.
    """
    from app.services.records import viewable_where

    for role in ("MASTER_ADMIN", "ADMIN", "PROFESSOR", "DESIGNER", "RESEARCHER",
                 "FIELD_CONTRIBUTOR", "CROWDSOURCE_VOLUNTEER"):
        user = SimpleNamespace(id=f"usr_{role}", name=role, role=role)
        assert await viewable_where(user) == {}, f"reading narrowed for {role}"
        assert await viewable_where(user, owner_field="uploadedById") == {}


def test_no_record_reader_can_serve_a_stale_value_because_there_is_no_overlay():
    """**THE HONEST ANSWER FOR THE OTHER HALF OF THE INVENTORY, MADE MECHANICAL.**

    The data browser, the CSV exports, the .xlsx workbook, the dataset zip and stream, and the API
    list/detail routes all read ``Artisan``/``ProductDocumentation``/``ToolDocumentation``/
    ``Process``/``Craft``/``Workshop`` rows directly. None of them needs a resolution step, for one
    reason only: there is exactly one row per record and no per-designer copy of it anywhere in the
    schema. A reader cannot serve a stale value when there is no second value.

    So instead of a resolution layer nobody needs, this asserts the PREMISE — that no per-designer
    overlay table has appeared beside those six models. The day somebody adds one, every reader in
    the paragraph above becomes wrong at once, and this is the test that says so rather than a
    support ticket about two screens disagreeing.

    Matched on the naming shapes an overlay table would plausibly take. It is a tripwire, not a
    proof; its value is that it fails in the same commit as the change, next to this docstring.
    """
    schema = (Path(__file__).resolve().parents[1] / "prisma" / "schema.prisma").read_text(
        encoding="utf-8"
    )
    models = {
        line.split()[1] for line in schema.splitlines() if line.startswith("model ")
    }
    shared = ("Artisan", "Product", "Tool", "Process", "Craft", "Workshop")
    suspicious = sorted(
        m for m in models
        if any(m.startswith(s) for s in shared)
        and any(token in m for token in ("Overlay", "Patch", "Override", "PerDesigner", "Draft"))
    )
    assert suspicious == [], (
        f"{suspicious} looks like a per-designer copy of a shared record. If that is what it is, "
        "every reader listed in this test's docstring now has two values to choose between and "
        "none of them chooses. See services/entry_provenance.py."
    )


# --------------------------------------------------------------------------------------
# Reader 8: the inspector's read-only view
# --------------------------------------------------------------------------------------


@pytest.fixture
def inspection(reader, monkeypatch):
    """The inspections route's own doubles, over the same three rows the other readers use.

    It needs a fixture of its own rather than reusing ``reader``: that one patches names on
    ``design_workshops``, and this route imported ITS copies at module load
    (``from app.services.design_workshops import entry_rows, …``), so a patch on the source module
    would not be seen here. The provenance lines are not stubbed — ``_stages_payload``,
    ``_provenance_maps`` and ``resolve_display_names`` are the real ones, imported from the very
    module the other readers exercise.

    ``load_inspectable_workshop_or_404`` is the only loader replaced, and replacing it is what keeps
    this test about provenance instead of about the scope query: whether the INSPECTOR row exists is
    ``test_dw_inspector_scope.py``'s subject, and asserting it twice in two files is how the two
    drift apart.
    """
    # THIS FIXTURE BUILDS ITS OWN STAMPS AND DOES NOT TOUCH THE MODULE-LEVEL `STAMPS`, AND THAT IS
    # THE WHOLE OF WHY THIS TEST IS TRUSTWORTHY.
    #
    # `_row` stores `dict(stamps)` — a SHALLOW copy — so every row built from `_entries()` shares the
    # one `STAMPS["name"]` dict, and `resolve_display_names` fills `byName` IN PLACE. Whichever test
    # runs first resolves that shared dict for the rest of the session, and every later test then
    # inherits a name its own reader never produced.
    #
    # TWO WEAKER FIXES WERE TRIED AND BOTH WERE WRONG, so do not "simplify" back to either. Reusing
    # `_entries()` made this test pass while proving nothing: `wanted` came out empty,
    # `resolve_display_names` returned before it queried, and a route that never called it at all
    # would have looked identical. `copy.deepcopy(_entries())` did not fix it either — a deep copy
    # faithfully reproduces whatever the global holds AT THAT MOMENT, which by then is the resolved
    # name. Restoring `byName` after the copy fixed the module-alone run and still left this test
    # ordering-dependent in the full suite, because it keeps reading a dict other tests mutate.
    #
    # Owning the data outright is the only version that cannot be perturbed by what ran before.
    reference = {
        "by": MEENA.id, "byName": None, "at": "2026-03-01T09:00:00+00:00",
        "source": ep.SOURCE_REFERENCE, "refModel": "Artisan", "refId": "art_1", "refKey": "name",
    }
    designer = {
        "by": ASHA.id, "byName": ASHA.name, "at": "2026-03-08T15:30:00+00:00",
        "source": ep.SOURCE_DESIGNER,
    }
    fresh = [
        _row("ent_setup", SINGLETON_STAGE, "workshopSetup", {"venue": "Barpali"},
             {"venue": dict(designer)}),
        _row("ent_p1", COLLECTION_STAGE, "participant",
             {"name": "Sita Devi", "phone": "9000011111"},
             {"name": dict(reference), "phone": dict(designer)}, ordinal=0),
        _row("ent_p2", COLLECTION_STAGE, "participant",
             {"name": "Kamla Bai"}, {"name": dict(reference)}, ordinal=1),
    ]

    async def _load(*_a, **_k):
        return SimpleNamespace(id="dw_1", title="Bagru 2026", deletedAt=None)

    async def _rows(_workshop_id, *, stage_key=None):
        return [r for r in fresh if stage_key is None or r.stageKey == stage_key]

    async def _definition(*_a, **_k):
        return SimpleNamespace(version="v1", sections=(), fields_by_stage={}, fields=())

    monkeypatch.setattr(inspections, "load_inspectable_workshop_or_404", _load)
    monkeypatch.setattr(inspections, "entry_rows", _rows)
    monkeypatch.setattr(inspections, "load_definition_or_empty", _definition)
    monkeypatch.setattr(inspections, "workshop_summary", lambda record: {"id": record.id})
    monkeypatch.setattr(inspections, "workshop_completeness", lambda *_a, **_k: {})
    return reader


async def test_the_inspector_read_resolves_the_overlay(inspection):
    """``GET /design-workshop-inspections/{id}`` — the read-only twin of the designer's detail read.

    This route was added by the INSPECTOR wave and went unclassified until the tripwire below caught
    it, which it could only do once ``records.py``'s stray carriage return stopped breaking this
    module's tokenizer. So the assertion here is the one the wave never wrote: an inspector opening
    somebody else's workshop sees the AUTHOR of each field, not "(unknown)".
    """
    out = await inspections.read_workshop_under_inspection("dw_1", current_user=INSPECTOR)
    _assert_resolved(
        out["stages"][COLLECTION_STAGE]["provenance"]["collections"]["participant"]["ent_p1"],
        where="GET /design-workshop-inspections/{id}",
    )
    assert out["stages"][SINGLETON_STAGE]["provenance"]["singleton"]["venue"]["by"] == ASHA.id
    assert inspection.users.calls == [[MEENA.id]], "one lookup for the whole workshop, not one per row"


async def test_the_inspector_read_says_on_the_wire_that_it_is_read_only(inspection):
    """``readOnly`` travels in the payload, and this is the reader that must not lose it.

    The route's own comment gives the reason — both clients will eventually render this through the
    same screen as the designer's read, and a screen that cannot tell the two apart offers a Save
    button the API answers 404 to. It is asserted beside the provenance because they arrive from the
    same handler and a refactor that rebuilt the payload would drop them together.
    """
    out = await inspections.read_workshop_under_inspection("dw_1", current_user=INSPECTOR)
    assert out["readOnly"] is True


# --------------------------------------------------------------------------------------
# Reader 9: the data browser's design-workshop tables — EXEMPT, and fenced
# --------------------------------------------------------------------------------------
#
# ``/data`` grew a design-workshop half on 2026-08-31 (``docs/DECISION-design-workshop-data-in-
# view-data.md``), so ``api/routes/data_browser.py`` now reads ``DwStageEntry`` and the tripwire
# below caught it. The classification is EXEMPT, on the REF picker's grounds: it serves VALUES and
# attributes nothing to anybody. There is no author column in the tree's text files, none in the
# workbook, and none in the JSON the web panel renders — so there is no "who wrote this" answer for
# it to get wrong, and nothing for a stale stamp to be stale IN.
#
# THE EXEMPTION IS ABOUT WHAT IT SHOWS, NOT ABOUT WHAT IT READS, so it has to be fenced rather than
# asserted once. The moment this surface prints an author beside a value it becomes the reader this
# file exists for, and it would be an easy and well-meant addition — a research export naming who
# recorded each answer is a reasonable thing to want. The two tests below fail on the day it is
# added, in the same commit, next to this paragraph, which is the cheapest place to find out.
#
# WHAT SUCH A CHANGE WOULD OWE. ``DwStageEntry.fieldProvenance`` is SPARSE and its ``byName`` is
# resolved by ``entry_provenance.resolve_display_names`` — a lookup, not a column — so an export
# that read the raw stamp would print user ids for some rows, "(unknown)" for the rows written
# before the column existed, and the REFERENCED record's author rather than the designer's for the
# 81 copied field-pairs. Getting that right is the work this exemption is deferring, not skipping.


def _dw_row_keys():
    """Every column key one flattened design-workshop row can carry, over the whole registry."""
    keys = {column.key for column in dwdata.WORKSHOP_IDENTITY_COLUMNS}
    keys |= {"entry.id", "entry.ordinal"}
    for table in dwdata.tables():
        keys |= {column.key for column in table.columns}
    return keys


def test_the_data_browser_attributes_no_stage_field_to_anybody():
    """The fence. A flattened row carries values and identity, and no authorship of any kind.

    Built over a row whose ``fieldProvenance`` is POPULATED, so this fails if the flattener ever
    starts copying the stamp through rather than merely ignoring it today.
    """
    stage = next(s for s in dwdata.stages() if s.entities)
    entity = stage.entities[0]
    field = entity.fields[0]
    entry = SimpleNamespace(
        id="ent_1",
        entityKey=entity.key,
        ordinal=0,
        data={field.key: "a value"},
        fieldProvenance={field.key: {"by": ASHA.id, "byName": ASHA.name, "source": "designer"}},
    )
    record = SimpleNamespace(id="dw_1", title="Bagru 2026")

    grouped, _unknown = dwdata.flatten(record, [entry])
    row = grouped[entity.key][0]

    assert set(row) <= _dw_row_keys(), (
        "a flattened row grew a column outside the registry and the workshop identity — if it is an "
        "author, this reader is no longer exempt and owes `entry_provenance` (see Reader 9 above)"
    )
    for key in row:
        assert "provenance" not in key.lower(), key
    assert ASHA.id not in " ".join(row.values())
    assert ASHA.name not in " ".join(row.values())


def test_the_design_workshop_sheets_carry_no_author_column():
    """The same fence one level up, on the workbook the researcher actually downloads.

    Asserted on the HEADERS rather than on the cells, because a column added with every cell empty
    on this fixture would slip past a value check and still be the change this fence is for.
    """
    stage = next(s for s in dwdata.stages() if s.entities)
    entity = stage.entities[0]
    field = entity.fields[0]
    data = {key: [] for key in browser._REPORT_KEYS}
    data["designWorkshops"] = [
        SimpleNamespace(id="dw_1", title="Bagru 2026", workshopCode="DW-1")
    ]
    data["dwEntries"] = [
        SimpleNamespace(
            id="ent_1",
            designWorkshopId="dw_1",
            entityKey=entity.key,
            ordinal=0,
            data={field.key: "a value"},
            fieldProvenance={field.key: {"by": ASHA.id, "byName": ASHA.name}},
        )
    ]

    banned = {"author", "recorded by", "written by", "provenance", "by name", "byname"}
    for sheet in browser._dw_sheets(data):
        for column in sheet["columns"]:
            assert str(column).strip().lower() not in banned, (
                f"sheet {sheet['name']!r} has an authorship column {column!r} — see Reader 9"
            )
        for row in sheet["rows"]:
            joined = " ".join(str(cell) for cell in row)
            assert ASHA.id not in joined and ASHA.name not in joined


#: Comments and string literals are not code, and matching them made this check cry wolf.
#:
#: `app/services/design_workshop_grants.py` was reported as an unclassified reader of
#: `DwStageEntry` on 2026-08-24 while containing NO read of it at all: its `_may_capture_for`
#: docstring explains that the predicate is a WRITE rule and that the row-level read filter
#: (``entry_rows(..., author_id=...)``) is the other half of the pair. Naming the loader in order
#: to say "this is not the loader" was enough to be accused of calling it.
#:
#: The two available fixes are not equivalent. Adding the module to the classified list would have
#: certified a reader that does not exist, and — because the list is what suppresses the failure —
#: would have silenced this check for that file on the day it really did start reading the table,
#: which is precisely the wave the docstring above warns about. So the DETECTOR is fixed instead.
#:
#: The trade, stated: a module reaching the table through a string (``getattr(db, "dwstageentry")``)
#: is now invisible here. It always was — that spelling matches neither pattern — so nothing is lost
#: that was previously held, and prose stops counting as a call.
_PROSE_TOKENS = {tokenize.COMMENT, tokenize.STRING} | {
    getattr(tokenize, name)
    for name in ("FSTRING_START", "FSTRING_MIDDLE", "FSTRING_END")
    if hasattr(tokenize, name)
}


# --------------------------------------------------------------------------------------
# Readers 10 and 11: the dataset archive and the search bucket - EXEMPT, and fenced
# --------------------------------------------------------------------------------------
#
# Both landed on 2026-08-31, in the same wave, and the tripwire below caught BOTH - which is that
# tripwire doing exactly its job twice in one day, and worth recording as evidence that it earns its
# keep rather than as an inconvenience somebody silenced.
#
# ``api/routes/export.py`` grew the design-workshop half of the repository archive; ``api/routes/
# search.py`` grew the sixth search bucket and, with it, a match against ``DwStageEntry.searchText``.
# Both are EXEMPT on Reader 9's grounds - they serve VALUES and MATCHES and attribute nothing to
# anybody - and both are FENCED here rather than merely named, because the exemption is a claim about
# what a surface SHOWS and not about what it reads.
#
# THE TWO ARE EXEMPT FOR SLIGHTLY DIFFERENT REASONS, AND THE DIFFERENCE MATTERS TO A LATER READER.
#
#   ``export.py`` renders through ``design_workshop_data.flatten`` - the same flattener Reader 9
#   already fences - so its ROWS cannot carry an author unless the flattener does, and the test above
#   fails first if it ever does. What is fenced HERE is the archive's own prose: a details block, a
#   note or a folder name could name a person without the flattener being touched at all, and both
#   of Reader 9's tests would stay green while it happened.
#
#   ``search.py`` never reads ``data`` OR ``fieldProvenance``. It answers "which (workshop, stage)
#   pairs matched" with ``group_by`` over exactly two columns and a count, and emits stage LABELS.
#   There is no value in its answer for an author to sit beside. The fence is therefore aimed at the
#   one change that would alter that: reading a row instead of grouping it.
#
# WHAT A CHANGE HERE WOULD OWE is unchanged from Reader 9 and is deliberately not restated:
# ``fieldProvenance`` is sparse, its ``byName`` is a lookup rather than a column, and the copied
# field-pairs carry the REFERENCED record's author rather than the designer's.


def _module_code(relative: str) -> str:
    """One route module's source with comments and strings blanked, lowercased.

    The same treatment :func:`_reads_stage_entries` gives, through the same helper, so a fence that
    matched inside a comment cannot fire on the very paragraph above explaining the fence.
    """
    return _blanked_code(Path(__file__).resolve().parents[1] / relative)


def _module_source(relative: str) -> str:
    """One route module's RAW source, lowercased — comments and strings included.

    ── WHY BOTH READINGS EXIST, AND WHY THE BLANKED ONE ALONE WAS NOT ENOUGH ──────────────────────

    :func:`_blanked_code` is right for asking "does this module CALL something", which is what the
    tripwire asks. It is WRONG for asking "does this module touch the provenance stamp", and the
    difference was measured rather than reasoned: ``app/services/entry_provenance.py`` — the module
    the whole stamp belongs to — contains ``fieldProvenance`` five times and NONE of them survives
    blanking, because every one is a Prisma field NAME inside a string literal.

    So a fence built on the blanked reading would catch ``entry.fieldProvenance`` (attribute access)
    and sail straight past ``{"fieldProvenance": True}`` in a ``select`` or a ``where`` — which is
    precisely how a route asks Prisma for the column, i.e. the likelier of the two spellings and the
    one that actually pulls the data.

    The raw reading catches both. Its cost is that a mere MENTION in a comment fires it, and that
    cost is accepted deliberately: neither module names the stamp anywhere today (counted, both zero),
    and somebody writing a sentence about provenance in a route that is classified EXEMPT for not
    touching provenance is exactly the moment this file wants to be read.
    """
    return (Path(__file__).resolve().parents[1] / relative).read_text(encoding="utf-8").lower()


def test_the_dataset_archive_attributes_no_stage_field_to_anybody():
    """The fence on the archive a researcher actually downloads.

    The ROWS are covered by Reader 9's fence over ``dwdata.flatten``. What is asserted here is that
    the module itself never reaches for the stamp - so a details block or a note cannot start naming
    an author while every row stays clean and both of those tests stay green.
    """
    source = _module_source("app/api/routes/export.py")
    assert "fieldprovenance" not in source, (
        "export.py names the provenance stamp. A dataset archive naming who recorded each answer is "
        "a reasonable thing to want, and it is no longer exempt - see Readers 9 to 11 above for what "
        "such a change owes."
    )
    assert "resolve_display_names" not in source, (
        "export.py resolves author display names, so this reader is no longer exempt - see Readers 9 "
        "to 11 above."
    )


def test_the_search_bucket_reads_no_stage_ROW_and_so_can_name_nobody():
    """The fence on the sixth search bucket.

    ``group_by`` and not ``find_many`` is the whole of why this reader is exempt, and it is also a
    scale decision the route argues for itself: reading rows to answer "which stages matched" would
    pull ``data`` - the largest JSON column in the schema - for up to ``pageSize`` times twenty-two
    rows, across the wire, to look at two of their columns. That it cannot attribute and that it does
    not haul the payload are the same fact, so this asserts it once.
    """
    code = _module_code("app/api/routes/search.py")
    assert "db.dwstageentry.group_by" in code, (
        "search.py no longer groups the stage hits. A reader that takes ROWS can see `data` and "
        "`fieldProvenance`, and is no longer exempt - see Readers 9 to 11 above."
    )
    assert "db.dwstageentry.find_many" not in code, (
        "search.py reads whole stage rows. Besides the provenance question, that pulls the largest "
        "JSON column in the schema across the wire to look at two of its columns - the route's own "
        "comment argues against exactly this."
    )
    # The RAW source for this one — see `_module_source` for the measurement that forced it: the
    # stamp reaches a route as a Prisma field NAME in a string, which blanking erases.
    assert "fieldprovenance" not in _module_source("app/api/routes/search.py"), (
        "search.py names the provenance stamp and is no longer exempt - see Readers 9 to 11 above."
    )


def _blanked_code(path: Path) -> str:
    """One module's source with comment and string spans blanked out, lowercased.

    Blanked rather than REMOVED, so every remaining character keeps its original offset and a
    match cannot be manufactured by two lines being joined together.

    Extracted from :func:`_reads_stage_entries` on 2026-08-31, when Readers 10 and 11 needed the
    same treatment. A second hand-rolled blanker is how two fences come to disagree about what
    counts as code, and this file's whole subject is readers that disagree.
    """
    text = path.read_text(encoding="utf-8")
    line_starts, offset = [], 0
    for line in text.splitlines(keepends=True):
        line_starts.append(offset)
        offset += len(line)
    chars = list(text)
    with open(path, "rb") as handle:
        for token in tokenize.tokenize(handle.readline):
            if token.type not in _PROSE_TOKENS:
                continue
            (start_row, start_col), (end_row, end_col) = token.start, token.end
            if start_row - 1 >= len(line_starts) or end_row - 1 >= len(line_starts):
                continue
            start = line_starts[start_row - 1] + start_col
            end = min(line_starts[end_row - 1] + end_col, len(chars))
            for index in range(start, end):
                if chars[index] != "\n":
                    chars[index] = " "
    return "".join(chars).lower()


def _reads_stage_entries(path: Path) -> bool:
    """Whether this module reads ``DwStageEntry`` IN CODE, both ways in."""
    code = _blanked_code(path)
    return "db.dwstageentry." in code or "entry_rows(" in code


def test_no_new_reader_of_stage_entries_appeared_without_resolving_provenance():
    """**THE TRIPWIRE FOR THE READER NOBODY REMEMBERED TO ADD TO THIS FILE.**

    The whole risk in a copy-on-write model is the reader you miss: it serves one answer while the
    screen beside it serves another, and nothing raises because both look like data. Every test above
    covers a reader that exists TODAY, which is exactly the coverage that cannot protect against the
    reader added next quarter.

    So this asserts the SHAPE of the inventory instead of its contents: which modules are allowed to
    read ``DwStageEntry`` at all. Each name below was visited when this feature was built and either
    wired to ``entry_provenance`` or deliberately left alone with a reason:

    ``api/routes/design_workshops``  the three stage reads and the admin view — all wired.
    ``services/design_workshops``    the save path (writes the column) and ``assemble_workshop_data``
                                     (wired); plus the intra-workshop REF picker, which returns
                                     dropdown LABELS and attributes nothing to anybody.
    ``api/routes/analytics``         cross-workshop counts and ratios. It never shows one field to
                                     one person, so there is nothing to attribute.
    ``services/dictation_consent``   collects media ids off the entries; reads no field value.
    ``services/design_ratings``      the review ledger's two subject loaders. EXEMPT on the REF
                                     picker's grounds AND on one of its own: it takes a display
                                     label, a pool gate it never serves, and ``createdById`` — and
                                     a stamp's ``byName`` would cross to the pool readers this
                                     module deliberately shows no names to. Argued in full above
                                     ``design_ratings.load_subject``; the exemption's boundary is
                                     fenced by the four tests under "Reader 7" above, which fail if
                                     either loader ever takes a third field off ``data``.

    A module not on this list that starts reading the table is a reader nobody has classified, and
    the failure it produces is silent. Failing here — in the same commit, next to this docstring —
    is the cheapest place to find out. Adding a name is a one-line change and is the right fix; it
    just has to be a deliberate one, with the reader's own test added above.

    THE EXPORTS ARE THE REASON THIS LIST IS SHORT, AND ONE OF THEM STOPPED BEING TRUE ON
    2026-08-31. This paragraph read: "the dataset zip, the CSV downloads, the .xlsx workbook and the
    data browser read the shared RECORD tables and never touch ``DwStageEntry``. They therefore
    cannot serve a stale stage-entry value — there is no path from them to one." That still holds
    for the dataset zip and the CSV downloads. It no longer holds for the DATA BROWSER: ``/data``
    gained a design-workshop taxonomy and design-workshop sheets, and it reads the table directly.
    It is on the list above, EXEMPT and fenced — see "Reader 9". The .xlsx workbook is the same
    module, so the same classification covers it.
    """
    #: BOTH WAYS IN ARE MATCHED, because they are two different mistakes. ``db.dwstageentry`` is a
    #: module going straight to the table, and ``entry_rows(`` is one using the shared loader — which
    #: is the RIGHT thing to do and is exactly why it is easy to add a reader through it without
    #: noticing. A check that watched only the raw delegate would miss every reader that behaved well.
    allowed = {
        "app/api/routes/design_workshops.py",
        "app/api/routes/analytics.py",
        # Classified 2026-08-27, when this check first ran against the INSPECTOR wave. It reads the
        # table through `entry_rows` and DOES resolve provenance — see the two tests in "Reader 8"
        # above, which is the order this list requires: classify by writing the test, never by
        # adding the name.
        "app/api/routes/design_workshop_inspections.py",
        "app/services/design_workshops.py",
        "app/services/dictation_consent.py",
        "app/services/design_ratings.py",
        # Classified 2026-08-31, when ``/data`` grew its design-workshop half. EXEMPT: it serves
        # VALUES and attributes nothing to anybody — no author column in the tree, the workbook or
        # the JSON. The exemption is FENCED by the two tests under "Reader 9" above, which fail if
        # this surface ever prints an author beside a value, and the paragraph there says what such
        # a change would owe.
        "app/api/routes/data_browser.py",
        # Classified 2026-08-31, in the same wave, when the repository archive grew its
        # design-workshop half and the sixth search bucket landed. BOTH EXEMPT and both FENCED
        # by the two tests under "Readers 10 and 11" above - the archive renders through the
        # already-fenced flattener and names nobody in its own prose, and the search bucket
        # groups by (workshop, stage) without ever reading a row.
        "app/api/routes/export.py",
        "app/api/routes/search.py",
    }
    root = Path(__file__).resolve().parents[1] / "app"
    found = set()
    for path in root.rglob("*.py"):
        if _reads_stage_entries(path):
            found.add(str(path.relative_to(root.parent)).replace("\\", "/"))
    unclassified = sorted(found - allowed)
    assert unclassified == [], (
        f"{unclassified} reads DwStageEntry and is not in this test's classified list. Decide "
        "whether it must resolve field provenance (services/entry_provenance), add a test for it "
        "beside the others in this file, and then add it here."
    )
    # AND THE DETECTOR MUST STILL DETECT, which is the failure mode the line above cannot see.
    # `unclassified` is a set difference, so anything that stops `_reads_stage_entries` finding
    # readers at all — a tokenizer that blanks too much, a spelling that changed — empties `found`
    # and makes this test pass while checking nothing. Every name on the list is a module known to
    # read the table, so every name on the list must come back.
    undetected = sorted(allowed - found)
    assert undetected == [], (
        f"{undetected} is on the classified list and the detector no longer finds it. This check "
        "reports what is NOT on the list, so a detector that finds nothing reports nothing: fix "
        "`_reads_stage_entries`, and do not delete these names to make it quiet."
    )


def test_record_level_field_provenance_is_still_the_records_own_and_was_not_duplicated_here():
    """The record tables' provenance stays in ``records.merge_field_provenance``. One rule, one home.

    ``entry_provenance`` is for ``DwStageEntry`` — rows that hold COPIES of shared records and had no
    authorship answer at all. The record tables already had one, on all six types, with a frontend
    component rendering it. Reimplementing it beside the original would give the same question two
    answers that drift, which is the failure ``viewable_where``'s own banner describes when it says
    the old ``visibility_where`` was deliberately deleted rather than redefined.
    """
    from app.services import records

    assert hasattr(records, "merge_field_provenance")
    assert not hasattr(ep, "merge_field_provenance"), "the record rule must not be forked in here"
    source = Path(ep.__file__).read_text(encoding="utf-8")
    assert "extraMetadata" not in source.split('"""', 2)[2], (
        "entry_provenance must not write the record tables' provenance column"
    )
