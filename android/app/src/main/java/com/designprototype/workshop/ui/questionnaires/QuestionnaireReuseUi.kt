package com.designprototype.workshop.ui.questionnaires

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.designprototype.workshop.data.CustomQuestionnaireDto
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.ATTACH_LATER
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field

/**
 * **USE THIS QUESTIONNAIRE AGAIN, AT ANOTHER WORKSHOP.**
 *
 * The handset's entry point to `POST /questionnaires/{id}/reuse`. The endpoint and the DTOs shipped
 * before any client offered them, so until this file existed a designer running the same intake at
 * the next cluster had exactly two options: retype forty questions, or download the question set as
 * a workbook and upload it again — which is the same copy, made by hand, across two file pickers and
 * a Downloads folder, on a phone.
 *
 * ── IT COPIES; IT DOES NOT SHARE ─────────────────────────────────────────────────────────────────
 *
 * Questions and sections come across. NO SITTING, NO RESPONDENT AND NO ANSWER does, and the original
 * keeps every answer ever recorded against it. That sentence is on screen before the copy is made
 * ([REUSE_BLURB]) and again in the server's own paragraph afterwards, because "use this again" is a
 * phrase a reader can hear as "share this", and a designer who believed the fieldwork travelled with
 * it would go looking for interviews at the wrong questionnaire weeks later.
 *
 * ── THE TWO GATES THIS DELIBERATELY DOES NOT APPLY ───────────────────────────────────────────────
 *
 * **NOT OWNER-GATED.** `mayEditQuestionnaire` decides who may EDIT this instrument, and reuse edits
 * nothing: it reads a questionnaire the caller is already allowed to read and writes a new one they
 * will own. The route is not owner-gated either, and a client gate here would refuse a colleague a
 * copy of a question set the API would have handed them — the same "hidden from you versus does not
 * exist" failure this repository has paid for before. What IS gated is the TARGET workshop, and it
 * is gated on the server (`_require_attachable_workshop`, a 404 for a workshop the caller cannot see
 * and a 409 for a soft-deleted one), which is why the picker below offers only workshops this
 * account can already reach rather than trying to reproduce that rule.
 *
 * **A DEACTIVATED SOURCE IS STILL REUSABLE.** `isActive: false` is this API's stand-in for a delete —
 * the instrument is out of use and its answers preserved — and a retired instrument is exactly the
 * thing a designer wants to lift for a new round. The route says so in as many words. Hiding this
 * control on a deactivated questionnaire would force a designer to reactivate it first, putting it
 * back in every list and every dropdown for everybody, in order to make a copy.
 */

/** The sentence shown BEFORE the copy is made. The one misreading this feature invites, closed. */
internal const val REUSE_BLURB: String =
    "This makes a NEW questionnaire you own. Its questions come across; no sitting, no respondent " +
        "and no answer does — the original keeps all of them."

/**
 * The control, in a card of its own beside the two interchange cards.
 *
 * NOT folded into [InterchangeCard]. That card is about a FILE — a workbook leaving the phone and
 * coming back — and shares one busy flag with the offline handoff because all three of those writes
 * land in the same Downloads folder. This one writes nothing to disk and touches no file at all; it
 * is a server-side copy, and putting it under a heading about spreadsheets is how a designer looking
 * for it never finds it.
 */
@Composable
internal fun ReuseCard(busy: Boolean, onOpen: () -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Use this questionnaire again",
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedButton(onClick = onOpen, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy it to another workshop", fontSize = 13.sp)
            }
            Text(REUSE_BLURB, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

/**
 * Where the copy goes, what it is called, and what happens to the description.
 *
 * ── THE DESCRIPTION IS A TRI-STATE AND THE CHECKBOX IS WHY ───────────────────────────────────────
 *
 * `questionnaireReuseJson` sends the `description` key only when [onCopy]'s `changeDescription` is
 * true, because the server reads the field through `exclude_unset`: KEY ABSENT means "carry the
 * source's description across", KEY PRESENT AND NULL means "start it empty", and there is no third
 * spelling. A plain text box could not express the first of those — an untouched box would send an
 * empty string and silently blank a description the designer never looked at. So the box is inert
 * until the checkbox is ticked, and the two sentences under it say which of the three is about to
 * happen.
 *
 * ── THE WORKSHOP IS OPTIONAL AND ITS EMPTY ROW IS NOT "NONE" BY ACCIDENT ─────────────────────────
 *
 * An EMPTY call makes an unattached copy this account owns, which is the right answer for a question
 * set a designer is keeping as a template. [SearchableSelectField]'s `includeNone` row is that case
 * stated, not an absence.
 */
@Composable
internal fun ReuseQuestionnaireDialog(
    source: CustomQuestionnaireDto,
    workshops: AttachableWorkshops,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCopy: (designWorkshopId: String?, title: String?, description: String?, changeDescription: Boolean) -> Unit,
) {
    var workshopId by remember(source.id) { mutableStateOf("") }
    var title by remember(source.id) { mutableStateOf("") }
    var changeDescription by remember(source.id) { mutableStateOf(false) }
    var description by remember(source.id) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Use “${source.title.ifBlank { "this questionnaire" }}” again", display = true) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(REUSE_BLURB, color = MaterialTheme.field.muted, fontSize = 12.sp, lineHeight = 17.sp)

                val options = workshops.options()
                SearchableSelectField(
                    label = "Attach the copy to",
                    options = options,
                    selectedValue = workshopId,
                    // The empty row is a REAL ANSWER and the server's own default: an empty call
                    // makes a copy this account owns and no workshop holds, which is what a designer
                    // keeping a template wants. `SearchableSelectField` labels that row with
                    // [placeholder], so the placeholder is written as the answer rather than as a
                    // prompt — "Select" over a row that means "unattached" reads as an unfilled
                    // required field.
                    //
                    // ATTACH_LATER is the constant behind these words: a COPY is the one operation
                    // where the answer can honestly be deferred, which is a different fact from a
                    // record that is filed under nothing, and DROPDOWN_DESIGN §2.7 keeps the two
                    // spellings apart on both clients rather than letting nine strings mean four
                    // things.
                    placeholder = ATTACH_LATER,
                    includeNone = true,
                    enabled = !busy,
                    emptyMessage = workshops.notice(),
                    onSelect = { workshopId = it },
                )
                /*
                  WHY THIS SENTENCE IS NOW TWO SENTENCES.

                  It used to read "No workshops could be listed, so the copy will be unattached"
                  whenever the list was empty — one wording for three different facts, because the
                  loader spelled all three `emptyList()`. It was right about the consequence and
                  silent about the cause, and the cause is the only part a designer can act on: a
                  walk that failed on one bar of signal is fixed by walking outside, and an account
                  that is genuinely on no workshop is fixed by an administrator. §3.5's sentence says
                  which; this file keeps the consequence, which is true in every one of them and is
                  the reason nothing here is blocked.
                */
                if (options.isEmpty()) {
                    workshops.notice()?.let { line ->
                        Text(
                            "$line The copy will be unattached, and it can be attached later from " +
                                "its own screen.",
                            color = MaterialTheme.field.muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                } else {
                    Text(
                        "Only workshops this account can already open are listed. The server refuses " +
                            "a workshop it has not shown you.",
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    workshops.capNotice()?.let { cap ->
                        Text(
                            cap,
                            color = MaterialTheme.field.muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title for the copy (optional)") },
                    placeholder = { Text("${source.title.ifBlank { "Untitled" }} (reused)") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    // The server's rule, said rather than left to be discovered: it counts up against
                    // the titles already at the target, so two copies at one workshop are tellable
                    // apart in a list.
                    "Left blank, the copy is called “${source.title.ifBlank { "Untitled" }} (reused)”, " +
                        "counted up if that name is already taken at the same workshop.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Checkbox(
                        checked = changeDescription,
                        onCheckedChange = { changeDescription = it },
                        enabled = !busy,
                    )
                    Text("Give the copy a different description", fontSize = 12.sp)
                }
                if (changeDescription) {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description for the copy") },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    if (changeDescription) {
                        // Blank MEANS empty here, and only here. That is the whole reason the
                        // checkbox exists rather than a text box that starts out filled in.
                        "Left blank, the copy starts with no description at all."
                    } else {
                        source.description?.takeIf { it.isNotBlank() }
                            ?.let { "The copy carries the same description: “$it”" }
                            ?: "The source has no description, so neither will the copy."
                    },
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    onCopy(
                        workshopId.takeIf { it.isNotBlank() },
                        title.takeIf { it.isNotBlank() },
                        description,
                        changeDescription,
                    )
                }
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (busy) "Copying…" else "Make the copy")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}
