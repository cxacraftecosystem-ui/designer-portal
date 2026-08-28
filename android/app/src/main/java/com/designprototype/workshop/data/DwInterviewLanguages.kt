package com.designprototype.workshop.data

/**
 * The languages a questionnaire interview is conducted in — ONE list, read by both surfaces.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY IT IS HOISTED OUT OF THE FORM
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * It lived inline in `QuestionnaireForm`, which was fine while the form was the only place a language
 * could be set. It stopped being the only place on 2026-08-28: the REVIEW editor — the one surface
 * that changes the language of an interview somebody has already saved — still rendered a free text
 * box, so the route that touches an approved record was the route that bypassed the vocabulary.
 *
 * Giving the review editor its own copy of twenty-four strings would be the obvious fix and the wrong
 * one: two lists drift, and the half that drifts is always the one nobody is looking at. `Bodo` added
 * to the form and not to the reviewer would mean a reviewer opening an interview recorded in Bodo and
 * being shown a picker that cannot represent it.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * IT IS THE WEB'S LIST, IN THE SAME ORDER, AND THAT IS A CONTRACT RATHER THAN A COINCIDENCE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `frontend/lib/interviewLanguages.ts` carries the identical twenty-four in the identical order and
 * says so at its own declaration — it took them FROM here when the web's Language box stopped being
 * free text. A researcher moves between the handset and the laptop mid-workshop; a list that differed
 * by one entry between them would produce interviews the other client cannot represent.
 *
 * Hindi leads because it is the working language of most of this programme's clusters; English second
 * because it is the fallback for a mixed sitting; then the scheduled languages. `Other` closes it,
 * and is deliberately a REAL answer rather than an escape hatch — an interview conducted in a
 * language this list does not name is a fact, and recording it as blank would lose it.
 */
val DW_INTERVIEW_LANGUAGES: List<String> = listOf(
    "Hindi", "English", "Bengali", "Marathi", "Telugu", "Tamil", "Gujarati", "Urdu",
    "Kannada", "Odia", "Malayalam", "Punjabi", "Assamese", "Maithili", "Sanskrit",
    "Konkani", "Nepali", "Manipuri (Meitei)", "Bodo", "Dogri", "Kashmiri", "Santali",
    "Sindhi", "Other",
)

/**
 * What the trigger reads while nothing is chosen.
 *
 * THERE IS DELIBERATELY NO "None" OPTION and both call sites pass `includeNone = false`. An
 * unanswered language is the ABSENCE of a value, and an option meaning "no answer" is a thing a
 * researcher can pick on purpose and then cannot tell apart from never having reached the field.
 */
const val DW_INTERVIEW_LANGUAGE_PLACEHOLDER: String = "Select language"

/**
 * The options to draw, given whatever language the record already carries.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE PRESERVE RULE, AND WHY IT IS LOAD-BEARING
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Interviews recorded before this vocabulary existed hold free text — "Hindi/Bhojpuri", "Mewari", a
 * lower-case "hindi". A picker built from the list alone shows NOTHING selected over a record that
 * has an answer, and the first save from that screen writes the blank over it. So a stored value the
 * list does not carry is prepended and stays selectable.
 *
 * THE COMPARISON IS EXACT, deliberately. `equals(ignoreCase = true)` looks kinder and is wrong: it
 * decides that a stored "hindi" IS the option "Hindi", so nothing is prepended, and the dropdown then
 * matches no option at all — because the picker compares values exactly. The control would draw
 * "Select language" over a record that has one, which is the very defect this function exists for.
 *
 * CANONICALISING was rejected outright. Quietly swapping the stored "hindi" for "Hindi" edits a
 * researcher's record on the way past without asking, and the whole point of this function is that
 * nothing rewrites what somebody wrote.
 *
 * @param current the language already on the record, or null/blank for a new one.
 */
fun dwInterviewLanguageOptions(current: String?): List<Pair<String, String>> {
    val base = DW_INTERVIEW_LANGUAGES.map { it to it }
    // Whitespace-only is "not answered", the same as empty — trimmed for the TEST but never for the
    // VALUE: prepending the trimmed form would make the option and the stored string different
    // strings, and the control would go back to matching nothing.
    val existing = current.orEmpty()
    if (existing.isBlank()) return base
    if (DW_INTERVIEW_LANGUAGES.any { it == existing }) return base
    return listOf(existing to existing) + base
}
