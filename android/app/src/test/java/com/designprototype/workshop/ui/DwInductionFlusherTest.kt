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

/**
 * THE JOIN-CARD FLUSHER WAITS OUT THE PASSWORD GATE, AND OWES EXACTLY ONE PASS WHEN IT CLEARS.
 *
 * While the signed-in account owes a new password the server answers both of the flusher's routes
 * with a gated 401. The rows would survive that — a 401 is queued, not refused — but a pass would
 * spend a request and an attempt on every row to learn nothing, so [DwInductionFlusher.flushNow]
 * holds back and records that it did. `RepositoryApp` then calls
 * [DwInductionFlusher.resumeAfterPasswordGate] when its own loop starts again, and that call owes
 * exactly ONE pass:
 *
 *  - **none** would leave a scan taken in a courtyard sitting in the queue until the next network
 *    change or the next cold start — and the cold start that met the gate has already happened;
 *  - **two** would make every sign-in a new moment at which queued scans go out, which nothing asked
 *    for.
 *
 * The pass itself is swapped for a counter ([DwInductionFlusher.flushPass]), so these assertions are
 * about the flusher's decisions and not about a queue file or a server.
 */
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
        // The flusher is an `object`: whatever this test leaves behind, the next one inherits.
        settle()
        DwInductionFlusher.flushPass = thePass
        DwInductionFlusher.heldForPassword.set(false)
    }

    /** Wait for the pass the flusher launched last, so nothing is still in flight when we look. */
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

        // The gate clears: the profile is rewritten without the flag, and the loop resumes.
        TokenStore(context).setUser(account(mustChange = false))
        DwInductionFlusher.resumeAfterPasswordGate(context)
        settle()

        assertEquals("the pass the gate held back must run once it clears", 1, passes.get())
        assertFalse(DwInductionFlusher.heldForPassword.get())
    }

    @Test
    fun `the gate on screen owes one pass when it clears, and a second resume owes none`() {
        // `RepositoryApp` marks the flusher held while the gate is on screen, even where no pass was
        // skipped: a profile cached before the flag was raised lets one pass go out at process start,
        // and its rows come back gated with nothing else due to send them again.
        val context = signedIn(mustChange = false)
        DwInductionFlusher.holdForPasswordGate()

        DwInductionFlusher.resumeAfterPasswordGate(context)
        settle()
        assertEquals(1, passes.get())

        // The loop restarts on every change of profile; only the first restart after the gate owes
        // anything.
        val before = DwInductionFlusher.lastPass
        DwInductionFlusher.resumeAfterPasswordGate(context)
        assertSame("a second resume launched a pass", before, DwInductionFlusher.lastPass)
        settle()
        assertEquals(1, passes.get())
    }

    @Test
    fun `a resume with nothing held sends nothing`() {
        // An ordinary sign-in passes through the same call, and must not become a moment at which
        // queued scans go out.
        val context = signedIn(mustChange = false)
        val before = DwInductionFlusher.lastPass

        DwInductionFlusher.resumeAfterPasswordGate(context)

        assertSame("a resume with nothing held launched a pass", before, DwInductionFlusher.lastPass)
        settle()
        assertEquals(0, passes.get())
    }
}
