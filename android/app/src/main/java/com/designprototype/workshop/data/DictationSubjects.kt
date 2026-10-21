package com.designprototype.workshop.data

/**
 * Who an interview dictation is about, as the two optional multipart parts of
 * `POST /design-workshops/{id}/dictate` (H3, 21 Oct 2026).
 *
 * The workshop's consent covers only the artisans linked to that workshop. An interviewed artisan
 * who refused, but is filed elsewhere, must stop the send as well, so the interview form names its
 * saved interview and every artisan currently selected. Blank values are dropped; nothing named
 * means both parts are left off, exactly as before.
 */
data class DictationSubjects(val interviewId: String?, val artisanIds: String?)

fun dictationSubjects(interviewId: String?, artisanIds: Collection<String>): DictationSubjects {
    val id = interviewId?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("tmp-") }
    val ids = artisanIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    return DictationSubjects(id, ids.takeIf { it.isNotEmpty() }?.joinToString(","))
}
