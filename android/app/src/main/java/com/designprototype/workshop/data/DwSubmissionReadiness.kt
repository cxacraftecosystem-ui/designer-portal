package com.designprototype.workshop.data

import com.designprototype.workshop.report.summary as richSummary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/**
 * What still stops this workshop being submitted, assembled into one ranked, actionable list — the
 * handset's port of `frontend/lib/submissionReadiness.ts`.
 *
 * WHY THIS FILE IS ASSEMBLY AND NOT ARITHMETIC. Completeness is already computed, three times and
 * deliberately: `stage_completeness` on the server, `scoreStageData` in the browser and
 * [computeStageCompleteness] here, which are character-for-character ports of each other so a stage
 * cannot read "complete" on a phone and 422 on a submit. A FOURTH scorer written to drive a checklist
 * would be a fourth opinion, and the day it disagreed the designer would be told to go and fill in a
 * field the Save button was perfectly happy with. So the set of blocking items in this module is
 * [DwStageCompleteness.missing] and nothing else — the very list the report already prints in
 * `X-Report-Warnings` as "Stage 7 (…): 3 required field(s) not recorded". This module adds two things
 * to it and only two: an ADDRESS for each entry, and an order.
 *
 * WHY AN ADDRESS HAS TO BE DERIVED SEPARATELY. `missing` holds field LABELS, because that is what a
 * warning sentence and a report annexure need. A label cannot be navigated to. [stageAddresses]
 * therefore walks the registry a second time to work out WHERE each label lives — but it decides
 * nothing about completeness: it is a lookup keyed by the label the scorer already produced, and an
 * entry it fails to resolve degrades to a link to the stage rather than dropping the item. If the two
 * walks ever drift, the worst case is a link that lands on the stage instead of on the box; it can
 * never be a blocker that appears or vanishes. That asymmetry is the whole design.
 *
 * WHY IT IS PURE. No Compose, no Retrofit, no filesDir — it takes the registry and the local draft and
 * returns data, in the shape of [DwMarketAnalysis] and [DwCostIntegrity]. That is what lets the screen
 * answer "what is left?" on a handset that has had no signal for four days, which is the only place
 * the question is ever actually asked: nobody chases a submission from a desk with a connection, they
 * chase it in a courtyard on the last afternoon of the workshop.
 *
 * WHAT IS DELIBERATELY NOT HERE. Standard- and Advanced-tier gaps are reported as COUNTS PER STAGE
 * and never as items beside the blocking ones. They do not 422 a submit and they do not produce a
 * report warning; mixing them in would turn a list of four real obstacles into a list of two hundred
 * suggestions, and a designer who learns that most of this screen is optional stops reading the part
 * that is not.
 */

/* --------------------------------------------------------------------------------------
 * Shapes
 * -------------------------------------------------------------------------------------- */

/** Where an outstanding field actually is, so the item can be tapped rather than hunted for. */
data class DwReadinessAddress(
    val entityKey: String,
    val entityTitle: String,
    val fieldKey: String,
    val fieldLabel: String,
    /**
     * The field the stage screen should scroll to, which is [fieldKey] except for a caption — a
     * caption input is drawn under the media field it describes (see [FieldDto.captionFor]) and has
     * no wrapper of its own to land on.
     */
    val anchorFieldKey: String,
    /** The row's client key (or its server `_entryId`), so the right row of a collection opens. */
    val rowKey: String?,
    /** "Sketch 3", or the row's own title. Null for a singleton, which has no row to name. */
    val rowTitle: String?,
    /**
     * How many rows of this collection are missing this same field.
     *
     * `missing` is de-duplicated by label, exactly as the server de-duplicates it, so eleven prototype
     * rows with no material named are ONE entry. Printing "in 11 of 18 rows" is the difference between
     * an item a designer can size up and one that reads as a single forgotten box.
     */
    val occurrences: Int,
    /** How many rows of this collection exist at all, so [occurrences] has a denominator. */
    val rowCount: Int,
)

/** One thing that will 422 a submit, with somewhere to go. */
data class DwReadinessItem(
    val stageKey: String,
    val stageNumber: Int,
    val stageTitle: String,
    /** Exactly the string the scorer puts in `missing` — the report's own words, verbatim. */
    val label: String,
    /** Null only if the address walk could not place the label; the item still stands and still links. */
    val address: DwReadinessAddress?,
    val href: String,
)

/**
 * Where a readiness item points, in the terms THIS client can actually navigate by.
 *
 * [DwReadinessItem.href] stays exactly what it is — the web's URL, character for character, which is
 * what a designer pastes to a colleague and what `readStageFocus` reads back in a browser. It is
 * INERT on this client and must stay so: `AndroidManifest.xml` declares MAIN/LAUNCHER and no
 * `<intent-filter>` for that path, so there is nothing here to hand a URL to, and parsing our own
 * query string back into its parts would be a second spelling of an address we are already holding.
 * The screen navigates by [entityKey] / [fieldKey] / [rowKey]; the href is for people.
 */
data class DwStageFocus(
    val stageKey: String,
    val entityKey: String,
    /**
     * The box to scroll to — [DwReadinessAddress.anchorFieldKey], never a caption's own key.
     *
     * A caption input is drawn inside the media field it describes and has no wrapper of its own, so
     * focusing its key would scroll to nothing at all.
     */
    val fieldKey: String,
    /** The collection row that holds it — its `_clientKey`. Null for a singleton. */
    val rowKey: String? = null,
)

/** A report-blocking condition that is not a missing field. */
data class DwReadinessCheck(
    val id: String,
    val stageKey: String,
    val stageNumber: Int,
    val stageTitle: String,
    val title: String,
    val detail: String,
    val href: String,
)

/** A stage's optional-tier shortfall. Informational, and never ranked beside a blocker. */
data class DwReadinessStageGap(
    val stageKey: String,
    val stageNumber: Int,
    val stageTitle: String,
    val optionalTotal: Int,
    val optionalFilled: Int,
    val href: String,
)

data class DwWorkshopReadiness(
    val stagesTotal: Int,
    val stagesComplete: Int,
    val requiredTotal: Int,
    val requiredFilled: Int,
    /** How many stages still hold at least one unfilled required field. */
    val blockedStages: Int,
    /** Everything that 422s a submit, in stage order. */
    val blocking: List<DwReadinessItem>,
    /** Everything the report would warn about that is not a missing field. */
    val checks: List<DwReadinessCheck>,
    /** Optional-tier shortfalls, per stage, for the stages that have one. */
    val advisory: List<DwReadinessStageGap>,
    /**
     * Nothing here would refuse a submit.
     *
     * Deliberately NOT weakened by [checks]: a report generated from a substituted template is a
     * report, and telling a designer they may not submit over something the server would happily
     * accept is the kind of invented gate this repo has no business adding. The checks are shown;
     * they do not block.
     */
    val isSubmittable: Boolean,
)

object DwSubmissionReadiness {

    /* ----------------------------------------------------------------------------------
     * Stage 20's settings, by key
     *
     * Named rather than inlined because all three are read by the server too —
     * `resolve_template_id` and `apply_report_settings` — and a typo here would produce a check that
     * silently never fires, which is indistinguishable on screen from a workshop that has nothing
     * wrong with it.
     * ---------------------------------------------------------------------------------- */

    private const val REPORT_STAGE_KEY = "REPORT_GENERATION"
    private const val REPORT_SETTINGS_ENTITY = "reportSettings"
    private const val TEMPLATE_FIELD_KEY = "templateId"
    private const val EXCLUDED_STAGES_FIELD_KEY = "excludedStages"

    /* ----------------------------------------------------------------------------------
     * The link a row is tapped through to
     * ---------------------------------------------------------------------------------- */

    /** `?find=entityKey.fieldKey` — which box on the stage the designer asked for. */
    const val FOCUS_FIELD_PARAM = "find"

    /** `&row=<clientKey>` — which row of a collection holds it. Absent for a singleton. */
    const val FOCUS_ROW_PARAM = "row"

    /** The stage with nothing focused: where an item whose label could not be placed still goes. */
    fun stageHref(workshopId: String, stageKey: String): String =
        "/design-workshops/$workshopId/stages/$stageKey"

    /**
     * The link that opens one stage with one box on it focused — the port of `stageFieldHref` in
     * `lib/workshopSearch.ts`, which is where the web spells this and only there.
     *
     * IT IS THE WEB'S URL, CHARACTER FOR CHARACTER, and that is not decoration on a client that has
     * no address bar. A readiness item is what a designer sends to a colleague ("stage 13, the
     * material on the first prototype") and what the deep-link handler reads back; a second spelling
     * of the same query is a link that lands on the right stage with nothing highlighted, which is
     * the failure `readStageFocus` and this function exist as a pair to prevent.
     *
     * Encoded through [URLEncoder] rather than interpolated, because that is `URLSearchParams`'
     * serialisation exactly — the same unreserved set (`A-Za-z0-9*-._`) and the same space-as-plus —
     * so a client key that ever carried a character outside it cannot produce two different links.
     */
    fun stageFieldHref(
        workshopId: String,
        stageKey: String,
        entityKey: String,
        anchorFieldKey: String,
        rowKey: String? = null,
    ): String {
        val query = StringBuilder()
            .append(FOCUS_FIELD_PARAM).append('=').append(encode("$entityKey.$anchorFieldKey"))
        if (!rowKey.isNullOrEmpty()) {
            query.append('&').append(FOCUS_ROW_PARAM).append('=').append(encode(rowKey))
        }
        return "${stageHref(workshopId, stageKey)}?$query"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * The same destination as [DwReadinessItem.href], for the client that has no address bar.
     *
     * Null when the address walk could not place the label — and null means "open the stage with
     * nothing focused", which is the degradation [assess] already builds into `href` and NOT a reason
     * to drop the item. An obstacle a designer cannot tap is an obstacle they hunt for by eye through
     * several hundred fields, which is the whole thing this module exists to stop; landing on the
     * right form with the box unhighlighted is a far smaller failure.
     */
    fun focusOf(item: DwReadinessItem): DwStageFocus? {
        val address = item.address ?: return null
        return DwStageFocus(
            stageKey = item.stageKey,
            entityKey = address.entityKey,
            fieldKey = address.anchorFieldKey,
            rowKey = address.rowKey,
        )
    }

    /**
     * Every blocking label of every stage, addressed — `stageKey → (label → where it lives)`.
     *
     * Keyed by the label because the label is what the SCREEN already holds:
     * [DwStageCompleteness.missing] is the single source of blocking items (see the header), the
     * stage index prints those strings, and this map only decides where each one goes. A label it
     * has no entry for keeps its row and opens the stage; it is never dropped and never invented.
     */
    fun addressBook(readiness: DwWorkshopReadiness): Map<String, Map<String, DwStageFocus>> =
        readiness.blocking
            .mapNotNull { item -> focusOf(item)?.let { item.stageKey to (item.label to it) } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, entries) -> entries.toMap() }

    /* ----------------------------------------------------------------------------------
     * Addresses
     * ---------------------------------------------------------------------------------- */

    /** The label the scorer would file this field's absence under. Must match it exactly. */
    private fun missingLabel(entity: EntityDto, field: FieldDto): String =
        if (entity.cardinality == "SINGLETON") field.label else "${entity.title}: ${field.label}"

    /**
     * A row's stable identity, in the order the stage screen will recognise it.
     *
     * [DraftRow.id] is `entityKey#rowId`, and the suffix IS the `_clientKey` the sync sends and the
     * server matches on (see `buildStageBody`), so it is the same identity the web reads out of the
     * row object. The server's own `_entryId` is the fallback for the one shape that has neither: a
     * row seeded from a stage read before this build's key scheme existed.
     */
    private fun rowKeyOf(row: DraftRow): String? {
        val clientKey = row.id.substringAfter(DW_ROW_KEY_SEPARATOR, "")
        if (clientKey.isNotEmpty()) return clientKey
        return (row.values["_entryId"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
    }

    /**
     * How a designer recognises one row of a collection — the port of `rowTitle` in
     * `lib/designWorkshops.ts`.
     *
     * The declared label field first, then the first free text anybody typed into the row, and only
     * then "Prototypes 3". A row identified by its ordinal is a row the designer has to open to
     * recognise, and the whole promise of a readiness item is that it NAMES the thing to go and fix.
     *
     * RICH_TEXT is read through the report's own `summary` and not as a value. It stores an object,
     * so a plain read returns nothing for it, and a row whose only prose is a narrative would be
     * headed "Prototypes 3" — the case the web calls out by name. `summary` is the single
     * implementation of `rich_text.summary` on this client; a second one here would be a second
     * opinion about what a document says.
     */
    private fun rowTitleOf(entity: EntityDto, row: DwDataRow, index: Int): String {
        if (entity.labelField.isNotEmpty()) {
            // Read straight off the row rather than through the field spec, as the web does: a
            // labelField naming a key this build's registry has dropped still titles the row.
            val labelled = DwPy.strip(DwValues.text(row[entity.labelField]))
            if (labelled.isNotEmpty()) return labelled
        }
        for (field in entity.liveFields) {
            when (DwFieldType.of(field.type)) {
                DwFieldType.RICH_TEXT ->
                    richSummary(row[field.key], 80).takeIf { it.isNotEmpty() }?.let { return it }
                DwFieldType.TEXT, DwFieldType.LONG_TEXT ->
                    DwPy.strip(DwValues.text(row[field.key])).takeIf { it.isNotEmpty() }?.let { return it }
                else -> Unit
            }
        }
        return "${entity.title} ${index + 1}"
    }

    /**
     * Where every unfilled required field of one stage lives, keyed by the label the scorer files it
     * under.
     *
     * Reads values through the SAME two primitives [computeStageCompleteness] does — [StageDraft.values]
     * for the singletons, [StageDraft.rowsFor] for the rows, and [DwValues.isFilled] to judge a value
     * — so that "which fields are unfilled" cannot be answered differently here. What this function
     * owns is only the mapping from a label to a place.
     *
     * It walks EVERY singleton entity while the scorer scores the stage's first one, and that
     * direction is deliberate: a label this map holds and the scorer never files is simply never
     * looked up, while a label the scorer files and this map lacks costs the item its highlight and
     * nothing more. Erring the other way would be the only version of this that could lose an item.
     *
     * The FIRST offending row keeps the link. A designer sent to the first prototype missing its
     * material can work down the list from there; sending them to the last one would have them scroll
     * back up.
     */
    private fun stageAddresses(spec: StageDto, stored: StageDraft?): Map<String, DwReadinessAddress> {
        val singleton = stored?.values.orEmpty()
        val found = LinkedHashMap<String, DwReadinessAddress>()

        fun note(label: String, address: DwReadinessAddress) {
            val seen = found[label]
            // Second and later rows only raise the count: the address is the first one, on purpose.
            if (seen != null) found[label] = seen.copy(occurrences = seen.occurrences + 1)
            else found[label] = address
        }

        for (entity in spec.entities) {
            if (entity.cardinality == "SINGLETON") {
                for (field in entity.liveFields) {
                    if (!field.required) continue
                    if (DwValues.isFilled(singleton[field.key])) continue
                    note(
                        missingLabel(entity, field),
                        DwReadinessAddress(
                            entityKey = entity.key,
                            entityTitle = entity.title,
                            fieldKey = field.key,
                            fieldLabel = field.label,
                            anchorFieldKey = field.captionFor.ifEmpty { field.key },
                            rowKey = null,
                            rowTitle = null,
                            occurrences = 1,
                            rowCount = 1,
                        ),
                    )
                }
                continue
            }

            val rows = stored?.rowsFor(entity.key).orEmpty()
            rows.forEachIndexed { index, row ->
                for (field in entity.liveFields) {
                    if (!field.required) continue
                    if (DwValues.isFilled(row.values[field.key])) continue
                    note(
                        missingLabel(entity, field),
                        DwReadinessAddress(
                            entityKey = entity.key,
                            entityTitle = entity.title,
                            fieldKey = field.key,
                            fieldLabel = field.label,
                            anchorFieldKey = field.captionFor.ifEmpty { field.key },
                            rowKey = rowKeyOf(row),
                            rowTitle = rowTitleOf(entity, row.values, index),
                            occurrences = 1,
                            rowCount = rows.size,
                        ),
                    )
                }
            }
        }

        return found
    }

    /* ----------------------------------------------------------------------------------
     * The report's own checks, beyond the fields
     * ---------------------------------------------------------------------------------- */

    /**
     * The stage-20 conditions that change the delivered document without failing anything.
     *
     * Both are computable from the registry and the draft alone, which is the bar for putting a check
     * here at all: a warning that needs a connection to be raised is a warning a designer in a
     * village never sees. The transcript-annexure warning (`annexure_warnings`) deliberately has no
     * counterpart here — whether a recording has been transcribed is a fact that lives on the
     * server's media queue and cannot be known offline, and guessing at it would be exactly the
     * fabricated confidence the repository refuses elsewhere.
     */
    private fun reportChecks(
        schema: SchemaResponse,
        draft: WorkshopDraft?,
        workshopId: String,
        scores: Map<String, DwStageCompleteness>,
    ): List<DwReadinessCheck> {
        val spec = schema.stages.firstOrNull { it.key == REPORT_STAGE_KEY } ?: return emptyList()
        val entity = spec.entity(REPORT_SETTINGS_ENTITY) ?: return emptyList()

        val settings = draft?.stages?.get(spec.key)?.values.orEmpty()
        val out = ArrayList<DwReadinessCheck>()
        fun at(fieldKey: String) = stageFieldHref(workshopId, spec.key, entity.key, fieldKey)

        /*
         * THE TEMPLATE PICKER, AGAINST THE TEMPLATES THAT EXIST. `resolve_template_id` ignores a token
         * it does not recognise rather than obeying it, and the builder then reports a substitution —
         * so a stage-20 answer left over from a build that offered a template this one does not
         * produces a different document from the one named on the form, and says so only in a header
         * beside the download. The field is required and Basic, so the EMPTY case is already a
         * blocking item above; this catches the case where it is filled in and inert.
         *
         * [DwPy.strip] and not `trim()`, because the comparison is against what `str(candidate).strip()`
         * on the server will make of the same token: Python strips the no-break space and Kotlin's
         * `trim()` does not, so a value pasted with one attached would be judged unknown here and
         * accepted there — a warning about a template that is in fact going to be used.
         */
        val templateField = entity.field(TEMPLATE_FIELD_KEY)
        val chosen = (settings[TEMPLATE_FIELD_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val token = chosen?.let { DwPy.strip(it) }.orEmpty()
        if (templateField != null && token.isNotEmpty()) {
            // Inlined options first, then the shared enum they were inlined from. An empty result
            // means the registry did not inline them and holds no matching enum — a shape this build
            // cannot judge. Saying nothing is the honest answer; asserting the template is unknown on
            // the strength of a list we failed to read would send a designer to change a correct answer.
            val options = templateField.options.ifEmpty { schema.enums[templateField.enumName].orEmpty() }
            if (options.isNotEmpty() && options.none { it.value == token }) {
                out.add(
                    DwReadinessCheck(
                        id = "template-unknown",
                        stageKey = spec.key,
                        stageNumber = spec.number,
                        stageTitle = spec.title,
                        title = "The report template chosen in stage 20 is not one this repository offers",
                        detail =
                            "Stage 20 names \"$token\", which is not in the list of templates available here. The " +
                                "report will be generated from the workshop's own template instead, and the file will not " +
                                "match the format named on the form. Re-pick the template to settle it.",
                        href = at(TEMPLATE_FIELD_KEY),
                    )
                )
            }
        }

        /*
         * STAGES DELIBERATELY LEFT OUT — but only where leaving one out actually loses something.
         * Excluding an empty stage is what the setting is FOR, and warning about it would train a
         * designer to ignore this section. Whether a stage holds anything is read off the score that
         * is already computed, so this costs one lookup and no traversal.
         */
        val excluded = settings[EXCLUDED_STAGES_FIELD_KEY]
        // Only JSON strings, so a TAGS list that somehow holds a number names no stage rather than
        // naming one whose key happens to stringify to the same thing. `DwValues.list` would coerce
        // instead, and this list is matched against registry keys, not shown to anybody.
        val excludedKeys = (excluded as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
        for (stageKey in excludedKeys) {
            val target = schema.stages.firstOrNull { it.key == stageKey } ?: continue
            val score = scores[target.key] ?: continue
            if (score.requiredFilled == 0 && score.optionalFilled == 0) continue
            out.add(
                DwReadinessCheck(
                    id = "stage-excluded:${target.key}",
                    stageKey = target.key,
                    stageNumber = target.number,
                    stageTitle = target.title,
                    title = "Stage ${target.number} is excluded from the report but has answers in it",
                    detail =
                        "${score.requiredFilled + score.optionalFilled} field(s) are filled in on \"${target.title}\", and " +
                            "stage 20 leaves this stage out of the generated report. That is a legitimate choice — but the " +
                            "work will not appear in the file that is submitted.",
                    // The link goes to STAGE 20, not to the excluded stage the entry names: the box
                    // that would settle this is the exclusion list, and sending a designer to the
                    // stage itself offers them nothing to change.
                    href = at(EXCLUDED_STAGES_FIELD_KEY),
                )
            )
        }

        return out
    }

    /* ----------------------------------------------------------------------------------
     * The assembly
     * ---------------------------------------------------------------------------------- */

    /**
     * Everything standing between this draft and a submission — the port of `workshopReadiness`.
     *
     * [workshopId] is what the links are built against and is the caller's to choose: a workshop
     * created offline is reached by its local id (see [DW_LOCAL_ID_PREFIX]) and a synced one by the
     * server's, and this module must not have an opinion about which — getting it wrong produces a
     * whole screen of links to a workshop that does not exist under that name.
     *
     * [draft] is NULLABLE where the web's is not, and that is this client's own state rather than a
     * loosening: a workshop whose draft.json has not been written yet is the state the phone is in
     * the moment a stage index is opened on a record it has only ever seen in a list, and the honest
     * answer for it is "every required field is outstanding", not a crash.
     *
     * ORDERED BY STAGE NUMBER, not by severity within the blocking set. Every entry in that set
     * blocks equally (they are all the same 422), so the only ordering left that means anything is
     * the one the designer already has in their head from the 22-stage index they arrived from. A
     * list sorted by "most missing first" would put stage 13 above stage 1 and cost a re-read on
     * every visit. [computeWorkshopCompleteness] already returns its scores in that order, and
     * walking ITS list rather than the registry's is what keeps the two in step even if the server
     * ever serves the stages out of order.
     */
    fun assess(schema: SchemaResponse, draft: WorkshopDraft?, workshopId: String): DwWorkshopReadiness {
        // THE SINGLE AUTHORITY. Everything below counts, addresses and orders what this returned;
        // nothing below decides whether a field is filled.
        val scores = computeWorkshopCompleteness(schema, draft)
        val specs = schema.stages.associateBy { it.key }

        val blocking = ArrayList<DwReadinessItem>()
        val advisory = ArrayList<DwReadinessStageGap>()
        var stagesComplete = 0
        var requiredTotal = 0
        var requiredFilled = 0
        var blockedStages = 0

        for (score in scores) {
            val spec = specs[score.stageKey] ?: continue

            requiredTotal += score.requiredTotal
            requiredFilled += score.requiredFilled
            if (score.isComplete) stagesComplete += 1

            if (score.missing.isNotEmpty()) {
                blockedStages += 1
                val addresses = stageAddresses(spec, draft?.stages?.get(spec.key))
                for (label in score.missing) {
                    val address = addresses[label]
                    blocking.add(
                        DwReadinessItem(
                            stageKey = spec.key,
                            stageNumber = spec.number,
                            stageTitle = spec.title,
                            label = label,
                            address = address,
                            // A label the walk could not place still gets a link — to the stage, with
                            // nothing focused. An item a designer cannot act on has failed, and
                            // landing on the right form with the box unhighlighted is a far smaller
                            // failure than an entry that goes nowhere.
                            href = if (address != null) {
                                stageFieldHref(
                                    workshopId, spec.key, address.entityKey, address.anchorFieldKey, address.rowKey,
                                )
                            } else {
                                stageHref(workshopId, spec.key)
                            },
                        )
                    )
                }
            }

            if (score.optionalFilled < score.optionalTotal) {
                advisory.add(
                    DwReadinessStageGap(
                        stageKey = spec.key,
                        stageNumber = spec.number,
                        stageTitle = spec.title,
                        optionalTotal = score.optionalTotal,
                        optionalFilled = score.optionalFilled,
                        href = stageHref(workshopId, spec.key),
                    )
                )
            }
        }

        return DwWorkshopReadiness(
            stagesTotal = schema.stages.size,
            stagesComplete = stagesComplete,
            requiredTotal = requiredTotal,
            requiredFilled = requiredFilled,
            blockedStages = blockedStages,
            blocking = blocking,
            checks = reportChecks(schema, draft, workshopId, scores.associateBy { it.stageKey }),
            advisory = advisory,
            isSubmittable = blocking.isEmpty(),
        )
    }

    /**
     * The progress sentence, stated honestly rather than as a percentage — the port of
     * `readinessSummary`.
     *
     * "82%" is the figure this screen must NOT lead with: it is the same number whether the remaining
     * 18% is one date field or a stage nobody has opened, and a designer reads it as "nearly there"
     * in both cases. Counting the stages and then counting the fields inside them gives the two
     * numbers that actually decide whether the afternoon is enough.
     */
    fun summary(readiness: DwWorkshopReadiness): String {
        val stages = "${readiness.stagesComplete} of ${readiness.stagesTotal} stages complete"
        if (readiness.blocking.isEmpty()) return "$stages; no required fields remain."
        val count = readiness.blocking.size
        val fields = "$count required field${if (count == 1) "" else "s"}"
        val where = "${readiness.blockedStages} stage${if (readiness.blockedStages == 1) "" else "s"}"
        return "$stages; $fields remain${if (count == 1) "s" else ""} in $where."
    }

    /**
     * How an item names the exact box, for a reader who is deciding whether to tap it — the port of
     * `itemFieldName`.
     *
     * Kept beside the model rather than in the composable because the spec asserts on it: the promise
     * this screen makes is that it NAMES the field, and a test that only checked a link existed would
     * pass against a list of twenty-two rows reading "something is missing".
     */
    fun fieldName(item: DwReadinessItem): String {
        val address = item.address ?: return item.label
        val rowTitle = address.rowTitle ?: return address.fieldLabel
        return "${address.fieldLabel} · $rowTitle"
    }
}
