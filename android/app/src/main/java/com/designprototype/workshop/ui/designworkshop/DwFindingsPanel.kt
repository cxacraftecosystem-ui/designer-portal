package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.plural
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwCostFindings
import com.designprototype.workshop.data.DwCostIntegrity
import com.designprototype.workshop.data.DwDataRow
import com.designprototype.workshop.data.DwDistribution
import com.designprototype.workshop.data.DwMarketAnalysis
import com.designprototype.workshop.data.DwMarketFindings
import com.designprototype.workshop.data.DwPy
import com.designprototype.workshop.data.DwReferenceStore
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.data.rowsFor
import com.designprototype.workshop.ui.ActionRow
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.serialization.json.JsonPrimitive

private const val STAGE_MARKET_ANALYSIS = "MARKET_ANALYSIS_DIRECTION"
private const val STAGE_MARKET_SURVEY = "MARKET_SURVEY_CAPTURE"
private const val STAGE_COSTING = "COSTING_MARKET_LINKAGE"
private const val STAGE_FINAL_PRODUCTS = "FINAL_PROTOTYPE_DOCUMENTATION"

@Composable
fun DwStageFindings(
    stage: StageDto,
    workshopId: String,
    repository: WorkshopRepository,
    rows: Map<String, List<DwDataRow>>,
    onUse: ((sheetEntryId: String, key: String, value: String) -> Unit)? = null,
) {
    when (stage.key) {
        STAGE_MARKET_ANALYSIS -> MarketFindingsCard(workshopId, repository, rows)
        STAGE_COSTING -> CostFindingsCard(workshopId, rows, onUse)
        else -> Unit
    }
}

@Composable
private fun MarketFindingsCard(
    workshopId: String,
    repository: WorkshopRepository,
    rows: Map<String, List<DwDataRow>>,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    var survey by remember(workshopId) { mutableStateOf<SurveyRows?>(null) }
    var searched by remember(workshopId) { mutableStateOf(false) }
    var saved by remember(workshopId) { mutableStateOf<DwMarketFindings?>(null) }

    LaunchedEffect(workshopId) {
        val draft = runCatching { WorkshopDraftStore.load(appContext, workshopId) }.getOrNull()
        val local = draft?.stages?.get(STAGE_MARKET_SURVEY)
        val fromDraft = SurveyRows(
            responses = local?.rowsFor("surveyResponse").orEmpty().map { it.values },
            competitors = local?.rowsFor("competitorProduct").orEmpty().map { it.values },
        )
        if (fromDraft.isNotEmpty()) {
            survey = fromDraft
            searched = true
            return@LaunchedEffect
        }
        val remoteId = draft?.remoteId ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
        if (remoteId != null) {
            val bucket = runCatching {
                repository.designWorkshopStage(remoteId, STAGE_MARKET_SURVEY)
            }.getOrNull()
            if (bucket != null) {
                survey = SurveyRows(
                    responses = bucket.collections["surveyResponse"].orEmpty().map { it.toMap() },
                    competitors = bucket.collections["competitorProduct"].orEmpty().map { it.toMap() },
                )
            } else {
                saved = repository.designWorkshopMarketAnalysis(remoteId)
            }
        }
        searched = true
    }

    if (!searched) return
    saved?.let { fromServer ->
        if (survey == null && !fromServer.isEmpty) {
            MarketFindingsBody(
                findings = fromServer,
                subtitle = DW_MARKET_FINDINGS_SAVED_SUBTITLE,
                surveyMissing = false,
            )
            return
        }
    }
    val rowsFound = survey ?: SurveyRows(emptyList(), emptyList())
    val bands = rows["priceBand"].orEmpty()
    val swot = rows["swotPoint"].orEmpty()

    val findings = remember(rowsFound, bands, swot) {
        DwMarketAnalysis.analyse(
            responses = rowsFound.responses,
            competitors = rowsFound.competitors,
            bands = bands,
            swot = swot,
        )
    }
    if (findings.isEmpty && rowsFound.isEmpty()) {
        return
    }

    MarketFindingsBody(
        findings = findings,
        subtitle = "From ${plural(rowsFound.responses.size, "survey response")} and ${plural(rowsFound.competitors.size, "competitor product")} in stage 8. Not saved to this stage.",
        surveyMissing = rowsFound.isEmpty(),
    )
}

internal const val DW_MARKET_FINDINGS_SAVED_SUBTITLE =
    "From the survey and bands last saved on the server. Save this stage to check what is on screen."

@Composable
private fun MarketFindingsBody(findings: DwMarketFindings, subtitle: String, surveyMissing: Boolean) {
    FindingsCard(
        title = "What the survey says",
        subtitle = subtitle,
    ) {
        findings.cautions.forEach { Advisory(it, MaterialTheme.field.warningContainer, MaterialTheme.field.onWarningContainer) }

        if (surveyMissing) {
            Advisory(
                "Stage 8 isn't on this phone. Open it once online to run these checks.",
                MaterialTheme.field.surface100, MaterialTheme.field.body,
            )
        }

        DistributionLine("Buyer price expectations", findings.respondentPrices)
        DistributionLine("Competitor shelf prices", findings.competitorPrices)

        if (findings.bands.isNotEmpty()) {
            SectionLabel("Declared price bands")
            findings.bands.forEach { band ->
                Verdict(
                    token = band.verdict,
                    headline = band.category.ifBlank { "Uncategorised band" },
                    body = band.message,
                    good = band.verdict == "SOUND",
                    bad = band.verdict == "LOW" || band.verdict == "HIGH" || band.verdict == "NARROW",
                )
            }
        }

        if (findings.competitors.isNotEmpty()) {
            SectionLabel("Competitor placement")
            findings.competitors.forEach { competitor ->
                val who = listOf(competitor.name, competitor.seller)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                Advisory("$who — ${competitor.note}", MaterialTheme.field.surface100, MaterialTheme.field.body)
            }
        }

        if (findings.swot.isNotEmpty()) {
            SectionLabel("SWOT points against what respondents said")
            findings.swot.forEach { point ->
                val body = when {
                    point.hasOwnEvidence ->
                        "Evidence cited on the point."
                    point.supportedBy.isNotEmpty() ->
                        "Shares ${plural(point.overlap, "word")} with what " +
                            point.supportedBy.joinToString(", ") + " said — worth comparing."
                    else ->
                        "No survey evidence matches this point yet."
                }
                Verdict(
                    token = point.kind.ifBlank { "POINT" },
                    headline = point.point,
                    body = body,
                    good = point.supported,
                    bad = !point.supported,
                )
            }
        }

        if (findings.clusters.isNotEmpty()) {
            SectionLabel("Products discussed together")
            findings.clusters.forEach { cluster ->
                Advisory(
                    cluster.members.joinToString(", ") + " — ${plural(cluster.mentions, "mention")}, " +
                        "${cluster.coOccurrences} in the same conversation.",
                    MaterialTheme.field.surface100, MaterialTheme.field.body,
                )
            }
        }
    }
}

internal fun dwUnsyncedSheetCount(sheets: List<DwDataRow>): Int = sheets.count {
    (it["_entryId"] as? JsonPrimitive)?.content?.isNotEmpty() != true
}

private data class SurveyRows(
    val responses: List<DwDataRow>,
    val competitors: List<DwDataRow>,
) {
    fun isEmpty(): Boolean = responses.isEmpty() && competitors.isEmpty()
    fun isNotEmpty(): Boolean = !isEmpty()
}

@Composable
private fun DistributionLine(label: String, dist: DwDistribution?) {
    if (dist == null) return
    val money = { value: Double -> "₹" + DwPy.format(value, 0, grouped = true) }
    val quartiles = if (dist.quantilesReported) {
        " · quartiles ${money(dist.p25)}–${money(dist.p75)}"
    } else {
        " · too few for quartiles"
    }
    Advisory(
        "$label: ${plural(dist.count, "observation")}, ${money(dist.minimum)}–${money(dist.maximum)}, " +
            "median ${money(dist.median)}$quartiles",
        MaterialTheme.field.surface100,
        MaterialTheme.field.body,
    )
}

@Composable
private fun CostFindingsCard(
    workshopId: String,
    rows: Map<String, List<DwDataRow>>,
    onUse: ((String, String, String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    val sheets = rows["costSheet"].orEmpty()
    val materialLines = rows["costMaterialLine"].orEmpty()
    val labourLines = rows["costLabourLine"].orEmpty()
    if (sheets.isEmpty() && materialLines.isEmpty() && labourLines.isEmpty()) return

    var labels by remember(workshopId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(workshopId) {
        val draft = runCatching { WorkshopDraftStore.load(appContext, workshopId) }.getOrNull()
        val products = draft?.stages?.get(STAGE_FINAL_PRODUCTS)?.rowsFor("finalProduct").orEmpty()
        val resolved = LinkedHashMap<String, String>()
        for (row in products) {
            val id = (row.values["_entryId"] as? JsonPrimitive)?.content.orEmpty()
            val name = (row.values["name"] as? JsonPrimitive)?.content.orEmpty()
            if (id.isNotEmpty() && name.isNotEmpty()) resolved[id] = name
        }
        for (sheet in sheets) {
            val ref = (sheet["productRef"] as? JsonPrimitive)?.content.orEmpty()
            if (ref.isNotEmpty() && ref !in resolved) {
                DwReferenceStore.labelFor(ref)?.let { resolved[ref] = it }
            }
        }
        labels = resolved
    }

    val findings = remember(sheets, materialLines, labourLines, labels) {
        DwCostIntegrity.analyse(sheets, materialLines, labourLines, labels)
    }

    val unsyncedSheets = dwUnsyncedSheetCount(sheets)
    val anyUnsynced = unsyncedSheets > 0

    FindingsCard(
        title = "Cost sheets against their own lines",
        subtitle = "${plural(findings.sheetCount, "sheet")}, ${plural(materialLines.size, "material line")}, ${plural(labourLines.size, "labour line")}. Not saved to this stage.",
    ) {
        if (anyUnsynced) {
            Advisory(
                "$unsyncedSheets of ${plural(sheets.size, "sheet")} still uploading; their lines aren't totalled yet.",
                MaterialTheme.field.surface100, MaterialTheme.field.body,
            )
        }

        findings.warnings.forEach {
            Advisory(it, MaterialTheme.field.warningContainer, MaterialTheme.field.onWarningContainer)
        }
        if (!anyUnsynced) {
            findings.cautions.forEach {
                Advisory(it, MaterialTheme.field.warningContainer, MaterialTheme.field.onWarningContainer)
            }
        }

        findings.sheets.forEach { sheet ->
            SectionLabel(sheet.label)
            sheet.checks.forEach { check ->
                Verdict(
                    token = check.verdict,
                    headline = check.key,
                    body = check.message,
                    good = check.verdict == "AGREES",
                    bad = check.verdict == "MISMATCH",
                )
                val computed = check.computed
                if (onUse != null && sheet.entryId.isNotEmpty() && computed != null && dwUsableCheck(check)) {
                    ActionRow {
                        OutlinedButton(
                            onClick = { onUse(sheet.entryId, check.key, String.format(java.util.Locale.ROOT, "%.2f", computed)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Use ${dwRupees(computed)}")
                        }
                    }
                }
            }
            Verdict(
                token = sheet.margin.verdict,
                headline = "margin",
                body = sheet.margin.message,
                good = sheet.margin.verdict == "COMPUTED",
                bad = sheet.margin.verdict == "BELOW_COST",
            )
        }
    }
}

@Composable
private fun FindingsCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    var expanded by remember(title) { mutableStateOf(true) }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
            ) {
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Hide these findings" else "Show these findings",
                    tint = MaterialTheme.field.muted,
                )
            }
            Text(subtitle, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            if (expanded) {
                HorizontalDivider()
                content()
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = MaterialTheme.field.muted,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun Advisory(text: String, container: Color, ink: Color) {
    Text(
        text,
        color = ink,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun Verdict(token: String, headline: String, body: String, good: Boolean, bad: Boolean) {
    val container = when {
        bad -> MaterialTheme.field.warningContainer
        good -> MaterialTheme.field.successContainer
        else -> MaterialTheme.field.surface100
    }
    val ink = when {
        bad -> MaterialTheme.field.onWarningContainer
        good -> MaterialTheme.field.onSuccessContainer
        else -> MaterialTheme.field.body
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            "$token · $headline",
            color = ink,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(body, color = ink, style = MaterialTheme.typography.bodySmall)
    }
}

internal fun dwUsableCheck(check: com.designprototype.workshop.data.DwCostCheck): Boolean =
    (check.key == "materialCost" || check.key == "labourCost") &&
        check.computed != null &&
        (check.verdict == "MISMATCH" || check.verdict == "NOT_DECLARED")

internal fun dwRupees(value: Double): String =
    "₹" + java.text.NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag("en-IN")).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(value)
