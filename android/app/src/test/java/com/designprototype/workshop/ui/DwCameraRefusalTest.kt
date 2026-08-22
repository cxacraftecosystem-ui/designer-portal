package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE FOUR SENTENCES A REFUSED CAMERA CAN PRODUCE, PINNED ON THIS MACHINE.
 *
 * ── WHAT IS BEING ASSERTED, AND WHY IT IS WORTH A TEST AT ALL ─────────────────────────────────
 *
 * Not prose taste. Every claim below is a property a designer's next action depends on, and each one
 * was FALSE on one of the two surfaces before `DwCameraRefusal.kt` existed: the identity-card reader
 * had a single sentence for both situations, so it never said that Android had stopped asking and
 * never named the one page where that can be undone.
 *
 * The properties:
 *
 *  1. NEITHER SENTENCE IS EVER A DEAD END. Both must name a route that needs no camera — the picker
 *     and the typed value. This is the whole reason a refused permission is survivable in a village.
 *  2. THE BLOCKED ONE SAYS SO AND OFFERS THE PAGE. A designer who is not told that pressing the
 *     button does nothing will press it, conclude the app is broken, and be right about the symptom.
 *  3. THE DENIED ONE DOES **NOT** SEND ANYBODY TO SETTINGS. Pressing the same button again brings the
 *     prompt back, and a detour through a system screen for a prompt answerable in place is worse
 *     than useless — it is the one that teaches designers to ignore the settings sentence.
 *  4. EACH NAMES ITS OWN BUTTON. A sentence quoting a control that is not on the screen the designer
 *     is looking at is a sentence about somebody else's app.
 *
 * ── WHAT IT DOES NOT PROVE ────────────────────────────────────────────────────────────────────
 *
 * Nothing about the PLATFORM half. Whether `shouldShowRequestPermissionRationale` really separates
 * "denied once" from "Android has stopped asking" on a given OEM build is a hardware claim, and this
 * repository has no handset to make it with — the same limit `IdentityCardRecognizer`'s header states
 * about ML Kit. What can be checked here is that once the platform has answered, the right sentence
 * comes out of it; that is why [dwCameraRefusal] takes a boolean rather than a Context.
 */
class DwCameraRefusalTest {

    /** Every surface, both answers — the whole space this file can produce. */
    private fun all(): List<Pair<DwCameraUse, Boolean>> =
        DwCameraUse.entries.flatMap { use -> listOf(use to false, use to true) }

    @Test
    fun `every refusal names a route that needs no camera`() {
        all().forEach { (use, blocked) ->
            val message = dwCameraRefusal(use, blocked)
            // The alternatives clause is the enum's own and ends the sentence, so its presence is
            // checked verbatim rather than by hunting for the word "type" — a paraphrase that lost
            // one of the two routes would still pass a looser check.
            assertTrue(
                "$use blocked=$blocked must name the camera-free routes: $message",
                message.contains(use.alternatives),
            )
        }
    }

    @Test
    fun `every refusal quotes the button of the surface it came from`() {
        all().forEach { (use, blocked) ->
            assertTrue(
                "$use blocked=$blocked must quote its own button: ${dwCameraRefusal(use, blocked)}",
                dwCameraRefusal(use, blocked).contains("“${use.button}”"),
            )
        }
    }

    @Test
    fun `a blocked camera is told it is blocked and offered the settings page`() {
        DwCameraUse.entries.forEach { use ->
            val message = dwCameraRefusal(use, blocked = true)
            assertTrue("$use: must say Android will not ask again — $message", message.contains("will not ask again"))
            assertTrue("$use: must say the button does nothing — $message", message.contains("will do nothing"))
            assertTrue("$use: must name the permission settings — $message", message.contains("permission settings"))
            // BY NAME AND NOT BY POSITION. The two surfaces render the refusal in different places
            // relative to the button — one below the row, one on the screen's own error surface — so
            // "the button below" was true of one layout and wrong on the other.
            assertTrue(
                "$use: must name the button that opens them — $message",
                message.contains("the “$DW_CAMERA_SETTINGS_BUTTON” button opens them"),
            )
        }
    }

    @Test
    fun `a camera denied once is told to press again and is not sent to settings`() {
        DwCameraUse.entries.forEach { use ->
            val message = dwCameraRefusal(use, blocked = false)
            assertTrue("$use: must invite a second press — $message", message.contains("to be asked once more"))
            assertFalse("$use: must not send anybody to Settings — $message", message.contains("permission settings"))
            assertFalse("$use: must not claim Android has stopped asking — $message", message.contains("will not ask again"))
        }
    }

    @Test
    fun `the two situations never read alike, on either surface`() {
        DwCameraUse.entries.forEach { use ->
            assertNotEquals(
                "$use: denied and blocked must not be the same sentence",
                dwCameraRefusal(use, blocked = false),
                dwCameraRefusal(use, blocked = true),
            )
        }
    }

    /**
     * The two surfaces do not accidentally say the same thing either.
     *
     * A card and a QR code are photographed for different reasons and the routes that remain are
     * different routes — a typed record code is not a typed Aadhaar number. If these two ever
     * collapsed into one string it would mean a surface had been given the other's vocabulary.
     */
    @Test
    fun `the two surfaces speak about their own subject`() {
        listOf(false, true).forEach { blocked ->
            assertNotEquals(
                "blocked=$blocked: the scanner and the card reader must not share one sentence",
                dwCameraRefusal(DwCameraUse.QR_CODE, blocked),
                dwCameraRefusal(DwCameraUse.IDENTITY_CARD, blocked),
            )
        }
        assertTrue(dwCameraRefusal(DwCameraUse.QR_CODE, blocked = false).contains("code printed under the QR"))
        assertTrue(dwCameraRefusal(DwCameraUse.IDENTITY_CARD, blocked = false).contains("type the number in"))
    }

    /**
     * THE RULE [DwCameraUse] STATES, ENFORCED RATHER THAN TRUSTED.
     *
     * Every entry must offer at least one route that needs no camera, and the ones this app has are
     * the system photo picker and a typed value. A future surface added to that enum with only a lens
     * would fail here rather than shipping a feature that vanishes when somebody taps Deny.
     */
    @Test
    fun `every surface in the enum offers a picture route and a typed route`() {
        DwCameraUse.entries.forEach { use ->
            assertTrue(
                "$use must offer an already-on-this-phone picture: ${use.alternatives}",
                use.alternatives.contains("already on this phone"),
            )
            assertTrue(
                "$use must offer a typed route: ${use.alternatives}",
                use.alternatives.contains("type the"),
            )
            assertEquals(
                "$use's alternatives must end the sentence: ${use.alternatives}",
                '.',
                use.alternatives.last(),
            )
        }
    }
}
