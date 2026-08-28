package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DW_INSPECTOR_LIMIT
import com.designprototype.workshop.data.DwEligibleInspectorDto
import com.designprototype.workshop.data.DwEligibleInspectors
import com.designprototype.workshop.data.DwInspectionAttempt
import com.designprototype.workshop.data.DwInspectorChoice
import com.designprototype.workshop.data.DwInspectorDto
import com.designprototype.workshop.data.DwInspectorSelection
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.dwInspectionAdministrationMissing
import com.designprototype.workshop.data.dwInspectionFailureMessage
import com.designprototype.workshop.data.dwInspectorChoices
import com.designprototype.workshop.data.dwInspectorOfferNotice
import com.designprototype.workshop.data.dwInspectorSearchTerm
import com.designprototype.workshop.data.dwPersonLabel
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.SearchableMultiSelectField
import com.designprototype.workshop.ui.SelectOption
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * The same 350ms the workshop list and the viewer roster use, because it is the same interaction and
 * an admin should not meet two typing rhythms in one app.
 */
private const val SEARCH_DEBOUNCE_MS = 350L

/**
 * APPOINTING AN INSPECTOR TO ONE DESIGN & PROTOTYPE WORKSHOP, from the handset.
 *
 * ── WHAT THIS IS FOR, AND WHY IT IS NOT THE VIEWER ROSTER WEARING A DIFFERENT WORD ───────────────
 *
 * [WorkshopViewersScreen] decides who may WORK on a workshop: a viewer row admits its holder to all
 * 22 stages and every write on them, because `load_workshop_or_404(..., for_edit=True)` performs no
 * role check at all. This screen decides who may EXAMINE it. An inspection row admits its holder to
 * a single read-only route and to nothing else — no stage write, no report, no dictation consent, no
 * AI-layer acceptance, no delete, no re-granting, no media and no questionnaire responses. The two
 * are separate tables on purpose, and the server refuses at import time to let their role sets
 * overlap, so that one account can never hold both on one workshop.
 *
 * ── THE PERMISSION, READ OFF THE ROUTES AND NOT GUESSED ──────────────────────────────────────────
 *
 * `Depends(require_admin)` on both administration routes — `deps.is_admin`, so {ADMIN, MASTER_ADMIN}
 * and nobody else. **AND THE ARGUMENT IS STRONGER HERE THAN ON THE VIEWERS SCREEN.** That one is
 * admin-only for a handover reason: an owner who chooses their own readers freezes their workshop's
 * access the day they leave. This one is admin-only because it is the point of the tier —
 *
 *     THE INSPECTED MUST NOT CHOOSE THE INSPECTOR.
 *
 * If a designer could put somebody on their own workshop as its inspector, or take somebody off it,
 * the inspection is worth nothing. That is not a workflow preference; it is the entire value of an
 * independent review, which is why there is no "suggest an inspector" route either — a suggestion an
 * admin rubber-stamps is the same thing wearing a queue.
 *
 * The other half of the rule is not ours to hold: WHO MAY BE ASSIGNED comes off the wire from
 * `GET /design-workshop-inspections/eligible-inspectors` and is never re-derived here. See
 * `data/DesignWorkshopInspections.kt`.
 *
 * ── THE REFUSALS THE SERVER MAKES THAT THIS SCREEN DOES NOT PRE-EMPT ─────────────────────────────
 *
 * `_assert_every_id_may_inspect` refuses the workshop's CREATOR and any co-designer holding a viewer
 * row, BY NAME, with "an independent review by somebody who worked on it is not a review". This
 * screen deliberately does not filter those accounts out of the picker: filtering would hide a
 * MISTAKE an admin needs to be told about behind a silent no-op, and the two role sets are disjoint
 * today so the case is only reachable through a promotion — a DESIGNER holding a viewer row later
 * made an INSPECTOR — which is exactly the case nothing else in the codebase would notice. The 422
 * is passed through verbatim, because that sentence knows which account it is about and this client
 * does not.
 *
 * ── AND IT NEEDS A CONNECTION, WHICH THIS APP HAS TO SAY OUT LOUD ────────────────────────────────
 *
 * An assignment is a row another person's sign-in reads on the other side of the country, so a
 * queued one would be an app telling an admin somebody is inspecting while that person is still
 * refused at the door. The screen says so in a sentence before anything is attempted, refuses
 * honestly when it cannot reach the server, and never claims a change did not happen when it cannot
 * know that.
 */
@Composable
fun WorkshopInspectorsScreen(
    repository: WorkshopRepository,
    /**
     * The DRAFT STORE's id, like every other screen reached from the stage index — which for a
     * workshop started in a courtyard is a `local-…` id no server has ever seen. The server id is
     * resolved here from the draft's `remoteId`, exactly as [WorkshopViewersScreen] does it.
     *
     * NOTE THE ASYMMETRY WITH THE INSPECTOR'S OWN SCREENS, which take the SERVER's id directly:
     * an inspector has no draft of this workshop and must never acquire one, so there is nothing for
     * them to resolve through. This screen is the admin's, and an admin reaches it from a workshop
     * they may well be holding a draft of.
     */
    workshopId: String,
    /**
     * That the save landed, and how the workshop now stands.
     *
     * No `onError` beside it, matching [WorkshopViewersScreen]: every failure here is rendered where
     * it happened, because a message about who is examining somebody's fortnight of fieldwork must
     * not be a line that slides away after four seconds while the screen underneath still shows the
     * panel the admin thought they had saved.
     */
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    val viewer = remember(repository) { repository.cachedUser() }
    val canAdminister = remember(viewer) { mayAdministerInspections(viewer) }

    var loading by remember(workshopId) { mutableStateOf(canAdminister) }
    var reload by remember(workshopId) { mutableIntStateOf(0) }
    var saving by remember(workshopId) { mutableStateOf(false) }

    var title by remember(workshopId) { mutableStateOf("") }
    var onServer by remember(workshopId) { mutableStateOf(true) }
    var featureMissing by remember(workshopId) { mutableStateOf(false) }

    /**
     * The eligible accounts as the server last answered, and the question that answer belongs to.
     *
     * NOT "the eligible accounts": it is at most `ELIGIBLE_INSPECTOR_LIMIT` of them, narrowed to
     * whatever term was last sent. Everything that reads it has to know which of those it holds,
     * which is why the term and the truncation flag travel inside it.
     */
    var offer by remember(workshopId) { mutableStateOf(DwEligibleInspectors()) }

    /**
     * Every eligible account this screen has been shown since it opened, first-seen order.
     *
     * **THE ANTI-REVOCATION STORE.** A search replaces [offer], and a tick whose account is no longer
     * in [offer] would vanish from the options — and the PUT replaces the whole set, so an option
     * that is not rendered is a row the next Save deletes. An admin who found one examiner under one
     * surname, ticked them, then typed a second would save the second and silently end the first
     * one's inspection.
     */
    var seenEligible by remember(workshopId) {
        mutableStateOf<Map<String, DwEligibleInspectorDto>>(emptyMap())
    }
    var rows by remember(workshopId) { mutableStateOf<List<DwInspectorDto>?>(null) }
    var selection by remember(workshopId) { mutableStateOf(DwInspectorSelection()) }

    var query by remember(workshopId) { mutableStateOf("") }
    var searching by remember(workshopId) { mutableStateOf(false) }

    var loadError by remember(workshopId) { mutableStateOf<String?>(null) }
    var saveError by remember(workshopId) { mutableStateOf<String?>(null) }
    var searchError by remember(workshopId) { mutableStateOf<String?>(null) }

    LaunchedEffect(workshopId, reload, canAdminister) {
        // NOT REQUESTED AT ALL for a non-admin. Issuing the calls and swallowing the 403s would put
        // an unexplained failure in the error channel every time such an account opened this screen,
        // and would ask the server to refuse something this client already knows it may not have.
        if (!canAdminister) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        loadError = null

        val remoteId = WorkshopDraftStore.load(appContext, workshopId)?.remoteId
            ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
        if (remoteId == null) {
            onServer = false
            loading = false
            return@LaunchedEffect
        }
        onServer = true

        // The workshop's TITLE, for the heading — and nothing else is read off this call. The
        // viewers screen needs `createdById` from it because the creator is held out of its picker;
        // here the creator is refused by the SERVER by name rather than hidden by the client, so
        // there is no id to fetch and no state for it to be stale in.
        runCatching { repository.designWorkshop(remoteId) }
            .onSuccess { title = it.title }
            .onFailure { error ->
                loadError = error.inspectionFailure(DwInspectionAttempt.READ)
                loading = false
                return@LaunchedEffect
            }

        // WITH WHATEVER IS IN THE SEARCH BOX, not unconditionally unsearched: this effect is also
        // the "Try again" path, and re-running it as an empty search would answer a different
        // question from the one still typed in the field. Read non-reactively on purpose — keying
        // this effect on `query` would re-fetch the workshop and the assigned rows on every
        // keystroke, and re-reading the rows would re-adopt the baseline and throw away unsaved ticks.
        val term = dwInspectorSearchTerm(query)
        runCatching { repository.eligibleDesignWorkshopInspectors(term) }
            .onSuccess { answered ->
                offer = answered
                seenEligible = answered.users.associateBy { it.id }
                searchError = null
                featureMissing = false
            }
            .onFailure { error ->
                // The id-less call, and therefore the only honest probe for "this deployment
                // predates the feature": a 404 from it cannot mean a missing record.
                val status = (error as? HttpException)?.code()
                if (dwInspectionAdministrationMissing(status)) {
                    featureMissing = true
                    offer = DwEligibleInspectors()
                    loading = false
                    return@LaunchedEffect
                }
                // ANY OTHER FAILURE STOPS THE LOAD, and this early return is load-bearing rather
                // than tidy — the same trap `WorkshopViewersScreen` documents. `dwInspectorChoices`
                // marks every assigned account a COMPLETE eligible list does not contain as
                // "assigned, no longer eligible", which is the truth when the server OFFERED a list
                // without them and a lie when no list arrived at all. Left to fall through, a 500 or
                // a dropped socket would draw the picker over an empty eligible list and tell an
                // administrator that every examiner on the workshop had been barred from the
                // platform, with nobody available to add in their place.
                offer = DwEligibleInspectors()
                loadError = error.inspectionFailure(DwInspectionAttempt.READ)
                loading = false
                return@LaunchedEffect
            }

        runCatching { repository.designWorkshopInspectors(remoteId) }
            .onSuccess { served ->
                rows = served
                selection = DwInspectorSelection.adopt(served)
            }
            .onFailure { error ->
                rows = null
                if (loadError == null) loadError = error.inspectionFailure(DwInspectionAttempt.READ)
            }
        loading = false
    }

    /**
     * Ask the SERVER again whenever the typed term changes — the only way past the ceiling.
     *
     * The term goes into the server's `WHERE`, inside the same query as the eligibility rule.
     * Filtering [offer] here instead would search only the part of the alphabet that fitted and
     * answer "no such person" about somebody perfectly eligible.
     */
    LaunchedEffect(query) {
        val term = dwInspectorSearchTerm(query)
        // Nothing loaded yet, or this is already the answer on screen — clearing "abc " back to
        // "abc" is the same question and must not cost a request.
        if (rows == null || term == offer.search) return@LaunchedEffect
        // Not for the CLEAR, which is a deliberate act and not typing. Waiting 350ms to un-narrow a
        // list the admin has just emptied the box for reads as a stuck screen.
        if (term != null) delay(SEARCH_DEBOUNCE_MS)
        searching = true
        val answered = runCatching { repository.eligibleDesignWorkshopInspectors(term) }
        searching = false
        answered
            .onSuccess { served ->
                offer = served
                // MERGED, never replaced: this is the store that stops a search from silently
                // ending an inspection ticked under the previous term.
                seenEligible = seenEligible + served.users.associateBy { it.id }
                searchError = null
            }
            .onFailure { error ->
                // A CANCELLED SEARCH IS NOT A FAILED ONE. This effect is keyed on the search box, so
                // every keystroke cancels the request in flight and `runCatching` catches that like
                // anything else; reported, it would flash "This phone could not reach the
                // repository" over a connection that is fine, mid-word.
                if (error is CancellationException) return@onFailure
                searchError = error.inspectionFailure(DwInspectionAttempt.READ)
            }
    }

    /**
     * Send the whole intended set, re-checking the permission first.
     *
     * The re-check reads the CACHED account rather than the `viewer` captured when this screen
     * composed: a disabled button is a statement about a layout and a recomposition can undo it,
     * whereas this is the rule.
     */
    fun save() {
        if (!mayAdministerInspections(repository.cachedUser())) {
            saveError = "Only an administrator can decide who inspects a design workshop."
            return
        }
        saving = true
        saveError = null
        scope.launch {
            val remoteId = WorkshopDraftStore.load(appContext, workshopId)?.remoteId
                ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
            if (remoteId == null) {
                saveError = "This workshop is not on the server, so there is nothing to inspect yet."
                saving = false
                return@launch
            }
            runCatching { repository.setDesignWorkshopInspectors(remoteId, selection.payload()) }
                .onSuccess { served ->
                    rows = served
                    // The ANSWER becomes the new baseline, never the payload that was sent: another
                    // admin may have assigned somebody between this screen loading and Save being
                    // pressed, and a client that trusted its own request would show a panel nobody
                    // has.
                    selection = DwInspectorSelection.adopt(served)
                    val count = selection.baseline.size
                    onMessage(
                        if (count == 0) {
                            "Nobody is assigned to inspect this workshop. The designers who run it " +
                                "are unaffected."
                        } else {
                            "$count ${if (count == 1) "inspector" else "inspectors"} assigned to " +
                                "this workshop. They can read every stage of it and change nothing."
                        }
                    )
                }
                .onFailure { error -> saveError = error.inspectionFailure(DwInspectionAttempt.SAVE) }
            saving = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Inspection of a design workshop",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp
        )
        if (title.isNotBlank()) {
            Text(title, color = MaterialTheme.field.body, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }

        // ── The refusal, first, and never as a greyed control ────────────────────────────────────
        if (!canAdminister) {
            InspectionNotice(
                "Only an administrator decides who inspects a design & prototype workshop — not the " +
                    "designer who created it, and not the designers who run it. That is the point of " +
                    "the tier rather than a workflow preference: an independent review whose reviewer " +
                    "was chosen by the people being reviewed is not a review. Ask an administrator.",
                warning = true
            )
            return@Column
        }

        Text(
            "Who may read one design & prototype workshop in order to inspect and review it.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )
        Text(
            "An inspection is READ-ONLY: an Inspector / Reviewer assigned here can open every stage " +
                "of this workshop and change none of it. Only an admin decides who inspects what — " +
                "the designers who run a workshop have no say in who examines it, and cannot be its " +
                "inspector themselves.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )

        // Said before anything is attempted, not only when it fails. An admin who does not know this
        // reads a failure as the app being broken rather than as the signal being gone.
        Text(
            "This screen needs a connection. An assignment is a row in the repository that an " +
                "inspector's sign-in reads, so unlike the 22 stages it cannot be held on the phone " +
                "until later.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )

        if (!onServer) {
            InspectionNotice(
                "This workshop has not been sent to the server yet, so there is nothing to inspect " +
                    "— and nobody else could open it in any case. Send it from the workshop list " +
                    "first, then come back.",
                warning = true
            )
            return@Column
        }

        if (featureMissing) {
            // The routes and this screen ship separately, so a phone updated ahead of the API is a
            // real state to render rather than a case to assume away.
            InspectionNotice(
                "This repository does not offer design workshop inspections yet. The controls below " +
                    "are hidden rather than shown doing nothing — nobody has been assigned or " +
                    "unassigned, and every workshop is unaffected.",
                warning = true
            )
            return@Column
        }

        loadError?.let { InspectionNotice(it, warning = false) }

        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Loading…", color = MaterialTheme.field.muted, fontSize = 13.sp)
            }
            return@Column
        }

        val served = rows
        if (served == null) {
            // The load failed and `loadError` above already says how. One control, because the only
            // useful action is asking again — and because a form drawn over an unknown panel would
            // let an admin "save" a set built from nothing.
            OutlinedButton(onClick = { reload++ }, modifier = Modifier.fillMaxWidth()) {
                Text("Try again")
            }
            return@Column
        }

        // Ticked, and not in the answer now on screen because that answer is a search result. Handed
        // back to the picker so a second search cannot end the first one's inspection on the next
        // Save. Drawn from `seenEligible` only: an assigned-but-ineligible account was never in an
        // eligible answer, so it falls through to the marked group below where it belongs.
        val retained = remember(seenEligible, offer, selection.selected) {
            val answered = offer.users.mapTo(HashSet()) { it.id }
            seenEligible.values.filter { it.id !in answered && it.id in selection.selected }
        }
        val choices = remember(offer, retained, served) {
            dwInspectorChoices(
                eligible = offer.users,
                inspectors = served,
                // The one claim a searched or cut list cannot support.
                eligibleListComplete = offer.complete,
                retained = retained,
            )
        }

        // ── Reaching past the server's ceiling ───────────────────────────────────────────────────
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search by name or email") },
            singleLine = true,
            enabled = !saving,
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
                    searching -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    query.isNotEmpty() -> IconButton(onClick = { query = "" }) {
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
        // ONE SENTENCE, AND ONLY WHEN THERE IS SOMETHING TO SAY. Three states here where the viewer
        // picker has four — see `dwInspectorOfferNotice` for why the fourth cannot occur on this
        // endpoint.
        if (searchError == null) {
            dwInspectorOfferNotice(offer)?.let {
                Text(
                    it,
                    // The WORD carries it; the colour only separates "somebody is hidden from you"
                    // from "nobody matched", and neither is an error.
                    color = if (offer.truncated) MaterialTheme.field.warning else MaterialTheme.field.muted,
                    fontSize = 12.sp
                )
            }
        }
        // A search that never answered must not leave the previous term's list looking like this
        // term's answer.
        searchError?.let { InspectionNotice(it, warning = false) }

        // ── The picker ───────────────────────────────────────────────────────────────────────────
        SearchableMultiSelectField(
            label = "Inspectors assigned to this workshop",
            options = choices.map { it.asOption() },
            selected = selection.selected,
            placeholder = "Select one or more inspectors",
            // TWO MESSAGES, because they are two different facts: "nobody holds the tier" is a
            // statement about the repository, "nothing matched" is a statement about the term just
            // typed, and the whole defect this pattern was fixed for is those two looking identical.
            emptyMessage = if (offer.search != null) {
                "No Inspector / Reviewer account matches that search."
            } else {
                "No account holds the Inspector / Reviewer tier yet"
            },
            enabled = !saving,
            onSelectedChange = { picked -> selection = selection.withSelection(picked) }
        )
        Text(
            "Only accounts holding the Inspector / Reviewer tier are offered, and only those the " +
                "platform access list still admits — assigning somebody who cannot sign in would " +
                "leave this screen saying they are inspecting while they are shown a refusal at the " +
                "door. Unticking somebody ends their inspection when you save. At most " +
                "$DW_INSPECTOR_LIMIT accounts.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )

        // ── What is currently SAVED ──────────────────────────────────────────────────────────────
        //
        // Spelled out rather than left to the picker's trigger, which reads "3 of 12 selected" — the
        // PENDING answer, not the state of the repository.
        //
        // AN EMPTY LIST HERE IS THE LITERAL TRUTH, unlike the viewer roster where an empty answer
        // still leaves the creator holding the workshop through `createdById`. Nobody holds an
        // inspection by any route other than a row in this table, so this sentence may say so flatly.
        if (served.isEmpty()) {
            Text(
                "This workshop is not under inspection. Nobody has been assigned to review it — and " +
                    "unlike the designers who can see it, there is nobody holding an inspection some " +
                    "other way.",
                color = MaterialTheme.field.muted,
                fontSize = 13.sp
            )
        } else {
            served.forEach { row ->
                AssignedRow(row = row, willEnd = row.userId in selection.removed)
            }
        }

        val added = choices.filter { it.userId in selection.added }
        if (added.isNotEmpty()) {
            Text(
                "Will be assigned on save: " +
                    added.joinToString(", ") { dwPersonLabel(it.name, it.email) } + ".",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
        }

        // THE CAP, STATED BEFORE THE SAVE rather than discovered from a 422 naming a shape.
        if (selection.overLimit) {
            val over = selection.payload().size - DW_INSPECTOR_LIMIT
            InspectionNotice(
                "${selection.payload().size} accounts are selected and a workshop may have at most " +
                    "$DW_INSPECTOR_LIMIT. Untick $over of them to save.",
                warning = true
            )
        }

        // Immediately above the buttons, never pinned to the top of a screen this tall: a refusal
        // rendered five sections away from the control that caused it reads as a button that did
        // nothing.
        saveError?.let { InspectionNotice(it, warning = false) }

        Button(
            onClick = { save() },
            enabled = selection.dirty && !saving && !selection.overLimit,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (saving) "Saving…" else "Save who inspects this")
        }
        if (selection.dirty) {
            OutlinedButton(
                onClick = { selection = selection.discard(); saveError = null },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Discard changes") }
            val pending = selection.added.size + selection.removed.size
            // The WORD carries the meaning, never the colour on its own.
            Text(
                "$pending unsaved change${if (pending == 1) "" else "s"} · " +
                    "${selection.resultingCount} " +
                    (if (selection.resultingCount == 1) "account" else "accounts") +
                    " would be inspecting this workshop.",
                color = MaterialTheme.field.warning,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

// --------------------------------------------------------------------------------------
// The rule
// --------------------------------------------------------------------------------------

/**
 * `require_admin` — `deps.is_admin`, so ADMIN and MASTER_ADMIN and nobody else.
 *
 * A FUNCTION rather than a remembered Boolean, so the screen's chrome and the write's own guard are
 * provably the same rule instead of two readings of it that can drift by one clause.
 *
 * **A SEPARATE FUNCTION FROM [mayAdministerViewers] ALTHOUGH BOTH ARE `isAdmin` TODAY**, and this is
 * not duplication for its own sake: they mirror two different server predicates over two different
 * tables, and collapsing them would mean that the day either gate moves the other client surface
 * moves with it in silence. It is the same argument `FieldPermissions` makes for keeping
 * `canManageDesignerRoster` and `canManageAccessRoster` apart.
 *
 * DELIBERATELY NOT [FieldPermissions.canInspectDesignWorkshops], which is the predicate for the
 * OTHER door and would be exactly wrong here: it admits an INSPECTOR and refuses an admin, so using
 * it would hand the appointment screen to the people being appointed and take it from the only tier
 * allowed to appoint them.
 */
internal fun mayAdministerInspections(user: UserDto?): Boolean =
    user != null && FieldPermissions.isAdmin(user)

// --------------------------------------------------------------------------------------
// Reading a failure once
// --------------------------------------------------------------------------------------

/**
 * The sentence for a failed call, built from the STATUS and the server's own words.
 *
 * Reading the error body CONSUMES it, so `apiErrorMessage` may be called exactly once per failure —
 * which is why this is one function and not a status check followed by a message lookup at the call
 * site. `apiErrorMessage` falls back to `HttpException.message`, the literal string "HTTP 403
 * Forbidden", when the body carries no `detail`; that is a status line and not a sentence, so it is
 * filtered out here rather than shown to a person.
 *
 * Internal rather than private: the inspector's own two screens read the same failures through it,
 * and a second copy would be a second set of sentences for one set of statuses.
 */
internal fun Throwable.inspectionFailure(attempt: DwInspectionAttempt): String {
    // No HttpException means nothing answered — no signal, DNS, a socket dropped mid-request — or
    // the answer would not parse. Both leave the outcome of a SAVE genuinely unknown, which is
    // exactly what the null-status arm says, so they share it rather than inventing a third sentence.
    val status = (this as? HttpException)?.code()
        ?: return dwInspectionFailureMessage(null, null, attempt)
    val said = apiErrorMessage("").takeIf { it.isNotBlank() && !it.startsWith("HTTP ") }
    return dwInspectionFailureMessage(status, said, attempt)
}

// --------------------------------------------------------------------------------------
// Rows
// --------------------------------------------------------------------------------------

/**
 * One picker row: the person, then the two things that tell two colleagues apart.
 *
 * The email rides in the hint rather than being dropped — on a handset the list is scanned rather
 * than read, so a repository with two inspectors called Sharma needs the address to be pickable at
 * all. The "no longer eligible" mark is kept because it is the one row whose meaning is not obvious.
 */
private fun DwInspectorChoice.asOption(): SelectOption = SelectOption(
    value = userId,
    label = dwPersonLabel(name, email),
    hint = listOfNotNull(
        email.takeIf { it.isNotBlank() },
        FieldPermissions.label(role).takeIf { it.isNotBlank() },
        "assigned, no longer eligible".takeIf { assignedButIneligible },
    ).joinToString(" · ").takeIf { it.isNotBlank() }
)

@Composable
private fun AssignedRow(row: DwInspectorDto, willEnd: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            dwPersonLabel(row.name, row.email),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            listOfNotNull(
                row.email.takeIf { it.isNotBlank() },
                FieldPermissions.label(row.role).takeIf { it.isNotBlank() },
                // The only answer anybody has to "how long has this workshop been under
                // inspection", which is why `replace_inspectors` writes only the difference and
                // never restamps it.
                row.assignedAt?.takeIf { it.isNotBlank() }?.let { "assigned ${it.take(10)}" },
            ).joinToString(" · "),
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )
        // The WORDS carry it, not the colour: legible to somebody who cannot tell the red container
        // from the grey one.
        if (willEnd) {
            Text(
                "inspection ends on save",
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = 11.sp,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

/**
 * A block of prose the reader must not scroll past: amber for a rule, red for a failure.
 *
 * A private twin of `WorkshopViewersScreen`'s `Notice`, which is file-private there. Spelled again
 * rather than promoted, for the reason `DesignWorkshopInspections.kt` gives about `asSentence`: a
 * shared spelling belongs in a change that moves both at once.
 */
@Composable
internal fun InspectionNotice(text: String, warning: Boolean) {
    Text(
        text,
        color = if (warning) MaterialTheme.field.onWarningContainer else MaterialTheme.colorScheme.onErrorContainer,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (warning) MaterialTheme.field.warningContainer else MaterialTheme.colorScheme.errorContainer,
                RoundedCornerShape(10.dp)
            )
            .padding(12.dp)
    )
}
