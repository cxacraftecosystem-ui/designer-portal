from __future__ import annotations

import asyncio
import json
import tracemalloc
import uuid
from typing import Any

from conftest import needs_db
from query_count_support import count_statements

from app.core.db import db

pytestmark = needs_db

STAMP = uuid.uuid4().hex[:8]
SEEDED = 14
PREVIEW = 5
RUNS = 10
MIB = 1024 * 1024


async def _seed() -> dict[str, Any]:
    admin = await db.user.create(
        data={"email": f"d39-{STAMP}@example.org", "name": "Report Admin", "role": "MASTER_ADMIN"}
    )
    media = []
    for n in range(SEEDED):
        media.append(
            await db.mediafile.create(
                data={
                    "originalFilename": f"d39-{STAMP}-{n:02d}.m4a",
                    "mediaType": "AUDIO",
                    "mimeType": "audio/mp4",
                    "sizeBytes": 1024,
                    "bucket": "ci",
                    "objectKey": f"media/{admin.id}/d39-{STAMP}-{n}.m4a",
                    "transcriptText": f"**Speaker 1:** words {n} " + "dabu " * 4000,
                    "transcriptStatus": "COMPLETED",
                    "uploadedBy": {"connect": {"id": admin.id}},
                }
            )
        )
    return {"admin": admin, "media": media}


async def _cleanup(seed: dict[str, Any]) -> None:
    ids = [m.id for m in seed["media"]]
    await db.mediafile.delete_many(where={"id": {"in": ids}})
    await db.user.delete_many(where={"id": seed["admin"].id})


def _private(work: Any) -> Any:
    async def connected() -> Any:
        await db.connect()
        try:
            return await work()
        finally:
            await db.disconnect()

    return asyncio.run(connected())


def test_ten_root_reports_keep_statements_and_memory_flat(monkeypatch):
    from app.api.routes import data_browser

    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            statements: list[int] = []
            peaks: list[int] = []
            currents: list[int] = []
            tracemalloc.start()
            try:
                for _ in range(RUNS):
                    tracemalloc.reset_peak()
                    async with count_statements() as counted:
                        response = await data_browser.data_report(
                            path="", format="json", current_user=seed["admin"]
                        )
                    assert response.status_code == 200
                    del response
                    statements.append(counted.value)
                    current, peak = tracemalloc.get_traced_memory()
                    peaks.append(peak)
                    currents.append(current)
            finally:
                tracemalloc.stop()
            return {"statements": statements, "peaks": peaks, "currents": currents}
        finally:
            await _cleanup(seed)

    seen = _private(work)
    statements, peaks, currents = seen["statements"], seen["peaks"], seen["currents"]
    assert len(set(statements[1:])) == 1, f"statements per root report grew: {statements}"
    assert statements[0] >= statements[1], statements
    assert max(peaks[2:]) <= peaks[1] * 1.25 + 4 * MIB, f"peak heap per build: {peaks}"
    assert currents[-1] - currents[1] <= 4 * MIB, f"heap held between builds: {currents}"


def test_the_json_preview_is_cut_and_the_xlsx_gets_every_row(monkeypatch):
    from app.api.routes import data_browser
    from app.services.datareport import writer

    monkeypatch.setattr(data_browser, "PREVIEW_ROWS", PREVIEW)
    handed: dict[str, list[dict[str, Any]]] = {}
    real_write = writer._SheetState.write_rows

    def capture(self: Any, rows: list[dict[str, Any]]) -> None:
        handed.setdefault(self.sheet.key, []).extend(rows)
        real_write(self, rows)

    monkeypatch.setattr(writer._SheetState, "write_rows", capture)

    async def work() -> dict[str, Any]:
        seed = await _seed()
        try:
            shown = await data_browser.data_report(path="", format="json", current_user=seed["admin"])
            download = await data_browser.data_report(path="", format="xlsx", current_user=seed["admin"])
            body = bytes(download.body)
            return {"json": json.loads(shown.body), "xlsx": body, "sheets": handed}
        finally:
            await _cleanup(seed)

    seen = _private(work)
    payload = seen["json"]
    assert payload["previewRows"] == PREVIEW
    by_name = {sheet["name"]: sheet for sheet in payload["sheets"]}
    media = by_name["Media by type"]
    assert len(media["rows"]) == PREVIEW
    assert media["truncated"] is True and media["preview"] is True
    assert media["totalRows"] >= SEEDED

    assert seen["xlsx"][:2] == b"PK"
    full = seen["sheets"]
    assert len(full["media"]) == media["totalRows"]
    assert len([row for row in full["media"] if _text(row.get("originalFilename")).startswith(f"d39-{STAMP}-")]) == SEEDED
    whole = [row for row in full["transcripts"] if row.get("_row") == "summary" and _text(row.get("file")).startswith(f"d39-{STAMP}-")]
    assert len(whole) == SEEDED
    assert all("words" in str(row["text"]) for row in whole)
    english = [row for row in full["transcripts"] if row.get("_row") == "summary_translation" and _text(row.get("file")).startswith(f"d39-{STAMP}-")]
    assert len(english) == SEEDED


def _text(value: Any) -> str:
    return str(getattr(value, "text", value) or "")
