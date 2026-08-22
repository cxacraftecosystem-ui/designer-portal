"""A designer can ask to be let into a design workshop — and cannot use the asking to enumerate one.

WHAT THIS MODULE IS FOR. Every route that can put somebody on a design workshop is admin-only, and
that refusal is deliberate and is not weakened by anything here. What had no answer was the floor
underneath it: a designer holding the card a colleague just printed had NO ENDPOINT through which to
ask, so the most the clients could honestly say was to send the code to an admin and ask them — an
instruction with no request behind it anywhere. These tests pin the queue that makes the ask real,
and the four properties it would be easy to ship it without.

**ONE. THE ASK MUST NOT BE AN EXISTENCE ORACLE, AND THAT IS THE HARD ONE.** Every other read in this
repository answers 404 for a record the caller may not have, so nobody can enumerate it by asking
about random ids. This route CANNOT do that: its entire purpose is to be called by somebody who may
not see the record, so "404 unless you can see it" refuses every legitimate ask and "201 if it
exists, 404 if it does not" is the oracle wearing a friendly name. The answer is therefore UNIFORM —
the same status and the same bytes for a real workshop, a soft-deleted one, an id that names
nothing, and an id that cannot even be stored — and the tests below compare the responses to each
other rather than to a literal, so a future edit that improves the copy for one branch fails here.

**TWO. IDEMPOTENT, BECAUSE THE ASK ARRIVES TWICE.** A request typed in a courtyard reaches the
server whenever the handset next finds signal and a flaky link retries. Two identical posts must be
one row, and the second must not restamp ``createdAt`` — the queue is ordered oldest-first, so
restamping is the anti-spam rule inverted into a way to jump the queue. A REFUSED request must not
be reopened by asking again either, which is the sibling table's rule ("a user cannot silently
re-request their way around a refusal") and the opposite of what ``POST /workshops/access-requests``
does to a DENIED row — see the service module for why the two differ on purpose. There is exactly
ONE repeat ask that is not a no-op, and it has a test of its own: a GRANT whose access an admin has
since removed is reopened, and it is the one place ``createdAt`` moves. It is also the only branch of
the ask that issues an UPDATE, so an untested one there would be a write nothing measures.

**THREE. GRANTING GOES THROUGH THE VIEWER MECHANISM AND NOWHERE ELSE.** The tests assert the
outcome, not the call: after a grant the requester appears on the workshop's OWN viewers screen
(``GET /design-workshops/{id}/viewers``) and can open the workshop. And granting somebody who can
never hold a viewer row is refused with that module's own 422 — a refusal no private insert here
could produce, which is what makes it the assertion that the reuse is real.

**FOUR. THE ROLES.** A designer may ask and may not list, decide, or see anybody else's request.
Refusing somebody who can already open the workshop is a 409 rather than a DENIED row over access
that remains — "we never built the UI for it" is not enforcement.

The scanned code is EVIDENCE and never authorisation, and
``test_a_valid_code_is_not_what_lets_the_request_through`` says so: an ask with no code at all is
filed exactly as readily. Anybody can compute a valid check — the algorithm ships to the browser —
so a test that treated a good code as a gate would be pinning a security property that does not
exist. Whether the SERVER computes the same check as the browser is a different question and needs
no database, so it lives in ``test_workshop_code_check_port.py``, which is ungated and therefore
actually runs in CI. The ORDER of the refusals — that a bad code is refused before any database read,
which is what makes saying so safe — lives in ``test_design_workshop_access_gate.py``, for the same
reason and because a test over a real database cannot see it.

── HOW THESE TESTS READ THE DATABASE, WHICH IS: THEY DO NOT ────────────────────────────────────
Every assertion goes through HTTP, and the only direct ``db`` use in this file is inside the
module-scoped fixture. That is not a style preference, it is the rule ``test_design_workshop_viewers``
and ``test_design_ratings_api`` both follow and both explain: the Prisma client is shared with the
running app and its connection is bound to the ``TestClient``'s event loop, so awaiting a query from
a test's own loop is cross-loop use that fails intermittently rather than honestly. Reading rows back
through ``GET /design-workshop-access/requests?statusFilter=ALL`` is also the better assertion — it
is the surface an admin actually works from, so a payload that is right in the table and wrong on the
wire cannot pass.

Postgres is required. The module skips itself when ``DATABASE_URL`` does not point at a local
database.

    cd backend && DATABASE_URL=postgresql://postgres:postgres@127.0.0.1:55442/design_workshop \\
        .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

import os
import uuid
from datetime import datetime
from typing import Any

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services.design_workshop_access import code_check

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

PASSWORD = "dw-access-request-password"

#: slug -> (role, display name).
#:
#: ``creator`` IS AN ADMIN because only an admin may START a design workshop
#: (``can_create_design_workshops``), and every workshop here is made through the real endpoint so
#: that the create gate is exercised on the way past. That is the same choice — and the same cost —
#: ``test_design_workshop_viewers`` documents: the ``createdById`` arm of the access rule can no
#: longer be isolated from the admin arm through the API, because the creator satisfies both.
#:
#: ``requester`` and ``second`` are BOTH empanelled designers, and the second one is not a spare: it
#: is the co-designer already seated on a workshop that
#: ``test_granting_leaves_the_viewers_already_on_the_workshop_alone`` needs, and no other account
#: here can be it.
ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("admin", "ADMIN", "Queue Admin"),
    ("creator", "ADMIN", "Workshop Creator"),
    ("requester", "DESIGNER", "Asking Designer"),
    ("second", "DESIGNER", "Other Asking Designer"),
    # AN ACCOUNT THAT MAY ASK AND MAY NOT BE GRANTED. ``DESIGN_WORKSHOP_ROLES`` is a SET — Designer,
    # Admin, Master Admin — so a researcher can never hold a viewer row, and the ask route
    # deliberately does not check the role: the rule lives in ``replace_viewers``, where it reads
    # both rosters and produces a sentence naming the screen that fixes it. This account is what
    # proves the grant really goes through that mechanism rather than around it.
    ("researcher", "RESEARCHER", "Ordinary Researcher"),
)

#: Everyone who needs an ACTIVE ``DesignerRoster`` row. The designers do, because
#: ``replace_viewers`` refuses a viewer row for a designer the roster no longer admits and the GRANT
#: path goes straight through it — without these rows the grant tests would fail with a 422 about
#: empanelment and prove nothing about the queue.
ROSTERED = ("requester", "second")


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    """Every account the module needs, created before the app starts.

    THE ONLY PLACE IN THIS FILE THAT TOUCHES ``db``, and the reason is in the module docstring: the
    client is shared with the running app and bound to the ``TestClient``'s loop, so this work has to
    happen either side of it rather than inside a test.

    Every address carries a per-run stamp, because ``DesignerRoster.email`` is UNIQUE and fixed
    addresses would pass on a clean database and fail on the second run of the suite.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"dwaccess-{slug}-{stamp}@example.org"

    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role, name in ACCOUNTS:
            people[slug] = await db.user.create(
                data={
                    "email": address(slug),
                    "name": name,
                    "role": role,
                    "passwordHash": hash_password(PASSWORD),
                }
            )
        for slug in ROSTERED:
            await db.designerroster.create(
                data={
                    "email": address(slug),
                    "fullName": f"Roster row for {slug}",
                    "institution": "Directorate of Handicrafts",
                    "isActive": True,
                    "addedById": people["admin"].id,
                }
            )
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {"client": client, "people": people, "address": address, "stamp": stamp}

    # ── THE ONE TEARDOWN IN THIS FAMILY OF MODULES, AND IT IS NOT TIDINESS ──────────────────────
    # ``test_design_workshop_viewers`` leaves its rows behind and is right to: nothing it asserts
    # depends on how many rows the tables hold. This module has assertions that do — every row is
    # read back through the admin queue, which is capped at ``QUEUE_LIMIT`` and ordered OLDEST
    # FIRST. Left to accumulate at roughly a dozen rows a run, the cap is reached after a few dozen
    # runs, and the first symptom is not a clear failure: it is THIS run's rows that fall off the
    # end, so the module starts failing with "the request I just filed is not in the list".
    #
    # SCOPED TO THIS RUN'S ACCOUNTS. Deleting the table would also delete a developer's own
    # experiment, and a test that tidies up after other people is a test that destroys evidence.
    # The requests go first because the viewer rows are what some of them granted.
    await db.connect()
    try:
        ours = [person.id for person in people.values()]
        await db.designworkshopaccessrequest.delete_many(where={"requestedById": {"in": ours}})
        await db.designworkshopviewer.delete_many(where={"userId": {"in": ours}})
    finally:
        await db.disconnect()


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    """A bearer token for one of the fixture's accounts.

    Minted directly rather than obtained by signing in: what is under test is the queue, and a
    helper that logged in first would make every assertion here depend on the sign-in gate too.
    """
    return {"Authorization": f"Bearer {create_access_token(world['people'][slug].id)}"}


def _make_workshop(world: dict[str, Any], title: str) -> str:
    """A fresh workshop owned by ``creator``, made through the API the way workshops are made.

    One per test that files a request. Sharing one workshop would make the module order-dependent
    through the (workshop, requester) unique index — the second test's "first ask" would be the
    first test's replay — and the failure mode of that is a suite that passes alone and fails in CI.
    """
    response = world["client"].post(
        "/api/design-workshops",
        json={"title": title},
        headers=_headers(world, "creator"),
    )
    assert response.status_code == 201, response.text
    return response.json()["id"]


def _code_for(workshop_id: str) -> str:
    """The card a designer would scan for this workshop: ``DPW1:G:<ID>:<check>``.

    Built with the SERVER's ``code_check`` rather than a second copy of the algorithm, which is safe
    only because ``test_workshop_code_check_port`` pins that function against vectors the browser
    produced. Without that module this helper would be checking the implementation against itself.
    """
    prefix = f"DPW1:G:{workshop_id.upper()}"
    return f"{prefix}:{code_check(prefix)}"


def _ask(world: dict[str, Any], slug: str, workshop_id: str, **body: Any):
    """File one request as ``slug``. Returns the raw response so a test can compare bytes."""
    return world["client"].post(
        "/api/design-workshop-access/requests",
        json={"workshopId": workshop_id, **body},
        headers=_headers(world, slug),
    )


def _queue(world: dict[str, Any], status_filter: str = "ALL") -> dict[str, Any]:
    """The admin queue, read as an admin. The only way this module reads a request row back."""
    response = world["client"].get(
        f"/api/design-workshop-access/requests?statusFilter={status_filter}",
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    return response.json()


def _rows_for(world: dict[str, Any], workshop_id: str) -> list[dict[str, Any]]:
    """Every request in the queue naming this workshop, in queue order."""
    return [row for row in _queue(world)["requests"] if row["workshop"]["id"] == workshop_id]


def _one_row(world: dict[str, Any], workshop_id: str) -> dict[str, Any]:
    rows = _rows_for(world, workshop_id)
    assert len(rows) == 1, rows
    return rows[0]


def _without_access_flag(row: dict[str, Any]) -> dict[str, Any]:
    """One queue row minus ``requesterHasAccess``, for comparing a STORED row against itself.

    Every other key in the payload is a column; that one is computed per read from the CURRENT access
    (creator column, admin role, viewer row), so it moves when an admin edits the roster while the
    row itself has not been written to at all. Comparing whole payloads across such an edit would
    read as "the request changed" when nothing did.
    """
    return {key: value for key, value in row.items() if key != "requesterHasAccess"}


def _viewer_ids(world: dict[str, Any], workshop_id: str) -> set[str]:
    """Who is on the workshop's viewers screen — the surface an admin manages the team from."""
    response = world["client"].get(
        f"/api/design-workshops/{workshop_id}/viewers", headers=_headers(world, "admin")
    )
    assert response.status_code == 200, response.text
    return {viewer["userId"] for viewer in response.json()["viewers"]}


def _decide(world: dict[str, Any], request_id: str, decision: str, note: str | None = None):
    body: dict[str, Any] = {"status": decision}
    if note is not None:
        body["note"] = note
    return world["client"].post(
        f"/api/design-workshop-access/requests/{request_id}/decide",
        json=body,
        headers=_headers(world, "admin"),
    )


# --------------------------------------------------------------------------------------
# Filing an ask
# --------------------------------------------------------------------------------------


def test_a_designer_can_ask_to_join_a_workshop_they_scanned(client, world):
    """The ask lands as one PENDING row carrying the code that was scanned.

    ``source`` is SCAN and ``scannedCode`` holds the CANONICAL form, not the bytes that were posted:
    an admin comparing a queue row against a card must be reading one spelling, not a lower-cased
    paste in one row and a bare scan in the next. The posted value is deliberately lower-cased and
    spaced the way ``formatWorkshopCodeForPrint`` prints it, which is what somebody typing off a
    card actually produces.
    """
    workshop_id = _make_workshop(world, "Scanned join request")
    code = _code_for(workshop_id)
    typed = " ".join(code[index : index + 4] for index in range(0, len(code), 4)).lower()

    response = _ask(world, "requester", workshop_id, scannedCode=typed, note="Rekha asked me")
    assert response.status_code == 202, response.text

    row = _one_row(world, workshop_id)
    assert row["requestedBy"]["id"] == world["people"]["requester"].id
    assert row["status"] == "PENDING"
    assert row["source"] == "SCAN"
    assert row["scannedCode"] == code
    assert row["note"] == "Rekha asked me"
    assert row["requesterHasAccess"] is False
    assert row["decidedBy"] is None


def test_a_valid_code_is_not_what_lets_the_request_through(client, world):
    """An ask with NO code is filed just as readily, and is recorded as MANUAL.

    THIS TEST EXISTS TO STOP THE CODE BEING MISTAKEN FOR A GATE. Its four check characters are a
    typo detector whose algorithm ships to every browser — ``workshopCodes.ts`` says so in terms —
    so treating a valid code as proof of anything would be a security property that does not exist,
    and building the manual path out would strand the designer typing an identifier off a card under
    a tin roof. What the code buys is the ``source`` column: an admin can see which asks came from a
    real card and which did not, and ask the difference.
    """
    workshop_id = _make_workshop(world, "Manual join request")
    assert _ask(world, "requester", workshop_id).status_code == 202

    row = _one_row(world, workshop_id)
    assert row["source"] == "MANUAL"
    assert row["scannedCode"] is None


def test_the_upper_case_identifier_a_card_shows_is_still_found(client, world):
    """``workshopId`` POSTED IN UPPER CASE FILES A ROW, on both the scanned and the typed path.

    THIS IS THE CASE A CARD ACTUALLY PRODUCES, which is why it is worth its own test rather than
    being folded into the two above. ``workshopCodes.ts`` encodes the payload as
    ``${NAMESPACE}${VERSION}:${letter}:${id.toUpperCase()}``, so the identifier a designer READS off
    a printed card is upper case — and a client that posts what it read, or a person told the
    identifier by the colleague beside them, sends it that way. It shipped broken once: the id was
    folded when the body was compared against the code and NOT folded for the workshop lookup, so
    the ask fell into the "names nothing" no-op.

    IT IS INVISIBLE FROM OUTSIDE, which is the reason to pin it here. Every branch of this route
    answers identical bytes by design, so the dropped ask still returned 202 and the sentence saying
    an administrator could now see it — with nothing written, nothing logged, and no way for the
    requester, the admin or the log to tell. Only the queue shows the difference.
    """
    scanned_id = _make_workshop(world, "Upper-case id, scanned")
    typed_id = _make_workshop(world, "Upper-case id, typed off the card")

    scanned = _ask(
        world, "requester", scanned_id.upper(), scannedCode=_code_for(scanned_id)
    )
    assert scanned.status_code == 202, scanned.text
    # THE ROW POINTS AT THE REAL WORKSHOP: ``_rows_for`` matches on the lower-case id the create
    # returned, so finding it here is the assertion that the lookup folded rather than missed.
    assert _one_row(world, scanned_id)["source"] == "SCAN"

    typed = _ask(world, "requester", typed_id.upper())
    assert typed.status_code == 202, typed.text
    assert _one_row(world, typed_id)["source"] == "MANUAL"


def test_asking_twice_files_one_request_and_does_not_move_it_up_the_queue(client, world):
    """The offline replay. Two identical posts are ONE row, with the ORIGINAL ``createdAt``.

    Both halves matter and only the first is obvious. One row is what stops a retrying handset
    filling an admin's queue with the same card. The frozen clock is what stops the retry acting as
    a bump: the queue is ordered oldest-first, so restamping would push everybody who asked earlier
    down it — the anti-spam rule inverted into a way to jump the queue.
    """
    workshop_id = _make_workshop(world, "Replayed join request")
    code = _code_for(workshop_id)

    first = _ask(world, "requester", workshop_id, scannedCode=code)
    assert first.status_code == 202, first.text
    before = _one_row(world, workshop_id)

    second = _ask(world, "requester", workshop_id, scannedCode=code)
    assert second.status_code == 202, second.text
    # THE SAME BYTES, not merely the same status: the replay must be indistinguishable from the
    # first ask, or the response itself says whether a row already existed.
    assert second.json() == first.json()

    after = _one_row(world, workshop_id)
    assert after["id"] == before["id"]
    assert after["createdAt"] == before["createdAt"]


def test_nothing_is_filed_for_somebody_who_can_already_open_the_workshop(client, world):
    """The creator asking about their own workshop is accepted and recorded nowhere.

    The ordinary way this happens is a race rather than a mistake — an offline ask syncing a week
    after an admin answered it in person, or a designer scanning while the admin is granting them on
    another screen. An admin working the queue must not be handed decisions that are already made.
    The ANSWER is still the uniform one, because a different answer here would tell any caller
    whether they hold access to an id, which is the same oracle in a smaller room.
    """
    workshop_id = _make_workshop(world, "Creator asks about their own")
    response = _ask(world, "creator", workshop_id, scannedCode=_code_for(workshop_id))
    assert response.status_code == 202, response.text
    assert _rows_for(world, workshop_id) == []


# --------------------------------------------------------------------------------------
# The enumeration refusal
# --------------------------------------------------------------------------------------


def test_asking_about_an_id_that_names_nothing_is_the_same_answer_as_asking_about_a_real_one(
    client, world
):
    """THE ENUMERATION TEST. Four asks, four identical answers, and only one row anywhere.

    The four ids are a real workshop, a well-formed cuid that names nothing, a soft-deleted
    workshop, and an id carrying a NUL byte. A caller must not be able to tell them apart from the
    response — not by status, not by body, not by the shape of a refusal — because being callable by
    somebody with no access is the whole point of this route, and an informative answer would turn
    it into a way to walk the repository one id at a time.

    ASSERTED AGAINST EACH OTHER RATHER THAN AGAINST A LITERAL, deliberately. Pinning the exact
    sentence would pass just as well if all four branches were changed together, and would fail for
    a copy edit that broke nothing. What must hold is that the four cannot be told apart.

    THE NUL BYTE IS NOT PADDING. An id Postgres cannot hold reaches the driver as a ``DataError``
    and comes back as a bare 500 with a stack trace in the log — a distinguishable answer AND an
    error log any authenticated caller could fill at will. It has to take the same silent exit as
    every other id that resolves to nothing.
    """
    real_id = _make_workshop(world, "Real workshop for the oracle test")
    deleted_id = _make_workshop(world, "Deleted workshop for the oracle test")
    deleted = client.delete(
        f"/api/design-workshops/{deleted_id}", headers=_headers(world, "creator")
    )
    assert deleted.status_code in (200, 204), deleted.text

    invented_id = f"cmsdoesnotexist{uuid.uuid4().hex[:10]}"
    unstorable_id = f"cms\x00{uuid.uuid4().hex[:12]}"

    answers = [
        (response.status_code, response.json())
        for response in (
            _ask(world, "second", real_id),
            _ask(world, "second", invented_id),
            _ask(world, "second", deleted_id),
            _ask(world, "second", unstorable_id),
        )
    ]
    assert answers[0][0] == 202, answers[0]
    assert len({repr(answer) for answer in answers}) == 1, answers

    # And only the real one left a row behind, which is the other half of the promise: the uniform
    # answer is not achieved by filing junk for ids that name nothing.
    assert len(_rows_for(world, real_id)) == 1
    assert _rows_for(world, invented_id) == []
    assert _rows_for(world, deleted_id) == []


def test_a_code_that_does_not_check_out_is_refused_out_loud(client, world):
    """A malformed or mismatched code is a 422 — and that is not a hole in the rule above.

    These refusals depend on the REQUEST BODY alone: a wrong check character, a code for a different
    kind of record, a code naming a different workshop from the id beside it, something that is not
    one of our codes at all. None of them reads the database — which
    ``test_design_workshop_access_gate`` asserts directly, with a tripwire in place of ``db`` — so
    none can say whether an id exists, and swallowing them would leave a designer re-scanning a card
    that will never work.
    """
    workshop_id = _make_workshop(world, "Refused codes")
    good = _code_for(workshop_id)

    broken = good[:-1] + ("Z" if good[-1] != "Z" else "Y")
    artisan_prefix = f"DPW1:A:{workshop_id.upper()}"
    other_type = f"{artisan_prefix}:{code_check(artisan_prefix)}"
    elsewhere = _code_for(_make_workshop(world, "A different workshop"))

    for code in (broken, other_type, elsewhere, "https://example.org/not-a-code"):
        response = _ask(world, "requester", workshop_id, scannedCode=code)
        assert response.status_code == 422, (code, response.text)

    assert _rows_for(world, workshop_id) == []


# --------------------------------------------------------------------------------------
# The roles
# --------------------------------------------------------------------------------------


def test_a_designer_cannot_read_or_decide_the_queue(client, world):
    """Asking is open; the queue is not. A designer gets 403 from both admin routes.

    The queue names every account that has asked for anything, across every workshop, which is a
    directory of who is trying to get where. Nothing about being allowed to ASK implies being
    allowed to read that.
    """
    workshop_id = _make_workshop(world, "Roles on the queue")
    assert _ask(world, "requester", workshop_id).status_code == 202
    request_id = _one_row(world, workshop_id)["id"]

    listed = client.get(
        "/api/design-workshop-access/requests", headers=_headers(world, "requester")
    )
    assert listed.status_code == 403, listed.text

    decided = client.post(
        f"/api/design-workshop-access/requests/{request_id}/decide",
        json={"status": "GRANTED"},
        headers=_headers(world, "requester"),
    )
    assert decided.status_code == 403, decided.text

    # And the row is untouched: a refused call must not be a half-applied one.
    assert _one_row(world, workshop_id)["status"] == "PENDING"
    assert _viewer_ids(world, workshop_id) == set()


def test_the_pending_filter_is_the_default_and_a_decided_row_leaves_it(client, world):
    """PENDING by default, and ``statusFilter=ALL`` is the audit view.

    The default is what makes the queue a queue: an admin opening it sees what is owed and not the
    history of everything ever asked. Both halves are asserted together because the failure that
    matters is the pair coming apart — a decided row still sitting in the default view is a decision
    an admin makes twice, and a row absent from ALL is a refusal nobody can audit.
    """
    workshop_id = _make_workshop(world, "Pending then decided")
    assert _ask(world, "requester", workshop_id).status_code == 202
    request_id = _one_row(world, workshop_id)["id"]

    pending = _queue(world, "PENDING")["requests"]
    assert any(row["id"] == request_id for row in pending)

    assert _decide(world, request_id, "DENIED").status_code == 200

    pending = _queue(world, "PENDING")["requests"]
    assert all(row["id"] != request_id for row in pending)
    everything = _queue(world, "ALL")["requests"]
    assert any(row["id"] == request_id and row["status"] == "DENIED" for row in everything)


def test_the_queue_says_when_it_was_not_truncated(client, world):
    """``truncated`` is on the wire and is false on a queue this size.

    It is asserted because both clients must render it: a queue silently missing its oldest entries
    is people waiting for access nobody can see they asked for, which is the failure this whole
    feature exists to end, reintroduced by a limit. A flag nothing reads is a flag that goes wrong
    unnoticed.
    """
    assert _queue(world, "ALL")["truncated"] is False


# --------------------------------------------------------------------------------------
# Deciding
# --------------------------------------------------------------------------------------


def test_granting_a_request_puts_the_designer_on_the_workshop_for_real(client, world):
    """A grant is a row on the workshop's viewers screen and a workshop the designer can open.

    ASSERTED THROUGH THE DOOR AND THROUGH THE ADMIN'S OWN SCREEN, not through this table's
    ``status`` column. A second way to become a viewer — a private insert, or an access check that
    consulted this queue — would satisfy a test that only read the status back, and would leave two
    places to look when somebody has access they should not. Before the grant the designer is
    refused with the same 404 a missing workshop gets; after it they read the record.
    """
    workshop_id = _make_workshop(world, "Granted join request")
    assert _ask(world, "requester", workshop_id, scannedCode=_code_for(workshop_id)).status_code == 202
    request_id = _one_row(world, workshop_id)["id"]

    before = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "requester")
    )
    assert before.status_code == 404, before.text

    decided = _decide(world, request_id, "GRANTED", "second designer on this cluster")
    assert decided.status_code == 200, decided.text
    payload = decided.json()
    assert payload["status"] == "GRANTED"
    assert payload["requesterHasAccess"] is True
    assert payload["decidedBy"]["id"] == world["people"]["admin"].id
    assert payload["decisionNote"] == "second designer on this cluster"

    # THE VIEWER MECHANISM, read from the screen that owns it.
    assert _viewer_ids(world, workshop_id) == {world["people"]["requester"].id}

    after = client.get(f"/api/design-workshops/{workshop_id}", headers=_headers(world, "requester"))
    assert after.status_code == 200, after.text


def test_granting_leaves_the_viewers_already_on_the_workshop_alone(client, world):
    """A grant ADDS. It must not be a whole-set replace that quietly removes everybody else.

    ``replace_viewers`` takes the COMPLETE set, so the one way to get this wrong is to hand it the
    requester alone — which reads as "grant this person" and means "and revoke the rest of the
    team". A workshop with a co-designer already on it is the only fixture that can catch it.
    """
    workshop_id = _make_workshop(world, "Grant beside an existing viewer")
    seated = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [world["people"]["second"].id]},
        headers=_headers(world, "admin"),
    )
    assert seated.status_code == 200, seated.text

    assert _ask(world, "requester", workshop_id).status_code == 202
    request_id = _one_row(world, workshop_id)["id"]
    assert _decide(world, request_id, "GRANTED").status_code == 200

    assert _viewer_ids(world, workshop_id) == {
        world["people"]["second"].id,
        world["people"]["requester"].id,
    }


def test_granting_an_account_that_cannot_hold_a_viewer_row_is_refused_by_the_mechanism(
    client, world
):
    """A researcher's request is filed, and granting it is refused with the viewers screen's own 422.

    THIS IS THE TEST THAT THE GRANT REALLY GOES THROUGH ``replace_viewers``. Everything else about a
    grant — a viewer row, a workshop the designer can open — could also be produced by a private
    insert here. This could not: the refusal, its status code and its sentence all belong to that
    module, which reads the eligibility SET (a researcher outranks nobody into it) and both rosters.
    If somebody ever replaces the call with an insert, this is what goes red, and the failure will
    read "a researcher was given access to a design workshop".

    THE ASK ITSELF IS ACCEPTED, deliberately. The role is not checked at the door, because the one
    place that rule can be enforced usefully is where it produces a message an admin can act on —
    and refusing quietly at the door would leave an admin unable to see that somebody had asked at
    all, which is the state this whole feature exists to end.
    """
    workshop_id = _make_workshop(world, "Ineligible requester")
    assert _ask(world, "researcher", workshop_id).status_code == 202
    request_id = _one_row(world, workshop_id)["id"]

    granted = _decide(world, request_id, "GRANTED")
    assert granted.status_code == 422, granted.text
    assert "RESEARCHER" in granted.json()["detail"]

    # NOTHING WAS HALF-APPLIED: no viewer row, and the request is still PENDING for an admin to see.
    assert _viewer_ids(world, workshop_id) == set()
    assert _one_row(world, workshop_id)["status"] == "PENDING"


def test_a_refusal_stands_and_asking_again_does_not_reopen_it(client, world):
    """DENIED, no access, and a second scan changes nothing.

    The refusal is KEPT rather than deleted — the sibling table's rule, "a user cannot silently
    re-request their way around a refusal" — and the repeat ask is the way somebody would try. This
    is deliberately the OPPOSITE of what ``POST /workshops/access-requests`` does to a DENIED row:
    there a re-request is a new ask and the queue re-ranks it; here it would put the same card back
    in an admin's queue every time somebody pointed a phone at it. Reversing a refusal is an admin
    action, and the next test does it.
    """
    workshop_id = _make_workshop(world, "Refused join request")
    code = _code_for(workshop_id)
    assert _ask(world, "requester", workshop_id, scannedCode=code).status_code == 202
    request_id = _one_row(world, workshop_id)["id"]

    refused = _decide(world, request_id, "DENIED", "not on this cluster")
    assert refused.status_code == 200, refused.text
    assert refused.json()["status"] == "DENIED"

    assert _viewer_ids(world, workshop_id) == set()
    still_out = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "requester")
    )
    assert still_out.status_code == 404, still_out.text

    again = _ask(world, "requester", workshop_id, scannedCode=code)
    assert again.status_code == 202, again.text
    row = _one_row(world, workshop_id)
    assert row["id"] == request_id
    assert row["status"] == "DENIED"


def test_an_admin_can_reverse_their_own_refusal(client, world):
    """A DENIED row can still be granted, which is why ``decide`` accepts any state.

    ``POST /workshops/access-requests/{id}/decide`` 409s anything but PENDING and sends the admin to
    the roster endpoints instead. There is no such second endpoint here, and — since a designer
    cannot re-ask their way past a refusal, which is the test above — a refusal that could never be
    reversed would be permanent. The two rules only work as a pair.
    """
    workshop_id = _make_workshop(world, "Reversed refusal")
    assert _ask(world, "requester", workshop_id).status_code == 202
    request_id = _one_row(world, workshop_id)["id"]

    assert _decide(world, request_id, "DENIED").status_code == 200
    reversed_ = _decide(world, request_id, "GRANTED")
    assert reversed_.status_code == 200, reversed_.text
    assert reversed_.json()["status"] == "GRANTED"

    opened = client.get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "requester")
    )
    assert opened.status_code == 200, opened.text


def test_refusing_somebody_who_already_has_access_is_a_conflict_not_a_lie(client, world):
    """409, and the row stays as it was.

    Writing DENIED over access that remains would put a sentence on the admin's screen that is false
    in the direction that matters: they would believe they had taken the access away, and the person
    would carry on reading the workshop. Removing a viewer is the viewers PUT and only the viewers
    PUT — one way in, one way out — so this refuses and names the screen.
    """
    workshop_id = _make_workshop(world, "Refusing an existing viewer")
    assert _ask(world, "requester", workshop_id).status_code == 202
    request_id = _one_row(world, workshop_id)["id"]

    seated = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [world["people"]["requester"].id]},
        headers=_headers(world, "admin"),
    )
    assert seated.status_code == 200, seated.text

    refused = _decide(world, request_id, "DENIED")
    assert refused.status_code == 409, refused.text
    assert _one_row(world, workshop_id)["status"] == "PENDING"


def test_asking_again_after_a_grant_was_removed_reopens_the_same_row(client, world):
    """THE ONE REPEAT ASK THAT IS NOT A NO-OP, and the only place ``createdAt`` ever moves.

    Both halves are here because they are one rule. While the grant STANDS a second ask changes
    nothing at all — the "already in" branch catches it before anything is written, so the row keeps
    its GRANTED status, its clock and its note. Once an admin has taken the person OFF the workshop,
    the same ask is a genuinely new one: it could not have been produced by the designer alone (an
    admin had to remove them), and without the reopen that person could never ask again through any
    surface, because the unique index means their old row is the only one they will ever have.

    WHAT MOVES AND WHAT IS KEPT is the substance of it. ``status`` goes back to PENDING and
    ``createdAt`` moves, so the ask sits at the BACK of a queue ordered oldest-first rather than in
    the position of one answered months ago — the one exception to the frozen clock
    ``test_asking_twice_files_one_request_and_does_not_move_it_up_the_queue`` pins. The DECISION
    columns are kept: they are the only record the previous answer has, and PENDING beside a filled-in
    ``decidedBy`` is exactly what tells an admin this has been decided before and not decided again
    yet. The note and the source are the NEW ask's, because they are what this person is saying now.
    """
    workshop_id = _make_workshop(world, "Reopened after a grant was removed")
    code = _code_for(workshop_id)
    assert _ask(world, "requester", workshop_id, scannedCode=code, note="first ask").status_code == 202
    request_id = _one_row(world, workshop_id)["id"]

    assert _decide(world, request_id, "GRANTED", "on for stage 4").status_code == 200
    assert _viewer_ids(world, workshop_id) == {world["people"]["requester"].id}
    granted = _one_row(world, workshop_id)
    assert granted["status"] == "GRANTED"

    # ── THE NEGATIVE HALF: while the grant stands, asking again writes nothing at all ───────────
    assert _ask(world, "requester", workshop_id, scannedCode=code, note="second ask").status_code == 202
    assert _one_row(world, workshop_id) == granted

    removed = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": []},
        headers=_headers(world, "admin"),
    )
    assert removed.status_code == 200, removed.text
    assert _viewer_ids(world, workshop_id) == set()

    # THE STORED ROW IS UNTOUCHED BY THE REMOVAL, and the one field that moves is the one that is
    # not stored. A request records that the ask was answered yes, not that the access still stands,
    # so ``status`` stays GRANTED — while ``requesterHasAccess`` is computed per read and follows the
    # access away. That combination is the row ``request_payload``'s docstring calls the one thing in
    # this queue that looks alarming and usually is not, and it is what the reopen below keys on.
    after_removal = _one_row(world, workshop_id)
    assert _without_access_flag(after_removal) == _without_access_flag(granted)
    assert granted["requesterHasAccess"] is True
    assert after_removal["requesterHasAccess"] is False

    again = _ask(world, "requester", workshop_id, scannedCode=code, note="please let me back on")
    assert again.status_code == 202, again.text

    reopened = _one_row(world, workshop_id)
    assert reopened["id"] == request_id
    assert reopened["status"] == "PENDING"
    assert datetime.fromisoformat(reopened["createdAt"]) > datetime.fromisoformat(
        granted["createdAt"]
    )
    assert reopened["note"] == "please let me back on"
    assert reopened["source"] == "SCAN"
    # KEPT, all three: the previous decision is the only history this row has.
    assert reopened["decidedBy"]["id"] == world["people"]["admin"].id
    assert reopened["decidedAt"] == granted["decidedAt"]
    assert reopened["decisionNote"] == "on for stage 4"


def test_deciding_a_request_that_does_not_exist_is_a_404(client, world):
    """``require_record``'s answer, unchanged: 404 and the same detail a missing record gets.

    The uniform 202 the ASK route gives is a departure forced by that route's purpose. Nothing here
    needs it — every caller is already an admin, for whom the answer to "may I see this" is always
    yes — so this route follows the repository rule exactly.
    """
    response = _decide(world, f"cmsnosuchrequest{uuid.uuid4().hex[:8]}", "GRANTED")
    assert response.status_code == 404, response.text
    assert response.json()["detail"] == "Record not found"
