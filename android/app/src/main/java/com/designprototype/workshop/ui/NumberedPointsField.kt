package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * THE NUMBERED-POINTS INPUT, MOVED HERE OUT OF `MainActivity.kt` SO THE STAGE FORM CAN USE IT.
 *
 * It was private to the artisan record form, which is why the design workshop's own Do's/Don'ts
 * boxes — the SAME two facts, carried onto the participant row by hydration and editable there —
 * were a single textarea whose only statement of its own structure was the words "One point per
 * line" in its help text. The requirement is that the workshop offer the same input affordance as
 * the record page and not merely accept the same string, so the control had to become reachable
 * from `ui.designworkshop.FieldRenderer` as well.
 *
 * MOVED AND WIDENED, NEVER COPIED. A second numbered-list control would be a second opinion about
 * a three-way contract: the record form writes this newline-joined string, this control and its
 * `MultiNoteInput` neighbour read it back into rows, and `report_builder` splits it into bullets.
 * The five new parameters below all default to what the record form already did, so its two call
 * sites render exactly as before.
 */

/** Split a stored newline-separated list into editable rows (always at least one, for the empty case). */
internal fun splitNumbered(value: String?): List<String> =
    value?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: listOf("")

/** Collapse editable rows back into the stored newline-separated form (blank rows dropped). */
internal fun joinNumbered(items: List<String>): String =
    items.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

/**
 * A numbered multi-point input. Each row is one numbered bullet; pressing Enter inside a row
 * splits it into a new bullet (so the user just types a point and hits Enter for the next). Rows can be
 * removed individually, and "+ Add point" appends an empty one. Backed by a List<String>; persist with
 * [joinNumbered]. Used for an artisan's Do's (positive prompt) and Don'ts (negative prompt), and — via
 * `ui.designworkshop.FieldRenderer` — for every registry LONG_TEXT field the report prints as bullets.
 *
 * ── THE MICROPHONE, WHICH THIS CONTROL STILL DOES NOT OWN ─────────────────────────────────────
 *
 * It is the one multi-row input on a record form that got neither a microphone nor an editor, so a
 * later reader will assume it was missed. It was not.
 *
 * NO EDITOR: this control already IS a list editor. Its rows persist as a newline-joined string and
 * reopen as numbered points, which is precisely the structure a rich document would encode — so
 * adding one would put two list models in one column, each convinced it owned the newlines.
 *
 * NO MICROPHONE OF ITS OWN, still, and that judgement stands for the record form: a do or a don't is
 * one short line ("do not wash in hot water"), which is the shape the user's own rule excludes. What
 * changed is that the stage form's LONG_TEXT box HAD a microphone before this control replaced it
 * there, and taking dictation away to gain list rows would have been a trade rather than a fix. So
 * the caller supplies one per row through [rowTrailing] and shows its running partial through
 * [rowOverlay], and the recogniser stays entirely on the caller's side of the boundary — which is
 * what the old note asked for ("widen the shared control rather than hand-rolling a fourth
 * microphone here"). The record form passes neither and is unchanged.
 *
 * The row's own box keeps the three behaviours a shared prose field does not have: an `onValueChange`
 * that splits a pasted newline into new bullets, an `isError` on the first row, and a
 * [FocusRequester] the form drives on a validation failure.
 */
@Composable
internal fun NumberedListInput(
    label: String,
    items: List<String>,
    error: String? = null,
    focusRequester: FocusRequester? = null,
    helper: String? = null,
    /** False disables every box and button — a stage being previewed rather than edited. */
    enabled: Boolean = true,
    /**
     * Append the asterisk to [label].
     *
     * True for the record form's Do's/Don'ts, which are required and pass a bare label. The stage
     * form passes false because its `fieldLabel` has already decided — from the registry's own
     * `required` flag — whether the asterisk belongs there, and a second one would print "Do's * *".
     */
    required: Boolean = true,
    /**
     * Draw the header in the muted 12sp the stage form uses for a block label, rather than the
     * record forms' semibold-on-surface. One field styled unlike the fields above and below it reads
     * as a different KIND of question, which this one is not.
     */
    mutedLabel: Boolean = false,
    /**
     * Text to draw in row `index` INSTEAD of its stored value, or null to draw the stored value.
     *
     * For a dictation partial, which is revised as the sentence continues and so must not be
     * committed as it grows. A row showing an overlay is read-only for the seconds it is showing
     * one: a keystroke landing mid-stream would be overwritten by the next partial, which reads as a
     * broken keyboard.
     */
    rowOverlay: (Int) -> String? = { null },
    /** A control drawn at the end of row `index` — the caller's microphone, and nothing else so far. */
    rowTrailing: @Composable ((Int) -> Unit)? = null,
    onChange: (List<String>) -> Unit
) {
    val rows = items.ifEmpty { listOf("") }
    // Read into a local before the null test, so the slot is smart-cast without relying on a cast
    // reaching into the lambda that invokes it. The type is written out rather than inferred, so the
    // `@Composable` on it cannot be lost on the way into the local.
    val trailing: @Composable ((Int) -> Unit)? = rowTrailing
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        val header = if (required) "$label *" else label
        if (mutedLabel) {
            Text(header, color = MaterialTheme.field.muted, fontSize = 12.sp)
        } else {
            Text(header, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        }
        helper?.let { Text(it, color = Muted, fontSize = 12.sp) }
        rows.forEachIndexed { index, item ->
            val overlay = rowOverlay(index)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${index + 1}.", color = Muted, fontSize = 14.sp)
                OutlinedTextField(
                    value = overlay ?: item,
                    onValueChange = { raw ->
                        // A keystroke arriving while an overlay is showing is dropped rather than
                        // committed, for the reason [rowOverlay] gives. `readOnly` below already
                        // stops the box accepting one; this is the second lock, because a hardware
                        // keyboard is not the soft one.
                        if (overlay == null) {
                            if (raw.contains('\n')) {
                                // Enter pressed: commit text before the break, push the remainder to new bullet(s).
                                val segments = raw.split('\n')
                                val updated = rows.toMutableList()
                                updated[index] = segments.first().trim()
                                updated.addAll(index + 1, segments.drop(1).map { it.trim() })
                                onChange(updated)
                            } else {
                                val updated = rows.toMutableList()
                                updated[index] = raw
                                onChange(updated)
                            }
                        }
                    },
                    enabled = enabled,
                    readOnly = overlay != null,
                    isError = error != null && index == 0,
                    trailingIcon = if (trailing == null) null else {
                        { trailing(index) }
                    },
                    minLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .let { if (index == 0 && focusRequester != null) it.focusRequester(focusRequester) else it }
                )
                if (rows.size > 1) {
                    IconButton(
                        enabled = enabled,
                        onClick = {
                            val updated = rows.toMutableList().also { it.removeAt(index) }
                            onChange(updated.ifEmpty { listOf("") })
                        }
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove point", tint = Muted)
                    }
                }
            }
        }
        TextButton(onClick = { onChange(rows + "") }, enabled = enabled) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add point")
        }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
}
