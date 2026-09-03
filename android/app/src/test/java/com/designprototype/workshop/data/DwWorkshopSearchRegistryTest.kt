package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two claims [DwWorkshopSearch] makes about the REAL registry, checked against the real registry.
 *
 * `DwWorkshopSearchTest` is the spec, and its fixture is hand-built on purpose so that a failure
 * there names a rule rather than a stage somebody edited. That leaves one thing untested, and it is
 * the thing most likely to rot: decision 4 — "a REF is indexed by its resolved LABEL or not at all" —
 * is only as good as the box the label is read out of, and this client DERIVES that box from the
 * registry where the web reads it out of a hand-written table (`DW_REFERENCE_HYDRATION` in
 * `lib/designWorkshops.ts`). `DwReferenceField` explains at length why the table is not copied here:
 * a second client-side copy of the server's hydration rules drifts, and an inline-created artisan
 * then fills a row differently from the same artisan picked off the list an hour later.
 *
 * A DERIVATION THAT AGREES TODAY CAN STOP AGREEING WITHOUT ANYBODY TOUCHING THIS CODE. The rule is
 * "the ref's own key with `Ref` traded for `Name`, then plain `name`" — so the day somebody adds a
 * `toolName` field to `tool`, or an `artisanName` to `participant`, the handset starts reading the
 * label out of a different box from the one the browser reads, silently, for a REF the designer can
 * still see filled in on both screens. This file is what fails on that day, here, rather than as
 * "the search cannot find our artisans" a fortnight later in a cluster.
 *
 * Read through the PUBLIC surface — build an index, run a query — rather than by reaching into the
 * private resolver, because what has to agree is what a designer can search for, not an internal
 * spelling. See `DwParentGroupParityTest` for the same repo-file idiom.
 */
class DwWorkshopSearchRegistryTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * A file of this repository, found by walking up from wherever the test runner started.
     *
     * The working directory of a Gradle test worker is not something to depend on, and a test that
     * skipped when it could not find its data would be a test that proves nothing on the day somebody
     * moves it. Missing is a failure, loudly.
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

    private val registry: SchemaResponse = json.decodeFromString(
        SchemaResponse.serializer(),
        repoFile(
            "src/main/assets/design-workshop-schema.json",
            "app/src/main/assets/design-workshop-schema.json",
            "android/app/src/main/assets/design-workshop-schema.json",
        ).readText()
    )

    /**
     * `entity.field` → the row key `referenceDisplayHint` in `lib/designWorkshops.ts` reads the
     * display name out of, transcribed from `DW_REFERENCE_HYDRATION`.
     *
     * The web's table is keyed by the SOURCE key on the referenced record and maps to the TARGET key
     * on the row; `referenceDisplayHint` tries the sources "name" then "craftName" and reads whatever
     * they map to. What is written here is the answer to that walk — the box on the row — because
     * that is the only part of the table this module's behaviour depends on.
     */
    private val webHydration = mapOf(
        "workshopSetup.craftRef" to "craftName",
        "participant.artisanRef" to "name",
        "tool.toolRef" to "name",
        "processStep.processRef" to "name",
        "existingProduct.artisanRef" to "artisanName",
        "existingProduct.productRef" to "name",
        "prototype.productRef" to "productName",
        // Added 2026-08-16 with the widening of the server/web hydration table from seven pairs to
        // eight. This row is why the test failed: the registry declared the ref, the web's table
        // named `documentedProcessName` for it, and this client's derivation could spell only
        // `processName` and `name` — so the stage-5 documented process was in the web's search index
        // and absent from the phone's. See the `documented` rule in `DwWorkshopSearch`.
        "traditionalProcess.processRef" to "documentedProcessName",
        // Added 2026-08-24, when the table went from eight pairs to eleven: a sixth reference model
        // and a `productRef` parent on both stage-5 process pickers.
        //
        // `interviewTitle` and not `interviewName`: a `QuestionnaireInterview`'s label is its TITLE
        // (`REFERENCE_MODELS`'s `label` lambda reads `r.title`), so the entity has no `…Name` box for
        // the two `Name` rules to find.
        "artisanBaseline.interviewRef" to "interviewTitle",
        // The stage-7 instrument link (spec §22, 2026-09-03): the portal labels the chosen
        // questionnaire from `questionnaireName`, the `fromref` box the promotion added.
        "surveyPlan.questionnaireRef" to "questionnaireName",
        // Both of these land in `documentedFor`, the box that says which product the documented thing
        // was documented FOR. `processStep` is why the client's rule for it is conditional and not a
        // fourth spelling: the step declares `documentedFor` AND its own `name`, and its `processRef`
        // reads that `name`, so an unconditional rule would label every step's process with the
        // product instead.
        "traditionalProcess.productRef" to "documentedFor",
        "processStep.productRef" to "documentedFor",
    )

    /**
     * One REF field, and where it lives.
     *
     * `refField` and not `field`: inside a property accessor `field` is the SOFT KEYWORD naming the
     * backing field, so `${field.key}` below does not compile at all — the same trap [FieldDto] names
     * on its `enum` property, met from the other direction.
     */
    private data class RefSite(val stage: StageDto, val entity: EntityDto, val refField: FieldDto) {
        val pair: String get() = "${entity.key}.${refField.key}"
    }

    /** Every REF whose model is NOT an entity of this registry — the ones that need a hydrated name. */
    private fun refsOutsideTheWorkshop(): List<RefSite> {
        val models = registry.stages.flatMap { it.entities }.map { it.name }.toSet()
        return registry.stages.flatMap { stage ->
            stage.entities.flatMap { entity ->
                entity.liveFields
                    .filter { DwFieldType.of(it.type) == DwFieldType.REF && it.refModel !in models }
                    .map { RefSite(stage, entity, it) }
            }
        }
    }

    @Test
    fun `exactly the seven models named in the header live outside this registry`() {
        // The header of [DwWorkshopSearch] names these six as the records the rest of the repository
        // owns and this device cannot resolve offline. A seventh appearing is not a bug in itself — it
        // is a decision nobody has made yet about which box holds ITS hydrated name, and it must not
        // be made by a `removeSuffix` quietly finding something plausible.
        //
        // `Questionnaire` arrived on 2026-09-03 (the stage-7 instrument link, spec §22) and went
        // through exactly this gate: its hydrated name lives in `questionnaireName`, declared in
        // the table above in the same change that widened this list.
        //
        // `QuestionnaireInterview` arrived on 2026-08-24 and this assertion is what stopped it being
        // waved through: its label is a TITLE, so every `…Name` rule missed it and the sitting a
        // designer cited would have been absent from their own phone's index while the web had it.
        assertEquals(
            listOf("Artisan", "Craft", "Process", "ProductDocumentation", "Questionnaire", "QuestionnaireInterview", "ToolDocumentation"),
            refsOutsideTheWorkshop().map { it.refField.refModel }.distinct().sorted(),
        )
    }

    @Test
    fun `every REF outside the workshop is labelled from the same box the web labels it from`() {
        val sites = refsOutsideTheWorkshop()
        assertEquals("the web's table covers every one of them", webHydration.keys.sorted(), sites.map { it.pair }.sorted())

        for (site in sites) {
            val webKey = webHydration.getValue(site.pair)
            // The keys this client's derivation will try, IN ORDER. Every one of them that the entity
            // actually declares as a live text box gets a DIFFERENT word written into it, so that a
            // hit for the web's word is proof the same box was read and not merely that some box was.
            //
            // RESTATED HERE ON PURPOSE, rather than imported from `DwWorkshopSearch`: a mirror that
            // called the production function would agree with any rule it grew, including a wrong
            // one, and the whole job of this test is to hold the derivation against the web's table.
            // So when the `documented…Name` rule was added on 2026-08-16 this line had to be edited
            // by hand — which is the review step, not an oversight in the design. It was edited by
            // hand again on 2026-08-24 for the `Title` and `documentedFor` rules, for the same reason.
            val stem = site.refField.key.removeSuffix("Ref")
            // `documentedFor` is deliberately NOT offered to every ref: it is reached only for a
            // cascade parent, read off `refFilterBy` exactly as the production rule reads it. Mirror
            // the condition too, or this test would pass a rule that relabels `processStep.processRef`.
            val cascadeParent = site.entity.liveFields.any { it.refFilterBy == site.refField.key }
            val candidates = buildList {
                add(stem + "Name")
                add("documented" + stem.replaceFirstChar { it.uppercase() } + "Name")
                add(stem + "Title")
                if (cascadeParent) add("documentedFor")
                add("name")
            }
            assertTrue("${site.pair}: the web's box is not one this client would ever try", webKey in candidates)

            val values = HashMap<String, JsonElement>()
            // A cuid that names no row of this draft, so the label cannot come from the workshop
            // itself and must come from the name the picker hydrated onto the row.
            values[site.refField.key] = JsonPrimitive("cmabsent0000000000000000001")
            for (key in candidates.distinct()) {
                val target = site.entity.field(key)?.takeIf { !it.deprecated } ?: continue
                val type = DwFieldType.of(target.type)
                if (type != DwFieldType.TEXT && type != DwFieldType.LONG_TEXT) continue
                values[key] = JsonPrimitive(if (key == webKey) "webhydratedname" else "wronghydratedname")
            }

            val singleton = site.entity.cardinality == "SINGLETON"
            val draft = WorkshopDraft(
                workshopId = "dwlocal-registry",
                stages = mapOf(
                    site.stage.key to StageDraft(
                        stageId = site.stage.key,
                        values = if (singleton) values else emptyMap(),
                        rows = if (singleton) {
                            emptyList()
                        } else {
                            listOf(DraftRow(id = dwRowId(site.entity.key, "row-1"), values = values))
                        },
                    )
                ),
            )

            val index = DwWorkshopSearch.buildIndex(registry, draft)
            val onWeb = DwWorkshopSearch.search(index, "webhydratedname").hits
                .firstOrNull { it.fieldKey == site.refField.key }
            assertNotNull(
                "${site.pair} must resolve through `$webKey`, the box the web reads it out of",
                onWeb,
            )
            assertEquals(
                "${site.pair} must not resolve through any other candidate key",
                0,
                DwWorkshopSearch.search(index, "wronghydratedname").hits
                    .count { it.fieldKey == site.refField.key },
            )
        }
    }

    /**
     * The one place this port is narrower than the web, measured rather than asserted in prose.
     *
     * [StageDraft] holds ONE flat `values` map per stage, so only the stage's first SINGLETON entity
     * can be indexed — see [DwWorkshopSearch.buildIndex]. That costs nothing while every stage
     * declares at most one, and it costs a whole entity's worth of answers the day one declares two,
     * with no symptom except a search that quietly never finds them. The draft store is what has to
     * change first, so the failure belongs here and not in a courtyard.
     */
    @Test
    fun `no stage declares a second singleton, which is the only thing this index cannot reach`() {
        val doubled = registry.stages
            .map { stage -> stage.key to stage.entities.filter { it.cardinality == "SINGLETON" }.map { it.key } }
            .filter { it.second.size > 1 }
        assertEquals(emptyList<Pair<String, List<String>>>(), doubled)
    }

    /**
     * Every entity of the SHIPPED registry can actually be searched — not three of them in a fixture.
     *
     * The claim on the screen is "searches every written answer on this device", and a hand-built
     * three-stage fixture cannot say whether the forty-third entity is reachable. The shapes that
     * differ are real ones: a stage whose entities are all collections, a collection nested under
     * another through [EntityDto.parent], a singleton sharing a stage with two lists. Each is given
     * one word and asked for it back, one draft at a time, so a failure names the entity rather than
     * reporting a count that is off by something.
     *
     * THE PROBE IS [DwFieldType.isFreeText], WHICH INCLUDES RICH_TEXT, and that is not tidiness:
     * five of these forty-three entities declare no plain text box at all, so their only prose is a
     * narrative stored as an object rather than a string. Probing TEXT and LONG_TEXT alone would skip
     * exactly the five entities a search is easiest to leave out of — the same omission
     * [dwCardRowTitle] warns about for row titles — and would have left this test asserting nothing
     * about them while still passing.
     */
    @Test
    fun `every entity of the shipped registry can be searched`() {
        val unreachable = ArrayList<String>()
        var exercised = 0
        for (stage in registry.stages) {
            for (entity in stage.entities) {
                val probe = entity.liveFields.firstOrNull { DwFieldType.of(it.type).isFreeText }
                    ?: continue
                exercised += 1
                // One shape answers for all three types: a bare string IS a legitimate RICH_TEXT
                // value, which `fromJson` reads as a single paragraph rather than discarding.
                val values = mapOf<String, JsonElement>(probe.key to JsonPrimitive("koraput"))
                val singleton = entity.cardinality == "SINGLETON"
                val draft = WorkshopDraft(
                    workshopId = "dwlocal-registry",
                    stages = mapOf(
                        stage.key to StageDraft(
                            stageId = stage.key,
                            values = if (singleton) values else emptyMap(),
                            rows = if (singleton) {
                                emptyList()
                            } else {
                                listOf(DraftRow(id = dwRowId(entity.key, "row-1"), values = values))
                            },
                        )
                    ),
                )
                val hits = DwWorkshopSearch
                    .search(DwWorkshopSearch.buildIndex(registry, draft), "koraput").hits
                if (hits.none { it.entityKey == entity.key && it.fieldKey == probe.key }) {
                    unreachable.add("${stage.key}.${entity.key}.${probe.key}")
                }
            }
        }
        assertEquals(emptyList<String>(), unreachable)
        // EVERY entity, counted off the registry rather than written down as a number somebody would
        // have to keep true. An entity holding no free-text field at all would be one the search can
        // never reach, and it must fail here — not be quietly skipped by the `?: continue` above,
        // which is how this test would otherwise degrade into one that exercises less and less.
        assertEquals(registry.stages.sumOf { it.entities.size }, exercised)
    }
}
