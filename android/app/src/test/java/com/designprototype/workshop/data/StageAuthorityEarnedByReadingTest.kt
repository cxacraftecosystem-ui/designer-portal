package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun typedOneFieldOffline() = StageDraft(
        stageId = spec.key,
        values = mapOf("artisanHouseholds" to JsonPrimitive(412)),
        stageSeen = false,
    )

    private fun build(stored: StageDraft?, record: StageSyncRecord? = null) =
        buildStageBody(spec, stored, emptyMap(), isAuthoritative(stored, record))

    @Test
    fun `the first save from a stage that failed to download is a merge`() {
        val singleton = build(typedOneFieldOffline()).body.entries.single()
        assertTrue("the office's fields must be preserved by the server", singleton.merge)
        assertEquals(JsonPrimitive(412), singleton.data["artisanHouseholds"])
    }

    @Test
    fun `and the save after it is still a merge, because nothing has been read in between`() {
        val afterOneSave = typedOneFieldOffline()
        val record = StageSyncRecord(signature = "whatever-the-first-save-hashed-to")

        val second = build(afterOneSave, record).body
        assertTrue("still a merge", second.entries.single().merge)
        assertFalse("and still no sweep", second.replaceCollections)
    }

    @Test
    fun `the promotion alone re-sends the stage as a replacement, with nobody typing`() {
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
        assertEquals(JsonPrimitive(412), fold.draft.values["artisanHouseholds"])
        assertEquals(JsonPrimitive(12), fold.draft.values["loomsWorking"])
        assertEquals(listOf("loomsWorking", "clusterNotes"), fold.added)

        val body = buildStageBody(spec, fold.draft, emptyMap(), isAuthoritative(fold.draft, null)).body
        assertFalse("now, and only now, a replacement is honest", body.entries.single().merge)
        assertEquals(JsonPrimitive(12), body.entries.single().data["loomsWorking"])
    }

    @Test
    fun `the fold never overwrites what the designer typed, including a value they cleared to blank`() {
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

        val body = buildStageBody(spec, fold.draft, emptyMap(), isAuthoritative(fold.draft, null)).body
        assertTrue(body.replaceCollections)
        assertEquals(listOf("tool"), body.emptiedEntities)
    }

    @Test
    fun `the fold does NOT restore rows the designer deleted, which is what makes the deletion mean anything`() {
        val local = StageDraft(
            stageId = spec.key,
            values = mapOf("artisanHouseholds" to JsonPrimitive(412)),
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
        assertTrue(fold.draft.stageSeen)
        assertEquals(listOf("tool"), fold.draft.emptiedEntities)

        val body = buildStageBody(spec, fold.draft, emptyMap(), isAuthoritative(fold.draft, null)).body
        assertTrue(body.replaceCollections)
        assertEquals(listOf("tool"), body.emptiedEntities)
        assertTrue(
            "a payload that named the rows again would sweep nothing",
            body.entries.none { it.entityKey == "tool" }
        )
    }

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
        assertTrue(notice, notice.contains("2 entries still saved online"))
        assertTrue(
            "it has to say the next save is what does it, while there is still time to act",
            notice.contains("the next save will delete")
        )
        assertTrue(
            "and that the deletion standing is what was asked for, not a fault",
            notice.contains("Your deletion stands")
        )
        assertTrue(notice, notice.contains("a deleted entry is kept"))
        assertTrue(notice, notice.contains("an administrator can bring them back"))
        assertFalse(
            "it must not ask a designer to retype rows it never showed them:\n$notice",
            notice.contains("add the rows back here"),
        )
    }

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

        val body = buildStageBody(spec, fold.draft, emptyMap(), isAuthoritative(fold.draft, null)).body
        assertEquals(listOf("tool"), body.emptiedEntities)
        assertEquals(1, body.entries.count { it.entityKey == "tool" })
    }

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
        assertTrue(notice.contains("latest online version"))
        assertTrue(notice.contains("loomsWorking"))
        assertTrue(notice.contains("1 entry"))
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

    @Test
    fun `a draft written by an earlier build claims nothing, whatever it said about baselines`() {
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
        val written = DRAFT_JSON.encodeToString(
            StageDraft.serializer(),
            StageDraft(stageId = spec.key, stageSeen = true),
        )
        assertTrue(written.contains("\"stageSeen\":true"))
        assertFalse("nothing is written under the old name", written.contains("serverBaseline"))
    }

    private companion object {
        val DRAFT_JSON = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
