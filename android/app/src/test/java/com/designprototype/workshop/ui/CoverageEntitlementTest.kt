package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Places | Coverage answer lasts one sign-in session: another account on the same phone is asked
 * again, and signed out nothing is kept.
 */
class CoverageEntitlementTest {
    @Test
    fun answerIsKeptForItsSessionOnly() {
        CoverageEntitlement.remember("session-a", true)
        assertEquals(true, CoverageEntitlement.cached("session-a"))
        assertNull(CoverageEntitlement.cached("session-b"))
        assertNull(CoverageEntitlement.cached(null))

        CoverageEntitlement.remember("session-b", false)
        assertEquals(false, CoverageEntitlement.cached("session-b"))
        assertNull(CoverageEntitlement.cached("session-a"))
    }

    @Test
    fun signedOutIsNeverRemembered() {
        CoverageEntitlement.remember("session-c", true)
        CoverageEntitlement.remember(null, false)
        assertEquals(true, CoverageEntitlement.cached("session-c"))
        assertNull(CoverageEntitlement.cached(null))
    }
}
