import sys
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI, Request

import app.core.db as core_db
from app.api.routes import auth as auth_routes, usage as usage_routes
from app.core import deps
from app.core.security import hash_password
from app.schemas.auth import LoginRequest
from app.services import usage

_SIGN_IN_REQUEST = Request({"type": "http", "client": ("203.0.113.9", 443), "headers": []})

USER_ID = "ckv9r2m4x0001qz8h3n7d2f5g"

NOW = datetime(2026, 8, 30, 12, 0, tzinfo=UTC)

_FIXTURE_PASSWORD_HASH = hash_password("a-designer-password")



class _UserTable:
    def __init__(self, row: Any) -> None:
        self.row = row
        self.updates: list[dict[str, Any]] = []

    async def update(self, *, where: dict[str, Any], data: dict[str, Any]) -> Any:
        self.updates.append({"where": dict(where), "data": dict(data)})
        for key, value in data.items():
            setattr(self.row, key, value)
        return self.row

    async def find_unique(self, *, where: dict[str, Any]) -> Any:
        for key in ("id", "email"):
            if key in where:
                return self.row if where[key] == getattr(self.row, key, None) else None
        return None

    async def find_first(self, **_: Any) -> Any:
        return self.row


class _ConsentDecisionTable:
    def __init__(self) -> None:
        self.rows: list[Any] = []

    async def create(self, *, data: dict[str, Any]) -> Any:
        row = SimpleNamespace(id=f"d-{len(self.rows)}", createdAt=NOW, **data)
        self.rows.append(row)
        return row

    async def find_many(self, **kwargs: Any) -> list[Any]:
        wanted = (kwargs.get("where") or {}).get("userId")
        rows = [row for row in self.rows if getattr(row, "userId", None) == wanted]
        return list(reversed(rows))[: kwargs.get("take") or 50]


class _EventTable:
    def __init__(self) -> None:
        self.batches: list[list[dict[str, Any]]] = []
        self.deletes: list[dict[str, Any]] = []
        self.stored = 0

    async def create_many(self, data: Any) -> int:
        rows = list(data)
        self.batches.append(rows)
        return len(rows)

    async def delete_many(self, *, where: dict[str, Any]) -> int:
        self.deletes.append(dict(where))
        deleted, self.stored = self.stored, 0
        return deleted

    @property
    def rows(self) -> list[dict[str, Any]]:
        return [row for batch in self.batches for row in batch]


def _account(**overrides: Any) -> Any:
    row = SimpleNamespace(
        id=USER_ID,
        email="designer@example.org",
        name="A Designer",
        role="DESIGNER",
        passwordHash=_FIXTURE_PASSWORD_HASH,
        usageConsent="NOT_RECORDED",
        usageConsentAt=None,
        usageConsentBasis=None,
        usageConsentVersion=None,
    )
    for key, value in overrides.items():
        setattr(row, key, value)
    return row


@pytest.fixture
def store(monkeypatch: pytest.MonkeyPatch) -> Iterator[SimpleNamespace]:
    users = _UserTable(_account())
    decisions = _ConsentDecisionTable()
    events = _EventTable()
    fake_db = SimpleNamespace(user=users, usageconsentdecision=decisions, usageevent=events)

    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", fake_db)
    for module in list(sys.modules.values()):
        if (
            getattr(module, "__name__", "").startswith("app.")
            and getattr(module, "db", None) is real_db
        ):
            monkeypatch.setattr(module, "db", fake_db)

    usage.reset_buffer()
    usage.register_known_templates(())
    yield SimpleNamespace(users=users, decisions=decisions, events=events, db=fake_db)
    usage.reset_buffer()
    usage.register_known_templates(())


_CALLER: dict[str, Any] = {"user": None}


def _consent_app() -> FastAPI:
    application = FastAPI()
    application.include_router(usage_routes.router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CALLER["user"]
    return application


@pytest.fixture
def caller() -> Iterator[dict[str, Any]]:
    _CALLER["user"] = None
    yield _CALLER
    _CALLER["user"] = None


async def _request(application: FastAPI, method: str, path: str, **kwargs: Any) -> httpx.Response:
    transport = httpx.ASGITransport(app=application, raise_app_exceptions=False)
    async with httpx.AsyncClient(transport=transport, base_url="http://usage.test") as client:
        return await client.request(method, path, **kwargs)



async def test_a_grant_records_the_circumstance_and_the_text_and_not_only_the_answer(
    store: SimpleNamespace,
) -> None:
    outcome = await usage.record_consent(
        user_id=USER_ID,
        decision=usage.UsageConsent.GRANTED,
        basis=usage.UsageConsentBasis.REQUIRED_AT_SIGN_IN,
        notice_version=usage.NOTICE_VERSION,
        at=NOW,
    )

    assert outcome.decision is usage.UsageConsent.GRANTED
    assert outcome.withdrawal is None, "a grant deletes nothing"

    written = store.users.updates[0]["data"]
    assert written[usage.CONSENT_ATTRIBUTE] == "GRANTED"
    assert written[usage.CONSENT_BASIS_ATTRIBUTE] == "REQUIRED_AT_SIGN_IN"
    assert written[usage.CONSENT_VERSION_ATTRIBUTE] == usage.NOTICE_VERSION
    assert written[usage.CONSENT_AT_ATTRIBUTE] == NOW

    logged = store.decisions.rows[0]
    assert logged.decision == "GRANTED"
    assert logged.basis == "REQUIRED_AT_SIGN_IN", (
        "the circumstance is on the log row too. Without it a history can say what somebody "
        "answered and not whether they were free to answer otherwise, which is the distinction "
        "this whole vocabulary exists to keep."
    )
    assert logged.noticeVersion == usage.NOTICE_VERSION
    assert logged.recordedAt is None, (
        "the answer was given straight against the server, so there is no device clock to record — "
        "copying `at` in here would later read as 'a device reported this', which is false"
    )


async def test_the_column_name_is_the_one_the_recorder_reads_off_the_row(
    store: SimpleNamespace,
) -> None:
    from prisma import models as prisma_models

    fields = set(prisma_models.User.model_fields)
    for attribute in (
        usage.CONSENT_ATTRIBUTE,
        usage.CONSENT_AT_ATTRIBUTE,
        usage.CONSENT_BASIS_ATTRIBUTE,
        usage.CONSENT_VERSION_ATTRIBUTE,
    ):
        assert attribute in fields, (
            f"usage.py reads {attribute!r} off a User row and the column is not there. A getattr "
            f"miss resolves to NOT_RECORDED, so this does not raise anywhere — it just stops "
            f"attributing anything, for everybody."
        )
    decision_fields = set(prisma_models.UsageConsentDecision.model_fields)
    assert {"userId", "decision", "basis", "noticeVersion", "recordedAt", "createdAt"} <= (
        decision_fields
    )


async def test_not_recorded_is_refused_as_a_decision_with_the_next_move_in_the_sentence(
    store: SimpleNamespace,
) -> None:
    with pytest.raises(usage.UsageRuleViolation) as refusal:
        usage.consent_decision_plans(
            user_id=USER_ID,
            decision=usage.UsageConsent.NOT_RECORDED,
            basis=usage.UsageConsentBasis.OFFERED_IN_SETTINGS,
            notice_version=usage.NOTICE_VERSION,
            at=NOW,
        )

    assert "REFUSED" in str(refusal.value)
    assert store.users.updates == [], "nothing was written"


async def test_a_consent_may_not_be_written_onto_the_observations_themselves(
    store: SimpleNamespace,
) -> None:
    with pytest.raises(usage.UsageRuleViolation) as refusal:
        usage.ConsentWritePlan(
            table=usage.OBSERVATION_TABLE,
            operation=usage.ConsentOperation.UPDATE,
            where={"id": USER_ID},
            data={"consentState": "GRANTED"},
        )

    assert usage.OBSERVATION_TABLE in str(refusal.value)
    assert "User" in str(refusal.value), "the refusal names where it should have been written"

    with pytest.raises(usage.UsageRuleViolation):
        usage._consent_model(usage.OBSERVATION_TABLE)


async def test_a_clock_in_the_future_is_refused_rather_than_quietly_corrected(
    store: SimpleNamespace,
) -> None:
    with pytest.raises(usage.UsageRuleViolation) as refusal:
        usage.consent_decision_plans(
            user_id=USER_ID,
            decision=usage.UsageConsent.GRANTED,
            basis=usage.UsageConsentBasis.REQUIRED_AT_SIGN_IN,
            notice_version=usage.NOTICE_VERSION,
            at=NOW,
            recorded_at=NOW + timedelta(days=30),
        )

    detail = str(refusal.value)
    assert "in the future" in detail
    assert "Set the correct date and time on your device" in detail

    plans = usage.consent_decision_plans(
        user_id=USER_ID,
        decision=usage.UsageConsent.GRANTED,
        basis=usage.UsageConsentBasis.REQUIRED_AT_SIGN_IN,
        notice_version=usage.NOTICE_VERSION,
        at=NOW,
        recorded_at=NOW + timedelta(minutes=5),
    )
    assert plans.decision.data["recordedAt"] == NOW + timedelta(minutes=5)
    assert plans.account.data[usage.CONSENT_AT_ATTRIBUTE] == NOW + timedelta(minutes=5), (
        "what lands on the account is when the PERSON answered, not when the server heard it"
    )


async def test_an_unrecognised_notice_version_is_stored_verbatim_rather_than_refused(
    store: SimpleNamespace,
) -> None:
    plans = usage.consent_decision_plans(
        user_id=USER_ID,
        decision=usage.UsageConsent.GRANTED,
        basis=usage.UsageConsentBasis.REQUIRED_AT_SIGN_IN,
        notice_version="1999-01-01.7",
        at=NOW,
    )
    assert plans.decision.data["noticeVersion"] == "1999-01-01.7"
    assert plans.account.data[usage.CONSENT_VERSION_ATTRIBUTE] == "1999-01-01.7"

    with pytest.raises(usage.UsageRuleViolation) as refusal:
        usage.consent_decision_plans(
            user_id=USER_ID,
            decision=usage.UsageConsent.GRANTED,
            basis=usage.UsageConsentBasis.REQUIRED_AT_SIGN_IN,
            notice_version="   ",
            at=NOW,
        )
    assert "which version" in str(refusal.value)



async def test_a_withdrawal_stops_collection_empties_the_buffer_and_deletes_what_was_stored(
    store: SimpleNamespace,
) -> None:
    usage.record_event(
        route_template="/design-workshops/{workshop_id}",
        method="GET",
        status_code=200,
        duration_ms=12,
        user_id=USER_ID,
        consent=usage.UsageConsent.GRANTED,
    )
    assert usage.buffer_stats()["buffered"] == 1
    store.events.stored = 7

    outcome = await usage.record_consent(
        user_id=USER_ID,
        decision=usage.UsageConsent.REFUSED,
        basis=usage.UsageConsentBasis.OFFERED_IN_SETTINGS,
        notice_version=usage.NOTICE_VERSION,
        at=NOW,
    )

    assert store.users.updates[0]["data"][usage.CONSENT_ATTRIBUTE] == "REFUSED"
    assert outcome.withdrawal is not None
    assert outcome.withdrawal.buffered_dropped == 1, "observed and not yet written: thrown away"
    assert outcome.withdrawal.stored_deleted == 7
    assert outcome.withdrawal.stored_delete_ran is True
    assert store.events.deletes == [{"userId": USER_ID}], (
        "the delete names the account, and there is no update blanking userId anywhere"
    )
    assert usage.buffer_stats()["buffered"] == 0
    assert usage.is_withdrawn(USER_ID) is True

    assert (
        usage.record_event(
            route_template="/design-workshops/{workshop_id}",
            method="GET",
            status_code=200,
            duration_ms=12,
            user_id=USER_ID,
            consent=usage.UsageConsent.GRANTED,
        )
        is False
    )


async def test_a_withdrawal_does_not_erase_the_answer_the_collection_was_made_under(
    store: SimpleNamespace,
) -> None:
    await usage.record_consent(
        user_id=USER_ID,
        decision=usage.UsageConsent.GRANTED,
        basis=usage.UsageConsentBasis.REQUIRED_AT_SIGN_IN,
        notice_version=usage.NOTICE_VERSION,
        at=NOW,
    )
    await usage.record_consent(
        user_id=USER_ID,
        decision=usage.UsageConsent.REFUSED,
        basis=usage.UsageConsentBasis.OFFERED_IN_SETTINGS,
        notice_version=usage.NOTICE_VERSION,
        at=NOW + timedelta(days=6),
        note="I would rather not.",
    )

    assert usage.resolve_consent(store.users.row) is usage.UsageConsent.REFUSED
    history = [
        usage.consent_decision_payload(row)
        for row in await usage.consent_history(USER_ID)
    ]
    assert [entry["decision"] for entry in history] == ["REFUSED", "GRANTED"], "newest first"
    assert [entry["basis"] for entry in history] == [
        "OFFERED_IN_SETTINGS",
        "REQUIRED_AT_SIGN_IN",
    ], (
        "the withdrawal was a free choice and the grant was a turnstile. A history that cannot "
        "distinguish them cannot say whether anybody was ever asked freely."
    )
    assert history[0]["note"] == "I would rather not."


async def test_agreeing_again_after_a_withdrawal_actually_resumes_recording(
    store: SimpleNamespace,
) -> None:
    await usage.record_consent(
        user_id=USER_ID,
        decision=usage.UsageConsent.REFUSED,
        basis=usage.UsageConsentBasis.OFFERED_IN_SETTINGS,
        notice_version=usage.NOTICE_VERSION,
        at=NOW,
    )
    assert usage.is_withdrawn(USER_ID) is True

    await usage.record_consent(
        user_id=USER_ID,
        decision=usage.UsageConsent.GRANTED,
        basis=usage.UsageConsentBasis.OFFERED_IN_SETTINGS,
        notice_version=usage.NOTICE_VERSION,
        at=NOW + timedelta(days=1),
    )

    assert usage.is_withdrawn(USER_ID) is False
    recorded = usage.record_event(
        route_template="/design-workshops/{workshop_id}",
        method="GET",
        status_code=200,
        duration_ms=12,
        user_id=USER_ID,
        consent=usage.UsageConsent.GRANTED,
    )
    assert recorded is True
    row = next(iter(usage._BUFFER))
    assert row["userId"] == USER_ID
    assert row["consentState"] == "GRANTED"



async def test_a_new_notice_version_asks_again_without_reclassifying_the_stored_answer(
    store: SimpleNamespace, monkeypatch: pytest.MonkeyPatch
) -> None:
    await usage.record_consent(
        user_id=USER_ID,
        decision=usage.UsageConsent.GRANTED,
        basis=usage.UsageConsentBasis.REQUIRED_AT_SIGN_IN,
        notice_version=usage.NOTICE_VERSION,
        at=NOW,
    )
    account = store.users.row
    settled = usage.consent_gate(account)
    assert settled["required"] is False

    old_version = usage.NOTICE_VERSION
    monkeypatch.setattr(usage, "NOTICE_VERSION", "2027-01-01.1")

    asked_again = usage.consent_gate(account)
    assert asked_again["required"] is True
    assert asked_again["state"] == "GRANTED", "they did agree; the text moved"
    assert asked_again["agreedVersion"] == old_version
    assert old_version in asked_again["reason"]
    assert usage.resolve_consent(account) is usage.UsageConsent.GRANTED, (
        "collection continues under the answer already given. Flipping this to NOT_RECORDED would "
        "blank every designer's own usage on a deploy that changed a sentence."
    )
    assert usage.collection_plan(usage.resolve_consent(account)).attribute is True


async def test_a_refusal_is_not_asked_again_and_costs_the_account_nothing(
    store: SimpleNamespace,
) -> None:
    await usage.record_consent(
        user_id=USER_ID,
        decision=usage.UsageConsent.REFUSED,
        basis=usage.UsageConsentBasis.OFFERED_IN_SETTINGS,
        notice_version=usage.NOTICE_VERSION,
        at=NOW,
    )
    gate = usage.consent_gate(store.users.row)

    assert gate["state"] == "REFUSED"
    assert gate["required"] is False
    assert "Everything else works as normal" in gate["reason"]



async def test_the_notice_is_ungated_and_carries_what_a_person_must_be_told(
    store: SimpleNamespace, caller: dict[str, Any]
) -> None:
    caller["user"] = None
    response = await _request(_consent_app(), "GET", "/usage/consent/notice")

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["version"] == usage.NOTICE_VERSION
    assert body["required"] is True
    assert "need to agree to this to sign in" in body["requiredSentence"]
    assert "given at sign-in" in body["requiredSentence"]
    assert any("Which screen you opened" in line for line in body["collects"])
    assert any("Your name" in line for line in body["collects"])
    assert any("IP address" in line for line in body["doesNotCollect"])
    assert "not how long you waited" in body["durationCaveat"]
    assert any("is deleted" in line for line in body["withdrawal"]["does"])
    assert "does not sign you out" in body["withdrawal"]["costsNothing"]
    assert body["retention"] == "Kept until you withdraw or your account is deleted."
    assert set(body["readableBy"]) == {"You", "Admins", "The master admin"}
    assert body["document"] == ""


async def test_every_read_route_in_this_module_is_named_in_the_notice(
    store: SimpleNamespace,
) -> None:
    named = set(usage.READ_ROUTE_READERS)
    served = {
        route.path
        for route in usage_routes.router.routes
        if "GET" in getattr(route, "methods", set())
        and not getattr(route, "path", "").startswith("/usage/consent")
    }
    assert served == named, (
        f"routes not named in usage.READ_ROUTE_READERS: {sorted(served - named)}; named but not "
        f"served: {sorted(named - served)}"
    )
    assert set(usage.READ_ROUTE_READERS.values()) == set(usage.readable_by())


async def test_recording_an_answer_needs_no_permission_and_drops_the_cached_identity(
    store: SimpleNamespace, caller: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    dropped: list[str | None] = []
    monkeypatch.setattr(usage_routes, "invalidate_cached_user", dropped.append)
    caller["user"] = store.users.row

    response = await _request(
        _consent_app(),
        "POST",
        "/usage/consent",
        json={
            "decision": "GRANTED",
            "basis": "REQUIRED_AT_SIGN_IN",
            "noticeVersion": usage.NOTICE_VERSION,
        },
    )

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["consent"]["state"] == "GRANTED"
    assert body["consent"]["basis"] == "REQUIRED_AT_SIGN_IN"
    assert body["gate"]["required"] is False
    assert body["decisions"][0]["decision"] == "GRANTED"
    assert dropped == [USER_ID], (
        "the cached identity was not dropped; resolve_consent would go on reading the pre-answer "
        "row for the life of the cache entry"
    )


async def test_the_withdraw_route_supplies_its_own_basis_and_reports_what_it_deleted(
    store: SimpleNamespace, caller: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(usage_routes, "invalidate_cached_user", lambda _user_id: None)
    caller["user"] = store.users.row
    store.events.stored = 3

    response = await _request(
        _consent_app(),
        "POST",
        "/usage/consent/withdraw",
        json={"noticeVersion": usage.NOTICE_VERSION, "note": "no thanks"},
    )

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["consent"]["state"] == "REFUSED"
    assert body["consent"]["basis"] == "OFFERED_IN_SETTINGS", (
        "the route chose the basis; there is no field on the body for a client to choose it with"
    )
    assert body["withdrawal"]["storedDeleted"] == 3
    assert body["withdrawal"]["storedDeleteRan"] is True
    assert "deleted" in body["withdrawal"]["explanation"]
    assert body["gate"]["required"] is False, "a withdrawal is not answered by asking again"


async def test_an_answer_nobody_can_record_is_a_422_naming_the_alternatives(
    store: SimpleNamespace, caller: dict[str, Any]
) -> None:
    caller["user"] = store.users.row
    application = _consent_app()

    unknown = await _request(
        application,
        "POST",
        "/usage/consent",
        json={
            "decision": "MAYBE",
            "basis": "REQUIRED_AT_SIGN_IN",
            "noticeVersion": usage.NOTICE_VERSION,
        },
    )
    unrecordable = await _request(
        application,
        "POST",
        "/usage/consent",
        json={
            "decision": "NOT_RECORDED",
            "basis": "REQUIRED_AT_SIGN_IN",
            "noticeVersion": usage.NOTICE_VERSION,
        },
    )

    assert unknown.status_code == 422
    assert "GRANTED" in unknown.json()["detail"] and "REFUSED" in unknown.json()["detail"]
    assert unrecordable.status_code == 422
    assert "not an answer somebody can record" in unrecordable.json()["detail"]
    assert store.users.updates == []



@pytest.fixture
def sign_in(monkeypatch: pytest.MonkeyPatch, store: SimpleNamespace) -> Iterator[dict[str, Any]]:
    async def _admits(_email: str, *, is_master: bool) -> Any:
        return SimpleNamespace(status="ACTIVE")

    async def _noop(*_args: Any, **_kwargs: Any) -> Any:
        return None

    monkeypatch.setattr(auth_routes, "assert_access_admits", _admits)
    monkeypatch.setattr(auth_routes, "assert_roster_admits", _noop)
    monkeypatch.setattr(auth_routes, "mark_roster_seen", _noop)
    monkeypatch.setattr(auth_routes, "ensure_empanelled", _noop)
    monkeypatch.setattr(auth_routes.access_roster, "mark_access_seen", _noop)
    monkeypatch.setattr(auth_routes.access_roster, "admits", lambda _row: True)
    monkeypatch.setattr(auth_routes, "verify_password", lambda _raw, _hash: True)
    monkeypatch.setattr(auth_routes, "verify_password_or_decoy", lambda _raw, _hash: True)
    monkeypatch.setattr(
        auth_routes, "create_access_token", lambda **_kwargs: "a-real-looking-token"
    )
    yield {}


async def test_the_sign_in_gate_reports_on_both_credential_paths_at_the_point_they_join(
    store: SimpleNamespace, sign_in: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    account = store.users.row

    async def _google(_token: str) -> tuple[Any, Any]:
        return account, SimpleNamespace(status="ACTIVE")

    monkeypatch.setattr(auth_routes, "login_with_google", _google)

    by_password = await auth_routes.login(
        LoginRequest(email=account.email, password="a-password"), _SIGN_IN_REQUEST
    )
    by_google = await auth_routes.login(
        LoginRequest(googleIdToken="a-google-token"), _SIGN_IN_REQUEST
    )

    for answer in (by_password, by_google):
        gate = answer["user"][auth_routes.USAGE_CONSENT_GATE_KEY]
        assert gate["required"] is True
        assert gate["state"] == "NOT_RECORDED"
        assert gate["noticeVersion"] == usage.NOTICE_VERSION
        assert gate["answerAt"] == "POST /api/usage/consent"
    assert (
        by_password["user"][auth_routes.USAGE_CONSENT_GATE_KEY]
        == by_google["user"][auth_routes.USAGE_CONSENT_GATE_KEY]
    ), "the two credential paths must not describe the same account's consent differently"

    assert by_password["user"]["usageConsent"] == "NOT_RECORDED"
    assert "passwordHash" not in by_password["user"], "still stripped, as it always was"


async def test_the_sign_in_gate_admits_rather_than_refuses_so_the_answer_can_be_given(
    store: SimpleNamespace, sign_in: dict[str, Any]
) -> None:
    account = store.users.row
    answer = await auth_routes.login(
        LoginRequest(email=account.email, password="a-password"), _SIGN_IN_REQUEST
    )

    assert answer["accessToken"] == "a-real-looking-token", (
        "an un-consented account MUST come away with a session — it is the only way it can reach "
        "POST /usage/consent to give the answer being demanded of it"
    )
    assert answer["user"][auth_routes.USAGE_CONSENT_GATE_KEY]["required"] is True
    reason = answer["user"][auth_routes.USAGE_CONSENT_GATE_KEY]["reason"]
    assert "required to use the platform" in reason
    assert "nothing recorded carries your name" in reason


async def test_the_sign_in_gate_cannot_lock_out_the_break_glass_master_admin(
    store: SimpleNamespace, sign_in: dict[str, Any]
) -> None:
    master = _account(
        id="master-1",
        email="master@example.org",
        role="MASTER_ADMIN",
        usageConsent="NOT_RECORDED",
    )
    store.users.row = master

    answer = await auth_routes.login(
        LoginRequest(email=master.email, password="a-password"), _SIGN_IN_REQUEST
    )

    assert answer["accessToken"] == "a-real-looking-token"
    assert answer["user"]["role"] == "MASTER_ADMIN"
    assert answer["user"][auth_routes.USAGE_CONSENT_GATE_KEY]["required"] is True
    assert deps.is_break_glass_master(master) is True


async def test_a_consented_account_signs_in_with_the_gate_closed(
    store: SimpleNamespace, sign_in: dict[str, Any]
) -> None:
    store.users.row = _account(
        usageConsent="GRANTED",
        usageConsentAt=NOW,
        usageConsentBasis="REQUIRED_AT_SIGN_IN",
        usageConsentVersion=usage.NOTICE_VERSION,
    )

    answer = await auth_routes.login(
        LoginRequest(email=store.users.row.email, password="a-password"), _SIGN_IN_REQUEST
    )

    gate = answer["user"][auth_routes.USAGE_CONSENT_GATE_KEY]
    assert gate["required"] is False
    assert gate["agreedVersion"] == usage.NOTICE_VERSION
    assert gate["basis"] == "REQUIRED_AT_SIGN_IN"
