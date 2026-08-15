package com.designprototype.workshop.data

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The refusal that backs up the warning, on the path where the warning was all there was.
 *
 * WHAT WAS WRONG. An older build that opens a draft written by a newer one decodes it degraded —
 * `ignoreUnknownKeys` drops the fields it has never heard of — and then re-encodes the whole model
 * with `schemaVersion` forced back down to its own constant. That silent downgrade is the original
 * audit finding (2026-08-15, "an older build that opens a newer draft writes it back stamped with its
 * own schemaVersion"), and it was closed by setting the newer bytes aside before overwriting them.
 *
 * The closing left one door open. `quarantineFutureDraft` computed whether the rename had actually
 * happened, used it to choose between two sentences — the second of which told the designer the
 * draft "could not be set aside" and that they should not save any more work — and then returned
 * Unit, whereupon `writeDraftIn` overwrote the file in the next four lines. The alert was accurate
 * about the danger and powerless to prevent it, which is worse than silence: it is advice the code
 * itself ignores, given to somebody with no way to act on it. These tests hold the store to it.
 *
 * WHAT MUST REMAIN TRUE: the original defect stays closed. A newer draft is still READ rather than
 * refused (refusing would tell a designer their fortnight is corrupt because a colleague's phone
 * updated first), and where the set-aside works the save still proceeds. `WorkshopDraftDowngradeTest`
 * owns those; this file owns the branch where the rescue fails.
 */
class WorkshopDraftDowngradeRefusalTest {

    private lateinit var dir: File

    private val draftFile: File get() = File(dir, "draft.json")
    private val setAside: List<File>
        get() = dir.listFiles()?.filter { it.name.startsWith("draft.newer-") }.orEmpty()

    /**
     * A destination `renameTo` cannot have, on Windows or on Linux: an existing non-empty directory.
     * The POSIX way of staging this — chmod the parent — does nothing on the Windows host these unit
     * tests actually run on, so it would have proved the branch on neither.
     */
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
        // The store's alert is a process-wide single slot; clear whatever an earlier test left there.
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

    // ── The finding ──────────────────────────────────────────────────────────────────────────────

    /**
     * THE ASSERTION THE OLD CODE COULD NOT PASS. The newer document's own field is on disk in exactly
     * one place; if it cannot be moved somewhere safe, the only way to keep it is not to write.
     */
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
        // And the file is still legible as what it is, so reinstalling the newer build recovers it.
        assertEquals(
            WORKSHOP_DRAFT_SCHEMA_VERSION + 1,
            WorkshopDraftStore.readDraftIn(dir, "w-1")!!.schemaVersion,
        )
    }

    /**
     * A refusal that reaches the designer as a dead end is barely better than the overwrite. The
     * message travels verbatim — StageScreen's save handler shows `error.message` on the status line
     * — so it has to say what happened, that nothing was lost, and the one action that gets the work
     * back. Wording is asserted by its load-bearing clauses, not by its full text.
     */
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

    /**
     * The alert says the same thing as the exception, because they are read in different places and a
     * store that says "do not save any more work" while the save it is describing has just been
     * refused sends the designer looking for a problem that no longer exists.
     */
    @Test
    fun `the alert agrees with what actually happened`() {
        val current = readFutureDraft()
        runCatching { WorkshopDraftStore.writeDraftIn(dir, current, current) { _, _ -> blocked } }

        val alert = WorkshopDraftStore.takeAlert()
        assertNotNull("a refused save must not be silent either", alert)
        assertTrue(alert!!.contains("could not be set aside"))
        assertTrue("the alert must not contradict the refusal: $alert", alert.contains("refused"))
        assertTrue(alert.contains("Install the newer version again"))
    }

    // ── What must be left completely alone ───────────────────────────────────────────────────────

    /**
     * The original fix must still work. With the ordinary naming the rename succeeds, the newer bytes
     * are kept, and the save PROCEEDS — refusing here would cost the designer the edit they just made
     * for a danger that has already been dealt with.
     */
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

    /**
     * And an ordinary draft never goes near any of this. The blocked destination is handed in
     * deliberately: nothing may consult it unless the document on disk is from the future, or every
     * save on every device would depend on a rename that ordinary saves have no reason to perform.
     */
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
