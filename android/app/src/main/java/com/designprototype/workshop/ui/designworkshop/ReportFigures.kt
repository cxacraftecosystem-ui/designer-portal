package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.plural
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

private const val MIN_CHART_CATEGORIES = 2

private val PRICE_STEPS = doubleArrayOf(
    100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0, 25000.0, 50000.0, 100000.0,
)

private const val MAX_PRICE_BANDS = 6

internal fun priceBands(values: List<Double>): List<Pair<String, Double>> {
    val positive = values.filter { it > 0 }.sorted()
    if (positive.size < 3) return emptyList()
    val top = positive.last()
    if (top <= positive.first()) return emptyList()
    val step = PRICE_STEPS.firstOrNull { top / it <= MAX_PRICE_BANDS } ?: PRICE_STEPS.last()
    val counts = LinkedHashMap<Long, Int>()
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

private fun fixedZero(value: Double): String =
    java.math.BigDecimal(value).setScale(0, java.math.RoundingMode.HALF_EVEN).toPlainString()

internal val REPORT_FIGURES: Map<String, String> = linkedMapOf(
    "OUTPUT_COUNTS" to "WORKSHOP_OUTCOMES",
    "PROTOTYPE_STATUS" to "WORKSHOP_OUTCOMES",
    "COST_BY_HEAD" to "COSTING_MARKET_LINKAGE",
    "PRICE_BANDS" to "COSTING_MARKET_LINKAGE",
    "ADOPTION" to "POST_WORKSHOP_FOLLOWUP",
)

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

    private fun labelOf(stageKey: String, entityKey: String, fieldKey: String, fallback: String) =
        fieldOf(stageKey, entityKey, fieldKey)?.label?.takeIf { it.isNotBlank() } ?: fallback

    private fun outputCount(stageKey: String, entityKey: String, overrideKey: String = ""): Int {
        val counted = rows(stageKey, entityKey).size
        if (overrideKey.isEmpty()) return counted
        val stated = asFiniteNumber(singleton("WORKSHOP_OUTCOMES")[overrideKey]) ?: return counted
        if (stated < 0) return counted
        return stated.toInt()
    }

    private fun countOverrideReason(): String {
        val outcomes = singleton("WORKSHOP_OUTCOMES")
        val overridden = listOf("designsCountOverride", "prototypesCountOverride")
            .any { asFiniteNumber(outcomes[it]) != null }
        if (!overridden) return ""
        return DwValues.text(outcomes["countOverrideReason"]).trim()
    }

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

    private fun chartPrototypeStatus(): ChartBlock? {
        val spec = fieldOf("PROTOTYPE_VALIDATION", "prototypeValidation", "decision") ?: return null
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
            caption = "${tally.values.sum()} ${if (tally.values.sum() == 1) "prototype" else "prototypes"} reviewed.",
        )
    }

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

    private fun chartPriceBands(): ChartBlock? {
        val prices = ArrayList<Double>()
        for (row in rows("COSTING_MARKET_LINKAGE", "costSheet")) {
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
            caption = "${prices.size} ${if (prices.size == 1) "product" else "products"} with a recorded price, in rupees.",
        )
    }

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
        if (block == null || block.total <= 0) return null
        drawn.add(figureId)
        return block
    }

    internal fun chartsFor(stageKey: String): List<ChartBlock> =
        REPORT_FIGURES.filterValues { it == stageKey }.keys.mapNotNull { figure(it) }
}

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

private const val MAP_VENUE_STAGE = "WORKSHOP_SETUP"

private const val MAP_ROSTER_STAGE = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"
private const val MAP_ROSTER_ENTITY = "participant"

private val MAP_ROSTER_PLACE_KEYS = listOf("village", "district")

private const val MAP_ROSTER_STATE_KEY = "state"

private const val MAP_ROSTER_PIN_KEY = "subjectLocation"

private fun geoFix(value: JsonElement?): Pair<Double, Double>? {
    val (lat, lon) = DwValues.geo(value) ?: return null
    if (!lat.isFinite() || !lon.isFinite()) return null
    if (Math.abs(lat) < 0.0001 && Math.abs(lon) < 0.0001) return null
    return lat to lon
}

private class RosterPins(
    val points: List<MapPoint> = emptyList(),
    val states: Set<String> = emptySet(),
    val placed: Int = 0,
    val total: Int = 0,
)

private fun rosterPins(draft: WorkshopDraft?, stateHint: String): RosterPins {
    val rows = draft?.stages?.get(MAP_ROSTER_STAGE)?.rowsFor(MAP_ROSTER_ENTITY).orEmpty()
    if (rows.isEmpty()) return RosterPins()

    fun round5(value: Double): Double =
        java.math.BigDecimal(value).setScale(5, java.math.RoundingMode.HALF_EVEN).toDouble()

    val folded = LinkedHashMap<Pair<Double, Double>, Pair<String, Int>>()
    val states = LinkedHashSet<String>()
    var placed = 0
    rows.forEach { row ->
        val values = row.values
        val fix = geoFix(values[MAP_ROSTER_PIN_KEY]) ?: return@forEach
        val text = MAP_ROSTER_PLACE_KEYS
            .map { DwValues.text(values[it]).trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        val state = DwValues.text(values[MAP_ROSTER_STATE_KEY]).trim().ifEmpty { stateHint }
        if (state.isNotEmpty()) states += (canonicalState(state) ?: state)
        val key = round5(fix.first) to round5(fix.second)
        val existing = folded[key]
        folded[key] = (existing?.first ?: text.ifEmpty { "Artisan's home" }) to ((existing?.second ?: 0) + 1)
        placed++
    }
    return RosterPins(
        points = folded.map { (key, value) ->
            MapPoint(
                label = value.first,
                lat = key.first,
                lon = key.second,
                kind = MapPointKind.ARTISAN,
                count = value.second,
            )
        },
        states = states,
        placed = placed,
        total = rows.size,
    )
}

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
    val venuePoints = if (fix == null) {
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
    val artisans = rosterPins(draft, state)
    val points = venuePoints + artisans.points

    val highlight = artisans.states + if (state.isEmpty()) emptySet() else setOf(canonicalState(state) ?: state)

    val statedVenue = listOf("venue", "village", "district")
        .map(::setupText)
        .filter { it.isNotEmpty() }
        .joinToString(", ")

    val sentences = ArrayList<String>()
    if (venuePoints.isNotEmpty() && statedVenue.isNotEmpty()) sentences += "Workshop venue: $statedVenue."
    if (artisans.placed > 0) {
        sentences += "Home places of ${artisans.placed} of ${artisans.total} participating " +
            "artisans, at ${artisans.points.size} ${if (artisans.points.size == 1) "location" else "locations"}."
    }
    if (artisans.total > artisans.placed) {
        sentences += "${artisans.total - artisans.placed} " +
            "${if (artisans.total - artisans.placed == 1) "participant has" else "participants have"} no surveyed position " +
            "and ${if (artisans.total - artisans.placed == 1) "is" else "are"} not shown on this map."
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
