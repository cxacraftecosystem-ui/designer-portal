package com.designprototype.workshop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The questions a DESIGNER added to one workshop, and the twelve types they may be asked in.
 *
 * The rules are `backend/app/services/custom_sections.py` and
 * `docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md` §4. Nothing is re-decided here. This file is the
 * handset's half of one GET, the adapter that lets a designer's question be drawn by the renderer
 * this app already has, and the three-state answer to "what can this phone honestly say about this
 * workshop's own questions".
 *
 * ── WHAT THIS ENDS ────────────────────────────────────────────────────────────────────────────────
 *
 * The server has held designer-defined sections since `20260812090000_dw_custom_sections`, the
 * browser has drawn, answered, scored and printed them since `lib/customSections.ts` landed, and
 * `grep -r customSection android/app/src/main/java/` returned NOTHING. So a designer who added "how
 * many looms are in working order" to stage 5, answered it on the web, and then exported the field
 * copy from the handset in a courtyard got a .docx with no such question and no such answer in it,
 * and nothing anywhere saying a question had been left out. That is the divergence this file closes.
 *
 * ── NO ANDROID IMPORTS, AND THAT IS A RULE RATHER THAN A HAPPY ACCIDENT ───────────────────────────
 *
 * Everything in this file is a decision — which questions a stage asks, which of them are still
 * asked, which can be drawn, what a field becomes when it is handed to [FieldRenderer]. Every one of
 * those is exercised by a plain desktop JVM test in `app/src/test`, with no emulator, no Robolectric
 * and no handset, because a rule that can only be checked by a person holding a phone is a rule that
 * is checked when somebody remembers. The Context-shaped half — one file per workshop under
 * `filesDir` — is [DwCustomSectionStore], and it holds no rules at all.
 *
 * ── EVERY KEY BELOW WAS READ OFF THE SERVER'S OWN SERIALISER ON 2026-08-12 ────────────────────────
 *
 * Not remembered, and not copied from the brief. `field_payload`, `section_payload` and
 * `definition_payload` (`custom_sections.py:1511`, `:1539`, `:1553`) were EXECUTED against a
 * hand-built definition and the JSON they printed is pinned verbatim in `DwCustomDefinitionWireTest`.
 * That is the only test with any power here: this client has shipped a DTO listening on keys an
 * endpoint never sent TWICE — `DwReferenceStore`'s `options`, and the identity-OCR DTO that declared
 * `number`/`documentType`/`name` — and both times `ignoreUnknownKeys = true` plus all-defaulted
 * properties turned a perfect payload into an empty object with nothing logged and nothing thrown.
 * Here the same mistake would read as "this workshop has no custom questions", which is
 * indistinguishable from the truth for most workshops. So: `stageKey` and not `stage`,
 * `supersededById` and not `supersededBy`, `customSchemaVersion` and not `version`, and an option is
 * `{value, label}` where the label ARRIVES already resolved — `field_payload` emits
 * `CustomOption.display`, which is the token itself when nobody wrote a label, so a `TUSSAR` with no
 * label comes back as `{"value":"TUSSAR","label":"TUSSAR"}` rather than as an empty string to invent
 * a word for.
 *
 * ── THE TWO ASYMMETRIES THAT WILL BITE ANYONE EDITING THIS FILE ───────────────────────────────────
 *
 * 1. **RETIRED SECTIONS AND FIELDS ALWAYS ARRIVE AND MUST NEVER BE DROPPED.** `definition_payload`
 *    includes them on purpose, for the reason `DwQuestionnaireStore` refuses a payload fetched
 *    without `includeRetired`: a copy missing every answer given under a superseded wording makes the
 *    two copies of one report disagree about the fieldwork, with nothing in either saying so. A
 *    client OFFERS the live ones and PRINTS the retired ones that hold an answer. [liveCustomFields]
 *    and [retiredCustomFieldsWithAnswers] are the two halves and no caller may filter by hand.
 * 2. **A SECTION'S RETIREMENT AND ITS FIELDS' ARE TWO SEPARATE COLUMNS**, so a retired section can
 *    arrive carrying fields that call themselves live. [sectionsForStage] forces them, in one place,
 *    for the same reason the server's `fields_for` does — see its own comment there.
 */

// --------------------------------------------------------------------------------------
// The reserved key, and the twelve types
// --------------------------------------------------------------------------------------

/**
 * The `StageEntryBody.entityKey` a workshop's custom answers travel under, and the key the server
 * files their errors beneath. `custom_sections.CUSTOM_ENTITY_KEY`.
 *
 * A ROW OF ITS OWN AND NOT AN OBJECT NESTED IN THE STAGE'S SINGLETON, and the reason is the installed
 * fleet rather than taste. `save_stage` replaces a singleton's `data` wholesale, so a handset one
 * release behind — one that has never heard of custom sections and sends no `custom` key — would have
 * DELETED every custom answer on the stage, silently, with nothing in `droppedKeys` to say so. With a
 * separate row an old client sends no `_custom` entry, no `_custom` row is touched, and the
 * collection sweep cannot reach it because `emptiedEntities` is intersected with the registry's own
 * collection keys and this is not one of them.
 *
 * The leading underscore is this repository's existing reservation — `_clientKey`, `_entryId` and
 * `_ordinal` already mean "the protocol's own, not a designer's key" on both clients and in
 * `save_stage` — and [CUSTOM_KEY_PATTERN] refuses anything not starting with a lower-case letter, so
 * a designer's key can never collide with it.
 */
const val CUSTOM_ENTITY_KEY: String = "_custom"

/**
 * The field types a designer may declare, and the whole list. `custom_sections.V1_FIELD_TYPES`.
 *
 * **NO MEDIA, NO RICH_TEXT, NO REF, NO GEO — and each exclusion is a defect already paid for once.**
 * Five separate walkers translate a local media reference into a server id (the server's `_media_ids`,
 * this client's [wireData], and the web's `unresolvedMediaRefs`, draft-resolve and `rewriteMediaRefs`)
 * and every one of them enumerates the media-typed fields OF THE ROW'S REGISTRY ENTITY and reads them
 * at the top level of the row. None can see a value that is not a registry field, so a custom
 * photograph would sync as a `dwlocal:` reference resolving to nothing: the save reports success, and
 * the picture is simply absent from the .docx — which the designer learns from the officer who
 * received it. That is the RICH_TEXT scar already recorded in `WorkshopSync.wireData`. REF is out
 * because `ref_resolves` is supplied by the REPORT and by nothing else, so a dangling custom
 * reference would read FILLED on every form and UNFILLED in the document.
 *
 * The order is the order the server declares them in `V1_FIELD_TYPES`' own docstring and the order
 * the web's authoring screen offers them in; nothing on this handset authors a definition, so here it
 * is only a set that has to match.
 */
val V1_CUSTOM_TYPES: List<String> = listOf(
    "TEXT", "LONG_TEXT", "INT", "DECIMAL", "MONEY", "PERCENT",
    "DATE", "TIME", "BOOL", "ENUM", "MULTI_ENUM", "TAGS",
)

private val V1_CUSTOM_TYPE_SET: Set<String> = V1_CUSTOM_TYPES.toSet()

/**
 * Is this token one a designer may declare in v1?
 *
 * **ASKED AGAINST THE TWELVE AND NOT AGAINST WHAT THIS BUILD CAN DRAW**, which is a wider set and the
 * wrong question. [FieldRenderer] has a real, working arm for GEO, IMAGE, REF and RICH_TEXT — so a
 * definition naming one of those, from a server that had moved past v1, would draw a live map card or
 * a camera button for a value none of the five media walkers can see. The v1 boundary is about what
 * can safely ROUND TRIP, not about what can be painted.
 *
 * The second half of the question — whether this build knows the token at all — is
 * [DwFieldType.known], and both have to pass. They are asked separately because they fail for
 * different reasons and the sentence a designer reads names the token either way.
 */
fun isV1CustomType(type: String): Boolean = type in V1_CUSTOM_TYPE_SET

/**
 * Whether this build may draw an EDITABLE control for a custom field of this type.
 *
 * False sends the field down [dwCustomUnsupportedNote]'s read-only arm. Both halves are required: a
 * token outside v1 cannot round trip even where a control exists for it, and a token this build has
 * never heard of has no control at all — `DwFieldType.of` would degrade it to TEXT and draw an
 * ordinary editable box with no note, which is a silent WRONG answer rather than a blank, and worse
 * than the web's failure because the designer types into it.
 */
fun dwCustomFieldDrawable(type: String): Boolean =
    isV1CustomType(type) && DwFieldType.known(type) != null

/**
 * What a custom field key may look like — `custom_sections.KEY_PATTERN`, character for character.
 *
 * Declared here and never applied to an incoming payload. The server is the authority on a key it
 * has already stored, and a client that refused to render a key it thought malformed would hide an
 * answer the server holds. It exists so a reader of this file can see WHY [CUSTOM_ENTITY_KEY] cannot
 * collide with a designer's key: nothing matching this can begin with an underscore.
 */
val CUSTOM_KEY_PATTERN: Regex = Regex("^[a-z][A-Za-z0-9]{0,39}$")

// --------------------------------------------------------------------------------------
// The wire shape. Every property defaulted, without exception.
// --------------------------------------------------------------------------------------

/*
 * DEFAULTS ARE A RULE HERE, exactly as the standalone block above [EnumOption] in `StageSchema.kt`
 * states it: kotlinx throws `MissingFieldException` on a non-defaulted field that is ABSENT, and the
 * only recovery [DwCustomSectionStore.load] has is to delete the cache and re-read it — so a build
 * three weeks behind would drop a definition it could otherwise have degraded losslessly.
 *
 * THE SECOND CLAUSE OF THAT RULE CUTS THE OTHER WAY HERE AND IS WORTH NAMING. The registry's DTOs
 * need defaults because `field_to_dict` deliberately OMITS every key at its default value to keep a
 * 119 KB payload small. `field_payload` does the opposite and emits every key on every field, on
 * purpose ("a client that has to supply its own default for an absent key is a client that will
 * eventually supply a different one from the server's"). So these defaults are insurance against
 * VERSION SKEW — a v1.1 field this build has never seen, a cache written by a newer build — and not
 * against ordinary omission. Nullable exactly where the server is nullable and nowhere else.
 */

/** One option of a designer's own ENUM or MULTI_ENUM list. */
@Serializable
data class DwCustomOptionDto(
    val value: String = "",
    /**
     * What a form and a report print.
     *
     * ALREADY RESOLVED BY THE SERVER: `field_payload` emits `CustomOption.display`, which falls back
     * to the token itself when nobody wrote a label — so `TUSSAR` arrives as `"TUSSAR"` and not as
     * `""`. Verified by executing the serialiser; see `DwCustomDefinitionWireTest`. This client must
     * therefore never invent "Tussar" from `TUSSAR`: a word this app made up would go into a ministry
     * document under the designer's name.
     */
    val label: String = "",
)

/** One designer-defined field, exactly as `field_payload` serialises it. */
@Serializable
data class DwCustomFieldDto(
    /** The database row id. Carried, never used as a storage key — the ANSWER is stored under [key]. */
    val id: String = "",
    val key: String = "",
    val label: String = "",
    /**
     * TEXT | LONG_TEXT | INT | … — kept as the RAW TOKEN, and that is what makes the honest degrade
     * possible at all. See [dwCustomFieldDrawable] and [DwFieldType.known].
     */
    val type: String = "TEXT",
    /** BASIC | STANDARD | ADVANCED, read through [DwTier.of] like every other tier in this app. */
    val tier: String = "STANDARD",
    val required: Boolean = false,
    val help: String = "",
    val unit: String = "",
    val options: List<DwCustomOptionDto> = emptyList(),
    val maxLength: Int = 0,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val sortOrder: Int = 0,
    /** Stopped being asked; its answers stay readable and printable. Never dropped from a document. */
    val retired: Boolean = false,
    /** The field that replaced this one when its LABEL was rewritten after it had answers. */
    val supersededById: String? = null,
)

/** One block of designer-defined questions, as `section_payload` serialises it. */
@Serializable
data class DwCustomSectionDto(
    val id: String = "",
    val key: String = "",
    /**
     * The [StageDto.key] these answers belong to. **Never empty** — `validate_definition` refuses a
     * section with no stage, because answers must have a home.
     */
    val stageKey: String = "",
    val title: String = "",
    val description: String = "",
    val sortOrder: Int = 0,
    /** Bumped whenever something under this section was superseded or retired. */
    val revision: Int = 1,
    val retired: Boolean = false,
    val fields: List<DwCustomFieldDto> = emptyList(),
)

/** A whole definition, as `GET /design-workshops/{id}/custom-sections` returns it. */
@Serializable
data class DwCustomDefinitionDto(
    /**
     * The digest of this definition, and `""` for a workshop that has never had one.
     *
     * EMPTY IS A REAL VALUE AND NOT A MISSING ONE: `custom_schema_version` returns `""` rather than a
     * hash of nothing, precisely so that "I hold nothing" and "there is nothing to hold" stay
     * distinguishable — the same distinction [DwCustomCopy] needs three states for.
     *
     * NOT `schemaVersion`, and the two must never be conflated. `schemaVersion` is
     * `registry_version()`, a digest of every key, type, tier, derivation and hydration mapping of
     * the whole registry, and [StageSchemaStore] uses it to decide whether the BUNDLED asset is
     * stale. A designer's own question must never move that digest, or every handset in the fleet
     * would treat its bundled schema as stale the moment anyone anywhere added a field (plan §4,
     * constraint 2).
     */
    val customSchemaVersion: String = "",
    val sections: List<DwCustomSectionDto> = emptyList(),
    /** Server clock, ISO-8601. Recorded and printed; never used to decide whether a cache may be used. */
    val fetchedAt: String = "",
)

// --------------------------------------------------------------------------------------
// What this device holds, and how sure it is of it
// --------------------------------------------------------------------------------------

/**
 * One workshop's definition as the DEVICE keeps it, plus the provenance that makes it safe to use.
 *
 * @property complete this is EVERY section the server says this workshop has. Only a successful read
 *   of `GET /{id}/custom-sections` may claim it, and [dwCustomCopy] is the only thing that reads it.
 * @property fetchedAt device clock, ISO-8601. Printed beside the questions in a report for the reason
 *   [DwQuestionnaireCache.fetchedAt] is printed, and never used to expire anything: there is no clock
 *   on a phone that can tell "a fortnight old because nothing changed" from "a fortnight old because
 *   there has been no signal for a fortnight".
 */
@Serializable
data class DwCustomCache(
    val workshopId: String = "",
    val customSchemaVersion: String = "",
    val sections: List<DwCustomSectionDto> = emptyList(),
    val complete: Boolean = false,
    val fetchedAt: String = "",
)

/**
 * What this device can honestly say about the questions a designer added to one workshop.
 *
 * THREE STATES AND NOT TWO, transcribed from [DwQuestionnaireCopy] together with the argument in its
 * docstring, which applies here word for word: *"we hold nothing" and "there is nothing to hold" look
 * identical from inside a phone with no signal, and warning on both puts an apology on the majority
 * of reports, which is how a designer learns to stop reading warnings.* Most workshops have no custom
 * sections at all, so a two-state answer would put a warning on nearly every export ever produced.
 *
 * It is also what makes [DwCustomSectionStore]'s delete-on-corruption safe. That store destroys a
 * cache it cannot parse, because the cache is a COPY of something the server still holds — and the
 * only reason that is not a data-loss bug is that the next [load] returns null and null resolves
 * HERE to [UNKNOWN]. If a parse failure resolved to [NONE_DEFINED] the phone would assert "this
 * workshop has no custom questions" on the strength of a corrupt file, print a report short a whole
 * section, and say nothing.
 */
enum class DwCustomCopy {
    /** Never read for this workshop. "Has questions" and "has none" are indistinguishable from here. */
    UNKNOWN,

    /** The server was asked and said none. Draw nothing, print nothing, warn about nothing. */
    NONE_DEFINED,

    /** At least one section is defined and this device holds it. The form and the report draw it. */
    DEFINED,
}

/** Which of the three states [cache] represents. A null cache has never been fetched. */
fun dwCustomCopy(cache: DwCustomCache?): DwCustomCopy = when {
    cache == null -> DwCustomCopy.UNKNOWN
    cache.sections.isNotEmpty() -> DwCustomCopy.DEFINED
    cache.complete -> DwCustomCopy.NONE_DEFINED
    // Empty AND not complete: a cache written by something that could not claim to have read the
    // whole set. Treated as never having asked, which is the conservative direction.
    else -> DwCustomCopy.UNKNOWN
}

/** True when this device holds a definition it may draw, score and print from. */
val DwCustomCache?.isHeld: Boolean
    get() = dwCustomCopy(this) != DwCustomCopy.UNKNOWN

// --------------------------------------------------------------------------------------
// Reading one stage's questions out of a definition
// --------------------------------------------------------------------------------------

/** One stage's questions, still grouped by the section that asks them. */
data class DwCustomStageBlock(
    val section: DwCustomSectionDto,
    /** Every field of the section, retired ones included, in the order they are asked. */
    val fields: List<DwCustomFieldDto>,
) {
    /** The fields still being asked — what a form OFFERS and what completeness counts. */
    val live: List<DwCustomFieldDto> get() = fields.filter { !it.retired }
}

/**
 * The sections asked at one stage, retired ones included, in the order they are asked.
 *
 * **EVERY FIELD OF A RETIRED SECTION IS FORCED TO `retired` HERE, AND THIS IS THE ONE PLACE THAT
 * HAPPENS.** `section_payload` does not do it — a section's flag and a field's are separate columns,
 * and `field_payload` reports each field's own — so a retired section can arrive carrying fields that
 * call themselves live. The server's `fields_for` forces the flag for exactly that reason and says
 * so at length; forcing it at the same single door on this side is what stops the form and the scorer
 * reading two different answers to "is this still asked" off one payload. Without it a designer would
 * be typing into an editable form whose every field the completeness bar ignores and the report
 * prints under "no longer asked".
 */
fun sectionsForStage(cache: DwCustomCache?, stageKey: String): List<DwCustomSectionDto> {
    if (cache == null || stageKey.isBlank()) return emptyList()
    return cache.sections
        .filter { it.stageKey == stageKey }
        .sortedWith(compareBy({ it.sortOrder }, { it.key }))
        .map(::withSectionRetirementForced)
}

/** The one implementation of the rule [sectionsForStage] states at length. */
private fun withSectionRetirementForced(section: DwCustomSectionDto): DwCustomSectionDto =
    if (!section.retired || section.fields.none { !it.retired }) section
    else section.copy(fields = section.fields.map { if (it.retired) it else it.copy(retired = true) })

/**
 * Every section of the whole workshop that the REPORT should carry — the port of the loop in
 * `design_workshops.attach_report_custom_sections`.
 *
 * WHY THE REPORT NEEDS A WORKSHOP-WIDE LIST AT ALL, when the form only ever needs one stage's. The
 * template decides where each block prints, and `applyReportSettings` is the single arbiter of that:
 * a section is spliced after its anchor stage's section when the template carries that stage, and as
 * a back annexure when it does not. The second placement cannot be reached from a per-stage walk,
 * which is exactly how the handset came to drop a designer's questions out of PHOTO_CATALOGUE
 * entirely — see [com.designprototype.workshop.report.SpecialSection.CUSTOM_SECTION].
 *
 * A RETIRED SECTION NOBODY EVER ANSWERED IS LEFT OUT, and that one drop is the server's: it is not
 * evidence of anything, and printing an empty heading for every block a designer thought better of
 * would fill a submitted report with the history of its own form. Asked through [DwValues.isFilled]
 * and never through the truthiness of the stored value — a retired "How many looms?" answered "0" is
 * a finding, and an ordinary one in a cluster where the looms have been sold.
 *
 * A retired section that WAS answered stays, with every field forced retired, so the answers still
 * print under the marker that says they are no longer asked.
 *
 * @param answersFor the `_custom` bucket of one stage, as the draft holds it. A LOOKUP rather than a
 *   `WorkshopDraft`, so this file keeps knowing only about definitions: it is already the one place
 *   that decides what a custom section IS, and giving it an opinion about where the answers are
 *   stored would make the next storage change a change to this file as well. The two callers (the
 *   plan and the renderer) pass the same one-line lambda for exactly that reason.
 */
fun customSectionsForReport(
    cache: DwCustomCache?,
    answersFor: (stageKey: String) -> Map<String, JsonElement>,
): List<DwCustomSectionDto> {
    if (cache == null) return emptyList()
    return cache.sections
        .map(::withSectionRetirementForced)
        .filter { section ->
            if (!section.retired) return@filter true
            val values = answersFor(section.stageKey)
            section.fields.any { DwValues.isFilled(values[it.key]) }
        }
}

/**
 * The same questions [customFieldsForStage] scores, still paired with their sections.
 *
 * ONE OWNER OF THE ORDER, TWO VIEWS OF IT, which is why the flat version below is defined as this
 * one's flatten rather than the two being walked separately. The scorer needs a flat list and files
 * every label bare; the form needs to know WHICH section asks a question so it can draw the heading
 * above it. Two independent walks would be two orderings, and `missing` is printed in the scorer's
 * order — so the day they diverged, a designer sent to "the first missing question" would be looking
 * at the second.
 */
fun customStageBlocks(cache: DwCustomCache?, stageKey: String): List<DwCustomStageBlock> =
    sectionsForStage(cache, stageKey).map { section ->
        DwCustomStageBlock(
            section = section,
            fields = section.fields.sortedWith(compareBy({ it.sortOrder }, { it.key })),
        )
    }

/**
 * Every field of every section of one stage, in the order they are asked — the server's `fields_for`.
 *
 * **RETIRED FIELDS ARE INCLUDED**, and reading this as "live only" was a silent data-loss bug on the
 * server which this client can commit independently. A section is retired precisely BECAUSE somebody
 * answered it (an answered section is retired, never deleted), so its keys are exactly the keys the
 * `_custom` row still holds. Left out of this list they become keys the definition does not carry —
 * and the next ordinary save of that stage would send the row without them, the server would write it
 * without them, and "the stored answer stays readable and printable" would have lasted until the next
 * save. The completeness scorer skips the retired ones itself, exactly as it skips a deprecated
 * registry field.
 */
fun customFieldsForStage(cache: DwCustomCache?, stageKey: String): List<DwCustomFieldDto> {
    /*
      ONE KEY, ONE SPEC — NEVER TWO, HOWEVER THE DEFINITION GOT INTO THAT STATE, and it is the
      server's `fields_for` slot rule rather than a tidy-up of it.

      The answers are ONE FLAT CONTAINER PER STAGE while the database can only make a field key
      unique per SECTION, so two sections of one stage declaring one key hand this list two questions
      fighting over a single slot. `plan_definition` refuses to create that state and is the first
      line of defence; the server keeps this as the second, for a row written by hand, a definition
      restored from a backup taken before that refusal existed, or a bug nobody has found yet. A
      client that flattened without it is a THIRD arithmetic: `computeStageCompleteness` walks this
      list, so one required key declared twice would raise `requiredTotal` by two on this handset and
      by one on the server — a stage reading 6/7 on the phone and 6/6 in the office, with nothing on
      either surface able to explain the disagreement, which is the exact failure the whole
      one-scorer rule exists to prevent.

      THE LIVE SPEC WINS THE SLOT, and that is the point rather than a tie-break: the live question is
      the one on the screen the answer is being typed into, so it must be the one the scorer counts
      and the one a coercion error is named after. Two LIVE specs under one key really is only a
      tie-break — first by `(sortOrder, key)`, which [customStageBlocks] has already applied — and
      nothing here can do better, because both questions are equally on the form.
    */
    val out = ArrayList<DwCustomFieldDto>()
    val slotOf = HashMap<String, Int>()
    customStageBlocks(cache, stageKey).forEach { block ->
        block.fields.forEach { field ->
            val slot = slotOf[field.key]
            if (slot == null) {
                slotOf[field.key] = out.size
                out += field
            } else if (out[slot].retired && !field.retired) {
                out[slot] = field
            }
        }
    }
    return out
}

/** The fields still being asked. What a form draws. */
fun liveCustomFields(section: DwCustomSectionDto): List<DwCustomFieldDto> =
    section.fields.filter { !it.retired }.sortedWith(compareBy({ it.sortOrder }, { it.key }))

/**
 * The fields no longer asked — but only where an answer stands against them.
 *
 * A retired field with no answer is a question the designer removed and nobody ever filled in;
 * drawing it would put a crossed-out row on the screen for no reason. A retired field WITH an answer
 * is evidence recorded under a wording that appears nowhere else, and hiding it is how two copies of
 * one report come to disagree about the fieldwork.
 */
fun retiredCustomFieldsWithAnswers(
    section: DwCustomSectionDto,
    values: Map<String, JsonElement>,
): List<DwCustomFieldDto> = section.fields
    .filter { it.retired && DwValues.isFilled(values[it.key]) }
    .sortedWith(compareBy({ it.sortOrder }, { it.key }))

// --------------------------------------------------------------------------------------
// The adapter: one custom field, drawn by the renderer this app already has
// --------------------------------------------------------------------------------------

/**
 * One custom field as the [FieldDto] the existing renderer draws.
 *
 * WHY AN ADAPTER AND NOT A SECOND RENDERER. [FieldRenderer] is 1,100 lines and already does
 * everything a custom question needs: the local text buffer that stops a MONEY field rewriting
 * "1250." under the cursor, the searchable ENUM with its blank row, the multi-select re-ordered to
 * declaration order, the locale-proof date entry, the tag chips, the inline error and the help line.
 * A second renderer would be a second set of answers to all of that, and the day the two disagreed a
 * designer would meet one question behaving unlike every other question on the same screen.
 *
 * **THE TYPE IS PASSED THROUGH RAW AND IS NOT CLAMPED**, which is where this differs from the web's
 * adapter and the difference is forced by the language. `FieldInput`'s `switch` has no `default`, so
 * the web re-tokenises an unsupported type into something no branch matches and lets the `default:`
 * arm catch it. Kotlin has no such arm to fall into: [FieldRenderer]'s `when` is exhaustive over a
 * closed enum and `DwFieldType.of` resolves ANY unknown token to TEXT — so a re-tokenised value would
 * arrive as an ordinary editable text box with no note at all. The refusal therefore has to happen
 * BEFORE the renderer is called, which is what [dwCustomFieldDrawable] is for and what
 * `DwCustomSectionForm` obeys. This function is only ever called for a field that already passed it.
 *
 * `reportRole` is left at `KEY_VALUE`, its default, so the report's `renderEntity` prints a custom
 * answer in the label/value grid beside the stage's own answers rather than inventing a role for it.
 * `id` is not carried across: the answer is stored under [DwCustomFieldDto.key], which is what makes
 * it survive a rewording.
 */
fun customFieldToFieldDto(field: DwCustomFieldDto): FieldDto = FieldDto(
    key = field.key,
    label = field.label,
    type = field.type,
    tier = field.tier,
    required = field.required,
    help = field.help,
    unit = field.unit,
    options = field.options.map { EnumOption(value = it.value, label = it.label) },
    maxLength = field.maxLength,
    minValue = field.minValue,
    maxValue = field.maxValue,
)

/**
 * The rendering identity of one custom section, and the one place that string is built.
 *
 * PER SECTION AND NOT `_custom`, because two sections on one stage rendered under one entity key
 * would collide in the search index's `stageKey/entityKey/fieldKey` address and in the readiness
 * screen's focus. The ANSWERS still live in one flat bucket keyed by field key — field keys are
 * unique across the whole workshop and the server enforces exactly that — so this is a rendering
 * identity only and never a storage one.
 */
fun customSectionEntityKey(section: DwCustomSectionDto): String = "$CUSTOM_ENTITY_KEY:${section.key}"

/**
 * One custom section as a synthetic SINGLETON [EntityDto], so [renderEntity] can print it unchanged.
 *
 * ONLY THE LIVE FIELDS GO IN, and only the DRAWABLE ones. A retired field is not offered again — that
 * is what retiring means — and an undrawable one must not reach a renderer at all: on the form it
 * would arrive at [FieldRenderer]'s `when` as TEXT and be drawn as an editable box, and in the report
 * `displayValue` would flatten a shape this build cannot interpret into something that looks like an
 * answer. Both are handled by their own arms — the read-only note on the form, and
 * [undrawableCustomFieldsWithValues] in the document — so that a question this build cannot read is
 * STATED rather than either faked or silently dropped.
 */
fun customSectionEntity(section: DwCustomSectionDto): EntityDto = EntityDto(
    key = customSectionEntityKey(section),
    name = "DwCustomSection",
    cardinality = "SINGLETON",
    title = section.title,
    description = section.description,
    fields = liveCustomFields(section).filter { dwCustomFieldDrawable(it.type) }.map(::customFieldToFieldDto),
)

/**
 * Live questions this build cannot draw, that nevertheless hold a stored value.
 *
 * THE REPORT'S HALF OF THE DEGRADE. [customSectionEntity] keeps these out of the label/value grid
 * because `displayValue` would flatten a shape this build cannot interpret — a v1.1 answer's raw JSON
 * — into something an officer would read as the designer's words. But dropping them without a word
 * would be the very failure this lane exists to end: a question silently absent from the field copy
 * and present in the office's. So they are named, and what is said about them is that this app cannot
 * read them.
 *
 * Only where a value is actually stored: a question nobody has answered needs no apology.
 */
fun undrawableCustomFieldsWithValues(
    section: DwCustomSectionDto,
    values: Map<String, JsonElement>,
): List<DwCustomFieldDto> = liveCustomFields(section)
    .filter { !dwCustomFieldDrawable(it.type) && DwValues.isFilled(values[it.key]) }

// --------------------------------------------------------------------------------------
// What a designer is told about a question this build cannot draw
// --------------------------------------------------------------------------------------

/**
 * The sentence under a custom question this build has no control for.
 *
 * **IT NAMES THE TOKEN, AS IT ARRIVED.** A note that will not say what the type was is a note a
 * designer cannot report, and the only person who will ever be standing in that cluster is the one
 * reading it. The register is taken from the REF-without-services help override in [FieldRenderer]:
 * short, present tense, second person, shown while somebody is filling in a form.
 *
 * IT PROMISES THAT NOTHING IS LOST, and that promise is kept by the code and not by this string: the
 * field is drawn `enabled = false` with no `onChange` wired, and the stored value is passed through
 * untouched by [buildStageBody], which sends the whole `custom` bucket rather than the fields it
 * happened to draw. The key travels under a question the SERVER's definition still carries — what
 * this build lacks is a control for the type, not the question — so `validate_custom_entry` coerces
 * and stores it. It is NOT resting on "the server carries unknown keys forward", which is false:
 * `plan_custom_write` does that for a RETIRED key only, and drops an unrecognised one into
 * `droppedCustomKeys` (executed: `sent={'other': 1}, previous={'loomsWorking': 12}, merge=False` →
 * `data={}, dropped=('other',)`).
 */
fun dwCustomUnsupportedNote(type: String): String =
    "This question is a ${type.ifBlank { "type this app was not told the name of" }} and this " +
        "version of the app cannot draw one. Whatever is already recorded against it is kept and is " +
        "not changed by anything you do here. Answer it on the web, or update the app."

/**
 * What to say beside the export buttons about this workshop's own questions.
 *
 * ONE SENTENCE AND ONLY FOR THE ONE STATE THAT DESERVES IT — a device that has never read the
 * definition. [DwCustomCopy.NONE_DEFINED] warns about nothing because there is nothing to warn about,
 * and [DwCustomCopy.DEFINED] warns about nothing because the questions and their answers are printed.
 * Warning on all three is what would put an apology on the majority of exports; the guard is
 * [DwQuestionnaireStore]'s, applied to the same shape of fact.
 *
 * **THAT SECOND CLAUSE WAS FALSE FOR AS LONG AS THE HANDSET DREW CUSTOM QUESTIONS INSIDE THE STAGE
 * SECTION, AND IT IS THE REASON THE LOSS WAS SILENT.** A section whose anchor stage the template
 * does not print — PHOTO_CATALOGUE carries three of the twenty-two, and stage 20's `excludedStages`
 * can remove any of the rest — had no code path to a renderer at all, so the .docx came off the
 * phone with the designer's own questions absent, this function said nothing because the copy was
 * DEFINED, and the export screen said nothing either. THE PLACEMENT HALF IS TRUE AGAIN NOW:
 * `report.applyReportSettings` splices an orphaned section in as its own back annexure, exactly as
 * `apply_report_settings` does, so every section a DEFINED copy holds now REACHES a renderer. What
 * the renderer then decides to print is a separate question, and two of its answers are still gaps —
 * see the numbered list below, which is what this claim must be read against.
 *
 * TWO STATES ARE STILL NOT COVERED, and this sentence named only the first for a while — which is
 * the failure the house style singles out, a comment reporting a gap as closed when it is not:
 *
 *  1. THE TIER CAP. A section whose every question sits ABOVE the template's cap prints nothing and
 *     warns nothing. This is the server's own uncovered state rather than a new one — `section_prints`
 *     names the same gap on the other side ("the quiet direction of the two") — and closing it means
 *     giving this function a template to ask, which it does not have and which the server's loader
 *     does not have either.
 *
 *  2. THE `section_prints` PREDICATE ITSELF, which is a HANDSET-ONLY gap and the sharper of the two.
 *     The server's `section_prints` is `has_content_at`: true for an answered section OR one carrying
 *     a LIVE REQUIRED question, so an unanswered required question prints a heading and
 *     "Not recorded." at the office. `renderCustomSection` returns early on `!anyLive &&
 *     retired.isEmpty()`, i.e. it has always demanded an actual answer — so that section prints
 *     NOTHING on this device, and a DEFINED copy really does not print every section it holds. It is
 *     left for its own change deliberately, and `renderCustomSection`'s KDoc argues why: moving the
 *     predicate in the same edit as the placement would have put two behaviours under one test.
 *
 * The paragraph above is still true of PLACEMENT, which is the claim it makes: an orphaned section
 * reaches the document. Neither of these is a section going missing because no renderer can be
 * reached; both are a renderer deciding, on a rule, to print nothing.
 *
 * **IT IS CONDITIONAL IN ITS WORDING ("if this workshop has questions of its own") FOR THE REASON THE
 * QUESTIONNAIRE ENTRY IN `UNSUPPORTED_SECTIONS` IS**: a device in this state cannot tell a workshop
 * with custom sections from one without, and an unconditional "answers are missing from this file"
 * would be false for most workshops.
 *
 * @param answersHeld whether this device's draft holds any custom answer for this workshop. It
 *   sharpens the sentence rather than gating it: answers on the phone with no definition to label
 *   them with is a strictly worse state than no answers at all, and the designer can act on both.
 */
fun dwCustomSectionWarnings(copy: DwCustomCopy, answersHeld: Boolean = false): List<String> {
    if (copy != DwCustomCopy.UNKNOWN) return emptyList()
    val tail = "Open this workshop on this phone once while you have a connection and they are kept " +
        "here for every export afterwards, including offline ones. The office's copy of this report " +
        "will carry them either way."
    return listOf(
        if (answersHeld) {
            "This device holds answers to questions that belong to this workshop's own sections, and " +
                "it has not read the sections themselves — so those answers are not in this file, " +
                "because nothing here knows what they were asked. $tail"
        } else {
            "If this workshop has questions of its own, the answers recorded against them are not in " +
                "this file. This device has not yet read this workshop's custom sections. $tail"
        }
    )
}
