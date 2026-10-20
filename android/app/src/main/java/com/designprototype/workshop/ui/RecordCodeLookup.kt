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
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.dispatchScan
import com.designprototype.workshop.data.readWorkshopScan
import com.designprototype.workshop.data.unresolvedWorkshopCodeMessage
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/**
 * Read a code back from anywhere in the app, for every record type it can carry, and open the record.
 *
 * ── IT NOW ANSWERS THROUGH THE TOP-BAR DISPATCHER (REQUIREMENTS_2026-10-20 §6) ───────────────
 *
 * [RecordCodeLookupPanel] draws [TopBarScanContent] over [designerScanServices], the same body the
 * header's scan button opens, so the search screen, "Scan a code" and the header cannot answer one
 * code two ways. What a code is and what it says is `data/ScanDispatch.kt`, shared with the Field
 * Repository and the web. The paragraphs below describe the camera and the anti-enumeration rule,
 * both of which still hold.
 *
 * ── WHY IT IS NOT ONLY ON THE WORKSHOP'S CARDS & TAGS SCREEN ─────────────────────────────────
 *
 * That screen's lookup is scoped to one design workshop: it answers out of that workshop's local
 * draft first, because a prototype tag in a village with no signal has nowhere else to be answered
 * from. A tool tag, a product tag or an artisan card is not about one workshop — it is about the
 * repository — and asking a designer to open some workshop before looking up a tool would be asking
 * them to name the very thing they scanned the tag to avoid naming.
 *
 * IT LIVES ON THE SEARCH SCREEN because that is where "find the record for this thing" already
 * lives: a scan is a search whose query happens to be exact. Hosting it there also means no new
 * destination has to be invented in the navigation on one client and mirrored on the other — the web
 * puts its own scanner at the top of `/search` for the same reason.
 *
 * ── THERE IS A CAMERA HERE NOW, AND THERE DID NOT USED TO BE ─────────────────────────────────
 *
 * WHAT THIS HEADER USED TO SAY, kept so the reversal is legible rather than mysterious:
 *
 *     "THERE IS NO CAMERA HERE, AND THAT IS A DECISION. The same one `WorkshopCodesScreen`'s header
 *      records and does not repeat here: Android has no `BarcodeDetector`, decoding a QR needs ML
 *      Kit or ZXing, and that is a new transitive dependency in an APK that ships to field handsets
 *      over prepaid mobile data. The typed code is a shorter path to the same record and it works on
 *      every device today with nothing added, so the typed box is not a fallback — it is the route,
 *      and it is never hidden."
 *
 * That was a fair reading and it was overtaken by a requirement: every QR surface is to offer the
 * camera AND a picture the designer already holds. THE SECOND HALF IS WHAT THE OLD ARGUMENT NEVER
 * COVERED. "Typing is a shorter path" assumes somebody is standing in front of the card — and the
 * case this feature exists for is a screenshot forwarded on WhatsApp, or a photograph of a tag taken
 * last week, where there is no card to read from and no shorter path at all.
 *
 * ZXing (0.58 MB, pure Java, no Play Services) is the dependency, which is what
 * `docs/DECISION-qr-scanning-on-android.md` decided in the first place and never built.
 *
 * ── AND THE CAMERA IS NOW A LIVE ONE, WHICH FIXED A DEFECT RATHER THAN ADDING A FLOURISH ────
 *
 * [DwQrLiveScanControl] sits above [DwQrScanControl] on this panel. The reason is not that a live
 * preview is nicer: `ActivityResultContracts.TakePicture()` hands off to the SYSTEM camera app,
 * which reopens whatever lens IT last used, so pressing "Scan a code" showed designers the FRONT
 * camera and no argument to that contract can change it. `DwQrLiveScanner` binds
 * `CameraSelector.DEFAULT_BACK_CAMERA` explicitly, per bind.
 *
 * BOTH OF THE OLD BUTTONS ARE STILL HERE AND STILL UNCHANGED. The photograph is the better tool for
 * a small or dim code — it decodes at full resolution and walks `DW_QR_SAMPLE_LADDER` — and the
 * picked picture is the route nothing else replaces. Three doors, and the typed box below them is
 * the fourth.
 *
 * ── AND A SCANNED DESIGN-WORKSHOP CARD NOW DOES SOMETHING ───────────────────────────────
 *
 * It used to be answered by `designWorkshopCodeNotOpenableMessage()`, which said this build could not
 * act on it. It can: `POST /design-workshop-access/requests` was shipped with no client, and
 * `ui/DwWorkshopJoin.kt` is now that client. See [RecordCodeOutcome.Join].
 *
 * THE TYPED BOX IS STILL THE GUARANTEED PATH and is still never hidden. It needs no permission, no
 * lens and no library, and it is the only route that works on a card whose QR is smudged while the
 * characters printed under it are not.
 *
 * ── A REFUSAL NEVER SAYS THE RECORD IS REAL ──────────────────────────────────────────────────
 *
 * Every detail endpoint behind this is `Depends(get_current_user)` + `require_record`, and
 * `require_record` raises **404** — never 403 — for a record the caller cannot have
 * (`backend/app/services/records.py`). A 403 would confirm that the identifier names something, and
 * confirming that from a code printed on a card is a way to enumerate the repository one photograph
 * at a time. So every answer the server gives collapses into ONE sentence here. Do not add a branch
 * on the status code. The one distinction that IS made is "the server never answered", because
 * waiting for signal and reading the card again are completely different next actions — and Retrofit
 * gives that split for free: an [HttpException] means the server answered, anything else means it
 * was never reached.
 */

/** A record a typed code resolved to. Never an identity number — see `DwWorkshopCodes`. */
data class RecordCodeHit(val label: String, val detail: String? = null)

sealed interface RecordCodeOutcome {
    data class Found(val ref: DwWorkshopCodeRef, val hit: RecordCodeHit) : RecordCodeOutcome

    data class Refused(val message: String) : RecordCodeOutcome

    /**
     * A DESIGN-WORKSHOP card, which is the one code in this grammar that is about PEOPLE rather than
     * about a record.
     *
     * A MARKER AND NOT A MESSAGE, deliberately. [lookUpRecordCode] is a suspend function with a
     * repository and nothing else — no Android context, no scope — and the join path needs a context
     * (a queue on disk, a connectivity check, a fresh token). So this arm carries the workshop id and
     * the CALLER performs the act. Both callers are screens that have a context; putting the act
     * behind this function would have meant widening a signature that fourteen other record types are
     * perfectly served by.
     *
     * It also keeps the anti-enumeration discipline intact by construction: this outcome says nothing
     * at all about whether the workshop exists, because nothing has been asked yet.
     */
    data class Join(val workshopId: String) : RecordCodeOutcome
}

/** Join the parts of a supporting line, dropping the ones this record does not carry. */
private fun line(vararg parts: String?): String? =
    parts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

/**
 * Ask the repository what a decoded reference points at.
 *
 * ONE try/catch around every record type rather than one per type: a per-type catch is how one type
 * comes to explain a dead network as a missing record.
 */
suspend fun lookUpRecordCode(repository: WorkshopRepository, ref: DwWorkshopCodeRef): RecordCodeOutcome {
    if (ref.recordType == DwWorkshopRecordType.PROTOTYPE) {
        // A prototype is a row inside one design workshop's draft — often only on the device that made
        // it, in a village with no signal — so there is no endpoint to ask. Saying which screen DOES
        // resolve it is the useful answer; "no prototype matches" would be a claim this cannot support.
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


/**
 * Fetch what a reference names, throwing on any failure so each caller decides what a failure means.
 *
 * ONE `when` for every record type, shared by [lookUpRecordCode] and [designerScanServices]: a
 * second copy is how one surface comes to describe a record differently from another. The artisans a
 * sitting covered are deliberately NOT named for an interview: its title and place confirm the right
 * sitting, and a list of people is what ends up on a screen held up in the room they are standing in.
 */
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
        // The server answers 404 for a workshop this account is not on; the scanner then offers to ask.
        DwWorkshopRecordType.DESIGN_WORKSHOP ->
            repository.designWorkshop(ref.id).let {
                RecordCodeHit(it.title.ifBlank { "Design workshop" }, line(it.craftName, it.district))
            }
        // A prototype lives in one workshop's draft; there is no endpoint to ask.
        DwWorkshopRecordType.PROTOTYPE -> throw IllegalArgumentException("A prototype is looked up inside its workshop.")
    }

// ---------------------------------------------------------------------------------------------
// The top-bar dispatcher's designer half (REQUIREMENTS_2026-10-20 §6)
// ---------------------------------------------------------------------------------------------

/** The DPW grammars, read by `readWorkshopScan` exactly as every other scanner here reads them. */
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

/** How an artisan card reads, by this app's [ArtisanCardCode]. */
fun designerScanCardKind(raw: String): ScanCardKind = when {
    ArtisanCardCode.parse(raw) is ArtisanCardCode.Result.Ok -> ScanCardKind.VALID
    ArtisanCardCode.looksLikeCard(raw) -> ScanCardKind.MISTYPED
    else -> ScanCardKind.NONE
}

/** The designer app's answers to the dispatcher: the cached register first, then the server. */
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
            // A card this phone has not seen, with no signal: nothing to open until it resolves.
            is CardResolution.Pending -> ScanCardAnswer.Offline
            is CardResolution.Refused -> ScanCardAnswer.Refused(answer.message)
        }

    override suspend fun lookUpRecord(recordType: String, id: String): ScanRecordAnswer {
        val type = DwWorkshopRecordType.ofWire(recordType) ?: return ScanRecordAnswer.Missing
        if (type == DwWorkshopRecordType.PROTOTYPE) return ScanRecordAnswer.Missing
        return try {
            val hit = fetchRecordCodeHit(repository, DwWorkshopCodeRef(type, id))
            ScanRecordAnswer.Found(hit.label, hit.detail)
        } catch (e: CancellationException) {
            throw e
        } catch (_: HttpException) {
            // One answer for "no such record" and "not yours", as the API gives it.
            ScanRecordAnswer.Missing
        } catch (_: Exception) {
            ScanRecordAnswer.Offline
        }
    }
}

/**
 * Run the action the person pressed. Returns the next outcome to show, or null once it has handed
 * [onTarget] a place to go. The join card and the ask are queued on the device when there is no
 * signal (`DwJoinCard.kt`, `DwWorkshopJoin.kt`), and the server's own sentence is shown as given.
 */
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
        // A Field Repository session; the dispatcher refuses it here before an action exists.
        is ScanAction.JoinSession -> ScanOutcome(ScanOutcomeKind.REFUSED, ScanCopy.SESSION_ELSEWHERE)
    }

/** This app's camera and picture controls, in the slot [TopBarScanContent] leaves for them. */
@Composable
fun DesignerScanControls(enabled: Boolean, onText: (String) -> Unit, onRefusal: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DwQrLiveScanControl(enabled = enabled, onText = onText, onRefusal = onRefusal)
        DwQrScanControl(enabled = enabled, onText = onText, onRefusal = onRefusal)
    }
}

/**
 * The search screen's and "Scan a code"'s panel: the top-bar scanner's own body, in a card.
 *
 * @param onOpen a record's wire type and id: the search screen's own arm (`openSearchRecord`).
 * @param onTarget every other place a code can lead (the add-artisan form, the design workshops,
 *   Questionnaires).
 */
@Composable
fun RecordCodeLookupPanel(
    repository: WorkshopRepository,
    onOpen: (recordType: String, recordId: String) -> Unit,
    modifier: Modifier = Modifier,
    onTarget: ((ScanTarget) -> Unit)? = null,
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
                dispatch = { raw -> dispatchScan(raw, services) },
                onAction = { action -> runDesignerScanAction(context, action, route) },
                onDone = {},
                scanner = { enabled, onText, onRefusal -> DesignerScanControls(enabled, onText, onRefusal) },
            )
        }
    }
}
