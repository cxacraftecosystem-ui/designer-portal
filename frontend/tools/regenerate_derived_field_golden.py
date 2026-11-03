from __future__ import annotations

import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "backend"))

from app.services.stage_schema import FieldSpec, FieldType, derive_value  # noqa: E402

GOLDEN = REPO_ROOT / "frontend" / "e2e" / "fixtures" / "derived-field-cases.json"

CASES: list[tuple[str, str, str, tuple[str, ...], dict]] = [
    ("d01-three-day-workshop", "INT", "DAYS_BETWEEN", ("startDate", "endDate"),
     {"startDate": "2026-08-12", "endDate": "2026-08-14"}),
    ("d02-single-day-is-one", "INT", "DAYS_BETWEEN", ("startDate", "endDate"),
     {"startDate": "2026-08-12", "endDate": "2026-08-12"}),
    ("d03-end-before-start", "INT", "DAYS_BETWEEN", ("startDate", "endDate"),
     {"startDate": "2026-08-14", "endDate": "2026-08-12"}),
    ("d04-no-end-date", "INT", "DAYS_BETWEEN", ("startDate", "endDate"),
     {"startDate": "2026-08-12"}),
    ("d05-blank-end-date", "INT", "DAYS_BETWEEN", ("startDate", "endDate"),
     {"startDate": "2026-08-12", "endDate": ""}),
    ("d06-datetime-strings", "INT", "DAYS_BETWEEN", ("startDate", "endDate"),
     {"startDate": "2026-08-12T00:00:00.000Z", "endDate": "2026-08-14T23:59:59Z"}),
    ("d07-across-a-dst-shift", "INT", "DAYS_BETWEEN", ("startDate", "endDate"),
     {"startDate": "2026-03-28", "endDate": "2026-04-02"}),
    ("d08-leap-day-spanned", "INT", "DAYS_BETWEEN", ("startDate", "endDate"),
     {"startDate": "2028-02-27", "endDate": "2028-03-01"}),
    ("d09-impossible-day-of-month", "INT", "DAYS_BETWEEN", ("startDate", "endDate"),
     {"startDate": "2026-02-01", "endDate": "2026-02-30"}),
    ("d10-impossible-month", "INT", "DAYS_BETWEEN", ("startDate", "endDate"),
     {"startDate": "2026-01-01", "endDate": "2026-13-01"}),
    ("d11-not-a-date-at-all", "INT", "DAYS_BETWEEN", ("startDate", "endDate"),
     {"startDate": "2026-08-12", "endDate": "next Tuesday"}),
    ("d12-compact-iso-from-a-number", "INT", "DAYS_BETWEEN", ("startDate", "endDate"),
     {"startDate": "2026-08-12", "endDate": 20260814}),
    ("d13-a-boolean-is-not-a-date", "INT", "DAYS_BETWEEN", ("startDate", "endDate"),
     {"startDate": "2026-08-12", "endDate": True}),

    ("p01-quantity-times-rate", "DECIMAL", "PRODUCT", ("quantity", "rate"),
     {"quantity": "4", "rate": "12.5"}),
    ("p02-money-half-paisa-up", "MONEY", "PRODUCT", ("quantity", "rate"),
     {"quantity": "1.5", "rate": "4.75"}),
    ("p03-money-half-paisa-down", "MONEY", "PRODUCT", ("quantity", "rate"),
     {"quantity": "0.5", "rate": "0.25"}),
    ("p04-grouped-thousands", "MONEY", "PRODUCT", ("quantity", "rate"),
     {"quantity": "2", "rate": "1,250.00"}),
    ("p05-one-factor-blank", "MONEY", "PRODUCT", ("quantity", "rate"),
     {"quantity": "", "rate": "12.50"}),
    ("p06-one-factor-missing", "MONEY", "PRODUCT", ("quantity", "rate"),
     {"rate": "12.50"}),
    ("p07-one-factor-unparseable", "MONEY", "PRODUCT", ("quantity", "rate"),
     {"quantity": "about four", "rate": "12.50"}),
    ("p08-hex-literal", "DECIMAL", "PRODUCT", ("quantity", "rate"),
     {"quantity": "0x1A", "rate": "2"}),
    ("p09-underscore-separated", "DECIMAL", "PRODUCT", ("quantity", "rate"),
     {"quantity": "1_000", "rate": "2"}),
    ("p10-a-one-element-list", "DECIMAL", "PRODUCT", ("quantity", "rate"),
     {"quantity": [5], "rate": "2"}),
    ("p11-a-boolean", "DECIMAL", "PRODUCT", ("quantity", "rate"),
     {"quantity": True, "rate": "2"}),
    ("p12-zero-is-a-quantity", "MONEY", "PRODUCT", ("quantity", "rate"),
     {"quantity": "0", "rate": "12.50"}),
    ("p13-four-decimal-rounding", "DECIMAL", "PRODUCT", ("quantity", "rate"),
     {"quantity": "1.00005", "rate": "1"}),
    ("p14-exponent-notation", "DECIMAL", "PRODUCT", ("quantity", "rate"),
     {"quantity": "1e3", "rate": "2"}),
    ("p15-leading-plus-and-spaces", "DECIMAL", "PRODUCT", ("quantity", "rate"),
     {"quantity": "  +4 ", "rate": "2"}),
    ("p16-three-factors-persons-days-rate", "MONEY", "PRODUCT", ("persons", "days", "rate"),
     {"persons": "3", "days": "2.5", "rate": "450"}),
    ("p17-a-stored-number-not-a-string", "MONEY", "PRODUCT", ("quantity", "rate"),
     {"quantity": 3, "rate": 12.5}),

    ("s01-three-cost-heads", "MONEY", "SUM", ("material", "labour", "packaging"),
     {"material": "1200.50", "labour": "800.25", "packaging": "99.00"}),
    ("s02-optional-heads-blank", "MONEY", "SUM", ("material", "labour", "packaging"),
     {"material": "1200.50", "labour": "", "packaging": None}),
    ("s03-every-head-blank", "MONEY", "SUM", ("material", "labour", "packaging"),
     {"material": "", "labour": "", "packaging": ""}),
    ("s04-no-heads-at-all", "MONEY", "SUM", ("material", "labour", "packaging"), {}),
    ("s05-one-head-unparseable", "MONEY", "SUM", ("material", "labour", "packaging"),
     {"material": "1200.50", "labour": "n/a", "packaging": "99.00"}),
    ("s06-a-zero-head-counts", "MONEY", "SUM", ("material", "labour", "packaging"),
     {"material": "0", "labour": "", "packaging": ""}),
    ("s07-money-half-paisa", "MONEY", "SUM", ("material", "labour", "packaging"),
     {"material": "0.005", "labour": "0.12", "packaging": ""}),
    ("s08-negative-adjustment", "MONEY", "SUM", ("material", "labour", "packaging"),
     {"material": "1200.50", "labour": "-200.50", "packaging": ""}),
    ("s09-decimal-type-four-places", "DECIMAL", "SUM", ("material", "labour", "packaging"),
     {"material": "0.00005", "labour": "1", "packaging": ""}),
    ("s10-mixed-magnitudes", "MONEY", "SUM", ("material", "labour", "packaging"),
     {"material": "1e16", "labour": "1", "packaging": "1"}),
    ("s11-grouped-thousands", "MONEY", "SUM", ("material", "labour", "packaging"),
     {"material": "1,200.50", "labour": "800", "packaging": ""}),
    ("s12-a-boolean-head", "MONEY", "SUM", ("material", "labour", "packaging"),
     {"material": "1200.50", "labour": False, "packaging": ""}),

    ("n01-no-derived-kind", "MONEY", "", ("material",), {"material": "12"}),
    ("n02-kind-with-no-sources", "MONEY", "SUM", (), {"material": "12"}),
    ("n03-unknown-kind", "MONEY", "QUOTIENT", ("a", "b"), {"a": "12", "b": "4"}),
]


def build() -> dict:
    cases = []
    for name, field_type, kind, sources, row in CASES:
        spec = FieldSpec(
            key="derived",
            label="Derived",
            type=FieldType[field_type],
            derived_kind=kind,
            derived_from=tuple(sources),
        )
        value = derive_value(spec, row)
        if isinstance(value, float) and value != value or value in (float("inf"), float("-inf")):
            raise SystemExit(f"{name}: non-finite expectation cannot be carried in JSON")
        cases.append(
            {
                "name": name,
                "type": field_type,
                "derivedKind": kind,
                "derivedFrom": list(sources),
                "row": row,
                "expected": value,
            }
        )
    return {
        "generatedBy": "frontend/tools/regenerate_derived_field_golden.py",
        "source": "backend/app/services/stage_schema.py::derive_value",
        "cases": cases,
    }


def main(argv: list[str]) -> int:
    payload = build()
    text = json.dumps(payload, indent=2, ensure_ascii=False) + "\n"
    if "--check" in argv:
        current = GOLDEN.read_text(encoding="utf-8") if GOLDEN.exists() else ""
        if current == text:
            print(f"{GOLDEN.name}: up to date ({len(payload['cases'])} cases)")
            return 0
        print(f"{GOLDEN.name}: STALE — re-run without --check", file=sys.stderr)
        return 1
    GOLDEN.parent.mkdir(parents=True, exist_ok=True)
    GOLDEN.write_text(text, encoding="utf-8")
    print(f"wrote {GOLDEN} ({len(payload['cases'])} cases)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
