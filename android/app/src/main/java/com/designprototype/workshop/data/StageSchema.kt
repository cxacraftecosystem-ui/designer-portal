package com.designprototype.workshop.data

import android.content.Context
import com.designprototype.workshop.report.isEmptyDocument
// Aliased because `fromJson`/`toJson` are words half this file's other imports could also have
// claimed; at the call site in [DwValues.coerceHydrated] the reader has to be able to see that the
// rich-text model is what is doing the normalising, and not kotlinx.
import com.designprototype.workshop.report.fromJson as richTextFromJson
import com.designprototype.workshop.report.toJson as richTextToJson
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
 * stages holding hundreds of typed fields across dozens of entities, and the tiers within them move between
 * studies. Hand-writing 22 forms means every registry edit is an app release, and — worse — it means
 * the phone, the web form, the validator and the report writer each carry their own opinion about what
 * stage 14 contains. They would drift, and the first anyone would notice is a ministry report with an empty
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
 *
 * NOTHING ON THIS PHONE COMPARES IT TO ANYTHING, AND THAT IS THE HONEST DESCRIPTION. This comment
 * used to claim the version "is exposed through [StageSchemaStore.cachedVersion] so a draft written
 * against an older registry can be recognised as such rather than being silently rendered through a
 * form that no longer matches the keys it holds". It is not, and it cannot be: [cachedVersion] has no
 * callers, [StageSchemaStore.store]'s "the version moved" return value is discarded by its only
 * caller ([WorkshopRepository.designWorkshopSchema]), and a [WorkshopDraft] has no field naming the
 * registry it was written against — so there is nothing to recognise a draft AS. The browser is the
 * client that does this properly: its draft carries `registryVersion` and its IndexedDB registry
 * store is keyed by it, so a stage can be drawn through the registry it was captured with.
 *
 * MEASURED, NOT REASONED (SM-M325F, 2026-08-13). With a real draft on disk, the cached registry's
 * digest was replaced with a bogus one and the workshop list re-opened to force a fetch. The cache
 * file was rewritten to the server's digest and the draft was byte-for-byte identical afterwards
 * (sha256 unchanged): no migration, no refusal, no silent re-keying, no data loss, and no banner.
 *
 * That outcome is safe because of three things that are NOT this version string, and they are what
 * to protect if this ever gets touched: [StageDraft.values] is a `JsonElement` map, so a key this
 * build no longer knows survives a round trip instead of being dropped; [StageDraft.requiredKeys] is
 * recomputed from the live registry on every persist rather than trusted from disk, so a newly
 * required field cannot leave a stage reading 100%; and the drift a designer must actually hear
 * about is the server's `droppedKeys` at save time, which [WorkshopSync] already surfaces.
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

/**
 * THE CEILING A MULTI-VALUED FIELD IS HELD TO WHERE THE REGISTRY DECLARES NONE — enforced, never
 * printed.
 *
 * The twin of `DEFAULT_MAX_ITEMS` at backend/app/services/stage_schema.py:1764, and a twin rather
 * than a guess: `coerce_value` reads `spec.max_items or DEFAULT_MAX_ITEMS` and then REFUSES an array
 * longer than that instead of trimming it (stage_schema.py:1822, under a comment headed "A REFUSAL,
 * NOT A TRUNCATION"), while `save_stage` restores the rejected key from `previous`. So a client that
 * reads an absent [FieldDto.maxItems] as "no ceiling at all" does not cost the designer the surplus
 * photographs — it costs them the whole field's write, with the bytes already copied into the
 * workshop's media directory. That is what this client did until 2026-08-26; see the ceiling block
 * in `ui/designworkshop/DwMediaCapture.kt`.
 *
 * IT IS ENFORCED AND NEVER PRINTED, which is both halves of docs/DESIGN_WORKSHOP.md:229-232 — a
 * client "must neither read the absence as no limit nor print a number it did not read". Drawing
 * "up to 200" on a gallery the registry said nothing about would be this client inventing a number
 * the server owns and may change without a `registry_version()` bump. So the always-visible "up to
 * N" hint is gated on the DECLARED value alone, and the trim that stops an array growing past this
 * ceiling still SAYS what it dropped, worded without the number.
 */
const val DW_DEFAULT_MAX_ITEMS: Int = 200

/**
 * The ceiling actually enforced for a multi-valued field, from whatever [FieldDto.maxItems] holds.
 *
 * One function rather than the same `takeIf` written out at each call site: the callers are the
 * capture card's trim, [DwPhotoIntake.appendMediaRef], the TAGS and MULTI_ENUM controls in
 * `FieldRenderer.kt`, and [DwValues.coerceHydrated] — every path that can put entries into a
 * multi-valued field. The defect this closes was those surfaces disagreeing about what an absent cap
 * meant, so the list is deliberately exhaustive: a new one that does not call this is the bug.
 */
fun dwEffectiveMaxItems(declared: Int): Int = declared.takeIf { it > 0 } ?: DW_DEFAULT_MAX_ITEMS

/**
 * THE FLOOR A MULTI-VALUED FIELD MUST REACH, OR NULL WHERE THE REGISTRY DECLARES NONE — and there is
 * deliberately NO default to fall back on.
 *
 * The mirror image of [dwEffectiveMaxItems] and the shape of the mirroring is the point: an absent
 * CEILING means the server's [DW_DEFAULT_MAX_ITEMS], because `coerce_value` will enforce one
 * whatever a client believes; an absent FLOOR means no floor at all, because nothing anywhere
 * enforces one. So this returns null and never a number, and a caller that wants to print "of 25"
 * must have read the 25 off the wire. Two fields in the whole registry answer it today — the motif
 * pair, at 25 each.
 *
 * ── THE OTHER ASYMMETRY, WHICH IS THE ONE THAT MATTERS ON A HANDSET ───────────────────────────
 *
 * `min_items` IS part of `registry_version()` and `max_items` is NOT, and the reason is this client.
 * A ceiling has a server-side backstop, so a phone enforcing a stale cap is merely early. A floor
 * has none: it is scored in `stage_completeness` and nowhere else, so a handset that has never
 * refetched the registry scores stage 4 complete at one photograph and tells a designer they may
 * leave the cluster. The digest moving is what makes the bundled asset and the cached copy stale
 * together; see `StageSchemaStore` for what a moved version costs, which is a refetch and nothing
 * more.
 */
fun dwDeclaredMinItems(declared: Int): Int? = declared.takeIf { it > 0 }

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
    /**
     * WHICH BOXES THIS PICKER FILLS IN: the chosen record's own `data` keys, mapped to the field
     * keys of the entity being filled.
     *
     * THE CLIENT MAY NOT DERIVE THIS BY MATCHING KEY NAMES, which is what this surface used to do
     * and is not a smaller version of the rule — it is a different and wrong one. On
     * `existingProduct` the reference's `data["name"]` is the ARTISAN's name under `artisanRef`
     * and the PRODUCT's name under `productRef`, and the entity has a `name` field of its own that
     * means the product. Matching names therefore wrote the artisan's name into the product's name
     * box, on a row nobody had finished; the server's only-fill-blanks rule then refused to correct
     * it at save, and the participant's name printed in the product table of a ministry report —
     * on this surface only, while the same pick on the web filled the row correctly. `prototype`
     * had the same collision, with the documented product's name landing in "Prototype name".
     *
     * The server is the authority (`REFERENCE_HYDRATION` in `stage_schema.py`, emitted by
     * `field_to_dict`). An ABSENT map hydrates NOTHING rather than falling back to a guess: a
     * missing entry costs the designer one retyped box and the server fills it at save regardless,
     * whereas a guessed one costs a wrong value nobody can see is wrong.
     */
    val refHydration: Map<String, String> = emptyMap(),
    val maxLength: Int = 0,
    /**
     * HOW MANY ENTRIES A MULTI-VALUED FIELD MAY HOLD — IMAGE_LIST, TAGS, MULTI_ENUM.
     *
     * ABSENT (0) MEANS "the server's own default", which is [DW_DEFAULT_MAX_ITEMS] = 200, and NOT
     * "unbounded". `field_to_dict` emits the key only when the registry declares one, and the two
     * things that follow from that must not be traded for each other: the absence is ENFORCED as 200
     * ([dwEffectiveMaxItems]) and never PRINTED as 200. A cap drawn on screen has to be the cap that
     * will be enforced, and 200 is the server's business to change — so the "up to N" sentence is
     * gated on this value being non-zero, while the trim that holds an array to the ceiling is not.
     * Reading the absence as no ceiling at all is the OTHER half of the pair
     * docs/DESIGN_WORKSHOP.md:229-232 forbids, and it is the half both clients did until 2026-08-26.
     *
     * READ BY EVERY CONTROL THAT CAN GROW A MULTI-VALUED FIELD, AND BY NOTHING AT ALL BEFORE
     * 2026-08-25. The capture card came first (2026-08-25); the photo intake, the TAGS box and the
     * MULTI_ENUM pair followed on 2026-08-26, which is when the last write path that could overrun a
     * gallery was closed. Enumerated as a RULE rather than as a list of three, because this sentence
     * has already gone stale once by naming surfaces: anything that appends to one of these fields
     * owes it [dwEffectiveMaxItems], and the test for a new one is whether it can make an array
     * longer. `coerce_value` REFUSES an over-long array rather than trimming it — deliberately,
     * because silently keeping the first N of a list the client believes it stored is exactly the
     * "Stage saved, and the photographs are gone" failure that module refuses everywhere — but with
     * no client reading the key, that refusal was the only thing enforcing it. A designer could
     * photograph twenty-five motifs onto a twenty-photograph gallery, watch every one import into the
     * draft, sync, and have the field refused with the work already done and nothing saying which
     * five to drop. Worse on a handset than on the web: the import has already copied the bytes into
     * the workshop's media directory by then.
     *
     * NOT PART OF `registry_version()`, so a field gaining or losing a cap does not invalidate
     * cached drafts. That is the server's call and is correct — the value already stored is still
     * the value stored — but it means a phone that has not refetched enforces the PREVIOUS cap, and
     * the server is the authority either way.
     */
    val maxItems: Int = 0,
    /**
     * HOW MANY ENTRIES THE REGISTRY SAYS THIS FIELD MUST HOLD — 0 meaning "none declared", which is
     * NO FLOOR and never a default.
     *
     * `field_to_dict` emits `minItems` only for a field that declares one, exactly as it does
     * `maxItems`, so the absence carries meaning here too — just the opposite meaning. Read it
     * through [dwDeclaredMinItems], which is the only place that asymmetry is spelled out.
     *
     * ── WHAT IT DOES, AND THE THREE THINGS IT EMPHATICALLY DOES NOT ──────────────────────────
     *
     * It makes the field REQUIRED for scoring: [computeStageCompleteness] counts a floored field in
     * `requiredTotal` whatever tier it sits at, and a gallery short of its floor is not "filled".
     * That is the whole of the owner's "all 25 are required" on this client, and it is the whole of
     * it on the server too — `stage_completeness` is the ONLY place a floor is enforced anywhere.
     *
     * IT REFUSES NO SAVE, AND MUST NOT BE MADE TO. Not in [DwValues.coerce], not in the stage payload
     * builder, not on the wire. A designer in a village with twenty good photographs and no signal
     * has no body that satisfies a floor of twenty-five, and `saveOrQueue` DROPS a 4xx rather than
     * queueing it — so a floor on any write path would not delay that day's work, it would destroy
     * it, on stage 4 of a twenty-two-stage flow. The server records the same reasoning above
     * `FieldSpec.min_items` and reached it by measuring both loss paths rather than by argument.
     *
     * IT IS NOT [FieldDto.required]. That flag is what `validate_entry` enforces and what a submit
     * can be refused over; this one is scored and never validated. Two concepts, kept apart on
     * purpose, and folding them together here would put a floor onto exactly the write path the
     * paragraph above refuses.
     *
     * AND IT IS NOT A CEILING. Both motif galleries declare 25 for BOTH, which makes them look
     * interchangeable and they are not: [maxItems] is trimmed against before an import copies a byte,
     * this is counted after.
     */
    val minItems: Int = 0,
    /**
     * THE SERVER WILL KEEP ONLY THE MASK OF AN IDENTITY NUMBER IN THIS FIELD, WHATEVER IS SENT.
     *
     * `coerce_value` on the server replaces this field's value with `mask_aadhaar(...)` —
     * "XXXX XXXX 9012" — on every save, under an owner decision of 2026-08-24 that both of an
     * artisan's identity numbers cross into a design report masked to their last four digits.
     * Declared on `participant.aadhaarNumber` and nothing else in today's registry.
     *
     * DECODING IT IS NOT OPTIONAL ON THIS CLIENT, AND THE REASON IS THIS CLIENT SPECIFICALLY.
     * `DwIdentityOcr.isIdentityNumberField` matches PER FIELD rather than picking one, and
     * `identityKindFor` answers AADHAAR for that key, so this handset mounts a Verhoeff-checked
     * on-device card reader on the box — the only client that can produce a full twelve-digit number
     * there in a single tap. A phone that did not know about this flag would write those twelve
     * digits into a draft, print them on the on-device report, show them to the designer as their
     * saved answer, and be silently overruled on the next sync. So [StageSchemaStore] masks at the
     * same point the server does (see [scalarText]) and `FieldRenderer` says so on the box.
     *
     * It is part of `registry_version()` for the same reason: a field GAINING the flag has to
     * invalidate cached drafts, or a phone with no signal goes on promising to keep what it cannot.
     */
    val storeMasked: Boolean = false,
    /**
     * THE SHAPE THIS FIELD'S TEXT HAS TO HAVE — "EMAIL", "PHONE_IN", "AADHAAR", "PEHCHAN",
     * "PINCODE" — or "" when the registry declares none.
     *
     * `coerce_value` enforces it in the scalar-text arm, between the `max_length` check and the
     * `store_masked` mask, and REFUSES a value that does not match rather than storing it. So this
     * is the one key on [FieldDto] that lets this handset say the server's "no" at the moment the
     * value is typed, which is the whole reason [DwValues] exists.
     *
     * DECODED AS A STRING AND NOT AN ENUM, deliberately, and it is the same choice [reportRole] and
     * [derivedKind] make. A server one release ahead can name a format this build has never heard
     * of; as an enum that is a decode failure that blanks the field — or, with
     * `coerceInputValues`, silently becomes the first member and enforces the WRONG rule. As a
     * string it is a token [DwTextFormats.error] has no entry for, and it answers null: the value
     * goes up and the server refuses it by name. See that function on why "enforce nothing" is the
     * safe direction and "guess a rule" is not.
     *
     * IT IS PART OF `registry_version()`, so a field GAINING a format invalidates cached drafts —
     * exactly as [storeMasked] and [derivedKind] do, and for exactly the same reason: a phone with
     * no signal that does not know a field gained a rule goes on accepting values the server will
     * now refuse, and the designer meets the refusal a fortnight later as a card about a stage they
     * finished in another district.
     */
    val format: String = "",
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
 * server will coerce or drop it, and every other field in the registry keeps working. (NO COUNT:
 * this said "the other 495" until 2026-08-26, when the bundled asset held 635 — a figure that has
 * moved with every registry edit since it was written, and the argument never needed it.)
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

        /**
         * The type, or NULL for a token this build has no arm for. **Only the custom-section renderer
         * may ask, and [of] must not be changed to behave like this.**
         *
         * The degrade-to-[TEXT] above is load-bearing for the REGISTRY and its KDoc says why: one new
         * type on the server would otherwise blank all 22 stages on every handset that had not
         * updated. That trade is right when the alternative is losing 495 working fields, and wrong
         * for a designer's own question, where it is the whole of the failure — an unknown token
         * reaches the `when` as TEXT and is drawn as an ordinary editable box with no note, no
         * disabled state and no caption. The designer types an answer into it, the value is stored
         * under a shape nothing downstream can read, and nobody is told. That is a silent WRONG
         * answer, which is worse than the web's blank.
         *
         * So the registry keeps its forgiving door and the custom path gets a strict one, and the
         * strict one is only ever asked about a handful of fields on one stage. See
         * [dwCustomFieldDrawable], which is the only caller.
         */
        fun known(raw: String): DwFieldType? = entries.firstOrNull { it.name == raw }
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
     *
     * A NARRATIVE IS JUDGED ON ITS TEXT, NOT ON THE PRESENCE OF ITS JSON. A rich-text document that
     * has been opened and left alone is `{"blocks":[{"kind":"PARAGRAPH","spans":[]}]}` — a non-empty
     * JsonObject holding not one word. The plain `value.isNotEmpty()` below scored that as answered,
     * while `_is_filled` (stage_schema.py) and the browser's `isFilled` (designWorkshops.ts) both
     * branch on the `blocks` key and read the text, so a required introduction left blank made the
     * phone's stage index say "complete" and the same workshop on a laptop say "outstanding". The
     * phone was the OPTIMISTIC one, which is the wrong direction: it is the surface a designer uses
     * to decide the fieldwork is done.
     *
     * The predicate is [com.designprototype.workshop.report.isEmptyDocument] and must stay that one
     * rather than a second opinion written here — the handset's own report model already uses it to
     * decide the same question about the same value, and two answers to "is this narrative empty?"
     * inside one build is how the scorer and the document disagree about a stage.
     *
     * THE `blocks` TEST IS LOAD-BEARING; do not widen it to all objects. GEO stores `{lat,lon}` and
     * the custom-answer container stores an arbitrary map, and `isEmptyDocument` parses either as a
     * document with no blocks — i.e. as empty — so a fix that dropped the key check would stop
     * counting every recorded coordinate on the device.
     */
    fun isFilled(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        is JsonPrimitive -> value.content.isNotBlank()
        is JsonArray -> value.isNotEmpty()
        is JsonObject ->
            if ("blocks" in value) !isEmptyDocument(value)
            else value.isNotEmpty()
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
            //
            // HALF_EVEN THROUGH BigDecimal, NEVER `String.format("%.2f")`. This used to be the
            // Formatter, and it disagreed with the server on ordinary typed amounts. Java's
            // `Formatter` rounds the SHORTEST decimal representation of the double half AWAY from
            // zero; Python's `f"{x:.2f}"` — which is what `coerce_value` does — rounds the exact
            // binary value half to EVEN. Measured on this repo's JDK 17 against its own interpreter:
            // 2.675 → "2.68" vs "2.67", 5.005 → "5.01" vs "5.00", 1.005 → "1.01" vs "1.00",
            // 0.045 → "0.05" vs "0.04", 12.125 → "12.13" vs "12.12". The web's MONEY input sends the
            // raw typed string and lets the server round it, so the browser gets the server's answer
            // and the phone got a different one from the same three keystrokes — permanently, because
            // the save accepts an already-formatted string verbatim rather than re-rounding it. Both
            // then feed PRODUCT derivations, so the paisa scales with quantity.
            //
            // The input to BigDecimal must be the DOUBLE, not `BigDecimal(trimmed)`: the value that
            // has to be rounded is the same binary double the server will parse out of the wire, not
            // the decimal the designer typed. `DwDerived.formatted` has done it this way since it was
            // written, for this exact reason — the two paths now agree with each other as well.
            DwFieldType.MONEY -> {
                val parsed = trimmed.replace(",", "").replace("₹", "").toDoubleOrNull()
                    ?.takeIf { it.isFinite() }
                    ?: return Coerced(null, "${field.label} is not a valid amount")
                rangeChecked(field, parsed)?.let { return Coerced(null, it) }
                Coerced(JsonPrimitive(BigDecimal(parsed).setScale(2, RoundingMode.HALF_EVEN).toPlainString()), null)
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

            /*
             * NO LOCAL EMAIL RULE ANY MORE — AND DELETING IT IS THE POINT OF THE CHANGE.
             *
             * This arm used to read `!trimmed.contains('@') || trimmed.startsWith('@') ||
             * trimmed.endsWith('@')` and answer "${'$'}{field.label} is not a valid email address".
             * That was THE THIRD ANSWER to what a valid email address is: stricter than the browser
             * (which checked nothing at all on a stage box) and stricter than the server (which
             * checked nothing anywhere, on either path). So this handset refused addresses the
             * browser accepted, and the repository accepted addresses this handset had refused.
             *
             * The rule now arrives as a DECLARATION on the field — `text_format = EMAIL` — and is
             * answered by [DwTextFormats.emailError], which is the same rule and the same sentence as
             * `lib/textFormats.ts` and `contact_formats.py`. It is enforced in [scalarText] below,
             * with the other four formats, in the same branch and the same order as the server. So
             * EMAIL keeps no arm of its own here: falling through to `else` is what makes the type
             * carry nothing but the keyboard, which is all it should ever have carried.
             */
            DwFieldType.URL ->
                // Deliberately permissive: a field officer types "dch.gov.in" and meaning it is the
                // point, not the scheme. Only something that is plainly not a locator is refused.
                if (trimmed.contains(' ')) {
                    Coerced(null, "${field.label} must not contain spaces")
                } else {
                    scalarText(field, trimmed)
                }

            else -> scalarText(field, trimmed)
        }
    }

    /**
     * `coerce_value`'s SCALAR-TEXT ARM, ported in the server's own order: length, then format, then
     * mask.
     *
     * RENAMED FROM `lengthChecked`, because that name had already stopped being true when the mask
     * landed and would now be lying about two things. The three steps are one door — the server says
     * so of its own — and the order between them is argued below rather than left to look incidental.
     */
    private fun scalarText(field: FieldDto, text: String): Coerced {
        // LENGTH FIRST. An over-long answer keeps its OWN refusal rather than being handed to a
        // format rule that would report a different fault, or — worse, on a `storeMasked` field —
        // being quietly shortened into a plausible mask. Same ordering, same reason, as the server.
        if (field.maxLength > 0 && text.length > field.maxLength) {
            return Coerced(null, "${field.label} is longer than ${field.maxLength} characters")
        }

        /*
         * FORMAT SECOND, AND BEFORE THE MASK — WHICH IS FORCED, NOT MERELY TIDY.
         *
         * [ArtisanIdentity.mask] takes the last four characters of ANYTHING once separators come
         * off. So with the two steps the other way round, a typed "hello world 1234" normalises to
         * fourteen characters, sails under `max_length = 20`, and becomes "XXXX XXXX 1234" BEFORE any
         * rule looks at it — at which point it is a well-formed mask and nothing downstream can tell
         * it from a real Aadhaar number. Reversing this ordering does not weaken the check; it
         * MANUFACTURES the exact defect the check exists to prevent, in the shape of a government
         * identity number, in a document submitted to a ministry.
         *
         * That is also why `_validate_registry` on the server refuses `store_masked = true` without
         * `text_format = AADHAAR`: unqualified, `store_masked` is `mask(anything)`.
         */
        DwTextFormats.error(field.format, text)?.let { return Coerced(null, it) }

        // THE MASK IS APPLIED HERE BECAUSE THE SERVER APPLIES IT HERE — same branch, same order,
        // after the length check and not before it. See [FieldDto.storeMasked]: this is a port of
        // `coerce_value`'s scalar-text arm, and a port that skipped the transform would leave the
        // phone showing twelve digits as the designer's saved answer while the repository held
        // four. `ArtisanIdentity.mask` is character-for-character the server's `mask_aadhaar`
        // (both keep the last four, X out the rest, and mask anything shorter than four whole),
        // and it is idempotent, so re-coercing a value hydration already masked changes nothing.
        //
        // A null answer means the value normalised to nothing, which `text` cannot be — it is
        // non-blank and trimmed by the caller — but reading a null as "clear the field" rather
        // than "leave it alone" is the direction that loses data, so it is spelled out.
        val stored = if (field.storeMasked) ArtisanIdentity.mask(text) ?: text else text
        return Coerced(JsonPrimitive(stored), null)
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
     * A value arriving from a chosen REFERENCE RECORD, resolved into the form [field] may legally
     * hold — or null, meaning the box is left blank for the designer to answer.
     *
     * ── WHY HYDRATION MAY NOT SKIP THE COERCION THE TYPED PATH GOES THROUGH ─────────────────────
     *
     * `hydrate_entries` (design_workshops.py) runs every value it copies through `coerce_value`
     * before storing it, with the reason written beside the call: "a value the target field cannot
     * legally hold — a product type that is not one of the workshop's categories — is dropped
     * rather than written. A rejected hydration leaves the field blank for the designer to answer,
     * which is recoverable; a token no client can render is not."
     *
     * THIS SURFACE HAD NO SUCH STEP. [com.designprototype.workshop.ui.designworkshop.hydratedValues]
     * wrote the reference model's raw value into the row verbatim, so the phone and the server
     * stored DIFFERENT THINGS from the same pick — and cross-surface divergence in what a record
     * carries is the defect class this repository has been bitten by most often. Concretely:
     *
     *  - ENUM. `REFERENCE_MODELS["ProductDocumentation"].data["category"]` is a MAPPED token and the
     *    map is deliberately partial, because ProductType and PRODUCT_CATEGORY answer two different
     *    questions. The server drops a token the registry's enum does not contain. The phone WROTE
     *    it, and then [validate] marked the row red — "…is not one of the options" — on a value the
     *    designer never typed and cannot correct, because the dropdown does not offer it either.
     *    [validate]'s own comment says "a picker cannot produce an out-of-range enum"; a picker
     *    cannot, and a hydration can, which is precisely how that comment came to be wrong.
     *  - RICH_TEXT. The server normalises through the rich-text model, so a plain string is stored
     *    as a document. The phone stored the bare string — and the phone builds its OWN report,
     *    offline, with no server to correct it, so the copy handed to a visiting officer at the
     *    close of the workshop would be rendered from a value the renderer was never given.
     *  - INT / DECIMAL / MONEY. Several source values are free metadata rather than typed columns
     *    (`Artisan.experienceYears` is read out of `extraMetadata`), so "12 years" is a real answer
     *    to find in one. The server drops it; the phone wrote the string into a field typed INT.
     *
     * None of that cost much while `REFERENCE_HYDRATION` held 27 pairs across seven pickers, which
     * is exactly why it was worth fixing before the widening rather than after. It now holds 81
     * across eight, and among the new pairs are three more ENUM targets (`existingProduct.recordType`
     * on PRODUCT_TYPE, `marketDemand` on DEMAND_LEVEL, `tool.maker` on MAKER_TYPE — every one of
     * them mapped from a Prisma enum whose token set is NOT the registry's), two BOOL targets, a GEO
     * target and nine DATE/DECIMAL ones. Every pair added is one more chance for the two surfaces to
     * store different things from the same pick.
     *
     * DROPPING IS FAIL-CLOSED, IT IS THE SERVER'S RULE, AND IT IS WORTH NAMING WHAT IT COSTS. This
     * build's enum list can be OLDER than the value's source: the picker's records come off the
     * network while the registry may still be the bundled asset, and a token ADDED to an enum does
     * not move `registry_version()` (the digest covers the enum's NAME, not its members — see
     * `test_the_bundled_android_asset_is_the_registry_it_claims_to_be`, which lists "an enum gains a
     * token" among the eleven changes that move the asset and leave the version identical). In that
     * window the phone leaves a box blank the server would have filled, and the server fills it on
     * the next save regardless. The alternative — writing a token no dropdown on this device can
     * draw — leaves a control showing nothing over a stored value, which is worse and, unlike a
     * blank, does not correct itself.
     *
     * DO NOT "SIMPLIFY" THIS INTO [coerce]. [coerce] answers a different question — what a TEXT BOX
     * a human is typing into should store — and it reports an error message meant to be drawn under
     * that box. Here there is no box being typed into and no one to show a message to, so the only
     * two answers are the value and nothing.
     */
    fun coerceHydrated(field: FieldDto, raw: JsonElement): JsonElement? {
        if (!isFilled(raw)) return null
        val type = DwFieldType.of(field.type)

        // A gallery seeded from the record's one photograph, or a TAGS box. The scalar is wrapped
        // rather than refused, which is `hydrate_entries`' own line: `if target.type.is_multi and
        // not isinstance(value, (list, tuple)): value = [value]`. Writing the bare string into an
        // IMAGE_LIST would leave a media field holding something no renderer here can read.
        if (type.isMulti) {
            val items = (raw as? JsonArray ?: JsonArray(listOf(raw)))
                .map { text(it).trim() }
                .filter { it.isNotBlank() }
            if (items.isEmpty()) return null
            // THE WHOLE VALUE IS REFUSED WHEN ANY TOKEN IS UNKNOWN, not the offending token alone.
            // `coerce_value`'s MULTI_ENUM arm returns an error for the list rather than filtering
            // it, and the two are not the same outcome: half a market-channel answer is a claim
            // about where a product is sold that nobody actually made.
            if (type == DwFieldType.MULTI_ENUM && field.options.isNotEmpty() &&
                items.any { token -> field.options.none { it.value == token } }
            ) return null
            // AND THE CEILING, REFUSED WHOLE FOR THE SAME REASON AS THE UNKNOWN TOKEN ABOVE.
            //
            // This was the FOURTH write path that could put an over-cap array into a draft, and the
            // last one closed (2026-08-26). It is the quietest of the four because it neither appends
            // nor asks: hydration REPLACES the field from a referenced record, and that record's own
            // gallery answers to its own cap, so a source holding fifty photographs can reach a target
            // declared at twenty without anybody choosing anything.
            //
            // REFUSED RATHER THAN TRIMMED, which is the opposite of what the capture card does, and
            // deliberately. `hydrate_entries` says of this exact case that "a value `coerce_value`
            // rejects is not written and is not stamped, so the provenance map can never claim
            // authorship of a field that stayed blank" — so the server's answer here is a blank field,
            // and a handset that instead stored a trimmed copy would hold a value the server refused,
            // attributed to a record it no longer matches, until the next save lost the whole field.
            // The card trims because a designer is standing there choosing files and can be told what
            // did not fit; nothing is being chosen here, so there is nobody to tell.
            if (items.size > dwEffectiveMaxItems(field.maxItems)) return null
            return JsonArray(items.map { JsonPrimitive(it) })
        }

        // A media id: nothing to coerce and nothing this function may reshape. An IMAGE field holds
        // an id, and reformatting an id is how you lose the file.
        if (type.isMedia) return raw

        // A coordinate is passed through UNCHANGED but only if it is a coordinate at all.
        //
        // `coerce_value`'s GEO arm refuses a non-dict outright ("must be a coordinate") and then
        // range-checks lat, lon and accuracy. The range checks are NOT repeated here on purpose:
        // the object was built by the server out of a Location row it had already validated, and a
        // second opinion about a bound is how two ports come to disagree. What IS repeated is the
        // shape check, because that is the one failure a client can be handed and cannot survive —
        // a bare string where the map picker expects `{lat,lon}` draws nothing and offers no way to
        // fix it. `participant.subjectLocation` became a hydration target with the artisan widening,
        // so this arm now carries real traffic rather than standing by.
        if (type == DwFieldType.GEO) return raw as? JsonObject

        // Normalised through the rich-text model exactly as `coerce_value`'s RICH_TEXT arm does, so
        // a plain string arrives as unformatted prose in the document shape every reader on this
        // device expects, and an empty document arrives as nothing at all rather than as a value
        // that reads as filled to [isFilled] and prints as a blank line.
        if (type == DwFieldType.RICH_TEXT) {
            val doc = richTextFromJson(raw)
            return if (doc.isEmpty) null else richTextToJson(doc)
        }

        // Everything else goes through the same arms a typed answer goes through — which is what
        // makes a MONEY value land two-place and HALF_EVEN, matching the server to the paisa, and a
        // number outside a declared range get dropped instead of stored.
        return coerce(field, text(raw)).value
    }

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
        checkConditional(entity, data, errors)
        return errors
    }

    /**
     * Fields that become required once another field on the SAME entity is filled in, as
     * `entity key -> (the dependent field, the fields that trigger it)`.
     *
     * The phone's copy of `stage_schema._CONDITIONALLY_REQUIRED`, and it must stay a copy: the
     * server applies this rule on every save of every entry, so a rule the handset does not know is
     * a refusal the designer only meets when the phone next finds signal — a fortnight later, as a
     * card about a stage they finished in another district. The whole reason [DwValues] exists is to
     * say the same "no" the server would say, at the moment the value is typed.
     *
     * ONE ENTRY, AND DELIBERATELY A TABLE RATHER THAN A RULE ENGINE — matching the server's shape
     * exactly, so the two can be diffed by eye. The registry declares fields; a requirement that
     * depends on ANOTHER answer is the one thing a flat field list cannot express, and inventing a
     * general conditional grammar here would put a second declaration of the rule on the device,
     * which is the drift `DwDerived`'s KDoc argues against at length. Adding the next rule means
     * adding one line here and one line in `_CONDITIONALLY_REQUIRED`, and nothing else.
     */
    private val CONDITIONALLY_REQUIRED: Map<String, Pair<String, List<String>>> = mapOf(
        "outcomes" to ("countOverrideReason" to listOf("designsCountOverride", "prototypesCountOverride")),
    )

    /**
     * Apply [CONDITIONALLY_REQUIRED], mirroring `stage_schema._check_conditional` line for line —
     * including the wording of the message, so the inline mark the designer reads offline is the
     * same sentence the refusal card would carry.
     *
     * NOT GATED ON `enforceRequired`, and that asymmetry is the point rather than an oversight. An
     * ordinary required field may sit empty overnight — that is the normal state of a 40-field stage
     * — but this one is only ever triggered by a figure the designer has JUST typed, and that figure
     * overrides the record on the report's front page. A number that contradicts the record needs
     * its reason in the same breath. `_check_conditional` is called outside the `enforce_required`
     * branch on the server (stage_schema.py:1151) for exactly this reason; moving it under
     * [enforceRequired] here would silently switch the rule off, because no client ever sets
     * `submit` true (StageScreen's push hardcodes it false), so `enforceRequired` is never on.
     */
    private fun checkConditional(
        entity: EntityDto,
        data: Map<String, JsonElement>,
        errors: MutableMap<String, String>,
    ) {
        val (dependent, triggers) = CONDITIONALLY_REQUIRED[entity.key] ?: return
        // Already answered, or already carrying a problem of its own — the server bails on both, and
        // stacking a second message onto a field that failed coercion would replace the specific
        // complaint ("is not a valid amount") with a vaguer one.
        if (isFilled(data[dependent]) || errors.containsKey(dependent)) return
        if (triggers.none { isFilled(data[it]) }) return
        // The dependent field may be absent from an older registry this build is still running on;
        // an entity that cannot show the box must not be told to fill it in.
        val spec = entity.field(dependent) ?: return
        val labels = triggers.mapNotNull { entity.field(it)?.label }
        errors[dependent] = "${spec.label} is required once ${labels.joinToString(" or ")} is filled in."
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
    /**
     * Labels of the required fields nothing was recorded in — plus any field short of its declared
     * [FieldDto.minItems], which is filed WITH ITS COUNT.
     *
     * "Traditional motif photographs (20 of 25)" and not the bare label, because the bare label
     * would be a lie by omission: every other entry in this list means "nothing was recorded", and a
     * designer reading that about a gallery holding twenty photographs concludes the app has lost
     * them. See [dwShortfallLabel], which is the only place the count may be said.
     */
    val missing: List<String>,
) {
    /**
     * Progress across the fields this stage counts as required, matching `StageCompleteness.percent`.
     *
     * "REQUIRED" IS NO LONGER THE SAME SET AS "BASIC-TIER", and this docstring said it was until
     * 2026-08-28. A declared [FieldDto.minItems] makes a field required for scoring at whatever tier
     * it sits at, and both fields that declare one today are STANDARD — so a stage can now be
     * incomplete over a field a BASIC-only reading of this number would never have counted.
     *
     * A stage with nothing required reads as 100 rather than as 0. Dividing by zero to decide whether
     * a designer may submit is how a stage becomes permanently unsubmittable.
     */
    val percent: Int get() = if (requiredTotal == 0) 100 else Math.round(100f * requiredFilled / requiredTotal)

    val isComplete: Boolean get() = requiredFilled >= requiredTotal
}

/**
 * Whether a declared [FieldDto.minItems] floor is reached. True for every field that declares none.
 *
 * A NON-LIST IS SHORT, NOT EXEMPT. A gallery holding a single bare media id rather than a list is a
 * client bug, and answering true for it would report a stage of one photograph as twenty-five
 * complete — the one wrong answer this predicate must never give. [DwValues.isFilled] is asked
 * separately and first, so "empty" and "short" stay distinguishable to the caller.
 *
 * The port of `_meets_minimum` in `backend/app/services/stage_schema.py`, arm for arm.
 */
fun dwMeetsMinimum(field: FieldDto, value: JsonElement?): Boolean {
    if (field.minItems <= 0) return true
    val array = value as? JsonArray ?: return false
    return array.size >= field.minItems
}

/**
 * The name a field goes into [DwStageCompleteness.missing] under — with its count, when it declares
 * a floor.
 *
 * The port of `_shortfall_label`, and it must stay character-for-character identical to it and to
 * the web's `scoreStageData`, for two reasons that are both other people's screens:
 *
 *  * THIS EXACT STRING IS WHAT THE READINESS ADDRESS WALK MATCHES ON. [DwSubmissionReadiness] keys
 *    its "where do I go to fix this" map by the label the scorer files, so a decoration applied here
 *    and not there costs the item its link and drops a designer on the stage with nothing focused.
 *  * IT IS PRINTED VERBATIM in the report's warning line, in the completeness annexure's Outstanding
 *    column and on both readiness screens. Two clients wording one shortfall differently is two
 *    accounts of one gallery.
 *
 * [DwStageCompleteness.missing] IS DE-DUPLICATED BY LABEL, so a collection field declaring a floor
 * would file one entry per DISTINCT count rather than one per field. No collection field declares
 * one today — both are singleton galleries — and the count is worth more than the collapsing when
 * one does; a future floor on a repeating row should read this before assuming the old shape.
 */
fun dwShortfallLabel(field: FieldDto, value: JsonElement?): String {
    if (field.minItems <= 0) return field.label
    val held = (value as? JsonArray)?.size ?: 0
    return "${field.label} ($held of ${field.minItems})"
}

/**
 * Does this field count toward the stage's required total?
 *
 * A DECLARED FLOOR MAKES THE FIELD REQUIRED AT WHATEVER TIER IT SITS AT. Without this the whole
 * feature scores nothing: both motif galleries are optional STANDARD fields, so a minimum would only
 * move `optionalFilled`, and [DwStageCompleteness.isComplete] — which is `requiredFilled >=
 * requiredTotal` and nothing else — would stay true at twenty-four of twenty-five.
 *
 * `||` AND NOT A SECOND COUNTER, so a field that is both `required` and floored is counted ONCE.
 * Neither of the two is today; a future one counted twice would make its stage report 49 of 50 with
 * one box outstanding.
 */
fun dwCountsAsRequired(field: FieldDto): Boolean = field.required || field.minItems > 0

/**
 * Score one stage from the data held on this device, mirroring `stage_completeness`.
 *
 * A COLLECTION contributes its required fields ONCE PER EXISTING ROW and contributes nothing while it
 * is empty. An empty sketch list on day one of a workshop is a legitimate state, not a deficiency,
 * and scoring it as a deficiency would leave every stage stuck below 100% until every optional list
 * had been populated.
 *
 * A GALLERY BELOW ITS DECLARED [FieldDto.minItems] IS NOT FILLED, and this function is the ONLY
 * place on this client where that sentence is true — a minimum is scored and never validated, so no
 * save path here or on the server can refuse the twenty photographs a designer has so far. See
 * [FieldDto.minItems] for why a floor on a write path would destroy a village day's work on this
 * client specifically.
 */
fun computeStageCompleteness(
    stage: StageDto,
    singleton: Map<String, JsonElement>,
    collections: Map<String, List<Map<String, JsonElement>>>,
    /**
     * The designer's own questions for this stage, retired ones included —
     * [customFieldsForStage] resolves them off [DwCustomSectionStore]'s cache.
     *
     * Mirrors `stage_completeness(..., custom_fields=, custom_values=)` argument for argument, and
     * the web's `scoreStageData` in the same order, because a required custom question counted on one
     * surface and not on another is a stage that reads 100% on the form and 422s on submit.
     *
     * DEFAULTED EMPTY, WHICH IS THE HONEST DEFAULT AND NOT A CONVENIENCE. A caller with no definition
     * to hand scores the stage exactly as it did before this feature existed — it cannot invent a
     * higher total and it cannot invent a lower one.
     */
    customFields: List<DwCustomFieldDto> = emptyList(),
    customValues: Map<String, JsonElement> = emptyMap(),
): DwStageCompleteness {
    var requiredTotal = 0
    var requiredFilled = 0
    var optionalTotal = 0
    var optionalFilled = 0
    val missing = ArrayList<String>()

    stage.singleton?.liveFields?.forEach { field ->
        val value = singleton[field.key]
        // BOTH TESTS, IN THIS ORDER. `isFilled` answers "was anything recorded"; `dwMeetsMinimum`
        // answers "is it enough". A gallery of twenty is filled and short, and only the second test
        // can tell it from a gallery of twenty-five — while only the first can tell an EMPTY gallery
        // from either, which is what keeps the sentence [dwShortfallLabel] writes honest.
        val filled = DwValues.isFilled(value) && dwMeetsMinimum(field, value)
        if (dwCountsAsRequired(field)) {
            requiredTotal++
            if (filled) requiredFilled++ else missing.add(dwShortfallLabel(field, value))
        } else {
            optionalTotal++
            if (filled) optionalFilled++
        }
    }

    /*
      THE DESIGNER'S OWN QUESTIONS, SCORED BETWEEN THE STAGE'S SINGLETON AND ITS COLLECTIONS.

      BETWEEN, AND NOT AFTER, AND THE ORDER IS NOT COSMETIC. `missing` is printed in order and
      truncated — the report screen prints `missing.take(3)` per stage and the completeness annexure
      prints the same three in its Outstanding column — so whatever this list puts first is what a
      designer and a ministry officer actually read. Between the stage's own fields and its repeating
      rows is the order the questions appear on the form, and it is `stage_completeness`' order to the
      line (`services/stage_schema.py:1323-1352`).

      FILED UNDER THE BARE LABEL, like a singleton field and unlike a collection field, which files
      `"${entity.title}: ${field.label}"` below. That is what makes a duplicate label a
      definition-time refusal rather than a document disagreeing with itself: two required questions
      filing the same string collapse into one row through the de-duplication at the end while
      `requiredTotal` still counts two. `custom_sections.validate_definition` refuses exactly that
      pair, and this is the arithmetic it is protecting.

      A RETIRED FIELD IS SKIPPED, exactly as `liveFields` skips a deprecated registry field and for
      its reason: it is no longer asked, so counting it would make a stage permanently incomplete
      because of a question the designer corrected.

      KEY BY KEY, AND THE CONTAINER IS NEVER TESTED AS A WHOLE. `DwValues.isFilled` answers true for
      any JsonObject with keys, so a bucket holding twenty blank answers is truthy — a stage would
      report itself complete on the strength of the bucket existing.
    */
    customFields.forEach { field ->
        if (field.retired) return@forEach
        val filled = DwValues.isFilled(customValues[field.key])
        if (field.required) {
            requiredTotal++
            if (filled) requiredFilled++ else missing.add(field.label.ifBlank { field.key })
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
                val value = row[field.key]
                val filled = DwValues.isFilled(value) && dwMeetsMinimum(field, value)
                if (dwCountsAsRequired(field)) {
                    requiredTotal++
                    if (filled) requiredFilled++
                    else missing.add("${entity.title}: ${dwShortfallLabel(field, value)}")
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
    /**
     * This workshop's custom definition as this device holds it, or null when it holds none.
     *
     * THE SIGNATURE GAINS ONE ARGUMENT AND THAT IS WHAT TEACHES EVERY READER AT ONCE. The stage
     * index, the submission-readiness assembly and the report all fan out through this function, so
     * threading the per-stage field lists through each of them instead would mean three call sites
     * each deciding for themselves whether to pass a definition — and the one that forgot would be a
     * second arithmetic on the same screen.
     *
     * NULL SCORES EXACTLY AS THIS FUNCTION DID BEFORE THE FEATURE EXISTED. That is the honest answer
     * for a device that has never read the definition: it must not invent a required total it cannot
     * see, and it must not invent a lower one either — which is why [StageIndexScreen] refuses the
     * SERVER's score rather than adopting a number computed under a definition this device lacks.
     */
    definition: DwCustomCache? = null,
): List<DwStageCompleteness> = schema.stages.sortedBy { it.number }.map { stage ->
    val stored = draft?.stages?.get(stage.key)
    computeStageCompleteness(
        stage = stage,
        singleton = stored?.values.orEmpty(),
        collections = stage.collections.associate { entity ->
            entity.key to stored?.rowsFor(entity.key).orEmpty().map { it.values }
        },
        customFields = customFieldsForStage(definition, stage.key),
        customValues = stored?.custom.orEmpty(),
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
    /*
      WHETHER THIS WORKSHOP'S RECORDINGS MAY LEAVE THE DEVICE — plan §6 answer 3, three keys added to
      `workshop_summary` by `dictation_consent.consent_keys`.

      Read by the phone so a workshop whose consent was recorded on the web, or on a colleague's
      handset, gates and un-gates dictation here too. The token is always one of the three enum values
      and never null on a server that has the column; a build talking to a server that predates it
      simply sees the default, which is the same fail-closed answer as never having been asked. The
      reading is [dwTier3ConsentOf]'s, so an unrecognised token gates rather than reaching the ladder
      as a state no sentence exists for.

      `dictationConsentByName` is deliberately absent: the server resolves that one in the
      single-record read alone, because looking a display name up per row would put a query per
      workshop into a paged list to print something the list does not show.
    */
    val dictationConsent: String = DW_CONSENT_NOT_RECORDED,
    /** When the ARTISAN answered, which on this fleet can be a fortnight before the server heard it. */
    val dictationConsentAt: String? = null,
    val dictationConsentById: String? = null,
)

@Serializable
data class DesignWorkshopPageDto(
    val items: List<DesignWorkshopDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
    val pages: Int = 0,
)

/**
 * `GET /design-workshops/default-for-me` — the workshop every record form opens on.
 *
 * ── "NONE" IS AN ANSWER AND NOT A FAILURE ────────────────────────────────────────────────────────
 *
 * A newly onboarded designer is on no workshop, which is ordinary, and the endpoint answers 200 with
 * [workshopId] null rather than 404 — because the callers are dropdowns filling in a default, and a
 * 404 would arrive at seven forms as a failure to report, each of which would then have to learn
 * that this particular failure means "nothing to prefill". That is how an empty answer comes to be
 * drawn as a broken screen, which is the defect class this repository keeps un-shipping.
 *
 * ── [reason] EXISTS SO THE PREFILL CAN EXPLAIN ITSELF ───────────────────────────────────────────
 *
 * A dropdown that fills itself in and cannot say why reads as a bug. The two doors need different
 * sentences: `"GRANTED"` is "the workshop you were most recently added to" — which sends a designer
 * looking for an allocation — and `"CREATED"` is "the one you opened most recently", which does not.
 * Null only when there is no default at all, so a client cannot print either word over an empty box.
 */
@Serializable
data class DesignWorkshopDefaultDto(
    val workshopId: String? = null,
    val title: String? = null,
    /** ISO. When access began: the grant's own timestamp, or the workshop's creation. */
    val accessAt: String? = null,
    /** `"GRANTED"`, `"CREATED"`, or null. See the class note. */
    val reason: String? = null,
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
 * WHO LAST SET ONE FIELD. The server's stamp, read-only on this device.
 *
 * [source] is the whole point of the model and has exactly two values, both wire format:
 *
 *  * `"reference"` — the value was COPIED onto this row from a shared canonical record (an artisan,
 *    a documented tool) when the designer picked it. [by] is then THAT RECORD'S author, not the
 *    designer who chose it, and [refModel]/[refId] name the row it came from. This is what "the
 *    provenance stays with the original author" means on a handset screen.
 *  * `"designer"` — somebody working on this workshop typed or changed it, and [by] is them.
 *
 * An unrecognised value is kept verbatim rather than coerced to either: a server one release ahead
 * would otherwise have its third source silently rendered as one of these two, which is worse than
 * rendering it as unknown.
 *
 * EVERY FIELD DEFAULTS, INCLUDING [by]. A stamp is sparse by design — a field nobody recorded an
 * author for carries no entry at all, and the ones that do exist may name an account that has since
 * been deleted, in which case the server sends the id with no [byName]. Both are ordinary states
 * and neither is an error: see `app/services/entry_provenance.py`, which explains why fabricating a
 * name for either would be worse than showing none.
 */
@Serializable
data class DwFieldStampDto(
    val by: String? = null,
    val byName: String? = null,
    val at: String = "",
    val source: String = "",
    val refModel: String? = null,
    val refId: String? = null,
    val refKey: String? = null,
) {
    /** True when this value came off a shared record rather than out of this workshop. */
    val fromSharedRecord: Boolean get() = source == "reference"

    /**
     * What to show beside a field, or null when there is nothing honest to say.
     *
     * Null rather than "Unknown" for an unattributed field: eight of the twenty-two stages are full
     * of rows written before this existed, and a label reading "Unknown" on every one of them is
     * noise that trains a designer to stop looking at the label entirely — at which point it cannot
     * do its one job on the rows that DO carry an author.
     */
    fun attribution(): String? {
        // TRIMMED, AS THE WEB TRIMS IT (`stamp.byName?.trim()` in `FieldProvenance.tsx`). `User.name`
        // is free text an admin typed, so padding reaches this column, and untrimmed it renders as
        // "by  Meena Iyer " — a double space and a trailing one, in the label whose whole job is to
        // name a person properly.
        val byName = this.byName?.trim()
        if (fromSharedRecord) {
            // A reference stamp that names NEITHER a record nor a person has nothing concrete to
            // say, and "From the linked record" is a vague sentence pretending to be a fact. The
            // server always sets refModel/refId/refKey when hydration writes a value, so this is not
            // a state it produces — but a build one release ahead might, and silence is the honest
            // fallback. `DwFieldProvenanceWireTest` pins it.
            if (refModel.isNullOrBlank() && byName.isNullOrBlank()) return null
            // The RECORD is the subject, and the person — when there is one — is whoever recorded
            // THAT record, one table away. Never phrased as an edit to this field: `by` on a
            // reference stamp is the canonical record's author and has typically never opened this
            // workshop, so "Edited by Sita Devi" would be a sentence about somebody who did not do
            // it. The web made exactly that mistake once; see FieldProvenance.tsx.
            val record = "From the ${recordLabel(refModel)} record"
            return if (!byName.isNullOrBlank()) "$record, by $byName" else record
        }
        // A designer stamp. No `by` at all is an unchanged value on a row written before this column
        // existed — the server had nothing to attribute, and neither has this.
        if (by.isNullOrBlank()) return null
        // An id with no name is an account that has been deleted. Saying so is more useful than
        // printing a cuid, and more honest than pretending the field was never attributed.
        val person = if (!byName.isNullOrBlank()) byName else "A former colleague"
        val day = shortDay(at)
        return if (day.isNotEmpty()) "$person, $day" else person
    }
}


/**
 * The record a hydrated value came out of, in the words a designer uses for it.
 *
 * WORD-FOR-WORD THE SAME TABLE AS THE WEB'S `recordName` in `FieldProvenance.tsx`, and the two must
 * stay identical: a designer who reads "From the artisan record" on a laptop and "From the Artisan
 * record" — or worse, "From the ProductDocumentation record" — on the handset has been shown two
 * products. A model this table does not name falls back to "linked", which is vague and true,
 * rather than to the Prisma model name, which is neither.
 */
private fun recordLabel(refModel: String?): String = when (refModel) {
    "Artisan" -> "artisan"
    "Craft" -> "craft"
    "Process" -> "process"
    "ProductDocumentation" -> "product"
    "ToolDocumentation" -> "tool"
    // THE SIXTH MODEL, ADDED 2026-08-24 WITH THE STAGE-6 BASELINE CITATION. "interview" and not
    // "questionnaire": there is exactly ONE global instrument, so what the designer picked is a
    // SITTING, and calling it a questionnaire tells them they chose a form. Without this entry the
    // eleven hydrated boxes of `artisanBaseline` all read "From the linked record" — the fallback
    // doing what it is for (an UNKNOWN model) on a model this build knows by name.
    "QuestionnaireInterview" -> "interview"
    else -> "linked"
}

/**
 * The day and month of an ISO timestamp IN THE READER'S OWN TIME ZONE, or "" for anything
 * unparseable.
 *
 * THE ZONE IS THE WHOLE POINT OF THE CONVERSION AND THIS GOT IT WRONG. The server stamps in UTC and
 * sends `+00:00`, so reading the date straight off the offset — `OffsetDateTime.parse(iso)
 * .toLocalDate()` — names the UTC day. For this product's users that is off by one for a third of
 * the day: a designer in Asia/Kolkata (UTC+5:30) who edits a field at 01:30 on 2 March is stamped
 * `2026-03-01T20:00:00+00:00`, and the handset said "1 Mar" for something they did on the 2nd. The
 * browser has always converted (`new Date(iso).toLocaleDateString`), so the same stamp read the same
 * minute said "2 Mar" on a laptop and "1 Mar" on a phone — one product, two answers, which is the
 * failure rule 3 of this port exists to prevent. Any stamp between 18:30 and 24:00 UTC hit it.
 *
 * Day and month only, matching the web: a year on every one of forty fields is noise, and where the
 * year matters the provenance view prints it in full. "" rather than an exception for a shape this
 * build does not expect — the API is entitled to send one, and a crash in a label is far worse than
 * a label without a date.
 *
 * ONE DIFFERENCE FROM THE BROWSER REMAINS, DELIBERATELY. This orders the parts day-then-month while
 * the web asks the platform for the locale's own order, so an `en-US` reader sees "8 Mar" here and
 * "Mar 8" there. Both name the same day, which is what the sentence is for; matching the ORDER would
 * mean pushing a fixed order onto the browser or a locale-driven one onto forty labels here, and
 * neither buys anything for a deployment that reads `en-IN`. The DAY had to match. The word order
 * did not.
 *
 * The bare-date fallback takes no zone: `2026-03-01` with no time names a day already, and shifting
 * it by an offset would invent one.
 */
private fun shortDay(iso: String): String {
    if (iso.isBlank()) return ""
    fun render(date: java.time.LocalDate): String =
        "${date.dayOfMonth} ${date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())}"
    return runCatching {
        render(
            java.time.OffsetDateTime.parse(iso)
                .atZoneSameInstant(java.time.ZoneId.systemDefault())
                .toLocalDate()
        )
    }.getOrElse {
        runCatching { render(java.time.LocalDate.parse(iso.take(10))) }.getOrDefault("")
    }
}

/**
 * One stage's provenance, mirroring the three data buckets of [StageBucketDto].
 *
 * [collections] is keyed by entity and then BY ENTRY ID, never by position. The three readers of
 * this data sort their rows differently — the server's `_stages_payload` sorts by `_ordinal` after
 * grouping, its report builder sorts before, and this device sorts its own draft — so a positional
 * list would be misaligned on whichever of them disagreed, and the failure is one participant's
 * edits shown against another participant's name in the table that proves who attended.
 */
@Serializable
data class DwStageProvenanceDto(
    val singleton: Map<String, DwFieldStampDto> = emptyMap(),
    /** entity key -> entry id -> field key -> stamp. Three levels, and the middle one is the point. */
    val collections: Map<String, Map<String, Map<String, DwFieldStampDto>>> = emptyMap(),
    val custom: Map<String, DwFieldStampDto> = emptyMap(),
) {
    /**
     * The stamps for one collection row, addressed by the `_entryId` the row already carries.
     *
     * Empty for a row this device created offline and has not yet synced: it has no server id, so
     * there is nothing to look up, and "not yet recorded" is the correct answer for a value that
     * has never reached the server.
     */
    fun forRow(entityKey: String, entryId: String?): Map<String, DwFieldStampDto> =
        if (entryId.isNullOrBlank()) emptyMap()
        else collections[entityKey].orEmpty()[entryId].orEmpty()
}

// --------------------------------------------------------------------------------------
// The ADMIN authorship & divergence report — `GET /design-workshops/{id}/provenance`
// --------------------------------------------------------------------------------------
//
// THE HALF OF "ADMINS SEE ALL OF IT" THAT NOTHING ELSE CAN ANSWER, and the reason it is a whole
// endpoint rather than a flag on the stage read. Every designer on a workshop already sees the
// per-field stamps above — they ride on `GET /stages` and render under each box, because knowing a
// colleague changed the price is part of working on the record. This report adds, for every value
// COPIED from a shared canonical record, what that record says TODAY beside what this workshop
// stored. That comparison is not derivable from anything else on the wire: once hydrated, a value is
// an ordinary string in `data`, and a hydrated village and a typed village are the same bytes
// DELIBERATELY (see `REFERENCE_HYDRATION`, which exists so a workshop keeps what the designer saw on
// the day). Only the `reference` stamp — which names the record and the column — plus a live read of
// that record can say "this workshop says Barpali and the artisan record now says Bargarh".
//
// DIVERGENCE IS NOT AN ERROR AND NOTHING IN THESE TYPES MAY IMPLY IT IS. A workshop is a DATED
// OBSERVATION and is supposed to keep what was captured; an artisan who moved village after the
// workshop makes every row that names them differ, and every one of those rows is CORRECT. Nothing
// here is named `problem`, `stale`, `conflict` or `error`, and nothing counts faults — the reading
// functions live in `DwProvenanceReport.kt` and are comparative throughout.
//
// THE MODELS BELOW ARE BUILT FROM `workshop_provenance` (api/routes/design_workshops.py) AND FROM
// `canonical_divergence` (services/entry_provenance.py), not from a summary of them. Two things in
// the real payload are easy to get wrong from a description and are called out on the fields
// themselves: the entry-level `canonical` is `{}` — never null — for an entry with no hydrated
// fields, and a DELETED canonical record is signalled by `recordDeleted`, NOT by a null `canonical`.

/**
 * One hydrated field's stored value beside what its canonical record says TODAY.
 *
 * ── FOUR KEYS, AND THE LAST TWO ARE NOT THE SAME QUESTION ────────────────────────────────────────
 *
 * `canonical_divergence` emits all four for every field carrying a `reference` stamp:
 *
 *   [stored]        what THIS WORKSHOP holds — the value the report prints, for ever.
 *   [canonical]     what the shared record says now, spelled through the SAME `data` lambda
 *                   hydration would use, so a masked Pehchan number is compared against a masked one.
 *   [diverged]      `source is not None and str(stored) != str(canonical)` — the two differ AND the
 *                   record is still there to differ from.
 *   [recordDeleted] the record has been deleted, so there was nothing to compare against at all.
 *
 * **[recordDeleted] IS THE DELETED-RECORD SIGNAL AND A NULL [canonical] IS NOT.** This is the one
 * distinction that must not be flattened, and flattening it is a live defect on the web:
 * `DwCanonicalComparison` in `frontend/lib/designWorkshopProvenance.ts` does not model this key at
 * all, so the page reads `canonical == null` as "the record no longer exists". A record that is
 * perfectly present and has simply had a column CLEARED — a phone number the researcher blanked —
 * also answers `canonical: null`, with `recordDeleted: false`, and the browser tells an admin that
 * artisan's record has been deleted. That is the more interesting divergence of the two (the web
 * lib's own comment says so) and it is reported as the wrong thing entirely. The handset therefore
 * keys the deleted sentence on this flag and renders a null canonical as the em dash, which is what
 * "this record says nothing here" honestly looks like.
 *
 * Both value keys are [JsonElement] because both are ARBITRARY JSON: `spec.data(...)` yields strings,
 * numbers, booleans and GEO objects depending on the column. Decoding them as `String` would coerce a
 * `{lat, lng}` into nonsense or throw on the whole report; rendering them is [dwComparisonText]'s job
 * and there is exactly one of it, so no call site has to grow a `when` over JSON shapes.
 *
 * ABSENT AND EXPLICIT-NULL BOTH READ AS `null` HERE, which is the same convention [DwValue] already
 * uses for every stage value (`null, JsonNull -> …`), and it is safe ONLY because neither means
 * "deleted": both mean "there is no value on that side", which is what the em dash says. A build that
 * moved the deleted signal onto this field would reintroduce the web's defect exactly.
 */
@Serializable
data class DwCanonicalComparisonDto(
    val stored: JsonElement? = null,
    val canonical: JsonElement? = null,
    val diverged: Boolean = false,
    val recordDeleted: Boolean = false,
)

/**
 * One stage entry's authorship: who created the ROW, who set each FIELD, and what the shared records
 * behind the hydrated fields say today.
 *
 * [createdById] is reported BESIDE the per-field answers and never instead of them, which is the
 * route's own note and the thing this feature exists to make visible: a row created by one designer
 * whose fields are now attributed to three other people.
 *
 * [canonical] IS `{}` AND NEVER NULL for an entry with no hydrated fields — the route builds it as
 * `divergence.get(row.id, {})`, and `canonical_divergence` omits an entry entirely when it has no
 * `reference` stamps. So "this row copied nothing from a shared record" is an empty map, and the
 * screen must not distinguish it from a missing key. (`coerceInputValues` on [ApiClient]'s decoder
 * would fall back to this default even if a future server did answer null, which is the behaviour
 * we want rather than a crash.)
 *
 * [fields] is the SAME [DwFieldStampDto] the stage read already carries, deliberately — one stamp
 * type on this handset, one `attribution()`, and therefore one sentence under a field name whether
 * the designer is reading it on their own stage form or an admin is reading it in this report.
 */
@Serializable
data class DwProvenanceEntryDto(
    val entryId: String = "",
    val stageKey: String = "",
    val entityKey: String = "",
    /**
     * Zero-based, as `_ordinal` is everywhere else — AND ALWAYS PRESENT, INCLUDING ON A SINGLETON.
     *
     * This was declared nullable with a comment saying null meant "a singleton row that has no
     * position". No such payload exists: `DwStageEntry.ordinal` is `Int @default(0)`, non-nullable
     * in Postgres, its own schema comment reads "A singleton's is always 0", and the route emits
     * `"ordinal": row.ordinal` unconditionally. So a singleton arrives as `0`, and code that read
     * "is there an ordinal?" as "is this a row of a list?" headed every singleton "· row 1" of a
     * list that does not exist.
     *
     * WHETHER A ROW HAS A POSITION IS THE REGISTRY'S ANSWER, NOT THIS FIELD'S — `EntitySpec
     * .cardinality`. The default stays for a decode of a truncated payload, and it is 0 because
     * that is what the column's own default is.
     */
    val ordinal: Int = 0,
    val createdById: String? = null,
    val fields: Map<String, DwFieldStampDto> = emptyMap(),
    val canonical: Map<String, DwCanonicalComparisonDto> = emptyMap(),
)

/**
 * The whole report for one workshop — every stage entry it has, in the server's order.
 *
 * EVERY ENTRY, not only the interesting ones. The endpoint reports the workshop's authorship picture;
 * deciding which rows have something to show is the reader's job and is done by [dwDivergedFields],
 * which is what lets the summary line count records and fields without a second request.
 */
@Serializable
data class DwProvenanceReportDto(
    val entries: List<DwProvenanceEntryDto> = emptyList(),
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
    /**
     * The designer's own answers for this stage — the `_custom` row's whole `data`, flat, keyed by
     * their own field keys. `_stages_payload`'s third sibling key.
     *
     * A THIRD KEY BESIDE THE OTHER TWO AND NOT NESTED INSIDE `singleton`. Eight of the twenty-two
     * stages declare no singleton entity at all (sketch development, prototype iteration, costing …),
     * which is exactly the third a designer is most likely to extend, so a container hung off the
     * singleton row could not serve them. And the answers are a row of their own on the server
     * precisely so that a client which has never heard of them cannot delete them by omission.
     *
     * Defaulted empty like everything else here, so a payload from a server predating the feature
     * reads as "no custom answers", which is the truth for it.
     */
    val custom: JsonObject = JsonObject(emptyMap()),
    /**
     * WHO LAST SET EACH FIELD of the three buckets above — `_stages_payload`'s fourth sibling key.
     *
     * The phone is a READER in this feature and not a writer: it never computes a stamp and never
     * sends one. The server recomputes the whole map on every save from the values themselves (see
     * `merge_entry_provenance`), which is deliberate — a stamp a client could set is a stamp a
     * client could forge, and this one names a researcher who is not in the room.
     *
     * WHY IT IS CARRIED HERE AT ALL, given the phone does not write it. A stage entry holds two
     * kinds of value that look identical once stored: what the designer typed, and what the artisan
     * picker COPIED off a shared record (81 field-pairs of it — see `REFERENCE_HYDRATION`). A
     * designer standing in a cluster looking at a phone-filled participant row has no way to tell
     * which is which, and the difference decides whether correcting a wrong phone number is
     * "fix my typo" or "the master record is out of date and somebody should be told".
     *
     * SIBLING OF THE DATA AND NEVER NESTED INSIDE IT, for the same reason [custom] is: a key inside
     * `singleton` that the registry does not declare is reported in `droppedKeys` on every save —
     * firing "this phone is running a newer field registry than the server" on every workshop that
     * has ever picked an artisan, which destroys the one drift signal this repository has.
     *
     * Defaulted empty, so a payload from a server predating the feature reads as "nobody recorded
     * who set these", which is the truth for it. No [WORKSHOP_DRAFT_SCHEMA_VERSION] rung is owed:
     * this is a wire model, additive and defaulted, and nothing on the draft ladder carries it.
     */
    val provenance: DwStageProvenanceDto = DwStageProvenanceDto(),
    val completeness: StageCompletenessDto? = null,
    /**
     * The digest of the custom definition the score beside it was computed under — `GET /stages/{key}`.
     *
     * Carried BESIDE the score, and the pairing is the point rather than a convenience; the route's
     * own comment says so. See [StageIndexScreen]'s adoption rule: a handset holding an older
     * definition, or none, would otherwise show the server's higher `requiredTotal` for untouched
     * stages and its own lower one for touched stages — two arithmetics in one list, with nothing on
     * screen to say why.
     */
    val customSchemaVersion: String = "",
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
    /**
     * Who pressed "New". Served all along — `GET /design-workshops/{id}` returns `workshop_summary`
     * with the stages folded in, and that helper has always carried `createdById`; this field was
     * simply never decoded.
     *
     * It is here for ONE caller, `WorkshopViewersScreen`, and it is the fact that whole screen turns
     * on. The creator holds the workshop through this column and not through a viewer row, so they
     * are held out of the picker on both sides: an admin who could untick them would be offered a
     * control that cannot do what it appears to (`_deduplicate` drops them from any payload naming
     * them), and a screen that showed every reader EXCEPT the one everybody knows has access reads
     * as a bug. Nullable because the server may answer null, and a screen that cannot name the
     * creator has to say so rather than exclude nobody and quietly offer them for removal.
     */
    val createdById: String? = null,
    val schemaVersion: String = "",
    /**
     * The digest of the custom definition every score in [completeness] was computed under.
     *
     * NEVER FOLDED INTO [schemaVersion], which is `registry_version()` and is what
     * [StageSchemaStore] compares the BUNDLED 119 KB asset against. A designer's own question must
     * not move that digest or every handset in the fleet would treat its bundled schema as stale the
     * moment anyone anywhere added a field — plan §4, constraint 2, and the reason custom definitions
     * are a separately versioned resource at all.
     */
    val customSchemaVersion: String = "",
    val stages: Map<String, StageBucketDto> = emptyMap(),
    val completeness: Map<String, StageCompletenessDto> = emptyMap(),
    /*
      WHETHER THIS WORKSHOP'S RECORDINGS MAY LEAVE THE DEVICE — the same three keys as
      [DesignWorkshopDto], because this payload is `workshop_summary` with the stages folded in, plus
      the one key only the single-record read resolves.

      Decoded HERE as well as on the list row because this is the payload the workshop's own screen
      reads, and that screen is where the answer is recorded. A consent given on the web, or on a
      colleague's handset, reaches this phone through these keys and nowhere else.
    */
    val dictationConsent: String = DW_CONSENT_NOT_RECORDED,
    val dictationConsentAt: String? = null,
    val dictationConsentById: String? = null,
    /**
     * Whoever recorded the current answer, by name.
     *
     * Resolved by the SINGLE-RECORD read alone — the list deliberately carries only the id, because a
     * display-name lookup per row would put a query per workshop into a paged endpoint to print
     * something the list does not show. Null is a real state and not an absence of information: the
     * pointer is `SetNull`, so a workshop can legitimately carry an answer with no name against it once
     * that account has been removed, and the honest rendering of that is to say nothing rather than to
     * put somebody else's name against it.
     */
    val dictationConsentByName: String? = null,
)

@Serializable
data class StageListDto(
    val stages: Map<String, StageBucketDto> = emptyMap(),
    val completeness: Map<String, StageCompletenessDto> = emptyMap(),
    val schemaVersion: String = "",
    /** See [DesignWorkshopDetailDto.customSchemaVersion]. Never folded into [schemaVersion]. */
    val customSchemaVersion: String = "",
)

@Serializable
data class DesignWorkshopCreateBody(
    val title: String,
    val templateId: String = "DCH_STANDARD",
    /**
     * WHAT KIND OF WORKSHOP THIS IS — one of the registry's `WORKSHOP_KIND` tokens, or null.
     *
     * ── IT IS NOT THE REPORT TEMPLATE, AND THAT IS WHY IT SITS HERE ─────────────────────────────
     *
     * Directly beside [templateId] on purpose. The two have been confused on both clients for as
     * long as the create form has existed: the handset drew a six-value dropdown under the title and
     * it was the REPORT TEMPLATE — the output document's format — so the form looked as though it
     * carried a type/name pair and carried neither half. Keeping them adjacent, here and on both
     * screens, is what makes them legible as two questions rather than one asked twice.
     *
     * ── A PROMOTED COLUMN THE CREATE MAY ALSO SET ──────────────────────────────────────────────
     *
     * `workshopSetup.workshopKind` is a stage-1 field and `promoted_values()` copies it onto
     * `DesignWorkshop.workshopKind`, so stage 1 remains the authority. This key exists so the list
     * screen can filter and label a workshop by type on the day it is opened, before stage 1 has
     * been saved at all. The server validates the token against `WORKSHOP_KINDS` and 422s anything
     * else rather than storing it, because the column is plain TEXT and Prisma would accept a typo.
     *
     * ── NULL IS "NOT STATED", AND IT IS LEFT OFF THE WIRE ENTIRELY ─────────────────────────────
     *
     * Same mechanism and same reason as [designerUserId] below: `ApiClient.json` sets
     * `explicitNulls = false` and leaves `encodeDefaults` at kotlinx's default of false, so a body
     * that has nothing to say with this key does not carry it — which matters because `APIModel` is
     * `extra="forbid"`, an API deployed before this field answers 422 `extra_forbidden` to a body
     * that merely CARRIES it, and a 422 is never queued, so a phone running ahead of its server
     * would otherwise strand a whole courtyard's fortnight rather than merely fail to state a type.
     *
     * ── AND THE OFFLINE CREATE DOES NOT CARRY IT, WHICH IS THE EXISTING SHAPE AND NOT A NEW GAP ─
     *
     * `WorkshopDraft` has no field for it, exactly as it has none for `craftName` or `clusterName`,
     * so a workshop minted in a courtyard is posted by `WorkshopSync` without a type and gets one
     * from the first stage-1 save — which is where the answer is authoritative anyway. Widening the
     * draft is a change to `WorkshopDraftStore` and its schema version, and it belongs with the two
     * fields already in that position rather than with this one alone.
     */
    val workshopKind: String? = null,
    /**
     * THE DESIGNER THIS WORKSHOP IS FOR — the one field in this body that changes what the finished
     * report SAYS rather than what it is filed under.
     *
     * ── THE DEFECT IT CLOSES ────────────────────────────────────────────────────────────────────
     *
     * `seed_designer_prefill` copies a `DesignerProfile` into stage 1 and stage 3, and until this
     * field could be SENT the profile it copied was always the CREATOR'S. For an admin opening a
     * workshop on somebody else's behalf that is the wrong person's name on a ministry document,
     * and it is not hypothetical: `require_designer` admits ADMIN, `GET /designers/me/profile`
     * upserts a profile row for any admin who so much as opens the Designer Profile screen, and
     * `prefill_from_profile`'s tail fallback then writes `profile.user.name` — so an admin who has
     * never filled anything in still lands their own account name on the promoted `designerName`
     * column. The server grew this field, with `assert_designer_may_be_named` and
     * `attach_the_named_designer` behind it, and NEITHER CLIENT COULD SEND IT; this is the handset
     * half of reaching it, and `DwCreateBody.designerUserId` in `frontend/lib/designWorkshops.ts`
     * is the browser's.
     *
     * ── OPTIONAL, AND ABSENT MEANS "NOBODY NAMED" ───────────────────────────────────────────────
     *
     * Absent leaves the pre-field behaviour bit for bit — the creator's profile is copied — which is
     * what makes the field additive for a handset that has not adopted it and for a server that has
     * never heard of it. A workshop is opened in a room on day one and the admin may genuinely not
     * know yet who will run it, and the offline create cannot reach the eligibility picker at all.
     *
     * BLANK IS ABSENT TOO, and the server says so: the route reads `(payload.designerUserId or
     * "").strip() or None`, so an empty picker is "nobody named" and not an account whose id is the
     * empty string. Sending null is nevertheless the honest spelling from here, and
     * `dwNamedDesignerId` (`ui/designworkshop/WorkshopListScreen.kt`) is what folds one into the
     * other before it reaches this body or the local draft.
     *
     * ── NAMING SOMEBODY ALSO PUTS THEM ON THE WORKSHOP ──────────────────────────────────────────
     *
     * The create route grants them a `DesignWorkshopViewer` row in the same call, under the same
     * eligibility rule `WorkshopViewersScreen` applies — `assert_designer_may_be_named` delegates to
     * the same `_assert_every_id_may_be_granted` the viewers PUT uses, so the two cannot drift. An
     * ineligible id (a lapsed empanelment, a suspended account) refuses the WHOLE create with a 422
     * naming the account, and the question is asked ABOVE the create so a refusal leaves no orphan
     * record. On this client that 422 arrives as a permanent refusal through `classifyCreate` in the
     * dialog and through `refusal(...)` in `WorkshopSync`'s create arm.
     *
     * ── IT IS OMITTED FROM THE WIRE WHEN UNSET, AND THAT WAS CHECKED RATHER THAN ASSUMED ────────
     *
     * `ApiClient.retrofit` serialises with `ApiClient.json`, which leaves `encodeDefaults` at
     * kotlinx's default of false AND sets `explicitNulls = false` — two independent reasons a
     * property still holding this null is left out of the body entirely. That is what keeps this
     * handset compatible with an API that predates the field: `APIModel` is `extra="forbid"` on the
     * server, so a deployment that has never heard of `designerUserId` would answer 422 "Extra
     * inputs are not permitted" to the whole create, and a phone updates when it next sees wifi
     * while the API updates when somebody deploys it. See [StageEntryBody.merge], which carries the
     * same argument for the same reason.
     *
     * NOT ON THE UPDATE PATH. PATCH is closed to this field — `DesignWorkshopUpdate` has no such
     * member and `APIModel` is `extra="forbid"` — because naming the designer is a CREATE-time act
     * by construction: it decides whose profile is copied into stage 1 before stage 1 exists.
     */
    val designerUserId: String? = null,
    /**
     * EVERY DESIGNER THIS WORKSHOP IS OPENED FOR, lead first — and the whole of how they get in.
     *
     * ── WHY THE SINGULAR FIELD ABOVE WAS NOT ENOUGH ─────────────────────────────────────────────
     *
     * A design workshop is visible ONLY to its creator, to admins, and to whoever holds a
     * `DesignWorkshopViewer` row — enforced in the QUERY on the list (`visible_to_clause`) and in
     * the loader on the single read, which refuses with a 404 identical to a nonexistent id. A
     * DESIGNER cannot create a workshop at all, so `createdById` never matches for them: the
     * workshops a designer can see are exactly the ones they hold a row on. A real workshop is a
     * fortnight worked by two designers alongside a master craftsperson and a reviewing officer,
     * and with only [designerUserId] to send, everybody after the first had to be added afterwards
     * from "Designers on a workshop" — so an admin who forgot left a designer who could not open
     * the workshop their own stage 1 already named.
     *
     * The server writes ONE VIEWER ROW PER ID here, through the same `add_one_viewer` the viewers
     * PUT uses, under the same eligibility rule: `assert_every_designer_may_be_named` is asked ONCE
     * for the whole set, ABOVE the create, so an ineligible id ANYWHERE in the list refuses the
     * WHOLE create with a 422 naming every account it objected to and leaves no orphan record.
     * Never `replace_viewers` — a whole-set replace would delete rows a concurrent join-card
     * redemption had just created.
     *
     * ── IT DECIDES ACCESS AND NOTHING ELSE. [designerUserId] STILL DECIDES THE REPORT ────────────
     *
     * Several people may OPEN it; exactly ONE name is ON it. Stage 1 and stage 3 declare a single
     * designer block — one `designerName`, one `designerProfile`, one signature — and `report_meta`
     * feeds the promoted name into the .docx's `dc:creator`, a single-author field the format
     * cannot express as a list. So [designerUserId] keeps its exact meaning: the LEAD, whose
     * profile `seed_designer_prefill` copies in. Co-designers named here get access and appear in
     * "Designers on a workshop"; they do not appear on the cover.
     *
     * A body sending only this field and no [designerUserId] promotes the FIRST ID IN THE LIST to
     * lead, because the only other candidate is the ADMIN who pressed create — which is precisely
     * the wrong-name-on-a-ministry-document defect [designerUserId] exists to end.
     *
     * ── OPTIONAL FOREVER, AND OMITTED FROM THE WIRE UNLESS THERE IS A SECOND DESIGNER ───────────
     *
     * Null here is not "no designers"; it is "this create had nothing to say with this key", which
     * is exactly what a one-designer create must say. `APIModel` is `extra="forbid"`, so an API
     * deployed before this field answers **422 `extra_forbidden` to a body that merely CARRIES
     * it** — and a 422 is never queued, so on the offline arm that would take a whole courtyard's
     * fortnight down with it. `ApiClient.json` sets `explicitNulls = false` AND leaves
     * `encodeDefaults` at kotlinx's default of false, two independent reasons a null property is
     * left out of the body entirely; [com.designprototype.workshop.data.dwDesignerCreateFields] is
     * the single place that decides whether it is null, and it sends the key ONLY when there is
     * genuinely more than one designer. `designerCreateFields` in `frontend/lib/designWorkshops.ts`
     * is the browser's copy of that same three-way answer.
     *
     * NEITHER MAY EVER BECOME REQUIRED, and the singular one may never be removed: a 4xx is not
     * queued, so narrowing this shape would silently destroy an un-updated handset's fortnight.
     *
     * Capped at [com.designprototype.workshop.data.DW_MAX_NAMED_DESIGNERS] — the server's own
     * `MAX_DESIGN_WORKSHOP_VIEWERS`, refused rather than trimmed at both ends. NOT ON THE UPDATE
     * PATH, for the same reason [designerUserId] is not: `DesignWorkshopUpdate` has no such member.
     */
    val designerUserIds: List<String>? = null,
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
     * NOT UNCONDITIONALLY TRUE ANY MORE: it is a claim of authority, and the phone is only entitled
     * to make it over a stage it has actually seen the server's copy of. See [StageDraft.stageSeen]
     * for the fortnight of process steps, tools and raw materials that the unconditional version
     * deleted.
     *
     * ── IT HAS NO DEFAULT, AND THAT IS THE WHOLE OF THE GATE. DO NOT GIVE IT ONE. ─────────────────
     *
     * It was `= false`, and a default is a value kotlinx OMITS: `ApiClient.retrofit`'s `Json { … }`
     * never sets `encodeDefaults`, so it stands at kotlinx's default of false — which is exactly what
     * [StageEntryBody.merge] one class up RELIES on to stay compatible with an API that predates it.
     * For that field the omission is the intended meaning. For this one it is the opposite of it:
     * `StageSaveIn.replaceCollections` on the server is `Field(default=True)`, so a body that leaves
     * the key out is read as **"these are now exactly the rows"** — the strongest claim the protocol
     * has, made silently by every save that disclaimed it.
     *
     * MEASURED, with this handset's own builder against the running API and Postgres. A draft with
     * `stageSeen = false` holding one `tool` row, built by [buildStageBody], serialised exactly as
     * `ApiClient` serialises it:
     *
     *   {"entries":[{"entityKey":"tool","ordinal":0,
     *                "data":{"name":"Pit loom (corrected)","_clientKey":"phone-tool-1"},
     *                "merge":true}]}
     *   -> HTTP 200 {"saved":1,"created":0,"updated":1,"removed":3,"errors":{}}
     *
     * Three rows this phone had never downloaded, soft-deleted by a payload that asked for no sweep
     * at all, on a stage the app had correctly judged it had no authority over. `merge: true` is no
     * defence: it preserves keys INSIDE a row the server matched and says nothing about a row the
     * payload never named. Without a default the property is always encoded, the wire carries
     * `"replaceCollections":false`, and the same walk answers `removed:0`.
     *
     * Every `false` on this field is therefore load-bearing on the wire, which is why
     * `StageSweepReachesTheWireTest` asserts on the SERIALISED JSON and not on this object: 1107
     * passing unit tests read `body.replaceCollections` off the Kotlin value, where the gate has been
     * correct all along.
     */
    val replaceCollections: Boolean,
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
    /**
     * CUSTOM field keys the server's definition does not carry, and therefore did not store.
     *
     * **ITS OWN FIELD AND ITS OWN SENTENCE, NEVER MERGED INTO [droppedKeys]** — plan §4, and the one
     * place this DTO could destroy something. `droppedKeys` is the only client/server REGISTRY-drift
     * signal this repository has, and both clients render it as "this phone is running a newer field
     * registry than the server". A custom key the definition no longer carries is a different fact
     * with a different remedy (the designer edited their own sections on the web), and folding the
     * two together would fire the registry-drift banner on every save of every workshop that has a
     * custom section — which is how the one signal that matters comes to be ignored.
     */
    val droppedCustomKeys: List<String> = emptyList(),
    val completeness: StageCompletenessDto? = null,
    val schemaVersion: String = "",
    /** The digest the score in [completeness] was computed under. See [DesignWorkshopDetailDto]. */
    val customSchemaVersion: String = "",
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

/**
 * An [ExportRecordBody] plus the workshop it belongs to, so the pair survives a spell in the offline
 * outbox.
 *
 * The outbox stores one opaque `payloadJson` per entry and the workshop id is a PATH segment rather
 * than part of the body, so without this wrapper the id is simply not there when the entry is
 * replayed days later. See `WorkshopRepository.recordDesignWorkshopExport`.
 */
@Serializable
data class PendingExportRecord(
    val workshopId: String,
    val body: ExportRecordBody
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

/**
 * Whether this stage holds anything a PERSON answered — the question the sync walks are asking.
 *
 * ── AN UNDERSCORE KEY IS NOT AN ANSWER, AND ASKING `values.isNotEmpty()` COUNTED ONE ─────────────
 *
 * `DwRecordingPlaceCard` offers "where were you when this stage was filled in?" on all twenty-two
 * stages and writes a whole location object into the singleton under `_recordingPlace`
 * (ui/designworkshop/DwLocationField.kt). The underscore prefix is the sync protocol's own marker:
 * `wireData` drops every `_`-prefixed key from the payload by construction, deliberately, because the
 * server answers an unknown key with a `droppedKeys` entry and that would put a red line on every
 * stage of every sync for a value that was never going to be stored.
 *
 * So a draft whose only content is a recording place holds nothing any payload can carry, and the two
 * sync walks that asked `values.isNotEmpty()` were asking about the MAP rather than about the answers
 * in it. [dwStageSaysNothing] therefore built a body for such a stage and spent a metered PUT carrying
 * an empty singleton (creating a server-side stage record nobody had answered), and
 * [dwStrandedStages] named it on the status screen as a stage this build cannot send — a sentence no
 * action can ever discharge, against its own comment's rule that it must not fire "for no answers at
 * all".
 *
 * ── WHY IT IS A FUNCTION RATHER THAN THE SAME LINE IN TWO MORE PLACES ────────────────────────────
 *
 * Six copies of this test exist in the app (the two callers here, `ReportSource.holdsWork`,
 * `DwWorkshopCards.touchedLocally`, `StageIndexScreen`, and the stage read's own — see below), and
 * every defect this lane has recorded about them came from the copies drifting apart. The costliest
 * was `ReportSource.holdsWork`, where counting `_recordingPlace` as work made a stage "the device's"
 * and kept the office's whole copy of it OUT of the delivered document; it learned this rule the
 * expensive way and the sync lane had not. Asked once, the status a designer reads and the payload a
 * pass sends cannot answer it differently.
 *
 * ── AND THE ONE CALLER THAT MUST NOT USE IT ──────────────────────────────────────────────────────
 *
 * `dwStageReadPlan` asks a genuinely different question — "would seeding this stage from the server
 * LOSE something this device holds?" — and for that one an underscore key is emphatically something
 * held: the seed arm adopts the server's bucket verbatim through `fromRemote` and the next
 * `persistLocally` writes `values` wholesale, so routing a provenance-only draft there would delete
 * the recording place off the device. That asymmetry is pinned by the last case in
 * `DwProvenanceIsNotWorkTest`, so a later tidy-up that "unifies the work tests" fails on a desktop JVM
 * rather than in a courtyard.
 *
 * ROWS AND CUSTOM ANSWERS ARE COUNTED WHOLE. A row's presence IS work — its `_clientKey`/`_entryId`
 * live INSIDE the row, beside the designer's cells, not beside the row — and eight of the twenty-two
 * stages declare no singleton at all, so a stage whose only answers are rows or the designer's own
 * questions is the ordinary shape rather than an edge case.
 */
fun StageDraft.holdsAnswers(): Boolean =
    values.keys.any { !it.startsWith("_") } || rows.isNotEmpty() || custom.isNotEmpty()
