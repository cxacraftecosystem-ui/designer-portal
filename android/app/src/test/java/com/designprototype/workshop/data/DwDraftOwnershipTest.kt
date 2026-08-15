package com.designprototype.workshop.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A PERMISSION BOUNDARY WRITTEN TO DISK AND THEN ENFORCED BY NOTHING.
 *
 * [WorkshopDraft.ownerUserId] carries the KDoc "the account that owns this draft, so a shared field
 * handset never shows one designer another's work". A grep over the whole module returned exactly two
 * hits: that declaration, and the one assignment in `WorkshopListScreen`. There was no reader.
 *
 * ── WHAT THAT COST ───────────────────────────────────────────────────────────────────────────────
 *
 * Two designers share one field handset, the case the field is written for. A captures a fortnight
 * with no signal — `local-…` drafts, no `remoteId`, `ownerUserId = A`. A signs out (or the token
 * expires: `logout()` clears the token store and nothing else, so the drafts and their photographs
 * stay on disk). B signs in, and `MainActivity`'s sign-in effect calls `syncOutbox` within the second,
 * which tails into `syncAll` → `syncOneWorkshop` → `createDesignWorkshop` under B's token. Every one
 * of A's workshops is created on the server as B's: `createdById` is B, the records appear in B's
 * list, and A has to be GRANTED ACCESS TO THEIR OWN FIELDWORK. The `remoteId` written back points at
 * B's record, so A's phone is then bound to it.
 *
 * ── WHAT IS PINNED HERE, AND WHAT DELIBERATELY IS NOT ────────────────────────────────────────────
 *
 * The rule the sync pass now consults. The two permissive cases matter as much as the refusal: get
 * either wrong and this stops a sync that should run, which strands real fieldwork rather than
 * merely misfiling it.
 *
 * The other half of the finding — the workshop LIST still drawing A's drafts to B — is not closed and
 * is not pinned here; `WorkshopDraftStore.list` enumerates every directory and its caller applies no
 * account filter.
 */
class DwDraftOwnershipTest {

    @Test
    fun `a draft another account captured is not sent under this account's token`() {
        // THE ASSERTION THE DEFECT FAILED: nothing asked this question at all.
        assertTrue(dwDraftIsForAnotherAccount(ownerUserId = "designer-A", signedInUserId = "designer-B"))
    }

    @Test
    fun `the owner's own drafts go up as they always did`() {
        assertFalse(dwDraftIsForAnotherAccount(ownerUserId = "designer-A", signedInUserId = "designer-A"))
    }

    /**
     * A DRAFT WITH NO OWNER IS NOT A DRAFT WITH THE WRONG OWNER. Drafts written before the stamp
     * existed carry null, and so does every draft created by a path that does not set it — a workshop
     * opened from the server has its draft written by the stage screen. Refusing those would be a
     * silent, total sync stop on every handset upgraded into this build: worse than the leak.
     */
    @Test
    fun `a draft that predates the stamp still syncs`() {
        assertFalse(dwDraftIsForAnotherAccount(ownerUserId = null, signedInUserId = "designer-B"))
    }

    /** Nobody signed in means nothing to compare against; the pass cannot run in that state anyway. */
    @Test
    fun `no signed-in account is not a mismatch`() {
        assertFalse(dwDraftIsForAnotherAccount(ownerUserId = "designer-A", signedInUserId = null))
        assertFalse(dwDraftIsForAnotherAccount(ownerUserId = null, signedInUserId = null))
    }
}
