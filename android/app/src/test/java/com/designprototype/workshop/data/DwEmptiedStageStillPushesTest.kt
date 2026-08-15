package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A STAGE THE DESIGNER EMPTIED WAS SKIPPED BY EVERY PUSH AND SCORED "BACKED UP TO THE SERVER".
 *
 * ── THE DEFECT ───────────────────────────────────────────────────────────────────────────────────
 *
 * `statusOf` and `pushStages` each carried their own copy of one test — "no values, no rows, no custom
 * answers, and no signature the server has acknowledged, so there is nothing to say about this stage"
 * — and neither copy read [StageDraft.emptiedEntities] or [StageDraft.deletedRowKeys]. A stage whose
 * entire content WAS a collection (eight of the twenty-two declare no singleton at all, so this is the
 * ordinary shape of a costing or sketch-development stage) holds exactly nothing the moment its rows
 * are deleted, so the deletion-only stage is precisely the arm that early return swallows.
 *
 * The `unsentDeletions` counter did not see it either, and could not: it sits behind
 * `!isAuthoritative`, and the stage in this walk IS authoritative — it was read that morning — which
 * is the very fact that makes the deletion SENDABLE.
 *
 * ── THE WALK, WHICH COSTS A MINISTRY REPORT ──────────────────────────────────────────────────────
 *
 * Morning, in signal: the designer opens a costing stage, six rows come down, they type nothing, so no
 * signature is ever recorded. Afternoon, no signal: they delete all six. The save writes `rows = []`,
 * `stageSeen = true`, `emptiedEntities = ["costLine"]`, and the PUT throws — `StagePush.NotSent`
 * records nothing. That evening, back in signal: every pass finds the stage empty and unsent and skips
 * it, `pendingStages` stays 0, `isFullySynced` is true, and the workshop row reads "Backed up to the
 * server" while the six superseded costing lines sit in the repository and print in the .docx.
 *
 * ── WHAT IS PINNED HERE ──────────────────────────────────────────────────────────────────────────
 *
 * [dwStageSaysNothing] is the single gate both passes now ask, so these cases hold for the status the
 * designer reads AND for the payload the pass sends; they cannot answer differently, which is what the
 * two inlined copies did. The counting half — the sentence about a deletion that cannot travel yet —
 * is pinned by `DwUnsentDeletionTest`, and the fold that finally lets it travel by
 * `StageAuthorityEarnedByReadingTest`.
 */
class DwEmptiedStageStillPushesTest {

    private val spec = StageDto(
        number = 17,
        key = "COSTING",
        title = "Costing",
        entities = listOf(
            // No singleton. Eight of the twenty-two stages are shaped like this, which is why a
            // collection-only stage is the ordinary case rather than the edge one.
            EntityDto(
                key = "costLine", cardinality = "COLLECTION", title = "Cost line",
                fields = listOf(
                    FieldDto(key = "item", label = "Item", type = "TEXT"),
                    FieldDto(key = "rate", label = "Rate", type = "DECIMAL"),
                ),
            ),
        ),
    )

    /** The draft as it is that evening: read this morning, emptied this afternoon, never sent. */
    private fun emptiedAfterReading() = StageDraft(
        stageId = spec.key,
        rows = emptyList(),
        // Earned by the morning's read, and it is what entitles the payload to say "these are now
        // exactly the rows" — i.e. none.
        stageSeen = true,
        emptiedEntities = listOf("costLine"),
        deletedRowKeys = listOf(
            dwRowId("costLine", "c1"),
            dwRowId("costLine", "c2"),
        ),
    )

    // ── The gate ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a stage holding nothing but a deletion it can send is not silence`() {
        val stored = emptiedAfterReading()
        val record: StageSyncRecord? = null

        // FIRST, THAT THIS IS THE CASE THE OLD GATE SWALLOWED, stated as its own arithmetic rather
        // than as a story: every term of the inlined test answered "skip".
        assertTrue(
            "the draft really does hold nothing else — that is what makes this the swallowed case",
            stored.values.isEmpty() && stored.rows.isEmpty() && stored.custom.isEmpty(),
        )
        assertTrue(
            "and the server has never acknowledged a payload for it",
            record?.signature.isNullOrBlank(),
        )

        // THE ASSERTION THE DEFECT FAILED.
        assertFalse(
            "a recorded deletion the draft is entitled to state is something to say",
            dwStageSaysNothing(spec, stored, record, workshopIsRemote = true),
        )
    }

    @Test
    fun `and the payload it is now allowed to build actually carries the deletion`() {
        // The gate falling through is worth nothing unless what comes out the other side deletes. This
        // is the whole point of not skipping: `replaceCollections` plus the named entity plus NO entry
        // for that entity is the exact shape the server's sweep acts on.
        val stored = emptiedAfterReading()
        val built = buildStageBody(spec, stored, emptyMap(), isAuthoritative(stored, null))

        assertTrue(built.body.replaceCollections)
        assertEquals(listOf("costLine"), built.body.emptiedEntities)
        assertTrue(
            "a payload that named the rows again would sweep nothing",
            built.body.entries.none { it.entityKey == "costLine" },
        )
        // And it is judged PENDING rather than merely sendable: `statusOf` compares this signature
        // with the record's, and there is no record, so the stage is counted and the pass sends it.
        val record: StageSyncRecord? = null
        assertTrue(
            "falling through the gate is only worth something if the comparison below it says pending",
            signatureOf(built.body) != record?.signature,
        )
    }

    @Test
    fun `a stage holding only individual row deletions speaks too`() {
        // Deleting SOME rows of a collection leaves `emptiedEntities` empty — the collection did not
        // go from rows to none — so this is the other record, and it is owed in exactly the same way.
        // The draft is empty here because the deleted rows were this stage's only content on disk.
        val stored = StageDraft(
            stageId = spec.key,
            stageSeen = true,
            deletedRowKeys = listOf(dwRowId("costLine", "c9")),
        )
        assertFalse(dwStageSaysNothing(spec, stored, record = null, workshopIsRemote = true))
    }

    // ── And the three things it must NOT do ──────────────────────────────────────────────────────

    @Test
    fun `a deletion this device cannot yet state is still silence, because a push cannot fix it`() {
        // UNREAD, so the draft is not authoritative — and `buildStageBody` puts `emptiedEntities` on
        // the wire only alongside `replaceCollections`, so a payload built here would carry no
        // deletion at all. Pushing it would spend a metered request to change nothing AND contradict
        // the sentence `statusOf` prints beside it ("Nothing else about this stage is held up"). This
        // is the case `unsentDeletions` counts; its remedy is a READ, which no pass makes.
        val unread = emptiedAfterReading().copy(stageSeen = false)
        assertTrue(dwStageSaysNothing(spec, unread, record = null, workshopIsRemote = true))

        val built = buildStageBody(spec, unread, emptyMap(), isAuthoritative(unread, null))
        assertFalse(built.body.replaceCollections)
        assertTrue(
            "so there would have been nothing in the payload to justify sending it",
            built.body.emptiedEntities.isEmpty(),
        )
    }

    @Test
    fun `a deletion naming an entity this build does not declare is not a reason to push`() {
        // A key left behind by a registry that has moved on is not a deletion instruction for
        // anything: nothing can draw it, `buildStageBody` intersects it away before the wire, and
        // treating it as owed would hold the stage unsynced for ever over an entity that does not
        // exist. Same intersection, same reason, as the counter and the payload builder.
        val stale = StageDraft(
            stageId = spec.key,
            stageSeen = true,
            emptiedEntities = listOf("anEntityTheRegistryDropped"),
            deletedRowKeys = listOf(dwRowId("anEntityTheRegistryDropped", "x1")),
        )
        assertTrue(dwOwedDeletions(spec, stale).entities.isEmpty())
        assertTrue(dwOwedDeletions(spec, stale).rows.isEmpty())
        assertTrue(dwStageSaysNothing(spec, stale, record = null, workshopIsRemote = true))
    }

    @Test
    fun `a deletion on a workshop that exists nowhere else is not a reason to push`() {
        // With no server record there is nothing anywhere that could still be holding the rows. This
        // also keeps the list row's "On this device only" honest, which requires nothing pending.
        val stored = emptiedAfterReading()
        assertTrue(dwStageSaysNothing(spec, stored, record = null, workshopIsRemote = false))
    }

    // ── The behaviour that must not regress ──────────────────────────────────────────────────────

    @Test
    fun `an empty stage with nothing recorded is still silence`() {
        // Twenty-two stages per workshop and most of them untouched: without this, every pass would
        // PUT twenty-odd empty bodies per workshop over a metered connection.
        assertTrue(dwStageSaysNothing(spec, StageDraft(stageId = spec.key), null, workshopIsRemote = true))
        assertTrue(dwStageSaysNothing(spec, null, null, workshopIsRemote = true))
    }

    @Test
    fun `an empty stage the server HAS acknowledged still speaks`() {
        // The rule this gate has always had: an empty stage that has been sent is a stage the designer
        // emptied, and skipping it would leave the deleted rows alive on the server.
        val sent = StageSyncRecord(signature = "whatever-the-last-save-hashed-to")
        assertFalse(dwStageSaysNothing(spec, StageDraft(stageId = spec.key), sent, workshopIsRemote = true))
    }

    @Test
    fun `a stage holding answers is never silence, custom answers included`() {
        val typed = StageDraft(
            stageId = spec.key,
            rows = listOf(DraftRow(id = dwRowId("costLine", "c1"), values = mapOf("item" to JsonPrimitive("Warp")))),
        )
        assertFalse(dwStageSaysNothing(spec, typed, null, workshopIsRemote = true))
        // `custom` alone counts, for the reason the gate's own comment gives: on a stage that declares
        // no singleton, the designer's own questions may be the only answers there are.
        val customOnly = StageDraft(
            stageId = spec.key,
            custom = mapOf("dyeSource" to JsonPrimitive("Madder, bought in Bhuj")),
        )
        assertFalse(dwStageSaysNothing(spec, customOnly, null, workshopIsRemote = true))
    }
}
