package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DesignerDirectoryEntryDto
import com.designprototype.workshop.data.DesignerRosterDto
import com.designprototype.workshop.data.PageResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignerRosterWireTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    private val servedPage = """
        {
          "items": [
            {
              "id": "cmsroster0001",
              "email": "a.sharma@example.org",
              "fullName": "A. Sharma",
              "institution": "NID",
              "notes": null,
              "isActive": true,
              "revokedAt": null,
              "firstSeenAt": "2026-03-02T09:14:00+00:00",
              "createdAt": "2026-02-27T11:00:00+00:00",
              "updatedAt": "2026-03-02T09:14:00+00:00",
              "addedById": "cmsadmin0001"
            },
            {
              "id": "cmsroster0002",
              "email": "b.rao@example.org",
              "fullName": null,
              "institution": null,
              "notes": "Standing in for the Bagru cluster",
              "isActive": false,
              "revokedAt": "2026-07-19T05:00:00+00:00",
              "firstSeenAt": null,
              "createdAt": "2026-01-04T08:00:00+00:00",
              "updatedAt": "2026-07-19T05:00:00+00:00",
              "addedById": "cmsadmin0001"
            }
          ],
          "total": 2,
          "page": 1,
          "pageSize": 50,
          "pages": 1
        }
    """.trimIndent()

    @Test
    fun `the served roster decodes as a page`() {
        val page = json.decodeFromString<PageResponse<DesignerRosterDto>>(servedPage)

        assertEquals(2, page.items.size)
        assertEquals(2, page.total)
        assertEquals("a.sharma@example.org", page.items[0].email)
        assertFalse(page.items[1].isActive)
        assertNotNull(page.items[1].revokedAt)
        assertNull(page.items[1].firstSeenAt)
    }

    @Test
    fun `reading the same payload as a bare list fails — the defect this replaces`() {
        var thrown: Throwable? = null
        try {
            json.decodeFromString<List<DesignerRosterDto>>(servedPage)
        } catch (error: Throwable) {
            thrown = error
        }
        assertTrue(
            "an object cannot decode into a list; isLenient and coerceInputValues do not bridge it",
            thrown is SerializationException || thrown is IllegalArgumentException
        )
    }

    @Test
    fun `a server that has not learned a column yet does not take the screen down`() {
        val page = json.decodeFromString<PageResponse<DesignerRosterDto>>(
            """{"items":[{"id":"r1","email":"c.iyer@example.org"}],"total":1,"page":1,"pageSize":50,"pages":1}"""
        )

        assertEquals(1, page.items.size)
        assertTrue("a row with no isActive must read as able to sign in", page.items[0].isActive)
        assertNull(page.items[0].fullName)
    }

    @Test
    fun `the account behind a row is found whatever case the address was typed in`() {
        val accounts = accountsByEmail(
            listOf(
                DesignerDirectoryEntryDto(id = "u-1", email = "A.Sharma@Example.org", name = "A. Sharma"),
                DesignerDirectoryEntryDto(id = "u-2", email = "  d.nair@example.org  ", name = "D. Nair")
            )
        )

        assertEquals("u-1", accounts["a.sharma@example.org"])
        assertEquals("u-2", accounts["d.nair@example.org"])
    }

    @Test
    fun `a row whose invitation is outstanding offers no profile`() {
        val accounts = accountsByEmail(
            listOf(DesignerDirectoryEntryDto(id = "u-1", email = "a.sharma@example.org"))
        )

        assertNull(accounts["b.rao@example.org"])
    }

    @Test
    fun `a directory row with no id or no address is skipped rather than keyed blank`() {
        val accounts = accountsByEmail(
            listOf(
                DesignerDirectoryEntryDto(id = "", email = "ghost@example.org"),
                DesignerDirectoryEntryDto(id = "u-3", email = ""),
                DesignerDirectoryEntryDto(id = "u-4", email = "e.pillai@example.org")
            )
        )

        assertEquals(mapOf("e.pillai@example.org" to "u-4"), accounts)
    }

    @Test
    fun `the directory cap this screen reports is the one the server enforces`() {
        assertEquals(500, DESIGNER_DIRECTORY_CAP)
    }

}
