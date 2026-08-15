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

/**
 * An older build must not claim authorship of a newer build's draft.
 *
 * `migrate`'s ladder only climbs, on purpose: a phone one release behind that opens a v3 draft decodes
 * it degraded rather than refusing it, because telling a designer their fortnight is corrupt when their
 * colleague's phone updated first is worse than showing them what this build understands. That is the
 * right READ. The write was the defect. `save`, `update` and `updateBookkeeping` re-encode the whole
 * model and forced `schemaVersion` back down to this build's constant, so the first debounced stage
 * save turned the v3 document into a well-formed v2 with v3's own fields gone — and stamped it as this
 * build's work, so no later migration rung could ever tell that anything had been dropped.
 *
 * How a phone gets there was checked rather than assumed, and it is narrow: the in-app updater only
 * ever installs a HIGHER versionCode, Android's package manager refuses a lower one over an installed
 * app, uninstalling wipes `filesDir` with the drafts in it, and the manifest sets `allowBackup` without
 * `restoreAnyVersion` (default false) so a backup from a newer build is not restored onto an older one.
 * What is left is an operator-driven downgrade — `adb install -d`, or an MDM push that keeps app data —
 * on a fleet that side-loads its own APKs. Rare; and the never-destroy rule this store is built on has
 * no exception for rare.
 */
class WorkshopDraftDowngradeTest {

    private lateinit var dir: File

    private val draftFile: File get() = File(dir, "draft.json")
    private val setAside: List<File>
        get() = dir.listFiles()?.filter { it.name.startsWith("draft.newer-") }.orEmpty()

    /**
     * A draft written by a build whose format number is one past this one, carrying a field this build
     * has never heard of. `reviewerName` stands in for whatever v3 actually adds; what matters is that
     * `ignoreUnknownKeys` drops it on decode and it therefore cannot survive a re-encode.
     */
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
        // The store's alert is a process-wide single slot; clear whatever an earlier test left in it.
        WorkshopDraftStore.takeAlert()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        WorkshopDraftStore.takeAlert()
    }

    /** The read half is unchanged and must stay unchanged: degraded, never refused. */
    @Test
    fun `a newer draft is still readable, degraded`() {
        draftFile.writeText(futureDraft)
        val read = WorkshopDraftStore.readDraftIn(dir, "w-1")
        assertNotNull("a document from the future must not be refused", read)
        assertEquals("Bagru block print", read!!.title)
        // And it still carries the ON-DISK version — the ladder does not rewrite it on the way in,
        // which is exactly what lets the write side notice without a second read of the file.
        assertEquals(WORKSHOP_DRAFT_SCHEMA_VERSION + 1, read.schemaVersion)
        assertNull("nothing is damaged, so nothing may be quarantined", WorkshopDraftStore.takeAlert())
        assertTrue(setAside.isEmpty())
    }

    /**
     * THE FINDING. Saving over a newer draft must set the newer bytes aside first, complete with the
     * field this build cannot represent.
     */
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
        // The save itself still happened — refusing to save would cost the designer the edit they just
        // made, which is not an improvement on losing a field they cannot see.
        val rewritten = Json.parseToJsonElement(draftFile.readText()).jsonObject
        assertEquals(
            WORKSHOP_DRAFT_SCHEMA_VERSION,
            rewritten.getValue("schemaVersion").jsonPrimitive.content.toInt(),
        )
        assertEquals("Bagru block print (edited)", rewritten.getValue("title").jsonPrimitive.content)
    }

    /**
     * And it is SAID. A draft set aside with nothing spoken looks exactly like a workshop that was
     * never started, which is the failure mode `quarantine` exists to avoid — and here the sentence
     * has to carry the one instruction that actually recovers the data.
     */
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

    /**
     * One quarantine per downgrade, not one per debounced save.
     *
     * Both writes stamp `schemaVersion` down the way `save`, `update` and `updateBookkeeping` all do —
     * that stamp is the thing the finding is about, and the point here is that once the newer document
     * has been set aside the store is back on its own version and stops complaining. A stage screen
     * auto-saves on every debounce; a guard that quarantined per save would fill the workshop
     * directory with copies and repeat the alert until the designer stopped typing.
     */
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

    // ── What must be left completely alone ───────────────────────────────────────────────────────

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

    /**
     * An OLDER document on disk is the ordinary upgrade path and must climb the ladder silently. The
     * guard is one-directional on purpose; making it symmetrical would quarantine every phone's drafts
     * on the release that finally adds a rung.
     */
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
