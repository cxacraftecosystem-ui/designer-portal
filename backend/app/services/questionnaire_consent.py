"""Designer-portal's consent gate for the shared questionnaire package (UNIFY_2026-10-21.md 2.3 rule 7).

The package asks ``ports.consent_gate`` the questions documentation-portal's ``services/consent_gate``
answers (``verdict``, ``verdict_details``, ``verdict_detail``, ``current_states``, ``ALLOW``,
``TRANSCRIPT_STATUS_FOR``). This module answers them for this portal with the same rule, the shared
ledger's ``decide`` (``app/transcription/ledger.py``, byte-identical in both portals), over:

1. the recording's own current ``RECORDING`` row (a refusal there always refuses);
2. the take's ANSWERED and PRESENT artisans (``QuestionnaireRecordingSubject``); for a file that is
   no take, its own artisan and its interview's participants;
3. **the design workshop's dictation consent** (the port's extra on this portal, C4): the newest
   ``WORKSHOP`` row for the session's design workshop (``dictation_consent.current_row``, which falls
   back to the cached ``DesignWorkshop.dictationConsent`` columns before the ledger migration ran).

OPT-OUT (owner, 19 and 22 Oct 2026): no answer on record counts as agreeing, so only a REFUSED row
stops an outside send. Recording, uploading, filing, typing answers and review never consult this
module; only a send (speech-to-text, an AI draft) does. A guest respondent has no ledger subject
type, so a guest never refuses on their own; the artisans and the workshop of the same take still do.

This fixes DR audit H3 for questionnaire takes: the gate is per subject and per workshop, not per
workshop alone.
"""

from __future__ import annotations

from collections.abc import Iterable
from typing import Any

from app.transcription import ledger
from app.transcription.ledger import ALLOW, AWAITING, REFUSED, Verdict

__all__ = ["ALLOW", "AWAITING", "REFUSED", "Verdict"]

PURPOSE = ledger.EXTERNAL_PROCESSING
SOURCE_RESEARCHER = ledger.SOURCE_RESEARCHER
SOURCE_OWNER = ledger.SOURCE_OWNER
TRANSCRIPT_STATUS_FOR = {AWAITING: "AWAITING_CONSENT", REFUSED: "REFUSED_CONSENT"}


def _db(client: Any) -> Any:
    if client is not None:
        return client
    from app.core.db import db

    return db


def decide(recording: Any, subjects: Iterable[Any]) -> Verdict:
    return ledger.decide(recording, subjects)


async def current_states(
    subject_type: str, subject_ids: list[str], *, purpose: str = PURPOSE, client: Any = None
) -> dict[str, dict[str, Any]]:
    """The newest ledger row per subject, keyed by subject id."""
    ids = list(dict.fromkeys(str(i) for i in subject_ids if i))
    if not ids:
        return {}
    return await ledger.current_states(_db(client), subject_type, ids, purpose=purpose)


async def _subjects_many(media_ids: list[str], client: Any) -> tuple[dict[str, list[str]], dict[str, list[str]]]:
    """Per media id: the artisans it is about, and the design workshops that name it. Three reads."""
    db = _db(client)
    artisans: dict[str, list[str]] = {}
    workshops: dict[str, list[str]] = {m: [] for m in media_ids}
    rows = await db.query_raw(
        'SELECT r."mediaFileId" AS m, s."artisanId" FROM "QuestionnaireRecording" r '
        'LEFT JOIN "QuestionnaireRecordingSubject" s ON s."recordingId" = r."id" WHERE r."mediaFileId" = ANY($1::text[])',
        media_ids,
    )
    for row in rows:
        found = artisans.setdefault(str(row["m"]), [])
        if row.get("artisanId"):
            found.append(str(row["artisanId"]))
    rest = [m for m in media_ids if m not in artisans]
    if rest:
        linked = await db.query_raw(
            'SELECT m."id" AS m, m."artisanId" AS a FROM "MediaFile" m WHERE m."id" = ANY($1::text[]) AND m."artisanId" IS NOT NULL '
            'UNION SELECT m."id", qa."artisanId" FROM "MediaFile" m JOIN "QuestionnaireInterviewArtisan" qa '
            '  ON qa."interviewId" = m."questionnaireInterviewId" WHERE m."id" = ANY($1::text[])',
            rest,
        )
        for media_id in rest:
            artisans[media_id] = []
        for row in linked:
            if row.get("a"):
                artisans[str(row["m"])].append(str(row["a"]))
    named = await db.query_raw(
        'SELECT m."id" AS m, i."designWorkshopId" AS w FROM "MediaFile" m '
        'JOIN "QuestionnaireInterview" i ON i."id" = m."questionnaireInterviewId" '
        'WHERE m."id" = ANY($1::text[]) AND i."designWorkshopId" IS NOT NULL',
        media_ids,
    )
    for row in named:
        if row.get("w") and str(row["w"]) not in workshops[str(row["m"])]:
            workshops[str(row["m"])].append(str(row["w"]))
    return artisans, workshops


async def _workshop_rows(workshop_ids: list[str], client: Any) -> dict[str, dict[str, Any] | None]:
    from app.services import dictation_consent

    out: dict[str, dict[str, Any] | None] = {}
    for workshop_id in dict.fromkeys(workshop_ids):
        out[workshop_id] = await dictation_consent.current_row(workshop_id, client=_db(client))
    return out


async def verdict_details(media_ids: Iterable[str], *, client: Any = None) -> dict[str, dict[str, Any]]:
    """``{"verdict", "source"}`` per media id, in a handful of reads whatever the number of files."""
    ids = list(dict.fromkeys(str(i) for i in media_ids if i))
    if not ids:
        return {}
    grants = await current_states(ledger.RECORDING, ids, client=client)
    artisans, workshops = await _subjects_many(ids, client)
    states = await current_states(ledger.ARTISAN, [a for m in ids for a in artisans.get(m, [])], client=client)
    workshop_rows = await _workshop_rows([w for m in ids for w in workshops.get(m, [])], client)
    out: dict[str, dict[str, Any]] = {}
    for media_id in ids:
        grant = grants.get(media_id)
        rows = [states.get(a) for a in artisans.get(media_id, [])]
        rows += [workshop_rows.get(w) for w in workshops.get(media_id, [])]
        result = decide(grant, rows)
        if result == ALLOW and (grant or {}).get("state") == ledger.GRANTED:
            out[media_id] = {"verdict": ALLOW, "source": (grant or {}).get("source") or SOURCE_OWNER}
        else:
            answered = grant is not None or any(rows)
            out[media_id] = {"verdict": result, "source": SOURCE_RESEARCHER if answered else None}
    return out


async def verdict_detail(media_id: str, *, client: Any = None) -> dict[str, Any]:
    return (await verdict_details([media_id], client=client))[str(media_id)]


async def verdict(media: Any, *, client: Any = None) -> Verdict:
    """ALLOW or REFUSED for one media file (a row, a dict or its id)."""
    media_id = media if isinstance(media, str) else str(getattr(media, "id", None) or media["id"])
    return (await verdict_detail(media_id, client=client))["verdict"]


async def artisans_verdict(artisan_ids: Iterable[str], *, design_workshop_id: str | None = None, client: Any = None) -> Verdict:
    """Text about people that is no one recording: one refusal (an artisan's or the workshop's) refuses."""
    ids = list(dict.fromkeys(str(i) for i in artisan_ids if i))
    states = await current_states(ledger.ARTISAN, ids, client=client)
    rows: list[Any] = [states.get(a) for a in ids]
    if design_workshop_id:
        rows += list((await _workshop_rows([design_workshop_id], client)).values())
    return decide(None, rows)


async def interview_verdict(interview_id: str, *, client: Any = None) -> Verdict:
    """:func:`artisans_verdict` over a session's participants and its design workshop."""
    db = _db(client)
    rows = await db.query_raw('SELECT "artisanId" FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = $1', interview_id)
    session = await db.query_raw('SELECT "designWorkshopId" FROM "QuestionnaireInterview" WHERE "id" = $1', interview_id)
    workshop = str(session[0]["designWorkshopId"]) if session and session[0].get("designWorkshopId") else None
    return await artisans_verdict([str(r["artisanId"]) for r in rows], design_workshop_id=workshop, client=client)


async def record_artisan_consent(artisan_id: str, *, state: str, actor_id: str, basis: str = ledger.VERBAL_RECORDED,
                                 purpose: str = PURPOSE, note: str | None = None, client: Any = None) -> dict[str, Any]:
    written = await ledger.append(
        _db(client), subject_type=ledger.ARTISAN, subject_id=artisan_id, state=state, basis=basis,
        actor_id=actor_id, purpose=purpose, note=note,
    )
    return written or {}


async def record_recording_consent(media_id: str, *, state: str, actor_id: str, basis: str = ledger.VERBAL_RECORDED,
                                   note: str | None = None, client: Any = None) -> dict[str, Any]:
    written = await ledger.append(
        _db(client), subject_type=ledger.RECORDING, subject_id=media_id, state=state, basis=basis,
        actor_id=actor_id, note=note,
    )
    return written or {}
