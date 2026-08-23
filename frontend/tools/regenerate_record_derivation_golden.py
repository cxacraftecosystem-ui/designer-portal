"""Regenerate `frontend/e2e/fixtures/record-derivation-cases.json` from the BACKEND's own rule.

WHY THIS FILE EXISTS. `frontend/lib/recordDerivations.ts` is a port of `records.derive_age` and
`records.derive_experience_years` — the two numbers the artisan record page SHOWS and never stores —
and its header makes a promise about itself: "IT IS A PORT AND NOT A SECOND OPINION". A form that
computed its own age would print a figure the save then disagreed with, which is worse than printing
none, because the researcher has already read it and acted on it.

So the expectations here are whatever PYTHON answers, and `e2e/record-derivations-unit.spec.ts`
recomputes every case in Node and diffs it. When the two disagree the Python is right and the port is
broken — never "regenerate until it passes". That is the same rule, and the same reason, as
`regenerate_derived_field_golden.py` beside this file; this one covers the other kind of derivation,
the one that reads a COLUMN ON A RECORD rather than a sibling field on a workshop entry.

THE REFERENCE DAY IS STATED, NOT `now()`. Both functions take a keyword-only `on` for exactly this
reason, written on the server: "an age function tested against `now()` passes in March and fails in
September, on the birthday of whatever fixture it uses." Every case below is evaluated against
2026-08-23, so the golden is a fact about the rule rather than about the day the file was written.

Run it from anywhere, with the backend venv:

    backend/.venv/Scripts/python.exe frontend/tools/regenerate_record_derivation_golden.py

Add `--check` to compare without writing — what proves the committed golden is still what today's
Python produces:

    backend/.venv/Scripts/python.exe frontend/tools/regenerate_record_derivation_golden.py --check

WRITTEN WITH AN EXPLICIT UTF-8 ENCODING rather than printed, for the reason the sibling generators
give: redirecting Python's stdout on Windows is cp1252 and dies on the first non-ASCII character.

TWO SPELLINGS ARE DELIBERATELY ABSENT and are asserted by hand in the spec instead, with the
argument written where the assertion is: the ISO week form ("2026-W33-4") and the ordinal form
("2026-227"), both of which `datetime.fromisoformat` accepts and the port refuses. No date control on
any of the three clients can produce either, and growing an ISO-8601 parser in the browser to cover
them would be the port writing its own opinion. Carrying them here would instead pin a divergence
into the golden as though it were the rule.
"""

from __future__ import annotations

import json
import sys
from datetime import datetime
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "backend"))

from app.services.records import derive_age, derive_experience_years  # noqa: E402

GOLDEN = REPO_ROOT / "frontend" / "e2e" / "fixtures" / "record-derivation-cases.json"

#: The stated day every case is evaluated against. See the module docstring.
ON = datetime(2026, 8, 23)

# (name, which derivation, the stored/typed value as a client would hold it)
#
# EVERY VALUE IS A STRING OR NULL, because that is what a client can hold: the artisan form submits
# `yyyy-mm-dd` out of a date field and the API returns `1994-03-12T00:00:00Z` for the column. The
# server also accepts `date`/`datetime` objects, which no browser has, so those paths are covered by
# the backend's own tests rather than pretended at here.
CASES: list[tuple[str, str, str | None]] = [
    # ── AGE: the shapes that reach a client ─────────────────────────────────────────────────
    ("a01-bare-date", "age", "1971-01-08"),
    ("a02-stored-datetime-utc", "age", "1971-01-08T00:00:00Z"),
    ("a03-stored-datetime-ist", "age", "1971-01-08T00:00:00+05:30"),
    ("a04-space-separator", "age", "1971-01-08 09:15:00"),
    ("a05-basic-form", "age", "19710108"),
    # ── AGE: the anniversary correction, which is the whole reason it is not a division ──────
    ("a06-day-before-birthday", "age", "1980-08-24"),
    ("a07-on-the-birthday", "age", "1980-08-23"),
    ("a08-day-after-birthday", "age", "1980-08-22"),
    ("a09-born-today", "age", "2026-08-23"),
    # ── AGE: everything that must answer null rather than a number ───────────────────────────
    ("a10-future-date", "age", "2030-01-01"),
    ("a11-impossible-day", "age", "1971-02-30"),
    ("a12-impossible-month", "age", "1971-13-01"),
    ("a13-out-of-band", "age", "1800-01-01"),
    ("a14-empty", "age", ""),
    ("a15-null", "age", None),
    ("a16-prose", "age", "about 30"),
    ("a17-trailing-junk", "age", "1971-01-08nonsense"),
    ("a18-legacy-plus", "age", "30+"),
    # ── EXPERIENCE: the same rule, a different band ──────────────────────────────────────────
    ("e01-thirty-two-years", "experience", "1994-03-12"),
    ("e02-stored-datetime-ist", "experience", "1994-03-12T00:00:00+05:30"),
    # ZERO IS A REAL ANSWER HERE and null is not the same statement — an apprentice who started
    # this year has zero whole years of experience. This is the case that says every reader must
    # test for null rather than for truthiness.
    ("e03-started-this-year", "experience", "2026-01-15"),
    ("e04-started-today", "experience", "2026-08-23"),
    ("e05-day-before-anniversary", "experience", "1994-08-24"),
    ("e06-on-the-anniversary", "experience", "1994-08-23"),
    # THE BAND IS `participant.experienceYears`' OWN (0..90) and it is load-bearing: `validate_entry`
    # re-coerces every field on every save, so a hydrated 91 would become a refused answer on a box
    # the designer never touched. 90 is carried; 91 must answer null.
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
