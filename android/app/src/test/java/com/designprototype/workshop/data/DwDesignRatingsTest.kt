package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

class DwDesignRatingsTest {

    private fun ranked(
        id: String,
        score: Double? = null,
        count: Int = 0,
        defaultPosition: Int = 0,
        placedPosition: Int = 0,
        ordinal: Int? = null,
    ) = RankedSubjectDto(
        subjectId = id,
        entityKey = "prototype",
        label = id.uppercase(),
        workshopId = "w1",
        score = score,
        ratingCount = count,
        defaultPosition = defaultPosition,
        placedPosition = placedPosition,
        ordinal = ordinal,
    )

    private fun row(
        clientKey: String,
        entryId: String? = null,
        entity: String = "prototype",
        extra: Map<String, String> = emptyMap(),
    ): DraftRow = DraftRow(
        id = dwRowId(entity, clientKey),
        values = buildMap {
            entryId?.let { put("_entryId", JsonPrimitive(it)) }
            extra.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        },
    )

    @Test
    fun `the score order is the server's default position and the placed order is its ordinal`() {
        val items = listOf(
            ranked("a", score = 3.0, count = 2, defaultPosition = 3, placedPosition = 1),
            ranked("b", score = 5.0, count = 4, defaultPosition = 1, placedPosition = 2),
            ranked("c", score = 4.0, count = 1, defaultPosition = 2, placedPosition = 3),
        )
        assertEquals(listOf("b", "c", "a"), dwScoreOrder(items))
        assertEquals(listOf("a", "b", "c"), dwPlacedOrder(items))
    }

    @Test
    fun `two pieces on the same position are broken apart by id rather than by scan order`() {
        val items = listOf(
            ranked("zeta", defaultPosition = 1, placedPosition = 1),
            ranked("alpha", defaultPosition = 1, placedPosition = 1),
        )
        assertEquals(listOf("alpha", "zeta"), dwScoreOrder(items))
        assertEquals(listOf("alpha", "zeta"), dwPlacedOrder(items))
    }

    @Test
    fun `an unfixed list opens in score order and a fixed one opens in the designers' order`() {
        val items = listOf(
            ranked("a", defaultPosition = 2, placedPosition = 1),
            ranked("b", defaultPosition = 1, placedPosition = 2),
        )
        assertEquals(listOf("b", "a"), dwOpeningOrder(items, fixed = null))
        assertEquals(
            listOf("a", "b"),
            dwOpeningOrder(items, fixed = DwFixedOrderStamp("Asha", "2026-08-20")),
        )
    }

    @Test
    fun `on a fixed list this device's own row order beats the server's ordinal`() {
        val items = listOf(
            ranked("a", defaultPosition = 1, placedPosition = 1),
            ranked("b", defaultPosition = 2, placedPosition = 2),
        )
        val heldNewestFirst = listOf("b", "a")
        assertEquals(
            listOf("b", "a"),
            dwOpeningOrder(items, DwFixedOrderStamp("Asha", "2026-08-20"), heldNewestFirst),
        )
    }

    @Test
    fun `an unfixed list ignores the local row order, because the scores govern by rule`() {
        val items = listOf(
            ranked("a", defaultPosition = 2, placedPosition = 1),
            ranked("b", defaultPosition = 1, placedPosition = 2),
        )
        assertEquals(listOf("b", "a"), dwOpeningOrder(items, fixed = null, held = listOf("a", "b")))
    }

    @Test
    fun `reconciling drops pieces that have gone and appends new ones in score order`() {
        val order = listOf("gone", "b", "a")
        val items = listOf(
            ranked("a", defaultPosition = 3),
            ranked("b", defaultPosition = 2),
            ranked("new", defaultPosition = 1),
        )
        assertEquals(listOf("b", "a", "new"), dwReconcileOrder(order, items))
    }

    @Test
    fun `one step up and one step down move exactly one place`() {
        val order = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), dwMoveBy(order, "b", -1))
        assertEquals(listOf("a", "c", "b"), dwMoveBy(order, "b", 1))
    }

    @Test
    fun `a step past either end is a no-op rather than an error or a wrap`() {
        val order = listOf("a", "b", "c")
        assertEquals(order, dwMoveBy(order, "a", -1))
        assertEquals(order, dwMoveBy(order, "c", 1))
        assertEquals(order, dwMoveTo(order, 0, -5))
        assertEquals(order, dwMoveTo(order, 2, 99))
    }

    @Test
    fun `moving an id the order does not hold changes nothing`() {
        val order = listOf("a", "b")
        assertEquals(order, dwMoveBy(order, "ghost", 1))
        assertEquals(order, dwMoveTo(order, 7, 0))
    }

    @Test
    fun `a long move closes the gap behind it rather than swapping two rows`() {
        assertEquals(
            listOf("d", "a", "b", "c"),
            dwMoveTo(listOf("a", "b", "c", "d"), 3, 0),
        )
        assertEquals(
            listOf("b", "c", "d", "a"),
            dwMoveTo(listOf("a", "b", "c", "d"), 0, 3),
        )
    }

    @Test
    fun `a stamp on any row counts, and the most recent one wins`() {
        val rows = listOf(
            row("k1", "e1"),
            row("k2", "e2", extra = mapOf("rankFixedBy" to "Asha", "rankFixedAt" to "2026-08-10")),
            row("k3", "e3", extra = mapOf("rankFixedBy" to "Ravi", "rankFixedAt" to "2026-08-19")),
        )
        assertEquals(DwFixedOrderStamp("Ravi", "2026-08-19"), dwFixedOrderStamp(rows))
    }

    @Test
    fun `half a stamp is not a stamp`() {
        assertNull(dwFixedOrderStamp(listOf(row("k1", "e1", extra = mapOf("rankFixedBy" to "Asha")))))
        assertNull(
            dwFixedOrderStamp(listOf(row("k1", "e1", extra = mapOf("rankFixedAt" to "2026-08-10"))))
        )
        assertNull(
            dwFixedOrderStamp(
                listOf(row("k1", "e1", extra = mapOf("rankFixedBy" to " ", "rankFixedAt" to " ")))
            )
        )
    }

    @Test
    fun `today's stamp is the yyyy-mm-dd a registry DATE field stores`() {
        assertEquals("2026-08-26", dwTodayStamp(LocalDate.of(2026, 8, 26)))
        assertEquals("2026-01-05", dwTodayStamp(LocalDate.of(2026, 1, 5)))
    }

    @Test
    fun `arranging rows reorders by server id and stamps every row, not only the moved one`() {
        val rows = listOf(row("k1", "e1"), row("k2", "e2"), row("k3", "e3"))
        val arranged = dwArrangeRows(rows, listOf("e3", "e1", "e2"), DwFixedOrderStamp("Asha", "2026-08-26"))
        assertEquals(listOf("e3", "e1", "e2"), arranged.map { it.dwEntryId() })
        assertTrue(arranged.all { it.values["rankFixedBy"] == JsonPrimitive("Asha") })
        assertTrue(arranged.all { it.values["rankFixedAt"] == JsonPrimitive("2026-08-26") })
    }

    @Test
    fun `a null stamp clears both fields on every row, which is the way back to the default`() {
        val rows = listOf(
            row("k1", "e1", extra = mapOf("rankFixedBy" to "Asha", "rankFixedAt" to "2026-08-10"))
        )
        val cleared = dwArrangeRows(rows, listOf("e1"), null)
        assertEquals(JsonPrimitive(""), cleared.single().values["rankFixedBy"])
        assertEquals(JsonPrimitive(""), cleared.single().values["rankFixedAt"])
        assertNull(dwFixedOrderStamp(cleared))
    }

    @Test
    fun `a row the order does not name keeps its place at the end instead of being dropped`() {
        val rows = listOf(row("k1", "e1"), row("unsent"), row("k2", "e2"))
        val arranged = dwArrangeRows(rows, listOf("e2", "e1"), null)
        assertEquals(listOf("e2", "e1", null), arranged.map { it.dwEntryId() })
        assertEquals(3, arranged.size)
    }

    @Test
    fun `an entry id of the literal string null is not an id`() {
        val nulled = DraftRow(
            id = dwRowId("prototype", "k1"),
            values = mapOf("_entryId" to kotlinx.serialization.json.JsonNull),
        )
        assertNull(nulled.dwEntryId())
        assertNull(DraftRow(id = dwRowId("prototype", "k2")).dwEntryId())
        assertNull(row("k3", entryId = "  ").dwEntryId())
    }

    @Test
    fun `an arrangement with no name on it is refused before anything is written`() {
        val plan = dwPlanArrangement(
            held = listOf(row("k1", "e1")),
            order = listOf("e1"),
            stamp = DwFixedOrderStamp(by = "   ", at = "2026-08-26"),
            stageSeen = true,
        )
        val refused = plan as DwArrangementPlan.Refused
        assertTrue(refused.reason.contains("your name could not be recorded"))
    }

    @Test
    fun `an order of eight pieces over zero held rows is refused rather than blanking the entity`() {
        val plan = dwPlanArrangement(
            held = emptyList(),
            order = listOf("e1", "e2"),
            stamp = DwFixedOrderStamp("Asha", "2026-08-26"),
            stageSeen = false,
        )
        val refused = plan as DwArrangementPlan.Refused
        assertTrue(refused.reason.contains("these pieces are not on this phone"))
    }

    @Test
    fun `returning to score order is refused on a stage this phone has never read`() {
        val plan = dwPlanArrangement(
            held = listOf(row("k1", "e1")),
            order = listOf("e1"),
            stamp = null,
            stageSeen = false,
        )
        val refused = plan as DwArrangementPlan.Refused
        assertTrue(refused.reason.contains("open this stage once with a connection"))
    }

    @Test
    fun `a workshop that has never left the phone returns to score order as normal`() {
        val plan = dwPlanArrangement(
            held = listOf(row("unsent-a"), row("unsent-b")),
            order = emptyList(),
            stamp = null,
            stageSeen = false,
        )
        assertTrue(plan is DwArrangementPlan.Write)
    }

    @Test
    fun `a readable stage writes the rearranged rows and carries the stamp with them`() {
        val plan = dwPlanArrangement(
            held = listOf(row("k1", "e1"), row("k2", "e2")),
            order = listOf("e2", "e1"),
            stamp = DwFixedOrderStamp("Asha", "2026-08-26"),
            stageSeen = true,
        )
        val write = plan as DwArrangementPlan.Write
        assertEquals(listOf("e2", "e1"), write.rows.map { it.dwEntryId() })
        assertEquals(DwFixedOrderStamp("Asha", "2026-08-26"), write.stamp)
        assertTrue(write.rows.all { it.entityKey() == "prototype" })
    }

    @Test
    fun `the pool round opens on a piece only when its own date is filled in`() {
        assertFalse(dwPoolIsOpen(row("k1", "e1")))
        assertFalse(dwPoolIsOpen(row("k1", "e1", extra = mapOf("peerRoundClosedAt" to ""))))
        assertFalse(dwPoolIsOpen(row("k1", "e1", extra = mapOf("peerRoundClosedAt" to "   "))))
        assertTrue(dwPoolIsOpen(row("k1", "e1", extra = mapOf("peerRoundClosedAt" to "2026-08-01"))))
    }

    @Test
    fun `a sketch is gated by its own date exactly as a prototype is`() {
        assertTrue(
            dwPoolIsOpen(
                row("k1", "e1", entity = "sketch", extra = mapOf("peerRoundClosedAt" to "2026-08-01"))
            )
        )
        assertFalse(dwPoolIsOpen(row("k1", "e1", entity = "sketch")))
    }

    @Test
    fun `the open count is what an empty pool round says about itself`() {
        val rows = listOf(
            row("k1", "e1", extra = mapOf("peerRoundClosedAt" to "2026-08-01")),
            row("k2", "e2"),
            row("k3", "e3", extra = mapOf("peerRoundClosedAt" to "2026-08-04")),
        )
        assertEquals(2, dwPoolOpenCount(rows))
        assertEquals(0, dwPoolOpenCount(listOf(row("k1", "e1"))))
    }

    @Test
    fun `both rounds are addressable by their wire token and an unknown one is not guessed at`() {
        assertEquals(DwRatingRound.PEER, DwRatingRound.of("PEER"))
        assertEquals(DwRatingRound.POOL, DwRatingRound.of("pool"))
        assertNull(DwRatingRound.of("JURY"))
        assertNull(DwRatingRound.of(null))
        assertEquals(listOf("PEER", "POOL"), DwRatingRound.entries.map { it.wire })
    }

    @Test
    fun `the two rateable entities are exactly the pair the API accepts`() {
        assertEquals(setOf("prototype", "sketch"), DwRateableEntity.entries.map { it.wire }.toSet())
        assertEquals(DwRateableEntity.PROTOTYPE, DwRateableEntity.of("prototype"))
        assertNull(DwRateableEntity.of("prototypeMaterial"))
        assertNull(DwRateableEntity.of("prototypeStageLog"))
    }

    @Test
    fun `the pool hints are the web's words and the peer hints do not claim anything was released`() {
        assertEquals(
            "The prototypes in this workshop's pool round.",
            DwRateableEntity.PROTOTYPE.hint(DwRatingRound.POOL),
        )
        assertTrue(DwRateableEntity.SKETCH.hint(DwRatingRound.POOL).contains("never prototyped"))
        DwRateableEntity.entries.forEach { entity ->
            assertFalse(entity.hint(DwRatingRound.PEER).contains("pool"))
        }
    }

    @Test
    fun `the raw ordinal's presence is the whole answer to may I write an order back`() {
        assertTrue(dwMayArrange(listOf(ranked("a", ordinal = 0), ranked("b", ordinal = 1))))
        assertFalse(dwMayArrange(listOf(ranked("a", ordinal = 0), ranked("b"))))
        assertFalse(dwMayArrange(listOf(ranked("a"))))
        assertFalse(dwMayArrange(emptyList()))
    }

    @Test
    fun `an unrated piece says so instead of scoring zero`() {
        assertEquals("Not rated yet", dwScoreText(null, 0))
        assertEquals("Not rated yet", dwScoreText(4.5, 0))
        assertEquals("4.2 from 5 designers", dwScoreText(4.236, 5))
        assertEquals("3.0 from 1 designer", dwScoreText(3.0, 1))
    }

    @Test
    fun `the placed position is printed only where it describes the whole collection`() {
        val item = ranked("a", defaultPosition = 1, placedPosition = 3)
        assertEquals(
            "The designers place it 3 · scores put it 1",
            dwPositionText(item, showPlaced = true, fixedOrder = true),
        )
        assertEquals(
            "scores put it 1 (this list is in score order)",
            dwPositionText(item, showPlaced = false, fixedOrder = false),
        )
    }

    private fun ledger(
        rows: List<DesignRatingDto> = emptyList(),
        count: Int = rows.size,
        canRead: Boolean = false,
        namesShown: Boolean = false,
    ) = SubjectLedgerDto(
        subject = RatingSubjectDto(id = "s1", entityKey = "prototype", label = "P1", workshopId = "w1"),
        round = "PEER",
        summary = RatingSummaryDto(score = if (count == 0) null else 4.0, ratingCount = count),
        ratings = rows,
        canReadLedger = canRead,
        namesShown = namesShown,
    )

    @Test
    fun `nobody has rated this and not yours to see are two different sentences`() {
        assertEquals("Nobody has rated this piece yet.", dwLedgerEmptyNote(ledger(count = 0)))
        val withheld = dwLedgerEmptyNote(ledger(count = 5, canRead = false)).orEmpty()
        assertTrue(withheld.contains("5 designers have rated this piece"))
        assertTrue(withheld.contains("You can see the score, not who gave it."))
        assertEquals(
            "No ratings could be shown for this piece.",
            dwLedgerEmptyNote(ledger(count = 5, canRead = true)),
        )
    }

    @Test
    fun `there is no empty note when there are rows to draw`() {
        val note = dwLedgerEmptyNote(
            ledger(rows = listOf(DesignRatingDto(id = "r1", score = 4)), canRead = true)
        )
        assertNull(note)
    }

    @Test
    fun `the withheld-names note names the server as the decider, and only when names are withheld`() {
        val rows = listOf(DesignRatingDto(id = "r1", score = 4))
        assertNull(dwLedgerNamesNote(ledger(rows = rows, namesShown = true)))
        assertNull(dwLedgerNamesNote(ledger(count = 3, namesShown = false)))
        val note = dwLedgerNamesNote(ledger(rows = rows, namesShown = false)).orEmpty()
        assertTrue(note.contains("not shown in this round"))
    }

    @Test
    fun `a row's attribution is read off the row and never off the list-level flag`() {
        assertEquals("your rating", dwRatingAttribution(DesignRatingDto(id = "r1", mine = true)))
        assertEquals(
            "reviewer u9",
            dwRatingAttribution(DesignRatingDto(id = "r2", reviewerId = "u9")),
        )
        assertEquals(
            "reviewer not named",
            dwRatingAttribution(DesignRatingDto(id = "r3")),
        )
        assertEquals(
            "reviewer not named",
            dwRatingAttribution(DesignRatingDto(id = "r4", reviewerId = "  ")),
        )
    }

    @Test
    fun `both clocks are printed when they name different days and one when they do not`() {
        val tz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            val apart = dwRatingClockLine(
                DesignRatingDto(
                    id = "r1",
                    ratedAt = "2026-08-01T09:00:00+00:00",
                    createdAt = "2026-08-15T04:30:00+00:00",
                )
            )
            assertTrue(apart.startsWith("Judged 1 Aug 2026"))
            assertTrue(apart.contains("uploaded 15 Aug 2026"))
            val sameDay = dwRatingClockLine(
                DesignRatingDto(
                    id = "r2",
                    ratedAt = "2026-08-01T09:00:00+00:00",
                    createdAt = "2026-08-01T09:00:04+00:00",
                )
            )
            assertEquals("Judged 1 Aug 2026", sameDay)
            assertEquals(
                "Judged 1 Aug 2026",
                dwRatingClockLine(DesignRatingDto(id = "r3", createdAt = "2026-08-01T09:00:00+00:00")),
            )
        } finally {
            TimeZone.setDefault(tz)
        }
    }

    @Test
    fun `a stamp is read in the reader's own zone and not in UTC`() {
        val tz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            assertEquals("2 Mar 2026", dwRatingDay("2026-03-01T20:00:00+00:00"))
            assertEquals("1 Mar 2026", dwRatingDay("2026-03-01"))
            assertTrue(dwRatingDay("2026-03-01").contains("2026"))
            assertEquals("-", dwRatingDay(null))
            assertEquals("-", dwRatingDay("not a date"))
        } finally {
            TimeZone.setDefault(tz)
        }
    }

    @Test
    fun `a replay is reported as the success it is, and never as a second filing`() {
        assertEquals(
            "This rating was already saved, unchanged.",
            dwRatingSavedNote(replayed = true, amended = false),
        )
        assertEquals("Your rating has been amended.", dwRatingSavedNote(false, amended = true))
        assertEquals("Your rating has been recorded.", dwRatingSavedNote(false, amended = false))
    }

    @Test
    fun `an unreachable round is never reported as an empty one`() {
        val offline = dwRoundFailure(offline = true, refusal = "ignored")
        assertTrue(offline.offline)
        assertTrue(offline.message.contains("could not be loaded"))
        val refused = dwRoundFailure(offline = false, refusal = DW_ROUND_REFUSED)
        assertFalse(refused.offline)
        assertEquals(DW_ROUND_REFUSED, refused.message)
    }

    @Test
    fun `every stage-push outcome gets a sentence, and neither no-op reads as a failure`() {
        assertEquals("Saved. This arrangement was already uploaded.", dwPushNote(StagePush.AlreadySent))
        assertTrue(dwPushNote(StagePush.NoRemoteYet).contains("after the workshop itself has been uploaded"))
        assertTrue(dwPushNote(StagePush.HeldBack(1)).contains("1 attachment "))
        assertTrue(dwPushNote(StagePush.HeldBack(3)).contains("3 attachments"))
        assertTrue(dwPushNote(StagePush.NotSent).contains("next sync"))
        assertTrue(dwPushNote(StagePush.NothingToSend).contains("once this stage has been opened"))
        assertEquals(
            "Saved on this phone and uploaded.",
            dwPushNote(StagePush.Sent(StageSaveResultDto())),
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a ranking row decodes when the server sends none of the optional keys`() {
        val row = json.decodeFromString<RankedSubjectDto>("""{"subjectId":"s1"}""")
        assertEquals("s1", row.subjectId)
        assertNull(row.score)
        assertEquals(0, row.ratingCount)
        assertNull(row.myRating)
        assertNull(row.ordinal)
    }

    @Test
    fun `an omitted reviewerId decodes as absent and never as an empty name`() {
        val hidden = json.decodeFromString<DesignRatingDto>(
            """{"id":"r1","score":4,"mine":false}"""
        )
        assertNull(hidden.reviewerId)
        assertEquals(
            "reviewer not named",
            dwRatingAttribution(hidden),
        )
        val shown = json.decodeFromString<DesignRatingDto>(
            """{"id":"r2","score":4,"mine":false,"reviewerId":"u7"}"""
        )
        assertEquals("u7", shown.reviewerId)
    }

    @Test
    fun `the ledger's two booleans default to the conservative answer`() {
        val ledger = json.decodeFromString<SubjectLedgerDto>("""{"round":"PEER"}""")
        assertFalse(ledger.canReadLedger)
        assertFalse(ledger.namesShown)
        assertEquals(0, ledger.summary.ratingCount)
        assertNull(ledger.summary.score)
    }

    @Test
    fun `a submission answer decodes its replay flag and its stored row`() {
        val saved = json.decodeFromString<DesignRatingSavedDto>(
            """{"rating":{"id":"r1","score":5,"mine":true},"replayed":true}"""
        )
        assertTrue(saved.replayed)
        assertEquals(5, saved.rating.score)
        assertTrue(saved.rating.mine)
        val bare = json.decodeFromString<DesignRatingSavedDto>("""{}""")
        assertFalse(bare.replayed)
        assertEquals("", bare.rating.id)
    }

    @Test
    fun `the request body sends only what the designer filled in`() {
        val body = DesignRatingBody(subjectId = "s1", round = "PEER", score = 4)
        val encoded = Json.encodeToString(DesignRatingBody.serializer(), body)
        assertEquals("""{"subjectId":"s1","round":"PEER","score":4}""", encoded)
    }

    @Test
    fun `the scale is the one the whole product uses`() {
        assertEquals(1, DW_MIN_SCORE)
        assertEquals(5, DW_MAX_SCORE)
        assertEquals(listOf(1, 2, 3, 4, 5), DW_SCORES)
    }

    @Test
    fun `the stage a rateable entity lives in is read out of the registry, never hardcoded`() {
        val schema = SchemaResponse(
            stages = listOf(
                StageDto(
                    number = 11,
                    key = "SKETCH_DEVELOPMENT",
                    entities = listOf(
                        EntityDto(key = "sketchSummary", cardinality = "SINGLETON"),
                        EntityDto(key = "sketch", cardinality = "COLLECTION"),
                    ),
                ),
                StageDto(
                    number = 13,
                    key = "PROTOTYPE_DEVELOPMENT",
                    entities = listOf(EntityDto(key = "prototype", cardinality = "COLLECTION")),
                ),
            )
        )
        assertEquals("SKETCH_DEVELOPMENT", dwStageKeyForEntity(schema, "sketch"))
        assertEquals("PROTOTYPE_DEVELOPMENT", dwStageKeyForEntity(schema, "prototype"))
        assertNull(dwStageKeyForEntity(schema, "sketchSummary"))
        assertNull(dwStageKeyForEntity(schema, "prototypeMaterial"))
        assertNull(dwStageKeyForEntity(SchemaResponse(), "prototype"))
    }

    @Test
    fun `the subtitle is the identifier the ranking label leaves out`() {
        assertEquals(
            "S-14 · Asha",
            dwRowSubtitle(row("k1", "e1", extra = mapOf("sketchNo" to "S-14", "designerName" to "Asha"))),
        )
        assertEquals("", dwRowSubtitle(row("k1", "e1")))
        assertEquals("", dwRowSubtitle(null))
    }
}
