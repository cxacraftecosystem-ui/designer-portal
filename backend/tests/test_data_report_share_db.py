from __future__ import annotations

import asyncio
import contextlib
import json
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

from conftest import needs_db
from query_count_support import asgi_client

from app.core.db import db
from app.core.security import create_access_token

pytestmark = needs_db

STAMP = uuid.uuid4().hex[:8]
SECRET_WORDS = f"private family story {STAMP}"


def _private(work: Any) -> Any:
    async def connected() -> Any:
        await db.connect()
        try:
            return await work()
        finally:
            await db.disconnect()

    return asyncio.run(connected())


def _auth(user: Any) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=user.id)}"}


async def _user(seed: dict[str, Any], role: str, label: str, *, downloads: bool = True) -> Any:
    user = await db.user.create(
        data={
            "email": f"share-{label}-{STAMP}-{uuid.uuid4().hex[:6]}@example.org",
            "name": f"Share {label}",
            "role": role,
            "canDownloadDataset": downloads,
        }
    )
    seed["users"].append(user.id)
    return user


async def _seed() -> dict[str, Any]:
    seed: dict[str, Any] = {"users": [], "media": []}
    admin = await _user(seed, "MASTER_ADMIN", "admin")
    workshop = await db.workshop.create(
        data={"title": f"Share {STAMP}", "date": datetime.now(UTC), "place": "Bagru", "createdById": admin.id}
    )
    artisan = await db.artisan.create(
        data={"name": f"Kamla {STAMP}", "place": "Bagru", "createdById": admin.id, "workshopId": workshop.id}
    )
    await db.workshopartisan.create(data={"workshopId": workshop.id, "artisanId": artisan.id})
    seed.update(admin=admin, workshop=workshop, artisan=artisan)
    for kind, mime, name, extra in (
        ("IMAGE", "image/jpeg", "a.jpg", {}),
        ("AUDIO", "audio/mp4", "b.m4a", {"transcriptText": SECRET_WORDS, "transcriptStatus": "COMPLETED"}),
    ):
        row = await db.mediafile.create(
            data={
                "originalFilename": name,
                "mediaType": kind,
                "mimeType": mime,
                "sizeBytes": 1000,
                "bucket": "test-bucket",
                "objectKey": f"media/{admin.id}/{uuid.uuid4().hex}/{name}",
                "uploadedById": admin.id,
                "artisanId": artisan.id,
                "workshopId": workshop.id,
                **extra,
            }
        )
        seed["media"].append(row.id)
    return seed


async def _cleanup(seed: dict[str, Any]) -> None:
    for step in (
        db.datareportshareview.delete_many(where={"share": {"is": {"ownerId": {"in": seed["users"]}}}}),
        db.datareportshare.delete_many(where={"ownerId": {"in": seed["users"]}}),
        db.mediafile.delete_many(where={"id": {"in": seed["media"]}}),
        db.workshopartisan.delete_many(where={"artisanId": seed["artisan"].id}),
        db.artisan.delete_many(where={"id": seed["artisan"].id}),
        db.workshop.delete_many(where={"id": seed["workshop"].id}),
        db.user.delete_many(where={"id": {"in": seed["users"]}}),
    ):
        with contextlib.suppress(Exception):
            await step


def _token(url: str) -> str:
    return url.rsplit("/", 1)[-1]


def _kpi(snapshot: dict[str, Any], key: str) -> Any:
    return next(k["value"] for k in snapshot["kpis"] if k["key"] == key)


def test_a_link_opens_logs_and_never_carries_transcript_text():
    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            headers = _auth(seed["admin"])
            path = f"by-workshop/{seed['workshop'].id}"
            async with asgi_client() as client:
                made = await client.post(
                    "/api/data/report/shares", json={"path": path, "expiresInDays": 3}, headers=headers
                )
                share = made.json()
                token = _token(share["url"])
                opened = await client.get(f"/api/report-share/{token}", headers={"User-Agent": "UnitBrowser/1"})
                again = await client.get(f"/api/report-share/{token}", headers={"User-Agent": "UnitBrowser/1"})
                signed = await client.get(f"/api/report-share/{token}", headers=headers)
                views = (await client.get(f"/api/data/report/shares/{share['id']}/views", headers=headers)).json()
                listed = (await client.get("/api/data/report/shares", params={"path": path}, headers=headers)).json()
                stored = await db.datareportshare.find_unique(where={"id": share["id"]})
            return {
                "made": made.status_code,
                "share": share,
                "opened": opened.status_code,
                "body": opened.json(),
                "headers": dict(opened.headers),
                "again": again.status_code,
                "signed": signed.status_code,
                "views": views,
                "listed": listed,
                "stored_hash": stored.tokenHash if stored else None,
                "token": token,
                "admin": seed["admin"].id,
            }
        finally:
            await _cleanup(seed)

    out = _private(work)
    assert out["made"] == 200
    assert out["share"]["state"] == "active"
    assert out["share"]["url"].endswith(f"/report-share/{out['token']}")
    assert out["stored_hash"] and out["stored_hash"] != out["token"]
    assert out["opened"] == 200 and out["again"] == 200 and out["signed"] == 200
    assert out["headers"].get("cache-control") == "no-store"
    body = out["body"]
    assert SECRET_WORDS not in json.dumps(body)
    assert _kpi(body, "files") + body["withheldTranscripts"] == 2
    assert _kpi(body, "size") == 1000 * _kpi(body, "files")
    assert body["expiresAt"]
    assert {c["key"] for c in body["charts"]} == {
        "mediaByType",
        "recordingsOverTime",
        "completionBySection",
        "transcriptStatus",
    }
    assert out["views"]["total"] == 2
    viewers = [v["viewer"]["id"] if v["viewer"] else None for v in out["views"]["items"]]
    assert sorted(viewers, key=str) == sorted([None, out["admin"]], key=str)
    assert out["listed"]["items"][0]["views"] == 2


def test_revoked_and_expired_links_are_gone():
    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            headers = _auth(seed["admin"])
            async with asgi_client() as client:
                first = (await client.post("/api/data/report/shares", json={"path": "by-type"}, headers=headers)).json()
                second = (await client.post("/api/data/report/shares", json={"path": "by-type"}, headers=headers)).json()
                revoked = await client.delete(f"/api/data/report/shares/{first['id']}", headers=headers)
                after_revoke = await client.get(f"/api/report-share/{_token(first['url'])}")
                await db.datareportshare.update(
                    where={"id": second["id"]}, data={"expiresAt": datetime.now(UTC) - timedelta(minutes=1)}
                )
                after_expiry = await client.get(f"/api/report-share/{_token(second['url'])}")
                listed = (await client.get("/api/data/report/shares", headers=headers)).json()
                bad_days = [
                    (await client.post("/api/data/report/shares", json={"path": "", "expiresInDays": d}, headers=headers)).status_code
                    for d in (0, 31)
                ]
                junk = await client.get("/api/report-share/not-a-token")
                logged = await db.datareportshareview.count(where={"shareId": {"in": [first["id"], second["id"]]}})
            return {
                "revoked": revoked.status_code,
                "after_revoke": after_revoke.status_code,
                "after_expiry": after_expiry.status_code,
                "states": {i["id"]: i["state"] for i in listed["items"]},
                "ids": (first["id"], second["id"]),
                "bad_days": bad_days,
                "junk": junk.status_code,
                "logged": logged,
            }
        finally:
            await _cleanup(seed)

    out = _private(work)
    assert out["revoked"] == 204
    assert out["after_revoke"] == 404
    assert out["after_expiry"] == 404
    first, second = out["ids"]
    assert out["states"][first] == "revoked"
    assert out["states"][second] == "expired"
    assert out["bad_days"] == [422, 422]
    assert out["junk"] == 404
    assert out["logged"] == 0


def test_a_link_shows_only_what_its_creator_could_see_and_stays_theirs():
    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            researcher = await _user(seed, "RESEARCHER", "researcher")
            plain = await _user(seed, "RESEARCHER", "plain", downloads=False)
            mine = _auth(researcher)
            async with asgi_client() as client:
                theirs = await client.post(
                    "/api/data/report/shares",
                    json={"path": f"by-workshop/{seed['workshop'].id}"},
                    headers=mine,
                )
                own = (await client.post("/api/data/report/shares", json={"path": "by-type"}, headers=mine)).json()
                admin_share = (
                    await client.post("/api/data/report/shares", json={"path": "by-type"}, headers=_auth(seed["admin"]))
                ).json()
                own_body = (await client.get(f"/api/report-share/{_token(own['url'])}")).json()
                admin_body = (await client.get(f"/api/report-share/{_token(admin_share['url'])}")).json()
                foreign_delete = await client.delete(f"/api/data/report/shares/{admin_share['id']}", headers=mine)
                foreign_views = await client.get(f"/api/data/report/shares/{admin_share['id']}/views", headers=mine)
                listed = (await client.get("/api/data/report/shares", headers=mine)).json()
                refused = await client.post("/api/data/report/shares", json={"path": "by-type"}, headers=_auth(plain))
                await db.user.update(where={"id": researcher.id}, data={"canDownloadDataset": False})
                from app.core.deps import invalidate_cached_user

                invalidate_cached_user(researcher.id)
                after_loss = await client.get(f"/api/report-share/{_token(own['url'])}")
            return {
                "theirs": theirs.status_code,
                "own_files": _kpi(own_body, "files"),
                "admin_files": _kpi(admin_body, "files"),
                "foreign_delete": foreign_delete.status_code,
                "foreign_views": foreign_views.status_code,
                "listed": [i["id"] for i in listed["items"]],
                "own_id": own["id"],
                "refused": refused.status_code,
                "after_loss": after_loss.status_code,
            }
        finally:
            await _cleanup(seed)

    out = _private(work)
    assert out["theirs"] == 404
    assert out["own_files"] == 0
    assert out["admin_files"] >= 1
    assert out["foreign_delete"] == 404
    assert out["foreign_views"] == 404
    assert out["listed"] == [out["own_id"]]
    assert out["refused"] == 403
    assert out["after_loss"] == 404


def test_a_link_stops_once_its_owner_is_barred_on_the_access_roster():
    async def work() -> dict[str, Any]:
        seed = await _seed()
        owner = await _user(seed, "ADMIN", "barred-owner")
        try:
            async with asgi_client() as client:
                made = (
                    await client.post("/api/data/report/shares", json={"path": "by-type"}, headers=_auth(owner))
                ).json()
                token = _token(made["url"])
                before = await client.get(f"/api/report-share/{token}")
                await db.accessroster.create(
                    data={"email": owner.email, "status": "SUSPENDED", "decidedAt": datetime.now(UTC)}
                )
                after = await client.get(f"/api/report-share/{token}")
                await db.accessroster.update(where={"email": owner.email}, data={"status": "REJECTED"})
                rejected = await client.get(f"/api/report-share/{token}")
            return {"before": before.status_code, "after": after.status_code, "rejected": rejected.status_code}
        finally:
            with contextlib.suppress(Exception):
                await db.accessroster.delete_many(where={"email": owner.email})
            await _cleanup(seed)

    out = _private(work)
    assert out == {"before": 200, "after": 404, "rejected": 404}
