package com.designprototype.workshop.ui.questionnaires

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field

/**
 * WHAT HAPPENED TO THE VOICE NOTE, AND WHAT THE BOX HOLDS BECAUSE OF IT.
 *
 * The handset's twin of the web's `QuickTranscript` (`app/(protected)/questionnaire/page.tsx`), with
 * the same four states and the same words. A researcher moves between the two apps mid workshop, so
 * every sentence here is copied rather than composed.
 *
 * ── FOUR STATES, AND EVERY ONE OF THEM IS SAID IN WORDS ─────────────────────────────────────────
 *
 * A round trip to a transcription provider takes seconds on a village connection, so silence under
 * the box would read as a recorder that ate the take; a refusal that says nothing reads the same
 * way. House rule: a thing that quietly did not happen is indistinguishable from a thing that was
 * never offered.
 *
 * ── THE SPINNER IS NEVER THE ONLY SIGNAL ────────────────────────────────────────────────────────
 *
 * A ring plus the word "Transcribing…", because a signal that exists only as motion is one a
 * reduced-motion reader never gets. Same rule on the web, stated in the same place.
 *
 * ── THE FLAG IS A WORD AND NOT A COLOUR ─────────────────────────────────────────────────────────
 *
 * [questionnaireEditedFlagLabel] returns "Edited" or "Not edited" or null, and the chip prints it.
 * The icon and the tint are secondary: a reader who cannot tell amber from grey still has to know
 * whether the answer on screen is a person's writing or a machine's.
 *
 * ── AND THE OFFERED TEXT IS SHOWN IN FULL, NOT HIDDEN BEHIND A BUTTON ───────────────────────────
 *
 * Somebody deciding whether to add a machine's second take to their own writing needs to read what
 * they would be adding. Hiding it behind "show" would make the two buttons a guess.
 */
@Composable
fun QuestionnaireQuickTranscript(
    busy: Boolean,
    /** Why no instant transcript happened, if it did not. The clip is unaffected either way. */
    problem: String?,
    /** The machine's words as last written into the box, or null if it never wrote any. */
    machine: String?,
    /** What is in the answer box right now — the other half of the edited comparison. */
    current: String,
    /** A newer transcript held back because the box had been edited. */
    offered: String?,
    onAccept: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val edited = questionnaireTranscriptEdited(machine, current)
    val flag = questionnaireEditedFlagLabel(edited)
    // NOTHING AT ALL where there is nothing to say. An answer box on a form nobody has recorded
    // against must not grow an empty strip under it on every one of two hundred questions.
    if (!busy && problem == null && offered == null && flag == null) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                Text(
                    QUESTIONNAIRE_TRANSCRIBING_LINE,
                    // POLITE and not ASSERTIVE: the researcher is mid-interview, and an assertive
                    // region interrupts them to announce something they can do nothing about.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp
                )
            }
            flag?.let { label ->
                val amber = edited == true
                Row(
                    modifier = Modifier
                        .background(
                            if (amber) MaterialTheme.field.warningContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(999.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (amber) Icons.Filled.Edit else Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = if (amber) MaterialTheme.field.onWarningContainer else MaterialTheme.field.muted,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        label,
                        color = if (amber) MaterialTheme.field.onWarningContainer else MaterialTheme.field.muted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        offered?.let { text ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.warningContainer, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    QUESTIONNAIRE_OFFER_LINE,
                    color = MaterialTheme.field.onWarningContainer,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 17.sp
                )
                Text(
                    text,
                    color = MaterialTheme.field.onWarningContainer,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAccept) {
                        Text(QUESTIONNAIRE_OFFER_ACCEPT, fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                    TextButton(onClick = onDiscard) {
                        Text(QUESTIONNAIRE_OFFER_DISCARD, fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        problem?.let {
            Text(
                it,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}
