"""The security properties of a printed join card, each as an assertion. **No database required.**

WHY THIS MODULE IS UNGATED, WHICH MATTERS MORE HERE THAN ANYWHERE ELSE IN THIS DIRECTORY. Twenty-odd
modules under ``backend/tests`` skip themselves without a local Postgres — which is every CI run,
deliberately, because the deployed database is not a scratch pad. So a test that needed one would
never make its assertion on the machine that gates a merge, and the properties in this file are
exactly the ones that must not silently stop being checked:

* a forged card is refused, and **nothing is written**;
* an expired card never becomes a full grant;
* a single-use card admits exactly one person, however many people scan it;
* a provisional foothold is **not** a viewer row and does not satisfy ``has_viewer_grant``;
* the provenance recorded is the card's ISSUER, on the viewer row the redemption wrote;
* a redemption writes **only its own** viewer row, and cannot remove or resurrect anybody else's;
* a FULL grant clears the provisional foothold, so nobody is in two membership tables at once;
* only an administrator can mint a card good for more than one person.

The stub below answers queries, which is the opposite of ``test_design_workshop_access_gate``'s
tripwire — that module asserts WHEN the database is first touched and this one asserts WHAT is
written to it. They cannot be one file, and ``test_design_workshop_grant_gate`` is the tripwire half
for the join-card routes.

WHAT IS DELIBERATELY STUBBED, AND WHY IT IS THE RIGHT SEAM. The ELIGIBILITY CHECK — the function
``design_workshop_viewers`` uses to decide whether an account may hold a viewer row at all — is
replaced by a fake that records who it was asked about and can be told to refuse. Its real body reads
the designer roster, the platform allow-list and the user table, none of which is what this file is
about; what IS this file's business is WHO it is asked about, because that is the difference between
validating the redeemer and validating the whole workshop.

⚠ **THIS MODULE USED TO STUB ``replace_viewers``, AND THAT CALL IS GONE ON PURPOSE.** ``redeem`` no
longer hands a whole viewer set to a whole-set replace, because doing so deleted the viewers it had
not read and re-created the ones an admin had just removed — see the service header, and
``test_design_workshop_grant_concurrency`` for the two failing sequences as executable assertions.
The invariant that survives is the one that mattered: ``DesignWorkshopViewer`` is still the only
thing that confers access, and the eligibility rule guarding it is still that module's own, imported
rather than copied. So the assertion here is that the redemption writes ONE row for ONE account, with
the card's issuer and the card's id on it, in one statement.
"""

import asyncio
import hashlib
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

import app.services.design_workshop_access as access
import app.services.design_workshop_grants as grants
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


# --------------------------------------------------------------------------------------
# The fake database
# --------------------------------------------------------------------------------------


class _Delegate:
    """One Prisma model delegate over a list of ``SimpleNamespace`` rows.

    Narrow on purpose: it answers only the shapes this module's code under test actually sends, so a
    query that grows a clause the fake does not understand fails loudly here rather than passing by
    accident. ``matched`` is the whole of the filtering language — equality on scalars, plus the
    ``{"in": [...]}`` and ``{"gt": x}`` forms the service uses — and ``None`` in a filter means IS
    NULL, exactly as Prisma reads it.
    """

    def __init__(
        self, rows: list[Any] | None = None, *, defaults: dict[str, Any] | None = None
    ) -> None:
        self.rows = list(rows or [])
        # THE COLUMN DEFAULTS THE REAL TABLE CARRIES. Without these a freshly created row is missing
        # `usesConsumed`, `revokedAt` and `createdAt` — which Postgres would have filled — and the
        # payload builder fails on an attribute rather than on the property under test.
        self.defaults = dict(defaults or {})
        self.created: list[dict[str, Any]] = []
        self.updates: list[tuple[dict[str, Any], dict[str, Any]]] = []
        self.deletes: list[dict[str, Any]] = []

    # -- reading ----------------------------------------------------------------------
    @staticmethod
    def _matches(row: Any, where: dict[str, Any]) -> bool:
        for key, wanted in where.items():
            if key.endswith(("_userId", "_requestedById")):
                # A compound-unique selector: {"tokenId_userId": {"tokenId": …, "userId": …}}
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

    # -- writing ----------------------------------------------------------------------
    async def create(self, data: dict[str, Any]) -> Any:
        self.created.append(dict(data))
        row = SimpleNamespace(**{**self.defaults, "id": f"row-{len(self.rows)}", **data})
        self.rows.append(row)
        return row

    async def create_many(self, data: list[dict[str, Any]], skip_duplicates: bool = False) -> int:
        written = 0
        for item in data:
            # THE UNIQUE INDEX, FAKED — and it has to be, because "a replay writes nothing" is one of
            # the properties this file exists to assert. Both real indexes are on a pair, so the pair
            # is what is compared.
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
    """The whole client, and an ``async with db.tx()`` that is itself.

    THE TRANSACTION IS NOT SIMULATED, and that is stated rather than hidden: this fake cannot roll
    anything back, so nothing in this module asserts a rollback. What it CAN assert — and what the
    ordering in ``redeem`` was designed around — is that the seat is given back by an explicit
    compensating statement when a grant is refused, which is a WRITE and is therefore visible here.
    See :func:`test_a_card_whose_grant_is_refused_keeps_its_seat`.
    """

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
    """A stand-in for ``design_workshop_viewers._assert_every_id_may_be_granted``.

    IT RECORDS WHO IT WAS ASKED ABOUT, which is the assertion that matters: the redemption must ask
    about the REDEEMER and nobody else. The version of this code that handed a whole viewer set to
    ``replace_viewers`` asked about every account on the workshop, so one colleague's lapsed
    empanelment refused an unrelated person's induction — a 422 in a courtyard about somebody else's
    roster row, which is a refusal the person holding the card cannot act on.

    ``refuse`` reproduces the 422 the real function raises, which is what ``INELIGIBLE`` is for.
    """

    def __init__(self, *, refuse: str | None = None) -> None:
        self.refuse = refuse
        self.calls: list[set[str]] = []

    async def __call__(self, user_ids: set[str]) -> None:
        self.calls.append(set(user_ids))
        if self.refuse is not None:
            raise HTTPException(status_code=422, detail=self.refuse)


@pytest.fixture
def world(monkeypatch: pytest.MonkeyPatch):
    """A workshop, a card, and every delegate the service reaches for.

    ``db`` IS REBOUND ON EVERY MODULE THAT HOLDS ITS OWN REFERENCE, which is the trick the sibling
    test modules document: each does ``from app.core.db import db``, so patching ``app.core.db``
    alone would leave all of them pointing at the real client. ``design_workshop_viewers`` is in the
    list on purpose — ``has_viewer_grant`` is the predicate whose behaviour over a provisional row is
    one of this file's assertions, so it must be the REAL one reading the FAKE table.
    """
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
    # THE SEAM IS THE NAME `design_workshop_grants` IMPORTED, not the one the other module defines.
    # `grants` does `from ... import _assert_every_id_may_be_granted`, so patching the source module
    # would leave the service holding its own reference to the real one.
    monkeypatch.setattr(grants, "_assert_every_id_may_be_granted", eligibility)
    return SimpleNamespace(
        db=db,
        secret=secret,
        code=grants.encode_join_code(WORKSHOP_ID, secret),
        eligibility=eligibility,
        **delegates,
    )


def _redeem(user: Any, code: str, **kwargs: Any) -> dict[str, Any]:
    return asyncio.run(grants.redeem(user, code=code, **kwargs))


# --------------------------------------------------------------------------------------
# THE GRAMMAR: what a join card is, and what it is not
# --------------------------------------------------------------------------------------


def test_the_record_type_enum_matches_the_code_letters():
    """The eleventh hand-kept copy of ``TYPE_LETTER``, pinned so a drift is a failing test.

    ``DwCodeRecordType`` in ``schema.prisma`` and :data:`RECORD_TYPE_LETTERS` here are two more
    copies of a table that already exists in TypeScript and in Kotlin. Nothing can read across those
    languages, so the only defence is a test that asserts the shape both halves must have: ten
    entries, exactly the ten letters, and no letter used twice. A type added in the browser and
    forgotten here is a code that encodes and a card that cannot be issued.
    """
    letters = grants.RECORD_TYPE_LETTERS
    assert len(letters) == 10
    assert sorted(letters.values()) == sorted("ACWDSTQMGP")
    assert len(set(letters.values())) == 10, "two record types share a letter"
    assert grants.JOIN_LETTER not in letters.values(), (
        "J must NOT be a record-type letter: a join card is a credential, not a locator, and the "
        "record-lookup path must have no entry it could resolve"
    )


def test_a_join_card_is_sixty_characters_and_survives_a_round_trip():
    """The QR budget, as an assertion rather than a paragraph.

    Sixty characters fits QR version 4 at error-correction level Q — one version above today's 29x29
    symbol — so the hand-written encoders in ``DwQrEncode.kt`` and ``lib/qrEncode.ts``, their 24-row
    block tables and their cross-language reference matrices need NO CHANGE. This is the assertion
    that notices if the secret or the separator ever grows.
    """
    secret = grants.mint_secret()
    code = grants.encode_join_code(WORKSHOP_ID, secret)
    assert len(code) == 60, code
    record_id, decoded_secret, canonical = grants.decode_join_code(code)
    assert (record_id, decoded_secret) == (WORKSHOP_ID, secret)
    assert canonical == code
    # And the printed, space-grouped form a person types back reads the same.
    grouped = " ".join(code[i : i + 4] for i in range(0, len(code), 4))
    assert grants.decode_join_code(grouped.lower()) == (WORKSHOP_ID, secret, code)


def test_a_secret_is_a_hundred_and_ten_bits_from_a_csprng():
    """22 Crockford characters, and two draws are never the same.

    Not a statistical test — it cannot be, in one process — but it does pin the two things a
    regression would break: the LENGTH, which is the whole entropy argument, and the fact that this
    is not a constant.
    """
    first, second = grants.mint_secret(), grants.mint_secret()
    assert len(first) == 22 and len(second) == 22
    assert first != second
    assert set(first) <= set("0123456789ABCDEFGHJKMNPQRSTVWXYZ")


@pytest.mark.parametrize(
    "code",
    [
        # A check character one out. The refusal the whole grammar exists for.
        "DPW2:J:CMGRANTCARD0000000000000W.AAAAAAAAAAAAAAAAAAAAAA:AAAA",
        # The secret is one character short — not a near miss, a different card or no card.
        "DPW2:J:CMGRANTCARD0000000000000W.AAAAAAAAAAAAAAAAAAAAA:AAAA",
        # `U` is deliberately absent from Crockford base32 and is left to fail rather than guessed at.
        "DPW2:J:CMGRANTCARD0000000000000W.UUUUUUUUUUUUUUUUUUUUUU:AAAA",
        # Ours and well formed, but it is a RECORD tag rather than a join card.
        "DPW1:G:CMGRANTCARD0000000000000W:AAAA",
        # A workshop that exists only on the handset that printed it. Both clients' spellings.
        "DPW2:J:LOCAL-3F2504E0-4F89-11D3-9A0C-0305E82C3301.AAAAAAAAAAAAAAAAAAAAAA:AAAA",
        "DPW2:J:DWLOCAL-3F2504E0-4F89-11D3-9A0C-0305E82C3301.AAAAAAAAAAAAAAAAAAAAAA:AAAA",
        # Not one of ours at all.
        "https://example.org/scan-me",
        "",
    ],
)
def test_a_damaged_or_foreign_card_is_refused_by_the_grammar_alone(code):
    """Every one of these is decided from the STRING, with no database in the picture.

    That is what makes saying them out loud safe: a statement about what was sent discloses nothing
    about which records exist. ``test_design_workshop_grant_gate`` is what asserts the database was
    genuinely never touched; this asserts the refusals themselves.
    """
    with pytest.raises(access.ScannedCodeRefused):
        grants.decode_join_code(code)


def test_a_record_tag_scanned_at_the_join_door_is_sent_to_the_right_place():
    """And a JOIN CARD scanned at the request door is sent back the other way.

    Two sentences, two directions, and neither is "malformed" — the card in the person's hand is
    fine, they are at the wrong screen, and telling them the tag is damaged sends them to look for
    another card that does not exist.
    """
    with pytest.raises(access.ScannedCodeRefused) as refusal:
        grants.decode_join_code("DPW1:G:CMGRANTCARD0000000000000W:AAAA")
    assert "join card" in str(refusal.value.detail).lower()

    card = grants.encode_join_code(WORKSHOP_ID, grants.mint_secret())
    with pytest.raises(access.ScannedCodeRefused) as other:
        access.decode_design_workshop_code(card)
    detail = str(other.value.detail)
    assert "join card" in detail.lower()
    # ⚠ AND THE REFUSAL DOES NOT ECHO THE CARD. A join card's payload is a live credential and a 422
    # body is the easiest place in a web application for one to reach an access log.
    assert card not in detail
    assert grants.decode_join_code(card)[1] not in detail


def test_a_redacted_code_never_carries_the_secret():
    """What the admin queue stores, and the one property that makes storing it safe at all.

    ``DesignWorkshopAccessRequest.scannedCode``'s own comment justified keeping a whole code with "it
    carries no identity data by construction" — TRUE of a v1 record tag and FALSE of a join card,
    because a join card IS the credential. So the stored form keeps four characters (twenty bits:
    enough to match a card in somebody's hand, useless to a guesser) and drops the rest.
    """
    secret = grants.mint_secret()
    stored = grants.redacted_code(WORKSHOP_ID, secret)
    assert secret not in stored
    assert secret[:-4] not in stored
    assert secret[-4:] in stored
    assert WORKSHOP_ID.upper() in stored
    # THE CHECK CHARACTERS ARE RECOMPUTED over the redacted string rather than carried over. Carrying
    # them would make the stored form look like a card that fails its own check — the one refusal in
    # this feature people trust — and an admin comparing it against a card would conclude the card
    # was damaged.
    prefix, _, check = stored.rpartition(":")
    assert check == access.code_check(prefix)


# --------------------------------------------------------------------------------------
# A FORGED CARD
# --------------------------------------------------------------------------------------


def test_a_forged_card_is_refused_and_nothing_at_all_is_written(world):
    """**THE PROPERTY THAT MAKES THE 110-BIT SECRET THE WHOLE SECURITY MECHANISM.**

    The string is perfectly well formed — a real workshop id, correct namespace, correct letter,
    correct check characters, because the FNV algorithm ships to every browser and anybody can
    compute one. What it does not have is a secret that matches a row. So it is refused with the
    UNIFORM refusal, and — the half that is easy to lose — **nothing is written anywhere**.

    That second assertion is not tidiness. A redemption row per forged string is a table anybody can
    grow by posting random bytes: a denial-of-service with an audit trail attached.
    """
    forged = grants.encode_join_code(WORKSHOP_ID, grants.mint_secret())
    assert grants.decode_join_code(forged)  # the grammar is satisfied; that is the point

    with pytest.raises(grants.CardRefused):
        _redeem(_user(ALICE_ID), forged)

    assert world.recordaccesstokenredemption.rows == []
    assert world.designworkshopprovisionalmember.rows == []
    assert world.designworkshopaccessrequest.rows == []
    assert world.designworkshopviewer.rows == []
    assert world.recordaccesstoken.rows[0].usesConsumed == 0
    assert world.eligibility.calls == []


def test_a_revoked_card_is_refused_and_writes_nothing(world):
    """Revocation is effective, and it does not degrade into a provisional foothold.

    A card revoked because it was leaked must not still buy its holder a workspace and a place in an
    admin's queue — that would make revoking a card an invitation. This is the one deliberate
    asymmetry with ``ALREADY_SPENT``: a spent card was legitimate and its late scanner is a
    colleague; a revoked card has been withdrawn.
    """
    world.recordaccesstoken.rows[0].revokedAt = datetime.now(UTC)
    with pytest.raises(grants.CardRefused):
        _redeem(_user(ALICE_ID), world.code)
    assert world.recordaccesstokenredemption.rows == []
    assert world.designworkshopprovisionalmember.rows == []


def test_a_card_naming_a_deleted_workshop_is_refused(world):
    """Why the missing foreign key on ``recordId`` is affordable rather than an oversight.

    ``RecordAccessToken.recordId`` has no FK — the price of ONE generic token table instead of ten —
    and this is the line that makes an orphaned card inert: the record is re-read on every single
    redemption and a missing or soft-deleted one refuses before anything is written.
    """
    world.designworkshop.rows[0].deletedAt = datetime.now(UTC)
    with pytest.raises(grants.CardRefused):
        _redeem(_user(ALICE_ID), world.code)
    assert world.recordaccesstokenredemption.rows == []


def test_a_card_for_another_kind_of_record_cannot_grant_workshop_membership(world):
    """Requirement 8, read precisely: the SCANNING applies everywhere, the INDUCTION does not.

    An artisan card confers nothing — there is no membership table for an artisan — so a card whose
    ``recordType`` is not ``DESIGN_WORKSHOP`` must not be able to reach the viewer mechanism, even if
    somebody hand-wrote the row. Uniform refusal, because "that is a tool card" would be a statement
    about a row rather than about the body.
    """
    world.recordaccesstoken.rows[0].recordType = "TOOL"
    with pytest.raises(grants.CardRefused):
        _redeem(_user(ALICE_ID), world.code)
    assert world.designworkshopviewer.rows == []


# --------------------------------------------------------------------------------------
# AN EXPIRED CARD
# --------------------------------------------------------------------------------------


def test_an_expired_card_never_becomes_a_full_grant(world):
    """**EXPIRY IS JUDGED BY SERVER ARRIVAL, so a device clock cannot buy an extension.**

    The handset here claims the scan happened a fortnight ago, well inside the card's life, and it is
    stored as evidence and ignored as authority. The outcome is PROVISIONAL — not a refusal, because
    the fieldwork behind a genuine late sync is real — and above all **not FULL**, and the card's seat
    is untouched so somebody scanning it in time still cannot be beaten to it by a stale delivery.
    """
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
    # And they are in the queue an admin already works from, PENDING, so requirement 6's upgrade is
    # one click rather than a support conversation.
    assert [row.status for row in world.designworkshopaccessrequest.rows] == ["PENDING"]


def test_an_expired_card_beyond_the_sync_grace_is_refused_outright(world):
    """The other end of the grace window, and why there has to be one at all.

    Inside :data:`GRANT_SYNC_GRACE_DAYS` a genuine scan that synced late is worth a foothold: a
    courtyard with no signal for a fortnight is the ordinary case this whole feature exists for.
    Beyond it, a card months out of date is indistinguishable from a card somebody kept, and it gets
    the uniform refusal with nothing written.
    """
    token = world.recordaccesstoken.rows[0]
    token.expiresAt = datetime.now(UTC) - timedelta(days=grants.GRANT_SYNC_GRACE_DAYS + 1)

    with pytest.raises(grants.CardRefused):
        _redeem(_user(ALICE_ID), world.code)

    assert world.recordaccesstokenredemption.rows == []
    assert world.designworkshopprovisionalmember.rows == []
    assert token.usesConsumed == 0


# --------------------------------------------------------------------------------------
# SINGLE USE, TWICE
# --------------------------------------------------------------------------------------


def test_a_single_use_card_admits_exactly_one_person_and_does_not_refuse_the_second(world):
    """**THE CENTRAL SEQUENCE: the two-scanner run, end to end.**

    Bob and Alice both scan one single-use card in a courtyard with no signal. Bob's handset finds
    signal first — even though Alice scanned EARLIER by her own device's clock, and even though Bob's
    phone thinks it is an hour before hers. Bob gets the seat, because **server arrival order at the
    compare-and-swap is the authority and a settable number is not**: ordering by the handset's clock
    hands the grant to whoever winds theirs back furthest, which is precisely the spoof requirement 5
    names.

    Alice is **not refused**, which is requirement 6 and the reason this feature is shaped this way at
    all. She gets a capture-only foothold, a PENDING row in the queue an admin already reads, and her
    device-reported scan time recorded beside the server's arrival time so an admin can see for
    themselves that she scanned first and synced second.
    """
    token = world.recordaccesstoken.rows[0]
    # RELATIVE TO THE SERVER'S OWN CLOCK, deliberately, and not two fixed dates: a fixed date is a
    # test that starts failing on a particular Tuesday, and the property here is about ORDER rather
    # than about any wall-clock value. Alice scanned five hours ago; Bob scanned SIX hours ago by his
    # own handset, whose clock is an hour slow — so by device time Bob looks earlier, and it changes
    # nothing.
    now = datetime.now(UTC)
    alice_scanned = now - timedelta(hours=5)
    bob_scanned = now - timedelta(hours=6)

    bob = _redeem(_user(BOB_ID), world.code, scanned_at_client=bob_scanned)
    alice = _redeem(_user(ALICE_ID), world.code, scanned_at_client=alice_scanned)

    assert bob["outcome"] == "FULL" and bob["reason"] == "OK"
    assert alice["outcome"] == "PROVISIONAL" and alice["reason"] == "ALREADY_SPENT"

    # ONE SEAT, ONE VIEWER ROW. The CHECK constraint in the migration is the database-level backstop
    # for the same statement; this is the service-level one.
    assert token.usesConsumed == 1
    assert token.maxUses == 1
    assert [row.userId for row in world.designworkshopviewer.rows] == [BOB_ID]

    # ALICE'S WORK IS NOT ORPHANED. A foothold, and a place in the queue.
    assert [row.userId for row in world.designworkshopprovisionalmember.rows] == [ALICE_ID]
    alice_request = next(
        row for row in world.designworkshopaccessrequest.rows if row.requestedById == ALICE_ID
    )
    assert alice_request.status == "PENDING"
    assert alice_request.tokenId == TOKEN_ID

    # THE EVIDENCE IS RECORDED AND IS NOT THE AUTHORITY. Alice's own clock says she was first; the
    # server's arrival order says Bob was, and Bob is the one who got the seat.
    alice_redemption = next(
        row for row in world.recordaccesstokenredemption.rows if row.userId == ALICE_ID
    )
    assert alice_redemption.scannedAtClient == alice_scanned
    assert alice_redemption.serverArrivedAt > alice_scanned
    assert alice_redemption.outcome == "PROVISIONAL"
    assert alice_request.scannedAt == alice_scanned


def test_the_same_person_scanning_twice_does_not_spend_a_second_seat(world):
    """The replay: a flaky link retrying, or one person with two handsets.

    **TWO GUARDS STOP THIS, AND BOTH ARE ASSERTED, because either one alone has a hole.**

    The one that fires here is the MEMBERSHIP check: the second delivery finds them already on the
    workshop and stops before a seat is even looked at. Note the outcome is ``ALREADY_A_MEMBER``
    rather than a repeat of ``FULL``, which is the honest answer — by the time the second delivery
    arrives, "you are already in" is simply true, and it is the same sentence a member who scans the
    card at the wall gets.

    The membership check alone is not enough, though, and the second guard is why
    ``RecordAccessTokenRedemption.@@unique([tokenId, userId])`` exists as a DATABASE constraint rather
    than a check in this file: it also covers the two cases membership cannot see — two deliveries
    arriving CONCURRENTLY, where neither can observe the other's viewer row, and a PROVISIONAL holder
    re-delivering, who is not a member at all. See
    :func:`test_a_provisional_holder_redelivering_the_same_card_gets_the_first_answer_back`.
    """
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
    """The case the membership check cannot catch, and the reason the unique index is in Postgres.

    A late-comer holds a foothold rather than a viewer row, so the "already a member" branch does not
    see them at all. Their handset re-delivers the same card — a flaky link, or the outbox retrying
    after a reboot — and the ``@@unique([tokenId, userId])`` row is what makes it a no-op: the FIRST
    outcome is returned unchanged rather than recomputed, so a replay can neither spend a seat nor
    quietly upgrade somebody whose card really was already spent.

    Note what is asserted about ``maxUses`` here: the card has FOUR seats left. So without this guard
    the replay would have taken one — which is exactly the "one person with two handsets spends a
    multi-use card twice" failure, and it is invisible from the outside because both deliveries
    succeed.
    """
    token = world.recordaccesstoken.rows[0]
    token.maxUses = 5
    token.usesConsumed = 5  # spent, so the first delivery is a late-comer
    first = _redeem(_user(ALICE_ID), world.code)
    assert first["outcome"] == "PROVISIONAL" and first["reason"] == "ALREADY_SPENT"

    token.usesConsumed = 1  # an admin raised the ceiling; there are seats again
    second = _redeem(_user(ALICE_ID), world.code)

    assert second["outcome"] == "PROVISIONAL", "a replay is not an upgrade"
    assert second["reason"] == "ALREADY_SPENT"
    assert token.usesConsumed == 1, "a replay must not consume a seat"
    assert len(world.recordaccesstokenredemption.rows) == 1
    # ONCE, FOR THE FIRST DELIVERY, AND NOT AGAIN FOR THE REPLAY — which is the point of this test.
    #
    # THE FIRST DELIVERY ASKS EVEN THOUGH THE CARD IS SPENT, and that ordering is deliberate: the
    # eligibility question is asked BEFORE the seat, so an account that cannot hold a viewer row never
    # takes one. The cost is two roster reads on a late-comer's scan, which is not a hot path, and the
    # gain is that there is no compensating "give the seat back" statement on the ineligible path at
    # all. It also means an ineligible late-comer's row reads `INELIGIBLE` rather than `ALREADY_SPENT`,
    # which is the more useful of the two facts for the admin who has to act on it: a person whose
    # account is barred cannot be granted access by pressing Grant either.
    assert len(world.eligibility.calls) == 1
    assert world.eligibility.calls == [{ALICE_ID}]


def test_a_member_scanning_the_card_at_the_wall_does_not_burn_the_invitation(world):
    """Somebody already on the workshop scans the card pinned up beside the door.

    Nothing is written and no seat is spent. Without this branch, a workshop's only invitation is
    used up by the person who least needs it, and nobody would ever know why the card stopped working.
    """
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
    """The three sources of access, all three short-circuiting before a seat is spent.

    The creator holds the workshop through ``createdById`` and has no viewer row at all; an admin
    reaches every workshop through ``is_admin`` regardless of either. A predicate that checked only
    the viewer table would burn a seat on both.
    """
    token = world.recordaccesstoken.rows[0]
    assert _redeem(_user(CREATOR_ID), world.code)["outcome"] == "ALREADY_A_MEMBER"
    assert _redeem(_user("cmadmin00000000000000000x", "ADMIN"), world.code)["outcome"] == (
        "ALREADY_A_MEMBER"
    )
    assert token.usesConsumed == 0


# --------------------------------------------------------------------------------------
# PROVENANCE
# --------------------------------------------------------------------------------------


def test_a_full_redemption_writes_one_viewer_row_for_one_account_and_nothing_else(world):
    """**REQUIREMENT 4, AND THE INVARIANT THE ACCESS MODULE'S HEADER STATES IN TERMS.**

    "GRANTING GOES THROUGH THE VIEWER MECHANISM AND NOWHERE ELSE … there is no second way to become a
    viewer." ``DesignWorkshopViewer`` is still that mechanism and this asserts the redemption's write
    into it — ONE row, ONE account, and the eligibility rule of the module that owns the table
    consulted about exactly that account.

    ⚠ WHAT THIS TEST DELIBERATELY NO LONGER ASSERTS, AND WHY THAT IS NOT A WEAKENING. It used to
    assert that ``replace_viewers`` was the thing that wrote the row, with the WHOLE resulting set,
    "exactly as an admin ticking a box does". That call was the bug: a whole-set replace deletes what
    it did not see, so a redemption expressing "add me" as "here is everything I read a moment ago,
    plus me" deleted a concurrent redeemer's brand-new row and resurrected a viewer an admin had just
    removed. ``test_design_workshop_grant_concurrency`` holds both sequences as assertions. The
    property that had to survive is that access still comes from ONE table under ONE eligibility rule,
    and that is what is checked here.

    AND THE PROVENANCE, which is the point of the whole trail:

    * ``grantedById`` is the card's **ISSUER** — not the redeemer, and not an administrator who was
      never in the courtyard.
    * ``tokenId`` on the viewer row names **which card**, which is what makes "revoke everybody this
      batch let in" an answerable question. **Set by the statement that CREATES the row**, not by a
      follow-up update: the update it replaced ran after the whole-set replace and, whenever the row
      it meant to stamp had just been deleted by that same call, silently affected zero rows.

    THE HONEST LIMIT, which the code comments state and which this test cannot assert because it is
    not a fact about the database: a card names its ISSUER, not necessarily the person who handed it
    over. SINGLE-USE is what collapses those two into one fact, and that is the second reason
    multi-use is admin-only.
    """
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

    # ONE STATEMENT, WITH BOTH PROVENANCE COLUMNS ON IT. A row created without `tokenId` and stamped
    # afterwards is a row that can be missed; this asserts the write itself carried it.
    assert len(world.designworkshopviewer.created) == 1
    written = world.designworkshopviewer.created[0]
    assert written["tokenId"] == TOKEN_ID
    assert written["grantedById"] == ISSUER_ID

    redemption = world.recordaccesstokenredemption.rows[0]
    assert (redemption.tokenId, redemption.userId) == (TOKEN_ID, ALICE_ID)
    assert redemption.outcome == "FULL" and redemption.reason == "OK"
    assert redemption.serverArrivedAt is not None


def test_the_offline_evidence_is_all_recorded_and_none_of_it_decides_anything(world):
    """Every field the handset reports, stored — and the authority is still the server's clock.

    The monotonic pair is the only device-reported time worth anything (a wall clock is settable, and
    ``elapsedRealtime`` is not without root), ``bootId`` is what makes the pair comparable at all
    because a reboot resets it, and ``clockJumpObserved`` is not an accusation — a phone that finds a
    network after two days offline legitimately jumps. All four are shown to an admin and none of them
    is compared to decide an outcome.
    """
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
    # THE AUTHORITY. It is the server's, and it is not the handset's — the two differ by four days
    # here and the server's is the one on the row every decision was made against.
    assert row.serverArrivedAt > scanned


def test_a_card_scanned_by_an_ineligible_account_never_spends_its_seat(world, monkeypatch):
    """``INELIGIBLE``: the outcome nobody expects, and the reason it must not be silent.

    The REDEEMER'S OWN ACCOUNT cannot hold a viewer row — off the ACTIVE designer roster, barred by
    the platform allow-list, or a role that cannot run a workshop at all. Refusing them outright would
    be a refusal in a courtyard naming a screen nobody present can reach, so it becomes a capture-only
    foothold plus a row in the queue an admin already reads.

    **THE SEAT IS NEVER TAKEN, WHICH IS STRONGER THAN GIVING IT BACK.** Eligibility is asked before
    the compare-and-swap, so there is no compensating statement on this path and no window in which
    the card looks spent. The earlier shape checked eligibility inside the grant — as a side effect of
    handing the whole viewer set to ``replace_viewers`` — and had to hand the seat back afterwards.

    ⚠ AND THE SENTENCE IS NOT RETURNED TO THE SCANNER. It names another screen and, for the role arm,
    the account's own role; the redeemer gets the ordinary provisional detail, because a redemption
    answer that varied with the reason would be a second, quieter refusal.
    """
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


def test_a_card_supersedes_an_administrators_earlier_refusal_and_says_so(world, caplog):
    """The policy edge, pinned so it is a decision rather than an accident.

    Somebody an admin REFUSED last month is handed a genuine card by a colleague already on the
    workshop. Requirement 2 makes scanning equivalent to an admin's induction, and there is no way to
    hold a viewer row and a DENIED queue row at once without one of the two screens lying — leaving
    the row DENIED beside real access is the exact "lie on the screen" ``decide``'s 409 exists to
    prevent. So the row follows reality, **the refusal's own columns are kept**, and the event is
    logged at WARNING because an administrator whose decision has been overridden should be able to
    find out.

    THE CONTRAST THAT MAKES THIS NOT A HOLE: a refusal still cannot be reopened by ASKING. That is
    the ``status: PENDING`` pin in ``_file_or_refresh_the_queue_row``, on the anti-spam rule the
    access module's header argues at length — "letting a scan reopen it would put the same card back
    in an admin's queue every time somebody pointed a phone at it". What crossed the line here is a
    110-bit credential minted by somebody entitled to mint it.
    """
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
    # THE REFUSAL'S OWN RECORD SURVIVES. Who refused, when, and why are the only account anybody has
    # of the earlier decision, and `file_request`'s reopen branch keeps them for the same reason.
    assert row.decidedById == "cmadmin00000000000000000x"
    assert row.decisionNote == "not on this cluster"
    assert row.decidedAt is not None
    assert row.tokenId == TOKEN_ID
    assert any("previously REFUSED" in record.message for record in caplog.records), (
        "an administrator whose decision was overridden must be able to find out"
    )


# --------------------------------------------------------------------------------------
# MINTING: who may print what
# --------------------------------------------------------------------------------------


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
    """**THE NON-NEGOTIABLE, AND BOTH SHAPES OF IT.**

    ``max_uses=None`` means UNLIMITED and is in this list on purpose: a rule written as
    ``max_uses > 1`` passes the first two rows and hands a designer a card that admits everybody.
    PROFESSOR is here for the same kind of reason — they outrank a designer on the ladder and are
    still not an administrator, which is the row a rank comparison written in place of ``is_admin``
    gets wrong.

    THE REASONING, because it is what stops this being "simplified": a designer cannot create a
    workshop, and every route that puts somebody on one is admin-only, so a card admitting
    arbitrarily many people would hand them exactly the membership power those two rules deny.
    """
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
    """And the response carries the secret exactly once, which is the only time it ever exists."""
    minted = _mint(_user("cmadmin00000000000000000x", role), max_uses=12)
    assert minted["maxUses"] == 12
    assert minted["usesConsumed"] == 0
    record_id, secret, _canonical = grants.decode_join_code(minted["code"])
    assert record_id == WORKSHOP_ID
    # THE SECRET IS NEVER STORED. What went into the row is its hash and its last four characters.
    written = world.recordaccesstoken.created[0]
    assert written["secretHash"] == grants.secret_hash(secret)
    assert written["secretLast4"] == secret[-4:]
    assert secret not in str(written)


def test_a_designer_on_the_workshop_may_print_a_single_use_card(world):
    """The courtyard case, which is the entire motivation and must stay reachable.

    A designer already on the workshop hands a card to the person standing next to them, because
    there is no administrator within two districts. Bounded three ways — single use only, a cap on
    outstanding cards, and every card visible with its issuer and revocable — and NOT bounded by
    ``require_admin`` on the route, which would have refused the case the feature exists for.
    """
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
    """Minting CAN follow the repository's enumeration rule, so it does.

    Unlike the ask route — whose whole purpose is to be called by somebody who may not see the record
    — a caller here is either entitled or is nobody, so the answer is ``require_record``'s own 404
    with its own detail string. A 403 would confirm the id is real.
    """
    with pytest.raises(HTTPException) as refusal:
        _mint(_user("cmstranger00000000000000z"))
    assert refusal.value.status_code == 404
    assert refusal.value.detail == "Record not found"


def test_a_card_cannot_be_printed_for_a_record_that_has_no_membership_to_grant(world):
    """Requirement 8's dividing line, refused LOUDLY rather than minted.

    A card that admits nobody is worse than no card, because somebody prints twenty of them and hands
    them out. The refusal is a statement about the request body — it does not depend on which records
    exist — so saying it discloses nothing.
    """
    for record_type in ("ARTISAN", "TOOL", "PRODUCT", "PROTOTYPE"):
        with pytest.raises(HTTPException) as refusal:
            _mint(_user("cmadmin00000000000000000x", "ADMIN"), record_type=record_type)
        assert refusal.value.status_code == 422
        assert "design workshop" in str(refusal.value.detail)
    assert world.recordaccesstoken.created == []


def test_a_non_admin_is_capped_on_outstanding_cards(world):
    """Single use bounds one CARD, not one ISSUER — the gap named rather than left to be found.

    Fifty single-use cards over a month admits fifty people. The cap counts what is actually LOOSE —
    un-revoked, unexpired, and with a seat still on it — rather than what was ever printed, so a
    designer whose three earlier cards were all used can print again, and one holding three unspent
    keys cannot.
    """
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

    # A SPENT CARD IS NOT OUTSTANDING. One of the three is used, and the designer can print again.
    world.recordaccesstoken.rows[0].usesConsumed = 1
    assert _mint(_user(ISSUER_ID), max_uses=1)["maxUses"] == 1


def test_an_admin_is_not_capped(world):
    """The cap is about a designer minting around the multi-use rule, and an admin is not doing that:
    they can simply print one card for twenty people, which is the thing the cap exists to stop a
    designer approximating."""
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
    """NOT NULL in the schema, and this is the assertion that keeps it meaningful.

    A card with no end date is a permanent key to a workshop, printed on paper, that nobody remembers
    exists. The default is a fortnight — the length of the workshop the cards are printed for — and
    the ceiling stops "valid for ten years" being one keystroke away.
    """
    admin = _user("cmadmin00000000000000000x", "ADMIN")
    minted = _mint(admin)
    expires = datetime.fromisoformat(minted["expiresAt"])
    assert timedelta(days=grants.DEFAULT_GRANT_DAYS - 1) < expires - datetime.now(UTC)
    assert expires - datetime.now(UTC) <= timedelta(days=grants.DEFAULT_GRANT_DAYS)

    with pytest.raises(HTTPException) as refusal:
        _mint(admin, days_valid=grants.MAX_GRANT_DAYS + 1)
    assert refusal.value.status_code == 422
