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
import com.designprototype.workshop.data.DwImageDecode
import com.designprototype.workshop.data.DwIntakePhoto
import com.designprototype.workshop.data.DwPhotoGate
import com.designprototype.workshop.data.DwPhotoIntake
import com.designprototype.workshop.data.DwPhotoIntakeRow
import com.designprototype.workshop.data.DwStageData
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

/**
 * Where one confirmed photograph is going: a stage, an entity, optionally a row, and a field.
 *
 * `internal` rather than private, with [dwIntakeDestinations], so [DwIntakeDestinationsTest] can hold
 * the walk to its rules by value. It holds no Android type on purpose — a `Uri` or a `Context` here
 * would put the one decision about WHERE a photograph may go beyond the reach of every test this
 * module can run, since app/build.gradle.kts declares JUnit 4 and no Robolectric.
 */
internal data class DwIntakeDestination(
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
    /**
     * [FieldDto.maxItems] verbatim — the DECLARED ceiling of the field this photograph is headed for,
     * or 0 where the registry declares none.
     *
     * 0 IS NOT "no ceiling", and it is carried raw rather than resolved so the one place that spells
     * the fallback out stays [dwEffectiveMaxItems]. It is here because a confirmation WRITES, and
     * [multiple] told the write that a gallery holds many without saying how many: until this field
     * existed, a two-hundred-photograph camera dump appended straight past the motif galleries'
     * declared twenty. That is not a cosmetic overrun — `coerce_value` REFUSES an over-long array
     * rather than trimming it (backend/app/services/stage_schema.py:1822) and `save_stage` restores
     * the rejected key from `previous`, so the sync that followed lost the whole field's write with
     * every byte already copied into the workshop's media directory.
     *
     * Filled from [DwPhotoTarget.maxItems], which is where it comes off the registry, and defaulted so
     * that a destination built by a caller that has not been taught about it is still held to the
     * server's default rather than to nothing at all.
     */
    val maxItems: Int = 0,
)

/** One line of the list: the file, what the intake made of it, and where it is currently headed. */
private data class DwIntakeLine(
    val uri: Uri,
    val row: DwPhotoIntakeRow,
    /** The chosen destination key, or "" for "leave this one out". */
    val choice: String,
)

internal fun dwDestinationKey(stageKey: String, entityKey: String, rowKey: String?, fieldKey: String): String =
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
internal fun dwIntakeDestinations(
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
                            // The field's own ceiling travels WITH the destination, because the
                            // confirm walk has nothing but this record when it writes — see
                            // [DwIntakeDestination.maxItems].
                            maxItems = target.maxItems,
                            label = "${stage.number}. ${stage.title} — ${target.fieldLabel}",
                        )
                    )
                }
                continue
            }
            data?.collections?.get(entity.key).orEmpty().forEachIndexed { index, row ->
                /*
                 * AN EMPTY KEY IS NOT A KEY, and this is `if (!rowKey) return` from page.tsx rather
                 * than the null check that let "" through.
                 *
                 * `dwDestinationKey` folds null and "" into the same string, so two rows carrying an
                 * empty key produced ONE destination key between them: the picker would have offered
                 * two options with one value — the very thing the option list below refuses to do —
                 * and `indexOfFirst` at Confirm would have attached the photograph to whichever of
                 * them came first, silently and possibly to the wrong one. A row nothing can address
                 * contributes no destination at all, which is also what the browser decides.
                 *
                 * The ordinal below still counts EVERY row, skipped ones included, so a row named
                 * "row 3" here is the third row on both clients.
                 */
                val rowKey = DwPhotoIntake.rowKeyOf(row)?.takeIf { it.isNotEmpty() }
                    ?: return@forEachIndexed
                // [DwPhotoIntake.rowLabelOf] rather than a second spelling written here: it is the
                // port of page.tsx's own `typeof labelValue === "string" && labelValue.trim()`, and
                // it trims the way JavaScript does. See its KDoc for the no-break space this closes.
                val label = DwPhotoIntake.rowLabelOf(entity.labelField, row) ?: "row ${index + 1}"
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
                            maxItems = target.maxItems,
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

/**
 * WHAT A FULL GALLERY REFUSED, IN WORDS — AND ITS CEILING ONLY WHERE THE REGISTRY DECLARED IT.
 *
 * The intake's twin of [dwCapNotice], and a twin rather than a call of it because the two refusals
 * differ in the two things a designer acts on. The capture card trims Uris BEFORE anything is copied,
 * so all it can honestly say is how many were dropped; by the time this fires the bytes are in the
 * workshop's media directory and the rows are still on screen, so this names the FILES and says where
 * they are. Sharing one sentence would mean one of the two lying about the state of the phone.
 *
 * THE ONE CLAUSE THAT CHANGES IS THE CEILING, exactly as it is there. With a declared cap the number
 * is stated, because it came off the registry and the field's own capture card has been printing it
 * all along. With none, the ceiling in force is the server's `DW_DEFAULT_MAX_ITEMS` and this says the
 * gallery is FULL and stops: docs/DESIGN_WORKSHOP.md:229-232 forbids a client printing a number it did
 * not read, since "a stated cap that is not the enforced cap is worse than no sentence at all".
 *
 * AND IT IS NEVER TRADED FOR SILENCE. Saying nothing on an undeclared gallery — the obvious way to
 * avoid printing 200 — turns a loud refusal into a camera dump whose tail vanishes, which is the one
 * outcome that paragraph and `DwMediaCaptureCard`'s `adopt` both refuse: "the honest act is to take
 * what fits and SAY what did not". Here it is worse than on the card, because the designer confirmed
 * two hundred rows at once and has no way to work out which twenty are missing.
 *
 * [DwIntakeDestination.label] is the whole path ("13. Prototype Development — Stage logs “Warping the
 * loom” — Photographs") rather than the field's own label, because one confirmation writes into many
 * fields and "Photographs is full" would not say which of them.
 *
 * A LONG LIST IS SHORTENED BY WHOLE NAMES AND SAYS THAT IT WAS. This joined the names and then cut
 * the result with `.take(200)` until 2026-08-26, which on a forty-file refusal ended the sentence
 * mid-filename with nothing to mark it — an unmarked truncation inside the one receipt whose whole
 * job is to NAME the files rather than count them. A half-written filename is worse than an honest
 * "and 24 more": the designer goes looking for a photograph by a name that does not exist.
 *
 * `internal` and a pure function of its inputs so [DwListCapCeilingTest] can hold it to both halves of
 * the rule on a desktop JVM, where nothing composes.
 */
internal fun dwIntakeFullNotice(destination: DwIntakeDestination, fileNames: List<String>): String {
    val one = fileNames.size == 1
    val ceiling = if (destination.maxItems > 0) {
        "${destination.label} already holds the ${destination.maxItems} " +
            "photograph${if (destination.maxItems == 1) "" else "s"} it may"
    } else {
        "${destination.label} is already full"
    }
    // Whole names only, and the remainder counted rather than cut. The first name is always kept
    // even if it alone is longer than the budget: a receipt that names nothing is not a receipt.
    val shown = mutableListOf<String>()
    var used = 0
    for (name in fileNames) {
        val cost = name.length + if (shown.isEmpty()) 0 else 2
        if (shown.isNotEmpty() && used + cost > 200) break
        shown += name
        used += cost
    }
    val listed = shown.joinToString(", ") +
        if (shown.size < fileNames.size) " and ${fileNames.size - shown.size} more" else ""
    return "$ceiling, so ${fileNames.size} ${if (one) "was" else "were"} not attached: " +
        "$listed. ${if (one) "It is" else "They are"} still on this " +
        "device and still in the list below — remove something from that field first, or choose " +
        "another destination."
}

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
 *
 * THE WEB DOES NOT DO THIS, and the asymmetry is deliberate rather than drift. `stageLocalMedia`
 * stages every file it is handed, and `docs/MEDIA_PIPELINE.md` §5 lists checksum dedupe as "noted,
 * not built" — but what it declines there is a SERVER lookup, which needs an endpoint and a policy
 * for what one file in two records means. This is neither: both references name bytes this handset
 * already holds, so no fact about the workshop changes, and the phone is the client where a duplicate
 * six-megabyte copy costs storage that runs out. The count is stated in the message for that reason —
 * a saving the designer cannot see is indistinguishable from a photograph that went missing.
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
    /**
     * Chosen a destination the list no longer offers — named, never skipped.
     *
     * `continue` alone was the whole handling here, and it is the silent-emptiness failure wearing a
     * bulk importer's clothes: the caller drops every row whose photograph is not in [unresolved], so
     * a `continue` would take the row off the screen having attached nothing and said nothing. The
     * designer would find the gap at delivery. Reachable whenever the destination list is rebuilt
     * between choosing and confirming — it is remembered on the draft, which a Confirm itself
     * replaces — so it costs one branch to make impossible rather than merely unlikely.
     *
     * A DIVERGENCE FROM THE WEB, NAMED RATHER THAN QUIET. `.../[id]/photos/page.tsx` still writes
     * `if (!destination) continue;` and drops the row the same way. Diverging is right here because
     * the two clients do not disagree about any ANSWER — not one photograph is attached differently —
     * only about whether a photograph that went nowhere is mentioned, and the rule that the web wins
     * is about computed results, not about staying quiet in the same places. The web should take the
     * same branch; until it does, this client says more than its laptop does and never less.
     */
    val unplaceable = ArrayList<String>()

    /**
     * Turned away by [DwPhotoGate] — BEFORE the copy, exactly as the capture card does it.
     *
     * ── WHY THE BULK PATH IS GATED TOO, WHICH IS NOT AN OBVIOUS CALL ─────────────────────────
     *
     * This is the OTHER door into a workshop's galleries, and until it was gated it was the wider
     * one: the capture card takes photographs one and five at a time, while this takes two hundred
     * off a camera dump in one confirmation. Leaving it open would have made the registry's own help
     * text — "Each photograph is checked on this device before it uploads … and one that fails is
     * not sent" — false for the path a designer is most likely to fill a motif gallery through. A
     * sentence on screen that is true of one route and not the other is worse than no sentence.
     *
     * ── WHAT IT COSTS, STATED RATHER THAN DISCOVERED ─────────────────────────────────────────
     *
     * A measurement is a few hundred milliseconds and sometimes approaches a second (see
     * [DwImageDecode]'s measured budget), so a two-hundred-photograph confirmation now takes roughly
     * twice as long as it did — it was already copying two hundred files byte by byte with an
     * `fd.sync()` each. It runs inside the same `Dispatchers.IO` block and the same `confirming`
     * flag, so nothing about the screen's shape changes; what changes is the wait. If that wait ever
     * needs a counter, the loop is already sequential for the reason the reading pass above is.
     *
     * ── AND THE ORDER IS GATE FIRST, IMPORT SECOND ───────────────────────────────────────────
     *
     * A refused photograph is never copied, so it consumes no storage on a phone that is about to
     * receive a hundred and ninety more, gets no descriptor, and reaches no field. It stays in the
     * list below with its chosen destination intact — the same treatment as a row whose destination
     * vanished — so a designer can retake it and confirm again.
     */
    val refusedByGate = ArrayList<DwPhotoGate.RefusedPhoto>()
    var reused = 0

    for (line in chosen) {
        val destination = destinations[line.choice]
        if (destination == null) {
            unplaceable.add(line.row.fileName)
            unresolved.add(line.uri)
            continue
        }
        val screened = DwImageDecode.screen(context.contentResolver, line.uri)
        if (screened != null) {
            /*
              JUDGED AGAINST NOTHING, AND THAT IS DELIBERATE HERE.

              `attached` is left empty, so the duplicate arm never fires on this path. Two reasons,
              and neither is laziness. This screen already has a BETTER duplicate rule than the gate
              does — it reuses the descriptor of any photograph whose SHA-256 the device already
              holds, anywhere in the workshop, rather than making a second copy — so refusing a
              duplicate here would replace a saving with a refusal. And the gate's rule is
              per-FIELD, while a camera dump is spread across a dozen fields at once; a photograph
              legitimately headed for two different stages would be turned away on the second.
              Blur and resolution are properties of the file alone and need no context at all.
            */
            val verdict = DwPhotoGate.judge(measurement = screened.measurement)
            if (!verdict.admitted) {
                refusedByGate.add(DwPhotoGate.RefusedPhoto(line.row.fileName, verdict.faults))
                unresolved.add(line.uri)
                continue
            }
        }
        // A photograph this device cannot measure is admitted, with no finding and no refusal — the
        // same fail-open [DwPhotoGate]'s header requires, reached here by the null check above.
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
    /**
     * Copied, and then refused by the field's own ceiling — SAID, never counted as attached.
     *
     * The gallery this photograph was headed for is already holding as many as it may, and the write
     * below declines it: `coerce_value` REFUSES an over-long array rather than trimming it
     * (backend/app/services/stage_schema.py:1822) and `save_stage` restores the rejected key from
     * `previous`, so appending anyway would not cost the designer the surplus photographs — it would
     * cost the whole field's write at the next sync, with every byte already on the phone.
     *
     * WHY IT IS A LIST AND NOT A COUNTER. A refusal that is not spoken is the silent drop
     * docs/DESIGN_WORKSHOP.md:229-232 and `DwMediaCaptureCard`'s `adopt` both refuse in as many words
     * — "the honest act is to take what fits and SAY what did not" — and a camera dump whose tail
     * vanishes is that failure at its worst, because the designer has two hundred rows and no way to
     * tell which twenty did not land. So the whole write is kept: [dwIntakeFullNotice] needs the
     * destination to name the field and to decide whether it may print the ceiling, and the file name
     * to say which photograph.
     *
     * Judged against the draft the store hands the transform, exactly as [missed] is, and cleared in
     * the same place for the same reason.
     */
    val overCap = ArrayList<DwIntakeWrite>()
    val grouped = writes.groupBy { it.destination.stageKey }
    if (grouped.isNotEmpty()) {
        WorkshopDraftStore.update(context, workshopId) { current ->
            // Cleared inside the transform, not outside it: the store hands this lambda whatever is
            // on disk right now, and the row it fails to find — or the gallery it finds already full
            // — has to be judged against THAT.
            missed.clear()
            overCap.clear()
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
                        /*
                         * ASKED BEFORE IT IS WRITTEN, BECAUSE THE ANSWER HAS TO BE SAID.
                         *
                         * [DwPhotoIntake.appendMediaRef] stops at the field's ceiling and returns the
                         * list unchanged when it will not take another — it hands back a value, not a
                         * receipt, so `landed.add` below would count a photograph the draft does not
                         * hold and put its id into `StageDraft.mediaIds` with nothing referencing it.
                         * [DwPhotoIntake.mediaRefFits] is the same question asked in a statement that
                         * shows up in a diff. It answers TRUE for a single-valued field and for a ref
                         * the gallery already holds, so the only refusal it reports is real growth
                         * past the ceiling — the one a receipt has to account for.
                         *
                         * The ceiling is the DECLARED one where the registry gave it and the server's
                         * default where it did not; the branch has to pass [target.maxItems] to get
                         * the first, because [dwEffectiveMaxItems] reads 0 as the second.
                         */
                        val held = singleton[target.fieldKey]
                        if (!DwPhotoIntake.mediaRefFits(held, write.mediaId, target.multiple, target.maxItems)) {
                            overCap.add(write)
                            unresolved.add(write.uri)
                            continue
                        }
                        singleton = singleton + (
                            target.fieldKey to DwPhotoIntake.appendMediaRef(
                                held, write.mediaId, target.multiple, target.maxItems,
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
                    // The same ceiling question as the singleton branch above, asked against the row's
                    // own copy of the field. Both branches need it and for different reasons: the
                    // galleries that DECLARE a cap sit on a singleton entity, while a collection row's
                    // gallery is held to the server's default — which is a ceiling too, and the one a
                    // camera dump aimed at a single row would reach.
                    val heldInRow = row.values[target.fieldKey]
                    if (!DwPhotoIntake.mediaRefFits(heldInRow, write.mediaId, target.multiple, target.maxItems)) {
                        overCap.add(write)
                        unresolved.add(write.uri)
                        continue
                    }
                    rows = rows.toMutableList().also { list ->
                        list[index] = row.copy(
                            values = row.values + (
                                target.fieldKey to DwPhotoIntake.appendMediaRef(
                                    heldInRow, write.mediaId, target.multiple, target.maxItems,
                                )
                                )
                        )
                    }
                    landed.add(write.mediaId)
                }

                // `copy` on whatever is already there, never a fresh [StageDraft]: rebuilding the
                // record would reset `stageSeen` and `emptiedEntities` to their class defaults
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

    // EVERY WAY A COPIED PHOTOGRAPH CAN FAIL TO LAND COMES OFF THIS TOTAL. `writes` counts what was
    // copied, not what was written: a row that went missing under the transform and a gallery that
    // was already at its ceiling both leave the bytes on the device with no field referencing them.
    // Counting them as attached is how a receipt comes to claim more than the draft holds — and this
    // screen's whole job is to be believed about a two-hundred-file import.
    val attached = writes.size - missed.size - overCap.size
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
        // ONE SENTENCE PER FULL GALLERY, and grouped rather than pooled because the remedy is
        // per-field: "remove something first" is only actionable if the designer knows WHICH field to
        // remove it from, and one import can fill two of them. `groupBy` keeps the order the writes
        // were made in, so the sentences read in the order the list did.
        overCap.groupBy { it.destination }.forEach { (destination, refused) ->
            add(dwIntakeFullNotice(destination, refused.map { it.fileName }))
        }
        // Worded apart from `missed` rather than folded into it, because these were never copied:
        // saying "they are still on this device" of a file this app has not touched would send a
        // designer looking in the wrong place for it.
        if (unplaceable.isNotEmpty()) {
            val one = unplaceable.size == 1
            add(
                "${unplaceable.size} ${if (one) "was" else "were"} headed for a place this workshop no " +
                    "longer offers, so ${if (one) "it was" else "they were"} not attached: " +
                    "${unplaceable.joinToString(", ").take(200)}. ${if (one) "It is" else "They are"} " +
                    "still in the list below — choose again."
            )
        }
        if (unreadable.isNotEmpty()) {
            add(
                "${unreadable.size} could not be read and ${if (unreadable.size == 1) "was" else "were"} " +
                    "not attached: ${unreadable.joinToString(", ").take(200)}."
            )
        }
        /*
          WHAT THE QUALITY GATE TURNED AWAY, NAMED FILE BY FILE AND REASON BY REASON.

          NOT TRUNCATED at 200 characters as its neighbours are, and the difference is deliberate:
          the sentences beside it name a list of FILES, where the head of the list is a fair sample
          of the whole, and one of them is joined from up to two hundred names. Each line here is a
          different photograph with a different measured reading, and cutting the list mid-sentence
          would leave a designer holding a receipt that names four soft photographs and stops in the
          middle of the fifth. The heading counts them all either way, so nothing is hidden by
          keeping the lines whole.
        */
        if (refusedByGate.isNotEmpty()) {
            add(DwPhotoGate.refusalHeading(refusedByGate.size))
            addAll(DwPhotoGate.refusalLines(refusedByGate))
            add(DwPhotoGate.scopeSentence())
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
