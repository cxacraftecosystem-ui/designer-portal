package com.designprototype.workshop.data

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkshopDraftDowngradeRefusalTest {

    private lateinit var dir: File

    private val draftFile: File get() = File(dir, "draft.json")
    private val setAside: List<File>
        get() = dir.listFiles()?.filter { it.name.startsWith("draft.newer-") }.orEmpty()

    private lateinit var blocked: File

    private val futureDraft = """
        {"schemaVersion":${WORKSHOP_DRAFT_SCHEMA_VERSION + 1},"workshopId":"w-1","title":"Bagru block print",
         "createdAt":"2026-08-01T09:00:00Z","updatedAt":"2026-08-12T17:41:00Z",
         "reviewerName":"a field this build has never heard of","stages":{}}
    """.trimIndent()

    @Before
    fun setUp() {
        dir = File.createTempFile("workshop-draft-refusal", "").let { probe ->
            probe.delete()
            probe.mkdirs()
            probe
        }
        blocked = File(dir, "occupied-destination").apply {
            mkdirs()
            File(this, "in-the-way").writeText("x")
        }
        WorkshopDraftStore.takeAlert()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        WorkshopDraftStore.takeAlert()
    }

    private fun readFutureDraft(): WorkshopDraft {
        draftFile.writeText(futureDraft)
        return WorkshopDraftStore.readDraftIn(dir, "w-1")!!
    }

    @Test
    fun `a save that cannot set the newer draft aside does not overwrite it`() {
        val current = readFutureDraft()

        val failure = runCatching {
            WorkshopDraftStore.writeDraftIn(
                dir,
                current.copy(schemaVersion = WORKSHOP_DRAFT_SCHEMA_VERSION, title = "Bagru block print (edited)"),
                current,
            ) { _, _ -> blocked }
        }.exceptionOrNull()

        assertNotNull("the save must be refused, not merely warned about", failure)
        assertEquals("the newer bytes must be exactly as they were", futureDraft, draftFile.readText())
        assertTrue("nothing may claim to have kept them", setAside.isEmpty())
        assertEquals(
            WORKSHOP_DRAFT_SCHEMA_VERSION + 1,
            WorkshopDraftStore.readDraftIn(dir, "w-1")!!.schemaVersion,
        )
    }

    @Test
    fun `the refusal tells the designer what to do about it`() {
        val current = readFutureDraft()

        val message = runCatching {
            WorkshopDraftStore.writeDraftIn(dir, current, current) { _, _ -> blocked }
        }.exceptionOrNull()?.message

        assertNotNull(message)
        assertTrue("it must name the cause: $message", message!!.contains("newer version"))
        assertTrue("it must not leave them fearing a loss: $message", message.contains("Nothing has been lost"))
        assertTrue("and it must name the way back: $message", message.contains("Install the newer version again"))
    }

    @Test
    fun `the alert agrees with what actually happened`() {
        val current = readFutureDraft()
        runCatching { WorkshopDraftStore.writeDraftIn(dir, current, current) { _, _ -> blocked } }

        val alert = WorkshopDraftStore.takeAlert()
        assertNotNull("a refused save must not be silent either", alert)
        assertTrue(alert!!.contains("could not be set aside"))
        assertTrue("the alert must not contradict the refusal: $alert", alert.contains("this save was stopped"))
        assertTrue(alert.contains("Install the newer version again"))
    }

    @Test
    fun `the rescue that works still lets the save through`() {
        val current = readFutureDraft()

        WorkshopDraftStore.writeDraftIn(
            dir,
            current.copy(schemaVersion = WORKSHOP_DRAFT_SCHEMA_VERSION, title = "Bagru block print (edited)"),
            current,
        )

        assertEquals(1, setAside.size)
        assertEquals(futureDraft, setAside.single().readText())
        assertEquals("Bagru block print (edited)", WorkshopDraftStore.readDraftIn(dir, "w-1")!!.title)
    }

    @Test
    fun `an ordinary save is untouched by the refusal`() {
        val draft = WorkshopDraft(workshopId = "w-1", title = "Bagru", createdAt = "t", updatedAt = "t")
        WorkshopDraftStore.writeDraftIn(dir, draft, null) { _, _ -> blocked }
        val read = WorkshopDraftStore.readDraftIn(dir, "w-1")!!
        WorkshopDraftStore.writeDraftIn(dir, read.copy(title = "Bagru II"), read) { _, _ -> blocked }

        assertEquals("Bagru II", WorkshopDraftStore.readDraftIn(dir, "w-1")!!.title)
        assertTrue(setAside.isEmpty())
    }
}
