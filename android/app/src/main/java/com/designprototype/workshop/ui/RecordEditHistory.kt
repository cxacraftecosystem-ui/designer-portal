package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.RecordRevisionDto
import com.designprototype.workshop.data.RevisionChange
import com.designprototype.workshop.data.readableStamp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneId
import java.util.Locale

object RecordRevisionRedaction {

    val FIELDS: Set<String> = setOf(
        "aadhaarNumber",
        "pehchanCardNumber",
        "phone",
        "email",
        "address"
    )

    const val HAD_VALUE: String = "(value recorded)"
    const val CLEARED: String = "(cleared)"
    const val REPLACED: String = "(value replaced)"
    const val EMPTY: String = "(empty)"
    const val STILL_EMPTY: String = "(still empty)"

    val PLACEHOLDER_PAIRS: Set<Pair<String, String>> = setOf(
        EMPTY to STILL_EMPTY,
        HAD_VALUE to CLEARED,
        HAD_VALUE to REPLACED,
        EMPTY to HAD_VALUE
    )

    fun isRedactedField(field: String): Boolean = field in FIELDS

    fun isRedactedPlaceholder(field: String, old: String?, new: String?): Boolean {
        if (!isRedactedField(field)) return false
        if (old == null || new == null) return false
        return (old to new) in PLACEHOLDER_PAIRS
    }

    val SERVER_MASKED_FIELDS: Set<String> = setOf("aadhaarNumber", "pehchanCardNumber")

    fun redactedPlaceholder(old: JsonElement?, new: JsonElement?): Pair<String, String> {
        val had = !isEmptyJson(old)
        val has = !isEmptyJson(new)
        return when {
            !had && !has -> EMPTY to STILL_EMPTY
            !has -> HAD_VALUE to CLEARED
            had -> HAD_VALUE to REPLACED
            else -> EMPTY to HAD_VALUE
        }
    }

    private fun isEmptyJson(value: JsonElement?): Boolean = when {
        value == null || value is JsonNull -> true
        value is JsonPrimitive -> value.content.isBlank()
        value is JsonArray -> value.isEmpty()
        value is JsonObject -> value.isEmpty()
        else -> false
    }
}

const val RECORD_EDIT_HISTORY_TITLE: String = "Edit history"

const val RECORD_EDIT_HISTORY_CAPTION: String =
    "Identity and contact fields (Aadhaar, Pehchan card, phone, email, address) record only that " +
        "they changed, never the value. Visible to the owner and admins."

const val RECORD_EDIT_HISTORY_EMPTY: String = "No edits recorded."

const val RECORD_EDIT_HISTORY_UNKNOWN_EDITOR: String = "Unknown"

const val RECORD_EDIT_HISTORY_ABSENT: String = "—"

data class RecordRevisionChangeRow(
    val field: String,
    val old: String,
    val new: String,
    val redacted: Boolean
)

fun revisionValueText(value: JsonElement?): String {
    if (value == null || value is JsonNull) return RECORD_EDIT_HISTORY_ABSENT
    val primitive = value as? JsonPrimitive ?: return value.toString()
    return primitive.content
}

private fun revisionRawText(value: JsonElement?): String? {
    if (value == null || value is JsonNull) return null
    return (value as? JsonPrimitive)?.content
}

fun recordRevisionChangeRow(field: String, change: RevisionChange): RecordRevisionChangeRow {
    val rawOld = revisionRawText(change.old)
    val rawNew = revisionRawText(change.new)
    if (RecordRevisionRedaction.isRedactedPlaceholder(field, rawOld, rawNew)) {
        return RecordRevisionChangeRow(field, rawOld.orEmpty(), rawNew.orEmpty(), redacted = true)
    }
    if (field in RecordRevisionRedaction.SERVER_MASKED_FIELDS) {
        val (old, new) = RecordRevisionRedaction.redactedPlaceholder(change.old, change.new)
        return RecordRevisionChangeRow(field, old, new, redacted = true)
    }
    return RecordRevisionChangeRow(
        field = field,
        old = revisionValueText(change.old),
        new = revisionValueText(change.new),
        redacted = false
    )
}

fun recordRevisionRows(changes: Map<String, RevisionChange>): List<RecordRevisionChangeRow> =
    changes.map { (field, change) -> recordRevisionChangeRow(field, change) }

fun recordRevisionEditorLine(
    revision: RecordRevisionDto,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): String {
    val who = revision.editedBy?.name?.takeIf { it.isNotBlank() } ?: RECORD_EDIT_HISTORY_UNKNOWN_EDITOR
    val whenText = readableStamp(revision.createdAt, zone, locale) ?: RECORD_EDIT_HISTORY_ABSENT
    return "$who · $whenText"
}

@Composable
fun RecordEditHistorySection(
    revisions: List<RecordRevisionDto>?,
    modifier: Modifier = Modifier
) {
    val revs = revisions ?: return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            RECORD_EDIT_HISTORY_TITLE,
            display = true,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium
        )
        Text(RECORD_EDIT_HISTORY_CAPTION, color = Muted, style = MaterialTheme.typography.bodySmall, lineHeight = 15.sp)
        if (revs.isEmpty()) {
            Text(RECORD_EDIT_HISTORY_EMPTY, color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            revs.forEach { revision ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(recordRevisionEditorLine(revision), color = Muted, style = MaterialTheme.typography.bodySmall)
                    recordRevisionRows(revision.changes).forEach { row ->
                        Text(recordRevisionRowText(row), style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun recordRevisionRowText(row: RecordRevisionChangeRow): AnnotatedString {
    val fieldStyle = SpanStyle(
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold
    )
    val oldStyle = if (row.redacted) {
        SpanStyle(color = Muted, fontStyle = FontStyle.Italic)
    } else {
        SpanStyle(color = MaterialTheme.colorScheme.error, textDecoration = TextDecoration.LineThrough)
    }
    val newStyle = if (row.redacted) {
        SpanStyle(color = Muted, fontStyle = FontStyle.Italic)
    } else {
        SpanStyle(color = MaterialTheme.field.success)
    }
    return buildAnnotatedString {
        withStyle(fieldStyle) { append(row.field) }
        withStyle(SpanStyle(color = Body)) { append(": ") }
        withStyle(oldStyle) { append(row.old) }
        withStyle(SpanStyle(color = Body)) { append(" → ") }
        withStyle(newStyle) { append(row.new) }
    }
}
