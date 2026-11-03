package com.designprototype.workshop.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiModelCopyTest {
    private val pkg = "com/designprototype/workshop"

    private fun source(name: String): String {
        val file = listOf(File("src/main/java/$pkg/$name"), File("app/src/main/java/$pkg/$name")).first { it.exists() }
        return file.readText()
    }

    @Test
    fun theRefineConfirmationNamesNoModel() {
        val main = source("MainActivity.kt")
        assertTrue(main.contains("uses an AI model at a small cost. Continue?"))
        assertFalse(Regex("\\((gpt|gemini|claude)-[0-9a-z.]+\\)").containsMatchIn(main))
    }
}
