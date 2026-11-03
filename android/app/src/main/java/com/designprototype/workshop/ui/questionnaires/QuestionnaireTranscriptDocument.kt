package com.designprototype.workshop.ui.questionnaires

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.ui.Text
import kotlinx.coroutines.delay

fun transcriptDocumentFileName(base: String, extension: String): String {
    val cleaned = base
        .replace(Regex("[\\\\/:*?\"<>|]"), "-")
        .replace(Regex("\\s+"), "-")
        .replace(Regex("-+"), "-")
        .trim('-')
        .take(60)
        .let { if (it.isNotEmpty() && it.last().isHighSurrogate()) it.dropLast(1) else it }
    return "${cleaned.ifBlank { "transcript" }}.$extension"
}

const val TRANSCRIPT_DOCUMENT_EXTENSION: String = "md"
const val TRANSCRIPT_DOCUMENT_MIME: String = "text/markdown"

@Composable
fun QuestionnaireTranscriptActions(
    text: String,
    filenameBase: String,
    onSave: ((fileName: String) -> Unit)?,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        leading()
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = {
                clipboard.setText(AnnotatedString(text))
                copied = true
            },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Icon(
                if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(if (copied) "Copied" else "Copy", style = MaterialTheme.typography.labelMedium)
        }
        if (onSave != null) {
            TextButton(
                onClick = {
                    onSave(transcriptDocumentFileName(filenameBase, TRANSCRIPT_DOCUMENT_EXTENSION))
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Download", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
