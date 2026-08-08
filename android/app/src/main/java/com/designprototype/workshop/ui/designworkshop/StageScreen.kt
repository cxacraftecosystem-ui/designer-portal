package com.designprototype.workshop.ui.designworkshop

import android.content.Context
import android.net.Uri
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.designprototype.workshop.data.DW_ROW_KEY_SEPARATOR
import com.designprototype.workshop.data.DraftMedia
import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.DwImageQuality
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
import com.designprototype.workshop.data.dwRowId
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.data.liveFields
import com.designprototype.workshop.data.rowTitleField
import com.designprototype.workshop.data.rowsFor
import com.designprototype.workshop.data.singleton
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 */

/**
 * How long after the last keystroke the draft is written.
 *
 * Long enough that a sentence is one write rather than forty, short enough that the work is on disk
 * before the designer can put the phone down and walk away — which, in a courtyard, is the moment the
 * process gets killed. 800ms is the same order the rest of this app uses for auto-save.
 */
private const val SAVE_DEBOUNCE_MS = 800L

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
)

/** What the status line under the header is currently able to promise. */
private enum class SaveState { CLEAN, PENDING, SAVING, ON_DEVICE, SYNCED }

@Composable
fun StageScreen(
    repository: WorkshopRepository,
    workshopId: String,
    stageKey: String,
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
     * Whether this device holds the SERVER's copy of the stage — see [StageDraft.serverBaseline].
     *
     * Carried in screen state as well as on disk because [persistLocally] writes a whole [StageDraft]
     * and would otherwise reset it to the class default on the first keystroke, silently disclaiming
     * an authority the screen had just earned by reading the server successfully.
     */
    var baseline by remember(stageKey) { mutableStateOf(false) }
    /**
     * Collections the designer has emptied in this session, unioned with whatever the draft already
     * recorded. See [StageDraft.emptiedEntities]: with no per-row delete endpoint this is the only
     * way deleting the LAST row of a collection ever reaches the server.
     */
    var emptied by remember(stageKey) { mutableStateOf<Set<String>>(emptySet()) }
    /** Set when the stage could not be downloaded, so the blank screen below is explained. */
    var downloadNote by remember(stageKey) { mutableStateOf<String?>(null) }

    // ── Load ─────────────────────────────────────────────────────────────────────────────────────
    LaunchedEffect(workshopId, stageKey) {
        loading = true
        runCatching {
            val schema = repository.designWorkshopSchema(appContext)
            val spec = schema.stages.firstOrNull { it.key == stageKey }
                ?: error("This build's field registry has no stage called $stageKey.")

            val draft = WorkshopDraftStore.load(appContext, workshopId)
            mediaIndex = draft?.media.orEmpty().associateBy { it.id }
            syncId = draft?.remoteId ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
            val local = draft?.stages?.get(stageKey)

            // THE LOCAL DRAFT WINS whenever it holds anything. The device is where the work is done
            // and the server copy can only ever be older or equal; overwriting a courtyard's worth of
            // typing with a two-day-old server snapshot because the screen happened to reopen with
            // signal is the single most expensive mistake this screen could make. The server is read
            // only to seed a stage this device has never opened.
            val remoteId = draft?.remoteId ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
            val loaded = if (local != null && (local.values.isNotEmpty() || local.rows.isNotEmpty())) {
                // A draft that already holds work keeps whatever baseline it was written with. It is
                // NOT promoted here: this branch does not read the server, so nothing has been
                // learned about what the server holds.
                StageLoad(fromDraft(spec, local), baseline = local.serverBaseline, downloadFailed = false)
            } else if (remoteId != null) {
                val remote = runCatching { repository.designWorkshopStage(remoteId, stageKey) }.getOrNull()
                if (remote != null) {
                    // The draft now starts from everything the server had, which is exactly the
                    // condition that entitles a later save to say "these are now exactly the rows".
                    StageLoad(fromRemote(spec, remote), baseline = true, downloadFailed = false)
                } else {
                    // ── THE READ FAILED, AND THAT IS NOT THE SAME THING AS AN EMPTY STAGE ────────
                    //
                    // This used to seed `StageState()` and say nothing, which is how a stage holding
                    // a fortnight of work — a 5-field singleton, 6 process steps, 5 tools, 4 raw
                    // materials — opened as a blank screen in a courtyard with no signal. One typed
                    // field then produced a payload with that field, zero rows for every collection
                    // and `replaceCollections = true`, and the server swept the lot.
                    //
                    // The blank screen still appears, because a designer with no signal must still be
                    // able to capture. What changes is that it is ANNOUNCED, and that the draft is
                    // marked as having no server baseline, so no save built from it can claim to be
                    // the whole truth of the stage. The work syncs; nothing it has not seen is
                    // destroyed.
                    StageLoad(StageState(), baseline = false, downloadFailed = true)
                }
            } else {
                // No server record at all. There is nothing on the server this draft could be missing,
                // so the device genuinely is the whole truth of this stage.
                StageLoad(StageState(), baseline = true, downloadFailed = false)
            }
            Triple(spec, loaded, local)
        }.onSuccess { (spec, loaded, local) ->
            stage = spec
            state = loaded.state
            baseline = loaded.baseline
            saveState = SaveState.CLEAN
            downloadNote = if (!loaded.downloadFailed) null else
                "This stage could not be downloaded — there is no connection, or the request " +
                    "failed. What you type here will be saved and sent, but anything already on " +
                    "the server for this stage is NOT shown below and will not be replaced by it."
            // Whatever the draft last recorded the designer emptying, carried forward so a deletion
            // made offline yesterday still reaches the server today.
            emptied = local?.emptiedEntities.orEmpty().toSet()
        }.onFailure { error ->
            onError(error.message ?: "Unable to open this stage.")
        }
        loading = false
    }

    // ── Debounced save ───────────────────────────────────────────────────────────────────────────
    LaunchedEffect(revision) {
        if (revision == 0) return@LaunchedEffect
        val spec = stage ?: return@LaunchedEffect
        saveState = SaveState.PENDING
        delay(SAVE_DEBOUNCE_MS)
        saveState = SaveState.SAVING
        val snapshot = state

        // The device first and unconditionally. Nothing below this line may prevent it.
        runCatching { persistLocally(appContext, workshopId, spec, snapshot, baseline, emptied) }
            .onFailure { error ->
                saveState = SaveState.PENDING
                onError(error.message ?: "Could not write this stage to the device.")
                return@LaunchedEffect
            }
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
        if (syncId == null) return@LaunchedEffect
        when (val push = runCatching {
            WorkshopSyncEngine.pushStage(appContext, repository, workshopId, spec)
        }.getOrElse { StagePush.NotSent }) {
            is StagePush.Sent -> {
                saveState = SaveState.SYNCED
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

    fun edit(transform: (StageState) -> StageState) {
        state = transform(state)
        revision++
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
                // separation is load-bearing: `removeMedia` reclaims space by deleting every file
                // under `media/` that no descriptor references, and a capture still on its way
                // through the camera has no descriptor yet. Sharing the directory would let one
                // attachment being deleted take an unrelated photograph with it.
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
    val completeness = remember(spec, state) {
        computeStageCompletenessFor(spec, state)
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        StageHeader(spec, completeness.percent, saveState, syncNote, localOnly = syncId == null)

        // SAID OUT LOUD, because the alternative is a blank stage that looks exactly like an empty
        // one. A designer who opens stage 5 in a courtyard with no signal and is shown nothing
        // concludes the stage was never filled in, and starts filling it in again — over a fortnight
        // of process steps, tools and raw materials that are sitting safely on the server and are
        // simply not on this handset. The save built from that screen is deliberately non-authoritative
        // (see [StageDraft.serverBaseline]) so nothing is destroyed, but silence would still have
        // cost the designer the afternoon.
        downloadNote?.let { note ->
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.warningContainer),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "This stage could not be downloaded",
                        color = MaterialTheme.field.onWarningContainer,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(note, color = MaterialTheme.field.onWarningContainer, fontSize = 11.sp)
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
                showAdvanced = showAdvanced,
                onToggleAdvanced = { showAdvanced = !showAdvanced },
                onValueChange = { key, value ->
                    edit { current -> current.copy(singleton = current.singleton.put(key, value)) }
                },
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

        spec.collections.forEach { entity ->
            CollectionSection(
                entity = entity,
                rows = state.collections[entity.key].orEmpty(),
                media = media,
                services = services,
                onRowsChange = { rows ->
                    // RECORDED THE MOMENT THE LAST ROW GOES, and only then. An emptied collection
                    // contributes no entries to a stage payload, so without this the deletion is
                    // invisible on the wire and the rows simply come back — into the .docx a ministry
                    // receives. The condition is "this screen was showing rows and now shows none",
                    // which is a thing the designer did, and not "the draft holds no rows", which is
                    // also true of a collection whose rows were entered on the web this morning.
                    val had = state.collections[entity.key].orEmpty().isNotEmpty()
                    emptied = when {
                        rows.isEmpty() && had -> emptied + entity.key
                        rows.isNotEmpty() -> emptied - entity.key
                        else -> emptied
                    }
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
        OutlinedButton(
            onClick = { revision++ },
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
                SaveState.SYNCED -> "$percent% complete · saved and synced"
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
@Composable
private fun EntitySection(
    entity: EntityDto,
    values: Map<String, JsonElement>,
    media: DwMediaBridge,
    services: DwFieldServices,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    onValueChange: (String, JsonElement?) -> Unit,
    /** Hydration from a reference pick: several keys, one write. See `putAll` and [FieldRenderer]. */
    onPatch: (Map<String, JsonElement?>) -> Unit,
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

    /**
     * "2 of 3 views captured — no back view", for the one entity in the registry that really has
     * named view slots (stage 6's `existingProduct`). Every other entity gets nothing at all: asking
     * for a "back view" on a form that has no such field is worse than saying nothing, because the
     * designer cannot act on it. See [DwImageQuality.NAMED_VIEW_SLOTS].
     */
    val missingViews = remember(entity.key, values) { DwImageQuality.findMissingViews(entity.key, values) }

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
    onRowsChange: (List<CollectionRow>) -> Unit,
) {
    var expanded by remember(entity.key) { mutableStateOf<String?>(null) }
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
                }
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
    onToggle: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onValueChange: (String, JsonElement?) -> Unit,
    onPatch: (Map<String, JsonElement?>) -> Unit,
) {
    var showAdvanced by remember(row.rowId) { mutableStateOf(false) }

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
                    showAdvanced = showAdvanced,
                    onToggleAdvanced = { showAdvanced = !showAdvanced },
                    onValueChange = onValueChange,
                    onPatch = onPatch,
                    resetKey = row.rowId,
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
    /** True when this device holds the server's copy, or the server has no copy to hold. */
    val baseline: Boolean,
    /** True when a server read was attempted and failed — the screen says so. */
    val downloadFailed: Boolean,
)

private fun fromDraft(stage: StageDto, draft: StageDraft): StageState = StageState(
    singleton = draft.values,
    collections = stage.collections.associate { entity ->
        entity.key to draft.rowsFor(entity.key).map { row ->
            CollectionRow(
                rowId = row.id.substringAfter(DW_ROW_KEY_SEPARATOR),
                values = row.values,
            )
        }
    }
)

private fun fromRemote(stage: StageDto, bucket: StageBucketDto): StageState = StageState(
    singleton = bucket.singleton.toMap(),
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
    /** See [StageDraft.serverBaseline]. Passed in, because this function rebuilds the whole record. */
    baseline: Boolean,
    /** See [StageDraft.emptiedEntities]. */
    emptied: Set<String>,
) {
    WorkshopDraftStore.update(context, workshopId) { draft ->
        val existing = draft.stages[stage.key]
        val merged = StageDraft(
            stageId = stage.key,
            title = stage.title,
            order = stage.number,
            values = state.singleton,
            rows = stage.collections.flatMap { entity ->
                state.collections[entity.key].orEmpty().map { row ->
                    DraftRow(id = dwRowId(entity.key, row.rowId), values = row.values)
                }
            },
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
            // defaults on every debounced save — quietly disclaiming a baseline the screen had just
            // established by reading the server, and quietly discarding a deletion the designer made
            // ten seconds ago. OR-ing with what is already on disk is what makes them cumulative
            // rather than whatever the last frame happened to hold.
            serverBaseline = baseline || existing?.serverBaseline == true,
            emptiedEntities = (emptied + existing?.emptiedEntities.orEmpty())
                .filter { key -> state.collections[key].orEmpty().isEmpty() }
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

private fun computeStageCompletenessFor(stage: StageDto, state: StageState) =
    computeStageCompleteness(
        stage = stage,
        singleton = state.singleton,
        collections = state.collections.mapValues { (_, rows) -> rows.map { it.values } },
    )
