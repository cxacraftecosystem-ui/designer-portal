package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NO FREE-TEXT CRAFT ON THE HANDSET (REQUIREMENTS_2026-10-20 §2.4): every craft is picked from the
 * catalogue through [CraftPickerField]. A source read, the same shape as the web's
 * `craft-no-free-text-unit.spec.ts`.
 */
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
        // Artisan, product, tool, workshop crafts covered, tool assignment.
        assertTrue(Regex("CraftPickerField\\(").findAll(activity).count() >= 5)
        assertTrue(read("ui/SearchScreen.kt").contains("CraftPickerField("))
        // The craft register every form reads is the catalogue.
        assertTrue(activity.contains("repository.craftCatalogue(context)"))
    }

    @Test
    fun `stage one's craft name is read-only once a craft is linked`() {
        val renderer = read("ui/designworkshop/FieldRenderer.kt")
        assertTrue(renderer.contains("enabled = enabled && !dwCraftNameLocked(field, rowValues)"))
    }

    /**
     * IDENTICAL IN BOTH APPS apart from the package and the app's own package prefix on imports.
     * The sibling checkout is optional (CI checks out one repo), so an absent sibling passes.
     */
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
