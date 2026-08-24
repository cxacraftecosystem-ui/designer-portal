"""Which door each caller gets on the join-card routes, and WHEN the database is first touched.

**No database required, and that is the point of the file rather than a convenience.**

TWO PROPERTIES, AND THE SECOND IS THE ONE A REAL DATABASE CANNOT SEE
====================================================================

**ONE: THE ROLE GATES, IN CI.** Every join-card route is open to any signed-in account, which looks
like a gap and is not: what is admin-only is a card good for MORE THAN ONE PERSON, and that rule
lives in ``mint_grant`` where it can see both the actor's role and the record. This module asserts
both halves over the real routers — that a designer reaches minting at all, and that a designer
asking for a multi-use card is refused before a row is written.

**TWO: THE ORDER OF THE REFUSALS, WHICH IS THE ENUMERATION ARGUMENT ITSELF.**
``services/design_workshop_grants`` claims, in its header and in ``redeem``'s docstring, that the
only thing the redemption route ever says out loud about a card's SHAPE is decided from the request
body, before any database read. That claim is what makes a 422 there safe rather than an oracle, and
it is exactly the kind of claim a later edit breaks by moving one line. A test over a real database
cannot see it: the 422 looks identical whether it was decided before or after a lookup.

Here it is visible. ``db`` is a tripwire that raises the moment any delegate is read off it, so "HTTP
422 and the tripwire was never touched" is the property stated as an assertion. If somebody moves the
token lookup above ``decode_join_code``, the status code does not change and this test goes red.

THE TRIPWIRE IS THE ONE FROM ``test_design_workshop_access_gate``, deliberately re-implemented rather
than imported from it — a test module that imports another test module's harness makes two files fail
together for one cause and makes neither runnable on its own. That module states the same reasoning
about borrowing it from ``test_permission_matrix``. What is borrowed is the IDEA and the rebinding
trick, which is the part that is easy to get wrong: every module does ``from app.core.db import db``,
so each holds its own reference and patching the source alone would miss all of them.

WHAT THIS MODULE DELIBERATELY DOES NOT ASSERT: anything about a card that RESOLVES. A tripwire raises
on the first read, so every outcome past the grammar is out of reach by construction — those live in
``test_design_workshop_grant_tokens``, over a stub that answers. The two files are the two halves and
neither can host the other.
"""

import asyncio
import sys
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.core.db as core_db
from app.api.router import api_router
from app.core import deps
from app.services.design_workshop_grants import encode_join_code, mint_secret

#: A cuid-shaped id that names nothing. Nothing here reaches a database, so it never needs to.
WORKSHOP_ID = "cmgrantgate000000000000aa"


class _DatabaseTouched(Exception):
    """Raised by the tripwire. Escaping the request means the handler got past every guard."""


class _Tripwire:
    """Stands in for ``db``. Reading any delegate off it means a database read was about to happen."""

    def __init__(self) -> None:
        object.__setattr__(self, "touched", False)

    def __getattr__(self, name: str) -> Any:
        # ``__getattr__`` and not ``__getattribute__``, so ``touched`` above stays readable.
        object.__setattr__(self, "touched", True)
        raise _DatabaseTouched(name)


class _Outcome:
    """Either "a read was attempted" or "the request was answered", never a bare status code.

    The distinction is the whole point of the file: a 422 that reached the database and a 422 that did
    not are the same three digits and two different security properties.
    """

    def __init__(self, *, reached: bool, status_code: int | None = None, detail: Any = "") -> None:
        self.reached = reached
        self.status_code = status_code
        self.detail = str(detail)

    def __repr__(self) -> str:  # pragma: no cover - only read out of a failure message
        return "reached-the-database" if self.reached else f"HTTP {self.status_code}: {self.detail}"


_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


#: Assembled once. Every router with its response models costs a couple of seconds to build, and
#: nothing request-scoped lives on it.
_APP = _build_app()


def _user(role: str) -> SimpleNamespace:
    return SimpleNamespace(
        id=f"u-{role.lower()}", email=f"{role.lower()}@example.test", name="Gate Test", role=role
    )


@pytest.fixture
def api(monkeypatch: pytest.MonkeyPatch):
    """The real API with every module's ``db`` rebound to the tripwire, found BY IDENTITY.

    By identity rather than by module name, so it keeps working when a module is added — which is
    exactly what happened in this wave: ``services/design_workshop_grants`` is new, and a list of
    module names written last month would have left it pointing at the real client and this whole
    file asserting nothing.
    """
    tripwire = _Tripwire()
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", tripwire)
    for module in list(sys.modules.values()):
        name = getattr(module, "__name__", "")
        if name.startswith("app.") and getattr(module, "db", None) is real_db:
            monkeypatch.setattr(module, "db", tripwire)

    def call(method: str, path: str, *, as_role: str, body: dict[str, Any] | None = None) -> _Outcome:
        _CURRENT["user"] = _user(as_role)

        async def run() -> _Outcome:
            transport = httpx.ASGITransport(app=_APP)
            async with httpx.AsyncClient(transport=transport, base_url="http://gate.test") as client:
                response = await client.request(method, f"/api{path}", json=body)
            payload = response.json() if response.content else {}
            detail = payload.get("detail", payload) if isinstance(payload, dict) else payload
            return _Outcome(reached=False, status_code=response.status_code, detail=detail)

        try:
            return asyncio.run(run())
        except _DatabaseTouched:
            return _Outcome(reached=True)

    yield SimpleNamespace(call=call, tripwire=tripwire)
    _CURRENT["user"] = None


def _card(workshop_id: str = WORKSHOP_ID) -> str:
    """A syntactically PERFECT join card whose secret matches nothing.

    Which is the whole demonstration: the FNV check characters are computable by anybody — the
    algorithm ships to every browser — so a well-formed card proves nothing at all. What decides is
    the 110-bit secret matching a row, and that can only be decided ONLINE.
    """
    return encode_join_code(workshop_id, mint_secret())


# --------------------------------------------------------------------------------------
# The refusals that must be decided from the body alone
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "code",
    [
        # A check character one out. The refusal the grammar exists for.
        "DPW2:J:CMGRANTGATE000000000000AA.AAAAAAAAAAAAAAAAAAAAAA:AAAA",
        # A secret one character short — not a near miss, a different card or no card.
        "DPW2:J:CMGRANTGATE000000000000AA.AAAAAAAAAAAAAAAAAAAAA:AAAA",
        # No separator at all between the id and the secret.
        "DPW2:J:CMGRANTGATE000000000000AAAAAAAAAAAAAAAAAAAAAAAAA:AAAA",
        # Ours and well formed, but it is a RECORD tag rather than a join card.
        "DPW1:G:CMGRANTGATE000000000000AA:AAAA",
        # Ours, a join card, and printed against a format this server does not read.
        "DPW9:J:CMGRANTGATE000000000000AA.AAAAAAAAAAAAAAAAAAAAAA:AAAA",
        # A workshop that exists only on the handset that printed it. Both clients' spellings.
        "DPW2:J:LOCAL-3F2504E0-4F89-11D3-9A0C-0305E82C3301.AAAAAAAAAAAAAAAAAAAAAA:AAAA",
        "DPW2:J:DWLOCAL-3F2504E0-4F89-11D3-9A0C-0305E82C3301.AAAAAAAAAAAAAAAAAAAAAA:AAAA",
        # Not one of ours at all.
        "https://example.org/scan-me",
    ],
)
def test_a_damaged_card_is_refused_before_the_database_is_asked_anything(api, code):
    """**THE ENUMERATION ORDERING, AS AN ASSERTION RATHER THAN A PARAGRAPH.**

    The redemption route's refusals about a card that RESOLVES are uniform — an unknown secret, a
    revoked card and a card expired beyond the grace window all answer the same 403 sentence — and
    that is asserted over a stub in ``test_design_workshop_grant_tokens``. The one thing this route
    says out loud is that the STRING is unreadable, and that is only safe while it is decided from
    the body alone: a grammar check that ran AFTER a lookup could refuse differently depending on
    whether the card existed, and the status code would look exactly the same.

    So the assertion is not merely 422. It is **422 WITH THE DATABASE NEVER TOUCHED.**
    """
    outcome = api.call(
        "POST", "/design-workshop-access/redemptions", as_role="DESIGNER", body={"code": code}
    )
    assert outcome.status_code == 422, outcome
    assert api.tripwire.touched is False, "the grammar refusal must not depend on a database read"


def test_a_refusal_never_echoes_the_card(api):
    """A join card's payload is a live credential, and a 422 body is where one would leak.

    Pydantic puts a rejected value INTO the error body, which is why ``JoinCardRedeemIn.code`` has a
    length cap and deliberately no ``pattern``: a regex failure would report the offending input, and
    the offending input is somebody's card. The grammar refusals are written by hand and quote
    nothing.
    """
    card = _card()
    outcome = api.call(
        "POST",
        "/design-workshop-access/redemptions",
        as_role="DESIGNER",
        # One character of the check mangled: the card is otherwise entirely real.
        body={"code": card[:-1] + ("0" if card[-1] != "0" else "1")},
    )
    assert outcome.status_code == 422, outcome
    assert card[:-1] not in outcome.detail
    assert card.split(".")[1][:8] not in outcome.detail


def test_a_perfectly_formed_card_reaches_the_database_which_is_the_whole_point(api):
    """The positive control, and it is the argument for the 110-bit secret in one assertion.

    This card's check characters are correct, its namespace is correct, its letter is correct and its
    workshop id is well formed — because ANY of that is computable by anybody holding the algorithm,
    which ships to every browser. So the grammar cannot refuse it, and the only thing left that can
    is the secret failing to match a row. That read is what the tripwire catches here.

    "Reached the database" rather than a status code, because "not 422" would also be satisfied by a
    403 from an unrelated cause.
    """
    outcome = api.call(
        "POST", "/design-workshop-access/redemptions", as_role="DESIGNER", body={"code": _card()}
    )
    assert outcome.reached, outcome


# --------------------------------------------------------------------------------------
# Who gets which door
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("role", ["DESIGNER", "RESEARCHER", "PROFESSOR", "CROWDSOURCE_VOLUNTEER"])
def test_anybody_signed_in_may_present_a_card(api, role):
    """Not a gap, and the reasoning is the same one the ask route carries.

    Whether the redeemer may actually hold a viewer row is decided at the moment of the grant, by the
    eligibility rule ``design_workshop_viewers`` owns — where the rule already lives, where it reads
    both rosters, and where a refusal becomes a provisional foothold rather than a refusal in a
    courtyard naming a screen nobody present can reach. It is asked about the REDEEMER alone: it used
    to be inherited from a whole-set ``replace_viewers`` call, which meant a colleague's lapsed
    empanelment could refuse an unrelated induction.
    Refusing a role at this door instead would put a second, quieter copy of that rule in the one
    place nobody can see its answer, and would lose the record that the person scanned at all.
    """
    outcome = api.call(
        "POST", "/design-workshop-access/redemptions", as_role=role, body={"code": _card()}
    )
    assert outcome.reached, outcome


@pytest.mark.parametrize("max_uses", [2, 50, None])
@pytest.mark.parametrize("role", ["DESIGNER", "RESEARCHER", "PROFESSOR"])
def test_only_an_admin_may_ask_for_a_card_that_lets_more_than_one_person_in(api, role, max_uses):
    """**THE NON-NEGOTIABLE, ASSERTED OVER THE WIRE AND BEFORE ANY DATABASE READ.**

    403, and the tripwire untouched — which is the stronger form of the assertion: the refusal cannot
    depend on whether the record exists, so it is not an oracle, and no row can have been written.

    ``max_uses=None`` (unlimited) is in this list on purpose. A rule spelled ``max_uses > 1`` passes
    the first two columns and hands a designer a card that admits everybody, and that is the exact
    shape of mistake this parametrisation exists to catch. PROFESSOR is here for the sibling reason:
    they outrank a designer on the ladder and are still not an administrator, which is the row a rank
    comparison written in place of ``is_admin`` gets wrong.
    """
    outcome = api.call(
        "POST",
        "/design-workshop-access/grants",
        as_role=role,
        body={"recordType": "DESIGN_WORKSHOP", "recordId": WORKSHOP_ID, "maxUses": max_uses},
    )
    assert outcome.status_code == 403, outcome
    assert api.tripwire.touched is False, "no card may be minted, and no row read, by a refused call"


def test_a_designer_may_ask_for_a_single_use_card(api):
    """The courtyard case, and the reason ``require_admin`` is deliberately NOT on this route.

    Somebody already on the workshop hands a card to the person standing next to them, because there
    is no administrator within two districts. Whether THIS designer is on THIS workshop is decided
    against the record — a read, which is what the tripwire catches — and a stranger gets the
    ordinary 404 there rather than a 403 here.
    """
    outcome = api.call(
        "POST",
        "/design-workshop-access/grants",
        as_role="DESIGNER",
        body={"recordType": "DESIGN_WORKSHOP", "recordId": WORKSHOP_ID, "maxUses": 1},
    )
    assert outcome.reached, outcome


def test_a_card_for_a_record_with_no_membership_is_refused_from_the_body_alone(api):
    """Requirement 8's dividing line, and it is a statement about the body.

    An artisan, a tool or a product is not something a person is a member of, so a card for one would
    admit nobody — worse than no card, because somebody prints twenty and hands them out. The refusal
    depends only on the record TYPE that was sent, which is why it is safe to say out loud and why
    the tripwire stays untouched.
    """
    for record_type in ("ARTISAN", "TOOL", "PROTOTYPE", "MEDIA"):
        outcome = api.call(
            "POST",
            "/design-workshop-access/grants",
            as_role="ADMIN",
            body={"recordType": record_type, "recordId": WORKSHOP_ID, "maxUses": 1},
        )
        assert outcome.status_code == 422, (record_type, outcome)
        assert api.tripwire.touched is False, record_type


def test_a_record_tag_posted_to_the_join_door_is_refused_from_the_body_alone(api):
    """And the sentence sends them to the right screen rather than calling the card damaged.

    The card in the person's hand is fine; they are at the wrong door. Telling them it is damaged
    sends them looking for another card that does not exist, which is the failure mode every refusal
    in this feature is written against.
    """
    outcome = api.call(
        "POST",
        "/design-workshop-access/redemptions",
        as_role="DESIGNER",
        body={"code": "DPW1:G:CMGRANTGATE000000000000AA:AAAA"},
    )
    assert outcome.status_code == 422, outcome
    assert api.tripwire.touched is False
    assert "join card" in outcome.detail.lower(), outcome


def test_a_join_card_posted_to_the_ask_door_is_refused_from_the_body_alone(api):
    """The other direction, on the route that already existed.

    ``POST /requests`` reads only the v1 record grammar, and a join card arriving there must not be
    filed as a request — that would put somebody in a queue waiting for an admin when the card in
    their hand would simply have let them in. Body-only, so
    ``test_design_workshop_access_gate``'s standing property that the ask route's only loud refusal
    is decided before any read is preserved rather than quietly widened.
    """
    outcome = api.call(
        "POST",
        "/design-workshop-access/requests",
        as_role="DESIGNER",
        body={"workshopId": WORKSHOP_ID, "scannedCode": _card()},
    )
    assert outcome.status_code == 422, outcome
    assert api.tripwire.touched is False, "the ask route must still refuse from the body alone"
    assert "join card" in outcome.detail.lower(), outcome
