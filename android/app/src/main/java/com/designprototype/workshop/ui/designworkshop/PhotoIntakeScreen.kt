package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.userMessage

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
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
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
import coil3.compose.AsyncImage
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
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

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
    var reading by remember(workshopId) { mutableStateOf<Pair<Int, Int>?>(null) }
    var confirming by remember(workshopId) { mutableStateOf(false) }
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
            onError(it.userMessage("Unable to read this workshop on this device."))
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
    val destinationsByAnchor = remember(destinations) {
        destinations.groupBy { Triple(it.stageKey, it.entityKey, it.rowKey) }
    }

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
                lines = lines.filter { it.choice.isEmpty() || it.uri in result.unresolved }
                onMessage(result.message)
                result.problem?.let(onError)
            }.onFailure {
                onError(
                    "Couldn't save to storage; nothing attached. Free space and retry."
                )
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Import photographs", display = true, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Match camera photos to this workshop's dates, then confirm.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 17.sp
        )

        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Reading this workshop…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }

        if (missingDraft) {
            DwWorkshopNotice(
                "Open this workshop's stages once online first."
            )
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface50, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("How the dates are read", display = true, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
            Text(
                "Times are read as ${DwPhotoIntake.DEFAULT_TIMEZONE}. Nothing is attached until you confirm.",
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 18.sp
            )
            Text(
                if (anchors.isNotEmpty()) {
                    "Matching against ${anchors.size} dated ${if (anchors.size == 1) "entry" else "entries"}."
                } else {
                    "No dates yet. Add start and end dates on stage 1."
                },
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
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
            "Files aren't copied until you confirm.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        reading?.let { (done, total) ->
            Text(
                "Reading capture dates — $done of $total.",
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

        if (lines.isEmpty()) return@Column

        HorizontalDivider()

        val summary = DwPhotoIntake.intakeSummary(lines.map { it.row })
        val ready = lines.count { it.choice.isNotEmpty() }
        Text(
            "${summary.total} chosen · $ready ready to attach · ${summary.total - ready} need you",
            color = if (ready < summary.total) MaterialTheme.field.warning else MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
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
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        line.row.stamp?.let { "Taken ${DwPhotoIntake.formatStamp(it)}" } ?: "No capture date",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        destination?.label ?: "Left out — choose a stage to attach it",
                        color = if (destination != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.field.warning
                        },
                        style = MaterialTheme.typography.bodySmall,
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

            if (expanded) {

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
                    style = MaterialTheme.typography.bodySmall,
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

                val options = proposed.map { SelectOption(it.key, it.label, "Proposed from the capture date") } +
                    allDestinations
                        .filterNot { candidate -> proposed.any { it.key == candidate.key } }
                        .map { SelectOption(it.key, it.label) }
                if (options.isEmpty()) {
                    Text(
                        "This workshop has no photograph fields to attach to yet.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    SearchableSelectField(
                        label = "Attach to",
                        options = options,
                        selectedValue = line.choice,
                        placeholder = "Leave out — I will attach this one myself",
                        enabled = enabled,
                        onSelect = onChoose
                    )
                }
            }
        }
    }
}

internal data class DwIntakeDestination(
    val key: String,
    val stageKey: String,
    val stageNumber: Int,
    val stageTitle: String,
    val entityKey: String,
    val rowKey: String?,
    val fieldKey: String,
    val label: String,
    val multiple: Boolean,
    val maxItems: Int = 0,
)

private data class DwIntakeLine(
    val uri: Uri,
    val row: DwPhotoIntakeRow,
    val choice: String,
)

internal fun dwDestinationKey(stageKey: String, entityKey: String, rowKey: String?, fieldKey: String): String =
    "$stageKey|$entityKey|${rowKey.orEmpty()}|$fieldKey"

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
                            maxItems = target.maxItems,
                            label = "${stage.number}. ${stage.title} — ${target.fieldLabel}",
                        )
                    )
                }
                continue
            }
            data?.collections?.get(entity.key).orEmpty().forEachIndexed { index, row ->
                val rowKey = DwPhotoIntake.rowKeyOf(row)?.takeIf { it.isNotEmpty() }
                    ?: return@forEachIndexed
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

internal fun dwIntakeFullNotice(destination: DwIntakeDestination, fileNames: List<String>): String {
    val one = fileNames.size == 1
    val ceiling = if (destination.maxItems > 0) {
        "${destination.label} already holds the ${destination.maxItems} " +
            "photograph${if (destination.maxItems == 1) "" else "s"} it may"
    } else {
        "${destination.label} is already full"
    }
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

private data class DwIntakeOutcome(
    val message: String,
    val problem: String?,
    val unresolved: Set<Uri>,
)

private suspend fun dwConfirmIntake(
    context: Context,
    workshopId: String,
    schema: SchemaResponse,
    chosen: List<DwIntakeLine>,
    destinations: Map<String, DwIntakeDestination>,
): DwIntakeOutcome = withContext(Dispatchers.IO) {
    val known = HashMap<String, String>()
    WorkshopDraftStore.load(context, workshopId)?.media?.forEach { media ->
        media.sha256?.takeIf { it.isNotBlank() }?.let { known.putIfAbsent(it, media.id) }
    }

    val writes = ArrayList<DwIntakeWrite>()
    val unreadable = ArrayList<String>()
    val unresolved = LinkedHashSet<Uri>()
    val unplaceable = ArrayList<String>()

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
            val verdict = DwPhotoGate.judge(measurement = screened.measurement)
            if (!verdict.admitted) {
                refusedByGate.add(DwPhotoGate.RefusedPhoto(line.row.fileName, verdict.faults))
                unresolved.add(line.uri)
                continue
            }
        }
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
    val overCap = ArrayList<DwIntakeWrite>()
    val grouped = writes.groupBy { it.destination.stageKey }
    if (grouped.isNotEmpty()) {
        WorkshopDraftStore.update(context, workshopId) { current ->
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
                        missed.add(write.fileName)
                        unresolved.add(write.uri)
                        continue
                    }
                    val row = rows[index]
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

                val base = existing ?: StageDraft(
                    stageId = spec.key,
                    title = spec.title,
                    order = spec.number,
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

    val attached = writes.size - missed.size - overCap.size
    val stageCount = grouped.count { (_, stageWrites) -> stageWrites.any { it.uri !in unresolved } }
    val message = buildString {
        append("$attached photo${if (attached == 1) "" else "s"} attached")
        append(" across $stageCount stage${if (stageCount == 1) "" else "s"}. ")
        append(
            "Uploads when you're online."
        )
        if (reused > 0) {
            append(
                " $reused already here, reused."
            )
        }
    }
    val problem = buildList {
        if (missed.isNotEmpty()) {
            add(
                "${missed.size} couldn't be placed (row removed): ${missed.joinToString(", ").take(200)}. Choose another destination."
            )
        }
        overCap.groupBy { it.destination }.forEach { (destination, refused) ->
            add(dwIntakeFullNotice(destination, refused.map { it.fileName }))
        }
        if (unplaceable.isNotEmpty()) {
            val one = unplaceable.size == 1
            add(
                "${unplaceable.size} not attached (destination removed): ${unplaceable.joinToString(", ").take(200)}. Choose again below."
            )
        }
        if (unreadable.isNotEmpty()) {
            add(
                "${unreadable.size} unreadable, not attached: ${unreadable.joinToString(", ").take(200)}."
            )
        }
        if (refusedByGate.isNotEmpty()) {
            add(DwPhotoGate.refusalHeading(refusedByGate.size))
            addAll(DwPhotoGate.refusalLines(refusedByGate))
            add(DwPhotoGate.scopeSentence())
        }
    }.joinToString(" ").ifBlank { null }

    DwIntakeOutcome(message = message, problem = problem, unresolved = unresolved)
}

private data class DwIntakeWrite(
    val destination: DwIntakeDestination,
    val mediaId: String,
    val uri: Uri,
    val fileName: String,
)
