"""The OFFICER's read-only surface, the screen that assigns it, and the artisan-list import.

Fifteen routes on one prefix. Read ``app/services/design_workshop_oversight.py`` for the argument in
full; this module is the wire, and the three things it adds are the two doors and the upload.

**THIS HEADER SAID "Ten routes" UNTIL 0.0.12.** The five that arrived are one change — the
assignment screen was ADD-ONLY — and they are named here rather than left to be found:
``POST /workshops`` (the THIRD creation door, see its own docstring), ``PUT /{id}/designers`` (the
whole TEAM, and the first removal a Ministry Admin has ever been able to perform),
``GET /{id}/artisans`` and ``DELETE /{id}/artisans/{artisan_id}`` (the roster had no read anywhere
in the product, which is why nobody noticed it had no removal either). The fifth is ``designers`` on
the existing ``GET /{workshop_id}``, which is a key rather than a route.

=======================================================================================
WHY THIS IS ITS OWN ROUTER ON ITS OWN PREFIX
=======================================================================================

``/design-workshops`` is already shared by two routers and carries ``GET /{workshop_id}``, which
swallows any literal path mounted after it. That is the ordering hazard, and it is the LESSER of the
two reasons.

The deciding reason is the one ``design_ratings``, ``design_workshop_access`` and
``design_workshop_inspections`` all give for their own prefixes: **the caller of every route in this
file is, by definition, somebody ``load_workshop_or_404`` turns away.** An officer is not in
``DESIGN_WORKSHOP_ROLES``, so that loader 404s them — and since 2026-09-03 it honours a viewer grant
only for an account inside that set, so it 404s them twice. A route sharing that prefix invites the
next reader to "fix" the inconsistency by widening the shared loader, and widening it grants STAGE
WRITES rather than reads.

**REGISTRATION POSITION IN ``api/router.py`` IS NOT LOAD-BEARING HERE, AND THE COMMENT THERE SAYS
SO.** ``/design-workshop-oversight`` and ``/design-workshops`` are DIFFERENT PREFIXES — Starlette
matches the whole path, not a prefix — so nothing this router declares can be swallowed by that one
however the two are ordered. Treating a reordering as a fix for a 404 on this prefix wastes a
debugging session; the real hazard is one file down.

=======================================================================================
⚠ DECLARATION ORDER *INSIDE THIS FILE* IS LOAD-BEARING
=======================================================================================

Every literal path is declared BEFORE ``GET /{workshop_id}``, because ``{workshop_id}`` matches
``officers`` perfectly well and would answer 404 "Record not found". **That has already shipped once
on a live server as a permanently empty admin picker** — a 404 that reads as "nothing here" rather
than as "your route never ran". FastAPI matches in declaration order, so the literal paths go first
and the parameterised ones go last.

=======================================================================================
THE TWO DOORS
=======================================================================================

* :func:`require_workshop_assigner` — naming the designer, the AD and the RD, and uploading the
  artisan list. ``{MINISTRY_ADMIN, ADMIN, MASTER_ADMIN}``. **A REGIONAL DIRECTOR IS REFUSED** even
  though they outrank an Assistant Director: the supervised must not choose the supervisor.
* :func:`require_officer` — the officer's own read surface. **403 for admins too**, naming the route
  they actually want.
"""

from typing import Any

from fastapi import (
    APIRouter,
    Depends,
    File,
    HTTPException,
    Query,
    Request,
    Response,
    UploadFile,
    status,
)

# ``_parse_date`` JOINS THE TWO SERIALISERS ALREADY IMPORTED FROM THAT MODULE, and for the same
# reason: the ordinary create door reads ``startDate``/``endDate`` through it, so importing it is
# what makes the third door treat a malformed date IDENTICALLY (dropped, never refused) rather than
# making two doors disagree about what "2026-13-40" means. All three are pure; the authorisation on
# this prefix is the two dependencies below and nothing in that module.
from app.api.routes.design_workshops import _parse_date, _provenance_maps, _stages_payload
from app.core.db import db
from app.core.deps import get_current_user
from app.schemas.design_workshop_oversight import (
    DesignWorkshopDesignerIn,
    DesignWorkshopDesignersIn,
    DesignWorkshopOversightCreateIn,
    DesignWorkshopOversightIn,
)
from app.services import design_workshop_oversight as oversight
from app.services.artisan_import import import_artisans
from app.services.artisan_xlsx import (
    ArtisanDefaults,
    ArtisanXlsxError,
    build_artisan_pro_forma,
    parse_artisan_workbook,
    pro_forma_filename,
)
from app.services.concurrency import gather_reads
from app.services.custom_sections import load_definition_or_empty
from app.services.design_workshops import (
    entry_rows,
    named_designer_team,
    open_design_workshop,
    workshop_completeness,
    workshop_summary,
)
from app.services.designers import assignable_designers_payload, workshop_capable_accounts
from app.services.entry_provenance import resolve_display_names
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import contains, public_encode
from app.services.stage_schema import registry_version
from app.services.uploads import read_upload_bounded
from app.services.xlsx_report import xlsx_response

router = APIRouter(prefix="/design-workshop-oversight", tags=["design-workshops"])


# --------------------------------------------------------------------------------------
# The two doors
# --------------------------------------------------------------------------------------


async def require_workshop_assigner(current_user: Any = Depends(get_current_user)) -> Any:
    """Who may say who supervises a workshop, and who may upload its artisan list.

    ⚠ **LIVES HERE AND NOT IN ``core/deps.py``**, which is where ``require_admin``,
    ``require_designer`` and the rest of the ladder's doors live, and that asymmetry is TEMPORARY
    rather than principled: ``deps.py`` is owned by another workstream in this wave, and a
    cross-agent edit to it silently destroys the other change. ``require_inspector`` carries the
    identical note one scope over, for the identical reason.

    **If you are the person consolidating them, move ``OVERSIGHT_ASSIGNER_ROLES`` and this function
    together** — splitting a set from the predicate that reads it is how "who may assign" comes to
    have two answers, and the one in ``deps`` will be the one everybody finds.
    """
    oversight.assert_may_assign_oversight(current_user)
    return current_user


async def require_officer(current_user: Any = Depends(get_current_user)) -> Any:
    """The officer's own READ surface: the three ministry posts and nobody else.

    A dependency rather than a call inside each handler, so that a route added to this file without
    one is visible as a missing ``Depends`` rather than as a missing line in a body — and so the
    read-only sweep in ``tests/test_workshop_oversight_unit.py`` can walk the dependency tree and
    assert that every route behind THIS door is a GET.

    **ADMINS ARE REFUSED HERE**, and the same ⚠ placement note above applies to this function too.
    """
    oversight.assert_oversight_surface(current_user)
    return current_user


# --------------------------------------------------------------------------------------
# Bounds
# --------------------------------------------------------------------------------------

#: How large an artisan-list upload may be.
#:
#: An artisan roster is fifteen rows of typing. 4 MiB is generous for a workbook with photographs of
#: nothing in it, and low enough that this endpoint cannot be used to push a hundred megabytes
#: through a synchronous parser — which, since ``services/uploads`` landed (finding A30-10,
#: 2026-09-03), is a claim the code actually keeps rather than one it makes after the fact.
#:
#: **HALF THE QUESTIONNAIRE'S 8 MiB, DELIBERATELY, AND THE TWO NUMBERS MUST STAY INDEPENDENT.** A
#: thousand-question instrument with every sitting's answers in it is genuinely large; fifteen
#: artisans are not. There is no global body-size middleware in this application for exactly this
#: reason — the per-route numbers differ by two orders of magnitude on purpose, and a single ceiling
#: would be either useless at the top or a refusal at the bottom.
MAX_ARTISAN_UPLOAD_BYTES = 4 * 1024 * 1024

_XLSX_SUFFIXES = (".xlsx", ".xlsm", ".xltx")


async def _read_artisan_upload(file: UploadFile, request: Request | None = None) -> bytes:
    """The uploaded bytes, or a 4xx an officer can act on rather than a 500 from openpyxl.

    THREE REFUSALS, CHEAPEST FIRST, and the order is the point. The extension is a string on the
    multipart part, so a .docx is turned away without the body being copied into the heap at all.
    The declared ``Content-Length`` costs a header lookup. Only then is the body read, and
    ``read_upload_bounded`` counts it as it arrives so a request that UNDERSTATES its length is
    stopped mid-read rather than after it. Then an empty body, which is somebody who attached the
    wrong thing rather than an error the parser should be asked to explain.

    **THE EXTENSION GATE IS NOT TOTAL AND MUST NOT BE ASSUMED TO BE.** It reads ``if name and not
    name.endswith(...)`` — a multipart part with NO filename skips it entirely and falls through to
    the parser's MAGIC-BYTE check, which is the real answer and the one that describes the file
    rather than the label somebody put on it. This is stated because a reader who assumed otherwise
    would delete the magic-byte branch as redundant.

    **``services/uploads.read_upload_bounded`` IS CALLED AND NOT RE-IMPLEMENTED, AND NOTHING NEW WAS
    ADDED TO THAT MODULE FOR THIS DOOR.** It already bounds the heap, already skips the pointless
    copy on an honest ``Content-Length``, already keeps the peak residency inside one chunk of the
    limit, and already answers one terse sentence naming the number in MB —
    ``tests/test_upload_bounds.py`` covers all of it. What is specific to THIS door is the two
    sentences below, which is why they live here and not there.
    """
    name = (file.filename or "").lower()
    if name and not name.endswith(_XLSX_SUFFIXES):
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=(
                f"'{file.filename}' is not an Excel workbook. Fill in the artisan list pro-forma "
                "and upload that, or use File > Save As and choose 'Excel Workbook (.xlsx)'."
            ),
        )
    content = await read_upload_bounded(
        file, MAX_ARTISAN_UPLOAD_BYTES, request=request, purpose="artisan list"
    )
    if not content:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="The upload was empty. Attach the filled-in artisan list pro-forma.",
        )
    return content


async def _workshop_for_assignment_or_404(workshop_id: str) -> Any:
    """The workshop, for somebody who is administering it rather than working in it.

    **DELIBERATELY NOT ``load_workshop_or_404``.** Since 2026-09-03 that loader's grant arm is
    role-gated to ``DESIGN_WORKSHOP_ROLES``, which an officer is outside, so it would 404 every
    Ministry Admin on every workshop — a permission bug that reads as a missing record.

    ``deletedAt`` IS HONOURED HERE, unlike on the two administration routes of the sibling roster: a
    soft-deleted workshop has no roster to change and no list to import into, and an officer has no
    restore button. The admin who wants to restore it does that on ``/design-workshops``.
    """
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    if record is None or record.deletedAt is not None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record


# --------------------------------------------------------------------------------------
# THE LITERAL PATHS. DECLARED FIRST — see the ⚠ paragraph in the module docstring.
# --------------------------------------------------------------------------------------


@router.get("/officers")
async def list_officers(
    search: str | None = Query(None, max_length=120),
    _: Any = Depends(require_workshop_assigner),
) -> dict[str, Any]:
    """The accounts that hold one of the three ministry posts.

    Not the user directory narrowed by the client. The eligible set is a SET of roles and not a rank
    threshold, and it further excludes anyone the platform allow-list has rejected or suspended —
    accounts that cannot sign in, for whom an oversight row would mean this screen saying somebody
    is monitoring while they are shown a refusal at the door. All of it is a rule the client cannot
    see and would drift from within one release.

    ``capacities`` ON EACH ROW is what lets the picker grey out a MINISTRY_ADMIN in a capacity slot
    rather than letting the PUT 422 them, and ``truncated`` says the list was cut. Both clients must
    say so when it is true and say nothing when it is false; an empty list with no explanation is
    this repository's most repeated bug class.
    """
    return await oversight.officer_directory(search=search)


@router.get("/designers")
async def list_assignable_designers(
    search: str | None = Query(None, max_length=120),
    _: Any = Depends(require_workshop_assigner),
) -> dict[str, Any]:
    """The accounts a workshop can be handed to.

    **THE SAME QUERY ``GET /designers/directory`` RUNS, AND A DELIBERATELY SMALLER PAYLOAD.** That
    route is gated on ``can_manage_designer_roster``, which refuses a Ministry Admin — and widening
    it was the wrong fix, because that gate is what stands in front of the EMPANELMENT table and an
    account that could reach it could suspend a designer's sign-in.

    So: two doors, one query, two payloads. ``assignable_designers_payload`` carries four keys and
    carries **no ``rosterId``, no ``rosterActive``, no ``canSignIn``, no ``firstSeenAt``, no
    ``hasProfile`` and no ``institution``** — every one of those is a fact about the roster, and
    whether a designer has a suspension on file is not an officer's business. The suspended are
    already absent by the time this renders, because the roster fold is inside the query's WHERE.

    ``truncated`` MIRRORS THE OFFICER PICKER'S, so one screen reads two lists the same way. The cap
    lives on the service beside the query it bounds.
    """
    from app.services.designers import DIRECTORY_TAKE

    users = await workshop_capable_accounts(search=search, include_suspended=False)
    return {
        "users": assignable_designers_payload(users),
        # The service's ``take`` is the cap, so a full page IS the cut. Reported rather than
        # inferred by the client: both clients had been inferring "the list was cut" from
        # ``len(rows) >= 500`` against a hard-coded copy of that number, which is only sound while
        # every filter is inside the query and is exactly what breaks the day one is not.
        "truncated": len(users) >= DIRECTORY_TAKE,
    }


@router.get("/artisan-pro-forma")
async def download_artisan_pro_forma(
    workshopId: str | None = Query(None, max_length=64),
    _: Any = Depends(require_workshop_assigner),
) -> Response:
    """The blank .xlsx an officer types a workshop's artisan list into.

    ``workshopId`` IS OPTIONAL AND IS WORTH PASSING. With it the Details sheet names the workshop,
    and the State, District and Venue on it are what blank cells fall back to — so the defaults are
    VISIBLE in the file rather than applied invisibly at upload time. It is also what the upload
    checks against the URL, which is what stops fifteen people's regulated records being filed under
    a stranger's project.

    Generated on every request rather than cached: it is a few kilobytes of openpyxl, a stale cached
    copy whose columns no longer match the parser is an office typing a hundred rows into headings
    the app no longer recognises, and the Details sheet is per workshop anyway.
    """
    workshop = await _workshop_for_assignment_or_404(workshopId) if workshopId else None
    return xlsx_response(build_artisan_pro_forma(workshop=workshop), pro_forma_filename(workshop))


@router.post("/workshops", status_code=status.HTTP_201_CREATED)
async def open_workshop_from_oversight(
    payload: DesignWorkshopOversightCreateIn,
    current_user: Any = Depends(require_workshop_assigner),
) -> dict[str, Any]:
    """Open a design & prototype workshop from the assignment screen. **THE THIRD CREATION DOOR.**

    ── WHY THERE IS A THIRD DOOR AND NOT A WIDER GATE ────────────────────────────────────────────

    ``POST /design-workshops`` stands behind ``assert_can_create_design_workshops``, i.e.
    ``DESIGN_WORKSHOP_CREATOR_ROLES`` = ``{ADMIN, MASTER_ADMIN}``, and **a MINISTRY_ADMIN is not an
    admin anywhere in this codebase**. So the primary user of this screen — the account that decides
    who every workshop is for, who supervises it and whose artisans are on it — could not open one,
    and the journey simply stopped: choose a workshop that somebody else had to create first.

    The annual-plan directory met the identical wall and its answer is the precedent this follows
    verbatim. ``POST /annual-plan/{entry_id}/promote`` creates a workshop through the SAME opener
    behind ``require_annual_plan_manager``, and its docstring says why: *"a MINISTRY_ADMIN is not in
    ``DESIGN_WORKSHOP_CREATOR_ROLES``, and widening that set to fit would hand every ministry admin
    the ordinary create button as well. Two doors, two gates, one creation path."* This is the third
    of those. ``DESIGN_WORKSHOP_CREATOR_ROLES`` is UNTOUCHED —
    ``backend/tests/test_design_workshop_gate.py`` reads ``frontend/lib/permissions.ts`` to hold the
    two copies identical, and it stays green.

    ── ``open_design_workshop`` AND NEVER ``db.designworkshop.create`` ───────────────────────────

    Four steps in order — eligibility above the create, the row, the viewer rows, the prefill seed —
    and forgetting the fourth is invisible: the workshop's state, district, craft and dates sit on
    the ROW with no stage entry behind them, and the designer's FIRST stage-1 save nulls every one
    of them under a 200 reading "Stage saved". ``tests/test_design_workshop_creation_path.py``
    enumerates the creation sites and fails on a fourth, so this route calls the opener rather than
    becoming one.

    ``named_designer_team`` reads the body's two designer fields into (the lead, everybody who gets
    a row) — the same pure function both other doors call, so a third reading of ``designerUserId``
    beside ``designerUserIds`` cannot come into existence here.

    ── WHAT THIS DOOR DELIBERATELY DOES NOT HAVE ────────────────────────────────────────────────

    **No offline arm.** ``createWorkshopOrKeepItHere`` and ``DwDraft.createSentAt`` exist because a
    designer opens a workshop in a courtyard with no signal; ``/officers`` is an office desktop
    under the standing web-and-backend-only decision for ministry surfaces, and a local draft
    created by an account that cannot sync one is a trap rather than a safety net.

    **No idempotency key**, for the same reason the ordinary door has none: this is a deliberate
    administrative act taken with the record in front of the officer, not a replayed sync.

    Answers ``workshop_summary`` — the same shape ``GET /workshops`` above lists — so the client can
    make the new workshop the chosen one without a second read.
    """
    lead_id, granted_ids = named_designer_team(payload.designerUserId, payload.designerUserIds)

    # THE SAME SEVEN COLUMNS THE OTHER TWO DOORS CARRY, and falsy values dropped rather than written:
    # a blank box on a create means "not known yet", and there is no stored value it could overwrite.
    columns: dict[str, Any] = {
        "title": payload.title.strip(),
        **{
            key: value
            for key, value in (
                ("workshopKind", payload.workshopKind),
                ("craftName", payload.craftName),
                ("clusterName", payload.clusterName),
                ("state", payload.state),
                ("district", payload.district),
            )
            if value
        },
    }
    for key in ("startDate", "endDate"):
        parsed = _parse_date(getattr(payload, key))
        if parsed:
            columns[key] = parsed

    # ── THE SAME VALUES IN TWO PLACES, DELIBERATELY, AND NEITHER IS DERIVED FROM THE OTHER ───────
    #
    # Every key of `seeded` is declared in `stage_schema.PROMOTED_COLUMNS` under `workshopSetup.*`,
    # so writing any of them as a COLUMN without also writing the stage entry behind it gets it
    # nulled by the first stage-1 save under a 200 reading "Stage saved". `seed_designer_prefill`
    # writes BOTH halves out of `seeded`, which is why the stage values are handed through
    # `open_design_workshop` rather than created here.
    #
    # DATES GO IN AS THE RAW ISO STRINGS, not the `_parse_date` datetimes above: the registry's DATE
    # type coerces and stores an ISO string, and `_coerce_promoted` is what turns it back into a
    # column value on the stage-save path. That is the note both other doors carry beside their own
    # `seeded` literal, and a malformed date is dropped by `validate_entry` exactly as `_parse_date`
    # drops it, so the two halves agree.
    #
    # `title` IS DELIBERATELY NOT SEEDED: it is the one promoted column `DesignWorkshop` declares
    # NOT NULL and the one `_coerce_promoted` refuses to blank, so it was never at risk — and
    # seeding it would freeze the create-form title into stage 1 where a later PATCH could not reach
    # it.
    seeded = {
        key: value
        for key, value in (
            ("workshopKind", payload.workshopKind),
            ("craftName", payload.craftName),
            ("clusterName", payload.clusterName),
            ("state", payload.state),
            ("district", payload.district),
            ("startDate", payload.startDate),
            ("endDate", payload.endDate),
        )
        if value
    }

    record = await open_design_workshop(
        actor=current_user,
        columns=columns,
        designer_id=lead_id,
        designer_ids=granted_ids,
        seeded=seeded,
    )
    return workshop_summary(record)


@router.get("/workshops")
async def list_assignable_workshops(
    page: int = 1,
    pageSize: int = 20,
    search: str | None = Query(None, max_length=120),
    # WHETHER THIS WORKSHOP HAS A DESIGNER ON IT — "staffed", "unstaffed", or absent for both.
    #
    # THE ONE FILTER THIS SCREEN ACTUALLY NEEDS, and it is a filter over the promoted
    # ``designerName`` column rather than over the viewer table, which is the same fact the picker
    # rows already print. "Which of the two hundred workshops in this directory has nobody named on
    # it" is the question a ministry administrator opens this page to answer, and before it existed
    # the only way to answer it was to choose each workshop in turn and read the panel below.
    #
    # A STRING AND NOT A BOOLEAN. ``buildQuery`` on the web client drops ``""`` exactly as it drops
    # null, so a boolean ``staffed=false`` is unsendable — the shape ``workshopIds``' reserved word
    # ``"none"`` exists for. Two words are also honest about the third state: absent means BOTH,
    # never "false".
    staffed: str | None = Query(None, max_length=16),
    _: Any = Depends(require_workshop_assigner),
) -> dict[str, Any]:
    """The design & prototype workshops an assigner may name people on. **Every one of them.**

    ── WHY THIS ROUTE EXISTS AT ALL, WHICH IS NOT OBVIOUS ────────────────────────────────────────

    **A MINISTRY ADMIN CANNOT SEE A SINGLE WORKSHOP THROUGH ``GET /design-workshops``.** That route
    takes ``get_current_user`` with no role gate, so it does not REFUSE them — it scopes anybody who
    is not an admin with ``design_workshop_viewers.visible_to_clause``, which reads the viewer
    relation and the creator column. A Ministry Admin holds neither on any workshop, so the list
    comes back **empty with a 200**: not a refusal they could act on, but a screen that says there
    are no workshops in a repository full of them.

    That is this codebase's single most repeated bug class — a list that quietly stops, or quietly
    never starts, is indistinguishable from a place with no records — and it would land on the
    FIRST control of the assignment screen, before the officer has done anything at all.

    ── WHY THE ANSWER IS NOT "WIDEN THAT ROUTE'S SCOPE" ──────────────────────────────────────────

    ``visible_to_clause`` is the DESIGNER's scope: it decides which workshops somebody may open,
    and ``load_workshop_or_404`` reads the same relation with ``for_edit=True`` to decide who may
    SAVE A STAGE. Adding an arm to it for officers would be a read widening whose blast radius is a
    write gate — the exact "widen the shared loader to fit" move this router's own prefix exists to
    keep somebody from making.

    ── WHAT THIS LISTS, AND WHAT IT DELIBERATELY DOES NOT ────────────────────────────────────────

    ``workshop_summary`` and nothing else: title, code, craft, cluster, dates, status, designer name.
    **No stage data, no entries, no media, no viewer list.** Choosing whom to assign needs enough to
    recognise a workshop and nothing more, and every extra field here would be a second, unscoped
    read of workshop content on a prefix whose whole premise is that its callers are outside the
    designer's scope.

    ``deletedAt: None`` because a soft-deleted workshop has nobody to assign and no list to import
    into. The admin who wants to restore it does that on ``/design-workshops``.

    **THE SCOPE IS AND-COMPOSED AND THE SEARCH IS NOT** — the same warning every list on this prefix
    carries, for the same reason. Here there is no scope clause to lose, which is precisely why the
    search must not be allowed to establish the habit of writing to ``where["OR"]`` twice.

    ``staffed`` IS AND-COMPOSED FOR THAT EXACT REASON. It goes into ``where["AND"]`` and never beside
    the search's ``OR``: an unstaffed filter written onto the same key would either stop narrowing
    or would silently widen the search to every workshop with no designer the moment somebody typed.
    """
    where: dict[str, Any] = {"deletedAt": None}
    term = (search or "").strip()
    if term:
        where["OR"] = [
            {"title": contains(term)},
            {"craftName": contains(term)},
            {"clusterName": contains(term)},
            {"workshopCode": contains(term)},
        ]
    wanted_staffing = (staffed or "").strip().lower()
    if wanted_staffing in {"staffed", "unstaffed"}:
        # A BLANK STRING COUNTS AS UNSTAFFED, not only NULL. ``designerName`` is a promoted column
        # and ``_coerce_promoted`` writes "" rather than NULL for an entity that came back with an
        # empty designer block, so a NULL-only test would file such a workshop as staffed and hide
        # it from exactly the list an officer opened to find it.
        empty = [{"designerName": None}, {"designerName": ""}]
        where.setdefault("AND", []).append(
            {"OR": empty} if wanted_staffing == "unstaffed" else {"NOT": {"OR": empty}}
        )
    # AN UNRECOGNISED WORD IS IGNORED RATHER THAN REFUSED, which is this repository's rule for every
    # other narrowing on every other list route: "an id the caller cannot see, or one that does not
    # exist, matches nothing and is not an error — this is a filter, not a lookup".
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    total, rows = await gather_reads(
        db.designworkshop.count(where=where),
        db.designworkshop.find_many(
            where=where, skip=skip, take=clean_size, order={"createdAt": "desc"}
        ),
    )
    return page_payload([workshop_summary(r) for r in rows], total, clean_page, clean_size)


@router.get("/assigned")
async def list_overseen_workshops(
    page: int = 1,
    pageSize: int = 20,
    search: str | None = Query(None, max_length=120),
    current_user: Any = Depends(require_officer),
) -> dict[str, Any]:
    """The design & prototype workshops this officer supervises, newest first.

    **AN OFFICER WITH NO OVERSIGHT ROW SEES AN EMPTY PAGE, AND THAT IS THE WHOLE SCOPE.** There is
    no "all workshops" arm, no rank fallback and no ``createdById`` arm — an officer creates nothing
    — so this list has exactly one source and there is no second way in to reason about.

    THE LIST IS HALF THE FEATURE. A scope the list does not honour tells its holder that a workshop
    exists (they can open it by id) and simultaneously that it does not (it is absent from every
    list they can reach), and nothing in either client navigates to a workshop by typed id.

    ``deletedAt: None`` because a soft-deleted workshop is a 404 for everyone but an admin, and
    leaving it out would list workshops the detail route then refuses.

    **THE SCOPE IS AND-COMPOSED AND THE SEARCH IS NOT.** The search box takes ``where["OR"]``;
    writing the scope there too is two assignments to one key, the later silently wins, and either
    the search stops narrowing or the scope vanishes the moment somebody types.
    """
    where: dict[str, Any] = {"deletedAt": None}
    term = (search or "").strip()
    if term:
        where["OR"] = [
            {"title": contains(term)},
            {"craftName": contains(term)},
            {"clusterName": contains(term)},
            {"workshopCode": contains(term)},
        ]
    where.setdefault("AND", []).append(oversight.oversight_by_clause(current_user.id))

    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    total, rows = await gather_reads(
        db.designworkshop.count(where=where),
        db.designworkshop.find_many(
            where=where, skip=skip, take=clean_size, order={"createdAt": "desc"}
        ),
    )
    return page_payload([workshop_summary(r) for r in rows], total, clean_page, clean_size)


@router.get("/assigned/{workshop_id}")
async def read_overseen_workshop(
    workshop_id: str, current_user: Any = Depends(require_officer)
) -> dict[str, Any]:
    """One workshop this officer supervises, with every stage's data and its completeness scores.

    **THE READ-ONLY TWIN OF ``GET /design-workshops/{workshop_id}``, and the differences are the
    point rather than an omission.** What is deliberately absent:

    * ``transcripts``. The designer's read fills that key from ``owned_or_granted_where``, which
      admits an account below professor only for media it uploaded, media whose owner granted it a
      ``DataAccessGrant``, or media tagged to a workshop it holds as a creator or viewer. An officer
      holds none of those, so calling it here would cost a query to produce an empty list — and,
      worse, would put this route on the media path at all, so that the next person widening that
      predicate widens this surface without noticing. It is not called.
    * Anything that writes. There is no ``for_edit``, no PATCH twin, no stage save and no report
      route on this prefix, and ``load_overseen_workshop_or_404`` takes no ``for_edit`` parameter.

    Whether an officer SHOULD see the workshop's photographs and recordings is an owner's decision
    that has not been made. It is deliberately not made here by accident: today the answer is no,
    stated in one place, rather than yes by inheritance from a predicate written for co-designers.
    The inspector surface one scope over carries the identical non-decision and it is the same
    question.

    Provenance names ARE resolved, because "who wrote this field" is most of what supervision is
    for, and the ids without them are unreadable.
    """
    record = await oversight.load_overseen_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)
    definition = await load_definition_or_empty(workshop_id)
    summary = workshop_summary(record)
    summary["stages"] = _stages_payload(entries)
    await resolve_display_names(_provenance_maps(summary["stages"]))
    summary["completeness"] = workshop_completeness(entries, definition=definition)
    summary["schemaVersion"] = registry_version()
    summary["customSchemaVersion"] = definition.version
    summary["oversight"] = await oversight.oversight_rows(workshop_id)
    # SAID ON THE WIRE RATHER THAN INFERRED FROM THE URL, because both clients will eventually
    # render this payload through the same screen as the designer's read, and a screen that cannot
    # tell the two apart will offer a Save button that the API answers 404 to. One boolean is
    # cheaper than the bug report.
    summary["readOnly"] = True
    return summary


# --------------------------------------------------------------------------------------
# THE PARAMETERISED PATHS. DECLARED LAST.
# --------------------------------------------------------------------------------------


@router.get("/{workshop_id}")
async def read_workshop_oversight(
    workshop_id: str, _: Any = Depends(require_workshop_assigner)
) -> dict[str, Any]:
    """Who is on this workshop: its designers, its Assistant Director and its Regional Director.

    ``designerName`` is read off the promoted column rather than out of an oversight row, because
    ``DwOversightCapacity`` has no DESIGNER member and must never grow one — who a workshop is FOR
    already has an owner, and a capacity row saying "the designer is X" beside a column saying "the
    designer is Y" is two answers to one question with the report printing the column.

    ``designers`` IS NEW IN 0.0.12 AND IT IS THE KEY THE ASSIGNMENT SCREEN WAS MISSING. This route
    answered ``designerName`` — a STRING, no ids — so the one page that decides who a workshop is
    for could not see who currently holds it, could not pre-tick a picker and had nothing to compare
    a change against. The rows come from ``design_workshop_viewers.viewer_rows`` through
    ``oversight.named_designer_rows``, i.e. THE SERVICE and never ``db.designworkshopviewer.*``,
    which ``tests/test_workshop_oversight_unit.py``'s AST sweep over the three oversight files
    forbids outright.

    **THE CREATOR IS NOT IN ``designers``** — they hold the workshop through ``createdById`` and
    have no viewer row — so an empty list means "nobody but whoever opened it", never "nobody at
    all". The screen over this says so in words.
    """
    record = await _workshop_for_assignment_or_404(workshop_id)
    return {
        "workshopId": record.id,
        "title": record.title,
        "status": str(getattr(record.status, "value", record.status)),
        "designerName": getattr(record, "designerName", None),
        "designers": await oversight.named_designer_rows(record),
        "oversight": await oversight.oversight_rows(workshop_id),
    }


@router.put("/{workshop_id}")
async def set_workshop_oversight(
    workshop_id: str,
    payload: DesignWorkshopOversightIn,
    current_user: Any = Depends(require_workshop_assigner),
) -> dict[str, Any]:
    """Name this workshop's Assistant Director and Regional Director, and answer with the result.

    **AN OMITTED KEY LEAVES THAT CAPACITY ALONE; AN EXPLICIT ``null`` UNASSIGNS.** Told apart by
    ``model_fields_set``, which is the same ``exclude_unset`` discipline ``ArtisanUpdate``
    documents — without it a screen that only ever sends the field it changed would silently
    unassign the other post on every save.

    VALIDATION RUNS TO COMPLETION BEFORE ANY WRITE. One bad id refuses the whole call rather than
    applying the good half: somebody who named two officers and is shown one has been told nothing
    about which failed or why, and a partially applied assignment looks like it worked.

    THE ANSWER IS THE SET AS THE SERVER NOW HOLDS IT, never an echo of what was sent.
    """
    await _workshop_for_assignment_or_404(workshop_id)
    sent = payload.model_fields_set
    wanted: dict[str, str | None] = {}
    if "assistantDirectorId" in sent:
        wanted["ASSISTANT_DIRECTOR"] = payload.assistantDirectorId
    if "regionalDirectorId" in sent:
        wanted["REGIONAL_DIRECTOR"] = payload.regionalDirectorId

    await oversight.assert_may_hold(workshop_id, {c: uid for c, uid in wanted.items() if uid})
    rows = await oversight.apply_oversight(
        workshop_id, wanted=wanted, assigned_by_id=current_user.id
    )
    return {"oversight": rows}


@router.put("/{workshop_id}/designer")
async def set_workshop_designer(
    workshop_id: str,
    payload: DesignWorkshopDesignerIn,
    current_user: Any = Depends(require_workshop_assigner),
) -> dict[str, Any]:
    """Name a different designer on this workshop, and move their details with them.

    Four things happen and all four are the EXISTING machinery rather than a second copy of it: the
    designer eligibility rule the viewers screen enforces, the viewer row ``add_one_viewer`` writes,
    the profile prefill, and a stage save. See ``design_workshop_oversight.reassign_designer``,
    which sets out why each is where it is — in particular why the prefill is
    ``prefill_from_profile(user_id)`` and never ``user_id or actor.id``.
    """
    record = await _workshop_for_assignment_or_404(workshop_id)
    return await oversight.reassign_designer(record, payload.designerId, actor=current_user)


@router.put("/{workshop_id}/designers")
async def set_workshop_designers(
    workshop_id: str,
    payload: DesignWorkshopDesignersIn,
    current_user: Any = Depends(require_workshop_assigner),
) -> dict[str, Any]:
    """Set the whole team this workshop is FOR, and answer with it as the server now holds it.

    ── THE PLURAL DOOR BESIDE ``PUT …/designer``, AND THE TWO ARE NOT ALTERNATIVES ────────────────

    That one answers *whose name is on the report* and is a replacement by construction. This one
    answers *who may open the workshop*, which on a real Design & Prototype Development Workshop is
    two designers alongside a master craftsperson and a reviewing officer — every one of whom needs
    the 22 stages. Both facts already exist on the create doors as ``designerUserId`` beside
    ``designerUserIds``; this prefix had only half of the pair until 0.0.12, which is why an
    assignment made here was ADD-ONLY and could never be corrected.

    ⚠ **A WHOLE-SET BODY, AND EMPHATICALLY NOT A WHOLE-SET WRITE.** The service DIFFS the body
    against the rows that exist and adds and removes one at a time.
    ``design_workshop_viewers.replace_viewers`` is **forbidden from this feature**: a fourth writer
    of that table (``services/design_workshop_grants.py``, the join cards and the access requests)
    means a whole-set replace destroys a row a concurrent redemption created in the same second —
    the exact hazard ``attach_the_named_designers`` refuses to take on.

    **"NOBODY IS THE DESIGNER" IS AN EXPRESSIBLE STATE, AND THIS DOOR IS THE WAY BACK TO IT
    (2026-09-20).** This paragraph claimed the opposite until that date and the claim was false:
    design workshop ``cmsxcdc2y000`` ("Test", IN_PROGRESS) is in the live database with
    ``designerName = None``, which is where every workshop opened without a designer named begins.
    The missing half was the TRANSITION BACK, so an officer who added the wrong designer could not
    take her off again and was asked for a replacement she did not want to name. An empty
    ``userIds`` now removes the last viewer row, blanks the promoted ``designerName`` AND stage 1's
    own ``designerName`` field in one act — the field because it is the single source the column is
    promoted from — and keeps every other stage value the prefill wrote.

    ONE 422 remains on this shape, and it names the remedy: a body that drops the LEAD **while other
    designers remain** without ``leadUserId`` saying which of them leads instead. Removing a
    CO-designer is always allowed — that is the gap this route exists to close, and their name is on
    no document. A FILED report is refused before any of it.

    Validation runs to completion before any write, and ``assert_every_designer_may_be_named`` is
    asked of the ADDED ids only. Refusing a REMOVAL because somebody's empanelment has lapsed would
    strand access precisely on the accounts it is most urgent to withdraw.

    The answer carries ``designers`` (the set as the server now holds it, re-read rather than
    echoed), ``removedDesigners`` (who lost access, named — a silent stale grant was the whole
    defect here once already), ``designerName`` and ``stagesWritten``.
    """
    record = await _workshop_for_assignment_or_404(workshop_id)
    return await oversight.set_named_designers(
        record,
        user_ids=list(payload.userIds),
        lead_user_id=payload.leadUserId,
        actor=current_user,
    )


@router.post("/{workshop_id}/artisans/upload", status_code=status.HTTP_201_CREATED)
async def upload_artisan_list(
    request: Request,
    workshop_id: str,
    file: UploadFile = File(...),
    current_user: Any = Depends(require_workshop_assigner),
) -> dict[str, Any]:
    """Import a workshop's artisan list from the .xlsx pro-forma.

    ``request`` is filled by FastAPI and is not sent by any client; it is here only so the size
    ceiling can read ``Content-Length`` before the spooled body is copied into the heap. There are
    **no ``Form()`` scalars**: the workshop is in the path and everything else is in the workbook.

    ── THE ORDER OF WORK, AND WHY EACH STEP IS WHERE IT IS ───────────────────────────────────────

    **1. The workshop, then THE VENUE-COORDINATE PRE-CHECK, both BEFORE the file is parsed.** A
    workshop with no stage-1 ``venueLocation`` is refused outright, and refusing early costs the
    officer nothing while refusing late costs them a parse they cannot use. The reason the check
    exists at all is the finding the ``Location`` model records: *fifteen artisans documented in
    four different states all carrying one office's coordinates, because the only place the schema
    offered for "where the artisan is" was the field that means "where the device is."* Defaulting
    the fix to (0,0), to the office, or to a state centroid would recreate that defect fifteen rows
    at a time.

    **2. The bytes, bounded.** See :func:`_read_artisan_upload`.

    **3. The parse, OFF THE EVENT LOOP.** openpyxl is synchronous CPU work and this process is a
    single uvicorn worker, so a five-thousand-row workbook parsed inline blocks every other request
    on the box. ``asyncio.to_thread`` is what the batch paths in ``data_browser`` already do; the
    questionnaire door does NOT, and this is correcting that omission rather than inventing a rule.

    **4. The Details-sheet guard, 409.** A workbook naming a DIFFERENT workshop is refused naming
    both — the same guard the questionnaire upload makes, for the sharper version of its reason:
    importing fifteen artisans into the wrong workshop files fifteen people's regulated records
    under a stranger's project.

    **5. The import.** See ``services/artisan_import``.

    **6. ``del content`` before returning.** The bytes carry Aadhaar numbers and nothing needs them
    after the parse. The workbook itself is never stored — ``DwArtisanImport`` keeps the filename.
    """
    import asyncio

    workshop = await _workshop_for_assignment_or_404(workshop_id)

    venue_fix = await _venue_fix(workshop_id)
    if venue_fix is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "This workshop has no venue location yet, so an imported artisan would have no "
                "coordinate to record. Capture the venue location on stage 1 (Workshop setup) and "
                "upload the list again."
            ),
        )

    content = await _read_artisan_upload(file, request)
    defaults = ArtisanDefaults(
        state=str(getattr(workshop, "state", "") or ""),
        district=str(getattr(workshop, "district", "") or ""),
        place=str(getattr(workshop, "venue", "") or ""),
        craftName=str(getattr(workshop, "craftName", "") or ""),
    )
    try:
        parsed = await asyncio.to_thread(
            parse_artisan_workbook, content, filename=file.filename, defaults=defaults
        )
    except ArtisanXlsxError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    if parsed.designWorkshopId and parsed.designWorkshopId != workshop_id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"That workbook was filled in for a different workshop. Its Details sheet names "
                f"{parsed.designWorkshopId} and this upload is going into {workshop_id}. Download "
                f"the pro-forma from the workshop you mean, or correct the Design workshop ID on "
                f"the Details sheet."
            ),
        )

    report = await import_artisans(
        parsed,
        workshop=workshop,
        actor=current_user,
        venue_fix=venue_fix,
        source_filename=file.filename,
    )
    del content
    return public_encode(report, current_user)


@router.get("/{workshop_id}/artisans")
async def list_workshop_artisans(
    workshop_id: str, _: Any = Depends(require_workshop_assigner)
) -> dict[str, Any]:
    """Who is on this workshop's artisan roster.

    **THIS READ DID NOT EXIST ANYWHERE IN THE PRODUCT UNTIL 0.0.12, ON EITHER CLIENT.**
    ``GET /artisans?designWorkshopId=…`` has existed for as long as the column has and is called by
    nothing; ``ArtisanListPanel`` on ``/officers`` offered a pro-forma, an upload and an import
    history, and never once said who was actually on the list. That absence is why the roster's
    missing REMOVAL went unnoticed for so long: there was no list to remove anybody from.

    ``oversight.linked_artisan_rows`` carries the payload argument — six keys, and **no regulated
    identity column at all**. An officer may read an unmasked Aadhaar through the artisan record
    itself (``_may_read_full_aadhaar`` is ``has_rank(user, "PROFESSOR")`` and all three ministry
    posts clear it), so this is not a capability being withheld; it is the frontend contract's own
    rule that a regulated number is never rendered in a LIST.

    ``truncated`` says the list was cut, and the screen says so when it is true — the same contract
    every other list on this prefix keeps.
    """
    await _workshop_for_assignment_or_404(workshop_id)
    return await oversight.linked_artisan_rows(workshop_id)


@router.delete("/{workshop_id}/artisans/{artisan_id}")
async def unlink_workshop_artisan(
    workshop_id: str, artisan_id: str, _: Any = Depends(require_workshop_assigner)
) -> dict[str, Any]:
    """Take one artisan off this workshop's roster. **UNFILES; NEVER DELETES.**

    The artisan's record, its photographs, its products, its tools and its interviews are all
    untouched — ``Artisan.designWorkshopId`` is a nullable FK and this clears it, which is the same
    act ``schemas/records.assert_payload_workshop`` already permits from the artisan form (*"an
    explicit ``None`` unfiles the record and is always allowed"*), reached from the screen that
    actually holds the roster. ``Artisan.createdBy`` is ``Restrict`` and is never touched: an
    officer did not author these records and a roster correction must not be a way to destroy a
    regulated person-record.

    ⚠ **THE STAGE-3 PARTICIPANT ROW IS LEFT STANDING, AND THE SCREEN SAYS SO IN WORDS.**
    ``services/artisan_import`` writes one ``DwStageEntry`` per imported artisan under
    ``WORKSHOP_PLAN_PARTICIPANTS_OPENING``. Deleting it here would be a STAGE WRITE performed by an
    officer, on a report that may be under inspection, through the same ``save_stage`` path whose
    ``NEEDS_REVISION`` arm silently RE-SUBMITS a sent-back report and burns a round. **These are two
    deletions and nothing links them.** Whether they should be one is a product decision nobody has
    taken; until then this door does one thing and says what it did not do.

    **IT IS NOT A 404 WHEN THE ARTISAN IS ALREADY OFF THE ROSTER**, and ``unlinked`` is how the
    screen tells the two apart. Two officers working the same list is the ordinary case, and "the
    row you asked me to remove is already gone" is a state the second of them should be told about
    rather than shown an error over. An artisan id belonging to a DIFFERENT workshop lands in the
    same arm, because the service's ``update_many`` predicate carries the workshop — a stale screen
    can never unfile a record from somewhere it was not looking at.
    """
    await _workshop_for_assignment_or_404(workshop_id)
    unlinked = await oversight.unlink_artisan_from_workshop(workshop_id, artisan_id)
    return {"unlinked": unlinked}


@router.get("/{workshop_id}/artisan-imports")
async def list_artisan_imports(
    workshop_id: str, _: Any = Depends(require_workshop_assigner)
) -> dict[str, Any]:
    """This workshop's accepted artisan-list uploads, newest first.

    THE LEDGER EXISTS BECAUSE THE UPLOAD'S OWN RESPONSE IS READ ONCE, by the officer who pressed the
    button. "Where did these fourteen artisans come from, and which rows were refused" is asked
    months later by somebody holding a ministry document, and the artisan rows carry ``createdById``
    and nothing about the spreadsheet.

    ``problems`` COMES BACK EXACTLY AS IT WAS STORED and nothing here re-masks it: every identity
    number in it was masked by the importer at the one place the number was read. A mask applied in
    two places is a mask that can be forgotten in one.
    """
    await _workshop_for_assignment_or_404(workshop_id)
    rows = await db.dwartisanimport.find_many(
        where={"designWorkshopId": workshop_id},
        include={"uploadedBy": True},
        order={"createdAt": "desc"},
        take=100,
    )
    return {
        "imports": [
            {
                "id": row.id,
                "sourceFilename": row.sourceFilename,
                "sheetName": row.sheetName,
                "rowsRead": row.rowsRead,
                "artisansCreated": row.artisansCreated,
                "artisansLinked": row.artisansLinked,
                "rowsRefused": row.rowsRefused,
                "participantsCreated": row.participantsCreated,
                "problems": row.problems,
                "uploadedBy": getattr(getattr(row, "uploadedBy", None), "name", None),
                "createdAt": row.createdAt.isoformat() if row.createdAt else None,
            }
            for row in rows
        ]
    }


async def _venue_fix(workshop_id: str) -> dict[str, Any] | None:
    """The workshop's stage-1 venue coordinate, or ``None``.

    READ OFF THE STAGE ROW rather than off a column, because there is no column: ``venueLocation``
    is a ``GEO`` field on stage 1's ``workshopSetup`` entity, stored inside ``DwStageEntry.data`` as
    ``{"lat": float, "lon": float, "accuracy": float?}``.

    A MALFORMED VALUE ANSWERS ``None`` rather than raising, so the officer gets the one sentence
    telling them to capture the venue location rather than a 500 naming a key. The stage's own
    coercion refuses a non-finite or out-of-range coordinate on the way IN, so a stored value that
    fails this check is a row written before that coercion existed.
    """
    row = await db.dwstageentry.find_first(
        where={
            "designWorkshopId": workshop_id,
            "stageKey": "WORKSHOP_SETUP",
            "entityKey": "workshopSetup",
            "deletedAt": None,
        }
    )
    data = getattr(row, "data", None)
    if not isinstance(data, dict):
        return None
    fix = data.get("venueLocation")
    if not isinstance(fix, dict):
        return None
    try:
        lat = float(fix["lat"])
        lon = float(fix["lon"])
    except (KeyError, TypeError, ValueError):
        return None
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        return None
    out: dict[str, Any] = {"lat": lat, "lon": lon}
    accuracy = fix.get("accuracy")
    if accuracy is not None:
        try:
            out["accuracy"] = float(accuracy)
        except (TypeError, ValueError):
            pass
    return out
