package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DwWorkshopCards] on its own — the join between the draft store and the two code modules, with no
 * Compose, no filesDir and no server.
 *
 * WHAT THIS FILE IS ACTUALLY FOR. `DwWorkshopCodesTest` already pins the grammar and `DwQrEncodeTest`
 * already pins every module of every symbol against an independent encoder. Neither of them can catch
 * the failure this surface actually has, which is that the two modules are handed the WRONG THING: a
 * row whose client key this client keeps outside the object (see [codeRow]), a roster that is really
 * stage 6's baseline product table, a sheet that quietly holds 29 tags for 30 prototypes. Every test
 * below is one of those.
 *
 * The registry fixture is hand-built rather than read from the bundled asset, for the reason
 * `DesignWorkshopListingTest` gives: a fixture that changed when somebody edited stage 5 would make
 * every failure here ambiguous.
 */
class DwWorkshopCardsTest {

    /** A cuid, as the server issues for a row that has synced. */
    private val prototypeEntryId = "cmsik2jg8000eh8xc1lcy661a"

    /** A UUID client key, as this device issues for a row created in a courtyard. */
    private val prototypeClientKey = "9f3b1c2d-4e5a-4b6c-8d7e-0f1a2b3c4d5e"

    private val artisanId = "cmr7q2xa1000btd4m0zq9pk3v"

    private val registry = SchemaResponse(
        version = "cards-spec",
        stages = listOf(
            StageDto(
                number = 3, key = "PARTICIPANTS", title = "Participants",
                entities = listOf(
                    EntityDto(
                        key = "participant", name = "DwParticipant", cardinality = "COLLECTION",
                        title = "Participating artisans", labelField = "name",
                        fields = listOf(
                            FieldDto(key = "artisanRef", label = "Artisan record", type = "REF", refModel = "Artisan"),
                            FieldDto(key = "serialNo", label = "S. No.", type = "INT"),
                            FieldDto(key = "name", label = "Artisan name", type = "TEXT"),
                            FieldDto(key = "village", label = "Village", type = "TEXT"),
                            // On the row and never on the card. Its presence in the fixture is the
                            // point: the card builder must not be able to reach it.
                            FieldDto(key = "artisanCardNo", label = "Artisan ID / card number", type = "TEXT"),
                        ),
                    )
                ),
            ),
            StageDto(
                number = 6, key = "BASELINE", title = "Baseline products",
                entities = listOf(
                    // THE DECOY. Stage 6 also carries an `artisanRef` to `Artisan`, so a roster search
                    // that took the LAST match, or that walked entities before stages, would print
                    // "artisan cards" for a table of products.
                    EntityDto(
                        key = "existingProduct", name = "DwExistingProduct", cardinality = "COLLECTION",
                        title = "Existing products", labelField = "name",
                        fields = listOf(
                            FieldDto(key = "artisanRef", label = "Artisan", type = "REF", refModel = "Artisan"),
                            FieldDto(key = "name", label = "Product", type = "TEXT"),
                        ),
                    )
                ),
            ),
            StageDto(
                number = 13, key = "PROTOTYPE", title = "Prototype development",
                entities = listOf(
                    EntityDto(
                        key = "prototype", name = "DwPrototype", cardinality = "COLLECTION",
                        title = "Prototypes", labelField = "name",
                        fields = listOf(
                            FieldDto(key = "prototypeCode", label = "Prototype ID", type = "TEXT"),
                            FieldDto(key = "name", label = "Prototype name", type = "TEXT"),
                            FieldDto(key = "materials", label = "Materials", type = "TAGS"),
                        ),
                    )
                ),
            ),
        ),
    )

    private fun prototypeRow(clientKey: String, entryId: String? = null, name: String = "Low stool") = DraftRow(
        id = dwRowId("prototype", clientKey),
        values = buildMap {
            put("name", JsonPrimitive(name))
            put("prototypeCode", JsonPrimitive("PT-04"))
            put("materials", buildJsonArray { add("Sal wood"); add("Cane") })
            if (entryId != null) put("_entryId", JsonPrimitive(entryId))
        },
    )

    private fun participantRow(clientKey: String, artisanRef: String?) = DraftRow(
        id = dwRowId("participant", clientKey),
        values = buildMap {
            put("name", JsonPrimitive("Sita Devi"))
            put("serialNo", JsonPrimitive(4))
            put("village", JsonPrimitive("Bagru"))
            put("artisanCardNo", JsonPrimitive("RJ/BAG/2019/0148"))
            if (artisanRef != null) put("artisanRef", JsonPrimitive(artisanRef))
        },
    )

    private fun draftWith(stageKey: String, rows: List<DraftRow>) = WorkshopDraft(
        workshopId = "local-cards-spec",
        stages = mapOf(stageKey to StageDraft(stageId = stageKey, rows = rows)),
    )

    // ----------------------------------------------------------------------------------
    // Finding the two collections in the registry
    // ----------------------------------------------------------------------------------

    @Test
    fun `the prototype collection is found by its model name`() {
        val source = findPrototypeSource(registry)
        assertNotNull(source)
        assertEquals("PROTOTYPE", source!!.stage.key)
        assertEquals("prototype", source.entity.key)
    }

    @Test
    fun `the roster is the FIRST collection referring to an artisan, not the last`() {
        // Stage 6's product table refers to an Artisan too. Taking the last match would print cards
        // headed with product names and pointing at the artisan who makes them, which every designer
        // would read as the roster being wrong rather than the search being wrong.
        val source = findRosterSource(registry)
        assertNotNull(source)
        assertEquals("PARTICIPANTS", source!!.stage.key)
        assertEquals("participant", source.entity.key)
        assertEquals("artisanRef", artisanRefKey(source.entity))
    }

    @Test
    fun `a registry with neither collection answers null rather than an empty sheet`() {
        val bare = SchemaResponse(version = "bare", stages = emptyList())
        assertNull(findPrototypeSource(bare))
        assertNull(findRosterSource(bare))
        assertNull(workshopCardSource(DwWorkshopRecordType.PROTOTYPE, bare))
    }

    // ----------------------------------------------------------------------------------
    // The one place this port cannot be a transcription
    // ----------------------------------------------------------------------------------

    @Test
    fun `a row that has never synced is tagged with the client key held in its row id`() {
        // THE TEST THIS FILE EXISTS FOR. On the web the client key is a key of the row object; here it
        // is the suffix of DraftRow.id. Handing `row.values` straight to the code module answers null,
        // and null refuses as NO_ID — for precisely the row a tag is for, on the afternoon it is made.
        val row = prototypeRow(prototypeClientKey)
        assertNull("the raw values carry no client key", workshopCodeIdForRow(row.values))
        assertEquals(prototypeClientKey, workshopCodeIdForRow(row.codeRow()))
    }

    @Test
    fun `the server's own id wins over the client key when the row has synced`() {
        val row = prototypeRow(prototypeClientKey, entryId = prototypeEntryId)
        assertEquals(prototypeEntryId, workshopCodeIdForRow(row.codeRow()))
    }

    @Test
    fun `a client key already inside the row is left exactly as the server spelled it`() {
        // The server's `_clientKey` is what the sync matches on. Overwriting it with the local id would
        // print a tag the sync could not pair with the row it came from.
        val serverKey = "11112222-3333-4444-5555-666677778888"
        val row = DraftRow(
            id = dwRowId("prototype", prototypeClientKey),
            values = mapOf("_clientKey" to JsonPrimitive(serverKey), "name" to JsonPrimitive("Low stool")),
        )
        assertEquals(serverKey, workshopCodeIdForRow(row.codeRow()))
    }

    // ----------------------------------------------------------------------------------
    // Which copy of the workshop a sheet is printed from
    // ----------------------------------------------------------------------------------

    @Test
    fun `the local draft wins for a stage this device holds`() {
        val source = findPrototypeSource(registry)!!
        val draft = draftWith("PROTOTYPE", listOf(prototypeRow(prototypeClientKey, name = "Made this morning")))
        val remote = mapOf(
            "PROTOTYPE" to StageBucketDto(
                collections = mapOf("prototype" to listOf(JsonObject(mapOf("name" to JsonPrimitive("Stale")))))
            )
        )
        val rows = workshopCardRows(source, draft, remote)
        assertEquals(1, rows.size)
        assertEquals("Made this morning", DwValues.text(rows[0]["name"]))
    }

    @Test
    fun `the server's rows are used for a stage this device has never opened`() {
        // Otherwise a workshop somebody else started reads "no prototypes have been recorded yet" over
        // a workshop holding twenty-five — the silent-emptiness failure, and here it would have a
        // designer conclude the tags cannot be printed at all.
        val source = findPrototypeSource(registry)!!
        val remote = mapOf(
            "PROTOTYPE" to StageBucketDto(
                collections = mapOf(
                    "prototype" to listOf(
                        JsonObject(
                            mapOf(
                                "name" to JsonPrimitive("Entered on the laptop"),
                                "_entryId" to JsonPrimitive(prototypeEntryId),
                            )
                        )
                    )
                )
            )
        )
        val rows = workshopCardRows(source, draft = null, remoteStages = remote)
        assertEquals(1, rows.size)
        assertEquals(prototypeEntryId, workshopCodeIdForRow(rows[0]))
    }

    // ----------------------------------------------------------------------------------
    // What is printed on a card
    // ----------------------------------------------------------------------------------

    @Test
    fun `a prototype tag carries its name, its own code and its materials`() {
        val source = findPrototypeSource(registry)!!
        val rows = workshopCardRows(source, draftWith("PROTOTYPE", listOf(prototypeRow(prototypeClientKey))), emptyMap())
        val specs = workshopCardSpecs(DwWorkshopRecordType.PROTOTYPE, source, rows)
        assertEquals(1, specs.size)
        assertEquals("Low stool", specs[0].title)
        assertEquals(listOf("PT-04", "Sal wood, Cane"), specs[0].lines)
        assertEquals(prototypeClientKey, specs[0].id)
    }

    @Test
    fun `an artisan card carries the serial number and the village and nothing else`() {
        val source = findRosterSource(registry)!!
        val rows = workshopCardRows(
            source,
            draftWith("PARTICIPANTS", listOf(participantRow("a1b2c3d4-0000-4000-8000-000000000001", artisanId))),
            emptyMap(),
        )
        val specs = workshopCardSpecs(DwWorkshopRecordType.ARTISAN, source, rows)
        assertEquals(1, specs.size)
        assertEquals("Sita Devi", specs[0].title)
        assertEquals(listOf("S. No. 4", "Bagru"), specs[0].lines)
        // The artisan's repository id, not the roster row's — a card is scanned to open a person.
        assertEquals(artisanId, specs[0].id)
        // The card number is on the row and must reach neither the face nor the payload. A card is a
        // public object the moment it leaves the room.
        assertTrue(specs[0].lines.none { it.contains("RJ/BAG") })
        val rendered = renderWorkshopCard(specs[0])
        assertTrue(rendered is DwCardRender.Ok)
        assertTrue((rendered as DwCardRender.Ok).code.none { it == '/' })
        assertTrue(!rendered.code.contains("0148"))
    }

    // ----------------------------------------------------------------------------------
    // Rendering, and what happens to a row that cannot be printed
    // ----------------------------------------------------------------------------------

    @Test
    fun `a rendered tag round-trips through the printed form back to the same row`() {
        // Print and scan are ONE grammar with one validator. This is the whole feature in one
        // assertion: what goes on the card comes back off it and names the row it came from.
        val source = findPrototypeSource(registry)!!
        val draft = draftWith("PROTOTYPE", listOf(prototypeRow(prototypeClientKey)))
        val rows = workshopCardRows(source, draft, emptyMap())
        val card = renderWorkshopCard(workshopCardSpecs(DwWorkshopRecordType.PROTOTYPE, source, rows)[0])
        assertTrue(card is DwCardRender.Ok)
        val ok = card as DwCardRender.Ok

        // Read back the way a designer types it: off the printed line, spaces and all.
        val decoded = decodeWorkshopCode(ok.printed)
        assertTrue(decoded is DwDecodeResult.Ok)
        val ref = (decoded as DwDecodeResult.Ok).ref
        assertEquals(DwWorkshopRecordType.PROTOTYPE, ref.recordType)
        assertEquals(prototypeClientKey, ref.id)

        val hit = findWorkshopCodeInDraft(ref, registry, draft, emptyMap())
        assertNotNull(hit)
        assertEquals("Low stool", hit!!.label)
        assertTrue(hit.detail.contains("Prototype development"))
        assertTrue(hit.detail.contains("PT-04"))
    }

    @Test
    fun `every payload this app prints fits the symbol size the sheet reserves`() {
        // `CardSheetPdf` sizes its 26mm box so that a VERSION 4 symbol still clears 0.63mm per module,
        // and version 4 is what an offline row needs because its identifier is a 36-character UUID
        // rather than a 25-character cuid. If a payload ever grew past that the sheet would still
        // print — at a module size no camera can read in a courtyard — so the bound is pinned here
        // rather than left as a sentence in a comment.
        val source = findPrototypeSource(registry)!!
        val rows = workshopCardRows(
            source,
            draftWith("PROTOTYPE", listOf(prototypeRow(prototypeClientKey), prototypeRow("x", entryId = prototypeEntryId))),
            emptyMap(),
        )
        val cards = workshopCardSpecs(DwWorkshopRecordType.PROTOTYPE, source, rows).map { renderWorkshopCard(it) }
        assertEquals(2, cards.size)
        for (card in cards) {
            assertTrue("every fixture row must draw", card is DwCardRender.Ok)
            assertTrue("version ${(card as DwCardRender.Ok).symbol.version}", card.symbol.version <= 4)
            assertEquals(DwQrEccLevel.Q, card.symbol.level)
        }
    }

    @Test
    fun `a row with no identifier becomes a card carrying the reason, not a missing card`() {
        // A sheet that silently held 29 tags when the workshop has 30 is cut up, tied on, and found
        // out on the day the report is due.
        val source = findRosterSource(registry)!!
        val rows = workshopCardRows(
            source,
            draftWith(
                "PARTICIPANTS",
                listOf(
                    participantRow("a1b2c3d4-0000-4000-8000-000000000001", artisanId),
                    // Typed in by hand at the door on day two: a real participant with no artisan
                    // record behind them, and therefore nothing a card could point at.
                    participantRow("a1b2c3d4-0000-4000-8000-000000000002", artisanRef = null),
                ),
            ),
            emptyMap(),
        )
        val cards = workshopCardSpecs(DwWorkshopRecordType.ARTISAN, source, rows).map { renderWorkshopCard(it) }
        assertEquals("one card per row, always", 2, cards.size)
        assertTrue(cards[0] is DwCardRender.Ok)
        assertTrue(cards[1] is DwCardRender.Refused)
        assertTrue((cards[1] as DwCardRender.Refused).message.contains("no identifier yet"))
    }

    @Test
    fun `the anti-PII gate reaches the card surface`() {
        // The refusal lives in `encodeWorkshopCode`, but the assertion that matters is that a card
        // BUILDER cannot route around it — this is the one surface that turns a stored field into a
        // physical object somebody carries home.
        val aadhaarShaped = DwCardSpec(
            key = "k", recordType = DwWorkshopRecordType.ARTISAN,
            id = "2345 6789 0123", title = "Sita Devi", lines = emptyList(),
        )
        val card = renderWorkshopCard(aadhaarShaped)
        assertTrue(card is DwCardRender.Refused)
        assertTrue((card as DwCardRender.Refused).message.contains("Aadhaar"))
    }

    // ----------------------------------------------------------------------------------
    // Reading one back
    // ----------------------------------------------------------------------------------

    @Test
    fun `an artisan card on this workshop's roster is answered from the device`() {
        val draft = draftWith("PARTICIPANTS", listOf(participantRow("a1b2c3d4-0000-4000-8000-000000000001", artisanId)))
        val hit = findWorkshopCodeInDraft(
            DwWorkshopCodeRef(DwWorkshopRecordType.ARTISAN, artisanId), registry, draft, emptyMap(),
        )
        assertNotNull(hit)
        assertEquals("Sita Devi", hit!!.label)
        assertTrue(hit.detail.contains("roster"))
    }

    @Test
    fun `a card that is not on the roster answers null so the caller may ask the server`() {
        // NULL IS NOT A REFUSAL. An artisan card may name somebody documented in another workshop, and
        // deciding that here would put a network call in a pure function. A prototype tag, by
        // contrast, has nowhere else to be looked for — but that decision is the screen's too.
        val draft = draftWith("PARTICIPANTS", listOf(participantRow("a1b2c3d4-0000-4000-8000-000000000001", artisanId)))
        assertNull(
            findWorkshopCodeInDraft(
                DwWorkshopCodeRef(DwWorkshopRecordType.ARTISAN, "cmzzzzzzz000000000000000z"), registry, draft, emptyMap(),
            )
        )
        assertNull(
            findWorkshopCodeInDraft(
                DwWorkshopCodeRef(DwWorkshopRecordType.PROTOTYPE, prototypeClientKey), registry, draft, emptyMap(),
            )
        )
    }

    @Test
    fun `a tag read one character wrong is refused rather than resolved to another row`() {
        // The check digit's whole purpose, asserted at the surface that uses it: one character out in
        // an identifier is a different record or no record at all, and nothing downstream would notice.
        val source = findPrototypeSource(registry)!!
        val draft = draftWith("PROTOTYPE", listOf(prototypeRow(prototypeClientKey)))
        val rows = workshopCardRows(source, draft, emptyMap())
        val ok = renderWorkshopCard(workshopCardSpecs(DwWorkshopRecordType.PROTOTYPE, source, rows)[0]) as DwCardRender.Ok
        // Change one character of the id, leaving the check as printed.
        val broken = ok.code.replaceFirst("9F3B", "9F3C")
        assertTrue("the fixture must actually have been changed", broken != ok.code)
        val decoded = decodeWorkshopCode(broken)
        assertTrue(decoded is DwDecodeResult.Refused)
        assertEquals(DwDecodeRefusal.CHECK_FAILED, (decoded as DwDecodeResult.Refused).reason)
    }
}
