"""RECORD COMPLETENESS — one rules table, the same answer from the meter and from the list filter.

``shared/record-completeness.json`` declares, per record kind, which fields a complete record holds.
``services/completeness`` evaluates it on every record the API returns and turns it into the Prisma
clause behind the lists' ``completeness=`` filter. The web and Android clients run ports of the same
evaluator over the same file, so this module holds:

* the evaluator and the sentence to the shared vectors, plus the edge cases the vectors leave out;
* THE KEY PROPERTY: for every kind and every mode, the filter selects exactly the rows whose meter
  says so — first against an in-memory interpreter of the generated clause over hundreds of rows,
  then against Postgres when a local database is running;
* the rules to the artisan form contract (a "required" item is one the form refuses to save without)
  and to data protection (no prose, no identity or contact column, no column the schema lacks);
* the page cost (one query per count source, whatever the page size);
* the four committed copies of the file to each other, and to the sibling portal's when present;
* the routes: the parameter, the 422, the block on GET / POST / PATCH, the masked viewer, the ETag.
"""

from __future__ import annotations

import asyncio
import itertools
import json
import os
import pathlib
import random
import re
import uuid
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from app.services import completeness as rc

ROOT = pathlib.Path(__file__).resolve().parents[2]
SHARED = ROOT / "shared" / "record-completeness.json"
COPIES = (
    ROOT / "backend" / "app" / "data" / "record-completeness.json",
    ROOT / "frontend" / "lib" / "generated" / "record-completeness.json",
    ROOT / "android" / "app" / "src" / "main" / "assets" / "record-completeness.json",
)


def _text(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")


def table() -> dict[str, Any]:
    return json.loads(_text(SHARED))


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The evaluator and the sentence
# ──────────────────────────────────────────────────────────────────────────────────────────────


def test_the_shared_vectors_hold():
    vectors = table()["vectors"]
    assert vectors, "a vector table that failed to load would pass vacuously"
    for vector in vectors:
        block = rc.evaluate(vector["kind"], vector["record"], vector["counts"])
        expect = vector["expect"]
        assert block["filled"] == expect["filled"], vector
        assert block["total"] == expect["total"], vector
        assert [m["key"] for m in block["missing"]] == expect["missing"], vector
        assert block["requiredMissing"] == expect["requiredMissing"], vector
        assert rc.sentence(block) == expect["sentence"], vector


def test_the_block_shape_is_the_contract():
    block = rc.evaluate("artisan", {"dateOfBirth": None}, {"photos": 0})
    assert set(block) == {"rulesVersion", "filled", "total", "percent", "requiredMissing", "missing"}
    assert block["rulesVersion"] == table()["version"]
    for item in block["missing"]:
        assert set(item) == {"key", "label", "words", "level"}


@pytest.mark.parametrize(
    ("record", "counts", "sentence", "percent"),
    [
        # Nothing at all: five required items lead, then the two recommended, in file order.
        ({}, {}, "7 fields left: date of birth, craft start date, craft, +4 more", 0),
        # One left: singular.
        (
            {"dateOfBirth": "1970-01-01", "craftStartDate": "1990-01-01", "craftId": "c", "dos": "d",
             "donts": "n", "locationId": "l"},
            {"photos": 0},
            "1 field left: photo",
            85,
        ),
        # Everything: "Complete" and 100.
        (
            {"dateOfBirth": "1970-01-01", "craftStartDate": "1990-01-01", "craftId": "c", "dos": "d",
             "donts": "n", "locationId": "l"},
            {"photos": 2},
            "Complete",
            100,
        ),
        # Blank after trim is missing; a recommended item stays behind every required one.
        (
            {"dateOfBirth": "1970-01-01", "craftStartDate": "1990-01-01", "craftId": "c", "dos": "  ",
             "donts": "n"},
            {"photos": 1},
            "2 fields left: do's, location",
            71,
        ),
    ],
)
def test_sentence_and_percent_edges(record, counts, sentence, percent):
    block = rc.evaluate("artisan", record, counts)
    assert rc.sentence(block) == sentence
    assert block["percent"] == percent


def test_zero_is_present_and_unknown_is_not():
    product = {"artisanId": "a", "sellingPrice": 0, "costOfMaking": 0, "timeTakenToCompleteProduct": "2 days",
               "heightInches": 0, "marketDemand": "UNKNOWN"}
    block = rc.evaluate("product", product, {"photos": 1})
    assert [m["key"] for m in block["missing"]] == ["marketDemand"]


# ──────────────────────────────────────────────────────────────────────────────────────────────
# THE KEY PROPERTY: the filter and the meter agree, row for row
# ──────────────────────────────────────────────────────────────────────────────────────────────

# A row in the interpreter is the record's scalars plus its relation lists, named as Prisma names
# them. Counts are derived from those lists by the definitions in the JSON's "counts" block, which is
# what ``counts_for`` computes in SQL.
_RELATIONS = {
    "artisan": ("media",),
    "product": ("media",),
    "tool": ("media",),
    "workshop": ("media", "artisans", "artisansLinked", "crafts", "craftsLinked"),
    "interview": ("media", "artisans", "responses"),
}
_MEDIA_POOL = (
    {"mediaType": "IMAGE", "status": "APPROVED"},
    {"mediaType": "IMAGE", "status": "REJECTED"},
    {"mediaType": "IMAGE", "status": "PENDING"},
    {"mediaType": "AUDIO", "status": "PENDING"},
    {"mediaType": "VIDEO", "status": "REJECTED"},
    {"mediaType": "PDF", "status": "APPROVED"},
)


def _live(media: list[dict[str, Any]], types: tuple[str, ...]) -> int:
    return sum(1 for m in media if m["mediaType"] in types and m["status"] != "REJECTED")


def _counts(kind: str, row: dict[str, Any]) -> dict[str, int]:
    media = row.get("media", [])
    if kind == "interview":
        return {
            "recordings": _live(media, ("AUDIO", "VIDEO")),
            "artisans": len(row["artisans"]),
            "answers": len(row["responses"]),
        }
    counts = {"photos": _live(media, ("IMAGE",))}
    if kind == "workshop":
        counts["artisans"] = len(set(row["artisans"]) | set(row["artisansLinked"]))
        counts["crafts"] = len(set(row["crafts"]) | set(row["craftsLinked"]))
    return counts


def _match_scalar(value: Any, cond: Any) -> bool:
    if isinstance(cond, dict):
        ok = True
        for op, arg in cond.items():
            if op == "in":
                ok &= value is not None and value in arg
            elif op == "not":
                ok &= value is not None and value != arg
            elif op == "equals":
                ok &= value == arg
            else:
                raise AssertionError(f"interpreter lacks scalar op {op}")
        return ok
    if cond is None:
        return value is None
    return value is not None and value == cond


def matches(row: dict[str, Any], where: dict[str, Any]) -> bool:
    """A Prisma ``where`` over one in-memory row — the subset ``missing_where`` emits, and no more."""
    for key, cond in where.items():
        if key == "AND":
            ok = all(matches(row, c) for c in (cond if isinstance(cond, list) else [cond]))
        elif key == "OR":
            ok = any(matches(row, c) for c in cond)
        elif key == "NOT":
            ok = not any(matches(row, c) for c in (cond if isinstance(cond, list) else [cond]))
        elif isinstance(cond, dict) and set(cond) & {"none", "some", "every"}:
            members = row[key]
            (op, sub), = cond.items()
            hits = [m for m in members if (matches(m, sub) if isinstance(m, dict) else True) or not sub]
            if sub and members and not isinstance(members[0], dict):
                raise AssertionError("a filter on a link list the interpreter stores as ids")
            ok = {"none": not hits, "some": bool(hits), "every": len(hits) == len(members)}[op]
        elif key == "id" and isinstance(cond, dict):
            ok = _match_scalar(row.get("id"), cond)
        else:
            ok = _match_scalar(row.get(key), cond)
        if not ok:
            return False
    return True


def _value_pool(kind: str, path: str, item: dict[str, Any]) -> list[Any]:
    """Values a column of that type can hold: a blank string only where the column is a string."""
    if item["check"] == "notIn":
        return list(item["empty"]) + ["HIGH", "ARTISAN", "TRADITIONAL"]
    if path in rc.STRING_COLUMNS[kind]:
        return [None, "", "x", "y"]
    return [None, 0, 12.5, "2026-01-01T00:00:00Z"]


def _rows(kind: str, n: int, seed: int) -> list[dict[str, Any]]:
    rng = random.Random(seed)
    paths: dict[str, list[Any]] = {}
    for item in rc.rules_for(kind):
        for path in item.get("paths") or ([item["path"]] if "path" in item else []):
            paths.setdefault(path, _value_pool(kind, path, item))
    rows = []
    for index in range(n):
        row: dict[str, Any] = {"id": f"{kind}-{index}"}
        for path, pool in paths.items():
            row[path] = rng.choice(pool)
        for relation in _RELATIONS[kind]:
            if relation == "media":
                row[relation] = [dict(m) for m in rng.sample(_MEDIA_POOL, rng.choice([0, 0, 1, 2]))]
            else:
                row[relation] = rng.sample(["p", "q", "r"], rng.choice([0, 0, 1, 2]))
        rows.append(row)
    # And the two corners random choice may miss: everything filled, nothing filled.
    full = {"id": f"{kind}-full"}
    empty = {"id": f"{kind}-empty"}
    for item in rc.rules_for(kind):
        for path in item.get("paths") or ([item["path"]] if "path" in item else []):
            full[path] = "HIGH" if item["check"] == "notIn" else "x"
            empty[path] = item["empty"][0] if item["check"] == "notIn" else None
    for relation in _RELATIONS[kind]:
        full[relation] = [{"mediaType": "IMAGE", "status": "APPROVED"}, {"mediaType": "AUDIO", "status": "APPROVED"}] if relation == "media" else ["p"]
        empty[relation] = []
    return rows + [full, empty]


def _expected(kind: str, mode: str, block: dict[str, Any]) -> bool:
    if mode == "incomplete":
        return bool(block["missing"])
    if mode == "required":
        return block["requiredMissing"] > 0
    return not block["missing"]


@pytest.mark.parametrize(("kind", "mode"), list(itertools.product(rc.KINDS, sorted(rc.MODES))))
def test_the_filter_selects_exactly_the_rows_the_meter_flags(kind, mode):
    where = rc.missing_where(kind, mode)
    rows = _rows(kind, 400, seed=hash((kind, mode)) & 0xFFFF)
    seen = {True: 0, False: 0}
    for row in rows:
        block = rc.evaluate(kind, row, _counts(kind, row))
        want = _expected(kind, mode, block)
        assert matches(row, where) == want, (kind, mode, row, block)
        seen[want] += 1
    # Both sides exercised, or the agreement is vacuous (``required`` on a kind with no required
    # rule is empty by definition).
    if not (mode == "required" and not any(i["level"] == "required" for i in rc.rules_for(kind))):
        assert seen[True] and seen[False], (kind, mode, seen)


def test_a_bad_mode_is_refused():
    with pytest.raises(ValueError):
        rc.missing_where("artisan", "half")
    from fastapi import HTTPException

    with pytest.raises(HTTPException) as caught:
        rc.filter_or_422("artisan", "half")
    assert caught.value.status_code == 422
    assert rc.filter_or_422("artisan", None) is None


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The rules themselves
# ──────────────────────────────────────────────────────────────────────────────────────────────


def test_every_required_rule_is_one_the_artisan_form_already_refuses_without():
    """The meter reports; it never invents a requirement. A ``required`` item is either one of the
    contract's two dates or a field ``ArtisanCreate`` / the form already demands."""
    from pydantic import ValidationError

    from app.schemas.records import ArtisanCreate

    contract = json.loads(_text(ROOT / "shared" / "artisan-form-contract.json"))
    contract_dates = {d["field"] for d in contract["requiredDates"]}
    validated = {"craftId", "dos", "donts"}
    for kind, items in table()["kinds"].items():
        for item in items:
            if item["level"] != "required":
                continue
            assert kind == "artisan", f"{kind}.{item['key']} is required but only the artisan form refuses"
            assert item["path"] in contract_dates | validated, item
            if item["path"] in contract_dates:
                assert item.get("source") == "artisan-form-contract", item
                words = {d["field"]: d["words"] for d in contract["requiredDates"]}
                assert item["words"] == words[item["path"]], item
    # The three validators the claim leans on are really there.
    base = {"name": "A", "place": "P", "aadhaarNumber": "x", "dos": "d", "donts": "n"}
    for broken in ({**base, "dos": ""}, {**base, "donts": ""}, base):
        with pytest.raises(ValidationError):
            ArtisanCreate(**broken)


_PROSE = {"notes", "rawMaterialsUsed", "mainToolsUsed", "processUsedIn", "remarks", "description",
          "productFunctionUse", "suggestionsForToolImprovement", "reviewNotes"}
_SENSITIVE = re.compile(r"aadhaar|pehchan|phone|email|address|gender", re.IGNORECASE)
_MODEL = {
    "artisan": "Artisan",
    "product": "ProductDocumentation",
    "tool": "ToolDocumentation",
    "interview": "QuestionnaireInterview",
    "workshop": "Workshop",
}


def _columns(model: str) -> dict[str, str]:
    schema = _text(ROOT / "backend" / "prisma" / "schema.prisma")
    body = re.search(rf"^model {model} \{{(.*?)^\}}", schema, re.S | re.M)
    assert body, model
    out = {}
    for line in body.group(1).splitlines():
        line = line.split("//", 1)[0].strip()
        parts = line.split()
        if len(parts) >= 2 and not parts[0].startswith("@"):
            out[parts[0]] = parts[1]
    return out


def test_no_rule_names_prose_identity_contact_or_a_missing_column():
    for kind, items in table()["kinds"].items():
        columns = _columns(_MODEL[kind])
        for item in items:
            paths = item.get("paths") or ([item["path"]] if "path" in item else [])
            for path in paths:
                assert path not in _PROSE, (kind, path)
                assert not _SENSITIVE.search(path), (kind, path)
                assert path in columns, f"{kind}.{path} is not a column of {_MODEL[kind]}"
                is_string = columns[path].rstrip("?") == "String"
                assert (path in rc.STRING_COLUMNS[kind]) == is_string, (kind, path, columns[path])


def test_every_count_has_a_clause_and_a_definition():
    data = table()
    for kind, items in data["kinds"].items():
        for item in items:
            if item["check"] == "count":
                assert item["of"] in data["counts"], item
                assert (kind, item["of"]) in rc.COUNT_CLAUSES


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The page cost
# ──────────────────────────────────────────────────────────────────────────────────────────────


class _Recorder:
    def __init__(self, name: str, log: list[str]) -> None:
        self.name, self.log = name, log

    async def group_by(self, **kwargs: Any) -> list[dict[str, Any]]:
        self.log.append(self.name)
        return []

    def __getattr__(self, attr: str) -> Any:
        raise AssertionError(f"counts_for called {self.name}.{attr}")


@pytest.mark.parametrize("kind", rc.KINDS)
def test_counts_for_is_one_query_per_source_for_a_page_of_100(kind):
    log: list[str] = []
    client = SimpleNamespace(
        **{name: _Recorder(name, log) for name in (
            "mediafile", "workshopartisan", "artisan", "workshopcraft", "craft",
            "questionnaireinterviewartisan", "questionnaireresponse",
        )}
    )
    ids = [f"id-{i}" for i in range(100)]
    counts = asyncio.run(rc.counts_for(kind, ids, client))
    assert len(log) == len(set(log)), log
    assert len(log) == {"workshop": 5, "interview": 3}.get(kind, 1), log
    assert set(counts) == set(ids)


def test_attach_carries_names_never_values():
    record = {
        "id": "a1", "aadhaarNumber": "XXXX XXXX 1234", "phone": "9876543210", "dos": "Greet first",
        "craftId": "craft-secret-id", "dateOfBirth": None,
    }

    class _Client:
        def __getattr__(self, _name: str) -> Any:
            return SimpleNamespace(group_by=lambda **_: _none())

    async def _none() -> list[Any]:
        return []

    out = asyncio.run(rc.attach("artisan", dict(record), _Client()))
    block = json.dumps(out["completeness"])
    for value in ("1234", "9876543210", "Greet first", "craft-secret-id"):
        assert value not in block


def test_a_failing_count_read_drops_the_block_and_never_the_response():
    class _Broken:
        def __getattr__(self, _name: str) -> Any:
            raise RuntimeError("no database")

    out = asyncio.run(rc.attach("tool", {"id": "t1"}, _Broken()))
    assert out == {"id": "t1"}


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The copies
# ──────────────────────────────────────────────────────────────────────────────────────────────


def test_every_committed_copy_is_the_shared_file():
    shared = _text(SHARED)
    for copy in COPIES:
        assert copy.is_file(), copy
        assert _text(copy) == shared, f"{copy.relative_to(ROOT)} differs from shared/"


def _sibling() -> pathlib.Path | None:
    other = (
        "documentation-portal"
        if any(ROOT.glob("android/app/src/main/java/com/designprototype"))
        else "designer-portal"
    )
    for candidate in (os.environ.get("SIBLING_REPO"), ROOT.parent / other, pathlib.Path("C:/dev/IIT") / other):
        if candidate and (pathlib.Path(candidate) / "shared" / "record-completeness.json").is_file():
            return pathlib.Path(candidate)
    return None


def test_the_sibling_portal_carries_the_same_rules():
    sibling = _sibling()
    if sibling is None:
        pytest.skip("no sibling checkout carrying the rules (CI has one repository)")
    assert _text(sibling / "shared" / "record-completeness.json") == _text(SHARED)


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The routes, without a database: the parameter, the 422 and the rules endpoint
# ──────────────────────────────────────────────────────────────────────────────────────────────

_LIST_ROUTES = ("/api/artisans", "/api/products", "/api/tools", "/api/workshops", "/api/questionnaire/interviews")


def _app() -> FastAPI:
    from app.api.router import api_router
    from app.core import deps

    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: SimpleNamespace(
        id="u1", role="RESEARCHER", email="u@example.test", name="U", canReview=False,
        canDownloadDataset=False,
    )
    return application


def _get(path: str, headers: dict[str, str] | None = None) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_app())
        async with httpx.AsyncClient(transport=transport, base_url="http://rc.test") as client:
            return await client.get(path, headers=headers or {})

    return asyncio.run(run())


@pytest.mark.parametrize("path", _LIST_ROUTES)
def test_every_list_route_refuses_an_unknown_completeness(path):
    response = _get(f"{path}?completeness=mostly")
    assert response.status_code == 422, response.text
    assert "completeness" in response.text


def test_the_rules_endpoint_serves_the_file_with_an_etag_and_answers_304():
    first = _get("/api/records/completeness-rules")
    assert first.status_code == 200
    assert first.json() == table()
    etag = first.headers["etag"]
    assert re.fullmatch(r'"\d+-[0-9a-f]{8}"', etag)
    assert etag.startswith(f'"{table()["version"]}-')
    again = _get("/api/records/completeness-rules", {"If-None-Match": etag})
    assert again.status_code == 304
    assert again.content == b""
    stale = _get("/api/records/completeness-rules", {"If-None-Match": '"0-deadbeef"'})
    assert stale.status_code == 200


def test_the_rules_endpoint_needs_a_sign_in():
    from app.api.router import api_router

    application = FastAPI()
    application.include_router(api_router)

    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=application)
        async with httpx.AsyncClient(transport=transport, base_url="http://rc.test") as client:
            return await client.get("/api/records/completeness-rules")

    assert asyncio.run(run()).status_code == 401


# ──────────────────────────────────────────────────────────────────────────────────────────────
# Against Postgres
# ──────────────────────────────────────────────────────────────────────────────────────────────

from conftest import needs_db  # noqa: E402

LOCATION = {"latitude": 21.19, "longitude": 83.58, "state": "Odisha", "district": "Bargarh"}


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.core.db import db
    from app.core.security import create_access_token, hash_password
    from app.main import app

    tag = uuid.uuid4().hex[:8]

    async def seed() -> dict[str, Any]:
        await db.connect()
        try:
            async def user(role: str) -> Any:
                return await db.user.create(
                    data={
                        "email": f"rc-{role.lower()}-{tag}@example.org",
                        "name": f"RC {role.title()}",
                        "role": role,
                        "passwordHash": hash_password("unused"),
                    }
                )

            owner, other, admin = await user("RESEARCHER"), await user("FIELD_CONTRIBUTOR"), await user("ADMIN")
            craft = await db.craft.create(data={"name": f"RC Craft {tag}"})
            location = await db.location.create(data={"latitude": 21.19, "longitude": 83.58})

            async def media(kind: str, fk: str, record_id: str, status: str = "APPROVED") -> None:
                await db.mediafile.create(
                    data={
                        "originalFilename": "f", "mediaType": kind, "mimeType": "image/jpeg",
                        "sizeBytes": 1, "bucket": "b", "objectKey": f"rc/{uuid.uuid4().hex}",
                        "uploadedById": owner.id, "status": status, fk: record_id,
                    }
                )

            base = {"place": "Bagru", "createdById": owner.id}
            artisans = {
                # The FR shape: everything but the craft start date.
                "no_start": await db.artisan.create(data={
                    **base, "name": f"No Start {tag}", "dateOfBirth": "1970-01-01T00:00:00Z",
                    "craftId": craft.id, "dos": "d", "donts": "n", "locationId": location.id,
                    "aadhaarNumber": None, "phone": "9876543210",
                }),
                "complete": await db.artisan.create(data={
                    **base, "name": f"Complete {tag}", "dateOfBirth": "1970-01-01T00:00:00Z",
                    "craftStartDate": "1990-01-01T00:00:00Z", "craftId": craft.id, "dos": "d",
                    "donts": "n", "locationId": location.id,
                }),
                "rejected_photo": await db.artisan.create(data={
                    **base, "name": f"Rejected Photo {tag}", "dateOfBirth": "1970-01-01T00:00:00Z",
                    "craftStartDate": "1990-01-01T00:00:00Z", "craftId": craft.id, "dos": "d",
                    "donts": "n", "locationId": location.id,
                }),
                "blank": await db.artisan.create(data={**base, "name": f"Blank {tag}", "dos": "", "donts": None}),
            }
            await media("IMAGE", "artisanId", artisans["complete"].id)
            await media("IMAGE", "artisanId", artisans["rejected_photo"].id, status="REJECTED")
            await media("AUDIO", "artisanId", artisans["rejected_photo"].id)

            pbase = {"craftName": "C", "place": "P", "artisanName": "A", "createdById": owner.id}
            products = [
                await db.productdocumentation.create(data={**pbase, "productName": f"P0 {tag}"}),
                await db.productdocumentation.create(data={
                    **pbase, "productName": f"P1 {tag}", "artisanId": artisans["complete"].id,
                    "sellingPrice": "0", "costOfMaking": "10", "timeTakenToCompleteProduct": "2 days",
                    "breadthInches": "3", "marketDemand": "HIGH",
                }),
            ]
            await media("IMAGE", "productId", products[1].id, status="PENDING")
            tbase = {"craftName": "C", "place": "P", "artisanName": "A", "createdById": owner.id}
            tools = [
                await db.tooldocumentation.create(data={**tbase, "toolkitName": f"T0 {tag}", "localName": ""}),
                await db.tooldocumentation.create(data={
                    **tbase, "toolkitName": f"T1 {tag}", "artisanId": artisans["complete"].id,
                    "localName": "L", "material": "Iron", "lengthInches": "1", "maker": "ARTISAN",
                    "traditionType": "MODERN", "replacementCost": "100",
                }),
            ]
            await media("IMAGE", "toolId", tools[1].id)
            wbase = {"place": "P", "createdById": owner.id, "date": "2026-01-01T00:00:00Z"}
            workshops = [
                await db.workshop.create(data={**wbase, "title": f"W0 {tag}"}),
                await db.workshop.create(data={
                    **wbase, "title": f"W1 {tag}", "startDate": "2026-01-01T00:00:00Z",
                    "endDate": "2026-01-03T00:00:00Z",
                }),
                await db.workshop.create(data={
                    **wbase, "title": f"W2 {tag}", "startDate": "2026-01-01T00:00:00Z",
                    "endDate": "2026-01-03T00:00:00Z",
                }),
            ]
            # W1 links through the join tables, W2 through the columns: both are "artisans" and "crafts".
            await db.workshopartisan.create(data={"workshopId": workshops[1].id, "artisanId": artisans["blank"].id})
            await db.workshopcraft.create(data={"workshopId": workshops[1].id, "craftId": craft.id})
            await db.artisan.update(where={"id": artisans["no_start"].id}, data={"workshopId": workshops[2].id})
            craft2 = await db.craft.create(data={"name": f"RC Craft Two {tag}", "workshopId": workshops[2].id})
            await media("IMAGE", "workshopId", workshops[1].id)
            await media("IMAGE", "workshopId", workshops[2].id)

            # sortOrder is unique: a run killed before its teardown leaves its section behind.
            top = await db.questionnairesection.find_first(order={"sortOrder": "desc"})
            section = await db.questionnairesection.create(
                data={"code": f"RC_{tag}", "title": f"RC {tag}", "sortOrder": (top.sortOrder if top else 0) + 1}
            )
            question = await db.questionnairequestion.create(
                data={
                    "sectionId": section.id, "sectionCode": section.code, "sectionTitle": section.title,
                    "prompt": "Q", "sortOrder": 1,
                }
            )
            ibase = {"createdById": owner.id}
            interviews = [
                await db.questionnaireinterview.create(data={**ibase, "title": f"I0 {tag}", "place": " "}),
                await db.questionnaireinterview.create(data={
                    **ibase, "title": f"I1 {tag}", "interviewDate": "2026-01-01T00:00:00Z", "place": "P",
                    "language": "Hindi",
                }),
            ]
            await db.questionnaireinterviewartisan.create(
                data={"interviewId": interviews[1].id, "artisanId": artisans["complete"].id}
            )
            await db.questionnaireresponse.create(data={
                "interviewId": interviews[1].id, "questionId": question.id, "answerText": "A",
                "answeredById": owner.id,
            })
            await media("VIDEO", "questionnaireInterviewId", interviews[1].id)
            return {
                "tag": tag, "owner": owner, "other": other, "admin": admin, "craft": craft,
                "craft2": craft2, "artisans": {k: v.id for k, v in artisans.items()},
                "ids": {
                    "artisan": [a.id for a in artisans.values()],
                    "product": [p.id for p in products],
                    "tool": [t.id for t in tools],
                    "workshop": [w.id for w in workshops],
                    "interview": [i.id for i in interviews],
                },
                "auth": {
                    "owner": {"Authorization": f"Bearer {create_access_token(owner.id)}"},
                    "other": {"Authorization": f"Bearer {create_access_token(other.id)}"},
                    "admin": {"Authorization": f"Bearer {create_access_token(admin.id)}"},
                },
            }
        finally:
            await db.disconnect()

    seeded = asyncio.run(seed())
    with TestClient(app) as client:
        seeded["client"] = client
        yield seeded


_PATHS = {
    "artisan": "/api/artisans",
    "product": "/api/products",
    "tool": "/api/tools",
    "workshop": "/api/workshops",
    "interview": "/api/questionnaire/interviews",
}


def _list(world: dict[str, Any], kind: str, mode: str | None) -> list[dict[str, Any]]:
    params = {"createdBy": world["owner"].id, "pageSize": 100}
    if mode:
        params["completeness"] = mode
    response = world["client"].get(_PATHS[kind], params=params, headers=world["auth"]["owner"])
    assert response.status_code == 200, response.text
    return response.json()["items"]


@needs_db
@pytest.mark.parametrize("kind", rc.KINDS)
def test_postgres_agrees_with_the_meter_for_every_mode(world, kind):
    everything = _list(world, kind, None)
    ours = {row["id"]: row for row in everything if row["id"] in world["ids"][kind]}
    assert set(ours) == set(world["ids"][kind]), "the seeded rows did not come back"
    for mode in sorted(rc.MODES):
        got = {row["id"] for row in _list(world, kind, mode) if row["id"] in ours}
        want = {i for i, row in ours.items() if _expected(kind, mode, row["completeness"])}
        assert got == want, (kind, mode, got, want)
    # Every kind is seeded with a row that lacks something. (Interview I0's place is a single space:
    # the accepted whitespace edge, harmless here because I0 lacks its date and language too.)
    assert any(row["completeness"]["missing"] for row in ours.values()), kind


@needs_db
def test_the_missing_craft_start_date_is_listed_under_required(world):
    rows = {row["id"]: row for row in _list(world, "artisan", "required")}
    target = world["artisans"]["no_start"]
    assert target in rows
    assert [m["key"] for m in rows[target]["completeness"]["missing"]] == ["craftStartDate", "photo"]
    assert world["artisans"]["complete"] not in rows


@needs_db
def test_a_rejected_image_is_not_a_photo(world):
    response = world["client"].get(
        f"/api/artisans/{world['artisans']['rejected_photo']}", headers=world["auth"]["owner"]
    )
    block = response.json()["completeness"]
    assert [m["key"] for m in block["missing"]] == ["photo"]
    complete = world["client"].get(
        f"/api/artisans/{world['artisans']['complete']}", headers=world["auth"]["owner"]
    ).json()["completeness"]
    assert complete["missing"] == [] and complete["percent"] == 100


@needs_db
def test_workshop_links_count_through_either_source(world):
    for workshop_id in world["ids"]["workshop"][1:]:
        block = world["client"].get(f"/api/workshops/{workshop_id}", headers=world["auth"]["owner"]).json()
        assert block["completeness"]["missing"] == [], block["completeness"]


@needs_db
def test_post_and_patch_answer_with_the_block(world):
    client, auth = world["client"], world["auth"]["admin"]
    created = client.post(
        "/api/workshops",
        json={
            "title": f"Posted {world['tag']}",
            "place": "P",
            "location": LOCATION,
            "date": "2026-01-05T00:00:00Z",
        },
        headers=auth,
    )
    assert created.status_code == 201, created.text
    block = created.json()["completeness"]
    assert {m["key"] for m in block["missing"]} >= {"artisans", "crafts", "photo"}

    patched = client.patch(
        f"/api/artisans/{world['artisans']['no_start']}",
        json={"craftStartDate": "1991-02-03T00:00:00Z"},
        headers=world["auth"]["owner"],
    )
    assert patched.status_code == 200, patched.text
    assert [m["key"] for m in patched.json()["completeness"]["missing"]] == ["photo"]
    # Put it back for the other tests' sake.
    client.patch(
        f"/api/artisans/{world['artisans']['no_start']}",
        json={"craftStartDate": None},
        headers=world["auth"]["owner"],
    )


@needs_db
def test_a_masked_viewer_sees_the_same_block_and_no_values(world):
    path = f"/api/artisans/{world['artisans']['no_start']}"
    mine = world["client"].get(path, headers=world["auth"]["owner"]).json()
    theirs = world["client"].get(path, headers=world["auth"]["other"]).json()
    assert theirs["completeness"] == mine["completeness"]
    assert "9876543210" not in json.dumps(theirs["completeness"])


def test_a_browser_may_read_the_rules_etag():
    """The web revalidates the rules with ``If-None-Match``; hidden from a cross-origin script, the
    tag would be unreadable and every page load would download the table again."""
    from app.main import app as application

    cors = [entry for entry in application.user_middleware if entry.cls.__name__ == "CORSMiddleware"]
    assert cors and "ETag" in cors[0].kwargs["expose_headers"]
