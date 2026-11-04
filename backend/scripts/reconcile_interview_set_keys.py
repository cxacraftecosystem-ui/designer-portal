import asyncio
import sys
from collections import defaultdict

from app.api.routes.questionnaire import artisan_set_key
from app.core.db import connect_db, db, disconnect_db


async def media_count(interview_id: str) -> int:
    return await db.mediafile.count(where={"questionnaireInterviewId": interview_id})


async def main() -> None:
    execute = "--execute" in sys.argv
    print("MODE:", "EXECUTE" if execute else "DRY RUN (no changes)")
    await connect_db()
    try:
        interviews = await db.questionnaireinterview.find_many(include={"artisans": True})
        correct_key: dict[str, str | None] = {}
        by_key: dict[str, list[str]] = defaultdict(list)
        for iv in interviews:
            key = artisan_set_key(
                [link.artisanId for link in (iv.artisans or [])],
                workshop_id=iv.workshopId,
                design_workshop_id=iv.designWorkshopId,
            )
            correct_key[iv.id] = key
            if key:
                by_key[key].append(iv.id)

        stale = [iv.id for iv in interviews if correct_key[iv.id] != iv.artisanSetKey]
        dup_groups = {k: v for k, v in by_key.items() if len(v) > 1}
        print(f"interviews={len(interviews)} stale_keys={len(stale)} duplicate_set_groups={len(dup_groups)}")

        if execute:
            await db.questionnaireinterview.update_many(where={}, data={"artisanSetKey": None})

        survivors: dict[str, str] = {}
        for key, ids in by_key.items():
            if len(ids) == 1:
                survivors[key] = ids[0]
                continue
            counts = {i: await media_count(i) for i in ids}
            iv_by_id = {iv.id: iv for iv in interviews}
            canonical = min(ids, key=lambda i: (-counts[i], iv_by_id[i].createdAt, i))
            survivors[key] = canonical
            dups = [i for i in ids if i != canonical]
            print(f"  consolidate set {key[:40]}…: keep {canonical} (media={counts[canonical]}), "
                  f"fold {dups}")
            for dup in dups:
                if execute:
                    await db.mediafile.update_many(
                        where={"questionnaireInterviewId": dup}, data={"questionnaireInterviewId": canonical}
                    )
                    await db.mediafile.update_many(
                        where={"linkedRecordType": "questionnaire", "linkedRecordId": dup},
                        data={"linkedRecordId": canonical},
                    )
                    await db.mediafile.update_many(
                        where={"linkedRecordType": "questionnaireinterview", "linkedRecordId": dup},
                        data={"linkedRecordId": canonical},
                    )
                    dup_resp = await db.questionnaireresponse.find_many(where={"interviewId": dup})
                    for r in dup_resp:
                        clash = await db.questionnaireresponse.find_first(
                            where={"interviewId": canonical, "questionId": r.questionId}
                        )
                        if not clash:
                            await db.questionnaireresponse.update(
                                where={"id": r.id}, data={"interviewId": canonical}
                            )
                    await db.questionnaireinterview.delete(where={"id": dup})

        for key, survivor in survivors.items():
            if execute:
                await db.questionnaireinterview.update(
                    where={"id": survivor}, data={"artisanSetKey": key}
                )
        print(f"survivors={len(survivors)}")
    finally:
        await disconnect_db()
    print("\nDone." if execute else "\nDry run complete — re-run with --execute to apply.")


if __name__ == "__main__":
    asyncio.run(main())
