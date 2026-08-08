package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the phone is entitled to CLAIM about a stage, as opposed to what it happens to be holding.
 *
 * `replaceCollections = true` is not a formatting choice, it is an assertion: "these are now exactly
 * the rows, delete anything else you have." It was hard-coded true in `buildStageBody` on the
 * reasoning that the phone owns the whole stage locally — which is true of a draft the designer
 * filled in and false of the one the stage screen manufactured when its offline read failed. The
 * failure that produced:
 *
 *   Stage 5 holds a fortnight of work — a 5-field singleton, 6 processStep rows, 5 tool rows, 4
 *   rawMaterial rows. The designer opens it in a courtyard. `designWorkshopStage` is called inside
 *   `runCatching{…}.getOrNull()`, there is no signal, it returns null, and a blank `StageState()` was
 *   seeded with no message. The designer types ONE field. The debounced save builds a payload holding
 *   that field, zero rows for every collection, and `replaceCollections = true` — and the sweep
 *   deleted every row of every collection the payload had never mentioned.
 *
 * Reproduced against the running API before these tests were written: PUT
 * TRADITIONAL_PROCESS_BASELINE with a singleton-only payload and `replaceCollections: true` answered
 * `removed: 5` and left the stage with nothing in it.
 *
 * The second half is the mirror image. With the sweep scoped to what a payload NAMES, a collection
 * whose last row was deleted is invisible — it contributes no entries — so the deletion has to be
 * stated in `emptiedEntities` or it never reaches the server and the rows come back into the .docx a
 * ministry receives.
 */
class StagePayloadAuthorityTest {

    // ── A stage the size of the one that was lost ────────────────────────────────────────────────

    private val spec = StageDto(
        number = 5,
        key = "TRADITIONAL_PROCESS_BASELINE",
        title = "Traditional process baseline",
        entities = listOf(
            EntityDto(
                key = "traditionalProcess", cardinality = "SINGLETON", title = "Traditional process",
                fields = listOf(FieldDto(key = "currentProblems", label = "Current problems", type = "LONG_TEXT"))
            ),
            EntityDto(
                key = "processStep", cardinality = "COLLECTION", title = "Process step",
                fields = listOf(FieldDto(key = "name", label = "Name", type = "TEXT"))
            ),
            EntityDto(
                key = "tool", cardinality = "COLLECTION", title = "Tool",
                fields = listOf(FieldDto(key = "name", label = "Name", type = "TEXT"))
            ),
            EntityDto(
                key = "rawMaterial", cardinality = "COLLECTION", title = "Raw material",
                fields = listOf(FieldDto(key = "name", label = "Name", type = "TEXT"))
            ),
        )
    )

    private fun row(entity: String, key: String, name: String) =
        DraftRow(id = dwRowId(entity, key), values = mapOf("name" to JsonPrimitive(name)))

    /** The draft as it is after the designer types one field into a stage that would not download. */
    private fun typedOneFieldOffline() = StageDraft(
        stageId = spec.key,
        values = mapOf("currentProblems" to JsonPrimitive("The vat has gone cold.")),
        rows = emptyList(),
        serverBaseline = false,
    )

    private fun build(stored: StageDraft?, record: StageSyncRecord? = null) =
        buildStageBody(spec, stored, emptyMap(), isAuthoritative(stored, record)).body

    // ── replaceCollections ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a stage seeded blank after a failed download claims no authority`() {
        val body = build(typedOneFieldOffline())

        // THE ONE ASSERTION THAT WOULD HAVE SAVED THE FORTNIGHT.
        assertFalse(
            "a draft that has never seen the server's copy must not ask for a sweep",
            body.replaceCollections
        )
        // The designer's work still goes. Refusing authority is not refusing to sync.
        assertEquals(1, body.entries.size)
        assertEquals("traditionalProcess", body.entries.single().entityKey)
    }

    @Test
    fun `a stage read successfully from the server does claim authority`() {
        // The draft started from everything the server had, so "these are now exactly the rows" is a
        // true statement and a row deleted here is genuinely deleted.
        val body = build(typedOneFieldOffline().copy(serverBaseline = true))
        assertTrue(body.replaceCollections)
    }

    @Test
    fun `a stage the server has already acknowledged claims authority`() {
        // From the moment the server accepted a payload built from this draft, the server's copy IS
        // this draft's, whatever the draft's provenance was before that.
        val body = build(typedOneFieldOffline(), StageSyncRecord(signature = "abc123"))
        assertTrue(body.replaceCollections)
    }

    @Test
    fun `authority is exactly the two honest cases and nothing else`() {
        val noBaseline = typedOneFieldOffline()
        assertFalse(isAuthoritative(noBaseline, null))
        assertFalse(isAuthoritative(noBaseline, StageSyncRecord(signature = "")))
        assertFalse(isAuthoritative(null, null))
        assertTrue(isAuthoritative(noBaseline.copy(serverBaseline = true), null))
        assertTrue(isAuthoritative(noBaseline, StageSyncRecord(signature = "x")))
    }

    // ── emptiedEntities ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `deleting the last row of a collection is stated on the wire`() {
        // Nothing else can carry it. There is no per-row delete endpoint, and a collection with zero
        // rows contributes zero entries, so a sweep scoped to what the payload names cannot see the
        // deletion at all. Without this the tool rows come back on the next load — and print.
        val draft = StageDraft(
            stageId = spec.key,
            values = mapOf("currentProblems" to JsonPrimitive("x")),
            rows = listOf(row("processStep", "s1", "Tying")),
            serverBaseline = true,
            emptiedEntities = listOf("tool"),
        )
        val body = build(draft)

        assertTrue(body.replaceCollections)
        assertEquals(listOf("tool"), body.emptiedEntities)
        // processStep is named by its entries, so the sweep already covers it; it must not ALSO be
        // listed as emptied, which would be a contradiction.
        assertFalse(body.emptiedEntities.contains("processStep"))
    }

    @Test
    fun `a deletion is never asserted without the knowledge that justifies one`() {
        // A draft that has never seen the server's copy of the stage might be "missing" a collection
        // because the designer emptied it or because it was entered on the web this morning. It
        // cannot tell the two apart, so it says nothing.
        val draft = typedOneFieldOffline().copy(emptiedEntities = listOf("tool"))
        val body = build(draft)
        assertFalse(body.replaceCollections)
        assertEquals(emptyList<String>(), body.emptiedEntities)
    }

    @Test
    fun `a stale entity key cannot be sent as a deletion instruction`() {
        // A draft written by a build whose registry has since dropped an entity would otherwise name
        // it, and the server would read a key this stage does not declare.
        val draft = StageDraft(
            stageId = spec.key,
            serverBaseline = true,
            emptiedEntities = listOf("tool", "somethingTheRegistryDropped", "traditionalProcess"),
        )
        // The singleton is filtered out too: a singleton is never deleted by omission, and naming one
        // would be asking for the stage's narrative to be erased.
        assertEquals(listOf("tool"), build(draft).emptiedEntities)
    }

    // ── the shape that actually lost the data ────────────────────────────────────────────────────

    @Test
    fun `the payload that swept stage 5 can no longer ask for a sweep`() {
        // Byte-for-byte the body reproduced against the running API:
        //   {"entries":[{"entityKey":"traditionalProcess","data":{"currentProblems":…}}],
        //    "replaceCollections":true}
        // …which answered `removed: 5`.
        val body = build(typedOneFieldOffline())
        assertEquals(1, body.entries.size)
        assertEquals(emptyList<String>(), body.emptiedEntities)
        assertFalse(body.replaceCollections)
    }

    @Test
    fun `a draft holding every row still replaces them wholesale`() {
        // The behaviour that must NOT regress in the other direction: a phone that genuinely owns the
        // stage has to be able to delete a row the designer removed in a courtyard.
        val draft = StageDraft(
            stageId = spec.key,
            values = mapOf("currentProblems" to JsonPrimitive("x")),
            rows = listOf(
                row("processStep", "s1", "Tying"),
                row("processStep", "s2", "Dyeing"),
                row("tool", "t1", "Charkha"),
            ),
            serverBaseline = true,
        )
        val body = build(draft)
        assertTrue(body.replaceCollections)
        assertEquals(4, body.entries.size)   // one singleton + three rows
        assertEquals(
            listOf("traditionalProcess", "processStep", "processStep", "tool"),
            body.entries.map { it.entityKey }
        )
    }

    @Test
    fun `every row still travels with the client key the server matches on`() {
        // Guards the de-duplication scheme while the payload around it is being changed: without
        // `_clientKey` each send inserts new rows and soft-deletes the previous ones, so a costing
        // table grows every time the debounce fires.
        val draft = StageDraft(
            stageId = spec.key,
            rows = listOf(row("processStep", "s1", "Tying")),
            serverBaseline = true,
        )
        val entry = build(draft).entries.single { it.entityKey == "processStep" }
        assertEquals(JsonPrimitive("s1"), entry.data["_clientKey"])
        assertEquals(0, entry.ordinal)
    }
}
