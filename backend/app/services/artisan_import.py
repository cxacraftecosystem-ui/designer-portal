from __future__ import annotations

import logging
from typing import Any

from fastapi import HTTPException
from pydantic import ValidationError

from app.schemas.common import LocationInput
from app.schemas.records import ArtisanCreate
from app.services import aadhaar_fingerprint, artisan_duplicates, design_workshops, records
from app.services.artisan_identity import mask_aadhaar
from app.services.artisan_xlsx import MAX_ARTISANS, ParsedArtisan, ParsedArtisanList
from app.services.standard_values import StandardValueError, apply_artisan_values
from app.services.xlsx_table import ParseProblem
from prisma import Json

logger = logging.getLogger(__name__)

STAGE_PARTICIPANTS = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"
ENTITY_PARTICIPANT = "participant"

STAGE_CHUNK = 100

IMPORT_SOURCE = "ARTISAN_XLSX_IMPORT"


class ImportOutcome:
    CREATED = "created"
    MATCHED = "matched"
    LINKED = "linked"
    REFUSED = "refused"


async def import_artisans(
    parsed: ParsedArtisanList,
    *,
    workshop: Any,
    actor: Any,
    venue_fix: dict[str, Any],
    source_filename: str | None = None,
) -> dict[str, Any]:
    from app.core.db import db

    try:
        aadhaar_fingerprint.configured_key()
    except aadhaar_fingerprint.FingerprintKeyMissing as exc:
        logger.error("artisan import refused: %s", exc)
        raise HTTPException(
            status_code=503, detail="Aadhaar numbers can't be saved right now. Try again shortly."
        ) from exc

    ledger = await db.dwartisanimport.create(
        data={
            "designWorkshopId": workshop.id,
            "uploadedById": getattr(actor, "id", None),
            "sourceFilename": (source_filename or "")[:255] or None,
            "sheetName": parsed.sheet,
            "problems": Json([]),
        }
    )

    problems: list[ParseProblem] = list(parsed.problems)
    sheet = parsed.sheet or ""

    def refuse(row: int, reason: str, value: str | None = None) -> None:
        problems.append(
            ParseProblem(sheet=sheet, row=row, severity="error", reason=reason, value=value)
        )

    def warn(row: int | None, reason: str, value: str | None = None) -> None:
        problems.append(
            ParseProblem(sheet=sheet, row=row, severity="warning", reason=reason, value=value)
        )

    rows = _drop_in_file_duplicates(parsed.artisans, refuse)

    clashes = await _identity_clashes(rows)

    created = 0
    matched = 0
    linked = 0
    ordered_ids: list[str] = []

    for artisan in rows:
        existing = clashes.get(artisan.aadhaarNumber) or (
            clashes.get(artisan.pehchanCardNumber) if artisan.pehchanCardNumber else None
        )
        if existing is not None:
            _report_disagreements(artisan, existing, warn)
            other = getattr(existing, "designWorkshopId", None)
            if other == workshop.id:
                matched += 1
                warn(
                    artisan.row,
                    f"{artisan.name} ({artisan.place}) is already on this workshop — linked to the "
                    f"existing record, nothing was created.",
                )
            else:
                linked += 1
                where = _filed_under(existing)
                warn(
                    artisan.row,
                    f"{artisan.name} ({artisan.place}) is already recorded{where}. They were added "
                    f"to this workshop's participant list; the existing record was not changed and "
                    f"was not re-filed.",
                )
            ordered_ids.append(existing.id)
            continue

        outcome, artisan_id = await _create_one(
            artisan,
            workshop=workshop,
            actor=actor,
            venue_fix=venue_fix,
            import_id=ledger.id,
            refuse=refuse,
        )
        if outcome == ImportOutcome.CREATED:
            created += 1
            ordered_ids.append(artisan_id or "")
            if artisan_id:
                await _flag_possible_duplicate(artisan_id, artisan, warn)
        elif outcome == ImportOutcome.LINKED:
            linked += 1
            ordered_ids.append(artisan_id or "")

    ordered_ids = [aid for aid in ordered_ids if aid]
    participants = await _write_roster(workshop, ordered_ids, actor=actor, warn=warn)

    refused_rows = {p.row for p in problems if p.severity == "error" and p.row is not None}
    refused = len(refused_rows)
    if (
        created + matched + linked + refused != parsed.rowsRead
    ):  # pragma: no cover - a bug, not a state
        logger.warning(
            "artisan import %s does not balance: read=%s created=%s linked=%s refused=%s",
            ledger.id,
            parsed.rowsRead,
            created,
            matched + linked,
            refused,
        )

    payload = {
        "importId": ledger.id,
        "sheet": parsed.sheet,
        "rowsRead": parsed.rowsRead,
        "artisansCreated": created,
        "artisansLinked": matched + linked,
        "rowsRefused": refused,
        "participantsCreated": participants,
        "problems": [p.payload() for p in problems],
    }

    await db.dwartisanimport.update(
        where={"id": ledger.id},
        data={
            "rowsRead": parsed.rowsRead,
            "artisansCreated": created,
            "artisansLinked": matched + linked,
            "rowsRefused": refused,
            "participantsCreated": participants,
            "problems": Json(payload["problems"]),
        },
    )
    return payload



def _drop_in_file_duplicates(artisans: list[ParsedArtisan], refuse: Any) -> list[ParsedArtisan]:
    seen_aadhaar: dict[str, int] = {}
    seen_pehchan: dict[str, int] = {}
    kept: list[ParsedArtisan] = []
    for artisan in artisans:
        first = seen_aadhaar.get(artisan.aadhaarNumber)
        if first is None and artisan.pehchanCardNumber:
            first = seen_pehchan.get(artisan.pehchanCardNumber)
        if first is not None:
            refuse(
                artisan.row,
                f"{artisan.name} carries the same identity number as row {first} of this sheet, so "
                f"this row was not imported — the same person cannot be recorded twice.",
                mask_aadhaar(artisan.aadhaarNumber),
            )
            continue
        seen_aadhaar[artisan.aadhaarNumber] = artisan.row
        if artisan.pehchanCardNumber:
            seen_pehchan[artisan.pehchanCardNumber] = artisan.row
        kept.append(artisan)
    return kept


async def _identity_clashes(rows: list[ParsedArtisan]) -> dict[str, Any]:
    from app.core.db import db

    aadhaars = sorted({a.aadhaarNumber for a in rows if a.aadhaarNumber})
    pehchans = sorted({a.pehchanCardNumber for a in rows if a.pehchanCardNumber})
    if not aadhaars and not pehchans:
        return {}
    number_of = {aadhaar_fingerprint.fingerprint_of(n): n for n in aadhaars}
    clauses: list[dict[str, Any]] = []
    if aadhaars:
        clauses.append({aadhaar_fingerprint.FINGERPRINT_FIELD: {"in": sorted(number_of)}})
        if await aadhaar_fingerprint.legacy_pending(db):
            clauses.append({"aadhaarNumber": {"in": aadhaars}})
    if pehchans:
        clauses.append({"pehchanCardNumber": {"in": pehchans}})
    found = await db.artisan.find_many(
        where={"OR": clauses}, include={"craft": True, "designWorkshop": True}
    )
    file_numbers = set(aadhaars)
    by_number: dict[str, Any] = {}
    for row in found:
        number = number_of.get(getattr(row, aadhaar_fingerprint.FINGERPRINT_FIELD, None) or "")
        if number:
            by_number[number] = row
        legacy = getattr(row, "aadhaarNumber", None) if aadhaar_fingerprint.LEGACY_COLUMN_PRESENT else None
        if legacy and legacy in file_numbers:
            by_number[legacy] = row
        pehchan = getattr(row, "pehchanCardNumber", None)
        if pehchan:
            by_number[pehchan] = row
    return by_number


def _filed_under(existing: Any) -> str:
    workshop = getattr(existing, "designWorkshop", None)
    title = getattr(workshop, "title", None)
    return f', filed under "{title}"' if title else ""


_COMPARED = (
    ("name", "Artisan name"),
    ("place", "Place"),
    ("phone", "Phone"),
    ("email", "Email"),
)


def _report_disagreements(artisan: ParsedArtisan, existing: Any, warn: Any) -> None:
    for column, label in _COMPARED:
        theirs = str(getattr(existing, column, "") or "").strip()
        ours = str(getattr(artisan, column, "") or "").strip()
        if ours and theirs and ours.casefold() != theirs.casefold():
            warn(
                artisan.row,
                f"{label} differs: this sheet says '{ours}' and the stored record says '{theirs}'. "
                f"The stored record was not changed — edit it in the app if the sheet is right.",
            )



def _location_for(
    artisan: ParsedArtisan, *, workshop: Any, venue_fix: dict[str, Any], import_id: str
) -> LocationInput:
    return LocationInput(
        latitude=float(venue_fix["lat"]),
        longitude=float(venue_fix["lon"]),
        accuracy=(float(venue_fix["accuracy"]) if venue_fix.get("accuracy") is not None else None),
        placeName=str(getattr(workshop, "venue", "") or "") or None,
        state=artisan.state or None,
        district=artisan.district or None,
        village=artisan.village or None,
        pincode=artisan.pincode or None,
        extraMetadata={
            "source": IMPORT_SOURCE,
            "designWorkshopId": workshop.id,
            "importId": import_id,
        },
    )


async def _create_one(
    artisan: ParsedArtisan,
    *,
    workshop: Any,
    actor: Any,
    venue_fix: dict[str, Any],
    import_id: str,
    refuse: Any,
) -> tuple[str, str | None]:
    from app.core.db import db

    try:
        payload = ArtisanCreate(
            name=artisan.name,
            localName=artisan.localName or None,
            gender=artisan.gender or None,
            phone=artisan.phone or None,
            email=artisan.email or None,
            place=artisan.place,
            address=artisan.address or None,
            notes=artisan.notes or None,
            aadhaarNumber=artisan.aadhaarNumber,
            pehchanCardAvailable=artisan.pehchanCardAvailable,
            pehchanCardNumber=artisan.pehchanCardNumber or None,
            dos=artisan.dos,
            donts=artisan.donts,
            craftName=artisan.craftName or None,
            designWorkshopId=workshop.id,
            dateOfBirth=artisan.dateOfBirth or None,
            craftStartDate=artisan.craftStartDate or None,
            experienceYears=artisan.experienceYears,
            experienceMonths=artisan.experienceMonths,
            location=_location_for(
                artisan, workshop=workshop, venue_fix=venue_fix, import_id=import_id
            ),
        )
    except ValidationError as exc:
        reasons = "; ".join(
            str(err.get("msg", "")).removeprefix("Value error, ") for err in exc.errors()
        )
        refuse(artisan.row, f"{artisan.name} could not be recorded: {reasons}")
        return ImportOutcome.REFUSED, None

    data = records.clean_data(payload.model_dump())
    number = aadhaar_fingerprint.seal(data)
    try:
        apply_artisan_values(data)
    except StandardValueError as exc:
        refuse(artisan.row, f"{artisan.name} could not be recorded: {exc.message}")
        return ImportOutcome.REFUSED, None
    try:
        data = await records.resolve_craft_id(data, actor, strict=True)
    except HTTPException as exc:
        refuse(artisan.row, f"{artisan.name}: {exc.detail}")
        return ImportOutcome.REFUSED, None

    data = await records.attach_location(data)
    data["createdById"] = getattr(actor, "id", None)
    records.merge_field_provenance(data, actor, previous=None)
    records.apply_status_policy_create(actor, data)

    try:
        created = await db.artisan.create(data=data)
    except Exception as exc:
        if not _is_identity_violation(exc):
            raise
        logger.warning(
            "artisan import row %s lost the unique race on an identity column; linking instead",
            artisan.row,
        )
        winner = await db.artisan.find_first(
            where=aadhaar_fingerprint.match_where(
                number or artisan.aadhaarNumber,
                legacy=await aadhaar_fingerprint.legacy_pending(db),
            )
        )
        if winner is None:
            refuse(
                artisan.row,
                f"{artisan.name} was recorded by somebody else at the same moment and could not "
                f"then be found. Nothing was created for this row; upload it again.",
            )
            return ImportOutcome.REFUSED, None
        return ImportOutcome.LINKED, winner.id
    return ImportOutcome.CREATED, created.id


async def _flag_possible_duplicate(artisan_id: str, artisan: ParsedArtisan, warn: Any) -> None:
    try:
        profile = await artisan_duplicates.load_profile(artisan_id)
        if profile is None:
            return
        found = await artisan_duplicates.candidates_for_profile(profile, exclude_id=artisan_id)
        await artisan_duplicates.store_candidates(artisan_id, found, source="IMPORT")
    except Exception:
        logger.exception("duplicate scoring failed on an imported artisan")
        return
    likely = [(o, r) for o, r in found if r.score >= artisan_duplicates.HOLD_THRESHOLD]
    if likely:
        other = likely[0][0]
        warn(
            artisan.row,
            f"{artisan.name} ({artisan.place}) may be {other.display.get('name')} "
            f"({other.display.get('place')}), already recorded. Review it under Duplicates.",
        )


_IDENTITY_COLUMNS = (aadhaar_fingerprint.FINGERPRINT_FIELD, "aadhaarNumber", "pehchanCardNumber")


def _is_identity_violation(error: Exception) -> bool:
    text = str(error)
    if "unique" not in text.lower():
        return False
    return any(column in text for column in _IDENTITY_COLUMNS)



async def _write_roster(workshop: Any, artisan_ids: list[str], *, actor: Any, warn: Any) -> int:
    if not artisan_ids:
        return 0

    from app.core.db import db
    from app.schemas.design_workshops import StageEntryIn, StageSaveIn
    from app.services.stage_schema import stages

    spec = next((s for s in stages() if s.key == STAGE_PARTICIPANTS), None)
    if spec is None:  # pragma: no cover - the registry disagreeing with itself
        warn(None, "The workshop's participant stage couldn't be found, so participants weren't added.")
        return 0

    existing = await db.dwstageentry.find_many(
        where={
            "designWorkshopId": workshop.id,
            "stageKey": STAGE_PARTICIPANTS,
            "entityKey": ENTITY_PARTICIPANT,
            "deletedAt": None,
        }
    )
    next_ordinal = max((row.ordinal or 0) for row in existing) + 1 if existing else 1

    on_the_roster = {
        ref
        for row in existing
        if isinstance(getattr(row, "data", None), dict)
        and isinstance(ref := row.data.get("artisanRef"), str)
        and ref
    }
    artisan_ids = [aid for aid in artisan_ids if aid not in on_the_roster]
    if not artisan_ids:
        return 0

    written = 0
    for start in range(0, len(artisan_ids), STAGE_CHUNK):
        chunk = artisan_ids[start : start + STAGE_CHUNK]
        entries = [
            StageEntryIn(
                entityKey=ENTITY_PARTICIPANT,
                ordinal=ordinal,
                data={"artisanRef": artisan_id, "serialNo": ordinal},
            )
            for ordinal, artisan_id in enumerate(chunk, start=next_ordinal + start)
        ]
        result = await design_workshops.save_stage(
            workshop.id,
            spec,
            StageSaveIn(
                entries=entries,
                replaceCollections=False,
                emptiedEntities=[],
                submit=False,
            ),
            actor,
        )
        by_entity = result.get("createdByEntity")
        created = by_entity.get(ENTITY_PARTICIPANT, 0) if isinstance(by_entity, dict) else result.get("created", 0)
        written += int(created or 0)
        if result.get("errors"):
            warn(
                None,
                "Some participant rows could not be filled in completely. The artisan records were "
                "created; open the workshop's Participating artisans stage to finish them.",
            )
    return written


from app.schemas.design_workshops import MAX_STAGE_ROWS as _MAX_STAGE_ROWS  # noqa: E402

if not (STAGE_CHUNK <= _MAX_STAGE_ROWS and MAX_ARTISANS <= _MAX_STAGE_ROWS):  # pragma: no cover
    raise RuntimeError(
        f"artisan_import.STAGE_CHUNK ({STAGE_CHUNK}) and artisan_xlsx.MAX_ARTISANS "
        f"({MAX_ARTISANS}) must both stay at or under schemas.design_workshops.MAX_STAGE_ROWS "
        f"({_MAX_STAGE_ROWS}); a StageSaveIn built in process that exceeds it is a 500 raised "
        f"after every artisan is already written."
    )
