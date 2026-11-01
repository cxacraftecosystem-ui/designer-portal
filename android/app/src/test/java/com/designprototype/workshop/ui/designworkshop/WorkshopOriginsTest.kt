package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.0.6: a workshop opened from search keeps that search as its way back through every sub-screen;
 * opened from its list, it forgets it. The source check holds MainActivity to building every rebuilt
 * index through the one helper that carries the origin.
 */
class WorkshopOriginsTest {
    @Test fun `search survives a stage and back, the list forgets it`() {
        val origins = WorkshopOrigins<String>()
        origins.opened("w1", "search:indigo")
        // search → workshop → stage → Back: the rebuilt index still knows the search.
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
        // The helper itself, the search door (which records the origin) and the list door (which clears it).
        assertEquals(3, built)
        assertTrue(text.contains("workshopOrigins.opened(workshopId, screen)"))
        assertTrue(text.contains("workshopOrigins.openedFromList(id)"))
        assertTrue(text.contains("is Screen.DesignWorkshopStage -> workshopIndex(s.workshopId)"))
        // Back from a session the form detail opened returns to the form.
        assertTrue(text.contains("is Screen.Create -> s.from ?: Screen.Dashboard"))
        assertTrue(text.contains("Screen.Create(EntryMode.QUESTIONNAIRE, from = s)"))
    }
}
