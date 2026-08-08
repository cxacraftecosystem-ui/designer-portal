"""The cross-workshop comparison, read-only and admin-only.

WHY THIS IS A SEPARATE MODULE from `design_workshops.py`. Everything there is scoped to ONE workshop
and gated by `load_workshop_or_404`, which admits the creator, the granted viewers and admins. This
route is the opposite shape in both respects: it reads every workshop at once, and it is gated on
rank alone. Putting a whole-archive read behind a per-record loader would have meant weakening that
loader, which is the mechanism keeping one designer's fortnight out of another's browser.

THE GATE IS ON THE SERVER, AND `require_admin` IS THE WHOLE OF IT. A cross-workshop view aggregates
data a designer cannot otherwise see: a designer holds their own workshops and whatever an admin has
granted them, and this endpoint would hand them the adoption record of every cluster in the scheme,
including the ones they were refused. This repository has twice shipped a UI guard over an open
endpoint — the link disappeared and the URL stayed open — so the dependency below is the boundary and
`frontend/lib/permissions.ts` merely mirrors it.

NOTHING HERE IS A DOWNLOAD. It returns computed findings, never rows: no workshop title, no designer
name, no free text a follow-up visit recorded. Taking the underlying data out still goes through
/export and /data, which are gated as they always were.

THE READ IS THREE QUERIES, FLAT, AND STAYS THREE AS THE ARCHIVE GROWS. See `_load` below for the
measurements; the shape that matters is that no query is issued per workshop.
"""

from typing import Any

from fastapi import APIRouter, Depends

from app.core.db import db
from app.core.deps import require_admin
from app.services.concurrency import gather_reads
from app.services.workshop_analytics import (
    WorkshopRows,
    analyse_archive,
    archive_findings_payload,
)

router = APIRouter(prefix="/analytics", tags=["analytics"])

#: The entity keys this comparison reads, and nothing else.
#:
#: NAMED EXPLICITLY RATHER THAN FETCHING THE STAGES WHOLE. A workshop's 270 rows are mostly sketches,
#: participants and existing products; the four entities below are ~2% of them. Selecting by entity
#: rather than by stage is what keeps the read proportional to the question instead of to the
#: archive — `MARKET_ANALYSIS_DIRECTION` alone would drag in every SWOT point and price band for a
#: comparison that reads neither.
_FOLLOW_UP = "followUp"
_COST_SHEET = "costSheet"
_OPPORTUNITY = "designOpportunity"
ANALYSED_ENTITIES = (_FOLLOW_UP, _COST_SHEET, _OPPORTUNITY)

#: The most stage rows this will read in one request.
#:
#: A CAP THAT IS ANNOUNCED IS A CAP; A CAP THAT IS SILENT IS A LIE. At the observed shape of the data
#: (61 matching rows against 6,200 entries on the local stack, and roughly 60 per fully-worked
#: workshop) this is about 800 complete workshops' worth of follow-up, cost sheets and design
#: opportunities — well past the 200-report archive this is for. If it is ever hit, `truncated`
#: rides through to the payload and the page says the archive was not read whole, rather than
#: presenting a partial reading as the total.
ROW_CAP = 50_000

#: The most workshop headers fetched for the ids the rows named. One id per contributing workshop, so
#: this can only bind before ROW_CAP does in an archive where every workshop has exactly one row.
HEADER_CAP = 20_000


async def _load() -> tuple[list[WorkshopRows], int, bool]:
    """Every row this comparison reads, and the size of the archive it came from.

    THREE QUERIES, AND DELIBERATELY NOT ONE PER WORKSHOP. The naive shape — list the workshops, then
    fetch each one's stages — grows linearly with the archive, and on a cross-region link at ~750ms a
    round trip it never returns at all. MEASURED on the local dataset (5,924 live workshops, 6,200
    stage entries, 55 of them matching, 39 contributing workshops), warm client:

        count of live workshops             9.6 ms
        stage rows for the three entities  16.9 ms
        headers for the ids those named     9.6 ms
        _load() end to end                 25.7 ms   (the first two are gathered, not sequential)

    Against the same data the per-workshop shape took 242 ms for 39 header reads — 6.2 ms each —
    which extrapolates to ~1.2 s for a 200-workshop archive on a LOCAL database, and to two and a
    half minutes on the production link. The analysis itself is 0.6 ms; every millisecond here is
    the database.

    The middle query is a sequential scan (`EXPLAIN ANALYZE`: 12.1 ms, 6,139 rows discarded) and
    that is the right trade. `DwStageEntry` is indexed on `(designWorkshopId, entityKey)`, whose
    leading column this query deliberately does not constrain, and an index on `entityKey` alone
    would be a write cost on every stage save a designer makes in the field, to speed up a read
    three admins make a month.

    DELETED WORKSHOPS ARE EXCLUDED AT THE WORKSHOP LEVEL, WHICH THE ENTRY FILTER CANNOT DO.
    `delete_design_workshop` is a soft delete that sets `DesignWorkshop.deletedAt` and touches
    nothing else, so every stage entry of a deleted workshop is still live: `deletedAt = null` on the
    entry, and no amount of filtering `DwStageEntry` will find it. On the local stack that is 116
    live entries on deleted workshops, three of them cost sheets that would otherwise have entered
    the price-to-cost ratios. The third query re-asks for the ids WITH `deletedAt: None`, and any row
    whose workshop does not come back is dropped — see the loop at the bottom of this function.
    """
    archive_size, rows = await gather_reads(
        db.designworkshop.count(where={"deletedAt": None}),
        db.dwstageentry.find_many(
            # BOTH `deletedAt` COLUMNS MATTER AND THIS IS ONLY THE FIRST. This one drops rows a
            # designer removed from a collection (`save_stage_data` soft-deletes them so a phone
            # that re-syncs an old draft cannot resurrect them). The workshop's own `deletedAt` is
            # a different column on a different table, and it is handled below.
            where={"deletedAt": None, "entityKey": {"in": list(ANALYSED_ENTITIES)}},
            # ROW_CAP + 1 so the cap can be DETECTED rather than inferred: exactly ROW_CAP rows is
            # ambiguous, one more is not.
            take=ROW_CAP + 1,
            # A STABLE ORDER, because the read is capped. Without one, two requests a minute apart
            # could take two different slices of the same archive and publish two different
            # adoption figures with nothing on either page to explain the difference. `id` is the
            # primary key, so it is total and never changes under a row.
            order={"id": "asc"},
        ),
    )
    truncated = len(rows) > ROW_CAP
    rows = rows[:ROW_CAP]

    workshop_ids = sorted({row.designWorkshopId for row in rows})
    truncated = truncated or len(workshop_ids) > HEADER_CAP
    workshop_ids = workshop_ids[:HEADER_CAP]

    headers = (
        await db.designworkshop.find_many(
            where={"id": {"in": workshop_ids}, "deletedAt": None},
        )
        if workshop_ids
        else []
    )

    # Keyed by id, so the row loop below is a dict lookup rather than a scan per row.
    live = {header.id: header for header in headers}

    grouped: dict[str, dict[str, list[dict[str, Any]]]] = {}
    for row in rows:
        if row.designWorkshopId not in live:
            # The workshop was deleted (or was capped out of the header fetch). Either way this row
            # has no header to attribute it to, and attributing it to nothing would put a deleted
            # workshop's cost sheets into a cluster's ratio.
            continue
        bucket = grouped.setdefault(row.designWorkshopId, {})
        # `data` is Json and arrives as a dict; a row written by a client one release ahead may hold
        # keys the registry does not know, which the analytics module simply does not read.
        payload = row.data if isinstance(row.data, dict) else {}
        bucket.setdefault(row.entityKey, []).append(payload)

    workshops = [
        WorkshopRows(
            workshop_id=workshop_id,
            title=live[workshop_id].title or "",
            # The denormalised cover columns, written only by `promoted_values`. Read from the
            # header rather than from stage 1's rows: they are indexed, they are already loaded, and
            # they are the same values every list and filter in the app groups by — a comparison
            # that grouped by its own reading of stage 1 could disagree with the workshop list about
            # which cluster a workshop belongs to.
            craft=live[workshop_id].craftName or "",
            cluster=live[workshop_id].clusterName or "",
            state=live[workshop_id].state or "",
            follow_ups=tuple(entities.get(_FOLLOW_UP, ())),
            cost_sheets=tuple(entities.get(_COST_SHEET, ())),
            opportunities=tuple(entities.get(_OPPORTUNITY, ())),
        )
        for workshop_id, entities in grouped.items()
    ]
    return workshops, archive_size, truncated


@router.get("/design-workshops")
async def design_workshop_analytics(_: Any = Depends(require_admin)) -> dict[str, Any]:
    """Adoption, survival, cost-to-price and recurring opportunities across the whole archive.

    Read-only by construction: it issues three SELECTs and writes nothing, so there is no state a
    caller can reach through it and no per-workshop authority to check. The admin gate is about
    VISIBILITY, not about mutation.
    """
    workshops, archive_size, truncated = await _load()
    findings = analyse_archive(workshops, workshops_in_archive=archive_size, truncated=truncated)
    return archive_findings_payload(findings)
