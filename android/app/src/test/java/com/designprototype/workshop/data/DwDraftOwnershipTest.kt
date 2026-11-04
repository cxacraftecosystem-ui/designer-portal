package com.designprototype.workshop.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwDraftOwnershipTest {

    @Test
    fun `a draft another account captured is not sent under this account's token`() {
        assertTrue(dwDraftIsForAnotherAccount(ownerUserId = "designer-A", signedInUserId = "designer-B"))
    }

    @Test
    fun `the owner's own drafts go up as they always did`() {
        assertFalse(dwDraftIsForAnotherAccount(ownerUserId = "designer-A", signedInUserId = "designer-A"))
    }

    @Test
    fun `a draft that predates the stamp still syncs`() {
        assertFalse(dwDraftIsForAnotherAccount(ownerUserId = null, signedInUserId = "designer-B"))
    }

    @Test
    fun `no signed-in account is not a mismatch`() {
        assertFalse(dwDraftIsForAnotherAccount(ownerUserId = "designer-A", signedInUserId = null))
        assertFalse(dwDraftIsForAnotherAccount(ownerUserId = null, signedInUserId = null))
    }

    @Test
    fun `the list row says whose the work is and what moves it`() {
        assertTrue(DW_DRAFT_OTHER_ACCOUNT_ROW.contains("Captured by another account"))
        assertTrue(DW_DRAFT_OTHER_ACCOUNT_ROW.contains("sign in as them"))
        assertTrue(
            "one line, not a paragraph:\n$DW_DRAFT_OTHER_ACCOUNT_ROW",
            DW_DRAFT_OTHER_ACCOUNT_ROW.length <= 90,
        )
    }

    @Test
    fun `the row line never claims the work has been deleted or lost`() {
        for (forbidden in listOf("deleted", "lost", "removed", "gone")) {
            assertFalse(
                "\"$forbidden\" appeared in:\n$DW_DRAFT_OTHER_ACCOUNT_ROW",
                DW_DRAFT_OTHER_ACCOUNT_ROW.contains(forbidden),
            )
        }
    }
}
