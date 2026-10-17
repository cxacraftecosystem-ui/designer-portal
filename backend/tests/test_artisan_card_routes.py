"""Artisan QR cards through the API, against a real database.

Pinned here: a card is issued once however many first reads race; a reissue revokes and the old
code answers REPLACED; resolve finds by QR and by typed code, refuses a mistyped code (422) and a
forged tag (404), and limits misses but never hits; every route is 404 while cards are off; the PDFs
are the right size and print the masked Aadhaar and never a 12-digit run; a sheet prints ceil(n/10)
A4 pages and refuses a request naming an artisan that can't be printed; the register list carries
the card; the backfill is idempotent.

The seed follows tests/test_workshop_join_sync.py: rows are written in a private event loop before
the TestClient starts, and the tests then drive the client only.
"""

from __future__ import annotations

import asyncio
import io
import math
import os
import re
import uuid
from concurrent.futures import ThreadPoolExecutor
from typing import Any

import pytest

from app.core.config import get_settings
from app.core.db import db
from app.core.security import create_access_token, hash_password

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL, reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL"
    ),
]

KEY = "test-only-artisan-card-key-not-a-secret-0123456789"
MM = 72 / 25.4
SHEET_COUNT = 12


def _card_settings(**over: Any) -> dict[str, Any]:
    values = {
        "artisan_cards_enabled": True,
        "artisan_card_key": KEY,
        "artisan_card_key_version": 1,
        "artisan_card_key_previous": None,
        "artisan_card_resolve_miss_limit": 20,
        "artisan_card_issuer": "D",
    }
    values.update(over)
    return values


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app
    from scripts import backfill_artisan_cards

    settings = get_settings()
    saved = {name: getattr(settings, name) for name in _card_settings()}
    for name, value in _card_settings().items():
        setattr(settings, name, value)
    tag = uuid.uuid4().hex[:8]

    async def seed() -> dict[str, Any]:
        await db.connect()
        try:
            admin = await db.user.create(
                data={
                    "email": f"card-admin-{tag}@example.org",
                    "name": "Card Admin",
                    "role": "ADMIN",
                    "passwordHash": hash_password("unused"),
                }
            )
            researcher = await db.user.create(
                data={
                    "email": f"card-researcher-{tag}@example.org",
                    "name": "Card Researcher",
                    "role": "RESEARCHER",
                    "passwordHash": hash_password("unused"),
                }
            )
            prober = await db.user.create(
                data={
                    "email": f"card-prober-{tag}@example.org",
                    "name": "Card Prober",
                    "role": "RESEARCHER",
                    "passwordHash": hash_password("unused"),
                }
            )
            craft = await db.craft.create(
                data={"name": f"Bandhani {tag}", "place": "Bhuj", "createdById": admin.id}
            )
            location = await db.location.create(
                data={"latitude": 23.2, "longitude": 69.6, "village": "Nirona", "district": "Kachchh"}
            )
            main = await db.artisan.create(
                data={
                    "name": f"Meena Devi {tag}",
                    "localName": "मीना देवी",
                    "place": "Bhuj",
                    "craftId": craft.id,
                    "locationId": location.id,
                    "aadhaarLast4": "9012",
                    "phone": "9999988888",
                    "createdById": admin.id,
                }
            )
            racer = await db.artisan.create(
                data={"name": f"Racer {tag}", "place": "Bagru", "createdById": admin.id}
            )
            sheet = []
            for index in range(SHEET_COUNT):
                row = await db.artisan.create(
                    data={
                        "name": f"Sheet Artisan {index} {tag}",
                        "place": "Sanganer",
                        "aadhaarLast4": f"{1000 + index}",
                        "createdById": admin.id,
                    }
                )
                sheet.append(row.id)
            # The backfill, twice: the second run issues nothing.
            _seen_dry, missing_dry, issued_dry = await backfill_artisan_cards.run(apply=False)
            first = await backfill_artisan_cards.run(apply=True)
            second = await backfill_artisan_cards.run(apply=True)
            backfilled = await db.artisancard.count(
                where={"artisanId": {"in": sheet}, "revokedAt": None}
            )
            # The racer's card is issued by the API, below, so the backfill must not have reached it:
            # wipe whatever it issued for that one artisan.
            await db.artisancard.delete_many(where={"artisanId": racer.id})
            return {
                "admin": admin.id,
                "researcher": researcher.id,
                "prober": prober.id,
                "main": main.id,
                "racer": racer.id,
                "sheet": sheet,
                "tag": tag,
                "backfill": {
                    "dry": (missing_dry, issued_dry),
                    "first": first,
                    "second": second,
                    "live": backfilled,
                },
            }
        finally:
            await db.disconnect()

    try:
        seeded = asyncio.run(seed())
        with TestClient(app) as client:
            seeded["client"] = client
            yield seeded
    finally:
        for name, value in saved.items():
            setattr(settings, name, value)


def _auth(user_id: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(user_id)}"}


@pytest.fixture
def client(world):
    return world["client"]


@pytest.fixture
def admin(world):
    return _auth(world["admin"])


def test_status_says_enabled_with_the_issuer(client, admin):
    response = client.get("/api/artisan-cards/status", headers=admin)
    assert response.status_code == 200
    assert response.json() == {"enabled": True, "issuer": "D"}


def test_the_backfill_is_a_dry_run_by_default_and_idempotent(world):
    missing, issued = world["backfill"]["dry"]
    assert missing >= SHEET_COUNT and issued == 0
    assert world["backfill"]["first"][2] == world["backfill"]["first"][1]
    assert world["backfill"]["second"][1] == 0 and world["backfill"]["second"][2] == 0
    assert world["backfill"]["live"] == SHEET_COUNT


def test_the_card_json_has_the_face_and_nothing_more(client, admin, world):
    response = client.get(f"/api/artisans/{world['main']}/card", headers=admin)
    assert response.status_code == 200, response.text
    body = response.json()
    assert re.fullmatch(r"D-[0-9A-Z]{5}-[0-9A-Z]{5}-[0-9A-Z]{2}", body["shortCode"])
    assert body["token"].startswith("CXA1:D1:" + body["code"] + ":")
    assert world["main"] not in body["token"]
    assert body["face"] == {
        "name": f"Meena Devi {world['tag']}",
        "localName": "मीना देवी",
        "craft": f"Bandhani {world['tag']}",
        "cluster": "Nirona",
        "aadhaarLast4": "9012",
        "photoMediaId": None,
    }
    assert body["qrSvg"].startswith("<svg") and "<path" in body["qrSvg"]
    assert "9999988888" not in response.text
    again = client.get(f"/api/artisans/{world['main']}/card", headers=admin).json()
    assert again["code"] == body["code"], "a second read returns the same card"


def test_an_unknown_artisan_has_no_card(client, admin):
    assert client.get("/api/artisans/does-not-exist/card", headers=admin).status_code == 404


def test_concurrent_first_reads_issue_one_card(client, admin, world):
    url = f"/api/artisans/{world['racer']}/card"
    with ThreadPoolExecutor(max_workers=6) as pool:
        answers = list(pool.map(lambda _: client.get(url, headers=admin), range(6)))
    assert all(a.status_code == 200 for a in answers), [a.text for a in answers]
    assert len({a.json()["code"] for a in answers}) == 1


def test_resolve_finds_by_qr_and_by_typed_code(client, admin, world):
    card = client.get(f"/api/artisans/{world['main']}/card", headers=admin).json()
    for given in (card["token"], card["shortCode"], card["shortCode"].lower().replace("-", " ")):
        response = client.post(
            "/api/artisan-cards/resolve", json={"input": given, "source": "ANDROID"}, headers=admin
        )
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["status"] == "FOUND" and body["via"] == "LOCAL"
        assert body["artisan"]["id"] == world["main"]
        assert body["artisan"]["aadhaarLast4"] == "9012"
        assert body["artisan"]["cluster"] == "Nirona"


def test_resolve_refuses_a_mistyped_code_and_a_forged_tag(client, admin, world):
    card = client.get(f"/api/artisans/{world['main']}/card", headers=admin).json()
    short = card["shortCode"]
    wrong = short[:-1] + ("0" if short[-1] != "0" else "1")
    bad = client.post("/api/artisan-cards/resolve", json={"input": wrong}, headers=admin)
    assert bad.status_code == 422
    assert bad.json()["detail"] == "Check the code. One character is wrong."
    head, tag_part = card["token"].rsplit(":", 1)
    forged = head + ":" + ("0" if tag_part[0] != "0" else "1") + tag_part[1:]
    response = client.post("/api/artisan-cards/resolve", json={"input": forged}, headers=admin)
    assert response.status_code == 404
    assert response.json()["detail"] == "No artisan matches this card."


def test_an_unknown_code_is_the_same_404(client, admin):
    from app.services import artisan_card as ac

    short = ac.short_code("D", "ZZZZZZZZZZ")
    response = client.post("/api/artisan-cards/resolve", json={"input": short}, headers=admin)
    assert response.status_code == 404
    assert response.json()["detail"] == "No artisan matches this card."


def test_an_f_card_with_no_link_and_no_channel_is_the_same_404(client, admin):
    from app.services import artisan_card as ac

    response = client.post(
        "/api/artisan-cards/resolve", json={"input": ac.short_code("F", "0123456789")}, headers=admin
    )
    assert response.status_code == 404
    assert response.json()["detail"] == "No artisan matches this card."


def test_misses_are_limited_and_hits_never_count(client, world):
    from app.services import artisan_card as ac

    settings = get_settings()
    prober = _auth(world["prober"])
    card = client.get(f"/api/artisans/{world['main']}/card", headers=prober).json()
    settings.artisan_card_resolve_miss_limit = 3
    try:
        for _ in range(6):
            hit = client.post("/api/artisan-cards/resolve", json={"input": card["shortCode"]}, headers=prober)
            assert hit.status_code == 200
        for index in range(3):
            miss = client.post(
                "/api/artisan-cards/resolve",
                json={"input": ac.short_code("D", f"ZZZZZZZZ{index}0")},
                headers=prober,
            )
            assert miss.status_code == 404
        blocked = client.post("/api/artisan-cards/resolve", json={"input": card["shortCode"]}, headers=prober)
        assert blocked.status_code == 429
    finally:
        settings.artisan_card_resolve_miss_limit = 20


def test_reissue_is_for_editors_and_the_old_card_says_replaced(client, admin, world):
    old = client.get(f"/api/artisans/{world['main']}/card", headers=admin).json()
    refused = client.post(
        f"/api/artisans/{world['main']}/card/reissue",
        json={"reason": "LOST"},
        headers=_auth(world["researcher"]),
    )
    assert refused.status_code == 403
    bad_reason = client.post(
        f"/api/artisans/{world['main']}/card/reissue", json={"reason": "STOLEN"}, headers=admin
    )
    assert bad_reason.status_code == 422
    response = client.post(
        f"/api/artisans/{world['main']}/card/reissue", json={"reason": "LOST"}, headers=admin
    )
    assert response.status_code == 200, response.text
    new = response.json()
    assert new["code"] != old["code"]
    assert new["replacedCards"] >= 1
    replaced = client.post("/api/artisan-cards/resolve", json={"input": old["token"]}, headers=admin)
    assert replaced.status_code == 200
    assert replaced.json() == {"status": "REPLACED", "artisanId": world["main"]}
    found = client.post("/api/artisan-cards/resolve", json={"input": new["shortCode"]}, headers=admin)
    assert found.json()["status"] == "FOUND"


def _pdf(content: bytes):
    from pypdf import PdfReader

    return PdfReader(io.BytesIO(content))


def _size_mm(page) -> tuple[float, float]:
    box = page.mediabox
    return round(float(box.width) / MM, 1), round(float(box.height) / MM, 1)


def test_the_id1_and_a6_pdfs(client, admin, world):
    for size, expected in (("id1", (85.6, 54.0)), ("a6", (105.0, 148.0))):
        response = client.get(f"/api/artisans/{world['main']}/card.pdf?size={size}&photo=0", headers=admin)
        assert response.status_code == 200, response.text
        assert response.headers["content-type"] == "application/pdf"
        assert re.search(r'filename="artisan-card-D-[0-9A-Z-]+\.pdf"', response.headers["content-disposition"])
        reader = _pdf(response.content)
        assert len(reader.pages) == 1
        assert _size_mm(reader.pages[0]) == expected
        text = reader.pages[0].extract_text()
        assert "XXXX XXXX 9012" in text
        assert not re.search(r"\d{12}", text.replace(" ", ""))
        assert "9999988888" not in text


def test_the_sheet_prints_ten_to_an_a4_page(client, admin, world):
    response = client.post(
        "/api/artisan-cards/sheet.pdf",
        json={"artisanIds": world["sheet"], "size": "id1", "photo": False},
        headers=admin,
    )
    assert response.status_code == 200, response.text
    reader = _pdf(response.content)
    assert len(reader.pages) == math.ceil(SHEET_COUNT / 10)
    assert _size_mm(reader.pages[0]) == (210.0, 297.0)
    text = "".join(page.extract_text() for page in reader.pages)
    assert "XXXX XXXX 1000" in text and "XXXX XXXX 1011" in text
    a6 = client.post(
        "/api/artisan-cards/sheet.pdf",
        json={"artisanIds": world["sheet"][:3], "size": "a6", "photo": False},
        headers=admin,
    )
    assert len(_pdf(a6.content).pages) == 3


def test_a_sheet_naming_an_unprintable_artisan_prints_nothing(client, admin, world):
    response = client.post(
        "/api/artisan-cards/sheet.pdf",
        json={"artisanIds": [world["sheet"][0], "nobody-1", "nobody-2"], "size": "id1"},
        headers=admin,
    )
    assert response.status_code == 422
    assert response.json()["detail"] == "2 of these artisans can't be printed by you."
    both = client.post(
        "/api/artisan-cards/sheet.pdf",
        json={"artisanIds": [world["sheet"][0]], "designWorkshopId": "x", "size": "id1"},
        headers=admin,
    )
    assert both.status_code == 422
    too_many = client.post(
        "/api/artisan-cards/sheet.pdf", json={"artisanIds": [f"a{i}" for i in range(201)]}, headers=admin
    )
    assert too_many.status_code == 422


def test_the_register_list_carries_the_card(client, admin, world):
    response = client.get("/api/artisans?search=Sheet Artisan&pageSize=100", headers=admin)
    assert response.status_code == 200
    items = [i for i in response.json()["items"] if i["id"] in world["sheet"]]
    assert len(items) == SHEET_COUNT
    for item in items:
        assert item["card"]["token"].startswith("CXA1:D1:")
        assert item["card"]["shortCode"].startswith("D-")
        assert item["linkedCardCodes"] == []
        assert item["aadhaarLast4"]


def test_every_route_is_404_while_cards_are_off(client, admin, world):
    settings = get_settings()
    settings.artisan_card_key = None
    try:
        assert client.get("/api/artisan-cards/status", headers=admin).json()["enabled"] is False
        assert client.get(f"/api/artisans/{world['main']}/card", headers=admin).status_code == 404
        assert client.get(f"/api/artisans/{world['main']}/card.pdf", headers=admin).status_code == 404
        assert (
            client.post("/api/artisan-cards/resolve", json={"input": "D-00000-00000-00"}, headers=admin).status_code
            == 404
        )
        assert (
            client.post(
                f"/api/artisans/{world['main']}/card/reissue", json={"reason": "LOST"}, headers=admin
            ).status_code
            == 404
        )
        listed = client.get("/api/artisans?search=Sheet Artisan&pageSize=5", headers=admin).json()
        assert all("card" not in item for item in listed["items"])
    finally:
        settings.artisan_card_key = KEY
