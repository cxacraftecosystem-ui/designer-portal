package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * Stage 17's cost sheets checked against their own line items, on the handset — the port of
 * `backend/app/services/cost_integrity.py`.
 *
 * THE PROBLEM THIS SOLVES. A cost sheet carries a header — material cost, labour cost, six heads and
 * a total — and, underneath it, the material and labour LINES those heads are supposed to summarise.
 * Nothing ever compared the two. A designer hand-totals six material lines, retypes ₹1,650.00 into
 * `materialCost` as ₹1,560.00, and the sheet now contradicts itself; or the sheet was right and a
 * seventh line was appended a week later and the header never moved. Either way the header is what
 * the report prints into a document submitted to a government office, and the lines that disprove it
 * sit two tables below on the same page.
 *
 * WHY IT CANNOT BE A REGISTRY DERIVATION, and therefore why this file exists at all. [DwDerived] is
 * the port of `derive_value`, which takes `(spec, row)` and reads only the SAME row's other fields —
 * `totalCost` is a SUM over six heads of one cost sheet, and that is its whole reach. A roll-up
 * across a sibling COLLECTION is out of it by construction, which is why `stage_definitions` says so
 * in a comment on `materialCost` rather than declaring a derivation there.
 *
 * THIS PRODUCES A FINDING. IT DOES NOT CORRECT ANYTHING, and nothing here is ever written back into
 * the stage. The designer's typed subtotal stays exactly as typed. Overwriting the header with the
 * computed figure would be a worse bug than the one it fixes: a subtotal can legitimately differ from
 * its lines — a rounding, a cost carried but not itemised, a rate renegotiated after the lines were
 * entered — and the designer was in the room when it was decided. What they did not have was the
 * arithmetic in front of them. Now they do, with both numbers named, in the courtyard rather than at
 * a desk a fortnight later.
 *
 * IT REFUSES TO CONCLUDE WHEN IT CANNOT. A sheet with no line items is NOT a contradiction, it is an
 * un-itemised sheet, and it is reported as `NOT_ITEMISED` rather than as an error. A sheet whose
 * lines cannot all be read is `INCOMPLETE` rather than totalled from the readable half — a partial
 * total compared against a correct header manufactures a mismatch that is not there, and a check that
 * accuses correct sheets is a check that gets dismissed, taking the true findings with it.
 *
 * IT NEVER LOSES A ROW. A line whose `costSheetRef` names no sheet in this stage is an ORPHAN and is
 * reported as one, with its amount, because it is fieldwork somebody did and money somebody spent —
 * and because its absence from every subtotal may be the very discrepancy being looked at.
 *
 * MATCHING A LINE TO ITS SHEET follows the convention `report_builder` established: the child's REF
 * field — `costSheetRef`, whose `refModel` is `DwCostSheet` — holds the parent's `_entryId`. That key
 * is the SERVER's id for the row, injected into the stored map by the sync layer; a sheet this device
 * has created and not yet synced has none, so it claims NOTHING rather than claiming the lines whose
 * ref is also blank. Pairing those two would attach a week of costing to a sheet at random.
 */

/**
 * What one sheet's child lines of a single kind add up to, and what could not be read.
 *
 * [unreadable] is separate from [count] on purpose. A total computed from the readable lines alone
 * looks exactly like a total computed from all of them, and the difference is the difference between
 * a finding and a false accusation.
 */
data class DwLineRollUp(
    /** MATERIAL | LABOUR. */
    val kind: String,
    val count: Int = 0,
    val readable: Int = 0,
    val total: Double = 0.0,
    val unreadable: List<String> = emptyList(),
)

/**
 * One comparison between a figure a designer typed and the arithmetic underneath it.
 *
 * [verdict] is one of:
 *   AGREES          within tolerance of what the rows come to
 *   MISMATCH        outside it — the one verdict that becomes a warning
 *   NOT_ITEMISED    there are no rows to roll up. NOT a criticism of the sheet
 *   NOT_DECLARED    nothing was typed to compare against; the computed figure is reported anyway
 *   INCOMPLETE      rows exist but could not all be read, so no total was formed
 *   NOT_COMPUTABLE  the computed side cannot be formed at all (a margin with no cost)
 */
data class DwCostCheck(
    val key: String,
    val label: String,
    /** INR | PCT. */
    val unit: String,
    val declared: Double?,
    val computed: Double?,
    /** declared - computed. */
    val difference: Double?,
    val verdict: String,
    val message: String,
    val lineCount: Int = 0,
) {
    val isFinding: Boolean get() = verdict == "MISMATCH"
}

/**
 * What the sheet's own figures imply about what it earns.
 *
 * [percent] is margin ON COST — `(price - cost) / cost` — and saying which convention is meant is not
 * pedantry: the same sheet is 25% on cost and 20% on price, and the registry's own `marginPercent`
 * allows up to 500, which only a markup on cost can reach.
 *
 * [verdict] is one of:
 *   COMPUTED        a positive margin, reported
 *   AT_COST         price and cost agree within the tolerance — nothing is earned
 *   BELOW_COST      the price is under the cost. A real and common data-entry error
 *   NOT_COMPUTABLE  no price, no cost, or a cost of zero to divide by
 */
data class DwMargin(
    val totalCost: Double?,
    val expectedPrice: Double?,
    val amount: Double?,
    val percent: Double?,
    val verdict: String,
    val message: String,
) {
    val isFinding: Boolean get() = verdict == "BELOW_COST"
}

/**
 * A cost line that belongs to no sheet in this stage.
 *
 * [amount] is null when the line's own amount could not be read — the line is still reported, because
 * the point of reporting it is that it EXISTS and is in no total.
 */
data class DwOrphanLine(
    /** MATERIAL | LABOUR. */
    val kind: String,
    val label: String,
    val ref: String,
    val amount: Double?,
)

/** Everything concluded about one cost sheet, including what could not be concluded. */
data class DwSheetFindings(
    val entryId: String,
    val label: String,
    val checks: List<DwCostCheck>,
    val material: DwLineRollUp,
    val labour: DwLineRollUp,
    val margin: DwMargin,
) {
    val mismatches: List<DwCostCheck> get() = checks.filter { it.isFinding }
}

/** Every sheet's findings, the lines that belong to none of them, and the caveats. */
data class DwCostFindings(
    val sheets: List<DwSheetFindings> = emptyList(),
    val orphans: List<DwOrphanLine> = emptyList(),
    val cautions: List<String> = emptyList(),
) {
    val sheetCount: Int get() = sheets.size

    val mismatches: List<DwCostCheck> get() = sheets.flatMap { it.mismatches }

    /**
     * The findings as sentences, in sheet order.
     *
     * DERIVED FROM THE CHECKS rather than accumulated alongside them, so the banner a designer reads
     * and the per-field verdicts underneath it cannot disagree about the same sheet.
     */
    val warnings: List<String>
        get() = buildList {
            for (sheet in sheets) {
                sheet.checks.filter { it.isFinding }.forEach { add(it.message) }
                if (sheet.margin.isFinding) add(sheet.margin.message)
            }
        }

    /** Nothing was passed in at all — the state in which the panel draws nothing rather than "0 sheets". */
    val isEmpty: Boolean get() = sheets.isEmpty() && orphans.isEmpty()
}

object DwCostIntegrity {

    /**
     * The six heads `totalCost` is declared to SUM over, in `stage_definitions.STAGE_17`.
     *
     * Re-stated here rather than read out of the registry so this module stays free of it and mirrors
     * what the Python and TypeScript ports state; `CostIntegrityParityTest` pins the list against the
     * BUNDLED registry asset, so a head added on the server fails a test here rather than being
     * silently left out of the roll-up on the one client that cannot ask.
     */
    val COST_HEADS = listOf(
        "materialCost", "labourCost", "packagingCost", "finishingCost", "transportCost",
        "overheadCost",
    )

    /** The heads by the label the designer sees, so a message can say which one it could not read. */
    private val COST_HEAD_LABELS = mapOf(
        "materialCost" to "Material cost",
        "labourCost" to "Labour cost",
        "packagingCost" to "Packaging",
        "finishingCost" to "Finishing",
        "transportCost" to "Transport",
        "overheadCost" to "Overhead",
    )

    /**
     * How far a declared subtotal may sit from its lines before it is called a contradiction: ONE
     * RUPEE.
     *
     * THE REASONING, because the number is the whole product. There is no float drift to absorb —
     * MONEY is stored as a fixed-2 string and every line `amount` is already rounded to the paisa by
     * [DwDerived], so a hundred lines accumulate at most half a rupee of rounding between them. The
     * tolerance is not there for the machine's arithmetic. It is there for the designer's: someone
     * who totals ₹1,649.50 of yarn and writes ₹1,650 has rounded to the rupee, which is what a cost
     * sheet is normally quoted in, and that is not a sheet contradicting itself.
     *
     * One rupee is also comfortably below every failure this exists to catch. A transposed digit
     * (₹1,650 as ₹1,560) is ₹90; a misplaced decimal is ten times the sheet; a line entered and never
     * added in is that line's whole amount. Nothing real lands between ₹1 and ₹10. A paisa tolerance
     * would flag every rounded-to-the-rupee subtotal on every correct sheet in the workshop, and a
     * warning that fires on correct data is one designers learn to dismiss — which would cost the
     * real findings underneath it.
     */
    const val TOLERANCE_RUPEES = 1.00

    /**
     * How far a declared `marginPercent` may sit from the margin the sheet's own figures imply: ONE
     * PERCENTAGE POINT. A designer who computes 25.4% and types 25 has rounded; two points apart is a
     * different claim about the same product.
     */
    const val MARGIN_TOLERANCE_POINTS = 1.0

    /** A stored MONEY value as a finite float, or null. Blank is null, not zero. */
    private fun money(value: JsonElement?): Double? = DwPy.asNumber(value)

    /**
     * A figure as a message prints it.
     *
     * TO THE PAISA, deliberately, unlike [DwMarketAnalysis]'s whole-rupee price bands. The tolerance
     * here is a rupee, so a message that rounded to the rupee could report a ₹1.40 discrepancy as
     * "₹1,650 against ₹1,650" and read as though the check had malfunctioned.
     */
    private fun rupees(value: Double): String = "₹" + DwPy.format(value, 2, grouped = true)

    /** A percentage as a message prints it — one place, half to even, matching `f"{x:.1f}"`. */
    private fun percent(value: Double): String = DwPy.format(value, 1, grouped = false)

    // ── What the lines under one sheet come to ───────────────────────────────────────────────────

    /**
     * Total the `amount` column of [lines], keeping the unreadable ones visible.
     *
     * The STORED `amount` is used rather than recomputed from quantity × rate. It is the registry's
     * own PRODUCT derivation, written on save by all three clients, and re-deriving it here would put
     * a second implementation of the same arithmetic in the codebase — the drift then shows up as a
     * roll-up that disagrees with the Amount column printed directly above it in the report. A line
     * whose stored amount is missing or unreadable is therefore counted and NAMED, never quietly
     * skipped, so the caller can decline to conclude.
     */
    fun rollUp(kind: String, lines: List<DwDataRow>): DwLineRollUp {
        val labelKey = if (kind == "MATERIAL") "item" else "task"
        var total = 0.0
        var readable = 0
        val unreadable = ArrayList<String>()
        for (line in lines) {
            val amount = money(line["amount"])
            if (amount == null) {
                unreadable.add(
                    DwPy.strip(DwPy.text(line[labelKey])).ifEmpty { "an unnamed line" }
                )
                continue
            }
            total += amount
            readable++
        }
        return DwLineRollUp(kind, lines.size, readable, total, unreadable)
    }

    // ── One declared figure against what the sheet's own rows say it should be ───────────────────

    /**
     * AGREES or MISMATCH for two figures that both exist.
     *
     * The comparison is `<=` so a difference EXACTLY at the tolerance still agrees — the boundary
     * belongs to the designer, not to the warning.
     */
    private fun compare(declared: Double?, computed: Double?, tolerance: Double): String {
        if (declared == null || computed == null) return "NOT_DECLARED"
        return if (abs(declared - computed) <= tolerance) "AGREES" else "MISMATCH"
    }

    /**
     * One declared subtotal against the lines it summarises.
     *
     * The order of the refusals is the order of the questions. Are there rows at all? Could they all
     * be read? Was anything typed to compare them with? Only then is a mismatch a mismatch.
     */
    fun checkSubtotal(
        sheetLabel: String,
        key: String,
        noun: String,
        declaredRaw: JsonElement?,
        rolled: DwLineRollUp,
    ): DwCostCheck {
        val declared = money(declaredRaw)

        if (rolled.count == 0) {
            // AN UN-ITEMISED SHEET IS NOT A WRONG SHEET, and this is the case the whole module has to
            // get right. Plenty of sheets are entered as totals — a subcontracted product, a cost
            // carried over from a previous workshop — and reporting every one of them as a
            // contradiction would bury the sheets that really are one.
            val message = if (declared == null) {
                "$sheetLabel: no $noun lines are recorded and no $noun cost is declared."
            } else {
                "$sheetLabel: ${rupees(declared)} is declared as $noun cost and there are no $noun " +
                    "lines to check it against. An un-itemised subtotal is not an error — it simply " +
                    "cannot be verified from this stage."
            }
            return DwCostCheck(key, sheetLabel, "INR", declared, null, null, "NOT_ITEMISED", message)
        }

        if (rolled.unreadable.isNotEmpty()) {
            val named = rolled.unreadable.take(3).joinToString(", ")
            val message = "$sheetLabel: ${rolled.unreadable.size} of ${rolled.count} $noun lines " +
                "have no readable amount ($named), so the lines cannot be totalled and the declared " +
                "subtotal cannot be checked. Reopening those lines and saving them will recompute " +
                "the Amount column."
            return DwCostCheck(
                key, sheetLabel, "INR", declared, null, null, "INCOMPLETE", message, rolled.count
            )
        }

        if (declared == null) {
            val message = "$sheetLabel: the ${rolled.count} $noun line(s) add up to " +
                "${rupees(rolled.total)}; no $noun cost is declared on the sheet."
            return DwCostCheck(
                key, sheetLabel, "INR", null, rolled.total, null, "NOT_DECLARED", message,
                rolled.count
            )
        }

        val difference = declared - rolled.total
        val verdict = compare(declared, rolled.total, TOLERANCE_RUPEES)
        val message = if (verdict == "AGREES") {
            "$sheetLabel: the ${rolled.count} $noun line(s) add up to ${rupees(rolled.total)}, " +
                "which is what the sheet declares."
        } else {
            val direction = if (difference < 0) "less" else "more"
            "$sheetLabel: the ${rolled.count} $noun line(s) add up to ${rupees(rolled.total)}, but " +
                "the sheet declares ${rupees(declared)} — ${rupees(abs(difference))} $direction " +
                "than the lines account for."
        }
        return DwCostCheck(
            key, sheetLabel, "INR", declared, rolled.total, difference, verdict, message,
            rolled.count
        )
    }

    /**
     * The six heads added up, and the labels of any that could not be read.
     *
     * MIRRORS `derive_value`'s SUM EXACTLY, and the two rules that make it non-obvious are the reason
     * it is spelled out rather than approximated. A blank head counts as ZERO — four of the six are
     * optional and requiring all six would leave `totalCost` empty for most workshops. But a sheet
     * with NONE of them filled has no total at all rather than a total of zero, because "₹ 0.00" in a
     * cost sheet a ministry reads is a claim and not a blank.
     */
    fun sumCostHeads(sheet: DwDataRow): Pair<Double?, List<String>> {
        var total = 0.0
        var seen = false
        val unreadable = ArrayList<String>()
        for (key in COST_HEADS) {
            val raw = sheet[key]
            // `raw is None or raw == ""` on the server. A stored " " is NEITHER: Python calls it
            // present, fails to parse it and reports the head unreadable, and so does this.
            if (raw == null || raw is JsonNull) continue
            if (raw is JsonPrimitive && raw.isString && raw.content.isEmpty()) continue
            val value = money(raw)
            if (value == null) {
                unreadable.add(COST_HEAD_LABELS.getValue(key))
                continue
            }
            total += value
            seen = true
        }
        return (if (seen) total else null) to unreadable
    }

    /**
     * The stored `totalCost` against the six heads it is derived from.
     *
     * UNLIKE THE SUBTOTALS, THIS ONE IS A DERIVED FIELD, so a mismatch means something narrower and
     * more actionable: the stored value is STALE. It was computed correctly from what the sheet held
     * at the time and a head has moved since — most often on a row written by a client running an
     * older copy of the registry, or edited while offline. The fix is mechanical, and the message
     * says so, which is not true of a subtotal mismatch where only the designer can know the answer.
     */
    fun checkTotalCost(sheetLabel: String, sheet: DwDataRow): DwCostCheck {
        val (computed, unreadable) = sumCostHeads(sheet)
        val declared = money(sheet["totalCost"])

        if (unreadable.isNotEmpty()) {
            val message = "$sheetLabel: ${unreadable.joinToString(", ")} could not be read as a " +
                "number, so the total cost cannot be checked against the cost heads."
            return DwCostCheck(
                "totalCost", sheetLabel, "INR", declared, null, null, "INCOMPLETE", message
            )
        }

        if (computed == null) {
            val message = "$sheetLabel: none of the six cost heads is filled in, so there is no " +
                "total to check."
            return DwCostCheck(
                "totalCost", sheetLabel, "INR", declared, null, null, "NOT_ITEMISED", message
            )
        }

        if (declared == null) {
            val message = "$sheetLabel: the cost heads add up to ${rupees(computed)}; no total cost " +
                "is stored on the sheet."
            return DwCostCheck(
                "totalCost", sheetLabel, "INR", null, computed, null, "NOT_DECLARED", message
            )
        }

        val difference = declared - computed
        val verdict = compare(declared, computed, TOLERANCE_RUPEES)
        val message = if (verdict == "AGREES") {
            "$sheetLabel: the stored total cost ${rupees(declared)} matches the six cost heads."
        } else {
            "$sheetLabel: the six cost heads add up to ${rupees(computed)}, but the stored total " +
                "cost is ${rupees(declared)}. Total cost is a derived field, so the stored value is " +
                "out of date — reopening the sheet and saving it will recompute it."
        }
        return DwCostCheck(
            "totalCost", sheetLabel, "INR", declared, computed, difference, verdict, message
        )
    }

    // ── Margin ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The margin between `expectedPrice` and the sheet's cost.
     *
     * COMPUTED FROM THE SIX HEADS RATHER THAN THE STORED `totalCost`, falling back to the stored
     * value only when no head is filled. If the stored total is stale — which [checkTotalCost] may
     * have just reported — a margin taken from it would be a second wrong number derived from the
     * first, and the two findings would contradict each other on the same sheet.
     */
    fun computeMargin(sheetLabel: String, sheet: DwDataRow): DwMargin {
        val (computed, unreadable) = sumCostHeads(sheet)
        var cost = if (unreadable.isEmpty()) computed else null
        if (cost == null) cost = money(sheet["totalCost"])
        val price = money(sheet["expectedPrice"])

        if (cost == null || price == null || cost <= 0) {
            // A cost of zero is not a free product, it is a sheet nobody has costed yet, and dividing
            // by it would either raise or report an infinite margin into a report.
            val reason = when {
                price == null && cost == null -> "neither an expected price nor any cost is recorded"
                price == null -> "no expected price is recorded"
                cost == null -> "no cost is recorded"
                else -> "the total cost is zero"
            }
            return DwMargin(
                cost, price, null, null, "NOT_COMPUTABLE",
                "$sheetLabel: no margin can be implied — $reason."
            )
        }

        val amount = price - cost
        val pct = amount / cost * 100.0

        if (abs(amount) <= TOLERANCE_RUPEES) {
            return DwMargin(
                cost, price, amount, pct, "AT_COST",
                "$sheetLabel: the expected price ${rupees(price)} is the same as the total cost " +
                    "${rupees(cost)} — this product earns nothing as priced."
            )
        }
        if (amount < 0) {
            return DwMargin(
                cost, price, amount, pct, "BELOW_COST",
                "$sheetLabel: the expected price ${rupees(price)} is below the total cost " +
                    "${rupees(cost)} — a loss of ${rupees(abs(amount))} on every piece sold."
            )
        }
        return DwMargin(
            cost, price, amount, pct, "COMPUTED",
            "$sheetLabel: an expected price of ${rupees(price)} against a total cost of " +
                "${rupees(cost)} implies a margin of ${rupees(amount)}, or ${percent(pct)}% on cost."
        )
    }

    /**
     * The typed `marginPercent` against the margin the sheet's own figures imply.
     *
     * The same class of bug as a mistyped subtotal — a number a designer entered by hand that the
     * rest of the sheet contradicts — and nothing checked it before either.
     */
    fun checkDeclaredMargin(sheetLabel: String, sheet: DwDataRow, margin: DwMargin): DwCostCheck {
        val declared = DwPy.asNumber(sheet["marginPercent"])

        if (margin.percent == null) {
            val message = if (declared == null) {
                "$sheetLabel: there is no implied margin to check a declared margin against."
            } else {
                "$sheetLabel: a margin of ${percent(declared)}% is declared, but the sheet has no " +
                    "price and cost to imply a margin from, so it cannot be checked."
            }
            return DwCostCheck(
                "marginPercent", sheetLabel, "PCT", declared, null, null, "NOT_COMPUTABLE", message
            )
        }

        if (declared == null) {
            return DwCostCheck(
                "marginPercent", sheetLabel, "PCT", null, margin.percent, null, "NOT_DECLARED",
                "$sheetLabel: the sheet's figures imply a margin of ${percent(margin.percent)}% on " +
                    "cost; no margin is declared."
            )
        }

        val difference = declared - margin.percent
        val verdict = compare(declared, margin.percent, MARGIN_TOLERANCE_POINTS)
        val message = if (verdict == "AGREES") {
            "$sheetLabel: the declared margin of ${percent(declared)}% matches the " +
                "${percent(margin.percent)}% implied by the price and cost."
        } else {
            "$sheetLabel: a margin of ${percent(declared)}% is declared, but the expected price and " +
                "total cost on this sheet imply ${percent(margin.percent)}% on cost."
        }
        return DwCostCheck(
            "marginPercent", sheetLabel, "PCT", declared, margin.percent, difference, verdict,
            message
        )
    }

    // ── One sheet, and the whole stage ──────────────────────────────────────────────────────────

    /**
     * What a finding calls this sheet.
     *
     * The entity's `labelField` is `productRef`, a REF this pure module cannot resolve on its own —
     * the products it points at live in stage 16. The caller passes what it has resolved, and the
     * fallback is positional, which is the same answer `report_builder._row_label` gives a sheet that
     * names no product. A finding a designer cannot trace back to a row is one they cannot act on.
     */
    private fun sheetLabel(sheet: DwDataRow, index: Int, labels: Map<String, String>): String {
        val ref = sheet["productRef"]
        // `isinstance(ref, str) and ref` on the server: a REF that somehow holds a number is not an
        // id, and looking one up by its decimal spelling would attach a finding to the wrong product.
        if (ref is JsonPrimitive && ref !is JsonNull && ref.isString && ref.content.isNotEmpty()) {
            val label = labels[ref.content].orEmpty()
            if (label.isNotEmpty()) return label
        }
        return "Cost sheet $index"
    }

    /** One sentence naming lines in no sheet, and the money that is therefore in no total. */
    private fun orphanCaution(noun: String, orphans: List<DwOrphanLine>): String {
        val named = orphans.take(3).filter { it.label.isNotEmpty() }.joinToString(", ") { it.label }
        val amounts = orphans.mapNotNull { it.amount }
        val total = if (amounts.isNotEmpty()) {
            // DwPy.sum and not `amounts.sum()`: the server's `sum()` is compensated, and these
            // amounts are the least filtered in the module — whatever was typed, at any magnitude.
            " Their amounts come to ${rupees(DwPy.sum(amounts))}, counted in no subtotal."
        } else {
            " None of them has a readable amount."
        }
        val plural = if (orphans.size == 1) "line is" else "lines are"
        val naming = if (named.isNotEmpty()) " ($named)" else ""
        return "${orphans.size} $noun $plural not attached to any cost sheet in this stage$naming. " +
            "The sheet they named may have been deleted after they were recorded.$total"
    }

    /**
     * Every stage-17 cost sheet checked against its own line items.
     *
     * Each argument is the list of stage entry `data` maps exactly as they are stored, WITH
     * `_entryId` present on each row — so a caller has nothing to reshape and the handset can pass
     * what it already holds. [labels] maps an entry id to the name it should print under, for the
     * `productRef` this module cannot resolve itself; it is optional, and its absence costs a
     * readable sheet name, never a wrong verdict.
     */
    fun analyse(
        sheets: List<DwDataRow>,
        materialLines: List<DwDataRow>,
        labourLines: List<DwDataRow>,
        labels: Map<String, String> = emptyMap(),
    ): DwCostFindings {
        // Bucket the children by the parent id they name, exactly as `report_builder._child_groups`
        // does: an empty or missing ref goes to "" and is never matched to a sheet, because a sheet
        // that has not synced yet has no `_entryId` either and pairing the two would attach lines to
        // a sheet at random. LinkedHashMap so the orphan list comes out in the order the rows were
        // captured — the order the designer will scroll past looking for them.
        val materialBySheet = LinkedHashMap<String, MutableList<DwDataRow>>()
        val labourBySheet = LinkedHashMap<String, MutableList<DwDataRow>>()
        for ((rows, bucket) in listOf(
            materialLines to materialBySheet,
            labourLines to labourBySheet,
        )) {
            for (row in rows) {
                val ref = row["costSheetRef"]
                val key = if (ref is JsonPrimitive && ref !is JsonNull && ref.isString) ref.content
                else ""
                bucket.getOrPut(key) { ArrayList() }.add(row)
            }
        }

        val found = ArrayList<DwSheetFindings>()
        sheets.forEachIndexed { position, sheet ->
            val index = position + 1
            val rawId = sheet["_entryId"]
            val entryId =
                if (rawId is JsonPrimitive && rawId !is JsonNull && rawId.isString) rawId.content
                else ""
            val label = sheetLabel(sheet, index, labels)

            val material = rollUp(
                "MATERIAL",
                if (entryId.isNotEmpty()) materialBySheet.remove(entryId).orEmpty() else emptyList()
            )
            val labour = rollUp(
                "LABOUR",
                if (entryId.isNotEmpty()) labourBySheet.remove(entryId).orEmpty() else emptyList()
            )

            val margin = computeMargin(label, sheet)
            val checks = listOf(
                checkSubtotal(label, "materialCost", "material", sheet["materialCost"], material),
                checkSubtotal(label, "labourCost", "labour", sheet["labourCost"], labour),
                checkTotalCost(label, sheet),
                checkDeclaredMargin(label, sheet, margin),
            )
            found.add(DwSheetFindings(entryId, label, checks, material, labour, margin))
        }

        // WHATEVER IS LEFT IN THE BUCKETS BELONGS TO NO SHEET. Reported, never dropped: these are
        // lines somebody entered and money somebody spent, and their absence from every subtotal is a
        // candidate explanation for any mismatch above.
        val orphans = ArrayList<DwOrphanLine>()
        val cautions = ArrayList<String>()
        for ((kind, noun, bucket) in listOf(
            Triple("MATERIAL", "material", materialBySheet),
            Triple("LABOUR", "labour", labourBySheet),
        )) {
            val labelKey = if (kind == "MATERIAL") "item" else "task"
            val ofKind = bucket.values.flatten().map { row ->
                DwOrphanLine(
                    kind = kind,
                    label = DwPy.strip(DwPy.text(row[labelKey])).ifEmpty { "an unnamed line" },
                    ref = DwPy.text(row["costSheetRef"]),
                    amount = money(row["amount"]),
                )
            }
            if (ofKind.isNotEmpty()) {
                orphans.addAll(ofKind)
                cautions.add(orphanCaution("$noun cost", ofKind))
            }
        }

        return DwCostFindings(found, orphans, cautions)
    }

    // ── The wire form ───────────────────────────────────────────────────────────────────────────

    private fun rounded(value: Double?, places: Int = 2): JsonElement =
        if (value == null) JsonNull else JsonPrimitive(DwPy.round(value, places))

    /**
     * Exactly what `GET /design-workshops/{id}/cost-integrity` returns.
     *
     * `warnings` is named at the TOP rather than left to be assembled by each client from the
     * per-check verdicts. Three clients reading the same rules three ways is three chances to show a
     * designer a clean sheet that this module found a contradiction in.
     */
    fun payload(findings: DwCostFindings): JsonObject = buildJsonObject {
        put("sheetCount", findings.sheetCount)
        put("warnings", buildJsonArray { findings.warnings.forEach { add(JsonPrimitive(it)) } })
        put("cautions", buildJsonArray { findings.cautions.forEach { add(JsonPrimitive(it)) } })
        put("sheets", buildJsonArray {
            findings.sheets.forEach { sheet ->
                add(buildJsonObject {
                    put("entryId", sheet.entryId)
                    put("label", sheet.label)
                    put("materialLines", sheet.material.count)
                    put("labourLines", sheet.labour.count)
                    put("checks", buildJsonArray {
                        sheet.checks.forEach { check ->
                            add(buildJsonObject {
                                put("key", check.key)
                                put("unit", check.unit)
                                put("declared", rounded(check.declared))
                                put("computed", rounded(check.computed))
                                put("difference", rounded(check.difference))
                                put("verdict", check.verdict)
                                put("message", check.message)
                                put("lineCount", check.lineCount)
                            })
                        }
                    })
                    put("margin", buildJsonObject {
                        put("totalCost", rounded(sheet.margin.totalCost))
                        put("expectedPrice", rounded(sheet.margin.expectedPrice))
                        put("amount", rounded(sheet.margin.amount))
                        put("percent", rounded(sheet.margin.percent, 1))
                        put("verdict", sheet.margin.verdict)
                        put("message", sheet.margin.message)
                    })
                })
            }
        })
        put("orphans", buildJsonArray {
            findings.orphans.forEach { orphan ->
                add(buildJsonObject {
                    put("kind", orphan.kind)
                    put("label", orphan.label)
                    put("ref", orphan.ref)
                    put("amount", rounded(orphan.amount))
                })
            }
        })
    }
}
