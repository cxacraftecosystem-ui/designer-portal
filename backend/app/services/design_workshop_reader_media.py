from __future__ import annotations

from typing import Any

from app.core.db import db
from app.services.dictation_consent import MEDIA_TAG
from app.services.records import public_encode, with_id_tiebreak

READER_MEDIA_LIMIT = 500

_READER_KEYS = (
    "id",
    "originalFilename",
    "mediaType",
    "mimeType",
    "sizeBytes",
    "url",
    "caption",
    "transcriptText",
    "recordedAt",
    "createdAt",
)


async def workshop_media_for_reader(workshop_id: str, viewer: Any) -> dict[str, Any]:
    rows = await db.mediafile.find_many(
        where={"linkedRecordType": MEDIA_TAG, "linkedRecordId": workshop_id},
        order=with_id_tiebreak({"createdAt": "asc"}),
        take=READER_MEDIA_LIMIT + 1,
    )
    truncated = len(rows) > READER_MEDIA_LIMIT
    encoded = public_encode(
        rows[:READER_MEDIA_LIMIT],
        viewer,
        media_urls=set(),
        media_workshops=frozenset({workshop_id}),
        signed_only=True,
    )
    items = [{key: row[key] for key in _READER_KEYS if key in row} for row in encoded]
    return {"items": items, "truncated": truncated}
