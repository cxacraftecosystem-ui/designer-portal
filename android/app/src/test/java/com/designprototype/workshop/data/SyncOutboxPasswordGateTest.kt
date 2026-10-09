package com.designprototype.workshop.data

import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * WHILE THE ACCOUNT OWES A NEW PASSWORD, `syncOutbox` SENDS NOTHING — AND SPENDS NOTHING.
 *
 * The server answers every route the outbox uses with a gated 401 until the password is changed, so
 * `syncOutbox` returns before doing anything at all. Two properties ride on WHERE that return sits,
 * and neither shows on a screen:
 *
 *  1. **It is the first line.** The pass opens with once-per-process housekeeping — the staged sweep
 *     and the orphan reclaim, latched on [WorkshopRepository.sweptStagedObjects]. Moved below the
 *     latch, the gate would let a gated handset spend that one chance against a server that refuses
 *     everything, and the sweep would not run again until the app was killed and reopened.
 *  2. **It touches nothing.** Not the queue file, not the connection, not the design-workshop pass.
 *     The queue keeps every entry for the pass after the change.
 *
 * And the counter-assertion, so the first is not vacuous: the moment the profile is cleared, the same
 * repository's next pass DOES spend the latch.
 */
class SyncOutboxPasswordGateTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun account(mustChange: Boolean) = UserDto(
        id = "u-1",
        email = "meera@example.org",
        name = "Meera",
        role = "DESIGNER",
        mustChangePassword = mustChange,
    )

    /**
     * A repository whose every request would throw. Nothing in this test should reach one: the gated
     * pass returns first, and the ungated one finds no token and no connection.
     */
    private fun repository(store: TokenStore): WorkshopRepository {
        val api = java.lang.reflect.Proxy.newProxyInstance(
            WorkshopRepositoryApi::class.java.classLoader,
            arrayOf(WorkshopRepositoryApi::class.java)
        ) { _, method, _ -> throw UnsupportedOperationException(method.name) } as WorkshopRepositoryApi
        return WorkshopRepository(api, store)
    }

    @Test
    fun `a gated pass returns before the once-per-process housekeeping, and touches nothing`() {
        val context = InMemoryContext(folder.root)
        val store = TokenStore(context).apply { setUser(account(mustChange = true)) }
        val repository = repository(store)

        assertEquals(0, runBlocking { repository.syncOutbox(context) })

        assertFalse(
            "the staged sweep and the reclaim were spent on a pass that could not reach the server",
            repository.sweptStagedObjects.get()
        )
        assertFalse(
            "the gated pass read the queue; it must return before anything",
            File(folder.root, "outbox").exists()
        )

        // THE COUNTER-ASSERTION. The gate clears — the profile is rewritten without the flag, as
        // `changeOwnPassword` does — and the very next pass spends the latch it kept.
        store.setUser(account(mustChange = false))
        // A stale queue alert from another test would be toasted, and the JVM has no toast.
        OfflineOutbox.takeAlert()
        runBlocking { repository.syncOutbox(context) }
        assertTrue(
            "the latch this test watches is not the one the housekeeping hangs off",
            repository.sweptStagedObjects.get()
        )

        awaitDetachedHousekeeping()
    }

    /**
     * The ungated pass launches its housekeeping DETACHED, on the app scope; let it finish against
     * this test's folder before the folder is deleted, rather than racing the rule's cleanup. Bounded,
     * because the app scope is shared and this test does not own everything running on it.
     */
    private fun awaitDetachedHousekeeping() = runBlocking {
        withTimeoutOrNull(5_000) {
            AppScope.io.coroutineContext.job.children.toList().joinAll()
        }
        Unit
    }
}
