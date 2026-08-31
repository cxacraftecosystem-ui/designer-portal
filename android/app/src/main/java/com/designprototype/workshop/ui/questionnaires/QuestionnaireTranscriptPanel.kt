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
 * What an offered take is saved under when the host names nothing better.
 *
 * A HOST SHOULD ALWAYS NAME ONE, and every host in this app does: an offer belongs to a numbered
 * question inside a coded section, and a Downloads folder holding forty files called
 * `Offered-transcript.md` is a folder a researcher cannot use. This exists so that the two
 * parameters can travel with defaults — which is what lets a surface wire Copy without Download —
 * and not as an invitation to omit the real one. It is at least a name a filesystem accepts, which
 * is more than an empty string is: see [transcriptDocumentFileName].
 */
const val QUESTIONNAIRE_OFFERED_TRANSCRIPT_FILENAME_BASE: String = "Offered-transcript"

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
 *
 * ── AND IT CAN BE COPIED AND SAVED, WHICH UNTIL THIS LANE IT COULD NOT ──────────────────────────
 *
 * The web wraps this same offered take in a `MarkdownDocument` (`components/richtext/`), and that
 * component IS Copy and Download. This panel had neither, so the only two ways out of the amber box
 * were [onAccept] — which MIXES the machine's words into a person's writing, and is irreversible on
 * this screen — and [onDiscard], which throws the take away. A researcher who wanted the take kept
 * as a SEPARATE second reading had one option left: retype it off the screen, in a courtyard, from a
 * transcript that disappears the moment either button is pressed.
 *
 * Both buttons are `QuestionnaireTranscriptActions`, the row the stored transcript already uses, so
 * a take saved from here and the same take saved from the media card are one file with one name. See
 * [onSave] for why writing the bytes is the caller's job and not this panel's.
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
    /**
     * The human part of the offered take's download name, passed through
     * [transcriptDocumentFileName] so no caller ever builds a file name itself.
     *
     * DEFAULTED, AND THE DEFAULT IS A REAL NAME rather than a placeholder. It is read only when
     * [onSave] is non-null — that is the only case in which a Download button exists to press — so a
     * host that wires Copy alone is not made to invent a name for a file it never offers to write.
     */
    filenameBase: String = QUESTIONNAIRE_OFFERED_TRANSCRIPT_FILENAME_BASE,
    /**
     * Hands the caller the finished file name for the OFFERED take; null draws no Download button.
     *
     * NULL IS A REAL STATE, for the reason `QuestionnaireTranscriptActions.onSave` gives in full:
     * the one function in this app that puts a file in Downloads is a method on `WorkshopRepository`
     * and needs a `Context` besides, and this panel is deliberately given neither — it is drawn under
     * two hundred answer boxes and must stay cheap. A surface composed without a repository genuinely
     * cannot save, and Copy still works there. A button that refused when pressed would be worse than
     * one that is not offered.
     */
    onSave: ((fileName: String) -> Unit)? = null,
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
                /*
                 * COPY AND DOWNLOAD, ABOVE THE TAKE AND NOT BESIDE Add to answer / Discard.
                 *
                 * Same order as the whole-section take in `MainActivity`, which draws this identical
                 * row above its transcript — the two surfaces show the same kind of thing and must
                 * not disagree about where its controls are. Two further reasons, either of which
                 * would settle it alone: the row is a `fillMaxWidth` that pushes its own buttons
                 * right, so it cannot be folded into the button row below without the two fighting
                 * over the same space; and Add to answer is this box's PRIMARY action, which must
                 * not be flattened into a strip of secondary text buttons beside Copy.
                 *
                 * RETINTED TO THE PLATE'S OWN INK. A `TextButton` reads `colorScheme.primary`, which
                 * is purple on both themes and belongs to the app's chrome, not to an amber warning
                 * plate whose every other glyph and letter is `onWarningContainer`. Overriding the
                 * one role for this subtree keeps the shared row shared — the alternative was a
                 * colour parameter on a component three surfaces call, which is how one surface's
                 * palette ends up leaking onto the other two.
                 */
                MaterialTheme(
                    colorScheme = MaterialTheme.colorScheme.copy(
                        primary = MaterialTheme.field.onWarningContainer
                    )
                ) {
                    QuestionnaireTranscriptActions(
                        text = text,
                        filenameBase = filenameBase,
                        onSave = onSave
                    )
                }
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
