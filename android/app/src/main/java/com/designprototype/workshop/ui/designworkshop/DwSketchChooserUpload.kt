package com.designprototype.workshop.ui.designworkshop

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DraftMedia
import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.WorkshopSyncEngine
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.dwFoldServerStage
import com.designprototype.workshop.data.dwStageKeyForEntity
import com.designprototype.workshop.data.isConnectionFailure
import com.designprototype.workshop.data.liveFields
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.util.UUID

/**
 * The UPLOAD tab: pick which sketch or prototype, add another, and attach its files — from here.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THIS IS THE CAPABILITY THE CHOOSER'S OWN HEADER USED TO REFUSE, AND THE REFUSAL STILL HOLDS
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `SketchesAndPrototypesScreen`'s KDoc argued for a long time that a screen here which let a designer
 * add a sketch "would be one feature with two stores, and the one it wrote to would be the one the
 * report did not read". That sentence is exactly right and it is not what this file does. Read what
 * it forbids: a SECOND STORE. The web reached the same conclusion first and resolved it the same way
 * — `frontend/components/sketches/UploadTabHost.tsx` invents no store at all; it picks an EXISTING
 * row of the stage's own collection and writes through `putDraftStage`, which is the identical
 * function the stage form calls. `docs/SKETCHES-PROTOTYPES-PARITY.md` says so in as many words:
 * *"the web's upload tab does not add one either … so the web is not the thing this comment
 * forbids."*
 *
 * So every write in this file goes to ONE place, and it is the place the stage screen writes and the
 * report reads:
 *
 *   * the row is a [DraftRow] in `StageDraft.rows`, filed under `dwRowId(entityKey, uuid)` —
 *     the id shape `buildStageBody` splits back into the `_clientKey` the server matches on;
 *   * it is written with `WorkshopDraftStore.updateStage`, whose own KDoc reads *"This is what a
 *     stage screen should call"*;
 *   * the photograph is copied by `WorkshopDraftStore.importMedia` into the workshop's own media
 *     directory, exactly as `StageScreen`'s bridge does, so it inherits the retry, the resumption,
 *     the orphan sweep and the offline durability with no upload code here;
 *   * and it goes up through `WorkshopSyncEngine.pushStage`, which is the one place a stage becomes
 *     a payload.
 *
 * **THERE IS NO SECOND STORE, NO PARALLEL COLLECTION AND NO NEW ENDPOINT.** A sketch added here is
 * the same row, in the same draft, under the same stage, that a designer would have created by
 * walking to stage 11 — which is why the report reads it.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY A DRAFT IS ONLY EVER SEEDED AFTER THE REPOSITORY HAS ANSWERED
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `WorkshopDraftStore.update` is a check-AND-CREATE: handed a workshop id it holds nothing for, it
 * mints a draft record. The web's `stageRows.ts` carries the defect that follows at length — its
 * `ensureDraft` was called unconditionally, so a blank session-owned draft appeared for whatever
 * workshop id was in the URL, and the workshops list prepends exactly such drafts to the device's own
 * list whenever it is offline. **A stranger's workshop appeared as a blank row on a designer's list
 * because they opened a review page.**
 *
 * The handset has the same hazard — `WorkshopListScreen` falls back to `WorkshopDraftStore.list` with
 * "Showing what is stored on this device" — and this file is stricter than the web about it. The read
 * below uses `WorkshopDraftStore.load`, which creates NOTHING. A draft record is written only in two
 * places, both of which are a decision that has already been checked:
 *
 *  1. after `designWorkshopStage` has ANSWERED for this workshop, which is `load_workshop_or_404`
 *     saying yes about this exact record; and
 *  2. when the designer presses "Add a sketch" or "Add a prototype", which is a person asking.
 *
 * So a chooser opened with no signal, or on a workshop this account cannot open, leaves the device
 * exactly as it found it.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THE SERVER'S COPY IS FOLDED IN, AND WHAT IT EARNS
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The picker has to offer the rows that EXIST, not the rows this handset happens to hold — a designer
 * whose colleague filed eight sketches on the web must see eight. So the stage is read and folded
 * with `dwFoldServerStage`, the same pure function the stage screen's load uses, which honours a
 * deletion this device is holding rather than resurrecting it.
 *
 * That fold sets `StageDraft.stageSeen`, and it is the whole reason it must not be skipped. That flag
 * is what `buildStageBody` reads as `merge = !authoritative`: a stage this device has NOT read goes
 * up as a merge, so the repository keeps every row the payload does not name. Without the fold, one
 * sketch added from this chooser would be the only row in the payload — and once anything later
 * earned authority, the sweep would take the other seven. With the fold, the payload names them all.
 * `DW_SKETCH_CHOOSER_STALE` is what the screen says while that read has not landed.
 */
@Composable
internal fun DwSketchChooserUploadTab(
    repository: WorkshopRepository,
    /** The workshop the chooser above has scoped this screen to. Never blank when this is composed. */
    workshopId: String,
    /** Open the stage that owns a piece — the handset's "Open the record". */
    onOpenStage: (workshopId: String, stageKey: String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    var sketch by remember(workshopId) { mutableStateOf<DwChooserHalf?>(null) }
    var prototype by remember(workshopId) { mutableStateOf<DwChooserHalf?>(null) }
    var sketchRow by remember(workshopId) { mutableStateOf("") }
    var prototypeRow by remember(workshopId) { mutableStateOf("") }
    /** Every attachment this workshop holds, by id — what the capture card resolves against. */
    var mediaIndex by remember(workshopId) { mutableStateOf<Map<String, DraftMedia>>(emptyMap()) }
    var loading by remember(workshopId) { mutableStateOf(true) }
    var notice by remember(workshopId) { mutableStateOf<String?>(null) }
    var problem by remember(workshopId) { mutableStateOf<String?>(null) }
    /** Bumped by a write or by "Try again"; re-runs the read below. */
    var attempt by remember(workshopId) { mutableIntStateOf(0) }

    /**
     * The screening desk both halves share.
     *
     * KEYED ON THE WORKSHOP AND NOT ON THE FIELD, for the reason `StageScreen` gives at its own
     * `screeningStore`: a store constructed inside the bridge would be thrown away and replaced on
     * the very import that caused it, taking the in-flight measurement and its refusal notice with
     * it. It outlives every card on this tab.
     */
    val screeningStore = remember(workshopId) { DwScreeningStore(scope) }

    /**
     * Read both halves: the registry, then this device, then the repository.
     *
     * `designWorkshopSchema` NEVER THROWS on a network failure — it answers from filesDir and then
     * from the copy built into the APK — so the two stage keys can be named on a phone with no
     * signal at all. Only the repository read below can fail, and it fails per half.
     */
    suspend fun read() {
        val schema = runCatching { repository.designWorkshopSchema(appContext) }.getOrNull()
        val draft = runCatching { WorkshopDraftStore.load(appContext, workshopId) }.getOrNull()
        mediaIndex = draft?.media.orEmpty().associateBy { it.id }

        suspend fun half(entityKey: String): DwChooserHalf {
            val stageKey = schema?.let { dwStageKeyForEntity(it, entityKey) }
            val spec = schema?.stages?.firstOrNull { it.key == stageKey }
            if (stageKey == null || spec == null) {
                return DwChooserHalf(entityKey = entityKey, stageKey = null, spec = null)
            }
            val local = draft?.stages?.get(stageKey)
            /*
              THE REPOSITORY'S COPY, FOLDED — see the header for what this earns and what skipping it
              would cost. A failure here is NOT fatal and is NOT silent: the disk answer stands, the
              half reports `reconciled = false`, and the screen prints DW_SKETCH_CHOOSER_STALE over
              the controls that would otherwise write over rows this device has never read.
            */
            return runCatching { repository.designWorkshopStage(workshopId, stageKey) }
                .fold(
                    onSuccess = { bucket ->
                        val folded = dwFoldServerStage(spec, local, bucket, stageKey)
                        // THE ONLY SEEDING WRITE ON THE READ PATH, and it happens after the
                        // repository has said yes about this exact record. See the header.
                        val saved = runCatching {
                            WorkshopDraftStore.updateStage(appContext, workshopId, folded.draft)
                        }.getOrNull()
                        if (saved != null) mediaIndex = saved.media.associateBy { it.id }
                        DwChooserHalf(
                            entityKey = entityKey,
                            stageKey = stageKey,
                            spec = spec,
                            stage = folded.draft,
                            reconciled = true,
                        )
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        DwChooserHalf(
                            entityKey = entityKey,
                            stageKey = stageKey,
                            spec = spec,
                            stage = local,
                            reconciled = false,
                            // The repository's own words where it gave any: a 403 and a 500 need
                            // different things from the reader and only the server knows which.
                            failure = if (repository.isConnectionFailure(error)) {
                                DW_SKETCH_CHOOSER_STAGE_OFFLINE
                            } else {
                                error.apiErrorMessage(DW_SKETCH_CHOOSER_STAGE_REFUSED)
                            },
                        )
                    },
                )
        }

        sketch = half(DW_CHOOSER_SKETCH_ENTITY)
        prototype = half(DW_CHOOSER_PROTOTYPE_ENTITY)
        // THE SELECTION IS ONLY EVER FILLED IN, NEVER MOVED. A read that re-runs while the designer
        // is halfway through attaching a photograph to row four must not put them back on row one.
        sketchRow = dwChooserKeepSelection(sketchRow, sketch?.rows.orEmpty())
        prototypeRow = dwChooserKeepSelection(prototypeRow, prototype?.rows.orEmpty())
    }

    LaunchedEffect(workshopId, attempt) {
        loading = true
        problem = null
        runCatching { read() }
            .onFailure { error ->
                // Leaving the screen is not a failure, and neither is a workshop change: both arrive
                // here as a CancellationException. Rethrown, as every load on this client does, so a
                // dead composable never writes state and a cancelled run cannot raise a refusal over
                // the read that replaced it.
                if (error is CancellationException) throw error
                problem = error.apiErrorMessage(DW_SKETCH_CHOOSER_STAGE_REFUSED)
            }
        loading = false
    }

    /**
     * The bridge every capture card on this tab shares — the stage screen's, built the same way.
     *
     * `newCaptureFile` writes into the workshop's own `captures/` directory under `filesDir` and NOT
     * into `cacheDir`, which is the whole argument on `DwMediaBridge.newCaptureFile`: Android reclaims
     * cacheDir under storage pressure, silently and with no callback, and preferentially when the
     * disk is tight — which on a 32 GB field phone two weeks into a study is always. A camera intent
     * writing there can have its output deleted between the shutter and the import.
     */
    val media = remember(workshopId, screeningStore) {
        DwMediaBridge(
            workshopId = workshopId,
            screening = screeningStore,
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
                        remoteMediaId = descriptor.remoteMediaId,
                    )
                }
            },
            attach = { uris: List<Uri>, field: FieldDto, onAttached: (List<String>) -> Unit ->
                // ONE coroutine for the whole selection, importing IN ORDER and reporting the ids
                // once. `StageScreen`'s bridge records what a coroutine per Uri cost: the completion
                // callbacks raced and overwrote each other's appends, so a five-photo pick kept one
                // and orphaned four — imported, referenced by nothing, undeletable.
                scope.launch {
                    val imported = mutableListOf<String>()
                    val added = mutableMapOf<String, DraftMedia>()
                    var failures = 0
                    for (uri in uris) {
                        runCatching {
                            WorkshopDraftStore.importMedia(
                                context = appContext,
                                workshopId = workshopId,
                                uri = uri,
                                // The stage and the field are stamped on the descriptor so the sync
                                // pass and the report writer can find the photograph's home without
                                // walking every stage. Which STAGE depends on which card called, and
                                // the field carries it: both halves' fields are unique across the two
                                // stages, so the lookup below cannot pick the wrong one.
                                stageId = dwChooserStageForField(field.key, sketch, prototype),
                                fieldKey = field.key,
                            )
                        }.onSuccess { descriptor ->
                            added[descriptor.id] = descriptor
                            imported += descriptor.id
                        }.onFailure { failures++ }
                    }
                    if (added.isNotEmpty()) mediaIndex = mediaIndex + added
                    if (imported.isNotEmpty()) onAttached(imported)
                    if (failures > 0) {
                        problem = if (imported.isEmpty()) {
                            "That file could not be attached."
                        } else {
                            "$failures of ${uris.size} files could not be attached."
                        }
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
                val dir = File(
                    WorkshopDraftStore.workshopDir(appContext, workshopId), "captures"
                ).apply { mkdirs() }
                File(dir, "capture-${UUID.randomUUID()}$suffix")
            },
        )
    }

    /**
     * Add one row to a half's collection, then offer the stage.
     *
     * THE WRITE AND THE PUSH ARE TWO DIFFERENT PROMISES and the note says which one landed —
     * `dwChooserSaveNote` turns the five `StagePush` answers into five sentences, because "saved" is
     * true of all of them and useful for none. The row is durable on this phone the moment
     * `updateStage` returns; whether the repository has it depends on a connection this courtyard may
     * not have.
     */
    fun addRow(half: DwChooserHalf, select: (String) -> Unit) {
        val spec = half.spec ?: return
        val stageKey = half.stageKey ?: return
        scope.launch {
            notice = null
            problem = null
            val row = dwChooserNewRow(half.entityKey, UUID.randomUUID().toString())
            val written = runCatching {
                val draft = WorkshopDraftStore.load(appContext, workshopId)
                val next = dwChooserAppendRow(spec, draft?.stages?.get(stageKey), row)
                WorkshopDraftStore.updateStage(appContext, workshopId, next)
            }
            written.onFailure { error ->
                if (error is CancellationException) throw error
                problem = DW_SKETCH_CHOOSER_ROW_NOT_ADDED
                return@launch
            }
            /*
              THE SCREEN CATCHES UP BEFORE THE PUSH IS EVEN ATTEMPTED, and the order is deliberate
              rather than incidental. `pushStage` can sit on `ApiClient`'s 30-second connect timeout
              in a courtyard; a designer who pressed "Add a sketch" and then watched nothing happen
              for half a minute presses it again, and the second press is a second real row in a real
              record. So the row is selected and the re-read is scheduled the instant it is DURABLE,
              which it is the moment `updateStage` returns, and the sentence below is upgraded when
              the repository answers. `DesignReviewRound` splits its arrangement note the same way.
            */
            select(dwChooserRowKey(row))
            notice = dwChooserSendingNote(half.what)
            attempt++
            val push = runCatching {
                WorkshopSyncEngine.pushStage(
                    context = appContext,
                    repository = repository,
                    workshopId = workshopId,
                    spec = spec,
                )
            }.getOrNull()
            notice = dwChooserSaveNote(push, half.what)
        }
    }

    /** Write one field's media ids into the chosen row, then offer the stage. */
    fun writeMedia(half: DwChooserHalf, rowKey: String, field: FieldDto, ids: List<String>) {
        val spec = half.spec ?: return
        val stageKey = half.stageKey ?: return
        scope.launch {
            notice = null
            problem = null
            val written = runCatching {
                val draft = WorkshopDraftStore.load(appContext, workshopId)
                val stage = draft?.stages?.get(stageKey) ?: return@runCatching null
                val rows = dwChooserWriteMedia(
                    rows = dwChooserRows(stage, half.entityKey),
                    rowKey = rowKey,
                    fieldKey = field.key,
                    ids = ids,
                    // SINGLE OR LIST OFF THE REGISTRY, never off the count. A one-element array in a
                    // single-file field and a bare string in an IMAGE_LIST are both refused by
                    // `coerce_value`, silently on this device and visibly in a document a ministry
                    // receives a fortnight later.
                    asList = DwFieldType.of(field.type) == DwFieldType.IMAGE_LIST,
                )
                WorkshopDraftStore.updateStage(
                    appContext,
                    workshopId,
                    dwChooserReplaceRows(stage, half.entityKey, rows),
                )
            }
            written.onFailure { error ->
                if (error is CancellationException) throw error
                problem = DW_SKETCH_CHOOSER_FILE_NOT_ATTACHED
                return@launch
            }
            if (written.getOrNull() == null) {
                // No stage on disk to write into. Reachable when a fold failed and the designer has
                // never opened the stage: the cards should not have been enabled, and saying so is
                // better than a silent no-op.
                problem = DW_SKETCH_CHOOSER_FILE_NOT_ATTACHED
                return@launch
            }
            // THE ATTACHMENT IS ON SCREEN BEFORE THE PUSH IS ATTEMPTED — see the note in `addRow`.
            // Here the cost of the other order is worse than a duplicate row: the card reads its
            // `ids` off the reloaded draft, so a photograph would appear to VANISH for as long as a
            // metered PUT takes to time out.
            notice = dwChooserSendingNote("This file")
            attempt++
            val push = runCatching {
                WorkshopSyncEngine.pushStage(
                    context = appContext,
                    repository = repository,
                    workshopId = workshopId,
                    spec = spec,
                )
            }.getOrNull()
            notice = dwChooserSaveNote(push, "This file")
        }
    }

    /**
     * Write one measured dimension into the chosen row, then offer the stage.
     *
     * ── THE THIRD DOOR, AND IT GOES THROUGH THE SAME WALL AS THE OTHER TWO ────────────────────
     *
     * This tab had exactly two writes — [addRow] and [writeMedia] — and both move media or mint a
     * row. A measurement is neither, which is why "measure a dimension from a photograph" could not
     * be offered here at all before this: the panel's one output is `onPropose(key, value, technique)`
     * and there was nothing to hand it to.
     *
     * It is written the same way as everything else on this tab and that is the whole of its safety
     * argument: `WorkshopDraftStore.load` → the row → `WorkshopDraftStore.updateStage` →
     * `WorkshopSyncEngine.pushStage`. **NO SECOND STORE, NO PARALLEL COLLECTION AND NO NEW
     * ENDPOINT** — the file header's rule, and a dimension filed from here is the same value in the
     * same row of the same draft that a designer would have typed on stage 11.
     *
     * ── THE TECHNIQUE IS DROPPED HERE, AND THE STAGE FORM DROPS IT TOO ────────────────────────
     *
     * `DwPhotoMeasurePanel`'s third argument says WHICH GEOMETRY produced the number. A stage field's
     * provenance is written by `entry_provenance.merge_entry_provenance`, which has no
     * `measurementMethods` key to put it under — that mechanism reaches RECORDS only. `FieldRenderer`
     * drops it at its own mount for exactly this reason and says so; this is the same seam, and when
     * the stage half lands it is the same one line in both places.
     */
    fun writeScalar(half: DwChooserHalf, rowKey: String, fieldKey: String, value: JsonElement?) {
        val spec = half.spec ?: return
        val stageKey = half.stageKey ?: return
        scope.launch {
            notice = null
            problem = null
            val written = runCatching {
                val draft = WorkshopDraftStore.load(appContext, workshopId)
                val stage = draft?.stages?.get(stageKey) ?: return@runCatching null
                val rows = dwChooserWriteScalar(
                    rows = dwChooserRows(stage, half.entityKey),
                    rowKey = rowKey,
                    fieldKey = fieldKey,
                    value = value,
                )
                WorkshopDraftStore.updateStage(
                    appContext,
                    workshopId,
                    dwChooserReplaceRows(stage, half.entityKey, rows),
                )
            }
            written.onFailure { error ->
                if (error is CancellationException) throw error
                problem = DW_SKETCH_CHOOSER_MEASUREMENT_NOT_SAVED
                return@launch
            }
            if (written.getOrNull() == null) {
                // No stage on disk to write into — reachable when a fold failed and the designer has
                // never opened the stage. The panel should not have been enabled, and saying so is
                // better than a button that appears to do nothing to a number somebody just measured.
                problem = DW_SKETCH_CHOOSER_MEASUREMENT_NOT_SAVED
                return@launch
            }
            // ON SCREEN BEFORE THE PUSH IS ATTEMPTED — see the note in `addRow`. The measuring card
            // reads what the field already holds off the reloaded row, so the other order would make
            // a proposed dimension appear to VANISH for as long as a metered PUT takes to time out.
            notice = dwChooserSendingNote("This measurement")
            attempt++
            val push = runCatching {
                WorkshopSyncEngine.pushStage(
                    context = appContext,
                    repository = repository,
                    workshopId = workshopId,
                    spec = spec,
                )
            }.getOrNull()
            notice = dwChooserSaveNote(push, "This measurement")
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        /*
          THE STATUS OF THE TAB — ONE NODE, ALWAYS COMPOSED, WITH A LIVE REGION AROUND IT.

          Composed whether or not there is anything in it, exactly as the workshop list's status
          region above is and for the identical reason: assistive technology announces a CHANGE
          inside a region that ALREADY EXISTED, so a region created in the same frame as its first
          sentence is a region whose first sentence is never announced. `mergeDescendants` is what
          makes it work — this node has no text of its own, so merged, the child's text IS its text.
        */
        Box(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            }
        ) {
            when {
                problem != null -> Text(
                    problem.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )

                loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Text(
                        "Reading this workshop's sketches and prototypes…",
                        color = MaterialTheme.field.muted,
                        fontSize = 13.sp,
                    )
                }

                notice != null -> Text(
                    notice.orEmpty(),
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )

                else -> Unit
            }
        }

        DwChooserUploadHalf(
            half = sketch,
            icon = Icons.Filled.Brush,
            fieldKeys = DW_CHOOSER_SKETCH_FIELDS,
            chosenRow = sketchRow,
            onChooseRow = { sketchRow = it },
            onAdd = { half -> addRow(half) { sketchRow = it } },
            onWriteMedia = { half, rowKey, field, ids -> writeMedia(half, rowKey, field, ids) },
            onWriteScalar = { half, rowKey, key, value -> writeScalar(half, rowKey, key, value) },
            onOpenStage = onOpenStage,
            workshopId = workshopId,
            repository = repository,
            media = media,
            onMessage = { notice = it },
            onProblem = { problem = it },
        )

        DwChooserUploadHalf(
            half = prototype,
            icon = Icons.Filled.Category,
            fieldKeys = DW_CHOOSER_PROTOTYPE_FIELDS,
            chosenRow = prototypeRow,
            onChooseRow = { prototypeRow = it },
            onAdd = { half -> addRow(half) { prototypeRow = it } },
            onWriteMedia = { half, rowKey, field, ids -> writeMedia(half, rowKey, field, ids) },
            onWriteScalar = { half, rowKey, key, value -> writeScalar(half, rowKey, key, value) },
            onOpenStage = onOpenStage,
            workshopId = workshopId,
            repository = repository,
            media = media,
            onMessage = { notice = it },
            onProblem = { problem = it },
        )
    }
}

/**
 * One half of the tab — the sketches, or the prototypes.
 *
 * TWO CALLS OF ONE COMPOSABLE AND NOT TWO COMPOSABLES, because the two halves differ in exactly four
 * values (the entity, the icon, the fields, and the words) and everything else about them — the
 * picker, the add button, the staleness sentence, the capture cards, the "open the record" escape —
 * is one behaviour that must not come to be two. The web makes the same choice for the same reason;
 * where it forked, `UploadTabHost`'s own header records the cost.
 */
@Composable
private fun DwChooserUploadHalf(
    half: DwChooserHalf?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    fieldKeys: List<String>,
    chosenRow: String,
    onChooseRow: (String) -> Unit,
    onAdd: (DwChooserHalf) -> Unit,
    onWriteMedia: (DwChooserHalf, String, FieldDto, List<String>) -> Unit,
    /** Write one measured dimension into the chosen row. See `writeScalar` at the call site. */
    onWriteScalar: (DwChooserHalf, String, String, JsonElement?) -> Unit,
    onOpenStage: (String, String) -> Unit,
    workshopId: String,
    /**
     * The repository, for the tracing panel's export card and nothing else on this half.
     *
     * A HOST THAT CANNOT OFFER FILE SAVING PASSES NOTHING AND LOSES NOTHING ELSE, which is
     * [DwTraceExportSlot]'s own contract — but this host CAN, and `FieldRenderer.kt`'s call site
     * records at length what leaving that slot empty cost the one mount that had it: five finished,
     * tested surfaces (the five-format table, the route to the public Downloads folder, the share
     * sheet, the per-format losses, and the one `DwTraceTier.EXPORT` control) reachable from nothing,
     * behind a defaulted argument and a comment that read as a decision. This tab is not going to
     * repeat it.
     */
    repository: WorkshopRepository,
    media: DwMediaBridge,
    onMessage: (String) -> Unit,
    onProblem: (String) -> Unit,
) {
    if (half == null) return
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                half.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
        }

        // THE REGISTRY DID NOT DECLARE THIS ENTITY AT ALL. A schema mismatch rather than a
        // permission, and the sentence says so and names the surface that renders whatever the
        // registry DOES declare — which is the web's answer, in the web's shape.
        //
        // PULLED OUT INTO LOCALS RATHER THAN LEANING ON A SMART CAST of `half.stageKey` below: the
        // cast is legal today, and it is legal only because `DwChooserHalf` is a data class in this
        // module with a default getter. Somebody giving that property a custom getter would turn a
        // compile error here into… nothing, because the compiler would simply stop offering the
        // cast, and the next reader would have to work out why.
        val stageKey = half.stageKey
        val spec = half.spec
        if (stageKey == null || spec == null) {
            Text(
                DW_SKETCH_CHOOSER_NO_STAGE,
                color = MaterialTheme.field.muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            return@Column
        }

        // THE READ THAT DID NOT LAND, SAID BEFORE ANYTHING BELOW IT IS OFFERED. Rule 5: a failure and
        // an empty answer are different states with different sentences, and a list of rows drawn
        // under nothing at all would be read as this workshop's whole collection.
        half.failure?.let { sentence ->
            Text(
                sentence,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Text(
                DW_SKETCH_CHOOSER_STALE,
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        val rows = half.rows
        if (rows.isEmpty()) {
            // ANSWERED, AND THE ANSWER IS NONE — an ordinary state for a workshop that has not got to
            // this stage, and worded as such. Only reachable as an ANSWER because the failure above
            // is printed first and separately; a failed read draws its own sentence and this one too,
            // in that order, so the two can never be mistaken for each other.
            Text(
                half.emptyNote,
                color = MaterialTheme.field.muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        } else {
            SearchableSelectField(
                label = half.pickerLabel,
                options = rows.mapIndexed { index, row ->
                    SelectOption(
                        value = dwChooserRowKey(row),
                        label = dwChooserRowLabel(row, index),
                        // The position rides in the hint, which this picker SEARCHES as well as
                        // shows, so "3" finds the third row on a workshop that named none of them.
                        hint = "Row ${index + 1} of ${rows.size}",
                    )
                },
                selectedValue = chosenRow,
                placeholder = half.pickerPlaceholder,
                // No "none" row: emptying the picker would leave the cards below with nowhere to
                // write and a control implying that is a state worth choosing.
                includeNone = false,
                onSelect = onChooseRow,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { onAdd(half) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(half.addLabel, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(
                onClick = { onOpenStage(workshopId, stageKey) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    Icons.Filled.OpenInFull,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text("Open the stage", fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }
        // THE ESCAPE IS NAMED RATHER THAN IMPLIED. Everything this tab cannot do is on that form, and
        // a designer who is not told that will look for it here.
        //
        // AND WHAT IT CAN DO IS NO LONGER IN THAT LIST — see [DwChooserHalf.stageNote]. This comment
        // named "straightening the plate" as an escape while the derivation section below composes
        // the straightening panel on this very screen.
        Text(
            half.stageNote,
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        // THE WHOLE ENTITY, once — the capture cards narrow it to four keys below, and the
        // derivation section needs every field it declares. Two lookups of the same collection would
        // be two answers to "what does this entity declare" a rename could put out of step.
        val entity = dwChooserEntity(spec, half.entityKey)
        val fields = dwChooserMediaFields(entity, fieldKeys)
        val chosen = rows.firstOrNull { dwChooserRowKey(it) == chosenRow }
        /*
          SAID IMMEDIATELY ABOVE THE CARDS THEY ARE ABOUT, AND NOT AT THE TOP OF THE SCREEN.

          These two paragraphs used to be printed over the whole chooser, on the argument that a
          designer must read them BEFORE the upload rather than after it. That argument is unchanged
          and is better served here: this is the last thing between a designer and the “3D model”
          field itself, which is exactly where the web prints the identical wording
          (`frontend/components/sketches/upload/PrototypeModelField.tsx` renders it on the turntable
          card). The full argument, and the three backend files that are the authority for every
          clause of it, are at the declaration of [DW_PROTOTYPE_3D_IN_THE_REPORT].
        */
        if (fields.isNotEmpty()) {
            half.mediaNotes.forEachIndexed { index, note ->
                Text(
                    note,
                    color = MaterialTheme.field.muted,
                    // The FACT and the ADVICE are two different kinds of statement and are sized
                    // apart deliberately — see the two constants' own KDocs for why they are two
                    // constants rather than one paragraph.
                    fontSize = if (index == 0) 12.sp else 11.sp,
                    lineHeight = if (index == 0) 17.sp else 16.sp,
                )
            }
        }
        when {
            fields.isEmpty() -> Text(
                DW_SKETCH_CHOOSER_NO_MEDIA_FIELDS,
                color = MaterialTheme.field.muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )

            chosen == null -> Unit

            else -> fields.forEach { field ->
                DwMediaCaptureCard(
                    field = field,
                    type = DwFieldType.of(field.type),
                    ids = dwChooserHeldMedia(chosen, field.key),
                    media = media,
                    // THE SLOT IS THE ROW AND THE FIELD, never the field alone. Collection rows share
                    // composable slots, so a field key on its own would give every sketch in the
                    // workshop one screening queue between them and report row 2's refused photograph
                    // under row 7 — which is exactly what `DwMediaCaptureCard.slotKey` is documented
                    // to prevent.
                    slotKey = "${half.entityKey}:$chosenRow:${field.key}",
                    // REFUSED WHILE THE REPOSITORY'S COPY HAS NOT BEEN READ. Writing a row back over
                    // a collection this device has not reconciled is how a stage loses rows nobody
                    // deleted; the sentence above the cards says which read is missing.
                    enabled = half.reconciled,
                    onIdsChange = { ids -> onWriteMedia(half, chosenRow, field, ids) },
                    onMessage = onMessage,
                    onError = onProblem,
                )
            }
        }

        /*
          ── THE DERIVATION CARDS, UNDER ONE PHOTOGRAPH — requirements 5, 6, 7, 18 and 20 ─────────

          THE CAPABILITY EXISTED AND THIS TAB COULD NOT REACH IT. "Trace a sketch into line art",
          "Straighten a photographed sketch" and "Measure a dimension from a
          photograph" are all built, all tested, and were mounted at exactly ONE place in the app —
          `FieldRenderer`, inside the stage form, one tap deeper than this screen — while the web
          puts every one of them on this tab. `RECON_FINDINGS.md` section 9 records the grep:
          `TracePanel|RectifyPanel|MeasurePanel|dwOffers` across the four chooser files returned
          nothing at all. This repository's own rule about a finished surface that is reachable from
          nothing is the argument, and `FieldRenderer.kt` states it about this very feature.

          AND THIS IS WHERE "ONE PHOTOGRAPH FEEDS BOTH CARDS" IS A SENTENCE THAT MEANS SOMETHING.
          In the stage form the cards sit on two DIFFERENT fields — measuring on the image, tracing
          and straightening on the file that receives the plate — and are composed from two different
          field renders, so no composable above them owns both. Here they are siblings under one
          section, which is exactly the shape `UploadTabHost.tsx` reached on the other client, and
          [DwSketchDerivationSection] is the owner. Everything about which photograph, what a change
          of photograph resets, and what is decoded is in that file's header.

          THE FIELDS COME FROM THE WHOLE ENTITY AND NOT FROM THIS TAB'S FOUR KEYS.
          `dwChooserMediaFields` narrows the entity to the media fields this tab draws CAPTURE CARDS
          for, which is a different question from which fields a derivation may READ or PROPOSE INTO:
          a prototype's dimensions are `lengthCm`/`widthCm`/`heightCm`/`diameterCm` and none of them
          is a media field at all. So the offers are asked of the entity's own `liveFields`, through
          the same two predicates the stage form uses — nothing here decides anything a second time.

          ── AND THIS IS ONE COMPOSABLE FOR BOTH HALVES, INCLUDING PROTOTYPES — requirement 7 ────

          THE PROTOTYPES HALF GETS THE SAME CALL, AND COMES OUT OF IT WITH A DIFFERENT SCREEN,
          BECAUSE THE REGISTRY SAYS SO RATHER THAN BECAUSE THIS FILE DOES. `dwOffersSketchRectify`
          refuses both FILE fields a prototype declares — "Measurement sheet" and "3D model" are not
          the home of a plate — so `plateField` is null on that half and the straightening and tracing
          panels are simply not offered. `dwOffersPhotoMeasure` answers true, so the measuring card
          is. One card, therefore no shared photograph card: [dwSharesOnePhotograph] is where that is
          decided and argued, and `UploadTabPanel.tsx:391-407` is the other client reaching the same
          answer for the same reason.

          THIS HALF IS WHERE PROTOTYPES ACTUALLY GAIN SOMETHING, AND IT IS NOT SYMMETRY.
          `dwChooserDerivationSources` spans EVERY image field the entity declares, so on a prototype
          the one measuring card here reads `prototypePhotos` and `turntablePhotos` together. The
          stage form mounts one measuring card PER image field, each able to see only its own — so a
          designer who shot the frame with the ruler in it into the turn, and the clean frames into
          the photographs, has the picture they need on one card and the dimension they want on the
          other. That is the duplicated-work report on this half, and one list is the whole of the
          fix. See [DwSketchDerivationSection]'s header.

          WHAT DELIBERATELY DID NOT TRANSFER, so the next reader does not "restore" it: the shared
          photograph card (no second consumer to share with — above), and the measuring card's
          "Measure a different photograph" disclosure, which is composed only under a hosted supply.
          `MeasureFromPhotoCard.tsx` offers that control on BOTH halves of the other client's tab and
          is right to; there it chooses a picture that is on no record at all, which is a thing no
          panel on this client can be handed. [DwMeasureDifferentPhotograph]'s header carries the
          long form.
        */
        val siblings = remember(entity) { entity?.liveFields.orEmpty().associateBy { it.key } }
        val derivationSources = dwChooserDerivationSources(chosen, siblings, media)
        val plateField = siblings.values.firstOrNull { dwOffersSketchRectify(it, siblings) }
        val measureTargets = if (siblings.values.any { dwOffersPhotoMeasure(it, siblings) }) {
            dwMeasurableLengthFields(siblings)
        } else {
            emptyList()
        }

        // A ROW WITH NO PHOTOGRAPHS STILL GETS THE SECTION, and the `derivationSources.isNotEmpty()`
        // that used to be on this line is gone deliberately. The section says what it cannot do and
        // where a photograph comes from; a section that vanished said neither, and on this tab the
        // cards are the only sign the capability exists at all. See the empty branch of
        // [DwSketchDerivationSection], and `UploadTabHost.tsx:1449-1453` for the other client paying
        // for the same omission. `chosen != null` stays: with no ROW there is nothing to be empty
        // ABOUT, and the picker immediately above already says to choose one.
        if (chosen != null) {
            DwSketchDerivationSection(
                sources = derivationSources,
                /*
                  WHERE A PHOTOGRAPH GOES, AS OPPOSED TO WHICH ONES ARE THERE — and read through the
                  SAME predicate that built `derivationSources` one line up, not a second filter.

                  [dwSketchSourceFields] is the one answer to "which fields of this entity hold a
                  photograph a derivation may read"; `dwChooserDerivationSources` walks it for the
                  media, and this walks it for the labels. Two filters would be two answers, and the
                  screen would eventually offer a chooser over one set while naming another.

                  IT IS THE ENTITY'S FIELDS AND NOT THIS TAB'S FOUR KEYS, which matters on exactly one
                  half: a prototype's `prototypePhotos` has no capture card here (the tab draws
                  `turntablePhotos` and `modelFile`), so a list built from `fields` would name only the
                  turn — and the empty sentence below would send a designer to the one destination
                  that is on this screen while staying silent about the one that is not. That is the
                  defect `MeasureFromPhotoCard.tsx` fixed on the other client for this same half.
                */
                photoFieldLabels = dwSketchSourceFields(siblings).map { it.label },
                rowName = dwChooserRowLabel(chosen, rows.indexOf(chosen)),
                plateField = plateField,
                plateFileName = plateField
                    ?.let { dwChooserHeldMedia(chosen, it.key).firstOrNull() }
                    ?.let(media.resolve)
                    ?.displayName,
                targets = measureTargets,
                rowValues = chosen.values,
                // The row's own category seeds the trace subject. `dwTraceSubjectFor` falls back to
                // the default for anything it does not recognise, absence included, so a row with no
                // category is not a special case.
                recordCategory = DwValues.text(chosen.values["category"]).takeIf { it.isNotBlank() },
                media = media,
                runtime = rememberDwTraceRuntime(),
                // THE SAME GATE THE CAPTURE CARDS ARE UNDER, and it has to be: both of these cards
                // write a row back, and writing over a collection this device has not reconciled is
                // how a stage loses rows nobody deleted.
                enabled = half.reconciled,
                onAttachedToPlate = { id ->
                    // `plateField` is non-null wherever this lambda can be reached — the section
                    // composes the two panels that call it only when it has one — but it is read
                    // through a null check rather than asserted, because a non-null assertion here
                    // would be a crash in a courtyard to save one line.
                    plateField?.let { onWriteMedia(half, chosenRow, it, listOf(id)) }
                },
                onPropose = { key, value, _ -> onWriteScalar(half, chosenRow, key, value) },
                onMessage = onMessage,
                onError = onProblem,
                exportCard = { slot ->
                    DwSketchTraceExportCard(
                        repository = repository,
                        traceSvg = slot.result.svg,
                        geometry = slot.result.geometry,
                        documentWidth = slot.result.width,
                        documentHeight = slot.result.height,
                        documentBackground = slot.documentBackground,
                        sourceName = slot.sourceName,
                        shapeCount = slot.result.shapeCount,
                        nodeCount = slot.result.nodeCount,
                        frameNote = slot.result.frameNote,
                        isPreview = slot.result.isPreview,
                        exporter = rememberDwTraceExporter(),
                        busy = slot.busy,
                        onBusyChange = slot.onBusyChange,
                        onError = onProblem,
                        onBackgroundChange = slot.onBackgroundChange,
                        onNeedFullResolution = slot.onNeedFullResolution,
                    )
                },
            )
        }
    }
}

/**
 * Every photograph on one chooser row that a derivation card may read, built ONCE.
 *
 * ── ONE DERIVATION OF "WHICH PHOTOGRAPHS", WHICH IS THE WHOLE OF REQUIREMENT 5 ON THIS CLIENT ──
 *
 * The stage form builds this list for the tracing and straightening panels and then reads a
 * DIFFERENT list for the measuring card — that one's own field's ids — and `FieldRenderer.kt`'s own
 * comment argues against precisely that shape one line above where it does it: *"A second copy of
 * the SOURCE list would be the same mistake one layer down."* On `sketch` the two happen to
 * coincide, because the entity declares exactly one image field. On `prototype`, which declares two
 * image lists, they do not.
 *
 * So there is one here, and all three cards read it. It is the entity's non-deprecated image fields
 * through [dwSketchSourceFields] — the same function the stage form's list uses — resolved against
 * the bridge, which is what turns an id into a path this device can actually open.
 *
 * NOT REMEMBERED, exactly as the stage form's is not: it is a filter over a map that is already in
 * memory, and a `remember` keyed on a row would have to compare a values map to know whether the
 * photographs had changed, which is the more expensive half.
 */
private fun dwChooserDerivationSources(
    row: DraftRow?,
    siblings: Map<String, FieldDto>,
    media: DwMediaBridge,
): List<DwSketchSource> {
    if (row == null) return emptyList()
    return dwSketchSourceFields(siblings).flatMap { imageField ->
        dwChooserHeldMedia(row, imageField.key)
            .mapNotNull(media.resolve)
            .filter { it.mediaType.equals("IMAGE", ignoreCase = true) }
            .map { DwSketchSource(fieldLabel = imageField.label, item = it) }
    }
}

/**
 * One entity's half of the tab, as one value: where it lives, what this device holds, and what it
 * could not find out.
 *
 * `reconciled` IS THE FIELD EVERY WRITE IS GATED ON and it means one narrow thing: the repository's
 * copy of this stage was READ on this pass and folded in. It is the handset's port of the web's
 * `StageRows.reconciled`, whose own comment gives the rule — *"anything that writes rows BACK … must
 * be writing over rows this browser has actually read"*.
 */
private data class DwChooserHalf(
    val entityKey: String,
    val stageKey: String?,
    val spec: StageDto?,
    val stage: StageDraft? = null,
    val reconciled: Boolean = false,
    /** Why the repository's copy is not here, in words — or null when it is. */
    val failure: String? = null,
) {
    val rows: List<DraftRow> get() = dwChooserRows(stage, entityKey)

    private val isSketch: Boolean get() = entityKey == DW_CHOOSER_SKETCH_ENTITY

    val title: String get() = if (isSketch) "Sketches" else "Prototypes"
    val what: String get() = if (isSketch) "This sketch" else "This prototype"
    val addLabel: String get() = if (isSketch) "Add a sketch" else "Add a prototype"
    val pickerLabel: String get() = if (isSketch) "Which sketch" else "Which prototype"

    val pickerPlaceholder: String
        get() = if (isSketch) "Choose a sketch" else "Choose a prototype"

    /**
     * NOTHING ON THIS HALF YET — what the one button here makes, and what it does not.
     *
     * ── IT CARRIED THE SAME STALE CLAIM [stageNote] WAS CORRECTED FOR, ONE SENTENCE EARLIER ───
     *
     * The sketches branch read *"…naming it, its caption and its measurements are on the stage
     * form."* Measurements stopped being only there when [DwSketchDerivationSection] was mounted
     * below: the measuring card proposes into `lengthCm`/`widthCm`/`heightCm` off a photograph, on
     * this tab. The correction is [stageNote]'s, word for word — what remains elsewhere is TYPING a
     * dimension in by hand, because the card only ever proposes one measured off a photograph and a
     * designer who used a tape still has nowhere here to put the number.
     *
     * The prototypes branch needed no correction and got none. It never claimed a derivation was
     * elsewhere, because that half has none to claim — `dwOffersSketchRectify` refuses both FILE
     * fields a prototype declares. See [dwSharesOnePhotograph].
     */
    val emptyNote: String
        get() = if (isSketch) DW_SKETCH_CHOOSER_NO_SKETCHES_YET else DW_SKETCH_CHOOSER_NO_PROTOTYPES_YET

    /**
     * Paragraphs drawn immediately above this half's capture cards, in order.
     *
     * EMPTY FOR SKETCHES, because there is nothing about a sketch's two fields that a designer
     * cannot see: both are images, both print, and a sentence saying so would be noise that trains
     * the reader to skip the place where the prototype's genuinely surprising fact is said.
     *
     * ── AND THE THIRD ONE IS THE ACCEPT LIST, WHICH IS THE DIFFERENCE THAT HAD NO WORDS ───────
     *
     * [DW_PROTOTYPE_MODEL_FORMATS]. This half takes a kind of file the other half refuses outright,
     * and until it was written down the only place that fact existed on this client was a wildcard
     * MIME in `DwMediaCapture.galleryMimeFor` — which says the opposite of what it means. The other client
     * names the eight formats under its own model button (`PrototypeModelField.tsx:619`); this is
     * that, in the one place on this half where a paragraph can be added. Neither client refuses
     * anything, and the constant's own header is careful to say so rather than turning a list into a
     * gate.
     *
     * THE ORDER IS THE READING ORDER OF THE CARDS BELOW, not the order the notes were written in:
     * the fact about the delivered document first, because it changes whether a designer bothers
     * with the turn at all; then how to shoot the turn, which is the first capture card; then what
     * to put in the model box, which is the second.
     */
    val mediaNotes: List<String>
        get() = if (isSketch) {
            emptyList()
        } else {
            listOf(
                DW_PROTOTYPE_3D_IN_THE_REPORT,
                DW_TURNTABLE_CAPTURE_ADVICE,
                DW_PROTOTYPE_MODEL_FORMATS,
            )
        }

    /**
     * Where everything this tab does NOT offer lives — one sentence, per half.
     *
     * ── IT NAMED A CAPABILITY THAT IS NOW ON THIS SCREEN, WHICH IS THE WORST KIND OF STALE ────
     *
     * The sketches half read *"Everything this tab does not offer — the name, the caption, the
     * measurements, straightening a photographed sketch into a plate — is on that form."* Two of those
     * four stopped being true the moment [DwSketchDerivationSection] was mounted below: the
     * straightening panel is composed on this screen wherever `dwOffersSketchRectify` answers for the
     * entity's plate field, which on `sketch` is `lineArtFile`, and the measuring card proposes into
     * `lengthCm`/`widthCm`/`heightCm` from a photograph.
     *
     * **A SENTENCE THAT SENDS A DESIGNER AWAY FROM A CONTROL THEY ARE LOOKING AT IS WORSE THAN NO
     * SENTENCE**, and it is the same defect, in the same direction, that the other client fixed on
     * its own empty measuring branch — *"a sentence pointing at the wrong place is the defect this tab
     * has already paid for once"* — and that [dwNoPhotographSentence] was rewritten for. This is the
     * third instance of it on these two surfaces, which is why it is written down here rather than
     * quietly corrected.
     *
     * SO THE LIST IS WHAT THIS TAB GENUINELY CANNOT REACH, and the derivations are not in it. Typing a
     * dimension in by hand IS still elsewhere and stays named, because the measuring card only
     * PROPOSES one off a photograph — a designer who measured the piece with a tape has nowhere here
     * to put the number.
     *
     * THE TWO HALVES ARE ONE VOICE AND NOT ONE STRING, which is the same call [emptyNote] and
     * [addLabel] make: the shape, the order and the promise are identical and only the entity's own
     * nouns differ. The prototypes half needed no correction — it never claimed a derivation was
     * elsewhere, because it has none to claim.
     */
    val stageNote: String
        get() = if (isSketch) DW_SKETCH_CHOOSER_SKETCH_ELSEWHERE else DW_SKETCH_CHOOSER_PROTOTYPE_ELSEWHERE
}

/**
 * Which stage a media descriptor is stamped with, from the field key that is being attached to.
 *
 * The four keys are unique across the two stages, so the field alone identifies its stage. Null when
 * neither half claims it, which stamps the descriptor with no stage — the same thing a media import
 * with no stage in hand already does, and the sync pass reads the reference off the row rather than
 * off this stamp, so nothing breaks. It is a locator, not a link.
 */
private fun dwChooserStageForField(
    fieldKey: String,
    sketch: DwChooserHalf?,
    prototype: DwChooserHalf?,
): String? = when (fieldKey) {
    in DW_CHOOSER_SKETCH_FIELDS -> sketch?.stageKey
    in DW_CHOOSER_PROTOTYPE_FIELDS -> prototype?.stageKey
    else -> null
}

// ══════════════════════════════════════════════════════════════════════════════════════════════════
// THE SENTENCES THIS TAB CAN SAY
// ══════════════════════════════════════════════════════════════════════════════════════════════════
//
// Named and `internal` for the same reason the chooser's three list answers are: the defects these
// exist to prevent are WORDING ones, and a wording defect cannot be caught by a test that cannot see
// the wording. `DwSketchChooserSentenceTest` pins the properties that matter — a failure is never
// worded as an answer, and a promise about the repository is never made on a phone's behalf.

/*
  ── WHERE THE REST OF THE RECORD IS, AND THE FOUR SENTENCES THAT SAY IT ──────────────────────────

  THESE WERE INLINE IN `DwChooserHalf` AND THAT IS WHY ONE OF THEM STAYED WRONG. Two of the four make
  a claim about what this tab does NOT do, and that claim goes stale every time the tab gains a
  capability — which it did on 2026-08-29, when [DwSketchDerivationSection] put tracing, straightening
  and measuring on the screen they were sending designers away from. The pass that mounted the section
  corrected `stageNote` and missed `emptyNote`, one getter above it, saying the same thing in fewer
  words.

  A private getter on a private data class is invisible to a JVM test, so nothing could have caught
  it. Hoisted here they are pinnable, and `DwSketchChooserSentenceTest` pins the property rather than
  the prose: NO SENTENCE ON THIS TAB MAY PUT AN ACT THIS TAB MOUNTS A PANEL FOR SOMEWHERE ELSE. That
  is a rule the next capability added to this screen has to answer to, rather than a transcription of
  today's wording that a reader would have to update by hand and would therefore not.
*/

/**
 * NO SKETCHES ON THIS WORKSHOP YET.
 *
 * "its measurements are on the stage form" stood here until 2026-08-29 and had been false since the
 * measuring card was mounted below. See [DwChooserHalf.emptyNote] for the correction and
 * [DW_SKETCH_CHOOSER_SKETCH_ELSEWHERE] for the same narrowing in the sibling sentence: what is
 * genuinely still elsewhere is TYPING a dimension in, because the card only proposes one it measured.
 */
internal const val DW_SKETCH_CHOOSER_NO_SKETCHES_YET: String =
    "This workshop has no sketches yet. \"Add a sketch\" makes the row here; naming it, its caption " +
        "and typing a dimension in by hand are on the stage form."

/** NO PROTOTYPES ON THIS WORKSHOP YET. Never carried the stale claim — see [DwChooserHalf.emptyNote]. */
internal const val DW_SKETCH_CHOOSER_NO_PROTOTYPES_YET: String =
    "This workshop has no prototypes yet. \"Add a prototype\" makes the row here; naming it, its " +
        "materials and its stage log are on the stage form."

/** WHERE THE SKETCH HALF'S REMAINING WORK IS. The full argument is at [DwChooserHalf.stageNote]. */
internal const val DW_SKETCH_CHOOSER_SKETCH_ELSEWHERE: String =
    "A workshop can hold as many sketches as it documented, and each one is a row on the sketch " +
        "stage. Everything this tab does not offer — the name, the caption, the category, typing a " +
        "dimension in by hand — is on that form."

/** WHERE THE PROTOTYPE HALF'S REMAINING WORK IS. Needed no correction; see [DwChooserHalf.stageNote]. */
internal const val DW_SKETCH_CHOOSER_PROTOTYPE_ELSEWHERE: String =
    "A workshop can hold as many prototypes as it made, and each one is a row on the prototype " +
        "stage. Everything this tab does not offer — the name, the materials, the stage log, the " +
        "costing — is on that form."

/** COULD NOT READ THE STAGE, with no signal. The offline half of the split. */
internal const val DW_SKETCH_CHOOSER_STAGE_OFFLINE: String =
    "No connection, so the repository's copy of this stage could not be read — a list that could " +
        "not be asked for, not a list with nothing in it."

/** COULD NOT READ THE STAGE, and the repository said why. The fallback for when it did not. */
internal const val DW_SKETCH_CHOOSER_STAGE_REFUSED: String =
    "The repository's copy of this stage could not be read."

/**
 * WHY THE CONTROLS ARE OFF WHILE THE READ HAS NOT LANDED — the honest half of the refusal.
 *
 * A disabled control with no explanation is the shape of a screen that looks broken, which is
 * `DwRankableList.disabledReason`'s rule and holds here. The reason is not a permission and the
 * sentence must not read as one: this phone simply has not seen the repository's copy, so attaching
 * a file to a row it has not read would be writing over a collection it cannot vouch for.
 */
internal const val DW_SKETCH_CHOOSER_STALE: String =
    "Adding to a row is off until that read lands: this phone would be writing into a collection it " +
        "has not seen, and a workshop's other sketches may not be on it. Nothing has been lost — the " +
        "rows below are what this device holds, and the stage form opens with what it has."

/** The registry does not declare this entity at all. A schema mismatch and not a permission. */
internal const val DW_SKETCH_CHOOSER_NO_STAGE: String =
    "This build's field registry does not declare the stage this belongs to, so nothing can be " +
        "added or attached from here. That is a schema mismatch rather than a permission — open the " +
        "workshop once with a connection, which is what refreshes the registry."

/** The stage exists but the four media fields do not. The web's sentence, in the handset's words. */
internal const val DW_SKETCH_CHOOSER_NO_MEDIA_FIELDS: String =
    "This build's field registry does not declare the image, line-art and 3D-model fields this tab " +
        "attaches into, so files cannot be added from here. Open the stage form, which renders " +
        "whatever the registry does declare."

/** The row write failed on the device. Said in place, beside the button that asked for it. */
internal const val DW_SKETCH_CHOOSER_ROW_NOT_ADDED: String =
    "That row has NOT been added: this phone could not write to its own draft. Nothing has changed. " +
        "Storage being full is the usual cause, and the stage form writes through the same store, " +
        "so it will refuse in the same way until there is room."

/** The media write failed on the device. The bytes are kept; see the sentence. */
internal const val DW_SKETCH_CHOOSER_FILE_NOT_ATTACHED: String =
    "That file has NOT been attached to the row: this phone could not write to its own draft. The " +
        "file itself was copied into this workshop and is not lost — attach it from the stage form, " +
        "which writes through the same store."

/**
 * The dimension write failed on the device.
 *
 * ITS OWN SENTENCE AND NOT A REUSE OF THE FILE ONE, which is this screen's stated rule: *"Any new
 * state added to the Upload tab needs its own sentence rather than reuse of one of these."* What is
 * lost here is different in kind — a file that failed to attach is still in the workshop's media
 * directory and can be attached again in one press, and a measurement that failed to write is gone
 * unless somebody re-marks the photograph. Saying that plainly is what stops a designer walking away
 * from a number they think is filed.
 */
internal const val DW_SKETCH_CHOOSER_MEASUREMENT_NOT_SAVED: String =
    "That measurement has NOT been written to the row: this phone could not write to its own draft. " +
        "Nothing has changed, and the figure is not kept anywhere — write it down before you leave " +
        "this screen. Storage being full is the usual cause, and the stage form writes through the " +
        "same store, so it will refuse in the same way until there is room."
