package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DwWorkshopSearchRegistryTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

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

    private val webHydration = mapOf(
        "workshopSetup.craftRef" to "craftName",
        "participant.artisanRef" to "name",
        "tool.toolRef" to "name",
        "processStep.processRef" to "name",
        "existingProduct.artisanRef" to "artisanName",
        "existingProduct.productRef" to "name",
        "prototype.productRef" to "productName",
        "traditionalProcess.processRef" to "documentedProcessName",
        "artisanBaseline.interviewRef" to "interviewTitle",
        "surveyPlan.questionnaireRef" to "questionnaireName",
        "traditionalProcess.productRef" to "documentedFor",
        "processStep.productRef" to "documentedFor",
    )

    private data class RefSite(val stage: StageDto, val entity: EntityDto, val refField: FieldDto) {
        val pair: String get() = "${entity.key}.${refField.key}"
    }

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
            val stem = site.refField.key.removeSuffix("Ref")
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

    @Test
    fun `no stage declares a second singleton, which is the only thing this index cannot reach`() {
        val doubled = registry.stages
            .map { stage -> stage.key to stage.entities.filter { it.cardinality == "SINGLETON" }.map { it.key } }
            .filter { it.second.size > 1 }
        assertEquals(emptyList<Pair<String, List<String>>>(), doubled)
    }

    @Test
    fun `every entity of the shipped registry can be searched`() {
        val unreachable = ArrayList<String>()
        var exercised = 0
        for (stage in registry.stages) {
            for (entity in stage.entities) {
                val probe = entity.liveFields.firstOrNull { DwFieldType.of(it.type).isFreeText }
                    ?: continue
                exercised += 1
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
        val unwritten = registry.stages.sumOf { stage ->
            stage.entities.count { entity ->
                entity.liveFields.all { it.reportRole == "HIDDEN" && !DwFieldType.of(it.type).isFreeText }
            }
        }
        assertEquals(1, unwritten)
        assertEquals(registry.stages.sumOf { it.entities.size } - unwritten, exercised)
    }
}
