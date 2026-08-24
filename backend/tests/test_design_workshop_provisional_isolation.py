"""**A PROVISIONAL FOOTHOLD IS NOT MEMBERSHIP.** The tripwire for that. No database required.

WHAT THIS FILE IS DEFENDING, AND WHY IT IS A WHOLE MODULE FOR ONE IDEA
=====================================================================

Requirement 6 says a designer who scans a spent single-use card must not be refused: they get a
capture-only foothold so the fieldwork they recorded in a courtyard is not orphaned. The obvious way
to build that is a ``level`` column on ``DesignWorkshopViewer`` — and it was designed, written out in
full, and rejected. This module is the assertion that keeps the rejection true.

``has_viewer_grant`` reads the EXISTENCE of a viewer row. It is consulted from four places
(``design_workshops.load_workshop_or_404``, ``design_ratings``, ``routes/questionnaire_forms``,
``design_workshop_access``), and **two more reads do not go through it at all**:

* ``questionnaire_forms._visible_questionnaire_where`` writes the relation filter by hand —
  ``{"designWorkshop": {"is": {"deletedAt": None, "viewers": {"some": {"userId": user.id}}}}}`` — so
  narrowing the predicate would not have narrowed it, and a provisional row would have handed over
  every questionnaire attached to the workshop: respondents' names and their answers.
* ``records._design_workshop_media_branches`` follows ``visible_to_clause`` on that function's own
  written instruction — "so the day that widens again the audio widens with it" — which rides every
  ``/export`` CSV, every ``/data`` page and every transcript. Following it in the wrong direction
  leaks recorded audio of artisans.

A ``level`` column means every one of those six admits an unadjudicated scan until each is
individually taught the difference. Missing one is not a cosmetic bug.

**A SEPARATE TABLE CANNOT FAIL THAT WAY**, and that is the whole design: to every existing read a
provisional member is a stranger, because nothing that decides access consults
``DesignWorkshopProvisionalMember`` at all. So the assertions below are deliberately shaped as
"the foothold exists AND the predicate still says no" rather than as a test of any one route:

1. A foothold does not satisfy ``has_viewer_grant``, which is the chokepoint for four of the six.
2. A foothold does not appear in ``_access_by_pair``, so the admin queue does not report the person
   as already having access — which matters more than it sounds, because ``decide``'s 409 would then
   refuse the very upgrade requirement 6 depends on.
3. The queue reports the foothold in its OWN field, so an admin can see it without it being access.
4. ``may_capture`` — the write predicate — is the ONLY thing that says yes to a foothold.
5. Granting clears the foothold; refusing clears it too, or the refusal is a lie.

IF YOU ARE HERE BECAUSE THIS MODULE WENT RED after "simplifying" the foothold into a boolean on the
viewer row: that is what it is for. The security property was in the separation.
"""

import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

import app.services.design_workshop_access as access
import app.services.design_workshop_grants as grants
import app.services.design_workshop_viewers as viewers

WORKSHOP_ID = "cmprovisional00000000000w"
CREATOR_ID = "cmprovisional00000000000c"
LATECOMER_ID = "cmprovisional00000000000l"
MEMBER_ID = "cmprovisional00000000000m"
ADMIN_ID = "cmprovisional00000000000d"
REQUEST_ID = "cmprovisional00000000000r"
TOKEN_ID = "cmprovisional00000000000t"


class _Rows:
    """A delegate over a list, narrow enough that an unexpected clause fails loudly."""

    def __init__(self, rows: list[Any] | None = None) -> None:
        self.rows = list(rows or [])
        self.deletes: list[dict[str, Any]] = []

    @staticmethod
    def _matches(row: Any, where: dict[str, Any]) -> bool:
        for key, wanted in where.items():
            if key.endswith("_userId"):
                return _Rows._matches(row, wanted)
            actual = getattr(row, key, None)
            if isinstance(wanted, dict) and "in" in wanted:
                if actual not in wanted["in"]:
                    return False
            elif actual != wanted:
                return False
        return True

    async def find_unique(self, where: dict[str, Any], include: Any = None) -> Any:
        return next((row for row in self.rows if self._matches(row, where)), None)

    async def find_many(self, where: dict[str, Any] | None = None, **_: Any) -> list[Any]:
        return [row for row in self.rows if self._matches(row, where or {})]

    async def update(self, where: dict[str, Any], data: dict[str, Any]) -> Any:
        for row in self.rows:
            if self._matches(row, where):
                for key, value in data.items():
                    setattr(row, key, value)
                return row
        return None

    async def create_many(self, data: list[dict[str, Any]], skip_duplicates: bool = False) -> int:
        """The additive write a grant now performs, WITH the unique index it relies on.

        THE INDEX IS FAKED RATHER THAN IGNORED, because "a collision settles to one row" is one of
        the properties this file asserts. Both tables reached through here are keyed on
        ``(designWorkshopId, userId)``, so that pair is what is compared — deliberately NOT including
        the nullable ``tokenId``, which would make a row written by a card look like a different row
        from one written by an admin and let both exist.
        """
        written = 0
        for item in data:
            pair = (item.get("designWorkshopId"), item.get("userId"))
            if any(
                (getattr(row, "designWorkshopId", None), getattr(row, "userId", None)) == pair
                for row in self.rows
            ):
                if not skip_duplicates:
                    raise AssertionError("duplicate key without skip_duplicates")
                continue
            self.rows.append(SimpleNamespace(**item))
            written += 1
        return written

    async def delete_many(self, where: dict[str, Any]) -> int:
        keep = [row for row in self.rows if not self._matches(row, where)]
        removed = len(self.rows) - len(keep)
        self.deletes.append(dict(where))
        self.rows = keep
        return removed


class _Client:
    """The fake Prisma client, and an ``async with db.tx()`` that hands back itself.

    THE TRANSACTION IS NOT SIMULATED and nothing here asserts a rollback — the sibling modules say the
    same about their own fakes. What it lets this file assert is that the grant and the foothold's
    removal are issued through ONE client inside one block, which is the shape that stops the two
    coming apart; whether Postgres rolls them back together is a claim for Postgres.
    """

    def __init__(self, **tables: "_Rows") -> None:
        for name, table in tables.items():
            setattr(self, name, table)

    def tx(self) -> Any:
        client = self

        class _Tx:
            async def __aenter__(self) -> Any:
                return client

            async def __aexit__(self, *_exc: object) -> bool:
                return False

        return _Tx()


def _foothold(user_id: str = LATECOMER_ID) -> SimpleNamespace:
    """One ``DesignWorkshopProvisionalMember`` row: the late-comer of requirement 6."""
    return SimpleNamespace(
        designWorkshopId=WORKSHOP_ID,
        userId=user_id,
        viaTokenId=TOKEN_ID,
        reason="ALREADY_SPENT",
        scannedAtClient=None,
        serverArrivedAt=datetime.now(UTC),
        createdAt=datetime.now(UTC),
    )


def _viewer(user_id: str) -> SimpleNamespace:
    return SimpleNamespace(
        designWorkshopId=WORKSHOP_ID,
        userId=user_id,
        grantedById=ADMIN_ID,
        tokenId=None,
        createdAt=datetime.now(UTC),
    )


def _request_row(status: str = "PENDING", requester_id: str = LATECOMER_ID) -> SimpleNamespace:
    return SimpleNamespace(
        id=REQUEST_ID,
        designWorkshopId=WORKSHOP_ID,
        requestedById=requester_id,
        status=status,
        source="SCAN",
        scannedCode="DPW2:J:CMPROVISIONAL00000000000W.…6X1Y:5299",
        tokenId=TOKEN_ID,
        scannedAt=datetime.now(UTC),
        note=None,
        createdAt=datetime.now(UTC),
        decidedById=None,
        decidedAt=None,
        decisionNote=None,
        designWorkshop=SimpleNamespace(
            id=WORKSHOP_ID, title="Kalamkari", workshopCode="DPW/OD/2026/14",
            createdById=CREATOR_ID, deletedAt=None,
        ),
        requestedBy=SimpleNamespace(
            id=requester_id, name="Late Comer", email="late@example.test", role="DESIGNER"
        ),
        decidedBy=None,
    )


@pytest.fixture
def world(monkeypatch: pytest.MonkeyPatch):
    """One workshop with a full member and a provisional late-comer beside them.

    Every module that holds its own ``db`` reference is rebound — ``design_workshop_viewers`` above
    all, because ``has_viewer_grant`` is the REAL predicate here reading the FAKE tables. Stubbing
    the predicate instead would assert nothing: the property is what that function does when a
    foothold exists, and a stub would just be this file agreeing with itself.
    """
    tables = {
        "designworkshopviewer": _Rows([_viewer(MEMBER_ID)]),
        "designworkshopprovisionalmember": _Rows([_foothold()]),
        "designworkshopaccessrequest": _Rows([_request_row()]),
        "designworkshop": _Rows(
            [SimpleNamespace(id=WORKSHOP_ID, createdById=CREATOR_ID, deletedAt=None)]
        ),
    }
    db = _Client(**tables)
    for module in (access, grants, viewers):
        monkeypatch.setattr(module, "db", db)
    return SimpleNamespace(db=db, **tables)


# --------------------------------------------------------------------------------------
# 1. THE CHOKEPOINT
# --------------------------------------------------------------------------------------


def test_a_provisional_foothold_does_not_satisfy_has_viewer_grant(world):
    """**THE SINGLE MOST IMPORTANT ASSERTION IN THIS FEATURE.**

    ``has_viewer_grant`` is what ``load_workshop_or_404`` asks on every read of a design workshop by
    somebody who is neither its creator nor an admin. If a foothold satisfied it, a person who
    scanned a spent card — or a FORGED one, before the server ever saw it — would read a fortnight of
    another designer's fieldwork, the ratings ledger, the report and every questionnaire response.

    The foothold is a row in a DIFFERENT TABLE, so the predicate cannot see it and does not have to
    be taught not to. That is the whole reason the design is shaped this way, and the assertion is
    written against the real function rather than a stub so that it stays an assertion about the
    codebase and not about this file.
    """
    assert world.designworkshopprovisionalmember.rows, "the fixture must actually hold a foothold"
    assert asyncio.run(viewers.has_viewer_grant(WORKSHOP_ID, LATECOMER_ID)) is False
    # And the control: a real viewer row does satisfy it, so the assertion above is not passing
    # because the predicate is broken for everybody.
    assert asyncio.run(viewers.has_viewer_grant(WORKSHOP_ID, MEMBER_ID)) is True


def test_a_provisional_foothold_is_not_reported_as_access_to_the_admin_queue(world):
    """``requesterHasAccess`` must stay false, and this is not a cosmetic field.

    ``decide`` asks ``_access_by_pair`` over its single row and raises **409 on the DENIED arm** for
    somebody who already has access — which is correct, because writing DENIED over access that
    remains would be a lie on the screen. But if a foothold read as access, that 409 would fire for
    every late-comer, and ``decide(GRANTED)`` would be reporting somebody as already in while writing
    the promotion. Requirement 6's upgrade would be unreachable through the only screen that offers
    it.
    """
    row = world.designworkshopaccessrequest.rows[0]
    granted = asyncio.run(access._access_by_pair([row]))
    assert (WORKSHOP_ID, LATECOMER_ID) not in granted
    # The control: that function DOES see the real viewer row on the same workshop, so the absence
    # above is the foothold not counting rather than the query not running.
    assert (WORKSHOP_ID, MEMBER_ID) in granted


def test_the_queue_reports_the_foothold_in_its_own_field(world):
    """Seen, and seen as what it is — the other half of the previous test.

    An admin looking at a queue row otherwise cannot tell "somebody who scanned a card and is
    waiting" from "somebody who scanned a card, is waiting, AND has a fortnight of fieldwork on a
    handset that only becomes readable when you press Grant". Those need different urgency, so the
    fact travels — in a SEPARATE field, because folding it into ``requesterHasAccess`` is exactly the
    lie the test above is about.
    """
    answer = asyncio.run(access.queue("PENDING"))
    assert len(answer["requests"]) == 1
    payload = answer["requests"][0]
    assert payload["requesterHasAccess"] is False
    assert payload["requesterIsProvisional"] is True
    # THE EVIDENCE TRAVELS TOO, so a human can weigh a device clock against the server's.
    assert payload["tokenId"] == TOKEN_ID
    assert payload["scannedAt"] is not None
    # ⚠ AND THE STORED CODE IS REDACTED. `scannedCode` on a v2 join card must never be the whole
    # string; four characters is all an admin needs to match the card in front of them.
    assert "…" in payload["scannedCode"]


# --------------------------------------------------------------------------------------
# 2. WHAT A FOOTHOLD *IS* GOOD FOR
# --------------------------------------------------------------------------------------


def test_may_capture_is_the_only_predicate_that_says_yes_to_a_foothold(world):
    """A WRITE predicate, and deliberately not a read one.

    ``may_capture`` answers "may this account create rows attributed to itself". It says nothing
    about reading anybody else's, and the row-level filter that keeps other designers' work out is
    the other half. **A wave that hangs a READ on this predicate has quietly given a spent-card
    scanner the whole workshop** — which is why the two functions have different names, live in
    different modules, and are asserted against each other here.
    """
    latecomer = SimpleNamespace(id=LATECOMER_ID, role="DESIGNER")
    member = SimpleNamespace(id=MEMBER_ID, role="DESIGNER")
    stranger = SimpleNamespace(id="cmstranger00000000000000z", role="DESIGNER")
    admin = SimpleNamespace(id=ADMIN_ID, role="ADMIN")

    assert asyncio.run(grants.may_capture(WORKSHOP_ID, latecomer)) is True
    assert asyncio.run(grants.may_capture(WORKSHOP_ID, member)) is True
    assert asyncio.run(grants.may_capture(WORKSHOP_ID, admin)) is True
    assert asyncio.run(grants.may_capture(WORKSHOP_ID, stranger)) is False

    # THE ASYMMETRY, SPELLED OUT: the same person, the same workshop, two predicates, two answers.
    assert asyncio.run(viewers.has_viewer_grant(WORKSHOP_ID, LATECOMER_ID)) is False


def test_a_stranger_has_no_foothold_and_no_grant(world):
    """The control for everything above: nothing here is answering yes to everybody."""
    stranger = "cmstranger00000000000000z"
    assert asyncio.run(grants.provisional_member(WORKSHOP_ID, stranger)) is None
    assert asyncio.run(viewers.has_viewer_grant(WORKSHOP_ID, stranger)) is False


# --------------------------------------------------------------------------------------
# 3. THE TWO DECISIONS THAT MUST CLEAR IT
# --------------------------------------------------------------------------------------


def test_granting_promotes_the_late_comer_and_clears_the_foothold(world, monkeypatch):
    """**REQUIREMENT 6'S UPGRADE, AND IT IS ONE CLICK.**

    The grant writes ONE ``DesignWorkshopViewer`` row for the requester and clears the foothold in the
    SAME TRANSACTION, so the two cannot come apart.

    ⚠ IT USED TO GO THROUGH ``replace_viewers`` AND THAT WAS THE BUG. That function is a whole-set
    replace: it re-reads the workshop's viewers, diffs them against the set it was handed, and deletes
    the difference. So granting one requester from a set read ~750ms earlier deleted a viewer row a
    concurrent join-card redemption had just created — that person's redemption said FULL, their queue
    row said GRANTED, and they had no access — and re-created a viewer an admin had removed in the same
    window. This test used to assert the whole set was passed, which is precisely the shape that
    failed, so the assertion is now that ONE account is written and nothing is deleted.

    THE ORDERING COMMENT THAT USED TO BE HERE IS GONE WITH THE CALL IT WAS ABOUT. It said the foothold
    had to be deleted AFTER the grant, so that a 422 from the whole-set validation could not take away
    the one thing keeping this person's fieldwork reachable. The only refusal left — the REQUESTER's own
    eligibility — happens before either write, and the two writes are now one transaction, so there is
    no order for a reader to get wrong.

    Deleting the foothold destroys no fieldwork: ``DwStageEntry`` cascades from ``DesignWorkshop``
    and not from this row, so everything they captured survives — and becomes readable to them for
    the first time.
    """
    asked: list[set[str]] = []

    async def _eligible(user_ids: set[str]) -> None:
        asked.append(set(user_ids))

    monkeypatch.setattr(access, "_assert_every_id_may_be_granted", _eligible)

    # A COLLEAGUE WHO WAS ALREADY ON THE WORKSHOP, and the point of putting her here: the old
    # whole-set write had to be handed her id to avoid deleting her. An additive write never sees her.
    bystander = "cmprovision0000000000000z"
    world.designworkshopviewer.rows.append(_viewer(bystander))

    result = asyncio.run(
        access.decide(
            REQUEST_ID,
            decision="GRANTED",
            note="Rekha confirmed her",
            admin=SimpleNamespace(id=ADMIN_ID, role="ADMIN"),
        )
    )

    assert asked == [{LATECOMER_ID}], (
        "the requester and nobody else: validating the whole workshop is how a colleague's lapsed "
        "empanelment came to refuse an unrelated grant"
    )
    written = [row for row in world.designworkshopviewer.rows if row.userId == LATECOMER_ID]
    assert len(written) == 1
    # AN ADMIN'S DECISION IS THAT ADMIN'S, not the card issuer's. The card's provenance is on the
    # redemption row and stays there; this row records who actually decided.
    assert written[0].grantedById == ADMIN_ID
    # AND NO CARD DECIDED THIS, which is what makes `decidedById` being NULL readable as "a card did"
    # on the rows where a card really did.
    assert written[0].tokenId is None
    # THE BYSTANDER IS UNTOUCHED, which is the whole finding.
    assert bystander in {row.userId for row in world.designworkshopviewer.rows}

    assert world.designworkshopprovisionalmember.rows == [], "the foothold must not outlive the grant"
    assert asyncio.run(viewers.has_viewer_grant(WORKSHOP_ID, LATECOMER_ID)) is True
    assert result["status"] == "GRANTED"
    assert result["requesterIsProvisional"] is False


def test_refusing_actually_takes_the_foothold_away(world):
    """**OR THE REFUSAL IS A LIE**, and this is the gap that had to be closed in the same change.

    Without this, an admin pressing Refuse writes DENIED on the queue row and the person carries on
    capturing into the workshop: a sentence on a screen that is false in the direction that matters,
    which is precisely what the 409 on the other arm exists to prevent.

    NOTHING THEY RECORDED IS DELETED — ``DwStageEntry`` cascades from the workshop, not from this row
    — so their fieldwork survives and simply stops being theirs to read. Whether a refused designer's
    entries stay in the report is an owner's decision and not a side effect of a click.
    """
    result = asyncio.run(
        access.decide(
            REQUEST_ID,
            decision="DENIED",
            note="not on this cluster",
            admin=SimpleNamespace(id=ADMIN_ID, role="ADMIN"),
        )
    )
    assert result["status"] == "DENIED"
    assert world.designworkshopprovisionalmember.rows == []
    assert asyncio.run(grants.may_capture(WORKSHOP_ID, SimpleNamespace(id=LATECOMER_ID, role="DESIGNER"))) is False
    assert asyncio.run(viewers.has_viewer_grant(WORKSHOP_ID, LATECOMER_ID)) is False


def test_a_full_member_can_still_be_refused_without_a_foothold_in_the_way(world):
    """The 409 still fires for somebody who really does have access, and the new field did not move it.

    This is the guard ``test_design_workshop_access_decide_guard`` is entirely about, re-asserted here
    only because :func:`_provisional_by_pair` landing beside ``_access_by_pair`` is exactly the kind of
    change that folds two predicates into one by accident.
    """
    world.designworkshopaccessrequest.rows = [_request_row(requester_id=MEMBER_ID)]
    with pytest.raises(HTTPException) as refusal:
        asyncio.run(
            access.decide(
                REQUEST_ID,
                decision="DENIED",
                note="",
                admin=SimpleNamespace(id=ADMIN_ID, role="ADMIN"),
            )
        )
    assert refusal.value.status_code == 409
