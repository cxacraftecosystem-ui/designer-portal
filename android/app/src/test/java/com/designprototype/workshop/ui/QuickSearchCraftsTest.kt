package com.designprototype.workshop.ui

import com.designprototype.workshop.data.CraftDto
import com.designprototype.workshop.data.PageResponse
import com.designprototype.workshop.data.SearchResultsDto
import com.designprototype.workshop.data.SearchTotalsDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSearchCraftsTest {
    private val namda = CraftDto(id = "c1", name = "Namda Craft", category = "Textiles", place = "Kashmir")
    private val kalamkari = CraftDto(id = "c2", name = "Kalamkari", localName = "కలంకారి")

    private fun crafts(vararg items: CraftDto, total: Int = items.size): suspend (Int, Int) -> PageResponse<CraftDto> =
        { page, size -> PageResponse(items.toList(), total, page, size, 1) }

    @Test
    fun `everything search for ka lists crafts beside the records`() = runBlocking {
        var askedTypes: List<String>? = listOf("unset")
        val result = searchWithCrafts(SearchFilters(query = "ka"), null, 1, 8, crafts(namda, kalamkari, total = 36)) { types ->
            askedTypes = types
            SearchResultsDto(totals = SearchTotalsDto(), total = 0)
        }
        assertNull(askedTypes)
        assertEquals(36, result.totals.crafts)
        val bucket = result.toSearchBuckets().first { it.recordType == SearchRecordTypes.CRAFT }
        assertEquals("Crafts", bucket.title)
        assertEquals(36, bucket.total)
        assertEquals(listOf("Namda Craft", "Kalamkari"), bucket.rows.map { it.title })
        assertEquals("Textiles · Kashmir", bucket.rows.first().subtitle)
    }

    @Test
    fun `a page of crafts alone never sends the craft bucket to the record search`() = runBlocking {
        var called = false
        val result = searchWithCrafts(SearchFilters(query = "ka"), listOf("crafts"), 2, 20, crafts(namda)) {
            called = true
            SearchResultsDto()
        }
        assertFalse(called)
        assertEquals(listOf("c1"), result.crafts.map { it.id })
    }

    @Test
    fun `record types without crafts leave crafts out`() = runBlocking {
        val filters = SearchFilters(query = "ka", types = setOf(SearchRecordTypes.ARTISAN))
        assertFalse(filters.searchesCrafts())
        var asked: List<String>? = null
        val result = searchWithCrafts(filters, filters.bucketTypes(), 1, 8, crafts(namda)) { types -> asked = types; SearchResultsDto() }
        assertEquals(listOf("artisans"), asked)
        assertTrue(result.crafts.isEmpty())
    }

    @Test
    fun `a failed craft lookup keeps the record results`() = runBlocking {
        val records = SearchResultsDto(total = 3, totals = SearchTotalsDto(artisans = 3))
        val result = searchWithCrafts(SearchFilters(query = "ka"), null, 1, 8, { _, _ -> error("offline") }) { records }
        assertEquals(records, result)
    }

    @Test
    fun `filters crafts cannot honour skip the craft lookup`() {
        assertFalse(SearchFilters(query = "ka", place = "Jaipur").searchesCrafts())
        assertFalse(SearchFilters(query = "").searchesCrafts())
        assertTrue(SearchFilters(query = "ka").searchesCrafts())
        assertFalse(SearchRecordTypes.CRAFT in SearchRecordTypes.ALL)
    }
}
