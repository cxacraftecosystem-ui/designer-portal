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

        store.setUser(account(mustChange = false))
        OfflineOutbox.takeAlert()
        runBlocking { repository.syncOutbox(context) }
        assertTrue(
            "the latch this test watches is not the one the housekeeping hangs off",
            repository.sweptStagedObjects.get()
        )

        awaitDetachedHousekeeping()
    }

    private fun awaitDetachedHousekeeping() = runBlocking {
        withTimeoutOrNull(5_000) {
            AppScope.io.coroutineContext.job.children.toList().joinAll()
        }
        Unit
    }
}
