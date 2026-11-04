package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CraftPickerSourceTest {
    private val main = File("src/main/java/com/designprototype/workshop")
    private fun read(path: String) = File(main, path).readText()

    @Test
    fun `the artisan form has no Or new craft name box`() {
        val activity = read("MainActivity.kt")
        assertFalse(activity.contains("Text(\"Or new craft name\")"))
        assertFalse(activity.contains("newCraftName"))
    }

    @Test
    fun `no form types a craft name`() {
        val activity = read("MainActivity.kt")
        assertFalse(activity.contains("RequiredInput(\"Craft name\", craftName"))
        assertFalse(activity.contains("label = \"Linked craft (fills craft name)\""))
        assertFalse(activity.contains("label = \"Linked crafts (fills craft name)\""))
        assertFalse(activity.contains("TextInput(\"Category\", category)"))
    }

    @Test
    fun `every craft picker is the catalogue picker`() {
        val activity = read("MainActivity.kt")
        assertTrue(Regex("CraftPickerField\\(").findAll(activity).count() >= 5)
        assertTrue(read("ui/SearchScreen.kt").contains("CraftPickerField("))
        assertTrue(activity.contains("repository.craftCatalogue(context)"))
    }

    @Test
    fun `the stage craft link is the catalogue picker, as in the Field Repository`() {
        val field = read("ui/designworkshop/DwReferenceField.kt")
        assertTrue(field.contains("if (field.refModel == \"Craft\") {"))
        val branch = field.substringAfter("if (field.refModel == \"Craft\") {").substringBefore("} else SearchableSelectField(")
        assertTrue(branch.contains("CraftPickerField("))
        assertTrue(branch.contains("choose(chosen, known)"))
        assertTrue(branch.contains("designWorkshopReferenceById("))
        assertTrue(branch.contains("craftCatalogue(context)"))
    }

    @Test
    fun `stage one's craft name is always read-only beside its craft link`() {
        val renderer = read("ui/designworkshop/FieldRenderer.kt")
        assertTrue(renderer.contains("enabled = enabled && !dwCraftNameLocked(field, siblings)"))
        assertTrue(renderer.contains("rowValues = rowValues,\n                    siblings = siblings,"))
        val craftRef = com.designprototype.workshop.data.FieldDto(key = "craftRef", type = "REF", refModel = "Craft")
        val craftName = com.designprototype.workshop.data.FieldDto(key = "craftName")
        assertTrue(com.designprototype.workshop.ui.designworkshop.dwCraftNameLocked(craftName, mapOf("craftRef" to craftRef)))
        assertFalse(com.designprototype.workshop.ui.designworkshop.dwCraftNameLocked(craftName, emptyMap()))
    }

    @Test
    fun `review edit shows a product's or tool's craft read-only`() {
        val activity = read("MainActivity.kt")
        assertFalse(activity.contains("ReviewField(\"craftName\", \"Craft name\")"))
        assertEquals(2, Regex("ReviewField\\(\"craftName\", \"Craft\", readOnly = true\\)").findAll(activity).count())
        assertTrue(activity.contains(".filterNot { it.readOnly }"))
    }

    @Test
    fun `the two shared craft files match the documentation portal's`() {
        val sibling = File("../../../documentation-portal/android/app/src/main/java/com/fieldrepository/app")
        for (path in listOf("ui/CraftPicker.kt", "data/CraftCatalogueCache.kt")) {
            val theirs = File(sibling, path)
            if (!theirs.exists()) continue
            assertEquals(path, normalise(read(path), "com.designprototype.workshop"), normalise(theirs.readText(), "com.fieldrepository.app"))
        }
    }

    private fun normalise(source: String, prefix: String) =
        source.replace("\r\n", "\n").replace(prefix, "APP")
}
