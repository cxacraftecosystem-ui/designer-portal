package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.dwLocalRowPassesKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TYPE A DESIGNER PICKS IN A COURTYARD, AND THE THREE PLACES IT HAD TO SURVIVE.
 *
 * ── WHAT WAS WRONG ──────────────────────────────────────────────────────────────────────────────
 *
 * `POST /design-workshops` has taken a `workshopKind` since the create dialog grew its picker, and
 * the browser and the handset both send it — ONLINE. A workshop minted with no signal took a
 * different path: the dialog built the body, the post failed, the draft was written to the disk, and
 * `WorkshopDraft` had no field for the type, so it was dropped on the floor. `WorkshopSync` posted
 * the workshop days later WITHOUT one.
 *
 * The consequence was not hypothetical and not deferred to the sync. `WorkshopListScreen`'s type
 * filter cannot test a row whose type it does not know, so every device-only workshop was shown
 * whatever the filter said, under a sentence reading "the type is only known once they sync" — about
 * a type the designer had chosen on that same phone, minutes earlier, in a picker the app drew.
 *
 * ── WHY THIS IS A TEST AND NOT THREE READINGS OF THE SOURCE ─────────────────────────────────────
 *
 * All three legs fail SILENTLY and only offline. A draft written by an older build that decodes with
 * an exception, a field that stops round-tripping, or a sync arm that quietly drops the token again
 * all look identical on a developer's desk with a working connection: the create lands, the server
 * has the type, and nothing is wrong. They are only ever exercised in the one place nobody is
 * reading the source.
 */
class DwOfflineWorkshopKindTest {

    /**
     * `WorkshopDraftStore`'s own decoder, so this exercises the real configuration rather than a
     * default `Json` that happens to agree with it today. `ignoreUnknownKeys` is half of the
     * no-schema-rung argument and must not be assumed.
     */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // -------------------------------------------------------------------------------------------
    // 1. The disk. This is the whole of the "no schema rung is owed" claim, under test.
    // -------------------------------------------------------------------------------------------

    /**
     * A DRAFT WRITTEN BY EVERY BUILD BEFORE THIS FIELD DECODES, AND READS AS "NO TYPE STATED".
     *
     * This is the property that makes [WorkshopDraft.workshopKind] additive, and additive is the
     * entire reason it takes no rung of [WORKSHOP_DRAFT_SCHEMA_VERSION] and adds no arm to
     * `WorkshopDraftStore.migrate` — a rung is owed only to a field that moves, is renamed, or
     * changes meaning. Get this wrong and kotlinx throws `MissingFieldException` on decode, whose
     * only recovery in that file is QUARANTINE: a phone that has been out of signal for three weeks
     * comes back, updates, and every workshop on it is filed away as unreadable.
     */
    @Test
    fun `a draft from before the field decodes as no type stated`() {
        val beforeTheField = """
            {"schemaVersion":2,"workshopId":"w-1","title":"Bagru winter","templateId":"DCH_STANDARD"}
        """.trimIndent()
        val draft = json.decodeFromString(WorkshopDraft.serializer(), beforeTheField)
        assertNull(draft.workshopKind)
        assertEquals("Bagru winter", draft.title)
        // And it is NOT a version the ladder has to climb: nothing about this field moved the
        // constant, so a document at the current version stays at it.
        assertEquals(WORKSHOP_DRAFT_SCHEMA_VERSION, draft.schemaVersion)
    }

    /**
     * AND A BUILD FROM BEFORE THE FIELD READS A DRAFT THAT HAS IT, rather than quarantining one.
     *
     * The other direction of the same claim, and the one that actually bites: the app updates over
     * the air and field phones update at wildly different times, so a draft written by a NEW build is
     * routinely handed to an OLD one. `ignoreUnknownKeys` is what makes that survivable, and this
     * asserts the store's decoder really is configured with it.
     */
    @Test
    fun `a build from before the field ignores the key rather than failing`() {
        val fromANewerBuild = """
            {"schemaVersion":2,"workshopId":"w-1","title":"Bagru winter",
             "workshopKind":"DESIGN_INTERVENTION","somethingElseEntirely":42}
        """.trimIndent()
        val draft = json.decodeFromString(WorkshopDraft.serializer(), fromANewerBuild)
        assertEquals("DESIGN_INTERVENTION", draft.workshopKind)
    }

    /** And the ordinary case: what was chosen is what comes back off the disk. */
    @Test
    fun `the chosen type round-trips`() {
        val draft = WorkshopDraft(workshopId = "w-1", title = "Bagru winter", workshopKind = "SKILL_UPGRADATION")
        val reloaded = json.decodeFromString(
            WorkshopDraft.serializer(),
            json.encodeToString(WorkshopDraft.serializer(), draft)
        )
        assertEquals("SKILL_UPGRADATION", reloaded.workshopKind)
    }

    /**
     * NULL STAYS NULL ACROSS A WRITE, and does not become `""`.
     *
     * Two spellings of one state is what every later pass then disagrees about — the create dialog
     * folds a blank pick to null for exactly this reason, and a round trip that reintroduced the
     * empty string would put the difference back on the disk. It also decides what reaches the wire:
     * `ApiClient.json` leaves a null property off the request entirely, which is what keeps a
     * courtyard create compatible with an API that predates the key, and `""` would be sent.
     */
    @Test
    fun `an unanswered type stays null rather than becoming blank`() {
        val draft = WorkshopDraft(workshopId = "w-1", title = "Bagru winter")
        val reloaded = json.decodeFromString(
            WorkshopDraft.serializer(),
            json.encodeToString(WorkshopDraft.serializer(), draft)
        )
        assertNull(reloaded.workshopKind)
    }

    // -------------------------------------------------------------------------------------------
    // 2. The list screen, which is where the omission was actually visible
    // -------------------------------------------------------------------------------------------

    /**
     * A DEVICE-ONLY ROW THAT NAMES ITS TYPE IS FILTERED LIKE ANY OTHER ROW.
     *
     * This is the payoff and it does not wait for a sync: the designer picked the type on this
     * phone, so this phone can honour the filter with it.
     */
    @Test
    fun `a typed local row is filtered on the type its designer chose`() {
        assertTrue(dwLocalRowPassesKind("DESIGN_INTERVENTION", "DESIGN_INTERVENTION"))
        assertFalse(dwLocalRowPassesKind("DESIGN_INTERVENTION", "SKILL_UPGRADATION"))
    }

    /**
     * AND A ROW WITH NO TYPE IS STILL SHOWN, WHATEVER THE FILTER SAYS.
     *
     * The surviving half of the original rule, over a much smaller set: a draft written before the
     * field existed, or one started with the type left blank, has nothing for the filter to test.
     * Hiding a fortnight of fieldwork behind a filter that cannot read it is the worse of the two
     * errors, so it stays — and `unfilterableLocal` counts exactly these and says so on screen.
     *
     * BLANK IS COUNTED AS ABSENT for the same reason: a `""` left by an older build is not a token
     * any workshop can match, so testing it would hide the row — failing in the one direction this
     * rule must never fail in.
     */
    @Test
    fun `an untyped local row survives every filter`() {
        assertTrue(dwLocalRowPassesKind(null, "DESIGN_INTERVENTION"))
        assertTrue(dwLocalRowPassesKind("", "DESIGN_INTERVENTION"))
        assertTrue(dwLocalRowPassesKind("   ", "DESIGN_INTERVENTION"))
    }

    /** No filter shows everything, which is the arm that must not be swallowed by the other two. */
    @Test
    fun `no filter shows every local row`() {
        assertTrue(dwLocalRowPassesKind(null, ""))
        assertTrue(dwLocalRowPassesKind("DESIGN_INTERVENTION", ""))
        assertTrue(dwLocalRowPassesKind("SKILL_UPGRADATION", "   "))
    }
}
