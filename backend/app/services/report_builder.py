from __future__ import annotations

import math
import re
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from typing import Any

from app.services import rich_text
from app.services.report_ai_layers import ai_layers_of, append_ai_layer_annexure
from app.services.report_annexures import append_transcript_annexure, transcripts_of
from app.services.report_custom_sections import (
    append_custom_section,
    custom_scoring,
    custom_section_of,
    sections_hidden_by_tier,
)
from app.services.report_model import (
    Align,
    ChartBlock,
    ChartKind,
    CoverBlock,
    DocumentBuilder,
    ImageBlock,
    ImageGridBlock,
    ImageRef,
    KeyValueBlock,
    MapBlock,
    MapPoint,
    MapPointKind,
    MetricRowBlock,
    PageBreakBlock,
    ParaStyle,
    ReportDocument,
    ReportMeta,
    ReportTheme,
    Run,
    SignatureBlock,
    TableBlock,
    TableColumn,
    TocBlock,
    clean_text,
    runs_of,
)
from app.services.report_questionnaires import (
    append_questionnaire_annexure,
    questionnaires_of,
)
from app.services.report_templates import (
    Presentation,
    ReportTemplate,
    SpecialSection,
    TemplateSection,
    template as get_template,
)
from app.services.stage_schema import (
    Cardinality,
    EntitySpec,
    FieldSpec,
    FieldType,
    ReportRole,
    StageSpec,
    enum_label,
    reference_hydration_for,
    stage_completeness,
    stages,
)
from app.services.text_format import by_count, count_noun

MediaResolver = Callable[[str], ImageRef | None]


@dataclass(frozen=True, slots=True)
class ReferencedRecord:
    model: str = ""
    label: str = ""
    photo: str = ""
    place: str = ""
    district: str = ""
    state: str = ""


_REFERENCE_NAME_SOURCES: dict[str, tuple[str, ...]] = {"Craft": ("craftName",)}


@dataclass
class WorkshopData:
    workshop_id: str
    title: str
    singletons: dict[str, dict[str, Any]] = field(default_factory=dict)
    collections: dict[str, dict[str, list[dict[str, Any]]]] = field(default_factory=dict)
    references: dict[str, ReferencedRecord] = field(default_factory=dict)
    district_points: dict[str, tuple[float, float]] = field(default_factory=dict)
    generated_at: str = ""
    generated_by: str = ""
    field_provenance: dict[str, dict[str, Any]] = field(default_factory=dict)
    sign_off_lines: tuple[str, ...] = ()

    def singleton(self, stage_key: str) -> dict[str, Any]:
        return self.singletons.get(stage_key) or {}

    def provenance(self, entry_id: Any) -> dict[str, Any]:
        if not entry_id or not isinstance(entry_id, str):
            return {}
        return self.field_provenance.get(entry_id) or {}

    def rows(self, stage_key: str, entity_key: str) -> list[dict[str, Any]]:
        return (self.collections.get(stage_key) or {}).get(entity_key) or []

    def value(self, stage_key: str, key: str, default: Any = None) -> Any:
        return self.singleton(stage_key).get(key, default)

    def reference(self, ref_id: Any) -> ReferencedRecord | None:
        if not ref_id or not isinstance(ref_id, str):
            return None
        return self.references.get(ref_id)



def _group_indian(digits: str) -> str:
    if len(digits) <= 3:
        return digits
    head, tail = digits[:-3], digits[-3:]
    parts: list[str] = []
    while len(head) > 2:
        parts.insert(0, head[-2:])
        head = head[:-2]
    if head:
        parts.insert(0, head)
    return ",".join([*parts, tail])


_OPAQUE_ID = re.compile(r"^[a-z0-9]{16,}$")


def _looks_like_an_id(text: str) -> bool:
    return bool(_OPAQUE_ID.match(text.strip()))


MODEL_3D_FIELD_KEYS: dict[str, tuple[str, str]] = {"modelFile": ("turntablePhotos", "360° capture")}


def model_preview_note(spec: FieldSpec, row: dict[str, Any]) -> str:
    preview = MODEL_3D_FIELD_KEYS.get(spec.key) if spec.type is FieldType.FILE else None
    if preview is None:
        return ""
    frames = len(_media_ids(row.get(preview[0])))
    if not frames:
        return ""
    return f"; {frames} photograph{'' if frames == 1 else 's'} of it under “{preview[1]}”"


def format_value(spec: FieldSpec, value: Any) -> str:
    if value is None or value == "" or value == []:
        return ""
    t = spec.type

    if t is FieldType.RICH_TEXT:
        return rich_text.to_plain(value)
    if t is FieldType.ENUM:
        return enum_label(spec.enum, str(value))
    if t is FieldType.MULTI_ENUM:
        if spec.ref_model:
            return ", ".join(str(v) for v in value)
        return ", ".join(enum_label(spec.enum, str(v)) for v in value)
    if t is FieldType.TAGS:
        return ", ".join(str(v) for v in value)
    if t is FieldType.BOOL:
        return "Yes" if value else "No"
    if t is FieldType.MONEY:
        amount = _as_number(value)
        if amount is None:
            return clean_text(value)
        whole, _, frac = f"{amount:.2f}".partition(".")
        sign = "-" if whole.startswith("-") else ""
        return f"{sign}₹ {_group_indian(whole.lstrip('-'))}.{frac}"
    if t is FieldType.PERCENT:
        percent = _as_number(value)
        return f"{percent:g}%" if percent is not None else clean_text(value)
    if t in (FieldType.INT, FieldType.DECIMAL):
        number = _as_number(value)
        if number is None:
            return clean_text(value)
        text = f"{number:.0f}" if t is FieldType.INT else f"{number:g}"
        if abs(number) >= 10000:
            whole, _, frac = f"{abs(number):.2f}".partition(".")
            text = ("-" if number < 0 else "") + _group_indian(whole)
            if t is not FieldType.INT and frac.rstrip("0"):
                text += "." + frac.rstrip("0")
        return f"{text} {spec.unit}".strip() if spec.unit else text
    if t is FieldType.DATE:
        return _format_date(str(value))
    if t is FieldType.GEO:
        if isinstance(value, dict):
            return f"{float(value.get('lat', 0)):.5f}, {float(value.get('lon', 0)):.5f}"
        return clean_text(value)
    if t in (FieldType.IMAGE, FieldType.IMAGE_LIST):
        return ""
    if t.is_media:
        count = len(_media_ids(value))
        if not count:
            return ""
        if t is FieldType.FILE and spec.key in MODEL_3D_FIELD_KEYS:
            return f"{count} 3D model{'' if count == 1 else 's'} attached"
        singular, plural = {
            FieldType.AUDIO: ("recording", "recordings"),
            FieldType.VIDEO: ("video", "videos"),
        }.get(t, ("document", "documents"))
        return f"{count} {singular if count == 1 else plural} attached"

    text = clean_text(value)
    return f"{text} {spec.unit}".strip() if spec.unit and text else text


_MONTHS = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")


def sign_off_lines(
    *,
    approved_at: Any,
    approved_by_name: str | None,
    approver_role_label: str | None,
    handed_on_at: Any,
    handed_on_to: str | None,
) -> tuple[str, ...]:
    def _iso(moment: Any) -> str:
        return moment.isoformat() if hasattr(moment, "isoformat") else str(moment or "")

    if not approved_at:
        return ()
    on = _format_date(_iso(approved_at))
    name = (approved_by_name or "").strip()
    role = (approver_role_label or "").strip()
    if not name:
        lines = [f"Approved on {on}."]
    elif not role:
        lines = [f"Approved by {name} on {on}."]
    else:
        lines = [f"Approved by {name} ({role}) on {on}."]
    if handed_on_at:
        office = (handed_on_to or "").strip() or "the office"
        lines.append(f"Handed on to {office} on {_format_date(_iso(handed_on_at))}.")
    return tuple(lines)


def _format_date(iso: str) -> str:
    parts = iso.strip()[:10].split("-")
    if len(parts) != 3:
        return clean_text(iso)
    try:
        year, month, day = int(parts[0]), int(parts[1]), int(parts[2])
        return f"{day:02d} {_MONTHS[month - 1]} {year}"
    except (ValueError, IndexError):
        return clean_text(iso)


_HEADING_CHARS = 80


def _heading_summary(text: str) -> str:
    collapsed = " ".join(text.split())
    if not collapsed:
        return ""
    for i, character in enumerate(collapsed):
        if character in ".?!" and (i + 1 == len(collapsed) or collapsed[i + 1] == " "):
            sentence = collapsed[: i + 1]
            if len(sentence) <= _HEADING_CHARS:
                return sentence
            break
    if len(collapsed) <= _HEADING_CHARS:
        return collapsed
    cut = collapsed[: _HEADING_CHARS - 1]
    spaced = cut.rsplit(" ", 1)[0] if " " in cut else cut
    return f"{spaced.rstrip(' ,;:-')}…"


def _submission_line(settings: Mapping[str, Any]) -> str:
    to = clean_text(settings.get("submittedTo")).strip()
    on = clean_text(settings.get("submissionDate")).strip()
    when = _format_date(on) if on else ""
    if to and when:
        return f"Submitted to {to} on {when}"
    if to:
        return f"Submitted to {to}"
    return f"Submitted on {when}" if when else ""


def _media_ids(value: Any) -> list[str]:
    if not value:
        return []
    if isinstance(value, str):
        return [value]
    if isinstance(value, (list, tuple)):
        return [str(v) for v in value if v]
    return []


MAP_VENUE_STAGE = "WORKSHOP_SETUP"
MAP_ROSTER_STAGE = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"
MAP_ROSTER_ENTITY = "participant"

MAP_ROSTER_PLACE_KEYS: tuple[str, ...] = ("village", "district")

MAP_ROSTER_STATE_KEY = "state"

MAP_ROSTER_PIN_KEY = "subjectLocation"


@dataclass(frozen=True, slots=True)
class _Located:
    lat: float
    lon: float
    state: str
    label: str
    precise: bool = True


def _geocode(
    text: Any, state_hint: Any = "", district_points: dict[str, tuple[float, float]] | None = None
) -> _Located | None:
    from app.services.address import canonical_state
    from app.services.geography import state_seat
    from app.services.place_atlas import Precision, resolve_place

    local = clean_text(text).strip()
    state_text = clean_text(state_hint).strip()
    joined = ", ".join(part for part in (local, state_text) if part)
    if joined:
        found = resolve_place(joined).place
        if found is not None:
            precise = found.precision is not Precision.STATE
            if precise:
                return _Located(
                    found.latitude,
                    found.longitude,
                    canonical_state(found.state) or found.state,
                    found.label,
                    precise=precise,
                )

    if district_points:
        from app.services.address import canonical_district

        state_key = canonical_state(state_text) or state_text
        if state_key:
            parts = [p.strip() for p in local.replace(";", ",").split(",") if p.strip()]
            for candidate in [local, *reversed(parts)]:
                district = canonical_district(state_key, candidate)
                if not district:
                    continue
                point = district_points.get(f"{state_key}|{district}")
                if point:
                    return _Located(point[0], point[1], state_key, district, precise=True)

    if joined:
        found = resolve_place(joined).place
        if found is not None:
            return _Located(
                found.latitude,
                found.longitude,
                canonical_state(found.state) or found.state,
                found.label,
                precise=False,
            )

    canonical = canonical_state(state_text) or canonical_state(local)
    if canonical:
        seat = state_seat(canonical)
        if seat is not None:
            return _Located(seat[1], seat[2], canonical, canonical, precise=False)
    return None


def _geo_point(value: Any) -> tuple[float, float] | None:
    if not isinstance(value, dict):
        return None
    try:
        lat, lon = float(value.get("lat")), float(value.get("lon"))  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return None
    if abs(lat) < 0.0001 and abs(lon) < 0.0001:
        return None
    return (lat, lon)


@dataclass
class _MapFacts:
    points: list[MapPoint] = field(default_factory=list)
    states: set[str] = field(default_factory=set)
    placed: int = 0
    total: int = 0
    approximate: int = 0

    def merge(self, other: _MapFacts) -> _MapFacts:
        return _MapFacts(
            points=self.points + other.points,
            states=self.states | other.states,
            placed=self.placed + other.placed,
            total=self.total + other.total,
            approximate=self.approximate + other.approximate,
        )


def _fold_points(found: list[tuple[str, _Located]], kind: MapPointKind) -> list[MapPoint]:
    folded: dict[tuple[float, float], tuple[str, int]] = {}
    for label, place in found:
        key = (round(place.lat, 5), round(place.lon, 5))
        existing = folded.get(key)
        folded[key] = (existing[0] if existing else label, (existing[1] if existing else 0) + 1)
    return [
        MapPoint(label=label, lat=lat, lon=lon, kind=kind, count=count)
        for (lat, lon), (label, count) in folded.items()
    ]


MIN_CHART_CATEGORIES = 2

_PRICE_STEPS: tuple[float, ...] = (
    100.0,
    250.0,
    500.0,
    1000.0,
    2500.0,
    5000.0,
    10000.0,
    25000.0,
    50000.0,
    100000.0,
)

_MAX_PRICE_BANDS = 6

FIGURES: dict[str, tuple[str, str]] = {
    "OUTPUT_COUNTS": ("WORKSHOP_OUTCOMES", "_chart_output_counts"),
    "PROTOTYPE_STATUS": ("WORKSHOP_OUTCOMES", "_chart_prototype_status"),
    "SURVEY_RESPONDENTS": ("MARKET_SURVEY_CAPTURE", "_chart_survey_respondents"),
    "SURVEY_PRICE_EXPECTATIONS": ("MARKET_SURVEY_CAPTURE", "_chart_survey_price_expectations"),
    "COST_BY_HEAD": ("COSTING_MARKET_LINKAGE", "_chart_cost_by_head"),
    "PRICE_BANDS": ("COSTING_MARKET_LINKAGE", "_chart_price_bands"),
    "ADOPTION": ("POST_WORKSHOP_FOLLOWUP", "_chart_adoption"),
}


def printable_collections(spec: Any) -> list[EntitySpec]:
    return [
        e
        for e in spec.collections
        if any(f.report_role is not ReportRole.HIDDEN for f in e.fields if not f.deprecated)
    ]


def _as_number(value: Any) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if math.isnan(number) or math.isinf(number):
        return None
    return number


def _price_bands(values: list[float]) -> list[tuple[str, float]]:
    positive = sorted(value for value in values if value > 0)
    if len(positive) < 3:
        return []
    top = positive[-1]
    if top <= positive[0]:
        return []
    step = next((s for s in _PRICE_STEPS if top / s <= _MAX_PRICE_BANDS), _PRICE_STEPS[-1])
    counts: dict[int, int] = {}
    for value in positive:
        counts[int(value // step)] = counts.get(int(value // step), 0) + 1
    return [
        (
            f"{_group_indian(f'{index * step:.0f}')}–{_group_indian(f'{(index + 1) * step - 1:.0f}')}",
            float(count),
        )
        for index, count in sorted(counts.items())
    ]


COVER_INFO_ROWS = 10

COVER_PINNED_KEYS: frozenset[str] = frozenset({"sanctionAmount"})


def cover_rows(
    keyed: list[tuple[str, str, str]],
) -> tuple[list[tuple[str, str]], list[tuple[str, str]]]:
    room = COVER_INFO_ROWS - sum(1 for key, _l, _t in keyed if key in COVER_PINNED_KEYS)
    printed: list[tuple[str, str]] = []
    overflow: list[tuple[str, str]] = []
    for key, label, text in keyed:
        if key in COVER_PINNED_KEYS:
            printed.append((label, text))
        elif room > 0:
            printed.append((label, text))
            room -= 1
        else:
            overflow.append((label, text))
    return printed, overflow



class ReportBuilder:
    def __init__(
        self,
        data: WorkshopData,
        template: ReportTemplate,
        resolve_media: MediaResolver,
        *,
        meta: ReportMeta,
        theme: ReportTheme | None = None,
    ) -> None:
        self.data = data
        self.template = template
        self.resolve_media = resolve_media
        self.doc = DocumentBuilder(meta=meta, theme=theme or template.theme)
        self._stages = {s.key: s for s in stages()}
        self._drawn: set[str] = set()
        self._entities: dict[str, EntitySpec] = {e.key: e for s in stages() for e in s.entities}
        self._entity_stage: dict[str, str] = {e.key: s.key for s in stages() for e in s.entities}
        self._rows_by_id: dict[str, tuple[EntitySpec | None, dict[str, Any]]] = {}
        for entities in data.collections.values():
            for entity_key, rows in entities.items():
                entity = self._entities.get(entity_key)
                for row in rows:
                    entry_id = row.get("_entryId")
                    if isinstance(entry_id, str) and entry_id:
                        self._rows_by_id[entry_id] = (entity, row)
        self._label_cache: dict[str, str] = {}
        self.cover_fields_dropped: tuple[str, ...] = ()
        self._photographs_over_cap: dict[str, int] = {}
        self._stage_key: str = ""

    def _visible(self, spec: FieldSpec) -> bool:
        return not spec.deprecated and spec.tier.rank <= self.template.max_tier.rank

    def _is_filled(self, spec: FieldSpec, row: dict[str, Any]) -> bool:
        if spec.type.is_media:
            return bool(_media_ids(row.get(spec.key)))
        return bool(self._value(spec, row))

    def fields_hidden_by_tier(self) -> list[tuple[StageSpec, list[str]]]:
        out: list[tuple[StageSpec, list[str]]] = []
        for spec in stages():
            if self.template.section_for(spec.key) is None:
                continue
            lost: list[str] = []
            for entity in spec.entities:
                rows = (
                    [self.data.singleton(spec.key)]
                    if entity.cardinality is Cardinality.SINGLETON
                    else self.data.rows(spec.key, entity.key)
                )
                for field_spec in entity.fields:
                    if self._visible(field_spec) or field_spec.deprecated:
                        continue
                    if field_spec.report_role is ReportRole.HIDDEN:
                        continue
                    if any(self._is_filled(field_spec, row) for row in rows):
                        lost.append(field_spec.label)
            if lost:
                out.append((spec, lost))
        return out

    def photographs_over_cap(self) -> list[tuple[StageSpec, int]]:
        return [
            (spec, self._photographs_over_cap[spec.key])
            for spec in stages()
            if self._photographs_over_cap.get(spec.key)
        ]

    def attachments_named_but_not_carried(self) -> list[tuple[StageSpec, int]]:
        out: list[tuple[StageSpec, int]] = []
        for spec in stages():
            if self.template.section_for(spec.key) is None:
                continue
            named = 0
            for entity in spec.entities:
                rows = (
                    [self.data.singleton(spec.key)]
                    if entity.cardinality is Cardinality.SINGLETON
                    else self.data.rows(spec.key, entity.key)
                )
                for field_spec in entity.fields:
                    if not field_spec.type.is_media:
                        continue
                    if field_spec.type in (FieldType.IMAGE, FieldType.IMAGE_LIST):
                        continue
                    if not self._visible(field_spec):
                        continue
                    if field_spec.report_role is ReportRole.HIDDEN:
                        continue
                    named += sum(len(_media_ids(row.get(field_spec.key))) for row in rows)
            if named:
                out.append((spec, named))
        return out

    def _ref_label(self, ref_id: Any, _seen: frozenset[str] = frozenset()) -> str:
        if not isinstance(ref_id, str) or not ref_id:
            return ""
        if ref_id in self._label_cache:
            return self._label_cache[ref_id]
        if ref_id in _seen:
            return ""

        label = ""
        found = self._rows_by_id.get(ref_id)
        if found is not None:
            entity, row = found
            if entity is not None:
                label = self._row_label_text(entity, row, _seen | {ref_id})
        if not label:
            record = self.data.reference(ref_id)
            if record is not None:
                label = record.label

        self._label_cache[ref_id] = label
        return label

    def _row_label_text(
        self, entity: EntitySpec, row: dict[str, Any], _seen: frozenset[str] = frozenset()
    ) -> str:
        if entity.label_field:
            spec = entity.field(entity.label_field)
            if spec is not None:
                if spec.type is FieldType.REF:
                    text = self._ref_label(row.get(spec.key), _seen)
                else:
                    text = format_value(spec, row.get(spec.key))
                    if spec.type is FieldType.TEXT and _looks_like_an_id(text):
                        text = ""
                if text:
                    return text
        for spec in entity.fields:
            if spec.is_free_text and self._visible(spec):
                text = format_value(spec, row.get(spec.key))
                if text:
                    return _heading_summary(text)
        return ""

    def _value(self, spec: FieldSpec, row: dict[str, Any]) -> str:
        if spec.type is FieldType.REF:
            raw = row.get(spec.key)
            label = self._ref_label(raw)
            if label:
                return label
            text = format_value(spec, raw)
            return "" if _looks_like_an_id(text) else text

        if spec.type is FieldType.MULTI_ENUM and spec.ref_model:
            printed: list[str] = []
            for token in row.get(spec.key) or []:
                text = str(token).strip()
                if not text:
                    continue
                label = self._ref_label(text)
                if label:
                    printed.append(label)
                elif not _looks_like_an_id(text):
                    printed.append(text)
            return ", ".join(printed)

        text = format_value(spec, row.get(spec.key))
        if text:
            text += model_preview_note(spec, row)
        return "" if spec.type is FieldType.TEXT and _looks_like_an_id(text) else text

    def _printable(
        self, entity: EntitySpec, row: dict[str, Any], roles: set[ReportRole]
    ) -> list[tuple[FieldSpec, str]]:
        out: list[tuple[FieldSpec, str]] = []
        for spec in entity.fields:
            if not self._visible(spec) or spec.report_role not in roles:
                continue
            if spec.caption_for:
                continue
            text = self._value(spec, row)
            if text:
                out.append((spec, text))
            elif spec.required and self.template.show_empty_note:
                out.append((spec, "Not recorded."))
        return out

    def _image_sources(
        self,
        entity: EntitySpec,
        row: dict[str, Any],
    ) -> dict[str, tuple[FieldSpec, str]]:
        wanted: dict[str, tuple[FieldSpec, str]] = {}

        for spec in entity.fields:
            if not self._visible(spec) or spec.type not in (FieldType.IMAGE, FieldType.IMAGE_LIST):
                continue
            caption_spec = next((f for f in entity.fields if f.caption_for == spec.key), None)
            caption = format_value(caption_spec, row.get(caption_spec.key)) if caption_spec else ""
            for media_id in _media_ids(row.get(spec.key)):
                wanted.setdefault(media_id, (spec, caption))

        for spec in entity.fields:
            if not self._visible(spec) or spec.type is not FieldType.REF:
                continue
            reference = self.data.reference(row.get(spec.key))
            if reference is None or not reference.photo:
                continue
            wanted.setdefault(
                reference.photo, (spec, self._reference_caption(entity, spec, row, reference))
            )
        return wanted

    def _images(
        self, entity: EntitySpec, row: dict[str, Any], *, limit: int = 0
    ) -> list[tuple[ImageRef, str]]:
        found: list[tuple[ImageRef, str]] = []
        for media_id, (spec, caption) in self._image_sources(entity, row).items():
            ref = self.resolve_media(media_id)
            if ref is None:
                continue
            found.append((ref, caption or spec.label))
            if limit and len(found) >= limit:
                break
        return found

    def _image_groups(
        self, entity: EntitySpec, row: dict[str, Any], *, cap: int = 0
    ) -> list[tuple[str, list[tuple[ImageRef, str]]]]:
        claims: dict[str, list[tuple[str, FieldSpec, str]]] = {}
        order: list[str] = []
        for media_id, (spec, caption) in self._image_sources(entity, row).items():
            if spec.key not in claims:
                claims[spec.key] = []
                order.append(spec.key)
            claims[spec.key].append((media_id, spec, caption))

        plates: list[tuple[str, list[tuple[str, FieldSpec, str]]]] = []
        shared: int | None = None
        for key in order:
            claimed = claims[key]
            if len(claimed) > 1:
                plates.append((claimed[0][1].label, claimed))
                continue
            if shared is None:
                shared = len(plates)
                plates.append(("", []))
            plates[shared][1].extend(claimed)

        groups: list[tuple[str, list[tuple[ImageRef, str]]]] = []
        for grid_caption, claimed in plates:
            images: list[tuple[ImageRef, str]] = []
            over = 0
            for media_id, spec, caption in claimed:
                if cap and len(images) >= cap:
                    over += 1
                    continue
                ref = self.resolve_media(media_id)
                if ref is None:
                    continue
                images.append((ref, caption or ("" if grid_caption else spec.label)))
            if over:
                self._note_photographs_over_cap(over)
            if images:
                groups.append((grid_caption, images))
        return groups

    def _note_photographs_over_cap(self, count: int) -> None:
        if count > 0 and self._stage_key:
            self._photographs_over_cap[self._stage_key] = (
                self._photographs_over_cap.get(self._stage_key, 0) + count
            )

    def _reference_caption(
        self, entity: EntitySpec, spec: FieldSpec, row: dict[str, Any], reference: ReferencedRecord
    ) -> str:
        sources = ("name", *_REFERENCE_NAME_SOURCES.get(reference.model, ()))
        for source, target in reference_hydration_for(entity.key, spec.key).items():
            if source not in sources:
                continue
            target_spec = entity.field(target)
            if target_spec is None:
                continue
            frozen = self._value(target_spec, row)
            if frozen:
                return frozen
        return reference.label or spec.label

    def _render_narrative(
        self, entity: EntitySpec, row: dict[str, Any], level: int, *, skip: set[str] | None = None
    ) -> bool:
        wrote = False
        skip = skip or set()

        def printable(*roles: ReportRole) -> list[tuple[FieldSpec, str]]:
            return [
                (s, v) for s, v in self._printable(entity, row, set(roles)) if s.key not in skip
            ]

        narrative = printable(ReportRole.NARRATIVE)
        pairs = printable(ReportRole.KEY_VALUE, ReportRole.COVER_FIELD, ReportRole.TABLE_COLUMN)
        bullets = printable(ReportRole.BULLETS)

        if pairs:
            self.doc.add(
                KeyValueBlock(
                    pairs=tuple((s.label, self._cell_runs(s, row, v)) for s, v in pairs),
                    columns=2,
                )
            )
            wrote = True
        for spec, text in narrative:
            if spec.is_rich_text:
                blocks = rich_text.to_report_blocks(
                    row.get(spec.key), resolve_media=self.resolve_media
                )
                if blocks:
                    if len(text) > 160 or len(blocks) > 1:
                        self.doc.heading(
                            spec.label, min(4, level + 1), numbered=self.template.number_headings
                        )
                    else:
                        self.doc.para(f"{spec.label}:")
                    for block in blocks:
                        self.doc.add(block)
                    wrote = True
                    continue
            if spec.type is FieldType.LONG_TEXT and len(text) > 160:
                self.doc.heading(
                    spec.label, min(4, level + 1), numbered=self.template.number_headings
                )
                self.doc.para(text)
            else:
                self.doc.para(f"{spec.label}: {text}")
            wrote = True
        for spec, text in bullets:
            self.doc.heading(spec.label, min(4, level + 1), numbered=self.template.number_headings)
            if spec.is_rich_text:
                blocks = rich_text.to_report_blocks(
                    row.get(spec.key), resolve_media=self.resolve_media
                )
                if blocks:
                    for block in blocks:
                        self.doc.add(block)
                    wrote = True
                    continue
            self.doc.bullets([p.strip() for p in text.replace(";", "\n").split("\n")])
            wrote = True
        return wrote

    def _cell_runs(self, spec: FieldSpec, row: dict[str, Any], text: str) -> tuple[Run, ...]:
        if spec.is_rich_text and not rich_text.is_empty(row.get(spec.key)):
            return rich_text.plain_runs(row.get(spec.key))
        return runs_of(text)

    def _table_columns(self, entity: EntitySpec) -> list[FieldSpec]:
        columns = [
            f
            for f in entity.fields
            if self._visible(f) and f.report_role is ReportRole.TABLE_COLUMN and not f.type.is_media
        ]
        return columns[:6]

    def _render_table(
        self, entity: EntitySpec, rows: list[dict[str, Any]], section: TemplateSection, level: int
    ) -> bool:
        columns = self._table_columns(entity)
        if not columns:
            return self._render_cards(entity, rows, section, level)

        declared = sum(c.column_width_pct for c in columns)
        if declared and abs(declared - 100.0) < 0.5:
            widths = [c.column_width_pct for c in columns]
        else:
            weights = [2.0 if c.is_free_text else 1.0 for c in columns]
            total = sum(weights)
            widths = [100.0 * w / total for w in weights]
            widths[-1] += 100.0 - sum(widths)

        table_rows: list[tuple[tuple[Any, ...], ...]] = []
        for row in rows:
            printable = {
                spec.key: text
                for spec, text in self._printable(entity, row, {ReportRole.TABLE_COLUMN})
            }
            table_rows.append(
                tuple(
                    self._cell_runs(spec, row, printable.get(spec.key) or self._value(spec, row))
                    for spec in columns
                )
            )

        self.doc.add(
            TableBlock(
                columns=tuple(
                    TableColumn(
                        header=spec.label,
                        width_pct=w,
                        numeric=spec.type.is_numeric,
                        align=Align.RIGHT if spec.type.is_numeric else Align.LEFT,
                    )
                    for spec, w in zip(columns, widths)
                ),
                rows=tuple(table_rows),
                caption=entity.title,
            )
        )

        column_keys = {c.key for c in columns}
        for index, row in enumerate(rows, start=1):
            has_extra = any(
                s.key not in column_keys
                for s, _v in self._printable(
                    entity,
                    row,
                    {
                        ReportRole.NARRATIVE,
                        ReportRole.TABLE_COLUMN,
                        ReportRole.KEY_VALUE,
                        ReportRole.COVER_FIELD,
                        ReportRole.BULLETS,
                    },
                )
            )
            plates = (
                self._image_groups(entity, row, cap=section.max_photos)
                if section.include_photos
                else []
            )
            if not has_extra and not plates:
                continue
            self.doc.heading(
                self._row_label(entity, row, index),
                min(4, level + 1),
                numbered=self.template.number_headings,
            )
            self._place_image_groups(plates, section)
            self._render_narrative(entity, row, level + 1, skip=column_keys)
        return True

    def _render_cards(
        self, entity: EntitySpec, rows: list[dict[str, Any]], section: TemplateSection, level: int
    ) -> bool:
        for index, row in enumerate(rows, start=1):
            self.doc.heading(
                self._row_label(entity, row, index),
                min(4, level + 1),
                numbered=self.template.number_headings,
            )
            if section.include_photos:
                self._place_image_groups(
                    self._image_groups(entity, row, cap=section.max_photos), section
                )
            self._render_narrative(entity, row, level + 1)
        return bool(rows)

    def _parent_groups(
        self,
        entity: EntitySpec,
        rows: list[dict[str, Any]],
    ) -> list[tuple[str, list[dict[str, Any]]]] | None:
        parent = self._entities.get(entity.parent) if entity.parent else None
        if parent is None:
            return None
        link = next(
            (f for f in entity.fields if f.type is FieldType.REF and f.ref_model == parent.name),
            None,
        )
        if link is None:
            return None

        buckets: dict[str, list[dict[str, Any]]] = {}
        for row in rows:
            ref = row.get(link.key)
            buckets.setdefault(ref if isinstance(ref, str) and ref else "", []).append(row)

        groups: list[tuple[str, list[dict[str, Any]]]] = []
        parent_rows = self.data.rows(self._entity_stage.get(parent.key, ""), parent.key)
        for index, parent_row in enumerate(parent_rows, start=1):
            entry_id = parent_row.get("_entryId")
            taken = buckets.pop(entry_id, None) if isinstance(entry_id, str) and entry_id else None
            if taken:
                groups.append((self._row_label(parent, parent_row, index), taken))

        orphans = buckets.pop("", [])
        for ref, taken in buckets.items():
            label = self._ref_label(ref)
            if label:
                groups.append((label, taken))
            else:
                orphans.extend(taken)
        if orphans:
            groups.append((f"No {link.label.lower()} recorded", orphans))
        return groups

    def _render_rows(
        self,
        entity: EntitySpec,
        rows: list[dict[str, Any]],
        section: TemplateSection,
        presentation: Presentation,
        level: int,
    ) -> bool:
        if presentation is Presentation.TABLE:
            return self._render_table(entity, rows, section, level)
        if presentation is Presentation.CARDS:
            return self._render_cards(entity, rows, section, level)
        if presentation is Presentation.GALLERY:
            every = [img for row in rows for img in self._images(entity, row)]
            shown = every[: section.max_photos or len(every)]
            self._note_photographs_over_cap(len(every) - len(shown))
            self._place_images(shown, section)
            return True
        wrote = False
        for row in rows:
            wrote |= self._render_narrative(entity, row, level)
        return wrote

    def _row_label(self, entity: EntitySpec, row: dict[str, Any], index: int) -> str:
        return self._row_label_text(entity, row) or f"{entity.title} {index}"

    def _place_images(self, images: list[tuple[ImageRef, str]], section: TemplateSection) -> None:
        if not images:
            return
        if len(images) == 1:
            ref, caption = images[0]
            self.doc.add(ImageBlock(image=ref, width_pct=62.0, caption=caption))
            return
        self.doc.add(
            ImageGridBlock(
                images=tuple(images),
                columns=max(1, min(4, section.photo_columns)),
            )
        )

    def _place_image_groups(
        self, groups: list[tuple[str, list[tuple[ImageRef, str]]]], section: TemplateSection
    ) -> None:
        for caption, images in groups:
            if not images:
                continue
            if len(images) == 1:
                ref, image_caption = images[0]
                self.doc.add(
                    ImageBlock(image=ref, width_pct=62.0, caption=image_caption or caption)
                )
                continue
            self.doc.add(
                ImageGridBlock(
                    images=tuple(images),
                    columns=max(1, min(4, section.photo_columns)),
                    caption=caption,
                )
            )

    def _venue_point(self, setup: dict[str, Any]) -> _MapFacts:
        label = next(
            (
                clean_text(setup.get(key)).strip()
                for key in ("venue", "village", "block", "district")
                if clean_text(setup.get(key)).strip()
            ),
            "",
        )
        fix = _geo_point(setup.get("venueLocation"))
        if fix is not None:
            return _MapFacts(
                points=[
                    MapPoint(
                        label=label or "Workshop venue",
                        lat=fix[0],
                        lon=fix[1],
                        kind=MapPointKind.VENUE,
                    )
                ],
                placed=1,
                total=1,
            )

        address = ", ".join(
            text
            for text in (
                clean_text(setup.get(key)).strip()
                for key in ("venue", "village", "block", "district")
            )
            if text
        )
        if not address:
            return _MapFacts(total=1)
        located = _geocode(address, setup.get("state"), self.data.district_points)
        if located is None:
            return _MapFacts(total=1)
        return _MapFacts(
            points=[
                MapPoint(
                    label=located.label or label or "Workshop venue",
                    lat=located.lat,
                    lon=located.lon,
                    kind=MapPointKind.VENUE,
                )
            ],
            states={located.state} if located.state else set(),
            placed=1,
            total=1,
            approximate=0 if located.precise else 1,
        )

    def _artisan_points(self, state_hint: Any) -> _MapFacts:
        entity = self._entity(MAP_ROSTER_STAGE, MAP_ROSTER_ENTITY)
        if entity is None:
            return _MapFacts()
        ref_keys = [
            f.key for f in entity.fields if f.type is FieldType.REF and f.ref_model == "Artisan"
        ]

        from app.services.address import canonical_state

        rows = self.data.rows(MAP_ROSTER_STAGE, MAP_ROSTER_ENTITY)
        found: list[tuple[str, _Located]] = []
        facts = _MapFacts(total=len(rows))
        for row in rows:
            stated = [clean_text(row.get(key)).strip() for key in MAP_ROSTER_PLACE_KEYS]
            text = ", ".join(part for part in stated if part)
            place_label = next((part for part in stated if part), "")
            state = clean_text(row.get(MAP_ROSTER_STATE_KEY)).strip()
            if not text or not state:
                reference = next(
                    (
                        r
                        for r in (self.data.reference(row.get(key)) for key in ref_keys)
                        if r is not None
                    ),
                    None,
                )
                if reference is not None:
                    ref_place = clean_text(reference.place).strip()
                    ref_district = clean_text(reference.district).strip()
                    ref_text = ", ".join(part for part in (ref_place, ref_district) if part)
                    if ref_text:
                        text, state = ref_text, clean_text(reference.state).strip()
                        place_label = ref_place or ref_district
            if not state:
                state = clean_text(state_hint).strip()

            fix = _geo_point(row.get(MAP_ROSTER_PIN_KEY))
            if fix is not None:
                located = _Located(
                    lat=fix[0],
                    lon=fix[1],
                    state=canonical_state(state) or state,
                    label=place_label or "Artisan's home",
                )
            else:
                if not text:
                    continue
                located = _geocode(text, state, self.data.district_points)
                if located is None:
                    continue
            found.append((located.label, located))
            facts.placed += 1
            if located.state:
                facts.states.add(located.state)
            if not located.precise:
                facts.approximate += 1
        facts.points = _fold_points(found, MapPointKind.ARTISAN)
        return facts

    def _render_map(self, section: TemplateSection) -> None:
        setup = self.data.singleton(MAP_VENUE_STAGE)
        state = clean_text(setup.get("state")).strip()
        district = clean_text(setup.get("district")).strip()
        if not state and not district:
            return

        venue = self._venue_point(setup)
        artisans = self._artisan_points(state)
        facts = venue.merge(artisans)

        from app.services.address import canonical_state

        highlight = set(facts.states)
        if state:
            highlight.add(canonical_state(state) or state)

        stated_venue = ", ".join(
            text
            for text in (
                clean_text(setup.get(key)).strip() for key in ("venue", "village", "district")
            )
            if text
        )
        sentences: list[str] = []
        if venue.points and stated_venue:
            sentences.append(f"Workshop venue: {stated_venue}.")
        if artisans.placed:
            sentences.append(
                f"Home places of {artisans.placed} of {artisans.total} participating artisans, "
                f"at {count_noun(len(artisans.points), 'location')}."
            )
        if artisans.total > artisans.placed:
            sentences.append(
                f"{count_noun(artisans.total - artisans.placed, 'participant')} recorded no address that "
                f"could be placed."
            )
        if facts.approximate:
            sentences.append(
                "A place this atlas cannot resolve is drawn at its state capital, and its pin is "
                "labelled with the state rather than with the place."
            )
        if not facts.points:
            sentences.append(
                "No address in the record could be resolved to a position; the map shows the "
                "region only."
            )

        if section.page_break_before:
            self.doc.add(PageBreakBlock())
        self.doc.heading(
            section.heading or "Workshop location and participants' origins",
            1,
            numbered=self.template.number_headings,
        )
        if section.intro:
            self.doc.para(section.intro, style=ParaStyle.LEAD)
        self.doc.add(
            MapBlock(
                caption=" ".join(sentences),
                points=tuple(facts.points),
                highlight=frozenset(h for h in highlight if h),
            )
        )

    def _render_stage(self, spec: StageSpec, section: TemplateSection) -> None:
        self._stage_key = spec.key
        singleton_data = self.data.singleton(spec.key)
        has_rows = any(self.data.rows(spec.key, e.key) for e in printable_collections(spec))
        has_any = bool(singleton_data) or has_rows
        if not has_any and section.omit_if_empty:
            return

        if section.page_break_before:
            self.doc.add(PageBreakBlock())
        self.doc.heading(section.heading or spec.title, 1, numbered=self.template.number_headings)
        if section.intro:
            self.doc.para(section.intro, style=ParaStyle.LEAD)

        wrote = False
        single = spec.singleton
        if single is not None and singleton_data:
            wrote |= self._render_narrative(single, singleton_data, 1)
            metrics = self._printable(single, singleton_data, {ReportRole.METRIC})
            if metrics:
                self.doc.add(
                    MetricRowBlock(metrics=tuple((s.label, v, s.unit) for s, v in metrics[:4]))
                )
                wrote = True
            if section.include_photos:
                plates = self._image_groups(single, singleton_data, cap=section.max_photos)
                if plates:
                    self._place_image_groups(plates, section)
                    wrote = True

        collections = printable_collections(spec)
        for entity in collections:
            if section.entities and entity.key not in section.entities:
                continue
            rows = self.data.rows(spec.key, entity.key)
            if not rows:
                if not section.omit_if_empty:
                    self.doc.heading(entity.title, 2, numbered=self.template.number_headings)
                    self.doc.para(f"No {entity.title.lower()} were recorded.", style=ParaStyle.NOTE)
                continue
            if len(collections) > 1:
                self.doc.heading(entity.title, 2, numbered=self.template.number_headings)
            presentation = section.presentation
            if presentation is Presentation.AUTO:
                presentation = (
                    Presentation.TABLE if self._table_columns(entity) else Presentation.CARDS
                )
            groups = self._parent_groups(entity, rows)
            if groups is None:
                wrote |= self._render_rows(entity, rows, section, presentation, 1)
                continue
            group_level = 3 if len(collections) > 1 else 2
            for title, group_rows in groups:
                self.doc.heading(title, group_level, numbered=self.template.number_headings)
                wrote |= self._render_rows(entity, group_rows, section, presentation, group_level)

        if section.include_figures:
            for chart in self._charts_for(spec.key):
                self.doc.add(chart)
                wrote = True

        if not wrote and self.template.show_empty_note:
            self.doc.para("Not recorded.", style=ParaStyle.NOTE)

    def _entity(self, stage_key: str, entity_key: str) -> EntitySpec | None:
        stage = self._stages.get(stage_key)
        if stage is None:
            return None
        return next((e for e in stage.entities if e.key == entity_key), None)

    def _label_of(self, stage_key: str, entity_key: str, field_key: str, fallback: str) -> str:
        entity = self._entity(stage_key, entity_key)
        spec = entity.field(field_key) if entity else None
        return spec.label if spec else fallback

    def _output_count(self, stage_key: str, entity_key: str, override_key: str = "") -> int:
        rows = len(self.data.rows(stage_key, entity_key))
        if not override_key:
            return rows
        stated = _as_number(self.data.value("WORKSHOP_OUTCOMES", override_key))
        if stated is None or stated < 0:
            return rows
        return int(stated)

    def _count_override_reason(self) -> str:
        outcomes = self.data.singleton("WORKSHOP_OUTCOMES")
        if not any(
            _as_number(outcomes.get(key)) is not None
            for key in ("designsCountOverride", "prototypesCountOverride")
        ):
            return ""
        return clean_text(outcomes.get("countOverrideReason")).strip()

    def _chart_output_counts(self) -> ChartBlock | None:
        counts = (
            (
                "Sketches",
                self._output_count("SKETCH_DEVELOPMENT", "sketch", "designsCountOverride"),
            ),
            (
                "Prototypes",
                self._output_count("PROTOTYPE_DEVELOPMENT", "prototype", "prototypesCountOverride"),
            ),
            ("Final products", self._output_count("FINAL_PROTOTYPE_DOCUMENTATION", "finalProduct")),
        )
        series = [(label, float(n)) for label, n in counts if n]
        if len(series) < MIN_CHART_CATEGORIES:
            return None
        reason = self._count_override_reason()
        return ChartBlock(
            kind=ChartKind.BAR,
            series=tuple(series),
            title="Designs, prototypes and final products",
            caption=reason or "Counted from the records in this report, not typed.",
        )

    def _chart_prototype_status(self) -> ChartBlock | None:
        entity = self._entity("PROTOTYPE_VALIDATION", "prototypeValidation")
        spec = entity.field("decision") if entity else None
        if spec is None:
            return None
        tally: dict[str, int] = {}
        for row in self.data.rows("PROTOTYPE_VALIDATION", "prototypeValidation"):
            token = clean_text(row.get("decision")).strip()
            if not token:
                continue
            tally[enum_label(spec.enum, token)] = tally.get(enum_label(spec.enum, token), 0) + 1
        if len(tally) < MIN_CHART_CATEGORIES:
            return None
        return ChartBlock(
            kind=ChartKind.DONUT,
            series=tuple((label, float(count)) for label, count in tally.items()),
            title="Prototypes by review decision",
            caption=f"{count_noun(sum(tally.values()), 'prototype')} reviewed.",
        )

    def _survey_responses(self) -> list[dict[str, Any]]:
        return self.data.rows("MARKET_SURVEY_CAPTURE", "surveyResponse")

    def _unentered_responses(self) -> int:
        stated = _as_number(self.data.value("MARKET_SURVEY_CAPTURE", "responsesCollected"))
        if stated is None or stated < 0:
            return 0
        return max(0, int(stated) - len(self._survey_responses()))

    def _chart_survey_respondents(self) -> ChartBlock | None:
        entity = self._entity("MARKET_SURVEY_CAPTURE", "surveyResponse")
        spec = entity.field("respondentGroup") if entity else None
        if spec is None:
            return None
        from app.services.stage_schema import ENUMS

        known = list(ENUMS.get(spec.enum, {}))
        tally: dict[str, int] = {}
        unstated = 0
        for row in self._survey_responses():
            token = clean_text(row.get("respondentGroup")).strip()
            if not token:
                unstated += 1
                continue
            tally[token] = tally.get(token, 0) + 1
        order = [t for t in known if t in tally] + [t for t in tally if t not in known]
        series = [(enum_label(spec.enum, token), float(tally[token])) for token in order]
        if len(series) < MIN_CHART_CATEGORIES:
            return None
        plotted = sum(tally.values())
        caption = f"{count_noun(plotted, 'response')} plotted, from the rows entered at this workshop."
        if unstated:
            caption += f" {unstated} more recorded no respondent group and could not be plotted."
        unentered = self._unentered_responses()
        if unentered:
            caption += (
                f" The survey summary states {count_noun(plotted + unstated + unentered, 'response')} "
                f"collected, so {unentered} were never entered as rows and are in no figure here."
            )
        return ChartBlock(
            kind=ChartKind.HORIZONTAL_BAR,
            series=tuple(series),
            title="Survey responses by respondent group",
            unit="responses",
            caption=caption,
        )

    def _chart_survey_price_expectations(self) -> ChartBlock | None:
        responses = self._survey_responses()
        prices: list[float] = []
        for row in responses:
            amount = _as_number(row.get("priceExpectation"))
            if amount is not None and amount > 0:
                prices.append(amount)
        bands = _price_bands(prices)
        if len(bands) < MIN_CHART_CATEGORIES:
            return None
        caption = (
            f"{len(prices)} of {count_noun(len(responses), 'response')} stated a price expectation, "
            "in rupees."
        )
        unentered = self._unentered_responses()
        if unentered:
            caption += (
                f" A further {count_noun(unentered, 'response')} the summary counts "
                f"{by_count(unentered, 'was', 'were')} never entered as rows."
            )
        return ChartBlock(
            kind=ChartKind.BAR,
            series=tuple(bands),
            title="What respondents said they would pay",
            unit="responses",
            caption=caption,
        )

    def _chart_cost_by_head(self) -> ChartBlock | None:
        heads = (
            "materialCost",
            "labourCost",
            "packagingCost",
            "finishingCost",
            "transportCost",
            "overheadCost",
        )
        totals = dict.fromkeys(heads, 0.0)
        for row in self.data.rows("COSTING_MARKET_LINKAGE", "costSheet"):
            for key in heads:
                amount = _as_number(row.get(key))
                if amount is not None and amount > 0:
                    totals[key] += amount
        series = [
            (self._label_of("COSTING_MARKET_LINKAGE", "costSheet", key, key), value)
            for key, value in totals.items()
            if value > 0
        ]
        if len(series) < MIN_CHART_CATEGORIES:
            return None
        return ChartBlock(
            kind=ChartKind.HORIZONTAL_BAR,
            series=tuple(series),
            title="Cost by head, all products",
            unit="INR",
            caption="Summed across every cost sheet recorded at this workshop.",
        )

    def _chart_price_bands(self) -> ChartBlock | None:
        prices: list[float] = []
        for row in self.data.rows("COSTING_MARKET_LINKAGE", "costSheet"):
            amount = next(
                (
                    value
                    for value in (
                        _as_number(row.get("expectedPrice")),
                        _as_number(row.get("retailPrice")),
                    )
                    if value is not None and value > 0
                ),
                None,
            )
            if amount is not None:
                prices.append(amount)
        bands = _price_bands(prices)
        if len(bands) < MIN_CHART_CATEGORIES:
            return None
        return ChartBlock(
            kind=ChartKind.BAR,
            series=tuple(bands),
            title="Products by price band",
            unit="products",
            caption=f"{count_noun(len(prices), 'product')} with a recorded price, in rupees.",
        )

    def _chart_adoption(self) -> ChartBlock | None:
        entity = self._entity("POST_WORKSHOP_FOLLOWUP", "followUp")
        interval_spec = entity.field("interval") if entity else None
        if interval_spec is None:
            return None
        from app.services.stage_schema import ENUMS

        order = [token for token in ENUMS.get(interval_spec.enum, {}) if token != "AD_HOC"]
        adopted = {"ADOPTED_IN_PRODUCTION", "ADOPTED_ON_ORDER"}
        tally: dict[str, int] = {}
        seen: set[str] = set()
        for row in self.data.rows("POST_WORKSHOP_FOLLOWUP", "followUp"):
            token = clean_text(row.get("interval")).strip()
            if token not in order:
                continue
            seen.add(token)
            if clean_text(row.get("adoptionStatus")).strip() in adopted:
                tally[token] = tally.get(token, 0) + 1
        series = [
            (enum_label(interval_spec.enum, token), float(tally.get(token, 0)))
            for token in order
            if token in seen
        ]
        if len(series) < MIN_CHART_CATEGORIES or sum(v for _l, v in series) <= 0:
            return None
        return ChartBlock(
            kind=ChartKind.LINE,
            series=tuple(series),
            title="Products still in production at follow-up",
            unit="products",
            caption="Counted from follow-up visits that were actually made.",
        )

    def _figure(self, figure_id: str) -> ChartBlock | None:
        entry = FIGURES.get(figure_id)
        if entry is None or figure_id in self._drawn:
            return None
        block = getattr(self, entry[1])()
        if block is None or block.total <= 0:
            return None
        self._drawn.add(figure_id)
        return block

    def _charts_for(self, stage_key: str) -> list[ChartBlock]:
        blocks = [
            self._figure(figure_id)
            for figure_id, (owner, _method) in FIGURES.items()
            if owner == stage_key
        ]
        return [block for block in blocks if block is not None]

    def _render_charts(self, section: TemplateSection) -> None:
        wanted = section.figures or tuple(FIGURES)
        blocks = [block for block in (self._figure(f) for f in wanted) if block is not None]
        if not blocks:
            return
        if section.page_break_before:
            self.doc.add(PageBreakBlock())
        self.doc.heading(
            section.heading or "The workshop in figures", 1, numbered=self.template.number_headings
        )
        if section.intro:
            self.doc.para(section.intro, style=ParaStyle.LEAD)
        for block in blocks:
            self.doc.add(block)

    def _render_cover(self, section: TemplateSection) -> None:
        setup = self.data.singleton("WORKSHOP_SETUP")
        spec = self._stages.get("WORKSHOP_SETUP")
        keyed: list[tuple[str, str, str]] = []
        if spec and spec.singleton:
            for f in spec.singleton.fields:
                if f.report_role is not ReportRole.COVER_FIELD:
                    continue
                if not (self._visible(f) or (f.key in COVER_PINNED_KEYS and not f.deprecated)):
                    continue
                text = format_value(f, setup.get(f.key))
                if text:
                    keyed.append((f.key, f.label, text))
        printed, overflow = cover_rows(keyed)

        settings = self.data.singleton("REPORT_GENERATION")

        hero = None
        if spec and spec.singleton and section.include_photos:
            images = self._images(spec.singleton, setup, limit=1)
            if images:
                hero = images[0][0]
        logo_ids = _media_ids(settings.get("logo"))
        logo = self.resolve_media(logo_ids[0]) if logo_ids else None

        org = self.doc.meta.organisation or self.template.organisation
        letterhead = [
            line.strip()
            for line in clean_text(settings.get("letterheadText")).split("\n")
            if line.strip()
        ][:6]
        self.doc.add(
            CoverBlock(
                title=self.doc.meta.title,
                subtitle=self.doc.meta.subtitle,
                org_lines=tuple(
                    x for x in ("Government of India • Ministry of Textiles", org, *letterhead) if x
                ),
                logo=logo,
                hero_image=hero,
                info_rows=tuple(printed),
                footer_lines=tuple(
                    x
                    for x in (
                        _submission_line(settings),
                        (
                            f"Generated on {_format_date(self.doc.meta.generated_at[:10])}"
                            if self.doc.meta.generated_at
                            else ""
                        ),
                    )
                    if x
                ),
            )
        )

        if overflow and self.template.section_for("WORKSHOP_SETUP") is None:
            self.cover_fields_dropped = tuple(label for label, _v in overflow)

    def _render_summary_metrics(self, section: TemplateSection) -> None:
        metrics: list[tuple[str, str, str]] = []
        counts = (
            ("Artisans", "WORKSHOP_PLAN_PARTICIPANTS_OPENING", "participant", ""),
            ("Sketches", "SKETCH_DEVELOPMENT", "sketch", "designsCountOverride"),
            ("Prototypes", "PROTOTYPE_DEVELOPMENT", "prototype", "prototypesCountOverride"),
            ("Final products", "FINAL_PROTOTYPE_DOCUMENTATION", "finalProduct", ""),
        )
        reason = self._count_override_reason()
        for label, stage_key, entity_key, override_key in counts:
            n = self._output_count(stage_key, entity_key, override_key)
            if n:
                metrics.append((label, str(n), ""))
        if not metrics:
            return
        if section.heading:
            self.doc.heading(section.heading, 1, numbered=self.template.number_headings)
        self.doc.add(MetricRowBlock(metrics=tuple(metrics[:4])))
        if reason:
            self.doc.para(f"Stated counts: {reason}", style=ParaStyle.NOTE)

    def _render_signatures(self, section: TemplateSection) -> None:
        setup = self.data.singleton("WORKSHOP_SETUP")
        closing = self.data.singleton("INSPECTION_CLOSING")
        signatories: list[tuple[str, str]] = []
        designer = clean_text(setup.get("designerName"))
        if designer:
            signatories.append((designer, "Designer"))
        agency = clean_text(setup.get("implementingAgency"))
        if agency:
            signatories.append((agency, "Implementing Agency"))
        officer = clean_text(closing.get("inspectingOfficer"))
        if officer:
            signatories.append((officer, "Inspecting Officer"))
        if not signatories:
            return
        if section.page_break_before:
            self.doc.add(PageBreakBlock())
        self.doc.heading(
            section.heading or "Certification", 1, numbered=self.template.number_headings
        )
        self.doc.para(
            "Certified that the workshop was conducted and the prototypes documented above were "
            "developed during the period stated on the cover of this report."
        )
        self.doc.add(SignatureBlock(signatories=tuple(signatories)))
        for line in self.data.sign_off_lines:
            self.doc.para(line)

    def _render_media_annexure(self, section: TemplateSection) -> None:
        gathered: list[tuple[ImageRef, str]] = []
        for spec in stages():
            for entity in spec.entities:
                if entity.cardinality is Cardinality.SINGLETON:
                    sources = [self.data.singleton(spec.key)]
                else:
                    sources = self.data.rows(spec.key, entity.key)
                for row in sources:
                    for ref, caption in self._images(entity, row):
                        gathered.append((ref, caption or spec.title))
        if not gathered:
            return
        if section.page_break_before:
            self.doc.add(PageBreakBlock())
        self.doc.heading(
            section.heading or "Photographic record", 1, numbered=self.template.number_headings
        )
        self.doc.add(ImageGridBlock(images=tuple(gathered), columns=3))

    def ref_resolves(self, value: Any) -> bool:
        return bool(self._ref_label(value))

    def _render_completeness(self, section: TemplateSection) -> None:
        rows: list[tuple[Any, ...]] = []
        for spec in stages():
            custom_fields, custom_values = custom_scoring(self.data, spec.key)
            score = stage_completeness(
                spec,
                self.data.singleton(spec.key),
                {e.key: self.data.rows(spec.key, e.key) for e in spec.collections},
                ref_resolves=self.ref_resolves,
                custom_fields=custom_fields,
                custom_values=custom_values,
            )
            rows.append(
                (
                    runs_of(f"{spec.number}. {spec.title}"),
                    runs_of(f"{score.required_filled}/{score.required_total}"),
                    runs_of(f"{score.percent}%"),
                    runs_of("Complete" if score.is_complete else ", ".join(score.missing[:3])),
                )
            )
        if not rows:
            return
        self.doc.heading(
            section.heading or "Data completeness", 1, numbered=self.template.number_headings
        )
        self.doc.add(
            TableBlock(
                columns=(
                    TableColumn("Stage", 40.0),
                    TableColumn("Required fields", 15.0, numeric=True),
                    TableColumn("Complete", 12.0, numeric=True),
                    TableColumn("Outstanding", 33.0),
                ),
                rows=tuple(rows),
            )
        )

    def build(self) -> ReportDocument:
        for section in self.template.sections:
            if section.special is SpecialSection.COVER:
                self._render_cover(section)
            elif section.special is SpecialSection.TOC:
                self.doc.add(TocBlock(depth=3))
            elif section.special is SpecialSection.SUMMARY_METRICS:
                self._render_summary_metrics(section)
            elif section.special is SpecialSection.SIGNATURES:
                self._render_signatures(section)
            elif section.special is SpecialSection.ANNEXURE_MEDIA:
                self._render_media_annexure(section)
            elif section.special is SpecialSection.ANNEXURE_TRANSCRIPTS:
                append_transcript_annexure(
                    self.doc,
                    transcripts_of(self.data),
                    heading=section.heading,
                    numbered=self.template.number_headings,
                    page_break_before=section.page_break_before,
                )
            elif section.special is SpecialSection.ANNEXURE_QUESTIONNAIRES:
                append_questionnaire_annexure(
                    self.doc,
                    questionnaires_of(self.data),
                    heading=section.heading,
                    numbered=self.template.number_headings,
                    page_break_before=section.page_break_before,
                )
            elif section.special is SpecialSection.ANNEXURE_AI_LAYERS:
                append_ai_layer_annexure(
                    self.doc,
                    ai_layers_of(self.data),
                    heading=section.heading,
                    numbered=self.template.number_headings,
                    page_break_before=section.page_break_before,
                )
            elif section.special is SpecialSection.CUSTOM_SECTION:
                append_custom_section(
                    self.doc,
                    custom_section_of(self.data, section.custom_key),
                    heading=section.heading,
                    numbered=self.template.number_headings,
                    page_break_before=section.page_break_before,
                    max_tier_rank=self.template.max_tier.rank,
                )
            elif section.special is SpecialSection.COMPLETENESS:
                self._render_completeness(section)
            elif section.special is SpecialSection.MAP:
                self._render_map(section)
            elif section.special is SpecialSection.CHART:
                self._render_charts(section)
            elif section.special is SpecialSection.ACKNOWLEDGEMENT:
                text = self.data.value("INTRODUCTORY_ADMIN_DOCUMENTATION", "acknowledgement")
                if clean_text(text).strip():
                    self.doc.heading(
                        section.heading or "Acknowledgement",
                        1,
                        numbered=self.template.number_headings,
                    )
                    self.doc.para(text)
            elif section.stage_key:
                spec = self._stages.get(section.stage_key)
                if spec is not None:
                    self._render_stage(spec, section)
        return self.doc.build()


def build_report(
    data: WorkshopData,
    template_id: str,
    resolve_media: MediaResolver,
    *,
    meta: ReportMeta,
    theme: ReportTheme | None = None,
    template: ReportTemplate | None = None,
) -> tuple[ReportDocument, list[str]]:
    if template is None:
        template = get_template(template_id)
    warnings: list[str] = []
    if template.id != template_id and template_id:
        warnings.append(
            f"Template {template_id!r} is no longer available; the report was generated with "
            f"{template.name!r} instead."
        )

    builder = ReportBuilder(data, template, resolve_media, meta=meta, theme=theme)
    document = builder.build()

    for spec in stages():
        if not template.section_for(spec.key):
            continue
        custom_fields, custom_values = custom_scoring(data, spec.key)
        score = stage_completeness(
            spec,
            data.singleton(spec.key),
            {e.key: data.rows(spec.key, e.key) for e in spec.collections},
            custom_fields=custom_fields,
            custom_values=custom_values,
            ref_resolves=builder.ref_resolves,
        )
        if not score.is_complete and score.missing:
            warnings.append(
                f"Stage {spec.number} ({spec.title}): "
                f"{count_noun(len(score.missing), 'required field')} "
                f"not recorded — {', '.join(score.missing[:4])}"
                + ("…" if len(score.missing) > 4 else "")
            )

    lost_by_tier = builder.fields_hidden_by_tier()
    if lost_by_tier:
        total = sum(len(labels) for _spec, labels in lost_by_tier)
        where = ", ".join(f"stage {spec.number}" for spec, _labels in lost_by_tier[:4])
        warnings.append(
            f"{count_noun(total, 'field')} recorded in this workshop {by_count(total, 'is', 'are')} "
            f"above {template.name}'s capture tier ({template.max_tier.value.title()}) and "
            f"{by_count(total, 'is', 'are')} not in this file — {where}"
            + ("…" if len(lost_by_tier) > 4 else "")
            + ". Generate the report with a template that captures every tier to include "
            + f"{by_count(total, 'it', 'them')}."
        )

    over_cap = builder.photographs_over_cap()
    if over_cap:
        total = sum(count for _spec, count in over_cap)
        where = ", ".join(f"stage {spec.number}" for spec, _count in over_cap[:4])
        warnings.append(
            f"{count_noun(total, 'photograph')} recorded in this workshop did not fit "
            f"{template.name}'s photograph cap and {by_count(total, 'is', 'are')} not in this file "
            f"— {where}"
            + ("…" if len(over_cap) > 4 else "")
            + ". Generate the report with a template that prints every photograph to include "
            + f"{by_count(total, 'it', 'them')}."
        )

    attachments = builder.attachments_named_but_not_carried()
    if attachments:
        total = sum(count for _spec, count in attachments)
        where = ", ".join(f"stage {spec.number}" for spec, _count in attachments[:4])
        warnings.append(
            f"{total} attached {by_count(total, 'file is', 'files are')} named in this report but "
            f"the {by_count(total, 'file itself is', 'files themselves are')} not "
            f"inside it — {where}"
            + ("…" if len(attachments) > 4 else "")
            + ". A report file cannot carry a document, a recording or a video; send them "
            "alongside it."
        )

    if builder.cover_fields_dropped:
        names = ", ".join(builder.cover_fields_dropped[:4])
        warnings.append(
            f"{count_noun(len(builder.cover_fields_dropped), 'cover field')} did not fit the cover "
            f"table and the {template.name} template prints no workshop-setup section to carry "
            f"{by_count(len(builder.cover_fields_dropped), 'it, so it is', 'them, so they are')} "
            f"not in this file: {names}"
            + ("…" if len(builder.cover_fields_dropped) > 4 else "")
            + "."
        )

    hidden = sections_hidden_by_tier(
        [
            custom_section_of(data, section.custom_key)
            for section in template.sections
            if section.special is SpecialSection.CUSTOM_SECTION
        ],
        template.max_tier.rank,
    )
    if hidden:
        titles = sorted(item.title for item in hidden)
        warnings.append(
            f"{len(hidden)} of this workshop's own sections {by_count(len(hidden), 'asks', 'ask')} "
            f"only questions above {template.name}'s capture tier "
            f"({template.max_tier.value.title()}) and {by_count(len(hidden), 'is', 'are')} not in "
            f"this file: " + ", ".join(titles[:4]) + ("…" if len(titles) > 4 else "")
        )

    return document, warnings
