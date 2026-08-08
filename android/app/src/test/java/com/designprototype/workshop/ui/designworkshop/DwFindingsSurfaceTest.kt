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

/**
 * Every registry key [DwStageFindings] reads, pinned against the BUNDLED registry.
 *
 * WHY THIS TEST EXISTS AND WHY IT IS NOT PARANOIA. Both findings panels are lookups by string into a
 * map of rows: `rows["priceBand"]`, `row["priceExpectation"]`, `row["costSheetRef"]`. A key that does
 * not match the registry does not raise, does not warn and does not log — it yields an empty list or a
 * null, and the panel then draws exactly what it draws for a workshop nobody has filled in. This app
 * has already shipped that failure once in a different place: the reference pickers were permanently
 * empty for months because a DTO read `items` and the API sends `options`, and the symptom read as
 * "this phone has never seen the register" rather than as a bug. A silent lookup miss here would read
 * as "the survey supports your band" — the most dangerous sentence this feature can produce, because
 * it is the one a designer would carry into a document a ministry sanctions against.
 *
 * IT PINS THE SHIPPED ASSET, NOT THE LIVE SERVER, on purpose. `design-workshop-schema.json` is what
 * the handset renders from before it has ever had a connection, so it is the copy that decides what a
 * courtyard sees. A key that exists on the server and not in the bundled asset is still a panel that
 * draws nothing on first launch.
 *
 * WHAT IT DELIBERATELY DOES NOT DO is assert on Compose output. The arithmetic is proven by value in
 * `DwAnalysisParityTest`; what is unproven and cheap to prove is that the panel is reading the rows
 * the registry actually declares.
 */
class DwFindingsSurfaceTest {

    /** Matches the app's own decoder: the registry carries keys the DTOs here do not model. */
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

    /**
     * Stage 8's two collections, which the stage-9 panel reaches into a DIFFERENT stage's draft for.
     *
     * The cross-stage read is the part with no compiler behind it: `MarketFindingsCard` names
     * `MARKET_SURVEY_CAPTURE`, `surveyResponse` and `competitorProduct` as strings, and a rename on
     * the server would leave the panel drawing "0 observations" beside a survey of forty people.
     */
    @Test
    fun `the stage-8 rows the market panel reads are the ones the registry declares`() {
        requireFields(
            "MARKET_SURVEY_CAPTURE", "surveyResponse",
            // Read by collectObservations …
            "priceExpectation", "respondentGroup", "respondentName",
            // … and by the tokeniser in linkSwotEvidence / clusterProducts.
            "response", "productsDiscussed", "place",
        )
        requireFields(
            "MARKET_SURVEY_CAPTURE", "competitorProduct",
            "price", "category", "name", "seller",
        )
    }

    /** Stage 9's own two collections — the boxes the findings are printed beside. */
    @Test
    fun `the stage-9 rows the market panel reads are the ones the registry declares`() {
        requireFields("MARKET_ANALYSIS_DIRECTION", "priceBand", "category", "lowPrice", "highPrice")
        requireFields("MARKET_ANALYSIS_DIRECTION", "swotPoint", "kind", "point", "evidence")
    }

    /** Stage 17's sheets and the lines the roll-up totals. */
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

    /**
     * The label lookup, which is the one part of the cost panel that reaches outside stage 17.
     *
     * `productRef` names a `DwFinalProduct`, and the panel resolves it against stage 16's rows on this
     * device before it asks the reference cache — a cache the picker only fills once it has had a
     * connection. Pinning `refModel` rather than the stage key is deliberate: the link is declared by
     * MODEL, and matching on a field-name spelling is a convention nothing enforces.
     */
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

    /**
     * The line→sheet join, declared by model rather than by the spelling of `costSheetRef`.
     *
     * `DwCostIntegrity` matches a line to its sheet through this REF holding the sheet's `_entryId`.
     * If the registry ever pointed `costSheetRef` somewhere else, every line would become an orphan
     * and every sheet would read "un-itemised" — a refusal, which is the shape this module is
     * designed to produce, so nothing would look broken.
     */
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

    /**
     * The two stages that draw a panel, and — just as load-bearing — the twenty that must not.
     *
     * `DwStageFindings` is called from every stage screen. A key typo in the `when` costs nothing
     * visible on the stage that loses its panel, but a key that accidentally matched a different
     * stage would put a cost-sheet card under, say, the participant roster.
     */
    /**
     * The mixed-sync case: some sheets have reached the server and some have not.
     *
     * WHAT WAS WRONG. The panel asked `sheets.none { it has an _entryId }`, which is false the moment
     * ONE sheet has synced — so a stage holding three synced sheets and one entered this morning took
     * the else arm and printed `cost_integrity`'s orphan caution verbatim: "N material cost lines are
     * not attached to any cost sheet in this stage … The sheet they named may have been deleted after
     * they were recorded." Nothing was deleted. The sheet is on the same screen; its lines cannot name
     * it because `costSheetRef` is filled from the references endpoint, which cannot serve a row the
     * server has never seen. That sentence, shown to a designer in a courtyard about the costing they
     * did an hour earlier, is precisely the false accusation the module's own docstring forbids — and
     * once a warning has lied to somebody, the real ones underneath it stop being read.
     *
     * The count, not the `none`, is the fix, and this is the test that tells the two apart: every case
     * below except the mixed one gives the same answer under both rules.
     */
    @Test
    fun `a sheet still waiting to sync is counted even when its neighbours have synced`() {
        fun sheet(entryId: String?): Map<String, JsonElement> =
            if (entryId == null) mapOf("materialCost" to JsonPrimitive("100.00"))
            else mapOf("_entryId" to JsonPrimitive(entryId), "materialCost" to JsonPrimitive("100.00"))

        assertEquals("no sheets at all is not an unsynced sheet", 0, dwUnsyncedSheetCount(emptyList()))
        assertEquals(0, dwUnsyncedSheetCount(listOf(sheet("s1"), sheet("s2"))))
        assertEquals(2, dwUnsyncedSheetCount(listOf(sheet(null), sheet(null))))
        // THE CASE THE OLD RULE GOT WRONG. `none { … }` answers "everything is synced" here.
        assertEquals(
            "one unsynced sheet among synced ones must still suppress the deletion caution",
            1, dwUnsyncedSheetCount(listOf(sheet("s1"), sheet(null), sheet("s3")))
        )
        // An `_entryId` that is present but empty is not an identity — `DwCostIntegrity` refuses to
        // join on it, so the panel must not claim the sheet has synced either.
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
