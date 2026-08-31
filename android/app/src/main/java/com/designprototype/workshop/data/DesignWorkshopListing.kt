package com.designprototype.workshop.data

import kotlinx.coroutines.CancellationException

/**
 * Reading the WHOLE of the design-workshop list a designer is entitled to, not the first page of it.
 *
 * ── THE FAILURE THIS ENDS ──────────────────────────────────────────────────────────────────────────
 *
 * `WorkshopListScreen` asked for `pageSize = 100` in a single request and used `items` — throwing
 * `total` away. That reads as "ask for everything", and it is not: `backend/app/services/pagination.py`
 * declares `MAX_PAGE_SIZE = 100` and `normalize_pagination` CLAMPS SILENTLY, so a client asking for
 * 500 is served 100 and told `pageSize: 100, pages: 2` in a field it never looked at. One hundred is
 * not a generous ceiling the client chose; it is the hard ceiling of one request, and paging is the
 * only way past it.
 *
 * That would be an ordinary long-list limitation were it not for WHO it hides. An admin can now put a
 * second designer on a workshop (`DesignWorkshopViewer`), and `GET /design-workshops` AND-composes
 * `visible_to_clause` — `createdById` OR a viewer grant — into its `where` for every non-admin. So a
 * grant WIDENS this list, while the rows stay ordered `createdAt desc`. A grant is issued against a
 * workshop that ALREADY EXISTS: the colleague who joins mid-season, the handover from a designer who
 * left. It is therefore older than the workshops the grantee has started themselves, which is exactly
 * the position in a newest-first ordering that falls off the end of page one.
 *
 * Verified against the running API rather than reasoned about: signed in as designer@example.org with
 * one viewer grant, `GET /design-workshops?pageSize=100` answers `total: 120`, and the granted
 * workshop is the 119th row — page 2, index 19. The single-page client showed the designer 100 rows,
 * every one of them their own, and not the one they were let into. The server said yes and the phone
 * said the workshop does not exist, which is the precise failure
 * `services/design_workshop_viewers.py` warns about: "a colleague whose grant the list does not
 * honour is simultaneously told the workshop exists and that it does not."
 *
 * ── WHY THE WALK IS BOUNDED, AND WHY IT COSTS NOTHING IN THE ORDINARY CASE ─────────────────────────
 *
 * [DW_LIST_MAX_PAGES] stops the walk whatever the server reports. A designer with fewer than 100
 * visible workshops — every designer, most of the time — fires exactly ONE request, as before: the
 * second page is asked for only when the server has already said there is one. An unbounded walk on a
 * handset with one bar of signal is how a list screen turns into a five-minute stall, and this repo's
 * premise is that the field connection is the constraint.
 *
 * When the walk stops short, [DesignWorkshopListing.truncated] says so and the screen prints it.
 * Silently showing a prefix of the list is what caused this defect in the first place.
 */

/**
 * Rows per request. The server's own ceiling (`MAX_PAGE_SIZE`), so asking for more buys nothing —
 * it is clamped, and the clamp is invisible except in the `pageSize` the answer echoes back.
 */
const val DW_LIST_PAGE_SIZE = 100

/**
 * How many pages the walk will ever ask for: 500 workshops.
 *
 * Far past any real designer's caseload even with grants, and deliberately a number rather than "keep
 * going until the server stops": the cap is what guarantees this terminates on a server whose `total`
 * disagrees with what it can actually serve, and it bounds the worst case a designer can hit on a
 * metered connection to five requests.
 */
const val DW_LIST_MAX_PAGES = 5

/**
 * The design workshops this account may open, gathered across as many pages as it took.
 *
 * @property total what the SERVER said it holds for this account, kept even when [items] is shorter —
 *   the difference between the two is the whole point of [truncated].
 * @property truncated the walk ended before it had covered [total]. Advisory, never blocking: the
 *   rows gathered are real and the screen still works, the designer is simply told the list is a
 *   prefix rather than being left to infer it from a workshop that is not there.
 */
data class DesignWorkshopListing(
    val items: List<DesignWorkshopDto>,
    val total: Int,
    val truncated: Boolean,
)

/**
 * The page-by-page policy, with no IO in it: fold each answered page in and it says which page to ask
 * for next, or that the walk is over.
 *
 * Separated from the request loop so that every decision here — when to stop, what counts as a
 * duplicate, whether the result is short — is reachable from a plain JVM test with no network, no
 * coroutine dispatcher and no Retrofit. The loop that drives it ([walkDesignWorkshopPages]) is then
 * small enough to read as obviously correct.
 */
class DesignWorkshopPageWalk(private val maxPages: Int = DW_LIST_MAX_PAGES) {

    /**
     * Rows in the order the server sent them, keyed by id.
     *
     * A MAP AND NOT A LIST, because offset paging over a live table repeats rows. The list is ordered
     * `createdAt desc`, so a workshop created between the request for page 1 and the request for page
     * 2 shifts every row down by one and page 2 re-sends the row that was last on page 1. Appending
     * blindly renders that workshop twice, and two identical cards on a list whose whole job is
     * telling a designer what is and is not on the phone is worse than the row being missing — one of
     * the two may carry a local draft and the other not, so they would show different sync states for
     * the same workshop. First sighting wins; a later page never overwrites an earlier one.
     *
     * The opposite skew — a workshop soft-deleted mid-walk shifts rows UP and one row is never served
     * on any page — cannot be repaired from the client. It is why [truncated] is derived from the
     * count rather than from the page cap alone: a walk that came back short for ANY reason says so.
     */
    private val ordered = LinkedHashMap<String, DesignWorkshopDto>()
    private var reportedTotal = 0
    private var pagesFetched = 0
    private var done = false

    /** Rows gathered so far, first sighting first. */
    val items: List<DesignWorkshopDto> get() = ordered.values.toList()

    /** What the server last said it holds for this account. */
    val total: Int get() = reportedTotal

    /** The walk stopped before it had gathered [total] rows. */
    val truncated: Boolean get() = ordered.size < reportedTotal

    /** The listing as it now stands, whether the walk finished or was cut short by a dead connection. */
    fun listing(): DesignWorkshopListing =
        DesignWorkshopListing(items = items, total = reportedTotal, truncated = truncated)

    /**
     * Fold one answered page in.
     *
     * @return the page number to request next, or null when there is nothing left worth asking for.
     */
    fun accept(page: DesignWorkshopPageDto): Int? {
        if (done) return null
        pagesFetched++
        // The server's count, not a running maximum: it is the authority on how many rows this
        // account may see, and it is re-read on every page so a grant issued mid-walk is reflected.
        reportedTotal = page.total
        page.items.forEach { row ->
            // A blank id cannot be opened, cannot be de-duplicated against, and would collide with
            // every other blank one into a single phantom row.
            if (row.id.isNotBlank() && !ordered.containsKey(row.id)) ordered[row.id] = row
        }

        // An empty page is the end of the data whatever `total` claims. Without this a server that
        // over-reports — a row soft-deleted between the count and the fetch — would be asked for the
        // same empty page until the cap, spending four pointless requests of a designer's data
        // allowance to gather nothing.
        if (page.items.isEmpty()) return stop()
        if (ordered.size >= reportedTotal) return stop()
        if (pagesFetched >= maxPages) return stop()
        return pagesFetched + 1
    }

    /**
     * End the walk here — the connection died, or the caller has decided to stop.
     *
     * Keeping what was already gathered rather than discarding it is the edge-first half of this: a
     * designer whose signal drops during page 3 gets the 200 workshops that did arrive, marked as a
     * partial list, instead of an empty screen and an error about a list that was two-thirds read.
     */
    fun stop(): Int? {
        done = true
        return null
    }
}

/**
 * Every design workshop this account may open, across as many pages as the server reports.
 *
 * [fetch] is the only IO, so this is the whole of the request loop: `page 1` is always asked for and
 * anything beyond it only when [DesignWorkshopPageWalk] says the server has more.
 *
 * FAILURE IS ASYMMETRIC ON PURPOSE. A failure on the FIRST page is rethrown — the screen needs to know
 * it is offline so it can say so and fall back to the drafts on the device. A failure on any LATER
 * page is swallowed and the walk ends: something was already read, and throwing it away would turn a
 * connection that dropped after 100 rows into the same blank screen as no connection at all. The
 * result is then short of `total`, so [DesignWorkshopListing.truncated] is already true and the
 * designer is told.
 */
suspend fun walkDesignWorkshopPages(
    maxPages: Int = DW_LIST_MAX_PAGES,
    fetch: suspend (page: Int, pageSize: Int) -> DesignWorkshopPageDto,
): DesignWorkshopListing {
    val walk = DesignWorkshopPageWalk(maxPages)
    var next: Int? = 1
    var first = true
    while (next != null) {
        val answered = if (first) {
            fetch(next, DW_LIST_PAGE_SIZE)
        } else {
            try {
                fetch(next, DW_LIST_PAGE_SIZE)
            } catch (cancelled: CancellationException) {
                // NEVER swallowed, and this is the reason the arm is a `try` rather than the
                // `runCatching` it was first written as: the list screen's effect is keyed on the
                // search box, so every keystroke CANCELS the walk in flight. `runCatching` catches
                // Throwable, so a cancelled walk would have been reported as a successful partial
                // listing — the abandoned keystroke's 100 rows would render, and the banner would
                // tell the designer their list was truncated when nothing had gone wrong at all.
                throw cancelled
            } catch (offline: Throwable) {
                walk.stop()
                return walk.listing()
            }
        }
        first = false
        next = walk.accept(answered)
    }
    return walk.listing()
}

/**
 * The design workshops this account may open — its own AND the ones an admin has granted it — for the
 * list screen.
 *
 * `mineOnly` is deliberately NOT passed, and must never be: it is the server's explicit narrowing to
 * `createdById` and it "means OWN, so it deliberately excludes the granted ones" (see the comment on
 * the list endpoint in `api/routes/design_workshops.py`). Omitting it is what puts the request in the
 * server's `elif not is_admin` arm, where `visible_to_clause` applies and a grant counts.
 */
suspend fun WorkshopRepository.visibleDesignWorkshops(
    search: String? = null,
    /**
     * Narrow to one `WORKSHOP_KIND`, or null for every type.
     *
     * ON THE SERVER AND NOT OVER THE GATHERED ROWS, and that is the whole reason it is a parameter
     * here rather than a `filter` at the call site. This walk stops at [DW_LIST_MAX_PAGES], so a
     * client-side filter would search only the pages that happened to be fetched and answer "no
     * Skill Upgradation workshops" about a repository full of them — absence read as non-existence,
     * over a list the filter could never reach. `total` also comes back narrowed, so the "Showing
     * N of M" sentence counts the workshops of the chosen type instead of counting all of them and
     * looking wrong beside a shorter list.
     */
    workshopKind: String? = null,
): DesignWorkshopListing =
    walkDesignWorkshopPages { page, pageSize ->
        designWorkshops(page = page, pageSize = pageSize, search = search, workshopKind = workshopKind)
    }
