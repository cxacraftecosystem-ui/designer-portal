package com.designprototype.workshop.ui.questionnaires

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.CustomEntryDto
import com.designprototype.workshop.data.CustomQuestionnaireDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.WorkshopListState
import com.designprototype.workshop.ui.field

internal fun mayEditQuestionnaire(repository: WorkshopRepository, ownerId: String?): Boolean =
    mayDeleteQuestionnaire(repository.cachedUser(), ownerId)

internal fun signedInUserId(repository: WorkshopRepository): String? = repository.cachedUser()?.id

internal fun isAdminAccount(repository: WorkshopRepository): Boolean =
    repository.cachedUser()?.let { FieldPermissions.isAdmin(it) } ?: false

@Composable
internal fun QChip(text: String, container: Color, content: Color) {
    Text(
        text = text,
        modifier = Modifier
            .background(container, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = content,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium
    )
}

internal fun answeredCount(entry: CustomEntryDto): Int =
    entry.answers.count { !it.answerText.isNullOrBlank() }

internal fun entrySubtitle(entry: CustomEntryDto, total: Int): String = listOfNotNull(
    "${answeredCount(entry)} of $total answered",
    entry.createdByName?.takeIf { it.isNotBlank() }?.let { "by $it" },
    when (entry.source) {
        "UPLOAD" -> "from the spreadsheet"
        "APP" -> null
        else -> entry.source?.takeIf { it.isNotBlank() }
    },
    entry.createdAt?.take(10)?.takeIf { it.isNotBlank() }
).joinToString(" · ")

internal fun activeQuestionCount(form: CustomQuestionnaireDto): Int =
    form.sections.filter { it.isActive }.sumOf { section -> section.questions.count { it.isActive } }

internal suspend fun attachableDesignWorkshops(repository: WorkshopRepository): AttachableWorkshops =
    try {
        walkAttachableDesignWorkshops { page, pageSize ->
            repository.designWorkshops(page = page, pageSize = pageSize)
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        AttachableWorkshops(
            rows = emptyList(),
            state = WorkshopListState.Failed,
            online = !repository.isTransient(failure),
        )
    }

@Composable
internal fun EmptyNote(text: String) {
    Text(text, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
}
