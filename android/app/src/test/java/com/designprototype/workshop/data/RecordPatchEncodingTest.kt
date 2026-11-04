package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordPatchEncodingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    private fun keysOf(encoded: String): Set<String> =
        Regex("\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:").findAll(encoded).map { it.groupValues[1] }.toSet()

    @Test
    fun `a product demoted to every default still sends all three fields`() {
        val body = ProductCreateRequest(
            craftName = "Sambalpuri Bandha",
            place = "Barpali",
            artisanName = "Sushila Meher",
            productName = "Bandha table runner",
            productType = "OTHER",
            marketDemand = "UNKNOWN",
            status = "PENDING",
        )
        val keys = keysOf(json.encodeToString(ProductCreateRequest.serializer(), body))

        assertTrue("productType was dropped from the PATCH", "productType" in keys)
        assertTrue("marketDemand was dropped from the PATCH", "marketDemand" in keys)
        assertTrue("status was dropped from the PATCH", "status" in keys)
    }

    @Test
    fun `a tool corrected to UNKNOWN still sends maker and traditionType`() {
        val body = ToolCreateRequest(
            craftName = "Sambalpuri Bandha",
            place = "Barpali",
            artisanName = "Sushila Meher",
            toolkitName = "Charkha",
            maker = "UNKNOWN",
            traditionType = "UNKNOWN",
            status = "PENDING",
        )
        val keys = keysOf(json.encodeToString(ToolCreateRequest.serializer(), body))
        assertTrue("maker was dropped", "maker" in keys)
        assertTrue("traditionType was dropped", "traditionType" in keys)
        assertTrue("status was dropped", "status" in keys)
    }

    @Test
    fun `unticking pre-processes available reaches the server`() {
        val body = ProcessCreateRequest(name = "Tying", productId = "p1", preProcessAvailable = false)
        val keys = keysOf(json.encodeToString(ProcessCreateRequest.serializer(), body))
        assertTrue("preProcessAvailable was dropped", "preProcessAvailable" in keys)
        assertTrue("status was dropped", "status" in keys)
    }

    @Test
    fun `demoting an artisan and a workshop from APPROVED to PENDING reaches the server`() {
        val artisan = ArtisanCreateRequest(name = "Sushila Meher", place = "Barpali", status = "PENDING")
        assertTrue("status" in keysOf(json.encodeToString(ArtisanCreateRequest.serializer(), artisan)))

        val workshop = WorkshopCreateRequest(
            title = "Barpali cluster visit", date = "2026-01-12", place = "Barpali", status = "PENDING",
        )
        val keys = keysOf(json.encodeToString(WorkshopCreateRequest.serializer(), workshop))
        assertTrue("status" in keys)
        assertTrue("workshopType" in keys)
    }

    @Test
    fun `a nullable field with no value is still omitted`() {
        val body = ArtisanCreateRequest(name = "Ram Meher", place = "Barpali")
        val keys = keysOf(json.encodeToString(ArtisanCreateRequest.serializer(), body))
        assertTrue("localName" !in keys)
        assertTrue("aadhaarNumber" !in keys)
        assertTrue("pehchanCardAvailable" !in keys)
        assertTrue("status" in keys)
    }

    @Test
    fun `a non-default value was never the problem and still travels`() {
        val body = ProductCreateRequest(
            craftName = "c", place = "p", artisanName = "a", productName = "n",
            productType = "SAMPLE", marketDemand = "HIGH", status = "APPROVED",
        )
        val encoded = json.encodeToString(ProductCreateRequest.serializer(), body)
        assertTrue(encoded.contains("\"SAMPLE\""))
        assertTrue(encoded.contains("\"HIGH\""))
        assertTrue(encoded.contains("\"APPROVED\""))
    }

    @Test
    fun `every enum and flag a record form can set is on the wire at its default`() {
        val cases = listOf<Pair<String, Set<String>>>(
            json.encodeToString(
                ArtisanCreateRequest.serializer(),
                ArtisanCreateRequest(name = "n", place = "p")
            ) to setOf("status"),
            json.encodeToString(
                ProductCreateRequest.serializer(),
                ProductCreateRequest(craftName = "c", place = "p", artisanName = "a", productName = "n")
            ) to setOf("productType", "marketDemand", "status"),
            json.encodeToString(
                ToolCreateRequest.serializer(),
                ToolCreateRequest(craftName = "c", place = "p", artisanName = "a", toolkitName = "t")
            ) to setOf("maker", "traditionType", "status"),
            json.encodeToString(
                WorkshopCreateRequest.serializer(),
                WorkshopCreateRequest(title = "t", date = "d", place = "p")
            ) to setOf("workshopType", "status"),
            json.encodeToString(
                ProcessCreateRequest.serializer(),
                ProcessCreateRequest(name = "n", productId = "p")
            ) to setOf("preProcessAvailable", "status"),
        )

        val missing = cases.flatMap { (encoded, required) -> (required - keysOf(encoded)).toList() }
        assertEquals("these keys are dropped from their PATCH body: $missing", emptyList<String>(), missing)
    }
}
