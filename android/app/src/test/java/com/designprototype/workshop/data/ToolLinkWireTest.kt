package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TOOL'S PLURAL LINKS, AND THE OUTBOX ENTRY WRITTEN A FORTNIGHT BEFORE THEY EXISTED.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS IS A TEST AND NOT A READING OF TWO `Json` CONFIGURATIONS
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `ToolCreateRequest` gained `craftIds` / `artisanIds` on 2026-09-15, and on the wire those three
 * states mean three DIFFERENT things: absent is "leave the stored links alone", `[]` is "delete
 * them", and an explicit `null` is a 422 the server raises rather than guess between the two. The
 * whole of the handset's compatibility story is that those three survive a journey through two
 * different `Json` configurations —
 *
 *   FORM  → `offlineFormJson`  (`encodeDefaults = true`, explicit nulls) → a file on the disk
 *   FILE  → `offlineJson`      (`encodeDefaults = true`)                 → `ToolCreateRequest`
 *   MODEL → `ApiClient.json`   (`explicitNulls = false`)                 → the request body
 *
 * — and every failure on that path is silent and only reachable offline. A body queued by an older
 * build that decoded to `[]` instead of null would DELETE every link on the record it corrects, on a
 * replay days later, under a 200, about an edit to something else entirely. A `[]` that encoded as
 * absent would make the "I unticked every craft" instruction unsendable from a handset with no
 * signal, and the form would come back showing the links it had just been told to remove — the exact
 * defect `patchBodyWithClearances` was written to end, one field along.
 *
 * None of that can be exercised on a desk with a working connection, which is why it is pinned here.
 */
class ToolLinkWireTest {

    /** `MainActivity.offlineFormJson` / `WorkshopRepository.offlineJson`, byte for byte. */
    private val outbox = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The encoder Retrofit's converter is built from. Asked for rather than rebuilt. */
    private val wire = ApiClient.json

    private fun tool(
        craftIds: List<String>? = null,
        artisanIds: List<String>? = null,
    ) = ToolCreateRequest(
        craftName = "Block Printing",
        place = "Bagru",
        artisanName = "Ram Kumar",
        toolkitName = "Chhapa",
        craftIds = craftIds,
        artisanIds = artisanIds,
    )

    /* ── The entry written by a build that had never heard of these keys ─────────────────────── */

    @Test
    fun `a queued body from before the keys decodes to null and replays with neither key`() {
        // THE WHOLE COMPATIBILITY CLAIM. A handset out of coverage for a fortnight replays entries
        // written by the build installed a fortnight ago. Those are silent about these keys because
        // they had never heard of them — not because anybody asked for the links to be cleared —
        // and reading that silence as `[]` would strip every craft and artisan off a record nobody
        // touched.
        val beforeTheKeys = """
            {"craftName":"Block Printing","place":"Bagru","artisanName":"Ram Kumar",
             "toolkitName":"Chhapa","craftId":"c-1","artisanId":"a-1","maker":"UNKNOWN",
             "traditionType":"UNKNOWN","status":"PENDING","recordedTimezone":"Asia/Kolkata"}
        """.trimIndent()

        val decoded = outbox.decodeFromString(ToolCreateRequest.serializer(), beforeTheKeys)
        assertNull("absent must decode to null, never to an empty list", decoded.craftIds)
        assertNull(decoded.artisanIds)

        val body = wire.encodeToJsonElement(ToolCreateRequest.serializer(), decoded).jsonObject
        assertFalse("an absent key must stay absent on the wire", body.containsKey("craftIds"))
        assertFalse(body.containsKey("artisanIds"))
        // And the scalars it DID carry are untouched, so the replay is byte-identical to today's.
        assertEquals("c-1", body["craftId"].toString().trim('"'))
    }

    @Test
    fun `a null round-trips through the outbox file and is dropped on the way out`() {
        // `offlineFormJson` has `explicitNulls` at its default, so the FILE carries a literal null.
        // That is fine and must stay fine: it decodes back to null and `ApiClient.json`'s
        // `explicitNulls = false` drops it, so the server never sees the one value it refuses.
        val stored = outbox.encodeToString(ToolCreateRequest.serializer(), tool())
        assertEquals(
            "the outbox file writes the null explicitly, which is what it has always done",
            JsonNull,
            outbox.parseToJsonElement(stored).jsonObject["craftIds"],
        )

        val replayed = outbox.decodeFromString(ToolCreateRequest.serializer(), stored)
        val body = wire.encodeToJsonElement(ToolCreateRequest.serializer(), replayed).jsonObject
        assertFalse("a null must never reach the server — it is a 422", body.containsKey("craftIds"))
        assertFalse(body.containsKey("artisanIds"))
    }

    /* ── The three states, each surviving intact ────────────────────────────────────────────── */

    @Test
    fun `an empty list survives the outbox and reaches the wire as an empty list`() {
        // "I UNTICKED EVERY CRAFT" HAS TO BE SENDABLE FROM A COURTYARD. `[]` is not the default, so
        // `encodeDefaults = false` on the request encoder does not drop it, and it is not null, so
        // `explicitNulls = false` does not either.
        val stored = outbox.encodeToString(ToolCreateRequest.serializer(), tool(craftIds = emptyList(), artisanIds = emptyList()))
        val replayed = outbox.decodeFromString(ToolCreateRequest.serializer(), stored)
        assertEquals(emptyList<String>(), replayed.craftIds)

        val body = wire.encodeToJsonElement(ToolCreateRequest.serializer(), replayed).jsonObject
        assertEquals(JsonArray(emptyList()), body["craftIds"])
        assertEquals(JsonArray(emptyList()), body["artisanIds"])
    }

    @Test
    fun `the order of a selection survives both encoders`() {
        // ORDER IS THE CONTRACT: the server writes `craftId` from element 0 and joins `craftName` in
        // exactly this sequence. A `Set` anywhere on this path would lose it, and the loss would show
        // up as the wrong craft in every filter that reads the scalar.
        val picked = listOf("c-3", "c-1", "c-2")
        val replayed = outbox.decodeFromString(
            ToolCreateRequest.serializer(),
            outbox.encodeToString(ToolCreateRequest.serializer(), tool(craftIds = picked)),
        )
        assertEquals(picked, replayed.craftIds)

        val onWire = wire.encodeToJsonElement(ToolCreateRequest.serializer(), replayed)
            .jsonObject["craftIds"]!!.jsonArray.map { it.toString().trim('"') }
        assertEquals(picked, onWire)
    }

    /* ── What must NOT happen to them on a correction ───────────────────────────────────────── */

    @Test
    fun `neither key is a clearable link column, so no replay can manufacture a null for one`() {
        // `patchBodyWithClearances` adds an explicit null for every name that is BOTH declared by the
        // request class AND named in the caller's clearable set. Both keys are declared — that is
        // what the assertion below checks — so the only thing keeping a destroying null off the wire
        // is their absence from these two registries. They are not columns and must never join them:
        // `REFERENCE_FIELD_NOUNS` is foreign keys, and the dangling-reference sentence a designer
        // reads is already carried by `craftId` / `artisanId`, which the form sends beside them.
        assertFalse("craftIds is not a workshop link column", "craftIds" in WORKSHOP_LINK_KEYS)
        assertFalse("artisanIds is not a workshop link column", "artisanIds" in WORKSHOP_LINK_KEYS)
        assertFalse("craftIds is not a foreign key", REFERENCE_FIELD_NOUNS.containsKey("craftIds"))
        assertFalse("artisanIds is not a foreign key", REFERENCE_FIELD_NOUNS.containsKey("artisanIds"))
        assertTrue("the singular craft key is still the one a 404 names", REFERENCE_FIELD_NOUNS.containsKey("craftId"))
        assertTrue(REFERENCE_FIELD_NOUNS.containsKey("artisanId"))
    }

    @Test
    fun `a replay that 404s still names the craft and the artisan from the scalars`() {
        // The outbox explains a 404 by scanning the payload for reference ids it actually SENT. A
        // list is a `JsonArray` and is correctly not read as one — the scan takes primitives only —
        // and it does not need to be, because the form sends `craftId` / `artisanId` beside the
        // lists. The sentence a designer reads is unchanged by this whole change.
        val payload = outbox.encodeToString(
            ToolCreateRequest.serializer(),
            tool(craftIds = listOf("c-1")).copy(craftId = "c-1", artisanId = "a-1"),
        )
        val candidates = danglingReferenceCandidates(payload)
        assertTrue("craftId" in candidates)
        assertTrue("artisanId" in candidates)
        assertFalse("a list is not a reference the scan can resolve", "craftIds" in candidates)
    }

    /* ── Reading them back ──────────────────────────────────────────────────────────────────── */

    @Test
    fun `a tool from a server that predates the link tables decodes to empty lists`() {
        val old = """{"id":"t-1","toolkitName":"Chhapa","craftId":"c-1","artisanId":"a-1"}"""
        val tool = wire.decodeFromString(ToolDetailDto.serializer(), old)
        assertEquals(emptyList<ToolCraftLinkDto>(), tool.craftLinks)
        assertEquals(emptyList<ToolArtisanLinkDto>(), tool.artisanLinks)
        // Which is exactly why the form falls back to the scalar when the lists are empty: this
        // record has one craft and the multi-select must open holding it.
        assertEquals("c-1", tool.craftId)
    }

    @Test
    fun `an explicit null for either list decodes as empty rather than failing the whole read`() {
        // `ApiClient.json` sets `coerceInputValues`, which exists so one odd row cannot fail a whole
        // list parse. Asserted rather than assumed, because the form reads `craftLinks` with `?.` on
        // the record and a decode failure here would empty a form that has data behind it.
        val nulled = """{"id":"t-1","toolkitName":"Chhapa","craftLinks":null,"artisanLinks":null}"""
        val tool = wire.decodeFromString(ToolDetailDto.serializer(), nulled)
        assertEquals(emptyList<ToolCraftLinkDto>(), tool.craftLinks)
        assertEquals(emptyList<ToolArtisanLinkDto>(), tool.artisanLinks)
    }

    @Test
    fun `the links carry the embedded record, which is what draws an off-page row honestly`() {
        val payload = """
            {"id":"t-1","toolkitName":"Chhapa","craftName":"Bandhani, Block Printing",
             "craftLinks":[{"id":"l-1","toolId":"t-1","craftId":"c-2","craft":{"id":"c-2","name":"Bandhani"}},
                           {"id":"l-2","toolId":"t-1","craftId":"c-1","craft":{"id":"c-1","name":"Block Printing"}}],
             "artisanLinks":[{"id":"l-3","toolId":"t-1","artisanId":"a-1",
                              "artisan":{"id":"a-1","name":"Ram Kumar","place":"Bagru","status":"APPROVED","craftId":"c-1"}}]}
        """.trimIndent()
        val tool = wire.decodeFromString(ToolDetailDto.serializer(), payload)

        // THE SERVER'S ORDER IS THE SELECTION'S ORDER, and it agrees with `craftName`. The form seeds
        // its multi-select straight from this, so an order lost here is a first craft changed.
        assertEquals(listOf("c-2", "c-1"), tool.craftLinks.map { it.craftId })
        assertEquals("Bandhani, Block Printing", tool.craftName)
        assertEquals("Bandhani", tool.craftLinks.first().craft?.name)
        // And the artisan arrives whole, so a link the register could not list still has a name, a
        // place and a craft to sort under rather than an anonymous row.
        assertEquals("Ram Kumar", tool.artisanLinks.single().artisan?.name)
        assertEquals("Bagru", tool.artisanLinks.single().artisan?.place)
        // `id` and `toolId` ride along on the wire and are deliberately NOT declared on either DTO:
        // nothing on this client addresses a link row by its own id, and `ignoreUnknownKeys` is what
        // lets the server carry them without this file having to grow a field it cannot use.
    }
}
