package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.field
import com.designprototype.workshop.data.rowsFor
import com.designprototype.workshop.report.ChartBlock
import com.designprototype.workshop.report.ChartKind
import com.designprototype.workshop.report.DocumentBuilder
import com.designprototype.workshop.report.MapBlock
import com.designprototype.workshop.report.MapPoint
import com.designprototype.workshop.report.MapPointKind
import com.designprototype.workshop.report.PageBreakBlock
import com.designprototype.workshop.report.ParaStyle
import com.designprototype.workshop.report.TemplateSection
import com.designprototype.workshop.report.canonicalState
import com.designprototype.workshop.report.groupIndian
import kotlinx.serialization.json.JsonElement

/**
 * The report's infographics and its locator map, built on the handset — the port of the figure half
 * of `backend/app/services/report_builder.py`.
 *
 * WHAT THIS ENDS. [com.designprototype.workshop.report.ReportChart.renderChartPng] and
 * [com.designprototype.workshop.report.renderMapPng] have shipped in the APK the whole time, both
 * writers dispatch `ChartBlock` and `MapBlock`, and the India boundary rings are an offline asset
 * installed by `ReportExport` — every part of the drawing was present and complete. Nothing
 * CONSTRUCTED a block. So the DEFAULT template, DCH_STANDARD, which places two figures on its front
 * page and a map under the participant roster, came off the phone with no figures at all: the officer
 * standing in the courtyard read six rows of a cost table where the office read the picture. This
 * file is the arithmetic and the emit, not a rendering engine.
 *
 * IT IS A PORT AND THE SERVER IS THE AUTHORITY. Every threshold below — two categories minimum, only
 * cost heads that carry a figure, only follow-up intervals that were actually visited, a price
 * ladder rather than `max/6` — is the server's, reproduced rather than re-decided, because a figure
 * that draws on the phone and not at the office (or draws differently) is the exact divergence the
 * whole report port exists to end.
 *
 * WHAT IS DELIBERATELY NOT PORTED: the map's artisan pins. See [renderMap].
 */

// --------------------------------------------------------------------------------------
// Thresholds and bands — `report_builder` module constants
// --------------------------------------------------------------------------------------

/**
 * The fewest categories worth a picture — `report_builder.MIN_CHART_CATEGORIES`.
 *
 * A one-bar bar chart carries exactly what the metric row above it already carries, at the cost of a
 * third of a page, and a single-slice donut is a filled circle. Two is where a picture starts to say
 * something the prose does not.
 */
private const val MIN_CHART_CATEGORIES = 2

/**
 * Band widths for the price histogram, in rupees, smallest first — `report_builder._PRICE_STEPS`.
 *
 * A ladder rather than `max/6` so the boundaries are numbers a person would choose: a cost sheet
 * binned at "₹ 0–1,383" is arithmetically correct and unreadable.
 */
private val PRICE_STEPS = doubleArrayOf(
    100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0, 25000.0, 50000.0, 100000.0,
)

/** How many bands a price histogram may have — what fits a text column with legible labels. */
private const val MAX_PRICE_BANDS = 6

/**
 * Bin prices into readable bands, or nothing where there is no distribution — `_price_bands`.
 *
 * Nothing rather than one full band when every product carries the same price. That is a real state —
 * a cluster that agreed one price for its whole range — and it draws as a single bar carrying no
 * information the price column does not already carry.
 */
internal fun priceBands(values: List<Double>): List<Pair<String, Double>> {
    val positive = values.filter { it > 0 }.sorted()
    if (positive.size < 3) return emptyList()
    val top = positive.last()
    if (top <= positive.first()) return emptyList()
    // The narrowest ladder step that keeps the histogram inside its band budget. The final fallback
    // is the widest step, which cannot itself divide by zero because every entry is positive — the
    // guard that matters, since `top` is caller data.
    val step = PRICE_STEPS.firstOrNull { top / it <= MAX_PRICE_BANDS } ?: PRICE_STEPS.last()
    val counts = LinkedHashMap<Long, Int>()
    // Sorted ascending already, so the keys arrive in band order and the LinkedHashMap needs no
    // second sort — which also keeps the phone's band order identical to the server's `sorted()`.
    for (value in positive) {
        val index = Math.floor(value / step).toLong()
        counts[index] = (counts[index] ?: 0) + 1
    }
    return counts.entries.map { (index, count) ->
        val low = fixedZero(index * step)
        val high = fixedZero((index + 1) * step - 1)
        "${groupIndian(low)}–${groupIndian(high)}" to count.toDouble()
    }
}

/**
 * A double as Python's `f"{value:.0f}"` renders it — half-to-EVEN on the exact binary value.
 *
 * `String.format("%.0f", …)` rounds HALF_UP, so a band boundary landing exactly on .5 would be
 * labelled one rupee apart on the two copies of one report. The band edges here are products of a
 * ladder step and an integer so a tie is unlikely, but "unlikely" is how the last two rounding
 * divergences in this port got in.
 */
private fun fixedZero(value: Double): String =
    java.math.BigDecimal(value).setScale(0, java.math.RoundingMode.HALF_EVEN).toPlainString()

// --------------------------------------------------------------------------------------
// The figure catalogue
// --------------------------------------------------------------------------------------

/**
 * EVERY FIGURE THE REPORT CAN CONTAIN, by the public id a template names — `report_builder.FIGURES`.
 *
 * The id crosses a module boundary: [TemplateSection.figures] names one, and the pinned template
 * catalogue in `report_templates_pin.json` carries those names, so `ReportTemplatePinTest` fails the
 * build if the two sides ever disagree about what "OUTPUT_COUNTS" is. The value is the stage whose
 * data the figure is about, which is what [DwFigures.chartsFor] matches a stage section against.
 *
 * A stage absent from this table has no figure, which is most of them. A chart is worth a third of a
 * page and has to earn it.
 */
internal val REPORT_FIGURES: Map<String, String> = linkedMapOf(
    "OUTPUT_COUNTS" to "WORKSHOP_OUTCOMES",
    "PROTOTYPE_STATUS" to "WORKSHOP_OUTCOMES",
    "COST_BY_HEAD" to "COSTING_MARKET_LINKAGE",
    "PRICE_BANDS" to "COSTING_MARKET_LINKAGE",
    "ADOPTION" to "POST_WORKSHOP_FOLLOWUP",
)

/**
 * The five infographics, built from one draft, each at most once per document.
 *
 * ONE INSTANCE PER EXPORT is the whole reason this is a class rather than five functions.
 * DCH_STANDARD places `OUTPUT_COUNTS` and `PROTOTYPE_STATUS` explicitly at the front AND carries the
 * outcomes stage section that owns both, so a stateless builder would print the yield chart twice in
 * one report and make a reader hunt for the difference between two identical pictures. [drawn] is
 * `ReportBuilder._drawn`: the first place the template asks for a figure wins, which makes the
 * running order the thing that decides, exactly as it does for every other section.
 */
internal class DwFigures(
    private val schema: SchemaResponse,
    private val draft: WorkshopDraft?,
) {
    private val drawn = HashSet<String>()

    private fun singleton(stageKey: String): Map<String, JsonElement> =
        draft?.stages?.get(stageKey)?.values.orEmpty()

    private fun rows(stageKey: String, entityKey: String): List<Map<String, JsonElement>> =
        draft?.stages?.get(stageKey)?.rowsFor(entityKey).orEmpty().map { it.values }

    private fun fieldOf(stageKey: String, entityKey: String, fieldKey: String) =
        schema.stages.firstOrNull { it.key == stageKey }
            ?.entities?.firstOrNull { it.key == entityKey }
            ?.field(fieldKey)

    /**
     * A field's registry label, so a figure's axis and its table's header cannot disagree.
     *
     * Hard-coding "Material" beside a column the registry calls "Material cost" is the kind of
     * difference nobody reads as a bug and everybody reads as two different measures.
     */
    private fun labelOf(stageKey: String, entityKey: String, fieldKey: String, fallback: String) =
        fieldOf(stageKey, entityKey, fieldKey)?.label?.takeIf { it.isNotBlank() } ?: fallback

    /**
     * How many of something this report states there were — `ReportBuilder._output_count`.
     *
     * ONE FUNCTION on the server because the number has to be the same everywhere it appears, and the
     * same rule here: [renderSummaryMetrics] derives the front-page row exactly this way, including
     * the override and the `>= 0` guard on it, so the yield chart and the metric row above it cannot
     * state two different numbers for one measure.
     */
    private fun outputCount(stageKey: String, entityKey: String, overrideKey: String = ""): Int {
        val counted = rows(stageKey, entityKey).size
        if (overrideKey.isEmpty()) return counted
        val stated = asFiniteNumber(singleton("WORKSHOP_OUTCOMES")[overrideKey]) ?: return counted
        if (stated < 0) return counted
        return stated.toInt()
    }

    /** Why the stated counts differ from the records, if the designer said. */
    private fun countOverrideReason(): String {
        val outcomes = singleton("WORKSHOP_OUTCOMES")
        val overridden = listOf("designsCountOverride", "prototypesCountOverride")
            .any { asFiniteNumber(outcomes[it]) != null }
        if (!overridden) return ""
        return DwValues.text(outcomes["countOverrideReason"]).trim()
    }

    /**
     * Sketches against prototypes against final products — the workshop's yield.
     *
     * Through [outputCount], so this figure and the metric row on the front page state the same
     * numbers. They used to derive independently on the server while stage 18's overrides printed raw
     * beside them, which is how one report came to carry two different figures for one measure.
     */
    private fun chartOutputCounts(): ChartBlock? {
        val counts = listOf(
            "Sketches" to outputCount("SKETCH_DEVELOPMENT", "sketch", "designsCountOverride"),
            "Prototypes" to outputCount("PROTOTYPE_DEVELOPMENT", "prototype", "prototypesCountOverride"),
            "Final products" to outputCount("FINAL_PROTOTYPE_DOCUMENTATION", "finalProduct"),
        )
        val series = counts.filter { it.second != 0 }.map { it.first to it.second.toDouble() }
        if (series.size < MIN_CHART_CATEGORIES) return null
        val reason = countOverrideReason()
        return ChartBlock(
            kind = ChartKind.BAR,
            series = series,
            title = "Designs, prototypes and final products",
            caption = reason.ifEmpty { "Counted from the records in this report, not typed." },
        )
    }

    /** How the review dispositioned each prototype: selected, revised, rejected, pending. */
    private fun chartPrototypeStatus(): ChartBlock? {
        val spec = fieldOf("PROTOTYPE_VALIDATION", "prototypeValidation", "decision") ?: return null
        // A LinkedHashMap keyed by LABEL, which is what the server's plain dict is: the slices run in
        // the order the decisions were first met in the record, not alphabetically, so the phone's
        // donut and the office's put the same colour on the same decision.
        val tally = LinkedHashMap<String, Int>()
        for (row in rows("PROTOTYPE_VALIDATION", "prototypeValidation")) {
            val token = DwValues.text(row["decision"]).trim()
            if (token.isEmpty()) continue
            val label = spec.options.firstOrNull { it.value == token }?.label ?: token
            tally[label] = (tally[label] ?: 0) + 1
        }
        if (tally.size < MIN_CHART_CATEGORIES) return null
        return ChartBlock(
            kind = ChartKind.DONUT,
            series = tally.map { (label, count) -> label to count.toDouble() },
            title = "Prototypes by review decision",
            caption = "${tally.values.sum()} prototype(s) reviewed.",
        )
    }

    /**
     * Where the money went, summed across every cost sheet in the record.
     *
     * ONLY HEADS THAT CARRY A FIGURE APPEAR. A cost breakdown listing "Transport ₹ 0" beside four
     * real heads is read as "transport was free", when what the record says is that nobody entered
     * it — and on a document that becomes a sanctioned amount, the difference between those two
     * readings is the whole point of the sheet.
     */
    private fun chartCostByHead(): ChartBlock? {
        val heads = listOf(
            "materialCost", "labourCost", "packagingCost",
            "finishingCost", "transportCost", "overheadCost",
        )
        val totals = LinkedHashMap<String, Double>()
        heads.forEach { totals[it] = 0.0 }
        for (row in rows("COSTING_MARKET_LINKAGE", "costSheet")) {
            for (key in heads) {
                val amount = asFiniteNumber(row[key])
                if (amount != null && amount > 0) totals[key] = totals.getValue(key) + amount
            }
        }
        val series = totals.entries
            .filter { it.value > 0 }
            .map { (key, value) -> labelOf("COSTING_MARKET_LINKAGE", "costSheet", key, key) to value }
        if (series.size < MIN_CHART_CATEGORIES) return null
        return ChartBlock(
            kind = ChartKind.HORIZONTAL_BAR,
            series = series,
            title = "Cost by head, all products",
            unit = "INR",
            caption = "Summed across every cost sheet recorded at this workshop.",
        )
    }

    /** How many products fall in each price band — the range the workshop actually produced. */
    private fun chartPriceBands(): ChartBlock? {
        val prices = ArrayList<Double>()
        for (row in rows("COSTING_MARKET_LINKAGE", "costSheet")) {
            // Expected price first and retail second, in that order — the same `next(...)` the server
            // runs. A product quoted both must contribute exactly one price to the histogram.
            val amount = listOf(asFiniteNumber(row["expectedPrice"]), asFiniteNumber(row["retailPrice"]))
                .firstOrNull { it != null && it > 0 }
            if (amount != null) prices.add(amount)
        }
        val bands = priceBands(prices)
        if (bands.size < MIN_CHART_CATEGORIES) return null
        return ChartBlock(
            kind = ChartKind.BAR,
            series = bands,
            title = "Products by price band",
            unit = "products",
            caption = "${prices.size} product(s) with a recorded price, in rupees.",
        )
    }

    /**
     * Products still being made at three, six and twelve months after the workshop.
     *
     * THE INTERVAL ORDER COMES FROM THE REGISTRY'S OWN ENUM rather than from sorting the labels,
     * because "M12" sorts before "M3" and a follow-up line that goes 3 → 12 → 6 months reads as a
     * collapse and a recovery that never happened. The registry inlines each ENUM field's options in
     * declaration order, which is exactly what `ENUMS[...]` preserves on the server.
     */
    private fun chartAdoption(): ChartBlock? {
        val spec = fieldOf("POST_WORKSHOP_FOLLOWUP", "followUp", "interval") ?: return null
        val order = spec.options.map { it.value }.filter { it != "AD_HOC" }
        val adopted = setOf("ADOPTED_IN_PRODUCTION", "ADOPTED_ON_ORDER")
        val tally = HashMap<String, Int>()
        val seen = HashSet<String>()
        for (row in rows("POST_WORKSHOP_FOLLOWUP", "followUp")) {
            val token = DwValues.text(row["interval"]).trim()
            if (token !in order) continue
            seen.add(token)
            if (DwValues.text(row["adoptionStatus"]).trim() in adopted) {
                tally[token] = (tally[token] ?: 0) + 1
            }
        }
        // ONLY INTERVALS THAT WERE ACTUALLY VISITED. A twelve-month column on a workshop that ran
        // four months ago is not "zero adoption", it is a visit that has not happened yet.
        val series = order.filter { it in seen }.map { token ->
            val label = spec.options.firstOrNull { it.value == token }?.label ?: token
            label to (tally[token] ?: 0).toDouble()
        }
        if (series.size < MIN_CHART_CATEGORIES || series.sumOf { it.second } <= 0) return null
        return ChartBlock(
            kind = ChartKind.LINE,
            series = series,
            title = "Products still in production at follow-up",
            unit = "products",
            caption = "Counted from follow-up visits that were actually made.",
        )
    }

    /** Build one figure by id, once per document — `ReportBuilder._figure`. */
    internal fun figure(figureId: String): ChartBlock? {
        if (figureId !in REPORT_FIGURES || figureId in drawn) return null
        val block = when (figureId) {
            "OUTPUT_COUNTS" -> chartOutputCounts()
            "PROTOTYPE_STATUS" -> chartPrototypeStatus()
            "COST_BY_HEAD" -> chartCostByHead()
            "PRICE_BANDS" -> chartPriceBands()
            "ADOPTION" -> chartAdoption()
            else -> null
        }
        // A builder returns null when the record cannot fill the figure; the total guard is the belt
        // to that braces. A series that survived every rule above and still sums to zero must not be
        // drawn: a pie of zeros is a division by zero waiting for the first renderer that forgets to
        // check, and a bar chart of zeros is a flat line presented as a finding.
        if (block == null || block.total <= 0) return null
        drawn.add(figureId)
        return block
    }

    /** Every not-yet-drawn figure this stage's data can honestly support. */
    internal fun chartsFor(stageKey: String): List<ChartBlock> =
        REPORT_FIGURES.filterValues { it == stageKey }.keys.mapNotNull { figure(it) }
}

/**
 * A `CHART` section: the figures the template asked for, under one heading — `_render_charts`.
 *
 * NOTHING AT ALL IS EMITTED when the data supports none of them — not the heading, not an empty
 * frame, not a note. A heading over nothing is the single most common way a generated report looks
 * broken, and this section exists precisely for records that may have no figures in them yet.
 */
internal fun renderCharts(
    builder: DocumentBuilder,
    section: TemplateSection,
    plan: ReportPlan,
    figures: DwFigures,
) {
    val wanted = section.figures.ifEmpty { REPORT_FIGURES.keys.toList() }
    val blocks = wanted.mapNotNull { figures.figure(it) }
    if (blocks.isEmpty()) return

    if (section.pageBreakBefore) builder.add(PageBreakBlock)
    builder.heading(
        section.heading.ifBlank { "The workshop in figures" },
        level = 1,
        numbered = plan.template.numberHeadings,
    )
    if (section.intro.isNotBlank()) builder.para(section.intro, style = ParaStyle.LEAD)
    blocks.forEach(builder::add)
}

// --------------------------------------------------------------------------------------
// The map
// --------------------------------------------------------------------------------------

/** The stage whose singleton says where the workshop was — `report_builder.MAP_VENUE_STAGE`. */
private const val MAP_VENUE_STAGE = "WORKSHOP_SETUP"

/** The roster whose rows the OFFICE's copy turns into home-district pins. */
private const val MAP_ROSTER_STAGE = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"
private const val MAP_ROSTER_ENTITY = "participant"

/**
 * A stored GEO value as `(lat, lon)`, or null for anything that is not a real fix — `_geo_point`.
 *
 * `0, 0` is rejected along with the unparseable. It is the Gulf of Guinea, it is what a form that
 * never obtained a fix writes, and [com.designprototype.workshop.report.renderMapPng] would drop it
 * off the edge of India anyway — but rejecting it here means the map is drawn as the region-only
 * case with an honest caption instead of quietly losing its pin somewhere in the Atlantic.
 */
private fun geoFix(value: JsonElement?): Pair<Double, Double>? {
    val (lat, lon) = DwValues.geo(value) ?: return null
    if (!lat.isFinite() || !lon.isFinite()) return null
    if (Math.abs(lat) < 0.0001 && Math.abs(lon) < 0.0001) return null
    return lat to lon
}

/**
 * The workshop's map, once stage 1 says which part of the country this is — `_render_map`.
 *
 * THE GATE IS A STATE OR A DISTRICT AND NOTHING ELSE, which is the server's gate verbatim. A map of
 * India with no idea which bit of it the workshop happened in is a decoration; but a workshop that
 * has named its state has said enough for the tinted region alone to be worth printing, even before
 * a single address resolves. That is why the block is emitted with an EMPTY point list rather than
 * skipped — `renderMapPng` draws that case deliberately, and a figure that vanished whenever the
 * village names were unfamiliar would read to a designer as a broken renderer rather than as
 * unresolvable data.
 *
 * ── WHAT THIS DRAWS AND WHAT IT REFUSES TO DRAW ───────────────────────────────────────────────────
 *
 * THE TINTED STATE is identical to the office's, because [canonicalState] is the same strict
 * resolver `address.canonical_state` is and the boundary rings are the same geometry.
 *
 * THE VENUE PIN is drawn ONLY from `venueLocation`, the coordinate somebody stood at and captured.
 * That is the server's own FIRST branch — "the measured fix wins and is never averaged with
 * anything" — so a pin drawn here lands on the identical coordinate the office's copy puts it on.
 * Where there is no fix the server falls back to geocoding the typed address through a place atlas
 * that is NOT on this device, and this renderer draws no pin at all rather than inventing one: a pin
 * the office cannot reproduce is worse than no pin, because the two copies of one report would then
 * disagree about where the workshop was.
 *
 * THE ARTISANS' HOME DISTRICTS ARE NOT DRAWN, deliberately, and this is the one place the phone's
 * figure is a subset of the office's rather than a copy of it. The office resolves each roster row
 * through the `Artisan` record it was picked from; the cached artisan record on this device carries
 * a `village` and no district and no state, and the district anchors the server geocodes against are
 * a running average over live `Location` rows rather than a table that could ship in an APK.
 * Approximating those pins from what IS here would fold twenty Bargarh artisans onto Bhubaneswar —
 * the precise defect the server fixed by refusing to fall back to the state capital — in the copy
 * least likely to be checked against anything. The caption says so instead, in the sentence slot the
 * server uses for the same class of fact.
 *
 * THE HEADING IS THE TEMPLATE'S, UNCHANGED, even though DCH_STANDARD's names the artisans this
 * device does not place. A heading rewritten here would renumber nothing but WOULD put a different
 * line in the contents page of the phone's copy than in the office's, which is the divergence an
 * office notices first when it matches two files. The caption is where the difference belongs.
 */
internal fun renderMap(
    builder: DocumentBuilder,
    section: TemplateSection,
    plan: ReportPlan,
    draft: WorkshopDraft?,
) {
    val setup = draft?.stages?.get(MAP_VENUE_STAGE)?.values.orEmpty()
    fun setupText(key: String) = DwValues.text(setup[key]).trim()

    val state = setupText("state")
    val district = setupText("district")
    if (state.isEmpty() && district.isEmpty()) return

    val label = listOf("venue", "village", "block", "district")
        .map(::setupText)
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
    val fix = geoFix(setup["venueLocation"])
    val points = if (fix == null) {
        emptyList()
    } else {
        listOf(
            MapPoint(
                label = label.ifEmpty { "Workshop venue" },
                lat = fix.first,
                lon = fix.second,
                kind = MapPointKind.VENUE,
            )
        )
    }

    // The state stage 1 typed, canonicalised. The server also adds every state a PIN landed in; the
    // only pin this device can draw is the venue, whose state is the one already added, so the two
    // sets are the same here.
    val highlight = if (state.isEmpty()) emptySet() else setOf(canonicalState(state) ?: state)

    // The venue sentence names the place THE RECORD names, not a place an atlas resolved. A reader
    // who typed "Barpali" and reads "Workshop venue at Odisha" concludes the report lost their
    // answer.
    val statedVenue = listOf("venue", "village", "district")
        .map(::setupText)
        .filter { it.isNotEmpty() }
        .joinToString(", ")

    val roster = draft?.stages?.get(MAP_ROSTER_STAGE)?.rowsFor(MAP_ROSTER_ENTITY).orEmpty().size
    val sentences = ArrayList<String>()
    if (points.isNotEmpty() && statedVenue.isNotEmpty()) sentences += "Workshop venue: $statedVenue."
    if (roster > 0) {
        sentences += "The home places of the $roster participating artisan(s) are not plotted on " +
            "this copy, which was generated on a handset; the office's copy of this report maps them."
    }
    if (points.isEmpty()) {
        sentences += "No address in the record could be resolved to a position; the map shows the " +
            "region only."
    }

    if (section.pageBreakBefore) builder.add(PageBreakBlock)
    builder.heading(
        section.heading.ifBlank { "Workshop location and participants' origins" },
        level = 1,
        numbered = plan.template.numberHeadings,
    )
    if (section.intro.isNotBlank()) builder.para(section.intro, style = ParaStyle.LEAD)
    builder.add(
        MapBlock(
            caption = sentences.joinToString(" "),
            points = points,
            highlight = highlight.filter { it.isNotEmpty() }.toSet(),
        )
    )
}
