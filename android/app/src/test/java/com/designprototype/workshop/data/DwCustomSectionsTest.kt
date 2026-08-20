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

/**
 * The rules a designer's own questions are read by, on a desktop JVM with no emulator.
 *
 * Everything asserted here is a decision that decides what a designer SEES: whether a question is
 * offered, whether it is offered as an editable box or as a stated gap, and whether a phone holding
 * no definition says "there are none" or says nothing. Each of those has a failure that is silent by
 * construction, which is why they are pinned here rather than checked by opening the app.
 */
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

    // ── The three states ────────────────────────────────────────────────────────────────────────

    /**
     * The reason there are three and not two, executed.
     *
     * A two-state answer would have to call one of the first two rows the other, and either way one
     * of the two commonest exports gets the wrong treatment: an apology on every workshop that has no
     * custom questions (which is most of them, so nobody reads warnings any more), or silence on a
     * phone that is missing a whole section of a report it is about to hand to an officer.
     */
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
        // Empty AND not complete is not an answer about the workshop at all — it is a cache nothing
        // was able to claim the whole set for. Treated as never having asked, which is the
        // conservative direction: it warns rather than asserting there is nothing.
        assertEquals(
            DwCustomCopy.UNKNOWN,
            dwCustomCopy(DwCustomCache(workshopId = "w1", complete = false)),
        )
    }

    /**
     * THE ONE THING THAT MAKES [DwCustomSectionStore]'S DELETE-ON-CORRUPTION DEFENSIBLE.
     *
     * That store destroys a cache it cannot parse, because it is a copy of something the server still
     * holds. The next `load` returns null. If null resolved to NONE_DEFINED, the phone would assert
     * "this workshop has no custom questions" on the strength of a parse error, print a report short
     * a whole section, score it as complete, and say nothing about any of it.
     */
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
        // Conditional when this phone holds no answers of its own, because it cannot tell a workshop
        // with custom sections from one without; flat when it does, because then it knows.
        assertTrue(unread.single().startsWith("If this workshop"))
        assertTrue(
            dwCustomSectionWarnings(DwCustomCopy.UNKNOWN, answersHeld = true)
                .single().startsWith("This device holds answers")
        )
    }

    // ── Retired sections, retired fields ────────────────────────────────────────────────────────

    /**
     * A retired SECTION's live-looking fields are forced retired, in one place, for every reader.
     *
     * A section's `isActive` and a field's are separate columns, so a retired section arrives
     * carrying fields that call themselves live. Without the forcing the FORM would draw it as an
     * ordinary editable block (its live fields are non-empty) while the SCORER counted none of them
     * and the report printed none of them: a designer typing answers nobody is being asked, into a
     * bucket the completeness bar ignores.
     */
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
        // …and its fields are STILL in the flat list, because those are exactly the keys the
        // `_custom` row still holds. Dropped from here they become keys the definition does not
        // carry, and the next ordinary save writes the row without them.
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

    /**
     * ONE KEY, ONE SPEC IN THE FLAT LIST, WHICH IS THE SERVER'S `fields_for` SLOT RULE.
     *
     * The container is one flat bucket per STAGE while the database can only make a key unique per
     * SECTION, so two sections of one stage declaring one key is a state `plan_definition` refuses to
     * create and the server nevertheless defends against — for a row written by hand, a definition
     * restored from an older backup, or a bug nobody has found. `computeStageCompleteness` walks this
     * list, so a client that flattened without the rule counts one required key TWICE: the stage
     * reads 6/7 on the handset and 6/6 in the office, and neither surface can explain it.
     *
     * THE LIVE SPEC WINS THE SLOT — it is the question on the screen the answer is being typed into.
     */
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
        // Both are still OFFERED and PRINTED under their own sections — the dedup is the flat
        // answer-path list's rule and never the form's, exactly as `section_payload` keeps both.
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

    // ── Order ───────────────────────────────────────────────────────────────────────────────────

    /**
     * ONE OWNER OF THE ORDER, and the flat list is the grouped list flattened.
     *
     * `missing` is printed in the scorer's order and truncated at three, so the order the questions
     * are counted in is the order a designer is told to go and fill them in. Two independent walks
     * would be two orderings, and the day they diverged a "still missing" link would send somebody to
     * the second question when the list said the first.
     */
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
        // Equal sortOrder falls back to the key, so two fields a designer added in one sitting have
        // a stable order rather than whatever the database happened to return.
        assertEquals(listOf("a", "b", "y", "z"), customFieldsForStage(cache, "A").map { it.key })
        // The two views are the same list for any definition the server would create — the flat one
        // additionally applies `fields_for`'s one-key-one-slot rule, which can only ever remove a
        // DUPLICATE key, and `plan_definition` refuses to create one. See the slot test above.
        assertEquals(
            customFieldsForStage(cache, "A"),
            customStageBlocks(cache, "A").flatMap { it.fields },
        )
        assertTrue(customFieldsForStage(cache, "C").isEmpty())
        assertTrue("a null cache asks nothing anywhere", customFieldsForStage(null, "A").isEmpty())
    }

    // ── The twelve types, and the degrade ───────────────────────────────────────────────────────

    /**
     * The v1 boundary is asked against the TWELVE and not against what this build can paint.
     *
     * [FieldRenderer] has a real, working arm for GEO, IMAGE, REF and RICH_TEXT, so a definition
     * naming one of those — from a server that had moved past v1 — would draw a live map card or a
     * camera button for a value none of the five media walkers can see. It would sync as a `dwlocal:`
     * reference resolving to nothing: the save reports success and the photograph is simply absent
     * from the .docx.
     */
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

    /**
     * THE DEGRADE THAT MATTERS MOST, and the reason [DwFieldType.known] exists beside [DwFieldType.of].
     *
     * `of` resolves ANY unrecognised token to TEXT, deliberately: for the server registry the
     * alternative is one new server type blanking all 22 stages on every handset that has not
     * updated. For a designer's own question that same forgiveness IS the failure — the token arrives
     * at the renderer as TEXT and is drawn as an ordinary editable box with no note and no disabled
     * state, so the designer types an answer into it. A silent WRONG answer, worse than the web's
     * silent blank.
     */
    @Test
    fun `an unknown token degrades to TEXT through of and to null through known`() {
        assertEquals(DwFieldType.TEXT, DwFieldType.of("SIGNATURE"))
        assertNull(DwFieldType.known("SIGNATURE"))
        assertFalse(dwCustomFieldDrawable("SIGNATURE"))
    }

    /** `of`'s behaviour for the CORE registry must not change — it is what stops one new type blanking 22 stages. */
    @Test
    fun `of still degrades for the registry, and known is the only strict door`() {
        assertEquals(DwFieldType.TEXT, DwFieldType.of("A_TYPE_NOBODY_HAS_INVENTED"))
        assertEquals(DwFieldType.MONEY, DwFieldType.of("MONEY"))
        assertEquals(DwFieldType.MONEY, DwFieldType.known("MONEY"))
    }

    @Test
    fun `the unsupported note names the raw token as it arrived`() {
        // A note that will not say what the type was is a note a designer cannot report, and the only
        // person who will ever stand in that cluster is the one reading it.
        assertTrue(dwCustomUnsupportedNote("SIGNATURE").contains("SIGNATURE"))
        assertTrue(dwCustomUnsupportedNote("").isNotBlank())
    }

    // ── The adapter ─────────────────────────────────────────────────────────────────────────────

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
        // KEY_VALUE, the default: the report prints a custom answer in the same label/value grid as
        // the stage's own answers rather than inventing a role for it.
        assertEquals("KEY_VALUE", spec.reportRole)
        // The row id is deliberately NOT carried: the answer is stored under `key`, which is what
        // makes it survive a rewording.
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

        // PER SECTION and not `_custom`: two sections on one stage under one key would collide in
        // the search index's address and in the readiness screen's focus.
        assertEquals("_custom:loomAudit", entity.key)
        assertEquals("_custom:loomAudit", customSectionEntityKey(section))
        assertEquals("SINGLETON", entity.cardinality)
        assertEquals("Loom audit", entity.title)
        assertEquals(listOf("live"), entity.fields.map { it.key })
    }

    /** The reserved key cannot be a designer's, because the server's own pattern forbids the shape. */
    @Test
    fun `no key a designer may declare can collide with the reserved entity key`() {
        assertFalse(CUSTOM_KEY_PATTERN.matches(CUSTOM_ENTITY_KEY))
        assertFalse(CUSTOM_KEY_PATTERN.matches("_clientKey"))
        assertTrue(CUSTOM_KEY_PATTERN.matches("loomsWorking"))
        assertFalse(CUSTOM_KEY_PATTERN.matches("Looms"))
        assertFalse(CUSTOM_KEY_PATTERN.matches("looms_working"))
        assertEquals("_custom", CUSTOM_ENTITY_KEY)
    }

    /** A stored list value survives the round trip the store makes of it. */
    @Test
    fun `a multi-valued answer is carried as it was stored`() {
        val values = mapOf<String, JsonElement>(
            "tags" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        )
        assertTrue(DwValues.isFilled(values["tags"]))
        assertEquals(listOf("a", "b"), DwValues.list(values["tags"]))
    }
}
