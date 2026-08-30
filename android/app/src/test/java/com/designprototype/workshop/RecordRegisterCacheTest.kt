package com.designprototype.workshop

import com.designprototype.workshop.data.ArtisanDto
import com.designprototype.workshop.data.CraftDto
import com.designprototype.workshop.data.DwReferenceOption
import com.designprototype.workshop.data.DwReferenceStore
import com.designprototype.workshop.data.ProductDetailDto
import com.designprototype.workshop.data.ToolDetailDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1's encode/decode pair for each of the four record registers, and the one invariant the whole
 * feature depends on: that this cache's keys never collide with a design-workshop stage's.
 *
 * ── WHAT THIS FILE DOES NOT COVER, AND WHY ──────────────────────────────────────────────────────
 *
 * [loadCachedRegister] and its four typed wrappers ([loadCraftRegister] and its three siblings) call
 * the Context-based [DwReferenceStore.load] / [DwReferenceStore.store] overloads, and this module has
 * no Robolectric on its unit-test classpath — see [DwReferenceFallbackOwnerTest]'s own note on that,
 * and [DwReferenceStore]'s File-based overloads that exist BECAUSE of it. This file therefore tests
 * only what is reachable without a Context: the pure `*ToOption` / `optionTo*` conversions each
 * wrapper is built from, and the model-key constants, which is also where the actual correctness risk
 * of this feature lives — a field dropped on write or restored under the wrong name is silent forever
 * (a null a form quietly treats as "not carried"), where a Context this file cannot construct is a
 * loud, obvious dependency nobody is likely to get wrong by accident.
 *
 * ── WHY THE ROUND TRIPS ARE CHECKED FIELD BY FIELD, NOT WITH `equals` ───────────────────────────
 *
 * Every `optionTo*` function reconstructs a DTO with several fields left at their type's own default
 * (`status = ""`, `location = null`, `createdAt = null`, …) rather than the original record's real
 * values, DELIBERATELY — see [craftToOption]'s and [artisanToOption]'s own KDoc for the grep that
 * checked no record form reads those fields off a cached list. A plain `assertEquals(original,
 * roundTripped)` would therefore fail on every case and prove nothing about the fields that matter;
 * these tests name the fields the cache actually promises and leave the rest alone.
 */
class RecordRegisterCacheTest {

    @Test
    fun `a craft's id, name and place survive the round trip`() {
        val craft = CraftDto(id = "craft-1", name = "Bidriware", place = "Bidar")
        val restored = optionToCraft(craftToOption(craft))
        assertEquals(craft.id, restored?.id)
        assertEquals(craft.name, restored?.name)
        assertEquals(craft.place, restored?.place)
    }

    @Test
    fun `a craft with no place round-trips to a null place, not an empty string`() {
        // DwReferenceOption.hint defaults to "", and CraftDto.place is nullable — the decode side
        // has to fold that empty string back to null, or every cached craft with no place on record
        // would print an empty hint on the picker forever rather than showing nothing at all.
        val craft = CraftDto(id = "craft-2", name = "Undocumented place", place = null)
        val restored = optionToCraft(craftToOption(craft))
        assertNull(restored?.place)
    }

    @Test
    fun `an artisan's id, name, place and craftId survive the round trip`() {
        val artisan = ArtisanDto(
            id = "artisan-1", name = "Ram Kumar", place = "Chanderi", status = "APPROVED", craftId = "craft-1"
        )
        val restored = optionToArtisan(artisanToOption(artisan))
        assertEquals(artisan.id, restored?.id)
        assertEquals(artisan.name, restored?.name)
        assertEquals(artisan.place, restored?.place)
        assertEquals(artisan.craftId, restored?.craftId)
    }

    @Test
    fun `an artisan linked to no craft round-trips to a null craftId, not an empty string`() {
        // ToolForm's cascade reads `artisans.filter { it.craftId == craftId }` and a linked-craft
        // dropdown reads `artisanId.isBlank()`-shaped absence the same way `craftId == null` is
        // meant to read — an empty-string craftId here would make an unlinked artisan match a craft
        // whose id happens to also be blank, which cannot occur today but is the wrong shape to cache.
        val artisan = ArtisanDto(id = "artisan-2", name = "Unlinked", place = "Somewhere", status = "", craftId = null)
        val restored = optionToArtisan(artisanToOption(artisan))
        assertNull(restored?.craftId)
    }

    @Test
    fun `a product's id, name, artisan name and artisanId survive the round trip`() {
        val product = ProductDetailDto(
            id = "product-1", productName = "Terracotta pot", artisanName = "Ram Kumar", artisanId = "artisan-1"
        )
        val restored = optionToProduct(productToOption(product))
        assertEquals(product.id, restored?.id)
        assertEquals(product.productName, restored?.productName)
        assertEquals(product.artisanName, restored?.artisanName)
        assertEquals(product.artisanId, restored?.artisanId)
    }

    @Test
    fun `a product with no artisanId round-trips to null, not an empty string`() {
        // DwReferenceList.narrowedTo keeps a blank-filterValue option rather than dropping it — an
        // empty-string artisanId here rather than null would instead make this row match EVERY
        // artisan's cascade, the over-claim narrowedTo's own KDoc warns against for exactly this
        // field. See DwReferenceStore.kt's note on DwReferenceOption.filterValue.
        val product = ProductDetailDto(id = "product-2", productName = "Free-text artisan", artisanId = null)
        val restored = optionToProduct(productToOption(product))
        assertNull(restored?.artisanId)
    }

    @Test
    fun `a tool's id, toolkit name and craft name survive the round trip`() {
        val tool = ToolDetailDto(id = "tool-1", toolkitName = "Potter's wheel", craftName = "Terracotta")
        val restored = optionToTool(toolToOption(tool))
        assertEquals(tool.id, restored?.id)
        assertEquals(tool.toolkitName, restored?.toolkitName)
        assertEquals(tool.craftName, restored?.craftName)
    }

    @Test
    fun `every decode refuses a blank id rather than manufacturing a record with none`() {
        // A DwReferenceOption with a blank id is not a real cached row — DwReferenceStore never
        // writes one, since every *ToOption function above sources `id` from the DTO's own required
        // `id` field — but a future cache file written by a different build, or hand-edited on a
        // rooted device, is not a case any of these four functions should paper over with a synthetic
        // "" id a picker would then offer as a selectable row that saves as an empty foreign key.
        val blank = DwReferenceOption(id = "", label = "Ghost")
        assertNull(optionToCraft(blank))
        assertNull(optionToArtisan(blank))
        assertNull(optionToProduct(blank))
        assertNull(optionToTool(blank))
    }

    @Test
    fun `the four register keys are pairwise distinct and none collides with a stage REF model name`() {
        // The whole safety argument in the section header above HomeScreen, checked mechanically
        // rather than left as a comment nobody re-verifies: a design-workshop stage's REF field
        // caches its ALL-scoped lists under precisely "Craft", "Artisan", "Product" and "Tool" (see
        // DwReferenceField.kt's REF_MODEL_LABELS and WorkshopRepository.kt's own registry), and this
        // cache's own keys must never equal one of those four bare names or the two caches' writes
        // would silently overwrite each other under one file — see the header comment on
        // loadCachedRegister for what that failure looks like from either side.
        val registers = listOf(REGISTER_CRAFT, REGISTER_ARTISAN, REGISTER_PRODUCT, REGISTER_TOOL)
        assertEquals(
            "the four record-form register keys must be pairwise distinct",
            registers.size,
            registers.toSet().size
        )
        val stageModelNames = setOf("Craft", "Artisan", "Product", "Tool")
        for (register in registers) {
            assertTrue(
                "\"$register\" collides with a design-workshop stage's own REF model name",
                register !in stageModelNames
            )
        }
        // And the derived cache key really does carry the ALL owner an unscoped register needs —
        // R6's argument applies to the ACCESS-scoped pickers only, never to these four, but the
        // owner segment still has to resolve to "ALL" for the sharing-across-workshops property
        // DwReferenceStore.kt's header describes to hold for this cache too.
        for (register in registers) {
            val key = DwReferenceStore.cacheKey(register, "ALL", "some-workshop-id", "")
            assertTrue(
                "\"$register\"'s cache key must resolve to the ALL owner regardless of any workshop id",
                key.startsWith("${register}__ALL__")
            )
        }
    }
}
