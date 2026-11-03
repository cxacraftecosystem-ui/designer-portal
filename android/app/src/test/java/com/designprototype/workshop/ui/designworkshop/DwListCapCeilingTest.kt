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

class DwListCapCeilingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val schema: SchemaResponse by lazy {
        val asset = File("src/main/assets/design-workshop-schema.json")
        assertTrue(
            "the bundled registry is missing — it is what the handset renders from on first launch",
            asset.exists()
        )
        json.decodeFromString(SchemaResponse.serializer(), asset.readText(Charsets.UTF_8))
    }

    private val lists: List<Pair<String, Int>>
        get() = schema.stages.flatMap { stage ->
            stage.entities.flatMap { entity ->
                entity.liveFields
                    .filter { it.type == "TAGS" || it.type == "MULTI_ENUM" }
                    .map { "${entity.key}.${it.key}" to it.maxItems }
            }
        }

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
        all.forEach { (name, declared) ->
            assertTrue("$name would be enforced at nothing", dwEffectiveMaxItems(declared) > 0)
        }
    }

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
        assertTrue(defaulted.startsWith("Materials"))
    }

    @Test
    fun `growth stops at the ceiling and keeps the registry's order`() {
        val held = listOf("cotton", "silk")
        val kept = dwCapListGrowth(held, listOf("cotton", "silk", "jute"), ceiling = 2)
        assertEquals(held, kept)

        assertEquals(
            listOf("cotton", "jute"),
            dwCapListGrowth(listOf("cotton"), listOf("cotton", "jute", "wool"), ceiling = 2)
        )
        assertEquals(
            listOf("cotton", "jute"),
            dwCapListGrowth(listOf("cotton"), listOf("cotton", "jute"), ceiling = 2)
        )
    }

    @Test
    fun `a list already over its ceiling may still be shortened and rearranged`() {
        val held = listOf("a", "b", "c", "d", "e")
        assertEquals(
            listOf("a", "b", "c", "d"),
            dwCapListGrowth(held, listOf("a", "b", "c", "d"), ceiling = 3)
        )
        assertEquals(
            listOf("a", "b", "c", "d", "f"),
            dwCapListGrowth(held, listOf("a", "b", "c", "d", "f"), ceiling = 3)
        )
        assertEquals(held, dwCapListGrowth(held, held + "g", ceiling = 3))
    }

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

    @Test
    fun `a long refusal keeps whole names and counts what it left out`() {
        val many = (1..40).map { "IMG_%04d.JPG".format(it) }
        val notice = dwIntakeFullNotice(destination("13. Prototype Development — Photographs", maxItems = 20), many)

        assertTrue("the count is still stated in full", notice.contains("so 40 were not attached"))
        assertTrue("the first names are printed", notice.contains("IMG_0001.JPG, IMG_0002.JPG"))
        assertTrue(
            "what did not fit is counted rather than cut",
            Regex(""" and \d+ more\.""").containsMatchIn(notice)
        )

        val listed = notice.substringAfter("not attached: ").substringBefore(" and ")
        listed.split(", ").forEach { name ->
            assertTrue("a filename was cut in half: '$name'", name in many)
        }
        assertFalse("the list was not shortened at all", notice.contains("IMG_0040.JPG"))
        assertTrue("a refusal with no remedy in it is a dead end", notice.contains("remove something from that field first"))
    }

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
        assertEquals(DW_DEFAULT_MAX_ITEMS, dwEffectiveMaxItems(out[1].maxItems))
    }
}
