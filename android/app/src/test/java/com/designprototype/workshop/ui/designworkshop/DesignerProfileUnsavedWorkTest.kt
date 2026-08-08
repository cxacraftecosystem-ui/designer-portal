package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DesignerProfileDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A back gesture must not take the biography with it — and must not nag when there is nothing to keep.
 *
 * ── WHAT THIS DEFENDS ──────────────────────────────────────────────────────────────────────────────
 *
 * `DesignerProfileScreen` had no leave guard of any kind: no `BackHandler`, no registration with the
 * app's `UnsavedGuard`, no `onDispose` persist. The 22 stages next door survive Back because a stage
 * persists from its own `onDispose`, and every record form registers with the shared guard, so this
 * screen was the one place in the app where an edge swipe silently dropped what had been typed. It
 * holds the longest free-text box in the app — `minLines = 5`, the paragraph that prints under
 * "Designer's profile" in stage 3 of every report — on a 6.4-inch handset where Back is a thumb
 * gesture from the screen edge rather than a deliberate click on a small target.
 *
 * The rule is a diff against the snapshot the server last confirmed, not a flag the controls raise.
 * The web raises its flag from `onInput` and then has to re-raise it BY HAND for every control that
 * fires no input event — its state dropdown, its date picker and both media slots each carry a
 * manual `markDirty`. This tests that the diff answers the same questions without that list.
 */
class DesignerProfileUnsavedWorkTest {

    /** A stored profile as the server serves it, including the shapes the form has to convert. */
    private fun stored(
        biography: String? = "Twelve years of block-printing with the Bagru cluster.",
        website: String? = "https://example.org",
        photoMediaId: String? = null
    ) = DesignerProfileDto(
        id = "p-1",
        userId = "u-1",
        displayName = "A. Sharma",
        institution = "NID",
        // An Int on the wire and text in the box: the box needs a representation for "cleared on the
        // way to typing a different number", which an Int has not got.
        experienceYears = 12,
        biography = biography,
        website = website,
        state = "Rajasthan",
        city = "Bagru",
        photoMediaId = photoMediaId,
        // A full ISO timestamp, which the form truncates to a LocalDate. If that conversion were not
        // stable, merely opening the screen would look like an edit.
        empanelmentDate = "2024-11-04T00:00:00+00:00",
        empanelmentNo = "EMP/2024/118"
    )

    // ── Nothing typed ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a profile that was only read has nothing to save`() {
        val loaded = stored().toForm()

        assertFalse(
            "opening a profile and pressing Back must not raise a prompt",
            designerProfileHasUnsavedEdits(loaded, loaded)
        )
    }

    @Test
    fun `the conversion the load performs is stable`() {
        // Both halves of the comparison are seeded from the SAME conversion, so this is what
        // guarantees the screen cannot open dirty. It is asserted rather than assumed because the
        // conversion is not the identity: an Int becomes text, a timestamp becomes a LocalDate, and
        // every null becomes "".
        assertEquals(stored().toForm(), stored().toForm())
        assertEquals("12", stored().toForm().experienceYears)
        assertEquals("2024-11-04", stored().toForm().empanelmentDate?.toString())
        assertEquals("", DesignerProfileDto().toForm().biography)
    }

    @Test
    fun `a designer who has never saved a profile is not asked to`() {
        // The ordinary state of a designer sent here for the first time: no row, so an empty form.
        // Greeting them with an unsaved-changes prompt on the way out of a page they only looked at
        // is how a guard is taught to be dismissed — and it has to still mean something ten minutes
        // later when there IS a paragraph in the box.
        val empty = ProfileForm()

        assertFalse(designerProfileHasUnsavedEdits(empty, empty))
    }

    // ── Typed ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a typed biography is unsaved work`() {
        val loaded = stored(biography = "").toForm()
        val typed = loaded.copy(biography = "Twelve years of block-printing with the Bagru cluster.")

        assertTrue(designerProfileHasUnsavedEdits(typed, loaded))
    }

    @Test
    fun `typing a word and deleting it again is not unsaved work`() {
        val loaded = stored().toForm()
        val typed = loaded.copy(biography = loaded.biography + " Currently")
        val retracted = typed.copy(biography = loaded.biography)

        assertTrue(designerProfileHasUnsavedEdits(typed, loaded))
        assertFalse(
            "a retraction leaves nothing to save; prompting there trains the prompt away",
            designerProfileHasUnsavedEdits(retracted, loaded)
        )
    }

    @Test
    fun `a photograph uploaded but not saved is unsaved work`() {
        // The upload lands the file and puts its id in the form; the profile still points at the old
        // one until Save. The shared prompt says "including any recordings or media you just
        // captured" and this is the case that has to make that sentence true.
        val loaded = stored(photoMediaId = null).toForm()
        val afterUpload = loaded.copy(photoMediaId = "media-9")

        assertTrue(designerProfileHasUnsavedEdits(afterUpload, loaded))
    }

    @Test
    fun `a control that fires no keystroke is covered too`() {
        // The state dropdown and the date picker are precisely the controls the web has to mark
        // dirty by hand. A diff cannot forget them, which is the whole reason it is a diff.
        val loaded = stored().toForm()

        assertTrue(designerProfileHasUnsavedEdits(loaded.copy(state = "Gujarat"), loaded))
        assertTrue(
            designerProfileHasUnsavedEdits(
                loaded.copy(empanelmentDate = loaded.empanelmentDate?.plusDays(1)),
                loaded
            )
        )
    }

    // ── After a save ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the server's normalisation does not leave the screen dirty forever`() {
        // The save re-seeds BOTH halves from the server's answer. Re-seeding only what is on screen
        // would leave a permanent difference wherever the server normalised something — a trimmed
        // website, a lower-cased email — and every departure from then on would raise a prompt
        // offering to save a profile that is already saved.
        val typed = stored().toForm().copy(website = "  https://example.org  ")
        val confirmed = stored(website = "https://example.org").toForm()

        assertTrue("before the save there is something to send", designerProfileHasUnsavedEdits(typed, confirmed))
        assertFalse(
            "after the save both halves are the server's answer",
            designerProfileHasUnsavedEdits(confirmed, confirmed)
        )
    }
}
