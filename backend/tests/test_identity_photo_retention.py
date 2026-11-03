import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.routes import design_workshops as routes
from app.services.identity_ocr import (
    DISCARD,
    RETENTION_DECISIONS,
    RETENTION_METADATA_KEY,
    STORE,
    parse_retention,
    retention_of,
    retention_stamp,
    with_retention,
)

_T0 = datetime(2026, 8, 15, 11, 30, tzinfo=UTC)


def _person(role: str = "DESIGNER", *, user_id: str = "usr_1", name: str | None = "Asha Menon"):
    return SimpleNamespace(id=user_id, email="asha@example.test", name=name, role=role)


DESIGNER = _person()
ADMIN = _person("ADMIN", user_id="usr_admin", name="Admin")



def test_the_vocabulary_has_exactly_two_words():
    assert RETENTION_DECISIONS == (DISCARD, STORE)
    assert (DISCARD, STORE) == ("DISCARD", "STORE")


@pytest.mark.parametrize(
    "raw",
    [
        None,
        "",
        "   ",
        "keep",
        "KEEP",
        "retain",
        "yes",
        "true",
        "1",
        True,
        1,
        0,
        ["STORE"],
        {"decision": "STORE"},
        "stor",
        "store!",
        "DISCARD",
        "discard",
    ],
)
def test_anything_that_is_not_the_word_store_resolves_to_discard(raw):
    assert parse_retention(raw) == DISCARD


@pytest.mark.parametrize("raw", ["store", "STORE", "Store", "  store  ", "\tSTORE\n"])
def test_the_word_store_is_honoured_whatever_case_and_padding_it_arrives_in(raw):
    assert parse_retention(raw) == STORE


def test_parse_retention_never_raises_on_anything():
    class Hostile:
        def __str__(self):
            raise RuntimeError("no")

        def __eq__(self, other):
            raise RuntimeError("no")

        def __hash__(self):
            raise RuntimeError("no")

    assert parse_retention(Hostile()) == DISCARD



def test_the_stamp_records_who_decided_and_when_by_name():
    stamp = retention_stamp(STORE, user=DESIGNER, at=_T0)
    assert stamp == {
        "decision": "STORE",
        "decidedById": "usr_1",
        "decidedByName": "Asha Menon",
        "decidedAt": "2026-08-15T11:30:00+00:00",
    }


def test_the_stamp_falls_back_to_the_email_when_an_account_has_no_name():
    stamp = retention_stamp(STORE, user=_person(name=None), at=_T0)
    assert stamp["decidedByName"] == "asha@example.test"


def test_the_stamp_cannot_be_talked_into_saying_store():
    assert retention_stamp("keep", user=DESIGNER, at=_T0)["decision"] == DISCARD


def test_writing_the_decision_keeps_every_other_piece_of_metadata():
    before = {"lateSubmission": {"by": "usr_9"}, "clientNote": "courtyard"}
    after = with_retention(before, retention_stamp(STORE, user=DESIGNER, at=_T0))
    assert after["lateSubmission"] == {"by": "usr_9"}
    assert after["clientNote"] == "courtyard"
    assert after[RETENTION_METADATA_KEY]["decidedById"] == "usr_1"
    assert RETENTION_METADATA_KEY not in before


@pytest.mark.parametrize("metadata", [None, "a string", ["a", "list"], 7, {}, {"other": 1}])
def test_no_decision_reads_back_as_no_decision_whatever_the_column_holds(metadata):
    assert retention_of(metadata) is None


def test_a_recorded_decision_reads_back_whole():
    stamp = retention_stamp(STORE, user=DESIGNER, at=_T0)
    assert retention_of(with_retention({}, stamp)) == stamp



class Row(SimpleNamespace):
    def __getattr__(self, name: str) -> Any:
        if name.startswith("__"):
            raise AttributeError(name)
        return None


class _MediaFiles:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows

    async def find_unique(self, **kwargs: Any) -> Any:
        wanted = (kwargs.get("where") or {}).get("id")
        return next((row for row in self.rows if row.id == wanted), None)

    async def find_first(self, **kwargs: Any) -> Any:
        where = kwargs.get("where") or {}
        for row in self.rows:
            if where.get("objectKey") is not None and row.objectKey != where["objectKey"]:
                continue
            not_id = where.get("id", {}).get("not") if isinstance(where.get("id"), dict) else None
            if not_id is not None and row.id == not_id:
                continue
            return row
        return None

    async def update(self, **kwargs: Any) -> Any:
        from prisma import Json

        row = await self.find_unique(where=kwargs.get("where") or {})
        for key, value in (kwargs.get("data") or {}).items():
            if key == "extraMetadata" and isinstance(value, (dict, list)):
                raise AssertionError("extraMetadata reached the client unwrapped; Postgres refuses it")
            setattr(row, key, value.data if isinstance(value, Json) else value)
        return row

    async def delete(self, **kwargs: Any) -> Any:
        row = await self.find_unique(where=kwargs.get("where") or {})
        if row is not None:
            self.rows.remove(row)
        return row


class _DB:
    def __init__(self, rows: list[Any]) -> None:
        self.mediafile = _MediaFiles(rows)


def _photo(media_id: str = "med_1", **extra) -> Row:
    columns = {
        "id": media_id,
        "originalFilename": f"{media_id}.jpg",
        "mediaType": "IMAGE",
        "objectKey": f"media/usr_1/{media_id}.jpg",
        "uploadedById": "usr_1",
    }
    columns.update(extra)
    return Row(**columns)


def _held_by_no_workshop(monkeypatch) -> None:
    from app.services import design_workshop_posts

    async def _none(_media):
        return set()

    monkeypatch.setattr(design_workshop_posts, "media_design_workshop_ids", _none)


def _decide(monkeypatch, rows, *, user=DESIGNER, media_id="med_1", decision=DISCARD, storage=None):
    deleted: list[str] = []

    def _delete_object(key: str) -> None:
        if storage is not None:
            storage(key)
        deleted.append(key)

    store = _DB(rows)
    monkeypatch.setattr(routes, "db", store)
    monkeypatch.setattr(routes, "delete_object", _delete_object)
    _held_by_no_workshop(monkeypatch)
    body = asyncio.run(
        routes.decide_identity_photograph(mediaId=media_id, decision=decision, current_user=user)
    )
    return SimpleNamespace(body=body, rows=store.mediafile.rows, deleted=deleted)


def test_discard_leaves_nothing_behind(monkeypatch):
    outcome = _decide(monkeypatch, [_photo()])
    assert outcome.rows == []
    assert outcome.deleted == ["media/usr_1/med_1.jpg"]
    assert outcome.body["deleted"] is True
    assert outcome.body["decision"] == DISCARD
    assert outcome.body["retention"] is None


def test_discard_is_what_an_unrecognised_decision_does(monkeypatch):
    for decision in (DISCARD, "", "keep", None):
        outcome = _decide(monkeypatch, [_photo()], decision=decision)
        assert outcome.rows == []
        assert outcome.body["decision"] == DISCARD


def test_store_records_who_kept_it_and_when_and_keeps_the_file(monkeypatch):
    outcome = _decide(monkeypatch, [_photo()], decision="store")
    assert [row.id for row in outcome.rows] == ["med_1"]
    assert outcome.deleted == []
    stamp = outcome.rows[0].extraMetadata[RETENTION_METADATA_KEY]
    assert stamp["decision"] == STORE
    assert stamp["decidedById"] == "usr_1"
    assert stamp["decidedByName"] == "Asha Menon"
    assert datetime.fromisoformat(stamp["decidedAt"]).tzinfo is not None
    assert outcome.body["deleted"] is False


def test_storing_does_not_erase_metadata_the_upload_already_carried(monkeypatch):
    row = _photo(extraMetadata={"lateSubmission": {"by": "usr_9"}})
    outcome = _decide(monkeypatch, [row], decision=STORE)
    assert outcome.rows[0].extraMetadata["lateSubmission"] == {"by": "usr_9"}
    assert RETENTION_METADATA_KEY in outcome.rows[0].extraMetadata


def test_a_shared_object_is_unlinked_but_its_bytes_survive(monkeypatch):
    shared_key = "media/usr_1/shared.jpg"
    rows = [_photo("med_1", objectKey=shared_key), _photo("med_2", objectKey=shared_key)]
    outcome = _decide(monkeypatch, rows)
    assert [row.id for row in outcome.rows] == ["med_2"]
    assert outcome.deleted == []


def test_a_storage_failure_refuses_the_whole_request_and_keeps_the_row(monkeypatch):
    from fastapi import HTTPException

    def _boom(_key: str) -> None:
        raise RuntimeError("bucket unreachable")

    with pytest.raises(HTTPException) as raised:
        _decide(monkeypatch, [_photo()], storage=_boom)
    assert raised.value.status_code == 502
    assert "still attached" in raised.value.detail
    rows = [_photo()]
    with pytest.raises(HTTPException):
        _decide(monkeypatch, rows, storage=_boom)
    assert [row.id for row in rows] == ["med_1"]


def test_a_photograph_somebody_else_attached_is_not_yours_to_decide(monkeypatch):
    from fastapi import HTTPException

    rows = [_photo(uploadedById="usr_other")]
    with pytest.raises(HTTPException) as raised:
        _decide(monkeypatch, rows, user=_person(user_id="usr_1"))
    assert raised.value.status_code == 403
    assert [row.id for row in rows] == ["med_1"]


def test_an_admin_may_decide_about_anybody_s_photograph(monkeypatch):
    outcome = _decide(monkeypatch, [_photo(uploadedById="usr_other")], user=ADMIN)
    assert outcome.rows == []


@pytest.mark.parametrize("role", ["FIELD_CONTRIBUTOR", "RESEARCHER", "PROFESSOR"])
def test_only_the_designer_set_may_decide(monkeypatch, role):
    from fastapi import HTTPException

    rows = [_photo()]
    with pytest.raises(HTTPException) as raised:
        _decide(monkeypatch, rows, user=_person(role, user_id="usr_1"))
    assert raised.value.status_code == 403
    assert [row.id for row in rows] == ["med_1"]


def test_only_a_photograph_can_be_decided_about(monkeypatch):
    from fastapi import HTTPException

    rows = [_photo(mediaType="AUDIO")]
    with pytest.raises(HTTPException) as raised:
        _decide(monkeypatch, rows)
    assert raised.value.status_code == 422
    assert [row.id for row in rows] == ["med_1"]


def test_a_photograph_already_gone_is_a_sentence_not_a_crash(monkeypatch):
    from fastapi import HTTPException

    with pytest.raises(HTTPException) as raised:
        _decide(monkeypatch, [], media_id="med_gone")
    assert raised.value.status_code == 404
    assert "already have been deleted" in raised.value.detail



class _DatabaseTouched(Exception):
    pass


class _Tripwire:
    def __getattr__(self, name: str):
        raise _DatabaseTouched(name)


@pytest.fixture
def read_api(monkeypatch):
    import sys

    import httpx
    from fastapi import FastAPI

    import app.core.db as core_db
    from app.core import deps
    from app.services.identity_ocr import IdentityCandidate, IdentityOcrResult

    tripwire = _Tripwire()
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", tripwire)
    for module in list(sys.modules.values()):
        if getattr(module, "__name__", "").startswith("app.") and getattr(module, "db", None) is real_db:
            monkeypatch.setattr(module, "db", tripwire)

    monkeypatch.setattr(
        routes,
        "get_identity_ocr_settings",
        lambda: SimpleNamespace(enabled=True, max_image_bytes=8 * 1024 * 1024),
    )

    async def _read(content: bytes, mime_type: str):
        return IdentityOcrResult(
            aadhaar=(
                IdentityCandidate(
                    value="234567890128", kind="AADHAAR", confidence=0.7, masked="XXXX XXXX 0128"
                ),
            ),
            provider="gemini",
        )

    monkeypatch.setattr(routes, "read_identity_card", _read)

    app = FastAPI()
    app.include_router(routes.router, prefix="/api")
    caller = {"user": DESIGNER}
    app.dependency_overrides[deps.get_current_user] = lambda: caller["user"]

    def call(data: dict[str, str] | None = None):
        async def run():
            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(transport=transport, base_url="http://identity.test") as client:
                return await client.post(
                    "/api/design-workshops/ocr/identity",
                    files={"file": ("card.jpg", b"\xff" * 512, "image/jpeg")},
                    data=data or {},
                )

        return asyncio.run(run())

    return SimpleNamespace(call=call, caller=caller)


def test_a_client_that_says_nothing_about_retention_gets_discard(read_api):
    response = read_api.call()
    assert response.status_code == 200
    assert response.json()["photograph"] == {
        "stored": False,
        "retention": "DISCARD",
        "decisionRoute": "/design-workshops/ocr/identity/retention",
    }


def test_asking_this_route_to_store_does_not_make_it_store(read_api):
    response = read_api.call({"retention": "store"})
    assert response.status_code == 200
    body = response.json()["photograph"]
    assert body["retention"] == "STORE"
    assert body["stored"] is False


def test_the_read_route_still_never_touches_the_database(read_api):
    assert read_api.call({"retention": "STORE"}).status_code == 200



def _http_decide(monkeypatch, rows, body, *, user=DESIGNER):
    import httpx
    from fastapi import FastAPI

    from app.core import deps

    store = _DB(rows)
    monkeypatch.setattr(routes, "db", store)
    monkeypatch.setattr(routes, "delete_object", lambda key: None)

    app = FastAPI()
    app.include_router(routes.router, prefix="/api")
    app.dependency_overrides[deps.get_current_user] = lambda: user

    async def run():
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://identity.test") as client:
            return await client.post("/api/design-workshops/ocr/identity/retention", json=body)

    response = asyncio.run(run())
    return SimpleNamespace(
        status_code=response.status_code,
        body=response.json() if response.content else {},
        rows=store.mediafile.rows,
    )


def test_the_body_the_client_sends_is_the_body_the_route_takes(monkeypatch):
    outcome = _http_decide(monkeypatch, [_photo()], {"mediaId": "med_1", "decision": "DISCARD"})
    assert outcome.status_code == 200
    assert outcome.rows == []


def test_over_http_a_body_with_no_decision_at_all_still_deletes(monkeypatch):
    outcome = _http_decide(monkeypatch, [_photo()], {"mediaId": "med_1"})
    assert outcome.status_code == 200
    assert outcome.body["decision"] == DISCARD
    assert outcome.rows == []


def test_over_http_store_keeps_it_and_names_the_keeper(monkeypatch):
    outcome = _http_decide(monkeypatch, [_photo()], {"mediaId": "med_1", "decision": "STORE"})
    assert outcome.status_code == 200
    assert [row.id for row in outcome.rows] == ["med_1"]
    assert outcome.body["retention"]["decidedByName"] == "Asha Menon"
