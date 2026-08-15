"""Who, besides the creator, may open a design & prototype workshop — and what that buys them.

THE FAILURE THIS FEATURE ENDS. ``load_workshop_or_404`` admitted ``createdById`` and admins and
nobody else, so a 22-stage record holding a fortnight of fieldwork was readable by exactly one
account. A real Design & Prototype Development Workshop is run by two designers alongside a master
craftsperson and a reviewing officer; the second designer could not open it at all, and a designer
who left mid-season took the record with them.

FOUR THINGS ARE PINNED HERE, and each is a way the feature could be shipped looking finished while
being useless or dangerous.

**A GRANT THAT THE LIST DOES NOT HONOUR IS WORSE THAN NO GRANT.** If ``GET /design-workshops``
still filters on ``createdById`` alone, the granted colleague is told the workshop exists (they can
open it by id) and simultaneously that it does not (it is absent from every list they can reach).
There is no screen in either client that shows them a workshop they cannot enumerate, so the
feature would read as broken to the one person it was built for. Two tests cover the list, and the
second one is the interesting one: the scope must survive a ``search``, because both clauses want
to live in ``where["OR"]`` and the later assignment silently wins.

**A GRANT IS READ AND STAGE WRITES. IT IS NOT DELETE AND NOT RE-GRANTING.** A co-designer is there
to do fieldwork, not to hand out access or to destroy the record. Both refusals are asserted
directly, because "we never built the UI for it" is not enforcement.

**AN INELIGIBLE ID IS A 422 THAT NAMES IT, NEVER A SILENT SKIP.** An admin who ticks four designers,
presses Save and is shown three has been told nothing. Worse is the suspended designer: their
account cannot sign in at all, so a viewer row for them is a grant that the next sign-in refuses,
with nothing on screen saying why. The server owns that rule and states it out loud.

**THE CREATOR'S ACCESS IS NOT IN THIS LIST'S GIFT.** They hold the workshop through ``createdById``,
a different clause entirely, so naming them in the set must be harmless rather than an error, and
omitting them must not lock them out of their own workshop.

**AND AN ELIGIBLE ACCOUNT THE PICKER CANNOT REACH IS THE SAME BUG AS AN INELIGIBLE ONE — THE FIFTH,
ADDED AFTER IT SHIPPED.** ``eligible-viewers`` answered with the first 2000 accounts by name and had
no search parameter and no way to say it had truncated, so once the repository passed 2000 eligible
accounts every colleague sorting past the cut was absent from both clients with nothing on screen
distinguishing that from never having been empanelled. Those two states must never look identical.
The tests for it are hermetic BY SEARCHING FOR THEIR OWN FIXTURES: the assertions below must fail
when an eligible account becomes unreachable and must not depend on how many rows the shared table
happens to hold — which is exactly what the old spelling of the eligible-set test did, silently,
until the table outgrew it.

Postgres is required — the behaviour under test is a row in ``DesignWorkshopViewer`` deciding an
HTTP status — so the module skips itself when ``DATABASE_URL`` does not point at a local database,
exactly as ``test_designer_roster`` does.

    docker compose up -d postgres minio          # from the REPOSITORY ROOT, not from backend/
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

import json
import os
import uuid
from datetime import UTC, datetime
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

PASSWORD = "viewer-test-password"

# The first stage of the registry, used to prove that a grant carries stage WRITES and not only
# reads. Named rather than discovered so that a registry reshuffle fails this test loudly instead
# of quietly testing nothing.
STAGE_1 = "WORKSHOP_SETUP"

# slug -> (role, display name). The three designers are deliberately distinguished by their ROSTER
# standing rather than by their role: all three are DESIGNER, and only one of them can actually
# sign in, which is the whole point of the eligibility rule.
ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("admin", "ADMIN", "Viewer Admin"),
    ("creator", "DESIGNER", "Workshop Creator"),
    ("colleague", "DESIGNER", "Second Designer"),
    ("outsider", "DESIGNER", "Unrelated Designer"),
    ("suspended", "DESIGNER", "Suspended Designer"),
    ("unlisted", "DESIGNER", "Never Empanelled Designer"),
    ("researcher", "RESEARCHER", "Ordinary Researcher"),
    # THE ONE ACCOUNT A RANK LADDER WOULD ADMIT. ``DESIGN_WORKSHOP_ROLES`` is a SET, and PROFESSOR
    # sits at rank 40 — ABOVE DESIGNER's 35 — so every "this tier and above" spelling of the
    # eligibility rule lets them in and the set does not. The researcher below cannot prove that
    # distinction: at rank 30 they are refused by the ladder and by the set alike, so a test with
    # only a researcher in it passes just as well against the wrong rule.
    ("professor", "PROFESSOR", "Senior Professor"),
    # THE DESIGNER WHOSE ADDRESS IS STORED SHOUTING. ``User.email`` for this one is UPPER CASE while
    # its roster row is lower-cased, which is how ``normalise_email`` writes roster rows. Nothing in
    # this account is unusual otherwise: it is eligible, and the WRITE path accepts it, because
    # ``_designers_the_roster_still_admits`` normalises both sides before comparing. The picker's
    # roster fold did not, so it hid an account the PUT would have taken — absent from the offer for a
    # reason that has nothing to do with the designer's standing, which is this module's whole defect.
    ("shouty", "DESIGNER", "Shouting Designer"),
)

#: slug -> isActive on DesignerRoster. "suspended" has a row that no longer admits them; "unlisted"
#: has no row at all. Both are accounts that cannot sign in, by two different routes, and both must
#: therefore be refused a viewer row.
ROSTER: tuple[tuple[str, bool], ...] = (
    ("creator", True),
    ("colleague", True),
    ("outsider", True),
    ("suspended", False),
    # Written lower-cased, like every roster row, against a ``User.email`` that is not — see
    # ``ACCOUNTS`` above.
    ("shouty", True),
)


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    """Every account and roster row the module needs, created before the app starts.

    Made here rather than inside a test because the Prisma client is shared with the running app
    and bound to the TestClient's event loop; touching it from a test's own loop is the kind of
    cross-loop use that fails intermittently rather than honestly.

    Every address carries a per-run stamp, because ``DesignerRoster.email`` is UNIQUE and fixed
    addresses would pass on a clean database and fail on the second run of the suite.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    # A SECOND per-run token, and it lives in a NAME while ``stamp`` lives only in the addresses.
    # ``eligible-viewers?search=`` matches name OR email, and one token in both fields could not tell
    # those two arms apart: a server that indexed only email would pass a name search that happened
    # to match the address as well. With the tokens separated, each arm is proven on its own — search
    # for this and only the NAME can have matched.
    name_token = f"Nabakalebara{uuid.uuid4().hex[:8]}"
    # A THIRD token, in the display name of two accounts that share it. See ``tied_ids``.
    tie_token = f"Sambalpuri{uuid.uuid4().hex[:8]}"

    def address(slug: str) -> str:
        return f"dwviewer-{slug}-{stamp}@example.org".lower()

    def stored_address(slug: str) -> str:
        """``User.email`` as the row actually holds it, which is not always what the roster holds.

        ``address`` is the canonical, lower-cased form — what ``normalise_email`` writes to
        ``DesignerRoster`` and what every other account here also carries in ``User``. Exactly one
        account stores its address in a different case, because that difference is a real state of
        this database (measured: two ``User`` rows hold a mixed-case address today) and the picker's
        roster fold compared the two forms byte-for-byte.
        """
        return address(slug).upper() if slug == "shouty" else address(slug)

    def display_name(slug: str, name: str) -> str:
        """The fixed name, stamped for ``outsider`` alone.

        Only that one account, because the others' names are asserted verbatim elsewhere in this
        module (``test_a_viewer_row_names_the_person_it_admits`` expects "Second Designer"). The
        outsider is the right account to stamp anyway: its name is what put it past the truncation
        that this module failed on — "Unrelated Designer" sorts under U — so the account whose
        reachability is in question is the one the name search has to find.
        """
        return f"{name} {name_token}" if slug == "outsider" else name

    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role, name in ACCOUNTS:
            people[slug] = await db.user.create(data={
                "email": stored_address(slug),
                "name": display_name(slug, name),
                "role": role,
                "passwordHash": hash_password(PASSWORD),
            })
        # EIGHT ACCOUNTS WITH ONE SHARED DISPLAY NAME AND WRITTEN IDS, CREATED HIGHEST ID FIRST.
        #
        # ``name`` is not a unique sort key on this table — hundreds of real rows share the name "Sync
        # Test", and the name at the 2000th row IS one of them — so ordering by name alone leaves the
        # order of a tie to Postgres, and with a list that gets CUT that is what decides who is
        # invisible. Pinning it needs rows whose id order disagrees with their physical order, which is
        # why the ids are WRITTEN rather than generated: a cuid is minted in ascending order, so
        # generated ids would agree with the insertion order and the assertion could pass over a query
        # that did not sort at all.
        #
        # EIGHT RATHER THAN TWO, and the number is the point. Without a tiebreaker the answer comes
        # back in whatever order the scan produced, so with two rows a broken implementation has an
        # even chance of looking correct — measured: the same two-row assertion passed under one
        # mutation and failed under another. Eight rows created in descending id order cannot be
        # mistaken for ascending by luck.
        #
        # ADMINs, so they need no roster row, and their addresses deliberately do NOT end in the
        # ``-{stamp}@example.org`` that ``_run_term`` searches for — the exact-set assertion below
        # names the accounts it expects, and these eight are not part of that question.
        tied_slugs: list[str] = []
        for index, letter in enumerate("hgfedcba"):
            slug = f"tied{index}"
            tied_slugs.append(slug)
            people[slug] = await db.user.create(data={
                "id": f"cmtie{stamp}{letter * 12}",
                "email": f"dwviewer-{slug}-{stamp}@tied.example.org",
                "name": f"Tied Name {tie_token}",
                "role": "ADMIN",
                "passwordHash": hash_password(PASSWORD),
            })
        for slug, is_active in ROSTER:
            await db.designerroster.create(data={
                "email": address(slug),
                "fullName": f"Roster row for {slug}",
                "institution": "Directorate of Handicrafts",
                "isActive": is_active,
                "revokedAt": None if is_active else datetime.now(UTC),
                "addedById": people["admin"].id,
            })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "people": people,
            "address": address,
            "stored_address": stored_address,
            "stamp": stamp,
            "name_token": name_token,
            "tie_token": tie_token,
            "tied_slugs": tied_slugs,
        }


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    """A bearer token for one of the fixture's accounts.

    Minted directly rather than obtained by signing in, because the roster gate lives on the LOGIN
    path only. What is under test here is the viewer rules, and a helper that signed in first would
    make every one of these tests depend on the sign-in gate as well — in particular the suspended
    designer, who by design cannot log in and whose INELIGIBILITY is the thing being asserted.
    """
    user = world["people"][slug]
    return {"Authorization": f"Bearer {create_access_token(user.id)}"}


def _make_workshop(world: dict[str, Any], title: str) -> str:
    """A fresh workshop owned by ``creator``, made through the API like a designer would.

    One per test that mutates a viewer set. Sharing a single workshop would make the whole module
    order-dependent, and the failure mode of that is a suite that passes alone and fails in CI.
    """
    response = world["client"].post(
        "/api/design-workshops",
        json={"title": title},
        headers=_headers(world, "creator"),
    )
    assert response.status_code == 201, response.text
    return response.json()["id"]


def _grant(world: dict[str, Any], workshop_id: str, slugs: list[str]):
    return world["client"].put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [world["people"][s].id for s in slugs]},
        headers=_headers(world, "admin"),
    )


# ------------------------------------------------------------------------------------------
# The refusal that was there before, which must survive unchanged
# ------------------------------------------------------------------------------------------


def test_a_colleague_with_no_grant_still_cannot_open_the_workshop(world, client):
    """The control. Widening access for grant-holders must not widen it for everybody.

    404 rather than 403 deliberately, and this test would pass just as well against the old code —
    that is what makes it worth keeping. It is the assertion that the change is a widening of one
    clause and not the removal of the check.
    """
    workshop_id = _make_workshop(world, "Ikat, no grant")
    response = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "outsider"))
    assert response.status_code == 404
    assert response.json()["detail"] == "Record not found"


# ------------------------------------------------------------------------------------------
# Read: the workshop itself, and the list it must also appear in
# ------------------------------------------------------------------------------------------


def test_a_granted_colleague_can_open_the_workshop(world, client):
    workshop_id = _make_workshop(world, "Ikat, granted")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    response = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "colleague"))
    assert response.status_code == 200, response.text
    assert response.json()["id"] == workshop_id


def test_a_granted_colleague_finds_the_workshop_in_the_list(world, client):
    """THE HALF THAT IS EASY TO FORGET, and the half that makes the other half usable.

    ``load_workshop_or_404`` admitting a grant-holder is invisible on its own: nothing in either
    client navigates to a design workshop by typed id. If the list still filters on ``createdById``
    alone, the colleague is simultaneously told the workshop exists and that it does not.
    """
    workshop_id = _make_workshop(world, "Ikat, listed")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    response = client.get("/api/design-workshops?pageSize=100", headers=_headers(world, "colleague"))
    assert response.status_code == 200, response.text
    assert workshop_id in [row["id"] for row in response.json()["items"]]


def test_the_grant_scope_survives_a_search(world, client):
    """The ``where["OR"]`` collision, asserted rather than trusted.

    The search filter and the visibility scope both want to be an ``OR``. Written naively they are
    two assignments to the same key and the later one silently wins: either the search stops
    narrowing (an admin's private workshops leak into a colleague's results) or the scope stops
    applying (the granted workshop vanishes the moment anyone types in the search box). Both are
    bugs a reader would never see, so the composition is pinned here.
    """
    marker = f"Kalamkari{uuid.uuid4().hex[:6]}"
    workshop_id = _make_workshop(world, f"{marker} scoped search")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    found = client.get(
        f"/api/design-workshops?pageSize=100&search={marker}", headers=_headers(world, "colleague")
    )
    assert found.status_code == 200, found.text
    assert [row["id"] for row in found.json()["items"]] == [workshop_id]

    # And the search still NARROWS: a term that matches nothing must return nothing, not fall back
    # to "every workshop this user may see".
    empty = client.get(
        "/api/design-workshops?pageSize=100&search=NoWorkshopIsCalledThis",
        headers=_headers(world, "colleague"),
    )
    assert empty.status_code == 200, empty.text
    assert empty.json()["items"] == []


# ------------------------------------------------------------------------------------------
# What a grant buys: stage writes, but not delete and not re-granting
# ------------------------------------------------------------------------------------------


def test_a_grant_carries_stage_writes(world, client):
    """A co-designer is there to do fieldwork. Read-only would not be a team."""
    workshop_id = _make_workshop(world, "Ikat, co-written")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    response = client.put(
        f"/api/design-workshops/{workshop_id}/stages/{STAGE_1}",
        json={"entries": [], "replaceCollections": False},
        headers=_headers(world, "colleague"),
    )
    assert response.status_code == 200, response.text


def test_a_grant_does_not_carry_delete(world, client):
    """Being let in to read and record is not being handed the ability to destroy the record."""
    workshop_id = _make_workshop(world, "Ikat, undeletable by guest")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    response = client.delete(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "colleague")
    )
    assert response.status_code == 403, response.text

    # And the workshop is still there, which is the assertion that matters to the creator.
    still = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "creator"))
    assert still.status_code == 200


def test_a_grant_does_not_carry_the_power_to_re_grant(world, client):
    """A viewer cannot enlarge the room they were let into.

    Both directions: they may not read the roster and they may not write it. Deciding who sees a
    workshop is administration, and a grant that could hand itself onward would make the admin's
    decision the first link in a chain nobody is watching.
    """
    workshop_id = _make_workshop(world, "Ikat, no onward grants")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    read = client.get(
        f"/api/design-workshops/{workshop_id}/viewers", headers=_headers(world, "colleague")
    )
    assert read.status_code == 403, read.text

    write = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [world["people"]["outsider"].id]},
        headers=_headers(world, "colleague"),
    )
    assert write.status_code == 403, write.text

    # The refused write changed nothing.
    rows = client.get(
        f"/api/design-workshops/{workshop_id}/viewers", headers=_headers(world, "admin")
    ).json()["viewers"]
    assert [row["userId"] for row in rows] == [world["people"]["colleague"].id]


def test_the_creator_keeps_their_own_workshop_when_the_viewer_set_is_emptied(world, client):
    """An empty viewer list must not mean "nobody can see this"."""
    workshop_id = _make_workshop(world, "Ikat, emptied")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200
    assert _grant(world, workshop_id, []).status_code == 200

    mine = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "creator"))
    assert mine.status_code == 200

    # ...and the colleague really is out again. Removal is sending the list without them.
    gone = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "colleague"))
    assert gone.status_code == 404


# ------------------------------------------------------------------------------------------
# The PUT: whole-set replace, idempotent, and loud about a bad id
# ------------------------------------------------------------------------------------------


def test_the_put_replaces_the_whole_set_and_is_idempotent(world, client):
    workshop_id = _make_workshop(world, "Ikat, replaced")

    first = _grant(world, workshop_id, ["colleague", "outsider"])
    assert first.status_code == 200, first.text
    assert sorted(row["userId"] for row in first.json()["viewers"]) == sorted(
        [world["people"]["colleague"].id, world["people"]["outsider"].id]
    )

    # Sending the same set again changes nothing and still answers with it.
    again = _grant(world, workshop_id, ["colleague", "outsider"])
    assert again.status_code == 200, again.text
    assert sorted(row["userId"] for row in again.json()["viewers"]) == sorted(
        [world["people"]["colleague"].id, world["people"]["outsider"].id]
    )

    # Removing somebody is sending the list without them.
    narrowed = _grant(world, workshop_id, ["colleague"])
    assert narrowed.status_code == 200, narrowed.text
    assert [row["userId"] for row in narrowed.json()["viewers"]] == [world["people"]["colleague"].id]


def test_a_viewer_row_names_the_person_it_admits(world, client):
    """The GET shape, which the admin screen renders directly.

    ``name``/``email``/``role`` travel WITH the row rather than being joined against a directory
    the screen also holds, so a viewer who has since dropped off the eligible list is still
    nameable instead of rendering as a bare cuid.
    """
    workshop_id = _make_workshop(world, "Ikat, named")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    rows = client.get(
        f"/api/design-workshops/{workshop_id}/viewers", headers=_headers(world, "admin")
    ).json()["viewers"]
    assert len(rows) == 1
    row = rows[0]
    assert row["userId"] == world["people"]["colleague"].id
    assert row["name"] == "Second Designer"
    assert row["email"] == world["address"]("colleague")
    assert row["role"] == "DESIGNER"
    assert row["grantedAt"]


def test_an_unknown_user_id_is_a_422_that_names_it(world, client):
    """Never a silent skip. An admin shown three of the four they ticked has been told nothing."""
    workshop_id = _make_workshop(world, "Ikat, bad id")
    bogus = "ckzzzzzzzzzzzzzzzzzzzzzzz"

    response = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [world["people"]["colleague"].id, bogus]},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 422, response.text
    assert bogus in str(response.json()["detail"])

    # And the whole write was refused — a partial application would be the worst of both.
    rows = client.get(
        f"/api/design-workshops/{workshop_id}/viewers", headers=_headers(world, "admin")
    ).json()["viewers"]
    assert rows == []


@pytest.mark.parametrize(
    ("label", "bad_id"),
    [
        # A `text` column cannot hold 0x00 at all, so this reaches the driver as a DataError.
        ("nul byte", "ck\x00zzzzzzzzzzzzzzzzzzzzzz"),
        # A phone that cut an emoji in half sends half a surrogate pair; it cannot be encoded to
        # UTF-8 at all, so it fails before Postgres even sees it.
        ("lone surrogate", "ck\ud800zzzzzzzzzzzzzzzzzzzzzz"),
    ],
)
def test_an_id_postgres_cannot_hold_is_a_422_not_a_500(world, client, label, bad_id):
    """A malformed id is a bad REQUEST, and answering it with a 500 is two bugs.

    ``_assert_every_id_may_be_granted`` reads every id out of ``User`` before it writes anything, so
    an id carrying a NUL byte or a lone surrogate went straight into the ``where`` of that query and
    came back as a ``DataError`` / ``UnicodeEncodeError`` — a bare 500 whose body names the exception
    class, and a stack trace in the log for every attempt. This function already answers "no account
    exists with this id" correctly for every OTHER id that cannot match; these two only differed in
    that they could not survive the comparison, which is the server's problem and not something to
    hand back as an internal error.

    The same characters are stripped rather than rejected in ``services/records.plain`` for search
    and equality filters, for the reason given there. Here they are REFUSED instead, because this is
    not a filter that should quietly return nothing — it is a write naming accounts, and the module's
    rule is that a bad id refuses the whole call and says which one.
    """
    workshop_id = _make_workshop(world, f"Ikat, {label}")

    # SENT AS RAW BYTES, not through ``json=``. ``ensure_ascii`` renders both characters as the
    # ``\uXXXX`` escapes JSON defines for them, so the body on the wire is plain ASCII and the
    # server's parser is what turns them back into the characters under test. Passing the Python
    # string to httpx instead fails in the CLIENT ("surrogates not allowed") and would prove only
    # that httpx declines to encode it — never reaching the route at all.
    body = json.dumps(
        {"userIds": [world["people"]["colleague"].id, bad_id]}, ensure_ascii=True
    )
    response = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        content=body,
        headers={**_headers(world, "admin"), "Content-Type": "application/json"},
    )
    assert response.status_code == 422, response.text

    # And nothing was written — the good half of the list must not have been applied.
    rows = client.get(
        f"/api/design-workshops/{workshop_id}/viewers", headers=_headers(world, "admin")
    ).json()["viewers"]
    assert rows == []


@pytest.mark.parametrize(
    "slug",
    [
        "researcher",  # right rank ladder position, wrong SET — see can_run_design_workshops
        # OUTRANKS a designer and is still refused. The refusal reads "… is a PROFESSOR and cannot
        # run a design & prototype workshop", which is the sentence an admin needs: being senior to
        # a designer is not the same thing as being one, and a viewer row would hand this account
        # access its own capability check refuses.
        "professor",
        "suspended",   # a DESIGNER whose roster row no longer admits them
        "unlisted",    # a DESIGNER who never had a roster row at all
    ],
)
def test_an_ineligible_account_is_a_422_that_names_it(world, client, slug):
    """The trap this closes is the SUSPENDED designer.

    Their account cannot sign in — ``roster_allows`` refuses it — so a viewer row for them is a
    grant the next sign-in ignores. Offering the account and then storing the row would leave an
    admin looking at a screen that says the designer has access while the designer looks at a
    refusal, with nothing anywhere connecting the two.
    """
    workshop_id = _make_workshop(world, f"Ikat, ineligible {slug}")

    response = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [world["people"][slug].id]},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 422, response.text
    detail = str(response.json()["detail"])
    assert world["address"](slug) in detail or world["people"][slug].id in detail


def test_naming_the_creator_is_harmless(world, client):
    """Their access comes from ``createdById``, a different clause, so this is a no-op not an error.

    A screen that listed the creator alongside the viewers and sent the lot back would otherwise
    422 on the one id an admin is most likely to include by accident.
    """
    workshop_id = _make_workshop(world, "Ikat, creator named")

    response = _grant(world, workshop_id, ["creator", "colleague"])
    assert response.status_code == 200, response.text
    assert [row["userId"] for row in response.json()["viewers"]] == [world["people"]["colleague"].id]

    # The creator can still open it, and has not acquired a row that an admin could "remove".
    assert client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "creator")
    ).status_code == 200


def test_the_list_length_is_bounded(world, client):
    """Every list on this wire is bounded. An unbounded one is a free way to make the server work."""
    workshop_id = _make_workshop(world, "Ikat, unbounded")

    response = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [f"id{n}" for n in range(500)]},
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 422, response.text


# ------------------------------------------------------------------------------------------
# The eligible list, and who may ask for any of this at all
# ------------------------------------------------------------------------------------------


def _eligible(world: dict[str, Any], **params: Any):
    """``GET /design-workshops/eligible-viewers`` as an admin, with whatever query it is given."""
    return world["client"].get(
        "/api/design-workshops/eligible-viewers",
        params=params,
        headers=_headers(world, "admin"),
    )


#: How many of this run's accounts may be offered a viewer row: the admin, the creator, the
#: colleague, the outsider and the shouting designer. Named because three tests below turn on it and
#: a new fixture account is exactly the kind of change that makes a hard-coded 4 wrong in one place
#: and right in another.
ELIGIBLE_FIXTURES = 5


def _run_term(world: dict[str, Any]) -> str:
    """A search term that matches this run's nine ``ACCOUNTS`` by EMAIL and nothing else on earth.

    Every address in ``ACCOUNTS`` ends ``-{stamp}@example.org``, so this reaches all nine — including
    the one stored in upper case, since the search is case-insensitive — and, because the stamp is a
    fresh uuid per run, no account any other run or any other test left behind. That is what makes the
    assertions below EXACT rather than "is my account among the 2000 that fitted".

    The two ``tied`` accounts are deliberately outside it; they answer a different question.
    """
    return f"-{world['stamp']}@example.org"


def test_eligible_viewers_offers_only_accounts_that_could_actually_open_a_workshop(world, client):
    """The eligible SET, asserted exactly, over a table of whatever size.

    **THIS TEST USED TO ASSERT THE SAME THING AND PASS BY ACCIDENT.** It asked for the whole picker
    and looked for its own accounts in the answer, which worked only while the repository held fewer
    accounts than the endpoint's 2000-row ceiling. It crossed that line — 1344 admins plus 1282
    rostered designers — and the assertion on ``outsider`` began to fail: "Unrelated Designer" sorts
    under U, past a cut made in the middle of the alphabet by ``order={"name": "asc"}``. So the test
    had been measuring the size of a shared table all along, and its greenness was an accident.

    It now finds its own fixtures with ``search``, which fixes that in the only way that does not
    weaken it: the term is unique to this run, so the answer is a CLOSED set and can be compared
    with ``==``. Nothing here depends on how many accounts exist, and nothing here is satisfied by
    an endpoint that happens to return the first N names of the alphabet.

    **AND THE EQUALITY IS WHAT PINS THE SEARCH TO THE ELIGIBILITY RULE.** The term matches all NINE
    of this run's ``ACCOUNTS`` by email; only five of them may hold a viewer row. Eligibility is an
    ``OR`` and the search is an ``OR``, so an implementation that assigned both to ``where["OR"]``
    would let the later one win and offer all nine — the researcher, the professor and the two
    designers who cannot sign in included. That is not a cosmetic bug on this screen: it is an admin
    being offered accounts whose next sign-in refuses the access, and it is caught here by the four
    ids that must be absent.
    """
    response = _eligible(world, search=_run_term(world))
    assert response.status_code == 200, response.text
    body = response.json()
    offered = {row["id"] for row in body["users"]}

    assert offered == {
        world["people"]["admin"].id,
        world["people"]["creator"].id,      # a DESIGNER the roster admits
        world["people"]["colleague"].id,
        # THE ACCOUNT THIS MODULE FAILED ON. Named here rather than left to the set comparison
        # because the whole defect was this id, and only this id, dropping off the end of the
        # picker with nothing anywhere saying so.
        world["people"]["outsider"].id,
        # And the roster-admitted designer whose address is stored in upper case: the roster fold
        # compares lower-cased emails, so a byte-for-byte ``in`` hid this account from the picker
        # while the write path — which normalises both sides — accepted it. See
        # ``test_a_designer_whose_address_is_stored_shouting_is_still_offered``.
        world["people"]["shouty"].id,
    }
    assert world["people"]["outsider"].id in offered

    # A researcher outranks nobody into this set — it is a SET, not a threshold.
    assert world["people"]["researcher"].id not in offered
    # And a PROFESSOR, who DOES outrank a designer, is out for the same reason. This is the
    # assertion that distinguishes the set from the ladder: swap ``DESIGN_WORKSHOP_ROLES`` for
    # ``role_rank(user) >= ROLE_RANK["DESIGNER"]`` and only this line fails.
    assert world["people"]["professor"].id not in offered
    # And the two designers who cannot sign in are not offered, because a row for them is a trap.
    assert world["people"]["suspended"].id not in offered
    assert world["people"]["unlisted"].id not in offered

    # Five accounts asked for and five returned, so nothing was cut and the answer must not claim it
    # was. A ``truncated`` hardwired true would be as useless as the silence it replaced.
    assert body["truncated"] is False


def test_the_search_matches_a_name_as_well_as_an_email(world, client):
    """Both arms, each proven where the other cannot reach.

    An admin looking for a colleague knows their name or their address and should not have to guess
    which one the picker indexes; a server that folded in only one arm would look correct to whoever
    tested with the field they had in mind. The fixture keeps the two tokens in DIFFERENT fields for
    exactly this: ``stamp`` appears only in addresses and ``name_token`` only in one display name, so
    each search below can only have been answered by the field it names.

    The name arm also carries the original defect: the account it finds is the one whose NAME put it
    past the truncation, which is why reaching it by name is the thing that had to become possible.
    """
    by_email = _eligible(world, search=_run_term(world))
    assert by_email.status_code == 200, by_email.text
    assert world["people"]["outsider"].id in {row["id"] for row in by_email.json()["users"]}

    by_name = _eligible(world, search=world["name_token"])
    assert by_name.status_code == 200, by_name.text
    assert [row["id"] for row in by_name.json()["users"]] == [world["people"]["outsider"].id]

    # Case-insensitively, because an admin types a surname the way they say it, not the way the
    # directory stored it.
    shouted = _eligible(world, search=world["name_token"].upper())
    assert shouted.status_code == 200, shouted.text
    assert [row["id"] for row in shouted.json()["users"]] == [world["people"]["outsider"].id]

    # And it still NARROWS. A term that matches nothing must answer with nothing rather than falling
    # back to the whole picker — the failure that would make every search look like it worked.
    nothing = _eligible(world, search=f"no-account-is-called-this-{world['stamp']}")
    assert nothing.status_code == 200, nothing.text
    assert nothing.json()["users"] == []
    assert nothing.json()["truncated"] is False


def test_a_search_term_postgres_cannot_hold_is_an_empty_answer_not_a_500(world, client):
    """``?search=%00`` reaches the query as a parameter, and a ``text`` column cannot hold NUL.

    The same failure ``_UNSTORABLE_IN_AN_ID`` closes on the write path, one field away: it arrives as
    a ``DataError`` from the driver and surfaces as a bare 500 with a stack trace per attempt. Here
    it is STRIPPED rather than refused — ``records.contains`` does it for all 57 search boxes in the
    app — because this is a filter, and an admin who pasted a name out of a PDF and picked up a
    control character wants their search to run.
    """
    response = _eligible(world, search=f"\x00{world['name_token']}")
    assert response.status_code == 200, response.text
    assert [row["id"] for row in response.json()["users"]] == [world["people"]["outsider"].id]


def test_the_answer_says_when_the_list_was_cut_and_says_nothing_when_it_was_not(world, client,
                                                                               monkeypatch):
    """``truncated``, at both edges, without depending on the size of the table.

    The endpoint has no page-size parameter to drive this from the wire — the client renders one
    searchable dropdown — so the ceiling itself is moved. That keeps the test hermetic: asserting
    ``truncated is True`` on an unsearched call would only be asserting that this database currently
    holds more than 2000 eligible accounts, which is the table-size dependency this module is being
    cured of.

    **THE SECOND HALF IS THE ONE THAT WOULD HAVE BEEN MISSED.** The old code inferred truncation from
    ``len(users) == LIMIT``, which cannot tell "exactly full" from "cut" and cries truncation at a
    list that is complete. Reading one row more than is returned answers it exactly, and costs
    nothing — no second ``COUNT`` over a table an ILIKE cannot index.
    """
    from app.services import design_workshop_viewers as service

    term = _run_term(world)

    monkeypatch.setattr(service, "ELIGIBLE_VIEWER_LIMIT", 2)
    cut = _eligible(world, search=term)
    assert cut.status_code == 200, cut.text
    assert len(cut.json()["users"]) == 2, "the extra row read to detect the cut must not be served"
    assert cut.json()["truncated"] is True

    monkeypatch.setattr(service, "ELIGIBLE_VIEWER_LIMIT", ELIGIBLE_FIXTURES)
    exactly_full = _eligible(world, search=term)
    assert exactly_full.status_code == 200, exactly_full.text
    assert len(exactly_full.json()["users"]) == ELIGIBLE_FIXTURES
    assert exactly_full.json()["truncated"] is False, (
        "a list that is exactly as long as the ceiling is complete, not truncated"
    )


def test_a_cut_roster_read_is_reported_instead_of_dropping_designers_in_silence(world, client,
                                                                               monkeypatch):
    """The second truncation, which had no warning and no wire signal at all.

    ``_active_roster_emails`` borrowed the picker's ``ELIGIBLE_VIEWER_LIMIT`` as a read cap, and the
    two are not the same quantity. Its rows are not a page shown to anybody — they are folded into
    the user query's ``WHERE`` — so a roster row past the cut does not shorten a list, it removes an
    ELIGIBLE DESIGNER from the picker as though they had never been empanelled. At 1282 active rows
    the shared 2000 was not being hit, so there was nothing to see and nothing that would have said
    so when there was.

    Driven by moving the cap rather than by writing 50000 roster rows, and asserted as a COMPARISON
    against the same call uncut, so it does not depend on which row Postgres happens to return first.
    """
    from app.services import design_workshop_viewers as service

    term = _run_term(world)

    whole = _eligible(world, search=term)
    assert whole.status_code == 200, whole.text
    assert len(whole.json()["users"]) == ELIGIBLE_FIXTURES, whole.text

    monkeypatch.setattr(service, "ACTIVE_ROSTER_READ_LIMIT", 1)
    starved = _eligible(world, search=term)
    assert starved.status_code == 200, starved.text
    body = starved.json()

    # The designers this run rosters are gone, because the roster read could not see them...
    assert len(body["users"]) < ELIGIBLE_FIXTURES, (
        "the roster read was capped at one row and the picker answered as though nothing changed"
    )
    # ...the admin is untouched, because admins are not roster-gated at any point...
    assert world["people"]["admin"].id in {row["id"] for row in body["users"]}
    # ...and the answer ADMITS it is incomplete, which is the whole finding. Before this, an
    # eligible designer vanished with no log line anywhere and no test failing.
    assert body["truncated"] is True


def test_the_picker_is_ordered_by_name_and_both_clients_depend_on_that(world, client):
    """``order={"name": "asc"}``, pinned — because deleting it was a silent, green mutation.

    NOT COSMETIC, and that is the reason this test exists at all. Two things rest on the ordering:

    * **Both clients trust it and neither re-sorts.**
      ``android/…/data/DesignWorkshopViewers.dwViewerChoices`` says so in its own comment — Kotlin's
      ``sortedBy`` compares UTF-16 code units and disagrees with Postgres's collation, so it
      deliberately keeps the server's order. There is an Android test named "the order is the
      server's, not this client's"; it pins the CLIENT not re-sorting, and nothing pinned the SERVER
      sorting. A picker whose 2000 names arrive in physical row order is unreadable.
    * **Without an ORDER BY the CUT is non-deterministic.** The list is capped, so an arbitrary order
      means two identical requests hide two DIFFERENT populations, and "is this colleague reachable"
      changes between refreshes. That is the invisible-colleague defect this module was fixed for,
      restored in a form no search term can be relied on to reach.

    Asserted over this run's own accounts through ``search``, so it does not depend on the size of the
    shared table, and asserted as an EXACT sequence rather than "is it sorted": the fixture creates
    these accounts in a different order (admin, creator, colleague, outsider, …, shouty), and the
    answer to an unordered version of this query is a physical-row order that matches neither. Deleting
    the ``order`` and re-running put "Viewer Admin" first, which is where it was created.
    """
    response = _eligible(world, search=_run_term(world))
    assert response.status_code == 200, response.text
    names = [row["name"] for row in response.json()["users"]]

    assert names == [
        "Second Designer",
        "Shouting Designer",
        f"Unrelated Designer {world['name_token']}",
        "Viewer Admin",
        "Workshop Creator",
    ], "the picker's order is the only order either client has"


def test_accounts_that_share_a_name_come_back_in_one_stable_order(world, client, monkeypatch):
    """A name is not a unique sort key on this table, and the list is CUT.

    Measured on this database: 137 accounts share the name "Second Designer", which is the name the
    2000-row cut lands on today, and 205 share "Sync Test". Ordering by name alone leaves a tie to
    Postgres, which decides — arbitrarily, and possibly differently per request — which of the tied
    accounts falls inside the ceiling and which is invisible. An admin refreshing the screen would see
    a different set of colleagues with nothing having changed, and no search term could be relied on
    to reach a particular one of them.

    **THE ANSWER ALONE CANNOT PIN THIS, AND THAT IS MEASURED RATHER THAN ASSUMED.** Postgres answers
    this query with a bitmap heap scan (checked with EXPLAIN), which returns rows in physical order,
    and physical order does not follow insertion order once the table has reused free space. Removing
    the tiebreaker and re-running showed both outcomes on the same fixture at different moments — the
    tie came back ascending once and descending once — which is *exactly* why the tiebreaker is
    needed and also why an assertion over the returned ids is a coin toss as a regression guard. So
    the returned order is asserted (it is deterministic once the query sorts by id) AND the query
    itself is asserted, which is the part that cannot come out right by luck.

    The eight fixture accounts carry WRITTEN ids, created highest first, so no accidental agreement
    between insertion order, id order and physical order can make the answer look sorted.
    """
    from app.core.db import db as prisma

    # Patched on the CLASS, not on ``db.user``: the generated actions object refuses attribute
    # assignment ("'UserActions' object attribute 'find_many' is read-only"), which is worth writing
    # down because the instance-level spelling raises rather than silently failing to observe
    # anything. ``monkeypatch`` puts the method back at teardown; the module beside this one
    # (``test_designer_roster``) is re-run with it to prove the restoration.
    actions = type(prisma.user)
    captured: dict[str, Any] = {}
    original = actions.find_many

    async def spy(self: Any, *args: Any, **kwargs: Any):
        captured.update(kwargs)
        return await original(self, *args, **kwargs)

    monkeypatch.setattr(actions, "find_many", spy)

    response = _eligible(world, search=world["tie_token"])
    assert response.status_code == 200, response.text
    ids = [row["id"] for row in response.json()["users"]]

    assert set(ids) == {world["people"][slug].id for slug in world["tied_slugs"]}
    assert ids == sorted(ids), "accounts sharing a name must come back in a total, stable order"
    # THE ORDER THE SERVER ASKED FOR, not the order this table happened to hand back. Deleting the
    # ORDER BY, or dropping the id from it, fails here whatever Postgres does with the tie.
    assert captured.get("order") == [{"name": "asc"}, {"id": "asc"}], (
        "the picker must ask Postgres for a TOTAL order: name for the reader, id so the cut is "
        "deterministic when hundreds of accounts share a name"
    )


def test_a_whitespace_only_search_is_the_same_as_no_search_at_all(world, client, monkeypatch):
    """The contract the route's docstring states and nothing asserted: blank means unsearched.

    ``?search=%20%20`` reaching the query as a real ``ILIKE '%  %'`` collapses the picker to "No
    eligible account matches that search." for an admin who typed nothing — an empty list with no
    explanation, which this repository's own notes call its most repeated bug class. Both clients trim
    before sending today, so this is a SERVER contract with no test behind it: the guard is one
    ``.strip()`` and removing it left the whole suite green.

    Hermetic by raising the ceiling instead of counting rows: every variant must offer THIS RUN's
    thirteen eligible accounts, which a whitespace pattern cannot match (their names hold single
    spaces, no tabs and no newlines) and an unsearched query must. Asserting a subset rather than
    equality is deliberate — six other lanes create accounts continuously, so the whole answer is not
    stable between two requests, while the presence of these thirteen is.
    """
    from app.services import design_workshop_viewers as service

    monkeypatch.setattr(service, "ELIGIBLE_VIEWER_LIMIT", 100_000)
    expected = {
        world["people"][slug].id
        for slug in ("admin", "creator", "colleague", "outsider", "shouty", *world["tied_slugs"])
    }

    for label, params in (
        ("omitted", {}),
        ("empty", {"search": ""}),
        ("three spaces", {"search": "   "}),
        ("tab and newline", {"search": " \t\n "}),
    ):
        answer = _eligible(world, **params)
        assert answer.status_code == 200, f"{label}: {answer.text}"
        body = answer.json()
        assert expected <= {row["id"] for row in body["users"]}, (
            f"a {label} search was treated as a filter instead of as no search at all"
        )
        assert body["truncated"] is False, label


def test_the_roster_read_cap_is_its_own_number_and_stays_a_backstop():
    """The constant itself, because the behaviour test cannot see its value.

    ``test_a_cut_roster_read_…`` drives the truncation by monkeypatching this constant to 1, which
    proves the code READS it and says nothing about what it is set to. Re-shrinking it in source to
    the picker's 2000 therefore leaves the whole suite green — and at 1523 active roster rows measured
    today that is 1.3x headroom on a table that only grows, for a read whose overflow does not shorten
    a list but REMOVES eligible designers from the picker entirely.

    So what is pinned here is the reasoning rather than a magic number: this is a different quantity
    from the picker's page size (sharing that constant was the defect), and it is a backstop against
    an unbounded read rather than a working limit — far above any plausible roster, so that hitting it
    means the ``IN``-list query shape has been outgrown and not that a normal deployment has grown.
    Raise it freely; making it the picker's page size again is the regression.
    """
    from app.services import design_workshop_viewers as service

    assert service.ACTIVE_ROSTER_READ_LIMIT != service.ELIGIBLE_VIEWER_LIMIT, (
        "the roster read cap and the picker's page size are different quantities"
    )
    assert service.ACTIVE_ROSTER_READ_LIMIT >= 10 * service.ELIGIBLE_VIEWER_LIMIT, (
        "a roster read cap within reach of the picker's page size is a working limit, not a backstop"
    )


def test_a_designer_whose_address_is_stored_shouting_is_offered_and_may_be_granted(world, client):
    """The picker and the write must agree about the same account, in both directions.

    ``_active_roster_emails`` returns ``normalise_email``'d — lower-cased — addresses and the picker
    folded them into ``{"email": {"in": …}}``, an exact comparison against ``User.email`` as stored.
    The WRITE path (``_designers_the_roster_still_admits``) normalises BOTH sides. So a designer whose
    address is stored in a different case from their roster row was hidden from the picker while the
    PUT would happily accept them: an eligible colleague absent from the offer for a reason that has
    nothing to do with their standing, which is this module's defect in miniature — and worse than the
    ceiling, because no search term reaches them either.

    Not hypothetical: two ``User`` rows on this database hold a mixed-case address today. It is the
    ASYMMETRY that makes it a defect rather than a policy — either answer would be defensible if both
    paths gave it.
    """
    shouty = world["people"]["shouty"]
    assert shouty.email == world["address"]("shouty").upper(), "the fixture stores it shouting"

    # Found by the address as the ROSTER holds it (lower-cased, and what an admin would type)...
    by_roster_form = _eligible(world, search=world["address"]("shouty"))
    assert by_roster_form.status_code == 200, by_roster_form.text
    assert [row["id"] for row in by_roster_form.json()["users"]] == [shouty.id]

    # ...and by the address as ``User`` holds it, because the search is case-insensitive either way.
    by_stored_form = _eligible(world, search=shouty.email)
    assert [row["id"] for row in by_stored_form.json()["users"]] == [shouty.id]

    # And the write agrees, which is the half that was never in doubt and is what made the picker's
    # silence a contradiction rather than a policy.
    workshop_id = _make_workshop(world, "Ikat, shouting address")
    granted = _grant(world, workshop_id, ["shouty"])
    assert granted.status_code == 200, granted.text
    assert [row["userId"] for row in granted.json()["viewers"]] == [shouty.id]


def test_eligible_viewers_is_not_swallowed_by_the_workshop_id_route(world, client):
    """A literal path that collides with ``/{workshop_id}``, pinned as a test.

    ``GET /design-workshops/{workshop_id}`` matches ``/design-workshops/eligible-viewers`` perfectly
    well and answers 404 "Record not found". Whether this endpoint works at all therefore depends on
    router REGISTRATION ORDER, which is not visible from either module — so it is asserted here
    rather than left to whoever next reorders app/api/router.py.
    """
    response = client.get("/api/design-workshops/eligible-viewers", headers=_headers(world, "admin"))
    assert response.status_code == 200, response.text
    assert "users" in response.json()


@pytest.mark.parametrize("slug", ["creator", "colleague", "researcher", "professor"])
def test_only_an_admin_may_administer_viewers(world, client, slug):
    """Including the workshop's own CREATOR.

    Deliberate, and the one rule here most likely to be argued with. The owner deciding their own
    readers sounds reasonable until a designer leaves and their workshop's access is frozen in
    whatever state they left it — which is the handover problem this feature exists to solve,
    reintroduced one level up. An admin grant has an administrator behind it who is still here.

    AND INCLUDING A PROFESSOR, who outranks every designer in ``ROLE_RANK`` and is still not an
    admin. ``require_admin`` is ``is_admin`` — a two-member set — so the ladder has no say here
    either. Asserted on the SERVER rather than left to the clients, because
    ``android/…/ui/designworkshop/WorkshopViewersScreen.kt`` and
    ``frontend/components/settings/DesignWorkshopViewersPanel.tsx`` both hide their controls from
    these accounts, and a UI guard over an open endpoint has shipped as a security bug in this
    repository twice — both times surviving review because nobody opened the app as the role that
    should have been refused. All three routes are driven here as each of them.
    """
    workshop_id = _make_workshop(world, f"Ikat, admin only {slug}")

    assert client.get(
        f"/api/design-workshops/{workshop_id}/viewers", headers=_headers(world, slug)
    ).status_code == 403
    assert client.get(
        "/api/design-workshops/eligible-viewers", headers=_headers(world, slug)
    ).status_code == 403
    assert client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": []},
        headers=_headers(world, slug),
    ).status_code == 403


# ------------------------------------------------------------------------------------------
# The questionnaire attached to the workshop, which the grant must reach as well
#
# A grant that admits a co-designer to the stages and not to the survey instrument those stages
# refer to is half a grant. Stage 7 captures the questionnaire and stage 8 the responses; if the
# questionnaire list is empty for the colleague, the two halves of one piece of fieldwork disagree
# about who is working on it, and the colleague concludes the form was never uploaded.
# ------------------------------------------------------------------------------------------


def _make_questionnaire(world: dict[str, Any], workshop_id: str | None, title: str) -> str:
    """A questionnaire owned by ``creator``, optionally attached to a workshop."""
    body: dict[str, Any] = {"title": title}
    if workshop_id:
        body["designWorkshopId"] = workshop_id
    response = world["client"].post(
        "/api/questionnaires", json=body, headers=_headers(world, "creator")
    )
    assert response.status_code == 201, response.text
    return response.json()["id"]


def test_a_granted_colleague_sees_the_workshops_questionnaire_in_the_list(world, client):
    workshop_id = _make_workshop(world, "Questionnaire visibility")
    questionnaire_id = _make_questionnaire(world, workshop_id, "Attached instrument")

    before = client.get("/api/questionnaires", headers=_headers(world, "colleague"))
    assert before.status_code == 200, before.text
    assert questionnaire_id not in {row["id"] for row in before.json()["items"]}

    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    after = client.get("/api/questionnaires", headers=_headers(world, "colleague"))
    assert after.status_code == 200, after.text
    assert questionnaire_id in {row["id"] for row in after.json()["items"]}, (
        "the colleague can open the workshop but its questionnaire is invisible to them"
    )


def test_the_questionnaire_scope_survives_a_search(world, client):
    """The same trap the workshop list had: both clauses want ``where["OR"]``.

    Assigning the visibility filter there would be silently overwritten by the search box — the
    colleague would see the questionnaire only while the box was empty.
    """
    workshop_id = _make_workshop(world, "Questionnaire search scope")
    questionnaire_id = _make_questionnaire(world, workshop_id, "Barpali dyers instrument")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    found = client.get(
        "/api/questionnaires", params={"search": "Barpali"}, headers=_headers(world, "colleague")
    )
    assert found.status_code == 200, found.text
    assert questionnaire_id in {row["id"] for row in found.json()["items"]}

    # And the search still excludes what it should, rather than the filter having widened it.
    missed = client.get(
        "/api/questionnaires", params={"search": "Kanchipuram"}, headers=_headers(world, "colleague")
    )
    assert questionnaire_id not in {row["id"] for row in missed.json()["items"]}


def test_an_unattached_questionnaire_stays_the_owners_alone(world, client):
    """The grant reaches the workshop's fieldwork, not the whole of a colleague's filing cabinet."""
    workshop_id = _make_workshop(world, "Unattached questionnaire")
    loose_id = _make_questionnaire(world, None, "Nothing to do with the workshop")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    listed = client.get("/api/questionnaires", headers=_headers(world, "colleague"))
    assert loose_id not in {row["id"] for row in listed.json()["items"]}


def test_an_ungranted_designer_still_cannot_see_the_questionnaire(world, client):
    workshop_id = _make_workshop(world, "Questionnaire refusal")
    questionnaire_id = _make_questionnaire(world, workshop_id, "Not yours")

    listed = client.get("/api/questionnaires", headers=_headers(world, "outsider"))
    assert questionnaire_id not in {row["id"] for row in listed.json()["items"]}


def test_mine_only_still_means_mine(world, client):
    """``mineOnly`` is the one place the widened scope must NOT apply.

    An admin narrowing to their own work, or a designer asking what they uploaded, is asking about
    authorship — not about what they may read. Folding a colleague's attached form in would answer
    a different question from the one the parameter names.
    """
    workshop_id = _make_workshop(world, "Mine only")
    questionnaire_id = _make_questionnaire(world, workshop_id, "Creator's own")
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    mine = client.get(
        "/api/questionnaires", params={"mineOnly": "true"}, headers=_headers(world, "colleague")
    )
    assert mine.status_code == 200, mine.text
    assert questionnaire_id not in {row["id"] for row in mine.json()["items"]}


def test_a_granted_colleague_may_read_the_sittings_and_download_the_workbook(world, client):
    """Both doors, together, because they carry the same data.

    Letting the colleague read the answers on the page while refusing them the .xlsx of the same
    answers would be a distinction the data cannot support — and one they would route around by
    copying the page.
    """
    workshop_id = _make_workshop(world, "Sittings and workbook")
    questionnaire_id = _make_questionnaire(world, workshop_id, "With a sitting")
    started = client.post(
        f"/api/questionnaires/{questionnaire_id}/entries",
        json={"respondentName": "Rekha Meher"},
        headers=_headers(world, "creator"),
    )
    assert started.status_code == 201, started.text

    refused = client.get(
        f"/api/questionnaires/{questionnaire_id}/xlsx", headers=_headers(world, "colleague")
    )
    assert refused.status_code == 403

    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    read = client.get(
        f"/api/questionnaires/{questionnaire_id}", headers=_headers(world, "colleague")
    )
    assert read.status_code == 200, read.text
    names = [entry.get("respondentName") for entry in read.json().get("entries", [])]
    assert "Rekha Meher" in names, "the sitting is hidden from a designer working on the workshop"

    allowed = client.get(
        f"/api/questionnaires/{questionnaire_id}/xlsx", headers=_headers(world, "colleague")
    )
    assert allowed.status_code == 200, allowed.text


def test_deleting_the_workshop_ends_the_grants_reach_into_its_questionnaire(world, client):
    """A grant is access TO A WORKSHOP, so it cannot outlive the workshop.

    THE ASYMMETRY THIS PINS. Every other clause that reads a grant already excludes a soft-deleted
    workshop: ``load_workshop_or_404`` 404s it for everyone but an admin, and both grant clauses of
    ``_visible_questionnaire_where`` carry ``deletedAt: None`` so the row leaves the colleague's
    LIST. ``_works_on_this_questionnaires_workshop`` decides the same question for the direct read
    and the workbook, and its grant branch did not ask — so after a delete the colleague was refused
    the workshop, refused the stages and shown nothing in any list, while
    ``GET /questionnaires/{id}`` still handed over every sitting and ``/xlsx`` still served the
    lossless workbook of respondents' names and answers.

    That is exactly the shape ``read_questionnaire``'s own docstring calls out as the bug it exists
    to close — "their own list view showed total: 0 for the same questionnaire, so the app said it
    did not exist and the API handed it over" — reintroduced one delete later.

    The creator branch beside it already checked ``deletedAt``, so the two halves of one helper
    disagreed: a colleague who was GRANTED the workshop outlived the designer who CREATED it.
    """
    workshop_id = _make_workshop(world, "Deleted workshop, live questionnaire")
    questionnaire_id = _make_questionnaire(world, workshop_id, "Survives its workshop")
    started = client.post(
        f"/api/questionnaires/{questionnaire_id}/entries",
        json={"respondentName": "Ramesh Sahu"},
        headers=_headers(world, "creator"),
    )
    assert started.status_code == 201, started.text
    assert _grant(world, workshop_id, ["colleague"]).status_code == 200

    # The grant works while the workshop is live — otherwise the assertions below prove nothing.
    live = client.get(f"/api/questionnaires/{questionnaire_id}", headers=_headers(world, "colleague"))
    assert "Ramesh Sahu" in [e.get("respondentName") for e in live.json().get("entries", [])]
    assert (
        client.get(
            f"/api/questionnaires/{questionnaire_id}/xlsx", headers=_headers(world, "colleague")
        ).status_code
        == 200
    )

    deleted = client.delete(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "admin")
    )
    assert deleted.status_code == 204, deleted.text

    # The control: the workshop itself is gone for them, which is the behaviour that was already
    # right and that the questionnaire half has to agree with.
    gone = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "colleague"))
    assert gone.status_code == 404

    read = client.get(f"/api/questionnaires/{questionnaire_id}", headers=_headers(world, "colleague"))
    assert read.status_code == 200, "the FORM stays readable by any designer — that is the policy"
    names = [entry.get("respondentName") for entry in read.json().get("entries", [])]
    assert "Ramesh Sahu" not in names, (
        "a viewer of a DELETED workshop was still handed the respondent's name"
    )

    workbook = client.get(
        f"/api/questionnaires/{questionnaire_id}/xlsx", headers=_headers(world, "colleague")
    )
    assert workbook.status_code == 403, (
        "the lossless workbook of every sitting outlived the workshop the grant was about"
    )


def test_an_outsider_still_cannot_read_the_sittings_or_the_workbook(world, client):
    workshop_id = _make_workshop(world, "Sittings refusal")
    questionnaire_id = _make_questionnaire(world, workshop_id, "Outsider refusal")
    started = client.post(
        f"/api/questionnaires/{questionnaire_id}/entries",
        json={"respondentName": "Sunil Bhoi"},
        headers=_headers(world, "creator"),
    )
    assert started.status_code == 201, started.text

    read = client.get(
        f"/api/questionnaires/{questionnaire_id}", headers=_headers(world, "outsider")
    )
    assert read.status_code == 200, "the FORM stays readable by any designer — that is the policy"
    names = [entry.get("respondentName") for entry in read.json().get("entries", [])]
    assert "Sunil Bhoi" not in names, "an outsider was handed the respondent's name"

    workbook = client.get(
        f"/api/questionnaires/{questionnaire_id}/xlsx", headers=_headers(world, "outsider")
    )
    assert workbook.status_code == 403
