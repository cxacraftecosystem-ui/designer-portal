"""A design workshop's own files, read by the people who read the workshop and change none of it.

Owner's ruling (sweep item F5, 2026-10-10): a workshop's inspectors and its directorate monitors —
whoever holds its Assistant Director or Regional Director post, a Ministry Admin, an admin or the
master admin included — see its photographs, recordings and attachments, read-only, on the same
screen they already read its stages on. Until this module existed both read surfaces counted the
files and said they could not show them.

WHAT THIS MODULE IS NOT
=======================

* **It is not a door.** It never decides WHO may read a workshop. Every caller must already have
  loaded the workshop through its surface's own read-only loader —
  the inspection surface's read-only loader (``services/design_workshop_inspectors``) or
  ``design_workshop_oversight.load_overseen_workshop_or_404`` — which 404s an inspector of another
  workshop and a post holder not posted to this one before this module is reached. The two routes
  that call it are GETs behind ``require_inspector`` and ``require_officer``.
* **It is not a widening of the media predicates.** ``records.media_url_scope`` and
  ``records._design_workshop_media_ids`` are untouched, so an inspector still gets no URL from
  ``GET /media``, ``/search``, ``/export`` or ``/data``. The entitlement is stated here, per call,
  as the one workshop the route has just admitted the caller to.
* **It is not a write.** Nothing here writes, and ``design_workshop_posts.refuse_a_holders_media_write``
  still refuses a holder every media write door.

HOW THE BYTES TRAVEL
====================

Through the existing encoder, ``records.public_encode``, with the uploader half empty and the
workshop half naming this workshop only — so ``_redact_sensitive`` keeps a file's URL exactly when
the file is tagged to this workshop (``linkedRecordType="designWorkshop"``, the tag both clients
write) and withholds it otherwise. ``signed_only=True`` then replaces the kept URL with a short-lived
signature even while ``MEDIA_PRESIGNED_READS`` is off, drops ``objectKey`` and ``publicUrl`` (either
of which is the file while the bucket is public-read), and sends no URL at all for a file that cannot
be signed. No permanent handle leaves this surface.
"""

from __future__ import annotations

from typing import Any

from app.core.db import db
from app.services.dictation_consent import MEDIA_TAG
from app.services.records import public_encode, with_id_tiebreak

#: How many files one read returns. A fortnight's workshop holds a few hundred photographs at most;
#: past this the answer says ``truncated`` rather than pretending the list is whole.
READER_MEDIA_LIMIT = 500

#: The columns a reader is given. A whitelist, so a column added to ``MediaFile`` later is not
#: shipped to an inspector by default. ``url`` is present only when the encoder kept and signed it.
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
    """Every file tagged to ``workshop_id``, as a reader may see it, oldest first.

    PRECONDITION, NOT A CHECK: the caller has already admitted ``viewer`` to this workshop through
    its surface's read-only loader. See the module docstring.
    """
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
