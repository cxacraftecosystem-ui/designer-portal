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

/**
 * WHAT A PICKED RECORD CARRIES ONTO A ROW — checked against the REGISTRY THIS APK SHIPS, not a
 * fixture, because the fixture is not what a designer in a courtyard is filling in.
 *
 * ── THE REQUIREMENT THIS FILE EXISTS TO MAKE CHECKABLE ────────────────────────────────────────
 *
 * "It is okay to have more fields over there, but not less." A designer who picks an artisan, a
 * product, a process, a tool or a craft into a workshop stage must get EVERY field that record
 * carries, and the same set of them whichever surface they are standing at. `DwReferenceHydration‐
 * Test` is the spec — hand-built entities, so a failure there names a RULE. This file is the other
 * half: it reads `app/src/main/assets/design-workshop-schema.json` and asks what the shipped
 * registry actually carries, so a failure here names a STAGE somebody edited.
 *
 * ── HOW THE HANDSET LEARNS THE MAPPING, WHICH IS THE FACT THE REST OF THIS FILE RESTS ON ──────
 *
 * IT DOES NOT KEEP ONE. There is no Kotlin copy of `REFERENCE_HYDRATION` anywhere in this app — the
 * server publishes it per field as [FieldDto.refHydration] (`field_to_dict`), and the picker's
 * records arrive with a `data` map built by `REFERENCE_MODELS[...].data`. BOTH halves of the carry
 * therefore come off the wire, so widening either table on the server reaches this phone with no
 * app change at all. The browser is the surface that keeps a second copy (`DW_REFERENCE_HYDRATION`
 * in `lib/designWorkshops.ts`, pinned by a backend test); this one deliberately does not, and
 * [DwReferenceField]'s KDoc records what deriving the mapping by matching key names cost when this
 * surface last tried to be clever: the artisan's name printed in the product column of a ministry
 * report.
 *
 * The one place the phone can fall behind is TIER 3 of its cache — the bundled asset, which is what
 * a handset handed to a field worker renders from until its first successful GET. That asset is a
 * dump of `registry_to_dict()` and the backend compares it to the live registry CONTENT-WISE
 * (`test_the_bundled_android_asset_is_the_registry_it_claims_to_be`), with the version digest now
 * covering the hydration mapping itself (`registry_version`, stage_schema.py). So a widened
 * mapping cannot ship with a stale asset silently — but nothing checked that the ASSET'S mapping is
 * one this client can actually apply, which is what the tests below do.
 *
 * ── THE FLOOR, AND WHY IT IS A FLOOR AND NOT AN EQUALITY ──────────────────────────────────────
 *
 * [FLOOR] is a transcription, and a transcription is a second declaration — normally the thing this
 * repository refuses. It is justified here by being an ASSERTION rather than a mechanism: nothing
 * in the app reads it, hydration would behave identically if it were deleted, and it is compared
 * with `⊇` rather than `==`. Adding a pair on the server needs no edit here; REMOVING one fails
 * this test by name. That is exactly the requirement — more is fine, less is the defect — and it
 * is the only shape of check that does not have to be re-approved every time the server widens.
 */
class DwReferenceCarryParityTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * A file of this repository, found by walking up from wherever the test runner started.
     *
     * The working directory of a Gradle test worker is not something to depend on, and a test that
     * SKIPPED when it could not find its data would prove nothing on the day somebody moves it.
     * Missing is a failure, loudly. Same idiom as `DwWorkshopSearchRegistryTest`.
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

    /** One REF picker of the shipped registry, and the entity whose row it fills in. */
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

    // ── The floor ────────────────────────────────────────────────────────────────────────────────

    /**
     * The `source key on the record → box on the row` pairs that MUST survive, per picker.
     *
     * Transcribed from the shipped asset on 2026-08-16 and cross-read against
     * `stage_schema.REFERENCE_HYDRATION`. Read it as the answer to "what does picking this record
     * fill in", which is the question the requirement is phrased in.
     *
     * A PICKER WITH NO ENTRY HERE IS DELIBERATE AND IS NOT AN OVERSIGHT. Fifteen of the registry's
     * twenty-three live REF fields point at `Dw…` models — entities of this very registry, whose
     * rows the designer is looking at on an earlier stage — and they carry a JOIN KEY rather than a
     * copy. Copying a prototype's twenty-nine fields onto the row that references it would put two
     * versions of the same answer in one workshop with nothing to say which was meant. The test
     * `every picker that hydrates is one this file knows about` is what keeps that list honest
     * rather than open-ended.
     *
     * 81 PAIRS ACROSS 8 PICKERS as of the widening of 2026-08-16, up from 27 across 7 — the artisan
     * picker went from 8 boxes to 22, the tool picker from 6 to 24, the product picker from 6 to 22,
     * and stage 5's `traditionalProcess` started hydrating at all.
     */
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
            // The tool's THIRD converted figure, carried since 2026-08-27, when
            // `ToolDocumentation.heightInches` was added. `heightAsRecorded` below is the OLD
            // unit-less `height` and a different column, so losing either is a real loss.
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
        // Added 2026-08-24 with the product->process cascade. One pair each, and both land in the same
        // box: `documentedFor` says which product the documented thing was documented FOR.
        "traditionalProcess.productRef" to mapOf("name" to "documentedFor"),
        "processStep.productRef" to mapOf("name" to "documentedFor"),
        // Added 2026-08-24 with the sixth reference model. Eleven pairs, every one of them a fact
        // ABOUT the sitting and not an answer GIVEN in it: no question text, no response, no
        // respondent name. The server's `REFERENCE_MODELS["QuestionnaireInterview"]` argues why.
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
    )

    @Test
    fun `no picker loses a pair the handset used to carry`() {
        // THE DEFECT THIS CATCHES IS A NARROWING, WHICH HAS NO OTHER SYMPTOM. A pair removed from
        // `REFERENCE_HYDRATION` — or an asset regenerated from a registry that lost one — leaves a
        // box the designer used to find filled in simply empty. Nothing errors, nothing logs, the
        // stage still saves, and the missing answer surfaces as a blank column in a .docx months
        // later. Reported per pair rather than as a map diff, so the failure names the box.
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
        // The floor is only evidence if it is complete. A NEW hydrating picker is not a failure of
        // the product — it is the widening working — but it must not slip in unlisted, because an
        // unlisted picker is one nobody has checked can be applied on this surface, and the next
        // narrowing of it would go unnoticed. Add it to FLOOR and to the census below.
        val unlisted = hydratingSites().map { it.pair }.filterNot { it in FLOOR }
        assertEquals("a picker started hydrating and was not added to FLOOR", emptyList<String>(), unlisted)
    }

    // ── Every incoming value must have somewhere to land ─────────────────────────────────────────

    @Test
    fun `every hydration target is a live box on the very entity being filled in`() {
        /*
         * THE SILENT-DROP CHECK, and the reason it is worth its own test is the shape of the drop.
         *
         * `hydratedValues` resolves a target through the entity's own live fields and simply
         * `return@forEach`s when it finds nothing. That is the right behaviour — a client must not
         * invent a box — but it means a pair declared on the server against a receiving field that
         * was never declared, or was declared on a DIFFERENT entity, or was deprecated, carries
         * nothing here and says nothing about it. On the server the same pair reaches
         * `coerce_value` and is stored, so the row saved from the browser holds a value the row
         * saved from the phone does not, from the same pick. That is the divergence this lane
         * exists to prevent, and the widening in progress is exactly when it would be introduced:
         * "add the pair" and "declare the box it lands in" are two edits in two files.
         *
         * `validate_registry` checks targets exist server-side. This checks the ASSET a phone with
         * no signal is running, which is not the same artefact and can be older.
         */
        val problems = mutableListOf<String>()
        for (site in hydratingSites()) {
            val live = site.entity.liveFields.associateBy { it.key }
            for ((source, target) in site.refField.refHydration) {
                if (target.startsWith("_")) continue   // the sentinel for "resolved, but not stored"
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

    /**
     * Boxes that TWO pickers on one entity both claim, and the reason each is tolerated.
     *
     * Read the value as "these two pickers write this box, and we have decided that is acceptable".
     * Anything not listed fails the test `two pickers may not claim one box unless it is a known one`.
     */
    private val KNOWN_SHARED_BOXES: Map<String, String> = mapOf(
        "existingProduct.artisanName" to
            "artisanRef maps name→artisanName and productRef maps artisanName→artisanName. " +
            "Defensible — a designer who picks only the documented product should still get the " +
            "maker's name — but see the test's note on what it costs on this surface.",
        // Both stage-5 process pickers, 2026-08-24. `productRef` maps name→documentedFor and
        // `processRef` maps productName→documentedFor, so the parent and the child both name the
        // product. WHICH ONE WINS WAS MEASURED, not assumed, and it is NOT the declaration order the
        // comments used to claim: re-pointing the product forces the process to be re-picked, so one
        // save carries two re-pointed refs, `hydrate_entries` clears and rewrites for both, and
        // `processRef` writes last. They agree whenever the pair is consistent. They do not when it
        // is stale — a process still belonging to the previously chosen product prints THAT product's
        // name — which is recorded as an open limit on the server side rather than fixed here.
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
        /*
         * WHY A SHARED BOX IS A DIVERGENCE AND NOT MERELY UNTIDY.
         *
         * `hydrate_entries` walks `item.entity.fields` IN DECLARATION ORDER and applies only-fill-
         * blanks, so on the server the FIRST picker declared always wins a shared box: on
         * `existingProduct`, `artisanRef` is declared before `productRef`, so the artisan's own name
         * is what lands in `artisanName` whatever order the designer picked in.
         *
         * ON THIS SURFACE THE ORDER IS THE DESIGNER'S, NOT THE REGISTRY'S. Each picker is its own
         * composable with its own `lastHydration` (`remember(field.key)` at DwReferenceField.kt),
         * and [hydrationPatch] only overwrites a box holding exactly what THAT picker last wrote.
         * So whichever picker the designer touches first fills the box, and the other is then
         * refused it for the life of the row — and re-pointing the picker that did NOT write it
         * cannot clear it either, because `current[key] != lastHydration[key]`. Pick the product
         * first and the row carries the product record's copy of the maker's name; pick the artisan
         * first and it carries the artisan record's. Those two spellings are the reason
         * `ProductDocumentation` has an `artisanName` column at all.
         *
         * THE FIX IS SERVER-SIDE IF ONE IS WANTED — drop the pair from `productRef`, or give the
         * product's copy its own box — so this test does not try to arbitrate here. What it does is
         * refuse to let a SECOND such box appear without somebody writing down why, because the
         * next one will not have a note explaining it.
         */
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
        // And the note must not outlive the collision it describes, or the next reader trusts it.
        assertEquals(
            "KNOWN_SHARED_BOXES names a collision that no longer exists",
            emptySet<String>(),
            KNOWN_SHARED_BOXES.keys - collisions.keys,
        )
    }

    // ── A fully documented record arrives whole ──────────────────────────────────────────────────

    /**
     * A legal value for [field], in the shape `REFERENCE_MODELS[...].data` would actually produce.
     *
     * DELIBERATELY THE SERVER'S SHAPES AND NOT THE PRETTIEST ONES: money arrives as the two-place
     * STRING `_money()` formats, an enum arrives as a bare token, a photograph arrives as a single
     * media id even where the box is a gallery. A fixture that handed each box a value already in
     * its stored form would pass while proving nothing about the coercion that has to happen.
     */
    /**
     * A VALUE THAT SATISFIES THE DECLARED `text_format`, WHERE THE FIELD DECLARES ONE.
     *
     * ── THIS IS THE `maxLength` NOTE BELOW, ONE DECLARATION LATER ─────────────────────────────
     *
     * The type arm below already cuts its string to `maxLength` for a stated reason: "a longer
     * fixture string would be refused by the length check and read here as 'the mapping did not
     * arrive' — a green-to-red flip with nothing wrong on either surface." `text_format` is the same
     * kind of declaration and needs the same treatment. `participant.aadhaarNumber` is a TEXT field
     * declaring `AADHAAR`, so the generic "Recorded answer" is now refused by
     * [com.designprototype.workshop.data.DwTextFormats] inside `coerce` — correctly, because a typed
     * string in the shape of nothing at all was exactly the value that used to be masked to
     * "XXXX XXXX swer" and printed as a national identity number in a ministry document — and the
     * assertion read that legitimate refusal as a missing mapping.
     *
     * THE AADHAAR NUMBER IS A REAL VERHOEFF-VALID ONE. A plausible-looking twelve digits would be
     * refused by the checksum arm, which is the same green-to-red flip one arm further in.
     *
     * WHAT THIS FIXTURE DOES *NOT* MODEL, and it is worth knowing while reading the assertion: in
     * production the value hydration copies into that box is the MASK (`mask_identity_number`), not
     * a number, and the format accepts a mask by shape precisely so that re-coercing a hydrated row
     * stays silent. Both are accepted; this generator uses the number because it is also the value a
     * designer types.
     */
    private fun plausibleForFormat(field: FieldDto): JsonElement? = when (field.format) {
        "AADHAAR" -> JsonPrimitive("234567890124")
        "PINCODE" -> JsonPrimitive("768029")
        "EMAIL" -> JsonPrimitive("cluster@dch.gov.in")
        "PHONE_IN" -> JsonPrimitive("+91 9876543210")
        "PEHCHAN" -> JsonPrimitive("PMV1234567")
        else -> null
    }

    private fun plausible(field: FieldDto): JsonElement = plausibleForFormat(field) ?: when (DwFieldType.of(field.type)) {
        // Inside the declared range as well as the declared type: `participant.experienceYears`
        // carries min 0 / max 90, and a fixture that ignored a bound would report a value dropped
        // for being out of range as a mapping that never arrived.
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
        // `{lat, lon}` and no `accuracy`, which is exactly the shape `_subject_point` returns: a
        // hand-dropped pin has no error bar, and the registry treats the key as optional so that
        // "somebody pointed at this" stays distinguishable from "a device measured this".
        DwFieldType.GEO -> buildJsonObject {
            put("lat", JsonPrimitive(21.2))
            put("lon", JsonPrimitive(83.6))
        }
        // Prose off a source column arrives as a plain string, never as a document — which is the
        // whole reason `coerceHydrated` has a RICH_TEXT arm.
        //
        // CUT TO `maxLength` WHERE ONE IS DECLARED. `participant.gender` allows twenty characters,
        // and a longer fixture string would be refused by the length check and read here as "the
        // mapping did not arrive" — a green-to-red flip with nothing wrong on either surface.
        else -> JsonPrimitive(
            "Recorded answer".let { if (field.maxLength > 0) it.take(field.maxLength) else it }
        )
    }

    @Test
    fun `a fully documented record arrives with every mapped box filled, on every picker`() {
        /*
         * THE "WITHOUT A MISS" TEST. Give each picker a record whose every mapped source key holds
         * a plausible value and assert the row comes back holding ALL of them. Run over the shipped
         * registry rather than a fixture, so a pair added on the server is covered the day the
         * asset is regenerated, with no edit here.
         *
         * It catches three separate things at once, all of which are silent in the product: a
         * target with no box (above), a value the target's type rejects, and a value this client
         * writes but `hydrationPatch` then refuses to put on the row.
         */
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

    // ── The coercion the server runs, run here too ───────────────────────────────────────────────

    /** Stage 6's existing product, straight out of the shipped registry. */
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
        /*
         * `hydrate_entries` says it out loud: "a value the target field cannot legally hold — a
         * product type that is not one of the workshop's categories — is dropped rather than
         * written". This surface used to write it, and then [DwValues.validate] marked the row red
         * with "…is not one of the options" — over a value the designer never typed and cannot
         * correct, because the dropdown does not offer that token either. The mark reads as "you
         * filled this in wrongly" and there is no way to act on it.
         *
         * `_PRODUCT_TYPE_TO_CATEGORY` maps a Prisma ProductType onto PRODUCT_CATEGORY and the map
         * is deliberately partial, so the widening of it is precisely where a token that is not a
         * category would arrive.
         */
        val entity = existingProduct()
        val carried = carry(entity, "productRef", mapOf(
            "name" to JsonPrimitive("Sambalpuri saree"),
            "category" to JsonPrimitive("RAW_MATERIAL"),   // a ProductType, never a PRODUCT_CATEGORY
        ))
        assertEquals(JsonPrimitive("Sambalpuri saree"), carried["name"])
        assertFalse(
            "a token no dropdown on this device can draw was stored: $carried",
            carried.containsKey("category"),
        )
        // And the row it produced is clean — no red mark on a box the designer never touched.
        val marks = DwValues.validate(entity, carried, enforceRequired = false)
        assertTrue("hydration left a mark the designer cannot act on: $marks", marks.isEmpty())
    }

    @Test
    fun `a category token the enum does contain still arrives`() {
        // The drop above must be the narrow rule and not a refusal of enums generally, or the fix
        // would itself be a narrowing — which is the very thing this file is here to prevent.
        val entity = existingProduct()
        val carried = carry(entity, "productRef", mapOf("category" to JsonPrimitive("PACKAGING")))
        assertEquals(JsonPrimitive("PACKAGING"), carried["category"])
    }

    @Test
    fun `prose bound for a rich-text box arrives as a document, not as a bare string`() {
        /*
         * `coerce_value`'s RICH_TEXT arm normalises through the rich-text model, so a plain string
         * is stored as `{"blocks":[…]}`. The phone builds its OWN report from the local draft, with
         * no signal and no server to correct it, so a bare string in a document-shaped box is a
         * paragraph missing from the copy handed to a visiting officer at the close of the
         * workshop. `problems` on stage 6 is RICH_TEXT and is a natural target for the widening —
         * `ProductDocumentation` and `Artisan` both carry prose columns nothing currently copies.
         */
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
        // `Artisan.experienceYears` is read out of `extraMetadata`, which is free-form, so "12
        // years" is a real answer to find in one. The server drops it; this surface wrote the
        // string into a field typed INT, where every later reader has to guess what to do with it.
        val participant = registry.stages.flatMap { it.entities }.first { it.key == "participant" }
        val years = participant.field("experienceYears")!!
        assertEquals("INT", years.type)
        assertNull(DwValues.coerceHydrated(years, JsonPrimitive("12 years")))
        assertEquals(JsonPrimitive(12L), DwValues.coerceHydrated(years, JsonPrimitive("12")))
    }

    @Test
    fun `a measurement is copied in the unit the server sent it in, never converted here`() {
        /*
         * `ProductDocumentation` stores measurements in INCHES and `existingProduct` declares its
         * boxes in CENTIMETRES, so a conversion has to happen — ON THE SERVER, inside
         * `REFERENCE_MODELS[...].data`, which is the one place both clients read the number from.
         * A conversion added on this side would be applied on top of it and the handset would
         * report a product 2.54 times the size the browser reports for the same record.
         *
         * Pinned against `lengthCm` whether or not it is mapped yet: the assertion is about what
         * this client does to a number on its way onto a row, and the answer must stay "nothing
         * but the registry's own coercion".
         */
        val entity = existingProduct()
        val lengthCm = entity.field("lengthCm")!!
        assertEquals("cm", lengthCm.unit)
        // 45.7 cm is 18 inches. If anything here ever "helpfully" converts, this reads 116.08.
        assertEquals(
            JsonPrimitive(45.7),
            DwValues.coerceHydrated(lengthCm, JsonPrimitive("45.7")),
        )
    }

    @Test
    fun `the artisan's stated pin arrives whole, and anything that is not a pin does not arrive`() {
        // `participant.subjectLocation` became a hydration target with the artisan widening, fed by
        // `_subject_point`, which returns `{lat, lon}` or nothing. The object is passed through
        // UNCHANGED — a client that re-rounded a coordinate would put the map pin somewhere the
        // browser does not — but a value that is not an object at all is refused, because a bare
        // string reaching the map picker draws nothing and offers no way to fix it.
        val participant = registry.stages.flatMap { it.entities }.first { it.key == "participant" }
        val pin = participant.field("subjectLocation")!!
        assertEquals("GEO", pin.type)

        val point = buildJsonObject { put("lat", JsonPrimitive(21.2)); put("lon", JsonPrimitive(83.6)) }
        assertEquals(point, DwValues.coerceHydrated(pin, point))
        assertNull(DwValues.coerceHydrated(pin, JsonPrimitive("21.2, 83.6")))
    }

    @Test
    fun `a single photograph bound for a gallery arrives as a list, from the shipped registry`() {
        // `hydrate_entries` wraps a scalar for a multi field. The synthetic twin of this lives in
        // DwReferenceHydrationTest; this one proves the SHIPPED registry really does point the
        // product's one photograph at a gallery, which is the half a fixture cannot say.
        val entity = existingProduct()
        val carried = carry(entity, "productRef", mapOf("photo" to JsonPrimitive("media-7")))
        assertEquals(
            JsonArray(listOf(JsonPrimitive("media-7"))),
            carried["productPhotos"],
        )
    }

    @Test
    fun `money off a reference record lands two-place, as it does on the server`() {
        // `_money()` already formats to two places, so this is a round-trip rather than a rounding
        // — but the arm has to be exercised, because MONEY is the one type where the phone and the
        // server disagreed for real once (Formatter rounds half away from zero, Python half to
        // even) and a hydrated amount feeds the stage-17 cost sheet a PRODUCT derivation reads.
        val entity = existingProduct()
        val carried = carry(entity, "productRef", mapOf("price" to JsonPrimitive("1200.5")))
        assertEquals(JsonPrimitive("1200.50"), carried["price"])
    }

    // ── The digest, and what a widening does to a phone that already holds a cache ───────────────

    @Test
    fun `the shipped asset declares a registry version, and the mapping is inside it`() {
        /*
         * WHAT A WIDENED REGISTRY COSTS A HANDSET, ANSWERED HONESTLY RATHER THAN ASSUMED.
         *
         * `registry_version()` digests key, type, tier, required, enum NAME, deprecated, derivation
         * AND the hydration mapping — the last one added for exactly this feature, so that
         * correcting a wrong mapping (which touches no key, type or tier) still moves the version
         * and still forces the bundled asset to be re-dumped. So the widening in progress WILL move
         * the digest, and that is the honest outcome rather than a cost.
         *
         * What it does NOT do is invalidate anything on this phone. [StageSchemaStore.store]'s "the
         * version moved" return value is discarded by its only caller, [WorkshopDraft] carries no
         * registry version to be recognised against, and [StageDraft.values] is a `JsonElement` map
         * so a key this build does not know survives a round trip rather than being dropped. The
         * measured result (SM-M325F, 2026-08-13, recorded in [StageSchemaStore]'s KDoc) is that a
         * digest change rewrites the cache file and leaves the draft byte-for-byte identical.
         *
         * This test asserts only the two things a client can check for itself: the asset declares a
         * version at all, and the mapping it carries is not empty — i.e. the dump really did
         * include `refHydration`. A dump that silently stopped emitting it would leave every picker
         * on a never-connected handset hydrating nothing, with the version still matching, which is
         * the exact shape of the derived-fields incident this digest was widened for.
         */
        assertTrue("the bundled registry declares no version", registry.version.isNotBlank())
        assertEquals("registry_version() is a 16-character sha256 prefix", 16, registry.version.length)
        assertTrue(
            "the bundled asset carries no hydration mapping at all — was it dumped without refHydration?",
            hydratingSites().isNotEmpty(),
        )
        // The count is a floor, not an equality, for the same reason FLOOR is: the widening adds.
        assertTrue(
            "the shipped registry hydrates fewer pickers than this app has ever shipped with",
            hydratingSites().size >= FLOOR.size,
        )
    }

    @Test
    fun `a reference list cached before a widening hydrates what it has and clears nothing`() {
        /*
         * THE CACHE THIS PHONE ALREADY HOLDS IS THE ONE THING THE SERVER'S WIDENING CANNOT REACH.
         *
         * [DwReferenceStore] has no expiry and no version stamp, deliberately — "a stale artisan
         * list is worth immeasurably more than no artisan list". So a handset that fetched the
         * artisan register a fortnight ago holds options whose `data` predates every key the
         * widening adds, and it will go on serving them until it next sees a tower.
         *
         * That has to be a PARTIAL fill and never a wrong one: the keys the old payload has arrive,
         * the keys it lacks leave their boxes blank for the designer, and nothing already on the row
         * is cleared on the strength of a key the record simply could not have carried.
         */
        val participant = registry.stages.flatMap { it.entities }.first { it.key == "participant" }
        val writable = boxesOf(participant)
        val mapping = writable.getValue("artisanRef").refHydration

        // An option from before the widening: it carries the two oldest keys and nothing else.
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
