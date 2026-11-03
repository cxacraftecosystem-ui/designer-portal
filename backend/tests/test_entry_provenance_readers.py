import tokenize
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.api.routes import (
    data_browser as browser,
    design_workshop_approvals as approval_routes,
    design_workshop_inspections as inspections,
    design_workshop_oversight as oversight_routes,
    design_workshops as routes,
)
from app.services import (
    design_ratings as dr,
    design_workshop_data as dwdata,
    design_workshop_oversight as oversight_service,
    design_workshops as dw,
    entry_provenance as ep,
)

ASHA = SimpleNamespace(id="usr_asha", name="Asha Patel", role="DESIGNER")
MEENA = SimpleNamespace(id="usr_meena", name="Meena Iyer", role="RESEARCHER")
ADMIN = SimpleNamespace(id="usr_admin", name="Root", role="MASTER_ADMIN")
INSPECTOR = SimpleNamespace(id="usr_ravi", name="Ravi Nair", role="INSPECTOR")

DIRECTOR = SimpleNamespace(id="usr_devi", name="Devi Menon", role="REGIONAL_DIRECTOR")

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
    def __init__(self):
        self.calls: list[list[str]] = []

    async def find_many(self, where=None):
        self.calls.append(sorted(where["id"]["in"]))
        return [SimpleNamespace(id=MEENA.id, name=MEENA.name)]



def _quiet_review_aids(monkeypatch):
    from app.services import design_workshop_review_aids as aids

    async def _register(*_a, **_k):
        return [], False

    async def _empty(*_a, **_k):
        return []

    async def _edits(*_a, **_k):
        return {}

    monkeypatch.setattr(aids, "annotated_register", _register)
    monkeypatch.setattr(aids, "clearance_rows", _empty)
    monkeypatch.setattr(aids, "snapshot_list", _empty)
    monkeypatch.setattr(aids, "stage_edit_times", _edits)


async def _no_feedback(*_a, **_k):
    return ([], False)


async def _no_decisions(*_a, **_k):
    return []


@pytest.fixture
def reader(monkeypatch):
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
    monkeypatch.setattr(routes, "_inspection_feedback_payload", _no_feedback)
    monkeypatch.setattr(routes.design_workshop_approvals, "decision_history", _no_decisions)
    return SimpleNamespace(users=users, entries=entries)


def _assert_resolved(bucket_provenance, *, where):
    assert bucket_provenance["name"]["by"] == MEENA.id, f"{where}: the record's author was lost"
    assert bucket_provenance["name"]["byName"] == MEENA.name, f"{where}: name was not resolved"
    assert bucket_provenance["phone"]["by"] == ASHA.id, f"{where}: the designer's edit was lost"



async def test_the_workshop_detail_route_resolves_the_overlay(reader):
    out = await routes.get_design_workshop("dw_1", current_user=ASHA)
    stages = out["stages"]
    _assert_resolved(
        stages[COLLECTION_STAGE]["provenance"]["collections"]["participant"]["ent_p1"],
        where="GET /design-workshops/{id}",
    )
    assert stages[SINGLETON_STAGE]["provenance"]["singleton"]["venue"]["by"] == ASHA.id
    assert reader.users.calls == [[MEENA.id]], "one lookup for the whole workshop, not one per row"


async def test_the_stage_list_route_resolves_the_overlay(reader):
    out = await routes.list_stages("dw_1", current_user=ASHA)
    _assert_resolved(
        out["stages"][COLLECTION_STAGE]["provenance"]["collections"]["participant"]["ent_p1"],
        where="GET /design-workshops/{id}/stages",
    )


async def test_the_single_stage_route_resolves_the_overlay(reader):
    out = await routes.get_stage("dw_1", COLLECTION_STAGE, current_user=ASHA)
    _assert_resolved(
        out["provenance"]["collections"]["participant"]["ent_p1"],
        where="GET /design-workshops/{id}/stages/{key}",
    )


async def test_a_stage_nobody_has_saved_yet_still_carries_the_bucket(reader):
    out = await routes.get_stage("dw_1", UNSAVED_STAGE, current_user=ASHA)
    assert out["provenance"] == {"singleton": {}, "collections": {}, "custom": {}}


async def test_every_collection_row_is_addressed_by_its_own_entry_id(reader):
    out = await routes.get_stage("dw_1", COLLECTION_STAGE, current_user=ASHA)
    by_entry = out["provenance"]["collections"]["participant"]
    assert set(by_entry) == {"ent_p1", "ent_p2"}
    assert "phone" in by_entry["ent_p1"] and "phone" not in by_entry["ent_p2"]


async def test_the_provenance_bucket_is_beside_the_data_and_never_inside_it(reader):
    out = await routes.get_stage("dw_1", COLLECTION_STAGE, current_user=ASHA)
    for row in out["collections"]["participant"]:
        assert "provenance" not in row
        assert not any(k.startswith("fieldProvenance") for k in row)



def test_the_report_builder_resolves_the_overlay():
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
    source = Path(routes.__file__).read_text(encoding="utf-8")
    body = source.split("async def _report_inputs(", 1)[1]
    resolve_at = body.index("resolve_display_names(data.field_provenance")
    gather_at = body.index("gather_reads(")
    assert resolve_at < gather_at, "the name lookup must be awaited before the gathered waves"



async def test_an_admin_sees_both_the_stamps_and_the_canonical_values(reader, monkeypatch):
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
    with pytest.raises(HTTPException) as exc:
        await routes.workshop_provenance("dw_1", current_user=ASHA)
    assert exc.value.status_code == 403
    assert "master admin" in exc.value.detail
    assert exc.value.detail.strip().endswith(".")



async def test_the_wire_shape_the_handset_syncs_is_the_one_it_parses(reader):
    out = await routes.list_stages("dw_1", current_user=ASHA)
    bucket = out["stages"][COLLECTION_STAGE]["provenance"]
    assert set(bucket) == {"singleton", "collections", "custom"}
    stamp = bucket["collections"]["participant"]["ent_p1"]["name"]
    assert set(stamp) >= {"by", "byName", "at", "source"}
    assert stamp["source"] in {"reference", "designer"}


SKETCH_DATA = {
    "sketchNo": "SK-07",
    "name": "Kalamkari tote",
    "targetMarket": "Urban gifting",
    "materials": ["Indigo cotton"],
    dr.POOL_OPENS_WHEN_FIELD: "2026-05-04",
}

NOT_THE_LABEL = ("Urban gifting", "Indigo cotton")

RATED_WORKSHOP = SimpleNamespace(id="dw_1", createdById=MEENA.id, deletedAt=None)


def _sketch_row(entry_id, *, ordinal=0):
    return SimpleNamespace(
        id=entry_id, entityKey="sketch", designWorkshopId="dw_1", ordinal=ordinal,
        data=dict(SKETCH_DATA), clientKey=None, createdById=ASHA.id, deletedAt=None,
        fieldProvenance={
            "name": STAMPS["name"], dr.POOL_OPENS_WHEN_FIELD: STAMPS["phone"],
        },
    )


class _Entries:
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
    entries = _Entries([_sketch_row("ent_sk1"), _sketch_row("ent_sk2", ordinal=1)])

    async def _workshop(where=None):
        return RATED_WORKSHOP if (where or {}).get("id") == RATED_WORKSHOP.id else None

    monkeypatch.setattr(dr, "db", SimpleNamespace(
        dwstageentry=entries, designworkshop=SimpleNamespace(find_unique=_workshop),
    ))
    return entries


def _assert_only_a_label_and_a_gate(subject, *, where):
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
    subject = await dr.load_subject("ent_sk1")
    assert subject is not None
    _assert_only_a_label_and_a_gate(subject, where="load_subject")
    assert subject.author_id == ASHA.id
    assert subject.workshop_author_id == MEENA.id
    assert ratings_db.unique_calls[0] == {"id": "ent_sk1"}


async def test_the_ranked_list_loader_takes_a_label_and_a_gate_and_no_third_field(ratings_db):
    subjects = await dr.load_subjects("dw_1", "sketch", RATED_WORKSHOP)
    assert [s.entry_id for s in subjects] == ["ent_sk1", "ent_sk2"]
    for subject in subjects:
        _assert_only_a_label_and_a_gate(subject, where="load_subjects")
    where, order = ratings_db.many_calls[0]
    assert where == {"designWorkshopId": "dw_1", "entityKey": "sketch", "deletedAt": None}
    assert order == {"ordinal": "asc"}


async def test_the_pool_directory_takes_the_gate_and_serves_no_stage_field(monkeypatch):
    rows = [_sketch_row("ent_sk1"), _sketch_row("ent_sk2", ordinal=1)]
    closed = _sketch_row("ent_sk3", ordinal=2)
    closed.data.pop(dr.POOL_OPENS_WHEN_FIELD)
    rows.append(closed)
    entries = _Entries(rows)
    workshop = SimpleNamespace(
        id="dw_1", title="Bagru 2026", startDate=None, endDate=None, deletedAt=None,
        craftName="Block printing", workshopCode="DW-7",
    )

    async def _workshops(where=None):
        wanted = set((where or {}).get("id", {}).get("in", []))
        return [workshop] if workshop.id in wanted else []

    monkeypatch.setattr(dr, "db", SimpleNamespace(
        dwstageentry=entries, designworkshop=SimpleNamespace(find_many=_workshops),
    ))
    listed = await dr.pool_directory()
    assert [item.workshop_id for item in listed] == ["dw_1"]
    payload = dr.pool_workshop_payload(listed[0])
    assert set(payload) == {"workshopId", "title", "startDate", "endDate", "openCounts"}
    assert payload["openCounts"] == {"prototype": 0, "sketch": 2}
    served = repr(payload)
    for value in (*NOT_THE_LABEL, SKETCH_DATA["name"], SKETCH_DATA[dr.POOL_OPENS_WHEN_FIELD]):
        assert value not in served, f"{value!r} came off a stage row into the pool directory"
    assert "Block printing" not in served and "DW-7" not in served, "a workshop_summary field leaked"
    assert "byName" not in served and "fieldProvenance" not in served
    (where, _order) = entries.many_calls[0]
    assert where == {"entityKey": {"in": sorted(dr.RATEABLE_ENTITIES)}, "deletedAt": None}


def test_no_stage_entry_field_and_no_identity_reaches_the_ranked_payload():
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
    assert ASHA.id not in served and MEENA.id not in served
    assert "provenance" not in served and "byName" not in served


def test_the_keys_this_fence_is_built_on_are_real_registry_fields():
    from app.services.stage_schema import all_entities

    sketch = next(entity for _stage, entity in all_entities() if entity.key == "sketch")
    keys = {f.key for f in sketch.fields}
    assert set(SKETCH_DATA) <= keys, f"not registry fields: {sorted(set(SKETCH_DATA) - keys)}"
    assert sketch.label_field == "name", "the label this reader prints is no longer the label field"
    assert dr.POOL_OPENS_WHEN_FIELD in keys, "the pool gate is not declared on a sketch"



async def test_reading_the_repository_is_open_for_every_role():
    from app.services.records import viewable_where

    for role in ("MASTER_ADMIN", "ADMIN", "PROFESSOR", "DESIGNER", "RESEARCHER",
                 "FIELD_CONTRIBUTOR", "CROWDSOURCE_VOLUNTEER"):
        user = SimpleNamespace(id=f"usr_{role}", name=role, role=role)
        assert await viewable_where(user) == {}, f"reading narrowed for {role}"
        assert await viewable_where(user, owner_field="uploadedById") == {}


def test_no_record_reader_can_serve_a_stale_value_because_there_is_no_overlay():
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



@pytest.fixture
def inspection(reader, monkeypatch):
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
    _quiet_review_aids(monkeypatch)
    return reader


async def test_the_inspector_read_resolves_the_overlay(inspection):
    out = await inspections.read_workshop_under_inspection("dw_1", current_user=INSPECTOR)
    _assert_resolved(
        out["stages"][COLLECTION_STAGE]["provenance"]["collections"]["participant"]["ent_p1"],
        where="GET /design-workshop-inspections/{id}",
    )
    assert out["stages"][SINGLETON_STAGE]["provenance"]["singleton"]["venue"]["by"] == ASHA.id
    assert inspection.users.calls == [[MEENA.id]], "one lookup for the whole workshop, not one per row"


async def test_the_inspector_read_says_on_the_wire_that_it_is_read_only(inspection):
    out = await inspections.read_workshop_under_inspection("dw_1", current_user=INSPECTOR)
    assert out["readOnly"] is True



def _dw_row_keys():
    keys = {column.key for column in dwdata.WORKSHOP_IDENTITY_COLUMNS}
    keys |= {"entry.id", "entry.ordinal"}
    for table in dwdata.tables():
        keys |= {column.key for column in table.columns}
    return keys


def test_the_data_browser_attributes_no_stage_field_to_anybody():
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


_PROSE_TOKENS = {tokenize.COMMENT, tokenize.STRING} | {
    getattr(tokenize, name)
    for name in ("FSTRING_START", "FSTRING_MIDDLE", "FSTRING_END")
    if hasattr(tokenize, name)
}



def _module_code(relative: str) -> str:
    return _blanked_code(Path(__file__).resolve().parents[1] / relative)


def _module_source(relative: str) -> str:
    return (Path(__file__).resolve().parents[1] / relative).read_text(encoding="utf-8").lower()


def test_the_dataset_archive_attributes_no_stage_field_to_anybody():
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
    assert "fieldprovenance" not in _module_source("app/api/routes/search.py"), (
        "search.py names the provenance stamp and is no longer exempt - see Readers 9 to 11 above."
    )



@pytest.fixture
def supervision(reader, monkeypatch):
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

    async def _oversight_rows(*_a, **_k):
        return []

    monkeypatch.setattr(oversight_routes.oversight, "load_overseen_workshop_or_404", _load)
    monkeypatch.setattr(oversight_routes.oversight, "oversight_rows", _oversight_rows)
    monkeypatch.setattr(oversight_routes, "entry_rows", _rows)
    monkeypatch.setattr(oversight_routes, "load_definition_or_empty", _definition)
    monkeypatch.setattr(oversight_routes, "workshop_summary", lambda record: {"id": record.id})
    monkeypatch.setattr(oversight_routes, "workshop_completeness", lambda *_a, **_k: {})
    monkeypatch.setattr(oversight_routes.design_workshop_approvals, "decision_history", _no_decisions)
    monkeypatch.setattr(oversight_routes, "inspector_rows", _oversight_rows)
    _quiet_review_aids(monkeypatch)
    return SimpleNamespace(users=reader.users, entries=fresh)



@pytest.fixture
def sign_off(supervision, monkeypatch):
    async def _load(*_a, **_k):
        return SimpleNamespace(id="dw_1", title="Bagru 2026", deletedAt=None, status="PRE_SUBMISSION",
                               handedOnAt=None)

    async def _rows(_workshop_id, *, stage_key=None):
        return [r for r in supervision.entries if stage_key is None or r.stageKey == stage_key]

    async def _definition(*_a, **_k):
        return SimpleNamespace(version="v1", sections=(), fields_by_stage={}, fields=())

    async def _empty(*_a, **_k):
        return []

    async def _none(*_a, **_k):
        return None

    async def _names(*_a, **_k):
        return {"approvedByName": None, "approvedByRole": None, "handedOnByName": None}

    monkeypatch.setattr(approval_routes.approvals, "load_approvable_workshop_or_404", _load)
    monkeypatch.setattr(approval_routes.approvals, "decision_history", _empty)
    monkeypatch.setattr(approval_routes.approvals, "approval_names", _names)
    monkeypatch.setattr(approval_routes.approvals, "latest_export", _none)
    monkeypatch.setattr(approval_routes.report_delivery, "delivery_view", _none)
    monkeypatch.setattr(approval_routes.posts, "decision_refusal", _none)
    monkeypatch.setattr(approval_routes.oversight, "oversight_rows", _empty)
    monkeypatch.setattr(approval_routes, "inspector_rows", _empty)
    monkeypatch.setattr(approval_routes, "_inspection_feedback_payload", _no_feedback)
    monkeypatch.setattr(approval_routes.report_delivery, "delivery_view", _none)
    _quiet_review_aids(monkeypatch)
    monkeypatch.setattr(approval_routes, "entry_rows", _rows)
    monkeypatch.setattr(approval_routes, "load_definition_or_empty", _definition)
    monkeypatch.setattr(approval_routes, "workshop_summary", lambda record: {"id": record.id})
    monkeypatch.setattr(approval_routes, "workshop_completeness", lambda *_a, **_k: {})
    return supervision


async def test_the_approving_authoritys_read_resolves_the_overlay(sign_off):
    out = await approval_routes.read_report_to_approve("dw_1", current_user=ADMIN)
    _assert_resolved(
        out["stages"][COLLECTION_STAGE]["provenance"]["collections"]["participant"]["ent_p1"],
        where="GET /design-workshop-approvals/{id}",
    )
    assert sign_off.users.calls == [[MEENA.id]], "one lookup for the whole workshop"


async def test_the_approving_authoritys_read_says_it_is_read_only(sign_off):
    out = await approval_routes.read_report_to_approve("dw_1", current_user=ADMIN)
    assert out["readOnly"] is True
    assert out["mayRecordFeedback"] is False


async def test_the_officer_supervision_read_resolves_the_overlay(supervision):
    out = await oversight_routes.read_overseen_workshop("dw_1", current_user=DIRECTOR)
    _assert_resolved(
        out["stages"][COLLECTION_STAGE]["provenance"]["collections"]["participant"]["ent_p1"],
        where="GET /design-workshop-oversight/assigned/{id}",
    )
    assert out["stages"][SINGLETON_STAGE]["provenance"]["singleton"]["venue"]["by"] == ASHA.id
    assert supervision.users.calls == [[MEENA.id]], (
        "one lookup for the whole workshop, not one per row"
    )


async def test_the_officer_supervision_read_says_on_the_wire_that_it_is_read_only(supervision):
    out = await oversight_routes.read_overseen_workshop("dw_1", current_user=DIRECTOR)
    assert out["readOnly"] is True



async def test_the_roster_import_reads_an_ordinal_and_a_reference_and_no_third_thing(monkeypatch):
    from app.services import artisan_import

    existing = [
        _row("ent_p1", COLLECTION_STAGE, "participant",
             {"artisanRef": "art_1", "name": "Sita Devi", "phone": "9000011111"}, STAMPS, ordinal=4),
    ]
    handed: list = []

    async def _find_many(where=None):
        return existing

    async def _save_stage(_workshop_id, _spec, payload, _actor, **_kw):
        handed.append(payload)
        return {"created": len(payload.entries), "errors": {}}

    monkeypatch.setattr(
        "app.core.db.db", SimpleNamespace(dwstageentry=SimpleNamespace(find_many=_find_many))
    )
    monkeypatch.setattr(dw, "save_stage", _save_stage)

    written = await artisan_import._write_roster(
        SimpleNamespace(id="dw_1"),
        ["art_1", "art_2"],
        actor=ADMIN,
        warn=lambda *_a, **_k: None,
    )

    assert written == 1, "the artisan already on the roster is skipped; the new one is written"
    entries = [entry for payload in handed for entry in payload.entries]
    assert [entry.data for entry in entries] == [{"artisanRef": "art_2", "serialNo": 5}], (
        "the roster import carried a third key off the rows it read. It is EXEMPT from resolving "
        "provenance because it takes an ordinal and a reference and serves nothing - see above."
    )
    served = repr(handed)
    assert "Sita Devi" not in served and "9000011111" not in served, (
        "a stamped value came off the existing rows and into the write"
    )
    assert "fieldProvenance" not in served and "byName" not in served


async def test_the_sanction_register_takes_two_booleans_and_a_list_of_labels(monkeypatch):
    from app.services import sanction_orders as so

    singleton = _row(
        "ent_setup", SINGLETON_STAGE, "workshopSetup",
        {"sanctionOrderNo": "SO/2026/42", "sanctionOrderDate": "2026-04-01", "venue": "Barpali"},
        {"venue": STAMPS["phone"], "sanctionOrderNo": STAMPS["name"]},
    )

    async def _rows(_workshop_id, *, stage_key=None):
        assert stage_key == SINGLETON_STAGE, "the register reads stage 1 and nothing else"
        return [singleton]

    monkeypatch.setattr(so, "entry_rows", _rows)

    async def _no_planned_rows(_workshop_ids):
        return {}

    monkeypatch.setattr(so.annual_plan, "planned_rows_for_workshops", _no_planned_rows)

    order = SimpleNamespace(
        id="so_1", sanctionOrderNo="SO-2026-42", sanctionOrderKey="SO202642",
        sanctionOrderDate=datetime(2026, 4, 1, tzinfo=UTC), sanctionAmount=Decimal("250000.00"),
        designerUserId=ASHA.id, designerEmail="asha@example.org", accountCreated=True,
        notes=None, designWorkshopId="dw_1", createdById=ADMIN.id,
        createdAt=datetime(2026, 4, 1, tzinfo=UTC), updatedAt=datetime(2026, 4, 2, tzinfo=UTC),
    )
    payload = await so.sanction_payload(
        order,
        workshop=SimpleNamespace(id="dw_1", title="Bagru 2026", status="IN_PROGRESS"),
        designer=SimpleNamespace(name=ASHA.name),
        officer=SimpleNamespace(name=ADMIN.name),
    )

    assert payload["reportCopyMatches"] is True, "the two order keys are compared, normalised"
    assert isinstance(payload["readyForWork"], bool)
    assert all(isinstance(label, str) for label in payload["missingMandatory"])
    served = repr(payload)
    assert "Barpali" not in served, (
        "a stage-1 field value reached the sanction register. It is EXEMPT from resolving provenance "
        "because it serves two booleans and a list of labels - see above."
    )
    assert "fieldProvenance" not in served and "byName" not in served and "provenance" not in served
    assert MEENA.id not in served, "a stamp's author reached a reader holding no grant on the workshop"



async def test_the_registers_progress_column_is_counts_and_carries_no_value_and_no_author(monkeypatch):
    from app.services import ministry_dashboard as register

    entries = _entries()
    for row in entries:
        row.designWorkshopId = "dw_1"
    queries: list[dict] = []

    async def _find_entries(where=None, order=None):
        queries.append(where or {})
        return entries

    async def _no_sections(where=None, order=None):
        return []

    monkeypatch.setattr(
        register,
        "db",
        SimpleNamespace(
            dwstageentry=SimpleNamespace(find_many=_find_entries),
            dwcustomsection=SimpleNamespace(find_many=_no_sections),
        ),
    )

    scored = await register.progress_for(["dw_1"])

    assert queries and queries[0].get("deletedAt", "missing") is None, (
        "the register scored soft-deleted entries. A workshop would then be credited for stage work "
        "its designer has already taken back."
    )
    assert set(scored) == {"dw_1"}
    figures = scored["dw_1"]
    assert set(figures) == {
        "percent",
        "stagesTotal",
        "stagesComplete",
        "requiredTotal",
        "requiredFilled",
        "definitionRead",
        "customSchemaVersion",
    }, "the register's progress block grew a key — is it a stage-entry field?"
    assert isinstance(figures["percent"], int) and isinstance(figures["stagesComplete"], int)

    served = repr(scored)
    assert "Sita Devi" not in served and "9000011111" not in served and "Barpali" not in served, (
        "a stage-entry field value reached the ministry register. It is EXEMPT from resolving "
        "provenance because it counts rows and serves figures - see above."
    )
    assert "fieldProvenance" not in served and "byName" not in served and "provenance" not in served
    assert MEENA.id not in served and ASHA.id not in served, (
        "a stamp's author reached the widest audience in the product, none of whom hold a grant on "
        "the workshop it came from."
    )


async def test_the_search_index_serves_search_text_and_no_value_or_author_off_the_row(monkeypatch):
    from app.services import search_index

    entry = _row(
        "ent_s1", COLLECTION_STAGE, "participant",
        {"name": "Sita Devi", "phone": "9000011111"}, STAMPS,
    )
    entry.searchText = "indigo vats fermented"
    workshop = SimpleNamespace(
        id="dw_1", deletedAt=None, title="Dyers meet", craftName=None, clusterName="Bagru",
        district="Jaipur", state="Rajasthan", workshopCode="DW-1", venue="Hall",
        workshopId=None, startDate=None, createdAt=datetime(2026, 3, 1, tzinfo=UTC),
    )

    async def _find_unique(where=None):
        return workshop

    async def _find_many(where=None, order=None):
        return [entry]

    monkeypatch.setattr(
        search_index,
        "db",
        SimpleNamespace(
            designworkshop=SimpleNamespace(find_unique=_find_unique),
            dwstageentry=SimpleNamespace(find_many=_find_many),
        ),
    )

    served = repr(await search_index._design_workshop("dw_1"))

    assert "indigo vats fermented" in served, "the stage's search text is the point of the read"
    for leaked in ("Sita Devi", "9000011111", ASHA.name, ASHA.id, MEENA.id, "byName"):
        assert leaked not in served, (
            f"{leaked!r} reached the search document off the stage row. The index is EXEMPT from "
            "resolving provenance only because it reads `searchText` and serves no value or author."
        )



async def test_taking_the_designer_off_serves_no_value_and_no_author(monkeypatch):
    saved: list[tuple] = []

    COVER = {
        "designerName": ASHA.name,
        "craftName": "Ikat",
        "clusterName": "Barpali",
        "state": "Odisha",
        "district": "Bargarh",
        "startDate": "2026-04-01",
        "endDate": "2026-04-07",
        "schemeName": "Craft Toolkit",
        "implementingAgency": "NID",
        "sponsor": "Ministry of Textiles",
        "workshopCode": "CTW-3-BARPALI",
    }

    async def _entry_rows(_workshop_id, *, stage_key=None, **_kw):
        return [
            SimpleNamespace(
                entityKey="workshopSetup",
                clientKey=dw.singleton_client_key("WORKSHOP_SETUP"),
                data=dict(COVER),
                fieldProvenance=STAMPS,
            )
        ]

    async def _save_stage(workshop_id, spec, payload, actor):
        saved.append((workshop_id, spec, payload, actor))

    monkeypatch.setattr(dw, "entry_rows", _entry_rows)
    monkeypatch.setattr(dw, "save_stage", _save_stage)

    stages_to_reload = await oversight_service.take_the_designers_name_off_the_workshop(
        "dw_1", actor=ADMIN
    )

    served = repr(stages_to_reload)
    assert stages_to_reload == ["WORKSHOP_SETUP"], (
        "the unassign answered with something other than the stage keys to reload. Anything else "
        "it could answer with came off a stage entry, and this reader is EXEMPT only because it "
        "serves nothing - see above."
    )
    assert not any(value in served for value in COVER.values()), (
        "a stage-entry VALUE reached the caller of the unassign."
    )
    assert ASHA.name not in served and ASHA.id not in served and MEENA.id not in served, (
        "the outgoing designer, or a stamp's author, reached the caller of the unassign."
    )
    assert "fieldProvenance" not in served and "byName" not in served

    assert len(saved) == 1, "the unassign did not write the stage entry back"
    entries = saved[0][2].entries
    assert len(entries) == 1 and entries[0].merge is False
    written = entries[0].data
    assert "designerName" not in written, "the unassign left the name it exists to remove"
    assert written == {k: v for k, v in COVER.items() if k != "designerName"}, (
        "the unassign dropped a key it was not asked to drop, or invented one. `merge=False` "
        "REPLACES the stored entry, so a key missing here is a value deleted off the workshop "
        "cover - the incident `seed_designer_prefill` records, re-created by omission rather than "
        "by an empty save. Every key it must carry is a promoted column."
    )
    assert "fieldProvenance" not in written, (
        "the unassign put the stamps INSIDE `data`. They are a sibling column; a reserved key in "
        "`data` comes back in `droppedKeys` on every save - see schema.prisma at the column."
    )


def test_the_oversight_service_still_reads_stage_entries_in_exactly_one_place():
    code = _module_code("app/services/design_workshop_oversight.py")
    sites = code.count("entry_rows(") + code.count("db.dwstageentry.")
    assert sites == 1, (
        f"{sites} stage-entry read sites in services/design_workshop_oversight.py, not 1. The "
        "Reader 16 exemption covers ONE function that serves nothing and hands the row straight "
        "back; it is not a licence for the whole module. If the new read serves a value to a "
        "person, it must resolve provenance and carry its own test — see the classified list."
    )


def _blanked_code(path: Path) -> str:
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
    code = _blanked_code(path)
    return "db.dwstageentry." in code or "entry_rows(" in code


def test_no_new_reader_of_stage_entries_appeared_without_resolving_provenance():
    allowed = {
        "app/api/routes/design_workshops.py",
        "app/api/routes/analytics.py",
        "app/api/routes/design_workshop_inspections.py",
        "app/services/design_workshops.py",
        "app/services/dictation_consent.py",
        "app/services/design_ratings.py",
        "app/api/routes/data_browser.py",
        "app/api/routes/export.py",
        "app/api/routes/search.py",
        "app/api/routes/design_workshop_oversight.py",
        "app/services/artisan_import.py",
        "app/services/sanction_orders.py",
        "app/services/design_workshop_oversight.py",
        "app/services/ministry_dashboard.py",
        "app/api/routes/design_workshop_approvals.py",
        "app/services/design_workshop_review_aids.py",
        "app/services/stage_assist.py",
        "app/services/stage_computed.py",
        "app/services/workshop_copy.py",
        "app/api/routes/design_workshop_copy.py",
        "app/services/ministry_export.py",
        "app/services/search_index.py",
        "app/api/routes/artisan_cards.py",
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
    undetected = sorted(allowed - found)
    assert undetected == [], (
        f"{undetected} is on the classified list and the detector no longer finds it. This check "
        "reports what is NOT on the list, so a detector that finds nothing reports nothing: fix "
        "`_reads_stage_entries`, and do not delete these names to make it quiet."
    )


def test_record_level_field_provenance_is_still_the_records_own_and_was_not_duplicated_here():
    from app.services import records

    assert hasattr(records, "merge_field_provenance")
    assert not hasattr(ep, "merge_field_provenance"), "the record rule must not be forked in here"
    source = Path(ep.__file__).read_text(encoding="utf-8")
    assert "extraMetadata" not in source, (
        "entry_provenance must not write the record tables' provenance column"
    )
