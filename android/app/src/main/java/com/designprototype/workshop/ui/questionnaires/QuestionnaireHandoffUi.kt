package com.designprototype.workshop.ui.questionnaires

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwQrEccLevel
import com.designprototype.workshop.data.DwQrEncode
import com.designprototype.workshop.data.QUESTIONNAIRE_BUNDLE_ADOPT_NOTICE
import com.designprototype.workshop.data.QUESTIONNAIRE_BUNDLE_CONTENTS_NOTICE
import com.designprototype.workshop.data.QUESTIONNAIRE_BUNDLE_EXTENSION
import com.designprototype.workshop.data.QUESTIONNAIRE_BUNDLE_MIME
import com.designprototype.workshop.data.QuestionnaireHandoffRead
import com.designprototype.workshop.data.ReceivedQuestionnaire
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.questionnaireHandoffVerdict
import com.designprototype.workshop.data.readQuestionnaireHandoffCode
import com.designprototype.workshop.data.readableStamp
import com.designprototype.workshop.data.receivedQuestionnaireStatus
import com.designprototype.workshop.ui.DwQrLiveScanControl
import com.designprototype.workshop.ui.DwQrSymbolImage
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * HANDING A QUESTIONNAIRE TO THE PHONE IN FRONT OF YOU, with no internet on either side.
 *
 * ── WHAT THIS IS, SAID PLAINLY, BECAUSE IT WOULD BE EASY TO OVERSELL ─────────────────────────────
 *
 * It is a FILE and Android's own share sheet. It is not a bespoke peer channel: this app opens no
 * socket, discovers no peer, pairs with nothing, and adds no Bluetooth, Wi-Fi or Nearby permission —
 * the manifest still declares none of them. What it does is build a small file on the handset with no
 * server involved, and then hand it to `ACTION_SEND`, where the operating system offers Quick
 * Share/Nearby Share, Bluetooth, a cable, a shared folder, or a chat app. Every transport the owner
 * asked for is reached that way, and the radios are the platform's problem rather than ours.
 *
 * The cost of that honesty is one thing this app cannot do and must not pretend to: `ACTION_SEND` is
 * fire-and-forget and gives us no idea which target was chosen. If a designer with no signal picks
 * WhatsApp, the send fails later, in WhatsApp, and looks like our bug. The card says so in words
 * rather than trying to detect it, because the chooser does not tell us and guessing would be worse.
 *
 * ── WHY THE FILE EXISTS AT ALL, WHEN .xlsx SHARING ALREADY WORKED ────────────────────────────────
 *
 * It did not work in a courtyard. Every existing interchange path here is server-dependent: the
 * question set comes from `GET /questionnaires/{id}/question-set.xlsx` and goes back in through
 * `POST /questionnaires/upload`. So a designer with no signal could not produce anything for a
 * colleague, and a transport with nothing to carry is worth nothing. `.$QUESTIONNAIRE_BUNDLE_EXTENSION`
 * is built by `encodeQuestionnaireBundle` on this device out of the cached questionnaire — which is
 * why the offline read cache had to land first.
 *
 * ── THE QR IS A CHECK, NEVER THE QUESTIONNAIRE ───────────────────────────────────────────────────
 *
 * The measured questionnaire is 8,501 bytes gzipped: 13,608 base32 characters. `DwQrEncode` carries
 * 108 at the level cards print at, and a MAXIMUM QR symbol — version 40 at ECC L — carries 4,296
 * alphanumeric characters or 2,953 bytes. It is three times over the ceiling of any QR that exists,
 * so no encoder change could rescue it and nobody should re-open the question. What the code carries
 * is 23 characters: a truncated SHA-256 of the file's canonical JSON. Its ONE job is to answer "did
 * the whole file arrive" — Bluetooth object push and Quick Share cannot resume, so a truncated
 * transfer is the likeliest fault and it is invisible without a fingerprint. It is not a signature
 * and not a credential; see the discipline in `data/QuestionnaireBundle.kt`.
 */

/** The 48dp touch floor, matching the interchange controls beside these. */
private fun Modifier.heightIn48(): Modifier = this.heightIn(min = 48.dp)

/**
 * BUILD THE FILE AND HAND IT OVER. The sending half.
 *
 * [busy] and [onBusyChange] are the HOST screen's interchange flag, not a private one. Building this
 * file ends in `persistFileToDownloads`, which is the same MediaStore write the two .xlsx downloads
 * make into the same folder — two of those racing is how one of them ends up truncated with no error
 * anywhere, so all three controls take turns through one flag.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuestionnaireHandoffCard(
    repository: WorkshopRepository,
    questionnaireId: String,
    busy: Boolean,
    onBusyChange: (Boolean) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    var working by remember(questionnaireId) { mutableStateOf(false) }
    var built by remember(questionnaireId) {
        mutableStateOf<WorkshopRepository.QuestionnaireHandoffFile?>(null)
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Hand it to another phone",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Makes one small file on this phone — no internet needed to make it, and none to send " +
                    "it. The phone's own share sheet opens, so you can use nearby share, Bluetooth, a " +
                    "cable or a shared folder. The other designer opens it under “A questionnaire from " +
                    "another phone” on their questionnaires screen.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Text(
                QUESTIONNAIRE_BUNDLE_CONTENTS_NOTICE,
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (busy) return@OutlinedButton
                        onBusyChange(true)
                        working = true
                        built = null
                        scope.launch {
                            runCatching {
                                repository.buildQuestionnaireHandoffFile(appContext, questionnaireId)
                            }
                                .onSuccess { built = it }
                                .onFailure { error ->
                                    if (error !is CancellationException) {
                                        onError(
                                            error.apiErrorMessage(
                                                "That questionnaire could not be made into a file."
                                            )
                                        )
                                    }
                                }
                            working = false
                            onBusyChange(false)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.heightIn48(),
                ) {
                    if (working) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (working) "Making the file…" else "Make the file", fontSize = 13.sp)
                }
            }

            built?.let { file ->
                HorizontalDivider(color = MaterialTheme.field.hairline)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Ready — ${file.bundle.questionCount} question(s) in " +
                            "${file.bundle.sections.count { it.questions.isNotEmpty() }} section(s)",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(file.savedTo, color = MaterialTheme.field.body, fontSize = 11.sp)

                    if (file.shareUri != null) {
                        OutlinedButton(
                            onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    // The custom subtype, and not `application/gzip`: it keeps this
                                    // app off the share sheet for every .tar.gz on the phone.
                                    type = QUESTIONNAIRE_BUNDLE_MIME
                                    putExtra(Intent.EXTRA_STREAM, file.shareUri)
                                    putExtra(
                                        Intent.EXTRA_SUBJECT,
                                        file.bundle.title.ifBlank { "Questionnaire" },
                                    )
                                    // Without this the receiving app gets a Uri it has no permission
                                    // to read, and the share silently produces an empty attachment.
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(send, "Send the questionnaire")
                                )
                            },
                            modifier = Modifier.fillMaxWidth().heightIn48(),
                        ) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Send it")
                        }
                        Text(
                            "Pick nearby share, Bluetooth or a cable if you have no signal. A chat app " +
                                "will look like it worked and then send nothing until you are back " +
                                "online — the share sheet does not tell this app which one you chose, " +
                                "so it cannot warn you afterwards.",
                            color = MaterialTheme.field.muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    } else {
                        // Not a dead end: on the API levels where this app has no grantable Uri, the
                        // file is in the public Downloads folder and any file manager or share sheet
                        // can pick it up from there.
                        Text(
                            "Open it from the Downloads folder to send it — any app's file picker or " +
                                "share sheet will find it there.",
                            color = MaterialTheme.field.muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.field.hairline)
                    HandoffCodePanel(code = file.handoffCode)
                }
            }
        }
    }
}

/**
 * The code the recipient scans to check what arrived.
 *
 * Drawn with the app's OWN encoder (`data/DwQrEncode.kt`), whose header argues against taking on a
 * QR-writing dependency, and at the same ECC level the printed cards use — so this needs no new QR
 * version and both existing decode paths read it unchanged. The printed characters are shown as well
 * as the symbol: a cracked lens, a dead camera or a refused camera permission are all ordinary on the
 * field fleet, and 23 characters read out loud is the fallback that always works.
 */
@Composable
private fun HandoffCodePanel(code: String) {
    val symbol = remember(code) { runCatching { DwQrEncode.encode(code, DwQrEccLevel.Q) }.getOrNull() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "The check code",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Show this to whoever you sent the file to. It is NOT the questionnaire — a QR code cannot " +
                "carry one — it is a fingerprint of the file, and all it answers is whether what " +
                "arrived is whole. Nearby share and Bluetooth cannot resume a half-sent file, and a " +
                "half-sent file looks fine until somebody reads it.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (symbol != null) {
                DwQrSymbolImage(
                    symbol = symbol,
                    label = "Check code for the questionnaire file",
                    modifier = Modifier.widthIn(max = 132.dp).aspectRatio(1f),
                )
            }
            Text(
                code,
                color = MaterialTheme.field.body,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * QUESTIONNAIRES THAT ARRIVED FROM ANOTHER PHONE. The receiving half.
 *
 * ── WHY A LIST AND NOT JUST A FILE PICKER THAT IMPORTS ───────────────────────────────────────────
 *
 * The transfer works in a courtyard; ADOPTING does not. Adoption is one POST for the questionnaire,
 * one per section and one per question — 310 requests for the 24-section instrument, because there is
 * no bulk create route this handset can reach (`/questionnaires/upload` takes an .xlsx, which this app
 * deliberately does not write). So the file has to be held on the device across hours or days, and
 * the progress of a half-finished adoption has to be held with it, or a dropped connection at question
 * two hundred leaves the designer with no move except to start again and end up with two copies. See
 * `data/QuestionnaireBundleInbox.kt`.
 *
 * NOTHING HERE IS AUTOMATIC. `syncOutbox` does not drain this and must not: adopting creates rows
 * owned by whoever is signed in on THIS phone, and that is a decision a person makes after reading
 * what is in the file — not something that happens while the handset is in a pocket.
 *
 * @param incoming a file handed to the app by the share sheet or a file manager, or null. Consumed
 *   once: [onIncomingConsumed] is called whether the read succeeded or was refused, because a Uri
 *   from `ACTION_SEND` is a grant scoped to that delivery and retrying it later reads nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReceivedQuestionnairesCard(
    repository: WorkshopRepository,
    incoming: Uri?,
    onIncomingConsumed: () -> Unit,
    onAdopted: () -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    var rows by remember { mutableStateOf<List<ReceivedQuestionnaire>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    /** Which row is being adopted, and how far it has got, so only ITS row shows the progress. */
    var adopting by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    /** The verdict of the last code check, and which row it was about. */
    var checked by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(reload) {
        rows = runCatching { repository.receivedQuestionnaires(appContext) }.getOrDefault(emptyList())
    }

    /**
     * Deliveries seen but not yet read, because the card was doing something else.
     *
     * ── THE DELIVERY THAT WAS THROWN AWAY ─────────────────────────────────────────────────────
     *
     * `receive` opens with `if (busy) return`, and the share-sheet effect used to call it and then
     * call [onIncomingConsumed] UNCONDITIONALLY. So when the early return fired, the Uri was cleared
     * with nothing read: no row, no file, no message, and the sender's phone reporting success. And
     * `busy` is not a flicker — it is held for the whole of an adoption, which is 310 HTTP requests
     * and minutes of them, and for the whole of a picker import. A colleague's Quick Share arriving in
     * that window simply did not exist.
     *
     * QUEUED RATHER THAN REFUSED. Refusing would at least have been honest, but there is nothing for
     * the designer to do about it except send it again, and the work is a few hundred milliseconds of
     * file copy that can perfectly well happen after the adoption finishes.
     */
    var queuedDeliveries by remember { mutableStateOf<List<Uri>>(emptyList()) }

    /** Copy the bytes off a Uri into the inbox. Shared by the picker and the share-sheet delivery. */
    fun receive(uri: Uri) {
        if (busy) {
            queuedDeliveries = queuedDeliveries + uri
            return
        }
        busy = true
        scope.launch {
            repository.receiveQuestionnaireHandoff(appContext, uri)
                .onSuccess { row ->
                    reload++
                    onMessage(
                        "“${row.title.ifBlank { row.filename }}” arrived — " +
                            "${row.questionCount} question(s). Nothing has been added to your " +
                            "questionnaires yet."
                    )
                }
                // The refusals are written to be shown: damaged, not ours, from a newer build, or
                // empty. Each one leads to a different next act, which is why they are not one
                // sentence. See `readQuestionnaireBundle`.
                .onFailure { error -> onError(error.message ?: "That file could not be read.") }
            busy = false
        }
    }

    // The share sheet's delivery, taken exactly once. The Uri's read permission is scoped to the
    // delivery, so this cannot be deferred to a button the designer might press tomorrow —
    // `queuedDeliveries` defers it by seconds, inside the same task, which is a different thing.
    LaunchedEffect(incoming) {
        val uri = incoming ?: return@LaunchedEffect
        receive(uri)
        // Consumed only now that the Uri is either read or QUEUED to be read. It is still consumed on
        // a refusal — a dead Uri re-offered on every recomposition would re-refuse for ever.
        onIncomingConsumed()
    }

    // Drains what arrived while the card was busy. Keyed on `busy` as well, so it runs the moment an
    // adoption or an import lets go rather than waiting for the next delivery to push it.
    LaunchedEffect(queuedDeliveries, busy) {
        if (busy) return@LaunchedEffect
        val next = queuedDeliveries.firstOrNull() ?: return@LaunchedEffect
        queuedDeliveries = queuedDeliveries.drop(1)
        receive(next)
    }

    val pickBundle = rememberQuestionnaireBundlePicker { uri -> receive(uri) }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "A questionnaire from another phone",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                // NAMES THE PICKER AS THE NORMAL ROUTE for the two transports it is the normal route
                // for. The app's intent filters cannot be offered as a handler for a Quick Share or a
                // Bluetooth push — see `isQuestionnaireBundleDelivery` — so a designer told "it will
                // appear here" would sit waiting for a notification that never comes.
                "Takes the .$QUESTIONNAIRE_BUNDLE_EXTENSION file a colleague sent by nearby share, " +
                    "Bluetooth, a cable or a shared folder. Nearby share and Bluetooth leave it in " +
                    "your Downloads folder without telling this app, so open it here — it will not " +
                    "appear on its own.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = pickBundle,
                    enabled = !busy,
                    modifier = Modifier.heightIn48(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "Reading the file…" else "Open a questionnaire file", fontSize = 13.sp)
                }
            }

            if (rows.isEmpty()) {
                Text(
                    "Nothing has arrived on this phone yet.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                )
            } else {
                Text(
                    QUESTIONNAIRE_BUNDLE_ADOPT_NOTICE,
                    color = MaterialTheme.field.warning,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }

            rows.forEach { row ->
                HorizontalDivider(color = MaterialTheme.field.hairline)
                ReceivedQuestionnaireRow(
                    row = row,
                    busy = busy,
                    adopting = adopting == row.id,
                    progress = if (adopting == row.id) progress else null,
                    verdict = checked?.takeIf { it.first == row.id }?.second,
                    onAdopt = {
                        if (busy) return@ReceivedQuestionnaireRow
                        busy = true
                        adopting = row.id
                        progress = null
                        scope.launch {
                            repository.adoptReceivedQuestionnaire(
                                context = appContext,
                                row = row,
                                onProgress = { done, total ->
                                    progress = "$done of $total section(s) added."
                                },
                            )
                                .onSuccess {
                                    onMessage(
                                        "“${row.title.ifBlank { row.filename }}” was added to your " +
                                            "questionnaires. It is yours: it is recorded as uploaded " +
                                            "by you."
                                    )
                                    onAdopted()
                                }
                                .onFailure { error ->
                                    if (error !is CancellationException) {
                                        onError(error.message ?: "That questionnaire could not be added.")
                                    }
                                }
                            reload++
                            adopting = null
                            progress = null
                            busy = false
                        }
                    },
                    onDiscard = {
                        if (busy) return@ReceivedQuestionnaireRow
                        busy = true
                        scope.launch {
                            runCatching { repository.discardReceivedQuestionnaire(appContext, row.id) }
                            reload++
                            busy = false
                        }
                    },
                    onCheck = { scanned ->
                        checked = row.id to when (val read = readQuestionnaireHandoffCode(scanned)) {
                            is QuestionnaireHandoffRead.Refused -> read.message
                            is QuestionnaireHandoffRead.Ok ->
                                questionnaireHandoffVerdict(
                                    expected = read.digest,
                                    fileDigest = row.handoffDigest,
                                )
                        }
                    },
                    onScanRefused = onError,
                )
            }
        }
    }
}

/**
 * One arrived file: what it holds, how far its adoption got, and the three things to do with it.
 *
 * THE STATUS SENTENCE IS [receivedQuestionnaireStatus]'s and not this composable's, so it is pinned
 * by a JVM test. The middle state — half adopted — is the one a designer meets after a connection
 * drops, and it has to say that the questionnaire IS already on their account so that they carry on
 * rather than starting again and ending up with two.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReceivedQuestionnaireRow(
    row: ReceivedQuestionnaire,
    busy: Boolean,
    adopting: Boolean,
    progress: String?,
    verdict: String?,
    onAdopt: () -> Unit,
    onDiscard: () -> Unit,
    onCheck: (String) -> Unit,
    onScanRefused: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            row.title.ifBlank { row.filename },
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "${row.filename} · arrived ${readableStamp(row.receivedAt) ?: row.receivedAt}",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
        )
        Text(
            receivedQuestionnaireStatus(row),
            color = if (row.failure != null) MaterialTheme.field.warning else MaterialTheme.field.body,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        progress?.let {
            Text(it, color = MaterialTheme.field.body, fontSize = 11.sp)
        }

        if (!row.adopted) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAdopt, enabled = !busy, modifier = Modifier.heightIn48()) {
                    if (adopting) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        when {
                            adopting -> "Adding…"
                            // Never "Add again". A resume continues the questionnaire that already
                            // exists on the account; a designer told to "add again" would reasonably
                            // expect a second one, which is the mistake this label prevents.
                            row.started -> "Carry on adding"
                            else -> "Add to my questionnaires"
                        },
                        fontSize = 13.sp,
                    )
                }
                TextButton(onClick = onDiscard, enabled = !busy) {
                    Text("Throw it away", fontSize = 13.sp)
                }
            }
            Text(
                "Adding needs a signal — it writes to your account. The file itself keeps until then.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
            )
        } else {
            TextButton(onClick = onDiscard, enabled = !busy) {
                Text("Remove this file", fontSize = 13.sp)
            }
        }

        // The scan is offered on every row, adopted or not: the question "is this the whole file"
        // is worth answering before it is adopted, and worth answering afterwards when a section
        // looks short.
        DwQrLiveScanControl(
            enabled = !busy,
            onText = onCheck,
            onRefusal = onScanRefused,
        )
        verdict?.let {
            Text(
                it,
                color = if (it.startsWith("This is the same")) {
                    MaterialTheme.field.body
                } else {
                    MaterialTheme.field.warning
                },
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

/**
 * Pick a `.$QUESTIONNAIRE_BUNDLE_EXTENSION` off the device.
 *
 * `OpenDocument` AND NOT `GetContent`, for the reason [rememberWorkbookPicker] states beside the
 * workbook picker: `GetContent` can return a Uri whose permission dies with the activity result, and
 * the bytes here are read inside a coroutine that outlives the callback.
 *
 * THE MIME FILTER IS DELIBERATELY WIDE, and this file needs it more than the workbook does. Our
 * custom subtype is not in any provider's type table, so a downloads folder, a chat app's saved-files
 * folder or an SD card will report `application/octet-stream` or `application/gzip` for exactly these
 * bytes. A strict filter would grey out the file the designer is looking straight at, with no
 * explanation available anywhere on screen. The check belongs where the bytes can be read, and
 * `readQuestionnaireBundle` does it there with a sentence per refusal.
 */
@Composable
private fun rememberQuestionnaireBundlePicker(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri)
    }
    return {
        launcher.launch(
            arrayOf(
                QUESTIONNAIRE_BUNDLE_MIME,
                "application/gzip",
                "application/x-gzip",
                "application/octet-stream",
                "*/*",
            )
        )
    }
}
