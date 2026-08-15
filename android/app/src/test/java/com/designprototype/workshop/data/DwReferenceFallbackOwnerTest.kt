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

/**
 * THE PICKER'S LAST RESORT IS WHERE THE NARROWING WAS BEING GIVEN BACK.
 *
 * ── WHAT THIS PINS, AND WHY A SECOND FILE WAS NEEDED ──────────────────────────────────────────────
 *
 * [DwReferenceScopeWireTest] pins the REQUEST: a WORKSHOP-scoped picker now asks the server to narrow,
 * and a pre-fix cache file can no longer be reached by exact key because [dwReferenceCacheOwner]
 * stamps a generation onto the owner segment. Both of those assertions passed while the defect they
 * were written for was still reachable, and that is the whole reason this file exists.
 *
 * Retiring a file by changing the key it is filed under makes the EXACT KEY MISS. An exact-key miss
 * is precisely the condition that sends `WorkshopRepository.designWorkshopReferences` into
 * [DwReferenceStore.anyForModel] — and that fallback merged every file whose name began with the
 * model:
 *
 *     val prefix = safeName(model) + "__"          // the rule this test was written against
 *
 * The retired file is still called `Artisan__<workshop>___.json`. It still begins with `Artisan__`.
 * So the generation bump moved the poisoned list from "served by exact key" to "merged by the
 * fallback one function later", and a designer at stage 6 in a village was offered the same fifty
 * strangers from the whole artisan table as before. A cache-key test cannot see that, because the two
 * keys really are different strings; only a test that puts a file on disk and asks the store for an
 * answer can.
 *
 * The same missing segment is a second, independent defect the audit records on its own
 * (docs/AUDIT-2026-08-15.md — "`anyForModel` merges every cached list for a model across ALL
 * workshops on the device"): two clusters on one handset, and workshop A's products are offered in
 * workshop B. Both are fixed by the same fence and both are pinned below.
 *
 * ── WHY THESE TESTS BITE ──────────────────────────────────────────────────────────────────────────
 *
 * Every "must not be offered" test below writes its file through [DwReferenceStore.store] — the same
 * writer the repository uses — and then asserts, out loud, that the file name DOES satisfy the old
 * model-only prefix. That assertion is what makes the following `assertNull` meaningful: the old rule
 * demonstrably matched this exact file, merged it, and returned a non-empty list.
 *
 * That is not reasoning, it was measured. With `anyForModel`'s prefix put back to `safeName(model) +
 * "__"` and nothing else touched, `testDebugUnitTest` reports:
 *
 *     DwReferenceFallbackOwnerTest > a workshop-owned list written before the scope fix … FAILED
 *     DwReferenceFallbackOwnerTest > an ALL-owned register is not merged into a WORKSHOP-scoped … FAILED
 *     DwReferenceFallbackOwnerTest > another workshop's cached list is never offered in this … FAILED
 *     12 tests completed, 3 failed
 *
 * — the three negatives fail, the two positives below still pass, and all seven of
 * [DwReferenceScopeWireTest] pass in BOTH directions, which is the measurement that matters most:
 * the suite written alongside the `scope` fix cannot see this and never could.
 *
 * The two "must still be offered" tests are the other half, and they are not padding: the cheap fix
 * for all of the above — refuse the fallback whenever the scope is WORKSHOP — would pass every
 * negative test here and silently kill the offline cascade `productRef` depends on, and the shared
 * ALL-scoped register that makes a brand-new offline workshop usable at all.
 */
class DwReferenceFallbackOwnerTest {

    private lateinit var root: File

    /**
     * A real directory, because the thing under test is a rule about FILE NAMES. This module has no
     * Robolectric, so the store exposes directory-taking overloads for exactly this (see
     * [DwReferenceStore.load]); nothing in the app calls them.
     */
    @Before
    fun setUp() {
        root = java.nio.file.Files.createTempDirectory("dw-references-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ── The list that must never be served again ─────────────────────────────────────────────────

    @Test
    fun `a workshop-owned list written before the scope fix is not merged back in`() {
        // Every build before the `scope` fix filed the WHOLE artisan table under this workshop's own
        // id, because it asked for a workshop-scoped field and the server, told nothing, answered ALL.
        // This is that file, written with the key that build composed: the bare workshop id, no
        // generation.
        val workshop = "cmsik2jg8000eh8xc1lcy661a"
        val staleKey = DwReferenceStore.cacheKey("Artisan", "WORKSHOP", workshop, "")
        runBlocking { DwReferenceStore.store(root, staleKey, wholeTable()) }

        // PROOF THIS TEST BITES. The rule this replaced was `name.startsWith(safeName(model) + "__")`
        // and nothing more. Asserting the file satisfies it is asserting that the old implementation
        // reached this file, decoded it and flattened it into its answer — so the null below is a
        // statement about behaviour that genuinely changed, not about a file nobody was looking at.
        assertTrue(
            "the old model-only prefix must match this file, or this test proves nothing",
            File(root, "$staleKey.json").name.startsWith("Artisan__"),
        )

        // Today's owner carries the generation. The exact key misses — that is what retirement MEANS
        // — and this is the path that miss falls into.
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
        // Artisan is declared BOTH ways in the registry, so a device legitimately holds an ALL-owned
        // artisan register at the same time as a workshop-scoped picker asks for this workshop's
        // artisans. Merging the first into the second is the un-narrowed list arriving by another
        // door — no stale file and no second workshop required, so this survives on a phone that has
        // never held either.
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
        // Two clusters run from one handset. Workshop A's products were fetched; workshop B opens the
        // same field offline and has nothing of its own. `narrowedTo` cannot rescue this — it filters
        // on the parent artisan id, which says nothing about which workshop the row belongs to — so
        // picking one writes A's product id, name, material and price onto a row in B.
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

    // ── What the fence must NOT take away ────────────────────────────────────────────────────────

    @Test
    fun `this workshop's own cached lists still merge, so the offline cascade survives`() {
        // `productRef` is WORKSHOP-scoped AND cascaded: this workshop's products, narrowed to the
        // artisan chosen on the row. Offline it works only because the fallback merges what this
        // device already holds for the model. Here the device holds two per-artisan slices and no
        // whole-model file, so the answer can ONLY come from the merge — which is what makes this the
        // test that fails if somebody "fixes" the leak above by refusing the fallback for WORKSHOP
        // scope.
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
        // And the narrowing the picker applies on top of the merge still works on the merged list.
        assertEquals(listOf("p-1"), offered.narrowedTo("artisan-1").map { it.id })
    }

    @Test
    fun `an ALL-scoped register stays shared across every workshop on the device`() {
        // The header's central promise: a workshop created in a village, with no server id and no
        // signal, still picks from the artisan register some EARLIER workshop on this handset
        // downloaded. That sharing is why ALL-scoped lists are keyed by model alone, and the fence
        // must not touch it — the owner segment is the literal "ALL" for every one of them.
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

    /** The fifty-row, name-ascending slice of the whole table that a scope-less request came back with. */
    private fun wholeTable(): DwReferenceList = DwReferenceList(
        model = "Artisan",
        items = listOf(
            DwReferenceOption(id = "stranger-1", label = "Abani Behera", hint = "Bargarh"),
            DwReferenceOption(id = "stranger-2", label = "Bhagirathi Meher", hint = "Sonepur"),
        ),
    )
}
