package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwCustomSectionsTest {

    private fun field(
        key: String,
        label: String = key,
        type: String = "TEXT",
        required: Boolean = false,
        retired: Boolean = false,
        sortOrder: Int = 0,
    ) = DwCustomFieldDto(
        id = "id-$key", key = key, label = label, type = type,
        required = required, retired = retired, sortOrder = sortOrder,
    )

    private fun cacheOf(vararg sections: DwCustomSectionDto) = DwCustomCache(
        workshopId = "w1", customSchemaVersion = "v1", sections = sections.toList(), complete = true,
    )

    @Test
    fun `never read, none defined and defined are three different answers`() {
        assertEquals(DwCustomCopy.UNKNOWN, dwCustomCopy(null))
        assertEquals(
            DwCustomCopy.NONE_DEFINED,
            dwCustomCopy(DwCustomCache(workshopId = "w1", complete = true)),
        )
        assertEquals(
            DwCustomCopy.DEFINED,
            dwCustomCopy(cacheOf(DwCustomSectionDto(key = "s", stageKey = "A", fields = listOf(field("a"))))),
        )
        assertEquals(
            DwCustomCopy.UNKNOWN,
            dwCustomCopy(DwCustomCache(workshopId = "w1", complete = false)),
        )
    }

    @Test
    fun `a null cache resolves to UNKNOWN and never to NONE_DEFINED`() {
        assertEquals(DwCustomCopy.UNKNOWN, dwCustomCopy(null))
        assertFalse(null.isHeld)
        assertTrue(DwCustomCache(workshopId = "w1", complete = true).isHeld)
    }

    @Test
    fun `only the never-read state produces a warning, and it names what can be done`() {
        assertTrue(dwCustomSectionWarnings(DwCustomCopy.NONE_DEFINED).isEmpty())
        assertTrue(dwCustomSectionWarnings(DwCustomCopy.DEFINED).isEmpty())
        val unread = dwCustomSectionWarnings(DwCustomCopy.UNKNOWN)
        assertEquals(1, unread.size)
        assertTrue(unread.single().contains("connection"))
        assertTrue(unread.single().startsWith("If this workshop"))
        assertTrue(
            dwCustomSectionWarnings(DwCustomCopy.UNKNOWN, answersHeld = true)
                .single().startsWith("This device holds answers")
        )
    }

    @Test
    fun `every field of a retired section is forced retired`() {
        val cache = cacheOf(
            DwCustomSectionDto(
                key = "gone", stageKey = "A", retired = true,
                fields = listOf(field("a"), field("b", retired = true)),
            )
        )
        val section = sectionsForStage(cache, "A").single()
        assertTrue(section.fields.all { it.retired })
        assertTrue("a retired section offers nothing", liveCustomFields(section).isEmpty())
        assertEquals(listOf("a", "b"), customFieldsForStage(cache, "A").map { it.key })
    }

    @Test
    fun `a retired field is kept in the flat list and offered by no form`() {
        val section = DwCustomSectionDto(
            key = "s", stageKey = "A",
            fields = listOf(field("live"), field("old", retired = true)),
        )
        assertEquals(listOf("live", "old"), customFieldsForStage(cacheOf(section), "A").map { it.key })
        assertEquals(listOf("live"), liveCustomFields(section).map { it.key })
    }

    @Test
    fun `two sections declaring one key share a single slot, and the live one wins it`() {
        val cache = cacheOf(
            DwCustomSectionDto(
                key = "old", stageKey = "A", sortOrder = 0,
                fields = listOf(field("looms", label = "How many looms?", retired = true)),
            ),
            DwCustomSectionDto(
                key = "new", stageKey = "A", sortOrder = 1,
                fields = listOf(field("looms", label = "Looms in working order")),
            ),
        )
        val flat = customFieldsForStage(cache, "A")
        assertEquals(listOf("looms"), flat.map { it.key })
        assertEquals("Looms in working order", flat.single().label)
        assertFalse("the live question is the one counted and coerced against", flat.single().retired)
        assertEquals(2, customStageBlocks(cache, "A").size)
    }

    @Test
    fun `a retired field is printed only where an answer stands against it`() {
        val section = DwCustomSectionDto(
            key = "s", stageKey = "A",
            fields = listOf(field("answered", retired = true), field("never", retired = true)),
        )
        val values = mapOf<String, JsonElement>("answered" to JsonPrimitive("12"))
        assertEquals(listOf("answered"), retiredCustomFieldsWithAnswers(section, values).map { it.key })
        assertTrue(retiredCustomFieldsWithAnswers(section, emptyMap()).isEmpty())
    }

    @Test
    fun `sections and fields are ordered by sortOrder then key, and the two views agree`() {
        val cache = cacheOf(
            DwCustomSectionDto(
                key = "second", stageKey = "A", sortOrder = 5,
                fields = listOf(field("z", sortOrder = 1), field("y", sortOrder = 0)),
            ),
            DwCustomSectionDto(
                key = "first", stageKey = "A", sortOrder = 1,
                fields = listOf(field("b", sortOrder = 0), field("a", sortOrder = 0)),
            ),
            DwCustomSectionDto(key = "elsewhere", stageKey = "B", fields = listOf(field("x"))),
        )
        assertEquals(listOf("first", "second"), sectionsForStage(cache, "A").map { it.key })
        assertEquals(listOf("a", "b", "y", "z"), customFieldsForStage(cache, "A").map { it.key })
        assertEquals(
            customFieldsForStage(cache, "A"),
            customStageBlocks(cache, "A").flatMap { it.fields },
        )
        assertTrue(customFieldsForStage(cache, "C").isEmpty())
        assertTrue("a null cache asks nothing anywhere", customFieldsForStage(null, "A").isEmpty())
    }

    @Test
    fun `the twelve v1 types are drawable and the paintable-but-unsafe ones are not`() {
        assertEquals(12, V1_CUSTOM_TYPES.size)
        V1_CUSTOM_TYPES.forEach { type ->
            assertNotNull("v1 declares a type this build cannot resolve: $type", DwFieldType.known(type))
            assertTrue("$type must be drawable", dwCustomFieldDrawable(type))
        }
        listOf("GEO", "IMAGE", "IMAGE_LIST", "FILE", "AUDIO", "VIDEO", "REF", "RICH_TEXT", "URL", "PHONE", "EMAIL")
            .forEach { type ->
                assertNotNull("this build CAN paint $type", DwFieldType.known(type))
                assertFalse("…and must still refuse it for a custom field", dwCustomFieldDrawable(type))
            }
    }

    @Test
    fun `an unknown token degrades to TEXT through of and to null through known`() {
        assertEquals(DwFieldType.TEXT, DwFieldType.of("SIGNATURE"))
        assertNull(DwFieldType.known("SIGNATURE"))
        assertFalse(dwCustomFieldDrawable("SIGNATURE"))
    }

    @Test
    fun `of still degrades for the registry, and known is the only strict door`() {
        assertEquals(DwFieldType.TEXT, DwFieldType.of("A_TYPE_NOBODY_HAS_INVENTED"))
        assertEquals(DwFieldType.MONEY, DwFieldType.of("MONEY"))
        assertEquals(DwFieldType.MONEY, DwFieldType.known("MONEY"))
    }

    @Test
    fun `the unsupported note names the raw token as it arrived`() {
        assertTrue(dwCustomUnsupportedNote("SIGNATURE").contains("Update the app to answer this question"))
        assertTrue(dwCustomUnsupportedNote("").isNotBlank())
    }

    @Test
    fun `a custom field becomes a FieldDto the existing renderer can draw`() {
        val source = DwCustomFieldDto(
            id = "row-id", key = "warpMaterial", label = "Warp material", type = "ENUM",
            tier = "BASIC", required = true, help = "Pick one.", unit = "kg",
            options = listOf(DwCustomOptionDto("COTTON", "Cotton")),
            maxLength = 40, minValue = 1.0, maxValue = 9.0,
        )
        val spec = customFieldToFieldDto(source)

        assertEquals("warpMaterial", spec.key)
        assertEquals("Warp material", spec.label)
        assertEquals("ENUM", spec.type)
        assertEquals("BASIC", spec.tier)
        assertTrue(spec.required)
        assertEquals("Pick one.", spec.help)
        assertEquals("kg", spec.unit)
        assertEquals(listOf(EnumOption("COTTON", "Cotton")), spec.options)
        assertEquals(40, spec.maxLength)
        assertEquals(1.0, spec.minValue!!, 0.0)
        assertEquals(9.0, spec.maxValue!!, 0.0)
        assertEquals("KEY_VALUE", spec.reportRole)
        assertEquals("", spec.refModel)
    }

    @Test
    fun `a section becomes a synthetic singleton entity carrying only its live drawable fields`() {
        val section = DwCustomSectionDto(
            id = "s1", key = "loomAudit", stageKey = "A", title = "Loom audit",
            description = "Asked by this cluster.",
            fields = listOf(field("live"), field("old", retired = true)),
        )
        val entity = customSectionEntity(section)

        assertEquals("_custom:loomAudit", entity.key)
        assertEquals("_custom:loomAudit", customSectionEntityKey(section))
        assertEquals("SINGLETON", entity.cardinality)
        assertEquals("Loom audit", entity.title)
        assertEquals(listOf("live"), entity.fields.map { it.key })
    }

    @Test
    fun `no key a designer may declare can collide with the reserved entity key`() {
        assertFalse(CUSTOM_KEY_PATTERN.matches(CUSTOM_ENTITY_KEY))
        assertFalse(CUSTOM_KEY_PATTERN.matches("_clientKey"))
        assertTrue(CUSTOM_KEY_PATTERN.matches("loomsWorking"))
        assertFalse(CUSTOM_KEY_PATTERN.matches("Looms"))
        assertFalse(CUSTOM_KEY_PATTERN.matches("looms_working"))
        assertEquals("_custom", CUSTOM_ENTITY_KEY)
    }

    @Test
    fun `a multi-valued answer is carried as it was stored`() {
        val values = mapOf<String, JsonElement>(
            "tags" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        )
        assertTrue(DwValues.isFilled(values["tags"]))
        assertEquals(listOf("a", "b"), DwValues.list(values["tags"]))
    }
}
