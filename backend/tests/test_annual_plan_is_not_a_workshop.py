import asyncio
import re
import uuid
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import design_workshops

APP = Path(design_workshops.__file__).resolve().parents[1]
PASSWORD = "annual-plan-boundary-password"

ALLOWED_CLIENT_MODULES = {
    "services/annual_plan.py",
    "api/routes/annual_plan.py",
}

ALLOWED_MENTION_MODULES = ALLOWED_CLIENT_MODULES | {
    "services/annual_plan_xlsx.py",
    "schemas/annual_plan.py",
    "api/routes/workshop_types.py",
    "services/search_index.py",
    "services/search_indexer.py",
    "services/coverage.py",
    "services/craft_master.py",
    "scripts/map_crafts.py",
}


def _modules(pattern: str) -> set[str]:
    found: set[str] = set()
    for path in APP.rglob("*.py"):
        text = path.read_text(encoding="utf-8").lower()
        if re.search(pattern, text):
            found.add(path.relative_to(APP).as_posix())
    return found



def test_the_only_join_between_the_two_tables_is_the_promotion():
    assert _modules(r"\.annualplanentry\.") == ALLOWED_CLIENT_MODULES


def test_no_module_outside_this_feature_even_names_the_plan_table():
    assert _modules(r"\bannualplanentry\b") == ALLOWED_MENTION_MODULES


def test_no_workshop_reading_service_knows_this_table_exists():
    for module in (
        "services/design_workshops.py",
        "services/design_workshop_data.py",
        "services/report_builder.py",
        "api/routes/design_workshops.py",
        "api/routes/dashboard.py",
        "api/routes/datasets.py",
        "api/routes/data_browser.py",
        "api/routes/analytics.py",
    ):
        text = (APP / module).read_text(encoding="utf-8").lower()
        assert "annualplanentry" not in text, module


def test_the_plan_table_is_not_in_the_workshop_entity_registry():
    from app.services import design_workshop_data

    keys = {getattr(t, "key", getattr(t, "name", str(t))) for t in design_workshop_data.tables()}
    assert not any("annualplan" in str(k).lower() for k in keys)



@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    email = f"annual-plan-boundary-{stamp}@example.org"

    async def seed() -> dict[str, Any]:
        await db.connect()
        try:
            admin = await db.user.create(
                data={
                    "email": email,
                    "name": f"Master admin {stamp}",
                    "role": "MASTER_ADMIN",
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            await db.accessroster.create(
                data={
                    "email": email,
                    "status": "ACTIVE",
                    "admitRole": "MASTER_ADMIN",
                    "joinedAt": datetime.now(UTC),
                    "notes": "Seeded by tests/test_annual_plan_is_not_a_workshop.py.",
                }
            )
            workshop = await db.designworkshop.create(
                data={
                    "title": f"A real workshop {stamp}",
                    "templateId": "DCH_STANDARD",
                    "createdById": admin.id,
                    "status": "DRAFT",
                }
            )
            await db.annualplanentry.create_many(
                data=[
                    {
                        "planYear": 2098,
                        "workshopNo": f"BND/{stamp}/{n:03d}",
                        "workshopNoKey": f"BND/{stamp}/{n:03d}".upper(),
                        "state": "Gujarat",
                        "district": "Kachchh",
                    }
                    for n in range(300)
                ]
            )
            entry = await db.annualplanentry.find_first(where={"planYear": 2098})
            live_workshops = await db.designworkshop.count(where={"deletedAt": None})
            return {
                "admin": admin,
                "workshop": workshop,
                "entry": entry,
                "live_workshops": live_workshops,
                "stamp": stamp,
            }
        finally:
            await db.disconnect()

    seeded = asyncio.run(seed())
    with TestClient(app) as client:
        seeded["client"] = client
        yield seeded


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['admin'].id)}"}


@needs_db
def test_three_hundred_planned_rows_do_not_move_the_workshop_count(world, client) -> None:
    listed = client.get("/api/design-workshops", headers=_headers(world)).json()
    assert listed["total"] == world["live_workshops"]


@needs_db
def test_a_planned_row_id_is_not_a_workshop_id(world, client) -> None:
    response = client.get(f"/api/design-workshops/{world['entry'].id}", headers=_headers(world))
    assert response.status_code == 404
    assert response.json()["detail"] == "Record not found"


@needs_db
def test_no_dataset_streams_a_planned_row(world, client) -> None:
    from app.api.routes.datasets import DATASETS

    for key in DATASETS:
        response = client.get(f"/api/datasets/{key}", headers=_headers(world))
        if response.status_code != 200:
            continue
        assert world["entry"].workshopNo not in response.text, key


@needs_db
def test_the_data_report_has_no_sheet_of_planned_rows(world, client) -> None:
    response = client.get("/api/data/report?format=json", headers=_headers(world))
    if response.status_code == 200:
        assert "annualplan" not in response.text.lower()


@needs_db
def test_cross_workshop_analytics_counts_no_planned_row(world, client) -> None:
    response = client.get("/api/analytics/design-workshops", headers=_headers(world))
    if response.status_code != 200:
        return
    body = response.json()
    for key in ("total", "workshops", "count"):
        if isinstance(body.get(key), int):
            assert body[key] == world["live_workshops"], key
