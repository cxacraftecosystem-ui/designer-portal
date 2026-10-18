package com.designprototype.workshop.data

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handset's e-mail choices (2026-10-18): an older server's answer reads as the defaults, a save
 * carries only the field that changed, the hand-in word is one of three, the waiting pill draws
 * nothing at zero, and the three switches say what the web's say.
 */
class NotificationPreferencesTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    @Test
    fun `an older server's answer reads as everything on`() {
        val dto = json.decodeFromString(NotificationPreferencesDto.serializer(), """{"available":true,"emailReviewNotes":false}""")
        assertTrue(dto.available)
        assertFalse(dto.emailReviewNotes)
        assertTrue(dto.emailWorkshopUpdates && dto.emailAccessNotices)
        assertEquals(HandInMail.EACH, dto.emailHandInNotices)
        assertFalse(dto.reviewsHandIns)
    }

    @Test
    fun `a save sends only the changed field`() {
        val body = json.encodeToString(NotificationPreferencesUpdate.serializer(), EmailSwitch.WORKSHOP_UPDATES.update(false))
        assertEquals("""{"emailWorkshopUpdates":false}""", body)
    }

    @Test
    fun `the hand-in word is one of three`() {
        assertEquals(HandInMail.DAILY, HandInMail.normalised("daily"))
        assertEquals(HandInMail.EACH, HandInMail.normalised("HOURLY"))
        assertEquals(HandInMail.EACH, HandInMail.normalised(null))
        assertEquals(listOf("Each time", "Once a day", "Never"), HandInMail.OPTIONS.map { it.second })
    }

    @Test
    fun `the waiting pill draws nothing at zero and says the web's sentence`() {
        assertNull(dwInspectionWaitingLabel(0))
        assertEquals("2 waiting", dwInspectionWaitingLabel(2))
        assertEquals("1 report is waiting for your review", dwInspectionWaitingSentence(1))
        val desk = repoFile("frontend/components/dashboard/ministryDesk.ts", "../frontend/components/dashboard/ministryDesk.ts").readText()
        assertTrue(desk.contains("reports are waiting for your review"))
    }

    @Test
    fun `the three switches say what the web's say`() {
        val web = repoFile(
            "frontend/components/settings/EmailNotificationsCard.tsx",
            "../frontend/components/settings/EmailNotificationsCard.tsx"
        ).readText()
        EmailSwitch.entries.forEach { item ->
            assertTrue("web lacks label ${item.label}", web.contains("label: \"${item.label}\""))
            assertTrue("web lacks description of ${item.label}", web.contains(item.description))
        }
    }

    private fun repoFile(vararg relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in relative) {
                val candidate = File(dir, path)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("none of ${relative.toList()} found from ${File(".").absolutePath}")
    }
}
