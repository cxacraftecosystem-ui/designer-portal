package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DW_DEFAULT_MAX_ITEMS
import com.designprototype.workshop.data.DwPhotoIntake
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.dwEffectiveMaxItems
import com.designprototype.workshop.data.liveFields
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DwMediaCapCeilingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val schema: SchemaResponse by lazy {
        val asset = File("src/main/assets/design-workshop-schema.json")
        assertTrue(
            "the bundled registry is missing — it is what the handset renders from on first launch",
            asset.exists()
        )
        json.decodeFromString(SchemaResponse.serializer(), asset.readText(Charsets.UTF_8))
    }

    private val galleries: List<Pair<String, Int>>
        get() = schema.stages.flatMap { stage ->
            stage.entities.flatMap { entity ->
                entity.liveFields
                    .filter { it.type == "IMAGE_LIST" }
                    .map { "${entity.key}.${it.key}" to it.maxItems }
            }
        }

    private fun refs(count: Int): JsonElement =
        JsonArray((1..count).map { JsonPrimitive("m$it") })

    @Test
    fun `an absent maxItems is the server's default ceiling and never the absence of one`() {
        assertEquals(200, DW_DEFAULT_MAX_ITEMS)
        assertEquals(DW_DEFAULT_MAX_ITEMS, dwEffectiveMaxItems(0))
        assertEquals(DW_DEFAULT_MAX_ITEMS, dwEffectiveMaxItems(-3))
        assertEquals(20, dwEffectiveMaxItems(20))
        assertEquals(25, dwEffectiveMaxItems(25))
        assertEquals(500, dwEffectiveMaxItems(500))
    }

    @Test
    fun `most of the registry's galleries declare no ceiling, which is why the default is load-bearing`() {
        val all = galleries
        assertTrue("the registry declares no IMAGE_LIST at all — this asset cannot be right", all.isNotEmpty())
        val undeclared = all.filter { (_, declared) -> declared <= 0 }
        assertTrue(
            "every gallery now declares a cap, so this file's premise has changed and its reasoning " +
                "needs rereading rather than its assertion relaxing",
            undeclared.isNotEmpty()
        )
        all.forEach { (name, declared) ->
            assertTrue("$name would be enforced at nothing", dwEffectiveMaxItems(declared) > 0)
        }
    }

    @Test
    fun `the trim notice is spoken either way and names a ceiling only where one was declared`() {
        val declared = dwCapNotice(label = "Motif photographs", declaredCap = 20, dropped = 4, chosen = 9)
        assertTrue(
            "a declared cap came off the registry, so it may be stated",
            declared.contains("Motif photographs holds at most 20 files")
        )
        assertTrue("what did not land has to be counted", declared.contains("4 of the 9"))
        assertTrue("plural", declared.contains("were not attached"))
        assertTrue("a refusal with no remedy in it is a dead end", declared.contains("Remove something first"))

        val defaulted = dwCapNotice(label = "Step photographs", declaredCap = null, dropped = 4, chosen = 9)
        assertTrue("the field is still named — the designer has several open", defaulted.contains("Step photographs is full."))
        assertFalse(
            "the notice may not print a ceiling this client did not read",
            defaulted.contains(DW_DEFAULT_MAX_ITEMS.toString())
        )
        assertFalse("nor any other spelling of it", defaulted.contains("at most"))
        assertTrue(
            "and it may not fall silent either — this sentence is the only record of the refusal",
            defaulted.contains("4 of the 9 you chose were not attached")
        )

        val one = dwCapNotice(label = "Sketches", declaredCap = null, dropped = 1, chosen = 3)
        assertTrue("singular", one.contains("1 of the 3 you chose was not attached"))
        assertTrue("and singular in the remedy too", one.contains("if you need it instead"))
        assertTrue(
            "a cap of one is still a file, singular",
            dwCapNotice(label = "Cover", declaredCap = 1, dropped = 1, chosen = 2)
                .contains("Cover holds at most 1 file.")
        )
    }

    @Test
    fun `the photo intake append stops at the ceiling, declared or defaulted`() {
        val full = refs(DW_DEFAULT_MAX_ITEMS)
        assertFalse(DwPhotoIntake.mediaRefFits(full, "m201", multiple = true))
        assertEquals(
            DW_DEFAULT_MAX_ITEMS,
            (DwPhotoIntake.appendMediaRef(full, "m201", multiple = true) as JsonArray).size
        )
        val nearlyFull = refs(DW_DEFAULT_MAX_ITEMS - 1)
        assertTrue(DwPhotoIntake.mediaRefFits(nearlyFull, "m200", multiple = true))
        assertEquals(
            DW_DEFAULT_MAX_ITEMS,
            (DwPhotoIntake.appendMediaRef(nearlyFull, "m200", multiple = true) as JsonArray).size
        )

        val twenty = refs(20)
        assertFalse(DwPhotoIntake.mediaRefFits(twenty, "m21", multiple = true, maxItems = 20))
        assertEquals(
            twenty,
            DwPhotoIntake.appendMediaRef(twenty, "m21", multiple = true, maxItems = 20)
        )
        assertEquals(
            21,
            (DwPhotoIntake.appendMediaRef(twenty, "m21", multiple = true) as JsonArray).size
        )
    }

    @Test
    fun `a re-confirmed photograph and a single-valued field are never refused by the ceiling`() {
        val twenty = refs(20)
        assertTrue(DwPhotoIntake.mediaRefFits(twenty, "m7", multiple = true, maxItems = 20))
        assertEquals(
            twenty,
            DwPhotoIntake.appendMediaRef(twenty, "m7", multiple = true, maxItems = 20)
        )

        assertTrue(DwPhotoIntake.mediaRefFits(JsonPrimitive("m1"), "m2", multiple = false, maxItems = 1))
        assertEquals(
            JsonPrimitive("m2"),
            DwPhotoIntake.appendMediaRef(JsonPrimitive("m1"), "m2", multiple = false, maxItems = 1)
        )
    }
}
