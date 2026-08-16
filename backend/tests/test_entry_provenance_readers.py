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

Nothing here touches a database.
"""

from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.api.routes import design_workshops as routes
from app.services import design_workshops as dw
from app.services import entry_provenance as ep

ASHA = SimpleNamespace(id="usr_asha", name="Asha Patel", role="DESIGNER")
MEENA = SimpleNamespace(id="usr_meena", name="Meena Iyer", role="RESEARCHER")
ADMIN = SimpleNamespace(id="usr_admin", name="Root", role="MASTER_ADMIN")

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

    A module not on this list that starts reading the table is a reader nobody has classified, and
    the failure it produces is silent. Failing here — in the same commit, next to this docstring —
    is the cheapest place to find out. Adding a name is a one-line change and is the right fix; it
    just has to be a deliberate one, with the reader's own test added above.

    The exports are the reason this list is short and it is worth recording why: the dataset zip, the
    CSV downloads, the .xlsx workbook and the data browser read the shared RECORD tables and never
    touch ``DwStageEntry``. They therefore cannot serve a stale stage-entry value — there is no path
    from them to one.
    """
    #: BOTH WAYS IN ARE MATCHED, because they are two different mistakes. ``db.dwstageentry`` is a
    #: module going straight to the table, and ``entry_rows(`` is one using the shared loader — which
    #: is the RIGHT thing to do and is exactly why it is easy to add a reader through it without
    #: noticing. A check that watched only the raw delegate would miss every reader that behaved well.
    allowed = {
        "app/api/routes/design_workshops.py",
        "app/api/routes/analytics.py",
        "app/services/design_workshops.py",
        "app/services/dictation_consent.py",
    }
    root = Path(__file__).resolve().parents[1] / "app"
    found = set()
    for path in root.rglob("*.py"):
        text = path.read_text(encoding="utf-8").lower()
        if "db.dwstageentry." in text or "entry_rows(" in text:
            found.add(str(path.relative_to(root.parent)).replace("\\", "/"))
    unclassified = sorted(found - allowed)
    assert unclassified == [], (
        f"{unclassified} reads DwStageEntry and is not in this test's classified list. Decide "
        "whether it must resolve field provenance (services/entry_provenance), add a test for it "
        "beside the others in this file, and then add it here."
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
