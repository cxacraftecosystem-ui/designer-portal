package com.designprototype.workshop.ui

/**
 * **WHAT TO SAY WHEN A PICKER'S LIST WAS CUT — AND THE ONE THING NEVER TO SAY.**
 *
 * The handset's port of `flagCutNotice` in `frontend/components/data/cappedList.ts`, for the three
 * capped lists `GET /tasks/options` serves.
 *
 * ── THE FAILURE THIS CLOSES ──────────────────────────────────────────────────────────────────────
 *
 * `task_options` reads the first 500 accounts, 200 workshops and 500 artisans and returns whatever
 * fell inside. On this repository's own measured population — 3632 accounts and 731 artisans
 * (`docs/OPEN_FINDINGS.md`, 2026-08-13) — two of those cuts are LIVE, so an admin looking for a
 * colleague whose name sorts late in the alphabet was shown exactly what they would be shown for a
 * colleague who has no account at all: an empty picker and no explanation. That is the same
 * "hidden from you versus nobody matched" failure the design-workshop viewer picker already cost
 * this repository once.
 *
 * ── WHY TWO BRANCHES, AND WHY THE WORDING IS NOT INTERCHANGEABLE ─────────────────────────────────
 *
 * With NOTHING typed, the reader has been handed a slice of the roster and must be told which box
 * gets past it. With a term typed, the cut is a cut of the MATCHES, and telling somebody to "search"
 * when they have just searched is how a picker teaches its user that searching does not work; the
 * only instruction left that means anything is to narrow what they typed.
 *
 * ── [localFilter] IS NOT DECORATION ──────────────────────────────────────────────────────────────
 *
 * A picker with its own filter box has to be told, in the same breath, that the box does NOT reach
 * past the cut — it filters the array it was handed, which is the same lie one layer down. A picker
 * WITHOUT one must not be told about a box it does not have. On this screen `MultiSelectField` takes
 * `searchable` and `SingleSelectField` has no box at all, so the two cases both really occur and the
 * caller says which it is.
 *
 * ── AND WHY THE TERM PASSED IN MUST BE THE APPLIED ONE ───────────────────────────────────────────
 *
 * The flag is computed by the server for ONE request. Pairing it with a term the admin has typed
 * since would print "more people match “giri”" over a flag that was decided for an empty search.
 * Callers pass the settled term — the one that actually went on the wire — and never the live box.
 */
fun taskPickerCutNotice(
    truncated: Boolean,
    noun: String,
    term: String,
    localFilter: Boolean,
): String? {
    // Not a cut is not a sentence. The flag is defaulted false on the DTO, which is also the shape a
    // deployment that predates the field produces, and "nothing to say" is the only safe reading of
    // an absence — the same guard the web's `flagCutNotice` opens with.
    if (!truncated) return null
    val typed = term.trim()
    if (typed.isNotEmpty()) {
        return "More $noun match “$typed” than this list can hold, so some are not shown — " +
            "narrow the search above."
    }
    val head = "There are more $noun than this list can hold, so some are not shown — " +
        "search for a name in the box above to reach them."
    return if (localFilter) {
        "$head The box inside this picker only filters what is already listed."
    } else {
        head
    }
}
