package com.designprototype.workshop.ui

import com.designprototype.workshop.data.AccessRosterDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The sign-in list rule on the access screen: the server's sentence replaces the receipt. documentation-portal's `AccessRosterTest` holds the same cases. */
class AccessRosterHeldSentenceTest {
    @Test
    fun `an approval that kept an account's role shows the server's sentence, and nothing else does`() {
        val said = "a@example.org already has an account, as Researcher. Its role stays; change it on Manage users."
        assertEquals(said, heldSentence(AccessRosterDto(email = "a@example.org", accountPromotionHeld = "  $said ")))
        assertNull(heldSentence(AccessRosterDto(email = "a@example.org")))
        assertNull(heldSentence(AccessRosterDto(email = "a@example.org", accountPromotionHeld = "   ")))
        assertNull(heldSentence(Unit))
    }
}
