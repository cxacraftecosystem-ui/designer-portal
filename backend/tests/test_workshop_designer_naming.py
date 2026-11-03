from __future__ import annotations

import inspect
from types import SimpleNamespace
from typing import Any

import pytest
from pydantic import ValidationError

import app.services.stage_definitions  # noqa: F401
from app.api.routes import design_workshops as routes
from app.schemas.design_workshop_viewers import MAX_DESIGN_WORKSHOP_VIEWERS
from app.schemas.design_workshops import DesignWorkshopCreate
from app.services import design_workshops as service

pytestmark = pytest.mark.anyio

STAGE_1 = "WORKSHOP_SETUP"
STAGE_3 = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"

ACTOR = SimpleNamespace(id="admin-1", name="Sunil Patnaik", role="ADMIN")
DESIGNER_ID = "designer-1"
CREATOR_ID = ACTOR.id

CO_DESIGNER_ID = "designer-2"

PROFILES: dict[str, dict[str, Any]] = {
    ACTOR.id: {
        "designerName": "Sunil Patnaik",
        "designerInstitution": "Directorate of Handicrafts, Bhubaneswar",
        "designerProfile": "Twenty years administering cluster development programmes.",
        "designerExperience": 20,
    },
    DESIGNER_ID: {
        "designerName": "Meera Kanungo",
        "designerInstitution": "NIFT Bhubaneswar",
        "designerProfile": "Twelve years of ikat and tie-and-dye across western Odisha.",
        "designerExperience": 12,
    },
    CO_DESIGNER_ID: {
        "designerName": "Rukmini Behera",
        "designerInstitution": "Sambalpur Handloom Cooperative",
        "designerProfile": "Nine years of dhokra casting and bell metal in Dhenkanal.",
        "designerExperience": 9,
    },
    "designer-with-no-profile": {},
}


@pytest.fixture
def anyio_backend():
    return "asyncio"


class _StageEntryTable:
    def __init__(self) -> None:
        self.rows: list[dict[str, Any]] = []

    async def create(self, data: dict[str, Any]) -> Any:
        self.rows.append(data)
        return SimpleNamespace(id=f"entry-{len(self.rows)}")


class _WorkshopTable:
    def __init__(self, record: Any) -> None:
        self._record = record
        self.updates: list[dict[str, Any]] = []

    async def update(self, where: dict[str, Any], data: dict[str, Any]) -> Any:
        self.updates.append(data)
        return SimpleNamespace(**{**vars(self._record), **data})


class _StubDb:
    def __init__(self, record: Any) -> None:
        self.dwstageentry = _StageEntryTable()
        self.designworkshop = _WorkshopTable(record)


async def _seed(
    monkeypatch: Any, *, designer_id: str | None, extra: dict[str, Any] | None = None
) -> tuple[_StubDb, list[str]]:
    asked: list[str] = []

    async def _profile(user_id: str) -> dict[str, Any]:
        asked.append(user_id)
        return dict(PROFILES.get(user_id, {}))

    swallowed: list[Any] = []
    monkeypatch.setattr(service, "prefill_from_profile", _profile)
    monkeypatch.setattr(service.logger, "exception", lambda *a, **k: swallowed.append((a, k)))
    record = SimpleNamespace(id="ws-1", title="Ikat cluster fortnight", createdById=CREATOR_ID)
    db = _StubDb(record)
    monkeypatch.setattr(service, "db", db)

    await service.seed_designer_prefill(record, ACTOR, designer_id=designer_id, extra=extra)
    assert swallowed == [], (
        f"the prefill swallowed {swallowed!r}. Its blanket `except` exists so a convenience value "
        "can never fail a create, which also means a broken stub or a genuine regression surfaces "
        "as an empty stage rather than as an error — so it is asserted rather than trusted."
    )
    return db, asked


def _entry(db: _StubDb, stage_key: str) -> dict[str, Any]:
    rows = [row for row in db.dwstageentry.rows if row["stageKey"] == stage_key]
    assert len(rows) == 1, (
        f"expected exactly one seeded row for {stage_key}, got {len(rows)}; the seed wrote "
        f"{[row['stageKey'] for row in db.dwstageentry.rows]}"
    )
    row = dict(rows[0])
    for key in ("data", "fieldProvenance"):
        row[key] = getattr(row[key], "data", row[key])
    return row


def _every_seeded_value(db: _StubDb) -> str:
    return str([getattr(row["data"], "data", row["data"]) for row in db.dwstageentry.rows])



async def test_naming_a_designer_copies_THEIR_profile_and_never_asks_about_the_admin(monkeypatch):
    db, asked = await _seed(monkeypatch, designer_id=DESIGNER_ID)

    assert asked == [DESIGNER_ID], (
        f"the prefill asked about {asked}. It must ask about the NAMED DESIGNER and nobody else — "
        "an extra read of the creator's profile is a fallback, and a fallback here puts the admin's "
        "name back on the cover of a report submitted to a ministry."
    )
    stage_1 = _entry(db, STAGE_1)["data"]
    assert stage_1["designerName"] == "Meera Kanungo"
    assert stage_1["designerInstitution"] == "NIFT Bhubaneswar"
    assert "Sunil Patnaik" not in _every_seeded_value(db), (
        "the admin's name reached a stage entry; every surface that prints a designer reads these "
        "rows with no special case at all"
    )


async def test_naming_nobody_still_copies_the_creators_profile_byte_for_byte(monkeypatch):
    db, asked = await _seed(monkeypatch, designer_id=None)

    assert asked == [ACTOR.id]
    assert _entry(db, STAGE_1)["data"]["designerName"] == "Sunil Patnaik"


async def test_a_named_designer_with_no_profile_leaves_the_box_EMPTY_and_does_not_fall_back(
    monkeypatch,
):
    db, asked = await _seed(monkeypatch, designer_id="designer-with-no-profile")

    assert asked == ["designer-with-no-profile"], (
        f"asked about {asked}; a second read is the fallback this test exists to refuse"
    )
    assert db.dwstageentry.rows == [], (
        "a named designer with no profile must leave the designer block empty. These rows were "
        f"written instead: {db.dwstageentry.rows}"
    )
    assert db.designworkshop.updates == [], "and no promoted column may be written either"


async def test_the_create_forms_own_answers_are_still_seeded_for_a_named_designer(monkeypatch):
    db, _ = await _seed(
        monkeypatch, designer_id=DESIGNER_ID, extra={"craftName": "Ikat", "state": "Odisha"}
    )

    data = _entry(db, STAGE_1)["data"]
    assert data["craftName"] == "Ikat"
    assert data["state"] == "Odisha"
    assert data["designerName"] == "Meera Kanungo"
    assert db.designworkshop.updates[0]["designerName"] == "Meera Kanungo", (
        "the promoted columns are what the workshop LIST and the report cover read, so the "
        "designer's name has to reach them or the fix stops at the stage form"
    )


async def test_stage_3s_nineteen_boxes_are_the_designers_too(monkeypatch):
    db, _ = await _seed(monkeypatch, designer_id=DESIGNER_ID)

    data = _entry(db, STAGE_3)["data"]
    assert data["designerExperience"] == 12, "an INT field must arrive as an int"
    assert "ikat" in str(data["designerProfile"]).lower()
    assert "administering cluster development" not in str(data["designerProfile"]).lower()



async def test_the_row_is_authored_by_the_ADMIN_and_never_by_the_designer(monkeypatch):
    db, _ = await _seed(monkeypatch, designer_id=DESIGNER_ID)

    row = _entry(db, STAGE_1)
    assert row["createdById"] == ACTOR.id, "the row was created by the admin, because it was"
    stamp = row["fieldProvenance"]["designerName"]
    assert stamp["by"] == ACTOR.id
    assert stamp["byName"] == ACTOR.name
    assert stamp["source"] == "designer", (
        "`designer` is the source meaning 'a person working on this workshop set this'. A third "
        "source would need Android and the web to learn a new word in the same release."
    )
    assert stamp["by"] != DESIGNER_ID, (
        "stamping the designer would claim they set a value they have not seen — a fabricated audit "
        "trail on a ministry document, indistinguishable on screen from a real one"
    )



async def test_the_seeding_path_never_reads_the_viewer_TABLE_so_it_cannot_guess(monkeypatch):
    db, _ = await _seed(monkeypatch, designer_id=DESIGNER_ID)

    assert not hasattr(db, "designworkshopviewer"), "the stub's whole point"
    assert _entry(db, STAGE_1)["data"]["designerName"] == "Meera Kanungo"


async def test_the_creator_is_not_given_a_viewer_row_when_they_name_themselves(monkeypatch):
    written: list[dict[str, Any]] = []

    async def _add_one(tx: Any, **kwargs: Any) -> None:
        written.append(kwargs)

    monkeypatch.setattr(service, "add_one_viewer", _add_one)

    wrote_row = await service.attach_the_named_designer(
        "ws-1", CREATOR_ID, granted_by_id=ACTOR.id, creator_id=CREATOR_ID
    )
    assert wrote_row is False
    assert written == [], "the creator already holds the workshop through createdById"

    wrote_row = await service.attach_the_named_designer(
        "ws-1", DESIGNER_ID, granted_by_id=ACTOR.id, creator_id=CREATOR_ID
    )
    assert wrote_row is True
    assert written == [
        {"workshop_id": "ws-1", "user_id": DESIGNER_ID, "granted_by_id": ACTOR.id}
    ], (
        "one row, one account, through the same statement a decided access request and a redeemed "
        "join card use — a third hand-written insert into the table that confers access is how the "
        "three come to disagree about a column"
    )


async def test_eligibility_is_the_viewers_screens_own_rule_asked_about_that_one_account(
    monkeypatch,
):
    asked: list[set[str]] = []

    async def _assert(user_ids: set[str], **_workshop_context: Any) -> None:
        asked.append(set(user_ids))

    monkeypatch.setattr(service, "_assert_every_id_may_be_granted", _assert)
    await service.assert_every_designer_may_be_named({DESIGNER_ID})
    assert asked == [{DESIGNER_ID}]


async def test_a_whole_ticked_TEAM_is_asked_about_in_ONE_call_and_never_id_by_id(monkeypatch):
    asked: list[set[str]] = []

    async def _assert(user_ids: set[str], **_workshop_context: Any) -> None:
        asked.append(set(user_ids))

    monkeypatch.setattr(service, "_assert_every_id_may_be_granted", _assert)
    await service.assert_every_designer_may_be_named({DESIGNER_ID, CO_DESIGNER_ID})
    assert asked == [{DESIGNER_ID, CO_DESIGNER_ID}], (
        "the eligibility rule was asked one id at a time, so a create naming two ineligible "
        "designers now refuses with only the first of them named"
    )


async def test_naming_nobody_costs_no_eligibility_QUERY_at_all(monkeypatch):
    asked: list[set[str]] = []

    async def _assert(user_ids: set[str], **_workshop_context: Any) -> None:
        asked.append(set(user_ids))

    monkeypatch.setattr(service, "_assert_every_id_may_be_granted", _assert)
    await service.assert_every_designer_may_be_named(set())
    assert asked == [set()]



def test_the_create_route_settles_eligibility_BEFORE_it_writes_the_workshop_row():
    source = inspect.getsource(routes.create_design_workshop)
    validate_at = source.index("await assert_every_designer_may_be_named(")
    create_at = source.index("await db.designworkshop.create(")
    grant_at = source.index("await attach_the_named_designers(")

    assert validate_at < create_at, (
        "the eligibility check moved below the create, so a refused designer id now leaves an "
        "orphan draft behind on every retry"
    )
    assert create_at < grant_at, (
        "the grant needs the workshop's id, so it cannot precede the create — if this fails, the "
        "reading of this route has changed by more than an ordering"
    )


def test_designerUserId_is_optional_so_no_existing_client_is_broken_by_it():
    body = DesignWorkshopCreate(title="Ikat cluster fortnight")
    assert body.designerUserId is None

    named = DesignWorkshopCreate(title="Ikat cluster fortnight", designerUserId=DESIGNER_ID)
    assert named.designerUserId == DESIGNER_ID


def test_the_lead_is_the_singular_field_when_a_body_sends_both():
    lead, team = service.named_designer_team(DESIGNER_ID, [CO_DESIGNER_ID, DESIGNER_ID])
    assert lead == DESIGNER_ID
    assert team == [DESIGNER_ID, CO_DESIGNER_ID], (
        "the lead must be first in the team as well, so that a client reading the team back cannot "
        "disagree with the server about whose name is on the report"
    )


def test_a_body_that_ticks_names_but_names_no_lead_promotes_the_FIRST_TICKED():
    lead, team = service.named_designer_team(None, [CO_DESIGNER_ID, DESIGNER_ID])
    assert lead == CO_DESIGNER_ID
    assert team == [CO_DESIGNER_ID, DESIGNER_ID]


def test_a_lead_who_is_not_in_the_ticked_list_is_granted_anyway():
    lead, team = service.named_designer_team(DESIGNER_ID, [CO_DESIGNER_ID])
    assert lead == DESIGNER_ID
    assert team == [DESIGNER_ID, CO_DESIGNER_ID]


def test_blanks_duplicates_and_None_all_mean_nobody_named():
    assert service.named_designer_team(None, None) == (None, [])
    assert service.named_designer_team("", []) == (None, [])
    assert service.named_designer_team("   ", ["", "   ", None]) == (None, [])
    assert service.named_designer_team(None, [DESIGNER_ID, DESIGNER_ID, f" {DESIGNER_ID} "]) == (
        DESIGNER_ID,
        [DESIGNER_ID],
    ), "a duplicated id must collapse rather than attempt a second row on the same primary key"


def test_the_old_singular_wire_still_produces_exactly_what_it_used_to():
    assert service.named_designer_team(DESIGNER_ID, None) == (DESIGNER_ID, [DESIGNER_ID])


def test_designerUserIds_is_optional_and_bounded_by_the_viewers_screens_own_cap():
    body = DesignWorkshopCreate(title="Ikat cluster fortnight")
    assert body.designerUserIds is None, (
        "absent must mean 'nobody named'. If this ever defaults to [] it still reads the same to "
        "the route, but a client can no longer tell 'I did not answer' from 'I answered nobody'."
    )

    named = DesignWorkshopCreate(
        title="Ikat cluster fortnight",
        designerUserId=DESIGNER_ID,
        designerUserIds=[DESIGNER_ID, CO_DESIGNER_ID],
    )
    assert named.designerUserIds == [DESIGNER_ID, CO_DESIGNER_ID]

    with pytest.raises(ValidationError):
        DesignWorkshopCreate(
            title="Too many",
            designerUserIds=[f"designer-{n}" for n in range(MAX_DESIGN_WORKSHOP_VIEWERS + 1)],
        )


async def test_every_named_designer_gets_a_row_and_the_creator_gets_none(monkeypatch):
    written: list[str] = []

    async def _add(tx: Any, *, workshop_id: str, user_id: str, **kwargs: Any) -> None:
        written.append(user_id)

    monkeypatch.setattr(service, "add_one_viewer", _add)
    granted = await service.attach_the_named_designers(
        "ws-1",
        [ACTOR.id, DESIGNER_ID, CO_DESIGNER_ID],
        granted_by_id=ACTOR.id,
        creator_id=ACTOR.id,
    )
    assert written == [DESIGNER_ID, CO_DESIGNER_ID]
    assert granted == [DESIGNER_ID, CO_DESIGNER_ID]


def test_the_grant_loop_adds_and_can_never_REPLACE():
    source = inspect.getsource(service.attach_the_named_designers).replace("\r\n", "\n")
    opening = source.index('"""')
    closing = source.index('"""', opening + 3) + 3
    body = source[:opening] + source[closing:]
    assert "A LOOP OVER" not in body, (
        "the docstring was not cut out, so the sweep below is running over text that still "
        "contains the warning it is searching for — see the paragraph above"
    )
    assert "replace_viewers" not in body, (
        "the grant loop reached for the whole-set replace, which deletes the viewer rows it did "
        "not read — see services/design_workshop_access.add_one_viewer"
    )
    assert "add_one_viewer" in inspect.getsource(service.attach_the_named_designer)


async def test_a_grant_that_fails_PART_WAY_does_not_pretend_the_create_succeeded(monkeypatch):
    written: list[str] = []
    logged: list[Any] = []

    async def _add(tx: Any, *, workshop_id: str, user_id: str, **kwargs: Any) -> None:
        if user_id == CO_DESIGNER_ID:
            raise RuntimeError("driver went away")
        written.append(user_id)

    monkeypatch.setattr(service, "add_one_viewer", _add)
    monkeypatch.setattr(service.logger, "exception", lambda *a, **k: logged.append((a, k)))

    with pytest.raises(RuntimeError):
        await service.attach_the_named_designers(
            "ws-1",
            [DESIGNER_ID, CO_DESIGNER_ID],
            granted_by_id=ACTOR.id,
            creator_id=CREATOR_ID,
        )
    assert written == [DESIGNER_ID]
    assert logged, "the partial grant was neither raised to the caller nor written to the log"
    assert DESIGNER_ID in str(logged[0]), (
        "the log line must name the ids that DID get rows; 'granting failed' on its own tells an "
        "operator nothing about how much of the team is on the workshop"
    )


async def test_a_TEAM_of_designers_still_seeds_exactly_ONE_designer_block(monkeypatch):
    db, asked = await _seed(monkeypatch, designer_id=DESIGNER_ID)

    assert asked == [DESIGNER_ID], (
        f"the seed asked about {asked}; with a team named it must still read exactly one profile, "
        "because there is exactly one designer block to write it into"
    )
    setup = _entry(db, STAGE_1)["data"]
    assert setup["designerName"] == "Meera Kanungo"
    everything = _every_seeded_value(db)
    assert "Rukmini Behera" not in everything
    assert "Sambalpur Handloom Cooperative" not in everything
