package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignWorkshopInspectionsTest {

    private fun eligible(id: String, name: String = id, role: String = "INSPECTOR") =
        DwEligibleInspectorDto(id = id, name = name, email = "$id@example.org", role = role)

    private fun assigned(id: String, name: String = id, role: String = "INSPECTOR") =
        DwInspectorDto(userId = id, name = name, email = "$id@example.org", role = role)

    private val reader = "u-reader"

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun `an inspector the server no longer offers is still offered here, ticked and marked`() {
        val choices = dwInspectorChoices(
            eligible = listOf(eligible("u-active")),
            inspectors = listOf(assigned("u-barred")),
            readerId = reader,
            eligibleListComplete = true,
        )

        assertEquals(listOf("u-active", "u-barred"), choices.map { it.userId })
        assertFalse(choices.first { it.userId == "u-active" }.assignedButIneligible)
        assertTrue(
            "an assigned account missing from a COMPLETE eligible list must be marked, or an admin " +
                "cannot tell it from an ordinary option",
            choices.first { it.userId == "u-barred" }.assignedButIneligible
        )
    }

    @Test
    fun `over a searched list nobody is marked ineligible, because the absence proves nothing`() {
        val choices = dwInspectorChoices(
            eligible = listOf(eligible("u-match")),
            inspectors = listOf(assigned("u-elsewhere")),
            readerId = reader,
            eligibleListComplete = false,
        )
        assertTrue(choices.none { it.assignedButIneligible })
    }

    @Test
    fun `a tick narrowed out by a search is handed back, so the next save cannot end it`() {
        val choices = dwInspectorChoices(
            eligible = listOf(eligible("u-second")),
            inspectors = emptyList(),
            readerId = reader,
            eligibleListComplete = false,
            retained = listOf(eligible("u-first")),
        )
        assertEquals(listOf("u-second", "u-first"), choices.map { it.userId })
        assertTrue("a retained account says nothing about eligibility", choices.none { it.assignedButIneligible })
    }

    @Test
    fun `the workshop's creator is NOT filtered out, unlike the viewers picker`() {
        val choices = dwInspectorChoices(
            eligible = listOf(eligible("u-creator", role = "ADMIN"), eligible("u-b")),
            inspectors = emptyList(),
            readerId = reader,
            eligibleListComplete = true,
        )
        assertEquals(listOf("u-creator", "u-b"), choices.map { it.userId })
    }

    @Test
    fun `a blank id is never offered and a duplicate is never offered twice`() {
        val choices = dwInspectorChoices(
            eligible = listOf(eligible(""), eligible("u-a")),
            inspectors = listOf(assigned("u-a"), assigned("")),
            readerId = reader,
            eligibleListComplete = true,
        )
        assertEquals(listOf("u-a"), choices.map { it.userId })
        assertFalse(
            "an account in BOTH lists is an ordinary eligible option, not a marked one",
            choices.single().assignedButIneligible
        )
    }

    @Test
    fun `the reader's own inspection is kept, and never called barred`() {
        val choices = dwInspectorChoices(
            eligible = listOf(eligible("u-a")),
            inspectors = listOf(assigned(reader, role = "ADMIN"), assigned("u-barred")),
            readerId = reader,
            eligibleListComplete = true,
        )

        assertEquals(listOf("u-a", reader, "u-barred"), choices.map { it.userId })
        assertFalse(
            "the reader is missing from the eligible list for being the reader, which says nothing " +
                "about the access list",
            choices.first { it.userId == reader }.assignedButIneligible
        )
        assertTrue(
            "the rule still marks everybody else it can prove",
            choices.first { it.userId == "u-barred" }.assignedButIneligible
        )
    }

    @Test
    fun `the reader is never offered as a new choice, from any answer`() {
        val choices = dwInspectorChoices(
            eligible = listOf(eligible(reader, role = "ADMIN"), eligible("u-a")),
            inspectors = emptyList(),
            readerId = reader,
            eligibleListComplete = false,
            retained = listOf(eligible(reader, role = "ADMIN")),
        )

        assertEquals(listOf("u-a"), choices.map { it.userId })
    }

    @Test
    fun `the payload is exactly what is ticked - there is no creator row to re-attach`() {
        val selection = DwInspectorSelection.adopt(listOf(assigned("u-a"), assigned("u-b")))
            .withSelection(setOf("u-b", "u-c"))
        assertEquals(setOf("u-c"), selection.added)
        assertEquals(setOf("u-a"), selection.removed)
        assertTrue(selection.dirty)
        assertEquals(setOf("u-b", "u-c"), selection.payload().toSet())
    }

    @Test
    fun `the ANSWER becomes the baseline, never the payload that was sent`() {
        val sent = DwInspectorSelection.adopt(listOf(assigned("u-a"))).withSelection(setOf("u-a", "u-b"))
        assertTrue(sent.dirty)
        val served = DwInspectorSelection.adopt(listOf(assigned("u-a"), assigned("u-b"), assigned("u-c")))
        assertFalse("adopting the answer leaves nothing unsaved", served.dirty)
        assertEquals(setOf("u-a", "u-b", "u-c"), served.baseline)
    }

    @Test
    fun `discard returns to what the repository holds and nothing else`() {
        val selection = DwInspectorSelection.adopt(listOf(assigned("u-a"))).withSelection(emptySet())
        assertEquals(setOf("u-a"), selection.removed)
        assertFalse(selection.discard().dirty)
    }

    @Test
    fun `the cap is the server's twenty-five, and it is reached before the save rather than at it`() {
        assertEquals(25, DW_INSPECTOR_LIMIT)
        val under = DwInspectorSelection().withSelection((1..DW_INSPECTOR_LIMIT).map { "u-$it" }.toSet())
        assertFalse(under.overLimit)
        val over = DwInspectorSelection().withSelection((1..DW_INSPECTOR_LIMIT + 1).map { "u-$it" }.toSet())
        assertTrue(over.overLimit)
        assertTrue(DW_INSPECTOR_LIMIT < DW_VIEWER_LIMIT)
    }

    @Test
    fun `the notice has three states and says nothing at all in the ordinary one`() {
        assertNull(dwInspectorOfferNotice(DwEligibleInspectors(users = listOf(eligible("u-a")))))
        assertEquals(
            "Too many to list — search by name or email.",
            dwInspectorOfferNotice(DwEligibleInspectors(truncated = true))
        )
        assertEquals(
            "Too many matches — narrow the search.",
            dwInspectorOfferNotice(DwEligibleInspectors(truncated = true, search = "sharma"))
        )
        assertEquals(
            "No account that may inspect matches that search.",
            dwInspectorOfferNotice(DwEligibleInspectors(search = "sharma"))
        )
    }

    @Test
    fun `the viewers' FOURTH state is deliberately absent, and this is what pins that`() {
        val cutAndEmpty = DwEligibleInspectors(truncated = true)
        assertEquals(
            "an unsearched cut must advise searching, not report an unreachable cut",
            "Too many to list — search by name or email.",
            dwInspectorOfferNotice(cutAndEmpty)
        )
        assertTrue(
            "the viewers' first sentence must not appear here at all",
            dwInspectorOfferNotice(cutAndEmpty)?.contains("searching will not bring them up") != true
        )
    }

    @Test
    fun `complete means the WHOLE eligible set and nothing narrower`() {
        assertTrue(DwEligibleInspectors().complete)
        assertFalse(DwEligibleInspectors(truncated = true).complete)
        assertFalse(DwEligibleInspectors(search = "a").complete)
    }

    @Test
    fun `the search term is stripped Python's way, so both sides agree about emptiness`() {
        assertNull(dwInspectorSearchTerm(null))
        assertNull(dwInspectorSearchTerm("   "))
        assertNull(dwInspectorSearchTerm(" "))
        assertEquals("sharma", dwInspectorSearchTerm("  sharma  "))
    }

    @Test
    fun `the term is clamped to the server's max_length and never splits a surrogate pair`() {
        assertEquals(120, DW_INSPECTOR_SEARCH_MAX)
        val long = "a".repeat(DW_INSPECTOR_SEARCH_MAX + 40)
        assertEquals(DW_INSPECTOR_SEARCH_MAX, dwInspectorSearchTerm(long)?.length)
        val split = "a".repeat(DW_INSPECTOR_SEARCH_MAX - 1) + "🧵"
        assertEquals(DW_INSPECTOR_SEARCH_MAX - 1, dwInspectorSearchTerm(split)?.length)
    }

    @Test
    fun `a save whose answer was lost never claims nothing was changed`() {
        val lost = dwInspectionFailureMessage(null, null, DwInspectionAttempt.SAVE)
        assertFalse(lost.contains("Nothing has been changed"))
        assertTrue(lost.contains("may already have been saved"))

        val read = dwInspectionFailureMessage(null, null, DwInspectionAttempt.READ)
        assertTrue("a read that failed changed nothing by construction", read.contains("Nothing has been changed."))
    }

    @Test
    fun `a 5xx is never reported as being offline`() {
        val said = dwInspectionFailureMessage(500, null, DwInspectionAttempt.READ)
        assertTrue(said.contains("This is not a connection problem."))
        assertFalse(said.contains("Could not connect"))
    }

    @Test
    fun `the 403 names both doors, because either one of them may be the refusal`() {
        val notAnInspector = "The inspection surface belongs to the accounts that may be appointed " +
            "to inspect a workshop: the Inspector / Reviewer tier, Ministry Admins, admins and the " +
            "master admin."
        val said = dwInspectionFailureMessage(403, notAnInspector, DwInspectionAttempt.READ)
        assertTrue(said.startsWith("$notAnInspector "))
        assertTrue(said.contains("decides who inspects what"))
        assertTrue(
            "a rule is not a fault, and the sentence has to say whose rule it is",
            said.contains("a Ministry Admin, an admin or the master admin decides who inspects what.")
        )
        assertFalse(said.contains("only an Inspector / Reviewer"))
        assertTrue(said.contains("read by the accounts appointed to inspect it"))
    }

    @Test
    fun `the server's own 422 is passed through rather than talked over`() {
        val detail = "Meena Iyer (meena@example.org) is barred by the platform access list, so they " +
            "cannot sign in at all. Clear that on the access screen first; an inspection row on its " +
            "own would leave this screen saying they are inspecting while they are shown a refusal " +
            "at the door. Nothing was changed."
        val said = dwInspectionFailureMessage(422, detail, DwInspectionAttempt.SAVE)
        assertTrue(said.contains(detail))
        assertEquals(
            "the client must add exactly one lead-in and no second verdict",
            1,
            Regex("Nothing was changed\\.").findAll(said).count()
        )
    }

    @Test
    fun `a punctuation-less server sentence does not run into the clause after it`() {
        val said = dwInspectionFailureMessage(403, "Admin access required", DwInspectionAttempt.SAVE)
        assertTrue(said.startsWith("Admin access required. A workshop under inspection"))
    }

    @Test
    fun `the deployment-predates-the-feature probe is a 404 and nothing else`() {
        assertTrue(dwInspectionAdministrationMissing(404))
        assertFalse(dwInspectionAdministrationMissing(403))
        assertFalse("nothing answered is not a missing route", dwInspectionAdministrationMissing(null))
    }

    @Test
    fun `a server that has never heard of truncated decodes as saying nothing about it`() {
        val decoded = json.decodeFromString(
            DwEligibleInspectorListDto.serializer(),
            """{"users":[{"id":"u-a","name":"A","email":"a@example.org","role":"INSPECTOR"}]}"""
        )
        assertFalse(decoded.truncated)
        assertEquals(1, decoded.users.size)
    }

    @Test
    fun `an inspection payload from a server predating readOnly still decodes, and reads as read-only`() {
        val decoded = json.decodeFromString(
            DwInspectionDetailDto.serializer(),
            """{"id":"w-1","title":"Sambalpuri Ikat","stages":{},"completeness":{}}"""
        )
        assertNull(decoded.readOnly)
        assertTrue(dwInspectionIsReadOnly(decoded.readOnly))
    }

    @Test
    fun `an inspection payload decodes readOnly and the keys it deliberately does not carry`() {
        val decoded = json.decodeFromString(
            DwInspectionDetailDto.serializer(),
            """{"id":"w-1","title":"T","readOnly":true,"schemaVersion":"v9","stages":{},"completeness":{}}"""
        )
        assertEquals(true, decoded.readOnly)
        assertEquals("v9", decoded.schemaVersion)
        val ahead = json.decodeFromString(
            DwInspectionDetailDto.serializer(),
            """{"id":"w-1","title":"T","readOnly":true,"somethingNew":42,"stages":{},"completeness":{}}"""
        )
        assertEquals("w-1", ahead.id)
    }

    @Test
    fun `an assignment row decodes with a null assignedAt rather than throwing`() {
        val decoded = json.decodeFromString(
            DwInspectorListDto.serializer(),
            """{"inspectors":[{"userId":"u-a","name":"A","email":"a@example.org","role":"INSPECTOR","assignedAt":null}]}"""
        )
        assertNull(decoded.inspectors.single().assignedAt)
    }

    private fun field(
        key: String,
        type: String,
        label: String = key,
        unit: String = "",
        options: List<EnumOption> = emptyList(),
        refModel: String = "",
        refFilterBy: String = "",
    ) = FieldDto(
        key = key, label = label, type = type, unit = unit,
        options = options, refModel = refModel, refFilterBy = refFilterBy,
    )

    private fun entity(vararg fields: FieldDto) =
        EntityDto(key = "e", name = "E", title = "E", fields = fields.toList())

    private val schema = SchemaResponse(version = "v1")

    private fun row(vararg pairs: Pair<String, JsonElement>): Map<String, JsonElement> = mapOf(*pairs)

    @Test
    fun `a media field with files is neither empty nor a photograph - it is a count`() {
        val e = entity(field("motifs", "IMAGE_LIST"), field("cover", "IMAGE"))
        val many = dwInspectionFieldReading(
            schema, e, e.fields[0],
            row("motifs" to buildJsonArray { add(JsonPrimitive("m-1")); add(JsonPrimitive("m-2")) })
        )
        assertEquals(DwInspectionReading.Media(2), many)
        val one = dwInspectionFieldReading(
            schema, e, e.fields[1],
            row("cover" to JsonPrimitive("m-9"))
        )
        assertEquals(DwInspectionReading.Media(1), one)
    }

    @Test
    fun `a media field with nothing in it is EMPTY, so a gap and a withheld file never collapse`() {
        val e = entity(field("cover", "IMAGE"))
        assertEquals(DwInspectionReading.Empty, dwInspectionFieldReading(schema, e, e.fields[0], row()))
        assertEquals(
            DwInspectionReading.Empty,
            dwInspectionFieldReading(schema, e, e.fields[0], row("cover" to JsonPrimitive("")))
        )
    }

    @Test
    fun `an enum token the registry no longer offers is printed, never dropped`() {
        val e = entity(field("loom", "ENUM", options = listOf(EnumOption(value = "PIT", label = "Pit loom"))))
        assertEquals(
            DwInspectionReading.Text("Pit loom"),
            dwInspectionFieldReading(schema, e, e.fields[0], row("loom" to JsonPrimitive("PIT")))
        )
        assertEquals(
            DwInspectionReading.Text("FRAME"),
            dwInspectionFieldReading(schema, e, e.fields[0], row("loom" to JsonPrimitive("FRAME")))
        )
    }

    @Test
    fun `a shared enum is resolved through the registry table when the field inlines nothing`() {
        val withEnums = SchemaResponse(
            version = "v1",
            enums = mapOf("LOOM" to listOf(EnumOption(value = "PIT", label = "Pit loom")))
        )
        val e = entity(FieldDto(key = "loom", label = "Loom", type = "ENUM", enumName = "LOOM"))
        assertEquals(
            DwInspectionReading.Text("Pit loom"),
            dwInspectionFieldReading(withEnums, e, e.fields[0], row("loom" to JsonPrimitive("PIT")))
        )
    }

    @Test
    fun `a reference is named from the sibling key hydration wrote, never from the cuid`() {
        val e = entity(
            field("artisanRef", "REF", label = "Artisan", refModel = "Artisan"),
            field("artisanName", "TEXT", label = "Artisan name"),
        )
        assertEquals(
            DwInspectionReading.Text("Sita Devi"),
            dwInspectionFieldReading(
                schema, e, e.fields[0],
                row(
                    "artisanRef" to JsonPrimitive("clx123"),
                    "artisanName" to JsonPrimitive("Sita Devi"),
                )
            )
        )
    }

    @Test
    fun `an unnameable reference prints a sentence and never the raw id`() {
        val e = entity(field("artisanRef", "REF", refModel = "Artisan"))
        val reading = dwInspectionFieldReading(
            schema, e, e.fields[0],
            row("artisanRef" to JsonPrimitive("clx123"))
        )
        assertEquals(DwInspectionReading.Text("A linked record"), reading)
        assertFalse((reading as DwInspectionReading.Text).text.contains("clx123"))
    }

    @Test
    fun `documentedFor names a cascade parent and never an ordinary reference`() {
        val parentOnly = entity(
            field("productRef", "REF", refModel = "ProductDocumentation"),
            field("documentedFor", "TEXT"),
            field("processRef", "REF", refModel = "Process", refFilterBy = "productRef"),
        )
        assertEquals(
            DwInspectionReading.Text("Ikat saree"),
            dwInspectionFieldReading(
                schema, parentOnly, parentOnly.fields[0],
                row(
                    "productRef" to JsonPrimitive("p-1"),
                    "documentedFor" to JsonPrimitive("Ikat saree"),
                )
            )
        )
        assertEquals(
            DwInspectionReading.Text("A linked record"),
            dwInspectionFieldReading(
                schema, parentOnly, parentOnly.fields[2],
                row(
                    "processRef" to JsonPrimitive("pr-1"),
                    "documentedFor" to JsonPrimitive("Ikat saree"),
                )
            )
        )
    }

    @Test
    fun `a boolean false is an answer and not a blank`() {
        val e = entity(field("hasPower", "BOOL"))
        assertEquals(
            DwInspectionReading.Text("No"),
            dwInspectionFieldReading(schema, e, e.fields[0], row("hasPower" to JsonPrimitive(false)))
        )
        assertEquals(
            DwInspectionReading.Text("Yes"),
            dwInspectionFieldReading(schema, e, e.fields[0], row("hasPower" to JsonPrimitive("yes")))
        )
    }

    @Test
    fun `a unit is appended to the value it belongs to`() {
        val e = entity(field("width", "DECIMAL", unit = "cm"))
        assertEquals(
            DwInspectionReading.Text("44.5 cm"),
            dwInspectionFieldReading(schema, e, e.fields[0], row("width" to JsonPrimitive("44.5")))
        )
    }

    @Test
    fun `a coordinate prints six decimals, and its accuracy only when the device reported one`() {
        val e = entity(field("where", "GEO"))
        val withAccuracy = buildJsonObject {
            put("lat", 20.2961); put("lon", 85.8245); put("accuracy", 12.4)
        }
        assertEquals(
            DwInspectionReading.Text("20.296100, 85.824500 (±12 m)"),
            dwInspectionFieldReading(schema, e, e.fields[0], row("where" to withAccuracy))
        )
        val without = buildJsonObject { put("lat", 20.2961); put("lon", 85.8245) }
        assertEquals(
            DwInspectionReading.Text("20.296100, 85.824500"),
            dwInspectionFieldReading(schema, e, e.fields[0], row("where" to without))
        )
    }

    @Test
    fun `a narrative that was opened and left alone is empty, matching the server and the browser`() {
        val e = entity(field("intro", "RICH_TEXT"))
        val emptyDoc: JsonObject = buildJsonObject {
            put("blocks", buildJsonArray { add(buildJsonObject { put("kind", "PARAGRAPH"); put("spans", buildJsonArray { }) }) })
        }
        assertEquals(DwInspectionReading.Empty, dwInspectionFieldReading(schema, e, e.fields[0], row("intro" to emptyDoc)))
    }

    @Test
    fun `unanswered fields are counted rather than drawn, and media with files counts as answered`() {
        val e = entity(
            field("name", "TEXT"),
            field("notes", "LONG_TEXT"),
            field("cover", "IMAGE"),
        )
        val answered = row(
            "name" to JsonPrimitive("Sambalpuri"),
            "cover" to JsonPrimitive("m-1"),
        )
        assertEquals(1, dwInspectionUnansweredCount(schema, e, e.fields, answered))
        assertEquals(3, dwInspectionUnansweredCount(schema, e, e.fields, row()))
    }
}
