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

/**
 * AN ABSENT `maxItems` IS THE SERVER'S CEILING, NOT THE ABSENCE OF ONE — and the client may still
 * not print it.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE DEFECT THESE PIN
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * docs/DESIGN_WORKSHOP.md:229-232 states the contract in as many words: a client "must neither read
 * the absence as no limit nor print a number it did not read". Until 2026-08-26 both clients did the
 * first half. `DwMediaCaptureCard` computed its ceiling as `if (multiple && field.maxItems > 0)
 * field.maxItems else null` and then trimmed only when that was non-null, so every gallery the
 * registry says nothing about was capped at nothing at all, and `DwPhotoIntake.appendMediaRef`
 * — the third write path into a media field — had no ceiling of any kind, declared or otherwise.
 *
 * WHAT THAT COST WAS NOT THE SURPLUS PHOTOGRAPHS. `coerce_value` REFUSES an over-long array rather
 * than trimming it (backend/app/services/stage_schema.py:1822, `limit = spec.max_items or
 * DEFAULT_MAX_ITEMS`) and `save_stage` restores the rejected key from `previous`, so a gallery grown
 * past the ceiling syncs as a field that did not save — with every byte already copied into the
 * workshop's media directory and nothing on screen saying which ones to drop.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * AND WHY THE FIX HAD TO CARRY THE NOTICE WITH IT
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The obvious repair — cap at 200, and go on gating the "up to N" sentence on a declared cap — trades
 * one half of the contract for the other: it turns a loud trim into a SILENT drop of the
 * two-hundred-and-first photograph, which is the one outcome `adopt`'s own comment refuses ("the
 * honest act is to take what fits and SAY what did not"). So the third case below is as much the
 * point as the first two: [dwCapNotice] must fire when the DEFAULT ceiling bites, and must do it
 * without naming a number this client never read.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS FILE DOES NOT CLAIM
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Nothing here composes anything: a JVM test cannot see that the hint was drawn under the buttons
 * only for a declared cap, and it cannot see that the notice reached the assertive live region. What
 * it CAN see is every piece that was lifted out of the lambdas for exactly that reason — the ceiling
 * arithmetic, the sentence, and the append the intake writes through — plus the registry property
 * that makes the default load-bearing in the first place.
 */
class DwMediaCapCeilingTest {

    /** Matches the app's own decoder: the registry carries keys the DTOs here do not model. */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The SHIPPED asset and not the live server, for the reason `DwBulletListFieldTest` gives:
     * `design-workshop-schema.json` is what a handset renders from before it has ever had a
     * connection, so it is the copy that decides what a courtyard is held to.
     */
    private val schema: SchemaResponse by lazy {
        val asset = File("src/main/assets/design-workshop-schema.json")
        assertTrue(
            "the bundled registry is missing — it is what the handset renders from on first launch",
            asset.exists()
        )
        json.decodeFromString(SchemaResponse.serializer(), asset.readText(Charsets.UTF_8))
    }

    /** Every live IMAGE_LIST in the registry, as `entity.field` to its declared `maxItems`. */
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

    /**
     * The whole of the first half of the contract, in one function.
     *
     * 200 is asserted as a literal as well as through the constant, because the constant agreeing
     * with itself proves nothing: the number that matters is `DEFAULT_MAX_ITEMS` in
     * backend/app/services/stage_schema.py:1764, and a silent edit on this side would otherwise pass.
     * A NEGATIVE is folded in with zero rather than trusted — an `Int` field decoded from a payload
     * this build has never seen can hold anything, and `uris.take(-3)` throws.
     */
    @Test
    fun `an absent maxItems is the server's default ceiling and never the absence of one`() {
        assertEquals(200, DW_DEFAULT_MAX_ITEMS)
        assertEquals(DW_DEFAULT_MAX_ITEMS, dwEffectiveMaxItems(0))
        assertEquals(DW_DEFAULT_MAX_ITEMS, dwEffectiveMaxItems(-3))
        // A declared ceiling wins, in both directions: the two motif galleries cap DOWN — at 25
        // since the owner's instruction of 2026-08-27, and at 20 before it, which is why the number
        // below is a literal argument rather than a read of the registry — and a field that one day
        // declares more than the default must get what it declared.
        assertEquals(20, dwEffectiveMaxItems(20))
        assertEquals(25, dwEffectiveMaxItems(25))
        assertEquals(500, dwEffectiveMaxItems(500))
    }

    /**
     * The registry property that makes all of this load-bearing rather than theoretical.
     *
     * Asserted as a PROPERTY and not as a count. The report that opened this defect counted twenty
     * live IMAGE_LIST fields with two of them capped, and an equality assertion on either number
     * would turn the next correctly-declared gallery into a red Android build. What may never change
     * without this file changing with it is the shape: most galleries declare nothing, so the client
     * that reads the absence as "no limit" is the client that enforces nothing on most of the
     * registry.
     */
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
        // The ones that DO declare are the ones entitled to print their number; the rest are held to
        // the server's default and say nothing about it. Either way NONE of them is held to nothing,
        // which is the property that was false before 2026-08-26.
        all.forEach { (name, declared) ->
            assertTrue("$name would be enforced at nothing", dwEffectiveMaxItems(declared) > 0)
        }
    }

    /**
     * THE SENTENCE THAT MAKES THE DEFAULT CEILING HONEST — spoken either way, and naming a number only
     * where the registry gave it one.
     *
     * THE FIRST HALF IS THE ASSERTION A LATER "HELPFUL" EDIT WILL WANT TO REMOVE. Putting "200" into
     * the undeclared sentence is precisely what docs/DESIGN_WORKSHOP.md:231-232 forbids: on every gallery
     * that declares no cap this client never read that number — the server did, and may change it
     * without a `registry_version()` bump.
     *
     * THE SECOND HALF IS THE ASSERTION THE OBVIOUS FIX WOULD HAVE BROKEN. Gating the whole notice on a
     * declared cap while trimming at two hundred anyway is a silent drop, so the undeclared sentence
     * must still count what did not land. Both halves have to hold at once; either one alone is a
     * defect the report named.
     */
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

    /**
     * THE THIRD WRITE PATH, which had no ceiling of any kind until 2026-08-26.
     *
     * `PhotoIntakeScreen`'s confirm walk writes through [DwPhotoIntake.appendMediaRef], not through
     * the capture card, so a two-hundred-photograph camera dump reached a gallery by a door neither
     * of the other two clients' caps stood in.
     */
    @Test
    fun `the photo intake append stops at the ceiling, declared or defaulted`() {
        // Defaulted: the field declares nothing, so the ceiling is the server's.
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

        // Declared: the motif galleries' twenty, which the intake overran even before the default
        // mattered — this is the case a designer actually reaches.
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

    /**
     * The two cases the ceiling must NOT refuse, because neither is growth.
     *
     * A reference the field already holds is the idempotent re-confirm this function has always
     * answered with a no-op, and answering it with a refusal instead would make a full gallery report
     * a photograph as dropped that is sitting in it. A single-valued IMAGE replaces rather than
     * appends, so it has no list to overrun at all.
     */
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
