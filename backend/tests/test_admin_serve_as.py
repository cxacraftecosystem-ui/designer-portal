"""Administrators serving as a workshop's designer, Assistant Director, Regional Director or inspector.

THE OWNER'S RULING OF 2026-10-09 (D3). MASTER_ADMIN, ADMIN and MINISTRY_ADMIN may be APPOINTED, one
workshop at a time, to any of the four posts, through the same pickers and the same writes as
everybody else — and the separation of duties that makes that safe is about ONE workshop:

* nobody appoints themselves;
* one person is never both a workshop's Assistant Director and its Regional Director, nor its
  inspector and either of those;
* nobody inspects or supervises a workshop they AUTHORED — a viewer row on it, or stages they wrote
  — and creating it is not authoring it;
* whoever holds an inspection or oversight post on a workshop cannot write it, through the admin arm
  of the loader too, and is refused designer access to it.

WHAT "CANNOT WRITE IT" COVERS was made precise the same day: every write to the workshop's CONTENT
or its DESIGNER TEAM, by any door — stage saves, the workshop's edit and delete, the artisan list's
import and unlink, the viewers PUT, the oversight screen's designer doors, deciding an access request
and printing a join card. The records filed under a workshop are its content too, so the record
forms are in the list: filing a record into the workshop, on a create or a move, and then ANY write
to a record filed there — a PATCH of any field, an unfile or a move out, a delete, an interview
merge with either side there, a tool's artisan links, every edit of an attached questionnaire form —
for artisans, products, processes, tools, interviews and the designer-authored questionnaire. And so
are its files: deleting one, setting, refining or re-transcribing its transcript, deciding an
identity photograph either way, and relinking one out of the workshop or into it. The last three
doors arrived later the same day: uploading a NEW file into the workshop (tagged to it, or attached
to a record filed under it), re-queueing a failed job for one of its files, and the review queue's
edit of a record filed under it or a file it holds — which, besides, no longer re-files a record
under any workshop for anybody. What a holder KEEPS is every read, appointing OTHER people (under
the rules above), restore, and the report with its export ledger row. Both lists are tested door
by door; a holder who scans a join card for their own workshop lands as a foothold, keeps the
card's seat unspent, and is not told the card was used.

Appointment refusals are 409 with the rule named; a refused write is 403 naming the post. The
INSPECTOR, ASSISTANT_DIRECTOR and REGIONAL_DIRECTOR tiers must work exactly as they did.

TWO HALVES. The first needs no database and runs in the gating CI job: the pure rules in
``services/design_workshop_posts`` and the three validators over fake tables. The second is the
whole thing over Postgres and the real routes, built the way ``tests/test_workshop_join_sync.py``
builds a database module: one synchronous seed in a private loop, then a ``TestClient``, and no
database call while the client is alive.
"""

import asyncio
import uuid
from types import SimpleNamespace
from typing import Any

import pytest
from conftest import needs_db
from fastapi import HTTPException

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core import deps
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import access_roster, design_workshop_posts as posts

# ════════════════════════════════════════════════════════════════════════════════════════════════
# 1. THE RULES, PURE
# ════════════════════════════════════════════════════════════════════════════════════════════════

ME = "A. Sharma (a@x.test)"


def _refusals(post: str, *, held=(), authored=(), self_appointed=False) -> list[str]:
    return posts.separation_refusals(
        person=ME,
        post=post,
        standing=posts.Standing(posts=frozenset(held), authored=frozenset(authored)),
        self_appointed=self_appointed,
    )


def test_is_admin_is_still_exactly_the_two_platform_tiers():
    """The ruling appoints administrators to POSTS; it widens no role set. A MINISTRY_ADMIN serving
    as a workshop's inspector is still not an admin anywhere else in the product."""
    for role in deps.ROLE_RANK:
        assert deps.is_admin(SimpleNamespace(role=role)) is (role in {"ADMIN", "MASTER_ADMIN"}), role
    assert frozenset({"MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"}) == posts.SERVING_ADMIN_ROLES


@pytest.mark.parametrize("post", ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "INSPECTOR", "DESIGNER"])
def test_a_clean_appointment_breaks_no_rule(post):
    assert _refusals(post) == []


@pytest.mark.parametrize("post", ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "INSPECTOR", "DESIGNER"])
def test_nobody_appoints_themselves_to_any_post(post):
    [refusal] = _refusals(post, self_appointed=True)
    assert "nobody appoints themselves" in refusal
    assert ME in refusal


def test_one_person_is_never_both_directors():
    for post, other in (("ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"), ("REGIONAL_DIRECTOR", "ASSISTANT_DIRECTOR")):
        [refusal] = _refusals(post, held={post, other})
        assert "both the Assistant Director and the Regional Director" in refusal
    # Holding the same post already is not "both".
    assert _refusals("ASSISTANT_DIRECTOR", held={"ASSISTANT_DIRECTOR"}) == []


def test_an_inspector_never_supervises_and_a_supervisor_never_inspects():
    [as_inspector] = _refusals("INSPECTOR", held={"REGIONAL_DIRECTOR"})
    assert "is this workshop's Regional Director, so they cannot also inspect it" in as_inspector
    [as_director] = _refusals("ASSISTANT_DIRECTOR", held={"INSPECTOR"})
    assert "inspects this workshop, so they cannot also be its Assistant Director" in as_director
    # An inspector already on the panel is not refused the panel.
    assert _refusals("INSPECTOR", held={"INSPECTOR"}) == []


@pytest.mark.parametrize("post", ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "INSPECTOR"])
@pytest.mark.parametrize(
    ("authored", "phrase"),
    [
        ({posts.DESIGNER_ACCESS}, "holds designer access to this workshop"),
        ({posts.STAGE_WRITES}, "has written this workshop's stages"),
        (
            {posts.DESIGNER_ACCESS, posts.STAGE_WRITES},
            "holds designer access to this workshop and has written its stages",
        ),
    ],
)
def test_an_author_never_inspects_or_supervises(post, authored, phrase):
    [refusal] = _refusals(post, authored=authored)
    assert phrase in refusal
    assert "nobody inspects or supervises work they authored" in refusal
    assert "Creating a workshop is not authoring it" in refusal


def test_authorship_is_no_bar_to_being_a_designer():
    """Rule 4 is about supervising and inspecting. A co-designer being named a designer is the job."""
    assert _refusals("DESIGNER", authored={posts.DESIGNER_ACCESS, posts.STAGE_WRITES}) == []


@pytest.mark.parametrize("held", [{"INSPECTOR"}, {"ASSISTANT_DIRECTOR"}, {"REGIONAL_DIRECTOR"}])
def test_a_post_holder_is_refused_designer_access(held):
    [refusal] = _refusals("DESIGNER", held=held)
    assert "cannot also be given designer access to it" in refusal
    assert "does not write it" in refusal


def test_every_broken_rule_is_named_and_none_is_swallowed_by_another():
    refused = _refusals(
        "INSPECTOR",
        held={"ASSISTANT_DIRECTOR"},
        authored={posts.STAGE_WRITES},
        self_appointed=True,
    )
    assert len(refused) == 3


def test_the_write_refusal_names_every_post_in_one_order():
    """The web prints this same sentence before a save is even attempted, word for word, so the
    order of the posts is part of the wording."""
    assert posts.write_refusal({"INSPECTOR"}).startswith("You are this workshop's inspector, so you")
    assert posts.write_refusal({"INSPECTOR", "ASSISTANT_DIRECTOR"}).startswith(
        "You are this workshop's Assistant Director and inspector, so you"
    )
    assert posts.write_refusal({"REGIONAL_DIRECTOR"}) == (
        "You are this workshop's Regional Director, so you can read it but not change it: whoever "
        "inspects or supervises a workshop does not write it. Ask whoever made the appointment to "
        "take you off that post if you need to work on it."
    )


def test_an_account_problem_is_a_422_and_a_staffing_problem_alone_is_a_409():
    with pytest.raises(HTTPException) as staffing:
        posts.raise_refusals([], ["a", "a", "b"])
    assert staffing.value.status_code == 409
    assert staffing.value.detail == "a b Nothing was changed."

    with pytest.raises(HTTPException) as account:
        posts.raise_refusals(["x"], ["a"])
    assert account.value.status_code == 422
    assert account.value.detail == "x a Nothing was changed."

    posts.raise_refusals([], [])  # nothing wrong, nothing raised


@pytest.mark.parametrize("role", list(deps.ROLE_RANK))
def test_only_a_role_that_may_hold_a_post_pays_for_the_write_gates_query(role):
    """A DESIGNER can hold no supervisory post, so a designer's stage save — the hot path — asks
    nothing. Everybody who could hold one is asked."""
    expected = role in {"INSPECTOR", "ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"} | set(
        posts.SERVING_ADMIN_ROLES
    )
    assert posts.could_hold_a_supervisory_post(SimpleNamespace(role=role)) is expected


# ════════════════════════════════════════════════════════════════════════════════════════════════
# 2. THE VALIDATORS, OVER FAKE TABLES
# ════════════════════════════════════════════════════════════════════════════════════════════════


def _account(role: str) -> SimpleNamespace:
    return SimpleNamespace(id=f"u-{role}", name=role.title(), email=f"{role.lower()}@x.test", role=role)


class _Users:
    def __init__(self) -> None:
        self.by_id = {f"u-{role}": _account(role) for role in deps.ROLE_RANK}

    async def find_many(self, where=None, **_kwargs):
        return [self.by_id[uid] for uid in where["id"]["in"] if uid in self.by_id]


@pytest.fixture
def staffed(monkeypatch: pytest.MonkeyPatch):
    """The posts readers answering from dicts the test fills in, and every allow-list read clean."""
    state: dict[str, Any] = {"held": {}, "authored": {}}

    async def _held(workshop_id, user_ids):
        ids = set(user_ids)
        return {uid: frozenset(p) for uid, p in state["held"].items() if uid in ids}

    async def _authored(workshop_id, user_ids):
        ids = set(user_ids)
        return {uid: frozenset(e) for uid, e in state["authored"].items() if uid in ids}

    async def _bars_nobody(emails):
        return set()

    monkeypatch.setattr(posts, "supervisory_posts_among", _held)
    monkeypatch.setattr(posts, "authorship_among", _authored)
    monkeypatch.setattr(access_roster, "barred_among", _bars_nobody)
    return state


def _inspect(monkeypatch, ids, *, appointing=None):
    from app.services import design_workshop_inspectors as inspectors

    monkeypatch.setattr(inspectors, "db", SimpleNamespace(user=_Users()))
    try:
        asyncio.run(inspectors._assert_every_id_may_inspect("w1", set(ids), appointing=appointing))
    except HTTPException as refusal:
        return refusal
    return None


@pytest.mark.parametrize("role", ["INSPECTOR", "MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"])
def test_the_inspector_tier_and_every_administrator_tier_may_be_appointed_to_inspect(
    staffed, monkeypatch, role
):
    assert _inspect(monkeypatch, {f"u-{role}"}) is None


@pytest.mark.parametrize("role", ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "PROFESSOR", "DESIGNER"])
def test_a_role_that_may_not_inspect_is_still_a_422(staffed, monkeypatch, role):
    refusal = _inspect(monkeypatch, {f"u-{role}"})
    assert refusal is not None and refusal.status_code == 422
    assert f"is a {role}" in refusal.detail


def test_the_inspector_validator_applies_every_separation_rule_as_a_409(staffed, monkeypatch):
    self_named = _inspect(monkeypatch, {"u-ADMIN"}, appointing="u-ADMIN")
    assert self_named is not None and self_named.status_code == 409
    assert "nobody appoints themselves" in self_named.detail

    staffed["held"] = {"u-MINISTRY_ADMIN": {"ASSISTANT_DIRECTOR"}}
    supervisor = _inspect(monkeypatch, {"u-MINISTRY_ADMIN"})
    assert supervisor is not None and supervisor.status_code == 409
    assert "cannot also inspect it" in supervisor.detail

    staffed["held"] = {}
    staffed["authored"] = {"u-MASTER_ADMIN": {posts.STAGE_WRITES}}
    author = _inspect(monkeypatch, {"u-MASTER_ADMIN"})
    assert author is not None and author.status_code == 409
    assert "has written this workshop's stages" in author.detail


def _grant(monkeypatch, ids, *, workshop_id="w1", appointing=None):
    from app.services import design_workshop_viewers as viewers

    async def _admits_every_designer(found):
        return {u.email for u in found}

    monkeypatch.setattr(viewers, "db", SimpleNamespace(user=_Users()))
    monkeypatch.setattr(viewers, "_designers_the_roster_still_admits", _admits_every_designer)
    try:
        asyncio.run(
            viewers._assert_every_id_may_be_granted(
                set(ids), workshop_id=workshop_id, appointing=appointing
            )
        )
    except HTTPException as refusal:
        return refusal
    return None


@pytest.mark.parametrize("post", ["INSPECTOR", "ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"])
def test_the_workshops_inspector_or_supervisor_is_refused_a_viewer_row_on_it(staffed, monkeypatch, post):
    staffed["held"] = {"u-ADMIN": {post}}
    refusal = _grant(monkeypatch, {"u-ADMIN"})
    assert refusal is not None and refusal.status_code == 409
    assert "cannot also be given designer access" in refusal.detail
    # The SAME account on a workshop it holds no post on is granted without a word.
    staffed["held"] = {}
    assert _grant(monkeypatch, {"u-ADMIN"}) is None


def test_nobody_grants_themselves_designer_access(staffed, monkeypatch):
    refusal = _grant(monkeypatch, {"u-MINISTRY_ADMIN"}, appointing="u-MINISTRY_ADMIN")
    assert refusal is not None and refusal.status_code == 409
    assert "nobody appoints themselves" in refusal.detail


def test_without_a_workshop_the_grant_rule_asks_nothing_about_posts(staffed, monkeypatch):
    """The create doors pass no workshop: one being created has no posts yet."""
    staffed["held"] = {"u-ADMIN": {"INSPECTOR"}}
    assert _grant(monkeypatch, {"u-ADMIN"}, workshop_id=None) is None


def test_a_role_refusal_and_a_post_refusal_arrive_together_as_one_422(staffed, monkeypatch):
    staffed["held"] = {"u-ADMIN": {"INSPECTOR"}}
    refusal = _grant(monkeypatch, {"u-ADMIN", "u-PROFESSOR"})
    assert refusal is not None and refusal.status_code == 422
    assert "PROFESSOR" in refusal.detail
    assert "cannot also be given designer access" in refusal.detail


def test_the_write_gate_refuses_a_holder_by_post_and_asks_nothing_of_a_designer(
    staffed, monkeypatch
):
    """``refuse_a_holders_write`` is the one test both stage-write doors ask: the loader's edit arm
    and the artisan-list import. A holder is told which post; an administrator holding none passes;
    a designer is answered from the role alone."""
    staffed["held"] = {"u-ADMIN": {"INSPECTOR"}, "u-MINISTRY_ADMIN": {"REGIONAL_DIRECTOR"}}
    for role, posts_held in (("ADMIN", {"INSPECTOR"}), ("MINISTRY_ADMIN", {"REGIONAL_DIRECTOR"})):
        with pytest.raises(HTTPException) as refused:
            asyncio.run(posts.refuse_a_holders_write("w1", _account(role)))
        assert refused.value.status_code == 403
        assert refused.value.detail == posts.write_refusal(posts_held)

    asyncio.run(posts.refuse_a_holders_write("w1", _account("MASTER_ADMIN")))  # holds nothing here

    async def _never(*_args, **_kwargs):
        raise AssertionError("a designer's stage save must not query who serves on the workshop")

    monkeypatch.setattr(posts, "supervisory_posts_among", _never)
    asyncio.run(posts.refuse_a_holders_write("w1", _account("DESIGNER")))


def test_the_artisan_import_asks_the_write_gate_before_it_reads_the_workbook():
    """The import has its own loader rather than ``load_workshop_or_404``, so the gate is a line in
    the route — and it must come before the workbook is read, or a holder pays for an upload that
    was always going to be refused. Held here without a database, for the job that has none."""
    import inspect

    from app.api.routes import design_workshop_oversight as routes

    source = inspect.getsource(routes.upload_artisan_list)
    gate = source.find("posts.refuse_a_holders_write(workshop_id, current_user)")
    assert gate != -1, "the artisan import no longer asks whether its caller serves on the workshop"
    assert gate < source.find("_read_artisan_upload(file, request)")
    assert gate < source.find("import_artisans(")


def _gate_comes_first(handler: Any, write: str) -> None:
    import inspect

    source = inspect.getsource(handler)
    gate = source.find("posts.refuse_a_holders_write(workshop_id, current_user)")
    assert gate != -1, f"{handler.__name__} no longer asks whether its caller serves on the workshop"
    assert source.find(write) != -1, f"{handler.__name__} no longer calls {write!r}"
    assert gate < source.find(write), f"{handler.__name__} writes before it asks the write gate"


def test_every_route_that_writes_the_team_or_unfiles_an_artisan_asks_the_write_gate_first():
    """Each of these doors has its own loader rather than ``load_workshop_or_404``, so the rule is a
    line in the route — and it must come before the write, so a holder learns the reason rather
    than half an outcome. Held without a database, for the job that has none."""
    from app.api.routes import (
        design_workshop_oversight as oversight_routes,
        design_workshop_viewers as viewer_routes,
    )

    _gate_comes_first(oversight_routes.set_workshop_designer, "oversight.reassign_designer(")
    _gate_comes_first(oversight_routes.set_workshop_designers, "oversight.set_named_designers(")
    _gate_comes_first(
        oversight_routes.unlink_workshop_artisan, "oversight.unlink_artisan_from_workshop("
    )
    _gate_comes_first(viewer_routes.set_viewers, "replace_viewers(")


def test_the_doors_a_holder_keeps_do_not_ask_the_write_gate():
    """Appointing other people, restore and the report are what the ruling leaves a holder. A gate
    added to one of them would take back something the owner gave, so their absence is pinned."""
    import inspect

    from app.api.routes import (
        design_workshop_inspections as inspection_routes,
        design_workshop_oversight as oversight_routes,
        design_workshops as workshop_routes,
    )

    for handler in (
        oversight_routes.set_workshop_oversight,
        inspection_routes.set_inspectors,
        workshop_routes.restore_design_workshop,
        workshop_routes.generate_report,
    ):
        assert "refuse_a_holders_write" not in inspect.getsource(handler), handler.__name__
    # The export ledger loads for edit, and says in its own call that a holder may still write it.
    assert "barred_to_post_holders=False" in inspect.getsource(workshop_routes.record_device_export)


class _Delegate:
    """One fake table: answers ``find_unique`` with a fixed row and records anything written."""

    def __init__(self, row: Any = None) -> None:
        self.row = row
        self.written: list[Any] = []

    async def find_unique(self, where=None, **_kwargs):
        return self.row

    async def update(self, **kwargs):
        self.written.append(kwargs)

    async def create(self, **kwargs):
        self.written.append(kwargs)

    async def count(self, **_kwargs):
        return 0


def test_a_holder_decides_no_access_request_on_their_workshop_either_way(staffed, monkeypatch):
    """Granting writes the designer team and refusing takes a capture-only foothold off it; both are
    the team's shape, which its inspector and its directors do not decide. Refused before anything
    is written, so the queue row waits for another admin untouched."""
    from app.services import design_workshop_access as access

    row = SimpleNamespace(
        id="r1",
        designWorkshopId="w1",
        requestedById="u-DESIGNER",
        designWorkshop=SimpleNamespace(id="w1", createdById="u-opener", title="T", workshopCode=None),
        requestedBy=_account("DESIGNER"),
        status="PENDING",
    )
    requests = _Delegate(row)
    monkeypatch.setattr(access, "db", SimpleNamespace(designworkshopaccessrequest=requests))
    staffed["held"] = {"u-ADMIN": {"INSPECTOR"}}
    for decision in ("GRANTED", "DENIED"):
        with pytest.raises(HTTPException) as refused:
            asyncio.run(access.decide("r1", decision=decision, note=None, admin=_account("ADMIN")))
        assert refused.value.status_code == 403, decision
        assert refused.value.detail == posts.write_refusal({"INSPECTOR"})
    assert requests.written == [], "a refused decision wrote to the queue row"


def test_a_holder_prints_no_join_card_for_their_workshop(staffed, monkeypatch):
    """A card is the designer team by proxy. Asked after the issuer test, so a caller who may not
    print cards here at all still gets that module's ordinary 404, and before the card exists."""
    from app.services import design_workshop_grants as grants

    workshop = SimpleNamespace(id="w1", createdById="u-opener", deletedAt=None)
    tokens = _Delegate()
    monkeypatch.setattr(
        grants,
        "db",
        SimpleNamespace(designworkshop=_Delegate(workshop), recordaccesstoken=tokens),
    )
    staffed["held"] = {"u-ADMIN": {"REGIONAL_DIRECTOR"}}
    with pytest.raises(HTTPException) as refused:
        asyncio.run(
            grants.mint_grant(
                _account("ADMIN"),
                record_type="DESIGN_WORKSHOP",
                record_id="w1",
                max_uses=5,
                days_valid=None,
                label=None,
            )
        )
    assert refused.value.status_code == 403
    assert refused.value.detail == posts.write_refusal({"REGIONAL_DIRECTOR"})
    assert tokens.written == [], "a card was minted for a refused issuer"


def test_the_write_loader_spares_a_holder_only_where_the_caller_says_so(staffed, monkeypatch):
    """``barred_to_post_holders`` is the one switch, and it is off for the export ledger alone: the
    same holder through the same loader is refused an edit and allowed the ledger row."""
    from app.services import design_workshops

    record = SimpleNamespace(id="w1", createdById="u-opener", deletedAt=None)
    monkeypatch.setattr(design_workshops, "db", SimpleNamespace(designworkshop=_Delegate(record)))
    staffed["held"] = {"u-ADMIN": {"INSPECTOR"}}
    admin = _account("ADMIN")

    with pytest.raises(HTTPException) as refused:
        asyncio.run(design_workshops.load_workshop_or_404("w1", admin, for_edit=True))
    assert refused.value.status_code == 403
    loaded = asyncio.run(
        design_workshops.load_workshop_or_404(
            "w1", admin, for_edit=True, barred_to_post_holders=False
        )
    )
    assert loaded is record
    # And the read path never asked in the first place.
    assert asyncio.run(design_workshops.load_workshop_or_404("w1", admin)) is record


def test_the_designer_directories_leave_the_caller_out_only_for_a_workshop_that_exists(
    monkeypatch,
):
    """``workshopId`` says which door the list feeds. Sent — a viewers PUT or a designer door on a
    workshop that exists, where naming yourself is a 409 — the reader is left out; absent — a create
    form, where the creator may name themselves — the list is exactly what it was."""
    from app.api.routes import (
        design_workshop_oversight as oversight_routes,
        design_workshop_viewers as viewer_routes,
    )

    asked: list[tuple[str, str | None]] = []

    async def _viewers(search=None, *, exclude_user_id=None):
        asked.append(("eligible-viewers", exclude_user_id))
        return {"users": [], "truncated": False}

    async def _designers(*, search=None, include_suspended=False, exclude_user_id=None, **_kw):
        asked.append(("designers", exclude_user_id))
        return []

    monkeypatch.setattr(viewer_routes, "eligible_viewers", _viewers)
    monkeypatch.setattr(oversight_routes, "workshop_capable_accounts", _designers)
    reader = _account("ADMIN")
    for workshop_id in (None, "", "w1"):
        asyncio.run(
            viewer_routes.list_eligible_viewers(
                search=None, workshopId=workshop_id, current_user=reader
            )
        )
        asyncio.run(
            oversight_routes.list_assignable_designers(
                search=None, workshopId=workshop_id, current_user=reader
            )
        )
    assert asked == [
        ("eligible-viewers", None),
        ("designers", None),
        ("eligible-viewers", None),
        ("designers", None),
        ("eligible-viewers", "u-ADMIN"),
        ("designers", "u-ADMIN"),
    ]


def test_the_directory_queries_leave_the_caller_out_inside_the_where(monkeypatch):
    """IN the ``WHERE``, never after the ``take``: dropping a row from a capped answer is how a full
    page stops meaning "there are more", which both directories report on the wire."""
    from app.services import design_workshop_viewers as viewers, designers

    sent: list[dict] = []

    class _Users:
        async def find_many(self, where=None, **_kwargs):
            sent.append(where)
            return []

    async def _roster():
        return [], False

    async def _bars_nobody():
        return []

    monkeypatch.setattr(viewers, "active_roster_emails", _roster)
    monkeypatch.setattr(access_roster, "barred_emails", _bars_nobody)
    monkeypatch.setattr(viewers, "db", SimpleNamespace(user=_Users()))
    monkeypatch.setattr(designers, "db", SimpleNamespace(user=_Users()))

    asyncio.run(viewers.eligible_viewers(exclude_user_id="u-me"))
    asyncio.run(designers.workshop_capable_accounts(exclude_user_id="u-me"))
    asyncio.run(viewers.eligible_viewers())
    asyncio.run(designers.workshop_capable_accounts())
    excluded = [{"id": {"not": "u-me"}} in where["AND"] for where in sent]
    assert excluded == [True, True, False, False]


# ── The record forms: which records a workshop holds is its content too ─────────────────────────


def _serving(monkeypatch, on: dict[str, dict[str, set[str]]]) -> None:
    """``supervisory_posts_among`` answering from ``{workshop id: {user id: posts}}``.

    Per WORKSHOP, unlike ``staffed``, because the question here is which workshop a record is
    leaving: the same administrator may serve on one and be a stranger to the next.
    """

    async def _held(workshop_id, user_ids):
        ids = set(user_ids)
        return {uid: frozenset(p) for uid, p in on.get(workshop_id, {}).items() if uid in ids}

    monkeypatch.setattr(posts, "supervisory_posts_among", _held)


def _file(monkeypatch, data: dict[str, Any], user: Any, *, filed_under: str | None):
    """``assert_payload_workshop`` with the DESTINATION's loader answering from a list.

    Returns ``(refusal or None, the destinations the loader was asked about)``. The loader is
    replaced because its own refusal of a holder is pinned above
    (``test_the_write_loader_spares_a_holder_only_where_the_caller_says_so``) and is the half of a
    filing that was never open; the LEAVE is the half that was.
    """
    from app.services import design_workshops, record_design_workshop as filing

    asked: list[str] = []

    async def _destination(workshop_id, _user, **_kwargs):
        asked.append(workshop_id)

    monkeypatch.setattr(design_workshops, "load_workshop_or_404", _destination)
    try:
        asyncio.run(filing.assert_payload_workshop(data, user, filed_under=filed_under))
    except HTTPException as refusal:
        return refusal, asked
    return None, asked


@pytest.mark.parametrize(
    ("role", "post"),
    [
        ("INSPECTOR", "INSPECTOR"),
        ("ASSISTANT_DIRECTOR", "ASSISTANT_DIRECTOR"),
        ("REGIONAL_DIRECTOR", "REGIONAL_DIRECTOR"),
        ("MINISTRY_ADMIN", "REGIONAL_DIRECTOR"),
        ("ADMIN", "INSPECTOR"),
        ("MASTER_ADMIN", "ASSISTANT_DIRECTOR"),
    ],
)
def test_a_record_form_refuses_a_holder_taking_a_record_out_of_their_workshop(
    monkeypatch, role, post
):
    """An unfile — ``null``, or the ``""`` a truthiness test would wave through — and a move are
    both a LEAVE, and a holder is refused it with the write sentence BEFORE the destination is asked
    anything, so the reason they are given is their post and not somebody else's workshop."""
    _serving(monkeypatch, {"w1": {f"u-{role}": {post}}})
    holder = _account(role)
    for body in ({"designWorkshopId": None}, {"designWorkshopId": ""}, {"designWorkshopId": "w2"}):
        refusal, asked = _file(monkeypatch, body, holder, filed_under="w1")
        assert refusal is not None and refusal.status_code == 403, body
        assert refusal.detail == posts.write_refusal({post}), body
        assert asked == [], f"{body}: the destination was asked before the leave was refused"


def test_a_holder_changes_nothing_on_a_record_filed_under_their_workshop(monkeypatch):
    """THE LEAVE WAS ONLY TWO OF THE SHAPES (2026-10-09). The records filed under a workshop are its
    content, so its inspector is refused EVERY PATCH of one — a field edit that never mentions the
    workshop, and re-sending the workshop it already names, which both clients do on every save —
    and refused before the destination is asked anything."""
    _serving(monkeypatch, {"w1": {"u-ADMIN": {"INSPECTOR"}}})
    holder = _account("ADMIN")
    for body in ({"notes": "Re-measured."}, {"designWorkshopId": "w1"}, {}):
        refusal, asked = _file(monkeypatch, body, holder, filed_under="w1")
        assert refusal is not None and refusal.status_code == 403, body
        assert refusal.detail == posts.write_refusal({"INSPECTOR"}), body
        assert asked == [], body


def test_off_their_workshop_a_holders_record_forms_are_answered_exactly_as_before(monkeypatch):
    """The same administrator, on records filed nowhere or under a workshop they hold nothing on:
    a PATCH that does not mention the workshop asks nothing, re-sending the workshop the row names
    goes to the destination's loader alone as it always did, and unfiles and moves are free."""
    _serving(monkeypatch, {"w1": {"u-ADMIN": {"INSPECTOR"}}})
    holder = _account("ADMIN")
    assert _file(monkeypatch, {"notes": "Re-measured."}, holder, filed_under="w3") == (None, [])
    assert _file(monkeypatch, {"designWorkshopId": "w3"}, holder, filed_under="w3") == (None, ["w3"])
    assert _file(monkeypatch, {"designWorkshopId": None}, holder, filed_under=None) == (None, [])
    assert _file(monkeypatch, {"designWorkshopId": "w2"}, holder, filed_under=None) == (None, ["w2"])
    assert _file(monkeypatch, {"designWorkshopId": None}, holder, filed_under="w3") == (None, [])
    assert _file(monkeypatch, {"designWorkshopId": "w2"}, holder, filed_under="w3") == (None, ["w2"])


def test_everybody_else_takes_a_record_out_and_a_designer_is_asked_nothing(monkeypatch):
    """The workshop has its holder, and they are the only one refused: an administrator serving on
    nothing there, and a professor, edit, unfile and move as before. A designer can hold no post, so
    their save is answered from the role — the hot path costs no query."""
    _serving(monkeypatch, {"w1": {"u-ADMIN": {"INSPECTOR"}}})
    for role in ("MASTER_ADMIN", "MINISTRY_ADMIN", "PROFESSOR", "RESEARCHER"):
        user = _account(role)
        assert _file(monkeypatch, {"notes": "Re-measured."}, user, filed_under="w1") == (None, [])
        assert _file(monkeypatch, {"designWorkshopId": None}, user, filed_under="w1") == (None, [])
        assert _file(monkeypatch, {"designWorkshopId": "w2"}, user, filed_under="w1") == (
            None,
            ["w2"],
        )

    async def _never(*_args, **_kwargs):
        raise AssertionError("a designer's save must not query who serves on the workshop")

    monkeypatch.setattr(posts, "supervisory_posts_among", _never)
    designer = _account("DESIGNER")
    assert _file(monkeypatch, {"designWorkshopId": None}, designer, filed_under="w1") == (None, [])
    assert _file(monkeypatch, {"notes": "Re-measured."}, designer, filed_under="w1") == (None, [])


def test_the_questionnaire_form_patch_refuses_a_holder_everything_and_writes_nothing(monkeypatch):
    """The designer-authored questionnaire is the sixth form: an attached form's sittings print in
    the workshop's report annexure, so the form is the workshop's content. Its Regional Director —
    an ADMIN, whom ``_require_owner`` admits — is refused a detach, a move, a re-send, a rename and a
    deactivation alike, and nothing is written and no destination is asked. Owning the form, or being
    an admin who serves on nothing there, still lets everybody else change it."""
    from app.api.routes import questionnaire_forms as forms
    from app.schemas.questionnaire import QuestionnaireUpdate

    record = SimpleNamespace(id="q1", ownerId="u-opener", designWorkshopId="w1")
    table = _Delegate(record)
    attached_to: list[str] = []

    async def _found(_questionnaire_id, _user):
        return record

    async def _attachable(workshop_id, _user):
        attached_to.append(workshop_id)

    async def _form(_questionnaire_id, **_kwargs):
        return {"id": "q1"}

    monkeypatch.setattr(forms, "_require_questionnaire", _found)
    monkeypatch.setattr(forms, "_require_attachable_workshop", _attachable)
    monkeypatch.setattr(forms, "load_form", _form)
    monkeypatch.setattr(forms, "db", SimpleNamespace(questionnaire=table))
    _serving(monkeypatch, {"w1": {"u-ADMIN": {"REGIONAL_DIRECTOR"}}})

    for body in (
        {"designWorkshopId": None},
        {"designWorkshopId": "w2"},
        {"designWorkshopId": "w1"},
        {"title": "Renamed by its director"},
        {"isActive": False},
    ):
        with pytest.raises(HTTPException) as refused:
            asyncio.run(
                forms.update_questionnaire("q1", QuestionnaireUpdate(**body), _account("ADMIN"))
            )
        assert refused.value.status_code == 403, body
        assert refused.value.detail == posts.write_refusal({"REGIONAL_DIRECTOR"})
    assert table.written == [], "a refused edit wrote to the questionnaire"
    assert attached_to == [], "the destination was asked before the holder was refused"

    # An administrator who serves on nothing there: the attach check answers as before, and a
    # detach writes.
    asyncio.run(
        forms.update_questionnaire(
            "q1", QuestionnaireUpdate(designWorkshopId="w1"), _account("MASTER_ADMIN")
        )
    )
    assert attached_to == ["w1"]
    asyncio.run(
        forms.update_questionnaire(
            "q1", QuestionnaireUpdate(designWorkshopId=None), _account("MASTER_ADMIN")
        )
    )
    assert table.written[-1] == {"where": {"id": "q1"}, "data": {"designWorkshopId": None}}


#: The statements in the record routes that WRITE before the transaction does: a ``Location`` row
#: and, on the artisan form, possibly a craft. A gate below either of them leaves its row behind.
_EARLY_WRITES = ("attach_location(", "resolve_craft_id(")


def _asks_before(source: str, gate: str, *writes: str, name: str) -> None:
    at = source.find(gate)
    assert at != -1, f"{name} no longer asks {gate!r}"
    for write in writes:
        if write in source:
            assert at < source.find(write), f"{name} reaches {write!r} before it asks {gate!r}"


def test_every_record_form_passes_the_workshop_its_row_is_filed_under():
    """``filed_under`` is a REQUIRED keyword, so no route can forget it — but one could pass the
    wrong thing, and ``None`` on a PATCH would quietly reopen every write a holder is refused. So
    each PATCH names its own row's column; each create names ``None``, because a row that does not
    exist yet is filed nowhere; and both ask before ``attach_location`` and the craft lookup, which
    WRITE, and before the transaction that holds the record's own write — a refused save must leave
    no ``Location`` and no craft behind. Held without a database, for the job that has none."""
    import inspect

    from app.api.routes import artisans, processes, products, questionnaire, tools

    for handler, row in (
        (artisans.update_artisan, "artisan"),
        (products.update_product, "product"),
        (processes.update_process, "process"),
        (tools.update_tool, "tool"),
        (questionnaire.update_interview, "interview"),
    ):
        _asks_before(
            inspect.getsource(handler),
            f"assert_payload_workshop(data, current_user, filed_under={row}.designWorkshopId)",
            *_EARLY_WRITES,
            "async with db.tx()",
            name=handler.__name__,
        )
    for handler in (
        artisans.create_artisan,
        products.create_product,
        processes.create_process,
        tools.create_tool,
    ):
        source = inspect.getsource(handler)
        for gate in (
            "assert_payload_workshop(data, current_user, filed_under=None)",
            "enforce_workshop_submission(",
        ):
            _asks_before(source, gate, *_EARLY_WRITES, name=handler.__name__)


def test_every_other_write_to_a_filed_record_asks_about_the_workshop_it_is_filed_under():
    """The doors that are not a PATCH: each DELETE, the interview merge — about BOTH sides, before
    the scope refusal and before its transaction — and the tool's two artisan-link routes, each
    asking with the row's own column before its first write."""
    import inspect

    from app.api.routes import artisans, processes, products, questionnaire, tools

    for handler, row, write in (
        (artisans.delete_artisan, "artisan", "db.artisan.delete("),
        (products.delete_product, "product", "db.productdocumentation.delete("),
        (processes.delete_process, "process", "db.process.delete("),
        (tools.delete_tool, "tool", "db.tooldocumentation.delete("),
        (questionnaire.delete_interview, "interview", "db.questionnaireinterview.delete("),
        (tools.assign_tool_artisans, "tool", "db.toolartisan.create_many("),
        (tools.unassign_tool_artisan, "tool", "db.toolartisan.delete_many("),
    ):
        _asks_before(
            inspect.getsource(handler),
            f"assert_may_write_a_record_filed_under({row}.designWorkshopId, current_user)",
            write,
            name=handler.__name__,
        )

    source = inspect.getsource(questionnaire.merge_interview_into)
    _asks_before(
        source,
        "await assert_may_write_a_record_filed_under(side, current_user)",
        "_merge_scope(source) != _merge_scope(target)",
        "async with db.tx()",
        name="merge_interview_into",
    )
    assert "source.designWorkshopId" in source and "target.designWorkshopId" in source


def test_every_edit_of_a_questionnaire_form_asks_about_the_workshop_it_is_attached_to():
    """The seven owner-gated edits and the sitting PATCH, each before the first thing it writes —
    and the re-upload before it reads the workbook. Starting a sitting and recording answers ask
    through the loader (``_require_recordable_questionnaire``), whose own refusal is pinned above."""
    import inspect

    from app.api.routes import questionnaire_forms as forms

    gate = "_refuse_its_workshops_holder(record, current_user)"
    for handler, write in (
        (forms.reupload_questionnaire, "_read_upload(file, request)"),
        (forms.update_questionnaire, "_require_attachable_workshop("),
        (forms.update_questionnaire, "db.questionnaire.update("),
        (forms.create_section, "db.questionnaireformsection.create("),
        (forms.update_section, "db.questionnaireformsection.update("),
        (forms.create_question, "db.questionnaireformquestion.create("),
        (forms.update_question, "supersede_question("),
        (forms.update_question, "db.questionnaireformquestion.update("),
        (forms.remove_question, "db.questionnaireformquestion.update("),
        (forms.remove_question, "db.questionnaireformquestion.delete("),
        (forms.update_entry, "db.questionnaireformentry.update("),
    ):
        _asks_before(inspect.getsource(handler), gate, write, name=handler.__name__)
    for handler in (forms.create_entry, forms.record_answers):
        assert "_require_recordable_questionnaire(record, current_user)" in (
            inspect.getsource(handler)
        ), handler.__name__


@pytest.mark.parametrize("kind", ["artisan", "product", "tool"])
def test_a_refused_create_mints_no_location_and_no_craft(monkeypatch, kind):
    """The three creates that carry a location, refused by the workshop gate — the holder's 403,
    raised by a stand-in so nothing else is under test — reach neither ``attach_location``, which
    INSERTS a ``Location`` row, nor the craft lookup, which may insert a craft. Before 2026-10-09 the
    gate sat below both, and every refused save left a ``Location`` that nothing references."""
    from app.api.routes import artisans, products, tools

    module, handler = {
        "artisan": (artisans, artisans.create_artisan),
        "product": (products, products.create_product),
        "tool": (tools, tools.create_tool),
    }[kind]
    reached: list[str] = []

    def _writer(name: str):
        async def _write(data, *_args, **_kwargs):
            reached.append(name)
            return data

        return _write

    async def _refused(_data, _user, *, filed_under):
        raise HTTPException(status_code=403, detail=posts.write_refusal({"INSPECTOR"}))

    async def _any_workshop(_user, _workshop_id, **_kwargs):
        return SimpleNamespace()

    monkeypatch.setattr(module, "assert_payload_workshop", _refused)
    monkeypatch.setattr(module, "enforce_workshop_submission", _any_workshop)
    monkeypatch.setattr(module, "attach_location", _writer("attach_location"))
    if hasattr(module, "resolve_craft_id"):
        monkeypatch.setattr(module, "resolve_craft_id", _writer("resolve_craft_id"))
    body = {
        "designWorkshopId": "w1",
        "craftName": "A craft the register lacks",
        "location": {"latitude": 21.19, "longitude": 83.58},
    }
    payload = SimpleNamespace(model_dump=lambda **_kwargs: dict(body), clientKey=None)

    with pytest.raises(HTTPException) as refused:
        asyncio.run(handler(payload, current_user=_account("ADMIN")))
    assert refused.value.status_code == 403
    assert reached == [], f"a refused create reached {reached}"


# ── The media doors: the files a workshop holds are its content too (2026-10-09) ────────────────


class _Table:
    """One fake table: ``find_unique`` by id, ``find_many`` by equality on every key it is given."""

    def __init__(self, *rows: Any) -> None:
        self.rows = list(rows)

    async def find_unique(self, where=None, **_kwargs):
        return next((row for row in self.rows if row.id == where["id"]), None)

    async def find_many(self, where=None, **_kwargs):
        return [
            row
            for row in self.rows
            if all(getattr(row, key, None) == value for key, value in (where or {}).items())
        ]


class _MediaWorld:
    """The tables ``design_workshop_posts`` reads to learn which workshops hold a file, faked."""

    def __init__(self, *, staged: dict[str, set[str]] | None = None, **tables: Any) -> None:
        self.staged = staged or {}
        self.asked: list[tuple[str, list[str], list[str]]] = []
        for name in (
            "artisan",
            "productdocumentation",
            "tooldocumentation",
            "questionnaireinterview",
            "process",
            "processstep",
            "mediafile",
            "dwailayer",
        ):
            setattr(self, name, tables.get(name, _Table()))

    async def query_raw(self, _sql, media_id, entity_keys, field_keys):
        self.asked.append((media_id, entity_keys, field_keys))
        return [{"id": workshop_id} for workshop_id in sorted(self.staged.get(media_id, set()))]


def _file_row(media_id: str = "m1", **columns: Any) -> SimpleNamespace:
    blank = dict.fromkeys(
        (
            "designWorkshopId",
            "linkedRecordType",
            "linkedRecordId",
            "artisanId",
            "productId",
            "toolId",
            "questionnaireInterviewId",
        )
    )
    return SimpleNamespace(id=media_id, **{**blank, **columns})


def test_a_file_belongs_to_every_workshop_that_names_it_by_any_of_five_ways(monkeypatch):
    """Filed there, tagged there, held by a stage there, made into an AI layer there, and hanging off
    a record filed there — every one is a workshop the file's write would change. A soft-deleted
    layer does not count, and the stage scan is asked about the media AND the rich-text fields."""
    from app.services.stage_schema import FieldType, stages

    world = _MediaWorld(
        staged={"m1": {"w-stage"}},
        artisan=_Table(SimpleNamespace(id="a1", designWorkshopId="w-artisan")),
        dwailayer=_Table(
            SimpleNamespace(sourceMediaId="m1", designWorkshopId="w-ai", deletedAt=None),
            SimpleNamespace(sourceMediaId="m1", designWorkshopId="w-gone", deletedAt="2026-10-01"),
        ),
    )
    monkeypatch.setattr(posts, "db", world)
    media = _file_row(
        designWorkshopId="w-filed",
        linkedRecordType="designWorkshop",
        linkedRecordId="w-tag",
        artisanId="a1",
    )
    found = asyncio.run(posts.media_design_workshop_ids(media))
    assert found == {"w-filed", "w-tag", "w-stage", "w-ai", "w-artisan"}

    [(media_id, entity_keys, field_keys)] = world.asked
    assert media_id == "m1"
    wanted = {
        f.key
        for spec in stages()
        for entity in spec.entities
        for f in entity.fields
        if f.type.is_media or f.type is FieldType.RICH_TEXT
    }
    assert set(field_keys) == wanted, "the stage scan does not read every field that holds a file"
    assert entity_keys, "the stage scan was narrowed to no entity at all"


def test_the_record_a_file_hangs_off_is_read_through_every_spelling_and_one_hop(monkeypatch):
    """A step answers through its process, a misc file through its parent file's column and tag,
    the interview link through either spelling — and the typed column before a tag that names a row
    an interview merge has since deleted. A parent that is gone, or a link to a craft, is nothing."""
    world = _MediaWorld(
        process=_Table(SimpleNamespace(id="p1", designWorkshopId="w-process")),
        processstep=_Table(SimpleNamespace(id="s1", processId="p1")),
        questionnaireinterview=_Table(SimpleNamespace(id="i-new", designWorkshopId="w-sitting")),
        mediafile=_Table(
            _file_row(
                "m-parent",
                designWorkshopId="w-parent",
                linkedRecordType="designworkshop",
                linkedRecordId="w-parent-tag",
            )
        ),
    )
    monkeypatch.setattr(posts, "db", world)
    assert asyncio.run(posts.link_filing("processstep", "s1")) == {"w-process"}
    assert asyncio.run(posts.link_filing("misc", "m-parent")) == {"w-parent", "w-parent-tag"}
    assert asyncio.run(posts.link_filing("questionnaireinterview", "i-new")) == {"w-sitting"}
    assert asyncio.run(posts.link_filing("questionnaire", "i-gone")) == set()
    assert asyncio.run(posts.link_filing("craft", "c1")) == set()
    assert asyncio.run(posts.link_filing(None, "p1")) == set()

    merged = _file_row(
        questionnaireInterviewId="i-new", linkedRecordType="questionnaire", linkedRecordId="i-gone"
    )
    assert asyncio.run(posts.media_design_workshop_ids(merged)) == {"w-sitting"}


def test_a_holder_changes_no_file_of_their_workshop_and_everybody_else_is_answered_as_before(
    monkeypatch,
):
    """The media gate is the stage gate's sentence over the file's workshops: the workshop's
    inspector is refused, an administrator serving on nothing there is not, and a relink asks about
    where the file would ARRIVE as well as where it is. A designer can hold no post and is answered
    from the role, before anything is read."""
    _serving(monkeypatch, {"w1": {"u-ADMIN": {"INSPECTOR"}}})
    belongs: dict[str, set[str]] = {"m-held": {"w1", "w9"}, "m-free": {"w9"}}

    async def _workshops_of(media):
        return set(belongs[media.id])

    async def _filed(link_type, record_id):
        return {"w1"} if (link_type, record_id) == ("artisan", "a-held") else set()

    monkeypatch.setattr(posts, "media_design_workshop_ids", _workshops_of)
    monkeypatch.setattr(posts, "link_filing", _filed)
    holder, other = _account("ADMIN"), _account("MASTER_ADMIN")

    for media, relinked_to in (
        (_file_row("m-held"), None),
        (_file_row("m-free"), ("artisan", "a-held")),
    ):
        with pytest.raises(HTTPException) as refused:
            asyncio.run(posts.refuse_a_holders_media_write(media, holder, relinked_to=relinked_to))
        assert refused.value.status_code == 403
        assert refused.value.detail == posts.write_refusal({"INSPECTOR"})
        asyncio.run(posts.refuse_a_holders_media_write(media, other, relinked_to=relinked_to))
    asyncio.run(posts.refuse_a_holders_media_write(_file_row("m-free"), holder))
    asyncio.run(
        posts.refuse_a_holders_media_write(
            _file_row("m-free"), holder, relinked_to=("artisan", "a-loose")
        )
    )

    async def _never(*_args, **_kwargs):
        raise AssertionError("a designer's media write must not read which workshops hold the file")

    monkeypatch.setattr(posts, "media_design_workshop_ids", _never)
    asyncio.run(posts.refuse_a_holders_media_write(_file_row("m-held"), _account("DESIGNER")))


def test_every_media_door_asks_about_the_files_workshops_before_it_writes():
    """Held as source, without a database: each door asks after its own permission check and
    before the first thing it writes or spends — the relink with where the file would arrive."""
    import inspect

    from app.api.routes import design_workshops as workshop_routes, media as media_routes

    gate = "design_workshop_posts.refuse_a_holders_media_write("
    for handler, writes in (
        (media_routes.delete_media, ("db.mediafile.delete(", "delete_object")),
        (media_routes.set_media_transcript, ("db.mediafile.update(",)),
        (media_routes.refine_media_transcript, ("transcription_verdict(", "refine_transcript_text(")),
        (media_routes.transcribe_media_now_route, ("transcribe_media_now(media",)),
        (media_routes.relink_media, ("db.mediafile.update(",)),
        (
            workshop_routes.decide_identity_photograph,
            ("db.mediafile.update(", "delete_object", "db.mediafile.delete("),
        ),
    ):
        source = inspect.getsource(handler)
        _asks_before(source, gate, *writes, name=handler.__name__)
        asked = source[source.find(gate) + len(gate) :].lstrip()
        assert asked.startswith("media, current_user"), f"{handler.__name__} asks about another file"
    assert "relinked_to=(rec_type, payload.linkedRecordId)" in inspect.getsource(
        media_routes.relink_media
    )


def test_the_upload_the_job_retry_and_the_review_edit_ask_before_they_write():
    """The three doors that met the rule later (2026-10-09), held as source without a database.

    The upload asks about the row it is ABOUT to create — after the replay of the caller's own
    earlier upload, which is answered as before, and before the storage HEAD, the ``Location`` row
    and the create. The job retry asks about the job's file before its compare-and-set. The review
    queue's edit asks the record gate or the media gate about the STORED row before its transaction,
    and refuses ``designWorkshopId`` outright."""
    import inspect

    from app.api.routes import media as media_routes, review as review_routes

    gate = "design_workshop_posts.refuse_a_holders_media_write("

    def asked_about(source: str) -> str:
        return source[source.find(gate) + len(gate) :].lstrip()

    complete = inspect.getsource(media_routes.complete_media_upload)
    _asks_before(
        complete,
        gate,
        "_assert_stored_object_within_ceiling(",
        "attach_location(",
        "db.mediafile.create(",
        name="complete_media_upload",
    )
    assert asked_about(complete).startswith("_upload_as_filed(payload), current_user")
    assert complete.find("_finish_pending_media(existing") < complete.find(gate), (
        "the replay of the caller's own upload must be answered as it was"
    )

    retry = inspect.getsource(media_routes.retry_media_processing_job)
    _asks_before(
        retry, gate, "db.mediaprocessingjob.update_many(", name="retry_media_processing_job"
    )
    assert asked_about(retry).startswith("media, current_user")

    edit = inspect.getsource(review_routes.edit_reviewed_record)
    for review_gate in (gate, "assert_may_write_a_record_filed_under("):
        _asks_before(
            edit, review_gate, "async with db.tx()", "record_revision(", name="edit_reviewed_record"
        )
    assert asked_about(edit).startswith("record, reviewer")
    assert "designWorkshopId" in review_routes._NOT_REVIEW_EDITABLE  # noqa: SLF001


def test_an_upload_is_read_as_the_row_it_would_become(monkeypatch):
    """``_upload_as_filed`` hands the media gate the row ``/complete`` is about to write: the
    workshop a designer filed it under and the link tag, with NO id — so the stage and AI-layer
    reads answer nothing without a query, and the tag and the filed record are what is asked."""
    from app.api.routes import media as media_routes
    from app.schemas.media import MediaCompleteRequest

    world = _MediaWorld(artisan=_Table(SimpleNamespace(id="a1", designWorkshopId="w-artisan")))
    monkeypatch.setattr(posts, "db", world)

    def upload(**links: Any) -> Any:
        return media_routes._upload_as_filed(  # noqa: SLF001
            MediaCompleteRequest(
                originalFilename="loom.jpg",
                mediaType="IMAGE",
                mimeType="image/jpeg",
                sizeBytes=1024,
                objectKey="media/u1/loom.jpg",
                **links,
            )
        )

    assert asyncio.run(
        posts.media_design_workshop_ids(
            upload(linkedRecordType="designWorkshop", linkedRecordId="w-tag")
        )
    ) == {"w-tag"}
    assert asyncio.run(
        posts.media_design_workshop_ids(upload(linkedRecordType="artisan", linkedRecordId="a1"))
    ) == {"w-artisan"}
    assert asyncio.run(
        posts.media_design_workshop_ids(upload(designWorkshopId="w-filed"))
    ) == {"w-filed"}
    assert asyncio.run(posts.media_design_workshop_ids(upload())) == set()
    assert world.asked == [], "a row that does not exist yet was looked for in the stages"


# ── The unfiled-records report: a row a design workshop claims is not unfiled (2026-10-09) ────────


def test_the_ladder_reads_no_row_a_design_workshop_claims():
    """The report's candidate reads leave out every record filed under a design workshop and every
    file filed under one or tagged to one — the tag in any letter case, and written so a file with NO
    tag, which is most of what genuinely needs a person, is still read. Held as the reads' shape;
    the database half is ``test_the_unfiled_report_lists_no_row_a_design_workshop_claims``."""
    from app.services import workshop_inference as inference

    unfiled = {"workshopId": None, "designWorkshopId": None}
    assert unfiled == inference._UNFILED_RECORD  # noqa: SLF001
    media = inference._UNFILED_MEDIA  # noqa: SLF001
    assert {key: media[key] for key in unfiled} == unfiled
    assert {"linkedRecordType": None} in media["OR"], "a file with no tag at all would be dropped"
    assert {
        "NOT": {"linkedRecordType": {"equals": "designWorkshop", "mode": "insensitive"}}
    } in media["OR"]

    import inspect

    # Whitespace dropped, so a reflowed call still matches.
    ladder = "".join(inspect.getsource(inference.run_ladder).split())
    for table in (
        "questionnaireinterview",
        "productdocumentation",
        "tooldocumentation",
        "process",
        "artisan",
    ):
        assert f"db.{table}.find_many(where=_UNFILED_RECORD" in ladder, table
    assert "db.mediafile.find_many(where=_UNFILED_MEDIA" in ladder


def test_the_unfiled_doors_ask_about_the_design_workshop_before_they_write():
    """Held as source, without a database: the three routes bind the caller and hand it on; the file
    door asks the holder gate before its write, the discard asks it and then refuses any claimed row
    before it counts, deletes or touches storage, and the bulk map works out which rows to leave
    alone before its first write."""
    import inspect

    from app.api.routes import workshops as workshop_routes
    from app.services import workshop_inference as inference

    for handler in (
        workshop_routes.map_unmapped_records,
        workshop_routes.file_one_unmapped_record,
        workshop_routes.discard_one_unmapped_record,
    ):
        source = inspect.getsource(handler)
        assert "current_user: Any = Depends(require_admin)" in source, handler.__name__
        assert "user=current_user)" in source, f"{handler.__name__} drops the caller"

    filed = inspect.getsource(inference.file_one_unmapped)
    _asks_before(filed, "_refuse_a_holder(", "db.workshop.find_unique(", "update_many(", name="file")
    discarded = inspect.getsource(inference.discard_one_unmapped)
    for gate in ("_refuse_a_holder(", "if claimed:"):
        _asks_before(
            discarded, gate, "_media_kept_by(", "delete_many(", "delete_object", name="discard"
        )
    mapped = inspect.getsource(inference.apply_workshop_mapping)
    _asks_before(mapped, "_rows_held_from(", "update_many(", name="apply_workshop_mapping")


def test_nobody_takes_themselves_off_a_post_and_the_sentence_says_who_can():
    """Rule 1's other half: the sentence names the post and the remedy, in the write refusal's order."""
    assert posts.self_release_refusal({"INSPECTOR"}) == (
        "You are this workshop's inspector, and nobody takes themselves off a post: another "
        "administrator has to take you off. Nothing was changed."
    )
    assert posts.self_release_refusal({"REGIONAL_DIRECTOR", "ASSISTANT_DIRECTOR"}).startswith(
        "You are this workshop's Assistant Director and Regional Director, and nobody"
    )


# ════════════════════════════════════════════════════════════════════════════════════════════════
# 3. THE WHOLE THING, OVER POSTGRES
# ════════════════════════════════════════════════════════════════════════════════════════════════

PASSWORD = "admin-serve-as-password"

#: A stage outside the cover whose field no profile prefill writes — so a save here is AUTHORSHIP.
INTRO_STAGE = "INTRODUCTORY_ADMIN_DOCUMENTATION"
INTRO_ENTITY = "introduction"

#: slug -> (role, display name).
#:
#: ``master`` APPOINTS in every test and is never appointed, so its own lists stay empty. ``opener``
#: OPENS the workshops and has a designer profile, so the opening prefill writes stage 1's cover and
#: stage 3's designer block under its stamp — which is exactly the administrative write that must not
#: make it an author. ``ministryOpener`` is used by one test alone, so the dashboard has a creator
#: nothing else in this module touches.
ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("master", "MASTER_ADMIN", "Appointing Master Admin"),
    ("opener", "ADMIN", "Opening Admin"),
    ("ministry", "MINISTRY_ADMIN", "Serving Ministry Admin"),
    ("admin", "ADMIN", "Serving Admin"),
    ("master2", "MASTER_ADMIN", "Serving Master Admin"),
    ("ministryOpener", "MINISTRY_ADMIN", "Opening Ministry Admin"),
    ("ad", "ASSISTANT_DIRECTOR", "Assistant Director Tier"),
    ("rd", "REGIONAL_DIRECTOR", "Regional Director Tier"),
    ("inspector", "INSPECTOR", "Inspector Tier"),
    ("designer", "DESIGNER", "Rostered Designer"),
)

#: Every record a form files under a design workshop, and the one path its list, create and PATCH
#: share. The questionnaire is the designer-authored form, whose attachment prints its sittings in
#: the workshop's report annexure.
RECORD_PATHS: dict[str, str] = {
    "artisan": "/api/artisans",
    "product": "/api/products",
    "process": "/api/processes",
    "tool": "/api/tools",
    "interview": "/api/questionnaire/interviews",
    "questionnaire": "/api/questionnaires",
}


async def _seed_filed(
    kind: str, *, author: str, workshop_id: str | None, label: str, parent: str
) -> str:
    """One record of ``kind`` written straight to its table, filed under ``workshop_id`` or nowhere.

    Seeded rather than created through the API for the access request's reason below: the subject is
    the PATCH, and a create asks for a coordinate, a district and an Aadhaar number that have
    nothing to do with it. Answers the record's id.
    """
    filed = {"designWorkshopId": workshop_id} if workshop_id else {}
    if kind == "questionnaire":
        form = await db.questionnaire.create(
            data={"title": f"Form {label}", "ownerId": author, **filed}
        )
        return form.id
    table, columns = {
        "artisan": (db.artisan, {"name": f"Artisan {label}", "place": "Barpali"}),
        "product": (
            db.productdocumentation,
            {
                "craftName": "Sambalpuri Ikat",
                "place": "Barpali",
                "artisanName": f"Artisan {label}",
                "productName": f"Ikat sari {label}",
            },
        ),
        "process": (db.process, {"name": f"Tie and dye {label}", "productId": parent}),
        "tool": (
            db.tooldocumentation,
            {
                "craftName": "Sambalpuri Ikat",
                "place": "Barpali",
                "artisanName": f"Artisan {label}",
                "toolkitName": f"Pit loom {label}",
            },
        ),
        "interview": (db.questionnaireinterview, {"title": f"Sitting {label}"}),
    }[kind]
    row = await table.create(data={**columns, "createdById": author, **filed})
    return row.id


def _first_field_of(kind: Any) -> tuple[str, str, str]:
    """``(stage key, entity key, field key)`` of the registry's first field of ``kind``."""
    from app.services.stage_schema import stages

    for spec in stages():
        for entity in spec.entities:
            for field in entity.fields:
                if field.type is kind:
                    return spec.key, entity.key, field.key
    raise AssertionError(f"the registry has no {kind} field")


async def _seed_media(people: dict[str, Any], tag: str) -> dict[str, str]:
    """The files of two workshops, for the media doors (2026-10-09).

    ``held`` gets one file for every way a file belongs to a workshop — tagged to it, held by an
    IMAGE field of its stage, placed inside a RICH_TEXT field of its stage, filed under it, hanging
    off an artisan filed under it — and one the Ministry Admin uploaded, for the uploader who is
    also a holder. ``free``, where nobody serves, gets the controls. Stage rows carry no author, so
    nobody here is an author of either workshop and anybody may be appointed to them.
    """
    from app.services.stage_schema import FieldType
    from prisma import Json

    author = people["master"].id
    held = await db.designworkshop.create(
        data={"title": f"Files while serving {tag}", "createdById": people["opener"].id}
    )
    free = await db.designworkshop.create(
        data={"title": f"Files nobody serves on {tag}", "createdById": people["opener"].id}
    )
    pictured = await db.artisan.create(
        data={
            "name": f"Pictured artisan {tag}",
            "place": "Barpali",
            "createdById": author,
            "designWorkshopId": held.id,
        }
    )
    unfiled = await db.artisan.create(
        data={"name": f"Unfiled artisan {tag}", "place": "Barpali", "createdById": author}
    )

    async def upload(label: str, *, kind: str = "IMAGE", by: str = author, **links: Any) -> str:
        row = await db.mediafile.create(
            data={
                "originalFilename": f"{label}.{'jpg' if kind == 'IMAGE' else 'm4a'}",
                "mediaType": kind,
                "mimeType": "image/jpeg" if kind == "IMAGE" else "audio/mp4",
                "sizeBytes": 2048,
                "bucket": "test-bucket",
                "objectKey": f"media/{by}/serve-as-{tag}-{label}",
                "uploadedById": by,
                **links,
            }
        )
        return row.id

    def tagged(workshop: Any) -> dict[str, str]:
        return {"linkedRecordType": "designWorkshop", "linkedRecordId": workshop.id}

    transcript = {"transcriptText": "As the artisan said it.", "transcriptStatus": "COMPLETED"}
    files = {
        "tagged": await upload("tagged", **tagged(held)),
        "staged": await upload("staged"),
        "rich": await upload("rich"),
        "filed": await upload("filed", kind="AUDIO", designWorkshopId=held.id, **transcript),
        "onRecord": await upload(
            "on-record", linkedRecordType="artisan", linkedRecordId=pictured.id, artisanId=pictured.id
        ),
        "ministryOwn": await upload("ministry-own", by=people["ministry"].id, **tagged(held)),
        "freeTagged": await upload("free-tagged", **tagged(free)),
        "freeMoved": await upload("free-moved", **tagged(free)),
        "freeAudio": await upload("free-audio", kind="AUDIO", designWorkshopId=free.id, **transcript),
        "freeKept": await upload("free-kept", **tagged(free)),
        "freeDeleted": await upload("free-deleted", **tagged(free)),
    }
    stage, entity, field = _first_field_of(FieldType.IMAGE)
    await db.dwstageentry.create(
        data={
            "designWorkshopId": held.id,
            "stageKey": stage,
            "entityKey": entity,
            "ordinal": 0,
            "data": Json({field: files["staged"]}),
        }
    )
    stage, entity, field = _first_field_of(FieldType.RICH_TEXT)
    placed = {"blocks": [{"kind": "IMAGE", "media": files["rich"], "spans": ["The seam."]}]}
    await db.dwstageentry.create(
        data={
            "designWorkshopId": held.id,
            "stageKey": stage,
            "entityKey": entity,
            "ordinal": 0,
            "data": Json({field: placed}),
        }
    )
    return {"held": held.id, "free": free.id, "pictured": pictured.id, "unfiled": unfiled.id, **files}


#: The review queue's record types, the kind ``_seed_filed`` writes for each, and one field its edit
#: may correct. A file is the seventh: its caption.
REVIEWED: dict[str, tuple[str, dict[str, str]]] = {
    "artisan": ("artisan", {"notes": "Corrected from the review queue."}),
    "product": ("product", {"remarks": "Corrected from the review queue."}),
    "process": ("process", {"notes": "Corrected from the review queue."}),
    "tool": ("tool", {"remarks": "Corrected from the review queue."}),
    "questionnaire": ("interview", {"notes": "Corrected from the review queue."}),
    "media": ("media", {"caption": "Corrected from the review queue."}),
}


async def _seed_the_later_doors(people: dict[str, Any], tag: str, *, parent: str) -> dict[str, Any]:
    """Two workshops — ``held``, where the tests appoint a holder, and ``free``, where nobody
    serves — and under each: a record of every reviewable kind and a photograph, all by the DESIGNER
    (below every administrator, so the review edit's own rank gate admits each of them), and a
    recording with a FAILED transcription job on it, for the retry.
    """
    author = people["designer"].id
    doors: dict[str, Any] = {}
    for place in ("held", "free"):
        workshop = await db.designworkshop.create(
            data={"title": f"The later doors, {place} {tag}", "createdById": people["opener"].id}
        )
        doors[place] = workshop.id
    for review_type, (kind, _fields) in REVIEWED.items():
        at: dict[str, str] = {}
        for place in ("held", "free"):
            if kind == "media":
                row = await db.mediafile.create(
                    data={
                        "originalFilename": f"reviewed-{place}.jpg",
                        "mediaType": "IMAGE",
                        "mimeType": "image/jpeg",
                        "sizeBytes": 2048,
                        "bucket": "test-bucket",
                        "objectKey": f"media/{author}/serve-as-{tag}-reviewed-{place}",
                        "uploadedById": author,
                        "designWorkshopId": doors[place],
                    }
                )
                at[place] = row.id
            else:
                at[place] = await _seed_filed(
                    kind,
                    author=author,
                    workshop_id=doors[place],
                    label=f"reviewed {review_type} {place} {tag}",
                    parent=parent,
                )
        doors[review_type] = at
    jobs: dict[str, str] = {}
    for place in ("held", "free"):
        recording = await db.mediafile.create(
            data={
                "originalFilename": f"retried-{place}.m4a",
                "mediaType": "AUDIO",
                "mimeType": "audio/mp4",
                "sizeBytes": 2048,
                "bucket": "test-bucket",
                "objectKey": f"media/{author}/serve-as-{tag}-retried-{place}",
                "uploadedById": author,
                "designWorkshopId": doors[place],
            }
        )
        job = await db.mediaprocessingjob.create(
            data={
                "jobType": "TRANSCRIPTION",
                "status": "FAILED",
                "mediaFileId": recording.id,
                "requestedById": author,
                "attempts": 3,
                "maxAttempts": 3,
                "error": "The provider was unreachable.",
            }
        )
        jobs[place] = job.id
    doors["jobs"] = jobs
    return doors


async def _seed_the_unfiled(people: dict[str, Any], tag: str, *, media: dict[str, str]) -> dict:
    """The unfiled-records report's doors (2026-10-09): one design workshop where the tests appoint
    a holder, one crafts workshop to file things under, and rows with NO crafts workshop — the ones
    the report reads — that the design workshop claims by every way there is, beside rows nothing
    claims.

    Two things are ASKED here, inside the seed's connection, and recorded for the tests: which of
    these rows the real ladder lists, and the batched holder question
    (``design_workshop_posts.media_held_among``) beside the file-by-file one
    (``media_design_workshop_ids``) over every file this module seeds, ``_seed_media``'s included.
    """
    from datetime import UTC, datetime

    from app.services import workshop_inference
    from app.services.stage_schema import FieldType
    from prisma import Json

    author = people["master"].id
    held = await db.designworkshop.create(
        data={"title": f"Unfiled while serving {tag}", "createdById": people["opener"].id}
    )
    crafts = await db.workshop.create(
        data={
            "title": f"Crafts workshop for the unfiled {tag}",
            "date": datetime(2026, 3, 2, tzinfo=UTC),
            "place": "Barpali",
            "createdById": author,
        }
    )
    ids: dict[str, Any] = {"held": held.id, "heldTitle": held.title, "crafts": crafts.id}

    async def record(kind: str, name: str, *, filed: bool) -> str:
        return await _seed_filed(
            kind,
            author=author,
            workshop_id=held.id if filed else None,
            label=f"unfiled {name} {tag}",
            parent="",
        )

    ids["filedArtisan"] = await record("artisan", "filedArtisan", filed=True)
    ids["looseArtisan"] = await record("artisan", "looseArtisan", filed=False)
    for name, filed in (
        ("filedProduct", True),
        ("mapRecordHeld", True),
        ("parentHeld", True),
        ("looseProduct", False),
        ("looseProductHolder", False),
        ("looseDiscard", False),
        ("mapProduct", False),
    ):
        ids[name] = await record("product", name, filed=filed)

    async def upload(label: str, **links: Any) -> str:
        row = await db.mediafile.create(
            data={
                "originalFilename": f"{label}.jpg",
                "mediaType": "IMAGE",
                "mimeType": "image/jpeg",
                "sizeBytes": 2048,
                "bucket": "test-bucket",
                "objectKey": f"media/{author}/serve-as-{tag}-unfiled-{label}",
                "uploadedById": author,
                **links,
            }
        )
        return row.id

    files = {
        # What the ladder's reads now leave out: the column, and the tag in either spelling.
        "tagged": await upload("tagged", linkedRecordType="designWorkshop", linkedRecordId=held.id),
        "taggedLower": await upload(
            "tagged-lower", linkedRecordType="designworkshop", linkedRecordId=held.id
        ),
        "filed": await upload("filed", designWorkshopId=held.id),
        # What only the row-by-row question finds: a stage entry, and the record it hangs off.
        "staged": await upload("staged"),
        "onRecord": await upload(
            "on-record",
            linkedRecordType="artisan",
            linkedRecordId=ids["filedArtisan"],
            artisanId=ids["filedArtisan"],
        ),
        # Nothing claims these: a tag naming an artisan filed nowhere, and no tag at all.
        "otherTag": await upload(
            "other-tag", linkedRecordType="artisan", linkedRecordId=ids["looseArtisan"]
        ),
        "loose": await upload("loose"),
        "looseHolder": await upload("loose-holder"),
        # The bulk map's two: one hanging off a product filed under the held workshop, one free.
        "mapHeld": await upload(
            "map-held",
            linkedRecordType="product",
            linkedRecordId=ids["parentHeld"],
            productId=ids["parentHeld"],
        ),
        "mapFree": await upload("map-free"),
    }
    ids.update(files)
    stage, entity, field = _first_field_of(FieldType.IMAGE)
    await db.dwstageentry.create(
        data={
            "designWorkshopId": held.id,
            "stageKey": stage,
            "entityKey": entity,
            "ordinal": 0,
            "data": Json({field: files["staged"]}),
        }
    )

    run = await workshop_inference.run_ladder()
    listed = {row.id for plan in run.plans.values() for row in plan.rows}
    ids["listed"] = {
        name: ids[name] in listed
        for name in (
            *files,
            "filedArtisan",
            "looseArtisan",
            "filedProduct",
            "looseProduct",
        )
    }

    # A SET OF IDS, not a merged dict: both seeds name files "tagged", "filed", "staged" and
    # "onRecord", and a dict merge would quietly keep only one of each pair.
    seeded = {*files.values(), *(media[key] for key in media if key not in _SEED_MEDIA_PLACES)}
    rows = await db.mediafile.find_many(where={"id": {"in": sorted(seeded)}})
    held_set = {held.id, media["held"]}
    by_file = {row.id: await posts.media_design_workshop_ids(row) for row in rows}
    ids["parity"] = {
        "fileByFile": sorted(mid for mid, found in by_file.items() if found & held_set),
        "batched": sorted(await posts.media_held_among(rows, held_set)),
        "asked": len(rows),
    }
    return ids


#: The keys of ``_seed_media``'s answer that name workshops and artisans rather than files.
_SEED_MEDIA_PLACES = frozenset({"held", "free", "pictured", "unfiled"})


@pytest.fixture(scope="module")
def world():
    """Every account, before the app starts, in a private loop. See the module docstring."""
    from fastapi.testclient import TestClient

    from app.main import app

    tag = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"serve-as-{slug.lower()}-{tag}@example.org"

    async def seed() -> dict[str, Any]:
        await db.connect()
        try:
            people: dict[str, Any] = {}
            for slug, role, name in ACCOUNTS:
                people[slug] = await db.user.create(
                    data={
                        "email": address(slug),
                        "name": f"{name} {tag}",
                        "role": role,
                        "passwordHash": hash_password(PASSWORD),
                    }
                )
            await db.designerroster.create(
                data={
                    "email": address("designer"),
                    "fullName": "Rostered Designer",
                    "institution": "Directorate of Handicrafts",
                    "isActive": True,
                    "addedById": people["master"].id,
                }
            )
            await db.designerprofile.create(
                data={
                    "user": {"connect": {"id": people["opener"].id}},
                    "displayName": f"Opening Admin {tag}",
                    "biography": "Twelve years of administration, none of it fieldwork.",
                }
            )
            # ONE WORKSHOP WITH A DESIGNER WAITING TO JOIN IT, seeded rather than filed through the
            # API: the admin queue is cross-workshop, oldest first and capped, so on a database
            # other suites have filled a request filed here could sit past the cut and be unfindable.
            asked = await db.designworkshop.create(
                data={"title": f"Asked to join {tag}", "createdById": people["opener"].id}
            )
            request = await db.designworkshopaccessrequest.create(
                data={
                    "designWorkshopId": asked.id,
                    "requestedById": people["designer"].id,
                    "source": "MANUAL",
                }
            )
            # RECORDS FILED UNDER WORKSHOPS, for the record forms' doors. Every kind gets three
            # sets — one per test that reads them, so none depends on another's order — and each
            # set is a workshop where the test appoints a holder (``held``), one where nobody serves
            # (``free``), and a record filed under each plus one filed nowhere. ``master`` authors
            # them all: it holds no post anywhere, and its records are ones every administrator tier
            # may otherwise edit.
            author = people["master"].id
            craft = await db.craft.create(data={"name": f"Serve-as ikat {tag}"})
            parent = await db.productdocumentation.create(
                data={
                    "craftName": "Sambalpuri Ikat",
                    "place": "Barpali",
                    "artisanName": f"Parent artisan {tag}",
                    "productName": f"Parent sari {tag}",
                    "createdById": author,
                }
            )
            filing: dict[str, dict[str, dict[str, str]]] = {}
            for kind in RECORD_PATHS:
                for purpose in ("refused", "kept", "edited"):
                    ids: dict[str, str] = {}
                    for place in ("held", "free"):
                        workshop = await db.designworkshop.create(
                            data={
                                "title": f"Files a {kind}, {purpose}, {place} {tag}",
                                "createdById": people["opener"].id,
                            }
                        )
                        ids[place] = workshop.id
                    placed = [("atHeld", ids["held"]), ("atFree", ids["free"]), ("loose", None)]
                    if kind == "interview" and purpose == "edited":
                        # Two sittings on each side, for the merge: either side in a held workshop
                        # is refused, and a pair where nobody serves is the control.
                        placed += [
                            ("mergeHeld", ids["held"]),
                            ("mergeFree", ids["free"]),
                            ("mergeFreeToo", ids["free"]),
                        ]
                    for name, workshop_id in placed:
                        ids[name] = await _seed_filed(
                            kind,
                            author=author,
                            workshop_id=workshop_id,
                            label=f"{purpose} {name} {tag}",
                            parent=parent.id,
                        )
                    filing.setdefault(kind, {})[purpose] = ids
            media = await _seed_media(people, tag)
            return {
                "people": people,
                "tag": tag,
                "askedWorkshop": asked.id,
                "accessRequest": request.id,
                "craft": craft.id,
                "parentProduct": parent.id,
                "filing": filing,
                "media": media,
                "doors": await _seed_the_later_doors(people, tag, parent=parent.id),
                "unfiled": await _seed_the_unfiled(people, tag, media=media),
            }
        finally:
            await db.disconnect()

    seeded = asyncio.run(seed())
    with TestClient(app) as client:
        seeded["client"] = client
        yield seeded


def _headers(world: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(world['people'][slug].id)}"}


def _id(world: dict[str, Any], slug: str | None) -> str | None:
    return world["people"][slug].id if slug else None


def _open(world: dict[str, Any], title: str) -> str:
    """A workshop OPENED by ``opener`` with cover values, so the opening prefill writes stages."""
    response = world["client"].post(
        "/api/design-workshops",
        json={
            "title": f"{title} {world['tag']}",
            "craftName": "Sambalpuri Ikat",
            "clusterName": "Barpali",
        },
        headers=_headers(world, "opener"),
    )
    assert response.status_code == 201, response.text
    return response.json()["id"]


def _officers(world, workshop_id, *, as_slug="master", **slots):
    body: dict[str, Any] = {}
    if "ad" in slots:
        body["assistantDirectorId"] = _id(world, slots["ad"])
    if "rd" in slots:
        body["regionalDirectorId"] = _id(world, slots["rd"])
    return world["client"].put(
        f"/api/design-workshop-oversight/{workshop_id}",
        json=body,
        headers=_headers(world, as_slug),
    )


def _inspectors(world, workshop_id, slugs, *, as_slug="master"):
    return world["client"].put(
        f"/api/design-workshop-inspections/{workshop_id}/inspectors",
        json={"userIds": [_id(world, s) for s in slugs]},
        headers=_headers(world, as_slug),
    )


def _designers(world, workshop_id, slugs, *, as_slug="master"):
    return world["client"].put(
        f"/api/design-workshop-oversight/{workshop_id}/designers",
        json={"userIds": [_id(world, s) for s in slugs]},
        headers=_headers(world, as_slug),
    )


def _viewers(world, workshop_id, slugs, *, as_slug="master"):
    return world["client"].put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [_id(world, s) for s in slugs]},
        headers=_headers(world, as_slug),
    )


def _write(world, workshop_id, slug, text="Revive the Barpali ikat motifs for a new market."):
    """A stage save outside the cover, by ``slug``: the act that makes somebody an AUTHOR."""
    return world["client"].put(
        f"/api/design-workshops/{workshop_id}/stages/{INTRO_STAGE}",
        json={
            "entries": [{"entityKey": INTRO_ENTITY, "data": {"purpose": text}, "merge": True}],
            "replaceCollections": False,
        },
        headers=_headers(world, slug),
    )


def _listed(world, path, slug) -> list[str]:
    response = world["client"].get(path, headers=_headers(world, slug))
    assert response.status_code == 200, response.text
    return [item["id"] for item in response.json()["items"]]


@needs_db
def test_every_administrator_tier_serves_in_every_supervisory_post(world):
    """Each of the three tiers as Assistant Director, as Regional Director and as inspector, on three
    workshops — and each then reads exactly that workshop on the surface of the post it holds."""
    rotation = (
        ("ministry", "admin", "master2"),
        ("admin", "master2", "ministry"),
        ("master2", "ministry", "admin"),
    )
    for n, (ad, rd, inspector) in enumerate(rotation):
        workshop_id = _open(world, f"Rotation {n}")
        appointed = _officers(world, workshop_id, ad=ad, rd=rd)
        assert appointed.status_code == 200, appointed.text
        assert {row["capacity"]: row["userId"] for row in appointed.json()["oversight"]} == {
            "ASSISTANT_DIRECTOR": _id(world, ad),
            "REGIONAL_DIRECTOR": _id(world, rd),
        }
        assigned = _inspectors(world, workshop_id, [inspector])
        assert assigned.status_code == 200, assigned.text

        for officer in (ad, rd):
            assert workshop_id in _listed(world, "/api/design-workshop-oversight/assigned", officer)
            read = world["client"].get(
                f"/api/design-workshop-oversight/assigned/{workshop_id}",
                headers=_headers(world, officer),
            )
            assert read.status_code == 200, read.text
            assert read.json()["readOnly"] is True
        assert workshop_id in _listed(world, "/api/design-workshop-inspections", inspector)
        read = world["client"].get(
            f"/api/design-workshop-inspections/{workshop_id}", headers=_headers(world, inspector)
        )
        assert read.status_code == 200, read.text
        assert read.json()["readOnly"] is True


@needs_db
def test_every_administrator_tier_is_named_a_designer_and_then_writes(world):
    """The MINISTRY_ADMIN is the one that proves something: the two platform tiers write every
    workshop through the admin arm, and a ministry admin writes this one only through the row."""
    workshop_id = _open(world, "Team of administrators")
    before = _write(world, workshop_id, "ministry")
    assert before.status_code == 404, "a ministry admin wrote a workshop nobody had put them on"

    named = _designers(world, workshop_id, ["ministry", "admin", "master2"])
    assert named.status_code == 200, named.text
    assert {row["userId"] for row in named.json()["designers"]} == {
        _id(world, s) for s in ("ministry", "admin", "master2")
    }
    for slug in ("ministry", "admin", "master2"):
        assert _write(world, workshop_id, slug).status_code == 200, slug


@needs_db
def test_nobody_appoints_themselves_to_any_of_the_four_posts(world):
    workshop_id = _open(world, "Self appointment")
    for response in (
        _officers(world, workshop_id, ad="master"),
        _inspectors(world, workshop_id, ["master"]),
        _designers(world, workshop_id, ["master"]),
        _viewers(world, workshop_id, ["master"]),
    ):
        assert response.status_code == 409, response.text
        assert "nobody appoints themselves" in response.json()["detail"]

    staffing = world["client"].get(
        f"/api/design-workshop-oversight/{workshop_id}", headers=_headers(world, "master")
    ).json()
    assert staffing["oversight"] == [] and staffing["designers"] == [], "a refusal wrote anyway"
    panel = world["client"].get(
        f"/api/design-workshop-inspections/{workshop_id}/inspectors",
        headers=_headers(world, "master"),
    ).json()
    assert panel["inspectors"] == []


@needs_db
def test_one_person_never_holds_both_director_posts_on_one_workshop(world):
    workshop_id = _open(world, "Both directors")
    both = _officers(world, workshop_id, ad="ministry", rd="ministry")
    assert both.status_code == 409, both.text
    assert "both the Assistant Director and the Regional Director" in both.json()["detail"]

    assert _officers(world, workshop_id, ad="ministry").status_code == 200
    later = _officers(world, workshop_id, rd="ministry")
    assert later.status_code == 409, "the second slot was taken in a second save"

    # A SWAP is judged on where it lands: the AD becomes the RD while somebody else takes AD.
    swapped = _officers(world, workshop_id, ad="admin", rd="ministry")
    assert swapped.status_code == 200, swapped.text
    assert {row["capacity"]: row["userId"] for row in swapped.json()["oversight"]} == {
        "ASSISTANT_DIRECTOR": _id(world, "admin"),
        "REGIONAL_DIRECTOR": _id(world, "ministry"),
    }


@needs_db
def test_an_inspector_never_supervises_the_workshop_and_a_supervisor_never_inspects_it(world):
    workshop_id = _open(world, "Inspector and supervisor")
    assert _inspectors(world, workshop_id, ["admin"]).status_code == 200

    as_director = _officers(world, workshop_id, rd="admin")
    assert as_director.status_code == 409, as_director.text
    assert "inspects this workshop" in as_director.json()["detail"]

    assert _officers(world, workshop_id, ad="ministry").status_code == 200
    as_inspector = _inspectors(world, workshop_id, ["admin", "ministry"])
    assert as_inspector.status_code == 409, as_inspector.text
    assert "is this workshop's Assistant Director, so they cannot also inspect it" in (
        as_inspector.json()["detail"]
    )
    panel = world["client"].get(
        f"/api/design-workshop-inspections/{workshop_id}/inspectors",
        headers=_headers(world, "master"),
    ).json()
    assert [row["userId"] for row in panel["inspectors"]] == [_id(world, "admin")]


@needs_db
def test_an_author_never_inspects_or_supervises_the_workshop(world):
    workshop_id = _open(world, "Authored")
    assert _designers(world, workshop_id, ["ministry"]).status_code == 200
    for response in (
        _inspectors(world, workshop_id, ["ministry"]),
        _officers(world, workshop_id, ad="ministry"),
    ):
        assert response.status_code == 409, response.text
        assert "holds designer access to this workshop" in response.json()["detail"]

    # A PLATFORM ADMIN NEEDS NO VIEWER ROW TO WRITE — so their authorship is the stages they wrote.
    assert _write(world, workshop_id, "master2").status_code == 200
    stage_writer = _inspectors(world, workshop_id, ["master2"])
    assert stage_writer.status_code == 409, stage_writer.text
    assert "has written this workshop's stages" in stage_writer.json()["detail"]


@needs_db
def test_opening_a_workshop_is_not_authoring_it(world):
    """``opener`` created these workshops, and the opening wrote stage 1's cover and stage 3's
    designer block under its stamp. Neither is authorship — an administrator who opened a workshop
    and wrote none of it may inspect or supervise it — and once they do, the creator column no
    longer lets them write it."""
    inspected = _open(world, "Opened, then inspected")
    appointed = _inspectors(world, inspected, ["opener"])
    assert appointed.status_code == 200, appointed.text

    supervised = _open(world, "Opened, then supervised")
    assert _officers(world, supervised, ad="opener").status_code == 200

    refused = _write(world, inspected, "opener")
    assert refused.status_code == 403, refused.text
    assert "You are this workshop's inspector" in refused.json()["detail"]


@needs_db
def test_a_post_holder_is_refused_designer_access_to_the_workshop(world):
    workshop_id = _open(world, "Post holder as designer")
    assert _inspectors(world, workshop_id, ["admin"]).status_code == 200
    assert _officers(world, workshop_id, ad="ministry").status_code == 200

    for response in (
        _viewers(world, workshop_id, ["admin"]),
        _designers(world, workshop_id, ["admin"]),
        _viewers(world, workshop_id, ["ministry"]),
        _designers(world, workshop_id, ["ministry"]),
    ):
        assert response.status_code == 409, response.text
        assert "cannot also be given designer access" in response.json()["detail"]


@needs_db
def test_a_post_holder_cannot_write_the_workshop_even_through_the_admin_route(world):
    """An ADMIN and a MASTER_ADMIN pass the loader on role alone. Holding a post takes that one
    workshop's write away from them — stage saves, the header and the delete — and giving the post up
    gives it back."""
    workshop_id = _open(world, "Read only while serving")
    assert _inspectors(world, workshop_id, ["admin"]).status_code == 200
    assert _officers(world, workshop_id, ad="master2").status_code == 200

    stage = _write(world, workshop_id, "admin")
    assert stage.status_code == 403, stage.text
    assert "You are this workshop's inspector" in stage.json()["detail"]
    header = world["client"].patch(
        f"/api/design-workshops/{workshop_id}",
        json={"title": "Renamed by its inspector"},
        headers=_headers(world, "admin"),
    )
    assert header.status_code == 403, header.text
    delete = world["client"].delete(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "admin")
    )
    assert delete.status_code == 403, delete.text

    supervisor = _write(world, workshop_id, "master2")
    assert supervisor.status_code == 403, supervisor.text
    assert "You are this workshop's Assistant Director" in supervisor.json()["detail"]

    # Still readable on the ordinary read, which is the half the post does not take away.
    read = world["client"].get(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "admin")
    )
    assert read.status_code == 200, read.text

    assert _inspectors(world, workshop_id, []).status_code == 200
    assert _officers(world, workshop_id, ad=None).status_code == 200
    assert _write(world, workshop_id, "admin").status_code == 200
    assert _write(world, workshop_id, "master2").status_code == 200


@needs_db
def test_a_post_holder_cannot_import_an_artisan_list_into_the_workshop(world):
    """The oversight screen's artisan-list import files stage-3 participant rows through its own
    loader, so it asks the same write gate itself — before the workbook is read. An assigner who
    serves on nothing here goes past that gate to the next check, the missing venue location, which
    is what shows the refusal is the POST and not the role."""
    workshop_id = _open(world, "Artisans while serving")
    assert _officers(world, workshop_id, rd="ministry").status_code == 200

    def upload(slug: str):
        return world["client"].post(
            f"/api/design-workshop-oversight/{workshop_id}/artisans/upload",
            files={"file": ("artisans.xlsx", b"never read", "application/octet-stream")},
            headers=_headers(world, slug),
        )

    refused = upload("ministry")
    assert refused.status_code == 403, refused.text
    assert "You are this workshop's Regional Director" in refused.json()["detail"]

    passed_the_gate = upload("master")
    assert passed_the_gate.status_code == 422, passed_the_gate.text
    assert "no venue location" in passed_the_gate.json()["detail"]


@needs_db
def test_the_three_tiers_serve_exactly_as_they_did(world):
    workshop_id = _open(world, "The tiers as before")
    assert _officers(world, workshop_id, ad="ad", rd="rd").status_code == 200
    assert _inspectors(world, workshop_id, ["inspector"]).status_code == 200
    assert _designers(world, workshop_id, ["designer"]).status_code == 200

    for officer in ("ad", "rd"):
        assert workshop_id in _listed(world, "/api/design-workshop-oversight/assigned", officer)
    assert workshop_id in _listed(world, "/api/design-workshop-inspections", "inspector")
    assert _write(world, workshop_id, "designer").status_code == 200

    # And their limits: an inspector writes nothing, a directorate officer with no viewer row cannot
    # open the workshop on the designer's side, and neither tier fits the other's post.
    assert _write(world, workshop_id, "inspector").status_code == 403
    assert _write(world, workshop_id, "ad").status_code == 404
    other = _open(world, "The tiers in the wrong post")
    wrong_slot = _officers(world, other, ad="rd")
    assert wrong_slot.status_code == 422, wrong_slot.text
    wrong_panel = _inspectors(world, other, ["ad"])
    assert wrong_panel.status_code == 422, wrong_panel.text


@needs_db
def test_an_administrator_holding_no_post_sees_two_empty_lists_and_a_designer_is_refused(world):
    """The surfaces open to whoever MAY hold a post, and what they show is the rows — for ``master``,
    who appoints in every test here and is appointed in none, nothing at all."""
    assert _listed(world, "/api/design-workshop-inspections", "master") == []
    assert _listed(world, "/api/design-workshop-oversight/assigned", "master") == []
    for path in ("/api/design-workshop-inspections", "/api/design-workshop-oversight/assigned"):
        refused = world["client"].get(path, headers=_headers(world, "designer"))
        assert refused.status_code == 403, refused.text


@needs_db
def test_the_pickers_offer_the_administrator_tiers_and_never_the_person_appointing(world):
    tag = world["tag"]

    def offered(path: str) -> dict[str, dict[str, Any]]:
        response = world["client"].get(
            path, params={"search": tag}, headers=_headers(world, "master")
        )
        assert response.status_code == 200, response.text
        return {row["id"]: row for row in response.json()["users"]}

    officers = offered("/api/design-workshop-oversight/officers")
    for slug in ("ministry", "admin", "master2", "opener", "ministryOpener", "ad", "rd"):
        assert _id(world, slug) in officers, slug
    for slug in ("master", "inspector", "designer"):
        assert _id(world, slug) not in officers, slug
    both = ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"]
    assert officers[_id(world, "ministry")]["capacities"] == both
    assert officers[_id(world, "admin")]["capacities"] == both
    assert officers[_id(world, "ad")]["capacities"] == ["ASSISTANT_DIRECTOR"]
    assert officers[_id(world, "rd")]["capacities"] == ["REGIONAL_DIRECTOR"]

    inspectors = offered("/api/design-workshop-inspections/eligible-inspectors")
    for slug in ("inspector", "ministry", "admin", "master2", "opener"):
        assert _id(world, slug) in inspectors, slug
    for slug in ("master", "ad", "rd", "designer"):
        assert _id(world, slug) not in inspectors, slug

    # THE DESIGNER PICKERS OFFER WHAT THE VIEWER WRITE ACCEPTS — the directorate tiers included.
    for path in ("/api/design-workshops/eligible-viewers", "/api/design-workshop-oversight/designers"):
        designers = offered(path)
        for slug in ("ministry", "ad", "rd", "admin", "master2", "designer"):
            assert _id(world, slug) in designers, (path, slug)
        assert _id(world, "inspector") not in designers, path


@needs_db
def test_the_ministry_dashboard_counts_a_creator_only_where_they_wrote_the_workshop(world):
    """A Ministry Admin who opens a workshop on the oversight screen is its creator and not its
    designer — until they write it."""
    opened = world["client"].post(
        "/api/design-workshop-oversight/workshops",
        json={"title": f"Opened by the ministry {world['tag']}"},
        headers=_headers(world, "ministryOpener"),
    )
    assert opened.status_code == 201, opened.text
    workshop_id = opened.json()["id"]

    def register_row() -> dict[str, Any] | None:
        """The opener's row on whichever page it falls. The register is busiest first and this
        database is shared, so a one-workshop row can sit past the first hundred — walking every
        page keeps "absent" meaning absent rather than "not on page one"."""
        wanted = _id(world, "ministryOpener")
        page, pages = 1, 1
        while page <= pages:
            response = world["client"].get(
                "/api/ministry-dashboard/designers",
                params={"page": page, "pageSize": 100},
                headers=_headers(world, "master"),
            )
            assert response.status_code == 200, response.text
            payload = response.json()
            for row in payload["items"]:
                if row["id"] == wanted:
                    return row
            pages = int(payload["pages"])
            page += 1
        return None

    assert register_row() is None, "opening a workshop counted its opener as its designer"

    assert _write(world, workshop_id, "ministryOpener").status_code == 200
    row = register_row()
    assert row is not None, "a creator who wrote their workshop is missing from the register"
    assert row["workshopsCreated"] == 1


# ── What "does not write it" covers, and what a holder keeps (2026-10-09) ───────────────────────


@needs_db
def test_a_post_holder_cannot_change_the_designer_team_or_unfile_an_artisan(world):
    """The four doors with loaders of their own — the oversight screen's two designer doors and its
    artisan unlink, and the viewers PUT — each refuse the workshop's Regional Director and inspector
    with the write sentence, and write nothing. An assigner who serves on nothing here goes through
    the same four, which shows the refusal is the post and not the role."""
    workshop_id = _open(world, "Team while serving")
    client = world["client"]
    assert _officers(world, workshop_id, rd="ministry").status_code == 200
    assert _inspectors(world, workshop_id, ["admin"]).status_code == 200

    def name_the_designer(slug: str):
        return client.put(
            f"/api/design-workshop-oversight/{workshop_id}/designer",
            json={"designerId": _id(world, "designer")},
            headers=_headers(world, slug),
        )

    def unfile(slug: str):
        return client.delete(
            f"/api/design-workshop-oversight/{workshop_id}/artisans/no-such-artisan",
            headers=_headers(world, slug),
        )

    as_director = posts.write_refusal({"REGIONAL_DIRECTOR"})
    as_inspector = posts.write_refusal({"INSPECTOR"})
    for response, sentence in (
        (_designers(world, workshop_id, ["designer"], as_slug="ministry"), as_director),
        (name_the_designer("ministry"), as_director),
        (unfile("ministry"), as_director),
        (_viewers(world, workshop_id, ["designer"], as_slug="admin"), as_inspector),
    ):
        assert response.status_code == 403, response.text
        assert response.json()["detail"] == sentence

    staffing = client.get(
        f"/api/design-workshop-oversight/{workshop_id}", headers=_headers(world, "master")
    ).json()
    assert staffing["designers"] == [], "a refused team write landed anyway"

    assert _designers(world, workshop_id, ["designer"]).status_code == 200
    renamed = name_the_designer("master")
    assert renamed.status_code == 200, renamed.text
    unfiled = unfile("master")
    assert unfiled.status_code == 200, unfiled.text
    assert unfiled.json() == {"unlinked": False}
    assert _viewers(world, workshop_id, ["designer"]).status_code == 200


@needs_db
def test_a_post_holder_decides_no_access_request_and_prints_no_card_but_lists_them(world):
    """Deciding who joins and printing a card that lets somebody join are the designer team too.
    Another admin decides and prints; the holder may still see the cards that exist."""
    workshop_id = world["askedWorkshop"]
    client = world["client"]
    assert _inspectors(world, workshop_id, ["admin"]).status_code == 200

    def decide(slug: str, decision: str):
        return client.post(
            f"/api/design-workshop-access/requests/{world['accessRequest']}/decide",
            json={"status": decision},
            headers=_headers(world, slug),
        )

    def print_a_card(slug: str):
        return client.post(
            "/api/design-workshop-access/grants",
            json={"recordId": workshop_id, "maxUses": 3},
            headers=_headers(world, slug),
        )

    for response in (decide("admin", "GRANTED"), decide("admin", "DENIED"), print_a_card("admin")):
        assert response.status_code == 403, response.text
        assert response.json()["detail"] == posts.write_refusal({"INSPECTOR"})

    cards = client.get(
        f"/api/design-workshop-access/grants/{workshop_id}", headers=_headers(world, "admin")
    )
    assert cards.status_code == 200, cards.text

    granted = decide("master", "GRANTED")
    assert granted.status_code == 200, granted.text
    assert granted.json()["status"] == "GRANTED"
    assert print_a_card("master").status_code == 201


@needs_db
def test_a_post_holder_keeps_reading_appointing_others_restoring_and_reporting(world):
    """The other half of the ruling, door by door: every read, appointing OTHER people under the
    separation rules, the report with its ledger row on both paths, and restore."""
    workshop_id = _open(world, "What a holder keeps")
    client = world["client"]
    assert _officers(world, workshop_id, rd="ministry").status_code == 200
    assert _inspectors(world, workshop_id, ["admin"]).status_code == 200

    assert _officers(world, workshop_id, as_slug="ministry", ad="ad").status_code == 200
    panel = _inspectors(world, workshop_id, ["admin", "inspector"], as_slug="ministry")
    assert panel.status_code == 200, panel.text
    self_named = _officers(world, workshop_id, as_slug="ministry", ad="ministry")
    assert self_named.status_code == 409, "the separation rules stopped applying to a holder"

    for slug, path in (
        ("ministry", f"/api/design-workshop-oversight/{workshop_id}/artisans"),
        ("ministry", f"/api/design-workshop-oversight/assigned/{workshop_id}"),
        ("admin", f"/api/design-workshops/{workshop_id}"),
        ("admin", f"/api/design-workshops/{workshop_id}/viewers"),
        ("admin", f"/api/design-workshop-inspections/{workshop_id}"),
    ):
        read = client.get(path, headers=_headers(world, slug))
        assert read.status_code == 200, (path, read.text)

    report = client.post(
        f"/api/design-workshops/{workshop_id}/report",
        json={"formats": ["DOCX"], "record": True},
        headers=_headers(world, "admin"),
    )
    assert report.status_code == 200, report.text
    recorded = client.post(
        f"/api/design-workshops/{workshop_id}/exports",
        json={
            "format": "DOCX",
            "templateId": "default",
            "fileName": "made-by-its-inspector.docx",
            "generatedAt": "2026-10-09T00:00:00Z",
        },
        headers=_headers(world, "admin"),
    )
    assert recorded.status_code == 201, recorded.text
    ledger = client.get(
        f"/api/design-workshops/{workshop_id}/exports", headers=_headers(world, "admin")
    )
    assert ledger.status_code == 200, ledger.text
    assert len(ledger.json()) == 2, "one of the two ledger rows was not written"

    deleted = client.delete(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "master")
    )
    assert deleted.status_code == 204, deleted.text
    restored = client.post(
        f"/api/design-workshops/{workshop_id}/restore", headers=_headers(world, "admin")
    )
    assert restored.status_code == 200, restored.text


@needs_db
def test_the_designer_pickers_leave_the_reader_out_only_for_a_workshop_that_exists(world):
    """``workshopId`` says the list feeds a door on an existing workshop, where naming yourself is a
    409; without it the list feeds a create form, and the create lets its creator name themselves —
    no viewer row for them, their own profile on stage 1 when they lead."""
    workshop_id = _open(world, "Picked for an existing workshop")
    tag = world["tag"]
    me = _id(world, "master")

    def offered(path: str, **params: str) -> set[str]:
        response = world["client"].get(
            path, params={"search": tag, **params}, headers=_headers(world, "master")
        )
        assert response.status_code == 200, response.text
        return {row["id"] for row in response.json()["users"]}

    for path in ("/api/design-workshops/eligible-viewers", "/api/design-workshop-oversight/designers"):
        assert me in offered(path), path
        on_the_workshop = offered(path, workshopId=workshop_id)
        assert me not in on_the_workshop, path
        assert _id(world, "designer") in on_the_workshop, path

    created = world["client"].post(
        "/api/design-workshops",
        json={"title": f"Named myself {tag}", "designerUserIds": [me, _id(world, "designer")]},
        headers=_headers(world, "master"),
    )
    assert created.status_code == 201, created.text
    staffing = world["client"].get(
        f"/api/design-workshop-oversight/{created.json()['id']}", headers=_headers(world, "master")
    ).json()
    assert [row["userId"] for row in staffing["designers"]] == [_id(world, "designer")], (
        "the creator was given a viewer row, or the designer they named beside themselves was not"
    )


# ── The record forms: which records a workshop holds is its content too (2026-10-09) ────────────

#: Where a create says it was made and where its subject is — required of every create here but a
#: process's and a questionnaire's, and read before the refusal under test is reached.
LOCATION = {"latitude": 21.19, "longitude": 83.58, "state": "Odisha", "district": "Bargarh"}


def _fresh_aadhaar() -> str:
    """A Verhoeff-valid Aadhaar number nobody in a shared database is likely to hold already, so a
    create that is ADMITTED fails on nothing but what it is testing."""
    import random

    from app.services.artisan_identity import verhoeff_ok

    stem = str(random.randint(2, 9)) + "".join(random.choices("0123456789", k=10))
    return next(stem + last for last in "0123456789" if verhoeff_ok(stem + last))


def _new_record_body(world: dict[str, Any], kind: str, workshop_id: str) -> dict[str, Any]:
    """A complete create body for ``kind``, filed under ``workshop_id``."""
    named = {"place": "Barpali", "artisanName": "Filed by the panel", "craftName": "Sambalpuri Ikat"}
    body = {
        "artisan": {
            "name": "Filed by the panel",
            "place": "Barpali",
            "aadhaarNumber": _fresh_aadhaar(),
            "dos": "Greet the master weaver first.",
            "donts": "Do not photograph the loom without asking.",
            "craftId": world["craft"],
            "location": LOCATION,
        },
        "product": {**named, "productName": "Ikat stole", "location": LOCATION},
        "process": {"name": "Warp tying", "productId": world["parentProduct"]},
        "tool": {**named, "toolkitName": "Pit loom", "location": LOCATION},
        "interview": {"title": "Sitting filed by the panel", "location": LOCATION},
        "questionnaire": {"title": "Form filed by the panel"},
    }[kind]
    return {**body, "designWorkshopId": workshop_id}


def _refile(world: dict[str, Any], kind: str, record_id: str, workshop_id: str | None, slug: str):
    """PATCH one record's ``designWorkshopId`` as ``slug``. ``None`` unfiles it."""
    return world["client"].patch(
        f"{RECORD_PATHS[kind]}/{record_id}",
        json={"designWorkshopId": workshop_id},
        headers=_headers(world, slug),
    )


def _filed_under(world: dict[str, Any], kind: str, workshop_id: str) -> set[str]:
    """Which ``kind`` records the kind's own list files under ``workshop_id`` — asked by ``master``,
    who holds no post, so the answer is the table's and not a scoped view of it."""
    response = world["client"].get(
        RECORD_PATHS[kind],
        params={"designWorkshopId": workshop_id},
        headers=_headers(world, "master"),
    )
    assert response.status_code == 200, response.text
    return {item["id"] for item in response.json()["items"]}


@needs_db
@pytest.mark.parametrize("kind", list(RECORD_PATHS))
def test_a_post_holder_files_no_record_into_the_workshop_and_takes_none_out(world, kind):
    """THE RECORD FORMS' HALF OF THE ROSTER UNLINK'S REFUSAL. Which records a workshop holds is its
    content, so its inspector — an ADMIN, whom every record's edit gate admits on role — is refused,
    with the write sentence, every way a record form could change that: unfiling a record, moving one
    out, moving one in, filing a loose one in, creating one in it, and for a questionnaire form
    copying one into it. Its Regional Director is refused the unfile too. Nothing moves and nothing
    is created."""
    filing = world["filing"][kind]["refused"]
    held, free = filing["held"], filing["free"]
    assert _inspectors(world, held, ["admin"]).status_code == 200
    assert _officers(world, held, rd="ministry").status_code == 200
    as_inspector = posts.write_refusal({"INSPECTOR"})

    for record_id, workshop_id, what in (
        (filing["atHeld"], None, "unfile"),
        (filing["atHeld"], free, "move out"),
        (filing["atFree"], held, "move in"),
        (filing["loose"], held, "file a loose record in"),
    ):
        refused = _refile(world, kind, record_id, workshop_id, "admin")
        assert refused.status_code == 403, (what, refused.text)
        assert refused.json()["detail"] == as_inspector, what
    created = world["client"].post(
        RECORD_PATHS[kind],
        json=_new_record_body(world, kind, held),
        headers=_headers(world, "admin"),
    )
    assert created.status_code == 403, created.text
    assert created.json()["detail"] == as_inspector

    if kind == "questionnaire":
        # COPYING a form into the workshop is a filing too — the attachment door the reuse route
        # adds — and it asks the same loader, so it is refused the same way.
        copied = world["client"].post(
            f"{RECORD_PATHS[kind]}/{filing['atFree']}/reuse",
            json={"designWorkshopId": held},
            headers=_headers(world, "admin"),
        )
        assert copied.status_code == 403, copied.text
        assert copied.json()["detail"] == as_inspector
    else:
        # A questionnaire form is changed by its owner or an admin and by nobody else, so a
        # Ministry Admin who owns none is turned away before any workshop is asked about.
        director = _refile(world, kind, filing["atHeld"], None, "ministry")
        assert director.status_code == 403, director.text
        assert director.json()["detail"] == posts.write_refusal({"REGIONAL_DIRECTOR"})

    assert _filed_under(world, kind, held) == {filing["atHeld"]}, "a refused write landed"
    assert _filed_under(world, kind, free) == {filing["atFree"]}, "a refused write landed"


@needs_db
@pytest.mark.parametrize("kind", list(RECORD_PATHS))
def test_the_refusal_is_the_post_so_everybody_else_and_the_holder_off_it_still_refile(world, kind):
    """The other half, which is what keeps the rule about the POST and not the role: on the very
    workshop that has a holder, an administrator who serves on nothing there moves a record out,
    unfiles it and files it back; the holder still files records that never touch their workshop,
    creates included; and once taken off the post, the holder makes the moves they were refused."""
    filing = world["filing"][kind]["kept"]
    held, free = filing["held"], filing["free"]
    assert _inspectors(world, held, ["admin"]).status_code == 200
    assert _officers(world, held, rd="ministry").status_code == 200

    for workshop_id in (free, None, held):
        moved = _refile(world, kind, filing["atHeld"], workshop_id, "master")
        assert moved.status_code == 200, (workshop_id, moved.text)
    assert _filed_under(world, kind, held) == {filing["atHeld"]}

    for workshop_id in (free, None):
        loose = _refile(world, kind, filing["loose"], workshop_id, "admin")
        assert loose.status_code == 200, (workshop_id, loose.text)
    created = world["client"].post(
        RECORD_PATHS[kind],
        json=_new_record_body(world, kind, free),
        headers=_headers(world, "admin"),
    )
    assert created.status_code == 201, created.text
    assert _filed_under(world, kind, free) == {filing["atFree"], created.json()["id"]}

    assert _inspectors(world, held, []).status_code == 200
    moved_in = _refile(world, kind, filing["atFree"], held, "admin")
    assert moved_in.status_code == 200, moved_in.text
    assert _filed_under(world, kind, held) == {filing["atHeld"], filing["atFree"]}
    unfiled = _refile(world, kind, filing["atFree"], None, "admin")
    assert unfiled.status_code == 200, unfiled.text
    assert _filed_under(world, kind, held) == {filing["atHeld"]}


#: A PATCH that changes one ordinary field of each kind and says nothing about any workshop.
FIELD_EDITS: dict[str, dict[str, Any]] = {
    "artisan": {"place": "Sambalpur"},
    "product": {"place": "Sambalpur"},
    "process": {"notes": "Re-measured on the second visit."},
    "tool": {"place": "Sambalpur"},
    "interview": {"notes": "Re-measured on the second visit."},
    "questionnaire": {"title": "Renamed on the second visit"},
}


@needs_db
@pytest.mark.parametrize("kind", list(RECORD_PATHS))
def test_a_post_holder_edits_and_deletes_no_record_filed_under_their_workshop(world, kind):
    """THE REST OF THE RECORD FORMS (2026-10-09). A record filed under a workshop is its content, so
    the workshop's inspector — an ADMIN, whom every record's edit and delete gate admits on role — is
    refused a PATCH that never mentions the workshop, and the delete; for the questionnaire form,
    which has no delete, the deactivation, a new section and a re-upload, the last refused before
    the workbook is read. Nothing changes. The same administrator edits the twin record on the
    workshop next door, and ``master``, who serves on nothing, edits this one."""
    filing = world["filing"][kind]["edited"]
    held = filing["held"]
    client = world["client"]
    path = f"{RECORD_PATHS[kind]}/{filing['atHeld']}"
    assert _inspectors(world, held, ["admin"]).status_code == 200
    as_inspector = posts.write_refusal({"INSPECTOR"})
    admin = _headers(world, "admin")

    refused = [client.patch(path, json=FIELD_EDITS[kind], headers=admin)]
    if kind == "questionnaire":
        refused += [
            client.patch(path, json={"isActive": False}, headers=admin),
            client.post(f"{path}/sections", json={"title": "Added by its inspector"}, headers=admin),
            client.post(
                f"{path}/upload",
                files={"file": ("form.xlsx", b"never read", "application/octet-stream")},
                headers=admin,
            ),
        ]
    else:
        refused.append(client.delete(path, headers=admin))
    for response in refused:
        assert response.status_code == 403, response.text
        assert response.json()["detail"] == as_inspector
    # Every record seeded under the held workshop is still there — the interviews' set carries the
    # merge test's sitting beside it.
    seeded = {filing[name] for name in ("atHeld", "mergeHeld") if name in filing}
    assert _filed_under(world, kind, held) == seeded, "a refused write landed"

    next_door = client.patch(
        f"{RECORD_PATHS[kind]}/{filing['atFree']}", json=FIELD_EDITS[kind], headers=admin
    )
    assert next_door.status_code == 200, next_door.text
    by_master = client.patch(path, json=FIELD_EDITS[kind], headers=_headers(world, "master"))
    assert by_master.status_code == 200, by_master.text
    if kind != "questionnaire":
        loose = client.delete(f"{RECORD_PATHS[kind]}/{filing['loose']}", headers=admin)
        assert loose.status_code == 204, loose.text


@needs_db
def test_a_post_holder_merges_no_sitting_on_either_side_of_their_workshop(world):
    """A merge rewrites the target and deletes the source, so a sitting in the held workshop on
    EITHER side refuses its inspector — before the scope refusal a cross-workshop pair would
    otherwise get. Two sittings where nobody serves merge for the same administrator."""
    filing = world["filing"]["interview"]["edited"]
    assert _inspectors(world, filing["held"], ["admin"]).status_code == 200
    client = world["client"]

    def merge(source: str, target: str):
        return client.post(
            f"/api/questionnaire/interviews/{filing[source]}/merge-into/{filing[target]}",
            headers=_headers(world, "admin"),
        )

    for source, target in (
        ("mergeHeld", "atHeld"),
        ("mergeHeld", "mergeFree"),
        ("mergeFree", "mergeHeld"),
    ):
        refused = merge(source, target)
        assert refused.status_code == 403, (source, target, refused.text)
        assert refused.json()["detail"] == posts.write_refusal({"INSPECTOR"})
    assert _filed_under(world, "interview", filing["held"]) == {
        filing["atHeld"],
        filing["mergeHeld"],
    }, "a refused merge deleted a sitting"

    merged = merge("mergeFreeToo", "mergeFree")
    assert merged.status_code == 200, merged.text
    assert merged.json()["id"] == filing["mergeFree"]


@needs_db
def test_a_post_holder_changes_no_artisan_link_of_a_tool_filed_under_their_workshop(world):
    """A tool's artisan links are part of the tool: both link routes refuse its workshop's
    inspector, and the twin tool next door takes the same link from the same administrator."""
    tools = world["filing"]["tool"]["edited"]
    artisan = world["filing"]["artisan"]["edited"]["atFree"]
    assert _inspectors(world, tools["held"], ["admin"]).status_code == 200
    client, admin = world["client"], _headers(world, "admin")

    for response in (
        client.post(
            f"/api/tools/{tools['atHeld']}/artisans", json={"artisanIds": [artisan]}, headers=admin
        ),
        client.delete(f"/api/tools/{tools['atHeld']}/artisans/{artisan}", headers=admin),
    ):
        assert response.status_code == 403, response.text
        assert response.json()["detail"] == posts.write_refusal({"INSPECTOR"})

    linked = client.post(
        f"/api/tools/{tools['atFree']}/artisans", json={"artisanIds": [artisan]}, headers=admin
    )
    assert linked.status_code == 200, linked.text
    assert [row["id"] for row in linked.json()] == [artisan]


@needs_db
def test_a_post_holder_changes_no_file_their_workshop_holds_by_any_door(world, monkeypatch):
    """SEC-5. Every way a file belongs to a workshop, and every door that changes one: its inspector
    — an ADMIN, whom each of these doors admits on role — is refused with the write sentence, and so
    is its Regional Director deleting a file they uploaded themselves. Each file is still there and
    the transcript still reads as recorded. On the workshop next door the same administrator keeps
    every power, and ``master``, who serves on nothing, rewrites the held workshop's transcript."""
    from app.api.routes import media as media_routes

    files = world["media"]
    held = files["held"]
    assert _inspectors(world, held, ["admin"]).status_code == 200
    assert _officers(world, held, rd="ministry").status_code == 200
    client, admin = world["client"], _headers(world, "admin")
    removed: list[str] = []
    monkeypatch.setattr(media_routes, "delete_object", removed.append)

    def relink(media: str, record: str, headers: dict[str, str]):
        return client.post(
            f"/api/media/{files[media]}/relink",
            json={"linkedRecordType": "artisan", "linkedRecordId": files[record]},
            headers=headers,
        )

    def decide(media: str, decision: str):
        return client.post(
            "/api/design-workshops/ocr/identity/retention",
            json={"mediaId": files[media], "decision": decision},
            headers=admin,
        )

    as_inspector = posts.write_refusal({"INSPECTOR"})
    for what, response in (
        *(
            (f"delete {name}", client.delete(f"/api/media/{files[name]}", headers=admin))
            for name in ("tagged", "staged", "rich", "filed", "onRecord")
        ),
        (
            "transcript",
            client.post(
                f"/api/media/{files['filed']}/transcript",
                json={"text": "Rewritten by its inspector."},
                headers=admin,
            ),
        ),
        (
            "refine",
            client.post(
                f"/api/media/{files['filed']}/refine-transcript",
                json={"translate": False},
                headers=admin,
            ),
        ),
        ("transcribe now", client.post(f"/api/media/{files['filed']}/transcribe-now", headers=admin)),
        ("keep the identity photograph", decide("tagged", "STORE")),
        ("discard the identity photograph", decide("staged", "DISCARD")),
        ("relink out of the workshop", relink("tagged", "unfiled", admin)),
        ("relink into the workshop", relink("freeTagged", "pictured", admin)),
    ):
        assert response.status_code == 403, (what, response.text)
        assert response.json()["detail"] == as_inspector, what

    own = client.delete(
        f"/api/media/{files['ministryOwn']}", headers=_headers(world, "ministry")
    )
    assert own.status_code == 403, own.text
    assert own.json()["detail"] == posts.write_refusal({"REGIONAL_DIRECTOR"})

    master = _headers(world, "master")
    for name in ("tagged", "staged", "rich", "filed", "onRecord", "ministryOwn", "freeTagged"):
        still = client.get(f"/api/media/{files[name]}", headers=master)
        assert still.status_code == 200, (name, still.text)
    assert client.get(f"/api/media/{files['tagged']}", headers=master).json()[
        "linkedRecordType"
    ] == "designWorkshop", "a refused relink moved the file"
    recording = client.get(f"/api/media/{files['filed']}", headers=master).json()
    assert recording["transcriptText"] == "As the artisan said it.", "a refused rewrite landed"
    assert recording["transcriptEditedById"] is None
    assert removed == [], "a refused delete reached object storage"

    # Next door, where nobody serves, the same administrator keeps every power.
    rewritten = client.post(
        f"/api/media/{files['freeAudio']}/transcript",
        json={"text": "Corrected on the second visit."},
        headers=admin,
    )
    assert rewritten.status_code == 200, rewritten.text
    assert rewritten.json()["transcriptText"] == "Corrected on the second visit."
    assert relink("freeMoved", "unfiled", admin).status_code == 200
    # Over Postgres, which is the point: until this test the STORE arm had only ever met an
    # in-memory store, and it answered 500 against the real Json column.
    kept = decide("freeKept", "STORE")
    assert kept.status_code == 200, kept.text
    assert kept.json()["retention"]["decision"] == "STORE"
    deleted = client.delete(f"/api/media/{files['freeDeleted']}", headers=admin)
    assert deleted.status_code == 204, deleted.text
    assert removed == [f"media/{_id(world, 'master')}/serve-as-{world['tag']}-free-deleted"]
    # And on the held workshop itself, an administrator who serves on nothing there.
    by_master = client.post(
        f"/api/media/{files['filed']}/transcript",
        json={"text": "Corrected by the master admin."},
        headers=master,
    )
    assert by_master.status_code == 200, by_master.text


@needs_db
def test_a_post_holder_who_scans_their_workshops_join_card_lands_provisional_and_spends_nothing(
    world,
):
    """tests-docs/F3. The workshop's inspector may not hold designer access to it, so scanning its
    card lands them as a capture-only foothold — the redemption's INELIGIBLE arm, reached through
    the viewers rule's 409 and not its 422 — and the card's one seat is still there for somebody
    else. A MINISTRY_ADMIN, because a platform admin is "already a member" of every workshop and
    never reaches the question."""
    workshop_id = _open(world, "Join card while serving")
    client = world["client"]
    assert _inspectors(world, workshop_id, ["ministry"]).status_code == 200
    minted = client.post(
        "/api/design-workshop-access/grants",
        json={"recordId": workshop_id, "maxUses": 1},
        headers=_headers(world, "master"),
    )
    assert minted.status_code == 201, minted.text

    scanned = client.post(
        "/api/design-workshop-access/redemptions",
        json={"code": minted.json()["code"]},
        headers=_headers(world, "ministry"),
    )
    assert scanned.status_code == 200, scanned.text
    assert (scanned.json()["outcome"], scanned.json()["reason"]) == ("PROVISIONAL", "INELIGIBLE")
    # The card was NOT used, so the answer must not say it was — and it names no post either.
    assert "already been used" not in scanned.json()["detail"], scanned.json()["detail"]
    assert "the card was not used up" in scanned.json()["detail"]
    assert "inspector" not in scanned.json()["detail"]

    cards = client.get(
        f"/api/design-workshop-access/grants/{workshop_id}", headers=_headers(world, "master")
    )
    assert cards.status_code == 200, cards.text
    assert [card["usesConsumed"] for card in cards.json()["grants"]] == [0]
    viewers = client.get(
        f"/api/design-workshops/{workshop_id}/viewers", headers=_headers(world, "master")
    )
    assert viewers.status_code == 200, viewers.text
    assert _id(world, "ministry") not in {row["userId"] for row in viewers.json()["viewers"]}


# ── The later doors: the review queue, a new upload, a retried job (2026-10-09) ──────────────────


def _read_back(world: dict[str, Any], review_type: str, record_id: str) -> dict[str, Any]:
    """One reviewed record, as ``master`` — who serves on nothing — reads it through its route."""
    path = "/api/media" if review_type == "media" else RECORD_PATHS[REVIEWED[review_type][0]]
    response = world["client"].get(f"{path}/{record_id}", headers=_headers(world, "master"))
    assert response.status_code == 200, response.text
    return response.json()


@needs_db
@pytest.mark.parametrize("review_type", list(REVIEWED))
def test_a_post_holder_rewrites_nothing_of_their_workshop_from_the_review_queue(world, review_type):
    """``POST /api/review/{type}/{id}/edit`` writes a record's fields and a file's caption, and it
    asked nothing about the workshop. The workshop's inspector — an ADMIN, whom the edit's rank gate
    admits over a designer's work — is refused with the write sentence for every record type and for
    a file, and nothing changes. The same administrator corrects the twin next door, ``master``
    corrects this one, and nobody re-files anything from the queue: ``designWorkshopId`` is a
    422."""
    doors = world["doors"]
    held, free = doors["held"], doors["free"]
    assert _inspectors(world, held, ["admin"]).status_code == 200
    client = world["client"]
    (field, value), = REVIEWED[review_type][1].items()
    at = doors[review_type]

    def edit(record_id: str, slug: str, fields: dict[str, Any]) -> Any:
        return client.post(
            f"/api/review/{review_type}/{record_id}/edit",
            json={"fields": fields, "note": "From the review queue."},
            headers=_headers(world, slug),
        )

    refused = edit(at["held"], "admin", {field: value})
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == posts.write_refusal({"INSPECTOR"})
    assert _read_back(world, review_type, at["held"])[field] != value, "a refused edit landed"

    next_door = edit(at["free"], "admin", {field: value})
    assert next_door.status_code == 200, next_door.text
    assert _read_back(world, review_type, at["free"])[field] == value
    by_master = edit(at["held"], "master", {field: value})
    assert by_master.status_code == 200, by_master.text
    assert _read_back(world, review_type, at["held"])[field] == value

    if review_type != "media":
        refiled = edit(at["free"], "master", {"designWorkshopId": held})
        assert refiled.status_code == 422, refiled.text
        assert "designWorkshopId" in refiled.json()["detail"]
        assert _read_back(world, review_type, at["free"])["designWorkshopId"] == free


@needs_db
def test_a_post_holder_uploads_no_new_file_into_their_workshop(world, monkeypatch):
    """``POST /api/media/complete`` is the door that ADDS a file, and a file tagged to a workshop or
    attached to a record filed under it is that workshop's content. Its inspector is refused both,
    with the write sentence, and nothing is created — a second try at the same key is refused again
    rather than answered as the replay of a row that exists. The same administrator uploads into
    the workshop next door, and ``master`` into this one."""
    from app.api.routes import media as media_routes

    monkeypatch.setattr(media_routes, "head_object", lambda _key: None)
    doors = world["doors"]
    held, free = doors["held"], doors["free"]
    assert _inspectors(world, held, ["admin"]).status_code == 200
    as_inspector = posts.write_refusal({"INSPECTOR"})

    def complete(slug: str, label: str, **links: Any) -> Any:
        user_id = _id(world, slug)
        return world["client"].post(
            "/api/media/complete",
            json={
                "originalFilename": f"{label}.jpg",
                "mediaType": "IMAGE",
                "mimeType": "image/jpeg",
                "sizeBytes": 2048,
                "objectKey": f"media/{user_id}/serve-as-{world['tag']}-upload-{label}.jpg",
                **links,
            },
            headers=_headers(world, slug),
        )

    tagged = {"linkedRecordType": "designWorkshop", "linkedRecordId": held}
    on_record = {"linkedRecordType": "artisan", "linkedRecordId": doors["artisan"]["held"]}
    for label, links in (("tagged", tagged), ("on-record", on_record)):
        for attempt in (1, 2):
            refused = complete("admin", f"refused-{label}", **links)
            assert refused.status_code == 403, (label, attempt, refused.text)
            assert refused.json()["detail"] == as_inspector, (label, attempt)

    next_door = complete(
        "admin", "next-door", linkedRecordType="designWorkshop", linkedRecordId=free
    )
    assert next_door.status_code == 201, next_door.text
    by_master = complete("master", "by-master", **tagged)
    assert by_master.status_code == 201, by_master.text
    assert by_master.json()["linkedRecordId"] == held


@needs_db
def test_a_post_holder_requeues_no_transcription_of_their_workshops_recording(world):
    """``POST /api/media/jobs/{id}/retry`` sends a recording back to the provider, and the queue
    writes the answer onto it as its transcript — ``transcribe-now``'s write, deferred. The
    workshop's inspector is refused it, and the job is untouched: ``master`` requeues it straight
    afterwards, which only a job still FAILED allows. The same administrator requeues the recording
    next door."""
    doors = world["doors"]
    assert _inspectors(world, doors["held"], ["admin"]).status_code == 200
    client = world["client"]

    def retry(place: str, slug: str) -> Any:
        return client.post(
            f"/api/media/jobs/{doors['jobs'][place]}/retry", headers=_headers(world, slug)
        )

    refused = retry("held", "admin")
    assert refused.status_code == 403, refused.text
    assert refused.json()["detail"] == posts.write_refusal({"INSPECTOR"})

    by_master = retry("held", "master")
    assert by_master.status_code == 200, by_master.text
    assert by_master.json()["status"] == "QUEUED", "the refused retry had already moved the job"
    next_door = retry("free", "admin")
    assert next_door.status_code == 200, next_door.text
    assert next_door.json()["status"] == "QUEUED"


# ── The unfiled-records report (2026-10-09) ──────────────────────────────────────────────────────


def _unfiled(world: dict[str, Any], method: str, bucket: str, name: str, slug: str, **body: Any):
    """One single-row door of ``/api/workshops/unmapped``, on the seeded row called ``name``."""
    path = f"/api/workshops/unmapped/{bucket}/{world['unfiled'][name]}"
    send = world["client"].delete if method == "DELETE" else world["client"].post
    kwargs: dict[str, Any] = {"headers": _headers(world, slug)}
    if body:
        kwargs["json"] = body
    return send(path, **kwargs)


def _crafts_workshop_of(world: dict[str, Any], bucket: str, name: str) -> str | None:
    """The crafts ``workshopId`` the seeded row called ``name`` carries now, read by ``master``."""
    path = {"media": "/api/media", "products": "/api/products", "artisans": "/api/artisans"}[bucket]
    response = world["client"].get(
        f"{path}/{world['unfiled'][name]}", headers=_headers(world, "master")
    )
    assert response.status_code == 200, (name, response.text)
    return response.json()["workshopId"]


@needs_db
def test_the_unfiled_report_lists_no_row_a_design_workshop_claims(world):
    """Asked of the real ladder in the seed. A record filed under a design workshop and a file filed
    under one, or tagged to one in either spelling, are not "unfiled" and are not listed; a file the
    workshop holds only through a stage entry or the record it hangs off is still listed, and the
    single-row doors answer for it. Rows nothing claims — a file with no tag, a file whose tag names
    an artisan filed nowhere — are listed as they always were."""
    listed = world["unfiled"]["listed"]
    for name in ("tagged", "taggedLower", "filed", "filedArtisan", "filedProduct"):
        assert listed[name] is False, f"{name} is claimed by a design workshop and was listed"
    for name in ("staged", "onRecord", "otherTag", "loose", "looseArtisan", "looseProduct"):
        assert listed[name] is True, f"{name} needs a person and was not listed"


@needs_db
def test_the_batched_holder_question_answers_exactly_what_the_file_by_file_one_does(world):
    """``media_held_among`` asks each of the five ways once for many files; held equal, in the seed,
    to ``media_design_workshop_ids`` file by file over every file this module seeds — tagged, held
    by an IMAGE field, inside a RICH_TEXT field, filed, hanging off an artisan or a product — and
    the controls nothing claims."""
    parity = world["unfiled"]["parity"]
    assert parity["batched"] == parity["fileByFile"]
    media, unfiled = world["media"], world["unfiled"]
    belongs = {
        *(media[key] for key in ("tagged", "staged", "rich", "filed", "onRecord", "ministryOwn")),
        *(unfiled[key] for key in ("tagged", "taggedLower", "filed", "staged", "onRecord", "mapHeld")),
    }
    stranger = {
        *(media[key] for key in ("freeTagged", "freeAudio")),
        *(unfiled[key] for key in ("otherTag", "loose", "looseHolder", "mapFree")),
    }
    assert set(parity["batched"]) == belongs, "the fixture stopped exercising every way a file belongs"
    assert parity["asked"] >= len(belongs | stranger)


@needs_db
def test_nobody_discards_from_the_unfiled_report_a_row_a_design_workshop_claims(world, monkeypatch):
    """D6-UNMAPPED-DISCARD. The report offered ``Delete permanently`` on a workshop's stage photos,
    its roster artisans and the records filed under it, by a door no rule of the workshop's watched.
    Its inspector — an ADMIN, whom ``require_admin`` admits — is refused with the write sentence;
    every other administrator with a 409 naming the workshop and sending them to the record's own
    screen. Nothing is deleted and storage is not touched. A row nothing claims is still deleted —
    by the holder too, which shows the refusal is the workshop's and not the caller's."""
    from app.services import workshop_inference

    held = world["unfiled"]["held"]
    assert _inspectors(world, held, ["admin"]).status_code == 200
    removed: list[str] = []
    monkeypatch.setattr(workshop_inference, "delete_object", removed.append)
    as_inspector = posts.write_refusal({"INSPECTOR"})
    claimed = (
        ("media", "tagged"),
        ("media", "taggedLower"),
        ("media", "filed"),
        ("media", "staged"),
        ("media", "onRecord"),
        ("artisans", "filedArtisan"),
        ("products", "filedProduct"),
    )
    for bucket, name in claimed:
        refused = _unfiled(world, "DELETE", bucket, name, "admin")
        assert refused.status_code == 403, (name, refused.text)
        assert refused.json()["detail"] == as_inspector, name
        for slug in ("opener", "master"):
            claimed_answer = _unfiled(world, "DELETE", bucket, name, slug)
            assert claimed_answer.status_code == 409, (name, slug, claimed_answer.text)
            detail = claimed_answer.json()["detail"]
            assert f"“{world['unfiled']['heldTitle']}”" in detail, (name, detail)
            assert "is not deleted from here" in detail and "Open the" in detail, detail
        assert _crafts_workshop_of(world, bucket, name) is None, f"{name} was deleted or moved"
    assert removed == [], "a refused discard reached object storage"

    gone = _unfiled(world, "DELETE", "media", "loose", "opener")
    assert gone.status_code == 200, gone.text
    assert gone.json()["mediaKept"] == 0
    assert removed == [f"media/{_id(world, 'master')}/serve-as-{world['tag']}-unfiled-loose"]
    by_the_holder = _unfiled(world, "DELETE", "products", "looseDiscard", "admin")
    assert by_the_holder.status_code == 200, by_the_holder.text


@needs_db
def test_a_post_holder_files_no_row_of_their_workshop_from_the_unfiled_report(world):
    """D6-UNMAPPED-FILE. The single-row file wrote ``workshopId`` onto a record filed under the
    workshop, and onto a file it holds, for its inspector — writes the record form and the media
    doors refuse them. Refused now with the write sentence, and nothing is written. The holder still
    files a row nothing claims, and an administrator serving on nothing there files as before."""
    unfiled = world["unfiled"]
    assert _inspectors(world, unfiled["held"], ["admin"]).status_code == 200
    target = {"workshopId": unfiled["crafts"]}
    for bucket, name in (("products", "filedProduct"), ("media", "staged"), ("media", "tagged")):
        refused = _unfiled(world, "POST", bucket, name, "admin", **target)
        assert refused.status_code == 403, (name, refused.text)
        assert refused.json()["detail"] == posts.write_refusal({"INSPECTOR"}), name
        assert _crafts_workshop_of(world, bucket, name) is None, f"a refused file of {name} landed"

    for name, slug in (("looseProductHolder", "admin"), ("looseProduct", "opener")):
        filed = _unfiled(world, "POST", "products", name, slug, **target)
        assert filed.status_code == 200, (name, filed.text)
        assert filed.json()["workshopId"] == unfiled["crafts"]
        assert _crafts_workshop_of(world, "products", name) == unfiled["crafts"]


@needs_db
def test_a_holders_bulk_map_leaves_their_workshops_rows_alone_and_says_so(world, monkeypatch):
    """D6-UNMAPPED-FILE's bulk half. The plan is the server's own, so a holder's run is not refused
    whole: the rows of their workshop — a record filed under it, a file hanging off a record filed
    under it — are left alone and counted as ``heldBack``, with the sentence for the screen, and
    everything else is stamped. The same run by an administrator serving on nothing there stamps
    the rows the holder's left. The ladder is replaced by one naming only these rows, so the run
    writes nothing outside this module's fixture."""
    from app.services import workshop_inference as inference

    unfiled = world["unfiled"]
    crafts = unfiled["crafts"]
    assert _inspectors(world, unfiled["held"], ["admin"]).status_code == 200
    resolved = {
        "media": ("mapHeld", "mapFree"),
        "products": ("mapRecordHeld", "mapProduct"),
    }

    async def _ladder() -> Any:
        plans = {bucket: inference.BucketPlan(bucket=bucket) for bucket in inference.BUCKET_KEYS}
        for bucket, names in resolved.items():
            plans[bucket].unassigned = len(names)
            plans[bucket].rows = [
                inference.RowPlan(
                    id=unfiled[name], title=name, workshopId=crafts, rung=inference.RUNG_PARENT
                )
                for name in names
            ]
        return inference.LadderRun(
            plans=plans, workshopTitles={crafts: "Crafts workshop"}, windows=[]
        )

    monkeypatch.setattr(inference, "run_ladder", _ladder)

    def run(slug: str) -> dict[str, Any]:
        response = world["client"].post(
            "/api/workshops/unmapped/map", headers=_headers(world, slug)
        )
        assert response.status_code == 200, response.text
        body = response.json()
        return {"body": body, **{b["bucket"]: b for b in body["buckets"]}}

    by_the_holder = run("admin")
    for bucket in resolved:
        assert (by_the_holder[bucket]["heldBack"], by_the_holder[bucket]["applied"]) == (1, 1), bucket
    assert by_the_holder["body"]["totals"]["heldBack"] == 2
    assert by_the_holder["body"]["heldBackDetail"] == inference.held_back_detail(2)
    assert "inspect or supervise" in by_the_holder["body"]["heldBackDetail"]
    assert _crafts_workshop_of(world, "media", "mapHeld") is None, "the holder's run stamped it"
    assert _crafts_workshop_of(world, "products", "mapRecordHeld") is None
    assert _crafts_workshop_of(world, "media", "mapFree") == crafts
    assert _crafts_workshop_of(world, "products", "mapProduct") == crafts

    by_master = run("master")
    for bucket in resolved:
        assert (by_master[bucket]["heldBack"], by_master[bucket]["applied"]) == (0, 1), bucket
    assert by_master["body"]["heldBackDetail"] is None
    assert _crafts_workshop_of(world, "media", "mapHeld") == crafts
    assert _crafts_workshop_of(world, "products", "mapRecordHeld") == crafts


# ── Nobody takes themselves off a post (2026-10-09) ──────────────────────────────────────────────


@needs_db
def test_nobody_takes_themselves_off_an_inspection_or_oversight_post(world):
    """D6-SELF-RELEASE. The 403 a holder reads says to ask whoever made the appointment to take them
    off; until now a holder could take themselves off in one call and then write the workshop, the
    deleted row the only record they held it. Refused now (409) for the inspector panel, the AD slot
    and the RD slot — emptied or handed to somebody else — and nothing changes. A holder still takes
    OTHER people off, and another assigner takes the holder off."""
    workshop_id = _open(world, "Nobody takes themselves off")
    client, master = world["client"], _headers(world, "master")
    assert _inspectors(world, workshop_id, ["admin"]).status_code == 200
    assert _officers(world, workshop_id, ad="ministry", rd="master2").status_code == 200

    for response, post in (
        (_inspectors(world, workshop_id, [], as_slug="admin"), "INSPECTOR"),
        (_inspectors(world, workshop_id, ["inspector"], as_slug="admin"), "INSPECTOR"),
        (_officers(world, workshop_id, as_slug="ministry", ad=None), "ASSISTANT_DIRECTOR"),
        (_officers(world, workshop_id, as_slug="ministry", ad="ad"), "ASSISTANT_DIRECTOR"),
        (_officers(world, workshop_id, as_slug="master2", rd=None), "REGIONAL_DIRECTOR"),
        (_officers(world, workshop_id, as_slug="master2", rd="rd"), "REGIONAL_DIRECTOR"),
    ):
        assert response.status_code == 409, (post, response.text)
        assert response.json()["detail"] == posts.self_release_refusal({post}), post

    def panel() -> list[str]:
        response = client.get(
            f"/api/design-workshop-inspections/{workshop_id}/inspectors", headers=master
        )
        assert response.status_code == 200, response.text
        return [row["userId"] for row in response.json()["inspectors"]]

    def slots() -> dict[str, str]:
        response = client.get(f"/api/design-workshop-oversight/{workshop_id}", headers=master)
        assert response.status_code == 200, response.text
        return {row["capacity"]: row["userId"] for row in response.json()["oversight"]}

    assert panel() == [_id(world, "admin")], "a refused release took the inspector off"
    assert slots() == {
        "ASSISTANT_DIRECTOR": _id(world, "ministry"),
        "REGIONAL_DIRECTOR": _id(world, "master2"),
    }, "a refused release changed a slot"

    # The Assistant Director takes the inspector off; the master admin takes both directors off.
    assert _inspectors(world, workshop_id, [], as_slug="ministry").status_code == 200
    assert panel() == []
    assert _officers(world, workshop_id, ad=None, rd=None).status_code == 200
    assert slots() == {}
