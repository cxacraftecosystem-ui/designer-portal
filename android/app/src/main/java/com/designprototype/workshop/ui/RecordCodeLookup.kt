package com.designprototype.workshop.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.designprototype.workshop.data.ArtisanCardCode
import com.designprototype.workshop.data.CARD_REPLACED
import com.designprototype.workshop.data.CardResolution
import com.designprototype.workshop.data.DwWorkshopCodeRef
import com.designprototype.workshop.data.DwWorkshopRecordType
import com.designprototype.workshop.data.DwWorkshopScan
import com.designprototype.workshop.data.OfflineOutbox
import com.designprototype.workshop.data.ScanAction
import com.designprototype.workshop.data.ScanCardAnswer
import com.designprototype.workshop.data.ScanCardKind
import com.designprototype.workshop.data.ScanCodecs
import com.designprototype.workshop.data.ScanCopy
import com.designprototype.workshop.data.ScanDecoded
import com.designprototype.workshop.data.ScanOutcome
import com.designprototype.workshop.data.ScanOutcomeKind
import com.designprototype.workshop.data.ScanPortal
import com.designprototype.workshop.data.ScanRecordAnswer
import com.designprototype.workshop.data.ScanServices
import com.designprototype.workshop.data.ScanTarget
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.cardWaitingToSync
import com.designprototype.workshop.data.dispatchScan
import com.designprototype.workshop.data.readWorkshopScan
import com.designprototype.workshop.data.unresolvedWorkshopCodeMessage
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

data class RecordCodeHit(val label: String, val detail: String? = null)

sealed interface RecordCodeOutcome {
    data class Found(val ref: DwWorkshopCodeRef, val hit: RecordCodeHit) : RecordCodeOutcome

    data class Refused(val message: String) : RecordCodeOutcome

    data class Join(val workshopId: String) : RecordCodeOutcome
}

private fun line(vararg parts: String?): String? =
    parts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

suspend fun lookUpRecordCode(repository: WorkshopRepository, ref: DwWorkshopCodeRef): RecordCodeOutcome {
    if (ref.recordType == DwWorkshopRecordType.PROTOTYPE) {
        return RecordCodeOutcome.Refused(
            "That's a prototype tag. Look it up from its workshop's Cards & tags."
        )
    }
    if (ref.recordType == DwWorkshopRecordType.DESIGN_WORKSHOP) return RecordCodeOutcome.Join(ref.id)
    return try {
        val hit = fetchRecordCodeHit(repository, ref)
        RecordCodeOutcome.Found(ref, hit)
    } catch (e: HttpException) {
        RecordCodeOutcome.Refused(unresolvedWorkshopCodeMessage(ref.recordType))
    } catch (e: Exception) {
        RecordCodeOutcome.Refused(
            "Offline — couldn't look up the code. It's valid; retry online."
        )
    }
}


suspend fun fetchRecordCodeHit(repository: WorkshopRepository, ref: DwWorkshopCodeRef): RecordCodeHit =
    when (ref.recordType) {
        DwWorkshopRecordType.ARTISAN -> repository.artisan(ref.id).let { RecordCodeHit(it.name, line(it.place)) }
        DwWorkshopRecordType.CRAFT -> repository.craft(ref.id).let { RecordCodeHit(it.name, line(it.category, it.place)) }
        DwWorkshopRecordType.WORKSHOP -> repository.workshop(ref.id).let { RecordCodeHit(it.title, line(it.place)) }
        DwWorkshopRecordType.PRODUCT ->
            repository.product(ref.id).let { RecordCodeHit(it.productName, line(it.craftName, it.artisanName, it.place)) }
        DwWorkshopRecordType.PROCESS -> repository.process(ref.id).let { RecordCodeHit(it.name, line(it.notes)) }
        DwWorkshopRecordType.TOOL ->
            repository.tool(ref.id).let { RecordCodeHit(it.toolkitName, line(it.craftName, it.artisanName, it.place)) }
        DwWorkshopRecordType.QUESTIONNAIRE ->
            repository.interview(ref.id).let { RecordCodeHit(it.title, line(it.place, it.language)) }
        DwWorkshopRecordType.MEDIA ->
            repository.mediaItem(ref.id).let {
                RecordCodeHit(it.caption?.trim()?.ifEmpty { null } ?: it.originalFilename, line(it.mediaType, it.mimeType))
            }
        DwWorkshopRecordType.DESIGN_WORKSHOP ->
            repository.designWorkshop(ref.id).let {
                RecordCodeHit(it.title.ifBlank { "Design workshop" }, line(it.craftName, it.district))
            }
        DwWorkshopRecordType.PROTOTYPE -> throw IllegalArgumentException("A prototype is looked up inside its workshop.")
    }

object DesignerScanCodecs : ScanCodecs {
    override fun decodeJoinCard(raw: String): ScanDecoded.JoinCard = when (val scan = readWorkshopScan(raw)) {
        is DwWorkshopScan.JoinCard -> ScanDecoded.JoinCardOk(scan.card.workshopId, scan.card.code)
        is DwWorkshopScan.Refused -> ScanDecoded.Refused(scan.message)
        is DwWorkshopScan.RecordCode -> ScanDecoded.Refused(ScanCopy.UNKNOWN)
    }

    override fun decodeRecordCode(raw: String): ScanDecoded.RecordCode = when (val scan = readWorkshopScan(raw)) {
        is DwWorkshopScan.RecordCode -> ScanDecoded.RecordCodeOk(scan.ref.recordType.wire, scan.ref.id, scan.code)
        is DwWorkshopScan.Refused -> ScanDecoded.Refused(scan.message)
        is DwWorkshopScan.JoinCard -> ScanDecoded.Refused(ScanCopy.UNKNOWN)
    }
}

fun designerScanCardKind(raw: String): ScanCardKind = when {
    ArtisanCardCode.parse(raw) is ArtisanCardCode.Result.Ok -> ScanCardKind.VALID
    ArtisanCardCode.looksLikeCard(raw) -> ScanCardKind.MISTYPED
    else -> ScanCardKind.NONE
}

private suspend fun queuedPayloads(context: Context): List<String> =
    try {
        OfflineOutbox.all(context).map { it.payloadJson }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

private suspend fun localDrafts(context: Context): List<WorkshopDraft> =
    try {
        WorkshopDraftStore.list(context).mapNotNull { WorkshopDraftStore.load(context, it.workshopId) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

fun designerScanServices(context: Context, repository: WorkshopRepository): ScanServices = object : ScanServices {
    override val portal = ScanPortal.DESIGNER
    override val codecs: ScanCodecs = DesignerScanCodecs

    override fun cardKind(raw: String): ScanCardKind = designerScanCardKind(raw)

    override suspend fun resolveCard(raw: String): ScanCardAnswer =
        when (val answer = resolveScannedCard(context, repository, raw)) {
            is CardResolution.Found -> ScanCardAnswer.Found(answer.artisanId, answer.name, answer.detail)
            is CardResolution.Replaced ->
                if (answer.artisanId.isBlank()) ScanCardAnswer.Refused(CARD_REPLACED) else ScanCardAnswer.Replaced(answer.artisanId)
            CardResolution.Remote -> ScanCardAnswer.Peer
            is CardResolution.Pending ->
                if (cardWaitingToSync(answer.card, queuedPayloads(context), localDrafts(context))) {
                    ScanCardAnswer.Refused(ArtisanCardCode.pendingLabel(ArtisanCardCode.pendingValue(answer.card)))
                } else {
                    ScanCardAnswer.Offline
                }
            is CardResolution.Refused -> ScanCardAnswer.Refused(answer.message)
        }

    override suspend fun lookUpRecord(recordType: String, id: String, code: String): ScanRecordAnswer {
        val type = DwWorkshopRecordType.ofWire(recordType) ?: return ScanRecordAnswer.Missing()
        if (type == DwWorkshopRecordType.PROTOTYPE) return ScanRecordAnswer.Missing()
        return try {
            val hit = fetchRecordCodeHit(repository, DwWorkshopCodeRef(type, id))
            ScanRecordAnswer.Found(hit.label, hit.detail)
        } catch (e: CancellationException) {
            throw e
        } catch (_: HttpException) {
            val title = if (type == DwWorkshopRecordType.DESIGN_WORKSHOP) dwScannedWorkshopTitle(context, code) else null
            ScanRecordAnswer.Missing(title)
        } catch (_: Exception) {
            ScanRecordAnswer.Offline
        }
    }
}

const val SCAN_ADD_ARTISAN_NOT_ALLOWED = "Only researchers and above can add artisans."

fun designerScanOutcome(outcome: ScanOutcome, canAddArtisan: Boolean): ScanOutcome =
    if (!canAddArtisan && outcome.kind == ScanOutcomeKind.ADD_FROM_PEER) {
        outcome.copy(detail = SCAN_ADD_ARTISAN_NOT_ALLOWED, action = null)
    } else {
        outcome
    }

suspend fun runDesignerScanAction(context: Context, action: ScanAction, onTarget: (ScanTarget) -> Unit): ScanOutcome? =
    when (action) {
        is ScanAction.Open -> {
            onTarget(action.target)
            null
        }
        is ScanAction.Add -> {
            onTarget(action.target)
            null
        }
        is ScanAction.Redeem -> when (val outcome = dwScanJoinCard(context, action.workshopId, action.code)) {
            is DwJoinOutcome.Inducted -> ScanOutcome(
                ScanOutcomeKind.OPEN,
                if (outcome.fullMember) ScanCopy.JOINED else outcome.message,
                if (outcome.fullMember) outcome.message else null,
                ScanAction.Open(ScanCopy.OPEN_WORKSHOP, ScanTarget.DesignWorkshop(action.workshopId)),
            )
            is DwJoinOutcome.Queued -> ScanOutcome(ScanOutcomeKind.OFFLINE, outcome.message)
            is DwJoinOutcome.Refused -> ScanOutcome(ScanOutcomeKind.REFUSED, outcome.message)
            is DwJoinOutcome.Asked -> ScanOutcome(ScanOutcomeKind.JOIN_WORKSHOP, outcome.detail)
        }
        is ScanAction.RequestAccess -> when (val outcome = dwJoinDesignWorkshop(context, action.workshopId, action.scannedCode)) {
            is DwJoinOutcome.Asked -> ScanOutcome(ScanOutcomeKind.JOIN_WORKSHOP, outcome.detail)
            is DwJoinOutcome.Queued -> ScanOutcome(ScanOutcomeKind.JOIN_WORKSHOP, outcome.message)
            is DwJoinOutcome.Refused -> ScanOutcome(ScanOutcomeKind.REFUSED, outcome.message)
            is DwJoinOutcome.Inducted -> ScanOutcome(
                ScanOutcomeKind.OPEN,
                outcome.message,
                action = ScanAction.Open(ScanCopy.OPEN_WORKSHOP, ScanTarget.DesignWorkshop(action.workshopId)),
            )
        }
        is ScanAction.JoinSession -> ScanOutcome(ScanOutcomeKind.REFUSED, ScanCopy.SESSION_ELSEWHERE)
    }

const val DESIGNER_SCAN_HINT = "Line the code up in the box, or choose a photo of it. Works offline."

@Composable
fun DesignerScanControls(enabled: Boolean, onText: (String) -> Unit, onRefusal: (String) -> Unit) {
    DwQrLiveScanControl(
        enabled = enabled,
        onText = onText,
        onRefusal = onRefusal,
        label = ScanCopy.USE_CAMERA,
        pictureLabel = ScanCopy.CHOOSE_PHOTO,
        captureLabel = null,
        hint = DESIGNER_SCAN_HINT,
        alongside = {
            DwQrPictureButton(enabled = enabled, onText = onText, onRefusal = onRefusal, label = ScanCopy.CHOOSE_PHOTO)
        },
    )
}

@Composable
fun RecordCodeLookupPanel(
    repository: WorkshopRepository,
    onOpen: (recordType: String, recordId: String) -> Unit,
    modifier: Modifier = Modifier,
    onTarget: ((ScanTarget) -> Unit)? = null,
    canAddArtisan: Boolean = true,
) {
    val context = LocalContext.current
    val services = remember(repository) { designerScanServices(context, repository) }
    val route: (ScanTarget) -> Unit = { target ->
        when (target) {
            is ScanTarget.Record -> onOpen(target.recordType, target.id)
            is ScanTarget.DesignWorkshop -> onOpen("designWorkshop", target.id)
            else -> onTarget?.invoke(target)
        }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.field.surface50),
        border = BorderStroke(1.dp, MaterialTheme.field.hairline),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.QrCode2, contentDescription = null, tint = MaterialTheme.field.muted, modifier = Modifier.size(16.dp))
                Text(
                    "Open a record from its code",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            TopBarScanContent(
                dispatch = { raw -> designerScanOutcome(dispatchScan(raw, services), canAddArtisan) },
                onAction = { action -> runDesignerScanAction(context, action, route) },
                onDone = {},
                scanner = { enabled, onText, onRefusal -> DesignerScanControls(enabled, onText, onRefusal) },
            )
        }
    }
}
