"""Who may CREATE, UPDATE and DELETE each record type — the confirmed permission matrix, as tests.

    ADMIN (50) / MASTER_ADMIN (60)  everything, everywhere.
    PROFESSOR (40)                  create + update crafts and workshops, but NOT delete them;
                                    edit the data of anyone ranked below them. NOT a design
                                    workshop — see the last block of this file.
    DESIGNER (35)                   every record type a researcher may open — artisan, product,
                                    tool, process and interview, all five driven below — plus the
                                    writes inside a design workshop that no other tier has. NOT
                                    crafts or workshops: those are a rank floor at Professor, and
                                    35 < 40.
    RESEARCHER (30)                 create an artisan, product, tool, process or interview.
    FIELD_CONTRIBUTOR (20) /        create NOTHING. They populate what already exists: attach media
    CROWDSOURCE_VOLUNTEER (10)      to an artisan, answer an existing interview, comment on a record.

DESIGNER SAT OUTSIDE ``ALL_ROLES`` UNTIL 2026-08-22, so every LADDER-WIDE test in this file — the
ones that iterate the whole tuple, namely the edit/review-ladder identity and the crafts-and-workshops
rank predicates — skipped the account this product is built for. Not the whole file: ``BELOW_ADMIN``
below has carried DESIGNER all along and drives it over real HTTP against the transcription refusal,
where it is the row the consent 409 was written for. The hole was the tuple, not the coverage.
Nothing failed when the tier was added — the gates were already right — but "already right" and
"asserted" are different states, and the two rows that matter are the non-monotonic ones: a designer
is refused the taxonomy a professor may edit, and a professor is refused the workshop writes a
designer may perform. A ladder read as a floor gets both of those backwards, and neither direction
had a test.

The last line is the one worth testing hardest. Tightening creation is a two-line change and it is
very easy to tighten it one endpoint too far — POST /questionnaire/interviews in particular is BOTH
the "open a new interview" call and, for an artisan set that already has one, the "answer the
existing interview" call. Refusing the whole endpoint would silently remove the contribution path
the two bottom tiers exist for, and every test below the divider is there to catch that.

NOTHING HERE TOUCHES A DATABASE. The real routers are mounted and driven over HTTP with ``db``
swapped for a tripwire that raises on the first delegate anybody reads off it. That makes the two
outcomes unambiguous and needs no schema: a refusal is an HTTP 403 with the tripwire never touched
(so the gate fired before any work), and an authorisation is the tripwire raising (so every guard
passed and the handler body began). Assertions phrased as "not 403" would also pass for a route
that 404s or 422s for an unrelated reason; these two cannot. A test that needs the handler to get
PAST a particular read hands that one delegate over with ``preload``.
"""

import asyncio
import sys
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.core.db as core_db
from app.api.router import api_router
from app.api.routes import data_access, media, questionnaire
from app.core import deps
from app.services.artisan_identity import verhoeff_ok

#: Every tier, in ladder order. It must BE ``deps.ROLE_RANK`` and not a hand-kept copy of it —
#: ``test_this_matrix_still_covers_every_tier_that_exists`` below is the assertion that says so,
#: because a tuple that silently lags the ladder is precisely how DESIGNER stayed out of every
#: LADDER-WIDE test in this file for as long as the tier has existed. It was never wholly untested
#: here — the ``BELOW_ADMIN`` block further down has always driven it — but the tests that iterate
#: the ladder itself could not see it.
ALL_ROLES = (
    "CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER", "DESIGNER", "INSPECTOR",
    "PROFESSOR", "ADMIN", "MASTER_ADMIN",
)
LOWER_TIERS = ("CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR")
#: The tiers that may OPEN a record but may not touch the TAXONOMY — everything at or above the
#: Researcher floor and below the Professor one, which today is exactly these two. Hand-written
#: because a parametrisation has to be, and tied back to those two predicates in
#: ``test_this_matrix_still_covers_every_tier_that_exists`` so a new tier landing in the gap cannot
#: quietly miss both halves of the rule this tuple exists to pin.
#:
#: INSPECTOR (37) JOINED THIS TUPLE THE DAY THE TIER LANDED, and the assertion below is what made
#: that a decision rather than an accident: the tier is between the two floors, so `can_create_records`
#: (Researcher and above) already said yes and `can_manage_crafts` (Professor and above) already said
#: no. An inspector may therefore open an artisan or a product. That is the ladder being inclusive
#: rather than a power granted to inspectors on purpose — but it is what the code does, so it is
#: written down here rather than discovered later.
RECORD_CREATORS = ("RESEARCHER", "DESIGNER", "INSPECTOR")


def _user(role: str, user_id: str = "u1", **grants: Any) -> SimpleNamespace:
    """A user row. Every grantable capability defaults to False; pass one to turn it on."""
    flags = {
        "canManageCrafts": False,
        "canManageWorkshops": False,
        "canManageQuestionnaire": False,
        "canDownloadDataset": False,
        "canReview": False,
        "canViewProvenance": False,
    }
    flags.update(grants)
    return SimpleNamespace(id=user_id, email=f"{user_id}@example.test", name="Test", role=role, **flags)


def _valid_aadhaar() -> str:
    """A 12-digit number the create schema accepts, checked with the app's own Verhoeff routine
    rather than hard-coded — a literal here would rot the day the validator is tightened."""
    for last in "0123456789":
        candidate = f"22345678901{last}"
        if verhoeff_ok(candidate):
            return candidate
    raise AssertionError("no Verhoeff-valid Aadhaar in the candidate range")


# A create carries both halves of a location: the coordinate the device reported, and the state and
# district the researcher stated about the subject. The second pair is not decoration here — since
# 20260727120000_location_stated_address it is required on create (see require_location in
# app/schemas/common.py), because a nullable column nobody has to fill is how `district` stayed empty
# on every record for as long as it has been a shipped export column.
LOCATION = {
    "latitude": 22.57,
    "longitude": 88.36,
    "state": "West Bengal",
    "district": "Kolkata",
}
CRAFT_BODY = {"name": "Test craft"}
WORKSHOP_BODY = {"title": "Test workshop", "place": "Kolkata", "location": LOCATION}
ARTISAN_BODY = {
    "name": "Test artisan",
    "place": "Kolkata",
    "aadhaarNumber": _valid_aadhaar(),
    "dos": "Greet the artisan first.",
    "donts": "Do not photograph without asking.",
    "craftId": "c1",
    "location": LOCATION,
}
PRODUCT_BODY = {
    "craftName": "Test craft",
    "place": "Kolkata",
    "artisanName": "Test artisan",
    "productName": "Test product",
    "location": LOCATION,
}
TOOL_BODY = {
    "craftName": "Test craft",
    "place": "Kolkata",
    "artisanName": "Test artisan",
    "toolkitName": "Test toolkit",
    "location": LOCATION,
}
PROCESS_BODY = {"name": "Test process", "productId": "p1"}
INTERVIEW_BODY = {"title": "Test interview", "location": LOCATION, "artisanIds": ["a1"]}

CREATE_BODIES = [
    ("/artisans", ARTISAN_BODY),
    ("/products", PRODUCT_BODY),
    ("/tools", TOOL_BODY),
    ("/processes", PROCESS_BODY),
]
TAXONOMY = [("/crafts", CRAFT_BODY), ("/workshops", WORKSHOP_BODY)]


class _DatabaseTouched(Exception):
    """The route's guards all passed and its body started working — the whole "allowed" assertion."""


class _Tripwire:
    """Stands in for ``db``. Reading any delegate off it means the handler got past every guard,
    unless the test explicitly handed that delegate over with ``preload``.

    ``db.tx()`` NEEDS NO SPECIAL CASE, AND THAT WAS CHECKED RATHER THAN ASSUMED (2026-09-03). The
    seven record PATCH routes and both review writes now open ``async with db.tx()``; ``tx`` is an
    attribute like any other, so ``__getattr__`` below sets ``touched`` and raises the same
    ``_DatabaseTouched`` it raises for ``artisan`` or ``craft``, and an outcome built from it means
    exactly what it means everywhere else in this file. Giving the class an explicit ``tx`` METHOD
    would be strictly worse: the raise would move from attribute lookup to call, and the ``touched``
    bookkeeping would have to be written a second time to stay true.

    The ordering the ``touched is False`` assertions rely on is also unchanged, for the mechanical
    reason that every one of those PATCH routes reads its record first: ``require_record(db.craft,
    ...)`` and ``require_record(db.workshop, ...)`` sit ABOVE their ``db.tx()``, so the wire is
    tripped where it always was and no refusal that used to arrive as a status code now arrives as
    ``reached``.
    """

    def __init__(self) -> None:
        object.__setattr__(self, "touched", False)
        object.__setattr__(self, "_preloaded", {})

    def preload(self, name: str, delegate: Any) -> None:
        self._preloaded[name] = delegate

    def reset(self) -> None:
        object.__setattr__(self, "touched", False)
        object.__setattr__(self, "_preloaded", {})

    def __getattr__(self, name: str) -> Any:
        # __getattr__, not __getattribute__, so the real attributes above stay readable.
        preloaded = object.__getattribute__(self, "_preloaded")
        if name in preloaded:
            return preloaded[name]
        object.__setattr__(self, "touched", True)
        raise _DatabaseTouched(name)


class _Outcome:
    """Either "the gate refused it" or "the handler ran" — never a bare status code, so a 404 or a
    422 from an unrelated cause can never be mistaken for either one."""

    def __init__(self, *, reached: bool, status_code: int | None = None, detail: Any = "") -> None:
        self.reached = reached
        self.status_code = status_code
        self.detail = str(detail)

    @property
    def refused(self) -> bool:
        return self.status_code == 403

    def __repr__(self) -> str:  # pragma: no cover - only ever read out of a failure message
        return "reached-handler" if self.reached else f"HTTP {self.status_code}: {self.detail}"


# The whole API, assembled ONCE. Twenty-eight routers with their response models cost a couple of
# seconds to build, and rebuilding them per test turned a fast suite into a two-minute one. Nothing
# request-scoped lives on it: the caller comes from ``_CURRENT`` and the database from the fixture.
_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


_APP = _build_app()


class _Api:
    """The real routers, driven as one caller at a time."""

    def __init__(self, tripwire: _Tripwire) -> None:
        self.tripwire = tripwire

    def as_(self, user: Any) -> "_Api":
        _CURRENT["user"] = user
        return self

    def preload(self, name: str, delegate: Any) -> "_Api":
        """Let the handler get past one specific database read, for the tests whose decision point
        is behind it."""
        self.tripwire.preload(name, delegate)
        return self

    def call(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        files: dict[str, Any] | None = None,
    ) -> _Outcome:
        """``files`` sends a real multipart body, for the endpoints that take an upload.

        It is not decoration on those routes: FastAPI validates the declared form parts, so a JSON body
        would 422 on the missing ``file`` and a test of the GATE would pass without the gate existing.
        Both cannot be sent at once — a multipart request has no JSON body — so ``json`` is dropped
        whenever parts are given.
        """

        async def run() -> _Outcome:
            transport = httpx.ASGITransport(app=_APP)
            async with httpx.AsyncClient(transport=transport, base_url="http://matrix.test") as client:
                response = await client.request(
                    method, f"/api{path}", json=body if files is None else None, files=files
                )
            payload = response.json() if response.content else {}
            detail = payload.get("detail", payload) if isinstance(payload, dict) else payload
            return _Outcome(reached=False, status_code=response.status_code, detail=detail)

        try:
            return asyncio.run(run())
        except _DatabaseTouched:
            return _Outcome(reached=True)


@pytest.fixture
def api(monkeypatch: pytest.MonkeyPatch) -> _Api:
    """Mount the real API with every module's ``db`` replaced.

    The modules do ``from app.core.db import db``, so each holds its OWN reference and patching the
    source alone would miss all of them. Rebinding by identity finds every one already imported and
    keeps finding them when a new module is added.
    """
    tripwire = _Tripwire()
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", tripwire)
    for module in list(sys.modules.values()):
        if getattr(module, "__name__", "").startswith("app.") and getattr(module, "db", None) is real_db:
            monkeypatch.setattr(module, "db", tripwire)
    yield _Api(tripwire)
    _CURRENT["user"] = None


def _returning(value: Any):
    async def call(*_args: Any, **_kwargs: Any) -> Any:
        return value

    return call


# --- Crafts and workshops: rank, and nothing but rank --------------------------------------------


@pytest.mark.parametrize("role", RECORD_CREATORS)
@pytest.mark.parametrize("path,body", TAXONOMY)
def test_a_researcher_or_designer_cannot_create_or_update_a_craft_or_workshop(
    api: _Api, path: str, body: dict, role: str
) -> None:
    """DESIGNER is the row worth having here. The taxonomy is a rank FLOOR at Professor and a
    designer ranks 35, so they are refused it — even though they may open every record type a
    researcher can and hold writes inside a workshop that a professor does not. Read as "designers
    are senior contributors" this looks like an inconsistency; it is the ladder working."""
    caller = api.as_(_user(role))

    created = caller.call("POST", path, body)
    updated = caller.call("PATCH", f"{path}/x1", body)

    assert (created.refused, updated.refused) == (True, True), (created, updated)
    # The refusal happened in the dependency, before the route read a single row.
    assert api.tripwire.touched is False


@pytest.mark.parametrize("path,body", TAXONOMY)
def test_the_old_per_user_grant_no_longer_opens_that_door(api: _Api, path: str, body: dict) -> None:
    """THE HOLE THIS CLOSES. ``canManageCrafts`` / ``canManageWorkshops`` used to sit in an OR beside
    the rank floor, so a master admin could hand craft or workshop authorship to a researcher — or to
    a volunteer — without their role column ever changing. Rank is now the only criterion; the
    columns survive in the schema, unread, so restoring the old behaviour is one clause."""
    caller = api.as_(_user("RESEARCHER", canManageCrafts=True, canManageWorkshops=True))

    created = caller.call("POST", path, body)
    updated = caller.call("PATCH", f"{path}/x1", body)

    assert (created.refused, updated.refused) == (True, True), (created, updated)


@pytest.mark.parametrize("path,body", TAXONOMY)
def test_a_professor_creates_and_updates_but_may_not_delete(api: _Api, path: str, body: dict) -> None:
    caller = api.as_(_user("PROFESSOR"))

    created = caller.call("POST", path, body)
    updated = caller.call("PATCH", f"{path}/x1", body)
    api.tripwire.reset()
    deleted = caller.call("DELETE", f"{path}/x1")

    assert created.reached and updated.reached, (created, updated)
    assert deleted.refused, deleted
    assert api.tripwire.touched is False, "the delete was refused before reading the record"


@pytest.mark.parametrize("role", ["ADMIN", "MASTER_ADMIN"])
@pytest.mark.parametrize("path,body", TAXONOMY)
def test_admins_create_update_and_delete(api: _Api, role: str, path: str, body: dict) -> None:
    caller = api.as_(_user(role))

    outcomes = [
        caller.call("POST", path, body),
        caller.call("PATCH", f"{path}/x1", body),
        caller.call("DELETE", f"{path}/x1"),
    ]

    assert all(outcome.reached for outcome in outcomes), outcomes


def test_a_new_craft_cannot_be_minted_sideways_through_the_artisan_form(api: _Api) -> None:
    """``craftName`` on POST /artisans creates the craft when nothing matches it — the same write
    POST /crafts guards, reached from another route. Both ask ``can_manage_crafts``, so the grant
    that no longer opens the front door does not open this one either."""
    caller = api.as_(_user("RESEARCHER", canManageCrafts=True))
    # Let the name lookup answer "no such craft", which is the branch that decides.
    caller.preload("craft", SimpleNamespace(find_unique=_returning(None)))

    body = {**ARTISAN_BODY, "craftName": "A craft nobody added yet"}
    body.pop("craftId")
    outcome = caller.call("POST", "/artisans", body)

    assert outcome.refused, outcome
    assert "ask a professor or an admin to add it" in outcome.detail


# --- Creating a record: Researcher and above ------------------------------------------------------


@pytest.mark.parametrize("path,body", CREATE_BODIES)
@pytest.mark.parametrize("role", LOWER_TIERS)
def test_the_two_bottom_tiers_cannot_open_a_new_record(api: _Api, path: str, body: dict, role: str) -> None:
    outcome = api.as_(_user(role)).call("POST", path, body)

    assert outcome.refused, outcome
    assert "Researcher access or above" in outcome.detail
    assert api.tripwire.touched is False


@pytest.mark.parametrize("role", RECORD_CREATORS)
@pytest.mark.parametrize("path,body", CREATE_BODIES)
def test_a_researcher_or_designer_opens_all_four_record_types(
    api: _Api, path: str, body: dict, role: str
) -> None:
    """The designer half is the point of the workshop product: the stage forms embed these very
    creates, so a designer refused here would be a designer who cannot fill a workshop."""
    assert api.as_(_user(role)).call("POST", path, body).reached


@pytest.mark.parametrize("role", RECORD_CREATORS)
def test_a_researcher_or_designer_opens_the_fifth_record_type_an_interview(api: _Api, role: str) -> None:
    """The FIFTH type, which is not in ``CREATE_BODIES`` because it is not a plain create.

    ``POST /questionnaire/interviews`` is two operations behind one path: for an artisan set that
    already has an interview it FOLDS into it (open to every tier, asserted below the divider), and
    only for a set that has none is it a create. The matrix asserted the refusing half for the two
    bottom tiers and never the admitting half, so "a designer may open every record type a
    researcher can" was a claim about four types dressed as a claim about five.

    ``questionnaireinterview`` is preloaded to return ``None`` — mirroring
    ``test_a_volunteer_may_not_open_an_interview_for_an_artisan_set_that_has_none``, whose refusal
    is only meaningful because the fold could not happen. Past the gate the handler writes the
    subject location first, so the tripwire fires there and ``reached`` means exactly what it means
    everywhere else in this file.
    """
    api.preload("questionnaireinterview", SimpleNamespace(find_unique=_returning(None)))

    assert api.as_(_user(role)).call("POST", "/questionnaire/interviews", INTERVIEW_BODY).reached


# --- The lower tiers keep every path they exist for ----------------------------------------------
#
# Everything below this line is a REGRESSION guard, not a new rule. If one of these starts failing
# because creation was tightened, the tightening went too far and the crowdsourcing path is broken.


@pytest.mark.parametrize("role", LOWER_TIERS)
def test_a_volunteer_still_attaches_media_to_an_existing_record(
    api: _Api, monkeypatch: pytest.MonkeyPatch, role: str
) -> None:
    """Both halves of an upload: asking for the signed URL, then registering the finished object
    against an existing artisan. Neither is gated by record creation, and neither may become so."""
    monkeypatch.setattr(media, "presign_put_url", lambda key, mime: f"https://upload.test/{key}")
    monkeypatch.setattr(media, "public_url_for_key", lambda key: f"https://cdn.test/{key}")
    caller = api.as_(_user(role))

    presigned = caller.call(
        "POST",
        "/media/presign",
        {"filename": "loom.jpg", "mimeType": "image/jpeg", "mediaType": "IMAGE", "sizeBytes": 1024},
    )
    completed = caller.call(
        "POST",
        "/media/complete",
        {
            "originalFilename": "loom.jpg",
            "mediaType": "IMAGE",
            "mimeType": "image/jpeg",
            "sizeBytes": 1024,
            "objectKey": "media/u1/loom.jpg",
            "linkedRecordType": "artisan",
            "linkedRecordId": "a1",
        },
    )

    assert presigned.status_code == 200, presigned
    assert completed.reached, completed


@pytest.mark.parametrize("role", LOWER_TIERS)
def test_a_volunteer_still_answers_an_interview_that_already_exists(
    api: _Api, monkeypatch: pytest.MonkeyPatch, role: str
) -> None:
    """POST /questionnaire/interviews for an artisan set that ALREADY has one folds into it — that is
    how both apps submit answers to a shared entry. The create gate sits after the fold for exactly
    this reason, and this is the test that says so."""
    folded: list[str] = []

    async def fold(existing, payload, current_user, check=None):
        folded.append(current_user.role)
        return {"id": existing.id}

    monkeypatch.setattr(questionnaire, "merge_into_interview", fold)
    api.preload("questionnaireinterview", SimpleNamespace(find_unique=_returning(SimpleNamespace(id="i1"))))

    outcome = api.as_(_user(role)).call("POST", "/questionnaire/interviews", INTERVIEW_BODY)

    assert outcome.status_code == 201, outcome
    assert folded == [role]


@pytest.mark.parametrize("role", LOWER_TIERS)
def test_a_volunteer_may_not_open_an_interview_for_an_artisan_set_that_has_none(api: _Api, role: str) -> None:
    """The other half of the same endpoint: nothing to fold into means this really is a create."""
    api.preload("questionnaireinterview", SimpleNamespace(find_unique=_returning(None)))

    outcome = api.as_(_user(role)).call("POST", "/questionnaire/interviews", INTERVIEW_BODY)

    assert outcome.refused, outcome
    assert "Researcher access or above" in outcome.detail


@pytest.mark.parametrize("role", LOWER_TIERS)
def test_a_volunteer_still_comments_on_an_existing_record(
    api: _Api, monkeypatch: pytest.MonkeyPatch, role: str
) -> None:
    """Commenting is gated by the sharing tier (owner/admin/COMMENT grant) and by nothing about rank.
    Given the tier, the bottom two tiers reach the write exactly as anyone else does."""
    monkeypatch.setattr(data_access, "_resolve_record_owner", _returning("someone-else"))
    monkeypatch.setattr(data_access, "effective_tier_for_record", _returning("COMMENT"))

    outcome = api.as_(_user(role)).call(
        "POST", "/data-access/comments", {"recordType": "artisan", "recordId": "a1", "body": "Seen at the fair."}
    )

    assert outcome.reached, outcome


# --- Handing a recording to a transcription provider ----------------------------------------------
#
# ``POST /media/transcribe`` takes a clip nobody has uploaded, hands it to ``ai.transcribe_audio_bytes``
# and returns the words. It writes no MediaFile row, queues no job and leaves no record that it ran.
#
# IT WAS ``get_current_user`` — every signed-in account, down to the volunteer tier that cannot create
# a single record — and that made it the widest of the three doors onto one provider chain. The other
# two are the design-workshop dictation routes: the per-workshop one is gated on the artisan's recorded
# ``dictationConsent``, and its id-less sibling was retired to a 410 for being a second way in that
# could consult no consent at all. A designer refused by that 409 could re-post the identical clip here.
#
# The rule asserted below is the one the STORED-clip twin already had: ``POST /media/{id}/transcribe-now``
# is admin-only. It does not make this route consent-aware — nothing could, since the request names no
# workshop — but it removes every rank for whom the consent gate is a refusal that means something.

TRANSCRIBE_CLIP = {"file": ("dictation.webm", b"\x00" * 32, "audio/webm")}
BELOW_ADMIN = (
    "CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER", "DESIGNER", "INSPECTOR", "PROFESSOR",
)


@pytest.fixture
def transcriptions(monkeypatch: pytest.MonkeyPatch) -> list[str]:
    """Records every clip that reached the provider chain, and lets none of them get there.

    The refusals have to be asserted against the CALL and not against the status code: with no API key
    configured this endpoint answers 200 ``UNAVAILABLE`` to a caller it let through, so "not a 403"
    would be indistinguishable from "the provider was never asked" on this machine and would differ on
    a developer's machine that has a key in ``.env``.
    """
    sent: list[str] = []

    async def _transcribe(file: Any, _settings: Any) -> dict[str, Any]:
        sent.append(file.filename)
        return {"available": True, "status": "COMPLETED", "text": "dabu resist printing"}

    monkeypatch.setattr(media, "transcribe_audio", _transcribe)
    return sent


@pytest.mark.parametrize("role", BELOW_ADMIN)
def test_no_rank_below_admin_hands_a_recording_to_the_transcription_provider(
    api: _Api, transcriptions: list[str], role: str
) -> None:
    """DESIGNER is the row that matters: they are the account the consent 409 is written for."""
    outcome = api.as_(_user(role)).call("POST", "/media/transcribe", files=TRANSCRIBE_CLIP)

    assert outcome.refused, outcome
    assert transcriptions == [], "an artisan's voice reached the provider chain past the gate"


@pytest.mark.parametrize("role", ("ADMIN", "MASTER_ADMIN"))
def test_an_admin_still_transcribes_a_clip_that_is_not_a_media_row(
    api: _Api, transcriptions: list[str], role: str
) -> None:
    """The other half, and a regression guard rather than a new rule: ``scripts/live-smoke.ps1`` signs
    in as MASTER_ADMIN and posts here, so narrowing this one tier further would break the live smoke
    with no other caller left to notice."""
    outcome = api.as_(_user(role)).call("POST", "/media/transcribe", files=TRANSCRIBE_CLIP)

    assert outcome.status_code == 200, outcome
    assert transcriptions == ["dictation.webm"]


# --- "Below them", decided once ------------------------------------------------------------------


def test_a_professor_edits_the_work_of_everyone_ranked_below_them() -> None:
    professor = _user("PROFESSOR")

    assert deps.can_edit_others_record(professor, "RESEARCHER") is True
    assert deps.can_edit_others_record(professor, "FIELD_CONTRIBUTOR") is True
    assert deps.can_edit_others_record(professor, "CROWDSOURCE_VOLUNTEER") is True
    # Strictly below: a peer's record is not theirs to overwrite.
    assert deps.can_edit_others_record(professor, "PROFESSOR") is False
    assert deps.can_edit_others_record(professor, "ADMIN") is False
    # A record with no creator role on file is treated as a researcher's, as the review ladder does.
    assert deps.can_edit_others_record(professor, None) is True


def test_the_edit_ladder_is_the_review_ladder_and_not_a_second_reading_of_it() -> None:
    """Two rank rules that disagree by one tier is how a privilege bug hides. Above the Professor
    floor the edit rule must BE ``can_review_record``, tier for tier, with no exceptions."""
    for role in ("PROFESSOR", "ADMIN", "MASTER_ADMIN"):
        editor = _user(role)
        for creator_role in ALL_ROLES:
            assert deps.can_edit_others_record(editor, creator_role) == deps.can_review_record(editor, creator_role), (
                role,
                creator_role,
            )


def test_editing_is_narrower_than_reviewing_below_professor() -> None:
    """The review ladder deliberately reaches further down than editing: a field contributor reviews
    a volunteer's submission and a researcher reviews a field contributor's. Neither of them gets to
    rewrite it — only Professor and above edit other people's work."""
    for role in ("FIELD_CONTRIBUTOR", "RESEARCHER"):
        reviewer = _user(role)
        assert deps.can_review_record(reviewer, "CROWDSOURCE_VOLUNTEER") is True
        assert deps.can_edit_others_record(reviewer, "CROWDSOURCE_VOLUNTEER") is False


def test_a_professor_is_privileged_over_a_lower_ranked_authors_record(monkeypatch: pytest.MonkeyPatch) -> None:
    """``may_edit_lower_ranked_record`` is the async half — the creator's role looked up once. It
    must cost that lookup ONLY for the ranks the rule can apply to."""
    lookups: list[str] = []

    async def find_unique(where: dict, **_: Any) -> Any:
        lookups.append(where["id"])
        return _user("RESEARCHER", user_id=where["id"])

    monkeypatch.setattr(deps, "db", SimpleNamespace(user=SimpleNamespace(find_unique=find_unique)))

    assert asyncio.run(deps.may_edit_lower_ranked_record(_user("PROFESSOR"), "author")) is True
    assert asyncio.run(deps.may_edit_lower_ranked_record(_user("RESEARCHER"), "author")) is False
    assert asyncio.run(deps.may_edit_lower_ranked_record(_user("PROFESSOR"), None)) is False
    # One lookup, for the professor. The researcher and the ownerless record are refused on rank
    # alone, so no ordinary contributor's edit pays a query for a clause that cannot help them.
    assert lookups == ["author"]


def test_the_craft_and_workshop_predicates_read_rank_alone() -> None:
    for role in ALL_ROLES:
        granted = _user(role, canManageCrafts=True, canManageWorkshops=True)
        expected = deps.has_rank(granted, "PROFESSOR")
        assert deps.can_manage_crafts(granted) is expected, role
        assert deps.can_manage_workshops(granted) is expected, role


# --- The design workshop: a SET, and the one place the ladder does not apply ----------------------
#
# Every other rule in this file is "this tier and above". `_require_designer` is
# `deps.can_run_design_workshops`, which is the SET {DESIGNER, ADMIN, MASTER_ADMIN} — so a PROFESSOR
# outranks a designer everywhere in this codebase and still cannot write a workshop, and that
# refusal is the reason this block exists. `tests/test_design_workshop_gate.py` asserts the
# PREDICATE; what it does not do is drive the routes, and the gate is stated inside each handler
# rather than as a dependency, so "the predicate is right" and "the route calls it" are two claims.
#
# THE STAGE SAVE IS THE ROW THAT MATTERS. It is the whole fortnight of fieldwork behind one PUT, and
# it is one of the eighteen routes `_require_designer` guards — a count that lived nowhere until its
# docstring was rewritten to carry it, having previously described the gate as covering "the two
# capture aids".
#
# `{}`-ish bodies are deliberate: all three schemas — `DesignWorkshopUpdate`, `StageSaveIn` and
# `CustomSectionsIn` — are all-optional, so FastAPI validates them before the handler runs and the
# decision reached is the GATE's rather than a 422's.

DESIGNER_SET = ("DESIGNER", "ADMIN", "MASTER_ADMIN")
# INSPECTOR is OUTSIDE, beside PROFESSOR and for the same reason: this block is about who may WRITE
# inside a workshop, and an inspector inspects the result rather than producing it. Both tiers
# outrank a designer and both are refused here, which is the clearest statement in this file that the
# design-workshop gate is set membership and not a rank floor.
OUTSIDE_DESIGNER_SET = (
    "CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER", "INSPECTOR", "PROFESSOR",
)
WORKSHOP_WRITES = [
    ("PATCH", "/design-workshops/w1", {"title": "Renamed in the field"}),
    ("PUT", "/design-workshops/w1/stages/WORKSHOP_SETUP", {"entries": []}),
    ("PUT", "/design-workshops/w1/custom-sections", {"sections": []}),
]


@pytest.mark.parametrize("method,path,body", WORKSHOP_WRITES)
@pytest.mark.parametrize("role", OUTSIDE_DESIGNER_SET)
def test_nobody_outside_the_designer_set_writes_a_workshop(
    api: _Api, method: str, path: str, body: dict, role: str
) -> None:
    """PROFESSOR is in this list on purpose and is the only surprising member of it."""
    outcome = api.as_(_user(role)).call(method, path, body)

    assert outcome.refused, outcome
    assert "Designer access or above" in outcome.detail
    # Refused before the workshop was even looked up, so no row leaks through the timing either.
    assert api.tripwire.touched is False


@pytest.mark.parametrize("method,path,body", WORKSHOP_WRITES)
@pytest.mark.parametrize("role", DESIGNER_SET)
def test_the_designer_set_reaches_the_workshop_write(
    api: _Api, method: str, path: str, body: dict, role: str
) -> None:
    """A "reached" outcome here means the gate passed and `load_workshop_or_404` began — which is
    as far as this file can see, and exactly the right depth: WHICH workshop this account may open is
    ownership, decided by that helper and tested where it lives, not rank."""
    assert api.as_(_user(role)).call(method, path, body).reached


# --- The tuple that let this happen --------------------------------------------------------------


def test_this_matrix_still_covers_every_tier_that_exists() -> None:
    """``ALL_ROLES`` must be the ladder, not a copy of it that was true once.

    DESIGNER existed in ``deps.ROLE_RANK`` for the whole life of the design-workshop product and was
    absent from this tuple the entire time, so no test that ITERATES THE LADDER ever ran as the
    account the product is built for. (``BELOW_ADMIN`` did run it, which is why the claim is about
    the ladder-wide tests and not about the file.) A hand-kept list cannot notice that; this
    assertion can, and it fails on the commit that adds the eighth tier rather than on the audit
    that finds it a year later.

    THE TIER-SPECIFIC TUPLES ARE CHECKED TOO, and that is the half the first draft of this test
    forgot. Closing the hole in ``ALL_ROLES`` while leaving three more hand-kept tuples beside it
    recreates the identical defect one level down: the eighth tier is appended to ``ALL_ROLES`` to
    make the first assertion pass, and is silently absent from the designer block below. So the
    PARTITION is asserted and not merely the membership — every tuple below the divider is tied
    back to the predicate in ``deps`` that decides it.
    """
    assert set(ALL_ROLES) == set(deps.ROLE_RANK), (
        "a role exists that this matrix never exercises — add it to ALL_ROLES and to whichever "
        "tier-specific tuples below the divider it belongs in"
    )
    # Ladder order, so a reader of a parametrised failure sees the tiers in the order they rank.
    assert list(ALL_ROLES) == sorted(ALL_ROLES, key=lambda role: deps.ROLE_RANK[role])

    # The designer block: a SET and its complement, both tied to the server's own frozenset.
    assert set(DESIGNER_SET) == set(deps.DESIGN_WORKSHOP_ROLES), (
        "DESIGNER_SET no longer IS deps.DESIGN_WORKSHOP_ROLES, so the workshop-write block is "
        "asserting a set the server does not have"
    )
    assert not set(DESIGNER_SET) & set(OUTSIDE_DESIGNER_SET), (
        "a tier is listed as both inside and outside the designer set"
    )
    assert set(DESIGNER_SET) | set(OUTSIDE_DESIGNER_SET) == set(deps.ROLE_RANK), (
        "a tier is in neither workshop tuple, so it is neither admitted nor refused by any test "
        "here — put it in DESIGNER_SET or in OUTSIDE_DESIGNER_SET"
    )

    # RECORD_CREATORS is not a set the server declares, so it is derived from the two predicates
    # whose DISAGREEMENT it exists to pin: may open every record type (a floor at Researcher), may
    # not touch the taxonomy (a floor at Professor). Exactly the tiers between 30 and 40.
    between = {
        role
        for role in deps.ROLE_RANK
        if deps.can_create_records(_user(role)) and not deps.can_manage_crafts(_user(role))
    }
    assert set(RECORD_CREATORS) == between, (
        "RECORD_CREATORS is no longer 'may open a record, may not edit the taxonomy' — a tier was "
        "added between Researcher and Professor, or one of those two floors moved"
    )
