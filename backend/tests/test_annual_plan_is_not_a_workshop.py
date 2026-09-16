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

══ HOW SECTION 2 IS ARRANGED ═════════════════════════════════════════════════════════════════════

Every database call is in ONE SYNC fixture, inside a private ``asyncio.run`` loop, and finishes
before the ``TestClient`` exists; the tests then drive the client and assert over what the fixture
recorded. That is the convention ``tests/test_workshop_join_sync.py`` and
``tests/test_seed_shared_questionnaire.py`` set out, and it is not stylistic: ``db`` is a
process-wide Prisma singleton shared with the running app, and a Prisma connection is bound to the
event loop that opened it. A module-scoped ASYNC fixture opens the connection in one loop and then
starts the app in another, so the app's own handlers fail with ``RuntimeError: … is bound to a
different event loop`` and the log fills with "Unhandled error on POST /api/…" for tests whose
assertions were never reached. ``asyncio.run`` opens a loop nothing else shares and closes it again,
which is why the blind ``connect``/``disconnect`` pair below is correct exactly here.

THE WORKSHOP COUNT IS TAKEN IN THE FIXTURE, AND THAT IS SOUND RATHER THAN CONVENIENT: nothing in
this module creates or deletes a ``DesignWorkshop`` after the seed — every test below is a GET — so
the number the fixture records is still the table's answer when the list is asked for it.
"""

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
#:
#: WHAT PUTS A MODULE IN THIS SET IS THAT IT IS THE ANNUAL PLAN'S OWN, not that its mention looks
#: harmless. All four are this feature: two reach the table, two only spell it. A module from
#: another feature has to earn its way in by needing the TABLE — never merely the NAME.
#:
#: THE DISTINCTION IS NOT PEDANTRY, because the two censuses are not equally strong and this set
#: is what decides which one still covers a module. The strict census matches
#: ``\.annualplanentry\.``, which is how prisma is CALLED — it catches ``db.annualplanentry.…``
#: and misses a raw read, where the table arrives quoted as ``from "AnnualPlanEntry"`` with no dot
#: in front of it. ``db.query_raw`` is ordinary here (``services/usage.py``,
#: ``services/design_workshops.py``, ``services/dictation_consent.py``, and see
#: ``annual_plan.py``'s own note on it), so that gap is a real shape and not a contrived one. The
#: LOOSE census below is the only guard that closes it. Listing a module here therefore hands it
#: BOTH halves: it may say the name, and it may then read the table in raw SQL with the whole
#: suite green. For the annual plan's own modules that is exactly right — a feature reading its
#: own table leaks nothing. For anything else it is the hole this file exists to prevent.
#:
#: PROPOSED AND REJECTED, v0.0.12: ``services/sanction_orders.py``. The sanction register grew an
#: .xlsx importer this release, and with it ``SanctionOrder.sourceFilename``/``sheetRow``; a
#: comment in ``sanction_payload`` credited this table's identically-named pair as the shape it
#: copied, and that comment alone turned the census below red. The mention was prose and nothing
#: else — no query, no include, no import, and no relation between the two models anywhere in
#: ``schema.prisma``. The fix was to rewrite the comment, NOT to widen this set, on three counts.
#:
#: One: the module wanted the PRECEDENT, not the table, and a precedent can be cited by pointing
#: at where it is already written down. It is written down in ``prisma/schema.prisma`` above
#: ``SanctionOrder.sourceFilename``, which ``_modules`` never opens — the rglob is rooted at
#: ``app/``. The citation survives intact at its canonical address; only the duplicate went.
#:
#: Two: the rest of that same release had already proved it costs nothing.
#: ``services/sanction_orders_xlsx.py`` is an avowed near-copy of ``annual_plan_xlsx`` and names
#: the annual plan roughly twenty times — its caps, its colour, its date reader, which of its
#: machinery was deliberately not copied — and ``services/sanction_import.py`` argues against its
#: transaction shape by name. Neither has ever appeared in this census, because neither needed to
#: say ``AnnualPlanEntry`` to say any of it. One comment in a third file was the outlier.
#:
#: Three: this is the module where the hole above would most likely be used. "Which planned
#: workshop was this order raised against" is a plausible next request for the officer's register,
#: and the honest way to build it is the promotion pointer — ``AnnualPlanEntry.designWorkshopId``
#: to the workshop the order already names — read through ``services/annual_plan.py``. Granting
#: this module the name in advance, for a comment, would have retired the guard that makes anyone
#: stop and choose. THE DAY AN ORDER GENUINELY NEEDS A PLAN ROW, ADD IT HERE AND SAY SO; a
#: citation is not that day.
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
def world():
    """A master admin, one REAL workshop, and three hundred planned rows that are not workshops.

    SYNC, and every write inside ``asyncio.run`` before the client exists — see the module
    docstring. ``live_workshops`` is the workshop table's own answer, recorded here so that the
    tests below can compare a LIST TOTAL against it without reaching for ``db`` while the app is
    running and holding a connection bound to a different loop.
    """
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
            # READ LAST, with all three hundred plan rows already in the table: the number the
            # workshop table answers while the plan table is full is exactly what the list's total
            # has to match.
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
    """The whole suite in one assertion: the list's total is the workshop table's, not the sum."""
    listed = client.get("/api/design-workshops", headers=_headers(world)).json()
    assert listed["total"] == world["live_workshops"]


@needs_db
def test_a_planned_row_id_is_not_a_workshop_id(world, client) -> None:
    """404 ``"Record not found"``, byte-identical to an id that does not exist. Anything else tells
    the caller that the id they have is a real row in some other table."""
    response = client.get(f"/api/design-workshops/{world['entry'].id}", headers=_headers(world))
    assert response.status_code == 404
    assert response.json()["detail"] == "Record not found"


@needs_db
def test_no_dataset_streams_a_planned_row(world, client) -> None:
    """Every entry in the dataset registry, checked. One that streamed plan rows would put them in a
    downloaded file labelled as fieldwork, where nothing distinguishes them."""
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
