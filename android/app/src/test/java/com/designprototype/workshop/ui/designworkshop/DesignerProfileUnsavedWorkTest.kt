package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DesignerProfileDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignerProfileUnsavedWorkTest {

    private fun stored(
        biography: String? = "Twelve years of block-printing with the Bagru cluster.",
        website: String? = "https://example.org",
        photoMediaId: String? = null
    ) = DesignerProfileDto(
        id = "p-1",
        userId = "u-1",
        displayName = "A. Sharma",
        institution = "NID",
        experienceYears = 12,
        biography = biography,
        website = website,
        state = "Rajasthan",
        city = "Bagru",
        photoMediaId = photoMediaId,
        empanelmentDate = "2024-11-04T00:00:00+00:00",
        empanelmentNo = "EMP/2024/118"
    )

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
        assertEquals(stored().toForm(), stored().toForm())
        assertEquals("12", stored().toForm().experienceYears)
        assertEquals("2024-11-04", stored().toForm().empanelmentDate?.toString())
        assertEquals("", DesignerProfileDto().toForm().biography)
    }

    @Test
    fun `a designer who has never saved a profile is not asked to`() {
        val empty = ProfileForm()

        assertFalse(designerProfileHasUnsavedEdits(empty, empty))
    }

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
        val loaded = stored(photoMediaId = null).toForm()
        val afterUpload = loaded.copy(photoMediaId = "media-9")

        assertTrue(designerProfileHasUnsavedEdits(afterUpload, loaded))
    }

    @Test
    fun `a control that fires no keystroke is covered too`() {
        val loaded = stored().toForm()

        assertTrue(designerProfileHasUnsavedEdits(loaded.copy(state = "Gujarat"), loaded))
        assertTrue(
            designerProfileHasUnsavedEdits(
                loaded.copy(empanelmentDate = loaded.empanelmentDate?.plusDays(1)),
                loaded
            )
        )
    }

    @Test
    fun `the server's normalisation does not leave the screen dirty forever`() {
        val typed = stored().toForm().copy(website = "  https://example.org  ")
        val confirmed = stored(website = "https://example.org").toForm()

        assertTrue("before the save there is something to send", designerProfileHasUnsavedEdits(typed, confirmed))
        assertFalse(
            "after the save both halves are the server's answer",
            designerProfileHasUnsavedEdits(confirmed, confirmed)
        )
    }
}
