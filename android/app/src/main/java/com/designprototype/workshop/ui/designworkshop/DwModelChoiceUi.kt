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
import com.designprototype.workshop.data.DwConnection
import com.designprototype.workshop.data.DwDeviceMeasurement
import com.designprototype.workshop.data.DwModelChoice
import com.designprototype.workshop.data.DwModelFit
import com.designprototype.workshop.data.dwModelChoiceIntroSentence
import com.designprototype.workshop.data.dwModelChoiceSentence
import com.designprototype.workshop.data.dwModelDownloadMayBeOffered
import com.designprototype.workshop.data.dwModelFitLabel
import com.designprototype.workshop.data.dwModelLanguagesSentence
import com.designprototype.workshop.data.dwModelNeedsConsent
import com.designprototype.workshop.data.dwModelOverrideConfirmLabel
import com.designprototype.workshop.data.dwModelOverrideSentence
import com.designprototype.workshop.ui.field

@Composable
internal fun DwModelChoiceList(
    choices: List<DwModelChoice>,
    measurement: DwDeviceMeasurement,
    connection: DwConnection,
    languageLabels: Map<String, String>,
    heading: String,
    modifier: Modifier = Modifier,
    onInstall: ((DwModelChoice) -> Unit)? = null,
) {
    if (choices.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            heading,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        dwModelChoiceIntroSentence(choices)?.let { intro ->
            Text(intro, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)
        }
        choices.forEach { choice ->
            DwModelChoiceRow(
                choice = choice,
                measurement = measurement,
                connection = connection,
                languageLabels = languageLabels,
                onInstall = onInstall,
            )
        }
    }
}

@Composable
private fun DwModelChoiceRow(
    choice: DwModelChoice,
    measurement: DwDeviceMeasurement,
    connection: DwConnection,
    languageLabels: Map<String, String>,
    onInstall: ((DwModelChoice) -> Unit)?,
) {
    val shape = RoundedCornerShape(10.dp)
    var accepting by remember(choice.plan.modelId, choice.fit) { mutableStateOf(false) }

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
                choice.plan.modelId,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (choice.suggested) {
                Text("Suggested", color = MaterialTheme.field.success, style = MaterialTheme.typography.bodySmall)
            }
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

        Text(dwModelChoiceSentence(choice, measurement), color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)

        Text(
            dwModelLanguagesSentence(choice.plan, languageLabels),
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        if (onInstall != null && dwModelDownloadMayBeOffered(choice, connection)) {
            if (accepting) {
                dwModelOverrideSentence(choice)?.let { warning ->
                    Text(warning, color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall)
                }
                ActionRow {
                    Button(onClick = { onInstall(choice) }) {
                        Text(dwModelOverrideConfirmLabel(choice))
                    }
                    TextButton(onClick = { accepting = false }) { Text("Not this one") }
                }
            } else {
                ActionRow {
                    OutlinedButton(
                        onClick = { if (dwModelNeedsConsent(choice)) accepting = true else onInstall(choice) }
                    ) {
                        Text(if (dwModelNeedsConsent(choice)) "Choose this one anyway" else "Install this model")
                    }
                }
            }
        }
    }
}
