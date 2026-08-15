package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUTHORITY IS EARNED BY HAVING READ, NEVER BY HAVING WRITTEN.
 *
 * `recordStageSent` set `serverBaseline = true` after ANY successful save — including a save whose
 * entries carried `merge = true` because the stage had been seeded BLANK by a failed download — with
 * the comment "the draft and the server agree". After a merge save that is false: the server holds
 * `previous ∪ sent`, and this device has never seen `previous`.
 *
 * ── THE WALK, EXECUTED AGAINST THE SERVER'S OWN PLANNER BEFORE THESE TESTS WERE WRITTEN ──────────
 *
 * The office types a core field on the web. The designer opens that stage in a courtyard;
 * `GET .../stages/{key}` fails; a blank `StageState()` is seeded; they type one field. The save lands
 * on the drive home carrying `merge = true`, which is correct and which the server honours — and the
 * old code then stamped the baseline. From that moment `isAuthoritative()` answered true, so the next
 * payload sent the CORE singleton with `merge` omitted: a full replacement containing only the fields
 * this phone happened to hold. `save_stage` replaces a singleton's `data` wholesale and writes no
 * `RecordRevision`, so the office's field was gone in place with the save reporting success.
 *
 * ONE CORRECTION TO THE WALK AS IT WAS REPORTED, VERIFIED HERE: it does not need the "next keystroke".
 * A merge body and a replace body have DIFFERENT SIGNATURES, so the moment the draft is promoted the
 * recorded signature stops matching what `buildStageBody` now produces — and the very next background
 * pass rebuilds the stage as a replacement and sends it, with nobody touching the phone. That is
 * pinned by `the promotion alone re-sends the stage as a replacement` below.
 */
class StageAuthorityEarnedByReadingTest {

    private val spec = StageDto(
        number = 4,
        key = "CLUSTER_PROFILE",
        title = "Cluster profile",
        entities = listOf(
            EntityDto(
                key = "clusterProfile", cardinality = "SINGLETON", title = "Cluster profile",
                fields = listOf(
                    FieldDto(key = "artisanHouseholds", label = "Artisan households", type = "INT"),
                    FieldDto(key = "loomsWorking", label = "Looms in working order", type = "INT"),
                    FieldDto(key = "clusterNotes", label = "Notes", type = "LONG_TEXT"),
                )
            ),
            EntityDto(
                key = "tool", cardinality = "COLLECTION", title = "Tool",
                fields = listOf(FieldDto(key = "name", label = "Name", type = "TEXT"))
            ),
        )
    )

    /** The draft as it is after the designer types one field into a stage that would not download. */
    private fun typedOneFieldOffline() = StageDraft(
        stageId = spec.key,
        values = mapOf("artisanHouseholds" to JsonPrimitive(412)),
        stageSeen = false,
    )

    private fun build(stored: StageDraft?, record: StageSyncRecord? = null) =
        buildStageBody(spec, stored, emptyMap(), isAuthoritative(stored, record))

    // ── The merge save ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `the first save from a stage that failed to download is a merge`() {
        val singleton = build(typedOneFieldOffline()).body.entries.single()
        assertTrue("the office's fields must be preserved by the server", singleton.merge)
        assertEquals(JsonPrimitive(412), singleton.data["artisanHouseholds"])
    }

    @Test
    fun `and the save after it is still a merge, because nothing has been read in between`() {
        // THE ASSERTION THE DEFECT FAILED. The old code promoted the draft on the acknowledgement, so
        // this second payload went up as a full replacement holding one field.
        val afterOneSave = typedOneFieldOffline()
        val record = StageSyncRecord(signature = "whatever-the-first-save-hashed-to")

        val second = build(afterOneSave, record).body
        assertTrue("still a merge", second.entries.single().merge)
        assertFalse("and still no sweep", second.replaceCollections)
    }

    @Test
    fun `the promotion alone re-sends the stage as a replacement, with nobody typing`() {
        /*
          Why the defect needed no further keystroke, stated as arithmetic rather than as a story.
          `statusOf` and `pushStages` both rebuild the payload from the draft and compare its signature
          with the recorded one. Promote the draft and the payload CHANGES — `merge` disappears and
          `replaceCollections` flips — so the signatures differ, the stage is judged pending, and the
          next pass sends the replacement.
        */
        val draft = typedOneFieldOffline()
        val asMerged = build(draft).body
        val asPromoted = buildStageBody(spec, draft.copy(stageSeen = true), emptyMap(), true).body

        assertTrue(asMerged.entries.single().merge)
        assertFalse(asPromoted.entries.single().merge)
        assertFalse(asMerged.replaceCollections)
        assertTrue(asPromoted.replaceCollections)
        assertTrue(
            "two different payloads, so two different signatures, so an unprompted re-send",
            asMerged != asPromoted
        )
    }

    @Test
    fun `a stage that WAS read sends a replacement, which is the behaviour that must not regress`() {
        val read = typedOneFieldOffline().copy(stageSeen = true)
        val body = build(read).body
        assertFalse("a read stage may say 'these are exactly the keys'", body.entries.single().merge)
        assertTrue(body.replaceCollections)
    }

    // ── The fold that gives the authority back ───────────────────────────────────────────────────

    @Test
    fun `folding the server's copy in is what earns the reading`() {
        val local = typedOneFieldOffline()
        val bucket = StageBucketDto(
            singleton = buildJsonObject {
                put("loomsWorking", 12)
                put("clusterNotes", "Seven looms are idle for want of a reed.")
            },
        )
        val fold = dwFoldServerStage(spec, local, bucket, spec.key)

        assertTrue("the read is the evidence", fold.draft.stageSeen)
        assertTrue("and it covers the custom row it returned", fold.draft.customSeen)
        // What the designer typed is untouched…
        assertEquals(JsonPrimitive(412), fold.draft.values["artisanHouseholds"])
        // …and what the office typed is now here, so a later replacement cannot destroy it unseen.
        assertEquals(JsonPrimitive(12), fold.draft.values["loomsWorking"])
        assertEquals(listOf("loomsWorking", "clusterNotes"), fold.added)

        val body = buildStageBody(spec, fold.draft, emptyMap(), isAuthoritative(fold.draft, null)).body
        assertFalse("now, and only now, a replacement is honest", body.entries.single().merge)
        assertEquals(JsonPrimitive(12), body.entries.single().data["loomsWorking"])
    }

    @Test
    fun `the fold never overwrites what the designer typed, including a value they cleared to blank`() {
        // A key held as an empty string is an OPINION — the designer cleared it — and re-filling it
        // from the server would undo an edit rather than reveal one.
        val local = StageDraft(
            stageId = spec.key,
            values = mapOf(
                "artisanHouseholds" to JsonPrimitive(412),
                "clusterNotes" to JsonPrimitive(""),
            ),
        )
        val bucket = StageBucketDto(
            singleton = buildJsonObject {
                put("artisanHouseholds", 999)
                put("clusterNotes", "the office's paragraph")
            },
        )
        val fold = dwFoldServerStage(spec, local, bucket, spec.key)
        assertEquals(JsonPrimitive(412), fold.draft.values["artisanHouseholds"])
        assertEquals(JsonPrimitive(""), fold.draft.values["clusterNotes"])
        assertTrue("nothing appeared, so nothing is announced", fold.isNothingNew)
        assertEquals(null, fold.notice)
    }

    @Test
    fun `a row this phone already holds is recognised and not added twice`() {
        // Without the client-key match, one fold would double every row of every costing table this
        // handset had ever sent — the duplication `_clientKey` exists to prevent, arriving by a new door.
        val local = StageDraft(
            stageId = spec.key,
            rows = listOf(
                DraftRow(id = dwRowId("tool", "t1"), values = mapOf("name" to JsonPrimitive("Charkha"))),
            ),
        )
        val bucket = StageBucketDto(
            collections = mapOf(
                "tool" to listOf(
                    buildJsonObject {
                        put("_clientKey", "t1")
                        put("name", "Charkha")
                    },
                    buildJsonObject {
                        put("_entryId", "srv-9")
                        put("name", "Reed the office recorded")
                    },
                )
            )
        )
        val fold = dwFoldServerStage(spec, local, bucket, spec.key)
        assertEquals(2, fold.draft.rows.size)
        assertEquals(mapOf("tool" to 1), fold.addedRows)
        assertEquals(
            listOf("Charkha", "Reed the office recorded"),
            fold.draft.rows.map { (it.values["name"] as JsonPrimitive).content }
        )
    }

    @Test
    fun `a row under an entity this stage does not declare is left where it is`() {
        // It cannot be drawn, cannot be edited, and would be sent straight back as an entity the
        // server reports in `droppedKeys` — a phone reporting registry drift to itself.
        val bucket = StageBucketDto(
            collections = mapOf(
                "somethingTheRegistryDropped" to listOf(buildJsonObject { put("name", "x") })
            )
        )
        val fold = dwFoldServerStage(spec, null, bucket, spec.key)
        assertTrue(fold.draft.rows.isEmpty())
        assertTrue(fold.addedRows.isEmpty())
    }

    @Test
    fun `a deletion waiting to be sent survives the fold that finally lets it be sent`() {
        val local = StageDraft(
            stageId = spec.key,
            values = mapOf("artisanHouseholds" to JsonPrimitive(412)),
            emptiedEntities = listOf("tool"),
        )
        val fold = dwFoldServerStage(spec, local, StageBucketDto(), spec.key)
        assertEquals(listOf("tool"), fold.draft.emptiedEntities)

        // And it now reaches the wire, which it could not do while the stage was unread.
        val body = buildStageBody(spec, fold.draft, emptyMap(), isAuthoritative(fold.draft, null)).body
        assertTrue(body.replaceCollections)
        assertEquals(listOf("tool"), body.emptiedEntities)
    }

    // ── The fold that REVERSED the deletion it was carrying ───────────────────────────────────────

    /**
     * THE ASSERTION THAT FAILED, AND THE ONE THE TEST ABOVE COULD NOT MAKE.
     *
     * The case above folds an EMPTY bucket, so there were no rows to restore and it passed either way.
     * With rows in the bucket the fold added every one of them back, the next payload then NAMED them
     * all, and the server's sweep — which skips any row the payload touched (`design_workshops.py`,
     * `if row.id in touched_ids: continue`) — removed nothing. So `emptiedEntities` travelled exactly
     * as three comments in this repository promised and meant nothing when it arrived: the designer's
     * deletion was silently reversed by the read that was supposed to make it possible.
     */
    @Test
    fun `the fold does NOT restore rows the designer deleted, which is what makes the deletion mean anything`() {
        val local = StageDraft(
            stageId = spec.key,
            values = mapOf("artisanHouseholds" to JsonPrimitive(412)),
            // Every row gone, and the deletion recorded — the only way emptying a collection is
            // visible in a payload at all.
            rows = emptyList(),
            emptiedEntities = listOf("tool"),
        )
        val bucket = StageBucketDto(
            collections = mapOf(
                "tool" to listOf(
                    buildJsonObject { put("_clientKey", "t1"); put("name", "Charkha") },
                    buildJsonObject { put("_clientKey", "t2"); put("name", "Reed") },
                )
            )
        )
        val fold = dwFoldServerStage(spec, local, bucket, spec.key)

        assertTrue("the rows the designer deleted are not resurrected", fold.draft.rows.isEmpty())
        assertTrue("and are not announced as having appeared", fold.addedRows.isEmpty())
        // The reading is still earned — that half was never in doubt and is what carries the deletion.
        assertTrue(fold.draft.stageSeen)
        assertEquals(listOf("tool"), fold.draft.emptiedEntities)

        // AND NOW THE PAYLOAD ACTUALLY DELETES. It claims the sweep, names the entity, and names no
        // row inside it — which is the exact shape the server's sweep acts on.
        val body = buildStageBody(spec, fold.draft, emptyMap(), isAuthoritative(fold.draft, null)).body
        assertTrue(body.replaceCollections)
        assertEquals(listOf("tool"), body.emptiedEntities)
        assertTrue(
            "a payload that named the rows again would sweep nothing",
            body.entries.none { it.entityKey == "tool" }
        )
    }

    /**
     * THE COLLATERAL, MADE VISIBLE RATHER THAN MERELY RECOVERABLE.
     *
     * Honouring the deletion means a row somebody else added into that collection since goes with it.
     * That is the cheaper of the two failures — it is SOFT-deleted server-side and recoverable by its
     * client key, whereas a deliberate deletion silently reversed leaves no record anywhere and nobody
     * knows to look — but "recoverable in principle" is not the same as "the designer was told", and
     * only one of those is worth anything to somebody driving home from a cluster.
     */
    @Test
    fun `the collateral of honouring a deletion is announced rather than merely recoverable`() {
        val local = StageDraft(
            stageId = spec.key,
            rows = emptyList(),
            emptiedEntities = listOf("tool"),
        )
        val bucket = StageBucketDto(
            collections = mapOf(
                "tool" to listOf(
                    buildJsonObject { put("_entryId", "srv-1"); put("name", "A reed the office added") },
                    buildJsonObject { put("_entryId", "srv-2"); put("name", "And another") },
                )
            )
        )
        val fold = dwFoldServerStage(spec, local, bucket, spec.key)

        assertEquals(mapOf("tool" to 2), fold.sweptRows)
        assertEquals(2, fold.sweptRowCount)
        assertFalse("a sweep is something the designer can see, so it is not 'nothing new'", fold.isNothingNew)
        val notice = requireNotNull(fold.notice)
        assertTrue(notice, notice.contains("You had deleted everything in tool"))
        assertTrue(notice, notice.contains("2 rows the server still holds"))
        assertTrue(
            "it has to say the next save is what does it, while there is still time to act",
            notice.contains("the next save will delete")
        )
        assertTrue(
            "and that the deletion standing is what was asked for, not a fault",
            notice.contains("Your deletion stands")
        )
        // THE REMEDY MUST BE ONE THE DESIGNER CAN CARRY OUT. "Add the rows back here" is not: these are
        // rows this phone has never shown them. What is true — and checked against Postgres, where
        // `deletedAt` is stamped and the row is kept — is that the deletion is recorded rather than
        // erased, so the sentence names the fact that makes it fixable instead of an impossible action.
        assertTrue(notice, notice.contains("records a deletion rather than erasing the row"))
        assertTrue(notice, notice.contains("can be brought back by whoever runs it"))
        assertFalse(
            "it must not ask a designer to retype rows it never showed them:\n$notice",
            notice.contains("add the rows back here"),
        )
    }

    /**
     * A row the draft STILL HOLDS is not collateral, because the payload names it and the server's
     * sweep skips any row it touched. Counting it would be a false alarm about a row that is safe —
     * reachable in the ordinary way: empty a collection, then start it again with a fresh row.
     */
    @Test
    fun `a row the save will name is not counted as about to be deleted`() {
        val local = StageDraft(
            stageId = spec.key,
            rows = listOf(
                DraftRow(id = dwRowId("tool", "t1"), values = mapOf("name" to JsonPrimitive("Charkha"))),
            ),
            emptiedEntities = listOf("tool"),
        )
        val bucket = StageBucketDto(
            collections = mapOf(
                "tool" to listOf(
                    buildJsonObject { put("_clientKey", "t1"); put("name", "Charkha") },
                    buildJsonObject { put("_entryId", "srv-9"); put("name", "Reed the office added") },
                )
            )
        )
        val fold = dwFoldServerStage(spec, local, bucket, spec.key)

        assertEquals("only the one this device has never shown anybody", mapOf("tool" to 1), fold.sweptRows)
        assertEquals("and the row the designer kept is still the only one here", 1, fold.draft.rows.size)
        assertEquals(dwRowId("tool", "t1"), fold.draft.rows.single().id)

        // The row the draft holds is NAMED, so it survives the sweep it travels with.
        val body = buildStageBody(spec, fold.draft, emptyMap(), isAuthoritative(fold.draft, null)).body
        assertEquals(listOf("tool"), body.emptiedEntities)
        assertEquals(1, body.entries.count { it.entityKey == "tool" })
    }

    /**
     * THE SKIP IS PER ENTITY AND NOT PER STAGE, which is the way an over-broad fix would have been
     * invisible: a stage usually declares several collections, and emptying one of them must not stop
     * the others being read.
     */
    @Test
    fun `a collection the designer did not empty still folds normally alongside one they did`() {
        val wide = spec.copy(
            entities = spec.entities + EntityDto(
                key = "rawMaterial", cardinality = "COLLECTION", title = "Raw material",
                fields = listOf(FieldDto(key = "name", label = "Name", type = "TEXT")),
            )
        )
        val local = StageDraft(stageId = wide.key, rows = emptyList(), emptiedEntities = listOf("tool"))
        val bucket = StageBucketDto(
            collections = mapOf(
                "tool" to listOf(buildJsonObject { put("_entryId", "srv-1"); put("name", "Reed") }),
                "rawMaterial" to listOf(buildJsonObject { put("_entryId", "srv-2"); put("name", "Indigo") }),
            )
        )
        val fold = dwFoldServerStage(wide, local, bucket, wide.key)

        assertEquals("the collection they did not touch is read normally", mapOf("rawMaterial" to 1), fold.addedRows)
        assertEquals("and only the emptied one is held back", mapOf("tool" to 1), fold.sweptRows)
        assertEquals(1, fold.draft.rows.size)
        assertEquals(dwRowId("rawMaterial", "srv-2"), fold.draft.rows.single().id)
    }

    @Test
    fun `what appeared is announced, because a stage that changes under a designer must say so`() {
        val bucket = StageBucketDto(
            singleton = buildJsonObject { put("loomsWorking", 12) },
            custom = buildJsonObject { put("dyeVatCount", 3) },
            collections = mapOf(
                "tool" to listOf(buildJsonObject { put("_entryId", "srv-1"); put("name", "Reed") })
            ),
        )
        val notice = dwFoldServerStage(spec, StageDraft(stageId = spec.key), bucket, spec.key).notice
        requireNotNull(notice)
        assertTrue(notice.contains("read from the server"))
        assertTrue(notice.contains("loomsWorking"))
        assertTrue(notice.contains("1 row"))
        assertTrue(notice.contains("dyeVatCount"))
        assertTrue(
            "and it must promise the thing a designer will be worried about",
            notice.contains("Nothing you had typed here was changed.")
        )
    }

    @Test
    fun `the protocol's own keys are folded silently rather than announced as answers`() {
        val bucket = StageBucketDto(singleton = buildJsonObject { put("_entryId", "srv-1") })
        val fold = dwFoldServerStage(spec, StageDraft(stageId = spec.key), bucket, spec.key)
        assertEquals(JsonPrimitive("srv-1"), fold.draft.values["_entryId"])
        assertTrue("nothing a designer would recognise appeared", fold.added.isEmpty())
        assertEquals(null, fold.notice)
    }

    @Test
    fun `a stage this device holds nothing for folds to the server's copy verbatim`() {
        val bucket = StageBucketDto(
            singleton = buildJsonObject { put("loomsWorking", 12) },
            collections = mapOf(
                "tool" to listOf(buildJsonObject { put("_clientKey", "t9"); put("name", "Reed") })
            ),
        )
        val fold = dwFoldServerStage(spec, null, bucket, spec.key)
        assertEquals(JsonPrimitive(12), fold.draft.values["loomsWorking"])
        assertEquals(1, fold.draft.rows.size)
        assertEquals(dwRowId("tool", "t9"), fold.draft.rows.single().id)
        assertTrue(fold.draft.stageSeen)
        assertEquals(spec.key, fold.draft.stageId)
        assertEquals(spec.number, fold.draft.order)
    }

    // ── The on-disk shape ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a draft written by an earlier build claims nothing, whatever it said about baselines`() {
        /*
          THE MIGRATION, ASSERTED RATHER THAN ASSUMED. `serverBaseline` on disk is indistinguishable
          between a stage that was read and a stage the defect stamped, so v2 does not carry it: the
          key is unknown to this model, `ignoreUnknownKeys` drops it, and `stageSeen` defaults false.
          A draft that arrives claiming a baseline therefore claims nothing at all, which is the only
          safe reading of an unattributable claim.
        */
        val onDisk = """
            {"stageId":"CLUSTER_PROFILE","values":{"artisanHouseholds":412},"serverBaseline":true}
        """.trimIndent()
        val decoded = DRAFT_JSON.decodeFromString(StageDraft.serializer(), onDisk)

        assertFalse("the old claim does not survive the rename", decoded.stageSeen)
        assertEquals(JsonPrimitive(412), decoded.values["artisanHouseholds"])
        assertFalse(
            "so the next save merges rather than replacing",
            buildStageBody(spec, decoded, emptyMap(), isAuthoritative(decoded, null))
                .body.replaceCollections
        )
    }

    @Test
    fun `and an older build reading a NEW draft is equally conservative`() {
        // The other direction of the same rule: `stageSeen` is unknown to a build that predates it,
        // is dropped on decode, and `serverBaseline` is absent — so it too claims nothing. Lossless in
        // the safe direction both ways is what makes the ladder's no-op rung honest.
        val written = DRAFT_JSON.encodeToString(
            StageDraft.serializer(),
            StageDraft(stageId = spec.key, stageSeen = true),
        )
        assertTrue(written.contains("\"stageSeen\":true"))
        assertFalse("nothing is written under the old name", written.contains("serverBaseline"))
    }

    private companion object {
        /** The store's own reader settings, so these assertions are about the real decode. */
        val DRAFT_JSON = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
