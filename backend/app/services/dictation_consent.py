from __future__ import annotations

import logging
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import Enum
from typing import Any

from app.core.db import db
from app.transcription import ledger

logger = logging.getLogger(__name__)



class DictationConsent(str, Enum):
    NOT_RECORDED = "NOT_RECORDED"
    GRANTED = "GRANTED"
    REFUSED = "REFUSED"


MAX_DEVICE_CLOCK_SKEW = timedelta(minutes=15)

WRITABLE_TABLES: frozenset[str] = frozenset({"DesignWorkshop", "Consent"})

LEDGER_TABLE = "Consent"
LEGACY_TABLE = "DwWorkshopConsentDecision"

STAGE_TABLE = "DwStageEntry"


class ConsentRuleViolation(ValueError):
    pass


def consent_of(row: Any) -> DictationConsent:
    raw = getattr(row, "dictationConsent", None)
    token = str(getattr(raw, "value", raw) or "")
    try:
        return DictationConsent(token)
    except ValueError:
        if token:
            logger.warning(
                "dictation_consent: workshop %s carries the unreadable consent %r; treating it as "
                "NOT_RECORDED",
                getattr(row, "id", "?"),
                token,
            )
        return DictationConsent.NOT_RECORDED



@dataclass(frozen=True, slots=True)
class Send:
    material: str
    destination: str
    consequence: str
    alternative: str = ""

    def __post_init__(self) -> None:
        for name in ("material", "destination", "consequence"):
            if not str(getattr(self, name) or "").strip():
                raise ConsentRuleViolation(
                    f"A send description must name its {name}. A refusal with a blank in it is a "
                    f"sentence a designer reads in a courtyard with a hole where the fact was."
                )
        if self.alternative and not self.alternative.endswith(" "):
            raise ConsentRuleViolation(
                "An alternative is composed between two sentences and must end with the space that "
                "separates it from the next one."
            )


DICTATION = Send(
    material="recordings",
    destination="the transcription service",
    consequence="this dictation cannot be written down there",
    alternative="Type the words in instead. ",
)

SENDS: Mapping[str, Send] = {
    "PROOFREAD": Send(
        material="material",
        destination="OpenAI's language model",
        consequence="this passage cannot be proofread there",
        alternative="Nothing was sent, and the passage is exactly as you left it. ",
    ),
    "EXPAND": Send(
        material="material",
        destination="OpenAI's language model",
        consequence="this note cannot be written out there",
        alternative="Write the note out in your own words instead — typed prose is yours rather than "
        "a machine's, which is what the report needs anyway. ",
    ),
    "TRANSLATE": Send(
        material="material",
        destination="OpenAI's language model",
        consequence="this passage cannot be translated there",
        alternative="Nothing was sent, and the passage stays in the language it was written in. ",
    ),
    "CAPTION": Send(
        material="material",
        destination="Google's Gemini",
        consequence="a photograph or a video from it cannot be described there",
        alternative="Write the description yourself in the caption box under the photograph, where "
        "the stage has one. ",
    ),
    "SUBTITLES": Send(
        material="material",
        destination="the transcription service",
        consequence="a recording or a video from it cannot be subtitled there",
        alternative="Nothing was sent, and the file is untouched. ",
    ),
}

MEDIA = Send(
    material="recordings",
    destination="the transcription service",
    consequence="this recording cannot be written down there",
    alternative="The recording is still kept with the workshop and can be listened to; write down "
    "what matters from it in the stage's own fields instead. ",
)

REFINEMENT = Send(
    material="recordings",
    destination="OpenAI's language model",
    consequence="this transcript cannot be rewritten there",
    alternative="Nothing was sent, and the transcript is exactly as it was. ",
)

INSIGHTS = Send(
    material="transcripts",
    destination="the AI language model",
    consequence="this transcript cannot be summarised there",
    alternative="Nothing was sent, and the transcript is unchanged. ",
)

DRAFTING = Send(
    material="transcripts",
    destination="the AI language model",
    consequence="no answers can be drafted from it there",
    alternative="Nothing was sent; type the answers in instead. ",
)

EMBEDDING = Send(
    material="transcripts",
    destination="the search embedding service",
    consequence="this text cannot be indexed for meaning there",
    alternative="It can still be found by its words. ",
)

UNKNOWN_SEND = Send(
    material="material",
    destination="a third-party provider",
    consequence="this cannot be done there",
    alternative="Nothing was sent. ",
)


def send_for(verb: str | None) -> Send:
    token = str(getattr(verb, "value", verb) or "").upper()
    described = SENDS.get(token)
    if described is None:
        logger.warning(
            "dictation_consent: no send description for verb %r; the refusal will name neither the "
            "material nor the destination",
            token or verb,
        )
        return UNKNOWN_SEND
    return described


def gate_refusal(consent: DictationConsent, send: Send = DICTATION) -> str | None:
    if consent is not DictationConsent.REFUSED:
        return None
    return (
        f"This workshop's {send.material} may not be sent to {send.destination} — that is the "
        f"answer on record — so {send.consequence}. {send.alternative}If the artisan has since "
        f"agreed, change that answer on the workshop's own screen; nothing on this field can "
        f"change it."
    )


def legacy_waiting_sentence(send: Send) -> str:
    return (
        f"Nobody has recorded yet whether {send.material} from this workshop may be sent to "
        f"{send.destination}, so {send.consequence}. {send.alternative}Open the workshop's own screen "
        f"and record the artisan's answer to that question — until somebody does, this stays "
        f"unavailable."
    )


def subject_refusal(consent: DictationConsent, send: Send = MEDIA) -> str:
    if consent is DictationConsent.REFUSED:
        return (
            f"Consent to send these {send.material} to {send.destination} was refused, so "
            f"{send.consequence}. {send.alternative}If the artisan has since agreed, record that on "
            f"the artisan's own record."
        )
    return UNREADABLE



MEDIA_TAG = "designWorkshop"

INTERVIEW_TAG = "questionnaire"


async def interview_workshop_id(media: Any) -> str | None:
    interview_id = str(_attr(media, "questionnaireInterviewId") or "").strip()
    if not interview_id:
        tag = str(_attr(media, "linkedRecordType") or "").strip().lower()
        if tag != INTERVIEW_TAG.lower():
            return None
        interview_id = str(_attr(media, "linkedRecordId") or "").strip()
    if not interview_id:
        return None
    row = await db.questionnaireinterview.find_unique(where={"id": interview_id})
    if row is None:
        return None
    return str(getattr(row, "designWorkshopId", None) or "").strip() or None


class SendDecision(str, Enum):
    NOT_WORKSHOP_MATERIAL = "NOT_WORKSHOP_MATERIAL"
    GRANTED = "GRANTED"
    REFUSED = "REFUSED"


@dataclass(frozen=True, slots=True)
class SendVerdict:
    decision: SendDecision
    workshop_id: str | None = None
    refusal: str | None = None

    def __post_init__(self) -> None:
        if (self.decision is SendDecision.REFUSED) != bool(self.refusal):
            raise ConsentRuleViolation(
                "A refused send must carry the sentence that will be shown, and a permitted one must "
                "carry none. A refusal with nothing to say reaches a designer as a blank."
            )

    @property
    def may_send(self) -> bool:
        return self.decision is not SendDecision.REFUSED


class SendRefused(Exception):
    def __init__(self, verdict: SendVerdict) -> None:
        super().__init__(verdict.refusal or "This send is not permitted.")
        self.verdict = verdict


def tagged_workshop_id(media: Any) -> str | None:
    tag = str(_attr(media, "linkedRecordType") or "").strip().lower()
    if tag != MEDIA_TAG.lower():
        return None
    return str(_attr(media, "linkedRecordId") or "").strip() or None


def verdict_for(
    consent: DictationConsent | None,
    *,
    workshop_id: str | None,
    send: Send = MEDIA,
) -> SendVerdict:
    if not workshop_id:
        return SendVerdict(SendDecision.NOT_WORKSHOP_MATERIAL)
    if consent is None:
        return SendVerdict(SendDecision.REFUSED, workshop_id=workshop_id, refusal=UNREADABLE)
    refusal = gate_refusal(consent, send)
    if refusal is None:
        return SendVerdict(SendDecision.GRANTED, workshop_id=workshop_id)
    return SendVerdict(SendDecision.REFUSED, workshop_id=workshop_id, refusal=refusal)


def _attr(record: Any, key: str) -> Any:
    if isinstance(record, Mapping):
        return record.get(key)
    return getattr(record, key, None)



class Operation(str, Enum):
    CREATE = "CREATE"
    UPDATE = "UPDATE"


@dataclass(frozen=True, slots=True)
class ConsentWritePlan:
    table: str
    operation: Operation
    data: Mapping[str, Any]
    where: Mapping[str, Any] | None = None

    def __post_init__(self) -> None:
        if self.table not in WRITABLE_TABLES:
            raise ConsentRuleViolation(
                f"A dictation consent may not be written into {self.table}. It is three columns on "
                f"the workshop and a row in its decision log, and nowhere else: a stage field cannot "
                f"say WHO recorded it or WHEN — save_stage writes only data, ordinal and deletedAt, "
                f"and a stage row's updatedAt moves whenever anything in that stage changes. Write it "
                f"to one of {', '.join(sorted(WRITABLE_TABLES))}."
            )
        if self.operation is Operation.UPDATE and not self.where:
            raise ConsentRuleViolation(
                "An update must name the single row it changes. Pass where={'id': workshop_id}."
            )
        if self.operation is Operation.CREATE and self.where:
            raise ConsentRuleViolation("A create names no existing row. Drop the where clause.")


@dataclass(frozen=True, slots=True)
class ConsentDecisionPlans:
    workshop: ConsentWritePlan
    decision: ConsentWritePlan

    def __iter__(self):
        yield self.workshop
        yield self.decision


def decision_plans(
    *,
    workshop_id: str,
    decision: DictationConsent,
    actor_id: str,
    at: datetime,
    recorded_at: datetime | None = None,
    note: str | None = None,
) -> ConsentDecisionPlans:
    workshop = _require_text(
        workshop_id,
        what="A consent belongs to a workshop.",
        remedy="Send the id of the workshop whose recordings this answer is about.",
    )
    actor = _require_text(
        actor_id,
        what="A consent records who took the answer down.",
        remedy=(
            "Sign in and record it as yourself — this is the row that says a named person cleared an "
            "artisan's voice to leave the device, and an unsigned one is worth nothing."
        ),
    )
    if decision is DictationConsent.NOT_RECORDED:
        raise ConsentRuleViolation(
            "NOT_RECORDED is what a workshop says before anybody has asked, not an answer somebody "
            "can record. Send GRANTED if the artisan agreed, or REFUSED if they did not — a REFUSED "
            "answer is how a consent is taken back."
        )
    at = _as_utc(at)
    recorded_at = _as_utc(recorded_at)
    if recorded_at is not None and recorded_at > at + MAX_DEVICE_CLOCK_SKEW:
        raise ConsentRuleViolation(
            f"This answer says it was recorded at {recorded_at.isoformat()}, which is in the future — "
            f"the device's clock is wrong. Fix the date and time on the phone and sync again, or "
            f"record the answer again here."
        )

    answered_at = recorded_at or at
    return ConsentDecisionPlans(
        workshop=ConsentWritePlan(
            table="DesignWorkshop",
            operation=Operation.UPDATE,
            where={"id": workshop},
            data={
                "dictationConsent": decision.value,
                "dictationConsentAt": answered_at,
                "dictationConsentById": actor,
            },
        ),
        decision=ConsentWritePlan(
            table=LEDGER_TABLE,
            operation=Operation.CREATE,
            data={
                "subject_type": ledger.WORKSHOP,
                "subject_id": workshop,
                "purpose": ledger.EXTERNAL_PROCESSING,
                "state": decision.value,
                "basis": ledger.VERBAL_RECORDED,
                "source": ledger.SOURCE_RESEARCHER,
                "note": (note or "").strip() or None,
                "actor_id": actor,
                "recorded_at": answered_at,
            },
        ),
    )


def _as_utc(moment: datetime | None) -> datetime | None:
    if moment is None or moment.tzinfo is not None:
        return moment
    return moment.replace(tzinfo=UTC)


def _require_text(value: str | None, *, what: str, remedy: str) -> str:
    text = (value or "").strip()
    if not text:
        raise ConsentRuleViolation(f"{what} {remedy}")
    return text



def consent_keys(record: Any) -> dict[str, Any]:
    return {
        "dictationConsent": consent_of(record).value,
        "dictationConsentAt": _iso(getattr(record, "dictationConsentAt", None)),
        "dictationConsentById": getattr(record, "dictationConsentById", None),
    }


def decision_payload(row: Any) -> dict[str, Any]:
    if isinstance(row, Mapping) and "subjectId" in row:
        return {
            "id": row.get("id"),
            "designWorkshopId": row.get("subjectId"),
            "decision": _enum_str(row.get("state")),
            "note": row.get("note"),
            "actorId": row.get("actorId"),
            "recordedAt": _iso(_moment(row.get("recordedAt"))),
            "createdAt": _iso(_moment(row.get("createdAt"))),
        }
    return _legacy_decision_payload(row)


def _moment(value: Any) -> datetime | None:
    if isinstance(value, str):
        try:
            value = datetime.fromisoformat(value)
        except ValueError:
            return None
    if not isinstance(value, datetime):
        return None
    return value if value.tzinfo is not None else value.replace(tzinfo=UTC)


def _legacy_decision_payload(row: Any) -> dict[str, Any]:
    return {
        "id": getattr(row, "id", None),
        "designWorkshopId": getattr(row, "designWorkshopId", None),
        "decision": _enum_str(getattr(row, "decision", None)),
        "note": getattr(row, "note", None),
        "actorId": getattr(row, "actorId", None),
        "recordedAt": _iso(getattr(row, "recordedAt", None)),
        "createdAt": _iso(getattr(row, "createdAt", None)),
    }


def _enum_str(value: Any) -> str | None:
    if value is None:
        return None
    return str(getattr(value, "value", value))


def _iso(value: Any) -> str | None:
    return value.isoformat() if isinstance(value, datetime) else None



def _writable_model(table: str) -> Any:
    if table not in WRITABLE_TABLES:
        raise ConsentRuleViolation(
            f"There is no consent writer for {table}. A consent is three columns on the workshop and "
            f"a row in its decision log: {', '.join(sorted(WRITABLE_TABLES))}."
        )
    return {"DesignWorkshop": "designworkshop", LEDGER_TABLE: LEDGER_TABLE}[table]


async def apply_plan(plan: ConsentWritePlan, client: Any = None) -> Any:
    target = client if client is not None else db
    model = _writable_model(plan.table)
    if plan.table == LEDGER_TABLE:
        return await ledger.append(target, **dict(plan.data))
    model = getattr(target, model)
    if plan.operation is Operation.CREATE:
        return await model.create(data=dict(plan.data))
    return await model.update(where=dict(plan.where or {}), data=dict(plan.data))


async def apply_decision(plans: ConsentDecisionPlans) -> Any:
    async with db.tx() as tx:
        await apply_plan(plans.decision, tx)
        return await apply_plan(plans.workshop, tx)


async def stage_attached_workshop_ids(media_id: str) -> list[str]:
    media_id = str(media_id or "").strip()
    if not media_id:
        return []
    from app.services.workshop_transcripts import audio_field_map

    field_map = audio_field_map()
    entity_keys = sorted(field_map)
    field_keys = sorted({key for fields in field_map.values() for key in fields})
    if not entity_keys or not field_keys:
        return []
    rows = await db.query_raw(
        'SELECT DISTINCT e."designWorkshopId" AS id FROM "DwStageEntry" e '
        'WHERE e."deletedAt" IS NULL AND e."entityKey" = ANY($2::text[]) AND EXISTS ('
        '  SELECT 1 FROM jsonb_each(e."data") kv'
        "  WHERE kv.key = ANY($3::text[]) AND kv.value @> to_jsonb($1::text))",
        media_id,
        entity_keys,
        field_keys,
    )
    return sorted({str(row["id"]) for row in rows if row.get("id")})


async def transcription_verdict(
    media: Any,
    *,
    design_workshop_id: str | None = None,
    send: Send = MEDIA,
    resolve_from_stages: bool = False,
) -> SendVerdict:
    about = str(_attr(media, "id") or "").strip()
    own = (await ledger.current_states(db, ledger.RECORDING, [about])).get(about) if about else None
    if _consent_token(own) is DictationConsent.REFUSED:
        return SendVerdict(
            SendDecision.REFUSED,
            workshop_id=str(design_workshop_id or "").strip() or tagged_workshop_id(media),
            refusal=subject_refusal(DictationConsent.REFUSED, send),
        )
    candidates: list[str] = []
    for candidate in (str(design_workshop_id or "").strip(), tagged_workshop_id(media)):
        if candidate and candidate not in candidates:
            candidates.append(candidate)
    interview_workshop = await interview_workshop_id(media)
    if interview_workshop and interview_workshop not in candidates:
        candidates.append(interview_workshop)
    if not candidates and resolve_from_stages:
        candidates = await stage_attached_workshop_ids(about)
    artisan_ids = await recording_artisan_ids(media) if about else []
    artisan_ids = sorted({*artisan_ids, *await workshop_artisan_ids(candidates)})
    artisans = await artisan_rows(artisan_ids) if artisan_ids else {}
    return await _subjects_verdict(own, candidates, artisans, send=send, about=about)


async def _subjects_verdict(
    own: Mapping[str, Any] | None,
    candidates: list[str],
    artisans: Mapping[str, Mapping[str, Any]],
    *,
    send: Send,
    about: Any,
    records: Mapping[str, Any] | None = None,
) -> SendVerdict:
    if not candidates and own is None and not artisans:
        return SendVerdict(SendDecision.NOT_WORKSHOP_MATERIAL)
    first = candidates[0] if candidates else None
    try:
        rows: list[Any] = []
        for workshop_id in candidates:
            record = (records or {}).get(workshop_id)
            if record is None:
                record = await db.designworkshop.find_unique(where={"id": workshop_id})
            rows.append(await current_row(workshop_id, record))
    except Exception as exc:  # noqa: BLE001 — a failed read refuses, never raises.
        logger.warning(
            "dictation_consent: could not read the consent of %s (%s); refusing the send", about, exc
        )
        return verdict_for(None, workshop_id=first, send=send) if first else SendVerdict(
            SendDecision.REFUSED, refusal=UNREADABLE
        )
    if ledger.decide(own, [*rows, *artisans.values()]) == ledger.ALLOW:
        return SendVerdict(SendDecision.GRANTED, workshop_id=first)
    for workshop_id, row in zip(candidates, rows, strict=True):
        if _consent_token(row) is DictationConsent.REFUSED:
            return verdict_for(DictationConsent.REFUSED, workshop_id=workshop_id, send=send)
    return SendVerdict(
        SendDecision.REFUSED,
        workshop_id=first,
        refusal=subject_refusal(DictationConsent.REFUSED, send),
    )


async def recording_artisan_ids(media: Any) -> list[str]:
    media_id = str(_attr(media, "id") or "").strip()
    if not media_id:
        return []
    rows = await db.query_raw(
        'SELECT m."artisanId" AS a FROM "MediaFile" m WHERE m."id" = $1 AND m."artisanId" IS NOT NULL '
        'UNION SELECT qa."artisanId" FROM "MediaFile" m JOIN "QuestionnaireInterviewArtisan" qa '
        '  ON qa."interviewId" = COALESCE(m."questionnaireInterviewId", '
        "    CASE WHEN lower(m.\"linkedRecordType\") = 'questionnaire' THEN m.\"linkedRecordId\" END) "
        'WHERE m."id" = $1',
        media_id,
    )
    return sorted({str(row["a"]) for row in rows if row.get("a")})


async def workshop_artisan_ids(workshop_ids: list[str]) -> list[str]:
    ids = sorted({str(w).strip() for w in workshop_ids if str(w or "").strip()})
    if not ids:
        return []
    rows = await db.query_raw(
        'SELECT "id" AS a FROM "Artisan" WHERE "designWorkshopId" = ANY($1::text[])', ids
    )
    return sorted({str(row["a"]) for row in rows if row.get("a")})


async def workshop_verdict(
    workshop_id: str, *, send: Send = DICTATION, record: Any = None
) -> SendVerdict:
    workshop_id = str(workshop_id or "").strip()
    artisan_ids = await workshop_artisan_ids([workshop_id])
    artisans = await artisan_rows(artisan_ids)
    return await _subjects_verdict(
        None, [workshop_id], artisans, send=send, about=workshop_id,
        records={workshop_id: record} if record is not None else None,
    )


UNREADABLE = (
    "The consent record could not be read just now, so nothing was sent. Try again in a minute."
)


async def artisan_rows(artisan_ids: list[str], *, client: Any = None) -> dict[str, dict[str, Any]]:
    return await ledger.current_states(client if client is not None else db, ledger.ARTISAN, artisan_ids)


async def interview_verdict(interview: Any, *, send: Send = DRAFTING) -> SendVerdict:
    interview_id = str(_attr(interview, "id") or "").strip()
    workshop = str(_attr(interview, "designWorkshopId") or "").strip()
    rows = await db.query_raw(
        'SELECT "artisanId" FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = $1', interview_id
    )
    linked = await workshop_artisan_ids([workshop]) if workshop else []
    artisan_ids = sorted({*(str(row["artisanId"]) for row in rows), *linked})
    artisans = await artisan_rows(artisan_ids)
    return await _subjects_verdict(
        None, [workshop] if workshop else [], artisans, send=send, about=interview_id,
    )


async def dictation_verdict(
    workshop_id: str,
    *,
    interview_id: str | None = None,
    artisan_ids: list[str] | None = None,
    record: Any = None,
) -> SendVerdict:
    workshop_id = str(workshop_id or "").strip()
    ids: set[str] = {str(a).strip() for a in (artisan_ids or []) if str(a or "").strip()}
    interview_id = interview_id.strip() if isinstance(interview_id, str) else ""
    if interview_id:
        rows = await db.query_raw(
            'SELECT "artisanId" FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = $1',
            interview_id,
        )
        ids.update(str(row["artisanId"]) for row in rows if row.get("artisanId"))
    ids.update(await workshop_artisan_ids([workshop_id]))
    artisans = await artisan_rows(sorted(ids))
    return await _subjects_verdict(
        None, [workshop_id] if workshop_id else [], artisans, send=DICTATION,
        about=interview_id or workshop_id,
        records={workshop_id: record} if record is not None and workshop_id else None,
    )


def split_ids(raw: str | None) -> list[str]:
    seen: dict[str, None] = {}
    for part in (raw if isinstance(raw, str) else "").split(","):
        cleaned = part.strip()
        if cleaned:
            seen.setdefault(cleaned, None)
    return list(seen)


async def record_artisan_consent(
    artisan_id: str,
    *,
    state: str,
    actor_id: str,
    basis: str = ledger.VERBAL_RECORDED,
    note: str | None = None,
    recorded_at: datetime | None = None,
    client: Any = None,
) -> dict[str, Any]:
    written = await ledger.append(
        client if client is not None else db, subject_type=ledger.ARTISAN, subject_id=artisan_id,
        state=state, basis=basis, actor_id=actor_id, note=note, recorded_at=recorded_at,
    )
    return written or {}


def _consent_token(row: Mapping[str, Any] | None) -> DictationConsent:
    try:
        return DictationConsent(str((row or {}).get("state") or ""))
    except ValueError:
        return DictationConsent.NOT_RECORDED


async def current_row(
    workshop_id: str, record: Any = None, *, client: Any = None
) -> dict[str, Any] | None:
    target = client if client is not None else db
    rows = await ledger.current_states(target, ledger.WORKSHOP, [workshop_id])
    if workshop_id in rows:
        return rows[workshop_id]
    if record is None:
        record = await target.designworkshop.find_unique(where={"id": workshop_id})
    if record is None:
        return None
    cached = consent_of(record)
    if cached is DictationConsent.NOT_RECORDED:
        return None
    return {
        "subjectId": workshop_id,
        "state": cached.value,
        "recordedAt": getattr(record, "dictationConsentAt", None),
        "actorId": getattr(record, "dictationConsentById", None),
    }


async def current_consent(workshop_id: str, record: Any = None) -> DictationConsent:
    return _consent_token(await current_row(workshop_id, record))


async def workshop_audio_media_ids(workshop_id: str) -> list[str]:
    from app.services.design_workshops import entry_rows
    from app.services.workshop_transcripts import audio_references

    found: set[str] = set()
    try:
        found |= set(audio_references(await entry_rows(workshop_id)))
    except Exception as exc:  # noqa: BLE001 — a withdrawal must be recorded even if a walk fails.
        logger.warning(
            "dictation_consent: could not walk workshop %s's stages for its recordings (%s)",
            workshop_id,
            exc,
        )
    try:
        tagged = await db.mediafile.find_many(
            where={"linkedRecordType": MEDIA_TAG, "linkedRecordId": workshop_id}
        )
        found |= {str(row.id) for row in tagged}
    except Exception as exc:  # noqa: BLE001 — same rule.
        logger.warning(
            "dictation_consent: could not read workshop %s's tagged media (%s)", workshop_id, exc
        )
    return sorted(found)


async def cancel_pending_transcriptions(workshop_id: str) -> int:
    media_ids = await workshop_audio_media_ids(workshop_id)
    if not media_ids:
        return 0
    try:
        stopped = await db.mediaprocessingjob.update_many(
            where={
                "mediaFileId": {"in": media_ids},
                "jobType": "TRANSCRIPTION",
                "status": {"in": ["QUEUED", "PROCESSING"]},
            },
            data={
                "status": "FAILED",
                "lockedAt": None,
                "lockedBy": None,
                "completedAt": datetime.now(UTC),
                "error": (
                    "Consent for this workshop's recordings was withdrawn, so this transcription was "
                    "cancelled before it ran. Recording the artisan's answer again as GRANTED, and "
                    "re-saving the stage the recording is attached to, is what queues it afresh."
                ),
            },
        )
    except Exception as exc:  # noqa: BLE001 — see the docstring: the recorded answer comes first.
        logger.warning(
            "dictation_consent: consent for workshop %s was withdrawn but its queued transcriptions "
            "could not be cancelled (%s); the queue will refuse them individually at send time",
            workshop_id,
            exc,
        )
        return 0
    if stopped:
        try:
            await db.mediafile.update_many(
                where={
                    "id": {"in": media_ids},
                    "transcriptStatus": {"in": ["QUEUED", "PROCESSING"]},
                },
                data={
                    "transcriptStatus": "FAILED",
                    "transcriptError": gate_refusal(DictationConsent.REFUSED, MEDIA),
                },
            )
        except Exception as exc:  # noqa: BLE001 — the jobs are already stopped; this is the note.
            logger.warning(
                "dictation_consent: cancelled %s transcription job(s) for workshop %s but could not "
                "mark the recordings (%s)",
                stopped,
                workshop_id,
                exc,
            )
    return stopped


async def workshop_decisions(workshop_id: str) -> list[Any]:
    rows: list[Any] = list(await ledger.history(db, ledger.WORKSHOP, workshop_id))
    copied = {row.get("migratedFrom") for row in rows if row.get("migratedFrom")}
    legacy = await db.dwworkshopconsentdecision.find_many(
        where={"designWorkshopId": workshop_id}, order={"createdAt": "asc"}
    )
    rows += [row for row in legacy if ledger.legacy_key(LEGACY_TABLE, row.id) not in copied]

    def _when(row: Any) -> datetime:
        if isinstance(row, Mapping):
            moment = _moment(row.get("recordedAt")) or _moment(row.get("createdAt"))
        else:
            moment = _as_utc(getattr(row, "recordedAt", None) or getattr(row, "createdAt", None))
        return moment or datetime.min.replace(tzinfo=UTC)

    return sorted(rows, key=_when)


async def actor_name(user_id: str | None) -> str | None:
    if not user_id:
        return None
    row = await db.user.find_unique(where={"id": user_id})
    return getattr(row, "name", None) if row is not None else None
