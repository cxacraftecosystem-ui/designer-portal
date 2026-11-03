import inspect

import pytest
from fastapi import HTTPException

from app.api.routes import annual_plan as routes
from app.services import annual_plan as service
from app.services.annual_plan_xlsx import MAX_PLAN_YEAR, MIN_PLAN_YEAR

SOURCE = inspect.getsource(routes)


def test_the_literal_paths_are_declared_above_the_id_route():
    first_id_route = SOURCE.index('@router.get("/{entry_id}")')
    for literal in ('"/pro-forma"', '"/years"', '"/export.xlsx"', '"/upload"', '@router.get("")'):
        assert SOURCE.index(literal) < first_id_route, literal


def test_no_literal_path_is_declared_below_the_id_family():
    tail = SOURCE[SOURCE.index('@router.get("/{entry_id}")') :]
    decorators = [line for line in tail.splitlines() if line.startswith("@router.")]
    assert decorators, "the tail must contain the id family"
    for line in decorators:
        assert "/{entry_id}" in line, line


READER_ARMS = {
    ("/annual-plan/years", "GET"),
    ("/annual-plan", "GET"),
    ("/annual-plan/{entry_id}", "GET"),
    ("/annual-plan/{entry_id}", "PATCH"),
}


def test_every_route_on_this_router_carries_the_annual_plan_gate():
    assert len(routes.router.routes) == 13
    for route in routes.router.routes:
        names = [d.call.__name__ for d in route.dependant.dependencies if d.call is not None]
        for method in route.methods:
            wanted = (
                "require_annual_plan_reader"
                if (route.path, method) in READER_ARMS
                else "require_annual_plan_manager"
            )
            assert wanted in names, f"{route.path} {method}: {names}"


def test_every_reader_arm_narrows_to_the_callers_scope():
    for name in ("list_plan_years", "list_annual_plan"):
        assert "plan_scope(" in inspect.getsource(getattr(routes, name)), name
    for name in ("get_annual_plan_entry", "update_annual_plan_entry"):
        assert "_entry_in_scope_or_404(" in inspect.getsource(getattr(routes, name)), name


def test_the_upload_takes_its_scalars_as_form_fields_and_not_query_parameters():
    assert "planYear: str | None = Form(" in SOURCE
    assert "withdrawAbsent: str | None = Form(" in SOURCE


def test_the_upload_parses_off_the_event_loop():
    squeezed = " ".join(SOURCE.split())
    assert "asyncio.to_thread( parse_annual_plan_workbook" in squeezed
    assert "asyncio.to_thread( build_annual_plan_workbook" in squeezed


@pytest.mark.parametrize("value", [None, "", "no", "0", "off", "maybe", "FALSE", " false "])
def test_the_withdraw_flag_defaults_to_false_for_every_unrecognised_value(value):
    assert routes._flag(value) is False


@pytest.mark.parametrize("value", ["true", "TRUE", " yes ", "1", "on", "On"])
def test_the_withdraw_flag_is_true_only_for_an_explicit_yes(value):
    assert routes._flag(value) is True


def test_the_plan_year_form_field_refuses_a_non_year_with_a_422():
    assert routes._plan_year_or_422(None) is None
    assert routes._plan_year_or_422("") is None
    assert routes._plan_year_or_422("  ") is None
    assert routes._plan_year_or_422("2026") == 2026

    with pytest.raises(HTTPException) as not_a_number:
        routes._plan_year_or_422("2O26")
    assert not_a_number.value.status_code == 422

    with pytest.raises(HTTPException) as out_of_range:
        routes._plan_year_or_422("1899")
    assert out_of_range.value.status_code == 422
    assert str(MIN_PLAN_YEAR) in out_of_range.value.detail
    assert str(MAX_PLAN_YEAR) in out_of_range.value.detail


def test_the_service_writes_through_the_transaction_client_and_not_the_singleton():
    source = inspect.getsource(service.apply_parsed_plan)
    body = source[source.index("async with db.tx(") :]
    assert "db.annualplanentry." not in body, "a write through the singleton inside the tx block"
    assert "tx.annualplanentry.create_many(" in body
    assert "tx.annualplanentry.update_many(" in body
    assert "_write_entry_update(tx," in body


def test_the_per_row_update_goes_through_one_named_seam():
    assert inspect.iscoroutinefunction(service._write_entry_update)
    assert "tx.annualplanentry.update(" in inspect.getsource(service._write_entry_update)


def test_the_sort_map_is_closed():
    assert set(routes._SORTS) == {"plannedStartDate", "workshopNo", "district", "updatedAt"}
    assert routes._list_order("withdrawnById", "asc")[0] == {"plannedStartDate": "asc"}
    assert routes._list_order("'; drop table", "desc")[0] == {"plannedStartDate": "desc"}


def test_every_listing_order_carries_a_tiebreak():
    for key in routes._SORTS:
        order = routes._list_order(key, "asc")
        assert len(order) == 2
        assert order[-1] == {"workshopNoKey": "asc"}


def test_the_list_filter_and_the_export_filter_are_the_same_function():
    assert "_list_where(" in inspect.getsource(routes.export_annual_plan)
    assert "_list_where(" in inspect.getsource(routes.list_annual_plan)


def test_the_filter_note_is_built_from_exactly_the_arguments_the_filter_narrows_on():
    narrows_on = set(inspect.signature(routes._list_where).parameters) - {"plan_year"}
    declares = set(inspect.signature(routes._filter_note).parameters)
    assert declares == narrows_on, (
        f"_list_where narrows on {sorted(narrows_on)} but _filter_note only describes "
        f"{sorted(declares)}; a filtered export would declare itself the whole year"
    )


def test_an_unfiltered_export_declares_no_filter_at_all():
    assert routes._filter_note(None, None, None, "all") is None
    assert routes._filter_note("", "", "", "") is None
    assert routes._filter_note("   ", None, None, "ALL") is None


@pytest.mark.parametrize(
    ("args", "expected"),
    [
        (("Bhuj", None, None, "all"), "Search: 'Bhuj'"),
        ((None, "Rajasthan", None, "all"), "State: Rajasthan"),
        ((None, None, "Kachchh", "all"), "District: Kachchh"),
        ((None, None, None, "planned"), "Standing: planned"),
        (("Bhuj", "Gujarat", None, "withdrawn"), "Search: 'Bhuj' · State: Gujarat · Standing: withdrawn"),
    ],
)
def test_a_filtered_export_says_which_filter_it_was_taken_under(args, expected):
    assert routes._filter_note(*args) == expected


def test_the_upload_refuses_to_withdraw_from_a_sheet_that_declares_itself_filtered():
    source = inspect.getsource(routes.upload_annual_plan)
    assert "parsed.filterNote" in source
    assert "withdraw_absent and parsed.filterNote" in source
    assert source.index("parsed.filterNote") < source.index("return await apply_parsed_plan(")
    assert "filter_note=_filter_note(" in inspect.getsource(routes.export_annual_plan)


@pytest.mark.parametrize(
    "standing,expected",
    [
        ("planned", {"withdrawnAt": None, "designWorkshopId": None}),
        ("promoted", {"withdrawnAt": None, "designWorkshopId": {"not": None}}),
        ("withdrawn", {"withdrawnAt": {"not": None}}),
    ],
)
def test_the_standing_filter_is_derived_from_the_two_columns_that_carry_it(standing, expected):
    where = routes._list_where(2026, None, None, None, standing)
    for key, value in expected.items():
        assert where[key] == value


def test_an_unknown_standing_filters_nothing_rather_than_everything():
    where = routes._list_where(2026, None, None, None, "PLANED")
    assert "withdrawnAt" not in where
    assert "designWorkshopId" not in where


def test_the_page_size_declares_the_bound_that_is_actually_enforced():
    from app.services.pagination import MAX_PAGE_SIZE

    assert "le=MAX_PAGE_SIZE" in SOURCE
    assert MAX_PAGE_SIZE == 100


def test_the_promote_arm_does_not_reach_for_the_workshop_create_gate():
    source = inspect.getsource(routes.promote_annual_plan_entry)
    assert "assert_can_create_design_workshops" not in source.split('"""')[-1]
    assert "open_design_workshop" not in source.split('"""')[-1], "the route calls promote_entry"


def test_the_upload_never_writes_to_the_design_workshop_table():
    source = inspect.getsource(service.apply_parsed_plan)
    for client in ("db", "tx"):
        assert f"{client}.designworkshop." not in source, f"{client}. reaches the workshop table"
    assert "designWorkshopId" in source, (
        "it may READ the promotion pointer — that is how `absentPromoted` is decided, and how a "
        "promoted row is kept out of the withdraw statement"
    )
