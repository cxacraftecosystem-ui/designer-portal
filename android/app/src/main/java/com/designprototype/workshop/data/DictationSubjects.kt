package com.designprototype.workshop.data

fun interviewAnswersToSend(
    questionIds: List<String>,
    current: (String) -> String?,
    initial: Map<String, String?>,
): List<Pair<String, String>> = questionIds.mapNotNull { id ->
    val now = current(id)?.trim().orEmpty()
    val before = initial[id]?.trim().orEmpty()
    if (now.isNotBlank() && now != before) id to now else null
}

data class DictationSubjects(val interviewId: String?, val artisanIds: String?)

fun dictationSubjects(interviewId: String?, artisanIds: Collection<String>): DictationSubjects {
    val id = interviewId?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("tmp-") }
    val ids = artisanIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    return DictationSubjects(id, ids.takeIf { it.isNotEmpty() }?.joinToString(","))
}
