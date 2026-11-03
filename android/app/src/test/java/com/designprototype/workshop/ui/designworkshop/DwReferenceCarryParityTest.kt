package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.DwReferenceOption
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.field
import com.designprototype.workshop.data.liveFields
import com.designprototype.workshop.report.toPlain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DwReferenceCarryParityTest {

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

    private data class RefSite(val stage: StageDto, val entity: EntityDto, val refField: FieldDto) {
        val pair: String get() = "${entity.key}.${refField.key}"
    }

    private fun refSites(): List<RefSite> = registry.stages.flatMap { stage ->
        stage.entities.flatMap { entity ->
            entity.liveFields
                .filter { DwFieldType.of(it.type) == DwFieldType.REF }
                .map { RefSite(stage, entity, it) }
        }
    }

    private fun hydratingSites(): List<RefSite> = refSites().filter { it.refField.refHydration.isNotEmpty() }

    private val FLOOR: Map<String, Map<String, String>> = mapOf(
        "workshopSetup.craftRef" to mapOf(
            "craftName" to "craftName",
            "craftLocalName" to "craftLocalName",
        ),
        "participant.artisanRef" to mapOf(
            "name" to "name",
            "localName" to "localName",
            "specialisation" to "specialisation",
            "experienceYears" to "experienceYears",
            "age" to "age",
            "gender" to "gender",
            "phone" to "phone",
            "email" to "email",
            "pehchanCardAvailable" to "pehchanCardAvailable",
            "pehchanCardNumber" to "artisanCardNo",
            "village" to "village",
            "state" to "state",
            "district" to "district",
            "pincode" to "pincode",
            "address" to "address",
            "subjectLocation" to "subjectLocation",
            "notes" to "recordNotes",
            "dos" to "dos",
            "donts" to "donts",
            "documentedOn" to "documentedOn",
            "photo" to "photo",
            "photoCaption" to "photoCaption",
        ),
        "traditionalProcess.processRef" to mapOf(
            "name" to "documentedProcessName",
            "notes" to "documentedProcessNotes",
            "productName" to "documentedFor",
            "steps" to "documentedSteps",
            "preProcessAvailable" to "preProcessAvailable",
            "documentedOn" to "documentedOn",
        ),
        "processStep.processRef" to mapOf(
            "name" to "name",
            "notes" to "description",
            "productName" to "documentedFor",
        ),
        "tool.toolRef" to mapOf(
            "name" to "name",
            "localName" to "localName",
            "englishName" to "englishName",
            "material" to "material",
            "usedFor" to "usedFor",
            "cost" to "cost",
            "yearsInUse" to "yearsInUse",
            "maker" to "maker",
            "traditionType" to "traditionType",
            "craftName" to "craftName",
            "place" to "place",
            "artisanName" to "artisanName",
            "improvements" to "improvements",
            "remarks" to "remarks",
            "lengthCm" to "lengthCm",
            "breadthCm" to "breadthCm",
            "heightCm" to "heightCm",
            "heightAsRecorded" to "heightAsRecorded",
            "widthAsRecorded" to "widthAsRecorded",
            "thicknessAsRecorded" to "thicknessAsRecorded",
            "weightAsRecorded" to "weightAsRecorded",
            "radiusAsRecorded" to "radiusAsRecorded",
            "documentedOn" to "documentedOn",
            "photo" to "photo",
            "photoCaption" to "photoCaption",
        ),
        "existingProduct.artisanRef" to mapOf("name" to "artisanName"),
        "existingProduct.productRef" to mapOf(
            "name" to "name",
            "localName" to "localName",
            "category" to "category",
            "recordType" to "recordType",
            "material" to "material",
            "mainToolsUsed" to "mainToolsUsed",
            "price" to "price",
            "costOfMaking" to "costOfMaking",
            "marketDemand" to "marketDemand",
            "use" to "use",
            "craftName" to "craftName",
            "place" to "place",
            "artisanName" to "artisanName",
            "lengthCm" to "lengthCm",
            "widthCm" to "widthCm",
            "heightCm" to "heightCm",
            "dimensionsNote" to "dimensionsNote",
            "productionTimeNote" to "productionTimeNote",
            "remarks" to "remarks",
            "documentedOn" to "documentedOn",
            "photo" to "productPhotos",
            "photoCaption" to "productPhotosCaption",
        ),
        "prototype.productRef" to mapOf("name" to "productName"),
        "traditionalProcess.productRef" to mapOf("name" to "documentedFor"),
        "processStep.productRef" to mapOf("name" to "documentedFor"),
        "artisanBaseline.interviewRef" to mapOf(
            "interviewTitle" to "interviewTitle",
            "interviewDate" to "interviewDate",
            "interviewPlace" to "interviewPlace",
            "interviewLanguage" to "interviewLanguage",
            "interviewArtisanCount" to "interviewArtisanCount",
            "interviewSectionsCovered" to "interviewSectionsCovered",
            "interviewQuestionsAnswered" to "interviewQuestionsAnswered",
            "interviewLastAnsweredOn" to "interviewLastAnsweredOn",
            "interviewMediaNote" to "interviewMediaNote",
            "interviewDocumentedOn" to "interviewDocumentedOn",
            "interviewDocumentedAtWorkshop" to "interviewDocumentedAtWorkshop",
        ),
        "surveyPlan.questionnaireRef" to mapOf(
            "name" to "questionnaireName",
        ),
        "prototypeValidation.prototypeRef" to mapOf(
            "lengthCm" to "finalLengthCm",
            "widthCm" to "finalWidthCm",
            "heightCm" to "finalHeightCm",
        ),
        "finalProduct.prototypeRef" to mapOf(
            "name" to "name",
            "materials" to "materials",
            "lengthCm" to "lengthCm",
            "widthCm" to "widthCm",
            "heightCm" to "heightCm",
            "weightG" to "weightG",
            "dimensionsNote" to "dimensionsNote",
            "makingTimeDays" to "makingTimeDays",
            "processSummary" to "makingProcess",
        ),
    )

    @Test
    fun `no picker loses a pair the handset used to carry`() {
        val shipped = hydratingSites().associate { it.pair to it.refField.refHydration }
        val lost = mutableListOf<String>()
        for ((pair, mapping) in FLOOR) {
            val actual = shipped[pair]
            if (actual == null) {
                lost += "$pair: the picker no longer hydrates at all"
                continue
            }
            for ((source, target) in mapping) {
                when (actual[source]) {
                    target -> Unit
                    null -> lost += "$pair: '$source' no longer reaches any box (was '$target')"
                    else -> lost += "$pair: '$source' now lands on '${actual[source]}', not '$target'"
                }
            }
        }
        assertEquals("the carry has NARROWED — more is fine, less is the defect", emptyList<String>(), lost)
    }

    @Test
    fun `every picker that hydrates is one this file knows about`() {
        val unlisted = hydratingSites().map { it.pair }.filterNot { it in FLOOR }
        assertEquals("a picker started hydrating and was not added to FLOOR", emptyList<String>(), unlisted)
    }

    @Test
    fun `every hydration target is a live box on the very entity being filled in`() {
        val problems = mutableListOf<String>()
        for (site in hydratingSites()) {
            val live = site.entity.liveFields.associateBy { it.key }
            for ((source, target) in site.refField.refHydration) {
                if (target.startsWith("_")) continue
                val box = site.entity.field(target)
                when {
                    box == null ->
                        problems += "${site.pair}: '$source' is mapped to '$target', which is not a field of ${site.entity.key}"
                    box.deprecated ->
                        problems += "${site.pair}: '$source' is mapped to '$target', which is deprecated and takes no input"
                    target !in live ->
                        problems += "${site.pair}: '$source' is mapped to '$target', which this entity does not offer"
                }
            }
        }
        assertEquals("a mapped value has nowhere to land on this surface", emptyList<String>(), problems)
    }

    private val KNOWN_SHARED_BOXES: Map<String, String> = mapOf(
        "existingProduct.artisanName" to
            "artisanRef maps name→artisanName and productRef maps artisanName→artisanName. " +
            "Defensible — a designer who picks only the documented product should still get the " +
            "maker's name — but see the test's note on what it costs on this surface.",
        "traditionalProcess.documentedFor" to
            "productRef maps name→documentedFor and processRef maps productName→documentedFor. " +
            "processRef writes last and wins; consistent pairs agree, a stale pair prints the " +
            "process's own parent.",
        "processStep.documentedFor" to
            "The same collision as traditionalProcess.documentedFor, on the collection rather than " +
            "the singleton, with the same winner and the same stale-pair limit."
    )

    @Test
    fun `two pickers may not claim one box unless it is a known one`() {
        val collisions = mutableMapOf<String, List<String>>()
        for (stage in registry.stages) {
            for (entity in stage.entities) {
                val claims = mutableMapOf<String, MutableList<String>>()
                for (site in entity.liveFields.filter { DwFieldType.of(it.type) == DwFieldType.REF }) {
                    for (target in site.refHydration.values) {
                        claims.getOrPut(target) { mutableListOf() }.add(site.key)
                    }
                }
                for ((target, pickers) in claims) {
                    if (pickers.size > 1) collisions["${entity.key}.$target"] = pickers
                }
            }
        }
        val unexplained = collisions.keys.filterNot { it in KNOWN_SHARED_BOXES }
        assertEquals(
            "two pickers now fill one box and nobody has said which should win: $collisions",
            emptyList<String>(),
            unexplained,
        )
        assertEquals(
            "KNOWN_SHARED_BOXES names a collision that no longer exists",
            emptySet<String>(),
            KNOWN_SHARED_BOXES.keys - collisions.keys,
        )
    }

    private fun plausibleForFormat(field: FieldDto): JsonElement? = when (field.format) {
        "AADHAAR" -> JsonPrimitive("234567890124")
        "PINCODE" -> JsonPrimitive("768029")
        "EMAIL" -> JsonPrimitive("cluster@dch.gov.in")
        "PHONE_IN" -> JsonPrimitive("+91 9876543210")
        "PEHCHAN" -> JsonPrimitive("PMV1234567")
        else -> null
    }

    private fun plausible(field: FieldDto): JsonElement = plausibleForFormat(field) ?: when (DwFieldType.of(field.type)) {
        DwFieldType.INT -> JsonPrimitive("12")
        DwFieldType.DECIMAL, DwFieldType.PERCENT -> JsonPrimitive("1.5")
        DwFieldType.MONEY -> JsonPrimitive("1200.00")
        DwFieldType.BOOL -> JsonPrimitive("true")
        DwFieldType.DATE -> JsonPrimitive("2026-03-12")
        DwFieldType.TIME -> JsonPrimitive("09:30")
        DwFieldType.ENUM, DwFieldType.MULTI_ENUM ->
            JsonPrimitive(field.options.firstOrNull()?.value ?: "OTHER")
        DwFieldType.EMAIL -> JsonPrimitive("cluster@dch.gov.in")
        DwFieldType.URL -> JsonPrimitive("dch.gov.in")
        DwFieldType.PHONE -> JsonPrimitive("+91 98765 43210")
        DwFieldType.IMAGE, DwFieldType.IMAGE_LIST, DwFieldType.FILE,
        DwFieldType.AUDIO, DwFieldType.VIDEO -> JsonPrimitive("media-7")
        DwFieldType.GEO -> buildJsonObject {
            put("lat", JsonPrimitive(21.2))
            put("lon", JsonPrimitive(83.6))
        }
        else -> JsonPrimitive(
            "Recorded answer".let { if (field.maxLength > 0) it.take(field.maxLength) else it }
        )
    }

    @Test
    fun `a fully documented record arrives with every mapped box filled, on every picker`() {
        for (site in hydratingSites()) {
            val writable = site.entity.liveFields.associateBy { it.key }
            val mapping = site.refField.refHydration
            val data = buildJsonObject {
                for ((source, target) in mapping) {
                    val box = writable[target] ?: continue
                    put(source, plausible(box))
                }
            }
            val option = DwReferenceOption(id = "rec-1", label = "A documented record", data = data)
            val patch = hydrationPatch(
                hydratedValues(option, mapping, writable),
                lastHydration = emptyMap(),
                current = emptyMap(),
                writable = writable,
            )
            for ((source, target) in mapping) {
                if (target.startsWith("_") || target !in writable) continue
                assertNotNull(
                    "${site.pair}: a fully documented record left '$target' empty (from '$source')",
                    patch[target],
                )
            }
        }
    }

    private fun existingProduct(): EntityDto = registry.stages
        .flatMap { it.entities }
        .first { it.key == "existingProduct" }

    private fun boxesOf(entity: EntityDto) = entity.liveFields.associateBy { it.key }

    private fun carry(entity: EntityDto, refKey: String, data: Map<String, JsonElement>): Map<String, JsonElement> {
        val writable = boxesOf(entity)
        val mapping = writable.getValue(refKey).refHydration
        return hydratedValues(
            DwReferenceOption(id = "rec-1", data = JsonObject(data)),
            mapping,
            writable,
        )
    }

    @Test
    fun `a category token this build's enum list does not contain is dropped, not written`() {
        val entity = existingProduct()
        val carried = carry(entity, "productRef", mapOf(
            "name" to JsonPrimitive("Sambalpuri saree"),
            "category" to JsonPrimitive("RAW_MATERIAL"),
        ))
        assertEquals(JsonPrimitive("Sambalpuri saree"), carried["name"])
        assertFalse(
            "a token no dropdown on this device can draw was stored: $carried",
            carried.containsKey("category"),
        )
        val marks = DwValues.validate(entity, carried, enforceRequired = false)
        assertTrue("hydration left a mark the designer cannot act on: $marks", marks.isEmpty())
    }

    @Test
    fun `a category token the enum does contain still arrives`() {
        val entity = existingProduct()
        val carried = carry(entity, "productRef", mapOf("category" to JsonPrimitive("PACKAGING")))
        assertEquals(JsonPrimitive("PACKAGING"), carried["category"])
    }

    @Test
    fun `prose bound for a rich-text box arrives as a document, not as a bare string`() {
        val entity = existingProduct()
        val problems = entity.field("problems")
        assertEquals("this test is pinned to a RICH_TEXT box", "RICH_TEXT", problems?.type)

        val stored = DwValues.coerceHydrated(problems!!, JsonPrimitive("The warp snaps in the dry months."))
        assertTrue("a bare string reached a RICH_TEXT box: $stored", stored is JsonObject)
        assertTrue("the document has no blocks: $stored", (stored as JsonObject).containsKey("blocks"))
        assertEquals("The warp snaps in the dry months.", toPlain(stored))
    }

    @Test
    fun `a free-metadata answer the target's type cannot hold is dropped rather than stored`() {
        val participant = registry.stages.flatMap { it.entities }.first { it.key == "participant" }
        val years = participant.field("experienceYears")!!
        assertEquals("INT", years.type)
        assertNull(DwValues.coerceHydrated(years, JsonPrimitive("12 years")))
        assertEquals(JsonPrimitive(12L), DwValues.coerceHydrated(years, JsonPrimitive("12")))
    }

    @Test
    fun `a measurement is copied in the unit the server sent it in, never converted here`() {
        val entity = existingProduct()
        val lengthCm = entity.field("lengthCm")!!
        assertEquals("cm", lengthCm.unit)
        assertEquals(
            JsonPrimitive(45.7),
            DwValues.coerceHydrated(lengthCm, JsonPrimitive("45.7")),
        )
    }

    @Test
    fun `the artisan's stated pin arrives whole, and anything that is not a pin does not arrive`() {
        val participant = registry.stages.flatMap { it.entities }.first { it.key == "participant" }
        val pin = participant.field("subjectLocation")!!
        assertEquals("GEO", pin.type)

        val point = buildJsonObject { put("lat", JsonPrimitive(21.2)); put("lon", JsonPrimitive(83.6)) }
        assertEquals(point, DwValues.coerceHydrated(pin, point))
        assertNull(DwValues.coerceHydrated(pin, JsonPrimitive("21.2, 83.6")))
    }

    @Test
    fun `a single photograph bound for a gallery arrives as a list, from the shipped registry`() {
        val entity = existingProduct()
        val carried = carry(entity, "productRef", mapOf("photo" to JsonPrimitive("media-7")))
        assertEquals(
            JsonArray(listOf(JsonPrimitive("media-7"))),
            carried["productPhotos"],
        )
    }

    @Test
    fun `money off a reference record lands two-place, as it does on the server`() {
        val entity = existingProduct()
        val carried = carry(entity, "productRef", mapOf("price" to JsonPrimitive("1200.5")))
        assertEquals(JsonPrimitive("1200.50"), carried["price"])
    }

    @Test
    fun `the shipped asset declares a registry version, and the mapping is inside it`() {
        assertTrue("the bundled registry declares no version", registry.version.isNotBlank())
        assertEquals("registry_version() is a 16-character sha256 prefix", 16, registry.version.length)
        assertTrue(
            "the bundled asset carries no hydration mapping at all — was it dumped without refHydration?",
            hydratingSites().isNotEmpty(),
        )
        assertTrue(
            "the shipped registry hydrates fewer pickers than this app has ever shipped with",
            hydratingSites().size >= FLOOR.size,
        )
    }

    @Test
    fun `a reference list cached before a widening hydrates what it has and clears nothing`() {
        val participant = registry.stages.flatMap { it.entities }.first { it.key == "participant" }
        val writable = boxesOf(participant)
        val mapping = writable.getValue("artisanRef").refHydration

        val old = DwReferenceOption(
            id = "a1",
            data = buildJsonObject {
                put("name", JsonPrimitive("Latha Devi"))
                put("village", JsonPrimitive("Barpali"))
            },
        )
        val typedByHand = mapOf<String, JsonElement>("phone" to JsonPrimitive("+91 98765 43210"))
        val patch = hydrationPatch(
            hydratedValues(old, mapping, writable),
            lastHydration = emptyMap(),
            current = typedByHand,
            writable = writable,
        )

        assertEquals(JsonPrimitive("Latha Devi"), patch["name"])
        assertEquals(JsonPrimitive("Barpali"), patch["village"])
        assertFalse(
            "a key the cached record could not have carried was written anyway: $patch",
            patch.containsKey("specialisation"),
        )
        assertFalse(
            "a number the designer typed in the room was cleared by a stale cached record: $patch",
            patch.containsKey("phone"),
        )
    }
}
