package com.designprototype.workshop.data

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkshopDraftDowngradeTest {

    private lateinit var dir: File

    private val draftFile: File get() = File(dir, "draft.json")
    private val setAside: List<File>
        get() = dir.listFiles()?.filter { it.name.startsWith("draft.newer-") }.orEmpty()

    private val futureDraft = """
        {"schemaVersion":${WORKSHOP_DRAFT_SCHEMA_VERSION + 1},"workshopId":"w-1","title":"Bagru block print",
         "createdAt":"2026-08-01T09:00:00Z","updatedAt":"2026-08-12T17:41:00Z",
         "reviewerName":"a field this build has never heard of","stages":{}}
    """.trimIndent()

    @Before
    fun setUp() {
        dir = File.createTempFile("workshop-draft", "").let { probe ->
            probe.delete()
            probe.mkdirs()
            probe
        }
        WorkshopDraftStore.takeAlert()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        WorkshopDraftStore.takeAlert()
    }

    @Test
    fun `a newer draft is still readable, degraded`() {
        draftFile.writeText(futureDraft)
        val read = WorkshopDraftStore.readDraftIn(dir, "w-1")
        assertNotNull("a document from the future must not be refused", read)
        assertEquals("Bagru block print", read!!.title)
        assertEquals(WORKSHOP_DRAFT_SCHEMA_VERSION + 1, read.schemaVersion)
        assertNull("nothing is damaged, so nothing may be quarantined", WorkshopDraftStore.takeAlert())
        assertTrue(setAside.isEmpty())
    }

    @Test
    fun `saving over a newer draft keeps the newer bytes`() {
        draftFile.writeText(futureDraft)
        val current = WorkshopDraftStore.readDraftIn(dir, "w-1")!!

        WorkshopDraftStore.writeDraftIn(
            dir,
            current.copy(schemaVersion = WORKSHOP_DRAFT_SCHEMA_VERSION, title = "Bagru block print (edited)"),
            current,
        )

        assertEquals("the newer document must be kept, exactly once", 1, setAside.size)
        val kept = setAside.single()
        assertEquals(futureDraft, kept.readText())
        assertTrue(
            "the kept file must name the version it came from: ${kept.name}",
            kept.name.contains("v${WORKSHOP_DRAFT_SCHEMA_VERSION + 1}"),
        )
        val rewritten = Json.parseToJsonElement(draftFile.readText()).jsonObject
        assertEquals(
            WORKSHOP_DRAFT_SCHEMA_VERSION,
            rewritten.getValue("schemaVersion").jsonPrimitive.content.toInt(),
        )
        assertEquals("Bagru block print (edited)", rewritten.getValue("title").jsonPrimitive.content)
    }

    @Test
    fun `the designer is told, and told what to do about it`() {
        draftFile.writeText(futureDraft)
        val current = WorkshopDraftStore.readDraftIn(dir, "w-1")!!
        WorkshopDraftStore.writeDraftIn(dir, current, current)

        val alert = WorkshopDraftStore.takeAlert()
        assertNotNull("a downgrade must not be silent", alert)
        assertTrue(alert!!.contains("newer version"))
        assertTrue("the alert must promise nothing was deleted", alert.contains("Nothing has been deleted"))
        assertTrue("and name the way back", alert.contains("Install the newer version again"))
        assertNull("taken exactly once", WorkshopDraftStore.takeAlert())
    }

    @Test
    fun `a second save does not quarantine again`() {
        draftFile.writeText(futureDraft)
        val current = WorkshopDraftStore.readDraftIn(dir, "w-1")!!
        WorkshopDraftStore.writeDraftIn(dir, current.copy(schemaVersion = WORKSHOP_DRAFT_SCHEMA_VERSION), current)
        WorkshopDraftStore.takeAlert()

        val afterFirst = WorkshopDraftStore.readDraftIn(dir, "w-1")!!
        assertEquals(WORKSHOP_DRAFT_SCHEMA_VERSION, afterFirst.schemaVersion)
        WorkshopDraftStore.writeDraftIn(dir, afterFirst.copy(title = "again"), afterFirst)

        assertEquals(1, setAside.size)
        assertNull(WorkshopDraftStore.takeAlert())
    }

    @Test
    fun `an ordinary save of a current draft is untouched by the check`() {
        val draft = WorkshopDraft(workshopId = "w-1", title = "Bagru", createdAt = "t", updatedAt = "t")
        WorkshopDraftStore.writeDraftIn(dir, draft, null)
        val read = WorkshopDraftStore.readDraftIn(dir, "w-1")!!
        WorkshopDraftStore.writeDraftIn(dir, read.copy(title = "Bagru II"), read)

        assertTrue("nothing may be set aside", setAside.isEmpty())
        assertNull(WorkshopDraftStore.takeAlert())
        assertEquals("Bagru II", WorkshopDraftStore.readDraftIn(dir, "w-1")!!.title)
    }

    @Test
    fun `an older draft is migrated up and never set aside`() {
        draftFile.writeText("""{"workshopId":"w-1","title":"From a build with no version key","stages":{}}""")
        val read = WorkshopDraftStore.readDraftIn(dir, "w-1")!!
        assertEquals(WORKSHOP_DRAFT_SCHEMA_VERSION, read.schemaVersion)
        WorkshopDraftStore.writeDraftIn(dir, read, read)
        assertTrue(setAside.isEmpty())
        assertNull(WorkshopDraftStore.takeAlert())
    }
}
