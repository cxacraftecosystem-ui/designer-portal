"""``GET /design-workshops?deletedOnly=true`` — the trash, and the gate on it. No database required.

WHAT THIS DEFENDS
=================

``DELETE /design-workshops/{id}`` is a SOFT delete: it sets ``deletedAt`` and nothing else, and
``POST /{id}/restore`` clears it. The web's delete confirmation promises as much in so many words —
*"Nothing is erased … an admin can restore it"*. For as long as those two routes have existed there
was no third one that would NAME a deleted workshop: every read filtered ``deletedAt: null``, so an
admin could restore only a workshop whose id they had written down before deleting it. The promise
was true of the database and false of the product.

``deletedOnly`` (and its wider sibling ``includeDeleted``) is the listing that closes that, and this
module pins the four things about it that could each ship looking finished while being wrong:

1. **THE GATE IS THE RESTORE'S GATE.** Admin and master admin, nobody else, refused BEFORE the
   database is touched. A deleted workshop is invisible to its own creator today —
   ``load_workshop_or_404`` answers a non-admin 404 on a deleted row — so a looser gate here would
   list rows that the single read then denies.

2. **REFUSED, NOT IGNORED.** The tempting cheap fix is to drop the flag for a caller who may not use
   it. That renders the LIVE list under a "Deleted" heading with a Restore button beside every row.

3. **THE FILTER IS THREE STATES.** Off (deleted rows hidden), ``deletedOnly`` (nothing else), and
   ``includeDeleted`` (both, interleaved). Getting the middle one wrong in the direction of "no
   filter at all" is a trash view that offers to restore live workshops.

4. **THE TOTAL IS THE WHOLE TRASH, NOT THE PAGE.** A trash card that shows twenty rows out of
   fifty-one has to be able to say so. ``total`` is what it says it with, and a page payload whose
   total silently equalled the page length would make a truncated list indistinguishable from a
   complete one — the failure this repository has shipped more often than any other.

It calls the handler directly with ``db`` replaced by a recorder, so it asserts the ``where`` that
would reach Prisma rather than the rows a fixture happened to hold. The database half — that the
rows really do come back and that a restore really does clear the columns — is the round-trip
script's, and it is why nothing here claims to have exercised Postgres.
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import design_workshops as routes

#: Every tier on the ladder that is NOT an admin. Written out rather than derived from ``ROLE_RANK``
#: for the reason ``test_dw_inspector_scope_gate`` gives for its own copy: these strings are the
#: INPUT to a set-membership gate, so a list computed from the ladder would stop exercising a tier
#: on the deployment where that tier has not landed yet. ``tests/test_role_ladder_parity`` is what
#: keeps the ladder's copies honest; this is not a mirror of it.
NON_ADMIN_TIERS = (
    "CROWDSOURCE_VOLUNTEER",
    "FIELD_CONTRIBUTOR",
    "RESEARCHER",
    "DESIGNER",
    "INSPECTOR",
    "PROFESSOR",
)
ADMIN_TIERS = ("ADMIN", "MASTER_ADMIN")

DELETED_AT = datetime(2026, 8, 26, 9, 30, tzinfo=UTC)


def _user(role: str) -> SimpleNamespace:
    """A user row as ``is_admin`` and ``role_value`` read one."""
    return SimpleNamespace(id=f"u-{role.lower()}", email=f"{role.lower()}@example.test", role=role)


def _row(rid: str, *, deleted_at: datetime | None = None, deleted_by: str | None = None) -> Any:
    """A ``DesignWorkshop`` row as ``workshop_summary`` reads one — every key it names, and no more."""
    return SimpleNamespace(
        id=rid,
        title=f"Workshop {rid}",
        templateId="default",
        status="DRAFT",
        workshopCode=None,
        scheme=None,
        craftName=None,
        clusterName=None,
        state=None,
        district=None,
        venue=None,
        startDate=None,
        endDate=None,
        designerName=None,
        implementingAgency=None,
        sponsor=None,
        notes=None,
        workshopId=None,
        createdById="u-admin",
        createdAt=None,
        updatedAt=None,
        deletedAt=deleted_at,
        deletedById=deleted_by,
        dictationConsent="NOT_RECORDED",
        dictationConsentAt=None,
        dictationConsentById=None,
    )


class _Table:
    """One Prisma model delegate, recording the arguments it was called with."""

    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows
        self.finds: list[dict[str, Any]] = []
        self.counts: list[dict[str, Any]] = []

    async def count(self, where: dict[str, Any]) -> int:
        self.counts.append(where)
        return len(self.rows)

    async def find_many(self, **kwargs: Any) -> list[Any]:
        self.finds.append(kwargs)
        take = kwargs.get("take")
        skip = kwargs.get("skip") or 0
        return self.rows[skip : skip + take] if take else self.rows


class _Db:
    def __init__(self, workshops: list[Any], accounts: list[Any] | None = None) -> None:
        self.designworkshop = _Table(workshops)
        self.user = _Table(accounts or [])


@pytest.fixture
def stub(monkeypatch: pytest.MonkeyPatch):
    """Rebind the module's own ``db``. It does ``from app.core.db import db``, so this is the one."""

    def install(workshops: list[Any], accounts: list[Any] | None = None) -> _Db:
        fake = _Db(workshops, accounts)
        monkeypatch.setattr(routes, "db", fake)
        return fake

    return install


async def _list(user: SimpleNamespace, **kwargs: Any) -> dict[str, Any]:
    """The handler, called with the query defaults spelled out.

    ``page`` and ``pageSize`` carry ``Query(...)`` objects as their defaults, which FastAPI resolves
    per request and a direct call does not — so they are passed here, always, rather than left to a
    default that would arrive at ``normalize_pagination`` as a Query instance.
    """
    return await routes.list_design_workshops(
        page=kwargs.pop("page", 1), pageSize=kwargs.pop("pageSize", 20), current_user=user, **kwargs
    )


# --------------------------------------------------------------------------------------
# 1. The gate
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("role", NON_ADMIN_TIERS)
@pytest.mark.parametrize("flag", ["deletedOnly", "includeDeleted"])
async def test_only_an_admin_may_ask_for_deleted_workshops(stub, role, flag):
    """403 with the sentence ``require_admin`` uses, and the database never asked.

    The refusal has to happen ABOVE the read, not be filtered out below it: a gate that runs after
    the query has already served the row it was meant to withhold, and a gate that answers a
    different sentence teaches a client to show its own guess instead.
    """
    fake = stub([_row("w1", deleted_at=DELETED_AT)])
    with pytest.raises(HTTPException) as raised:
        await _list(_user(role), **{flag: True})
    assert raised.value.status_code == 403
    assert raised.value.detail == "Admin access required"
    assert fake.designworkshop.counts == [] and fake.designworkshop.finds == [], (
        "the trash was refused only after it had been read out of the database"
    )


@pytest.mark.parametrize("role", ADMIN_TIERS)
async def test_both_admin_tiers_reach_the_trash(stub, role):
    """MASTER_ADMIN is not a special case anywhere else in this file and is not one here."""
    stub([_row("w1", deleted_at=DELETED_AT)])
    payload = await _list(_user(role), deletedOnly=True)
    assert [item["id"] for item in payload["items"]] == ["w1"]


async def test_a_designers_ordinary_list_is_untouched_by_the_new_parameters(stub):
    """The flags are opt-in. Not sending them leaves a non-admin's list exactly as it was."""
    fake = stub([_row("w1")])
    await _list(_user("DESIGNER"))
    where = fake.designworkshop.finds[0]["where"]
    assert where["deletedAt"] is None
    assert where["AND"] == [{"OR": [{"createdById": "u-designer"}, {"viewers": {"some": {"userId": "u-designer"}}}]}]


# --------------------------------------------------------------------------------------
# 2. The filter, in all three of its states
# --------------------------------------------------------------------------------------


async def test_the_default_list_still_hides_deleted_rows(stub):
    fake = stub([_row("w1")])
    await _list(_user("ADMIN"))
    assert fake.designworkshop.finds[0]["where"]["deletedAt"] is None


async def test_deleted_only_asks_for_the_deleted_rows_and_nothing_else(stub):
    """``{"not": None}``, and NOT an absent key.

    A trash view built on "no filter" would list every live workshop under a Deleted heading with a
    Restore control beside it — the restore is idempotent, so nothing would even go wrong loudly.
    """
    fake = stub([_row("w1", deleted_at=DELETED_AT)])
    await _list(_user("ADMIN"), deletedOnly=True)
    assert fake.designworkshop.finds[0]["where"]["deletedAt"] == {"not": None}
    assert fake.designworkshop.counts[0]["deletedAt"] == {"not": None}, (
        "the count and the page were taken under different filters, so the total counts rows the "
        "list cannot contain"
    )


async def test_include_deleted_drops_the_filter_rather_than_inverting_it(stub):
    fake = stub([_row("w1"), _row("w2", deleted_at=DELETED_AT)])
    await _list(_user("ADMIN"), includeDeleted=True)
    assert "deletedAt" not in fake.designworkshop.finds[0]["where"]


async def test_deleted_only_wins_when_a_caller_sends_both(stub):
    """The narrower of the two. A client that sent both meant the specific one."""
    fake = stub([_row("w1", deleted_at=DELETED_AT)])
    await _list(_user("ADMIN"), includeDeleted=True, deletedOnly=True)
    assert fake.designworkshop.finds[0]["where"]["deletedAt"] == {"not": None}


async def test_the_trash_is_ordered_by_when_each_row_went(stub):
    """Newest DELETED first, not newest created.

    The row an admin opens a trash view to find is the one they deleted a minute ago. A workshop
    opened in March and deleted this morning sorts to the bottom of a ``createdAt`` ordering, under
    rows nobody is looking for and possibly on a page they never reach.
    """
    fake = stub([_row("w1", deleted_at=DELETED_AT)])
    await _list(_user("ADMIN"), deletedOnly=True)
    assert fake.designworkshop.finds[0]["order"] == {"deletedAt": "desc"}

    fake = stub([_row("w1")])
    await _list(_user("ADMIN"))
    assert fake.designworkshop.finds[0]["order"] == {"createdAt": "desc"}


async def test_the_other_filters_still_compose_with_the_trash(stub):
    """A trash view with a search box narrows the trash, not the live list."""
    fake = stub([_row("w1", deleted_at=DELETED_AT)])
    await _list(_user("ADMIN"), deletedOnly=True, search="ikat")
    where = fake.designworkshop.finds[0]["where"]
    assert where["deletedAt"] == {"not": None}
    assert [next(iter(clause)) for clause in where["OR"]] == [
        "title",
        "craftName",
        "clusterName",
        "workshopCode",
    ]


# --------------------------------------------------------------------------------------
# 3. Who deleted it
# --------------------------------------------------------------------------------------


async def test_the_trash_names_who_deleted_each_row_in_one_query(stub):
    """Two rows, two accounts, ONE ``user.find_many``.

    A name lookup per row is how a twenty-row trash page becomes twenty-one queries. The batch is
    also why this enrichment sits on the route and not in ``workshop_summary``, which every list and
    every single read goes through.
    """
    fake = stub(
        [
            _row("w1", deleted_at=DELETED_AT, deleted_by="u-priya"),
            _row("w2", deleted_at=DELETED_AT, deleted_by="u-ravi"),
        ],
        accounts=[
            SimpleNamespace(id="u-priya", name="Priya"),
            SimpleNamespace(id="u-ravi", name="Ravi"),
        ],
    )
    payload = await _list(_user("ADMIN"), deletedOnly=True)
    assert len(fake.user.finds) == 1
    assert fake.user.finds[0]["where"] == {"id": {"in": ["u-priya", "u-ravi"]}}
    assert [(i["deletedById"], i["deletedByName"]) for i in payload["items"]] == [
        ("u-priya", "Priya"),
        ("u-ravi", "Ravi"),
    ]


async def test_a_deleter_whose_account_is_gone_keeps_the_id_and_gets_no_name(stub):
    """``deletedById`` is ``onDelete: SetNull``, so a pointer can outlive the account it names.

    The id is kept and the name is null, which both clients must render as "an account no longer on
    record". Substituting the workshop's creator — the tempting default — puts a name against a
    deletion they did not perform, on a screen whose entire purpose is undoing one.
    """
    fake = stub([_row("w1", deleted_at=DELETED_AT, deleted_by="u-departed")], accounts=[])
    payload = await _list(_user("ADMIN"), deletedOnly=True)
    assert len(fake.user.finds) == 1
    assert payload["items"][0]["deletedById"] == "u-departed"
    assert payload["items"][0]["deletedByName"] is None


async def test_a_row_deleted_by_nobody_costs_no_account_query(stub):
    """``deletedById`` is nullable and rows written before it existed carry no pointer at all."""
    fake = stub([_row("w1", deleted_at=DELETED_AT)])
    payload = await _list(_user("ADMIN"), deletedOnly=True)
    assert fake.user.finds == []
    assert payload["items"][0] == {**payload["items"][0], "deletedById": None, "deletedByName": None}


async def test_the_ordinary_list_carries_no_deletion_pointer_at_all(stub):
    """No keys, and no account query, on the list every designer loads.

    ``deletedById`` is null on every live workshop, so putting it on ``workshop_summary`` would add a
    key that is null everywhere except the one listing that can show a deleted row — and resolving
    the name there would be the per-row account lookup ``consent_keys`` already refuses to make.
    """
    fake = stub([_row("w1")])
    payload = await _list(_user("ADMIN"))
    assert fake.user.finds == []
    assert "deletedById" not in payload["items"][0]
    assert "deletedByName" not in payload["items"][0]


# --------------------------------------------------------------------------------------
# 4. The total, which is what lets a card admit what it is not showing
# --------------------------------------------------------------------------------------


async def test_the_total_counts_the_whole_trash_and_not_the_page(stub):
    """Five deleted, two on the page, and the payload says five.

    This is the number a trash card subtracts from to say "3 more are not shown". Were it the page
    length, a truncated list would be indistinguishable from a complete one, which is the bug class
    this repository has shipped most often.
    """
    fake = stub([_row(f"w{n}", deleted_at=DELETED_AT) for n in range(5)])
    payload = await _list(_user("ADMIN"), deletedOnly=True, pageSize=2)
    assert len(payload["items"]) == 2
    assert payload["total"] == 5
    assert payload["pages"] == 3
    assert fake.designworkshop.finds[0]["take"] == 2


async def test_an_empty_trash_is_a_zero_and_not_an_absence(stub):
    """``total: 0`` with ``pages: 0`` — a shape a client can tell apart from a failed load.

    "Nothing has been deleted" and "we could not ask" must never render the same way, and the only
    thing that makes them distinguishable on the wire is that one of them is a payload at all.
    """
    stub([])
    payload = await _list(_user("ADMIN"), deletedOnly=True)
    assert payload == {"items": [], "total": 0, "page": 1, "pageSize": 20, "pages": 0}
