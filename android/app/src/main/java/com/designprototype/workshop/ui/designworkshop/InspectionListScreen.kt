package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.DwInspectionAttempt
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.canInspectDesignWorkshops
import com.designprototype.workshop.data.dwInspectorSearchTerm
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** The same 350ms as every other server-backed search box in this app. */
private const val INSPECTION_SEARCH_DEBOUNCE_MS = 350L

/** The server clamps to `MAX_PAGE_SIZE = 100`; twenty is a phone screen's worth of cards. */
private const val INSPECTION_PAGE_SIZE = 20

/**
 * The list page's own title.
 *
 * It must equal the label of the `NavDestination.DESIGN_WORKSHOP_INSPECTIONS` row in
 * [com.designprototype.workshop.ui.FIELD_NAV_ITEMS] — a menu row whose label differs from the
 * heading it opens sends a reader looking for a second feature. That is NOT enforced by sharing this
 * constant: the nav table is a flat list of literals and threading a UI-subpackage constant into it
 * would be the only such reference in the file. It is enforced instead by
 * `InspectionGateTest.the nav row exists, is in Browse, and reads the same predicate as the screens`,
 * which asserts the row's label against THIS constant, so changing either alone goes red.
 *
 * The words are the web's, verbatim — see the nav row's own comment on why this one wave wrote the
 * strings on the web side first.
 */
const val DW_INSPECTION_LIST_TITLE = "Workshops to inspect"

/**
 * THE INSPECTOR'S OWN LIST: the design & prototype workshops an admin has assigned them to inspect.
 *
 * ── THE LIST IS HALF THE FEATURE, AND THAT IS NOT A FIGURE OF SPEECH ─────────────────────────────
 *
 * `services/design_workshop_viewers.py` records the lesson this screen exists to honour: a scope the
 * LIST does not honour tells its holder that a workshop exists (they can open it by id) and
 * simultaneously that it does not (it is absent from every list they can reach). Nothing in either
 * client navigates to a design workshop by typed id, so without this screen an inspection row is
 * unreachable in practice — which is exactly the state this handset was in before this wave.
 *
 * ── THE SCOPE HAS EXACTLY ONE SOURCE, WHICH IS WHY THE EMPTY STATE CAN BE HONEST ─────────────────
 *
 * `GET /design-workshop-inspections` AND-composes `inspectable_by_clause` and nothing else: there is
 * no "all workshops" arm, no rank fallback and no `createdById` arm, because an inspector creates
 * nothing. So an empty answer means "no admin has assigned you anything", flatly, and the screen may
 * say so — unlike the designer's list, where an empty page could be any of several things.
 *
 * **THAT MAKES THE THREE STATES LOAD-BEARING.** `rows == null` (nothing has been asked for yet),
 * `rows == emptyList()` (the server answered, and the answer is none) and `loadError != null` (the
 * ask failed) are kept apart all the way to the screen, because an inspector must be able to tell
 * "nothing is assigned to me" from "the load failed" — and collapsing the first two is how a broken
 * connection comes to read as a withdrawn assignment.
 *
 * ── NOT CACHED, AND THE REASON IS THE SCOPE ITSELF ───────────────────────────────────────────────
 *
 * This screen does not fall back to the device and holds nothing between visits. An admin who ends
 * an inspection this morning has ended it; a cached list would go on offering a workshop this
 * account may no longer open, and the detail screen would then answer 404 to a card the app itself
 * drew. See the block comment above the inspection methods in `WorkshopRepository`.
 *
 * ── PAGED, AND DELIBERATELY NOT WALKED ───────────────────────────────────────────────────────────
 *
 * `visibleDesignWorkshops` walks up to five pages because a viewer GRANT lands an older workshop
 * past the end of page one in a `createdAt desc` list a designer otherwise fills with their own. No
 * such burial can happen here: every row is an assignment and there is no second source for them to
 * be buried under. So the inspector drives the pager, the server's `total` is on screen, and a
 * metered connection is asked for one page at a time.
 */
@Composable
fun InspectionListScreen(
    repository: WorkshopRepository,
    /** Open one workshop under inspection. The id is the SERVER's — there is no draft to resolve. */
    onOpenWorkshop: (workshopId: String) -> Unit,
) {
    val viewer = remember(repository) { repository.cachedUser() }
    // MIRRORS `assert_inspection_surface`, WHICH REFUSES AN ADMIN. Re-derived here from the cached
    // account rather than trusted from the menu: a nav entry is not a guard, and an account demoted
    // since the drawer was drawn must meet the same refusal the server would give it. Nothing is
    // REQUESTED when the answer is already known — asking the server to refuse something this client
    // knows it may not have would put an unexplained 403 in the error channel on every visit.
    val mayInspect = remember(viewer) { viewer != null && canInspectDesignWorkshops(viewer.role) }

    var page by remember { mutableIntStateOf(1) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }

    /** Null until the server has answered once. NOT the same state as an empty list — see the KDoc. */
    var rows by remember { mutableStateOf<List<DesignWorkshopDto>?>(null) }
    var total by remember { mutableIntStateOf(0) }
    var pages by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(mayInspect) }
    var loadError by remember { mutableStateOf<String?>(null) }
    /** The term the list on screen was actually fetched for — never the text in the box. */
    var answeredFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(page, reload, query, mayInspect) {
        if (!mayInspect) {
            loading = false
            return@LaunchedEffect
        }
        val term = dwInspectorSearchTerm(query)
        // Not for the first load and not for the CLEAR, which is a deliberate act rather than
        // typing: waiting 350ms to un-narrow a list the inspector has just emptied the box for reads
        // as a stuck screen.
        if (term != null && term != answeredFor) delay(INSPECTION_SEARCH_DEBOUNCE_MS)
        loading = true
        loadError = null
        runCatching {
            repository.inspectableDesignWorkshops(
                page = page,
                pageSize = INSPECTION_PAGE_SIZE,
                search = term
            )
        }
            .onSuccess { answered ->
                rows = answered.items
                total = answered.total
                pages = answered.pages
                answeredFor = term
            }
            .onFailure { error ->
                // A CANCELLED LOAD IS NOT A FAILED ONE. This effect is keyed on the search box, so
                // every keystroke cancels the request in flight; reported, it would flash a
                // connection failure over a connection that is fine, mid-word.
                if (error is CancellationException) return@onFailure
                // ROWS ARE NOT CLEARED. What was on screen is still what the server last said, and
                // blanking it would turn a dropped socket into an apparently withdrawn assignment.
                // The error renders above the list instead, with the list marked stale by the words
                // in it.
                loadError = error.inspectionFailure(DwInspectionAttempt.READ)
            }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            DW_INSPECTION_LIST_TITLE,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp
        )

        // ── The refusal, and it is the one refusal in this app an ADMIN also meets ───────────────
        if (!mayInspect) {
            InspectionNotice(
                "The inspection surface belongs to the Inspector / Reviewer tier, and is scoped to " +
                    "the workshops an admin has assigned to that account. Designers and admins read " +
                    "design & prototype workshops on Design workshops instead; an admin chooses who " +
                    "inspects a workshop from that workshop's own stage index.",
                warning = true
            )
            return@Column
        }

        Text(
            "The design & prototype workshops an admin has assigned you to inspect. You can read " +
                "every stage of one and change none of it.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )
        // Said before anything fails, not after. An inspection is read from the server every time,
        // and an inspector who does not know that reads an offline moment as a withdrawn assignment.
        Text(
            "This screen needs a connection. An inspection is read from the repository each time and " +
                "is never kept on this phone — an assignment an admin ends today has ended, and a " +
                "copy held here could not know that.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it; page = 1 },
            label = { Text("Search these workshops") },
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
                when {
                    loading -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    query.isNotEmpty() -> IconButton(onClick = { query = ""; page = 1 }) {
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
            modifier = Modifier.fillMaxWidth()
        )

        loadError?.let { InspectionNotice(it, warning = false) }

        val served = rows
        when {
            // NOTHING HAS ARRIVED YET AND THE ASK FAILED. Distinct from the empty state below and
            // rendered as an action rather than as a sentence about assignments, because nobody has
            // learned anything about their assignments yet.
            served == null && loadError != null -> OutlinedButton(
                onClick = { reload++ },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Try again") }

            served == null -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Loading…", color = MaterialTheme.field.muted, fontSize = 13.sp)
            }

            // THE SERVER ANSWERED, AND THE ANSWER IS NONE. Two different sentences, because a search
            // that matched nothing and an account with no assignments are two different facts and
            // the remedy for one is not the remedy for the other.
            served.isEmpty() && answeredFor != null -> {
                Text(
                    "No workshop under your inspection matches that search",
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp
                )
                Text(
                    "This searches only the workshops assigned to you, which is the whole of what " +
                        "you can read here. Clear the search to see them all.",
                    color = MaterialTheme.field.muted,
                    fontSize = 13.sp
                )
            }

            served.isEmpty() -> {
                Text(
                    "No workshop is assigned to you",
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp
                )
                Text(
                    "An admin assigns inspections one workshop at a time, from that workshop's own " +
                        "stage index. Until they have, there is nothing here to read — this screen " +
                        "is not hiding anything from you, and nothing failed to load.",
                    color = MaterialTheme.field.muted,
                    fontSize = 13.sp
                )
            }

            else -> {
                // THE COUNT IS THE SERVER'S `total`, NOT `served.size`. Printing the page's length
                // would tell an inspector holding 40 assignments that they have 20.
                Text(
                    "$total ${if (total == 1) "workshop" else "workshops"} assigned to you" +
                        if (answeredFor != null) ", matching this search." else ".",
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp
                )
                served.forEach { workshop ->
                    InspectableWorkshopCard(
                        workshop = workshop,
                        onOpen = { onOpenWorkshop(workshop.id) }
                    )
                }
                if (pages > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { page-- },
                            enabled = page > 1 && !loading,
                            modifier = Modifier.weight(1f)
                        ) { Text("Previous") }
                        Text(
                            "Page $page of $pages",
                            color = MaterialTheme.field.muted,
                            fontSize = 12.sp
                        )
                        OutlinedButton(
                            onClick = { page++ },
                            enabled = page < pages && !loading,
                            modifier = Modifier.weight(1f)
                        ) { Text("Next") }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * One workshop as an inspector scans it.
 *
 * The fields drawn are exactly the ones `workshop_summary` promotes to columns — craft, cluster,
 * place, dates, code and status — and no attempt is made to say anything about the STAGES, because
 * this payload carries none and inventing a progress figure from a list row is how two screens come
 * to disagree about the same workshop.
 */
@Composable
private fun InspectableWorkshopCard(workshop: DesignWorkshopDto, onOpen: () -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                // Never blank: an untitled row in a list of fourteen is a row nobody can identify,
                // and on this screen there is no second way to tell two of them apart.
                workshop.title.ifBlank { "Untitled workshop" },
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            val line = listOfNotNull(
                workshop.craftName?.takeIf { it.isNotBlank() },
                workshop.clusterName?.takeIf { it.isNotBlank() },
                listOfNotNull(
                    workshop.district?.takeIf { it.isNotBlank() },
                    workshop.state?.takeIf { it.isNotBlank() },
                ).joinToString(", ").takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (line.isNotBlank()) {
                Text(line, color = MaterialTheme.field.body, fontSize = 13.sp)
            }
            val second = listOfNotNull(
                workshop.workshopCode?.takeIf { it.isNotBlank() },
                workshop.status.takeIf { it.isNotBlank() },
                listOfNotNull(
                    workshop.startDate?.takeIf { it.isNotBlank() }?.take(10),
                    workshop.endDate?.takeIf { it.isNotBlank() }?.take(10),
                ).joinToString(" – ").takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (second.isNotBlank()) {
                Text(second, color = MaterialTheme.field.muted, fontSize = 11.sp)
            }
            // SAID ON EVERY CARD, not only on the detail screen. An inspector tapping through has
            // to know before they arrive that there is nothing here for them to do to the record —
            // and the word is what carries it, never the absence of a button.
            Text(
                "Read-only",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
