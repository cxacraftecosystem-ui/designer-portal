package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The handset's half of the 2026-10-17 stage capture work (C6 numbering, C7/C8 counts, the server's
 * suggestions, A15's last stage name), held to the same answers the server and the web give.
 */
class DwStageCaptureTest {
    private fun p(value: Any): JsonElement = when (value) {
        is Int -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        else -> error("unsupported")
    }

    private val sketchNo = FieldDto(key = "sketchNo", type = "TEXT", autoNumber = "S")
    private val versionNo = FieldDto(key = "versionNo", type = "INT", autoNumber = "#", autoNumberWithin = "prototypeRef")

    @Test
    fun `a code follows the highest one used and never collides with a typed one`() {
        assertEquals(JsonPrimitive("S-01"), DwAutoNumber.nextNumber(sketchNo, emptyList()))
        assertEquals(
            JsonPrimitive("S-08"),
            DwAutoNumber.nextNumber(sketchNo, listOf(mapOf("sketchNo" to p("S-01")), mapOf("sketchNo" to p("s-07")))),
        )
        assertEquals(
            JsonPrimitive("S-03"),
            DwAutoNumber.nextNumber(sketchNo, listOf(mapOf("sketchNo" to p("Sk1")), mapOf("sketchNo" to p("Runner")))),
        )
    }

    @Test
    fun `iterations number within their own prototype`() {
        val rows = listOf(
            mapOf("prototypeRef" to p("p1"), "versionNo" to p(1)),
            mapOf("prototypeRef" to p("p1"), "versionNo" to p(2)),
            mapOf("prototypeRef" to p("p2"), "versionNo" to p(1)),
        )
        assertEquals(JsonPrimitive(3), DwAutoNumber.nextNumber(versionNo, rows, p("p1")))
        assertEquals(JsonPrimitive(1), DwAutoNumber.nextNumber(versionNo, rows, p("p3")))
    }

    @Test
    fun `only new rows are numbered, in order, and an existing number is never touched`() {
        val entity = EntityDto(key = "sketch", fields = listOf(sketchNo))
        val old = listOf("a" to mapOf("sketchNo" to p("S-01")), "b" to mapOf<String, JsonElement>())
        val next = DwAutoNumber.apply(entity, old, old + listOf("c" to emptyMap(), "d" to emptyMap()), null)
        assertEquals("S-01", next[0].second["sketchNo"]?.jsonPrimitive?.content)
        assertNull(next[1].second["sketchNo"])
        assertEquals("S-03", next[2].second["sketchNo"]?.jsonPrimitive?.content)
        assertEquals("S-04", next[3].second["sketchNo"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a schedule day is numbered and dated from the start`() {
        val entity = EntityDto(
            key = "workshopScheduleDay",
            fields = listOf(
                FieldDto(key = "dayNumber", type = "INT", autoNumber = "#"),
                FieldDto(key = "date", type = "DATE", autoDateFromDay = "dayNumber"),
            ),
        )
        val (_, row) = DwAutoNumber.apply(entity, emptyList(), listOf("n" to emptyMap()), "2026-10-05").single()
        assertEquals("1", row["dayNumber"]?.jsonPrimitive?.content)
        assertEquals("2026-10-05", row["date"]?.jsonPrimitive?.content)
        assertEquals("2027-01-01", DwAutoNumber.dateOfDay("2026-12-31", p(2)))
    }

    @Test
    fun `an untouched roster reads all present and a blank row is nobody`() {
        val people = listOf(
            mapOf("name" to p("Asha"), "gender" to p("Female")),
            mapOf("name" to p("Ravi"), "gender" to p("Male"), "absentDays" to JsonArray(listOf(p("2")))),
            mapOf("name" to p("")),
        )
        val values = DwStageComputed.rosterValues(people, listOf(1, 2, 3))
        assertEquals(DwStageComputed.RosterValues(2, 1, 1), values)
        assertEquals(2, DwStageComputed.attendedDays(people[1], listOf(1, 2, 3)))
        assertEquals(DwStageComputed.RosterValues(null, null, null), DwStageComputed.rosterValues(emptyList(), listOf(1)))
    }

    @Test
    fun `ticking writes a sorted absent list`() {
        val row = DwStageComputed.withAttendance(mapOf("absentDays" to JsonArray(listOf(p("3")))), 1, present = false)
        assertEquals(listOf("1", "3"), row["absentDays"]!!.jsonArray.map { it.jsonPrimitive.content })
        val back = DwStageComputed.withAttendance(row, 3, present = true)
        assertEquals(listOf("1"), back["absentDays"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `the computed patch writes only what differs, and nothing it cannot count`() {
        val collections = mapOf(
            "participant" to listOf(
                "r1" to mapOf("name" to p("Asha"), "gender" to p("F"), "attendedDays" to p(5)),
                "r2" to mapOf("name" to p("Ravi"), "absentDays" to JsonArray(listOf(p("1")))),
            ),
        )
        val assist = DwStageAssistDto(attendanceDays = listOf(1, 2, 3, 4, 5))
        val patch = dwComputedPatch(DW_ROSTER, assist, mapOf("totalArtisans" to p(40)), collections)
        assertEquals(JsonPrimitive(2), patch.singleton["totalArtisans"])
        assertEquals(JsonPrimitive(1), patch.singleton["womenParticipants"])
        val rows = patch.rows["participant"]!!.toMap()
        assertEquals(5, rows["r1"]!!["attendedDays"]!!.jsonPrimitive.content.toInt())
        assertEquals(4, rows["r2"]!!["attendedDays"]!!.jsonPrimitive.content.toInt())

        // A legacy workshop with no roster keeps its typed total.
        val legacy = dwComputedPatch(DW_ROSTER, assist, mapOf("totalArtisans" to p(25)), emptyMap())
        assertTrue(legacy.singleton.isEmpty())
    }

    @Test
    fun `a typed legacy days-attended stands until the row is ticked`() {
        val legacy = mapOf("name" to p("Asha"), "attendedDays" to p(3))
        val days = listOf(1, 2, 3, 4, 5)
        assertTrue(DwStageComputed.isLegacyAttendance(legacy))
        assertNull(DwStageComputed.attendedDays(legacy, days))
        assertNull(DwStageComputed.rosterValues(listOf(legacy, mapOf("name" to p("Ravi"))), days).completed)
        val ticked = legacy + ("absentDays" to JsonArray(emptyList()))
        assertEquals(5, DwStageComputed.attendedDays(ticked, days))

        // The patch leaves the typed 3 alone and stamps a counted new row with its tick-list.
        val patch = dwComputedPatch(
            DW_ROSTER,
            DwStageAssistDto(attendanceDays = days),
            emptyMap(),
            mapOf("participant" to listOf("r1" to legacy, "r2" to mapOf("name" to p("Ravi")))),
        )
        val rows = patch.rows["participant"]!!.toMap()
        assertEquals(legacy, rows["r1"])
        assertEquals(5, rows["r2"]!!["attendedDays"]!!.jsonPrimitive.content.toInt())
        assertEquals(JsonArray(emptyList()), rows["r2"]!!["absentDays"])
    }

    @Test
    fun `with no server answer stage 3 works its days and schedule out from stage 1`() {
        val setup = mapOf("startDate" to p("2026-10-05"), "endDate" to p("2026-10-09"))
        val schedule = listOf(mapOf("dayNumber" to p(2)))
        val local = dwLocalStageAssist(DW_ROSTER, null, setup, schedule)!!
        assertEquals(listOf(1, 2, 3, 4, 5), local.attendanceDays)
        assertEquals("2026-10-05", local.startDate)
        val fill = local.suggestions.single()
        assertEquals("Fill the schedule", fill.title)
        assertEquals("Adds 4 days from 5 Oct 2026, numbered and dated.", fill.detail)
        assertEquals(listOf(1, 3, 4, 5), fill.rows.map { it["dayNumber"]!!.jsonPrimitive.content.toInt() })
        assertEquals("2026-10-07", fill.rows[1]["date"]!!.jsonPrimitive.content)

        // The stored duration wins over the dates, exactly as `setup_duration` reads it.
        assertEquals(listOf(1, 2, 3), DwStageComputed.setupDays(setup + ("durationDays" to p(3))))
        // Offline days feed the computed patch, so attendance is counted with no signal.
        val patch = dwComputedPatch(
            DW_ROSTER,
            local,
            emptyMap(),
            mapOf("participant" to listOf("r1" to mapOf("name" to p("Asha"), "absentDays" to JsonArray(listOf(p("1")))))),
        )
        assertEquals(4, patch.rows["participant"]!!.single().second["attendedDays"]!!.jsonPrimitive.content.toInt())

        // The server's answer stays the authority; only what it left empty is filled.
        val server = DwStageAssistDto(stageKey = DW_ROSTER, attendanceDays = listOf(1, 2))
        val enriched = dwLocalStageAssist(DW_ROSTER, server, setup, schedule)!!
        assertEquals(listOf(1, 2), enriched.attendanceDays)
        assertEquals("2026-10-05", enriched.startDate)
        assertTrue(enriched.suggestions.isEmpty())
        // Nothing to work from, and other stages, are left as they were.
        assertTrue(dwLocalStageAssist(DW_ROSTER, null, null, emptyList())!!.attendanceDays.isEmpty())
        assertNull(dwLocalStageAssist("MARKET_SURVEY_CAPTURE", null, setup, emptyList()))
    }

    @Test
    fun `a FILL lands on blank boxes only and an ADD_ROWS stays offered until tapped`() {
        val fill = DwAssistSuggestionDto(
            id = "reportSettings:template", kind = "FILL", entityKey = "reportSettings",
            values = mapOf("templateId" to p("PHOTO_CATALOGUE")),
        )
        assertTrue(DwStageAssist.stillApplies(fill, emptyMap(), emptyMap()))
        assertFalse(DwStageAssist.stillApplies(fill, mapOf("templateId" to p("DCH_STANDARD")), emptyMap()))
        val use = fill.copy(entityKey = "prototype", rowKey = "p1", values = mapOf("materialCost" to p("150.50")), replaces = true)
        val rows = mapOf("prototype" to listOf("local" to mapOf("_entryId" to p("p1"), "materialCost" to p("100.00"))))
        assertTrue(DwStageAssist.stillApplies(use, emptyMap(), rows))
        assertEquals("Use", DwStageAssist.actionWord(fill))
        assertEquals("Add 3", DwStageAssist.actionWord(fill.copy(kind = "ADD_ROWS", rows = List(3) { emptyMap() })))
        assertEquals("Record", DwStageAssist.actionWord(fill.copy(kind = "ADD_ROWS", due = true)))
    }

    @Test
    fun `the server's assist payload decodes`() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(
            DwStageAssistDto.serializer(),
            """{"stageKey":"X","suggestions":[{"id":"a","kind":"ADD_ROWS","entityKey":"workshopScheduleDay","rowKey":null,
               "title":"Fill the schedule","detail":"","rows":[{"dayNumber":2,"date":"2026-10-06"}]}],
               "computed":{"singleton":{"totalArtisans":3,"womenParticipants":null},"rows":{}},
               "attendanceDays":[1,2],"startDate":"2026-10-05","futureKey":true}""",
        )
        assertEquals(1, decoded.suggestions.single().rows.size)
        assertEquals(3, decoded.computed.singleton["totalArtisans"])
        assertNull(decoded.computed.singleton["womenParticipants"])
        assertEquals("2026-10-05", decoded.startDate)
    }

    @Test
    fun `the last stage name is the latest one logged for that prototype`() {
        val logs = listOf(
            mapOf("prototypeRef" to p("p1"), "logDate" to p("2026-10-10"), "stageName" to p("Weaving")),
            mapOf("prototypeRef" to p("p1"), "logDate" to p("2026-10-12"), "stageName" to p("Dyeing")),
            mapOf("prototypeRef" to p("p2"), "logDate" to p("2026-10-13"), "stageName" to p("Cutting")),
        )
        assertEquals("Dyeing", dwLastStageName(setOf("p1"), logs))
        assertEquals("", dwLastStageName(setOf("p9"), logs))
    }

    @Test
    fun `the bundled registry carries every behaviour this build reads`() {
        val asset = File("src/main/assets/design-workshop-schema.json").readText()
        val fields = Json.parseToJsonElement(asset).jsonObject["stages"]!!.jsonArray
            .flatMap { it.jsonObject["entities"]!!.jsonArray }
            .flatMap { e -> e.jsonObject["fields"]!!.jsonArray.map { e.jsonObject["key"]!!.jsonPrimitive.content to it.jsonObject } }
            .associate { (entity, f) -> "$entity.${f["key"]!!.jsonPrimitive.content}" to f }
        assertEquals("S", fields["sketch.sketchNo"]!!["autoNumber"]!!.jsonPrimitive.content)
        assertEquals("ROSTER_COUNT", fields["workshopPlan.totalArtisans"]!!["computedKind"]!!.jsonPrimitive.content)
        assertEquals("ATTENDANCE", fields["participant.absentDays"]!!["widget"]!!.jsonPrimitive.content)
        assertTrue(fields["prototype.sketchRef"]!!.containsKey("refSuggest"))
    }

    private companion object {
        const val DW_ROSTER = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"
    }
}
