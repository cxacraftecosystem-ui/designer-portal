package com.designprototype.workshop.data

import com.designprototype.workshop.report.BlockKind
import com.designprototype.workshop.report.RichBlock
import com.designprototype.workshop.report.RichDoc
import com.designprototype.workshop.report.RichSpan
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

class InlineMediaWireTest {

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
        stageSeen = true,
    )

    private fun build(draft: StageDraft, media: List<DraftMedia>) =
        buildStageBody(spec, draft, media.associateBy { it.id }, authoritative = true)

    private fun inlineMediaOf(entry: StageEntryBody): List<String> =
        entry.data["narrative"]!!.jsonObject["blocks"]!!.jsonArray
            .mapNotNull { it.jsonObject["media"]?.jsonPrimitive?.content }

    @Test
    fun `an uploaded inline photograph travels under the server's id`() {
        val built = build(draftWith(narrative(localId)), listOf(descriptor(confirmed = true)))

        val entry = built.body.entries.single()
        assertEquals(listOf(serverId), inlineMediaOf(entry))
        assertTrue("nothing is outstanding", built.unresolved.isEmpty())
    }

    @Test
    fun `the rest of the narrative is untouched by the translation`() {
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
        assertEquals(listOf("kind", "spans", "media", "widthPct"), figure.keys.toList())
    }

    @Test
    fun `a stage is held back while the inline photograph is still only on this device`() {
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
        val built = build(draftWith(narrative(serverId)), listOf(descriptor(confirmed = true)))

        assertEquals(listOf(serverId), inlineMediaOf(built.body.entries.single()))
        assertTrue(built.unresolved.isEmpty())
    }

    @Test
    fun `a narrative with no photograph in it is not rewritten at all`() {
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

    @Test
    fun `remapInlineMedia leaves everything it was not asked to change`() {
        val doc = narrative(localId)

        assertSame("a translator that declines changes nothing", doc, remapInlineMedia(doc) { null })
        assertSame("and neither does one that returns the same id", doc, remapInlineMedia(doc) { it })
        val legacy = JsonPrimitive("Prose written before the field was promoted.")
        assertSame(legacy, remapInlineMedia(legacy) { serverId })
    }

    @Test
    fun `remapInlineMedia touches only IMAGE blocks`() {
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
