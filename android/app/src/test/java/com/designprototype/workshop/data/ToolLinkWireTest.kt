package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLinkWireTest {

    private val outbox = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val wire = ApiClient.json

    private fun tool(
        craftIds: List<String>? = null,
        artisanIds: List<String>? = null,
    ) = ToolCreateRequest(
        craftName = "Block Printing",
        place = "Bagru",
        artisanName = "Ram Kumar",
        toolkitName = "Chhapa",
        craftIds = craftIds,
        artisanIds = artisanIds,
    )

    @Test
    fun `a queued body from before the keys decodes to null and replays with neither key`() {
        val beforeTheKeys = """
            {"craftName":"Block Printing","place":"Bagru","artisanName":"Ram Kumar",
             "toolkitName":"Chhapa","craftId":"c-1","artisanId":"a-1","maker":"UNKNOWN",
             "traditionType":"UNKNOWN","status":"PENDING","recordedTimezone":"Asia/Kolkata"}
        """.trimIndent()

        val decoded = outbox.decodeFromString(ToolCreateRequest.serializer(), beforeTheKeys)
        assertNull("absent must decode to null, never to an empty list", decoded.craftIds)
        assertNull(decoded.artisanIds)

        val body = wire.encodeToJsonElement(ToolCreateRequest.serializer(), decoded).jsonObject
        assertFalse("an absent key must stay absent on the wire", body.containsKey("craftIds"))
        assertFalse(body.containsKey("artisanIds"))
        assertEquals("c-1", body["craftId"].toString().trim('"'))
    }

    @Test
    fun `a null round-trips through the outbox file and is dropped on the way out`() {
        val stored = outbox.encodeToString(ToolCreateRequest.serializer(), tool())
        assertEquals(
            "the outbox file writes the null explicitly, which is what it has always done",
            JsonNull,
            outbox.parseToJsonElement(stored).jsonObject["craftIds"],
        )

        val replayed = outbox.decodeFromString(ToolCreateRequest.serializer(), stored)
        val body = wire.encodeToJsonElement(ToolCreateRequest.serializer(), replayed).jsonObject
        assertFalse("a null must never reach the server — it is a 422", body.containsKey("craftIds"))
        assertFalse(body.containsKey("artisanIds"))
    }

    @Test
    fun `an empty list survives the outbox and reaches the wire as an empty list`() {
        val stored = outbox.encodeToString(ToolCreateRequest.serializer(), tool(craftIds = emptyList(), artisanIds = emptyList()))
        val replayed = outbox.decodeFromString(ToolCreateRequest.serializer(), stored)
        assertEquals(emptyList<String>(), replayed.craftIds)

        val body = wire.encodeToJsonElement(ToolCreateRequest.serializer(), replayed).jsonObject
        assertEquals(JsonArray(emptyList()), body["craftIds"])
        assertEquals(JsonArray(emptyList()), body["artisanIds"])
    }

    @Test
    fun `the order of a selection survives both encoders`() {
        val picked = listOf("c-3", "c-1", "c-2")
        val replayed = outbox.decodeFromString(
            ToolCreateRequest.serializer(),
            outbox.encodeToString(ToolCreateRequest.serializer(), tool(craftIds = picked)),
        )
        assertEquals(picked, replayed.craftIds)

        val onWire = wire.encodeToJsonElement(ToolCreateRequest.serializer(), replayed)
            .jsonObject["craftIds"]!!.jsonArray.map { it.toString().trim('"') }
        assertEquals(picked, onWire)
    }

    @Test
    fun `neither key is a clearable link column, so no replay can manufacture a null for one`() {
        assertFalse("craftIds is not a workshop link column", "craftIds" in WORKSHOP_LINK_KEYS)
        assertFalse("artisanIds is not a workshop link column", "artisanIds" in WORKSHOP_LINK_KEYS)
        assertFalse("craftIds is not a foreign key", REFERENCE_FIELD_NOUNS.containsKey("craftIds"))
        assertFalse("artisanIds is not a foreign key", REFERENCE_FIELD_NOUNS.containsKey("artisanIds"))
        assertTrue("the singular craft key is still the one a 404 names", REFERENCE_FIELD_NOUNS.containsKey("craftId"))
        assertTrue(REFERENCE_FIELD_NOUNS.containsKey("artisanId"))
    }

    @Test
    fun `a replay that 404s still names the craft and the artisan from the scalars`() {
        val payload = outbox.encodeToString(
            ToolCreateRequest.serializer(),
            tool(craftIds = listOf("c-1")).copy(craftId = "c-1", artisanId = "a-1"),
        )
        val candidates = danglingReferenceCandidates(payload)
        assertTrue("craftId" in candidates)
        assertTrue("artisanId" in candidates)
        assertFalse("a list is not a reference the scan can resolve", "craftIds" in candidates)
    }

    @Test
    fun `a tool from a server that predates the link tables decodes to empty lists`() {
        val old = """{"id":"t-1","toolkitName":"Chhapa","craftId":"c-1","artisanId":"a-1"}"""
        val tool = wire.decodeFromString(ToolDetailDto.serializer(), old)
        assertEquals(emptyList<ToolCraftLinkDto>(), tool.craftLinks)
        assertEquals(emptyList<ToolArtisanLinkDto>(), tool.artisanLinks)
        assertEquals("c-1", tool.craftId)
    }

    @Test
    fun `an explicit null for either list decodes as empty rather than failing the whole read`() {
        val nulled = """{"id":"t-1","toolkitName":"Chhapa","craftLinks":null,"artisanLinks":null}"""
        val tool = wire.decodeFromString(ToolDetailDto.serializer(), nulled)
        assertEquals(emptyList<ToolCraftLinkDto>(), tool.craftLinks)
        assertEquals(emptyList<ToolArtisanLinkDto>(), tool.artisanLinks)
    }

    @Test
    fun `the links carry the embedded record, which is what draws an off-page row honestly`() {
        val payload = """
            {"id":"t-1","toolkitName":"Chhapa","craftName":"Bandhani, Block Printing",
             "craftLinks":[{"id":"l-1","toolId":"t-1","craftId":"c-2","craft":{"id":"c-2","name":"Bandhani"}},
                           {"id":"l-2","toolId":"t-1","craftId":"c-1","craft":{"id":"c-1","name":"Block Printing"}}],
             "artisanLinks":[{"id":"l-3","toolId":"t-1","artisanId":"a-1",
                              "artisan":{"id":"a-1","name":"Ram Kumar","place":"Bagru","status":"APPROVED","craftId":"c-1"}}]}
        """.trimIndent()
        val tool = wire.decodeFromString(ToolDetailDto.serializer(), payload)

        assertEquals(listOf("c-2", "c-1"), tool.craftLinks.map { it.craftId })
        assertEquals("Bandhani, Block Printing", tool.craftName)
        assertEquals("Bandhani", tool.craftLinks.first().craft?.name)
        assertEquals("Ram Kumar", tool.artisanLinks.single().artisan?.name)
        assertEquals("Bagru", tool.artisanLinks.single().artisan?.place)
    }
}
