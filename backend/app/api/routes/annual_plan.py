"""``/api/annual-plan`` — the ministry's annual directory of planned workshops.

══ WHAT AN ADMINISTRATOR DOES HERE ═══════════════════════════════════════════════════════════════

Downloads a pro-forma, types 200-300 planned workshops into it, uploads it, and reads a report of
what landed. Then, two or three times a season, corrects the same spreadsheet and uploads it again —
which is the feature, and which is why every write on this prefix is keyed on
``(planYear, workshopNoKey)`` rather than on a row id nobody outside this database has.

══ THIS PREFIX IS NOT ``/design-workshops``, AND MUST NEVER BE MOUNTED UNDER IT ══════════════════

A row here is a LINE IN A DOCUMENT and not a workshop. A shared prefix would put it one
``/{workshop_id}`` away from ``load_workshop_or_404``, which is the function the entire access model
of the real table rests on — and the next reader would widen that loader to fit. The boundary, and
the list of every surface a planned row must never appear on, is in
``app/services/annual_plan.py``'s module docstring; ``tests/test_annual_plan_is_not_a_workshop.py``
asserts it surface by surface.

══ EVERY ARM IS GATED, READ INCLUDED ═════════════════════════════════════════════════════════════

``require_annual_plan_manager`` guards the GETs as well as the writes, for the reason
``require_access_manager`` gives about its own queue: the plan is a list of named places and dates
the ministry HAS NOT ANNOUNCED. A read gate one tier looser than the write gate would make the
unannounced plan browsable by people who cannot be told apart from those who may change it.

``can_manage_annual_plan`` and ``ANNUAL_PLAN_REFUSAL`` live in ``app/services/annual_plan.py``
rather than in ``app/core/deps.py``, where every other ``can_*`` predicate in this product lives.
That is not a design opinion — ``deps.py`` was owned by another change in flight when this landed,
exactly as it was when ``sanction_orders.can_record_sanction_orders`` landed — and moving both is a
welcome follow-up. The dependency below is defined here so the refusal is spelled exactly once.

══ THE PARSE RUNS OFF THE EVENT LOOP, AND THE EXISTING PARSER'S DOES NOT ═════════════════════════

``asyncio.to_thread`` wraps the two calls that touch row data, per the house rule (per BATCH, never
per row). ``questionnaire_forms`` calls ``parse_questionnaire_workbook`` INLINE at two call sites —
that is a pre-existing defect, it is deliberately not copied here, and it is deliberately not fixed
here either. If somebody sets out to "make the two consistent", the direction is to thread the
questionnaire's, not to un-thread this one.

══ ROUTE DECLARATION ORDER IS LOAD-BEARING ═══════════════════════════════════════════════════════

Every LITERAL path is declared above ``/{entry_id}``. A path parameter is ``[^/]+``, which matches a
dot, so ``GET /annual-plan/export.xlsx`` declared after ``GET /annual-plan/{entry_id}`` is answered
404 "Record not found" on a server where the route exists — the failure ``datasets.py`` and
``design_workshop_oversight.py`` both carry this warning about.
``tests/test_annual_plan_routes.py::test_the_literal_paths_are_declared_above_the_id_route`` reads
this module's source and fails if the order is ever disturbed.
"""

from __future__ import annotations

import asyncio
from typing import Any

from fastapi import (
    APIRouter,
    Depends,
    File,
    Form,
    HTTPException,
    Query,
    Request,
    UploadFile,
    status,
)
from fastapi.responses import Response

from app.core.db import db
from app.core.deps import get_current_user
from app.schemas.annual_plan import AnnualPlanEntryUpdate, AnnualPlanPromoteRequest
from app.services.annual_plan import (
    ANNUAL_PLAN_REFUSAL,
    apply_parsed_plan,
    can_manage_annual_plan,
    entry_payload,
    plan_year_label,
    promote_entry,
    reinstate_entry,
    standing_of,
    withdraw_entry,
)
from app.services.annual_plan_xlsx import (
    MAX_PLAN_ROWS,
    MAX_PLAN_YEAR,
    MIN_PLAN_YEAR,
    PRO_FORMA_FILENAME,
    AnnualPlanXlsxError,
    build_annual_plan_pro_forma,
    build_annual_plan_workbook,
    export_filename,
    parse_annual_plan_workbook,
)
from app.services.design_workshops import workshop_summary
from app.services.pagination import MAX_PAGE_SIZE, normalize_pagination, page_payload
from app.services.records import contains
from app.services.uploads import read_workbook_upload
from app.services.xlsx_report import xlsx_response

router = APIRouter(prefix="/annual-plan", tags=["annual-plan"])

# A directory of three hundred rows of short text is under a hundred kilobytes. Four megabytes is
# forty times that, which leaves room for a ministry letterhead image and a year of accumulated
# formatting, and refuses a file that can only be a mistake — the chosen-the-wrong-thing case this
# ceiling is actually for. It is HALF the questionnaire's 8 MiB and that is not an oversight: a
# questionnaire legitimately carries a thousand questions with every recorded answer beside them,
# and this carries at most six hundred rows of thirteen short columns.
#
# WHAT THIS BOUNDS IS THE HEAP AND NOT THE NETWORK. By the time it is consulted, starlette has
# already read the body off the socket and spooled anything over 1 MiB to disk — see the correction
# in `services/uploads.py`'s own docstring. The only ceiling on what the BOX accepts is
# `client_max_body_size` in `infra/terraform/user_data.sh`.
MAX_UPLOAD_BYTES = 4 * 1024 * 1024

_WRONG_TYPE_DETAIL = (
    "That is not an Excel workbook. Download the annual plan pro-forma, type the directory into it, "
    "and upload that — or use File > Save As in Excel and choose 'Excel Workbook (.xlsx)'."
)
_EMPTY_DETAIL = "The upload was empty. Attach the filled-in annual plan pro-forma."

#: The four orderings the list offers, mapped to the column each one actually sorts on. A closed map
#: rather than a passthrough, because `order` reaches Prisma: an open one would let a caller order by
#: any column on the model, including the four account pointers, which is both an information leak
#: and a free way to make the database sort on an unindexed column.
_SORTS: dict[str, str] = {
    "plannedStartDate": "plannedStartDate",
    "workshopNo": "workshopNoKey",
    "district": "district",
    "updatedAt": "updatedAt",
}


async def require_annual_plan_manager(current_user: Any = Depends(get_current_user)) -> Any:
    """Gates EVERY arm of /api/annual-plan, read included — see the module docstring.

    The refusal sentence is IMPORTED rather than retyped. It is said on three surfaces (this 403,
    the web route guard's lock panel, and the nav entry that is simply absent for anyone below the
    floor), and a refusal naming a different next move depending on where you met it is not a rule,
    it is three rumours.
    """
    if not can_manage_annual_plan(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=ANNUAL_PLAN_REFUSAL)
    return current_user


def _flag(value: str | None) -> bool:
    """A multipart checkbox as a bool. EVERYTHING that is not an explicit yes is FALSE.

    THE DEFAULT HAS TO BE THE SAFE ONE AND IT HAS TO BE UNREACHABLE BY A TYPO. The flag this reads
    is ``withdrawAbsent``, and a true answer withdraws every planned workshop the uploaded sheet
    does not mention. A partial correction sheet of twelve rows would take 288 planned workshops out
    of the ministry's directory in one press. So "maybe", "FALSE", "0", "off", "" and a missing field
    are all false, and only "1"/"true"/"yes"/"on" — case-insensitively, trimmed — are true.
    """
    return (value or "").strip().lower() in {"1", "true", "yes", "on"}


def _plan_year_or_422(value: str | None) -> int | None:
    """The year the administrator chose on the upload panel, or ``None`` for "they did not choose".

    A FORM SCALAR VALIDATED IN THE ROUTE, not a pydantic field, because the body is multipart — the
    rule ``questionnaire_forms._kind_or_422`` states about its own. The bound is named in the
    refusal: "that is not a valid year" about a box the person can see but cannot re-read is a
    message they cannot act on.
    """
    if value is None:
        return None
    text = value.strip()
    if not text:
        # AN APPENDED EMPTY STRING IS ABSENCE, NOT A VALUE. A browser `FormData` that appends an
        # untouched field sends "", and treating that as a year the administrator chose would refuse
        # an upload whose Details sheet names the year perfectly well.
        return None
    try:
        year = int(text)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"'{text}' is not a year. Choose the financial year this directory is for, or "
                "leave it blank and let the workbook's own Details sheet say."
            ),
        ) from exc
    if not MIN_PLAN_YEAR <= year <= MAX_PLAN_YEAR:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"{year} is not a plan year this directory can hold — it has to be between "
                f"{MIN_PLAN_YEAR} and {MAX_PLAN_YEAR}."
            ),
        )
    return year


async def _entry_or_404(entry_id: str) -> Any:
    """One row, or the repository's standard 404.

    ``"Record not found"`` BYTE-FOR-BYTE, matching every other single-row read in this product. A
    404 that described what was missing would tell a caller who may not read this table that the id
    they guessed is a real plan row.
    """
    row = await db.annualplanentry.find_unique(
        where={"id": entry_id}, include={"designWorkshop": True}
    )
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return row


# --------------------------------------------------------------------------------------
# The literal paths. EVERY ONE OF THEM IS ABOVE `/{entry_id}` — see the module docstring.
# --------------------------------------------------------------------------------------


@router.get("/pro-forma")
async def download_pro_forma(_: Any = Depends(require_annual_plan_manager)) -> Response:
    """The blank .xlsx the ministry's directory is typed into.

    GENERATED ON EVERY REQUEST RATHER THAN CACHED, for the reason the questionnaire's pro-forma
    gives: it is a few kilobytes of openpyxl, and a stale cached copy whose headings no longer match
    the parser is somebody typing three hundred rows under headings the app has stopped recognising.

    INLINE AND NOT ON A THREAD, unlike the two calls that touch row data. This builds three sheets
    and no rows; the thread hop would cost more than the work.
    """
    return xlsx_response(build_annual_plan_pro_forma(), PRO_FORMA_FILENAME)


@router.get("/years")
async def list_plan_years(_: Any = Depends(require_annual_plan_manager)) -> list[dict[str, Any]]:
    """Every year the directory holds, newest first, with its standing counted.

    ONE READ AND A COUNT IN PYTHON, deliberately. The alternative is four `group_by` round trips or a
    raw-SQL aggregate, and this table is bounded at `MAX_PLAN_ROWS` rows per year over a handful of
    years — the whole directory is smaller than one workshop's stage entries.
    """
    rows = await db.annualplanentry.find_many(order={"planYear": "desc"})
    years: dict[int, dict[str, Any]] = {}
    for row in rows:
        bucket = years.setdefault(
            row.planYear,
            {
                "planYear": row.planYear,
                "planYearLabel": plan_year_label(row.planYear),
                "total": 0,
                "planned": 0,
                "promoted": 0,
                "withdrawn": 0,
            },
        )
        bucket["total"] += 1
        bucket[standing_of(row).lower()] += 1
    return sorted(years.values(), key=lambda item: item["planYear"], reverse=True)


@router.get("/export.xlsx")
async def export_annual_plan(
    planYear: int = Query(...),
    search: str | None = Query(default=None),
    state: str | None = Query(default=None),
    district: str | None = Query(default=None),
    standing: str = Query(default="all"),
    sort: str = Query(default="plannedStartDate"),
    dir: str = Query(default="asc"),
    _: Any = Depends(require_annual_plan_manager),
) -> Response:
    """The current directory as the same workbook the pro-forma is — the download half of
    edit-in-Excel.

    THE SAME FILTERS AS THE LIST AND NO PAGING, capped at ``MAX_PLAN_ROWS``: an administrator
    exporting what they are looking at must get what they are looking at, and a page of fifty would
    be a corrected sheet that silently withdrew the other two hundred and fifty on re-upload if they
    ticked the box.

    ⚠ **AND A FILTER PRODUCES EXACTLY THE SHAPE THAT PARAGRAPH REFUSES TO SHIP AS A PAGE**, which
    is why :func:`_filter_note` exists. The hazard was closed for paging and left open for the
    filters, and the filtered file is the more dangerous of the two because nothing about it looks
    partial: it arrives under ``annual-plan-2026-27.xlsx``, its Details sheet said "Plan year:
    2026-27" and nothing else, and ``apply_parsed_plan`` reads the comparison set as the WHOLE year
    unconditionally. Filter the list to forty of three hundred rows, correct two venues, re-upload
    with "withdraw the workshops missing from this sheet" ticked, and one ``update_many`` withdraws
    the two hundred and fifty-eight the filter hid. So a filtered export now WRITES the filter onto
    its own Details sheet, and ``upload_annual_plan`` refuses ``withdrawAbsent`` for a sheet that
    declares one. The filters stay — an administrator exporting what they are looking at still gets
    what they are looking at — and the file carries the one fact the upload could not otherwise
    learn, because an upload sees bytes and a form field and never the query string this download
    was taken under.

    THE FILENAME IS PURE ASCII, so ``xlsx_response``'s plain ``filename="…"`` header is sufficient.
    The RFC 6266 ``filename*`` form ``data_browser._content_disposition`` builds exists for
    Devanagari RECORD names and is not needed here. Said out loud so nobody "fixes" the simpler
    header into the harder one.
    """
    where = _list_where(planYear, search, state, district, standing)
    rows = await db.annualplanentry.find_many(
        where=where, order=_list_order(sort, dir), take=MAX_PLAN_ROWS
    )
    payload = await asyncio.to_thread(
        build_annual_plan_workbook,
        plan_year=planYear,
        filter_note=_filter_note(search, state, district, standing),
        rows=[
            {
                "workshopNo": row.workshopNo,
                "plannedTitle": row.plannedTitle,
                "workshopKind": row.workshopKind,
                "craftName": row.craftName,
                "clusterName": row.clusterName,
                "state": row.state,
                "district": row.district,
                "venue": row.venue,
                "plannedStartDate": row.plannedStartDate,
                "plannedEndDate": row.plannedEndDate,
                "implementingAgency": row.implementingAgency,
                "sponsor": row.sponsor,
                "notes": row.notes,
            }
            for row in rows
        ],
    )
    return xlsx_response(payload, export_filename(planYear))


@router.post("/upload", status_code=status.HTTP_201_CREATED)
async def upload_annual_plan(
    request: Request,
    file: UploadFile = File(...),
    planYear: str | None = Form(default=None),
    withdrawAbsent: str | None = Form(default=None),
    current_user: Any = Depends(require_annual_plan_manager),
) -> dict[str, Any]:
    """Upload — or re-upload a corrected copy of — one year's directory of planned workshops.

    ``planYear`` AND ``withdrawAbsent`` ARE ``Form()`` FIELDS ON THE SAME MULTIPART BODY, NOT QUERY
    PARAMETERS. Left as bare defaults, FastAPI would read them off the QUERY STRING, and a client
    that put them in the body would have had them silently ignored — a directory filed under the
    wrong year, or a withdrawal that did not happen, under a 201 saying it all went fine. That is
    the trap ``upload_questionnaire`` names in its own docstring, and it is the reason both are
    typed ``str | None`` and validated by hand: a ``bool`` or ``int`` form field would 422 on an
    empty string, which is what a browser's untouched field actually sends.

    ``request`` IS NOT A PARAMETER ANY CLIENT SENDS. FastAPI fills it from the connection; it is
    here only so the size gate can read the declared ``Content-Length`` and refuse an oversized
    workbook before copying it into the heap.

    THE RESPONSE CARRIES THE REPORT AND THAT LIST IS THE FEATURE. An administrator who uploads three
    hundred rows and is shown a count has no way to find out which rows changed, which were skipped
    or why — and the likeliest causes (a merged cell, a date typed as text, a workshop number that
    picked up a non-breaking space) are all invisible from the result.

    ⚠ **A SHEET THAT DECLARES ITSELF A FILTERED VIEW IS REFUSED ``withdrawAbsent``**, 422, naming
    the filter it declares. See the guard below and ``export_annual_plan``: an upload is compared
    against the WHOLE year, so a forty-row export of a three-hundred-row year, re-uploaded with
    that box ticked, withdraws the two hundred and sixty rows the filter hid. Every other upload of
    such a sheet is untouched — it corrects the rows it names and leaves the rest alone, which is
    what a partial correction has always meant here.
    """
    content = await read_workbook_upload(
        file,
        MAX_UPLOAD_BYTES,
        request=request,
        purpose="annual plan workbook",
        wrong_type_detail=_WRONG_TYPE_DETAIL,
        empty_detail=_EMPTY_DETAIL,
    )
    try:
        # OFF THE EVENT LOOP, PER BATCH. Three hundred rows of openpyxl is tens of milliseconds of
        # pure CPU, and this process serves every other request while it runs.
        parsed = await asyncio.to_thread(
            parse_annual_plan_workbook, content, filename=file.filename
        )
    except AnnualPlanXlsxError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    typed = _plan_year_or_422(planYear)
    if typed is not None and parsed.planYear is not None and typed != parsed.planYear:
        # THE SAME SHAPE AS THE QUESTIONNAIRE'S WRONG-WORKBOOK 409, and for the same reason: filing
        # this sheet under the year the form field names would write three hundred rows into a year
        # the document itself says it is not for — and a later correction uploaded under the RIGHT
        # year would then create three hundred more beside them, with nothing linking the two sets.
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"This workbook says it is the {plan_year_label(parsed.planYear)} plan and it was "
                f"uploaded as the {plan_year_label(typed)} plan. Correct the 'Plan year' on the "
                "workbook's Details sheet, or choose the year the workbook names."
            ),
        )
    year = typed if typed is not None else parsed.planYear
    if year is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "This workbook does not say which year's plan it is. Type the financial year on "
                "its Details sheet, or choose a year before uploading."
            ),
        )

    withdraw_absent = _flag(withdrawAbsent)
    if withdraw_absent and parsed.filterNote:
        # ── THE ONE COMBINATION THAT WITHDRAWS WORKSHOPS NOBODY MEANT TO WITHDRAW ────────────────
        #
        # `apply_parsed_plan` compares this sheet against the WHOLE year (its `find_many` is
        # `where={"planYear": …}` and takes no filter argument), so every row the sheet does not
        # name is "absent" — and with the box ticked, absent means `withdrawnAt` stamped. That is
        # right for a corrected copy of the year and catastrophic for a corrected copy of forty
        # rows of it: 258 workshops leave the Planned standing in one `update_many`, `promote_entry`
        # then 409s any of them somebody tries to open, and each carries a `withdrawnById` recording
        # a decision nobody took.
        #
        # REFUSED RATHER THAN SILENTLY DOWNGRADED TO `withdraw_absent=False`. An administrator who
        # ticked that box came to withdraw something; quietly not doing it would leave them
        # believing the plan had been pruned, which is the same class of error in the other
        # direction. The refusal names the filter the sheet itself declares and the one action that
        # gets them what they meant.
        #
        # THIS GUARD ONLY SEES SHEETS THAT SAY THEY ARE PARTIAL — a file exported before the marker
        # existed, or one typed from scratch, declares nothing and passes. That is deliberate (see
        # `annual_plan_xlsx._details_filter`) and it is why the export writes the marker at all.
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"This workbook says it is a filtered view of the "
                f"{plan_year_label(year)} plan ({parsed.filterNote}), so it holds only some of the "
                f"year's rows — and 'withdraw the workshops missing from this sheet' would withdraw "
                f"every row the filter left out. Upload it without that box ticked to correct the "
                f"rows it does name, or clear the filters on the list, export the whole year again "
                f"and upload that if you do mean to withdraw workshops."
            ),
        )

    return await apply_parsed_plan(
        parsed,
        plan_year=year,
        actor_id=current_user.id,
        source_filename=file.filename,
        withdraw_absent=withdraw_absent,
    )


def _filter_note(
    search: str | None, state: str | None, district: str | None, standing: str
) -> str | None:
    """The sentence a filtered export writes onto its own Details sheet, or ``None`` for the year.

    **DERIVED FROM THE SAME FOUR ARGUMENTS ``_list_where`` NARROWS ON, AND THAT IS THE INVARIANT.**
    A fifth filter added to ``_list_where`` and not to this function is a sheet that is partial and
    says it is whole — which is the whole defect this pair exists to close — so
    ``tests/test_annual_plan_routes.py`` pins the two signatures against each other. ``planYear`` is
    excluded on purpose: it is not a narrowing of the year, it IS the year, and the Details sheet
    already carries it on its own labelled row.

    ``standing="all"`` IS NOT A FILTER and must not produce a note. It is the default the screen
    loads with, and a marker written on every single export would make the upload refuse
    ``withdrawAbsent`` for every file the product has ever handed out — the feature would be dead
    rather than guarded, and within a week somebody would delete the guard rather than the cause.

    A SENTENCE AND NOT A FLAG, because the only consumer is a 422 an administrator reads: "this
    sheet was exported with Search: 'Bhuj'" tells them which box to clear, and ``partial: true``
    tells them to go and find out.
    """
    parts: list[str] = []
    if term := (search or "").strip():
        parts.append(f"Search: '{term}'")
    if value := (state or "").strip():
        parts.append(f"State: {value}")
    if value := (district or "").strip():
        parts.append(f"District: {value}")
    key = (standing or "all").strip().lower()
    if key in {"planned", "promoted", "withdrawn"}:
        parts.append(f"Standing: {key}")
    return " · ".join(parts) if parts else None


def _list_where(
    plan_year: int, search: str | None, state: str | None, district: str | None, standing: str
) -> dict[str, Any]:
    """The list's filter, built once and shared with the export so the two cannot disagree."""
    where: dict[str, Any] = {"planYear": plan_year}
    if state:
        where["state"] = state
    if district:
        where["district"] = district
    if search:
        term = search.strip()
        if term:
            # ``records.contains`` AND NOT A HAND-WRITTEN ``{"contains": …, "mode": "insensitive"}``.
            # The funnel does two things this box would otherwise miss: it STRIPS the control bytes
            # Postgres cannot store (a single NUL pasted out of a PDF returned a 500 from every
            # search box in the app until it did), and it ESCAPES the LIKE metacharacters, so a
            # ministry reference containing "%" or "_" searches for itself rather than for
            # everything. Five boxes composed this by hand and got neither treatment;
            # ``tests/test_record_filters.test_no_route_still_hand_rolls_a_contains_filter`` is the
            # sweep that fails on the sixth, and it failed on this one.
            clause = contains(term)
            where["OR"] = [
                {"workshopNo": clause},
                {"plannedTitle": clause},
                {"venue": clause},
            ]
    key = (standing or "all").strip().lower()
    if key == "planned":
        where["withdrawnAt"] = None
        where["designWorkshopId"] = None
    elif key == "promoted":
        where["withdrawnAt"] = None
        where["designWorkshopId"] = {"not": None}
    elif key == "withdrawn":
        where["withdrawnAt"] = {"not": None}
    return where


def _list_order(sort: str, direction: str) -> list[dict[str, str]]:
    """The ordering, through a CLOSED map, always with a tiebreak.

    THE TIEBREAK IS NOT DECORATION. Two hundred rows planned for the same day are a normal year, and
    an ordering with no tiebreak lets Postgres return them in a different order per page — so page 2
    repeats a row page 1 already showed and drops one nobody ever sees. Every paged list in this
    repository carries one for that reason.
    """
    column = _SORTS.get(sort, "plannedStartDate")
    way = "desc" if str(direction).lower() == "desc" else "asc"
    return [{column: way}, {"workshopNoKey": "asc"}]


@router.get("")
async def list_annual_plan(
    planYear: int = Query(...),
    page: int = Query(default=1, ge=1),
    # `le=MAX_PAGE_SIZE` AND NOT 200. `GET /designers/roster` declares `le=200` while
    # `normalize_pagination` clamps at 100, so a caller who asks for 200 is answered 100 with no
    # explanation. Copying that pattern would copy the lie; this declares the bound that is actually
    # enforced one function down.
    pageSize: int = Query(default=50, ge=1, le=MAX_PAGE_SIZE),
    search: str | None = Query(default=None),
    state: str | None = Query(default=None),
    district: str | None = Query(default=None),
    standing: str = Query(default="all"),
    sort: str = Query(default="plannedStartDate"),
    dir: str = Query(default="asc"),
    _: Any = Depends(require_annual_plan_manager),
) -> dict[str, Any]:
    """One year of the directory, filtered, sorted and paged BY THE SERVER.

    NO CLIENT-SIDE FILTERING OR SORTING ANYWHERE, which is the rule the designer roster screen
    states: a table that sorts the fifty rows it happens to be holding is a table that lies about
    which fifty they are, and the lie is invisible because the column header looks like it worked.
    """
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    where = _list_where(planYear, search, state, district, standing)
    total = await db.annualplanentry.count(where=where)
    rows = await db.annualplanentry.find_many(
        where=where,
        order=_list_order(sort, dir),
        skip=skip,
        take=clean_size,
        include={"designWorkshop": True},
    )
    return page_payload([entry_payload(row) for row in rows], total, clean_page, clean_size)


# --------------------------------------------------------------------------------------
# The `/{entry_id}` family. NOTHING LITERAL MAY BE DECLARED BELOW THIS LINE.
# --------------------------------------------------------------------------------------


@router.get("/{entry_id}")
async def get_annual_plan_entry(
    entry_id: str, _: Any = Depends(require_annual_plan_manager)
) -> dict[str, Any]:
    """One planned row, with the title of the workshop it became when it became one."""
    return entry_payload(await _entry_or_404(entry_id))


@router.patch("/{entry_id}")
async def update_annual_plan_entry(
    entry_id: str,
    payload: AnnualPlanEntryUpdate,
    current_user: Any = Depends(require_annual_plan_manager),
) -> dict[str, Any]:
    """Correct the REMARKS on one row. Nothing else is editable here — see the body's own docstring.

    ``revision`` IS NOT INCREMENTED BY THIS. That counter answers "how many uploads have changed
    this row", which is provenance about the WORKBOOK; a hand-typed remark is not an upload and
    counting it would make the number mean two different things.

    ⚠ **``exclude_unset=True`` IS THE PRECONDITION OF THE FIELD BEING CLEARABLE, NOT A STYLISTIC
    CHOICE** — the sentence ``api/routes/artisans.py`` writes beside its own PATCH, and the reason
    twenty of the twenty-two PATCH handlers in this package dump their body that way. ``notes`` is
    ``str | None`` with a default of ``None`` under ``extra="forbid"``, so a body that OMITS the key
    and a body that sends ``"notes": null`` arrive at this handler identical — and ``{}`` is the one
    other body ``extra="forbid"`` permits. Writing ``payload.notes`` unconditionally, as this
    handler did until it was corrected, turned that empty body into ``notes = NULL``:
    prisma-client-py drops ``None`` ARGUMENTS for convenience but never a ``None`` INSIDE a ``data``
    dict, so the column really is set. The cost is not hypothetical — this column is the
    administrator's own note about a plan row, there is no audit row for this route and no
    ``revision`` bump, so a client emitting ``{}`` (a JS caller passing ``undefined``, a retry, a
    future screen that posts only what it changed) silently and permanently erased it under a 200.
    An explicit ``{"notes": null}`` still clears it, which is the whole point of telling the two
    apart.

    AN EMPTY BODY IS A READ, not a write. Nothing is sent, so nothing moves — and in particular
    ``updatedById`` is not restamped, which it was: a no-op request used to leave a fingerprint
    saying somebody edited a row nobody had edited.
    """
    entry = await _entry_or_404(entry_id)
    values = payload.model_dump(exclude_unset=True)
    if not values:
        return entry_payload(entry)
    updated = await db.annualplanentry.update(
        where={"id": entry.id},
        data={**values, "updatedById": current_user.id},
        include={"designWorkshop": True},
    )
    return entry_payload(updated)


@router.post("/{entry_id}/promote", status_code=status.HTTP_201_CREATED)
async def promote_annual_plan_entry(
    entry_id: str,
    payload: AnnualPlanPromoteRequest,
    current_user: Any = Depends(require_annual_plan_manager),
) -> dict[str, Any]:
    """Open the real workshop this planned row was always for.

    THROUGH ``design_workshops.open_design_workshop``, WHICH IS THE POINT OF THE WHOLE ARM. A second
    copy of the create/attach/seed sequence would be a second place that can forget the seed, and a
    workshop whose promoted columns have no stage entry behind them loses its header on the
    designer's first stage-1 save under a 200 reading "Stage saved".

    THE GATE IS THIS ROUTE'S OWN AND IS SPELLED HERE. ``open_design_workshop`` deliberately does not
    gate — ``tests/test_design_workshop_gate.py`` reads the create route's source to prove the
    create gate is on the ROUTE, and a gate hidden inside the shared opener would be invisible to
    the reader of either door. Note that this arm is gated by ``require_annual_plan_manager`` and
    NOT by ``assert_can_create_design_workshops``: a MINISTRY_ADMIN is not in
    ``DESIGN_WORKSHOP_CREATOR_ROLES``, and widening that set to fit would hand every ministry admin
    the ordinary create button as well. Two doors, two gates, one creation path.
    """
    entry = await _entry_or_404(entry_id)
    record = await promote_entry(
        entry,
        actor=current_user,
        designer_id=payload.designerUserId,
        designer_ids=list(payload.designerUserIds or []),
        template_id=payload.templateId,
    )
    return {"entry": entry_payload(await _entry_or_404(entry_id)), "workshop": workshop_summary(record)}


@router.post("/{entry_id}/withdraw")
async def withdraw_annual_plan_entry(
    entry_id: str, current_user: Any = Depends(require_annual_plan_manager)
) -> dict[str, Any]:
    """Take one row out of the standing plan. A stamp, not a delete — and refused for a promoted row."""
    entry = await _entry_or_404(entry_id)
    await withdraw_entry(entry, actor_id=current_user.id)
    return entry_payload(await _entry_or_404(entry_id))


@router.post("/{entry_id}/reinstate")
async def reinstate_annual_plan_entry(
    entry_id: str, current_user: Any = Depends(require_annual_plan_manager)
) -> dict[str, Any]:
    """Put a withdrawn row back. Clears the stamp; nothing was ever deleted to restore."""
    entry = await _entry_or_404(entry_id)
    await reinstate_entry(entry, actor_id=current_user.id)
    return entry_payload(await _entry_or_404(entry_id))


__all__ = ["require_annual_plan_manager", "router"]
