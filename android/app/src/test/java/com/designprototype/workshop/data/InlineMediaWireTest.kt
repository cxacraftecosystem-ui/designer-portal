package com.designprototype.workshop.data

import com.designprototype.workshop.report.BlockKind
import com.designprototype.workshop.report.RichBlock
import com.designprototype.workshop.report.RichDoc
import com.designprototype.workshop.report.RichSpan
import com.designprototype.workshop.report.mediaIds
import com.designprototype.workshop.report.remapInlineMedia
import com.designprototype.workshop.report.toJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A photograph placed INSIDE a narrative has to reach the server under the SERVER'S id.
 *
 * ── THE FAILURE THIS EXISTS TO PREVENT ────────────────────────────────────────────────────────
 *
 * `wireData` translated this device's media UUIDs to the server's for every key whose field type is
 * IMAGE, IMAGE_LIST, FILE, AUDIO or VIDEO. A photograph placed in the prose is not in any of those:
 * its id sits in an IMAGE block, several levels down inside a RICH_TEXT field's document JSON. So
 * the narrative went up carrying `9f3c1b7e-…`, a UUID that exists only on one handset.
 *
 * NOTHING WOULD HAVE FAILED. The stage saves. The sync status reads clean. The designer sees the
 * photograph in the editor, on the phone, for ever. And the .docx the ministry is handed comes out
 * of the server's `to_report_blocks` with the picture silently dropped, because `resolve_media`
 * answers None for an id no MediaFile row has — deliberately, since a placeholder box in a
 * government document is worse than a gap. The first person to notice is the officer.
 *
 * The server has looked inside these documents since the feature was written
 * (`design_workshops._media_ids` walks every RICH_TEXT field through `rich_text.media_ids`). It was
 * the phone that only ever looked at the top level of a record.
 *
 * ── AND WHY THE PROSE IS NEVER TRIMMED TO ACHIEVE IT ──────────────────────────────────────────
 *
 * A media FIELD whose reference has not uploaded yet has its value dropped from the payload and the
 * stage is held back. Doing that to a narrative would throw away a page of somebody's writing to
 * avoid sending one id. So the document travels unchanged and the id is recorded as UNRESOLVED,
 * which holds the whole stage back until the upload lands — nothing sent wrong, nothing thrown
 * away, the stage simply late by exactly one upload.
 */
class InlineMediaWireTest {

    // ── A stage with one narrative, one media field and one plain field ─────────────────────────

    private val spec = StageDto(
        number = 3,
        key = "DESIGN_INTERVENTION",
        title = "Design intervention",
        entities = listOf(
            EntityDto(
                key = "intervention",
                cardinality = "SINGLETON",
                title = "Intervention",
                fields = listOf(
                    FieldDto(key = "narrative", label = "Narrative", type = "RICH_TEXT"),
                    FieldDto(key = "photos", label = "Photographs", type = "IMAGE_LIST"),
                    FieldDto(key = "title", label = "Title", type = "TEXT"),
                ),
            ),
        ),
    )

    private val localId = "9f3c1b7e-0000-4a2b-9c1d-000000000042"
    private val serverId = "cm5x9q2p0000abcdef123456"

    private fun descriptor(confirmed: Boolean) = DraftMedia(
        id = localId,
        relativePath = "media/$localId.jpg",
        originalFilename = "loom.jpg",
        mediaType = "IMAGE",
        remoteMediaId = if (confirmed) serverId else null,
    )

    /** A narrative with a paragraph, a photograph and its caption — what the button produces. */
    private fun narrative(media: String) = toJson(
        RichDoc(
            listOf(
                RichBlock(kind = BlockKind.PARAGRAPH, spans = listOf(RichSpan("The loom was rebuilt."))),
                RichBlock(
                    kind = BlockKind.IMAGE,
                    spans = listOf(RichSpan("The rebuilt loom")),
                    media = media,
                    widthPct = 40f,
                ),
            )
        )
    )

    private fun draftWith(
        narrativeValue: JsonElement,
        photos: List<String> = emptyList(),
    ) = StageDraft(
        stageId = spec.key,
        values = buildMap<String, JsonElement> {
            put("narrative", narrativeValue)
            put("title", JsonPrimitive("Kotpad"))
            if (photos.isNotEmpty()) {
                put("photos", JsonArray(photos.map { JsonPrimitive(it) }))
            }
        },
        serverBaseline = true,
    )

    private fun build(draft: StageDraft, media: List<DraftMedia>) =
        buildStageBody(spec, draft, media.associateBy { it.id }, authoritative = true)

    private fun inlineMediaOf(entry: StageEntryBody): List<String> =
        entry.data["narrative"]!!.jsonObject["blocks"]!!.jsonArray
            .mapNotNull { it.jsonObject["media"]?.jsonPrimitive?.content }

    // ── The translation ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `an uploaded inline photograph travels under the server's id`() {
        // THE ASSERTION THAT KEEPS THE MINISTRY'S COPY HONEST.
        val built = build(draftWith(narrative(localId)), listOf(descriptor(confirmed = true)))

        val entry = built.body.entries.single()
        assertEquals(listOf(serverId), inlineMediaOf(entry))
        assertTrue("nothing is outstanding", built.unresolved.isEmpty())
    }

    @Test
    fun `the rest of the narrative is untouched by the translation`() {
        // The swap must be surgery on one key of one block. A round trip through the model would
        // re-serialise the whole document, which is how a build one release behind loses a mark it
        // does not recognise from prose it was only asked to relabel.
        val built = build(draftWith(narrative(localId)), listOf(descriptor(confirmed = true)))

        val blocks = built.body.entries.single().data["narrative"]!!.jsonObject["blocks"]!!.jsonArray
        assertEquals(2, blocks.size)
        assertEquals("PARAGRAPH", blocks[0].jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals(
            "The loom was rebuilt.",
            blocks[0].jsonObject["spans"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        val figure = blocks[1].jsonObject
        assertEquals("IMAGE", figure["kind"]!!.jsonPrimitive.content)
        assertEquals("40.0", figure["widthPct"]!!.jsonPrimitive.content)
        assertEquals(
            "The rebuilt loom",
            figure["spans"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        // The key ORDER is what the port's oracle compares against, and `plus` on the backing map
        // replaces `media` in place rather than moving it to the end.
        assertEquals(listOf("kind", "spans", "media", "widthPct"), figure.keys.toList())
    }

    @Test
    fun `a stage is held back while the inline photograph is still only on this device`() {
        // Held, never trimmed. `save_stage` writes the entry WHOLESALE, so a payload that dropped
        // the narrative would not merely fail to add the picture — it would erase the prose the
        // server already holds under that key.
        val built = build(draftWith(narrative(localId)), listOf(descriptor(confirmed = false)))

        assertEquals(listOf(localId), built.unresolved)
        val entry = built.body.entries.single()
        assertEquals("the narrative is sent nowhere, but it is not trimmed", listOf(localId), inlineMediaOf(entry))
        assertEquals(
            "and the prose is all still there",
            "The loom was rebuilt.",
            entry.data["narrative"]!!.jsonObject["blocks"]!!.jsonArray[0]
                .jsonObject["spans"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `an id this device has never held passes through as the server id it already is`() {
        // A picture placed in the browser, on a stage this handset seeded from the server. Treating
        // it as unresolvable would hold that stage back for ever, waiting on an upload that
        // happened last week on somebody else's machine.
        val built = build(draftWith(narrative(serverId)), listOf(descriptor(confirmed = true)))

        assertEquals(listOf(serverId), inlineMediaOf(built.body.entries.single()))
        assertTrue(built.unresolved.isEmpty())
    }

    @Test
    fun `a narrative with no photograph in it is not rewritten at all`() {
        // The payload is HASHED to decide whether a stage needs sending. A translation pass that
        // re-serialised every narrative would change the signature of stages nobody had touched and
        // re-upload the whole workshop on the next sync.
        val plain = toJson(
            RichDoc(listOf(RichBlock(spans = listOf(RichSpan("Just prose, no pictures.")))))
        )
        val built = build(draftWith(plain), listOf(descriptor(confirmed = true)))

        assertSame(
            "the same instance goes on the wire",
            plain,
            built.body.entries.single().data["narrative"],
        )
    }

    @Test
    fun `the media field beside it still behaves exactly as it did`() {
        // The control. The rich-text arm is checked BEFORE the media arm in `wireData`, so a key
        // that is somehow both would take the wrong path; these two are separate keys and each must
        // keep its own rule — the media field DROPS an unresolved reference, the narrative does not.
        val draft = draftWith(narrative(serverId), photos = listOf(localId))

        val uploaded = build(draft, listOf(descriptor(confirmed = true)))
        assertEquals(
            listOf(serverId),
            uploaded.body.entries.single().data["photos"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        val pending = build(draft, listOf(descriptor(confirmed = false)))
        assertFalse(
            "an unresolved media FIELD is dropped, unlike a narrative",
            pending.body.entries.single().data.containsKey("photos"),
        )
        assertEquals(listOf(localId), pending.unresolved)
    }

    // ── The document walkers the sync layer is built on ─────────────────────────────────────────

    @Test
    fun `mediaIds finds the photographs a designer placed in prose`() {
        // The port of `rich_text.media_ids`, which is what makes the SERVER load these pictures for
        // its own report. Asserted here so the two walkers cannot drift apart.
        assertEquals(setOf(localId), mediaIds(narrative(localId)))
        assertEquals(emptySet<String>(), mediaIds(toJson(RichDoc(listOf(RichBlock(spans = listOf(RichSpan("x"))))))))
        assertEquals("a plain string has no blocks in it", emptySet<String>(), mediaIds(JsonPrimitive("legacy prose")))
        assertEquals("and neither has nothing", emptySet<String>(), mediaIds(null))
    }

    @Test
    fun `remapInlineMedia leaves everything it was not asked to change`() {
        val doc = narrative(localId)

        assertSame("a translator that declines changes nothing", doc, remapInlineMedia(doc) { null })
        assertSame("and neither does one that returns the same id", doc, remapInlineMedia(doc) { it })
        // A pre-promotion value is a bare string with no blocks — it must survive, not become null.
        val legacy = JsonPrimitive("Prose written before the field was promoted.")
        assertSame(legacy, remapInlineMedia(legacy) { serverId })
    }

    @Test
    fun `remapInlineMedia touches only IMAGE blocks`() {
        // A TABLE block has no `media` key and a paragraph has none either; a walker that rewrote by
        // key name rather than by kind would invent one, and `fromJson` would then read a paragraph
        // carrying a media id it has no arm for.
        val mixed = toJson(
            RichDoc(
                listOf(
                    RichBlock(kind = BlockKind.PARAGRAPH, spans = listOf(RichSpan("prose"))),
                    RichBlock(kind = BlockKind.IMAGE, media = localId),
                    RichBlock(
                        kind = BlockKind.TABLE,
                        rows = listOf(listOf(listOf(RichSpan("Head"))), listOf(listOf(RichSpan("cell")))),
                    ),
                )
            )
        )

        val out = remapInlineMedia(mixed) { serverId }!!.jsonObject["blocks"]!!.jsonArray

        assertFalse("the paragraph gains nothing", out[0].jsonObject.containsKey("media"))
        assertEquals(serverId, out[1].jsonObject["media"]!!.jsonPrimitive.content)
        assertFalse("the table gains nothing", out[2].jsonObject.containsKey("media"))
        assertTrue("and keeps its grid", out[2].jsonObject.containsKey("rows"))
    }
}
