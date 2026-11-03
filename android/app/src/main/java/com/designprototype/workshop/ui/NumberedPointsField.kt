package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun splitNumbered(value: String?): List<String> =
    value?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: listOf("")

internal fun joinNumbered(items: List<String>): String =
    items.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

internal fun numberedRowsAfterEdit(
    rows: List<String>,
    index: Int,
    raw: String,
    rowsLocked: Boolean = false,
): List<String> {
    if (index !in rows.indices) return rows
    if (!raw.contains('\n')) return rows.toMutableList().also { it[index] = raw }
    if (rowsLocked) return rows.toMutableList().also { it[index] = raw.replace('\n', ' ') }
    val segments = raw.split('\n')
    return rows.toMutableList().also { updated ->
        updated[index] = segments.first().trim()
        updated.addAll(index + 1, segments.drop(1).map { it.trim() })
    }
}

@Composable
internal fun NumberedListInput(
    label: String,
    items: List<String>,
    error: String? = null,
    focusRequester: FocusRequester? = null,
    helper: String? = null,
    enabled: Boolean = true,
    required: Boolean = true,
    mutedLabel: Boolean = false,
    rowOverlay: (Int) -> String? = { null },
    rowTrailing: @Composable ((Int) -> Unit)? = null,
    rowsLocked: Boolean = false,
    onChange: (List<String>) -> Unit
) {
    val rows = items.ifEmpty { listOf("") }
    val trailing: @Composable ((Int) -> Unit)? = rowTrailing
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        val header = if (required) "$label *" else label
        if (mutedLabel) {
            Text(requiredMarked(header), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                requiredMarked(header),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
        }
        helper?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        rows.forEachIndexed { index, item ->
            val overlay = rowOverlay(index)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${index + 1}.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = overlay ?: item,
                    onValueChange = { raw ->
                        if (overlay == null) onChange(numberedRowsAfterEdit(rows, index, raw, rowsLocked))
                    },
                    enabled = enabled,
                    readOnly = overlay != null,
                    isError = error != null && index == 0,
                    trailingIcon = if (trailing == null) null else {
                        { trailing(index) }
                    },
                    minLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .let { if (index == 0 && focusRequester != null) it.focusRequester(focusRequester) else it }
                )
                if (rows.size > 1) {
                    IconButton(
                        enabled = enabled && !rowsLocked,
                        onClick = {
                            val updated = rows.toMutableList().also { it.removeAt(index) }
                            onChange(updated.ifEmpty { listOf("") })
                        }
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove point", tint = Muted)
                    }
                }
            }
        }
        ActionRow {
            TextButton(onClick = { onChange(rows + "") }, enabled = enabled) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add point")
            }
        }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
