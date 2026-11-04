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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwFieldStampDto
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.DwInspectionAttempt
import com.designprototype.workshop.data.DwInspectionDetailDto
import com.designprototype.workshop.data.DwInspectionRead
import com.designprototype.workshop.data.DwInspectionReading
import com.designprototype.workshop.data.dwSavedInspectionSentence
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageBucketDto
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.StageSchemaStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.canInspectDesignWorkshops
import com.designprototype.workshop.data.collections
import com.designprototype.workshop.data.dwCardRowTitle
import com.designprototype.workshop.data.dwInspectionFieldReading
import com.designprototype.workshop.data.dwInspectionIsReadOnly
import com.designprototype.workshop.data.dwInspectionUnansweredCount
import com.designprototype.workshop.data.liveFields
import com.designprototype.workshop.data.singleton
import com.designprototype.workshop.data.workshopDisplayTitle
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException

@Composable
fun InspectionDetailScreen(
    repository: WorkshopRepository,
    workshopId: String,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    val viewer = remember(repository) { repository.cachedUser() }
    val mayInspect = remember(viewer) { viewer != null && canInspectDesignWorkshops(viewer.role) }

    var schema by remember { mutableStateOf<SchemaResponse?>(null) }
    var detail by remember(workshopId) { mutableStateOf<DwInspectionDetailDto?>(null) }
    var loading by remember(workshopId) { mutableStateOf(mayInspect) }
    var reload by remember(workshopId) { mutableIntStateOf(0) }
    var loadError by remember(workshopId) { mutableStateOf<String?>(null) }
    var notOpen by remember(workshopId) { mutableStateOf(false) }
    var savedAt by remember(workshopId) { mutableStateOf<String?>(null) }
    var readCount by remember(workshopId) { mutableIntStateOf(0) }

    LaunchedEffect(workshopId, reload, mayInspect) {
        if (!mayInspect) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        loadError = null
        notOpen = false
        schema = runCatching { StageSchemaStore.load(appContext) }.getOrNull()
        runCatching { repository.readWorkshopUnderInspection(appContext, workshopId) }
            .onSuccess { read ->
                detail = read.detail
                savedAt = (read as? DwInspectionRead.Saved)?.savedAt
                readCount++
                if (read is DwInspectionRead.Live) {
                    runCatching { repository.syncInspectionNotes(appContext, workshopId) }
                        .getOrNull()?.refreshed?.get(workshopId)?.let { detail = it }
                    readCount++
                }
            }
            .onFailure { error ->
                val status = (error as? HttpException)?.code()
                if (status == 404) notOpen = true else loadError = error.inspectionFailure(DwInspectionAttempt.READ)
            }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!mayInspect) {
            InspectionNotice(
                "For Inspector / Reviewer accounts. Shows workshops assigned to you.",
                warning = true
            )
            return@Column
        }

        if (notOpen) {
            InspectionNotice(
                "This workshop isn't open to you.",
                warning = true
            )
            return@Column
        }

        loadError?.let { InspectionNotice(it, warning = false) }

        val record = detail
        if (record == null) {
            if (loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Text("Loading…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                OutlinedButton(onClick = { reload++ }, modifier = Modifier.fillMaxWidth()) {
                    Text("Try again")
                }
            }
            return@Column
        }

        Text(
            workshopDisplayTitle(
                record.title,
                date = record.startDate,
                workshopCode = record.workshopCode,
                clusterName = record.clusterName,
                district = record.district,
                state = record.state,
                craftName = record.craftName,
            ),
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall
        )
        val subtitle = listOfNotNull(
            record.craftName?.takeIf { it.isNotBlank() },
            record.clusterName?.takeIf { it.isNotBlank() },
            listOfNotNull(
                record.district?.takeIf { it.isNotBlank() },
                record.state?.takeIf { it.isNotBlank() },
            ).joinToString(", ").takeIf { it.isNotBlank() },
            record.workshopCode?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodyMedium)
        }

        savedAt?.let { InspectionNotice(dwSavedInspectionSentence(it), warning = true) }

        if (dwInspectionIsReadOnly(record.readOnly)) {
            InspectionNotice(
                "Read-only. Add corrections under Correction suggestions below.",
                warning = true
            )
        }

        InspectionFeedbackPanel(
            repository = repository,
            record = record,
            stages = schema?.stages.orEmpty(),
            onRecord = { detail = it },
            refreshKey = readCount,
        )

        val registry = schema
        if (registry == null || registry.stages.isEmpty()) {
            InspectionNotice(
                "Couldn't load the form layout. Update the app and reopen.",
                warning = false
            )
            return@Column
        }

        HorizontalDivider()

        registry.stages.sortedBy { it.number }.forEach { stage ->
            InspectionStage(
                schema = registry,
                stage = stage,
                bucket = record.stages[stage.key],
                requiredFilled = record.completeness[stage.key]?.requiredFilled,
                requiredTotal = record.completeness[stage.key]?.requiredTotal,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun InspectionStage(
    schema: SchemaResponse,
    stage: StageDto,
    bucket: StageBucketDto?,
    requiredFilled: Int?,
    requiredTotal: Int?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "${stage.number}. ${stage.title}",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        if (requiredTotal != null && requiredFilled != null && requiredTotal > 0) {
            Text(
                "$requiredFilled of $requiredTotal required fields answered.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        val singletonEntity = stage.singleton
        val singletonRow = bucket?.singleton.orEmpty()
        val collectionRows = stage.collections.map { entity ->
            entity to bucket?.collections?.get(entity.key).orEmpty()
        }
        val customCount = bucket?.custom.orEmpty().count { (_, value) -> DwValues.isFilled(value) }

        val singletonHasWork = singletonEntity != null &&
            singletonEntity.liveFields.any { DwValues.isFilled(singletonRow[it.key]) }
        val anythingAtAll = singletonHasWork ||
            collectionRows.any { it.second.isNotEmpty() } ||
            customCount > 0

        if (!anythingAtAll) {
            Text(
                "Nothing has been recorded on this stage." +
                    if (stage.optionalStage) {
                        " Optional stage."
                    } else {
                        ""
                    },
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
            HorizontalDivider()
            return@Column
        }

        if (singletonEntity != null && singletonHasWork) {
            InspectionRecord(
                schema = schema,
                entity = singletonEntity,
                heading = singletonEntity.title.ifBlank { singletonEntity.name },
                row = singletonRow,
                stamps = bucket?.provenance?.singleton.orEmpty(),
            )
        }

        collectionRows.forEach { (entity, rows) ->
            if (rows.isEmpty()) return@forEach
            Text(
                "${entity.title.ifBlank { entity.name }} · ${rows.size} " +
                    if (rows.size == 1) "record" else "records",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
            rows.forEachIndexed { index, row ->
                InspectionRecord(
                    schema = schema,
                    entity = entity,
                    heading = dwCardRowTitle(entity, row, index),
                    row = row,
                    stamps = bucket?.provenance?.forRow(entity.key, row.entryId()).orEmpty(),
                )
            }
        }

        if (customCount > 0) {
            Text(
                "$customCount custom ${if (customCount == 1) "answer" else "answers"} recorded.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        HorizontalDivider()
    }
}

@Composable
private fun InspectionRecord(
    schema: SchemaResponse,
    entity: EntityDto,
    heading: String,
    row: Map<String, JsonElement>,
    stamps: Map<String, DwFieldStampDto>,
) {
    val fields = entity.liveFields
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (heading.isNotBlank()) {
                Text(
                    heading,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            fields.forEach { fieldSpec ->
                when (val reading = dwInspectionFieldReading(schema, entity, fieldSpec, row)) {
                    is DwInspectionReading.Empty -> Unit
                    is DwInspectionReading.Text ->
                        InspectionValue(fieldSpec, reading.text, stamps[fieldSpec.key], media = false)
                    is DwInspectionReading.Media -> InspectionValue(
                        fieldSpec,
                        "${reading.count} ${if (reading.count == 1) "file" else "files"}.",
                        stamps[fieldSpec.key],
                        media = true,
                    )
                }
            }
            val unanswered = dwInspectionUnansweredCount(schema, entity, fields, row)
            if (unanswered > 0) {
                Text(
                    "$unanswered of ${fields.size} " +
                        (if (fields.size == 1) "field" else "fields") + " here " +
                        (if (unanswered == 1) "is" else "are") + " unanswered and " +
                        (if (unanswered == 1) "is" else "are") + " not listed above.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun InspectionValue(
    field: FieldDto,
    text: String,
    stamp: DwFieldStampDto?,
    media: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            field.label.ifBlank { field.key },
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text,
            color = if (media) MaterialTheme.field.muted else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
        stamp?.attribution()?.let {
            Text(
                it,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .background(MaterialTheme.field.surface50, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

private fun Map<String, JsonElement>.entryId(): String? =
    (this["_entryId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
