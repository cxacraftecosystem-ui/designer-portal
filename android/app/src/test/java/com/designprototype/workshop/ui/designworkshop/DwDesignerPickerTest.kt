package com.designprototype.workshop.ui.designworkshop

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * The decision behind the create dialog's designer picker: what it says when there is no list.
 *
 * ── WHY THERE IS ANYTHING TO TEST HERE AT ALL ────────────────────────────────────────────────────
 *
 * `seed_designer_prefill` copies a `DesignerProfile` into stage 1 and stage 3 the instant a workshop
 * exists, and until `DesignWorkshopCreateBody.designerUserId` could be SENT the profile it copied was
 * always the CREATOR'S. Every account that can reach that dialog is an ADMIN or the master admin, so
 * "the creator" is very often the wrong person — and not by mistake: `GET /designers/me/profile`
 * upserts a profile row for any admin who so much as opens the Designer Profile screen, and
 * `prefill_from_profile`'s tail fallback then writes `profile.user.name` even from a wholly empty
 * one. The picker is how this handset answers instead, and these two functions are the parts of it
 * that are decisions rather than layout.
 *
 * It is written as a free function taking its one dependency as a parameter for the reason
 * `CreateWorkshopOutcomeTest` gives about `classifyCreate`: the alternative is a `when` inside a
 * composable, which is only ever exercised by somebody looking at a phone.
 *
 * THE OTHER HALF OF THIS PICKER MOVED. `dwNamedDesignerId` used to live beside
 * `dwDesignerPickerStandDown` and was tested here; it now sits in `data/DwWorkshopCreation.kt` with
 * `dwNamedDesignerTeam` and `dwDesignerCreateFields`, because the picker became a MULTI-SELECT and
 * every id in the list has to be folded the same single way — by a rule the data layer can reach.
 * Its assertions moved with it, to `data/DwDesignerTeamTest.kt`, rather than being copied.
 */
class DwDesignerPickerTest {

    private fun httpError(code: Int): HttpException = HttpException(
        Response.error<Any>(
            code,
            "{\"detail\":\"x\"}".toResponseBody("application/json".toMediaTypeOrNull())
        )
    )

    /** Stands in for `WorkshopRepository.isConnectionFailure` without an HTTP stack. */
    private val unreachable: (Throwable) -> Boolean = { it is IOException }

    // ── dwDesignerPickerStandDown ────────────────────────────────────────────────────────────────

    @Test
    fun `a whole list says nothing at all`() {
        // Silence is a real answer and the common one. A standing note on every visit is the padding
        // this app has twice been asked not to have, and the four sentences below are only reached
        // when something is genuinely missing.
        assertNull(dwDesignerPickerStandDown(offline = false, error = null, isConnectionFailure = unreachable))
    }

    @Test
    fun `with no connection the picker stands down and says the workshop can still be started`() {
        val said = dwDesignerPickerStandDown(offline = true, error = null, isConnectionFailure = unreachable)

        // Rule 10: an empty picker with nothing said is indistinguishable from a repository with no
        // eligible designers, which is a claim about the empanelment roster that no failure here
        // supports.
        assertTrue("the stand-down must say something", !said.isNullOrBlank())
        // And the second half is the load-bearing one. The create dialog works with no signal — that
        // is the whole reason it mints a local id — so an admin who read this as "come back when you
        // have signal" would stand in a courtyard waiting two days for a bar they may not get.
        assertTrue(
            "the sentence must say the workshop can still be started: $said",
            said!!.contains("Start the workshop now")
        )
    }

    @Test
    fun `a dropped connection is told as being offline, because to the admin it is`() {
        assertEquals(
            dwDesignerPickerStandDown(offline = true, error = null, isConnectionFailure = unreachable),
            dwDesignerPickerStandDown(
                offline = false,
                error = IOException("no route to host"),
                isConnectionFailure = unreachable,
            )
        )
    }

    @Test
    fun `a server that predates the endpoint is its own answer, not an offline one`() {
        // The id-less call is the only honest probe for "this deployment does not have the feature":
        // a 404 from a request with no id in it cannot mean a missing record. See
        // `dwViewerAdministrationMissing`, where that reasoning lives. Telling it as "no connection"
        // would send an admin to check their signal about a server that answered them immediately.
        val missing = dwDesignerPickerStandDown(
            offline = false,
            error = httpError(404),
            isConnectionFailure = unreachable,
        )
        val offline = dwDesignerPickerStandDown(offline = true, error = null, isConnectionFailure = unreachable)

        assertTrue("a missing endpoint must say something", !missing.isNullOrBlank())
        assertNotEquals("a 404 is not a lost connection", offline, missing)
    }

    @Test
    fun `an answered failure is not dressed up as a disconnection`() {
        // The same split this dialog already makes for the create itself, and for the same reason: a
        // refusal is not a disconnection, and "try again when you have a connection" is false in both
        // halves when the server answered.
        val offline = dwDesignerPickerStandDown(offline = true, error = null, isConnectionFailure = unreachable)
        listOf(401, 403, 500, 502).forEach { code ->
            val said = dwDesignerPickerStandDown(
                offline = false,
                error = httpError(code),
                isConnectionFailure = unreachable,
            )
            assertTrue("HTTP $code must still say something", !said.isNullOrBlank())
            assertNotEquals("HTTP $code is not a lost connection", offline, said)
        }
    }
}
