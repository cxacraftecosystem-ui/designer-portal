package com.designprototype.workshop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * THE FILTER AND SORT CONTROLS BOTH ADMIN ROSTERS SHARE — the Kotlin twin of the web's
 * `components/admin/RosterFilterBar.tsx`, over the grammar in `RosterFilters.kt`.
 *
 * ── ONE COMPONENT FOR TWO SCREENS, FOR THE REASON THE WEB GIVES ─────────────────────────────────
 *
 * `AccessRoster` and `DesignerRoster` are two tables with two jobs, and the screens over them are
 * deliberately separate. The CONTROLS are not: a search box, some multi-selects, one date range and
 * one order. Written twice they word one thing two ways, and the first thing an admin learns from a
 * screen that describes the same cut in two different sentences is that neither sentence means much.
 *
 * ── WHAT IS A CHIP AND WHAT IS IN THE SHEET, AND WHY ────────────────────────────────────────────
 *
 * The allow-list's four standing chips stay on the screen. They are the one filter an admin toggles
 * constantly — the whole screen is "decide about these people" — and burying them one tap down would
 * cost the screen its shape. Everything else lives behind one "Filters" button: on a handset in
 * portrait, nine role rows plus three date controls plus an order picker is more chrome than list.
 * §4.9 rules exactly this split and the web's own bar makes the same one.
 *
 * ── EVERY CONTROL IS A QUERY PARAMETER, AND NONE OF THEM TOUCHES A ROW ──────────────────────────
 *
 * There is no predicate over a fetched page anywhere in this file or in the two screens that call it.
 * That is rule (iv) of §4.6 and it is not a style preference: a client-side box over a
 * server-truncated page answers "no matches" about records that exist, which is the defect this
 * repository has already closed four times in one picker. `RosterFilterWireTest` asserts the absence.
 *
 * ── ACCESSIBILITY IS A REQUIREMENT OF REQ 30 AND IS HANDLED HERE, NOT PER SCREEN ────────────────
 *
 * Every control below has a real label from [RosterLabels], and every label is BOTH the visible text
 * and the spoken name — one constant used twice, so a control that shows one word and announces
 * another is unspellable. The two search boxes carry two constants rather than one, a short name and
 * the sentence naming their three columns, and BOTH of those are visible and both are spoken; see
 * [RosterLabels.SEARCH] for why the sentence cannot be the label on a handset.
 *
 * Chips and icon buttons name themselves from their child text and would otherwise announce
 * "Filters · 3" for a control that is really "Filters, 3 set, opens the filter and order sheet" —
 * a label a voice-control user cannot say and a screen-reader user cannot act on. Each of those
 * carries an explicit `contentDescription` giving the state and the action. Nothing here
 * uses a fixed height or a fixed text size in `dp`, so the largest system font scale grows the sheet's
 * scrolling content instead of clipping it — the sheet scrolls and the range dialog behind
 * `FieldDateField` is Material's full-screen one for that reason.
 */

// ---------------------------------------------------------------------------------------------
// The institution vocabulary, and the five sentences over it
// ---------------------------------------------------------------------------------------------

/**
 * What this device knows about `GET /designers/roster/institutions`.
 *
 * THREE STATES AND NOT A NULLABLE LIST, because "not fetched yet", "the read failed" and "the roster
 * records no institutions" are three different facts with three different next moves, and a
 * `List<String>?` can only tell two of them apart. This is §3.5's split applied one control down —
 * the same shape `WorkshopListState` takes for the workshop pickers.
 */
sealed interface InstitutionVocabulary {
    /** Asked for, no answer yet. */
    data object Loading : InstitutionVocabulary

    /**
     * The read did not answer.
     *
     * [online] is the OUTBOX's classification and not a network probe — `WorkshopRepository.isTransient`
     * treats an `IOException` or a 401/408/429/5xx as "this device could not reach the server" and
     * everything else as an answered refusal. The two get different sentences because they have
     * different next moves, and a 404 here is the ordinary shape of "the server has not shipped this
     * endpoint yet", which is an answer.
     */
    data class Failed(val online: Boolean) : InstitutionVocabulary

    /** The read answered. [truncated] is the server's own flag: null is "it said nothing". */
    data class Listed(val names: List<String>, val truncated: Boolean?) : InstitutionVocabulary
}

/**
 * The one sentence the institution picker prints, or null when it has nothing to say.
 *
 * §3.5's five, with the noun fixed at "institutions". The genuinely-empty arm is the UNSCOPED one —
 * this list is not narrowed by any grant, so an empty answer really is a statement about the roster
 * and its next move is to record an institution on somebody's row, not to ask an administrator.
 *
 * It returns null once there are names, because a control that is doing the obvious thing needs no
 * explanation; the CAP, which is a different fact, is [institutionCutNotice]'s and is printed
 * alongside rather than instead.
 */
fun institutionEmptyLine(state: InstitutionVocabulary): String? = when (state) {
    InstitutionVocabulary.Loading -> loadingListLine("institutions")
    is InstitutionVocabulary.Failed ->
        if (state.online) couldNotListLine("institutions") else offlineListLine("institutions")
    is InstitutionVocabulary.Listed ->
        // The reserved "No institution recorded" row is always appended by `institutionOptions`, so
        // the picker is never literally empty. The sentence is therefore keyed on the SERVED names:
        // a panel holding only the reserved row reads as "one institution value exists and it is
        // 'none'", which is absence presented as a fact about the repository.
        if (state.names.isEmpty()) unscopedEmptyLine("institutions") else null
}

// ---------------------------------------------------------------------------------------------
// The button, and the sheet behind it
// ---------------------------------------------------------------------------------------------

/**
 * The "Filters" chip, with the count of what is hidden behind it.
 *
 * The number is [sheetFilterCount], which deliberately does NOT count the allow-list's status chips:
 * they are on screen whether the sheet is open or shut, and a badge counting something already
 * visible reads as a second, disagreeing filter.
 */
@Composable
fun RosterFilterButton(kind: RosterKind, filters: RosterFilters, onOpen: () -> Unit) {
    val hidden = sheetFilterCount(kind, filters)
    val text = if (hidden > 0) "${RosterLabels.FILTERS} · $hidden" else RosterLabels.FILTERS
    FilterChip(
        selected = hidden > 0,
        onClick = onOpen,
        label = { Text(text) },
        leadingIcon = {
            Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(16.dp))
        },
        // A chip names itself from its child text, which would announce "Filters · 3" — a label a
        // voice-control user cannot say and a screen-reader user cannot act on. This says the state
        // and the action instead.
        modifier = Modifier.semantics {
            contentDescription = if (hidden > 0) {
                "Filters, $hidden set. Opens the filter and order sheet."
            } else {
                "Filters. Opens the filter and order sheet."
            }
        }
    )
}

/**
 * The order chip, which sits beside the filter one and says what the list is currently ordered by.
 *
 * ON SCREEN RATHER THAN ONLY IN THE SHEET, because an order the reader cannot see is an order they
 * will attribute to the data. A list that opens "newest first" and a list that has been sorted "A to
 * Z" look equally arbitrary from the top of a card stack, and the second one silently changes what
 * "the rows above the fold" means.
 */
@Composable
fun RosterSortButton(kind: RosterKind, filters: RosterFilters, onOpen: () -> Unit) {
    val spec = rosterSortSpec(kind, filters.sort)
    val phrase = spec?.let { sortDirectionPhrase(it.values, filters.dir) }
    val label = spec?.label ?: filters.sort
    FilterChip(
        selected = filters.sort != ROSTER_DEFAULT_SORT || filters.dir != ROSTER_DEFAULT_DIR,
        onClick = onOpen,
        label = { Text(if (phrase != null) "$label · $phrase" else label) },
        leadingIcon = {
            Icon(Icons.Filled.SwapVert, contentDescription = null, modifier = Modifier.size(16.dp))
        },
        modifier = Modifier.semantics {
            contentDescription = if (phrase != null) {
                "${RosterLabels.SORT}: $label, $phrase. Opens the filter and order sheet."
            } else {
                "${RosterLabels.SORT}: $label. Opens the filter and order sheet."
            }
        }
    )
}

/**
 * Everything that is not a chip: the tiers, the standing, the institutions, the date range and the
 * order.
 *
 * ── IT COMMITS ON EVERY CHANGE AND NEVER ON A "DONE" ────────────────────────────────────────────
 *
 * [onChange] fires as each control moves, and the screen behind the sheet re-requests on a debounce.
 * A sheet that batched its answer behind an Apply button would let an admin set four filters, close
 * it, and read a list that had not been re-fetched — and the failure mode of forgetting to press
 * Apply is a screen showing the wrong answer with nothing on it that disagrees. Nothing here is
 * expensive: each change is one request against one indexed page.
 *
 * ── THE PAGER IS THE SCREEN'S AND IS RESET BY THE SCREEN ────────────────────────────────────────
 *
 * A filter or a sort change re-orders the whole list, so the rows at `OFFSET 40` are not the rows
 * that were there a moment ago. Every caller of this resets to page 1 in its [onChange]; this file
 * cannot do it because the page number is not part of [RosterFilters], deliberately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RosterFilterSheet(
    kind: RosterKind,
    filters: RosterFilters,
    onChange: (RosterFilters) -> Unit,
    onDismiss: () -> Unit,
    /** DESIGNER ONLY, and ignored on the allow-list — see [ACCESS_INSTITUTION_NOTE]. */
    institutions: InstitutionVocabulary = InstitutionVocabulary.Loading,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val access = kind == RosterKind.ACCESS
    val institutionNames = (institutions as? InstitutionVocabulary.Listed)?.names.orEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Outside the scroll, so the keyboard SHRINKS the scrollable area rather than padding
                // the content inside it — the two date boxes are the last things that should end up
                // underneath the IME, and the sheet has its own window, which the activity's inset
                // handling does not reach.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    RosterLabels.FILTERS,
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                // SHOWN ONLY WHEN SOMETHING IS SET, because a button that clears nothing is a button
                // that teaches the reader their taps do not do anything. It keeps the ORDER: an admin
                // who sorted by "first signed in" to find outstanding invitations and then clears a
                // search is still asking that question.
                if (hasActiveRosterFilters(kind, filters)) {
                    TextButton(onClick = { onChange(clearRosterFilters(kind, filters)) }) {
                        Text(RosterLabels.CLEAR_ALL)
                    }
                }
            }

            // NOTE THAT STANDING IS NOT IN HERE, ON EITHER ROSTER. Both screens keep it as CHIPS on
            // the screen itself ([AccessStatusChips], [DesignerStandingChips]) because it is the one
            // filter an admin toggles constantly, and because putting it in both places would be two
            // controls for one state — which is how a reader learns that neither of them is the real
            // one. `sheetFilterCount` does not count it for the same reason.

            // ── The tier ladder ─────────────────────────────────────────────────────────────────
            SearchableMultiSelectField(
                label = if (access) RosterLabels.ACCESS_ROLES else RosterLabels.DESIGNER_ROLES,
                options = roleOptions(kind),
                selected = filters.roles,
                placeholder = "Every tier",
                // NINE ROWS, ONE ABOVE `SEARCH_THRESHOLD`. Left to the count this control would carry
                // a filter box today and lose it the day a tier is removed, and a reader cannot learn
                // a control that changes shape. It is a closed ladder read at a glance, which is the
                // case the threshold exists to separate from a corpus.
                searchable = false,
                // NO "SELECT ALL". Ticking every row would be a second spelling of the empty state,
                // and a filter with two spellings for one state cannot tell a default from a
                // deliberate choice — rule (i), and the reason the primitive publishes this flag.
                bulk = false,
                onSelectedChange = { picked -> onChange(filters.copy(roles = picked)) }
            )
            RosterHint(
                if (access) {
                    "Nothing ticked is every tier, including the ones admitted at the default. " +
                        "The last row is that default — it is the absence of a tier, not one of them."
                } else {
                    DESIGNER_ROLE_HINT
                }
            )

            // ── Institution (designer only) ─────────────────────────────────────────────────────
            if (access) {
                RosterHint(ACCESS_INSTITUTION_NOTE)
            } else {
                val emptyLine = institutionEmptyLine(institutions)
                SearchableMultiSelectField(
                    label = RosterLabels.DESIGNER_INSTITUTIONS,
                    options = institutionOptions(institutionNames),
                    selected = filters.institutions,
                    placeholder = "Every institution",
                    // THE CALLER'S SENTENCE, NEVER THE PRIMITIVE'S DEFAULT. "No options available."
                    // over a read that 404'd is a claim about the roster made from a request that
                    // never answered. Only this file knows which of §3.5's five cases it is in.
                    emptyMessage = emptyLine ?: "No institutions are recorded on the roster.",
                    // The list is the whole answer — a served vocabulary, not one page of one — so it
                    // keeps its box at every length rather than growing and losing one as
                    // institutions are added. §3.6 row 2.
                    searchable = true,
                    bulk = false,
                    onSelectedChange = { picked -> onChange(filters.copy(institutions = picked)) }
                )
                // DRAWN HERE AND NOT LEFT TO THE PANEL'S OWN EMPTY ARM, because the panel is never
                // actually empty: `institutionOptions` always appends the reserved "No institution
                // recorded" row, so a failed read renders a picker holding exactly that one option —
                // which reads as "one institution value exists and it is 'none'". No sentence inside
                // the panel would ever be reached to correct it.
                emptyLine?.let { RosterHint(it) }
                (institutions as? InstitutionVocabulary.Listed)?.let { listed ->
                    institutionCutNotice(listed.truncated, listed.names.size)?.let { RosterNotice(it) }
                }
            }

            // ── One date range, over one named column ────────────────────────────────────────────
            SearchableSelectField(
                label = RosterLabels.DATE_FIELD,
                options = dateFieldOptions(kind),
                selectedValue = filters.dateField,
                includeNone = false,
                searchable = false,
                onSelect = { picked -> onChange(filters.copy(dateField = picked)) }
            )
            SearchableSelectField(
                label = RosterLabels.DATE_PERIOD,
                options = RANGE_OPTIONS,
                selectedValue = filters.range.id,
                // "Any time" is a real row in this list, so there is no blank above it.
                includeNone = false,
                searchable = false,
                onSelect = { picked ->
                    val next = RosterRange.entries.firstOrNull { it.id == picked } ?: RosterRange.ANY
                    onChange(filters.copy(range = next))
                }
            )
            if (filters.range == RosterRange.CUSTOM) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // A heading over the pair, because the two boxes are ONE answer and each one's own
                    // label ("From", "To") says nothing about what it bounds. The web makes this a
                    // `<fieldset>` with the same words for the same reason.
                    Text(
                        RosterLabels.DATE_RANGE,
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp
                    )
                    // ⚠ TWO `FieldDateField`s AND NOT `FieldDateRangeField`, WHICH IS WHAT §4.9 NAMES.
                    //
                    // That component is the workshop-duration FORM FIELD: it passes no `clearable`, so
                    // once a bound is typed there is no way back to having none. On a form that is
                    // correct — a workshop has two dates — and on a FILTER it is the difference
                    // between "open at that end" and "no way to stop filtering", which is rule (i)
                    // with the escape hatch removed. This is the shape `SearchFilters` already uses
                    // for its own custom range, with the identical cross-clamping: each end bounds the
                    // other, so an inverted range — which matches nothing and reads as a broken
                    // filter — cannot be entered.
                    FieldDateField(
                        label = RosterLabels.DATE_FROM,
                        value = filters.from,
                        onValueChange = { picked -> onChange(filters.copy(from = picked)) },
                        maximum = filters.to,
                        placeholder = "Any date",
                        clearable = true
                    )
                    FieldDateField(
                        label = RosterLabels.DATE_TO,
                        value = filters.to,
                        onValueChange = { picked -> onChange(filters.copy(to = picked)) },
                        minimum = filters.from,
                        placeholder = "Any date",
                        clearable = true
                    )
                }
                RosterHint(
                    "Both boxes may be left empty — a range with no bound narrows nothing and is not " +
                        "sent."
                )
            }

            // ── The order ───────────────────────────────────────────────────────────────────────
            SearchableSelectField(
                label = RosterLabels.SORT,
                options = sortOptions(kind, filters),
                selectedValue = filters.sort,
                includeNone = false,
                // Six rows on one roster and nine on the other, and it must not change shape between
                // the two screens an admin moves between. Same ruling as the ladder above.
                searchable = false,
                onSelect = { picked -> onChange(nextRosterSort(kind, filters, picked)) }
            )
            RosterHint(
                "Choosing the order it is already in reverses it. Each row says what choosing it " +
                    "will do."
            )

            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
    }
}

/**
 * The allow-list's four standing chips — kept on the screen, and now MULTI.
 *
 * ── "EVERYONE" IS A CHIP LIKE THE OTHERS AND NOT THE ABSENCE OF ONE ─────────────────────────────
 *
 * The widest view has to be somewhere an admin can get BACK to. *"A filter you can enter and not
 * leave is how a screen ends up looking empty for reasons nothing on it explains"* — the rule this
 * control was written with, and it survives the change from one choice to several.
 *
 * ── TICKING THE FOURTH COLLAPSES TO "EVERYONE", AND THAT IS RULE (i) ENFORCED RATHER THAN STATED ──
 *
 * All four ticked and none ticked return the same rows, so if both states existed the control would
 * have two spellings for one question — and there would be no way to tell a default apart from a
 * deliberate choice, nor to say what a shared filter meant. Chips make the all-ticked state one tap
 * away, so it is collapsed the moment it is reached: the four go out and "Everyone" lights up. It
 * reads as the control teaching its own rule, and it is the only place in these two screens where a
 * tap changes more than the thing tapped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccessStatusChips(selected: Set<String>, onSelect: (Set<String>) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        FilterChip(
            selected = selected.isEmpty(),
            onClick = { onSelect(emptySet()) },
            label = { Text("Everyone") },
            modifier = Modifier.semantics {
                contentDescription = if (selected.isEmpty()) {
                    "Everyone, chosen. Every standing is listed, refused and suspended included."
                } else {
                    "Everyone. Lists every standing again, refused and suspended included."
                }
            }
        )
        ACCESS_STATUS_OPTIONS.forEach { option ->
            val on = option.value in selected
            FilterChip(
                selected = on,
                // See [toggledAccessStatus] and the KDoc above: all four is the same question as
                // none, so it is spelled once. The rule is a pure function because a decision buried
                // in an onClick can only be exercised by looking at a screen.
                onClick = { onSelect(toggledAccessStatus(selected, option.value)) },
                label = { Text(option.label) },
                modifier = Modifier.semantics {
                    contentDescription = "${RosterLabels.ACCESS_STATUS}: ${option.label}, " +
                        (if (on) "ticked. Untick to widen the list." else "not ticked. Tick to narrow to it.")
                }
            )
        }
    }
}

/**
 * The designer roster's standing, as chips — the same control the allow-list gets, over a column that
 * has three answerable states rather than four.
 *
 * ── THE CHIP AND ITS RULE SURVIVE THE REWRITE, WHICH IS §4.6 (ii) ───────────────────────────────
 *
 * What stood here was a single toggle reading "Showing suspended designers" / "Active designers
 * only", and its rule was written beside it: *a FILTER and never the default*, because a suspended
 * designer hidden by default is a person an admin cannot find in order to restore them, and the only
 * way back would be re-adding an email the unique index already holds — a 409 that reads as "this
 * person is already on the roster" while the roster on screen visibly does not contain them.
 *
 * That rule is intact: the first chip is the widest view, it is selected on open, and it is a chip
 * like the others so it is somewhere an admin can get BACK to. What is NEW is the third state.
 * `isActive` is a boolean, so a toggle could only ever ask two of the three questions the column can
 * answer, and the one it could not ask — *show me only the suspended ones* — is exactly the query an
 * admin runs when somebody says they have lost access. `standing` is absent from the wire for the
 * first chip, which is the server's spelling of "both".
 *
 * A TOGGLE WOULD NOW BE TWO SPELLINGS OF ONE STATE. "Showing suspended" ON is the same request as
 * this control's first chip, so keeping both would leave the screen with no way to tell a default
 * from a deliberate choice — rule (i). One control, three rows, said once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesignerStandingChips(selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        DESIGNER_STANDING_OPTIONS.forEach { option ->
            val on = selected == option.value
            FilterChip(
                selected = on,
                onClick = { onSelect(option.value) },
                label = { Text(option.label) },
                modifier = Modifier.semantics {
                    contentDescription = "${RosterLabels.DESIGNER_STANDING}: ${option.label}" +
                        (if (on) ", chosen." else ".")
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The small shared pieces both screens draw
// ---------------------------------------------------------------------------------------------

/**
 * A cap, a truncation or an ignored parameter, said out loud.
 *
 * THE WARNING CONTAINER AND NOT THE MUTED BODY TEXT: this is the one thing on either screen that
 * changes what an EMPTY result MEANS, and a sentence carrying that has to be findable after the admin
 * has already scrolled past it once.
 */
@Composable
fun RosterNotice(text: String) {
    Text(
        text,
        color = MaterialTheme.field.onWarningContainer,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.warningContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

/** A line under a control saying what it means. Muted, because it explains rather than warns. */
@Composable
fun RosterHint(text: String) {
    Text(text, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 15.sp)
}

/** One request in flight, named. Never "Loading…" alone — a reader cannot tell which read is late. */
@Composable
fun RosterLoadingRow(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp))
        Text(label, color = MaterialTheme.field.muted, fontSize = 13.sp)
    }
}

/**
 * The state a list is in when it has no rows, as a heading and a body.
 *
 * TWO LINES AND NOT ONE, because these states are the ones this repository gets wrong: a heading a
 * reader takes in at a glance ("The list could not be loaded") and a body that says what the screen
 * is NOT claiming. Collapsed to one sentence the qualifier is the part that gets trimmed, and the
 * qualifier is the whole point.
 */
@Composable
fun RosterEmptyState(title: String, body: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Text(
            title,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp
        )
        Text(body, color = MaterialTheme.field.muted, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

/**
 * Which page of how many, and how many rows there are in all.
 *
 * THE SERVER'S NUMBERS, NEVER THE SCREEN'S. A list that says "12" when the table holds 300 is the
 * defect this repository has closed four times in one picker: the rows an admin cannot see look
 * exactly like people who were never there. Both rosters draw this now — the designer roster used to
 * gather five pages into one scroll and could only ever report what had arrived.
 */
@Composable
fun RosterPageBar(
    page: Int,
    pages: Int,
    total: Int,
    noun: String,
    busy: Boolean,
    onPage: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedButton(
            onClick = { onPage(page - 1) },
            enabled = !busy && page > 1,
            modifier = Modifier.semantics { contentDescription = "Previous page. Page $page of $pages." }
        ) { Text("Previous") }
        Text(
            "Page $page of $pages · $total $noun",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(
            onClick = { onPage(page + 1) },
            enabled = !busy && page < pages,
            modifier = Modifier.semantics { contentDescription = "Next page. Page $page of $pages." }
        ) { Text("Next") }
    }
}
