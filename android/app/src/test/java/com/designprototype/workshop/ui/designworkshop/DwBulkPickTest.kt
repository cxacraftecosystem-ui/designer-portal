package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwReferenceOption
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** B3 — the handset's bulk add mirrors the web's `bulkField` and `addFromReferences`. */
class DwBulkPickTest {

    private val schemaJson = Json { ignoreUnknownKeys = true }

    private fun ref(key: String, model: String, tier: String = "BASIC", filterBy: String = "") =
        FieldDto(key = key, label = key, type = "REF", tier = tier, refModel = model, refFilterBy = filterBy)

    private fun collection(vararg fields: FieldDto) =
        EntityDto(key = "list", cardinality = "COLLECTION", title = "List", fields = fields.toList())

    @Test
    fun `a list picked by exactly one basic uncascaded reference offers the bulk add`() {
        val field = ref("participantRef", "DwParticipant")
        assertEquals(field, dwBulkField(collection(field, FieldDto(key = "note", type = "TEXT"))))
    }

    @Test
    fun `a pairing, a cascade, a standard tier, a deprecated field or a singleton offer nothing`() {
        assertNull(dwBulkField(collection(ref("a", "DwSketch"), ref("b", "DwParticipant"))))
        assertNull(dwBulkField(collection(ref("a", "ProductDocumentation", filterBy = "artisanRef"))))
        assertNull(dwBulkField(collection(ref("a", "ToolDocumentation", tier = "STANDARD"))))
        assertNull(dwBulkField(collection(ref("a", "DwSketch").copy(deprecated = true))))
        assertNull(dwBulkField(EntityDto(key = "s", cardinality = "SINGLETON", fields = listOf(ref("a", "Craft")))))
    }

    @Test
    fun `the shipped registry offers it on the roster, certificates, follow-ups and sketch reviews`() {
        val asset = File("src/main/assets/design-workshop-schema.json")
        // A missing file is a FAILURE, not a pass: a moved asset must not turn this test green.
        assertTrue("${asset.absolutePath} is missing", asset.exists())
        val schema = schemaJson.decodeFromString(SchemaResponse.serializer(), asset.readText(Charsets.UTF_8))
        val offered = schema.stages.flatMap { it.entities }
            .mapNotNull { entity -> dwBulkField(entity)?.let { entity.key to it.refModel } }
            .toMap()
        assertEquals("Artisan", offered["participant"])
        assertEquals("DwParticipant", offered["certificate"])
        assertEquals("DwFinalProduct", offered["followUp"])
        assertEquals("DwSketch", offered["sketchReview"])
        // A prototype pairs a sketch with an artisan: never bulk-added.
        assertTrue("prototype" !in offered)
    }

    @Test
    fun `the all shortcut is offered for the workshop's own records and never for the artisan register`() {
        assertEquals("All participants", dwBulkAllLabel("DwParticipant"))
        assertEquals("All final products", dwBulkAllLabel("DwFinalProduct"))
        assertNull(dwBulkAllLabel("Artisan"))
    }

    @Test
    fun `picked records become hydrated rows, skipping ones already on the list`() {
        val field = ref("participantRef", "DwParticipant").copy(refHydration = mapOf("name" to "name"))
        val writable = mapOf("participantRef" to field, "name" to FieldDto(key = "name", type = "TEXT"))
        val options = listOf(
            DwReferenceOption(id = "p1", label = "Ramesh", data = buildJsonObject { put("name", "Ramesh") }),
            DwReferenceOption(id = "p2", label = "Sita", data = buildJsonObject { put("name", "Sita") }),
        )
        val outcome = dwBulkAdd(options, field, writable, alreadyChosen = setOf("p1"), stageEntries = 3)
        assertEquals(1, outcome.created.size)
        assertEquals(JsonPrimitive("p2"), outcome.created[0]["participantRef"])
        assertEquals(JsonPrimitive("Sita"), outcome.created[0]["name"])
        assertNull(dwBulkRefusal(outcome))
    }

    @Test
    fun `a bulk add past the stage cap takes what fits and names what did not`() {
        val field = ref("participantRef", "DwParticipant")
        val options = (1..10).map { DwReferenceOption(id = "p$it", label = "Name $it") }
        val outcome = dwBulkAdd(options, field, mapOf(field.key to field), emptySet(), stageEntries = 497)
        assertEquals(3, outcome.created.size)
        assertEquals(
            "Added 3 of 10 — room for 3 on this stage. Not added: Name 4, Name 5, Name 6, Name 7, Name 8, " +
                "Name 9 and 1 more. Delete rows to make room.",
            dwBulkRefusal(outcome),
        )
    }

    @Test
    fun `an all press on a cut-short list says it added the first n, in the web's words`() {
        val field = ref("participantRef", "DwParticipant").copy(label = "Participants")
        val options = (1..4).map { DwReferenceOption(id = "p$it", label = "Name $it") }
        val outcome = dwBulkAdd(options, field, mapOf(field.key to field), emptySet(), stageEntries = 0)
        assertEquals(
            "Added the first 4. Use add several from participants for the rest.",
            dwBulkAddedSentence(outcome, truncated = true, fieldLabel = field.label),
        )
        assertEquals("Added 4 rows.", dwBulkAddedSentence(outcome, truncated = false, fieldLabel = field.label))
    }

    @Test
    fun `a cut-short add that also hit the cap says only the refusal, never two counts`() {
        val field = ref("participantRef", "DwParticipant")
        val options = (1..10).map { DwReferenceOption(id = "p$it", label = "Name $it") }
        val outcome = dwBulkAdd(options, field, mapOf(field.key to field), emptySet(), stageEntries = 497)
        val sentence = dwBulkAddedSentence(outcome, truncated = true, fieldLabel = "Participants")
        assertTrue(sentence.startsWith("Added 3 of 10"))
        assertTrue("first" !in sentence)
    }

    @Test
    fun `the bulk add asks for the server's ceiling, and the sheet says when the list is one page`() {
        assertEquals(200, DW_BULK_PAGE)
        assertEquals("Showing the first 200. Search to find the rest.", dwBulkCutShortNote(200))
        val backend = File("../../backend/app/services/design_workshops.py")
        assertTrue("${backend.absolutePath} is missing", backend.exists())
        assertTrue(backend.readText(Charsets.UTF_8).contains("REFERENCE_LIMIT_MAX = $DW_BULK_PAGE"))
    }

    @Test
    fun `an offline search filters the held list on the name and the second line`() {
        val options = listOf(
            DwReferenceOption(id = "a", label = "Ramesh Kumar", hint = "Bagru"),
            DwReferenceOption(id = "b", label = "Sita Devi", hint = "Sanganer"),
        )
        assertEquals(listOf("a"), dwBulkLocalSearch(options, "ram bagru").map { it.id })
        assertEquals(listOf("b"), dwBulkLocalSearch(options, "SANG").map { it.id })
        assertEquals(options, dwBulkLocalSearch(options, "  "))
    }

    @Test
    fun `opening a stage reads the cache only, and the server is asked when the bulk controls are used`() {
        val stage = File("src/main/java/com/designprototype/workshop/ui/designworkshop/StageScreen.kt")
        assertTrue("${stage.absolutePath} is missing", stage.exists())
        val source = stage.readText(Charsets.UTF_8)
        assertTrue(source.contains("cacheOnly = true,"))
        assertTrue(source.contains("limit = DW_BULK_PAGE,"))
        assertTrue(source.contains("bulkScope.launch { refreshBulk() }"))
        assertTrue(source.contains("dwBulkAddedSentence(outcome, truncated = truncated"))
    }

    @Test
    fun `the shortcut labels are the web's, word for word`() {
        val web = File("../../frontend/components/designworkshop/bulkPick.ts")
        assertTrue("${web.absolutePath} is missing", web.exists())
        val source = web.readText(Charsets.UTF_8)
        listOf("DwParticipant", "DwFinalProduct", "DwSketch", "DwPrototype").forEach { model ->
            val label = dwBulkAllLabel(model)!!
            assertTrue("$model -> $label missing on the web", source.contains("$model: \"$label\""))
        }
    }
}
