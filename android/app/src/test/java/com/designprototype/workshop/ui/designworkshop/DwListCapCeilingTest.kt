package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DW_DEFAULT_MAX_ITEMS
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.dwEffectiveMaxItems
import com.designprototype.workshop.data.liveFields
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `maxItems` IS NOT A MEDIA KEY — the two list controls and the photo intake are held to it too.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE DEFECTS THESE PIN
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * [DwMediaCapCeilingTest] closed the ceiling on the capture card and on
 * `DwPhotoIntake.appendMediaRef`. It left two holes, and both were doors into the same failure:
 *
 *  · THE INTAKE'S CALL SITES PASSED NO CAP. `appendMediaRef` had gained a `maxItems` parameter and a
 *    `mediaRefFits` companion, but `PhotoIntakeScreen`'s confirm walk passed neither — so the intake
 *    enforced the DEFAULT where the registry declared twenty, and a photograph the append silently
 *    declined was still counted in the receipt's "N attached" and still had its id written into
 *    `StageDraft.mediaIds` with no field referencing it.
 *  · THE TAGS AND MULTI_ENUM CONTROLS READ NO CAP AT ALL. docs/DESIGN_WORKSHOP.md names the three
 *    types `maxItems` governs in one breath — "IMAGE_LIST, TAGS, MULTI_ENUM" — and `coerce_value`
 *    applies `spec.max_items or DEFAULT_MAX_ITEMS` to whichever of them a stage carries
 *    (backend/app/services/stage_schema.py:1822, headed "A REFUSAL, NOT A TRUNCATION"), with
 *    `save_stage` restoring the rejected key from `previous`.
 *
 * WHAT EITHER ONE COSTS IS THE WHOLE FIELD'S WRITE, not the surplus entries — refused at sync, hours
 * after the courtyard, with the stage screen still showing what the designer typed.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * AND BOTH HALVES OF THE RULE, EVERY TIME
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * docs/DESIGN_WORKSHOP.md:229-232: a client "must neither read the absence as no limit nor print a
 * number it did not read". Enforcing 200 while going quiet about it trades one half for the other and
 * turns a loud refusal into a silent drop; printing "up to 200" on a field the registry said nothing
 * about states a cap this client never read and the server may change without a `registry_version()`
 * bump. So every sentence below is asserted twice: that it fires, and that it names a number only
 * where one was declared.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS FILE DOES NOT CLAIM
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Nothing here composes. It cannot see that the hint was drawn only for a declared cap, that the
 * notice reached the assertive live region, or that the tag the designer typed survived a refusal in
 * the box. What it CAN see is every rule that was lifted out of a lambda for exactly that reason —
 * the ceiling arithmetic, the two sentences, the destination the intake writes through — plus the
 * registry property that makes the default load-bearing rather than theoretical.
 */
class DwListCapCeilingTest {

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

    /** Every live TAGS/MULTI_ENUM in the registry, as `entity.field` to its declared `maxItems`. */
    private val lists: List<Pair<String, Int>>
        get() = schema.stages.flatMap { stage ->
            stage.entities.flatMap { entity ->
                entity.liveFields
                    .filter { it.type == "TAGS" || it.type == "MULTI_ENUM" }
                    .map { "${entity.key}.${it.key}" to it.maxItems }
            }
        }

    // -----------------------------------------------------------------------
    // The registry property that makes the default load-bearing
    // -----------------------------------------------------------------------

    /**
     * Asserted as a PROPERTY and not as a count, exactly as [DwMediaCapCeilingTest] argues for the
     * galleries: an equality on "seventeen live TAGS and MULTI_ENUM fields" would turn the next
     * question added to a survey into a red Android build, and `FieldRenderer.kt`'s own header bans
     * writing such a figure into a comment because two measured ones went stale within days.
     *
     * What may never change without this file changing with it is the SHAPE: these fields declare no
     * cap, so a client that reads the absence as "no limit" is a client enforcing nothing at all on
     * every one of them — which is what both controls did until 2026-08-26.
     */
    @Test
    fun `the registry's tag and multi-enum fields declare no ceiling, which is why the default is load-bearing`() {
        val all = lists
        assertTrue(
            "the registry declares no TAGS or MULTI_ENUM at all — this asset cannot be right",
            all.isNotEmpty()
        )
        val undeclared = all.filter { (_, declared) -> declared <= 0 }
        assertTrue(
            "every list field now declares a cap, so this file's premise has changed and its " +
                "reasoning needs rereading rather than its assertion relaxing",
            undeclared.isNotEmpty()
        )
        // Declared or not, none of them may be held to nothing. That is the property that was false
        // on this surface before the ceiling was applied here.
        all.forEach { (name, declared) ->
            assertTrue("$name would be enforced at nothing", dwEffectiveMaxItems(declared) > 0)
        }
    }

    // -----------------------------------------------------------------------
    // The clause that decides whether a number may be printed
    // -----------------------------------------------------------------------

    /**
     * THE FIRST HALF IS THE ASSERTION A LATER "HELPFUL" EDIT WILL WANT TO REMOVE. Putting the default
     * into the undeclared clause is precisely what docs/DESIGN_WORKSHOP.md:231-232 forbids — this client
     * never read that number off the wire, and a stated cap that is not the enforced cap "is worse
     * than no sentence at all".
     */
    @Test
    fun `the list ceiling clause names a number only where the registry declared one`() {
        assertEquals("Materials holds at most 3 entries", dwListCeilingClause("Materials", 3))
        assertEquals("Cover motif holds at most 1 entry", dwListCeilingClause("Cover motif", 1))

        val defaulted = dwListCeilingClause("Materials", null)
        assertEquals("Materials is full", defaulted)
        assertFalse(
            "the clause may not print a ceiling this client did not read",
            defaulted.contains(DW_DEFAULT_MAX_ITEMS.toString())
        )
        assertFalse("nor any other spelling of it", defaulted.contains("at most"))
        // The field is still NAMED either way: a designer has several open and "is full" alone does
        // not say which of them refused.
        assertTrue(defaulted.startsWith("Materials"))
    }

    // -----------------------------------------------------------------------
    // What a list control may commit
    // -----------------------------------------------------------------------

    @Test
    fun `growth stops at the ceiling and keeps the registry's order`() {
        val held = listOf("cotton", "silk")
        val kept = dwCapListGrowth(held, listOf("cotton", "silk", "jute"), ceiling = 2)
        assertEquals(held, kept)

        // Room for one of the two newly ticked: the first in the order it was handed, which both
        // callers have already put in REGISTRY order rather than tick order.
        assertEquals(
            listOf("cotton", "jute"),
            dwCapListGrowth(listOf("cotton"), listOf("cotton", "jute", "wool"), ceiling = 2)
        )
        // A selection that fits is passed through untouched, ceiling or no ceiling.
        assertEquals(
            listOf("cotton", "jute"),
            dwCapListGrowth(listOf("cotton"), listOf("cotton", "jute"), ceiling = 2)
        )
    }

    /**
     * THE TWO CHANGES THAT ARE NOT GROWTH, and refusing either would trap a designer rather than
     * protect one.
     *
     * A cap is deliberately not part of `registry_version()`, so a field may be holding five entries
     * on the day its declared ceiling becomes three — those values were valid when they were written.
     * Trimming them here would be this client deleting fieldwork nobody asked it to delete; refusing
     * the untick that brings the field back under the ceiling would leave the designer with no way
     * out at all. So a change that does not lengthen the list passes through whatever its size.
     */
    @Test
    fun `a list already over its ceiling may still be shortened and rearranged`() {
        val held = listOf("a", "b", "c", "d", "e")
        assertEquals(
            listOf("a", "b", "c", "d"),
            dwCapListGrowth(held, listOf("a", "b", "c", "d"), ceiling = 3)
        )
        // Swapped, not grown: still five, so still the designer's to make and not this client's to
        // refuse. The server goes on refusing that one field at save until it is brought under the
        // cap, exactly as it did before any of this existed — the overflow was not made here.
        assertEquals(
            listOf("a", "b", "c", "d", "f"),
            dwCapListGrowth(held, listOf("a", "b", "c", "d", "f"), ceiling = 3)
        )
        // And every entry already held survives a change that IS growth: what is dropped is only
        // ever the new arrivals that did not fit.
        assertEquals(held, dwCapListGrowth(held, held + "g", ceiling = 3))
    }

    // -----------------------------------------------------------------------
    // The intake's receipt
    // -----------------------------------------------------------------------

    private fun destination(label: String, maxItems: Int) = DwIntakeDestination(
        key = "PROTOTYPE_DEVELOPMENT|prototypeStageLog|row-a|logPhotos",
        stageKey = "PROTOTYPE_DEVELOPMENT",
        stageNumber = 13,
        stageTitle = "Prototype Development",
        entityKey = "prototypeStageLog",
        rowKey = "row-a",
        fieldKey = "logPhotos",
        label = label,
        multiple = true,
        maxItems = maxItems,
    )

    /**
     * The sentence the confirm walk owes a designer whose camera dump did not all fit.
     *
     * IT NAMES THE FILES, where the capture card's [dwCapNotice] can only count them. By the time
     * this fires the bytes are in the workshop's media directory and the rows are still on screen, so
     * "which twenty" is a question that has an answer and must be given one — a two-hundred-row
     * confirmation whose tail vanishes is the silent drop at its very worst.
     */
    @Test
    fun `the intake says which photographs a full gallery refused, and its ceiling only where declared`() {
        val declared = dwIntakeFullNotice(
            destination("13. Prototype Development — Stage logs “Warping” — Photographs", maxItems = 20),
            listOf("IMG_0007.JPG", "IMG_0008.JPG"),
        )
        assertTrue(
            "a declared cap came off the registry, so it may be stated",
            declared.contains("already holds the 20 photographs it may")
        )
        assertTrue("the field is named in full — one confirmation writes into many", declared.contains("Stage logs “Warping”"))
        assertTrue("plural", declared.contains("so 2 were not attached"))
        assertTrue("and which two", declared.contains("IMG_0007.JPG, IMG_0008.JPG"))
        assertTrue("the bytes are on the phone, unlike a trimmed import", declared.contains("still on this device"))
        assertTrue("a refusal with no remedy in it is a dead end", declared.contains("remove something from that field first"))

        val defaulted = dwIntakeFullNotice(destination("13. Prototype Development — Photographs", maxItems = 0), listOf("IMG_0009.JPG"))
        assertTrue("singular", defaulted.contains("so 1 was not attached"))
        assertTrue("and singular in what follows", defaulted.contains("It is still on this device"))
        assertFalse(
            "the receipt may not print a ceiling this client did not read",
            defaulted.contains(DW_DEFAULT_MAX_ITEMS.toString())
        )
        assertTrue(
            "and it may not fall silent either — this sentence is the only record of the refusal",
            defaulted.contains("is already full")
        )
    }

    /**
     * A FORTY-FILE REFUSAL IS SHORTENED BY WHOLE NAMES AND SAYS SO.
     *
     * The sentence is capped in length because a receipt is not a manifest, but the cap used to be
     * `.take(200)` over the JOINED string — so it ended mid-filename, unmarked, and sent the designer
     * looking for "IMG_00" in a gallery. What is elided has to be counted out loud, and what is
     * printed has to be a name that exists.
     */
    @Test
    fun `a long refusal keeps whole names and counts what it left out`() {
        val many = (1..40).map { "IMG_%04d.JPG".format(it) }
        val notice = dwIntakeFullNotice(destination("13. Prototype Development — Photographs", maxItems = 20), many)

        assertTrue("the count is still stated in full", notice.contains("so 40 were not attached"))
        assertTrue("the first names are printed", notice.contains("IMG_0001.JPG, IMG_0002.JPG"))
        // A RAW STRING, because `\d` and `\.` are not legal escapes in an ordinary Kotlin literal —
        // which is the illegal-escape compile error that stopped every Android build once already.
        assertTrue(
            "what did not fit is counted rather than cut",
            Regex(""" and \d+ more\.""").containsMatchIn(notice)
        )

        // Every name that IS printed is a whole one — no "IMG_00" left behind by a character cut.
        val listed = notice.substringAfter("not attached: ").substringBefore(" and ")
        listed.split(", ").forEach { name ->
            assertTrue("a filename was cut in half: '$name'", name in many)
        }
        assertFalse("the list was not shortened at all", notice.contains("IMG_0040.JPG"))
        assertTrue("a refusal with no remedy in it is a dead end", notice.contains("remove something from that field first"))
    }

    // -----------------------------------------------------------------------
    // The cap the intake writes with
    // -----------------------------------------------------------------------

    /**
     * The declared cap has to REACH the write, and this is the wire it travels on.
     *
     * `dwConfirmIntake` holds nothing but the destination when it writes — the registry is a stage
     * lookup away and the field is not — so a destination that carries only `multiple` tells the
     * write that a gallery holds many without saying how many, which is how a two-hundred-file dump
     * appended straight past a declared twenty.
     */
    @Test
    fun `a destination carries its field's declared ceiling from the registry`() {
        val registry = SchemaResponse(
            version = "test",
            stages = listOf(
                StageDto(
                    number = 5,
                    key = "CLUSTER_CRAFT_BACKGROUND",
                    title = "Cluster Craft Background",
                    entities = listOf(
                        EntityDto(
                            key = "clusterBackground",
                            name = "DwClusterBackground",
                            cardinality = "SINGLETON",
                            title = "Cluster background",
                            fields = listOf(
                                FieldDto(key = "motifPhotos", label = "Motif photographs", type = "IMAGE_LIST", maxItems = 20),
                                FieldDto(key = "stepPhotos", label = "Step photographs", type = "IMAGE_LIST"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val out = dwIntakeDestinations(registry, emptyMap())
        assertEquals(listOf(20, 0), out.map { it.maxItems })
        // 0 is "the registry declared none" and is carried RAW rather than resolved here, so that the
        // one place the fallback is spelled out stays `dwEffectiveMaxItems` — but what it resolves to
        // is still a ceiling, never the absence of one.
        assertEquals(DW_DEFAULT_MAX_ITEMS, dwEffectiveMaxItems(out[1].maxItems))
    }
}
