package com.designprototype.workshop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/*
 * THE MANDATORY-FIELD ASTERISK, IN RED, IN ONE PLACE — the Android half of the web's
 * `frontend/components/ui/RequiredMark.tsx`.
 *
 * ── WHY THIS SNIFFS A TRAILING " *" INSTEAD OF TAKING A BOOLEAN ─────────────────────────────────
 *
 * The obvious signature is `requiredMark(required: Boolean)` drawn beside the label, and it is the
 * wrong one HERE, for a reason specific to this codebase rather than to taste.
 *
 * On the web the mark is a JSX child: `<label>{text}<RequiredMark when={required} /></label>`, so a
 * Boolean at the call site is natural and there are exactly ten call sites to convert. On Android
 * there is no such seam. Compose cannot colour a substring of a `String`, and every shared control
 * in this app takes `label: String` and renders it with a single `Text(label)` —
 * `RichTextEditor`, `FieldDateField`/`FieldTimeField`, `SearchableSelectField`,
 * `SearchableMultiSelectField`, `NumberedListInput`, `RecordProseField`, `DwGeoField`,
 * `TaskAdminScreen.FieldLabel`, `MainActivity.DropdownField`. Threading a Boolean through all of
 * them means changing nine public signatures and then auditing every one of their ~100 call sites
 * to decide which passes `true` — and a call site that already bakes the mark into its literal
 * ("Craft *", "Email *", "Workshop title *") would keep drawing a plain-ink asterisk while
 * compiling perfectly, because nothing would have told it to stop.
 *
 * SO THE CONVENTION IS THE INPUT. That the mark IS a trailing `" *"` is not a guess: it is already
 * written down and relied upon in two producers —
 *
 *   · `FieldRenderer.fieldLabel` (`FieldRenderer.kt`), which builds the label for all 22 stages and
 *     ends `if (field.required) append(" *")`; and
 *   · `LocationFields.requiredLabel`, whose own comment says "The mark itself is
 *     `FieldRenderer.fieldLabel`'s, so one mark means one thing on every screen."
 *
 * Honouring that convention at the RENDERER means one shared helper catches every label that
 * carries the mark, including the two dozen that spell it into a literal and would have been missed
 * by a per-call-site Boolean. No signature changes, and `fieldLabel` keeps returning the plain
 * `String` that the cap-notice sentences and the TalkBack `speech` strings are built from.
 *
 * THE FAILURE THIS PREVENTS: a required field whose asterisk is the same ink as its label, on a
 * nineteen-field stage form, read in a courtyard on a phone — the mark is a character the designer
 * has to hunt for, and the moment they miss it is the moment the save is refused.
 *
 * ── WHY `colorScheme.error` AND NOT A LITERAL ──────────────────────────────────────────────────
 *
 * `Theme.kt` binds `error` to `FieldPalette.Error600` (#DC2626) in light and `FieldPalette.Error400`
 * (#F87171) in dark, and says why in as many words: "success-600 and error-600 both fall under
 * 4.5:1 against the dark canvas". A hardcoded #DC2626 here would be legible on the light canvas and
 * thin on `CardDark` — exactly the contrast failure that substitution exists to fix. The two values
 * this token resolves to are also, deliberately, the same two the web's `RequiredMark` uses
 * (`text-error-600 dark:text-red-400`), so one mark is one colour on both clients.
 *
 * ── THE MARK STAYS IN THE ACCESSIBLE NAME ──────────────────────────────────────────────────────
 *
 * Nothing here hides the asterisk from TalkBack. Colour is not information to a screen reader, so a
 * reader who loses the mark loses the fact; and this app's pickers have no `required` parameter of
 * their own to announce it from (see the note at `SearchableSelect`'s `speech`). The web made the
 * identical non-change for the identical reason. What changed is what the mark looks like, not what
 * it says.
 */

/**
 * The mark itself, INCLUDING its leading space, exactly as `FieldRenderer.fieldLabel` and
 * `LocationFields.requiredLabel` append it.
 *
 * The space is part of the mark rather than the caller's problem, so "Label *" can never become
 * "Label*" by somebody tidying a string template — the same rule the web's `RequiredMark` states.
 */
const val DW_REQUIRED_MARK: String = " *"

/**
 * [label] with the required mark removed, for the surfaces where a mark is meaningless or wrong.
 *
 * Three places use this, and all three are places the label ESCAPES its own control:
 *
 *  · the list cap-notice sentences in `FieldRenderer`, where the label is interpolated mid-prose —
 *    "Photographs * holds at most 8 entries" reads as a typo, and the reader is being told about a
 *    ceiling, not asked to fill anything in;
 *  · the searchable picker sheet's heading, which names the list being browsed rather than a box
 *    waiting for an answer.
 *
 * A no-op on a label that never carried the mark, so it is safe on any label string.
 */
fun dwWithoutRequiredMark(label: String): String = label.removeSuffix(DW_REQUIRED_MARK)

/**
 * [label] as it should be DRAWN: the words in the ambient ink, and the trailing required mark — if
 * there is one — in the theme's error colour.
 *
 * Returns an `AnnotatedString`, which the app's own `Text` has an overload for (`FieldText.kt`), so
 * a converted call site keeps its `display`/`fontSize`/`color` arguments unchanged. The span colour
 * wins over the `color` argument for the mark's range only, which is the whole point: the label
 * keeps whatever muted or on-surface ink its control already chose.
 *
 * A label with no trailing mark comes back as a plain single-span string, so this is safe to wrap
 * around EVERY label a control renders rather than only the ones known to be required — and that is
 * how it is applied, because "which labels are required" is a question this file must never have to
 * answer correctly.
 *
 * To put plain text AFTER the mark — the multi-select's "(3 selected)" count is the only case —
 * concatenate: `requiredMarked(label) + AnnotatedString(" (3 selected)")`. The mark belongs to the
 * label, not to the line.
 */
@Composable
fun requiredMarked(label: String): AnnotatedString {
    val markColor = MaterialTheme.colorScheme.error
    return dwRequiredMarked(label, markColor)
}

/**
 * [requiredMarked]'s body, with the colour passed in rather than read from the theme.
 *
 * Separate so it can be unit-tested off a device: `MaterialTheme` needs a composition, and the thing
 * worth testing — that the mark is split off, that it is the only styled span, and that a label
 * without one is left alone — does not.
 */
fun dwRequiredMarked(label: String, markColor: Color): AnnotatedString {
    val stem = label.removeSuffix(DW_REQUIRED_MARK)
    if (stem.length == label.length) return AnnotatedString(label)
    return buildAnnotatedString {
        append(stem)
        withStyle(SpanStyle(color = markColor)) { append(DW_REQUIRED_MARK) }
    }
}
