package com.designprototype.workshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminHubEntryTest {

    private fun labels(isMasterAdmin: Boolean, canReview: Boolean): List<String> =
        adminHubEntriesFor(isMasterAdmin = isMasterAdmin, canReview = canReview).map { it.label }

    @Test
    fun `a plain admin is offered the API keys tile, because the ranking behind it is require_admin`() {
        val forAdmin = adminHubEntriesFor(isMasterAdmin = false, canReview = true)
        assertTrue(
            "An admin must reach the transcription provider ranking; GET /settings/transcription-providers is require_admin",
            AdminHubEntry.API_KEYS in forAdmin
        )
    }

    @Test
    fun `opening the API keys tile did not open anything else`() {
        val forAdmin = adminHubEntriesFor(isMasterAdmin = false, canReview = true)
        assertFalse("GET/PUT /settings is require_master_admin", AdminHubEntry.SETTINGS in forAdmin)
        assertFalse("GET /feedback is require_master_admin", AdminHubEntry.FEEDBACK in forAdmin)
    }

    @Test
    fun `masterOnly names exactly the require_master_admin resources`() {
        assertEquals(
            "masterOnly must mirror deps.require_master_admin exactly",
            setOf(AdminHubEntry.FEEDBACK, AdminHubEntry.SETTINGS),
            AdminHubEntry.entries.filter { it.masterOnly }.toSet()
        )
    }

    @Test
    fun `an admin who cannot review loses the review tool and nothing else`() {
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
        val forAdmin = adminHubEntriesFor(isMasterAdmin = false, canReview = true)
        assertTrue("require_usage_reader is Admin and above", AdminHubEntry.USAGE in forAdmin)
        assertTrue(AdminHubEntry.USAGE in adminHubEntriesFor(isMasterAdmin = false, canReview = false))
    }

    @Test
    fun `the Usage blurb is the web tile's, verbatim`() {
        assertEquals("Usage", AdminHubEntry.USAGE.label)
        assertEquals(
            "Which screens are reached, how often, how fast, and how often broken — aggregated across every account.",
            AdminHubEntry.USAGE.description
        )
    }

    @Test
    fun `the API keys blurb says which half belongs to the master admin`() {
        assertEquals(
            "Rank the transcription providers, and — for the master admin — rotate, test and reveal keys.",
            AdminHubEntry.API_KEYS.description
        )
    }
}
