from types import SimpleNamespace

import pytest

import app.services.stage_definitions  # noqa: F401
from app.services import design_workshops as dw
from app.services.stage_schema import REF_SCOPE_ALL, REF_SCOPE_WORKSHOP, all_entities

WORKSHOP = "wsp_1"
PRODUCT_A = "prd_a"
PRODUCT_B = "prd_b"



def _process(pid: str, name: str, product_id: str, product_name: str, workshop_id=WORKSHOP):
    return SimpleNamespace(
        id=pid, name=name, notes=None, preProcessAvailable=False, recordedAt=None,
        status="APPROVED", workshopId=workshop_id, productId=product_id,
        product=SimpleNamespace(productName=product_name), steps=[],
    )


WORLD = [
    _process("prc_1", "Tying", PRODUCT_A, "Sambalpuri saree"),
    _process("prc_2", "Dyeing", PRODUCT_A, "Sambalpuri saree"),
    _process("prc_3", "Washing", PRODUCT_A, "Sambalpuri saree"),
    _process("prc_4", "Weaving", PRODUCT_A, "Sambalpuri saree"),
    _process("prc_9", "Block printing", PRODUCT_B, "Cotton stole"),
]


class _ProcessDelegate:
    def __init__(self, calls):
        self._calls = calls

    async def find_many(self, where=None, order=None, take=None, include=None):
        clauses = list((where or {}).get("AND", []))
        self._calls.append({"where": where, "order": order, "take": take, "include": include})
        rows = list(WORLD)
        for clause in clauses:
            for column, wanted in clause.items():
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



async def test_the_process_list_holds_only_the_chosen_products_processes(calls):
    payload = await _options(calls, filter_by=PRODUCT_A)
    assert payload["filtered"] is True
    assert payload["scopedToWorkshop"] is True
    assert [o["label"] for o in payload["options"]] == ["Dyeing", "Tying", "Washing", "Weaving"]


async def test_a_process_belonging_to_another_product_is_not_offered(calls):
    payload = await _options(calls, filter_by=PRODUCT_A)
    assert "Block printing" not in [o["label"] for o in payload["options"]]

    other = await _options(calls, filter_by=PRODUCT_B)
    assert [o["label"] for o in other["options"]] == ["Block printing"]


async def test_the_filtered_list_holds_several_and_nothing_selects_one(calls):
    payload = await _options(calls, filter_by=PRODUCT_A)
    assert len(payload["options"]) == 4
    assert payload["outOfScope"] is False and payload["outOfScopeOption"] is None
    assert set(payload) == {
        "model", "scope", "scopedToWorkshop", "filtered", "truncated", "outOfScope",
        "outOfScopeOption", "options", "tentativeFirst", "tentativeLabel",
    }
    assert isinstance(payload["tentativeFirst"], bool)
    assert payload["tentativeLabel"] is None or isinstance(payload["tentativeLabel"], str)


async def test_without_a_product_the_workshops_whole_process_list_is_offered(calls):
    payload = await _options(calls)
    assert payload["filtered"] is False
    assert len(payload["options"]) == 5


async def test_the_narrowing_is_a_where_clause_and_not_a_filter_over_a_fetched_page(calls):
    await _options(calls, filter_by=PRODUCT_A)
    where = calls[-1]["where"]
    assert {"productId": PRODUCT_A} in where["AND"]
    assert {"workshopId": WORKSHOP} in where["AND"]
    assert calls[-1]["take"] == dw.REFERENCE_LIMIT_DEFAULT + 1
    assert calls[-1]["order"] == {"name": "asc"}


async def test_the_product_id_is_not_sent_through_the_roster_resolver(calls, monkeypatch):
    async def _never(*_args, **_kw):
        raise AssertionError(
            "the product cascade resolved its parent through _artisan_id_behind, which is the "
            "roster resolver. Gate on spec.artisan_field, not on the lookup happening to miss."
        )

    monkeypatch.setattr(dw, "_artisan_id_behind", _never)
    payload = await _options(calls, filter_by=PRODUCT_A)
    assert len(payload["options"]) == 4



def test_the_process_model_declares_the_parent_column_the_cascade_needs():
    spec = dw.REFERENCE_MODELS["Process"]
    assert spec.filter_field == "productId"
    assert spec.artisan_field == "", (
        "Process has no artisan column; declaring one would send the product id through the roster "
        "resolver"
    )


def test_no_model_declares_two_parents():
    both = sorted(m for m, s in dw.REFERENCE_MODELS.items() if s.artisan_field and s.filter_field)
    assert both == []


def test_every_cascading_field_points_at_a_model_that_can_honour_it():
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
    from fastapi import HTTPException

    monkeypatch.setattr(dw, "db", _Db([]))
    for model in ("Artisan", "QuestionnaireInterview"):
        with pytest.raises(HTTPException) as raised:
            await dw.reference_options(RECORD, model, scope=REF_SCOPE_ALL, filter_by="anything")
        assert raised.value.status_code == 422
        assert "cannot be filtered by another record" in str(raised.value.detail)
