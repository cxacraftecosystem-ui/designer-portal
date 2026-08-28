"""A design workshop is visible to the designers it was created FOR, to admins, and to nobody else.

═════════════════════════════════════════════════════════════════════════════════════════════════
THE ASK, AND WHY IT IS A SECURITY BOUNDARY RATHER THAN A UI PREFERENCE
═════════════════════════════════════════════════════════════════════════════════════════════════

Verbatim: "Designer this workshop is for should be a multi-select dropdown with searchable
functionality … the design workshop would only be visible to those particular designers, admins and
master admins would be able to see all the design workshops."

The second half of that sentence is a rule about who may read a fortnight of a named artisan
cluster's fieldwork — photographs of people, dictated interviews, identity documents, addresses. A
list that filters CLIENT-SIDE is not scoped: the row is one typed URL away, and both clients
navigate by id. So the assertions here go through the HTTP surface twice for every account, once on
the LIST and once on the SINGLE READ, and a pass on one of the two proves nothing.

═════════════════════════════════════════════════════════════════════════════════════════════════
WHAT THIS MODULE IS ACTUALLY GUARDING, GIVEN THAT THE RULE WAS ALREADY THERE
═════════════════════════════════════════════════════════════════════════════════════════════════

**NO NEW PREDICATE WAS WRITTEN FOR THIS FEATURE, AND THAT WAS THE DESIGN.** The scope was already
enforced in the query — ``visible_to_clause`` on the list (``api/routes/design_workshops.py``) and
``load_workshop_or_404``'s three-way clause on the read — both reading the ONE table,
``DesignWorkshopViewer``. A DESIGNER cannot create a workshop at all
(``assert_can_create_design_workshops`` is ``is_admin``), so ``createdById`` never matches for them:
a designer already saw exactly the workshops they held a row on. What was missing was the WRITE —
the admin had to create the workshop and then remember the viewers panel, and forgetting left a
designer facing a 404 they could not tell apart from a workshop that does not exist.

So ``designerUserIds`` on the create body writes N rows where the singular ``designerUserId`` wrote
one, and every scoped surface in the product widens with it because they all read that one table.
The alternative that was designed and REJECTED — a ``DesignWorkshopDesigner`` join table beside the
viewer one — would have been a SECOND SOURCE OF ACCESS, which ``DesignWorkshopViewer``'s own schema
comment forbids by name. Viewer membership is consulted from at least six places and one of them
(``questionnaire_forms._visible_questionnaire_where``) spells the clause BY HAND; a second table
needs a second arm in every one of them, and the hand-written arm is the one somebody misses.

**THIS MODULE IS THEREFORE MOSTLY A SET OF CONTROLS**, and that is deliberate rather than lazy.
Several of these tests would have passed before the multi-select existed. They are here because the
change they guard against is not "somebody deletes the scope" — it is "somebody adds a second way
in, or lets the create write a row for an account the eligibility rule would refuse, or lets the
refusal start distinguishing 'not on it' from 'no such thing'".

═════════════════════════════════════════════════════════════════════════════════════════════════
THE FOUR PROPERTIES, AND THE WAY EACH ONE FAILS
═════════════════════════════════════════════════════════════════════════════════════════════════

1. **A TICKED DESIGNER IS ON THE WORKSHOP, ALL OF THEM, IN ONE CALL.** The failure is silent: a
   create that writes a row for the first name and not the rest looks perfect to the admin who
   pressed it, and shows up a fortnight later as a designer who cannot open the workshop whose
   stage 1 carries their name.

2. **AN UNTICKED DESIGNER IS REFUSED ON BOTH DOORS, AND TOLD NOTHING BY THE REFUSAL.** 404 with
   ``detail="Record not found"``, byte-identical to a cuid that does not exist. A 403 here is a free
   existence oracle over a research data set whose ids travel on PRINTED JOIN CARDS.

3. **ADMIN AND MASTER_ADMIN SEE EVERYTHING.** The narrowing must not strand the people who
   administer it — including on a workshop they did not create and are not named on.

4. **THE OLD SINGLE-DESIGNER WIRE STILL WORKS.** A handset a fortnight behind sends
   ``designerUserId`` alone and reads ``designerName`` back. Neither may 422 and neither may vanish:
   on Android ``saveOrQueue`` will NOT re-queue a 4xx, so a create the server refuses is a create
   whose record is LOST.

Postgres is required — the behaviour under test is a row in ``DesignWorkshopViewer`` deciding an
HTTP status — so the module skips itself when ``DATABASE_URL`` does not point at a local database,
exactly as ``test_design_workshop_viewers`` and ``test_designer_roster`` do.

    docker compose up -d postgres minio          # from the REPOSITORY ROOT, not from backend/
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

import os
import uuid
from typing import Any

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.core.security import create_access_token, hash_password

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

PASSWORD = "designer-scope-password"

#: Stage 1, named rather than discovered so a registry reshuffle fails loudly instead of quietly
#: testing nothing. It is the stage the adoption re-sync writes first, which is what section 5 uses.
STAGE_1 = "WORKSHOP_SETUP"

#: A cuid-shaped id that has never existed. THE CONTROL FOR THE WHOLE REFUSAL ARGUMENT: the answer a
#: designer gets for a workshop they are not on must be indistinguishable from this, in status AND
#: in detail string. Shaped like a real id on purpose — a malformed one could be refused by a
#: different branch (or by the router) and would prove nothing about the scope clause.
NO_SUCH_WORKSHOP = "cmnosuchworkshopatall000000"

#: slug -> (role, display name).
#:
#: ``lead`` AND ``co`` ARE THE MULTI-SELECT, and there have to be two of them: every question this
#: module asks — did all the ticks land, is the right one on the cover — has the same answer for a
#: one-element list as for the singular field it replaced.
#:
#: ``stranger`` IS THE POINT OF THE FEATURE. An actively empanelled DESIGNER, eligible by every rule
#: in the product, who was simply not ticked. If this account can reach the workshop then "visible
#: only to those particular designers" is not true, and no amount of UI makes it true.
ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("admin", "ADMIN", "Scope Admin"),
    # A SECOND ADMIN WHO CREATES NOTHING. ``is_admin`` and ``createdById`` are two different ways
    # into `load_workshop_or_404`, and an admin who also happens to be the creator cannot tell them
    # apart: property 3 is about the admin who is a stranger to the workshop in every way except
    # their role.
    ("otherAdmin", "ADMIN", "Uninvolved Admin"),
    ("master", "MASTER_ADMIN", "Scope Master Admin"),
    ("lead", "DESIGNER", "Lead Designer"),
    ("co", "DESIGNER", "Co Designer"),
    ("stranger", "DESIGNER", "Unticked Designer"),
    # THE ACCOUNT THE ELIGIBILITY RULE MUST REFUSE FROM INSIDE A LIST. A designer whose empanelment
    # was revoked cannot sign in at all, so a viewer row for them is a grant their next sign-in
    # ignores — one screen saying they are on the workshop, another showing them a refusal.
    ("revoked", "DESIGNER", "Revoked Designer"),
    # AN ADMIN THE PLATFORM ALLOW-LIST HAS SUSPENDED, and the ONE account here that proves a rule
    # about the CREATOR rather than about a designer. Two facts make it reachable rather than
    # theoretical: the ordinary door does not re-read the allow-list (only the dataset door does),
    # so a token minted before the suspension still works; and ``is_break_glass_master`` exempts the
    # MASTER_ADMIN, so this one is deliberately an ordinary ADMIN and is genuinely barred.
    ("suspendedAdmin", "ADMIN", "Suspended Admin"),
)

#: slug -> ``AccessRoster.status``. The SECOND table that can stop somebody signing in, and the one
#: that gates every role rather than designers only — a suspended ADMIN is caught by this and by
#: nothing else.
ACCESS_STATES: tuple[tuple[str, str], ...] = (("suspendedAdmin", "SUSPENDED"),)

#: slug -> ``DesignerRoster.isActive``. Admins are deliberately absent: that roster gates designers
#: only, which is why the platform allow-list exists beside it.
ROSTER: tuple[tuple[str, bool], ...] = (
    ("lead", True),
    ("co", True),
    ("stranger", True),
    ("revoked", False),
)

#: slug -> the ``DesignerProfile.displayName`` and ``institution`` the seed should copy.
#:
#: DISJOINT VALUES, because the one thing the multi-select must never do is start GUESSING which
#: grantee to print on a ministry document. A co-designer sharing an institution with the lead could
#: not tell a correct answer from a coincidence.
PROFILES: tuple[tuple[str, str, str], ...] = (
    ("lead", "Meera Kanungo", "NIFT Bhubaneswar"),
    ("co", "Rukmini Behera", "Sambalpur Handloom Cooperative"),
)


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    """Every account, roster row and profile the module needs, created before the app starts.

    Made here rather than inside a test because the Prisma client is shared with the running app and
    bound to the TestClient's event loop; touching it from a test's own loop is the kind of
    cross-loop use that fails intermittently rather than honestly. Same shape, and the same reason,
    as ``test_design_workshop_viewers``.

    Every address carries a per-run stamp, because ``DesignerRoster.email`` is UNIQUE and fixed
    addresses would pass on a clean database and fail on the second run of the suite.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"dwscope-{slug}-{stamp}@example.org".lower()

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
        for slug, is_active in ROSTER:
            await db.designerroster.create(data={
                "email": address(slug),
                "fullName": f"Roster row for {slug}",
                "institution": "Directorate of Handicrafts",
                "isActive": is_active,
                "addedById": people["admin"].id,
            })
        for slug, state in ACCESS_STATES:
            await db.accessroster.create(data={"email": address(slug), "status": state})
        for slug, display_name, institution in PROFILES:
            await db.designerprofile.create(data={
                "userId": people[slug].id,
                "displayName": display_name,
                "institution": institution,
            })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {"client": client, "people": people, "stamp": stamp, "address": address}


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    """A bearer token for one of the fixture's accounts.

    Minted directly rather than obtained by signing in, because the roster gate lives on the LOGIN
    path only. What is under test here is the visibility rules, and a helper that signed in first
    would make every test depend on the sign-in gate as well — in particular ``revoked``, who by
    design cannot log in and whose INELIGIBILITY is the thing being asserted.
    """
    user = world["people"][slug]
    return {"Authorization": f"Bearer {create_access_token(user.id)}"}


def _ids(world: dict[str, Any], slugs: list[str]) -> list[str]:
    return [world["people"][s].id for s in slugs]


def _create_as(world: dict[str, Any], slug: str, title: str, **body: Any):
    """``POST /design-workshops``, the way a workshop is actually made.

    A real request rather than a Prisma insert, so the create gate and the eligibility rule are both
    exercised on the way past: if the rule about who may start a workshop moves again, this module
    fails loudly on its first line instead of quietly testing rows nothing could make.
    """
    return world["client"].post(
        "/api/design-workshops",
        json={"title": title, **body},
        headers=_headers(world, slug),
    )


def _create(world: dict[str, Any], title: str, **body: Any):
    """The common case: ``admin`` opens the workshop."""
    return _create_as(world, "admin", title, **body)


def _make(world: dict[str, Any], title: str, **body: Any) -> str:
    response = _create(world, title, **body)
    assert response.status_code == 201, response.text
    return response.json()["id"]


def _list_ids(client: Any, world: dict[str, Any], slug: str, **params: Any) -> list[str]:
    """Every workshop id ``slug`` can enumerate, optionally filtered.

    ``params`` is handed to httpx rather than concatenated into the path, so a title carrying spaces
    can be searched for verbatim — the orphan-draft assertion below depends on that being the exact
    string the create was refused for.
    """
    response = client.get(
        "/api/design-workshops",
        params={"pageSize": 100, **params},
        headers=_headers(world, slug),
    )
    assert response.status_code == 200, response.text
    return [row["id"] for row in response.json()["items"]]


def _viewer_ids(client: Any, world: dict[str, Any], workshop_id: str) -> set[str]:
    response = client.get(
        f"/api/design-workshops/{workshop_id}/viewers", headers=_headers(world, "admin")
    )
    assert response.status_code == 200, response.text
    return {row["userId"] for row in response.json()["viewers"]}


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 1. The multi-select writes every row, in one call
# ══════════════════════════════════════════════════════════════════════════════════════════════


def test_ticking_two_designers_puts_BOTH_of_them_on_the_workshop(world, client):
    """The whole of the new write path, asserted at the table and at both doors.

    THE FAILURE THIS CATCHES IS SILENT. A create that honours the first name and drops the rest
    looks perfect to the admin who pressed it — a 201, a workshop in the list — and surfaces a
    fortnight later as a designer who cannot open the workshop whose stage 1 already carries their
    colleague's name. Nothing in either client compares the ticks to the grants.
    """
    marker = f"Bomkai{uuid.uuid4().hex[:8]}"
    workshop_id = _make(
        world,
        f"{marker} two designers",
        designerUserIds=_ids(world, ["lead", "co"]),
    )

    assert _viewer_ids(client, world, workshop_id) == set(_ids(world, ["lead", "co"]))
    for slug in ("lead", "co"):
        read = client.get(
            f"/api/design-workshops/{workshop_id}", headers=_headers(world, slug)
        )
        assert read.status_code == 200, f"{slug} could not open the workshop: {read.text}"
        assert workshop_id in _list_ids(client, world, slug, search=marker), (
            f"{slug} holds a row and can open the workshop by id, but it is missing from their "
            "list — which is the state where a colleague is told the workshop both exists and does "
            "not, and there is no screen in either client that shows them a workshop they cannot "
            "enumerate"
        )


def test_the_LEAD_is_whose_name_reaches_the_report_and_the_co_designers_does_not(world, client):
    """Many designers, one ``designerName``. The seam the multi-select must not break.

    ``designerName`` is promoted from a stage-1 SINGLETON, capped at 180 characters, and fed into
    the .docx's own ``dc:creator`` by ``report_meta`` — a field the file format cannot express as a
    list. So the team is plural and the name is singular: ``designerUserId`` names the lead whose
    ``DesignerProfile`` is copied, and the co-designers get access and nothing else.

    Note which name must NOT be there. Not the co-designer's — but also not the ADMIN's, which is
    the original defect: for months the seed copied the profile of whoever pressed create, and every
    automatic check in the product agreed the document was correct, because ``designerName`` was
    not MISSING, it was FILLED WITH THE WRONG PERSON.
    """
    workshop_id = _make(
        world,
        "Ikat, lead named",
        designerUserId=world["people"]["lead"].id,
        designerUserIds=_ids(world, ["co", "lead"]),
    )

    read = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "admin"))
    assert read.status_code == 200, read.text
    assert read.json()["designerName"] == "Meera Kanungo", (
        "the workshop's promoted designer name is not the LEAD's; with several designers ticked "
        "the server has started guessing which one to print on a ministry document"
    )
    assert _viewer_ids(client, world, workshop_id) == set(_ids(world, ["lead", "co"]))


def test_a_body_that_ticks_names_but_names_no_lead_seeds_the_FIRST_TICKED(world, client):
    """Not the admin who pressed create, which is the only other candidate.

    A client can legitimately send the plural field alone. Stage 1 still has exactly one designer
    block to fill, and seeding the ADMIN's profile into it is the wrong-name-on-a-ministry-document
    defect arriving by a new road.
    """
    workshop_id = _make(
        world,
        "Ikat, no lead named",
        designerUserIds=_ids(world, ["co", "lead"]),
    )
    read = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "admin"))
    assert read.status_code == 200, read.text
    assert read.json()["designerName"] == "Rukmini Behera"


def test_naming_the_admin_THEMSELVES_writes_no_viewer_row(world, client):
    """An admin running their own cluster is a real case, and it must not mint a redundant row.

    Their access comes from ``createdById``. A viewer row for them would be a SECOND source of truth
    for access they already hold — and one they could "remove" from the viewers screen without
    anything changing, which is the worst kind of control.
    """
    workshop_id = _make(
        world,
        "Ikat, admin is the designer",
        designerUserIds=_ids(world, ["admin", "lead"]),
    )
    assert _viewer_ids(client, world, workshop_id) == set(_ids(world, ["lead"]))
    assert client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "admin")
    ).status_code == 200


def test_one_ineligible_id_anywhere_in_the_list_refuses_the_WHOLE_create(world, client):
    """422 AND NO WORKSHOP — eligibility is settled ABOVE ``db.designworkshop.create``.

    ``revoked`` is a designer whose empanelment was withdrawn: they cannot sign in at all, so a
    viewer row for them is a grant their next sign-in ignores. The refusal has to take the whole
    call rather than apply the good half, because an admin who ticked three designers and is shown
    two has been told nothing about which one failed or why — and a partly applied access change is
    the worst of both, since it looks like it worked.

    ASKED AFTER THE CREATE, the same 422 would answer the client with the workshop row already
    committed, so an admin correcting the picker and pressing create again would accumulate one
    orphan draft per attempt in a list that distinguishes them in no way.
    """
    title = f"Ikat, refused {uuid.uuid4().hex[:8]}"
    response = _create(world, title, designerUserIds=_ids(world, ["lead", "revoked"]))
    assert response.status_code == 422, response.text
    assert "Revoked Designer" in response.text, (
        "the refusal must NAME the account it objected to; 'one of these is ineligible' sends the "
        "admin back to a picker with nothing to act on"
    )

    left_behind = _list_ids(client, world, "admin", search=title)
    assert left_behind == [], (
        "the create was refused but a workshop row was written anyway, so every retry leaves "
        "another orphan draft behind"
    )


def test_the_allow_list_arm_of_the_rule_also_refuses_a_ticked_account(world, client):
    """The SECOND table that can bar somebody, and the one that gates every role.

    ``DesignerRoster`` (the branch above) gates DESIGNERS only, so an ADMIN the platform allow-list
    has suspended is invisible to it and can be caught by nothing else. Ticking them would put a
    viewer row on the workshop for an account whose next sign-in is refused: one screen saying they
    are on the workshop, another showing them a refusal, and nothing connecting the two.
    """
    title = f"Ikat, barred admin {uuid.uuid4().hex[:8]}"
    response = _create(world, title, designerUserIds=_ids(world, ["lead", "suspendedAdmin"]))
    assert response.status_code == 422, response.text
    assert "Suspended Admin" in response.text
    assert _list_ids(client, world, "admin", search=title) == []


def test_the_eligibility_rule_does_not_adjudicate_the_CREATORS_OWN_standing(world, client):
    """An admin ticking their own name must not be able to 422 their own create.

    THE SAME ACCOUNT AS THE TEST ABOVE, refused there and ignored here, which is what makes this an
    assertion about the RULE rather than about the account. The viewers PUT already draws exactly
    this line — ``_deduplicate`` subtracts the creator BEFORE validation and says why: their access
    comes from ``createdById``, no viewer row is ever written for them, so "their standing is simply
    not this list's business". The create writes into the same table and must not disagree about
    who is in the set.

    IT IS REACHABLE RATHER THAN THEORETICAL. The ordinary door does not re-read the platform
    allow-list — only the bulk dataset door does — so an admin suspended after their token was
    minted still reaches this route, and the picker will happily show them their own name.

    NOTE WHAT THIS IS NOT SAYING. It is not an opinion about whether a suspended admin should reach
    the create route at all; that is decided by ``get_current_user`` and is the same before and after
    this feature. It says only that the DESIGNER-eligibility rule is not the place that question gets
    answered, because answering it there refuses a row that was never going to be written.
    """
    response = _create_as(
        world,
        "suspendedAdmin",
        "Ikat, opened by a barred admin",
        designerUserIds=_ids(world, ["suspendedAdmin"]),
    )
    assert response.status_code == 201, response.text
    workshop_id = response.json()["id"]
    assert _viewer_ids(client, world, workshop_id) == set(), (
        "a viewer row was written for the creator, which is a second and redundant source of truth "
        "for access they already hold through createdById"
    )


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 2. The refusal: both doors, and a refusal that discloses nothing
# ══════════════════════════════════════════════════════════════════════════════════════════════


def test_an_unticked_designer_cannot_LIST_the_workshop(world, client):
    """THE SCOPING IS IN THE QUERY, and this is the half a client-side filter would fake.

    A list that came back complete and was narrowed in the browser is not scoped at all: the row is
    one typed URL away, and both clients navigate by id. The workshop is created and immediately
    looked for by an eligible, actively empanelled DESIGNER who simply was not ticked.
    """
    workshop_id = _make(world, "Ikat, unlisted to strangers", designerUserIds=_ids(world, ["lead"]))
    assert workshop_id not in _list_ids(client, world, "stranger")


def test_an_unticked_designer_cannot_READ_the_workshop_by_id(world, client):
    """The other door. Widening WHO may enter must never widen it past the ticks."""
    workshop_id = _make(world, "Ikat, unreadable to strangers", designerUserIds=_ids(world, ["lead"]))
    response = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "stranger")
    )
    assert response.status_code == 404, response.text
    assert response.json()["detail"] == "Record not found"


def test_the_refusal_is_BYTE_IDENTICAL_to_an_id_that_does_not_exist(world, client):
    """404 and not 403, and the same detail string — the whole reason it is not 403.

    A 403 would confirm the id exists to exactly the people the clause is turning away. Design
    workshops are keyed by cuid and their ids travel on PRINTED JOIN CARDS, so a distinguishable
    refusal is a free existence oracle over a research data set: hold a card, learn whether the
    workshop is real, and learn it from the server that just refused you.

    Asserted as an EQUALITY between the two responses rather than as two separate expectations,
    because the property is that they cannot be told apart — an assertion that checked each against
    a literal would still pass on the day one of them gained an extra key.
    """
    workshop_id = _make(world, "Ikat, indistinguishable", designerUserIds=_ids(world, ["lead"]))

    refused = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "stranger")
    )
    absent = client.get(
        f"/api/design-workshops/{NO_SUCH_WORKSHOP}", headers=_headers(world, "stranger")
    )
    assert (refused.status_code, refused.json()) == (absent.status_code, absent.json()), (
        "a designer who is not on a workshop can now tell it apart from a workshop that does not "
        "exist, which turns every join card into an existence oracle"
    )


def test_a_search_cannot_reach_past_the_scope(world, client):
    """The ``where["OR"]`` collision, asserted rather than trusted.

    The search filter and the visibility scope both want to live in ``where["OR"]``. Written
    naively they are two assignments to the same key and the later one silently wins — either the
    search stops narrowing, or THE SCOPE STOPS APPLYING and every workshop matching the term
    appears. The second is the one that matters here, and it is invisible to a reader: the code
    looks like it filters, and it does, on the wrong axis.

    The term is the workshop's exact title, so a stranger who can see anything at all sees this.
    """
    marker = f"Kalamkari{uuid.uuid4().hex[:8]}"
    workshop_id = _make(world, f"{marker} scoped", designerUserIds=_ids(world, ["lead"]))

    assert workshop_id in _list_ids(client, world, "lead", search=marker)
    assert _list_ids(client, world, "stranger", search=marker) == []


def test_mineOnly_is_not_a_way_around_the_scope(world, client):
    """The one branch of the list route that does NOT compose the scope clause.

    ``mineOnly`` replaces the scope with ``createdById == me`` rather than narrowing under it, and
    that is correct — it means OWN, deliberately excluding the granted ones. But it is the single
    place a reader could mistake for "the scope is optional", so what it returns to a DESIGNER is
    pinned: nothing. A designer cannot create a workshop at all, so their own set is empty, and it
    must not fall back to the unscoped list on the way to saying so.
    """
    workshop_id = _make(world, "Ikat, mine only", designerUserIds=_ids(world, ["lead"]))
    assert _list_ids(client, world, "lead", mineOnly=True) == []
    assert workshop_id in _list_ids(client, world, "lead")


def test_a_designer_off_the_workshop_cannot_reach_its_STAGES_either(world, client):
    """The read is not the only door on a workshop, and the others funnel through the same loader.

    ``GET /{id}/stages`` is a separate route with its own gate, and it carries the same fieldwork —
    the interviews, the measurements, the photographs. It is asserted here rather than assumed
    because "we never built a UI that links to it" is not enforcement.
    """
    workshop_id = _make(world, "Ikat, staged", designerUserIds=_ids(world, ["lead"]))
    response = client.get(
        f"/api/design-workshops/{workshop_id}/stages", headers=_headers(world, "stranger")
    )
    assert response.status_code == 404, response.text
    assert response.json()["detail"] == "Record not found"


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 3. Admins and the master admin see all of them
# ══════════════════════════════════════════════════════════════════════════════════════════════


@pytest.mark.parametrize("slug", ["otherAdmin", "master"])
def test_an_admin_who_is_a_stranger_to_the_workshop_still_sees_it(world, client, slug):
    """Property 3, and the accounts are chosen so that ``is_admin`` is the ONLY thing admitting them.

    ``otherAdmin`` and ``master`` did not create this workshop and hold no viewer row on it, so both
    the ``createdById`` arm and the grant arm fail for them and the role arm is what is left. Testing
    this with the CREATING admin would prove nothing, because they would have got in either way.

    Both doors again: an admin who can open a workshop by id but cannot find it in the list has no
    way to administer it, since nothing in either client navigates by typed id.
    """
    marker = f"Pattachitra{uuid.uuid4().hex[:8]}"
    workshop_id = _make(world, f"{marker} admin-visible", designerUserIds=_ids(world, ["lead"]))

    read = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, slug))
    assert read.status_code == 200, read.text
    # SEARCHED FOR RATHER THAN LOOKED FOR ON PAGE ONE. An admin's list is UNSCOPED, so on a
    # long-lived development database it is every workshop anybody has ever made — and this suite
    # alone adds dozens per run. A bare `pageSize=100` assertion would pass today and start failing
    # for a reason that has nothing to do with visibility.
    assert workshop_id in _list_ids(client, world, slug, search=marker)


def test_the_master_admin_sees_a_workshop_with_no_designers_on_it_at_all(world, client):
    """A workshop opened in a room on day one, before anybody knows who will run it.

    The narrowing must not make an unassigned workshop invisible to everyone including the people
    whose job is to assign it — which is what a rule spelled "visible to its designers" rather than
    "visible to its designers AND admins" would do.
    """
    marker = f"Dhokra{uuid.uuid4().hex[:8]}"
    workshop_id = _make(world, f"{marker} nobody named yet")
    assert _viewer_ids(client, world, workshop_id) == set()
    assert workshop_id in _list_ids(client, world, "master", search=marker)
    assert client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "master")
    ).status_code == 200
    assert _list_ids(client, world, "stranger", search=marker) == []


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 4. The fortnight-behind handset
# ══════════════════════════════════════════════════════════════════════════════════════════════


def test_the_old_single_designer_body_still_grants_and_still_seeds(world, client):
    """An APK in the field sends ``designerUserId`` and nothing else. It may never 422.

    On Android ``saveOrQueue`` will NOT re-queue a 4xx: a create body the server refuses is a create
    whose record is LOST, not retried. That is why the new field is additive and optional and why
    the old one keeps its exact meaning — and why this is asserted end to end rather than argued in
    a comment.
    """
    workshop_id = _make(world, "Ikat, old wire", designerUserId=world["people"]["lead"].id)

    assert _viewer_ids(client, world, workshop_id) == set(_ids(world, ["lead"]))
    read = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "lead"))
    assert read.status_code == 200, read.text
    assert read.json()["designerName"] == "Meera Kanungo"


def test_a_body_that_names_nobody_is_still_a_legal_create(world, client):
    """Older still: the body every client sent before either field existed.

    It is also what the OFFLINE create path sends today, because eligibility is two roster reads on
    the server and no useful part of it is answerable on a device with no signal.
    """
    response = _create(world, "Ikat, nobody named")
    assert response.status_code == 201, response.text
    assert response.json()["designerName"] is None, (
        "with nobody named, the designer box must be EMPTY — a blank the completeness score and the "
        "report warnings can both see, never the admin's name, which no check in the product can"
    )


# ══════════════════════════════════════════════════════════════════════════════════════════════
# 5. Linking an offline workshop into the admin's entry — the SERVER half
# ══════════════════════════════════════════════════════════════════════════════════════════════
#
# THE THIRD ASK, verbatim: "if the report has been created for a workshop named by the designer in
# absence of internet, there should be an option to later on link that entry to a corresponding
# design and prototype workshop entry made by the admins as well later."
#
# THAT MECHANISM IS ALREADY BUILT, AND IT IS BUILT ON THE CLIENTS, NOT HERE. A designer's offline
# workshop is not a row: it is a local draft (``DwDraft`` in IndexedDB on the web,
# ``WorkshopDraft`` on disk on Android) whose ``remoteId`` is null. "Linking" re-points that
# ``remoteId`` at the admin's existing workshop — ``adoptedIntoWorkshop`` /
# ``adoptDraftIntoWorkshop`` on the web, the identically named pair on Android — and the ordinary
# sync pass then does the rest through THESE routes, with no endpoint of its own. There is
# deliberately no server-side merge of two ``DesignWorkshop`` rows, and there must not be one: it
# would have to move every stage entry, AI layer, export, rating, questionnaire and media link, and
# would leave two ids in circulation on printed join cards.
#
# SO WHAT THE SERVER OWES THE FEATURE IS EXACTLY THESE THREE THINGS, and all three are visibility:
# the designer must be able to FIND the admin's workshop (the scoped list, with search), to PATCH
# its header, and to PUT its stages. The narrowing makes that dependent on being ticked — which is
# precisely why ask 1 and ask 3 are one wave: if the admin forgets to name the designer, there is
# nothing in the adoption picker for their fortnight of fieldwork to land in.


def test_a_ticked_designer_can_adopt_their_offline_fieldwork_into_the_admins_workshop(
    world, client
):
    """The two calls the adoption re-sync actually makes, in the order it makes them.

    After ``adoptDraftIntoWorkshop`` re-points a local draft at this id, the next sync pass PATCHes
    the header keys the designer typed in the room and PUTs each dirty stage. Both go through
    ``load_workshop_or_404(..., for_edit=True)``, which performs no role check beyond the loader —
    so a viewer row is what makes a fortnight of offline fieldwork land, and this is the assertion
    that it does.

    THE PICKER IS THE THIRD CALL AND IT IS ASSERTED FIRST, because it is the one that fails
    silently: the adoption dialog is populated from the SCOPED list, so a designer who cannot
    enumerate the workshop is shown "no matches" for a workshop that exists and is theirs.
    """
    marker = f"Sambalpuri{uuid.uuid4().hex[:8]}"
    workshop_id = _make(world, f"{marker} for adoption", designerUserIds=_ids(world, ["lead"]))

    assert workshop_id in _list_ids(client, world, "lead", search=marker), (
        "the workshop is absent from the designer's own scoped list, so the adoption picker cannot "
        "offer it and a fortnight of offline fieldwork has nowhere to land"
    )

    patched = client.patch(
        f"/api/design-workshops/{workshop_id}",
        json={"notes": "Typed in the room, before there was any signal."},
        headers=_headers(world, "lead"),
    )
    assert patched.status_code == 200, patched.text
    assert patched.json()["notes"] == "Typed in the room, before there was any signal."

    staged = client.put(
        f"/api/design-workshops/{workshop_id}/stages/{STAGE_1}",
        json={"entries": [], "replaceCollections": False},
        headers=_headers(world, "lead"),
    )
    assert staged.status_code == 200, staged.text


def test_an_unticked_designer_cannot_adopt_anything_into_it(world, client):
    """The same three calls, refused, and refused the same way the read is.

    This is the security half of the linking feature and the reason it is tested beside the scope
    rather than in a module of its own: "link my offline entry to the admin's entry" is a WRITE into
    somebody else's workshop, and if the picker were the only thing stopping it then a typed id
    would file a stranger's fortnight of fieldwork into a cluster it has nothing to do with.

    The refusals are 404 and not 403 for the same reason the read's is: a 403 on the PATCH would
    confirm the id exists to somebody who has just proved they are not on the workshop.
    """
    marker = f"Sambalpuri{uuid.uuid4().hex[:8]}"
    workshop_id = _make(world, f"{marker} not for adoption", designerUserIds=_ids(world, ["lead"]))

    assert _list_ids(client, world, "stranger", search=marker) == []

    patched = client.patch(
        f"/api/design-workshops/{workshop_id}",
        json={"notes": "Filed against somebody else's cluster."},
        headers=_headers(world, "stranger"),
    )
    assert patched.status_code == 404, patched.text
    assert patched.json()["detail"] == "Record not found"

    staged = client.put(
        f"/api/design-workshops/{workshop_id}/stages/{STAGE_1}",
        json={"entries": [], "replaceCollections": False},
        headers=_headers(world, "stranger"),
    )
    assert staged.status_code == 404, staged.text
    assert staged.json()["detail"] == "Record not found"

    # AND THE WORKSHOP IS UNTOUCHED, which is the assertion the designers who own it care about.
    read = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "lead"))
    assert read.status_code == 200, read.text
    assert read.json()["notes"] is None
