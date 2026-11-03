package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwProvenanceReportTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    private val wire = """
        {
          "entries": [
            {
              "entryId": "ent_p1",
              "stageKey": "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
              "entityKey": "participant",
              "ordinal": 0,
              "createdById": "usr_asha",
              "fields": {
                "name": {"by": "usr_meena", "byName": "Meena Iyer",
                         "at": "2026-03-01T09:00:00+00:00", "source": "reference",
                         "refModel": "Artisan", "refId": "art_1", "refKey": "name"},
                "village": {"by": "usr_meena", "byName": "Meena Iyer",
                            "at": "2026-03-01T09:00:00+00:00", "source": "reference",
                            "refModel": "Artisan", "refId": "art_1", "refKey": "village"},
                "phone": {"by": "usr_asha", "byName": "Asha Patel",
                          "at": "2026-03-08T15:30:00+00:00", "source": "designer"}
              },
              "canonical": {
                "name": {"stored": "Latha Devi", "canonical": "Latha Devi",
                         "diverged": false, "recordDeleted": false},
                "village": {"stored": "Barpali", "canonical": "Bargarh Town",
                            "diverged": true, "recordDeleted": false}
              }
            },
            {
              "entryId": "ent_p2",
              "stageKey": "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
              "entityKey": "participant",
              "ordinal": 1,
              "createdById": "usr_asha",
              "fields": {
                "name": {"by": "usr_gone", "byName": null,
                         "at": "2026-03-01T09:00:00+00:00", "source": "reference",
                         "refModel": "Artisan", "refId": "art_2", "refKey": "name"}
              },
              "canonical": {
                "name": {"stored": "Kamla Bai", "canonical": null,
                         "diverged": false, "recordDeleted": true}
              }
            },
            {
              "entryId": "ent_s1",
              "stageKey": "WORKSHOP_SETUP",
              "entityKey": "workshopSetup",
              "ordinal": 0,
              "createdById": "usr_asha",
              "fields": {},
              "canonical": {}
            }
          ]
        }
    """.trimIndent()

    private fun decoded(): DwProvenanceReportDto =
        json.decodeFromString(DwProvenanceReportDto.serializer(), wire)

    private fun comparison(
        stored: String? = null,
        canonical: String? = null,
        diverged: Boolean = false,
        recordDeleted: Boolean = false,
    ) = DwCanonicalComparisonDto(
        stored = stored?.let { JsonPrimitive(it) },
        canonical = canonical?.let { JsonPrimitive(it) },
        diverged = diverged,
        recordDeleted = recordDeleted,
    )

    private fun entry(
        entryId: String = "e1",
        canonical: Map<String, DwCanonicalComparisonDto>,
    ) = DwProvenanceEntryDto(
        entryId = entryId,
        stageKey = "S",
        entityKey = "participant",
        canonical = canonical,
    )

    @Test
    fun `the real response shape decodes, keys and all`() {
        val report = decoded()
        assertEquals(3, report.entries.size)

        val p1 = report.entries[0]
        assertEquals("ent_p1", p1.entryId)
        assertEquals("WORKSHOP_PLAN_PARTICIPANTS_OPENING", p1.stageKey)
        assertEquals("participant", p1.entityKey)
        assertEquals(0, p1.ordinal)
        assertEquals("usr_asha", p1.createdById)

        assertEquals(
            "From the artisan record, by Meena Iyer",
            p1.fields["village"]?.attribution()
        )
        assertTrue(p1.fields.containsKey("phone"))
        assertFalse(p1.canonical.containsKey("phone"))

        val village = p1.canonical["village"]!!
        assertEquals(JsonPrimitive("Barpali"), village.stored)
        assertEquals(JsonPrimitive("Bargarh Town"), village.canonical)
        assertTrue(village.diverged)
        assertFalse(village.recordDeleted)
    }

    @Test
    fun `an entry that copied nothing has an empty map and not a null`() {
        val singleton = decoded().entries[2]
        assertEquals(emptyMap<String, DwCanonicalComparisonDto>(), singleton.canonical)
        assertEquals(0, singleton.ordinal)
    }

    @Test
    fun `a workshop whose copied values still match reports nothing to explain`() {
        val tally = dwDivergenceTally(
            DwProvenanceReportDto(
                entries = listOf(
                    entry(canonical = mapOf("name" to comparison("Latha", "Latha", diverged = false)))
                )
            )
        )
        assertEquals(DwDivergenceTally(records = 0, fields = 0), tally)
        assertTrue(tally.nothingHasMoved)
        assertEquals("Nothing has moved since this workshop was recorded", dwDivergenceHeadline(tally))
    }

    @Test
    fun `fields and records are counted separately, because they answer different questions`() {
        val tally = dwDivergenceTally(
            DwProvenanceReportDto(
                entries = listOf(
                    entry(
                        entryId = "e1",
                        canonical = mapOf(
                            "village" to comparison("Barpali", "Bargarh", diverged = true),
                            "phone" to comparison("98765", "99999", diverged = true),
                            "name" to comparison("Latha", "Latha", diverged = false),
                        )
                    ),
                    entry(
                        entryId = "e2",
                        canonical = mapOf("name" to comparison("Bhikari", "Bhikari", diverged = false))
                    ),
                )
            )
        )
        assertEquals(DwDivergenceTally(records = 1, fields = 2), tally)
        assertEquals("2 fields across 1 record now differ", dwDivergenceHeadline(tally))
    }

    @Test
    fun `only the diverged fields are listed, never the whole comparison`() {
        val fields = dwDivergedFields(
            entry(
                canonical = mapOf(
                    "village" to comparison("Barpali", "Bargarh", diverged = true),
                    "name" to comparison("Latha", "Latha", diverged = false),
                )
            )
        )
        assertEquals(listOf("village"), fields.map { it.first })
    }

    @Test
    fun `a deleted canonical record counts and is never dropped`() {
        val report = DwProvenanceReportDto(
            entries = listOf(
                entry(
                    canonical = mapOf(
                        "name" to comparison("Latha Devi", canonical = null, diverged = false, recordDeleted = true)
                    )
                )
            )
        )
        assertEquals(DwDivergenceTally(records = 1, fields = 1), dwDivergenceTally(report))
        assertEquals(listOf("name"), dwDivergedFields(report.entries[0]).map { it.first })
        assertEquals("1 field across 1 record now differ", dwDivergenceHeadline(dwDivergenceTally(report)))
    }

    @Test
    fun `the deleted row survives decoding of the real payload, not only a hand-built one`() {
        val report = decoded()
        val deleted = report.entries[1].canonical["name"]!!
        assertTrue(deleted.recordDeleted)
        assertFalse("the server reports a deleted record as NOT diverged", deleted.diverged)
        assertTrue(deleted.hasMoved)
        assertEquals(DwDivergenceTally(records = 2, fields = 2), dwDivergenceTally(report))
    }

    @Test
    fun `a cleared column is not reported as a deleted record`() {
        val cleared = comparison("98765", canonical = null, diverged = true, recordDeleted = false)
        assertTrue(cleared.hasMoved)
        assertFalse(cleared.recordDeleted)
        assertEquals("—", dwCanonicalText(cleared))
        assertEquals(
            DW_PROVENANCE_RECORD_DELETED,
            dwCanonicalText(comparison("Kamla Bai", canonical = null, recordDeleted = true))
        )
    }

    @Test
    fun `a whole number is spelled the way the browser spells it`() {
        assertEquals("127", dwComparisonText(JsonPrimitive(127.0)))
        assertEquals("0", dwComparisonText(JsonPrimitive(0.0)))
        assertEquals("-4", dwComparisonText(JsonPrimitive(-4.0)))
        assertEquals("127.5", dwComparisonText(JsonPrimitive(127.5)))
        assertEquals("2.54", dwComparisonText(JsonPrimitive(2.54)))
        assertEquals("0", dwComparisonText(JsonPrimitive(0)))
        assertEquals("false", dwComparisonText(JsonPrimitive(false)))
        assertEquals("127.0", dwComparisonText(JsonPrimitive("127.0")))
    }

    @Test
    fun `an empty value renders as a dash rather than as the word null`() {
        assertEquals("—", dwComparisonText(null))
        assertEquals("—", dwComparisonText(JsonNull))
        assertEquals("—", dwComparisonText(JsonPrimitive("")))
        assertEquals("—", DW_COMPARISON_EMPTY)
    }

    @Test
    fun `zero and false are answers and must not render as the dash`() {
        assertEquals("0", dwComparisonText(JsonPrimitive(0)))
        assertEquals("false", dwComparisonText(JsonPrimitive(false)))
        assertEquals("Barpali", dwComparisonText(JsonPrimitive("Barpali")))
    }

    @Test
    fun `an object value is printed as JSON rather than summarised`() {
        val geo = buildJsonObject { put("lat", 21.34); put("lng", 83.61) }
        assertEquals("""{"lat":21.34,"lng":83.61}""", dwComparisonText(geo))
    }

    @Test
    fun `an entry is titled in the registry's words and falls back to its keys`() {
        val registry = SchemaResponse(
            stages = listOf(
                StageDto(
                    key = "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
                    title = "Participants",
                    entities = listOf(
                        EntityDto(key = "participant", title = "Participants", cardinality = "COLLECTION")
                    )
                ),
                StageDto(
                    key = "WORKSHOP_SETUP",
                    title = "Workshop setup",
                    entities = listOf(
                        EntityDto(key = "workshopSetup", title = "Workshop details", cardinality = "SINGLETON")
                    )
                )
            )
        )
        val report = decoded()
        assertEquals(
            "Participants · Participants · row 1",
            dwProvenanceEntryTitle(report.entries[0], registry)
        )
        assertEquals(
            "Workshop setup · Workshop details",
            dwProvenanceEntryTitle(report.entries[2], registry)
        )
        assertEquals(
            "WORKSHOP_PLAN_PARTICIPANTS_OPENING · participant",
            dwProvenanceEntryTitle(report.entries[0], null)
        )
    }

    @Test
    fun `both halves of the headline are pluralised on their own number`() {
        assertEquals(
            "1 field across 1 record now differ",
            dwDivergenceHeadline(DwDivergenceTally(records = 1, fields = 1))
        )
        assertEquals(
            "5 fields across 3 records now differ",
            dwDivergenceHeadline(DwDivergenceTally(records = 3, fields = 5))
        )
    }

    @Test
    fun `every sentence is the browser's, character for character`() {
        assertEquals("Authorship & divergence", DW_PROVENANCE_TITLE)
        assertEquals("Comparing this workshop against the shared records…", DW_PROVENANCE_LOADING)
        assertEquals("Could not load the provenance view.", DW_PROVENANCE_LOAD_FAILED)
        assertEquals("Nothing has moved since this workshop was recorded", DW_PROVENANCE_NOTHING_MOVED)
        assertEquals(
            "Every value this workshop copied from a shared record still matches that record.",
            DW_PROVENANCE_ALL_MATCH
        )
        assertEquals("The record this came from no longer exists", DW_PROVENANCE_RECORD_DELETED)
        assertEquals("Field", DW_PROVENANCE_COLUMN_FIELD)
        assertEquals("This workshop", DW_PROVENANCE_COLUMN_STORED)
        assertEquals("The record today", DW_PROVENANCE_COLUMN_CANONICAL)
        assertEquals(
            "A workshop keeps what the designer saw on the day, so a value that differs from the " +
                "shared record today is not an error — an artisan who moved village after the " +
                "workshop makes every row that names them differ, and every one of those rows is " +
                "right. This page exists so the difference can be seen and explained, not corrected.",
            DW_PROVENANCE_NOT_AN_ERROR_PREFIX +
                DW_PROVENANCE_NOT_AN_ERROR_EMPHASIS +
                DW_PROVENANCE_NOT_AN_ERROR_SUFFIX
        )
    }

    @Test
    fun `nothing on this screen calls a divergence a fault`() {
        val everySentence = listOf(
            DW_PROVENANCE_TITLE,
            DW_PROVENANCE_NOTHING_MOVED,
            DW_PROVENANCE_ALL_MATCH,
            DW_PROVENANCE_RECORD_DELETED,
            DW_PROVENANCE_COLUMN_FIELD,
            DW_PROVENANCE_COLUMN_STORED,
            DW_PROVENANCE_COLUMN_CANONICAL,
            dwDivergenceHeadline(DwDivergenceTally(records = 3, fields = 5)),
        ).joinToString(" ").lowercase()
        val accusations =
            listOf("error", "problem", "wrong", "invalid", "stale", "fix", "conflict", "outdated", "!")
        for (accusation in accusations) {
            assertFalse(
                "the divergence view must never call a correct, dated observation a $accusation",
                everySentence.contains(accusation)
            )
        }
        val screen = repoFile(
            "src/main/java/com/designprototype/workshop/ui/designworkshop/DwProvenanceScreen.kt",
            "app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwProvenanceScreen.kt",
            "android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwProvenanceScreen.kt",
        ).readText()
        val spoken = screen.lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .flatMap { Regex("\"([^\"]*)\"").findAll(it).map { m -> m.groupValues[1] } }
            .joinToString(" ")
            .lowercase()
        assertTrue("the scan must actually be reading the screen's copy", spoken.contains("try again"))
        for (accusation in accusations) {
            assertFalse(
                "nothing the divergence view says may call a correct, dated observation a " +
                    "$accusation, and that includes its controls",
                spoken.contains(accusation)
            )
        }
        assertTrue(DW_PROVENANCE_NOT_AN_ERROR_PREFIX.endsWith("today is "))
        assertEquals("not an error", DW_PROVENANCE_NOT_AN_ERROR_EMPHASIS)
    }

    private fun repoFile(vararg relative: String): java.io.File {
        var dir: java.io.File? = java.io.File(".").absoluteFile
        while (dir != null) {
            for (path in relative) {
                val candidate = java.io.File(dir, path)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("none of ${relative.toList()} found from ${java.io.File(".").absolutePath}")
    }
}
