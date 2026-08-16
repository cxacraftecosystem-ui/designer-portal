package com.designprototype.workshop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwDecodeResult
import com.designprototype.workshop.data.DwWorkshopCodeRef
import com.designprototype.workshop.data.DwWorkshopRecordType
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.decodeWorkshopCode
import com.designprototype.workshop.data.unresolvedWorkshopCodeMessage
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * Read a code back from anywhere in the app, for every record type it can carry, and open the record.
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
            "That is a prototype tag. A prototype belongs to one design workshop and is looked up inside " +
                "it — open that workshop and use its Cards & tags screen."
        )
    }
    return try {
        val hit = when (ref.recordType) {
            DwWorkshopRecordType.ARTISAN -> repository.artisan(ref.id).let { RecordCodeHit(it.name, line(it.place)) }
            DwWorkshopRecordType.CRAFT -> repository.craft(ref.id).let { RecordCodeHit(it.name, line(it.category, it.place)) }
            DwWorkshopRecordType.WORKSHOP -> repository.workshop(ref.id).let { RecordCodeHit(it.title, line(it.place)) }
            DwWorkshopRecordType.PRODUCT ->
                repository.product(ref.id).let { RecordCodeHit(it.productName, line(it.craftName, it.artisanName, it.place)) }
            DwWorkshopRecordType.PROCESS -> repository.process(ref.id).let { RecordCodeHit(it.name, line(it.notes)) }
            DwWorkshopRecordType.TOOL ->
                repository.tool(ref.id).let { RecordCodeHit(it.toolkitName, line(it.craftName, it.artisanName, it.place)) }
            // The artisans a sitting covered are deliberately NOT named: the interview's own title and
            // place are what confirm the right sitting was found, and a list of people is the sort of
            // thing that ends up on a screen held up in the room those people are standing in.
            DwWorkshopRecordType.QUESTIONNAIRE ->
                repository.interview(ref.id).let { RecordCodeHit(it.title, line(it.place, it.language)) }
            DwWorkshopRecordType.MEDIA ->
                repository.mediaItem(ref.id).let {
                    RecordCodeHit(it.caption?.trim()?.ifEmpty { null } ?: it.originalFilename, line(it.mediaType, it.mimeType))
                }
            // Answered above. Written out rather than left to an `else` so that adding a record type to
            // the grammar fails this `when`'s exhaustiveness check instead of falling into a branch
            // somebody else wrote.
            DwWorkshopRecordType.PROTOTYPE -> return RecordCodeOutcome.Refused(unresolvedWorkshopCodeMessage(ref.recordType))
        }
        RecordCodeOutcome.Found(ref, hit)
    } catch (e: HttpException) {
        RecordCodeOutcome.Refused(unresolvedWorkshopCodeMessage(ref.recordType))
    } catch (e: Exception) {
        RecordCodeOutcome.Refused(
            "There is no connection, so the repository could not be asked about that code. Try again when there " +
                "is signal — the code itself checked out, so the card is fine."
        )
    }
}

/**
 * The typed-code route, and the one press that opens what it found.
 *
 * IT REPORTS, THEN OFFERS TO OPEN. A lookup that jumped straight to a record would leave nothing on
 * screen to check, and the one failure this whole feature exists to remove is work attached to the
 * wrong record. Opening is a deliberate press on a real button, which is also what makes the result
 * reachable with a keyboard and readable by TalkBack after the announcement has gone.
 *
 * @param onOpen the record type's [DwWorkshopRecordType.wire] name and the record's id — the same
 *   singular vocabulary `SearchScreen` reports hits in, so the host routes both through one mapper.
 */
@Composable
fun RecordCodeLookupPanel(
    repository: WorkshopRepository,
    onOpen: (recordType: String, recordId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<RecordCodeOutcome.Found?>(null) }

    /**
     * Resolve whatever was read or typed — ONE route for both.
     *
     * A scanned payload goes through [decodeWorkshopCode] exactly as a typed one does. That is the
     * whole reason this takes a string rather than the scanner calling its own resolver: a payment
     * QR photographed by mistake must be refused by the same sentence a mistyped code is, and a
     * second parser is how the two come to disagree about the same card.
     */
    fun lookUp(input: String) {
        if (input.isBlank() || busy) return
        busy = true
        // Cleared BEFORE the request, not after it: the seconds a lookup takes are exactly when the
        // previous code's Open button would otherwise still be sitting under this one's spinner, one
        // press away from opening the wrong record.
        refusal = null
        found = null
        scope.launch {
            when (val decoded = decodeWorkshopCode(input)) {
                is DwDecodeResult.Refused -> refusal = decoded.message
                is DwDecodeResult.Ok -> when (val answer = lookUpRecordCode(repository, decoded.ref)) {
                    is RecordCodeOutcome.Found -> found = answer
                    is RecordCodeOutcome.Refused -> refusal = answer.message
                }
            }
            busy = false
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
                Icon(
                    Icons.Filled.QrCode2,
                    contentDescription = null,
                    tint = MaterialTheme.field.muted,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "Open a record from its code",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                "The code printed under the QR on any card, tag or record screen — an artisan, a craft, a workshop, " +
                    "a product, a process, a tool, an interview or a media file. Nothing about the record is inside " +
                    "the code: it holds a reference and a check digit, and nothing else.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            // THE SCANNER ABOVE THE BOX, because it is the faster route when it applies and the box
            // is what you fall back to. Both are always present; neither is hidden by the other.
            DwQrScanControl(
                enabled = !busy,
                onText = { text ->
                    // The decoded payload is put IN THE BOX as well as resolved. A designer who
                    // scanned the wrong card can see what was read and correct a character, rather
                    // than being shown a refusal about a string the app never told them it had.
                    typed = text
                    refusal = null
                    found = null
                    lookUp(text)
                },
                onRefusal = { message ->
                    found = null
                    refusal = message
                },
            )
            OutlinedTextField(
                value = typed,
                onValueChange = {
                    typed = it
                    refusal = null
                    found = null
                },
                label = { Text("Record code") },
                placeholder = { Text("DPW1 :T: …") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                leadingIcon = {
                    Icon(Icons.Filled.Keyboard, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { lookUp(typed) },
                    enabled = typed.isNotBlank() && !busy,
                    // The 48dp floor this app applies wherever a control was thought about — see
                    // ISLAND_TOUCH_TARGET in ui/AppNavigation.kt.
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Look up")
                }
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp))
            }
            Text(
                "Spaces and capitals do not matter. The four characters at the end are a check — if they do not " +
                    "match, the app says so rather than opening the wrong record.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )

            refusal?.let {
                Text(
                    it,
                    color = MaterialTheme.field.onWarningContainer,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Polite rather than a snackbar. A snackbar goes away after a few seconds, and
                        // both outcomes here are things the designer has to act on: open the record, or
                        // go and read the card again.
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .background(MaterialTheme.field.warningContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }

            found?.let { answer ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .background(MaterialTheme.field.successContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        answer.ref.recordType.label,
                        color = MaterialTheme.field.onSuccessContainer,
                        fontSize = 11.sp,
                    )
                    Text(
                        answer.hit.label,
                        color = MaterialTheme.field.onSuccessContainer,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    answer.hit.detail?.let {
                        Text(it, color = MaterialTheme.field.onSuccessContainer, fontSize = 11.sp)
                    }
                    Button(
                        onClick = { onOpen(answer.ref.recordType.wire, answer.ref.id) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("Open ${answer.ref.recordType.label.lowercase()}")
                    }
                }
            }
        }
    }
}
