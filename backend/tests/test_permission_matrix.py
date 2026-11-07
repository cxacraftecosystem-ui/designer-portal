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
from app.services import design_workshop_inspectors
from app.services.artisan_identity import verhoeff_ok

ALL_ROLES = (
    "CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER", "DESIGNER", "INSPECTOR",
    "PROFESSOR", "ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN",
)
LOWER_TIERS = ("CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR")
RECORD_CREATORS = ("RESEARCHER", "DESIGNER", "INSPECTOR")


def _user(role: str, user_id: str = "u1", **grants: Any) -> SimpleNamespace:
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
    for last in "0123456789":
        candidate = f"22345678901{last}"
        if verhoeff_ok(candidate):
            return candidate
    raise AssertionError("no Verhoeff-valid Aadhaar in the candidate range")


LOCATION = {
    "latitude": 22.57,
    "longitude": 88.36,
    "state": "West Bengal",
    "district": "Kolkata",
}
CRAFT_BODY = {"name": "Test craft", "subCategoryId": "sub_1", "giStatus": "NON_GI"}
WORKSHOP_BODY = {"title": "Test workshop", "date": "2026-10-01", "place": "Kolkata", "location": LOCATION}
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
    pass


class _Tripwire:
    def __init__(self) -> None:
        object.__setattr__(self, "touched", False)
        object.__setattr__(self, "_preloaded", {})

    def preload(self, name: str, delegate: Any) -> None:
        self._preloaded[name] = delegate

    def reset(self) -> None:
        object.__setattr__(self, "touched", False)
        object.__setattr__(self, "_preloaded", {})

    def __getattr__(self, name: str) -> Any:
        preloaded = object.__getattribute__(self, "_preloaded")
        if name in preloaded:
            return preloaded[name]
        object.__setattr__(self, "touched", True)
        raise _DatabaseTouched(name)


class _Outcome:
    def __init__(self, *, reached: bool, status_code: int | None = None, detail: Any = "") -> None:
        self.reached = reached
        self.status_code = status_code
        self.detail = str(detail)

    @property
    def refused(self) -> bool:
        return self.status_code == 403

    def __repr__(self) -> str:  # pragma: no cover - only ever read out of a failure message
        return "reached-handler" if self.reached else f"HTTP {self.status_code}: {self.detail}"


_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


_APP = _build_app()


class _Api:
    def __init__(self, tripwire: _Tripwire) -> None:
        self.tripwire = tripwire

    def as_(self, user: Any) -> _Api:
        _CURRENT["user"] = user
        return self

    def preload(self, name: str, delegate: Any) -> _Api:
        self.tripwire.preload(name, delegate)
        return self

    def call(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        files: dict[str, Any] | None = None,
    ) -> _Outcome:
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
    tripwire = _Tripwire()
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", tripwire)
    for module in list(sys.modules.values()):
        if getattr(module, "__name__", "").startswith("app.") and getattr(module, "db", None) is real_db:
            monkeypatch.setattr(module, "db", tripwire)
    harness = _Api(tripwire)
    harness.preload("artisanmerge", SimpleNamespace(find_many=_returning([]), find_first=_returning(None)))
    yield harness
    _CURRENT["user"] = None


def _returning(value: Any):
    async def call(*_args: Any, **_kwargs: Any) -> Any:
        return value

    return call



@pytest.mark.parametrize("role", RECORD_CREATORS)
@pytest.mark.parametrize("path,body", TAXONOMY)
def test_a_researcher_or_designer_cannot_create_or_update_a_craft_or_workshop(
    api: _Api, path: str, body: dict, role: str
) -> None:
    caller = api.as_(_user(role))

    created = caller.call("POST", path, body)
    updated = caller.call("PATCH", f"{path}/x1", body)

    assert (created.refused, updated.refused) == (True, True), (created, updated)
    assert api.tripwire.touched is False


@pytest.mark.parametrize("path,body", TAXONOMY)
def test_the_old_per_user_grant_no_longer_opens_that_door(api: _Api, path: str, body: dict) -> None:
    caller = api.as_(_user("RESEARCHER", canManageCrafts=True, canManageWorkshops=True))

    created = caller.call("POST", path, body)
    updated = caller.call("PATCH", f"{path}/x1", body)

    assert (created.refused, updated.refused) == (True, True), (created, updated)


@pytest.mark.parametrize("path,body", TAXONOMY)
def test_a_professor_updates_but_may_not_delete(api: _Api, path: str, body: dict) -> None:
    caller = api.as_(_user("PROFESSOR"))

    updated = caller.call("PATCH", f"{path}/x1", body)
    api.tripwire.reset()
    deleted = caller.call("DELETE", f"{path}/x1")

    assert updated.reached, updated
    assert deleted.refused, deleted
    assert api.tripwire.touched is False, "the delete was refused before reading the record"


def test_a_professor_still_creates_a_craft(api: _Api) -> None:
    caller = api.as_(_user("PROFESSOR"))

    created = caller.call("POST", "/crafts", CRAFT_BODY)

    assert created.reached, created


def test_a_professor_may_no_longer_open_a_workshop(api: _Api) -> None:
    caller = api.as_(_user("PROFESSOR"))

    created = caller.call("POST", "/workshops", WORKSHOP_BODY)

    assert created.refused, created
    assert api.tripwire.touched is False


def test_a_ministry_admin_opens_a_workshop(api: _Api) -> None:
    caller = api.as_(_user("MINISTRY_ADMIN"))

    created = caller.call("POST", "/workshops", WORKSHOP_BODY)

    assert created.reached, created


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


def test_a_typed_craft_name_never_mints_a_craft(api: _Api, monkeypatch: pytest.MonkeyPatch) -> None:
    from app.services import craft_master

    real_resolve = craft_master.resolve_record_craft
    resolved: list[dict[str, Any]] = []

    async def _spy(*args: Any, **kwargs: Any) -> dict[str, Any]:
        data = await real_resolve(*args, **kwargs)
        resolved.append(dict(data))
        return data

    monkeypatch.setattr(craft_master, "resolve_record_craft", _spy)
    caller = api.as_(_user("RESEARCHER", canManageCrafts=True))
    from app.api.routes import artisans

    async def _no_duplicates(*_args: Any, **_kwargs: Any) -> list:
        return []

    monkeypatch.setattr(artisans, "_likely_duplicates", _no_duplicates)
    minted: list[Any] = []

    async def _mint(**kwargs: Any) -> None:
        minted.append(kwargs)

    caller.preload(
        "craft", SimpleNamespace(find_unique=_returning(None), find_many=_returning([]), create=_mint)
    )
    caller.preload("craftspelling", SimpleNamespace(find_unique=_returning(None), find_many=_returning([])))
    caller.preload("artisan", SimpleNamespace(find_first=_returning(None), find_many=_returning([])))

    body = {**ARTISAN_BODY, "craftName": "A craft nobody added yet"}
    body.pop("craftId")
    outcome = caller.call("POST", "/artisans", body)

    assert minted == [], "a typed craft name created a craft"
    assert outcome.reached, outcome
    assert resolved, "the typed craft name was never resolved"
    assert not resolved[-1].get("craftId")
    assert "craftName" not in resolved[-1]
    kept = resolved[-1]["extraMetadata"][craft_master.LEGACY_TEXT_KEY]
    assert craft_master.normalise(kept) == craft_master.normalise("A craft nobody added yet")



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
    assert api.as_(_user(role)).call("POST", path, body).reached


@pytest.mark.parametrize("role", RECORD_CREATORS)
def test_a_researcher_or_designer_opens_the_fifth_record_type_an_interview(api: _Api, role: str) -> None:
    api.preload("questionnaireinterview", SimpleNamespace(find_unique=_returning(None), find_first=_returning(None)))

    assert api.as_(_user(role)).call("POST", "/questionnaire/interviews", INTERVIEW_BODY).reached



@pytest.mark.parametrize("role", LOWER_TIERS)
def test_a_volunteer_still_attaches_media_to_an_existing_record(
    api: _Api, monkeypatch: pytest.MonkeyPatch, role: str
) -> None:
    monkeypatch.setattr(media, "presign_put_url", lambda key, mime, *size: f"https://upload.test/{key}")
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
    folded: list[str] = []

    async def fold(existing, payload, current_user, check=None):
        folded.append(current_user.role)
        return {"id": existing.id}

    monkeypatch.setattr(questionnaire, "merge_into_interview", fold)
    api.preload("questionnaireinterview", SimpleNamespace(find_unique=_returning(SimpleNamespace(id="i1")), find_first=_returning(SimpleNamespace(id="i1"))))

    outcome = api.as_(_user(role)).call("POST", "/questionnaire/interviews", INTERVIEW_BODY)

    assert outcome.status_code == 201, outcome
    assert folded == [role]


@pytest.mark.parametrize("role", LOWER_TIERS)
def test_a_volunteer_may_not_open_an_interview_for_an_artisan_set_that_has_none(api: _Api, role: str) -> None:
    api.preload("questionnaireinterview", SimpleNamespace(find_unique=_returning(None), find_first=_returning(None)))

    outcome = api.as_(_user(role)).call("POST", "/questionnaire/interviews", INTERVIEW_BODY)

    assert outcome.refused, outcome
    assert "Researcher access or above" in outcome.detail


@pytest.mark.parametrize("role", LOWER_TIERS)
def test_a_volunteer_still_comments_on_an_existing_record(
    api: _Api, monkeypatch: pytest.MonkeyPatch, role: str
) -> None:
    monkeypatch.setattr(data_access, "_resolve_record_owner", _returning("someone-else"))
    monkeypatch.setattr(data_access, "effective_tier_for_record", _returning("COMMENT"))

    outcome = api.as_(_user(role)).call(
        "POST", "/data-access/comments", {"recordType": "artisan", "recordId": "a1", "body": "Seen at the fair."}
    )

    assert outcome.reached, outcome


TRANSCRIBE_CLIP = {"file": ("dictation.webm", b"\x00" * 32, "audio/webm")}
BELOW_ADMIN = (
    "CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER", "DESIGNER", "INSPECTOR", "PROFESSOR",
    "ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN",
)


@pytest.fixture
def transcriptions(monkeypatch: pytest.MonkeyPatch) -> list[str]:
    sent: list[str] = []

    async def _transcribe(file: Any, _settings: Any) -> dict[str, Any]:
        sent.append(file.filename)
        return {"available": True, "status": "COMPLETED", "text": "dabu resist printing"}

    async def _unpaused() -> bool:
        return False

    monkeypatch.setattr(media.app_settings, "load_transcription_paused", _unpaused)
    monkeypatch.setattr(media, "transcribe_audio", _transcribe)
    return sent


@pytest.mark.parametrize("role", ("ADMIN", "MASTER_ADMIN"))
def test_the_pause_holds_a_clip_that_is_not_a_media_row(
    api: _Api, transcriptions: list[str], monkeypatch: pytest.MonkeyPatch, role: str
) -> None:
    async def _paused() -> bool:
        return True

    monkeypatch.setattr(media.app_settings, "load_transcription_paused", _paused)
    outcome = api.as_(_user(role)).call("POST", "/media/transcribe", files=TRANSCRIBE_CLIP)

    assert outcome.status_code == 200, outcome
    assert "'paused': True" in outcome.detail, outcome
    assert transcriptions == []


@pytest.mark.parametrize("role", BELOW_ADMIN)
def test_no_rank_below_admin_hands_a_recording_to_the_transcription_provider(
    api: _Api, transcriptions: list[str], role: str
) -> None:
    outcome = api.as_(_user(role)).call("POST", "/media/transcribe", files=TRANSCRIBE_CLIP)

    assert outcome.refused, outcome
    assert transcriptions == [], "an artisan's voice reached the provider chain past the gate"


@pytest.mark.parametrize("role", ("ADMIN", "MASTER_ADMIN"))
def test_an_admin_still_transcribes_a_clip_that_is_not_a_media_row(
    api: _Api, transcriptions: list[str], role: str
) -> None:
    outcome = api.as_(_user(role)).call("POST", "/media/transcribe", files=TRANSCRIBE_CLIP)

    assert outcome.status_code == 200, outcome
    assert transcriptions == ["dictation.webm"]



def test_a_professor_edits_the_work_of_everyone_ranked_below_them() -> None:
    professor = _user("PROFESSOR")

    assert deps.can_edit_others_record(professor, "RESEARCHER") is True
    assert deps.can_edit_others_record(professor, "FIELD_CONTRIBUTOR") is True
    assert deps.can_edit_others_record(professor, "CROWDSOURCE_VOLUNTEER") is True
    assert deps.can_edit_others_record(professor, "PROFESSOR") is False
    assert deps.can_edit_others_record(professor, "ADMIN") is False
    assert deps.can_edit_others_record(professor, None) is True


def test_the_edit_ladder_is_the_review_ladder_and_not_a_second_reading_of_it() -> None:
    for role in ("PROFESSOR", "ADMIN", "MASTER_ADMIN"):
        editor = _user(role)
        for creator_role in ALL_ROLES:
            assert deps.can_edit_others_record(editor, creator_role) == deps.can_review_record(editor, creator_role), (
                role,
                creator_role,
            )


def test_editing_is_narrower_than_reviewing_below_professor() -> None:
    for role in ("FIELD_CONTRIBUTOR", "RESEARCHER"):
        reviewer = _user(role)
        assert deps.can_review_record(reviewer, "CROWDSOURCE_VOLUNTEER") is True
        assert deps.can_edit_others_record(reviewer, "CROWDSOURCE_VOLUNTEER") is False


def test_a_professor_is_privileged_over_a_lower_ranked_authors_record(monkeypatch: pytest.MonkeyPatch) -> None:
    lookups: list[str] = []

    async def find_unique(where: dict, **_: Any) -> Any:
        lookups.append(where["id"])
        return _user("RESEARCHER", user_id=where["id"])

    monkeypatch.setattr(deps, "db", SimpleNamespace(user=SimpleNamespace(find_unique=find_unique)))

    assert asyncio.run(deps.may_edit_lower_ranked_record(_user("PROFESSOR"), "author")) is True
    assert asyncio.run(deps.may_edit_lower_ranked_record(_user("RESEARCHER"), "author")) is False
    assert asyncio.run(deps.may_edit_lower_ranked_record(_user("PROFESSOR"), None)) is False
    assert lookups == ["author"]


def test_the_craft_and_workshop_predicates_read_rank_alone() -> None:
    for role in ALL_ROLES:
        granted = _user(role, canManageCrafts=True, canManageWorkshops=True)
        expected = deps.has_rank(granted, "PROFESSOR")
        assert deps.can_manage_crafts(granted) is expected, role
        assert deps.can_manage_workshops(granted) is expected, role


DESIGNER_SET = (
    "DESIGNER",
    "MINISTRY_ADMIN", "REGIONAL_DIRECTOR", "ASSISTANT_DIRECTOR",
    "ADMIN", "MASTER_ADMIN",
)
OUTSIDE_DESIGNER_SET = (
    "CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER",
    "INSPECTOR", "PROFESSOR",
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
    outcome = api.as_(_user(role)).call(method, path, body)

    assert outcome.refused, outcome
    assert "Designer access or above" in outcome.detail
    assert api.tripwire.touched is False


@pytest.mark.parametrize("method,path,body", WORKSHOP_WRITES)
@pytest.mark.parametrize("role", DESIGNER_SET)
def test_the_designer_set_reaches_the_workshop_write(
    api: _Api, method: str, path: str, body: dict, role: str
) -> None:
    assert api.as_(_user(role)).call(method, path, body).reached


INSPECTOR_WRITES = [
    ("POST", "/design-workshop-inspections/w1/feedback", {"note": "The cost sheet does not add up."}),
    ("POST", "/design-workshop-inspections/w1/send-back", {"note": "The cost sheet does not add up."}),
]

INSPECTION_HOLDERS = tuple(
    role for role in ALL_ROLES if role in design_workshop_inspectors.INSPECTION_HOLDER_ROLES
)

OUTSIDE_INSPECTION_SET = tuple(role for role in ALL_ROLES if role not in INSPECTION_HOLDERS)


@pytest.mark.parametrize("method,path,body", INSPECTOR_WRITES)
@pytest.mark.parametrize("role", OUTSIDE_INSPECTION_SET)
def test_nobody_who_may_hold_no_inspection_files_a_correction_suggestion(
    api: _Api, method: str, path: str, body: dict, role: str
) -> None:
    outcome = api.as_(_user(role)).call(method, path, body)

    assert outcome.refused, outcome
    assert api.tripwire.touched is False, (
        "the refusal must be decided from the caller's role alone; a gate that runs after the "
        "loader is a gate somebody can reorder without noticing"
    )


@pytest.mark.parametrize("method,path,body", INSPECTOR_WRITES)
@pytest.mark.parametrize("role", INSPECTION_HOLDERS)
def test_whoever_may_hold_an_inspection_reaches_the_two_write_doors(
    api: _Api, method: str, path: str, body: dict, role: str
) -> None:
    assert api.as_(_user(role)).call(method, path, body).reached



def test_this_matrix_still_covers_every_tier_that_exists() -> None:
    assert set(ALL_ROLES) == set(deps.ROLE_RANK), (
        "a role exists that this matrix never exercises — add it to ALL_ROLES and to whichever "
        "tier-specific tuples below the divider it belongs in"
    )
    assert list(ALL_ROLES) == sorted(ALL_ROLES, key=lambda role: deps.ROLE_RANK[role])

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

    between = {
        role
        for role in deps.ROLE_RANK
        if deps.can_create_records(_user(role)) and not deps.can_manage_crafts(_user(role))
    }
    assert set(RECORD_CREATORS) == between, (
        "RECORD_CREATORS is no longer 'may open a record, may not edit the taxonomy' — a tier was "
        "added between Researcher and Professor, or one of those two floors moved"
    )


CONTENT_ADMINS = ("MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN")
NOT_CONTENT_ADMINS = tuple(role for role in ALL_ROLES if role not in CONTENT_ADMINS)

CONTENT_DELETES = [
    ("DELETE", "/artisans/x1"),
    ("DELETE", "/products/x1"),
    ("DELETE", "/tools/x1"),
    ("DELETE", "/processes/x1"),
    ("DELETE", "/crafts/x1"),
    ("DELETE", "/workshops/x1"),
    ("DELETE", "/design-workshops/x1"),
    ("POST", "/design-workshops/x1/restore"),
    ("DELETE", "/media/x1"),
    ("DELETE", "/data-access/comments/x1"),
    ("DELETE", "/questionnaire/sections/x1"),
    ("DELETE", "/questionnaire/questions/x1"),
    ("DELETE", "/questionnaire/interviews/x1"),
    ("POST", "/questionnaire/interviews/x1/merge-into/x2"),
    ("DELETE", "/questionnaires/x1/entries/e1"),
    ("POST", "/questionnaires/x1/entries/e1/restore"),
    ("DELETE", "/tasks/x1"),
    ("DELETE", "/tasks/batch/b1"),
    ("DELETE", "/workshop-types/x1"),
    ("DELETE", "/workshops/unmapped/artisans/x1"),
    ("DELETE", "/map/coverage/aliases/x1"),
    ("DELETE", "/sanction-orders/x1"),
    ("DELETE", "/annual-plan/entries/x1"),
]


def test_the_content_admin_set_is_the_three_admin_tiers() -> None:
    assert frozenset({"ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"}) == deps.CONTENT_ADMIN_ROLES
    assert set(CONTENT_ADMINS) == set(deps.CONTENT_ADMIN_ROLES)
    assert not deps.is_admin(_user("MINISTRY_ADMIN"))
    for role in ALL_ROLES:
        assert deps.is_content_admin(_user(role)) is (role in CONTENT_ADMINS), role


@pytest.mark.parametrize("role", NOT_CONTENT_ADMINS)
@pytest.mark.parametrize("method,path", CONTENT_DELETES)
def test_no_role_below_the_content_admins_deletes(api: _Api, method: str, path: str, role: str) -> None:
    outcome = api.as_(_user(role)).call(method, path)

    assert outcome.refused, outcome
    assert api.tripwire.touched is False, "the delete was refused before reading a row"


@pytest.mark.parametrize("role", CONTENT_ADMINS)
@pytest.mark.parametrize("method,path", CONTENT_DELETES)
def test_every_content_admin_deletes(api: _Api, method: str, path: str, role: str) -> None:
    outcome = api.as_(_user(role)).call(method, path)

    assert outcome.reached, outcome


AUTHOR_OR_ADMIN_DELETES = [
    ("DELETE", "/questionnaires/x1"),
    ("POST", "/questionnaires/x1/restore"),
    ("DELETE", "/questionnaires/x1/questions/q1"),
]


class _AuthoredForm:
    def __init__(self) -> None:
        self.find_unique = _returning(
            SimpleNamespace(id="x1", ownerId="u1", deletedAt=None, designWorkshopId=None)
        )

    async def update(self, *_args: Any, **_kwargs: Any) -> Any:
        raise _DatabaseTouched("questionnaire.update")


@pytest.mark.parametrize("method,path", AUTHOR_OR_ADMIN_DELETES)
def test_the_forms_author_deletes_restores_and_removes_its_questions(
    api: _Api, method: str, path: str
) -> None:
    outcome = api.preload("questionnaire", _AuthoredForm()).as_(_user("DESIGNER", "u1")).call(
        method, path
    )

    assert outcome.reached, outcome


@pytest.mark.parametrize("role", CONTENT_ADMINS)
@pytest.mark.parametrize("method,path", AUTHOR_OR_ADMIN_DELETES)
def test_a_content_admin_deletes_somebody_elses_form(
    api: _Api, method: str, path: str, role: str
) -> None:
    outcome = api.preload("questionnaire", _AuthoredForm()).as_(_user(role, "u2")).call(method, path)

    assert outcome.reached, outcome


@pytest.mark.parametrize("role", NOT_CONTENT_ADMINS)
@pytest.mark.parametrize("method,path", AUTHOR_OR_ADMIN_DELETES)
def test_nobody_else_deletes_a_form(api: _Api, method: str, path: str, role: str) -> None:
    outcome = api.preload("questionnaire", _AuthoredForm()).as_(_user(role, "u2")).call(method, path)

    assert outcome.refused, outcome


AUTHOR_OR_ADMIN_EDITS = [
    ("PATCH", "/questionnaires/x1", {"title": "Renamed"}),
    ("POST", "/questionnaires/x1/sections", {"title": "New section"}),
    ("PATCH", "/questionnaires/x1/sections/s1", {"title": "Renamed section"}),
    ("PATCH", "/questionnaires/x1/sections/s1", {"isActive": False}),
    ("POST", "/questionnaires/x1/sections/s1/questions", {"prompt": "A new question?"}),
    ("PATCH", "/questionnaires/x1/questions/q1", {"prompt": "Reworded?"}),
]

_WORKBOOK = {
    "file": (
        "form.xlsx",
        b"not parsed: the parser is the handler's first step past the gate",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    )
}


@pytest.fixture
def reupload_reaches(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.api.routes import questionnaire_forms as routes

    def parsed(*_args: Any, **_kwargs: Any) -> Any:
        raise _DatabaseTouched("parse_questionnaire_workbook")

    monkeypatch.setattr(routes, "parse_questionnaire_workbook", parsed)


@pytest.mark.parametrize("method,path,body", AUTHOR_OR_ADMIN_EDITS)
def test_the_forms_author_edits_it(api: _Api, method: str, path: str, body: dict[str, Any]) -> None:
    outcome = api.preload("questionnaire", _AuthoredForm()).as_(_user("DESIGNER", "u1")).call(
        method, path, body
    )

    assert outcome.reached, outcome


@pytest.mark.parametrize("role", CONTENT_ADMINS)
@pytest.mark.parametrize("method,path,body", AUTHOR_OR_ADMIN_EDITS)
def test_a_content_admin_edits_somebody_elses_form(
    api: _Api, method: str, path: str, body: dict[str, Any], role: str
) -> None:
    outcome = api.preload("questionnaire", _AuthoredForm()).as_(_user(role, "u2")).call(
        method, path, body
    )

    assert outcome.reached, outcome


@pytest.mark.parametrize("role", NOT_CONTENT_ADMINS)
@pytest.mark.parametrize("method,path,body", AUTHOR_OR_ADMIN_EDITS)
def test_nobody_else_edits_a_form(
    api: _Api, method: str, path: str, body: dict[str, Any], role: str
) -> None:
    outcome = api.preload("questionnaire", _AuthoredForm()).as_(_user(role, "u2")).call(
        method, path, body
    )

    assert outcome.refused, outcome


def test_the_forms_author_reuploads_its_workbook(api: _Api, reupload_reaches: None) -> None:
    outcome = api.preload("questionnaire", _AuthoredForm()).as_(_user("DESIGNER", "u1")).call(
        "POST", "/questionnaires/x1/upload", files=_WORKBOOK
    )

    assert outcome.reached, outcome


@pytest.mark.parametrize("role", CONTENT_ADMINS)
def test_a_content_admin_reuploads_somebody_elses_workbook(
    api: _Api, reupload_reaches: None, role: str
) -> None:
    outcome = api.preload("questionnaire", _AuthoredForm()).as_(_user(role, "u2")).call(
        "POST", "/questionnaires/x1/upload", files=_WORKBOOK
    )

    assert outcome.reached, outcome


@pytest.mark.parametrize("role", NOT_CONTENT_ADMINS)
def test_nobody_else_reuploads_a_forms_workbook(
    api: _Api, reupload_reaches: None, role: str
) -> None:
    outcome = api.preload("questionnaire", _AuthoredForm()).as_(_user(role, "u2")).call(
        "POST", "/questionnaires/x1/upload", files=_WORKBOOK
    )

    assert outcome.refused, outcome


def test_the_author_does_not_publish_their_form_to_everyone(api: _Api) -> None:
    outcome = api.preload("questionnaire", _AuthoredForm()).as_(_user("DESIGNER", "u1")).call(
        "PATCH", "/questionnaires/x1", {"isShared": True}
    )

    assert outcome.refused, outcome


@pytest.mark.parametrize("role", CONTENT_ADMINS)
def test_a_content_admin_publishes_a_form_to_everyone(api: _Api, role: str) -> None:
    outcome = api.preload("questionnaire", _AuthoredForm()).as_(_user(role, "u2")).call(
        "PATCH", "/questionnaires/x1", {"isShared": True}
    )

    assert outcome.reached, outcome


class _Comments:
    def __init__(self) -> None:
        self.find_unique = _returning(SimpleNamespace(id="c1", authorId="u1"))

    async def update(self, *_args: Any, **_kwargs: Any) -> Any:
        raise _DatabaseTouched("entrycomment.update")


def test_the_author_edits_their_own_comment(api: _Api) -> None:
    outcome = api.preload("entrycomment", _Comments()).as_(_user("RESEARCHER", "u1")).call(
        "PATCH", "/data-access/comments/c1", {"body": "Corrected."}
    )

    assert outcome.reached, outcome


@pytest.mark.parametrize("role", ["RESEARCHER", "ADMIN", "MASTER_ADMIN"])
def test_nobody_else_edits_a_comment(api: _Api, role: str) -> None:
    outcome = api.preload("entrycomment", _Comments()).as_(_user(role, "u2")).call(
        "PATCH", "/data-access/comments/c1", {"body": "Rewritten."}
    )

    assert outcome.refused, outcome


def test_an_uploader_no_longer_deletes_their_own_media(api: _Api) -> None:
    api.preload(
        "mediafile", SimpleNamespace(find_unique=_returning(SimpleNamespace(id="m1", uploadedById="u1")))
    )

    outcome = api.as_(_user("RESEARCHER", "u1")).call("DELETE", "/media/m1")

    assert outcome.refused, outcome


def test_an_author_no_longer_deletes_their_own_comment(api: _Api) -> None:
    outcome = api.preload("entrycomment", _Comments()).as_(_user("RESEARCHER", "u1")).call(
        "DELETE", "/data-access/comments/c1"
    )

    assert outcome.refused, outcome
