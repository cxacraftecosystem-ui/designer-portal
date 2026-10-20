package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The picker's search folds each craft once per catalogue (2026-10-20 audit). This holds the indexed
 * search to the rule it replaced, word for word: the old per-keystroke implementation is kept here
 * as the reference, and every query below must rank the 455-craft master list identically.
 */
class CraftCatalogueSearchIndexTest {
    private val body: String = javaClass.getResourceAsStream("/craft-catalogue.json")!!.bufferedReader().readText()
    private val catalogue: CraftCatalogue = CraftCatalogueCache.parse(body)!!

    private fun referenceCategory(craft: CatalogueCraft): String {
        val categoryId = craft.categoryId ?: catalogue.subCategoryParent(craft.subCategoryId)
        return catalogue.categories.firstOrNull { it.id == categoryId }?.name.orEmpty()
    }

    private fun referenceSub(craft: CatalogueCraft): String =
        catalogue.categories.flatMap { it.subCategories }.firstOrNull { it.id == craft.subCategoryId }?.name.orEmpty()

    private fun referenceScore(craft: CatalogueCraft, query: String): Int? {
        val fold = CraftCatalogueCache::fold
        val score = CraftCatalogueCache::score
        val q = fold(query)
        if (q.isEmpty()) return 0
        val name = score(fold(craft.name), q)
        if (name < 4) return name
        val second = (listOf(craft.localName, craft.slug) + craft.aliases).minOfOrNull { score(fold(it), q) } ?: 4
        if (second < 4) return 10 + second
        val third = (craft.stateOfPractice + craft.stateOfOrigin + listOf(referenceSub(craft), referenceCategory(craft)))
            .minOfOrNull { score(fold(it), q) } ?: 4
        if (third < 4) return 20 + third
        val words = q.split(" ")
        if (words.size > 1) {
            val haystack = fold(
                (listOf(craft.name, craft.localName.orEmpty(), craft.slug) + craft.aliases + craft.stateOfPractice + craft.stateOfOrigin +
                    listOf(referenceSub(craft), referenceCategory(craft))).joinToString(" ")
            )
            if (words.all { haystack.contains(it) }) return 30
        }
        return null
    }

    private fun referenceSearch(query: String): List<String> =
        catalogue.crafts.withIndex()
            .mapNotNull { (at, craft) -> referenceScore(craft, query)?.let { Triple(craft, it, at) } }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .map { it.first.id }

    @Test
    fun `the indexed search ranks exactly as the per-keystroke one did`() {
        val queries = listOf(
            "a", "aj", "Ajrakh", "kutch ajrakh", "block", "Hand Block Printing", "gujarat", "Tamil Nadu",
            "textiles", "pottery", "metal", "bengal", "&", "Kútch–AJRAKH", "zzz", "wood carving", "in",
            "odisha pattachitra", "bamboo cane", "gi",
        )
        for (query in queries) {
            assertEquals(query, referenceSearch(query), CraftCatalogueCache.search(catalogue, query).map { it.id })
        }
    }

    @Test
    fun `every craft's heading is the one the list scans gave`() {
        for (craft in catalogue.crafts) {
            assertEquals(craft.id, referenceCategory(craft), CraftCatalogueCache.categoryName(catalogue, craft))
            assertEquals(craft.id, referenceSub(craft), CraftCatalogueCache.subCategoryName(catalogue, craft))
        }
    }

    @Test
    fun `a new catalogue is indexed afresh`() {
        val ajrakh = catalogue.crafts.first { it.name == "Kutch Ajrakh" }
        assertEquals(emptyList<String>(), CraftCatalogueCache.search(catalogue, "qwxyz renamed").map { it.id })
        val renamed = catalogue.copy(crafts = catalogue.crafts.map { if (it.id == ajrakh.id) it.copy(name = "Qwxyz Renamed") else it })
        assertEquals(listOf(ajrakh.id), CraftCatalogueCache.search(renamed, "qwxyz renamed").map { it.id })
    }
}
