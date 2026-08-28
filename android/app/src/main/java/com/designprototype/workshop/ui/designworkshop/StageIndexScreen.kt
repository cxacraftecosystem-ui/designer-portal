package com.designprototype.workshop.ui.designworkshop

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DW_CONSENT_NOT_YOURS_TO_RECORD
import com.designprototype.workshop.data.DW_CONSENT_NO_LABEL
import com.designprototype.workshop.data.DW_CONSENT_QUESTION
import com.designprototype.workshop.data.DW_CONSENT_ROW_TITLE
import com.designprototype.workshop.data.DW_CONSENT_YES_LABEL
import com.designprototype.workshop.data.DW_MAX_HITS
import com.designprototype.workshop.data.DW_MIN_QUERY_CHARS
import com.designprototype.workshop.data.DW_PROVENANCE_TITLE
import com.designprototype.workshop.data.DW_REPORT_HISTORY_TITLE
import com.designprototype.workshop.data.DraftConsent
import com.designprototype.workshop.data.DwConsentMerge
import com.designprototype.workshop.data.DwDictationRun
import com.designprototype.workshop.data.DwSearchStatus
import com.designprototype.workshop.data.DwTier3Consent
import com.designprototype.workshop.data.DesignWorkshopDetailDto
import com.designprototype.workshop.data.DwStageCompleteness
import com.designprototype.workshop.data.DwStageFocus
import com.designprototype.workshop.data.DwSubmissionReadiness
import com.designprototype.workshop.data.DwWorkshopSearch
import com.designprototype.workshop.data.DwWorkshopSearchHit
import com.designprototype.workshop.data.DwWorkshopSearchIndex
import com.designprototype.workshop.data.StageCompletenessDto
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.DwCustomSectionStore
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.dwCustomDefinition
import com.designprototype.workshop.data.isHeld
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.computeWorkshopCompleteness
import com.designprototype.workshop.data.dwConsentMerge
import com.designprototype.workshop.data.dwConsentRecordedNote
import com.designprototype.workshop.data.dwConsentStateSentence
import com.designprototype.workshop.data.dwTier3ConsentOf
import com.designprototype.workshop.data.dwTier3ConsentToken
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.data.overallPercent
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import java.time.Instant
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * The 22 stages of one workshop, each with its percentage and the list of what is still missing.
 *
 * ── WHERE THE NUMBERS COME FROM, AND WHY IT IS NOT SIMPLY "THE API" ──────────────────────────────
 *
 * The API returns a `completeness` block per stage and it is authoritative — for the data the SERVER
 * holds. That is not the same data the designer is looking at. A stage filled in an hour ago in a
 * courtyard has not reached the server, so rendering the server's figure beside it would tell the
 * designer their morning did not count, and the natural response to that is to type it again.
 *
 * So the score is computed HERE, from the local draft, through [computeWorkshopCompleteness], which
 * is a line-for-line port of the server's `stage_completeness`. The API's copy is fetched anyway and
 * used for exactly one thing: a stage this device has never opened, whose data therefore only exists
 * server-side (a workshop somebody else started, opened here for the first time). Local wins wherever
 * local knows anything.
 *
 * ── "WHAT IS MISSING" ────────────────────────────────────────────────────────────────────────────
 *
 * The missing list is BASIC-tier fields only, because BASIC is what the completeness gate counts and
 * what the report needs. Listing the unanswered STANDARD and ADVANCED fields too would produce a list
 * of two hundred items per stage, which is a list nobody reads — and the tiers exist precisely so a
 * workshop held without facilities can still be complete.
 *
 * ── AND EACH ONE IS A DESTINATION ────────────────────────────────────────────────────────────────
 *
 * Every entry in that list is tappable and lands on the box itself. It was inert text, and inert is
 * expensive in exactly the place this screen is read: the designer taps into stage 11, and then
 * scrolls a form of several hundred fields by eye hunting for "Warp count", once per gap, in the
 * last hour of a workshop. The web has had these as links throughout.
 *
 * The addresses come from [DwSubmissionReadiness], which walks the registry a second time to work
 * out WHERE each label lives and decides nothing about completeness — so a label it cannot place
 * still shows and still opens the stage, and this screen still prints exactly the labels the scorer
 * produced. See that module's header for why the asymmetry is the whole design.
 *
 * ── AND SO IS EVERY WORD IN THE WORKSHOP ─────────────────────────────────────────────────────────
 *
 * The box above the list searches all 22 stages, offline, through [DwWorkshopSearch] — see that
 * module for the tokenising and folding rules and for why the Odia case is the one that decides
 * whether any of it works. It is HERE rather than on a screen of its own for the same reason the web
 * puts it on the workshop overview: this is the screen a designer is already looking at when the
 * question occurs to them, and a search that had to be navigated to in order to navigate to a stage
 * would be one screen too many in a courtyard.
 */
@Composable
fun StageIndexScreen(
    repository: WorkshopRepository,
    workshopId: String,
    /** [DwStageFocus] is null for "just open it" — the row itself, and any label with no address. */
    onOpenStage: (stageKey: String, focus: DwStageFocus?) -> Unit,
    onOpenReport: () -> Unit,
    /**
     * Open the log of every file already generated, and the diff between any two of them.
     *
     * A SIBLING OF [onOpenReport] AND NOT PART OF IT. That button makes the NEXT file; this one is
     * about the files already produced and, in the cases that matter, already handed to an office.
     * The two are read at different moments by people asking different questions, which is the same
     * argument [DwReportHistoryScreen] makes for not folding itself into [ReportScreen].
     *
     * NULL MEANS THE HOST OFFERS NO ROUTE TO IT, and then no control is drawn at all. A button that
     * looks live and dead-ends a tap is the failure this screen argues against a few rows down,
     * where a decision the reader may not make is a SENTENCE rather than a disabled control; an
     * absent destination is a different thing again and takes the control with it.
     */
    onOpenReportHistory: (() -> Unit)? = null,
    /**
     * Open the artisan cards and prototype tags for this workshop.
     *
     * It hangs off the INDEX rather than off stage 13, and that is the point of putting it here: the
     * tags are wanted at the close of a workshop, for prototypes entered over a fortnight across three
     * stages, and a designer looking for them is looking at the workshop rather than at any one stage.
     * The report button beside it is reached the same way for the same reason.
     */
    onOpenCodes: () -> Unit,
    /**
     * Open the bulk photo intake for this workshop.
     *
     * A SIBLING OF THE CARDS BUTTON, and off the index for the same reason — sharpened. A camera dump
     * spans the whole fortnight, and the entire point of the intake is that it works out WHICH stage
     * each photograph belongs to. Hanging it off a stage would ask the designer for the answer the
     * feature exists to produce.
     */
    onOpenPhotos: () -> Unit,
    /**
     * Open "who can open this workshop" — the viewer roster.
     *
     * Offered only to an ADMIN, and only for a workshop the server has (see the row this draws). It
     * hangs off the index for the same reason the report and the cards do: it is a fact about the
     * WORKSHOP rather than about any one of its stages, and the moment somebody wants it is the
     * moment a second designer is standing next to them unable to open the record.
     */
    onOpenViewers: () -> Unit,
    /**
     * Open "who inspects this workshop" - the appointment screen for the fifth scope.
     *
     * Offered only to an ADMIN, and only for a workshop the server has, exactly like [onOpenViewers].
     * It hangs off the index for the same reason the report and the viewer roster do: it is a fact
     * about the WORKSHOP rather than about any one of its 22 stages.
     *
     * NOT THE SAME QUESTION AS [onOpenViewers], and the two rows sit apart on this screen for that
     * reason. That one decides who may WORK on this record - a viewer row admits its holder to all
     * 22 stages and every write on them. This one decides who may EXAMINE it, read-only, and the
     * server refuses at import time to let one account hold both on one workshop.
     */
    onOpenInspectors: () -> Unit,
    /**
     * Open the admin authorship & divergence view for this workshop.
     *
     * Offered ONLY to an admin, and only for a workshop the server has — and, unlike [onOpenViewers],
     * with nothing at all in its place for everyone else. See the row this draws for why the two
     * admin-only controls on this screen are treated differently.
     */
    onOpenProvenance: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewer = remember(repository) { repository.cachedUser() }

    var stages by remember(workshopId) { mutableStateOf<List<DwStageCompleteness>>(emptyList()) }
    var title by remember(workshopId) { mutableStateOf("") }
    var loading by remember(workshopId) { mutableStateOf(true) }
    var expanded by remember(workshopId) { mutableStateOf<String?>(null) }
    var serverNote by remember(workshopId) { mutableStateOf<String?>(null) }
    /**
     * Whether this workshop exists on the SERVER, which is a different question from whether the
     * server could be reached just now. Access is administered against the server's row, so a
     * workshop that has never left this phone has nothing to administer — and an offline moment must
     * not be mistaken for that, or the row below would tell an admin to "send it first" about a
     * workshop that was sent a fortnight ago.
     */
    var onServer by remember(workshopId) { mutableStateOf(false) }
    /**
     * `stageKey → (missing label → the box it names)`, for turning the list below into destinations.
     *
     * Held apart from `stages` rather than folded into [DwStageCompleteness] because the two answer
     * different questions and only one of them is allowed to decide what is outstanding. This map is
     * a lookup; a label absent from it costs the row its precision and nothing else.
     */
    var addresses by remember(workshopId) {
        mutableStateOf<Map<String, Map<String, DwStageFocus>>>(emptyMap())
    }
    /**
     * Every written answer in the workshop, indexed once.
     *
     * Built HERE, in the load that has already read the registry and the draft, and never again: a
     * keystroke costs a query against this and nothing else. Rebuilding per keystroke would re-fold a
     * quarter of a megabyte on a 6 GB handset for every letter typed.
     */
    var searchIndex by remember(workshopId) { mutableStateOf(DwWorkshopSearch.emptyIndex()) }
    /**
     * Whether this workshop's recordings may be sent out to be written down, as this device has it.
     *
     * ON THIS SCREEN because it is a fact about the WORKSHOP rather than about any one of its stages —
     * the same rule the viewers row above states for itself, and the reason both live here. It is also
     * the screen a designer can be SENT to: every sentence the dictation control prints about a missing
     * consent names "this workshop's own screen", and that has to be somewhere findable.
     */
    var consent by remember(workshopId) { mutableStateOf(DraftConsent()) }
    /** True while an answer is being written down and pushed, so the two buttons cannot be double-tapped. */
    var recordingConsent by remember(workshopId) { mutableStateOf(false) }
    /** What just happened to the answer, said in place rather than as a toast that outlives the screen. */
    var consentNote by remember(workshopId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(workshopId) {
        loading = true
        runCatching {
            val schema = repository.designWorkshopSchema(appContext)
            val draft = WorkshopDraftStore.load(appContext, workshopId)
            // Read off disk BEFORE the network call below, like the search index under it and for the
            // same reason: a designer opening this screen with no signal waits for a timeout, and the
            // figures beside all 22 stages must be right the moment the spinner goes rather than a
            // request later. The refresh happens further down, once `remoteId` is known.
            var definition = DwCustomSectionStore.load(appContext, workshopId)
            var local = computeWorkshopCompleteness(schema, draft, definition)
            // Computed from the same registry and the same draft, with no network in it — which is
            // the point: the question "where is the field I am missing" is asked in a courtyard on
            // the last afternoon, not from a desk with a connection.
            addresses = DwSubmissionReadiness.addressBook(
                DwSubmissionReadiness.assess(schema, draft, workshopId, definition)
            )
            // Same two inputs again, and deliberately before the network call below: a designer who
            // opens this screen with no signal waits for a timeout, and the search must be ready the
            // moment the spinner goes rather than a request later.
            searchIndex = DwWorkshopSearch.buildIndex(schema, draft)

            val remoteId = draft?.remoteId ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
            onServer = remoteId != null
            val remote = remoteId?.let {
                runCatching { repository.designWorkshop(it) }.getOrNull()
            }
            // Refreshed opportunistically, and only ever ADDING to what was read off disk above: a
            // failure leaves the cached copy exactly where it was — see [dwCustomDefinition].
            if (remoteId != null) {
                val seeded = definition?.customSchemaVersion
                definition = runCatching {
                    repository.dwCustomDefinition(appContext, workshopId, remoteId)
                }.getOrNull() ?: definition
                /*
                  AND EVERYTHING SCORED OFF THE OLD COPY IS SCORED AGAIN, WHICH IS THE WHOLE POINT OF
                  THE DIGEST COMPARISON TWENTY LINES DOWN.

                  `local` and `addresses` were computed against the definition that was on disk when
                  this screen opened; `heldDigest` below is read from the one this refresh just
                  fetched. Left as they were, the refresh that MOVED the definition — a designer
                  adding a required question on the web this morning, which is the ordinary way this
                  screen meets a new definition — would make the two disagree in exactly the shape
                  this screen refuses elsewhere: the server's score adopted for untouched stages
                  under today's definition, sitting in one column beside this device's own totals
                  computed under yesterday's, with the digests reported as equal and nothing on
                  screen to say why. The guard would have passed while the list showed two
                  arithmetics — the failure it exists to prevent, arrived at through its own door.

                  Only when the digest actually moved: this is a full 22-stage walk plus a readiness
                  assembly, and re-running it on every open of the ordinary workshop — whose digest
                  is `""` before and after — would spend that for nothing.
                */
                if (definition?.customSchemaVersion != seeded) {
                    local = computeWorkshopCompleteness(schema, draft, definition)
                    addresses = DwSubmissionReadiness.addressBook(
                        DwSubmissionReadiness.assess(schema, draft, workshopId, definition)
                    )
                }
            }
            serverNote = when {
                remoteId == null -> "This workshop has not been created on the server yet."
                remote == null -> "The server could not be reached. These figures are from this device."
                else -> null
            }

            // The consent, reconciled between this device and the server and — where this device holds
            // an answer the server has not heard — pushed while there is a connection to push it over.
            // Never allowed to fail the screen: see the function.
            consent = dwResolveDictationConsent(
                context = appContext,
                repository = repository,
                workshopId = workshopId,
                onDevice = draft?.consent ?: DraftConsent(),
                remote = remote,
                remoteId = remoteId,
            )
            // Handed to the stage screens for the one case the draft cannot cover: a workshop authored in
            // a browser, whose consent is already recorded up there and which has no draft on this
            // handset to write it into yet. Without it this screen would say the recordings may be sent
            // out while the microphone one tap away went on withholding the rung — see
            // [DwDictationRun.consentAnswerSeen].
            DwDictationRun.rememberConsentAnswer(workshopId, dwTier3ConsentOf(consent.decision))

            /*
              WHETHER THE SERVER'S SCORE MAY BE ADOPTED AT ALL — the second arithmetic this list
              could otherwise show.

              `fromServer` replaces `requiredTotal`, `requiredFilled` and `missing` for a stage this
              device has never touched. Once the server counts a designer's own required questions,
              that number is computed under a DEFINITION, and this device may be holding an older one
              or none: the list would then print the server's higher total for untouched stages and
              its own lower one for touched stages, in one column, with nothing on screen to say why.
              A designer reading "3 of 7" beside "2 of 4" on two stages of one workshop has no way to
              know which arithmetic either row used.

              The server carries `customSchemaVersion` beside the score for exactly this — the route's
              own comment names this failure — so the digests are compared and a score computed under
              a definition this device does not hold is REFUSED, falling back to the local number.
              Two equal digests are the same definition, because it is a content digest.

              A device holding NO definition compares as `""`, which is also what the server sends for
              a workshop that has none — so the ordinary workshop, which is most of them, adopts
              exactly as it always did and nothing about this screen changes for it.
            */
            val heldDigest = definition?.takeIf { it.isHeld }?.customSchemaVersion.orEmpty()
            val serverScoreUsable = remote != null && remote.customSchemaVersion == heldDigest
            if (remote != null && !serverScoreUsable && serverNote == null) {
                serverNote = "These figures are this device's own. The server counts this workshop's " +
                    "own questions using a version of them this phone has not read yet, so its " +
                    "figures are not shown here — they would not be counting the same things. " +
                    "Open this screen again with a connection to pick up the current set."
            }
            val merged = local.map { stage ->
                // `custom` counted with the other two: a stage whose only answers are the designer's
                // own would otherwise be judged untouched, and the server's score adopted over the
                // very work this handset is holding.
                val touchedLocally = draft?.stages?.get(stage.stageKey)?.let {
                    it.values.isNotEmpty() || it.rows.isNotEmpty() || it.custom.isNotEmpty()
                } ?: false
                // Only fall back to the server for a stage this device has never touched — see the
                // KDoc. Anything else would let a stale snapshot overwrite this morning's work.
                if (touchedLocally || !serverScoreUsable) stage
                else remote?.completeness?.get(stage.stageKey)?.let { fromServer(it, stage) } ?: stage
            }
            (remote?.title ?: draft?.title.orEmpty()) to merged
        }.onSuccess { (name, merged) ->
            title = name
            stages = merged
        }.onFailure { onError(it.message ?: "Unable to read this workshop's progress.") }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Reading progress…", color = MaterialTheme.field.muted, fontSize = 13.sp)
            }
            return@Column
        }

        val overall = remember(stages) { overallPercent(stages) }
        val done = remember(stages) { stages.count { it.isComplete } }

        Text(
            title.ifBlank { "Design workshop" },
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp
        )
        LinearProgressIndicator(progress = { overall / 100f }, modifier = Modifier.fillMaxWidth())
        Text(
            "$overall% overall · $done of ${stages.size} stages complete",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )
        serverNote?.let { Text(it, color = MaterialTheme.field.warning, fontSize = 12.sp) }

        Button(onClick = onOpenReport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Generate the report")
        }

        /*
         * THE FILES ALREADY GENERATED — and what changed in the record between any two of them.
         *
         * OUTLINED, directly under the filled report button, for the reason the two controls below it
         * are outlined: the report is what the workshop is FOR, and this is a record OF the reports.
         *
         * NOT HIDDEN FOR A LOCAL-ONLY WORKSHOP, unlike the provenance row further down. That row is
         * hidden because it has nothing to say until the server has the workshop; this screen has
         * something to say precisely then — `DW_REPORT_HISTORY_LOCAL_ONLY` explains that a file
         * generated before the workshop reaches the server is never logged at all — and that is a
         * live problem, which silence would leave a designer to discover from a ministry.
         *
         * The label is [DW_REPORT_HISTORY_TITLE] and not a literal, the rule the provenance row
         * already follows, so the control and the heading it opens cannot drift apart.
         */
        onOpenReportHistory?.let { openHistory ->
            OutlinedButton(onClick = openHistory, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(DW_REPORT_HISTORY_TITLE)
            }
        }

        // OUTLINED, under the filled report button, because the report is what the workshop is FOR and
        // the cards are a tool used along the way — two filled buttons of equal weight would make a
        // designer choose between them at the moment they are looking for the report.
        OutlinedButton(onClick = onOpenCodes, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Cards & tags")
        }

        // Outlined for the same reason as the cards button above: the report is what the workshop is
        // FOR, and both of these are tools used along the way to it.
        OutlinedButton(onClick = onOpenPhotos, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Import photographs")
        }

        /*
         * WHO CAN OPEN THIS WORKSHOP — and, for everyone who may not decide that, the answer in
         * words instead.
         *
         * The gate is `require_admin` on all three routes in `api/routes/design_workshop_viewers.py`
         * — `deps.is_admin`, so ADMIN and MASTER_ADMIN only. Deliberately NOT the creator and NOT
         * `can_run_design_workshops`: a designer may not hand out access to their own workshop,
         * because access that is in its owner's gift freezes the day the owner leaves.
         *
         * THE NON-ADMIN ARM IS A SENTENCE AND NOT A DISABLED BUTTON, and that is the decision worth
         * defending. A greyed control refuses a tap without saying why, which is how somebody
         * concludes the app is broken; an absent one leaves the second designer's own question —
         * "how do I get my colleague into this record" — with no answer anywhere on the phone, and
         * the workshop they cannot open still telling them it does not exist. So the row is always
         * here: a button for the account that can act, and the name of who to ask for the account
         * that cannot. Nothing is offered that the API would refuse.
         */
        val mayAdminister = mayAdministerViewers(viewer)
        when {
            mayAdminister && onServer -> OutlinedButton(
                onClick = onOpenViewers,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Group, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Who can open this workshop")
            }

            mayAdminister -> Text(
                "Who can open this workshop is decided on the server, and this one has not been " +
                    "sent there yet. Send it from the workshop list first — until then nobody else " +
                    "could open it in any case.",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )

            else -> Text(
                "Only you, the designers an administrator has added to this workshop, and admins " +
                    "can open it. Deciding who else may is administration — ask an administrator " +
                    "to add your co-designer.",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
        }

        /*
         * WHO INSPECTS THIS WORKSHOP - the fifth scope, and a DIFFERENT question from the row above.
         *
         * The gate is `require_admin` again, on both routes in
         * `api/routes/design_workshop_inspections.py` - but the ARGUMENT for it is stronger here than
         * the handover argument that makes the viewers screen admin-only:
         *
         *     THE INSPECTED MUST NOT CHOOSE THE INSPECTOR.
         *
         * If a designer could put somebody on their own workshop as its inspector, or take somebody
         * off it, the inspection is worth nothing. There is deliberately no "suggest an inspector"
         * route either, because a suggestion an admin rubber-stamps is the same thing wearing a queue.
         *
         * HIDDEN FROM A NON-ADMIN RATHER THAN EXPLAINED, which is the OPPOSITE treatment from the
         * viewer row directly above and the same treatment as the provenance row directly below. The
         * split follows the position the refusal leaves a designer in, not the rule. A designer
         * refused the viewer roster still HAS the question it answers - their co-designer is standing
         * next to them unable to open the record - so silence there leaves a live problem with no
         * answer anywhere on the phone. A designer refused THIS has no question at all, and the
         * honest reason is uncomfortable enough to be worth writing down: whether their work is being
         * examined, and by whom, is not their business to arrange. An explained-but-refused control
         * would invite exactly the conversation the tier exists to prevent.
         *
         * AND ONLY FOR A WORKSHOP THE SERVER HAS. There is nothing to inspect in a workshop that has
         * never left this phone, and no row for an assignment to point at.
         */
        if (mayAdminister && onServer) {
            OutlinedButton(onClick = onOpenInspectors, modifier = Modifier.fillMaxWidth()) {
                // `FindInPage` is the same glyph the menu row for the inspector's own list carries,
                // which is the point: an admin appointing an inspector and the inspector reading the
                // result are two halves of one feature, and one glyph is what says so.
                Icon(Icons.Filled.FindInPage, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Who inspects this workshop")
            }
        }

        /*
         * THE ADMIN AUTHORSHIP & DIVERGENCE VIEW — and, for everyone else, NOTHING AT ALL.
         *
         * The gate is `is_admin` again: `workshop_provenance` refuses anyone below ADMIN, because the
         * comparison crosses OUT of this workshop into the shared record tables and reports one
         * account's data beside another's.
         *
         * SO WHY IS THIS ROW HIDDEN WHEN THE ONE ABOVE IT IS A SENTENCE? Because the two refusals
         * leave a designer in completely different positions, and the treatment follows the position
         * rather than the rule. A designer refused the viewer roster still HAS the question it
         * answers — their co-designer is standing next to them unable to open the record — so silence
         * there leaves a live problem with no answer anywhere on the phone. A designer refused this
         * has no question at all: they have lost nothing. Every per-field stamp is already under
         * their own boxes on every one of the 22 stages, off the ordinary stage read, and what this
         * adds is only the canonical column. A greyed or explained control would advertise a
         * capability they cannot get and do not need — which is exactly how the create control is
         * handled, and for the same reason.
         *
         * AND ONLY FOR A WORKSHOP THE SERVER HAS. A workshop that has never left this phone has
         * hydrated nothing through the server, so there is no comparison to make; unlike the viewer
         * row there is no pending question for silence to leave unanswered, so it says nothing rather
         * than explaining an absence nobody is waiting on. ([DwProvenanceScreen] still renders that
         * state in words, for an arrival by any other route.)
         *
         * NOT GATED ON `canViewProvenance`. That column is a real grant on this handset and it opens
         * a DIFFERENT feature — the per-field edit history on the View Data screen, for the record
         * tables. `workshop_provenance` does not consult it, so ORing it in would put this control in
         * front of a grantee the API refuses.
         */
        if (mayReadWorkshopProvenance(viewer) && onServer) {
            OutlinedButton(onClick = onOpenProvenance, modifier = Modifier.fillMaxWidth()) {
                // AutoMirrored: the glyph is two arrows pointing at each other across a divide, and
                // in an RTL layout it has to flip with the text or it points the wrong way at the
                // wrong column. The plain `Icons.Filled` variant is deprecated for exactly this.
                Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                // The web's own name for the page, and THE SAME CONSTANT THE SCREEN TITLES ITSELF
                // WITH — a control whose label drifts from the heading it opens sends a reader
                // looking for a second feature. NOT "Check for problems" or anything else that would
                // tell an admin, before they have read a word of it, that what they are about to
                // look at is a fault list. Divergence is not an error.
                Text(DW_PROVENANCE_TITLE)
            }
        }

        HorizontalDivider()

        DwDictationConsentRow(
            consent = consent,
            // `can_run_design_workshops` — {DESIGNER, ADMIN, MASTER_ADMIN}, the set the server's own
            // consent route is gated on. NOT the rank ladder: a PROFESSOR outranks a designer and may
            // not run a workshop, so a threshold would put these two buttons in front of a 403.
            mayRecord = viewer != null && FieldPermissions.canRunDesignWorkshops(viewer),
            busy = recordingConsent,
            note = consentNote,
            onRecord = { decision ->
                recordingConsent = true
                consentNote = null
                scope.launch {
                    val written = dwRecordDictationConsent(
                        context = appContext,
                        repository = repository,
                        workshopId = workshopId,
                        decision = decision,
                        actor = viewer,
                    )
                    consent = written.consent
                    consentNote = dwConsentRecordedNote(
                        consent = dwTier3ConsentOf(written.consent.decision),
                        synced = written.consent.synced,
                        storedOnDevice = written.storedOnDevice,
                        // The server ANSWERED and refused — its own sentence, which names the fix.
                        // Null for the ordinary courtyard case of no connection at all.
                        serverRefusal = written.serverRefusal,
                    )
                    // Held for the rest of the run as well, so walking straight into a stage honours the
                    // answer even in the case where the draft write is what failed.
                    if (written.storedOnDevice || written.consent.synced) {
                        DwDictationRun.rememberConsentAnswer(workshopId, decision)
                    }
                    recordingConsent = false
                }
            },
        )

        HorizontalDivider()

        WorkshopSearchPanel(
            index = searchIndex,
            // The receiving half. [DwWorkshopSearch.focusOf] is the ONE place a hit becomes an
            // address, so the box and the form cannot disagree about which field was asked for.
            onOpenHit = { hit -> onOpenStage(hit.stageKey, DwWorkshopSearch.focusOf(hit)) },
        )

        HorizontalDivider()

        stages.forEach { stage ->
            StageIndexRow(
                stage = stage,
                expanded = expanded == stage.stageKey,
                addresses = addresses[stage.stageKey].orEmpty(),
                onToggle = { expanded = if (expanded == stage.stageKey) null else stage.stageKey },
                onOpen = { onOpenStage(stage.stageKey, null) },
                onOpenField = { focus -> onOpenStage(stage.stageKey, focus) }
            )
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

// =================================================================================================
// May this workshop's recordings leave the device? — plan §6 answer 3
// =================================================================================================

/**
 * THE ROW WHERE AN ARTISAN'S ANSWER IS PUT ON RECORD, and the question in the words it is asked in.
 *
 * ── WHY THE QUESTION IS PRINTED IN FULL RATHER THAN REDUCED TO A SWITCH ────────────────────────
 *
 * This is the whole difference between a consent and a checkbox. The person who ASKS is the designer
 * holding this phone, and if the screen says only "Allow cloud dictation" then what the artisan is
 * actually asked is whatever that designer improvises — no two artisans asked the same thing, and no
 * way afterwards to know what any of them agreed to. So [DW_CONSENT_QUESTION] is on screen, in full,
 * above the two buttons, and the buttons are worded as the ARTISAN'S ANSWER ("they agreed") rather than
 * as a setting the designer is choosing for them.
 *
 * ── NO GREYED CONTROLS, IN EITHER DIRECTION ────────────────────────────────────────────────────
 *
 * The viewers row above states the rule and this row follows it: an account that may not record this
 * gets a SENTENCE naming who can, never a disabled button, because "a greyed control refuses a tap
 * without saying why, which is how somebody concludes the app is broken". And the button matching the
 * CURRENT answer is not disabled either — recording the same answer again is a legitimate act (an
 * artisan asked a second time and saying the same thing is worth a second row in the log), and the
 * state sentence above already says which answer is on record. The only disabling here is while a
 * write is in flight, which is the same reason the microphone disables while a clip is uploading.
 */
@Composable
private fun DwDictationConsentRow(
    consent: DraftConsent,
    mayRecord: Boolean,
    busy: Boolean,
    note: String?,
    onRecord: (DwTier3Consent) -> Unit,
) {
    val state = dwTier3ConsentOf(consent.decision)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            DW_CONSENT_ROW_TITLE,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        // WHAT IS ON RECORD AND WHAT IT MEANS FOR THE MICROPHONE, in one sentence, because "not
        // recorded" on its own tells a designer nothing about why dictation behaved differently on the
        // stage they have just come from.
        Text(
            dwConsentStateSentence(state, consent),
            color = if (state == DwTier3Consent.GRANTED) {
                MaterialTheme.field.body
            } else {
                MaterialTheme.field.warning
            },
            fontSize = 12.sp,
        )
        if (mayRecord) {
            Text(DW_CONSENT_QUESTION, color = MaterialTheme.field.muted, fontSize = 12.sp)
            // STACKED AND FULL WIDTH, not side by side: both labels are sentences rather than verbs,
            // and two of them in one row would truncate to "They agreed —…" on the narrow handsets this
            // fleet runs, which is where a consent control must be least ambiguous.
            OutlinedButton(
                onClick = { onRecord(DwTier3Consent.GRANTED) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(DW_CONSENT_YES_LABEL) }
            OutlinedButton(
                onClick = { onRecord(DwTier3Consent.REFUSED) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(DW_CONSENT_NO_LABEL) }
        } else {
            Text(DW_CONSENT_NOT_YOURS_TO_RECORD, color = MaterialTheme.field.muted, fontSize = 12.sp)
        }
        note?.let {
            Text(
                it,
                color = MaterialTheme.field.muted,
                fontSize = 12.sp,
                // Announced, because the visible change this write makes is one line of prose several
                // rows away from the button that caused it.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * Write a recorded answer down on THIS DEVICE first, then try to tell the server.
 *
 * ── THE ORDER IS THE WHOLE DESIGN ──────────────────────────────────────────────────────────────
 *
 * The draft is written first and the push is attempted second, because the artisan is standing there
 * and the courtyard has no signal. A control that recorded consent only when it could reach the server
 * would refuse the answer at the exact moment it was given, and the designer would be told to walk to
 * the guest house and ask again. So the answer lands in the document the device owns, the ladder on this
 * phone honours it immediately, and the server hears about it when there is something to hear it over.
 *
 * [WorkshopDraftStore.update] and not `updateBookkeeping`: a designer recording an artisan's answer is an
 * act they performed, and it belongs in `updatedAt` — the workshop list sorts by it, and this is
 * fieldwork rather than a background refresh.
 *
 * ── AND THE PUSH NEVER THROWS INTO THE SCREEN ──────────────────────────────────────────────────
 *
 * A failed push is not a failed recording. The answer IS recorded — on this device, where the gate that
 * matters to this phone reads it — so raising the network failure would tell a designer their consent
 * was not taken when it was. What the sentence they read says instead is that it has not reached the
 * server yet, which is true and is the one thing they could not otherwise know: the server checks its
 * own column on the upload, so until this arrives up there, dictation sent out may still be refused.
 */
private suspend fun dwRecordDictationConsent(
    context: Context,
    repository: WorkshopRepository,
    workshopId: String,
    decision: DwTier3Consent,
    actor: UserDto?,
): DwConsentWriteResult {
    // WHEN THE ARTISAN ANSWERED, on this device's clock, which is the moment the server stores as the
    // consent's own timestamp — its `createdAt` records when it heard, and on this fleet the two can
    // differ by a fortnight. A device clock more than fifteen minutes ahead of the server's is refused
    // rather than corrected, because a corrected stamp is a fabricated fact about when somebody agreed.
    val recorded = DraftConsent(
        decision = dwTier3ConsentToken(decision),
        // TRUNCATED TO MILLISECONDS, which is not tidiness. `Instant.now()` on Android can carry
        // nanoseconds, and the moment is parsed server-side by `datetime.fromisoformat` against a field
        // capped at 32 characters — so a nine-digit fraction risks a 422 on a value the designer can do
        // nothing about, and a 422 here is not a visible failure: the push simply never succeeds, the
        // answer stays marked unsent for ever, and every upload from this workshop goes on being refused
        // up there. Milliseconds are more precision than "when the artisan answered" has any use for.
        recordedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),
        recordedById = actor?.id,
        recordedByName = actor?.name,
        synced = false,
    )
    val draft = runCatching {
        WorkshopDraftStore.update(context, workshopId) { it.copy(consent = recorded) }
    }.getOrNull()
    val remoteId = draft?.remoteId ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
    if (remoteId == null) return DwConsentWriteResult(recorded, storedOnDevice = draft != null)
    val failure = runCatching {
        repository.designWorkshopRecordDictationConsent(
            workshopId = remoteId,
            decision = decision,
            recordedAt = recorded.recordedAt,
        )
    }.exceptionOrNull()
    if (failure != null) {
        return DwConsentWriteResult(
            recorded,
            storedOnDevice = draft != null,
            serverRefusal = dwConsentPushRefusal(failure),
        )
    }
    val acknowledged = recorded.copy(synced = true)
    // Bookkeeping and not an edit: the designer's act was the tap, and it has already been stamped.
    // Stamping it again because a request came back would make one act look like two.
    runCatching {
        WorkshopDraftStore.updateBookkeeping(context, workshopId) { it.copy(consent = acknowledged) }
    }
    return DwConsentWriteResult(acknowledged, storedOnDevice = draft != null)
}

/**
 * Where a recorded answer actually landed, so the sentence about it can be true.
 *
 * [storedOnDevice] IS NOT DECORATION. `WorkshopDraftStore.update` can fail — a full disk, a quarantined
 * draft — and the answer is then only wherever the push put it. A control that reported "saved on this
 * phone" regardless would tell a designer an artisan's answer was on record when nothing had kept it.
 */
private data class DwConsentWriteResult(
    val consent: DraftConsent,
    val storedOnDevice: Boolean,
    /**
     * The server's own sentence, when the server ANSWERED and refused — never when it was merely
     * unreachable. See [dwConsentPushRefusal]; null is the ordinary courtyard state.
     */
    val serverRefusal: String? = null,
)

/**
 * A push failure the designer has to be told about, or null for one that will clear itself.
 *
 * ── WHY THE TWO CANNOT SHARE A SENTENCE ─────────────────────────────────────────────────────────
 *
 * A dropped connection is the ORDINARY state here: the answer is in the draft, the gate on this phone
 * honours it, and the next open with signal carries it up. Saying anything about that would turn the
 * normal case into an error.
 *
 * A 4xx is the opposite. The server has read the body and refused it, and it will refuse the identical
 * body on every open for ever — so the note that says "it goes to the server the next time this
 * workshop is opened here with a connection" is a promise that can never come true, repeated daily,
 * while every dictation from this workshop goes on being refused up there with a sentence a designer
 * cannot connect to a tap they made a fortnight ago. The one refusal a field phone actually earns is a
 * wrong date: the consent route declines a `recordedAt` more than fifteen minutes in its own future
 * rather than storing a corrected one, and its 422 names the fix in words.
 *
 * 401 AND 429 ARE EXCLUDED, and that is the whole reason this is not "any HttpException". A 401 clears
 * when the token is renewed and a 429 is the courtesy limiter answering about this instant; both DO
 * succeed on a later open, so reporting them as permanent would be the false claim in the other
 * direction.
 */
private fun dwConsentPushRefusal(failure: Throwable): String? {
    val http = failure as? HttpException ?: return null
    val code = http.code()
    if (code !in 400..499 || code == 401 || code == 429) return null
    return http.apiErrorMessage(
        "The server would not record that answer, and did not say why. Tell whoever runs the server " +
            "before dictating anything from this workshop."
    )
}

/**
 * Reconcile the answer on this device with the answer on the server, and carry an unsent one over.
 *
 * ── WHICH COPY WINS IS [dwConsentMerge]'S DECISION AND NOT THIS FUNCTION'S ─────────────────────
 *
 * It used to be decided here, in four lines, on the rule "an unsent local answer is newer by
 * construction" — which is false, and whose failure is a WITHDRAWN CONSENT COMING BACK: a GRANTED
 * recorded offline on the 3rd pushed over a REFUSED recorded on the web on the 5th, silently, on the
 * 9th. The ordering rule and the fail-closed tie-break are now a pure function with the whole argument
 * against it, tested on the desktop JVM, because a rule about whose voice may leave a device is not a
 * rule to hold in a composable where nothing can assert it.
 *
 * What is left here is the IO each answer implies, and nothing else.
 *
 * ── AND WHY NOTHING HERE MAY THROW ─────────────────────────────────────────────────────────────
 *
 * This runs inside the screen's one load, whose failure path calls `onError` and replaces the whole list
 * of stages with a message. A consent that could not be reconciled — or a push that failed — must not
 * cost a designer the screen they opened to find a missing field on. Every write and the push are
 * wrapped, and the worst case is that the answer stays as it was, unsent, and is offered again next time.
 */
private suspend fun dwResolveDictationConsent(
    context: Context,
    repository: WorkshopRepository,
    workshopId: String,
    onDevice: DraftConsent,
    remote: DesignWorkshopDetailDto?,
    remoteId: String?,
): DraftConsent = when (
    dwConsentMerge(
        onDevice = onDevice,
        serverDecision = remote?.dictationConsent,
        serverRecordedAt = remote?.dictationConsentAt,
        // NOT `remote?.dictationConsent != null`: the field is defaulted, so a payload from a server
        // that predates the column decodes as NOT_RECORDED and would otherwise look like an answer.
        // What is being asked is whether the server was READ at all.
        serverKnown = remote != null,
    )
) {
    DwConsentMerge.KEEP_DEVICE -> onDevice
    DwConsentMerge.MARK_SYNCED -> dwMarkSynced(context, workshopId, onDevice)
    DwConsentMerge.PUSH_DEVICE ->
        dwPushUnsent(context, repository, workshopId, onDevice, remoteId)
    // Reachable only with `serverKnown`, so `remote` is there; an elvis rather than a `!!` because a
    // crash on the workshop screen would cost a designer the list of missing fields they opened it for.
    DwConsentMerge.TAKE_SERVER ->
        remote?.let { dwLearnFromServer(context, workshopId, it) } ?: onDevice
}

/**
 * The server's answer is the one to honour. Written down as BOOKKEEPING and not as an edit.
 *
 * Nobody on this handset performed an act, so `updatedAt` must not move: the workshop list sorts by it,
 * and a background refresh that reordered the list under a designer's thumb would look like somebody
 * else editing the record. `updateBookkeeping` also refuses to CREATE a draft that is not already there
 * — bookkeeping about a workshop that has been deleted must not resurrect it as an empty document —
 * which is why [DwDictationRun.consentAnswerSeen] exists to carry the answer to the stage screens in
 * exactly that case.
 */
private suspend fun dwLearnFromServer(
    context: Context,
    workshopId: String,
    remote: DesignWorkshopDetailDto,
): DraftConsent {
    val learned = DraftConsent(
        decision = dwTier3ConsentToken(dwTier3ConsentOf(remote.dictationConsent)),
        recordedAt = remote.dictationConsentAt,
        recordedById = remote.dictationConsentById,
        recordedByName = remote.dictationConsentByName,
        synced = true,
    )
    runCatching {
        WorkshopDraftStore.updateBookkeeping(context, workshopId) { it.copy(consent = learned) }
    }
    return learned
}

/**
 * A consent recorded in a courtyard, carried to the server the next time this screen is opened with
 * signal.
 *
 * ── THIS IS THE CARRIER, AND IT IS NOT THE DRAFT SYNC PASS ─────────────────────────────────────
 *
 * `WorkshopSync`'s pass pushes the workshop create, the stages and the media; there is no
 * workshop-HEADER write anywhere in it and `DraftSyncState` has no slot for one. So this screen is the
 * carrier: it is the screen a designer is sent to by every sentence about a missing consent, it already
 * reads the draft and reaches the server on every open, and one more push costs nothing beside the
 * request it is already making.
 *
 * WHAT THAT LEAVES OPEN, SAID PLAINLY RATHER THAN LEFT TO BE DISCOVERED: a workshop whose consent was
 * recorded offline and whose designer never opens this screen again with a connection keeps that answer
 * on the device alone. The ladder here honours it, so dictation behaves correctly on this phone; the
 * server, which checks its own column, refuses those uploads with its own sentence. The fix is a
 * `DraftSyncState.consent` record and a push step after the create in `WorkshopSync` — the create is
 * what produces the `remoteId` this needs — and it is a change to a file this lane did not otherwise
 * touch.
 */
private suspend fun dwPushUnsent(
    context: Context,
    repository: WorkshopRepository,
    workshopId: String,
    onDevice: DraftConsent,
    remoteId: String?,
): DraftConsent {
    // No server row to push against: a workshop created in a courtyard has none until the sync pass
    // creates it, and the answer waits in the draft, where the gate on this phone already reads it.
    if (remoteId == null) return onDevice
    val pushed = runCatching {
        repository.designWorkshopRecordDictationConsent(
            workshopId = remoteId,
            decision = dwTier3ConsentOf(onDevice.decision),
            recordedAt = onDevice.recordedAt,
        )
    }.isSuccess
    return if (pushed) dwMarkSynced(context, workshopId, onDevice) else onDevice
}

/** The server has this answer. Bookkeeping only — nobody performed an act, so nothing is re-stamped. */
private suspend fun dwMarkSynced(
    context: Context,
    workshopId: String,
    onDevice: DraftConsent,
): DraftConsent {
    val acknowledged = onDevice.copy(synced = true)
    runCatching {
        WorkshopDraftStore.updateBookkeeping(context, workshopId) { it.copy(consent = acknowledged) }
    }
    return acknowledged
}

/**
 * "Where did I write about indigo?" — asked of a whole 22-stage workshop, with no network.
 *
 * IT COSTS NOTHING AND ASKS FOR NOTHING. The index is built by [DwWorkshopSearch] from the draft this
 * screen has ALREADY read out of `filesDir`, so there is no request, no spinner and no difference in
 * behaviour between a designer at a desk and one who has had no signal for three days. That is not a
 * nicety: a search that needed a server would be missing in exactly the fortnight the fieldwork
 * happens in.
 *
 * WHAT IS SAID OUT LOUD, because none of it is guessable from an empty list:
 *   • how much was searched — values, and how many stages actually hold text;
 *   • that the result list is capped, and what the true total was;
 *   • that a one-character query was refused rather than answered;
 *   • that a query of punctuation had nothing in it to search for.
 * A list that quietly stops is indistinguishable from a workshop with nothing in it, and that is the
 * single most repeated defect in this repository. The wording is the web panel's, verbatim, because a
 * designer moves between the two surfaces inside one workshop.
 */
@Composable
private fun WorkshopSearchPanel(
    index: DwWorkshopSearchIndex,
    onOpenHit: (DwWorkshopSearchHit) -> Unit,
) {
    /*
     * KEYED, exactly as every one of the seven state slots above is keyed on the workshop id.
     *
     * EVERY DESTINATION IN THIS PART OF THE APP RENDERS THROUGH ONE COMPOSITION SLOT. [MainActivity]
     * says so itself where it wraps `rememberScrollState` in `key(screen)`, having shipped that bug
     * once: two screens of the same `when` branch share every unkeyed `remember` inside it, and a
     * form left half-scrolled opened the next one already past its own first fields.
     *
     * A SECOND WORKSHOP CANNOT REACH THAT TODAY, and the guard is here anyway. The only route to one
     * is `WorkshopListScreen`'s `onOpen`, which means passing through the workshop LIST — a different
     * branch of the same `when`, so this subtree is torn down and every `remember` in it forgotten on
     * the way. What keys buy is that the box cannot outlive the draft under it should any of the four
     * routes into this screen ever set a second workshop id directly: an unkeyed `remember` would
     * then leave the word typed against the previous workshop in the box while the results beneath it
     * were rebuilt from a different draft — a query the designer never typed, answered honestly.
     *
     * ON THE INDEX rather than on `workshopId`, which this composable is not given.
     * [DwWorkshopSearch.buildIndex] and `emptyIndex()` both mint a fresh instance, so it resets on a
     * new workshop; and because the panel is not drawn until `loading` is false, the one rebuild that
     * happens within a workshop happens before there is a box to clear.
     */
    var query by remember(index) { mutableStateOf("") }
    /*
     * Answered synchronously on the keystroke, with no debounce.
     *
     * A debounce makes every designer wait the same fixed delay whether or not the device needed it,
     * and a query against a built index is a binary search and a walk — microseconds against the tens
     * of milliseconds a frame has. `remember` keyed on both inputs is what stops it re-running on
     * every unrelated recomposition of a screen that also holds 22 progress bars.
     */
    val result = remember(index, query) { DwWorkshopSearch.search(index, query) }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Search this workshop") },
        placeholder = {
            Text(
                "A word, a name, a product code — in any script",
                color = MaterialTheme.field.placeholder,
                fontSize = 13.sp,
            )
        },
        singleLine = true,
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.field.muted,
                modifier = Modifier.size(18.dp),
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.field.muted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        // The box takes Odia and Hindi as often as English, and a keyboard that autocorrects a
        // transliterated village name changes what was typed into something that matches nothing.
        // Capitalisation is turned off with it even though the fold lowercases everything, because a
        // designer watching their own word being changed under their finger stops trusting the box.
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth(),
    )

    /*
     * THE STATUS LINE, and it is a polite live region for a reason: nothing announces a list that
     * re-renders under an input still holding focus, so a designer using TalkBack would type six
     * letters into silence. Polite, never assertive — it must not interrupt them mid-word.
     */
    Text(
        when {
            result.status == DwSearchStatus.IDLE ->
                "Searches every written answer on this device — ${index.fieldCount} across " +
                    "${index.stageCount} stage${if (index.stageCount == 1) "" else "s"}. " +
                    "No connection needed."

            result.status == DwSearchStatus.TOO_SHORT -> "Type at least $DW_MIN_QUERY_CHARS characters."

            result.status == DwSearchStatus.NO_TERMS ->
                "There are no words or numbers in that to search for."

            result.total == 0 ->
                "Nothing in this workshop holds " +
                    (if (result.terms.size == 1) "that word" else "all of those words") +
                    ". Every answer saved on this device was searched — ${index.fieldCount} of them."

            result.truncated ->
                "Showing the $DW_MAX_HITS closest of ${result.total} matching answers. " +
                    "Add another word to narrow it."

            else -> "${result.total} matching answer${if (result.total == 1) "" else "s"}."
        },
        color = MaterialTheme.field.muted,
        fontSize = 12.sp,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )

    if (result.hits.isNotEmpty()) {
        // A plain Column, not a LazyColumn: this screen is already inside a `verticalScroll`, and
        // nesting a lazy list in one is an unbounded-height crash. The cap on the module's side is
        // what keeps this to at most 60 rows.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
            result.hits.forEach { hit -> SearchHitRow(hit = hit, onOpen = { onOpenHit(hit) }) }
        }
    }
}

/** One result: where it is, then what it says. */
@Composable
private fun SearchHitRow(hit: DwWorkshopSearchHit, onOpen: () -> Unit) {
    val markStyle = SpanStyle(
        background = MaterialTheme.colorScheme.primaryContainer,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        // Weight AS WELL as a wash, so the answer to "why is this row here" survives a screen read in
        // sunlight and a reader who cannot separate the two purples.
        fontWeight = FontWeight.SemiBold,
    )
    val quoted = remember(hit, markStyle) { markedSnippet(hit, markStyle) }

    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                // Read out instead of the bare "double tap to activate": sixty rows that all announce
                // the same verb are sixty rows a screen-reader user has to open to tell apart.
                onClickLabel = "Open ${hit.fieldLabel} in stage ${hit.stageNumber}",
                onClick = onOpen,
            )
            .padding(vertical = 6.dp)
    ) {
        Text(
            buildString {
                // The stage NUMBER as well as its title: a designer navigates this workshop by
                // number, and "13" is what they will look for in the list below.
                append("${hit.stageNumber}. ${hit.stageTitle} · ${hit.entityTitle}")
                // The row, titled exactly as the collection list titles it, so the designer
                // recognises what they will land on rather than being shown a second name for it.
                hit.recordTitle?.let { append(" · $it") }
                append(" · ${hit.fieldLabel}")
            },
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(quoted, color = MaterialTheme.field.body, fontSize = 13.sp)
    }
}

/**
 * The snippet as one string, with the matched runs marked.
 *
 * SEGMENTS IN, SPANS OUT, and never a marked-up string parsed back apart: the text being highlighted
 * is prose a designer typed, including — legitimately — whatever character a marker would have used.
 */
private fun markedSnippet(hit: DwWorkshopSearchHit, mark: SpanStyle): AnnotatedString = buildAnnotatedString {
    // The ellipses are the module's `clippedStart`/`clippedEnd`, drawn rather than inferred from the
    // length: a snippet that happens to start at character 0 of a long field is not clipped, and one
    // that fills the window exactly still is.
    if (hit.snippet.clippedStart) append("…")
    for (segment in hit.snippet.segments) {
        if (segment.match) withStyle(mark) { append(segment.text) } else append(segment.text)
    }
    if (hit.snippet.clippedEnd) append("…")
}

@Composable
private fun StageIndexRow(
    stage: DwStageCompleteness,
    expanded: Boolean,
    /** Where each of [DwStageCompleteness.missing] lives, by label. Missing entries open the stage. */
    addresses: Map<String, DwStageFocus>,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onOpenField: (DwStageFocus?) -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
            ) {
                Text(
                    stage.number.toString().padStart(2, '0'),
                    color = MaterialTheme.field.muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        stage.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    LinearProgressIndicator(
                        progress = { stage.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        buildString {
                            append("${stage.percent}%")
                            if (stage.requiredTotal > 0) {
                                append(" · ${stage.requiredFilled}/${stage.requiredTotal} required")
                            } else {
                                // Said out loud rather than left as a bare 100%. A stage with nothing
                                // required reads as complete by construction, and a designer who
                                // does not know that will trust the figure and skip the stage.
                                append(" · nothing required here")
                            }
                            val rows = stage.collectionCounts.values.sum()
                            if (rows > 0) append(" · $rows entr${if (rows == 1) "y" else "ies"}")
                        },
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Open stage ${stage.number}",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (stage.missing.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
                ) {
                    Text(
                        "${stage.missing.size} required field${if (stage.missing.size == 1) "" else "s"} still missing",
                        color = MaterialTheme.field.onWarningContainer,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .background(MaterialTheme.field.warningContainer, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onToggle, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (expanded) "Hide what is missing" else "Show what is missing",
                            tint = MaterialTheme.field.muted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                if (expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        stage.missing.forEach { label ->
                            // EVERY ENTRY IS A DESTINATION, including the ones with no address: a
                            // label the registry walk could not place still opens its stage, which
                            // is the same degradation `DwSubmissionReadiness` builds into `href`.
                            // A row that does nothing when tapped is worse than a row that does
                            // less than the row beside it.
                            val focus = addresses[label]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        // Read out instead of the bare "double tap to activate":
                                        // twelve rows that all announce the same verb are twelve
                                        // rows a screen-reader user has to open to tell apart.
                                        onClickLabel = if (focus != null) {
                                            "Open this field in stage ${stage.number}"
                                        } else {
                                            "Open stage ${stage.number}"
                                        },
                                        onClick = { onOpenField(focus) }
                                    )
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(
                                    "· $label",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Adopt the server's score for a stage this device has never touched.
 *
 * [local] supplies the title and number rather than the payload, so a server one registry behind
 * cannot rename a stage on screen while the form beneath it is drawn from this build's registry —
 * which would leave the index and the stage screen disagreeing about what stage 14 is called.
 */
private fun fromServer(dto: StageCompletenessDto, local: DwStageCompleteness): DwStageCompleteness =
    local.copy(
        requiredTotal = dto.requiredTotal,
        requiredFilled = dto.requiredFilled,
        optionalTotal = dto.optionalTotal,
        optionalFilled = dto.optionalFilled,
        collectionCounts = dto.collectionCounts,
        missing = dto.missing,
    )
