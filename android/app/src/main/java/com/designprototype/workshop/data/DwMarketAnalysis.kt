package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

typealias DwDataRow = Map<String, JsonElement>

const val DW_MIN_SAMPLE_FOR_QUANTILES = 5

const val DW_MIN_SAMPLE_FOR_VERDICT = 8

const val DW_NARROW_COVERAGE = 0.55

object DwPy {

    fun strip(text: String): String =
        text.trim { it.isWhitespace() || Character.isSpaceChar(it) || it == '\u0085' }

    val doubleOrder: Comparator<Double> =
        Comparator { a, b -> if (a < b) -1 else if (a > b) 1 else 0 }

    val stringOrder: Comparator<String> = Comparator { a, b -> compareStrings(a, b) }

    private val PY_FLOAT = Regex(
        "^[+-]?(?:\\p{Nd}+(?:_\\p{Nd}+)*(?:\\.(?:\\p{Nd}+(?:_\\p{Nd}+)*)?)?" +
            "|\\.\\p{Nd}+(?:_\\p{Nd}+)*)(?:[eE][+-]?\\p{Nd}+(?:_\\p{Nd}+)*)?$"
    )

    private fun asciiDigits(text: String): String {
        if (text.all { it.code < 128 }) return text
        val out = StringBuilder(text.length)
        for (ch in text) {
            val digit = Character.digit(ch, 10)
            out.append(if (digit in 0..9 && Character.isDigit(ch)) '0' + digit else ch)
        }
        return out.toString()
    }

    fun asNumber(value: JsonElement?): Double? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (!primitive.isString) {
            val content = primitive.content
            if (content == "true" || content == "false") return null
            return content.toDoubleOrNull()?.takeIf { it.isFinite() }
        }
        val text = strip(primitive.content).replace(",", "")
        if (text.isEmpty()) return null
        if (!PY_FLOAT.matches(text)) return null
        return asciiDigits(text.replace("_", "")).toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    fun isFalsy(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> true
        is JsonArray -> value.isEmpty()
        is JsonObject -> value.isEmpty()
        is JsonPrimitive ->
            if (value.isString) value.content.isEmpty()
            else value.content == "false" || value.content.toDoubleOrNull() == 0.0
    }

    fun str(value: JsonElement?): String = when (value) {
        null, JsonNull -> "None"
        is JsonPrimitive ->
            if (value.isString) value.content
            else when (value.content) {
                "true" -> "True"
                "false" -> "False"
                else -> value.content
            }
        else -> value.toString()
    }

    fun text(value: JsonElement?): String = if (isFalsy(value)) "" else str(value)

    fun compareStrings(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return if (x < y) -1 else 1
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        return when {
            i < a.length -> 1
            j < b.length -> -1
            else -> 0
        }
    }

    fun sum(values: Iterable<Double>): Double {
        var total = 0.0
        var compensation = 0.0
        for (value in values) {
            val stepped = total + value
            compensation += if (abs(total) >= abs(value)) {
                (total - stepped) + value
            } else {
                (value - stepped) + total
            }
            total = stepped
        }
        return if (total.isFinite()) total + compensation else total
    }

    fun round(value: Double, digits: Int): Double {
        if (!value.isFinite()) return value
        val rounded = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
        return if (rounded == 0.0 && java.lang.Double.doubleToRawLongBits(value) < 0) -0.0 else rounded
    }

    fun format(value: Double, decimals: Int, grouped: Boolean): String {
        if (value.isNaN()) return "nan"
        if (value.isInfinite()) return if (value < 0) "-inf" else "inf"
        val negative = java.lang.Double.doubleToRawLongBits(value) < 0
        val body = BigDecimal(abs(value)).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString()
        val dot = body.indexOf('.')
        val whole = if (dot < 0) body else body.substring(0, dot)
        val fraction = if (dot < 0) "" else body.substring(dot)
        return (if (negative) "-" else "") + (if (grouped) group(whole) else whole) + fraction
    }

    private fun group(digits: String): String {
        val out = StringBuilder(digits.length + digits.length / 3)
        for (index in digits.indices) {
            if (index > 0 && (digits.length - index) % 3 == 0) out.append(',')
            out.append(digits[index])
        }
        return out.toString()
    }
}

data class DwPriceObservation(
    val amount: Double,
    val category: String = "",
    val source: String = "RESPONDENT",
    val group: String = "",
    val label: String = "",
)

data class DwDistribution(
    val count: Int,
    val minimum: Double,
    val maximum: Double,
    val mean: Double,
    val p25: Double,
    val median: Double,
    val p75: Double,
    val quantilesReported: Boolean,
) {
    val spread: Double get() = maximum - minimum
}

data class DwBandVerdict(
    val category: String,
    val declaredLow: Double?,
    val declaredHigh: Double?,
    val evidence: DwDistribution?,
    val inside: Int,
    val below: Int,
    val above: Int,
    val coverage: Double,
    val verdict: String,
    val message: String,
)

data class DwCompetitorPosition(
    val name: String,
    val seller: String,
    val category: String,
    val price: Double,
    val percentile: Double?,
    val versusMedian: Double?,
    val note: String,
)

data class DwSwotSupport(
    val kind: String,
    val point: String,
    val supportedBy: List<String>,
    val overlap: Int,
    val hasOwnEvidence: Boolean,
) {
    val supported: Boolean get() = hasOwnEvidence || supportedBy.isNotEmpty()
}

data class DwTrendCluster(
    val members: List<String>,
    val mentions: Int,
    val coOccurrences: Int,
)

data class DwMarketFindings(
    val observations: Int = 0,
    val respondentPrices: DwDistribution? = null,
    val competitorPrices: DwDistribution? = null,
    val byCategory: Map<String, DwDistribution> = emptyMap(),
    val bands: List<DwBandVerdict> = emptyList(),
    val competitors: List<DwCompetitorPosition> = emptyList(),
    val swot: List<DwSwotSupport> = emptyList(),
    val clusters: List<DwTrendCluster> = emptyList(),
    val groupCounts: Map<String, Int> = emptyMap(),
    val cautions: List<String> = emptyList(),
) {
    val unsupportedSwot: List<DwSwotSupport> get() = swot.filter { !it.supported }

    val isEmpty: Boolean
        get() = observations == 0 && bands.isEmpty() && swot.isEmpty() &&
            clusters.isEmpty() && cautions.isEmpty()
}

object DwMarketAnalysis {

    private val STOPWORDS = setOf(
        "and", "are", "but", "for", "from", "has", "have", "its", "not", "our", "that", "the",
        "their", "there", "they", "this", "was", "were", "will", "with", "you", "more", "most",
        "some", "such", "than", "then", "these", "those", "can", "could", "would", "should",
        "may", "might", "also", "very", "much", "many",
    )

    private const val MIN_TOKEN = 3

    private const val SUPPORT_OVERLAP = 2

    private val WORD_RUN = Regex("[\\p{L}\\p{M}]+")

    private fun tokens(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (match in WORD_RUN.findAll(text)) {
            val word = match.value.lowercase()
            if (word.codePointCount(0, word.length) >= MIN_TOKEN && word !in STOPWORDS) out.add(word)
        }
        return out
    }

    fun quantile(sortedValues: List<Double>, q: Double): Double {
        require(sortedValues.isNotEmpty()) { "quantile of an empty sample" }
        if (sortedValues.size == 1) return sortedValues[0]
        val position = q * (sortedValues.size - 1)
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        if (lower == upper) return sortedValues[lower]
        return sortedValues[lower] + (sortedValues[upper] - sortedValues[lower]) * (position - lower)
    }

    fun describe(values: List<Double>): DwDistribution? {
        if (values.isEmpty()) return null
        val ordered = values.sortedWith(DwPy.doubleOrder)
        val count = ordered.size
        val median = quantile(ordered, 0.5)
        val enough = count >= DW_MIN_SAMPLE_FOR_QUANTILES
        val total = DwPy.sum(ordered)
        return DwDistribution(
            count = count,
            minimum = ordered[0],
            maximum = ordered[count - 1],
            mean = total / count,
            p25 = if (enough) quantile(ordered, 0.25) else median,
            median = median,
            p75 = if (enough) quantile(ordered, 0.75) else median,
            quantilesReported = enough,
        )
    }

    private fun rupees0(value: Double): String = "₹" + DwPy.format(value, 0, grouped = true)

    private fun bandMessage(
        verdict: String,
        category: String,
        low: Double,
        high: Double,
        dist: DwDistribution,
        inside: Int,
        below: Int,
        above: Int,
    ): String {
        val label = category.ifEmpty { "this category" }
        val range = "₹${DwPy.format(low, 0, true)}–${DwPy.format(high, 0, true)}"
        val median = rupees0(dist.median)
        return when (verdict) {
            "SOUND" ->
                "The band $range covers $inside of ${dist.count} price observations for $label; " +
                    "the median observed is $median."
            "LOW" ->
                "The band $range sits below the evidence for $label: $above of ${dist.count} " +
                    "observations are above it, and the median observed is $median. The prototypes " +
                    "may be priced under what buyers said."
            "HIGH" ->
                "The band $range sits above the evidence for $label: $below of ${dist.count} " +
                    "observations are below it, and the median observed is $median."
            else ->
                "The band $range covers only $inside of ${dist.count} observations for $label " +
                    "($below below, $above above; median $median). Widening it, or splitting the " +
                    "category, would match what was recorded."
        }
    }

    fun judgeBand(
        category: String,
        low: JsonElement?,
        high: JsonElement?,
        observations: List<DwPriceObservation>,
    ): DwBandVerdict {
        var declaredLow = DwPy.asNumber(low)
        var declaredHigh = DwPy.asNumber(high)
        if (declaredLow != null && declaredHigh != null && declaredLow > declaredHigh) {
            val swap = declaredLow
            declaredLow = declaredHigh
            declaredHigh = swap
        }

        val values = observations.map { it.amount }
        val dist = describe(values)
        val named = category.ifEmpty { "this category" }

        if (dist == null) {
            return DwBandVerdict(
                category, declaredLow, declaredHigh, null, 0, 0, 0, 0.0, "NO_EVIDENCE",
                "No price was recorded in stage 8 for $named, so this band cannot be checked " +
                    "against the survey."
            )
        }

        if (declaredLow == null || declaredHigh == null) {
            return DwBandVerdict(
                category, declaredLow, declaredHigh, dist, 0, 0, 0, 0.0, "NO_EVIDENCE",
                "${dist.count} price ${if (dist.count == 1) "observation exists" else "observations exist"} for $named, but the band itself is " +
                    "incomplete."
            )
        }

        var inside = 0
        var below = 0
        var above = 0
        for (value in values) {
            if (value >= declaredLow && value <= declaredHigh) inside++
            if (value < declaredLow) below++
            if (value > declaredHigh) above++
        }
        val coverage = inside.toDouble() / dist.count

        if (dist.count < DW_MIN_SAMPLE_FOR_VERDICT) {
            return DwBandVerdict(
                category, declaredLow, declaredHigh, dist, inside, below, above, coverage,
                "UNVERIFIABLE",
                "Only ${dist.count} price ${if (dist.count == 1) "observation was" else "observations were"} recorded for $named — too few to " +
                    "judge the band against. $inside of them fall inside it."
            )
        }

        val verdict = when {
            coverage >= DW_NARROW_COVERAGE -> "SOUND"
            above > below * 2 -> "LOW"
            below > above * 2 -> "HIGH"
            else -> "NARROW"
        }

        return DwBandVerdict(
            category, declaredLow, declaredHigh, dist, inside, below, above, coverage, verdict,
            bandMessage(verdict, category, declaredLow, declaredHigh, dist, inside, below, above)
        )
    }

    fun positionCompetitors(
        competitors: List<DwDataRow>,
        observations: List<DwPriceObservation>,
    ): List<DwCompetitorPosition> {
        val byCategory = LinkedHashMap<String, MutableList<Double>>()
        val pooled = ArrayList<Double>()
        for (observation in observations) {
            if (observation.source != "RESPONDENT") continue
            pooled.add(observation.amount)
            if (observation.category.isNotEmpty()) {
                byCategory.getOrPut(observation.category) { ArrayList() }.add(observation.amount)
            }
        }
        byCategory.values.forEach { it.sortWith(DwPy.doubleOrder) }
        pooled.sortWith(DwPy.doubleOrder)

        val out = ArrayList<DwCompetitorPosition>()
        for (row in competitors) {
            val price = DwPy.asNumber(row["price"])
            if (price == null) continue
            val category = DwPy.text(row["category"])
            val name = DwPy.strip(DwPy.text(row["name"])).ifEmpty { "Unnamed product" }
            val seller = DwPy.strip(DwPy.text(row["seller"]))

            var sample: List<Double> = byCategory[category] ?: emptyList()
            val matched = sample.size >= DW_MIN_SAMPLE_FOR_QUANTILES
            if (!matched) sample = pooled
            if (sample.size < DW_MIN_SAMPLE_FOR_QUANTILES) {
                out.add(
                    DwCompetitorPosition(
                        name, seller, category, price, null, null,
                        "${rupees0(price)}. Too few buyer price expectations were recorded in the " +
                            "survey to place this against them."
                    )
                )
                continue
            }

            var atOrBelow = 0
            for (value in sample) if (value <= price) atOrBelow++
            val percentile = atOrBelow.toDouble() / sample.size
            val median = quantile(sample, 0.5)
            val whose = if (matched) "the ${sample.size} buyers asked about $category"
            else "all ${sample.size} buyers asked"
            val comparison = if (percentile >= 0.5) "above" else "below"
            val share = if (percentile >= 0.5) percentile else 1 - percentile
            out.add(
                DwCompetitorPosition(
                    name, seller, category, price, percentile, price - median,
                    "${rupees0(price)} is $comparison what ${DwPy.format(share * 100, 0, false)}% " +
                        "of $whose said they would pay (their median: ${rupees0(median)})."
                )
            )
        }
        return out
    }

    fun linkSwotEvidence(swot: List<DwDataRow>, responses: List<DwDataRow>): List<DwSwotSupport> {
        val prepared = ArrayList<Pair<String, Set<String>>>(responses.size)
        for (row in responses) {
            val builder = StringBuilder(
                listOf("response", "productsDiscussed", "place")
                    .joinToString(" ") { DwPy.text(row[it]) }
            )
            (row["productsDiscussed"] as? JsonArray)?.let { tags ->
                builder.append(' ').append(tags.joinToString(" ") { DwPy.str(it) })
            }
            val name = DwPy.strip(DwPy.text(row["respondentName"])).ifEmpty { "A respondent" }
            prepared.add(name to tokens(builder.toString()))
        }

        val out = ArrayList<DwSwotSupport>()
        for (row in swot) {
            val point = DwPy.strip(DwPy.text(row["point"]))
            if (point.isEmpty()) continue
            val kind = DwPy.text(row["kind"])
            val own = DwPy.strip(DwPy.text(row["evidence"])).isNotEmpty()
            val wanted = tokens(point)
            val matches = ArrayList<Pair<Int, String>>()
            for ((name, candidate) in prepared) {
                var shared = 0
                for (word in wanted) if (word in candidate) shared++
                if (shared >= SUPPORT_OVERLAP) matches.add(shared to name)
            }
            matches.sortWith(
                compareByDescending<Pair<Int, String>> { it.first }
                    .thenComparator { a, b -> DwPy.compareStrings(a.second, b.second) }
            )
            out.add(
                DwSwotSupport(
                    kind = kind,
                    point = point,
                    supportedBy = matches.take(5).map { it.second },
                    overlap = matches.firstOrNull()?.first ?: 0,
                    hasOwnEvidence = own,
                )
            )
        }
        return out
    }

    fun clusterProducts(
        responses: List<DwDataRow>,
        minimumMentions: Int = 2,
        limit: Int = 8,
    ): List<DwTrendCluster> {
        val tagLists = ArrayList<List<String>>()
        for (row in responses) {
            val raw = row["productsDiscussed"]
            val tags: List<JsonElement?> = if (raw is JsonArray) raw
            else DwPy.text(raw).split(",").map { JsonPrimitive(it) }
            val unique = LinkedHashSet<String>()
            for (tag in tags) {
                val text = DwPy.strip(DwPy.str(tag))
                if (text.isNotEmpty()) unique.add(text.lowercase())
            }
            val cleaned = unique.sortedWith(DwPy.stringOrder)
            if (cleaned.isNotEmpty()) tagLists.add(cleaned)
        }

        val mentions = LinkedHashMap<String, Int>()
        val pairs = LinkedHashMap<String, Triple<String, String, Int>>()
        for (tags in tagLists) {
            for (tag in tags) mentions[tag] = (mentions[tag] ?: 0) + 1
            for (i in tags.indices) {
                for (j in i + 1 until tags.size) {
                    val key = tags[i] + '\u0000' + tags[j]
                    val existing = pairs[key]
                    pairs[key] = if (existing == null) Triple(tags[i], tags[j], 1)
                    else existing.copy(third = existing.third + 1)
                }
            }
        }

        val parent = LinkedHashMap<String, String>()
        for (tag in mentions.keys) parent[tag] = tag
        fun find(start: String): String {
            var node = start
            while (parent[node] != node) {
                val grandparent = parent[parent[node]]!!
                parent[node] = grandparent
                node = grandparent
            }
            return node
        }

        val joined = LinkedHashMap<String, Int>()
        for ((a, b, seen) in pairs.values) {
            if (seen < minimumMentions) continue
            val rootA = find(a)
            val rootB = find(b)
            if (rootA != rootB) parent[rootB] = rootA
            val root = find(a)
            joined[root] = (joined[root] ?: 0) + seen
        }

        val groups = LinkedHashMap<String, MutableList<String>>()
        for (tag in mentions.keys) groups.getOrPut(find(tag)) { ArrayList() }.add(tag)

        val clusters = ArrayList<DwTrendCluster>()
        for ((root, members) in groups) {
            if (!(members.size > 1 || (mentions[members[0]] ?: 0) >= minimumMentions)) continue
            var total = 0
            for (member in members) total += mentions[member] ?: 0
            clusters.add(
                DwTrendCluster(
                    members = members.sortedWith(DwPy.stringOrder),
                    mentions = total,
                    coOccurrences = joined[root] ?: 0,
                )
            )
        }
        val ordered = clusters.sortedWith(
            compareByDescending<DwTrendCluster> { it.mentions }
                .thenByDescending { it.members.size }
                .thenComparator { a, b -> compareMembers(a.members, b.members) }
        )
        return ordered.take(limit)
    }

    private fun compareMembers(a: List<String>, b: List<String>): Int {
        val shared = minOf(a.size, b.size)
        for (index in 0 until shared) {
            val order = DwPy.compareStrings(a[index], b[index])
            if (order != 0) return order
        }
        return a.size - b.size
    }

    fun collectObservations(
        responses: List<DwDataRow>,
        competitors: List<DwDataRow>,
        defaultCategory: String = "",
    ): List<DwPriceObservation> {
        val out = ArrayList<DwPriceObservation>()
        for (row in responses) {
            val amount = DwPy.asNumber(row["priceExpectation"]) ?: continue
            out.add(
                DwPriceObservation(
                    amount = amount,
                    category = defaultCategory,
                    source = "RESPONDENT",
                    group = DwPy.text(row["respondentGroup"]),
                    label = DwPy.strip(DwPy.text(row["respondentName"])).ifEmpty { "A respondent" },
                )
            )
        }
        for (row in competitors) {
            val amount = DwPy.asNumber(row["price"]) ?: continue
            out.add(
                DwPriceObservation(
                    amount = amount,
                    category = DwPy.text(row["category"]),
                    source = "COMPETITOR",
                    label = DwPy.strip(DwPy.text(row["name"])).ifEmpty { "A competitor product" },
                )
            )
        }
        return out
    }

    fun analyse(
        responses: List<DwDataRow>,
        competitors: List<DwDataRow>,
        bands: List<DwDataRow>,
        swot: List<DwDataRow>,
    ): DwMarketFindings {
        val observations = collectObservations(responses, competitors)
        val respondentValues = ArrayList<Double>()
        val competitorValues = ArrayList<Double>()
        for (observation in observations) {
            if (observation.source == "RESPONDENT") respondentValues.add(observation.amount)
            else competitorValues.add(observation.amount)
        }

        val grouped = LinkedHashMap<String, MutableList<Double>>()
        for (observation in observations) {
            grouped.getOrPut(observation.category) { ArrayList() }.add(observation.amount)
        }
        val byCategory = LinkedHashMap<String, DwDistribution>()
        for ((category, values) in grouped) {
            describe(values)?.let { byCategory[category] = it }
        }

        val bandVerdicts = bands.map { row ->
            val category = DwPy.text(row["category"])
            judgeBand(
                category,
                row["lowPrice"],
                row["highPrice"],
                observations.filter { it.category == category || it.category.isEmpty() },
            )
        }

        val groupCounts = LinkedHashMap<String, Int>()
        for (observation in observations) {
            if (observation.source != "RESPONDENT" || observation.group.isEmpty()) continue
            groupCounts[observation.group] = (groupCounts[observation.group] ?: 0) + 1
        }

        val cautions = ArrayList<String>()
        if (respondentValues.isNotEmpty() && respondentValues.size < DW_MIN_SAMPLE_FOR_VERDICT) {
            cautions.add(
                "Only ${respondentValues.size} respondent price " +
                    "${if (respondentValues.size == 1) "expectation was" else "expectations were"} recorded. " +
                    "The figures below describe what was collected; they are not a market estimate."
            )
        }
        if (groupCounts.isNotEmpty()) {
            var dominant = ""
            var dominantCount = -1
            var total = 0
            for ((group, count) in groupCounts) {
                total += count
                if (count > dominantCount) {
                    dominant = group
                    dominantCount = count
                }
            }
            if (total >= DW_MIN_SAMPLE_FOR_QUANTILES && dominantCount.toDouble() / total > 0.8) {
                cautions.add(
                    "$dominantCount of $total priced responses come from one respondent group " +
                        "($dominant). The price figures describe that group rather than the market."
                )
            }
        }
        if (competitorValues.isNotEmpty() && respondentValues.isEmpty()) {
            cautions.add(
                "Competitor prices were recorded but no buyer price expectations were. Shelf prices " +
                    "say what is charged, not what the buyers surveyed said they would pay."
            )
        }

        return DwMarketFindings(
            observations = observations.size,
            respondentPrices = describe(respondentValues),
            competitorPrices = describe(competitorValues),
            byCategory = byCategory,
            bands = bandVerdicts,
            competitors = positionCompetitors(competitors, observations),
            swot = linkSwotEvidence(swot, responses),
            clusters = clusterProducts(responses),
            groupCounts = groupCounts,
            cautions = cautions,
        )
    }

    private fun distributionPayload(dist: DwDistribution?): JsonElement {
        if (dist == null) return JsonNull
        return buildJsonObject {
            put("count", dist.count)
            put("minimum", DwPy.round(dist.minimum, 2))
            put("maximum", DwPy.round(dist.maximum, 2))
            put("mean", DwPy.round(dist.mean, 2))
            put("p25", DwPy.round(dist.p25, 2))
            put("median", DwPy.round(dist.median, 2))
            put("p75", DwPy.round(dist.p75, 2))
            put("quantilesReported", dist.quantilesReported)
        }
    }

    fun fromPayload(json: JsonObject): DwMarketFindings = DwMarketFindings(
        observations = json.int("observations"),
        respondentPrices = distributionOf(json["respondentPrices"]),
        competitorPrices = distributionOf(json["competitorPrices"]),
        byCategory = (json["byCategory"] as? JsonObject).orEmpty()
            .mapNotNull { (key, value) -> distributionOf(value)?.let { key to it } }.toMap(),
        bands = json.objects("bands").map { band ->
            DwBandVerdict(
                category = band.text("category"),
                declaredLow = band.number("declaredLow"),
                declaredHigh = band.number("declaredHigh"),
                evidence = distributionOf(band["evidence"]),
                inside = band.int("inside"),
                below = band.int("below"),
                above = band.int("above"),
                coverage = band.number("coverage") ?: 0.0,
                verdict = band.text("verdict"),
                message = band.text("message"),
            )
        },
        competitors = json.objects("competitors").map { item ->
            DwCompetitorPosition(
                name = item.text("name"),
                seller = item.text("seller"),
                category = item.text("category"),
                price = item.number("price") ?: 0.0,
                percentile = item.number("percentile"),
                versusMedian = item.number("versusMedian"),
                note = item.text("note"),
            )
        },
        swot = json.objects("swot").map { point ->
            DwSwotSupport(
                kind = point.text("kind"),
                point = point.text("point"),
                supportedBy = point.texts("supportedBy"),
                overlap = point.int("overlap"),
                hasOwnEvidence = (point["hasOwnEvidence"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        },
        clusters = json.objects("clusters").map { cluster ->
            DwTrendCluster(
                members = cluster.texts("members"),
                mentions = cluster.int("mentions"),
                coOccurrences = cluster.int("coOccurrences"),
            )
        },
        groupCounts = (json["groupCounts"] as? JsonObject).orEmpty()
            .mapNotNull { (key, value) -> (value as? JsonPrimitive)?.intOrNull?.let { key to it } }.toMap(),
        cautions = json.texts("cautions"),
    )

    private fun distributionOf(element: JsonElement?): DwDistribution? {
        val obj = element as? JsonObject ?: return null
        return DwDistribution(
            count = obj.int("count"),
            minimum = obj.number("minimum") ?: 0.0,
            maximum = obj.number("maximum") ?: 0.0,
            mean = obj.number("mean") ?: 0.0,
            p25 = obj.number("p25") ?: 0.0,
            median = obj.number("median") ?: 0.0,
            p75 = obj.number("p75") ?: 0.0,
            quantilesReported = (obj["quantilesReported"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: 0

    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()

    private fun JsonObject.texts(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    fun payload(findings: DwMarketFindings): JsonObject = buildJsonObject {
        put("observations", findings.observations)
        put("cautions", buildJsonArray { findings.cautions.forEach { add(JsonPrimitive(it)) } })
        put("unsupportedSwot", buildJsonArray {
            findings.unsupportedSwot.forEach { point ->
                add(buildJsonObject {
                    put("kind", point.kind)
                    put("point", point.point)
                })
            }
        })
        put("respondentPrices", distributionPayload(findings.respondentPrices))
        put("competitorPrices", distributionPayload(findings.competitorPrices))
        put("byCategory", buildJsonObject {
            findings.byCategory.keys.sortedWith(DwPy.stringOrder).forEach { category ->
                put(category, distributionPayload(findings.byCategory[category]))
            }
        })
        put("groupCounts", buildJsonObject {
            findings.groupCounts.keys.sortedWith(DwPy.stringOrder).forEach { group ->
                put(group, findings.groupCounts.getValue(group))
            }
        })
        put("bands", buildJsonArray {
            findings.bands.forEach { band ->
                add(buildJsonObject {
                    put("category", band.category)
                    put("declaredLow", band.declaredLow?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("declaredHigh", band.declaredHigh?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("evidence", distributionPayload(band.evidence))
                    put("inside", band.inside)
                    put("below", band.below)
                    put("above", band.above)
                    put("coverage", DwPy.round(band.coverage, 4))
                    put("verdict", band.verdict)
                    put("message", band.message)
                })
            }
        })
        put("competitors", buildJsonArray {
            findings.competitors.forEach { competitor ->
                add(buildJsonObject {
                    put("name", competitor.name)
                    put("seller", competitor.seller)
                    put("category", competitor.category)
                    put("price", competitor.price)
                    put(
                        "percentile",
                        competitor.percentile?.let { JsonPrimitive(DwPy.round(it, 4)) } ?: JsonNull
                    )
                    put(
                        "versusMedian",
                        competitor.versusMedian?.let { JsonPrimitive(DwPy.round(it, 2)) } ?: JsonNull
                    )
                    put("note", competitor.note)
                })
            }
        })
        put("swot", buildJsonArray {
            findings.swot.forEach { point ->
                add(buildJsonObject {
                    put("kind", point.kind)
                    put("point", point.point)
                    put("supported", point.supported)
                    put("hasOwnEvidence", point.hasOwnEvidence)
                    put("supportedBy", buildJsonArray {
                        point.supportedBy.forEach { add(JsonPrimitive(it)) }
                    })
                    put("overlap", point.overlap)
                })
            }
        })
        put("clusters", buildJsonArray {
            findings.clusters.forEach { cluster ->
                add(buildJsonObject {
                    put("members", buildJsonArray {
                        cluster.members.forEach { add(JsonPrimitive(it)) }
                    })
                    put("mentions", cluster.mentions)
                    put("coOccurrences", cluster.coOccurrences)
                })
            }
        })
    }
}
