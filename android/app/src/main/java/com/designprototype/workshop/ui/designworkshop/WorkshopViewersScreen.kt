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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwEligibleViewerDto
import com.designprototype.workshop.data.DwEligibleViewers
import com.designprototype.workshop.data.DwViewerAttempt
import com.designprototype.workshop.data.DwViewerChoice
import com.designprototype.workshop.data.DwViewerDto
import com.designprototype.workshop.data.DwViewerSelection
import com.designprototype.workshop.data.DW_VIEWER_LIMIT
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.dwPersonLabel
import com.designprototype.workshop.data.dwViewerAdministrationMissing
import com.designprototype.workshop.data.dwViewerChoices
import com.designprototype.workshop.data.dwViewerFailureMessage
import com.designprototype.workshop.data.dwViewerOfferNotice
import com.designprototype.workshop.data.dwViewerSearchTerm
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.SearchableMultiSelectField
import com.designprototype.workshop.ui.SelectOption
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt. Every file
// in this feature imports it, or its headings are quietly set in the body face.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * How long the search box waits before it asks the server.
 *
 * The same 350ms the workshop list uses, because it is the same interaction and an admin should not
 * meet two typing rhythms in one app. It paces a request that cannot be answered locally: the term
 * goes into the server's `WHERE`, so every keystroke without this is one more ILIKE scan of the user
 * table, and the first five answers to "Nabakalebara" are replies nobody will ever look at.
 */
private const val SEARCH_DEBOUNCE_MS = 350L

/**
 * Putting a second designer on one design & prototype workshop, from the handset.
 *
 * ── THE FAILURE THIS ENDS ────────────────────────────────────────────────────────────────────────
 *
 * A design workshop is a fortnight of work by TWO designers alongside a master craftsperson and a
 * reviewing officer, all of whom read the same 22 stages — and `load_workshop_or_404` answers
 * everybody but the account that pressed "New" with 404 "Record not found". Not "you may not see
 * this", which would at least be actionable: there is nothing there. The second designer cannot open
 * the record at all, and when a designer leaves mid-season the fortnight becomes unreadable to
 * everyone but an admin. The phone has honoured a grant on the READ side since the paging walk
 * landed (`data/DesignWorkshopListing.kt`); this is the other half — issuing one without finding a
 * laptop.
 *
 * ── THE PERMISSION, AND THE REFUSAL BUILT FOR IT ─────────────────────────────────────────────────
 *
 * `require_admin` — `deps.is_admin`, so {ADMIN, MASTER_ADMIN} — on all three routes in
 * `api/routes/design_workshop_viewers.py`. Deliberately NOT the workshop's creator and NOT
 * `can_run_design_workshops`: a DESIGNER cannot hand out access to their own workshop, because an
 * owner's grant freezes the moment the owner leaves, which is the handover problem this whole
 * feature exists to solve reintroduced one level up.
 *
 * That rule has a person on the wrong side of it — the designer who created the workshop and wants
 * their co-designer in — so the refusal is a first-class part of this feature rather than an
 * afterthought, and it is built in TWO places:
 *
 *  - on [StageIndexScreen], where the question is actually asked, a non-admin gets a sentence rather
 *    than a hidden button or a greyed one. It names who can do this and what to ask them for. A
 *    disabled control that refuses a tap without saying why is how somebody concludes the app is
 *    broken; an absent one leaves the designer with no answer at all.
 *  - HERE, because the composition that drew the button is not the rule. This screen re-derives the
 *    permission from the CACHED account on entry and again at the moment of the write, so an account
 *    demoted since the stage index was drawn is refused by the phone as well as by the server, and
 *    a non-admin who reaches this screen by any route reads the same sentence rather than an empty
 *    form. It also issues NO REQUEST at all when it knows the answer: making one and swallowing the
 *    403 would ask the server to refuse something this client already knows it may not have.
 *
 * The other half of the rule is not ours to hold. WHO MAY BE GRANTED — {DESIGNER, ADMIN,
 * MASTER_ADMIN}, a SET so a PROFESSOR is out despite outranking a designer, narrowed further by the
 * designer roster — comes off the wire from `GET /design-workshops/eligible-viewers` and is never
 * re-derived here. See `data/DesignWorkshopViewers.kt`.
 *
 * ── AND IT NEEDS A CONNECTION, WHICH THIS APP HAS TO SAY OUT LOUD ────────────────────────────────
 *
 * Everything else a designer does on this handset survives a courtyard with no signal. This does
 * not, and cannot: a grant is a row another person's sign-in reads on the other side of the country,
 * so a queued one would be an app telling an admin their colleague is in while the colleague is
 * still refused. The screen says so in a sentence, refuses honestly when it cannot reach the server,
 * and never claims a change did not happen when it cannot know that.
 *
 * ── WHAT WAS TAKEN FROM THE WEB, AND THE ONE THING THAT MOVED ────────────────────────────────────
 *
 * `frontend/components/settings/DesignWorkshopViewersPanel.tsx` is the counterpart, and its wording
 * and information architecture are kept: the creator sits OUTSIDE the picker, a current viewer who
 * is no longer eligible is still offered and marked, saving is explicit, and the saved membership is
 * spelled out under the picker rather than left to the trigger's "3 selected".
 *
 * What moved is the WORKSHOP SELECTOR. The web panel lives on /workshop-access/manage and therefore
 * has to begin by choosing a workshop out of a hundred, behind a type filter. On a handset that is a
 * dropdown of a hundred titles, on the one screen where picking the wrong row means granting a
 * stranger access to somebody's fieldwork — and the phone does not need it, because an admin gets
 * here from the workshop they are already looking at. The workshop is named in the heading instead,
 * and the type filter (which only ever NARROWED an offer this screen no longer makes) goes with it.
 */
@Composable
fun WorkshopViewersScreen(
    repository: WorkshopRepository,
    /**
     * The DRAFT STORE's id, like every other screen in this feature — which for a workshop started
     * in a courtyard is a `local-…` id no server has ever seen. The server id is resolved here from
     * the draft's `remoteId`, exactly as [StageIndexScreen] does it; routing must never assume the
     * two are the same.
     */
    workshopId: String,
    /**
     * The one thing worth saying somewhere other than in place: that the save landed, and how the
     * workshop now stands.
     *
     * There is deliberately NO `onError` beside it, unlike every sibling screen in this feature.
     * Every failure here is rendered where it happened — the refusal above the picker, the save
     * error immediately above the Save button — because a message about who can open somebody's
     * fortnight of fieldwork must not be a line that slides away after four seconds while the screen
     * underneath still shows the membership the admin thought they had saved.
     */
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    val viewer = remember(repository) { repository.cachedUser() }
    val canAdminister = remember(viewer) { mayAdministerViewers(viewer) }

    var loading by remember(workshopId) { mutableStateOf(canAdminister) }
    var reload by remember(workshopId) { mutableIntStateOf(0) }
    var saving by remember(workshopId) { mutableStateOf(false) }

    var title by remember(workshopId) { mutableStateOf("") }
    /** Null while unknown, and "" when the server answered without naming a creator — different states. */
    var creatorId by remember(workshopId) { mutableStateOf<String?>(null) }
    var onServer by remember(workshopId) { mutableStateOf(true) }
    var featureMissing by remember(workshopId) { mutableStateOf(false) }

    /**
     * The eligible accounts as the server last answered, and the question that answer belongs to.
     *
     * NOT "the eligible accounts". It is at most 2000 of them (`ELIGIBLE_VIEWER_LIMIT`, and this
     * repository has 2543 eligible accounts), narrowed to whatever [query] was last sent as `search`.
     * Everything that reads it has to know which of those it is holding, which is why the term and
     * the truncation flag travel inside it — see [DwEligibleViewers].
     */
    var offer by remember(workshopId) { mutableStateOf(DwEligibleViewers()) }
    /**
     * Every eligible account this screen has been shown since it opened, first-seen order.
     *
     * **THE ANTI-REVOCATION STORE.** A search replaces [offer], and a tick whose account is no longer
     * in [offer] would vanish from the options — and the PUT replaces the whole set, so an option that
     * is not rendered is a row the next Save deletes. An admin who found one colleague under
     * "Nabakalebara", ticked them, then typed a second surname would save the second and silently
     * drop the first. This is where the first one is kept, and `dwViewerChoices(retained = …)` is
     * where it is put back.
     */
    var seenEligible by remember(workshopId) {
        mutableStateOf<Map<String, DwEligibleViewerDto>>(emptyMap())
    }
    var rows by remember(workshopId) { mutableStateOf<List<DwViewerDto>?>(null) }
    var selection by remember(workshopId) { mutableStateOf(DwViewerSelection()) }

    /** What the admin has typed. [dwViewerSearchTerm] turns it into what the server is asked. */
    var query by remember(workshopId) { mutableStateOf("") }
    var searching by remember(workshopId) { mutableStateOf(false) }

    var loadError by remember(workshopId) { mutableStateOf<String?>(null) }
    var saveError by remember(workshopId) { mutableStateOf<String?>(null) }
    var searchError by remember(workshopId) { mutableStateOf<String?>(null) }

    LaunchedEffect(workshopId, reload, canAdminister) {
        // NOT REQUESTED AT ALL for a non-admin, matching `DesignerRosterScreen`. Issuing the calls
        // and swallowing three 403s would put an unexplained failure in the error channel every time
        // such an account opened this screen, and would ask the server to refuse something this
        // client already knows it may not have.
        if (!canAdminister) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        loadError = null

        val remoteId = WorkshopDraftStore.load(appContext, workshopId)?.remoteId
            ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
        if (remoteId == null) {
            // A workshop that exists only on this phone has no row for a grant to point at, and
            // nobody else could open it in any case. Said in words below rather than rendered as a
            // form that cannot be saved.
            onServer = false
            loading = false
            return@LaunchedEffect
        }
        onServer = true

        // THE WHOLE DETAIL, for two fields, and it is worth the bytes here. `createdById` decides who
        // is held out of the picker on both sides, and getting it wrong means offering an admin a
        // control that appears to remove the creator's access and cannot (`_deduplicate` drops them),
        // or omitting a designer who genuinely holds a row. Carrying the value down the route from
        // the stage index would have been free and would have been a value that could be stale by
        // the time it is used to decide an access question. This screen is admin-only, opened rarely,
        // and already declares that it needs a connection.
        val detail = runCatching { repository.designWorkshop(remoteId) }
        detail.onSuccess {
            title = it.title
            creatorId = it.createdById.orEmpty()
        }.onFailure { error ->
            loadError = error.viewerFailure(DwViewerAttempt.READ)
            loading = false
            return@LaunchedEffect
        }

        // WITH WHATEVER IS IN THE SEARCH BOX, not unconditionally unsearched. This effect is also the
        // "Try again" path, and re-running it as an empty search would answer a different question
        // from the one still typed in the field — a list of everybody under the term that found one
        // person. Read non-reactively on purpose: keying this effect on `query` would make every
        // keystroke re-fetch the workshop detail and the viewer rows too.
        val term = dwViewerSearchTerm(query)
        runCatching { repository.eligibleDesignWorkshopViewers(term) }
            .onSuccess { answered ->
                offer = answered
                // Replaced, not merged: this is a fresh load of the screen, and every tick is about
                // to be re-adopted from the server's own rows below.
                seenEligible = answered.users.associateBy { it.id }
                searchError = null
                featureMissing = false
            }
            .onFailure { error ->
                // The id-less call, and therefore the only honest probe for "this deployment predates
                // the feature": a 404 from it cannot mean a missing record, because there is no id in
                // it to be missing. See `dwViewerAdministrationMissing`.
                val status = (error as? HttpException)?.code()
                if (dwViewerAdministrationMissing(status)) {
                    featureMissing = true
                    offer = DwEligibleViewers()
                    loading = false
                    return@LaunchedEffect
                }
                // ANY OTHER FAILURE STOPS THE LOAD, and this early return is load-bearing rather
                // than tidy. `dwViewerChoices` marks every granted account a COMPLETE eligible list
                // does not contain as "has access, no longer eligible" — which is the truth when the
                // server OFFERED a list without them, and a lie when no list arrived at all. Left
                // to fall through, a 500 or a dropped socket here drew the picker over an empty
                // eligible list and told an administrator that every designer on the workshop had
                // been suspended from the roster, with a "Try again" nowhere in sight and nobody
                // available to add. Better to render the failure that actually happened. (The
                // default `DwEligibleViewers()` says `complete`, so this early return is what keeps
                // that from being read as "the eligible set is empty and we know it".)
                offer = DwEligibleViewers()
                loadError = error.viewerFailure(DwViewerAttempt.READ)
                loading = false
                return@LaunchedEffect
            }

        runCatching { repository.designWorkshopViewers(remoteId) }
            .onSuccess { served ->
                rows = served
                selection = DwViewerSelection.adopt(served, creatorId.orEmpty())
            }
            .onFailure { error ->
                rows = null
                if (loadError == null) loadError = error.viewerFailure(DwViewerAttempt.READ)
            }
        loading = false
    }

    /**
     * Ask the SERVER again whenever the typed term changes — the only way past the 2000-account cap.
     *
     * **THE TERM GOES INTO THE SERVER'S `WHERE`, and filtering [offer] here instead would be the
     * defect wearing a search box.** The list this screen holds is the first 2000 eligible accounts
     * by name; the colleague an admin cannot find is the one who sorts past that. Searching what
     * arrived would search only the part of the alphabet that fitted and answer "no such person"
     * about somebody who is perfectly eligible.
     *
     * Keyed on the raw text, so each keystroke cancels the run before it. Only the eligible list is
     * re-fetched: the workshop detail and the viewer rows do not depend on the term, and re-reading
     * the rows would re-adopt the baseline and throw away unsaved ticks.
     */
    LaunchedEffect(query) {
        val term = dwViewerSearchTerm(query)
        // Nothing loaded yet (the field is not even drawn), or this is already the answer on screen —
        // clearing "abc " back to "abc" is the same question and must not cost a request.
        if (rows == null || term == offer.search) return@LaunchedEffect
        // Not for the clear, which is a deliberate act and not typing — the same split the workshop
        // list makes. Waiting 350ms to un-narrow a list the admin has just emptied the box for reads
        // as a stuck screen.
        if (term != null) delay(SEARCH_DEBOUNCE_MS)
        searching = true
        val answered = runCatching { repository.eligibleDesignWorkshopViewers(term) }
        searching = false
        answered
            .onSuccess { served ->
                offer = served
                // MERGED, never replaced. This is the store that stops a search from silently
                // revoking a tick made under the previous term; see `seenEligible`.
                seenEligible = seenEligible + served.users.associateBy { it.id }
                searchError = null
            }
            .onFailure { error ->
                // A CANCELLED SEARCH IS NOT A FAILED ONE. This effect is keyed on the search box, so
                // every keystroke cancels the request in flight and `runCatching` catches that like
                // anything else. Reported, it would flash "This phone could not reach the repository"
                // over a connection that is fine, mid-word. The house pattern is to guard the
                // HANDLING rather than rethrow (see `WorkshopListScreen`); the run that replaced this
                // one sets both fields a moment later.
                if (error is CancellationException) return@onFailure
                // SAID, not swallowed. The alternative is the previous term's list sitting under the
                // new term with nothing to say it is stale — a picker answering a question nobody
                // asked, on the screen where the answer decides who can open a fortnight of work.
                searchError = error.viewerFailure(DwViewerAttempt.READ)
            }
    }

    /**
     * Send the whole intended membership, re-checking the permission first.
     *
     * The re-check is the point, and it reads the CACHED account rather than the `viewer` captured
     * when this screen composed: a disabled button is a statement about a layout and a recomposition
     * can undo it, whereas this is the rule. Same shape as `DesignerRosterScreen.mutate`.
     */
    fun save() {
        if (!mayAdministerViewers(repository.cachedUser())) {
            saveError = "Only an administrator can decide who may open a design workshop."
            return
        }
        saving = true
        saveError = null
        scope.launch {
            val remoteId = WorkshopDraftStore.load(appContext, workshopId)?.remoteId
                ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
            if (remoteId == null) {
                saveError = "This workshop is not on the server, so there is nobody to grant access to yet."
                saving = false
                return@launch
            }
            runCatching { repository.setDesignWorkshopViewers(remoteId, selection.payload()) }
                .onSuccess { served ->
                    rows = served
                    // The ANSWER becomes the new baseline, never the payload that was sent. Another
                    // admin may have added somebody between this screen loading and Save being
                    // pressed, and a client that trusted its own request would show a membership
                    // nobody has.
                    selection = DwViewerSelection.adopt(served, creatorId.orEmpty())
                    val count = selection.baseline.size
                    onMessage(
                        if (count == 0) {
                            "Nobody else can open this workshop. The designer who created it always " +
                                "can, and is not affected by this list."
                        } else {
                            "$count designer${if (count == 1) "" else "s"} can open this workshop, in " +
                                "addition to the designer who created it."
                        }
                    )
                }
                .onFailure { error -> saveError = error.viewerFailure(DwViewerAttempt.SAVE) }
            saving = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Who can open this workshop",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp
        )
        if (title.isNotBlank()) {
            Text(title, color = MaterialTheme.field.body, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }

        // ── The refusal, first, and never as a greyed control ─────────────────────────────────────
        if (!canAdminister) {
            Notice(
                "Deciding who may open a design & prototype workshop is administration, so it is " +
                    "open to admins and the master admin only — not to the designer who created it. " +
                    "That is deliberate: a workshop whose access is in its owner's gift freezes the " +
                    "day the owner leaves, and handover is what this exists for. Ask an " +
                    "administrator to add your co-designer to this workshop.",
                warning = true
            )
            return@Column
        }

        Text(
            "Besides the designer who created it. A workshop is usually run by two designers " +
                "alongside a master craftsperson and a reviewing officer, and a colleague who opens " +
                "it today is told the record does not exist — so name the designers who should see " +
                "this one.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )

        // Said before anything is attempted, not only when it fails. This is the one capability in
        // the app that cannot be done from a courtyard, and an admin who does not know that reads a
        // failure as the app being broken rather than as the signal being gone.
        Text(
            "This screen needs a connection. A grant is a row in the repository that a colleague's " +
                "sign-in reads, so unlike the 22 stages it cannot be held on the phone until later.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )

        if (!onServer) {
            Notice(
                "This workshop has not been sent to the server yet, so there is nothing for a grant " +
                    "to point at — and nobody else could open it in any case. Send it from the " +
                    "workshop list first, then come back.",
                warning = true
            )
            return@Column
        }

        if (featureMissing) {
            // The routes and this screen ship separately, so a phone updated ahead of the API is a
            // real state to render rather than a case to assume away.
            Notice(
                "This repository does not offer design workshop visibility yet. The controls are " +
                    "hidden rather than shown doing nothing — nobody has been granted or removed, " +
                    "and an admin can still open any workshop themselves.",
                warning = true
            )
            return@Column
        }

        loadError?.let { Notice(it, warning = false) }

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
            // useful action is asking again — and because a form drawn over an unknown membership
            // would let an admin "save" a set built from nothing.
            OutlinedButton(onClick = { reload++ }, modifier = Modifier.fillMaxWidth()) {
                Text("Try again")
            }
            return@Column
        }

        // "" once the detail has been read and answered no creator. Resolved ONCE here so the
        // options list, the creator card and the saved list cannot disagree about who the creator is
        // — three readings of a nullable is how one of them ends up comparing a String to a null and
        // quietly offering the creator for removal.
        val creator = creatorId.orEmpty()
        // Ticked, and not in the answer now on screen — because the answer is a search result. Handed
        // back to the picker so a second search cannot delete the first one's grant on the next Save.
        // Drawn from `seenEligible` only: a granted-but-ineligible account was never in an eligible
        // answer, so it is not here and falls through to the marked group below, where it belongs.
        val retained = remember(seenEligible, offer, selection.selected) {
            val answered = offer.users.mapTo(HashSet()) { it.id }
            seenEligible.values.filter { it.id !in answered && it.id in selection.selected }
        }
        val choices = remember(offer, retained, served, creator) {
            dwViewerChoices(
                eligible = offer.users,
                viewers = served,
                creatorId = creator,
                // The one claim a searched or cut list cannot support; see `DwEligibleViewers`.
                eligibleListComplete = offer.complete,
                retained = retained,
            )
        }

        // ── The creator, OUTSIDE the picker ──────────────────────────────────────────────────────
        //
        // Their access is not this list's to give or take, so putting them in the picker ticked and
        // disabled — the obvious first draft — would invite an admin to try to untick the one person
        // whose access is not on offer, and a control that refuses a tap without saying why is how
        // somebody concludes the screen is broken.
        //
        // NAMED FROM EVERY ANSWER SEEN, not from the current one: a search that does not match the
        // creator must not blank out the card that says who always has access.
        CreatorCard(creatorId = creator, rows = served, eligible = seenEligible.values.toList())

        // ── Reaching past the server's ceiling ───────────────────────────────────────────────────
        //
        // The endpoint answers at most 2000 accounts ordered by name and this repository has 2543
        // eligible ones, so on the sheet's local filter alone an admin could only ever find a
        // colleague who sorts inside the first 2000 — and would be told nothing about the rest. This
        // field sends `search` to the server, which applies it inside the same query as the
        // eligibility rule. The sheet's own box still narrows what came back; this is what decides
        // what comes back.
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
        // ONE SENTENCE, AND ONLY WHEN THERE IS SOMETHING TO SAY. Silence is the correct output when
        // the list is whole: a standing note about pagination on every visit is the padding this
        // screen has been asked twice not to have. The four states are different facts — people are
        // hidden and searching reaches them; people are hidden from this search and it needs
        // narrowing; people are hidden from EVERY search because the roster read was cut, so
        // narrowing cannot help; the search matched nobody, which is NOT the same as nobody being
        // eligible. Chosen by [dwViewerOfferNotice] rather than here, because it is a decision with
        // four outcomes and one of them no live database can produce — see its own note.
        if (searchError == null) {
            dwViewerOfferNotice(offer)?.let {
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
        searchError?.let { Notice(it, warning = false) }

        // ── The picker ───────────────────────────────────────────────────────────────────────────
        SearchableMultiSelectField(
            label = "Designers who may see this workshop",
            options = choices.map { it.asOption() },
            selected = selection.selected,
            placeholder = "Select one or more designers",
            // TWO MESSAGES, because they are two different facts and the whole defect this screen was
            // fixed for is those two looking identical. "Nobody is eligible" is a statement about the
            // roster; "nothing matched" is a statement about the term just typed.
            emptyMessage = if (offer.search != null) {
                "No eligible account matches that search."
            } else {
                "No designers are eligible. An account has to be able to run a design " +
                    "workshop, and be on the ACTIVE designer roster, before it can be let into one."
            },
            enabled = !saving,
            onSelectedChange = { picked -> selection = selection.withSelection(picked) }
        )
        Text(
            "Only accounts that could actually run a design workshop are offered — a designer whose " +
                "roster row is suspended would be refused at the door. Unticking somebody removes " +
                "their access when you save.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )

        // ── What is currently SAVED ──────────────────────────────────────────────────────────────
        //
        // Spelled out rather than left to the picker's trigger, which reads "3 of 12 selected" — the
        // PENDING answer, not the state of the repository.
        val granted = served.filter { it.userId != creator }
        if (granted.isEmpty()) {
            Text(
                "Nobody else can open this workshop yet. Only the designer who created it, and " +
                    "admins, can.",
                color = MaterialTheme.field.muted,
                fontSize = 13.sp
            )
        } else {
            granted.forEach { row ->
                GrantedRow(row = row, willBeRemoved = row.userId in selection.removed)
            }
        }

        val added = choices.filter { it.userId in selection.added }
        if (added.isNotEmpty()) {
            Text(
                "Will be added on save: " + added.joinToString(", ") { dwPersonLabel(it.name, it.email) } + ".",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
        }

        if (selection.overLimit) {
            Notice(
                "A workshop can hold at most $DW_VIEWER_LIMIT designers, and this list has " +
                    "${selection.payload().size}. Take some off before saving.",
                warning = true
            )
        }

        // Immediately above the buttons, never pinned to the top of a screen this tall: a refusal
        // rendered five sections away from the control that caused it reads as a button that did
        // nothing.
        saveError?.let { Notice(it, warning = false) }

        Button(
            onClick = { save() },
            enabled = selection.dirty && !saving && !selection.overLimit,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (saving) "Saving…" else "Save who can see this")
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
                    "${selection.resultingCount} designer${if (selection.resultingCount == 1) "" else "s"} " +
                    "would be able to open this workshop.",
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
 * provably the same rule instead of two readings of it that can drift by one clause — the same shape
 * as `mayManageDesignerRoster`.
 *
 * DELIBERATELY NOT `canRunDesignWorkshops`, and the difference is the whole feature: that predicate
 * admits a DESIGNER, and a designer may not administer viewers even on the workshop they created.
 * Widening this to the creator would put a control in front of a 403 and, worse, would suggest a
 * rule the server does not have.
 */
internal fun mayAdministerViewers(user: UserDto?): Boolean =
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
 * filtered out here rather than shown to an administrator.
 */
private fun Throwable.viewerFailure(attempt: DwViewerAttempt): String {
    // No HttpException means nothing answered — no signal, DNS, a socket dropped mid-request — or the
    // answer would not parse. Both leave the outcome of a SAVE genuinely unknown, which is exactly
    // what the null-status arm says, so they share it rather than inventing a third sentence.
    val status = (this as? HttpException)?.code() ?: return dwViewerFailureMessage(null, null, attempt)
    val said = apiErrorMessage("").takeIf { it.isNotBlank() && !it.startsWith("HTTP ") }
    return dwViewerFailureMessage(status, said, attempt)
}

// --------------------------------------------------------------------------------------
// Rows
// --------------------------------------------------------------------------------------

/**
 * One picker row: the person, then the two things that tell two colleagues apart.
 *
 * The email rides in the hint rather than being dropped — the web's option reads "Name · Role", and
 * on a handset the list is scanned rather than read, so a workshop with two designers called Sharma
 * needs the address to be pickable at all. The "no longer eligible" mark is kept from the web
 * verbatim, because it is the one row on the list whose meaning is not obvious.
 */
private fun DwViewerChoice.asOption(): SelectOption = SelectOption(
    value = userId,
    label = dwPersonLabel(name, email),
    hint = listOfNotNull(
        email.takeIf { it.isNotBlank() },
        FieldPermissions.label(role).takeIf { it.isNotBlank() },
        "has access, no longer eligible".takeIf { grantedButIneligible },
    ).joinToString(" · ").takeIf { it.isNotBlank() }
)

@Composable
private fun CreatorCard(
    creatorId: String,
    rows: List<DwViewerDto>,
    eligible: List<DwEligibleViewerDto>,
) {
    // Never the raw cuid. Twenty-five random characters ask an admin to recognise somebody they
    // cannot possibly recognise, which is the defect the artisan pickers shipped once.
    val named = rows.firstOrNull { it.userId == creatorId }?.let { dwPersonLabel(it.name, it.email) to it.role }
        ?: eligible.firstOrNull { it.id == creatorId }?.let { dwPersonLabel(it.name, it.email) to it.role }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "ALWAYS HAS ACCESS",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                named?.first ?: "The designer who created it",
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            named?.second?.takeIf { it.isNotBlank() }?.let {
                Text(FieldPermissions.label(it), color = MaterialTheme.field.muted, fontSize = 12.sp)
            }
            Text(
                "They created this workshop, so they can always open it. Nothing below removes that.",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
            // SAID rather than passed over. With no creator id the picker cannot hold them out, so
            // they may appear in the list below — and an admin who does not know that would read
            // their presence as a grant somebody made, and their own untick as a revocation. It is
            // neither: `_deduplicate` drops the creator from every payload that names them.
            if (creatorId.isBlank()) {
                Text(
                    "The repository did not say who created this workshop. They may therefore appear " +
                        "in the list below; ticking or unticking them there changes nothing, because " +
                        "the server ignores the creator in this list.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun GrantedRow(row: DwViewerDto, willBeRemoved: Boolean) {
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
                // The only answer anybody has to "how long has this person been on this workshop",
                // which is why `replace_viewers` writes only the difference and never restamps it.
                row.grantedAt?.takeIf { it.isNotBlank() }?.let { "added ${it.take(10)}" },
            ).joinToString(" · "),
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )
        // The WORDS carry it, not the colour: "will be removed on save" is legible to somebody who
        // cannot tell the red container from the grey one.
        if (willBeRemoved) {
            Text(
                "will be removed on save",
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = 11.sp,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

/** A block of prose the reader must not scroll past: amber for a rule, red for a failure. */
@Composable
private fun Notice(text: String, warning: Boolean) {
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
