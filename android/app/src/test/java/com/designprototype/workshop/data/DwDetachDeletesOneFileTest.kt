package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DwDetachDeletesOneFileTest {

    private fun media(id: String, path: String) = DraftMedia(id = id, relativePath = path)

    @Test
    fun `the file of the descriptor that was removed is the one deleted`() {
        val target = WorkshopDraftStore.detachedFileToDelete(
            removedPath = "media/aaa.jpg",
            survivors = listOf(media("m2", "media/bbb.jpg"), media("m3", "media/ccc.jpg")),
        )
        assertEquals("media/aaa.jpg", target)
    }

    @Test
    fun `a file no descriptor names is not deleted by a detach`() {
        assertNull(
            WorkshopDraftStore.detachedFileToDelete(removedPath = null, survivors = emptyList()),
        )
    }

    @Test
    fun `removing a descriptor that was already gone deletes nothing`() {
        assertNull(
            WorkshopDraftStore.detachedFileToDelete(
                removedPath = null,
                survivors = listOf(media("m2", "media/bbb.jpg")),
            ),
        )
    }

    @Test
    fun `a blank relative path is never turned into a deletion`() {
        assertNull(WorkshopDraftStore.detachedFileToDelete("", emptyList()))
        assertNull(WorkshopDraftStore.detachedFileToDelete("   ".trim(), emptyList()))
    }

    @Test
    fun `bytes a surviving descriptor still names are kept`() {
        assertNull(
            WorkshopDraftStore.detachedFileToDelete(
                removedPath = "media/aaa.jpg",
                survivors = listOf(media("m9", "media/aaa.jpg")),
            ),
        )
    }
}
