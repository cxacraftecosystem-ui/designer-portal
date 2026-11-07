from __future__ import annotations

import asyncio
import io
import json
import warnings
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.services import records

UPLOADER = "user-uploader"
STRANGER = SimpleNamespace(id="user-stranger", role="CROWDSOURCE_VOLUNTEER")
SECRET = "DUMMY-SECRET-WORDS"


@pytest.fixture
def own_uploads_only(monkeypatch):
    from app.core.deps import has_rank

    async def owners(viewer):
        if has_rank(viewer, "PROFESSOR"):
            return None
        return {viewer.id} if getattr(viewer, "id", None) else set()

    async def no_workshops(_user_id):
        return []

    monkeypatch.setattr(records, "media_url_owners", owners)
    monkeypatch.setattr(records, "_design_workshop_media_ids", no_workshops)


def _run(coro):
    return asyncio.run(coro)


def test_redaction_drops_every_copy_of_a_withheld_transcript():
    node = {
        "id": "m1",
        "objectKey": "media/user-uploader/a/x.mp3",
        "url": "https://b/media/user-uploader/a/x.mp3",
        "uploadedById": UPLOADER,
        "transcriptText": SECRET,
        "transcriptSegments": [{"text": "SEG-SECRET"}],
        "cleanedObjectKey": "media/user-uploader/b/x.clean.m4a",
        "processingJobs": [{"id": "j1", "status": "COMPLETED", "result": {"text": "JOB-SECRET", "formattedTranscript": "FMT-SECRET"}}],
    }
    out = records.public_encode(node, STRANGER, media_urls={STRANGER.id}, media_workshops=frozenset())
    text = json.dumps(out)
    for leaked in (SECRET, "SEG-SECRET", "JOB-SECRET", "FMT-SECRET", "x.clean.m4a"):
        assert leaked not in text, leaked
    assert out["processingJobs"][0]["status"] == "COMPLETED"


def test_redaction_keeps_every_copy_for_the_uploader():
    node = {
        "id": "m1",
        "objectKey": "media/user-uploader/a/x.mp3",
        "uploadedById": UPLOADER,
        "transcriptSegments": [{"text": "SEG"}],
        "cleanedObjectKey": "media/user-uploader/b/x.clean.m4a",
    }
    out = records.public_encode(node, SimpleNamespace(id=UPLOADER, role="RESEARCHER"), media_urls={UPLOADER})
    assert out["transcriptSegments"] == [{"text": "SEG"}]
    assert out["cleanedObjectKey"].endswith("x.clean.m4a")


class _RawClient:
    def __init__(self):
        self.sql: list[str] = []

    async def query_raw(self, sql, *args):
        self.sql.append(sql)
        return []


def _take_row(uploader: str) -> dict[str, Any]:
    return {
        "id": "r1", "mediaFileId": "m1", "clientClipKey": "k1", "sessionId": "s1", "version": 1,
        "capturedAt": datetime(2026, 11, 1, 9, 0, tzinfo=UTC), "source": "DEVICE", "durationMs": 1000,
        "rosterCount": 1, "recordedById": uploader, "recordedByName": "A", "continuationOfId": None,
        "filing_t": "FILED", "decisionKind": None, "provenance_t": "NATIVE", "languageCode": None,
        "removedAt": None, "objectKey": f"media/{uploader}/x.m4a", "transcriptStatus": "COMPLETED",
        "transcriptText": SECRET, "uploadedById": uploader, "linkedRecordType": None, "linkedRecordId": None,
    }


@pytest.fixture
def serializer(monkeypatch, own_uploads_only):
    from app.questionnaire import recordings

    async def not_paused(*_a, **_k):
        return False

    async def previews(shown, client=None):
        return {k: (v[:8] if v else None) for k, v in shown.items()}

    async def nothing(*_a, **_k):
        return {}

    from app.services import questionnaire_consent, questionnaire_transcription

    monkeypatch.setattr(questionnaire_transcription, "is_paused", not_paused)
    monkeypatch.setattr(questionnaire_transcription, "previews", previews)
    monkeypatch.setattr(questionnaire_consent, "verdict_details", nothing)
    monkeypatch.setattr(recordings.titles, "title_inputs", nothing)
    monkeypatch.setattr(recordings.chips, "for_rows", nothing)
    return recordings


def test_session_reads_withhold_another_researchers_transcript(serializer):
    out = _run(serializer.serialize_recordings(_RawClient(), [_take_row(UPLOADER)], STRANGER))
    assert out[0]["transcript"]["text"] is None
    assert out[0]["transcript"]["preview"] is None


def test_session_reads_keep_the_uploaders_own_transcript(serializer):
    me = SimpleNamespace(id=UPLOADER, role="RESEARCHER")
    out = _run(serializer.serialize_recordings(_RawClient(), [_take_row(UPLOADER)], me))
    assert out[0]["transcript"]["text"] == SECRET


def test_recording_search_matches_transcript_words_only_where_the_reader_may_read(own_uploads_only):
    from app.questionnaire import filters

    client = _RawClient()
    _run(filters.list_recordings(filters.parse({"q": "mordant"}), STRANGER, client=client))
    first = client.sql[0]
    assert 'm."transcriptText" ILIKE' in first
    assert 'AND (m."uploadedById" = ANY(' in first


def test_consolidated_questionnaire_withholds_another_researchers_transcript(monkeypatch, own_uploads_only):
    from app.services import questionnaire_consolidation as qc
    from tests.test_questionnaire_consolidation import ARTISAN_ID, _build_db

    async def owners(viewer):
        return {viewer.id}

    monkeypatch.setattr(qc, "db", _build_db())
    monkeypatch.setattr(qc, "media_url_owners", owners)
    payload = _run(qc.consolidate_for_artisan(ARTISAN_ID, STRANGER))
    assert "spoken answer" not in json.dumps(payload, default=str)
    assert all("spoken answer" not in str(row) for row in qc.consolidated_rows(payload))


def test_session_insights_skip_recordings_the_reader_may_not_read(monkeypatch, own_uploads_only):
    import app.core.db as core_db
    from app.api.routes import questionnaire_v2 as routes
    from app.services import transcript_insights

    media = SimpleNamespace(id="m1", uploadedById=UPLOADER, linkedRecordType=None, linkedRecordId=None, objectKey="media/x")

    async def media_rows(**_k):
        return [media]

    async def nothing(*_a, **_k):
        return {}

    async def session(*_a, **_k):
        return {"id": "s1"}

    async def rows(*_a, **_k):
        return [{"id": "r1", "mediaFileId": "m1"}]

    monkeypatch.setattr(core_db, "db", SimpleNamespace(mediafile=SimpleNamespace(find_many=media_rows)))
    monkeypatch.setattr(routes.sessions, "require_session", session)
    monkeypatch.setattr(routes.recordings, "recording_rows", rows)
    monkeypatch.setattr(transcript_insights, "enabled", lambda: True)
    monkeypatch.setattr(transcript_insights, "current_insights", nothing)
    monkeypatch.setattr(transcript_insights, "latest_jobs", nothing)
    monkeypatch.setattr(
        transcript_insights, "build_view", lambda *_a, **_k: {"state": "READY", "summaryLines": ["DUMMY-SUMMARY"]}
    )
    out = _run(routes.sessions_insights("s1", STRANGER))
    assert "DUMMY-SUMMARY" not in json.dumps(out)


def test_unified_search_limits_transcript_hits_to_readable_recordings(monkeypatch, own_uploads_only):
    from app.services import search_query, unified_search as us

    async def none(_user):
        return []

    monkeypatch.setattr(us, "visible_design_workshops", none)
    scope, _refused = _run(us.account_scope(STRANGER, ["transcripts"]))
    sql, params = search_query.build_sql("zebrafinch", scope, limit=5)
    assert '"MediaFile" tm' in sql
    assert [STRANGER.id] in params


def test_data_explorer_transcript_list_hides_consent_withheld_text(monkeypatch):
    from app.api.routes import data_explorer as routes
    from app.services import transcript_export

    row = SimpleNamespace(
        id="m1", transcriptText="alpha bravo secretword delta", mediaType="AUDIO",
        originalFilename="a.m4a", createdAt=datetime(2026, 11, 1, tzinfo=UTC),
    )

    async def scope(_user):
        return SimpleNamespace()

    async def folder(_scope, _norm, _offset=0):
        return {"m1": {"mediaId": "m1", "name": "a.m4a", "path": "x/a.m4a", "mediaType": "AUDIO"}}, {"m1": row}, False

    async def withheld(ids, **_k):
        return set(ids)

    monkeypatch.setattr(routes, "download_scope", scope)
    monkeypatch.setattr(routes, "_folder_transcripts", folder)
    monkeypatch.setattr(routes, "_browser", lambda: SimpleNamespace(_norm=lambda p: p))
    monkeypatch.setattr(transcript_export, "withheld_ids", withheld)
    out = _run(routes.transcript_list(path="x", q="secretword", offset=0, current_user=STRANGER))
    assert "secretword" not in json.dumps(out)
    plain = _run(routes.transcript_list(path="x", q=None, offset=0, current_user=STRANGER))
    assert all(item.get("hasText") is False for item in plain["items"])


class _CardEvents:
    def __init__(self):
        self.rows: list[dict[str, Any]] = []

    async def count(self, where):
        return sum(1 for r in self.rows if r["userId"] == where["userId"] and r["action"] == where["action"])

    async def create(self, data):
        self.rows.append(dict(data))
        return SimpleNamespace(**data)


def _verhoeff_complete(prefix11: str) -> str:
    from app.services.artisan_identity import verhoeff_ok

    return next(prefix11 + d for d in "0123456789" if verhoeff_ok(prefix11 + d))


def test_aadhaar_lookup_stops_answering_after_a_day_of_misses(monkeypatch):
    import app.core.db as core_db
    from app.api.routes import artisans as routes
    from app.services import aadhaar_fingerprint

    events = _CardEvents()
    target = _verhoeff_complete("23456789012")
    target_fp = aadhaar_fingerprint.fingerprint_of(target)

    async def find_first(where, include=None):
        if where == {aadhaar_fingerprint.FINGERPRINT_FIELD: target_fp}:
            return SimpleNamespace(id="artisan-x", name="Dummy", place="P", craft=None, workshop=None)
        return None

    async def no_legacy(_db):
        return False

    monkeypatch.setattr(core_db, "db", SimpleNamespace(artisancardevent=events))
    monkeypatch.setattr(routes, "db", SimpleNamespace(artisan=SimpleNamespace(find_first=find_first)))
    monkeypatch.setattr(aadhaar_fingerprint, "legacy_pending", no_legacy)
    volunteer = SimpleNamespace(id="v1", role="CROWDSOURCE_VOLUNTEER")
    misses = 0
    for n in range(100, 400):
        guess = _verhoeff_complete(f"3{n:010d}")
        try:
            out = _run(routes.lookup_artisan_by_aadhaar_body(routes.AadhaarLookup(number=guess), volunteer))
        except HTTPException:
            break
        assert out["found"] is False
        misses += 1
    assert misses <= 100
    with pytest.raises(HTTPException) as refused:
        _run(routes.lookup_artisan_by_aadhaar_body(routes.AadhaarLookup(number=target), volunteer))
    assert refused.value.status_code == 429


def test_duplicate_check_stops_confirming_pehchan_numbers_after_a_day_of_misses(monkeypatch):
    import app.core.db as core_db
    from app.api.routes import artisan_duplicates as routes
    from app.schemas.duplicates import DuplicateCheckIn
    from app.services import artisan_duplicates

    events = _CardEvents()
    stored = artisan_duplicates.Profile(
        id="artisan-a", name="Dummy Artisan", pehchanCardNumber="TESTPC123456",
        display={"id": "artisan-a", "name": "Dummy Artisan"},
    )

    async def prefilter(profile, **_k):
        return [stored] if profile.pehchanCardNumber == stored.pehchanCardNumber else []

    async def profile_from_form(**kw):
        return artisan_duplicates.Profile(name=kw.get("name") or "", pehchanCardNumber=kw.get("pehchanCardNumber"))

    async def read_all(*_a, **_k):
        return {}

    monkeypatch.setattr(core_db, "db", SimpleNamespace(artisancardevent=events))
    monkeypatch.setattr(artisan_duplicates, "prefilter", prefilter)
    monkeypatch.setattr(routes, "profile_from_form", profile_from_form)
    monkeypatch.setattr(routes, "_check_buckets", SimpleNamespace(take=lambda _k: (True, 0)))
    monkeypatch.setattr(records, "viewable_where", read_all)
    researcher = SimpleNamespace(id="r1", role="RESEARCHER")
    for n in range(150):
        _run(routes.check_duplicates(DuplicateCheckIn(name="zz", pehchanCardNumber=f"TESTPC{n:06d}"), researcher))
    out = _run(routes.check_duplicates(DuplicateCheckIn(name="zz", pehchanCardNumber="TESTPC123456"), researcher))
    assert not any(r["code"] == "PEHCHAN" for c in out["candidates"] for r in c["reasons"])


def _report_book(viewer):
    openpyxl = pytest.importorskip("openpyxl")
    from app.services.datareport import build
    from tests import report_fixtures as rf

    ctx = rf.context(build.options_from({"extras": "everything"}))
    ctx.user = viewer
    artisan = ctx.material["records"]["artisans"][0]
    artisan.pehchanCardNumber = "DUMMY12345"
    artisan.pehchanCardAvailable = True
    artisan.aadhaarVerifyName = "Dummy Verified Name"
    artisan.aadhaarVerifyGender = "F"
    payload = asyncio.run(build.render(ctx))
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        book = openpyxl.load_workbook(io.BytesIO(payload))
    return " ".join(
        str(cell) for ws in book.worksheets for row in ws.iter_rows(values_only=True) for cell in row if cell is not None
    )


def test_report_sheets_mask_pehchan_and_drop_aadhaar_verification_below_professor():
    text = _report_book(SimpleNamespace(id="r1", role="RESEARCHER", name="R"))
    assert "DUMMY12345" not in text
    assert "Dummy Verified Name" not in text


def test_report_sheets_keep_pehchan_for_a_professor():
    text = _report_book(SimpleNamespace(id="p1", role="PROFESSOR", name="P"))
    assert "DUMMY12345" in text
    assert "Dummy Verified Name" not in text


def test_an_identity_edit_withdraws_the_aadhaar_verification():
    from app.services.aadhaar import records as aadhaar_records

    class _Revisions:
        def __init__(self):
            self.rows = []

        async def create(self, data):
            self.rows.append(data)

    tx = SimpleNamespace(recordrevision=_Revisions())
    row = SimpleNamespace(id="a1", name="Kamla Devi", aadhaarFingerprint="fp", aadhaarLast4="1234",
                          aadhaarVerifyStatus="VERIFIED", aadhaarVerifyMethod="OFFLINE_XML")
    data = {"name": "Somebody Else"}
    _run(aadhaar_records.withdraw_for_identity_edit(row, SimpleNamespace(id="e1"), data, client=tx))
    assert data["aadhaarVerifyStatus"] is None and data["aadhaarVerifyName"] is None
    assert len(tx.recordrevision.rows) == 1
    same = {"name": "Kamla Devi"}
    _run(aadhaar_records.withdraw_for_identity_edit(row, SimpleNamespace(id="e1"), same, client=tx))
    assert "aadhaarVerifyStatus" not in same


def test_a_workshop_filed_recording_is_a_consent_subject_of_that_workshop(monkeypatch):
    from app.services import dictation_consent

    seen: list[list[str]] = []

    async def no_state(*_a, **_k):
        return {}

    async def none(*_a, **_k):
        return None

    async def empty(*_a, **_k):
        return []

    async def verdict(own, candidates, artisans, **_k):
        seen.append(list(candidates))
        return dictation_consent.SendVerdict(dictation_consent.SendDecision.NOT_WORKSHOP_MATERIAL)

    monkeypatch.setattr(dictation_consent.ledger, "current_states", no_state)
    monkeypatch.setattr(dictation_consent, "interview_workshop_id", none)
    monkeypatch.setattr(dictation_consent, "recording_artisan_ids", empty)
    monkeypatch.setattr(dictation_consent, "workshop_artisan_ids", empty)
    monkeypatch.setattr(dictation_consent, "stage_attached_workshop_ids", empty)
    monkeypatch.setattr(dictation_consent, "_subjects_verdict", verdict)
    media = SimpleNamespace(id="m1", linkedRecordType="craft", linkedRecordId="c1", designWorkshopId="dw-refused")
    _run(dictation_consent.transcription_verdict(media))
    assert seen == [["dw-refused"]]


def test_a_bundle_cannot_prefill_aadhaar_or_pehchan_columns():
    from app.questionnaire.answers import prefill_value

    artisan = SimpleNamespace(aadhaarLast4="1234", aadhaarVerifyName="Dummy", pehchanCardNumber="PC1", name="Kamla")
    assert prefill_value(artisan, "aadhaarLast4") is None
    assert prefill_value(artisan, "aadhaarVerifyName") is None
    assert prefill_value(artisan, "pehchanCardNumber") is None
    assert prefill_value(artisan, "name") == "Kamla"


class _Inserted(Exception):
    pass


def _merge_world(monkeypatch, *, holds, current, after, merged):
    from datetime import timedelta

    from app.services import artisan_merge

    record = SimpleNamespace(
        id="merge-1", undoneAt=None, snapshotPurgedAt=None, survivorId="keep", mergedId="gone",
        undoUntil=datetime.now(UTC) + timedelta(days=5), moves={}, crossPortal=None,
        snapshot={"merged": merged, "survivorBefore": {}, "survivorAfter": after},
    )
    seen: dict[str, Any] = {}

    class _Tx:
        artisan = SimpleNamespace(find_first=lambda **_k: _none())

        async def execute_raw(self, sql, *args):
            if sql.startswith('INSERT INTO "Artisan"'):
                seen["inserted"] = json.loads(args[0])
                raise _Inserted

    async def _none():
        return None

    class _TxContext:
        async def __aenter__(self):
            return _Tx()

        async def __aexit__(self, *_a):
            return False

    async def find_unique(**_k):
        return record

    async def find_first(**_k):
        return None

    async def hold_rows(**_k):
        return holds

    async def rows(_client, ids, lock=False):
        return {"keep": current}

    async def nothing(*_a, **_k):
        return None

    fake = SimpleNamespace(
        artisanmerge=SimpleNamespace(find_unique=find_unique, find_first=find_first),
        aadhaarlegalhold=SimpleNamespace(find_many=hold_rows),
        tx=lambda **_k: _TxContext(),
    )
    monkeypatch.setattr(artisan_merge, "db", fake)
    monkeypatch.setattr(artisan_merge, "_rows", rows)
    monkeypatch.setattr(artisan_merge, "_write_survivor", nothing)
    return artisan_merge, seen


def test_merge_undo_refuses_while_a_record_is_under_an_aadhaar_legal_hold(monkeypatch):
    artisan_merge, seen = _merge_world(
        monkeypatch, holds=[SimpleNamespace(recordType="artisan")], current={"id": "keep"}, after={}, merged={"id": "gone"},
    )
    with pytest.raises(HTTPException) as refused:
        _run(artisan_merge.undo("merge-1", user=SimpleNamespace(id="admin"), settings=SimpleNamespace()))
    assert refused.value.status_code == 409
    assert "inserted" not in seen


def test_merge_undo_does_not_restore_a_withdrawn_aadhaar_verification(monkeypatch):
    artisan_merge, seen = _merge_world(
        monkeypatch,
        holds=[],
        current={"id": "keep", "aadhaarVerifyStatus": None, "aadhaarVerifyName": None},
        after={"aadhaarVerifyStatus": "VERIFIED", "aadhaarVerifyName": "Dummy UIDAI Name"},
        merged={"id": "gone", "name": "Gone", "aadhaarVerifyStatus": "VERIFIED", "aadhaarVerifyName": "Dummy UIDAI Name"},
    )
    with pytest.raises(_Inserted):
        _run(artisan_merge.undo("merge-1", user=SimpleNamespace(id="admin"), settings=SimpleNamespace()))
    assert seen["inserted"]["aadhaarVerifyName"] is None
    assert seen["inserted"]["aadhaarVerifyStatus"] is None
