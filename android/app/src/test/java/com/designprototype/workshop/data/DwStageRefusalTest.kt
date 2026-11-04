package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwStageRefusalTest {

    private val spec = StageDto(
        number = 17,
        key = "COSTING",
        title = "Costing",
        entities = listOf(
            EntityDto(
                key = "costing", cardinality = "SINGLETON", title = "Costing",
                fields = listOf(
                    FieldDto(key = "totalCost", label = "Total cost", type = "MONEY"),
                    FieldDto(key = "notes", label = "Notes", type = "LONG_TEXT"),
                    FieldDto(
                        key = "oldRate", label = "Old rate", type = "MONEY", deprecated = true,
                    ),
                )
            ),
            EntityDto(
                key = "costLine", cardinality = "COLLECTION", title = "Cost line",
                fields = listOf(
                    FieldDto(key = "label", label = "Line", type = "TEXT"),
                    FieldDto(key = "amount", label = "Amount", type = "MONEY"),
                )
            ),
        )
    )

    private fun entries(rows: Int = 0, custom: Boolean = false): List<StageEntryBody> = buildList {
        add(StageEntryBody(entityKey = "costing", data = JsonObject(emptyMap())))
        repeat(rows) { index ->
            add(StageEntryBody(entityKey = "costLine", ordinal = index, data = JsonObject(emptyMap())))
        }
        if (custom) add(StageEntryBody(entityKey = CUSTOM_ENTITY_KEY, data = JsonObject(emptyMap())))
    }

    private fun errors(vararg pairs: Pair<String, Map<String, String>>): Map<String, kotlinx.serialization.json.JsonElement> =
        pairs.associate { (scope, fields) ->
            scope to buildJsonObject { fields.forEach { (key, message) -> put(key, message) } }
        }

    @Test
    fun `a refused singleton field is named by its label, not its key`() {
        val report = dwDecodeStageRefusals(
            spec, entries(),
            errors("costing" to mapOf("totalCost" to "Total cost must be a number")),
        )
        val refusal = report.refusals.single()
        assertEquals("costing", refusal.entityKey)
        assertEquals(null, refusal.rowIndex)
        assertEquals("Total cost", refusal.label)
        assertTrue(refusal.drawn)
        assertTrue(refusal.sentence.startsWith("Total cost: Total cost must be a number."))
    }

    @Test
    fun `a refused collection row is decoded through the array that was SENT, not by row number`() {
        val report = dwDecodeStageRefusals(
            spec, entries(rows = 3),
            errors(
                "costLine[1]" to mapOf("amount" to "Amount must be a number"),
                "costLine[3]" to mapOf("amount" to "Amount must be a number"),
            ),
        )
        assertEquals(listOf(0, 2), report.refusals.map { it.rowIndex }.sortedBy { it })
        assertEquals(
            setOf("costLine[0]", "costLine[2]"),
            report.refusals.map { it.address }.toSet()
        )
        assertTrue(report.refusals.first().sentence.contains("(row 1)"))
    }

    @Test
    fun `the custom container's refusals arrive under the reserved key and are placed`() {
        val fields = listOf(
            DwCustomFieldDto(key = "dyeVatCount", label = "How many dye vats?", type = "INT"),
        )
        val report = dwDecodeStageRefusals(
            spec, entries(custom = true),
            errors(CUSTOM_ENTITY_KEY to mapOf("dyeVatCount" to "How many dye vats? is not a whole number")),
            customFields = fields,
        )
        val refusal = report.refusals.single()
        assertEquals(CUSTOM_ENTITY_KEY, refusal.entityKey)
        assertEquals("How many dye vats?", refusal.label)
        assertTrue("this build can draw an INT", refusal.drawn)
        assertTrue(report.unplaced.isEmpty())
    }

    @Test
    fun `a custom question this build cannot draw is still reported, and says so`() {
        val report = dwDecodeStageRefusals(
            spec, entries(custom = true),
            errors(CUSTOM_ENTITY_KEY to mapOf("sketchOfVat" to "Not a valid value")),
            customFields = listOf(
                DwCustomFieldDto(key = "sketchOfVat", label = "Sketch of the vat", type = "SIGNATURE"),
            ),
        )
        val refusal = report.refusals.single()
        assertEquals("Sketch of the vat", refusal.label)
        assertFalse(refusal.drawn)
        assertTrue(refusal.sentence.contains("has no box for that question"))
    }

    @Test
    fun `a field key nothing on this device knows keeps its bare key rather than disappearing`() {
        val report = dwDecodeStageRefusals(
            spec, entries(),
            errors("costing" to mapOf("somethingAddedLastWeek" to "Out of range")),
        )
        val refusal = report.refusals.single()
        assertEquals("somethingAddedLastWeek", refusal.label)
        assertFalse(refusal.drawn)
    }

    @Test
    fun `a deprecated field is reported but not claimed to be drawable`() {
        val report = dwDecodeStageRefusals(
            spec, entries(), errors("costing" to mapOf("oldRate" to "Old rate must be a number")),
        )
        assertEquals("Old rate", report.refusals.single().label)
        assertFalse("nothing draws a deprecated field", report.refusals.single().drawn)
    }

    @Test
    fun `a scope the payload cannot account for is reported rather than dropped`() {
        val report = dwDecodeStageRefusals(
            spec, entries(rows = 1),
            errors(
                "costLine[9]" to mapOf("amount" to "Amount must be a number"),
                "costLine[0]" to mapOf("amount" to "Amount must be a number"),
            ),
        )
        assertTrue(report.refusals.isEmpty())
        assertEquals(2, report.unplaced.size)
        assertTrue(report.unplaced.all { it.contains("Amount must be a number") })
    }

    @Test
    fun `a scope whose payload is not a field map is still surfaced`() {
        val report = dwDecodeStageRefusals(
            spec, entries(),
            mapOf("costing" to JsonPrimitive("the whole entry was rejected")),
        )
        assertTrue(report.refusals.isEmpty())
        assertEquals(listOf("costing: the whole entry was rejected"), report.unplaced)
    }

    @Test
    fun `what the repository holds is UNRECORDED until something has actually read it`() {
        val report = dwDecodeStageRefusals(
            spec, entries(), errors("costing" to mapOf("totalCost" to "not a number")),
        )
        val held = report.refusals.single().held
        assertEquals(DwHeldState.UNRECORDED, held.state)
        assertEquals("UNRECORDED", held.text)
        assertTrue(report.refusals.single().sentence.contains("it is not shown here"))
    }

    @Test
    fun `a read fills in what the repository holds, for every shape of address`() {
        val report = dwDecodeStageRefusals(
            spec, entries(rows = 2, custom = true),
            errors(
                "costing" to mapOf("totalCost" to "not a number"),
                "costLine[2]" to mapOf("amount" to "not a number"),
                CUSTOM_ENTITY_KEY to mapOf("dyeVatCount" to "not a whole number"),
            ),
        )
        val bucket = StageBucketDto(
            singleton = buildJsonObject { put("totalCost", 6500) },
            collections = mapOf(
                "costLine" to listOf(
                    buildJsonObject { put("amount", 100) },
                    buildJsonObject { put("amount", 250) },
                )
            ),
            custom = buildJsonObject { put("dyeVatCount", 3) },
        )
        val filled = dwHoldingsFrom(report, bucket)
        val byKey = filled.refusals.associateBy { it.fieldKey }

        assertEquals("6500", byKey.getValue("totalCost").held.text)
        assertEquals(DwHeldState.HOLDS, byKey.getValue("totalCost").held.state)
        assertEquals("250", byKey.getValue("amount").held.text)
        assertEquals("3", byKey.getValue("dyeVatCount").held.text)
        assertTrue(byKey.getValue("totalCost").sentence.contains("is still: “6500”"))
    }

    @Test
    fun `a key the read came back silent about holds NOTHING, which is not the same as UNRECORDED`() {
        val report = dwDecodeStageRefusals(
            spec, entries(), errors("costing" to mapOf("totalCost" to "not a number")),
        )
        val filled = dwHoldingsFrom(report, StageBucketDto())
        assertEquals(DwHeldState.NOTHING, filled.refusals.single().held.state)
        assertTrue(filled.refusals.single().sentence.contains("no earlier answer"))
    }

    @Test
    fun `the heading says the save did NOT fail, and that nothing typed was thrown away`() {
        val report = dwDecodeStageRefusals(
            spec, entries(),
            errors("costing" to mapOf("totalCost" to "not a number", "notes" to "too long")),
        )
        assertEquals(2, report.count)
        assertTrue(report.heading.contains("Everything else in this stage was saved."))
        assertTrue(report.heading.contains("Nothing you typed has been thrown away"))
        assertFalse(
            "a refusal is not a failed save and must never be reported as one",
            report.heading.contains("failed")
        )
        assertTrue(
            "every refusal promises the typed text is still on the phone",
            report.refusals.all { it.sentence.contains("still on this phone") }
        )
    }

    @Test
    fun `no errors is no report at all, so a clean save clears the card`() {
        assertTrue(dwDecodeStageRefusals(spec, entries(), emptyMap()).isEmpty)
    }

    @Test
    fun `a holding already measured is carried, so an unchanged refusal asks for no second read`() {
        val first = dwHoldingsFrom(
            dwDecodeStageRefusals(spec, entries(), errors("costing" to mapOf("totalCost" to "not a number"))),
            StageBucketDto(singleton = buildJsonObject { put("totalCost", 6500) }),
        )
        assertFalse("measured, so nothing more to ask", first.needsRead)

        val second = dwDecodeStageRefusals(
            spec, entries(), errors("costing" to mapOf("totalCost" to "not a number")),
        )
        assertTrue("a fresh decode knows nothing", second.needsRead)

        val carried = dwCarryHoldings(first, second)
        assertFalse("…until the earlier measurement is carried onto it", carried.needsRead)
        assertEquals("6500", carried.refusals.single().held.text)
    }

    @Test
    fun `a NEW refusal is not answered by an old measurement`() {
        val first = dwHoldingsFrom(
            dwDecodeStageRefusals(spec, entries(), errors("costing" to mapOf("totalCost" to "not a number"))),
            StageBucketDto(singleton = buildJsonObject { put("totalCost", 6500) }),
        )
        val second = dwCarryHoldings(
            first,
            dwDecodeStageRefusals(
                spec, entries(),
                errors("costing" to mapOf("totalCost" to "not a number", "notes" to "too long")),
            ),
        )
        assertTrue(second.needsRead)
        val byKey = second.refusals.associateBy { it.fieldKey }
        assertEquals(DwHeldState.HOLDS, byKey.getValue("totalCost").held.state)
        assertEquals(DwHeldState.UNRECORDED, byKey.getValue("notes").held.state)
    }

    @Test
    fun `a refusal that moved to a different row is measured again rather than assumed`() {
        val first = dwHoldingsFrom(
            dwDecodeStageRefusals(
                spec, entries(rows = 2), errors("costLine[1]" to mapOf("amount" to "not a number")),
            ),
            StageBucketDto(
                collections = mapOf("costLine" to listOf(buildJsonObject { put("amount", 100) }))
            ),
        )
        val moved = dwCarryHoldings(
            first,
            dwDecodeStageRefusals(
                spec, entries(rows = 2), errors("costLine[2]" to mapOf("amount" to "not a number")),
            ),
        )
        assertTrue(moved.needsRead)
        assertEquals(DwHeldState.UNRECORDED, moved.refusals.single().held.state)
    }

    private val conflict =
        "Someone else saved this row first — reopen the stage to see the latest before saving again."

    @Test
    fun `a row-level refusal lands on the row, in the repository's own words`() {
        val report = dwDecodeStageRefusals(
            spec, entries(rows = 2),
            errors("costLine[1]" to mapOf(DW_ROW_REFUSAL_KEY to conflict)),
        )
        val refusal = report.refusals.single()

        assertTrue(refusal.isRowLevel)
        assertEquals(0, refusal.rowIndex)
        assertEquals("costLine[0]", refusal.address)
        assertEquals("Cost line", refusal.label)
        assertEquals("Cost line (row 1): $conflict", refusal.sentence)
        assertFalse("the reserved key is not a question", refusal.sentence.contains("_row"))
        assertFalse(
            "there is no box for a row, so this build must not apologise for lacking one",
            refusal.sentence.contains("has no box for that question"),
        )
        assertFalse(
            "and nothing is held under a key no row carries",
            refusal.sentence.contains("UNRECORDED"),
        )
    }

    @Test
    fun `a row-level refusal marks no box and asks for no read`() {
        val report = dwDecodeStageRefusals(
            spec, entries(rows = 2),
            errors("costLine[1]" to mapOf(DW_ROW_REFUSAL_KEY to conflict)),
        )
        assertEquals(emptyMap<String, Map<String, String>>(), report.byAddress)
        assertFalse(report.needsRead)

        val measured = dwHoldingsFrom(report, StageBucketDto())
        assertEquals(DwHeldState.UNRECORDED, measured.refusals.single().held.state)
        assertEquals("Cost line (row 1): $conflict", measured.refusals.single().sentence)
    }

    @Test
    fun `a row-level refusal counts once, on both counters that decide what the app claims`() {
        val wire = errors("costLine[1]" to mapOf(DW_ROW_REFUSAL_KEY to conflict))

        assertEquals(1, dwRefusedAnswerCount(wire))
        assertEquals(1, dwDecodeStageRefusals(spec, entries(rows = 2), wire).count)
    }

    @Test
    fun `a row refused for a bad value AND for a conflict keeps both, and only the value marks a box`() {
        val wire = errors(
            "costLine[1]" to mapOf(
                "amount" to "Amount must be a number",
                DW_ROW_REFUSAL_KEY to conflict,
            ),
        )
        val report = dwDecodeStageRefusals(spec, entries(rows = 2), wire)

        assertEquals(2, report.count)
        assertEquals(2, dwRefusedAnswerCount(wire))
        assertEquals(
            mapOf("amount" to false, DW_ROW_REFUSAL_KEY to true),
            report.refusals.associate { it.fieldKey to it.isRowLevel },
        )
        assertEquals(
            mapOf("costLine[0]" to mapOf("amount" to "Amount must be a number")),
            report.byAddress,
        )
        assertTrue("the field half is still worth measuring", report.needsRead)
    }

    @Test
    fun `a row-level refusal on the custom container names the section, not the reserved key`() {
        val report = dwDecodeStageRefusals(
            spec, entries(custom = true),
            errors(CUSTOM_ENTITY_KEY to mapOf(DW_ROW_REFUSAL_KEY to conflict)),
        )
        val refusal = report.refusals.single()
        assertTrue(refusal.isRowLevel)
        assertEquals(null, refusal.rowIndex)
        assertEquals("This workshop's own questions: $conflict", refusal.sentence)
    }

    @Test
    fun `the addressing the form marks its boxes by`() {
        val report = dwDecodeStageRefusals(
            spec, entries(rows = 2),
            errors(
                "costing" to mapOf("totalCost" to "not a number"),
                "costLine[1]" to mapOf("amount" to "not a number"),
            ),
        )
        assertEquals(
            mapOf(
                "costing" to mapOf("totalCost" to "not a number"),
                "costLine[0]" to mapOf("amount" to "not a number"),
            ),
            report.byAddress
        )
    }
}
