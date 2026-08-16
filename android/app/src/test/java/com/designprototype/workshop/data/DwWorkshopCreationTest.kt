package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A designer may not start a design workshop — and finds that out in the courtyard, not at sync.
 *
 * ── THE FAILURE THIS FILE EXISTS TO CATCH ─────────────────────────────────────────────────────
 *
 * A designer fills twenty-two stages into a local draft over a fortnight and learns, at the first
 * bar of signal, that the workshop can never be created. That is not a permissions message; it is
 * lost fieldwork. `POST /design-workshops` is and remains the load-bearing gate, but it can only
 * speak when there is a connection, and the whole point of this client is the days when there is
 * not — so the rule is also evaluated here, from the cached role, before a byte is written.
 *
 * Every assertion below is a way that could quietly stop being true.
 */
class DwWorkshopCreationTest {

    // ── The rule ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The set is ADMIN and MASTER_ADMIN, and a DESIGNER is outside it.
     *
     * A SET rather than a rank floor, exactly as the server writes it. Written as
     * `rank >= RANK_ADMIN` it would give the same answer today and would silently admit any role
     * later inserted above 50 — and the one role this rule is actually about, DESIGNER at 35, sits
     * below a PROFESSOR at 40 who is ALSO refused. There is no threshold that produces this answer
     * for both of them, which is why approximating it with the ladder is not available.
     */
    @Test
    fun `only admins and the master admin may create a workshop`() {
        assertTrue(canCreateDesignWorkshops("ADMIN"))
        assertTrue(canCreateDesignWorkshops("MASTER_ADMIN"))
        assertFalse("the whole point of the rule", canCreateDesignWorkshops("DESIGNER"))
        assertFalse("outranks a designer and is still refused", canCreateDesignWorkshops("PROFESSOR"))
        assertFalse(canCreateDesignWorkshops("RESEARCHER"))
        assertFalse(canCreateDesignWorkshops("FIELD_CONTRIBUTOR"))
        assertFalse(canCreateDesignWorkshops("CROWDSOURCE_VOLUNTEER"))
        assertFalse(canCreateDesignWorkshops(null))
        assertFalse("an unknown role must never be a creator", canCreateDesignWorkshops("SUPERUSER"))
    }

    /**
     * The refusal names the next move, and it names what still works.
     *
     * Somebody reading it is standing in a courtyard with participants in front of them. "Forbidden"
     * tells them to stop working; the truth is that everything they came to do works as soon as an
     * admin has opened the workshop. If a later edit trims this to a status message, this fails.
     */
    @Test
    fun `the refusal says who can, and that everything else still works`() {
        assertTrue(DW_WORKSHOP_CREATE_REFUSAL.contains("Ask an admin"))
        assertTrue(DW_WORKSHOP_CREATE_REFUSAL.contains("22 stages"))
        assertTrue(DW_WORKSHOP_CREATE_REFUSAL.contains("already have access to is open to you now"))
    }

    // ── The tri-state ────────────────────────────────────────────────────────────────────────────

    /**
     * Not told yet is ALLOWED; signed out is REFUSED; the role decides the rest.
     *
     * "Not told yet" allows deliberately. Refusing it would block an ADMIN in the moment before the
     * cached user has been read — a refusal for a rule that does not apply to them, which is the
     * kind of false negative that teaches people to ignore refusals altogether. Nothing escapes
     * through that window that matters: the server is the gate.
     *
     * "Signed out" is the state the tri-state is worth having for. Signing out does not clear the
     * draft store, so without this a signed-out handset could still mint a workshop into `filesDir`
     * that no account owns and no sync pass could ever send.
     */
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

    // ── The bug that was shipped once and caught ─────────────────────────────────────────────────

    /**
     * A draft ALREADY ON THE SERVER is never declined, whoever is signed in.
     *
     * THIS IS THE MISTAKE, PINNED. Written as "refuse whenever this session may not create", the
     * rule also refuses the draft whose create had already landed before the rule shipped — a real
     * workshop on the server that this device merely never saw the answer for. That draft needs no
     * create at all; the pass only has to write the id back. Refusing it strands an existing
     * workshop behind a permanent failure and tells the designer to go and ask an admin for a
     * workshop they already have.
     *
     * Both facts are in the signature so neither can be dropped by accident.
     */
    @Test
    fun `a create that already landed is never declined, even for a designer`() {
        assertFalse(
            "there is nothing to create, so there is nothing to refuse",
            createMustBeDeclined(alreadyOnServer = true, sessionMayCreate = false),
        )
        assertFalse(createMustBeDeclined(alreadyOnServer = true, sessionMayCreate = true))
    }

    /** A create that is genuinely owed, by a session that may not make one, is declined. */
    @Test
    fun `a designer's uncreated draft is declined rather than posted every forty-five seconds`() {
        assertTrue(createMustBeDeclined(alreadyOnServer = false, sessionMayCreate = false))
        assertFalse(createMustBeDeclined(alreadyOnServer = false, sessionMayCreate = true))
    }

    /**
     * The declined-create failure names the control that fixes it, and says nothing was lost first.
     *
     * A designer meets this sentence looking at a fortnight of fieldwork with a red mark on it. The
     * order matters: "nothing has been deleted" before anything else, then the route out by name.
     */
    @Test
    fun `the declined-create failure names the way out and says nothing was lost`() {
        assertTrue(DW_WORKSHOP_CREATE_DECLINED_BY_APP.contains("Move into a workshop"))
        assertTrue(DW_WORKSHOP_CREATE_DECLINED_BY_APP.contains("has been deleted or changed"))
        assertTrue(
            "it must still carry the shared refusal, not a private paraphrase of it",
            DW_WORKSHOP_CREATE_DECLINED_BY_APP.startsWith(DW_WORKSHOP_CREATE_REFUSAL),
        )
    }

    // ── Which drafts need a workshop ─────────────────────────────────────────────────────────────

    /**
     * A workshop created ONLINE is keyed by the server's id with `remoteId` still null.
     *
     * So `remoteId == null` is NOT the test, and using it would be a live defect in two places at
     * once: the list would offer to "move into a workshop" a workshop that is already one, and the
     * sync pass would decline a create it does not owe. `WorkshopSync.remoteIdOf` computes exactly
     * this, and [remoteWorkshopIdOf] is its public twin so the two cannot drift.
     */
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

    /** A blank `remoteId` is an absent one — a stored empty string must not read as adopted. */
    @Test
    fun `a blank remote id is treated as no remote id`() {
        assertTrue(localDraftNeedsAWorkshop(WorkshopDraft(workshopId = "local-abc", remoteId = "")))
    }

    // ── Adoption ─────────────────────────────────────────────────────────────────────────────────

    /** A courtyard draft: two stages of real work, with deletions and completion marks on it. */
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
                // TRUE because a local-only workshop has nothing on the server to be missing — the
                // documented rule, and exactly the premise adoption invalidates.
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

    /**
     * THE ADOPTION CLEARS `stageSeen`, ON EVERY STAGE — this is the wholesale-overwrite guard.
     *
     * `stageSeen` is true on a local-only draft by its own rule: there is no server copy to be
     * missing. Adoption is the moment that expires, and it expires harder than on a create — the
     * target workshop was made by somebody else and `POST /design-workshops` has already seeded
     * `workshopSetup` and `workshopPlan` singletons into it. Left standing, the first save claims
     * authority (merge omitted), `save_stage` replaces the singleton's `data` wholesale, and the
     * seeded designer block is destroyed in place under a 200.
     */
    @Test
    fun `adoption withdraws every claim to have read the server`() {
        val moved = adoptedIntoWorkshop(courtyardDraft(), "cmsv_target", "2026-08-16T12:00:00Z")
        assertTrue(
            "a stage claiming to have been read would overwrite the seeded singletons",
            moved.stages.values.none { it.stageSeen },
        )
    }

    /**
     * AND IT CLEARS THE DELETIONS, WHICH IS THE DANGEROUS HALF.
     *
     * `emptiedEntities` and `deletedRowKeys` are the only mechanism by which a deletion reaches the
     * server, and they arm the sweep on the sync PUT. On a never-synced draft every one of those
     * deletions was of a row that has only ever existed on THIS DEVICE. Carried into an adoption
     * they arm a sweep against a workshop this phone has never read, and `save_stage` deletes rows
     * belonging to whoever has been working in the target. That is the difference between adopting a
     * workshop and emptying one.
     */
    @Test
    fun `adoption disarms the deletions so it cannot sweep the workshop it moves into`() {
        val moved = adoptedIntoWorkshop(courtyardDraft(), "cmsv_target", "2026-08-16T12:00:00Z")
        val stage = moved.stages.getValue("workshopSetup")
        assertTrue("device-only deletions must not travel into somebody else's workshop", stage.emptiedEntities.isEmpty())
        assertTrue(stage.deletedRowKeys.isEmpty())
    }

    /**
     * AND IT CLEARS THE STAGE SIGNATURES, or the fortnight is reported as already sent.
     *
     * A signature is "the body the server last accepted for this stage" — and the server in question
     * was a different workshop, or none. Kept, `isFullySynced` reads the adopted draft as backed up,
     * the pass sends nothing, and the designer is told everything is on the server while none of it
     * is. This is the failure mode that loses the fieldwork silently, which is why it is cleared
     * here rather than left to the pass to notice.
     */
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

    /**
     * AND IT KEEPS EVERY LAST THING THE DESIGNER RECORDED. Adoption is not a reset.
     *
     * This is the assertion that makes the rule shippable: the answers, the custom answers, the
     * notes, the completion marks, the photographs, the title and the creation date all survive. If
     * a later "tidy-up" starts clearing one of them, a fortnight of somebody's fieldwork is quietly
     * emptied by the control that exists to save it.
     */
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

    /**
     * `customSeen` is deliberately LEFT ALONE, and that is worth pinning next to `stageSeen`.
     *
     * The two look like a pair and are not. `stageSeen` is cleared because it would arm
     * `replaceCollections` and a non-merge write against a workshop somebody else seeded.
     * `customSeen` guards the SEPARATE `_custom` row, which `POST /design-workshops` does not seed
     * at all — there is nothing there for a claim to overwrite. Clearing it would cost a designer
     * their custom-answer clearances until each stage is next opened with a connection, for no
     * corresponding protection.
     *
     * If a future change makes the create route seed custom rows, this test is the one that has to
     * be rewritten — and its docstring says why the old assertion went.
     */
    @Test
    fun `adoption leaves the custom-row claim alone because nothing seeds that row`() {
        val moved = adoptedIntoWorkshop(courtyardDraft(), "cmsv_target", "2026-08-16T12:00:00Z")
        assertTrue(moved.stages.getValue("workshopSetup").customSeen)
    }
}
