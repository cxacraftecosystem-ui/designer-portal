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
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status

from app.core.db import db
from app.core.deps import (
    MINISTRY_DASHBOARD_REFUSAL,
    can_download_dataset,
    can_see_ministry_dashboard,
    get_current_user,
)
from app.services import ministry_dashboard as register
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
