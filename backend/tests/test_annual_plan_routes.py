"""``/api/annual-plan``'s doors, read off their own source. No database, no network.

Four of the things that can go wrong on this router leave no trace in any behavioural test:

* a literal path declared BELOW ``/{entry_id}`` answers 404 on a server where the route exists, and
  only for the paths that contain a dot (``export.xlsx``), so a smoke test of the other nine passes;
* a ``Form()`` scalar written as a bare default is read off the QUERY STRING, so a client that put
  it in the body is silently ignored under a 201;
* a write inside ``async with db.tx() as tx:`` issued through ``db`` instead of ``tx`` executes
  OUTSIDE the transaction, and every count in the report still adds up;
* an ungated arm on a router whose other nine are gated looks exactly like the nine until somebody
  finds it.

Each is asserted here by reading the source, because that is the only place the evidence is.
"""

import inspect

import pytest
from fastapi import HTTPException

from app.api.routes import annual_plan as routes
from app.services import annual_plan as service
from app.services.annual_plan_xlsx import MAX_PLAN_YEAR, MIN_PLAN_YEAR

SOURCE = inspect.getsource(routes)


def test_the_literal_paths_are_declared_above_the_id_route():
    """A path parameter is ``[^/]+``, WHICH MATCHES A DOT.

    So ``@router.get("/{entry_id}")`` declared before ``@router.get("/export.xlsx")`` swallows the
    export and answers it 404 "Record not found" — indistinguishable, from the client, from a server
    that has not shipped the feature. FastAPI matches in declaration order, so the order in this file
    IS the rule, and nothing else in the codebase records it.
    """
    first_id_route = SOURCE.index('@router.get("/{entry_id}")')
    for literal in ('"/pro-forma"', '"/years"', '"/export.xlsx"', '"/upload"', '@router.get("")'):
        assert SOURCE.index(literal) < first_id_route, literal


def test_no_literal_path_is_declared_below_the_id_family():
    """The rule above, stated in the direction somebody actually breaks it: by APPENDING a route.

    Every decorator after the first ``/{entry_id}`` must itself be an ``/{entry_id}`` route. This is
    the assertion that fails on the commit that adds ``GET /annual-plan/summary`` at the bottom of
    the file, which is exactly how it will be added.
    """
    tail = SOURCE[SOURCE.index('@router.get("/{entry_id}")') :]
    decorators = [line for line in tail.splitlines() if line.startswith("@router.")]
    assert decorators, "the tail must contain the id family"
    for line in decorators:
        assert "/{entry_id}" in line, line


def test_every_route_on_this_router_carries_the_annual_plan_gate():
    """ALL TEN, THE GETS INCLUDED.

    The plan is a list of named places and dates the ministry has not announced. A read gate one
    tier looser than the write gate would make the unannounced plan browsable by people who cannot
    be told apart from those who may change it — the reason ``require_access_manager`` gates its own
    queue's reads.
    """
    assert len(routes.router.routes) == 10
    for route in routes.router.routes:
        names = [d.call.__name__ for d in route.dependant.dependencies if d.call is not None]
        assert "require_annual_plan_manager" in names, f"{route.path} {sorted(route.methods)}"


def test_the_upload_takes_its_scalars_as_form_fields_and_not_query_parameters():
    """``planYear`` and ``withdrawAbsent`` are ``Form(...)``, and both are ``str | None``.

    Left as bare defaults FastAPI reads them off the query string. Typed as ``int``/``bool`` they
    422 on the empty string a browser's untouched ``FormData`` field actually sends. Both spellings
    are wrong in a way a 201 hides.
    """
    assert "planYear: str | None = Form(" in SOURCE
    assert "withdrawAbsent: str | None = Form(" in SOURCE


def test_the_upload_parses_off_the_event_loop():
    """300 rows of openpyxl is pure CPU, and this process serves every other request while it runs.

    Per BATCH, never per row — the house rule. (``questionnaire_forms`` parses INLINE at two call
    sites; that is a pre-existing defect named in this module's docstring and deliberately not
    copied.)
    """
    squeezed = " ".join(SOURCE.split())
    assert "asyncio.to_thread( parse_annual_plan_workbook" in squeezed
    assert "asyncio.to_thread( build_annual_plan_workbook" in squeezed


@pytest.mark.parametrize("value", [None, "", "no", "0", "off", "maybe", "FALSE", " false "])
def test_the_withdraw_flag_defaults_to_false_for_every_unrecognised_value(value):
    """A DESTRUCTIVE DEFAULT MUST NOT BE REACHABLE BY A TYPO.

    ``withdrawAbsent`` true withdraws every planned workshop the uploaded sheet does not mention. A
    partial correction sheet of twelve rows would take 288 workshops out of a ministry's directory in
    one press.
    """
    assert routes._flag(value) is False


@pytest.mark.parametrize("value", ["true", "TRUE", " yes ", "1", "on", "On"])
def test_the_withdraw_flag_is_true_only_for_an_explicit_yes(value):
    assert routes._flag(value) is True


def test_the_plan_year_form_field_refuses_a_non_year_with_a_422():
    """And an absent or blank field is ABSENCE, not a value — the workbook may name the year itself."""
    assert routes._plan_year_or_422(None) is None
    assert routes._plan_year_or_422("") is None
    assert routes._plan_year_or_422("  ") is None
    assert routes._plan_year_or_422("2026") == 2026

    with pytest.raises(HTTPException) as not_a_number:
        routes._plan_year_or_422("2O26")  # a capital O, which is how this is actually mistyped
    assert not_a_number.value.status_code == 422

    with pytest.raises(HTTPException) as out_of_range:
        routes._plan_year_or_422("1899")
    assert out_of_range.value.status_code == 422
    assert str(MIN_PLAN_YEAR) in out_of_range.value.detail
    assert str(MAX_PLAN_YEAR) in out_of_range.value.detail


def test_the_service_writes_through_the_transaction_client_and_not_the_singleton():
    """``db.tx()`` HANDS BACK A DIFFERENT CLIENT, and getting this wrong is invisible.

    ``await db.annualplanentry.update(...)`` written inside ``async with db.tx() as tx:`` executes
    OUTSIDE the transaction. Every count in the report still adds up; every test that asserts counts
    still passes; the all-or-nothing property the write is built for simply does not exist. This is
    the only cheap thing that catches a refactor re-introducing the module singleton.
    """
    source = inspect.getsource(service.apply_parsed_plan)
    body = source[source.index("async with db.tx(") :]
    assert "db.annualplanentry." not in body, "a write through the singleton inside the tx block"
    assert "tx.annualplanentry.create_many(" in body
    assert "tx.annualplanentry.update_many(" in body
    assert "_write_entry_update(tx," in body


def test_the_per_row_update_goes_through_one_named_seam():
    """Two reasons, and the second is what makes the transaction testable at all.

    One named function makes "wrote through ``db`` instead of ``tx``" a one-line diff rather than a
    habit; and it is the seam ``test_a_failure_after_the_creates_leaves_nothing_written``
    monkeypatches to provoke a failure AFTER the ``create_many`` has landed.
    """
    assert inspect.iscoroutinefunction(service._write_entry_update)
    assert "tx.annualplanentry.update(" in inspect.getsource(service._write_entry_update)


def test_the_sort_map_is_closed():
    """``order`` reaches Prisma. An open passthrough would let a caller sort by any column on the
    model — the four account pointers included — which is both an information leak and a free way to
    make the database sort on an unindexed column."""
    assert set(routes._SORTS) == {"plannedStartDate", "workshopNo", "district", "updatedAt"}
    assert routes._list_order("withdrawnById", "asc")[0] == {"plannedStartDate": "asc"}
    assert routes._list_order("'; drop table", "desc")[0] == {"plannedStartDate": "desc"}


def test_every_listing_order_carries_a_tiebreak():
    """Two hundred rows planned for the same day are a normal year.

    Without a tiebreak Postgres may return equal rows in a different order per page, so page 2
    repeats a row page 1 showed and drops one nobody ever sees — silently, with the total correct.
    """
    for key in routes._SORTS:
        order = routes._list_order(key, "asc")
        assert len(order) == 2
        assert order[-1] == {"workshopNoKey": "asc"}


def test_the_list_filter_and_the_export_filter_are_the_same_function():
    """An export that filtered differently from the list would hand an administrator a "corrected"
    sheet missing rows they were looking at — and if they then ticked the withdraw box, the upload
    would withdraw exactly those rows."""
    assert "_list_where(" in inspect.getsource(routes.export_annual_plan)
    assert "_list_where(" in inspect.getsource(routes.list_annual_plan)


def test_the_filter_note_is_built_from_exactly_the_arguments_the_filter_narrows_on():
    """**THE INVARIANT THE WHOLE WITHDRAW GUARD RESTS ON.**

    ``_filter_note`` is what a filtered export writes onto its Details sheet, and it is the only
    thing that lets the upload tell a corrected copy of the WHOLE year from a corrected copy of
    forty rows of it. A fifth filter added to ``_list_where`` and not to ``_filter_note`` produces a
    sheet that is partial and declares itself whole — the exact state that, re-uploaded with
    "withdraw the workshops missing from this sheet" ticked, withdraws every row the filter hid.

    ``plan_year`` is excluded from the comparison because it is not a narrowing OF the year, it IS
    the year, and the Details sheet carries it on its own labelled row.
    """
    narrows_on = set(inspect.signature(routes._list_where).parameters) - {"plan_year"}
    declares = set(inspect.signature(routes._filter_note).parameters)
    assert declares == narrows_on, (
        f"_list_where narrows on {sorted(narrows_on)} but _filter_note only describes "
        f"{sorted(declares)}; a filtered export would declare itself the whole year"
    )


def test_an_unfiltered_export_declares_no_filter_at_all():
    """``standing="all"`` IS NOT A FILTER, and this is the assertion that keeps the guard alive.

    "all" is what the screen loads with. A marker written on every export would make the upload
    refuse ``withdrawAbsent`` for every file the product has ever handed out — the feature would be
    dead rather than guarded, and within a week somebody would delete the guard rather than the
    cause.
    """
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
    """A SENTENCE AND NOT A FLAG, because the only consumer is a 422 an administrator reads.

    "This sheet was exported with Search: 'Bhuj'" tells them which box to clear; ``partial: true``
    tells them to go and find out.
    """
    assert routes._filter_note(*args) == expected


def test_the_upload_refuses_to_withdraw_from_a_sheet_that_declares_itself_filtered():
    """Read at the source, because the behavioural version needs a database and this is the gate.

    ``apply_parsed_plan`` reads the comparison set as the whole year unconditionally — its
    ``find_many`` is ``where={"planYear": …}`` and it takes no filter argument — so every row a
    filtered sheet does not name is "absent", and with the box ticked absent means ``withdrawnAt``
    stamped. Forty rows exported out of three hundred withdrew the other two hundred and sixty.
    """
    source = inspect.getsource(routes.upload_annual_plan)
    assert "parsed.filterNote" in source
    assert "withdraw_absent and parsed.filterNote" in source
    # AND THE REFUSAL IS RAISED BEFORE apply_parsed_plan IS EVER CALLED. A guard that ran after the
    # withdraw would be a sentence attached to the damage.
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
    """There is no ``status`` column to filter on, and there must not be one: standing is PLANNED /
    PROMOTED / WITHDRAWN derived from ``withdrawnAt`` and ``designWorkshopId``, in one place."""
    where = routes._list_where(2026, None, None, None, standing)
    for key, value in expected.items():
        assert where[key] == value


def test_an_unknown_standing_filters_nothing_rather_than_everything():
    """A typo in a query parameter must widen to "all", never narrow to nothing. A screen that
    silently shows zero rows reads as "the plan was not uploaded"."""
    where = routes._list_where(2026, None, None, None, "PLANED")
    assert "withdrawnAt" not in where
    assert "designWorkshopId" not in where


def test_the_page_size_declares_the_bound_that_is_actually_enforced():
    """``GET /designers/roster`` declares ``le=200`` while ``normalize_pagination`` clamps at 100, so
    a caller who asks for 200 is answered 100 with no explanation. That pattern is deliberately NOT
    copied: the declared bound here is the enforced one."""
    from app.services.pagination import MAX_PAGE_SIZE

    assert "le=MAX_PAGE_SIZE" in SOURCE
    assert MAX_PAGE_SIZE == 100


def test_the_promote_arm_does_not_reach_for_the_workshop_create_gate():
    """A MINISTRY_ADMIN is not in ``DESIGN_WORKSHOP_CREATOR_ROLES``, and widening that set to fit
    this door would hand every ministry admin the ordinary create button as well. Two doors, two
    gates, one creation path."""
    source = inspect.getsource(routes.promote_annual_plan_entry)
    assert "assert_can_create_design_workshops" not in source.split('"""')[-1]
    assert "open_design_workshop" not in source.split('"""')[-1], "the route calls promote_entry"


def test_the_upload_never_writes_to_the_design_workshop_table():
    """THE NATURAL-LOOKING "KEEP THEM IN STEP" CHANGE, refused at the source.

    Updating the promoted workshop's venue when the plan's venue changes silently rewrites a
    fortnight of somebody's fieldwork header from a planning document — and is then overwritten
    again by their next stage save. The upload path must not mention that table at all.
    """
    source = inspect.getsource(service.apply_parsed_plan)
    for client in ("db", "tx"):
        assert f"{client}.designworkshop." not in source, f"{client}. reaches the workshop table"
    assert "designWorkshopId" in source, (
        "it may READ the promotion pointer — that is how `absentPromoted` is decided, and how a "
        "promoted row is kept out of the withdraw statement"
    )
