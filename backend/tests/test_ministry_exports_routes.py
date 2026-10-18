"""/api/ministry-exports, OVER A DATABASE.

404 while the switch is off; the whole-estate tiers in and everyone else refused with the sentence the
web guard shows; an ineligible recipient refused; a future month refused; a replayed Idempotency-Key
answered with the same body; and a download link refused before READY, gone after the lifecycle rule,
logged when minted, and as short-lived as the setting says.
"""

from __future__ import annotations

import uuid
from typing import Any

import pytest
from conftest import needs_db
from ministry_exports_support import FakeBucket, bearer, make_user, own_client, private, reset

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.config import get_settings
from app.core.db import db
from app.services import ministry_exports as packs

pytestmark = needs_db

ALLOWED = ("MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN")
REFUSED = ("ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "PROFESSOR", "DESIGNER")


@pytest.fixture
def world(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    async def work() -> dict[str, Any]:
        await reset()
        people = {role: await make_user(role) for role in (*ALLOWED, *REFUSED)}
        people["barred"] = await make_user("MINISTRY_ADMIN", "barred")
        await db.accessroster.create(
            data={"email": people["barred"].email.lower(), "status": "SUSPENDED"}
        )
        return people

    people = private(work)
    monkeypatch.setattr(get_settings(), "ministry_exports_enabled", True)
    bucket = FakeBucket().install(monkeypatch)
    from fastapi.testclient import TestClient

    from app.main import app

    with TestClient(app) as client:
        yield {"people": people, "client": client, "bucket": bucket}


def test_every_route_is_a_404_while_the_switch_is_off(
    world: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(get_settings(), "ministry_exports_enabled", False)
    client, admin = world["client"], world["people"]["ADMIN"]
    for method, path in (
        ("get", "/api/ministry-exports/schemes"),
        ("get", "/api/ministry-exports/recipients"),
        ("get", "/api/ministry-exports/packs"),
        ("get", "/api/ministry-exports/eligible-accounts"),
    ):
        assert getattr(client, method)(path, headers=bearer(admin)).status_code == 404
    assert (
        client.post(
            "/api/ministry-exports/packs", json={"period": "2026-09"}, headers=bearer(admin)
        ).status_code
        == 404
    )
    assert client.get("/api/features", headers=bearer(admin)).json()["ministryExports"] is False


@pytest.mark.parametrize("role", ALLOWED)
def test_the_whole_estate_tiers_are_let_in(world: dict[str, Any], role: str) -> None:
    client = world["client"]
    answer = client.get("/api/ministry-exports/schemes", headers=bearer(world["people"][role]))
    assert answer.status_code == 200
    keys = [s["key"] for s in answer.json()["schemes"]]
    assert len(keys) == 7 and keys[-1] == "UNSTATED"
    assert (
        client.get("/api/features", headers=bearer(world["people"][role])).json()["ministryExports"]
        is True
    )


@pytest.mark.parametrize("role", REFUSED)
def test_everyone_else_is_refused_with_the_shared_sentence(
    world: dict[str, Any], role: str
) -> None:
    answer = world["client"].get(
        "/api/ministry-exports/packs", headers=bearer(world["people"][role])
    )
    assert answer.status_code == 403
    assert answer.json()["detail"] == "For Ministry Admins, admins and the master admin."


def test_recipients_can_be_added_changed_and_removed(world: dict[str, Any]) -> None:
    client, people = world["client"], world["people"]
    target = people["MINISTRY_ADMIN"]
    put = client.put(
        f"/api/ministry-exports/recipients/{target.id}",
        json={"schemes": ["OTHER", "SKILL_UPGRADATION"], "active": True},
        headers=bearer(people["ADMIN"]),
    )
    assert put.status_code == 200, put.text
    body = put.json()
    assert body["schemes"] == ["SKILL_UPGRADATION", "OTHER"]  # registry order
    assert body["eligible"] is True and body["role"] == "MINISTRY_ADMIN"
    listed = client.get("/api/ministry-exports/recipients", headers=bearer(people["ADMIN"])).json()[
        "recipients"
    ]
    assert [r["userId"] for r in listed] == [target.id]
    gone = client.delete(
        f"/api/ministry-exports/recipients/{target.id}", headers=bearer(people["ADMIN"])
    )
    assert gone.status_code == 204
    assert (
        client.get("/api/ministry-exports/recipients", headers=bearer(people["ADMIN"])).json()[
            "recipients"
        ]
        == []
    )


def test_an_ineligible_account_or_unknown_scheme_is_refused(world: dict[str, Any]) -> None:
    client, people = world["client"], world["people"]
    headers = bearer(people["MASTER_ADMIN"])
    for slug in ("ASSISTANT_DIRECTOR", "DESIGNER", "barred"):
        answer = client.put(
            f"/api/ministry-exports/recipients/{people[slug].id}",
            json={"schemes": []},
            headers=headers,
        )
        assert answer.status_code == 422, slug
    unknown = client.put(
        f"/api/ministry-exports/recipients/{people['ADMIN'].id}",
        json={"schemes": ["NOPE"]},
        headers=headers,
    )
    assert unknown.status_code == 422


def test_the_account_search_offers_only_eligible_accounts(world: dict[str, Any]) -> None:
    client, people = world["client"], world["people"]
    name = people["ADMIN"].name
    found = client.get(
        "/api/ministry-exports/eligible-accounts",
        params={"search": name},
        headers=bearer(people["ADMIN"]),
    ).json()["accounts"]
    assert [a["userId"] for a in found] == [people["ADMIN"].id]
    designer = client.get(
        "/api/ministry-exports/eligible-accounts",
        params={"search": people["DESIGNER"].name},
        headers=bearer(people["ADMIN"]),
    ).json()["accounts"]
    assert designer == []


def test_generating_refuses_a_future_or_malformed_month_and_replays_by_key(
    world: dict[str, Any],
) -> None:
    client, people = world["client"], world["people"]
    headers = bearer(people["MINISTRY_ADMIN"])
    assert (
        client.post(
            "/api/ministry-exports/packs", json={"period": "2999-01"}, headers=headers
        ).status_code
        == 422
    )
    assert (
        client.post(
            "/api/ministry-exports/packs", json={"period": "Sept"}, headers=headers
        ).status_code
        == 422
    )
    keyed = {**headers, "Idempotency-Key": f"mx-{uuid.uuid4().hex[:16]}"}
    body = {"period": "2026-09", "schemes": ["OTHER"]}
    first = client.post("/api/ministry-exports/packs", json=body, headers=keyed)
    assert first.status_code == 202, first.text
    second = client.post("/api/ministry-exports/packs", json=body, headers=keyed)
    assert second.status_code == 202 and second.json() == first.json()
    assert second.headers.get("Idempotent-Replayed") == "true"
    [item] = first.json()["items"]
    assert item["status"] == "QUEUED" and item["revision"] == 1 and item["trigger"] == "ON_DEMAND"

    # Without the key, a QUEUED pack comes back unchanged rather than bumped.
    again = client.post("/api/ministry-exports/packs", json=body, headers=headers).json()["items"][
        0
    ]
    assert again["id"] == item["id"] and again["revision"] == 1

    listed = client.get(
        "/api/ministry-exports/packs", params={"period": "2026-09"}, headers=headers
    ).json()
    assert [p["id"] for p in listed["items"]] == [item["id"]] and listed["periods"] == ["2026-09"]


def _ready_pack(**extra: Any) -> Any:
    async def work(client: Any) -> Any:
        return await client.ministryexportpack.create(
            data={
                "period": "2026-08",
                "scheme": "OTHER",
                "trigger": packs.SCHEDULED,
                "status": packs.READY,
                "xlsxKey": "exports/ministry/2026-08/OTHER/r1/ministry-pack-other-2026-08.xlsx",
                "pdfKey": "exports/ministry/2026-08/OTHER/r1/ministry-pack-other-2026-08.pdf",
                "xlsxBytes": 10,
                "pdfBytes": 10,
                **extra,
            }
        )

    return own_client(work)


def test_a_link_is_refused_before_ready(world: dict[str, Any]) -> None:
    pack = _ready_pack(status=packs.BUILDING)
    answer = world["client"].post(
        f"/api/ministry-exports/packs/{pack.id}/links",
        json={"file": "xlsx"},
        headers=bearer(world["people"]["ADMIN"]),
    )
    assert answer.status_code == 409


def test_a_link_to_a_removed_file_is_gone(world: dict[str, Any]) -> None:
    pack = _ready_pack()
    answer = world["client"].post(
        f"/api/ministry-exports/packs/{pack.id}/links",
        json={"file": "pdf"},
        headers=bearer(world["people"]["ADMIN"]),
    )
    assert answer.status_code == 410
    assert (
        answer.json()["detail"]
        == "This pack's files have been removed after 13 months. Generate it again."
    )


def test_a_storage_hiccup_is_not_reported_as_removed(world: dict[str, Any]) -> None:
    pack = _ready_pack()
    world["bucket"].objects[pack.pdfKey] = b"pdf"
    world["bucket"].unreachable = True
    answer = world["client"].post(
        f"/api/ministry-exports/packs/{pack.id}/links",
        json={"file": "pdf"},
        headers=bearer(world["people"]["ADMIN"]),
    )
    assert answer.status_code == 503
    assert answer.json()["detail"] == "Storage did not answer. Try again in a minute."


def test_a_link_is_short_lived_and_logged(
    world: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(get_settings(), "ministry_exports_link_seconds", 300)
    pack = _ready_pack()
    bucket, viewer = world["bucket"], world["people"]["MINISTRY_ADMIN"]
    bucket.objects[pack.xlsxKey] = b"xlsx"
    answer = world["client"].post(
        f"/api/ministry-exports/packs/{pack.id}/links",
        json={"file": "xlsx"},
        headers=bearer(viewer),
    )
    assert answer.status_code == 200, answer.text
    body = answer.json()
    assert body["fileName"] == "ministry-pack-other-2026-08.xlsx" and body["url"].startswith(
        "https://"
    )
    assert bucket.presigned[-1]["expires_in"] == 300

    async def downloads(client: Any) -> list[Any]:
        return list(await client.ministryexportdownload.find_many(where={"packId": pack.id}))

    [row] = own_client(downloads)
    assert row.userId == viewer.id and row.file == "xlsx" and row.revision == 1

    payload = (
        world["client"].get(f"/api/ministry-exports/packs/{pack.id}", headers=bearer(viewer)).json()
    )
    assert payload["files"]["xlsx"]["name"] == "ministry-pack-other-2026-08.xlsx"
    assert payload["periodLabel"] == "August 2026" and payload["schemeLabel"] == "Other"
