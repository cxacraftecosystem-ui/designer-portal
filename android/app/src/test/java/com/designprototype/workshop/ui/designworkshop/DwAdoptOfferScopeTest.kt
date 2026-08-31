package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.canCreateDesignWorkshops
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHO IS OFFERED "MOVE INTO A WORKSHOP", AND WHY AN ADMIN IS NOT.
 *
 * ── THE DIVERGENCE THIS CLOSES ──────────────────────────────────────────────────────────────────
 *
 * The browser narrows the control to `!allowCreate && allowWork`, and says why on
 * `design-workshops/page.tsx`: *"An admin holding a device-only draft does not need this: their next
 * sync creates the workshop and the draft resolves itself. Showing them a control that quietly
 * re-files a fortnight of fieldwork into a DIFFERENT workshop, for no benefit, is a way to lose work
 * by mis-tap."* The handset gated on `row.localOnly && row.hasLocalDraft` alone, so an admin was
 * offered it — and the move is not undoable from that screen.
 *
 * The same screen already half-believed the rule: its banner ("N workshops here … Use 'Move into a
 * workshop' on the row") was gated on `!mayCreate`, so an admin was told nothing and then shown the
 * button the sentence would have named. One predicate now answers both.
 *
 * ── AND WHY A TEST RATHER THAN A READING ────────────────────────────────────────────────────────
 *
 * `app/build.gradle.kts` carries no `ui-test-junit4` and no Robolectric, so the JVM suite cannot
 * render this screen; the rule is therefore lifted into [dwOfferDraftMove] and asserted here. The
 * browser pins its own copy by asserting the literal source text of the line, which is that same
 * instinct reaching for the only tool a Playwright spec had.
 *
 * Every case below is a role that exists in `deps.py` and behaves differently from its neighbour.
 */
class DwAdoptOfferScopeTest {

    /** `mayCreate` as the screen computes it, so these cases are the real role ladder. */
    private fun mayCreate(role: String) = canCreateDesignWorkshops(role)

    /**
     * A DESIGNER IS OFFERED IT, and that is the whole reason the control exists.
     *
     * This is the half of the old rule that is NOT overruled. A designer runs workshops and cannot
     * create one, so a draft they started before the rule changed — 22 stages, photographs,
     * recordings — has no route to a server record except this one. Nothing here brings a workshop
     * into existence; it decides which EXISTING workshop the fortnight belongs to, which is the
     * designer's own judgement about their own fieldwork.
     */
    @Test
    fun `a designer is offered the move`() {
        assertTrue(dwOfferDraftMove(mayCreate = mayCreate("DESIGNER"), mayRunWorkshops = true))
    }

    /**
     * AN ADMIN IS NOT — the narrowing itself.
     *
     * An admin's device-only draft creates its own workshop on the next sync (`WorkshopSync`'s create
     * arm posts it, and `dwRefuseLocalCreate` does not refuse an account that may create). The
     * control can therefore only do one thing an admin might not want: file the fortnight into some
     * OTHER workshop. A control whose entire remaining effect is a way to lose work is not offered.
     */
    @Test
    fun `an admin is not offered the move, because their draft resolves itself`() {
        assertFalse(dwOfferDraftMove(mayCreate = mayCreate("ADMIN"), mayRunWorkshops = true))
        assertFalse(dwOfferDraftMove(mayCreate = mayCreate("MASTER_ADMIN"), mayRunWorkshops = true))
    }

    /**
     * AND NEITHER IS AN ACCOUNT THAT DOES NOT RUN WORKSHOPS AT ALL.
     *
     * `mayRunWorkshops` is not redundant beside `!mayCreate`: filing a fortnight into a workshop is
     * WORK, and the roles outside `DESIGN_WORKSHOP_ROLES` — a professor, a researcher, an inspector —
     * are all `!mayCreate` too. Without this half, the narrowing would have handed the control to
     * every one of them, which is the opposite of the fix.
     */
    @Test
    fun `an account that does not run workshops is not offered the move`() {
        assertFalse(dwOfferDraftMove(mayCreate = mayCreate("PROFESSOR"), mayRunWorkshops = false))
        assertFalse(dwOfferDraftMove(mayCreate = mayCreate("INSPECTOR"), mayRunWorkshops = false))
    }

    /**
     * THE TWO HALVES ARE INDEPENDENT, so neither can be dropped as implied by the other.
     *
     * Written out because the roles above never produce this pair on a live device — an account that
     * may create AND does not run workshops is not a role, it is what a future set change would look
     * like the day before somebody noticed. The rule refuses it, which is the safe direction.
     */
    @Test
    fun `both halves are required`() {
        assertFalse(dwOfferDraftMove(mayCreate = true, mayRunWorkshops = false))
        assertFalse(dwOfferDraftMove(mayCreate = true, mayRunWorkshops = true))
        assertFalse(dwOfferDraftMove(mayCreate = false, mayRunWorkshops = false))
        assertTrue(dwOfferDraftMove(mayCreate = false, mayRunWorkshops = true))
    }
}
