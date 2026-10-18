package com.designprototype.workshop.data

import com.designprototype.workshop.ui.AppSearchGroup
import com.designprototype.workshop.ui.unifiedOpenId
import com.designprototype.workshop.ui.unifiedSearchItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `GET /search/unified` and `GET /search/remote/{type}/{id}` as the phone reads them
 * (docs/SEARCH.md): decoded with the app's own `Json`, unknown types ignored, local rows open this
 * app's screens and remote rows the read-only card.
 */
class UnifiedSearchParsingTest {

    private val body = """
        {"query": "indigo", "mode": "hybrid",
         "groups": [
           {"type": "media", "label": "Media", "total": 1, "localTotal": 1, "remoteTotal": 0,
            "items": [{"portal": "designer", "local": true, "type": "media", "id": "m1", "title": "vat.mp3",
                       "href": "/media/m1", "highlights": [[0, 3]], "score": 0.016}]},
           {"type": "artisans", "label": "Artisans", "total": 6, "localTotal": 1, "remoteTotal": 5,
            "items": [
              {"portal": "designer", "local": true, "type": "artisans", "id": "a1", "title": "Ramesh",
               "subtitle": "Indigo dyeing", "href": "/artisans/a1", "openUrl": null, "score": 0.0164},
              {"portal": "fieldRepository", "local": false, "type": "artisans", "id": "fr9", "title": "Meena",
               "href": "/remote/artisans/fr9", "openUrl": "https://docs.example/artisans/fr9", "score": 0.0164,
               "somethingNew": true}
            ]},
           {"type": "reports", "label": "Reports", "total": 1, "localTotal": 1, "remoteTotal": 0,
            "items": [{"portal": "designer", "local": true, "type": "reports", "id": "r1", "title": "camp.pdf",
                       "href": "/design-workshops/dw7"}]},
           {"type": "holograms", "label": "?", "total": 1, "items": [{"id": "h1", "title": "?"}]}
         ],
         "remote": {"portal": "fieldRepository", "label": "Field Repository", "status": "ok"},
         "typesRefused": ["designWorkshops"]}
    """.trimIndent()

    private fun parse(): UnifiedSearchResponse = ApiClient.json.decodeFromString(UnifiedSearchResponse.serializer(), body)

    @Test
    fun `the answer decodes, and items come in vocabulary order with unknown types dropped`() {
        val response = parse()
        assertEquals("hybrid", response.mode)
        assertEquals("ok", response.remote.status)
        assertEquals(listOf("designWorkshops"), response.typesRefused)
        assertEquals(listOf("a1", "fr9", "r1", "m1"), response.knownItems().map { it.id })
        assertEquals(listOf(0, 3), response.knownItems().last().highlights.single())
    }

    @Test
    fun `local rows open this app, remote rows carry their portal and open the card`() {
        val opened = mutableListOf<String>()
        val items = unifiedSearchItems(
            parse(),
            openRecord = { type, id -> opened += "record:$type:$id" },
            openCluster = { name -> opened += "cluster:$name" },
            openRemote = { type, id -> opened += "remote:$type:$id" }
        )
        assertEquals(
            listOf(AppSearchGroup.ARTISANS, AppSearchGroup.ARTISANS, AppSearchGroup.REPORTS, AppSearchGroup.MEDIA),
            items.map { it.group }
        )
        assertNull(items[0].portal)
        assertEquals("fieldRepository", items[1].portal)
        items.forEach { it.onOpen() }
        assertEquals(
            listOf("record:artisan:a1", "remote:artisans:fr9", "record:designWorkshop:dw7", "record:media:m1"),
            opened
        )
    }

    @Test
    fun `a report opens its design workshop and everything else opens itself`() {
        assertEquals("dw7", unifiedOpenId(UnifiedSearchItemDto(type = "reports", id = "r1", href = "/design-workshops/dw7")))
        assertEquals("r1", unifiedOpenId(UnifiedSearchItemDto(type = "reports", id = "r1", href = null)))
        assertEquals("p1", unifiedOpenId(UnifiedSearchItemDto(type = "products", id = "p1", href = "/products/p1")))
    }

    @Test
    fun `a remote record decodes into its card rows`() {
        val record = ApiClient.json.decodeFromString(
            RemoteRecord.serializer(),
            """{"portal": "fieldRepository", "type": "products", "id": "fr9", "title": "Stole",
                "fields": [{"label": "Craft", "value": "Bagru"}, {"label": "Place", "value": ""}],
                "recordDate": "2026-09-02T00:00:00Z", "openUrl": "https://docs.example/products/fr9"}"""
        )
        assertEquals(listOf("Craft" to "Bagru", "Recorded" to "2026-09-02"), record.cardRows { it.take(10) })
        assertEquals("Open in Field Repository", openInLabel(record.portal))
    }
}
