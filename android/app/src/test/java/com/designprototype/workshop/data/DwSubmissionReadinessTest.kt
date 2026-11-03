package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwSubmissionReadinessTest {

    private val workshopId = "cmspecworkshop00000000001"

    private val registry = SchemaResponse(
        version = "spec",
        enums = mapOf(
            "REPORT_TEMPLATE" to listOf(
                EnumOption(value = "DCH_STANDARD", label = "DCH standard workshop report"),
                EnumOption(value = "PHOTO_CATALOGUE", label = "Photo catalogue"),
            )
        ),
        stages = listOf(
            StageDto(
                number = 1, key = "SETUP", title = "Workshop setup",
                entities = listOf(
                    EntityDto(
                        key = "workshopSetup", name = "DwWorkshopSetup", cardinality = "SINGLETON",
                        title = "Workshop setup",
                        fields = listOf(
                            FieldDto(key = "craftName", label = "Craft name", type = "TEXT", tier = "BASIC", required = true),
                            FieldDto(key = "venue", label = "Venue", type = "TEXT", tier = "BASIC", required = true),
                            FieldDto(key = "weather", label = "Weather", type = "TEXT", tier = "STANDARD", required = false),
                            FieldDto(
                                key = "oldCode", label = "Old code", type = "TEXT", tier = "BASIC",
                                required = true, deprecated = true,
                            ),
                        ),
                    )
                ),
            ),
            StageDto(
                number = 13, key = "PROTOTYPE", title = "Prototype development",
                entities = listOf(
                    EntityDto(
                        key = "prototype", name = "DwPrototype", cardinality = "COLLECTION",
                        title = "Prototypes", labelField = "name",
                        fields = listOf(
                            FieldDto(key = "name", label = "Prototype name", type = "TEXT", tier = "BASIC", required = true),
                            FieldDto(key = "material", label = "Material", type = "TEXT", tier = "BASIC", required = true),
                            FieldDto(key = "photo", label = "Photograph", type = "IMAGE", tier = "STANDARD", required = false),
                            FieldDto(
                                key = "photoCaption", label = "Photograph caption", type = "TEXT",
                                tier = "BASIC", required = true, captionFor = "photo",
                            ),
                        ),
                    )
                ),
            ),
            StageDto(
                number = 20, key = "REPORT_GENERATION", title = "Report Generation & Submission",
                entities = listOf(
                    EntityDto(
                        key = "reportSettings", name = "DwReportSettings", cardinality = "SINGLETON",
                        title = "Report settings",
                        fields = listOf(
                            FieldDto(
                                key = "templateId", label = "Report template", type = "ENUM",
                                tier = "BASIC", required = true, enumName = "REPORT_TEMPLATE",
                            ),
                            FieldDto(
                                key = "excludedStages", label = "Stages to leave out", type = "TAGS",
                                tier = "STANDARD", required = false,
                            ),
                        ),
                    )
                ),
            ),
        ),
    )

    private fun stage(
        stageKey: String,
        singleton: Map<String, JsonElement> = emptyMap(),
        rows: List<DraftRow> = emptyList(),
    ) = StageDraft(stageId = stageKey, values = singleton, rows = rows)

    private fun row(entityKey: String, rowKey: String, values: Map<String, JsonElement>) =
        DraftRow(id = dwRowId(entityKey, rowKey), values = values)

    private fun draftWith(vararg stages: StageDraft) =
        WorkshopDraft(workshopId = workshopId, stages = stages.associateBy { it.stageId })

    private fun text(value: String): JsonElement = JsonPrimitive(value)

    private val draft = draftWith(
        stage("SETUP", singleton = mapOf("craftName" to text("Sambalpuri bandha"))),
        stage(
            "PROTOTYPE",
            rows = listOf(
                row("prototype", "proto-1", mapOf("name" to text("Phoda kumbha table runner"))),
                row(
                    "prototype", "proto-2",
                    mapOf("name" to text("Saptapar stole"), "material" to text("Tussar silk")),
                ),
            ),
        ),
        stage("REPORT_GENERATION", singleton = mapOf("templateId" to text("DCH_STANDARD"))),
    )

    private val readiness = DwSubmissionReadiness.assess(registry, draft, workshopId)

    private fun labels(): List<String> = readiness.blocking.map { it.label }

    @Test
    fun `an unfilled required field blocks and an unfilled optional one does not`() {
        assertTrue(labels().contains("Venue"))
        assertFalse(labels().contains("Weather"))
        assertTrue(readiness.advisory.map { it.stageKey }.contains("SETUP"))
    }

    @Test
    fun `a filled field is not listed, and a deprecated one is never listed at all`() {
        assertFalse(labels().contains("Craft name"))
        assertFalse(labels().contains("Old code"))
    }

    @Test
    fun `a collection's missing field is one entry that says how many rows it covers`() {
        assertEquals(1, labels().count { it == "Prototypes: Photograph caption" })

        val caption = readiness.blocking.first { it.label == "Prototypes: Photograph caption" }
        assertEquals("both rows are missing a caption", 2, caption.address?.occurrences)
        assertEquals(2, caption.address?.rowCount)

        val material = readiness.blocking.first { it.label == "Prototypes: Material" }
        assertEquals("only the first row is missing its material", 1, material.address?.occurrences)
        assertEquals(2, material.address?.rowCount)
    }

    @Test
    fun `every entry deep-links to its own stage, and a collection entry to its own row`() {
        for (item in readiness.blocking) {
            assertTrue(
                "${item.label} links into its stage",
                item.href.contains("/design-workshops/$workshopId/stages/${item.stageKey}"),
            )
            assertNotNull("${item.label} was placed", item.address)
        }

        val material = readiness.blocking.first { it.label == "Prototypes: Material" }
        assertEquals(
            "/design-workshops/$workshopId/stages/PROTOTYPE?find=prototype.material&row=proto-1",
            material.href,
        )
        assertEquals(
            "the row is named the way a designer recognises it",
            "Phoda kumbha table runner",
            material.address?.rowTitle,
        )
        assertEquals("Material · Phoda kumbha table runner", DwSubmissionReadiness.fieldName(material))
    }

    @Test
    fun `a caption anchors to the media field it is drawn under, not to itself`() {
        val caption = readiness.blocking.first { it.label == "Prototypes: Photograph caption" }
        assertEquals("photo", caption.address?.anchorFieldKey)
        assertTrue(caption.href.contains("find=prototype.photo"))
    }

    @Test
    fun `the focus a handset navigates by names the same box as the href a person pastes`() {
        for (item in readiness.blocking) {
            val focus = DwSubmissionReadiness.focusOf(item)
            assertNotNull("${item.label} was placed", focus)
            assertEquals(item.stageKey, focus!!.stageKey)
            assertEquals(
                "${item.label}: the focus and the href name one field",
                DwSubmissionReadiness.stageFieldHref(
                    workshopId, focus.stageKey, focus.entityKey, focus.fieldKey, focus.rowKey,
                ),
                item.href,
            )
        }
    }

    @Test
    fun `a collection focus carries its row and a singleton focus carries none`() {
        val material = DwSubmissionReadiness.focusOf(
            readiness.blocking.first { it.label == "Prototypes: Material" }
        )!!
        assertEquals("prototype", material.entityKey)
        assertEquals("material", material.fieldKey)
        assertEquals("proto-1", material.rowKey)

        val caption = DwSubmissionReadiness.focusOf(
            readiness.blocking.first { it.label == "Prototypes: Photograph caption" }
        )!!
        assertEquals("photo", caption.fieldKey)

        val venue = DwSubmissionReadiness.focusOf(readiness.blocking.first { it.label == "Venue" })!!
        assertEquals("workshopSetup", venue.entityKey)
        assertEquals("venue", venue.fieldKey)
        assertEquals("a singleton has no row to open", null, venue.rowKey)
    }

    @Test
    fun `the address book is keyed by the label the scorer produced`() {
        val book = DwSubmissionReadiness.addressBook(readiness)
        for (score in computeWorkshopCompleteness(registry, draft)) {
            for (label in score.missing) {
                assertNotNull(
                    "stage ${score.stageKey} label “$label” is addressable",
                    book[score.stageKey]?.get(label),
                )
            }
        }
        assertFalse(book.containsKey("REPORT_GENERATION"))
    }

    @Test
    fun `a stage-20 template this build does not offer is reported, and a known one is not`() {
        assertFalse(
            "DCH_STANDARD is a template this registry offers",
            readiness.checks.map { it.id }.contains("template-unknown"),
        )

        val stale = DwSubmissionReadiness.assess(
            registry,
            draftWith(stage("REPORT_GENERATION", singleton = mapOf("templateId" to text("MINISTRY_2031")))),
            workshopId,
        )
        val check = stale.checks.firstOrNull { it.id == "template-unknown" }
        assertNotNull(
            "an unrecognised template is what `resolve_template_id` silently ignores",
            check,
        )
        assertTrue(check!!.href.contains("find=reportSettings.templateId"))
        assertTrue(check.detail.contains("\"MINISTRY_2031\""))
    }

    @Test
    fun `a stage excluded from the report is reported only when it holds answers`() {
        val withAnswers = DwSubmissionReadiness.assess(
            registry,
            draftWith(
                stage("SETUP", singleton = mapOf("craftName" to text("Sambalpuri bandha"))),
                stage(
                    "REPORT_GENERATION",
                    singleton = mapOf(
                        "templateId" to text("DCH_STANDARD"),
                        "excludedStages" to buildJsonArray { add("SETUP") },
                    ),
                ),
            ),
            workshopId,
        )
        assertTrue(withAnswers.checks.map { it.id }.contains("stage-excluded:SETUP"))

        val empty = DwSubmissionReadiness.assess(
            registry,
            draftWith(
                stage(
                    "REPORT_GENERATION",
                    singleton = mapOf(
                        "templateId" to text("DCH_STANDARD"),
                        "excludedStages" to buildJsonArray { add("SETUP") },
                    ),
                )
            ),
            workshopId,
        )
        assertFalse(empty.checks.map { it.id }.contains("stage-excluded:SETUP"))
    }

    @Test
    fun `the report screen reaches the same two checks assess computes, entry for entry`() {
        val exporting = draftWith(
            stage("SETUP", singleton = mapOf("craftName" to text("Sambalpuri bandha"))),
            stage(
                "REPORT_GENERATION",
                singleton = mapOf(
                    "templateId" to text("MINISTRY_2031"),
                    "excludedStages" to buildJsonArray { add("SETUP") },
                ),
            ),
        )

        val direct = DwSubmissionReadiness.reportChecks(
            registry, exporting, workshopId, computeWorkshopCompleteness(registry, exporting, null),
        )
        val throughAssess = DwSubmissionReadiness.assess(registry, exporting, workshopId).checks

        assertEquals(
            "the report screen must be able to state both without assembling a readiness result",
            listOf("template-unknown", "stage-excluded:SETUP"),
            direct.map { it.id },
        )
        assertEquals(
            "one question, one answer: the export screen and the stage index must not drift",
            throughAssess.map { it.id to it.detail },
            direct.map { it.id to it.detail },
        )
    }

    @Test
    fun `an excluded stage is judged by the copy being exported, not by the local one`() {
        val settings = stage(
            "REPORT_GENERATION",
            singleton = mapOf(
                "templateId" to text("DCH_STANDARD"),
                "excludedStages" to buildJsonArray { add("SETUP") },
            ),
        )
        val localOnly = draftWith(settings)
        val merged = draftWith(
            stage("SETUP", singleton = mapOf("craftName" to text("Sambalpuri bandha"))),
            settings,
        )

        assertFalse(
            "nothing is being lost from the file, so nothing is said",
            DwSubmissionReadiness.reportChecks(
                registry, localOnly, workshopId,
                computeWorkshopCompleteness(registry, localOnly, null),
            ).map { it.id }.contains("stage-excluded:SETUP"),
        )
        assertTrue(
            "the downloaded stage IS in the document's source and IS being dropped from it",
            DwSubmissionReadiness.reportChecks(
                registry, merged, workshopId, computeWorkshopCompleteness(registry, merged, null),
            ).map { it.id }.contains("stage-excluded:SETUP"),
        )
    }

    @Test
    fun `the stage index header and the summary count stages with one rule`() {
        val header = dwStageProgress(computeWorkshopCompleteness(registry, draft, null))
        assertEquals(readiness.stagesComplete, header.stagesComplete)
        assertEquals(readiness.stagesTotal, header.stagesTotal)
        assertEquals(readiness.blocking.size, header.requiredLeft)
        assertEquals("1 of 3 stages complete · 3 required fields left", dwStageProgressLine(header))
    }

    @Test
    fun `the summary counts stages and fields rather than printing a percentage`() {
        assertEquals(
            "${readiness.stagesComplete} of 3 stages complete; ${readiness.blocking.size} " +
                "required fields remain in ${readiness.blockedStages} stages.",
            DwSubmissionReadiness.summary(readiness),
        )
        assertEquals(
            "1 of 3 stages complete; 3 required fields remain in 2 stages.",
            DwSubmissionReadiness.summary(readiness),
        )

        val done = DwSubmissionReadiness.assess(
            registry,
            draftWith(
                stage(
                    "SETUP",
                    singleton = mapOf("craftName" to text("Sambalpuri bandha"), "venue" to text("Barpali")),
                ),
                stage("REPORT_GENERATION", singleton = mapOf("templateId" to text("DCH_STANDARD"))),
            ),
            workshopId,
        )
        assertTrue(done.isSubmittable)
        assertEquals("3 of 3 stages complete; no required fields remain.", DwSubmissionReadiness.summary(done))
    }

    @Test
    fun `one outstanding field is worded in the singular`() {
        val nearly = DwSubmissionReadiness.assess(
            registry,
            draftWith(
                stage("SETUP", singleton = mapOf("craftName" to text("Sambalpuri bandha"))),
                stage("REPORT_GENERATION", singleton = mapOf("templateId" to text("DCH_STANDARD"))),
            ),
            workshopId,
        )
        assertEquals(
            "2 of 3 stages complete; 1 required field remains in 1 stage.",
            DwSubmissionReadiness.summary(nearly),
        )
    }

    @Test
    fun `the blocking list agrees with the scorer, entry for entry`() {
        val drafts = linkedMapOf<String, WorkshopDraft?>(
            "the half-filled fixture" to draft,
            "an untouched workshop" to draftWith(),
            "a workshop with no draft on this device" to null,
            "stage 1 only" to draftWith(stage("SETUP", singleton = mapOf("craftName" to text("Sambalpuri bandha")))),
            "a finished workshop" to draftWith(
                stage(
                    "SETUP",
                    singleton = mapOf("craftName" to text("Sambalpuri bandha"), "venue" to text("Barpali")),
                ),
                stage("REPORT_GENERATION", singleton = mapOf("templateId" to text("DCH_STANDARD"))),
            ),
        )

        for ((name, candidate) in drafts) {
            val scores = computeWorkshopCompleteness(registry, candidate)
            val fromScorer = scores.flatMap { score -> score.missing.map { "${score.stageKey}:$it" } }
            val assessed = DwSubmissionReadiness.assess(registry, candidate, workshopId)
            val fromReadiness = assessed.blocking.map { "${it.stageKey}:${it.label}" }

            assertEquals(
                "$name: the blocking list is the scorer's `missing`, in its order",
                fromScorer,
                fromReadiness,
            )
            assertEquals(
                "$name: submittability follows the scorer",
                fromScorer.isEmpty(),
                assessed.isSubmittable,
            )
            assertEquals("$name: filled count", scores.sumOf { it.requiredFilled }, assessed.requiredFilled)
            assertEquals("$name: required total", scores.sumOf { it.requiredTotal }, assessed.requiredTotal)
        }
    }
}
