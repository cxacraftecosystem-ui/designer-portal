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

/**
 * THE REFUSAL CARD WAS ERASED BY THE APP'S OWN ADVICE.
 *
 * The card naming every refused answer, and the marks on the boxes beside them, lived only in
 * `StageScreen`'s `remember(stageKey)` state. Leaving the stage destroyed all of it. And the note
 * `recordStageSent` writes onto [StageSyncRecord.failure] — the one thing that DID outlive the screen,
 * and the only thing a designer sees from the workshop list — says, in these words:
 *
 *   *"the repository refused N of the answers in this stage and kept what it already held for them.
 *   Everything else on the stage was saved, and nothing you typed has been thrown away — OPEN THE
 *   STAGE to see which answers, and what the repository holds."*
 *
 * So the instruction the app gave was the action that erased the evidence. A designer who followed it
 * arrived at a stage with nothing on it, while [StageSyncRecord.refusedFields] went on counting and the
 * workshop went on refusing to say "Backed up to the server" — a phone insisting something was wrong
 * and having lost the only surface that said what.
 *
 * ── WHAT IS PINNED HERE ──────────────────────────────────────────────────────────────────────────
 *
 * That [DwStageRefusalRecord] carries exactly enough to REBUILD the card rather than a frozen copy of
 * it, that it survives the on-disk round trip a draft actually makes, that a restored refusal is honest
 * about what it has not measured, and that a corrected answer clears it. Plus the one thing a stored
 * refusal made newly reachable: a measurement carried onto a row that has MOVED.
 */
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

    /**
     * A payload of the shape `buildStageBody` sends: the singleton first, then one entry per row,
     * each carrying the `_clientKey` that is the identity `save_stage` matches a row on.
     *
     * THE CLIENT KEYS ARE THE POINT OF THIS HELPER. The sibling suite's payload builder omits them,
     * which is why the carry tests there cannot see the defect the last case below covers.
     */
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

    // ── What is stored, and what is deliberately not ──────────────────────────────────────────────

    /**
     * The record stores the ERROR MAP and the ORDERING, and the ordering is three fields per entry.
     * Not the entries: a stage's entries carry every answer the designer typed, and a second copy of
     * them on disk would be a second thing to keep in step with the draft, for a decode that reads
     * exactly which entity the position held, the row's ordinal, and its client key.
     */
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

    /** A blank client key is not a key. It would match a row by accident and quote its value. */
    @Test
    fun `a blank client key is stored as no key at all`() {
        val entry = StageEntryBody(
            entityKey = "costLine",
            ordinal = 0,
            data = buildJsonObject { put("_clientKey", "  ") },
        )
        assertNull(dwSentEntryOf(entry).rowKey)
    }

    /**
     * THE ON-DISK ROUND TRIP, THROUGH THE STORE'S OWN SETTINGS.
     *
     * The record rides inside a [StageSyncRecord] in a draft that is written to flash and read back by
     * a build that may not be the one that wrote it, so the shape matters as much as the values.
     *
     * THE STORE'S OWN SETTINGS, COPIED RATHER THAN ASSUMED — `WorkshopDraftStore.json` is
     * `Json { ignoreUnknownKeys = true; encodeDefaults = true }`, and both halves are what make this
     * field additive in BOTH directions, which is the rule this repository requires of a change that
     * owes no schema rung:
     *
     *  * `encodeDefaults = true` writes `"refusal": null` for every stage that has none, so the
     *    document shape is uniform and nothing has to remember to omit it;
     *  * the constructor default is what a draft written by an EARLIER build decodes to — it has no
     *    `refusal` key at all, and must come back null rather than throw;
     *  * `ignoreUnknownKeys = true` is the other direction: an OLDER build reading a draft this one
     *    wrote skips the key instead of refusing the document. It drops the record when it rewrites,
     *    which costs exactly the card and is the behaviour that build already had.
     */
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

        // A draft written by any earlier build — no `refusal` key in the document at all.
        val older = json.decodeFromString(
            StageSyncRecord.serializer(),
            """{"signature":"sha","refusedFields":2}""",
        )
        assertNull("additive and defaulted, so an older draft behaves exactly as it did", older.refusal)
        assertEquals(2, older.refusedFields)

        // AND THE UNIFORM SHAPE THE STORE ACTUALLY WRITES, so nobody later "optimises" the null away
        // and breaks the ladder's expectations about what a stage record looks like on disk.
        assertTrue(
            json.encodeToString(StageSyncRecord.serializer(), StageSyncRecord()).contains("\"refusal\":null")
        )
    }

    // ── The card, rebuilt ─────────────────────────────────────────────────────────────────────────

    /**
     * A REFUSAL DECODED OFF DISK LANDS ON THE SAME BOX AS THE SAME REFUSAL DECODED ON THE WIRE.
     *
     * This is the whole reason the record stores the ordering rather than the drawn card. The scope key
     * `costLine[2]` is an index into the ENTRIES THAT WERE SENT — not the row's ordinal and not its
     * position within its collection — so a payload sending a singleton and two rows files the second
     * row's errors under `costLine[2]`. Two paths that each had their own reading of that would put the
     * same message on two different rows an hour apart.
     */
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
        // And the boxes the form marks are the same set.
        assertEquals(onTheWire.byAddress, offDisk.byAddress)
    }

    /**
     * WHAT THE REPOSITORY HOLDS IS NOT STORED, AND THE RESTORED CARD SAYS SO IN THAT WORD.
     *
     * It is measured by a `GET .../stages/{key}` that may have happened on a connection that no longer
     * exists, so a value quoted off disk as "the repository still holds" could be a day old — the one
     * guess that would make this surface a second way of lying. Restored, every refusal is UNRECORDED
     * and `needsRead` is true, which is what tells the screen the one read is worth making.
     */
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
        // DATED ON THE CARD. A designer who has just pressed save does not need to be told when; one
        // who came back on the app's own instruction is looking at what the repository said THEN.
        assertTrue(
            report.heading,
            report.heading.contains("Recorded when this stage was last saved to the repository, at 2026-08-13T00:31:00Z.")
        )
    }

    /**
     * A CORRECTED ANSWER CLEARS THE CARD. `recordStageSent` writes the record only when there is
     * something in it, so the save carrying the correction stores null over it — one event clearing the
     * count and the addressing together, which is what stops a red mark outliving its correction. This
     * pins the emptiness rule the write turns on.
     */
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
        // And a response that refused nothing but stored nothing under this workshop's own retired
        // questions is NOT empty: it has something to say and the card is drawn for it.
        assertFalse(DwStageRefusalRecord(droppedCustomKeys = listOf("dyeVatCount")).isEmpty)

        assertNull("nothing stored means no card", dwRestoreStageRefusals(spec, DwStageRefusalRecord()))
        assertNull("and no record at all is the same answer", dwRestoreStageRefusals(spec, null))
    }

    /**
     * A response can carry `droppedCustomKeys` AND NO ERRORS AT ALL, and the card is drawn for it —
     * with a heading that does not claim everything else was saved, because this same response says
     * otherwise.
     */
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

    /**
     * "AND KEPT WHAT IT ALREADY HELD FOR THEM" IS A PROMISE ABOUT AN EMPTY BOX WHEN THE BOX IS EMPTY.
     *
     * It is the repository's documented behaviour — `save_stage` deliberately puts the stored value
     * back — but read as *your previous answer is safe there*, and for a question the repository holds
     * NOTHING for that sends a designer looking for something that was never there. So it is claimed
     * only where a read has actually found a value under at least one refused key.
     */
    @Test
    fun `the heading claims the repository kept something only when a read found something`() {
        val decoded = dwDecodeStageRefusals(
            spec, entries(), errors("costing" to mapOf("totalCost" to "must be a number")),
        )

        assertFalse(
            "nothing has been measured yet, so nothing is promised:\n${decoded.heading}",
            decoded.heading.contains("kept what it already held")
        )

        val held = dwHoldingsFrom(
            decoded, StageBucketDto(singleton = buildJsonObject { put("totalCost", 6500) }),
        )
        assertTrue(held.heading, held.heading.contains("and kept what it already held for it"))

        val empty = dwHoldingsFrom(decoded, StageBucketDto())
        assertFalse(
            "the read came back silent, so the promise is withdrawn:\n${empty.heading}",
            empty.heading.contains("kept what it already held")
        )
        assertTrue(
            "and it says so, because a designer told their answer is safe will not go looking",
            empty.heading.contains("holds no previous answer under it")
        )
    }

    // ── The measurement that was inherited by whoever took the row's position ──────────────────────

    /**
     * A DELETED ROW'S MEASUREMENT MUST NOT BE INHERITED BY THE ROW THAT TAKES ITS PLACE.
     *
     * Carried on the ADDRESS alone, a measured holding belonged to a POSITION: delete row 2 of a
     * costing table and row 3 slides up into `costLine[1]`, matches the measurement taken against the
     * row that is now gone, and inherits it — while [DwStageRefusalReport.needsRead] goes FALSE on the
     * strength of that match, so no read is ever made to correct it. The card then quotes one row's
     * stored amount against another row's line, confidently, for as long as the refusal stands.
     *
     * WHY THE SIBLING SUITE CANNOT SEE THIS. `DwStageRefusalTest`'s payload helper builds its rows with
     * no `_clientKey`, so every `carryKey` there falls back to the address and its case
     * `a refusal that moved to a different row is measured again rather than assumed` passes with or
     * without the fix. It pins a CHANGED address; this pins an address that stayed the same while the
     * row underneath it changed, which is the shape a deletion actually produces.
     */
    @Test
    fun `a row that inherits a deleted row's position does not inherit its measured holding`() {
        // Two rows. The second one's amount is refused, and a read measures what the repository holds.
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

        // The designer now deletes row-a. row-b keeps its own client key but slides into the position
        // the measurement was taken against: the refusal's address is `costLine[0]` either way.
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

        // And the other direction, which is the defect: a DIFFERENT row now sitting where the measured
        // one was must come out UNRECORDED rather than quoting the gone row's amount.
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

    /**
     * A singleton and the custom container have no row key, and the address IS their whole identity —
     * so the carry still works for them. Without this the frugality argument would be lost for the
     * commonest refusal of all.
     */
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

    /** A row that carried a client key keys on it; one that did not falls back to its address. */
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
