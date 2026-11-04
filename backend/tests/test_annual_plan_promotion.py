import ast
import asyncio
import inspect
import textwrap
import uuid
from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.schemas.annual_plan import AnnualPlanPromoteRequest
from app.schemas.design_workshop_viewers import MAX_DESIGN_WORKSHOP_VIEWERS
from app.schemas.design_workshops import DesignWorkshopCreate
from app.services import annual_plan
from app.services.annual_plan_xlsx import _CAP_MIRRORS, _TEXT_CAPS
from app.services.stage_schema import PROMOTED_COLUMNS, stages

PASSWORD = "annual-plan-promotion-password"


def _literal_keys(source: str, name: str) -> list[str]:
    tree = ast.parse(textwrap.dedent(source))
    found: list[str] = []

    def _collect(value: ast.expr) -> None:
        for literal in ast.walk(value):
            if isinstance(literal, ast.Tuple) and literal.elts:
                first = literal.elts[0]
                if isinstance(first, ast.Constant) and isinstance(first.value, str):
                    found.append(first.value)

    class _Visitor(ast.NodeVisitor):
        def visit_Assign(self, node: ast.Assign) -> None:
            if name in [t.id for t in node.targets if isinstance(t, ast.Name)]:
                _collect(node.value)
            self.generic_visit(node)

        def visit_AnnAssign(self, node: ast.AnnAssign) -> None:
            if isinstance(node.target, ast.Name) and node.target.id == name and node.value:
                _collect(node.value)
            self.generic_visit(node)

    _Visitor().visit(tree)
    return found


def _workshop_setup_fields() -> dict[str, Any]:
    for spec in stages():
        for entity in spec.entities:
            if entity.key == "workshopSetup":
                return {f.key: f for f in entity.fields}
    raise AssertionError("the registry has no workshopSetup entity")


SEEDED_KEYS = _literal_keys(inspect.getsource(annual_plan.plan_stage_one_values), "seeded")
COLUMN_KEYS = _literal_keys(inspect.getsource(annual_plan.promote_entry), "columns")



def test_every_promoted_key_promote_entry_seeds_is_a_real_workshop_setup_field():
    fields = _workshop_setup_fields()
    assert SEEDED_KEYS, "the seeded map must be readable — has it been rewritten?"
    for key in SEEDED_KEYS:
        assert key in fields, f"`{key}` is not a workshopSetup field"
        assert not fields[key].deprecated, f"`{key}` is deprecated"


def test_every_promoted_key_is_a_column_the_registry_actually_promotes():
    promoted = {
        path.split(".", 1)[1] for path in PROMOTED_COLUMNS if path.startswith("workshopSetup.")
    }
    for key in SEEDED_KEYS:
        assert key in promoted, f"`{key}` is seeded but is not in PROMOTED_COLUMNS"


def test_no_parser_clip_cap_exceeds_the_field_it_is_promoted_into():
    fields = _workshop_setup_fields()
    assert _CAP_MIRRORS, "the mirror map must not be empty"
    for role, field_key in _CAP_MIRRORS.items():
        assert field_key in fields, f"`{field_key}` is not a workshopSetup field"
        assert _TEXT_CAPS[role] <= fields[field_key].max_length, (
            f"parser cap for {role} ({_TEXT_CAPS[role]}) exceeds "
            f"workshopSetup.{field_key} ({fields[field_key].max_length})"
        )


def test_every_seeded_role_that_is_promoted_has_a_cap_mirror():
    fields = _workshop_setup_fields()
    bounded = {key for key in SEEDED_KEYS if fields[key].max_length}
    mirrored = set(_CAP_MIRRORS.values())
    assert bounded <= mirrored, f"unbounded promoted text: {sorted(bounded - mirrored)}"


def test_every_column_promote_entry_passes_is_a_header_column_and_none_is_stage_one_owned():
    from app.api.routes import design_workshops as dw_routes

    header = set(dw_routes._HEADER_TEXT_COLUMNS) | set(dw_routes._HEADER_DATE_COLUMNS)
    stage_one = set(dw_routes._STAGE_ONE_OWNS)
    passed = set(COLUMN_KEYS)
    assert passed, "the columns map must be readable"
    assert passed <= header, f"not header columns: {sorted(passed - header)}"
    assert not (passed & stage_one), f"stage 1 owns these: {sorted(passed & stage_one)}"
    for key in ("venue", "implementingAgency", "sponsor", "workshopCode"):
        assert key in SEEDED_KEYS
        assert key not in passed


def test_the_promote_body_is_bounded_exactly_as_the_create_body_is():
    promote = AnnualPlanPromoteRequest.model_fields
    create = DesignWorkshopCreate.model_fields

    def _max_length(field) -> int | None:
        for meta in field.metadata:
            if hasattr(meta, "max_length"):
                return meta.max_length
        return None

    assert _max_length(promote["designerUserIds"]) == MAX_DESIGN_WORKSHOP_VIEWERS
    assert _max_length(promote["designerUserIds"]) == _max_length(create["designerUserIds"])
    assert _max_length(promote["designerUserId"]) == _max_length(create["designerUserId"]) == 64


def test_the_promote_body_bounds_the_template_id_to_the_same_closed_set_the_create_body_does():
    from pydantic import ValidationError

    for bad in ("PHOTO-CATALOGUE", "DCH_STANDRD", "", "anything at all"):
        with pytest.raises(ValidationError) as promote_refusal:
            AnnualPlanPromoteRequest(templateId=bad)
        with pytest.raises(ValidationError):
            DesignWorkshopCreate(title="A workshop", templateId=bad)
        assert "templateId must be one of" in str(promote_refusal.value)

    assert AnnualPlanPromoteRequest().templateId == "DCH_STANDARD"
    assert AnnualPlanPromoteRequest(templateId="PHOTO_CATALOGUE").templateId == "PHOTO_CATALOGUE"


def test_the_promotion_folds_the_lead_designer_into_the_set_that_is_checked_and_granted():
    source = inspect.getsource(annual_plan.promote_entry)
    assert "named_designer_team(" in source, (
        "promote_entry hands the raw body to open_design_workshop; a lead named alone is then "
        "neither eligibility-checked nor granted, while their profile is copied into the report"
    )
    call = source.index("named_designer_team(")
    opened = source.index("open_design_workshop(")
    assert call < opened, "the normalisation has to happen BEFORE the workshop is created"
    assert "designer_id=lead_id" in source
    assert "designer_ids=granted_ids" in source


def test_the_promotion_stamp_is_a_compare_and_set_and_not_a_blind_update():
    tree = ast.parse(textwrap.dedent(inspect.getsource(annual_plan.promote_entry)))
    stamps = [
        node
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and node.func.attr == "update_many"
    ]
    assert len(stamps) == 1, "the promotion stamp is not a single update_many"
    where = next(kw.value for kw in stamps[0].keywords if kw.arg == "where")
    predicate = ast.unparse(where)
    assert "'designWorkshopId': None" in predicate, (
        f"the stamp's WHERE is {predicate}; without the None predicate it is an unconditional "
        "overwrite and a second promotion of the same row simply replaces the first"
    )

    source = inspect.getsource(annual_plan.promote_entry)
    assert "deletedAt" in source


def test_a_plan_row_with_no_title_still_produces_a_scannable_workshop_title():
    class _Entry:
        workshopNo = "DPW/2026/017"
        plannedTitle = None
        craftName = "Kachchh weaving"
        district = "Kachchh"
        state = "Gujarat"

    title = annual_plan.promotion_title(_Entry())
    assert title.startswith("DPW/2026/017")
    assert "Kachchh weaving" in title
    assert len(title) <= 220

    class _Bare:
        workshopNo = "DPW/2026/018"
        plannedTitle = None
        craftName = None
        district = None
        state = None

    assert annual_plan.promotion_title(_Bare()) == "DPW/2026/018"

    class _Long:
        workshopNo = "DPW/2026/019"
        plannedTitle = "क" * 400
        craftName = None
        district = None
        state = None

    assert len(annual_plan.promotion_title(_Long())) == 220



BOUNDARY = {
    "craftName": "c" * 160,
    "clusterName": "k" * 160,
    "state": "s" * 80,
    "district": "d" * 80,
    "venue": "v" * 220,
    "implementingAgency": "i" * 220,
    "sponsor": "p" * 220,
    "workshopNo": "w" * 60,
}


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    email = f"annual-plan-promoter-{stamp}@example.org"

    facts: dict[str, Any] = {"stamp": stamp}
    entries: dict[str, Any] = {}
    answers: dict[str, Any] = {}
    rows: dict[str, Any] = {}
    facts["entries"] = entries
    facts["answers"] = answers
    facts["rows"] = rows

    async def seed() -> None:
        async def entry(**overrides: Any):
            workshop_no = overrides.pop("workshopNo", f"DPW/2099/{uuid.uuid4().hex[:6]}")
            data = {
                "planYear": 2099,
                "workshopNo": workshop_no,
                "plannedTitle": f"A planned workshop {workshop_no}",
                "workshopKind": "DESIGN_PROTOTYPE_DEVELOPMENT",
                "plannedStartDate": datetime(2026, 3, 12, tzinfo=UTC),
                "plannedEndDate": datetime(2026, 3, 26, tzinfo=UTC),
            }
            data.update(overrides)
            data["workshopNoKey"] = data["workshopNo"].strip().upper()
            return await db.annualplanentry.create(data=data)

        async def account(slug: str, role: str, name: str, *, empanelled: bool = False):
            user = await db.user.create(
                data={
                    "email": f"plan-{slug}-{uuid.uuid4().hex[:8]}@example.org",
                    "name": name,
                    "role": role,
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            if empanelled:
                await db.designerroster.create(
                    data={
                        "email": user.email,
                        "fullName": user.name,
                        "institution": "Directorate of Handicrafts",
                        "isActive": True,
                        "addedById": admin.id,
                    }
                )
            return user

        await db.connect()
        try:
            admin = await db.user.create(
                data={
                    "email": email,
                    "name": f"Ministry admin {stamp}",
                    "role": "MINISTRY_ADMIN",
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            await db.accessroster.create(
                data={
                    "email": email,
                    "status": "ACTIVE",
                    "admitRole": "MINISTRY_ADMIN",
                    "joinedAt": datetime.now(UTC),
                    "notes": "Seeded by tests/test_annual_plan_promotion.py.",
                }
            )
            facts["admin"] = admin

            for key in ("plain", "twice", "withdraw_refused"):
                entries[key] = await entry()
            stamped_boundary = {**BOUNDARY, "workshopNo": ("w" * 52) + stamp}
            assert len(stamped_boundary["workshopNo"]) == len(BOUNDARY["workshopNo"]), (
                "the boundary row must stay exactly as long as BOUNDARY declares — that length is "
                "the assertion these tests exist to make"
            )
            entries["boundary"] = await entry(**stamped_boundary)
            entries["withdrawn"] = await entry(withdrawnAt=datetime.now(UTC))

            entries["ineligible_plural"] = await entry()
            entries["ineligible_lead"] = await entry()
            entries["lead_alone"] = await entry()
            entries["race"] = await entry()
            facts["professor_plural"] = await account("prof", "PROFESSOR", "A professor")
            facts["professor_lead"] = await account("lead-prof", "PROFESSOR", "A professor")
            facts["lead_designer"] = await account("lead", "DESIGNER", "Meera Kanungo", empanelled=True)
            facts["platform_admin"] = await account("platform-admin", "ADMIN", "A platform admin")

            facts["stale"] = await db.annualplanentry.find_unique(
                where={"id": entries["race"].id}
            )
            facts["stale_link_at_snapshot"] = facts["stale"].designWorkshopId

            first = await entry()
            second = await entry()
            promoted = await db.designworkshop.create(
                data={
                    "title": "A workshop",
                    "templateId": "DCH_STANDARD",
                    "createdById": admin.id,
                    "status": "DRAFT",
                }
            )
            await db.annualplanentry.update(
                where={"id": first.id}, data={"designWorkshopId": promoted.id}
            )
            try:
                await db.annualplanentry.update(
                    where={"id": second.id}, data={"designWorkshopId": promoted.id}
                )
            except Exception as exc:  # noqa: BLE001 - the CLASS is the finding; see the test
                facts["one_workshop_one_row"] = type(exc).__name__
            else:
                facts["one_workshop_one_row"] = None
        finally:
            await db.disconnect()

    def act(client: Any) -> None:
        headers = _headers(facts)

        def promote(key: str, body: dict[str, Any]) -> None:
            answers[key] = client.post(
                f"/api/annual-plan/{entries[key].id}/promote", json=body, headers=headers
            )

        promote("ineligible_plural", {"designerUserIds": [facts["professor_plural"].id]})
        promote("ineligible_lead", {"designerUserId": facts["professor_lead"].id})
        promote("lead_alone", {"designerUserId": facts["lead_designer"].id})
        promote("race", {})

    async def observe() -> None:
        from fastapi import HTTPException

        await db.connect()
        try:
            for key in ("ineligible_plural", "ineligible_lead"):
                plan = entries[key]
                rows[key] = {
                    "orphans": await db.designworkshop.count(
                        where={"title": {"contains": plan.plannedTitle}}
                    ),
                    "reread": await db.annualplanentry.find_unique(where={"id": plan.id}),
                }

            rows["lead_alone"] = {"granted": []}
            created = answers["lead_alone"]
            if created.status_code == 201:
                rows["lead_alone"]["granted"] = await db.designworkshopviewer.find_many(
                    where={"designWorkshopId": created.json()["workshop"]["id"]}
                )

            rows["race"] = {}
            before = await db.designworkshop.count(where={"deletedAt": None})
            try:
                await annual_plan.promote_entry(
                    facts["stale"], actor=facts["admin"], designer_id=None, designer_ids=[]
                )
            except HTTPException as exc:
                rows["race"]["refusal_status"] = exc.status_code
                rows["race"]["refusal_type"] = None
            except Exception as exc:  # noqa: BLE001 - recorded and re-raised by the assertion below
                rows["race"]["refusal_status"] = None
                rows["race"]["refusal_type"] = f"{type(exc).__name__}: {exc}"
            else:
                rows["race"]["refusal_status"] = None
                rows["race"]["refusal_type"] = None
            rows["race"]["reread"] = await db.annualplanentry.find_unique(
                where={"id": entries["race"].id}
            )
            rows["race"]["live_after"] = await db.designworkshop.count(where={"deletedAt": None})
            rows["race"]["live_before"] = before
        finally:
            await db.disconnect()

    asyncio.run(seed())
    with TestClient(app) as client:
        act(client)
    asyncio.run(observe())
    with TestClient(app) as client:
        facts["client"] = client
        yield facts


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['admin'].id)}"}


def _platform_headers(world) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['platform_admin'].id)}"}


def _promote(client: Any, world: dict[str, Any], key: str, body: dict[str, Any] | None = None):
    return client.post(
        f"/api/annual-plan/{world['entries'][key].id}/promote",
        json=body or {},
        headers=_headers(world),
    )


@needs_db
def test_promoting_a_row_creates_one_workshop_and_records_the_link(world, client) -> None:
    response = _promote(client, world, "plain")
    assert response.status_code == 201, response.text
    body = response.json()
    assert body["entry"]["standing"] == "PROMOTED"
    assert body["entry"]["designWorkshopId"] == body["workshop"]["id"]
    assert body["entry"]["promotedAt"] is not None


@needs_db
def test_a_promoted_row_cannot_be_promoted_twice(world, client) -> None:
    assert _promote(client, world, "twice").status_code == 201
    second = _promote(client, world, "twice")
    assert second.status_code == 409
    assert world["entries"]["twice"].workshopNo in second.json()["detail"]


@needs_db
def test_the_database_also_refuses_a_second_workshop_on_one_row(world) -> None:
    assert world["one_workshop_one_row"] == "UniqueViolationError", (
        "a second plan row was allowed to point at a workshop another row already holds — "
        "`AnnualPlanEntry.designWorkshopId` is no longer unique and the route is now the only guard"
    )


@needs_db
@pytest.mark.xfail(
    strict=True,
    reason=(
        "A PARTIAL stage-1 save nulls the promoted header columns, and which of two documented "
        "rules should win is a product decision nobody has taken. Traced in full in the docstring "
        "below; strict=True so this goes red the moment somebody fixes it and the marker is removed "
        "rather than lingering."
    ),
)
def test_the_promoted_workshop_survives_its_first_stage_one_save(world, client) -> None:
    created = _promote(client, world, "boundary")
    assert created.status_code == 201, created.text
    workshop_id = created.json()["workshop"]["id"]

    before = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world)
    ).json()
    for column in ("craftName", "clusterName", "state", "district", "venue"):
        assert before[column] == BOUNDARY[column], column

    saved = client.put(
        f"/api/design-workshops/{workshop_id}/stages/WORKSHOP_SETUP",
        json={"entries": [{"entityKey": "workshopSetup", "data": {"block": "Bhujodi"}}]},
        headers=_headers(world),
    )
    assert saved.status_code in (200, 201), saved.text

    after = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world)
    ).json()
    for column in ("craftName", "clusterName", "state", "district", "venue", "workshopCode"):
        assert after.get(column) == before.get(column), f"{column} was nulled by the first save"


@needs_db
def test_a_withdrawn_row_cannot_be_promoted(world, client) -> None:
    response = _promote(client, world, "withdrawn")
    assert response.status_code == 409
    assert "withdrawn" in response.json()["detail"].lower()


@needs_db
def test_naming_an_ineligible_designer_refuses_the_whole_promotion(world) -> None:
    professor = world["professor_plural"]
    response = world["answers"]["ineligible_plural"]
    assert response.status_code == 422
    assert professor.id in response.text or professor.email in response.text

    seen = world["rows"]["ineligible_plural"]
    assert seen["orphans"] == 0, (
        "a refused promotion left a workshop carrying this plan row's number behind"
    )
    assert seen["reread"].designWorkshopId is None


@needs_db
def test_naming_an_ineligible_lead_designer_alone_refuses_the_promotion_too(world) -> None:
    professor = world["professor_lead"]
    response = world["answers"]["ineligible_lead"]
    assert response.status_code == 422, response.text
    assert professor.id in response.text or professor.email in response.text

    seen = world["rows"]["ineligible_lead"]
    assert seen["orphans"] == 0, (
        "a workshop was created for a designer the eligibility rule refuses — and its stage 1 "
        "and stage 3 now hold that account's profile"
    )
    assert seen["reread"].designWorkshopId is None


@needs_db
def test_a_lead_designer_named_alone_is_granted_the_workshop_they_are_named_on(world) -> None:
    created = world["answers"]["lead_alone"]
    assert created.status_code == 201, created.text

    granted = world["rows"]["lead_alone"]["granted"]
    assert [row.userId for row in granted] == [world["lead_designer"].id], (
        "the lead named alone was given no viewer row, so the designer this workshop is FOR "
        "answers 404 on it while stage 1 carries their name"
    )


@needs_db
def test_a_second_promotion_that_slips_past_the_check_creates_no_orphan_workshop(world) -> None:
    assert world["stale_link_at_snapshot"] is None, (
        "the snapshot handed to promote_entry was taken AFTER the row was promoted, so it is not "
        "stale and the compare-and-set is not being exercised at all"
    )

    winner = world["answers"]["race"]
    assert winner.status_code == 201, winner.text
    winning_id = winner.json()["workshop"]["id"]

    seen = world["rows"]["race"]
    assert seen["refusal_type"] is None, (
        f"the stale promotion raised something that is not an HTTPException: {seen['refusal_type']}. "
        "That is not the refusal this test is about — the compare-and-set did not get as far as "
        "deciding."
    )
    assert seen["refusal_status"] == 409, (
        "the stale promotion was not refused — the compare-and-set is gone and the second call "
        "simply overwrote the first"
    )
    assert seen["reread"].designWorkshopId == winning_id, "the loser overwrote the winner's link"
    assert seen["live_after"] == seen["live_before"], (
        "the losing promotion left a live orphan workshop in the directory that the plan row "
        "can never reach, delete or explain"
    )


@needs_db
def test_withdrawing_a_promoted_row_is_refused(world, client) -> None:
    assert _promote(client, world, "withdraw_refused").status_code == 201
    refused = client.post(
        f"/api/annual-plan/{world['entries']['withdraw_refused'].id}/withdraw",
        json={},
        headers=_headers(world),
    )
    assert refused.status_code == 409
    assert "cancel the workshop" in refused.json()["detail"].lower()
