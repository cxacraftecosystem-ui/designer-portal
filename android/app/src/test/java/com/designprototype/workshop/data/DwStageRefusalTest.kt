package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A REFUSAL THAT LOOKED LIKE A SUCCESS.
 *
 * `StageSaveResultDto.errors` was decoded off every save this app has ever made and read by NOTHING
 * in `main`. So when the repository refused a value — a bound tightened on the web, a MONEY field sent
 * "65OO", anything `coerce_value` cannot read — the phone went on showing the typed text, the
 * repository went on holding the previous good value (`save_stage` puts it back deliberately), the
 * stage recorded a matching signature so nothing would ever re-send it, and the workshop scored
 * "Backed up to the server".
 *
 * These pin the four sentences that have to stay true, which are set out on [DwStageRefusal]: WHICH
 * question, WHAT the repository now holds, that nothing typed was discarded, and that the save did NOT
 * fail.
 */
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

    /** The payload shape `buildStageBody` produces: the singleton, then the rows, then `_custom`. */
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

    // ── Which question ───────────────────────────────────────────────────────────────────────────

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
        /*
          THE TRAP THIS WHOLE MODULE IS SHAPED BY. `save_stage` keys a collection row's errors by the
          entry's INDEX IN `payload.entries` — so a stage sending a singleton and three cost lines
          files them under `costLine[1]`, `costLine[2]`, `costLine[3]`, and the row the designer sees
          as "1." is index 1, not index 0. Read as a row number, every message lands one row late and
          the last one lands on no row at all.
        */
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

    // ── A key this build has no control for ──────────────────────────────────────────────────────

    @Test
    fun `a custom question this build cannot draw is still reported, and says so`() {
        // The one refusal a designer is least able to work out for themselves: their own question,
        // added on the web, of a type this handset has no control for. Dropping it would make the
        // most important message the only invisible one.
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
        // An index past the end of what we sent, and an entity sitting at that index that is not the
        // one named. Naming the WRONG row would send a designer to correct an answer nobody objected
        // to, so the honest answer is to admit the message cannot be placed.
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

    // ── What the repository now holds ────────────────────────────────────────────────────────────

    @Test
    fun `what the repository holds is UNRECORDED until something has actually read it`() {
        // The save response carries no stored values — measured against `save_stage`'s return, which
        // is stageKey/saved/created/updated/removed/errors/droppedKeys/droppedCustomKeys/completeness/
        // transcriptionsQueued/schemaVersion/customSchemaVersion and nothing else. So it says so, in
        // the word this repository uses for a thing it has not measured.
        val report = dwDecodeStageRefusals(
            spec, entries(), errors("costing" to mapOf("totalCost" to "not a number")),
        )
        val held = report.refusals.single().held
        assertEquals(DwHeldState.UNRECORDED, held.state)
        assertEquals("UNRECORDED", held.text)
        assertTrue(report.refusals.single().sentence.contains("UNRECORDED"))
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
        // `costLine[2]` is the SECOND row of the collection (entry index 2, singleton at 0).
        assertEquals("250", byKey.getValue("amount").held.text)
        assertEquals("3", byKey.getValue("dyeVatCount").held.text)
        assertTrue(byKey.getValue("totalCost").sentence.contains("still holds: “6500”"))
    }

    @Test
    fun `a key the read came back silent about holds NOTHING, which is not the same as UNRECORDED`() {
        // The read succeeded, so its silence is a measurement. Saying UNRECORDED here would send a
        // designer looking for a value that is not there.
        val report = dwDecodeStageRefusals(
            spec, entries(), errors("costing" to mapOf("totalCost" to "not a number")),
        )
        val filled = dwHoldingsFrom(report, StageBucketDto())
        assertEquals(DwHeldState.NOTHING, filled.refusals.single().held.state)
        assertTrue(filled.refusals.single().sentence.contains("holds no answer"))
    }

    // ── The sentences a designer actually reads ──────────────────────────────────────────────────

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

    // ── What it costs to ask ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a holding already measured is carried, so an unchanged refusal asks for no second read`() {
        // The screen decodes on EVERY debounced save. A designer who has typed "65OO" and moved on to
        // the next twenty boxes is refused on every one of those saves, and reading the stage back
        // each time would spend a request per keystroke-burst on a prepaid connection.
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
        // A second field is refused now. It has never been measured, so the read is worth making —
        // and the one that HAS been measured is not asked about again.
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
        // Carried on the ADDRESS as well as the field key: the same question refused on row 3 is not
        // the same question refused on row 1, and quoting row 1's stored value against row 3 would be
        // confidently wrong — worse than saying UNRECORDED.
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
