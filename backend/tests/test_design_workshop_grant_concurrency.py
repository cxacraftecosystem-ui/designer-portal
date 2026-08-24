"""What a redemption may and may not do to **somebody else's** access. **No database required.**

Every assertion here is a sequence that used to end with a person losing access nobody took away.

-- WHY THIS IS A MODULE OF ITS OWN ---------------------------------------------------------------

``test_design_workshop_grant_tokens`` asserts what ONE redemption writes. This asserts what a
redemption does to rows it did not create, which needs a different fixture: two redeemers interleaved
at a chosen point, and a viewer table another actor has changed underneath. Folding the two together
would mean a fake that is both "answers queries" and "answers queries differently on the second
call", and the second behaviour would then silently apply to forty tests that are not about it.

UNGATED, LIKE ITS SIBLING, AND FOR THE REASON THAT MODULE STATES AT LENGTH: twenty-odd modules under
``backend/tests`` skip themselves without a local Postgres, which is every CI run. A concurrency
property that only ran on a developer's laptop is a property that stops being checked.

-- WHAT THE FAKE CAN AND CANNOT SHOW ------------------------------------------------------------

It cannot show a real race: there is one event loop and no second connection. What it CAN show — and
what the bug actually was — is that the redemption's write is **not derived from a read of anybody
else's rows**. So the sequences below interleave deterministically at the exact point the old code
read the viewer set, and assert that the second redeemer's write leaves the first one's row alone.
That is the property, and it is the property whether or not two requests ever truly overlap: a
statement that names one account cannot remove another.

-- THE TWO SEQUENCES, IN THE WORDS OF THE FAILURE -----------------------------------------------

1. **TWO REDEEMERS OF A MULTI-USE CARD, SYNCING AT ONCE** — the offline-batch case this whole feature
   exists for. Both read an empty viewer set; the first adds itself; the second hands over "everything
   I read, plus me", and a whole-set replace deletes the first one's row. The redemption said ``FULL``,
   the queue row said ``GRANTED``, ``tokenId`` was stamped, and the person had no access — with nothing
   on any screen that would ever correct it.

2. **A SCAN CONCURRENT WITH AN ADMIN REMOVING SOMEBODY.** The admin saves the viewers screen without
   designer X. A redemption whose read still held X puts X back, with the card issuer's id in
   ``grantedById`` and a NULL ``tokenId`` — a revocation undone by an unrelated person's scan, and a
   provenance trail naming an issuer for a row no card produced.
"""

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
    """One model delegate over a list of rows. Narrow on purpose — see the sibling module.

    THE ONE THING IT SIMULATES THAT ITS SIBLING DOES NOT is the UNIQUE INDEX on
    ``(designWorkshopId, userId)`` for the viewer table, because "a second write for the same pair
    changes nothing" is one of this file's assertions.
    """

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
            # WHICH PAIR IS THE KEY DEPENDS ON THE TABLE, and getting that wrong is how a fake
            # cheerfully writes a second row for a pair Postgres would have refused. The viewer and
            # provisional tables are keyed on (designWorkshopId, userId) and CARRY a nullable
            # `tokenId`; the redemption table is keyed on (tokenId, userId) and has no workshop
            # column. Including `tokenId` in the first case made a row whose token differed look
            # like a different row — which is exactly the duplicate the primary key exists to refuse.
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
    """A delegate whose first write raises whatever a dead connection would raise.

    NOT AN ``HTTPException``, WHICH IS THE ENTIRE POINT. The handler around the grant used to catch
    only that, so a Prisma or connection error left the seat consumed and nothing else written at all.
    """

    def __init__(self, error: BaseException) -> None:
        super().__init__()
        self.error = error

    async def create_many(self, data: list[dict[str, Any]], skip_duplicates: bool = False) -> int:
        raise self.error


@pytest.fixture
def world(monkeypatch: pytest.MonkeyPatch):
    """A workshop, one card with several seats, and every delegate the service reaches for."""
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
                    # SEVERAL SEATS, because the sequence under test is two people BOTH getting a
                    # full grant. With one seat the second redeemer takes the provisional path and the
                    # destructive write never happens — which is exactly why the bug was invisible to
                    # the single-use test that already existed.
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

    async def _every_id_is_fine(_user_ids: set[str]) -> None:
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


# --------------------------------------------------------------------------------------
# 1. Two redeemers of one multi-use card
# --------------------------------------------------------------------------------------


def test_a_second_redeemer_does_not_delete_the_first_redeemers_viewer_row(world):
    """**THE FAILURE THAT SHIPPED: a full grant that quietly revoked somebody else's.**

    Bob and Alice both scan the same multi-use card in a courtyard, and both handsets sync when signal
    returns — which is the routine path for this feature, not an edge case. Both are told ``FULL``.

    THE OLD CODE READ THE WHOLE VIEWER SET AND WROTE IT BACK. Alice's read did not contain Bob (or
    contained him, depending on the microsecond), and ``replace_viewers`` deleted the difference. So
    Bob's redemption said ``FULL``, his queue row said ``GRANTED``, his viewer row carried
    ``tokenId``, and he could not open the workshop — and no screen anywhere compares those four
    things, so nothing would ever have reported it.

    The fix is that a redemption writes ONE row naming ONE account, so there is no difference to
    delete. This asserts the outcome of that: two people, two rows, both still there.
    """
    bob = _redeem(_user(BOB_ID), world.code)
    alice = _redeem(_user(ALICE_ID), world.code)

    assert bob["outcome"] == "FULL"
    assert alice["outcome"] == "FULL"

    held = {row.userId for row in world.designworkshopviewer.rows}
    assert held == {BOB_ID, ALICE_ID}, "a full grant must not take away another full grant"

    # AND NOTHING WAS DELETED AT ALL. The stronger statement, and the one that keeps holding when a
    # third redeemer arrives: the grant path issues no delete against this table.
    assert world.designworkshopviewer.deleted == []

    # BOTH ROWS CARRY THE WHOLE TRAIL. A row whose `tokenId` went missing is a row whose card cannot
    # be revoked with the batch it belongs to.
    for row in world.designworkshopviewer.rows:
        assert row.tokenId == TOKEN_ID
        assert row.grantedById == ISSUER_ID

    assert world.recordaccesstoken.rows[0].usesConsumed == 2


def test_a_redemption_leaves_a_viewer_it_has_never_heard_of_alone(world):
    """A colleague who was already on the workshop is untouched by somebody else's scan.

    The plainest form of the same property, and the one a reader can check without thinking about
    concurrency at all: Carol is a viewer, Alice redeems a card, Carol is still a viewer with her own
    ``grantedById`` and her own NULL ``tokenId``. The old whole-set write had to be handed Carol's id
    to avoid deleting her, which meant it had to have READ her — and everything that read implied.
    """
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
    # `grantedAt` IS THE ONLY ANSWER TO "how long has this person been on this workshop", which is why
    # a redemption restamping it would be a quiet loss of the one fact the column exists for.
    assert carol.createdAt == carol_granted_at


def test_a_redemption_does_not_resurrect_a_viewer_an_admin_has_removed(world):
    """**THE SECOND FAILURE: a revocation undone by an unrelated person's scan.**

    An admin takes designer X off the workshop on the viewers screen. A redemption that read the set
    before that save then wrote it back — and X was a viewer again, with ``grantedById`` naming the
    card's issuer and ``tokenId`` NULL: a row no card produced, attributed to the person who printed
    one. Nobody would look for it, because nobody did anything.

    The sequence is written here as the interleaving it really is: the removal happens between the
    read the old code made and the write it made afterwards. With an additive write there is no such
    window to place it in, so this test can only be expressed as "the removal sticks".
    """
    world.designworkshopviewer.rows.append(
        SimpleNamespace(
            designWorkshopId=WORKSHOP_ID,
            userId=CAROL_ID,
            grantedById=CREATOR_ID,
            tokenId=None,
            createdAt=datetime.now(UTC),
        )
    )
    # THE ADMIN'S SAVE. Removing a viewer is the viewers PUT and only the viewers PUT — one way in and
    # one way out — so the removal is spelled as the delete that endpoint performs.
    world.designworkshopviewer.rows = [
        row for row in world.designworkshopviewer.rows if row.userId != CAROL_ID
    ]

    assert _redeem(_user(ALICE_ID), world.code)["outcome"] == "FULL"

    held = {row.userId for row in world.designworkshopviewer.rows}
    assert held == {ALICE_ID}, "a scan must not undo an administrator's removal"


def test_a_race_with_an_admin_granting_the_same_person_settles_to_one_row(world):
    """The one collision that remains, and it settles the honest way.

    An admin grants Alice from the viewers screen in the same second that Alice scans a card. The
    write is ``create_many(skip_duplicates=True)`` against a pair that is the primary key, so the
    admin's row stands and the redemption writes nothing — no 500 on a duplicate key, and no second
    row.

    **WHAT THAT MEANS FOR THE PROVENANCE, STATED BECAUSE IT LOOKS LIKE A LOSS AND IS NOT.** The row
    keeps the admin's ``grantedById`` and a NULL ``tokenId``, so the viewers screen reads "an
    administrator added them" — which is TRUE, and is what happened first. The card's own receipt is
    the ``RecordAccessTokenRedemption`` row, which is written either way and is where "this card
    admitted this person" actually lives.
    """
    world.designworkshopviewer.rows.append(
        SimpleNamespace(
            designWorkshopId=WORKSHOP_ID,
            userId=ALICE_ID,
            grantedById=CREATOR_ID,
            tokenId=None,
            createdAt=datetime.now(UTC),
        )
    )
    # NOT `ALREADY_A_MEMBER`: that branch reads `has_viewer_grant` at the top of `redeem`, and the
    # point of this test is the collision that happens AFTER that read — so the row is added here,
    # after the membership check would have run. `_consume_a_seat` is what serialises the seat; this
    # is what happens to the row.
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


# --------------------------------------------------------------------------------------
# 2. The upgrade requirement 6 promises
# --------------------------------------------------------------------------------------


def test_a_later_valid_scan_clears_the_provisional_foothold_it_upgrades(world):
    """**REQUIREMENT 6's "a later valid scan upgrades them", and the contradiction it used to leave.**

    Somebody scans a spent card and gets a capture-only foothold. Later they are handed a fresh card
    and scan that, and it grants in full. The foothold has to go with the promotion, for the reason
    ``decide``'s GRANT arm gives in its own docstring: "the foothold must go with it or the same person
    is in two membership tables at once and every screen has to pick one."

    IT DID NOT GO. So ``request_payload`` reported ``requesterHasAccess: true`` AND
    ``requesterIsProvisional: true`` for one person — precisely the pair ``requesterIsProvisional``'s
    comment argues must never occur — and ``may_capture``'s three arms stopped agreeing about which
    fact had admitted them.

    NOTHING THEY CAPTURED IS DESTROYED BY THE DELETE: ``DwStageEntry`` cascades from
    ``DesignWorkshop`` and not from the foothold, which is what makes this a promotion rather than a
    loss.
    """
    token = world.recordaccesstoken.rows[0]
    token.usesConsumed = token.maxUses  # spent: the first scan is a late-comer

    late = _redeem(_user(ALICE_ID), world.code)
    assert late["outcome"] == "PROVISIONAL" and late["reason"] == "ALREADY_SPENT"
    assert [row.userId for row in world.designworkshopprovisionalmember.rows] == [ALICE_ID]

    # A FRESH CARD, which is a different token and therefore a different redemption row — the replay
    # guard is on `(tokenId, userId)`, so re-scanning the SAME spent card is correctly a no-op and
    # would test nothing about the upgrade.
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

    # AND THE QUEUE ROW FOLLOWS REALITY, with `decidedById` left NULL beside a `tokenId` that names
    # which card — the pair that tells an admin no person answered this.
    request = next(
        row for row in world.designworkshopaccessrequest.rows if row.requestedById == ALICE_ID
    )
    assert request.status == "GRANTED"
    assert request.tokenId == "cmgrantrace0000000000002t"
    assert getattr(request, "decidedById", None) is None


def test_an_ordinary_full_grant_deletes_no_foothold_and_says_nothing_about_it(world):
    """The common case: somebody who never scanned a spent card.

    The delete is unconditional and idempotent — ``delete_many`` on a pair that usually has no row —
    rather than read-then-delete, which is the same call ``decide`` makes and for the same reason this
    repository gives everywhere: two round trips with a window in the middle. There is nothing to
    report when it deletes nothing.
    """
    assert _redeem(_user(ALICE_ID), world.code)["outcome"] == "FULL"
    assert world.designworkshopprovisionalmember.rows == []
    assert [row.userId for row in world.designworkshopviewer.rows] == [ALICE_ID]


# --------------------------------------------------------------------------------------
# 3. The grant that failed for a reason nobody wrote a branch for
# --------------------------------------------------------------------------------------


def test_a_database_failure_inside_the_grant_gives_the_seat_back(world, monkeypatch):
    """**A single-use card must not die for a grant that never happened.**

    The handler around the grant used to be ``except HTTPException``. A Prisma error, a dropped
    connection, a statement timeout — none of those is an ``HTTPException``, so the seat stayed
    consumed with no viewer row, no redemption row, no queue row and no foothold. The caller got a
    500, and a single-use card was permanently spent on nobody.

    THE SEAT COMES BACK AND THE ERROR IS RE-RAISED, which is the honest pair. It is not folded into
    the provisional path: writing a foothold needs the same database that just refused to write the
    grant, so "recover by recording something else" is a promise this branch cannot keep. The card
    still works, so the retry — which the Android induction queue performs by itself — turns this into
    an induction rather than a dead card.
    """
    boom = RuntimeError("the connection to the database was lost")
    monkeypatch.setattr(world.db, "designworkshopviewer", _ExplodingDelegate(boom))
    token = world.recordaccesstoken.rows[0]

    with pytest.raises(RuntimeError):
        _redeem(_user(ALICE_ID), world.code)

    assert token.usesConsumed == 0, "the card must still admit as many people as it says"
    assert world.recordaccesstokenredemption.rows == []
    assert world.designworkshopprovisionalmember.rows == []


def test_a_cancelled_request_gives_the_seat_back_too(world, monkeypatch):
    """A handset that walks out of signal mid-request cancels the task. The seat must still return.

    ``asyncio.CancelledError`` is a ``BaseException`` and not an ``Exception``, so a handler written as
    ``except Exception`` would let a cancellation through with the seat spent. That is not a theoretical
    distinction on this route: the caller is a phone on a village connection, and a client disconnect is
    the ordinary way a request ends there.
    """
    monkeypatch.setattr(
        world.db, "designworkshopviewer", _ExplodingDelegate(asyncio.CancelledError())
    )
    token = world.recordaccesstoken.rows[0]

    with pytest.raises(asyncio.CancelledError):
        _redeem(_user(ALICE_ID), world.code)

    assert token.usesConsumed == 0
    assert world.recordaccesstokenredemption.rows == []


def test_the_compensation_does_not_replace_the_failure_it_is_compensating_for(world, monkeypatch):
    """If giving the seat back ALSO fails, the original error is what the caller sees.

    A grant that died because the connection died will very often be followed by a compensation that
    dies the same way. Letting that second failure propagate would hide the exception that actually
    explains the request, and send whoever reads the log at 3am to debug the compensation instead of
    the fault. The seat is then lost in the safe direction: the card admits one fewer person.
    """
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
