package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwCustomCache
import com.designprototype.workshop.data.DwCustomCopy
import com.designprototype.workshop.data.DwCustomFieldDto
import com.designprototype.workshop.data.DwCustomSectionDto
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.customFieldToFieldDto
import com.designprototype.workshop.data.customStageBlocks
import com.designprototype.workshop.data.dwCustomCopy
import com.designprototype.workshop.data.liveCustomFields
import com.designprototype.workshop.data.retiredCustomFieldsWithAnswers
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.serialization.json.JsonElement

/**
 * The questions THIS WORKSHOP'S DESIGNER added to this stage, drawn under the stage's own form.
 *
 * ── WHERE IT SITS, AND WHY THAT IS NOT COSMETIC ───────────────────────────────────────────────────
 *
 * BETWEEN the stage's singleton and its collections, because that is where `stage_completeness`
 * counts them (`services/stage_schema.py:1323`) and where the web's `scoreStageData` counts them. The
 * scorer's `missing` list is printed in order and truncated at three — on the stage index, on the
 * readiness screen and in the report's Outstanding column — so the order the questions are counted in
 * is the order a designer is told to go and fill them in. A form that drew them somewhere else would
 * send somebody to the bottom of a stage for the item printed first.
 *
 * ── NO TIER DISCLOSURE, AND THAT IS A DELIBERATE DIVERGENCE FROM [EntitySection] ───────────────────
 *
 * The registry's singleton hides its ADVANCED fields behind "More detail" because a stage can carry
 * forty of them and a workshop held in a village without power must not open on two hundred boxes. A
 * custom section is capped at sixty fields by the server and is in practice three or four, all of
 * them written by the person holding the phone — so a disclosure here would hide a designer's own
 * question from the designer who wrote it, which is how it comes to be unanswered. The tier is still
 * carried on the field and still reaches the report's tier cap; only the on-screen collapse is
 * dropped.
 *
 * ── THE THREE STATES ARE VISIBLE HERE, WHICH IS THE WHOLE REASON THERE ARE THREE ──────────────────
 *
 * [DwCustomCopy.DEFINED] draws the sections. [DwCustomCopy.NONE_DEFINED] draws NOTHING AT ALL — no
 * heading, no note, no apology — because the server has been asked and there is nothing to apologise
 * for, and that is the state most workshops are in. [DwCustomCopy.UNKNOWN] draws a note only when
 * this device is actually holding answers it cannot label, which is the one case where silence would
 * be a lie: the designer is looking at a stage that has answers in it and a form that does not
 * mention them.
 */
@Composable
fun DwCustomSectionForm(
    definition: DwCustomCache?,
    stageKey: String,
    values: Map<String, JsonElement>,
    onValueChange: (String, JsonElement?) -> Unit,
    services: DwFieldServices? = null,
    /**
     * What the repository refused in THIS container, by the designer's own field key.
     *
     * `_custom` is not a registry entity, so `save_stage` files its per-field errors under the
     * reserved key itself rather than under an entity — see [DwStageRefusal]. A refusal here is the
     * one a designer is least able to work out for themselves: the question is their own, the bound
     * is their own, and until this parameter existed the repository declined the answer, kept the
     * previous one, and said so to nobody.
     */
    errors: Map<String, String> = emptyMap(),
) {
    val blocks = remember(definition, stageKey) { customStageBlocks(definition, stageKey) }
    val copy = remember(definition) { dwCustomCopy(definition) }

    if (blocks.isEmpty()) {
        // Said out loud ONLY where staying quiet would be a lie. See the header: an unread definition
        // on a stage with no custom answers on this device is the ordinary state of the ordinary
        // workshop, and a note there would be the false alarm that teaches people to stop reading
        // notes.
        if (copy == DwCustomCopy.UNKNOWN && values.values.any { DwValues.isFilled(it) }) {
            DwCustomUnreadNote()
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        blocks.forEach { block ->
            DwCustomSectionBlock(block.section, values, onValueChange, services, errors)
        }
    }
}

@Composable
private fun DwCustomSectionBlock(
    section: DwCustomSectionDto,
    values: Map<String, JsonElement>,
    onValueChange: (String, JsonElement?) -> Unit,
    services: DwFieldServices?,
    errors: Map<String, String>,
) {
    val live = remember(section) { liveCustomFields(section) }
    val retired = remember(section, values) { retiredCustomFieldsWithAnswers(section, values) }
    // Only the DRAWABLE ones, and keyed the way [FieldRenderer]'s `siblings` expects. A field this
    // build cannot draw is not offered as a hydration target or as a cascade parent either — it has
    // no control, so it has no value to narrow anything by.
    val siblings = remember(live) {
        live.filter { com.designprototype.workshop.data.dwCustomFieldDrawable(it.type) }
            .associate { it.key to customFieldToFieldDto(it) }
    }

    // A section with no live fields and no answered retired ones is a section the designer removed
    // everything from. Drawing its heading over nothing reads as a broken form.
    if (live.isEmpty() && retired.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            section.title.ifBlank { section.key },
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        // NAMED AS THE DESIGNER'S OWN, once per section. Without it a custom question is
        // indistinguishable from one of the registry's 496, and the first time a colleague cannot
        // find it on their own handset there is nothing on screen to explain why.
        Text(
            "Added to this workshop by its designer.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
        )
        if (section.description.isNotBlank()) {
            Text(section.description, color = MaterialTheme.field.muted, fontSize = 12.sp)
        }

        live.forEach { field ->
            DwCustomFieldRow(
                field = field,
                value = values[field.key],
                onChange = { next -> onValueChange(field.key, next) },
                error = errors[field.key],
                services = services,
                siblings = siblings,
                rowValues = values,
            )
        }

        retired.forEach { field -> DwCustomRetiredAnswer(field, values[field.key]) }
    }
}

/**
 * One answer given under a wording that is no longer asked.
 *
 * PRINTED RATHER THAN HIDDEN, and only where an answer stands against it — the rule
 * [retiredCustomFieldsWithAnswers] enforces. A retired field is retired precisely BECAUSE it was
 * answered (an answered question is superseded, never deleted), so what is on screen here is evidence
 * recorded under a form of words that appears nowhere else. Hiding it is how the phone's copy of a
 * report and the office's come to disagree about the fieldwork with nothing in either saying so — the
 * "how many looms" → "how many weavers" failure `questionnaire_forms.py` names in full.
 *
 * It is drawn as text and not as a disabled box, deliberately: a box invites a tap, and there is
 * nothing to type.
 */
@Composable
private fun DwCustomRetiredAnswer(field: DwCustomFieldDto, value: JsonElement?) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(field.label.ifBlank { field.key }, color = MaterialTheme.field.muted, fontSize = 12.sp)
        Text(DwValues.text(value), fontSize = 13.sp)
        Text(
            "No longer asked. The answer above was recorded under this wording and is kept.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
        )
    }
}

/** The one note this form ever draws about a definition it has not got — see the header's third state. */
@Composable
private fun DwCustomUnreadNote() {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.field.warningContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "This stage has answers to questions this phone has not read",
                color = MaterialTheme.field.onWarningContainer,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Answers are recorded here against questions that belong to this workshop's own " +
                    "sections, and this device has not read those sections — so they cannot be " +
                    "shown, counted or printed. Nothing has been lost: they are on this phone and " +
                    "they still sync. Open this workshop once with a connection and they appear.",
                color = MaterialTheme.field.onWarningContainer,
                fontSize = 11.sp,
            )
        }
    }
}
