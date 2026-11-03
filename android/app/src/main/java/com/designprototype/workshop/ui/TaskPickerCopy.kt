package com.designprototype.workshop.ui

fun taskPickerCutNotice(
    truncated: Boolean,
    noun: String,
    term: String,
    localFilter: Boolean,
): String? {
    if (!truncated) return null
    val typed = term.trim()
    if (typed.isNotEmpty()) {
        return "Too many matches for “$typed” — narrow the search."
    }
    val head = "Too many $noun to list — search for a name above."
    return if (localFilter) {
        "$head The box here only filters what is already listed."
    } else {
        head
    }
}
