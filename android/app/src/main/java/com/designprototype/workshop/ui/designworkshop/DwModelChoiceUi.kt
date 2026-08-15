package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwConnection
import com.designprototype.workshop.data.DwDeviceMeasurement
import com.designprototype.workshop.data.DwModelChoice
import com.designprototype.workshop.data.DwModelFit
import com.designprototype.workshop.data.dwModelChoiceIntroSentence
import com.designprototype.workshop.data.dwModelChoiceSentence
import com.designprototype.workshop.data.dwModelDownloadMayBeOffered
import com.designprototype.workshop.data.dwModelFitLabel
import com.designprototype.workshop.data.dwModelLanguagesSentence
import com.designprototype.workshop.data.dwModelNeedsConsent
import com.designprototype.workshop.data.dwModelOverrideConfirmLabel
import com.designprototype.workshop.data.dwModelOverrideSentence
import com.designprototype.workshop.ui.field

/**
 * **THE MODELS A DESIGNER MAY CHOOSE FROM, AND WHAT EACH OF THEM WOULD MEAN ON THIS PHONE.**
 *
 * Every decision here is `data/DwModelChoice.kt` and `data/DwModelLanguages.kt`, which are pure
 * Kotlin the desktop JVM tests; every sentence comes from them too. This file decides nothing and
 * writes no copy — it chooses which of their sentences is drawn, and in what order. A screen that
 * did arithmetic on memory would be arithmetic no test could reach, which is the split this
 * repository keeps rediscovering the value of.
 *
 * ── WHAT THE TWO CATALOGUES ACTUALLY HOLD TODAY. RE-MEASURED 2026-08-13 ───────────────────────
 *
 * This header used to say *"`DW_TIER1_CATALOGUE` and `DW_TIER2_CATALOGUE` are empty, so the model
 * list has no rows on any handset in the fleet"*. **That is no longer true of Tier 1 and has not been
 * since 2026-08-12.** Tier 1 holds ONE plan — the 300M omnilingual CTC int8 graph — and it is
 * installed and verified on the attached SM-M325F, so `DwModelChoiceList` draws a real row with a
 * real verdict there. `DW_TIER2_CATALOGUE` is still empty, and its list still draws nothing.
 *
 * The second half of the old paragraph promised a nineteen-row language list beside this one. **That
 * list is deleted** — see the block at the foot of this file, and `dwPackRowWorthShowing` for the rule
 * that replaced it.
 *
 * ── THE INSTALL BUTTON IS WIRED TO A SEAM, AND SETTINGS NOW PASSES IT ────────────────────────
 *
 * [DwModelChoiceList] takes an `onInstall` callback and **draws no control at all when it is null** —
 * this repository's own rule, `DwPackOffer.NO_CONNECTION`, "a control that cannot work is worse than
 * an absent one". `SpeechAndAiScreen` passes a real lambda for Tier 1 (it installs the pinned speech
 * model) and passes none for Tier 2, whose catalogue is empty and for which nothing in this app can
 * fetch a language model. Two lists, two states of completion, said separately.
 */

// ---------------------------------------------------------------------------------------------
// The models
// ---------------------------------------------------------------------------------------------

/**
 * The measured models, judged against this handset, with the suggestion marked and the rest
 * offerable. **Draws nothing at all when there is nothing measured**, which is every handset today.
 *
 * The caller has already printed the tier's own sentence — the refusal that explains why the list
 * is empty — so an empty list here must add nothing: two accounts of "there is no model" on one
 * card is how a designer learns to skim past both.
 */
@Composable
internal fun DwModelChoiceList(
    choices: List<DwModelChoice>,
    measurement: DwDeviceMeasurement,
    connection: DwConnection,
    languageLabels: Map<String, String>,
    /**
     * WHAT KIND OF MODEL THESE ARE, IN A DESIGNER'S WORDS RATHER THAN AS A TIER NUMBER.
     *
     * Required, and it is not decoration: the tier card can show two of these lists, and two
     * unlabelled lists of model ids on one card would leave a designer to work out from the file
     * names which of them is the one that listens and which is the one that summarises. `DwAiTier`
     * is deliberately not passed instead — "Tier 1" is this repository's vocabulary for a provenance
     * column, not a phrase anybody holding the phone has a use for.
     */
    heading: String,
    modifier: Modifier = Modifier,
    onInstall: ((DwModelChoice) -> Unit)? = null,
) {
    if (choices.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            heading,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        dwModelChoiceIntroSentence(choices)?.let { intro ->
            Text(intro, color = MaterialTheme.field.body, fontSize = 12.sp)
        }
        choices.forEach { choice ->
            DwModelChoiceRow(
                choice = choice,
                measurement = measurement,
                connection = connection,
                languageLabels = languageLabels,
                onInstall = onInstall,
            )
        }
    }
}

/**
 * One model: what it is, what it hears, how this phone would get on with it, and — where it can be
 * had at all — the control that spends the data, with the cost of a tight fit accepted first.
 *
 * ── THE CONFIRMATION IS TWO TAPS AND EXACTLY ONE SENTENCE ─────────────────────────────────────
 *
 * A tight fit is a trade a designer is entitled to make: *"consent to a risk is not the same as
 * being protected from it."* So the first tap does not install — it reveals
 * [dwModelOverrideSentence], which says what is expected to happen in the words a person would use,
 * and a button naming the model. The second tap installs. Nothing is greyed out, nothing is hidden,
 * and there is no second warning underneath the first: a confirmation that recites four risks is
 * one nobody reads, and this app has one thing to say here that a designer cannot work out for
 * themselves — the job stops, the work does not.
 */
@Composable
private fun DwModelChoiceRow(
    choice: DwModelChoice,
    measurement: DwDeviceMeasurement,
    connection: DwConnection,
    languageLabels: Map<String, String>,
    onInstall: ((DwModelChoice) -> Unit)?,
) {
    val shape = RoundedCornerShape(10.dp)
    // Local to the row, and deliberately not remembered across a re-probe: a reading that has moved
    // may have moved this model from tight to comfortable or the other way, and an accept-state that
    // outlived the numbers it was accepted against would be consent to a sentence nobody read.
    var accepting by remember(choice.plan.modelId, choice.fit) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.field.hairline, shape)
            .background(MaterialTheme.field.surface50, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                choice.plan.modelId,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            // The suggestion is a marking on a row, never a row of its own and never a filter over
            // the list: the other rows are choices, not mistakes.
            if (choice.suggested) {
                Text("Suggested", color = MaterialTheme.field.success, fontSize = 11.sp)
            }
            Text(
                dwModelFitLabel(choice.fit),
                color = when (choice.fit) {
                    DwModelFit.COMFORTABLE -> MaterialTheme.field.success
                    DwModelFit.TIGHT -> MaterialTheme.field.warning
                    DwModelFit.WILL_NOT_FIT, DwModelFit.UNMEASURED -> MaterialTheme.field.muted
                },
                fontSize = 11.sp
            )
        }

        // The size, the memory, the handset it was measured on, and the fit — one sentence, from the
        // pure layer, so the card and any future surface cannot come to two accounts of one model.
        Text(dwModelChoiceSentence(choice, measurement), color = MaterialTheme.field.body, fontSize = 12.sp)

        /*
         * THE LANGUAGES, ON EVERY ROW, INCLUDING THE ROWS THAT WILL NOT FIT.
         *
         * A designer scanning this list is usually looking for one language — theirs. Printing the
         * coverage only on the installable rows would leave somebody in Odisha unable to tell
         * whether the model they cannot have was the one that would have helped, which is exactly
         * the question they need answered before they go looking for it somewhere else.
         */
        Text(
            dwModelLanguagesSentence(choice.plan, languageLabels),
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )

        // ---- The one control that would spend data ------------------------------------------
        if (onInstall != null && dwModelDownloadMayBeOffered(choice, connection)) {
            if (accepting) {
                dwModelOverrideSentence(choice)?.let { warning ->
                    Text(warning, color = MaterialTheme.field.warning, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { onInstall(choice) }, modifier = Modifier.weight(1f)) {
                        Text(dwModelOverrideConfirmLabel(choice))
                    }
                    TextButton(onClick = { accepting = false }) { Text("Not this one") }
                }
            } else {
                OutlinedButton(
                    onClick = { if (dwModelNeedsConsent(choice)) accepting = true else onInstall(choice) }
                ) {
                    // The words differ because the acts differ. "Install" on a tight model would be
                    // a button that does something other than what it says — it opens a question.
                    Text(if (dwModelNeedsConsent(choice)) "Choose this one anyway" else "Install this model")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// THE NINETEEN-ROW LANGUAGE COVERAGE LIST IS GONE. It stood here, 51 lines of it, and the sentences
// it drew were measured on the handset's own view hierarchy at 1,207 words.
//
// WHAT IT DID: one row per dictation language — the name, a two-word state label, and for every row
// whose answer was not "Works offline" a paragraph from `dwCoverageSentence` explaining why. On the
// fleet's SM-M325F that is SEVENTEEN paragraphs, each of which said, at length, that nothing could
// be done. It also opened with `dwCoverageSummarySentence`, a fourth paragraph counting them.
//
// WHY IT WENT, IN THE OWNER'S OWN WORDS: *"I do not need to know it about each and every language in
// three paragraphs whether it has been downloaded or not"*, and *"For the language that have no
// download option at all, why even show them in the very first place?"* Principle 3: the offline list
// contains exactly what a user can install, is installing, or has installed — which is
// `DwLanguagePackList`, one card up, and `dwPackRowWorthShowing` is the rule that keeps it so.
//
// AND IT HAD NO CALLER WHEN IT WAS DELETED. `SpeechAndAiScreen`'s own header already claimed this
// list "is gone"; the composable had merely been unhooked and left in the tree, which made that
// claim false and left the whole list one import away from being re-hung. Deleting it is what makes
// the claim true.
//
// WHAT A DESIGNER LOST: nothing they can act on, and nothing about whether dictation WORKS. Those
// seventeen languages still dictate through the server — which is where the craft keyterm list lives
// — and the one fact worth learning ("Odia will not work in a courtyard with no bars") is said in
// the dictation flow at the moment it costs something, by `dwDictationNothingLeftSentence`. That is
// where the web says it too.
//
// THE ARITHMETIC BEHIND IT IS DELIBERATELY KEPT — `dwLanguageCoverages`, `DwLanguageCoverage`,
// `dwOfflineCoverage` in `data/DwModelLanguages.kt`. Read the note on `dwLanguageCoverages` before
// drawing any of it again: the composition is correct and tested, the PROSE it used to feed is what
// was wrong, and a new surface has to answer principle 3 on its own terms rather than by reaching
// for a list that already exists.
// ---------------------------------------------------------------------------------------------
