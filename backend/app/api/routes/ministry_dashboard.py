"""``/api/ministry-dashboard`` — the ministry's register of the whole programme.

═══════════════════════════════════════════════════════════════════════════════════════════════
ITS OWN PREFIX, AND THE REASON IS THE SAME ONE `/design-workshop-oversight` GIVES
═══════════════════════════════════════════════════════════════════════════════════════════════

Every caller of this router is, by definition, somebody ``load_workshop_or_404`` turns away: a
MINISTRY_ADMIN holds neither a ``createdById`` nor a viewer row on any workshop, so
``GET /design-workshops`` answers them an EMPTY 200. A route sharing the ``/design-workshops`` prefix
would invite the next reader to "widen that shared loader to fit", and that loader takes
``for_edit=True`` — so the widening whose blast radius is STAGE WRITES is exactly one helpful edit
away. ``app/services/ministry_dashboard.py`` carries the argument at length. That prefix also carries
a ``GET /{workshop_id}`` which swallows any literal path mounted after it.

**AND THIS ROUTER DECLARES NO PARAMETERISED PATH AT ALL**, which is not an accident of scope: every
per-workshop read the ministry already has lives on ``/design-workshop-oversight``, and a second door
onto one workshop would be a second place to keep the read-only promise. Nothing here answers about
one workshop; everything answers about the register. Keep it that way — the ⚠ ordering hazard the
oversight module spends a paragraph on cannot arise in a router with no ``/{id}``.

═══════════════════════════════════════════════════════════════════════════════════════════════
EVERY ROUTE HERE IS A GET, AND THE DOOR IS A DEPENDENCY RATHER THAN A CALL IN EACH BODY
═══════════════════════════════════════════════════════════════════════════════════════════════

``require_ministry_dashboard_reader`` is written as a ``Depends`` so that a route added to this file
without one is visible as a missing dependency rather than as a missing line in a body — the reason
``require_officer`` gives one scope over, and the property
``tests/test_ministry_dashboard_gate.py`` sweeps for.

═══════════════════════════════════════════════════════════════════════════════════════════════
THE TWO TABLES ANSWER IN TWO VOCABULARIES, AND THAT IS NOT A THING TO TIDY UP
═══════════════════════════════════════════════════════════════════════════════════════════════

"Ongoing / completed / newly registered" is a LIFECYCLE and ``DesignWorkshop`` has one:
``DesignWorkshopStatus``, eight members, moved by a transition graph. The legacy ``Workshop`` table
carries ``RecordStatus`` — DRAFT, PENDING, APPROVED, REJECTED, NEEDS_REVISION — which is a REVIEW
state and not a lifecycle at all: it says how far a recorded visit has got through moderation, not
how far a programme has got through its stages.

So the standing filter on the design & prototype tab is the lifecycle grouping, the filter on the
other-workshops tab is the review status those rows actually carry, and **the payload names its own
vocabulary** (``standingVocabulary``) so the screen prints the words the rows were filed under rather
than a translation of them. Mapping "ongoing" onto a review status would be a second meaning for one
word, invented on this screen, for rows every other screen in the product describes differently.

═══════════════════════════════════════════════════════════════════════════════════════════════
THE DOWNLOADS ARE A COPY OF THE REGISTER AND NOT OF THE WORKSHOPS
═══════════════════════════════════════════════════════════════════════════════════════════════

``deps.DESIGN_WORKSHOP_DATA_EXPORT_ROLES`` is ``{ADMIN, MASTER_ADMIN}`` and REFUSES all three
directorate tiers — deliberately, and the ruling survived the 2026-09-13 widening that put those
tiers into ``DESIGN_WORKSHOP_DATA_VIEW_ROLES``: *"a directorate tier reads design-workshop stage data
on screen and cannot take it out of the product"*. **Nothing here crosses that line.** What it gates
is STAGE DATA — the .xlsx workbook, the whole-repository archive, a fortnight of a named designer's
fieldwork including artisan dictation, consent decisions and unpublished prototype work.

:func:`export_design_workshops_csv` emits the REGISTER: the header columns
``design_workshops.workshop_summary`` already hands these same callers on screen, plus the
completeness roll-up this module computes. No stage field value, no media, no transcript, no consent
decision, no artisan content. That is the same kind of file ``/api/annual-plan/export.xlsx`` already
hands a MINISTRY_ADMIN — a directory of what is planned, where and by whom — and the precedent is
exact rather than rhetorical.

``tests/test_ministry_dashboard_gate.py`` holds that claim to the code, and STRUCTURALLY rather than
by comparing column names: it pins the column list, and it asserts that the export function reaches
no stage reader at all. (The name-comparison version was tried first and was wrong — "State",
"Venue", "Cluster", "Start date" and "Kind" are stage-1 fields that ``promoted_values()``
DENORMALISES onto the workshop row, so a label collision is what promotion MEANS rather than a leak.
That test carries the record.) If a future change wants stage content in this file, it needs the
export role and a new decision record, not a new column.

The beneficiaries download is a different question again and needs no new rule at all:
``can_download_dataset`` is ``has_rank(user, "PROFESSOR")`` or the grant, and all three ministry
posts sit above PROFESSOR — so ``/api/export/artisans.csv`` (added in the same change, beside the
products and tools exports it copies) serves them under the gate that already governs taking records
out of this product, with the registry's Aadhaar and Pehchan masking applied by construction.
"""

from __future__ import annotations

import csv
import io
import logging
from types import SimpleNamespace
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status

from app.core.db import db
from app.core.deps import (
    MINISTRY_DASHBOARD_REFUSAL,
    can_download_dataset,
    can_see_ministry_dashboard,
    get_current_user,
)
from app.services import design_workshop_oversight as officers, ministry_dashboard as register
from app.services.concurrency import gather_reads
from app.services.design_workshops import workshop_summary
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import contains

router = APIRouter(prefix="/ministry-dashboard", tags=["ministry"])

logger = logging.getLogger(__name__)


#: The largest page this register will serve.
#:
#: DECLARED AS ``le=MAX_PAGE_SIZE`` AND REFUSED WITH A 422 RATHER THAN CLAMPED, which is
#: ``design_workshops.py``'s rule and not the oversight list's: *"A 422 here is the caller finding
#: that out from the response instead of from a ``pages`` count that no longer matches the
#: ``pageSize`` it thinks it sent."* ``normalize_pagination`` clamps to 100 regardless, so a route
#: declaring a larger bound is a route whose response silently disagrees with its own request — the
#: defect already live on ``GET /sanction-orders`` (``le=200``).
MAX_PAGE_SIZE = 100

#: How long a search term may be, matching every other list on the ministry surfaces.
MAX_SEARCH = 120


async def require_ministry_dashboard_reader(current_user: Any = Depends(get_current_user)) -> Any:
    """The ministry's own posts and the master admin. See ``deps.MINISTRY_DASHBOARD_ROLES``.

    THE REFUSAL SENTENCE IS AN IMPORTED CONSTANT AND NOT A LITERAL HERE. It is said on two surfaces
    — this 403 and the client's ``ROUTE_GUARDS`` row — and
    ``tests/test_ministry_dashboard_gate.py`` holds the two byte-for-byte, the way
    ``test_sanction_order_gate.py`` does for its own. A refusal that names a different next move
    depending on whether you met the wall at the URL or at the API is not a rule, it is two rumours.
    """
    if not can_see_ministry_dashboard(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail=MINISTRY_DASHBOARD_REFUSAL
        )
    return current_user


def _search_clause(term: str | None) -> dict[str, Any] | None:
    """The design-workshop search, over the four columns the officer's lists already search.

    Identical to ``design_workshop_oversight``'s so that a term typed on one ministry screen finds
    the same rows on the other. It is returned rather than written, because the caller has to place
    it on ``where["OR"]`` and the SCOPE on ``where["AND"]`` — two assignments to one key is the
    defect every list on that prefix carries a warning about.
    """
    clean = (term or "").strip()
    if not clean:
        return None
    return {
        "OR": [
            {"title": contains(clean)},
            {"craftName": contains(clean)},
            {"clusterName": contains(clean)},
            {"workshopCode": contains(clean)},
        ]
    }


def _design_where(current_user: Any, search: str | None, standing: str | None) -> dict[str, Any]:
    """The whole ``where`` for a design & prototype read, composed in the one correct order.

    ``deletedAt: None`` because a soft-deleted workshop is a 404 for everyone but an admin, and a
    register listing rows the detail routes then refuse is a register that lies about the estate.
    ``includeDeleted``/``deletedOnly`` are deliberately NOT offered here: the trash is an admin's
    screen on ``/design-workshops`` and an officer has no restore button, so an arm for it would be a
    filter whose every row is a dead end.
    """
    where: dict[str, Any] = {"deletedAt": None}

    search_clause = _search_clause(search)
    if search_clause:
        where["OR"] = search_clause["OR"]

    # ⚠ AND-COMPOSED, BOTH OF THEM, AND NEVER BESIDE THE SEARCH'S ``OR``. The later assignment to one
    # dict key silently wins: written onto ``OR``, the scope would vanish — handing an Assistant
    # Director every workshop in the country — or the standing filter would stop narrowing, the
    # moment somebody typed in the search box. Every list on the oversight prefix carries this
    # warning and this is the widest ``where`` in the feature.
    scope = register.scope_clause(current_user)
    if scope is not None:
        where.setdefault("AND", []).append(scope)
    standing_clause = register.standing_clause(standing)
    if standing_clause is not None:
        where.setdefault("AND", []).append(standing_clause)
    return where


#: The order every design-workshop read on this router takes.
#:
#: TOTAL, AND THE ``id`` TIEBREAK IS THE POINT. ``services/records.with_id_tiebreak`` states the rule
#: and the oversight lists do not follow it: *"A row that changes side of the cut between two
#: requests is handed over TWICE … or NEVER — and either way the response looks perfectly healthy."*
#: On a register that RE-READS ITSELF ON A TIMER that is not a theoretical hazard — it is a workshop
#: appearing twice on page 2 while another vanishes, several times an hour, with nothing on screen to
#: say so.
#:
#: ``updatedAt`` rather than ``createdAt`` so the rows a ministry is chasing — the ones somebody has
#: just touched — are the ones on page one. ``createdAt`` is the second key so a page of workshops
#: that have never been edited still has a stable order among themselves.
_DESIGN_ORDER = [{"updatedAt": "desc"}, {"createdAt": "desc"}, {"id": "asc"}]


async def _design_rows(
    current_user: Any,
    *,
    where: dict[str, Any],
    skip: int,
    take: int,
) -> tuple[int, list[dict[str, Any]]]:
    """A page of design & prototype workshops, scored, with beneficiary counts.

    FIVE QUERIES FOR THE WHOLE PAGE and not five per row: the count and the page (concurrently),
    then the stage entries, the custom sections and fields, and the artisan aggregate.
    ``custom_sections.load_definition`` is two round trips EACH and one round trip has measured 756ms
    across regions on this deployment — a twenty-row page scored one workshop at a time is
    forty-one round trips before the first byte, on the screen most likely to be left open.
    """
    total, records = await gather_reads(
        db.designworkshop.count(where=where),
        db.designworkshop.find_many(where=where, skip=skip, take=take, order=_DESIGN_ORDER),
    )
    ids = [str(record.id) for record in records]

    # ── SCORING MAY FAIL WITHOUT TAKING THE REGISTER WITH IT ──────────────────────────────────────
    #
    # The header read above has already succeeded, so the ministry CAN be shown which workshops exist,
    # where they are and who is on them. Letting a failure in the completeness read turn the whole
    # response into a 500 would trade a screen with one unreadable COLUMN for a screen with no rows at
    # all — and an officer meeting that sees "the register could not be read", which on a live national
    # programme is the worse of the two lies by a distance.
    #
    # It degrades to :func:`ministry_dashboard.unscored`, NEVER to zero. `_roll_up`'s note has the
    # argument: 0% against a workshop nobody scored is the single most damaging false statement this
    # screen can make, and the client renders the two absences as different sentences.
    #
    # ``beneficiaries`` is deliberately NOT inside the same guard. An artisan count that fails is
    # answered by the same `.get(..., 0)` an empty roster is answered by, and those two genuinely ARE
    # the same on this column — a workshop with nobody on it is an ordinary state on the day it opens.
    # Progress has no such reading, which is why only one of the two has a fallback with a reason.
    # TWO INDEPENDENT GUARDS AND NOT ONE AROUND BOTH, and the first attempt at this got it wrong in a
    # way worth recording: it wrapped a single ``gather_reads`` and then, in the ``except``, RE-RAN
    # ``beneficiary_counts`` — the very read that may have been the one that failed. A roster read
    # that was down stayed down, the retry raised outside the guard, and the whole register 500'd
    # through the handler written to stop exactly that. ``gather_reads`` is ``asyncio.gather`` with no
    # ``return_exceptions``, so one failure discards the other coroutine's result as well; splitting
    # them is what makes "one column may fail" true rather than merely intended.
    #
    # THEY ARE STILL CONCURRENT. Each coroutine is wrapped in its own failure-to-None shim and the two
    # shims are gathered, so the round trips still overlap and neither can take the other down.
    async def _progress_or_none() -> dict[str, dict[str, Any]] | None:
        try:
            return await register.progress_for(ids)
        except Exception:
            logger.exception("ministry dashboard: scoring %d workshop(s) failed", len(ids))
            return None

    async def _beneficiaries_or_none() -> dict[str, int] | None:
        try:
            return await register.beneficiary_counts(ids)
        except Exception:
            logger.exception("ministry dashboard: counting artisans on %d workshop(s) failed", len(ids))
            return None

    scored_map, counted_map = await gather_reads(_progress_or_none(), _beneficiaries_or_none())
    progress: dict[str, dict[str, Any]] = scored_map or {}
    # ⚠ ``None`` HERE IS NOT AN EMPTY MAP, AND THE ROW BELOW SPENDS THE DIFFERENCE. A workshop with
    # nobody on its roster is an ordinary state on the day it opens and is honestly 0; a roster read
    # that FAILED is not 0, and printing one is the same false statement the progress column spends a
    # paragraph refusing. `_roll_up`'s note has the argument.
    beneficiaries: dict[str, int] | None = counted_map

    rows: list[dict[str, Any]] = []
    for index, record in enumerate(records):
        workshop_id = str(record.id)
        summary = workshop_summary(record)
        # THE ROW SAYS WHY IT HAS NO FIGURE RATHER THAN CARRYING A ZERO. ``progress_for`` scores at
        # most ``PROGRESS_SCORE_CAP`` workshops and returns a map; anything absent from it was not
        # scored, and a register printing 0% against a workshop it failed to read is the single most
        # damaging false statement this screen can make. The reason is carried so the client can say
        # WHICH of the two it is — see ``ministry_dashboard.unscored``.
        summary["progress"] = progress.get(
            workshop_id,
            register.unscored(
                "capped" if index >= register.PROGRESS_SCORE_CAP else "unreadable"
            ),
        )
        # None when the count could not be read at all — the client prints "not read" rather than 0.
        summary["beneficiaries"] = beneficiaries.get(workshop_id, 0) if beneficiaries is not None else None
        summary["standingGroup"] = register.group_of(summary.get("status"))
        rows.append(summary)
    return total, rows


@router.get("/design-workshops")
async def list_design_workshops(
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=MAX_PAGE_SIZE),
    search: str | None = Query(None, max_length=MAX_SEARCH),
    standing: str | None = Query(None, max_length=32),
    current_user: Any = Depends(require_ministry_dashboard_reader),
) -> dict[str, Any]:
    """The design & prototype workshops this account may read, newest-touched first, with progress.

    **WHAT "THIS ACCOUNT MAY READ" MEANS IS ON THE WIRE**, under ``scope`` and ``scopeLabel``, and
    that is the whole defence against this repository's most repeated bug class. A MINISTRY_ADMIN and
    the master admin read the estate; an ASSISTANT_DIRECTOR and a REGIONAL_DIRECTOR read the
    workshops they were posted to, which is the same ``oversight_by_clause`` that scopes
    ``/officers/monitored`` and its own header's words: *"an officer with no oversight row sees an
    empty page, and that IS the whole scope."* An officer reading "every workshop on the platform"
    over their own four would be told the programme is four workshops large.

    ``standing`` IS A WORD AND NEVER A BOOLEAN, and an unrecognised one is ignored rather than
    refused — both are this API's rules for a narrowing, and the first is forced by the web client:
    ``buildQuery`` drops ``""`` exactly as it drops null, so a value that has to survive the query
    builder cannot be a boolean, and absence has to mean EVERYTHING rather than "false".
    """
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    where = _design_where(current_user, search, standing)
    total, rows = await _design_rows(current_user, where=where, skip=skip, take=clean_size)

    payload = page_payload(rows, total, clean_page, clean_size)
    payload["scope"] = "estate" if register.sees_whole_estate(current_user) else "posted"
    payload["scopeLabel"] = register.scope_label(current_user)
    payload["standingVocabulary"] = "designWorkshopLifecycle"
    payload["standingGroups"] = {
        name: list(members) for name, members in register.STANDING_GROUPS.items()
    }
    payload["progressScoreCap"] = register.PROGRESS_SCORE_CAP
    return payload


@router.get("/workshops")
async def list_other_workshops(
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=MAX_PAGE_SIZE),
    search: str | None = Query(None, max_length=MAX_SEARCH),
    standing: str | None = Query(None, max_length=32),
    _: Any = Depends(require_ministry_dashboard_reader),
) -> dict[str, Any]:
    """The OTHER workshops — the legacy ``Workshop`` table, the recorded field visits.

    ── THESE ROWS ARE NOT SCOPED, AND THE PAYLOAD SAYS SO ─────────────────────────────────────────

    ``services/records.viewable_where`` returns ``{}`` — *"Row filter for READING the repository:
    everything, for every signed-in account"* — so there is no per-row narrowing to apply and there
    is no oversight relation on this table to narrow BY. An officer therefore reads every recorded
    workshop here and only their own posted ones on the tab beside it. That asymmetry is real, it is
    not this router's to invent a rule about, and ``scopeLabel`` states it in words rather than
    leaving a reader to infer that one tab is broken.

    ── AND THE STANDING WORDS ARE DIFFERENT WORDS ────────────────────────────────────────────────

    ``standing`` here is a ``RecordStatus`` — DRAFT, PENDING, APPROVED, REJECTED, NEEDS_REVISION —
    which is a REVIEW state, not a lifecycle. Mapping "ongoing" onto it would invent a second meaning
    for a word the tab beside this one already uses for something else. ``standingVocabulary`` names
    which set the client is being handed so the screen prints the words the rows were actually filed
    under. See the module docstring.
    """
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)

    where: dict[str, Any] = {}
    clean_search = (search or "").strip()
    if clean_search:
        where["OR"] = [
            {"title": contains(clean_search)},
            {"place": contains(clean_search)},
            {"description": contains(clean_search)},
        ]
    clean_standing = (standing or "").strip().upper()
    if clean_standing in _RECORD_STATUSES:
        # AND-composed for the reason the sibling states, even though there is no scope clause on
        # this table today: writing a narrowing onto ``where["OR"]`` beside the search is a habit,
        # and the day this table gains a scope the habit is what loses it.
        where.setdefault("AND", []).append({"status": clean_standing})

    total, records = await gather_reads(
        db.workshop.count(where=where),
        db.workshop.find_many(
            where=where,
            skip=skip,
            take=clean_size,
            # THE SAME OCCURRENCE ORDER ``GET /workshops`` USES, and for the reason written out
            # there at length: this table's rows are dated visits, and ordering them by ``createdAt``
            # makes "the most recent workshop" mean the most recently TYPED IN rather than the most
            # recently HELD. ``id`` closes it into a total order, which that route still owes.
            order=[
                {"startDate": "desc"},
                {"date": "desc"},
                {"createdAt": "desc"},
                {"id": "asc"},
            ],
        ),
    )
    ids = [str(record.id) for record in records]
    beneficiaries = await register.legacy_beneficiary_counts(ids)

    rows = [_legacy_row(record, beneficiaries) for record in records]
    payload = page_payload(rows, total, clean_page, clean_size)
    payload["scope"] = "estate"
    payload["scopeLabel"] = (
        "Every recorded workshop on the platform. Recorded workshops carry no supervising officer, "
        "so unlike the design & prototype tab this list is not narrowed by your postings."
    )
    payload["standingVocabulary"] = "recordStatus"
    payload["standingGroups"] = {status_name: [status_name] for status_name in _RECORD_STATUSES}
    return payload


#: ``RecordStatus``, written out rather than imported from the schema parser for the reason
#: :data:`ministry_dashboard.ALL_STANDINGS` is: a literal is what makes a sixth member a decision
#: somebody takes rather than one that happens.
_RECORD_STATUSES: tuple[str, ...] = (
    "DRAFT",
    "PENDING",
    "APPROVED",
    "REJECTED",
    "NEEDS_REVISION",
)


def _legacy_row(record: Any, beneficiaries: dict[str, int]) -> dict[str, Any]:
    """One recorded workshop as the register prints it.

    A HAND-WRITTEN DICT AND NOT ``public_encode``, which is the whole-record encoder the record
    surfaces use: it resolves relations, media URLs and identity columns per reader, and none of that
    belongs in a register that draws eight columns. The trap ``workshop_summary`` names applies here
    too — a column added to the schema and not to this dict is invisible on this screen and looks
    from the outside exactly like a column nobody writes.
    """
    workshop_id = str(record.id)
    return {
        "id": workshop_id,
        "title": record.title,
        "workshopType": record.workshopType,
        "place": record.place,
        "date": record.date,
        "startDate": record.startDate,
        "endDate": record.endDate,
        "status": record.status,
        "beneficiaries": beneficiaries.get(workshop_id, 0),
        "createdAt": record.createdAt,
        "updatedAt": record.updatedAt,
    }


@router.get("/summary")
async def register_summary(
    current_user: Any = Depends(require_ministry_dashboard_reader),
) -> dict[str, Any]:
    """How many workshops are in each standing group, counted over the WHOLE scope.

    **COUNTED ON THE SERVER AND NOT BUCKETED IN THE BROWSER.** ``/sanction-orders``' own page states
    the rule this serves: *"NO ``.filter()`` AND NO ``.sort()`` OVER ``data.items``, ANYWHERE ON THIS
    PAGE. Every narrowing is a query parameter. A list filtered in the browser is the right SIZE and
    has silently dropped whatever it excluded."* A bucket count taken from twenty loaded rows is a
    property of the page and not of the platform, and on a ministry screen that number is read as the
    size of the programme.

    ``unclassified`` IS A REAL KEY AND IS USUALLY ZERO. ``ministry_dashboard.group_of`` answers
    ``None`` for a status this build has not heard of — a server one release ahead can legitimately
    store a ninth — and a workshop counted in the total but absent from all three groups would be the
    summary contradicting its own rows with nothing on screen to say so. So it is counted, and the
    page prints it when it is not zero.
    """
    scope = register.scope_clause(current_user)
    base: dict[str, Any] = {"deletedAt": None}
    if scope is not None:
        base["AND"] = [scope]

    names = list(register.STANDING_GROUPS)
    counts = await gather_reads(
        db.designworkshop.count(where=base),
        *(
            db.designworkshop.count(
                where={**base, "status": {"in": list(register.STANDING_GROUPS[name])}}
            )
            for name in names
        ),
        db.workshop.count(where={}),
    )
    total = int(counts[0])
    grouped = {name: int(counts[index + 1]) for index, name in enumerate(names)}

    # ⚠ THE SCOPE SENTENCE BELONGS TO THE DESIGN-WORKSHOP HALF AND NOT TO THE PAYLOAD, and it sat at
    # the top level until review caught it. The two counts in this response are scoped DIFFERENTLY:
    # the design-workshop counts honour `scope_clause`, while `Workshop` has no oversight relation to
    # be narrowed by and is counted whole — `viewable_where` returns `{}` for every signed-in account.
    # One sentence over both therefore told an Assistant Director "the workshops you were named on"
    # above a NATIONAL count of recorded workshops: a tile contradicting its own caption, on the
    # screen whose entire defence against this repository's most repeated bug class is that caption.
    #
    # The fix is placement, not wording. Each half now carries the sentence that is true of it, and
    # the client reads them per tile rather than once per page.
    return {
        "designWorkshops": {
            "total": total,
            **grouped,
            # Derived rather than counted with a NOT-IN query, so it can never disagree with the four
            # numbers beside it: whatever the groups do not account for is what is left.
            "unclassified": max(0, total - sum(grouped.values())),
            "scope": "estate" if register.sees_whole_estate(current_user) else "posted",
            "scopeLabel": register.scope_label(current_user),
        },
        "otherWorkshops": {
            "total": int(counts[-1]),
            "scope": "estate",
            "scopeLabel": (
                "Every recorded workshop on the platform. Recorded workshops carry no supervising "
                "officer, so this count is not narrowed by your postings."
            ),
        },
        # Kept at the top level as well, and it is the DESIGN half's — because that is the register
        # the page opens on and the one the heading is about. A client reading only this key gets the
        # sentence that matches the tab in front of it; the per-half keys above are what a tile reads.
        "scope": "estate" if register.sees_whole_estate(current_user) else "posted",
        "scopeLabel": register.scope_label(current_user),
    }


# --------------------------------------------------------------------------------------
# Downloads
# --------------------------------------------------------------------------------------

#: The register export's row cap.
#:
#: ``EXPORT_TAKE`` in ``routes/export.py`` is 5000 and this matches it deliberately — the whole
#: repository is pulled into memory on a single-worker box, and a register download is not the place
#: to discover a new ceiling. Hitting it raises the truncation note INSIDE the file as well as on the
#: screen, because a spreadsheet outlives the page it came from.
EXPORT_TAKE = 5000

#: The design & prototype register's columns, in file order.
#:
#: ⚠ **THIS LIST IS THE CLAIM THAT THE FILE CARRIES NO STAGE DATA**, and
#: ``tests/test_ministry_dashboard_gate.py`` holds it to that: it pins this literal and
#: asserts that no registry stage-field key appears in it. ``DESIGN_WORKSHOP_DATA_EXPORT_ROLES`` is
#: ``{ADMIN, MASTER_ADMIN}`` and refuses every tier this download is for, and it refuses them from
#: STAGE DATA — the workbook, the archive, the fieldwork. These are the header columns
#: ``workshop_summary`` already hands the same callers on screen, plus the completeness roll-up. If a
#: future change wants an answer, a photograph or a transcript in here, it needs that role and a new
#: decision record, and not a new entry in this tuple.
_DESIGN_EXPORT_COLUMNS: tuple[str, ...] = (
    "Workshop code",
    "Title",
    "Kind",
    "Craft",
    "Cluster",
    "State",
    "District",
    "Venue",
    "Start date",
    "End date",
    "Designer",
    "Standing",
    "Standing group",
    "Artisans",
    "Stages complete",
    "Stages total",
    "Required fields answered",
    "Required fields asked",
    "Progress %",
    "Last updated",
)


def _csv_response(filename: str, rows: list[list[str]], columns: tuple[str, ...]) -> Response:
    """Rectangular CSV, UTF-8, as an attachment.

    THE SAME SHAPE AS ``routes/export.csv_response`` AND NOT A CALL INTO IT, because that helper is
    paired with ``records_to_csv`` over the record registry and this file is not a registry kind. The
    ASCII filename is a constant at every call site here, so the plain ``filename="…"`` header is
    sufficient and the RFC 6266 ``filename*`` form that ``data_browser._content_disposition`` builds
    for Devanagari RECORD names is not owed. Said out loud so nobody "fixes" the simpler header into
    the harder one.
    """
    buffer = io.StringIO()
    writer = csv.writer(buffer)
    writer.writerow(list(columns))
    for row in rows:
        writer.writerow(row)
    return Response(
        content=buffer.getvalue(),
        media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


def _truncation_row(count: int, columns: tuple[str, ...]) -> list[str]:
    """The note a capped file carries, kept rectangular so a parser still reads a clean table.

    ``services/csv_export.records_to_csv`` writes the same note in the same shape for the same
    reason: *"a download that silently stops short is worse than one that says so, and keeping the
    note rectangular means a spreadsheet or a parser still reads the file as a clean table."* The
    wording is that function's, character for character, so the two files do not tell a reader the
    same fact in two sentences.
    """
    note = f"Note: capped at {count} rows — the full data set has more."
    return [note] + [""] * (len(columns) - 1)


def _stamp(value: Any) -> str:
    """A date or timestamp cell, ISO-formatted the way ``workshop_summary`` formats its own.

    ⚠ **``str(datetime)`` IS NOT A DATE AND A SPREADSHEET WILL NOT READ IT AS ONE.** Python renders a
    tz-aware datetime as ``2026-09-20 00:00:00+00:00``; Excel and LibreOffice both take that as TEXT,
    so the column cannot be sorted, filtered by range or charted — which is most of what a register is
    downloaded for. The design-workshop export never had this because ``workshop_summary`` already
    calls ``.date().isoformat()`` on the two dates and ``.isoformat()`` on the stamps; the legacy row
    hands its columns over raw, because the JSON response has FastAPI to serialise them and the CSV
    does not. Same values, two encoders, and only one of them was asked.

    A DATE COLUMN LOSES ITS TIME DELIBERATELY. ``Workshop.date``, ``startDate`` and ``endDate`` are
    the days a visit happened; the midnight on them is an artefact of storing a day in a timestamp
    column, and printing it invites a reader to believe a workshop began at midnight UTC.
    """
    if value is None:
        return ""
    date_part = getattr(value, "date", None)
    if callable(date_part) and getattr(value, "hour", None) is not None:
        # A datetime. Time-of-day is meaningful on a record stamp and not on a workshop's dates, so
        # the caller chooses by which column it passes here — see `_legacy_export_row`.
        return value.isoformat()
    if hasattr(value, "isoformat"):
        return value.isoformat()
    return str(value)


def _day(value: Any) -> str:
    """A calendar day, with the storage artefact of a midnight timestamp dropped. See :func:`_stamp`."""
    if value is None:
        return ""
    date_part = getattr(value, "date", None)
    if callable(date_part):
        return date_part().isoformat()
    if hasattr(value, "isoformat"):
        return value.isoformat()
    return str(value)


def _text(value: Any) -> str:
    """A cell. ``None`` is an empty cell and never the string "None".

    ``datasets.stream_dataset_csv`` states the reason: *"the string 'None' in a CSV cell is a value a
    spreadsheet will happily filter on, and a researcher counting grievances by severity would find a
    category called None sitting between CRITICAL and HIGH."*
    """
    if value is None:
        return ""
    return str(value)


@router.get("/design-workshops.csv")
async def export_design_workshops_csv(
    search: str | None = Query(None, max_length=MAX_SEARCH),
    standing: str | None = Query(None, max_length=32),
    current_user: Any = Depends(require_ministry_dashboard_reader),
) -> Response:
    """The design & prototype register as a spreadsheet — the columns on screen, and nothing else.

    **THE SAME FILTERS AS THE LIST AND NO PAGING.** An officer exporting what they are looking at
    must get what they are looking at; a page of twenty would be a file that silently withheld the
    rest. ``/annual-plan/export.xlsx`` makes the identical trade and is the precedent for this whole
    route — including that it is a MINISTRY register and not designer fieldwork.

    ⚠ **AND THE FILTER IS WRITTEN INTO THE FILE.** The annual plan's export learned this the hard
    way: a filtered download arrives under a plausible filename with nothing about it looking
    partial. Here there is no destructive round trip to protect against — this register has no
    upload — so the note is one row rather than a whole sheet, and it is present whenever a filter
    was applied so that a file opened a year later says which slice of the programme it is.

    **PROGRESS IS SCORED FOR THE ROWS THE SCORER REACHES AND THE REST SAY SO IN THE CELL.** Scoring
    five thousand workshops in one request would read every stage entry in the repository;
    ``PROGRESS_SCORE_CAP`` bounds it, and the cells past the cap carry "not scored in this export"
    rather than a zero, for the reason every other surface in this feature refuses a zero.
    """
    where = _design_where(current_user, search, standing)
    records = await db.designworkshop.find_many(
        where=where, take=EXPORT_TAKE, order=_DESIGN_ORDER
    )
    ids = [str(record.id) for record in records]
    progress, beneficiaries = await gather_reads(
        register.progress_for(ids), register.beneficiary_counts(ids)
    )

    rows: list[list[str]] = []
    for record in records:
        workshop_id = str(record.id)
        summary = workshop_summary(record)
        scored = progress.get(workshop_id)
        rows.append(
            [
                _text(summary.get("workshopCode")),
                _text(summary.get("title")),
                _text(summary.get("workshopKind")),
                _text(summary.get("craftName")),
                _text(summary.get("clusterName")),
                _text(summary.get("state")),
                _text(summary.get("district")),
                _text(summary.get("venue")),
                _text(summary.get("startDate")),
                _text(summary.get("endDate")),
                _text(summary.get("designerName")),
                _text(summary.get("status")),
                _text(register.group_of(summary.get("status"))),
                _text(beneficiaries.get(workshop_id, 0)),
                _text(scored["stagesComplete"]) if scored else "not scored in this export",
                _text(scored["stagesTotal"]) if scored else "",
                _text(scored["requiredFilled"]) if scored else "",
                _text(scored["requiredTotal"]) if scored else "",
                _text(scored["percent"]) if scored else "",
                _text(summary.get("updatedAt")),
            ]
        )

    # ⚠ THE SCOPE GOES INTO THE FILE, ALWAYS, AND IT DID NOT UNTIL REVIEW CAUGHT IT. The filter note
    # below was written and the SCOPE was not, which is the more dangerous omission of the two: a
    # filter is something the officer chose and remembers, while the scope is something the server
    # applied to them silently. An Assistant Director's four-workshop export and the national register
    # arrive under the same filename, with the same columns, and nothing inside either to tell them
    # apart. A year later, in a shared drive, the smaller one reads as a programme that was that size.
    #
    # UNCONDITIONAL, unlike the filter note, because "this is the whole estate" is a fact worth
    # asserting too — a file that says nothing about its scope is one a reader has to guess about.
    rows.append(
        [
            f"Scope: {register.scope_label(current_user)}",
            *([""] * (len(_DESIGN_EXPORT_COLUMNS) - 1)),
        ]
    )
    if (search or "").strip() or register.standing_clause(standing) is not None:
        rows.append(
            [
                "Note: this file was taken under a filter and is not the whole register.",
                *([""] * (len(_DESIGN_EXPORT_COLUMNS) - 1)),
            ]
        )
    if len(records) >= EXPORT_TAKE:
        rows.append(_truncation_row(len(records), _DESIGN_EXPORT_COLUMNS))

    return _csv_response(
        "design-prototype-workshops.csv", rows, _DESIGN_EXPORT_COLUMNS
    )


#: The other-workshops register's columns, in file order.
_OTHER_EXPORT_COLUMNS: tuple[str, ...] = (
    "Title",
    "Kind",
    "Place",
    "Date",
    "Start date",
    "End date",
    "Status",
    "Artisans",
    "Recorded on",
)


@router.get("/workshops.csv")
async def export_other_workshops_csv(
    search: str | None = Query(None, max_length=MAX_SEARCH),
    standing: str | None = Query(None, max_length=32),
    _: Any = Depends(require_ministry_dashboard_reader),
) -> Response:
    """The recorded-workshop register as a spreadsheet. Same filters as its list, no paging."""
    where: dict[str, Any] = {}
    clean_search = (search or "").strip()
    if clean_search:
        where["OR"] = [
            {"title": contains(clean_search)},
            {"place": contains(clean_search)},
            {"description": contains(clean_search)},
        ]
    clean_standing = (standing or "").strip().upper()
    if clean_standing in _RECORD_STATUSES:
        where.setdefault("AND", []).append({"status": clean_standing})

    records = await db.workshop.find_many(
        where=where,
        take=EXPORT_TAKE,
        order=[{"startDate": "desc"}, {"date": "desc"}, {"createdAt": "desc"}, {"id": "asc"}],
    )
    beneficiaries = await register.legacy_beneficiary_counts([str(r.id) for r in records])

    rows = [
        [
            _text(record.title),
            _text(record.workshopType),
            _text(record.place),
            # The three DAYS lose their midnight; the record STAMP keeps its time. See `_stamp`.
            _day(record.date),
            _day(record.startDate),
            _day(record.endDate),
            _text(record.status),
            _text(beneficiaries.get(str(record.id), 0)),
            _stamp(record.createdAt),
        ]
        for record in records
    ]

    # The same unconditional scope row as the sibling export — and here it says the OTHER thing, which
    # is why it is written out rather than shared: this table carries no oversight relation, so every
    # caller gets every row and the file should say that rather than leave it to be assumed.
    rows.append(
        [
            "Scope: every recorded workshop on the platform. Recorded workshops carry no supervising "
            "officer, so this file is not narrowed by the reader's postings.",
            *([""] * (len(_OTHER_EXPORT_COLUMNS) - 1)),
        ]
    )
    if clean_search or clean_standing in _RECORD_STATUSES:
        rows.append(
            [
                "Note: this file was taken under a filter and is not the whole register.",
                *([""] * (len(_OTHER_EXPORT_COLUMNS) - 1)),
            ]
        )
    if len(records) >= EXPORT_TAKE:
        rows.append(_truncation_row(len(records), _OTHER_EXPORT_COLUMNS))

    return _csv_response("other-workshops.csv", rows, _OTHER_EXPORT_COLUMNS)


@router.get("/entitlements")
async def download_entitlements(
    current_user: Any = Depends(require_ministry_dashboard_reader),
) -> dict[str, Any]:
    """What THIS account may take out of the product, so the page can say it beside the button.

    **SAY IT ON SCREEN, NEVER 403 A BUTTON** — ``deps.can_export_design_workshop_data``'s own
    standing rule, stated there in capitals: *"Handing them a download button that answers 403 would
    teach them the product is broken rather than that the rule exists."* The beneficiaries download
    is gated by ``can_download_dataset`` (Professor and above, or the grant), which every tier this
    router admits already clears — so today this key is true for every caller, and it is asked for
    rather than assumed because the day the gate moves, the screen must move with it without anybody
    remembering to.

    THE TWO REGISTER DOWNLOADS ARE NOT LISTED HERE because they carry no entitlement of their own:
    they are this router's own rows, under this router's own gate, so a caller who reached this
    endpoint may take them. Listing them would imply a second question that is not asked.
    """
    return {
        "beneficiaries": bool(can_download_dataset(current_user)),
        "beneficiariesRefusal": (
            "Downloading the beneficiary list needs dataset-download access — Professor and above, "
            "or an explicit grant. Ask an admin to grant it."
        ),
    }


# --------------------------------------------------------------------------------------
# THE PEOPLE REGISTERS — who is running, supervising and inspecting the programme
# --------------------------------------------------------------------------------------
#
# ═══════════════════════════════════════════════════════════════════════════════════════════════
# THREE MORE GETs ON THE SAME PREFIX, UNDER THE SAME DEPENDENCY, WITH NO ``/{id}`` BETWEEN THEM
# ═══════════════════════════════════════════════════════════════════════════════════════════════
#
# Everything the module docstring says about the two workshop registers holds here unchanged, and
# these three routes were written to keep it true rather than to test it:
#
#   * **Every one is a GET.** Nothing on this prefix writes. A people register is a read of
#     relations that three OTHER features own the writes to — ``design_workshop_viewers``,
#     ``design_workshop_oversight`` and ``design_workshop_inspectors`` — and a write here would be a
#     second place those tables are maintained from. The viewer table is the dangerous one: a row in
#     it confers STAGE WRITES, because ``load_workshop_or_404(for_edit=True)`` reads the same
#     relation.
#   * **Every one takes** ``Depends(require_ministry_dashboard_reader)``, so the gate is visible as a
#     dependency rather than as a line somebody can forget inside a body.
#   * **Not one declares a path parameter.** "What is this one officer overseeing" is a question this
#     router deliberately cannot be asked: it would be a second door onto one person's postings, and
#     the ⚠ ordering hazard the oversight module spends a paragraph on arrives with the first
#     ``/{id}``. The registers answer about the register.
#
# ``tests/test_ministry_dashboard_gate.py`` sweeps all three properties across the whole router, so
# the paragraph above is a description of a test rather than a request to a future reader.
#
# ═══════════════════════════════════════════════════════════════════════════════════════════════
# THE GATE WAS NOT TOUCHED, AND THREE SEPARATE TEMPTATIONS TO TOUCH IT WERE REFUSED
# ═══════════════════════════════════════════════════════════════════════════════════════════════
#
# This page's floor is ASSISTANT_DIRECTOR, rank 42, and every list of PEOPLE in this product sits
# behind a gate that was drawn for somebody more senior. The repository's standing answer to that is
# written out in ``routes/sanction_orders.list_sanction_designers``: **a fifth door with a narrower
# payload, never a widened gate.** So:
#
#   * ``can_manage_designer_roster`` (``is_admin``) is UNTOUCHED. It stands in front of the
#     empanelment table, and an account that could reach it could end a designer's sign-in. Nothing
#     here reads ``DesignerRoster`` directly and no row carries a roster judgement —
#     ``designers.assignable_designers_payload`` is the payload, four keys, and
#     ``ministry_dashboard.new_person_row`` is the only place a people row is built.
#   * ``OVERSIGHT_ASSIGNER_ROLES`` is UNTOUCHED, REGIONAL_DIRECTOR's exclusion included. The two
#     account directories behind it — ``officer_directory`` and ``eligible_inspectors`` — are offered
#     only to callers who are already inside that set, which
#     ``ministry_dashboard.may_read_account_directories`` establishes is exactly
#     ``sees_whole_estate``. The other two tiers get the relation and a sentence saying so.
#   * ``MINISTRY_DASHBOARD_ROLES`` is UNTOUCHED and is still a SET with a hole at ADMIN. Nothing here
#     needs a rank floor and nothing here should grow one.
#
# The one directory that IS handed to all four tiers is the empanelled designer roster, and that is
# not an exception: ``GET /sanction-orders/designers`` already answers the identical call at a rank
# floor of 42. ``ministry_dashboard.empanelled_designer_accounts`` carries the arithmetic.
#
# ═══════════════════════════════════════════════════════════════════════════════════════════════
# EVERY AUXILIARY READ DEGRADES ON ITS OWN, AND THE SHIMS ARE PER READ RATHER THAN PER PAGE
# ═══════════════════════════════════════════════════════════════════════════════════════════════
#
# ``_design_rows`` above records what the first attempt at this got wrong — one guard around a
# ``gather_reads`` of two coroutines, whose ``except`` then RE-RAN one of them — and the fix is the
# shape copied here: each optional read is wrapped in its own failure-to-``None`` shim and the shims
# are gathered, so the round trips still overlap and no one of them can take another down. What is
# PRIMARY on each route (the workshop scan and the link read) is deliberately unguarded: without it
# there is no register to serve, and a 200 carrying an empty list would say the programme has no
# designers in it.
#
# And every degradation is NAMED IN THE PAYLOAD — ``progressRead``, ``feedbackRead``,
# ``includesUnpostedAccounts`` and the sentence beside each. A column that quietly turns into blanks
# is a screen telling a ministry that nobody has done anything.


def _people_scope(current_user: Any) -> str:
    """``estate`` or ``posted`` — the same two words the workshop registers use, for one reason.

    The people lists are derived FROM the workshop scope, so a client that renders the two words
    differently on two tabs of one screen would be describing one decision twice. The SENTENCE
    differs (``people_scope_label``); the token does not.
    """
    return "estate" if register.sees_whole_estate(current_user) else "posted"


async def _people_progress_or_none(
    workshop_ids: list[str], register_name: str
) -> dict[str, dict[str, Any]] | None:
    """Score the scanned workshops, or answer ``None`` and let the register serve without percentages.

    THE SAME SHIM ``_design_rows`` USES AND FOR ITS REASON: the scan and the link read have already
    succeeded by the time this runs, so the ministry CAN be told who is on what. Turning the whole
    response into a 500 over the completeness read would trade a list with one unreadable column for
    no list at all, and an officer meeting that reads "the register could not be read".

    It degrades to ``progressReason: "unreadable"``, NEVER to zero — ``_roll_up``'s note has the
    argument, and ``close_person_progress`` keeps the three absences three separate facts.
    """
    try:
        return await register.progress_for(workshop_ids)
    except Exception:
        logger.exception(
            "ministry dashboard: scoring %d workshop(s) for the %s register failed",
            len(workshop_ids),
            register_name,
        )
        return None


def _people_envelope(
    ordered: list[dict[str, Any]],
    *,
    current_user: Any,
    noun: str,
    page: int,
    page_size: int,
    skip: int,
    scan: dict[str, Any],
    withheld: int,
    scoring_read: bool,
    includes_unposted: bool,
    unposted_note: str,
) -> dict[str, Any]:
    """The keys every people list carries, so the three cannot describe themselves differently.

    ⚠ **THE SCOPE SENTENCE IS PER LIST AND NOT PER PAGE**, which is this router's hardest-won rule:
    ``register_summary`` shipped one caption over two differently-scoped counts and told an Assistant
    Director "the workshops you were named on" above a NATIONAL figure. Each of these three lists is
    built from the caller's own workshop scope, so each gets its own noun in its own sentence.

    ``standingGroups`` TRAVELS WITH THE ROWS because every row carries a standing breakdown under
    those exact keys, and a client that had to guess which three words the server used would guess
    from a build that may be older than this one. ``standingVocabulary`` names which enum they are,
    exactly as the two workshop registers do — these counts are ``DesignWorkshopStatus`` and never
    ``RecordStatus``, and one word meaning two things on one screen is the defect the module
    docstring refuses at length.
    """
    payload = page_payload(ordered[skip : skip + page_size], len(ordered), page, page_size)
    payload["scope"] = _people_scope(current_user)
    # ⚠ THE FOLD IS PASSED IN, NOT ASSUMED. When an account directory is folded in, "somebody who
    # works only on workshops you were not posted to is absent" stops being true — they are on
    # screen with a measured zero — and a caption contradicting a row the reader can see is how a
    # caption stops being believed. See `people_scope_label`.
    payload["scopeLabel"] = register.people_scope_label(
        current_user, noun=noun, includes_unposted=includes_unposted
    )
    payload["standingVocabulary"] = "designWorkshopLifecycle"
    payload["standingGroups"] = {
        name: list(members) for name, members in register.STANDING_GROUPS.items()
    }
    payload["scan"] = scan
    payload["progressScoreCap"] = register.PROGRESS_SCORE_CAP
    payload["progressRead"] = scoring_read
    # A SENTENCE AND NOT ONLY A BOOLEAN. The client has to tell a reader why an entire column is
    # blank, and a column of blanks with no explanation reads as a programme where nothing has been
    # done — the most damaging false statement on this screen, one surface out from `_roll_up`.
    payload["progressNote"] = (
        None
        if scoring_read
        else (
            "Progress could not be read for this request, so no percentage below is a measurement. "
            "Each row says so under progressReason rather than showing 0%."
        )
    )
    # MEASURED AND REPORTED, NEVER SILENT. The withheld accounts' workshops are still inside
    # `workshopsRead`, so a reader adding the rows up would find them short with nothing to explain
    # the difference. The count names nobody, which is the whole point of reporting a count.
    payload["withheldAccounts"] = withheld
    payload["withheldAccountsNote"] = (
        None
        if withheld == 0
        else (
            f"{withheld} account(s) holding a row on these workshops are not named here because "
            "they are platform administrator accounts, which this register never lists. Their "
            "workshops are still counted in the totals above."
        )
    )
    payload["includesUnpostedAccounts"] = includes_unposted
    payload["unpostedAccountsNote"] = unposted_note
    return payload


@router.get("/designers")
async def list_designers(
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=MAX_PAGE_SIZE),
    standing: str | None = Query(None, max_length=32),
    current_user: Any = Depends(require_ministry_dashboard_reader),
) -> dict[str, Any]:
    """Who is running design & prototype workshops, how many, at what standing, and how far along.

    ── ⚠ COUNTED OVER RELATIONS, NEVER OVER ``designerName`` ─────────────────────────────────────

    ``DesignWorkshop.designerName`` is a free-typed string ``promoted_values()`` denormalises off
    stage 1: no index, no uniqueness, no foreign key, nothing reconciling it against an account.
    Grouping on it would make two spellings two designers and one shared spelling one designer, and
    would omit every designer who has not opened stage 1. ``design_workshop_oversight._the_lead_among``
    exists because matching that string back to an account is a guess that answers ``None`` whenever
    it is not certain. This register counts ``DesignWorkshopViewer`` rows and ``createdById``, both of
    which are ids. The services module's people-register header carries the full argument.

    **BOTH ARMS, AND THE SECOND IS THE ONE THAT WILL LOOK REDUNDANT.** ``named_designer_rows`` states
    in capitals that the creator holds NO viewer row — their access is ``createdById`` — so a register
    built on viewer rows alone would be missing the lead designer of every workshop nobody has shared,
    which is most of them on the day they are opened. ``workshopsCreated`` and ``workshopsNamedOn``
    are reported separately and ``workshops`` counts each workshop ONCE, because a designer who opened
    a workshop and was later also added as a viewer is one designer on one workshop.

    ── THE EMPANELLED DESIGNER WITH NOTHING TO DO IS THE MOST ACTIONABLE ROW ON THIS SCREEN ──────

    A register built only from links cannot contain them: somebody with no workshop row would be
    indistinguishable from somebody who does not exist, which is absence reading as non-existence in
    the column this page is named after. So the empanelled roster is folded in, through
    ``ministry_dashboard.empanelled_designer_accounts`` — the SAME call
    ``GET /sanction-orders/designers`` already answers at a rank floor of ASSISTANT_DIRECTOR, with
    ``include_admins=False``, so this widens nothing and discloses nothing new to any tier here.
    Those rows carry a MEASURED zero and say ``progressReason: "noWorkshops"``, which is not the same
    statement as 0% and not the same statement as "we did not look".

    ── THE PAYLOAD IS FOUR IDENTITY KEYS AND WHAT THIS REGISTER MEASURED ────────────────────────

    ``assignable_designers_payload``: ``id``, ``name``, ``email``, ``role``. **NO** ``rosterActive``,
    **NO** ``canSignIn``, **NO** ``firstSeenAt``, **NO** ``institution``, **NO** ``rosterId``. Every
    one of those is a fact about the empanelment table, which ``can_manage_designer_roster``
    (``is_admin``) stands in front of, and an officer reading this page is rank 42. Whether a designer
    has a suspension on file is not an officer's business — and the suspended are already gone before
    the roster fold runs, because ``workshop_capable_accounts`` folds the roster into the ``WHERE``.

    ``standing`` narrows the WORKSHOP SCAN and is the same word, the same groups and the same
    ignore-an-unknown rule as the register beside it. **There is deliberately no ``search``**: a term
    applied to people found by scanning at most ``PEOPLE_WORKSHOP_SCAN`` workshops would search only
    the part of the register that fitted inside the cap, which is the defect ``eligible_inspectors``
    was fixed for twice. Narrow by standing, which is inside the query.
    """
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    # ⚠ THE SAME `_design_where` THE REGISTER USES, so the scope lands on `where["AND"]` and can
    # never be assigned beside a search's `OR`. `search` is None here by design — see the docstring.
    where = _design_where(current_user, None, standing)
    total_in_scope, records = await register.people_spine(where)
    workshop_ids = [str(record.id) for record in records]

    async def _roster_or_none() -> tuple[list[Any], bool] | None:
        try:
            return await register.empanelled_designer_accounts()
        except Exception:
            logger.exception("ministry dashboard: reading the empanelled designer roster failed")
            return None

    async def _representation_or_none() -> dict[str, Any] | None:
        """How much of the roster this register structurally cannot show.

        ITS OWN SHIM, like every other optional read here. It answers a CAVEAT rather than a column,
        so a failure must cost the caveat and never the register — and the client is told the
        difference, because a missing caveat that looks like "nothing is missing" is the exact
        inversion this whole feature exists to prevent.
        """
        try:
            return await register.roster_representation()
        except Exception:
            logger.exception("ministry dashboard: measuring roster representation failed")
            return None

    # FIVE READS, GATHERED, AND ONLY TWO OF THEM MAY FAIL. The viewer links and the creator accounts
    # are what this register IS; the scoring, the roster and the representation gap are columns and
    # captions on it. See the section header.
    links, creators, scored_map, roster, representation = await gather_reads(
        register.designer_links(workshop_ids),
        register.creator_accounts(records),
        _people_progress_or_none(workshop_ids, "designer"),
        _roster_or_none(),
        _representation_or_none(),
    )

    scoring_read = scored_map is not None
    progress: dict[str, dict[str, Any]] = scored_map or {}
    by_workshop = {str(record.id): record for record in records}

    rows: dict[str, dict[str, Any]] = {}
    withheld: set[str] = set()
    # A PERSON AND A WORKSHOP ARE COUNTED ONCE. The two arms overlap — a designer can have opened a
    # workshop AND hold a viewer row on it — and without this set that designer's workshop count, her
    # standing tally and her progress denominator would all be double.
    counted: set[tuple[str, str]] = set()

    def _person(user: Any) -> dict[str, Any] | None:
        user_id = str(getattr(user, "id", "") or "")
        if not user_id:
            return None
        if register.is_withheld_person(user):
            withheld.add(user_id)
            return None
        row = rows.get(user_id)
        if row is None:
            row = rows[user_id] = register.new_person_row(user)
            # The two link kinds, reported apart. "She opened nine and was added to two" and "she was
            # added to eleven" are different facts about who is running a workshop, and `workshops`
            # alone cannot tell them apart.
            row["workshopsCreated"] = 0
            row["workshopsNamedOn"] = 0
        return row

    def _fold(user: Any, record: Any, *, created: bool) -> None:
        row = _person(user) if user is not None else None
        if row is None or record is None:
            return
        row["workshopsCreated" if created else "workshopsNamedOn"] += 1
        pair = (str(row["id"]), str(record.id))
        if pair in counted:
            return
        counted.add(pair)
        register.count_workshop(row, record)
        register.add_score(row, progress.get(str(record.id)))

    for record in records:
        _fold(creators.get(str(getattr(record, "createdById", "") or "")), record, created=True)
    for link in links:
        _fold(
            getattr(link, "user", None),
            by_workshop.get(str(getattr(link, "designWorkshopId", "") or "")),
            created=False,
        )

    roster_users, roster_truncated = roster if roster is not None else ([], False)
    for user in roster_users:
        # No workshop is folded in: this call only ensures the person EXISTS in the register, with
        # counters that are a measured zero. Somebody already found through a link is untouched.
        _person(user)

    for row in rows.values():
        register.close_person_progress(row, scoring_read=scoring_read)

    payload = _people_envelope(
        register.order_people(list(rows.values())),
        current_user=current_user,
        noun="designer",
        page=clean_page,
        page_size=clean_size,
        skip=skip,
        scan=register.scan_report(total_in_scope, len(records)),
        withheld=len(withheld),
        scoring_read=scoring_read,
        includes_unposted=roster is not None,
        unposted_note=(
            "Empanelled designers holding no workshop in this scope are listed with a measured zero, "
            "so somebody who has been given nothing is visible rather than absent."
            if roster is not None
            else "The empanelled designer roster could not be read for this request, so a designer "
            "who holds no workshop in this scope is missing from this list entirely. This is not "
            "the same as there being none."
        ),
    )
    # The roster read has a ceiling of its own (`designers.DIRECTORY_TAKE`) and a list that stopped
    # at it must say so — the inference two other clients already draw from the same number.
    payload["unpostedAccountsTruncated"] = roster_truncated

    # ── WHY NINE, WHEN THE ROSTER SAYS THIRTY-FIVE ────────────────────────────────────────────
    #
    # Reported on 2026-09-20 against production: the designer roster showed 35 and this register
    # showed 9, and the 9 was arithmetically correct — 24 of those addresses had no `User` row at
    # all and 2 held an account under another role. Every number on the payload was right and the
    # screen still could not be believed, because nothing accounted for the other twenty-six.
    #
    # The counts are DERIVED on every request, never stored and never written down: see
    # `register.roster_representation`, which recomputes the eligible role set from
    # `workshop_capable_roles()` rather than naming a role, so it cannot drift from the fold above.
    #
    # NULL IS NOT ZERO HERE EITHER. A failed measurement leaves every figure absent and the note
    # says the measurement failed — it does NOT say "nobody is missing", which is what a zero would
    # claim and is the one answer this screen must never give by accident.
    if representation is None:
        payload["rosterRepresentation"] = None
        payload["rosterRepresentationNote"] = (
            "How much of the empanelled roster this register can show could not be measured for "
            "this request. The count above may therefore be smaller than the roster."
        )
    else:
        payload["rosterRepresentation"] = representation
        # `payload["total"]` and not the page's length: the caption says how many of the roster are
        # "listed above", and a reader on page two must not be told that two designers are listed.
        payload["rosterRepresentationNote"] = register.roster_representation_note(
            representation, int(payload.get("total") or 0)
        )
    return payload


@router.get("/officers")
async def list_officers(
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=MAX_PAGE_SIZE),
    standing: str | None = Query(None, max_length=32),
    current_user: Any = Depends(require_ministry_dashboard_reader),
) -> dict[str, Any]:
    """The Assistant Directors and Regional Directors, and what each of them oversees.

    ── THE RELATION IS ``DesignWorkshopOversight``, WHICH IS THE ONE THIS WHOLE PAGE IS SCOPED BY ──

    ``oversight_by_clause`` narrows every other read on this router by exactly this table; this route
    reads it in the other direction. So a Regional Director opening this list sees the officers who
    share their own postings, and ``scopeLabel`` says that in words — an officer reading a list of
    three colleagues as "the directorate" would be reading their own postings as the country.

    ``byCapacity`` AND NOT A ROLE COUNT. An oversight row carries the CAPACITY the person was filed
    in, and ``OVERSIGHT_CAPACITY_ROLES`` allows exactly one role per capacity today — so the two
    happen to agree, and would stop agreeing the day a third capacity is added, which is an
    ``ALTER TYPE`` plus an entry in that map. The keys are seeded from ``CAPACITIES`` so a third one
    appears here without a code change, and ``unknownCapacity`` catches a capacity a server one
    release ahead can legitimately store — the same reasoning ``group_of`` and ``unclassified``
    already carry for a ninth status.

    ── ⚠ OFFICERS WITH NO POSTING AT ALL: THE ANSWER DEPENDS ON THE CALLER, AND THE PAYLOAD SAYS SO ─

    ``officer_directory`` — every account holding a ministry post, whether posted to anything or not
    — is served today behind ``require_workshop_assigner``, i.e. ``OVERSIGHT_ASSIGNER_ROLES`` =
    ``{MINISTRY_ADMIN, ADMIN, MASTER_ADMIN}``. That set REFUSES a REGIONAL_DIRECTOR deliberately —
    *"the supervised must not choose the supervisor"* — and ``routes/sanction_orders`` records the
    standing ruling that a tier needing a list gets a fifth door with a narrower payload and never a
    widened gate. ``may_read_account_directories`` establishes that the set is, member for member,
    the one ``sees_whole_estate`` already answers True for, so:

      * an estate reader is folded the directory in — a list they can already open at
        ``GET /design-workshop-oversight/officers``, so nothing is widened;
      * an Assistant Director and a Regional Director get the relation alone, and
        ``includesUnpostedAccounts: false`` with a sentence saying which narrower question was
        answered. A list that silently answers a narrower question than the one asked is this
        repository's most repeated bug class with the numbers left intact.

    A third state exists and is kept distinct: offered, attempted, and FAILED. That is not the same
    fact as not offered, and the note says which.
    """
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    where = _design_where(current_user, None, standing)
    total_in_scope, records = await register.people_spine(where)
    workshop_ids = [str(record.id) for record in records]

    offered = register.may_read_account_directories(current_user)

    async def _directory_or_none() -> dict[str, Any] | None:
        if not offered:
            return None
        try:
            return await register.officer_accounts()
        except Exception:
            logger.exception("ministry dashboard: reading the officer directory failed")
            return None

    links, scored_map, directory = await gather_reads(
        register.oversight_links(workshop_ids),
        _people_progress_or_none(workshop_ids, "officer"),
        _directory_or_none(),
    )

    scoring_read = scored_map is not None
    progress: dict[str, dict[str, Any]] = scored_map or {}
    by_workshop = {str(record.id): record for record in records}

    rows: dict[str, dict[str, Any]] = {}
    withheld: set[str] = set()

    def _person(user: Any) -> dict[str, Any] | None:
        user_id = str(getattr(user, "id", "") or "")
        if not user_id:
            return None
        if register.is_withheld_person(user):
            withheld.add(user_id)
            return None
        row = rows.get(user_id)
        if row is None:
            row = rows[user_id] = register.new_person_row(user)
            # Seeded from CAPACITIES so a third capacity appears without a code change here, and a
            # capacity an officer holds none of prints a measured 0 rather than a missing key.
            row["byCapacity"] = dict.fromkeys(officers.CAPACITIES, 0)
            row["unknownCapacity"] = 0
        return row

    for link in links:
        record = by_workshop.get(str(getattr(link, "designWorkshopId", "") or ""))
        row = _person(getattr(link, "user", None))
        if row is None or record is None:
            continue
        capacity = getattr(link, "capacity", None)
        capacity_name = str(getattr(capacity, "value", capacity) or "")
        if capacity_name in row["byCapacity"]:
            row["byCapacity"][capacity_name] += 1
        else:
            row["unknownCapacity"] += 1
        # The PK is [designWorkshopId, capacity], so one person can hold BOTH capacities on one
        # workshop only by being filed in two slots — which `OVERSIGHT_CAPACITY_ROLES` forbids today
        # by role. Counted per row regardless: `workshops` is what this register measured, and
        # inventing a de-duplication for a state the schema does not produce would be a rule nobody
        # could test.
        register.count_workshop(row, record)
        register.add_score(row, progress.get(str(record.id)))

    for entry in (directory or {}).get("users", []):
        # ⚠ ONLY THE POSTS THAT CAN HOLD A CAPACITY, AND THE OMISSION IS A ROLE FACT RATHER THAN A
        # TRUNCATION. `officer_directory` answers all THREE ministry posts, and
        # `OVERSIGHT_CAPACITY_ROLES` admits a MINISTRY_ADMIN to neither slot — which is exactly what
        # that payload's `capacities` key exists to tell a picker, and why this reads the key rather
        # than re-deriving the rule from a role name. A Ministry Administrator listed here with two
        # zeros would read as an officer supervising nothing, when in truth they supervise nothing
        # BY ROLE and can never appear in the relation this list is counted over. The sentence under
        # `unpostedAccountsNote` says so rather than leaving the absence to be noticed.
        if not entry.get("capacities"):
            continue
        # The directory's rows are plain dicts, not User records, so they are wrapped in the same
        # attribute shape `new_person_row` reads. Nothing new is disclosed: `officer_directory`
        # answers id/name/email/role/capacities and only the first four are carried through.
        _person(
            SimpleNamespace(
                id=entry.get("id"),
                name=entry.get("name"),
                email=entry.get("email"),
                role=entry.get("role"),
            )
        )

    for row in rows.values():
        register.close_person_progress(row, scoring_read=scoring_read)

    payload = _people_envelope(
        register.order_people(list(rows.values())),
        current_user=current_user,
        noun="officer",
        page=clean_page,
        page_size=clean_size,
        skip=skip,
        scan=register.scan_report(total_in_scope, len(records)),
        withheld=len(withheld),
        scoring_read=scoring_read,
        includes_unposted=directory is not None,
        unposted_note=(
            "Officers holding a ministry post but posted to nothing in this scope are listed with "
            "a measured zero. Only the two posts that can hold a capacity are listed — a Ministry "
            "Administrator may be named on no oversight slot at all, so they are not an officer "
            "this register counts."
            if directory is not None
            else (
                "This list is only the officers posted to workshops you can see. The directory of "
                "every ministry post is read on Workshop oversight, which an Assistant Director and "
                "a Regional Director are not admitted to, so an officer with no posting here is "
                "absent from this list rather than shown with a zero."
                if not offered
                else "The directory of ministry posts could not be read for this request, so an "
                "officer posted to nothing is missing from this list entirely. This is not the same "
                "as there being none."
            )
        ),
    )
    payload["unpostedAccountsTruncated"] = bool((directory or {}).get("truncated", False))
    payload["capacities"] = list(officers.CAPACITIES)
    return payload


@router.get("/inspectors")
async def list_inspectors(
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=MAX_PAGE_SIZE),
    standing: str | None = Query(None, max_length=32),
    current_user: Any = Depends(require_ministry_dashboard_reader),
) -> dict[str, Any]:
    """Who inspects, how many workshops, how much feedback they filed and how many sent a workshop back.

    ── TWO FEEDBACK NUMBERS BECAUSE THEY ARE TWO DIFFERENT JOBS ─────────────────────────────────

    ``DwInspectionFeedback.sentBack`` is true only on the suggestion that actually moved a workshop
    to NEEDS_REVISION — that column's own note — while the rest are suggestions added to an open
    round. An inspector who filed forty notes and sent nothing back and one who filed forty and sent
    back thirty are doing different things, and a single "feedback" number cannot tell them apart.
    Both are ``group_by`` aggregates: a register that counts suggestions has no business loading the
    sentences an officer wrote to a named designer about a named field.

    ── ⚠ AND THE SUGGESTIONS FILED BY PEOPLE WHO ARE NOT ON THIS LIST ARE COUNTED AND REPORTED ────

    Feedback is filed by whoever is entitled to file it, and holding an inspector row on one of the
    scanned workshops is not the same set. Attributing only what this list can attribute and printing
    the attributed total as "the feedback" would be a number that silently excludes an unknown
    amount — so ``feedbackFiledTotal``, ``feedbackAttributed`` and ``feedbackByAccountsNotListed``
    all travel, derived from the same two aggregates rather than from a third query. The third of
    those is usually zero and is a measured zero.

    ── OFFERING AN INSPECTOR WHO INSPECTS NOTHING: THE SAME RULE AS THE OFFICERS' LIST ───────────

    ``eligible_inspectors`` is behind ``require_workshop_assigner``, which is
    ``sees_whole_estate``'s set exactly (see ``may_read_account_directories``). So a Ministry
    Administrator and the master admin — who can already open
    ``GET /design-workshop-inspections/eligible-inspectors`` — have unassigned inspectors folded in
    with a measured zero, and the other two tiers are told in a sentence that this list is only the
    inspectors on workshops they can see. No gate moved to make that happen.
    """
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    where = _design_where(current_user, None, standing)
    total_in_scope, records = await register.people_spine(where)
    workshop_ids = [str(record.id) for record in records]

    offered = register.may_read_account_directories(current_user)

    async def _feedback_or_none() -> dict[str, dict[str, int]] | None:
        try:
            return await register.inspection_feedback_counts(workshop_ids)
        except Exception:
            logger.exception(
                "ministry dashboard: counting inspection feedback on %d workshop(s) failed",
                len(workshop_ids),
            )
            return None

    async def _directory_or_none() -> dict[str, Any] | None:
        if not offered:
            return None
        try:
            return await register.inspector_accounts()
        except Exception:
            logger.exception("ministry dashboard: reading the eligible-inspector directory failed")
            return None

    links, scored_map, feedback, directory = await gather_reads(
        register.inspector_links(workshop_ids),
        _people_progress_or_none(workshop_ids, "inspector"),
        _feedback_or_none(),
        _directory_or_none(),
    )

    scoring_read = scored_map is not None
    progress: dict[str, dict[str, Any]] = scored_map or {}
    feedback_read = feedback is not None
    filed = (feedback or {}).get("filed", {})
    sent_back = (feedback or {}).get("sentBack", {})
    by_workshop = {str(record.id): record for record in records}

    rows: dict[str, dict[str, Any]] = {}
    withheld: set[str] = set()

    def _person(user: Any) -> dict[str, Any] | None:
        user_id = str(getattr(user, "id", "") or "")
        if not user_id:
            return None
        if register.is_withheld_person(user):
            withheld.add(user_id)
            return None
        row = rows.get(user_id)
        if row is None:
            row = rows[user_id] = register.new_person_row(user)
            # ⚠ `None` AND NOT 0 WHEN THE AGGREGATE COULD NOT BE READ. An inspector who has filed
            # nothing and an inspector whose filings this request failed to count are different
            # facts: the first is an ordinary state on the day they are assigned, the second is a
            # fact about the request, and "0 suggestions" against a working inspector is the
            # accusation this distinction exists to prevent.
            row["feedbackFiled"] = filed.get(user_id, 0) if feedback_read else None
            row["sendBacks"] = sent_back.get(user_id, 0) if feedback_read else None
        return row

    for link in links:
        record = by_workshop.get(str(getattr(link, "designWorkshopId", "") or ""))
        row = _person(getattr(link, "user", None))
        if row is None or record is None:
            continue
        register.count_workshop(row, record)
        register.add_score(row, progress.get(str(record.id)))

    for entry in (directory or {}).get("users", []):
        _person(
            SimpleNamespace(
                id=entry.get("id"),
                name=entry.get("name"),
                email=entry.get("email"),
                role=entry.get("role"),
            )
        )

    for row in rows.values():
        register.close_person_progress(row, scoring_read=scoring_read)

    ordered = register.order_people(list(rows.values()))
    payload = _people_envelope(
        ordered,
        current_user=current_user,
        noun="inspector",
        page=clean_page,
        page_size=clean_size,
        skip=skip,
        scan=register.scan_report(total_in_scope, len(records)),
        withheld=len(withheld),
        scoring_read=scoring_read,
        includes_unposted=directory is not None,
        unposted_note=(
            "Inspectors who may be assigned but hold no inspection in this scope are listed with a "
            "measured zero."
            if directory is not None
            else (
                "This list is only the inspectors assigned to workshops you can see. The directory "
                "of every account that may be assigned an inspection is read on Workshop oversight, "
                "which an Assistant Director and a Regional Director are not admitted to, so an "
                "inspector with no assignment here is absent rather than shown with a zero."
                if not offered
                else "The directory of assignable inspectors could not be read for this request, so "
                "an inspector holding no assignment is missing from this list entirely. This is not "
                "the same as there being none."
            )
        ),
    )
    payload["unpostedAccountsTruncated"] = bool((directory or {}).get("truncated", False))
    payload["feedbackRead"] = feedback_read
    # ⚠ NEVER A ZERO WE DID NOT MEASURE. When the aggregate failed these are None, not 0 — and the
    # note is the sentence the screen prints in place of the column.
    payload["feedbackFiledTotal"] = sum(filed.values()) if feedback_read else None
    payload["feedbackAttributed"] = (
        sum(int(row["feedbackFiled"] or 0) for row in ordered) if feedback_read else None
    )
    payload["feedbackByAccountsNotListed"] = (
        max(0, int(payload["feedbackFiledTotal"]) - int(payload["feedbackAttributed"]))
        if feedback_read
        else None
    )
    payload["feedbackNote"] = (
        (
            "Suggestion counts cover the workshops read for this register only. "
            "feedbackByAccountsNotListed is feedback on those workshops filed by accounts this "
            "list does not name: anyone holding no inspector row on them, and the administrator "
            "accounts this register never lists. It is a measured figure and is usually zero."
        )
        if feedback_read
        else (
            "Inspection feedback could not be counted for this request. The columns are empty "
            "because nothing was read, not because nothing was filed."
        )
    )
    return payload
