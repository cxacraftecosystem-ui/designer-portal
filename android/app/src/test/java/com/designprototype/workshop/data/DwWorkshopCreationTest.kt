package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwWorkshopCreationTest {

    @Test
    fun `only admins and the master admin may create a workshop`() {
        assertTrue(canCreateDesignWorkshops("ADMIN"))
        assertTrue(canCreateDesignWorkshops("MASTER_ADMIN"))
        assertFalse("the whole point of the rule", canCreateDesignWorkshops("DESIGNER"))
        assertFalse("outranks a designer and is still refused", canCreateDesignWorkshops("INSPECTOR"))
        assertFalse("outranks a designer and is still refused", canCreateDesignWorkshops("PROFESSOR"))
        assertFalse("outranks a professor and is still refused", canCreateDesignWorkshops("ASSISTANT_DIRECTOR"))
        assertFalse("outranks a professor and is still refused", canCreateDesignWorkshops("REGIONAL_DIRECTOR"))
        assertFalse(
            "the token says admin; `is_admin` is a set and does not contain it",
            canCreateDesignWorkshops("MINISTRY_ADMIN")
        )
        assertFalse(canCreateDesignWorkshops("RESEARCHER"))
        assertFalse(canCreateDesignWorkshops("FIELD_CONTRIBUTOR"))
        assertFalse(canCreateDesignWorkshops("CROWDSOURCE_VOLUNTEER"))
        assertFalse(canCreateDesignWorkshops(null))
        assertFalse("an unknown role must never be a creator", canCreateDesignWorkshops("SUPERUSER"))
    }

    @Test
    fun `the refusal says who can, and that everything else still works`() {
        assertTrue(DW_WORKSHOP_CREATE_REFUSAL.contains("Ask an admin"))
        assertTrue(DW_WORKSHOP_CREATE_REFUSAL.contains("22 stages"))
        assertTrue(DW_WORKSHOP_CREATE_REFUSAL.contains("already have access to is open to you now"))
    }

    @Test
    fun `the tri-state allows the unknown session and refuses the signed-out one`() {
        assertTrue(
            "an admin must not be refused during the moment before the cached user is read",
            mayMintLocalWorkshop(known = false, role = null),
        )
        assertFalse(
            "signed out must not be able to mint a workshop nothing can ever send",
            mayMintLocalWorkshop(known = true, role = null),
        )
        assertFalse(mayMintLocalWorkshop(known = true, role = "DESIGNER"))
        assertTrue(mayMintLocalWorkshop(known = true, role = "ADMIN"))
    }

    @Test
    fun `a create that already landed is never declined, even for a designer`() {
        assertFalse(
            "there is nothing to create, so there is nothing to refuse",
            createMustBeDeclined(alreadyOnServer = true, sessionMayCreate = false),
        )
        assertFalse(createMustBeDeclined(alreadyOnServer = true, sessionMayCreate = true))
    }

    @Test
    fun `a designer's uncreated draft is declined rather than posted every forty-five seconds`() {
        assertTrue(createMustBeDeclined(alreadyOnServer = false, sessionMayCreate = false))
        assertFalse(createMustBeDeclined(alreadyOnServer = false, sessionMayCreate = true))
    }

    @Test
    fun `the declined-create failure names the way out and says nothing was lost`() {
        assertTrue(DW_WORKSHOP_CREATE_DECLINED_BY_APP.contains("Move into a workshop"))
        assertTrue(DW_WORKSHOP_CREATE_DECLINED_BY_APP.contains("has been deleted or changed"))
        assertTrue(
            "it must still carry the shared refusal, not a private paraphrase of it",
            DW_WORKSHOP_CREATE_DECLINED_BY_APP.startsWith(DW_WORKSHOP_CREATE_REFUSAL),
        )
    }

    @Test
    fun `an admin is answered CREATE whether or not the phone can reach the server`() {
        assertEquals(
            DwDraftStart.CREATE,
            dwClassifyDraftStart(mayCreate = true, mayRunWorkshops = true, reachable = true),
        )
        assertEquals(
            DwDraftStart.CREATE,
            dwClassifyDraftStart(mayCreate = true, mayRunWorkshops = true, reachable = false),
        )
    }

    @Test
    fun `a designer with a connection is still refused`() {
        assertEquals(
            DwDraftStart.REFUSED,
            dwClassifyDraftStart(mayCreate = false, mayRunWorkshops = true, reachable = true),
        )
    }

    @Test
    fun `a designer with no connection is answered LINK_LATER`() {
        assertEquals(
            DwDraftStart.LINK_LATER,
            dwClassifyDraftStart(mayCreate = false, mayRunWorkshops = true, reachable = false),
        )
    }

    @Test
    fun `an account outside the workshop set is refused, reachable or not`() {
        assertEquals(
            DwDraftStart.REFUSED,
            dwClassifyDraftStart(mayCreate = false, mayRunWorkshops = false, reachable = false),
        )
        assertEquals(
            DwDraftStart.REFUSED,
            dwClassifyDraftStart(mayCreate = false, mayRunWorkshops = false, reachable = true),
        )
    }

    @Test
    fun `a draft minted with no signal still takes the adopt path and never the create path`() {
        assertEquals(
            DwDraftStart.LINK_LATER,
            dwClassifyDraftStart(mayCreate = false, mayRunWorkshops = true, reachable = false),
        )
        assertTrue(createMustBeDeclined(alreadyOnServer = false, sessionMayCreate = false))
    }

    @Test
    fun `the link-later words name the state and the next move`() {
        assertTrue(DW_LOCAL_START_NOTE.contains("Not a workshop yet"))
        assertTrue(DW_LOCAL_DRAFT_UNLINKED.contains("Not a workshop yet"))
        assertTrue(DW_LOCAL_DRAFT_LINK_PROMPT.contains("workshops"))
        assertTrue(DW_LOCAL_START_ACTION.isNotBlank())
    }


    @Test
    fun `a draft keyed by a server id needs no workshop even with a null remoteId`() {
        val onlineCreated = WorkshopDraft(workshopId = "cmsv0001", remoteId = null)
        assertFalse(localDraftNeedsAWorkshop(onlineCreated))
        assertEquals("cmsv0001", remoteWorkshopIdOf(onlineCreated))

        val courtyard = WorkshopDraft(workshopId = "local-abc", remoteId = null)
        assertTrue(localDraftNeedsAWorkshop(courtyard))
        assertNull(remoteWorkshopIdOf(courtyard))

        val adopted = WorkshopDraft(workshopId = "local-abc", remoteId = "cmsv0002")
        assertFalse(localDraftNeedsAWorkshop(adopted))
        assertEquals("cmsv0002", remoteWorkshopIdOf(adopted))
    }

    @Test
    fun `a blank remote id is treated as no remote id`() {
        assertTrue(localDraftNeedsAWorkshop(WorkshopDraft(workshopId = "local-abc", remoteId = "")))
    }

    private fun courtyardDraft() = WorkshopDraft(
        workshopId = "local-abc",
        title = "Bagru block printing",
        templateId = "DCH_STANDARD",
        createdAt = "2026-08-01T09:00:00Z",
        updatedAt = "2026-08-14T17:00:00Z",
        remoteId = null,
        ownerUserId = "usr_designer",
        media = listOf(DraftMedia(id = "m1", relativePath = "media/loom.jpg", originalFilename = "loom.jpg")),
        stages = mapOf(
            "workshopSetup" to StageDraft(
                stageId = "workshopSetup",
                order = 1,
                values = mapOf("clusterName" to JsonPrimitive("Bagru")),
                custom = mapOf("loomsWorking" to JsonPrimitive(12)),
                notes = "Sanction order still awaited",
                completedAt = "2026-08-02T10:00:00Z",
                stageSeen = true,
                customSeen = true,
                emptiedEntities = listOf("costSheet"),
                deletedRowKeys = listOf("participants#p3"),
            ),
            "participants" to StageDraft(
                stageId = "participants",
                order = 3,
                values = mapOf("count" to JsonPrimitive(18)),
                stageSeen = true,
            ),
        ),
        sync = DraftSyncState(
            lastSuccessAt = "2026-08-10T00:00:00Z",
            lastError = "The server refused to create this workshop.",
            createFailure = "The server refused to create this workshop.",
            createFailedAt = "2026-08-10T00:00:00Z",
            createSkewRun = "run-1",
            stages = mapOf("workshopSetup" to StageSyncRecord(signature = "abc123", syncedAt = "2026-08-10T00:00:00Z")),
        ),
    )

    @Test
    fun `adoption withdraws every claim to have read the server`() {
        val moved = adoptedIntoWorkshop(courtyardDraft(), "cmsv_target", "2026-08-16T12:00:00Z")
        assertTrue(
            "a stage claiming to have been read would overwrite the seeded singletons",
            moved.stages.values.none { it.stageSeen },
        )
    }

    @Test
    fun `adoption disarms the deletions so it cannot sweep the workshop it moves into`() {
        val moved = adoptedIntoWorkshop(courtyardDraft(), "cmsv_target", "2026-08-16T12:00:00Z")
        val stage = moved.stages.getValue("workshopSetup")
        assertTrue("device-only deletions must not travel into somebody else's workshop", stage.emptiedEntities.isEmpty())
        assertTrue(stage.deletedRowKeys.isEmpty())
    }

    @Test
    fun `adoption forgets what an unrelated workshop had accepted`() {
        val moved = adoptedIntoWorkshop(courtyardDraft(), "cmsv_target", "2026-08-16T12:00:00Z")
        assertTrue(moved.sync.stages.isEmpty())
        assertNull("never synced INTO this workshop, whatever happened before", moved.sync.lastSuccessAt)
        assertNull("the refusal that sent them here has been acted on", moved.sync.createFailure)
        assertNull(moved.sync.createFailedAt)
        assertNull(moved.sync.createSkewRun)
        assertNull(moved.sync.lastError)
    }

    @Test
    fun `adoption keeps the fieldwork, which is the entire point of adopting`() {
        val before = courtyardDraft()
        val moved = adoptedIntoWorkshop(before, "cmsv_target", "2026-08-16T12:00:00Z")

        assertEquals("cmsv_target", moved.remoteId)
        assertEquals(before.title, moved.title)
        assertEquals(before.templateId, moved.templateId)
        assertEquals("the draft was not re-created, it was re-pointed", before.createdAt, moved.createdAt)
        assertEquals(before.ownerUserId, moved.ownerUserId)
        assertEquals(before.media, moved.media)
        assertEquals("2026-08-16T12:00:00Z", moved.updatedAt)

        val stage = moved.stages.getValue("workshopSetup")
        assertEquals(JsonPrimitive("Bagru"), stage.values["clusterName"])
        assertEquals(JsonPrimitive(12), stage.custom["loomsWorking"])
        assertEquals("Sanction order still awaited", stage.notes)
        assertNotNull("a stage the designer marked done stays done", stage.completedAt)
        assertEquals(2, moved.stages.size)
    }

    @Test
    fun `adoption leaves the custom-row claim alone because nothing seeds that row`() {
        val moved = adoptedIntoWorkshop(courtyardDraft(), "cmsv_target", "2026-08-16T12:00:00Z")
        assertTrue(moved.stages.getValue("workshopSetup").customSeen)
    }
}
