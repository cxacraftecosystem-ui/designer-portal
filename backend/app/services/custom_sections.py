from __future__ import annotations

import hashlib
import re
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from typing import Any

from app.core.db import db
from app.services.stage_schema import (
    FieldSpec,
    FieldType,
    Tier,
    _is_filled,
    coerce_value,
    stages,
)
from app.services.text_format import by_count, count_noun

CUSTOM_ENTITY_KEY = "_custom"

V1_FIELD_TYPES: frozenset[FieldType] = frozenset(
    {
        FieldType.TEXT,
        FieldType.LONG_TEXT,
        FieldType.INT,
        FieldType.DECIMAL,
        FieldType.MONEY,
        FieldType.PERCENT,
        FieldType.DATE,
        FieldType.TIME,
        FieldType.BOOL,
        FieldType.ENUM,
        FieldType.MULTI_ENUM,
        FieldType.TAGS,
    }
)

_LIST_TYPES: frozenset[FieldType] = frozenset({FieldType.MULTI_ENUM, FieldType.TAGS})

_OPTION_TYPES: frozenset[FieldType] = frozenset({FieldType.ENUM, FieldType.MULTI_ENUM})

_BOUNDED_TYPES: frozenset[FieldType] = frozenset(
    {
        FieldType.INT,
        FieldType.DECIMAL,
        FieldType.MONEY,
        FieldType.PERCENT,
    }
)

_LENGTH_TYPES: frozenset[FieldType] = frozenset({FieldType.TEXT, FieldType.LONG_TEXT})

KEY_PATTERN = re.compile(r"^[a-z][A-Za-z0-9]{0,39}$")

MAX_CUSTOM_SECTIONS = 12
MAX_CUSTOM_FIELDS_PER_SECTION = 60
MAX_CUSTOM_OPTIONS = 100
MAX_CUSTOM_LABEL_CHARS = 160
MAX_CUSTOM_HELP_CHARS = 600
MAX_CUSTOM_TITLE_CHARS = 160
MAX_CUSTOM_DESCRIPTION_CHARS = 600
MAX_CUSTOM_KEY_CHARS = 40
MAX_CUSTOM_UNIT_CHARS = 24

MAX_SUPERSEDE_HOPS = 8



@dataclass(frozen=True, slots=True)
class CustomOption:
    value: str
    label: str = ""

    @property
    def display(self) -> str:
        return self.label.strip() or self.value


@dataclass(frozen=True, slots=True)
class CustomFieldSpec:
    key: str
    label: str
    type: FieldType
    tier: Tier = Tier.STANDARD
    required: bool = False
    help: str = ""
    unit: str = ""
    options: tuple[CustomOption, ...] = ()
    max_length: int = 0
    min_value: float | None = None
    max_value: float | None = None
    sort_order: int = 0
    retired: bool = False
    superseded_by: str = ""
    id: str = ""

    @property
    def option_values(self) -> tuple[str, ...]:
        return tuple(o.value for o in self.options)

    def option_label(self, value: Any) -> str:
        token = str(value)
        return next((o.display for o in self.options if o.value == token), token)


@dataclass(frozen=True, slots=True)
class CustomSectionSpec:
    key: str
    title: str
    stage_key: str
    description: str = ""
    sort_order: int = 0
    fields: tuple[CustomFieldSpec, ...] = ()
    retired: bool = False
    revision: int = 1
    id: str = ""

    @property
    def live_fields(self) -> tuple[CustomFieldSpec, ...]:
        return tuple(f for f in self.fields if not f.retired)


@dataclass(frozen=True, slots=True)
class CustomDefinition:
    sections: tuple[CustomSectionSpec, ...] = ()
    version: str = ""

    @property
    def is_empty(self) -> bool:
        return not self.sections

    def sections_for(self, stage_key: str) -> tuple[CustomSectionSpec, ...]:
        return tuple(s for s in self.sections if s.stage_key == stage_key and not s.retired)

    def fields_for(self, stage_key: str) -> tuple[CustomFieldSpec, ...]:
        out: list[CustomFieldSpec] = []
        slot_of: dict[str, int] = {}
        for section in sorted(
            (s for s in self.sections if s.stage_key == stage_key),
            key=lambda s: (s.sort_order, s.key),
        ):
            for f in sorted(section.fields, key=lambda f: (f.sort_order, f.key)):
                spec = f if not section.retired else replace(f, retired=True)
                slot = slot_of.get(spec.key)
                if slot is None:
                    slot_of[spec.key] = len(out)
                    out.append(spec)
                elif out[slot].retired and not spec.retired:
                    out[slot] = spec
        return tuple(out)

    def fields_by_stage(self) -> dict[str, tuple[CustomFieldSpec, ...]]:
        keys = {s.stage_key for s in self.sections if not s.retired}
        return {key: self.fields_for(key) for key in keys}


EMPTY_DEFINITION = CustomDefinition()



def custom_schema_version(sections: Sequence[CustomSectionSpec]) -> str:
    parts: list[str] = []
    for s in sections:
        if s.fields:
            parts.append(f"{s.stage_key}.{s.key}:§:{s.title}:{s.sort_order}:{int(s.retired)}")
        for f in s.fields:
            options = ",".join(f"{o.value}={o.display}" for o in f.options)
            parts.append(
                f"{s.stage_key}.{s.key}.{f.key}:{f.type.value}:{f.tier.value}:"
                f"{int(f.required)}:{f.label}:{f.unit}:{options}:"
                f"{int(f.retired)}:{int(s.retired)}"
            )
    if not parts:
        return ""
    digest = hashlib.sha256("|".join(sorted(parts)).encode("utf-8")).hexdigest()
    return digest[:16]



def validate_definition(sections: Sequence[CustomSectionSpec]) -> list[str]:
    problems: list[str] = []
    by_stage_key = {s.key: s for s in stages()}

    if len(sections) > MAX_CUSTOM_SECTIONS:
        problems.append(
            f"A workshop may carry at most {MAX_CUSTOM_SECTIONS} custom sections and this one "
            f"names {len(sections)}. Merge two of them, or move the questions that belong to a "
            f"different stage into a section on that stage."
        )

    seen_section_keys: dict[str, str] = {}
    seen_field_keys: dict[str, str] = {}
    seen_labels: dict[tuple[str, str], str] = {}

    for section in sections:
        where = section.key or "(unnamed)"
        if not KEY_PATTERN.match(section.key or ""):
            problems.append(
                f"Section key {section.key!r} cannot be used. A key must start with a lower-case "
                f"letter, carry only letters and digits after it, and be at most "
                f"{MAX_CUSTOM_KEY_CHARS} characters — it is what the answers are stored under and "
                f"it is never shown to anybody."
            )
        elif section.key in seen_section_keys:
            problems.append(
                f"Two sections are both keyed {section.key!r}. A section key is unique within the "
                f"workshop; rename one of them."
            )
        else:
            seen_section_keys[section.key] = section.title

        if not (section.title or "").strip():
            problems.append(
                f"Section {where} has no title. Give it the heading it should print under."
            )
        elif len(section.title) > MAX_CUSTOM_TITLE_CHARS:
            problems.append(
                f"Section {where}'s title is longer than {MAX_CUSTOM_TITLE_CHARS} characters."
            )
        if len(section.description or "") > MAX_CUSTOM_DESCRIPTION_CHARS:
            problems.append(
                f"Section {where}'s description is longer than {MAX_CUSTOM_DESCRIPTION_CHARS} "
                f"characters."
            )

        stage = by_stage_key.get(section.stage_key or "")
        if stage is None:
            problems.append(
                f"Section {where} names {section.stage_key or '(nothing)'!r}, which is not one of "
                f"the 22 stages. Choose the stage these questions are asked at — that is where the "
                f"answers are stored and where they are counted towards the stage's completeness. "
                f"If the section should print at the back of the report instead, it still belongs "
                f"to the stage it is ASKED at; a template that does not print that stage prints the "
                f"section as its own annexure."
            )

        if len(section.fields) > MAX_CUSTOM_FIELDS_PER_SECTION:
            problems.append(
                f"Section {where} declares {len(section.fields)} fields; the limit is "
                f"{MAX_CUSTOM_FIELDS_PER_SECTION}. Split it into two sections."
            )

        for f in section.fields:
            problems.extend(_field_problems(section, f, stage, seen_field_keys, seen_labels))

    return problems


def _field_problems(
    section: CustomSectionSpec,
    f: CustomFieldSpec,
    stage: Any,
    seen_field_keys: dict[str, str],
    seen_labels: dict[tuple[str, str], str],
) -> list[str]:
    problems: list[str] = []
    where = f"{section.key}.{f.key}" if f.key else f"{section.key}.(unnamed)"

    if not KEY_PATTERN.match(f.key or ""):
        problems.append(
            f"Field key {f.key!r} in section {section.key!r} cannot be used. A key must start with "
            f"a lower-case letter, carry only letters and digits after it, and be at most "
            f"{MAX_CUSTOM_KEY_CHARS} characters. It is what the answer is stored under and it can "
            f"never be renamed — renaming one orphans the answers already given under it."
        )
    elif f.key in seen_field_keys:
        problems.append(
            f"Field key {f.key!r} is used twice ({seen_field_keys[f.key]} and {where}). A field "
            f"key is unique across the whole workshop, because the answers for one stage are all "
            f"stored side by side and two fields sharing a key would share one answer."
        )
    else:
        seen_field_keys[f.key] = where

    if not (f.label or "").strip():
        problems.append(
            f"Field {where} has no label. The label is the question the designer reads."
        )
    elif len(f.label) > MAX_CUSTOM_LABEL_CHARS:
        problems.append(
            f"Field {where}'s label is longer than {MAX_CUSTOM_LABEL_CHARS} characters. A label is "
            f"a question, not a paragraph — put the explanation in the help text."
        )
    if len(f.help or "") > MAX_CUSTOM_HELP_CHARS:
        problems.append(
            f"Field {where}'s help text is longer than {MAX_CUSTOM_HELP_CHARS} characters."
        )
    if len(f.unit or "") > MAX_CUSTOM_UNIT_CHARS:
        problems.append(f"Field {where}'s unit is longer than {MAX_CUSTOM_UNIT_CHARS} characters.")

    if f.type not in V1_FIELD_TYPES:
        allowed = ", ".join(sorted(t.value for t in V1_FIELD_TYPES))
        problems.append(
            f"Field {where}: a custom question can't be of type {f.type.value}. "
            f"Choose one of: {allowed}."
        )

    if f.required and f.tier is not Tier.BASIC:
        problems.append(
            f"Field {where} is required but its tier is {f.tier.value}. Only a Basic field may be "
            f"required — the tiers exist so a workshop held somewhere without power or a "
            f"specialist can still be completed, and a required Standard field makes that "
            f"impossible."
        )

    if f.type in _OPTION_TYPES:
        if len(f.options) < 2:
            problems.append(
                f"Field {where} is a {f.type.value} with {count_noun(len(f.options), 'option')}. "
                f"A choice needs "
                f"at least two; a single-option list is a label."
            )
        if len(f.options) > MAX_CUSTOM_OPTIONS:
            problems.append(
                f"Field {where} declares {len(f.options)} options; the limit is "
                f"{MAX_CUSTOM_OPTIONS}."
            )
        seen_values: set[str] = set()
        for option in f.options:
            token = (option.value or "").strip()
            if not token:
                problems.append(f"Field {where} has an option with no value.")
            elif token in seen_values:
                problems.append(
                    f"Field {where} lists the option {token!r} twice. Two options with one value "
                    f"are one answer under two labels."
                )
            seen_values.add(token)
    elif f.options:
        problems.append(
            f"Field {where} is a {f.type.value} and cannot carry options. Remove them, or make it "
            f"a choice."
        )

    if f.min_value is not None and f.max_value is not None and f.min_value > f.max_value:
        problems.append(
            f"Field {where} has a smallest value ({f.min_value:g}) above its largest "
            f"({f.max_value:g})."
        )
    if (f.min_value is not None or f.max_value is not None) and f.type not in _BOUNDED_TYPES:
        problems.append(
            f"Field {where} is a {f.type.value} and its smallest/largest value would never be "
            f"checked. Remove the bounds, or make the field a number."
        )
    if f.max_length and f.type not in _LENGTH_TYPES:
        problems.append(
            f"Field {where} is a {f.type.value} and its maximum length would never be checked. "
            f"Remove it, or make the field text."
        )

    if stage is None:
        return problems

    for entity in stage.entities:
        core = entity.field(f.key)
        if core is not None and not core.deprecated:
            problems.append(
                f"{f.key!r} is already a field of stage {stage.number} "
                f"({stage.title} → {entity.title}: {core.label}). Choose another key."
            )

    singleton = stage.singleton
    if singleton is not None:
        clash = next(
            (
                c
                for c in singleton.fields
                if not c.deprecated
                and c.label.strip().casefold() == (f.label or "").strip().casefold()
            ),
            None,
        )
        if clash is not None:
            problems.append(
                f"A field on stage {stage.number} ({stage.title}) is already called "
                f"{clash.label!r}. Two questions with one name become one line on the readiness "
                f"screen and in the report's Outstanding column, while the count beside it still "
                f"says two — so the report disagrees with itself. Give this one a different label."
            )
    label_id = (section.stage_key, (f.label or "").strip().casefold())
    if (f.label or "").strip():
        if label_id in seen_labels:
            problems.append(
                f"Field {where} and {seen_labels[label_id]} are both called {f.label!r} on the same "
                f"stage. Two questions with one name become one line on the readiness screen while "
                f"the count beside it says two."
            )
        else:
            seen_labels[label_id] = where

    return problems



def to_field_spec(f: CustomFieldSpec) -> FieldSpec:
    return FieldSpec(
        key=f.key,
        label=f.label,
        type=f.type,
        tier=f.tier,
        required=f.required,
        help=f.help,
        unit=f.unit,
        max_length=f.max_length,
        min_value=f.min_value,
        max_value=f.max_value,
    )


def _coerce_custom(f: CustomFieldSpec, raw: Any) -> tuple[Any, str | None]:
    spec = to_field_spec(f)
    if f.type is FieldType.ENUM:
        if raw is None or (isinstance(raw, str) and not raw.strip()):
            return None, None
        token = str(raw).strip()
        if token not in f.option_values:
            return None, f"{f.label}: {token!r} is not one of the options offered."
        return token, None
    if f.type is FieldType.MULTI_ENUM:
        if raw is None:
            return None, None
        if not isinstance(raw, (list, tuple)):
            return None, f"{f.label} must be a list"
        items = [str(v).strip() for v in raw if str(v).strip()]
        unknown = [v for v in items if v not in f.option_values]
        if unknown:
            return None, f"{f.label}: unknown {by_count(len(unknown), 'option', 'options')} {', '.join(unknown)}"
        return items, None
    return coerce_value(spec, raw)


@dataclass(frozen=True, slots=True)
class CustomEntryResult:
    clean: dict[str, Any]
    errors: dict[str, str]
    dropped: tuple[str, ...]


def validate_custom_entry(
    fields: Sequence[CustomFieldSpec],
    data: Mapping[str, Any],
    *,
    enforce_required: bool = True,
) -> CustomEntryResult:
    by_key = {f.key: f for f in fields}
    clean: dict[str, Any] = {}
    errors: dict[str, str] = {}

    for f in fields:
        raw = data.get(f.key)
        value, error = _coerce_custom(f, raw)
        if error:
            errors[f.key] = error
            continue
        if not _is_filled(value):
            if enforce_required and f.required and not f.retired:
                errors[f.key] = f"{f.label} is required"
            continue
        clean[f.key] = value

    dropped = tuple(
        sorted(
            key
            for key in data
            if key not in by_key and not str(key).startswith("_")
        )
    )
    return CustomEntryResult(clean=clean, errors=errors, dropped=dropped)


@dataclass(frozen=True, slots=True)
class CustomWrite:
    data: dict[str, Any] | None
    errors: dict[str, str]
    dropped: tuple[str, ...]


def plan_custom_write(
    fields: Sequence[CustomFieldSpec],
    *,
    sent: Mapping[str, Any] | None,
    previous: Mapping[str, Any],
    merge: bool = False,
    submit: bool = False,
) -> CustomWrite:
    dropped: tuple[str, ...] = ()
    errors: dict[str, str] = {}
    to_store: dict[str, Any] | None = None

    if sent is not None:
        first = validate_custom_entry(fields, sent, enforce_required=False)
        dropped = first.dropped
        errors = dict(first.errors)
        to_store = dict(first.clean)
        for bad_key in first.errors:
            if bad_key in previous:
                to_store[bad_key] = previous[bad_key]
        for f in fields:
            if f.retired and f.key in previous and f.key not in to_store:
                to_store[f.key] = previous[f.key]
        if merge and previous:
            to_store = {**previous, **to_store}

    if submit:
        row = to_store if to_store is not None else previous
        for f in fields:
            if not f.required or f.retired or f.key in errors:
                continue
            if not _is_filled(row.get(f.key)):
                errors[f.key] = f"{f.label} is required"

    return CustomWrite(data=to_store, errors=errors, dropped=dropped)


def answered_keys(fields: Sequence[CustomFieldSpec], values: Mapping[str, Any]) -> set[str]:
    return {f.key for f in fields if _is_filled(values.get(f.key))}



class CustomSectionEditError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class FieldPlan:
    action: str
    field_id: str = ""
    key: str = ""
    spec: CustomFieldSpec | None = None
    supersedes_id: str = ""


@dataclass(frozen=True, slots=True)
class SectionPlan:
    action: str
    section_id: str = ""
    spec: CustomSectionSpec | None = None
    fields: tuple[FieldPlan, ...] = ()
    bumps_revision: bool = False


@dataclass(frozen=True, slots=True)
class DefinitionPlan:
    sections: tuple[SectionPlan, ...] = ()

    @property
    def superseded(self) -> int:
        return sum(1 for s in self.sections for f in s.fields if f.action == "SUPERSEDE")

    @property
    def retired(self) -> int:
        return sum(1 for s in self.sections for f in s.fields if f.action == "RETIRE") + sum(
            1 for s in self.sections if s.action == "RETIRE"
        )

    @property
    def created(self) -> int:
        return sum(1 for s in self.sections for f in s.fields if f.action == "CREATE")

    @property
    def deleted(self) -> int:
        return sum(1 for s in self.sections for f in s.fields if f.action == "DELETE") + sum(
            1 for s in self.sections if s.action == "DELETE"
        )


def _live_successor(
    field: CustomFieldSpec, by_id: Mapping[str, CustomFieldSpec]
) -> CustomFieldSpec | None:
    seen: set[str] = set()
    current = field
    for _ in range(MAX_SUPERSEDE_HOPS):
        if not current.retired:
            return current
        if not current.superseded_by or current.superseded_by in seen:
            return None
        seen.add(current.superseded_by)
        nxt = by_id.get(current.superseded_by)
        if nxt is None:
            return None
        current = nxt
    return None


def _mint_key(base: str, taken: Iterable[str]) -> str:
    used = set(taken)
    stem = (base or "field")[: MAX_CUSTOM_KEY_CHARS - 4]
    for n in range(2, 1000):
        candidate = f"{stem}R{n}"
        if candidate not in used:
            return candidate
    raise CustomSectionEditError(
        f"{base!r} has been reworded too many times to store another version of it. Retire the "
        f"field and add a new one with its own key."
    )


def plan_definition(
    stored: Sequence[CustomSectionSpec],
    incoming: Sequence[CustomSectionSpec],
    answered: Mapping[str, set[str]],
) -> DefinitionPlan:
    stored_by_key = {s.key: s for s in stored}
    plans: list[SectionPlan] = []
    taken_keys = {f.key for s in stored for f in s.fields} | {
        f.key for s in incoming for f in s.fields
    }
    held = _keys_this_put_keeps(stored, incoming, answered)

    for section in incoming:
        previous = stored_by_key.get(section.key)
        answered_target = set(answered.get(section.stage_key, set()))
        held_target = held.get(section.stage_key, set())
        if previous is None:
            for f in section.fields:
                _refuse_answered_key(f, section, answered_target, held_target)
            plans.append(
                SectionPlan(
                    action="CREATE",
                    spec=section,
                    fields=tuple(
                        FieldPlan(action="CREATE", key=f.key, spec=f) for f in section.fields
                    ),
                )
            )
            continue

        answered_here = set(answered.get(previous.stage_key, set()))
        if section.stage_key != previous.stage_key and (
            answered_here & {f.key for f in previous.fields}
        ):
            raise CustomSectionEditError(
                f"Section {previous.title!r} cannot be moved to another stage, because answers have "
                f"already been recorded against it and they are stored with the stage they were "
                f"asked at. Leave it where it is, or remove it here and add a new section on the "
                f"other stage — the answers already given stay readable either way."
            )

        field_plans, bumped = _plan_fields(
            previous, section, answered_here, taken_keys, answered_target, held_target
        )
        plans.append(
            SectionPlan(
                action="EDIT",
                section_id=previous.id,
                spec=section,
                fields=field_plans,
                bumps_revision=bumped,
            )
        )

    incoming_keys = {s.key for s in incoming}
    for previous in stored:
        if previous.key in incoming_keys:
            continue
        if previous.retired:
            continue
        answered_here = set(answered.get(previous.stage_key, set()))
        touched = answered_here & {f.key for f in previous.fields}
        if touched:
            plans.append(
                SectionPlan(
                    action="RETIRE",
                    section_id=previous.id,
                    spec=previous,
                    bumps_revision=True,
                    fields=tuple(
                        FieldPlan(action="RETIRE", field_id=f.id, key=f.key, spec=f)
                        for f in previous.fields
                        if not f.retired
                    ),
                )
            )
        else:
            plans.append(SectionPlan(action="DELETE", section_id=previous.id, spec=previous))

    return DefinitionPlan(sections=tuple(plans))


def _keys_this_put_keeps(
    stored: Sequence[CustomSectionSpec],
    incoming: Sequence[CustomSectionSpec],
    answered: Mapping[str, set[str]],
) -> dict[str, set[str]]:
    incoming_by_key = {s.key: s for s in incoming}
    kept: dict[str, set[str]] = {}
    for section in stored:
        answered_here = set(answered.get(section.stage_key, set()))
        named = incoming_by_key.get(section.key)
        if named is None:
            if section.retired or (answered_here & {f.key for f in section.fields}):
                kept.setdefault(section.stage_key, set()).update(f.key for f in section.fields)
            continue
        declared = {f.key for f in named.fields}
        by_key = {f.key: f for f in section.fields}
        by_id = {f.id: f for f in section.fields if f.id}
        matched: set[str] = set()
        for incoming_field in named.fields:
            target = by_key.get(incoming_field.key)
            if target is not None and target.retired:
                target = _live_successor(target, by_id)
            if target is not None and target.id:
                matched.add(target.id)
        for f in section.fields:
            if (
                f.key in declared
                or f.retired
                or f.key in answered_here
                or (f.id and f.id in matched)
            ):
                kept.setdefault(named.stage_key, set()).add(f.key)
    return kept


def _refuse_answered_key(
    f: CustomFieldSpec,
    section: CustomSectionSpec,
    answered_target: set[str],
    held_target: set[str],
) -> None:
    if f.key in answered_target:
        raise CustomSectionEditError(
            f"{f.key!r} cannot be used for {section.title!r}: an answer is already recorded under "
            f"that key on this stage, given to a different question. Attaching it to this one would "
            f"make the report state that the old answer answers the new wording. Choose another key "
            f"— the answer already given stays readable under the question it was asked as."
        )
    if f.key in held_target:
        raise CustomSectionEditError(
            f"{f.key!r} cannot be used for {section.title!r}: that key still belongs to a question "
            f"on this stage that is no longer asked. A stage's answers are one set of keys, so the "
            f"two questions would share one answer and the report would print it under both "
            f"wordings. Choose another key — the question that holds it keeps it for as long as the "
            f"section it was asked in is kept."
        )


def _plan_fields(
    previous: CustomSectionSpec,
    section: CustomSectionSpec,
    answered_here: set[str],
    taken_keys: set[str],
    answered_target: set[str],
    held_target: set[str],
) -> tuple[tuple[FieldPlan, ...], bool]:
    by_key = {f.key: f for f in previous.fields}
    by_id = {f.id: f for f in previous.fields if f.id}
    plans: list[FieldPlan] = []
    bumped = False
    matched_ids: set[str] = set()

    for f in section.fields:
        target = by_key.get(f.key)
        if target is not None and target.retired:
            target = _live_successor(target, by_id)
            if target is None:
                raise CustomSectionEditError(
                    f"{f.key!r} belonged to a question that was removed after it had been "
                    f"answered. The answers are still held under that key, so it cannot be used "
                    f"again — choose another key for this question."
                )
        if target is None:
            _refuse_answered_key(f, section, answered_target, held_target)
            plans.append(FieldPlan(action="CREATE", key=f.key, spec=f))
            continue
        matched_ids.add(target.id)

        wording_changed = target.label.strip() != (f.label or "").strip()
        if wording_changed and target.key in answered_here:
            minted = _mint_key(target.key, taken_keys)
            taken_keys.add(minted)
            plans.append(
                FieldPlan(
                    action="SUPERSEDE",
                    field_id=target.id,
                    key=target.key,
                    spec=replace(f, key=minted, id=""),
                    supersedes_id=target.id,
                )
            )
            bumped = True
            continue
        plans.append(FieldPlan(action="EDIT", field_id=target.id, key=target.key, spec=f))

    for stored_field in previous.fields:
        if stored_field.id in matched_ids or stored_field.retired:
            continue
        if stored_field.key in answered_here:
            plans.append(
                FieldPlan(
                    action="RETIRE",
                    field_id=stored_field.id,
                    key=stored_field.key,
                    spec=stored_field,
                )
            )
            bumped = True
        else:
            plans.append(
                FieldPlan(
                    action="DELETE",
                    field_id=stored_field.id,
                    key=stored_field.key,
                    spec=stored_field,
                )
            )
    return tuple(plans), bumped



def field_payload(f: CustomFieldSpec) -> dict[str, Any]:
    return {
        "id": f.id,
        "key": f.key,
        "label": f.label,
        "type": f.type.value,
        "tier": f.tier.value,
        "required": f.required,
        "help": f.help,
        "unit": f.unit,
        "options": [{"value": o.value, "label": o.display} for o in f.options],
        "maxLength": f.max_length,
        "minValue": f.min_value,
        "maxValue": f.max_value,
        "sortOrder": f.sort_order,
        "retired": f.retired,
        "supersededById": f.superseded_by or None,
    }


def section_payload(s: CustomSectionSpec) -> dict[str, Any]:
    return {
        "id": s.id,
        "key": s.key,
        "stageKey": s.stage_key,
        "title": s.title,
        "description": s.description,
        "sortOrder": s.sort_order,
        "revision": s.revision,
        "retired": s.retired,
        "fields": [field_payload(f) for f in s.fields],
    }


def definition_payload(definition: CustomDefinition) -> dict[str, Any]:
    return {
        "customSchemaVersion": definition.version,
        "sections": [section_payload(s) for s in definition.sections],
        "fetchedAt": datetime.now(UTC).isoformat(),
    }



def _option_list(raw: Any) -> tuple[CustomOption, ...]:
    if not isinstance(raw, (list, tuple)):
        return ()
    out: list[CustomOption] = []
    for item in raw:
        if isinstance(item, Mapping):
            value = str(item.get("value", "") or "").strip()
            label = str(item.get("label", "") or "").strip()
        else:
            value, label = str(item or "").strip(), ""
        if value:
            out.append(CustomOption(value=value, label=label))
    return tuple(out)


def _field_from_row(row: Any) -> CustomFieldSpec:
    try:
        field_type = FieldType(str(getattr(row, "type", "") or ""))
    except ValueError:
        field_type = FieldType.TEXT
    try:
        tier = Tier(str(getattr(row, "tier", "") or ""))
    except ValueError:
        tier = Tier.STANDARD
    return CustomFieldSpec(
        key=str(getattr(row, "key", "") or ""),
        label=str(getattr(row, "label", "") or ""),
        type=field_type,
        tier=tier,
        required=bool(getattr(row, "isRequired", False)),
        help=str(getattr(row, "help", "") or ""),
        unit=str(getattr(row, "unit", "") or ""),
        options=_option_list(getattr(row, "options", None)),
        max_length=int(getattr(row, "maxLength", 0) or 0),
        min_value=getattr(row, "minValue", None),
        max_value=getattr(row, "maxValue", None),
        sort_order=int(getattr(row, "sortOrder", 0) or 0),
        retired=not bool(getattr(row, "isActive", True)),
        superseded_by=str(getattr(row, "supersededById", "") or ""),
        id=str(getattr(row, "id", "") or ""),
    )


def _section_from_row(row: Any, fields: Sequence[CustomFieldSpec]) -> CustomSectionSpec:
    return CustomSectionSpec(
        key=str(getattr(row, "key", "") or ""),
        title=str(getattr(row, "title", "") or ""),
        stage_key=str(getattr(row, "stageKey", "") or ""),
        description=str(getattr(row, "description", "") or ""),
        sort_order=int(getattr(row, "sortOrder", 0) or 0),
        fields=tuple(sorted(fields, key=lambda f: (f.sort_order, f.key))),
        retired=not bool(getattr(row, "isActive", True)),
        revision=int(getattr(row, "revision", 1) or 1),
        id=str(getattr(row, "id", "") or ""),
    )


async def load_definition(workshop_id: str) -> CustomDefinition:
    sections = await db.dwcustomsection.find_many(
        where={"designWorkshopId": workshop_id}, order={"sortOrder": "asc"}
    )
    if not sections:
        return EMPTY_DEFINITION
    field_rows = await db.dwcustomfield.find_many(
        where={"sectionId": {"in": [s.id for s in sections]}},
        order=[{"sortOrder": "asc"}, {"id": "asc"}],
    )
    grouped: dict[str, list[CustomFieldSpec]] = {}
    for row in field_rows:
        grouped.setdefault(str(row.sectionId), []).append(_field_from_row(row))
    specs = tuple(_section_from_row(row, grouped.get(str(row.id), [])) for row in sections)
    return CustomDefinition(sections=specs, version=custom_schema_version(specs))


async def load_definition_or_empty(workshop_id: str) -> CustomDefinition:
    try:
        return await load_definition(workshop_id)
    except Exception:
        import logging

        logging.getLogger(__name__).exception(
            "custom sections could not be read for workshop %s", workshop_id
        )
        return EMPTY_DEFINITION


def _field_columns(f: CustomFieldSpec) -> dict[str, Any]:
    from prisma import Json

    return {
        "key": f.key,
        "label": f.label,
        "help": f.help,
        "type": f.type.value,
        "tier": f.tier.value,
        "isRequired": f.required,
        "unit": f.unit,
        "maxLength": f.max_length or None,
        "minValue": f.min_value,
        "maxValue": f.max_value,
        "sortOrder": f.sort_order,
        "options": Json(
            [{"value": o.value, "label": o.display} for o in f.options] if f.options else None
        ),
    }


async def apply_definition_plan(
    plan: DefinitionPlan, workshop_id: str, *, actor_id: str | None
) -> None:
    from app.services.design_workshop_approvals import open_header

    now = datetime.now(UTC)
    async with db.tx() as tx:
        await open_header(tx, workshop_id)
        for section_plan in plan.sections:
            section = section_plan.spec
            section_id = section_plan.section_id

            if section_plan.action == "CREATE" and section is not None:
                created = await tx.dwcustomsection.create(
                    data={
                        "designWorkshopId": workshop_id,
                        "key": section.key,
                        "stageKey": section.stage_key,
                        "title": section.title,
                        "description": section.description,
                        "sortOrder": section.sort_order,
                        "createdById": actor_id,
                    }
                )
                section_id = created.id
            elif section_plan.action == "EDIT" and section is not None:
                await tx.dwcustomsection.update(
                    where={"id": section_id},
                    data={
                        "title": section.title,
                        "description": section.description,
                        "sortOrder": section.sort_order,
                        "stageKey": section.stage_key,
                        "isActive": True,
                        "retiredAt": None,
                        **({"revision": {"increment": 1}} if section_plan.bumps_revision else {}),
                    },
                )
            elif section_plan.action == "RETIRE":
                await tx.dwcustomsection.update(
                    where={"id": section_id},
                    data={"isActive": False, "retiredAt": now, "revision": {"increment": 1}},
                )
            elif section_plan.action == "DELETE":
                await tx.dwcustomsection.delete(where={"id": section_id})
                continue

            new_fields: list[dict[str, Any]] = []
            for field_plan in section_plan.fields:
                spec = field_plan.spec
                if field_plan.action == "CREATE" and spec is not None:
                    new_fields.append({"sectionId": section_id, **_field_columns(spec)})
                elif field_plan.action == "EDIT" and spec is not None:
                    await tx.dwcustomfield.update(
                        where={"id": field_plan.field_id},
                        data={
                            **_field_columns(spec),
                            "key": field_plan.key,
                        },
                    )
                elif field_plan.action == "SUPERSEDE" and spec is not None:
                    replacement = await tx.dwcustomfield.create(
                        data={
                            "sectionId": section_id,
                            **_field_columns(spec),
                        }
                    )
                    await tx.dwcustomfield.update(
                        where={"id": field_plan.supersedes_id},
                        data={
                            "isActive": False,
                            "retiredAt": now,
                            "supersededById": replacement.id,
                        },
                    )
                elif field_plan.action == "RETIRE":
                    await tx.dwcustomfield.update(
                        where={"id": field_plan.field_id},
                        data={"isActive": False, "retiredAt": now},
                    )
                elif field_plan.action == "DELETE":
                    await tx.dwcustomfield.delete(where={"id": field_plan.field_id})
            if new_fields:
                await tx.dwcustomfield.create_many(data=new_fields)


__all__ = [
    "CUSTOM_ENTITY_KEY",
    "EMPTY_DEFINITION",
    "KEY_PATTERN",
    "MAX_CUSTOM_DESCRIPTION_CHARS",
    "MAX_CUSTOM_FIELDS_PER_SECTION",
    "MAX_CUSTOM_HELP_CHARS",
    "MAX_CUSTOM_KEY_CHARS",
    "MAX_CUSTOM_LABEL_CHARS",
    "MAX_CUSTOM_OPTIONS",
    "MAX_CUSTOM_SECTIONS",
    "MAX_CUSTOM_TITLE_CHARS",
    "MAX_CUSTOM_UNIT_CHARS",
    "V1_FIELD_TYPES",
    "CustomDefinition",
    "CustomEntryResult",
    "CustomFieldSpec",
    "CustomOption",
    "CustomSectionEditError",
    "CustomSectionSpec",
    "CustomWrite",
    "DefinitionPlan",
    "FieldPlan",
    "SectionPlan",
    "answered_keys",
    "apply_definition_plan",
    "custom_schema_version",
    "definition_payload",
    "field_payload",
    "load_definition",
    "load_definition_or_empty",
    "plan_custom_write",
    "plan_definition",
    "section_payload",
    "to_field_spec",
    "validate_custom_entry",
    "validate_definition",
]
