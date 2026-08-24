"""The process picker, narrowed by the product — driven through the REAL ``reference_options``.

WHY A SECOND CASCADE FILE, AND WHY IT NEEDS NO DATABASE. ``test_reference_resolver.py`` exercises
this endpoint end to end against Postgres, which is the right place for the artisan cascade and is
where the product-narrowing story belongs too. It is also skipped on every machine that has not got
Docker up, which on this repository is most of them — and the cascade being added here is the one
whose failure mode is a **422 on every open of both stage-5 process pickers**, i.e. the whole of
stage 5's process linkage dead for every designer. That is the same shape as the ``media``-include
500 that shipped and sat, and it must be catchable without a container.

So the WHERE-clause construction is asserted here, against a fake Prisma client that honours the two
clauses this feature builds (the workshop scope and the parent column) and records the query it was
asked. What Postgres does with a `{"productId": …}` clause is not in doubt; what is in doubt is
whether ``reference_options`` builds one at all, and for which column.

WHAT THIS FILE DELIBERATELY DOES NOT ASSERT: that the narrowing works offline. It does not, on
either client, and that is a known and accepted parity with the artisan cascade rather than an
oversight — the web picker has no cache and shows a problem, and Android's
``DwReferenceList.narrowedTo`` KEEPS every cached option whose ``filterValue`` is blank while
``_reference_option`` has never populated that key. One payload key would make three dormant
client-side mechanisms live; it is a separate, reversible change and is written up in the design
rather than folded in here.
"""

from types import SimpleNamespace

import pytest

# Importing this module is what installs the twenty-two stages into the registry.
import app.services.stage_definitions  # noqa: F401
from app.services import design_workshops as dw
from app.services.stage_schema import REF_SCOPE_ALL, REF_SCOPE_WORKSHOP, all_entities

# No `pytestmark` and no `@pytest.mark.asyncio`: `asyncio_mode = "auto"` in `pyproject.toml` is what
# lets an `async def test_` run, and it is load-bearing (its own comment there says so).
# NO POSTGRES MARK EITHER, deliberately — see the module docstring. The fake client below is the
# whole point, because the failure this guards is a 422 that would take out both stage-5 pickers on
# a tree where the DB-backed resolver tests are skipped.

WORKSHOP = "wsp_1"
PRODUCT_A = "prd_a"
PRODUCT_B = "prd_b"


# --------------------------------------------------------------------------------------
# A Prisma client that honours exactly the clauses this endpoint builds
# --------------------------------------------------------------------------------------


def _process(pid: str, name: str, product_id: str, product_name: str, workshop_id=WORKSHOP):
    """One ``Process`` row as the picker loads it, with the parent the cascade filters on.

    ``productId`` AND ``product`` BOTH, because the two are read by different halves and a fixture
    carrying only one would let a broken half pass: the WHERE clause reads the scalar column, and the
    sublabel reads the relation — which is the only thing on screen that separates "Tie and dye" at
    Bagru from "Tie and dye" at Bhuj.
    """
    return SimpleNamespace(
        id=pid, name=name, notes=None, preProcessAvailable=False, recordedAt=None,
        status="APPROVED", workshopId=workshop_id, productId=product_id,
        product=SimpleNamespace(productName=product_name), steps=[],
    )


WORLD = [
    # FOUR PROCESSES ON ONE PRODUCT, which is the owner's confirmed requirement and the reason a
    # one-process fixture would prove nothing: the filtered list legitimately holds several and
    # nothing may auto-select.
    _process("prc_1", "Tying", PRODUCT_A, "Sambalpuri saree"),
    _process("prc_2", "Dyeing", PRODUCT_A, "Sambalpuri saree"),
    _process("prc_3", "Washing", PRODUCT_A, "Sambalpuri saree"),
    _process("prc_4", "Weaving", PRODUCT_A, "Sambalpuri saree"),
    # Another product AT THE SAME WORKSHOP. This is the row the cascade exists to exclude, and it is
    # deliberately in scope: excluding an out-of-workshop row proves only that the scope clause works.
    _process("prc_9", "Block printing", PRODUCT_B, "Cotton stole"),
]


class _ProcessDelegate:
    """``find_many`` over :data:`WORLD`, honouring the AND-composed clauses and recording them."""

    def __init__(self, calls):
        self._calls = calls

    async def find_many(self, where=None, order=None, take=None, include=None):
        clauses = list((where or {}).get("AND", []))
        self._calls.append({"where": where, "order": order, "take": take, "include": include})
        rows = list(WORLD)
        for clause in clauses:
            for column, wanted in clause.items():
                # Only the two shapes this endpoint builds for this model. Anything else is a clause
                # the feature did not mean to build, and failing loudly beats filtering nothing.
                assert column in {"workshopId", "productId"}, (
                    f"reference_options built a clause this fake does not model: {clause!r}"
                )
                rows = [r for r in rows if getattr(r, column, None) == wanted]
        if order:
            (column, direction), = order.items()
            rows.sort(key=lambda r: str(getattr(r, column, "") or ""),
                      reverse=direction == "desc")
        return rows[: take] if take else rows


class _Db:
    def __init__(self, calls):
        self._calls = calls

    def __getattr__(self, name):
        assert name == "process", f"the cascade queried an unexpected delegate: {name}"
        return _ProcessDelegate(self._calls)


@pytest.fixture
def calls(monkeypatch):
    seen: list[dict] = []
    monkeypatch.setattr(dw, "db", _Db(seen))

    async def _all_readable(_viewer):
        return {}

    monkeypatch.setattr(dw, "viewable_where", _all_readable)
    return seen


RECORD = SimpleNamespace(id="dw_1", workshopId=WORKSHOP)


async def _options(calls, **kw):
    return await dw.reference_options(RECORD, "Process", scope=REF_SCOPE_WORKSHOP, **kw)


# --------------------------------------------------------------------------------------
# The narrowing
# --------------------------------------------------------------------------------------


async def test_the_process_list_holds_only_the_chosen_products_processes(calls):
    """THE SENTENCE THE REQUIREMENT IS MADE OF, on the mirror of the artisan cascade's own test."""
    payload = await _options(calls, filter_by=PRODUCT_A)
    assert payload["filtered"] is True
    assert payload["scopedToWorkshop"] is True
    assert [o["label"] for o in payload["options"]] == ["Dyeing", "Tying", "Washing", "Weaving"]


async def test_a_process_belonging_to_another_product_is_not_offered(calls):
    """The excluded row is at THIS workshop, so only the product clause can be excluding it."""
    payload = await _options(calls, filter_by=PRODUCT_A)
    assert "Block printing" not in [o["label"] for o in payload["options"]]

    # And the other way round, so the test cannot pass by filtering everything out.
    other = await _options(calls, filter_by=PRODUCT_B)
    assert [o["label"] for o in other["options"]] == ["Block printing"]


async def test_the_filtered_list_holds_several_and_nothing_selects_one(calls):
    """ONE PRODUCT HAS MANY PROCESSES — the owner confirmed it — so the narrowed list is a LIST.

    THE SERVER'S HALF OF "NOTHING MAY AUTO-SELECT" IS THAT THE PAYLOAD CARRIES NO SUCH SIGNAL. There
    is no `selected`, no `only`, no `pick`: the payload's four flags all describe how the LIST was
    narrowed (`scopedToWorkshop`, `filtered`, `truncated`) or name a row that is deliberately NOT in
    it (`outOfScope`). A client cannot be told by this endpoint that a single option should be taken.
    The clients' own halves are asserted in `frontend/e2e/cascade-process-product-unit.spec.ts`.
    """
    payload = await _options(calls, filter_by=PRODUCT_A)
    assert len(payload["options"]) == 4
    assert payload["outOfScope"] is False and payload["outOfScopeOption"] is None
    assert set(payload) == {
        "model", "scope", "scopedToWorkshop", "filtered", "truncated", "outOfScope",
        "outOfScopeOption", "options",
    }


async def test_without_a_product_the_workshops_whole_process_list_is_offered(calls):
    """The picker is not BROKEN by the cascade for a caller that sends no parent.

    Reaching this state through the web or the handset is not possible — `awaitingCascade` fetches
    nothing at all while the parent is blank, and Android's `needsParent` does the same — which is
    the point: an unnarrowed list must never be RENDERED on a control the descriptor says is
    narrowed. It is still the honest answer to the question actually asked, and `filtered: false` is
    what says so.
    """
    payload = await _options(calls)
    assert payload["filtered"] is False
    assert len(payload["options"]) == 5


async def test_the_narrowing_is_a_where_clause_and_not_a_filter_over_a_fetched_page(calls):
    """WHY THIS MATTERS MORE THAN IT LOOKS: the page cap applies AFTER narrowing.

    `REFERENCE_LIMIT_DEFAULT` is 50. With the clause in the WHERE, a designer gets 50 of THIS
    product's processes. A client-side narrowing would take 50 rows of the workshop's whole process
    list and then filter them — showing an EMPTY picker for any product whose processes all sort
    after row 50, which reads as "nothing was documented" for records that exist. The cascade
    therefore makes truncation LESS likely, not more.
    """
    await _options(calls, filter_by=PRODUCT_A)
    where = calls[-1]["where"]
    assert {"productId": PRODUCT_A} in where["AND"]
    assert {"workshopId": WORKSHOP} in where["AND"]
    # `take + 1`, which is how `truncated` is learnt without a second COUNT over an ILIKE scan.
    assert calls[-1]["take"] == dw.REFERENCE_LIMIT_DEFAULT + 1
    # `title`-style ordering is the model's own, ascending on the label column.
    assert calls[-1]["order"] == {"name": "asc"}


async def test_the_product_id_is_not_sent_through_the_roster_resolver(calls, monkeypatch):
    """THE GATE IS ON THE COLUMN, NOT ON A MISS, and this is what that sentence buys.

    `_artisan_id_behind` exists because a `filterBy` on the ARTISAN cascade may be a `DwParticipant`
    roster-entry id rather than an `Artisan` id. Fed a product id it *happens* to pass it through —
    it does `find_unique` on `DwStageEntry`, misses, and returns the candidate — so the artisan arm
    would appear to work here. Two things make relying on that wrong: it assumes a `DwStageEntry` id
    can never collide with a `ProductDocumentation` id, and its OTHER branch returns None for a
    hand-typed roster entry, which `reference_options` answers with an EMPTY list. A collision would
    therefore empty a picker that says `filtered: true` beside it.

    Asserted by making the resolver explode: if the product cascade ever routes through it, this
    fails with that message instead of silently working until the day it does not.
    """
    async def _never(*_args, **_kw):
        raise AssertionError(
            "the product cascade resolved its parent through _artisan_id_behind, which is the "
            "roster resolver. Gate on spec.artisan_field, not on the lookup happening to miss."
        )

    monkeypatch.setattr(dw, "_artisan_id_behind", _never)
    payload = await _options(calls, filter_by=PRODUCT_A)
    assert len(payload["options"]) == 4


# --------------------------------------------------------------------------------------
# The registry half: the declaration the runtime depends on
# --------------------------------------------------------------------------------------


def test_the_process_model_declares_the_parent_column_the_cascade_needs():
    """WITHOUT THIS COLUMN BOTH STAGE-5 PICKERS 422 ON EVERY OPEN.

    `reference_options` refuses a `filterBy` a model cannot honour, deliberately and loudly — the
    alternative is serving the whole table to a picker the designer believes is narrowed. So the
    moment either `processRef` declares `ref_filter_by`, `REFERENCE_MODELS["Process"]` must declare a
    filter column or the whole of stage 5's process linkage is dead for every designer. The two are
    one change and this is the assertion that says so.
    """
    spec = dw.REFERENCE_MODELS["Process"]
    assert spec.filter_field == "productId"
    # `Process.productId` is NON-NULLABLE, which is what makes the cascade total: every process has
    # a parent, so no row is unreachable through it.
    assert spec.artisan_field == "", (
        "Process has no artisan column; declaring one would send the product id through the roster "
        "resolver"
    )


def test_no_model_declares_two_parents():
    """The import-time check in ``design_workshops``, asserted where a reader will look for it.

    `ref_filter_by` names a SIBLING FIELD and the server never learns which model that sibling points
    at, so the filter column is a property of the MODEL. Two declarations would have to be resolved
    by a precedence rule invisible from the registry: the picker would narrow by the wrong parent,
    the payload would still say `filtered: true`, and the only symptom would be a designer choosing
    another record's child.
    """
    both = sorted(m for m, s in dw.REFERENCE_MODELS.items() if s.artisan_field and s.filter_field)
    assert both == []


def test_every_cascading_field_points_at_a_model_that_can_honour_it():
    """THE WHOLE-REGISTRY VERSION, so the next cascade cannot ship half-declared.

    This is the check `validate_registry` structurally cannot make: it lives in `stage_schema`, which
    must not import `design_workshops`, so it can verify that the SIBLING exists and nothing about
    whether the TARGET MODEL has a column to filter on. A field declaring `ref_filter_by` against a
    model with neither `artisan_field` nor `filter_field` is a picker that answers 422 on every open.
    """
    for _stage, entity in all_entities():
        for f in entity.fields:
            if not f.ref_filter_by or f.ref_model not in dw.REFERENCE_MODELS:
                continue
            spec = dw.REFERENCE_MODELS[f.ref_model]
            assert spec.filter_field or spec.artisan_field, (
                f"{entity.key}.{f.key} is narrowed by {f.ref_filter_by!r}, but "
                f"REFERENCE_MODELS[{f.ref_model!r}] declares no column to narrow on — so this "
                f"picker answers 422 every time it is opened"
            )


async def test_a_model_that_still_cannot_be_filtered_still_says_so(calls, monkeypatch):
    """The 422 is NARROWED by this change, not removed, and the two survivors are named.

    `Artisan` and `QuestionnaireInterview` declare neither parent column. `Artisan` because stage 3
    is where the roster is BUILT and there is nothing above it; `QuestionnaireInterview` because its
    link to artisans is a many-to-many and the filter arm applies a scalar column name, so a nested
    clause is not expressible in the dataclass as it stands. Both must keep refusing rather than
    quietly serving an unnarrowed list.
    """
    from fastapi import HTTPException

    monkeypatch.setattr(dw, "db", _Db([]))
    for model in ("Artisan", "QuestionnaireInterview"):
        with pytest.raises(HTTPException) as raised:
            await dw.reference_options(RECORD, model, scope=REF_SCOPE_ALL, filter_by="anything")
        assert raised.value.status_code == 422
        assert "cannot be filtered by another record" in str(raised.value.detail)
