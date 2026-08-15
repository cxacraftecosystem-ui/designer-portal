package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A DETACH USED TO SWEEP THE WHOLE MEDIA DIRECTORY, AND THE SWEEP DELETED A PHOTOGRAPH ANOTHER
 * IMPORT WAS STILL COPYING.
 *
 * ── THE DEFECT ───────────────────────────────────────────────────────────────────────────────────
 *
 * `removeMedia` removed the descriptor under the store's lock and then, OUTSIDE it, listed `media/`
 * and deleted every file the post-removal SNAPSHOT did not name. `importMedia` copies bytes and
 * `fd.sync()`s them with the lock deliberately NOT held (its own step 1 of 3), taking the lock only to
 * register the descriptor afterwards. So two windows existed: a file mid-copy, and a file whose
 * descriptor was registered after this snapshot was taken.
 *
 * Both are ordinary inside one stage screen. `attach` multi-selects five photographs and imports them
 * in ONE coroutine; each copy suspends on `Dispatchers.IO` for the seconds a multi-megabyte file
 * takes, which frees the same scope to run `detach` when the designer taps the X on a photograph
 * attached earlier. The unlink succeeds while the writer's fd is open, so the copy "succeeds" — and
 * the draft is left holding a caption and a stage assignment for bytes with no directory entry.
 * `uploadPending` then told the designer the file "is no longer in this workshop's media folder on
 * this device … Nothing has been deleted by the app", which was false and pointed the investigation
 * away from us.
 *
 * ── WHAT IS PINNED HERE ──────────────────────────────────────────────────────────────────────────
 *
 * The decision, which is the whole fix: a detach deletes the bytes of the descriptor it removed and
 * NOTHING else. The filesystem half needs a Context and belongs to the instrumented suite; the rule
 * is checkable here, and it is the rule that was wrong.
 */
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

    /**
     * THE ASSERTION THE DEFECT FAILED, stated as the rule rather than as a directory listing: a file
     * that no descriptor names is NOT this function's business. Under the old sweep every such file
     * was deleted — including the one an import was mid-copy, whose descriptor did not exist yet.
     * Unreferenced bytes are wasted space, which is recoverable; the photograph was not.
     */
    @Test
    fun `a file no descriptor names is not deleted by a detach`() {
        // The only thing this function is ever told about is the descriptor that was removed. There is
        // no input for "everything else in the directory", and there must not be one.
        assertNull(
            WorkshopDraftStore.detachedFileToDelete(removedPath = null, survivors = emptyList()),
        )
    }

    /** Detaching an id that is not there (a double tap, a stale row) must delete nothing at all. */
    @Test
    fun `removing a descriptor that was already gone deletes nothing`() {
        assertNull(
            WorkshopDraftStore.detachedFileToDelete(
                removedPath = null,
                survivors = listOf(media("m2", "media/bbb.jpg")),
            ),
        )
    }

    /**
     * A BLANK PATH RESOLVES TO THE WORKSHOP DIRECTORY ITSELF. `statusOf` guards the same shape for the
     * same reason; deleting on it is not something a detach may ever attempt.
     */
    @Test
    fun `a blank relative path is never turned into a deletion`() {
        assertNull(WorkshopDraftStore.detachedFileToDelete("", emptyList()))
        assertNull(WorkshopDraftStore.detachedFileToDelete("   ".trim(), emptyList()))
    }

    /**
     * Two descriptors can in principle name one file, and the same id can be detached twice. Either
     * way the bytes are still owed to somebody who can still see a thumbnail for them.
     */
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
