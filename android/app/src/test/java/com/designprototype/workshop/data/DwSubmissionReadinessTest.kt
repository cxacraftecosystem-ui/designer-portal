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

/**
 * [DwSubmissionReadiness] on its own — no Compose, no server, no filesDir.
 *
 * Case for case the same spec as `frontend/e2e/submission-readiness-unit.spec.ts`, which is the
 * specification both clients answer to. A case that file covers and this one does not is a place the
 * two screens are free to disagree about whether a workshop may be submitted.
 *
 * THE ASSERTION THIS FILE EXISTS FOR is the last one: the blocking list must be exactly
 * [DwStageCompleteness.missing], entry for entry. That module claims in its header that it assembles
 * the existing scorer rather than re-deriving completeness, and a claim like that decays the moment
 * nobody checks it — someone adds "…and also warn when a collection is empty", the readiness screen
 * starts naming obstacles the Save button is perfectly happy with, and the designer it was written
 * for learns to distrust it. Everything above that test is the behaviour a reader would expect;
 * `agrees with the scorer, entry for entry` is the invariant.
 *
 * The fixture is hand-built rather than read from the bundled registry asset, for the reason
 * `DesignWorkshopListingTest` gives: a fixture that changed when somebody edited stage 5 would make
 * every failure here ambiguous.
 */
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
                            // Optional, and unfilled: it must reach `advisory` and never the blocking list.
                            FieldDto(key = "weather", label = "Weather", type = "TEXT", tier = "STANDARD", required = false),
                            // Deprecated AND required: a dead input no form draws, so it must be
                            // skipped entirely — listing it would send a designer hunting for a box
                            // that does not exist.
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

    // ── The fixture, in the draft store's own shape ──────────────────────────────────────────────

    private fun stage(
        stageKey: String,
        singleton: Map<String, JsonElement> = emptyMap(),
        rows: List<DraftRow> = emptyList(),
    ) = StageDraft(stageId = stageKey, values = singleton, rows = rows)

    /** A collection row whose client key — the suffix of [DraftRow.id] — is what a link names. */
    private fun row(entityKey: String, rowKey: String, values: Map<String, JsonElement>) =
        DraftRow(id = dwRowId(entityKey, rowKey), values = values)

    private fun draftWith(vararg stages: StageDraft) =
        WorkshopDraft(workshopId = workshopId, stages = stages.associateBy { it.stageId })

    private fun text(value: String): JsonElement = JsonPrimitive(value)

    /**
     * Stage 1 half-answered; two prototype rows, the first missing its material, both missing
     * captions; stage 20 answered with a template this registry does offer.
     */
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

    // ── What blocks, and what does not ───────────────────────────────────────────────────────────

    @Test
    fun `an unfilled required field blocks and an unfilled optional one does not`() {
        assertTrue(labels().contains("Venue"))
        // "Weather" is STANDARD tier. It has to be absent from the blocking list entirely — a
        // Standard gap that appeared beside a Basic one would tell a designer to do work no submit
        // is waiting on.
        assertFalse(labels().contains("Weather"))
        assertTrue(readiness.advisory.map { it.stageKey }.contains("SETUP"))
    }

    @Test
    fun `a filled field is not listed, and a deprecated one is never listed at all`() {
        assertFalse(labels().contains("Craft name"))
        // The dead input. [computeStageCompleteness] skips it, so this module must never surface it —
        // a designer sent to find "Old code" would search a form that has not drawn that box since it
        // was retired.
        assertFalse(labels().contains("Old code"))
    }

    @Test
    fun `a collection's missing field is one entry that says how many rows it covers`() {
        // De-duplicated by label exactly as the server de-duplicates it, so eleven prototypes missing
        // a material are one item and not eleven.
        assertEquals(1, labels().count { it == "Prototypes: Photograph caption" })

        val caption = readiness.blocking.first { it.label == "Prototypes: Photograph caption" }
        assertEquals("both rows are missing a caption", 2, caption.address?.occurrences)
        assertEquals(2, caption.address?.rowCount)

        val material = readiness.blocking.first { it.label == "Prototypes: Material" }
        assertEquals("only the first row is missing its material", 1, material.address?.occurrences)
        assertEquals(2, material.address?.rowCount)
    }

    // ── Being able to act on an entry ────────────────────────────────────────────────────────────

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
        // The FIRST offending row keeps the link — proto-1 is the one without a material, and proto-2
        // has one, so a link naming proto-2 would land the designer on a row that is already answered.
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
        // A caption input has no wrapper of its own — it is drawn beneath its media field — so
        // focusing `photoCaption` would scroll to nothing and the item would land on the stage with
        // no highlight.
        assertEquals("photo", caption.address?.anchorFieldKey)
        assertTrue(caption.href.contains("find=prototype.photo"))
    }

    // ── What the handset navigates by ────────────────────────────────────────────────────────────

    /**
     * The href is for people; [DwStageFocus] is for this client.
     *
     * `AndroidManifest.xml` declares MAIN/LAUNCHER and nothing else, so there is nothing here that
     * could open `/design-workshops/…?find=…`. If the two ever described different boxes, the
     * designer would be sent to one field and told about another — so they are asserted against each
     * other rather than each against a literal.
     */
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
        // The row the stage screen opens. It is the `_clientKey`, which is what `CollectionRow.rowId`
        // holds — a focus naming anything else would expand no row and land on a closed heading.
        assertEquals("proto-1", material.rowKey)

        val caption = DwSubmissionReadiness.focusOf(
            readiness.blocking.first { it.label == "Prototypes: Photograph caption" }
        )!!
        // The MEDIA field, not the caption's own key: a caption input is drawn inside the field it
        // describes and has no wrapper to scroll to.
        assertEquals("photo", caption.fieldKey)

        val venue = DwSubmissionReadiness.focusOf(readiness.blocking.first { it.label == "Venue" })!!
        assertEquals("workshopSetup", venue.entityKey)
        assertEquals("venue", venue.fieldKey)
        assertEquals("a singleton has no row to open", null, venue.rowKey)
    }

    /**
     * The screen looks each label up by the string it is already printing.
     *
     * The stage index renders [DwStageCompleteness.missing] verbatim and asks this map where each
     * entry lives. A key that did not match the printed label character for character would produce
     * a list that silently stopped being tappable — which looks exactly like the inert list this
     * replaced.
     */
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
        // A stage with nothing outstanding contributes no entry at all rather than an empty map.
        assertFalse(book.containsKey("REPORT_GENERATION"))
    }

    // ── The report's checks, beyond the fields ───────────────────────────────────────────────────

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
        // The token is quoted back verbatim, because the designer has to recognise the answer they
        // are being asked to re-pick.
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

        // Excluding an EMPTY stage is exactly what the setting is for. Warning about it would train a
        // designer to skim this section, and then the one warning that mattered would go unread too.
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

    // ── Saying it honestly ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the summary counts stages and fields rather than printing a percentage`() {
        assertEquals(
            "${readiness.stagesComplete} of 3 stages complete; ${readiness.blocking.size} " +
                "required fields remain in ${readiness.blockedStages} stages.",
            DwSubmissionReadiness.summary(readiness),
        )
        // Spelled out as well as composed, so the sentence cannot be satisfied by a readiness object
        // that counts the fixture wrongly in two places at once.
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
        // No prototype rows at all: an empty collection contributes nothing, which is a legitimate
        // state and NOT an obstacle — the same rule `stage_completeness` applies.
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

    // ── The invariant ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the blocking list agrees with the scorer, entry for entry`() {
        /*
         * THE WHOLE CONTRACT OF THIS MODULE. If these two ever disagree, a second completeness
         * algorithm has grown here — and the readiness screen and the Save button would then be
         * giving a designer two different answers to "may I submit this?".
         *
         * ASSERTED OVER SEVERAL DRAFTS, and that is not thoroughness for its own sake. Written
         * against the main fixture alone this test PASSED against a deliberately planted drift ("an
         * empty collection is also an obstacle"), because that fixture happens to have rows in its
         * only collection — the one shape the bug needed to show itself was the one shape the fixture
         * did not have. An empty draft, a draft with populated collections and a finished draft are
         * the three states a real workshop passes through, and the invariant has to hold in all of
         * them or it is not an invariant.
         *
         * The fourth state is this client's alone: NO DRAFT AT ALL. A stage index opened on a record
         * the phone has only ever seen in a list has nothing on disk, and "every required field is
         * outstanding" has to come out of that rather than a crash.
         */
        val drafts = linkedMapOf<String, WorkshopDraft?>(
            "the half-filled fixture" to draft,
            // Nothing recorded at all — every collection empty, which is a legitimate day-one state
            // and the case the planted drift got wrong.
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
            // The counts are the scorer's too, not re-totalled from the item list.
            assertEquals("$name: filled count", scores.sumOf { it.requiredFilled }, assessed.requiredFilled)
            assertEquals("$name: required total", scores.sumOf { it.requiredTotal }, assessed.requiredTotal)
        }
    }
}
