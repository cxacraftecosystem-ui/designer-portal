package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwCostIntegrity
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.collections
import com.designprototype.workshop.data.entity
import com.designprototype.workshop.data.field
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DwFindingsSurfaceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val schema: SchemaResponse by lazy {
        val asset = File("src/main/assets/design-workshop-schema.json")
        assertTrue(
            "the bundled registry is missing — it is what the handset renders from on first launch",
            asset.exists()
        )
        json.decodeFromString(SchemaResponse.serializer(), asset.readText(Charsets.UTF_8))
    }

    private fun stage(key: String) =
        schema.stages.firstOrNull { it.key == key }
            ?: throw AssertionError("the registry declares no stage `$key`")

    private fun fieldsOf(stageKey: String, entityKey: String): Set<String> {
        val entity = stage(stageKey).entity(entityKey)
            ?: throw AssertionError("stage `$stageKey` declares no entity `$entityKey`")
        assertTrue(
            "`$entityKey` must be a COLLECTION — the panel reads it out of `state.collections`",
            entity in stage(stageKey).collections
        )
        return entity.fields.map { it.key }.toSet()
    }

    private fun requireFields(stageKey: String, entityKey: String, vararg keys: String) {
        val declared = fieldsOf(stageKey, entityKey)
        for (key in keys) {
            assertTrue(
                "`$stageKey`.`$entityKey` declares no field `$key` — the panel would read null and " +
                    "silently report no evidence",
                key in declared
            )
        }
    }

    @Test
    fun `the stage-8 rows the market panel reads are the ones the registry declares`() {
        requireFields(
            "MARKET_SURVEY_CAPTURE", "surveyResponse",
            "priceExpectation", "respondentGroup", "respondentName",
            "response", "productsDiscussed", "place",
        )
        requireFields(
            "MARKET_SURVEY_CAPTURE", "competitorProduct",
            "price", "category", "name", "seller",
        )
    }

    @Test
    fun `the stage-9 rows the market panel reads are the ones the registry declares`() {
        requireFields("MARKET_ANALYSIS_DIRECTION", "priceBand", "category", "lowPrice", "highPrice")
        requireFields("MARKET_ANALYSIS_DIRECTION", "swotPoint", "kind", "point", "evidence")
    }

    @Test
    fun `the stage-17 rows the cost panel reads are the ones the registry declares`() {
        requireFields(
            "COSTING_MARKET_LINKAGE", "costSheet",
            "productRef", "totalCost", "expectedPrice", "marginPercent",
            *DwCostIntegrity.COST_HEADS.toTypedArray(),
        )
        requireFields("COSTING_MARKET_LINKAGE", "costMaterialLine", "costSheetRef", "item", "amount")
        requireFields("COSTING_MARKET_LINKAGE", "costLabourLine", "costSheetRef", "task", "amount")
    }

    @Test
    fun `the cost sheet label is resolved through the model the registry names`() {
        val productRef = stage("COSTING_MARKET_LINKAGE").entity("costSheet")?.field("productRef")
        assertNotNull("costSheet must declare productRef", productRef)
        assertEquals("REF", productRef!!.type)
        assertEquals("DwFinalProduct", productRef.refModel)

        val products = stage("FINAL_PROTOTYPE_DOCUMENTATION").entity("finalProduct")
            ?: throw AssertionError("stage 16 declares no `finalProduct` — the panel resolves names there")
        assertEquals(
            "the model `productRef` points at must be the entity the panel reads names out of",
            "DwFinalProduct", products.name
        )
        assertTrue("finalProduct must declare `name`", products.field("name") != null)
    }

    @Test
    fun `both cost line kinds join to the sheet through a ref to the sheet's own model`() {
        val sheet = stage("COSTING_MARKET_LINKAGE").entity("costSheet")!!
        for (child in listOf("costMaterialLine", "costLabourLine")) {
            val entity = stage("COSTING_MARKET_LINKAGE").entity(child)!!
            assertEquals(
                "`$child` must declare `costSheet` as its parent, or the report and this check " +
                    "would disagree about what a line belongs to",
                sheet.key, entity.parent
            )
            val link = entity.field("costSheetRef")
                ?: throw AssertionError("`$child` declares no `costSheetRef`")
            assertEquals("REF", link.type)
            assertEquals(sheet.name, link.refModel)
        }
    }

    @Test
    fun `a sheet still waiting to sync is counted even when its neighbours have synced`() {
        fun sheet(entryId: String?): Map<String, JsonElement> =
            if (entryId == null) mapOf("materialCost" to JsonPrimitive("100.00"))
            else mapOf("_entryId" to JsonPrimitive(entryId), "materialCost" to JsonPrimitive("100.00"))

        assertEquals("no sheets at all is not an unsynced sheet", 0, dwUnsyncedSheetCount(emptyList()))
        assertEquals(0, dwUnsyncedSheetCount(listOf(sheet("s1"), sheet("s2"))))
        assertEquals(2, dwUnsyncedSheetCount(listOf(sheet(null), sheet(null))))
        assertEquals(
            "one unsynced sheet among synced ones must still suppress the deletion caution",
            1, dwUnsyncedSheetCount(listOf(sheet("s1"), sheet(null), sheet("s3")))
        )
        assertEquals(1, dwUnsyncedSheetCount(listOf(sheet(""))))
    }

    @Test
    fun `exactly the two stages with something computable to say are named`() {
        val named = listOf("MARKET_ANALYSIS_DIRECTION", "COSTING_MARKET_LINKAGE")
        for (key in named) {
            assertTrue("the registry must declare stage `$key`", schema.stages.any { it.key == key })
        }
        assertEquals(9, stage("MARKET_ANALYSIS_DIRECTION").number)
        assertEquals(17, stage("COSTING_MARKET_LINKAGE").number)
        assertEquals(8, stage("MARKET_SURVEY_CAPTURE").number)
        assertEquals(16, stage("FINAL_PROTOTYPE_DOCUMENTATION").number)
    }
}
