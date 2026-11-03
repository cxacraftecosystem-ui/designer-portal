package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DwCameraRefusalTest {

    private fun all(): List<Pair<DwCameraUse, Boolean>> =
        DwCameraUse.entries.flatMap { use -> listOf(use to false, use to true) }

    @Test
    fun `every refusal names a route that needs no camera`() {
        all().forEach { (use, blocked) ->
            val message = dwCameraRefusal(use, blocked)
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
