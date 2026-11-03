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
        val blank = DwReferenceOption(id = "", label = "Ghost")
        assertNull(optionToCraft(blank))
        assertNull(optionToArtisan(blank))
        assertNull(optionToProduct(blank))
        assertNull(optionToTool(blank))
    }

    @Test
    fun `the four register keys are pairwise distinct and none collides with a stage REF model name`() {
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
        for (register in registers) {
            val key = DwReferenceStore.cacheKey(register, "ALL", "some-workshop-id", "")
            assertTrue(
                "\"$register\"'s cache key must resolve to the ALL owner regardless of any workshop id",
                key.startsWith("${register}__ALL__")
            )
        }
    }
}
