import asyncio
import sys
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.core.db as core_db
from app.api.router import api_router
from app.core import deps
from app.services.design_workshop_grants import encode_join_code, mint_secret

WORKSHOP_ID = "cmgrantgate000000000000aa"


class _DatabaseTouched(Exception):
    pass


class _Tripwire:
    def __init__(self) -> None:
        object.__setattr__(self, "touched", False)

    def __getattr__(self, name: str) -> Any:
        object.__setattr__(self, "touched", True)
        raise _DatabaseTouched(name)


class _Outcome:
    def __init__(self, *, reached: bool, status_code: int | None = None, detail: Any = "") -> None:
        self.reached = reached
        self.status_code = status_code
        self.detail = str(detail)

    def __repr__(self) -> str:  # pragma: no cover - only read out of a failure message
        return "reached-the-database" if self.reached else f"HTTP {self.status_code}: {self.detail}"


_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


_APP = _build_app()


def _user(role: str) -> SimpleNamespace:
    return SimpleNamespace(
        id=f"u-{role.lower()}", email=f"{role.lower()}@example.test", name="Gate Test", role=role
    )


@pytest.fixture
def api(monkeypatch: pytest.MonkeyPatch):
    tripwire = _Tripwire()
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", tripwire)
    for module in list(sys.modules.values()):
        name = getattr(module, "__name__", "")
        if name.startswith("app.") and getattr(module, "db", None) is real_db:
            monkeypatch.setattr(module, "db", tripwire)

    def call(method: str, path: str, *, as_role: str, body: dict[str, Any] | None = None) -> _Outcome:
        _CURRENT["user"] = _user(as_role)

        async def run() -> _Outcome:
            transport = httpx.ASGITransport(app=_APP)
            async with httpx.AsyncClient(transport=transport, base_url="http://gate.test") as client:
                response = await client.request(method, f"/api{path}", json=body)
            payload = response.json() if response.content else {}
            detail = payload.get("detail", payload) if isinstance(payload, dict) else payload
            return _Outcome(reached=False, status_code=response.status_code, detail=detail)

        try:
            return asyncio.run(run())
        except _DatabaseTouched:
            return _Outcome(reached=True)

    yield SimpleNamespace(call=call, tripwire=tripwire)
    _CURRENT["user"] = None


def _card(workshop_id: str = WORKSHOP_ID) -> str:
    return encode_join_code(workshop_id, mint_secret())



@pytest.mark.parametrize(
    "code",
    [
        "DPW2:J:CMGRANTGATE000000000000AA.AAAAAAAAAAAAAAAAAAAAAA:AAAA",
        "DPW2:J:CMGRANTGATE000000000000AA.AAAAAAAAAAAAAAAAAAAAA:AAAA",
        "DPW2:J:CMGRANTGATE000000000000AAAAAAAAAAAAAAAAAAAAAAAAA:AAAA",
        "DPW1:G:CMGRANTGATE000000000000AA:AAAA",
        "DPW9:J:CMGRANTGATE000000000000AA.AAAAAAAAAAAAAAAAAAAAAA:AAAA",
        "DPW2:J:LOCAL-3F2504E0-4F89-11D3-9A0C-0305E82C3301.AAAAAAAAAAAAAAAAAAAAAA:AAAA",
        "DPW2:J:DWLOCAL-3F2504E0-4F89-11D3-9A0C-0305E82C3301.AAAAAAAAAAAAAAAAAAAAAA:AAAA",
        "https://example.org/scan-me",
    ],
)
def test_a_damaged_card_is_refused_before_the_database_is_asked_anything(api, code):
    outcome = api.call(
        "POST", "/design-workshop-access/redemptions", as_role="DESIGNER", body={"code": code}
    )
    assert outcome.status_code == 422, outcome
    assert api.tripwire.touched is False, "the grammar refusal must not depend on a database read"


def test_a_refusal_never_echoes_the_card(api):
    card = _card()
    outcome = api.call(
        "POST",
        "/design-workshop-access/redemptions",
        as_role="DESIGNER",
        body={"code": card[:-1] + ("0" if card[-1] != "0" else "1")},
    )
    assert outcome.status_code == 422, outcome
    assert card[:-1] not in outcome.detail
    assert card.split(".")[1][:8] not in outcome.detail


def test_a_perfectly_formed_card_reaches_the_database_which_is_the_whole_point(api):
    outcome = api.call(
        "POST", "/design-workshop-access/redemptions", as_role="DESIGNER", body={"code": _card()}
    )
    assert outcome.reached, outcome



@pytest.mark.parametrize("role", ["DESIGNER", "RESEARCHER", "PROFESSOR", "CROWDSOURCE_VOLUNTEER"])
def test_anybody_signed_in_may_present_a_card(api, role):
    outcome = api.call(
        "POST", "/design-workshop-access/redemptions", as_role=role, body={"code": _card()}
    )
    assert outcome.reached, outcome


@pytest.mark.parametrize("max_uses", [2, 50, None])
@pytest.mark.parametrize("role", ["DESIGNER", "RESEARCHER", "PROFESSOR"])
def test_only_an_admin_may_ask_for_a_card_that_lets_more_than_one_person_in(api, role, max_uses):
    outcome = api.call(
        "POST",
        "/design-workshop-access/grants",
        as_role=role,
        body={"recordType": "DESIGN_WORKSHOP", "recordId": WORKSHOP_ID, "maxUses": max_uses},
    )
    assert outcome.status_code == 403, outcome
    assert api.tripwire.touched is False, "no card may be minted, and no row read, by a refused call"


def test_a_designer_may_ask_for_a_single_use_card(api):
    outcome = api.call(
        "POST",
        "/design-workshop-access/grants",
        as_role="DESIGNER",
        body={"recordType": "DESIGN_WORKSHOP", "recordId": WORKSHOP_ID, "maxUses": 1},
    )
    assert outcome.reached, outcome


def test_a_card_for_a_record_with_no_membership_is_refused_from_the_body_alone(api):
    for record_type in ("ARTISAN", "TOOL", "PROTOTYPE", "MEDIA"):
        outcome = api.call(
            "POST",
            "/design-workshop-access/grants",
            as_role="ADMIN",
            body={"recordType": record_type, "recordId": WORKSHOP_ID, "maxUses": 1},
        )
        assert outcome.status_code == 422, (record_type, outcome)
        assert api.tripwire.touched is False, record_type


def test_a_record_tag_posted_to_the_join_door_is_refused_from_the_body_alone(api):
    outcome = api.call(
        "POST",
        "/design-workshop-access/redemptions",
        as_role="DESIGNER",
        body={"code": "DPW1:G:CMGRANTGATE000000000000AA:AAAA"},
    )
    assert outcome.status_code == 422, outcome
    assert api.tripwire.touched is False
    assert "join card" in outcome.detail.lower(), outcome


def test_a_join_card_posted_to_the_ask_door_is_refused_from_the_body_alone(api):
    outcome = api.call(
        "POST",
        "/design-workshop-access/requests",
        as_role="DESIGNER",
        body={"workshopId": WORKSHOP_ID, "scannedCode": _card()},
    )
    assert outcome.status_code == 422, outcome
    assert api.tripwire.touched is False, "the ask route must still refuse from the body alone"
    assert "join card" in outcome.detail.lower(), outcome
