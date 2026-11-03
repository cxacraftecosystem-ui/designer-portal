package com.designprototype.workshop.ui

import android.content.Context
import com.designprototype.workshop.data.InMemoryContext
import com.designprototype.workshop.data.TokenStore
import com.designprototype.workshop.data.UserDto
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class DwInductionFlusherTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val passes = AtomicInteger(0)
    private lateinit var thePass: suspend (Context) -> Unit

    @Before
    fun countPassesInsteadOfSendingThem() {
        settle()
        thePass = DwInductionFlusher.flushPass
        DwInductionFlusher.flushPass = { passes.incrementAndGet() }
        DwInductionFlusher.heldForPassword.set(false)
    }

    @After
    fun putThePassBack() {
        settle()
        DwInductionFlusher.flushPass = thePass
        DwInductionFlusher.heldForPassword.set(false)
    }

    private fun settle() = runBlocking { DwInductionFlusher.lastPass?.join() }

    private fun signedIn(mustChange: Boolean): InMemoryContext =
        InMemoryContext(folder.root).also { TokenStore(it).setUser(account(mustChange)) }

    private fun account(mustChange: Boolean) = UserDto(
        id = "u-1",
        email = "meera@example.org",
        name = "Meera",
        role = "DESIGNER",
        mustChangePassword = mustChange,
    )

    @Test
    fun `a pass while the account owes a new password is held, not sent`() {
        val context = signedIn(mustChange = true)

        DwInductionFlusher.flushNow(context)
        settle()

        assertEquals("a gated account's queue was sent", 0, passes.get())
        assertTrue("the held pass was not recorded, so nothing will ever run it", DwInductionFlusher.heldForPassword.get())

        TokenStore(context).setUser(account(mustChange = false))
        DwInductionFlusher.resumeAfterPasswordGate(context)
        settle()

        assertEquals("the pass the gate held back must run once it clears", 1, passes.get())
        assertFalse(DwInductionFlusher.heldForPassword.get())
    }

    @Test
    fun `the gate on screen owes one pass when it clears, and a second resume owes none`() {
        val context = signedIn(mustChange = false)
        DwInductionFlusher.holdForPasswordGate()

        DwInductionFlusher.resumeAfterPasswordGate(context)
        settle()
        assertEquals(1, passes.get())

        val before = DwInductionFlusher.lastPass
        DwInductionFlusher.resumeAfterPasswordGate(context)
        assertSame("a second resume launched a pass", before, DwInductionFlusher.lastPass)
        settle()
        assertEquals(1, passes.get())
    }

    @Test
    fun `a resume with nothing held sends nothing`() {
        val context = signedIn(mustChange = false)
        val before = DwInductionFlusher.lastPass

        DwInductionFlusher.resumeAfterPasswordGate(context)

        assertSame("a resume with nothing held launched a pass", before, DwInductionFlusher.lastPass)
        settle()
        assertEquals(0, passes.get())
    }
}
