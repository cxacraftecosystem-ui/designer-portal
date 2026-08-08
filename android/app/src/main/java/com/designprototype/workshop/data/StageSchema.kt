package com.designprototype.workshop.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * The field registry, on the phone: the wire shape of `GET /api/design-workshops/schema`, a durable
 * three-tier cache of it, and the small amount of arithmetic that has to agree with the server's
 * `stage_schema.py` line for line.
 *
 * WHY THERE IS NO PER-STAGE FORM CODE ANYWHERE IN THIS APP. The requirements document defines 22
 * stages holding 496 typed fields across 43 entities, and the tiers within them move between studies.
 * Hand-writing 22 forms means every registry edit is an app release, and — worse — it means the phone,
 * the web form, the validator and the report writer each carry their own opinion about what stage 14
 * contains. They would drift, and the first anyone would notice is a ministry report with an empty
 * column. So the registry is DATA: this file decodes it, [FieldRenderer] dispatches on
 * [FieldDto.type], and a field added on the server appears on both clients with no client change.
 *
 * ── THE THREE-TIER CACHE, AND WHY ALL THREE TIERS EXIST ──────────────────────────────────────────
 *
 *  1. `filesDir/design-workshop-schema.json` — what the last successful fetch wrote. filesDir and NOT
 *     cacheDir, for the same reason [WorkshopDraftStore] gives: Android reclaims cacheDir under
 *     storage pressure, silently, without a callback, and preferentially on the 32 GB field handset
 *     two weeks into a study. A designer who loses the cached registry in a courtyard loses the FORM,
 *     not merely a cache, and has nothing to fill in until they next see a tower.
 *  2. `assets/design-workshop-schema.json` — the copy built into the APK, dumped from the very
 *     `registry_to_dict()` the API serves (see the build note below). This is the tier that answers
 *     "a phone that has never had signal since install still has a form to fill". Without it the app
 *     is unusable until its first successful GET, which on a handset handed to a field worker at the
 *     start of a trip is a guarantee nobody can make.
 *  3. The network. Fetched whenever there IS a connection, and its payload replaces tier 1.
 *
 * A fetch failure NEVER clears tiers 1 or 2 and never propagates: an app that refuses to draw a form
 * because it could not re-check a registry it already holds is an app that stops working exactly
 * where it is needed. The only thing a failed fetch costs is freshness.
 *
 * ── THE VERSION STRING ───────────────────────────────────────────────────────────────────────────
 *
 * [SchemaResponse.version] is a content digest of every key, type, tier and required flag — the
 * server derives it in `registry_version()` and deliberately makes it insensitive to labels and help
 * text, so retitling a field does not invalidate every draft on every phone while retyping one does.
 * It is exposed through [StageSchemaStore.cachedVersion] so a draft written against an older registry
 * can be recognised as such rather than being silently rendered through a form that no longer matches
 * the keys it holds.
 *
 * ── KEEPING THE BUNDLED ASSET HONEST ─────────────────────────────────────────────────────────────
 *
 * Regenerate `app/src/main/assets/design-workshop-schema.json` whenever the registry changes:
 *
 *     cd backend && python -c "import json,pathlib;\
 *       from app.services import stage_definitions;\
 *       from app.services.stage_schema import registry_to_dict;\
 *       pathlib.Path('../android/app/src/main/assets/design-workshop-schema.json')\
 *         .write_text(json.dumps(registry_to_dict(),ensure_ascii=False,separators=(',',':')),\
 *                     encoding='utf-8')"
 *
 * WRITE THE FILE, DO NOT REDIRECT STDOUT. The obvious `print(...) > file.json` form is what this
 * command used to say, and on Windows it dies with UnicodeEncodeError partway through: the console
 * encoding is cp1252 and the registry contains '✓' (stage 12's help text asks designers to type it).
 * The redirect had already truncated the file by then, so the failure mode is a HALF-WRITTEN asset,
 * which [StageSchemaStore.load] cannot parse at all.
 *
 * A stale asset is MOSTLY harmless — it is only the tier-3 floor, and the first fetch supersedes it
 * — but a MISSING one is not, so [StageSchemaStore.load] treats its absence as a hard error rather
 * than returning an empty registry that would render 22 blank stages and look like a data-loss bug.
 *
 * "Mostly" because staleness was once undetectable in the one way that mattered. This asset carried
 * two derived fields where the registry had five — missing exactly the three cost-sheet ones — and
 * its `version` matched the live registry CHARACTER FOR CHARACTER, because `registry_version()`
 * digested key/type/tier/required/enum/deprecated and not the derivation. So a handset that had
 * never reached the network simply never computed those totals, and the staleness check reported
 * agreement. The digest now covers `derived_kind` and `derived_from`; see
 * `test_the_version_changes_when_a_derivation_changes`.
 */

// --------------------------------------------------------------------------------------
// The wire shape. Every field defaulted, without exception.
// --------------------------------------------------------------------------------------

/*
 * DEFAULTS ARE A RULE HERE, not a convenience, and the reason is the same one written out at length
 * on [DraftMedia]: this payload is decoded by whatever build happens to be on the handset, which may
 * be weeks behind the server. kotlinx throws `MissingFieldException` on a non-defaulted field that is
 * absent, and the server deliberately OMITS every key at its default value to keep the payload small
 * (`field_to_dict` emits `help` only when non-empty, and so on). A non-defaulted property here would
 * therefore fail to decode the registry for the ordinary case, not the exotic one.
 */

/** One option of a shared enumeration: the stored token and the label a human reads. */
@Serializable
data class EnumOption(
    val value: String = "",
    val label: String = "",
)

/** One typed field of one entity — the unit [FieldRenderer] dispatches on. */
@Serializable
data class FieldDto(
    val key: String = "",
    val label: String = "",
    /** TEXT | LONG_TEXT | INT | … — kept as the raw token; see [DwFieldType.of] for why. */
    val type: String = "TEXT",
    /** BASIC | STANDARD | ADVANCED. */
    val tier: String = "STANDARD",
    val required: Boolean = false,
    val help: String = "",
    val unit: String = "",
    /**
     * The name of the shared list in the registry's `enums` table.
     *
     * `@SerialName` rather than a property called `enum` because `enum` is a Kotlin modifier keyword:
     * it is legal as an identifier today, but naming a property after a soft keyword is a trap for
     * the next person who writes `field.enum` inside a `when` and gets a parse error they cannot
     * explain. The wire name is what matters and it is pinned here.
     */
    @SerialName("enum") val enumName: String = "",
    /** Inlined by the server for ENUM/MULTI_ENUM so a renderer never has to join against `enums`. */
    val options: List<EnumOption> = emptyList(),
    val refModel: String = "",
    /**
     * The other field OF THE SAME ENTITY whose chosen value narrows this one's options.
     *
     * The server's half of this contract is `FieldSpec.ref_filter_by`, and it is emitted only when
     * set. `existingProduct.productRef` carries `refFilterBy = "artisanRef"`, so once the artisan is
     * picked the product list holds that artisan's products and nothing else.
     *
     * WHAT IGNORING IT COSTS. A cluster with three hundred documented products offers all three
     * hundred in one dropdown, and a designer who cannot find the row scrolls twice and then types
     * the product name in by hand. That leaves the stage with a NAME and no join key, which is the
     * exact failure the REF type exists to prevent: the workshop's baseline can no longer be
     * connected to the product record it was measured from, and nobody notices until someone tries
     * to compare a cluster's second workshop against its first and finds thirty free-text rows.
     */
    val refFilterBy: String = "",
    /**
     * WORKSHOP or ALL — how wide the reference search runs.
     *
     * Emitted for EVERY ref field, defaulted server-side rather than omitted, precisely so the client
     * never has to supply a default of its own; the picker sends this value straight back on
     * `GET /design-workshops/{id}/references`. The default here is the empty string rather than
     * "ALL" for one reason: an empty string means "the server did not say", and the picker forwards
     * nothing rather than asserting a scope the server never chose. Hard-coding "ALL" here is how a
     * client and a server come to disagree about how wide the net is — and the wrong answer in that
     * direction offers a designer at stage 6 every artisan in the country instead of the eleven on
     * this workshop's roster.
     */
    val refScope: String = "",
    val maxLength: Int = 0,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    /** NARRATIVE | KEY_VALUE | TABLE_COLUMN | CAPTION | GALLERY | COVER_FIELD | METRIC | BULLETS | HIDDEN. */
    val reportRole: String = "KEY_VALUE",
    /**
     * DAYS_BETWEEN | PRODUCT | SUM — how this field computes itself when left blank.
     *
     * DROPPING THESE TWO KEYS IS WHAT KEPT A COMPUTED FIELD EMPTY ON THIS SURFACE. They are served by
     * `GET /design-workshops/schema` and were simply not decoded here, so `durationDays` and every
     * costing line's `amount` had no rule to follow and stayed blank on the phone while the same
     * field filled itself in on the web. The help text — "Leave blank to compute it as quantity ×
     * rate" — promised a behaviour the phone did not have, which is worse than not offering it: the
     * designer leaves the box empty on the strength of the promise and the cost sheet ships with a
     * hole in it that nobody sees until a sync that may be a fortnight away.
     *
     * See [DwDerived] for the arithmetic and for why it is a port rather than a second opinion.
     */
    val derivedKind: String = "",
    /** The sibling keys the derivation reads, IN ORDER: DAYS_BETWEEN reads start first, then end. */
    val derivedFrom: List<String> = emptyList(),
    val columnWidthPct: Float = 0f,
    /**
     * This field is the CAPTION of that media field, and must be drawn directly beneath it.
     *
     * Rendering it as a separate input is the failure this exists to prevent: the designer sees a
     * lone "Caption" box three fields below the photo it belongs to, types the wrong subject into it,
     * and the report prints a caption under a picture it does not describe — permanently, in a file
     * already delivered. [StageScreen] therefore removes captioned fields from the ordinary flow and
     * hands them to their media field to draw.
     */
    val captionFor: String = "",
    val deprecated: Boolean = false,
    val replacedBy: String = "",
)

/** One record shape within a stage: the stage's own answers, or one row of a repeating list. */
@Serializable
data class EntityDto(
    val key: String = "",
    val name: String = "",
    /** SINGLETON | COLLECTION. */
    val cardinality: String = "SINGLETON",
    val title: String = "",
    val description: String = "",
    val parent: String = "",
    /** Which field titles a row in a COLLECTION list. Blank falls back to the first free-text field. */
    val labelField: String = "",
    val fields: List<FieldDto> = emptyList(),
)

/** One of the 22 stages. */
@Serializable
data class StageDto(
    val number: Int = 0,
    val key: String = "",
    val title: String = "",
    val purpose: String = "",
    val notes: String = "",
    val optionalStage: Boolean = false,
    val entities: List<EntityDto> = emptyList(),
)

/** The whole registry, exactly as `GET /design-workshops/schema` serves it. */
@Serializable
data class SchemaResponse(
    val version: String = "",
    val enums: Map<String, List<EnumOption>> = emptyMap(),
    val stages: List<StageDto> = emptyList(),
)

// --------------------------------------------------------------------------------------
// Typed views over the raw tokens
// --------------------------------------------------------------------------------------

/**
 * The capture kind, resolved from [FieldDto.type].
 *
 * Resolved rather than deserialized, and that is the whole point of keeping the DTO's property a
 * `String`. A phone one release behind the server meets field types it has never heard of; decoding
 * straight into a Kotlin enum makes that a `SerializationException` that fails the WHOLE registry,
 * so one new type on the server would blank all 22 stages on every handset that had not updated.
 * [of] degrades the unknown token to [TEXT] instead, which captures the answer as a string — the
 * server will coerce or drop it, and the other 495 fields keep working.
 */
enum class DwFieldType {
    TEXT, LONG_TEXT, RICH_TEXT, INT, DECIMAL, MONEY, PERCENT, DATE, TIME, BOOL, ENUM, MULTI_ENUM,
    TAGS, IMAGE, IMAGE_LIST, FILE, AUDIO, VIDEO, GEO, REF, URL, PHONE, EMAIL;

    /** Media fields capture a file, so they are drawn by the capture surface rather than a text box. */
    val isMedia: Boolean
        get() = this == IMAGE || this == IMAGE_LIST || this == FILE || this == AUDIO || this == VIDEO

    val isNumeric: Boolean get() = this == INT || this == DECIMAL || this == MONEY || this == PERCENT

    /**
     * Types that hold words a person wrote, mirroring `FieldSpec.is_free_text`.
     *
     * RICH_TEXT is one of them. It is easy to leave out — it is stored as an object rather than a
     * string — and leaving it out is how a row whose only prose lives in a rich field ends up with no
     * heading the reader can identify it by.
     */
    val isFreeText: Boolean get() = this == TEXT || this == LONG_TEXT || this == RICH_TEXT

    /** Whether the STORED value is a JSON array rather than a scalar. */
    val isMulti: Boolean get() = this == MULTI_ENUM || this == TAGS || this == IMAGE_LIST

    companion object {
        fun of(raw: String): DwFieldType =
            entries.firstOrNull { it.name == raw } ?: TEXT
    }
}

/**
 * Capture tier. BASIC is required and always shown; STANDARD is shown and optional; ADVANCED is
 * collapsed behind a "More detail" disclosure so a workshop held in a village without power is not
 * confronted with two hundred fields it cannot answer.
 *
 * Unknown tokens resolve to [STANDARD] — visible and optional — because the two failure modes are
 * not symmetrical: hiding an unknown field loses data silently, while showing one merely adds a row.
 */
enum class DwTier {
    BASIC, STANDARD, ADVANCED;

    companion object {
        fun of(raw: String): DwTier = entries.firstOrNull { it.name == raw } ?: STANDARD
    }
}

/** The stage's one-per-workshop entity, or null for a stage that is only repeating lists. */
val StageDto.singleton: EntityDto?
    get() = entities.firstOrNull { it.cardinality == "SINGLETON" }

/** The repeating entities, in declaration order. */
val StageDto.collections: List<EntityDto>
    get() = entities.filter { it.cardinality != "SINGLETON" }

fun StageDto.entity(key: String): EntityDto? = entities.firstOrNull { it.key == key }

fun EntityDto.field(key: String): FieldDto? = fields.firstOrNull { it.key == key }

/** Live (non-deprecated) fields. A deprecated field keeps its stored data but gets no input. */
val EntityDto.liveFields: List<FieldDto>
    get() = fields.filter { !it.deprecated }

/**
 * Which field titles a row of this collection in the list.
 *
 * Falls back to the first free-text field, and then to the first field of any kind, so a row is never
 * titled with a blank. An untitled row in a list of fourteen prototypes is a row the designer has to
 * open one at a time to identify, which is how the wrong one gets deleted.
 */
val EntityDto.rowTitleField: FieldDto?
    get() = field(labelField)
        ?: liveFields.firstOrNull { DwFieldType.of(it.type).let { t -> t == DwFieldType.TEXT || t == DwFieldType.LONG_TEXT } }
        ?: liveFields.firstOrNull()

// --------------------------------------------------------------------------------------
// Values: reading, writing and validating the JSON a field holds
// --------------------------------------------------------------------------------------

/**
 * The stored form of every field type, and the coercion between it and what a text box holds.
 *
 * THIS IS A DELIBERATE SECOND IMPLEMENTATION of `stage_schema.coerce_value` / `validate_entry` /
 * `stage_completeness`, and the duplication is the price of working offline. The alternative is
 * asking the server whether a value is acceptable, which is precisely the question a designer in a
 * courtyard with no signal cannot get an answer to — and a form that shows no error until the sync
 * two weeks later is a form that collected two weeks of unusable answers. Where the two ever
 * disagree the SERVER is right; this side exists to catch the mistake early, not to be the authority.
 */
object DwValues {

    private val TRUE_TOKENS = setOf("true", "yes", "y", "1", "on")
    private val FALSE_TOKENS = setOf("false", "no", "n", "0", "off")

    /** One coercion attempt: the value to store, or the message to show under the box. */
    data class Coerced(val value: JsonElement?, val error: String?)

    /** The scalar content of a stored value as text, blank when absent. Never quotes a string. */
    fun text(value: JsonElement?): String = when (value) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.content
        is JsonArray -> value.joinToString(", ") { (it as? JsonPrimitive)?.content.orEmpty() }
        is JsonObject -> ""
    }

    /** A stored array as a list of tokens. A scalar degrades to a one-element list, never to a crash. */
    fun list(value: JsonElement?): List<String> = when (value) {
        null, JsonNull -> emptyList()
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
        is JsonPrimitive -> listOfNotNull(value.content.takeIf(String::isNotBlank))
        is JsonObject -> emptyList()
    }

    /** A stored boolean, or null for "not answered" — which is NOT the same as `false`. */
    fun bool(value: JsonElement?): Boolean? {
        val primitive = value as? JsonPrimitive ?: return null
        primitive.booleanOrNull?.let { return it }
        val token = primitive.content.trim().lowercase(Locale.ROOT)
        return when {
            token in TRUE_TOKENS -> true
            token in FALSE_TOKENS -> false
            else -> null
        }
    }

    /** A stored GEO object, or null. */
    fun geo(value: JsonElement?): Pair<Double, Double>? {
        val obj = value as? JsonObject ?: return null
        val lat = (obj["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val lon = (obj["lon"] as? JsonPrimitive)?.doubleOrNull ?: return null
        return lat to lon
    }

    fun geoOf(lat: Double, lon: Double, accuracy: Double? = null): JsonObject = JsonObject(
        buildMap {
            put("lat", JsonPrimitive(lat))
            put("lon", JsonPrimitive(lon))
            if (accuracy != null) put("accuracy", JsonPrimitive(accuracy))
        }
    )

    fun ofList(items: List<String>): JsonElement? =
        if (items.isEmpty()) null else JsonArray(items.map { JsonPrimitive(it) })

    /**
     * Whether a value counts as an answer, matching the server's `_is_filled`.
     *
     * A blank string is NOT an answer. That case is the one that matters: a box the designer focused
     * and left, or cleared, holds `""`, and counting it as filled is how a stage reports itself 100%
     * complete with nothing in it. `false` and `0` ARE answers — "this cluster has no power supply"
     * is a finding, not a blank.
     */
    fun isFilled(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        is JsonPrimitive -> value.content.isNotBlank()
        is JsonArray -> value.isNotEmpty()
        is JsonObject -> value.isNotEmpty()
    }

    /**
     * Coerce what a text box holds into the stored form of [field], mirroring `coerce_value`.
     *
     * Forgiving on the way in and strict about the type, exactly as the server is. A blank always
     * coerces to `null` with no error — whether blank is ALLOWED is [validate]'s question, because
     * flagging every empty required box the moment the screen opens turns a 40-field stage into a
     * wall of red before the designer has typed anything.
     */
    fun coerce(field: FieldDto, raw: String): Coerced {
        val type = DwFieldType.of(field.type)
        val trimmed = if (type == DwFieldType.LONG_TEXT) raw else raw.trim()
        if (trimmed.isBlank()) return Coerced(null, null)

        return when (type) {
            DwFieldType.INT -> {
                val parsed = trimmed.replace(",", "").toLongOrNull()
                    ?: return Coerced(null, "${field.label} is not a valid whole number")
                rangeChecked(field, parsed.toDouble())?.let { return Coerced(null, it) }
                Coerced(JsonPrimitive(parsed), null)
            }

            /*
             * NON-FINITE IS NOT A NUMBER, and [rangeChecked] cannot be the thing that says so.
             *
             * `toDoubleOrNull` happily reads "NaN", "Infinity" and any run of more than 308 digits,
             * and this is an ordinary text box on a decimal keypad, so a designer can type one — or
             * paste one out of a spreadsheet cell that divided by an empty column. Every comparison
             * against NaN is false, so `nan < 0` sails through a min-0 floor and `inf` passes any
             * floor there is; the range check waves both through.
             *
             * The server added `math.isfinite` here for what happened next. MONEY stringifies, so
             * `"%.2f"` of NaN stored the literal "nan" behind a 200 with no errors — the designer is
             * told "Stage saved" — and the report printed "₹ nan" on the cover preview and in the
             * .docx submitted to the ministry, while the cost charts dropped the row silently
             * (`cleanSeries` rejects non-finite) so the totals disagreed with the table beside them.
             * DECIMAL stored the raw float, Postgres refused it, and the whole stage save 500'd —
             * which the stage editor reads as "no connection", so the designer is told to wait for
             * signal while a permanently un-writable value retries for ever.
             *
             * The server now rejects it, so without this guard the phone merely fails its save
             * later and blames the network. Caught here, the message names the box.
             */
            DwFieldType.DECIMAL, DwFieldType.PERCENT -> {
                val parsed = trimmed.replace(",", "").replace("₹", "").toDoubleOrNull()
                    ?.takeIf { it.isFinite() }
                    ?: return Coerced(null, "${field.label} is not a valid number")
                rangeChecked(field, parsed)?.let { return Coerced(null, it) }
                Coerced(JsonPrimitive(parsed), null)
            }

            // Money is stored as a fixed two-decimal STRING, matching the server, so it survives the
            // JSON round trip without picking up a binary-float artefact: ₹1250.10 must not come back
            // as 1250.0999999999999 in a costing table an officer is asked to sign.
            // Non-finite is refused here for the reason set out on DECIMAL above, and this is the
            // arm where it did the quieter damage: "nan" stored as a string is a value every layer
            // downstream accepts and prints.
            DwFieldType.MONEY -> {
                val parsed = trimmed.replace(",", "").replace("₹", "").toDoubleOrNull()
                    ?.takeIf { it.isFinite() }
                    ?: return Coerced(null, "${field.label} is not a valid amount")
                rangeChecked(field, parsed)?.let { return Coerced(null, it) }
                Coerced(JsonPrimitive(String.format(Locale.ROOT, "%.2f", parsed)), null)
            }

            DwFieldType.BOOL -> when (trimmed.lowercase(Locale.ROOT)) {
                in TRUE_TOKENS -> Coerced(JsonPrimitive(true), null)
                in FALSE_TOKENS -> Coerced(JsonPrimitive(false), null)
                else -> Coerced(null, "${field.label} must be yes or no")
            }

            // ISO-8601, always. The picker in FieldDateField types day-month-year on every handset
            // (see its KDoc on why Material's locale-driven input format is unusable here) and hands
            // back a LocalDate; this is only the paste/round-trip path.
            DwFieldType.DATE -> try {
                Coerced(JsonPrimitive(LocalDate.parse(trimmed.take(10)).toString()), null)
            } catch (_: DateTimeParseException) {
                Coerced(null, "${field.label} is not a valid date")
            }

            DwFieldType.TIME -> {
                val parts = trimmed.split(":")
                val hour = parts.getOrNull(0)?.trim()?.toIntOrNull()
                val minute = parts.getOrNull(1)?.trim()?.toIntOrNull()
                if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
                    Coerced(null, "${field.label} is not a valid time")
                } else {
                    Coerced(JsonPrimitive(String.format(Locale.ROOT, "%02d:%02d", hour, minute)), null)
                }
            }

            DwFieldType.ENUM ->
                if (field.options.isNotEmpty() && field.options.none { it.value == trimmed }) {
                    Coerced(null, "${field.label}: “$trimmed” is not one of the options")
                } else {
                    Coerced(JsonPrimitive(trimmed), null)
                }

            DwFieldType.EMAIL ->
                if (!trimmed.contains('@') || trimmed.startsWith('@') || trimmed.endsWith('@')) {
                    Coerced(null, "${field.label} is not a valid email address")
                } else {
                    lengthChecked(field, trimmed)
                }

            DwFieldType.URL ->
                // Deliberately permissive: a field officer types "dch.gov.in" and meaning it is the
                // point, not the scheme. Only something that is plainly not a locator is refused.
                if (trimmed.contains(' ')) {
                    Coerced(null, "${field.label} must not contain spaces")
                } else {
                    lengthChecked(field, trimmed)
                }

            else -> lengthChecked(field, trimmed)
        }
    }

    private fun lengthChecked(field: FieldDto, text: String): Coerced =
        if (field.maxLength > 0 && text.length > field.maxLength) {
            Coerced(null, "${field.label} is longer than ${field.maxLength} characters")
        } else {
            Coerced(JsonPrimitive(text), null)
        }

    private fun rangeChecked(field: FieldDto, value: Double): String? {
        val min = field.minValue
        val max = field.maxValue
        return when {
            min != null && value < min -> "${field.label} must be at least ${trimNumber(min)}"
            max != null && value > max -> "${field.label} must be at most ${trimNumber(max)}"
            else -> null
        }
    }

    /** "12" rather than "12.0" for a whole bound, matching the server's `%g` formatting. */
    private fun trimNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    /**
     * Every per-field problem in one entry, mirroring `validate_entry`.
     *
     * [enforceRequired] is off while a stage is a draft and on at submission, because a stage left
     * half-filled overnight is the NORMAL state of this app rather than an error state.
     */
    fun validate(
        entity: EntityDto,
        data: Map<String, JsonElement>,
        enforceRequired: Boolean,
    ): Map<String, String> {
        val errors = LinkedHashMap<String, String>()
        for (field in entity.liveFields) {
            val stored = data[field.key]
            if (!isFilled(stored)) {
                if (enforceRequired && field.required) errors[field.key] = "${field.label} is required"
                continue
            }
            // Only text-shaped values can be malformed at this point; a picker cannot produce an
            // out-of-range enum and a date field cannot produce a non-date.
            val type = DwFieldType.of(field.type)
            if (!type.isMulti && type != DwFieldType.GEO && !type.isMedia) {
                coerce(field, text(stored)).error?.let { errors[field.key] = it }
            }
        }
        return errors
    }
}

// --------------------------------------------------------------------------------------
// Fields that compute themselves
// --------------------------------------------------------------------------------------

/**
 * The phone's port of `stage_schema.derive_value`.
 *
 * WHY THIS RUNS ON THE DEVICE AT ALL. The server derives these too, on save, which is what makes the
 * STORED value right whatever wrote it. But a number that only materialises after a round trip is a
 * number the designer cannot check against the sanction order lying on the table in front of them —
 * and on this surface the round trip may be a fortnight away, so "it appears once you sync" means the
 * duration on a cover page and the total on a cost sheet are unverifiable for the whole trip, in the
 * one place where the paperwork they must agree with is actually to hand. Computing it here puts the
 * figure on screen in the same frame as the second date.
 *
 * IT IS A PORT, NOT A SECOND OPINION. The rule is declared once, in the registry
 * ([FieldDto.derivedKind] and [FieldDto.derivedFrom]), so this only interprets what the server sent;
 * `frontend/lib/derivedFields.ts` is the browser's copy of the same interpretation. Adding a
 * derivation means adding it to the registry and to the three interpreters. Inventing one HERE would
 * show a figure the save then silently overwrote — worse than showing nothing, because the designer
 * has already read it, written it into a register and moved on.
 *
 * `null` MEANS NOT COMPUTABLE, NEVER 0. A start date with no end date has no duration, and "0 days"
 * on a cover page is a wrong fact rather than a missing one — a reader can see a blank and ask.
 */
object DwDerived {

    /** Whether this field computes itself when left blank. */
    fun isDerived(field: FieldDto): Boolean =
        field.derivedKind.isNotBlank() && field.derivedFrom.isNotEmpty()

    /** What [field] computes to from [row] — the whole record it sits in — or null when it cannot. */
    fun value(field: FieldDto, row: Map<String, JsonElement>): JsonElement? {
        if (!isDerived(field)) return null
        return when (field.derivedKind) {
            "DAYS_BETWEEN" -> daysBetween(field, row)
            "PRODUCT" -> product(field, row)
            "SUM" -> sum(field, row)
            // A kind this build has never heard of is a phone running behind the registry, and the
            // only honest answer is to show nothing: the server still derives it on save, so the
            // value is not lost, it merely appears late. Guessing at an unknown rule would not.
            else -> null
        }
    }

    private fun daysBetween(field: FieldDto, row: Map<String, JsonElement>): JsonElement? {
        val start = isoDate(row[field.derivedFrom.getOrNull(0)]) ?: return null
        val end = isoDate(row[field.derivedFrom.getOrNull(1)]) ?: return null
        // INCLUSIVE of both endpoints: a workshop that runs the 12th to the 14th is three days long,
        // which is what its attendance register and its utilisation certificate both say. The
        // exclusive reading reports two and disagrees with every other document in the file.
        val days = ChronoUnit.DAYS.between(start, end) + 1
        return if (days > 0) JsonPrimitive(days) else null
    }

    private fun product(field: FieldDto, row: Map<String, JsonElement>): JsonElement? {
        var total = 1.0
        for (key in field.derivedFrom) {
            // ANY blank factor abandons the whole product, and that is the difference from [sum]:
            // a quantity with no rate is not a line worth nothing, it is a line not yet priced.
            total *= numeric(row[key]) ?: return null
        }
        return formatted(field, total)
    }

    private fun sum(field: FieldDto, row: Map<String, JsonElement>): JsonElement? {
        // BLANK MEANS ZERO HERE. A cost sheet's total is the sum of six heads of which four are
        // optional, so requiring all six would leave `totalCost` empty for every workshop with no
        // packaging or transport cost — which is most of them. But a row with NONE of them filled has
        // no total: it is an empty row, not a zero-rupee product, and "₹ 0.00" printed into a cost
        // sheet a ministry reads is a claim rather than a blank.
        //
        // A BLANK AND AN UNPARSEABLE VALUE ARE NOT ALIKE, which is why this cannot simply skip
        // whatever [numeric] rejects: a stored "abc" has to abandon the sum the way the server does,
        // or the phone would show a total the save then disagrees with.
        var total = 0.0
        var seen = false
        for (key in field.derivedFrom) {
            val raw = row[key]
            if (isAbsent(raw)) continue
            total += numeric(raw) ?: return null
            seen = true
        }
        return if (seen) formatted(field, total) else null
    }

    /**
     * Absent means EXACTLY null or the empty string, mirroring the server's `raw in (None, "")` —
     * and deliberately NOT [DwValues.isFilled], whose blank-string test is a different question.
     *
     * The difference decides what [sum] does with a stored " ": [DwValues.isFilled] would call it
     * empty and skip it, while the server calls it present, fails to parse it and abandons the sum.
     * A total that appears on the phone and then vanishes on save is the exact class of disagreement
     * this whole file exists to prevent, so the two definitions are kept apart rather than shared.
     */
    private fun isAbsent(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> true
        is JsonPrimitive -> value.content.isEmpty()
        else -> false
    }

    /** A stored value as a number, or null for a blank AND for anything that will not parse. */
    private fun numeric(value: JsonElement?): Double? {
        if (isAbsent(value)) return null
        val primitive = value as? JsonPrimitive ?: return null
        // `toDoubleOrNull` reads "NaN" and "Infinity" as happily as `float()` does, so the finiteness
        // test is what stops a stored non-finite propagating into a printed "₹ nan" — the same guard,
        // for the same reason, that [DwValues.coerce] applies on the way in.
        return primitive.content.replace(",", "").toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /**
     * Date-only, so no zone and no daylight-saving boundary can move it.
     *
     * [LocalDate] carries no instant at all, which is what the browser has to reach for `Date.UTC` to
     * imitate: a local-time parse either side of a DST change makes the difference 29 days where the
     * sanction order says 30, and that discrepancy is exactly what gets a utilisation certificate
     * sent back.
     */
    private fun isoDate(value: JsonElement?): LocalDate? {
        val text = (value as? JsonPrimitive)?.content?.trim().orEmpty()
        if (text.isEmpty()) return null
        return try {
            LocalDate.parse(text.take(10))
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * The computed number in the shape the field STORES.
     *
     * MONEY becomes a fixed-2 string, as `coerce_value` and [DwValues.coerce] both make it, so the
     * figure survives the JSON round trip without picking up a binary-float artefact: ₹1250.10 must
     * not come back as 1250.0999999999999 in a costing table an officer is asked to sign. Everything
     * else rounds to four places, which is where the server leaves it.
     *
     * HALF_EVEN through [BigDecimal] rather than `String.format`, for the reason `ReportChart.fixed`
     * sets out at length: Java's `Formatter` rounds half AWAY from zero and Python's `f"{x:.2f}"`
     * rounds half to EVEN, so a line of 1500.505 would read ₹1500.51 on the phone and be stored as
     * ₹1500.50 by the save. A figure that changes under the designer between reading it and syncing
     * it is precisely the discrepancy computing it here was meant to remove.
     */
    private fun formatted(field: FieldDto, total: Double): JsonElement? {
        // A finite factor times a finite factor still overflows to infinity, and BigDecimal refuses a
        // non-finite outright — so this is a guard against a crash as much as against a bad figure.
        if (!total.isFinite()) return null
        val exact = BigDecimal(total)
        return if (DwFieldType.of(field.type) == DwFieldType.MONEY) {
            JsonPrimitive(exact.setScale(2, RoundingMode.HALF_EVEN).toPlainString())
        } else {
            JsonPrimitive(exact.setScale(4, RoundingMode.HALF_EVEN).toDouble())
        }
    }
}

// --------------------------------------------------------------------------------------
// Completeness, computed on the device
// --------------------------------------------------------------------------------------

/**
 * One stage's score, the same numbers the API returns under `completeness`.
 *
 * Computed locally whenever the API's copy is not to hand, which in this app is most of the time. A
 * progress figure that needs a server to be honest is a progress figure that lies exactly when the
 * designer is deciding whether they can pack up and leave the cluster.
 */
data class DwStageCompleteness(
    val stageKey: String,
    val number: Int,
    val title: String,
    val requiredTotal: Int,
    val requiredFilled: Int,
    val optionalTotal: Int,
    val optionalFilled: Int,
    val collectionCounts: Map<String, Int>,
    /** Labels of the unfilled BASIC fields — the "what is missing" list, in registry order. */
    val missing: List<String>,
) {
    /**
     * Progress across BASIC-tier fields only, matching `StageCompleteness.percent`.
     *
     * A stage with nothing required reads as 100 rather than as 0. Dividing by zero to decide whether
     * a designer may submit is how a stage becomes permanently unsubmittable.
     */
    val percent: Int get() = if (requiredTotal == 0) 100 else Math.round(100f * requiredFilled / requiredTotal)

    val isComplete: Boolean get() = requiredFilled >= requiredTotal
}

/**
 * Score one stage from the data held on this device, mirroring `stage_completeness`.
 *
 * A COLLECTION contributes its required fields ONCE PER EXISTING ROW and contributes nothing while it
 * is empty. An empty sketch list on day one of a workshop is a legitimate state, not a deficiency,
 * and scoring it as a deficiency would leave every stage stuck below 100% until every optional list
 * had been populated.
 */
fun computeStageCompleteness(
    stage: StageDto,
    singleton: Map<String, JsonElement>,
    collections: Map<String, List<Map<String, JsonElement>>>,
): DwStageCompleteness {
    var requiredTotal = 0
    var requiredFilled = 0
    var optionalTotal = 0
    var optionalFilled = 0
    val missing = ArrayList<String>()

    stage.singleton?.liveFields?.forEach { field ->
        val filled = DwValues.isFilled(singleton[field.key])
        if (field.required) {
            requiredTotal++
            if (filled) requiredFilled++ else missing.add(field.label)
        } else {
            optionalTotal++
            if (filled) optionalFilled++
        }
    }

    val counts = LinkedHashMap<String, Int>()
    stage.collections.forEach { entity ->
        val rows = collections[entity.key].orEmpty()
        counts[entity.key] = rows.size
        rows.forEach { row ->
            entity.liveFields.forEach { field ->
                val filled = DwValues.isFilled(row[field.key])
                if (field.required) {
                    requiredTotal++
                    if (filled) requiredFilled++ else missing.add("${entity.title}: ${field.label}")
                } else {
                    optionalTotal++
                    if (filled) optionalFilled++
                }
            }
        }
    }

    return DwStageCompleteness(
        stageKey = stage.key,
        number = stage.number,
        title = stage.title,
        requiredTotal = requiredTotal,
        requiredFilled = requiredFilled,
        optionalTotal = optionalTotal,
        optionalFilled = optionalFilled,
        collectionCounts = counts,
        // De-duplicated with order preserved, as the server does: four prototype rows each missing
        // "Prototype name" is ONE thing to fix, and listing it four times buries the other three.
        missing = missing.distinct(),
    )
}

/**
 * Score all 22 stages from what is on this device, in registry order.
 *
 * A stage the draft has never held is still returned, scored at whatever its required count implies —
 * NOT omitted. An index that lists only the stages already started is an index that cannot tell a
 * designer what is left, which is the one question it exists to answer.
 */
fun computeWorkshopCompleteness(
    schema: SchemaResponse,
    draft: WorkshopDraft?,
): List<DwStageCompleteness> = schema.stages.sortedBy { it.number }.map { stage ->
    val stored = draft?.stages?.get(stage.key)
    computeStageCompleteness(
        stage = stage,
        singleton = stored?.values.orEmpty(),
        collections = stage.collections.associate { entity ->
            entity.key to stored?.rowsFor(entity.key).orEmpty().map { it.values }
        },
    )
}

/**
 * The whole workshop as one percentage.
 *
 * Summed across BASIC fields rather than averaged across stages, so a stage carrying forty required
 * fields counts for forty times as much as one carrying one. Averaging the per-stage percentages
 * would let a workshop with twenty untouched one-field stages and two enormous empty ones read as
 * nearly finished.
 */
fun overallPercent(stages: List<DwStageCompleteness>): Int {
    val total = stages.sumOf { it.requiredTotal }
    if (total == 0) return 100
    return Math.round(100f * stages.sumOf { it.requiredFilled } / total)
}

// --------------------------------------------------------------------------------------
// The API's other design-workshop payloads
// --------------------------------------------------------------------------------------

/** One report template offered at stage 20. */
@Serializable
data class ReportTemplateDto(
    val id: String = "",
    val name: String = "",
    val description: String = "",
)

/** The workshop header, as `workshop_summary` serves it. */
@Serializable
data class DesignWorkshopDto(
    val id: String = "",
    val title: String = "",
    val templateId: String = "DCH_STANDARD",
    val status: String = "DRAFT",
    val workshopCode: String? = null,
    val scheme: String? = null,
    val craftName: String? = null,
    val clusterName: String? = null,
    val state: String? = null,
    val district: String? = null,
    val venue: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val designerName: String? = null,
    val implementingAgency: String? = null,
    val sponsor: String? = null,
    val notes: String? = null,
    val workshopId: String? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val deletedAt: String? = null,
)

@Serializable
data class DesignWorkshopPageDto(
    val items: List<DesignWorkshopDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
    val pages: Int = 0,
)

/** The server's per-stage score. Field-for-field the same numbers [DwStageCompleteness] computes. */
@Serializable
data class StageCompletenessDto(
    val stageKey: String = "",
    val number: Int = 0,
    val title: String = "",
    val requiredTotal: Int = 0,
    val requiredFilled: Int = 0,
    val optionalTotal: Int = 0,
    val optionalFilled: Int = 0,
    val percent: Int = 0,
    val isComplete: Boolean = false,
    val collectionCounts: Map<String, Int> = emptyMap(),
    val missing: List<String> = emptyList(),
)

/**
 * One stage's stored data.
 *
 * Collection rows carry the server's own bookkeeping inside the object — `_entryId`, `_ordinal` and
 * `_clientKey` (see `_stages_payload`). They are stripped before the row reaches a form and re-added
 * on save, because a field key beginning with an underscore is not in the registry and would be
 * dropped by `validate_entry` — taking the row's identity with it and turning every save into a
 * delete-and-recreate that loses the row's ordinal.
 */
@Serializable
data class StageBucketDto(
    val singleton: JsonObject = JsonObject(emptyMap()),
    val collections: Map<String, List<JsonObject>> = emptyMap(),
    val completeness: StageCompletenessDto? = null,
)

@Serializable
data class DesignWorkshopDetailDto(
    val id: String = "",
    val title: String = "",
    val templateId: String = "DCH_STANDARD",
    val status: String = "DRAFT",
    val workshopCode: String? = null,
    val craftName: String? = null,
    val clusterName: String? = null,
    val state: String? = null,
    val district: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val updatedAt: String? = null,
    val schemaVersion: String = "",
    val stages: Map<String, StageBucketDto> = emptyMap(),
    val completeness: Map<String, StageCompletenessDto> = emptyMap(),
)

@Serializable
data class StageListDto(
    val stages: Map<String, StageBucketDto> = emptyMap(),
    val completeness: Map<String, StageCompletenessDto> = emptyMap(),
    val schemaVersion: String = "",
)

@Serializable
data class DesignWorkshopCreateBody(
    val title: String,
    val templateId: String = "DCH_STANDARD",
    val craftName: String? = null,
    val clusterName: String? = null,
    val state: String? = null,
    val district: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val notes: String? = null,
)

/** One entry of one entity, on the way to `PUT /design-workshops/{id}/stages/{stageKey}`. */
@Serializable
data class StageEntryBody(
    val entityKey: String,
    val entryId: String? = null,
    val ordinal: Int? = null,
    val data: JsonObject = JsonObject(emptyMap()),
    /**
     * "I am sending every key I HAVE, not every key there IS."
     *
     * Set only when this handset knows it has NOT seen the server's copy of the row — that is,
     * when [isAuthoritative] is false. The server then keeps the keys absent from [data] rather
     * than deleting them. Left false, the row's data is replaced WHOLESALE, which is what a
     * handset that HAS read the row must do, because for it an absent key is a real deletion.
     *
     * It exists because the blank form a failed download leaves behind is indistinguishable on the
     * wire from a stage somebody emptied. `StageScreen` tells the designer that what is already on
     * the server "will not be replaced by it"; this flag is what makes that true for a singleton.
     *
     * IT MUST NOT BE SERIALISED WHEN FALSE, and it is not: `ApiClient.retrofit` leaves
     * `encodeDefaults` at kotlinx's default of false, so a property still holding its default is
     * omitted from the body entirely. That is what keeps this handset compatible with an API that
     * predates the field — `APIModel` is `extra="forbid"` on the server, so a version that does not
     * know `merge` answers 422 "Extra inputs are not permitted" to every entry carrying it, and the
     * refusal banner then tells the designer to correct an answer that has nothing wrong with it.
     * A handset updates when it next sees wifi and the API updates when somebody deploys it, so the
     * client running ahead of the server is an ordinary state here. If `encodeDefaults` is ever
     * turned on for this client, this field has to become nullable and be sent only when true.
     */
    val merge: Boolean = false,
)

/**
 * A whole stage in one write.
 *
 * WHOLE, never field by field, and the reason is on `StageSaveIn` in the backend: the phone
 * reconnects after two days offline and posts everything it holds for a stage, and either all of it
 * lands or none of it does. A per-field endpoint leaves a stage half-written whenever the connection
 * drops mid-sync, which on one bar of signal is most of the time.
 */
@Serializable
data class StageSaveBody(
    val entries: List<StageEntryBody> = emptyList(),
    /**
     * Replaces THE ENTITIES THIS PAYLOAD NAMES wholesale, so a row the designer deleted on the phone
     * is deleted on the server too. The web form sends false because it edits one row at a time and
     * must not delete a row another editor added.
     *
     * NOT UNCONDITIONALLY TRUE ANY MORE, and the default here is false for the same reason the
     * server's sweep is scoped: it is a claim of authority, and the phone is only entitled to make it
     * over a stage it has actually seen the server's copy of. See [StageDraft.serverBaseline] for the
     * fortnight of process steps, tools and raw materials that the unconditional version deleted.
     */
    val replaceCollections: Boolean = false,
    /**
     * Collections the client now holds ZERO rows of, having deleted them: "delete what you still
     * have." Read by the server only when [replaceCollections] is true, and ignored for singletons,
     * which are never deleted by omission.
     *
     * Without it the sweep cannot see the deletion at all, because an emptied collection contributes
     * no entries to look at. It carries only what [StageDraft.emptiedEntities] recorded a designer
     * actually doing on this device — never "every collection this draft happens to hold no rows of",
     * which would delete rows entered on the web between two of this phone's saves.
     */
    val emptiedEntities: List<String> = emptyList(),
    val submit: Boolean = false,
)

@Serializable
data class StageSaveResultDto(
    val stageKey: String = "",
    val saved: Int = 0,
    val created: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val errors: Map<String, JsonElement> = emptyMap(),
    /**
     * Field keys the server did not recognise and therefore DROPPED.
     *
     * Surfaced rather than swallowed: this is how a phone learns it is running ahead of the server,
     * and a designer whose new field silently vanished on every sync deserves to be told once.
     */
    val droppedKeys: List<String> = emptyList(),
    val completeness: StageCompletenessDto? = null,
    val schemaVersion: String = "",
)

/** Records a report the PHONE generated offline. The bytes are never uploaded — only the fact. */
@Serializable
data class ExportRecordBody(
    val format: String,
    val templateId: String,
    val fileName: String,
    val generatedAt: String,
    val fileSizeBytes: Long? = null,
    val pageCount: Int? = null,
    val checksumSha256: String? = null,
    val warnings: String? = null,
)

// --------------------------------------------------------------------------------------
// The durable cache
// --------------------------------------------------------------------------------------

/**
 * Holds the registry across process deaths and across the two weeks a handset spends out of signal.
 *
 * Same four-part idiom as [WorkshopDraftStore]: kotlinx JSON, one [Mutex], every byte of IO on
 * [Dispatchers.IO], and an atomic temp -> `fd.sync()` -> `renameTo` write. There is no Room and no
 * DataStore here because there is exactly one document, and adding a schema compiler to store one
 * file would buy a code-generation step in exchange for nothing this does not already do.
 */
object StageSchemaStore {

    private const val CACHE_FILE = "design-workshop-schema.json"
    private const val ASSET_FILE = "design-workshop-schema.json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val mutex = Mutex()

    /**
     * The decoded registry, so a screen that opens twenty-two stage screens does not re-parse a
     * hundred kilobytes of JSON twenty-two times on the main thread's critical path.
     *
     * An [AtomicReference] rather than a `var`, because [load] can be entered concurrently from a
     * LaunchedEffect on one screen and a save on another, and a torn read of a reference is the kind
     * of bug that reproduces once a fortnight on one device.
     */
    private val memory = AtomicReference<SchemaResponse?>(null)

    /** The version of whatever is currently in hand, or null before anything has been loaded. */
    fun cachedVersion(): String? = memory.get()?.version

    /** Whatever has already been decoded this process, without touching disk. */
    fun peek(): SchemaResponse? = memory.get()

    /**
     * The registry, from memory, then disk, then the bundled asset — never from the network.
     *
     * Deliberately network-free so that opening a stage screen cannot block on a request. Freshness
     * is [refresh]'s job and it runs beside this, not in front of it: a designer must be able to
     * start typing into stage 3 the instant the screen opens, whether or not the phone has a tower.
     */
    suspend fun load(context: Context): SchemaResponse = withContext(Dispatchers.IO) {
        memory.get()?.let { return@withContext it }
        mutex.withLock {
            // Re-check inside the lock: two screens racing to be first would otherwise both parse.
            memory.get()?.let { return@withLock it }
            val fromDisk = readCacheFile(context)
            val resolved = fromDisk ?: readAsset(context)
            memory.set(resolved)
            resolved
        }
    }

    /**
     * Replace the cached registry with a freshly fetched one.
     *
     * Returns true when the version actually MOVED, which is the signal a caller uses to warn that
     * drafts written against the previous registry may hold keys this one no longer knows. Writing
     * the file even when the version is unchanged is deliberate — labels and help text are outside
     * the digest (see `registry_version`), so an unchanged version can still carry better prose.
     */
    suspend fun store(context: Context, fetched: SchemaResponse): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = memory.get()?.version ?: readCacheFile(context)?.version
            writeCacheFile(context, fetched)
            memory.set(fetched)
            previous != null && previous != fetched.version
        }
    }

    private fun readCacheFile(context: Context): SchemaResponse? {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(SchemaResponse.serializer(), file.readText())
        }.getOrNull()?.takeIf { it.stages.isNotEmpty() }
            ?: run {
                // A cache that will not decode is damage, and the recovery is to fall through to the
                // bundled asset rather than to hand back an empty registry. Delete it so the next
                // successful fetch is not shadowed by a file that can never be read.
                runCatching { file.delete() }
                null
            }
    }

    /**
     * The copy built into the APK.
     *
     * Its absence is a HARD error rather than an empty registry. A build that shipped without the
     * asset would otherwise render 22 stages with no fields in them on any handset that has not yet
     * fetched — which looks exactly like the data-loss bug this whole subsystem exists to prevent,
     * and which nobody would trace back to a missing file in the APK.
     */
    private fun readAsset(context: Context): SchemaResponse {
        val text = runCatching {
            context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        }.getOrElse {
            throw IOException(
                "The bundled field registry ($ASSET_FILE) is missing from this build. Regenerate it " +
                    "from the backend before shipping — see the note in StageSchema.kt.",
                it
            )
        }
        return json.decodeFromString(SchemaResponse.serializer(), text)
    }

    /**
     * Temp file in the SAME directory, flushed all the way to storage, then renamed over the target.
     *
     * The identical discipline [WorkshopDraftStore.writeLocked] documents at length, and for the same
     * reason: a rename is atomic only within one filesystem, and a rename whose bytes are still in
     * the page cache publishes a zero-length registry after a power loss — which would be worse than
     * having no cache at all, because the previous good copy is gone with it.
     */
    private fun writeCacheFile(context: Context, schema: SchemaResponse) {
        val target = File(context.filesDir, CACHE_FILE)
        val temp = File(context.filesDir, "$CACHE_FILE.writing")
        val bytes = json.encodeToString(SchemaResponse.serializer(), schema).toByteArray()
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(target)) throw IOException("Unable to replace ${target.name}")
        } catch (e: Throwable) {
            runCatching { temp.delete() }
            throw e
        }
    }
}

// --------------------------------------------------------------------------------------
// Bridging the draft store's shape to the registry's
// --------------------------------------------------------------------------------------

/**
 * The separator between an entity key and a row's own id inside [DraftRow.id].
 *
 * [StageDraft] holds ONE `rows` list, but a stage can declare several COLLECTION entities (stage 11
 * has sketches and iterations; stage 17 has costing lines and channels). Rather than change the
 * on-disk shape of a store that is already written and tested — which would mean a migration rung on
 * every field phone — each row's id carries the entity it belongs to as a prefix, so
 * `rowsFor(entityKey)` is a filter that preserves relative order and therefore preserves ordinal.
 *
 * '#' because it cannot appear in an entity key (the registry's own validator requires keys to start
 * with a letter and they are all lowerCamelCase) and because a UUID never contains one, so splitting
 * on the FIRST occurrence is unambiguous in both directions.
 */
const val DW_ROW_KEY_SEPARATOR: Char = '#'

/**
 * The prefix on the id of a workshop that exists ONLY on this device.
 *
 * A workshop started with no connection cannot have a server id, and refusing to start one until
 * there is signal would make the app useless on the morning it is opened — which is exactly the
 * morning a designer arrives at a cluster. So it gets a local id, every stage is captured against it,
 * and [WorkshopDraft.remoteId] is filled in when the record is finally created on the server.
 *
 * The prefix is what tells the stage screen there is nowhere to sync to yet, so it does not spend a
 * request (and a failure message) on a PUT to an id the server has never heard of.
 */
const val DW_LOCAL_ID_PREFIX: String = "local-"

fun isLocalOnlyWorkshop(workshopId: String): Boolean = workshopId.startsWith(DW_LOCAL_ID_PREFIX)

fun dwRowId(entityKey: String, rowId: String): String = "$entityKey$DW_ROW_KEY_SEPARATOR$rowId"

fun DraftRow.entityKey(): String = id.substringBefore(DW_ROW_KEY_SEPARATOR, "")

/** This entity's rows, in stored order — which IS their ordinal. */
fun StageDraft.rowsFor(entityKey: String): List<DraftRow> =
    rows.filter { it.entityKey() == entityKey }
