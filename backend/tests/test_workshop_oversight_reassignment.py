"""**NAMING A DIFFERENT DESIGNER ON A WORKSHOP, AND THE THREE WAYS IT USED TO ONLY HALF HAPPEN.**

``PUT /api/design-workshop-oversight/{id}/designer`` is presented, by its own route name, by
``DesignWorkshopDesignerIn``'s docstring ("Somebody who wants that is replacing the designer, which
is this route") and by the officers panel's "Currently: <name>", as a REPLACEMENT. Three separate
things about it were additions instead, and each was silent:

1. **THE OUTGOING DESIGNER KEPT THEIR VIEWER ROW.** ``attach_the_named_designer`` is
   ``add_one_viewer``, which the service's own docstring defends as "emphatically not
   ``replace_viewers``" — correctly, for concurrency — but nothing removed the row it was replacing.
   ``load_workshop_or_404(for_edit=True)`` admits a viewer row without ever asking whether its
   holder is the NAMED designer, so the designer the ministry had just taken off the workshop could
   go on saving stages, replacing photographs, recording dictation consent and pushing the report
   into PRE_SUBMISSION. Nothing could report it or undo it either: the only viewer removal in the
   backend was ``replace_viewers`` behind ``require_admin`` = {ADMIN, MASTER_ADMIN}, and a
   MINISTRY_ADMIN — the role that performs this act — is outside that set, while
   ``read_workshop_oversight`` returns no viewer list for them to look at.

2. **A DESIGNER WITH NO PROFILE ROW MOVED NO NAME AT ALL.** ``prefill_from_profile`` returns ``{}``
   outright when there is no ``DesignerProfile``, and its account-name fallback sits after that
   early return. On the CREATE path an empty designer block is honest and argued for. On THIS path
   nothing empties the block: no stage is written, and the promoted ``designerName`` column — the
   .docx ``dc:creator``, the workshop summary, the data export column, the thing every list sorts on
   — KEEPS THE PREVIOUS DESIGNER'S NAME, under a 200 whose only hint is ``stagesWritten: []``.
   Profile rows are minted lazily, so an account created through ``POST /api/users`` that has never
   opened its own profile screen is exactly this case.

3. **A FILED REPORT COULD BE RE-ATTRIBUTED.** ``_CLOSED_STATUSES`` was the literal
   ``{"SUBMITTED", "ARCHIVED"}`` — the whole of "filed" under the vocabulary this product had until
   2026-09-13 — and covered neither of the two states a handed-in report is actually in today. That
   half is asserted in ``tests/test_workshop_oversight_unit.py``, which needs no database.

**POSTGRES IS REQUIRED.** Every property here is a ROW: which viewer rows exist afterwards, and what
the promoted column says. None of it can be read off the source, because all three defects were
functions that ran and returned successfully.

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

from __future__ import annotations

import uuid
from typing import Any

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import hash_password
from app.services import design_workshop_oversight as oversight

pytestmark = pytest.mark.anyio

PASSWORD = "workshop-oversight-reassignment-password"


@pytest.fixture
def anyio_backend():
    return "asyncio"


async def _designer(stamp: str, slug: str, name: str, *, with_profile: bool) -> Any:
    """One empanelled designer, with or without the lazily-created ``DesignerProfile`` row.

    THE PROFILE IS THE VARIABLE, because "has this account ever opened its own profile screen" is
    the only difference between the two halves of defect 2 and it is invisible everywhere else: the
    picker (``workshop_capable_accounts``) filters on ``User.role`` and roster emails with no
    profile join, so both accounts are offered identically.
    """
    address = f"oversight-reassign-{slug}-{stamp}@example.org"
    user = await db.user.create(
        data={
            "email": address,
            "name": name,
            "role": "DESIGNER",
            "passwordHash": hash_password(PASSWORD),
        }
    )
    await db.designerroster.create(
        data={
            "email": address,
            "fullName": name,
            "institution": "Directorate of Handicrafts",
            "isActive": True,
            "addedById": user.id,
        }
    )
    if with_profile:
        await db.designerprofile.create(
            data={"user": {"connect": {"id": user.id}}, "displayName": name}
        )
    return user


@pytest.fixture
async def world():
    """An officer, two designers with profiles, one without, and a workshop naming the first.

    The workshop is created the way the ordinary doors create one — an ADMIN creator plus a viewer
    row for the designer — because that is the state both live paths leave behind
    (``create_design_workshop`` and ``sanction_orders.create_from_sanction`` both set ``createdById``
    to the officer and grant the designer through ``attach_the_named_designer``). A fixture that put
    the designer in ``createdById`` would be testing a workshop this product does not make, and the
    residual-grant defect would be invisible in it.
    """
    stamp = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        officer = await db.user.create(
            data={
                "email": f"oversight-officer-{stamp}@example.org",
                "name": f"Ministry admin {stamp}",
                "role": "MINISTRY_ADMIN",
                "passwordHash": hash_password(PASSWORD),
            }
        )
        creator = await db.user.create(
            data={
                "email": f"oversight-creator-{stamp}@example.org",
                "name": f"Platform admin {stamp}",
                "role": "ADMIN",
                "passwordHash": hash_password(PASSWORD),
            }
        )
        outgoing = await _designer(stamp, "outgoing", "A. Sharma", with_profile=True)
        incoming = await _designer(stamp, "incoming", "B. Mohanty", with_profile=True)
        profileless = await _designer(stamp, "profileless", "C. Nayak", with_profile=False)
        stranger = await _designer(stamp, "codesigner", "D. Patnaik", with_profile=True)

        workshop = await db.designworkshop.create(
            data={
                "title": f"Reassignment fixture {stamp}",
                "templateId": "DCH_STANDARD",
                "createdById": creator.id,
                "status": "IN_PROGRESS",
                # THE PROMOTED COLUMN AS THE PREFILL WOULD HAVE LEFT IT when the outgoing designer
                # was named. It is the value under test: every assertion below is about whether it
                # moves.
                "designerName": "A. Sharma",
            }
        )
        await db.designworkshopviewer.create_many(
            data=[{"designWorkshopId": workshop.id, "userId": outgoing.id}]
        )
        yield {
            "officer": officer,
            "creator": creator,
            "outgoing": outgoing,
            "incoming": incoming,
            "profileless": profileless,
            "stranger": stranger,
            "workshop": workshop,
        }
    finally:
        await db.disconnect()


async def _viewer_ids(workshop_id: str) -> set[str]:
    rows = await db.designworkshopviewer.find_many(where={"designWorkshopId": workshop_id})
    return {row.userId for row in rows}


@needs_db
async def test_naming_a_different_designer_takes_the_outgoing_one_off_the_workshop(world) -> None:
    """**DEFECT 1.** The route says replace; before this it added.

    The residual row is not a cosmetic leftover — ``docs/PERMISSIONS.md`` states what it confers:
    "a viewer row confers every stage save, the AI-layer accept/withdraw, the dictation consent and
    the export ledger". So a designer removed from a ministry workshop could keep writing to it, and
    the officer who removed them had no route in the product that could take it away.
    """
    world_workshop = world["workshop"]
    answer = await oversight.reassign_designer(
        world_workshop, world["incoming"].id, actor=world["officer"]
    )

    assert await _viewer_ids(world_workshop.id) == {world["incoming"].id}, (
        "the outgoing designer still holds a viewer row, so they still hold every stage write on a "
        "workshop the ministry has reassigned away from them"
    )
    assert answer["removedDesigner"]["userId"] == world["outgoing"].id
    assert answer["stillHaveAccess"] == [], (
        "the answer must name who ELSE can still write to this workshop; the officers screen has "
        "no viewer list and no route that could show them"
    )


@needs_db
async def test_a_co_designer_the_reassignment_did_not_name_keeps_access_and_is_reported(
    world,
) -> None:
    """AMBIGUITY REMOVES NOBODY, AND SAYS SO.

    A co-designer, a redeemed join card and a granted access request all leave viewer rows this
    route must not touch — it names ONE designer and knows nothing about anybody else's membership,
    which is the same argument ``add_one_viewer`` makes against widening itself to a set. What was
    unacceptable was that the survivors were INVISIBLE: ``read_workshop_oversight`` returns only
    ``designerName`` plus the two oversight rows, and the only viewer screen in the product is
    behind ``require_admin``, which the assigning MINISTRY_ADMIN is outside.
    """
    workshop = world["workshop"]
    await db.designworkshopviewer.create_many(
        data=[{"designWorkshopId": workshop.id, "userId": world["stranger"].id}]
    )

    answer = await oversight.reassign_designer(workshop, world["incoming"].id, actor=world["officer"])

    assert world["stranger"].id in await _viewer_ids(workshop.id), (
        "a co-designer was removed by a call that named somebody else; this route knows nothing "
        "about their membership and must not decide it"
    )
    assert [row["userId"] for row in answer["stillHaveAccess"]] == [world["stranger"].id]
    assert answer["removedDesigner"]["userId"] == world["outgoing"].id


@needs_db
async def test_the_outgoing_designer_is_not_guessed_at_when_two_viewers_would_write_one_name(
    world,
) -> None:
    """Deleting a viewer row is a REVOCATION, and a revocation made on a guess is worse than a stale
    grant the caller is told about.

    Two accounts whose profiles would both write "A. Sharma" is a real state — a designer with two
    accounts, or two people who share a name — and there is no column that could break the tie:
    ``DesignWorkshop`` carries ``designerName`` and a set of viewer rows and nothing that says which
    row is the lead, which is the arrangement the "why there is no ``designer`` capacity" note
    defends. So nobody is removed and both are reported.
    """
    workshop = world["workshop"]
    twin = await _designer(uuid.uuid4().hex[:8], "twin", "A. Sharma", with_profile=True)
    await db.designworkshopviewer.create_many(
        data=[{"designWorkshopId": workshop.id, "userId": twin.id}]
    )

    answer = await oversight.reassign_designer(workshop, world["incoming"].id, actor=world["officer"])

    assert answer["removedDesigner"] is None
    assert {world["outgoing"].id, twin.id} <= await _viewer_ids(workshop.id)
    assert {row["userId"] for row in answer["stillHaveAccess"]} == {
        world["outgoing"].id,
        twin.id,
    }, "an ambiguous answer has to be REPORTED; silence is what made the stale grant invisible"


@needs_db
async def test_a_designer_who_has_never_opened_their_profile_still_moves_the_promoted_column(
    world,
) -> None:
    """**DEFECT 2, AND IT IS THE ONE THAT PUT THE WRONG NAME ON A MINISTRY DOCUMENT.**

    ``prefill_from_profile`` answers ``{}`` for an account with no ``DesignerProfile`` row, and its
    account-name fallback is below that early return. ``by_entity`` then came back empty, no
    ``save_stage`` ran, and the promoted ``designerName`` column kept "A. Sharma" — on the .docx
    cover's ``dc:creator``, on the workshop summary, in the xlsx export column and in every list
    that filters or sorts on it — while the route answered 200 and the officers panel re-rendered
    "Currently: A. Sharma" from the server's own reply.

    The account here is created the way an admin creates one (``POST /api/users`` makes a User, an
    AccessRoster row and a DesignerRoster row and NO profile), so this is the ordinary case rather
    than an edge one.
    """
    workshop = world["workshop"]
    assert (
        await db.designerprofile.find_unique(where={"userId": world["profileless"].id})
    ) is None, "the fixture is not exercising the branch this test is about"

    answer = await oversight.reassign_designer(
        workshop, world["profileless"].id, actor=world["officer"]
    )

    assert answer["designerName"] == "C. Nayak", (
        f"the promoted column reads {answer['designerName']!r}; a workshop reassigned to a designer "
        "with no profile row still names the designer it was taken away from"
    )
    reread = await db.designworkshop.find_unique(where={"id": workshop.id})
    assert reread.designerName == "C. Nayak"
    assert answer["stagesWritten"], "no stage was written, so nothing can have moved the column"


@needs_db
async def test_the_reassignment_never_writes_the_officers_own_name_into_the_report(world) -> None:
    """The defect ``seed_designer_prefill``'s docstring names, asserted against rows.

    ``prefill_from_profile(user_id or actor.id)`` is one plausible extra word, and what it produces
    is an administrator who picked a designer off a list and got their OWN name onto the cover of a
    ministry report — with completeness scoring 100% and the only detector being a human reading it.
    ``tests/test_workshop_oversight_unit.py`` asserts the absence of that word at the source; this
    asserts the outcome, because a second fallback added anywhere else in the chain would satisfy
    the source test and still produce the wrong name.
    """
    workshop = world["workshop"]
    answer = await oversight.reassign_designer(
        workshop, world["incoming"].id, actor=world["officer"]
    )
    assert answer["designerName"] == "B. Mohanty"
    assert world["officer"].name not in str(answer["designerName"])
