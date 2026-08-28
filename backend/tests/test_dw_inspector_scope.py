"""**THE FIFTH SCOPE, OVER A REAL DATABASE: what an inspection row buys, and everything it does not.**

The companion to ``test_dw_inspector_scope_gate``, and the division between the two is deliberate
rather than a filing accident. That module replaces ``db`` with a tripwire and asserts the things
that are true of the SOURCE — which doors exist, that every inspector-reachable route is a GET, that
the read-only loader has no ``for_edit`` parameter — so it runs in CI, where there is no Postgres.
This module asserts the things that are only true of a DATABASE: what a row does, what its absence
does, and which of the two scopes can see the other. Four docstrings in the feature point here by
name for exactly those claims.

WHAT IS PINNED HERE, AND WHY EACH IS A WAY THE SCOPE COULD SHIP LOOKING FINISHED WHILE BEING WRONG.

**THE ZERO STATE IS THE FIRST THING ASSERTED, BECAUSE IT IS THE ONE NOBODY WRITES.** An INSPECTOR
with no inspection row must see an empty list and a 404 on every workshop in the repository. Every
other test here can pass against an implementation whose scope clause is missing entirely — a list
that answers "all workshops" satisfies "the assigned inspector finds their workshop" perfectly well.
Only the zero state tells those two apart, which is why it is first and why it is asserted on both
surfaces rather than one.

**THE THREE WRITE DOORS THE GATE MODULE CANNOT REACH.** ``DELETE /{id}``, ``POST /{id}/report`` and
``POST /{id}/exports`` all call ``load_workshop_or_404`` BEFORE they gate — or, for the report, do
not gate at all, because it is open to anyone who may READ the workshop through that loader. So they
cannot be refused from the request alone, and the gate module says so in terms and defers them here.
They are refused by a database fact: an inspector holds no ``DesignWorkshopViewer`` row and is not
the creator, so the loader answers 404. **That is the whole read-only property for those three, and
it is invisible without a database.**

**THE TWO SCOPES CANNOT SEE EACH OTHER, ASSERTED IN BOTH DIRECTIONS OVER REAL ROWS.** An inspection
row must not make a workshop appear on the DESIGNER's surface (``GET /design-workshops``, whose
clause is ``visible_to_clause`` — creator OR viewer), and a viewer row must not admit anybody to the
inspection surface. The gate module proves the two PREDICATES are different expressions; this proves
the two TABLES stay strangers when both hold rows for the same workshop, which is the property that
actually protects the artisan's recordings.

**THE REFUSAL THAT EXISTS NOWHERE ELSE IN THE CODEBASE.** An account already on the workshop — its
creator, or a co-designer holding a viewer row — cannot be assigned as its inspector. An independent
review by somebody who worked on the thing is not a review. Today the two role sets are disjoint so
this is nearly unreachable through the API, and "nearly" is why it is tested: a DESIGNER holding a
viewer row who is later PROMOTED to INSPECTOR walks straight into it, role changes are not
hypothetical, and nothing else in the codebase would notice.

**AND THE ADMINISTRATION IS ADMIN-ONLY, INCLUDING AGAINST THE INSPECTOR THEMSELVES.** The inspected
must not choose the inspector; neither may the inspector choose themselves, or quietly take a
colleague off the panel.

Postgres is required — the behaviour under test is the presence or absence of a row in
``DesignWorkshopInspector`` deciding an HTTP status — so the module skips itself when
``DATABASE_URL`` does not point at a local database, exactly as ``test_design_workshop_viewers``
does. It ALSO skips until the INSPECTOR tier has reached ``deps.ROLE_RANK`` and the ``UserRole``
enum, which is a second lane's work: without the enum value no fixture account can be created at
all, and a module that failed on its first line would read as this feature being broken.

    docker compose up -d postgres minio          # from the REPOSITORY ROOT, not from backend/
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

Current as of 2026-08-27.
"""

import os
import uuid
from typing import Any

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.core.deps import ROLE_RANK
from app.core.security import create_access_token, hash_password

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    # THE SECOND SKIP, AND IT IS NOT BELT-AND-BRACES. The tier's rank and its `UserRole` enum value
    # land in another lane's migration; until both are deployed, `db.user.create(role="INSPECTOR")`
    # fails inside the driver on an enum value Postgres has never heard of. Skipping reads as "not
    # yet", where a hard failure on the fixture would read as "this feature is broken".
    pytest.mark.skipif(
        "INSPECTOR" not in ROLE_RANK,
        reason="the INSPECTOR tier has not reached deps.ROLE_RANK yet; see the other lane",
    ),
    pytest.mark.anyio,
]

PASSWORD = "inspector-test-password"

#: The first stage of the registry. Named rather than discovered so a registry reshuffle fails
#: loudly here instead of quietly testing nothing.
STAGE_1 = "WORKSHOP_SETUP"

#: slug -> (role, display name).
#:
#: ``creator`` IS AN ADMIN because only admins may START a design workshop
#: (``can_create_design_workshops``), and every workshop in this module is made through the real
#: ``POST /design-workshops``. The sibling viewers module records what that costs in full: the
#: creator is now an account ``is_admin`` admits everywhere, so the ``createdById`` clause cannot be
#: isolated by a black-box test any more. Nothing this module exists for runs through the creator.
#:
#: **THREE INSPECTORS, AND THE THIRD IS THE POINT OF THE MODULE.** ``inspector`` is assigned;
#: ``elsewhere`` is assigned to a DIFFERENT workshop; ``idle`` is never assigned to anything and
#: never will be. Two would not be enough: with only an assigned and an unassigned account, an
#: implementation whose scope clause reads "any workshop, for any inspector" is caught, but one
#: whose clause reads "any workshop this inspector was assigned OR any workshop with an inspection
#: on it at all" is not. ``elsewhere`` catches that second one.
ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("admin", "ADMIN", "Inspection Admin"),
    ("creator", "ADMIN", "Workshop Creator"),
    ("inspector", "INSPECTOR", "Assigned Inspector"),
    ("elsewhere", "INSPECTOR", "Inspector On Another Workshop"),
    # THE ZERO-STATE ACCOUNT. Never assigned anything, in any test, ever. If a test in this module
    # ever assigns it, the first two assertions in the file stop meaning anything.
    ("idle", "INSPECTOR", "Unassigned Inspector"),
    ("colleague", "DESIGNER", "Co-designer On The Workshop"),
    ("outsider", "DESIGNER", "Unrelated Designer"),
    # THE ONE ACCOUNT A RANK LADDER WOULD ADMIT TO THE INSPECTION SURFACE. PROFESSOR sits at 40,
    # ABOVE INSPECTOR's 37, so every "this tier and above" spelling of the rule lets them in and the
    # SET does not. A researcher cannot prove that distinction — they are refused by ladder and set
    # alike, so a test with only a researcher in it passes just as well against the wrong rule.
    ("professor", "PROFESSOR", "Senior Professor"),
    # An INSPECTOR the platform allow-list has SUSPENDED: eligible by role, refused by the door.
    # Their address is on its own domain so the picker's exact-set arithmetic is not disturbed.
    ("barred", "INSPECTOR", "Suspended Inspector"),
    # ------------------------------------------------------------------------------------------
    # THE TWO ACCOUNTS THAT REACH THE "ALREADY ON THIS WORKSHOP" REFUSAL, one arm each.
    #
    # Both are INSPECTOR so they PASS the role check, which is the only way to reach the arm at
    # all: `_assert_every_id_may_inspect` refuses a wrong role with a `continue`, so an ordinary
    # ADMIN creator or DESIGNER co-designer is caught by that branch first and the arm under test
    # never runs. A test written with those accounts goes green on the wrong refusal and stays green
    # if the arm is deleted outright.
    #
    # WHY THEY ARE FIXTURE ACCOUNTS RATHER THAN A PROMOTION INSIDE A TEST. Changing a role mid-test
    # means calling `db` from the test's own event loop, and the Prisma client is shared with the
    # running app and bound to the TestClient's loop — `db.disconnect()` there would drop the
    # connection the app is serving every other test on. The sibling viewers module states the rule
    # and never breaks it: the database is touched in the fixture, before the client starts, or not
    # at all.
    ("selfinspector", "INSPECTOR", "Inspector Who Made One"),
    ("workedonit", "INSPECTOR", "Inspector Who Worked On One"),
)

#: The one account that gets an ``AccessRoster`` row, and the status on it.
BARRED_SLUG = "barred"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    """Every account the module needs, created before the app starts.

    Made here rather than inside a test because the Prisma client is shared with the running app and
    bound to the TestClient's event loop; touching it from a test's own loop is the kind of
    cross-loop use that fails intermittently rather than honestly.

    Every address carries a per-run stamp so the module passes on the second run of the suite as
    well as the first.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    # A SECOND per-run token, and it lives only in TITLES. The search test needs a term that matches
    # this run's workshops and cannot match a workshop left behind by an earlier run — otherwise a
    # scope clause that had been dropped entirely could still return "some rows" and look right.
    title_token = f"Pattachitra{uuid.uuid4().hex[:8]}"

    def address(slug: str) -> str:
        domain = "barred.example.org" if slug == BARRED_SLUG else "example.org"
        return f"dwinspector-{slug}-{stamp}@{domain}".lower()

    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role, name in ACCOUNTS:
            people[slug] = await db.user.create(data={
                "email": address(slug),
                "name": name,
                "role": role,
                "passwordHash": hash_password(PASSWORD),
            })
        await db.accessroster.create(data={"email": address(BARRED_SLUG), "status": "SUSPENDED"})
        # THE DESIGNERS NEED AN EMPANELMENT, and this is not incidental bookkeeping.
        # ``design_workshop_viewers`` refuses a DESIGNER who is not on the ACTIVE designer roster
        # with a 422, so without these rows every ``_grant_viewer`` call in this module would fail —
        # and the tests that compare the two scopes would be comparing an inspection against nothing
        # at all, passing for entirely the wrong reason. The INSPECTORS deliberately get no row: an
        # inspector is not empanelled to run anything, and requiring one would refuse every
        # inspector there will ever be. That asymmetry IS the difference between the two rosters.
        for slug, role, _name in ACCOUNTS:
            if role == "DESIGNER":
                await db.designerroster.create(data={
                    "email": address(slug),
                    "fullName": f"Roster row for {slug}",
                    "institution": "Directorate of Handicrafts",
                    "isActive": True,
                    "addedById": people["admin"].id,
                })

        # THE TWO WORKSHOPS THAT REACH THE "ALREADY ON THIS WORKSHOP" REFUSAL, built here with
        # Prisma rather than through the API, because neither state is reachable through it.
        #
        # `POST /design-workshops` is admin-only, so an INSPECTOR can never be a `createdById` by
        # any route — and `PUT /{id}/viewers` refuses a non-DESIGNER, so an INSPECTOR can never be
        # given a viewer row either. Both states are nonetheless one admin action away in the real
        # world: they are what a DESIGNER's or an ADMIN's account BECOMES the day somebody promotes
        # them to the inspection tier. That promotion is the whole reason the refusal exists, and
        # writing the rows directly is the only honest way to put an account into the state a
        # promotion would leave it in.
        self_made = await db.designworkshop.create(data={
            "title": f"Ikat, made by its own inspector {stamp}",
            "createdById": people["selfinspector"].id,
        })
        worked_on = await db.designworkshop.create(data={
            "title": f"Ikat, worked on by its inspector {stamp}",
            "createdById": people["creator"].id,
        })
        await db.designworkshopviewer.create(data={
            "designWorkshopId": worked_on.id,
            "userId": people["workedonit"].id,
            "grantedById": people["admin"].id,
        })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "people": people,
            "address": address,
            "stamp": stamp,
            "title_token": title_token,
            "self_made_workshop": self_made.id,
            "worked_on_workshop": worked_on.id,
        }


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    """A bearer token for one of the fixture's accounts.

    Minted directly rather than obtained by signing in, because what is under test is the inspection
    rules and not the sign-in gate. The barred inspector in particular CANNOT sign in by design, and
    their ineligibility for an inspection row is the thing being asserted.
    """
    return {"Authorization": f"Bearer {create_access_token(world['people'][slug].id)}"}


def _make_workshop(world: dict[str, Any], title: str) -> str:
    """A fresh workshop owned by ``creator``, made through the API the way workshops are made.

    A real ``POST /design-workshops`` rather than a Prisma insert, so the create gate is exercised on
    the way past: if the rule about who may start a workshop moves again, this module fails loudly on
    its first line instead of quietly testing a row nothing could make.

    One per test that touches an inspection set. Sharing a single workshop would make the module
    order-dependent, and the failure mode of that is a suite that passes alone and fails in CI.
    """
    response = world["client"].post(
        "/api/design-workshops", json={"title": title}, headers=_headers(world, "creator")
    )
    assert response.status_code == 201, response.text
    return response.json()["id"]


def _assign(world: dict[str, Any], workshop_id: str, slugs: list[str], *, as_slug: str = "admin"):
    """PUT the whole inspection set. There is no add route and no remove route — see the service."""
    return world["client"].put(
        f"/api/design-workshop-inspections/{workshop_id}/inspectors",
        json={"userIds": [world["people"][s].id for s in slugs]},
        headers=_headers(world, as_slug),
    )


def _grant_viewer(world: dict[str, Any], workshop_id: str, slugs: list[str]):
    """Put a DESIGNER on the workshop the ordinary way, so the two scopes can be compared."""
    return world["client"].put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [world["people"][s].id for s in slugs]},
        headers=_headers(world, "admin"),
    )


# ------------------------------------------------------------------------------------------
# 1. THE ZERO STATE. Asserted first because every other test here passes without a scope clause.
# ------------------------------------------------------------------------------------------


async def test_an_inspector_with_no_scope_row_sees_an_empty_list(world, client):
    """**THE ASSERTION THE WHOLE SCOPE RESTS ON.** No row, nothing to see.

    A workshop exists and is under inspection by somebody else while this runs, so an empty answer
    here cannot be an empty database. That is what makes it an assertion about the clause rather than
    about the fixture: ``inspectable_by_clause`` has ONE source and no ``createdById`` arm, no rank
    fallback and no "all workshops" branch, so an inspector nobody has assigned anything sees exactly
    nothing.

    ``idle`` is used here and NOWHERE ELSE in this module. If a later test assigns it, this stops
    meaning anything and the file has quietly lost its most important assertion.
    """
    workshop_id = _make_workshop(world, "Ikat, inspected by somebody else")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.get("/api/design-workshop-inspections", headers=_headers(world, "idle"))
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["items"] == []
    assert payload["total"] == 0


async def test_an_inspector_with_no_scope_row_cannot_open_a_workshop(world, client):
    """The other half of the zero state, and it must be a 404 rather than a 403.

    A 403 would confirm the id exists to exactly the people this is turning away, which for a
    research data set keyed by cuid is a small but free leak. The detail string is the same "Record
    not found" a designer and a stranger get, so no refusal here can be told from any other.

    THE LIST AND THE DETAIL ARE BOTH ASSERTED because a scope honoured by only one of them is worse
    than no scope: it tells its holder simultaneously that a workshop exists and that it does not.
    """
    workshop_id = _make_workshop(world, "Ikat, closed to the unassigned")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.get(
        f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, "idle")
    )
    assert response.status_code == 404, response.text
    assert response.json()["detail"] == "Record not found"


async def test_an_inspector_assigned_elsewhere_is_refused_this_workshop(world, client):
    """The scope is per WORKSHOP, not per TIER — the distinction ``idle`` alone cannot prove.

    ``elsewhere`` holds a real inspection row, so an implementation that admits "any inspector who is
    inspecting anything" passes both tests above and fails this one. That is the only reason this
    account exists.
    """
    theirs = _make_workshop(world, "Ikat, theirs")
    mine = _make_workshop(world, "Ikat, not theirs")
    assert _assign(world, theirs, ["elsewhere"]).status_code == 200
    assert _assign(world, mine, ["inspector"]).status_code == 200

    response = client.get(
        f"/api/design-workshop-inspections/{mine}", headers=_headers(world, "elsewhere")
    )
    assert response.status_code == 404, response.text

    listed = client.get("/api/design-workshop-inspections", headers=_headers(world, "elsewhere"))
    ids = [item["id"] for item in listed.json()["items"]]
    assert theirs in ids
    assert mine not in ids


# ------------------------------------------------------------------------------------------
# 2. WHAT THE ROW DOES BUY — a read, and a read that the list honours
# ------------------------------------------------------------------------------------------


async def test_an_assigned_inspector_opens_the_workshop_and_is_told_it_is_read_only(world, client):
    """The positive case, and the ``readOnly`` flag that stops a client offering a Save button.

    Said on the wire rather than inferred from the URL, because both clients will eventually render
    this payload through the same screen as the designer's read, and a screen that cannot tell the
    two apart offers a Save the API answers 404 to. One boolean is cheaper than the bug report.
    """
    workshop_id = _make_workshop(world, "Ikat, under inspection")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.get(
        f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, "inspector")
    )
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["id"] == workshop_id
    assert payload["readOnly"] is True
    # The inspection is of the WORK, so the stages and their completeness must actually be there —
    # a scope that admits somebody to an empty payload has not let them inspect anything.
    assert "stages" in payload
    assert "completeness" in payload


async def test_the_inspection_list_honours_the_scope(world, client):
    """The list is half the feature; a scope it does not honour is a scope its holder cannot use.

    Nothing in either client navigates to a design workshop by typed id, so a workshop absent from
    every list an inspector can reach is a workshop they cannot open at all.
    """
    workshop_id = _make_workshop(world, "Ikat, listed for its inspector")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.get("/api/design-workshop-inspections", headers=_headers(world, "inspector"))
    assert response.status_code == 200, response.text
    assert workshop_id in [item["id"] for item in response.json()["items"]]


async def test_the_scope_survives_a_search(world, client):
    """**BOTH CLAUSES WANT ``where["OR"]`` AND THE LATER ASSIGNMENT SILENTLY WINS.**

    This is the bug this repository has shipped more than once: the scope written to that key after
    the search box has taken it, or the reverse. One direction makes the search stop narrowing; the
    other makes the scope vanish the moment somebody types — a workshop nobody assigned appearing
    the instant an inspector uses the search box.

    Both are asserted in one test, because the fix is one line and a test covering only one direction
    passes against half of it: the assigned workshop must still be FOUND, and the unassigned one
    sharing the same search term must still be ABSENT.
    """
    marker = world["title_token"]
    mine = _make_workshop(world, f"{marker} mine to inspect")
    theirs = _make_workshop(world, f"{marker} not mine")
    assert _assign(world, mine, ["inspector"]).status_code == 200
    assert _assign(world, theirs, ["elsewhere"]).status_code == 200

    response = client.get(
        "/api/design-workshop-inspections",
        params={"search": marker},
        headers=_headers(world, "inspector"),
    )
    assert response.status_code == 200, response.text
    ids = [item["id"] for item in response.json()["items"]]
    assert mine in ids, "the search dropped the scope's own workshop"
    assert theirs not in ids, "the search widened the scope past what was assigned"


async def test_a_soft_deleted_workshop_is_gone_from_the_inspection_surface(world, client):
    """Deleted is a 404 here with no 409 arm, and it is absent from the list.

    The designer's loader answers 409 to an EDITOR holding unsent stages, so an admin can be asked to
    restore it. An inspector has nothing pending and no restore button, so the honest answer is that
    there is nothing to inspect. The row survives the soft delete — it is the workshop that is gone,
    not the inspection — so a restore brings the inspection back with it.
    """
    workshop_id = _make_workshop(world, "Ikat, soft deleted")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200
    assert (
        client.delete(
            f"/api/design-workshops/{workshop_id}", headers=_headers(world, "admin")
        ).status_code
        == 204
    )

    detail = client.get(
        f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, "inspector")
    )
    assert detail.status_code == 404, detail.text

    listed = client.get("/api/design-workshop-inspections", headers=_headers(world, "inspector"))
    assert workshop_id not in [item["id"] for item in listed.json()["items"]]

    # THE ROW IS STILL THERE, which is what makes a restore whole. Asked of the admin's roster
    # route rather than of Prisma, so the assertion is about the product and not about a table.
    roster = client.get(
        f"/api/design-workshop-inspections/{workshop_id}/inspectors",
        headers=_headers(world, "admin"),
    )
    assert roster.status_code == 200, roster.text
    assert [row["userId"] for row in roster.json()["inspectors"]] == [
        world["people"]["inspector"].id
    ]


# ------------------------------------------------------------------------------------------
# 3. READ-ONLY: the three doors the gate module cannot reach, because a DATABASE decides them
# ------------------------------------------------------------------------------------------


async def test_an_inspector_cannot_delete_the_workshop_they_inspect(world, client):
    """``DELETE /{id}`` loads BEFORE it gates, so only a database can refuse it.

    404 and not 403: the loader is what turns them away, and it turns them away by answering that
    the workshop is not there. An inspector destroying the record they were asked to examine is the
    single worst thing this scope could be made to authorise.
    """
    workshop_id = _make_workshop(world, "Ikat, undeletable by its inspector")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.delete(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "inspector")
    )
    assert response.status_code == 404, response.text

    # AND IT IS STILL THERE. A 404 that had actually deleted the row would pass the line above.
    assert (
        client.get(
            f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, "inspector")
        ).status_code
        == 200
    )


async def test_an_inspector_cannot_generate_the_report(world, client):
    """``POST /{id}/report`` has NO role gate at all — it is open to whoever may READ through the
    designer's loader — so this refusal is made entirely of the absence of a viewer row.

    That makes it the most fragile of the three and the one most worth pinning: widen
    ``load_workshop_or_404`` by one clause and an inspector is signing ministry documents. The report
    is the designer's signed output; an inspector examines it, they do not issue it.
    """
    workshop_id = _make_workshop(world, "Ikat, unreportable by its inspector")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.post(
        f"/api/design-workshops/{workshop_id}/report",
        json={"formats": ["DOCX"]},
        headers=_headers(world, "inspector"),
    )
    assert response.status_code == 404, response.text


async def test_an_inspector_cannot_write_the_export_ledger(world, client):
    """``POST /{id}/exports`` stands behind ``load_workshop_or_404(for_edit=True)`` ALONE.

    No ``_require_designer`` in front of it, which is precisely why the read-only property here has
    to come from the loader refusing an account with no viewer row. An inspection that could write
    the export ledger would be recording that the inspector had issued a report.
    """
    workshop_id = _make_workshop(world, "Ikat, no ledger row from an inspector")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.post(
        f"/api/design-workshops/{workshop_id}/exports",
        json={
            "format": "DOCX",
            "templateId": "default",
            "fileName": "inspector.docx",
            "generatedAt": "2026-08-27T00:00:00Z",
        },
        headers=_headers(world, "inspector"),
    )
    assert response.status_code == 404, response.text


async def test_an_inspector_cannot_save_a_stage(world, client):
    """The 22-stage fortnight, refused over a real database as well as at the gate.

    The gate module already proves this is decided from the ROLE before any lookup. It is repeated
    here against a workshop the inspector genuinely holds a row for, because "refused for an account
    with no scope" and "refused for the account whose scope this is" are different claims, and only
    the second one is about this feature.
    """
    workshop_id = _make_workshop(world, "Ikat, unwritable by its inspector")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.put(
        f"/api/design-workshops/{workshop_id}/stages/{STAGE_1}",
        json={"entries": []},
        headers=_headers(world, "inspector"),
    )
    assert response.status_code == 403, response.text


async def test_an_inspector_cannot_regrant_access_to_anybody(world, client):
    """Not viewers, and not other inspectors. **NEITHER MAY THEY CHOOSE THEMSELVES.**

    An inspector who could put a colleague on the panel — or take one off it — is choosing who
    examines the work, which is the authority this tier is defined by NOT having.
    """
    workshop_id = _make_workshop(world, "Ikat, no onward grants")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    viewers = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [world["people"]["outsider"].id]},
        headers=_headers(world, "inspector"),
    )
    assert viewers.status_code == 403, viewers.text

    onward = _assign(world, workshop_id, ["inspector", "elsewhere"], as_slug="inspector")
    assert onward.status_code == 403, onward.text


async def test_the_inspection_payload_carries_no_transcripts(world, client):
    """**THE ARTISAN'S RECORDED VOICE IS NOT IN THIS GRANT'S GIFT**, and the key is absent entirely.

    The designer's read fills ``transcripts`` from ``owned_or_granted_where``, which admits an
    account below PROFESSOR only for media it uploaded, media whose owner granted it a
    ``DataAccessGrant``, or media tagged to a workshop it holds through ``DesignWorkshopViewer`` /
    ``createdById``. An inspector holds none of those.

    ABSENT RATHER THAN EMPTY, and the difference is the assertion. An empty list would mean this
    route is on the media path and today happens to return nothing — so the next person widening
    that predicate widens this surface without noticing. A missing key means the route never asks.

    Whether an inspector SHOULD see the workshop's photographs is an owner's decision that has not
    been made. This test is what keeps it from being made by accident.
    """
    workshop_id = _make_workshop(world, "Ikat, no recordings for the inspector")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.get(
        f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, "inspector")
    )
    assert response.status_code == 200, response.text
    assert "transcripts" not in response.json()

    # THE CONTROL: the designer's read of the same workshop DOES carry the key, so the assertion
    # above is about this route and not about a key that no longer exists anywhere.
    assert _grant_viewer(world, workshop_id, ["colleague"]).status_code == 200
    designer_read = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "colleague")
    )
    assert designer_read.status_code == 200, designer_read.text
    assert "transcripts" in designer_read.json()


# ------------------------------------------------------------------------------------------
# 4. THE TWO SCOPES ARE STRANGERS, over real rows in both tables
# ------------------------------------------------------------------------------------------


async def test_an_inspection_row_is_invisible_on_the_designer_surface(world, client):
    """An inspection must not admit anybody to ``/design-workshops``, list or detail.

    That surface's clause is ``visible_to_clause`` — created-by-me OR a viewer row — and an inspector
    holds neither. If this ever goes green the wrong way, the inspection has become a viewer grant,
    and a viewer grant carries STAGE WRITES because ``load_workshop_or_404(for_edit=True)`` performs
    no role check at all.
    """
    workshop_id = _make_workshop(world, "Ikat, inspection only")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    detail = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "inspector")
    )
    assert detail.status_code == 404, detail.text

    listed = client.get("/api/design-workshops", headers=_headers(world, "inspector"))
    assert listed.status_code == 200, listed.text
    assert workshop_id not in [item["id"] for item in listed.json()["items"]]


async def test_a_viewer_row_is_invisible_on_the_inspection_surface(world, client):
    """The other direction. A co-designer holding a real viewer grant is refused the whole prefix.

    403 and not 404, and deliberately so: this is the surface refusing a TIER rather than a scope
    hiding a row, and the sentence names the door they actually want. A designer told only
    "forbidden" on a read surface reasonably concludes the deployment is broken.
    """
    workshop_id = _make_workshop(world, "Ikat, viewer is not an inspector")
    assert _grant_viewer(world, workshop_id, ["colleague"]).status_code == 200
    # The colleague can genuinely read it the ordinary way — so the refusal below is about the
    # surface and not about them having no access to this workshop at all.
    assert (
        client.get(
            f"/api/design-workshops/{workshop_id}", headers=_headers(world, "colleague")
        ).status_code
        == 200
    )

    refused = client.get(
        f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, "colleague")
    )
    assert refused.status_code == 403, refused.text
    assert "Inspector / Reviewer" in refused.json()["detail"]


@pytest.mark.parametrize("slug", ["admin", "colleague", "professor"])
async def test_the_inspection_surface_refuses_everybody_else_including_admins(world, client, slug):
    """**INCLUDING ADMINS, AND THAT IS THE INTERESTING ONE.**

    Admitting an admin here would mean one of two things and both are worse than a refusal. Scoped by
    THEIR OWN inspection rows they see an empty list and read it as a broken feature; scoped by
    "everything, because they are an admin" this becomes a second full read of every workshop in the
    repository — a second place to look when somebody has access they should not.

    PROFESSOR is in the parametrisation because rank 40 is ABOVE INSPECTOR's 37: every "this tier and
    above" spelling of the rule admits them, and the SET does not.
    """
    response = client.get("/api/design-workshop-inspections", headers=_headers(world, slug))
    assert response.status_code == 403, response.text
    assert "Inspector / Reviewer" in response.json()["detail"]


# ------------------------------------------------------------------------------------------
# 5. WHO MAY ASSIGN ONE, AND THE HONEST REFUSAL WHEN THEY MAY NOT
# ------------------------------------------------------------------------------------------


@pytest.mark.parametrize("slug", ["colleague", "inspector", "professor"])
async def test_only_an_admin_assigns_an_inspection(world, client, slug):
    """THE INSPECTED MUST NOT CHOOSE THE INSPECTOR, and neither may the inspector.

    ``colleague`` is a co-designer on the workshop — the account with the strongest claim to a say
    and the one that must most certainly not have one. There is deliberately no "suggest an
    inspector" route either: a suggestion an admin rubber-stamps is the same thing wearing a queue.
    """
    workshop_id = _make_workshop(world, f"Ikat, admin only {slug}")
    assert _grant_viewer(world, workshop_id, ["colleague"]).status_code == 200

    response = _assign(world, workshop_id, ["elsewhere"], as_slug=slug)
    assert response.status_code == 403, response.text

    # AND NOTHING WAS WRITTEN. A 403 raised after the write would pass the line above.
    roster = client.get(
        f"/api/design-workshop-inspections/{workshop_id}/inspectors",
        headers=_headers(world, "admin"),
    )
    assert roster.json()["inspectors"] == []


async def test_the_workshops_own_creator_cannot_be_its_inspector(world, client):
    """**THE REFUSAL THAT EXISTS NOWHERE ELSE IN THE CODEBASE**, through its ``createdById`` arm.

    The sibling viewers module DROPS the creator from the set silently, as a harmless no-op — they
    already hold the access being granted. That silence is right there and wrong here: naming the
    creator asks for somebody to inspect their own work, which is a MISTAKE an admin needs to be
    told about rather than a no-op.

    **THE PROMOTION IS WHAT MAKES THIS TEST MEAN ANYTHING, and it is worth stating why.** ``creator``
    is an ADMIN, because only an admin may start a workshop. Assigning an ADMIN is refused by the
    ROLE branch, which ``continue``s — so a test that simply named the creator here would go green on
    the wrong refusal and would keep going green if the creator arm were deleted outright. Promoting
    them to INSPECTOR first gets past the role branch and reaches the arm actually under test.

    **THE ACCOUNT HAS TO PASS THE ROLE CHECK FOR THIS TEST TO MEAN ANYTHING**, which is why it uses
    the fixture's ``selfinspector`` and that account's own workshop rather than ``creator``.
    ``creator`` is an ADMIN — only an admin may start a workshop — and an ADMIN is refused by the
    ROLE branch, which ``continue``s. A test that simply named the creator would go green on the
    wrong refusal and would keep going green if the creator arm were deleted outright.
    """
    response = _assign(world, world["self_made_workshop"], ["selfinspector"])
    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert "Inspector Who Made One" in detail
    assert "already on this workshop" in detail
    assert "Nothing was changed." in detail


async def test_a_co_designer_on_the_workshop_cannot_inspect_it(world, client):
    """The same refusal through the other arm: a ``DesignWorkshopViewer`` row for this workshop.

    **THIS IS THE ONE THAT IS NOT HYPOTHETICAL.** Today ``INSPECTION_ROLES`` and
    ``DESIGN_WORKSHOP_ROLES`` are disjoint, so the role check catches an ordinary designer first —
    and that is exactly why the viewer arm has to be asserted with an account that PASSES the role
    check. A DESIGNER holding a viewer row who is later PROMOTED to INSPECTOR is one admin action
    away, and nothing else in the codebase would notice.

    So the fixture builds the account in the state a promotion would leave it in: ``workedonit`` is
    an INSPECTOR holding a real ``DesignWorkshopViewer`` row on ``worked_on_workshop``. It passes the
    role check and is caught by the viewer arm, which is the only ordering that tests anything.
    """
    response = _assign(world, world["worked_on_workshop"], ["workedonit"])
    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert "Inspector Who Worked On One" in detail
    assert "already on this workshop" in detail
    assert "is not a review" in detail


async def test_a_designer_cannot_be_assigned_an_inspection(world, client):
    """Wrong ROLE, refused by name — and the sentence says what the account IS.

    ``outsider`` is not on this workshop, so the only thing wrong with them is the tier. That keeps
    this test about the role rule rather than about the already-on-the-workshop rule beside it.
    """
    workshop_id = _make_workshop(world, "Ikat, wrong tier")

    response = _assign(world, workshop_id, ["outsider"])
    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert "DESIGNER" in detail
    assert "Nothing was changed." in detail


async def test_a_barred_inspector_is_refused(world, client):
    """An account the platform allow-list has SUSPENDED cannot sign in at all.

    An inspection row for them would leave the admin screen saying somebody is inspecting while they
    are shown a refusal at the door — a screen stating something false, which is the defect this
    rule was added to the sibling module to end.
    """
    workshop_id = _make_workshop(world, "Ikat, barred inspector")

    response = _assign(world, workshop_id, ["barred"])
    assert response.status_code == 422, response.text
    assert "barred by the platform access list" in response.json()["detail"]


async def test_an_unknown_id_refuses_the_whole_call(world, client):
    """One bad id refuses everything, and the good half is NOT applied.

    An admin who named two inspectors and is shown one has been told nothing about which failed or
    why, and a partially applied access change is the worst of both — it looks like it worked.
    """
    workshop_id = _make_workshop(world, "Ikat, one bad id")

    response = client.put(
        f"/api/design-workshop-inspections/{workshop_id}/inspectors",
        json={"userIds": [world["people"]["inspector"].id, "cmnosuchaccount0000000000"]},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 422, response.text
    assert "No account exists with" in response.json()["detail"]

    roster = client.get(
        f"/api/design-workshop-inspections/{workshop_id}/inspectors",
        headers=_headers(world, "admin"),
    )
    assert roster.json()["inspectors"] == [], "the good half of a refused call was applied anyway"


# ------------------------------------------------------------------------------------------
# 6. THE ROSTER'S OWN MECHANICS
# ------------------------------------------------------------------------------------------


async def test_re_saving_an_unchanged_set_does_not_restamp_the_assignment(world, client):
    """Idempotent by construction: only the difference is written.

    ``assignedAt`` is the only answer anybody has to "how long has this workshop been under
    inspection", so a re-save that restamped it would erase the one fact the row carries. A
    read-then-delete-then-recreate implementation passes every other test in this file and fails
    this one.
    """
    workshop_id = _make_workshop(world, "Ikat, saved twice")

    first = _assign(world, workshop_id, ["inspector"])
    assert first.status_code == 200, first.text
    stamped = first.json()["inspectors"][0]["assignedAt"]
    assert stamped

    second = _assign(world, workshop_id, ["inspector"])
    assert second.status_code == 200, second.text
    assert second.json()["inspectors"][0]["assignedAt"] == stamped


async def test_removing_an_inspector_ends_their_access_immediately(world, client):
    """The row IS the grant, so DELETING it is the revocation. There is no status column.

    Sending the set without somebody is how they come off; there is no remove route. The access must
    end with the row rather than at some later expiry, which is what "the row is the grant" means
    when it is true rather than merely written down.
    """
    workshop_id = _make_workshop(world, "Ikat, inspection ended")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200
    assert (
        client.get(
            f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, "inspector")
        ).status_code
        == 200
    )

    assert _assign(world, workshop_id, []).status_code == 200

    after = client.get(
        f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, "inspector")
    )
    assert after.status_code == 404, after.text
    listed = client.get("/api/design-workshop-inspections", headers=_headers(world, "inspector"))
    assert workshop_id not in [item["id"] for item in listed.json()["items"]]


async def test_the_roster_names_the_person_it_assigns(world, client):
    """Name, email and role travel WITH the row rather than being joined against a directory.

    An inspector whose account has since been suspended is precisely the row an admin most needs to
    see and act on, and a join against the eligible list would render it as a bare cuid.
    """
    workshop_id = _make_workshop(world, "Ikat, named inspector")
    assert _assign(world, workshop_id, ["inspector"]).status_code == 200

    response = client.get(
        f"/api/design-workshop-inspections/{workshop_id}/inspectors",
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    row = response.json()["inspectors"][0]
    assert row["userId"] == world["people"]["inspector"].id
    assert row["name"] == "Assigned Inspector"
    assert row["email"] == world["address"]("inspector")
    assert row["role"] == "INSPECTOR"


async def test_the_picker_offers_inspectors_and_excludes_the_barred(world, client):
    """Eligibility is a SET of one role, narrowed by the platform allow-list read as a CUT LIST.

    Searched by this run's own stamp so the assertion cannot depend on how many rows the shared table
    happens to hold — the exact failure mode the sibling module's picker test shipped with.

    ``truncated`` must be present and False here: a client that shows a cut list without saying so is
    this repository's most repeated bug class, and the key going missing is how that starts.
    """
    response = client.get(
        "/api/design-workshop-inspections/eligible-inspectors",
        params={"search": world["stamp"]},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["truncated"] is False

    offered = {u["id"] for u in payload["users"]}
    people = world["people"]
    assert people["inspector"].id in offered
    assert people["idle"].id in offered
    assert people["elsewhere"].id in offered
    # The tier is a SET: a designer below it and a professor above it are both absent.
    assert people["outsider"].id not in offered
    assert people["professor"].id not in offered
    assert people["admin"].id not in offered
    # Barred by the allow-list: eligible by role, unable to sign in, so never offered.
    assert people["barred"].id not in offered


async def test_the_literal_picker_path_is_not_swallowed_by_the_workshop_id_route(world, client):
    """``/eligible-inspectors`` must not be matched as a workshop id.

    FastAPI matches in declaration order and ``GET /{workshop_id}/inspectors`` would fit this path
    perfectly well, answering 404 "Record not found". That is the trap that once left the admin's
    designer picker empty on a server where the route existed and worked.
    """
    response = client.get(
        "/api/design-workshop-inspections/eligible-inspectors", headers=_headers(world, "admin")
    )
    assert response.status_code == 200, response.text
    assert "users" in response.json()
