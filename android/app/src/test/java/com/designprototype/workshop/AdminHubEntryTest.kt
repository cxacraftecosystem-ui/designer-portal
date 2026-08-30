package com.designprototype.workshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which tools the phone's settings hub offers, walked role by role.
 *
 * WHY THIS FILE EXISTS. [AdminHubEntry.API_KEYS] carried `masterOnly = true`, and everything about
 * that flag read as correct: the comment beside it said "every /secrets route is
 * require_master_admin", which is true, and the screen it opens is called "API keys", which sounds
 * like credentials. What it missed is that TWO resources at two heights sit behind that one tile.
 * `ApiKeysScreen` draws [com.designprototype.workshop.ui.ProviderOrderPanel] first and the key list
 * second, and the ranking is `require_admin`:
 *
 *   backend/app/api/routes/settings.py:227  GET  /settings/transcription-providers        require_admin
 *   backend/app/api/routes/settings.py:236  PUT  /settings/transcription-providers        require_admin
 *   backend/app/api/routes/settings.py:281  POST /settings/transcription-providers/{p}/test require_admin
 *   backend/app/api/routes/secrets.py:39..  GET/PUT/DELETE /secrets*                require_master_admin
 *   backend/app/api/routes/settings.py:39,47 GET/PUT /settings                      require_master_admin
 *   backend/app/api/routes/feedback.py:82   GET  /feedback                          require_master_admin
 *
 * So an admin was refused a screen written for them, by a client, over a route the server would have
 * served — and the screen already handled the other half by DISCOVERING the 403 and drawing
 * "Master admin only" in the key list's place. The web hit the identical bug and fixed it the same
 * way; `/settings/api-keys` and the `/admin` tile are both `isAdmin(user)` there.
 *
 * A permission mirror cannot be verified by reading it — that is exactly what nobody caught here for
 * as long as the flag stood — so this walks the two booleans that decide the list.
 */
class AdminHubEntryTest {

    private fun labels(isMasterAdmin: Boolean, canReview: Boolean): List<String> =
        adminHubEntriesFor(isMasterAdmin = isMasterAdmin, canReview = canReview).map { it.label }

    @Test
    fun `a plain admin is offered the API keys tile, because the ranking behind it is require_admin`() {
        // The whole defect, stated as one assertion. The hub is already behind `isAdmin`, so this is
        // the only gate between an admin and the transcription provider ladder.
        val forAdmin = adminHubEntriesFor(isMasterAdmin = false, canReview = true)
        assertTrue(
            "An admin must reach the transcription provider ranking; GET /settings/transcription-providers is require_admin",
            AdminHubEntry.API_KEYS in forAdmin
        )
    }

    @Test
    fun `opening the API keys tile did not open anything else`() {
        // The half that is genuinely master-admin's stays master-admin's. Asserted separately so a
        // future "just make the hub simpler" cannot pass by dropping masterOnly everywhere.
        val forAdmin = adminHubEntriesFor(isMasterAdmin = false, canReview = true)
        assertFalse("GET/PUT /settings is require_master_admin", AdminHubEntry.SETTINGS in forAdmin)
        assertFalse("GET /feedback is require_master_admin", AdminHubEntry.FEEDBACK in forAdmin)
    }

    @Test
    fun `masterOnly names exactly the require_master_admin resources`() {
        // Pins the flag itself rather than one account's view of it, so re-marking API_KEYS
        // masterOnly fails here even if somebody also changed the filter that reads it.
        assertEquals(
            "masterOnly must mirror deps.require_master_admin exactly",
            setOf(AdminHubEntry.FEEDBACK, AdminHubEntry.SETTINGS),
            AdminHubEntry.entries.filter { it.masterOnly }.toSet()
        )
    }

    @Test
    fun `an admin who cannot review loses the review tool and nothing else`() {
        // canReview is require_reviewer (FIELD_CONTRIBUTOR and up), which every admin passes, so in
        // practice this arm only fires for the non-admin reviewer MainActivity routes straight to
        // ReviewApprovalCard. It is still the second of the two booleans, so it is walked.
        val withReview = labels(isMasterAdmin = false, canReview = true)
        val withoutReview = labels(isMasterAdmin = false, canReview = false)
        assertEquals(
            listOf(AdminHubEntry.REVIEWS.label),
            withReview.filterNot { it in withoutReview }
        )
    }

    @Test
    fun `the master admin is offered every tool there is`() {
        assertEquals(
            AdminHubEntry.entries.toList(),
            adminHubEntriesFor(isMasterAdmin = true, canReview = true)
        )
    }

    @Test
    fun `a plain admin is offered the Usage tile, because require_usage_reader is Admin and above`() {
        // `deps.can_read_usage` is Admin and above — the same rank the web's ADMIN_LINKS card is
        // gated at — so `masterOnly` must stay off. The hub itself is already behind
        // `isAdmin && adminChrome`, which means this filter is the ONLY thing between an admin and
        // the aggregate, exactly as it was for [AdminHubEntry.API_KEYS] before that flag was fixed.
        // Asserted rather than read, for the reason this whole file exists: a permission mirror that
        // is only ever verified by looking at it is how API_KEYS stayed unreachable.
        val forAdmin = adminHubEntriesFor(isMasterAdmin = false, canReview = true)
        assertTrue("require_usage_reader is Admin and above", AdminHubEntry.USAGE in forAdmin)
        // And it is not review-gated: a reviewer who is not an admin never reaches this hub at all,
        // and adding a second condition here would only make the mirror stop mirroring.
        assertTrue(AdminHubEntry.USAGE in adminHubEntriesFor(isMasterAdmin = false, canReview = false))
    }

    @Test
    fun `the Usage blurb is the web tile's, verbatim`() {
        // Verbatim from ADMIN_LINKS in frontend/app/(protected)/settings/page.tsx. A researcher who
        // moves between the laptop and the handset mid-workshop must meet ONE name for one thing —
        // and this one in particular must not drift into "Analytics", which is already the name of a
        // different screen about craft outcomes that observes no person at all. The backend keeps
        // those two names apart on purpose and says so in `usage.py`'s module docstring.
        assertEquals("Usage", AdminHubEntry.USAGE.label)
        assertEquals(
            "Which screens are reached, how often, how fast, and how often broken — aggregated across every account.",
            AdminHubEntry.USAGE.description
        )
    }

    @Test
    fun `the API keys blurb says which half belongs to the master admin`() {
        // Verbatim from ADMIN_LINKS in frontend/app/(protected)/settings/page.tsx:72. A tile now
        // offered to admins that promised "rotate, test and reveal the provider keys" — as this one
        // did while it was master-only and only the master admin ever read it — would lie to every
        // admin who tapped it, before the screen had a chance to say otherwise.
        assertEquals(
            "Rank the transcription providers, and — for the master admin — rotate, test and reveal keys.",
            AdminHubEntry.API_KEYS.description
        )
    }
}
