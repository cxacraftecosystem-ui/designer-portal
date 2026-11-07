package com.designprototype.workshop.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DwHeldDraftsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun account(id: String) = UserDto(id = id, email = "$id@example.org", name = id, role = "DESIGNER")

    private fun heldDraft(context: InMemoryContext, workshopId: String) = runBlocking {
        TokenStore(context).setUser(account("designer-a"))
        WorkshopDraftStore.update(context, workshopId) { it.copy(title = "Captured by A") }
        TokenStore(context).setUser(account("designer-b"))
    }

    @Test
    fun `a held draft asks until the signed-in account keeps it`() {
        assertTrue(dwHeldDraftAsks(otherAccount = true, kept = false))
        assertFalse(dwHeldDraftAsks(otherAccount = true, kept = true))
        assertFalse(dwHeldDraftAsks(otherAccount = false, kept = false))
        assertFalse(dwHeldDraftAsks(otherAccount = false, kept = true))
    }

    @Test
    fun `keep is remembered for the account that chose it, not for every account`() {
        val context = InMemoryContext(folder.root)
        assertFalse(DwHeldDrafts.kept(context, "designer-b", "local-1"))

        DwHeldDrafts.keep(context, "designer-b", "local-1")

        assertTrue(DwHeldDrafts.kept(context, "designer-b", "local-1"))
        assertFalse(DwHeldDrafts.kept(context, "designer-c", "local-1"))
        assertFalse(DwHeldDrafts.kept(context, "designer-b", "local-2"))
    }

    @Test
    fun `keep needs a signed-in account`() {
        val context = InMemoryContext(folder.root)
        DwHeldDrafts.keep(context, null, "local-1")
        assertFalse(DwHeldDrafts.kept(context, null, "local-1"))
        assertNull(dwHeldDraftKey("", "local-1"))
    }

    @Test
    fun `discard removes a draft another account saved and forgets the keep`() = runBlocking {
        val context = InMemoryContext(folder.root)
        heldDraft(context, "local-held")
        DwHeldDrafts.keep(context, "designer-b", "local-held")

        assertTrue(DwHeldDrafts.discard(context, "designer-b", "local-held"))

        assertNull(WorkshopDraftStore.load(context, "local-held"))
        assertTrue(WorkshopDraftStore.list(context).none { it.workshopId == "local-held" })
        assertFalse(DwHeldDrafts.kept(context, "designer-b", "local-held"))
    }

    @Test
    fun `discard never removes the signed-in account's own draft`() = runBlocking {
        val context = InMemoryContext(folder.root)
        TokenStore(context).setUser(account("designer-b"))
        WorkshopDraftStore.update(context, "local-mine") { it.copy(title = "Mine") }

        assertFalse(DwHeldDrafts.discard(context, "designer-b", "local-mine"))

        assertNotNull(WorkshopDraftStore.load(context, "local-mine"))
    }

    @Test
    fun `discarding a draft that is already gone says so`() = runBlocking {
        val context = InMemoryContext(folder.root)
        assertFalse(DwHeldDrafts.discard(context, "designer-b", "local-missing"))
    }

    @Test
    fun `a discarded draft goes to the phone's trash, not straight to nothing`() = runBlocking {
        val context = InMemoryContext(folder.root)
        heldDraft(context, "local-trash")

        DwHeldDrafts.discard(context, "designer-b", "local-trash")

        val trashed = WorkshopDraftStore.trashed(context)
        assertEquals(1, trashed.count { it.name.startsWith("local-trash") })
        assertTrue(trashed.all { it.isDirectory && File(it.path).exists() })
    }

    @Test
    fun `the confirm is short and names the draft`() {
        assertEquals("Discard this draft?", dwHeldDraftDiscardTitle())
        val body = dwHeldDraftDiscardBody("Bagru dabu")
        assertTrue(body.contains("“Bagru dabu”"))
        assertTrue("one or two short sentences:\n$body", body.length <= 110)
    }
}
