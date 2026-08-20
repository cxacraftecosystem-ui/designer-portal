package com.designprototype.workshop.ui.designworkshop

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.designprototype.workshop.data.ConnectivityObserver
import com.designprototype.workshop.data.DwAiVerbAllowanceStore
import com.designprototype.workshop.data.DwAiVerbCapView
import com.designprototype.workshop.data.DwDictationRun
import com.designprototype.workshop.data.DwTier3Consent
import com.designprototype.workshop.data.DwVerbConditions
import com.designprototype.workshop.data.DwVerbGate
import com.designprototype.workshop.data.TokenStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.dwAiVerbCapView
import com.designprototype.workshop.data.dwDictationIstDay
import com.designprototype.workshop.data.dwVerbGate

/**
 * WHAT EVERY VERB CONTROL ON THIS HANDSET NEEDS BEFORE IT MAY DRAW ITSELF — read once, in one place.
 *
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 * NOTHING HERE DECIDES A RULE. `data/DwAiVerbs.kt` decides every one of them.
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The ladder is [dwVerbGate], the ceiling is [dwAiVerbCapView], the sentences are that file's
 * constants, and each is used rather than re-expressed. What lives here is the Android half those
 * pure functions deliberately refuse to touch: a Context, a SharedPreferences read, the connectivity
 * manager, and the process-wide ambient the stage screen publishes.
 *
 * ── WHY THE FACTS ARE READ FROM `DwDictationRun` AND NOT THREADED IN AS PARAMETERS ──────────────
 *
 * Because a verb control is drawn from two places with different dependencies — the media card
 * inside [FieldRenderer], and [RichTextEditor], whose header states in capitals that it has no data
 * layer and deliberately wants none. That is the same split `DwDictationRun.repository` was written
 * up against, in its own words: threading a parameter through *"would therefore have produced a
 * phone where server dictation works in a short prose field and silently does not in a long one,
 * which is worse than either answer alone."*
 *
 * The stage screen already publishes exactly the pair these controls need —
 * `publishWorkshopConsent(consent, serverWorkshopId)` — for the microphones, and a local-only
 * workshop already publishes a null id there because that is what costs dictation its server rung.
 * The verbs are gated on precisely the same fact, from precisely the same publication, so the two
 * capabilities cannot come to disagree about which workshop this is or what it has agreed to.
 *
 * ── AND WHY THERE IS NO "STILL READING" STATE ON THIS CLIENT ────────────────────────────────────
 *
 * [DwVerbGate.StillReading] exists because the browser reads its consent asynchronously per control,
 * and drawing the floor answer before the read lands flashes "nobody has been asked" over a workshop
 * that has been asked. On this handset the stage screen draws *"Opening the stage…"* and returns
 * before composing a single field, and the publication happens inside the same load — so no field
 * exists while the answer is unknown. [dwVerbSurface] therefore reports `draftRead = true`, and the
 * `when` arms in the surfaces still handle [DwVerbGate.StillReading] by drawing NOTHING, which is
 * the correct behaviour on the day that early return stops being true.
 *
 * **THE ONE CASE WHERE THIS IS NOT WATERTIGHT, STATED RATHER THAN HIDDEN.** If the stage load throws
 * before it reaches the publication, `loading` still clears and the fields still compose, over
 * whatever publication was last made — the default `(null, NOT_RECORDED)`, or the previous stage
 * screen's. Both fail closed: the first refuses everything with the not-on-the-server sentence and
 * the second is the same workshop. That is the existing behaviour of the dictation ladder, inherited
 * deliberately rather than diverged from in one lane.
 */
@Immutable
class DwAiVerbSurface(
    /** The one [WorkshopRepository], or null on a build that composed a field before it existed. */
    val repository: WorkshopRepository?,
    /**
     * The id the SERVER knows this workshop by, or null while it exists only on this phone.
     *
     * **NEVER THE ID THE SCREEN WAS NAVIGATED WITH.** A workshop created in a courtyard keeps its
     * local id in the route for the rest of the session, so the route param would put `local-…` into
     * `/design-workshops/{id}/ai-layers/proofread` and collect a bare 404 — which is what the browser
     * shipped. Normalised to non-blank-or-null by `publishWorkshopConsent`, which argues that case at
     * length: `""` is not null and would put an empty path segment on the wire.
     */
    val serverWorkshopId: String?,
    val consent: DwTier3Consent,
    /**
     * Today, on the SERVER's India-time boundary and never this phone's timezone.
     *
     * It is the day [cap]'s numbers belong to as well as today's date, and those are the same thing
     * by construction: `dwAiVerbCapView` answers "unknown" for a stored row whose day is not this
     * one, so a count and a date shown together can never be about different days.
     */
    val today: String,
    /** The stored ceiling as it stands for this designer, this day. Never "spent" without evidence. */
    val cap: DwAiVerbCapView,
) {
    /**
     * The pre-press ladder for right now. **`online` is asked at the moment this is called**, which
     * is why it is a function and not a stored field: a designer walks out of a courtyard mid-flow,
     * and a boolean captured when a card was drawn would offer a control into a certain failure or
     * withhold one that would work.
     */
    fun gate(context: Context): DwVerbGate = dwVerbGate(
        DwVerbConditions(
            draftRead = true,
            workshopOnServer = !serverWorkshopId.isNullOrBlank(),
            consent = consent,
            online = ConnectivityObserver.isOnline(context),
            capSpent = cap.spent,
            // The server's own ceiling sentence is not carried in the mirror — see
            // `dwAiVerbCapSpentRecord`, which stores the numbers and deliberately not the words,
            // because `cap_refusal` composes today's breakdown into the sentence and a stored copy
            // would be a stale statement about a designer's own afternoon. So a gate refusal off the
            // mirror uses the fallback, and a refusal off a live 429 uses the server's, which is the
            // one place it exists.
            capRefusal = null,
        )
    )
}

/**
 * Read the surface's facts. Cheap enough to call per control and it is called per control.
 *
 * `TokenStore` and `DwAiVerbAllowanceStore` are both SharedPreferences, which is why they can be read
 * during composition at all — the same property `DwDictationAllowance` chose them for: *"synchronous,
 * which is what `conditionsNow()` requires (it is called on the tap, on composition's main thread,
 * and may do no IO worth measuring)."*
 *
 * **`DwDictationRun.publishedWorkshop()` IS A PLAIN VOLATILE FIELD AND NOT COMPOSE STATE**, so a
 * publication that changes does not itself recompose these controls. That is correct rather than
 * tolerated: the stage screen publishes inside the load, before any field is composed, so the first
 * composition already reads the right pair — and every surface re-reads the whole surface at the
 * press, which is what makes a workshop that syncs while a card is open behave correctly.
 *
 * @param runs how many verbs this surface has run. **IT IS NOT DECORATION AND IT IS NOT A COUNTER
 *   ANYBODY DISPLAYS**: the allowance mirror is written by `WorkshopRepository.runVerb` on the way
 *   past, and SharedPreferences is not observable, so without a key that changes when a run finishes
 *   this would hold the number from before it. A countdown that stayed at "3 runs left" after three
 *   more runs is the shape of the browser's own cap defect, arrived at from the other direction —
 *   there by computing it in three places, here by never re-reading it.
 */
@Composable
internal fun dwVerbSurface(runs: Int = 0): DwAiVerbSurface {
    val context = LocalContext.current
    val published = DwDictationRun.publishedWorkshop()
    // Keyed on the published pair AND on the run count, so a stage screen that publishes a different
    // workshop rebuilds this and a finished run re-reads the mirror it just wrote.
    return remember(published.serverId, published.consent, runs) {
        val userId = runCatching { TokenStore(context).getUser()?.id }.getOrNull()
        val today = dwDictationIstDay()
        DwAiVerbSurface(
            repository = DwDictationRun.repository(),
            serverWorkshopId = published.serverId,
            consent = published.consent,
            today = today,
            cap = dwAiVerbCapView(
                stored = DwAiVerbAllowanceStore.read(context, userId),
                userId = userId,
                today = today,
            ),
        )
    }
}
