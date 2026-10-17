package com.designprototype.workshop.ui

/**
 * The one line the review queue prints about how much of it is on screen. The handset's copy of
 * `queueCutNotice` in `frontend/components/data/cappedList.ts` — the two clients look at one queue.
 *
 * UX audit 2026-10-16, DW-06 / DA-09. This used to say "the queue holds at most 200 of each record
 * type, and the ones behind that limit are the oldest. They appear here as the queue is worked" —
 * an implementation limit, and an admission that the most overdue work could not be reached. The
 * queue now PAGES (`GET /review/pending?pageSize=&before=`, newest first, with a "Load older"
 * button), so the line is a count and nothing else.
 *
 *  1. Everything loaded → null. A standing notice over a complete queue teaches a reviewer to ignore it.
 *  2. More waiting than shown → "Showing N of T waiting, newest first." The button below says how to
 *     reach the rest.
 *  3. Never arithmetic that contradicts itself: no "Showing 200 of 200", no "Showing 0 of 340".
 */
fun reviewQueueCutNotice(truncated: Boolean, shown: Int, total: Int, cap: Int): String? {
    // `cap` is kept in the signature for the older server, which sends it without paging; the
    // sentence no longer prints it, because it described the server rather than the queue.
    if (!truncated) return null
    if (shown == 0) return "Couldn't list the $total waiting records. Try again."
    if (total <= shown) return "More waiting — Load older to see them."
    return "$shown of $total waiting, newest first."
}

/** How many records the queue asks for per page, on both clients. */
const val REVIEW_QUEUE_PAGE_SIZE = 50
