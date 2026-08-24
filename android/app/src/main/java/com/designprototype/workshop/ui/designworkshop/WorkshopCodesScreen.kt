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
 * grammar, the check digit and the whole QR encoder — was already on this device in
 * [DwWorkshopCodes] and [DwQrEncode] BEFORE THIS SCREEN EXISTED, with nothing calling either of
 * them; this screen is what calls them, and it is reached from `MainActivity`'s
 * `Screen.DesignWorkshopCodes` arm. (The clause used to read "with nothing calling either of them"
 * in the present tense, in the file that is their caller.)
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
 * ── POINT 4 GREW A LIVE PREVIEW ON 2026-08-24, AND THE REASON WAS A DEFECT ────────────────────
 *
 * Not "a live scanner is nicer". `ActivityResultContracts.TakePicture()` hands the job to the SYSTEM
 * camera app, which reopens whatever lens IT last used, so "Scan a code" showed designers the FRONT
 * camera — and the lens cannot be forced through that contract at all. `ui/DwQrLiveScanner.kt` binds
 * `CameraSelector.DEFAULT_BACK_CAMERA` explicitly, on every bind, with a reticle to line the code up
 * in and live detection. CameraX is what that cost: four artifacts, 2,059,824 measured AAR bytes,
 * argued in full in `android/app/build.gradle.kts`.
 *
 * BOTH OLD BUTTONS SURVIVE UNCHANGED AND ARE NOT FALLBACKS FOR EACH OTHER. A photograph decodes at
 * FULL resolution through `DW_QR_SAMPLE_LADDER`, which reads a small or dim code the live view
 * cannot; a picked picture is the only route for a code somebody was SENT. Four doors on this panel,
 * counting the typed box, and no permission is needed for two of them.
 *
 * ── AND THERE IS A THIRD KIND OF CARD: THE WORKSHOP'S OWN ────────────────────────────────────
 *
 * `DwWorkshopRecordType.DESIGN_WORKSHOP` had no show affordance anywhere on this handset — every one
 * of its four appearances in `main/` was a refusal or a predicate — so the one code that is about
 * PEOPLE rather than about a record was the one code the app could not draw. It is a third kind
 * beside "Prototype tags" and "Artisan cards" now. Scanning it asks an administrator to put somebody
 * on this workshop (`ui/DwWorkshopJoin.kt`); it admits nobody by itself, and
 * `designWorkshopCardPurposeMessage` is printed beside it saying so.
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

    /**
     * Ask to be put on one design workshop, from a card that has just been read.
     *
     * ── ONE IMPLEMENTATION, AND THE CODE IS RE-ENCODED RATHER THAN PASSED THROUGH ─────────────
     *
     * `scannedCode` must be the CANONICAL spelling — upper case, no grouping spaces — because the
     * server decodes it with its own copy of this grammar and compares the id inside it to the
     * `workshopId` beside it. Sending back what a designer typed under a tin roof ("dpw1 g…") would
     * earn a 422 about the body for no reason at all. Re-encoding from the id that
     * `decodeWorkshopCode` already validated is the one spelling that cannot be wrong.
     *
     * ── AND IT IS NOT SHORT-CIRCUITED WHEN THE CARD NAMES THIS VERY WORKSHOP ──────────────────
     *
     * Worth stating because it looks like an obvious optimisation. Being on this SCREEN is not the
     * same as being on the WORKSHOP, and the server is the only thing that knows which: `file_request`
     * answers a member's re-ask as a no-op that does not move anybody's queue position, and it answers
     * one uniform sentence for all seven of its outcomes on purpose. Deciding here would be a second
     * opinion made with less information, and the one this screen could give is the wrong kind of
     * confident.
     */
    suspend fun joinDesignWorkshop(targetWorkshopId: String): DwLookupOutcome {
        val canonical = (
            encodeWorkshopCode(DwWorkshopRecordType.DESIGN_WORKSHOP, targetWorkshopId) as? DwEncodeResult.Ok
            )?.code
        return when (val joined = dwJoinDesignWorkshop(appContext, targetWorkshopId, canonical.orEmpty())) {
            // THE SERVER'S OWN SENTENCE, SHOWN AS GIVEN — see `ui/DwWorkshopJoin.kt`'s rule 1.
            is DwJoinOutcome.Asked -> DwLookupOutcome.Noted(joined.detail)
            is DwJoinOutcome.Queued -> DwLookupOutcome.Noted(joined.message)
            is DwJoinOutcome.Refused -> DwLookupOutcome.Refused(joined.message)
            // UNREACHABLE: this is the ASK path, and an ask cannot admit anybody. Written out rather
            // than left to an `else` so that crossing the two paths is a compiler error and not a
            // screen quietly reporting an induction as an ask. A join card takes the other branch of
            // `readWorkshopScan` entirely and never reaches this function.
            is DwJoinOutcome.Inducted -> DwLookupOutcome.Noted(joined.message)
        }
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

        /*
         * What to print. Buttons rather than a segmented control, matching the web's pair and the
         * rest of this app's toggles, and each states its own pressed state for a screen reader.
         *
         * THE THIRD ONE IS NOT LIKE THE OTHER TWO and it is deliberately last. "Prototype tags" and
         * "Artisan cards" are sheets of many; the workshop's own code is ONE card, it names the
         * workshop rather than a row inside it, and scanning it asks to join rather than opening
         * anything. Putting it first would suggest it is the ordinary case.
         */
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
                    /*
                     * FIRST, AND ABOVE THE SCHEMA CHECK, WHICH IS THE WHOLE POINT OF PUTTING IT HERE.
                     *
                     * Joining a workshop needs no field registry: it is one POST carrying an id and a
                     * code. Left below the `currentSchema == null` arm, a designer handed a card in a
                     * courtyard where the registry had never downloaded would be told "this workshop
                     * could not be read on this device, so there is nothing to match it against" —
                     * a sentence about matching a tag against a prototype list, answering a request
                     * that has nothing to do with one. That is the shape of the `else -> lookUpArtisan`
                     * bug this same `when` already carries a paragraph about: a branch written when
                     * the grammar had fewer letters, quietly answering for a case it never saw.
                     */
                    ref.recordType == DwWorkshopRecordType.DESIGN_WORKSHOP -> joinDesignWorkshop(ref.id)

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

                        // UNREACHABLE, AND WRITTEN OUT RATHER THAN LEFT TO AN `else`. `Join` is
                        // answered for `DESIGN_WORKSHOP` and for nothing else, and that record type
                        // is taken by the FIRST arm of this `when` — above the schema check, for the
                        // reason stated there. Spelling it here keeps the exhaustiveness check that
                        // caught `G` in the first place, and `joinDesignWorkshop` is the ONE
                        // implementation of the act rather than a second copy of it three branches
                        // down. If this ever fires, `lookUpRecordCode` has grown a record type and the
                        // first arm is what needs widening.
                        is RecordCodeOutcome.Join -> joinDesignWorkshop(answer.workshopId)
                    }
                }
            }
        )

        HorizontalDivider()

        /*
         * THE WORKSHOP'S OWN CARD, AND IT LEAVES BEFORE THE TWO GUARDS BELOW.
         *
         * Both of them are about a CARD SOURCE in the field registry — "this version of the app
         * cannot find the prototype list", "no prototypes have been recorded yet" — and neither is
         * true or even meaningful of the workshop's own code, which needs nothing but the workshop's
         * id. `workshopCardSource(DESIGN_WORKSHOP, schema)` answers null, so leaving this below would
         * tell a designer to update the app because a registry section that was never supposed to
         * exist is missing.
         */
        if (kind == DwWorkshopRecordType.DESIGN_WORKSHOP) {
            val serverId = draft?.remoteId ?: workshopId
            DwDesignWorkshopCodeCard(serverId)
            /*
             * AND BELOW THE TAG, THE CARD THAT ACTUALLY LETS SOMEBODY IN.
             *
             * THE TWO ARE DELIBERATELY ADJACENT AND DELIBERATELY DIFFERENT, and the order is the same
             * argument the "cards & tags" buttons make about themselves: the tag is the ordinary thing,
             * so it comes first. A join card is a credential and it is minted on demand, one at a time,
             * by a person who has decided to admit somebody — so it is a button and not a symbol that
             * is simply there.
             *
             * ONLY FOR A WORKSHOP THE SERVER KNOWS ABOUT. A card is minted BY the server against a
             * real row, so a device-local draft has nothing to mint against — [DwJoinCardsPanel] says
             * so rather than offering a button that can only fail.
             */
            DwJoinCardsPanel(serverId)
            Spacer(Modifier.padding(bottom = 8.dp))
            return@Column
        }

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

    /**
     * Something happened that is neither a hit nor a refusal — today, an ask to join a workshop.
     *
     * A THIRD ARM AND NOT A [Found] WITH A DIFFERENT LABEL. [Found] is rendered under the word
     * "Found:" in the GREEN container, and both halves of that would be wrong here: nothing was
     * found, and nothing is finished — an administrator has still to decide, and on the offline
     * branch nothing has even been sent. The server's own sentence is conditional for exactly that
     * reason ("IF that workshop exists and you are not already on it"), and painting a conditional
     * green is how somebody stops waiting for the thing they are waiting for.
     *
     * Not a [Refused] either: a queued ask is not a failure, and the amber container would tell a
     * designer their card had not worked when it had.
     */
    data class Noted(val message: String, val detail: String? = null) : DwLookupOutcome
}

/**
 * Read one code back: scanned, picked out of a picture, or typed.
 *
 * ALL THREE ROUTES END IN `readWorkshopScan`, which is the point. A payment QR photographed by
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
    val appContext = LocalContext.current.applicationContext
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Pair<DwPanelTone, String>?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }

    /**
     * DRAIN THE OFFLINE QUEUE WHEN THIS PANEL APPEARS.
     *
     * BELT AND BRACES, NOT THE MECHANISM — `DwInductionFlusher` runs from a process-wide network
     * callback and at process start, which is what makes `dwJoinQueuedMessage`'s promise true. This
     * covers the one case that callback cannot: the designer who scanned offline, force-stopped the
     * app, and came back to this screen already on wifi. It is idempotent (the flusher takes a
     * process-wide latch) and it reports nothing, because a panel announcing the outcome of a scan the
     * designer made two days ago would be a claim they did not ask for.
     */
    LaunchedEffect(Unit) { DwInductionFlusher.flushNow(appContext) }

    fun lookUp(input: String) {
        if (input.isBlank() || busy) return
        busy = true
        detail = null
        scope.launch {
            // ONE FRONT DOOR FOR BOTH GRAMMARS — see `DwWorkshopCodes.readWorkshopScan`. A JOIN CARD
            // must never reach `decodeWorkshopCode`, whose version gate answered a genuine v2 card
            // with "update the app to read it" against an app that did not exist.
            when (val scan = readWorkshopScan(input)) {
                is DwWorkshopScan.Refused -> outcome = DwPanelTone.BAD to scan.message
                is DwWorkshopScan.JoinCard -> {
                    // NOT PUT BACK IN THE BOX, unlike a record code. The payload is a live credential
                    // and the box is visible, copyable and survives recomposition.
                    typed = ""
                    outcome = DwPanelTone.NOTED to designWorkshopJoinCardRedeemingMessage()
                    when (val answer = dwScanJoinCard(appContext, scan.card.workshopId, scan.card.code)) {
                        // GREEN ONLY FOR ACTUAL MEMBERSHIP. A provisional foothold is NOT membership
                        // and must never be painted as one — see `DwJoinOutcome.Inducted.fullMember`.
                        is DwJoinOutcome.Inducted -> outcome =
                            (if (answer.fullMember) DwPanelTone.GOOD else DwPanelTone.NOTED) to answer.message
                        is DwJoinOutcome.Queued -> outcome = DwPanelTone.NOTED to answer.message
                        is DwJoinOutcome.Refused -> outcome = DwPanelTone.BAD to answer.message
                        // Unreachable: a card is redeemed, never asked. Written out so the compiler
                        // notices if the two paths are ever crossed.
                        is DwJoinOutcome.Asked -> outcome = DwPanelTone.NOTED to answer.detail
                    }
                    busy = false
                    return@launch
                }
                is DwWorkshopScan.RecordCode -> {
                    // SAID BEFORE THE ASK, NOT AFTER IT. A join goes over the network and can take
                    // seconds on a village connection, and a panel that showed nothing at all for
                    // those seconds is a panel a designer presses again.
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
            /*
             * TWO CONTROLS AND NOT ONE, AND THAT IS A WAVE BOUNDARY RATHER THAN A DESIGN. The live
             * scanner belongs INSIDE `DwQrScanControl` — one control, three surfaces, one refusal
             * wording, which is that file's own stated rule — and that file is not this wave's to
             * edit. Mounting it beside the existing control is the additive move: nothing about the
             * photograph or the picked-picture routes changes, no refusal sentence is duplicated
             * (`DwQrLiveScanControl` uses `DwCameraRefusal.kt`'s), and the next wave's change is one
             * call and one deletion.
             */
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
                        // Polite rather than a toast. A toast vanishes after five seconds, and every
                        // outcome here is something the designer has to act on: go to the record, wait
                        // for an administrator, or go and read the card again.
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .background(container, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(message, color = ink, fontSize = 13.sp, lineHeight = 18.sp)
                    detail?.let { Text(it, color = ink, fontSize = 11.sp) }
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

// --------------------------------------------------------------------------------------
// The workshop's own card
// --------------------------------------------------------------------------------------

/**
 * Which of the three containers an answer is painted in.
 *
 * AN ENUM AND NOT THE `Boolean` IT REPLACED. The panel used to carry `Pair<Boolean, String>`, which
 * had exactly two colours available: green for a hit and amber for everything else. A join is neither
 * — it is a thing that has been asked and not yet decided, and both of the old colours state
 * something false about it. A third state that a boolean cannot hold is the definition of a case for
 * an enum, and Postgres's reasoning applies to Kotlin here for once: a typo is a compile error where
 * `false` was merely wrong quietly.
 */
private enum class DwPanelTone { GOOD, NOTED, BAD }

/**
 * The design workshop's own code, drawn on screen so it can be photographed off it.
 *
 * ── WHAT THIS IS FOR, AND WHY IT IS THE ONE CARD WITH A PARAGRAPH BESIDE IT ───────────────────
 *
 * Every other card this screen prints NAMES a record: a designer scans it to open the right prototype
 * or the right artisan. This one is handed to a PERSON so that they can ask to be put on the
 * workshop — so unlike the other nine it has a consequence for somebody's access, and two things
 * about that consequence are routinely got wrong. `designWorkshopCardPurposeMessage` says both: that
 * scanning ASKS rather than admits, and that the code is not a password. It is printed under the
 * symbol rather than left to be discovered.
 *
 * ── ON SCREEN AND NOT IN A PDF, WHICH IS A DECISION ───────────────────────────────────────────
 *
 * The other two kinds write an A4 sheet of many cards, at the millimetres they are cut to. One code
 * does not want a sheet: it is shown to the phone standing next to you, or it is read aloud, or the
 * printed line under it is typed. So this offers the symbol at a size a second camera can read off
 * this screen, and the code in the print grouping so it can be spoken and typed back. A sheet with a
 * single 26mm card on it would be a page of white paper.
 *
 * ── THE BRIGHTNESS, WHICH IS THE DIFFERENCE BETWEEN THIS WORKING AND NOT ──────────────────────
 *
 * A QR shown on a handset to be photographed by another handset fails on GLARE, not on encoding, and
 * a field phone in a courtyard is usually sitting at whatever brightness the auto sensor chose for a
 * form. [DwHoldScreenBright] raises the window to full while this card is on screen and puts it back
 * on the way out. No dependency, and it is the single cheapest thing that makes the feature work
 * outdoors.
 *
 * ── A DEVICE-LOCAL WORKSHOP IS REFUSED, NOT DRAWN ─────────────────────────────────────────────
 *
 * A workshop created with no signal has an id only this device can resolve (`DW_LOCAL_ID_PREFIX`),
 * and a card naming one would resolve to nothing on every other phone for ever — which is exactly the
 * divergence a shared code exists to prevent, and `DwWorkshopCodes` refuses it at the encoder with a
 * sentence. That sentence is rendered as prose here rather than being second-guessed: the refusal is
 * the encoder's and there is one copy of it.
 */
@Composable
private fun DwDesignWorkshopCodeCard(workshopId: String) {
    val render = remember(workshopId) {
        when (val encoded = encodeWorkshopCode(DwWorkshopRecordType.DESIGN_WORKSHOP, workshopId)) {
            is DwEncodeResult.Refused -> null to encoded.message
            is DwEncodeResult.Ok -> runCatching {
                // Level Q, the same as every other card this app draws, and for the reason
                // `RecordCodeCard` gives: two surfaces drawing one record at two levels produce two
                // different-looking symbols for one record, which is the sort of thing that makes
                // somebody doubt a scan they should have trusted.
                Triple(
                    DwQrEncode.encode(encoded.code, DwQrEccLevel.Q),
                    encoded.code,
                    formatWorkshopCodeForPrint(encoded.code),
                )
            }.fold(
                onSuccess = { it to null },
                onFailure = { null to (it.message ?: "This code could not be drawn.") },
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

    // Only while a symbol is actually on screen. A brightened window left behind on a form is a
    // battery complaint from a designer who will never connect it to this screen.
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
            // 220dp rather than the 96dp of a preview card: this symbol is not being previewed, it is
            // being READ, by a camera held across a table. The 33-module version-4 symbol this code
            // produces then has about 6 device pixels per module before any scaling the other phone's
            // lens applies.
            DwQrSymbolImage(
                symbol = drawn.first,
                label = "Code for this design workshop",
                modifier = Modifier.size(220.dp),
            )
            Text(
                drawn.third,
                color = MaterialTheme.field.body,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                // Monospace, because this string is read aloud and typed back and the grouping is
                // only legible when the groups line up.
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )
            Text(
                designWorkshopCardPurposeMessage(),
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Text(
                "The screen is at full brightness while this is open so another phone can read it. " +
                    "If the camera will not read it, the line above can be typed into “Open a record " +
                    "from its code” on the other phone instead — it is the same code.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

/**
 * JOIN CARDS for this workshop: print one, see the ones that exist, cancel one.
 *
 * ── WHY THIS SCREEN EXISTS AT ALL, WHICH IS THE FINDING RATHER THAN THE FEATURE ───────────────
 *
 * `POST /design-workshop-access/grants`, `GET /grants/{recordId}` and `POST /grants/{id}/revoke`
 * shipped complete and **UNREACHABLE**: nothing on any surface minted a card, nothing displayed one,
 * and nothing redeemed one. A whole credential system — single-use seats, a cap on outstanding cards,
 * revocation, an issuer trail — existed with no door. This panel is the door for the first three, and
 * `readWorkshopScan` plus `dwScanJoinCard` are the door for the fourth.
 *
 * ── THE SECRET IS SHOWN ONCE AND IS NEVER STORED, WHICH SHAPES THE WHOLE LAYOUT ───────────────
 *
 * `mint_grant` answers with the code and after that the database holds only `sha256(secret)` and the
 * last four characters. So [minted] lives in composition state and nowhere else: it is not written to
 * the draft, not put in a text field, and not re-fetched — the list below CANNOT show it, because the
 * server cannot tell anybody what it was. That is why the freshly minted card is drawn large, with
 * the printable characters under it, and with a sentence saying it will not be shown again.
 *
 * ── AND WHY THIS IS NOT ADMIN-GATED ───────────────────────────────────────────────────────────
 *
 * The courtyard case is the entire motivation: somebody already on the workshop hands a card to the
 * person standing next to them, because there is no administrator within two districts. So the route
 * is `Depends(get_current_user)` and the LIMITS are what make that safe — single-use only, three
 * unused cards outstanding at a time, every card visible here with its issuer and revocable. A
 * designer who asks for more is answered by the server with a sentence naming the screen that can do
 * it, and this panel shows that sentence rather than second-guessing it.
 */
@Composable
private fun DwJoinCardsPanel(workshopId: String) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    /**
     * The card just minted, **secret included**, held for exactly as long as it is on screen.
     *
     * DELIBERATELY NOT HOISTED, NOT SAVED AND NOT `rememberSaveable`. A saveable would put a live
     * credential into the activity's saved-instance bundle, which is written to disk by the platform
     * on a low-memory kill; losing the symbol on a rotation is the correct trade, and the card is
     * still on paper if it was printed.
     */
    var minted by remember(workshopId) { mutableStateOf<DwJoinCardDto?>(null) }
    var cards by remember(workshopId) { mutableStateOf<List<DwJoinCardDto>>(emptyList()) }
    var truncated by remember(workshopId) { mutableStateOf(false) }
    var listed by remember(workshopId) { mutableStateOf(false) }
    var busy by remember(workshopId) { mutableStateOf(false) }
    var notice by remember(workshopId) { mutableStateOf<String?>(null) }

    // A DEVICE-LOCAL DRAFT HAS NOTHING TO MINT AGAINST. Said here rather than left to the server's
    // 404, which for this id would be indistinguishable from "you may not print cards for that
    // workshop" — the uniformity that keeps minting from being an existence oracle, and exactly the
    // wrong sentence for a workshop that simply has not synced yet.
    if (isLocalOnlyWorkshop(workshopId)) {
        DwWorkshopNotice(
            "This workshop has not been sent to the server yet, so a join card cannot be printed for " +
                "it. A card is made by the server and checked against the real workshop — it is a key " +
                "rather than something this device can invent. Sync this workshop first."
        )
        return
    }

    /**
     * Run one card action and fold its answer into this panel's state.
     *
     * NAMED `perform` AND NOT `run`, which was the first spelling and is a trap: `kotlin.run` is a
     * stdlib function with the same shape, overload resolution happens before the lambda body is
     * analysed, and a local `run` taking a SUSPEND lambda beside a stdlib `run` taking a plain one is
     * how a call site comes to resolve to the wrong function and fail with "suspension functions can
     * be called only within coroutine body" — a message that points at the lambda rather than at the
     * name.
     */
    fun perform(action: suspend () -> DwJoinCardAction) {
        if (busy) return
        busy = true
        notice = null
        scope.launch {
            when (val answer = action()) {
                is DwJoinCardAction.Minted -> {
                    minted = answer.card
                    // THE LIST IS REFRESHED RATHER THAN APPENDED TO, because `usesConsumed` on every
                    // other card may have moved since it was read and a stale row here is a card an
                    // admin believes is still unused.
                    listed = false
                }
                is DwJoinCardAction.Listed -> {
                    cards = answer.cards
                    truncated = answer.truncated
                    listed = true
                }
                is DwJoinCardAction.Revoked -> {
                    // THE MINTED CARD IS DROPPED IF IT WAS THE ONE CANCELLED. Leaving a symbol on
                    // screen for a card that no longer works is how somebody hands over a dead card.
                    if (minted?.id == answer.card.id) minted = null
                    listed = false
                }
                is DwJoinCardAction.Refused -> notice = answer.message
                is DwJoinCardAction.Offline -> notice = answer.message
            }
            busy = false
        }
    }

    // LISTED ON DEMAND AND ON EVERY CHANGE, not once: `listed` is reset by a mint and by a revoke, so
    // the effect re-runs and the counts a person is about to act on are the server's current ones.
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
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                "A join card puts one person on this workshop the moment they scan it — no " +
                    "administrator, no waiting. Print one for the person in front of you, hand it " +
                    "over, and it stops working once they have used it.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { perform { dwMintJoinCard(context, workshopId) } },
                    enabled = !busy,
                    // The 48dp floor this app applies wherever a control was thought about — see
                    // ISLAND_TOUCH_TARGET in ui/AppNavigation.kt.
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Print a join card")
                }
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp))
            }

            notice?.let {
                Text(
                    it,
                    color = MaterialTheme.field.onWarningContainer,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Polite rather than a snackbar: both the 403 about multi-use cards and the
                        // 409 about the outstanding cap name another action, and a message that
                        // disappears after four seconds is a message somebody has to guess at.
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
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (truncated) {
                    // SAID OUT LOUD, on the route's own instruction: "a card an admin cannot see is a
                    // card they cannot revoke". Silently showing the first two hundred would leave
                    // somebody certain they had cancelled everything.
                    DwWorkshopNotice(
                        "There are more cards than this list can show, so some are not here. Ask an " +
                            "administrator to review them on the web if you need to cancel one you " +
                            "cannot see."
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

/**
 * The card that has just been minted — the ONE moment its secret exists anywhere but on paper.
 *
 * DRAWN AT 220dp for the reason `DwDesignWorkshopCodeCard` gives: this symbol is not being previewed,
 * it is being READ, by a camera held across a table, and the 33-module version-4 symbol a 60-character
 * card produces then has about six device pixels per module before the other phone's lens scales it.
 *
 * THE PRINTABLE CHARACTERS ARE UNDER IT, in monospace and in groups of four, because the camera fails
 * often enough that the typed route is not a fallback but a route — and this string is the only form
 * of the card that survives a smudged symbol.
 *
 * ⚠ AND IT SAYS THE CARD WILL NOT BE SHOWN AGAIN, which is not a nicety: the server keeps only
 * `sha256(secret)` and the last four characters, so a designer who closes this without printing or
 * writing it down has lost a card and used one of their three outstanding slots on it. The remedy is
 * to cancel it and print another, which the list below can do.
 */
@Composable
private fun DwFreshJoinCard(card: DwJoinCardDto) {
    val code = card.code
    if (code.isNullOrBlank()) return

    val render = remember(code) {
        runCatching {
            // Level Q, the same as every other card this app draws — see `DwDesignWorkshopCodeCard`:
            // two surfaces drawing at two levels produce two different-looking symbols for one card,
            // which is the sort of thing that makes somebody doubt a scan they should have trusted.
            DwQrEncode.encode(code, DwQrEccLevel.Q) to formatWorkshopCodeForPrint(code)
        }.getOrNull()
    }
    if (render == null) {
        DwWorkshopNotice(
            "The card was made but this device could not draw its symbol. The line of characters " +
                "below is the whole card and can be typed in instead."
        )
    }

    // Only while a symbol is actually on screen — the same rule the workshop's own card keeps.
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
                fontSize = 13.sp,
                lineHeight = 18.sp,
                // Monospace, because this string is read aloud and typed back and the grouping is
                // only legible when the groups line up.
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )
            Text(
                designWorkshopJoinCardPurposeMessage(),
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Text(
                "This is the only time this card will be shown. The server keeps no copy of it — only " +
                    "the last four characters, so a card in somebody's hand can be matched against " +
                    "the list. Print it, photograph it for the person it is for, or write it down " +
                    "now; if you lose it, cancel it below and print another.",
                color = MaterialTheme.field.onWarningContainer,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.warningContainer, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * One existing card in the list. **It cannot show the secret and does not pretend to.**
 *
 * `secretLast4` is twenty bits — enough to match the card in somebody's hand and useless to a guesser
 * — and it is the only part of the secret the server holds. The three states a person acts on are
 * spelled in words rather than left to be inferred from three nullable columns: cancelled, used up,
 * out of date, or still good.
 */
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
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                state,
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // OFFERED ONLY WHILE IT COULD STILL LET SOMEBODY IN. Revoking is idempotent on the server, so
        // this is about not offering an action with no effect rather than about safety.
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

/**
 * What a card's state is, in the words somebody acts on. PURE, so `DwJoinCardStateTest` pins it.
 *
 * ── THE ORDER OF THE BRANCHES IS THE MEANING ──────────────────────────────────────────────────
 *
 * CANCELLED FIRST, because it beats everything: `redeem` refuses a revoked card before it looks at
 * expiry or at seats, so a cancelled card that is also expired is cancelled. USED UP next, because a
 * spent card is spent whether or not its date has passed. Only then the date.
 *
 * ⚠ AND IT SAYS WHAT CANCELLING DOES **NOT** DO, on the route's own insistence: revoking stops the
 * card admitting anybody FURTHER and removes nobody it has already let in. An admin who believed
 * otherwise would cancel a misprinted batch and think they had removed the colleagues who used it.
 */
internal fun dwJoinCardState(card: DwJoinCardDto, nowMs: Long): String {
    val issuer = card.issuedBy?.name?.takeIf { it.isNotBlank() }
    val by = if (issuer == null) "" else " Printed by $issuer."
    val label = card.label?.takeIf { it.isNotBlank() }?.let { " $it." } ?: ""
    if (card.revokedAt != null) {
        return "Cancelled. It will not let anybody else in — anybody it already admitted is still " +
            "on the workshop, and taking that away is the viewers screen.$by$label"
    }
    val ceiling = card.maxUses
    if (ceiling != null && card.usesConsumed >= ceiling) {
        return "Used up. Somebody has already joined with it, so it will not let anybody else in.$by$label"
    }
    // AN UNREADABLE OR ABSENT DATE IS NOT TREATED AS EXPIRED. The column is NOT NULL on the server, so
    // a null here means this build could not read what it was sent — and reporting a perfectly good
    // card as out of date would send somebody to print a replacement they do not need.
    val expiresAtMs = card.expiresAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
    if (expiresAtMs != null && expiresAtMs <= nowMs) {
        // WORDED AS A PROBABILITY RATHER THAN A CERTAINTY, because the server does not refuse an
        // expired card outright: a genuine scan that syncs within the 30-day grace window still gets
        // the person a provisional foothold and a place in the admin's queue. Saying "it will not
        // work" would be wrong in the direction that makes somebody throw a card away.
        return "Out of date. Somebody scanning it now will not be let straight in, though a scan " +
            "taken before it lapsed can still reach an administrator.$by$label"
    }
    val remaining = if (ceiling == null) "any number of people" else "${ceiling - card.usesConsumed} more person"
    return "Still good for $remaining.$by$label"
}

/**
 * Hold this window at full brightness while the composable is on screen, and restore it after.
 *
 * ── WHY THE ACTIVITY WALK IS WRITTEN OUT AGAIN HERE ───────────────────────────────────────────
 *
 * `DwCameraRefusal.kt` has this exact loop, `private`, with a comment explaining that
 * `LocationCapture.kt` and `Theme.kt` each keep their own private copy because promoting one would
 * leave two same-signature top-level functions visible in a package where the private one is still
 * declared. This is the same situation from the other side: that copy cannot be reached from here and
 * that file is not this wave's to edit. It is four lines and it has no behaviour to drift.
 *
 * ── AND EVERY PART OF IT FAILS SOFTLY ─────────────────────────────────────────────────────────
 *
 * No activity behind the context, a window the platform will not let us touch — none of that is worth
 * an exception on a screen whose job is to draw a QR. The symbol is still correct at whatever
 * brightness the device chose; it is just harder to photograph.
 *
 * `screenBrightness = -1f` (`BRIGHTNESS_OVERRIDE_NONE`) is the restore value and not the value read
 * on entry, deliberately: reading and restoring an explicit number would pin the window to whatever
 * the auto-brightness sensor happened to have chosen at that instant, in a courtyard, for the rest of
 * the session. -1 hands control back to the system, which is what "leave it as it was" actually means
 * on Android.
 */
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

/** The Activity behind a Compose context — see [DwHoldScreenBright] on why this loop is here again. */
private fun android.content.Context.dwCodeHostActivity(): android.app.Activity? {
    var cursor: android.content.Context? = this
    while (cursor is android.content.ContextWrapper) {
        if (cursor is android.app.Activity) return cursor
        cursor = cursor.baseContext
    }
    return null
}
