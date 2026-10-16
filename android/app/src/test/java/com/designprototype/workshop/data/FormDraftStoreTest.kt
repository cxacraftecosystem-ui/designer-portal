package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The on-disk draft every record form now keeps until Save (see [FormDraftStore]).
 *
 * What these pin is the promise to the person holding the phone: what they typed comes back after the
 * app is killed, it comes back only to THEM on a shared phone, a damaged file never costs them the
 * typing, and opening a form and closing it again leaves nothing behind.
 */
class FormDraftStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun root(): File = folder.newFolder("form-drafts")

    @Test
    fun a_saved_draft_reads_back_whole() {
        val root = root()
        val fields = mapOf("name" to "Ramesh", "notes" to "line one\nline two", "${URI_FIELD_PREFIX}media" to "content://a\ncontent://b")
        FormDraftStore.save(root, "user-1", "artisan:new", fields)

        val back = FormDraftStore.load(root, "user-1", "artisan:new")
        assertEquals(fields, back?.fields)
        assertEquals("user-1", back?.ownerUserId)
        assertTrue(back!!.savedAt.isNotBlank())
    }

    @Test
    fun another_account_on_the_same_phone_does_not_see_it_and_the_owner_gets_it_back() {
        val root = root()
        FormDraftStore.save(root, "user-1", "craft:new", mapOf("name" to "Bandhani"))

        assertNull(FormDraftStore.load(root, "user-2", "craft:new"))
        assertNull(FormDraftStore.load(root, null, "craft:new"))
        // Signing back in as the owner — nothing was deleted by somebody else's session.
        assertEquals("Bandhani", FormDraftStore.load(root, "user-1", "craft:new")?.fields?.get("name"))
    }

    @Test
    fun clear_removes_only_that_form() {
        val root = root()
        FormDraftStore.save(root, "u", "craft:new", mapOf("name" to "a"))
        FormDraftStore.save(root, "u", "tool:new", mapOf("toolkitName" to "b"))

        FormDraftStore.clear(root, "u", "craft:new")

        assertNull(FormDraftStore.load(root, "u", "craft:new"))
        assertEquals("b", FormDraftStore.load(root, "u", "tool:new")?.fields?.get("toolkitName"))
    }

    @Test
    fun a_second_save_replaces_the_first() {
        val root = root()
        FormDraftStore.save(root, "u", "product:new", mapOf("productName" to "first"))
        FormDraftStore.save(root, "u", "product:new", mapOf("productName" to "second"))
        assertEquals("second", FormDraftStore.load(root, "u", "product:new")?.fields?.get("productName"))
        // No temp file is left beside it.
        val dir = FormDraftStore.fileFor(root, "u", "product:new").parentFile!!
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".writing") })
    }

    @Test
    fun a_damaged_file_reads_as_no_draft_and_is_set_aside_not_deleted() {
        val root = root()
        val file = FormDraftStore.fileFor(root, "u", "workshop:new")
        file.parentFile!!.mkdirs()
        file.writeText("{ this is not json")

        assertNull(FormDraftStore.load(root, "u", "workshop:new"))
        assertFalse(file.exists())
        assertTrue(file.parentFile!!.listFiles()!!.any { it.name.startsWith("workshop_new.json.damaged-") })
    }

    @Test
    fun a_draft_from_a_newer_build_is_left_alone() {
        val root = root()
        val file = FormDraftStore.fileFor(root, "u", "craft:new")
        file.parentFile!!.mkdirs()
        file.writeText("""{"schema":99,"formKey":"craft:new","ownerUserId":"u","fields":{"name":"x"}}""")

        assertNull(FormDraftStore.load(root, "u", "craft:new"))
        assertTrue("the newer file must not be renamed or deleted", file.exists())
    }

    @Test
    fun a_form_key_cannot_escape_its_directory() {
        val root = root()
        val file = FormDraftStore.fileFor(root, "u", "../../etc/passwd")
        assertTrue(file.canonicalPath.startsWith(root.canonicalPath))
    }

    @Test
    fun the_owner_directory_is_a_hash_not_the_id() {
        val segment = FormDraftStore.ownerSegment("cmsik2jg8000eh8xc1lcy661a")
        assertFalse(segment.contains("cmsik2"))
        assertEquals(segment, FormDraftStore.ownerSegment("cmsik2jg8000eh8xc1lcy661a"))
        assertNotEquals(segment, FormDraftStore.ownerSegment("someone-else"))
        assertEquals("_signed_out", FormDraftStore.ownerSegment(null))
    }

    @Test
    fun nothing_typed_keeps_no_draft_and_an_absent_box_equals_an_empty_one() {
        val baseline = mapOf("name" to "", "place" to "Kutch")
        assertFalse(formDraftIsWorthKeeping(baseline, baseline))
        // An interview's questions arrive after the screen: new, EMPTY boxes are not typing.
        assertFalse(formDraftIsWorthKeeping(baseline + ("a:q1" to ""), baseline))
        assertTrue(formDraftIsWorthKeeping(baseline + ("a:q1" to "yes"), baseline))
        assertTrue(formDraftIsWorthKeeping(mapOf("name" to "R", "place" to "Kutch"), baseline))
        // Clearing a prefilled box IS a change.
        assertTrue(formDraftIsWorthKeeping(mapOf("name" to "", "place" to ""), baseline))
    }

    @Test
    fun answers_whose_boxes_have_not_appeared_yet_are_held_back_not_dropped() {
        val restored = mapOf("title" to "Interview", "a:q1" to "yes", "a:q2" to "no")
        val formKeysNow = setOf("title", "place")
        assertEquals(mapOf("a:q1" to "yes", "a:q2" to "no"), formDraftLeftovers(restored, formKeysNow))
        assertEquals(emptyMap<String, String>(), formDraftLeftovers(restored, formKeysNow + "a:q1" + "a:q2"))
    }

    @Test
    fun a_uri_field_splits_back_into_its_uris() {
        assertEquals(listOf("content://a", "content://b"), formDraftUriList("content://a\n\ncontent://b\n"))
        assertEquals(emptyList<String>(), formDraftUriList(""))
        assertEquals(emptyList<String>(), formDraftUriList(null))
    }

    @Test
    fun the_uris_any_draft_points_at_are_found_across_accounts() {
        val root = root()
        FormDraftStore.save(root, "u1", "craft:new", mapOf("${URI_FIELD_PREFIX}media" to "content://x/1\ncontent://x/2", "name" to "a"))
        FormDraftStore.save(root, "u2", "tool:new", mapOf("${URI_FIELD_PREFIX}stages" to "content://x/3"))
        assertEquals(setOf("content://x/1", "content://x/2", "content://x/3"), FormDraftStore.referencedUris(root))
    }

    @Test
    fun the_sweep_keeps_young_files_and_referenced_files_and_reclaims_the_rest() {
        val dir = folder.newFolder("captures")
        val now = 10_000_000_000L
        val grace = 1_000L
        fun file(name: String, ageMs: Long) = File(dir, name).apply {
            writeText("x")
            setLastModified(now - ageMs)
        }
        val young = file("young.jpg", 10)
        val referenced = file("kept.jpg", 50_000)
        val orphan = file("orphan.jpg", 50_000)
        val emptyCopyDir = File(dir, "uuid-dir").apply { mkdirs() }

        val removed = FormDraftStore.sweep(listOf(dir), grace, now) { it.name == "kept.jpg" }

        assertEquals(1, removed)
        assertTrue(young.exists())
        assertTrue(referenced.exists())
        assertFalse(orphan.exists())
        assertFalse("an emptied copy directory is tidied away", emptyCopyDir.exists())
        assertTrue("the swept directory itself stays", dir.exists())
    }
}
