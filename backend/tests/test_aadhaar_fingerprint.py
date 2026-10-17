"""The artisan register keeps a keyed fingerprint and the last four digits, never the Aadhaar number.

Owner decision #2 (2026-10-17). What is pinned here:

* the fingerprint is deterministic, keyed, and computed over the NORMALISED number only — so one
  person typed two ways is one fingerprint, and the formula is byte for byte the cross-portal code;
* duplicates are still found: by fingerprint, and by the legacy column for rows the backfill has
  not reached yet, on create, on edit and in the mid-form lookup (POST and the old GET);
* no response, export, edit-history entry or log line carries the full number or the fingerprint;
* the backfill is a dry run by default, idempotent, verifies what it wrote, and scrubs old
  edit-history values; the deferred drop migration refuses until it has finished.
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import logging
import random
import re
import uuid
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from conftest import TEST_AADHAAR_FINGERPRINT_KEY, needs_db

from app.services import aadhaar_fingerprint as fp, record_fields
from app.services.artisan_identity import validate_aadhaar, verhoeff_ok

KEY = TEST_AADHAAR_FINGERPRINT_KEY
BACKEND = Path(__file__).resolve().parents[1]
DROP_MIGRATION = (
    BACKEND / "prisma" / "deferred_migrations" / "20261029090000_drop_aadhaar_number" / "migration.sql"
)


def _fresh_aadhaar() -> str:
    """A Verhoeff-valid number nobody in a shared database is likely to hold already."""
    while True:
        stem = str(random.randint(2, 9)) + "".join(random.choices("0123456789", k=10))
        for last in "0123456789":
            if verhoeff_ok(stem + last):
                return stem + last


def _settings(key: str | None = KEY) -> SimpleNamespace:
    return SimpleNamespace(aadhaar_fingerprint_key=key)


# ── The fingerprint itself ──────────────────────────────────────────────────────────────────────


def test_the_fingerprint_is_hmac_sha256_of_the_twelve_digits_in_lower_case_hex() -> None:
    number = _fresh_aadhaar()
    expected = hmac.new(KEY.encode(), number.encode(), hashlib.sha256).hexdigest()
    assert fp.fingerprint(number, KEY) == expected
    assert fp.fingerprint(number, KEY) == fp.fingerprint(number, KEY)
    assert re.fullmatch(r"[0-9a-f]{64}", expected)
    assert fp.is_fingerprint(expected)


def test_the_formula_is_the_cross_portal_code_byte_for_byte() -> None:
    """``services/cross_portal.aadhaar_code`` on feature/s-cross-portal: key stripped, UTF-8, the
    digits as the message. With one shared key a stored fingerprint IS the code the other portal
    sends, so the link can match on the column."""
    number = _fresh_aadhaar()
    cross_portal_code = hmac.new(
        f"  {KEY}\n".strip().encode("utf-8"), number.encode("ascii"), hashlib.sha256
    ).hexdigest()
    assert fp.fingerprint(number, f"  {KEY}\n") == cross_portal_code


def test_a_different_key_gives_a_different_fingerprint() -> None:
    number = _fresh_aadhaar()
    assert fp.fingerprint(number, KEY) != fp.fingerprint(number, KEY + "-other")


@pytest.mark.parametrize(
    "spelling",
    ["{a} {b} {c}", "{a}-{b}-{c}", " {a}{b}{c} ", "{a}–{b}–{c}"],
)
def test_every_spelling_of_one_number_is_one_fingerprint(spelling: str) -> None:
    number = _fresh_aadhaar()
    typed = spelling.format(a=number[:4], b=number[4:8], c=number[8:])
    assert fp.fingerprint(validate_aadhaar(typed), KEY) == fp.fingerprint(number, KEY)


@pytest.mark.parametrize(
    "raw", ["1234 5678 9012", "12345678901", "１２３４５６７８９０１２", "XXXX XXXX 9012", "", None]
)
def test_only_the_normalised_number_is_ever_fingerprinted(raw: Any) -> None:
    with pytest.raises(ValueError):
        fp.fingerprint(raw, KEY)


def test_no_key_means_nothing_is_stored_or_matched() -> None:
    for key in (None, "", "short"):
        with pytest.raises(fp.FingerprintKeyMissing):
            fp.configured_key(_settings(key))
        with pytest.raises(fp.FingerprintKeyMissing):
            fp.seal({"aadhaarNumber": _fresh_aadhaar()}, _settings(key))


# ── seal: the write side ────────────────────────────────────────────────────────────────────────


def test_seal_replaces_the_number_with_its_fingerprint_and_last_four() -> None:
    number = _fresh_aadhaar()
    data: dict[str, Any] = {"aadhaarNumber": number, "name": "Kalu Ram"}
    assert fp.seal(data, _settings()) == number
    assert data["aadhaarFingerprint"] == fp.fingerprint(number, KEY)
    assert data["aadhaarLast4"] == number[-4:]
    # The legacy column is written only to clear it.
    assert data["aadhaarNumber"] is None
    assert number not in repr(data)


def test_seal_leaves_a_payload_without_the_number_alone() -> None:
    data: dict[str, Any] = {"phone": "9876543210"}
    assert fp.seal(data, _settings()) is None
    assert data == {"phone": "9876543210"}


def test_seal_retracts_on_an_explicit_null() -> None:
    data: dict[str, Any] = {"aadhaarNumber": None}
    fp.seal(data, _settings())
    assert data == {"aadhaarNumber": None, "aadhaarFingerprint": None, "aadhaarLast4": None}


def test_re_sending_the_stored_number_changes_nothing() -> None:
    number = _fresh_aadhaar()
    stored = SimpleNamespace(
        aadhaarFingerprint=fp.fingerprint(number, KEY), aadhaarLast4=number[-4:], aadhaarNumber=None
    )
    data: dict[str, Any] = {"aadhaarNumber": number, "phone": "1"}
    fp.seal(data, _settings(), previous=stored)
    assert data == {"phone": "1"}


def test_a_legacy_row_re_sending_its_own_number_gains_the_columns_without_a_retraction() -> None:
    number = _fresh_aadhaar()
    legacy = SimpleNamespace(aadhaarFingerprint=None, aadhaarLast4=None, aadhaarNumber=number)
    data: dict[str, Any] = {"aadhaarNumber": number}
    fp.seal(data, _settings(), previous=legacy)
    assert data == {"aadhaarFingerprint": fp.fingerprint(number, KEY), "aadhaarLast4": number[-4:]}


def test_the_lookup_matches_the_fingerprint_and_the_legacy_column() -> None:
    number = _fresh_aadhaar()
    where = fp.match_where(number, _settings())
    assert {"aadhaarFingerprint": fp.fingerprint(number, KEY)} in where["OR"]
    assert {"aadhaarNumber": number} in where["OR"]


# ── The read side ───────────────────────────────────────────────────────────────────────────────


def test_an_encoded_artisan_carries_the_mask_and_never_the_fingerprint() -> None:
    from app.services.records import public_encode

    number = _fresh_aadhaar()
    row = {
        "id": "a1",
        "createdById": "viewer",
        "aadhaarNumber": None,
        "aadhaarFingerprint": fp.fingerprint(number, KEY),
        "aadhaarLast4": number[-4:],
    }
    admin = SimpleNamespace(id="viewer", role="MASTER_ADMIN")
    out = public_encode({"artisan": row}, admin)["artisan"]
    assert out["aadhaarNumber"] == f"XXXX XXXX {number[-4:]}"
    assert "aadhaarFingerprint" not in out
    # A legacy row (before the backfill) is masked too, even for its own creator and an admin.
    legacy = {"id": "a2", "createdById": "viewer", "aadhaarNumber": number, "aadhaarLast4": None}
    out = public_encode([legacy], admin)[0]
    assert out["aadhaarNumber"] == f"XXXX XXXX {number[-4:]}"


def test_every_export_surface_prints_the_mask_from_the_last_four() -> None:
    number = _fresh_aadhaar()
    artisan = SimpleNamespace(
        id="a1",
        name="Kalu Ram",
        localName=None,
        craft=SimpleNamespace(name="Bagru Hand Block Printing"),
        location=None,
        workshops=[],
        workshopId=None,
        designWorkshopId=None,
        place="Bagru",
        address=None,
        phone=None,
        email=None,
        aadhaarNumber=None,
        aadhaarFingerprint=fp.fingerprint(number, KEY),
        aadhaarLast4=number[-4:],
        pehchanCardAvailable=False,
        pehchanCardNumber=None,
        gender=None,
        dos="d",
        donts="d",
        notes=None,
        extraMetadata={},
        status="APPROVED",
        createdById="owner",
        createdBy=SimpleNamespace(name="Owner"),
        createdAt=None,
    )
    panel = record_fields.info_panel("artisan", artisan)
    values = {field["label"]: field["value"] for field in panel["fields"]}
    assert values["Aadhaar number"] == f"XXXX XXXX {number[-4:]}"
    rendered = record_fields.info_text(panel) + " | ".join(record_fields.sheet_row("artisan", artisan))
    assert number not in rendered
    assert artisan.aadhaarFingerprint not in rendered


def test_the_edit_history_records_one_valueless_aadhaar_change() -> None:
    from app.services.access import record_revision

    written: list[dict[str, Any]] = []

    class _Writer:
        class recordrevision:
            @staticmethod
            async def create(data: dict[str, Any]) -> None:
                written.append(data)

    number, other = _fresh_aadhaar(), _fresh_aadhaar()
    stored = SimpleNamespace(
        id="a1",
        aadhaarNumber=None,
        aadhaarFingerprint=fp.fingerprint(number, KEY),
        aadhaarLast4=number[-4:],
    )
    data: dict[str, Any] = {"aadhaarNumber": other}
    fp.seal(data, _settings(), previous=stored)
    asyncio.run(record_revision(stored, SimpleNamespace(id="u1"), data, "artisan", client=_Writer))
    changes = written[0]["changes"].data
    assert set(changes) == {"aadhaarNumber"}
    assert changes["aadhaarNumber"]["redacted"] is True
    assert (changes["aadhaarNumber"]["old"], changes["aadhaarNumber"]["new"]) == (
        "(value recorded)",
        "(value replaced)",
    )
    text = repr(written)
    assert number not in text and other not in text and data["aadhaarFingerprint"] not in text


@pytest.mark.parametrize("sep", ["", "%20", "+", "-"])
def test_an_aadhaar_in_a_logged_query_string_is_redacted(sep: str) -> None:
    """Old app builds send the lookup as ``GET ...?number=``; uvicorn's access line is redacted."""
    import logging as _logging

    number = _fresh_aadhaar()
    typed = sep.join((number[:4], number[4:8], number[8:]))
    path = f"/api/artisans/lookup/aadhaar?number={typed}&page=2"
    assert fp.redact_path(path) == "/api/artisans/lookup/aadhaar?number=[redacted]&page=2"
    record = _logging.LogRecord(
        "uvicorn.access", _logging.INFO, __file__, 1, '%s - "%s %s HTTP/%s" %d',
        ("1.2.3.4:5", "GET", path, "1.1", 200), None,
    )
    assert fp.AccessLogRedaction().filter(record) is True
    assert typed not in record.getMessage() and number not in record.getMessage()
    assert "[redacted]" in record.getMessage()
    # A path without a query, or a query with no Aadhaar-shaped value, is left alone.
    assert fp.redact_path("/api/artisans?page=2&pageSize=20") == "/api/artisans?page=2&pageSize=20"
    assert fp.redact_path("/api/artisans/abc") == "/api/artisans/abc"


def test_the_drop_migration_is_deferred_and_guarded() -> None:
    """Shipped in prisma/migrations it would run at deploy, before the backfill, and fail it."""
    assert not (BACKEND / "prisma" / "migrations" / DROP_MIGRATION.parent.name).exists()
    sql = DROP_MIGRATION.read_text(encoding="utf-8")
    guard_at = sql.index("RAISE EXCEPTION")
    assert guard_at < sql.index('UPDATE "Artisan" SET "aadhaarNumber" = NULL')
    assert guard_at < sql.index('DROP COLUMN "aadhaarNumber"')
    assert fp.LEGACY_COLUMN_PRESENT, "flip this only in the commit that ships the drop migration"


# ── Against a database ──────────────────────────────────────────────────────────────────────────

LOCATION = {"latitude": 26.82, "longitude": 75.54, "state": "Rajasthan", "district": "Jaipur"}


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
            people = {}
            for slug, role in (("designer", "DESIGNER"), ("admin", "ADMIN")):
                people[slug] = await db.user.create(
                    data={
                        "email": f"aadhaar-fp-{slug}-{tag}@example.org",
                        "name": f"Fingerprint {slug} {tag}",
                        "role": role,
                        "passwordHash": hash_password("aadhaar-fingerprint-pass"),
                    }
                )
            craft = await db.craft.create(data={"name": f"Fingerprint ikat {tag}"})
            return {"people": people, "craft": craft.id, "tag": tag}
        finally:
            await db.disconnect()

    seeded = asyncio.run(seed())
    seeded["headers"] = {
        slug: {"Authorization": f"Bearer {create_access_token(user.id)}"}
        for slug, user in seeded["people"].items()
    }
    with TestClient(app) as client:
        seeded["client"] = client
        yield seeded


def _body(world: dict[str, Any], number: str, **extra: Any) -> dict[str, Any]:
    return {
        "name": f"Kalu Ram {world['tag']}",
        "place": "Bagru",
        "aadhaarNumber": number,
        "dos": "Greet the artisan first.",
        "donts": "Do not photograph without asking.",
        "craftId": world["craft"],
        "location": LOCATION,
        **extra,
    }


def _client() -> Any:
    """A Prisma client of the test's own. The app under TestClient holds ``app.core.db.db`` connected
    on its own event loop, so a test reading a column the API never serves needs a second one."""
    from prisma import Prisma

    return Prisma()


async def _row(artisan_id: str) -> Any:
    db = _client()
    await db.connect()
    try:
        return await db.artisan.find_unique(where={"id": artisan_id})
    finally:
        await db.disconnect()


def _no_secret_in(text: str, number: str) -> None:
    assert number not in text
    assert fp.fingerprint(number, KEY) not in text
    assert "aadhaarFingerprint" not in text


@needs_db
def test_the_register_stores_a_fingerprint_finds_duplicates_and_leaks_nothing(world, caplog) -> None:
    client, designer, admin = world["client"], world["headers"]["designer"], world["headers"]["admin"]
    # A shared test database can hold legacy rows other suites wrote; with none left (as on a box
    # after the backfill) the typed number never travels to the database at all, not even in a WHERE.
    asyncio.run(_backfill(None, apply=True, retract=True))
    fp.reset_legacy_pending()
    caplog.set_level(logging.DEBUG)
    number = _fresh_aadhaar()
    spaced = f"{number[:4]} {number[4:8]} {number[8:]}"

    created = client.post("/api/artisans", json=_body(world, spaced), headers=designer)
    assert created.status_code == 201, created.text
    artisan_id = created.json()["id"]
    assert created.json()["aadhaarNumber"] == f"XXXX XXXX {number[-4:]}"
    _no_secret_in(created.text, number)

    row = asyncio.run(_row(artisan_id))
    assert row.aadhaarNumber is None
    assert row.aadhaarFingerprint == fp.fingerprint(number, KEY)
    assert row.aadhaarLast4 == number[-4:]

    # The same person typed without spaces is a duplicate, and the 409 still names the Aadhaar box.
    again = client.post("/api/artisans", json=_body(world, number), headers=designer)
    assert again.status_code == 409, again.text
    detail = again.json()["detail"]
    assert detail["field"] == "aadhaarNumber"
    assert detail["existingArtisan"]["id"] == artisan_id
    assert detail["maskedValue"] == f"XXXX XXXX {number[-4:]}"
    _no_secret_in(again.text, number)

    # The mid-form lookup, in the body (new clients) and in the query (builds in the field).
    found = client.post("/api/artisans/lookup/aadhaar", json={"number": spaced}, headers=designer)
    assert found.json()["found"] is True and found.json()["artisan"]["id"] == artisan_id
    old = client.get("/api/artisans/lookup/aadhaar", params={"number": number}, headers=designer)
    assert old.json()["found"] is True
    other = client.post(
        "/api/artisans/lookup/aadhaar", json={"number": _fresh_aadhaar()}, headers=designer
    )
    assert other.json() == {"found": False, "artisan": None}

    # Every read surface, for the creator and for an admin: the mask, never the number.
    surfaces = [
        client.get(f"/api/artisans/{artisan_id}", headers=designer),
        client.get(f"/api/artisans/{artisan_id}", headers=admin),
        client.get("/api/artisans", params={"createdBy": world["people"]["designer"].id}, headers=admin),
        client.get("/api/search", params={"q": world["tag"]}, headers=admin),
        client.get("/api/export/artisans.csv", headers=admin),
    ]
    for response in surfaces:
        assert response.status_code == 200, (response.request.url, response.text[:300])
        _no_secret_in(response.text, number)

    # An edit that re-sends the mask changes nothing; a new number replaces the fingerprint.
    unchanged = client.patch(
        f"/api/artisans/{artisan_id}",
        json={"aadhaarNumber": f"XXXX XXXX {number[-4:]}", "phone": "9876543210"},
        headers=designer,
    )
    assert unchanged.status_code == 200, unchanged.text
    assert asyncio.run(_row(artisan_id)).aadhaarFingerprint == fp.fingerprint(number, KEY)
    replacement = _fresh_aadhaar()
    edited = client.post(
        f"/api/review/artisan/{artisan_id}/edit",
        json={"fields": {"aadhaarNumber": replacement}},
        headers=admin,
    )
    assert edited.status_code == 200, edited.text
    _no_secret_in(edited.text, replacement)
    row = asyncio.run(_row(artisan_id))
    assert row.aadhaarFingerprint == fp.fingerprint(replacement, KEY)
    assert row.aadhaarLast4 == replacement[-4:] and row.aadhaarNumber is None

    history = client.get(
        "/api/data-access/revisions",
        params={"recordType": "artisan", "recordId": artisan_id},
        headers=admin,
    )
    assert history.status_code == 200, history.text
    _no_secret_in(history.text, number)
    _no_secret_in(history.text, replacement)

    # Nothing above wrote either number to a log line, at any level, from any logger — the Prisma
    # engine's DEBUG trace of every query included. The fingerprint appears only in that trace (off
    # in every deployment); no application logger writes it.
    # The one line that carries a number is the TEST CLIENT's own record of the old GET it sent; on
    # the server, uvicorn's access line for that request is redacted (aadhaar_fingerprint.redact_path).
    leaks = [r for r in caplog.records if number in r.getMessage() or replacement in r.getMessage()]
    assert all(
        r.name.startswith("httpx") and "lookup/aadhaar?number=" in r.getMessage() for r in leaks
    ), [r.name for r in leaks]
    ours = " ".join(
        r.getMessage() for r in caplog.records if not r.name.startswith(("prisma", "httpx", "httpcore"))
    )
    _no_secret_in(ours, number)
    _no_secret_in(ours, replacement)


async def _legacy(world: dict[str, Any], number: str, *, history_value: str | None = None) -> str:
    """An artisan as production holds it before the backfill: the number in the legacy column."""
    from prisma import Json

    db = _client()

    await db.connect()
    try:
        row = await db.artisan.create(
            data={
                "name": f"Legacy {world['tag']}",
                "place": "Bagru",
                "aadhaarNumber": number,
                "createdById": world["people"]["designer"].id,
            }
        )
        if history_value:
            await db.recordrevision.create(
                data={
                    "recordType": "artisan",
                    "recordId": row.id,
                    "changes": Json({"aadhaarNumber": {"old": history_value, "new": number}}),
                }
            )
        return row.id
    finally:
        await db.disconnect()


async def _backfill(ids: list[str] | None, *, apply: bool, retract: bool = False) -> Any:
    import scripts.backfill_aadhaar_fingerprint as backfill

    db = _client()
    await db.connect()
    real, backfill.db = backfill.db, db
    try:
        return await backfill.run(apply=apply, retract_unusable=retract, only_ids=ids, batch=1)
    finally:
        backfill.db = real
        await db.disconnect()


async def _drop_guard_refuses() -> bool:
    """Run the drop migration's guard (its first statement) and report whether it refused."""
    guard = DROP_MIGRATION.read_text(encoding="utf-8")
    guard = guard[guard.index("DO $$") : guard.index("END $$;") + len("END $$;")]
    db = _client()
    await db.connect()
    try:
        await db.execute_raw(guard)
        return False
    except Exception as exc:  # noqa: BLE001 - the guard's RAISE arrives as whatever Prisma wraps it in
        assert "Refusing to drop" in str(exc)
        return True
    finally:
        await db.disconnect()


@needs_db
def test_the_backfill_is_a_dry_run_by_default_idempotent_and_unlocks_the_drop(world) -> None:
    client, designer = world["client"], world["headers"]["designer"]
    number, older = _fresh_aadhaar(), _fresh_aadhaar()
    artisan_id = asyncio.run(_legacy(world, number, history_value=older))
    fp.reset_legacy_pending()

    # Before the backfill the legacy row is still a duplicate, and is served masked.
    clash = client.post("/api/artisans", json=_body(world, number), headers=designer)
    assert clash.status_code == 409 and clash.json()["detail"]["existingArtisan"]["id"] == artisan_id
    shown = client.get(f"/api/artisans/{artisan_id}", headers=world["headers"]["admin"])
    assert shown.json()["aadhaarNumber"] == f"XXXX XXXX {number[-4:]}"
    _no_secret_in(shown.text, number)
    assert asyncio.run(_drop_guard_refuses())

    dry = asyncio.run(_backfill([artisan_id], apply=False))
    assert (dry.would_fill, dry.filled, dry.revisions_would_scrub) == (1, 0, 1)
    assert asyncio.run(_row(artisan_id)).aadhaarFingerprint is None, "a dry run wrote"

    applied = asyncio.run(_backfill([artisan_id], apply=True))
    assert (applied.filled, applied.verified, applied.revisions_scrubbed) == (1, 1, 1)
    assert applied.unfinished == 0
    row = asyncio.run(_row(artisan_id))
    assert row.aadhaarFingerprint == fp.fingerprint(number, KEY)
    assert row.aadhaarLast4 == number[-4:]

    again = asyncio.run(_backfill([artisan_id], apply=True))
    assert (again.filled, again.done_already, again.revisions_scrubbed) == (0, 1, 0)

    history = client.get(
        "/api/data-access/revisions",
        params={"recordType": "artisan", "recordId": artisan_id},
        headers=world["headers"]["admin"],
    )
    _no_secret_in(history.text, number)
    _no_secret_in(history.text, older)
    # The guard is global, and other suites leave legacy rows in a shared test database, so the
    # whole table is backfilled (unusable test values retracted) before the guard is asked again.
    everything = asyncio.run(_backfill(None, apply=True, retract=True))
    assert everything.unfinished == 0, everything.lines(True)
    assert not asyncio.run(_drop_guard_refuses())

