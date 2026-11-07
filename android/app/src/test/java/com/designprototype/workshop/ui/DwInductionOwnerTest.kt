package com.designprototype.workshop.ui

import com.designprototype.workshop.data.ConnectivityObserver
import com.designprototype.workshop.data.InMemoryContext
import com.designprototype.workshop.data.TokenStore
import com.designprototype.workshop.data.UserDto
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DwInductionOwnerTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun offline() {
        ConnectivityObserver.answerForTests = false
    }

    @After
    fun reset() {
        ConnectivityObserver.answerForTests = null
    }

    private fun account(id: String) = UserDto(id = id, email = "$id@example.org", name = id, role = "DESIGNER")

    @Test
    fun `a card one account scanned offline is not redeemed under the next account`() = runBlocking {
        val context = InMemoryContext(folder.root)
        TokenStore(context).setUser(account("designer-a"))
        DwInductionQueue.record(context, "ws-1", "CARD-1", kind = DW_INDUCTION_JOIN)

        TokenStore(context).setUser(account("designer-b"))
        val sentAsB = DwInductionQueue.flush(context)

        assertTrue("designer-a's card was tried under designer-b's session: $sentAsB", sentAsB.isEmpty())
        val kept = DwInductionQueue.all(context).single()
        assertEquals("the held card must not count an attempt", 0, kept.attempts)

        TokenStore(context).setUser(account("designer-a"))
        val sentAsA = DwInductionQueue.flush(context)
        assertEquals(listOf("ws-1"), sentAsA.map { it.first.workshopId })
    }

    @Test
    fun `an ask queued by one account is not sent under the next account`() = runBlocking {
        val context = InMemoryContext(folder.root)
        TokenStore(context).setUser(account("designer-a"))
        DwInductionQueue.record(context, "ws-2", "ASK-1", kind = DW_INDUCTION_ASK)

        TokenStore(context).setUser(account("designer-b"))

        assertTrue(DwInductionQueue.flush(context).isEmpty())
    }

    @Test
    fun `nothing is sent while nobody is signed in`() = runBlocking {
        val context = InMemoryContext(folder.root)
        TokenStore(context).setUser(account("designer-a"))
        DwInductionQueue.record(context, "ws-3", "CARD-3", kind = DW_INDUCTION_JOIN)

        TokenStore(context).setUser(null)

        assertTrue(DwInductionQueue.flush(context).isEmpty())
    }
}
