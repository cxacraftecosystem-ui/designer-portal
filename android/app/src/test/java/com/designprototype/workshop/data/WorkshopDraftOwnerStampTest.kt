package com.designprototype.workshop.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkshopDraftOwnerStampTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun account(id: String) = UserDto(id = id, email = "$id@example.org", name = id, role = "DESIGNER")

    @Test
    fun `an offline edit to a server workshop is stamped with the account that made it`() = runBlocking {
        val context = InMemoryContext(folder.root)
        TokenStore(context).setUser(account("designer-a"))

        WorkshopDraftStore.update(context, "server-workshop-1") { it.copy(title = "Edited offline") }

        val draft = WorkshopDraftStore.load(context, "server-workshop-1")!!
        assertEquals("designer-a", draft.ownerUserId)

        TokenStore(context).setUser(account("designer-b"))
        assertTrue(
            "designer-b would send designer-a's offline edit under its own session",
            dwDraftIsForAnotherAccount(draft.ownerUserId, TokenStore(context).getUser()?.id),
        )
    }

    @Test
    fun `a later edit keeps the original owner`() = runBlocking {
        val context = InMemoryContext(folder.root)
        TokenStore(context).setUser(account("designer-a"))
        WorkshopDraftStore.update(context, "server-workshop-2") { it.copy(title = "First") }

        WorkshopDraftStore.update(context, "server-workshop-2") { it.copy(title = "Second") }

        assertEquals("designer-a", WorkshopDraftStore.load(context, "server-workshop-2")!!.ownerUserId)
    }
}
