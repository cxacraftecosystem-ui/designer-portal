from __future__ import annotations

import json
import sys
from datetime import datetime
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "backend"))

from app.services.records import derive_age, derive_experience_years  # noqa: E402

GOLDEN = REPO_ROOT / "frontend" / "e2e" / "fixtures" / "record-derivation-cases.json"

ON = datetime(2026, 8, 23)

CASES: list[tuple[str, str, str | None]] = [
    ("a01-bare-date", "age", "1971-01-08"),
    ("a02-stored-datetime-utc", "age", "1971-01-08T00:00:00Z"),
    ("a03-stored-datetime-ist", "age", "1971-01-08T00:00:00+05:30"),
    ("a04-space-separator", "age", "1971-01-08 09:15:00"),
    ("a05-basic-form", "age", "19710108"),
    ("a06-day-before-birthday", "age", "1980-08-24"),
    ("a07-on-the-birthday", "age", "1980-08-23"),
    ("a08-day-after-birthday", "age", "1980-08-22"),
    ("a09-born-today", "age", "2026-08-23"),
    ("a10-future-date", "age", "2030-01-01"),
    ("a11-impossible-day", "age", "1971-02-30"),
    ("a12-impossible-month", "age", "1971-13-01"),
    ("a13-out-of-band", "age", "1800-01-01"),
    ("a14-empty", "age", ""),
    ("a15-null", "age", None),
    ("a16-prose", "age", "about 30"),
    ("a17-trailing-junk", "age", "1971-01-08nonsense"),
    ("a18-legacy-plus", "age", "30+"),
    ("e01-thirty-two-years", "experience", "1994-03-12"),
    ("e02-stored-datetime-ist", "experience", "1994-03-12T00:00:00+05:30"),
    ("e03-started-this-year", "experience", "2026-01-15"),
    ("e04-started-today", "experience", "2026-08-23"),
    ("e05-day-before-anniversary", "experience", "1994-08-24"),
    ("e06-on-the-anniversary", "experience", "1994-08-23"),
    ("e07-ninety-years", "experience", "1936-08-23"),
    ("e08-ninety-one-years", "experience", "1935-08-23"),
    ("e09-future-date", "experience", "2030-01-01"),
    ("e10-impossible-day", "experience", "1994-02-30"),
    ("e11-empty", "experience", ""),
    ("e12-null", "experience", None),
    ("e13-prose", "experience", "about 30"),
]


def answer(kind: str, value: str | None) -> int | None:
    if kind == "age":
        return derive_age(value, on=ON)
    if kind == "experience":
        return derive_experience_years(value, on=ON)
    raise AssertionError(f"unknown derivation {kind!r}")


def build() -> dict:
    return {
        "on": ON.date().isoformat(),
        "cases": [
            {"name": name, "kind": kind, "value": value, "expected": answer(kind, value)}
            for name, kind, value in CASES
        ],
    }


def main() -> int:
    payload = build()
    text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    if "--check" in sys.argv:
        current = GOLDEN.read_text(encoding="utf-8") if GOLDEN.is_file() else ""
        if current != text:
            print(f"{GOLDEN} is not what today's Python produces. Re-run without --check.")
            return 1
        print(f"{GOLDEN} matches the backend rule ({len(payload['cases'])} cases).")
        return 0
    GOLDEN.parent.mkdir(parents=True, exist_ok=True)
    GOLDEN.write_text(text, encoding="utf-8")
    print(f"wrote {len(payload['cases'])} cases to {GOLDEN}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
