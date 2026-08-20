package com.designprototype.workshop.ui.designworkshop

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.designprototype.workshop.data.DwStageProvenanceDto
import com.designprototype.workshop.data.DwFieldStampDto
import com.designprototype.workshop.data.AppScope
import com.designprototype.workshop.data.CUSTOM_ENTITY_KEY
import com.designprototype.workshop.data.ConnectivityObserver
import com.designprototype.workshop.data.DW_ROW_KEY_SEPARATOR
import com.designprototype.workshop.data.DraftMedia
import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.DwCustomCache
import com.designprototype.workshop.data.DwDictationRun
import com.designprototype.workshop.data.DwTier3Consent
import com.designprototype.workshop.data.DwImageQuality
import com.designprototype.workshop.data.DwStageFocus
import com.designprototype.workshop.data.DwStageRefusalReport
import com.designprototype.workshop.data.DwTier
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.StageBucketDto
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.StagePush
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.WorkshopSyncEngine
import com.designprototype.workshop.data.collections
import com.designprototype.workshop.data.computeStageCompleteness
import com.designprototype.workshop.data.customFieldsForStage
import com.designprototype.workshop.data.dwCarryHoldings
import com.designprototype.workshop.data.dwCustomDefinition
import com.designprototype.workshop.data.dwDecodeStageRefusals
import com.designprototype.workshop.data.dwFoldServerStage
import com.designprototype.workshop.data.dwHoldingsFrom
import com.designprototype.workshop.data.dwRestoreStageRefusals
import com.designprototype.workshop.data.dwRowId
import com.designprototype.workshop.data.entityKey
import com.designprototype.workshop.data.dwTier3ConsentOf
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.data.liveFields
import com.designprototype.workshop.data.rowTitleField
import com.designprototype.workshop.data.rowsFor
import com.designprototype.workshop.data.singleton
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * One of the 22 stages, rendered entirely from the registry.
 *
 * The layout follows the tiers the source matrix declares, and the ordering is a data decision rather
 * than a visual one:
 *
 *  - BASIC and STANDARD fields are shown together, in registry order, because BASIC is what the
 *    completeness gate counts and STANDARD is what most workshops can also answer. Splitting them
 *    into two visible sections would put a heading between two questions an interviewer asks in one
 *    breath.
 *  - ADVANCED sits behind a "More detail" disclosure, closed by default. That is the whole reason the
 *    tiers exist: a workshop held in a village without power must still be able to produce a complete
 *    report, and confronting the designer with two hundred fields they cannot answer is how a form
 *    gets abandoned halfway.
 *  - Each COLLECTION entity then follows as its own add / edit / reorder / delete list.
 *
 * ── SAVING ───────────────────────────────────────────────────────────────────────────────────────
 *
 * Two destinations, in a fixed order, and the order is the point.
 *
 *  1. The DEVICE, always, debounced by [SAVE_DEBOUNCE_MS]. This is the copy that matters: a design
 *     workshop is filled in over two weeks in a courtyard with no signal, so the local draft is the
 *     document and the server is a backup of it. Debounced rather than per-keystroke because
 *     rewriting a 22-stage JSON document on every character janks the frame the designer typed into.
 *  2. The SERVER, opportunistically, and never in a way that can fail the local save. A network error
 *     leaves the stage saved on the phone and the status line saying so. An app that reported "save
 *     failed" because a GET timed out would be telling a designer their fieldwork is gone when it is
 *     sitting in filesDir.
 *
 * The whole stage goes in one PUT, never field by field. That is what makes the write atomic: the
 * phone reconnects after two days offline and posts everything it has for the stage, and either all
 * of it lands or none of it does. A per-field endpoint leaves a stage half-written whenever the
 * connection drops mid-sync, which on one bar of signal is most of the time.
 *
 * THREE THINGS START THAT SAVE AND ALL THREE RUN THE SAME CODE — the debounce, the "Save and sync
 * this stage now" button, and the `onDispose` that catches a designer leaving inside the debounce
 * window. The last two were missing, which meant the window between the final keystroke and the
 * write was 800ms during which Back discarded the edit, and the button that exists to close that
 * window merely reopened it.
 */

/**
 * How long after the last keystroke the draft is written.
 *
 * Long enough that a sentence is one write rather than forty, short enough that the work is on disk
 * before the designer can put the phone down and walk away — which, in a courtyard, is the moment the
 * process gets killed. 800ms is the same order the rest of this app uses for auto-save.
 */
private const val SAVE_DEBOUNCE_MS = 800L

/**
 * How long the box a readiness link landed on stays outlined.
 *
 * The web's `FLASH_MS` (`components/hooks/useRevealRow.ts`), to the millisecond, because it is the
 * same signal answering the same question — "which of these is the one I tapped" — and two clients
 * that mark an arrival for visibly different lengths teach a designer two different habits.
 */
private const val FIELD_FLASH_MS = 1400L

/** One row of a repeating entity, held as the form edits it. */
@Immutable
private data class CollectionRow(
    /**
     * This device's own id for the row, stable across saves.
     *
     * It is sent as `_clientKey` and is what lets the server recognise a row it has already stored
     * instead of creating a duplicate. Without it, every debounced save of a stage with four sketch
     * rows would insert four more rows and soft-delete the previous four — a costing table that grows
     * by four lines a minute while the designer types.
     */
    val rowId: String,
    /** Registry values plus the sync protocol's own underscore keys (`_entryId`, `_ordinal`). */
    val values: Map<String, JsonElement>,
)

@Immutable
private data class StageState(
    val singleton: Map<String, JsonElement> = emptyMap(),
    val collections: Map<String, List<CollectionRow>> = emptyMap(),
    /**
     * WHO LAST SET EACH FIELD, as the server reported it — [StageDraft.provenance], carried onto the
     * screen state so the attribution under a box and the value in it come from one read.
     *
     * NEVER WRITTEN BY THIS SCREEN. A keystroke does not restamp anything: authorship is decided by
     * `entry_provenance.merge_entry_provenance`, which ignores whatever a client sends, so a locally
     * invented stamp would be a claim this handset is not entitled to make and would be replaced by
     * the truth on the next fold. It is seeded and then read.
     */
    val provenance: DwStageProvenanceDto = DwStageProvenanceDto(),
    /**
     * Answers to the questions this workshop's DESIGNER added to this stage — [StageDraft.custom].
     *
     * A SIBLING OF [singleton] AND NEVER FOLDED INTO IT. That map is posted as the stage's singleton
     * entity's `data`, where `validate_entry` iterates the registry entity's own fields and drops
     * every key it does not know — so a custom answer smuggled through it would be thrown away and
     * reported in `droppedKeys`, firing the registry-drift banner on every save of every workshop
     * with a custom section.
     *
     * IT IS SEEDED FROM THE DRAFT WHETHER OR NOT A DEFINITION IS HELD, which is what makes writing it
     * straight back in [persistLocally] lossless. A key this build cannot draw — a retired question, a
     * v1.1 type, anything answered on the web while this phone held an older definition — travels
     * through the screen untouched rather than being erased by a keystroke in an unrelated box.
     */
    val custom: Map<String, JsonElement> = emptyMap(),
    /**
     * Whether [custom] came from the SERVER's own container rather than from this device alone.
     *
     * Carried on the state and not read off the draft at save time because [persistLocally] rebuilds
     * the whole record: the fact is established by the seed ([fromRemote] read the server's bucket)
     * and has to survive until the record is written. See [StageDraft.customSeen] for what it costs
     * to get this wrong — a phone that has never read the row telling the server the designer cleared
     * every answer on it.
     */
    val customSeen: Boolean = false,
)

/** What the status line under the header is currently able to promise. */
private enum class SaveState { CLEAN, PENDING, SAVING, ON_DEVICE, SYNCED }

/**
 * One stage's unwritten edit, complete enough to be landed by something that is not this screen.
 *
 * It carries the whole argument list [persistLocally] takes — including the workshop id and the
 * registry spec — precisely so that the dispose does not have to consult the composition for any of
 * it. That is the point: `onDispose` runs after the composition that let go of this screen, and
 * anything read from a `remember(stageKey)` slot at that moment is already the NEXT stage's default.
 *
 * Plain state, not Compose state, and not `@Immutable`: nothing draws from it, and a recomposition
 * on every keystroke to update a holder no pixel depends on would be pure cost.
 */
private class PendingWrite {

    /** Everything needed to write one stage without asking a composable anything. */
    class Outstanding(
        val workshopId: String,
        val stage: StageDto,
        val state: StageState,
        val seen: Boolean,
        val emptied: Set<String>,
        /** See [StageDraft.deletedRowKeys]. */
        val deletedRows: Set<String>,
        /** Whether there is a server record to offer it to — see `syncId` in [StageScreen]. */
        val syncable: Boolean,
    )

    /** The screen's edit counter when this was recorded. Zero when nothing is outstanding. */
    var revision: Int = 0
        private set

    private var outstanding: Outstanding? = null

    fun record(
        revision: Int,
        workshopId: String,
        stage: StageDto,
        state: StageState,
        seen: Boolean,
        emptied: Set<String>,
        deletedRows: Set<String>,
        syncable: Boolean,
    ) {
        this.revision = revision
        outstanding = Outstanding(workshopId, stage, state, seen, emptied, deletedRows, syncable)
    }

    fun clear() {
        revision = 0
        outstanding = null
    }

    /** Hand the write over, ONCE. Taking it discharges it, so a second dispose cannot re-send it. */
    fun take(): Outstanding? = outstanding.also { clear() }

    /**
     * Re-point an outstanding write at the deletion records the DRAFT now holds, leaving everything
     * else about it alone.
     *
     * ── WHY AN OUTSTANDING WRITE HAS TO BE CORRECTED AND NOT JUST THE SCREEN ─────────────────────
     *
     * A push the server acknowledged causes `recordStageSent` to drop the acknowledged deletion keys
     * from the draft, and the screen adopts that (see the `StagePush.Sent` branch). But the keystroke
     * that arrives during the push has ALREADY snapshotted the pre-acknowledgement `emptied` /
     * `deletedRows` into this holder — that is what [record] does, on the same line as the edit — and
     * the dispose writes this holder, not the screen. So a designer who types one more character and
     * presses Back inside the 800ms window would have `persistLocally` union the acknowledged key
     * straight back onto disk: the resurrected deletion, arriving by the one path that skips the
     * debounce entirely. Refreshing the screen's two fields and not this one closes three quarters of
     * the defect and leaves the quarter that happens when somebody is in a hurry.
     *
     * The revision is deliberately untouched: this is the same edit it always was, corrected, not a
     * newer one — bumping it here would make [StageScreen]'s `pending.revision == at` discharge check
     * disagree with the write that is actually outstanding.
     */
    fun adoptDeletionRecord(emptied: Set<String>, deletedRows: Set<String>) {
        val current = outstanding ?: return
        outstanding = Outstanding(
            workshopId = current.workshopId,
            stage = current.stage,
            state = current.state,
            seen = current.seen,
            emptied = emptied,
            deletedRows = deletedRows,
            syncable = current.syncable,
        )
    }
}

@Composable
fun StageScreen(
    repository: WorkshopRepository,
    workshopId: String,
    stageKey: String,
    /**
     * One box on this stage to arrive at, or null for the ordinary "open the stage".
     *
     * Set by the stage index when a designer taps one of the "still missing" labels. It is honoured
     * ONCE, on the way in — it opens the ADVANCED disclosure or the collection row that holds the
     * field if either is closed, scrolls it into view and marks it briefly. Nothing about it is
     * saved, and it never changes a value.
     */
    focus: DwStageFocus? = null,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
    /**
     * How this stage's REF pickers open the app's own record forms — see [DwInlineRecordHost].
     *
     * Passed in rather than built here, because the forms it opens need the craft and artisan
     * registers, the signed-in account and the message sink, and a stage screen has no business
     * acquiring any of them. Null means the pickers offer only what already exists.
     */
    inlineRecords: DwInlineRecordHost? = null,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    var stage by remember(stageKey) { mutableStateOf<StageDto?>(null) }
    var state by remember(stageKey) { mutableStateOf(StageState()) }
    var loading by remember(stageKey) { mutableStateOf(true) }
    var saveState by remember(stageKey) { mutableStateOf(SaveState.CLEAN) }
    var syncNote by remember(stageKey) { mutableStateOf<String?>(null) }
    var showAdvanced by remember(stageKey) { mutableStateOf(false) }
    // Bumped on every edit. The debounced save is a LaunchedEffect keyed on it, so a new keystroke
    // cancels the pending write rather than queueing a second one behind it.
    var revision by remember(stageKey) { mutableIntStateOf(0) }
    var mediaIndex by remember(workshopId) { mutableStateOf<Map<String, DraftMedia>>(emptyMap()) }
    /**
     * The id to PUT this stage to, or null while the workshop exists only on this device.
     *
     * Null is not an error state and must not be reported as one. A workshop started in a courtyard
     * with no signal has no server record to write to yet; spending a request on a PUT to an id the
     * server has never heard of would produce a 404 and a red line under a stage that is, in fact,
     * safely saved. The list screen's "Send to server" action is what fills this in.
     */
    var syncId by remember(workshopId) { mutableStateOf<String?>(null) }
    /**
     * Whether this device has READ the server's copy of the stage — see [StageDraft.stageSeen].
     *
     * Carried in screen state as well as on disk because [persistLocally] writes a whole [StageDraft]
     * and would otherwise reset it to the class default on the first keystroke, silently disclaiming
     * a reading the screen had just made.
     */
    var seen by remember(stageKey) { mutableStateOf(false) }
    /**
     * Collections the designer has emptied in this session, unioned with whatever the draft already
     * recorded. See [StageDraft.emptiedEntities]: with no per-row delete endpoint this is the only
     * way deleting the LAST row of a collection ever reaches the server.
     */
    var emptied by remember(stageKey) { mutableStateOf<Set<String>>(emptySet()) }
    /**
     * Individual rows the designer has deleted in this session, unioned with whatever the draft
     * already recorded — see [StageDraft.deletedRowKeys].
     *
     * THE SIBLING OF [emptied], FOR THE DELETION IT CANNOT SEE. `emptied` gains a key only when a
     * collection goes from having rows to having none; deleting one row of three left no record
     * anywhere, so nothing could count it and the workshop row said "Backed up to the server" while
     * the row was still in the repository and still in the report.
     */
    var deletedRows by remember(stageKey) { mutableStateOf<Set<String>>(emptySet()) }
    /** Set when the stage could not be downloaded, so the blank screen below is explained. */
    var downloadNote by remember(stageKey) { mutableStateOf<String?>(null) }
    /** The heading over [downloadNote]. Null keeps the "could not be downloaded" wording. */
    var noteTitle by remember(stageKey) { mutableStateOf<String?>(null) }
    /**
     * What the repository refused inside a save it otherwise accepted, or null when it refused nothing.
     *
     * RESTORED FROM THE DRAFT ON EVERY OPEN, AND IT USED NOT TO BE. This slot held the card and the
     * marks on the boxes for the life of the composition and no longer, and the comment here argued
     * for that: the addressing "is only meaningful while that payload is the newest thing this screen
     * has sent", and persisting it "would mean drawing a red mark on a box whose answer the designer
     * corrected an hour ago."
     *
     * THE FIRST HALF WAS FALSE AND THE SECOND HALF HAS AN ANSWER. What outlived the screen was
     * `recordStageSent`'s note, and that note says *"open the stage to see which answers, and what the
     * repository holds."* So the app told the designer to do the one thing that destroyed the evidence:
     * they arrived at a stage with nothing on it, while [StageSyncRecord.refusedFields] went on counting
     * and the workshop went on saying answers were refused. And a corrected answer cannot leave a stale
     * mark, because the save that carries the correction comes back with an empty error map and writes
     * NULL over the stored record — one event clears the count and the addressing together. What is
     * shown is dated ([DwStageRefusalReport.recordedAt]), so it reads as the last thing the repository
     * actually said rather than as a claim about now.
     *
     * See [StageSyncRecord.refusal] for what is stored (the error map and the ordering of the entries
     * that were sent, so the decode is re-run against the registry this build has) and what is
     * deliberately not (what the repository HOLDS, which is measured by a read that may be a day old).
     */
    var refusals by remember(stageKey) { mutableStateOf<DwStageRefusalReport?>(null) }
    /**
     * The server's copy of this stage, IF this open already fetched it — so the fill below need not
     * fetch it twice.
     *
     * A stored refusal restored off disk says [DW_UNRECORDED] under every key until a read answers it,
     * and the load may already have made exactly that read for the fold. Spending a second request on
     * a prepaid connection to ask the same question twice in one screen open is the cost
     * [dwCarryHoldings] exists to avoid on the save path; this is the same cost on the load path.
     */
    var readBucket by remember(stageKey) { mutableStateOf<StageBucketDto?>(null) }
    /**
     * This workshop's own questions, as this device holds them. NULL IS A STATE — see [DwCustomCopy].
     *
     * Keyed on `workshopId` and not on `stageKey`, because a definition covers all 22 stages: keying
     * it per stage would re-fetch and re-read it on every tab swipe of a pager that keeps a stage's
     * neighbours composed.
     */
    var definition by remember(workshopId) { mutableStateOf<DwCustomCache?>(null) }
    /**
     * Which publication of this workshop's id and dictation consent is ours, so disposal clears only ours.
     *
     * A TOKEN AND NOT A FLAG, because a tabbed pager keeps a stage's NEIGHBOURS composed: two of these
     * screens are alive at once, and the one that scrolls away disposes while the visible one is still
     * being dictated into. A disposal that cleared unconditionally would withdraw the craft-aware rung
     * from the screen the designer is actually looking at, silently, until they navigated away and back.
     *
     * DELIBERATELY NOT KEYED ON `workshopId, stageKey`, WHICH IT WAS AND WHICH MADE THE GUARD DEAD ON
     * THE ONE TRANSITION IT IS FOR. Stage-to-stage navigation reuses this same composable with a new
     * `stageKey`, so a keyed `remember` re-initialised this to 0 during the composition that ran BEFORE
     * the old `DisposableEffect` was disposed — and `forgetWorkshopConsent(0)` matches nothing, because
     * the counter only ever hands out values from 1 up. The previous stage's answer was therefore left
     * published while the next stage's draft was still being read off disk: harmless within one workshop,
     * which is the only shape this app can reach today, and exactly the fail-OPEN window a consent gate
     * must not have on the day something navigates straight from one workshop's stage to another's. Held
     * across key changes, the disposal reads the token that was actually published and clears it, and the
     * gate is closed for the moment it takes the new stage to publish its own.
     */
    var consentPublication by remember { mutableStateOf(0L) }

    /**
     * The consent goes back to "nobody has said" when this stage leaves the tree.
     *
     * NOT AN OPTIONAL TIDY-UP. The published answer is read by every microphone in the process,
     * including one drawn on a screen that has no workshop behind it at all, and an answer left behind
     * by the last stage screen would clear a third-party send for a dictation that belongs to nothing.
     * Fail closed on the way out as well as on the way in.
     */
    DisposableEffect(workshopId, stageKey) {
        onDispose { DwDictationRun.forgetWorkshopConsent(consentPublication) }
    }

    // ── Load ─────────────────────────────────────────────────────────────────────────────────────
    LaunchedEffect(workshopId, stageKey) {
        loading = true
        runCatching {
            val schema = repository.designWorkshopSchema(appContext)
            val spec = schema.stages.firstOrNull { it.key == stageKey }
                ?: error("This build's field registry has no stage called $stageKey.")

            val draft = WorkshopDraftStore.load(appContext, workshopId)
            mediaIndex = draft?.media.orEmpty().associateBy { it.id }
            // ONE EXPRESSION FOR THE SERVER'S ID, read once and used for all three things that need it:
            // the stage PUT, the dictation ambient below, and the seed read further down. It was written
            // out twice before, and a third copy — beside a consent gate that turns on it — is how two
            // of them end up disagreeing about whether this workshop exists up there.
            //
            // AND A BLANK IS NOT AN ID, WHICH IS `WorkshopSync.remoteIdOf`'s RULE AND NOT A NEW ONE. Both
            // halves are guarded because `""` is not null and would therefore read as a workshop that IS
            // on the server: the stage PUT would go to `design-workshops//stages/…` and the dictation
            // ambient would open rung 2 for a workshop nothing up there can load — spending a
            // six-megabyte upload of an artisan's voice on an empty path segment. `DesignWorkshopDto.id`
            // is defaulted to `""`, so a create answered by a captive portal rather than by this API
            // leaves exactly that in a draft's `remoteId`; `WorkshopSync` refuses it in as many words and
            // so does this. Null is the honest reading — as far as this phone can prove, the workshop has
            // not been sent up — and it is the reading the ladder already has a sentence for.
            val serverId = draft?.remoteId?.takeIf { it.isNotBlank() }
                ?: workshopId.takeIf { it.isNotBlank() && !isLocalOnlyWorkshop(it) }
            syncId = serverId
            /*
              WHICH WORKSHOP THIS IS AND WHETHER ITS RECORDINGS MAY LEAVE THE DEVICE, HANDED TO EVERY
              MICROPHONE ON THIS STAGE — plan §6 answer 3, and the gate on rung 2 of the dictation ladder.

              PUBLISHED FROM HERE BECAUSE THIS SCREEN HAS ALREADY PAID FOR THE DRAFT. The dictation
              control cannot read it for itself: `conditionsNow()` runs on the tap, on the main thread,
              and loading a draft there would be a whole-22-stage JSON parse per field taken under the
              one store-wide mutex every stage auto-save also takes — hundreds of microphones
              serialising behind the very auto-saves that protect the designer's text. And it cannot be
              passed as a parameter either: `DwDictationButton` is drawn from `FieldRenderer` and from
              `RichTextEditor`, and only one of those has a data layer, so a parameter would produce a
              phone where server dictation works in a short prose field and silently does not in a long
              one. That argument is already written down at [DwDictationRun.repository] and was settled
              there.

              A DRAFT THAT WOULD NOT LOAD PUBLISHES NOTHING, so the ambient stays NOT_RECORDED and rung
              2 is withheld. That is the fail-closed direction: an unknown consent costs the craft-aware
              rung, while a wrong guess costs a named artisan's recorded voice leaving the device.

              AND THE SERVER'S ID GOES WITH IT, WHICH IS NEW AND IS WHAT MAKES THE GATE ENFORCEABLE.
              Rung 2 now posts to `POST /design-workshops/{id}/dictate` — the only dictation route that
              can read a workshop's `dictationConsent` column at all — so the control needs the id, and
              `serverId` above is the one this screen already trusts for the stage PUT.

              A LOCAL-ONLY WORKSHOP THEREFORE PUBLISHES A null ID, AND THAT COSTS IT RUNG 2. Said plainly
              because it is a capability this screen used to have: a workshop captured in a courtyard and
              not yet sent up has no record on the server, so the gated route could only answer 404 — and
              only after a six-megabyte upload. The ladder withholds the rung with a sentence naming the
              send instead ([dwDictationNothingLeftSentence]). The alternative was the old behaviour: post
              to the id-less route, where no consent column is consulted at all, which is the door this
              change shut.
            */
            val fromDraft = dwTier3ConsentOf(draft?.consent?.decision)
            consentPublication = DwDictationRun.publishWorkshopConsent(
                // THE DRAFT FIRST, AND THE SERVER'S ANSWER ONLY WHERE THE DRAFT HAS NONE. A workshop
                // created in a browser has no draft here until a stage is saved, so its consent —
                // already recorded, already GRANTED — would have nowhere on this device to live and this
                // screen would withhold the rung one tap after the workshop's own screen said it was
                // cleared. The fallback can only ever widen, never narrow: a REFUSED recorded on this
                // handset is in the draft, so it wins. See [DwDictationRun.consentAnswerSeen].
                consent = if (fromDraft == DwTier3Consent.NOT_RECORDED) {
                    DwDictationRun.consentAnswerSeen(workshopId) ?: fromDraft
                } else {
                    fromDraft
                },
                serverWorkshopId = serverId,
            )
            /*
              THE DEFINITION, REFRESHED WHERE THERE IS SIGNAL AND READ OFF DISK WHERE THERE IS NOT.

              [dwCustomDefinition] never throws for a failed refresh — it falls back to whatever this
              device already holds — because a designer in a courtyard must still get their stage. What
              it will not do is invent an empty definition out of a failure: that is the difference
              between "this workshop has no custom questions" and "this phone has not been told", and
              the whole three-state design turns on the two staying apart.

              Read for the SERVER's id and cached under the DRAFT's, which are not the same string for
              a workshop created in a courtyard — see the function.
            */
            definition = runCatching {
                repository.dwCustomDefinition(appContext, workshopId, serverId)
            }.getOrNull()
            val local = draft?.stages?.get(stageKey)

            // THE LOCAL DRAFT WINS whenever it holds anything. The device is where the work is done
            // and the server copy can only ever be older or equal; overwriting a courtyard's worth of
            // typing with a two-day-old server snapshot because the screen happened to reopen with
            // signal is the single most expensive mistake this screen could make. The server is read
            // only to seed a stage this device has never opened.
            val remoteId = serverId
            /*
              ASKED BEFORE THE REQUEST, NOT DISCOVERED BY MAKING IT, and it is the difference between
              a stage that opens and a stage that appears to have hung.

              `ApiClient` allows a 30-second connect timeout and a 60-second read — generous on
              purpose, because mobile data drops connections and a sync that gave up in three seconds
              would never finish an upload. That is the right budget for a background pass and the
              wrong one to put in front of a form: a designer in a courtyard opening stage after stage
              would watch a spinner for half a minute EACH TIME, for a request that was never going to
              succeed. The empty-stage branch below has always paid that cost, for a stage where there
              is genuinely nothing else to show; widening it to every stage that holds work would have
              made the app unusable exactly where it is meant to be used.

              A false negative here costs one deferred reading, which the next open retries. A false
              positive costs the timeout. `isOnline` demands NET_CAPABILITY_VALIDATED, so a captive
              portal that has not been signed into reads as offline — which is the answer that keeps
              the form quick.
            */
            val canReach = remoteId != null && ConnectivityObserver.isOnline(appContext)
            /*
              WHICH OF THE FOUR READS THIS OPEN IS, DECIDED ONCE, OUTSIDE COMPOSITION AND UNIT-TESTED.

              It was a chain of `if`s over an inline `holdsWork`, and the routing — not the bodies —
              was where the deletion was lost: see [dwStageReadPlan], which is that chain, moved
              somewhere a test can reach it and taught the one term it was missing.
            */
            val plan = dwStageReadPlan(local, remoteExists = remoteId != null, canReach = canReach)
            val loaded = when (plan) {
                DwStageRead.DRAFT_AS_IS -> {
                    // Nothing to learn, or nothing to learn it from. The draft is shown as it is.
                    // `held` rather than `local!!` three times: this arm is reachable only for a draft
                    // [dwStageReadPlan] has already found something in, so the assertion belongs at
                    // the top of the arm where it is read as the routing's guarantee, not sprinkled
                    // through a constructor call where the next reader has to re-derive it.
                    val held = local!!
                    StageLoad(
                        fromDraft(spec, held),
                        seen = held.stageSeen,
                        // A read that was never ATTEMPTED leaves the designer in exactly the position
                        // a read that FAILED does — the stage is unread, so a clearance made here will
                        // not propagate yet — and they are owed the same sentence. Reported only where
                        // there was something to read: a workshop that exists on this phone alone has
                        // no server copy to be missing, and warning about one would be a false alarm on
                        // every stage of every workshop captured in a courtyard.
                        downloadFailed = !held.stageSeen && remoteId != null,
                        heldWorkAlready = true,
                    )
                }
                DwStageRead.FOLD_SERVER_COPY -> {
                    /*
                      A STAGE THAT HOLDS WORK AND HAS NEVER BEEN READ IS READ NOW, AND THE ANSWER IS
                      FOLDED RATHER THAN ADOPTED.

                      This branch did not exist. The rule was "the local draft wins whenever it holds
                      anything", which is right about whose VALUES survive and was, by accident, also
                      the rule for whether the request happened at all — so a stage opened once without
                      signal and typed into was never read again for the life of the draft, and could
                      therefore never become authoritative again. That did not show while
                      `recordStageSent` handed authority out after any successful save; with that gone
                      (see [StageDraft.stageSeen]) it would have made `replaceCollections` unreachable
                      for ever, on every handset, so no clearance and no row deletion would ever have
                      reached the repository again.

                      THE LOCAL COPY STILL WINS EVERY KEY IT HOLDS. [dwFoldServerStage] only ADDS what
                      the server has and this device does not, which is what makes the resulting claim
                      honest: after the fold, "delete what I do not name" cannot name anything the
                      designer has not been shown. What appeared is announced rather than slipped in —
                      see `foldNotice`.

                      AND IT IS ALSO THE ARM A DELETION-ONLY DRAFT TAKES, which is why the routing
                      above counts `emptiedEntities` as something held. This is the only reader that
                      declines to fold an emptied collection's rows back in and the only one that
                      counts them in `sweptRows` so the notice can say so; the seed arm below would
                      have put them back on screen in silence.
                    */
                    val remote = runCatching { repository.designWorkshopStage(remoteId!!, stageKey) }
                        .getOrNull()
                    if (remote == null) {
                        StageLoad(
                            fromDraft(spec, local!!),
                            seen = false,
                            downloadFailed = true,
                            heldWorkAlready = true,
                        )
                    } else {
                        // Held so a refusal restored off disk can be measured against this read
                        // instead of paying for a second identical one — see [readBucket].
                        readBucket = remote
                        val fold = dwFoldServerStage(spec, local, remote, stageKey)
                        // Written to disk HERE, and only in this branch. The fold is the one read
                        // whose result must survive the screen: it is what the next save's authority
                        // rests on, and a fold held only in composition would be re-fetched on every
                        // open and lost entirely to a designer who read the stage and then went back
                        // out of signal. `updateBookkeeping` rather than `update`, so re-reading a
                        // stage does not reorder the workshop list or stale every generated report —
                        // see its KDoc.
                        WorkshopDraftStore.updateBookkeeping(appContext, workshopId) { draft ->
                            draft.copy(stages = draft.stages + (stageKey to fold.draft))
                        }
                        StageLoad(
                            fromDraft(spec, fold.draft),
                            seen = true,
                            downloadFailed = false,
                            heldWorkAlready = true,
                            foldNotice = fold.notice,
                        )
                    }
                }
                DwStageRead.SEED_FROM_SERVER -> {
                    val remote =
                        runCatching { repository.designWorkshopStage(remoteId!!, stageKey) }.getOrNull()
                    if (remote != null) {
                        readBucket = remote
                        // The draft now starts from everything the server had, which is exactly the
                        // condition that entitles a later save to say "these are now exactly the rows".
                        //
                        // AND THE DRAFT REALLY DOES HOLD NOTHING, which is the whole licence for
                        // adopting the server's bucket verbatim. [fromRemote] has no term for
                        // [StageDraft.emptiedEntities] and must not need one: [dwStageReadPlan] routes
                        // a draft holding a deletion record to the fold above, which is the one reader
                        // that declines to bring emptied rows back. Widen this arm and the deletion is
                        // not merely ignored, it is ERASED — the rows come back on screen with no
                        // notice, and `persistLocally`'s filter drops the record on the first
                        // debounced save after.
                        StageLoad(fromRemote(spec, remote), seen = true, downloadFailed = false)
                    } else {
                        // ── THE READ FAILED, AND THAT IS NOT THE SAME THING AS AN EMPTY STAGE ──────
                        //
                        // This used to seed `StageState()` and say nothing, which is how a stage
                        // holding a fortnight of work — a 5-field singleton, 6 process steps, 5 tools,
                        // 4 raw materials — opened as a blank screen in a courtyard with no signal.
                        // One typed field then produced a payload with that field, zero rows for every
                        // collection and `replaceCollections = true`, and the server swept the lot.
                        //
                        // The blank screen still appears, because a designer with no signal must still
                        // be able to capture. What changes is that it is ANNOUNCED, and that the draft
                        // is marked as NOT SEEN, so no save built from it can claim to be the whole
                        // truth of the stage. The work syncs; nothing it has not seen is destroyed.
                        StageLoad(StageState(), seen = false, downloadFailed = true)
                    }
                }
                DwStageRead.SEED_BLANK ->
                    // No server record at all. There is nothing on the server this draft could be
                    // missing, so the device genuinely is the whole truth of this stage.
                    StageLoad(StageState(), seen = true, downloadFailed = false)
            }
            /*
              THE CARD THE APP TOLD THE DESIGNER TO COME BACK FOR, PUT BACK ON THE SCREEN.

              `recordStageSent` writes a note that says "open the stage to see which answers, and what
              the repository holds", and until [StageSyncRecord.refusal] existed, following that
              instruction was what destroyed the evidence: the card lived in composition state, so
              leaving the stage erased it while the count that justified it stayed on the workshop.

              RE-DECODED, NOT REDRAWN. What was stored is the server's error map and the ordering of the
              entries that were sent, so the refusal is decoded here against the registry and the custom
              definition THIS build actually holds — by the same function the save path uses, walking the
              same addressing. A frozen copy of the card would have gone stale the moment either moved.

              Every refusal comes back saying UNRECORDED, deliberately: what the repository holds was
              measured by a read that may have happened on a connection that no longer exists, and
              quoting a day-old value as "the repository still holds" is the one guess that would make
              this surface a second way of lying. The fill below is what measures it again.
            */
            refusals = dwRestoreStageRefusals(
                spec = spec,
                record = draft?.sync?.stages?.get(stageKey)?.refusal,
                customFields = customFieldsForStage(definition, spec.key),
            )
            Triple(spec, loaded, local)
        }.onSuccess { (spec, loaded, local) ->
            stage = spec
            state = loaded.state
            seen = loaded.seen
            // A gap the designer tapped may be behind "More detail", which is closed by default —
            // and a link that lands on a disclosure the field is hiding inside has not arrived
            // anywhere. Set here rather than as the initial `remember` value because the registry is
            // only known once the load has finished.
            showAdvanced = focusOpensAdvanced(spec, focus)
            saveState = SaveState.CLEAN
            noteTitle = when {
                loaded.foldNotice != null -> "This stage has been read from the server"
                loaded.downloadFailed && loaded.heldWorkAlready ->
                    "This stage has not been read from the server yet"
                else -> null
            }
            downloadNote = when {
                // What the fold added, and it is said BEFORE anything else because it is the only one
                // of these notes that describes the screen having CHANGED under the designer.
                loaded.foldNotice != null -> loaded.foldNotice
                !loaded.downloadFailed -> null
                // The frightening case: a blank screen that is not an empty stage.
                !loaded.heldWorkAlready ->
                    "This stage could not be downloaded — there is no connection, or the request " +
                        "failed. What you type here will be saved and sent, but anything already on " +
                        "the server for this stage is NOT shown below and will not be replaced by it."
                // The duller case, and it needs its own words rather than the ones above: the work on
                // screen IS this designer's, nothing is hidden from them, and the single consequence
                // is the one they would otherwise discover from an officer — that emptying a box here
                // does not empty it there until this stage has been read once with a connection.
                else ->
                    "This stage has not been read from the server on this device — there is no " +
                        "connection, or the request failed. Everything you have typed is here and " +
                        "will be sent. Until it has been read once, clearing an answer or deleting " +
                        "a row here does NOT clear or delete it on the server: that is deliberate, " +
                        "because this phone cannot yet tell an answer you removed from one it has " +
                        "never seen. The deletion is remembered and goes up on the first save after " +
                        "the stage has been read."
            }
            // Whatever the draft last recorded the designer emptying, carried forward so a deletion
            // made offline yesterday still reaches the server today.
            emptied = local?.emptiedEntities.orEmpty().toSet()
            // And the individual rows, for the same reason and with the same lifetime.
            deletedRows = local?.deletedRowKeys.orEmpty().toSet()
        }.onFailure { error ->
            onError(error.message ?: "Unable to open this stage.")
        }
        loading = false
    }

    /*
      AND WHAT THE REPOSITORY HOLDS UNDER A REFUSAL RESTORED OFF DISK — MEASURED, ONCE, AFTER THE FORM
      IS ALREADY ON SCREEN.

      A restored refusal says UNRECORDED under every key, which is honest and is not much use to the
      designer who came here on the app's own instruction to find out what the repository holds. The one
      honest source is a `GET .../stages/{key}`, so it is made — but NOT inside the load, and that is
      deliberate. `ApiClient` allows a 30-second connect and a 60-second read, and paying that inside the
      load would put a spinner in front of a form that is otherwise ready, on the connection least likely
      to answer. Exactly the argument the load's own `canReach` check is there for.

      IT COSTS AT MOST ONE REQUEST PER OPEN, and often none:

       * `recordedAt != null` restricts it to a report restored off DISK. A refusal this composition just
         earned is filled by the save path, which already has the response in hand.
       * `readBucket` is the fold's read where the load already made one, so the frugal case pays nothing.
       * a failed read leaves `needsRead` true and does not re-key this effect, so it is attempted once
         and the card goes on saying UNRECORDED, which is true.
       * offline, it is not attempted at all.

      IT FILLS THE CARD AND NOTHING ELSE — no value is folded into the draft and no `stageSeen` is earned
      off it, for the reason the save path's read gives: the designer is looking at their own text, and
      replacing it with the repository's copy while they read a message about it would be the overwrite
      this whole surface exists to prevent. It is a report, not a sync.
    */
    val restoredNeedsHoldings = !loading &&
        refusals?.let { it.recordedAt != null && it.needsRead } == true
    LaunchedEffect(workshopId, stageKey, restoredNeedsHoldings) {
        if (!restoredNeedsHoldings) return@LaunchedEffect
        val spec = stage ?: return@LaunchedEffect
        val bucket = readBucket ?: run {
            val id = syncId ?: return@LaunchedEffect
            if (!ConnectivityObserver.isOnline(appContext)) return@LaunchedEffect
            runCatching { repository.designWorkshopStage(id, spec.key) }.getOrNull()
        } ?: return@LaunchedEffect
        readBucket = bucket
        refusals = refusals?.let { dwHoldingsFrom(it, bucket) }
    }

    // ── Saving ───────────────────────────────────────────────────────────────────────────────────

    /**
     * The edit that has not reached the disk yet, held OUTSIDE every `remember(stageKey)` slot.
     *
     * WHY THE DISPOSE BELOW DOES NOT SIMPLY READ THIS SCREEN'S STATE. `onDispose` runs during apply,
     * AFTER the composition that changed the key — so on a move from one stage straight into another
     * every `remember(stageKey)` slot has already been re-created and reads back its default. The
     * dispose would find `stage == null`, write nothing, and lose exactly what it exists to save, in
     * the one navigation nobody would think to test. Unkeyed, it still holds the outgoing stage.
     */
    val pending = remember { PendingWrite() }

    /**
     * Write the stage to the device, then offer it to the server. THE ONE SAVE PATH.
     *
     * Extracted from the debounce because it now has three callers and every one of them has to do
     * the identical thing: the timer below, the explicit button, and the dispose that catches a
     * designer leaving inside the timer's window. The button used to be `onClick = { revision++ }`,
     * which merely RESTARTED the 800ms debounce — so the one control that exists to stop trusting a
     * timer you cannot see started the timer, and a designer who tapped "Save and sync this stage
     * now" and walked away had nothing written anywhere.
     */
    suspend fun saveAndSync() {
        val spec = stage ?: return
        saveState = SaveState.SAVING
        val at = revision
        val snapshot = state

        // The two deletion records EXACTLY AS THEY ARE HANDED TO THE WRITE, so the acknowledgement
        // below can tell "the server accepted this key and `recordStageSent` dropped it" apart from
        // "the designer deleted something else while the push was in flight". Read once, here,
        // because `emptied`/`deletedRows` are composition state that a row deletion can change under
        // this coroutine at any suspension point — and re-reading them after the push is what would
        // make the difference below meaningless.
        val emptiedSent = emptied
        val deletedRowsSent = deletedRows

        // The device first and unconditionally. Nothing below this line may prevent it.
        runCatching {
            persistLocally(appContext, workshopId, spec, snapshot, seen, emptiedSent, deletedRowsSent)
        }
            .onFailure { error ->
                // A CANCELLATION IS NOT A FAILED WRITE, and `runCatching` catches `Throwable`, which
                // includes it. This whole function runs inside `LaunchedEffect(revision)` and Compose
                // cancels that on the next keystroke, while `persistLocally` is suspended on
                // `Dispatchers.IO` and on the store's process-wide mutex behind a neighbouring
                // stage's save or a media import — so cancellation lands INSIDE the write routinely,
                // not exotically. Swallowed, the cancelled coroutine ran on and wrote
                // `saveState = PENDING` and an error over the state the newly-launched effect had
                // already set, so the status line could read the wrong thing until the next debounce
                // 800ms later. This is the rule this file already states at the `catch` further down
                // ("a `runCatching` here would eat the cancellation"); it was stated in one place and
                // not the other, which is how the two disagreed.
                if (error is CancellationException) throw error
                saveState = SaveState.PENDING
                onError(error.message ?: "Could not write this stage to the device.")
                return
            }
        // Discharged only if the edit this write was built from is still the newest one. A designer
        // typing while the write was in flight has produced a NEWER snapshot, and clearing on the
        // strength of the older one would let the dispose decide there was nothing outstanding.
        // Same comparison, and the same reason, as `dirtyAt` in `lib/designWorkshopStore.ts`.
        if (pending.revision == at) pending.clear()
        saveState = SaveState.ON_DEVICE

        // Then the server, opportunistically, THROUGH THE ONE SYNC ENGINE rather than with a PUT of
        // this screen's own. That indirection is not ceremony: the engine is what substitutes each
        // attachment's local id for the id the server acknowledged, what holds a stage back until
        // its photographs have actually landed, and what records the payload signature so the
        // background pass does not send the same stage again. This screen used to build the body
        // itself, which meant it shipped this device's private UUIDs into the server's media
        // references — a stage that reported itself synced and printed empty frames in the report.
        //
        // `submit` stays false in there for every auto-save: turning it on makes the server enforce
        // the Basic-tier required fields and 422 the request, so a stage the designer is halfway
        // through typing would silently refuse to sync for the rest of the day.
        if (syncId == null) return
        when (val push = runCatching {
            WorkshopSyncEngine.pushStage(appContext, repository, workshopId, spec)
        }.getOrElse {
            // Rethrown for the same reason as the local write above: a cancelled push is not a push
            // that did not go, and treating it as `NotSent` here assigns `ON_DEVICE`/`syncNote = null`
            // over whatever the effect that replaced this one has already decided.
            if (it is CancellationException) throw it
            StagePush.NotSent
        }) {
            is StagePush.Sent -> {
                saveState = SaveState.SYNCED
                /*
                  THE TWO DELETION RECORDS, RE-READ FROM DISK BECAUSE THE SERVER HAS JUST
                  ACKNOWLEDGED SOME OF THEM AND THIS SCREEN'S COPY HAS NOT HEARD.

                  `emptied` and `deletedRows` are seeded from the draft once, at load, and thereafter
                  only ever GAIN keys (`onRowsChange`). `recordStageSent` — inside the push that has
                  just returned — removes from the draft exactly the keys the acknowledged payload
                  carried, deliberately scoped to those. Nothing told the composition, so the RAM copy
                  was still authoritative for the next write, and `persistLocally` unions it back onto
                  disk: the key resurrected, the next differing payload asserted the sweep a second
                  time, and rows the office had entered on the web in the meantime — in a collection
                  this handset once emptied — were soft-deleted by an auto-save nobody asked for. The
                  handset never re-reads a stage it has already seen, so nothing else would ever have
                  corrected it.

                  READ FROM THE STORE RATHER THAN SUBTRACTING WHAT WE THINK WAS SENT. The payload was
                  built inside the engine (`buildStageBody` intersects the list with the entities the
                  registry declares) and only `recordStageSent` knows what the server accepted, so a
                  second opinion computed here is precisely the duplicated rule that produced this
                  defect. The draft is the one source of truth; this is a re-read of it.

                  BOTH FIELDS TOGETHER, always: they are one fact to the designer, and refreshing one
                  while the other keeps a stale key would leave the status screen reporting an unsent
                  deletion that no payload will ever carry again.

                  THE ARITHMETIC AND THE UNCANCELLABILITY ARE BOTH IN [dwAdoptDeletionRecordAfterPush]
                  — extracted rather than left inline for the reason its KDoc opens with: the version
                  that shipped here was a plain load-and-assign inside a coroutine the next keystroke
                  cancels, its test exercised [dwDeletionRecordOnDisk] instead, and a two-line
                  projection cannot fail whether or not this ever runs. `DwStageWriteBackTest` now
                  cancels the calling job mid-load and asserts the adoption happened anyway.

                  What this callback still owns is WHERE the answer lands, and it lands in two places.
                */
                dwAdoptDeletionRecordAfterPush(
                    sentEmptied = emptiedSent,
                    sentDeletedRows = deletedRowsSent,
                    // Read INSIDE the block, after the load, because a row deletion can be recorded
                    // while it is in flight — see the KDoc's second half.
                    screenEmptied = { emptied },
                    screenDeletedRows = { deletedRows },
                    loadStage = {
                        WorkshopDraftStore.load(appContext, workshopId)?.stages?.get(spec.key)
                    },
                ) { nextEmptied, nextDeletedRows ->
                    emptied = nextEmptied
                    deletedRows = nextDeletedRows
                    // AND THE WRITE THAT IS ALREADY OUTSTANDING, which is a snapshot of the two
                    // fields above taken on the keystroke that cancelled this coroutine. The
                    // dispose writes that snapshot and not the screen, so leaving it stale keeps
                    // the resurrection alive on the "type one more character and press Back"
                    // path. See [PendingWrite.adoptDeletionRecord].
                    pending.adoptDeletionRecord(nextEmptied, nextDeletedRows)
                }
                syncNote = if (push.result.droppedKeys.isEmpty()) {
                    null
                } else {
                    // The server did not recognise these keys and threw them away. Said out loud
                    // because a field that vanishes on every sync, silently, is a data loss nobody
                    // notices until the report is short a column.
                    "The server did not recognise ${push.result.droppedKeys.size} field(s) and did " +
                        "not store them: ${push.result.droppedKeys.joinToString(", ").take(160)}. " +
                        "This phone is running a newer field registry than the server."
                }
                /*
                  THE ANSWERS THE REPOSITORY REFUSED, DECODED AGAINST THE PAYLOAD THAT PRODUCED THEM.

                  `push.entries` is that payload, in the order it was sent, and it has to be: a
                  collection row's errors are keyed by the entry's INDEX IN THAT ARRAY. Rebuilding it
                  here would be a second builder and a second ordering — the trap the web's
                  `buildStageEntries` carries the same warning about.

                  Assigned unconditionally, including to the empty report, so a save that fixes the
                  refused answer CLEARS the card and the marks. A red box that outlives its correction
                  is the same lie in the other direction.
                */
                val decoded = dwDecodeStageRefusals(
                    spec = spec,
                    entries = push.entries,
                    errors = push.result.errors,
                    customFields = customFieldsForStage(definition, spec.key),
                ).copy(
                    /*
                      THE SAME RESPONSE'S `droppedCustomKeys`, WHICH THIS CARD WAS CONTRADICTING.

                      The heading asserted "Everything else in this stage was saved" while the very
                      response it was built from reported answers that were NOT stored — this
                      workshop's own questions, retired or renamed on the web since this phone last
                      read the sections. `recordStageSent` wrote a sentence about them onto the sync
                      status and the stage the designer was then told to open said the opposite.

                      Its own clause and not a refusal, because the remedy is not the same: a refused
                      answer needs correcting, and this needs the phone's copy of the sections
                      refreshed. Counting it with the refusals would send a designer to retype an
                      answer nobody objected to. See [DwStageRefusalReport.droppedCustomKeys].

                      Carried here rather than inside the decode because the decode is pure and takes
                      the ERROR MAP; this is a different list off the same response, and a save that
                      refused nothing at all can still carry it — which is why the card is now drawn
                      for a report with no refusals in it.
                    */
                    droppedCustomKeys = push.result.droppedCustomKeys,
                )
                // Holdings already measured for a question that is still refused are carried over, so
                // the read below happens on a CHANGE of refusal rather than on every debounced save.
                // See [dwCarryHoldings] for what that costs a designer without it.
                val carried = dwCarryHoldings(refusals, decoded)
                refusals = if (carried.isEmpty) {
                    null
                } else if (!carried.needsRead) {
                    carried
                } else {
                    /*
                      AND ONE READ TO ANSWER "WHAT DOES IT HOLD NOW", WHICH THE SAVE RESPONSE CANNOT.

                      Measured: `save_stage` returns no stored values at all, and the value it keeps
                      under a refused key is the one it already had — which this device may never have
                      seen. So the only honest source is a `GET .../stages/{key}`, and it is made HERE,
                      once, on the rare path where something was actually refused, rather than as a
                      standing cost on every save.

                      IT FILLS THE CARD AND NOTHING ELSE. The values are not folded into the draft and
                      no `stageSeen` is earned off the back of it: the designer is looking at their own
                      unsaved text, and quietly replacing it with the repository's copy — while they
                      are reading a message about it — would be the same overwrite this whole lane
                      exists to prevent. It is a report, not a sync.

                      If it fails, every refusal keeps saying UNRECORDED, which is true.
                    */
                    val bucket = syncId?.let { id ->
                        runCatching { repository.designWorkshopStage(id, spec.key) }.getOrNull()
                    }
                    if (bucket == null) carried else dwHoldingsFrom(carried, bucket)
                }
            }
            StagePush.AlreadySent -> {
                saveState = SaveState.SYNCED
                syncNote = null
            }
            is StagePush.HeldBack -> {
                // Named, not hidden. A stage that stops syncing the moment a photograph is attached
                // looks broken; a stage that says which photographs it is waiting for is a stage the
                // designer knows will finish itself the next time there is signal.
                saveState = SaveState.ON_DEVICE
                syncNote = "Saved on this device. ${push.files} attached file(s) have not reached " +
                    "the server yet, so this stage waits for them — it sends itself as soon as they " +
                    "upload, and nothing has been thrown away."
            }
            StagePush.NoRemoteYet, StagePush.NothingToSend, StagePush.NotSent -> {
                saveState = SaveState.ON_DEVICE
                syncNote = null
            }
        }
    }

    // The debounce itself: a new keystroke cancels the pending write rather than queueing a second
    // one behind it. `PENDING` is set here rather than inside [saveAndSync] because it describes the
    // WAIT, and the other two callers do not wait.
    LaunchedEffect(revision) {
        if (revision == 0) return@LaunchedEffect
        if (stage == null) return@LaunchedEffect
        saveState = SaveState.PENDING
        delay(SAVE_DEBOUNCE_MS)
        saveAndSync()
    }

    /**
     * THE WRITE THAT SURVIVES THE SCREEN.
     *
     * Leaving a stage inside the debounce window used to discard the edit outright: `goBack()` runs
     * unguarded, this screen leaves composition, and the coroutine sitting in `delay(800)` is
     * cancelled along with the scope it belongs to — so the last thing typed into a document that
     * exists nowhere else was gone, silently, with the status line still reading "unsaved changes"
     * as it disappeared. Pressing Back is exactly what a designer does when they have finished
     * typing.
     *
     * [AppScope.io] and not the composition's scope, for the same reason the eager media upload uses
     * it: the work has to outlive the composable that started it, and a scope that is being torn
     * down cannot finish anything. It reads [pending] rather than this screen's state for the reason
     * given on that holder.
     *
     * It runs only when there IS something outstanding, so an ordinary back-out of a stage nobody
     * edited writes nothing and asks the network nothing. A write that lands twice is harmless —
     * [WorkshopDraftStore.update] merges under its own lock, and [WorkshopSyncEngine.pushStage]
     * answers `AlreadySent` to an identical payload.
     *
     * NO UNSAVED-CHANGES PROMPT, deliberately, and it is why this screen does not register with the
     * app's unsaved-changes guard the way the record forms do. This feature's premise is that the
     * local draft IS the document and is written continuously; a "Save or discard?" dialog on every
     * exit from a stage a designer opens forty times a day would obstruct that, and its Discard
     * button would be a lie — the work has already been persisted by the time anyone could press it.
     * The routing comment in MainActivity says which of the two protects these screens.
     */
    DisposableEffect(workshopId, stageKey) {
        onDispose {
            val outstanding = pending.take() ?: return@onDispose
            AppScope.io.launch {
                runCatching {
                    persistLocally(
                        appContext,
                        outstanding.workshopId,
                        outstanding.stage,
                        outstanding.state,
                        outstanding.seen,
                        outstanding.emptied,
                        outstanding.deletedRows,
                    )
                }.onSuccess {
                    // The other half of what the button promises. Never allowed to fail the local
                    // write above, which has already happened by the time this runs.
                    if (outstanding.syncable) {
                        runCatching {
                            WorkshopSyncEngine.pushStage(
                                appContext, repository, outstanding.workshopId, outstanding.stage,
                            )
                        }
                    }
                }
            }
        }
    }

    fun edit(transform: (StageState) -> StageState) {
        val next = transform(state)
        state = next
        revision++
        // Recorded on the SAME line as the edit, so the dispose can never be looking at a stage the
        // screen has already forgotten. `stage` is non-null here by construction — nothing is
        // editable until the load has finished — but the null branch simply records nothing rather
        // than asserting, because a crash on a keystroke is not a defensible way to find out.
        stage?.let { spec ->
            pending.record(
                revision = revision,
                workshopId = workshopId,
                stage = spec,
                state = next,
                seen = seen,
                emptied = emptied,
                deletedRows = deletedRows,
                syncable = syncId != null,
            )
        }
    }

    // ── Media ────────────────────────────────────────────────────────────────────────────────────
    val media = remember(workshopId, stageKey, mediaIndex) {
        DwMediaBridge(
            resolve = { id ->
                mediaIndex[id]?.let { descriptor ->
                    DwMediaItem(
                        id = descriptor.id,
                        displayName = descriptor.originalFilename.ifBlank { descriptor.id },
                        absolutePath = WorkshopDraftStore
                            .mediaFile(appContext, workshopId, descriptor).absolutePath,
                        mediaType = descriptor.mediaType,
                        sizeBytes = descriptor.sizeBytes,
                        sha256 = descriptor.sha256,
                        // THE SERVER'S ID, PASSED THROUGH RATHER THAN DERIVED. Null until
                        // `/media/complete` has answered with one, which is that field's whole
                        // discipline — and it is what lets the AI media verbs tell "this file is on
                        // the server" from "these bytes are only here". See [DwMediaItem].
                        remoteMediaId = descriptor.remoteMediaId,
                    )
                }
            },
            attach = { uris: List<Uri>, field: FieldDto, onAttached: (List<String>) -> Unit ->
                // ONE coroutine for the whole selection, importing IN ORDER and reporting the
                // ids once. Launching one coroutine per Uri let their completion callbacks race
                // and overwrite each other's appends, so a five-photo pick kept one photo and
                // orphaned four — see the note on DwMediaBridge.attach.
                scope.launch {
                    val imported = mutableListOf<String>()
                    val added = mutableMapOf<String, com.designprototype.workshop.data.DraftMedia>()
                    var failures = 0
                    for (uri in uris) {
                        runCatching {
                            // Copies the bytes into filesDir/workshops/<id>/media BEFORE the id is
                            // stored. The picker's Uri is a permission grant scoped to this task
                            // and the app's own camera captures land in cacheDir, which Android
                            // empties without warning — either one leaves the draft pointing at
                            // nothing by morning.
                            WorkshopDraftStore.importMedia(
                                context = appContext,
                                workshopId = workshopId,
                                uri = uri,
                                stageId = stageKey,
                                fieldKey = field.key,
                            )
                        }.onSuccess { descriptor ->
                            added[descriptor.id] = descriptor
                            imported += descriptor.id
                        }.onFailure {
                            // Keep going. One unreadable file out of five must not cost the
                            // designer the other four, and the count below says what happened.
                            failures++
                        }
                    }
                    if (added.isNotEmpty()) {
                        mediaIndex = mediaIndex + added
                    }
                    if (imported.isNotEmpty()) {
                        onAttached(imported)
                    }
                    if (failures > 0) {
                        onError(
                            if (imported.isEmpty()) "That file could not be attached."
                            else "$failures of ${uris.size} files could not be attached."
                        )
                    }
                }
            },
            detach = { id: String ->
                scope.launch {
                    runCatching { WorkshopDraftStore.removeMedia(appContext, workshopId, id) }
                    mediaIndex = mediaIndex - id
                }
            },
            newCaptureFile = { suffix ->
                // INSIDE THE WORKSHOP'S OWN DIRECTORY UNDER filesDir, never cacheDir. MainActivity's
                // `createAppFile` writes to `cacheDir/field-captures/`, which is right for a record
                // form that uploads within seconds; here the file is the document. Android reclaims
                // cacheDir under storage pressure without warning and without a callback, so a
                // camera intent writing there can have its output deleted between the shutter and
                // the import — a photograph that was taken, was on screen, and is gone by morning.
                //
                // `captures/` is a sibling of `media/` rather than the same directory, and the
                // separation is kept even though the hazard it was written against is gone:
                // `removeMedia` used to reclaim space by deleting every file under `media/` that no
                // descriptor referenced, and a capture still on its way through the camera has no
                // descriptor yet. That sweep has since been narrowed to the one file the detached
                // descriptor named (see its KDoc — it was deleting photographs another import was
                // mid-copy), so this directory is no longer the thing standing between a camera
                // intent and a deletion. It stays because `media/` is the set of files the report
                // writer and the uploader walk, and a half-written camera output has no business in
                // that set until the import has copied and measured it.
                val dir = java.io.File(
                    WorkshopDraftStore.workshopDir(appContext, workshopId), "captures"
                ).apply { mkdirs() }
                java.io.File(dir, "capture-${UUID.randomUUID()}$suffix")
            },
        )
    }

    /**
     * The registry, the network and the message sinks, as one handle the renderer can be given.
     *
     * `workshopId` here is the SYNC id and may be null, which is the state that matters: a workshop
     * created in a courtyard has no server record, so the references endpoint has nothing to answer
     * about it. The picker treats null as "cache only" rather than as an error — an ALL-scoped list
     * some earlier workshop downloaded onto this handset is still perfectly usable, and it is what
     * lets a designer link a real artisan record on day one of an offline workshop.
     */
    val services = remember(syncId, workshopId, stageKey, inlineRecords) {
        DwFieldServices(
            repository = repository,
            workshopId = syncId,
            onMessage = onMessage,
            onError = onError,
            inlineRecords = inlineRecords,
        )
    }

    // ── Render ───────────────────────────────────────────────────────────────────────────────────
    if (loading) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
            Text("Opening the stage…", color = MaterialTheme.field.muted, fontSize = 13.sp)
        }
        return
    }

    val spec = stage ?: return
    val completeness = remember(spec, state, definition) {
        computeStageCompletenessFor(spec, state, definition)
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        StageHeader(
            spec,
            completeness.percent,
            saveState,
            syncNote,
            localOnly = syncId == null,
            // What the save that produced [SaveState.SYNCED] did NOT store. See the SYNCED arm.
            //
            refused = refusals?.count ?: 0,
            // AND WHAT THE SAME SAVE DID NOT STORE FOR THE OTHER REASON, which the count above does not
            // cover: [DwStageRefusalReport.count] counts refusals and unplaced scopes only. A response
            // that refused NOTHING and reported two of this workshop's own answers as not stored —
            // measured on the running API, HTTP 200, `errors {}`,
            // `droppedCustomKeys ['dyeVatCount','retiredQuestion']` — gave `count == 0`, and this line
            // printed "saved and synced" directly above a card saying two answers had not been stored.
            notStored = refusals?.droppedCustomKeys?.size ?: 0,
        )

        // SAID OUT LOUD, because the alternative is a blank stage that looks exactly like an empty
        // one. A designer who opens stage 5 in a courtyard with no signal and is shown nothing
        // concludes the stage was never filled in, and starts filling it in again — over a fortnight
        // of process steps, tools and raw materials that are sitting safely on the server and are
        // simply not on this handset. The save built from that screen is deliberately non-authoritative
        // (see [StageDraft.stageSeen]) so nothing is destroyed, but silence would still have cost the
        // designer the afternoon.
        //
        // THE HEADING IS NOT A CONSTANT ANY MORE. Three different things reach this card — the
        // download failed on a blank stage, it failed on a stage that holds work, and it SUCCEEDED
        // and added answers this device had never seen — and a card headed "This stage could not be
        // downloaded" above a sentence saying it had just been read is the kind of contradiction a
        // designer stops reading the card over.
        downloadNote?.let { note ->
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.warningContainer),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        noteTitle ?: "This stage could not be downloaded",
                        color = MaterialTheme.field.onWarningContainer,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(note, color = MaterialTheme.field.onWarningContainer, fontSize = 11.sp)
                }
            }
        }

        /*
          WHAT THE REPOSITORY REFUSED, ABOVE THE FORM AND NAMING EVERY QUESTION.

          `StageSaveResultDto.errors` had been decoded off every save this app ever made and read by
          nothing at all, so a refusal was indistinguishable from a success: the phone kept showing
          the typed text, the repository kept the previous value, the stage reported itself synced.
          This card and the marks on the boxes below are the whole of the remedy, and they are two
          halves rather than one — the marks say WHICH box, the card says what the repository now
          holds, which no box can, and reaches a designer who cannot see the marks at all.

          `_custom` refusals and refusals for keys this build has no control for land here too, and
          only here: there is no box to mark for a question this app cannot draw. See
          [DwStageRefusalReport.unplaced].
        */
        refusals?.takeIf { !it.isEmpty }?.let { report ->
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        // A report can now carry `droppedCustomKeys` and no refusals at all, and for
                        // that one "not ACCEPTED" is the wrong word: nobody objected to the answer, the
                        // sections simply no longer ask the question, so it was not stored.
                        if (report.count > 0) "Some answers were not accepted"
                        else "Some answers were not stored",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        report.heading,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 11.sp
                    )
                    report.refusals.forEach { refusal ->
                        Text(
                            "• ${refusal.sentence}",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 11.sp
                        )
                    }
                    report.unplaced.forEach { line ->
                        /*
                          SAID, NOT DROPPED. The repository objected to something this build cannot
                          place on the form — a row index that does not match the payload we sent, an
                          entity that is not the one sitting at that position, a scope whose payload
                          is not a field map at all. Silence here would be the same defect as the one
                          this card exists to fix, one level in.

                          IT NO LONGER SENDS ANYBODY TO THE BROWSER, and that sentence was checked
                          rather than assumed. The web's stage page re-keys `entity[i]` and hands the
                          result to `EntityForm`, which reads `errorsByIndex?.[index]` inside
                          `rows.map((row, index) => …)` — so it can only ever draw an index that IS a
                          row on screen. Every member of THIS list is, by construction, one that is
                          not: out of range, or filed against an entity the payload did not put
                          there. The browser drops all of them in silence. Telling a designer to
                          drive back and open the workshop on a laptop to see a message that is not
                          drawn there either is a worse lie than the silence this card replaced, so
                          the line now says what is actually true and who can act on it.
                        */
                        Text(
                            "• $line — neither this app nor the browser can show which box that " +
                                "is: the repository filed it against a position this stage did not " +
                                "send. Nothing you typed has been lost. Send this line to whoever " +
                                "runs the repository.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // WHERE THIS STAGE WAS WRITTEN, on every stage and above the fields. The registry has no
        // field for it anywhere — its GEO fields describe the SUBJECT (a cluster, a workshop, a
        // demonstration site), which is a different question from where the designer was standing.
        // A 22-stage report is assembled over a fortnight across several villages, and a reviewer
        // asking "was stage 14 written at the cluster or typed up afterwards in Jaipur?" has
        // nothing to read without this. Stored under an underscore key so the sync protocol strips
        // it by construction rather than by anyone remembering to — see [DW_RECORDING_PLACE_KEY].
        DwRecordingPlaceCard(
            value = state.singleton[DW_RECORDING_PLACE_KEY],
            repository = repository,
            onChange = { next ->
                edit { current ->
                    current.copy(singleton = current.singleton.put(DW_RECORDING_PLACE_KEY, next))
                }
            },
            onMessage = onMessage,
        )

        spec.singleton?.let { entity ->
            EntitySection(
                entity = entity,
                values = state.singleton,
                media = media,
                services = services,
                // Filed by the repository under the bare entity key for a singleton.
                errors = refusals?.byAddress?.get(entity.key).orEmpty(),
                // Only a singleton focus lands here — one naming a row belongs to a collection
                // below, and handing it to this section would scroll to a field of the same key in
                // the wrong entity.
                focusFieldKey = focus?.takeIf { it.entityKey == entity.key && it.rowKey == null }?.fieldKey,
                showAdvanced = showAdvanced,
                onToggleAdvanced = { showAdvanced = !showAdvanced },
                onValueChange = { key, value ->
                    edit { current -> current.copy(singleton = current.singleton.put(key, value)) }
                },
                stamps = state.provenance.singleton,
                onPatch = { patch ->
                    // ONE state write for the whole hydration. Calling `onValueChange` once per key
                    // would work here by luck — the singleton setter reads through `edit`'s
                    // transform — but it does NOT work for a collection row, and having the two
                    // paths differ is how a bug arrives in only one of them. See `onPatch` on
                    // [FieldRenderer].
                    edit { current -> current.copy(singleton = current.singleton.putAll(patch)) }
                }
            )
        }

        // THE DESIGNER'S OWN QUESTIONS, BETWEEN THE STAGE'S OWN FIELDS AND ITS REPEATING ROWS.
        //
        // The position is the scorer's, not a layout preference: `computeStageCompleteness` counts
        // custom fields between the singleton and the collections, and `missing` is printed in that
        // order and truncated at three. A form that drew them last would send a designer following a
        // "still missing" link to the bottom of a stage for the item their list printed first.
        DwCustomSectionForm(
            definition = definition,
            stageKey = spec.key,
            values = state.custom,
            // `_custom` is not a registry entity, so its refusals arrive under the reserved key
            // itself — see `save_stage`, which files them there precisely so both clients' existing
            // per-entity rendering works on them unchanged.
            errors = refusals?.byAddress?.get(CUSTOM_ENTITY_KEY).orEmpty(),
            onValueChange = { key, value ->
                edit { current -> current.copy(custom = current.custom.put(key, value)) }
            },
            services = services,
        )

        spec.collections.forEach { entity ->
            CollectionSection(
                entity = entity,
                rows = state.collections[entity.key].orEmpty(),
                // This entity's slice of the stage's stamps. Keyed by entry id inside; see
                // [CollectionSection.stampsByEntry] for why it may never be keyed by position.
                stampsByEntry = state.provenance.collections[entity.key].orEmpty(),
                media = media,
                services = services,
                focus = focus?.takeIf { it.entityKey == entity.key },
                errorsByRow = refusals?.refusals.orEmpty()
                    .filter { it.entityKey == entity.key && it.rowIndex != null }
                    .groupBy { it.rowIndex!! }
                    .mapValues { (_, list) -> list.associate { it.fieldKey to it.message } },
                onRowsChange = { rows ->
                    // RECORDED THE MOMENT THE LAST ROW GOES, and only then. An emptied collection
                    // contributes no entries to a stage payload, so without this the deletion is
                    // invisible on the wire and the rows simply come back — into the .docx a ministry
                    // receives. The condition is "this screen was showing rows and now shows none",
                    // which is a thing the designer did, and not "the draft holds no rows", which is
                    // also true of a collection whose rows were entered on the web this morning.
                    val before = state.collections[entity.key].orEmpty()
                    val had = before.isNotEmpty()
                    emptied = when {
                        rows.isEmpty() && had -> emptied + entity.key
                        rows.isNotEmpty() -> emptied - entity.key
                        else -> emptied
                    }
                    /*
                      AND THE ROWS THEMSELVES, WHICH `emptied` ABOVE CANNOT SEE — see
                      [StageDraft.deletedRowKeys]. Deleting one row of three does not empty the
                      collection, so the branch above records nothing, and before this list existed the
                      deletion was written down nowhere at all: `unsentDeletions` could not count it and
                      `isFullySynced` had no term for it, so the workshop row said "Backed up to the
                      server" while the row was still in the repository and still in the report.

                      COMPUTED FROM WHAT LEFT THE LIST, not from what the list now holds. The rows this
                      screen was showing minus the rows it is about to show IS the designer's action;
                      "the draft holds no row with this key" is also true of a row entered on the web
                      this morning, and recording that would ask the server to delete it.

                      A KEY IS DROPPED AGAIN THE MOMENT THE ROW COMES BACK. A row deleted and re-added
                      before the next save owes nothing, and leaving the key behind would ask the
                      server to delete a row the payload is about to name — which the sweep would
                      refuse to do anyway, leaving a count that never falls to zero and a stage that
                      says it owes a deletion for ever.
                    */
                    val nowHeld = rows.mapTo(HashSet()) { dwRowId(entity.key, it.rowId) }
                    val gone = before
                        .map { dwRowId(entity.key, it.rowId) }
                        .filter { it !in nowHeld }
                    deletedRows = (deletedRows + gone) - nowHeld
                    edit { current -> current.copy(collections = current.collections + (entity.key to rows)) }
                }
            )
        }

        // WHAT THE ROWS ON THIS HANDSET ACTUALLY SAY, under the form and never inside it. Stage 9's
        // declared price bands are checked against stage 8's price expectations, and stage 17's
        // typed subtotals against their own line items — both computed here, with no network, from
        // rows that are already in filesDir. It draws nothing on the other twenty stages.
        //
        // BELOW the collections deliberately: the finding is about what has just been entered, so it
        // reads as a consequence of the form rather than as a gate in front of it. See
        // [DwStageFindings] for why nothing here may write a value back.
        DwStageFindings(
            stage = spec,
            workshopId = workshopId,
            repository = repository,
            rows = remember(state) {
                state.collections.mapValues { (_, rows) -> rows.map { it.values } }
            },
        )

        // Explicit sync, for the moment a designer walks back into signal and wants to know the work
        // has left the phone rather than trusting a debounce they cannot see.
        //
        // IT SAVES, RATHER THAN ASKING THE TIMER TO. This was `onClick = { revision++ }`, which
        // re-entered the same 800ms debounce the button exists to let a designer stop trusting — so
        // the label promised "now" and delivered "in a moment, if you are still here", and pressing
        // it and immediately leaving wrote nothing at all. The dispose above is the belt to this
        // brace; both are needed, because the tap is also the moment somebody puts the phone down.
        OutlinedButton(
            onClick = { scope.launch { saveAndSync() } },
            enabled = saveState != SaveState.SAVING,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save and sync this stage now") }

        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

// --------------------------------------------------------------------------------------
// Header
// --------------------------------------------------------------------------------------

@Composable
private fun StageHeader(
    stage: StageDto,
    percent: Int,
    saveState: SaveState,
    syncNote: String?,
    localOnly: Boolean,
    /** How many answers the save that reached [SaveState.SYNCED] came back refusing. */
    refused: Int = 0,
    /**
     * How many answers that same save did not store for a DIFFERENT reason — this workshop's own
     * sections no longer ask the question. See [DwStageRefusalReport.droppedCustomKeys].
     *
     * A SECOND NUMBER RATHER THAN ADDED TO THE FIRST, because "refused" is the wrong word for it and
     * the remedy is not the same: nobody objected to the answer, so telling a designer it was refused
     * sends them to correct something that was never wrong. It still cannot be left out of the line —
     * without it a save that stored neither of two custom answers printed "saved and synced".
     */
    notStored: Int = 0,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "Stage ${stage.number} of 22" + if (stage.optionalStage) " · optional" else "",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )
        Text(
            stage.title,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp
        )
        if (stage.purpose.isNotBlank()) {
            Text(stage.purpose, color = MaterialTheme.field.body, fontSize = 13.sp)
        }
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            when (saveState) {
                SaveState.CLEAN -> "$percent% of the required fields are filled in."
                SaveState.PENDING -> "$percent% complete · unsaved changes"
                SaveState.SAVING -> "$percent% complete · saving…"
                // Named precisely. "Saved" alone would let a designer believe the work had left the
                // handset, and this app's whole premise is that for most of a fortnight it has not.
                SaveState.ON_DEVICE -> if (localOnly) {
                    "$percent% complete · saved on this device. This workshop has not been created on " +
                        "the server yet — send it from the workshop list once you have a connection."
                } else {
                    "$percent% complete · saved on this device, not yet synced"
                }
                /*
                  "SAVED AND SYNCED" IS FALSE OF A SAVE THE REPOSITORY PARTLY REFUSED, and it was
                  being printed for one — measured on the handset, this exact line read "0% complete
                  · saved and synced" with the red card two lines beneath it saying the repository
                  had refused two of the answers and kept what it already held. It is the same claim
                  [WorkshopSyncStatus.isFullySynced] was corrected for one screen out; this line is
                  the one a designer is looking at while they are still in the stage.

                  It counts rather than pointing at the card alone, so the two cannot drift: the card
                  can be scrolled past, and a number that disagrees with it would be worse than
                  either.
                */
                SaveState.SYNCED -> when {
                    refused > 0 -> "$percent% complete · saved, and $refused answer" +
                        (if (refused == 1) " was" else "s were") +
                        " refused — see below. Everything else is on the server." +
                        // Both at once, and the second gets its own words because its remedy is a
                        // refresh of the sections rather than a correction of the answer.
                        if (notStored > 0) {
                            " $notStored more " + (if (notStored == 1) "was" else "were") +
                                " not stored — the sections no longer ask " +
                                (if (notStored == 1) "it" else "them") + "."
                        } else ""
                    // NOBODY OBJECTED TO ANYTHING, AND THE SAVE IS STILL NOT CLEAN. Reached by a
                    // response with an empty `errors` map and a non-empty `droppedCustomKeys` — HTTP
                    // 200, measured on the running API — which used to print "saved and synced" over a
                    // card saying two of this workshop's own answers had not been stored.
                    notStored > 0 -> "$percent% complete · saved, and $notStored answer" +
                        (if (notStored == 1) " was" else "s were") +
                        " not stored because the sections no longer ask " +
                        (if (notStored == 1) "it" else "them") +
                        " — see below. Everything else is on the server."
                    else -> "$percent% complete · saved and synced"
                }
            },
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )
        syncNote?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        if (stage.notes.isNotBlank()) {
            Text(stage.notes, color = MaterialTheme.field.muted, fontSize = 12.sp)
        }
        HorizontalDivider()
    }
}

// --------------------------------------------------------------------------------------
// One entity's fields
// --------------------------------------------------------------------------------------

/**
 * The singleton entity: BASIC + STANDARD in the open, ADVANCED behind a disclosure.
 *
 * Caption fields never appear here. They are removed from the flow and handed to the media field they
 * describe, because `captionFor` exists exactly so the two cannot be separated — see [FieldRenderer].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntitySection(
    entity: EntityDto,
    values: Map<String, JsonElement>,
    media: DwMediaBridge,
    services: DwFieldServices,
    /**
     * The one field of THIS record a designer was sent to, or null.
     *
     * The arrival lives here rather than on the stage screen because the moment to scroll is the
     * moment the box EXISTS, and when that is depends on where it lives: a singleton field is drawn
     * on first paint, a collection field appears when its row opens, an ADVANCED field when its
     * disclosure does. An effect inside the section is right in all three without enumerating any of
     * them — the same reasoning `FieldCell` in `components/designworkshop/EntityForm.tsx` gives for
     * putting it on the cell.
     */
    focusFieldKey: String? = null,
    /**
     * The repository's per-field refusals for THIS record, by field key — see [DwStageRefusal].
     *
     * Drawn on the box rather than only in the card above the form, because a message several hundred
     * pixels from the control it is about is a message a designer has to hunt for, and one a screen
     * reader never associates with anything at all. `FieldRenderer` has taken an `error` since it was
     * written; nothing had ever passed one.
     */
    errors: Map<String, String> = emptyMap(),
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    onValueChange: (String, JsonElement?) -> Unit,
    /** Hydration from a reference pick: several keys, one write. See `putAll` and [FieldRenderer]. */
    onPatch: (Map<String, JsonElement?>) -> Unit,
    /**
     * WHO LAST SET EACH FIELD OF THIS RECORD, keyed by field key.
     *
     * One record's worth, resolved by the caller — the singleton's map, or the map for THIS ROW's
     * entry id. Empty renders nothing, so a caller that does not pass it behaves as before.
     */
    stamps: Map<String, DwFieldStampDto> = emptyMap(),
    /**
     * What the field inputs' local text buffers belong to — see `resetKey` on [FieldRenderer].
     *
     * The entity key is right for a singleton, which has exactly one record. A collection passes the
     * ROW's id instead, because every row of an entity is drawn through the same composable slots
     * with the same field keys, and two rows that both leave a field blank would otherwise share one
     * text buffer: row 1's half-typed answer stays on screen over row 2 and is written into it on the
     * next keystroke.
     */
    resetKey: Any = entity.key,
) {
    val captions = remember(entity) { entity.liveFields.filter { it.captionFor.isNotBlank() } }
    val captionByTarget = remember(captions) { captions.associateBy { it.captionFor } }
    val visible = remember(entity, captions) {
        entity.liveFields.filter { it.captionFor.isBlank() }
    }
    val upfront = remember(visible) { visible.filter { DwTier.of(it.tier) != DwTier.ADVANCED } }
    val advanced = remember(visible) { visible.filter { DwTier.of(it.tier) == DwTier.ADVANCED } }
    /**
     * Every live field by key, INCLUDING the captions pulled out of the flow above.
     *
     * A cascading picker resolves `refFilterBy` through this map, and a hydration writes only keys
     * it contains. Building it from `liveFields` rather than from `visible` matters for the second
     * of those: a reference record can perfectly well supply a photograph caption, and excluding
     * captions here would silently refuse to hydrate it while hydrating everything around it.
     */
    val fieldsByKey = remember(entity) { entity.liveFields.associateBy { it.key } }

    /*
      THE MARKS ACTUALLY DRAWN: WHAT THIS DEVICE CAN WORK OUT FOR ITSELF, UNDER WHAT THE SERVER SAID.

      [DwValues.validate] is declared as the handset's copy of the server's `validate_entry`, with a
      KDoc arguing that the whole reason [DwValues] exists is "to say the same 'no' the server would
      say, at the moment the value is typed". It had NO CALLER anywhere in `android/app/src` outside
      one unit test. The only validation the form ran was `ScalarInput.commit`'s per-keystroke
      `coerce`, which never asks about requiredness and never sees a value written by a reference
      hydration or by the photo-measure panel.

      What that cost, concretely: the server applies `_check_conditional` on every save, ungated —
      "Reason for the override is required once Designs count override or Prototypes count override is
      filled in." A designer types the override figure at stage 20 in a courtyard, leaves the reason
      blank (the registry marks it `required: false`, so completeness and readiness say nothing), and
      meets the refusal a fortnight later as a card about a stage they finished in another district.

      `enforceRequired = false`, DELIBERATELY, and it is not a weakening. With it on, every empty
      required box on a 40-field stage turns red the moment the screen opens, which is the wall of red
      `coerce`'s own KDoc refuses; the conditional rule is NOT gated on it (see `checkConditional`),
      precisely because it fires only on a figure the designer has just typed. So this draws the
      conditional mark and any malformed stored value, and nothing else.

      THE SERVER'S REFUSALS WIN EVERY KEY THEY NAME — `+ errors` last. A local mark is a prediction;
      a refusal is something the repository has already done, and replacing "the repository would not
      store this" with a guess would be a downgrade.

      Memoised on the values, because this walks every live field of the entity and coerces the
      text-shaped ones; recomposition happens on every keystroke.
    */
    val marks = remember(entity, values, errors) {
        DwValues.validate(entity, values, enforceRequired = false) + errors
    }

    /**
     * "2 of 3 views captured — no back view", for the one entity in the registry that really has
     * named view slots (stage 6's `existingProduct`). Every other entity gets nothing at all: asking
     * for a "back view" on a form that has no such field is worse than saying nothing, because the
     * designer cannot act on it. See [DwImageQuality.NAMED_VIEW_SLOTS].
     */
    val missingViews = remember(entity.key, values) { DwImageQuality.findMissingViews(entity.key, values) }

    /**
     * The arrival: scroll the named box into view, then mark it for [FIELD_FLASH_MS].
     *
     * ONE FRAME LATER, because this effect runs in the same commit that opened the row panel or the
     * disclosure above it — measuring now measures a layout the field is about to leave. That is the
     * same deferral `FieldCell` makes with `requestAnimationFrame` on the web.
     *
     * THE MARK IS A STATIC OUTLINE and not an animation. It is what tells a designer WHICH of the
     * three boxes now on screen is the one they tapped, in a form of several hundred, and a signal
     * that existed only as motion would be no signal at all for a reader who has asked for less of
     * it. It is drawn on a border that is always laid out and merely changes colour, so arriving
     * does not shift the field under the finger that is about to type into it.
     */
    val arrival = remember { BringIntoViewRequester() }
    var marked by remember(focusFieldKey) { mutableStateOf(false) }
    LaunchedEffect(focusFieldKey) {
        if (focusFieldKey == null) return@LaunchedEffect
        withFrameNanos { }
        try {
            arrival.bringIntoView()
        } catch (e: CancellationException) {
            // Rethrown rather than swallowed: this is the designer leaving, and a `runCatching` here
            // would eat the cancellation and go on to mark a field on a screen that has gone.
            throw e
        } catch (_: Throwable) {
            // The node went away between the frame above and this call. Not scrolling is a small
            // failure; crashing on the way to a field a designer asked for is not.
        }
        marked = true
        delay(FIELD_FLASH_MS)
        marked = false
    }

    /** The anchor. Applied to the focused field and to nothing else — see [FieldRenderer]'s modifier. */
    val anchor = Modifier
        .bringIntoViewRequester(arrival)
        .border(
            width = 2.dp,
            color = if (marked) MaterialTheme.colorScheme.primary else Color.Transparent,
            shape = RoundedCornerShape(10.dp),
        )
        .padding(6.dp)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        if (entity.description.isNotBlank()) {
            Text(entity.description, color = MaterialTheme.field.muted, fontSize = 12.sp)
        }
        missingViews.firstOrNull()?.let { DwMissingViewsNote(it.message) }
        upfront.forEach { field ->
            FieldRenderer(
                field = field,
                value = values[field.key],
                onChange = { next -> onValueChange(field.key, next) },
                modifier = if (field.key == focusFieldKey) anchor else Modifier,
                error = marks[field.key],
                media = media,
                caption = captionByTarget[field.key],
                captionValue = captionByTarget[field.key]?.let { values[it.key] },
                onCaptionChange = { next ->
                    captionByTarget[field.key]?.let { onValueChange(it.key, next) }
                },
                resetKey = resetKey,
                services = services,
                siblings = fieldsByKey,
                rowValues = values,
                onPatch = onPatch,
                stamp = stamps[field.key],
            )
        }
        if (advanced.isNotEmpty()) {
            DisclosureHeader(
                label = "More detail (${advanced.size} advanced field${if (advanced.size == 1) "" else "s"})",
                expanded = showAdvanced,
                onToggle = onToggleAdvanced
            )
            if (showAdvanced) {
                advanced.forEach { field ->
                    FieldRenderer(
                        field = field,
                        value = values[field.key],
                        onChange = { next -> onValueChange(field.key, next) },
                        modifier = if (field.key == focusFieldKey) anchor else Modifier,
                        error = marks[field.key],
                        media = media,
                        caption = captionByTarget[field.key],
                        captionValue = captionByTarget[field.key]?.let { values[it.key] },
                        onCaptionChange = { next ->
                            captionByTarget[field.key]?.let { onValueChange(it.key, next) }
                        },
                        resetKey = resetKey,
                        services = services,
                        siblings = fieldsByKey,
                        rowValues = values,
                        onPatch = onPatch,
                        stamp = stamps[field.key],
                    )
                }
            }
        }
    }
}

@Composable
private fun DisclosureHeader(label: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.field.muted
        )
    }
}

// --------------------------------------------------------------------------------------
// Repeating entities
// --------------------------------------------------------------------------------------

/**
 * One COLLECTION entity as an add / edit / reorder / delete list.
 *
 * Reorder is two arrow buttons rather than a drag handle, and that is a dependency decision as much
 * as an ergonomic one: a reorderable LazyColumn means either a third-party library or a hand-rolled
 * drag detector, and this module has neither. Arrows also work with TalkBack, which a drag gesture
 * does not without a custom accessibility action nobody would remember to add.
 *
 * ORDER IS THE ORDINAL. The list's position is what is sent as `ordinal`, so what the designer sees
 * on the phone is the order the rows print in the report — a costing table whose lines reshuffle
 * between the screen and the .docx is a table an officer will send back.
 */
@Composable
private fun CollectionSection(
    entity: EntityDto,
    rows: List<CollectionRow>,
    media: DwMediaBridge,
    services: DwFieldServices,
    /** A gap the designer tapped that lives in one of these rows — see [DwStageFocus]. */
    focus: DwStageFocus? = null,
    /**
     * Refusals for this collection, keyed by the row's position ON SCREEN.
     *
     * Not by the entry's index in the payload, which is what the repository keys them by — that
     * translation has already happened in [dwDecodeStageRefusals], once, against the array that was
     * actually sent. Doing it here would be the second ordering the web's `buildStageEntries` warns
     * about, and it puts every message after the first collection on the wrong row.
     */
    errorsByRow: Map<Int, Map<String, String>> = emptyMap(),
    /**
     * WHO LAST SET EACH FIELD OF EACH ROW, keyed BY ENTRY ID and then by field key.
     *
     * BY ENTRY ID AND NEVER BY POSITION, which is the same rule [DwStageProvenanceDto] states for
     * the wire and for the same reason: the server, its report builder and this device each sort
     * these rows differently, so a positional lookup shows one participant's edits under another
     * participant's name — in the table that exists to prove who attended. `errorsByRow` above is
     * keyed by screen position deliberately and is NOT a precedent for this: that translation was
     * done once, against the array that was actually sent.
     */
    stampsByEntry: Map<String, Map<String, DwFieldStampDto>> = emptyMap(),
    onRowsChange: (List<CollectionRow>) -> Unit,
) {
    // OPEN ON THE ROW THAT HOLDS IT. Rows are collapsed by default, so a link to "the material on
    // prototype 1" that landed on a closed list would have arrived at a heading. `rowKey` is the
    // row's `_clientKey`, which is exactly what `rowId` holds here — both are `DraftRow.id` past
    // its entity prefix (see `fromDraft` and `DwSubmissionReadiness.rowKeyOf`).
    // Opened on the row the repository refused something in, when nothing more specific was asked
    // for. A message on a box inside a COLLAPSED row is a message nobody sees — the card above the
    // form names the row number, and a designer who has just read it should not then have to guess
    // which of nine collapsed cards to tap.
    var expanded by remember(entity.key, focus, errorsByRow) {
        mutableStateOf(focus?.rowKey ?: errorsByRow.keys.minOrNull()?.let { rows.getOrNull(it)?.rowId })
    }
    val titleField = remember(entity) { entity.rowTitleField }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entity.title,
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (rows.isEmpty()) "None recorded yet" else "${rows.size} recorded",
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp
                )
            }
            OutlinedButton(onClick = {
                val fresh = CollectionRow(rowId = UUID.randomUUID().toString(), values = emptyMap())
                onRowsChange(rows + fresh)
                expanded = fresh.rowId
            }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add")
            }
        }
        if (entity.description.isNotBlank()) {
            Text(entity.description, color = MaterialTheme.field.muted, fontSize = 12.sp)
        }

        rows.forEachIndexed { index, row ->
            CollectionRowCard(
                entity = entity,
                row = row,
                index = index,
                total = rows.size,
                title = titleField
                    ?.let { DwValues.text(row.values[it.key]) }
                    ?.takeIf { it.isNotBlank() }
                    ?: "${entity.title} ${index + 1}",
                expanded = expanded == row.rowId,
                media = media,
                services = services,
                focusFieldKey = focus?.takeIf { it.rowKey == row.rowId }?.fieldKey,
                errors = errorsByRow[index].orEmpty(),
                onToggle = { expanded = if (expanded == row.rowId) null else row.rowId },
                onMove = { delta ->
                    val target = index + delta
                    if (target in rows.indices) {
                        val reordered = rows.toMutableList()
                        reordered.add(target, reordered.removeAt(index))
                        onRowsChange(reordered)
                    }
                },
                onDelete = {
                    onRowsChange(rows.filterNot { it.rowId == row.rowId })
                    if (expanded == row.rowId) expanded = null
                },
                onValueChange = { key, value ->
                    onRowsChange(
                        rows.map { existing ->
                            if (existing.rowId == row.rowId) {
                                existing.copy(values = existing.values.put(key, value))
                            } else {
                                existing
                            }
                        }
                    )
                },
                onPatch = { patch ->
                    // THE REASON `onPatch` EXISTS AT ALL. `rows` here is the list this composition
                    // captured; `onRowsChange` replaces it wholesale. Two calls in the same frame
                    // therefore both map over the SAME captured list, and the second write erases
                    // the first. A reference hydration writes eight keys, so eight per-key calls
                    // would land one of them — apparently at random, since which one survives
                    // depends on iteration order. One call, one map, one write is what makes that
                    // impossible rather than merely unlikely.
                    onRowsChange(
                        rows.map { existing ->
                            if (existing.rowId == row.rowId) {
                                existing.copy(values = existing.values.putAll(patch))
                            } else {
                                existing
                            }
                        }
                    )
                },
                // THIS ROW's stamps, looked up by the entry id the server knows it by. A row the
                // server has never seen has no entry id and no stamps — correct, since nobody but
                // the person typing has set anything on it.
                stamps = (row.values["_entryId"] as? JsonPrimitive)?.content
                    ?.let { stampsByEntry[it] }
                    .orEmpty(),
            )
        }
    }
}

@Composable
private fun CollectionRowCard(
    entity: EntityDto,
    row: CollectionRow,
    index: Int,
    total: Int,
    title: String,
    expanded: Boolean,
    media: DwMediaBridge,
    services: DwFieldServices,
    /** The one field of THIS row a designer was sent to, or null. */
    focusFieldKey: String? = null,
    /** The repository's per-field refusals for THIS row — see [DwStageRefusal]. */
    errors: Map<String, String> = emptyMap(),
    onToggle: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onValueChange: (String, JsonElement?) -> Unit,
    onPatch: (Map<String, JsonElement?>) -> Unit,
    /** Who last set each field of THIS row. Empty for a row the server has never seen. */
    stamps: Map<String, DwFieldStampDto> = emptyMap(),
) {
    // Opened when the field they were sent to is behind "More detail", closed otherwise — and
    // ordinary state from then on, so the disclosure still answers its own header.
    var showAdvanced by remember(row.rowId) {
        mutableStateOf(isAdvanced(entity, focusFieldKey))
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${index + 1}. $title",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).clickable(onClick = onToggle)
                )
                IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up", tint = MaterialTheme.field.muted)
                }
                IconButton(onClick = { onMove(1) }, enabled = index < total - 1) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down", tint = MaterialTheme.field.muted)
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.field.muted
                    )
                }
            }

            if (expanded) {
                EntitySection(
                    entity = entity,
                    values = row.values,
                    media = media,
                    services = services,
                    focusFieldKey = focusFieldKey,
                    errors = errors,
                    showAdvanced = showAdvanced,
                    onToggleAdvanced = { showAdvanced = !showAdvanced },
                    onValueChange = onValueChange,
                    onPatch = onPatch,
                    resetKey = row.rowId,
                    stamps = stamps,
                )
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Remove this entry", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// --------------------------------------------------------------------------------------
// State <-> storage
// --------------------------------------------------------------------------------------

/**
 * Set or clear one key.
 *
 * A null value REMOVES the key rather than storing JSON null. The server's `_is_filled` treats an
 * explicit null as unfilled either way, but a document that accumulates a null for every field a
 * designer ever touched and cleared grows without bound across 496 fields and 22 stages, and every
 * one of those nulls is re-sent on every sync over a metered connection.
 */
private fun Map<String, JsonElement>.put(key: String, value: JsonElement?): Map<String, JsonElement> =
    if (value == null) this - key else this + (key to value)

/**
 * Set or clear MANY keys in one write.
 *
 * Choosing an artisan in a reference picker hydrates eight fields at once. Doing that with eight
 * calls to [put] through a per-key callback is the failure that ate four photographs out of five in
 * an earlier version of the media path, in exactly the same shape: the collection row's setter
 * rebuilds the row list from the `rows` snapshot its closure captured, so eight sequential calls in
 * one frame all start from the same stale snapshot and only the last survives. A designer would
 * watch a picker fill in one field out of eight and conclude the reference record was incomplete.
 *
 * Same null convention as [put] — a null value REMOVES the key rather than storing JSON null, so
 * clearing the previous record's leftovers does not leave 496 nulls accumulating in a document that
 * is re-sent on every sync over a metered connection.
 */
private fun Map<String, JsonElement>.putAll(patch: Map<String, JsonElement?>): Map<String, JsonElement> {
    if (patch.isEmpty()) return this
    val next = LinkedHashMap(this)
    patch.forEach { (key, value) -> if (value == null) next.remove(key) else next[key] = value }
    return next
}

/** The four ways a stage can be opened. See [dwStageReadPlan]. */
internal enum class DwStageRead {
    /** Show what is on disk. There is nothing to learn, or no connection to learn it over. */
    DRAFT_AS_IS,

    /** Read the server's copy and FOLD it into what is on disk — [dwFoldServerStage]. */
    FOLD_SERVER_COPY,

    /** This device holds nothing for the stage, so the server's copy is adopted verbatim. */
    SEED_FROM_SERVER,

    /** No server record at all, so this device is the whole truth and the stage starts blank. */
    SEED_BLANK,
}

/**
 * Which of the four reads an open of this stage is — decided from three facts and nothing else.
 *
 * ── WHY IT IS A FUNCTION AND NOT A CHAIN OF `if`s INSIDE THE LOAD ────────────────────────────────
 *
 * Because the routing, not any of the four bodies, is where a designer's deletion was silently
 * reversed — and a chain of `if`s inside a `LaunchedEffect` inside a composable is not reachable by
 * any test on this machine (the JVM suite has no Robolectric and no Context). This is that chain,
 * moved somewhere `DwEmptiedStageSurvivesTheOpenTest` can put a draft in front of it and read the
 * answer back.
 *
 * ── THE DEFECT: "HOLDS WORK" HAD NO TERM FOR A DELETION ──────────────────────────────────────────
 *
 * The test used to be `local.values.isNotEmpty() || local.rows.isNotEmpty() || local.custom.isNotEmpty()`
 * — so a stage whose ENTIRE content was one collection (eight of the twenty-two stages declare no
 * singleton at all, so a collection-only stage is ordinary) "held no work" the instant the designer
 * deleted its rows, and the open fell through to [DwStageRead.SEED_FROM_SERVER]. That arm adopts the
 * server's bucket verbatim through `fromRemote`, which has no notion of [StageDraft.emptiedEntities]:
 * the six deleted rows came back on screen with no notice at all, and the first debounced save after
 * that erased the record of the deletion, because `persistLocally` keeps an emptied key only while the
 * collection is still empty. `stageSeen` was then true, so the next payload re-asserted all six rows
 * under `replaceCollections` — the deletion undone, and no trace anywhere that it was ever made.
 *
 * The path is not exotic; it is the one the app itself prescribes. `statusOf` prints "Open the stage
 * once with a connection and it goes up on the save straight after" over exactly this stage, and
 * following that instruction was what destroyed the record.
 *
 * ── SO A DELETION RECORD IS WORK, AND THE FOLD IS THE ONE READER ALLOWED TO SEE IT ───────────────
 *
 * Counting `emptiedEntities`/`deletedRowKeys` as something held routes the stage to
 * [DwStageRead.FOLD_SERVER_COPY], where [dwFoldServerStage] already does the right thing and has done
 * since it was written: it declines to add rows back for an emptied entity, carries `emptiedEntities`
 * forward untouched, earns `stageSeen`, and counts what the next save will sweep in `sweptRows` so the
 * screen can SAY so. Reusing that is strictly better than teaching `fromRemote` the same rule, which
 * would be a second copy of the one decision in this lane whose mistake is unrecoverable.
 *
 * A deletion made with no connection lands on [DwStageRead.DRAFT_AS_IS] instead (`canReach` is false),
 * which is also right: the draft is shown as it is, the deleted rows stay deleted on screen, and the
 * "not read from the server yet" note the arm already sets tells the designer that emptying a box here
 * does not empty it there until the stage has been read once.
 *
 * `remoteExists` and `canReach` are passed rather than measured so this stays pure: `canReach` costs a
 * `ConnectivityObserver` lookup and is decided before the request precisely so a courtyard does not pay
 * `ApiClient`'s 30-second connect timeout in front of a form.
 */
internal fun dwStageReadPlan(
    local: StageDraft?,
    remoteExists: Boolean,
    canReach: Boolean,
): DwStageRead {
    // `custom` counted with `values` and `rows`: eight of the twenty-two stages declare no singleton
    // at all, so a stage whose only answers are the designer's own is ordinary. Left out, such a
    // stage falls to the seed arm and adopts the office's copy over answers typed in a courtyard this
    // morning.
    //
    // AND THE TWO DELETION RECORDS COUNTED BESIDE THEM — see the KDoc. An emptied collection leaves a
    // draft that holds nothing but is emphatically not a draft with nothing in it: it holds an
    // instruction, it is the only place that instruction exists, and the seed arm would overwrite it.
    //
    // ── DO NOT REPLACE THIS WITH `StageDraft.holdsAnswers()`, WHICH ASKS A DIFFERENT QUESTION ─────
    //
    // That predicate — used by `dwStageSaysNothing`, `dwStrandedStages` and `ReportSource.holdsWork` —
    // ignores `_`-prefixed singleton keys, correctly, because nothing under one can reach the wire or
    // the document. This test is not about what can TRAVEL; it is about what would be LOST, and the
    // difference is a real answer on the device. `DwRecordingPlaceCard` writes the designer's location
    // into `values["_recordingPlace"]` on all twenty-two stages, and a draft holding only that would,
    // under `holdsAnswers`, route to [DwStageRead.SEED_FROM_SERVER] — which adopts the server's bucket
    // verbatim through [fromRemote], after which `persistLocally` writes `values` WHOLESALE and the
    // recording place is gone from the device with nothing said. Counting it here costs one fold that
    // adds the server's keys to a draft that had none of them, which is the same content the seed arm
    // would have produced and loses nothing. `DwProvenanceIsNotWorkTest`'s last case pins this
    // asymmetry deliberately, so a tidy-up that unifies the three tests fails on a desktop JVM.
    val holds = local != null && (
        local.values.isNotEmpty() ||
            local.rows.isNotEmpty() ||
            local.custom.isNotEmpty() ||
            local.emptiedEntities.isNotEmpty() ||
            local.deletedRowKeys.isNotEmpty()
        )
    return when {
        // Nothing to learn (already read), or nothing to learn it from (no connection, or no record).
        holds && (local!!.stageSeen || !canReach) -> DwStageRead.DRAFT_AS_IS
        holds -> DwStageRead.FOLD_SERVER_COPY
        remoteExists -> DwStageRead.SEED_FROM_SERVER
        else -> DwStageRead.SEED_BLANK
    }
}

/**
 * What opening a stage produced: the values to show, and the two facts about PROVENANCE that decide
 * what a later save is entitled to claim.
 *
 * Spelled out rather than returned as a bare [StageState] because "the stage is empty" and "the stage
 * could not be downloaded" produce the identical screen and must not produce the identical payload.
 * Conflating them is what let one typed field sweep a fortnight of rows off the server.
 */
private data class StageLoad(
    val state: StageState,
    /** True when this device has READ the server's copy, or the server has no copy to read. */
    val seen: Boolean,
    /** True when a server read was attempted and failed — the screen says so. */
    val downloadFailed: Boolean,
    /**
     * True when this device already held work for the stage whose download failed.
     *
     * The two cases need different sentences and got the same one. A BLANK screen after a failed
     * download is the frightening case the note was written for — a designer concludes the stage was
     * never filled in and starts again over a fortnight that is safe on the server. A screen showing
     * their own work is not frightening at all; what the designer needs to know there is narrower and
     * duller, namely that a clearance will not propagate until the read lands.
     *
     * A DRAFT HOLDING ONLY A DELETION COUNTS AS HELD WORK, because the duller sentence is exactly the
     * one it is owed: the screen is showing what the designer did (a collection they emptied is
     * empty), nothing is hidden from them, and the single consequence is that the deletion does not
     * reach the server until this stage has been read once. The frightening sentence would be a lie
     * there — nothing is missing from the screen. See [dwStageReadPlan].
     */
    val heldWorkAlready: Boolean = false,
    /** What [dwFoldServerStage] added, when a read landed on a stage that already held work. */
    val foldNotice: String? = null,
)

/**
 * Is [focusFieldKey] one of this entity's ADVANCED fields — the ones behind "More detail"?
 *
 * Asked so the disclosure can be opened on arrival. A link that lands on a closed disclosure has not
 * arrived anywhere: the designer is looking at the same form they were told the field was missing
 * from, with no indication that it is one tap further down.
 */
private fun isAdvanced(entity: EntityDto, focusFieldKey: String?): Boolean {
    val key = focusFieldKey ?: return false
    return entity.liveFields.any { it.key == key && DwTier.of(it.tier) == DwTier.ADVANCED }
}

/** The same question for the stage's singleton, which is the one entity [StageScreen] itself draws. */
private fun focusOpensAdvanced(stage: StageDto, focus: DwStageFocus?): Boolean {
    val target = focus?.takeIf { it.rowKey == null } ?: return false
    val entity = stage.singleton?.takeIf { it.key == target.entityKey } ?: return false
    return isAdvanced(entity, target.fieldKey)
}

private fun fromDraft(stage: StageDto, draft: StageDraft): StageState = StageState(
    singleton = draft.values,
    custom = draft.custom,
    // Straight off the draft the boxes are filled from, in the same breath, so the attribution under
    // a field and the value in it can never come from two different reads of the stage.
    provenance = draft.provenance,
    // Carried through rather than re-derived: this branch reads no server copy, so it can neither
    // earn the fact nor honestly disclaim one the draft already recorded.
    customSeen = draft.customSeen,
    collections = stage.collections.associate { entity ->
        entity.key to draft.rowsFor(entity.key).map { row ->
            CollectionRow(
                rowId = row.id.substringAfter(DW_ROW_KEY_SEPARATOR),
                values = row.values,
            )
        }
    }
)

/**
 * The server's bucket, adopted verbatim, for a stage this device holds NOTHING for.
 *
 * IT HAS NO TERM FOR [StageDraft.emptiedEntities] AND MUST NOT NEED ONE, and that is a statement about
 * its caller rather than about deletions. It used to be reachable for a draft holding a deletion
 * record — a collection-only stage the designer emptied holds no values, no rows and no custom answers,
 * so the old "holds work" test routed it here — and adopting the bucket then put the deleted rows back
 * on screen with nothing said, after which the first debounced save dropped the record and the next
 * payload re-asserted every one of them. [dwStageReadPlan] now sends any draft holding a deletion to
 * [dwFoldServerStage], which is the one reader that declines to re-add an emptied collection's rows and
 * the one that counts them so the screen can announce the sweep.
 *
 * So the invariant to keep is the ROUTING one: if a future edit widens the arm that calls this, it must
 * teach this function the rule first, or it silently reverses deletions again — the failure with no
 * trace on the phone, no `deletedAt` on the server and no client key for anybody to recover from.
 */
private fun fromRemote(stage: StageDto, bucket: StageBucketDto): StageState = StageState(
    singleton = bucket.singleton.toMap(),
    custom = bucket.custom.toMap(),
    // THE ONE PLACE THIS FACT IS EARNED. The bucket came from a successful `GET .../stages/{key}`,
    // whose third key IS the server's `_custom` row — so from here on this device knows what the row
    // holds, including that it holds nothing, which is what makes a later clearance honest. An EMPTY
    // `bucket.custom` is therefore still evidence and still sets it: "the row is empty" and "I have
    // never seen the row" are the two states this flag exists to keep apart.
    customSeen = true,
    collections = stage.collections.associate { entity ->
        entity.key to bucket.collections[entity.key].orEmpty().map { row ->
            CollectionRow(
                // Adopt the server's `_clientKey` where the row already has one, so a row created on
                // this handset, synced, and then re-read after a reinstall keeps its identity and is
                // updated rather than duplicated on the next save.
                rowId = (row["_clientKey"] as? JsonPrimitive)?.content
                    ?: (row["_entryId"] as? JsonPrimitive)?.content
                    ?: UUID.randomUUID().toString(),
                values = row.toMap(),
            )
        }
    }
)

/**
 * The rows to WRITE BACK for a stage: everything the form is holding, plus every row on disk this
 * registry cannot draw.
 *
 * ── THE SECOND HALF IS NOT TIDINESS; WITHOUT IT THE SAVE DELETES ROWS ────────────────────────────
 *
 * `drawn` is built by walking `stage.collections`, and so is `fromDraft`, so a row filed under an
 * entity the loaded registry does not declare is never read into the form and — before this function
 * existed — was never written back either. One debounced save on any unrelated field and those rows
 * were gone from disk: never drawn, never counted, never warned about, and unrecoverable on the
 * device.
 *
 * `StageDraft.values` and `StageDraft.custom` are written WHOLESALE by `persistLocally` precisely so
 * unknown keys survive (it says so on both), and `dwFoldServerStage` starts from `ArrayList(base.rows)`
 * so a fold preserves undeclared rows. The row write was the one place in the feature that did not
 * honour the rule — an asymmetry, not a policy, which is exactly how it went unnoticed.
 *
 * IT IS REACHABLE WITHOUT ANY SERVER MISCHIEF. `StageSchemaStore.readCacheFile` deletes a registry
 * cache it cannot decode and falls back to the bundled asset, and `store` writes whatever registry is
 * fetched — including an older one after a rollback. Either leaves a handset whose registry has LOST
 * an entity that drafts on it already hold rows for.
 *
 * Nothing undeclared can reach the wire from here: `buildStageBody` walks `spec.collections` too. The
 * cost is a few objects in a JSON array on disk; the alternative was losing rows a designer typed.
 */
internal fun dwStageRowsToStore(
    stage: StageDto,
    drawn: List<DraftRow>,
    existing: StageDraft?,
): List<DraftRow> = drawn + existing?.rows.orEmpty().filterNot { row ->
    stage.collections.any { it.key == row.entityKey() }
}

/**
 * The two deletion records as the DRAFT now holds them, for the screen to adopt after a push the
 * server acknowledged. Null when there is no such stage on disk, which means "leave the screen alone".
 *
 * ── WHY THE SCREEN MUST RE-READ RATHER THAN KEEP ITS OWN COPY ────────────────────────────────────
 *
 * `emptied` and `deletedRows` are seeded from the draft once, at load, and thereafter only ever GAIN
 * keys. `recordStageSent` removes from the draft exactly the keys the acknowledged payload carried —
 * deliberately scoped to those — and nothing told the composition, so the screen's copy stayed
 * authoritative for the next write and `persistLocally` unioned the acknowledged key straight back
 * onto disk. The next differing payload then asserted the sweep a second time, and rows the office had
 * entered on the web in the meantime, in a collection this handset had once emptied, were soft-deleted
 * by an auto-save nobody asked for. The handset never re-reads a stage it has already seen, so nothing
 * else would have corrected it.
 *
 * BOTH FIELDS TOGETHER, ALWAYS. They are one fact to a designer, and refreshing one while the other
 * keeps a stale key leaves the status screen claiming an unsent deletion no payload will ever carry.
 */
internal fun dwDeletionRecordOnDisk(saved: StageDraft?): Pair<Set<String>, Set<String>>? =
    saved?.let { it.emptiedEntities.toSet() to it.deletedRowKeys.toSet() }

/**
 * Adopt the draft's deletion records after a push the server acknowledged — uncancellably, and
 * without discarding a deletion made while the push was in flight.
 *
 * ── WHY THIS IS A FUNCTION AND NOT FOUR LINES IN THE `Sent` BRANCH ───────────────────────────────
 *
 * Because the four lines shipped broken and their test could not tell. The re-read that closes the
 * resurrected-deletion defect (see [dwDeletionRecordOnDisk] for the defect itself) was written inline
 * in `saveAndSync`, which runs inside `LaunchedEffect(revision)` — the debounce, whose entire design
 * is that THE NEXT KEYSTROKE CANCELS IT. Between `pushStage` returning and a draft read landing there
 * is a suspension point, so on the handset of a designer who keeps typing — the ordinary case, the
 * case the debounce is for — the read was cancelled and the screen kept its pre-acknowledgement copy.
 * `persistLocally` unions that copy onto disk, so the acknowledged key came back and the next
 * differing payload asserted the sweep a second time. Meanwhile the test named for the refresh
 * asserted [dwDeletionRecordOnDisk], a two-line projection that passes whether or not any of this
 * runs. Pulled out here, the rule is reachable from `DwStageWriteBackTest`, which cancels the calling
 * job while [loadStage] is suspended and asserts [adopt] was called anyway.
 *
 * [NonCancellable] is the same instrument `WorkshopSync` uses around its media bookkeeping, for the
 * same reason: a record of what the SERVER HAS ALREADY ACCEPTED cannot lose a race with a teardown,
 * because the server will not say it again. The body is a local draft read and two assignments —
 * nothing here waits on a network, so nothing here can hang a screen that is being torn down.
 *
 * ── AND WHY IT IS NOT SIMPLY `= what the disk says` ──────────────────────────────────────────────
 *
 * Making the block uncancellable makes an edit racing it ORDINARY rather than exotic, and a straight
 * adoption of the draft's answer would then DELETE A DELETION: a designer who empties another
 * collection while the push is in flight has a key the screen holds and the draft has not been told
 * about, and adopting wholesale drops it — trading a resurrected deletion for a lost one, which is
 * the worse of the two because nothing later re-asserts it. So: what the disk holds, PLUS whatever
 * the screen has recorded since the write was handed over ([sentEmptied]/[sentDeletedRows], captured
 * at the `persistLocally` call).
 *
 * That is still a re-read and not a second opinion about what was sent — `buildStageBody` and
 * `recordStageSent` remain the only things that know that, which is the rule the `Sent` branch states
 * — the difference against what was sent is used only to date the SCREEN's copy, never the draft's.
 *
 * [screenEmptied] and [screenDeletedRows] are read lazily, after the load, for that same race.
 * Passing them as values would reintroduce the lost deletion through the back door.
 *
 * Returns nothing and calls [adopt] instead, at most once and never when there is no such stage on
 * disk — "leave the screen alone", as [dwDeletionRecordOnDisk] documents. It must stay a callback
 * INSIDE the [NonCancellable] block: code placed after a `withContext(NonCancellable)` in a cancelled
 * coroutine is not reliably reached, which would put the assignments back in the window this function
 * exists to close.
 */
internal suspend fun dwAdoptDeletionRecordAfterPush(
    sentEmptied: Set<String>,
    sentDeletedRows: Set<String>,
    screenEmptied: () -> Set<String>,
    screenDeletedRows: () -> Set<String>,
    loadStage: suspend () -> StageDraft?,
    adopt: (Set<String>, Set<String>) -> Unit,
) {
    withContext(NonCancellable) {
        dwDeletionRecordOnDisk(loadStage())?.let { (entities, rows) ->
            adopt(
                entities + (screenEmptied() - sentEmptied),
                rows + (screenDeletedRows() - sentDeletedRows),
            )
        }
    }
}

/**
 * Write the stage into the local draft, preserving everything on disk that this screen does not own.
 *
 * [WorkshopDraftStore.update] rather than [WorkshopDraftStore.updateStage], deliberately: attaching a
 * photo runs its own `update` that appends to the stage's `mediaIds`, and it can land between this
 * screen's read and its write. Building the [StageDraft] INSIDE the transform means the merge happens
 * under the store's lock against whatever is actually on disk, so an attachment made a moment ago
 * cannot be erased by a debounce that started before it.
 */
private suspend fun persistLocally(
    context: Context,
    workshopId: String,
    stage: StageDto,
    state: StageState,
    /** See [StageDraft.stageSeen]. Passed in, because this function rebuilds the whole record. */
    seen: Boolean,
    /** See [StageDraft.emptiedEntities]. */
    emptied: Set<String>,
    /** See [StageDraft.deletedRowKeys]. */
    deletedRows: Set<String>,
) {
    WorkshopDraftStore.update(context, workshopId) { draft ->
        val existing = draft.stages[stage.key]
        val merged = StageDraft(
            stageId = stage.key,
            title = stage.title,
            order = stage.number,
            values = state.singleton,
            // WRITTEN WHOLESALE AND THAT IS SAFE ONLY BECAUSE THE SCREEN SEEDED IT FROM DISK.
            // `fromDraft` copies the whole bucket in, drawn or not, so what goes back is what came
            // out plus whatever the designer changed. A `custom = emptyMap()` here — which is what a
            // plain `StageDraft(...)` gives you, and this function rebuilds the whole record — would
            // wipe every custom answer on the stage the first time somebody typed one character into
            // an ordinary field, with the save reporting success. That is exactly what the comment
            // below says about the two fields under it, and this one fails the same way.
            custom = state.custom,
            // ONCE EARNED, NEVER GIVEN BACK BY A KEYSTROKE, for the reason the two fields at the
            // bottom of this constructor are OR-ed with what is on disk: this function rebuilds the
            // whole record, so a plain `state.customSeen` would disclaim — on the first debounced
            // save after a screen whose download failed — a reading of the server's container that
            // an earlier session had actually made.
            customSeen = state.customSeen || existing?.customSeen == true,
            // The rows on screen, plus the ones this registry can no longer draw — see
            // [dwStageRowsToStore], which is where the whole argument for the second half is.
            rows = dwStageRowsToStore(
                stage,
                drawn = stage.collections.flatMap { entity ->
                    state.collections[entity.key].orEmpty().map { row ->
                        DraftRow(id = dwRowId(entity.key, row.rowId), values = row.values)
                    }
                },
                existing = existing,
            ),
            // Owned by the media import path, never by this screen. Copying it through from disk is
            // what keeps a debounced text save from dropping a photo attached two seconds earlier.
            mediaIds = existing?.mediaIds.orEmpty(),
            // Stored WITH the stage so the workshop list can score completeness with no registry and
            // no network — see the KDoc on [StageDraft.requiredKeys].
            requiredKeys = stage.singleton?.liveFields.orEmpty()
                .filter { it.required }
                .map { it.key },
            completedAt = existing?.completedAt,
            notes = existing?.notes.orEmpty(),
            // ONCE EARNED, NEVER GIVEN BACK BY A KEYSTROKE. This function rebuilds the whole record,
            // so a plain `StageDraft(...)` would reset both of the fields below to their class
            // defaults on every debounced save — quietly disclaiming a reading the screen had just
            // made of the server, and quietly discarding a deletion the designer made ten seconds
            // ago. OR-ing with what is already on disk is what makes them cumulative rather than
            // whatever the last frame happened to hold.
            //
            // OR-ED, NEVER ASSIGNED, AND NEVER SET BY A SAVE. This screen may only pass `true` here
            // for a stage it has actually read (see the load's fold branch); `recordStageSent` used
            // to set the same fact after ANY successful save, which is the defect [StageDraft.stageSeen]
            // is named after. A save is not a reading.
            stageSeen = seen || existing?.stageSeen == true,
            /*
              OR-ED WITH DISK, THEN FILTERED AGAINST WHAT IS ON SCREEN — AND THE FILTER IS ONLY SAFE
              BECAUSE OF WHAT THE LOAD IS NOW FORBIDDEN TO DO.

              The filter's job is the honest one the row-level filter below spells out: a collection
              that holds rows again is not emptied, and leaving the key would ask the server to delete
              rows the very same payload names — which the sweep declines to do, so the count would
              never fall to zero and the stage would claim an unsent deletion for ever.

              ITS DANGER IS THAT IT CANNOT SEE WHERE THE ROWS CAME FROM. Rows the DESIGNER put back
              should drop the key; rows the SERVER put back must not, because the record of the
              deletion is then the only place the deletion exists — and this is exactly how the emptied
              stage lost it: the open re-seeded the server's rows through `fromRemote`, the collection
              read as non-empty here, and one debounced save erased the instruction. The fix is at the
              source rather than here — [dwStageReadPlan] routes a draft holding this record to
              [dwFoldServerStage], which never re-adds an emptied collection's rows — so by the time
              this line runs, a non-empty collection means the designer, and only the designer.

              `recordStageSent` is what clears an ACKNOWLEDGED deletion, scoped to the keys the
              accepted payload actually carried. This line must never be made to do that job.
            */
            emptiedEntities = (emptied + existing?.emptiedEntities.orEmpty())
                .filter { key -> state.collections[key].orEmpty().isEmpty() }
                .distinct(),
            /*
              THE SAME RULE ONE LEVEL DOWN — OR-ED WITH DISK, THEN FILTERED AGAINST WHAT IS HELD.

              OR-ed for the reason the two fields above are: this function rebuilds the whole record,
              so a plain `deletedRows` would discard a deletion made in an earlier session on the
              first debounced save of this one.

              FILTERED because a key whose row is back in the draft is not owed. That is reachable
              without any undo feature at all: delete a row, then let `dwFoldServerStage` fold the
              server's copy of it back in on the next online open of a stage this device had emptied
              and re-populated. Leaving the key would ask the server to delete a row the very next
              payload names, which the sweep declines to do — so the count would never fall to zero
              and the stage would claim an unsent deletion for ever.
            */
            deletedRowKeys = (deletedRows + existing?.deletedRowKeys.orEmpty())
                .filterNot { key ->
                    stage.collections.any { entity ->
                        state.collections[entity.key].orEmpty()
                            .any { row -> dwRowId(entity.key, row.rowId) == key }
                    }
                }
                .distinct(),
        )
        draft.copy(stages = draft.stages + (stage.key to merged))
    }
}

// THE PUT BODY IS NO LONGER BUILT HERE, and the two functions that used to build it —
// `toSaveBody(StageDto, StageState)` and `stageSaveBodyFromDraft` — are gone rather than kept as a
// convenience. They were a second implementation of the wire format, and the two disagreed about the
// thing that matters most: this one sent an attachment's LOCAL id, because from inside a screen
// there is nothing to translate it against. The server resolves a stage's media field against
// `MediaFile.id`, so those references pointed at nothing and the report the ministry received had
// empty frames where the looms should have been. `buildStageBody` in data/WorkshopSync.kt is now the
// only place a stage becomes a payload, it substitutes the acknowledged remote id, and it refuses to
// send a stage whose photographs have not landed. Both callers go through
// [WorkshopSyncEngine.pushStage].

private fun computeStageCompletenessFor(
    stage: StageDto,
    state: StageState,
    /**
     * This workshop's custom definition, or null when this device holds none.
     *
     * PASSED RATHER THAN RESOLVED IN HERE, so the bar on screen counts exactly what the form on
     * screen is drawing. A helper that reached for the store itself could be one refresh ahead of
     * the composable that drew the boxes, and a progress bar that disagrees with the form above it
     * is the one number a designer stops believing.
     */
    definition: DwCustomCache? = null,
) =
    computeStageCompleteness(
        stage = stage,
        singleton = state.singleton,
        collections = state.collections.mapValues { (_, rows) -> rows.map { it.values } },
        customFields = customFieldsForStage(definition, stage.key),
        customValues = state.custom,
    )
