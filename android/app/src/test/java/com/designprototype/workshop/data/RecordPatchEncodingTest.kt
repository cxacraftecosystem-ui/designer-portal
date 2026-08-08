package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What actually reaches the wire when a designer edits a record back to a default value.
 *
 * THE TRAP, IN TWO HALVES THAT ARE EACH REASONABLE ALONE:
 *
 *  * `ApiClient.retrofit` builds its Json without `encodeDefaults`, so it is false, and kotlinx omits
 *    any property whose value equals its declared default. That single converter is the only one on
 *    the Retrofit every typed call goes through.
 *  * Every record PATCH route on the API reads its body with `model_dump(exclude_unset=True)` —
 *    verified in artisans.py, crafts.py, products.py, processes.py and the rest — so a key that is
 *    not present means "leave the stored value alone".
 *
 * Together they make the edit BACK to the default the one edit that cannot be saved. The form shows
 * the new value, the Save reports success, and the database never changed. Concretely: demoting a
 * record from APPROVED to PENDING, retyping a product from SAMPLE to OTHER, correcting marketDemand
 * from HIGH back to UNKNOWN, correcting a tool's maker or traditionType to UNKNOWN, and unticking
 * "Pre-processes available" on a process. Every one of them works in the browser, because the web
 * sends these fields unconditionally.
 *
 * The Json below is configured EXACTLY as ApiClient configures the real one. A test that set
 * `encodeDefaults = true` for its own convenience would pass against the defect it is guarding.
 */
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
            // Each of these IS the declared default — which is the whole point. Before this was
            // guarded, retyping a SAMPLE back to OTHER simply did not happen.
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
        // A Boolean whose default is false is the worst case of the shape: the value the designer
        // means to store is exactly the value that guarantees the key is omitted.
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
        // The one field that was already guarded, kept under test so the precedent cannot be undone.
        assertTrue("workshopType" in keys)
    }

    @Test
    fun `a nullable field with no value is still omitted`() {
        // The other half of the contract, and the reason `encodeDefaults` was not simply flipped to
        // true across the whole converter: `explicitNulls = false` omitting an unset optional is
        // load-bearing. `CraftCreateRequest.workshopId` documents it — the API's Pydantic base sets
        // `extra="forbid"`, and "no workshop named" has to travel as an ABSENT key, not as a null.
        val body = ArtisanCreateRequest(name = "Ram Meher", place = "Barpali")
        val keys = keysOf(json.encodeToString(ArtisanCreateRequest.serializer(), body))
        assertTrue("localName" !in keys)
        assertTrue("aadhaarNumber" !in keys)
        assertTrue("pehchanCardAvailable" !in keys)
        // …while the enum the designer can genuinely set to its default is present.
        assertTrue("status" in keys)
    }

    @Test
    fun `a non-default value was never the problem and still travels`() {
        // Guards against a "fix" that annotated the wrong thing: the failing case was only ever the
        // value that MATCHES the default, so a test that used APPROVED would have passed all along.
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
        // The sweep, so a field added to one of these six forms with a non-null default is caught
        // here rather than by a designer whose correction silently did nothing. Each pair is
        // (what the form holds, the keys that must survive encoding).
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
