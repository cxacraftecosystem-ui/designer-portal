import asyncio
import hashlib
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

import app.services.design_workshop_access as access
import app.services.design_workshop_grants as grants
import app.services.design_workshop_posts as posts
import app.services.design_workshop_viewers as viewers

WORKSHOP_ID = "cmgrantcard0000000000000w"
CREATOR_ID = "cmgrantcard0000000000000c"
ISSUER_ID = "cmgrantcard0000000000000i"
ALICE_ID = "cmgrantcard0000000000000a"
BOB_ID = "cmgrantcard0000000000000b"
TOKEN_ID = "cmgrantcard0000000000000t"


def _user(user_id: str, role: str = "DESIGNER") -> SimpleNamespace:
    return SimpleNamespace(
        id=user_id, email=f"{user_id}@example.test", name=user_id.upper(), role=role
    )



class _Delegate:
    def __init__(
        self, rows: list[Any] | None = None, *, defaults: dict[str, Any] | None = None
    ) -> None:
        self.rows = list(rows or [])
        self.defaults = dict(defaults or {})
        self.created: list[dict[str, Any]] = []
        self.updates: list[tuple[dict[str, Any], dict[str, Any]]] = []
        self.deletes: list[dict[str, Any]] = []

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
        for row in self.rows:
            if self._matches(row, where):
                return row
        return None

    async def find_many(
        self, where: dict[str, Any] | None = None, include: Any = None, order: Any = None,
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
            self.rows.append(SimpleNamespace(id=f"row-{len(self.rows)}", **item))
            written += 1
        return written

    async def update(self, where: dict[str, Any], data: dict[str, Any]) -> Any:
        for row in self.rows:
            if self._matches(row, where):
                self.updates.append((dict(where), dict(data)))
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
        if touched:
            self.updates.append((dict(where), dict(data)))
        return touched

    async def delete_many(self, where: dict[str, Any]) -> int:
        keep = [row for row in self.rows if not self._matches(row, where)]
        removed = len(self.rows) - len(keep)
        if removed:
            self.deletes.append(dict(where))
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


def _token(
    *,
    secret: str,
    max_uses: int | None = 1,
    uses_consumed: int = 0,
    expires_in_days: int = 14,
    revoked: bool = False,
    record_type: str = "DESIGN_WORKSHOP",
    record_id: str = WORKSHOP_ID,
) -> SimpleNamespace:
    now = datetime.now(UTC)
    return SimpleNamespace(
        id=TOKEN_ID,
        recordType=record_type,
        recordId=record_id,
        secretHash=hashlib.sha256(secret.encode()).hexdigest(),
        secretLast4=secret[-4:],
        issuedById=ISSUER_ID,
        maxUses=max_uses,
        usesConsumed=uses_consumed,
        expiresAt=now + timedelta(days=expires_in_days),
        revokedAt=now if revoked else None,
        revokedById=ISSUER_ID if revoked else None,
        label="stage-4 batch",
        createdAt=now,
    )


class _Eligibility:
    def __init__(self, *, refuse: str | None = None, status: int = 422) -> None:
        self.refuse = refuse
        self.status = status
        self.calls: list[set[str]] = []
        self.workshops: list[str | None] = []

    async def __call__(self, user_ids: set[str], **context: Any) -> None:
        self.calls.append(set(user_ids))
        self.workshops.append(context.get("workshop_id"))
        if self.refuse is not None:
            raise HTTPException(status_code=self.status, detail=self.refuse)


async def _the_issuer_still_admits(_token: Any, _workshop: Any) -> bool:
    return True


@pytest.fixture
def world(monkeypatch: pytest.MonkeyPatch):
    secret = grants.mint_secret()
    delegates = {
        "designworkshop": _Delegate(
            [
                SimpleNamespace(
                    id=WORKSHOP_ID, title="Kalamkari", createdById=CREATOR_ID, deletedAt=None
                )
            ]
        ),
        "recordaccesstoken": _Delegate(
            [_token(secret=secret)],
            defaults={
                "usesConsumed": 0,
                "revokedAt": None,
                "revokedById": None,
                "label": None,
                "createdAt": datetime.now(UTC),
                "maxUses": 1,
            },
        ),
        "recordaccesstokenredemption": _Delegate(),
        "designworkshopviewer": _Delegate(),
        "designworkshopprovisionalmember": _Delegate(),
        "designworkshopaccessrequest": _Delegate(),
    }
    db = _Db(**delegates)
    for module in (grants, access, viewers):
        monkeypatch.setattr(module, "db", db)
    eligibility = _Eligibility()
    monkeypatch.setattr(grants, "_assert_every_id_may_be_granted", eligibility)
    monkeypatch.setattr(grants, "_issuer_still_admits", _the_issuer_still_admits)

    async def _holds_no_post(workshop_id: str, user_ids: Any) -> dict[str, frozenset[str]]:
        return {}

    monkeypatch.setattr(posts, "supervisory_posts_among", _holds_no_post)
    return SimpleNamespace(
        db=db,
        secret=secret,
        code=grants.encode_join_code(WORKSHOP_ID, secret),
        eligibility=eligibility,
        **delegates,
    )


def _redeem(user: Any, code: str, **kwargs: Any) -> dict[str, Any]:
    return asyncio.run(grants.redeem(user, code=code, **kwargs))



def test_the_record_type_enum_matches_the_code_letters():
    letters = grants.RECORD_TYPE_LETTERS
    assert len(letters) == 10
    assert sorted(letters.values()) == sorted("ACWDSTQMGP")
    assert len(set(letters.values())) == 10, "two record types share a letter"
    assert grants.JOIN_LETTER not in letters.values(), (
        "J must NOT be a record-type letter: a join card is a credential, not a locator, and the "
        "record-lookup path must have no entry it could resolve"
    )


def test_a_join_card_is_sixty_characters_and_survives_a_round_trip():
    secret = grants.mint_secret()
    code = grants.encode_join_code(WORKSHOP_ID, secret)
    assert len(code) == 60, code
    record_id, decoded_secret, canonical = grants.decode_join_code(code)
    assert (record_id, decoded_secret) == (WORKSHOP_ID, secret)
    assert canonical == code
    grouped = " ".join(code[i : i + 4] for i in range(0, len(code), 4))
    assert grants.decode_join_code(grouped.lower()) == (WORKSHOP_ID, secret, code)


def test_a_secret_is_a_hundred_and_ten_bits_from_a_csprng():
    first, second = grants.mint_secret(), grants.mint_secret()
    assert len(first) == 22 and len(second) == 22
    assert first != second
    assert set(first) <= set("0123456789ABCDEFGHJKMNPQRSTVWXYZ")


@pytest.mark.parametrize(
    "code",
    [
        "DPW2:J:CMGRANTCARD0000000000000W.AAAAAAAAAAAAAAAAAAAAAA:AAAA",
        "DPW2:J:CMGRANTCARD0000000000000W.AAAAAAAAAAAAAAAAAAAAA:AAAA",
        "DPW2:J:CMGRANTCARD0000000000000W.UUUUUUUUUUUUUUUUUUUUUU:AAAA",
        "DPW1:G:CMGRANTCARD0000000000000W:AAAA",
        "DPW2:J:LOCAL-3F2504E0-4F89-11D3-9A0C-0305E82C3301.AAAAAAAAAAAAAAAAAAAAAA:AAAA",
        "DPW2:J:DWLOCAL-3F2504E0-4F89-11D3-9A0C-0305E82C3301.AAAAAAAAAAAAAAAAAAAAAA:AAAA",
        "https://example.org/scan-me",
        "",
    ],
)
def test_a_damaged_or_foreign_card_is_refused_by_the_grammar_alone(code):
    with pytest.raises(access.ScannedCodeRefused):
        grants.decode_join_code(code)


def test_a_record_tag_scanned_at_the_join_door_is_sent_to_the_right_place():
    with pytest.raises(access.ScannedCodeRefused) as refusal:
        grants.decode_join_code("DPW1:G:CMGRANTCARD0000000000000W:AAAA")
    assert "join card" in str(refusal.value.detail).lower()

    card = grants.encode_join_code(WORKSHOP_ID, grants.mint_secret())
    with pytest.raises(access.ScannedCodeRefused) as other:
        access.decode_design_workshop_code(card)
    detail = str(other.value.detail)
    assert "join card" in detail.lower()
    assert card not in detail
    assert grants.decode_join_code(card)[1] not in detail


def test_a_redacted_code_never_carries_the_secret():
    secret = grants.mint_secret()
    stored = grants.redacted_code(WORKSHOP_ID, secret)
    assert secret not in stored
    assert secret[:-4] not in stored
    assert secret[-4:] in stored
    assert WORKSHOP_ID.upper() in stored
    prefix, _, check = stored.rpartition(":")
    assert check == access.code_check(prefix)



def test_a_forged_card_is_refused_and_nothing_at_all_is_written(world):
    forged = grants.encode_join_code(WORKSHOP_ID, grants.mint_secret())
    assert grants.decode_join_code(forged)

    with pytest.raises(grants.CardRefused):
        _redeem(_user(ALICE_ID), forged)

    assert world.recordaccesstokenredemption.rows == []
    assert world.designworkshopprovisionalmember.rows == []
    assert world.designworkshopaccessrequest.rows == []
    assert world.designworkshopviewer.rows == []
    assert world.recordaccesstoken.rows[0].usesConsumed == 0
    assert world.eligibility.calls == []


def test_a_revoked_card_is_refused_and_writes_nothing(world):
    world.recordaccesstoken.rows[0].revokedAt = datetime.now(UTC)
    with pytest.raises(grants.CardRefused):
        _redeem(_user(ALICE_ID), world.code)
    assert world.recordaccesstokenredemption.rows == []
    assert world.designworkshopprovisionalmember.rows == []


def test_a_card_naming_a_deleted_workshop_is_refused(world):
    world.designworkshop.rows[0].deletedAt = datetime.now(UTC)
    with pytest.raises(grants.CardRefused):
        _redeem(_user(ALICE_ID), world.code)
    assert world.recordaccesstokenredemption.rows == []


def test_a_card_for_another_kind_of_record_cannot_grant_workshop_membership(world):
    world.recordaccesstoken.rows[0].recordType = "TOOL"
    with pytest.raises(grants.CardRefused):
        _redeem(_user(ALICE_ID), world.code)
    assert world.designworkshopviewer.rows == []



def test_an_expired_card_never_becomes_a_full_grant(world):
    token = world.recordaccesstoken.rows[0]
    token.expiresAt = datetime.now(UTC) - timedelta(days=2)

    result = _redeem(
        _user(ALICE_ID),
        world.code,
        scanned_at_client=datetime.now(UTC) - timedelta(days=14),
    )

    assert result["outcome"] == "PROVISIONAL"
    assert result["reason"] == "EXPIRED"
    assert token.usesConsumed == 0, "an expired card must not spend a seat"
    assert world.designworkshopviewer.rows == [], "an expired card must not produce a viewer row"
    assert world.eligibility.calls == [], "the grant must not even be reached for"
    assert [row.status for row in world.designworkshopaccessrequest.rows] == ["PENDING"]
    assert result["detail"] == EXPIRED_DETAIL
    assert "already been used" not in result["detail"]
    replay = _redeem(_user(ALICE_ID), world.code)
    assert (replay["outcome"], replay["reason"], replay["detail"]) == (
        "PROVISIONAL",
        "EXPIRED",
        EXPIRED_DETAIL,
    )


EXPIRED_DETAIL = (
    "That card's date had passed by the time your scan reached us, so you are not on the workshop "
    "yet — but nothing you record is lost, and the card was not used up. You can keep capturing "
    "your own work here and an administrator can see that you scanned the card; once they confirm "
    "you, everything you have recorded is already in place. Until then you will not see anybody "
    "else's stages."
)


def test_an_expired_card_beyond_the_sync_grace_is_refused_outright(world):
    token = world.recordaccesstoken.rows[0]
    token.expiresAt = datetime.now(UTC) - timedelta(days=grants.GRANT_SYNC_GRACE_DAYS + 1)

    with pytest.raises(grants.CardRefused):
        _redeem(_user(ALICE_ID), world.code)

    assert world.recordaccesstokenredemption.rows == []
    assert world.designworkshopprovisionalmember.rows == []
    assert token.usesConsumed == 0



def test_a_single_use_card_admits_exactly_one_person_and_does_not_refuse_the_second(world):
    token = world.recordaccesstoken.rows[0]
    now = datetime.now(UTC)
    alice_scanned = now - timedelta(hours=5)
    bob_scanned = now - timedelta(hours=6)

    bob = _redeem(_user(BOB_ID), world.code, scanned_at_client=bob_scanned)
    alice = _redeem(_user(ALICE_ID), world.code, scanned_at_client=alice_scanned)

    assert bob["outcome"] == "FULL" and bob["reason"] == "OK"
    assert alice["outcome"] == "PROVISIONAL" and alice["reason"] == "ALREADY_SPENT"
    assert alice["detail"].startswith("That card had already been used"), alice["detail"]

    assert token.usesConsumed == 1
    assert token.maxUses == 1
    assert [row.userId for row in world.designworkshopviewer.rows] == [BOB_ID]

    assert [row.userId for row in world.designworkshopprovisionalmember.rows] == [ALICE_ID]
    alice_request = next(
        row for row in world.designworkshopaccessrequest.rows if row.requestedById == ALICE_ID
    )
    assert alice_request.status == "PENDING"
    assert alice_request.tokenId == TOKEN_ID

    alice_redemption = next(
        row for row in world.recordaccesstokenredemption.rows if row.userId == ALICE_ID
    )
    assert alice_redemption.scannedAtClient == alice_scanned
    assert alice_redemption.serverArrivedAt > alice_scanned
    assert alice_redemption.outcome == "PROVISIONAL"
    assert alice_request.scannedAt == alice_scanned


def test_the_same_person_scanning_twice_does_not_spend_a_second_seat(world):
    token = world.recordaccesstoken.rows[0]
    token.maxUses = 5

    first = _redeem(_user(ALICE_ID), world.code)
    second = _redeem(_user(ALICE_ID), world.code)

    assert first["outcome"] == "FULL"
    assert second["outcome"] == "ALREADY_A_MEMBER"
    assert token.usesConsumed == 1, "a replay must not consume a second seat"
    assert len(world.recordaccesstokenredemption.rows) == 1
    assert len(world.eligibility.calls) == 1


def test_a_provisional_holder_redelivering_the_same_card_gets_the_first_answer_back(world):
    token = world.recordaccesstoken.rows[0]
    token.maxUses = 5
    token.usesConsumed = 5
    first = _redeem(_user(ALICE_ID), world.code)
    assert first["outcome"] == "PROVISIONAL" and first["reason"] == "ALREADY_SPENT"

    token.usesConsumed = 1
    second = _redeem(_user(ALICE_ID), world.code)

    assert second["outcome"] == "PROVISIONAL", "a replay is not an upgrade"
    assert second["reason"] == "ALREADY_SPENT"
    assert token.usesConsumed == 1, "a replay must not consume a seat"
    assert len(world.recordaccesstokenredemption.rows) == 1
    assert len(world.eligibility.calls) == 1
    assert world.eligibility.calls == [{ALICE_ID}]
    assert world.eligibility.workshops == [WORKSHOP_ID]


def test_a_member_scanning_the_card_at_the_wall_does_not_burn_the_invitation(world):
    token = world.recordaccesstoken.rows[0]
    world.designworkshopviewer.rows.append(
        SimpleNamespace(
            designWorkshopId=WORKSHOP_ID,
            userId=ALICE_ID,
            grantedById=None,
            tokenId=None,
            createdAt=datetime.now(UTC),
        )
    )

    result = _redeem(_user(ALICE_ID), world.code)

    assert result["outcome"] == "ALREADY_A_MEMBER"
    assert token.usesConsumed == 0
    assert world.recordaccesstokenredemption.rows == []
    assert world.designworkshopaccessrequest.rows == []


def test_the_creator_and_an_admin_are_already_members_too(world):
    token = world.recordaccesstoken.rows[0]
    assert _redeem(_user(CREATOR_ID), world.code)["outcome"] == "ALREADY_A_MEMBER"
    assert _redeem(_user("cmadmin00000000000000000x", "ADMIN"), world.code)["outcome"] == (
        "ALREADY_A_MEMBER"
    )
    assert token.usesConsumed == 0



def test_a_full_redemption_writes_one_viewer_row_for_one_account_and_nothing_else(world):
    result = _redeem(_user(ALICE_ID), world.code)
    assert result["outcome"] == "FULL"

    assert world.eligibility.calls == [{ALICE_ID}], (
        "the redeemer and nobody else: validating the whole workshop is how a colleague's lapsed "
        "empanelment came to refuse an unrelated induction"
    )

    assert len(world.designworkshopviewer.rows) == 1
    row = world.designworkshopviewer.rows[0]
    assert row.userId == ALICE_ID
    assert row.designWorkshopId == WORKSHOP_ID
    assert row.grantedById == ISSUER_ID, "requirement 4: the card's issuer, nobody else"
    assert row.tokenId == TOKEN_ID, "which card"

    assert len(world.designworkshopviewer.created) == 1
    written = world.designworkshopviewer.created[0]
    assert written["tokenId"] == TOKEN_ID
    assert written["grantedById"] == ISSUER_ID

    redemption = world.recordaccesstokenredemption.rows[0]
    assert (redemption.tokenId, redemption.userId) == (TOKEN_ID, ALICE_ID)
    assert redemption.outcome == "FULL" and redemption.reason == "OK"
    assert redemption.serverArrivedAt is not None


def test_the_offline_evidence_is_all_recorded_and_none_of_it_decides_anything(world):
    scanned = datetime(2026, 8, 20, 6, 30, tzinfo=UTC)
    result = _redeem(
        _user(ALICE_ID),
        world.code,
        scanned_at_client=scanned,
        scanned_at_elapsed_sec=1,
        synced_at_elapsed_sec=345_601,
        boot_id="3f2504e0-4f89-11d3-9a0c-0305e82c3301",
        clock_jump_observed=True,
    )
    assert result["outcome"] == "FULL"
    row = world.recordaccesstokenredemption.rows[0]
    assert row.scannedAtClient == scanned
    assert row.scannedAtElapsedSec == 1
    assert row.syncedAtElapsedSec == 345_601
    assert row.bootId == "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    assert row.clockJumpObserved is True
    assert row.serverArrivedAt > scanned


def test_a_card_scanned_by_an_ineligible_account_never_spends_its_seat(world, monkeypatch):
    monkeypatch.setattr(
        grants,
        "_assert_every_id_may_be_granted",
        _Eligibility(refuse="ALICE is not on the ACTIVE designer roster."),
    )
    token = world.recordaccesstoken.rows[0]

    result = _redeem(_user(ALICE_ID), world.code)

    assert result["outcome"] == "PROVISIONAL"
    assert result["reason"] == "INELIGIBLE"
    assert token.usesConsumed == 0, "a card must not be spent on a grant that did not happen"
    assert world.designworkshopviewer.rows == []
    assert [row.userId for row in world.designworkshopprovisionalmember.rows] == [ALICE_ID]
    assert "roster" not in result["detail"], "the refusal's own words must not reach the scanner"
    _assert_the_ineligible_answer(result["detail"])

    replay = _redeem(_user(ALICE_ID), world.code)
    assert (replay["outcome"], replay["reason"]) == ("PROVISIONAL", "INELIGIBLE")
    assert replay["detail"] == result["detail"]


INELIGIBLE_DETAIL = (
    "That card cannot put your account on this workshop by itself, so you are not on it yet — but "
    "nothing you record is lost, and the card was not used up. You can keep capturing your own "
    "work here and an administrator can see that you scanned the card; if they add you, everything "
    "you have recorded is already in place. Until then you will not see anybody else's stages."
)


def _assert_the_ineligible_answer(detail: str) -> None:
    assert detail == INELIGIBLE_DETAIL
    assert "already been used" not in detail, "the card was never spent; the answer must not say so"


def test_a_post_holder_scanning_the_card_lands_ineligible_and_keeps_the_seat(world, monkeypatch):
    eligibility = _Eligibility(
        refuse=(
            "ALICE (alice@example.test) is this workshop's inspector, so they cannot also be given "
            "designer access to it: whoever inspects or supervises a workshop does not write it."
        ),
        status=409,
    )
    monkeypatch.setattr(grants, "_assert_every_id_may_be_granted", eligibility)
    token = world.recordaccesstoken.rows[0]

    result = _redeem(_user(ALICE_ID), world.code)

    assert (result["outcome"], result["reason"]) == ("PROVISIONAL", "INELIGIBLE")
    assert token.usesConsumed == 0, "a post holder's scan spent the card's only seat"
    assert world.designworkshopviewer.rows == [], "a post holder was given designer access"
    assert [row.userId for row in world.recordaccesstokenredemption.rows] == [ALICE_ID]
    assert eligibility.workshops == [WORKSHOP_ID], "the rule was not asked about THIS workshop"
    assert "inspector" not in result["detail"], "the refusal's own words must not reach the scanner"
    _assert_the_ineligible_answer(result["detail"])


def test_a_fault_in_the_eligibility_read_is_not_read_as_ineligible(world, monkeypatch):
    monkeypatch.setattr(
        grants,
        "_assert_every_id_may_be_granted",
        _Eligibility(refuse="the database went away", status=500),
    )
    token = world.recordaccesstoken.rows[0]

    with pytest.raises(HTTPException) as raised:
        _redeem(_user(ALICE_ID), world.code)

    assert raised.value.status_code == 500
    assert token.usesConsumed == 0
    assert world.designworkshopviewer.rows == []
    assert world.recordaccesstokenredemption.rows == []
    assert world.designworkshopprovisionalmember.rows == []


def test_a_card_supersedes_an_administrators_earlier_refusal_and_says_so(world, caplog):
    world.designworkshopaccessrequest.rows.append(
        SimpleNamespace(
            id="prior-row",
            designWorkshopId=WORKSHOP_ID,
            requestedById=ALICE_ID,
            status="DENIED",
            source="MANUAL",
            scannedCode=None,
            tokenId=None,
            scannedAt=None,
            note=None,
            decidedById="cmadmin00000000000000000x",
            decidedAt=datetime.now(UTC) - timedelta(days=30),
            decisionNote="not on this cluster",
            createdAt=datetime.now(UTC) - timedelta(days=31),
        )
    )
    with caplog.at_level("WARNING"):
        result = _redeem(_user(ALICE_ID), world.code)

    assert result["outcome"] == "FULL"
    row = world.designworkshopaccessrequest.rows[0]
    assert row.status == "GRANTED"
    assert row.decidedById == "cmadmin00000000000000000x"
    assert row.decisionNote == "not on this cluster"
    assert row.decidedAt is not None
    assert row.tokenId == TOKEN_ID
    assert any("previously REFUSED" in record.message for record in caplog.records), (
        "an administrator whose decision was overridden must be able to find out"
    )



def _mint(user: Any, **kwargs: Any) -> dict[str, Any]:
    body = {
        "record_type": "DESIGN_WORKSHOP",
        "record_id": WORKSHOP_ID,
        "max_uses": 1,
        "days_valid": None,
        "label": None,
    }
    body.update(kwargs)
    return asyncio.run(grants.mint_grant(user, **body))


@pytest.mark.parametrize("max_uses", [2, 20, None])
@pytest.mark.parametrize("role", ["DESIGNER", "RESEARCHER", "PROFESSOR"])
def test_only_an_admin_may_print_a_card_for_more_than_one_person(world, role, max_uses):
    world.designworkshopviewer.rows.append(
        SimpleNamespace(
            designWorkshopId=WORKSHOP_ID, userId=ISSUER_ID, grantedById=None, tokenId=None,
            createdAt=datetime.now(UTC),
        )
    )
    with pytest.raises(HTTPException) as refusal:
        _mint(_user(ISSUER_ID, role), max_uses=max_uses)
    assert refusal.value.status_code == 403
    assert world.recordaccesstoken.created == [], "no card may be minted by a refused call"


@pytest.mark.parametrize("role", ["ADMIN", "MASTER_ADMIN"])
def test_an_admin_may_print_a_card_for_a_group(world, role):
    minted = _mint(_user("cmadmin00000000000000000x", role), max_uses=12)
    assert minted["maxUses"] == 12
    assert minted["usesConsumed"] == 0
    record_id, secret, _canonical = grants.decode_join_code(minted["code"])
    assert record_id == WORKSHOP_ID
    written = world.recordaccesstoken.created[0]
    assert written["secretHash"] == grants.secret_hash(secret)
    assert written["secretLast4"] == secret[-4:]
    assert secret not in str(written)


def test_a_designer_on_the_workshop_may_print_a_single_use_card(world):
    world.designworkshopviewer.rows.append(
        SimpleNamespace(
            designWorkshopId=WORKSHOP_ID, userId=ISSUER_ID, grantedById=None, tokenId=None,
            createdAt=datetime.now(UTC),
        )
    )
    minted = _mint(_user(ISSUER_ID), max_uses=1)
    assert minted["maxUses"] == 1
    assert world.recordaccesstoken.created[0]["issuedById"] == ISSUER_ID


def test_a_stranger_gets_the_ordinary_404_rather_than_a_403(world):
    with pytest.raises(HTTPException) as refusal:
        _mint(_user("cmstranger00000000000000z"))
    assert refusal.value.status_code == 404
    assert refusal.value.detail == "Record not found"


def test_a_card_cannot_be_printed_for_a_record_that_has_no_membership_to_grant(world):
    for record_type in ("ARTISAN", "TOOL", "PRODUCT", "PROTOTYPE"):
        with pytest.raises(HTTPException) as refusal:
            _mint(_user("cmadmin00000000000000000x", "ADMIN"), record_type=record_type)
        assert refusal.value.status_code == 422
        assert "design workshop" in str(refusal.value.detail)
    assert world.recordaccesstoken.created == []


def test_a_non_admin_is_capped_on_outstanding_cards(world):
    world.designworkshopviewer.rows.append(
        SimpleNamespace(
            designWorkshopId=WORKSHOP_ID, userId=ISSUER_ID, grantedById=None, tokenId=None,
            createdAt=datetime.now(UTC),
        )
    )
    now = datetime.now(UTC)
    world.recordaccesstoken.rows = [
        SimpleNamespace(
            id=f"loose-{n}",
            recordType="DESIGN_WORKSHOP",
            recordId=WORKSHOP_ID,
            issuedById=ISSUER_ID,
            revokedAt=None,
            expiresAt=now + timedelta(days=10),
            usesConsumed=0,
            maxUses=1,
        )
        for n in range(grants.VIEWER_OUTSTANDING_GRANT_LIMIT)
    ]
    with pytest.raises(HTTPException) as refusal:
        _mint(_user(ISSUER_ID), max_uses=1)
    assert refusal.value.status_code == 409

    world.recordaccesstoken.rows[0].usesConsumed = 1
    assert _mint(_user(ISSUER_ID), max_uses=1)["maxUses"] == 1


def test_an_admin_is_not_capped(world):
    now = datetime.now(UTC)
    world.recordaccesstoken.rows = [
        SimpleNamespace(
            id=f"loose-{n}", recordType="DESIGN_WORKSHOP", recordId=WORKSHOP_ID,
            issuedById="cmadmin00000000000000000x", revokedAt=None,
            expiresAt=now + timedelta(days=10), usesConsumed=0, maxUses=1,
        )
        for n in range(grants.VIEWER_OUTSTANDING_GRANT_LIMIT + 5)
    ]
    assert _mint(_user("cmadmin00000000000000000x", "ADMIN"))["maxUses"] == 1


def test_a_card_always_has_an_end_date(world):
    admin = _user("cmadmin00000000000000000x", "ADMIN")
    minted = _mint(admin)
    expires = datetime.fromisoformat(minted["expiresAt"])
    assert timedelta(days=grants.DEFAULT_GRANT_DAYS - 1) < expires - datetime.now(UTC)
    assert expires - datetime.now(UTC) <= timedelta(days=grants.DEFAULT_GRANT_DAYS)

    with pytest.raises(HTTPException) as refusal:
        _mint(admin, days_valid=grants.MAX_GRANT_DAYS + 1)
    assert refusal.value.status_code == 422
