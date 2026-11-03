from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace

import pytest
from pydantic import ValidationError

import app.services.stage_definitions  # noqa: F401
from app.schemas.designers import ADDRESS_LINE_PLAIN_MAX, DesignerProfileUpdate
from app.services import designers
from app.services.designers import PREFILL_MAP, PROFILE_FIELDS
from app.services.stage_schema import (
    STAGES,
    Cardinality,
    FieldType,
    coerce_value,
    validate_entry,
)

PREFILL_EXEMPT: dict[str, str] = {
    "phoneCountryCode": (
        "not a box of its own: the prefill folds it into designerPhone as \"+91 9876543210\" "
        "(services/standard_values.compose_phone), the one-string shape the stage box carries."
    ),
    "experienceMonths": (
        "stage 3 has no designerExperienceMonths box yet; adding one moves registry_version(), "
        "owes a re-dump of the bundled Android asset and a re-cut APK, and decides how the report "
        "prints the pair. See the note above this dict for the three steps that retire this entry."
    ),
}


def _registry_field_keys() -> set[str]:
    return {
        field.key
        for stage in STAGES
        for entity in stage.entities
        for field in entity.fields
        if not field.deprecated
    }


def _designer_field(key: str):
    plan = next(s for s in STAGES if s.number == 3).entity("workshopPlan")
    assert plan is not None, "stage 3 has no workshopPlan entity"
    field = plan.field(key)
    assert field is not None, f"workshopPlan has no {key!r} field"
    return field


def test_every_prefilled_profile_column_has_a_receiving_field():
    known = _registry_field_keys()
    missing = sorted({field_key for _, field_key in PREFILL_MAP if field_key not in known})
    assert missing == [], (
        "PREFILL_MAP names registry fields that do not exist (or are deprecated): "
        + ", ".join(missing)
        + ". prefill_from_profile would write these into DwStageEntry.data under a key no form "
        "renders and no report prints — saved, and invisible."
    )


def test_every_prefilled_profile_column_is_a_real_profile_column():
    unknown = sorted({column for column, _ in PREFILL_MAP if column not in PROFILE_FIELDS})
    assert unknown == [], (
        "PREFILL_MAP names profile columns that are not in PROFILE_FIELDS: "
        + ", ".join(unknown)
        + ". getattr(..., None) would silently read these as empty on every workshop."
    )


def test_every_writable_profile_column_is_either_prefilled_or_named_here():
    carried = {column for column, _ in PREFILL_MAP}
    forgotten = sorted(set(PROFILE_FIELDS) - carried - set(PREFILL_EXEMPT))
    assert forgotten == [], (
        "these profile columns reach no report and are not exempt: "
        + ", ".join(forgotten)
        + ". Add a PREFILL_MAP row with a receiving FieldSpec, or add the column to PREFILL_EXEMPT "
        "with the reason it must not cross."
    )


def test_the_exemption_list_cannot_name_a_column_that_is_also_carried():
    carried = {column for column, _ in PREFILL_MAP}
    both = sorted(carried & set(PREFILL_EXEMPT))
    assert both == [], "exempt AND prefilled, so one of the two is stale: " + ", ".join(both)


def test_the_wire_body_accepts_every_column_the_prefill_reads():
    body_fields = set(DesignerProfileUpdate.model_fields)
    unsendable = sorted(set(PROFILE_FIELDS) - body_fields)
    assert unsendable == [], (
        "PROFILE_FIELDS names columns DesignerProfileUpdate cannot carry: "
        + ", ".join(unsendable)
        + ". They would be readable, unwritable, and prefilled as blank into every report."
    )


def test_the_designer_boxes_stage_3_gained_are_all_there():
    stage = next(s for s in STAGES if s.number == 3)
    plan = stage.entity("workshopPlan")
    assert plan is not None, "stage 3 has no workshopPlan entity"

    expected = {
        "designerProfile",
        "designerExperience",
        "designerLocalName",
        "designerDesignation",
        "designerDepartment",
        "designerQualification",
        "designerSpecialisation",
        "designerPhone",
        "designerEmail",
        "designerWebsite",
        "designerAddress",
        "designerCity",
        "designerState",
        "designerPincode",
        "designerEmpanelmentNo",
        "designerEmpanelmentDate",
        "designerPhoto",
        "designerSignature",
        "designerCv",
    }
    present = {f.key for f in plan.fields if not f.deprecated}
    assert expected <= present, "stage 3 is missing designer boxes: " + ", ".join(
        sorted(expected - present)
    )

    required = sorted(f.key for f in plan.fields if f.key in expected and f.required and f.key != "designerProfile")
    assert required == [], "these designer boxes are required and must not be: " + ", ".join(required)



class _StubProfileTable:
    def __init__(self, profile: object | None) -> None:
        self._profile = profile
        self.calls: list[tuple[dict, dict | None]] = []

    async def find_unique(self, where: dict, include: dict | None = None) -> object | None:
        self.calls.append((where, include))
        return self._profile


class _StubDb:
    def __init__(self, profile: object | None) -> None:
        self.designerprofile = _StubProfileTable(profile)


_PROFILE_VALUES: dict[str, object] = {
    "displayName": "Latha Nayak",
    "localName": "ଲତା ନାୟକ",
    "designation": "Assistant Professor",
    "institution": "NIFT Bhubaneswar",
    "department": "Textile Design",
    "qualification": "M.Des (Textile Design), NID Ahmedabad",
    "specialisation": "Handloom weave structures, natural dyeing",
    "experienceYears": 14,
    "experienceMonths": 6,
    "biography": "Fourteen years on Odisha's ikat clusters.",
    "phone": "+91 9876500001",
    "phoneCountryCode": "+91",
    "email": "latha.nayak@nift.ac.in",
    "website": "https://nift.ac.in/bhubaneswar",
    "addressLine": "Plot 3, IDCO Institutional Area, Chandaka",
    "city": "Bhubaneswar",
    "state": "Odisha",
    "pincode": "751024",
    "photoMediaId": "cm000000000000000000photo",
    "signatureMediaId": "cm00000000000000000000sig",
    "cvMediaId": "cm0000000000000000000000cv",
    "empanelmentNo": "DCH/EMP/2024/0117",
    "empanelmentDate": datetime(2026, 3, 14, 18, 45, 12, tzinfo=UTC),
}


def _stub_profile() -> SimpleNamespace:
    missing = sorted(set(PROFILE_FIELDS) - set(_PROFILE_VALUES))
    assert missing == [], (
        f"_PROFILE_VALUES has no answer for {missing}; prefill_from_profile would skip those "
        "columns as unfilled and the round-trip below would silently stop covering them"
    )
    return SimpleNamespace(
        **_PROFILE_VALUES,
        user=SimpleNamespace(name="Latha Nayak", email="latha.nayak@nift.ac.in"),
    )


async def test_a_datetime_column_reaches_a_date_field_as_a_plain_yyyy_mm_dd(monkeypatch):
    monkeypatch.setattr(designers, "db", _StubDb(_stub_profile()))

    values = await designers.prefill_from_profile("user-latha")

    stored_date = values["designerEmpanelmentDate"]
    assert isinstance(stored_date, str), (
        f"designerEmpanelmentDate is a {type(stored_date).__name__}, so what reaches the JSON "
        "column depends on datetime.__str__ rather than on this function"
    )
    assert stored_date == "2026-03-14", stored_date

    field = _designer_field("designerEmpanelmentDate")
    assert field.type is FieldType.DATE
    assert coerce_value(field, stored_date) == ("2026-03-14", None), (
        "the narrowed value is not what the registry field stores, so the prefill and every client "
        "send this box two different shapes"
    )

    assert designers.db.designerprofile.calls == [
        ({"userId": "user-latha"}, {"user": True, "location": True})
    ]


async def test_every_prefilled_value_survives_the_coercion_that_seeds_it(monkeypatch):
    monkeypatch.setattr(designers, "db", _StubDb(_stub_profile()))
    values = await designers.prefill_from_profile("user-latha")

    assert set(values) == {field_key for _column, field_key in PREFILL_MAP}, (
        "the prefill did not answer every PREFILL_MAP pair; a column read as empty here would be "
        "skipped by the loop below rather than checked"
    )

    seeded: dict[str, object] = {}
    for stage in STAGES:
        for entity in stage.entities:
            if entity.cardinality is not Cardinality.SINGLETON:
                continue
            known = {f.key for f in entity.fields if not f.deprecated}
            subset = {k: v for k, v in values.items() if k in known}
            if not subset:
                continue
            clean, errors = validate_entry(entity, subset, enforce_required=False)
            assert errors == {}, (
                f"{stage.key}/{entity.key} refused prefilled values: {errors}. _seed_prefill drops "
                "a refused key silently, so the box would be blank on a stage nobody has opened "
                "while the Designer Page still shows the value it promised to copy."
            )
            dropped = sorted(set(subset) - set(clean))
            assert dropped == [], (
                f"{stage.key}/{entity.key} dropped {dropped} with no error recorded — the quietest "
                "form of the same loss"
            )
            seeded.update(clean)

    unseeded = sorted(set(values) - set(seeded))
    assert unseeded == [], (
        f"{unseeded} were prefilled but land on no singleton entity, so _seed_prefill never writes "
        "them: the loop it runs skips COLLECTION entities"
    )


KNOWN_PREFILL_GAPS: dict[str, str] = {
    "phone": "no bound below designerPhone's 20 and no PHONE_IN check",
    "email": "unbounded, while designerEmail is bounded at 180",
    "pincode": "no PINCODE check, while designerPincode declares one",
}


VALIDATED_BODY_CEILINGS: dict[str, int] = {"addressLine": ADDRESS_LINE_PLAIN_MAX}


def _body_max_length(column: str) -> int:
    if column in VALIDATED_BODY_CEILINGS:
        return VALIDATED_BODY_CEILINGS[column]
    info = DesignerProfileUpdate.model_fields[column]
    for constraint in info.metadata:
        bound = getattr(constraint, "max_length", None)
        if bound is not None:
            return int(bound)
    return 0


def test_a_validated_ceiling_is_the_one_the_API_actually_applies():
    for column, ceiling in VALIDATED_BODY_CEILINGS.items():
        assert DesignerProfileUpdate(**{column: "a" * ceiling})
        with pytest.raises(ValidationError):
            DesignerProfileUpdate(**{column: "a" * (ceiling + 1)})

        spans = ",".join('{"text":"word ","marks":["BOLD","ITALIC"]}' for _ in range(12))
        fits = f'{{"blocks":[{{"kind":"PARAGRAPH","spans":[{spans}]}}]}}'
        assert len(fits) > ceiling, "this fixture no longer exercises the envelope-versus-words split"
        assert DesignerProfileUpdate(**{column: fits})

        long_words = "c" * (ceiling + 1)
        over = f'{{"blocks":[{{"kind":"PARAGRAPH","spans":[{{"text":"{long_words}"}}]}}]}}'
        with pytest.raises(ValidationError):
            DesignerProfileUpdate(**{column: over})


def test_no_profile_column_is_bounded_wider_than_the_stage_box_it_seeds():
    stage_fields = {}
    for stage in STAGES:
        for entity in stage.entities:
            for field in entity.fields:
                stage_fields.setdefault(field.key, field)

    wider = {}
    for column, field_key in PREFILL_MAP:
        field = stage_fields[field_key]
        if not field.max_length:
            continue
        body_bound = _body_max_length(column)
        if body_bound == 0 or body_bound > field.max_length:
            wider[column] = (body_bound, field_key, field.max_length)

    unexpected = sorted(set(wider) - set(KNOWN_PREFILL_GAPS))
    assert unexpected == [], (
        "these profile columns accept more than the stage box they are copied into: "
        + ", ".join(
            f"{c} (body {wider[c][0] or 'unbounded'} -> {wider[c][1]} {wider[c][2]})"
            for c in unexpected
        )
        + ". _seed_prefill discards validate_entry's errors, so the over-long value is dropped and "
        "the report line is blank while the Designer Page still shows it. Narrow the body in "
        "app/schemas/designers.py — the way experienceYears already is — or add the column to "
        "KNOWN_PREFILL_GAPS with what it costs."
    )


def test_the_recorded_prefill_gaps_are_all_still_real():
    from pydantic import ValidationError

    demonstrations = {
        "phone": ("designerPhone", "987650000"),
        "email": ("designerEmail", "l" * 190 + "@nift.ac.in"),
        "pincode": ("designerPincode", "12345"),
    }
    assert set(demonstrations) == set(KNOWN_PREFILL_GAPS), (
        "every recorded gap needs a demonstration value, or the entry is an unverified claim"
    )

    for column, (field_key, value) in demonstrations.items():
        try:
            DesignerProfileUpdate(**{column: value})
        except ValidationError as exc:   # pragma: no cover - the day this fires is the fix landing
            raise AssertionError(
                f"DesignerProfileUpdate now refuses {column}={value!r} ({exc.error_count()} "
                f"error(s)), so the gap recorded in KNOWN_PREFILL_GAPS[{column!r}] is closed. "
                "Delete that entry."
            ) from exc

        stored, error = coerce_value(_designer_field(field_key), value)
        assert error is not None and stored is None, (
            f"{field_key} now accepts {value!r}, so the profile and the stage agree again and "
            f"KNOWN_PREFILL_GAPS[{column!r}] is stale — but check WHICH side moved before deleting "
            "it: a bound widened or a format deleted on the stage side closes the gap by giving up "
            "the check, not by gaining one."
        )


def test_each_prefilled_key_is_answered_by_exactly_one_singleton_entity():
    prefilled = {field_key for _column, field_key in PREFILL_MAP}

    singleton_receivers: dict[str, list[str]] = {key: [] for key in prefilled}
    collection_receivers: dict[str, list[str]] = {key: [] for key in prefilled}
    for stage in STAGES:
        for entity in stage.entities:
            bucket = (singleton_receivers
                      if entity.cardinality is Cardinality.SINGLETON
                      else collection_receivers)
            for field in entity.fields:
                if field.key in prefilled and not field.deprecated:
                    bucket[field.key].append(f"stage {stage.number}/{entity.key}")

    ambiguous = {k: v for k, v in singleton_receivers.items() if len(v) != 1}
    assert ambiguous == {}, (
        "these prefilled keys do not resolve to exactly one singleton entity: "
        + "; ".join(f"{k} -> {v or 'nothing'}" for k, v in sorted(ambiguous.items()))
        + ". _seed_prefill writes the key into EVERY singleton that declares it, and PREFILL_MAP "
        "has no way to say which one was meant — so the designer's value lands in a box nobody "
        "chose, on a stage they have not opened, looking deliberate."
    )

    assert collection_receivers["designerName"] == ["stage 16/finalProduct"], (
        "stage 16's 'Designed by' box has moved or changed cardinality: "
        f"{collection_receivers['designerName']}. Re-read this test's docstring — the safety of "
        "designerName's duplicate key rests entirely on finalProduct being a COLLECTION."
    )
