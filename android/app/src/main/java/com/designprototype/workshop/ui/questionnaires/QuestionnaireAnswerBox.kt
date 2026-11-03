package com.designprototype.workshop.ui.questionnaires

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.report.fromJson
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.designworkshop.RichTextEditor
import com.designprototype.workshop.ui.field

@Composable
fun QuestionnaireAnswerBox(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = "Answer",
    resetKey: Any? = null,
    onError: (String) -> Unit = {},
) {
    var seed by remember(resetKey) { mutableStateOf(questionnaireAnswerSeed(value)) }
    var mine by remember(resetKey) { mutableStateOf(value) }
    var dictationError by remember(resetKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(value, resetKey) {
        if (value != mine) {
            seed = questionnaireAnswerSeed(value)
            mine = value
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RichTextEditor(
            value = seed,
            onChange = { next ->
                val stored = questionnaireAnswerStored(fromJson(next))
                mine = stored
                onValueChange(stored)
            },
            enabled = enabled,
            label = label,
            onError = { message ->
                dictationError = message
                onError(message)
            },
        )
        dictationError?.let {
            Text(it, color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall)
        }
    }
}
