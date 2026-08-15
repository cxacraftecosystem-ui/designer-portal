package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a handset is entitled to SAY about a designer's own answers — the `_custom` entry of a stage
 * payload, decided with no network and no device.
 *
 * `StagePayloadAuthorityTest` is the same shape one argument over: there, `replaceCollections = true`
 * is an assertion ("these are now exactly the rows, delete anything else") and hard-coding it deleted
 * a fortnight of process steps. Here the assertion is an EMPTY CONTAINER. `plan_custom_write` treats
 * "no `_custom` entry at all" and "an entry carrying `{}`" as two different instructions
 * (`custom_sections.py:979`): the first writes NO ROW, and the second is a designer clearing every
 * answer and IS written. So a `{}` from a handset that simply never fetched the definition reads on
 * the server as "the designer cleared every custom answer" — and since a stage save replaces the row
 * wholesale and writes no `RecordRevision`, the office's answers would be gone in place with the save
 * reporting success.
 *
 * The five rows of the table this pins:
 *
 *   never read, nothing answered       →  NO ENTRY AT ALL        (the installed fleet's case)
 *   never read, something answered     →  entry, `merge = true`  (the courtyard's case)
 *   row never seen, answered           →  entry, `merge = true`  (authority over the stage is not
 *                                                                 authority over this row)
 *   row read + authoritative, answered →  entry, `merge` omitted (a full replace, now honest)
 *   row read + authoritative, emptied  →  entry carrying `{}`    (the only honest clear-all)
 *
 * THE THIRD ROW IS THE ONE THAT COST DATA. `isAuthoritative` is satisfied by a signature, and
 * `recordStageSent` stamps one after ANY successful save — including a merge save from a stage that
 * was seeded blank because its download failed. The custom answers live in a row of their own that
 * such a save does not touch, so the phone reached "authoritative" having never seen a single one of
 * them, and the next payload's `{}` deleted the office's answers in place. [StageDraft.customSeen] is
 * the fact that separates the two, and it is why these drafts name it explicitly.
 */
class DwCustomPayloadTest {

    private val spec = StageDto(
        number = 5,
        key = "TRADITIONAL_PROCESS_BASELINE",
        title = "Traditional process baseline",
        entities = listOf(
            EntityDto(
                key = "traditionalProcess", cardinality = "SINGLETON", title = "Traditional process",
                fields = listOf(FieldDto(key = "currentProblems", label = "Current problems", type = "LONG_TEXT")),
            ),
            EntityDto(
                key = "processStep", cardinality = "COLLECTION", title = "Process step",
                fields = listOf(FieldDto(key = "name", label = "Name", type = "TEXT")),
            ),
        ),
    )

    private fun build(
        stored: StageDraft?,
        authoritative: Boolean,
        customHeld: Boolean = true,
    ) = buildStageBody(spec, stored, emptyMap(), authoritative, customHeld)

    private fun customEntry(stored: StageDraft?, authoritative: Boolean, customHeld: Boolean = true) =
        build(stored, authoritative, customHeld).body.entries.firstOrNull { it.entityKey == "_custom" }

    // ── The omission rule ───────────────────────────────────────────────────────────────────────

    /**
     * THE CASE THAT MAKES THE WHOLE DESIGN SAFE AGAINST THE INSTALLED FLEET.
     *
     * A handset that has never read the definition and holds no answers sends nothing at all, so the
     * server writes no row and whatever the office typed on the web survives untouched.
     */
    @Test
    fun `a never-read client with no custom answers emits no entry`() {
        val stored = StageDraft(
            stageId = spec.key,
            values = mapOf("currentProblems" to JsonPrimitive("x")),
            customSeen = true,
        )
        assertNull(customEntry(stored, authoritative = false, customHeld = false))
        assertNull(customEntry(stored, authoritative = false, customHeld = true))
        assertNull("a draft with no custom bucket at all", customEntry(null, authoritative = false))
    }

    /**
     * AND AN EMPTY CONTAINER IS NEVER SENT BY A DEVICE THAT HAS NOT READ THE DEFINITION, even once it
     * is authoritative for the stage. Authority over the stage is not authority over a question this
     * phone has never been shown.
     */
    @Test
    fun `an authoritative client that holds no definition still emits no empty container`() {
        val stored = StageDraft(
            stageId = spec.key,
            values = mapOf("currentProblems" to JsonPrimitive("x")),
            customSeen = true,
        )
        assertNull(customEntry(stored, authoritative = true, customHeld = false))
    }

    /**
     * THE CASE THAT DELETED THE OFFICE'S ANSWERS, and the reason [StageDraft.customSeen] exists.
     *
     * Everything here says "authoritative": `stageSeen` is set, so this device HAS read the stage,
     * and the definition is held, so the questions are known. What is NOT known is what the server's
     * `_custom` row holds — and the gap is narrower than it looks, which is why this test is kept
     * even now that a save can no longer manufacture authority. `stageSeen` and [StageDraft.customSeen]
     * are set together by every read this build makes, but they are not the same fact and were not
     * always written by the same code: a draft written between the two builds carries one without the
     * other, and a stage read by a build that had no notion of a custom container carries `stageSeen`
     * over a container it never looked at.
     *
     * An empty container from such a device is
     * `plan_custom_write(sent={}, previous={'loomsWorking': 12}, merge=False)`, which returns
     * `data={}`: the office's answer gone in place, no `RecordRevision`, save successful.
     */
    @Test
    fun `a client that has never read the server's container never sends an empty one`() {
        val stored = StageDraft(
            stageId = spec.key,
            values = mapOf("currentProblems" to JsonPrimitive("x")),
            customSeen = false,
        )
        assertNull(customEntry(stored, authoritative = true, customHeld = true))
    }

    /**
     * AND WHEN SUCH A CLIENT DOES HAVE SOMETHING TO SAY, IT SAYS IT AS A MERGE. The answer the
     * designer typed must land; the six the office typed and this phone has never read must not be
     * swept by a replace claiming to be the whole picture.
     */
    @Test
    fun `a client that has never read the container sends its own answers as a merge`() {
        val stored = StageDraft(
            stageId = spec.key,
            custom = mapOf("loomsWorking" to JsonPrimitive(12)),
            customSeen = false,
        )
        val entry = customEntry(stored, authoritative = true, customHeld = true)!!
        assertTrue("authority over the stage is not authority over this row", entry.merge)
        assertEquals(JsonPrimitive(12), entry.data["loomsWorking"])
    }

    /** The one honest clear-all: this device has READ the container AND holds the definition. */
    @Test
    fun `an authoritative client that has read the container sends an empty one as a clearance`() {
        val stored = StageDraft(
            stageId = spec.key,
            values = mapOf("currentProblems" to JsonPrimitive("x")),
            customSeen = true,
        )
        val entry = customEntry(stored, authoritative = true, customHeld = true)
        assertEquals("_custom", entry?.entityKey)
        assertTrue(entry!!.data.isEmpty())
        assertFalse("a replace, and honestly so", entry.merge)
    }

    // ── merge ───────────────────────────────────────────────────────────────────────────────────

    /**
     * `merge = true` on a draft that has never seen the server's copy, verbatim from the singleton
     * arm's argument: `plan_custom_write` shallow-merges over `previous`, so the seven answers the
     * office typed survive the one answer this phone recorded in a courtyard.
     */
    @Test
    fun `a never-read client with one answer sends it as a merge`() {
        val stored = StageDraft(stageId = spec.key, custom = mapOf("loomsWorking" to JsonPrimitive(12)))
        val entry = customEntry(stored, authoritative = false)!!
        assertTrue(entry.merge)
        assertEquals(JsonPrimitive(12), entry.data["loomsWorking"])
    }

    /**
     * AN ANSWER GOES EVEN WHERE THE DEFINITION HAS BEEN LOST, and that is deliberately wider than
     * "the definition is held". A cache deleted by a decode failure must not strand a fortnight of a
     * designer's answers on the handset; the server validates against its own definition either way,
     * and an unrecognised key comes back in `droppedCustomKeys` rather than destroying anything.
     */
    @Test
    fun `an answered container is sent even when this device has lost the definition`() {
        val stored = StageDraft(stageId = spec.key, custom = mapOf("loomsWorking" to JsonPrimitive(12)))
        val entry = customEntry(stored, authoritative = false, customHeld = false)
        assertEquals(JsonPrimitive(12), entry?.data?.get("loomsWorking"))
    }

    /**
     * `merge` OMITTED rather than sent as false, which is what keeps a handset running ahead of the
     * API from 422ing every save — `APIModel` is `extra="forbid"`, and `ApiClient.retrofit` leaves
     * `encodeDefaults` off so a property at its default never reaches the wire.
     */
    @Test
    fun `an authoritative client sends a replace, and merge is left at its default so it is omitted`() {
        val stored = StageDraft(
            stageId = spec.key,
            custom = mapOf("loomsWorking" to JsonPrimitive(12)),
            // Both halves of the claim, named: this draft holds the server's copy of the stage AND
            // has read its custom container. Either one alone is not a replacement's worth of
            // knowledge — see the case two above this one.
            customSeen = true,
        )
        val entry = customEntry(stored, authoritative = true)!!
        assertFalse(entry.merge)
        assertTrue(build(stored, authoritative = true).body.entries.none { it.merge })
    }

    // ── What travels, and what does not ─────────────────────────────────────────────────────────

    /**
     * The protocol's own keys are stripped by hand, as `wireData` strips them for a registry entity
     * and for its reason: the server reports any key it does not know, and passing these through
     * would put a line in every response for something working exactly as designed.
     */
    @Test
    fun `underscore-prefixed keys never leave the phone`() {
        val stored = StageDraft(
            stageId = spec.key,
            custom = mapOf(
                "loomsWorking" to JsonPrimitive(12),
                "_scratch" to JsonPrimitive("local only"),
            ),
        )
        val entry = customEntry(stored, authoritative = true)!!
        assertEquals(setOf("loomsWorking"), entry.data.keys)
    }

    /**
     * `_custom` IS NOT A COLLECTION AND CANNOT BE SWEPT. `emptiedEntities` is intersected with the
     * stage's own declared collections here, and again with the registry's own collection keys
     * server-side — so a deletion instruction can never be built for the custom row.
     */
    @Test
    fun `_custom can never enter emptiedEntities`() {
        val stored = StageDraft(
            stageId = spec.key,
            custom = mapOf("loomsWorking" to JsonPrimitive(12)),
            emptiedEntities = listOf("processStep", "_custom"),
        )
        val body = build(stored, authoritative = true).body
        assertEquals(listOf("processStep"), body.emptiedEntities)
    }

    /**
     * THE ANSWERS ARE NOT SMUGGLED INTO THE SINGLETON. A plain key in `values` is posted inside the
     * core entry, dropped by `validate_entry`, and returned in `droppedKeys` — which would fire "this
     * phone is running a newer field registry than the server" on every save of every workshop with a
     * custom section, destroying the one registry-drift signal this repository has.
     */
    @Test
    fun `the custom answers travel in their own entry and not in the singleton's data`() {
        val stored = StageDraft(
            stageId = spec.key,
            values = mapOf("currentProblems" to JsonPrimitive("Slow warping")),
            custom = mapOf("loomsWorking" to JsonPrimitive(12)),
        )
        val body = build(stored, authoritative = true).body
        val singleton = body.entries.single { it.entityKey == "traditionalProcess" }
        assertEquals(setOf("currentProblems"), singleton.data.keys)
        assertEquals(1, body.entries.count { it.entityKey == "_custom" })
    }

    /**
     * The entry sits AFTER the registry's entities, so an error index the server keys by array
     * position cannot shift a collection row's message onto a row that is fine.
     */
    @Test
    fun `the custom entry is last, after every registry entry`() {
        val stored = StageDraft(
            stageId = spec.key,
            values = mapOf("currentProblems" to JsonPrimitive("x")),
            rows = listOf(DraftRow(id = dwRowId("processStep", "r1"), values = mapOf("name" to JsonPrimitive("Warp")))),
            custom = mapOf("loomsWorking" to JsonPrimitive(12)),
        )
        val keys = build(stored, authoritative = true).body.entries.map { it.entityKey }
        assertEquals(listOf("traditionalProcess", "processStep", "_custom"), keys)
    }

    // ── The gate that decides whether an empty container is an instruction ───────────────────────

    @Test
    fun `an empty container is an instruction only where the definition asks something on this stage`() {
        val held = DwCustomCache(
            workshopId = "w1", customSchemaVersion = "d1", complete = true,
            sections = listOf(
                DwCustomSectionDto(
                    key = "loomAudit", stageKey = spec.key,
                    fields = listOf(DwCustomFieldDto(key = "loomsWorking", label = "Looms")),
                ),
            ),
        )
        assertTrue(dwCustomHeldFor(held, spec.key))
        // …and NOT on the other twenty-one stages, which is what stops the release that adds this
        // code writing an empty `_custom` row for every stage of every workshop with a definition.
        assertFalse(dwCustomHeldFor(held, "SKETCH_DEVELOPMENT"))
        // Never read: an empty container from here would read as a clearance.
        assertFalse(dwCustomHeldFor(null, spec.key))
        // Read, and the server said there are none: nothing to clear anywhere.
        assertFalse(dwCustomHeldFor(DwCustomCache(workshopId = "w1", complete = true), spec.key))
        // A RETIRED field still counts as "asks something": it is retired precisely because it has an
        // answer, so the server is still holding that stage's container.
        val retiredOnly = held.copy(
            sections = listOf(
                held.sections.single().copy(
                    fields = listOf(DwCustomFieldDto(key = "looms", label = "Looms", retired = true)),
                ),
            ),
        )
        assertTrue(dwCustomHeldFor(retiredOnly, spec.key))
    }
}
