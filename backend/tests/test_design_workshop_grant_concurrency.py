import asyncio
import hashlib
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest

import app.services.design_workshop_access as access
import app.services.design_workshop_grants as grants
import app.services.design_workshop_viewers as viewers

WORKSHOP_ID = "cmgrantrace0000000000000w"
CREATOR_ID = "cmgrantrace0000000000000c"
ISSUER_ID = "cmgrantrace0000000000000i"
ALICE_ID = "cmgrantrace0000000000000a"
BOB_ID = "cmgrantrace0000000000000b"
CAROL_ID = "cmgrantrace00000000000000"
TOKEN_ID = "cmgrantrace0000000000000t"


def _user(user_id: str, role: str = "DESIGNER") -> SimpleNamespace:
    return SimpleNamespace(
        id=user_id, email=f"{user_id}@example.test", name=user_id.upper(), role=role
    )


class _Delegate:
    def __init__(self, rows: list[Any] | None = None, *, defaults: dict[str, Any] | None = None):
        self.rows = list(rows or [])
        self.defaults = dict(defaults or {})
        self.created: list[dict[str, Any]] = []
        self.deleted: list[dict[str, Any]] = []

    @staticmethod
    def _matches(row: Any, where: dict[str, Any]) -> bool:
        for key, wanted in where.items():
            if key.endswith(("_userId", "_requestedById")):
                return _Delegate._matches(row, wanted)
            actual = getattr(row, key, None)
            if isinstance(wanted, dict):
                if "in" in wanted and actual not in wanted["in"]:
                    return False
                if "gt" in wanted and not (actual is not None and actual > wanted["gt"]):
                    return False
            elif actual != wanted:
                return False
        return True

    async def find_unique(self, where: dict[str, Any], include: Any = None) -> Any:
        return next((row for row in self.rows if self._matches(row, where)), None)

    async def find_many(
        self,
        where: dict[str, Any] | None = None,
        include: Any = None,
        order: Any = None,
        take: int | None = None,
    ) -> list[Any]:
        found = [row for row in self.rows if self._matches(row, where or {})]
        return found[:take] if take else found

    async def count(self, where: dict[str, Any] | None = None) -> int:
        return len([row for row in self.rows if self._matches(row, where or {})])

    async def create(self, data: dict[str, Any]) -> Any:
        self.created.append(dict(data))
        row = SimpleNamespace(**{**self.defaults, "id": f"row-{len(self.rows)}", **data})
        self.rows.append(row)
        return row

    async def create_many(self, data: list[dict[str, Any]], skip_duplicates: bool = False) -> int:
        written = 0
        for item in data:
            if "designWorkshopId" in item:
                keys = ["designWorkshopId"]
            else:
                keys = [k for k in ("tokenId",) if k in item]
            keys += [k for k in ("userId", "requestedById") if k in item]
            if any(all(getattr(row, k, None) == item[k] for k in keys) for row in self.rows):
                if not skip_duplicates:
                    raise AssertionError("duplicate key without skip_duplicates")
                continue
            self.created.append(dict(item))
            self.rows.append(SimpleNamespace(**{**self.defaults, "id": f"row-{len(self.rows)}", **item}))
            written += 1
        return written

    async def update(self, where: dict[str, Any], data: dict[str, Any]) -> Any:
        for row in self.rows:
            if self._matches(row, where):
                for key, value in data.items():
                    setattr(row, key, value)
                return row
        return None

    async def update_many(self, where: dict[str, Any], data: dict[str, Any]) -> int:
        touched = 0
        for row in self.rows:
            if self._matches(row, where):
                for key, value in data.items():
                    setattr(row, key, value)
                touched += 1
        return touched

    async def delete_many(self, where: dict[str, Any]) -> int:
        keep = [row for row in self.rows if not self._matches(row, where)]
        removed = len(self.rows) - len(keep)
        if removed:
            self.deleted.append(dict(where))
        self.rows = keep
        return removed


class _Db:
    def __init__(self, **delegates: _Delegate) -> None:
        for name, delegate in delegates.items():
            setattr(self, name, delegate)

    def tx(self) -> Any:
        client = self

        class _Tx:
            async def __aenter__(self) -> Any:
                return client

            async def __aexit__(self, *_exc: object) -> bool:
                return False

        return _Tx()


class _ExplodingDelegate(_Delegate):
    def __init__(self, error: BaseException) -> None:
        super().__init__()
        self.error = error

    async def create_many(self, data: list[dict[str, Any]], skip_duplicates: bool = False) -> int:
        raise self.error


@pytest.fixture
def world(monkeypatch: pytest.MonkeyPatch):
    secret = grants.mint_secret()
    now = datetime.now(UTC)
    delegates = {
        "designworkshop": _Delegate(
            [
                SimpleNamespace(
                    id=WORKSHOP_ID, title="Kalamkari", createdById=CREATOR_ID, deletedAt=None
                )
            ]
        ),
        "recordaccesstoken": _Delegate(
            [
                SimpleNamespace(
                    id=TOKEN_ID,
                    recordType="DESIGN_WORKSHOP",
                    recordId=WORKSHOP_ID,
                    secretHash=hashlib.sha256(secret.encode()).hexdigest(),
                    secretLast4=secret[-4:],
                    issuedById=ISSUER_ID,
                    maxUses=5,
                    usesConsumed=0,
                    expiresAt=now + timedelta(days=14),
                    revokedAt=None,
                    revokedById=None,
                    label="stage-4 batch",
                    createdAt=now,
                )
            ]
        ),
        "recordaccesstokenredemption": _Delegate(),
        "designworkshopviewer": _Delegate(
            defaults={"grantedById": None, "tokenId": None, "createdAt": now}
        ),
        "designworkshopprovisionalmember": _Delegate(),
        "designworkshopaccessrequest": _Delegate(),
    }
    db = _Db(**delegates)
    for module in (grants, access, viewers):
        monkeypatch.setattr(module, "db", db)

    async def _every_id_is_fine(_user_ids: set[str], **_workshop_context: Any) -> None:
        return None

    monkeypatch.setattr(grants, "_assert_every_id_may_be_granted", _every_id_is_fine)
    return SimpleNamespace(
        db=db,
        secret=secret,
        code=grants.encode_join_code(WORKSHOP_ID, secret),
        **delegates,
    )


def _redeem(user: Any, code: str, **kwargs: Any) -> dict[str, Any]:
    return asyncio.run(grants.redeem(user, code=code, **kwargs))



def test_a_second_redeemer_does_not_delete_the_first_redeemers_viewer_row(world):
    bob = _redeem(_user(BOB_ID), world.code)
    alice = _redeem(_user(ALICE_ID), world.code)

    assert bob["outcome"] == "FULL"
    assert alice["outcome"] == "FULL"

    held = {row.userId for row in world.designworkshopviewer.rows}
    assert held == {BOB_ID, ALICE_ID}, "a full grant must not take away another full grant"

    assert world.designworkshopviewer.deleted == []

    for row in world.designworkshopviewer.rows:
        assert row.tokenId == TOKEN_ID
        assert row.grantedById == ISSUER_ID

    assert world.recordaccesstoken.rows[0].usesConsumed == 2


def test_a_redemption_leaves_a_viewer_it_has_never_heard_of_alone(world):
    world.designworkshopviewer.rows.append(
        SimpleNamespace(
            designWorkshopId=WORKSHOP_ID,
            userId=CAROL_ID,
            grantedById=CREATOR_ID,
            tokenId=None,
            createdAt=datetime.now(UTC) - timedelta(days=30),
        )
    )
    carol_granted_at = world.designworkshopviewer.rows[0].createdAt

    assert _redeem(_user(ALICE_ID), world.code)["outcome"] == "FULL"

    carol = next(row for row in world.designworkshopviewer.rows if row.userId == CAROL_ID)
    assert carol.grantedById == CREATOR_ID, "somebody else's provenance is not the card's to rewrite"
    assert carol.tokenId is None, "no card produced Carol's row and none may claim it"
    assert carol.createdAt == carol_granted_at


def test_a_redemption_does_not_resurrect_a_viewer_an_admin_has_removed(world):
    world.designworkshopviewer.rows.append(
        SimpleNamespace(
            designWorkshopId=WORKSHOP_ID,
            userId=CAROL_ID,
            grantedById=CREATOR_ID,
            tokenId=None,
            createdAt=datetime.now(UTC),
        )
    )
    world.designworkshopviewer.rows = [
        row for row in world.designworkshopviewer.rows if row.userId != CAROL_ID
    ]

    assert _redeem(_user(ALICE_ID), world.code)["outcome"] == "FULL"

    held = {row.userId for row in world.designworkshopviewer.rows}
    assert held == {ALICE_ID}, "a scan must not undo an administrator's removal"


def test_a_race_with_an_admin_granting_the_same_person_settles_to_one_row(world):
    world.designworkshopviewer.rows.append(
        SimpleNamespace(
            designWorkshopId=WORKSHOP_ID,
            userId=ALICE_ID,
            grantedById=CREATOR_ID,
            tokenId=None,
            createdAt=datetime.now(UTC),
        )
    )
    seats = asyncio.run(
        grants._consume_a_seat(world.recordaccesstoken.rows[0], now=datetime.now(UTC))
    )
    assert seats == 1
    asyncio.run(
        grants._write_the_viewer_row(
            world.db,
            workshop_id=WORKSHOP_ID,
            user_id=ALICE_ID,
            token_id=TOKEN_ID,
            granted_by_id=ISSUER_ID,
        )
    )

    rows = [row for row in world.designworkshopviewer.rows if row.userId == ALICE_ID]
    assert len(rows) == 1, "the pair is the primary key; a collision is one row, not two"
    assert rows[0].grantedById == CREATOR_ID, "the admin got there first and the row says so"
    assert rows[0].tokenId is None



def test_a_later_valid_scan_clears_the_provisional_foothold_it_upgrades(world):
    token = world.recordaccesstoken.rows[0]
    token.usesConsumed = token.maxUses

    late = _redeem(_user(ALICE_ID), world.code)
    assert late["outcome"] == "PROVISIONAL" and late["reason"] == "ALREADY_SPENT"
    assert [row.userId for row in world.designworkshopprovisionalmember.rows] == [ALICE_ID]

    fresh_secret = grants.mint_secret()
    world.recordaccesstoken.rows.append(
        SimpleNamespace(
            id="cmgrantrace0000000000002t",
            recordType="DESIGN_WORKSHOP",
            recordId=WORKSHOP_ID,
            secretHash=hashlib.sha256(fresh_secret.encode()).hexdigest(),
            secretLast4=fresh_secret[-4:],
            issuedById=ISSUER_ID,
            maxUses=1,
            usesConsumed=0,
            expiresAt=datetime.now(UTC) + timedelta(days=14),
            revokedAt=None,
            revokedById=None,
            label="reprint",
            createdAt=datetime.now(UTC),
        )
    )

    upgraded = _redeem(_user(ALICE_ID), grants.encode_join_code(WORKSHOP_ID, fresh_secret))

    assert upgraded["outcome"] == "FULL"
    assert [row.userId for row in world.designworkshopviewer.rows] == [ALICE_ID]
    assert world.designworkshopprovisionalmember.rows == [], (
        "the foothold must go with the promotion, or the same person is in two membership tables"
    )

    request = next(
        row for row in world.designworkshopaccessrequest.rows if row.requestedById == ALICE_ID
    )
    assert request.status == "GRANTED"
    assert request.tokenId == "cmgrantrace0000000000002t"
    assert getattr(request, "decidedById", None) is None


def test_an_ordinary_full_grant_deletes_no_foothold_and_says_nothing_about_it(world):
    assert _redeem(_user(ALICE_ID), world.code)["outcome"] == "FULL"
    assert world.designworkshopprovisionalmember.rows == []
    assert [row.userId for row in world.designworkshopviewer.rows] == [ALICE_ID]



def test_a_database_failure_inside_the_grant_gives_the_seat_back(world, monkeypatch):
    boom = RuntimeError("the connection to the database was lost")
    monkeypatch.setattr(world.db, "designworkshopviewer", _ExplodingDelegate(boom))
    token = world.recordaccesstoken.rows[0]

    with pytest.raises(RuntimeError):
        _redeem(_user(ALICE_ID), world.code)

    assert token.usesConsumed == 0, "the card must still admit as many people as it says"
    assert world.recordaccesstokenredemption.rows == []
    assert world.designworkshopprovisionalmember.rows == []


def test_a_cancelled_request_gives_the_seat_back_too(world, monkeypatch):
    monkeypatch.setattr(
        world.db, "designworkshopviewer", _ExplodingDelegate(asyncio.CancelledError())
    )
    token = world.recordaccesstoken.rows[0]

    with pytest.raises(asyncio.CancelledError):
        _redeem(_user(ALICE_ID), world.code)

    assert token.usesConsumed == 0
    assert world.recordaccesstokenredemption.rows == []


def test_the_compensation_does_not_replace_the_failure_it_is_compensating_for(world, monkeypatch):
    monkeypatch.setattr(
        world.db,
        "designworkshopviewer",
        _ExplodingDelegate(RuntimeError("the connection to the database was lost")),
    )

    async def _also_broken(_token_id: str, *, seats_taken: int) -> None:
        raise RuntimeError("and the compensation could not be written either")

    monkeypatch.setattr(grants, "_give_the_seat_back", _also_broken)

    with pytest.raises(RuntimeError, match="the connection to the database was lost"):
        _redeem(_user(ALICE_ID), world.code)
