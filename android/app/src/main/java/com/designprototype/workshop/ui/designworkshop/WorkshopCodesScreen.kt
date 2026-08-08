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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.draw.drawBehind
import com.designprototype.workshop.data.DwCardHit
import com.designprototype.workshop.data.DwCardRender
import com.designprototype.workshop.data.DwCardSource
import com.designprototype.workshop.data.DwDecodeResult
import com.designprototype.workshop.data.DwQrSymbol
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
import com.designprototype.workshop.ui.Text
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
 *  4. IT DOES NOT OPEN THE CAMERA, and that is a decision rather than a gap. Android has no
 *     `BarcodeDetector`; decoding a QR needs ML Kit or ZXing, which is a new transitive dependency in
 *     an APK that ships to field handsets over a village connection — method count in a 64K-limited
 *     dex, a second implementation of a standard this repository already implements once, and a
 *     download every designer pays for. The web's own scanner header settles the same argument the
 *     same way for the browsers with no native detector: **the typed code is a shorter path to the
 *     same record and it works everywhere, today, with nothing added.** So the manual box is not a
 *     fallback here, it is the route — and it is never hidden, exactly as it is never hidden there.
 *     A camera path is worth adding the day somebody measures how long a designer spends typing; it
 *     is not worth adding on the strength of it being what a scanner usually looks like.
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

        deviceOnlyNote?.let { DwCardNotice(it) }

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
                    else -> lookUpArtisan(repository, ref.id)
                }
            }
        )

        HorizontalDivider()

        if (source == null) {
            // NOT the same as "there is nothing to print", and confusing the two would send a designer
            // looking for rows they never entered instead of reporting a build that is out of date.
            DwCardNotice(
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

/**
 * A QR symbol, drawn straight onto the canvas from [DwQrEncode]'s module matrix.
 *
 * NO LIBRARY AND NO BITMAP. `drawBehind` runs on the draw pass rather than composition, so scrolling a
 * sheet of thirty tags repaints rather than recomposes, and nothing is allocated per frame.
 *
 * THE QUIET ZONE IS DRAWN, not assumed: four light modules on every side are what a scanner uses to
 * find the symbol at all, and a symbol laid straight onto a tinted card has none. The light modules
 * are painted WHITE rather than in a theme colour for the same reason a printed card is white — this
 * is a depiction of paper, and a symbol inverted for dark mode is a symbol most scanners refuse.
 *
 * The module size is computed from the box and each run is drawn on absolute boundaries rather than by
 * accumulating a step, so rounding cannot open a hairline between two runs of one row — a seam across
 * a module is exactly the artefact that makes a camera hesitate.
 */
@Composable
private fun DwQrSymbolImage(symbol: DwQrSymbol, label: String, modifier: Modifier = Modifier) {
    val quiet = 4
    val extent = symbol.size + quiet * 2
    Box(
        modifier = modifier
            .background(Color.White, RoundedCornerShape(2.dp))
            // The symbol is an image with meaning — a designer using TalkBack needs to know which card
            // this one is, and "QR code" alone would say the same thing thirty times.
            .semantics { contentDescription = label }
            .drawBehind {
                val unit = size.minDimension / extent
                for (row in 0 until symbol.size) {
                    var column = 0
                    while (column < symbol.size) {
                        if (!symbol.matrix[row][column]) {
                            column++
                            continue
                        }
                        var run = 1
                        while (column + run < symbol.size && symbol.matrix[row][column + run]) run++
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset((column + quiet) * unit, (row + quiet) * unit),
                            size = Size(run * unit, unit)
                        )
                        column += run
                    }
                }
            }
    )
}

// --------------------------------------------------------------------------------------
// Reading a code back
// --------------------------------------------------------------------------------------

/** What the host made of a reference. A refusal must never confirm that the record exists. */
sealed interface DwLookupOutcome {
    data class Found(val hit: DwCardHit) : DwLookupOutcome
    data class Refused(val message: String) : DwLookupOutcome
}

/**
 * The typed-code route — the one that works on every device, and therefore the one that is never
 * hidden. See the screen's header for why there is no camera beside it.
 *
 * Tolerant of what a human does to a code and strict about what it means: spaces and capitals are
 * stripped by [decodeWorkshopCode] before the parse, because a designer copying a code off a card
 * under a tin roof will not reproduce them and refusing over a space would be a refusal about nothing.
 */
@Composable
private fun DwCodeLookupPanel(resolve: suspend (DwWorkshopCodeRef) -> DwLookupOutcome) {
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }

    fun lookUp() {
        val input = typed
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
                    Icons.Filled.Keyboard,
                    contentDescription = null,
                    tint = MaterialTheme.field.muted,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "Type the code printed under the QR",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                // The honest sentence, in the place the web puts its own. It says what this build does
                // and does not do, without implying a broken camera or a missing permission.
                "This app does not read a QR from the camera — reading one needs a scanner library this " +
                    "build deliberately does not ship. Typing the code does exactly the same thing, and it " +
                    "is what the code is printed for.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp
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
                    onClick = { lookUp() },
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

/** A stated fact about what is on screen, on the warning fill the rest of this feature uses. */
@Composable
private fun DwCardNotice(message: String) {
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
