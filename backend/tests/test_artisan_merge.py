"""Merging two records of one artisan, and undoing it (services/artisan_merge.py).

Database-backed and service-level: every test is ONE ``asyncio.run`` of a coroutine that connects
``db``, does its work and disconnects (tests/conftest.py explains why nothing else is safe here).
Skips itself unless DATABASE_URL points at a local database.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest
from fastapi import HTTPException

from app.core.db import db
from app.services import artisan_merge
from tests import duplicates_support as ds

pytestmark = pytest.mark.skipif(not ds.LOCAL, reason="needs a LOCAL database")


def run(coro_fn):
    async def wrapper():
        await db.connect()
        try:
            return await coro_fn()
        finally:
            await db.disconnect()

    return asyncio.run(wrapper())


async def _merge(pair: Any, survivor: str, **kw: Any) -> dict[str, Any]:
    return await artisan_merge.merge(
        pair,
        survivor=survivor,
        choices=kw.get("choices", {}),
        expected_updated_at=kw.get("expected"),
        reason=kw.get("reason"),
        user=kw["user"],
        settings=kw.get("settings", ds.NO_PEER),
    )


def test_merge_moves_every_row_and_undo_puts_the_world_back():
    async def scenario():
        t = ds.tag()
        user = await ds.admin(t)
        w = await ds.rich_world(user, t)
        keep, gone = w["keep"], w["gone"]
        ids, dws = [keep.id, gone.id, w["other"].id], [w["dw"].id]
        before = await ds.state(ids, dws)
        pair = await ds.make_pair(keep.id, gone.id)
        survivor = ds.sides(pair, keep.id)
        gone_side = "B" if survivor == "A" else "A"
        preview = await artisan_merge.preview(pair)
        assert preview["blockers"] == []
        result = await _merge(pair, survivor, user=user, choices={"name": gone_side, "notes": "BOTH"})
        assert result["merge"]["mergedId"] == gone.id and result["survivorId"] == keep.id

        # The merged record is gone; everything it held is on the survivor.
        assert await db.artisan.find_unique(where={"id": gone.id}) is None
        after = await db.artisan.find_unique(where={"id": keep.id})
        assert after.name == gone.name
        assert after.phone == "9829012345"  # the survivor's was empty, so the other side's is the default
        assert "Keeps the old blocks." in after.notes and "Prints dabu on Tuesdays." in after.notes
        assert after.aadhaarFingerprint == ds.fp(t)  # unique key taken from the deleted record
        assert after.pehchanCardNumber == f"RJ{t}"
        assert after.extraMetadata["specialisation"] == "Dabu" and after.extraMetadata["age"] == "about 50"
        assert (await db.productdocumentation.find_unique(where={"id": w["product"].id})).artisanId == keep.id
        assert (await db.productdocumentation.find_unique(where={"id": w["product"].id})).artisanName == gone.name
        media = await db.mediafile.find_unique(where={"id": w["media"].id})
        assert media.artisanId == keep.id and media.linkedRecordId == keep.id
        assert await db.workshopartisan.count(where={"artisanId": keep.id}) == 2
        assert await db.toolartisan.count(where={"artisanId": keep.id}) == 2
        assert await db.questionnairesectionstatus.count(where={"artisanId": keep.id}) == 2
        s1 = await db.questionnairesectionstatus.find_first(where={"artisanId": keep.id, "sectionId": w["s1"].id})
        assert s1.status == "DONE"  # the survivor's row wins
        assert await db.dataaccessscopeitem.count(where={"recordId": keep.id}) == 2
        assert await db.recordaccesstoken.count(where={"recordId": keep.id}) == 1
        assert await db.entrycomment.count(where={"recordId": keep.id}) == 1
        assert await db.reviewlog.count(where={"recordId": keep.id}) == 1
        entry = await db.dwstageentry.find_unique(where={"id": w["p_entry"].id})
        assert entry.data["artisanRef"] == keep.id and entry.version == w["p_entry"].version + 1
        tasks = await db.assignedtask.find_many(where={"artisanIds": {"has": keep.id}})
        assert sorted(len(x.artisanIds) for x in tasks) == [1, 2]
        i_gone = await db.questionnaireinterview.find_unique(where={"id": w["i_gone"].id})
        assert keep.id in i_gone.artisanSetKey and gone.id not in i_gone.artisanSetKey
        # prisma-client-py has no JSON-path filter, so the merge revision is picked out here.
        revisions = await db.recordrevision.find_many(where={"recordId": keep.id})
        revision = next(
            (r for r in revisions if isinstance(r.changes, dict) and (r.changes.get("merge") or {}).get("mergedId") == gone.id),
            None,
        )
        assert revision is not None and revision.changes["merge"]["fields"]["name"] == gone_side

        # A merged id still leads somewhere.
        assert await artisan_merge.resolve(gone.id) == keep.id
        payload = {"artisanId": gone.id, "artisanIds": [gone.id, keep.id], "linkedRecordType": "artisan", "linkedRecordId": gone.id}
        await artisan_merge.redirect_payload(payload)
        assert payload == {"artisanId": keep.id, "artisanIds": [keep.id], "linkedRecordType": "artisan", "linkedRecordId": keep.id}

        undone = await artisan_merge.undo(result["merge"]["id"], user=user, settings=ds.NO_PEER)
        assert undone["restoredArtisanId"] == gone.id and undone["keptSurvivorEdits"] == []
        assert await artisan_merge.resolve(gone.id) is None
        assert await ds.state(ids, dws) == before

    run(scenario)


def test_undo_keeps_an_edit_made_to_the_survivor_after_the_merge():
    async def scenario():
        t = ds.tag()
        user = await ds.admin(t)
        keep = await ds.artisan(user, f"Hari Om {t}")
        gone = await ds.artisan(user, f"Hari Om {t}", phone="9829000099")
        pair = await ds.make_pair(keep.id, gone.id)
        result = await _merge(pair, ds.sides(pair, keep.id), user=user)
        await db.artisan.update(where={"id": keep.id}, data={"phone": "9000000000"})
        undone = await artisan_merge.undo(result["merge"]["id"], user=user, settings=ds.NO_PEER)
        assert undone["keptSurvivorEdits"] == ["phone"]
        assert (await db.artisan.find_unique(where={"id": keep.id})).phone == "9000000000"
        assert (await db.artisan.find_unique(where={"id": gone.id})).phone == "9829000099"

    run(scenario)


def _code(exc: HTTPException) -> str:
    detail = exc.detail
    return detail.get("code") if isinstance(detail, dict) else str(detail)


def test_undo_refusals():
    async def scenario():
        t = ds.tag()
        user = await ds.admin(t)
        # Expired.
        a = await ds.artisan(user, f"Kamal {t}")
        b = await ds.artisan(user, f"Kamal {t}")
        pair = await ds.make_pair(a.id, b.id)
        result = await _merge(pair, ds.sides(pair, a.id), user=user)
        await db.artisanmerge.update(where={"id": result["merge"]["id"]}, data={"undoUntil": datetime.now(UTC) - timedelta(minutes=1)})
        with pytest.raises(HTTPException) as caught:
            await artisan_merge.undo(result["merge"]["id"], user=user, settings=ds.NO_PEER)
        assert caught.value.status_code == 409 and _code(caught.value) == "merge_undo_expired"
        # Purge clears the snapshot; the redirect survives.
        await artisan_merge.purge()
        row = await db.artisanmerge.find_unique(where={"id": result["merge"]["id"]})
        assert row.snapshot is None and row.moves is None and row.snapshotPurgedAt is not None
        assert await artisan_merge.resolve(b.id if pair.artisanAId == a.id else a.id) is not None

        # Chained: the survivor was merged again.
        c = await ds.artisan(user, f"Mohan {t}")
        d = await ds.artisan(user, f"Mohan {t}")
        e = await ds.artisan(user, f"Mohan {t}")
        p1 = await ds.make_pair(c.id, d.id)
        first = await _merge(p1, ds.sides(p1, c.id), user=user)
        p2 = await ds.make_pair(c.id, e.id)
        await _merge(p2, ds.sides(p2, e.id), user=user)
        assert await artisan_merge.resolve(d.id) == e.id  # two hops
        with pytest.raises(HTTPException) as caught:
            await artisan_merge.undo(first["merge"]["id"], user=user, settings=ds.NO_PEER)
        assert _code(caught.value) == "merge_undo_later_first"

        # Conflict: the restored fingerprint is now held by someone else.
        f = await ds.artisan(user, f"Sohan {t}")
        g = await ds.artisan(user, f"Sohan {t}", aadhaarFingerprint=ds.fp("conf" + t), aadhaarLast4="1111")
        p3 = await ds.make_pair(f.id, g.id)
        third = await _merge(p3, ds.sides(p3, f.id), user=user)
        await db.artisan.update(where={"id": f.id}, data={"aadhaarFingerprint": ds.fp("edited" + t)})
        await ds.artisan(user, "Somebody else", aadhaarFingerprint=ds.fp("conf" + t))
        with pytest.raises(HTTPException) as caught:
            await artisan_merge.undo(third["merge"]["id"], user=user, settings=ds.NO_PEER)
        assert caught.value.status_code == 409 and _code(caught.value) == "merge_undo_conflict"

    run(scenario)


def test_every_blocker_refuses_with_409():
    async def scenario():
        from app.api.routes.questionnaire import artisan_set_key

        t = ds.tag()
        user = await ds.admin(t)

        async def blocked(a_extra, b_extra, expect):
            a = await ds.artisan(user, f"Block {expect} {t}", **a_extra)
            b = await ds.artisan(user, f"Block {expect} {t}", **b_extra)
            return a, b, await ds.make_pair(a.id, b.id)

        cases = [
            ({"aadhaarFingerprint": ds.fp("x1" + t)}, {"aadhaarFingerprint": ds.fp("x2" + t)}, "different_aadhaar"),
            ({"pehchanCardNumber": f"P1{t}"}, {"pehchanCardNumber": f"P2{t}"}, "different_pehchan"),
            ({"aadhaarNumber": f"9{t[:3]}0000{t[3:7]}"[:12]}, {}, "aadhaar_backfill_pending"),
        ]
        for a_extra, b_extra, expect in cases:
            a, b, pair = await blocked(a_extra, b_extra, expect)
            with pytest.raises(HTTPException) as caught:
                await _merge(pair, "A", user=user)
            assert caught.value.status_code == 409
            assert expect in [x["code"] for x in caught.value.detail["blockers"]]

        a, b, pair = await blocked({}, {}, "hold")
        await db.aadhaarlegalhold.create(data={"recordType": "artisan", "recordId": b.id, "reason": "court order"})
        with pytest.raises(HTTPException) as caught:
            await _merge(pair, "A", user=user)
        assert "aadhaar_legal_hold" in [x["code"] for x in caught.value.detail["blockers"]]

        a, b, pair = await blocked({}, {}, "xp")
        await db.crossportalartisanlink.create(data={"artisanId": a.id, "remoteArtisanId": f"fr1{t}", "matchedBy": "AADHAAR"})
        await db.crossportalartisanlink.create(data={"artisanId": b.id, "remoteArtisanId": f"fr2{t}", "matchedBy": "AADHAAR"})
        preview = await artisan_merge.preview(pair)
        assert "cross_portal_conflict" in [x["code"] for x in preview["blockers"]]
        with pytest.raises(HTTPException) as caught:
            await _merge(pair, "A", user=user)
        assert caught.value.status_code == 409

        # Interview set collision, resolved through the existing interview merge.
        a, b, pair = await blocked({}, {}, "interview")
        keep_side = ds.sides(pair, a.id)
        w = await db.workshop.create(data={"title": f"WI {t}", "date": datetime.now(UTC), "place": "Bagru", "createdById": user.id})
        ia = await db.questionnaireinterview.create(data={"title": f"IA {t}", "createdById": user.id, "workshopId": w.id, "artisanSetKey": artisan_set_key([a.id], workshop_id=w.id, design_workshop_id=None)})
        ib = await db.questionnaireinterview.create(data={"title": f"IB {t}", "createdById": user.id, "workshopId": w.id, "artisanSetKey": artisan_set_key([b.id], workshop_id=w.id, design_workshop_id=None)})
        await db.questionnaireinterviewartisan.create(data={"interviewId": ia.id, "artisanId": a.id})
        await db.questionnaireinterviewartisan.create(data={"interviewId": ib.id, "artisanId": b.id})
        with pytest.raises(HTTPException) as caught:
            await _merge(pair, keep_side, user=user)
        blocker = next(x for x in caught.value.detail["blockers"] if x["code"] == "interview_set_collision")
        action = blocker["action"]
        assert action["kind"] == "merge_interviews" and action["duplicatePairId"] == pair.id
        from app.api.routes.questionnaire import merge_interview_into

        await merge_interview_into(action["sourceId"], action["targetId"], current_user=user, duplicatePairId=pair.id)
        result = await _merge(pair, keep_side, user=user)
        assert result["survivorId"] == a.id

    run(scenario)


def test_stale_expected_updated_at_is_record_changed():
    async def scenario():
        t = ds.tag()
        user = await ds.admin(t)
        a = await ds.artisan(user, f"Stale {t}")
        b = await ds.artisan(user, f"Stale {t}")
        pair = await ds.make_pair(a.id, b.id)
        with pytest.raises(HTTPException) as caught:
            await _merge(pair, "A", user=user, expected={"a": "2001-01-01T00:00:00Z", "b": None})
        assert caught.value.status_code == 409 and _code(caught.value) == "record_changed"

    run(scenario)


def test_bad_and_locked_choices_are_422():
    async def scenario():
        t = ds.tag()
        user = await ds.admin(t)
        a = await ds.artisan(user, f"Choice {t}", aadhaarFingerprint=ds.fp("c" + t), aadhaarLast4="2222")
        b = await ds.artisan(user, f"Choice {t}")
        pair = await ds.make_pair(a.id, b.id)
        holder = ds.sides(pair, a.id)
        other = "B" if holder == "A" else "A"
        for choices in ({"nope": "A"}, {"aadhaar": other}, {"name": "BOTH"}):
            with pytest.raises(HTTPException) as caught:
                await _merge(pair, "A", user=user, choices=choices)
            assert caught.value.status_code == 422

    run(scenario)
