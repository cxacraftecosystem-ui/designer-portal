package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.canCreateDesignWorkshops
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwAdoptOfferScopeTest {

    private fun mayCreate(role: String) = canCreateDesignWorkshops(role)

    @Test
    fun `a designer is offered the move`() {
        assertTrue(dwOfferDraftMove(mayCreate = mayCreate("DESIGNER"), mayRunWorkshops = true))
    }

    @Test
    fun `an admin is not offered the move, because their draft resolves itself`() {
        assertFalse(dwOfferDraftMove(mayCreate = mayCreate("ADMIN"), mayRunWorkshops = true))
        assertFalse(dwOfferDraftMove(mayCreate = mayCreate("MASTER_ADMIN"), mayRunWorkshops = true))
    }

    @Test
    fun `an account that does not run workshops is not offered the move`() {
        assertFalse(dwOfferDraftMove(mayCreate = mayCreate("PROFESSOR"), mayRunWorkshops = false))
        assertFalse(dwOfferDraftMove(mayCreate = mayCreate("INSPECTOR"), mayRunWorkshops = false))
        assertFalse(dwOfferDraftMove(mayCreate = mayCreate("ASSISTANT_DIRECTOR"), mayRunWorkshops = false))
        assertFalse(dwOfferDraftMove(mayCreate = mayCreate("REGIONAL_DIRECTOR"), mayRunWorkshops = false))
        assertFalse(dwOfferDraftMove(mayCreate = mayCreate("MINISTRY_ADMIN"), mayRunWorkshops = false))
    }

    @Test
    fun `both halves are required`() {
        assertFalse(dwOfferDraftMove(mayCreate = true, mayRunWorkshops = false))
        assertFalse(dwOfferDraftMove(mayCreate = true, mayRunWorkshops = true))
        assertFalse(dwOfferDraftMove(mayCreate = false, mayRunWorkshops = false))
        assertTrue(dwOfferDraftMove(mayCreate = false, mayRunWorkshops = true))
    }
}
