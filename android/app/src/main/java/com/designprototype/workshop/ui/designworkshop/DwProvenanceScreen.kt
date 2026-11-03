package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.ReadCache
import com.designprototype.workshop.data.readCacheNotice
import com.designprototype.workshop.data.DW_PROVENANCE_ALL_MATCH
import com.designprototype.workshop.data.DW_PROVENANCE_COLUMN_CANONICAL
import com.designprototype.workshop.data.DW_PROVENANCE_COLUMN_FIELD
import com.designprototype.workshop.data.DW_PROVENANCE_COLUMN_STORED
import com.designprototype.workshop.data.DW_PROVENANCE_LOADING
import com.designprototype.workshop.data.DW_PROVENANCE_LOAD_FAILED
import com.designprototype.workshop.data.DW_PROVENANCE_NOT_AN_ERROR_EMPHASIS
import com.designprototype.workshop.data.DW_PROVENANCE_NOT_AN_ERROR_PREFIX
import com.designprototype.workshop.data.DW_PROVENANCE_NOT_AN_ERROR_SUFFIX
import com.designprototype.workshop.data.DW_PROVENANCE_TITLE
import com.designprototype.workshop.data.DwProvenanceEntryDto
import com.designprototype.workshop.data.DwProvenanceReportDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.dwCanonicalText
import com.designprototype.workshop.data.dwComparisonText
import com.designprototype.workshop.data.dwDivergedFields
import com.designprototype.workshop.data.dwDivergenceHeadline
import com.designprototype.workshop.data.dwDivergenceTally
import com.designprototype.workshop.data.dwProvenanceEntryTitle
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import retrofit2.HttpException

@Composable
fun DwProvenanceScreen(
    repository: WorkshopRepository,
    workshopId: String,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    val viewer = remember(repository) { repository.cachedUser() }
    val mayAudit = remember(viewer) { mayReadWorkshopProvenance(viewer) }

    var loading by remember(workshopId) { mutableStateOf(mayAudit) }
    var reload by remember(workshopId) { mutableIntStateOf(0) }
    var report by remember(workshopId) { mutableStateOf<DwProvenanceReportDto?>(null) }
    var registry by remember(workshopId) { mutableStateOf<SchemaResponse?>(null) }
    var loadError by remember(workshopId) { mutableStateOf<String?>(null) }
    var cachedNote by remember(workshopId) { mutableStateOf<String?>(null) }
    var onServer by remember(workshopId) { mutableStateOf(true) }

    LaunchedEffect(workshopId, reload, mayAudit) {
        if (!mayAudit) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        loadError = null

        val remoteId = WorkshopDraftStore.load(appContext, workshopId)?.remoteId
            ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
        if (remoteId == null) {
            onServer = false
            loading = false
            return@LaunchedEffect
        }
        onServer = true

        runCatching { repository.designWorkshopSchema(appContext) }
            .onSuccess { registry = it }

        cachedNote = null
        runCatching {
            ReadCache.read(appContext, "provenance:$remoteId", DwProvenanceReportDto.serializer()) {
                repository.designWorkshopProvenance(remoteId)
            }.also { read -> cachedNote = if (read.fromCache) readCacheNotice(read.cachedAt) else null }.value
        }
            .onSuccess {
                report = it
                loadError = null
            }
            .onFailure { error ->
                report = null
                loadError = error.provenanceFailure()
            }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            DW_PROVENANCE_TITLE,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall
        )

        if (!mayAudit) {
            Text(
                "Comparing with shared records is admin-only. Who set each field is shown under every box.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
            return@Column
        }

        if (!onServer) {
            Text(
                "Upload this workshop first to compare.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
            return@Column
        }

        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text(DW_PROVENANCE_LOADING, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }

        val served = report
        cachedNote?.let { com.designprototype.workshop.ui.CachedCopyNote(it) }
        if (served == null) {
            Text(
                loadError ?: DW_PROVENANCE_LOAD_FAILED,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(onClick = { reload++ }, modifier = Modifier.fillMaxWidth()) {
                Text("Try again")
            }
            return@Column
        }

        val tally = remember(served) { dwDivergenceTally(served) }

        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    dwDivergenceHeadline(tally),
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    buildAnnotatedString {
                        append(DW_PROVENANCE_NOT_AN_ERROR_PREFIX)
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(DW_PROVENANCE_NOT_AN_ERROR_EMPHASIS)
                        }
                        append(DW_PROVENANCE_NOT_AN_ERROR_SUFFIX)
                    },
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
                if (tally.nothingHasMoved) {
                    Text(DW_PROVENANCE_ALL_MATCH, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (!tally.nothingHasMoved) {
            served.entries.forEach { entry ->
                DwDivergenceCard(entry = entry, registry = registry)
            }
        }
    }
}

@Composable
private fun DwDivergenceCard(entry: DwProvenanceEntryDto, registry: SchemaResponse?) {
    val moved = remember(entry) { dwDivergedFields(entry) }
    if (moved.isEmpty()) return

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                dwProvenanceEntryTitle(entry, registry),
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                ColumnHeading(DW_PROVENANCE_COLUMN_FIELD, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                ColumnHeading(DW_PROVENANCE_COLUMN_STORED, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                ColumnHeading(DW_PROVENANCE_COLUMN_CANONICAL, Modifier.weight(1f))
            }

            moved.forEach { (fieldKey, comparison) ->
                HorizontalDivider(color = MaterialTheme.field.hairline)
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(fieldKey, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodyMedium)
                        entry.fields[fieldKey]?.attribution()?.let { line ->
                            Text(line, color = MaterialTheme.field.placeholder, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        dwComparisonText(comparison.stored),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        dwCanonicalText(comparison),
                        color = if (comparison.recordDeleted) {
                            MaterialTheme.field.muted
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnHeading(label: String, modifier: Modifier) {
    Text(
        label,
        color = MaterialTheme.field.muted,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        modifier = modifier
    )
}

internal fun mayReadWorkshopProvenance(user: UserDto?): Boolean =
    user != null && FieldPermissions.isAdmin(user)

private fun Throwable.provenanceFailure(): String {
    val status = (this as? HttpException)?.code()
    val served = apiErrorMessage("").takeIf { it.isNotBlank() && !it.startsWith("HTTP ") }
    return when (status) {
        403 -> served
            ?: "Admin-only. Ask a master admin if you need it."
        404 -> "Not found online. Nothing was deleted here."
        null -> "Offline — needs a connection."
        else -> served ?: DW_PROVENANCE_LOAD_FAILED
    }
}
