package com.designprototype.workshop.ui.questionnaires

/**
 * What kind of questionnaire this is — and therefore which stage of the report its answers land in.
 *
 * THE OWNER'S REQUEST, 2026-08-30: *"the designer can have multiple questionnaires for the same
 * workshop as well, they also do market survey interviews, so create that differentiation as well,
 * so that we can map the questionnaires and the transcripts to the correct stage in the report."*
 *
 * Several questionnaires per workshop already worked on both clients — `Questionnaire
 * .designWorkshopId` is one nullable pointer and the far side is a list, so nothing was blocking it.
 * What did not exist was any way to tell the two KINDS apart, so the report's questionnaire annexure
 * printed a baseline interview and a market survey under one heading with nothing saying which part
 * of the workshop either was evidence for.
 *
 * ── THIS IS A MIRROR. THE SERVER OWNS THE LIST ──────────────────────────────────────────────────
 *
 * The original is `backend/app/services/questionnaire_kinds.py`, and the web carries the third copy
 * in `frontend/lib/questionnaireForms.ts`. It is duplicated rather than fetched because a picker has
 * to be drawable before any row exists to label it, and this handset spends its working life in a
 * courtyard with no signal — a round trip for two words is a round trip that does not happen.
 *
 * Nothing compiles the three against each other, which is exactly the drift
 * `backend/tests/test_role_ladder_parity.py` exists for, so
 * `backend/tests/test_questionnaire_kinds.py` reads THIS FILE as text and holds it to the Python.
 * **When that test fails, the Python is the expectation** — find the mirror that lagged.
 *
 * ── WHAT THE SERVER SENDS, AND WHY THIS STILL EXISTS ────────────────────────────────────────────
 *
 * Every payload carrying a `kind` carries a `kindLabel` beside it, so a ROW is always labelled with
 * the server's own words and the two clients cannot word one stored value differently. This map is
 * for the PICKER — the control offering a kind for a questionnaire that does not exist yet — and for
 * a row from a handset that is a release behind. [labelForQuestionnaireKind] falls back to the token
 * itself rather than to "not stated", matching the server's `label_for`: relabelling a value somebody
 * chose as a value nobody chose is the one wrong answer available here.
 */

/** Token -> the label BOTH CLIENTS SHOW, in the order the pickers draw them. */
val QUESTIONNAIRE_KIND_LABELS: Map<String, String> = linkedMapOf(
    "WORKSHOP_INTERVIEW" to "Workshop interview",
    "MARKET_SURVEY" to "Market survey",
)

/**
 * What an unstated kind is called. Never a member of the vocabulary above: a questionnaire nobody
 * has classified has not been filed anywhere, and giving that state a token would make "nobody has
 * said" indistinguishable from "somebody said none of them".
 */
const val QUESTIONNAIRE_KIND_NOT_STATED = "Kind not stated"

/** The label to print for a kind off the wire. Unknown tokens print themselves — see the header. */
fun labelForQuestionnaireKind(kind: String?): String {
    if (kind.isNullOrBlank()) return QUESTIONNAIRE_KIND_NOT_STATED
    return QUESTIONNAIRE_KIND_LABELS[kind] ?: kind
}
