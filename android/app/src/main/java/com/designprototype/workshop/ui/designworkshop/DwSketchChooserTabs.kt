package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field

/**
 * The two tabs at the top of Sketches & prototypes — UPLOAD and REVIEW — and their panels.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY A FILE AND NOT TWO BUTTONS ON THE SCREEN
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The same argument `frontend/components/sketches/SketchTabs.tsx` opens with, and it holds harder
 * here: these two tabs are the whole navigation of the screen, so a strip that is not properly
 * keyboard- and TalkBack-operable is a screen half the readers cannot move around. Compose's
 * `TabRow`/`Tab` give the LAYOUT, the indicator and `Role.Tab` for free. They do not give the three
 * things that make the pattern work, and all three are added here:
 *
 *  1. **ONE TAB STOP FOR THE STRIP.** `Tab` is `selectable`, so out of the box every tab is a
 *     separate focus target and Tab walks through them. The roving tabindex — selected tab focusable,
 *     the rest not — is `focusProperties { canFocus = selected }` below. That is what the WAI-ARIA
 *     pattern promises and what the web half already does; the three ad-hoc strips
 *     `SketchTabs.tsx`'s header names as broken are broken in exactly this way.
 *  2. **ARROWS AND Home/End MOVE BETWEEN THEM,** wrapping at both ends, with focus following the
 *     selection — the automatic-activation form of the pattern, which is what the web chose and what
 *     a reader arrowing along a two-tab strip actually means.
 *  3. **THE SELECTION IS CARRIED BY A WORD.** House rule 4: colour never carries meaning alone. The
 *     filled tab says "showing" in its own text, and `stateDescription` says "showing" / "not
 *     showing" to the assistive layer. A purple pill among pale ones is precisely the distinction
 *     that vanishes in greyscale, in high-contrast mode and for a colour-blind reader — and on a
 *     handset in direct sunlight, which the web does not have to think about.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT `aria-controls` BECOMES ON THIS PLATFORM, AND WHY IT IS NOT A PRETEND ONE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The web pairs `role="tab"` + `aria-controls` with `role="tabpanel"` + `aria-labelledby`, addressed
 * by generated DOM ids. **Compose has no id space and no `aria-controls`.** Inventing a pair of
 * string ids here would be a comment describing a wire that is not connected to anything — the
 * failure house rule 2 exists for, and the one `SketchTabs.tsx`'s own header records making about a
 * sliding underline that never existed.
 *
 * The honest analogue is `Modifier.semantics { paneTitle = … }` on the panel, which is what
 * TalkBack announces when the content underneath a tab strip is replaced: the panel names itself
 * with the tab's own word, so a reader who has just moved the selection hears which panel arrived.
 * [DwSketchChooserTabPanel] is that, and it is the whole of what this platform offers.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE PANELS ARE MOUNTED EXCLUSIVELY, WHICH IS LOAD-BEARING RATHER THAN INCIDENTAL
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `SketchesWorkspace.tsx` states this and the reason ports exactly: the review panel coalesces its
 * arrangement push behind a quiet period and flushes on unmount, so keeping it composed behind the
 * upload tab would change WHEN a reorder is offered to the repository. The caller therefore composes
 * one panel or the other and never both, and [DwSketchChooserTabPanel] is deliberately not a pager.
 */
internal enum class DwSketchChooserTab(
    /** The word on the tab. THE WEB'S, verbatim — one screen must not be two words on two clients. */
    val label: String,
    /** One line under the strip saying what this tab is for. `SketchesWorkspace.tsx`'s `hint`. */
    val hint: String,
    val icon: ImageVector,
) {
    UPLOAD(
        label = "Upload",
        hint = "Add the drawings, photographs and 3D models of a piece to the record it belongs to.",
        icon = Icons.Filled.Upload,
    ),
    REVIEW(
        label = "Review",
        hint = "Rate the other designers' work, say what you would change, and settle the order the " +
            "pieces stand in.",
        icon = Icons.Filled.Star,
    ),
}

/**
 * The strip, its hint, and everything the pattern owes a reader who is not using a thumb.
 *
 * [enabled] false draws the strip greyed and refuses every move. It is the state where no workshop
 * is chosen yet: the tabs describe work on a workshop, and a tab that opens a panel about nothing is
 * worse than one that says it is waiting. The caller prints the reason beside it — a disabled control
 * with no explanation is the shape of a screen that looks broken, which is `DwRankableList`'s rule
 * for its own `disabledReason` and is why this parameter is not the whole story.
 */
@Composable
internal fun DwSketchChooserTabStrip(
    selected: DwSketchChooserTab,
    onSelect: (DwSketchChooserTab) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tabs = remember { DwSketchChooserTab.entries.toList() }
    /*
      ONE REQUESTER PER TAB, REMEMBERED ACROSS RECOMPOSITION. Focus has to be MOVED when the arrows
      change the selection, because the tab that was focused is about to stop being focusable — and a
      focus left on an unfocusable node is a focus that lands back at the top of the screen, which for
      a reader on a screen reader means losing their place entirely.
    */
    val requesters = remember { DwSketchChooserTab.entries.associateWith { FocusRequester() } }

    /**
     * True while an ARROW OR Home/End press is waiting for its focus to catch up.
     *
     * ── WHY THE FOCUS CANNOT BE REQUESTED IN THE KEY HANDLER, WHICH IS THE OBVIOUS PLACE ───────
     *
     * `canFocus` is DERIVED FROM THE SELECTION, and the selection is the caller's state. Calling
     * `requestFocus()` on the next tab in the same frame as `onSelect(next)` asks the focus system
     * for a node that, at that instant, is still `canFocus = false` — the composition that flips it
     * has not run yet. The request is simply declined, and the visible result is an arrow press that
     * changes the tab and drops focus out of the strip: exactly the failure the roving tabindex
     * exists to prevent, arriving through the code that implements it. So the intent is recorded
     * here and honoured from the effect below, which runs after the recomposition that made the
     * target focusable.
     *
     * ── AND ONLY FOR THE KEYBOARD ───────────────────────────────────────────────────────────────
     *
     * A tap must not pull focus. A designer using a thumb has no focus ring to move and would get
     * one appearing on the tab they just touched; a designer using a keyboard is already inside the
     * strip and has to stay there. The flag is what tells the two gestures apart.
     */
    var keyboardMove by remember { mutableStateOf(false) }

    LaunchedEffect(selected, keyboardMove) {
        if (!keyboardMove) return@LaunchedEffect
        keyboardMove = false
        // `runCatching` because a requester whose node has left the composition throws, and a tab
        // strip losing a race with its own disposal is not worth a crash.
        runCatching { requesters[selected]?.requestFocus() }
    }

    /** Move the selection and take the focus with it. The arrows and Home/End both come through here. */
    fun move(to: DwSketchChooserTab) {
        if (!enabled || to == selected) return
        keyboardMove = true
        onSelect(to)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TabRow(
            selectedTabIndex = tabs.indexOf(selected).coerceAtLeast(0),
            containerColor = MaterialTheme.field.surface50,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        ) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = tab == selected
                Tab(
                    selected = isSelected,
                    enabled = enabled,
                    onClick = { if (enabled) onSelect(tab) },
                    modifier = Modifier
                        .focusRequester(requesters.getValue(tab))
                        // THE ROVING TAB STOP. Tab moves PAST the strip; the arrows move within it.
                        .focusProperties { canFocus = isSelected && enabled }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val last = tabs.lastIndex
                            val target = when (event.key) {
                                // WRAPPING, which is what the pattern asks for and what makes a
                                // two-tab strip usable with one key rather than two.
                                Key.DirectionRight -> tabs[if (index == last) 0 else index + 1]
                                Key.DirectionLeft -> tabs[if (index == 0) last else index - 1]
                                Key.MoveHome -> tabs.first()
                                Key.MoveEnd -> tabs.last()
                                else -> null
                            } ?: return@onPreviewKeyEvent false
                            move(target)
                            // CONSUMED. Left unconsumed, the arrow ALSO reaches the focus system and
                            // moves focus a second time, out of the strip and into whatever is
                            // beside it — so one key press both changes the tab and leaves it.
                            true
                        }
                        .semantics {
                            // THE WORD, FOR THE ASSISTIVE LAYER. `Tab` already publishes `selected`,
                            // which a screen reader renders as "selected"; this is the same fact in
                            // the same words the sighted reader gets, so the two descriptions of one
                            // control cannot come to disagree.
                            stateDescription = if (isSelected) "showing" else "not showing"
                        },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(tab.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(
                                tab.label,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            // HOUSE RULE 4, ON SCREEN. The filled tab is not the only thing saying
                            // which tab is showing; this word says it too, and survives greyscale.
                            if (isSelected) {
                                Text("showing", fontSize = 10.sp, color = MaterialTheme.field.muted)
                            }
                        }
                    },
                )
            }
        }
        Text(
            selected.hint,
            color = MaterialTheme.field.muted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * The panel half of the pair — the content under the strip, named with its tab's own word.
 *
 * `paneTitle` IS THE POINT OF THE WRAPPER and the only reason it is a composable rather than a bare
 * `Column` in the caller: it is what makes a tab change ANNOUNCED rather than silent. See the file
 * header for why this, and not a pretend `aria-controls`, is the honest port.
 */
@Composable
internal fun DwSketchChooserTabPanel(
    tab: DwSketchChooserTab,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { paneTitle = tab.label },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}
