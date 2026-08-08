package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the rows on this handset actually say, printed BESIDE the boxes the designer typed into.
 *
 * WHY THIS EXISTS AND WHY IT IS HERE RATHER THAN BEHIND A REQUEST. [DwMarketAnalysis] and
 * [DwCostIntegrity] are ports of two pure backend modules, and both were written so the same findings
 * could be produced without a database, a network or a model. Until this panel there was nowhere on
 * the handset they surfaced: a designer stood in stage 9 typing a price band having collected the
 * price expectations that contradict it into stage 8 that morning, and nothing compared the two; a
 * designer typed a material subtotal into stage 17 with the six lines that disprove it in the list
 * directly above, and nothing added them up. Both findings existed — on the server, days later, at a
 * desk, about a workshop that had ended.
 *
 * IT NEVER WRITES TO THE STAGE. Nothing in this file returns a value, seeds a field or offers to
 * "fix" anything. The designer's declared band and typed subtotal stay exactly as typed. A subtotal
 * can legitimately differ from its lines and a band can legitimately be a considered judgement about
 * a market the survey under-sampled — the designer was in the room and the arithmetic was not. What
 * changes is that the judgement is now made in front of the evidence.
 *
 * IT IS ADVISORY AND FOLDS AWAY. Every card here collapses, and none of it gates a save or a sync. A
 * warning that blocks fieldwork is a warning that gets worked around.
 */

/** The stage keys that have something computable to say. Anything else draws nothing at all. */
private const val STAGE_MARKET_ANALYSIS = "MARKET_ANALYSIS_DIRECTION"
private const val STAGE_MARKET_SURVEY = "MARKET_SURVEY_CAPTURE"
private const val STAGE_COSTING = "COSTING_MARKET_LINKAGE"
private const val STAGE_FINAL_PRODUCTS = "FINAL_PROTOTYPE_DOCUMENTATION"

@Composable
fun DwStageFindings(
    stage: StageDto,
    workshopId: String,
    repository: WorkshopRepository,
    /** This stage's collection rows as the form currently holds them, keyed by entity. */
    rows: Map<String, List<DwDataRow>>,
) {
    when (stage.key) {
        STAGE_MARKET_ANALYSIS -> MarketFindingsCard(workshopId, repository, rows)
        STAGE_COSTING -> CostFindingsCard(workshopId, rows)
        else -> Unit
    }
}

// --------------------------------------------------------------------------------------
// Stage 9 — what the survey says about what was just typed
// --------------------------------------------------------------------------------------

@Composable
private fun MarketFindingsCard(
    workshopId: String,
    repository: WorkshopRepository,
    rows: Map<String, List<DwDataRow>>,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    /**
     * Stage 8's rows, which live in a DIFFERENT stage's draft and therefore have to be fetched.
     *
     * THE DEVICE IS ASKED FIRST AND THE SERVER ONLY AFTER, which is the opposite of how a screen that
     * assumed a connection would be written, and it is the whole point: the survey was captured on
     * this handset, into this filesDir, hours ago. A panel that waited for a request would be blank
     * in exactly the courtyard the analysis was written for. The server read exists only for a
     * workshop whose stage 8 was entered on the web and never opened here, it is wrapped so a
     * failure costs nothing, and its absence is SAID rather than silently rendered as "no evidence".
     */
    var survey by remember(workshopId) { mutableStateOf<SurveyRows?>(null) }
    var searched by remember(workshopId) { mutableStateOf(false) }

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
            }
        }
        searched = true
    }

    if (!searched) return
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
        // Nothing typed and nothing captured. A card reading "0 observations" beside an empty form is
        // noise on a screen a designer is trying to fill in.
        return
    }

    FindingsCard(
        title = "What the survey says",
        subtitle = "Computed on this device from the ${rowsFound.responses.size} survey " +
            "response(s) and ${rowsFound.competitors.size} competitor product(s) in stage 8. " +
            "Nothing here is written into this stage.",
    ) {
        // CAUTIONS BEFORE FIGURES, ALWAYS. The server orders its payload the same way for the same
        // reason: a caution that has to be scrolled to is a caution that will not be read, and
        // "these prices all come from one respondent group" changes what every number below it means.
        findings.cautions.forEach { Advisory(it, MaterialTheme.field.warningContainer, MaterialTheme.field.onWarningContainer) }

        if (rowsFound.isEmpty()) {
            Advisory(
                "Stage 8 has not been captured on this device and could not be downloaded, so there " +
                    "is nothing to check the bands and SWOT below against. Opening stage 8 once " +
                    "with a connection is enough.",
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
                        "Evidence is cited on the point itself, so it is not second-guessed here."
                    point.supportedBy.isNotEmpty() ->
                        "Shares ${point.overlap} content word(s) with what " +
                            point.supportedBy.joinToString(", ") + " said. Worth reading beside it — " +
                            "a shared vocabulary is a place to look, not a proof."
                    else ->
                        "No response in the survey shares two content words with this point, and no " +
                            "evidence is cited on it. It may still be true; it is currently an " +
                            "assertion rather than a finding."
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
                    cluster.members.joinToString(", ") + " — ${cluster.mentions} mention(s), " +
                        "${cluster.coOccurrences} in the same conversation.",
                    MaterialTheme.field.surface100, MaterialTheme.field.body,
                )
            }
        }
    }
}

/**
 * How many of these cost sheets have no SERVER id yet — the number that decides whether an orphan
 * caution can be true.
 *
 * A FUNCTION RATHER THAN A LINE INSIDE THE COMPOSABLE so it can be tested. The rule is one sentence
 * and the first spelling of it was wrong: it must COUNT, not ask `none { … }`. A screen with three
 * synced sheets and one entered this morning makes `none` false, and the panel then printed the
 * port's orphan caution word for word — "the sheet they named may have been deleted after they were
 * recorded" — about a sheet three rows further up the same screen. Compose is not unit-testable here;
 * this predicate is, and it is where the judgement lives.
 */
internal fun dwUnsyncedSheetCount(sheets: List<DwDataRow>): Int = sheets.count {
    // `_entryId` is the server's id for the row and is absent until the workshop syncs. A row whose
    // id is present but EMPTY counts as unsynced for the same reason `DwCostIntegrity` refuses to
    // match on it: an empty string is not an identity, and joining two rows on it attributes a week
    // of costing at random.
    (it["_entryId"] as? JsonPrimitive)?.content?.isNotEmpty() != true
}

/** Stage 8's two collections, held together so "did we find anything at all" is one question. */
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
    // THE SAMPLE IS PART OF THE FIGURE. A median with no count beside it is a number a designer can
    // carry into a report without knowing it came from three people.
    val quartiles = if (dist.quantilesReported) {
        " · quartiles ${money(dist.p25)}–${money(dist.p75)}"
    } else {
        " · too few for quartiles"
    }
    Advisory(
        "$label: ${dist.count} observation(s), ${money(dist.minimum)}–${money(dist.maximum)}, " +
            "median ${money(dist.median)}$quartiles",
        MaterialTheme.field.surface100,
        MaterialTheme.field.body,
    )
}

// --------------------------------------------------------------------------------------
// Stage 17 — a cost sheet against its own line items
// --------------------------------------------------------------------------------------

@Composable
private fun CostFindingsCard(workshopId: String, rows: Map<String, List<DwDataRow>>) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    val sheets = rows["costSheet"].orEmpty()
    val materialLines = rows["costMaterialLine"].orEmpty()
    val labourLines = rows["costLabourLine"].orEmpty()
    if (sheets.isEmpty() && materialLines.isEmpty() && labourLines.isEmpty()) return

    /**
     * `productRef` resolved to a product NAME, from stage 16's own rows on this device first.
     *
     * The reference CACHE is asked only second. It is populated by a picker that has been opened with
     * a connection, which is not a thing a courtyard can guarantee, whereas stage 16's rows are in
     * the same draft file as stage 17's. Getting this wrong costs nothing but a readable name —
     * `Cost sheet 3` is the same answer `report_builder._row_label` gives — so it is never allowed to
     * fail the panel.
     */
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

    // A SHEET WITH NO SERVER ID YET IS NOT A DATA-ENTRY ERROR and must not be reported as one.
    // `costSheetRef` holds the sheet's `_entryId`, which is the SERVER's id, and the picker that
    // fills that field is served from the references endpoint — so a sheet created in a courtyard
    // cannot be offered to its own lines at all. The designer leaves the link blank because there is
    // nothing to pick, the lines land in the orphan bucket, and the sheet reads "un-itemised".
    //
    // COUNTED RATHER THAN ALL-OR-NOTHING, and that is the fix rather than a refinement. This read
    // `sheets.none { … }`, so a workshop with three synced sheets and one entered this morning fell
    // through to the else arm and printed the port's orphan caution verbatim: "the sheet they named
    // may have been deleted after they were recorded." Nothing was deleted and nothing was named —
    // the sheet is on this screen, three rows up, waiting for a tower. Telling a designer their
    // morning's costing has been deleted, in the one situation this app is built for, is the exact
    // false accusation `cost_integrity`'s docstring forbids, and a designer who is told it once
    // stops reading the amber for the rest of the workshop.
    //
    // The port's own `cautions` are NOT edited to say this — they are the server's sentences and
    // stay byte-identical. This decides which of them a mixed-sync screen is entitled to show.
    val unsyncedSheets = dwUnsyncedSheetCount(sheets)
    val anyUnsynced = unsyncedSheets > 0

    FindingsCard(
        title = "Cost sheets against their own lines",
        subtitle = "Computed on this device. ${findings.sheetCount} sheet(s), " +
            "${materialLines.size} material line(s), ${labourLines.size} labour line(s). " +
            "Nothing here is written into this stage — a subtotal you typed stays as you typed it.",
    ) {
        if (anyUnsynced) {
            Advisory(
                "$unsyncedSheets of ${sheets.size} cost sheet(s) have not reached the server yet, " +
                    "so lines entered against them cannot name their sheet and are in no subtotal " +
                    "below. Nothing is lost and nothing needs re-entering — the sheets are on this " +
                    "screen and the check completes itself once the workshop has synced.",
                MaterialTheme.field.surface100, MaterialTheme.field.body,
            )
        }

        // WARNINGS ALWAYS, CAUTIONS ONLY WHEN THEY CAN BE TRUE. A warning is a sheet contradicting
        // its own lines, which is a fact about figures on this screen and is not affected by whether
        // anything has synced. Every caution this module produces is an ORPHAN caution, and every
        // orphan caution offers a deleted sheet as the explanation — which is the wrong explanation,
        // and an alarming one, the moment a sheet on this very screen is simply waiting for a tower.
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

// --------------------------------------------------------------------------------------
// The shared shell
// --------------------------------------------------------------------------------------

/**
 * A collapsible card at the foot of a stage.
 *
 * Expanded by default and collapsible rather than the other way round: a finding behind a closed
 * disclosure is a finding nobody reads, and these two panels exist because the findings were
 * previously a fortnight and several hundred kilometres away. Collapsible, because it sits under a
 * form the designer is still filling in and must be possible to get out of the way.
 */
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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Hide these findings" else "Show these findings",
                    tint = MaterialTheme.field.muted,
                )
            }
            Text(subtitle, color = MaterialTheme.field.muted, fontSize = 11.sp)
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
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun Advisory(text: String, container: Color, ink: Color) {
    Text(
        text,
        color = ink,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/**
 * One verdict, with the token that produced it shown rather than translated away.
 *
 * UNVERIFIABLE, NOT_ITEMISED and NOT_COMPUTABLE are printed as they are, and are drawn in the NEUTRAL
 * colour rather than the warning one, because they are refusals and not criticisms. Colouring "too
 * few observations to judge this band" the same amber as "this subtotal contradicts its lines" is how
 * a designer learns that the amber means nothing.
 */
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
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(body, color = ink, fontSize = 12.sp)
    }
}
