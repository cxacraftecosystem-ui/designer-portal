package com.designprototype.workshop.data

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [CoalescedLoader]: what each caller gets must be exactly what its own single read gave it; what
 * changes is only how many requests carry them. Every per-row card that moved onto it (transcript
 * insights, the signed-URL refresh, the profile's three files) leans on these four properties.
 */
class CoalescedLoaderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun loadAll(loader: CoalescedLoader<String>, keys: List<String>): List<String?> = runBlocking {
        keys.map { key -> async { loader.load(key) } }.awaitAll()
    }

    @Test
    fun `lookups asked together travel as ONE batch and each caller gets its own answer`() {
        val batches = mutableListOf<List<String>>()
        val singles = mutableListOf<String>()
        val loader = CoalescedLoader(
            scope = scope,
            maxBatch = 50,
            fetchBatch = { keys -> synchronized(batches) { batches.add(keys) }; keys.filter { it != "gone" }.associateWith { "row-$it" } },
            fetchOne = { key -> synchronized(singles) { singles.add(key) }; "single-$key" }
        )
        val answers = loadAll(loader, listOf("a", "b", "a", "gone", "c"))
        assertEquals(listOf("row-a", "row-b", "row-a", null, "row-c"), answers)
        assertEquals(1, batches.size)
        assertEquals(listOf("a", "b", "gone", "c"), batches.single())
        assertEquals(emptyList<String>(), singles)
    }

    @Test
    fun `a server that cannot batch is asked key by key, exactly as before`() {
        val singles = mutableListOf<String>()
        val loader = CoalescedLoader(
            scope = scope,
            maxBatch = 50,
            fetchBatch = { null },
            fetchOne = { key -> synchronized(singles) { singles.add(key) }; if (key == "bad") throw IOException("x") else "single-$key" }
        )
        assertEquals(listOf("single-a", null, "single-b"), loadAll(loader, listOf("a", "bad", "b")))
        assertEquals(setOf("a", "bad", "b"), singles.toSet())
    }

    @Test
    fun `a batch that fails answers nothing for every key in it`() {
        val loader = CoalescedLoader<String>(
            scope = scope,
            maxBatch = 50,
            fetchBatch = { throw IOException("offline") },
            fetchOne = { error("never asked") }
        )
        val answers = loadAll(loader, listOf("a", "b"))
        assertNull(answers[0])
        assertNull(answers[1])
    }

    @Test
    fun `more keys than the server's ceiling are split, never refused`() {
        val sizes = mutableListOf<Int>()
        val loader = CoalescedLoader(
            scope = scope,
            maxBatch = 3,
            fetchBatch = { keys -> synchronized(sizes) { sizes.add(keys.size) }; keys.associateWith { it } },
            fetchOne = { it }
        )
        val keys = (1..7).map { "k$it" }
        assertEquals(keys, loadAll(loader, keys))
        assertEquals(listOf(1, 3, 3), sizes.sorted())
    }
}
