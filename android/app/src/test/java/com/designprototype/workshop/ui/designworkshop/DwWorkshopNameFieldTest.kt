package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.liveFields
import com.designprototype.workshop.ui.SelectCreateAction
import com.designprototype.workshop.ui.WorkshopListKind
import com.designprototype.workshop.ui.WorkshopListState
import com.designprototype.workshop.ui.workshopListNotice
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DwWorkshopNameFieldTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val schema: SchemaResponse by lazy {
        val asset = File("src/main/assets/design-workshop-schema.json")
        assertTrue(
            "the bundled registry is missing — it is what the handset renders from on first launch",
            asset.exists()
        )
        json.decodeFromString(SchemaResponse.serializer(), asset.readText(Charsets.UTF_8))
    }

    private val allLiveFields: List<Pair<String, FieldDto>>
        get() = schema.stages.flatMap { stage ->
            stage.entities.flatMap { ent: EntityDto -> ent.liveFields.map { "${ent.key}.${it.key}" to it } }
        }

    @Test
    fun `the role matches exactly one field in the shipped registry`() {
        val matched = allLiveFields.filter { (_, field) -> dwOwnWorkshopTitleRole(field) }.map { it.first }
        assertEquals(
            "a second `workshopTitle` would put a list of design-workshop names on a box about " +
                "something else — read `dwOwnWorkshopTitleRole` before widening this",
            listOf("workshopSetup.workshopTitle"),
            matched
        )
    }

    @Test
    fun `the reference role's keys are not the workshop's own name`() {
        for (key in listOf(
            "documentedAtWorkshop",
            "craftDocumentedAtWorkshop",
            "interviewDocumentedAtWorkshop"
        )) {
            assertFalse(key, dwOwnWorkshopTitleRole(FieldDto(key = key, type = "TEXT")))
        }
    }

    @Test
    fun `the role reads the raw type token and skips a retired field`() {
        assertTrue(dwOwnWorkshopTitleRole(FieldDto(key = "workshopTitle", type = "TEXT")))
        assertFalse(dwOwnWorkshopTitleRole(FieldDto(key = "workshopTitle", type = "LONG_TEXT")))
        assertFalse(
            "an unknown token degrades to TEXT at render time and must not reach this control",
            dwOwnWorkshopTitleRole(FieldDto(key = "workshopTitle", type = "COLOUR_PICKER"))
        )
        assertFalse(
            dwOwnWorkshopTitleRole(FieldDto(key = "workshopTitle", type = "TEXT", deprecated = true))
        )
    }

    @Test
    fun `the create row quotes the term it will store`() {
        assertEquals("Use “Bagru winter 2026” as the name", dwWorkshopNameCreateLabel("Bagru winter 2026"))
        assertEquals("Use “Bagru winter 2026” as the name", dwWorkshopNameCreateLabel("  Bagru winter 2026 "))
        assertFalse(dwWorkshopNameCreateLabel("Ajrakh")!!.startsWith("Create"))
    }

    @Test
    fun `an empty box draws no create row, and a fixed label still does`() {
        assertNull(dwWorkshopNameCreateLabel(""))
        assertNull(dwWorkshopNameCreateLabel("   "))

        val fixed = SelectCreateAction("Create a new artisan") { }
        assertEquals("Create a new artisan", fixed.label(""))
        assertEquals("Create a new artisan", fixed.label("Ram Kumar"))
    }

    @Test
    fun `the stored name is always the first row, with a hint saying where it came from`() {
        val rows = dwWorkshopNameOptions("Kutch spring sitting", listOf("Ajrakh 2026", "Bagru 2025"))
        assertEquals(listOf("Kutch spring sitting", "Ajrakh 2026", "Bagru 2025"), rows.map { it.label })
        assertEquals("already on this workshop", rows.first().hint)
        assertEquals("Kutch spring sitting", rows.first().value)
    }

    @Test
    fun `a stored name already on offer is not duplicated`() {
        val rows = dwWorkshopNameOptions("Ajrakh 2026", listOf("Ajrakh 2026", "Bagru 2025"))
        assertEquals(listOf("Ajrakh 2026", "Bagru 2025"), rows.map { it.label })
        assertNull("the offered row keeps its own shape", rows.first().hint)
    }

    @Test
    fun `an empty field adds no row`() {
        assertEquals(listOf("Ajrakh 2026"), dwWorkshopNameOptions("  ", listOf("Ajrakh 2026")).map { it.label })
    }

    @Test
    fun `the offer is deduplicated and keeps the server's order`() {
        val offer = dwWorkshopNamesOnRecord(
            listOf(" Bagru 2026 ", "Ajrakh 2026", "Bagru 2026", "", "   "),
            maxLength = 0
        )
        assertEquals(listOf("Bagru 2026", "Ajrakh 2026"), offer.names)
        assertEquals(0, offer.withheld)
    }

    @Test
    fun `an over-length name is withheld and said out loud`() {
        val offer = dwWorkshopNamesOnRecord(listOf("Ajrakh 2026", "A".repeat(40)), maxLength = 20)
        assertEquals(listOf("Ajrakh 2026"), offer.names)
        assertEquals(1, offer.withheld)
        assertEquals("1 name over 20 characters hidden.", dwWorkshopNamesWithheldLine(1, 20))
        assertEquals("2 names over 20 characters hidden.", dwWorkshopNamesWithheldLine(2, 20))
        assertNull(dwWorkshopNamesWithheldLine(0, 20))
    }

    @Test
    fun `no declared bound withholds nothing`() {
        val offer = dwWorkshopNamesOnRecord(listOf("A".repeat(400)), maxLength = 0)
        assertEquals(1, offer.names.size)
        assertEquals(0, offer.withheld)
    }

    @Test
    fun `the offer line names the scope, the escape and both numbers`() {
        assertEquals(
            "Names from workshops you can open. Type to search, or add a new one.",
            dwWorkshopNameOfferLine(workshopKind = "", shown = 12, total = 12)
        )
        assertEquals(
            "Names from workshops of this type. Type to search, or add a new one.",
            dwWorkshopNameOfferLine(workshopKind = "DESIGN_INTERVENTION", shown = 12, total = 12)
        )
        assertEquals(
            "Names from workshops you can open. Type to search, or add a new one. " +
                "Showing 80 of 196.",
            dwWorkshopNameOfferLine(workshopKind = "", shown = 80, total = 196)
        )
    }

    @Test
    fun `the unsearched line says the box reaches past what is drawn`() {
        assertTrue(
            dwWorkshopNameOfferLine(workshopKind = "", shown = 80, total = 196)
                .contains("Type to search")
        )
    }

    @Test
    fun `a searched line says the whole list was searched, and keeps the escape`() {
        assertEquals(
            "Names from workshops you can open. All searched. Type a new one if it isn't here.",
            dwWorkshopNameOfferLine(workshopKind = "", shown = 12, total = 12, searching = true)
        )
        assertEquals(
            "Names from workshops of this type. All searched. Type a new one if it isn't here. Showing 12 of 40.",
            dwWorkshopNameOfferLine(
                workshopKind = "DESIGN_INTERVENTION",
                shown = 12,
                total = 40,
                searching = true
            )
        )
    }

    @Test
    fun `an unanswered read states no cut`() {
        assertFalse(dwWorkshopNameOfferLine("", shown = 0, total = 0).contains("Showing"))
        assertFalse(dwWorkshopNameOfferLine("", shown = 0, total = 196).contains("Showing"))
        assertFalse(dwWorkshopNameOfferLine("", shown = 12, total = 0).contains("Showing"))
    }

    @Test
    fun `an empty answer to a typed term talks about the term`() {
        assertEquals(
            "No workshop name matches “bagru”.",
            dwWorkshopNameNotice(
                WorkshopListState.Listed(count = 0, total = 0),
                online = true,
                query = "bagru"
            )
        )
        assertEquals(
            "No workshop name matches “bagru”.",
            dwWorkshopNameNotice(
                WorkshopListState.Listed(count = 0, total = 0),
                online = true,
                query = "  bagru  "
            )
        )
    }

    @Test
    fun `a failed or pending read is never reported as a term that did not match`() {
        val failedOffline = dwWorkshopNameNotice(WorkshopListState.Failed, online = false, query = "bagru")
        val failedOnline = dwWorkshopNameNotice(WorkshopListState.Failed, online = true, query = "bagru")
        val loading = dwWorkshopNameNotice(WorkshopListState.Loading, online = true, query = "bagru")
        listOf(failedOffline, failedOnline, loading).forEach { line ->
            assertNotNull(line)
            assertFalse(line!!.contains("No workshop name matches"))
        }
        assertEquals(
            workshopListNotice(WorkshopListState.Failed, WorkshopListKind.DESIGN, false),
            failedOffline
        )
        assertEquals(
            workshopListNotice(WorkshopListState.Failed, WorkshopListKind.DESIGN, true),
            failedOnline
        )
    }

    @Test
    fun `an empty answer with an empty box still names the scope`() {
        assertEquals(
            workshopListNotice(
                WorkshopListState.Listed(count = 0, total = 0),
                WorkshopListKind.DESIGN,
                true
            ),
            dwWorkshopNameNotice(
                WorkshopListState.Listed(count = 0, total = 0),
                online = true,
                query = ""
            )
        )
    }

    @Test
    fun `a list with rows prints no notice`() {
        assertNull(
            dwWorkshopNameNotice(
                WorkshopListState.Listed(count = 3, total = 3),
                online = true,
                query = "bagru"
            )
        )
        assertNull(
            dwWorkshopNameNotice(
                WorkshopListState.Listed(count = 3, total = 3),
                online = true,
                query = ""
            )
        )
    }
}
