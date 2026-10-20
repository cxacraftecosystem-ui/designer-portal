package com.designprototype.workshop.ui

import com.designprototype.workshop.data.ArtisanConsentCopy
import com.designprototype.workshop.data.ArtisanConsentDto
import com.designprototype.workshop.data.ScanAction
import com.designprototype.workshop.data.ScanCopy
import com.designprototype.workshop.data.ScanOutcome
import com.designprototype.workshop.data.ScanOutcomeKind
import com.designprototype.workshop.data.ScanTarget
import com.designprototype.workshop.ui.designworkshop.DW_CRAFT_REGISTER_NOTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The 2026-10-20 audit of the designer handset: scanner, craft note and artisan consent. */
class DesignerScanAuditTest {
    private val peer = ScanOutcome(
        ScanOutcomeKind.ADD_FROM_PEER,
        ScanCopy.FROM_PEER,
        action = ScanAction.Add(ScanCopy.ADD_ARTISAN, ScanTarget.AddFromPeer("FR1:card")),
    )

    @Test
    fun `a peer card offers Add only to an account that may create an artisan`() {
        assertSame(peer, designerScanOutcome(peer, canAddArtisan = true))
        val reader = designerScanOutcome(peer, canAddArtisan = false)
        assertNull(reader.action)
        assertEquals(ScanCopy.FROM_PEER, reader.title)
        assertEquals("Only researchers and above can add artisans.", reader.detail)
        val other = ScanOutcome(ScanOutcomeKind.NOT_FOUND, ScanCopy.NOT_FOUND)
        assertSame(other, designerScanOutcome(other, canAddArtisan = false))
    }

    @Test
    fun `the not-reading hint names only buttons the host shows`() {
        assertEquals(DW_QR_LIVE_STILL_TRYING, dwQrLiveStillTrying("Use a picture"))
        val topBar = dwQrLiveStillTrying(ScanCopy.CHOOSE_PHOTO, captureLabel = null)
        assertFalse(topBar.contains("Scan a code"))
        assertTrue(topBar.contains("“Choose a photo”"))
        assertFalse(dwQrLiveStillTrying(null, null).contains("“"))
    }

    @Test
    fun `the top-bar scanner has one camera button and one hint`() {
        val source = File("src/main/java/com/designprototype/workshop/ui/RecordCodeLookup.kt").readText()
        val controls = source.substringAfter("fun DesignerScanControls(").substringBefore("\n}")
        assertFalse(controls.contains("DwQrScanControl("))
        assertTrue(controls.contains("DwQrPictureButton("))
        assertTrue(controls.contains("captureLabel = null"))
    }

    @Test
    fun `the stage-one craft note never calls the craft optional or says to type it`() {
        assertFalse(DW_CRAFT_REGISTER_NOTE.contains("Optional"))
        assertFalse(DW_CRAFT_REGISTER_NOTE.contains("type", ignoreCase = true))
        assertTrue(DW_CRAFT_REGISTER_NOTE.contains("ask an admin to add it"))
    }

    @Test
    fun `the artisan consent switch reads as the web's`() {
        assertTrue(ArtisanConsentCopy.granted(ArtisanConsentDto()))
        assertFalse(ArtisanConsentCopy.granted(ArtisanConsentDto(state = "REFUSED")))
        assertEquals("On by default", ArtisanConsentCopy.status(ArtisanConsentDto(), null))
        val owner = ArtisanConsentDto(state = "GRANTED", source = "owner attestation", recordedAt = "2026-10-18T09:30:00+00:00")
        assertEquals(
            "Agreed on 18 Oct 2026 · confirmed by the owner",
            ArtisanConsentCopy.status(owner, artisanConsentDate(owner.recordedAt)),
        )
        assertEquals("Declined", ArtisanConsentCopy.status(ArtisanConsentDto(state = "REFUSED"), artisanConsentDate(null)))
        val web = File("../../frontend/components/forms/ArtisanConsentToggle.tsx")
        if (web.exists()) assertTrue(web.readText().contains("\"${ArtisanConsentCopy.DESCRIPTION}\""))
    }

    @Test
    fun `scanner Open and Add ask about unsaved work first`() {
        val activity = File("src/main/java/com/designprototype/workshop/MainActivity.kt").readText()
        assertTrue(activity.contains("attemptExit { openScanTarget(target) }"))
        assertTrue(activity.contains("ArtisanConsentRow(repository = repository, artisanId = editing!!.id)"))
    }
}
