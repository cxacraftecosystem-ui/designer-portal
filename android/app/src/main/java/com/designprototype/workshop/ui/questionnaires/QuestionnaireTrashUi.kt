package com.designprototype.workshop.ui.questionnaires

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * SOFT DELETE AND RESTORE FOR CUSTOM QUESTIONNAIRES ON ANDROID (audit L9, 21 Oct 2026).
 *
 * The server has soft-deleted forms and sittings since 20 Oct and the web offers a trash; the
 * handset offered neither. A form is deleted and restored by its author or a content admin
 * ([mayDeleteQuestionnaire], owner 22 Oct 2026); a sitting by a content admin alone
 * ([mayDeleteQuestionnaireItems]).
 *
 * The words live in [QuestionnaireTrashCopy] so a JVM test pins them.
 */
object QuestionnaireTrashCopy {
    const val QUESTIONNAIRE_BODY =
        "Moves this questionnaire to Deleted. Its sittings and answers are kept, and its author or an admin can restore it."
    const val QUESTIONNAIRE_CONFIRM = "Its sittings and answers are kept. Its author or an admin can restore it."
    const val SITTING_CONFIRM_TITLE = "Delete this sitting?"
    const val SITTING_CONFIRM = "Its answers are kept. An admin can restore it."
    const val DELETED_QUESTIONNAIRES = "Deleted questionnaires"
    const val YOUR_DELETED_QUESTIONNAIRES = "Your deleted questionnaires"
    const val DELETED_SITTINGS = "Deleted sittings"
    const val NOTHING_DELETED = "Nothing here."
    const val RESTORE = "Restore"

    /** "Deleted 21 Oct 2026 by Meera" — the date as the server sent it, its first ten characters. */
    fun deletedLine(deletedAt: String?, deletedBy: String?): String = listOfNotNull(
        deletedAt?.takeIf { it.isNotBlank() }?.let { "Deleted ${it.take(10)}" } ?: "Deleted",
        deletedBy?.takeIf { it.isNotBlank() }?.let { "by $it" },
    ).joinToString(" ")
}

/** One row of a trash: what it was, who deleted it and when, and a single Restore. */
@Composable
private fun TrashRow(title: String, line: String, busy: Boolean, onRestore: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(line, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onRestore, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(QuestionnaireTrashCopy.RESTORE)
        }
    }
}

/** A collapsed card that lists a trash only when opened, so the list screen reads nothing extra. */
@Composable
private fun <T> TrashCard(
    heading: String,
    reloadKey: Any?,
    load: suspend () -> List<T>,
    title: (T) -> String,
    line: (T) -> String,
    restore: suspend (T) -> Unit,
    onRestored: (T) -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<T>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(open, reload, reloadKey) {
        if (!open) return@LaunchedEffect
        rows = runCatching { load() }
            .onFailure { if (it !is CancellationException) onError(it.apiErrorMessage("The deleted items could not be read.")) }
            .getOrNull()
    }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(heading, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
            }
            if (open) {
                val list = rows
                when {
                    list == null -> EmptyNote("Loading…")
                    list.isEmpty() -> EmptyNote(QuestionnaireTrashCopy.NOTHING_DELETED)
                    else -> list.forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.field.hairline)
                        TrashRow(title(row), line(row), busy) {
                            if (busy) return@TrashRow
                            busy = true
                            scope.launch {
                                runCatching { restore(row) }
                                    .onSuccess { reload++; onRestored(row) }
                                    .onFailure { if (it !is CancellationException) onError(it.apiErrorMessage("That could not be restored.")) }
                                busy = false
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The questionnaire trash on the list screen. A content admin's lists every deleted form; anyone
 * else's ([ownOnly]) lists the forms they authored, which the server scopes and lets them restore.
 */
@Composable
fun DeletedQuestionnairesCard(
    repository: WorkshopRepository,
    onRestored: () -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
    ownOnly: Boolean = false,
) {
    TrashCard(
        heading = if (ownOnly) QuestionnaireTrashCopy.YOUR_DELETED_QUESTIONNAIRES else QuestionnaireTrashCopy.DELETED_QUESTIONNAIRES,
        reloadKey = null,
        load = { repository.deletedCustomQuestionnaires() },
        title = { it.title.ifBlank { "Untitled questionnaire" } },
        line = { QuestionnaireTrashCopy.deletedLine(it.deletedAt, it.deletedByName) },
        restore = { repository.restoreCustomQuestionnaire(it.id) },
        onRestored = { onMessage("“${it.title.ifBlank { "Questionnaire" }}” restored."); onRestored() },
        onError = onError,
    )
}

/** One questionnaire's deleted sittings, on its detail screen. Content admins only. */
@Composable
fun DeletedSittingsCard(
    repository: WorkshopRepository,
    questionnaireId: String,
    reloadKey: Any?,
    onRestored: () -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    TrashCard(
        heading = QuestionnaireTrashCopy.DELETED_SITTINGS,
        reloadKey = reloadKey,
        load = { repository.deletedCustomEntries(questionnaireId) },
        title = { it.respondentName?.takeIf { name -> name.isNotBlank() } ?: it.title.ifBlank { "Sitting" } },
        line = { QuestionnaireTrashCopy.deletedLine(it.deletedAt, it.deletedByName) },
        restore = { repository.restoreCustomEntry(questionnaireId, it.id) },
        onRestored = { onMessage("Sitting restored."); onRestored() },
        onError = onError,
    )
}
