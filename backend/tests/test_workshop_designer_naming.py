"""Whose details a new workshop starts with — the designer's, not the admin's who opened it.

═════════════════════════════════════════════════════════════════════════════════════════════════
THE DEFECT THIS MODULE GUARDS, STATED ONCE
═════════════════════════════════════════════════════════════════════════════════════════════════

Requirement 3, verbatim: "All information entered on the Designer Page should be treated as the
designer's master profile information and should be automatically pre-filled in every report that
the designer creates, while still allowing report-specific modifications where applicable."

``seed_designer_prefill`` copied the profile of the account that pressed create, and
``assert_can_create_design_workshops`` guarantees that account is an ADMIN, because a DESIGNER may
not open a workshop at all. So a designer's profile pre-filled nothing, ever. Every report named
the admin who opened the workshop — on the cover, in the certification block, in the .docx's own
``dc:creator`` — and **every automatic check in the product agreed the document was correct**,
because ``designerName`` was not missing, it was filled with the wrong person: completeness read
100%, the readiness screen was green, ``build_report`` emitted no warning. The only detector was a
human reading a stranger's name in a box labelled "Designer".

``DesignWorkshopCreate.designerUserId`` closes it: the admin names the designer the workshop is FOR,
their profile is what is copied, and they are put on the workshop in the same call.

═════════════════════════════════════════════════════════════════════════════════════════════════
WHY THESE TESTS HAVE NO DATABASE, WHICH IS WHY THIS FILE EXISTS BESIDE THE OTHER TWO
═════════════════════════════════════════════════════════════════════════════════════════════════

The end-to-end behaviour is pinned in ``tests/test_designer_roster.py``, which creates real
workshops through the real routes — and which **skips itself whenever ``DATABASE_URL`` does not
point at a local database**, which on this project's machines is most of the time. The four
properties below are the ones it would be most expensive to lose, and every one of them is a
statement about one Python function's branches rather than about a row in Postgres:

1. WHOSE profile is read — the named designer's, and the actor's when nobody is named;
2. that there is **no fallback** from a named designer with no profile to the actor's profile: the
   one plausible extra ``or`` that would silently restore the whole defect;
3. that the AUTHORSHIP stamp and ``createdById`` stay with the ACTOR, so nothing manufactures an
   audit trail saying a designer set twenty-one fields they have never seen;
4. that the seeding path never so much as LOOKS at ``DesignWorkshopViewer``, which is what makes "a
   workshop with three designer grantees" incapable of producing a guess.

Same argument as ``test_designer_prefill_contract.py``'s header, for the same reason: a guard that
is skipped precisely when somebody is working offline is not a guard.

Importing ``stage_definitions`` installs the twenty-two stages into the registry and is the only
cost here. The seeding loop asks the REGISTRY which stage each key belongs to, so these tests run
against the real ``workshopSetup`` and ``workshopPlan`` entities rather than against a fixture's
idea of them — a stub registry would happily agree with a prefill that wrote into a stage no form
reads.
"""

from __future__ import annotations

import inspect
from types import SimpleNamespace
from typing import Any

import pytest

# Importing this module is what installs the twenty-two stages into the registry.
import app.services.stage_definitions  # noqa: F401
from app.api.routes import design_workshops as routes
from app.schemas.design_workshops import DesignWorkshopCreate
from app.services import design_workshops as service

pytestmark = pytest.mark.anyio

STAGE_1 = "WORKSHOP_SETUP"
STAGE_3 = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"

#: The two accounts every test here needs, and they are TWO rather than one on purpose: the whole
#: defect was one account standing in for both. ``ACTOR`` is the admin who presses create;
#: ``DESIGNER_ID`` is the person the workshop is for, whose name belongs on the ministry document.
ACTOR = SimpleNamespace(id="admin-1", name="Sunil Patnaik", role="ADMIN")
DESIGNER_ID = "designer-1"
CREATOR_ID = ACTOR.id

#: What each account's ``DesignerProfile`` answers, keyed by user id. Deliberately disjoint values:
#: an assertion that named the same institution for both could not fail.
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
    # An account that has never opened the Designer Page. Present as a KEY with no values so the
    # stub answers ``{}`` for it exactly as ``prefill_from_profile`` answers for a missing profile
    # row, rather than raising and being swallowed by the seed's blanket ``except``.
    "designer-with-no-profile": {},
}


@pytest.fixture
def anyio_backend():
    return "asyncio"


class _StageEntryTable:
    """``db.dwstageentry`` with only ``create`` on it, recording every row written.

    Deliberately not a mock library and not a fake that accepts any method: a stub this small fails
    loudly if the function under test starts doing something else — a second query, a read of the
    viewer table, an update — instead of absorbing it silently. That property is doing real work
    here, because ``seed_designer_prefill`` wraps its whole body in a blanket ``except`` so that a
    convenience value can never fail a create: any ``AttributeError`` this stub raises would be
    SWALLOWED, and the test would merely see "no rows written". So every test below asserts on rows
    that were actually written, and :func:`_seed` asserts nothing was logged as an exception — a
    swallowed stub error surfaces as a failure rather than as a pass.
    """

    def __init__(self) -> None:
        self.rows: list[dict[str, Any]] = []

    async def create(self, data: dict[str, Any]) -> Any:
        self.rows.append(data)
        return SimpleNamespace(id=f"entry-{len(self.rows)}")


class _WorkshopTable:
    """``db.designworkshop`` with only the promoted-column ``update`` the seed performs."""

    def __init__(self, record: Any) -> None:
        self._record = record
        self.updates: list[dict[str, Any]] = []

    async def update(self, where: dict[str, Any], data: dict[str, Any]) -> Any:
        self.updates.append(data)
        return SimpleNamespace(**{**vars(self._record), **data})


class _StubDb:
    """``design_workshops.db`` with exactly the two tables the seed is allowed to touch.

    **``designworkshopviewer`` IS ABSENT, AND THAT ABSENCE IS AN ASSERTION.** The one thing this
    change must never do is decide for itself which of a workshop's grantees is "the designer":
    that table has no ``role``, no lead flag, and no ordering that survives a multi-name PUT, since
    one ``create_many`` gives every grantee the same ``createdAt``. A seed that read it would be
    guessing, and a guess prints a plausible wrong name on a ministry document — worse than the
    defect it replaced, which was at least consistent. Reaching for the attribute raises here, and
    :func:`_seed` turns that into a visible failure.
    """

    def __init__(self, record: Any) -> None:
        self.dwstageentry = _StageEntryTable()
        self.designworkshop = _WorkshopTable(record)


async def _seed(
    monkeypatch: Any, *, designer_id: str | None, extra: dict[str, Any] | None = None
) -> tuple[_StubDb, list[str]]:
    """Run the real ``seed_designer_prefill`` against the real registry and a stub database.

    ``prefill_from_profile`` is replaced by a lookup in :data:`PROFILES` that RECORDS WHICH ID IT
    WAS ASKED ABOUT, which is the fact three of the tests below are entirely about. Returns the stub
    database and the ids the prefill asked for, in order.
    """
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
    """The one row the seed wrote for a stage, with a message naming what it wrote instead.

    ``data`` and ``fieldProvenance`` are unwrapped from Prisma's ``Json`` box on the way out. Every
    write to a Json column goes through ``design_workshops._json`` because a raw dict reaching one
    is a 500 from the driver rather than a 422 — so what the stub records is the box, and asserting
    through it would read as ``row["data"].data["designerName"]``.
    """
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
    """Everything the seed wrote, as one string, for "this name must appear nowhere" assertions."""
    return str([getattr(row["data"], "data", row["data"]) for row in db.dwstageentry.rows])


# ── 1. Whose profile is copied ───────────────────────────────────────────────────────────────────


async def test_naming_a_designer_copies_THEIR_profile_and_never_asks_about_the_admin(monkeypatch):
    """The requirement as one assertion: the report names the designer, not the admin.

    Both halves are checked, because either alone can pass while the feature is broken. The VALUES
    have to be the designer's — that is what the cover, the sign-off block and ``dc:creator`` print.
    And the profile READ has to be for the designer's id ALONE: a read of both, with the actor's
    values filling any gap, is the fallback that would restore the defect wherever a designer's
    profile is thin, which is exactly where nobody would go looking for it.
    """
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
    """The old behaviour, unchanged — which is what makes the field additive rather than a flag day.

    If omitting the field changed anything, adopting it would have been a release-day behaviour swap
    on every existing client instead of a mechanism they take up one at a time. That mattered when
    this test was written and it still matters now, for a different reason: **both clients send the
    field as of 2026-08-26** — the web create form and its offline draft store
    (``frontend/components/designworkshop/WorkshopDesignerPicker.tsx``, ``DwCreateBody``,
    ``DwDraftHeader``) and Android's create body and sync arm — but an older APK in the field does
    not, and a handset is updated when its owner next has signal and a reason to.

    So naming nobody is no longer only "the client has not adopted it". It is now ALSO the answer an
    admin gives on purpose — the picker offers "Not decided yet" because a workshop is opened in a
    room on day one — and the offline create path still cannot reach the eligibility picker at all,
    because eligibility is two roster reads on the server. All three roads lead here, and the seed
    must behave identically down each: the CREATOR's profile is copied, with no fallback of any kind
    between that and a named designer.

    ``test_designer_roster.test_a_workshop_that_names_no_designer_still_carries_the_ADMINS_details``
    pins the same outcome against a real database under its own name.
    """
    db, asked = await _seed(monkeypatch, designer_id=None)

    assert asked == [ACTOR.id]
    assert _entry(db, STAGE_1)["data"]["designerName"] == "Sunil Patnaik"


async def test_a_named_designer_with_no_profile_leaves_the_box_EMPTY_and_does_not_fall_back(
    monkeypatch,
):
    """**THE MISSING ``or``. This is the test that protects against a one-word regression.**

    ``prefill_from_profile(designer_id or actor.id)`` picks whose profile. A second,
    plausible-looking fallback further down — "if the designer answered nothing, use the creator's"
    — would restore the entire defect in the case where it is hardest to notice: an admin picks a
    designer off a list and gets their OWN name back on the cover.

    WHY A BLANK IS THE RIGHT ANSWER AND NOT A DEGRADATION TO APOLOGISE FOR. ``designerName`` is a
    required Basic-tier stage-1 field, so an empty one is counted by the completeness score, shown
    on the readiness screen and named in ``build_report``'s warnings. The admin's name in that same
    box is counted as COMPLETE and warned about by nothing — it was 100% green and wrong. A blank a
    machine can see beats a confident wrong name no machine can, which is Rule 10 discharged by
    machinery that already exists rather than by a banner nobody has built.
    """
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
    """``extra`` is the create form's craft/cluster/state/district, and it is not the designer's.

    It rides in the same ``workshopSetup`` singleton as ``designerName``, because two creates for
    one singleton entity would be two rows where every matcher in ``save_stage`` expects one — and
    because with no stage entry behind them the promoted COLUMNS were nulled by the first stage-1
    save, an outage that cost a fortnight of one workshop's visibility in every list and filter.
    Naming a designer must not disturb that: two sets of keys, answered by two different people,
    landing in one row.
    """
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
    """Nineteen of the twenty-one profile fields live on stage 3, and that is where the cost was.

    A designer who never opens stage 3 submits whatever is in those boxes without ever seeing
    them — somebody else's biography, phone number, address, empanelment number, photograph and
    signature. Stage 1 is the box a human might notice; stage 3 is the nineteen they would not.
    """
    db, _ = await _seed(monkeypatch, designer_id=DESIGNER_ID)

    data = _entry(db, STAGE_3)["data"]
    assert data["designerExperience"] == 12, "an INT field must arrive as an int"
    assert "ikat" in str(data["designerProfile"]).lower()
    assert "administering cluster development" not in str(data["designerProfile"]).lower()


# ── 2. Authorship: the value is the designer's, the WRITE is the admin's ─────────────────────────


async def test_the_row_is_authored_by_the_ADMIN_and_never_by_the_designer(monkeypatch):
    """The provenance decision, pinned so "fixing the attribution" cannot happen by accident.

    ``entry_provenance`` declares exactly TWO sources — ``reference`` (copied off a record somebody
    else recorded) and ``designer`` (a person working on this workshop set it) — and that sentence
    is pinned word for word between ``FieldProvenance.tsx`` and Android's ``DwFieldStampDto``. A
    value lifted out of somebody else's profile at an admin's request is honestly NEITHER, and
    minting a third source is a two-client change and the owner's call.

    Of the two answers that exist, the ACTOR is the only one that is not a fabrication. Stamping the
    DESIGNER would put their name under twenty-one fields they have never read, on a document going
    to a ministry — the same manufactured audit trail ``merge_entry_provenance`` deliberately
    refuses to create for an unstamped legacy field, arrived at from the other end. What the
    designer sees instead is the admin's name in small grey type under their own, which is true and
    reads as an invitation to check the box.
    """
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


# ── 3. Three grantees, and why the seed cannot pick one ──────────────────────────────────────────


async def test_the_seeding_path_never_reads_the_viewer_TABLE_so_it_cannot_guess(monkeypatch):
    """**THE CASE THAT MADE THIS HARD, expressed as the absence of a read.**

    A workshop can carry several viewers, and ``DesignWorkshopViewer`` has no ``role``, no lead flag
    and no usable ordering: a single admin PUT inserts every grantee through one ``create_many``, so
    they share one ``createdAt`` and "the first designer granted" is not decidable. A join-card
    redemption can also add somebody at any moment, from a courtyard, with no admin acting.

    So the rejected design is "re-seed from the first grantee": it would print one of three
    designers' names on a ministry document by a nondeterministic tie-break. This test states the
    property that makes that impossible rather than describing it — ``_StubDb`` has no
    ``designworkshopviewer`` attribute at all, so any read of it raises, and :func:`_seed` fails on
    the swallowed exception. The end-to-end companion — three real grantees, one named designer —
    is in ``test_designer_roster.py``.
    """
    db, _ = await _seed(monkeypatch, designer_id=DESIGNER_ID)

    assert not hasattr(db, "designworkshopviewer"), "the stub's whole point"
    assert _entry(db, STAGE_1)["data"]["designerName"] == "Meera Kanungo"


async def test_the_creator_is_not_given_a_viewer_row_when_they_name_themselves(monkeypatch):
    """An admin who IS the practising designer names themselves, and gets no redundant row.

    ADMIN is inside ``DESIGN_WORKSHOP_ROLES`` precisely so admins can run workshops of their own,
    so this is an ordinary case rather than a corner. The creator's access comes from
    ``createdById``; a viewer row for them would be a second, redundant source of truth for access
    they already hold — and one they could "remove" from the viewers screen without anything
    changing. ``_deduplicate`` drops them on the admin PUT path and ``decide`` has the same branch
    on the request path; this is the third place that rule has to hold.

    Their PROFILE is still what gets copied. Being the creator does not stop somebody being the
    designer, and that distinction is the reason these functions take two ids.
    """
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
    """The rule is IMPORTED and never copied — the same decision ``design_workshop_grants`` took.

    ``_assert_every_id_may_be_granted`` reads the designer empanelment roster AND the platform
    allow-list, exempts the break-glass master through ``deps.is_break_glass_master`` itself, and
    answers with a sentence naming the screen that fixes each refusal. A second copy here is how
    somebody comes to be named as a workshop's designer while their next sign-in refuses them: one
    screen says they are on the workshop, another says they cannot get in, and nothing connects the
    two.

    A narrower ``role == "DESIGNER"`` test would also refuse an ADMIN who is the practising designer
    of a cluster, which the grant rule admits and which is a real case.
    """
    asked: list[set[str]] = []

    async def _assert(user_ids: set[str]) -> None:
        asked.append(set(user_ids))

    monkeypatch.setattr(service, "_assert_every_id_may_be_granted", _assert)
    await service.assert_designer_may_be_named(DESIGNER_ID)
    assert asked == [{DESIGNER_ID}]


# ── 4. The create route: the order of operations, which is a no-orphan rule ──────────────────────


def test_the_create_route_settles_eligibility_BEFORE_it_writes_the_workshop_row():
    """A refused ``designerUserId`` must not leave a committed draft behind.

    Read from the SOURCE rather than by calling it, exactly as
    ``test_design_workshop_gate.test_the_create_route_carries_the_create_gate_and_only_that_one``
    does and for the same reason: calling the route needs a database, and on this project's machines
    Docker is frequently not running.

    THE FAILURE THIS ORDER PREVENTS. ``assert_designer_may_be_named`` raises a 422 for a PROFESSOR,
    for a designer whose empanelment has lapsed, and for an account the platform allow-list has
    suspended. Asked AFTER ``db.designworkshop.create``, that 422 answers the client while the
    workshop row stands — so an admin correcting the picker and pressing create again accumulates
    one orphan draft per attempt, in a list that distinguishes them in no way. The prefill's blanket
    ``except`` exists to stop exactly this class of failure from the other direction, and the create
    itself has no equivalent guard, because a create that cannot honour the body it was given must
    not half-succeed.
    """
    source = inspect.getsource(routes.create_design_workshop)
    validate_at = source.index("await assert_designer_may_be_named(")
    create_at = source.index("await db.designworkshop.create(")
    grant_at = source.index("await attach_the_named_designer(")

    assert validate_at < create_at, (
        "the eligibility check moved below the create, so a refused designerUserId now leaves an "
        "orphan draft behind on every retry"
    )
    assert create_at < grant_at, (
        "the grant needs the workshop's id, so it cannot precede the create — if this fails, the "
        "reading of this route has changed by more than an ordering"
    )


def test_designerUserId_is_optional_so_no_existing_client_is_broken_by_it():
    """A body from today's web form, today's offline draft store and today's Android build.

    None of the three sends this key. If it were required — or if a missing one were anything other
    than "nobody named" — this change would refuse every create in production on the day it shipped.
    """
    body = DesignWorkshopCreate(title="Ikat cluster fortnight")
    assert body.designerUserId is None

    named = DesignWorkshopCreate(title="Ikat cluster fortnight", designerUserId=DESIGNER_ID)
    assert named.designerUserId == DESIGNER_ID
