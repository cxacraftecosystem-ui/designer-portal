package com.designprototype.workshop.data

/**
 * The answers an interview save sends (H4, 21 Oct 2026): only the ones this person typed or changed,
 * in question order. An untouched answer is never sent, so a second researcher's answers are never
 * re-sent under the saver's name and a shared interview is never refused over them.
 */
fun interviewAnswersToSend(
    questionIds: List<String>,
    current: (String) -> String?,
    initial: Map<String, String?>,
): List<Pair<String, String>> = questionIds.mapNotNull { id ->
    val now = current(id)?.trim().orEmpty()
    val before = initial[id]?.trim().orEmpty()
    if (now.isNotBlank() && now != before) id to now else null
}

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
