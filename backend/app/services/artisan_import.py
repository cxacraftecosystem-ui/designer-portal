"""Turning a parsed artisan list into artisan records and a workshop's participant roster.

``artisan_xlsx`` answers "what does this spreadsheet say". This module answers "what should the
repository do about it", and the two are kept apart because the second one needs a database and the
first one must not.

=======================================================================================
THE FOUR OUTCOMES PER ROW, AND THERE IS NO FIFTH
=======================================================================================

+-------------+------------------------------------------+-------------------------------------+
| **created** | no stored artisan holds either number    | ``Artisan`` + ``Location`` + a      |
|             |                                          | participant row                     |
+-------------+------------------------------------------+-------------------------------------+
| **matched** | a stored artisan holds one of the        | **no artisan write at all**; the    |
|             | numbers AND is already filed under THIS  | participant row is ensured          |
|             | workshop                                 |                                     |
+-------------+------------------------------------------+-------------------------------------+
| **linked**  | a stored artisan holds one of the        | **no artisan write at all, no field |
|             | numbers and is filed elsewhere or        | changed, ``designWorkshopId`` left  |
|             | nowhere                                  | alone**; a participant row created  |
+-------------+------------------------------------------+-------------------------------------+
| **refused** | a bad identity number, a missing         | nothing                             |
|             | required column, an unknown craft, or an |                                     |
|             | in-file duplicate                        |                                     |
+-------------+------------------------------------------+-------------------------------------+

**NEVER SILENTLY DUPLICATE AND NEVER SILENTLY OVERWRITE** is the whole contract, and the two halves
have different enforcement. Duplication is stopped by the batched identity query below plus the
unique indexes behind it. Overwriting is stopped by there being **NO UPDATE PATH IN THIS MODULE AT
ALL**: where the spreadsheet disagrees with a stored record on any field, the stored value stands
and the disagreement is reported as a warning naming the column and both values — except the two
identity columns, where only the masked form is printed.

*Why no update path.* ``backend/scripts/merge_artisans.py`` is what correcting a wrong artisan merge
costs: a dry-run script with hand-picked cuids, per-table re-pointing and collision handling for
every composite-key join. An importer that overwrote would make that damage routine and
unattributed. Adding an update path later needs the ``expectedUpdatedAt`` precondition, a rule for
dropping masked numbers, and a merge story — it is not a flag on this function.

**WHY A LINKED ARTISAN IS NOT RE-FILED.** Setting ``designWorkshopId`` on somebody else's artisan
moves that record out of THEIR workshop's scoped lists and totals, which is exactly what
``record_design_workshop.assert_payload_workshop`` exists to prevent. And it is not needed:
``participant.artisanRef`` is declared ``ALL_SCOPE`` and is the one artisan field in the stage
registry that is NOT scoped to the workshop — "this is where the roster is built". The participant
row IS the link the workshop needs.

=======================================================================================
IN-FILE DUPLICATES ARE AN ``error``, AND THE SIBLING PARSER'S RULE IS DELIBERATELY NOT FOLLOWED
=======================================================================================

``questionnaire_xlsx._read_questions`` reports a duplicate Question ID as a *warning* and imports
the row as a new question. That rule is right there and wrong here, and an unexplained departure
from a sibling reads as an oversight, so: ``Artisan.aadhaarNumber`` is ``@unique``, so importing the
second row would take the unique index MID-BATCH — a driver error surfacing as a bare 500 after half
the file had already been written, with the half-written artisans staying. The FIRST occurrence is
imported; every later one is an ``error`` naming the EARLIER Excel row number.

=======================================================================================
NOT ONE TRANSACTION, AND THAT IS THE DESIGN RATHER THAN AN OMISSION
=======================================================================================

Each artisan create is its own write — matching ``create_artisan``, which is not transactional with
its workshop link either — and then ONE stage save carries every participant row.

The never-drop-a-row contract makes partial success the DESIGNED outcome. A fifteen-row batch inside
one transaction that trips on row fourteen rolls back thirteen artisans the officer is looking at in
the report. The report — and the ``DwArtisanImport`` row behind it — is what makes partial success
legible, which is why the ledger row is written FIRST and updated at the end rather than written at
the end: a failure halfway leaves a ledger row saying what had happened by then.

=======================================================================================
WHAT THIS MODULE MUST NEVER DO
=======================================================================================

* **Write ``DwStageEntry`` directly.** The roster goes through ``design_workshops.save_stage``, and
  ``tests/test_design_workshop_search_text.py`` fails on a third writer of that table — by exact set
  equality, in both directions. If that test goes red because of this file, THIS FILE IS WRONG: a
  row written without ``searchText`` is invisible to ``GET /search`` for the workshop's whole life,
  because NULL there means "not computed yet" and can never match. It would also lose reference
  hydration (the ~30 fields that ARE "auto-populate the workflow"), the version guard, and
  provenance attribution to the artisan's original recorder.
* **Pass ``replaceCollections=True``.** It would sweep every participant row the designer added by
  hand, and report a successful import while doing it.
* **Write ``WorkshopArtisan``.** That is the join to the FIELD ``Workshop`` model, not to
  ``DesignWorkshop``. Writing it scopes the artisan to a field workshop that does not exist and the
  failure is a silent 404 indistinguishable from "no such workshop". Do not call
  ``workshop_access.link_workshop_artisan`` from here.
* **Echo a full Aadhaar.** Into a problem, into the ledger, into a log line, or into stage data.
  ``participant.aadhaarNumber`` declares ``store_masked``, so going through ``save_stage`` is what
  masks it; writing the raw number into stage data by hand would bypass that.
* **Create a craft.** ``resolve_craft_id`` is called with ``allow_create=False``. Its create arm is
  gated on ``can_manage_crafts`` — PROFESSOR and above — and every officer tier clears that floor,
  so a typo'd craft name in one cell would otherwise MINT a row in the controlled vocabulary and
  report it as a success.

=======================================================================================
THE REVIEW STATUS, MEASURED RATHER THAN ASSUMED — AND THE ASSUMPTION WAS WRONG
=======================================================================================

Every officer tier outranks PROFESSOR, and ``records.apply_status_policy_create`` reads "Professor
and above default to APPROVED". The obvious conclusion — that fifteen never-reviewed artisans enter
the repository already approved, with the review queue showing nothing — was written into this
workstream's plan as a risk to flag to the owner. **IT IS NOT WHAT HAPPENS, and the reason is one
word in that function.**

``apply_status_policy_create`` does ``data.setdefault("status", "APPROVED")`` on the senior branch,
and ``ArtisanCreate.status`` is declared ``str = "PENDING"`` — a real default, not ``None``, so it
survives ``clean_data`` and is already in the dict by the time the policy runs. ``setdefault`` finds
the key present and does nothing. The senior branch is therefore reachable only from a caller that
omits the key entirely, and this importer does not omit it because it goes through the same model
the artisan form does.

**So an imported artisan is created PENDING and DOES enter the review queue** — which is the
outcome anybody would have chosen, arrived at by an accident of two defaults rather than by a
decision. ``tests/test_artisan_import.py`` pins it in both directions: the imported row is PENDING,
AND the policy still answers APPROVED for a senior caller who omits the key, so the test fails if
either half changes. Do not "fix" the schema default without reading this paragraph: dropping it
would silently approve every artisan a professor or an officer has ever created through this path.
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import HTTPException
from pydantic import ValidationError

from app.schemas.common import LocationInput
from app.schemas.records import ArtisanCreate
from app.services import design_workshops, records
from app.services.artisan_identity import mask_aadhaar
from app.services.artisan_xlsx import MAX_ARTISANS, ParsedArtisan, ParsedArtisanList
from app.services.xlsx_table import ParseProblem
from prisma import Json

logger = logging.getLogger(__name__)

#: The stage and entity the roster is written into.
STAGE_PARTICIPANTS = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"
ENTITY_PARTICIPANT = "participant"

#: How many participant entries travel in ONE ``StageSaveIn``.
#:
#: HALF :data:`artisan_xlsx.MAX_ARTISANS`, so a full file is two saves rather than one, and well
#: under ``schemas/design_workshops.MAX_STAGE_ROWS``. The relationship between the two numbers is
#: what matters and it is asserted at import at the foot of this module: a ``StageSaveIn`` built IN
#: PROCESS that exceeds that bound raises a ``pydantic.ValidationError`` rather than a 422, which
#: becomes a bare 500 AFTER every artisan is already committed.
STAGE_CHUNK = 100

#: What ``Location.extraMetadata`` records about where the coordinate came from.
#:
#: **NONE OF THESE THREE KEYS MAY BE NAMED ``district``, ``state``, ``village`` OR ``pincode``.**
#: ``schemas/common._stated_district`` reads ``extraMetadata["district"]`` as a FALLBACK for the
#: stated district — the Android client keeps the stated address in there because it had nowhere
#: else to put it before the columns existed — so a key by that name here would silently become a
#: district nobody typed, and ``require_location`` would pass on it.
IMPORT_SOURCE = "ARTISAN_XLSX_IMPORT"


class ImportOutcome:
    """The four row outcomes, as strings the report and the tests both name."""

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
    """Write everything this list implies, and answer with a report of what happened to each row.

    ``venue_fix`` is the workshop's stage-1 ``venueLocation``, ``{"lat", "lon", "accuracy"?}``. The
    route refuses the upload before parsing when there is none — see :func:`_location_for` for the
    whole argument, which is the single most important decision in this feature.
    """
    from app.core.db import db

    # ── THE LEDGER ROW IS WRITTEN FIRST, WITH ZEROS. ────────────────────────────────────────────
    # It is the FIRST write this function makes, so a deployment whose migration has not run fails
    # here — the upload 500s having changed nothing — rather than after fifteen artisans exist and
    # there is nowhere to record that they do. Its id is also what every Location's provenance
    # points at, which is the other reason it cannot be written at the end.
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
        elif outcome == ImportOutcome.LINKED:
            linked += 1
            ordered_ids.append(artisan_id or "")
        # A refused row leaves an `error` problem behind and is counted off that list below.

    ordered_ids = [aid for aid in ordered_ids if aid]
    participants = await _write_roster(workshop, ordered_ids, actor=actor, warn=warn)

    # ── ``rowsRefused`` IS COUNTED OFF THE PROBLEM LIST AND NOT OFF THIS LOOP, SO THE ARITHMETIC
    # ADDS UP ──────────────────────────────────────────────────────────────────────────────────
    #
    # The loop above sees only the rows the PARSER handed over. A row it refused outright — no name,
    # no Do's, a mistyped identity number, past the ceiling — never reaches it, so a counter
    # incremented here would report "15 rows read, 13 created, 1 refused" and leave one row
    # unaccounted for. A report whose numbers do not add up is a report somebody has to reconcile by
    # hand against the spreadsheet, which is the whole thing this feature exists to end.
    #
    # DISTINCT ROWS, because one row can carry more than one error (a bad district AND a bad state),
    # and ``row=None`` problems are about the SHEET rather than about a row. The balance check
    # below is the control: created + linked + refused must equal the rows that were read.
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
            # THE SAME LIST THE RESPONSE CARRIES, AND NOTHING RE-MASKS IT HERE. Every ``value`` in
            # it was masked by the parser or by this module at the one place the number was read. A
            # mask applied in two places is a mask that can be forgotten in one.
            "problems": Json(payload["problems"]),
        },
    )
    return payload


# --------------------------------------------------------------------------------------
# Identity
# --------------------------------------------------------------------------------------


def _drop_in_file_duplicates(artisans: list[ParsedArtisan], refuse: Any) -> list[ParsedArtisan]:
    """The first occurrence of each number survives; every later one is refused BY ROW NUMBER.

    Naming the EARLIER row is the whole value of the message: "duplicate Aadhaar" sends somebody
    scrolling, and "the same number as row 7" is one keypress away from the fix. See this module's
    header for why this is an error rather than the warning the sibling parser gives.
    """
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
    """Every stored artisan holding one of this file's numbers, keyed by BOTH numbers.

    **ONE QUERY FOR THE WHOLE FILE**, and both columns in it. Both-in-one is
    ``artisans._guard_identity_conflicts``' own rule, which records that asking them separately cost
    three sequential round trips per artisan. Batching over the FILE rather than per row is this
    importer's addition: fifteen rows × two probes × a lookup is forty-five round trips for a
    question one ``IN`` answers.

    ``include={"craft": True, "workshop": True}`` because the warnings below print the craft name
    and the workshop an existing artisan is filed under, and a second lookup per clash to learn
    something the first query could have carried is the defect the paragraph above is about.
    """
    from app.core.db import db

    aadhaars = sorted({a.aadhaarNumber for a in rows if a.aadhaarNumber})
    pehchans = sorted({a.pehchanCardNumber for a in rows if a.pehchanCardNumber})
    if not aadhaars and not pehchans:
        return {}
    clauses: list[dict[str, Any]] = []
    if aadhaars:
        clauses.append({"aadhaarNumber": {"in": aadhaars}})
    if pehchans:
        clauses.append({"pehchanCardNumber": {"in": pehchans}})
    found = await db.artisan.find_many(
        where={"OR": clauses}, include={"craft": True, "designWorkshop": True}
    )
    by_number: dict[str, Any] = {}
    for row in found:
        for column in ("aadhaarNumber", "pehchanCardNumber"):
            value = getattr(row, column, None)
            if value:
                by_number[value] = row
    return by_number


def _filed_under(existing: Any) -> str:
    workshop = getattr(existing, "designWorkshop", None)
    title = getattr(workshop, "title", None)
    return f', filed under "{title}"' if title else ""


#: Columns whose disagreement is worth telling somebody about.
#:
#: DELIBERATELY NOT EVERY COLUMN. A stored artisan has a location, provenance metadata, a review
#: status and a dozen derived values, and reporting that the spreadsheet "disagrees" with any of
#: those would bury the four that mean something under noise nobody reads to the bottom of.
_COMPARED = (
    ("name", "Artisan name"),
    ("place", "Place"),
    ("phone", "Phone"),
    ("email", "Email"),
)


def _report_disagreements(artisan: ParsedArtisan, existing: Any, warn: Any) -> None:
    """Say where the sheet and the stored record differ. **THE STORED VALUE ALWAYS STANDS.**

    THE IDENTITY COLUMNS ARE NOT COMPARED HERE AT ALL, and that is not an omission: they are what
    MATCHED the two records, so by construction one of them is equal — and printing the other one's
    disagreement would mean printing two regulated numbers side by side in a diagnostic.
    """
    for column, label in _COMPARED:
        theirs = str(getattr(existing, column, "") or "").strip()
        ours = str(getattr(artisan, column, "") or "").strip()
        if ours and theirs and ours.casefold() != theirs.casefold():
            warn(
                artisan.row,
                f"{label} differs: this sheet says '{ours}' and the stored record says '{theirs}'. "
                f"The stored record was not changed — edit it in the app if the sheet is right.",
            )


# --------------------------------------------------------------------------------------
# Creating one artisan
# --------------------------------------------------------------------------------------


def _location_for(
    artisan: ParsedArtisan, *, workshop: Any, venue_fix: dict[str, Any], import_id: str
) -> LocationInput:
    """The ``Location`` an imported artisan gets, and the six rules that decide what goes in it.

    **1. THE STATED ADDRESS COMES FROM THE ROW.** Columns 13-16, validated by the parser against the
    served closed lists, with the workshop's own state and district as the per-row default and a
    warning saying so. That satisfies ``require_location``'s second clause honestly.

    **2. THE COORDINATE COMES FROM THE WORKSHOP, NEVER FROM THE ROW AND NEVER INVENTED.** A
    spreadsheet row has no GPS fix, and ``LocationInput.latitude``/``.longitude`` are non-optional
    floats. The workshop's stage-1 ``venueLocation`` is used, with ``placeName = workshop.venue``.

    **3. IF THE WORKSHOP HAS NO VENUE LOCATION, THE WHOLE UPLOAD IS REFUSED BEFORE PARSING**, by the
    route. **This is the single most important decision in this feature and the ``Location`` model
    argues it already**: the finding recorded there is *"fifteen artisans documented in Rajasthan,
    Gujarat, Uttarakhand and Andhra Pradesh all carry Kharagpur coordinates, because the only place
    the schema offered for 'where the artisan is' was the field that means 'where the device is'"*.
    Defaulting the fix to (0,0), to the office, or to a state centroid would recreate that defect
    fifteen rows at a time, with nothing on screen saying so and no way afterwards to tell an
    imported coordinate from a captured one.

    **4. ``subjectLatitude``/``subjectLongitude`` STAY NULL.** The venue fix is PROVENANCE — where
    the record was made, which is honestly what it is: an office keyed the list from the venue.
    Writing it into the subject pin would be a CLAIM ABOUT WHERE THE ARTISAN LIVES, which is the
    exact conflation the split columns exist to end. Both columns are floats and nothing would
    refuse it, which is why this paragraph is here.

    **5. ``extraMetadata`` RECORDS WHERE THE COORDINATE CAME FROM.** It is the only way a later
    reader can tell fifteen artisans sharing one fix from fifteen researchers who happened to stand
    in one place. None of the three keys collides with ``_stated_district``'s read — see
    :data:`IMPORT_SOURCE`.

    **6. THE ROW'S ADDRESS NEVER REACHES ``Location.address``.** Column 12 is ``Artisan.address``,
    the artisan's postal address; ``Location.address`` is the reverse-geocoded string for the
    DEVICE's fix and is left NULL. Two columns, two meanings; merging them is the same class of
    error as (4).
    """
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
    """One artisan, through the SAME sequence ``create_artisan`` uses, minus three steps.

    The sequence is ``ArtisanCreate`` → ``clean_data`` → ``resolve_craft_id`` → ``attach_location``
    → ``merge_field_provenance`` → ``apply_status_policy_create`` → ``db.artisan.create``.

    **THREE STEPS ARE DELIBERATELY OMITTED** — ``enforce_workshop_submission``,
    ``stamp_workshop_submission`` and ``pin_pending_if_late``. All three are the FIELD ``Workshop``
    window rules and read ``data["workshopId"]``, which this path never sets: the artisan is filed
    under a DESIGN workshop through ``designWorkshopId``, which is a different column with different
    machinery. Calling them would be a no-op today and a trap tomorrow.

    ``designWorkshopId`` IS SET AND ``WorkshopArtisan`` IS NOT WRITTEN. See this module's header.
    """
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
        # ONE SENTENCE PER FAILED RULE, and the ROW is refused rather than the FILE. Pydantic's own
        # ``loc`` is a tuple a person cannot read, so only the message is printed — every validator
        # on this model was written to be shown to somebody filling a form.
        reasons = "; ".join(
            str(err.get("msg", "")).removeprefix("Value error, ") for err in exc.errors()
        )
        refuse(artisan.row, f"{artisan.name} could not be recorded: {reasons}")
        return ImportOutcome.REFUSED, None

    data = records.clean_data(payload.model_dump())
    try:
        # ``allow_create=False`` — see this module's header. A typo'd craft name is a refused ROW,
        # never a new entry in the controlled vocabulary.
        data = await records.resolve_craft_id(data, actor, allow_create=False)
    except HTTPException as exc:
        refuse(artisan.row, f"{artisan.name}: {exc.detail}")
        return ImportOutcome.REFUSED, None

    data = await records.attach_location(data)
    data["createdById"] = getattr(actor, "id", None)
    records.merge_field_provenance(data, actor, previous=None)
    # CALLED FOR THE SAME REASON ``create_artisan`` CALLS IT, AND IT IS A NO-OP HERE. See this
    # module's header: ``ArtisanCreate.status`` defaults to PENDING, so the ``setdefault`` on the
    # senior branch finds the key already present. It is kept rather than dropped because the day
    # that default changes this line is what decides the answer, and a write path that skips the
    # status policy is a write path nobody thinks to check.
    records.apply_status_policy_create(actor, data)

    try:
        created = await db.artisan.create(data=data)
    except Exception as exc:
        if not _is_identity_violation(exc):
            raise
        # LOST THE RACE against a concurrent submission of the same person. **NOT a 409 here**: this
        # is a BATCH, and a fifteen-row upload must not be lost to one contested row. The row
        # becomes `linked`, which is the same answer the pre-flight would have given a second
        # earlier. The re-read is by the number rather than by id because the id is exactly what
        # this process does not have.
        logger.warning(
            "artisan import row %s lost the unique race on an identity column; linking instead",
            artisan.row,
        )
        winner = await db.artisan.find_first(where={"aadhaarNumber": artisan.aadhaarNumber})
        if winner is None:
            refuse(
                artisan.row,
                f"{artisan.name} was recorded by somebody else at the same moment and could not "
                f"then be found. Nothing was created for this row; upload it again.",
            )
            return ImportOutcome.REFUSED, None
        return ImportOutcome.LINKED, winner.id
    return ImportOutcome.CREATED, created.id


#: The two unique identity columns, by the name a Prisma error spells them with.
#:
#: SPELLED HERE RATHER THAN IMPORTED FROM ``api/routes/artisans``, where the same pair lives as
#: ``_IDENTITY_CONSTRAINTS``. A service reaching into a ROUTE module for a private name is the
#: dependency direction this codebase refuses, and the alternative — promoting that constant —
#: would move a message table this module has no use for. Two column names, one line.
_IDENTITY_COLUMNS = ("aadhaarNumber", "pehchanCardNumber")


def _is_identity_violation(error: Exception) -> bool:
    """Was this a unique-constraint failure on one of the identity columns?

    TEXT MATCHING, which is what ``artisans._violated_identity_field`` does and for the same reason:
    prisma-client-py surfaces a ``UniqueViolationError`` whose useful content is in its message, and
    a race on a DIFFERENT column must re-raise rather than be silently turned into a link.
    """
    text = str(error)
    if "unique" not in text.lower():
        return False
    return any(column in text for column in _IDENTITY_COLUMNS)


# --------------------------------------------------------------------------------------
# The roster
# --------------------------------------------------------------------------------------


async def _write_roster(workshop: Any, artisan_ids: list[str], *, actor: Any, warn: Any) -> int:
    """Put every imported artisan into stage 3's participant table, THROUGH ``save_stage``.

    **THIS IS WHAT "AUTO-POPULATE THE WORKFLOW" MEANS IN THIS CODEBASE**, and it is why a direct
    ``db.dwstageentry.create`` is refused four times over:

    1. **Reference hydration.** ``hydrate_entries`` copies the ~30 fields of
       ``REFERENCE_HYDRATION["participant.artisanRef"]`` onto each row — name, local name,
       specialisation, craft start date, experience, age, gender, phone, email, card number, the
       MASKED Aadhaar, village, state, district, pincode, address, notes, do's, don'ts, photograph.
       Fresh rows are blank, so only-fill-blanks fills everything.
    2. **``searchText``.** NULLABLE with no default, and NULL means "not computed yet" and can never
       match. A row written without it is invisible to ``GET /search`` for the workshop's life.
    3. **The version guard.** Two officers uploading the same list concurrently collide and the
       loser re-plans instead of clobbering.
    4. **Provenance.** Each hydrated field is attributed to the CANONICAL RECORD'S recorder rather
       than to the officer who uploaded the sheet — "the only moment at which a hydrated value is
       distinguishable from a typed one".

    ``replaceCollections=False`` — **NEVER True.** It would sweep every participant row the designer
    added by hand and report a successful import while doing it.

    ``submit=False`` — a required-field 422 raised by an import is a stage nobody can fix from the
    screen that caused it.

    ``ordinal`` CONTINUES FROM WHAT THE STAGE ALREADY HOLDS, so a second upload adds to the roster
    rather than colliding with it, and ``serialNo`` mirrors it so the printed roster numbers from 1.

    **AN ARTISAN ALREADY ON THE ROSTER IS SKIPPED**, which is what makes re-uploading the same list
    idempotent. See the comment at the skip for why that is a correctness rule and not a saving.
    """
    if not artisan_ids:
        return 0

    from app.core.db import db
    from app.schemas.design_workshops import StageEntryIn, StageSaveIn
    from app.services.stage_schema import stages

    spec = next((s for s in stages() if s.key == STAGE_PARTICIPANTS), None)
    if spec is None:  # pragma: no cover - the registry disagreeing with itself
        warn(None, "The workshop's participant stage is missing from this server's registry.")
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

    # ── ALREADY ON THE ROSTER IS SKIPPED, WHICH IS WHAT MAKES A SECOND UPLOAD IDEMPOTENT ────────
    #
    # **THIS IS NOT AN OPTIMISATION.** Re-uploading the same list is the ordinary thing to do after
    # fixing two refused rows, and every artisan on it comes back as `matched` — already recorded,
    # already filed under this workshop, nothing created. Without this skip the roster would grow a
    # SECOND row for each of them: the printed participant table would list fifteen people thirty
    # times, the completeness score would move for no reason, and the only way back would be
    # deleting rows by hand on a stage the officer cannot open.
    #
    # ``replaceCollections=False`` is what makes the skip necessary rather than redundant — the
    # sweep that would otherwise have replaced the collection is deliberately off, because it would
    # take every participant row the designer added by hand with it.
    #
    # READ OFF THE SAME QUERY AS ``next_ordinal``, so this costs nothing. ``data`` is a JSON column,
    # so the value may be anything a client ever wrote; only a string is a reference.
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
        written += int(result.get("created", 0) or 0)
        if result.get("errors"):
            # REPORTED, NOT RAISED. The artisans are already created; a 500 here would leave the
            # officer with N records and a stack trace, which is the exact failure the
            # never-drop-a-row contract exists to prevent.
            warn(
                None,
                "Some participant rows could not be filled in completely. The artisan records were "
                "created; open the workshop's Participating artisans stage to finish them.",
            )
    return written


# THE INVARIANT, CHECKED AT IMPORT. A ``StageSaveIn`` built in process that exceeds
# ``MAX_STAGE_ROWS`` raises a ``pydantic.ValidationError`` — not a 422, because there is no request
# body wrapper around it — which becomes a bare 500 AFTER every artisan in the file is committed.
# The two constants are compared LIVE rather than against literals, so lowering either of them
# fails here rather than in production.
from app.schemas.design_workshops import MAX_STAGE_ROWS as _MAX_STAGE_ROWS  # noqa: E402

if not (STAGE_CHUNK <= _MAX_STAGE_ROWS and MAX_ARTISANS <= _MAX_STAGE_ROWS):  # pragma: no cover
    raise RuntimeError(
        f"artisan_import.STAGE_CHUNK ({STAGE_CHUNK}) and artisan_xlsx.MAX_ARTISANS "
        f"({MAX_ARTISANS}) must both stay at or under schemas.design_workshops.MAX_STAGE_ROWS "
        f"({_MAX_STAGE_ROWS}); a StageSaveIn built in process that exceeds it is a 500 raised "
        f"after every artisan is already written."
    )
