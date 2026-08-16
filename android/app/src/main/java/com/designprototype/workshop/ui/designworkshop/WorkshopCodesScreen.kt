package com.designprototype.workshop.ui.designworkshop

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.designprototype.workshop.data.DwCardHit
import com.designprototype.workshop.data.DwCardRender
import com.designprototype.workshop.data.DwCardSource
import com.designprototype.workshop.data.DwDecodeResult
import com.designprototype.workshop.data.DwWorkshopCodeRef
import com.designprototype.workshop.data.DwWorkshopRecordType
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageBucketDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.decodeWorkshopCode
import com.designprototype.workshop.data.findWorkshopCodeInDraft
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.data.renderWorkshopCard
import com.designprototype.workshop.data.unresolvedWorkshopCodeMessage
import com.designprototype.workshop.data.workshopCardRows
import com.designprototype.workshop.data.workshopCardSource
import com.designprototype.workshop.data.workshopCardSpecs
import com.designprototype.workshop.data.workshopRecordTypeLabel
import com.designprototype.workshop.report.renderCardSheetPdf
import com.designprototype.workshop.ui.DwQrScanControl
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Cards & tags — print a code for every artisan on the roster and every prototype in the workshop,
 * and read one back.
 *
 * ── THE PROBLEM THIS SCREEN REMOVES ───────────────────────────────────────────────────────────
 *
 * Stage 14 asks a designer to record an iteration "against a prototype", stage 15 to validate one,
 * stage 16 to document one — three stages, over a fortnight, each beginning by choosing a prototype
 * from a list of twenty-five. Choose the wrong one and two days of measurements attach to somebody
 * else's work; nothing downstream can tell, because both rows look complete. A tag tied to the object
 * removes the choosing.
 *
 * ── WHY THIS EXISTS ON THE HANDSET AT ALL, GIVEN THE WEB HAS IT ───────────────────────────────
 *
 * The tags are wanted in the room, on the afternoon the prototypes are made, and that room has no
 * signal and no laptop. The web's sheet is unreachable there. Everything the codes need — the
 * grammar, the check digit and the whole QR encoder — has been on this device the entire time in
 * [DwWorkshopCodes] and [DwQrEncode], with nothing calling either of them.
 *
 * ── THREE THINGS THIS SCREEN DOES AND ONE IT HONESTLY DOES NOT ────────────────────────────────
 *
 *  1. It DRAWS every card on screen, from the same module matrix the file is drawn from, so a
 *     designer can check a symbol and a name before spending a walk to a printer.
 *  2. It writes an A4 PDF at the size the cards will be cut to — see [renderCardSheetPdf] for why the
 *     millimetres are not negotiable — into Downloads, which is what "print" means on a device with
 *     no printer.
 *  3. It reads a code BACK by hand, on every device, with no dependency and no permission.
 *
 *  4. It reads a code back from the CAMERA, or out of a picture the designer was sent.
 *
 * ── POINT 4 USED TO BE "IT DOES NOT OPEN THE CAMERA", AND THAT REVERSED ON 2026-08-16 ─────────
 *
 * The old text, kept so the change is legible rather than mysterious:
 *
 *     "IT DOES NOT OPEN THE CAMERA, and that is a decision rather than a gap. Android has no
 *      `BarcodeDetector`; decoding a QR needs ML Kit or ZXing, which is a new transitive dependency
 *      in an APK that ships to field handsets over a village connection — method count in a
 *      64K-limited dex, a second implementation of a standard this repository already implements
 *      once, and a download every designer pays for. … So the manual box is not a fallback here, it
 *      is the route — and it is never hidden … A camera path is worth adding the day somebody
 *      measures how long a designer spends typing; it is not worth adding on the strength of it
 *      being what a scanner usually looks like."
 *
 * WHAT REOPENED IT IS NOT THE CAMERA HALF. It is the other one: every QR surface is now to accept a
 * PICTURE the designer already holds — a screenshot forwarded on WhatsApp, a photograph of a tag
 * taken last week, a card sheet printed in an office two districts away. "Typing is a shorter path"
 * is true only while somebody is standing in front of the card, and in every one of those cases
 * nobody is. There is no shorter path; there is no path.
 *
 * The dependency is ZXing — 0.58 MB, pure Java, no Play Services, and on the JVM test classpath, so
 * `DwQrDecodeTest` decodes symbols made by this file's own printer. That is what
 * `docs/DECISION-qr-scanning-on-android.md` chose in the first place and never built; its own review
 * trigger was a QR dependency appearing in the build file, and it has been updated rather than left
 * to rot.
 *
 * THE TYPED BOX IS UNCHANGED AND IS STILL NEVER HIDDEN. It needs no permission, no lens and no
 * library, and it is the only route that works on a card whose QR is smudged while the characters
 * printed under it are not.
 *
 * ── IT RUNS OFF THE LOCAL DRAFT ───────────────────────────────────────────────────────────────
 *
 * [WorkshopDraftStore] holds the whole workshop on this device, so a designer standing in a courtyard
 * with no signal can print tags for prototypes made this morning. The server's copy is fetched too
 * and used for exactly one thing — a stage this handset has never opened — which is what stops the
 * sheet reading "no prototypes" over a workshop holding twenty-five. See [workshopCardRows].
 */
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

    LaunchedEffect(workshopId) {
        loading = true
        runCatching {
            val loadedSchema = repository.designWorkshopSchema(appContext)
            val local = WorkshopDraftStore.load(appContext, workshopId)
            val remoteId = local?.remoteId ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
            val remote = remoteId?.let { runCatching { repository.designWorkshopStages(it) }.getOrNull() }
            // WHICH COPY IS ON SCREEN IS NOT GUESSABLE FROM THE SHEET, and it decides whether a
            // missing tag means "nobody entered that prototype" or "this device has not seen it".
            val note = when {
                remoteId == null ->
                    "This workshop has not been created on the server yet, so these are the prototypes and " +
                        "artisans saved on this device. Printing works exactly the same."
                remote == null ->
                    "There is no connection, so these are the prototypes and artisans saved on this device. " +
                        "Printing works exactly the same; anything recorded elsewhere is not here yet."
                else -> null
            }
            Triple(loadedSchema, local, remote?.stages.orEmpty()) to note
        }.onSuccess { (loaded, note) ->
            schema = loaded.first
            draft = loaded.second
            remoteStages = loaded.third
            deviceOnlyNote = note
        }.onFailure {
            onError(it.message ?: "Unable to read this workshop's prototypes and roster.")
        }
        loading = false
    }

    val source: DwCardSource? = remember(schema, kind) { schema?.let { workshopCardSource(kind, it) } }

    /**
     * The cards, rendered once per change of kind or data.
     *
     * `remember` and not a recomputation per frame: encoding thirty payloads and scoring eight masks
     * of each is tens of milliseconds on the cheapest handset in the room, and it would otherwise be
     * paid on every keystroke in the lookup box below.
     */
    val cards: List<DwCardRender> = remember(source, draft, remoteStages, kind) {
        val current = source ?: return@remember emptyList()
        val rows = workshopCardRows(current, draft, remoteStages)
        workshopCardSpecs(kind, current, rows).map { renderWorkshopCard(it) }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Cards & tags", display = true, color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp)
        Text(
            "Printable codes for the artisans on this workshop's roster and for every prototype in it, and " +
                "a lookup that reads one back. A scan is what stops two days of work being attached to the " +
                "wrong prototype.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )

        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Reading this workshop…", color = MaterialTheme.field.muted, fontSize = 13.sp)
            }
            return@Column
        }

        deviceOnlyNote?.let { DwWorkshopNotice(it) }

        // What to print. Two buttons rather than a segmented control, matching the web's pair and the
        // rest of this app's toggles, and each states its own pressed state for a screen reader.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DwCardKindButton("Prototype tags", kind == DwWorkshopRecordType.PROTOTYPE, Modifier.weight(1f)) {
                kind = DwWorkshopRecordType.PROTOTYPE
                exported = null
            }
            DwCardKindButton("Artisan cards", kind == DwWorkshopRecordType.ARTISAN, Modifier.weight(1f)) {
                kind = DwWorkshopRecordType.ARTISAN
                exported = null
            }
        }

        DwCodeLookupPanel(
            resolve = { ref ->
                val currentSchema = schema
                val local = currentSchema?.let { findWorkshopCodeInDraft(ref, it, draft, remoteStages) }
                when {
                    // The registry never arrived, so this build cannot say where prototypes live. The
                    // code itself was still checked — a wrong character is already refused above — and
                    // saying THAT is the honest answer. `unresolvedWorkshopCodeMessage` here would
                    // report "no prototype in this workshop matches that tag" about a workshop this
                    // screen never managed to read, and send the designer to look for a row that is
                    // probably there.
                    currentSchema == null -> DwLookupOutcome.Refused(
                        "That code is well formed, but this workshop could not be read on this device, so " +
                            "there is nothing to match it against yet. Open it again when there is a connection."
                    )

                    local != null -> DwLookupOutcome.Found(local)
                    // A prototype tag has nowhere else to be looked for: prototypes belong to a
                    // workshop, and this is the workshop.
                    ref.recordType == DwWorkshopRecordType.PROTOTYPE ->
                        DwLookupOutcome.Refused(unresolvedWorkshopCodeMessage(ref.recordType))
                    // An artisan card may be somebody documented elsewhere, so the repository is worth
                    // one request — and its refusals are flattened into ONE sentence on purpose:
                    // `GET /artisans/{id}` answers 404 both for a record that does not exist and for
                    // one this designer may not see, and telling the two apart here would hand back
                    // exactly the fact the API withholds.
                    ref.recordType == DwWorkshopRecordType.ARTISAN -> lookUpArtisan(repository, ref.id)
                    // EVERY OTHER RECORD TYPE, and this branch is why it is a branch. This `when` used
                    // to end at `else -> lookUpArtisan`, which was correct while the grammar carried
                    // exactly two letters: anything that was not a prototype was an artisan. The
                    // moment nine letters existed, that `else` started asking `GET /artisans/{id}`
                    // about a TOOL id — which 404s, and is then reported as "no artisan you can open
                    // matches that code … search for the artisan by name instead". A designer standing
                    // at this screen with a tool tag would be told, confidently, the wrong thing about
                    // a record this build opens perfectly well from Search. `lookUpRecordCode` asks the
                    // endpoint that actually holds the record and names it correctly; its refusals are
                    // the same flattened sentence for the same reason.
                    else -> when (val answer = lookUpRecordCode(repository, ref)) {
                        is RecordCodeOutcome.Found ->
                            DwLookupOutcome.Found(DwCardHit(answer.hit.label, answer.hit.detail.orEmpty()))

                        is RecordCodeOutcome.Refused -> DwLookupOutcome.Refused(answer.message)
                    }
                }
            }
        )

        HorizontalDivider()

        if (source == null) {
            // NOT the same as "there is nothing to print", and confusing the two would send a designer
            // looking for rows they never entered instead of reporting a build that is out of date.
            DwWorkshopNotice(
                "This version of the app cannot find " +
                    (if (kind == DwWorkshopRecordType.PROTOTYPE) "the prototype list" else "the artisan roster") +
                    " in the field registry it was served, so it cannot print " +
                    (if (kind == DwWorkshopRecordType.PROTOTYPE) "prototype tags" else "artisan cards") +
                    ". Nothing is missing from your workshop — update the app."
            )
            return@Column
        }

        if (cards.isEmpty()) {
            Text(
                if (kind == DwWorkshopRecordType.PROTOTYPE) {
                    "No prototypes have been recorded in this workshop yet. Add them at the prototype " +
                        "development stage and the tags appear here."
                } else {
                    "No artisans are on this workshop's roster yet. Enrol them on the participants stage " +
                        "and their cards appear here."
                },
                color = MaterialTheme.field.body,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
            return@Column
        }

        val refusals = cards.count { it is DwCardRender.Refused }
        Text(
            buildString {
                append("${cards.size} ${if (kind == DwWorkshopRecordType.PROTOTYPE) "tag" else "card"}")
                if (cards.size != 1) append("s")
                // STATED, ALWAYS. A sheet that quietly held four fewer symbols than rows is the
                // silent-emptiness failure this repository keeps hitting; the count is what makes a
                // designer look at the amber card before they cut anything up.
                if (refusals > 0) append(" · $refusals cannot be printed yet")
            },
            color = if (refusals > 0) MaterialTheme.field.warning else MaterialTheme.field.muted,
            fontSize = 12.sp
        )

        Button(
            onClick = {
                exporting = true
                exported = null
                scope.launch {
                    runCatching { writeSheet(context, repository, kind, cards) }
                        .onSuccess {
                            exported = it
                            onMessage("Saved ${it.fileName}")
                        }
                        .onFailure { onError(it.message ?: "The sheet could not be written.") }
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
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Drawing the sheet on this device…", color = MaterialTheme.field.muted, fontSize = 13.sp)
            }
        }

        exported?.let { sheet -> DwSheetSavedCard(sheet) }

        Text(
            // Said beside the file rather than left to be discovered at the printer. A sheet printed
            // "fit to page" comes out a few per cent small, and a QR module under half a millimetre is
            // at the resolution limit of the camera that has to read it in a courtyard.
            "Print it at 100% — not \"fit to page\". The cards are drawn at the size they are cut to, and a " +
                "sheet scaled down produces symbols that scan on this phone and on no other.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )

        cards.forEach { card -> DwCardPreview(card) }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

// --------------------------------------------------------------------------------------
// The card preview
// --------------------------------------------------------------------------------------

/**
 * One card as it will be cut out: the symbol, the name, the supporting lines and the printed code —
 * or the reason there is no symbol.
 *
 * DRAWN FROM THE SAME MATRIX THE FILE IS DRAWN FROM, so what a designer checks here is what comes out
 * of the printer. It is deliberately NOT to scale: on a 360dp screen a 26mm box is unreadable, and a
 * preview that reproduced the physical size would defeat the one thing a preview is for.
 */
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
                        // Decorative: the amber sentence beside it carries the meaning, so the refusal
                        // survives greyscale and a screen reader that never sees the icon.
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
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    // Two lines then an ellipsis, as on paper: a long name must never push the printed
                    // code out of view, because the code is the half a human falls back on.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                card.spec.lines.take(2).forEach { line ->
                    Text(line, color = MaterialTheme.field.muted, fontSize = 11.sp)
                }
                when (card) {
                    is DwCardRender.Ok -> Text(
                        card.printed,
                        color = MaterialTheme.field.body,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        // Monospace, because this string is read aloud and typed back: the grouping is
                        // only legible if the groups line up.
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
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

// The symbol itself is drawn by `ui/RecordCodeCard.DwQrSymbolImage`, which used to live here as a
// private copy. It moved when every record type got a code on its own screen: two implementations of
// "draw this matrix" are two chances to get the quiet zone or the rounding wrong, in one feature a
// designer moves between within a single workshop.

// --------------------------------------------------------------------------------------
// Reading a code back
// --------------------------------------------------------------------------------------

/** What the host made of a reference. A refusal must never confirm that the record exists. */
sealed interface DwLookupOutcome {
    data class Found(val hit: DwCardHit) : DwLookupOutcome
    data class Refused(val message: String) : DwLookupOutcome
}

/**
 * Read one code back: scanned, picked out of a picture, or typed.
 *
 * ALL THREE ROUTES END IN [decodeWorkshopCode], which is the point. A payment QR photographed by
 * mistake and a mistyped code are refused by the same sentence, because there is one parser and no
 * second opinion; and the version gate and the check digit apply identically however the characters
 * arrived.
 *
 * The typed box remains tolerant of what a human does to a code and strict about what it means:
 * spaces and capitals are stripped before the parse, because a designer copying a code off a card
 * under a tin roof will not reproduce them and refusing over a space would be a refusal about
 * nothing.
 */
@Composable
private fun DwCodeLookupPanel(resolve: suspend (DwWorkshopCodeRef) -> DwLookupOutcome) {
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }

    fun lookUp(input: String) {
        if (input.isBlank() || busy) return
        busy = true
        detail = null
        scope.launch {
            when (val decoded = decodeWorkshopCode(input)) {
                is DwDecodeResult.Refused -> outcome = false to decoded.message
                is DwDecodeResult.Ok -> when (val answer = resolve(decoded.ref)) {
                    is DwLookupOutcome.Found -> {
                        outcome = true to "Found: ${answer.hit.label}"
                        detail = answer.hit.detail
                    }

                    is DwLookupOutcome.Refused -> outcome = false to answer.message
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
                    // QrCode2, not Keyboard. The heading above this used to be "Type the code printed
                    // under the QR" and a keyboard was the honest picture of the only route there was;
                    // over a panel that now leads with a Scan button it would advertise the slowest of
                    // the three. The record-code panel on Search already uses this icon for the same
                    // panel, and the two surfaces should look like the one control they now are.
                    Icons.Filled.QrCode2,
                    contentDescription = null,
                    tint = MaterialTheme.field.muted,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "Open a record from its code",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            // THE SENTENCE THAT USED TO BE HERE WAS "This app does not read a QR from the camera —
            // reading one needs a scanner library this build deliberately does not ship." It was
            // true and is not any more; leaving it standing above a Scan button would be the screen
            // contradicting itself about its own feature, which is worse than either state.
            DwQrScanControl(
                enabled = !busy,
                onText = { text ->
                    // Put in the box as well as resolved, so a designer who scanned the wrong tag
                    // can see what was read and fix a character rather than meeting a refusal about
                    // a string the app never showed them.
                    typed = text
                    outcome = null
                    detail = null
                    lookUp(text)
                },
                onRefusal = { message ->
                    detail = null
                    outcome = false to message
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
                    // The 48dp floor this app applies wherever a control was thought about — see
                    // ISLAND_TOUCH_TARGET in ui/AppNavigation.kt.
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Look up")
                }
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp))
            }
            Text(
                "Spaces and capitals do not matter. The four characters at the end are a check — if they do " +
                    "not match, the app says so rather than opening the wrong record.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )

            outcome?.let { (found, message) ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Polite rather than a toast. A toast vanishes after five seconds, and both
                        // outcomes here are things the designer has to act on: go to the record, or go
                        // and read the card again.
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .background(
                            if (found) MaterialTheme.field.successContainer else MaterialTheme.field.warningContainer,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        message,
                        color = if (found) MaterialTheme.field.onSuccessContainer else MaterialTheme.field.onWarningContainer,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    detail?.let {
                        Text(
                            it,
                            color = if (found) MaterialTheme.field.onSuccessContainer else MaterialTheme.field.onWarningContainer,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Ask the repository about an artisan card that is not on this workshop's roster.
 *
 * ONE SENTENCE FOR TWO OUTCOMES, deliberately — see [unresolvedWorkshopCodeMessage]. A missing
 * connection is told apart from a refusal, because those two lead a designer to completely different
 * next actions: wait for signal, or go and read the card again. Retrofit gives that split for free —
 * an [HttpException] means the server answered, anything else means it was never reached.
 */
private suspend fun lookUpArtisan(repository: WorkshopRepository, id: String): DwLookupOutcome =
    try {
        val artisan = repository.artisan(id)
        DwLookupOutcome.Found(
            DwCardHit(
                label = artisan.name,
                // Said out loud, because it changes what the designer does next: this person is
                // documented but is not in this workshop, so enrolling them is the missing step.
                detail = (artisan.place.ifBlank { "In the repository" }) + " · not on this workshop's roster"
            )
        )
    } catch (e: HttpException) {
        DwLookupOutcome.Refused(unresolvedWorkshopCodeMessage(DwWorkshopRecordType.ARTISAN))
    } catch (e: Exception) {
        DwLookupOutcome.Refused(
            "That card is not on this workshop's roster on this device, and there is no connection to check " +
                "the repository. Try again when there is signal — the card itself is fine."
        )
    }

// --------------------------------------------------------------------------------------
// The sheet
// --------------------------------------------------------------------------------------

/** Where a written sheet landed, and how much of it there is. */
private data class SheetExport(
    val displayLocation: String,
    val shareUri: android.net.Uri?,
    val fileName: String,
    val pages: Int,
)

/**
 * Render the sheet into a cache file, flush it, then publish it through the repository.
 *
 * WRITE COMPLETELY BEFORE PUBLISHING, and `fd.sync()` before either — the same discipline
 * [com.designprototype.workshop.report.ReportExport] uses and for the same reason: the publish step
 * copies the temp file into a MediaStore row that becomes visible to every app the moment IS_PENDING
 * clears, and a copy whose bytes are still only in the page cache publishes a truncated PDF, which a
 * viewer reports as a damaged file. The temp file is deleted in a `finally` so a failed export does
 * not leave a half-drawn sheet in the cache of a phone that is already short of space.
 */
private suspend fun writeSheet(
    context: android.content.Context,
    repository: WorkshopRepository,
    kind: DwWorkshopRecordType,
    cards: List<DwCardRender>,
): SheetExport = withContext(Dispatchers.IO) {
    // The same stamp the report exports carry, so a designer's Downloads folder sorts the sheet
    // beside the report it was cut for.
    val stamp = DateTimeFormatter.ofPattern("ddMMyyyyHHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
    val name = "DesignWorkshop_${workshopRecordTypeLabel(kind)}Cards_$stamp.pdf"
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
        Text("Saved", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(sheet.displayLocation, color = MaterialTheme.field.body, fontSize = 12.sp)
        Text(
            "${sheet.pages} A4 page${if (sheet.pages == 1) "" else "s"}",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )
        if (sheet.shareUri != null) {
            OutlinedButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, sheet.shareUri)
                        // Without this the receiving app gets a Uri it has no permission to read, and
                        // the share silently produces an empty attachment.
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
            // On Android 10 and above the file lands in the public Downloads collection and every app
            // can already open it from there, so there is no Uri to grant and nothing to share FROM
            // this app. Saying where it is beats offering a button that would have to re-copy the file.
            Text(
                "Open it from the Downloads folder, or attach it from any app's file picker.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp
            )
        }
    }
}

// --------------------------------------------------------------------------------------
// Small parts
// --------------------------------------------------------------------------------------

/**
 * One half of the "what to print" pair.
 *
 * The filled/outlined difference is the visible state and `selected` is the same fact for a screen
 * reader — colour alone never carries meaning in this app, and a designer using TalkBack would
 * otherwise hear two identical buttons with no way to tell which sheet is on screen. The 48dp floor is
 * the same one ISLAND_TOUCH_TARGET applies in ui/AppNavigation.kt.
 */
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

/**
 * A stated fact about what is on screen, on the warning fill the rest of this feature uses.
 *
 * `internal` rather than private to this file, and named for the FEATURE rather than for the card
 * sheet, because [PhotoIntakeScreen] says the same class of thing — "this workshop has not been
 * downloaded to this device yet" — and a second copy of an amber row is a second copy that drifts:
 * the two notices would end up on different fills, at different sizes, in one feature a designer
 * moves between within a workshop.
 */
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
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
    }
}
