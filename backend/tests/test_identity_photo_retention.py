"""A photograph of an identity card is kept because a person said so, or it is gone.

WHAT WAS TRUE BEFORE THIS FILE EXISTED. ``POST /design-workshops/ocr/identity`` never stored the
bytes it was sent, and said so in a comment — but the design workshop's stage form does not reach
that route with a loose file. ``IdentityCardReader`` sits UNDER a media field and reads the number
off photographs the designer has already attached there, and attaching there is the ordinary media
flow: a presigned PUT into S3 and a ``MediaFile`` row from ``/media/complete``. So on that surface
an unmasked identity document was durably in the repository BEFORE anybody was offered a thought
about it, and no code anywhere had asked. That is what these tests exist to keep closed.

**WHY AN IMAGE IS THE HOLE AND THE NUMBER IS NOT.** ``artisan_identity.mask_aadhaar`` and
``records.mask_identity_number`` mask the digits on every exported surface, and that machinery is
sound. It cannot touch a JPEG. A photograph of the card IS the number, unmasked, in the one form
the masking rule cannot reach — so "keep the picture" is a decision that has to be made by a named
person, and every other input has to mean "do not keep it".

**THE FOUR PROPERTIES, AND WHAT EACH COSTS IN THE FIELD IF IT STOPS HOLDING:**

1. **The default is DISCARD, for every unparseable, missing or misspelt answer.** A client that
   sends nothing — which is every build shipped before the field existed — must not retain an
   identity document. A 422 instead would be worse than useless: it hands the decision back to the
   thing that just proved it cannot make one.
2. **DISCARD leaves nothing behind, and these tests assert it against the STORE, never a flag.**
   The row is gone from the fake database and the object key reached ``delete_object``. A test that
   checked ``deleted == True`` in the response body would pass just as happily against a soft
   delete, which is the failure being guarded — this file's routes are the one place in
   ``design_workshops.py`` that hard-deletes, and the rest of that file loudly does not.
3. **STORE records WHO and WHEN, by name.** A retained identity document that cannot be traced to
   the person who chose to retain it is the same problem with a longer paper trail.
4. **A storage failure refuses the whole request.** ``media.delete_media`` swallows S3 errors and
   deletes the row anyway — right for a photograph of a loom, wrong here, because it ends with the
   JPEG in the bucket and nothing in the database that knows it exists. Merely hidden.

NO DATABASE AND NO NETWORK. The vocabulary tests are pure functions. The route tests call the
handlers directly over an in-memory ``mediafile`` delegate, so "nothing was left behind" is asserted
by looking in the store rather than by reading the answer the route gave about itself. The one test
that drives HTTP does so because it is about a FORM DEFAULT, and a default can only be observed by
letting FastAPI parse a request that omits the field.
"""

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


# --------------------------------------------------------------------------------------
# The vocabulary: two words, and everything else means "do not keep it"
# --------------------------------------------------------------------------------------


def test_the_vocabulary_has_exactly_two_words():
    """A third retention value would be a third behaviour nobody wrote, and the frontend switch
    would fall through it. Pinned so adding one is a deliberate act with a test to update."""
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
    """**THE SAFETY PROPERTY OF THE WHOLE FEATURE.** Every one of these is something a real client
    could send — an older build that omits the field entirely (``None``), a form that submits an
    empty string, a developer who guessed "keep", a JSON body that sent a boolean because the field
    reads like one, a truncated value off a flaky connection.

    Not one of them may be read as permission to keep an unmasked identity document. The direction
    of the fallback is the whole point: resolving an unrecognised value to STORE would let a typo
    retain somebody's Aadhaar card, and nobody would ever find out, because a retained photograph
    looks exactly like a photograph.

    ``True`` and ``1`` are in the list on purpose. ``bool`` is a subclass of ``int`` and a truthy
    check is the obvious wrong implementation of this function; anything that reads truthiness
    rather than the literal word fails here.
    """
    assert parse_retention(raw) == DISCARD


@pytest.mark.parametrize("raw", ["store", "STORE", "Store", "  store  ", "\tSTORE\n"])
def test_the_word_store_is_honoured_whatever_case_and_padding_it_arrives_in(raw):
    """Case and surrounding whitespace are not ambiguity — they are one intention spelled by four
    different clients. Refusing them would only teach client authors to send something else, and
    "something else" resolves to DISCARD, so a designer who pressed Keep would silently not get it.
    """
    assert parse_retention(raw) == STORE


def test_parse_retention_never_raises_on_anything():
    """It is called on the request path before any guard has run, so an exception here would be a
    500 on a route whose whole job is to be the safe thing to do. Objects with hostile ``__str__``
    and ``__eq__`` are the shape a fuzzed client body actually takes."""

    class Hostile:
        def __str__(self):
            raise RuntimeError("no")

        def __eq__(self, other):
            raise RuntimeError("no")

        def __hash__(self):
            raise RuntimeError("no")

    assert parse_retention(Hostile()) == DISCARD


# --------------------------------------------------------------------------------------
# The stamp: who kept it, and when
# --------------------------------------------------------------------------------------


def test_the_stamp_records_who_decided_and_when_by_name():
    """A retained identity document has to be traceable to a person. ``decidedById`` alone is not
    that: it resolves to nothing once the account is disabled or a leaver is removed, and a record
    that says "somebody decided" is not an accountability record."""
    stamp = retention_stamp(STORE, user=DESIGNER, at=_T0)
    assert stamp == {
        "decision": "STORE",
        "decidedById": "usr_1",
        "decidedByName": "Asha Menon",
        "decidedAt": "2026-08-15T11:30:00+00:00",
    }


def test_the_stamp_falls_back_to_the_email_when_an_account_has_no_name():
    """An account with no display name is ordinary — a service account, an import. The fallback is
    the email rather than "" because an empty name reads as "nobody decided", which is the one
    thing this record must never say when somebody did."""
    stamp = retention_stamp(STORE, user=_person(name=None), at=_T0)
    assert stamp["decidedByName"] == "asha@example.test"


def test_the_stamp_cannot_be_talked_into_saying_store():
    """``retention_stamp`` re-parses its own argument rather than trusting it, so a caller that
    passes a raw client string straight through cannot write a STORE record for a value that
    ``parse_retention`` would have refused. Belt and braces on the one field that matters."""
    assert retention_stamp("keep", user=DESIGNER, at=_T0)["decision"] == DISCARD


def test_writing_the_decision_keeps_every_other_piece_of_metadata():
    """``extraMetadata`` already carries ``stamp_workshop_submission``'s late-submission record and
    whatever the client sent with the upload. Replacing the column instead of merging into it would
    erase one audit record while writing another — and the erased one is the one that says a file
    arrived after the workshop ended."""
    before = {"lateSubmission": {"by": "usr_9"}, "clientNote": "courtyard"}
    after = with_retention(before, retention_stamp(STORE, user=DESIGNER, at=_T0))
    assert after["lateSubmission"] == {"by": "usr_9"}
    assert after["clientNote"] == "courtyard"
    assert after[RETENTION_METADATA_KEY]["decidedById"] == "usr_1"
    # A copy: the caller's dict is not mutated under it.
    assert RETENTION_METADATA_KEY not in before


@pytest.mark.parametrize("metadata", [None, "a string", ["a", "list"], 7, {}, {"other": 1}])
def test_no_decision_reads_back_as_no_decision_whatever_the_column_holds(metadata):
    """``extraMetadata`` is a Json column and it can hold anything some other feature wrote years
    ago. A reader looking for a retention record must answer "there isn't one" rather than raise —
    and must never invent one, because an invented decision is an unowned retained document."""
    assert retention_of(metadata) is None


def test_a_recorded_decision_reads_back_whole():
    stamp = retention_stamp(STORE, user=DESIGNER, at=_T0)
    assert retention_of(with_retention({}, stamp)) == stamp


# --------------------------------------------------------------------------------------
# The route, over an in-memory store. "Nothing left behind" is asserted on the STORE.
# --------------------------------------------------------------------------------------


class Row(SimpleNamespace):
    """A media row answering ``None`` for any column the test did not set, like a Prisma row."""

    def __getattr__(self, name: str) -> Any:
        if name.startswith("__"):
            raise AttributeError(name)
        return None


class _MediaFiles:
    """The ``mediafile`` delegate, honouring only the filters this route actually issues.

    Keeps the rows in a plain list so a test can look in it afterwards. That is the whole design of
    this fixture: the question "did the discard leave anything behind" is answered by reading the
    store, never by reading the response the route wrote about itself.
    """

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
        row = await self.find_unique(where=kwargs.get("where") or {})
        for key, value in (kwargs.get("data") or {}).items():
            setattr(row, key, value)
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
    """A stored identity-card photograph as the stage form would have left it.

    Overrides are MERGED over the defaults rather than passed alongside them, so a test can say
    ``_photo(mediaType="AUDIO")`` — which is the whole point of the helper — instead of being told
    it supplied the argument twice.
    """
    columns = {
        "id": media_id,
        "originalFilename": f"{media_id}.jpg",
        "mediaType": "IMAGE",
        "objectKey": f"media/usr_1/{media_id}.jpg",
        "uploadedById": "usr_1",
    }
    columns.update(extra)
    return Row(**columns)


def _decide(monkeypatch, rows, *, user=DESIGNER, media_id="med_1", decision=DISCARD, storage=None):
    """Call the route directly and hand back what it said AND what the store now holds."""
    deleted: list[str] = []

    def _delete_object(key: str) -> None:
        if storage is not None:
            storage(key)
        deleted.append(key)

    store = _DB(rows)
    monkeypatch.setattr(routes, "db", store)
    monkeypatch.setattr(routes, "delete_object", _delete_object)
    body = asyncio.run(
        routes.decide_identity_photograph(mediaId=media_id, decision=decision, current_user=user)
    )
    return SimpleNamespace(body=body, rows=store.mediafile.rows, deleted=deleted)


def test_discard_leaves_nothing_behind(monkeypatch):
    """**THE PROPERTY THE FEATURE IS FOR.** Asserted against the store and against object storage,
    not against a flag in the reply.

    ``design_workshops.py`` opens by stating that nothing in it hard-deletes — ``DELETE`` sets
    ``deletedAt``, because a designer's fortnight of fieldwork must survive a mis-tap. That rule is
    right for a workshop and exactly inverted for this: a soft-deleted photograph of somebody's
    Aadhaar card is a retained photograph of somebody's Aadhaar card with a flag on it, and the
    designer who pressed "delete this" would be entitled to believe otherwise. So the row must be
    GONE from the table and the bytes GONE from the bucket, and this test would still pass if the
    response body were a lie.
    """
    outcome = _decide(monkeypatch, [_photo()])
    assert outcome.rows == []
    assert outcome.deleted == ["media/usr_1/med_1.jpg"]
    assert outcome.body["deleted"] is True
    assert outcome.body["decision"] == DISCARD
    # No stamp is written on the way out. There is nothing left to stamp, and a surviving record
    # saying "this was discarded" would be a row that still names the artisan's photograph.
    assert outcome.body["retention"] is None


def test_discard_is_what_an_unrecognised_decision_does(monkeypatch):
    """The route parses through ``parse_retention``, so the safe default is not something the
    frontend has to remember to send. A client that posts ``{"mediaId": …}`` and nothing else, or
    posts a misspelling, deletes the photograph rather than keeping it."""
    for decision in (DISCARD, "", "keep", None):
        outcome = _decide(monkeypatch, [_photo()], decision=decision)
        assert outcome.rows == []
        assert outcome.body["decision"] == DISCARD


def test_store_records_who_kept_it_and_when_and_keeps_the_file(monkeypatch):
    """The other half of the choice has to be real too. The row survives, the bytes survive, and the
    file now carries the name of the person who decided that — which is the difference between a
    retained identity document and an unowned one."""
    outcome = _decide(monkeypatch, [_photo()], decision="store")
    assert [row.id for row in outcome.rows] == ["med_1"]
    assert outcome.deleted == []
    stamp = outcome.rows[0].extraMetadata[RETENTION_METADATA_KEY]
    assert stamp["decision"] == STORE
    assert stamp["decidedById"] == "usr_1"
    assert stamp["decidedByName"] == "Asha Menon"
    # A real moment, not a placeholder — parseable back into a datetime.
    assert datetime.fromisoformat(stamp["decidedAt"]).tzinfo is not None
    assert outcome.body["deleted"] is False


def test_storing_does_not_erase_metadata_the_upload_already_carried(monkeypatch):
    """The route merges rather than replaces. Written as a route test as well as a unit test on
    ``with_retention`` because the route is where the column is actually read and written back, and
    a handler that passed ``{}`` instead of the existing value would pass the unit test."""
    row = _photo(extraMetadata={"lateSubmission": {"by": "usr_9"}})
    outcome = _decide(monkeypatch, [row], decision=STORE)
    assert outcome.rows[0].extraMetadata["lateSubmission"] == {"by": "usr_9"}
    assert RETENTION_METADATA_KEY in outcome.rows[0].extraMetadata


def test_a_shared_object_is_unlinked_but_its_bytes_survive(monkeypatch):
    """One object can legitimately back two ``MediaFile`` rows — the same photograph attached to two
    stages. Deleting the bytes for one decision would break an attachment nobody decided anything
    about, so the row goes and the object stays. Mirrors ``media.delete_media``'s guard, minus the
    row being deleted, which that route does not have to exclude because it looks after the delete.
    """
    shared_key = "media/usr_1/shared.jpg"
    rows = [_photo("med_1", objectKey=shared_key), _photo("med_2", objectKey=shared_key)]
    outcome = _decide(monkeypatch, rows)
    assert [row.id for row in outcome.rows] == ["med_2"]
    assert outcome.deleted == []


def test_a_storage_failure_refuses_the_whole_request_and_keeps_the_row(monkeypatch):
    """**THE ORDERING TEST.** ``media.delete_media`` deletes the row first and then makes a
    best-effort attempt at the object, swallowing storage errors — defensible for a photograph of a
    loom, and wrong here, because it ends with the JPEG in the bucket and nothing in the database
    that knows it is there. That is the definition of merely hiding.

    So the object goes first and a failure aborts everything: the row survives, still pointing at
    the bytes, and the designer can press the button again. The refusal is a sentence that says the
    photograph is still attached, because it is.
    """
    from fastapi import HTTPException

    def _boom(_key: str) -> None:
        raise RuntimeError("bucket unreachable")

    with pytest.raises(HTTPException) as raised:
        _decide(monkeypatch, [_photo()], storage=_boom)
    assert raised.value.status_code == 502
    assert "still attached" in raised.value.detail
    # The row was NOT deleted. Re-run over a fresh store to look, since the raise unwound the call.
    rows = [_photo()]
    with pytest.raises(HTTPException):
        _decide(monkeypatch, rows, storage=_boom)
    assert [row.id for row in rows] == ["med_1"]


def test_a_photograph_somebody_else_attached_is_not_yours_to_decide(monkeypatch):
    """Workshops are shared. Without this an account granted a viewer row on somebody else's
    workshop could hard-delete their attachments — with no soft delete to recover from, on the one
    route in this file that means it. Mirrors ``media.delete_media``'s uploader-or-admin rule."""
    from fastapi import HTTPException

    rows = [_photo(uploadedById="usr_other")]
    with pytest.raises(HTTPException) as raised:
        _decide(monkeypatch, rows, user=_person(user_id="usr_1"))
    assert raised.value.status_code == 403
    assert [row.id for row in rows] == ["med_1"]


def test_an_admin_may_decide_about_anybody_s_photograph(monkeypatch):
    """The counterpart to the rule above, and the reason it is not simply "the uploader": somebody
    has to be able to remove a regulated document when the designer who attached it has left."""
    outcome = _decide(monkeypatch, [_photo(uploadedById="usr_other")], user=ADMIN)
    assert outcome.rows == []


@pytest.mark.parametrize("role", ["FIELD_CONTRIBUTOR", "RESEARCHER", "PROFESSOR"])
def test_only_the_designer_set_may_decide(monkeypatch, role):
    """The same gate as the read route: the SET {Designer, Admin, Master Admin}, not a rank
    threshold. A PROFESSOR outranks a designer and is deliberately outside it — and this route
    hard-deletes, so "outranks" is not a reason to hand it the button.

    The store is untouched on the refusal, which is what proves the gate ran before the body.
    """
    from fastapi import HTTPException

    rows = [_photo()]
    with pytest.raises(HTTPException) as raised:
        _decide(monkeypatch, rows, user=_person(role, user_id="usr_1"))
    assert raised.value.status_code == 403
    assert [row.id for row in rows] == ["med_1"]


def test_only_a_photograph_can_be_decided_about(monkeypatch):
    """This is the one route in ``design_workshops.py`` that hard-deletes, so a client bug that sent
    an audio id would destroy an interview recording outright — no ``deletedAt`` to restore from, no
    transcript, nothing. The mediaType check is what stands between that bug and the recording."""
    from fastapi import HTTPException

    rows = [_photo(mediaType="AUDIO")]
    with pytest.raises(HTTPException) as raised:
        _decide(monkeypatch, rows)
    assert raised.value.status_code == 422
    assert [row.id for row in rows] == ["med_1"]


def test_a_photograph_already_gone_is_a_sentence_not_a_crash(monkeypatch):
    """Two designers on one workshop, or one designer pressing twice on a slow connection. The
    second call must not 500, and must say plainly that nothing was changed."""
    from fastapi import HTTPException

    with pytest.raises(HTTPException) as raised:
        _decide(monkeypatch, [], media_id="med_gone")
    assert raised.value.status_code == 404
    assert "already have been deleted" in raised.value.detail


# --------------------------------------------------------------------------------------
# The read route: the default a client gets by sending nothing, over real HTTP
# --------------------------------------------------------------------------------------


class _DatabaseTouched(Exception):
    """The read route reached for the database, which it must never do."""


class _Tripwire:
    def __getattr__(self, name: str):
        raise _DatabaseTouched(name)


@pytest.fixture
def read_api(monkeypatch):
    """The design-workshop router with the provider stubbed and ``db`` replaced by a tripwire.

    The tripwire is the assertion, not scenery: the read route's entire promise is that the bytes it
    is handed touch no store, so a route that grew a storage path would raise here rather than
    quietly return a 200 that a status-code assertion would accept.
    """
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
    """**THE DEFAULT, OBSERVED THE ONLY WAY A DEFAULT CAN BE.** Every Android and web build shipped
    before this field existed posts the file and nothing else, and those builds are in the field on
    handsets that will not be updated this season. The answer they get has to be the safe one, and
    the answer has to be legible in the payload rather than assumed from a comment."""
    response = read_api.call()
    assert response.status_code == 200
    assert response.json()["photograph"] == {
        "stored": False,
        "retention": "DISCARD",
        "decisionRoute": "/design-workshops/ocr/identity/retention",
    }


def test_asking_this_route_to_store_does_not_make_it_store(read_api):
    """``retention`` is the caller's DECLARED intention for its own copy, not an instruction to this
    route — which has no storage path at all, and whose ``stored: false`` is a literal rather than
    an expression for exactly that reason. A client that intends to keep the photograph is told
    plainly that this request did not keep it, so "I sent it with retention=store" can never be
    mistaken for "it was stored"."""
    response = read_api.call({"retention": "store"})
    assert response.status_code == 200
    body = response.json()["photograph"]
    assert body["retention"] == "STORE"
    assert body["stored"] is False


def test_the_read_route_still_never_touches_the_database(read_api):
    """The tripwire raises on the first delegate access, so a passing 200 here is proof the whole
    handler ran without a store — including the new retention branch, which must remain a pure echo.
    """
    assert read_api.call({"retention": "STORE"}).status_code == 200


# --------------------------------------------------------------------------------------
# The decision route over real HTTP: the body shape the browser and the handset actually send
# --------------------------------------------------------------------------------------


def _http_decide(monkeypatch, rows, body, *, user=DESIGNER):
    """POST the decision as a client does, through FastAPI's own request parsing.

    THE TESTS ABOVE CALL THE HANDLER DIRECTLY, WHICH IS THE RIGHT WAY TO ASSERT WHAT IT DID TO THE
    STORE and the wrong way to assert how it is REACHED. A handler whose two parameters are declared
    ``Body(embed=True)`` expects ``{"mediaId": …, "decision": …}`` at the top level of the body, and
    a direct call cannot tell that apart from any other shape — so a server that quietly wanted
    ``{"body": {…}}`` would pass every test above and 422 every real client, which reads on screen as
    "the photograph could not be deleted" and leaves an identity document where it was.
    """
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
    """`lib/identityPhotoRetention.ts` posts exactly this JSON. Pinned here rather than trusted,
    because the failure is silent in the direction that keeps the file: a 422 from a shape mismatch
    is reported by the panel as "that decision could not be recorded", which a designer reads as a
    transient problem and which leaves the photograph on the record indefinitely."""
    outcome = _http_decide(monkeypatch, [_photo()], {"mediaId": "med_1", "decision": "DISCARD"})
    assert outcome.status_code == 200
    assert outcome.rows == []


def test_over_http_a_body_with_no_decision_at_all_still_deletes(monkeypatch):
    """``decision`` has a server-side default and it is the safe one, so a client that sends only
    the id — an older build, a hand-written call — destroys the photograph rather than keeping it.
    The default has to survive FastAPI's parsing, not merely exist in the signature."""
    outcome = _http_decide(monkeypatch, [_photo()], {"mediaId": "med_1"})
    assert outcome.status_code == 200
    assert outcome.body["decision"] == DISCARD
    assert outcome.rows == []


def test_over_http_store_keeps_it_and_names_the_keeper(monkeypatch):
    outcome = _http_decide(monkeypatch, [_photo()], {"mediaId": "med_1", "decision": "STORE"})
    assert outcome.status_code == 200
    assert [row.id for row in outcome.rows] == ["med_1"]
    assert outcome.body["retention"]["decidedByName"] == "Asha Menon"
