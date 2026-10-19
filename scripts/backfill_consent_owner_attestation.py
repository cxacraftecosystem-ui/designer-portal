"""Record consent for every design workshop that already holds recordings (owner attestation).

THE OWNER'S WORDS, 18 OCT 2026: consent exists for all existing recordings, including those still
waiting to be transcribed; backfill consent records for them, attested by the owner on 18 Oct 2026,
source 'owner attestation', and do not flag them.

WHAT IT WRITES, PER WORKSHOP WHOSE ANSWER IS NOT GRANTED AND THAT HOLDS AT LEAST ONE RECORDING:

* ``DesignWorkshop.dictationConsent = GRANTED``, ``dictationConsentAt = 2026-10-18``,
  ``dictationConsentById = <the owner's account>``;
* one ``Consent`` ledger row (``WORKSHOP``, GRANTED, basis ``OWNER_ATTESTATION``, the attestation
  note, ``source = 'owner attestation'``, ``recordedAt = 2026-10-18``) — the one consent store both
  portals share — built by ``dictation_consent.decision_plans`` so the rule has one implementation,
  and written with the cached columns above in one transaction;
* on that workshop's recordings, the consent-refusal note the gate wrote (``transcriptStatus``
  FAILED with the refusal sentence and no transcript) is cleared, so nothing reads as flagged.

EXPLICIT REFUSALS ARE INCLUDED BY DEFAULT: the owner attests consent for ALL existing recordings, so a
workshop whose answer is REFUSED is granted too, and the dry run lists each one separately so the owner
sees exactly which recorded refusals the attestation replaces. ``--keep-refused`` leaves them alone.

WHAT IT NEVER DOES: create a transcription job or call a provider. Recordings stay exactly as they
are; the queue is paused anyway.

IDEMPOTENT: a second run finds nothing to do, because every workshop it wrote is now GRANTED.

USAGE (from the repository root; ``DATABASE_URL`` comes from ``backend/.env``):

    python scripts/backfill_consent_owner_attestation.py                       # DRY RUN, writes nothing
    python scripts/backfill_consent_owner_attestation.py --fix --owner-email owner@example.org

On Windows, run with UTF-8 mode on (``set PYTHONUTF8=1``), as for the other backfills here.
"""

from __future__ import annotations

import argparse
import asyncio
import pathlib
import sys
from dataclasses import dataclass, field, replace
from datetime import UTC, datetime
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "backend"))

from app.core.db import connect_db, db, disconnect_db  # noqa: E402
from app.services import dictation_consent  # noqa: E402
from app.transcription import ledger  # noqa: E402
from app.transcription.consent import OWNER_ATTESTATION_NOTE, OWNER_ATTESTATION_SOURCE  # noqa: E402

#: 18 Oct 2026, midday India time, so the date reads as the 18th in every timezone a screen uses.
ATTESTED_AT = datetime(2026, 10, 18, 6, 30, tzinfo=UTC)


@dataclass
class Plan:
    workshops: list[tuple[str, str, int]] = field(default_factory=list)  # (id, title, recordings)
    refused: list[tuple[str, str, int]] = field(default_factory=list)
    kept_refused: bool = False  # True when --keep-refused left the REFUSED ones out
    refusal_notes: list[str] = field(
        default_factory=list
    )  # media ids whose consent note is cleared


def _refusal_sentences() -> set[str]:
    """The refusal note, and the "nobody has recorded yet" note the gate wrote before consent was
    assumed by default (19 Oct 2026), which is still stored on older recordings."""
    return {
        dictation_consent.legacy_waiting_sentence(dictation_consent.MEDIA),
        str(
            dictation_consent.gate_refusal(
                dictation_consent.DictationConsent.REFUSED, dictation_consent.MEDIA
            )
        ),
    }


def is_consent_note(media: Any, sentences: set[str]) -> bool:
    """True for a clip carrying only the gate's refusal note: FAILED, the sentence, no transcript."""
    return (
        str(getattr(media, "transcriptStatus", "") or "").upper() == "FAILED"
        and str(getattr(media, "transcriptError", "") or "") in sentences
        and not str(getattr(media, "transcriptText", "") or "").strip()
    )


async def build_plan(*, keep_refused: bool = False) -> Plan:
    plan = Plan(kept_refused=keep_refused)
    sentences = _refusal_sentences()
    for workshop in await db.designworkshop.find_many(order={"createdAt": "asc"}):
        consent = await dictation_consent.current_consent(workshop.id, workshop)
        if consent is dictation_consent.DictationConsent.GRANTED:
            continue
        media_ids = set(await dictation_consent.workshop_audio_media_ids(workshop.id))
        for row in await db.mediafile.find_many(
            where={"designWorkshopId": workshop.id, "mediaType": "AUDIO"}
        ):
            media_ids.add(row.id)
        if not media_ids:
            continue
        entry = (workshop.id, str(getattr(workshop, "title", "") or workshop.id), len(media_ids))
        if consent is dictation_consent.DictationConsent.REFUSED:
            plan.refused.append(entry)
            if keep_refused:
                continue
        plan.workshops.append(entry)
        for media in await db.mediafile.find_many(where={"id": {"in": sorted(media_ids)}}):
            if is_consent_note(media, sentences):
                plan.refusal_notes.append(media.id)
    return plan


async def apply(plan: Plan, owner_id: str) -> None:
    for workshop_id, _title, _count in plan.workshops:
        plans = dictation_consent.decision_plans(
            workshop_id=workshop_id,
            decision=dictation_consent.DictationConsent.GRANTED,
            actor_id=owner_id,
            at=datetime.now(UTC),
            recorded_at=ATTESTED_AT,
            note=OWNER_ATTESTATION_NOTE,
        )
        decision = replace(
            plans.decision,
            data={
                **plans.decision.data,
                "source": OWNER_ATTESTATION_SOURCE,
                "basis": ledger.OWNER_ATTESTATION,
            },
        )
        await dictation_consent.apply_decision(replace(plans, decision=decision))
    if plan.refusal_notes:
        await db.mediafile.update_many(
            where={"id": {"in": plan.refusal_notes}},
            data={"transcriptStatus": None, "transcriptError": None},
        )


def report(plan: Plan, *, fix: bool) -> None:
    verb = "Recorded" if fix else "Would record"
    print(f"{verb} consent (owner attestation, 18 Oct 2026) for {len(plan.workshops)} workshop(s):")
    for workshop_id, title, count in plan.workshops:
        print(f"  {workshop_id}  {title}  ({count} recording(s))")
    print(
        f"{'Cleared' if fix else 'Would clear'} the consent note on {len(plan.refusal_notes)} recording(s)."
    )
    if plan.refused:
        print(
            f"Left alone: {len(plan.refused)} workshop(s) whose answer is REFUSED (--keep-refused):"
            if plan.kept_refused
            else f"Of these, {len(plan.refused)} replace a recorded REFUSED answer:"
        )
        for workshop_id, title, count in plan.refused:
            print(f"  {workshop_id}  {title}  ({count} recording(s))")
    print("No transcription job was created and no provider was called.")


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--fix", "--execute", action="store_true", help="apply (default: dry run)")
    parser.add_argument(
        "--owner-email", help="the owner's account, recorded as the attesting person"
    )
    parser.add_argument(
        "--keep-refused", action="store_true", help="leave explicit REFUSED answers alone"
    )
    args = parser.parse_args()
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(errors="replace")
    await connect_db()
    try:
        owner_id = None
        if args.fix:
            if not args.owner_email:
                print("--fix needs --owner-email: the row must name who attested.", file=sys.stderr)
                return 2
            owner = await db.user.find_first(where={"email": args.owner_email.strip().lower()})
            if owner is None:
                print(f"No account for {args.owner_email}.", file=sys.stderr)
                return 2
            owner_id = owner.id
        plan = await build_plan(keep_refused=args.keep_refused)
        if args.fix and owner_id:
            await apply(plan, owner_id)
        report(plan, fix=args.fix)
    finally:
        await disconnect_db()
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
