package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Stage 9's computed findings, on the handset — the port of `backend/app/services/market_analysis.py`.
 *
 * WHY A PORT AND NOT A FETCH. The Python module is pure on purpose: no database, no network, no
 * model, just rows in and findings out. That is what lets the same analysis run on the server, in the
 * browser and here — and HERE is the client that needs it most. A designer types "₹400–800, high
 * demand" into stage 9 standing in the village where they collected twenty-three price expectations
 * into stage 8 that morning, on a handset that will not see a tower for two more days. Every row this
 * needs is already in `filesDir`. An analysis that waits for a sync is an analysis that arrives after
 * the workshop has ended, about a band that is already in a document at the ministry.
 *
 * IT IS A PORT, NOT A SECOND OPINION. When this file and `market_analysis.py` disagree, the Python is
 * right and this is broken — and `MarketAnalysisParityTest` proves the two equal case by case rather
 * than asserting it. Four things make disagreement easy on the JVM specifically, and each is spelled
 * out below rather than delegated to a library:
 *
 *  - **Rounding and money formatting.** Python's `round()` and `format(x, ',.0f')` round half to
 *    EVEN. Java's `String.format`/`Formatter` round half AWAY from zero. They differ on every exact
 *    half — a median of ₹624.50 prints ₹624 on the server and ₹625 here, and a coverage of 0.62500
 *    lands on 0.625 vs 0.63 — so [DwPy.round] and [DwPy.format] go through [BigDecimal] with
 *    [RoundingMode.HALF_EVEN] and every figure in the payload goes through them.
 *  - **The number grammar.** `String.toDoubleOrNull` is `Double.parseDouble`, which accepts `"1.5f"`,
 *    `"0x1Ap3"` and `"NaN"`, and refuses Python's `"1_000"`. `float()` accepts none of the first
 *    three and does take the fourth. A price column holding a scrap of spreadsheet would become a
 *    price here and nothing there, which moves a quantile.
 *  - **The tokeniser.** `[\p{L}\p{M}]+` — and the `\p{M}` is load-bearing. Without it the virama in
 *    ରଙ୍ଗ and the matra in ଦାମ are word boundaries, every Odia word shatters into one- and
 *    two-character fragments that the length floor then discards, and "unsupported by the survey"
 *    becomes the automatic verdict for exactly the fieldwork this application exists to collect.
 *  - **String ordering.** Kotlin's `compareTo` compares UTF-16 code UNITS; Python compares CODE
 *    POINTS. They disagree above U+FFFF, which is where an astral tag or a respondent name sorts
 *    into a different cluster on one client than the other. [DwPy.compareStrings] is Python's rule.
 *
 * IT REFUSES TO CONCLUDE FROM TOO LITTLE, identically. The sample floors and the UNVERIFIABLE /
 * NO_EVIDENCE verdicts are not defensive clutter to be simplified away: an analysis that produces a
 * confident verdict from four price expectations is worse than none, because the confidence is what
 * gets carried into a document a ministry reads.
 *
 * IT NEVER WRITES TO STAGE 9. Nothing here returns anything a form should be seeded from. The
 * designer's declared bands, SWOT and demand level stay exactly as typed; this produces findings
 * BESIDE them. They were in the room and the arithmetic was not.
 */

/**
 * One row of a stage entity, exactly as the draft stores it.
 *
 * `Map<String, JsonElement>` rather than a typed row for the same reason [StageDraft] holds one: the
 * registry is data, and a class per entity would put a second opinion about stage 8's columns into a
 * client that is deliberately built not to have one.
 */
typealias DwDataRow = Map<String, JsonElement>

/** Below this many observations, quantiles are not reported at all. */
const val DW_MIN_SAMPLE_FOR_QUANTILES = 5

/**
 * Below this many observations, a declared band gets no verdict. Deliberately higher than the
 * quantile floor: showing a spread from six numbers is honest, telling a designer their band is
 * WRONG from six numbers is not.
 */
const val DW_MIN_SAMPLE_FOR_VERDICT = 8

/**
 * A band is called NARROW when it covers less of the observed market than this. Not a statistical
 * threshold and not presented as one — it is the point at which "your band misses most of the people
 * you asked" becomes worth interrupting a designer to say.
 */
const val DW_NARROW_COVERAGE = 0.55

// --------------------------------------------------------------------------------------
// Python's semantics, where Kotlin's obvious answer is subtly not Python's
// --------------------------------------------------------------------------------------

/**
 * The primitives both ported modules stand on.
 *
 * Nothing in here is a general utility and none of it belongs anywhere else: every function exists
 * because the idiomatic Kotlin spelling of the same idea gives a DIFFERENT ANSWER from the Python
 * these two files are ports of, and the whole claim they make is that they do not.
 */
object DwPy {

    /**
     * Python's `str.strip()`.
     *
     * `String.trim()` is `Character.isWhitespace`, which is deliberately FALSE for the no-break space
     * U+00A0, the figure space U+2007 and the narrow no-break space U+202F — all three of which
     * Python's `str.isspace()` calls whitespace and therefore strips. A price pasted out of a
     * spreadsheet carries U+00A0 as its thousands separator often enough that this is not theoretical:
     * Python reads the number, `trim()` leaves the space attached, the parse fails, and the
     * observation silently leaves the sample on one client only.
     */
    fun strip(text: String): String =
        text.trim { it.isWhitespace() || Character.isSpaceChar(it) || it == '\u0085' }

    /**
     * Python's ordering of floats, which is `<` and nothing else.
     *
     * Kotlin's natural ordering for [Double] is `Double.compareTo`, which declares -0.0 to be LESS
     * than 0.0. Python's `list.sort()` calls `<`, under which the two are equal, so a stable sort
     * leaves them in the order they were collected. The pair is reachable — `as_number("-0.00")` is
     * -0.0 — and a sample holding both reports a minimum of -0.0 here and 0.0 on the server, which
     * `json.dumps` faithfully prints as two different numbers into two copies of one report.
     */
    val doubleOrder: Comparator<Double> =
        Comparator { a, b -> if (a < b) -1 else if (a > b) 1 else 0 }

    /** [compareStrings] as a comparator, for the sorts that must use Python's code-point order. */
    val stringOrder: Comparator<String> = Comparator { a, b -> compareStrings(a, b) }

    /**
     * What Python's `float()` accepts, once whitespace and grouping commas are gone.
     *
     * `\p{Nd}` rather than `\d` because Java's `\d` is ASCII-only while `float()` reads any Unicode
     * decimal digit — Odia ୦-୯ among them, on a handset whose keyboard can produce them.
     * `inf`/`nan` are deliberately absent: they parse in Python and are then rejected as non-finite,
     * so refusing them one step earlier is the same answer.
     */
    private val PY_FLOAT = Regex(
        "^[+-]?(?:\\p{Nd}+(?:_\\p{Nd}+)*(?:\\.(?:\\p{Nd}+(?:_\\p{Nd}+)*)?)?" +
            "|\\.\\p{Nd}+(?:_\\p{Nd}+)*)(?:[eE][+-]?\\p{Nd}+(?:_\\p{Nd}+)*)?$"
    )

    /** Unicode decimal digits folded to ASCII, so `Double.parseDouble` can read what `float()` reads. */
    private fun asciiDigits(text: String): String {
        if (text.all { it.code < 128 }) return text
        val out = StringBuilder(text.length)
        for (ch in text) {
            val digit = Character.digit(ch, 10)
            out.append(if (digit in 0..9 && Character.isDigit(ch)) '0' + digit else ch)
        }
        return out.toString()
    }

    /**
     * `value` as a finite number, or null — the port of `market_analysis.as_number`.
     *
     * MONEY is stored as a fixed-2 STRING and may carry grouping commas, so this is the one place
     * that knows how to read it. Non-finite is rejected rather than propagated: a NaN entering a
     * quantile silently poisons every figure downstream of it, and a report that prints "₹ nan" has
     * already been submitted by the time anybody notices.
     *
     * A JSON array or object is refused outright. Python reaches the same answer one step later —
     * `str(['5'])` is `"['5']"` and `float()` refuses it — but reaching it here closes the place the
     * two languages would otherwise diverge, because a one-element list stringifies to a bare number
     * in some languages and would have become a price on one client only.
     */
    fun asNumber(value: JsonElement?): Double? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (!primitive.isString) {
            // A JSON `true`/`false` decodes to a Python bool, which `as_number` refuses BEFORE the
            // int branch — `isinstance(True, int)` is True in Python, so without that guard a ticked
            // checkbox would enter a price distribution as ₹1.
            val content = primitive.content
            if (content == "true" || content == "false") return null
            return content.toDoubleOrNull()?.takeIf { it.isFinite() }
        }
        val text = strip(primitive.content).replace(",", "")
        if (text.isEmpty()) return null
        if (!PY_FLOAT.matches(text)) return null
        return asciiDigits(text.replace("_", "")).toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** Whether Python would read this stored value as falsy — which is what `value or ""` erases. */
    fun isFalsy(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> true
        is JsonArray -> value.isEmpty()
        is JsonObject -> value.isEmpty()
        is JsonPrimitive ->
            if (value.isString) value.content.isEmpty()
            // 0, 0.0 and -0.0 are all falsy in Python; NaN is not, but JSON has no NaN literal so a
            // decoded primitive can never be one.
            else value.content == "false" || value.content.toDoubleOrNull() == 0.0
    }

    /**
     * Python's `str(value)`, as far as the tokeniser and the tag cleaner can tell the difference.
     *
     * Only letter runs survive tokenising, so an exact `repr` is not needed — what is needed is the
     * same letters in the same order, and a JSON rendering of a list or an object gives that
     * (`{"a":"blue"}` and `{'a': 'blue'}` tokenise identically). The two scalars whose Python spelling
     * differs are written out.
     *
     * THE ONE PLACE THIS IS NOT EXACT is a non-integral NUMBER used as a product TAG: Python prints
     * the shortest round-tripping repr of the decoded float ("4.5") while this returns the literal as
     * written ("4.50"), and a cluster member would differ. Every writer of TAGS on all three clients
     * stores strings, so the case does not arise from real data; matching Java's `Double.toString` to
     * Python's `repr` would be a rounding library, for a row the registry cannot produce.
     */
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

    /** Python's `str(value or "")`. */
    fun text(value: JsonElement?): String = if (isFalsy(value)) "" else str(value)

    /**
     * Python's string ordering, which is by CODE POINT.
     *
     * Kotlin's `compareTo` compares UTF-16 code UNITS, so an astral character sorts before U+E000
     * here and after it there. Survey tags and respondent names are what gets sorted, and a stable,
     * identical order across the three clients is the only reason any of it is sorted at all.
     */
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

    /**
     * Python's `sum()` over floats, which since CPython 3.12 is COMPENSATED and not a running total.
     *
     * `Iterable<Double>.sum()` is a naive left-to-right accumulator. CPython 3.12 gave `sum()` the
     * improved Kahan-Babuška algorithm by Neumaier, and `backend/Dockerfile` pins `PYTHON_VERSION=3.12`
     * — so the compensated answer is the one that actually ships, and the handset's obvious spelling
     * is the one that is wrong. The two agree until a list mixes magnitudes far enough apart that the
     * small ones fall off the bottom of the accumulator, and then they disagree by the whole of the
     * small ones: a differential fuzz of 280 generated cases found `[1560, 1e308, 1000, -1e308]`
     * summing to ₹2,560.00 on the server and ₹0.00 here, and `[624.5, -1e308, 125000, 0.01, 1e308, 7]`
     * to ₹125,631.51 there and ₹7.00 here. Both sentences are the orphan-cost caution in
     * [DwCostIntegrity], so the same stage tells a designer that lines in no subtotal come to a
     * hundred and twenty-five thousand rupees in the office copy and to seven rupees in the copy they
     * are holding in the courtyard.
     *
     * THE COMPENSATION IS FOLDED BACK ONLY WHEN THE RUNNING TOTAL IS FINITE, which is not a refinement
     * but the difference between right and wrong on the overflow cases this is most likely to meet.
     * Two amounts near the top of the range sum to `inf`, and the compensation term for that step is
     * `-inf`; adding it back manufactures a NaN out of a real overflow, so `[1e308, 1e308]` would
     * print "₹nan" here against the server's "₹inf". Verified against CPython on 200,000 random lists
     * drawn from a pool of infinities, NaNs, ±0.0, subnormals and overflow pairs, with no mismatch.
     */
    fun sum(values: Iterable<Double>): Double {
        var total = 0.0
        var compensation = 0.0
        for (value in values) {
            val stepped = total + value
            // The larger magnitude is the one that keeps its bits; the smaller is what the step lost,
            // and that is exactly what the compensation has to carry.
            compensation += if (abs(total) >= abs(value)) {
                (total - stepped) + value
            } else {
                (value - stepped) + total
            }
            total = stepped
        }
        return if (total.isFinite()) total + compensation else total
    }

    /**
     * Python's `round(value, digits)` — correct rounding of the EXACT binary value, ties to EVEN.
     *
     * `String.format("%.2f", …)` is not this. Java's `Formatter` rounds HALF_UP, so a coverage of
     * exactly 0.625 lands on 0.63 here and 0.62 on the server, and a mean of ₹624.50 prints ₹625 here
     * and ₹624 there. Both figures go into the same report. `BigDecimal(double)` is the exact binary
     * value rather than the decimal that was typed, which is what makes HALF_EVEN over it agree with
     * Python instead of merely looking as though it should.
     */
    fun round(value: Double, digits: Int): Double {
        if (!value.isFinite()) return value
        val rounded = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
        // Python keeps the sign of a negative that rounds to zero — `round(-0.4)` is `-0.0` and
        // `json.dumps` prints it as `-0.0`. BigDecimal has no negative zero, so it comes back `+0.0`
        // and the two payloads differ in the one place nothing else would ever look.
        return if (rounded == 0.0 && java.lang.Double.doubleToRawLongBits(value) < 0) -0.0 else rounded
    }

    /**
     * Python's `format(value, ",.Nf")` / `format(value, ".Nf")` — half to EVEN, optionally grouped.
     *
     * Grouping is in THREES, unconditionally, and that is not an oversight in an Indian application:
     * the server prints `100,000` because Python's `,` is locale-independent, and an on-device report
     * that printed `1,00,000` beside the server's copy of the same sentence would read as a different
     * number to anybody skimming the two documents on one desk.
     *
     * A NON-FINITE IS PRINTED, NOT REFUSED, and this arm is a crash guard as much as a parity one.
     * `BigDecimal(double)` throws `NumberFormatException` on an infinity or a NaN, and this function
     * is where every money sentence in both ported modules ends up — so the first cost sheet whose
     * lines summed past the top of a Double took the stage screen down with it, in a courtyard, over
     * data the server merely prints. [round] two functions up already guards the same call, and
     * [DwDerived.formatted] carries the same guard with the same reasoning, which is what makes the
     * omission here an oversight rather than a decision.
     *
     * It is reachable even though [asNumber] refuses a non-finite INPUT: it refuses the input and
     * says nothing about the output. Two finite amounts near the top of the range sum to infinity,
     * an interpolated median subtracts two extremes and overflows, and `-inf / inf` is a NaN.
     * `inf`, `-inf` and `nan` are Python's own spellings for these under a `,.2f` — lower case, no
     * grouping, and no sign on a NaN however its sign bit happens to be set, which is why the NaN
     * arm comes first.
     */
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

// --------------------------------------------------------------------------------------
// The findings
// --------------------------------------------------------------------------------------

/**
 * One price somebody named, and who named it.
 *
 * `label` exists so a finding can be traced back to the row that produced it. An analysis a designer
 * cannot audit is one they have to take on faith, and the whole purpose of this module is to replace
 * faith with arithmetic.
 */
data class DwPriceObservation(
    val amount: Double,
    val category: String = "",
    /** RESPONDENT | COMPETITOR — what a buyer will pay and what the shelf charges are different facts. */
    val source: String = "RESPONDENT",
    /** The RESPONDENT_GROUP token, for respondent observations. */
    val group: String = "",
    val label: String = "",
)

/** A sample's shape. [quantilesReported] is false when the sample was too small to describe. */
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

/**
 * What the survey says about one declared price band.
 *
 *   SOUND         the band covers most of the observed market
 *   NARROW        the band excludes most of it, without being wholly on one side
 *   LOW / HIGH    the band sits below / above where the evidence clusters
 *   UNVERIFIABLE  too few observations to say anything (NOT a criticism of the band)
 *   NO_EVIDENCE   no observations in this category at all
 */
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

/** Where one competitor product sits among the prices observed in its category. */
data class DwCompetitorPosition(
    val name: String,
    val seller: String,
    val category: String,
    val price: Double,
    val percentile: Double?,
    val versusMedian: Double?,
    val note: String,
)

/** Whether one SWOT point is backed by something a respondent actually said. */
data class DwSwotSupport(
    val kind: String,
    val point: String,
    val supportedBy: List<String>,
    val overlap: Int,
    val hasOwnEvidence: Boolean,
) {
    /** A point that cited its own evidence is supported by definition — the designer named a source. */
    val supported: Boolean get() = hasOwnEvidence || supportedBy.isNotEmpty()
}

/** Products that respondents mentioned together, and how often. */
data class DwTrendCluster(
    val members: List<String>,
    val mentions: Int,
    val coOccurrences: Int,
)

/** Everything this module concluded, plus what it could not conclude and why. */
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

    /** Nothing to say and nothing to withhold — the state in which the panel draws nothing at all. */
    val isEmpty: Boolean
        get() = observations == 0 && bands.isEmpty() && swot.isEmpty() &&
            clusters.isEmpty() && cautions.isEmpty()
}

object DwMarketAnalysis {

    /**
     * Words too common to carry evidence. Kept small and English-only ON PURPOSE: the survey text is
     * frequently Odia, Hindi or transliterated, and a large English stop list would strip the rare
     * words that make a match meaningful in exactly the responses that matter most. Matching is a
     * hint for a human to check, never a claim of proof.
     */
    private val STOPWORDS = setOf(
        "and", "are", "but", "for", "from", "has", "have", "its", "not", "our", "that", "the",
        "their", "there", "they", "this", "was", "were", "will", "with", "you", "more", "most",
        "some", "such", "than", "then", "these", "those", "can", "could", "would", "should",
        "may", "might", "also", "very", "much", "many",
    )

    /** The shortest run of letters kept as a content word, counted in CODE POINTS as Python counts. */
    private const val MIN_TOKEN = 3

    /**
     * How many content words a SWOT point and a survey response must share before the response is
     * offered as support. Two, because one shared word is a coincidence at this vocabulary size and
     * three misses a short point ("price too high") against a short response.
     */
    private const val SUPPORT_OVERLAP = 2

    /**
     * A token is a maximal run of characters whose Unicode general category is L (letter) or M
     * (combining mark).
     *
     * THE MARKS ARE THE WHOLE POINT. `\w` and `[^\W\d_]` both look Unicode-correct and are not — they
     * follow `isalnum`, which is FALSE for combining marks — so the virama in ରଙ୍ଗ and the matra in
     * ଦାମ become WORD BOUNDARIES. Odia words shatter into one- and two-character fragments, the
     * length floor discards the fragments, and every Odia response scores as having no content at
     * all. "Unsupported by the survey" then becomes the automatic verdict for precisely the fieldwork
     * this application exists to collect, and it is invisible in English testing.
     */
    private val WORD_RUN = Regex("[\\p{L}\\p{M}]+")

    /** Content words of [text], lowercased. See [WORD_RUN] for the tokenising rule. */
    private fun tokens(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (match in WORD_RUN.findAll(text)) {
            val word = match.value.lowercase()
            // Counted in CODE POINTS, as Python counts them: `length` would read an astral letter as
            // two and let a one-character word through the floor.
            if (word.codePointCount(0, word.length) >= MIN_TOKEN && word !in STOPWORDS) out.add(word)
        }
        return out
    }

    /**
     * The `q`-quantile of an ALREADY SORTED list, by linear interpolation on (n-1) positions.
     *
     * Specified rather than delegated, because three ports have to produce the same rupee figure and
     * none of them may depend on a numerics library to do it. The method is numpy's default:
     *
     *     position = q * (n - 1);  lower = floor(position);  upper = ceil(position)
     *     result   = v[lower] + (v[upper] - v[lower]) * (position - lower)
     *
     * The caller sorts. That is not laziness — every caller here computes several quantiles from one
     * sample, and sorting inside would turn one sort into five.
     */
    fun quantile(sortedValues: List<Double>, q: Double): Double {
        // Zero is a price. Returning it for "no data" would print ₹0 into a costing table.
        require(sortedValues.isNotEmpty()) { "quantile of an empty sample" }
        if (sortedValues.size == 1) return sortedValues[0]
        val position = q * (sortedValues.size - 1)
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        if (lower == upper) return sortedValues[lower]
        return sortedValues[lower] + (sortedValues[upper] - sortedValues[lower]) * (position - lower)
    }

    /**
     * The distribution of [values], or null for an empty sample.
     *
     * Below [DW_MIN_SAMPLE_FOR_QUANTILES] the quartiles are filled with the median and
     * `quantilesReported` is false, so a caller that ignores the flag shows something defensible
     * rather than a quartile computed from two numbers.
     */
    fun describe(values: List<Double>): DwDistribution? {
        if (values.isEmpty()) return null
        val ordered = values.sortedWith(DwPy.doubleOrder)
        val count = ordered.size
        val median = quantile(ordered, 0.5)
        val enough = count >= DW_MIN_SAMPLE_FOR_QUANTILES
        // Summed in SORTED order and through [DwPy.sum], because the server's `sum(ordered)` differs
        // from the obvious loop in BOTH respects. Order, because floating-point addition is not
        // associative, so a mean summed over the original order can differ in the last place from one
        // summed over the sorted array — and `round(mean, 2)` can then land either side of a half.
        // Algorithm, because CPython 3.12's `sum()` is compensated: a sample holding both ends of the
        // range cancels its extremes and leaves the ordinary prices standing on the server, while a
        // running total has already dropped them.
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

    /** One sentence a designer can act on, naming the numbers rather than grading the designer. */
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

    /**
     * Compare one declared band against every price observed in its category.
     *
     * A reversed band (low > high) is read in the order the designer meant rather than refused: the
     * two boxes are adjacent on a phone and transposing them is a slip, not a claim, and answering a
     * slip with "no verdict" would hide the real finding underneath it.
     */
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
                "${dist.count} price observation(s) exist for $named, but the band itself is " +
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
            // NOT a criticism of the band. Seven observations cannot tell a designer their considered
            // band is wrong, and a designer told so by a machine counting seven numbers will —
            // correctly — stop believing the tool, taking the true findings with it. The counts are
            // still reported; they may look and decide for themselves.
            return DwBandVerdict(
                category, declaredLow, declaredHigh, dist, inside, below, above, coverage,
                "UNVERIFIABLE",
                "Only ${dist.count} price observation(s) were recorded for $named — too few to " +
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

    /**
     * Place each competitor product in its category's respondent price distribution.
     *
     * Positioned against what BUYERS said they would pay, not against the other competitors — the
     * useful question for a designer is "is this shelf price above or below what the people we asked
     * are willing to spend", and a competitor-only comparison cannot answer it.
     */
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
            // Skipped rather than counted as zero: a competitor with no price recorded is a row
            // somebody has not finished, and ₹0 on a shelf is a claim about the market nobody made.
            if (price == null) continue
            val category = DwPy.text(row["category"])
            val name = DwPy.strip(DwPy.text(row["name"])).ifEmpty { "Unnamed product" }
            val seller = DwPy.strip(DwPy.text(row["seller"]))

            /*
             * CATEGORY-MATCHED IF POSSIBLE, POOLED IF NOT, AND THE MESSAGE SAYS WHICH.
             *
             * A respondent's price expectation carries NO category — stage 8 asks what they would
             * pay, not what for — so a strict category lookup finds nothing for almost every
             * competitor and the whole section reads "too few buyer price expectations" against a
             * survey of forty people. That is not a cautious answer, it is a useless one, and it is
             * the opposite of what `judgeBand` does above: that one already pools the uncategorised
             * observations. The two must not disagree about the same sample.
             *
             * So the fallback is used and DECLARED. "Against all buyers asked" is a weaker statement
             * than "against buyers asked about this category", and a designer has to be able to tell
             * them apart to know how much weight to put on the number.
             */
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
            // "Above 20% of buyers" is a true sentence that reads as a boast. Say "below 80%" instead.
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

    /**
     * Match each SWOT point to the survey responses that share its vocabulary.
     *
     * THIS IS A RETRIEVAL AID, NOT A JUDGEMENT, and the distinction is the reason it can ship without
     * a model behind it. Shared words mean a response is worth reading next to the point, nothing
     * more; the designer decides whether it supports the claim. What it does reliably is the NEGATIVE
     * case — a SWOT point sharing no vocabulary with any of forty responses and carrying no evidence
     * of its own is an assertion, and saying so is worth more than any positive match.
     *
     * A point that filled its own `evidence` field is supported by definition: the designer cited
     * something. That is not second-guessed.
     */
    fun linkSwotEvidence(swot: List<DwDataRow>, responses: List<DwDataRow>): List<DwSwotSupport> {
        val prepared = ArrayList<Pair<String, Set<String>>>(responses.size)
        for (row in responses) {
            // Joined with a space even when a part is blank, exactly as `" ".join(…)` does: the token
            // set is the same either way, but keeping the string identical is what makes a divergence
            // here impossible to introduce later by accident.
            val builder = StringBuilder(
                listOf("response", "productsDiscussed", "place")
                    .joinToString(" ") { DwPy.text(row[it]) }
            )
            // A tag list contributes its members again as plain words. It costs nothing (the token
            // set already holds them) and it is what the server does, so the two cannot drift apart.
            (row["productsDiscussed"] as? JsonArray)?.let { tags ->
                builder.append(' ').append(tags.joinToString(" ") { DwPy.str(it) })
            }
            val name = DwPy.strip(DwPy.text(row["respondentName"])).ifEmpty { "A respondent" }
            prepared.add(name to tokens(builder.toString()))
        }

        val out = ArrayList<DwSwotSupport>()
        for (row in swot) {
            val point = DwPy.strip(DwPy.text(row["point"]))
            // A blank point is dropped rather than reported unsupported: an empty row in the table is
            // a row somebody has not typed yet, not an unevidenced claim.
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

    /**
     * Group the products respondents discussed by how often they came up in the same conversation.
     *
     * Co-occurrence rather than a topic model, deliberately. It is explainable to the designer whose
     * data it is ("these three were mentioned together nine times"), it is stable — the same rows
     * give the same clusters every run, which a k-means seeded at random does not — and it runs in
     * milliseconds on a handset. A model here would buy nothing that a designer could check.
     *
     * Single-link agglomeration over pairs seen at least twice: two products join when they were
     * discussed together, and a chain of such pairs becomes one cluster.
     */
    fun clusterProducts(
        responses: List<DwDataRow>,
        minimumMentions: Int = 2,
        limit: Int = 8,
    ): List<DwTrendCluster> {
        val tagLists = ArrayList<List<String>>()
        for (row in responses) {
            val raw = row["productsDiscussed"]
            // Android and the web both hand TAGS across as a list, but a hand-edited draft may not.
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

        // LinkedHashMap everywhere below, because Python's dicts and Counters are insertion-ordered
        // and the union-find's answer DEPENDS on that order: which of two roots survives a join is
        // decided by the order the pairs are walked, and a HashMap would hand two clients two
        // different (both defensible) clusterings of one set of rows.
        val mentions = LinkedHashMap<String, Int>()
        val pairs = LinkedHashMap<String, Triple<String, String, Int>>()
        for (tags in tagLists) {
            for (tag in tags) mentions[tag] = (mentions[tag] ?: 0) + 1
            for (i in tags.indices) {
                for (j in i + 1 until tags.size) {
                    // Python keys this on a TUPLE, which cannot be ambiguous. A joined STRING key
                    // can be: on a space, ["a b", "c"] and ["a", "b c"] produce the same key, and
                    // "floor mat" is a real tag in this survey. NUL cannot occur in a tag, so it is
                    // the one safe separator.
                    val key = tags[i] + '\u0000' + tags[j]
                    val existing = pairs[key]
                    pairs[key] = if (existing == null) Triple(tags[i], tags[j], 1)
                    else existing.copy(third = existing.third + 1)
                }
            }
        }

        // Union-find over the pairs that recur. A pair seen once is two people who happened to say
        // the same two words; at two it is worth showing.
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
            // A single mention is not a trend and is not promoted to one.
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
        // Python sorts by `(-mentions, -len(members), members)`; the last term is a TUPLE comparison,
        // which is elementwise by code point and then by length.
        val ordered = clusters.sortedWith(
            compareByDescending<DwTrendCluster> { it.mentions }
                .thenByDescending { it.members.size }
                .thenComparator { a, b -> compareMembers(a.members, b.members) }
        )
        return ordered.take(limit)
    }

    /** Python's tuple comparison over two member lists: elementwise, then by length. */
    private fun compareMembers(a: List<String>, b: List<String>): Int {
        val shared = minOf(a.size, b.size)
        for (index in 0 until shared) {
            val order = DwPy.compareStrings(a[index], b[index])
            if (order != 0) return order
        }
        return a.size - b.size
    }

    /**
     * Every price in stage 8, tagged with where it came from.
     *
     * A respondent's `priceExpectation` carries no category of its own — the survey form asks what
     * they would pay, not what for — so it inherits [defaultCategory] when one is known and is
     * otherwise pooled under "". Pretending to a category it does not have would put a buyer's answer
     * about a stole into the price band for floor coverings.
     */
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

    /**
     * The whole of stage 9's Advanced tier, from the rows of stages 8 and 9.
     *
     * Every argument is the list of stage entry `data` maps exactly as they are stored, so a caller
     * has nothing to reshape and the handset can pass what it already holds in its draft.
     */
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
                // Uncategorised observations are pooled into every band, which is what makes a real
                // survey answerable at all: stage 8 records what a buyer would pay without asking
                // what for.
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
                "Only ${respondentValues.size} respondent price expectation(s) were recorded. " +
                    "The figures below describe what was collected; they are not a market estimate."
            )
        }
        if (groupCounts.isNotEmpty()) {
            // The FIRST-inserted group wins a tie, which is what Python's `most_common` does — it
            // decorates with a decreasing index so ties break by position. Two clients disagreeing
            // about WHICH group dominates would print two different cautions from one set of rows.
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

    // ── The wire form ────────────────────────────────────────────────────────────────────────────

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

    /**
     * Exactly what `GET /design-workshops/{id}/market-analysis` returns.
     *
     * Produced here so the parity harness can diff one JSON document against the server's rather than
     * comparing thirty properties by hand — and so a caller that already holds the endpoint's answer
     * can render it through the same code path as a locally computed one.
     *
     * `cautions` and `unsupportedSwot` are named at the TOP of the object rather than buried inside
     * the sections they qualify. A caution that has to be found is a caution that will not be read,
     * and the whole value of this analysis rests on a designer seeing "these figures come from one
     * respondent group" before they see the figures.
     */
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
