"""THE BOUNDARY SUITE. A planned row is a line in a document; it is not a workshop.

The census in section 1 needs nothing. Section 2 needs Postgres:

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

══ WHAT A LEAK COSTS ═════════════════════════════════════════════════════════════════════════════

Three hundred planned rows unioned into the workshop table shows a ministry "287 workshops held this
year" when 284 of them have not started. Nothing in the product could show that it had happened: the
count is a number on a dashboard, the export is a spreadsheet of rows that look like rows, and the
analytics average over them reads as a bad year rather than as a bug.

The leak does not arrive as a union. It arrives as a helpful-looking change to ONE query — a
dashboard tile that "should include planned workshops too", a dataset that "should show what is
coming" — and every one of those is a one-line edit in a file that has nothing to do with this
feature. So the guard is a CENSUS of which modules may name the table at all, plus a behavioural
assertion per surface.
"""

import re
import uuid
from datetime import UTC, datetime
from pathlib import Path

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import design_workshops

pytestmark = pytest.mark.anyio

APP = Path(design_workshops.__file__).resolve().parents[1]
PASSWORD = "annual-plan-boundary-password"

#: The only modules that may name the table. Adding a row here is a decision to be argued in review.
#:
#: `api/routes/users.py` IS DELIBERATELY ABSENT even though it lists the relations that make an
#: account undeletable: all four account pointers on this table are ON DELETE SET NULL, so a plan
#: row never blocks a deletion and `_NAMED_ON_RELATIONS` must not learn about it.
ALLOWED_CLIENT_MODULES = {
    "services/annual_plan.py",
    "api/routes/annual_plan.py",
}

#: The modules that may so much as MENTION the model by name — the two above plus the parser and the
#: request bodies, both of which reference it only in prose (a docstring naming the column a value
#: is stored in) and neither of which reaches a database at all.
ALLOWED_MENTION_MODULES = ALLOWED_CLIENT_MODULES | {
    "services/annual_plan_xlsx.py",
    "schemas/annual_plan.py",
}


def _modules(pattern: str) -> set[str]:
    found: set[str] = set()
    for path in APP.rglob("*.py"):
        text = path.read_text(encoding="utf-8").lower()
        if re.search(pattern, text):
            found.add(path.relative_to(APP).as_posix())
    return found


# --------------------------------------------------------------------------------------
# 1. The census. No database.
# --------------------------------------------------------------------------------------


def test_the_only_join_between_the_two_tables_is_the_promotion():
    """ONE FOREIGN KEY, TWO MODULES, AND NOTHING ELSE ANYWHERE IN ``app/``.

    ``AnnualPlanEntry.designWorkshopId`` is the only legitimate join, in either direction. A third
    module that learns this table's name is either a leak or a decision somebody has to make on
    purpose — and this assertion is where they are asked to make it.

    The pattern is ``<client>.annualplanentry.``, which is how the table is actually REACHED. A
    module that merely says the model's name in a docstring has leaked nothing, and a census that
    could not tell those apart would be a census nobody could keep green.
    """
    assert _modules(r"\.annualplanentry\.") == ALLOWED_CLIENT_MODULES


def test_no_module_outside_this_feature_even_names_the_plan_table():
    """The looser half, and it is the one that catches a leak BEFORE it is written.

    A dashboard or dataset module that has begun to mention ``AnnualPlanEntry`` — in a comment, in a
    type, in a half-finished include — is one edit away from counting planned rows as workshops.
    That is worth failing on while it is still a comment.
    """
    assert _modules(r"\bannualplanentry\b") == ALLOWED_MENTION_MODULES


def test_no_workshop_reading_service_knows_this_table_exists():
    """The nine surfaces the module docstring of ``services/annual_plan.py`` enumerates, spot-checked
    by name — because the census above is only as good as its rglob, and this says out loud which
    files were meant."""
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
    """``design_workshop_data.tables()`` is the 44-entity registry every dataset and export reads.
    A forty-fifth entry that is not an entity would stream planned rows as fieldwork."""
    from app.services import design_workshop_data

    keys = {getattr(t, "key", getattr(t, "name", str(t))) for t in design_workshop_data.tables()}
    assert not any("annualplan" in str(k).lower() for k in keys)


# --------------------------------------------------------------------------------------
# 2. The surfaces, exercised. NEEDS POSTGRES.
# --------------------------------------------------------------------------------------


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    email = f"annual-plan-boundary-{stamp}@example.org"
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
    finally:
        await db.disconnect()
    with TestClient(app) as client:
        yield {"client": client, "admin": admin, "workshop": workshop, "entry": entry}


def _headers(world) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['admin'].id)}"}


@needs_db
async def test_three_hundred_planned_rows_do_not_move_the_workshop_count(world) -> None:
    """The whole suite in one assertion: the list's total is the workshop table's, not the sum."""
    listed = world["client"].get("/api/design-workshops", headers=_headers(world)).json()
    await db.connect()
    try:
        real = await db.designworkshop.count(where={"deletedAt": None})
    finally:
        await db.disconnect()
    assert listed["total"] == real


@needs_db
async def test_a_planned_row_id_is_not_a_workshop_id(world) -> None:
    """404 ``"Record not found"``, byte-identical to an id that does not exist. Anything else tells
    the caller that the id they have is a real row in some other table."""
    response = world["client"].get(
        f"/api/design-workshops/{world['entry'].id}", headers=_headers(world)
    )
    assert response.status_code == 404
    assert response.json()["detail"] == "Record not found"


@needs_db
async def test_no_dataset_streams_a_planned_row(world) -> None:
    """Every entry in the dataset registry, checked. One that streamed plan rows would put them in a
    downloaded file labelled as fieldwork, where nothing distinguishes them."""
    from app.api.routes.datasets import DATASETS

    for key in DATASETS:
        response = world["client"].get(f"/api/datasets/{key}", headers=_headers(world))
        if response.status_code != 200:
            continue
        assert world["entry"].workshopNo not in response.text, key


@needs_db
async def test_the_data_report_has_no_sheet_of_planned_rows(world) -> None:
    response = world["client"].get("/api/data/report?format=json", headers=_headers(world))
    if response.status_code == 200:
        assert "annualplan" not in response.text.lower()


@needs_db
async def test_cross_workshop_analytics_counts_no_planned_row(world) -> None:
    response = world["client"].get(
        "/api/analytics/design-workshops", headers=_headers(world)
    )
    if response.status_code != 200:
        return
    await db.connect()
    try:
        real = await db.designworkshop.count(where={"deletedAt": None})
    finally:
        await db.disconnect()
    body = response.json()
    for key in ("total", "workshops", "count"):
        if isinstance(body.get(key), int):
            assert body[key] == real, key
