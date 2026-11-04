package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

data class DwLineRollUp(
    val kind: String,
    val count: Int = 0,
    val readable: Int = 0,
    val total: Double = 0.0,
    val unreadable: List<String> = emptyList(),
)

data class DwCostCheck(
    val key: String,
    val label: String,
    val unit: String,
    val declared: Double?,
    val computed: Double?,
    val difference: Double?,
    val verdict: String,
    val message: String,
    val lineCount: Int = 0,
) {
    val isFinding: Boolean get() = verdict == "MISMATCH"
}

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

data class DwOrphanLine(
    val kind: String,
    val label: String,
    val ref: String,
    val amount: Double?,
)

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

data class DwCostFindings(
    val sheets: List<DwSheetFindings> = emptyList(),
    val orphans: List<DwOrphanLine> = emptyList(),
    val cautions: List<String> = emptyList(),
) {
    val sheetCount: Int get() = sheets.size

    val mismatches: List<DwCostCheck> get() = sheets.flatMap { it.mismatches }

    val warnings: List<String>
        get() = buildList {
            for (sheet in sheets) {
                sheet.checks.filter { it.isFinding }.forEach { add(it.message) }
                if (sheet.margin.isFinding) add(sheet.margin.message)
            }
        }

    val isEmpty: Boolean get() = sheets.isEmpty() && orphans.isEmpty()
}

object DwCostIntegrity {

    val COST_HEADS = listOf(
        "materialCost", "labourCost", "packagingCost", "finishingCost", "transportCost",
        "overheadCost",
    )

    private val COST_HEAD_LABELS = mapOf(
        "materialCost" to "Material cost",
        "labourCost" to "Labour cost",
        "packagingCost" to "Packaging",
        "finishingCost" to "Finishing",
        "transportCost" to "Transport",
        "overheadCost" to "Overhead",
    )

    const val TOLERANCE_RUPEES = 1.00

    const val MARGIN_TOLERANCE_POINTS = 1.0

    private fun money(value: JsonElement?): Double? = DwPy.asNumber(value)

    private fun rupees(value: Double): String = "₹" + DwPy.format(value, 2, grouped = true)

    private fun percent(value: Double): String = DwPy.format(value, 1, grouped = false)

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

    private fun compare(declared: Double?, computed: Double?, tolerance: Double): String {
        if (declared == null || computed == null) return "NOT_DECLARED"
        return if (abs(declared - computed) <= tolerance) "AGREES" else "MISMATCH"
    }

    fun checkSubtotal(
        sheetLabel: String,
        key: String,
        noun: String,
        declaredRaw: JsonElement?,
        rolled: DwLineRollUp,
    ): DwCostCheck {
        val declared = money(declaredRaw)

        if (rolled.count == 0) {
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
            val message = "$sheetLabel: the ${rolled.count} $noun ${if (rolled.count == 1) "line adds" else "lines add"} up to " +
                "${rupees(rolled.total)}; no $noun cost is declared on the sheet."
            return DwCostCheck(
                key, sheetLabel, "INR", null, rolled.total, null, "NOT_DECLARED", message,
                rolled.count
            )
        }

        val difference = declared - rolled.total
        val verdict = compare(declared, rolled.total, TOLERANCE_RUPEES)
        val message = if (verdict == "AGREES") {
            "$sheetLabel: the ${rolled.count} $noun ${if (rolled.count == 1) "line adds" else "lines add"} up to ${rupees(rolled.total)}, " +
                "which is what the sheet declares."
        } else {
            val direction = if (difference < 0) "less" else "more"
            "$sheetLabel: the ${rolled.count} $noun ${if (rolled.count == 1) "line adds" else "lines add"} up to ${rupees(rolled.total)}, but " +
                "the sheet declares ${rupees(declared)} — ${rupees(abs(difference))} $direction " +
                "than the lines account for."
        }
        return DwCostCheck(
            key, sheetLabel, "INR", declared, rolled.total, difference, verdict, message,
            rolled.count
        )
    }

    fun sumCostHeads(sheet: DwDataRow): Pair<Double?, List<String>> {
        var total = 0.0
        var seen = false
        val unreadable = ArrayList<String>()
        for (key in COST_HEADS) {
            val raw = sheet[key]
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

    fun computeMargin(sheetLabel: String, sheet: DwDataRow): DwMargin {
        val (computed, unreadable) = sumCostHeads(sheet)
        var cost = if (unreadable.isEmpty()) computed else null
        if (cost == null) cost = money(sheet["totalCost"])
        val price = money(sheet["expectedPrice"])

        if (cost == null || price == null || cost <= 0) {
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

    private fun sheetLabel(sheet: DwDataRow, index: Int, labels: Map<String, String>): String {
        val ref = sheet["productRef"]
        if (ref is JsonPrimitive && ref !is JsonNull && ref.isString && ref.content.isNotEmpty()) {
            val label = labels[ref.content].orEmpty()
            if (label.isNotEmpty()) return label
        }
        return "Cost sheet $index"
    }

    private fun orphanCaution(noun: String, orphans: List<DwOrphanLine>): String {
        val named = orphans.take(3).filter { it.label.isNotEmpty() }.joinToString(", ") { it.label }
        val amounts = orphans.mapNotNull { it.amount }
        val total = if (amounts.isNotEmpty()) {
            " Their amounts come to ${rupees(DwPy.sum(amounts))}, counted in no subtotal."
        } else {
            " None of them has a readable amount."
        }
        val plural = if (orphans.size == 1) "line is" else "lines are"
        val naming = if (named.isNotEmpty()) " ($named)" else ""
        return "${orphans.size} $noun $plural not attached to any cost sheet in this stage$naming. " +
            "The sheet they named may have been deleted after they were recorded.$total"
    }

    fun analyse(
        sheets: List<DwDataRow>,
        materialLines: List<DwDataRow>,
        labourLines: List<DwDataRow>,
        labels: Map<String, String> = emptyMap(),
    ): DwCostFindings {
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

    private fun rounded(value: Double?, places: Int = 2): JsonElement =
        if (value == null) JsonNull else JsonPrimitive(DwPy.round(value, places))

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
