package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkshopOriginsTest {
    @Test fun `search survives a stage and back, the list forgets it`() {
        val origins = WorkshopOrigins<String>()
        origins.opened("w1", "search:indigo")
        assertEquals("search:indigo", origins.of("w1"))
        assertNull(origins.of("w2"))
        origins.openedFromList("w1")
        assertNull(origins.of("w1"))
    }

    @Test fun `the newest search wins and old ones are dropped past the cap`() {
        val origins = WorkshopOrigins<String>()
        origins.opened("w1", "a"); origins.opened("w1", "b")
        assertEquals("b", origins.of("w1"))
        (0 until 40).forEach { origins.opened("x$it", "s$it") }
        assertNull(origins.of("w1"))
        assertEquals("s39", origins.of("x39"))
    }

    @Test fun `every rebuilt workshop index carries its origin`() {
        val main = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/java/com/designprototype/workshop/MainActivity.kt") }
            .firstOrNull { it.isFile } ?: File("src/main/java/com/designprototype/workshop/MainActivity.kt")
        val text = main.readText()
        val built = Regex("""Screen\.DesignWorkshopStages\(""").findAll(text).count()
        assertEquals(4, built)
        assertTrue(text.contains("workshopOrigins.opened(workshopId, screen)"))
        assertTrue(text.contains("workshopOrigins.openedFromList(id)"))
        assertTrue(text.contains("is Screen.DesignWorkshopStage -> workshopIndex(s.workshopId)"))
        assertTrue(text.contains("is Screen.Create -> s.from ?: Screen.Dashboard"))
        assertTrue(text.contains("Screen.Create(EntryMode.QUESTIONNAIRE, from = s)"))
    }
}
