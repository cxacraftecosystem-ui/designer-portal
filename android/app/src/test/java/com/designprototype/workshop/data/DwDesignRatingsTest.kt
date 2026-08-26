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

/**
 * Design review on the handset: the ordering, the arrangement, the round gate, and who is told what.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY EVERY ONE OF THESE IS A TEST AND NOT A CODE REVIEW
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Every failure this feature can have is SILENT. A list in the wrong order still renders; a stamp
 * written on one row instead of all of them still reads as fixed until somebody looks at a different
 * row; a ledger that says "nobody has rated this" over a ledger that was withheld looks exactly like
 * the truth; and an arrangement written over an empty collection redraws as an empty collection,
 * which is what an empty collection looks like. None of them throws, none of them logs, and the
 * person who finds out is a designer a fortnight later.
 *
 * So the whole of `DwDesignRatings.kt` is pure by construction — no Compose, no network, no disk —
 * and this file is what that is FOR. It runs on a desktop JVM in milliseconds, which is the only
 * reason the three refusals in [dwPlanArrangement] are covered at all: reproducing them by hand
 * means a phone that has never read one stage of one workshop.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS FILE DELIBERATELY DOES NOT TEST
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * THE PERMISSION RULE ITSELF, because this client does not hold one. `rating_payload` omits a
 * `reviewerId` the caller may not have and `visible_rows` drops rows they may not see, both before
 * the response is built — so there is no client-side predicate to assert. What IS asserted is that
 * this client RENDERS the server's two flags without collapsing them, and that an absent
 * `reviewerId` survives decoding as absent rather than as an empty string somebody could print.
 *
 * NO UI. The gesture in `DwRankableList` is deliberately a thin shell over [dwMoveTo] and
 * [dwMoveBy]; what it adds is pointer arithmetic, and a test that drove a synthetic drag would be
 * asserting Compose's input plumbing rather than this feature's rules.
 */
class DwDesignRatingsTest {

    // ──────────────────────────────────────────────────────────────────────────────────────────
    // Builders
    // ──────────────────────────────────────────────────────────────────────────────────────────

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

    // ──────────────────────────────────────────────────────────────────────────────────────────
    // The two orders
    // ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the score order is the server's default position and the placed order is its ordinal`() {
        // The two orders are DIFFERENT here on purpose. If a test used a list where they agreed, a
        // client that read the wrong field would pass it — and the whole feature is the gap between
        // them: a reader has to be able to see that a piece is third in the designers' order and
        // first on the scores.
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
        // A tie with no tiebreak comes back in whatever order the response happened to arrive in,
        // and with a list that gets CUT for display that is what decides which piece a designer
        // never sees. The server pins its own tie order four ways; this pins the client's last
        // resort, which is the only one it can see.
        val items = listOf(
            ranked("zeta", defaultPosition = 1, placedPosition = 1),
            ranked("alpha", defaultPosition = 1, placedPosition = 1),
        )
        assertEquals(listOf("alpha", "zeta"), dwScoreOrder(items))
        assertEquals(listOf("alpha", "zeta"), dwPlacedOrder(items))
    }

    @Test
    fun `an unfixed list opens in score order and a fixed one opens in the designers' order`() {
        // THE OWNER'S SENTENCE, ASSERTED IN BOTH DIRECTIONS. "Sorted by score by default" and "the
        // designer having the final say" are one rule with two outcomes, and a client that got
        // either half right and the other wrong would look correct on whichever workshop the
        // reviewer happened to open first.
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
        /*
          THE WINDOW BETWEEN A REORDER AND ITS SYNC, which on this fleet is days. `placedPosition` is
          the ordinal AS THE SERVER HOLDS IT; the draft rows are what the designer actually moved. If
          the server won here, a designer who reordered in a courtyard and reopened the screen would
          see the list in its PRE-REORDER order underneath a banner reading "this order was settled
          deliberately by you" — the arrangement looking thrown away and the sentence insisting it
          was not.
        */
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
        // The other side of the clause above, and the reason it is guarded rather than always
        // applied: reading the draft's row order on an UNFIXED list would quietly redefine "the
        // default order" as "the stage's row order", which is the one thing the default is not.
        val items = listOf(
            ranked("a", defaultPosition = 2, placedPosition = 1),
            ranked("b", defaultPosition = 1, placedPosition = 2),
        )
        assertEquals(listOf("b", "a"), dwOpeningOrder(items, fixed = null, held = listOf("a", "b")))
    }

    @Test
    fun `reconciling drops pieces that have gone and appends new ones in score order`() {
        // A reorder made on Monday still has to make sense against Friday's list. Appending rather
        // than inserting by score is the load-bearing half: slotting a new piece into the middle of
        // a fixed order on the strength of its first rating would be the score re-sorting a list
        // somebody took responsibility for.
        val order = listOf("gone", "b", "a")
        val items = listOf(
            ranked("a", defaultPosition = 3),
            ranked("b", defaultPosition = 2),
            ranked("new", defaultPosition = 1),
        )
        assertEquals(listOf("b", "a", "new"), dwReconcileOrder(order, items))
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────
    // The move helpers — the arrow path and the drag path share them
    // ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `one step up and one step down move exactly one place`() {
        val order = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), dwMoveBy(order, "b", -1))
        assertEquals(listOf("a", "c", "b"), dwMoveBy(order, "b", 1))
    }

    @Test
    fun `a step past either end is a no-op rather than an error or a wrap`() {
        // The arrows are disabled at the ends, so this is the DRAG path's case: a gesture that ends
        // past the edge of the list is an ordinary thing a thumb does. A wrap would move the first
        // piece to last on a flick nobody meant, and an exception would take the screen down.
        val order = listOf("a", "b", "c")
        assertEquals(order, dwMoveBy(order, "a", -1))
        assertEquals(order, dwMoveBy(order, "c", 1))
        assertEquals(order, dwMoveTo(order, 0, -5))
        assertEquals(order, dwMoveTo(order, 2, 99))
    }

    @Test
    fun `moving an id the order does not hold changes nothing`() {
        // Reachable: a card whose piece was deleted by a colleague between the read and the press.
        val order = listOf("a", "b")
        assertEquals(order, dwMoveBy(order, "ghost", 1))
        assertEquals(order, dwMoveTo(order, 7, 0))
    }

    @Test
    fun `a long move closes the gap behind it rather than swapping two rows`() {
        // A DRAG FROM THE END TO THE TOP IS NOT THREE SWAPS. If this were implemented as a swap the
        // list would come back with two pieces exchanged and the ones between them untouched, which
        // is a different arrangement from the one under the finger.
        assertEquals(
            listOf("d", "a", "b", "c"),
            dwMoveTo(listOf("a", "b", "c", "d"), 3, 0),
        )
        assertEquals(
            listOf("b", "c", "d", "a"),
            dwMoveTo(listOf("a", "b", "c", "d"), 0, 3),
        )
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────
    // The override stamp
    // ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a stamp on any row counts, and the most recent one wins`() {
        // The two fields are per-ROW because that is where the registry put them, but what they
        // describe is the ARRANGEMENT. A list where one row was written by an older build, or by a
        // laptop that has not synced the others, still reads as fixed — the fail direction that
        // KEEPS a deliberate order rather than silently re-sorting by score.
        val rows = listOf(
            row("k1", "e1"),
            row("k2", "e2", extra = mapOf("rankFixedBy" to "Asha", "rankFixedAt" to "2026-08-10")),
            row("k3", "e3", extra = mapOf("rankFixedBy" to "Ravi", "rankFixedAt" to "2026-08-19")),
        )
        assertEquals(DwFixedOrderStamp("Ravi", "2026-08-19"), dwFixedOrderStamp(rows))
    }

    @Test
    fun `half a stamp is not a stamp`() {
        // The sentence on screen is "fixed by X on Y" and half of it is not a sentence. Treating a
        // half-stamp as unfixed puts the list back in score order with a visible way to fix it
        // again, which is recoverable; treating it as fixed prints "fixed by — on 12 August" at a
        // designer for ever.
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
        // Not decoration: `dwFixedOrderStamp` compares these as STRINGS, which is correct for
        // exactly one format. Anything else and "the most recent stamp wins" silently becomes
        // "whichever sorts last".
        assertEquals("2026-08-26", dwTodayStamp(LocalDate.of(2026, 8, 26)))
        assertEquals("2026-01-05", dwTodayStamp(LocalDate.of(2026, 1, 5)))
    }

    @Test
    fun `arranging rows reorders by server id and stamps every row, not only the moved one`() {
        val rows = listOf(row("k1", "e1"), row("k2", "e2"), row("k3", "e3"))
        val arranged = dwArrangeRows(rows, listOf("e3", "e1", "e2"), DwFixedOrderStamp("Asha", "2026-08-26"))
        assertEquals(listOf("e3", "e1", "e2"), arranged.map { it.dwEntryId() })
        // EVERY row. A row left where it was is as much a part of the fixed arrangement as the one
        // that was dragged, and stamping only the moved row would make "is this list fixed?" depend
        // on which row a reader happened to look at.
        assertTrue(arranged.all { it.values["rankFixedBy"] == JsonPrimitive("Asha") })
        assertTrue(arranged.all { it.values["rankFixedAt"] == JsonPrimitive("2026-08-26") })
    }

    @Test
    fun `a null stamp clears both fields on every row, which is the way back to the default`() {
        // Blank is exactly what "the computed score still governs" is spelled as in the registry, so
        // returning to the default has to be a real WRITE and not a client-side pretence.
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
        /*
          REACHABLE IN ORDINARY USE, which is why it is a test and not a defensive branch: a sketch
          drawn on this phone this morning has no `_entryId` at all — no server has seen it — so it
          cannot appear in an order keyed by subject id. Dropping it here would delete a designer's
          unsent work from the draft on the next save, silently, as a side effect of a reorder.
        */
        val rows = listOf(row("k1", "e1"), row("unsent"), row("k2", "e2"))
        val arranged = dwArrangeRows(rows, listOf("e2", "e1"), null)
        assertEquals(listOf("e2", "e1", null), arranged.map { it.dwEntryId() })
        assertEquals(3, arranged.size)
    }

    @Test
    fun `an entry id of the literal string null is not an id`() {
        /*
          JsonNull IS A JsonPrimitive, so `(values["_entryId"] as? JsonPrimitive)?.content` hands back
          the four-character string "null" — which is not blank, matches nothing, and would therefore
          pass every emptiness test in the module while pairing a rating with no row at all. The trap
          `DwPhotoIntake` documents, asserted here because this module keys a whole arrangement off
          this one read.
        */
        val nulled = DraftRow(
            id = dwRowId("prototype", "k1"),
            values = mapOf("_entryId" to kotlinx.serialization.json.JsonNull),
        )
        assertNull(nulled.dwEntryId())
        assertNull(DraftRow(id = dwRowId("prototype", "k2")).dwEntryId())
        assertNull(row("k3", entryId = "  ").dwEntryId())
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────
    // Writing an arrangement back: the three refusals
    // ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an arrangement with no name on it is refused before anything is written`() {
        // `rankFixedBy` is TEXT and the sentence it feeds is "fixed by X on Y". A session with no
        // name to record would write a stamp that reads "fixed by — on 12 August" for ever.
        val plan = dwPlanArrangement(
            held = listOf(row("k1", "e1")),
            order = listOf("e1"),
            stamp = DwFixedOrderStamp(by = "   ", at = "2026-08-26"),
            stageSeen = true,
        )
        val refused = plan as DwArrangementPlan.Refused
        assertTrue(refused.reason.contains("no name to record"))
    }

    @Test
    fun `an order of eight pieces over zero held rows is refused rather than blanking the entity`() {
        /*
          THE STATE IS REACHABLE AND THE FAILURE IS SILENT. The ranking request succeeds while this
          phone has never opened the stage, so the cards on screen came from the server and the draft
          holds no rows. `dwArrangeRows` is total on ids it cannot find — which is right, an unsent
          row has no server id — so it would answer with an EMPTY list, and writing that back blanks
          this entity in the draft. It redraws as an empty collection, which is what an empty
          collection looks like.
        */
        val plan = dwPlanArrangement(
            held = emptyList(),
            order = listOf("e1", "e2"),
            stamp = DwFixedOrderStamp("Asha", "2026-08-26"),
            stageSeen = false,
        )
        val refused = plan as DwArrangementPlan.Refused
        assertTrue(refused.reason.contains("has not read the stage"))
    }

    @Test
    fun `returning to score order is refused on a stage this phone has never read`() {
        /*
          BECAUSE ON SUCH A STAGE IT CANNOT BE DONE, AND THE SCREEN WOULD SAY IT HAD BEEN. Clearing
          the stamp writes two blanks; the server coerces a blank to None and leaves the key out of
          the cleaned row; and every row of a stage this device has not read is sent with
          `merge = true` (`buildStageBody`: `merge = !authoritative`, and `isAuthoritative` IS
          `StageDraft.stageSeen`), whose branch keeps every field the client left out. So the
          repository keeps the stamp it already holds, the list stays "settled deliberately" for
          ever, and a naive client reports success.
        */
        val plan = dwPlanArrangement(
            held = listOf(row("k1", "e1")),
            order = listOf("e1"),
            stamp = null,
            stageSeen = false,
        )
        val refused = plan as DwArrangementPlan.Refused
        assertTrue(refused.reason.contains("never read the repository's copy"))
    }

    @Test
    fun `a workshop that has never left the phone returns to score order as normal`() {
        // The other side of that refusal, and the reason it is gated on the rows rather than on
        // `stageSeen` alone: a row with no server id has no previous version for a merge to
        // preserve, so a workshop still working entirely offline is not at risk and must not be
        // stopped. Refusing here would lock a courtyard-only workshop out of its own default order.
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
        // The rows keep their entity prefix, because that prefix is what tells the sync which
        // collection they belong to — `rowsFor` filters on it and `buildStageBody` walks it.
        assertTrue(write.rows.all { it.entityKey() == "prototype" })
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────
    // The round gate
    // ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the pool round opens on a piece only when its own date is filled in`() {
        /*
          PER PIECE AND NOT PER WORKSHOP. The registry's own note on `peerRoundClosedAt` gives the
          reason and it is the whole gate: prototypes finish one at a time, and a workshop-level flag
          would open the pool round on nine unfinished prototypes the day the tenth was done.
        */
        assertFalse(dwPoolIsOpen(row("k1", "e1")))
        assertFalse(dwPoolIsOpen(row("k1", "e1", extra = mapOf("peerRoundClosedAt" to ""))))
        assertFalse(dwPoolIsOpen(row("k1", "e1", extra = mapOf("peerRoundClosedAt" to "   "))))
        assertTrue(dwPoolIsOpen(row("k1", "e1", extra = mapOf("peerRoundClosedAt" to "2026-08-01"))))
    }

    @Test
    fun `a sketch is gated by its own date exactly as a prototype is`() {
        // The registry declares the same key on BOTH rateable entities and this client must not
        // special-case either. It used to be declared on `prototype` alone, which meant level 2
        // could never open on a sketch while three other declarations assumed it could.
        assertTrue(
            dwPoolIsOpen(
                row("k1", "e1", entity = "sketch", extra = mapOf("peerRoundClosedAt" to "2026-08-01"))
            )
        )
        assertFalse(dwPoolIsOpen(row("k1", "e1", entity = "sketch")))
    }

    @Test
    fun `the open count is what an empty pool round says about itself`() {
        // The sentence this feeds is the difference between "this workshop has opened nothing yet"
        // and "the round could not be read", which are the two things an empty pool list can mean
        // and which this repository has shipped as each other more than once.
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
        // The wire field is a String on purpose: decoding straight into an enum makes a round token
        // this build has never heard of a `SerializationException` that fails the WHOLE response, so
        // one new round on the server would blank the review screen on every handset behind it.
        assertEquals(DwRatingRound.PEER, DwRatingRound.of("PEER"))
        assertEquals(DwRatingRound.POOL, DwRatingRound.of("pool"))
        assertNull(DwRatingRound.of("JURY"))
        assertNull(DwRatingRound.of(null))
        assertEquals(listOf("PEER", "POOL"), DwRatingRound.entries.map { it.wire })
    }

    @Test
    fun `the two rateable entities are exactly the pair the API accepts`() {
        // `RATEABLE_ENTITIES` on the server is {sketch, prototype} and the child rows of a prototype
        // are refused BY NAME with a 422. Offering a third would be a control that can only produce
        // a refusal.
        assertEquals(setOf("prototype", "sketch"), DwRateableEntity.entries.map { it.wire }.toSet())
        assertEquals(DwRateableEntity.PROTOTYPE, DwRateableEntity.of("prototype"))
        assertNull(DwRateableEntity.of("prototypeMaterial"))
        assertNull(DwRateableEntity.of("prototypeStageLog"))
    }

    @Test
    fun `the pool hints are the web's words and the peer hints do not claim anything was released`() {
        /*
          THE POOL WORDING MUST NOT REACH THE PEER ROUND. `pool_visible` returns the whole collection
          to a member and to any admin, and the wire does not mark which rows were opened — so
          printing "in this workshop's pool round" over a peer list is how a designer comes to
          believe a colleague released a sketch they are still working on.
        */
        assertEquals(
            "The prototypes in this workshop's pool round.",
            DwRateableEntity.PROTOTYPE.hint(DwRatingRound.POOL),
        )
        assertTrue(DwRateableEntity.SKETCH.hint(DwRatingRound.POOL).contains("never prototyped"))
        DwRateableEntity.entries.forEach { entity ->
            assertFalse(entity.hint(DwRatingRound.PEER).contains("pool"))
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────
    // Who may arrange, and what a card is allowed to print
    // ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the raw ordinal's presence is the whole answer to may I write an order back`() {
        /*
          THE SERVER SENDS IT ONLY WHEN THE CALLER IS THE WORKSHOP'S OWN PARTY OR AN ADMIN, and the
          stage save that would persist a reorder is gated by `load_workshop_or_404`, which admits
          exactly that set. So this is one question asked once. The alternative — comparing the
          signed-in account against the workshop's creator — is the shortcut the server's own code
          refuses in as many words, because it silently demotes every viewer-granted co-designer to a
          stranger.
        */
        assertTrue(dwMayArrange(listOf(ranked("a", ordinal = 0), ranked("b", ordinal = 1))))
        assertFalse(dwMayArrange(listOf(ranked("a", ordinal = 0), ranked("b"))))
        assertFalse(dwMayArrange(listOf(ranked("a"))))
        // An empty round is not an entitlement. `all` over an empty list is true, so without the
        // size clause a workshop with nothing in it would offer arrangement controls to a stranger.
        assertFalse(dwMayArrange(emptyList()))
    }

    @Test
    fun `an unrated piece says so instead of scoring zero`() {
        // NULL IS NOT ZERO. A sketch nobody has got to has not been judged badly, it has not been
        // judged — and a list that printed 0.0 would rank the unreviewed as the worst work in the
        // workshop.
        assertEquals("Not rated yet", dwScoreText(null, 0))
        assertEquals("Not rated yet", dwScoreText(4.5, 0))
        assertEquals("4.2 from 5 designers", dwScoreText(4.236, 5))
        assertEquals("3.0 from 1 designer", dwScoreText(3.0, 1))
    }

    @Test
    fun `the placed position is printed only where it describes the whole collection`() {
        /*
          A POSITION WITHIN AN UNKNOWN SUBSET IS NOT THE MAKERS' ORDER. On a pool round the ranking
          is narrowed to the pieces this caller may see BEFORE the positions are computed, precisely
          so a stranger is not handed "placed 3 of 3" for the one opened prototype — which would also
          tell them how many the workshop holds.
        */
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

    // ──────────────────────────────────────────────────────────────────────────────────────────
    // The ledger: rendering the server's decision without collapsing it
    // ──────────────────────────────────────────────────────────────────────────────────────────

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
        /*
          THE SINGLE MOST IMPORTANT ASSERTION IN THIS FILE. `canReadLedger: false` with a populated
          summary is not a refusal — it is "you can see the score, not the scorers", the ordinary
          state of a peer in a round — and the server sends the flag precisely so a client does not
          have to guess. Collapsing the two tells a designer their prototype went unreviewed when
          five colleagues have judged it.
        */
        assertEquals("Nobody has rated this piece yet.", dwLedgerEmptyNote(ledger(count = 0)))
        val withheld = dwLedgerEmptyNote(ledger(count = 5, canRead = false)).orEmpty()
        assertTrue(withheld.contains("5 designer(s) have rated this piece"))
        assertTrue(withheld.contains("you can see the score, not the scorers"))
        // A third state, and it is neither of the two: rows were readable and none came back.
        assertEquals(
            "No rating rows came back for this round.",
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
        // Whether a pool rater's identity reaches the designer whose record it is, is an owner call
        // held in one server constant. A sentence implying this app withheld something would send a
        // designer looking for a setting on their phone that does not exist.
        val rows = listOf(DesignRatingDto(id = "r1", score = 4))
        assertNull(dwLedgerNamesNote(ledger(rows = rows, namesShown = true)))
        // Never over an empty list: there is nothing for the note to describe.
        assertNull(dwLedgerNamesNote(ledger(count = 3, namesShown = false)))
        val note = dwLedgerNamesNote(ledger(rows = rows, namesShown = false)).orEmpty()
        assertTrue(note.contains("the server's decision for this round"))
    }

    @Test
    fun `a row's attribution is read off the row and never off the list-level flag`() {
        /*
          THE REVIEWER ALWAYS SEES THEIR OWN ROW IN FULL, so a ledger can legitimately hold one named
          row among unnamed ones — and asking the list-level flag would print "not named on this
          response" over the reader's own rating, which they wrote.
        */
        assertEquals("your rating", dwRatingAttribution(DesignRatingDto(id = "r1", mine = true)))
        assertEquals(
            "reviewer u9",
            dwRatingAttribution(DesignRatingDto(id = "r2", reviewerId = "u9")),
        )
        assertEquals(
            "reviewer not named on this response",
            dwRatingAttribution(DesignRatingDto(id = "r3")),
        )
        // Blank is treated as absent. The server omits the key rather than sending it empty, so a
        // blank can only come from a build or a proxy that filled it in — and "reviewer " is not a
        // sentence.
        assertEquals(
            "reviewer not named on this response",
            dwRatingAttribution(DesignRatingDto(id = "r4", reviewerId = "  ")),
        )
    }

    @Test
    fun `both clocks are printed when they name different days and one when they do not`() {
        /*
          "WHEN" IS AMBIGUOUS AND SENDING ONE ANSWER DECIDES IT SILENTLY, in the direction that
          credits the sync with the judgement. On this fleet a rating made in a courtyard reaches the
          server whenever the phone next finds signal, which can be a fortnight.
        */
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
            assertTrue(apart.contains("reached the server 15 Aug 2026"))
            val sameDay = dwRatingClockLine(
                DesignRatingDto(
                    id = "r2",
                    ratedAt = "2026-08-01T09:00:00+00:00",
                    createdAt = "2026-08-01T09:00:04+00:00",
                )
            )
            assertEquals("Judged 1 Aug 2026", sameDay)
            // Typed straight against the server: there is no courtyard moment, so the row's own
            // arrival IS the answer and the line says one thing rather than repeating it.
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
        /*
          THE APP HAS SHIPPED THIS BUG ONE FILE OVER. The server stamps in UTC; reading the date
          straight off the offset names the UTC day, which for this product's users is off by one for
          a third of every day. A designer in Asia/Kolkata who rates a prototype at 01:30 on 2 March
          is stamped 2026-03-01T20:00:00Z, and a handset that did not convert said "1 Mar" while the
          browser, which has always converted, said "2 Mar" — one product, two answers, on a ledger
          whose whole subject is when something happened.
        */
        val tz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            assertEquals("2 Mar 2026", dwRatingDay("2026-03-01T20:00:00+00:00"))
            // A bare date names a day already and takes no zone: shifting it would invent one.
            assertEquals("1 Mar 2026", dwRatingDay("2026-03-01"))
            // THE YEAR IS CARRIED, unlike `StageSchema.shortDay`, which drops it because a year on
            // each of forty field labels is noise. A rating is a dated judgement in an audit trail a
            // ministry may read years later.
            assertTrue(dwRatingDay("2026-03-01").contains("2026"))
            assertEquals("-", dwRatingDay(null))
            assertEquals("-", dwRatingDay("not a date"))
        } finally {
            TimeZone.setDefault(tz)
        }
    }

    @Test
    fun `a replay is reported as the success it is, and never as a second filing`() {
        // A replay means the outbox delivered the same capture twice, which is the ordinary
        // behaviour of a phone with a flaky connection. Telling a designer "recorded" twice for one
        // judgement starts them wondering whether they filed two.
        assertEquals(
            "The server already held this rating, unchanged.",
            dwRatingSavedNote(replayed = true, amended = false),
        )
        assertEquals("Your rating has been amended.", dwRatingSavedNote(false, amended = true))
        assertEquals("Your rating has been recorded.", dwRatingSavedNote(false, amended = false))
    }

    @Test
    fun `an unreachable round is never reported as an empty one`() {
        // "No prototypes" and "cannot reach the server" are different facts, and this repository has
        // shipped the first as a disguise for the second more than once.
        val offline = dwRoundFailure(offline = true, refusal = "ignored")
        assertTrue(offline.offline)
        assertTrue(offline.message.contains("not an empty list"))
        // A refusal the repository ANSWERED is quoted rather than replaced: only the server knows
        // whether the round is empty, unreachable to this account, or behind a migration nobody ran.
        val refused = dwRoundFailure(offline = false, refusal = DW_ROUND_REFUSED)
        assertFalse(refused.offline)
        assertEquals(DW_ROUND_REFUSED, refused.message)
    }

    @Test
    fun `every stage-push outcome gets a sentence, and neither no-op reads as a failure`() {
        // `AlreadySent` and `NoRemoteYet` both mean nothing was sent and NEITHER is an error: the
        // first is the push having already happened, the second is a workshop that has not been
        // created on the repository yet. Reporting either as "could not be sent" sends a designer
        // looking for a signal problem they do not have.
        assertEquals("Saved. The repository already holds this arrangement.", dwPushNote(StagePush.AlreadySent))
        assertTrue(dwPushNote(StagePush.NoRemoteYet).contains("has not been created on the repository yet"))
        assertTrue(dwPushNote(StagePush.HeldBack(1)).contains("1 attachment "))
        assertTrue(dwPushNote(StagePush.HeldBack(3)).contains("3 attachments"))
        assertTrue(dwPushNote(StagePush.NotSent).contains("next sync"))
        assertTrue(dwPushNote(StagePush.NothingToSend).contains("no local copy of this stage"))
        assertEquals(
            "Saved on this phone and sent to the repository.",
            dwPushNote(StagePush.Sent(StageSaveResultDto())),
        )
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────
    // The wire
    // ──────────────────────────────────────────────────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a ranking row decodes when the server sends none of the optional keys`() {
        /*
          THIS APP SHIPS SEPARATELY FROM THE API IN BOTH DIRECTIONS. A handset a release AHEAD meets
          a server that does not send a key yet, and a required field would turn that into a
          `SerializationException` that takes the whole screen down — on a feature whose entire
          audience is designers standing in a courtyard.
        */
        val row = json.decodeFromString<RankedSubjectDto>("""{"subjectId":"s1"}""")
        assertEquals("s1", row.subjectId)
        assertNull(row.score)
        assertEquals(0, row.ratingCount)
        assertNull(row.myRating)
        // AND THE ABSENT ORDINAL STAYS ABSENT rather than defaulting to 0, because its presence is
        // what this client reads as "you may write an order back". A zero default would hand a pool
        // stranger the arrangement controls on every workshop.
        assertNull(row.ordinal)
    }

    @Test
    fun `an omitted reviewerId decodes as absent and never as an empty name`() {
        /*
          THE WHOLE PERMISSION RULE REACHING THE CLIENT. `rating_payload` omits the key entirely for a
          caller who may not have it rather than sending it empty, so that no screen can render a name
          that was never sent. The nullable type is the only thing stopping a later card from printing
          it unconditionally.
        */
        val hidden = json.decodeFromString<DesignRatingDto>(
            """{"id":"r1","score":4,"mine":false}"""
        )
        assertNull(hidden.reviewerId)
        assertEquals(
            "reviewer not named on this response",
            dwRatingAttribution(hidden),
        )
        val shown = json.decodeFromString<DesignRatingDto>(
            """{"id":"r2","score":4,"mine":false,"reviewerId":"u7"}"""
        )
        assertEquals("u7", shown.reviewerId)
    }

    @Test
    fun `the ledger's two booleans default to the conservative answer`() {
        // A handset ahead of the server says LESS than it might rather than claiming an entitlement
        // nobody granted. Per-row identity is still read off the row, which is the fact rather than
        // the flag.
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
        // A server that answered with nothing readable is not a crash: the row defaults empty and
        // the screen reports what it can.
        val bare = json.decodeFromString<DesignRatingSavedDto>("""{}""")
        assertFalse(bare.replayed)
        assertEquals("", bare.rating.id)
    }

    @Test
    fun `the request body sends only what the designer filled in`() {
        // `comment` and `suggestion` are omitted rather than sent empty, because the server
        // normalises "" to null anyway and two rows differing only in a way no screen can show is
        // the tidying the schema does at the edge. `ratedAt` is absent on the direct path by design:
        // the row's own `createdAt` IS the moment, and a client stamping it at send time would write
        // the sync clock into the one column whose job is to not be the sync clock.
        val body = DesignRatingBody(subjectId = "s1", round = "PEER", score = 4)
        val encoded = Json.encodeToString(DesignRatingBody.serializer(), body)
        assertEquals("""{"subjectId":"s1","round":"PEER","score":4}""", encoded)
    }

    @Test
    fun `the scale is the one the whole product uses`() {
        // 1 to 5, matching the server's own bounds AND the only other quantitative judgement in this
        // product. A second star scale teaches users that neither means anything.
        assertEquals(1, DW_MIN_SCORE)
        assertEquals(5, DW_MAX_SCORE)
        assertEquals(listOf(1, 2, 3, 4, 5), DW_SCORES)
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────
    // Finding the stage a rateable entity lives in
    // ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the stage a rateable entity lives in is read out of the registry, never hardcoded`() {
        /*
          Sketches are stage 11 and prototypes are stage 13 TODAY. Writing those keys into this
          client would be another copy of a fact the registry publishes, and the registry is the
          thing that moves — the sketch-review stage is marked optional and the source document
          proposed deleting it outright, so the numbering around these two is not a constant anybody
          should lean on.
        */
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
        // A SINGLETON of the same key is not a list anybody ranks, and accepting one would hand the
        // arrangement writer a stage whose rows are not these pieces.
        assertNull(dwStageKeyForEntity(schema, "sketchSummary"))
        assertNull(dwStageKeyForEntity(schema, "prototypeMaterial"))
        // A phone whose registry never downloaded answers null rather than guessing a stage key, and
        // the screen says it cannot rearrange rather than writing to a stage that may not exist.
        assertNull(dwStageKeyForEntity(SchemaResponse(), "prototype"))
    }

    @Test
    fun `the subtitle is the identifier the ranking label leaves out`() {
        // The ranking response builds its label from `name`, falling back to the identifier column,
        // so a piece with both shows only its name. A reviewer choosing between eight bamboo stools
        // needs the sketch number as well.
        assertEquals(
            "S-14 · Asha",
            dwRowSubtitle(row("k1", "e1", extra = mapOf("sketchNo" to "S-14", "designerName" to "Asha"))),
        )
        assertEquals("", dwRowSubtitle(row("k1", "e1")))
        assertEquals("", dwRowSubtitle(null))
    }
}
