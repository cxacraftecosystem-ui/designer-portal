package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.ActionRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import com.designprototype.workshop.ui.FieldLinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DW_TIER2_LIST_INTRO
import com.designprototype.workshop.data.DwConnection
import com.designprototype.workshop.data.DwDeviceMeasurement
import com.designprototype.workshop.data.DwModelChoice
import com.designprototype.workshop.data.DwModelFit
import com.designprototype.workshop.data.DwTier2InstallOffer
import com.designprototype.workshop.data.DwTier2ModelStatus
import com.designprototype.workshop.data.dwModelFitLabel
import com.designprototype.workshop.data.dwModelNeedsConsent
import com.designprototype.workshop.data.dwModelOverrideConfirmLabel
import com.designprototype.workshop.data.dwModelOverrideSentence
import com.designprototype.workshop.data.dwStalledSentence
import com.designprototype.workshop.data.dwTier2ActionLabel
import com.designprototype.workshop.data.dwTier2InstallOffer
import com.designprototype.workshop.data.dwTier2RowSentence
import com.designprototype.workshop.data.dwTier2StateLine
import com.designprototype.workshop.data.dwTier2Visible
import com.designprototype.workshop.data.dwTransferHeading
import com.designprototype.workshop.data.dwTransferLine
import com.designprototype.workshop.ui.field

@Composable
internal fun DwTier2ModelList(
    choices: List<DwModelChoice>,
    measurement: DwDeviceMeasurement,
    connection: DwConnection,
    models: DwTier2ModelController,
    modifier: Modifier = Modifier,
) {
    val statuses = models.statuses
    if (!dwTier2Visible(choices, statuses)) return
    val rows = choices.mapNotNull { choice ->
        val status = statuses[choice.plan.modelId] ?: DwTier2ModelStatus()
        val offer = dwTier2InstallOffer(choice, status, connection)
        if (offer == DwTier2InstallOffer.NOT_OFFERED || offer == DwTier2InstallOffer.CHECKING) {
            null
        } else {
            Triple(choice, status, offer)
        }
    }
    if (rows.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Language models",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(DW_TIER2_LIST_INTRO, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)
        rows.forEach { (choice, status, offer) ->
            DwTier2Row(choice, status, offer, measurement, models)
        }
    }
}

@Composable
private fun DwTier2Row(
    choice: DwModelChoice,
    status: DwTier2ModelStatus,
    offer: DwTier2InstallOffer,
    measurement: DwDeviceMeasurement,
    models: DwTier2ModelController,
) {
    var confirming by remember(choice.plan.modelId) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val modelId = choice.plan.modelId
    val busyElsewhere = models.activeModelId != null && models.activeModelId != modelId
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.field.hairline, shape)
            .background(MaterialTheme.field.surface50, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                modelId,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                dwModelFitLabel(choice.fit),
                color = when (choice.fit) {
                    DwModelFit.COMFORTABLE -> MaterialTheme.field.success
                    DwModelFit.TIGHT -> MaterialTheme.field.warning
                    DwModelFit.WILL_NOT_FIT, DwModelFit.UNMEASURED -> MaterialTheme.field.muted
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(dwTier2RowSentence(choice, measurement), color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)
        dwTier2StateLine(offer, status)?.let { line ->
            Text(
                line,
                color = if (offer == DwTier2InstallOffer.RETRY) MaterialTheme.field.warning else MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (offer == DwTier2InstallOffer.IN_PROGRESS && models.activeModelId == modelId) {
            val live = models.readout
            val stage = models.phase
            stage?.let { Text(dwTransferHeading(it), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall) }
            live?.percent?.let { percent ->
                FieldLinearProgressIndicator(progress = { percent.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
            }
            live?.let { Text(dwTransferLine(it), color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall) }
            if (live != null && stage != null) {
                dwStalledSentence(live, stage)?.let { Text(it, color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall) }
            }
            ActionRow {
                OutlinedButton(onClick = { models.pause() }) { Text("Pause") }
                OutlinedButton(onClick = { models.cancel() }) { Text("Cancel") }
            }
            return@Column
        }

        val label = dwTier2ActionLabel(offer) ?: return@Column
        if (confirming && offer != DwTier2InstallOffer.INSTALLED) {
            dwModelOverrideSentence(choice)?.let { Text(it, color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall) }
            ActionRow {
                Button(
                    onClick = {
                        confirming = false
                        models.install(modelId)
                    },
                    enabled = !busyElsewhere,
                ) { Text(dwModelOverrideConfirmLabel(choice)) }
                TextButton(onClick = { confirming = false }) { Text("Not this one") }
            }
            return@Column
        }
        ActionRow {
            OutlinedButton(
                onClick = {
                    when {
                        offer == DwTier2InstallOffer.INSTALLED -> models.remove(modelId)
                        offer != DwTier2InstallOffer.RESUME && dwModelNeedsConsent(choice) -> confirming = true
                        else -> models.install(modelId)
                    }
                },
                enabled = !busyElsewhere,
            ) { Text(label) }
            if (offer == DwTier2InstallOffer.RESUME || offer == DwTier2InstallOffer.RETRY) {
                OutlinedButton(onClick = { models.cancel() }, enabled = !busyElsewhere) { Text("Cancel") }
            }
        }
    }
}
