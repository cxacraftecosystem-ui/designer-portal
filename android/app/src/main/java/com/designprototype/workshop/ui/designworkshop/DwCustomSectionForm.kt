package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwCustomCache
import com.designprototype.workshop.data.DwCustomCopy
import com.designprototype.workshop.data.DwCustomFieldDto
import com.designprototype.workshop.data.DwCustomSectionDto
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.customFieldToFieldDto
import com.designprototype.workshop.data.customStageBlocks
import com.designprototype.workshop.data.dwCustomCopy
import com.designprototype.workshop.data.liveCustomFields
import com.designprototype.workshop.data.retiredCustomFieldsWithAnswers
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.serialization.json.JsonElement

@Composable
fun DwCustomSectionForm(
    definition: DwCustomCache?,
    stageKey: String,
    values: Map<String, JsonElement>,
    onValueChange: (String, JsonElement?) -> Unit,
    services: DwFieldServices? = null,
    errors: Map<String, String> = emptyMap(),
) {
    val blocks = remember(definition, stageKey) { customStageBlocks(definition, stageKey) }
    val copy = remember(definition) { dwCustomCopy(definition) }

    if (blocks.isEmpty()) {
        if (copy == DwCustomCopy.UNKNOWN && values.values.any { DwValues.isFilled(it) }) {
            DwCustomUnreadNote()
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        blocks.forEach { block ->
            DwCustomSectionBlock(block.section, values, onValueChange, services, errors)
        }
    }
}

@Composable
private fun DwCustomSectionBlock(
    section: DwCustomSectionDto,
    values: Map<String, JsonElement>,
    onValueChange: (String, JsonElement?) -> Unit,
    services: DwFieldServices?,
    errors: Map<String, String>,
) {
    val live = remember(section) { liveCustomFields(section) }
    val retired = remember(section, values) { retiredCustomFieldsWithAnswers(section, values) }
    val siblings = remember(live) {
        live.filter { com.designprototype.workshop.data.dwCustomFieldDrawable(it.type) }
            .associate { it.key to customFieldToFieldDto(it) }
    }

    if (live.isEmpty() && retired.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            section.title.ifBlank { section.key },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Added to this workshop by its designer.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (section.description.isNotBlank()) {
            Text(section.description, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }

        live.forEach { field ->
            DwCustomFieldRow(
                field = field,
                value = values[field.key],
                onChange = { next -> onValueChange(field.key, next) },
                error = errors[field.key],
                services = services,
                siblings = siblings,
                rowValues = values,
            )
        }

        retired.forEach { field -> DwCustomRetiredAnswer(field, values[field.key]) }
    }
}

@Composable
private fun DwCustomRetiredAnswer(field: DwCustomFieldDto, value: JsonElement?) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(field.label.ifBlank { field.key }, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        Text(DwValues.text(value), style = MaterialTheme.typography.bodyMedium)
        Text(
            "No longer asked. Earlier answer kept.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DwCustomUnreadNote() {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.field.warningContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Some answers here can't be shown yet",
                color = MaterialTheme.field.onWarningContainer,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "They're safe and will sync. Open this workshop once online to see them.",
                color = MaterialTheme.field.onWarningContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
