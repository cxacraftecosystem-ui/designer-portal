from __future__ import annotations

import math
import re
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from enum import Enum
from typing import Any

from app.services.address import normalize_pincode, pincode_error
from app.services.artisan_identity import (
    aadhaar_error,
    aadhaar_or_mask_error,
    mask_aadhaar,
    normalize_aadhaar,
    normalize_pehchan,
    pehchan_error,
)
from app.services.contact_formats import email_error, normalize_email, phone_error
from app.services.report_model import clean_text
from app.services.report_theme import ACCENT_PRESET_ENUM, FONT_PRESET_ENUM
from app.services.text_format import by_count


class Tier(str, Enum):
    BASIC = "BASIC"
    STANDARD = "STANDARD"
    ADVANCED = "ADVANCED"

    @property
    def rank(self) -> int:
        return {"BASIC": 0, "STANDARD": 1, "ADVANCED": 2}[self.value]


class FieldType(str, Enum):
    TEXT = "TEXT"
    LONG_TEXT = "LONG_TEXT"
    RICH_TEXT = "RICH_TEXT"
    INT = "INT"
    DECIMAL = "DECIMAL"
    MONEY = "MONEY"
    PERCENT = "PERCENT"
    DATE = "DATE"
    TIME = "TIME"
    BOOL = "BOOL"
    ENUM = "ENUM"
    MULTI_ENUM = "MULTI_ENUM"
    TAGS = "TAGS"
    IMAGE = "IMAGE"
    IMAGE_LIST = "IMAGE_LIST"
    FILE = "FILE"
    AUDIO = "AUDIO"
    VIDEO = "VIDEO"
    GEO = "GEO"
    REF = "REF"
    URL = "URL"
    PHONE = "PHONE"
    EMAIL = "EMAIL"

    @property
    def is_media(self) -> bool:
        return self in {
            FieldType.IMAGE,
            FieldType.IMAGE_LIST,
            FieldType.FILE,
            FieldType.AUDIO,
            FieldType.VIDEO,
        }

    @property
    def is_numeric(self) -> bool:
        return self in {FieldType.INT, FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT}

    @property
    def is_multi(self) -> bool:
        return self in {FieldType.MULTI_ENUM, FieldType.TAGS, FieldType.IMAGE_LIST}


class ReportRole(str, Enum):
    NARRATIVE = "NARRATIVE"
    KEY_VALUE = "KEY_VALUE"
    TABLE_COLUMN = "TABLE_COLUMN"
    CAPTION = "CAPTION"
    GALLERY = "GALLERY"
    COVER_FIELD = "COVER_FIELD"
    METRIC = "METRIC"
    BULLETS = "BULLETS"
    HIDDEN = "HIDDEN"


class TextFormat(str, Enum):
    NONE = ""
    EMAIL = "EMAIL"
    PHONE_IN = "PHONE_IN"
    AADHAAR = "AADHAAR"
    PEHCHAN = "PEHCHAN"
    PINCODE = "PINCODE"


def _no_format_error(_text: str) -> str | None:
    return None


def _email_format_error(text: str) -> str | None:
    return email_error(normalize_email(text))


def _aadhaar_format_error(text: str) -> str | None:
    if normalize_aadhaar(text) is None:
        return aadhaar_error(text)
    return aadhaar_or_mask_error(text)


def _pehchan_format_error(text: str) -> str | None:
    normalised = normalize_pehchan(text)
    if normalised is None:
        return pehchan_error(text)
    return pehchan_error(normalised)


def _pincode_format_error(text: str) -> str | None:
    normalised = normalize_pincode(text)
    if normalised is None:
        return pincode_error(text)
    return pincode_error(normalised)


_FORMATS: dict[TextFormat, Callable[[str], str | None]] = {
    TextFormat.NONE: _no_format_error,
    TextFormat.EMAIL: _email_format_error,
    TextFormat.PHONE_IN: phone_error,
    TextFormat.AADHAAR: _aadhaar_format_error,
    TextFormat.PEHCHAN: _pehchan_format_error,
    TextFormat.PINCODE: _pincode_format_error,
}

_unenforced = [member.name for member in TextFormat if member not in _FORMATS]
if _unenforced:
    raise RuntimeError(
        "TextFormat member(s) with no entry in _FORMATS: "
        + ", ".join(_unenforced)
        + " — a declared format that nothing enforces is published to both clients and refuses "
        "nothing on the way in"
    )


class Cardinality(str, Enum):
    SINGLETON = "SINGLETON"
    COLLECTION = "COLLECTION"


AUTO_NUMBER_SEQUENCE = "#"

COMPUTED_KINDS = frozenset(
    {"ROSTER_COUNT", "ROSTER_WOMEN", "ATTENDANCE_DAYS", "ROSTER_COMPLETED", "SURVEY_RESPONSES"}
)

FIELD_WIDGETS = frozenset({"ATTENDANCE"})

REF_SCOPE_WORKSHOP = "WORKSHOP"
REF_SCOPE_ALL = "ALL"
REF_SCOPES = frozenset({REF_SCOPE_WORKSHOP, REF_SCOPE_ALL})


@dataclass(frozen=True, slots=True)
class FieldSpec:
    key: str
    label: str
    type: FieldType
    tier: Tier = Tier.STANDARD
    required: bool = False
    help: str = ""
    details: str = ""
    unit: str = ""
    enum: str = ""
    ref_model: str = ""
    ref_filter_by: str = ""
    ref_scope: str = ""
    max_length: int = 0
    max_items: int = 0
    min_items: int = 0
    min_value: float | None = None
    max_value: float | None = None
    report_role: ReportRole = ReportRole.KEY_VALUE
    column_width_pct: float = 0.0
    caption_for: str = ""
    derived_kind: str = ""
    derived_from: tuple[str, ...] = ()
    store_masked: bool = False
    text_format: TextFormat = TextFormat.NONE
    phase_note: str = ""
    deprecated: bool = False
    replaced_by: str = ""
    auto_number: str = ""
    auto_number_within: str = ""
    auto_date_from_day: str = ""
    computed_kind: str = ""
    widget: str = ""

    @property
    def is_free_text(self) -> bool:
        return self.type in {FieldType.TEXT, FieldType.LONG_TEXT, FieldType.RICH_TEXT}

    @property
    def is_rich_text(self) -> bool:
        return self.type is FieldType.RICH_TEXT


@dataclass(frozen=True, slots=True)
class EntitySpec:
    key: str
    name: str
    cardinality: Cardinality
    title: str
    fields: tuple[FieldSpec, ...]
    parent: str = ""
    description: str = ""
    label_field: str = ""

    @property
    def required_fields(self) -> tuple[FieldSpec, ...]:
        return tuple(f for f in self.fields if f.required and not f.deprecated)

    def field(self, key: str) -> FieldSpec | None:
        return next((f for f in self.fields if f.key == key), None)


@dataclass(frozen=True, slots=True)
class StageSpec:
    number: int
    key: str
    title: str
    purpose: str
    entities: tuple[EntitySpec, ...]
    notes: str = ""
    optional_stage: bool = False

    @property
    def singleton(self) -> EntitySpec | None:
        return next((e for e in self.entities if e.cardinality is Cardinality.SINGLETON), None)

    @property
    def collections(self) -> tuple[EntitySpec, ...]:
        return tuple(e for e in self.entities if e.cardinality is Cardinality.COLLECTION)

    def entity(self, key: str) -> EntitySpec | None:
        return next((e for e in self.entities if e.key == key), None)


ENUMS: dict[str, dict[str, str]] = {
    "WORKSHOP_KIND": {
        "DESIGN_PROTOTYPE_DEVELOPMENT": "Design & Prototype Development",
        "SKILL_UPGRADATION": "Skill Upgradation",
        "DESIGN_INTERVENTION": "Design Intervention",
        "CLUSTER_DEVELOPMENT": "Cluster Development",
        "EXPOSURE_EXHIBITION": "Exposure / Exhibition",
        "OTHER": "Other",
    },
    "YES_NO_PARTIAL": {
        "YES": "Yes",
        "NO": "No",
        "PARTIAL": "Partially",
    },
    "MATERIAL_FAMILY": {
        "COTTON": "Cotton",
        "SILK": "Silk",
        "WOOL": "Wool",
        "JUTE": "Jute",
        "LINEN": "Linen",
        "BLEND": "Blended yarn",
        "BAMBOO": "Bamboo / cane",
        "WOOD": "Wood",
        "CLAY": "Clay / terracotta",
        "METAL": "Metal",
        "STONE": "Stone",
        "LEATHER": "Leather",
        "GRASS": "Grass / fibre",
        "PAPER": "Paper",
        "NATURAL_DYE": "Natural dye",
        "CHEMICAL_DYE": "Chemical dye",
        "OTHER": "Other",
    },
    "TOOL_TYPE": {
        "HAND_TOOL": "Hand tool",
        "LOOM": "Loom",
        "LOOM_ACCESSORY": "Loom accessory / attachment",
        "FRAME": "Frame / drum",
        "VESSEL": "Vessel / vat",
        "KILN": "Kiln / furnace / hearth",
        "MOULD": "Mould / die / block",
        "OTHER": "Other",
    },
    "PRODUCT_TYPE": {
        "FINISHED_GOOD": "Finished good",
        "SAMPLE": "Sample",
        "RAW_MATERIAL": "Raw material",
        "COMPONENT": "Component",
        "PACKAGING": "Packaging",
        "OTHER": "Other",
    },
    "MAKER_TYPE": {
        "ARTISAN": "The artisan themselves",
        "LOCAL_BLACKSMITH": "Local blacksmith",
        "CARPENTER": "Carpenter",
        "WORKSHOP": "Workshop",
        "FACTORY": "Factory",
        "UNKNOWN": "Not known",
        "OTHER": "Other",
    },
    "PRODUCT_CATEGORY": {
        "APPAREL": "Apparel",
        "SAREE": "Saree",
        "DUPATTA_STOLE": "Dupatta / stole",
        "YARDAGE": "Yardage / fabric",
        "HOME_FURNISHING": "Home furnishing",
        "TABLE_LINEN": "Table linen",
        "FLOOR_COVERING": "Floor covering",
        "ACCESSORY": "Accessory",
        "BAG": "Bag",
        "JEWELLERY": "Jewellery",
        "UTILITY": "Utility item",
        "DECORATIVE": "Decorative object",
        "TOY": "Toy",
        "PACKAGING": "Packaging",
        "OTHER": "Other",
    },
    "TRADITION_TYPE": {
        "TRADITIONAL": "Traditional",
        "CONTEMPORARY": "Contemporary",
        "TRANSITIONAL": "Transitional (Hybrid)",
    },
    "PROCESS_STEP_TYPE": {
        "SEQUENTIAL": "Sequential",
        "GROUP": "Group of activities",
    },
    "MARKET_CHANNEL": {
        "LOCAL_HAAT": "Local haat / weekly market",
        "EMPORIUM": "Government emporium",
        "EXHIBITION": "Exhibition / mela",
        "RETAILER": "Retailer",
        "WHOLESALER": "Wholesaler",
        "EXPORTER": "Exporter",
        "ONLINE": "Online marketplace",
        "DIRECT": "Direct to customer",
        "MASTER_WEAVER": "Through master weaver / MCP",
        "COOPERATIVE": "Cooperative society",
        "OTHER": "Other",
    },
    "RESPONDENT_GROUP": {
        "CONSUMER": "Consumer",
        "RETAILER": "Retailer",
        "WHOLESALER": "Wholesaler",
        "EXPORTER": "Exporter",
        "ARTISAN": "Artisan",
        "MASTER_CRAFTSPERSON": "Master craftsperson (MCP)",
        "EMPORIUM_STAFF": "Emporium staff",
        "DESIGNER": "Designer",
        "OFFICIAL": "Government official",
        "OTHER": "Other",
    },
    "REVIEW_DECISION": {
        "SELECTED": "Selected",
        "REJECTED": "Rejected",
        "REVISE": "Revise and resubmit",
        "PENDING": "Pending review",
    },
    "REVIEW_ROUND": {
        "PEER": "Workshop peers",
        "POOL": "All designers",
    },
    "DEMAND_LEVEL": {
        "HIGH": "High",
        "MEDIUM": "Medium",
        "LOW": "Low",
        "SEASONAL": "Seasonal",
        "UNKNOWN": "Not known",
    },
    "SWOT_KIND": {
        "STRENGTH": "Strength",
        "WEAKNESS": "Weakness",
        "OPPORTUNITY": "Opportunity",
        "THREAT": "Threat",
    },
    "ADOPTION_STATUS": {
        "ADOPTED_IN_PRODUCTION": "Adopted, in regular production",
        "ADOPTED_ON_ORDER": "Adopted, made to order",
        "TRIAL": "Under trial",
        "NOT_ADOPTED": "Not adopted",
        "UNKNOWN": "Not known",
    },
    "FOLLOWUP_INTERVAL": {
        "M3": "3 months",
        "M6": "6 months",
        "M12": "12 months",
        "AD_HOC": "Ad hoc",
    },
    "MEDIA_QUALITY_FLAG": {
        "BLUR": "Blurred",
        "LOW_RESOLUTION": "Resolution too low",
        "OVEREXPOSED": "Overexposed",
        "UNDEREXPOSED": "Underexposed",
        "MISSING_VIEW": "A required view is missing",
        "DUPLICATE": "Duplicate of another file",
        "WRONG_SUBJECT": "Wrong subject",
    },
    "RETENTION_CLASS": {
        "HOT": "Hot — in active use",
        "WARM": "Warm — occasional access",
        "COLD": "Cold — archive only",
    },
    "GI_STATUS": {
        "REGISTERED": "GI registered",
        "APPLIED": "GI application filed",
        "NOT_REGISTERED": "Not GI registered",
        "UNKNOWN": "Not known",
    },
    "EXPORT_FORMAT": {
        "DOCX": "Word (.docx)",
        "PDF": "PDF (.pdf)",
    },
    "REPORT_TEMPLATE": {
        "DCH_STANDARD": "DCH standard workshop report",
        "DIC_STANDARD": "DIC standard workshop report",
        "IMPLEMENTING_AGENCY": "Implementing agency format",
        "COMPACT_SUMMARY": "Compact summary",
        "DETAILED_TECHNICAL": "Detailed technical report",
        "PHOTO_CATALOGUE": "Photo catalogue",
    },
    "PAGE_SIZE": {
        "A4": "A4",
        "LETTER": "Letter",
    },
    "REPORT_ACCENT_PRESET": dict(ACCENT_PRESET_ENUM),
    "REPORT_FONT": dict(FONT_PRESET_ENUM),
    "SEVERITY": {
        "LOW": "Low",
        "MEDIUM": "Medium",
        "HIGH": "High",
    },
    "PROBLEM_TYPE": {
        "MATERIAL": "Material",
        "PROCESS": "Process",
        "DESIGN": "Design",
    },
    "QUALITY_RATING": {
        "1": "1 — Poor",
        "2": "2 — Below average",
        "3": "3 — Acceptable",
        "4": "4 — Good",
        "5": "5 — Excellent",
    },
}


def enum_label(enum_name: str, value: str) -> str:
    return ENUMS.get(enum_name, {}).get(value, value)


PROMOTED_COLUMNS: dict[str, str] = {
    "workshopSetup.workshopTitle": "title",
    "workshopSetup.workshopKind": "workshopKind",
    "workshopSetup.schemeName": "scheme",
    "workshopSetup.craftName": "craftName",
    "workshopSetup.craftRef": "craftId",
    "workshopSetup.clusterName": "clusterName",
    "workshopSetup.state": "state",
    "workshopSetup.district": "district",
    "workshopSetup.venue": "venue",
    "workshopSetup.startDate": "startDate",
    "workshopSetup.endDate": "endDate",
    "workshopSetup.designerName": "designerName",
    "workshopSetup.implementingAgency": "implementingAgency",
    "workshopSetup.sponsor": "sponsor",
    "workshopSetup.workshopCode": "workshopCode",
}


def promoted_values(entity_key: str, data: dict[str, Any]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for path, column in PROMOTED_COLUMNS.items():
        source_entity, _, field_key = path.partition(".")
        if source_entity == entity_key and field_key in data:
            out[column] = data[field_key]
    return out


REFERENCE_HYDRATION: dict[str, dict[str, str]] = {
    "workshopSetup.craftRef": {
        "craftName": "craftName",
        "craftLocalName": "craftLocalName",
        "craftCategory": "craftCategory",
        "craftPlace": "craftPlace",
        "craftDescription": "documentedCraftNotes",
        "craftDocumentedOn": "craftDocumentedOn",
        "craftDocumentedAtWorkshop": "craftDocumentedAtWorkshop",
        "craftMediaNote": "craftMediaNote",
        "craftPhoto": "craftPhoto",
        "craftPhotoCaption": "craftPhotoCaption",
    },
    "participant.artisanRef": {
        "name": "name",
        "localName": "localName",
        "specialisation": "specialisation",
        "craftStartDate": "craftStartDate",
        "experienceYears": "experienceYears",
        "age": "age",
        "gender": "gender",
        "phone": "phone",
        "email": "email",
        "pehchanCardAvailable": "pehchanCardAvailable",
        "pehchanCardNumber": "artisanCardNo",
        "aadhaarNumber": "aadhaarNumber",
        "village": "village",
        "state": "state",
        "district": "district",
        "pincode": "pincode",
        "address": "address",
        "subjectLocation": "subjectLocation",
        "notes": "recordNotes",
        "dos": "dos",
        "donts": "donts",
        "documentedOn": "documentedOn",
        "documentedAtWorkshop": "documentedAtWorkshop",
        "recordMediaNote": "recordMediaNote",
        "photo": "photo",
        "photoCaption": "photoCaption",
    },
    "tool.toolRef": {
        "name": "name",
        "localName": "localName",
        "englishName": "englishName",
        "material": "material",
        "usedFor": "usedFor",
        "cost": "cost",
        "yearsInUse": "yearsInUse",
        "maker": "maker",
        "traditionType": "traditionType",
        "craftName": "craftName",
        "place": "place",
        "artisanName": "artisanName",
        "improvements": "improvements",
        "remarks": "remarks",
        "lengthCm": "lengthCm",
        "breadthCm": "breadthCm",
        "heightCm": "heightCm",
        "measurementMethodNote": "measurementMethodNote",
        "heightAsRecorded": "heightAsRecorded",
        "widthAsRecorded": "widthAsRecorded",
        "thicknessAsRecorded": "thicknessAsRecorded",
        "weightAsRecorded": "weightAsRecorded",
        "radiusAsRecorded": "radiusAsRecorded",
        "recordState": "recordState",
        "recordDistrict": "recordDistrict",
        "recordVillage": "recordVillage",
        "recordPincode": "recordPincode",
        "subjectLocation": "recordSubjectLocation",
        "recordMediaNote": "recordMediaNote",
        "usedByArtisans": "usedByArtisans",
        "documentedOn": "documentedOn",
        "photo": "photo",
        "photoCaption": "photoCaption",
    },
    "traditionalProcess.processRef": {
        "recordMediaNote": "recordMediaNote",
        "name": "documentedProcessName",
        "notes": "documentedProcessNotes",
        "productName": "documentedFor",
        "steps": "documentedSteps",
        "preProcessAvailable": "preProcessAvailable",
        "documentedOn": "documentedOn",
    },
    "traditionalProcess.productRef": {"name": "documentedFor"},
    "processStep.processRef": {
        "name": "name",
        "notes": "description",
        "productName": "documentedFor",
    },
    "processStep.productRef": {"name": "documentedFor"},
    "existingProduct.artisanRef": {"name": "artisanName"},
    "existingProduct.productRef": {
        "name": "name",
        "localName": "localName",
        "category": "category",
        "recordType": "recordType",
        "material": "material",
        "mainToolsUsed": "mainToolsUsed",
        "price": "price",
        "costOfMaking": "costOfMaking",
        "marketDemand": "marketDemand",
        "use": "use",
        "craftName": "craftName",
        "place": "place",
        "artisanName": "artisanName",
        "lengthCm": "lengthCm",
        "widthCm": "widthCm",
        "heightCm": "heightCm",
        "measurementMethodNote": "measurementMethodNote",
        "dimensionsNote": "dimensionsNote",
        "productionTimeNote": "productionTimeNote",
        "remarks": "remarks",
        "recordState": "recordState",
        "recordDistrict": "recordDistrict",
        "recordVillage": "recordVillage",
        "recordPincode": "recordPincode",
        "subjectLocation": "recordSubjectLocation",
        "recordMediaNote": "recordMediaNote",
        "documentedOn": "documentedOn",
        "photo": "productPhotos",
        "photoCaption": "productPhotosCaption",
    },
    "prototype.productRef": {"name": "productName"},
    "artisanBaseline.interviewRef": {
        "interviewTitle": "interviewTitle",
        "interviewDate": "interviewDate",
        "interviewPlace": "interviewPlace",
        "interviewLanguage": "interviewLanguage",
        "interviewArtisanCount": "interviewArtisanCount",
        "interviewSectionsCovered": "interviewSectionsCovered",
        "interviewQuestionsAnswered": "interviewQuestionsAnswered",
        "interviewLastAnsweredOn": "interviewLastAnsweredOn",
        "interviewMediaNote": "interviewMediaNote",
        "interviewDocumentedOn": "interviewDocumentedOn",
        "interviewDocumentedAtWorkshop": "interviewDocumentedAtWorkshop",
    },
    "surveyPlan.questionnaireRef": {"name": "questionnaireName"},
    "prototypeValidation.prototypeRef": {
        "lengthCm": "finalLengthCm",
        "widthCm": "finalWidthCm",
        "heightCm": "finalHeightCm",
    },
    "finalProduct.prototypeRef": {
        "name": "name",
        "materials": "materials",
        "lengthCm": "lengthCm",
        "widthCm": "widthCm",
        "heightCm": "heightCm",
        "weightG": "weightG",
        "dimensionsNote": "dimensionsNote",
        "makingTimeDays": "makingTimeDays",
        "processSummary": "makingProcess",
    },
}


REFERENCE_SUGGESTIONS: dict[str, dict[str, dict[str, str]]] = {
    "prototype.sketchRef": {
        "fill": {"name": "name", "materials": "materials"},
        "hint": {"lengthCm": "lengthCm", "widthCm": "widthCm", "heightCm": "heightCm"},
    },
    "finalProduct.prototypeRef": {
        "fill": {"artisanRef": "artisanRef"},
        "hint": {},
    },
}


def reference_suggestions_for(entity_key: str, field_key: str) -> dict[str, dict[str, str]]:
    return REFERENCE_SUGGESTIONS.get(f"{entity_key}.{field_key}", {})


def reference_hydration_for(entity_key: str, field_key: str) -> dict[str, str]:
    return REFERENCE_HYDRATION.get(f"{entity_key}.{field_key}", {})


STAGES: tuple[StageSpec, ...] = ()


def _install(stages: tuple[StageSpec, ...]) -> None:
    global STAGES
    STAGES = stages


def stages() -> tuple[StageSpec, ...]:
    _ensure_installed()
    return STAGES


def _ensure_installed() -> None:
    if not STAGES:
        import app.services.stage_definitions  # noqa: F401  - importing installs the registry


def stage(key: str) -> StageSpec:
    _ensure_installed()
    found = next((s for s in STAGES if s.key == key), None)
    if found is None:
        raise KeyError(f"unknown stage {key!r}")
    return found


def stage_by_number(number: int) -> StageSpec:
    _ensure_installed()
    found = next((s for s in STAGES if s.number == number), None)
    if found is None:
        raise KeyError(f"unknown stage number {number}")
    return found


def all_entities() -> tuple[tuple[StageSpec, EntitySpec], ...]:
    _ensure_installed()
    return tuple((s, e) for s in STAGES for e in s.entities)



def validate_registry() -> list[str]:
    _ensure_installed()
    problems: list[str] = []
    seen_stage_keys: set[str] = set()
    seen_stage_numbers: set[int] = set()
    seen_entity_keys: set[str] = set()
    entity_names: set[str] = set()

    for spec in STAGES:
        if spec.key in seen_stage_keys:
            problems.append(f"duplicate stage key {spec.key!r}")
        seen_stage_keys.add(spec.key)
        if spec.number in seen_stage_numbers:
            problems.append(f"duplicate stage number {spec.number}")
        seen_stage_numbers.add(spec.number)
        if not 1 <= spec.number <= 22:
            problems.append(f"stage {spec.key} has number {spec.number}, outside 1-22")

        singletons = [e for e in spec.entities if e.cardinality is Cardinality.SINGLETON]
        if len(singletons) > 1:
            problems.append(f"stage {spec.key} declares more than one SINGLETON entity")

        for entity in spec.entities:
            if entity.key in seen_entity_keys:
                problems.append(f"duplicate entity key {entity.key!r} (stage {spec.key})")
            seen_entity_keys.add(entity.key)
            if entity.name in entity_names:
                problems.append(f"duplicate entity model name {entity.name!r}")
            entity_names.add(entity.name)
            if entity.parent and entity.parent not in seen_entity_keys | {
                e.key for s in STAGES for e in s.entities
            }:
                problems.append(
                    f"entity {entity.key!r} names parent {entity.parent!r}, which does not exist"
                )
            if entity.label_field and entity.field(entity.label_field) is None:
                problems.append(
                    f"entity {entity.key!r} label_field {entity.label_field!r} is not one of "
                    "its fields"
                )

            seen_field_keys: set[str] = set()
            for f in entity.fields:
                where = f"{entity.key}.{f.key}"
                if f.key in seen_field_keys:
                    problems.append(f"duplicate field key {where}")
                seen_field_keys.add(f.key)
                if not f.key or not f.key[0].isalpha():
                    problems.append(f"field key {where} must start with a letter")
                if not f.label:
                    problems.append(f"field {where} has no label")

                if f.required and f.tier is not Tier.BASIC:
                    problems.append(
                        f"field {where} is required but tier {f.tier.value}; only BASIC fields "
                        "may be required, or a workshop without facilities can never complete"
                    )
                if f.type in (FieldType.ENUM, FieldType.MULTI_ENUM):
                    records_backed = f.type is FieldType.MULTI_ENUM and bool(f.ref_model)
                    if not f.enum and not records_backed:
                        problems.append(
                            f"field {where} is {f.type.value} but names no enum"
                            + (" and no ref_model" if f.type is FieldType.MULTI_ENUM else "")
                        )
                    elif f.enum and records_backed:
                        problems.append(
                            f"field {where} is MULTI_ENUM and names BOTH an enum and a "
                            "ref_model; its options come from one place or the other"
                        )
                    elif f.enum and f.enum not in ENUMS:
                        problems.append(f"field {where} names unknown enum {f.enum!r}")
                elif f.enum:
                    problems.append(f"field {where} names an enum but is {f.type.value}")

                if f.type is FieldType.REF and not f.ref_model:
                    problems.append(f"field {where} is REF but names no ref_model")
                if f.ref_model and f.type not in (FieldType.REF, FieldType.MULTI_ENUM):
                    problems.append(
                        f"field {where} names a ref_model but is {f.type.value}; only REF and "
                        "MULTI_ENUM draw a picker"
                    )

                if f.ref_filter_by:
                    if f.type is not FieldType.REF:
                        problems.append(
                            f"field {where} names ref_filter_by but is {f.type.value}; only a "
                            "REF field is narrowed by another"
                        )
                    elif f.ref_filter_by == f.key:
                        problems.append(f"field {where} names itself as ref_filter_by")
                    elif entity.field(f.ref_filter_by) is None:
                        problems.append(
                            f"field {where} is filtered by {f.ref_filter_by!r}, which is not a "
                            f"field of {entity.key!r}"
                        )
                if f.ref_scope:
                    if f.type not in (FieldType.REF, FieldType.MULTI_ENUM):
                        problems.append(f"field {where} names ref_scope but is {f.type.value}")
                    elif f.type is FieldType.MULTI_ENUM and not f.ref_model:
                        problems.append(
                            f"field {where} names ref_scope but draws its options from an enum; "
                            "a scope narrows a list of records"
                        )
                    elif f.ref_scope not in REF_SCOPES:
                        problems.append(
                            f"field {where} names unknown ref_scope {f.ref_scope!r}; expected "
                            f"one of {', '.join(sorted(REF_SCOPES))}"
                        )
                if f.caption_for:
                    target = entity.field(f.caption_for)
                    if target is None:
                        problems.append(
                            f"field {where} captions {f.caption_for!r}, which is not in "
                            f"{entity.key}"
                        )
                    elif not target.type.is_media:
                        problems.append(
                            f"field {where} captions {f.caption_for!r}, which is not a media field"
                        )
                if f.min_items:
                    if not f.type.is_multi:
                        problems.append(
                            f"field {where} declares min_items={f.min_items} but is "
                            f"{f.type.value}; a minimum counts entries in a list, so on a scalar "
                            "it would be published to both clients and enforced by nothing"
                        )
                    else:
                        ceiling = f.max_items or DEFAULT_MAX_ITEMS
                        if f.min_items > ceiling:
                            problems.append(
                                f"field {where} declares min_items={f.min_items} above its "
                                f"ceiling of {ceiling}; coerce_value refuses an array longer "
                                "than the ceiling, so the stage could never be completed"
                            )
                if f.store_masked and f.type not in (
                    FieldType.TEXT,
                    FieldType.LONG_TEXT,
                    FieldType.URL,
                    FieldType.PHONE,
                    FieldType.EMAIL,
                ):
                    problems.append(
                        f"field {where} declares store_masked but is {f.type.value}; only a "
                        "scalar text field passes through the branch that applies the mask, so "
                        "the flag would be published and never enforced"
                    )
                if f.text_format is not TextFormat.NONE:
                    if f.type not in (
                        FieldType.TEXT,
                        FieldType.LONG_TEXT,
                        FieldType.URL,
                        FieldType.PHONE,
                        FieldType.EMAIL,
                    ):
                        problems.append(
                            f"field {where} declares text_format {f.text_format.value} but is "
                            f"{f.type.value}; only a scalar text field passes through the branch "
                            "that enforces a format, so the declaration would be published and "
                            "never enforced"
                        )
                    if f.text_format not in _FORMATS:
                        problems.append(
                            f"field {where} declares text_format {f.text_format!r}, which has no "
                            "entry in _FORMATS and therefore enforces nothing"
                        )
                if f.store_masked and f.text_format is not TextFormat.AADHAAR:
                    problems.append(
                        f"field {where} declares store_masked without text_format=AADHAAR; the "
                        "mask keeps the last four characters of ANY value, so without the format "
                        "a typo is stored in the shape of an identity number"
                    )
                if f.deprecated and not f.replaced_by:
                    problems.append(f"field {where} is deprecated with no replaced_by")
                if (
                    f.min_value is not None
                    and f.max_value is not None
                    and f.min_value > f.max_value
                ):
                    problems.append(f"field {where} has min_value above max_value")
                if f.column_width_pct and not 0 < f.column_width_pct <= 100:
                    problems.append(f"field {where} column_width_pct out of range")

    columns_seen: dict[str, str] = {}
    for path, column in PROMOTED_COLUMNS.items():
        entity_key, _, field_key = path.partition(".")
        entity = next((e for _s, e in all_entities() if e.key == entity_key), None)
        if entity is None:
            problems.append(
                f"promoted path {path!r} names entity {entity_key!r}, which does not exist"
            )
            continue
        if entity.field(field_key) is None:
            problems.append(f"promoted path {path!r} names no field of {entity_key!r}")
        if column in columns_seen:
            problems.append(
                f"promoted column {column!r} is written by both {columns_seen[column]!r} and "
                f"{path!r}"
            )
        columns_seen[column] = path

    entities_by_model = {e.name: e for _s, e in all_entities()}
    for path, mapping in REFERENCE_HYDRATION.items():
        entity_key, _, ref_field_key = path.partition(".")
        entity = next((e for _s, e in all_entities() if e.key == entity_key), None)
        if entity is None:
            problems.append(
                f"hydration path {path!r} names entity {entity_key!r}, which does not exist"
            )
            continue
        ref_field = entity.field(ref_field_key)
        if ref_field is None:
            problems.append(f"hydration path {path!r} names no field of {entity_key!r}")
        elif ref_field.type is not FieldType.REF:
            problems.append(
                f"hydration path {path!r} names {ref_field.type.value} field "
                f"{ref_field_key!r}; only a REF field hydrates a row"
            )
        source_entity = entities_by_model.get(ref_field.ref_model) if ref_field else None
        for source_key, target_key in mapping.items():
            if entity.field(target_key) is None:
                problems.append(
                    f"hydration {path}[{source_key!r}] writes {target_key!r}, which is not a "
                    f"field of {entity_key!r}"
                )
            if source_entity is None:
                continue
            source_field = source_entity.field(source_key)
            if source_field is None:
                problems.append(
                    f"hydration {path}[{source_key!r}] reads a key {source_entity.key!r} does not "
                    "declare, so it copies nothing"
                )
            elif source_field.deprecated:
                problems.append(
                    f"hydration {path}[{source_key!r}] reads {source_entity.key}.{source_key}, "
                    "which is deprecated and is dropped from the row on its next save"
                )

    problems.extend(_behaviour_problems())
    return problems


_AUTO_PREFIX = re.compile(r"^[A-Z]{1,3}$")


def _behaviour_problems() -> list[str]:
    problems: list[str] = []
    for stage_spec, entity in all_entities():
        for spec in entity.fields:
            where = f"{stage_spec.key}.{entity.key}.{spec.key}"
            if spec.auto_number:
                if spec.auto_number == AUTO_NUMBER_SEQUENCE:
                    if spec.type is not FieldType.INT:
                        problems.append(f"{where} numbers as a sequence but is not INT")
                elif spec.type is not FieldType.TEXT or not _AUTO_PREFIX.match(spec.auto_number):
                    problems.append(f"{where} declares auto_number {spec.auto_number!r}")
            if spec.auto_number_within and (
                not spec.auto_number or entity.field(spec.auto_number_within) is None
            ):
                problems.append(f"{where} numbers within {spec.auto_number_within!r}, not a sibling")
            if spec.auto_date_from_day:
                day = entity.field(spec.auto_date_from_day)
                if spec.type is not FieldType.DATE or day is None or day.type is not FieldType.INT:
                    problems.append(f"{where} takes its date from a field that is not an INT day")
            if spec.computed_kind:
                if spec.computed_kind not in COMPUTED_KINDS:
                    problems.append(f"{where} declares unknown computed_kind {spec.computed_kind!r}")
                if spec.type is not FieldType.INT or spec.required or spec.derived_kind:
                    problems.append(f"{where} is computed and must be an optional, underived INT")
            if spec.widget and spec.widget not in FIELD_WIDGETS:
                problems.append(f"{where} declares unknown widget {spec.widget!r}")
            if spec.widget == "ATTENDANCE" and spec.type is not FieldType.TAGS:
                problems.append(f"{where} is the attendance list and must be TAGS")
    entities_by_key = {e.key: e for _s, e in all_entities()}
    entities_by_model = {e.name: e for _s, e in all_entities()}
    for path, mapping in REFERENCE_SUGGESTIONS.items():
        entity_key, _, field_key = path.partition(".")
        entity = entities_by_key.get(entity_key)
        ref = entity.field(field_key) if entity else None
        source = entities_by_model.get(ref.ref_model) if ref is not None else None
        if entity is None or ref is None or ref.type is not FieldType.REF or source is None:
            problems.append(f"suggestion path {path!r} names no in-workshop REF field")
            continue
        for part in ("fill", "hint"):
            for source_key, target_key in mapping.get(part, {}).items():
                if source.field(source_key) is None or entity.field(target_key) is None:
                    problems.append(f"suggestion {path}[{source_key!r}] names an unknown field")
    return problems


_TRUE = {"true", "yes", "y", "1", "on"}
_FALSE = {"false", "no", "n", "0", "off"}


_MAX_ACCURACY_M = 20_037_508.0


DEFAULT_MAX_ITEMS = 200

DEFAULT_MAX_ITEM_CHARS = 300


def coerce_value(spec: FieldSpec, raw: Any) -> tuple[Any, str | None]:
    if raw is None:
        return None, None
    if isinstance(raw, str) and not raw.strip() and spec.type is not FieldType.LONG_TEXT:
        return None, None

    t = spec.type
    try:
        if t is FieldType.RICH_TEXT:
            from app.services.rich_text import from_json, is_empty, to_json

            if is_empty(raw):
                return None, None
            return to_json(from_json(raw)), None

        if t.is_multi:
            if not isinstance(raw, (list, tuple)):
                return None, f"{spec.label} must be a list"
            items = [str(v).strip() for v in raw if str(v).strip()]
            limit = spec.max_items or DEFAULT_MAX_ITEMS
            if len(items) > limit:
                return None, f"{spec.label} may hold at most {limit} entries"
            item_limit = spec.max_length or DEFAULT_MAX_ITEM_CHARS
            if any(len(v) > item_limit for v in items):
                return None, (f"{spec.label}: one entry is longer than {item_limit} characters")
            if t is FieldType.MULTI_ENUM and not spec.ref_model:
                allowed = ENUMS.get(spec.enum, {})
                unknown = [v for v in items if v not in allowed]
                if unknown:
                    return None, (
                        f"{spec.label}: unknown {by_count(len(unknown), 'option', 'options')} "
                        f"{', '.join(unknown)}"
                    )
            return items, None

        if t in (
            FieldType.TEXT,
            FieldType.LONG_TEXT,
            FieldType.URL,
            FieldType.PHONE,
            FieldType.EMAIL,
            FieldType.REF,
            FieldType.IMAGE,
            FieldType.FILE,
            FieldType.AUDIO,
            FieldType.VIDEO,
        ):
            text = clean_text(str(raw)).strip()
            if not text:
                return None, None
            if spec.max_length and len(text) > spec.max_length:
                return None, f"{spec.label} is longer than {spec.max_length} characters"
            format_error = _FORMATS.get(spec.text_format, _no_format_error)(text)
            if format_error:
                return None, format_error
            if spec.store_masked:
                masked = mask_aadhaar(text)
                return (masked or text), None
            return text, None

        if t is FieldType.ENUM:
            token = str(raw).strip()
            if token not in ENUMS.get(spec.enum, {}):
                return None, f"{spec.label}: {token!r} is not a valid option"
            return token, None

        if t is FieldType.BOOL:
            if isinstance(raw, bool):
                return raw, None
            token = str(raw).strip().lower()
            if token in _TRUE:
                return True, None
            if token in _FALSE:
                return False, None
            return None, f"{spec.label} must be yes or no"

        if t is FieldType.INT:
            value = int(str(raw).strip().replace(",", ""))
            return _range_checked(spec, value)

        if t in (FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT):
            value = float(str(raw).strip().replace(",", "").replace("₹", ""))
            if not math.isfinite(value):
                return None, f"{spec.label} is not a valid number"
            checked, error = _range_checked(spec, value)
            if error:
                return None, error
            if t is FieldType.MONEY:
                return f"{checked:.2f}", None
            return checked, None

        if t is FieldType.DATE:
            from datetime import date

            text = str(raw).strip()[:10]
            date.fromisoformat(text)
            return text, None

        if t is FieldType.TIME:
            text = str(raw).strip()
            parts = text.split(":")
            if len(parts) < 2 or not (0 <= int(parts[0]) <= 23 and 0 <= int(parts[1]) <= 59):
                raise ValueError(text)
            return f"{int(parts[0]):02d}:{int(parts[1]):02d}", None

        if t is FieldType.GEO:
            if not isinstance(raw, dict):
                return None, f"{spec.label} must be a coordinate"
            lat = float(raw.get("lat"))
            lon = float(raw.get("lon"))
            if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                return None, f"{spec.label} is not a valid coordinate"
            out: dict[str, Any] = {"lat": lat, "lon": lon}
            if raw.get("accuracy") is not None:
                accuracy = float(raw["accuracy"])
                if not math.isfinite(accuracy) or not (0 <= accuracy <= _MAX_ACCURACY_M):
                    return None, f"{spec.label}: {raw['accuracy']!r} is not a GPS accuracy"
                out["accuracy"] = accuracy
            return out, None
    except (TypeError, ValueError):
        return None, f"{spec.label} is not a valid {spec.type.value.lower().replace('_', ' ')}"

    return raw, None


def _range_checked(spec: FieldSpec, value: float) -> tuple[Any, str | None]:
    if spec.min_value is not None and value < spec.min_value:
        return None, f"{spec.label} must be at least {spec.min_value:g}"
    if spec.max_value is not None and value > spec.max_value:
        return None, f"{spec.label} must be at most {spec.max_value:g}"
    return value, None


def validate_entry(
    entity: EntitySpec, data: dict[str, Any], *, enforce_required: bool = True
) -> tuple[dict[str, Any], dict[str, str]]:
    cleaned: dict[str, Any] = {}
    errors: dict[str, str] = {}

    for spec in entity.fields:
        if spec.deprecated:
            continue
        raw = data.get(spec.key)
        value, error = coerce_value(spec, raw)
        if error:
            errors[spec.key] = error
            continue
        if spec.widget == "ATTENDANCE" and isinstance(raw, list) and not raw:
            cleaned[spec.key] = []
            continue
        if value is None or (isinstance(value, list) and not value):
            derived = derive_value(spec, data)
            if derived is not None:
                cleaned[spec.key] = derived
                continue
            if enforce_required and spec.required:
                errors[spec.key] = f"{spec.label} is required"
            continue
        cleaned[spec.key] = value

    _check_conditional(entity, cleaned, errors)
    return cleaned, errors


_CONDITIONALLY_REQUIRED: dict[str, tuple[str, tuple[str, ...]]] = {
    "outcomes": ("countOverrideReason", ("designsCountOverride", "prototypesCountOverride")),
}


def _check_conditional(entity: EntitySpec, cleaned: dict[str, Any], errors: dict[str, str]) -> None:
    rule = _CONDITIONALLY_REQUIRED.get(entity.key)
    if rule is None:
        return
    dependent, triggers = rule
    if cleaned.get(dependent) or dependent in errors:
        return
    if not any(_is_filled(cleaned.get(key)) for key in triggers):
        return
    spec = entity.field(dependent)
    if spec is None:  # pragma: no cover - the registry audit keeps this reachable
        return
    labels = [entity.field(k).label for k in triggers if entity.field(k) is not None]
    errors[dependent] = f"{spec.label} is required once {' or '.join(labels)} is filled in."



@dataclass(frozen=True, slots=True)
class StageCompleteness:
    stage_key: str
    title: str
    required_total: int
    required_filled: int
    optional_total: int
    optional_filled: int
    collection_counts: dict[str, int]
    missing: tuple[str, ...]

    @property
    def percent(self) -> int:
        if self.required_total == 0:
            return 100
        return round(100 * self.required_filled / self.required_total)

    @property
    def is_complete(self) -> bool:
        return self.required_filled >= self.required_total


def _is_filled(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, dict):
        if "blocks" in value:
            from app.services.rich_text import is_empty

            return not is_empty(value)
        return bool(value)
    if isinstance(value, (list, tuple)):
        return bool(value)
    return True


def _meets_minimum(spec: FieldSpec, value: Any) -> bool:
    if not spec.min_items:
        return True
    if not isinstance(value, (list, tuple)):
        return False
    return len(value) >= spec.min_items


def _shortfall_label(spec: FieldSpec, value: Any) -> str:
    if not spec.min_items:
        return spec.label
    held = len(value) if isinstance(value, (list, tuple)) else 0
    return f"{spec.label} ({held} of {spec.min_items})"


def stage_completeness(
    spec: StageSpec,
    singleton: dict[str, Any],
    collections: dict[str, list[dict[str, Any]]],
    *,
    ref_resolves: Callable[[Any], bool] | None = None,
    custom_fields: Sequence[Any] = (),
    custom_values: dict[str, Any] | None = None,
) -> StageCompleteness:
    required_total = required_filled = 0
    optional_total = optional_filled = 0
    missing: list[str] = []

    def _counts_as_filled(f: FieldSpec, value: Any) -> bool:
        if not _is_filled(value):
            return False
        if not _meets_minimum(f, value):
            return False
        if f.type is not FieldType.REF or ref_resolves is None:
            return True
        return bool(ref_resolves(value))

    def _is_required(f: FieldSpec) -> bool:
        return f.required or bool(f.min_items)

    single = spec.singleton
    if single is not None:
        for f in single.fields:
            if f.deprecated:
                continue
            value = singleton.get(f.key)
            filled = _counts_as_filled(f, value)
            if _is_required(f):
                required_total += 1
                if filled:
                    required_filled += 1
                else:
                    missing.append(_shortfall_label(f, value))
            else:
                optional_total += 1
                optional_filled += int(filled)

    values = custom_values or {}
    for cf in custom_fields:
        if getattr(cf, "retired", False):
            continue
        filled = _is_filled(values.get(getattr(cf, "key", "")))
        if getattr(cf, "required", False):
            required_total += 1
            if filled:
                required_filled += 1
            else:
                missing.append(str(getattr(cf, "label", "") or getattr(cf, "key", "")))
        else:
            optional_total += 1
            optional_filled += int(filled)

    counts: dict[str, int] = {}
    for entity in spec.collections:
        rows = collections.get(entity.key) or []
        counts[entity.key] = len(rows)
        for row in rows:
            for f in entity.fields:
                if f.deprecated:
                    continue
                value = row.get(f.key)
                filled = _counts_as_filled(f, value)
                if _is_required(f):
                    required_total += 1
                    if filled:
                        required_filled += 1
                    else:
                        missing.append(f"{entity.title}: {_shortfall_label(f, value)}")
                else:
                    optional_total += 1
                    optional_filled += int(filled)

    return StageCompleteness(
        stage_key=spec.key,
        title=spec.title,
        required_total=required_total,
        required_filled=required_filled,
        optional_total=optional_total,
        optional_filled=optional_filled,
        collection_counts=counts,
        missing=tuple(dict.fromkeys(missing)),
    )



def field_to_dict(f: FieldSpec, entity_key: str = "") -> dict[str, Any]:
    out: dict[str, Any] = {
        "key": f.key,
        "label": f.label,
        "type": f.type.value,
        "tier": f.tier.value,
        "required": f.required,
    }
    if f.help:
        out["help"] = f.help
    if f.details:
        out["details"] = f.details
    if f.unit:
        out["unit"] = f.unit
    if f.enum:
        out["enum"] = f.enum
        out["options"] = [{"value": v, "label": lbl} for v, lbl in ENUMS[f.enum].items()]
    if f.ref_model:
        out["refModel"] = f.ref_model
        out["refScope"] = f.ref_scope or REF_SCOPE_ALL
        hydration = reference_hydration_for(entity_key, f.key)
        if hydration:
            out["refHydration"] = dict(hydration)
        suggestions = reference_suggestions_for(entity_key, f.key)
        if suggestions:
            out["refSuggest"] = {
                "fill": dict(suggestions.get("fill", {})),
                "hint": dict(suggestions.get("hint", {})),
            }
    if f.ref_filter_by:
        out["refFilterBy"] = f.ref_filter_by
    if f.max_length:
        out["maxLength"] = f.max_length
    if f.store_masked:
        out["storeMasked"] = True
    if f.text_format is not TextFormat.NONE:
        out["format"] = f.text_format.value
    if f.max_items:
        out["maxItems"] = f.max_items
    if f.min_items:
        out["minItems"] = f.min_items
    if f.min_value is not None:
        out["minValue"] = f.min_value
    if f.max_value is not None:
        out["maxValue"] = f.max_value
    if f.report_role is not ReportRole.KEY_VALUE:
        out["reportRole"] = f.report_role.value
    if f.derived_kind:
        out["derivedKind"] = f.derived_kind
        out["derivedFrom"] = list(f.derived_from)
    if f.auto_number:
        out["autoNumber"] = f.auto_number
    if f.auto_number_within:
        out["autoNumberWithin"] = f.auto_number_within
    if f.auto_date_from_day:
        out["autoDateFromDay"] = f.auto_date_from_day
    if f.computed_kind:
        out["computedKind"] = f.computed_kind
    if f.widget:
        out["widget"] = f.widget
    if f.column_width_pct:
        out["columnWidthPct"] = f.column_width_pct
    if f.caption_for:
        out["captionFor"] = f.caption_for
    if f.deprecated:
        out["deprecated"] = True
        out["replacedBy"] = f.replaced_by
    return out


def entity_to_dict(e: EntitySpec) -> dict[str, Any]:
    return {
        "key": e.key,
        "name": e.name,
        "cardinality": e.cardinality.value,
        "title": e.title,
        "description": e.description,
        "parent": e.parent,
        "labelField": e.label_field,
        "fields": [field_to_dict(f, e.key) for f in e.fields if not f.deprecated],
    }


def stage_to_dict(s: StageSpec) -> dict[str, Any]:
    return {
        "number": s.number,
        "key": s.key,
        "title": s.title,
        "purpose": s.purpose,
        "notes": s.notes,
        "optionalStage": s.optional_stage,
        "entities": [entity_to_dict(e) for e in s.entities],
    }


def registry_to_dict() -> dict[str, Any]:
    _ensure_installed()
    return {
        "version": registry_version(),
        "enums": {
            name: [{"value": v, "label": lbl} for v, lbl in options.items()]
            for name, options in ENUMS.items()
        },
        "stages": [stage_to_dict(s) for s in STAGES],
    }


_VERSION_MEMO: tuple[tuple[Any, ...], str] | None = None


def _registry_fingerprint() -> tuple[Any, ...]:
    return (
        tuple((path, tuple(mapping.items())) for path, mapping in REFERENCE_HYDRATION.items()),
        tuple(
            (
                s.key,
                e.key,
                f.key,
                f.type.value,
                f.tier.value,
                f.required,
                f.enum,
                f.deprecated,
                f.derived_kind,
                f.derived_from,
                f.store_masked,
                f.text_format.value,
                f.min_items,
                (f.auto_number, f.auto_number_within, f.auto_date_from_day, f.computed_kind, f.widget),
            )
            for s in STAGES
            for e in s.entities
            for f in e.fields
        ),
    )


def _registry_digest(fingerprint: tuple[Any, ...]) -> str:
    import hashlib

    hydration_table = {path: dict(pairs) for path, pairs in fingerprint[0]}
    parts: list[str] = []
    for field in fingerprint[1]:
        (
            stage_key,
            entity_key,
            field_key,
            field_type,
            tier,
            required,
            enum,
            deprecated,
            derived_kind,
            derived_from,
            store_masked,
            text_format,
            min_items,
            behaviour,
        ) = field
        hydration = ",".join(
            f"{src}>{dst}"
            for src, dst in hydration_table.get(f"{entity_key}.{field_key}", {}).items()
        )
        parts.append(
            f"{stage_key}.{entity_key}.{field_key}:{field_type}:{tier}:"
            f"{int(required)}:{enum}:{int(deprecated)}:"
            f"{derived_kind}:{','.join(derived_from)}:{hydration}:"
            f"{int(store_masked)}:{text_format}:{min_items}"
            + (f":{'/'.join(behaviour)}" if any(behaviour) else "")
        )
    return hashlib.sha256("|".join(sorted(parts)).encode("utf-8")).hexdigest()[:16]


def registry_version() -> str:
    _ensure_installed()
    global _VERSION_MEMO
    fingerprint = _registry_fingerprint()
    memo = _VERSION_MEMO
    if memo is not None and memo[0] == fingerprint:
        return memo[1]
    digest = _registry_digest(fingerprint)
    _VERSION_MEMO = (fingerprint, digest)
    return digest


def derive_value(spec: FieldSpec, row: dict[str, Any]) -> Any:
    if not spec.derived_kind or not spec.derived_from:
        return None

    if spec.derived_kind == "DAYS_BETWEEN":
        from datetime import date

        try:
            start = date.fromisoformat(str(row.get(spec.derived_from[0]) or "")[:10])
            end = date.fromisoformat(str(row.get(spec.derived_from[1]) or "")[:10])
        except (TypeError, ValueError):
            return None
        days = (end - start).days + 1
        return days if days > 0 else None

    if spec.derived_kind == "PRODUCT":
        total = 1.0
        for key in spec.derived_from:
            raw = row.get(key)
            if raw in (None, ""):
                return None
            try:
                total *= float(str(raw).replace(",", ""))
            except (TypeError, ValueError):
                return None
        return f"{total:.2f}" if spec.type is FieldType.MONEY else round(total, 4)

    if spec.derived_kind == "SUM":
        total = 0.0
        seen = False
        for key in spec.derived_from:
            raw = row.get(key)
            if raw in (None, ""):
                continue
            try:
                total += float(str(raw).replace(",", ""))
            except (TypeError, ValueError):
                return None
            seen = True
        if not seen:
            return None
        return f"{total:.2f}" if spec.type is FieldType.MONEY else round(total, 4)

    return None



def format_auto_number(prefix: str, number: int) -> str:
    return f"{prefix}-{number:02d}"


def next_auto_number(spec: FieldSpec, rows: Sequence[dict[str, Any]], scope: Any = None) -> Any:
    if not spec.auto_number:
        return None
    considered = [
        row
        for row in rows
        if not spec.auto_number_within or row.get(spec.auto_number_within) == scope
    ]
    highest = 0
    if spec.auto_number == AUTO_NUMBER_SEQUENCE:
        for row in considered:
            try:
                highest = max(highest, int(str(row.get(spec.key) or "0").strip()))
            except ValueError:
                continue
        return max(highest, len(considered)) + 1
    pattern = re.compile(rf"^\s*{re.escape(spec.auto_number)}\s*-?\s*(\d+)\s*$", re.IGNORECASE)
    for row in considered:
        match = pattern.match(str(row.get(spec.key) or ""))
        if match:
            highest = max(highest, int(match.group(1)))
    return format_auto_number(spec.auto_number, max(highest, len(considered)) + 1)

