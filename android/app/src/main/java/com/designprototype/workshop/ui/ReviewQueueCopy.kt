package com.designprototype.workshop.ui

/**
 * **WHAT TO SAY WHEN THE REVIEW QUEUE WAS CUT — AND THE TWO THINGS NEVER TO SAY.**
 *
 * The handset's port of `queueCutNotice` in `frontend/components/data/cappedList.ts`, exactly as
 * [taskPickerCutNotice] is the port of that file's `flagCutNotice`. Same file, same argument: one
 * decider per shape of cut, so two clients cannot describe one cut in two sentences and teach a
 * reviewer that neither of them means much.
 *
 * ── THE FAILURE THIS CLOSES ──────────────────────────────────────────────────────────────────────
 *
 * `GET /review/pending` reads at most `cap` rows of each of six record types and orders each source
 * `createdAt desc`, so the rows behind the cap are the OLDEST — the most overdue work is what goes
 * missing. The server has said so on the wire for some time; this screen threw the answer away and
 * kept only the list, so a reviewer with 340 pending artisans and a reviewer with exactly 200 saw
 * byte-identical screens. The only way to discover the backlog was that the number refused to go
 * down as the queue was worked.
 *
 * ── THE TWO SENTENCES THAT WOULD BE WRONG HERE ──────────────────────────────────────────────────
 *
 * NOT "narrow your search": this route takes no search parameter at all, so a reviewer told to
 * narrow the list has nothing to narrow, and the closed viewer-picker finding
 * (`docs/OPEN_FINDINGS.md`, 2026-08-13) is on record for what that costs. NOT "page on to reach the
 * rest" either — nothing on either client pages this route; the rows past the cap were never sent.
 *
 * What IS true, and is an instruction a reviewer can act on, is that deciding the records on screen
 * is what brings the older ones forward.
 *
 * ── WHY [cap] IS PRINTED AND NOT ASSUMED ────────────────────────────────────────────────────────
 *
 * It is the server's number (`review.PENDING_TAKE`) and it arrives on the wire beside the flag, so
 * it is read rather than repeated here — a stated cap that is not the enforced cap is worse than no
 * sentence at all. A server that predates the key sends 0; see the guard below.
 */
fun reviewQueueCutNotice(truncated: Boolean, shown: Int, total: Int, cap: Int): String? {
    // Not a cut is not a sentence — the same guard, for the same reason, that `flagCutNotice` and
    // [taskPickerCutNotice] open with: the flag is defaulted false on the DTO, which is also the
    // shape a deployment predating the field produces, and "nothing to say" is the only safe
    // reading of an absence.
    if (!truncated) return null
    // Arithmetic that would read as a contradiction is not printed. `total <= shown` cannot happen
    // beside a true flag from a server that sends both, and `cap <= 0` is what a server predating
    // the key sends; in either case the honest fallback is the fact without the numbers rather than
    // "Showing 200 of 200" or "at most 0 of each".
    if (total <= shown || cap <= 0) {
        return "Some pending records are not shown — the queue holds a limited number of each " +
            "record type, and the ones behind that limit are the oldest."
    }
    // The server states that a cut answer can never carry an EMPTY list — the cut is by count
    // alone, so a cut answer holds `cap` rows by construction. Handled anyway, because a reader
    // meeting "Showing 0 of 340" followed by a cap sentence would be reading a contradiction.
    if (shown == 0) {
        return "None of the $total pending records could be listed here — this is not an empty queue."
    }
    return "Showing $shown of $total pending records — the queue holds at most $cap of each " +
        "record type, and the ones behind that limit are the oldest. They appear here as the " +
        "queue is worked."
}
