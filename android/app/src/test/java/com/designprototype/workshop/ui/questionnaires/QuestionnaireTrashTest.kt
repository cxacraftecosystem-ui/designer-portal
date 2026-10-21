package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.CustomQuestionnaireSummaryDto
import com.designprototype.workshop.data.DeletedCustomEntryDto
import com.designprototype.workshop.qLiveSource
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** L9 (audit 21 Oct 2026): soft delete and restore of questionnaires and sittings on Android. */
class QuestionnaireTrashTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the trash rows decode from the server's shapes`() {
        val row = json.decodeFromString(
            CustomQuestionnaireSummaryDto.serializer(),
            """{"id":"q1","title":"Loom survey","deletedAt":"2026-10-21T09:00:00Z","deletedByName":"Meera"}""",
        )
        assertEquals("Meera", row.deletedByName)
        val sitting = json.decodeFromString(
            DeletedCustomEntryDto.serializer(),
            """{"id":"e1","title":"","respondentName":"Padma","deletedAt":"2026-10-21T09:00:00Z"}""",
        )
        assertEquals("Padma", sitting.respondentName)
    }

    @Test
    fun `the words say nothing is lost and an admin can restore`() {
        assertEquals("Deleted 2026-10-21 by Meera", QuestionnaireTrashCopy.deletedLine("2026-10-21T09:00:00Z", "Meera"))
        assertEquals("Deleted", QuestionnaireTrashCopy.deletedLine(null, " "))
        listOf(QuestionnaireTrashCopy.QUESTIONNAIRE_BODY, QuestionnaireTrashCopy.QUESTIONNAIRE_CONFIRM, QuestionnaireTrashCopy.SITTING_CONFIRM)
            .forEach {
                assertTrue(it, it.contains("restore"))
                assertFalse(it, it.contains("ermanent"))
            }
    }

    @Test
    fun `the form's controls are the author's or an admin's, a sitting's an admin's, on the soft-delete routes`() {
        val api = qLiveSource("app/src/main/java/com/designprototype/workshop/data/WorkshopRepositoryApi.kt").readText()
        listOf(
            "@DELETE(\"questionnaires/{id}\")",
            "@POST(\"questionnaires/{id}/restore\")",
            "@DELETE(\"questionnaires/{id}/entries/{entryId}\")",
            "@GET(\"questionnaires/{id}/deleted-entries\")",
            "@POST(\"questionnaires/{id}/entries/{entryId}/restore\")",
            "@Query(\"deletedOnly\")",
        ).forEach { assertTrue(it, api.contains(it)) }
        val list = qLiveSource("app/src/main/java/com/designprototype/workshop/ui/questionnaires/QuestionnaireListScreen.kt").readText()
        // The trash is offered to designers too (their own forms) and to content admins (every form).
        assertTrue(list.substringBefore("DeletedQuestionnairesCard(").takeLast(200).contains("FieldPermissions.canRunDesignWorkshops(trashUser)"))
        assertTrue(list.contains("ownOnly = !mayDeleteQuestionnaireItems(trashUser),"))
        val detail = qLiveSource("app/src/main/java/com/designprototype/workshop/ui/questionnaires/QuestionnaireDetailScreen.kt").readText()
        assertTrue(detail.contains("onDeleteEntry = if (mayDeleteSittings && !isTempId(questionnaireId))"))
        assertTrue(detail.substringBefore("DeletedSittingsCard(").takeLast(150).contains("if (mayDeleteSittings && !isTempId(questionnaireId))"))
        assertTrue(detail.substringBefore("RecordDangerZoneCard(").takeLast(200).contains("if (mayDelete && !isTempId(questionnaireId))"))
    }
}
