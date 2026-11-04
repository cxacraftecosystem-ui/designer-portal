package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwStageRefusalSurvivesTheStageTest {

    private val spec = StageDto(
        number = 17,
        key = "COSTING",
        title = "Costing",
        entities = listOf(
            EntityDto(
                key = "costing", cardinality = "SINGLETON", title = "Costing",
                fields = listOf(
                    FieldDto(key = "totalCost", label = "Total cost", type = "MONEY"),
                ),
            ),
            EntityDto(
                key = "costLine", cardinality = "COLLECTION", title = "Cost line",
                fields = listOf(
                    FieldDto(key = "amount", label = "Amount", type = "MONEY"),
                ),
            ),
        ),
    )

    private fun entries(vararg rowKeys: String): List<StageEntryBody> = buildList {
        add(StageEntryBody(entityKey = "costing", data = JsonObject(emptyMap())))
        rowKeys.forEachIndexed { index, key ->
            add(
                StageEntryBody(
                    entityKey = "costLine",
                    ordinal = index,
                    data = buildJsonObject { put("_clientKey", key) },
                )
            )
        }
    }

    private fun errors(vararg pairs: Pair<String, Map<String, String>>): Map<String, JsonElement> =
        pairs.associate { (scope, fields) ->
            scope to buildJsonObject { fields.forEach { (key, message) -> put(key, message) } }
        }

    @Test
    fun `what is stored is the addressing, not the answers and not the drawn card`() {
        val sent = entries("row-a", "row-b").map(::dwSentEntryOf)

        assertEquals(3, sent.size)
        assertEquals("costing", sent[0].entityKey)
        assertNull("a singleton has no ordinal and no row key", sent[0].ordinal)
        assertNull(sent[0].rowKey)
        assertEquals(DwSentEntry(entityKey = "costLine", ordinal = 0, rowKey = "row-a"), sent[1])
        assertEquals(DwSentEntry(entityKey = "costLine", ordinal = 1, rowKey = "row-b"), sent[2])
    }

    @Test
    fun `a blank client key is stored as no key at all`() {
        val entry = StageEntryBody(
            entityKey = "costLine",
            ordinal = 0,
            data = buildJsonObject { put("_clientKey", "  ") },
        )
        assertNull(dwSentEntryOf(entry).rowKey)
    }

    @Test
    fun `the record survives the on-disk round trip, and an older draft decodes to null`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val record = StageSyncRecord(
            signature = "sha",
            refusedFields = 2,
            refusal = DwStageRefusalRecord(
                errors = errors("costLine[1]" to mapOf("amount" to "must be a number")),
                sent = entries("row-a", "row-b").map(::dwSentEntryOf),
                at = "2026-08-13T00:31:00Z",
                droppedCustomKeys = listOf("dyeVatCount"),
            ),
        )

        val restored = json.decodeFromString(
            StageSyncRecord.serializer(),
            json.encodeToString(StageSyncRecord.serializer(), record),
        )
        assertEquals(record, restored)
        val stored = requireNotNull(restored.refusal)
        assertEquals(3, stored.sent.size)
        assertEquals("row-b", stored.sent[2].rowKey)
        assertEquals(listOf("dyeVatCount"), stored.droppedCustomKeys)

        val older = json.decodeFromString(
            StageSyncRecord.serializer(),
            """{"signature":"sha","refusedFields":2}""",
        )
        assertNull("additive and defaulted, so an older draft behaves exactly as it did", older.refusal)
        assertEquals(2, older.refusedFields)

        assertTrue(
            json.encodeToString(StageSyncRecord.serializer(), StageSyncRecord()).contains("\"refusal\":null")
        )
    }

    @Test
    fun `the same refusal decodes to the same box on the wire and an hour later off disk`() {
        val sentEntries = entries("row-a", "row-b")
        val errorMap = errors("costLine[2]" to mapOf("amount" to "must be a number"))

        val onTheWire = dwDecodeStageRefusals(spec, sentEntries, errorMap)
        val offDisk = requireNotNull(
            dwRestoreStageRefusals(
                spec,
                DwStageRefusalRecord(
                    errors = errorMap,
                    sent = sentEntries.map(::dwSentEntryOf),
                    at = "2026-08-13T00:31:00Z",
                ),
            )
        )

        val wire = onTheWire.refusals.single()
        val disk = offDisk.refusals.single()
        assertEquals("the second row of the collection, not the second entry", 1, wire.rowIndex)
        assertEquals(wire.rowIndex, disk.rowIndex)
        assertEquals(wire.address, disk.address)
        assertEquals("costLine[1]", disk.address)
        assertEquals(wire.fieldKey, disk.fieldKey)
        assertEquals("Amount", disk.label)
        assertEquals("row-b", disk.rowKey)
        assertEquals(onTheWire.byAddress, offDisk.byAddress)
    }

    @Test
    fun `a restored refusal is UNRECORDED, and says when it was recorded`() {
        val report = requireNotNull(
            dwRestoreStageRefusals(
                spec,
                DwStageRefusalRecord(
                    errors = errors("costing" to mapOf("totalCost" to "must be a number")),
                    sent = entries().map(::dwSentEntryOf),
                    at = "2026-08-13T00:31:00Z",
                ),
            )
        )

        assertEquals(DwHeldState.UNRECORDED, report.refusals.single().held.state)
        assertTrue("so the screen knows to measure it again", report.needsRead)
        assertEquals("2026-08-13T00:31:00Z", report.recordedAt)
        assertTrue(
            report.heading,
            report.heading.contains("Recorded when this stage was last saved online, at 2026-08-13T00:31:00Z.")
        )
    }

    @Test
    fun `a save that refused nothing stores nothing, so the card cannot outlive the correction`() {
        assertTrue(DwStageRefusalRecord().isEmpty)
        assertTrue(
            "the ordering alone is not a refusal — a clean save sends entries too",
            DwStageRefusalRecord(sent = entries("row-a").map(::dwSentEntryOf)).isEmpty
        )
        assertFalse(
            DwStageRefusalRecord(
                errors = errors("costing" to mapOf("totalCost" to "no")),
            ).isEmpty
        )
        assertFalse(DwStageRefusalRecord(droppedCustomKeys = listOf("dyeVatCount")).isEmpty)

        assertNull("nothing stored means no card", dwRestoreStageRefusals(spec, DwStageRefusalRecord()))
        assertNull("and no record at all is the same answer", dwRestoreStageRefusals(spec, null))
    }

    @Test
    fun `a stored dropped custom key alone still draws the card, with its own clause`() {
        val report = requireNotNull(
            dwRestoreStageRefusals(
                spec,
                DwStageRefusalRecord(
                    at = "2026-08-13T00:31:00Z",
                    droppedCustomKeys = listOf("dyeVatCount"),
                ),
            )
        )

        assertEquals("nobody objected to an answer here", 0, report.count)
        assertTrue(report.refusals.isEmpty())
        assertFalse("but there is something to say, so it is not empty", report.isEmpty)
        val heading = report.heading
        assertFalse(
            "the claim that contradicted the same response:\n$heading",
            heading.contains("Everything else in this stage was saved. ")
        )
        assertTrue(heading, heading.contains("which the sections no longer ask"))
        assertTrue(
            "and the remedy, which is a refresh and not a retype",
            heading.contains("open this workshop once with a connection")
        )
        assertTrue(heading, heading.contains("Nothing you typed has been thrown away"))
    }

    @Test
    fun `the heading claims the repository kept something only when a read found something`() {
        val decoded = dwDecodeStageRefusals(
            spec, entries(), errors("costing" to mapOf("totalCost" to "must be a number")),
        )

        assertFalse(
            "nothing has been measured yet, so nothing is promised:\n${decoded.heading}",
            decoded.heading.contains("the earlier answer was kept")
        )

        val held = dwHoldingsFrom(
            decoded, StageBucketDto(singleton = buildJsonObject { put("totalCost", 6500) }),
        )
        assertTrue(held.heading, held.heading.contains(", and the earlier answer was kept"))

        val empty = dwHoldingsFrom(decoded, StageBucketDto())
        assertFalse(
            "the read came back silent, so the promise is withdrawn:\n${empty.heading}",
            empty.heading.contains("the earlier answer was kept")
        )
        assertTrue(
            "and it says so, because a designer told their answer is safe will not go looking",
            empty.heading.contains("there is no earlier answer for it")
        )
    }

    @Test
    fun `a row that inherits a deleted row's position does not inherit its measured holding`() {
        val before = dwHoldingsFrom(
            dwDecodeStageRefusals(
                spec,
                entries("row-a", "row-b"),
                errors("costLine[2]" to mapOf("amount" to "must be a number")),
            ),
            StageBucketDto(
                collections = mapOf(
                    "costLine" to listOf(
                        buildJsonObject { put("_clientKey", "row-a"); put("amount", 100) },
                        buildJsonObject { put("_clientKey", "row-b"); put("amount", 900) },
                    )
                )
            ),
        )
        assertEquals("900", before.refusals.single().held.text)
        assertFalse(before.needsRead)

        val afterDeletion = dwDecodeStageRefusals(
            spec,
            entries("row-b"),
            errors("costLine[1]" to mapOf("amount" to "must be a number")),
        )
        assertEquals(
            "the same box on screen, which is exactly why the address cannot be the identity",
            "costLine[0]", afterDeletion.refusals.single().address,
        )

        val carried = dwCarryHoldings(before, afterDeletion)
        assertEquals(
            "the row's own key is what a measurement belongs to",
            "900", carried.refusals.single().held.text,
        )
        assertFalse("and it is still measured, so no second read is spent", carried.needsRead)

        val differentRow = dwCarryHoldings(
            before,
            dwDecodeStageRefusals(
                spec,
                entries("row-c"),
                errors("costLine[1]" to mapOf("amount" to "must be a number")),
            ),
        )
        assertEquals(
            DwHeldState.UNRECORDED,
            differentRow.refusals.single().held.state,
        )
        assertTrue("so one read corrects it", differentRow.needsRead)
    }

    @Test
    fun `a singleton's measurement is still carried, because its address is its identity`() {
        val first = dwHoldingsFrom(
            dwDecodeStageRefusals(spec, entries(), errors("costing" to mapOf("totalCost" to "no"))),
            StageBucketDto(singleton = buildJsonObject { put("totalCost", 6500) }),
        )
        val second = dwCarryHoldings(
            first,
            dwDecodeStageRefusals(spec, entries(), errors("costing" to mapOf("totalCost" to "no"))),
        )
        assertEquals("6500", second.refusals.single().held.text)
        assertFalse(second.needsRead)
        assertEquals("costing", second.refusals.single().carryKey)
    }

    @Test
    fun `the carry key is the row's own key where there is one and the address where there is not`() {
        val withKey = DwStageRefusal(
            entityKey = "costLine", rowIndex = 0, rowKey = "row-a", fieldKey = "amount",
            label = "Amount", message = "no", drawn = true,
        )
        assertEquals("row-a", withKey.carryKey)
        assertEquals("costLine[0]", withKey.address)

        val withoutKey = withKey.copy(rowKey = null)
        assertEquals("costLine[0]", withoutKey.carryKey)
    }
}
