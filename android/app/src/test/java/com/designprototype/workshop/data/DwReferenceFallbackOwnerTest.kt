package com.designprototype.workshop.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DwReferenceFallbackOwnerTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = java.nio.file.Files.createTempDirectory("dw-references-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `a workshop-owned list written before the scope fix is not merged back in`() {
        val workshop = "cmsik2jg8000eh8xc1lcy661a"
        val staleKey = DwReferenceStore.cacheKey("Artisan", "WORKSHOP", workshop, "")
        runBlocking { DwReferenceStore.store(root, staleKey, wholeTable()) }

        assertTrue(
            "the old model-only prefix must match this file, or this test proves nothing",
            File(root, "$staleKey.json").name.startsWith("Artisan__"),
        )

        val offered = runBlocking {
            DwReferenceStore.anyForModel(root, "Artisan", "WORKSHOP", dwReferenceCacheOwner(workshop))
        }

        assertNull(
            "a retired pre-fix list came back through the fallback: the generation bump bought nothing",
            offered,
        )
    }

    @Test
    fun `an ALL-owned register is not merged into a WORKSHOP-scoped picker`() {
        val workshop = "cmsw0qv31000cq2n5t7t0k3zz"
        val allKey = DwReferenceStore.cacheKey("Artisan", "ALL", workshop, "")
        runBlocking { DwReferenceStore.store(root, allKey, wholeTable()) }

        assertTrue("the old prefix must match", File(root, "$allKey.json").name.startsWith("Artisan__"))

        val offered = runBlocking {
            DwReferenceStore.anyForModel(root, "Artisan", "WORKSHOP", dwReferenceCacheOwner(workshop))
        }

        assertNull("the ALL-scoped register was served to a WORKSHOP-scoped field", offered)
    }

    @Test
    fun `another workshop's cached list is never offered in this workshop`() {
        val other = dwReferenceCacheOwner("cmsotherws0000h8xc1lcy661a")
        val mine = dwReferenceCacheOwner("cmsminews00000h8xc1lcy661a")
        val theirKey = DwReferenceStore.cacheKey("ProductDocumentation", "WORKSHOP", other, "")
        runBlocking {
            DwReferenceStore.store(
                root,
                theirKey,
                DwReferenceList(
                    model = "ProductDocumentation",
                    items = listOf(DwReferenceOption(id = "prod-A1", label = "Sambalpuri saree (cluster A)")),
                ),
            )
        }

        assertTrue(
            "the old prefix must match",
            File(root, "$theirKey.json").name.startsWith("ProductDocumentation__"),
        )

        val offered = runBlocking {
            DwReferenceStore.anyForModel(root, "ProductDocumentation", "WORKSHOP", mine)
        }

        assertNull("another cluster's products were offered in this workshop", offered)
    }

    @Test
    fun `this workshop's own cached lists still merge, so the offline cascade survives`() {
        val owner = dwReferenceCacheOwner("cmscascade0000h8xc1lcy661a")
        runBlocking {
            DwReferenceStore.store(
                root,
                DwReferenceStore.cacheKey("ProductDocumentation", "WORKSHOP", owner, "artisan-1"),
                DwReferenceList(
                    model = "ProductDocumentation",
                    filteredBy = "artisan-1",
                    items = listOf(DwReferenceOption(id = "p-1", label = "Ikat stole", filterValue = "artisan-1")),
                ),
            )
            DwReferenceStore.store(
                root,
                DwReferenceStore.cacheKey("ProductDocumentation", "WORKSHOP", owner, "artisan-2"),
                DwReferenceList(
                    model = "ProductDocumentation",
                    filteredBy = "artisan-2",
                    items = listOf(DwReferenceOption(id = "p-2", label = "Bandha dupatta", filterValue = "artisan-2")),
                ),
            )
        }

        val offered = runBlocking {
            DwReferenceStore.anyForModel(root, "ProductDocumentation", "WORKSHOP", owner)
        }

        assertNotNull("the offline cascade lost its cache", offered)
        assertEquals(
            "both of this workshop's cached slices must merge",
            listOf("p-1", "p-2"),
            offered!!.items.map { it.id }.sorted(),
        )
        assertEquals(listOf("p-1"), offered.narrowedTo("artisan-1").map { it.id })
    }

    @Test
    fun `an ALL-scoped register stays shared across every workshop on the device`() {
        val fetchedByAnEarlierWorkshop =
            DwReferenceStore.cacheKey("Artisan", "ALL", "cmsearlier00000h8xc1lcy661a", "kalahandi")
        runBlocking {
            DwReferenceStore.store(
                root,
                fetchedByAnEarlierWorkshop,
                DwReferenceList(
                    model = "Artisan",
                    filteredBy = "kalahandi",
                    items = listOf(DwReferenceOption(id = "a-9", label = "Sita Devi", hint = "Kalahandi")),
                ),
            )
        }

        val offered = runBlocking {
            DwReferenceStore.anyForModel(root, "Artisan", "ALL", dwReferenceCacheOwner("cmsbrandnew0000h8xc1lcy66"))
        }

        assertNotNull("the shared ALL-scoped register stopped being shared", offered)
        assertEquals(listOf("a-9"), offered!!.items.map { it.id })
    }

    private fun wholeTable(): DwReferenceList = DwReferenceList(
        model = "Artisan",
        items = listOf(
            DwReferenceOption(id = "stranger-1", label = "Abani Behera", hint = "Bargarh"),
            DwReferenceOption(id = "stranger-2", label = "Bhagirathi Meher", hint = "Sonepur"),
        ),
    )
}
