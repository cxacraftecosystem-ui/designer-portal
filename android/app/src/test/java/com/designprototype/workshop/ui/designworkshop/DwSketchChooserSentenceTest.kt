package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.StagePush
import com.designprototype.workshop.data.StageSaveResultDto
import com.designprototype.workshop.data.dwRowId
import com.designprototype.workshop.data.entityKey
import com.designprototype.workshop.data.rowsFor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DwSketchChooserSentenceTest {

    private val failures = listOf(
        "offline" to DW_SKETCH_CHOOSER_OFFLINE,
        "refused" to DW_SKETCH_CHOOSER_REFUSED,
        "nothing lost" to DW_SKETCH_CHOOSER_NOTHING_LOST,
    )

    @Test
    fun `only the answered-and-none sentence names an administrator`() {
        assertTrue(DW_SKETCH_CHOOSER_NO_WORKSHOPS.contains("administrator"))
        failures.forEach { (name, sentence) ->
            assertFalse(
                "the '$name' sentence sends a designer to an administrator about a request that " +
                    "failed — that is the defect this test exists for",
                sentence.contains("administrator", ignoreCase = true),
            )
        }
    }

    @Test
    fun `the answered-and-none sentence never claims a failure`() {
        listOf("could not", "no connection", "failed", "try again").forEach { hedge ->
            assertFalse(
                "the empty-state sentence contains failure language: '$hedge'",
                DW_SKETCH_CHOOSER_NO_WORKSHOPS.contains(hedge, ignoreCase = true),
            )
        }
    }

    @Test
    fun `the offline sentence separates an empty list from an unaskable one`() {
        for (sentence in listOf(DW_SKETCH_CHOOSER_OFFLINE, DW_SKETCH_CHOOSER_STAGE_OFFLINE, DW_SKETCH_CHOOSER_ROUND_OFFLINE)) {
            assertTrue(sentence, sentence.startsWith("No connection"))
            assertTrue(sentence, sentence.contains("Try again"))
            assertTrue(sentence, !sentence.contains("asked for"))
        }
    }

    @Test
    fun `the failure block says that nothing is lost, and why it can`() {
        assertTrue(DW_SKETCH_CHOOSER_NOTHING_LOST.startsWith("Nothing is lost:"))
        assertTrue(
            "the promise must name the mechanism that actually carries it",
            DW_SKETCH_CHOOSER_NOTHING_LOST.contains("has to be chosen before"),
        )
        assertFalse(
            "this screen writes now — the retired 'only reads' justification must not come back",
            DW_SKETCH_CHOOSER_NOTHING_LOST.contains("only reads", ignoreCase = true),
        )
        assertTrue(DW_SKETCH_CHOOSER_NOTHING_LOST.contains("Design workshops"))
    }

    @Test
    fun `the three answers are three different sentences`() {
        val all = listOf(
            DW_SKETCH_CHOOSER_NO_WORKSHOPS,
            DW_SKETCH_CHOOSER_OFFLINE,
            DW_SKETCH_CHOOSER_REFUSED,
        )
        assertEquals("two of the three answers share a sentence", all.size, all.toSet().size)
        all.forEach { assertTrue("an answer is blank", it.isNotBlank()) }
    }

    @Test
    fun `the fallback prefill does not borrow the server's reason`() {
        assertFalse(
            "the fallback must not claim an allocation the repository never reported",
            DW_SKETCH_CHOOSER_FALLBACK_PREFILL.contains("added to", ignoreCase = true),
        )
        assertTrue(
            "a box that fills itself in must say why",
            DW_SKETCH_CHOOSER_FALLBACK_PREFILL.contains("because"),
        )
        assertTrue(
            "and must offer the way out of a wrong guess",
            DW_SKETCH_CHOOSER_FALLBACK_PREFILL.contains("Change it"),
        )
    }

    @Test
    fun `the two not-yet states are explained and are not worded as refusals`() {
        listOf(
            "pick a workshop first" to DW_SKETCH_CHOOSER_PICK_FIRST,
            "stage not yet read" to DW_SKETCH_CHOOSER_STALE,
        ).forEach { (name, sentence) ->
            assertTrue("the '$name' state says nothing at all", sentence.isNotBlank())
            listOf("not allowed", "permission", "you may not").forEach { refusal ->
                assertFalse(
                    "the '$name' state is worded as a refusal ('$refusal'), which it is not",
                    sentence.contains(refusal, ignoreCase = true),
                )
            }
        }
        assertTrue(DW_SKETCH_CHOOSER_STALE.contains("Nothing has been lost"))
    }

    @Test
    fun `a refused local write names what did not happen and what survived`() {
        assertTrue(DW_SKETCH_CHOOSER_ROW_NOT_ADDED.contains("NOT been added"))
        assertTrue(DW_SKETCH_CHOOSER_ROW_NOT_ADDED.contains("Nothing has changed"))
        assertTrue(DW_SKETCH_CHOOSER_FILE_NOT_ATTACHED.contains("NOT been attached"))
        assertTrue(
            "a designer whose file was copied but not referenced must be told it survived",
            DW_SKETCH_CHOOSER_FILE_NOT_ATTACHED.contains("is not lost"),
        )
    }

    private val derivationActs: List<String> =
        listOf(DW_TRACE_CARD_TITLE, DW_RECTIFY_CARD_TITLE, DW_MEASURE_CARD_TITLE)
            .map { it.substringBefore(' ').lowercase(Locale.ROOT) }

    private val whereTheRestOfTheRecordIs = listOf(
        "the sketches empty note" to DW_SKETCH_CHOOSER_NO_SKETCHES_YET,
        "the prototypes empty note" to DW_SKETCH_CHOOSER_NO_PROTOTYPES_YET,
        "the sketches stage note" to DW_SKETCH_CHOOSER_SKETCH_ELSEWHERE,
        "the prototypes stage note" to DW_SKETCH_CHOOSER_PROTOTYPE_ELSEWHERE,
    )

    @Test
    fun `a sentence about what is elsewhere never names an act this tab performs`() {
        whereTheRestOfTheRecordIs.forEach { (name, sentence) ->
            assertTrue("$name says nothing at all", sentence.isNotBlank())
            derivationActs.forEach { act ->
                assertFalse(
                    "$name puts '$act' on the stage form, and this tab mounts a card for it — a " +
                        "designer who believes that sentence never scrolls to the control",
                    sentence.contains(act, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `the subtitle names every act the Upload tab can perform on a photograph`() {
        derivationActs.forEach { act ->
            assertTrue(
                "the screen's own subtitle never mentions '$act', which this tab does",
                DW_SKETCH_CHOOSER_SUBTITLE.contains(act, ignoreCase = true),
            )
        }
        assertFalse(
            "the retired clause put every act on the stage form and must not come back",
            DW_SKETCH_CHOOSER_SUBTITLE.contains("The work itself lives on"),
        )
    }

    @Test
    fun `the prototype sentence names the exact words the document generator writes`() {
        assertTrue(
            "the .docx says this and the sentence must quote it",
            DW_PROTOTYPE_3D_IN_THE_REPORT.contains("1 3D model attached"),
        )
        listOf("is printed", "is drawn", "is shown", "is rendered").forEach { promise ->
            assertFalse(
                "the sentence must not promise the model itself reaches the page: $promise",
                DW_PROTOTYPE_3D_IN_THE_REPORT.contains(promise, ignoreCase = true),
            )
        }
        assertTrue(
            "the action is the point of saying any of this",
            DW_PROTOTYPE_3D_IN_THE_REPORT.contains("photograph the piece as well"),
        )
    }

    private fun row(id: String, vararg values: Pair<String, String>): DraftRow =
        DraftRow(
            id = id,
            values = values.associate { (key, value) -> key to JsonPrimitive(value) },
        )

    @Test
    fun `a row is named by its own name, then its identifier, then its position`() {
        assertEquals(
            "Bamboo stool",
            dwChooserRowLabel(row("sketch#a", "name" to "Bamboo stool", "sketchNo" to "S-4"), 0),
        )
        assertEquals("S-4", dwChooserRowLabel(row("sketch#a", "sketchNo" to "S-4"), 0))
        assertEquals("P-7", dwChooserRowLabel(row("prototype#a", "prototypeCode" to "P-7"), 0))
        assertEquals("Untitled 3", dwChooserRowLabel(row("sketch#a"), 2))
        assertEquals("Untitled 1", dwChooserRowLabel(row("sketch#a", "name" to "   "), 0))
    }

    @Test
    fun `a JSON null is not a name`() {
        val nulled = DraftRow(id = "sketch#a", values = mapOf("name" to JsonNull))
        assertEquals("Untitled 1", dwChooserRowLabel(nulled, 0))
    }

    @Test
    fun `a new row is filed under its entity and carries a client key the wire can split out`() {
        val fresh = dwChooserNewRow(DW_CHOOSER_SKETCH_ENTITY, "uuid-1")
        assertEquals(dwRowId("sketch", "uuid-1"), fresh.id)
        assertEquals("sketch", fresh.entityKey())
        assertEquals("uuid-1", fresh.id.substringAfter('#'))
        assertTrue("a minted row must claim nothing", fresh.values.isEmpty())
        assertNull(fresh.values["_entryId"])
        assertNull(fresh.values["_clientKey"])
    }

    @Test
    fun `appending a row leaves every other fact about the stage exactly as it was`() {
        val spec = StageDto(number = 11, key = "SKETCH_DEVELOPMENT", title = "Sketch development")
        val existing = StageDraft(
            stageId = "SKETCH_DEVELOPMENT",
            title = "Sketch development",
            order = 11,
            values = mapOf("summary" to JsonPrimitive("two weeks of drawing")),
            custom = mapOf("ownQuestion" to JsonPrimitive("answered")),
            customSeen = true,
            rows = listOf(row("sketch#one"), row("prototypeStageLog#log")),
            mediaIds = listOf("m1"),
            stageSeen = true,
            emptiedEntities = listOf("sketchReview"),
            deletedRowKeys = listOf("sketch#gone"),
            notes = "a note",
        )
        val next = dwChooserAppendRow(spec, existing, dwChooserNewRow("sketch", "uuid-2"))

        assertEquals(existing.values, next.values)
        assertEquals(existing.custom, next.custom)
        assertTrue("authority earned by reading must not be dropped by an append", next.stageSeen)
        assertTrue(next.customSeen)
        assertEquals(existing.mediaIds, next.mediaIds)
        assertEquals("a deletion record is the only place a deletion exists", listOf("sketchReview"), next.emptiedEntities)
        assertEquals(listOf("sketch#gone"), next.deletedRowKeys)
        assertEquals("a note", next.notes)
        assertEquals(listOf("sketch#one", dwRowId("sketch", "uuid-2")), next.rowsFor("sketch").map { it.id })
        assertEquals(1, next.rowsFor("prototypeStageLog").size)
    }

    @Test
    fun `a stage seeded from nothing claims no reading of the repository`() {
        val spec = StageDto(
            number = 13,
            key = "PROTOTYPE_DEVELOPMENT",
            title = "Prototype development",
            entities = listOf(
                EntityDto(
                    key = "prototypePlan",
                    cardinality = "SINGLETON",
                    fields = listOf(
                        FieldDto(key = "brief", label = "Brief", required = true),
                        FieldDto(key = "aside", label = "Aside", required = false),
                    ),
                ),
            ),
        )
        val seeded = dwChooserAppendRow(spec, null, dwChooserNewRow("prototype", "uuid-3"))
        assertFalse("a seeded stage must never claim to have read the server's copy", seeded.stageSeen)
        assertEquals("PROTOTYPE_DEVELOPMENT", seeded.stageId)
        assertEquals(13, seeded.order)
        assertEquals("Prototype development", seeded.title)
        assertEquals(listOf("brief"), seeded.requiredKeys)
        assertEquals(1, seeded.rowsFor("prototype").size)
    }

    @Test
    fun `a media value takes the shape the registry declares, not the shape of the count`() {
        val rows = listOf(row("sketch#a"), row("sketch#b"))
        val asList = dwChooserWriteMedia(rows, "sketch#a", "turntablePhotos", listOf("m1"), asList = true)
        assertEquals(JsonArray(listOf(JsonPrimitive("m1"))), asList.first().values["turntablePhotos"])

        val asOne = dwChooserWriteMedia(rows, "sketch#a", "image", listOf("m1"), asList = false)
        assertEquals(JsonPrimitive("m1"), asOne.first().values["image"])
    }

    @Test
    fun `detaching every file removes the key instead of storing an empty one`() {
        val held = listOf(row("sketch#a", "image" to "m1"))
        val cleared = dwChooserWriteMedia(held, "sketch#a", "image", emptyList(), asList = false)
        assertFalse(cleared.first().values.containsKey("image"))
    }

    @Test
    fun `writing media touches only the named row`() {
        val rows = listOf(row("sketch#a", "image" to "old"), row("sketch#b", "image" to "keep"))
        val next = dwChooserWriteMedia(rows, "sketch#a", "image", listOf("new"), asList = false)
        assertEquals(JsonPrimitive("new"), next[0].values["image"])
        assertEquals(JsonPrimitive("keep"), next[1].values["image"])
    }

    @Test
    fun `writing media to a row that has gone changes nothing`() {
        val rows = listOf(row("sketch#a", "image" to "keep"))
        assertEquals(rows, dwChooserWriteMedia(rows, "sketch#gone", "image", listOf("m1"), asList = false))
    }

    @Test
    fun `replacing one entity's rows leaves the stage's other collections alone`() {
        val stage = StageDraft(
            stageId = "PROTOTYPE_DEVELOPMENT",
            rows = listOf(
                row("prototype#a"),
                row("prototypeStageLog#l1"),
                row("prototypeMaterial#m1"),
            ),
        )
        val next = dwChooserReplaceRows(stage, "prototype", listOf(row("prototype#a", "name" to "Stool")))
        assertEquals(1, next.rowsFor("prototypeStageLog").size)
        assertEquals(1, next.rowsFor("prototypeMaterial").size)
        assertEquals("Stool", dwChooserRowLabel(next.rowsFor("prototype").single(), 0))
    }

    @Test
    fun `a held media value is read from either shape and from nothing else`() {
        assertEquals(listOf("m1"), dwChooserHeldMedia(row("sketch#a", "image" to "m1"), "image"))
        val list = DraftRow(
            id = "prototype#a",
            values = mapOf(
                "turntablePhotos" to JsonArray(listOf(JsonPrimitive("m1"), JsonPrimitive("m2"))),
            ),
        )
        assertEquals(listOf("m1", "m2"), dwChooserHeldMedia(list, "turntablePhotos"))
        assertEquals(emptyList<String>(), dwChooserHeldMedia(row("sketch#a"), "image"))
        assertEquals(emptyList<String>(), dwChooserHeldMedia(null, "image"))
        val nulled = DraftRow(id = "sketch#a", values = mapOf("image" to JsonNull))
        assertEquals("a JSON null is not a media reference", emptyList<String>(), dwChooserHeldMedia(nulled, "image"))
    }

    @Test
    fun `the media fields are found by key, in the order asked for`() {
        val entity = EntityDto(
            key = "prototype",
            cardinality = "COLLECTION",
            fields = listOf(
                FieldDto(key = "name", label = "Name"),
                FieldDto(key = "modelFile", label = "3D model", type = "FILE"),
                FieldDto(key = "turntablePhotos", label = "360° capture", type = "IMAGE_LIST"),
            ),
        )
        assertEquals(
            listOf("360° capture", "3D model"),
            dwChooserMediaFields(entity, DW_CHOOSER_PROTOTYPE_FIELDS).map { it.label },
        )
        assertEquals(emptyList<FieldDto>(), dwChooserMediaFields(entity, DW_CHOOSER_SKETCH_FIELDS))
        assertEquals(emptyList<FieldDto>(), dwChooserMediaFields(null, DW_CHOOSER_PROTOTYPE_FIELDS))
    }

    private fun workshop(id: String) = DesignWorkshopDto(id = id, title = "Workshop $id")

    @Test
    fun `the chosen workshop survives a re-read and a vanished one does not`() {
        val rows = listOf(workshop("a"), workshop("b"), workshop("c"))
        assertEquals("b", dwChooserDefaultWorkshop(rows, serverDefaultId = "c", chosen = "b"))
        assertEquals("c", dwChooserDefaultWorkshop(rows, serverDefaultId = "c", chosen = "gone"))
    }

    @Test
    fun `the default is the server's answer, then the newest workshop, then nothing`() {
        val rows = listOf(workshop("a"), workshop("b"))
        assertEquals("b", dwChooserDefaultWorkshop(rows, serverDefaultId = "b", chosen = ""))
        assertEquals("a", dwChooserDefaultWorkshop(rows, serverDefaultId = null, chosen = ""))
        assertEquals("a", dwChooserDefaultWorkshop(rows, serverDefaultId = "z", chosen = ""))
        assertEquals("", dwChooserDefaultWorkshop(emptyList(), serverDefaultId = "b", chosen = "b"))
    }

    @Test
    fun `the chosen row survives a re-read and a vanished one falls back to the first`() {
        val rows = listOf(row("sketch#a"), row("sketch#b"))
        assertEquals("sketch#b", dwChooserKeepSelection("sketch#b", rows))
        assertEquals("sketch#a", dwChooserKeepSelection("sketch#gone", rows))
        assertEquals("sketch#a", dwChooserKeepSelection("", rows))
        assertEquals("", dwChooserKeepSelection("sketch#a", emptyList()))
    }

    @Test
    fun `only a landed push claims the repository has it`() {
        val sent = dwChooserSaveNote(StagePush.Sent(StageSaveResultDto()), "This sketch")
        assertTrue(sent.contains("saved on this phone and online"))
        assertTrue(dwChooserSaveNote(StagePush.AlreadySent, "This sketch").contains("already online"))

        val open = listOf(
            StagePush.HeldBack(files = 2),
            StagePush.NoRemoteYet,
            StagePush.NothingToSend,
            StagePush.NotSent,
            null,
        )
        open.forEach { push ->
            val note = dwChooserSaveNote(push, "This prototype")
            assertTrue("$push must say the work is safe on the phone", note.contains("on this phone"))
            assertFalse(
                "$push must not claim the repository has it",
                note.contains("and online") || note.contains("already online"),
            )
            assertTrue("$push must name the thing it is about", note.startsWith("This prototype"))
        }
        assertEquals(dwChooserSaveNote(StagePush.NotSent, "x"), dwChooserSaveNote(null, "x"))
    }

    @Test
    fun `the interim sentence claims the phone and does not judge the request`() {
        val interim = dwChooserSendingNote("This sketch")
        assertTrue(interim.contains("saved on this phone"))
        assertFalse(
            "the push has not been attempted yet and must not be reported as having failed",
            interim.contains("did not complete"),
        )
        assertFalse(interim.contains("online"))
        assertTrue("the ellipsis is the only promise it makes about the repository", interim.endsWith("…"))
        listOf(
            StagePush.Sent(StageSaveResultDto()),
            StagePush.AlreadySent,
            StagePush.HeldBack(files = 1),
            StagePush.NoRemoteYet,
            StagePush.NothingToSend,
            StagePush.NotSent,
        ).forEach { push ->
            assertFalse(
                "the interim sentence is the same string as $push's verdict",
                dwChooserSaveNote(push, "This sketch") == interim,
            )
        }
    }
}
