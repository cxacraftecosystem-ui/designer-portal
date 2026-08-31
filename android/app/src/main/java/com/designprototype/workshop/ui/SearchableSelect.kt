package com.designprototype.workshop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// Searchable selects: one picker interaction for every long list in the app.
//
// WHERE THIS COMES FROM. The phone field's country dialog already had the interaction the rest of
// the app was missing — type "Nepal", the 200-row list collapses to one. Every other list in the
// app (74 tools, 37 states, 25 interviews, the user directory) was a plain anchored DropdownMenu
// you had to scroll past. Rather than write a second search box with its own habits, this file
// LIFTS that dialog into a shared picker and the phone field's list becomes one caller among many.
// A researcher learns "tap, type, commit" once.
//
// WHY A BOTTOM SHEET AND NOT A DROPDOWN OR A DIALOG. Three things decide it, and all three are
// about a thumb on a handset rather than a cursor on a laptop:
//
//   1. The KEYBOARD is the whole problem. The moment the researcher types, the IME takes the lower
//      half of the screen. An anchored DropdownMenu is positioned against its trigger, so a field
//      near the bottom of a long form opens a menu that the IME then sits on top of — the rows are
//      drawn where the keyboard now is. A sheet is anchored to the screen instead, and
//      [Modifier.imePadding] lets the keyboard SHRINK the list rather than cover it.
//   2. REACH. A sheet grows from the bottom edge, so its search box, its Select-all row and its
//      first rows land inside the arc a thumb can cover one-handed. A centred AlertDialog puts the
//      same controls in the middle of the screen, which is the one place a thumb has to stretch
//      for; the phone field's dialog is fine for a control you touch once per artisan and wrong as
//      the app's everyday picker.
//   3. DISMISSAL. Swipe down. A researcher who opened the wrong field gets out without aiming at
//      anything, which matters on a dusty screen in a workshop.
//
// [rememberModalBottomSheetState] is asked to skip the partially-expanded state. A half-height
// sheet plus an IME is a three-row peephole — the exact failure the sheet was chosen to avoid.
//
// WHY SHORT LISTS KEEP THE OLD MENU. Below [SEARCH_THRESHOLD] options there is nothing to search:
// "Draft / Pending / Approved" fits on screen, and making the researcher cross a sheet and dismiss
// a keyboard to pick one of four is worse than the dropdown it replaced. The threshold is a
// property of the LIST, not of the screen, so the same field behaves the same way on every device
// — and it is the same number the web uses, so a field that searches on the laptop searches on the
// phone.
//
// WHY THE COUNT IS ONLY EVER THE DEFAULT. Both fields take a nullable `searchable` override, and
// `null` — what every caller that says nothing gets — is the only value that lets the count decide.
// It exists because the count is a property of the ANSWER and not of the question, and two kinds of
// list have an answer that moves under the reader:
//
//   1. A list that is ONE SERVER-TRUNCATED PAGE. The design-workshop picker asks for twenty rows,
//      which is over the threshold, so the handset drew a filter box over a single page — and
//      typing the title of a workshop that sits on page four answered "Nothing matches" about a
//      workshop that exists. That is absence read as non-existence, over a list the box could never
//      reach. The web refuses to draw that box for exactly this reason; `searchable = false` is how
//      this file refuses too, and the caller owes the reader a sentence saying what does reach the
//      rest.
//   2. A list that CHANGES SHAPE WITH THE ANSWER ABOVE IT. Goa has 2 districts, Sikkim 6, Uttar
//      Pradesh 75, so one district field is an anchored menu in one state and a bottom sheet in the
//      next, and a researcher cannot learn a control that keeps changing what it is. `searchable =
//      true` pins it open whatever the count does.
//
// WHY AN EMPTY LIST HAS TO SAY SO IN BOTH SURFACES. Until [SearchableSelectField.emptyMessage]
// existed, a single-select whose list crossed BELOW eight lost, in one step, the filter box, the
// "N options" live region, the "This list is empty." sentence, the Select-all row and the IME
// commit path — because every one of those lives in the sheet, and below eight there is no sheet.
// With `options.isEmpty()`, `includeNone = false` and no `createAction`, tapping the trigger opened
// a popup with no words in it at all. A picker that opens on nothing reads as "there are none",
// which is a claim about the repository made from a read that may simply have failed on a handset
// with no signal — the single most repeated bug class in this product. So `emptyMessage` is drawn
// in BOTH surfaces, it is the CALLER'S sentence because only the caller knows which of the five
// empty states it is in (a vocabulary that is genuinely short, a cached list with a date on it, a
// list this device has not received yet, a read that failed while online, or a scope with nothing
// in it), and it is spoken from the CLOSED trigger as well, so a field that has been stood down and
// can no longer be opened still says why.
// ---------------------------------------------------------------------------------------------

/**
 * Options at or above this count get the searchable sheet; below it, the anchored menu.
 *
 * Eight is where an anchored menu stops fitting between a mid-form trigger and the bottom of a
 * small handset, so it is the point at which the researcher starts scrolling a floating menu whose
 * position they did not choose. MUST match the web's threshold — see the note at the top of the
 * file.
 *
 * THE NUMBER DOES NOT MOVE, AND IT IS ONLY EVER THE DEFAULT. A vocabulary written inside this app —
 * "Draft / Pending / Approved", the sharing tiers, the status ladders — is exactly what eight was
 * measured for, and every one of those callers still passes nothing and still gets it. What changed
 * is that a list BACKED BY RECORDS no longer lets a count that moves with the data decide what
 * shape a control is: those callers pass `searchable` explicitly, in both directions, and the
 * reasons are set out at the top of this file. Re-measuring this constant to serve them would
 * silently re-shape every vocabulary and none of the record-backed lists, which is the opposite of
 * the fix.
 */
const val SEARCH_THRESHOLD: Int = 8

/**
 * Above this count the search box takes focus as the sheet opens, so the first keystroke filters.
 *
 * Deliberately higher than [SEARCH_THRESHOLD]: for a dozen rows the researcher can very likely SEE
 * the one they want, and popping the IME unasked would hide half of them behind a keyboard they
 * then have to dismiss. Past ~16 rows the list no longer fits on a handset either way, so typing is
 * the faster route in and the keyboard is what they came for.
 */
private const val AUTOFOCUS_THRESHOLD: Int = 16

/** Fraction of the screen the sheet may grow to before its list starts scrolling instead. */
private const val SHEET_HEIGHT_FRACTION: Float = 0.88f

/** A picker row: the stored [value], the [label] read aloud, and an optional trailing [hint]. */
@Immutable
data class SelectOption(val value: String, val label: String, val hint: String? = null)

/**
 * An action offered at the FOOT of a picker, under the options rather than among them.
 *
 * It exists for one case and is shaped by it: "Create a new artisan", offered inside the reference
 * picker of a design-workshop stage. A designer who reaches stage 13 and finds the artisan missing
 * used to have to abandon a half-filled stage to go and make the record, and in a room with the
 * artisan standing in front of them that is where the app stops being used.
 *
 * BELOW THE LIST, NEVER IN IT. A row among the options would be a row the IME's action key can
 * commit — [SearchablePickerSheet.onImeAction] takes the first filtered row — so a designer typing a
 * name and hitting search would find themselves in a create form they did not ask for. It is also
 * never counted in "12 of 74 match", because it is not one of the records being matched.
 *
 * OFFERED WHETHER OR NOT THE SEARCH FOUND ANYTHING, deliberately. A designer usually knows the
 * artisan is absent before they have finished typing the name, and a control that only appears after
 * an empty result is one they have to discover twice.
 *
 * ── AND IT NOW KNOWS WHAT WAS TYPED, WHICH IS WHAT THE BROWSER'S ROW HAS ALWAYS SAID ────────────
 *
 * [label] used to be a fixed `String`, so the row could only ever say what it was going to do and
 * never what it was going to do it TO. That is fine for "Create a new artisan" — the name goes into
 * a record form the designer is about to fill in, and they will see it there. It is not fine for the
 * one control this app has whose create row IS the commit: the design workshop's own name, where
 * the web's `SelectCreateAction` draws *Use “Bagru winter 2026” as the name* and its own comment
 * gives the reason — *"a reader has to be able to see the exact string that would be stored, the
 * capitals, the punctuation, the double space they did not mean to type, and a paraphrase is the one
 * shape that cannot show them."* A handset offering a button that does not name the answer back is
 * asking somebody to commit a string they cannot read.
 *
 * ── `null` MEANS "DRAW NO ROW FOR THIS TERM", AND IT IS WHY THE OFFER RULE ABOVE STILL HOLDS ─────
 *
 * The two kinds of create row want opposite things from an EMPTY box. A record-making action wants
 * to be there before a letter is typed (the paragraph above). A commit-the-term action has nothing
 * to commit and must not draw a button reading *Use “” as the name* — nor may the primitive guess
 * which kind it is holding. So the decision is the caller's, expressed in the one place it can be
 * seen: the label lambda returns `null` for a term it will not act on, and neither surface draws a
 * row it has no words for. The secondary constructor below is the fixed-label case and returns the
 * same string for every term, so every call site written before this parameter existed is unchanged
 * in both surfaces.
 *
 * THE ANCHORED MENU ASKS WITH `""` because it has no box at all, which falls out correctly on both
 * kinds: a fixed label still draws, and a term-aware one draws nothing — a row inviting somebody to
 * use what they typed, on a surface with nowhere to type, is an affordance with no route to it.
 */
@Immutable
data class SelectCreateAction(
    /**
     * The row's words for what is in the box right now, or `null` to draw no row for that term.
     *
     * Quote the term when it appears — the web does, for the reason quoted above.
     */
    val label: (query: String) -> String?,
    /** Act on what is in the box. The term arrives trimmed, and is `""` on the anchored menu. */
    val onClick: (query: String) -> Unit,
) {
    /**
     * The fixed-label create row — "Create a new artisan" — whose words never mention the term.
     *
     * Kept as a constructor rather than asking eleven call sites to write `{ _ -> "…" }`, and kept
     * as the SHAPE OF THE ORIGINAL so a reader diffing this file sees no call site move. It also
     * documents the split: an action that ignores the query is one whose work happens somewhere the
     * designer can still read and correct the name, and it is always offered.
     */
    constructor(label: String, onClick: () -> Unit) : this({ label }, { onClick() })
}

/** Adapt the `value to label` pairs the record forms already build. */
fun List<Pair<String, String>>.asSelectOptions(): List<SelectOption> =
    map { (value, label) -> SelectOption(value, label) }

/**
 * Case-insensitive substring match over label, hint and stored value.
 *
 * Split on whitespace and required in full, so "ram bagru" finds "Ram Kumar · Bagru" — which a
 * single contiguous `contains` does not, and which is how a researcher who half-remembers two
 * things about an artisan actually types. The value is searched too because some lists (dial codes,
 * status names) carry the meaning there rather than in the label.
 */
private fun SelectOption.matches(terms: List<String>): Boolean {
    if (terms.isEmpty()) return true
    val haystack = buildString {
        append(label)
        hint?.let { append(' '); append(it) }
        append(' ')
        append(value)
    }
    return terms.all { haystack.contains(it, ignoreCase = true) }
}

private fun queryTerms(query: String): List<String> =
    query.trim().split(' ', '\t', '\n').filter { it.isNotBlank() }

/**
 * Which surface a single-select opens: the caller's ruling if it made one, otherwise the count.
 *
 * Lifted out of the composable and made `internal` for one reason. This one line is what a wave of
 * call sites is about to depend on, and a decision left inline inside an `@Composable` cannot be
 * asserted here at all: there is no `ui-test-junit4` and no Robolectric in `app/build.gradle.kts`,
 * so the JVM suite cannot render a picker to look at it. Pulled out, it is pinned by
 * `SearchableSelectEmptyStateTest` in plain JUnit, which is the same trade every other decision in
 * this app that matters more than its pixels has already made.
 *
 * `null` is NOT `false`. It means "the count decides", which is what every closed vocabulary in this
 * app wants and what all of them get by passing nothing at all.
 */
internal fun resolveSearchable(searchable: Boolean?, options: List<SelectOption>): Boolean =
    searchable ?: (options.size >= SEARCH_THRESHOLD)

/**
 * The words a surface falls back to when the caller has not written its own.
 *
 * IT IS A FALLBACK AND IT IS NEVER SPOKEN AS A CLAIM. It exists so that an OPENED picker with
 * nothing in it is not a popup containing no words at all — which is what the anchored menu drew
 * before, and a wordless popup reads as "there are none" quite as loudly as a sentence would. It is
 * deliberately the weakest true thing that can be said about a list with no members: it reports the
 * control’s own state and asserts nothing whatsoever about the repository behind it. Anything
 * stronger belongs to the caller — see [SearchableSelectField.emptyMessage].
 */
private const val GENERIC_EMPTY_LINE: String = "This list is empty."

/**
 * The one sentence an empty picker prints, in whichever of the two surfaces is open.
 *
 * TWO FACTS, AND THEY MAY NEVER SHARE A SENTENCE. "Nothing matches" is about the SEARCH — the term
 * just typed found nothing in a list that does have members, and the next move is to retype it.
 * [emptyMessage] is about the LIST — it has no members, and the next move depends entirely on WHY,
 * which is why that string belongs to the caller and not to this file. Printing “Nothing matches
 * “”.” at a researcher whose box is empty, which is what an unguarded message does the moment an
 * empty list can be opened at all, reads as a search that went wrong rather than as a register with
 * nobody in it, and sends them retyping a name that was never there.
 *
 * AND WHEN THERE IS NO LIST AT ALL, THE SEARCH IS NOT THE FACT WORTH PRINTING — which is what
 * [listIsEmpty] is here to settle, and it is not a refinement. The reference roster is the case, and
 * it is reachable today: an empty roster with a `createAction` keeps its trigger (that is the whole
 * point of the exception — the designer is standing in front of the artisan who has no record), the
 * sheet opens with its box drawn because a multi-select is searchable at every length, the designer
 * types the name they came to look for, and the caller’s sentence — "No records for this on the
 * device yet. Connect once and reopen this stage." — is replaced, on the first keystroke, by
 * "Nothing matches “Ram Kumar”.". That sentence says the term is not in the register. The truth was
 * that this device has never been given the register, and the designer has just been told the
 * opposite of it, in the one room where they could still have written the record down. A term cannot
 * fail to match a list that has no members, so with [listIsEmpty] the list’s own sentence stands
 * whatever is in the box, and the reader can still empty the box from the button beside it.
 *
 * Both surfaces call this so that they cannot drift apart. The sheet has said the right thing since
 * the day it was written and the anchored menu said nothing whatsoever; the whole point of the fix
 * is that a list crossing [SEARCH_THRESHOLD] in either direction does not change what the control
 * tells the reader.
 */
internal fun pickerEmptyLine(
    searching: Boolean,
    query: String,
    emptyMessage: String,
    listIsEmpty: Boolean
): String = if (searching && !listIsEmpty) "Nothing matches “${query.trim()}”." else emptyMessage

// ---------------------------------------------------------------------------------------------
// Single select
// ---------------------------------------------------------------------------------------------

/**
 * A one-of-many field. Long lists open the searchable sheet; short ones keep the anchored menu.
 *
 * [includeNone] adds the "no selection" row, labelled with [placeholder] — the blank the record
 * forms rely on to unlink a record.
 */
@Composable
fun SearchableSelectField(
    label: String,
    options: List<SelectOption>,
    selectedValue: String,
    modifier: Modifier = Modifier,
    placeholder: String = "Select",
    includeNone: Boolean = true,
    enabled: Boolean = true,
    /**
     * Which surface this one call site opens, overruling [SEARCH_THRESHOLD].
     *
     * `null` — THE DEFAULT, AND WHAT EVERY CALLER WRITTEN BEFORE THIS PARAMETER GETS — lets the
     * count decide exactly as it always has: at or above [SEARCH_THRESHOLD] the searchable sheet,
     * below it the anchored menu. Nothing about a vocabulary written inside this app changes.
     *
     * `true` where the list is BACKED BY RECORDS and [options] is the WHOLE answer, so that the
     * control keeps its shape when the answer shrinks. The district field is the case: two rows in
     * Goa and seventy-five in Uttar Pradesh, and a reader cannot learn a control that changes shape
     * with the answer above it.
     *
     * `false` to overrule a long list whose [options] are ONE SERVER-TRUNCATED PAGE, because a
     * filter box over a page filters the page. The design-workshop picker asks for twenty rows, so
     * typing the title of a workshop sitting on page four answers "Nothing matches" about a
     * workshop that exists — absence read as non-existence, which is the thing this control is
     * least allowed to say. A caller that passes `false` owes the reader the sentence naming what
     * does reach the rest of the list. Keep the list page-sized when you do: the anchored menu
     * builds every row eagerly inside a scrolling column, which is right for twenty and is not
     * where two hundred belong.
     *
     * Same rule, same words and the same number as `SearchableSelectProps.searchable` on the web,
     * so a field that searches on the laptop searches on the handset.
     */
    searchable: Boolean? = null,
    /**
     * The caller's sentence for an empty list, or `null` — THE DEFAULT — meaning it has not
     * written one.
     *
     * NULLABLE, AND THE NULL IS THE POINT. A plain `String` default made this file the author of a
     * sentence it has no standing to write, and then SPOKE it from the closed trigger.
     * `DesignReviewScreen.kt:272-281` is the caller that proves the harm: it computes the real
     * state into its [placeholder] — "Looking for your workshops…", "This list could not be
     * loaded", "No workshops are listed for this account" — and stands the field down with
     * `enabled = workshops?.isNotEmpty() == true`. With a non-null default, a researcher using
     * TalkBack on a handset whose fetch had just failed heard "A workshop you can open yourself.
     * Nothing selected. This list is empty." while the screen beside them read "This list could not
     * be loaded". Those are the two opposite facts this control exists to keep apart, and the
     * primitive was asserting the wrong one OVER a caller that had already got it right. `null`
     * means "say nothing I was not told", so the forty-odd call sites that pass nothing are, in
     * speech, exactly what they were before this parameter existed. What an OPENED surface prints
     * falls back to [GENERIC_EMPTY_LINE], because a wordless popup is worse than a weak sentence.
     *
     * IT IS NEVER "THERE ARE NONE". An empty picker has five different causes with five different
     * next moves — a vocabulary that is genuinely short, a cached list carrying a refresh date, a
     * list this device has not received yet, a read that failed while online, and a scope with
     * nothing in it — and only the caller can tell which. "No crafts available." asserts the last
     * of them from a read that may simply have timed out in a workshop with no signal.
     *
     * A FIELD STOOD DOWN OVER AN EMPTY LIST PRINTS IT ON THE FORM, and this file does that rather
     * than asking each caller to remember. `enabled = false` makes the trigger unopenable, so the
     * menu's empty arm — the arm added to stop wordless popups — cannot be reached at all; and at
     * the two workshop pickers the field is disabled by EXACTLY the condition that empties it
     * (`enabled = rows.isNotEmpty()`), so that arm is dead code at the call sites it was written
     * for. Left to the caller it is a sentence that has to be repeated at every such site and will
     * be forgotten at one of them; drawn here it comes from this one string, so the eye and the
     * screen reader cannot be told different things. It is printed only when this parameter is
     * non-null, so no caller's layout moves until it has something to say.
     */
    emptyMessage: String? = null,
    /**
     * "Create the record this list has not got", drawn at the foot of whichever surface opens.
     *
     * It must be offered in BOTH branches below and not only in the sheet. A cluster whose artisan
     * register holds three names takes the anchored menu, and three names is precisely the case where
     * the artisan being looked for is the one that was never documented — offering the escape only
     * past eight options would withhold it from every list short enough to need it most.
     *
     * IT SURVIVES THE EMPTY ARM TOO, in both surfaces, and that is the point of it. Every one of the
     * five empty states above is a state in which the record being looked for may be the one nobody
     * has written down yet; a picker that can still make it is not a dead end, and it is the only
     * control on the screen that turns "there is nothing here" into something the researcher can do
     * about it before the interview ends.
     */
    createAction: SelectCreateAction? = null,
    onSelect: (String) -> Unit
) {
    var sheetOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label
    val useSheet = resolveSearchable(searchable, options)

    // THE ONE STATE IN WHICH NEITHER SURFACE CAN BE OPENED, so the sentence has to be on the form
    // itself. A disabled trigger does not react to a tap, which puts both the anchored menu's empty
    // arm and the sheet's out of reach — and this is not a corner: the two workshop pickers stand the
    // field down with the very condition that empties it (`SketchesAndPrototypesScreen.kt:426`,
    // `DesignReviewScreen.kt:280`), so the arm written to stop a wordless popup is unreachable at
    // precisely those call sites. Non-null is what turns this on, so a caller with nothing to say
    // still draws exactly what it drew before.
    val standDownLine = emptyMessage?.takeIf { options.isEmpty() && !enabled }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // [requiredMarked] and not a bare `label`: a caller's trailing " *" is painted in the error
        // colour here, and a label without one is unchanged. `field.muted` still sets the words.
        Text(requiredMarked(label), color = MaterialTheme.field.muted, fontSize = 12.sp)
        Box(modifier = Modifier.fillMaxWidth()) {
            SelectTrigger(
                // The visible label sits in a separate Text above the button, which TalkBack reads
                // as its own node — so a researcher swiping onto the control alone would hear
                // "Ram Kumar, button" with no idea which field it belongs to. Naming the node with
                // both halves is the only way the control is self-describing wherever focus lands.
                //
                // AND THE CALLER'S EMPTY SENTENCE IS PART OF THAT NAME WHEN THERE IS NOTHING TO
                // PICK, so a screen-reader user is not made to open a menu to find out it has
                // nothing in it. It is only ever added when the list is empty, so no control that
                // has something to offer grows a longer description.
                //
                // ONLY EVER THE CALLER'S SENTENCE, NEVER [GENERIC_EMPTY_LINE]. Speech is the one
                // surface a reader cannot correct by glancing at the rest of the screen, so a
                // primitive that guesses here contradicts the screen out loud: three of these
                // pickers put the true state in their [placeholder] — "This list could not be
                // loaded" — and a manufactured "This list is empty." told the researcher using
                // TalkBack the register was empty while the researcher beside them read that the
                // fetch had failed. Weak-but-true is fine for a popup somebody chose to open; it
                // is not fine as the NAME of a control that is read out unasked.
                //
                // AND NOT WHEN [standDownLine] IS ALREADY DRAWING IT. A stood-down field prints
                // the sentence on the form as a node of its own; appending it here as well makes
                // TalkBack read the same words twice running, once as the button's name and once
                // as the text beneath it.
                //
                // THE REQUIRED MARK STAYS IN THIS STRING — `label`, not `dwWithoutRequiredMark`.
                // The mark went red on 2026-08-30 ([requiredMarked], applied to the visible label
                // above), and colour says nothing to a screen reader, so stripping it here would
                // take the fact away from the one reader who cannot see the new colour at all.
                // Nothing else in this control announces it: `SearchableSelectField` has no
                // `required` parameter — required-ness reaches it ONLY inside the label string —
                // so this text is the whole of what TalkBack knows. (Checked against the ruling
                // "keep it in speech only if the control does not otherwise announce required":
                // it does not.) The web made the same non-change for the same reason — its
                // `RequiredMark` is deliberately not `aria-hidden`.
                speech = buildString {
                    append(label)
                    append(". ")
                    append(selectedLabel ?: "Nothing selected")
                    if (options.isEmpty() && standDownLine == null) {
                        emptyMessage?.let {
                            append(". ")
                            append(it)
                        }
                    }
                },
                text = selectedLabel ?: placeholder,
                hasSelection = selectedLabel != null,
                enabled = enabled,
                onClick = { if (useSheet) sheetOpen = true else menuOpen = true }
            )
            if (!useSheet) {
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (includeNone) {
                        DropdownMenuItem(
                            text = { Text(placeholder, color = MaterialTheme.field.muted) },
                            trailingIcon = { if (selectedValue.isBlank()) SelectedTick() },
                            onClick = { onSelect(""); menuOpen = false }
                        )
                    }
                    if (options.isEmpty()) {
                        /*
                         * THE ARM THIS BRANCH DID NOT HAVE, AND THE WHOLE REASON THE PARAMETER
                         * ABOVE EXISTS.
                         *
                         * Before it, an empty list here drew a none-row that may not have been
                         * asked for, then nothing, then usually no create action either — a popup
                         * opening on a blank rectangle. A researcher reads that as "there are
                         * none", and on a handset in a workshop with no signal the truthful reading
                         * is nearly always "this device has not been given the list yet". The two
                         * are opposite facts with opposite next moves and they looked identical.
                         *
                         * A DISABLED MENU ITEM AND NOT A BARE Text, for three reasons. It keeps the
                         * row geometry of the menu, so the sentence reads as part of the control
                         * rather than as a caption floating in a popup. TalkBack stops on it and
                         * announces it as disabled, so a screen-reader user is told both the fact
                         * and that it is not one of the answers. And it cannot be tapped, so a
                         * mis-hit on a one-item menu commits nothing — which matters most here,
                         * because this menu is at its smallest exactly when it is empty.
                         *
                         * NO `maxLines`, unlike the option rows above. Four of the five sentences
                         * this can carry are two or three lines long and every one of them ends in
                         * the part that says what to do next; clipping them to one line with an
                         * ellipsis would keep the claim and throw away the remedy.
                         */
                        DropdownMenuItem(
                            text = {
                                Text(
                                    // The fallback belongs HERE and not in the parameter default.
                                    // This popup has been opened, so the reader is looking at it
                                    // and needs words in it; the closed trigger is read out
                                    // unasked, and there this file says nothing it was not told.
                                    emptyMessage ?: GENERIC_EMPTY_LINE,
                                    color = MaterialTheme.field.muted,
                                    fontSize = 13.sp,
                                    lineHeight = 17.sp
                                )
                            },
                            enabled = false,
                            onClick = {}
                        )
                    } else {
                        options.forEach { option ->
                            val isSelected = option.value == selectedValue
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.label,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.field.body
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                trailingIcon = { if (isSelected) SelectedTick() },
                                onClick = { onSelect(option.value); menuOpen = false }
                            )
                        }
                    }
                    // LAST, and behind a rule. A menu item that makes a record is not one of the
                    // answers to "which of these?", and putting it above or among them is how a
                    // mis-hit on a short list opens a create form instead of picking the neighbour.
                    //
                    // ASKED WITH `""`, because this surface has no filter box: there is nothing
                    // typed here and never can be. A fixed-label action answers as it always did; a
                    // term-aware one answers `null` and draws nothing, which is correct — see
                    // [SelectCreateAction].
                    val createRowLabel = createAction?.label("")
                    if (createAction != null && createRowLabel != null) {
                        HorizontalDivider(color = MaterialTheme.field.hairline)
                        DropdownMenuItem(
                            text = {
                                Text(
                                    createRowLabel,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            // Closed FIRST. The action opens a full-screen record form in its own
                            // window, and an anchored menu left standing under it is still there when
                            // the form is dismissed — over a list that no longer describes the data.
                            onClick = { menuOpen = false; createAction.onClick("") }
                        )
                    }
                }
            }
        }
        // Under the control and inside the same Column, so it moves with the field on a form that
        // reflows and a researcher reading top to bottom meets the label, the greyed control and the
        // reason for it in that order. `field.muted` at 12sp is the voice the multi-select already
        // uses for its own replacement sentence a few hundred lines below — one shape for one fact,
        // whichever of the two controls is carrying it.
        standDownLine?.let { line ->
            Text(line, color = MaterialTheme.field.muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }

    if (sheetOpen) {
        SearchablePickerSheet(
            // THE MARK IS STRIPPED FROM THE SHEET'S HEADING. It is a form control's mark, and the
            // heading of a sheet is not a form control: it names the list being browsed, and
            // "Craft *" over a list of crafts marks nothing the reader can act on. This file
            // already drew that line once — the search box below the heading is labelled just
            // "Search" rather than "Search $title" because a required field "produced 'Search
            // Craft *'" — so stripping it from the heading itself is the same rule applied one
            // level up. Nothing is lost to TalkBack: the trigger that opened this sheet announces
            // the label WITH its mark (see its `speech`).
            title = dwWithoutRequiredMark(label),
            options = options,
            selected = if (selectedValue.isBlank()) emptySet() else setOf(selectedValue),
            multiple = false,
            // Not `useSheet`, and not the override either: the sheet is reachable from this field
            // ONLY down the branch that decided the control is searchable, so by the time it opens
            // the answer is true by construction. A `false` here would open a sheet with no box,
            // which is a shape this field never takes — `searchable = false` keeps the menu.
            searchable = true,
            noneLabel = if (includeNone) placeholder else null,
            emptyMessage = emptyMessage ?: GENERIC_EMPTY_LINE,
            createAction = createAction,
            onDismiss = { sheetOpen = false },
            onApply = { next -> onSelect(next.firstOrNull().orEmpty()) }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Multi select
// ---------------------------------------------------------------------------------------------

/**
 * A many-of-many field: a summary trigger, the chosen rows as chips beneath it, and the same sheet.
 *
 * The sheet is used at EVERY length here, unlike the single-select. What it replaces is a wall of
 * checkboxes rendered straight into the form — which has no summary line, so the only way to see
 * what is ticked is to scroll the form back over it, and no room for a Select-all row without
 * pushing the next field further down a page that is already long. The chips give the form back its
 * at-a-glance reading without the wall.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchableMultiSelectField(
    label: String,
    options: List<SelectOption>,
    selected: Set<String>,
    modifier: Modifier = Modifier,
    placeholder: String = "Select",
    /**
     * What this control says when there is nothing to tick — the caller's sentence, in both of the
     * two places it can appear: REPLACING the trigger on the form when there is no [createAction],
     * and inside the sheet's empty arm when there is one and the trigger therefore survives.
     *
     * THE DEFAULT IS A KNOWN OFFENDER AND IT IS DELIBERATELY LEFT ALONE HERE. "No options
     * available." is a claim about what exists, made by a shared primitive that cannot possibly
     * know: on a handset the same empty list means "this device has not received it yet" far more
     * often than it means "there are none". Changing the default would silently re-word every
     * caller that relies on it, which is the sort of change that has to be made one call site at a
     * time with the reason for each — so the fix belongs to the screens, and the five sentences
     * they choose between are set out at the top of this file. Pass one. Every caller in this
     * repository that has thought about it already does.
     *
     * AND IT STAYS NON-NULL WHERE [SearchableSelectField.emptyMessage] WENT NULLABLE. The two are
     * not the same job. There, the string was being SPOKEN from a closed trigger that is read out
     * whether or not anybody asked, so a default was a claim this file had manufactured. Here it is
     * a visible line that REPLACES the control on the form, so some string has to exist or an empty
     * multi-select goes back to being a blank gap between two labels — and the one state in which
     * it is spoken instead, an empty list with a [createAction], is a control the reader has
     * deliberately opened.
     */
    emptyMessage: String = "No options available.",
    enabled: Boolean = true,
    /**
     * Whether the sheet draws its filter box. `null` — the default — keeps it, which is what this
     * control has done at every length since it was written.
     *
     * IT DOES NOT MEAN QUITE WHAT IT MEANS ON [SearchableSelectField], and that difference is why
     * the parameter is nullable on both rather than a `Boolean` with a default. The multi-select
     * has no anchored-menu branch to fall back to — the note above says why a wall of checkboxes is
     * not an option — so the sheet is the surface at every length, and `searchable` here can only
     * be about the box inside it. `null` therefore means the same thing on both: "this control's
     * own long-standing rule", which happens to be the count on one and "always" on the other.
     *
     * Pass `false` for a closed ladder short enough to read at a glance — eight roles, five
     * statuses — where a filter box is a row of chrome and a keyboard above a list nobody needs to
     * filter. Pass it for the same reason a single-select does: so that the day a tier is added or
     * removed, the control does not change shape underneath a reader who had learnt it.
     */
    searchable: Boolean? = null,
    /**
     * Whether the sheet offers "Select all N shown" and "Clear all". `true` — the default — is
     * exactly what every caller written before this parameter has today.
     *
     * PASS `false` ON ANYTHING THAT FILTERS A LIST RATHER THAN ANSWERING A FORM FIELD. A filter
     * says "everything" BY ABSENCE: nothing ticked is the unfiltered state and it is the only
     * spelling of it. Select-all hands the reader a second spelling — every row ticked — that means
     * the same thing to the query and something quite different to the next person to read the
     * screen, and once both exist there is no way to tell a default apart from a deliberate choice,
     * nor to write down which one a saved view meant. The web's scope control sends `undefined`
     * rather than an empty string for the same reason, and the roster filters on both clients are
     * bound by it.
     *
     * It is published by the primitive rather than by the screens that need it because this file is
     * written once and called from everywhere; its web twin is `SearchableMultiSelectProps.bulk`,
     * same name, same default.
     */
    bulk: Boolean = true,
    /** See [SearchableSelectField]'s own parameter — same action, same place, same reason. */
    createAction: SelectCreateAction? = null,
    onSelectedChange: (Set<String>) -> Unit
) {
    var sheetOpen by remember { mutableStateOf(false) }
    val chosen = options.filter { it.value in selected }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        /*
         * THE ONE LABEL IN THE APP WHOSE MARK IS NOT AT THE END OF THE LINE. This control prints a
         * live count after the label, so the string on screen is "Crafts * (3 selected)" and
         * [requiredMarked] — which splits a TRAILING " *" — would find nothing to paint if it were
         * handed the whole line. Marking the label first and concatenating the count afterwards is
         * what keeps the mark red here, and it is the right split on its own terms: the asterisk
         * belongs to the field, not to the count of what is in it.
         */
        Text(
            requiredMarked(label) + AnnotatedString(" (${chosen.size} selected)"),
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )
        // THE TRIGGER SURVIVES AN EMPTY LIST WHEN THERE IS SOMETHING TO CREATE, and that exception is
        // the whole point of it. An empty roster is the state in which a designer most needs to add
        // the artisan standing in front of them; replacing the control with a sentence would take the
        // one action that answers it away at exactly the moment it is wanted.
        if (options.isEmpty() && createAction == null) {
            Text(emptyMessage, color = MaterialTheme.field.muted, fontSize = 12.sp)
        } else {
            SelectTrigger(
                // THE REQUIRED MARK STAYS IN THIS STRING, as it does in the single-select's
                // `speech` a few hundred lines above and for the identical reason: the mark is
                // red on screen, red is not audible, and this control has no `required`
                // parameter to announce it from. `label`, never `dwWithoutRequiredMark(label)`.
                speech = buildString {
                    append(label)
                    append(". ")
                    if (chosen.isEmpty()) {
                        append("Nothing selected")
                    } else {
                        append(
                            "${chosen.size} of ${options.size} selected: " +
                                chosen.joinToString { it.label }
                        )
                    }
                    // ONLY REACHABLE WITH A createAction, and that is what stops it being said
                    // twice: without one, an empty list replaces this trigger with [emptyMessage]
                    // in the branch above, and a screen reader would then hear the same sentence
                    // from the text and again from the button beside it.
                    if (options.isEmpty()) {
                        append(". ")
                        append(emptyMessage)
                    }
                },
                text = if (chosen.isEmpty()) placeholder else "${chosen.size} of ${options.size} selected",
                hasSelection = chosen.isNotEmpty(),
                enabled = enabled,
                onClick = { sheetOpen = true }
            )
            if (chosen.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    // Cleared and re-set: the trigger above already reads every chip aloud, so
                    // letting TalkBack walk them again is the same list twice. They are a glance
                    // aid; removal happens in the sheet, where the row carries its checked state.
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "" }
                ) {
                    chosen.forEach { option ->
                        Text(
                            option.label,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }

    if (sheetOpen) {
        SearchablePickerSheet(
            // Stripped, exactly as the single-select strips it — see the note on the other
            // `SearchablePickerSheet` call in this file.
            title = dwWithoutRequiredMark(label),
            options = options,
            selected = selected,
            multiple = true,
            // `?: true` and NOT the count: this control has always drawn its box at every length,
            // and resolving `null` through [SEARCH_THRESHOLD] here would take the box away from
            // every multi-select in the app that happens to be holding fewer than eight rows today
            // — a silent change of shape in callers that never asked for one.
            searchable = searchable ?: true,
            noneLabel = null,
            emptyMessage = emptyMessage,
            createAction = createAction,
            bulk = bulk,
            onDismiss = { sheetOpen = false },
            onApply = onSelectedChange
        )
    }
}

/**
 * The picker with no trigger of its own, for a field that already has one of a shape a button
 * cannot take — the phone field's ISD box, which is a text field measured to the widest dial code.
 * Single-select: the tap commits and closes.
 *
 * [onSelect] must NOT close the sheet itself; [onDismiss] fires once the sheet has finished sliding
 * away, and a caller that drops the sheet out of composition on select cuts that animation short.
 */
@Composable
fun SearchableSelectSheet(
    title: String,
    options: List<SelectOption>,
    selectedValue: String = "",
    /**
     * As [SearchableMultiSelectField]'s, and for the same reason: this is a sheet at every length,
     * so `null` keeps the box, which is what this has always drawn. `false` is for a short closed
     * ladder mounted in a field a button cannot be.
     */
    searchable: Boolean? = null,
    /**
     * As [SearchableSelectField]'s, minus the nullability, and the difference is not an oversight.
     * This composable IS the opened surface — it has no closed trigger to be read out unasked, so
     * there is no state in which a fallback could be mistaken for something the caller asserted,
     * and something must be drawn. A caller whose list comes from records should still say which of
     * the five empty states it is in, here as much as anywhere.
     */
    emptyMessage: String = GENERIC_EMPTY_LINE,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    SearchablePickerSheet(
        title = title,
        options = options,
        selected = if (selectedValue.isBlank()) emptySet() else setOf(selectedValue),
        multiple = false,
        searchable = searchable ?: true,
        noneLabel = null,
        emptyMessage = emptyMessage,
        onDismiss = onDismiss,
        onApply = { next -> onSelect(next.firstOrNull().orEmpty()) }
    )
}

// ---------------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------------

/** The tick that marks the chosen row. Purple-700 is the app's only action colour. */
@Composable
private fun SelectedTick() {
    Icon(
        Icons.Filled.Check,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp)
    )
}

/**
 * The closed field. Same outlined button and caret the app's dropdowns have always had, so
 * adopting the sheet does not redraw thirty-odd forms.
 */
@Composable
private fun SelectTrigger(
    speech: String,
    text: String,
    hasSelection: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            // A button is already a merged semantics node, so naming it here REPLACES the child
            // text rather than adding to it — which is what lets one description carry both the
            // field name and the selection.
            .semantics {
                contentDescription = speech
                role = Role.DropdownList
            }
    ) {
        Text(
            text,
            color = if (hasSelection) MaterialTheme.field.body else MaterialTheme.field.placeholder,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.field.muted,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * The picker itself.
 *
 * Single-select commits and closes on the first tap — a second "Done" would be a tap that can only
 * confirm what the researcher just did. Multi-select holds a DRAFT of the selection and commits on
 * Done, because ticking six artisans against a form that re-derives its options on every change
 * (the tool form drops artisans when their craft is unticked) would otherwise pull rows out from
 * under the finger mid-list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SearchablePickerSheet(
    title: String,
    options: List<SelectOption>,
    selected: Set<String>,
    multiple: Boolean,
    /**
     * Whether the filter box is drawn. Resolved by the caller, never here: the two public fields
     * answer `null` differently — the count on the single-select, "always" on the multi — and
     * re-deriving it inside would give one of them the other's rule.
     */
    searchable: Boolean,
    noneLabel: String?,
    /** The caller's sentence for an empty list. See [SearchableSelectField.emptyMessage]. */
    emptyMessage: String,
    createAction: SelectCreateAction? = null,
    /** Whether the bulk row is offered at all. See [SearchableMultiSelectField]'s parameter. */
    bulk: Boolean = true,
    onDismiss: () -> Unit,
    onApply: (Set<String>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(selected) }

    val terms = queryTerms(query)
    val filtered = remember(options, query) { options.filter { it.matches(terms) } }
    val searching = terms.isNotEmpty()

    // Only meaningful while filtering. Unfiltered, "the first row" is whatever happens to sort
    // first and committing it on a stray IME tap would be a silent wrong answer.
    val highlighted = if (searching) filtered.firstOrNull() else null

    fun close() {
        // Hide first so the sheet slides away rather than vanishing, matching the filter sheet on
        // the search screen; the flag drops once the animation has actually finished.
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }

    fun commitSingle(value: String) {
        onApply(if (value.isBlank()) emptySet() else setOf(value))
        close()
    }

    fun toggle(value: String) {
        draft = if (value in draft) draft - value else draft + value
    }

    /**
     * What the IME's action key does — the phone's stand-in for Enter, which a handset has not got.
     *
     * Single: commit the one highlighted row and leave. Multi: tick it and CLEAR THE QUERY, keeping
     * the keyboard and the focus, so "bagru ⏎ jaipur ⏎ akola ⏎" ticks three without a tap in
     * between. Clearing is the whole trick — leaving the query would leave the researcher staring
     * at the row they just ticked with no room for the next name.
     */
    fun onImeAction() {
        val row = highlighted ?: return
        if (multiple) {
            toggle(row.value)
            query = ""
            scope.launch { listState.scrollToItem(0) }
        } else {
            commitSingle(row.value)
        }
    }

    // THE `searchable` GUARD IS A CRASH AND NOT A TIDINESS. [FocusRequester.requestFocus] throws
    // IllegalStateException("FocusRequester is not initialized") when the requester was never
    // attached to a node, and with `searchable = false` the OutlinedTextField holding it is not
    // composed at all — so a closed ladder long enough to clear [AUTOFOCUS_THRESHOLD] would take
    // the picker down on the frame it opened, on the one code path a short vocabulary is supposed
    // to make simpler.
    LaunchedEffect(Unit) {
        if (searchable && options.size >= AUTOFOCUS_THRESHOLD) focusRequester.requestFocus()
    }

    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * SHEET_HEIGHT_FRACTION
    val visibleUnselected = filtered.count { it.value !in draft }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // ORDER MATTERS, and the other way round is wrong. A padding modifier subtracts
                // from the constraints it passes inward and adds the same back to the size it
                // reports outward, so with `imePadding()` on the outside the cap only ever sees the
                // content — the sheet then reports content PLUS a 500dp keyboard and grows to the
                // full height of the screen, sliding its own drag handle up under the status bar
                // with no strip of the form left visible behind it. Capping first bounds the whole
                // node, keyboard included, so 12% of the screen stays visible whatever the IME does
                // and the researcher can still see which form they are picking into.
                .heightIn(max = maxSheetHeight)
                // Still outside the LIST, though: the keyboard has to SHRINK the rows rather than
                // pad them, or the Done bar ends up underneath the IME. The sheet has its own
                // window, which the activity's inset handling does not reach.
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    title,
                    display = true,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
                )
                // THE COUNT LINE BELOW STAYS WHEN THIS GOES, deliberately. It is the live region
                // that tells a screen-reader user how many rows are under their thumb, and it is
                // the only place an empty sheet says "0 options" out loud; dropping it with the box
                // would take the count away from precisely the short lists that were given
                // `searchable = false` because they are read at a glance — by everyone except the
                // reader who cannot see them.
                if (searchable) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        // Just "Search", not "Search $title". A field label in this app is a whole
                        // phrase — "Artisans of selected crafts", "State / union territory" — and
                        // prefixing it wrapped the label onto two lines, which grows the box and, on
                        // a required field, produced "Search Craft *". The heading directly above
                        // already names the list, and it is the first thing TalkBack lands on inside
                        // the sheet, so the short label loses nothing.
                        //
                        // THE SECOND HALF OF THAT REASON EXPIRED ON 2026-08-30 and the rule did
                        // not. Both callers now hand this sheet a [dwWithoutRequiredMark] title, so
                        // "Search Craft *" is no longer reachable from here — but the wrapping is,
                        // and the wrapping was always the bigger of the two complaints.
                        label = { Text("Search") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                tint = MaterialTheme.field.muted,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Clear search",
                                        tint = MaterialTheme.field.muted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onImeAction() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }
                // A FlowRow and not a Row: "Select all 74 shown" and "Clear all" beside a count are
                // about 230dp at font scale 1 and will not fit a 360dp screen at font scale 2, and
                // a Row answers that by drawing off the edge. Wrapping is the only answer that
                // holds at every scale, and at scale 1 it is still one line.
                FlowRow(
                    verticalArrangement = Arrangement.Center,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        countLine(filtered.size, options.size, searching),
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            // Polite, not Assertive: it fires on every keystroke, and a screen
                            // reader that interrupts its own echo of the letter just typed makes
                            // the field unusable.
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    // `bulk` AND NOT JUST `multiple`: a control that FILTERS a list rather than
                    // answering a form field must express "everything" by absence, and Select-all
                    // is a second spelling of it that the reader cannot tell apart from a
                    // deliberate choice to tick every row. See [SearchableMultiSelectField.bulk].
                    if (multiple && bulk) {
                        // SELECT ALL TAKES THE VISIBLE ROWS, NOT THE WHOLE LIST — and the count in
                        // the label is what says so, in both states, which is why the word "shown"
                        // stays there even when nothing is filtered. Filtering to "Bagru" and
                        // ticking the nine that match is the reason to have a search box in a
                        // multi-select at all; a Select-all that quietly reached past the filter
                        // and took all 74 tools would be the one action in the app capable of
                        // undoing a careful search in a single tap. Clear all is deliberately the
                        // OTHER way round — it empties the selection entirely, filtered or not,
                        // because a half-cleared selection you cannot see is not an escape hatch.
                        // Same wording and same split as the web's assignment builder.
                        TextButton(
                            onClick = { draft = draft + filtered.map { it.value } },
                            enabled = visibleUnselected > 0
                        ) { Text("Select all ${filtered.size} shown", fontSize = 12.sp) }
                        TextButton(
                            onClick = { draft = emptySet() },
                            enabled = draft.isNotEmpty()
                        ) { Text("Clear all", fontSize = 12.sp) }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.field.hairline)

            LazyColumn(
                state = listState,
                // fill = false so a five-row list makes a five-row sheet; the cap above is what
                // stops a 200-row one from trying to be taller than the screen.
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 6.dp,
                    bottom = 6.dp
                )
            ) {
                // NO ITEM KEYS anywhere in this list, deliberately. A key has to be unique and
                // an option value need not be: the country list gives twenty countries the dial
                // code "+1", and a language list can offer a record's existing language twice.
                // Keyed on the value, LazyColumn throws on the duplicate — the picker would crash
                // on exactly the longest lists it exists for. Falling back to position keys costs
                // only scroll-position stability across a filter change, and jumping back to the
                // top is what filtering should do anyway.
                if (noneLabel != null && !searching) {
                    item {
                        PickerRow(
                            label = noneLabel,
                            hint = null,
                            selected = draft.isEmpty(),
                            multiple = false,
                            isHighlighted = false,
                            muted = true,
                            onClick = { commitSingle("") }
                        )
                    }
                }
                items(filtered) { option ->
                    PickerRow(
                        label = option.label,
                        hint = option.hint,
                        selected = option.value in draft,
                        multiple = multiple,
                        isHighlighted = option.value == highlighted?.value,
                        muted = false,
                        onClick = { if (multiple) toggle(option.value) else commitSingle(option.value) }
                    )
                }
                if (filtered.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Two different facts, and they must not share a sentence — the
                            // argument, and the reason the second of them is the CALLER'S string
                            // rather than a hard-coded one, is written on [pickerEmptyLine]. It is
                            // a shared function and not a copy of the ternary that used to sit here
                            // because the anchored menu now prints the same thing, and two spellings
                            // of one sentence is how a list that crosses eight starts telling the
                            // reader two different stories about itself.
                            Text(
                                pickerEmptyLine(searching, query, emptyMessage, options.isEmpty()),
                                color = MaterialTheme.field.muted,
                                fontSize = 13.sp,
                                lineHeight = 17.sp
                            )
                            if (searching) {
                                TextButton(onClick = { query = "" }) { Text("Clear search") }
                            }
                        }
                    }
                }
            }

            /*
             * THE ESCAPE, under the list and above the commit bar.
             *
             * Under the list because it is not one of the answers — see [SelectCreateAction]. Above
             * the Done bar because Done is what closes a multi-select, and an action that opens a
             * form has to be reachable without the thumb passing over it.
             *
             * The sheet is dismissed before the action runs. It is a window of its own; leaving it up
             * under a full-screen record form means the designer comes back to a picker whose options
             * were fetched before the record they just made existed.
             *
             * THE TERM IS HANDED IN, TRIMMED, and it is what lets the row read *Use “Bagru winter
             * 2026” as the name* rather than a sentence that does not name the answer back. It is
             * also what decides whether the row is drawn at all: a caller whose label answers `null`
             * for this term wants no row for it. Both halves are [SelectCreateAction]'s.
             *
             * TRIMMED HERE AND IN ONE PLACE, so the string the row QUOTES is byte-identical to the
             * string [SelectCreateAction.onClick] is handed. Quoting the raw box while committing a
             * trimmed one would show a reader a name with a trailing space and store one without —
             * and on this field a name is the whole answer.
             */
            val createTerm = query.trim()
            val createRowLabel = createAction?.label(createTerm)
            if (createAction != null && createRowLabel != null) {
                HorizontalDivider(color = MaterialTheme.field.hairline)
                TextButton(
                    onClick = { close(); createAction.onClick(createTerm) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 8.dp)
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        createRowLabel,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (multiple) {
                HorizontalDivider(color = MaterialTheme.field.hairline)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        // The sheet's own bottom inset is consumed by imePadding once the keyboard
                        // is up; with it down, this is what keeps Done off the gesture bar.
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    OutlinedButton(
                        onClick = { close() },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("Cancel") }
                    Button(
                        onClick = { onApply(draft); close() },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("Done · ${draft.size} selected", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

/**
 * "12 of 74 match" while filtering, plain "74 options" otherwise. Read aloud on every keystroke.
 *
 * THE `total == 0` ARM IS THE LIVE REGION'S HALF OF [pickerEmptyLine]'S RULE, and it has to come
 * first. This line is announced by TalkBack on every keystroke, so without an arm of its own an
 * empty list answered the first letter typed with "No matches": the sentence in the body of the
 * sheet having just been made to go on saying WHY the list is empty, the one string that speaks
 * without being asked would have carried on reporting a failed search over a list that has no
 * members and therefore cannot fail one. "0 options" is the same weak, true thing the sheet says
 * before a key is pressed, and it does not move under typing because the fact it reports does not.
 *
 * `internal` for the reason [pickerEmptyLine] is: this is what a screen-reader user actually
 * hears, and it is asserted in `SearchableSelectEmptyStateTest` rather than looked at, because the
 * JVM suite cannot compose a sheet.
 */
internal fun countLine(shown: Int, total: Int, searching: Boolean): String = when {
    total == 0 -> "0 options"
    !searching -> if (total == 1) "1 option" else "$total options"
    shown == 0 -> "No matches"
    else -> "$shown of $total match"
}

/**
 * One row of the picker.
 *
 * The whole row is the target, not the checkbox: `toggleable`/`selectable` on the Row is what gives
 * TalkBack a single node with the right role and its checked state, and the Checkbox is handed a
 * null callback so it stops being a focus stop of its own. A 48dp floor because these rows sit in a
 * scrolling list where a mis-hit picks the neighbour.
 */
@Composable
private fun PickerRow(
    label: String,
    hint: String?,
    selected: Boolean,
    multiple: Boolean,
    isHighlighted: Boolean,
    muted: Boolean,
    onClick: () -> Unit
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 1.dp)
        .background(
            if (isHighlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            RoundedCornerShape(8.dp)
        )
        .let {
            if (isHighlighted) {
                it.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
            } else {
                it
            }
        }
        .let {
            if (multiple) {
                it.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
            } else {
                it.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            }
        }
        .heightIn(min = 48.dp)
        .padding(horizontal = 8.dp, vertical = 6.dp)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = rowModifier) {
        if (multiple) {
            Checkbox(checked = selected, onCheckedChange = null)
            Spacer(Modifier.size(8.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                label,
                color = when {
                    muted -> MaterialTheme.field.muted
                    selected && !multiple -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.field.body
                },
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            )
            hint?.let {
                Text(it, color = MaterialTheme.field.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (isHighlighted) {
            // Says what the keyboard's action key is about to do, so committing with it is never a
            // guess about which row "the highlighted one" means.
            Icon(
                Icons.AutoMirrored.Filled.KeyboardReturn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
        if (selected && !multiple) {
            Spacer(Modifier.size(6.dp))
            SelectedTick()
        }
    }
}
