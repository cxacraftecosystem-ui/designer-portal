from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, Query

from app.core.db import db
from app.core.deps import get_current_user
from app.services.concurrency import gather_reads
from app.services.pagination import normalize_pagination
from app.services.record_filters import RECORD_TYPES, build_record_wheres, resolve_types
from app.services.records import media_url_owners, media_url_scope, public_encode, with_id_tiebreak

router = APIRouter(prefix="/search", tags=["search"])

# The five buckets, in the order they are counted, read and returned. Also the order `types` is
# echoed back in, so a client comparing what it asked for against what it got is comparing like
# with like. Re-exported from services/record_filters rather than restated: the map reads the same
# tuple, and two copies of this list is exactly how one screen quietly grows a sixth bucket.
SEARCH_TYPES: tuple[str, ...] = RECORD_TYPES

# Every bucket is ordered by createdAt desc, like every record list in this API — with the ``id``
# tiebreak that makes that order TOTAL.
#
# All five buckets are OFFSET-paged from one shared page/pageSize, and ``createdAt`` is unique in
# none of the five tables. Without a tiebreaker Postgres is free to break a tie differently on the
# query for page 1 and the query for page 2, so a record can be handed over twice while another is
# never handed over at all — and a search result is precisely where nobody would notice, because
# "it did not come back" reads as "it is not in the repository". See ``records.with_id_tiebreak``.
_ORDER = with_id_tiebreak({"createdAt": "desc"})


def _resolve_types(raw: list[str] | None) -> set[str]:
    """The bucket selection, resolved by services/record_filters so the map applies the same rule.

    Kept as a module-level name because the route reads better for it, and because anything that
    already imports it from here keeps working.
    """
    return resolve_types(raw)


@router.get("")
async def global_search(
    current_user: Any = Depends(get_current_user),
    q: str | None = None,
    craftId: str | None = None,
    place: str | None = None,
    artisanId: str | None = None,
    mediaType: str | None = None,
    # Which buckets to search. Repeatable; omitted means all five.
    types: list[str] | None = Query(None),
    # The record time range, as CONCRETE dates. The clients offer presets (today, 7/30/90 days, this
    # month, this year, custom) and resolve them to a from/to pair themselves, deliberately: a preset
    # is a phrase in a UI, and putting phrases in the API would mean a new preset — or a client whose
    # idea of "this month" starts on a different weekday — needs a backend release. Either bound may
    # stand alone, so "everything since the workshop" needs no artificial end date.
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    # The workshop scope. Repeatable or comma-joined ids, plus the reserved value "none" for records
    # that are not linked to a workshop at all; omitted means every workshop. Spelled exactly as
    # ``GET /map/points`` spells it, because both are built from one client-side filter object — a
    # search box that disagreed with the map about what "this workshop" contains would leave no way to
    # tell which of the two was lying.
    workshopIds: list[str] | None = Query(None),
    page: int = Query(1, ge=1),
    pageSize: int = Query(10, ge=1, le=50),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    selected = _resolve_types(types)

    # Every filter below — visibility, free text, craft, place, artisan, media type, date range —
    # is built by services/record_filters, which is also what the map endpoint asks. One vocabulary,
    # one implementation, so the two screens can never answer the same question differently.
    wheres = await build_record_wheres(
        current_user,
        q=q,
        craft_id=craftId,
        place=place,
        artisan_id=artisanId,
        media_type=mediaType,
        date_from=dateFrom,
        date_to=dateTo,
        workshop_ids=workshopIds,
    )
    artisan_where = wheres["artisans"]
    workshop_where = wheres["workshops"]
    product_where = wheres["products"]
    tool_where = wheres["tools"]
    media_where = wheres["media"]

    # One count per bucket, so the client can page properly. The five buckets share one page/pageSize
    # but each has its own length, and without totals a UI can only guess at "is there a next page"
    # by checking whether some bucket happened to fill the page.
    #
    # A bucket `types` excluded is never counted and never read. It reports 0, which keeps it out of
    # `total` and — because `pageCount` is the longest bucket's page count — stops a 500-row bucket
    # nobody asked for from advertising pages that would come back empty.
    #
    # The count and the read for every selected bucket go out TOGETHER. They were sequential, on the
    # reasoning that ten concurrent queries would exhaust the pooler — but that fear dates from when
    # each of two uvicorn workers held a pool of forty. The box now runs one web worker with a pool
    # of ten, and `gather_reads` will not exceed it. Re-measured against production at 4, 8 and 16
    # simultaneous searches: no connection errors at any level, and the gathered version was faster
    # or level every time. An all-bucket search went from 8.25s to 1.43s, because on a cross-region
    # link ten sequential reads is ten times ~750ms of waiting and almost no database work.
    #
    # An unselected bucket contributes NO coroutine — it must not cost a round trip to return 0 —
    # so the reads are collected with their bucket names and zipped back up after the gather.
    planned: list[tuple[str, Any]] = []

    # WHOSE MEDIA BYTES MAY TRAVEL, PLANNED FIRST SO IT RIDES THE SAME WAVE AS THE BUCKETS. This is
    # not a bucket and it reports no total: all five buckets are encoded by the single
    # ``public_encode`` at the bottom of this route, and it is that ONE call that decides which
    # ``url`` values survive, so SOME answer is needed whatever `types` selected.
    #
    # THE TWO HALVES ARE NOT NEEDED ON THE SAME SELECTIONS, WHICH IS WHY THERE ARE TWO CALLS HERE.
    # ``media_urls`` is tested against the ``uploadedById`` of every file in the payload, and the
    # products and tools buckets embed their own media by ``include``, so it is needed even when
    # nobody asked for the media bucket. ``media_workshops`` is tested against a FILE'S OWN
    # ``linkedRecordType``/``linkedRecordId``, and only the ``media`` bucket can put such a node in
    # this payload: products and tools reach media through ``productId``/``toolId``, which
    # ``records.media_relation_data`` writes only from the ``product``/``tool`` link types — that map
    # has no ``designWorkshop`` key, and no column on ``MediaFile`` points at a DesignWorkshop at all
    # (true as of 2026-08-27; check
    # `grep -n "def media_relation_data" -A 15 backend/app/services/records.py`). The only other
    # writer of those two columns is ``POST /media/{id}/relink``, and its ``_relink_delegate`` has no
    # design-workshop entry either, so that type is a 400 before anything is written (true as of
    # 2026-08-27; check `grep -n "def _relink_delegate" -A 30 backend/app/api/routes/media.py`). No
    # row can therefore hold a product or tool foreign key AND the design-workshop tag. The rest of
    # the derivation is written out at the encode below, because that is where the halves are spent.
    #
    # So without the media bucket the workshop read is a round trip that cannot alter one byte of the
    # response, and this route already refuses to pay those — the paragraph above says an unselected
    # bucket must not cost a round trip to return 0, and this is that same rule, one line further on.
    # The narrow shape is not hypothetical: the dashboard totals link straight to
    # ``/search?type=tools``, and the web filter panel sends a canonical comma-joined ``types`` for
    # whatever the researcher ticked.
    #
    # THE UPLOADER HALF STAYS UNCONDITIONAL, INCLUDING ON ``?types=artisans`` WHERE NOTHING IN THE
    # PAYLOAD CAN CARRY A MEDIA NODE. Skipping it there would need a SECOND derivation — "artisan and
    # workshop rows embed no media" — which holds only until someone adds an ``include``, and it would
    # buy that shape nothing anyway: the encode still has to be handed a set, and ``public_encode``'s
    # own default is NARROWER than this answer (the viewer's own uploads, no grants), so the saved
    # read would have to be replaced by a wrong answer rather than by nothing. One conditional, on the
    # one fact this file already had to write down.
    #
    # Free for professor and above either way: both calls short-circuit to "every URL" with no query.
    if "media" in selected:
        planned.append(("media_scope", media_url_scope(current_user)))
    else:
        planned.append(("media_uploaders", media_url_owners(current_user)))

    # THE ARITHMETIC, AND THE GATHER COMES OUT LEVEL RATHER THAN AHEAD. A Prisma hop here is ~750ms
    # and the only number that moves this page is how many run IN SERIES. Before this change the route
    # gathered the buckets (one hop) and then awaited ``media_url_owners`` at the encode (one more):
    # two hops. ``media_url_scope`` is TWO reads run one after the other — the grant table, then the
    # workshops this account may open — so awaiting it at the encode would now cost three, a hop the
    # widest read in the app did not previously pay. Gathered, its FIRST read overlaps the bucket
    # queries and the wave is two hops deep instead of one, which leaves the route exactly where it
    # started. An earlier draft of this comment claimed it came out AHEAD; the arithmetic does not
    # support that, because the two reads are sequential inside ``media_url_scope`` and only the first
    # of them can hide behind the buckets. Where the route DOES come out ahead is the other branch:
    # without the media bucket the lone uploader read overlaps the buckets outright and the whole wave
    # is one hop, against two before. That is the conditional above paying for itself, and it is worth
    # keeping the two claims apart — the gather buys level, dropping a read nobody can observe buys
    # the hop.
    #
    # PLANNED FIRST, AND THAT ORDERING IS LOAD-BEARING RATHER THAN TIDY. ``gather_reads`` bounds
    # itself with a semaphore of ``pool_width()`` — ``DATABASE_CONNECTION_LIMIT``, ten by default
    # (true as of 2026-08-27; check `grep -n database_connection_limit backend/app/core/config.py`) —
    # and admits coroutines in the order it is handed them. An omitted ``types`` means all five
    # buckets, which is ten reads, exactly filling the pool; this one is then the eleventh and there
    # is no slot for it. LAST in the list it would wait for a bucket to return before starting its own
    # two serial reads — three hops, WORSE than the sequential version it replaced, and worst on the
    # default search. FIRST it takes a slot immediately, and the bucket read that queues in its place
    # is a single hop that lands alongside this pair's second one, off the critical path. Keep this
    # plan above the bucket block.
    if "artisans" in selected:
        planned.append(("artisans", db.artisan.count(where=artisan_where)))
        planned.append(
            (
                "artisans_rows",
                db.artisan.find_many(where=artisan_where, skip=skip, take=page_size, order=_ORDER),
            )
        )
    if "workshops" in selected:
        planned.append(("workshops", db.workshop.count(where=workshop_where)))
        planned.append(
            (
                "workshops_rows",
                db.workshop.find_many(
                    where=workshop_where, skip=skip, take=page_size, order=_ORDER
                ),
            )
        )
    if "products" in selected:
        planned.append(("products", db.productdocumentation.count(where=product_where)))
        planned.append(
            (
                "products_rows",
                db.productdocumentation.find_many(
                    where=product_where,
                    include={"media": True},
                    skip=skip,
                    take=page_size,
                    order=_ORDER,
                ),
            )
        )
    if "tools" in selected:
        planned.append(("tools", db.tooldocumentation.count(where=tool_where)))
        planned.append(
            (
                "tools_rows",
                db.tooldocumentation.find_many(
                    where=tool_where,
                    include={"media": True},
                    skip=skip,
                    take=page_size,
                    order=_ORDER,
                ),
            )
        )
    if "media" in selected:
        planned.append(("media", db.mediafile.count(where=media_where)))
        planned.append(
            (
                "media_rows",
                db.mediafile.find_many(where=media_where, skip=skip, take=page_size, order=_ORDER),
            )
        )

    results = dict(
        zip([name for name, _ in planned], await gather_reads(*(coro for _, coro in planned)))
    )

    totals = {name: results.get(name, 0) for name in SEARCH_TYPES}
    # Unpacked into two named locals rather than passed inline, so both halves are visibly SPENT at
    # the encode below. A pair that arrives as one expression is a pair whose second half a later
    # edit can drop without the diff looking wrong, and that is precisely the shape of omission the
    # ``THE FIELDS THAT HAND OVER BYTES`` banner above ``records._MEDIA_URL_KEYS`` warns about.
    #
    # The two plans carry DIFFERENT KEYS so the branch is legible in the results rather than hidden in
    # a shape test on one value, and the empty workshop half is WRITTEN OUT instead of left to
    # ``public_encode``'s default: the half this request deliberately did not pay for should be
    # visible at the place it is spent, not inferred from an argument that is missing.
    if "media_scope" in results:
        media_urls, media_workshops = results["media_scope"]
    else:
        media_urls, media_workshops = results["media_uploaders"], frozenset()
    artisans = results.get("artisans_rows", [])
    workshops = results.get("workshops_rows", [])
    products = results.get("products_rows", [])
    tools = results.get("tools_rows", [])
    media = results.get("media_rows", [])

    # `totals` / `total` / `pageCount` are ADDITIVE: every pre-existing key keeps its name and shape
    # so older clients (and the Android app) are untouched. `types` joins them on the same terms —
    # the RESOLVED set, in bucket order, so a client can show "searching artisans and media" without
    # re-deriving what an omitted parameter meant.
    #
    # BOTH HALVES OF THE MEDIA ANSWER ARE NAMED HERE. Search is the widest read in the app and the
    # media bucket is one of its five, so this is exactly the surface where "everyone may look, taking
    # data out stays earned" has to hold: every media ROW comes back, and the fetchable ``url`` on it
    # comes back only to a caller entitled to those bytes. ``media_workshops`` is empty on a selection
    # that omitted the media bucket, and empty BY DECISION rather than by omission — see the plan
    # above for why nothing in the other four buckets can be tested against it.
    #
    # THIS ROUTE READS THE ``MediaFile`` TABLE ITSELF, WHICH IS WHY IT NEEDS THE SECOND HALF AND THE
    # RECORD ROUTES DO NOT — and, one step further, why the second read is planned only when the media
    # bucket was selected. ``products``, ``tools`` and ``processes`` reach media through a parent
    # foreign key that ``records.media_relation_data`` writes FROM the link type, so the tag on
    # anything they return is the parent's own. Nothing narrows this bucket that way:
    # ``record_filters`` builds ``wheres["media"]`` from ``viewable_where(…, "uploadedById")``, which
    # is empty by design (reading the repository is open to every signed-in account), plus free text,
    # media type, date range and workshop scope. A design-workshop attachment carries
    # ``linkedRecordType="designWorkshop"`` and the workshop id and NO parent foreign key at all
    # (``dictation_consent.MEDIA_TAG`` — there is no column on MediaFile pointing at a
    # DesignWorkshop), so it is an ordinary row of that table and it comes back to anybody who
    # searches.
    #
    # THE DEFECT THAT CLOSES HERE. With the uploader set as the only test, a co-designer searching
    # their own workshop was shown the row and refused the ``url`` for a recording the same account
    # can already play through ``GET /design-workshops/{id}/transcripts``, take through ``/export``
    # and ``/data``, read as text off stage 8, and see printed into the report they generate
    # themselves. Five surfaces handed the bytes over and this one said no, which does not protect
    # the file — it just makes search look broken to the one person the workshop grant exists for.
    #
    # WHICH HALF DOES THE WIDENING MATTERS MORE ON THIS ROUTE THAN ANYWHERE ELSE. ``media_workshops``
    # is a set of WORKSHOP ids tested against the FILE's own tag columns; it is not extra uploaders
    # folded into ``media_urls`` (see ``records.media_url_scope`` for why the two cannot be one set).
    # The same ``media_urls`` is applied to the artisan, workshop, product and tool buckets in this
    # very payload, none of which has a workshop in it, so widening the uploader set for one
    # workshop's sake would hand over every file that uploader has ever attached to anything, in four
    # other buckets, to anyone who typed a query.
    return public_encode(
        {
            "query": q,
            "page": page,
            "pageSize": page_size,
            "types": [name for name in SEARCH_TYPES if name in selected],
            "artisans": artisans,
            "workshops": workshops,
            "products": products,
            "tools": tools,
            "media": media,
            "totals": totals,
            "total": sum(totals.values()),
            # The pager walks all five buckets at once, so the last page is the last page of the
            # LONGEST bucket; at least 1 so an empty result still reads as "page 1 of 1".
            "pageCount": max(1, (max(totals.values()) + page_size - 1) // page_size),
        },
        current_user,
        media_urls=media_urls,
        media_workshops=media_workshops,
    )
