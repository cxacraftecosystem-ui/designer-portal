package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.dwLocalRowPassesKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwOfflineWorkshopKindTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `a draft from before the field decodes as no type stated`() {
        val beforeTheField = """
            {"schemaVersion":2,"workshopId":"w-1","title":"Bagru winter","templateId":"DCH_STANDARD"}
        """.trimIndent()
        val draft = json.decodeFromString(WorkshopDraft.serializer(), beforeTheField)
        assertNull(draft.workshopKind)
        assertEquals("Bagru winter", draft.title)
        assertEquals(WORKSHOP_DRAFT_SCHEMA_VERSION, draft.schemaVersion)
    }

    @Test
    fun `a build from before the field ignores the key rather than failing`() {
        val fromANewerBuild = """
            {"schemaVersion":2,"workshopId":"w-1","title":"Bagru winter",
             "workshopKind":"DESIGN_INTERVENTION","somethingElseEntirely":42}
        """.trimIndent()
        val draft = json.decodeFromString(WorkshopDraft.serializer(), fromANewerBuild)
        assertEquals("DESIGN_INTERVENTION", draft.workshopKind)
    }

    @Test
    fun `the chosen type round-trips`() {
        val draft = WorkshopDraft(workshopId = "w-1", title = "Bagru winter", workshopKind = "SKILL_UPGRADATION")
        val reloaded = json.decodeFromString(
            WorkshopDraft.serializer(),
            json.encodeToString(WorkshopDraft.serializer(), draft)
        )
        assertEquals("SKILL_UPGRADATION", reloaded.workshopKind)
    }

    @Test
    fun `an unanswered type stays null rather than becoming blank`() {
        val draft = WorkshopDraft(workshopId = "w-1", title = "Bagru winter")
        val reloaded = json.decodeFromString(
            WorkshopDraft.serializer(),
            json.encodeToString(WorkshopDraft.serializer(), draft)
        )
        assertNull(reloaded.workshopKind)
    }

    @Test
    fun `a typed local row is filtered on the type its designer chose`() {
        assertTrue(dwLocalRowPassesKind("DESIGN_INTERVENTION", "DESIGN_INTERVENTION"))
        assertFalse(dwLocalRowPassesKind("DESIGN_INTERVENTION", "SKILL_UPGRADATION"))
    }

    @Test
    fun `an untyped local row survives every filter`() {
        assertTrue(dwLocalRowPassesKind(null, "DESIGN_INTERVENTION"))
        assertTrue(dwLocalRowPassesKind("", "DESIGN_INTERVENTION"))
        assertTrue(dwLocalRowPassesKind("   ", "DESIGN_INTERVENTION"))
    }

    @Test
    fun `no filter shows every local row`() {
        assertTrue(dwLocalRowPassesKind(null, ""))
        assertTrue(dwLocalRowPassesKind("DESIGN_INTERVENTION", ""))
        assertTrue(dwLocalRowPassesKind("SKILL_UPGRADATION", "   "))
    }
}
