package com.designprototype.workshop.ui.questionnaires

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.ui.Text
import kotlinx.coroutines.delay

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *  THE TWO THINGS ANYBODY EVER DOES TO A TRANSCRIPT: COPY IT, SAVE IT.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The handset's half of `frontend/components/richtext/MarkdownDocument.tsx`, which the owner asked
 * for on 2026-08-30: *"the markdown formatting helper that is there for the current implementation
 * of copying and downloading should be there as well"*. The web composed rendering, copying and
 * saving into one component mounted on three surfaces. This app had the first two, spelled once, in
 * one place — a bare `clipboard.setText` beside a stored transcript in `MainActivity` — and had no
 * way to save a transcript at all.
 *
 * ── WHAT TRAVELS IS THE MARKDOWN, NOT THE RENDERED TEXT ───────────────────────────────────────
 *
 * Both buttons hand over [text] verbatim. Copying the rendered text instead looks tidier in a mail
 * and is wrong here for a reason specific to what these documents are: a refined transcript's
 * SPEAKER LABELS ARE THE MARKDOWN, and the horizontal rules are how a multi-clip transcript
 * separates takes. Flattening them produces a wall of prose in which nobody can tell who spoke,
 * which is the one thing the refinement pass was run to establish.
 *
 * ── AND IT DRAWS THE ACTIONS ONLY, WHERE THE WEB'S ALSO DRAWS THE BODY ────────────────────────
 *
 * The three surfaces render their transcript differently and cannot be unified from here: a stored
 * transcript goes through `MarkdownText`, which is private to `MainActivity.kt`, while the two on
 * the interview form are plain paragraphs inside a coloured plate. Hoisting that renderer out of a
 * 19,000-line file three other agents are editing today is a change worth making and is not this
 * one. What HAD to be shared is what the two buttons do — bytes, file name, extension and refusal
 * wording — because a transcript saved from the media card and the same transcript saved from the
 * interview form must be the same file.
 */

/**
 * A file name that cannot break a file system or run away with a prompt.
 *
 * `safeDocumentFileName` on the web, rule for rule. Windows refuses backslash, slash, colon,
 * asterisk, question mark, double quote, angle brackets and pipe outright, and every one of them is
 * reachable from the recording's own filename or a section title, which is what these names are
 * built from. The 60-character ceiling is not cosmetic: a questionnaire prompt runs to two thousand
 * characters, and several filesystems cap one path segment at 255 BYTES, which a UTF-8 Devanagari
 * title reaches a long way before it reaches 255 characters.
 *
 * ONE DELIBERATE DIVERGENCE FROM THE WEB, at the ceiling: a lone trailing high surrogate left by
 * cutting an emoji in half is dropped, so the name is 59 characters rather than 60 ending in half a
 * character. `String.slice` on the web does not do this and produces a broken name for that input;
 * matching a defect for the sake of matching would be the wrong kind of parity, and the names differ
 * only for a name the web spells wrongly.
 */
fun transcriptDocumentFileName(base: String, extension: String): String {
    val cleaned = base
        .replace(Regex("[\\\\/:*?\"<>|]"), "-")
        .replace(Regex("\\s+"), "-")
        .replace(Regex("-+"), "-")
        .trim('-')
        .take(60)
        .let { if (it.isNotEmpty() && it.last().isHighSurrogate()) it.dropLast(1) else it }
    return "${cleaned.ifBlank { "transcript" }}.$extension"
}

/**
 * The extension and the media type a transcript is saved under, decided once.
 *
 * `.md` and `text/markdown`, NOT `.txt`: the speaker labels and the rules between takes ARE
 * markdown, so a `.md` opens as formatted text in every editor a researcher is likely to have while
 * still reading perfectly well raw. The web says the same at its own `save`.
 */
const val TRANSCRIPT_DOCUMENT_EXTENSION: String = "md"
const val TRANSCRIPT_DOCUMENT_MIME: String = "text/markdown"

/**
 * Copy and Download over a transcript, with whatever the surface wants to say at the left.
 *
 * @param text the markdown, handed to both buttons verbatim.
 * @param filenameBase the human part of the download's name; passed through
 *   [transcriptDocumentFileName], so a caller never builds one itself.
 * @param onSave hands the caller the finished file name. Writing the bytes is the caller's, because
 *   the one function in this app that puts a file in Downloads lives on `WorkshopRepository` and
 *   was learned from field failures — the IS_PENDING handshake, the pre-Q permission check, the
 *   `filesDir` fallback and the read-back of the name MediaProvider actually used. A second copy of
 *   it here would be a second copy to get wrong.
 *
 *   NULL DRAWS NO DOWNLOAD BUTTON, and it is a real state rather than a convenience: that writer is
 *   a method on a repository instance, and a surface composed without one — the preview arms of the
 *   media list take `repository = null` — genuinely cannot save. Copy still works there, which is
 *   what this control did on every surface until today. A button that refuses when pressed would be
 *   worse than one that is not offered.
 * @param leading drawn at the left of the row — the edited flag, a heading, a clip count. A slot and
 *   not a boolean, because "has a person touched this" is a different question with a different
 *   amount of evidence behind it on each of the three surfaces.
 */
@Composable
fun QuestionnaireTranscriptActions(
    text: String,
    filenameBase: String,
    onSave: ((fileName: String) -> Unit)?,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }

    // The confirmation clears itself. Keyed on `copied` so a second press restarts the two seconds
    // rather than leaving the label to expire from the first — and cancelled automatically when the
    // row leaves the composition, which is what stops a state write landing after the screen is gone.
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        leading()
        // Pushed right so whatever the surface put at the left reads FIRST: the state of the text
        // matters before what can be done with it. The web's row makes the same choice.
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = {
                clipboard.setText(AnnotatedString(text))
                copied = true
            },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Icon(
                if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                // The word beside it says which action this is; the icon repeating it would be read
                // out twice by a screen reader.
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(if (copied) "Copied" else "Copy", fontSize = 12.sp)
        }
        if (onSave != null) {
            TextButton(
                onClick = {
                    onSave(transcriptDocumentFileName(filenameBase, TRANSCRIPT_DOCUMENT_EXTENSION))
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Download", fontSize = 12.sp)
            }
        }
    }
}
