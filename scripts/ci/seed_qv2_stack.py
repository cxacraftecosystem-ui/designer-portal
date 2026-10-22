"""Seed a THROWAWAY CI database for designer-portal's web client against a real stack (stream U1).

Run from ``backend/`` by the ``web-v2-stack`` job in ``.github/workflows/checks.yml``, against the
job's own Postgres service container and nothing else: it refuses a DATABASE_URL whose host is not
loopback. It writes a design workshop and a form whose sections live in the shared instrument
tables (as the U5 migration leaves designer-portal's forms), bound to the workshop as its primary
form, three artisans on the workshop, and four accounts: the lead researcher (the workshop's
creator), a second researcher, a content admin and a researcher who is on no session.

Writes a JSON file (the path is the first argument) the spec reads: ids, question lineages and
short-lived bearer tokens for that throwaway database, which die with the runner.

Usage:  python ../scripts/ci/seed_qv2_stack.py "$RUNNER_TEMP/qv2-seed.json"
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
import uuid
from urllib.parse import urlsplit

HERE = os.getcwd()
sys.path.insert(0, HERE)


def _refuse_remote() -> None:
    host = (urlsplit(os.environ.get("DATABASE_URL", "")).hostname or "").lower()
    if host not in {"127.0.0.1", "localhost", "::1"}:
        raise SystemExit("seed_qv2_stack.py writes; it runs only against a loopback CI database.")


async def main() -> dict:
    _refuse_remote()
    import app  # noqa: F401  (loads settings and the Prisma client the way the API does)
    from app.core.db import db
    from app.core.security import create_access_token

    await db.connect()
    tag = uuid.uuid4().hex[:8]
    try:
        users = {}
        for key, role in (("lead", "RESEARCHER"), ("other", "RESEARCHER"), ("admin", "ADMIN"), ("stranger", "RESEARCHER")):
            uid = f"u{key}{tag}"
            await db.execute_raw(
                'INSERT INTO "User" ("id", "email", "name", "role", "updatedAt") VALUES ($1, $2, $3, $4::"UserRole", now())',
                uid, f"{uid}@example.test", {"lead": "Nandini Rao", "other": "Priya Shah", "admin": "Admin", "stranger": "Ravi Kumar"}[key], role,
            )
            users[key] = uid
        dw = f"dw{tag}"
        await db.execute_raw(
            'INSERT INTO "DesignWorkshop" ("id", "title", "createdById", "clusterName", "district", "state", "craftName", "workshopKind", "updatedAt") '
            "VALUES ($1, 'Bagru block printing', $2, 'Bagru', 'Jaipur', 'Rajasthan', 'Hand Block Printing', 'BASELINE', now())",
            dw, users["lead"],
        )
        form = f"f{tag}"
        await db.execute_raw(
            'INSERT INTO "Questionnaire" ("id", "title", "ownerId", "designWorkshopId", "kind", "updatedAt") '
            "VALUES ($1, $2, $3, $4, 'WORKSHOP_INTERVIEW', now())",
            form, f"2nd Craft Toolkit {tag}", users["lead"], dw,
        )
        await db.execute_raw('UPDATE "QuestionnaireBinding" SET "isPrimary" = true WHERE "questionnaireId" = $1', form)
        sections: dict[str, str] = {}
        for n, (code, title) in enumerate((("A", "Tools"), ("B", "Dyes"), ("C", "Market")), start=1):
            sid = f"s{code}{tag}"
            await db.execute_raw(
                'INSERT INTO "QuestionnaireSection" ("id", "code", "title", "sortOrder", "questionnaireId", "updatedAt") '
                "VALUES ($1, $2, $3, $4, $5, now())",
                sid, f"{code}{tag.upper()}", title, 700000 + n * 10 + int(tag[:3], 16) * 100, form,
            )
            sections[code] = sid
            for q in (1, 2):
                await db.execute_raw(
                    'INSERT INTO "QuestionnaireQuestion" ("id", "sectionCode", "sectionTitle", "prompt", "sortOrder", "sectionId", '
                    '"questionnaireId", "isRequired", "updatedAt") VALUES ($1, $2, $3, $4, $5, $6, $7, $8, now())',
                    f"q{code}{q}{tag}", f"{code}{tag.upper()}", title, f"{title} question {q}?", q, sid, form, q == 1,
                )
        artisans = []
        for n in range(3):
            aid = f"a{n}{tag}"
            await db.execute_raw(
                'INSERT INTO "Artisan" ("id", "name", "place", "designWorkshopId", "createdById", "updatedAt") VALUES ($1, $2, $3, $4, $5, now())',
                aid, ["Giriraj Prasad Chhipa", "Meena Devi", "Ramesh Bhai"][n], "Bagru", dw, users["lead"],
            )
            artisans.append(aid)
        rows = await db.query_raw(
            'SELECT "lineageId", "displayCode", "sectionId" FROM "QuestionnaireQuestion" WHERE "questionnaireId" = $1 ORDER BY "displayCode"', form
        )
        payload = {
            "questionnaireId": form,
            "designWorkshopId": dw,
            "sections": sections,
            "questions": {str(r["displayCode"]): str(r["lineageId"]) for r in rows},
            "artisans": artisans,
            "users": users,
            "tokens": {key: create_access_token(uid) for key, uid in users.items()},
        }
    finally:
        await db.disconnect()
    print(f"seeded {form} with {len(rows)} questions")
    return payload


if __name__ == "__main__":
    seeded = asyncio.run(main())
    with open(sys.argv[1], "w", encoding="utf-8") as handle:
        json.dump(seeded, handle)
