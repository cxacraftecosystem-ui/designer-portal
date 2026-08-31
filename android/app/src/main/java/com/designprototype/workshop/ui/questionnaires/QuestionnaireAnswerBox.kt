package com.designprototype.workshop.ui.questionnaires

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.report.fromJson
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.designworkshop.RichTextEditor
import com.designprototype.workshop.ui.field

/**
 * THE INTERVIEW FORM'S ANSWER BOX — the stage screens' editor, hosted over a `String?` column.
 *
 * ── WHY THE EDITOR AND NOT THE PLAIN BOX IT REPLACED ──────────────────────────────────────────
 *
 * The owner, 2026-08-30, of the transcript: it *"should appear in the rich text box"*. The web's
 * answer box became a `RichTextField` on 2026-08-31 and this one stayed a plain `TextInput`, which
 * left the handset — the device actually carried into the workshop — unable to READ a formatted
 * answer a colleague had written from the office, never mind write one. What the column holds and
 * why it may hold it is argued in full in `QuestionnaireAnswerText.kt`; this file is only the
 * control.
 *
 * ── IT HOSTS `RichTextEditor` AS IT STANDS, AND TAKES NO MEDIA BRIDGE ─────────────────────────
 *
 * The editor's media parameters are both nullable and both absent here, so it degrades to "no
 * Photograph button" — which is the right answer on an interview form: only a stage knows which
 * workshop's directory a photograph's bytes belong in, and an interview's own recordings and
 * attachments are collected by the clip tray and the attachment picker further down the same form.
 * Forking the editor to change nothing would have meant duplicating ~2,900 lines of caret, undo,
 * span-merge and list arithmetic; `RecordProseField` made this same call and its header argues it.
 *
 * ── NO "…AND ARE NOT STORED ON THIS FIELD" NOTE UNDER THIS ONE, AND THE ABSENCE IS THE POINT ──
 *
 * `RecordProseField` prints one line under every rich box on a RECORD form saying that bold and
 * italic help you write and are not kept, because those columns store `toPlain` and genuinely drop
 * them. This column stores the document, so the marks ARE kept and a line saying otherwise would be
 * false. Nothing is printed instead of printing a reassurance: the box behaves the way it looks,
 * which needs no sentence, and UI copy here is terse by house rule.
 *
 * ── THE ONE THING IN THIS FILE THAT WOULD MAKE THE BOX UNUSABLE IF IT WERE SIMPLIFIED ─────────
 *
 * The seed is held in state and is NOT derived from [value] on every recomposition. `RichTextEditor`
 * re-seeds itself from its `value` prop whenever the incoming document's signature differs from the
 * one it last emitted, and re-seeding moves the caret to the start of the document — its own file
 * calls that "the single most common way a home-grown editor becomes unusable for long-form
 * writing". Here the value that comes back is NOT always byte-identical to what the editor sent: an
 * unformatted document is stored FLATTENED, so its signature differs from the document's every
 * single time. Derived naively the chain is: type a character → emit prose → the form re-renders →
 * a new seed → the editor decides the document changed underneath it → the caret jumps to character
 * zero. On the second keystroke. For ever.
 *
 * So the seed changes only when the value arrived from somewhere OTHER than this editor — the form
 * loading an interview after it composed, a transcript being written into the box, an accepted
 * offer being appended. [mine] is what tells the two apart. This is `RecordProseField`'s mechanism,
 * and it is copied rather than shared because that function is one composable this lane may not
 * widen while three other agents are writing it; if the two ever need to differ, they differ here.
 *
 * @param resetKey re-seed when the form loads a DIFFERENT interview into the same composition.
 */
@Composable
fun QuestionnaireAnswerBox(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = "Answer",
    resetKey: Any? = null,
    onError: (String) -> Unit = {},
) {
    var seed by remember(resetKey) { mutableStateOf(questionnaireAnswerSeed(value)) }
    var mine by remember(resetKey) { mutableStateOf(value) }
    var dictationError by remember(resetKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(value, resetKey) {
        if (value != mine) {
            seed = questionnaireAnswerSeed(value)
            mine = value
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RichTextEditor(
            value = seed,
            onChange = { next ->
                val stored = questionnaireAnswerStored(fromJson(next))
                // Recorded BEFORE the value goes up, so the round trip that comes back is recognised
                // as this editor's own and does not re-seed it.
                mine = stored
                onValueChange(stored)
            },
            enabled = enabled,
            label = label,
        /*
         * WIRED, BECAUSE THE DEFAULT IS `{ }` AND THE DEFAULT IS A KNOWN DEFECT.
         *
         * `RichTextEditor` carries its own microphone and hands that microphone's `onError`
         * straight through to this parameter. Its file records what happened the last time a call
         * site left it unwired: every sentence the dictation ladder produces — the permission
         * refusal, the "no words were heard", the language that is not on this phone — arrived
         * there and stopped, so somebody who had just spoken a passage watched it produce nothing
         * with no account of why. Silence is the one answer this control may never give.
         *
         * It lands in a line under the box AND is forwarded to the screen's own error channel,
         * because on this form the box may be scrolled well off screen inside a long section.
         */
            onError = { message ->
                dictationError = message
                onError(message)
            },
        )
        dictationError?.let {
            Text(it, color = MaterialTheme.field.warning, fontSize = 11.sp)
        }
    }
}
