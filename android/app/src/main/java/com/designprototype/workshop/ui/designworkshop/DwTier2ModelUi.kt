package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DW_TIER2_UNJUDGED
import com.designprototype.workshop.data.DW_TIER2_UNJUDGED_LABEL
import com.designprototype.workshop.data.DwDeviceMeasurement
import com.designprototype.workshop.data.DwModelChoice
import com.designprototype.workshop.data.DwModelFit
import com.designprototype.workshop.data.DwTier2UnjudgedModel
import com.designprototype.workshop.data.dwModelFitLabel
import com.designprototype.workshop.data.dwTier2ListIntro
import com.designprototype.workshop.data.dwTier2RowSentence
import com.designprototype.workshop.data.dwTier2UnjudgedSentence
import com.designprototype.workshop.ui.field

/**
 * **THE FOUR LANGUAGE MODELS, ON EVERY HANDSET, TERSE. A ROW IS: NAME, SIZE, MEMORY, VERDICT.**
 *
 * A separate list from [DwModelChoiceList] rather than a flag on it, for two reasons that are both
 * about not printing a false sentence:
 *
 *  * **Two of the four cannot be [com.designprototype.workshop.data.DwModelPlan]s at all.** The Gemma
 *    3n artifacts have no published memory figure, so there is no peak RSS to construct a plan with and
 *    nothing for `dwModelFit` to judge. They are real files with real sizes and they are listed here
 *    with the word "unknown" where a verdict would be — `DwModelChoiceList` takes a list of judged
 *    choices and has nowhere to put a row like that.
 *  * **The speech list's sentences are about transcription.** `dwModelChoiceSentence` appends
 *    "How accurately it transcribes ANY language is UNMEASURED" and "How long it takes to transcribe a
 *    recording on this phone is UNMEASURED" for a plan with no scores and no timing band — true of the
 *    speech model those clauses were written for, and nonsense under a summariser.
 *
 * **THIS FILE DECIDES NOTHING AND WRITES NO COPY**, which is `DwModelChoiceUi`'s discipline and the
 * reason both are safe: every verdict comes from `dwModelFit`, every word from `data/DwTier2Models.kt`,
 * and what is chosen here is which sentence is drawn and in what order.
 *
 * **THERE IS NO INSTALL CONTROL, AND ITS ABSENCE IS DELIBERATE RATHER THAN UNFINISHED.**
 * `dwTier2InstallMayBeOffered` is false on every handset because this build has no runtime that could
 * open one of these files, and a button that spends 2.6 GB of a designer's data on bytes nothing can
 * read is worse than no button — the same rule as `DwPackOffer.NO_CONNECTION`. The reason is said once,
 * in the list's own opening line, and not repeated per row.
 */
@Composable
internal fun DwTier2ModelList(
    choices: List<DwModelChoice>,
    measurement: DwDeviceMeasurement,
    modifier: Modifier = Modifier,
    unjudged: List<DwTier2UnjudgedModel> = DW_TIER2_UNJUDGED,
) {
    if (choices.isEmpty() && unjudged.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Language models",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            dwTier2ListIntro(choices.size, unjudged.size),
            color = MaterialTheme.field.body,
            fontSize = 12.sp
        )
        choices.forEach { choice ->
            DwTier2Row(
                name = choice.plan.modelId,
                verdict = dwModelFitLabel(choice.fit),
                verdictColour = when (choice.fit) {
                    DwModelFit.COMFORTABLE -> MaterialTheme.field.success
                    DwModelFit.TIGHT -> MaterialTheme.field.warning
                    DwModelFit.WILL_NOT_FIT, DwModelFit.UNMEASURED -> MaterialTheme.field.muted
                },
                sentence = dwTier2RowSentence(choice, measurement),
            )
        }
        /*
         * THE UNJUDGED ROWS COME LAST, WHICH IS THE SAME ORDER `dwModelChoices` SORTS IN: the rows
         * somebody can act on first, the ones nothing can be said about after them. They are not
         * hidden — a designer who has heard of Gemma 3n needs to learn from this app that it exists,
         * is bigger, and has no published memory figure, rather than going to look for it elsewhere.
         */
        unjudged.forEach { model ->
            DwTier2Row(
                name = model.modelId,
                verdict = DW_TIER2_UNJUDGED_LABEL,
                verdictColour = MaterialTheme.field.muted,
                sentence = dwTier2UnjudgedSentence(model),
            )
        }
    }
}

/** One row: the name and the verdict on one line, the numbers on the next. Nothing else. */
@Composable
private fun DwTier2Row(
    name: String,
    verdict: String,
    verdictColour: androidx.compose.ui.graphics.Color,
    sentence: String,
) {
    val shape = RoundedCornerShape(10.dp)
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
                name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(verdict, color = verdictColour, fontSize = 11.sp)
        }
        Text(sentence, color = MaterialTheme.field.body, fontSize = 12.sp)
    }
}
