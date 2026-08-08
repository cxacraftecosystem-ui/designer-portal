package com.designprototype.workshop.ui.designworkshop

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.designprototype.workshop.data.DwIntakePhoto
import com.designprototype.workshop.data.DwPhotoIntake
import com.designprototype.workshop.data.DwPhotoIntakeRow
import com.designprototype.workshop.data.DwStageData
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.DwWorkshopAnchor
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.dwStageDataFrom
import com.designprototype.workshop.data.entityKey
import com.designprototype.workshop.data.liveFields
import com.designprototype.workshop.data.singleton
import com.designprototype.workshop.data.codeRow
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
// The two-typeface `Text`, shadowing androidx.compose.material3.Text. Without this import the bare
// `Text` below resolves to Material's, inherits whatever family LocalTextStyle carries, and quietly
// sets this screen's headings in the body face — the exact failure ui/FieldText.kt exists to prevent.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Import photographs — attach a camera dump to a workshop, one confirmation instead of two hundred.
 *
 * ── THE JOB ───────────────────────────────────────────────────────────────────────────────────
 *
 * A designer shoots two hundred photographs over a thirty-day workshop and then attaches each one by
 * hand, stage by stage. This screen reads the EXIF capture clock off every picked file, matches it
 * against the dates the workshop already records, and proposes where each one belongs. The designer
 * scans the list, fixes what is wrong, and presses Confirm once.
 *
 * ── WHY IT HAS TO BE HERE AND NOT ONLY ON THE WEB ─────────────────────────────────────────────
 *
 * The web has had this page since `app/(protected)/design-workshops/[id]/photos/page.tsx`, and it is
 * unreachable in the place the photographs actually are: the phone in the courtyard. The whole
 * matching runs with no network at all — the registry is cached, the workshop's dates are in the
 * local draft, and the clock is in the file — so the one thing missing was a surface.
 *
 * ── IT PROPOSES. IT DOES NOT COMMIT ───────────────────────────────────────────────────────────
 *
 * Nothing is copied, attached or uploaded until Confirm is pressed, and every proposal carries the
 * sentence that justifies it, written once in [DwPhotoIntake] so the phone and the browser cannot
 * describe one decision differently. That is the same rule the identity-card reader follows and it is
 * here for the same reason: a bulk tool that files two hundred photographs on its own judgement is a
 * bulk tool that files the wrong ones invisibly. The evidence line exists so a wrong proposal is
 * OBVIOUS rather than plausible; the ranking exists so the designer's job is checking rather than
 * sorting.
 *
 * ── WHAT CONFIRM ACTUALLY DOES, AND WHY IT WRITES NO UPLOAD CODE ──────────────────────────────
 *
 * Each confirmed photograph goes through [WorkshopDraftStore.importMedia] — the same copy-hash-sync
 * path a photograph attached on a stage form takes — and the media id it returns is written into the
 * target stage's image field in the local draft. From there [com.designprototype.workshop.data.WorkshopSyncEngine]'s
 * ordinary pass uploads the bytes, substitutes the id the server acknowledged and sends the stage.
 * This screen therefore inherits the whole of that pipeline (retries, hold-back until the photographs
 * have landed, the refusal ledger) instead of reimplementing a two-hundred-file uploader that would
 * have to learn all of it again.
 *
 * NOTHING IS RE-ENCODED. The bytes picked are the bytes stored. `docs/MEDIA_PIPELINE.md` §5 records
 * why this archive refuses to re-encode an image at all, and a photo importer that stripped the
 * timestamps it sorted by would be the sharpest possible version of that mistake.
 *
 * ── WHY THERE IS NO THUMBNAIL ON EVERY ROW ────────────────────────────────────────────────────
 *
 * This screen renders inside the app's shared scrolling Column, so — like every other screen in this
 * feature — it may not use a LazyColumn: a lazy list measured inside a parent that scrolls the same
 * way throws at layout. Every one of two hundred rows is therefore composed at once, and two hundred
 * live image requests on a 6 GB handset that is also holding a draft and a map is the
 * OutOfMemoryError this feature spends so much care avoiding elsewhere. So a row is text until it is
 * TAPPED, and the expanded row — there is only ever one — is the only thing that decodes a
 * photograph.
 *
 * That preview is a Coil `AsyncImage` on the picked `content://` Uri, which is the same bounded path
 * [DwAttachmentRow] already uses for an attachment: Coil samples to the box it is drawn in, applies
 * the EXIF quarter turn, and bounds its own cache. [com.designprototype.workshop.data.DwImageDecode]
 * is deliberately NOT called here even though it is this repository's careful decoder — it takes a
 * FILE PATH, and at this point in the flow the photographs have not been copied to this device;
 * copying two hundred of them to draw a preview is precisely what "nothing moves until Confirm"
 * forbids. Its `measure()` is a few hundred milliseconds per photograph besides, which over a camera
 * dump is a minute of frozen screen.
 *
 * ── IT READS THE LOCAL DRAFT ONLY ─────────────────────────────────────────────────────────────
 *
 * Exactly as the web page reads only `loadDraft(id)`. A workshop this handset has never opened has no
 * dates on it to match against and no rows to file into, and that is said in words rather than shown
 * as an empty list — the silent-emptiness failure this repository keeps hitting. Falling back to the
 * server's copy would be worse than useless: it would offer a collection row this draft does not
 * hold, and the confirmation would have nowhere to write it.
 */
@Composable
fun PhotoIntakeScreen(
    repository: WorkshopRepository,
    workshopId: String,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    var schema by remember(workshopId) { mutableStateOf<SchemaResponse?>(null) }
    var draft by remember(workshopId) { mutableStateOf<WorkshopDraft?>(null) }
    var loading by remember(workshopId) { mutableStateOf(true) }
    var missingDraft by remember(workshopId) { mutableStateOf(false) }
    var lines by remember(workshopId) { mutableStateOf<List<DwIntakeLine>>(emptyList()) }
    /** done to total while the clocks are being read, so a two-hundred-file dump shows progress. */
    var reading by remember(workshopId) { mutableStateOf<Pair<Int, Int>?>(null) }
    var confirming by remember(workshopId) { mutableStateOf(false) }
    /** The one row whose picker and preview are drawn. See the KDoc on why only one. */
    var expanded by remember(workshopId) { mutableStateOf<Int?>(null) }

    LaunchedEffect(workshopId) {
        loading = true
        runCatching {
            repository.designWorkshopSchema(appContext) to WorkshopDraftStore.load(appContext, workshopId)
        }.onSuccess { (loaded, local) ->
            schema = loaded
            draft = local
            missingDraft = local == null
        }.onFailure {
            onError(it.message ?: "Unable to read this workshop on this device.")
        }
        loading = false
    }

    val stageData: Map<String, DwStageData> = remember(schema, draft) {
        schema?.let { dwStageDataFrom(it, draft) }.orEmpty()
    }
    val anchors: List<DwWorkshopAnchor> = remember(schema, stageData) {
        schema?.let { DwPhotoIntake.buildAnchors(it, stageData) }.orEmpty()
    }
    val destinations: List<DwIntakeDestination> = remember(schema, stageData) {
        schema?.let { dwIntakeDestinations(it, stageData) }.orEmpty()
    }
    val destinationsByKey = remember(destinations) { destinations.associateBy { it.key } }
    /**
     * Which destinations each ANCHOR points at, so the expanded row does not scan the whole list.
     *
     * One entity can carry two image fields — an "Opening photographs" and an "Event photographs" —
     * so an anchor maps to a LIST, and the row offers both rather than guessing between them.
     */
    val destinationsByAnchor = remember(destinations) {
        destinations.groupBy { Triple(it.stageKey, it.entityKey, it.rowKey) }
    }

    /**
     * The destination a proposal points at: its entity's first image LIST, when it has one.
     *
     * `multiple` is a hard condition, not a preference, and it is the difference between a useful
     * import and a destructive one. A workshop's own window is a legitimate anchor for any photograph
     * taken during it, and the only image field on that entity is a single-valued cover photograph.
     * Auto-selecting it would point every photograph shot on an unlogged day at one box that holds
     * exactly one photograph, so a two-hundred-file import would write each over the last and finish
     * having attached one, silently, over the designer's chosen cover.
     *
     * A single-valued field can still be chosen BY HAND from the full list — a designer deliberately
     * setting the cover photograph is a perfectly good thing to do. It is only the automatic default
     * that refuses, because a default is applied two hundred times without being read.
     *
     * ONLY a proposal that actually COVERS the photograph's date may become a default. A NEAREST
     * proposal carries evidence saying in so many words that nothing recorded covers the date; that
     * is for a human to weigh, not to apply unread two hundred times.
     */
    fun defaultDestinationFor(row: DwPhotoIntakeRow): String {
        for (proposal in row.proposals) {
            if (proposal.daysAway != 0) continue
            val anchor = proposal.anchor
            val match = destinationsByAnchor[Triple(anchor.stageKey, anchor.entityKey, anchor.rowKey)]
                ?.firstOrNull { it.multiple }
            if (match != null) return match.key
        }
        return ""
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { picked ->
        if (picked.isEmpty()) return@rememberLauncherForActivityResult
        expanded = null
        scope.launch {
            reading = 0 to picked.size
            val photos = ArrayList<DwIntakePhoto>(picked.size)
            // READ IN ORDER, ONE AT A TIME. Each read is a seek into the head of a local file, not a
            // network request, so a fan-out buys little; a sequential loop is what keeps the counter
            // honest about progress and what lets leaving the screen cancel cleanly mid-dump.
            picked.forEachIndexed { index, uri ->
                val name = WorkshopDraftStore.displayName(appContext, uri) ?: "photograph ${index + 1}"
                val clock = dwReadCaptureStamp(appContext, uri)
                photos.add(DwIntakePhoto(fileName = name, takenAt = clock.first, takenAtOffset = clock.second))
                reading = (index + 1) to picked.size
            }
            val rows = DwPhotoIntake.intakePhotos(photos, anchors)
            lines = rows.mapIndexed { index, row ->
                DwIntakeLine(uri = picked[index], row = row, choice = defaultDestinationFor(row))
            }
            reading = null
        }
    }

    fun confirm() {
        val currentSchema = schema ?: return
        val chosen = lines.filter { it.choice.isNotEmpty() }
        if (chosen.isEmpty()) return
        confirming = true
        scope.launch {
            val outcome = runCatching {
                dwConfirmIntake(appContext, workshopId, currentSchema, chosen, destinationsByKey)
            }
            confirming = false
            outcome.onSuccess { result ->
                draft = WorkshopDraftStore.load(appContext, workshopId)
                expanded = null
                // The confirmed rows leave the list; the ones that could not be placed STAY, with
                // their choice intact, because the designer has to be able to see which they were.
                lines = lines.filter { it.choice.isEmpty() || it.uri in result.unresolved }
                onMessage(result.message)
                result.problem?.let(onError)
            }.onFailure {
                onError(
                    "Nothing could be written to this device's storage, so no photograph has been " +
                        "attached. Free some space and try again — nothing has been lost."
                )
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Import photographs", display = true, color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp)
        Text(
            "Match a camera dump against the dates this workshop already records, then confirm where " +
                "each photograph belongs.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )

        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Reading this workshop…", color = MaterialTheme.field.muted, fontSize = 13.sp)
            }
            return@Column
        }

        if (missingDraft) {
            DwWorkshopNotice(
                "This workshop has not been downloaded to this device yet. Open its stages once with a " +
                    "connection, then come back — the dates it records are what these photographs are " +
                    "matched against."
            )
            return@Column
        }

        // The assumption, stated before anything is read rather than after something looks wrong.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface50, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("How the dates are read", display = true, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Text(
                "Each photograph's capture time is read from the file itself and taken to be " +
                    "${DwPhotoIntake.DEFAULT_TIMEZONE} — the clock the camera was set to. Nothing in the " +
                    "file is changed, and no photograph is attached until you press Confirm.",
                color = MaterialTheme.field.body,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Text(
                if (anchors.isNotEmpty()) {
                    "This workshop records ${anchors.size} dated " +
                        "${if (anchors.size == 1) "entry" else "entries"} to match against — its start and " +
                        "end dates, its schedule days, its daily prototype logs and its closing. The more " +
                        "of those that are filled in, the more precise each proposal is."
                } else {
                    "This workshop has no dates recorded yet, so nothing can be proposed. Fill in the " +
                        "start and end dates on stage 1 — and the daily logs — and these photographs will " +
                        "place themselves."
                },
                color = MaterialTheme.field.muted,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }

        OutlinedButton(
            onClick = { pick.launch("image/*") },
            enabled = reading == null && !confirming,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Choose photographs")
        }
        Text(
            "Nothing is copied by choosing files. They are read where they are, and stay there until " +
                "you confirm.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )

        reading?.let { (done, total) ->
            Text(
                "Reading capture dates — $done of $total.",
                color = MaterialTheme.field.body,
                fontSize = 12.sp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

        if (lines.isEmpty()) return@Column

        HorizontalDivider()

        val summary = DwPhotoIntake.intakeSummary(lines.map { it.row })
        val ready = lines.count { it.choice.isNotEmpty() }
        // COUNTED, NEVER MERELY DESCRIBED: the number this could not place is the number a designer
        // has to look at, and it must be on screen before Confirm. These count the CURRENT
        // destinations rather than the proposals — a photograph whose proposal was refused as a
        // default (nothing covers its date, or the only field going spare holds one photograph) still
        // needs a human, and counting it as placed would be the reassuring version of the truth.
        Text(
            "${summary.total} chosen · $ready ready to attach · ${summary.total - ready} need you",
            color = if (ready < summary.total) MaterialTheme.field.warning else MaterialTheme.field.muted,
            fontSize = 12.sp
        )

        Button(
            onClick = ::confirm,
            enabled = ready > 0 && !confirming && reading == null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (confirming) {
                    "Attaching…"
                } else {
                    "Confirm $ready photograph${if (ready == 1) "" else "s"}"
                }
            )
        }

        lines.forEachIndexed { index, line ->
            DwIntakeRowCard(
                line = line,
                destination = destinationsByKey[line.choice],
                expanded = expanded == index,
                enabled = !confirming,
                // Built only for the row that is open, because it is a walk over this photograph's
                // proposals and the whole destination list, and doing it for two hundred rows on
                // every recomposition is work nobody can see.
                proposed = if (expanded == index) {
                    line.row.proposals
                        .flatMap { destinationsByAnchor[Triple(it.anchor.stageKey, it.anchor.entityKey, it.anchor.rowKey)].orEmpty() }
                        .distinctBy { it.key }
                } else {
                    emptyList()
                },
                allDestinations = destinations,
                onToggle = { expanded = if (expanded == index) null else index },
                onChoose = { key ->
                    lines = lines.mapIndexed { position, item ->
                        if (position == index) item.copy(choice = key) else item
                    }
                },
            )
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

// --------------------------------------------------------------------------------------
// One row
// --------------------------------------------------------------------------------------

@Composable
private fun DwIntakeRowCard(
    line: DwIntakeLine,
    destination: DwIntakeDestination?,
    expanded: Boolean,
    enabled: Boolean,
    proposed: List<DwIntakeDestination>,
    allDestinations: List<DwIntakeDestination>,
    onToggle: () -> Unit,
    onChoose: (String) -> Unit,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClickLabel = if (expanded) "Hide this photograph" else "Show this photograph",
                        onClick = onToggle
                    )
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        line.row.fileName,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        line.row.stamp?.let { "Taken ${DwPhotoIntake.formatStamp(it)}" } ?: "No capture date",
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp
                    )
                    Text(
                        destination?.label ?: "Left out — choose a stage to attach it",
                        color = if (destination != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.field.warning
                        },
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.field.muted,
                    modifier = Modifier.size(18.dp)
                )
            }

            // An `if` block rather than an early `return@Column`, so everything below is one
            // group the runtime adds and removes whole. The picker inside it keeps state of its
            // own, and a collapsed row must take that state away with it rather than leave slots
            // behind for whichever row is opened next to inherit.
            if (expanded) {

                // The one decode on this screen. See the file header for why it is not on every row.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
                ) {
                    AsyncImage(
                        model = line.uri,
                        contentDescription = "Preview of ${line.row.fileName}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // THE EVIDENCE SHOWN IS THE ONE FOR THE PROPOSAL ACTUALLY SELECTED, so changing the
                // destination cannot leave a sentence on screen justifying a different answer.
                val matching = line.row.proposals.firstOrNull { proposal ->
                    destination != null &&
                        proposal.anchor.stageKey == destination.stageKey &&
                        proposal.anchor.entityKey == destination.entityKey &&
                        proposal.anchor.rowKey == destination.rowKey
                }
                Text(
                    matching?.evidence
                        ?: line.row.refusal
                        ?: if (destination != null) {
                            "Chosen by hand — no capture date was used."
                        } else {
                            line.row.proposals.firstOrNull()?.evidence ?: "Nothing proposed."
                        },
                    color = if (matching == null && line.row.refusal != null) {
                        MaterialTheme.field.onWarningContainer
                    } else {
                        MaterialTheme.field.muted
                    },
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = if (matching == null && line.row.refusal != null) {
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.field.warningContainer, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    } else {
                        Modifier.fillMaxWidth()
                    }
                )

                /*
                 * ONE list, with this photograph's own proposals at the top under a hint that says where
                 * they came from, and every other place a photograph can go beneath.
                 *
                 * The proposed rows are EXCLUDED from the tail rather than repeated in it: two options
                 * carrying the same value is a control that cannot say which one is selected.
                 */
                // Not `remember`ed: [proposed] is rebuilt by the caller on every recomposition, so it
                // would never equal the previous key and the cache would cost a comparison to miss
                // every time. This runs for ONE row — the open one — over a list of tens.
                val options = proposed.map { SelectOption(it.key, it.label, "Proposed from the capture date") } +
                    allDestinations
                        .filterNot { candidate -> proposed.any { it.key == candidate.key } }
                        .map { SelectOption(it.key, it.label) }
                if (options.isEmpty()) {
                    Text(
                        "This workshop has no photograph fields to attach to yet.",
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp
                    )
                } else {
                    SearchableSelectField(
                        label = "Attach to",
                        options = options,
                        selectedValue = line.choice,
                        // The "nothing selected" row's own words, so leaving a photograph out is a
                        // decision a designer can read rather than an empty box they assume is a bug.
                        placeholder = "Leave out — I will attach this one myself",
                        enabled = enabled,
                        onSelect = onChoose
                    )
                }
            }
        }
    }
}

// --------------------------------------------------------------------------------------
// What the screen holds
// --------------------------------------------------------------------------------------

/** Where one confirmed photograph is going: a stage, an entity, optionally a row, and a field. */
private data class DwIntakeDestination(
    /** The picker's stored value, and the identity writes are grouped by. */
    val key: String,
    val stageKey: String,
    val stageNumber: Int,
    val stageTitle: String,
    val entityKey: String,
    /** Null for a SINGLETON entity. */
    val rowKey: String?,
    val fieldKey: String,
    val label: String,
    val multiple: Boolean,
)

/** One line of the list: the file, what the intake made of it, and where it is currently headed. */
private data class DwIntakeLine(
    val uri: Uri,
    val row: DwPhotoIntakeRow,
    /** The chosen destination key, or "" for "leave this one out". */
    val choice: String,
)

private fun dwDestinationKey(stageKey: String, entityKey: String, rowKey: String?, fieldKey: String): String =
    "$stageKey|$entityKey|${rowKey.orEmpty()}|$fieldKey"

/**
 * Every place a photograph could be filed, built from the registry.
 *
 * REGISTRY-DRIVEN, not a hand-written menu: it lists the image fields of every entity of all 22
 * stages, so a stage added on the server with a photograph field becomes a destination here with no
 * change to this file — the same property the stage forms have.
 *
 * A SINGLETON contributes its destinations whether or not this device holds the stage, because a
 * designer may legitimately file a photograph into a stage they have not opened yet. A COLLECTION
 * contributes one destination per EXISTING ROW, and a row with no key at all contributes none: a
 * photograph cannot be written into a row nothing can address, and offering it would be a
 * confirmation that quietly did nothing.
 */
private fun dwIntakeDestinations(
    schema: SchemaResponse,
    stageData: Map<String, DwStageData>,
): List<DwIntakeDestination> {
    val out = ArrayList<DwIntakeDestination>()
    for (stage in schema.stages) {
        val data = stageData[stage.key]
        for (entity in stage.entities) {
            val targets = DwPhotoIntake.photoTargets(schema, stage.key, entity.key)
            if (targets.isEmpty()) continue
            if (entity.cardinality == "SINGLETON") {
                targets.forEach { target ->
                    out.add(
                        DwIntakeDestination(
                            key = dwDestinationKey(stage.key, entity.key, null, target.fieldKey),
                            stageKey = stage.key,
                            stageNumber = stage.number,
                            stageTitle = stage.title,
                            entityKey = entity.key,
                            rowKey = null,
                            fieldKey = target.fieldKey,
                            multiple = target.multiple,
                            label = "${stage.number}. ${stage.title} — ${target.fieldLabel}",
                        )
                    )
                }
                continue
            }
            data?.collections?.get(entity.key).orEmpty().forEachIndexed { index, row ->
                val rowKey = DwPhotoIntake.rowKeyOf(row) ?: return@forEachIndexed
                val label = entity.labelField.takeIf { it.isNotEmpty() }
                    ?.let { DwValues.text(row[it]).trim() }
                    ?.takeIf { it.isNotEmpty() }
                    ?: "row ${index + 1}"
                targets.forEach { target ->
                    out.add(
                        DwIntakeDestination(
                            key = dwDestinationKey(stage.key, entity.key, rowKey, target.fieldKey),
                            stageKey = stage.key,
                            stageNumber = stage.number,
                            stageTitle = stage.title,
                            entityKey = entity.key,
                            rowKey = rowKey,
                            fieldKey = target.fieldKey,
                            multiple = target.multiple,
                            label = "${stage.number}. ${stage.title} — ${entity.title} “$label” — ${target.fieldLabel}",
                        )
                    )
                }
            }
        }
    }
    return out
}

// --------------------------------------------------------------------------------------
// Platform: reading the clock off a picked file
// --------------------------------------------------------------------------------------

/**
 * `DateTimeOriginal` and `OffsetTimeOriginal` off a picked file, WITHOUT copying it.
 *
 * The Android half of [DwPhotoIntake], exactly as `readCaptureStamp` in `lib/media.ts` is the
 * browser's: it opens the file, reads two tags out of the header and hands back strings. Two hundred
 * of these run before the designer has agreed to attach anything, so the one thing it must not do is
 * move bytes.
 *
 * THE PLATFORM `android.media.ExifInterface`, not `androidx.exifinterface`, and that is a decision
 * rather than an oversight. The androidx artifact is not a dependency of this module (see
 * app/build.gradle.kts) and the release APK ships to field handsets over a village connection, so a
 * megabyte of library for two tag reads is a cost every designer pays on every update. The platform
 * class has had `TAG_DATETIME_ORIGINAL` since API 24 and `minSdk` here is 26; it is also the class
 * [WorkshopDraftStore.withImageMetadata] and [com.designprototype.workshop.data.DwImageDecode] both
 * already read orientation with, so a second reader would be a second opinion about one file.
 *
 * `TAG_OFFSET_TIME_ORIGINAL` was added to that class in API 29. The constant is a compile-time
 * `String`, so it is inlined and cannot fail to resolve on an older handset — but the platform parser
 * on API 26-28 does not know the tag and answers null, which lands on exactly the naive-wall-clock
 * path the screen already states as its assumption. A camera that declares its zone is the minority
 * case; an older handset simply does not get the correction, and never gets a wrong one.
 *
 * `DateTimeDigitized` is the fallback rather than the TIFF `DateTime`, matching the web's
 * `DateTimeOriginal ?? CreateDate`. It is the digitisation time rather than the shutter time and the
 * two differ only for scans — where `DateTimeOriginal` is absent anyway, so taking it changes nothing
 * for a photograph and rescues a scan. The TIFF `DateTime` is a MODIFICATION time, which a copy or an
 * edit rewrites to today, and filing a fortnight-old photograph on today's date is the plausible
 * wrong answer this whole feature exists to avoid.
 *
 * Returns nulls rather than throwing. A PNG screenshot, a truncated download, a HEIC this platform
 * cannot parse and a file the provider revoked between the pick and the read are all ordinary here,
 * and every one of them must reach the list as a row a designer can assign by hand — never as a
 * failed import.
 */
private suspend fun dwReadCaptureStamp(context: Context, uri: Uri): Pair<String?, String?> =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val takenAt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                takenAt to exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            }
        }.getOrNull() ?: (null to null)
    }

// --------------------------------------------------------------------------------------
// Confirming
// --------------------------------------------------------------------------------------

/** What one Confirm did, in the words the surface says it in. */
private data class DwIntakeOutcome(
    val message: String,
    /** Non-null when something has to be reported as a problem rather than as a result. */
    val problem: String?,
    /** The photographs that did NOT land, so their rows stay on screen. */
    val unresolved: Set<Uri>,
)

/**
 * Copy every chosen photograph into the workshop, then write the references into the draft.
 *
 * ── ORDER OF OPERATIONS, AND IT MUST NOT BE REVERSED ──────────────────────────────────────────
 *
 * Bytes first, references second. [WorkshopDraftStore.importMedia] copies each file into
 * `filesDir/workshops/<id>/media/`, hashes it during the copy and `fd.sync()`s it before it returns,
 * so a process death between two photographs leaves unreferenced bytes — a few wasted megabytes —
 * rather than a draft pointing at a file that was never finished, which is a hole in the report that
 * a designer discovers at delivery.
 *
 * ── ONE WRITE FOR THE WHOLE CONFIRMATION ──────────────────────────────────────────────────────
 *
 * The field references go in through a SINGLE [WorkshopDraftStore.update]. A write per photograph
 * would be a hundred read-modify-writes of one document, and — worse — this screen would be building
 * each from a snapshot it read before the previous one landed. The same lost-update the store's mutex
 * exists to survive, arriving by the door the mutex cannot close.
 *
 * ── DUPLICATES ARE DETECTED FROM THE HASH THE COPY ALREADY COMPUTED ───────────────────────────
 *
 * [DraftMedia.sha256] exists for exactly this — its own KDoc says so: "a designer who re-picks the
 * same photo for three stages should not carry three copies on a 32 GB phone" — and nothing was using
 * it. Re-picking the same folder is the ordinary way this screen is used twice, so the second pass
 * reuses the descriptor already on the device and drops the fresh copy. The photograph is still filed
 * where the designer asked; what it does not do is spend another six megabytes and another upload on
 * bytes the phone already holds.
 *
 * A NULL HASH IS "UNKNOWN", NEVER "UNIQUE". A descriptor written before the store computed one may
 * not be reported as a duplicate OR as distinct, so it simply takes the ordinary path.
 */
private suspend fun dwConfirmIntake(
    context: Context,
    workshopId: String,
    schema: SchemaResponse,
    chosen: List<DwIntakeLine>,
    destinations: Map<String, DwIntakeDestination>,
): DwIntakeOutcome = withContext(Dispatchers.IO) {
    /** sha256 -> the media id this device already holds for it. Read once, before any copying. */
    val known = HashMap<String, String>()
    WorkshopDraftStore.load(context, workshopId)?.media?.forEach { media ->
        media.sha256?.takeIf { it.isNotBlank() }?.let { known.putIfAbsent(it, media.id) }
    }

    val writes = ArrayList<DwIntakeWrite>()
    val unreadable = ArrayList<String>()
    val unresolved = LinkedHashSet<Uri>()
    var reused = 0

    for (line in chosen) {
        val destination = destinations[line.choice] ?: continue
        val imported = runCatching {
            WorkshopDraftStore.importMedia(
                context = context,
                workshopId = workshopId,
                uri = line.uri,
                stageId = destination.stageKey,
                fieldKey = destination.fieldKey,
            )
        }.getOrNull()
        if (imported == null) {
            // Keep going. One unreadable file out of two hundred must not cost the designer the
            // other hundred and ninety-nine, and the count below says what happened.
            unreadable.add(line.row.fileName)
            unresolved.add(line.uri)
            continue
        }

        val hash = imported.sha256?.takeIf { it.isNotBlank() }
        val twin = hash?.let { known[it] }
        val mediaId = if (twin != null && twin != imported.id) {
            runCatching { WorkshopDraftStore.removeMedia(context, workshopId, imported.id) }
            reused++
            twin
        } else {
            if (hash != null) known[hash] = imported.id
            imported.id
        }
        writes.add(DwIntakeWrite(destination, mediaId, line.uri, line.row.fileName))
    }

    val missed = ArrayList<String>()
    val grouped = writes.groupBy { it.destination.stageKey }
    if (grouped.isNotEmpty()) {
        WorkshopDraftStore.update(context, workshopId) { current ->
            // Cleared inside the transform, not outside it: the store hands this lambda whatever is
            // on disk right now, and the row it fails to find has to be judged against THAT.
            missed.clear()
            var stages = current.stages
            for ((stageKey, stageWrites) in grouped) {
                val spec = schema.stages.firstOrNull { it.key == stageKey } ?: continue
                val existing = stages[stageKey]
                var singleton = existing?.values.orEmpty()
                var rows = existing?.rows.orEmpty()
                val landed = ArrayList<String>()

                for (write in stageWrites) {
                    val target = write.destination
                    if (target.rowKey == null) {
                        singleton = singleton + (
                            target.fieldKey to DwPhotoIntake.appendMediaRef(
                                singleton[target.fieldKey], write.mediaId, target.multiple,
                            )
                            )
                        landed.add(write.mediaId)
                        continue
                    }
                    val index = rows.indexOfFirst {
                        it.entityKey() == target.entityKey && DwPhotoIntake.rowKeyOf(it.codeRow()) == target.rowKey
                    }
                    if (index < 0) {
                        // The row was deleted on another surface between this screen reading the
                        // draft and Confirm being pressed. Naming the file is the only acceptable
                        // outcome — the bytes are already in the draft store, so nothing is lost,
                        // but the designer has to be told it did not land.
                        missed.add(write.fileName)
                        unresolved.add(write.uri)
                        continue
                    }
                    val row = rows[index]
                    rows = rows.toMutableList().also { list ->
                        list[index] = row.copy(
                            values = row.values + (
                                target.fieldKey to DwPhotoIntake.appendMediaRef(
                                    row.values[target.fieldKey], write.mediaId, target.multiple,
                                )
                                )
                        )
                    }
                    landed.add(write.mediaId)
                }

                // `copy` on whatever is already there, never a fresh [StageDraft]: rebuilding the
                // record would reset `serverBaseline` and `emptiedEntities` to their class defaults
                // — quietly disclaiming a baseline a stage screen established by reading the server,
                // and quietly discarding a deletion the designer made ten seconds ago. See the same
                // note on `persistLocally` in StageScreen.kt.
                val base = existing ?: StageDraft(
                    stageId = spec.key,
                    title = spec.title,
                    order = spec.number,
                    // Stored WITH the stage so the workshop list can score completeness with no
                    // registry and no network — see the KDoc on [StageDraft.requiredKeys].
                    requiredKeys = spec.singleton?.liveFields.orEmpty().filter { it.required }.map { it.key },
                )
                stages = stages + (
                    stageKey to base.copy(
                        values = singleton,
                        rows = rows,
                        mediaIds = (base.mediaIds + landed).distinct(),
                        updatedAt = Instant.now().toString(),
                    )
                    )
            }
            current.copy(stages = stages)
        }
    }

    val attached = writes.size - missed.size
    // Counted from the URIs rather than the names, because two cards can hold two different
    // photographs called IMG_0001.JPG and a stage that landed nothing must not be counted.
    val stageCount = grouped.count { (_, stageWrites) -> stageWrites.any { it.uri !in unresolved } }
    val message = buildString {
        append("$attached photograph${if (attached == 1) "" else "s"} attached on this device")
        append(" across $stageCount stage${if (stageCount == 1) "" else "s"}. ")
        append(
            "They upload themselves when this phone next has a connection, and the copy here is kept " +
                "until the server confirms each one."
        )
        // STATED, ALWAYS. A confirmation that quietly held fewer photographs than it copied is the
        // silent-emptiness failure this repository keeps hitting.
        if (reused > 0) {
            append(
                " $reused ${if (reused == 1) "was" else "were"} already on this device — the copy " +
                    "already here was attached rather than a second one made."
            )
        }
    }
    val problem = buildList {
        if (missed.isNotEmpty()) {
            add(
                "${missed.size} could not be placed because the row they were headed for is no longer " +
                    "in this workshop: ${missed.joinToString(", ").take(200)}. They are still on this " +
                    "device — choose another destination for them."
            )
        }
        if (unreadable.isNotEmpty()) {
            add(
                "${unreadable.size} could not be read and ${if (unreadable.size == 1) "was" else "were"} " +
                    "not attached: ${unreadable.joinToString(", ").take(200)}."
            )
        }
    }.joinToString(" ").ifBlank { null }

    DwIntakeOutcome(message = message, problem = problem, unresolved = unresolved)
}

/** One photograph on its way into one field, once its bytes are durable. */
private data class DwIntakeWrite(
    val destination: DwIntakeDestination,
    val mediaId: String,
    val uri: Uri,
    val fileName: String,
)
