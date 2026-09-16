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

── AND A FOURTH THING, WHICH WAS NOT A DEFECT IN THE ROUTE BUT THE ABSENCE OF ONE (0.0.12) ─────

``PUT …/{id}/designers`` — :func:`~app.services.design_workshop_oversight.set_named_designers` — is
the whole-TEAM door added beside the singular one, and the last third of this file is its rows.
The two are not alternatives: the singular one answers *whose name is on the report*, the plural one
answers *who may open the workshop*. A Design & Prototype Development Workshop is run by two
designers alongside a master craftsperson and a reviewing officer, every one of whom needs the 22
stages, and until 0.0.12 a MINISTRY_ADMIN could grant that to exactly one of them and could take it
away from NOBODY — ``replace_viewers`` is behind ``require_admin``, a set they are outside.

**THE PROPERTY THE NEW TESTS EXIST FOR IS THE ONE A HAPPY PATH WOULD MISS.** Adding a co-designer
must not run the prefill arm: that rewrites stage 1, re-copies a ``DesignerProfile`` and moves the
promoted ``designerName`` — the .docx ``dc:creator`` — so "also give Rekha access" would
re-attribute the report under a 200, with a human reading the cover as the only detector. That is
defect 2 in a new place, and it is asserted here against the column rather than against the source.

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


# ════════════════════════════════════════════════════════════════════════════════════════════════
# THE WHOLE-TEAM DOOR — `set_named_designers`, new in 0.0.12
#
# The same fixture, because the state under test is the same state: a workshop whose promoted
# column names one designer, with that designer holding the only viewer row. What differs is the
# act — a SET rather than a replacement — and every assertion below is about a row or a column.
# ════════════════════════════════════════════════════════════════════════════════════════════════


@needs_db
async def test_a_co_designer_can_be_added_without_touching_the_reports_designer(world) -> None:
    """**THE HALF OF THIS DOOR THAT IS A GRANT**, and the half that must not come with it.

    Before 0.0.12 the only way to give a second designer access from ``/officers`` was to NAME them,
    which moved the report's author. So a workshop run by two designers either had one of them
    locked out of the record or had the wrong name on its cover. Adding one must therefore leave
    ``designerName`` exactly as it stands — and ``stagesWritten`` empty is the machine-readable
    half of that: an empty list means the prefill arm did not run at all.
    """
    workshop = world["workshop"]
    answer = await oversight.set_named_designers(
        workshop,
        user_ids=[world["outgoing"].id, world["stranger"].id],
        lead_user_id=None,
        actor=world["officer"],
    )

    assert await _viewer_ids(workshop.id) == {world["outgoing"].id, world["stranger"].id}
    assert answer["designerName"] == "A. Sharma", (
        "adding a co-designer moved the name on the report's cover; that is defect 2 in a new place"
    )
    assert answer["stagesWritten"] == [], "the prefill arm ran on a save that only added access"
    assert answer["removedDesigners"] == []
    reread = await db.designworkshop.find_unique(where={"id": workshop.id})
    assert reread.designerName == "A. Sharma"


@needs_db
async def test_a_co_designer_can_be_taken_off_and_the_answer_names_them(world) -> None:
    """**THE REMOVAL THAT HAD NO ROUTE ANYWHERE A MINISTRY ADMIN COULD REACH.**

    ``remove_one_viewer``'s own docstring names the defect verbatim: *"before this existed the
    officer who performed a replacement had no route anywhere that could take the replaced
    designer's access away, and no screen that would even have shown it to them."* This is the
    second half of that sentence. The answer names who lost access because a viewer row IS write
    access to every stage — a silent stale grant was the whole defect the first time.
    """
    workshop = world["workshop"]
    await db.designworkshopviewer.create_many(
        data=[{"designWorkshopId": workshop.id, "userId": world["stranger"].id}]
    )

    answer = await oversight.set_named_designers(
        workshop, user_ids=[world["outgoing"].id], lead_user_id=None, actor=world["officer"]
    )

    assert await _viewer_ids(workshop.id) == {world["outgoing"].id}
    assert [row["userId"] for row in answer["removedDesigners"]] == [world["stranger"].id]
    assert answer["removedDesigners"][0]["name"] == "D. Patnaik", (
        "the officer is shown an account id rather than a person"
    )
    assert answer["designerName"] == "A. Sharma"
    assert answer["stagesWritten"] == []


@needs_db
async def test_taking_every_designer_off_a_workshop_that_names_one_is_refused(world) -> None:
    """NOBODY IS THE DESIGNER is not an expressible state, and the empty set does not become it.

    ``DesignWorkshopDesignerIn`` refuses it structurally on the singular door — ``designerId`` is
    ``min_length=1``. A whole-set body can SEND an empty list and has to, because a workshop that
    has never named anybody is the ordinary starting state, so the refusal lives where the workshop
    row is readable. Reaching the state would blank the promoted column, which is the write
    ``_coerce_promoted`` exists to stop happening by accident.

    **AND NOTHING IS WRITTEN ON THE WAY TO THE REFUSAL**, which is the row-level half a source read
    cannot show: an all-or-nothing validation that had already removed somebody would be worse than
    no validation at all.
    """
    workshop = world["workshop"]
    with pytest.raises(Exception) as exc:
        await oversight.set_named_designers(
            workshop, user_ids=[], lead_user_id=None, actor=world["officer"]
        )
    assert getattr(exc.value, "status_code", None) == 422
    assert await _viewer_ids(workshop.id) == {world["outgoing"].id}, (
        "a refusal that had already taken the designer's access away"
    )


@needs_db
async def test_dropping_the_named_designer_without_a_replacement_is_refused(world) -> None:
    """Taking the report's own author off is NAMING SOMEBODY ELSE, not a deletion.

    The co-designer beside them may go freely, so the refusal has to be about the LEAD specifically
    rather than about the set shrinking — which is why the lead is resolved from the rows rather
    than assumed to be the first of them.
    """
    workshop = world["workshop"]
    await db.designworkshopviewer.create_many(
        data=[{"designWorkshopId": workshop.id, "userId": world["stranger"].id}]
    )

    with pytest.raises(Exception) as exc:
        await oversight.set_named_designers(
            workshop, user_ids=[world["stranger"].id], lead_user_id=None, actor=world["officer"]
        )
    assert getattr(exc.value, "status_code", None) == 422
    assert "A. Sharma" in str(getattr(exc.value, "detail", "")), (
        "the refusal must name the designer it is about; an officer cannot act on 'that designer'"
    )
    assert await _viewer_ids(workshop.id) == {world["outgoing"].id, world["stranger"].id}


@needs_db
async def test_naming_a_different_lead_in_the_set_moves_the_column_and_the_access_together(
    world,
) -> None:
    """**THE REPLACEMENT, EXPRESSED AS A SET** — and both halves have to land.

    ``leadUserId`` moves the promoted column through the shared prefill; dropping the outgoing
    designer from ``userIds`` takes their viewer row. Doing one without the other is the pair of
    defects this whole file is named after: a cover page naming somebody who cannot open the record,
    or a designer removed from a ministry workshop who can still write to it.
    """
    workshop = world["workshop"]
    answer = await oversight.set_named_designers(
        workshop,
        user_ids=[world["incoming"].id],
        lead_user_id=world["incoming"].id,
        actor=world["officer"],
    )

    assert await _viewer_ids(workshop.id) == {world["incoming"].id}
    assert [row["userId"] for row in answer["removedDesigners"]] == [world["outgoing"].id]
    assert answer["designerName"] == "B. Mohanty"
    assert answer["stagesWritten"], "no stage was written, so nothing can have moved the column"
    reread = await db.designworkshop.find_unique(where={"id": workshop.id})
    assert reread.designerName == "B. Mohanty"
    assert {row["userId"] for row in answer["designers"]} == {world["incoming"].id}
    assert [row["isLead"] for row in answer["designers"]] == [True]


@needs_db
async def test_a_lead_with_no_profile_row_still_moves_the_promoted_column_through_this_door(
    world,
) -> None:
    """**DEFECT 2, ASSERTED ON THE SECOND DOOR**, because it is a defect of the BLOCK, not the route.

    ``prefill_from_profile`` answers ``{}`` for an account that has never opened its own profile
    screen, and its account-name fallback sits below that early return — so without the
    ``get_or_create_profile`` mint, no stage is written and the promoted column keeps the PREVIOUS
    designer's name on the cover, the .docx ``dc:creator`` and every list that sorts on it. The
    block is shared by both doors precisely so this cannot be true of one and false of the other;
    this is the assertion that says the sharing actually happened.
    """
    workshop = world["workshop"]
    assert (
        await db.designerprofile.find_unique(where={"userId": world["profileless"].id})
    ) is None, "the fixture is not exercising the branch this test is about"

    answer = await oversight.set_named_designers(
        workshop,
        user_ids=[world["profileless"].id],
        lead_user_id=world["profileless"].id,
        actor=world["officer"],
    )

    assert answer["designerName"] == "C. Nayak"
    reread = await db.designworkshop.find_unique(where={"id": workshop.id})
    assert reread.designerName == "C. Nayak"
    assert world["officer"].name not in str(answer["designerName"])


@needs_db
async def test_the_workshops_creator_in_the_body_is_a_no_op_rather_than_a_second_grant(
    world,
) -> None:
    """Their access is ``createdById``, and a viewer row beside it is a SECOND source of truth.

    ``attach_the_named_designer`` refuses to write one and ``_deduplicate`` drops them on the
    viewers PUT for the same stated reason. The body has to tolerate the id rather than refuse the
    whole save: the officer ticked the person the screen told them opened the workshop.
    """
    workshop = world["workshop"]
    answer = await oversight.set_named_designers(
        workshop,
        user_ids=[world["outgoing"].id, world["creator"].id],
        lead_user_id=None,
        actor=world["officer"],
    )

    assert await _viewer_ids(workshop.id) == {world["outgoing"].id}, (
        "the creator was given a redundant viewer row, so revoking their access now takes two "
        "deletions in two tables"
    )
    assert world["creator"].id not in {row["userId"] for row in answer["designers"]}


@needs_db
async def test_the_read_the_screen_was_missing_marks_exactly_one_row_as_the_lead(world) -> None:
    """``named_designer_rows`` — the key whose ABSENCE was headline finding 3 of the investigation.

    ``GET /design-workshop-oversight/{id}`` answered ``designerName`` as a STRING with no ids in it,
    so the one screen that decides who a workshop is for could not pre-tick a picker and had nothing
    to compare a change against. ``isLead`` is carried on EVERY row, including ``False``, so a
    client can tell "not the lead" from "this server does not answer the question".
    """
    workshop = world["workshop"]
    await db.designworkshopviewer.create_many(
        data=[{"designWorkshopId": workshop.id, "userId": world["stranger"].id}]
    )
    reread = await db.designworkshop.find_unique(where={"id": workshop.id})

    rows = await oversight.named_designer_rows(reread)

    assert {row["userId"] for row in rows} == {world["outgoing"].id, world["stranger"].id}
    assert all("isLead" in row for row in rows)
    assert [row["userId"] for row in rows if row["isLead"]] == [world["outgoing"].id]
    assert world["creator"].id not in {row["userId"] for row in rows}, (
        "the creator is in `designers`; they hold no viewer row and an empty list here means "
        "'nobody but whoever opened it', which the screen says in words"
    )


@needs_db
async def test_unfiling_an_artisan_clears_the_link_and_deletes_no_record(world) -> None:
    """**UNFILES; NEVER DELETES** — and answers ``False`` rather than raising when there is nothing
    to do.

    ``Artisan.designWorkshopId`` is a nullable link and ``Artisan.createdBy`` is ``Restrict``: an
    officer did not author these regulated person-records and a roster correction must not be a way
    to destroy one. The second call is the ordinary case of two officers working one list — "that
    artisan is already off this roster" is a state to report, not a 500 from
    ``RecordNotFoundError`` — and the third is the reason the predicate carries the workshop: a
    stale screen must not be able to unfile a record from somewhere it was not looking at.
    """
    workshop = world["workshop"]
    other = await db.designworkshop.create(
        data={
            "title": f"Roster neighbour {uuid.uuid4().hex[:8]}",
            "templateId": "DCH_STANDARD",
            "createdById": world["creator"].id,
        }
    )
    artisan = await db.artisan.create(
        data={
            "name": "Roster artisan",
            "place": "Puri",
            "designWorkshopId": workshop.id,
            "createdById": world["creator"].id,
        }
    )

    assert await oversight.unlink_artisan_from_workshop(workshop.id, artisan.id) is True
    assert await oversight.unlink_artisan_from_workshop(workshop.id, artisan.id) is False

    still_there = await db.artisan.find_unique(where={"id": artisan.id})
    assert still_there is not None, "an unfiling deleted a regulated person-record"
    assert still_there.designWorkshopId is None

    await db.artisan.update(where={"id": artisan.id}, data={"designWorkshopId": other.id})
    assert await oversight.unlink_artisan_from_workshop(workshop.id, artisan.id) is False, (
        "a stale screen unfiled a record from a workshop it was not looking at"
    )
    reread = await db.artisan.find_unique(where={"id": artisan.id})
    assert reread.designWorkshopId == other.id
