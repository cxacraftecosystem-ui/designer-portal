package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.userMessage

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.designprototype.workshop.data.DwCardHit
import com.designprototype.workshop.data.DwCardRender
import com.designprototype.workshop.data.DwCardSource
import com.designprototype.workshop.data.DwEncodeResult
import com.designprototype.workshop.data.DwQrEccLevel
import com.designprototype.workshop.data.DwQrEncode
import com.designprototype.workshop.data.DwQrSymbol
import com.designprototype.workshop.data.DwWorkshopCodeRef
import com.designprototype.workshop.data.DwWorkshopRecordType
import com.designprototype.workshop.data.DwWorkshopScan
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageBucketDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.designWorkshopCardPurposeMessage
import com.designprototype.workshop.data.designWorkshopJoinAskingMessage
import com.designprototype.workshop.data.designWorkshopJoinCardPurposeMessage
import com.designprototype.workshop.data.designWorkshopJoinCardRedeemingMessage
import com.designprototype.workshop.data.encodeWorkshopCode
import com.designprototype.workshop.data.formatWorkshopCodeForPrint
import com.designprototype.workshop.data.findWorkshopCodeInDraft
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.data.readWorkshopScan
import com.designprototype.workshop.data.renderWorkshopCard
import com.designprototype.workshop.data.ArtisanDto
import com.designprototype.workshop.loadArtisanRegister
import com.designprototype.workshop.data.unresolvedWorkshopCodeMessage
import com.designprototype.workshop.data.workshopCardRows
import com.designprototype.workshop.data.workshopCardSource
import com.designprototype.workshop.data.workshopCardSpecs
import com.designprototype.workshop.data.workshopRecordTypeLabel
import com.designprototype.workshop.report.renderCardSheetPdf
import com.designprototype.workshop.ui.DwInductionFlusher
import com.designprototype.workshop.ui.DwJoinCardAction
import com.designprototype.workshop.ui.DwJoinCardDto
import com.designprototype.workshop.ui.DwJoinOutcome
import com.designprototype.workshop.ui.DwQrLiveScanControl
import com.designprototype.workshop.ui.DwQrScanControl
import com.designprototype.workshop.ui.dwJoinDesignWorkshop
import com.designprototype.workshop.ui.dwListJoinCards
import com.designprototype.workshop.ui.dwMintJoinCard
import com.designprototype.workshop.ui.dwRevokeJoinCard
import com.designprototype.workshop.ui.dwScanJoinCard
import com.designprototype.workshop.ui.DwQrSymbolImage
import com.designprototype.workshop.ui.RecordCodeOutcome
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.lookUpRecordCode
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

@Composable
fun WorkshopCodesScreen(
    repository: WorkshopRepository,
    workshopId: String,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    var schema by remember(workshopId) { mutableStateOf<SchemaResponse?>(null) }
    var draft by remember(workshopId) { mutableStateOf<WorkshopDraft?>(null) }
    var remoteStages by remember(workshopId) { mutableStateOf<Map<String, StageBucketDto>>(emptyMap()) }
    var loading by remember(workshopId) { mutableStateOf(true) }
    var deviceOnlyNote by remember(workshopId) { mutableStateOf<String?>(null) }
    var kind by remember(workshopId) { mutableStateOf(DwWorkshopRecordType.PROTOTYPE) }
    var exporting by remember(workshopId) { mutableStateOf(false) }
    var exported by remember(workshopId) { mutableStateOf<SheetExport?>(null) }
    var artisanRegister by remember { mutableStateOf<Map<String, ArtisanDto>>(emptyMap()) }
    var artisanCardsOn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        artisanCardsOn = runCatching { repository.artisanCardsEnabled(appContext) }.getOrDefault(false)
        runCatching {
            loadArtisanRegister(appContext, repository) { list -> artisanRegister = list.associateBy { it.id } }
        }
    }

    LaunchedEffect(workshopId) {
        loading = true
        runCatching {
            val loadedSchema = repository.designWorkshopSchema(appContext)
            val local = WorkshopDraftStore.load(appContext, workshopId)
            val remoteId = local?.remoteId ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
            val remote = remoteId?.let { runCatching { repository.designWorkshopStages(it) }.getOrNull() }
            val note = when {
                remoteId == null ->
                    "Not uploaded — showing items on this phone. Printing still works."
                remote == null ->
                    "Offline — showing items on this phone. Printing still works."
                else -> null
            }
            Triple(loadedSchema, local, remote?.stages.orEmpty()) to note
        }.onSuccess { (loaded, note) ->
            schema = loaded.first
            draft = loaded.second
            remoteStages = loaded.third
            deviceOnlyNote = note
        }.onFailure {
            onError(it.userMessage("Couldn't load prototypes and roster."))
        }
        loading = false
    }

    val source: DwCardSource? = remember(schema, kind) { schema?.let { workshopCardSource(kind, it) } }

    val cards: List<DwCardRender> = remember(source, draft, remoteStages, kind, artisanRegister, artisanCardsOn) {
        val current = source ?: return@remember emptyList()
        val rows = workshopCardRows(current, draft, remoteStages)
        workshopCardSpecs(kind, current, rows, artisanRegister, artisanCardsOn).map { renderWorkshopCard(it) }
    }

    suspend fun joinDesignWorkshop(targetWorkshopId: String): DwLookupOutcome {
        val canonical = (
            encodeWorkshopCode(DwWorkshopRecordType.DESIGN_WORKSHOP, targetWorkshopId) as? DwEncodeResult.Ok
            )?.code
        return when (val joined = dwJoinDesignWorkshop(appContext, targetWorkshopId, canonical.orEmpty())) {
            is DwJoinOutcome.Asked -> DwLookupOutcome.Noted(joined.detail)
            is DwJoinOutcome.Queued -> DwLookupOutcome.Noted(joined.message)
            is DwJoinOutcome.Refused -> DwLookupOutcome.Refused(joined.message)
            is DwJoinOutcome.Inducted -> DwLookupOutcome.Noted(joined.message)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Cards & tags", display = true, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Print codes for artisans and prototypes, and scan one to look it up.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 17.sp
        )

        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Reading this workshop…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }

        deviceOnlyNote?.let { DwWorkshopNotice(it) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DwCardKindButton("Prototype tags", kind == DwWorkshopRecordType.PROTOTYPE, Modifier.weight(1f)) {
                kind = DwWorkshopRecordType.PROTOTYPE
                exported = null
            }
            DwCardKindButton("Artisan cards", kind == DwWorkshopRecordType.ARTISAN, Modifier.weight(1f)) {
                kind = DwWorkshopRecordType.ARTISAN
                exported = null
            }
            DwCardKindButton(
                "Workshop code",
                kind == DwWorkshopRecordType.DESIGN_WORKSHOP,
                Modifier.weight(1f),
            ) {
                kind = DwWorkshopRecordType.DESIGN_WORKSHOP
                exported = null
            }
        }

        DwCodeLookupPanel(
            resolve = { ref ->
                val currentSchema = schema
                val local = currentSchema?.let { findWorkshopCodeInDraft(ref, it, draft, remoteStages) }
                when {
                    ref.recordType == DwWorkshopRecordType.DESIGN_WORKSHOP -> joinDesignWorkshop(ref.id)

                    currentSchema == null -> DwLookupOutcome.Refused(
                        "Valid code, but this workshop isn't loaded here. Retry online."
                    )

                    local != null -> DwLookupOutcome.Found(local)
                    ref.recordType == DwWorkshopRecordType.PROTOTYPE ->
                        DwLookupOutcome.Refused(unresolvedWorkshopCodeMessage(ref.recordType))
                    ref.recordType == DwWorkshopRecordType.ARTISAN -> lookUpArtisan(repository, ref.id)
                    else -> when (val answer = lookUpRecordCode(repository, ref)) {
                        is RecordCodeOutcome.Found ->
                            DwLookupOutcome.Found(DwCardHit(answer.hit.label, answer.hit.detail.orEmpty()))

                        is RecordCodeOutcome.Refused -> DwLookupOutcome.Refused(answer.message)

                        is RecordCodeOutcome.Join -> joinDesignWorkshop(answer.workshopId)
                    }
                }
            }
        )

        HorizontalDivider()

        if (kind == DwWorkshopRecordType.DESIGN_WORKSHOP) {
            val serverId = draft?.remoteId ?: workshopId
            DwDesignWorkshopCodeCard(serverId)
            DwJoinCardsPanel(serverId)
            Spacer(Modifier.padding(bottom = 8.dp))
            return@Column
        }

        if (source == null) {
            DwWorkshopNotice(
                (if (kind == DwWorkshopRecordType.PROTOTYPE) "Prototype tags" else "Artisan cards") +
                    " need an app update to print."
            )
            return@Column
        }

        if (cards.isEmpty()) {
            Text(
                if (kind == DwWorkshopRecordType.PROTOTYPE) {
                    "No prototypes yet. Add them at the prototype stage."
                } else {
                    "No artisans yet. Enrol them on the participants stage."
                },
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 19.sp
            )
            return@Column
        }

        val refusals = cards.count { it is DwCardRender.Refused }
        Text(
            buildString {
                append("${cards.size} ${if (kind == DwWorkshopRecordType.PROTOTYPE) "tag" else "card"}")
                if (cards.size != 1) append("s")
                if (refusals > 0) append(" · $refusals cannot be printed yet")
            },
            color = if (refusals > 0) MaterialTheme.field.warning else MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        Button(
            onClick = {
                exporting = true
                exported = null
                scope.launch {
                    val setup = draft?.stages?.get("WORKSHOP_SETUP")?.values.orEmpty()
                    fun setupText(key: String) = DwValues.text(setup[key]).trim().ifBlank { null }
                    val sheetName = com.designprototype.workshop.data.Naming.workshopFilename(
                        workshopCode = setupText("workshopCode"),
                        title = draft?.title,
                        clusterName = setupText("clusterName"),
                        district = setupText("district"),
                        state = setupText("state"),
                        craftName = setupText("craftName"),
                        what = "${workshopRecordTypeLabel(kind)} Cards",
                        ext = "pdf",
                    )
                    runCatching { writeSheet(context, repository, kind, cards, sheetName) }
                        .onSuccess {
                            exported = it
                            onMessage("Saved ${it.fileName}")
                        }
                        .onFailure { onError(it.userMessage("The sheet could not be written.")) }
                    exporting = false
                }
            },
            enabled = !exporting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (kind == DwWorkshopRecordType.PROTOTYPE) "Save a sheet of tags (.pdf)" else "Save a sheet of cards (.pdf)")
        }

        if (exporting) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Drawing the sheet on this device…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        exported?.let { sheet -> DwSheetSavedCard(sheet) }

        Text(
            "Print at 100%, not “fit to page”.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp
        )

        cards.forEach { card -> DwCardPreview(card) }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun DwCardPreview(card: DwCardRender) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            when (card) {
                is DwCardRender.Ok -> DwQrSymbolImage(
                    symbol = card.symbol,
                    label = "Code for ${card.spec.title}",
                    modifier = Modifier.size(96.dp)
                )

                is DwCardRender.Refused -> Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(MaterialTheme.field.surface200, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.QrCode2,
                        contentDescription = null,
                        tint = MaterialTheme.field.placeholder,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    card.spec.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                card.spec.lines.take(2).forEach { line ->
                    Text(line, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                }
                when (card) {
                    is DwCardRender.Ok -> Text(
                        card.printed,
                        color = MaterialTheme.field.body,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    is DwCardRender.Refused -> Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.field.warningContainer, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Filled.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.field.onWarningContainer,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            card.message,
                            color = MaterialTheme.field.onWarningContainer,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

sealed interface DwLookupOutcome {
    data class Found(val hit: DwCardHit) : DwLookupOutcome
    data class Refused(val message: String) : DwLookupOutcome

    data class Noted(val message: String, val detail: String? = null) : DwLookupOutcome
}

@Composable
private fun DwCodeLookupPanel(resolve: suspend (DwWorkshopCodeRef) -> DwLookupOutcome) {
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Pair<DwPanelTone, String>?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { DwInductionFlusher.flushNow(appContext) }

    fun lookUp(input: String) {
        if (input.isBlank() || busy) return
        busy = true
        detail = null
        scope.launch {
            when (val scan = readWorkshopScan(input)) {
                is DwWorkshopScan.Refused -> outcome = DwPanelTone.BAD to scan.message
                is DwWorkshopScan.JoinCard -> {
                    typed = ""
                    outcome = DwPanelTone.NOTED to designWorkshopJoinCardRedeemingMessage()
                    when (val answer = dwScanJoinCard(appContext, scan.card.workshopId, scan.card.code)) {
                        is DwJoinOutcome.Inducted -> outcome =
                            (if (answer.fullMember) DwPanelTone.GOOD else DwPanelTone.NOTED) to answer.message
                        is DwJoinOutcome.Queued -> outcome = DwPanelTone.NOTED to answer.message
                        is DwJoinOutcome.Refused -> outcome = DwPanelTone.BAD to answer.message
                        is DwJoinOutcome.Asked -> outcome = DwPanelTone.NOTED to answer.detail
                    }
                    busy = false
                    return@launch
                }
                is DwWorkshopScan.RecordCode -> {
                    if (scan.ref.recordType == DwWorkshopRecordType.DESIGN_WORKSHOP) {
                        outcome = DwPanelTone.NOTED to designWorkshopJoinAskingMessage()
                    }
                    when (val answer = resolve(scan.ref)) {
                        is DwLookupOutcome.Found -> {
                            outcome = DwPanelTone.GOOD to "Found: ${answer.hit.label}"
                            detail = answer.hit.detail
                        }

                        is DwLookupOutcome.Refused -> outcome = DwPanelTone.BAD to answer.message

                        is DwLookupOutcome.Noted -> {
                            outcome = DwPanelTone.NOTED to answer.message
                            detail = answer.detail
                        }
                    }
                }
            }
            busy = false
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.field.surface50),
        border = BorderStroke(1.dp, MaterialTheme.field.hairline),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Filled.QrCode2,
                    contentDescription = null,
                    tint = MaterialTheme.field.muted,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "Open a record from its code",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            DwQrLiveScanControl(
                enabled = !busy,
                onText = { text ->
                    typed = text
                    outcome = null
                    detail = null
                    lookUp(text)
                },
                onRefusal = { message ->
                    detail = null
                    outcome = DwPanelTone.BAD to message
                },
            )
            DwQrScanControl(
                enabled = !busy,
                onText = { text ->
                    typed = text
                    outcome = null
                    detail = null
                    lookUp(text)
                },
                onRefusal = { message ->
                    detail = null
                    outcome = DwPanelTone.BAD to message
                },
            )
            OutlinedTextField(
                value = typed,
                onValueChange = {
                    typed = it
                    outcome = null
                    detail = null
                },
                label = { Text("Workshop code") },
                placeholder = { Text("DPW1 :A: …") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { lookUp(typed) },
                    enabled = typed.isNotBlank() && !busy,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Look up")
                }
                if (busy) FieldCircularProgressIndicator(modifier = Modifier.size(16.dp))
            }
            Text(
                "Spaces and capitals don't matter. The last four characters are a check.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp
            )

            outcome?.let { (tone, message) ->
                val container = when (tone) {
                    DwPanelTone.GOOD -> MaterialTheme.field.successContainer
                    DwPanelTone.NOTED -> MaterialTheme.field.surface200
                    DwPanelTone.BAD -> MaterialTheme.field.warningContainer
                }
                val ink = when (tone) {
                    DwPanelTone.GOOD -> MaterialTheme.field.onSuccessContainer
                    DwPanelTone.NOTED -> MaterialTheme.field.body
                    DwPanelTone.BAD -> MaterialTheme.field.onWarningContainer
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .background(container, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(message, color = ink, style = MaterialTheme.typography.bodyMedium, lineHeight = 18.sp)
                    detail?.let { Text(it, color = ink, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

private suspend fun lookUpArtisan(repository: WorkshopRepository, id: String): DwLookupOutcome =
    try {
        val artisan = repository.artisan(id)
        DwLookupOutcome.Found(
            DwCardHit(
                label = artisan.name,
                detail = (artisan.place.ifBlank { "Recorded artisan" }) + " · not on this workshop's roster"
            )
        )
    } catch (e: HttpException) {
        DwLookupOutcome.Refused(unresolvedWorkshopCodeMessage(DwWorkshopRecordType.ARTISAN))
    } catch (e: Exception) {
        DwLookupOutcome.Refused(
            "Not on this roster here, and you're offline. Retry online."
        )
    }

private data class SheetExport(
    val displayLocation: String,
    val shareUri: android.net.Uri?,
    val fileName: String,
    val pages: Int,
)

private suspend fun writeSheet(
    context: android.content.Context,
    repository: WorkshopRepository,
    kind: DwWorkshopRecordType,
    cards: List<DwCardRender>,
    name: String,
): SheetExport = withContext(Dispatchers.IO) {
    val tmp = File(context.cacheDir, name)
    try {
        val pages = FileOutputStream(tmp).use { fos ->
            val buffered = BufferedOutputStream(fos)
            val written = renderCardSheetPdf(kind, cards, buffered)
            buffered.flush()
            fos.fd.sync()
            written
        }
        val location = repository.persistFileToDownloads(context, tmp, name, "application/pdf")
        SheetExport(
            displayLocation = location,
            shareUri = repository.shareUriForSavedFile(context, location),
            fileName = name,
            pages = pages,
        )
    } finally {
        tmp.delete()
    }
}

@Composable
private fun DwSheetSavedCard(sheet: SheetExport) {
    val context = LocalContext.current
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text("Saved", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        Text(sheet.displayLocation, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)
        Text(
            "${sheet.pages} A4 page${if (sheet.pages == 1) "" else "s"}",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
        if (sheet.shareUri != null) {
            OutlinedButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, sheet.shareUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Share the sheet"))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share")
            }
        } else {
            Text(
                "Find it in Downloads.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun DwCardKindButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val marked = modifier
        .heightIn(min = 48.dp)
        .semantics { this.selected = selected }
    if (selected) {
        Button(onClick = onClick, modifier = marked) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = marked) { Text(label) }
    }
}

@Composable
internal fun DwWorkshopNotice(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.warningContainer, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.field.onWarningContainer,
            modifier = Modifier.size(16.dp)
        )
        Text(
            message,
            color = MaterialTheme.field.onWarningContainer,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 17.sp
        )
    }
}

private enum class DwPanelTone { GOOD, NOTED, BAD }

@Composable
private fun DwDesignWorkshopCodeCard(workshopId: String) {
    val render = remember(workshopId) {
        when (val encoded = encodeWorkshopCode(DwWorkshopRecordType.DESIGN_WORKSHOP, workshopId)) {
            is DwEncodeResult.Refused -> null to encoded.message
            is DwEncodeResult.Ok -> runCatching {
                Triple(
                    DwQrEncode.encode(encoded.code, DwQrEccLevel.Q),
                    encoded.code,
                    formatWorkshopCodeForPrint(encoded.code),
                )
            }.fold(
                onSuccess = { it to null },
                onFailure = { null to (it.userMessage("This code could not be drawn.")) },
            )
        }
    }
    val symbol: Triple<DwQrSymbol, String, String>? = render.first
    val refusal: String? = render.second

    if (refusal != null) {
        DwWorkshopNotice(refusal)
        return
    }
    val drawn = symbol ?: return

    DwHoldScreenBright()

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DwQrSymbolImage(
                symbol = drawn.first,
                label = "Code for this design workshop",
                modifier = Modifier.size(220.dp),
            )
            Text(
                drawn.third,
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )
            Text(
                designWorkshopCardPurposeMessage(),
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
            Text(
                "Brightness is at full so another phone can scan. Or type the code shown above.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun DwJoinCardsPanel(workshopId: String) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    var minted by remember(workshopId) { mutableStateOf<DwJoinCardDto?>(null) }
    var cards by remember(workshopId) { mutableStateOf<List<DwJoinCardDto>>(emptyList()) }
    var truncated by remember(workshopId) { mutableStateOf(false) }
    var listed by remember(workshopId) { mutableStateOf(false) }
    var busy by remember(workshopId) { mutableStateOf(false) }
    var notice by remember(workshopId) { mutableStateOf<String?>(null) }

    if (isLocalOnlyWorkshop(workshopId)) {
        DwWorkshopNotice(
            "Sync this workshop before printing a join card."
        )
        return
    }

    fun perform(action: suspend () -> DwJoinCardAction) {
        if (busy) return
        busy = true
        notice = null
        scope.launch {
            when (val answer = action()) {
                is DwJoinCardAction.Minted -> {
                    minted = answer.card
                    listed = false
                }
                is DwJoinCardAction.Listed -> {
                    cards = answer.cards
                    truncated = answer.truncated
                    listed = true
                }
                is DwJoinCardAction.Revoked -> {
                    if (minted?.id == answer.card.id) minted = null
                    listed = false
                }
                is DwJoinCardAction.Refused -> notice = answer.message
                is DwJoinCardAction.Offline -> notice = answer.message
            }
            busy = false
        }
    }

    LaunchedEffect(workshopId, listed) {
        if (!listed) perform { dwListJoinCards(context, workshopId) }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.field.surface50),
        border = BorderStroke(1.dp, MaterialTheme.field.hairline),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Filled.QrCode2,
                    contentDescription = null,
                    tint = MaterialTheme.field.muted,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "Join cards",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                "A join card lets one person onto this workshop instantly. Single use.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { perform { dwMintJoinCard(context, workshopId) } },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Print a join card")
                }
                if (busy) FieldCircularProgressIndicator(modifier = Modifier.size(16.dp))
            }

            notice?.let {
                Text(
                    it,
                    color = MaterialTheme.field.onWarningContainer,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .background(MaterialTheme.field.warningContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }

            minted?.let { card -> DwFreshJoinCard(card) }

            if (cards.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    "Cards printed for this workshop",
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                if (truncated) {
                    DwWorkshopNotice(
                        "Some cards aren't shown. Ask an admin to cancel one you can't see."
                    )
                }
                cards.forEach { card ->
                    DwJoinCardRow(
                        card = card,
                        busy = busy,
                        onRevoke = { perform { dwRevokeJoinCard(context, card.id) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun DwFreshJoinCard(card: DwJoinCardDto) {
    val code = card.code
    if (code.isNullOrBlank()) return

    val render = remember(code) {
        runCatching {
            DwQrEncode.encode(code, DwQrEccLevel.Q) to formatWorkshopCodeForPrint(code)
        }.getOrNull()
    }
    if (render == null) {
        DwWorkshopNotice(
            "Couldn't draw the QR. Type the characters below instead."
        )
    }

    DwHoldScreenBright()

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            render?.first?.let { symbol ->
                DwQrSymbolImage(
                    symbol = symbol,
                    label = "Join card for this design workshop",
                    modifier = Modifier.size(220.dp),
                )
            }
            Text(
                render?.second ?: formatWorkshopCodeForPrint(code),
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )
            Text(
                designWorkshopJoinCardPurposeMessage(),
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
            Text(
                "Shown only once. Print, photograph or write it down now — if lost, cancel it and print another.",
                color = MaterialTheme.field.onWarningContainer,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.warningContainer, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun DwJoinCardRow(card: DwJoinCardDto, busy: Boolean, onRevoke: () -> Unit) {
    val state = dwJoinCardState(card, System.currentTimeMillis())
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface200, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "…${card.secretLast4}",
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                state,
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (card.revokedAt == null) {
            OutlinedButton(
                onClick = onRevoke,
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Cancel")
            }
        }
    }
}

internal fun dwJoinCardState(card: DwJoinCardDto, nowMs: Long): String {
    val issuer = card.issuedBy?.name?.takeIf { it.isNotBlank() }
    val by = if (issuer == null) "" else " Printed by $issuer."
    val label = card.label?.takeIf { it.isNotBlank() }?.let { " $it." } ?: ""
    if (card.revokedAt != null) {
        return "Cancelled. Anyone it already admitted is still on the workshop (remove them on the viewers screen).$by$label"
    }
    val ceiling = card.maxUses
    if (ceiling != null && card.usesConsumed >= ceiling) {
        return "Used up — it won't admit anyone else.$by$label"
    }
    val expiresAtMs = card.expiresAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
    if (expiresAtMs != null && expiresAtMs <= nowMs) {
        return "Out of date. New scans won't get in directly, but can still reach an administrator.$by$label"
    }
    val remaining = if (ceiling == null) "any number of people" else "${ceiling - card.usesConsumed} more person"
    return "Still good for $remaining.$by$label"
}

@Composable
private fun DwHoldScreenBright() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.dwCodeHostActivity()?.window
        runCatching {
            window?.attributes = window?.attributes?.apply { screenBrightness = 1f }
        }
        onDispose {
            runCatching {
                window?.attributes = window?.attributes?.apply { screenBrightness = -1f }
            }
        }
    }
}

private fun android.content.Context.dwCodeHostActivity(): android.app.Activity? {
    var cursor: android.content.Context? = this
    while (cursor is android.content.ContextWrapper) {
        if (cursor is android.app.Activity) return cursor
        cursor = cursor.baseContext
    }
    return null
}
