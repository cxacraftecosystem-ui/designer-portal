import re
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
from typing import NamedTuple

import pytest

import app.services.stage_definitions  # noqa: F401
from app.services import design_workshops as dw
from app.services import measurement_provenance, record_fields
from app.services.records import derive_age, derive_experience_years, mask_identity_number
from app.services.stage_schema import (
    ENUMS,
    REFERENCE_HYDRATION,
    FieldType,
    all_entities,
    coerce_value,
    field_to_dict,
    validate_registry,
)

SCHEMA = Path(__file__).resolve().parents[1] / "prisma" / "schema.prisma"



def _schema_text() -> str:
    return SCHEMA.read_text(encoding="utf-8")


def _block(kind: str, name: str) -> str:
    text = _schema_text()
    start = re.search(rf"^{kind}\s+{name}\s*\{{", text, re.MULTILINE)
    assert start, f"{kind} {name} is not in {SCHEMA.name}"
    depth, index = 0, start.end() - 1
    for index in range(start.end() - 1, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                break
    body = text[start.end(): index]
    return re.sub(r"//[^\n]*|///[^\n]*", "", body)


def _columns(model: str) -> set[str]:
    scalars = {"String", "Int", "Float", "Boolean", "DateTime", "Decimal", "Json", "BigInt", "Bytes"}
    enums = set(re.findall(r"^enum\s+(\w+)\s*\{", _schema_text(), re.MULTILINE))
    out: set[str] = set()
    for line in _block("model", model).splitlines():
        line = line.strip()
        if not line or line.startswith("@@"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        name, type_name = parts[0], parts[1].rstrip("?[]")
        if not name[0].islower():
            continue
        if type_name in scalars or type_name in enums:
            out.add(name)
    return out


def _relations(model: str) -> set[str]:
    scalars = {"String", "Int", "Float", "Boolean", "DateTime", "Decimal", "Json", "BigInt", "Bytes"}
    enums = set(re.findall(r"^enum\s+(\w+)\s*\{", _schema_text(), re.MULTILINE))
    out: set[str] = set()
    for line in _block("model", model).splitlines():
        line = line.strip()
        if not line or line.startswith("@@"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        name, type_name = parts[0], parts[1].rstrip("?[]")
        if not name[0].islower():
            continue
        if type_name not in scalars and type_name not in enums:
            out.add(name)
    return out


def _enum_members(name: str) -> set[str]:
    return {m.strip() for m in _block("enum", name).splitlines() if m.strip()}


_FILING_SCOPE = (
    "scope, not a fact about the subject — which design workshop the SOURCE record was filed under. "
    "A carry writes into a stage of the DESTINATION workshop, which is already known and already "
    "gated (record_design_workshop.assert_may_file_under on the way in, load_workshop_or_404 on the "
    "way out); copying the source's filing onto it would assert a membership nothing re-checks."
)

_MUTABLE_VERDICT = (
    "a reviewer's verdict, and MUTABLE — not bookkeeping. Frozen into a permanent copy it would "
    "assert a stale verdict for ever, so it is shown live in the picker's sublabel "
    "(design_workshops._review_flag) and never written onto an entry."
)

_AN_IDEMPOTENCY_KEY = (
    "the idempotency key of the CREATE this row came from — a fact about one device's network "
    "exchange, not about the artisan, product or tool. Null on every row nobody replayed; see "
    "Workshop.clientKey and records.client_key_replay."
)

ARTISAN_CARRIED = {
    "name": "participant.name",
    "localName": "participant.localName",
    "gender": "participant.gender",
    "phone": "participant.phone",
    "phoneCountryCode": "participant.phone",
    "email": "participant.email",
    "place": "participant.village",
    "pehchanCardAvailable": "participant.pehchanCardAvailable",
    "pehchanCardNumber": "participant.artisanCardNo",
    "aadhaarLast4": "participant.aadhaarNumber (MASKED, as XXXX XXXX + these four)",
    "address": "participant.address",
    "notes": "participant.recordNotes",
    "dos": "participant.dos",
    "donts": "participant.donts",
    "recordedAt": "participant.documentedOn",
    "dateOfBirth": "participant.age (derived, never stored)",
    "craftStartDate": "participant.craftStartDate + participant.experienceYears (derived)",
    "experienceYears": "participant.experienceYears (the stated number, behind the derivation)",
    "extraMetadata": "participant.experienceYears + participant.age + specialisation (legacy)",
    "craftId": "participant.specialisation",
    "locationId": "participant.village/state/district/pincode/subjectLocation",
    "workshopId": "participant.documentedAtWorkshop (Workshop.title, via the relation)",
}
_AADHAAR_VERIFICATION = "not carried — an Aadhaar verification result, never copied off the record"

ARTISAN_NOT_CARRIED = {
    "status": _MUTABLE_VERDICT,
    "aadhaarFingerprint": "the dedup key, an HMAC under a server secret — it never leaves the API",
    "reviewNotes": "bookkeeping — internal moderation",
    "reviewedById": "bookkeeping — internal moderation",
    "reviewedAt": "bookkeeping — internal moderation",
    "recordedTimezone": "the date is carried as a bare DATE, so the zone has nothing to qualify",
    "createdAt": "bookkeeping",
    "updatedAt": "bookkeeping",
    "createdById": "bookkeeping — the researcher who typed the record",
    "designWorkshopId": _FILING_SCOPE,
    "experienceMonths": "no carry target — record_fields.py's Experience cell is whole-years only",
    "aadhaarVerifyStatus": _AADHAAR_VERIFICATION,
    "aadhaarVerifyMethod": _AADHAAR_VERIFICATION,
    "aadhaarVerifiedAt": _AADHAAR_VERIFICATION,
    "aadhaarVerifyLast4": _AADHAAR_VERIFICATION,
    "aadhaarVerifyName": _AADHAAR_VERIFICATION,
    "aadhaarVerifyBirthYear": _AADHAAR_VERIFICATION,
    "aadhaarVerifyDob": _AADHAAR_VERIFICATION,
    "aadhaarVerifyGender": _AADHAAR_VERIFICATION,
    "aadhaarVerifySignedAt": _AADHAAR_VERIFICATION,
    "aadhaarVerifyTxn": _AADHAAR_VERIFICATION,
    "aadhaarVerifyNameMatch": _AADHAAR_VERIFICATION,
    "aadhaarVerifyById": _AADHAAR_VERIFICATION,
    "aadhaarVerifyDecidedById": _AADHAAR_VERIFICATION,
    "aadhaarVerifyDecidedAt": _AADHAAR_VERIFICATION,
}

LOCATION_CARRIED = {
    "state": "participant.state + existingProduct.recordState + tool.recordState",
    "district": "participant.district + existingProduct.recordDistrict + tool.recordDistrict",
    "village": "participant.village + existingProduct.recordVillage + tool.recordVillage",
    "pincode": "participant.pincode + existingProduct.recordPincode + tool.recordPincode",
    "subjectLatitude": (
        "participant.subjectLocation + existingProduct.recordSubjectLocation "
        "+ tool.recordSubjectLocation"
    ),
    "subjectLongitude": (
        "participant.subjectLocation + existingProduct.recordSubjectLocation "
        "+ tool.recordSubjectLocation"
    ),
}
LOCATION_NOT_CARRIED = {
    "latitude": "provenance, not stated — the desk the record was typed at",
    "longitude": "provenance, not stated",
    "altitude": "provenance, not stated",
    "accuracy": "provenance, not stated",
    "capturedAt": "provenance, not stated",
    "placeName": "provenance, not stated — derived from the device fix, not from the artisan",
    "address": (
        "provenance, not stated — the address of the desk the record was typed at, derived from "
        "the same device fix placeName is. Artisan.address is the STATED one and still carries."
    ),
    "extraMetadata": "bookkeeping",
    "createdAt": "bookkeeping",
    "updatedAt": "bookkeeping",
}

PRODUCT_CARRIED = {
    "productName": "existingProduct.name",
    "localName": "existingProduct.localName",
    "productType": "existingProduct.recordType (+ .category for the two mappable tokens)",
    "craftName": "existingProduct.craftName",
    "place": "existingProduct.place",
    "artisanName": "existingProduct.artisanName",
    "rawMaterialsUsed": "existingProduct.material",
    "mainToolsUsed": "existingProduct.mainToolsUsed",
    "productFunctionUse": "existingProduct.use",
    "sellingPrice": "existingProduct.price",
    "costOfMaking": "existingProduct.costOfMaking",
    "marketDemand": "existingProduct.marketDemand",
    "lengthInches": "existingProduct.lengthCm (x2.54)",
    "breadthInches": "existingProduct.widthCm (x2.54)",
    "heightInches": "existingProduct.heightCm (x2.54)",
    "extraMetadata": "existingProduct.measurementMethodNote (fieldProvenance.method only)",
    "size": "existingProduct.dimensionsNote",
    "timeTakenToCompleteProduct": "existingProduct.productionTimeNote",
    "remarks": "existingProduct.remarks",
    "recordedAt": "existingProduct.documentedOn",
    "artisanId": "join key — existingProduct.artisanRef, which fills artisanName",
    "craftId": "carried by value as craftName",
    "locationId": (
        "existingProduct.place (denormalised) + recordState/recordDistrict/recordVillage/"
        "recordPincode + recordSubjectLocation. Provenance columns never cross — see LOCATION_*"
    ),
}
PRODUCT_NOT_CARRIED = {
    "measurementImageId": (
        "a working image from the grid-measurement pipeline, not a catalogue photograph. It would "
        "arrive in the report's product gallery as a picture of a ruler. This entry used to end "
        "'The catalogue shot is carried through _reference_photos', which was FALSE about the "
        "tree it was written in: _reference_photos took the OLDEST image and both record forms "
        "upload the grid frames first, so the grid shot reached the gallery anyway, by the path "
        "this entry named as the safe one. Refusing the column achieved nothing until "
        "_reference_photos learned to sort a MEASUREMENT_GRID-marked row last — which is what "
        "now makes the sentence true. See test_the_measurement_grid_shot_is_not_the_records_"
        "photograph."
    ),
    "measurementAnalysis": (
        "machine output. report_templates.py:69-84 requires a reader to be able to tell model "
        "prose from an author's, and this has no such treatment — it would need the naming "
        "ANNEXURE_AI_LAYERS gets."
    ),
    "measurementAnalysisStatus": "queue state of the above",
    "status": _MUTABLE_VERDICT,
    "reviewNotes": "bookkeeping",
    "reviewedById": "bookkeeping",
    "reviewedAt": "bookkeeping",
    "recordedTimezone": "the date is carried as a bare DATE",
    "createdAt": "bookkeeping",
    "updatedAt": "bookkeeping",
    "createdById": "bookkeeping",
    "workshopId": "join key",
    "designWorkshopId": _FILING_SCOPE,
    "clientKey": _AN_IDEMPOTENCY_KEY,
}

TOOL_CARRIED = {
    "toolkitName": "tool.name",
    "localName": "tool.localName",
    "englishName": "tool.englishName",
    "processUsedIn": "tool.usedFor",
    "material": "tool.material",
    "replacementCost": "tool.cost",
    "yearsInUse": "tool.yearsInUse",
    "maker": "tool.maker",
    "traditionType": "tool.traditionType",
    "craftName": "tool.craftName",
    "place": "tool.place",
    "artisanName": "tool.artisanName",
    "suggestionsForToolImprovement": "tool.improvements",
    "remarks": "tool.remarks",
    "lengthInches": "tool.lengthCm (x2.54)",
    "breadthInches": "tool.breadthCm (x2.54)",
    "heightInches": "tool.heightCm (x2.54)",
    "extraMetadata": "tool.measurementMethodNote (fieldProvenance.method only)",
    "height": "tool.heightAsRecorded (cm since the form pairing; older rows state no unit)",
    "width": "tool.widthAsRecorded (cm since the form pairing; older rows state no unit)",
    "thickness": "tool.thicknessAsRecorded (source states no unit)",
    "weight": "tool.weightAsRecorded (source states no unit)",
    "radius": "tool.radiusAsRecorded (source states no unit)",
    "recordedAt": "tool.documentedOn",
    "artisanId": "carried by value as artisanName",
    "craftId": "carried by value as craftName",
    "locationId": (
        "tool.place (denormalised) + recordState/recordDistrict/recordVillage/recordPincode "
        "+ recordSubjectLocation. Provenance columns never cross — see LOCATION_*"
    ),
}
TOOL_NOT_CARRIED = {
    "materialFamily": (
        "the shared material-family code (services/standard_values); the stage asks its own "
        "MATERIAL_FAMILY question, and the free `material` text is what the tool carries"
    ),
    "measurementImageId": (
        "a working image from the measurement pipeline; see the product note, including why that "
        "note's claim about _reference_photos was false until the MEASUREMENT_GRID marker existed"
    ),
    "measurementAnalysis": "machine output; see the product note",
    "measurementAnalysisStatus": "queue state of the above",
    "status": _MUTABLE_VERDICT,
    "reviewNotes": "bookkeeping",
    "reviewedById": "bookkeeping",
    "reviewedAt": "bookkeeping",
    "recordedTimezone": "the date is carried as a bare DATE",
    "createdAt": "bookkeeping",
    "updatedAt": "bookkeeping",
    "createdById": "bookkeeping",
    "workshopId": "join key",
    "designWorkshopId": _FILING_SCOPE,
    "clientKey": _AN_IDEMPOTENCY_KEY,
}

PROCESS_CARRIED = {
    "name": "processStep.name + traditionalProcess.documentedProcessName",
    "notes": "processStep.description + traditionalProcess.documentedProcessNotes",
    "preProcessAvailable": "traditionalProcess.preProcessAvailable",
    "recordedAt": "traditionalProcess.documentedOn",
    "productId": "processStep.documentedFor + traditionalProcess.documentedFor (the product name)",
}
PROCESS_NOT_CARRIED = {
    "status": _MUTABLE_VERDICT,
    "reviewNotes": "bookkeeping",
    "reviewedById": "bookkeeping",
    "reviewedAt": "bookkeeping",
    "extraMetadata": "bookkeeping",
    "recordedTimezone": "the date is carried as a bare DATE",
    "createdAt": "bookkeeping",
    "updatedAt": "bookkeeping",
    "createdById": "bookkeeping",
    "workshopId": "join key",
    "designWorkshopId": _FILING_SCOPE,
    "clientKey": _AN_IDEMPOTENCY_KEY,
}

PROCESS_STEP_CARRIED = {
    "name": "traditionalProcess.documentedSteps (one line per step)",
    "notes": "traditionalProcess.documentedSteps",
    "stepType": "traditionalProcess.documentedSteps ('(group)' marker)",
    "sortOrder": "traditionalProcess.documentedSteps (the line's number)",
}
PROCESS_STEP_NOT_CARRIED = {
    "processId": "join key",
    "createdAt": "bookkeeping",
    "updatedAt": "bookkeeping",
}

CRAFT_CARRIED = {
    "name": "workshopSetup.craftName",
    "localName": "workshopSetup.craftLocalName",
    "category": "workshopSetup.craftCategory",
    "description": "workshopSetup.documentedCraftNotes",
    "place": "workshopSetup.craftPlace",
    "recordedAt": "workshopSetup.craftDocumentedOn",
    "workshopId": "workshopSetup.craftDocumentedAtWorkshop (Workshop.title, via the relation)",
}
CRAFT_NOT_CARRIED = {
    "extraMetadata": "bookkeeping",
    "craftCode": "cross-portal identifier (C26) — matching key, nothing a stage prints",
    "recordedTimezone": "the date is carried as a bare DATE, so the zone has nothing to qualify",
    "createdAt": "bookkeeping",
    "updatedAt": "bookkeeping",
    "createdById": "bookkeeping",
    "slug": "cross-portal identity of a master-list craft — a matching key, nothing a stage prints",
    "subCategoryId": "taxonomy link — the category name crosses as `category`",
    "sourceSerial": "the PDF's row number — provenance of the list, not of the workshop",
    "stateOfOrigin": "a fact of the list; the workshop's own state is stage 1's",
    "stateOfPractice": "a fact of the list; the workshop's own state is stage 1's",
    "giStatus": "a fact of the list, not a stage box",
    "isCatchAll": "list bookkeeping — marks an 'Others' row",
    "sourceNotes": "list bookkeeping — the extraction's notes on the PDF row",
    "status": "list bookkeeping — the picker offers ACTIVE crafts only (offered_where)",
    "mergedIntoId": "list bookkeeping — where a MERGED craft went",
}

QUESTIONNAIRE_INTERVIEW_CARRIED = {
    "title": "artisanBaseline.interviewTitle",
    "interviewDate": "artisanBaseline.interviewDate",
    "place": "artisanBaseline.interviewPlace",
    "language": "artisanBaseline.interviewLanguage",
    "recordedAt": "artisanBaseline.interviewDocumentedOn",
    "workshopId": "artisanBaseline.interviewDocumentedAtWorkshop (Workshop.title, via the relation)",
}
QUESTIONNAIRE_INTERVIEW_NOT_CARRIED = {
    "clientKey": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "closedAt": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "endedAt": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "joinCode": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "languageCode": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "languageOther": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "legacyTitle": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "mergedIntoId": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "note": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "origin": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "pendingChangesAt": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "purpose": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "questionnaireId": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "sessionDate": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "sessionDateEstimated": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "source": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "startedAt": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "state": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "submittedAt": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "version": "the shared questionnaire's session column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "notes": (
        "free prose about a GROUP, of which a report page may name one member — unattributable and "
        "unredactable. Unlike the artisan record's notes, whose subject is the person named on the "
        "row it lands on. An owner decision to reverse; the default is silence."
    ),
    "status": _MUTABLE_VERDICT,
    "reviewNotes": _MUTABLE_VERDICT,
    "reviewedById": _MUTABLE_VERDICT,
    "reviewedAt": _MUTABLE_VERDICT,
    "artisanSetKey": (
        "a group re-identification key: the workshop scope plus the sorted, comma-joined ARTISAN "
        "IDS of the sitting. Carrying it would smuggle in the roster the names refusal excludes, "
        "in one string."
    ),
    "deletedAt": "bookkeeping: when the interview was soft-deleted. Not a fact a report cites.",
    "deletedById": "bookkeeping: who soft-deleted the interview. Not a fact a report cites.",
    "locationId": (
        "join key, and provenance rather than stated: the desk the record was typed at. This model "
        "does not include the relation at all, so no Location column is one getattr away."
    ),
    "extraMetadata": "bookkeeping",
    "recordedTimezone": "the date is carried as a bare DATE, so the zone has nothing to qualify",
    "createdAt": "bookkeeping",
    "updatedAt": "bookkeeping",
    "createdById": "bookkeeping — and see the refusal of an 'interviewed by' box on the model",
    "designWorkshopId": _FILING_SCOPE,
}

QUESTIONNAIRE_RESPONSE_CARRIED = {
    "answerText": (
        "artisanBaseline.interviewQuestionsAnswered — COUNTED (non-blank), NEVER QUOTED. See "
        "test_no_artisan_answer_or_name_crosses_from_an_interview."
    ),
    "updatedAt": "artisanBaseline.interviewLastAnsweredOn (the max across the sitting)",
}
QUESTIONNAIRE_RESPONSE_NOT_CARRIED = {
    "answerOrigin": (
        "whether the answer was typed or accepted from a transcript draft — bookkeeping about the "
        "answer, which itself does not cross"
    ),
    "interviewId": "join key — the rows are reached through the interview's own responses relation",
    "questionId": "join key",
    "notes": (
        "free text about a named person, on the same terms as answerText: the schema cannot say who "
        "in a group said it, and the copy is permanent and one-way."
    ),
    "answeredById": "bookkeeping — and the same saver-is-not-the-speaker refusal as createdById",
    "createdAt": "bookkeeping",
}

QUESTIONNAIRE_INTERVIEW_ARTISAN_CARRIED: dict[str, str] = {}
QUESTIONNAIRE_INTERVIEW_ARTISAN_NOT_CARRIED = {
    "joinedAt": "the shared questionnaire's participant column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "leftAt": "the shared questionnaire's participant column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "sortOrder": "the shared questionnaire's participant column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "interviewId": "join key",
    "artisanId": (
        "the roster of the sitting. Only the COUNT crosses — a sitting may cover artisans who are "
        "not on this workshop's roster, so naming or identifying them would have a submitted report "
        "disclose that a person from another cluster was interviewed."
    ),
    "createdAt": "bookkeeping",
}

QUESTIONNAIRE_QUESTION_CARRIED = {
    "sectionCode": "artisanBaseline.interviewSectionsCovered (distinct codes, counted)",
}
QUESTIONNAIRE_QUESTION_NOT_CARRIED = {
    "artisanField": "the shared questionnaire's question column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "displayCode": "the shared questionnaire's question column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "helpText": "the shared questionnaire's question column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "isRequired": "the shared questionnaire's question column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "lineageId": "the shared questionnaire's question column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "questionnaireId": "the shared questionnaire's question column (UNIFY U0, migration 20261107090000); written only by app/questionnaire, dark until U1",
    "questionCode": "cross-portal identifier (C26) — matching key; only counts of questions cross",
    "prompt": (
        "a prompt without its answer answers nothing, and with its answer it is the disclosure "
        "refused above. Only the COUNT of answered questions crosses."
    ),
    "sectionTitle": "as prompt: only the count of distinct sections crosses",
    "sortOrder": "the instrument's own ordering; nothing printed is ordered by it",
    "isActive": "instrument lifecycle — and the reason no denominator crosses",
    "retiredAt": "instrument lifecycle",
    "supersededById": "instrument lifecycle — the rewording column; see prompt",
    "sectionId": "join key",
    "createdAt": "bookkeeping",
    "updatedAt": "bookkeeping",
}

RELATION_LEDGER = {
    "Artisan": {
        "craft": "read by the picker — participant.specialisation, via include={'craft': True}",
        "location": (
            "read by the picker — the STATED address columns and the subject pin, via "
            "include={'location': True}. The PROVENANCE half of the same row does not cross."
        ),
        "createdBy": "bookkeeping — the researcher who typed the record",
        "workshop": (
            "read by the picker — participant.documentedAtWorkshop takes Workshop.title, via "
            "include={'workshop': True}. The EXPLICIT column and not the WorkshopArtisan join, "
            "which cannot answer 'which one documented it'."
        ),
        "workshops": "join key — the WorkshopArtisan reading of 'was at this workshop'",
        "products": "reached by its own picker (existingProduct.productRef)",
        "tools": "reached by its own picker (tool.toolRef)",
        "toolLinks": "the tool-assignment join; see ToolDocumentation.artisanLinks below",
        "media": (
            "one photograph per record, through _reference_photos — AND counted, by type, into the "
            "sentence participant.recordMediaNote carries, via include={'media': True}. The ids "
            "never cross: see _media_note."
        ),
        "questionnaireInterviews": "a different feature; the report reaches questionnaires directly",
        "crossPortalLink": (
            "NOT INCLUDED — the Field Repository link (C27) is how a record was filled, and is read "
            "only by services/cross_portal; nothing from the other portal reaches a stage through it"
        ),
        "sectionStatuses": "bookkeeping — questionnaire progress",
        "designWorkshop": (
            "the back-reference of the filing column above; see _FILING_SCOPE. Nothing includes it and nothing may: the picker reads a record to seed a stage entry, and the workshop that record was FILED under is not the workshop the entry is being written into."
        ),
        "cards": (
            "NOT INCLUDED — the printed QR cards (services/artisan_card) identify the artisan on paper; "
            "a card code is never a stage value, and nothing the picker reads goes through it"
        ),
        "duplicatePairsA": "NOT INCLUDED — likely-duplicate bookkeeping (services/artisan_duplicates)",
        "duplicatePairsB": "NOT INCLUDED — likely-duplicate bookkeeping (services/artisan_duplicates)",
    },
    "Location": {
        "artisans": "the back-reference of the relation the Artisan picker includes",
        "workshops": "join key",
        "products": "back-reference",
        "tools": "back-reference",
        "media": "back-reference",
        "questionnaireInterviews": "a different feature",
        "designerProfiles": "NOT carried — see prefill_from_profile; a profile's location is never included",
    },
    "ProductDocumentation": {
        "artisan": (
            "NOT INCLUDED, deliberately: every value the picker needs is on the denormalised "
            "scalar columns (artisanName, craftName, place). include={'artisan': True} used to be "
            "declared here and no lambda ever read it — a join per picker keystroke for nothing."
        ),
        "craft": "carried by value as craftName",
        "workshop": "join key",
        "location": (
            "read by the picker — the STATED address columns and the SUBJECT pin, via "
            "include={'location': True}, into existingProduct.recordState/recordDistrict/"
            "recordVillage/recordPincode/recordSubjectLocation. The PROVENANCE half of the same row "
            "(latitude/longitude/altitude/accuracy/capturedAt/placeName/address) never crosses; "
            "_reference_place is guarded on the model name so RENDER-time behaviour is unchanged."
        ),
        "createdBy": "bookkeeping",
        "media": (
            "one photograph per record, through _reference_photos (media_field='productId') — AND "
            "counted, by type, into existingProduct.recordMediaNote via include={'media': True}. A "
            "sentence, never the ids: see _media_note."
        ),
        "mediaProcessingJobs": "queue state; see measurementAnalysisStatus in the scalar list",
        "processes": "reached by its own picker (processStep.processRef / traditionalProcess)",
        "designWorkshop": (
            "the back-reference of the filing column above; see _FILING_SCOPE. Nothing includes it and nothing may: the picker reads a record to seed a stage entry, and the workshop that record was FILED under is not the workshop the entry is being written into."
        ),
    },
    "ToolDocumentation": {
        "artisan": "NOT INCLUDED — see the identical note on ProductDocumentation.artisan",
        "craft": "carried by value as craftName",
        "workshop": "join key",
        "location": "read by the picker — see the identical note on ProductDocumentation.location",
        "createdBy": "bookkeeping",
        "media": (
            "one photograph per record, through _reference_photos (media_field='toolId') — AND "
            "counted into tool.recordMediaNote via include={'media': True}, which also names the "
            "numbered making sequence by its STAGE_STEP_ prefix. A sentence, never the ids."
        ),
        "mediaProcessingJobs": "queue state",
        "artisanLinks": (
            "read by the picker — every assigned artisan's name, newline-separated, into "
            "tool.usedByArtisans (report_role=BULLETS), via "
            "include={'artisanLinks': {'include': {'artisan': True}}}. See _linked_artisan_names for "
            "why it is ordered by name and de-duplicated, and why 'documented for' and 'used by' are "
            "two boxes rather than one."
        ),
        "craftLinks": (
            "carried by VALUE, not by relation: tool.craftName already holds every linked craft's "
            "name joined \", \" in link order and IS the box the stage entry fills, so the "
            "selection reaches the workshop through the string the server derives from these rows. "
            "The ids never cross — a craft is reached by its own picker. See artisanLinks above, "
            "which DOES cross, and _linked_artisan_names for why that one is a separate box."
        ),
        "designWorkshop": (
            "the back-reference of the filing column above; see _FILING_SCOPE. Nothing includes it and nothing may: the picker reads a record to seed a stage entry, and the workshop that record was FILED under is not the workshop the entry is being written into."
        ),
    },
    "Process": {
        "product": "read by the picker — processStep.documentedFor, via include={'product': True}",
        "createdBy": "bookkeeping",
        "workshop": "join key",
        "steps": (
            "read by the picker — _step_lines flattens the ordered sequence onto "
            "traditionalProcess.documentedSteps, via include={'steps': True}"
        ),
        "designWorkshop": (
            "the back-reference of the filing column above; see _FILING_SCOPE. Nothing includes it and nothing may: the picker reads a record to seed a stage entry, and the workshop that record was FILED under is not the workshop the entry is being written into."
        ),
    },
    "ProcessStep": {
        "process": "join key — the step rows are reached through Process.steps",
    },
    "Craft": {
        "createdBy": "bookkeeping",
        "workshop": (
            "read by the picker — workshopSetup.craftDocumentedAtWorkshop takes Workshop.title, via "
            "include={'workshop': True}. Deliberately the EXPLICIT column and not the WorkshopCraft "
            "join below: a many-to-many cannot answer which workshop documented the craft."
        ),
        "artisans": "reached by its own picker (participant.artisanRef)",
        "products": "reached by its own picker",
        "tools": "reached by its own picker",
        "toolLinks": "the tool-to-crafts join; see ToolDocumentation.craftLinks",
        "media": (
            "one photograph per record, through _reference_photos (media_field='craftId'), into "
            "workshopSetup.craftPhoto/craftPhotoCaption — AND counted into "
            "workshopSetup.craftMediaNote via include={'media': True}. MediaFile.craftId is indexed."
        ),
        "workshops": "join key — the WorkshopCraft reading of workshop scope",
        "code": "NOT INCLUDED — the shared code row (C26); see craftCode",
        "subCategory": (
            "read by the picker — the sublabel's \"Category › Sub-category\", via "
            "include={'subCategory': {'include': {'category': True}}} (design_workshops._craft_path). "
            "Nothing hydrates from it; the category name still crosses as `category`."
        ),
        "mergedInto": "NOT INCLUDED — list bookkeeping; a MERGED craft is never offered",
        "mergedFrom": "NOT INCLUDED — list bookkeeping",
        "designWorkshops": "the back-reference of DesignWorkshop.craftId (promoted from craftRef)",
        "annualPlanEntries": "the back-reference of AnnualPlanEntry.craftId; never read as a workshop",
    },
    "QuestionnaireInterview": {
        "createdBy": "bookkeeping — who SAVED the record, which is not a claim about who sat in",
        "location": (
            "NOT INCLUDED, deliberately — provenance, not stated. The desk the record was typed at. "
            "Not including it is the cleanest form of the refusal: no Location column is one getattr "
            "away from a future carry."
        ),
        "workshop": (
            "read by the picker — artisanBaseline.interviewDocumentedAtWorkshop takes Workshop.title, "
            "via include={'workshop': True}. Safe by the same check Craft.workshop passed: nothing at "
            "render time reads row.workshop."
        ),
        "artisans": (
            "read by the picker — COUNTED into artisanBaseline.interviewArtisanCount and into the "
            "sublabel's artisan phrase, via include={'artisans': True}. THE COUNT AND NEVER THE "
            "NAMES: a sitting may cover artisans who are not on this workshop's roster, and naming "
            "them would have a submitted report disclose that a person from another cluster was "
            "interviewed. Deliberately NOT symmetrical with ToolDocumentation.artisanLinks."
        ),
        "responses": (
            "read by the picker — counted into artisanBaseline.interviewSectionsCovered, "
            "artisanBaseline.interviewQuestionsAnswered and artisanBaseline.interviewLastAnsweredOn, "
            "via include={'responses': {'include': {'question': True}}}. THE ANSWERS ARE LOADED AND "
            "DISCARDED, which is the one confidential include in the registry; the follow-up is a "
            "count_include facility so they never leave Postgres."
        ),
        "media": (
            "read by the picker — counted into artisanBaseline.interviewMediaNote via "
            "include={'media': True}. No media_field and therefore no photograph: an interview's "
            "images are pictures of named artisans mid-interview and the roster already carries each "
            "participant's portrait. The note is the only thing that can say the AUDIO RECORDING of "
            "the sitting exists."
        ),
        "designWorkshop": (
            "the back-reference of the filing column above; see _FILING_SCOPE. Nothing includes it and nothing may: the picker reads a record to seed a stage entry, and the workshop that record was FILED under is not the workshop the entry is being written into."
        ),
        "answerDrafts": "NOT INCLUDED — transcript drafts awaiting a researcher; the answers do not cross",
        "researchers": "NOT INCLUDED — the shared questionnaire's session researchers (UNIFY U0); bookkeeping, dark until U1",
    },
    "QuestionnaireResponse": {
        "interview": "join key — the rows are reached through the interview's responses relation",
        "question": (
            "read by the picker — the DENORMALISED sectionCode only, for "
            "artisanBaseline.interviewSectionsCovered. The nesting stops there: no `section`."
        ),
        "answeredBy": "bookkeeping — and the saver is not the speaker; see createdBy above",
    },
    "QuestionnaireQuestion": {
        "section": "NOT INCLUDED — the denormalised sectionCode answers the only question asked",
        "responses": "join key — reached from the other side, through the interview",
        "answerDrafts": "NOT INCLUDED — transcript drafts awaiting a researcher; never a stage value",
    },
    "QuestionnaireInterviewArtisan": {
        "interview": "join key",
        "artisan": (
            "LOADED AND NOT READ. `include={'artisans': True}` does not nest `artisan`, so the "
            "related Artisan row is not fetched at all — which is the cheapest possible form of the "
            "names refusal: there is no name in the process to leak."
        ),
    },
}


LEDGER = [
    ("Artisan", ARTISAN_CARRIED, ARTISAN_NOT_CARRIED),
    ("Location", LOCATION_CARRIED, LOCATION_NOT_CARRIED),
    ("ProductDocumentation", PRODUCT_CARRIED, PRODUCT_NOT_CARRIED),
    ("ToolDocumentation", TOOL_CARRIED, TOOL_NOT_CARRIED),
    ("Process", PROCESS_CARRIED, PROCESS_NOT_CARRIED),
    ("ProcessStep", PROCESS_STEP_CARRIED, PROCESS_STEP_NOT_CARRIED),
    ("Craft", CRAFT_CARRIED, CRAFT_NOT_CARRIED),
    ("QuestionnaireInterview", QUESTIONNAIRE_INTERVIEW_CARRIED,
     QUESTIONNAIRE_INTERVIEW_NOT_CARRIED),
    ("QuestionnaireResponse", QUESTIONNAIRE_RESPONSE_CARRIED, QUESTIONNAIRE_RESPONSE_NOT_CARRIED),
    ("QuestionnaireQuestion", QUESTIONNAIRE_QUESTION_CARRIED, QUESTIONNAIRE_QUESTION_NOT_CARRIED),
    ("QuestionnaireInterviewArtisan", QUESTIONNAIRE_INTERVIEW_ARTISAN_CARRIED,
     QUESTIONNAIRE_INTERVIEW_ARTISAN_NOT_CARRIED),
]


@pytest.mark.parametrize(("model", "carried", "refused"), LEDGER, ids=[m for m, _c, _r in LEDGER])
def test_no_source_column_is_unaccounted_for(model, carried, refused):
    columns = _columns(model) - {"id"}
    accounted = set(carried) | set(refused)
    missing = sorted(columns - accounted)
    assert not missing, (
        f"{model} has columns this repository has never decided about: {missing}. Add each to "
        f"{model.upper()}_CARRIED with the workshop field it reaches, or to _NOT_CARRIED with the "
        "reason it does not."
    )
    stale = sorted(accounted - columns)
    assert not stale, f"{model} no longer has these columns: {stale}"


@pytest.mark.parametrize("model", sorted(RELATION_LEDGER), ids=sorted(RELATION_LEDGER))
def test_no_source_relation_is_unaccounted_for(model):
    declared = _relations(model)
    accounted = set(RELATION_LEDGER[model])
    missing = sorted(declared - accounted)
    assert not missing, (
        f"{model} has relations this repository has never decided about: {missing}. Add each to "
        f"RELATION_LEDGER[{model!r}] with what reaches the workshop through it, or with the reason "
        "nothing does."
    )
    stale = sorted(accounted - declared)
    assert not stale, f"{model} no longer has these relations: {stale}"


@pytest.mark.parametrize(("model", "carried", "refused"), LEDGER, ids=[m for m, _c, _r in LEDGER])
def test_no_column_is_both_carried_and_refused(model, carried, refused):
    overlap = sorted(set(carried) & set(refused))
    assert not overlap, f"{model}: {overlap} appear in both lists"


def test_the_ledger_names_real_workshop_fields():
    known = {f"{entity.key}.{spec.key}"
             for _s, entity in all_entities() for spec in entity.fields}
    for model, carried, _refused in LEDGER:
        for column, target in carried.items():
            for token in re.findall(r"\b([a-z]\w*)\.(\w+)\b", target):
                path = f"{token[0]}.{token[1]}"
                if token[0] in {e.key for _s, e in all_entities()}:
                    assert path in known, (
                        f"{model}.{column} claims to reach {path}, which is not in the registry"
                    )



def test_the_registry_is_sound():
    assert validate_registry() == []


def test_every_mapping_source_exists_and_every_produced_key_lands():
    assert dw.validate_reference_carry() == []


def test_a_field_that_promises_to_fill_itself_in_actually_does():
    from app.services.stage_definitions import FROM_REF

    promises: set[str] = set()
    for _stage, entity in all_entities():
        for spec in entity.fields:
            if FROM_REF in spec.details:
                promises.add(f"{entity.key}.{spec.key}")

    delivers: set[str] = set()
    for path, mapping in REFERENCE_HYDRATION.items():
        entity_key = path.partition(".")[0]
        delivers.update(f"{entity_key}.{target}" for target in mapping.values())

    assert sorted(promises - delivers) == [], (
        "these fields tell the designer they fill in from the linked record and no mapping "
        "writes them"
    )
    assert sorted(delivers - promises) == [], (
        "these fields are filled in from the linked record and their help text does not say so"
    )



def test_inches_become_centimetres_and_the_factor_is_exact():
    assert dw._inches_to_cm(12) == 30.48
    assert dw._inches_to_cm("1") == 2.54
    assert dw._inches_to_cm(None) is None
    assert dw._inches_to_cm("not a number") is None


def test_the_source_columns_say_inches_and_the_target_fields_say_centimetres():
    for model in ("ProductDocumentation", "ToolDocumentation"):
        assert "lengthInches" in _columns(model)
        assert "breadthInches" in _columns(model)
        assert "heightInches" in _columns(model)

    for entity_key, field_keys in (
        ("existingProduct", ("lengthCm", "widthCm", "heightCm")),
        ("tool", ("lengthCm", "breadthCm", "heightCm")),
    ):
        for field_key in field_keys:
            spec = _field(entity_key, field_key)
            assert spec.unit == "cm", f"{entity_key}.{field_key} no longer declares centimetres"


def test_the_tool_measurements_whose_unit_nobody_knows_do_not_claim_one():
    for field_key in ("heightAsRecorded", "widthAsRecorded", "thicknessAsRecorded",
                      "weightAsRecorded", "radiusAsRecorded"):
        spec = _field("tool", field_key)
        assert spec.unit == "", f"tool.{field_key} claims a unit the source record does not state"
        assert "unit" in spec.help.lower(), f"tool.{field_key} does not warn the designer"



@pytest.mark.parametrize(
    ("prisma_enum", "table"),
    [
        ("ProductType", dw._PRODUCT_TYPE_TO_CATEGORY),
        ("ProductType", dw._PRODUCT_TYPE_TO_MEMBER),
        ("MarketDemand", dw._MARKET_DEMAND_TO_DEMAND_LEVEL),
        ("TraditionType", dw._TRADITION_TYPE_TO_TRADITION),
        ("MakerType", dw._MAKER_TYPE_TO_MAKER),
    ],
)
def test_every_translation_table_names_every_member_of_its_prisma_enum(prisma_enum, table):
    members = _enum_members(prisma_enum)
    assert set(table) == members, (
        f"the translation table for {prisma_enum} is out of step with prisma/schema.prisma: "
        f"missing {sorted(members - set(table))}, unknown {sorted(set(table) - members)}. Spell an "
        "unmappable member None, with the reason, rather than leaving it out."
    )


@pytest.mark.parametrize(
    ("prisma_enum", "registry_enum"),
    [("ProductType", "PRODUCT_TYPE"), ("MakerType", "MAKER_TYPE")],
)
def test_the_mirrored_registry_lists_match_their_prisma_enums(prisma_enum, registry_enum):
    assert set(ENUMS[registry_enum]) == _enum_members(prisma_enum)


def test_the_registry_tradition_list_is_the_tool_forms_shared_list():
    from app.services import standard_values as sv

    assert dict(ENUMS["TRADITION_TYPE"]) == {
        option["code"]: option["label"] for option in sv.tradition_options()
    }


def test_every_translated_target_can_actually_hold_what_the_table_produces():
    for table, registry_enum in (
        (dw._PRODUCT_TYPE_TO_CATEGORY, "PRODUCT_CATEGORY"),
        (dw._PRODUCT_TYPE_TO_MEMBER, "PRODUCT_TYPE"),
        (dw._MARKET_DEMAND_TO_DEMAND_LEVEL, "DEMAND_LEVEL"),
        (dw._TRADITION_TYPE_TO_TRADITION, "TRADITION_TYPE"),
        (dw._MAKER_TYPE_TO_MAKER, "MAKER_TYPE"),
    ):
        for source, target in table.items():
            if target is None:
                continue
            assert target in ENUMS[registry_enum], (
                f"{source} translates to {target}, which is not in ENUMS[{registry_enum!r}] and "
                "would be dropped by coerce_value without a word"
            )


def test_an_unmapped_token_is_logged_and_not_raised(caplog):
    import logging

    with caplog.at_level(logging.ERROR):
        assert dw._translated(dw._MAKER_TYPE_TO_MAKER, "A_TOKEN_FROM_THE_FUTURE") is None
    assert "A_TOKEN_FROM_THE_FUTURE" in caplog.text
    caplog.clear()
    assert dw._translated(dw._MAKER_TYPE_TO_MAKER, None) is None
    assert caplog.text == ""



def test_both_identity_numbers_arrive_masked_and_neither_arrives_bare():
    row = _artisan_row()
    data = dw.REFERENCE_MODELS["Artisan"].data(row, None)

    assert data["pehchanCardNumber"] == "XXXX XXXX 5678"
    assert data["aadhaarNumber"] == "XXXX XXXX 0123"
    assert row.pehchanCardNumber not in str(data), "the bare card number is in the carried data"
    assert row.aadhaarFingerprint not in str(data), "the Aadhaar fingerprint is in the carried data"


_SHAPE_WORDS = frozenset({"number", "no", "id", "the", "a", "of", "s"})


def test_the_carried_aadhaar_is_a_mask_a_key_value_and_never_a_column():
    aadhaar = _field("participant", "aadhaarNumber")
    pehchan = _field("participant", "artisanCardNo")

    assert aadhaar.report_role.value == "KEY_VALUE", (
        "participant.aadhaarNumber must print in the per-row key-value block, never as a seventh "
        "column of a participant table that is already inside submitted documents"
    )
    assert aadhaar.column_width_pct in (None, 0, 0.0), (
        "a KEY_VALUE field declaring a column width reads as a column waiting to be promoted"
    )

    widths = [f.column_width_pct for f in _entity("participant").fields
              if f.report_role.value == "TABLE_COLUMN" and not f.deprecated]
    assert abs(sum(widths) - 100.0) < 1e-6, (
        f"participant's declared table widths now sum to {sum(widths)}; at anything but exactly "
        "100 the proportional fallback is already governing and the no-seventh-column rule "
        "protects nothing"
    )

    once = mask_identity_number("234567890123")
    assert once == "XXXX XXXX 0123"
    assert mask_identity_number(once) == once, (
        "the mask is no longer idempotent, so records._IDENTITY_KEYS re-masking a stage entry by "
        "key name would corrupt the value instead of leaving it alone"
    )

    def naming_words(label: str) -> set[str]:
        return {
            w for w in re.split(r"[^a-z]+", label.lower())
            if w and w not in _SHAPE_WORDS
        }

    shared = naming_words(aadhaar.label) & naming_words(pehchan.label)
    assert shared == set(), (
        f"“{aadhaar.label}” and “{pehchan.label}” now share {sorted(shared)}, which is a word that "
        "names WHICH card rather than the shape of the box. Two masked rows reading "
        "“XXXX XXXX ####” must be told apart by their labels alone"
    )
    assert "aadhaar" in aadhaar.label.lower()
    assert naming_words(pehchan.label), f"“{pehchan.label}” no longer names a card at all"


def _valid_aadhaar() -> str:
    from app.services.artisan_identity import verhoeff_ok

    for last in "0123456789":
        candidate = f"23456789012{last}"
        if verhoeff_ok(candidate):
            return candidate
    raise AssertionError("no Verhoeff-valid Aadhaar in the candidate range")


def test_a_full_aadhaar_typed_into_the_carried_box_is_stored_as_the_mask():
    aadhaar = _field("participant", "aadhaarNumber")
    pehchan = _field("participant", "artisanCardNo")

    assert aadhaar.store_masked is True, (
        "participant.aadhaarNumber no longer declares store_masked, so a full number typed or read "
        "into it is stored verbatim on a permanent, grantee-readable stage entry — which is the "
        "state the owner's 2026-08-24 decision was made against"
    )

    real = _valid_aadhaar()
    grouped = f"{real[:4]} {real[4:8]} {real[8:]}"
    hyphenated = f"{real[:4]}-{real[4:8]}-{real[8:]}"
    mask = f"XXXX XXXX {real[-4:]}"
    for typed in (real, grouped, hyphenated, mask):
        stored, error = coerce_value(aadhaar, typed)
        assert error is None, f"{typed!r} was refused: {error}"
        assert stored == mask, f"{typed!r} was stored as {stored!r}"

    assert coerce_value(aadhaar, real)[1] is None

    stored, error = coerce_value(aadhaar, "hello world 1234")
    assert stored is None and error is not None, (
        f"a typed string that is not an Aadhaar number was stored as {stored!r}; the format check "
        "has to run BEFORE store_masked, or masking manufactures the defect it prevents"
    )
    assert aadhaar.text_format.value == "AADHAAR", (
        "the mask is only as good as the format beside it: store_masked alone is mask(anything), "
        "which is how a typo became a government identity number in a ministry document"
    )

    assert coerce_value(aadhaar, "x" * 21)[1] is not None

    assert pehchan.store_masked is False
    assert coerce_value(pehchan, "DL/2024/0001234")[0] == "DL/2024/0001234"

    assert field_to_dict(aadhaar, "participant")["storeMasked"] is True
    assert "storeMasked" not in field_to_dict(pehchan, "participant")


def test_experience_and_age_now_come_from_columns_with_the_legacy_metadata_behind_them():
    columns = _columns("Artisan")
    assert "experienceYears" in columns, "the migration is the point of this test"
    assert "dateOfBirth" in columns
    assert "craftStartDate" in columns, "the 20260823093000 migration is the point of 5-7 below"
    assert "age" not in columns, (
        "an age COLUMN is the mistake this design exists to avoid: it is wrong within a year of "
        "being written and nothing would ever notice. Store the date, derive the age."
    )

    documented = _artisan_row(
        dateOfBirth=datetime(1971, 1, 8), experienceYears=31,
        extraMetadata={"experienceYears": 22, "age": 44},
    )
    carried = dw.REFERENCE_MODELS["Artisan"].data(documented, None)
    assert carried["experienceYears"] == 31, "the column must outrank the legacy metadata"
    assert carried["age"] != 44, "age must be derived from dateOfBirth, not read from metadata"
    assert carried["age"] == derive_age(datetime(1971, 1, 8))

    legacy = dw.REFERENCE_MODELS["Artisan"].data(_artisan_row(), None)
    assert legacy["experienceYears"] == 22
    assert legacy["age"] == 44

    silent = dw.REFERENCE_MODELS["Artisan"].data(
        _artisan_row(extraMetadata={"mediaExif": [{"lat": 1}]}), None
    )
    assert silent["experienceYears"] is None
    assert silent["age"] is None

    joined = _artisan_row(
        craftStartDate=datetime(1994, 3, 12), experienceYears=31,
        extraMetadata={"experienceYears": 22, "age": 44},
    )
    from_date = dw.REFERENCE_MODELS["Artisan"].data(joined, None)
    assert from_date["experienceYears"] == derive_experience_years(datetime(1994, 3, 12))
    assert from_date["experienceYears"] not in (31, 22), (
        "the joining date must outrank both the stated column and the legacy metadata"
    )
    assert from_date["craftStartDate"] == "1994-03-12"

    stated_only = dw.REFERENCE_MODELS["Artisan"].data(
        _artisan_row(experienceYears=31, extraMetadata={"experienceYears": 22}), None
    )
    assert stated_only["experienceYears"] == 31
    assert stated_only["craftStartDate"] is None

    for unusable in (datetime(2099, 1, 1), datetime(1900, 1, 1)):
        fell_through = dw.REFERENCE_MODELS["Artisan"].data(
            _artisan_row(craftStartDate=unusable, experienceYears=31), None
        )
        assert derive_experience_years(unusable) is None
        assert fell_through["experienceYears"] == 31, unusable


def test_the_device_fix_never_reaches_the_workshop_and_the_subject_pin_does():
    data = dw.REFERENCE_MODELS["Artisan"].data(_artisan_row(), None)
    assert data["subjectLocation"] == {"lat": 21.2, "lon": 83.6}
    assert "accuracy" not in data["subjectLocation"], "a hand-dropped pin has no error bar"

    no_pin = _artisan_row(location=_location_row(subjectLatitude=None, subjectLongitude=None))
    assert dw.REFERENCE_MODELS["Artisan"].data(no_pin, None)["subjectLocation"] is None


async def test_the_gps_address_never_becomes_the_artisans_stated_address(monkeypatch):
    no_stated_address = _artisan_row(
        address=None,
        location=_location_row(address="Plot 14, campus road, Kharagpur"),
    )
    data = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"},
        rows={"artisan": [no_stated_address]},
    )
    assert "address" not in data, (
        f"the desk's address reached the participant roster as {data.get('address')!r}"
    )
    assert "Kharagpur" not in str(data), (
        "nothing derived from the device fix may cross — placeName is refused for the same reason"
    )
    assert data["village"] == "Barpali"
    assert data["district"] == "Bargarh"
    assert data["state"] == "Odisha"

    stated = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"}, rows={"artisan": [_artisan_row()]},
    )
    assert stated["address"] == "House 4, Weavers' lane"



def _field(entity_key: str, field_key: str):
    entity = next(e for _s, e in all_entities() if e.key == entity_key)
    spec = entity.field(field_key)
    assert spec is not None, f"{entity_key}.{field_key} is not in the registry"
    return spec


def _entity(entity_key: str):
    return next(e for _s, e in all_entities() if e.key == entity_key)


def _location_row(**overrides):
    fields = dict(
        state="Odisha", district="Bargarh", village="Barpali", pincode="768029",
        address="Plot 14, campus road, Kharagpur", placeName="Kharagpur",
        subjectLatitude=21.2, subjectLongitude=83.6,
        latitude=22.314, longitude=87.311, altitude=32.0, accuracy=26.0,
        capturedAt=datetime(2025, 3, 12, 9, 0),
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


def _media_row(**overrides):
    fields = dict(id="med_x", mediaType="IMAGE", originalFilename="loom.jpg", extraMetadata=None)
    fields.update(overrides)
    return SimpleNamespace(**fields)


def _artisan_row(**overrides):
    fields = dict(
        id="art_1", name="Latha Devi", localName="ଲତା ଦେବୀ", gender="Female",
        phone="9876500001", email="latha@example.org", place="Barpali",
        aadhaarFingerprint="ab" * 32, aadhaarLast4="0123", pehchanCardAvailable=True,
        pehchanCardNumber="PEHCHAN5678", address="House 4, Weavers' lane",
        notes="Prefers morning sessions.", dos="1. Speak in Odia\n2. Show samples",
        donts="1. Do not photograph the loom shed",
        extraMetadata={"experienceYears": 22, "age": 44},
        dateOfBirth=None, experienceYears=None, craftStartDate=None,
        recordedAt=datetime(2025, 3, 12, 9, 0), recordedTimezone="Asia/Kolkata",
        craft=SimpleNamespace(name="Sambalpuri Ikat"), location=_location_row(),
        workshop=SimpleNamespace(title="Sambalpuri Ikat cluster survey, Barpali"),
        media=[
            _media_row(id="med_a1"),
            _media_row(id="med_a2", mediaType="AUDIO", originalFilename="introduction.m4a"),
        ],
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


def _product_row(**overrides):
    fields = dict(
        id="prd_1", craftName="Sambalpuri Ikat", place="Barpali", artisanName="Latha Devi",
        productName="Sambalpuri saree", localName="ବନ୍ଧ ଶାଢ଼ୀ", productType="SAMPLE",
        timeTakenToCompleteProduct="about three weeks", size="6 yards, single width",
        lengthInches=216, breadthInches=45, heightInches=2,
        costOfMaking=2600, sellingPrice=4500, marketDemand="HIGH",
        rawMaterialsUsed="Cotton yarn", mainToolsUsed="Pit loom, bobbin winder",
        productFunctionUse="Daily and festive wear", remarks="Second-quality weft in one panel.",
        recordedAt=datetime(2025, 4, 2, 11, 0),
        location=_location_row(),
        extraMetadata={
            "fieldProvenance": {
                "lengthInches": {"by": "usr_7", "byName": "R. Menon",
                                 "at": "2025-04-02T11:00:00+00:00",
                                 "method": "VISION_MODEL", "methodProvider": "gemini",
                                 "methodModelId": "gemini-2.5-flash-lite",
                                 "methodConfidence": 0.8},
                "breadthInches": {"by": "usr_7", "byName": "R. Menon",
                                  "at": "2025-04-02T11:00:00+00:00",
                                  "method": "PHOTO_GEOMETRY", "methodTechnique": "SCALE"},
                "heightInches": {"by": "usr_7", "byName": "R. Menon",
                                 "at": "2025-04-02T11:00:00+00:00", "method": "TYPED"},
                "sellingPrice": {"by": "usr_7", "byName": "R. Menon",
                                 "at": "2025-04-02T11:00:00+00:00"},
            },
        },
        media=[
            _media_row(id="med_p1"),
            _media_row(id="med_p2", mediaType="VIDEO", originalFilename="finishing.mp4"),
        ],
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


def _tool_row(**overrides):
    fields = dict(
        id="tul_1", craftName="Sambalpuri Ikat", place="Barpali", artisanName="Latha Devi",
        toolkitName="Pit loom", localName="ଖଡ଼ି", englishName="Pit treadle loom",
        processUsedIn="Weaving", material="Teak and bamboo", yearsInUse=18,
        height=180, width=120, lengthInches=96, breadthInches=48, heightInches=30,
        thickness=4.5, weight=95, radius=6,
        maker="CARPENTER", traditionType="HYBRID", replacementCost=12000,
        suggestionsForToolImprovement="A higher bench would ease the back.",
        remarks="Rebuilt in 2019.", recordedAt=datetime(2025, 4, 3, 8, 30),
        location=_location_row(),
        extraMetadata={
            "fieldProvenance": {
                "lengthInches": {"by": "usr_9", "byName": "S. Bal",
                                 "at": "2025-04-03T08:30:00+00:00",
                                 "method": "VISION_MODEL", "methodProvider": "gemini"},
                "breadthInches": {"by": "usr_9", "byName": "S. Bal",
                                  "at": "2025-04-03T08:30:00+00:00", "method": "UNRECORDED"},
            },
        },
        media=[
            _media_row(id="med_t1", originalFilename="STAGE_STEP_1_warping.jpg"),
            _media_row(id="med_t2", originalFilename="STAGE_STEP_2_treadles.jpg"),
            _media_row(id="med_t3", mediaType="AUDIO", originalFilename="weaver-explains.m4a"),
            _media_row(id="med_t4", originalFilename="grid-length.jpg",
                       extraMetadata={"purpose": "MEASUREMENT_GRID"}),
        ],
        artisanLinks=[SimpleNamespace(artisan=SimpleNamespace(name="Latha Devi")),
                      SimpleNamespace(artisan=SimpleNamespace(name="Bhima Meher"))],
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


def _process_row(**overrides):
    fields = dict(
        id="prc_1", name="Tie and dye", notes="Yarn is tied in sections, dyed, untied, washed.",
        preProcessAvailable=True, recordedAt=datetime(2025, 4, 4, 7, 0),
        product=SimpleNamespace(productName="Sambalpuri saree"),
        media=[_media_row(id="med_pr1", mediaType="VIDEO", originalFilename="pre-process.mp4")],
        steps=[
            SimpleNamespace(name="Washing", stepType="SEQUENTIAL", sortOrder=2, notes=None,
                            media=[]),
            SimpleNamespace(name="Tying", stepType="SEQUENTIAL", sortOrder=0,
                            notes="Cotton thread, section by section",
                            media=[_media_row(id="med_ps1")]),
            SimpleNamespace(name="Dyeing", stepType="GROUP", sortOrder=1, notes=None, media=[]),
        ],
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


def _craft_row(**overrides):
    fields = dict(
        id="crf_1", name="Sambalpuri Ikat", localName="ସମ୍ବଲପୁରୀ ବନ୍ଧ",
        category="Weaving", place="Barpali",
        description="Warp and weft are tied and dyed before the cloth is woven.",
        recordedAt=datetime(2024, 11, 5, 10, 0),
        workshop=SimpleNamespace(title="Sambalpuri Ikat cluster survey, Barpali"),
        media=[
            _media_row(id="med_c1"),
            _media_row(id="med_c2", mediaType="AUDIO", originalFilename="elder-account.m4a"),
            _media_row(id="med_c3", mediaType="PDF", originalFilename="gazetteer-page.pdf"),
        ],
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


def _response_row(**overrides):
    fields = dict(
        answerText="Twelve, and two of them belong to my brother.",
        notes="She hesitated before answering.",
        updatedAt=datetime(2026, 3, 14, 11, 30),
        question=SimpleNamespace(sectionCode="LOOMS", sectionTitle="Looms and equipment",
                                 prompt="How many looms do you own?"),
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


def _interview_row(**overrides):
    fields = dict(
        id="qi_1",
        title="Barpali weavers, group 2",
        interviewDate=datetime(2026, 3, 14, 9, 0),
        place="Barpali",
        language="Odia",
        notes="The second weaver's daughter translated for her.",
        status="APPROVED",
        artisanSetKey="wsh_1||art_1,art_2,art_3,art_4,art_5,art_6",
        recordedAt=datetime(2026, 3, 16, 8, 0),
        createdById="usr_9",
        workshop=SimpleNamespace(title="Sambalpuri Ikat cluster survey, Barpali"),
        artisans=[SimpleNamespace(artisanId=f"art_{n}",
                                 artisan=SimpleNamespace(name=f"Artisan {n}"))
                  for n in range(1, 7)],
        responses=[
            _response_row(),
            _response_row(question=SimpleNamespace(sectionCode="DYES",
                                                   sectionTitle="Dyes",
                                                   prompt="Which dyes?"),
                          answerText="Natural indigo.",
                          updatedAt=datetime(2026, 3, 14, 12, 0)),
            _response_row(question=SimpleNamespace(sectionCode="INCOME",
                                                  sectionTitle="Income",
                                                  prompt="Monthly income?"),
                          answerText="   ",
                          updatedAt=datetime(2026, 3, 14, 12, 15)),
            _response_row(question=SimpleNamespace(sectionCode="DYES",
                                                  sectionTitle="Dyes",
                                                  prompt="Where from?"),
                          answerText="Bought in Bargarh.",
                          updatedAt=datetime(2026, 3, 15, 7, 45)),
        ],
        media=[
            _media_row(id="med_q1", mediaType="AUDIO", originalFilename="sitting.m4a"),
            _media_row(id="med_q2", mediaType="IMAGE", originalFilename="group.jpg"),
        ],
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


class _Delegate:
    def __init__(self, rows):
        self._rows = rows

    async def find_many(self, where=None, include=None):
        wanted = set((where or {}).get("id", {}).get("in", []))
        return [r for r in self._rows if r.id in wanted]


class _FakeDb:
    def __init__(self, rows_by_delegate, photos):
        self._rows = rows_by_delegate
        self._photos = photos

    def __getattr__(self, name):
        return _Delegate(self._rows.get(name, []))

    async def query_raw(self, _sql, ids, *_binds):
        return [
            {"parent": parent, "id": media_id, "caption": caption}
            for parent, (media_id, caption) in self._photos.items()
            if parent in ids
        ]


async def _hydrate_entry(monkeypatch, entity_key, sent, *, rows, photos=None, previous=None):
    monkeypatch.setattr(dw, "db", _FakeDb(rows, photos or {}))
    entry = dw.PendingEntry(
        entity=_entity(entity_key), data=dict(sent), previous=dict(previous or {}),
        row_id=None, ordinal=0, client_key="k1",
    )
    await dw.hydrate_entries([entry])
    return entry


async def _hydrate(monkeypatch, entity_key, sent, *, rows, photos=None, previous=None):
    entry = await _hydrate_entry(
        monkeypatch, entity_key, sent, rows=rows, photos=photos, previous=previous
    )
    return entry.data


def _targets(path: str) -> set[str]:
    return set(REFERENCE_HYDRATION[path].values())


async def test_a_fully_documented_artisan_arrives_whole(monkeypatch):
    data = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"},
        rows={"artisan": [_artisan_row(craftStartDate=datetime(1994, 3, 12))]},
        photos={"art_1": ("med_1", "At her loom, Barpali")},
    )
    blank = sorted(t for t in _targets("participant.artisanRef") if not data.get(t))
    assert blank == [], f"a fully documented artisan left these boxes empty: {blank}"

    assert data["name"] == "Latha Devi"
    assert data["email"] == "latha@example.org"
    assert data["artisanCardNo"] == "XXXX XXXX 5678"
    assert data["pehchanCardAvailable"] is True
    assert data["district"] == "Bargarh" and data["state"] == "Odisha"
    assert data["pincode"] == "768029"
    assert data["address"] == "House 4, Weavers' lane"
    assert data["subjectLocation"] == {"lat": 21.2, "lon": 83.6}
    assert data["dos"].startswith("1. Speak in Odia")
    assert data["recordNotes"] == "Prefers morning sessions."
    assert data["documentedOn"] == "2025-03-12"
    assert data["photo"] == "med_1"
    assert data["photoCaption"] == "At her loom, Barpali"


async def test_a_documented_questionnaire_interview_arrives_whole(monkeypatch):
    data = await _hydrate(
        monkeypatch, "artisanBaseline", {"interviewRef": "qi_1"},
        rows={"questionnaireinterview": [_interview_row()]},
    )
    blank = sorted(t for t in _targets("artisanBaseline.interviewRef") if not data.get(t))
    assert blank == [], f"a documented interview left these boxes empty: {blank}"

    assert data["interviewTitle"] == "Barpali weavers, group 2"
    assert data["interviewPlace"] == "Barpali"
    assert data["interviewLanguage"] == "Odia"
    assert data["interviewDate"] == "2026-03-14"
    assert data["interviewDocumentedOn"] == "2026-03-16"
    assert data["interviewArtisanCount"] == 6
    assert data["interviewSectionsCovered"] == 2
    assert data["interviewQuestionsAnswered"] == 3
    assert data["interviewLastAnsweredOn"] == "2026-03-15"
    assert data["interviewMediaNote"] == (
        "Attached to the interview record: 1 photograph, 1 audio note."
    )
    assert data["interviewDocumentedAtWorkshop"] == "Sambalpuri Ikat cluster survey, Barpali"


async def test_an_interview_that_answered_nothing_says_zero_rather_than_nothing(monkeypatch):
    data = await _hydrate(
        monkeypatch, "artisanBaseline", {"interviewRef": "qi_1"},
        rows={"questionnaireinterview": [_interview_row(responses=[], artisans=[], media=[])]},
    )
    assert data["interviewArtisanCount"] == 0
    assert data["interviewSectionsCovered"] == 0
    assert data["interviewQuestionsAnswered"] == 0
    assert "interviewLastAnsweredOn" not in data
    assert "interviewMediaNote" not in data


async def test_a_section_whose_only_row_is_blank_is_not_a_covered_section(monkeypatch):
    tabbed = [
        _response_row(answerText="", question=SimpleNamespace(sectionCode=f"S{n}",
                                                             sectionTitle=f"Section {n}",
                                                             prompt="?"))
        for n in range(1, 10)
    ]
    data = await _hydrate(
        monkeypatch, "artisanBaseline", {"interviewRef": "qi_1"},
        rows={"questionnaireinterview": [_interview_row(responses=tabbed, media=[])]},
    )
    assert data["interviewQuestionsAnswered"] == 0
    assert data["interviewSectionsCovered"] == 0, (
        "nine sections opened and none answered must not print as nine covered — the two counts "
        "read the same rows and must apply the same non-blank test"
    )
    assert "interviewLastAnsweredOn" not in data


async def test_an_interview_recorded_as_section_clips_counts_its_sections(monkeypatch):
    clips = [
        _media_row(id="med_c1", mediaType="AUDIO",
                   originalFilename="LOOMS_SEC_BARPALIGROUP2_001432_140320260915.m4a"),
        _media_row(id="med_c2", mediaType="AUDIO",
                   originalFilename="DYES_SEC_BARPALIGROUP2_000817_140320261002.m4a"),
        _media_row(id="med_c3", mediaType="AUDIO",
                   originalFilename="DYES_04_BARPALIGROUP2_000122_140320261010.m4a"),
        _media_row(id="med_c4", mediaType="AUDIO",
                   originalFilename="INCOME_SEC_BARPALIGROUP2_000355_140320261041.m4a"),
    ]
    data = await _hydrate(
        monkeypatch, "artisanBaseline", {"interviewRef": "qi_1"},
        rows={"questionnaireinterview": [_interview_row(responses=[], media=clips)]},
    )
    assert data["interviewSectionsCovered"] == 3, (
        "an interview recorded as one clip per section has to count those sections; reading 0 here "
        "is the report contradicting the screen it was checked against"
    )
    assert data["interviewQuestionsAnswered"] == 0
    assert "interviewLastAnsweredOn" not in data


async def test_an_uploaded_photograph_is_not_read_as_a_section_code(monkeypatch):
    misleading = [
        _media_row(id="med_m1", originalFilename="IMG_2031.jpg"),
        _media_row(id="med_m2", mediaType="PDF", originalFilename="consent_form.pdf"),
        _media_row(id="med_m3", mediaType="AUDIO", originalFilename="LOOMS_notes_draft.m4a"),
        _media_row(id="med_m4", mediaType="AUDIO",
                   originalFilename="SEC_SEC_BARPALIGROUP2_001432_140320260915.m4a"),
    ]
    data = await _hydrate(
        monkeypatch, "artisanBaseline", {"interviewRef": "qi_1"},
        rows={"questionnaireinterview": [_interview_row(responses=[], media=misleading)]},
    )
    assert data["interviewSectionsCovered"] == 0


async def test_a_section_tagged_on_a_media_row_counts_without_any_response(monkeypatch):
    rows = [
        _media_row(id="med_s1", mediaType="AUDIO", originalFilename="a.m4a",
                   extraMetadata={"sectionCode": "dyes"}),
        _media_row(id="med_s2", originalFilename="b.jpg", extraMetadata={"questionId": "q_looms"}),
    ]
    answered = _response_row(questionId="q_looms")
    data = await _hydrate(
        monkeypatch, "artisanBaseline", {"interviewRef": "qi_1"},
        rows={"questionnaireinterview": [_interview_row(responses=[answered], media=rows)]},
    )
    assert data["interviewSectionsCovered"] == 2


async def test_a_section_the_row_cannot_resolve_leaves_the_box_blank(monkeypatch):
    data = await _hydrate(
        monkeypatch, "artisanBaseline", {"interviewRef": "qi_1"},
        rows={"questionnaireinterview": [_interview_row(
            responses=[_response_row(questionId="q_looms")],
            media=[_media_row(id="med_u1", extraMetadata={"questionId": "q_unknown"})],
        )]},
    )
    assert "interviewSectionsCovered" not in data, (
        "an unresolvable section signal must blank the box rather than print a floor; a number the "
        "reader cannot tell is partial is worse than no number"
    )
    assert data["interviewTitle"] == "Barpali weavers, group 2"


async def test_the_last_answered_date_ignores_a_row_nobody_answered(monkeypatch):
    data = await _hydrate(
        monkeypatch, "artisanBaseline", {"interviewRef": "qi_1"},
        rows={"questionnaireinterview": [_interview_row(responses=[
            _response_row(answerText="Natural indigo.", updatedAt=datetime(2026, 3, 14, 12, 0)),
            _response_row(answerText="  ", updatedAt=datetime(2026, 4, 30, 18, 0)),
        ])]},
    )
    assert data["interviewLastAnsweredOn"] == "2026-03-14", (
        "the date must belong to the rows the count counted; 2026-04-30 is the day somebody tabbed "
        "through a section, which is not a day the sitting was answered"
    )


async def test_an_interview_of_nothing_but_blank_rows_prints_no_date(monkeypatch):
    data = await _hydrate(
        monkeypatch, "artisanBaseline", {"interviewRef": "qi_1"},
        rows={"questionnaireinterview": [_interview_row(
            responses=[_response_row(answerText=None), _response_row(answerText="")], media=[],
        )]},
    )
    assert data["interviewQuestionsAnswered"] == 0
    assert data["interviewSectionsCovered"] == 0
    assert "interviewLastAnsweredOn" not in data


async def test_no_artisan_answer_or_name_crosses_from_an_interview(monkeypatch):
    row = _interview_row()
    data = await _hydrate(
        monkeypatch, "artisanBaseline", {"interviewRef": "qi_1"},
        rows={"questionnaireinterview": [row]},
    )
    stored = " || ".join(f"{k}={v!r}" for k, v in sorted(data.items()))
    forbidden = {
        "the answer text": "Twelve, and two of them belong to my brother",
        "a second answer": "Natural indigo",
        "the response note": "She hesitated",
        "the question prompt": "How many looms",
        "the section title": "Looms and equipment",
        "an artisan name": "Artisan 3",
        "the group re-identification key": "art_1,art_2",
        "the interview's own free prose": "second weaver's daughter",
        "the reviewer's verdict": "APPROVED",
        "a media id": "med_q1",
    }
    leaked = sorted(what for what, needle in forbidden.items() if needle in stored)
    assert leaked == [], (
        f"a questionnaire interview carried {leaked} onto a stage entry. Every one of these is "
        f"permanent, unredactable and readable by a grantee. Stored row: {stored}"
    )
    assert data["interviewQuestionsAnswered"] == 3
    assert data["interviewArtisanCount"] == 6


async def test_the_review_verdict_is_live_in_the_sublabel_and_never_on_the_entry(monkeypatch):
    spec = dw.REFERENCE_MODELS["QuestionnaireInterview"]
    pending = _interview_row(status="PENDING")
    assert "Awaiting review" in spec.sublabel(pending)
    assert "2026-03-14" in spec.sublabel(pending)
    assert "Barpali" in spec.sublabel(pending)
    assert "Odia" in spec.sublabel(pending)
    assert "6 artisans" in spec.sublabel(pending)

    data = await _hydrate(
        monkeypatch, "artisanBaseline", {"interviewRef": "qi_1"},
        rows={"questionnaireinterview": [pending]},
    )
    assert not any("review" in str(v).lower() for v in data.values())


def test_the_questionnaire_picker_is_scoped_by_the_account_and_not_by_the_workshop():
    from app.services.questionnaire_forms import visible_questionnaire_where

    spec = dw.REFERENCE_MODELS["Questionnaire"]
    assert spec.delegate == "questionnaire"
    assert spec.viewer_where is visible_questionnaire_where
    assert spec.workshop_where is None
    assert spec.include == {}
    assert spec.media_field == "" and spec.artisan_field == "" and spec.filter_field == ""

    row = SimpleNamespace(
        title="Baseline instrument",
        kind="MARKET_SURVEY",
        isShared=True,
        sourceFilename="baseline.xlsx",
    )
    assert spec.label(row) == "Baseline instrument"
    assert spec.data(row, None) == {"name": "Baseline instrument"}
    label = spec.sublabel(row)
    assert "Market survey" in label and "Shared" in label and "baseline.xlsx" in label
    assert "Market survey" not in str(spec.data(row, None))

    unstated = SimpleNamespace(title="Draft", kind=None, isShared=False, sourceFilename="")
    assert spec.sublabel(unstated) == ""


def test_the_interview_picker_orders_by_title_because_nulls_sort_first_under_desc():
    assert dw.REFERENCE_MODELS["QuestionnaireInterview"].order == {"title": "asc"}


def test_the_interview_model_has_no_photograph_and_cannot_be_cascaded_from():
    spec = dw.REFERENCE_MODELS["QuestionnaireInterview"]
    assert spec.media_field == ""
    assert "questionnaireInterviewId" not in dw._PHOTO_PARENT_COLUMNS
    assert spec.artisan_field == "" and spec.filter_field == ""
    filtered_against = [
        f"{entity.key}.{f.key}"
        for _s, entity in all_entities() for f in entity.fields
        if f.ref_filter_by and f.ref_model == "QuestionnaireInterview"
    ]
    assert filtered_against == []


async def test_a_fully_documented_product_arrives_whole_and_in_centimetres(monkeypatch):
    data = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row()]},
        photos={"prd_1": ("med_2", "Front, natural light")},
    )
    blank = sorted(t for t in _targets("existingProduct.productRef")
                   if not data.get(t) and t != "category")
    assert blank == [], f"a fully documented product left these boxes empty: {blank}"

    assert data["lengthCm"] == pytest.approx(548.64)
    assert data["widthCm"] == pytest.approx(114.3)
    assert data["heightCm"] == pytest.approx(5.08)
    assert data["price"] == "4500.00" and data["costOfMaking"] == "2600.00"
    assert data["marketDemand"] == "HIGH"
    assert data["recordType"] == "SAMPLE"
    assert data["dimensionsNote"] == "6 yards, single width"
    assert data["productionTimeNote"] == "about three weeks"
    assert data["productPhotos"] == ["med_2"]
    assert data["productPhotosCaption"] == "Front, natural light"


async def test_a_product_type_with_no_honest_category_leaves_the_category_blank(monkeypatch):
    data = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row(productType="FINISHED_GOOD")]},
    )
    assert "category" not in data
    assert data["recordType"] == "FINISHED_GOOD"

    mapped = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row(productType="PACKAGING")]},
    )
    assert mapped["category"] == "PACKAGING" and mapped["recordType"] == "PACKAGING"


async def test_an_enum_member_that_is_only_ever_a_form_default_is_not_carried_as_an_answer(
    monkeypatch,
):
    untouched = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [
            _product_row(productType="OTHER", marketDemand="UNKNOWN")
        ]},
    )
    assert "category" not in untouched, (
        f"an unanswered product type asserted a category: {untouched.get('category')!r}"
    )
    assert "marketDemand" not in untouched, (
        f"an unanswered market demand arrived as {untouched.get('marketDemand')!r}"
    )
    assert untouched["recordType"] == "OTHER", "the record's own answer must still cross"

    untouched_tool = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"},
        rows={"tooldocumentation": [_tool_row(maker="UNKNOWN")]},
    )
    assert "maker" not in untouched_tool, (
        f"an unanswered maker arrived as {untouched_tool.get('maker')!r}"
    )

    chosen = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [
            _product_row(productType="PACKAGING", marketDemand="SEASONAL")
        ]},
    )
    assert chosen["category"] == "PACKAGING" and chosen["marketDemand"] == "SEASONAL"
    chosen_tool = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"},
        rows={"tooldocumentation": [_tool_row(maker="OTHER")]},
    )
    assert chosen_tool["maker"] == "OTHER"


async def test_a_fully_documented_tool_arrives_whole(monkeypatch):
    data = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"},
        rows={"tooldocumentation": [_tool_row()]},
        photos={"tul_1": ("med_3", "The loom in the shed")},
    )
    blank = sorted(t for t in _targets("tool.toolRef") if not data.get(t))
    assert blank == [], f"a fully documented tool left these boxes empty: {blank}"

    assert data["maker"] == "CARPENTER"
    assert data["traditionType"] == "TRANSITIONAL", "HYBRID must be translated, not dropped"
    assert data["lengthCm"] == pytest.approx(243.84)
    assert data["breadthCm"] == pytest.approx(121.92)
    assert data["heightCm"] == pytest.approx(76.2)
    assert data["heightAsRecorded"] == 180 and data["weightAsRecorded"] == 95
    assert data["yearsInUse"] == 18
    assert data["photoCaption"] == "The loom in the shed"


_PRODUCT_METHOD_NOTE = (
    "On the product record: length (vision model estimate), breadth (photo measurement)"
)
_TOOL_METHOD_NOTE = "On the tool record: length (vision model estimate)"


async def test_the_measurement_method_crosses_with_the_number_it_qualifies(monkeypatch):
    product = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row()]},
    )
    assert product["measurementMethodNote"] == _PRODUCT_METHOD_NOTE
    assert "height" not in product["measurementMethodNote"]

    tool = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"},
        rows={"tooldocumentation": [_tool_row()]},
    )
    assert tool["measurementMethodNote"] == _TOOL_METHOD_NOTE
    assert "breadth" not in tool["measurementMethodNote"]


async def test_a_record_whose_dimensions_were_all_typed_says_nothing_at_all(monkeypatch):
    typed = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row(extraMetadata={"fieldProvenance": {
            "lengthInches": {"by": "usr_7", "method": "TYPED"},
            "breadthInches": {"by": "usr_7", "method": "UNRECORDED"},
            "heightInches": {"by": "usr_7"},
        }})]},
    )
    assert "measurementMethodNote" not in typed, (
        "a record whose dimensions were typed, unrecorded or unstamped must leave this box blank"
    )

    legacy = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row(extraMetadata=None)]},
    )
    assert "measurementMethodNote" not in legacy, (
        "a row with no fieldProvenance blob at all predates the record half and states nothing"
    )


async def test_the_note_never_names_a_dimension_that_has_no_number_to_qualify(monkeypatch):
    data = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row(lengthInches=None)]},
    )
    assert "lengthCm" not in data, "the fixture must actually leave the length box empty"
    assert data["measurementMethodNote"] == "On the product record: breadth (photo measurement)"


def test_the_workshop_prints_the_record_sheets_own_two_phrases_and_not_a_third_vocabulary():
    assert record_fields.METHOD_CLAUSES == {
        "VISION_MODEL": "vision model estimate",
        "PHOTO_GEOMETRY": "photo measurement",
    }, (
        "these two phrases are printed by the record sheet, every .xlsx sheet, both CSV exports and "
        "now the workshop report. Rewording one rewords all four; rewording it in one place only is "
        "how one stamp comes to be described in two vocabularies"
    )
    for clause in record_fields.METHOD_CLAUSES.values():
        assert clause in _PRODUCT_METHOD_NOTE or clause in _TOOL_METHOD_NOTE

    assert "TYPED" not in record_fields.METHOD_CLAUSES
    assert measurement_provenance.UNRECORDED not in record_fields.METHOD_CLAUSES


def test_the_method_carry_covers_exactly_the_columns_a_method_can_be_stamped_on():
    table = dw._METHOD_CARRIED_DIMENSIONS
    assert set(table) <= set(dw.REFERENCE_MODELS)

    for model, pairs in table.items():
        columns = {column for _payload, column in pairs}
        assert columns <= measurement_provenance.DIMENSION_FIELDS, (
            f"{model} pairs a method against {sorted(columns - measurement_provenance.DIMENSION_FIELDS)}, "
            f"which method_stamps drops — the note would describe a stamp nothing writes"
        )
        carried = {
            source
            for path, mapping in REFERENCE_HYDRATION.items()
            if _field(*path.split(".", 1)).ref_model == model
            for source in mapping
        }
        payload_keys = {payload for payload, _column in pairs}
        assert payload_keys <= carried, (
            f"{model} names payload keys {sorted(payload_keys - carried)} that no mapping hydrates"
        )

    tool_columns = {column for _payload, column in table["ToolDocumentation"]}
    assert tool_columns.isdisjoint({"height", "width", "thickness", "weight", "radius"})
    assert tool_columns == {"lengthInches", "breadthInches", "heightInches"}, (
        "the tool's method carry is the same stampable triple as the product's"
    )


async def test_the_method_note_is_recomputable_by_the_divergence_path(monkeypatch):
    row = _product_row()
    canonical = dw.REFERENCE_MODELS["ProductDocumentation"].data(row, None)
    hydrated = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [row]},
    )
    assert canonical["measurementMethodNote"] == hydrated["measurementMethodNote"]
    assert canonical["measurementMethodNote"] == _PRODUCT_METHOD_NOTE


async def test_where_a_tool_was_obtained_is_not_guessed_from_who_made_it(monkeypatch):
    from app.services.stage_definitions import FROM_REF

    assert FROM_REF not in _field("tool", "source").details
    assert "source" not in _targets("tool.toolRef")

    data = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"}, rows={"tooldocumentation": [_tool_row()]}
    )
    assert "source" not in data


async def test_a_documented_process_reaches_the_overview_whole_including_its_sub_steps(
    monkeypatch,
):
    data = await _hydrate(
        monkeypatch, "traditionalProcess", {"processRef": "prc_1"},
        rows={"process": [_process_row()]},
    )
    blank = sorted(t for t in _targets("traditionalProcess.processRef") if not data.get(t))
    assert blank == [], f"a fully documented process left these boxes empty: {blank}"

    assert data["documentedProcessName"] == "Tie and dye"
    assert data["documentedFor"] == "Sambalpuri saree"
    assert data["preProcessAvailable"] is True
    lines = data["documentedSteps"].split("\n")
    assert lines == [
        "1. Tying — Cotton thread, section by section",
        "2. Dyeing (group)",
        "3. Washing",
    ], "the sub-steps must arrive in sortOrder, numbered by position, with notes and group marks"


def test_a_step_with_two_notes_is_still_one_bullet_in_the_report():
    process = _process_row(steps=[
        SimpleNamespace(name="Tying", stepType="SEQUENTIAL", sortOrder=0,
                        notes="Use cotton thread\n\nKnots must be tight"),
        SimpleNamespace(name="Dyeing", stepType="GROUP", sortOrder=1, notes=None),
        SimpleNamespace(name="Washing", stepType="SEQUENTIAL", sortOrder=2,
                        notes="Rinse in the tank; then dry in shade"),
    ])
    value = dw._step_lines(process)
    lines = value.split("\n")
    assert lines == [
        "1. Tying — Use cotton thread · Knots must be tight",
        "2. Dyeing (group)",
        "3. Washing — Rinse in the tank · then dry in shade",
    ], "one documented step must be one line, whatever its note contains"

    rendered = [piece.strip() for piece in value.replace(";", "\n").split("\n")]
    assert len([piece for piece in rendered if piece]) == 3, (
        f"report_builder would print {rendered} — a bullet per piece, and the unnumbered ones read "
        "to a ministry officer as steps in the sequence"
    )


async def test_the_overview_narrative_the_designer_writes_is_never_overwritten(monkeypatch):
    assert "processOverview" not in _targets("traditionalProcess.processRef")
    data = await _hydrate(
        monkeypatch, "traditionalProcess", {"processRef": "prc_1"},
        rows={"process": [_process_row()]},
    )
    assert "processOverview" not in data


async def test_a_process_step_row_still_receives_only_the_three_it_should(monkeypatch):
    assert _targets("processStep.processRef") == {"name", "description", "documentedFor"}
    data = await _hydrate(
        monkeypatch, "processStep", {"stepNumber": 1, "processRef": "prc_1"},
        rows={"process": [_process_row()]},
    )
    assert "documentedSteps" not in data and "preProcessAvailable" not in data



def test_exactly_three_boxes_have_two_writers():
    doubled: dict[tuple[str, str], list[tuple[int, str, str]]] = {}
    for _stage, entity in all_entities():
        order = [f.key for f in entity.fields]
        for f in entity.fields:
            for source, target in REFERENCE_HYDRATION.get(f"{entity.key}.{f.key}", {}).items():
                doubled.setdefault((entity.key, target), []).append(
                    (order.index(f.key), f.key, source)
                )
    two_writers = {k: sorted(v) for k, v in doubled.items() if len(v) > 1}
    assert two_writers == {
        ("traditionalProcess", "documentedFor"): [(0, "productRef", "name"),
                                                  (1, "processRef", "productName")],
        ("processStep", "documentedFor"): [(1, "productRef", "name"),
                                           (2, "processRef", "productName")],
        ("existingProduct", "artisanName"): [(0, "artisanRef", "name"),
                                             (1, "productRef", "artisanName")],
    }, (
        "a hydration target gained or lost a second writer. Two mappings on one entity writing one "
        "box is legal and is used three times on purpose, but WHICH of them wins depends on whether "
        "the ref was re-pointed in that save — see the block comment above. A new one needs the same "
        "two tests this block gives the other three."
    )


async def test_the_product_the_designer_picked_answers_documented_for(monkeypatch):
    for entity_key, sent in (
        ("traditionalProcess", {"productRef": "prd_1", "processRef": "prc_1"}),
        ("processStep", {"stepNumber": 1, "productRef": "prd_1", "processRef": "prc_1"}),
    ):
        entry = await _hydrate_entry(
            monkeypatch, entity_key, sent,
            rows={"productdocumentation": [_product_row()], "process": [_process_row()]},
        )
        assert entry.data["documentedFor"] == "Sambalpuri saree", entity_key
        stamp = entry.hydrated["documentedFor"]
        assert (stamp.model, stamp.source_key) == ("ProductDocumentation", "name"), entity_key


async def test_re_picking_the_process_hands_the_box_to_the_process(monkeypatch):
    rows = {
        "productdocumentation": [_product_row(), _product_row(id="prd_2",
                                                              productName="Bandha dupatta")],
        "process": [_process_row(), _process_row(id="prc_2", name="Bandha tying",
                                                 product=SimpleNamespace(
                                                     productName="Bandha dupatta"))],
    }
    entry = await _hydrate_entry(
        monkeypatch, "traditionalProcess",
        {"productRef": "prd_2", "processRef": "prc_2", "documentedFor": "Sambalpuri saree"},
        rows=rows, previous={"productRef": "prd_1", "processRef": "prc_1"},
    )
    assert entry.data["documentedFor"] == "Bandha dupatta"
    stamp = entry.hydrated["documentedFor"]
    assert (stamp.model, stamp.source_key) == ("Process", "productName")


async def test_a_stale_pair_prints_the_processs_parent(monkeypatch):
    rows = {
        "productdocumentation": [_product_row(), _product_row(id="prd_2",
                                                              productName="Bandha dupatta")],
        "process": [_process_row(), _process_row(id="prc_9", name="Stale pick",
                                                 product=SimpleNamespace(
                                                     productName="Sambalpuri saree"))],
    }
    entry = await _hydrate_entry(
        monkeypatch, "traditionalProcess",
        {"productRef": "prd_2", "processRef": "prc_9", "documentedFor": "Sambalpuri saree"},
        rows=rows, previous={"productRef": "prd_1", "processRef": "prc_1"},
    )
    assert entry.data["documentedFor"] == "Sambalpuri saree", (
        "the process's parent wins a re-pointed save; if this now reads 'Bandha dupatta' the "
        "last-writer rule has changed and the notes in stage_schema.REFERENCE_HYDRATION, "
        "frontend/lib/designWorkshops.ts and cascade-process-product-unit.spec.ts all describe the "
        "old behaviour"
    )



async def test_the_half_done_cascade_clears_the_process_it_no_longer_names(monkeypatch):
    rows = {
        "productdocumentation": [_product_row(id="prd_2", productName="Bandha dupatta")],
    }
    stale = {
        "documentedFor": "Sambalpuri saree",
        "documentedProcessName": "Tie and dye",
        "documentedProcessNotes": "Yarn is tied in sections, dyed, untied, washed.",
        "documentedSteps": "Tying; Dyeing; Washing",
        "preProcessAvailable": True,
        "recordMediaNote": "Attached to the process record: 1 video.",
        "documentedOn": "2025-04-04",
    }
    entry = await _hydrate_entry(
        monkeypatch, "traditionalProcess",
        {"productRef": "prd_2", **stale},
        rows=rows,
        previous={"productRef": "prd_1", "processRef": "prc_1", **stale},
    )
    assert entry.data["documentedFor"] == "Bandha dupatta"
    stamp = entry.hydrated["documentedFor"]
    assert (stamp.model, stamp.source_key) == ("ProductDocumentation", "name")
    left = sorted(k for k in stale if k != "documentedFor" and k in entry.data)
    assert left == [], f"these still describe the process the row no longer names: {left}"


async def test_the_half_done_cascade_clears_a_process_steps_required_name(monkeypatch):
    entry = await _hydrate_entry(
        monkeypatch, "processStep",
        {"stepNumber": 1, "productRef": "prd_2",
         "name": "Tie and dye", "description": "Yarn is tied in sections.",
         "documentedFor": "Sambalpuri saree"},
        rows={"productdocumentation": [_product_row(id="prd_2", productName="Bandha dupatta")]},
        previous={"productRef": "prd_1", "processRef": "prc_1", "name": "Tie and dye"},
    )
    assert entry.data["documentedFor"] == "Bandha dupatta"
    assert "name" not in entry.data
    assert "description" not in entry.data
    assert entry.data["stepNumber"] == 1, "the designer's own answers are not this rule's business"


async def test_unlinking_a_participant_still_keeps_the_name_it_filled_in(monkeypatch):
    entry = await _hydrate_entry(
        monkeypatch, "participant", {"name": "Latha Devi", "village": "Barpali"},
        rows={}, previous={"artisanRef": "art_1", "name": "Latha Devi", "village": "Barpali"},
    )
    assert entry.data["name"] == "Latha Devi"
    assert entry.data["village"] == "Barpali"


async def test_clearing_the_product_too_leaves_the_row_uniformly_stale(monkeypatch):
    entry = await _hydrate_entry(
        monkeypatch, "traditionalProcess",
        {"documentedFor": "Sambalpuri saree", "documentedProcessName": "Tie and dye"},
        rows={},
        previous={"productRef": "prd_1", "processRef": "prc_1",
                  "documentedFor": "Sambalpuri saree", "documentedProcessName": "Tie and dye"},
    )
    assert entry.data["documentedFor"] == "Sambalpuri saree"
    assert entry.data["documentedProcessName"] == "Tie and dye"


async def test_a_build_that_never_sends_the_product_cannot_trip_the_clear(monkeypatch):
    entry = await _hydrate_entry(
        monkeypatch, "traditionalProcess",
        {"documentedFor": "Sambalpuri saree", "documentedProcessName": "Tie and dye"},
        rows={},
        previous={"processRef": "prc_1", "documentedFor": "Sambalpuri saree",
                  "documentedProcessName": "Tie and dye"},
    )
    assert entry.data["documentedProcessName"] == "Tie and dye"


async def test_the_older_artisan_cascade_gets_the_same_treatment(monkeypatch):
    stale = {
        "name": "Sambalpuri saree",
        "material": "Cotton yarn",
        "mainToolsUsed": "Pit loom, bobbin winder",
        "remarks": "Second-quality weft in one panel.",
    }
    entry = await _hydrate_entry(
        monkeypatch, "existingProduct",
        {"artisanRef": "art_2", "artisanName": "Latha Devi", **stale},
        rows={"artisan": [_artisan_row(id="art_2", name="Sita Meher")]},
        previous={"artisanRef": "art_1", "productRef": "prd_1",
                  "artisanName": "Latha Devi", **stale},
    )
    assert entry.data["artisanName"] == "Sita Meher"
    left = sorted(k for k in stale if k in entry.data)
    assert left == [], f"these describe a product documented for the artisan the row dropped: {left}"


async def test_a_prototypes_source_product_goes_when_the_artisan_does(monkeypatch):
    entry = await _hydrate_entry(
        monkeypatch, "prototype",
        {"artisanRef": "par_2", "productName": "Sambalpuri saree"},
        rows={},
        previous={"artisanRef": "par_1", "productRef": "prd_1",
                  "productName": "Sambalpuri saree"},
    )
    assert "productName" not in entry.data


async def test_the_clear_runs_even_when_the_payload_resolves_no_record_at_all(monkeypatch):
    entry = await _hydrate_entry(
        monkeypatch, "traditionalProcess",
        {"productRef": "prd_missing", "documentedFor": "Sambalpuri saree",
         "documentedProcessName": "Tie and dye"},
        rows={"productdocumentation": []},
        previous={"productRef": "prd_1", "processRef": "prc_1",
                  "documentedFor": "Sambalpuri saree", "documentedProcessName": "Tie and dye"},
    )
    assert "documentedProcessName" not in entry.data
    assert "documentedFor" not in entry.data, (
        "nothing resolved, so nothing may be written; the box is blank for the designer to answer "
        "rather than holding another product's process name"
    )


async def test_an_older_client_sending_only_the_process_still_fills_the_box(monkeypatch):
    entry = await _hydrate_entry(
        monkeypatch, "traditionalProcess", {"processRef": "prc_1"},
        rows={"process": [_process_row()]},
    )
    assert entry.data["documentedFor"] == "Sambalpuri saree"
    stamp = entry.hydrated["documentedFor"]
    assert (stamp.model, stamp.source_key) == ("Process", "productName")


async def test_a_documented_craft_reaches_the_cover_page_whole(monkeypatch):
    data = await _hydrate(
        monkeypatch, "workshopSetup", {"craftRef": "crf_1"}, rows={"craft": [_craft_row()]},
        photos={"crf_1": ("med_c1", "The loom shed at Barpali")},
    )
    blank = sorted(t for t in _targets("workshopSetup.craftRef") if not data.get(t))
    assert blank == [], f"a documented craft left these cover-page boxes empty: {blank}"

    assert data["craftName"] == "Sambalpuri Ikat"
    assert data["craftLocalName"] == "ସମ୍ବଲପୁରୀ ବନ୍ଧ"
    assert data["craftCategory"] == "Weaving"
    assert data["craftPlace"] == "Barpali"
    assert data["craftDocumentedOn"] == "2024-11-05"
    assert data["craftPhoto"] == "med_c1"
    assert data["craftDocumentedAtWorkshop"] == "Sambalpuri Ikat cluster survey, Barpali"
    assert data["craftMediaNote"] == (
        "Attached to the craft record: 1 photograph, 1 audio note, 1 document."
    )


async def test_where_a_record_was_documented_crosses_and_the_join_key_does_not(monkeypatch):
    roster = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"},
        rows={"artisan": [_artisan_row()]},
    )
    assert roster["documentedAtWorkshop"] == "Sambalpuri Ikat cluster survey, Barpali"
    assert "wsh_" not in str(roster), "the workshop id is a join key and must not be copied"

    unlinked = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"},
        rows={"artisan": [_artisan_row(workshop=None)]},
    )
    assert "documentedAtWorkshop" not in unlinked


async def test_what_a_record_has_on_file_is_stated_and_never_copied(monkeypatch):
    roster = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"},
        rows={"artisan": [_artisan_row()]},
    )
    assert roster["recordMediaNote"] == (
        "Attached to the artisan record: 1 photograph, 1 audio note."
    )
    assert "med_a1" not in str(roster) and "med_a2" not in str(roster)

    product = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row()]},
    )
    assert product["recordMediaNote"] == (
        "Attached to the product record: 1 photograph, 1 video."
    )

    silent = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row(media=[])]},
    )
    assert "recordMediaNote" not in silent


async def test_a_tools_numbered_making_sequence_is_named_and_a_grid_frame_is_not_counted(
    monkeypatch,
):
    data = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"}, rows={"tooldocumentation": [_tool_row()]},
    )
    assert data["recordMediaNote"] == (
        "Attached to the tool record: 2 photographs, 1 audio note, "
        "of which 2 document the making in order."
    )

    single = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"},
        rows={"tooldocumentation": [_tool_row(media=[
            _media_row(id="med_s1", originalFilename="STAGE_STEP_1_warping.jpg"),
        ])]},
    )
    assert single["recordMediaNote"] == (
        "Attached to the tool record: 1 photograph, of which 1 documents the making in order."
    )

    only_grid = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"},
        rows={"tooldocumentation": [_tool_row(media=[
            _media_row(id="med_g1", originalFilename="grid-length.jpg",
                       extraMetadata={"purpose": "MEASUREMENT_GRID"}),
        ])]},
    )
    assert "recordMediaNote" not in only_grid, (
        "a record whose only file is a measurement grid carries no footage of the tool"
    )
    assert dw.MEASUREMENT_GRID_PURPOSE == "MEASUREMENT_GRID", (
        "the marker is written by three uploading clients; a tidied spelling silently starts "
        "counting ruled paper as a photograph of the tool"
    )


_MEDIA_NOTE_SUBJECTS = {
    ("participant", "recordMediaNote"): ("artisan", False),
    ("existingProduct", "recordMediaNote"): ("product", False),
    ("tool", "recordMediaNote"): ("tool", True),
    ("workshopSetup", "craftMediaNote"): ("craft", False),
    ("artisanBaseline", "interviewMediaNote"): ("interview", False),
}


def _widest_media_note(digits: int, subject: str = "product", *, numbered: bool = True) -> str:
    n = 10 ** digits - 1
    rows = (
        [_media_row(mediaType="IMAGE", originalFilename="STAGE_STEP_1_x.jpg")] * n
        + [_media_row(mediaType="VIDEO")] * n
        + [_media_row(mediaType="AUDIO")] * n
        + [_media_row(mediaType="PDF")] * n
        + [_media_row(mediaType="OTHER")] * n
    )
    note = dw._media_note(subject, rows,
                          numbered_prefix="STAGE_STEP_" if numbered else "")
    assert note is not None
    return note


def test_every_media_note_field_is_measured_against_its_own_subject_word():
    landing: set[tuple[str, str]] = set()
    for path, mapping in REFERENCE_HYDRATION.items():
        entity_key, _, _field_key = path.partition(".")
        for source, target in mapping.items():
            if source in {"recordMediaNote", "craftMediaNote", "interviewMediaNote"}:
                landing.add((entity_key, target))
    landing.discard(("traditionalProcess", "recordMediaNote"))
    unmeasured = sorted(landing - set(_MEDIA_NOTE_SUBJECTS))
    assert unmeasured == [], (
        f"these boxes receive a _media_note sentence and no subject word is recorded for them: "
        f"{unmeasured}. Add each to _MEDIA_NOTE_SUBJECTS with the literal its data lambda passes, "
        f"so the bound below is measured rather than extrapolated from another model's."
    )


def test_the_media_note_cannot_overrun_the_bound_its_field_declares():
    widths = {d: len(_widest_media_note(d)) for d in (1, 2, 3, 4, 5)}
    slopes = {widths[d + 1] - widths[d] for d in (1, 2, 3, 4)}
    assert slopes == {6}, (
        f"the sentence grew by {sorted(slopes)} characters per digit, not 6. Six integers are "
        f"printed in it; a different slope means a count was added or removed, and the bound below "
        f"is extrapolated from this slope, so it has to be re-derived rather than nudged"
    )

    for (entity_key, field_key), (subject, _numbered) in sorted(_MEDIA_NOTE_SUBJECTS.items()):
        intercept = len(_widest_media_note(1, subject)) - 6
        unreachable = 6 * 12 + intercept
        bound = _field(entity_key, field_key).max_length
        assert bound and unreachable <= bound, (
            f"{entity_key}.{field_key} declares max_length={bound}. `_media_note` builds "
            f"6 * digits + {intercept} characters, so coerce_value would REFUSE it at "
            f"{(bound - intercept) // 6 + 1} digits per count"
        )


def test_the_media_note_headroom_is_measured_rather_than_implied():
    slopes = {
        numbered: {
            len(_widest_media_note(d + 1, "product", numbered=numbered))
            - len(_widest_media_note(d, "product", numbered=numbered))
            for d in (1, 2, 3, 4)
        }
        for numbered in (True, False)
    }
    assert slopes == {True: {6}, False: {5}}, (
        f"the sentence prints {slopes} integers' worth of digits per digit of width. Six with the "
        f"numbered clause and five without it is what the bounds below are extrapolated from; a "
        f"different slope means a count was added or removed and every bound has to be re-derived"
    )

    measured: dict[tuple[str, str], tuple[int, int, int]] = {}
    for key, (subject, numbered) in sorted(_MEDIA_NOTE_SUBJECTS.items()):
        declared = _field(*key).max_length
        assert declared, f"{key} declares no max_length"
        formula = 6 * 12 + (len(_widest_media_note(1, subject)) - 6)
        integers = 6 if numbered else 5
        intercept = len(_widest_media_note(1, subject, numbered=numbered)) - integers
        measured[key] = (declared, integers * 12 + intercept, formula)

    short = {k: v for k, v in measured.items() if v[0] < v[2]}
    assert short == {}, (
        f"these declare a bound BELOW the widest sentence _media_note can build for their subject "
        f"at twelve digits: {short} as (declared, reachable, formula)"
    )

    assert measured == {
        ("participant", "recordMediaNote"): (200, 148, 200),
        ("existingProduct", "recordMediaNote"): (200, 148, 200),
        ("tool", "recordMediaNote"): (200, 197, 197),
        ("workshopSetup", "craftMediaNote"): (200, 146, 198),
        ("artisanBaseline", "interviewMediaNote"): (202, 150, 202),
    }, (
        f"(declared, reachable, formula) per media-note field moved: {measured}. `reachable` is what "
        f"the call site can actually be given and `formula` is what `_media_note` could build for "
        f"that subject if it were passed a numbered_prefix. If a subject word changed length, or a "
        f"call site gained or lost its prefix, re-derive the bound in stage_definitions AND the "
        f"paragraph beside it — the comment on `interviewMediaNote` quotes these numbers"
    )


def test_the_process_media_note_has_its_own_bound_and_its_own_grammar():
    def note(own: int, steps: int, per_step: int) -> str:
        process = SimpleNamespace(
            media=[_media_row()] * own,
            steps=[SimpleNamespace(media=[_media_row()] * per_step)] * steps,
        )
        built = dw._process_media_note(process)
        assert built is not None
        return built

    cases = {
        (9, 9, 9): 1 + 2 + 1,
        (99, 99, 99): 2 + 4 + 2,
        (999, 999, 999): 3 + 6 + 3,
    }
    fixed = {len(note(*args)) - digits for args, digits in cases.items()}
    assert len(fixed) == 1, (
        f"the fixed text measured {sorted(fixed)} characters at three different widths, so the "
        f"sentence is no longer 'fixed text plus the digits of three integers' and the bound below "
        f"has to be re-derived"
    )
    fixed_chars = fixed.pop()

    bound = _field("traditionalProcess", "recordMediaNote").max_length
    assert bound and fixed_chars + 30 <= bound, (
        f"traditionalProcess.recordMediaNote declares max_length={bound} and "
        f"`_process_media_note` spends {fixed_chars} characters before printing a single digit, "
        f"leaving {bound - fixed_chars} for three counts — tight enough for coerce_value to REFUSE "
        f"a real process record's note"
    )


async def test_the_pin_on_the_subjects_place_now_crosses_for_all_three_models(monkeypatch):
    product = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row()]},
    )
    tool = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"}, rows={"tooldocumentation": [_tool_row()]},
    )
    for data in (product, tool):
        assert data["recordSubjectLocation"] == {"lat": 21.2, "lon": 83.6}
        assert "accuracy" not in data["recordSubjectLocation"], (
            "a hand-dropped pin has no error bar, and `coerce_value` keeps the key optional so "
            "that 'somebody pointed at this' and 'a device measured this' stay distinguishable"
        )
        for provenance in ("22.314", "87.311", "Kharagpur", "Plot 14"):
            assert provenance not in str(data), (
                f"{provenance!r} is provenance — the desk the record was typed at — and must never "
                "cross as part of an address"
            )

    half = _location_row(subjectLatitude=21.2, subjectLongitude=None)
    assert dw._subject_point(half) is None, "half a coordinate is not a coordinate"


async def test_a_field_promoted_to_rich_text_still_receives_the_records_prose(monkeypatch):
    from app.services import rich_text

    product = await _hydrate(
        monkeypatch, "existingProduct", {"productRef": "prd_1"},
        rows={"productdocumentation": [_product_row()]},
    )
    tool = await _hydrate(
        monkeypatch, "tool", {"toolRef": "tul_1"}, rows={"tooldocumentation": [_tool_row()]},
    )
    process = await _hydrate(
        monkeypatch, "traditionalProcess", {"processRef": "prc_1"},
        rows={"process": [_process_row()]},
    )
    for entity_key, data, field_key, expected in (
        ("existingProduct", product, "material", "Cotton yarn"),
        ("existingProduct", product, "mainToolsUsed", "Pit loom, bobbin winder"),
        ("existingProduct", product, "use", "Daily and festive wear"),
        ("existingProduct", product, "remarks", "Second-quality weft in one panel."),
        ("tool", tool, "improvements", "A higher bench would ease the back."),
        ("tool", tool, "remarks", "Rebuilt in 2019."),
        ("traditionalProcess", process, "documentedProcessNotes",
         "Yarn is tied in sections, dyed, untied, washed."),
    ):
        spec = _field(entity_key, field_key)
        assert spec.type is FieldType.RICH_TEXT, f"{entity_key}.{field_key} is the field under test"
        stored = data.get(field_key)
        assert stored, f"{entity_key}.{field_key} arrived empty after the promotion"
        assert rich_text.to_plain(rich_text.from_json(stored)).strip() == expected


async def test_re_pointing_at_a_thinly_documented_record_clears_all_of_the_new_fields(
    monkeypatch,
):
    thin = _artisan_row(
        id="art_2", name="Sita Bai", localName=None, gender=None, phone=None, email=None,
        aadhaarFingerprint=None, aadhaarLast4=None, pehchanCardNumber=None, address=None, notes=None, dos=None,
        donts=None, extraMetadata={}, craft=None, location=None, place="Kutch",
        workshop=None, media=[],
    )
    filled = await _hydrate(
        monkeypatch, "participant", {"artisanRef": "art_1"},
        rows={"artisan": [_artisan_row(), thin]},
        photos={"art_1": ("med_1", "At her loom")},
    )
    assert filled["phone"] and filled["photo"]

    re_pointed = await _hydrate(
        monkeypatch, "participant", {**filled, "artisanRef": "art_2"},
        rows={"artisan": [_artisan_row(), thin]},
        photos={"art_1": ("med_1", "At her loom")},
        previous={"artisanRef": "art_1"},
    )
    assert re_pointed["name"] == "Sita Bai"
    assert re_pointed["village"] == "Kutch", "what the new record DOES say still overwrites"
    stale = sorted(
        t for t in _targets("participant.artisanRef")
        if t not in {"name", "village", "pehchanCardAvailable", "documentedOn"}
        and re_pointed.get(t)
    )
    assert stale == [], f"these still hold the previous artisan's answers: {stale}"


async def test_the_designers_own_answers_survive_the_widening(monkeypatch):
    typed = {
        "artisanRef": "art_1", "name": "Latha (Ammaji)", "email": "ammaji@example.org",
        "district": "Bolangir", "dos": "1. Ask before recording",
        "aadhaarNumber": "2345 6789 0123",
    }
    data = await _hydrate(
        monkeypatch, "participant", typed, rows={"artisan": [_artisan_row()]},
        previous={"artisanRef": "art_1"},
    )
    for key, value in typed.items():
        assert data[key] == value, f"{key} was reverted to the record's answer"
    assert data["state"] == "Odisha", "the blanks are still filled in"



def test_every_carried_photograph_carries_its_caption():
    for path, photo_target in (
        ("participant.artisanRef", "photo"),
        ("tool.toolRef", "photo"),
        ("existingProduct.productRef", "productPhotos"),
    ):
        entity_key = path.partition(".")[0]
        mapping = REFERENCE_HYDRATION[path]
        assert mapping.get("photo") == photo_target
        caption_target = mapping.get("photoCaption")
        assert caption_target, f"{path} carries a photograph and not its caption"
        spec = _field(entity_key, caption_target)
        assert spec.caption_for == photo_target, (
            f"{entity_key}.{caption_target} is not declared as the caption of {photo_target}"
        )


class _CapturingDb:
    def __init__(self):
        self.sql = ""
        self.args = ()

    async def query_raw(self, sql, *args):
        self.sql = sql
        self.args = args
        return []


@pytest.mark.parametrize(
    ("model", "column"),
    [("ToolDocumentation", "toolId"), ("ProductDocumentation", "productId")],
)
async def test_the_measurement_grid_shot_is_not_the_records_photograph(monkeypatch, model, column):
    fake = _CapturingDb()
    monkeypatch.setattr(dw, "db", fake)
    await dw._reference_photos(dw.REFERENCE_MODELS[model], ["rec_1"])
    sql = fake.sql

    assert f'm."{column}"' in sql, "the statement must still group by this model's parent key"

    assert dw.MEASUREMENT_GRID_PURPOSE == "MEASUREMENT_GRID"
    assert '"extraMetadata"' in sql and "'purpose'" in sql
    assert "'MEASUREMENT_GRID'" in sql

    with pytest.raises(ValueError, match="measurement-grid marker"):
        bad = _CapturingDb()
        monkeypatch.setattr(dw, "db", bad)
        monkeypatch.setattr(dw, "MEASUREMENT_GRID_PURPOSE", "GRID' OR TRUE--")
        await dw._reference_photos(dw.REFERENCE_MODELS[model], ["rec_1"])
    assert bad.sql == "", "the unvetted marker reached the statement before the guard ran"

    assert "grid (measurement) for" in sql
    assert "'grid-%'" in sql and "'measure-grid-%'" in sql

    where = sql[sql.index("WHERE"):sql.index(") SELECT DISTINCT ON (parent)")]
    assert "MEASUREMENT_GRID" not in where and "grid-" not in where and "$2" not in where, (
        f"the grid rule leaked into the WHERE clause, so a grid-only record now hydrates a blank "
        f"gallery: {where}"
    )
    assert "is_grid" not in where, f"the computed flag must stay a sort key: {where}"

    order = sql[sql.rindex("ORDER BY"):].strip()
    assert order == "ORDER BY parent, is_grid ASC, created_at ASC, id ASC", (
        f"the grid frame no longer sorts last, or the deterministic tiebreak is gone: {order}"
    )


def test_a_reviewers_verdict_is_visible_while_choosing_and_is_never_copied_onto_the_entry():
    spec = dw.REFERENCE_MODELS["ToolDocumentation"]

    assert "Rejected" in spec.sublabel(_tool_row(status="REJECTED"))
    assert "Sent back" in spec.sublabel(_tool_row(status="NEEDS_REVISION"))
    assert "Awaiting review" in spec.sublabel(_tool_row(status="PENDING"))

    approved = spec.sublabel(_tool_row(status="APPROVED"))
    assert approved == spec.sublabel(_tool_row()), "an approved record must read as it always did"
    assert "Rejected" not in approved and "review" not in approved

    assert "status" not in dw.REFERENCE_MODELS["ToolDocumentation"].data(_tool_row(), None)
    assert "REJECTED" not in str(
        dw.REFERENCE_MODELS["ToolDocumentation"].data(_tool_row(status="REJECTED"), None)
    )


def test_no_reference_model_joins_a_relation_none_of_its_lambdas_reads():
    readers = {
        "Artisan": {
            "craft": "participant.specialisation",
            "location": "the STATED address columns and the subject pin",
            "workshop": "participant.documentedAtWorkshop (Workshop.title)",
            "media": "participant.recordMediaNote, counted by type in _media_note",
        },
        "ProductDocumentation": {
            "location": "existingProduct.recordState/District/Village/Pincode/SubjectLocation",
            "media": "existingProduct.recordMediaNote",
        },
        "ToolDocumentation": {
            "location": "tool.recordState/District/Village/Pincode/SubjectLocation",
            "media": "tool.recordMediaNote, including the STAGE_STEP_ making sequence",
            "artisanLinks": "tool.usedByArtisans, via _linked_artisan_names",
        },
        "Process": {
            "product": "processStep.documentedFor / traditionalProcess.documentedFor",
            "steps": "traditionalProcess.documentedSteps, via _step_lines",
        },
        "Craft": {
            "media": "workshopSetup.craftMediaNote",
            "workshop": "workshopSetup.craftDocumentedAtWorkshop (Workshop.title)",
            "subCategory": "the picker's sublabel, \"Category › Sub-category\" (_craft_path)",
        },
        "QuestionnaireInterview": {
            "responses": (
                "artisanBaseline.interviewSectionsCovered / interviewQuestionsAnswered / "
                "interviewLastAnsweredOn — three counts, via _interview_* helpers. The nested "
                "`question` supplies the denormalised sectionCode and nothing else."
            ),
            "artisans": (
                "artisanBaseline.interviewArtisanCount, and the picker sublabel's artisan phrase. "
                "A COUNT and never the names — see _interview_artisan_count."
            ),
            "media": (
                "artisanBaseline.interviewMediaNote — the only thing that can say the AUDIO "
                "RECORDING of the sitting exists, since _reference_photos resolves one IMAGE and "
                "this model declares no media_field at all."
            ),
            "workshop": (
                "artisanBaseline.interviewDocumentedAtWorkshop (Workshop.title). Needed even though "
                "the field is WORKSHOP-scoped: the scoped:false fallback serves the whole table on "
                "an unlinked design workshop, so an out-of-cluster sitting can legitimately be "
                "picked and the printed row must say where it came from."
            ),
        },
    }
    for model, spec in dw.REFERENCE_MODELS.items():
        assert set(spec.include or {}) == set(readers.get(model, {})), (
            f"REFERENCE_MODELS[{model!r}].include and this test disagree about which relations are "
            "joined. An include with no reader is a join issued on every picker keystroke for a "
            "value nobody looks at; name the reader here, or drop the include."
        )


def test_the_photograph_lookup_carries_nothing_a_media_entitlement_would_gate():
    assert {f.name for f in dw.ReferencePhoto.__dataclass_fields__.values()} == {"id", "caption"}




def _formatted(*paragraphs: str) -> str:
    from app.services import rich_text

    blocks = [
        {
            "kind": "PARAGRAPH",
            "spans": [{"text": text, "marks": ["BOLD"]} if index == 0 else {"text": text}],
        }
        for index, text in enumerate(paragraphs)
    ]
    stored = rich_text.to_stored_text(rich_text.from_json({"blocks": blocks}))
    assert stored is not None
    return stored


def test_a_formatted_column_is_json_in_the_database_so_the_premise_holds():
    from app.services import rich_text

    stored = _formatted("Tied with cotton thread", "then dipped in indigo.")
    assert stored.startswith("{")
    assert "blocks" in stored
    assert rich_text.stored_text_document(stored) is not None


@pytest.mark.parametrize(
    ("model", "delegate", "row_builder", "column", "entity_key", "ref_key", "target"),
    [
        ("Process", "process", "_process_row", "notes",
         "processStep", "processRef", "description"),
        ("Process", "process", "_process_row", "notes",
         "traditionalProcess", "processRef", "documentedProcessNotes"),
        ("ProductDocumentation", "productdocumentation", "_product_row", "rawMaterialsUsed",
         "existingProduct", "productRef", "material"),
        ("ProductDocumentation", "productdocumentation", "_product_row", "remarks",
         "existingProduct", "productRef", "remarks"),
        ("ProductDocumentation", "productdocumentation", "_product_row", "mainToolsUsed",
         "existingProduct", "productRef", "mainToolsUsed"),
        ("ProductDocumentation", "productdocumentation", "_product_row", "productFunctionUse",
         "existingProduct", "productRef", "use"),
        ("ToolDocumentation", "tooldocumentation", "_tool_row", "remarks",
         "tool", "toolRef", "remarks"),
        ("ToolDocumentation", "tooldocumentation", "_tool_row", "suggestionsForToolImprovement",
         "tool", "toolRef", "improvements"),
        ("Artisan", "artisan", "_artisan_row", "notes",
         "participant", "artisanRef", "recordNotes"),
    ],
)
async def test_a_formatted_record_column_arrives_as_prose_and_never_as_json(
    monkeypatch, model, delegate, row_builder, column, entity_key, ref_key, target
):
    stored = _formatted("Tied with cotton thread", "then dipped in indigo.")
    row = globals()[row_builder](**{column: stored})
    sent = {ref_key: row.id}
    if entity_key == "processStep":
        sent["stepNumber"] = 1

    data = await _hydrate(monkeypatch, entity_key, sent, rows={delegate: [row]})

    landed = data.get(target)
    assert landed, f"{model}.{column} reached {entity_key}.{target} as nothing at all"
    if _field(entity_key, target).type is FieldType.RICH_TEXT:
        from app.services import rich_text

        assert isinstance(landed, dict), (
            f"{entity_key}.{target} is RICH_TEXT, so coerce_value must have normalised the carried "
            f"prose into a document; it stored {landed!r}"
        )
        assert rich_text.to_plain(rich_text.from_json(landed)).strip() == (
            "Tied with cotton thread\nthen dipped in indigo."
        )
        return
    assert "blocks" not in landed, (
        f"{model}.{column} reached {entity_key}.{target} as raw JSON: {landed!r}. "
        "design_workshops._reference_data is what flattens it; see its docstring."
    )
    assert landed == "Tied with cotton thread\nthen dipped in indigo."


def test_an_unformatted_column_leaves_the_flattening_boundary_untouched():
    prose = "Dyed twice.  Second dip is  longer.\n"
    row = _process_row(notes=prose)
    data = dw._reference_data(dw.REFERENCE_MODELS["Process"], row, None)
    assert data["notes"] == prose
    assert data["notes"] is row.notes, (
        "a plain string must come back as the same object, not as one that survived a round trip"
    )


async def test_the_subject_pin_is_not_flattened_into_a_string(monkeypatch):
    row = _artisan_row(notes=_formatted("Prefers the afternoon."))
    data = await _hydrate(
        monkeypatch, "participant", {"artisanRef": row.id}, rows={"artisan": [row]}
    )
    assert data["recordNotes"] == "Prefers the afternoon."
    assert isinstance(data.get("subjectLocation"), dict), (
        "the artisan's own pin must survive the prose flattening as the point it is"
    )
    assert data["subjectLocation"]["lat"]
    assert data["subjectLocation"]["lon"]


def test_no_new_table_column_was_added_to_a_table_whose_widths_are_already_full():
    for entity_key in ("participant", "tool", "processStep"):
        entity = _entity(entity_key)
        widths = [f.column_width_pct for f in entity.fields
                  if f.report_role.value == "TABLE_COLUMN" and not f.deprecated]
        assert sum(widths) <= 100.0 + 1e-6, (
            f"{entity_key}'s table column widths now sum to {sum(widths)}"
        )


class _NonGoverning(NamedTuple):
    declared_sum: float
    handset: tuple[float, ...]


_WIDTHS_THAT_DO_NOT_GOVERN = {
    "sketch": _NonGoverning(88.0, (13.6364, 27.2727, 20.4545, 20.4545, 18.1818)),
    "prototype": _NonGoverning(112.0, (10.7143, 21.4286, 14.2857, 17.8571, 25.0, 10.7143)),
    "prototypeValidation": _NonGoverning(
        86.0, (23.2558, 16.2791, 15.1163, 15.1163, 15.1163, 15.1163)
    ),
    "finalProduct": _NonGoverning(120.0, (10.0, 20.0, 15.0, 18.3333, 20.0, 16.6667)),
    "followUp": _NonGoverning(88.0, (22.7273, 13.6364, 15.9091, 22.7273, 13.6364, 11.3636)),
}


def _governing_widths(entity):
    columns = [f for f in entity.fields
               if f.report_role.value == "TABLE_COLUMN" and not f.deprecated][:6]
    return columns, sum(f.column_width_pct for f in columns)


def test_the_tables_whose_declared_widths_govern_still_do():
    drifted = []
    for _stage, entity in all_entities():
        columns, total = _governing_widths(entity)
        if not columns or not total:
            continue
        governs = abs(total - 100.0) < 0.5
        recorded = _WIDTHS_THAT_DO_NOT_GOVERN.get(entity.key)
        if governs:
            assert recorded is None, (
                f"{entity.key} now adds up to 100 and its declared widths govern again — "
                "delete its line from _WIDTHS_THAT_DO_NOT_GOVERN"
            )
            continue
        if recorded is None:
            drifted.append(f"{entity.key} (sums to {total})")
        else:
            assert abs(total - recorded.declared_sum) < 0.5, (
                f"{entity.key}'s widths have moved from {recorded.declared_sum} to {total} while "
                "still not governing — rebalance them to 100 rather than to another number "
                "nothing reads"
            )
    assert drifted == [], (
        "these tables' declared widths have stopped governing, so report_builder is laying them "
        f"out proportionally and the percentages beside their fields do nothing: {drifted}"
    )


def _server_table_widths(columns):
    declared = sum(c.column_width_pct for c in columns)
    if declared and abs(declared - 100.0) < 0.5:
        return [c.column_width_pct for c in columns]
    weights = [2.0 if c.is_free_text else 1.0 for c in columns]
    widths = [100.0 * w / sum(weights) for w in weights]
    widths[-1] += 100.0 - sum(widths)
    return widths


def _handset_table_widths(columns):
    hinted = sum(c.column_width_pct for c in columns)
    unhinted = sum(1 for c in columns if c.column_width_pct <= 0)
    share = max((100.0 - hinted) / unhinted, 4.0) if unhinted else 0.0
    widths = [c.column_width_pct if c.column_width_pct > 0 else share for c in columns]
    scaled = [w * 100.0 / sum(widths) for w in widths]
    scaled[-1] = 100.0 - sum(scaled[:-1])
    return scaled


def test_the_two_surfaces_lay_out_the_same_table_the_same_way():
    disagreed = []
    for _stage, entity in all_entities():
        columns, declared = _governing_widths(entity)
        if not columns or not declared:
            continue
        server = _server_table_widths(columns)
        handset = _handset_table_widths(columns)
        recorded = _WIDTHS_THAT_DO_NOT_GOVERN.get(entity.key)
        if recorded is None:
            if any(abs(a - b) > 0.1 for a, b in zip(server, handset, strict=True)):
                disagreed.append(
                    f"{entity.key}: server {[round(x, 4) for x in server]} vs handset "
                    f"{[round(x, 4) for x in handset]}"
                )
            continue
        assert len(recorded.handset) == len(columns), (
            f"{entity.key} now draws {len(columns)} columns and _WIDTHS_THAT_DO_NOT_GOVERN records "
            f"{len(recorded.handset)} — re-record the handset vector beside its declared sum"
        )
        assert all(abs(a - b) < 0.01 for a, b in zip(recorded.handset, handset, strict=True)), (
            f"{entity.key}'s handset layout has moved to {[round(x, 4) for x in handset]}; the "
            f"office still draws {[round(x, 4) for x in server]}, so the gap between the two "
            "documents has changed size. Re-record it here or close it on the printed page."
        )
        assert any(abs(a - b) > 0.1 for a, b in zip(server, handset, strict=True)), (
            f"{entity.key}'s two surfaces now agree — rebalance is done, so delete its line from "
            "_WIDTHS_THAT_DO_NOT_GOVERN"
        )
    assert disagreed == [], (
        "these entities' tables are now laid out differently by the server and the handset, and "
        "the divergence is not recorded in _WIDTHS_THAT_DO_NOT_GOVERN, so one workshop's report "
        f"wraps different cells depending on which machine built it: {disagreed}"
    )


def test_the_hydration_table_grew_and_no_pair_was_lost():
    for path, source, target in (
        ("workshopSetup.craftRef", "craftName", "craftName"),
        ("participant.artisanRef", "specialisation", "specialisation"),
        ("tool.toolRef", "usedFor", "usedFor"),
        ("processStep.processRef", "notes", "description"),
        ("existingProduct.artisanRef", "name", "artisanName"),
        ("existingProduct.productRef", "price", "price"),
        ("prototype.productRef", "name", "productName"),
    ):
        assert REFERENCE_HYDRATION[path][source] == target

    pairs = sum(len(m) for m in REFERENCE_HYDRATION.values())
    assert pairs >= 135, (
        f"the carry is down to {pairs} field-pairs; it was 32 before this lane, 107 as measured on "
        "2026-08-22, 109 on 2026-08-24 and 135 on 2026-09-03, and a drop means a mapping was "
        "removed rather than a source column disappearing"
    )
    assert REFERENCE_HYDRATION["finalProduct.prototypeRef"]["materials"] == "materials"
    assert REFERENCE_HYDRATION["prototypeValidation.prototypeRef"]["lengthCm"] == "finalLengthCm"

def test_no_reference_model_asks_prisma_for_a_media_relation_its_delegate_does_not_have():
    schema = SCHEMA.read_text(encoding="utf-8")

    with_relation = {
        name.lower()
        for name, body in re.findall(r"^model\s+(\w+)\s*\{(.*?)^\}", schema, re.DOTALL | re.MULTILINE)
        if re.search(r"^\s+media\s+MediaFile\[\]", body, re.MULTILINE)
    }
    assert "artisan" in with_relation, (
        "sanity check failed: Artisan should declare `media MediaFile[]`, so either the regex or "
        f"the schema changed shape. Parsed: {sorted(with_relation)}"
    )

    offenders = []
    for model_name, spec in dw.REFERENCE_MODELS.items():
        include = getattr(spec, "include", None) or {}
        if not _asks_for_media(include):
            continue
        if spec.delegate.lower() not in with_relation:
            offenders.append(f"{model_name} (delegate={spec.delegate!r})")

    assert not offenders, (
        "these reference models ask Prisma for a `media` relation their delegate does not declare, "
        f"which makes the whole picker query raise UnknownRelationalFieldError: {offenders}. Either "
        "give the model a real foreign key on MediaFile (a migration plus a backfill from the "
        "existing linkedRecordType tags — the cost is written out above REFERENCE_MODELS['Process']"
        "'s include) or drop the include. It cannot be replaced by a media QUERY: see the TRIED AND "
        "REFUSED section of _reference_media_note."
    )


def _asks_for_media(include: dict) -> bool:
    for key, value in include.items():
        if key == "media" and value:
            return True
        if isinstance(value, dict) and _asks_for_media(value.get("include") or {}):
            return True
    return False


_A_JUDGEMENT_NOT_A_DESCRIPTION = (
    "stage 15 is a JUDGEMENT of a prototype — a decision, five quality ratings, two approvals, a "
    "reason and a checklist — and the row is not a second description of the piece. The three "
    "measurements are the only boxes that re-ask something the prototype states."
)

_MEDIA_STAYS_WHERE_IT_WAS_TAKEN = (
    "media never crosses on an INTERNAL carry: both rows are in ONE workshop, so the copy would be "
    "the SAME media ids under two headings and report_builder's image pass dedupes by media id — "
    "the report would print the working shot as the catalogue plate, or print one picture twice."
)

PROTOTYPE_TO_FINAL_PRODUCT_CARRIED = {
    "name": "finalProduct.name",
    "materials": "finalProduct.materials",
    "lengthCm": "finalProduct.lengthCm",
    "widthCm": "finalProduct.widthCm",
    "heightCm": "finalProduct.heightCm",
    "weightG": "finalProduct.weightG",
    "dimensionsNote": "finalProduct.dimensionsNote",
    "makingTimeDays": "finalProduct.makingTimeDays",
    "processSummary": "finalProduct.makingProcess",
}
PROTOTYPE_TO_FINAL_PRODUCT_NOT_CARRIED = {
    "prototypeCode": (
        "two identifiers with two lifetimes. A prototype tag is printed the afternoon the piece is "
        "made (workshopCodeIdForRow) and a product code goes on a catalogue; a carry would make the "
        "catalogue assert the workshop's internal tag and only-fill-blanks would keep it."
    ),
    "sketchRef": "a link, not a fact. finalProduct declares no sketch picker for it to land in",
    "artisanRef": (
        "THE SAME QUESTION, AND THE BOX IS STILL REFUSED. The person who made the prototype made "
        "this, so the fact carries — but a hydrated REF needs a name box on the receiving entity to "
        "resolve through and finalProduct declares none, so the picker would show a chosen id with "
        "nothing readable beside it. Give finalProduct an artisanName and this becomes ordinary."
    ),
    "productRef": "a link to a ProductDocumentation, and finalProduct has no picker for one",
    "productName": (
        "“Developed from” — the EXISTING product a prototype reworks. A catalogue record "
        "of the new piece has no box that means the old one, and would misread it as its own name."
    ),
    "prototypePhotos": _MEDIA_STAYS_WHERE_IT_WAS_TAKEN,
    "prototypePhotosCaption": _MEDIA_STAYS_WHERE_IT_WAS_TAKEN,
    "diameterCm": (
        "no target. finalProduct declares length, width, height and weight and no diameter; the "
        "free-text dimensionsNote is what a round piece is described in, and it IS carried."
    ),
    "toolsUsed": (
        "a LIST OF TOOLS is not a TECHNIQUE. finalProduct.technique is the named craft operation "
        "(“bandha tie-dye”), and writing a tool list into it would print a product's "
        "technique as “pit loom, bobbin winder” in a catalogue."
    ),
    "materialCost": (
        "TWO SOURCE HEADS, ONE TARGET BOX. finalProduct holds one costPrice; a mapping is one key "
        "to one key and cannot sum, and synthesising materials+labour would assert a cost that "
        "stage 17's sheet then contradicts with four more heads. See costSheet.totalCost's SUM."
    ),
    "labourCost": "the other half of the same arithmetic limit — see materialCost above",
    "problemsAndModifications": (
        "the record of what went WRONG while making it. A catalogue paragraph is not the place, "
        "and finalProduct.description is the copy a buyer reads."
    ),
    "startDate": "when making began. The catalogue records the piece, not its schedule",
    "completedDate": "as startDate — and makingTimeDays, the figure a costing can use, IS carried",
    "measurementSheet": _MEDIA_STAYS_WHERE_IT_WAS_TAKEN,
    "processVideo": _MEDIA_STAYS_WHERE_IT_WAS_TAKEN,
    "audioNarration": _MEDIA_STAYS_WHERE_IT_WAS_TAKEN,
    "audioTranscript": (
        "the artisan's spoken account of making the piece, transcribed. It is a DIFFERENT VOICE "
        "from the catalogue's makingProcess, which the designer writes — the distinction "
        "documentedCraftNotes vs craftIntroduction is made for on the external side."
    ),
    "turntablePhotos": _MEDIA_STAYS_WHERE_IT_WAS_TAKEN,
    "modelFile": _MEDIA_STAYS_WHERE_IT_WAS_TAKEN,
    "rankFixedBy": "who settled the prototypes' order. A property of the LIST, not of the piece",
    "rankFixedAt": "as rankFixedBy",
    "peerRoundClosedAt": (
        "the day the prototype was opened to designers outside the workshop. A review-process "
        "state, and it belongs to the prototype row that design_ratings reads it off."
    ),
}

PROTOTYPE_TO_VALIDATION_CARRIED = {
    "lengthCm": "prototypeValidation.finalLengthCm",
    "widthCm": "prototypeValidation.finalWidthCm",
    "heightCm": "prototypeValidation.finalHeightCm",
}
PROTOTYPE_TO_VALIDATION_NOT_CARRIED = {
    "prototypeCode": _A_JUDGEMENT_NOT_A_DESCRIPTION,
    "name": (
        "NO BOX, and deliberately no box: prototypeValidation's label_field is prototypeRef itself, "
        "so the row is titled by the picker's own resolved label rather than by a copied name."
    ),
    "sketchRef": _A_JUDGEMENT_NOT_A_DESCRIPTION,
    "artisanRef": _A_JUDGEMENT_NOT_A_DESCRIPTION,
    "productRef": _A_JUDGEMENT_NOT_A_DESCRIPTION,
    "productName": _A_JUDGEMENT_NOT_A_DESCRIPTION,
    "materials": _A_JUDGEMENT_NOT_A_DESCRIPTION,
    "prototypePhotos": _MEDIA_STAYS_WHERE_IT_WAS_TAKEN,
    "prototypePhotosCaption": _MEDIA_STAYS_WHERE_IT_WAS_TAKEN,
    "diameterCm": (
        "the one measurement with no “Final…” counterpart on this entity. Three "
        "boxes are declared and three are carried; a round piece's diameter has nowhere to go, and "
        "inventing a fourth box to receive it is a registry decision rather than a carry."
    ),
    "weightG": "no target — stage 15 declares three FINAL dimensions and no weight",
    "dimensionsNote": "no target — the three numeric boxes are the whole of what stage 15 asks",
    "toolsUsed": _A_JUDGEMENT_NOT_A_DESCRIPTION,
    "processSummary": _A_JUDGEMENT_NOT_A_DESCRIPTION,
    "makingTimeDays": _A_JUDGEMENT_NOT_A_DESCRIPTION,
    "materialCost": _A_JUDGEMENT_NOT_A_DESCRIPTION,
    "labourCost": _A_JUDGEMENT_NOT_A_DESCRIPTION,
    "problemsAndModifications": (
        "the MAKER's record of what went wrong, and prototypeValidation.reason is the REVIEWER's "
        "record of why they decided as they did. Two authors, and only-fill-blanks would put the "
        "first where the second belongs on every row a reviewer had not written yet — the same "
        "refusal craftIntroduction and documentedCraftNotes are separated by."
    ),
    "startDate": _A_JUDGEMENT_NOT_A_DESCRIPTION,
    "completedDate": (
        "when the piece was finished, which is not when it was APPROVED. approvalDate is a "
        "reviewer's date and filling it from the maker's would misdate a named approval."
    ),
    "measurementSheet": _MEDIA_STAYS_WHERE_IT_WAS_TAKEN,
    "processVideo": _MEDIA_STAYS_WHERE_IT_WAS_TAKEN,
    "audioNarration": _MEDIA_STAYS_WHERE_IT_WAS_TAKEN,
    "audioTranscript": _MEDIA_STAYS_WHERE_IT_WAS_TAKEN,
    "turntablePhotos": _MEDIA_STAYS_WHERE_IT_WAS_TAKEN,
    "modelFile": _MEDIA_STAYS_WHERE_IT_WAS_TAKEN,
    "rankFixedBy": (
        "who settled the prototypes' ORDER, which is not who approved this one. approvedBy is a "
        "reviewer's name and a carry here would attribute an approval to whoever fixed a sort."
    ),
    "rankFixedAt": "as rankFixedBy",
    "peerRoundClosedAt": (
        "the gate that decides whether a POOL round is open at all (design_ratings.pool_is_open). "
        "reviewRound is a designer's description of the row they are filing; copying the gate into "
        "it would make a date look like an audience."
    ),
}

INTERNAL_LEDGER = [
    (
        "finalProduct.prototypeRef",
        "prototype",
        PROTOTYPE_TO_FINAL_PRODUCT_CARRIED,
        PROTOTYPE_TO_FINAL_PRODUCT_NOT_CARRIED,
    ),
    (
        "prototypeValidation.prototypeRef",
        "prototype",
        PROTOTYPE_TO_VALIDATION_CARRIED,
        PROTOTYPE_TO_VALIDATION_NOT_CARRIED,
    ),
]


def _live_field_keys(entity_key: str) -> set[str]:
    entity = next(e for _s, e in all_entities() if e.key == entity_key)
    return {f.key for f in entity.fields if not f.deprecated}


@pytest.mark.parametrize(
    ("path", "source_entity", "carried", "refused"),
    INTERNAL_LEDGER,
    ids=[p for p, _e, _c, _r in INTERNAL_LEDGER],
)
def test_no_internal_source_field_is_unaccounted_for(path, source_entity, carried, refused):
    fields = _live_field_keys(source_entity)
    accounted = set(carried) | set(refused)
    missing = sorted(fields - accounted)
    assert not missing, (
        f"{path}: {source_entity} has fields this repository has never decided about: {missing}. "
        f"Add each to the CARRIED map for this mapping with the box it reaches, or to the "
        "NOT_CARRIED map with the reason it does not."
    )
    stale = sorted(accounted - fields)
    assert not stale, f"{path}: {source_entity} no longer declares these fields: {stale}"


@pytest.mark.parametrize(
    ("path", "source_entity", "carried", "refused"),
    INTERNAL_LEDGER,
    ids=[p for p, _e, _c, _r in INTERNAL_LEDGER],
)
def test_no_internal_source_field_is_both_carried_and_refused(path, source_entity, carried, refused):
    overlap = sorted(set(carried) & set(refused))
    assert not overlap, f"{path}: {overlap} appear in both lists"


@pytest.mark.parametrize(
    ("path", "source_entity", "carried", "refused"),
    INTERNAL_LEDGER,
    ids=[p for p, _e, _c, _r in INTERNAL_LEDGER],
)
def test_the_internal_ledger_is_the_mapping_and_not_a_description_of_it(
    path, source_entity, carried, refused
):
    mapping = REFERENCE_HYDRATION[path]
    declared = {source: f"{path.partition('.')[0]}.{target}" for source, target in mapping.items()}
    assert declared == carried, (
        f"{path}: the hydration table carries {sorted(declared)} and this ledger claims "
        f"{sorted(carried)}. Every difference is either a carry nobody wrote down or a claim "
        "nothing implements."
    )


def test_every_internal_ref_is_either_hydrated_or_refused_in_writing():
    internal = {
        f"{entity.key}.{field.key}"
        for _stage, entity in all_entities()
        for field in entity.fields
        if field.type is FieldType.REF
        and not field.deprecated
        and any(e.name == field.ref_model for _s, e in all_entities())
    }
    hydrated = {path for path in internal if path in REFERENCE_HYDRATION}
    assert hydrated == {"finalProduct.prototypeRef", "prototypeValidation.prototypeRef"}, (
        "the set of hydrating INTERNAL refs has changed. Add the new one to INTERNAL_LEDGER with "
        "its coverage lists, to the web's DW_REFERENCE_HYDRATION, to StageRecordEmbed's "
        "MIRROR_POINTS or NOT_EMBEDDED, and to the handset's FLOOR."
    )
    assert sorted(internal - hydrated) == [
        "certificate.participantRef",
        "costLabourLine.costSheetRef",
        "costMaterialLine.costSheetRef",
        "costSheet.productRef",
        "finalProduct.artisanRef",
        "followUp.productRef",
        "materialUsage.prototypeRef",
        "plannedFollowUp.productRef",
        "prototype.artisanRef",
        "prototype.sketchRef",
        "prototypeIteration.prototypeRef",
        "prototypeStageLog.prototypeRef",
        "sketch.supersedesSketch",
        "sketchReview.sketchRef",
    ], (
        "an internal REF was added or removed. If it is new, read the refusals above "
        "REFERENCE_HYDRATION's internal-carry section, decide, and write the decision down there "
        "before adding the path here."
    )



class _StageRows:
    def __init__(self, rows):
        self._rows = rows

    async def find_many(self, where=None, include=None, order=None, take=None):
        where = where or {}
        keys = set((where.get("entityKey") or {}).get("in", []))
        out = []
        for row in self._rows:
            if row.designWorkshopId != where.get("designWorkshopId"):
                continue
            if keys and row.entityKey not in keys:
                continue
            if "deletedAt" in where and row.deletedAt is not where["deletedAt"]:
                continue
            out.append(row)
        return out


class _WorkshopDb:
    def __init__(self, rows):
        self.dwstageentry = _StageRows(rows)


def _stage_row(entity_key, row_id, data, **overrides):
    fields = dict(
        id=row_id,
        designWorkshopId="dw_1",
        entityKey=entity_key,
        data=dict(data),
        clientKey=None,
        deletedAt=None,
        createdById="usr_maker",
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


_PROTOTYPE_DATA = {
    "prototypeCode": "PT-01",
    "name": "Bandha tote, wide gusset",
    "materials": ["Cotton", "Jute webbing"],
    "lengthCm": 38.0,
    "widthCm": 12.0,
    "heightCm": 34.0,
    "weightG": 420.0,
    "dimensionsNote": "38 x 12 x 34, handle drop 24",
    "makingTimeDays": 3.5,
    "processSummary": "Warped on the pit loom, tied in four bands.",
    "materialCost": "1200.00",
    "labourCost": "800.00",
    "prototypePhotos": ["med_a", "med_b"],
}


async def _hydrate_internal(monkeypatch, entity_key, sent, *, rows, previous=None, workshop="dw_1"):
    monkeypatch.setattr(dw, "db", _WorkshopDb(rows))
    entry = dw.PendingEntry(
        entity=_entity(entity_key), data=dict(sent), previous=dict(previous or {}),
        row_id=None, ordinal=0, client_key="k1",
    )
    await dw.hydrate_entries([entry], workshop_id=workshop)
    return entry


async def test_a_prototype_fills_the_catalogue_row_that_names_it(monkeypatch):
    entry = await _hydrate_internal(
        monkeypatch,
        "finalProduct",
        {"productCode": "FP-01", "prototypeRef": "ent_proto"},
        rows=[_stage_row("prototype", "ent_proto", _PROTOTYPE_DATA)],
    )

    missing = sorted(k for k in _targets("finalProduct.prototypeRef") if not entry.data.get(k))
    assert missing == [], f"the prototype was fully filled in and these boxes stayed empty: {missing}"
    assert entry.data["name"] == "Bandha tote, wide gusset"
    assert entry.data["materials"] == ["Cotton", "Jute webbing"]
    assert entry.data["lengthCm"] == 38.0
    assert entry.data["makingTimeDays"] == 3.5
    assert isinstance(entry.data["makingProcess"], dict)
    assert "blocks" in entry.data["makingProcess"]
    assert entry.data["productCode"] == "FP-01"
    assert "finalPhotos" not in entry.data
    assert "costPrice" not in entry.data


async def test_the_boxes_the_designer_answered_are_left_exactly_as_they_answered_them(monkeypatch):
    entry = await _hydrate_internal(
        monkeypatch,
        "finalProduct",
        {
            "prototypeRef": "ent_proto",
            "name": "Bandha tote (retail)",
            "lengthCm": 39.5,
            "materials": ["Cotton"],
        },
        rows=[_stage_row("prototype", "ent_proto", _PROTOTYPE_DATA)],
    )

    assert entry.data["name"] == "Bandha tote (retail)"
    assert entry.data["lengthCm"] == 39.5
    assert entry.data["materials"] == ["Cotton"]
    assert entry.data["widthCm"] == 12.0
    assert entry.data["weightG"] == 420.0


async def test_a_prototype_edited_afterwards_does_not_rewrite_a_catalogue_row(monkeypatch):
    edited = dict(_PROTOTYPE_DATA, lengthCm=41.0, name="Bandha tote, MK II", weightG=500.0)
    entry = await _hydrate_internal(
        monkeypatch,
        "finalProduct",
        {
            "prototypeRef": "ent_proto",
            "name": "Bandha tote, wide gusset",
            "lengthCm": 38.0,
            "weightG": 420.0,
        },
        previous={
            "prototypeRef": "ent_proto",
            "name": "Bandha tote, wide gusset",
            "lengthCm": 38.0,
            "weightG": 420.0,
        },
        rows=[_stage_row("prototype", "ent_proto", edited)],
    )

    assert entry.data["name"] == "Bandha tote, wide gusset"
    assert entry.data["lengthCm"] == 38.0
    assert entry.data["weightG"] == 420.0
    assert "name" not in entry.hydrated
    assert "lengthCm" not in entry.hydrated


async def test_re_pointing_at_another_prototype_clears_and_rewrites(monkeypatch):
    thin = {"name": "Bandha runner", "lengthCm": 120.0}
    entry = await _hydrate_internal(
        monkeypatch,
        "finalProduct",
        {
            "prototypeRef": "ent_other",
            "name": "Bandha tote, wide gusset",
            "weightG": 420.0,
            "dimensionsNote": "38 x 12 x 34, handle drop 24",
            "materials": ["Cotton", "Jute webbing"],
        },
        previous={"prototypeRef": "ent_proto"},
        rows=[
            _stage_row("prototype", "ent_proto", _PROTOTYPE_DATA),
            _stage_row("prototype", "ent_other", thin),
        ],
    )

    assert entry.data["name"] == "Bandha runner"
    assert entry.data["lengthCm"] == 120.0
    assert "weightG" not in entry.data
    assert "dimensionsNote" not in entry.data
    assert entry.data["materials"] == ["Cotton", "Jute webbing"]


async def test_a_prototype_from_another_workshop_reaches_nothing(monkeypatch):
    entry = await _hydrate_internal(
        monkeypatch,
        "finalProduct",
        {"prototypeRef": "ent_proto"},
        rows=[_stage_row("prototype", "ent_proto", _PROTOTYPE_DATA, designWorkshopId="dw_2")],
    )
    assert entry.data == {"prototypeRef": "ent_proto"}
    assert entry.hydrated == {}


async def test_a_deleted_prototype_hydrates_nothing_and_disturbs_nothing(monkeypatch):
    entry = await _hydrate_internal(
        monkeypatch,
        "finalProduct",
        {"prototypeRef": "ent_proto", "name": "Bandha tote (retail)"},
        rows=[
            _stage_row(
                "prototype", "ent_proto", _PROTOTYPE_DATA, deletedAt=datetime(2026, 8, 1, 9, 0)
            )
        ],
    )
    assert entry.data == {"prototypeRef": "ent_proto", "name": "Bandha tote (retail)"}


async def test_a_prototype_addressed_by_the_key_the_handset_generated_still_resolves(monkeypatch):
    entry = await _hydrate_internal(
        monkeypatch,
        "finalProduct",
        {"prototypeRef": "local-proto-7"},
        rows=[_stage_row("prototype", "ent_proto", _PROTOTYPE_DATA, clientKey="local-proto-7")],
    )
    assert entry.data["name"] == "Bandha tote, wide gusset"


async def test_the_validation_row_takes_the_three_measurements_and_none_of_the_verdict(monkeypatch):
    entry = await _hydrate_internal(
        monkeypatch,
        "prototypeValidation",
        {"prototypeRef": "ent_proto", "decision": "APPROVED"},
        rows=[_stage_row("prototype", "ent_proto", _PROTOTYPE_DATA)],
    )
    assert entry.data["finalLengthCm"] == 38.0
    assert entry.data["finalWidthCm"] == 12.0
    assert entry.data["finalHeightCm"] == 34.0
    assert entry.data["decision"] == "APPROVED"
    for key in ("reason", "approvedBy", "approvalDate", "technicalQuality"):
        assert key not in entry.data


async def test_a_carried_value_is_stamped_with_the_row_it_came_from(monkeypatch):
    entry = await _hydrate_internal(
        monkeypatch,
        "finalProduct",
        {"prototypeRef": "ent_proto"},
        rows=[_stage_row("prototype", "ent_proto", _PROTOTYPE_DATA, createdById="usr_maker")],
    )
    stamp = entry.hydrated["dimensionsNote"]
    assert stamp.model == "DwPrototype"
    assert stamp.record_id == "ent_proto"
    assert stamp.source_key == "dimensionsNote"
    assert stamp.author_id == "usr_maker"


async def test_without_a_workshop_to_scope_to_the_carry_is_skipped_and_says_so(monkeypatch, caplog):
    monkeypatch.setattr(dw, "db", _WorkshopDb([_stage_row("prototype", "ent_proto", _PROTOTYPE_DATA)]))
    entry = dw.PendingEntry(
        entity=_entity("finalProduct"), data={"prototypeRef": "ent_proto"}, previous={},
        row_id=None, ordinal=0, client_key="k1",
    )
    with caplog.at_level("WARNING"):
        await dw.hydrate_entries([entry])

    assert entry.data == {"prototypeRef": "ent_proto"}
    assert any("DwPrototype" in record.getMessage() for record in caplog.records), (
        f"the skip was silent: {[r.getMessage() for r in caplog.records]}"
    )


def test_the_picker_sends_the_keys_the_mapping_reads_and_no_others(monkeypatch):
    assert dw._internal_carry_keys(dw._dw_entity("DwPrototype")) == (
        "name",
        "artisanRef",
        "materials",
        "lengthCm",
        "widthCm",
        "heightCm",
        "weightG",
        "dimensionsNote",
        "processSummary",
        "makingTimeDays",
    )
    assert dw._internal_carry_keys(dw._dw_entity("DwSketch")) == (
        "name",
        "materials",
        "lengthCm",
        "widthCm",
        "heightCm",
    )
    for model in ("DwParticipant", "DwFinalProduct", "DwCostSheet"):
        assert dw._internal_carry_keys(dw._dw_entity(model)) == (), (
            f"{model} started travelling in a picker payload; every REF pointing at it was refused, "
            "so either a mapping was added without its ledger row or this allowlist has widened"
        )
    assert "isTentative" not in dw._internal_carry_keys(dw._dw_entity("DwSketch"))


def test_an_internal_mapping_reading_a_field_the_source_does_not_have_fails_validation(monkeypatch):
    from app.services import stage_schema as stage_schema_module

    monkeypatch.setattr(
        stage_schema_module,
        "REFERENCE_HYDRATION",
        {"finalProduct.prototypeRef": {"lengthInMetres": "lengthCm"}},
    )
    problems = validate_registry()
    assert any("lengthInMetres" in p and "does not declare" in p for p in problems), problems


def test_an_internal_mapping_reading_a_RETIRED_field_fails_validation():
    spec = _field("prototype", "dimensionsNote")
    object.__setattr__(spec, "deprecated", True)
    try:
        problems = validate_registry()
    finally:
        object.__setattr__(spec, "deprecated", False)

    assert any("dimensionsNote" in p and "deprecated" in p for p in problems), problems
    assert validate_registry() == [], "the registry was restored and the guard did not follow"


class _CraftListDb:
    def __init__(self, crafts):
        rows = {c.id: c for c in crafts}

        class _Crafts:
            async def find_many(self, where):
                return [rows[i] for i in where["id"]["in"] if i in rows]

        self.craft = _Crafts()


def _setup_entry(sent, previous=None):
    return dw.PendingEntry(
        entity=_entity("workshopSetup"), data=dict(sent), previous=dict(previous or {}),
        row_id=None, ordinal=0, client_key="k1",
    )


_LISTED = SimpleNamespace(id="crf_active", name="Sambalpuri Ikat", status="ACTIVE")
_UNLISTED = SimpleNamespace(id="crf_legacy", name="Ikat (old spelling)", status="LEGACY")
_FOLDED = SimpleNamespace(id="crf_merged", name="Bandha", status="MERGED")


@pytest.mark.parametrize("craft", [_UNLISTED, _FOLDED, None], ids=["legacy", "merged", "unknown"])
async def test_stage_one_refuses_a_craft_that_is_not_on_the_list(monkeypatch, craft):
    monkeypatch.setattr(dw, "db", _CraftListDb([c for c in (_UNLISTED, _FOLDED) if c]))
    entry = _setup_entry({"craftRef": craft.id if craft else "crf_missing", "craftName": "typed"})
    with pytest.raises(dw.HTTPException) as refused:
        await dw.derive_stage_craft_names([entry])
    assert refused.value.status_code == 422
    assert refused.value.detail["code"] == "CRAFT_NOT_IN_LIST"


async def test_stage_one_keeps_a_craft_it_already_links_whatever_its_status(monkeypatch):
    monkeypatch.setattr(dw, "db", _CraftListDb([_UNLISTED, _LISTED]))
    kept = _setup_entry({"craftRef": "crf_legacy", "craftName": "x"}, previous={"craftRef": "crf_legacy"})
    picked = _setup_entry({"craftRef": "crf_active", "craftName": "typed"}, previous={"craftRef": "crf_legacy"})
    await dw.derive_stage_craft_names([kept, picked])
    assert kept.data["craftName"] == "Ikat (old spelling)"
    assert picked.data["craftName"] == "Sambalpuri Ikat"
