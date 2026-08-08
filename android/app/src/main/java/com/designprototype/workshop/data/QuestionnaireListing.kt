package com.designprototype.workshop.data

import kotlinx.coroutines.CancellationException

/**
 * Reading the WHOLE of the questionnaire list a designer is entitled to, not the first page of it.
 *
 * ── THE FAILURE THIS ENDS ──────────────────────────────────────────────────────────────────────────
 *
 * `QuestionnaireListScreen` asked for `pageSize = 50` in a single request and used `items`, throwing
 * `total` away. That is the SAME defect `DesignWorkshopListing.kt` was written to end, left standing
 * one screen over — and the questionnaire list is where it does the most damage, because it is the
 * screen on which a granted co-designer goes looking for the form attached to the workshop they were
 * just let into.
 *
 * `GET /questionnaires` orders `createdAt desc` and, for a non-admin, AND-composes
 * `_visible_questionnaire_where` — the designer's OWN forms, plus the forms attached to a design
 * workshop they work on. A grant is issued against a workshop that ALREADY EXISTS: the colleague who
 * joins mid-season, the handover from a designer who left. The form hanging off it was uploaded by
 * somebody else, before the grant, so in a newest-first ordering IT IS ALWAYS OLDER THAN EVERYTHING
 * THE GRANTEE HAS BUILT THEMSELVES. The one row a grant exists to reveal is therefore the row that
 * sorts last, which is precisely the position a single-page read cannot reach.
 *
 * Verified against the running API rather than reasoned about. Signed in as designer@example.org with
 * a `DesignWorkshopViewer` grant on cmsik2jg8000eh8xc1lcy661a:
 *
 *     GET /api/questionnaires?pageSize=100  ->  total 3, ordered createdAt desc
 *       [0] oracle-probe        2026-08-08T01:39:26  owner = designer@example.org
 *       [1] oracle-real         2026-08-08T01:39:14  owner = designer@example.org
 *       [2] Shape probe renamed 2026-08-07T13:49:54  owner = A THIRD ACCOUNT, attached to the granted
 *                                                    workshop — LAST, and the only row the grant added
 *     GET /api/questionnaires?pageSize=100&mineOnly=true -> total 2, that row absent
 *
 * Three rows do not overflow a window of fifty; the ORDERING is the defect, and the window is when it
 * becomes visible. A designer with fifty forms of their own — a season's fieldwork — is shown fifty of
 * their own and not their colleague's, and concludes the form was never uploaded. The server said yes
 * and the phone said the questionnaire does not exist: the failure
 * `services/design_workshop_viewers.py` warns about, reached by a different road.
 *
 * `mineOnly` is deliberately NOT passed, and must never be. The server's comment on it is explicit —
 * "an explicit 'mine' means MINE — the ones this designer uploaded — and must not quietly include a
 * colleague's form that happens to hang off a shared workshop". It asks about AUTHORSHIP, not about
 * what may be read, and passing it is exactly how this list would re-hide the granted row.
 *
 * ── WHY A GENERIC WALK, AND WHY THE PROVEN ONE IS LEFT ALONE ───────────────────────────────────────
 *
 * The paging POLICY here is the one [DesignWorkshopPageWalk] already carries: first sighting wins so a
 * row repeated across pages renders once, an empty page ends the walk whatever `total` claimed, the
 * walk is capped, and a short result says so. That policy is stated once, generically, rather than
 * transcribed — every list in this app that a grant can widen has the same four hazards, and a second
 * hand-written copy of four rules is a copy that drifts.
 *
 * [walkDesignWorkshopPages] is NOT rewired onto this. It is proven and pinned by
 * `DesignWorkshopListingTest`, and rewriting working, tested code underneath a concurrently-edited
 * tree buys nothing a test cannot buy more cheaply. Instead `QuestionnaireListingTest` drives the
 * SAME case table through both implementations and diffs them, so the two cannot disagree without a
 * named test failure — the house rule for a ported rule, applied to a rule ported inside one language.
 *
 * The bounds are deliberately the design-workshop list's own ([DW_LIST_PAGE_SIZE],
 * [DW_LIST_MAX_PAGES]). Two lists in one app that read to different depths are two different answers
 * to "what may this account see", and the designer has no way to tell which screen is lying.
 */

/**
 * A list gathered across as many pages as it took.
 *
 * @property total what the SERVER said it holds for this account, kept even when [items] is shorter —
 *   the difference between the two is the whole point of [truncated].
 * @property truncated the walk ended before it had covered [total]. Advisory, never blocking: the rows
 *   gathered are real and the screen still works, the designer is simply TOLD the list is a prefix
 *   rather than being left to infer it from a questionnaire that is not there. Showing a prefix in
 *   silence is what caused this defect in the first place.
 */
data class PagedListing<T>(
    val items: List<T>,
    val total: Int,
    val truncated: Boolean,
)

/**
 * The page-by-page policy, with no IO in it: fold each answered page in and it says which page to ask
 * for next, or that the walk is over.
 *
 * Separated from the request loop so every decision — when to stop, what counts as a duplicate,
 * whether the result is short — is reachable from a plain JVM test with no network, no coroutine
 * dispatcher and no Retrofit.
 *
 * @param idOf the row's identity for de-duplication. A row whose id is blank is DROPPED: it cannot be
 *   opened, cannot be told apart from any other blank one, and would collapse every such row into a
 *   single phantom entry.
 */
class PagedWalk<T>(
    private val maxPages: Int = DW_LIST_MAX_PAGES,
    private val idOf: (T) -> String,
) {

    /**
     * Rows in the order the server sent them, keyed by id.
     *
     * A MAP AND NOT A LIST, because offset paging over a live table repeats rows. The list is ordered
     * `createdAt desc`, so a questionnaire uploaded between the request for page 1 and the request for
     * page 2 shifts every row down by one and page 2 re-sends the row that was last on page 1.
     * Appending blindly renders that questionnaire twice — two identical cards, and a designer cannot
     * tell which of them holds the sitting they recorded this morning.
     *
     * The opposite skew — a questionnaire deactivated mid-walk shifts rows UP and one row is served on
     * no page at all — cannot be repaired from the client. It is why [truncated] is derived from the
     * COUNT rather than from the page cap alone: a walk that came back short for ANY reason says so.
     */
    private val ordered = LinkedHashMap<String, T>()
    private var reportedTotal = 0
    private var pagesFetched = 0
    private var done = false

    /** Rows gathered so far, first sighting first. */
    val items: List<T> get() = ordered.values.toList()

    /** What the server last said it holds for this account. */
    val total: Int get() = reportedTotal

    /** The walk stopped before it had gathered [total] rows. */
    val truncated: Boolean get() = ordered.size < reportedTotal

    /** The listing as it now stands, whether the walk finished or was cut short by a dead connection. */
    fun listing(): PagedListing<T> =
        PagedListing(items = items, total = reportedTotal, truncated = truncated)

    /**
     * Fold one answered page in.
     *
     * @return the page number to request next, or null when there is nothing left worth asking for.
     */
    fun accept(page: PageResponse<T>): Int? {
        if (done) return null
        pagesFetched++
        // The server's count, not a running maximum: it is the authority on how many rows this account
        // may see, and it is re-read on every page so a grant issued mid-walk is reflected.
        reportedTotal = page.total
        page.items.forEach { row ->
            val id = idOf(row)
            if (id.isNotBlank() && !ordered.containsKey(id)) ordered[id] = row
        }

        // An empty page is the end of the data whatever `total` claims. Without this a server that
        // over-reports — a row deactivated between the count and the fetch — would be asked for the
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
     * designer whose signal drops during page 3 gets the rows that did arrive, marked as a partial
     * list, instead of an empty screen and an error about a list that was two-thirds read.
     */
    fun stop(): Int? {
        done = true
        return null
    }
}

/**
 * Every row this account may see, across as many pages as the server reports.
 *
 * [fetch] is the only IO, so this is the whole of the request loop: `page 1` is always asked for and
 * anything beyond it only when [PagedWalk] says the server has more. A designer under the ceiling
 * therefore pays exactly ONE request, as before — the second page is asked for only when the server
 * has already said it exists.
 *
 * FAILURE IS ASYMMETRIC ON PURPOSE. A failure on the FIRST page is rethrown — the screen needs to know
 * it is offline so it can say so, and for questionnaires it must say so loudly, because a questionnaire
 * lives ONLY on the server and an empty list is otherwise indistinguishable from "you have not built
 * one yet". A failure on any LATER page is swallowed and the walk ends: something was already read,
 * and throwing it away would turn a connection that dropped after 100 rows into the same blank screen
 * as no connection at all. The result is then short of `total`, so [PagedListing.truncated] is already
 * true and the designer is told.
 */
suspend fun <T> walkPagedListing(
    maxPages: Int = DW_LIST_MAX_PAGES,
    pageSize: Int = DW_LIST_PAGE_SIZE,
    idOf: (T) -> String,
    fetch: suspend (page: Int, pageSize: Int) -> PageResponse<T>,
): PagedListing<T> {
    val walk = PagedWalk(maxPages, idOf)
    var next: Int? = 1
    var first = true
    while (next != null) {
        val answered = if (first) {
            fetch(next, pageSize)
        } else {
            try {
                fetch(next, pageSize)
            } catch (cancelled: CancellationException) {
                // NEVER swallowed. The list screen's effect is keyed on the search box, so every
                // keystroke CANCELS the walk in flight. Caught like a network failure, an abandoned
                // keystroke's rows would render and the banner would tell the designer their list was
                // truncated when nothing had gone wrong at all.
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
 * The questionnaires this account may open — its own AND the ones attached to a design workshop an
 * admin has granted it — for the list screen.
 *
 * `mineOnly` is deliberately not passed; see the header. `activeOnly` is passed straight through
 * because it is the designer's own visible switch ("Show deactivated"), not a scope decision.
 */
suspend fun WorkshopRepository.visibleQuestionnaires(
    search: String? = null,
    activeOnly: Boolean = true,
): PagedListing<CustomQuestionnaireSummaryDto> =
    walkPagedListing(idOf = { it.id }) { page, pageSize ->
        customQuestionnaires(
            page = page,
            pageSize = pageSize,
            search = search,
            activeOnly = activeOnly,
        )
    }
