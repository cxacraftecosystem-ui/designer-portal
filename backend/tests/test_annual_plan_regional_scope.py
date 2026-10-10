"""A REGIONAL DIRECTOR CORRECTS THEIR OWN STATE'S ANNUAL-PLAN ROWS, AND ONLY THEIR STATE'S (F11).

The annual plan is the Ministry Admin's and above. A Regional Director reaches four of its arms —
the year list, the list, one row and the remarks correction — NARROWED to the states a Ministry Admin
assigned them in ``RegionalDirectorState``; every other arm (upload, export, pro-forma, promote,
withdraw, reinstate, and the assignment itself) stays the manager's. Asked per role, allowed and
refused, including a Regional Director of a DIFFERENT state, a Ministry Admin and an admin.

The database pattern is ``tests/test_sanction_orders.py``'s: every database call in a private
``asyncio.run`` loop with no ``TestClient`` alive, requests in between.
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import annual_plan

pytestmark = [needs_db]

#: Far from any year a real upload or another module writes, so the rows here are this module's.
PLAN_YEAR = 2091

ROLES = {
    "rd_gujarat": "REGIONAL_DIRECTOR",
    "rd_kerala": "REGIONAL_DIRECTOR",
    "rd_none": "REGIONAL_DIRECTOR",
    "ad": "ASSISTANT_DIRECTOR",
    "ma": "MINISTRY_ADMIN",
    "admin": "ADMIN",
    "designer": "DESIGNER",
}


def _auth(user_id: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=user_id)}"}


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    facts: dict[str, Any] = {"users": {}, "rows": {}}

    async def seed() -> None:
        await db.connect()
        try:
            await db.annualplanentry.delete_many(where={"planYear": PLAN_YEAR})
            for key, role in ROLES.items():
                email = f"plan-scope-{key}-{stamp}@example.org"
                user = await db.user.create(
                    data={
                        "email": email,
                        "name": f"Plan scope {key} {stamp}",
                        "role": role,
                        "passwordHash": hash_password("plan-scope-test-password"),
                    }
                )
                await db.accessroster.create(
                    data={
                        "email": email,
                        "status": "ACTIVE",
                        "admitRole": role,
                        "joinedAt": datetime.now(UTC),
                        "notes": "Seeded by tests/test_annual_plan_regional_scope.py.",
                    }
                )
                facts["users"][key] = user.id
            for key, state in (("gujarat", "Gujarat"), ("kerala", "Kerala")):
                row = await db.annualplanentry.create(
                    data={
                        "planYear": PLAN_YEAR,
                        "workshopNo": f"DPW/{key}/{stamp}",
                        "workshopNoKey": f"DPW/{key}/{stamp}".upper(),
                        "state": state,
                        "district": None,
                    }
                )
                facts["rows"][key] = row.id
            # Assigned through the service, exactly as the Ministry Admin's route assigns them.
            ma = await db.user.find_unique(where={"id": facts["users"]["ma"]})
            for key, states in (("rd_gujarat", ["gujarat"]), ("rd_kerala", ["Kerala"])):
                target = await db.user.find_unique(where={"id": facts["users"][key]})
                facts[f"{key}_states"] = await annual_plan.set_regional_states(
                    target, states, actor=ma
                )
        finally:
            await db.disconnect()

    asyncio.run(seed())

    answers: dict[str, Any] = {}
    users = facts["users"]
    rows = facts["rows"]
    with TestClient(app) as client:

        def call(method: str, path: str, actor: str, **kwargs: Any) -> Any:
            return client.request(method, f"/api/annual-plan{path}", headers=_auth(users[actor]), **kwargs)

        for actor in ROLES:
            answers[f"list-{actor}"] = call("GET", f"?planYear={PLAN_YEAR}", actor)
            answers[f"years-{actor}"] = call("GET", "/years", actor)
            for row in ("gujarat", "kerala"):
                answers[f"get-{row}-{actor}"] = call("GET", f"/{rows[row]}", actor)
                answers[f"patch-{row}-{actor}"] = call(
                    "PATCH", f"/{rows[row]}", actor, json={"notes": f"{actor} was here"}
                )
        # The national acts stay the manager's, for a Regional Director inside their own state too.
        answers["withdraw-rd"] = call("POST", f"/{rows['gujarat']}/withdraw", "rd_gujarat")
        answers["reinstate-rd"] = call("POST", f"/{rows['gujarat']}/reinstate", "rd_gujarat")
        answers["promote-rd"] = call("POST", f"/{rows['gujarat']}/promote", "rd_gujarat", json={})
        answers["export-rd"] = call("GET", f"/export.xlsx?planYear={PLAN_YEAR}", "rd_gujarat")
        answers["proforma-rd"] = call("GET", "/pro-forma", "rd_gujarat")
        # The assignment: the manager's, per role.
        for actor in ("rd_gujarat", "ad", "designer", "ma", "admin"):
            answers[f"directors-{actor}"] = call("GET", "/regional-directors", actor)
        answers["assign-rd"] = call(
            "PUT", f"/regional-directors/{users['rd_kerala']}", "rd_gujarat", json={"states": ["Gujarat"]}
        )
        answers["assign-ma"] = call(
            "PUT",
            f"/regional-directors/{users['rd_none']}",
            "ma",
            json={"states": ["tamil nadu", "Tamil Nadu"]},
        )
        answers["assign-admin"] = call(
            "PUT", f"/regional-directors/{users['rd_none']}", "admin", json={"states": []}
        )
        answers["assign-not-rd"] = call(
            "PUT", f"/regional-directors/{users['ad']}", "ma", json={"states": ["Gujarat"]}
        )
        answers["assign-unknown"] = call(
            "PUT", f"/regional-directors/{users['rd_none']}", "ma", json={"states": ["Atlantis"]}
        )
    facts["answers"] = answers

    async def observe() -> None:
        await db.connect()
        try:
            for row in ("gujarat", "kerala"):
                entry = await db.annualplanentry.find_unique(where={"id": rows[row]})
                facts[f"notes-{row}"] = entry.notes if entry else None
            facts["rd_none_states"] = [
                r.state
                for r in await db.regionaldirectorstate.find_many(
                    where={"userId": users["rd_none"]}
                )
            ]
            await db.annualplanentry.delete_many(where={"planYear": PLAN_YEAR})
        finally:
            await db.disconnect()

    asyncio.run(observe())
    yield facts


def _ids(reply: Any) -> set[str]:
    return {item["id"] for item in reply.json()["items"]}


def test_states_are_stored_under_the_spelling_the_plan_rows_use(world) -> None:
    assert world["rd_gujarat_states"] == ["Gujarat"]
    assert world["rd_kerala_states"] == ["Kerala"]


def test_a_regional_director_lists_only_their_own_states_rows(world) -> None:
    rows = world["rows"]
    reply = world["answers"]["list-rd_gujarat"]
    assert reply.status_code == 200, reply.text
    assert _ids(reply) == {rows["gujarat"]}
    assert reply.json()["regionalStates"] == ["Gujarat"]
    assert _ids(world["answers"]["list-rd_kerala"]) == {rows["kerala"]}


def test_a_regional_director_with_no_state_sees_nothing(world) -> None:
    reply = world["answers"]["list-rd_none"]
    assert reply.status_code == 200
    assert reply.json()["items"] == []
    assert reply.json()["regionalStates"] == []
    assert all(year["planYear"] != PLAN_YEAR for year in world["answers"]["years-rd_none"].json())


@pytest.mark.parametrize("actor", ["ma", "admin"])
def test_a_manager_sees_every_state_and_no_scope(world, actor: str) -> None:
    reply = world["answers"][f"list-{actor}"]
    assert reply.status_code == 200
    assert {world["rows"]["gujarat"], world["rows"]["kerala"]} <= _ids(reply)
    assert reply.json()["regionalStates"] is None


@pytest.mark.parametrize("actor", ["ad", "designer"])
def test_tiers_outside_the_plan_are_refused_every_arm(world, actor: str) -> None:
    answers = world["answers"]
    for key in ("list", "years", "get-gujarat", "patch-gujarat", "patch-kerala"):
        assert answers[f"{key}-{actor}"].status_code == 403, key
    assert answers[f"list-{actor}"].json()["detail"] == annual_plan.ANNUAL_PLAN_REFUSAL


def test_a_regional_director_corrects_their_own_states_remarks(world) -> None:
    answers = world["answers"]
    assert answers["get-gujarat-rd_gujarat"].status_code == 200
    assert answers["patch-gujarat-rd_gujarat"].status_code == 200
    assert answers["patch-kerala-rd_kerala"].status_code == 200


def test_a_regional_director_of_a_different_state_is_refused_as_if_the_row_did_not_exist(
    world,
) -> None:
    answers = world["answers"]
    for key in (
        "get-kerala-rd_gujarat",
        "patch-kerala-rd_gujarat",
        "get-gujarat-rd_kerala",
        "patch-gujarat-rd_kerala",
        "patch-gujarat-rd_none",
    ):
        assert answers[key].status_code == 404, key
        assert answers[key].json()["detail"] == "Record not found"


@pytest.mark.parametrize("actor", ["ma", "admin"])
def test_a_manager_corrects_any_states_remarks(world, actor: str) -> None:
    for row in ("gujarat", "kerala"):
        assert world["answers"][f"patch-{row}-{actor}"].status_code == 200


def test_the_last_permitted_write_is_what_the_rows_hold(world) -> None:
    """Every refused write left nothing: the last writer each row accepted is a manager (the act
    order is the ROLES order, and the admin writes after everybody else who may)."""
    assert world["notes-gujarat"] == "admin was here"
    assert world["notes-kerala"] == "admin was here"


@pytest.mark.parametrize("key", ["withdraw-rd", "reinstate-rd", "promote-rd", "export-rd", "proforma-rd"])
def test_the_national_acts_stay_the_managers(world, key: str) -> None:
    assert world["answers"][key].status_code == 403, world["answers"][key].text


@pytest.mark.parametrize("actor", ["rd_gujarat", "ad", "designer"])
def test_only_a_manager_reads_or_assigns_the_scope(world, actor: str) -> None:
    assert world["answers"][f"directors-{actor}"].status_code == 403
    assert world["answers"]["assign-rd"].status_code == 403


@pytest.mark.parametrize("actor", ["ma", "admin"])
def test_a_manager_lists_every_regional_director_with_their_states(world, actor: str) -> None:
    reply = world["answers"][f"directors-{actor}"]
    assert reply.status_code == 200
    by_id = {item["id"]: item["states"] for item in reply.json()["items"]}
    assert by_id[world["users"]["rd_gujarat"]] == ["Gujarat"]
    assert world["users"]["ad"] not in by_id


def test_a_manager_assigns_and_clears_states(world) -> None:
    answers = world["answers"]
    assert answers["assign-ma"].status_code == 200
    assert answers["assign-ma"].json()["states"] == ["Tamil Nadu"]
    assert answers["assign-admin"].status_code == 200
    assert answers["assign-admin"].json()["states"] == []
    assert world["rd_none_states"] == []


def test_states_go_only_to_a_regional_director_and_only_real_ones(world) -> None:
    answers = world["answers"]
    assert answers["assign-not-rd"].status_code == 422
    assert answers["assign-not-rd"].json()["detail"] == annual_plan.REGIONAL_TARGET_REFUSAL
    assert answers["assign-unknown"].status_code == 422
    assert "Atlantis" in answers["assign-unknown"].json()["detail"]


def test_the_predicates() -> None:
    def user(role: str) -> Any:
        return type("U", (), {"role": role, "id": "x"})()

    assert annual_plan.can_read_annual_plan(user("REGIONAL_DIRECTOR"))
    assert not annual_plan.can_manage_annual_plan(user("REGIONAL_DIRECTOR"))
    assert not annual_plan.can_read_annual_plan(user("ASSISTANT_DIRECTOR"))
    assert annual_plan.can_read_annual_plan(user("MINISTRY_ADMIN"))
    assert annual_plan.in_scope(type("R", (), {"state": "Kerala"})(), None)
    assert not annual_plan.in_scope(type("R", (), {"state": "Kerala"})(), ["Gujarat"])
    assert not annual_plan.in_scope(type("R", (), {"state": None})(), [])
