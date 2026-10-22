"""Record completeness: which fields a record still lacks, computed on read (2026-10-18).

── ONE TABLE, THREE PORTS ───────────────────────────────────────────────────────────────────────

``shared/record-completeness.json`` names, per record kind (artisan, product, tool, interview,
workshop), the fields a complete record carries and how each is checked. It is byte-identical in
both portals and is committed four times in this repository, each copy held equal by
``tests/test_record_completeness.py``:

* ``shared/record-completeness.json`` — the source;
* ``backend/app/data/record-completeness.json`` — what this module loads, because the backend image
  copies ``backend/app`` and nothing above it;
* ``frontend/lib/generated/record-completeness.json`` and
  ``android/app/src/main/assets/record-completeness.json`` — the clients' bundled copies, so the
  meter works offline and on records that exist only in an outbox.

The web (``frontend/lib/completeness.ts``) and Android (``data/RecordCompleteness.kt``) evaluators
are line-for-line ports of :func:`evaluate` and :func:`sentence`; the shared vectors run through all
three.

── THE FILTER AND THE METER MUST AGREE ──────────────────────────────────────────────────────────

:func:`missing_where` turns the same rules into a Prisma clause for the lists' ``completeness=``
filter. That is why no rule may name a prose (rich-text) column: an empty editor document is not an
empty string, SQL cannot see the difference, and the filter would list as incomplete a record whose
meter reads complete. One edge is unavoidable and accepted: a string column holding only
whitespace reads as missing to the meter and as filled to SQL (Prisma has no trim). No client writes
such a value from a form.

── WHAT IT NEVER TOUCHES ────────────────────────────────────────────────────────────────────────

The block carries field names, never values. It is computed after masking, reads only the columns
the rules name, and no rule covers an identity number, phone, email, address or gender — those are
recorded only with the artisan's consent and a meter must not nag for them. Nothing is stored and
nothing is logged.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

from app.services.concurrency import gather_reads

RULES_FILE = Path(__file__).resolve().parents[1] / "data" / "record-completeness.json"

#: The file's bytes, served as-is by ``GET /records/completeness-rules``.
RULES_BYTES: bytes = RULES_FILE.read_bytes().replace(b"\r\n", b"\n")
RULES: dict[str, Any] = json.loads(RULES_BYTES.decode("utf-8"))
RULES_VERSION: int = int(RULES["version"])
RULES_ETAG: str = f'"{RULES_VERSION}-{hashlib.sha256(RULES_BYTES).hexdigest()[:8]}"'

KINDS: tuple[str, ...] = tuple(RULES["kinds"])
MODES: frozenset[str] = frozenset({"incomplete", "required", "complete"})

#: Columns whose type is a string in ``schema.prisma``: only these get the ``""`` arm of ``present``.
#: (A ``{col: ""}`` against a DateTime or Decimal column is a Prisma validation error.)
STRING_COLUMNS: dict[str, frozenset[str]] = {
    "artisan": frozenset({"craftId", "dos", "donts", "locationId"}),
    "product": frozenset({"artisanId", "timeTakenToCompleteProduct"}),
    "tool": frozenset({"artisanId", "localName", "material"}),
    "interview": frozenset({"place", "language"}),
    "workshop": frozenset(),
}

#: The Prisma delegate name per kind, for the route layer and the tests.
DELEGATES: dict[str, str] = {
    "artisan": "artisan",
    "product": "productdocumentation",
    "tool": "tooldocumentation",
    "interview": "questionnaireinterview",
    "workshop": "workshop",
}

_LIVE_IMAGE = {"mediaType": "IMAGE", "status": {"not": "REJECTED"}}
_LIVE_RECORDING = {"mediaType": {"in": ["AUDIO", "VIDEO"]}, "status": {"not": "REJECTED"}}

#: ``count`` rules as relation clauses that match a record HAVING NONE. Correct for ``min: 1`` only,
#: which every count rule is (asserted at import below): "fewer than one" is "none".
COUNT_CLAUSES: dict[tuple[str, str], dict[str, Any]] = {
    ("artisan", "photos"): {"media": {"none": _LIVE_IMAGE}},
    ("product", "photos"): {"media": {"none": _LIVE_IMAGE}},
    ("tool", "photos"): {"media": {"none": _LIVE_IMAGE}},
    ("workshop", "photos"): {"media": {"none": _LIVE_IMAGE}},
    ("interview", "recordings"): {"media": {"none": _LIVE_RECORDING}},
    ("interview", "artisans"): {"artisans": {"none": {}}},
    ("interview", "answers"): {"responses": {"none": {}}},
    ("workshop", "artisans"): {"AND": [{"artisans": {"none": {}}}, {"artisansLinked": {"none": {}}}]},
    ("workshop", "crafts"): {"AND": [{"crafts": {"none": {}}}, {"craftsLinked": {"none": {}}}]},
}


def _check_rules() -> None:
    for kind, items in RULES["kinds"].items():
        assert kind in STRING_COLUMNS, kind
        for item in items:
            assert item["check"] in RULES["checks"], item
            assert item["level"] in ("required", "recommended"), item
            if item["check"] == "count":
                assert item["min"] == 1, f"{kind}.{item['key']}: COUNT_CLAUSES assume min 1"
                assert (kind, item["of"]) in COUNT_CLAUSES, (kind, item["of"])


_check_rules()


def rules_for(kind: str) -> list[dict[str, Any]]:
    return RULES["kinds"][kind]


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The evaluator (ported to web and Android)
# ──────────────────────────────────────────────────────────────────────────────────────────────


def _present(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return value.strip() != ""
    return True


def _satisfied(item: dict[str, Any], record: dict[str, Any], counts: dict[str, int]) -> bool:
    check = item["check"]
    if check == "present":
        return _present(record.get(item["path"]))
    if check == "notIn":
        # Exactly "not in empty": the columns it names are NOT NULL enums, and SQL's ``IN`` never
        # matches a null, so treating null as filled is what keeps the filter and the meter equal.
        return record.get(item["path"]) not in item["empty"]
    if check == "anyPresent":
        return any(_present(record.get(path)) for path in item["paths"])
    if check == "count":
        return int(counts.get(item["of"]) or 0) >= int(item["min"])
    raise ValueError(f"unknown check {check!r}")


def evaluate(kind: str, record: dict[str, Any], counts: dict[str, int] | None = None) -> dict[str, Any]:
    """The ``completeness`` block for one record: names of what is missing, never values."""
    items = rules_for(kind)
    counts = counts or {}
    missing = [item for item in items if not _satisfied(item, record, counts)]
    # Required first, then file order.
    missing.sort(key=lambda item: 0 if item["level"] == "required" else 1)
    total = len(items)
    filled = total - len(missing)
    return {
        "rulesVersion": RULES_VERSION,
        "filled": filled,
        "total": total,
        # Floor, so 100 means complete and nothing less: 6 of 7 reads 85, never a rounded-up 100.
        "percent": (filled * 100 // total) if total else 100,
        "requiredMissing": sum(1 for item in missing if item["level"] == "required"),
        "missing": [
            {
                "key": item["key"],
                "label": item["label"],
                "words": item["words"],
                "level": item["level"],
            }
            for item in missing
        ],
    }


def sentence(block: dict[str, Any]) -> str:
    """``"3 fields left: craft start date, photo, product price"`` / ``"Complete"``."""
    missing = block.get("missing") or []
    count = len(missing)
    if count == 0:
        return "Complete"
    words = [item["words"] for item in missing]
    head = ", ".join(words[:3])
    more = f", +{count - 3} more" if count > 3 else ""
    return f"{count} {'field' if count == 1 else 'fields'} left: {head}{more}"


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The list filter
# ──────────────────────────────────────────────────────────────────────────────────────────────


def _missing_clause(kind: str, item: dict[str, Any]) -> dict[str, Any]:
    check = item["check"]
    if check == "present":
        path = item["path"]
        if path in STRING_COLUMNS[kind]:
            return {"OR": [{path: None}, {path: ""}]}
        return {path: None}
    if check == "notIn":
        return {item["path"]: {"in": list(item["empty"])}}
    if check == "anyPresent":
        return {"AND": [{path: None} for path in item["paths"]]}
    if check == "count":
        return COUNT_CLAUSES[(kind, item["of"])]
    raise ValueError(f"unknown check {check!r}")


def missing_where(kind: str, mode: str) -> dict[str, Any]:
    """The Prisma clause selecting records of ``kind`` in ``mode``. AND it into a list's filters."""
    if mode not in MODES:
        raise ValueError(mode)
    items = rules_for(kind)
    if mode == "required":
        items = [item for item in items if item["level"] == "required"]
        if not items:
            # No required rule: nothing can be missing one. ``id: {in: []}`` matches no row.
            return {"id": {"in": []}}
    incomplete = {"OR": [_missing_clause(kind, item) for item in items]}
    if mode == "complete":
        return {"NOT": incomplete}
    return incomplete


def filter_or_422(kind: str, mode: str | None) -> dict[str, Any] | None:
    """The route-side spelling: ``None`` for no filter, the clause for a valid mode, 422 otherwise."""
    if mode is None or mode == "":
        return None
    if mode not in MODES:
        from fastapi import HTTPException, status

        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="completeness must be one of complete, incomplete, required",
        )
    return missing_where(kind, mode)


# ──────────────────────────────────────────────────────────────────────────────────────────────
# Counts, one query per source for a whole page
# ──────────────────────────────────────────────────────────────────────────────────────────────


def _group_counts(groups: list[Any], key: str) -> dict[str, int]:
    out: dict[str, int] = {}
    for group in groups:
        value = group.get(key)
        if value:
            out[str(value)] = int((group.get("_count") or {}).get("_all") or 0)
    return out


def _pairs(groups: list[Any], key: str, other: str) -> dict[str, set[str]]:
    out: dict[str, set[str]] = {}
    for group in groups:
        value, member = group.get(key), group.get(other)
        if value and member:
            out.setdefault(str(value), set()).add(str(member))
    return out


async def counts_for(kind: str, ids: list[str], client: Any = None) -> dict[str, dict[str, int]]:
    """``{record id: {count name: n}}`` for every count the kind's rules name. No N+1."""
    if client is None:
        from app.core.db import db as client
    ids = [i for i in dict.fromkeys(ids) if i]
    result: dict[str, dict[str, int]] = {i: {} for i in ids}
    if not ids:
        return result

    if kind in ("artisan", "product", "tool", "workshop"):
        fk = {"artisan": "artisanId", "product": "productId", "tool": "toolId", "workshop": "workshopId"}[kind]
        reads = [client.mediafile.group_by(by=[fk], count=True, where={fk: {"in": ids}, **_LIVE_IMAGE})]
        if kind == "workshop":
            reads += [
                client.workshopartisan.group_by(by=["workshopId", "artisanId"], where={"workshopId": {"in": ids}}),
                client.artisan.group_by(by=["workshopId", "id"], where={"workshopId": {"in": ids}}),
                client.workshopcraft.group_by(by=["workshopId", "craftId"], where={"workshopId": {"in": ids}}),
                client.craft.group_by(by=["workshopId", "id"], where={"workshopId": {"in": ids}}),
            ]
        answers = await gather_reads(*reads)
        photos = _group_counts(answers[0], fk)
        for i in ids:
            result[i]["photos"] = photos.get(i, 0)
        if kind == "workshop":
            joined_a, column_a = _pairs(answers[1], "workshopId", "artisanId"), _pairs(answers[2], "workshopId", "id")
            joined_c, column_c = _pairs(answers[3], "workshopId", "craftId"), _pairs(answers[4], "workshopId", "id")
            for i in ids:
                result[i]["artisans"] = len(joined_a.get(i, set()) | column_a.get(i, set()))
                result[i]["crafts"] = len(joined_c.get(i, set()) | column_c.get(i, set()))
        return result

    if kind == "interview":
        recordings, artisans, answers = await gather_reads(
            client.mediafile.group_by(
                by=["questionnaireInterviewId"],
                count=True,
                where={"questionnaireInterviewId": {"in": ids}, **_LIVE_RECORDING},
            ),
            client.questionnaireinterviewartisan.group_by(
                by=["interviewId"], count=True, where={"interviewId": {"in": ids}}
            ),
            client.questionnaireresponse.group_by(
                by=["interviewId"], count=True, where={"interviewId": {"in": ids}}
            ),
        )
        rec = _group_counts(recordings, "questionnaireInterviewId")
        art = _group_counts(artisans, "interviewId")
        ans = _group_counts(answers, "interviewId")
        # A shared-questionnaire session's answers (UNIFY C10; nothing, and no query, while v2 is off).
        from app.services import questionnaire_readers

        for session_id, n in (await questionnaire_readers.answer_counts(ids, client=client)).items():
            ans[session_id] = ans.get(session_id, 0) + n
        for i in ids:
            result[i] = {"recordings": rec.get(i, 0), "artisans": art.get(i, 0), "answers": ans.get(i, 0)}
        return result

    raise ValueError(kind)


async def attach(kind: str, payload: Any, client: Any = None) -> Any:
    """Set ``completeness`` on an encoded record (dict) or list of them, after masking. Returns it."""
    items = payload if isinstance(payload, list) else [payload]
    records = [item for item in items if isinstance(item, dict) and item.get("id")]
    if not records:
        return payload
    try:
        counts = await counts_for(kind, [str(item["id"]) for item in records], client)
    except Exception:  # noqa: BLE001 - a meter must never fail the save or the read it rides on
        # A POST or PATCH that already wrote must not answer 500 over a read-only extra: the client
        # would retry a write that landed. No block is the honest answer; clients fall back to their
        # own evaluator. ``tests/test_record_completeness.py`` holds the block to being present.
        return payload
    for item in records:
        item["completeness"] = evaluate(kind, item, counts.get(str(item["id"]), {}))
    return payload
