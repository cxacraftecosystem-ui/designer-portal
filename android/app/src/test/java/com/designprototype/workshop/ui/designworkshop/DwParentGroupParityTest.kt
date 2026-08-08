package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.WorkshopDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [dwParentGroups] against `report_builder.ReportBuilder._parent_groups`, BY VALUE, over one shared
 * case table.
 *
 * ── WHY THIS EXISTS BESIDE `DwParentGroupTest` ──────────────────────────────────────────────────
 *
 * That suite is this repository's Python suite rewritten in Kotlin: the same sixteen situations,
 * asserted twice, in two languages, by hand. That proves each implementation matches SOMEBODY'S
 * READING of the rule, and two readings of one rule are two guesses. What a submitted document
 * needs is stronger and simpler — that the file the handset writes in a courtyard and the file the
 * server regenerates at the office put the same lines under the same sheet. Only a diff over values
 * says that. This is the standard the web port of `market_analysis` was held to over 76 cases, and
 * it is the standard a port is held to here.
 *
 * ── HOW THE TABLE IS BUILT AND WHY IT IS NOT CHECKED IN BY HAND ─────────────────────────────────
 *
 * `android/tools/gen_parent_group_cases.py` runs each case through the SERVER'S OWN
 * `_parent_groups` — imported, not transcribed — and writes its answers into
 * `app/src/test/resources/dw-parent-group-cases.json`:
 *
 *     python android/tools/gen_parent_group_cases.py \
 *         android/app/src/test/resources/dw-parent-group-cases.json
 *
 * Regenerate after any change to that method and this test will say whether the handset still
 * agrees. Nothing in the file was typed from an expectation. It is checked in rather than generated
 * during the build because this suite has to run on a machine with no Python and no backend
 * checkout — and because a case table regenerated silently on every run would agree with whatever
 * the server currently does, including with a regression.
 *
 * Both sides load their own copy of the REGISTRY rather than carrying entity shapes in the case
 * data: Python from `stage_definitions`, this test from the `design-workshop-schema.json` the APK
 * ships. So the parent declarations and the REF models the join is found by cannot drift apart
 * inside the fixture — if they drift, the diff says so, which is the point.
 *
 * ── THE TWO PLACES THE TWO ARE MEANT TO DIFFER, STATED SO A DIFF MEANS SOMETHING ─────────────────
 *
 * 1. HEADING PREFIX. The handset's `rowHeading` prints "Cost sheets — Sambalpuri stole" where the
 *    server prints "Sambalpuri stole"; that convention predates the grouping, applies to every
 *    record heading in the on-device report, and is asserted where it belongs, in
 *    `DwParentGroupTest.a group is headed by the same words as the sheet it belongs to`. It is
 *    normalised away here so that a REAL divergence — a group in the wrong order, a line under the
 *    wrong sheet, an orphan silently dropped — is not buried under sixteen cosmetic ones.
 * 2. LOCAL ROW KEYS. A row on this device answers to its `DraftRow` key as well as to the
 *    `_entryId` a sync gives it, and the server has only ever seen the second. Every case here
 *    gives each row an `_entryId` and a local key that collides with nothing, so the two
 *    implementations are being asked the same question; the local-key join is the handset's own
 *    and is pinned by `DwParentGroupTest.a line entered offline finds its sheet through the local
 *    row key`.
 *
 * A THIRD DIFFERENCE EXISTS AND IS NOT DELIBERATE, so no case here provokes it: when a parent's
 * label field is empty the server's `_row_label_text` falls through to that record's first free-text
 * answer before giving up on an ordinal, and `rowHeading` goes straight to the ordinal. That gap
 * predates the grouping and applies to EVERY record heading in the on-device report, not to groups;
 * closing it here would change what parent-free stages print, which is the one thing this whole
 * change is not allowed to do. Every parent row in the table below therefore carries either a
 * resolvable label or nothing at all, so the two implementations are answering the same question.
 */
class DwParentGroupParityTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A file of this repository, found by walking up from wherever the test runner started.
     *
     * The same approach `DwParentGroupTest.shippedRegistry` uses, and for the same reason: the
     * working directory of a Gradle test worker is not something to depend on, and a test that
     * skipped when it could not find its data would be a test that proves nothing on the day
     * somebody moves it. Missing is a failure, loudly.
     */
    private fun repoFile(vararg relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in relative) {
                val candidate = File(dir, path)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("none of ${relative.toList()} found from ${File(".").absolutePath}")
    }

    private fun caseTable(): JsonObject = json.parseToJsonElement(
        repoFile(
            "src/test/resources/dw-parent-group-cases.json",
            "app/src/test/resources/dw-parent-group-cases.json",
            "android/app/src/test/resources/dw-parent-group-cases.json",
        ).readText()
    ).jsonObject

    private fun shippedRegistry(): SchemaResponse = json.decodeFromString(
        SchemaResponse.serializer(),
        repoFile(
            "src/main/assets/design-workshop-schema.json",
            "app/src/main/assets/design-workshop-schema.json",
            "android/app/src/main/assets/design-workshop-schema.json",
        ).readText()
    )

    private fun entityOf(schema: SchemaResponse, stageKey: String, entityKey: String): EntityDto =
        schema.stages.first { it.key == stageKey }.entities.first { it.key == entityKey }

    /**
     * One case's rows as the draft store holds them.
     *
     * The local key is namespaced by the case index precisely so it can never coincide with an
     * `_entryId` the case uses — "p1" is a prototype's server id in two of these cases, and a local
     * key that happened to read "p1" would let the handset make a join the server cannot, turning a
     * genuine agreement into a fabricated one.
     */
    private fun draftRows(entityKey: String, rows: JsonArray, case: Int, role: String): List<DraftRow> =
        rows.mapIndexed { index, row ->
            DraftRow(id = "$entityKey#k$case$role$index", values = row.jsonObject.toMap())
        }

    /** The server prints the label alone; this device prefixes it with the entity's own title. */
    private fun normalise(heading: String, parentTitle: String): String =
        heading.removePrefix("$parentTitle — ")

    private fun text(value: JsonElement?): String =
        (value as? JsonPrimitive)?.content.orEmpty()

    @Test
    fun `the handset groups a child collection exactly as the server does`() {
        val table = caseTable()
        val schema = shippedRegistry()
        val products = table.getValue("products").jsonArray
        val labelKeys = table.getValue("labelKeys").jsonObject
        val cases = table.getValue("cases").jsonArray
        val expected = table.getValue("expected").jsonArray
        assertEquals("the case table and the server's answers are different lengths",
            cases.size, expected.size)
        assertTrue("the case table is empty", cases.size >= 16)

        val diffs = ArrayList<String>()
        for (index in cases.indices) {
            val case = cases[index].jsonObject
            val name = text(case["name"])
            val stageKey = text(case["stage"])
            val entityKey = text(case["entity"])
            val parentStageKey = text(case["parentStage"])
            val parentEntityKey = text(case["parentEntity"])

            // The role letter keeps the two lists apart in the one case where the child collection
            // IS the parent collection — without it both would be written under the same local key.
            val childRows = draftRows(entityKey, case.getValue("children").jsonArray, index, "c")
            val parentRows = draftRows(parentEntityKey, case.getValue("parents").jsonArray, index, "p")
            val productRows = draftRows("finalProduct", products, index, "f")

            // One StageDraft per stage the case touches, merged where the parent and the child live
            // in the same stage — which is stages 13 and 17, while stage 14's parent is in 13. That
            // crossing is the case a lookup confined to the stage being rendered gets wrong.
            val byStage = LinkedHashMap<String, MutableList<DraftRow>>()
            byStage.getOrPut("FINAL_PROTOTYPE_DOCUMENTATION") { ArrayList() } += productRows
            byStage.getOrPut(parentStageKey) { ArrayList() } += parentRows
            byStage.getOrPut(stageKey) { ArrayList() } += childRows
            val draft = WorkshopDraft(
                workshopId = "parity",
                title = "Parity",
                stages = byStage.mapValues { (key, rows) -> StageDraft(stageId = key, rows = rows) },
            )

            val entity = entityOf(schema, stageKey, entityKey)
            val parentTitle = entityOf(schema, parentStageKey, parentEntityKey).title
            val refs = DwRefLabels(schema, draft)
            val groups = dwParentGroups(
                schema = schema,
                draft = draft,
                entity = entity,
                rows = childRows.map { it.values },
                // The very lambdas `ReportScreen.renderStageSection` passes, so this is the
                // production join and not a re-implementation of it standing in for one.
                parentHeading = { parent, values, ordinal -> rowHeading(parent, values, ordinal, refs) },
                refLabel = { refId -> refs.label(refId) },
            )

            val labelKey = text(labelKeys[entityKey])
            val actual: List<Pair<String, List<String>>>? = groups?.map { group ->
                normalise(group.heading, parentTitle) to group.rows.map { text(it[labelKey]) }
            }

            val server = expected[index].jsonObject
            assertEquals("the case table and the answers are out of step at $index",
                name, text(server["name"]))
            val want: List<Pair<String, List<String>>>? =
                (server["groups"] as? JsonArray)?.map { group ->
                    text(group.jsonObject["heading"]) to
                        group.jsonObject.getValue("rows").jsonArray.map { text(it) }
                }

            if (actual != want) {
                diffs += "  $name\n      server:  $want\n      handset: $actual"
            }
        }

        assertEquals(
            "the handset and the server disagree about ${diffs.size} of ${cases.size} cases:\n" +
                diffs.joinToString("\n"),
            0,
            diffs.size,
        )
    }
}
