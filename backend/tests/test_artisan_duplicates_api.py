"""The duplicate-artisan finder through the API: the form's check, the hold at save time, the admin
queue, merge, undo and the merged-id redirect.

Database-backed. Rows are seeded before the app starts; read-backs while the client is alive use a
private Prisma client (the pattern of tests/test_aadhaar_fingerprint.py).
"""

from __future__ import annotations

import asyncio
import json
import random
import uuid
from types import SimpleNamespace
from typing import Any

import pytest
from conftest import TEST_AADHAAR_FINGERPRINT_KEY, needs_db

from app.services import aadhaar_fingerprint as fp
from app.services.artisan_identity import verhoeff_ok

pytestmark = needs_db

LOCATION = {"latitude": 26.82, "longitude": 75.54, "state": "Rajasthan", "district": "Jaipur"}


def _aadhaar() -> str:
    while True:
        stem = str(random.randint(2, 9)) + "".join(random.choices("0123456789", k=10))
        for last in "0123456789":
            if verhoeff_ok(stem + last):
                return stem + last


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.core.db import db
    from app.core.security import create_access_token, hash_password
    from app.main import app

    tag = uuid.uuid4().hex[:8]
    seeded_number = _aadhaar()

    async def seed() -> dict[str, Any]:
        await db.connect()
        try:
            people = {}
            for slug, role in (("designer", "DESIGNER"), ("admin", "ADMIN")):
                people[slug] = await db.user.create(data={
                    "email": f"dupes-api-{slug}-{tag}@example.org",
                    "name": f"Dupes {slug} {tag}",
                    "role": role,
                    "passwordHash": hash_password("unused"),
                })
            craft = await db.craft.create(data={"name": f"Dupes dabu {tag}"})
            loc = await db.location.create(data={"latitude": 26.8, "longitude": 75.5, "state": "Rajasthan", "district": "Jaipur"})
            # Recorded before the Aadhaar was required: no fingerprint, so a create with a number of
            # its own is not capped against it.
            existing = await db.artisan.create(data={
                "name": f"Ramesh Prajapat {tag}", "place": "Bagru", "phone": "9829011111",
                "createdById": people["designer"].id, "craftId": craft.id, "locationId": loc.id,
                "dateOfBirth": "1971-04-02T00:00:00Z",
            })
            holder = await db.artisan.create(data={
                "name": f"Bhagwati Devi {tag}", "place": "Bagru", "createdById": people["designer"].id,
                "aadhaarFingerprint": fp.fingerprint(seeded_number, TEST_AADHAAR_FINGERPRINT_KEY),
                "aadhaarLast4": seeded_number[-4:], "pehchanCardNumber": f"RJP{tag}",
                "dateOfBirth": "1968-01-09T00:00:00Z",
            })
            gopal = []
            for _ in range(2):
                row = await db.artisan.create(data={
                    "name": f"Gopal Meena {tag}", "place": "Bagru", "phone": "9829033333",
                    "createdById": people["designer"].id, "locationId": loc.id, "notes": "Dabu printer",
                })
                gopal.append(row.id)
            from app.services import artisan_duplicates

            await artisan_duplicates.rescore_artisan(gopal[1])
            crowd = []
            for i in range(7):
                row = await db.artisan.create(data={
                    "name": f"Kishan Lal {tag}", "place": "Bagru", "phone": "9829022222",
                    "createdById": people["designer"].id, "locationId": loc.id,
                })
                crowd.append(row.id)
            return {"people": people, "craft": craft.id, "existing": existing.id, "crowd": crowd,
                    "holder": holder.id, "gopal": gopal, "tag": tag, "number": seeded_number}
        finally:
            await db.disconnect()

    seeded = asyncio.run(seed())
    seeded["headers"] = {
        slug: {"Authorization": f"Bearer {create_access_token(user.id)}"}
        for slug, user in seeded["people"].items()
    }
    with TestClient(app) as client:
        seeded["client"] = client
        yield seeded


@pytest.fixture(autouse=True)
def _fresh_check_budget():
    from app.api.routes.artisan_duplicates import reset_check_budget

    reset_check_budget()
    yield
    reset_check_budget()


def _body(world: dict[str, Any], name: str, **extra: Any) -> dict[str, Any]:
    return {
        "name": name,
        "place": "Bagru",
        "aadhaarNumber": _aadhaar(),
        "dos": "Greet the artisan first.",
        "donts": "Do not photograph without asking.",
        "craftId": world["craft"],
        "location": LOCATION,
        **extra,
    }


async def _read(fn):
    from prisma import Prisma

    client = Prisma()
    await client.connect()
    try:
        return await fn(client)
    finally:
        await client.disconnect()


def _pairs_for(world, artisan_id: str, status: str = "OPEN") -> list[dict[str, Any]]:
    res = world["client"].get(
        f"/api/artisan-duplicates?status={status}&artisanId={artisan_id}", headers=world["headers"]["admin"]
    )
    assert res.status_code == 200, res.text
    return res.json()["items"]


def test_check_caps_at_five_and_carries_no_identifier(world):
    client, h = world["client"], world["headers"]["designer"]
    res = client.post("/api/artisan-duplicates/check", headers=h, json={
        "name": f"Kishan Lal {world['tag']}", "phone": "98290 22222", "state": "Rajasthan", "district": "Jaipur",
    })
    assert res.status_code == 200, res.text
    candidates = res.json()["candidates"]
    assert len(candidates) == 5
    assert all(c["score"] >= 0.6 for c in candidates)
    assert "9829022222" not in res.text and "pehchan" not in res.text.lower()


def test_check_takes_the_aadhaar_in_the_body_and_names_it_only_then(world):
    client, h = world["client"], world["headers"]["designer"]
    typed = client.post("/api/artisan-duplicates/check", headers=h, json={
        "name": "Somebody Unrelated", "aadhaar": world["number"],
    }).json()["candidates"]
    assert [c["artisan"]["id"] for c in typed] == [world["holder"]]
    assert "AADHAAR" in [r["code"] for r in typed[0]["reasons"]]
    assert typed[0]["artisan"]["birthYear"] == 1968
    text = json.dumps(typed)
    assert world["number"] not in text and world["number"][-4:] not in text and f"RJP{world['tag']}" not in text
    untyped = client.post("/api/artisan-duplicates/check", headers=h, json={
        "name": f"Ramesh Prajapat {world['tag']}", "phone": "9829011111", "state": "Rajasthan", "district": "Jaipur",
    }).json()["candidates"]
    assert world["existing"] in [c["artisan"]["id"] for c in untyped]
    assert all("AADHAAR" not in [r["code"] for r in c["reasons"]] for c in untyped)


def test_check_is_rate_limited(world):
    client, h = world["client"], world["headers"]["designer"]
    codes = [client.post("/api/artisan-duplicates/check", headers=h, json={"name": "Zz"}).status_code for _ in range(31)]
    assert codes[:30] == [200] * 30 and codes[30] == 429


def test_old_clients_are_never_held_and_the_pair_is_stored(world):
    client, h = world["client"], world["headers"]["designer"]
    body = _body(world, f"Ramesh Prajapat {world['tag']}", phone="9829011111")
    res = client.post("/api/artisans", headers=h, json=body)
    assert res.status_code == 201, res.text
    pairs = _pairs_for(world, res.json()["id"])
    assert [p for p in pairs if world["existing"] in (p["a"]["id"], p["b"]["id"]) and p["source"] == "CREATE"]


def test_a_list_holds_and_an_acknowledgement_saves(world):
    client, h = world["client"], world["headers"]["designer"]
    name = f"Ramesh Prajapat {world['tag']}"
    held = client.post("/api/artisans", headers=h, json=_body(world, name, phone="9829011111", duplicateAcknowledged=[]))
    assert held.status_code == 409, held.text
    detail = held.json()["detail"]
    assert detail["code"] == "artisan_possible_duplicate"
    ids = [c["artisan"]["id"] for c in detail["candidates"]]
    assert world["existing"] in ids and detail["existingArtisan"]["id"] in ids
    saved = client.post("/api/artisans", headers=h, json=_body(world, name, phone="9829011111", duplicateAcknowledged=ids))
    assert saved.status_code == 201, saved.text
    pair = next(p for p in _pairs_for(world, saved.json()["id"]) if world["existing"] in (p["a"]["id"], p["b"]["id"]))
    assert pair["creatorSaidDifferent"] is True


def test_the_kill_switch_turns_the_hold_off(world, monkeypatch):
    from app.api.routes import artisans

    monkeypatch.setattr(artisans, "get_settings", lambda: SimpleNamespace(artisan_duplicate_hold=False))
    res = world["client"].post("/api/artisans", headers=world["headers"]["designer"], json=_body(
        world, f"Ramesh Prajapat {world['tag']}", phone="9829011111", duplicateAcknowledged=[]
    ))
    assert res.status_code == 201, res.text


def test_an_idempotent_replay_returns_the_stored_answer(world):
    client = world["client"]
    headers = {**world["headers"]["designer"], "Idempotency-Key": f"dupes-{uuid.uuid4().hex}"}
    body = _body(world, f"Ramesh Prajapat {world['tag']}", phone="9829011111",
                 duplicateAcknowledged=[world["existing"]])
    first = client.post("/api/artisans", headers=headers, json=body)
    assert first.status_code == 201, first.text
    again = client.post("/api/artisans", headers=headers, json=body)
    assert again.status_code == 201 and again.json()["id"] == first.json()["id"]
    assert again.headers.get("Idempotent-Replayed") == "true"


def test_an_edit_rescores_and_a_dismissal_survives_it(world):
    client, h, admin = world["client"], world["headers"]["designer"], world["headers"]["admin"]
    created = client.post("/api/artisans", headers=h, json=_body(world, f"Unique Person {uuid.uuid4().hex[:6]}"))
    assert created.status_code == 201
    artisan_id = created.json()["id"]
    assert not [p for p in _pairs_for(world, artisan_id) if world["existing"] in (p["a"]["id"], p["b"]["id"])]
    patched = client.patch(f"/api/artisans/{artisan_id}", headers=h, json={"name": f"Ramesh Prajapat {world['tag']}", "phone": "9829011111"})
    assert patched.status_code == 200, patched.text
    pair = next(p for p in _pairs_for(world, artisan_id) if world["existing"] in (p["a"]["id"], p["b"]["id"]))
    dismissed = client.post(f"/api/artisan-duplicates/{pair['id']}/dismiss", headers=admin, json={"note": None})
    assert dismissed.status_code == 200 and dismissed.json()["status"] == "NOT_DUPLICATE"
    client.patch(f"/api/artisans/{artisan_id}", headers=h, json={"notes": "edited"})
    still = [p for p in _pairs_for(world, artisan_id, status="NOT_DUPLICATE") if p["id"] == pair["id"]]
    assert still and still[0]["status"] == "NOT_DUPLICATE"
    reopened = client.post(f"/api/artisan-duplicates/{pair['id']}/reopen", headers=admin)
    assert reopened.json()["status"] == "OPEN"


def test_admin_only_queue_preview_merge_redirect_and_undo(world):
    client, h, admin = world["client"], world["headers"]["designer"], world["headers"]["admin"]
    assert client.get("/api/artisan-duplicates", headers=h).status_code == 403
    keep_id, gone_id = world["gopal"]
    pair = next(p for p in _pairs_for(world, gone_id) if keep_id in (p["a"]["id"], p["b"]["id"]))
    listing = client.get("/api/artisan-duplicates", headers=admin).json()
    assert set(listing["counts"]) == {"VERY_LIKELY", "LIKELY", "POSSIBLE"}
    preview = client.get(f"/api/artisan-duplicates/{pair['id']}", headers=admin)
    assert preview.status_code == 200, preview.text
    body = preview.json()
    assert next(f for f in body["fields"] if f["key"] == "aadhaar")["locked"] is True
    survivor = "A" if body["a"]["id"] == keep_id else "B"
    stale = client.post(f"/api/artisan-duplicates/{pair['id']}/merge", headers=admin, json={
        "survivor": survivor, "choices": {}, "expectedUpdatedAt": {"a": "2001-01-01T00:00:00Z", "b": "2001-01-01T00:00:00Z"},
    })
    assert stale.status_code == 409 and stale.json()["detail"]["code"] == "record_changed"
    locked = client.post(f"/api/artisan-duplicates/{pair['id']}/merge", headers=admin, json={
        "survivor": survivor, "choices": {"name": "BOTH"},
        "expectedUpdatedAt": {"a": body["a"]["updatedAt"], "b": body["b"]["updatedAt"]},
    })
    assert locked.status_code == 422
    merged = client.post(f"/api/artisan-duplicates/{pair['id']}/merge", headers=admin, json={
        "survivor": survivor, "choices": {"notes": "A"},
        "expectedUpdatedAt": {"a": body["a"]["updatedAt"], "b": body["b"]["updatedAt"]},
    })
    assert merged.status_code == 200, merged.text
    assert merged.json()["artisan"]["id"] == keep_id
    old = client.get(f"/api/artisans/{gone_id}", headers=h)
    assert old.status_code == 404 and old.json()["detail"] == {
        "code": "artisan_merged", "message": "This artisan was merged into another record.", "survivorId": keep_id,
    }
    # A product queued against the old id lands on the survivor.
    product = client.post("/api/products", headers=h, json={
        "craftName": "Dabu", "place": "Bagru", "artisanName": "Gopal", "productName": "Bedsheet",
        "artisanId": gone_id, "location": LOCATION,
    })
    assert product.status_code == 201, product.text
    assert product.json()["artisanId"] == keep_id
    merges = client.get("/api/artisan-merges", headers=admin).json()["items"]
    record = next(m for m in merges if m["merged"]["id"] == gone_id)
    assert record["canUndo"] is True and record["merged"]["name"].startswith("Gopal Meena")
    undone = client.post(f"/api/artisan-merges/{record['id']}/undo", headers=admin)
    assert undone.status_code == 200, undone.text
    assert undone.json()["restoredArtisanId"] == gone_id
    assert client.get(f"/api/artisans/{gone_id}", headers=h).status_code == 200
