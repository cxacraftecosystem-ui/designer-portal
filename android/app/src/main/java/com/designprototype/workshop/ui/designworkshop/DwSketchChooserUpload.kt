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
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
            onOpenStage = onOpenStage,
            workshopId = workshopId,
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
            onOpenStage = onOpenStage,
            workshopId = workshopId,
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
    onOpenStage: (String, String) -> Unit,
    workshopId: String,
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
        // THE ESCAPE IS NAMED RATHER THAN IMPLIED. Everything this tab cannot do — naming the sketch,
        // its caption, its measurements, straightening the plate — is on that form, and a designer
        // who is not told that will look for it here.
        Text(
            half.stageNote,
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        val fields = dwChooserMediaFields(dwChooserEntity(spec, half.entityKey), fieldKeys)
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

    val emptyNote: String
        get() = if (isSketch) {
            "This workshop has no sketches yet. \"Add a sketch\" makes the row here; naming it, its " +
                "caption and its measurements are on the stage form."
        } else {
            "This workshop has no prototypes yet. \"Add a prototype\" makes the row here; naming " +
                "it, its materials and its stage log are on the stage form."
        }

    /**
     * Paragraphs drawn immediately above this half's capture cards, in order.
     *
     * EMPTY FOR SKETCHES, because there is nothing about a sketch's two fields that a designer
     * cannot see: both are images, both print, and a sentence saying so would be noise that trains
     * the reader to skip the place where the prototype's genuinely surprising fact is said.
     */
    val mediaNotes: List<String>
        get() = if (isSketch) {
            emptyList()
        } else {
            listOf(DW_PROTOTYPE_3D_IN_THE_REPORT, DW_TURNTABLE_CAPTURE_ADVICE)
        }

    val stageNote: String
        get() = if (isSketch) {
            "A workshop can hold as many sketches as it documented, and each one is a row on the " +
                "sketch stage. Everything this tab does not offer — the name, the caption, the " +
                "measurements, straightening a photographed sketch into a plate — is on that form."
        } else {
            "A workshop can hold as many prototypes as it made, and each one is a row on the " +
                "prototype stage. Everything this tab does not offer — the name, the materials, " +
                "the stage log, the costing — is on that form."
        }
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
