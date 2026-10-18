package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C9 (2026-10-17): the handset's half of "start a stage from an earlier workshop" — the same four
 * stages as the server, copies fill EMPTY boxes only, a copied box is pending until edited or kept,
 * and a stage with no copies sends the body every server has always accepted.
 */
class DwCopySourcesTest {

    @Test
    fun `the four narrative stages are copyable, as on the server`() {
        assertEquals(
            setOf(
                "INTRODUCTORY_ADMIN_DOCUMENTATION",
                "CLUSTER_CRAFT_BACKGROUND",
                "TRADITIONAL_PROCESS_BASELINE",
                "SURVEY_PLANNING",
            ),
            DW_COPYABLE_STAGES,
        )
    }

    @Test
    fun `a copy never overwrites an answer`() {
        val current = mapOf("history" to JsonPrimitive("Typed here"), "geography" to JsonPrimitive("  "))
        val source = mapOf(
            "history" to JsonPrimitive("Earlier"),
            "geography" to JsonPrimitive("Hills"),
            "craftIntroduction" to JsonPrimitive("Ajrakh"),
        )
        val filled = dwCopyIntoEmpty(current, source)
        assertEquals(setOf("geography", "craftIntroduction"), filled.keys)
        assertTrue(dwIsEmptyValue(JsonArray(emptyList())))
        assertFalse(dwIsEmptyValue(JsonObject(mapOf("blocks" to JsonArray(emptyList())))))
    }

    @Test
    fun `a copied box is pending until edited or kept`() {
        val stamps = mapOf(
            "history" to DwFieldStampDto(source = DW_COPIED_SOURCE),
            "geography" to DwFieldStampDto(source = "designer"),
            "craftIntroduction" to DwFieldStampDto(source = DW_COPIED_SOURCE),
        )
        assertEquals(
            setOf("history", "clusterIntroduction"),
            dwPendingCopies(mapOf("clusterIntroduction" to "w0"), stamps, edited = setOf("craftIntroduction")),
        )
    }

    @Test
    fun `the save body carries copiedFrom only when there is a copy`() {
        val empty = ApiClient.json.encodeToString(StageSaveBody.serializer(), StageSaveBody(replaceCollections = false))
        assertFalse("copiedFrom" in empty)
        val copied = ApiClient.json.encodeToString(
            StageSaveBody.serializer(),
            StageSaveBody(replaceCollections = false, copiedFrom = mapOf("history" to "w0")),
        )
        assertTrue("\"copiedFrom\":{\"history\":\"w0\"}" in copied)
    }

    @Test
    fun `rich text previews as its words`() {
        val doc = JsonObject(
            mapOf("blocks" to JsonArray(listOf(JsonObject(mapOf("spans" to JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive("Old  looms"))))))))))
        )
        assertEquals("Old looms", dwCopyPreview(doc))
    }
}
