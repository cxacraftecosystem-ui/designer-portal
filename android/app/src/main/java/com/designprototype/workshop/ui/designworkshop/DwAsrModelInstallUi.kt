package com.designprototype.workshop.ui.designworkshop

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.BuildConfig
import com.designprototype.workshop.data.ApiClient
import com.designprototype.workshop.data.DW_ASR_MODELS
import com.designprototype.workshop.data.DW_ASR_MODEL_ARTIFACTS
import com.designprototype.workshop.data.DW_ASR_MODEL_MISMATCH_SENTENCE
import com.designprototype.workshop.data.DW_MODEL_FREE_STORAGE_MARGIN_BYTES
import com.designprototype.workshop.data.DwAsrContainerFormat
import com.designprototype.workshop.data.DwAsrEndpointState
import com.designprototype.workshop.data.DwAsrManifestVerdict
import com.designprototype.workshop.data.DwAsrModel
import com.designprototype.workshop.data.DwAsrModelArtifact
import com.designprototype.workshop.data.DwAsrModelEndpointApi
import com.designprototype.workshop.data.DwAsrModelFile
import com.designprototype.workshop.data.DwAsrModelOffer
import com.designprototype.workshop.data.DwAsrModelSource
import com.designprototype.workshop.data.DwAsrModelState
import com.designprototype.workshop.data.DwAsrModelStatus
import com.designprototype.workshop.data.DwAsrVerification
import com.designprototype.workshop.data.DwConnection
import com.designprototype.workshop.data.TokenStore
import com.designprototype.workshop.data.dwAsrEndpointStateOf
import com.designprototype.workshop.data.dwAsrManifestVerdict
import com.designprototype.workshop.data.dwAsrModelFileUrl
import com.designprototype.workshop.data.dwAsrModelSourceFor
import com.designprototype.workshop.data.DwDeviceMeasurement
import com.designprototype.workshop.data.DwRecognitionSupport
import com.designprototype.workshop.data.DwResumeDecision
import com.designprototype.workshop.data.DwTransferControlState
import com.designprototype.workshop.data.DwTransferMeter
import com.designprototype.workshop.data.DwTransferPhase
import com.designprototype.workshop.data.DwTransferReadout
import com.designprototype.workshop.data.dwBytesLabel
import com.designprototype.workshop.data.dwIsDiskFull
import com.designprototype.workshop.data.dwIsPartialFileName
import com.designprototype.workshop.data.dwPauseLabel
import com.designprototype.workshop.data.dwParseContentRangeStart
import com.designprototype.workshop.data.dwPartialFileName
import com.designprototype.workshop.data.dwPausedSentence
import com.designprototype.workshop.data.dwRangeHonoured
import com.designprototype.workshop.data.dwResumePlan
import com.designprototype.workshop.data.dwStalledSentence
import com.designprototype.workshop.data.dwTransferDiskFullSentence
import com.designprototype.workshop.data.dwTransferHeading
import com.designprototype.workshop.data.dwTransferLine
import com.designprototype.workshop.data.dwTransferSpaceRefusal
import com.designprototype.workshop.data.dwAsrModelActionLabel
import com.designprototype.workshop.data.dwAsrModelArtifactFor
import com.designprototype.workshop.data.dwAsrModelMayInstall
import com.designprototype.workshop.data.dwAsrModelOffer
import com.designprototype.workshop.data.dwAsrModelOfferSentence
import com.designprototype.workshop.data.dwAsrModelStateLabel
import com.designprototype.workshop.data.dwAsrModelWhatItBuysSentence
import com.designprototype.workshop.data.dwAsrVerify
import com.designprototype.workshop.data.dwProbeDevice
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * **PUTTING THE 365 MB SPEECH MODEL ON THIS PHONE. THE ANDROID HALF OF `data/DwAsrModelInstall.kt`.**
 *
 * The decision — may it be offered, what does it cost, what does every state say — is pure Kotlin in
 * `data/`, where the desktop JVM tests it. Everything here is the part that cannot be: one directory
 * copy, one HTTP GET, one digest of a file on disk, and the surface that draws the answer.
 *
 * ── THE TWO ROUTES IN, AND WHAT THEY SHARE ────────────────────────────────────────────────────
 *
 *  1. **A CABLE.** Somebody with `adb` pushes `model.int8.onnx` and `tokens.txt` into a staging
 *     directory this app can read; [installFromStaged] copies them into `filesDir` and verifies.
 *     Costs nothing, takes seconds, and is how this feature is tested at all — re-downloading 292 MB
 *     to try a change is absurd. docs/ASR-MODEL-SIDELOAD.md is the two commands.
 *  2. **A DOWNLOAD**, which is written and is not reachable in this build: upstream publishes a
 *     `.tar.bz2` and Android has no bzip2. See [DwAsrContainerFormat], which argues that at length.
 *
 * **BOTH END IN [readInstalled], WHICH IS THE ONLY THING EITHER OF THEM IS TRUSTED THROUGH.** There
 * is no flag anywhere in this file that skips the digest check, and the sideload is not "the debug
 * path" — it is the ordinary path with a shorter corridor. A sideload that skipped verification would
 * be the route a developer uses every day and the only one feeding unchecked bytes to a native graph
 * executor, which is precisely the wrong one to make weak.
 *
 * ── WHY `filesDir`, AND WHY THIS ONE IS ALLOWED TO READ FROM OUTSIDE IT ───────────────────────
 *
 * The model is INSTALLED into internal storage and read from nowhere else, for `DwAsrRuntimeUi.kt`'s
 * reason: `cacheDir` is reclaimed without warning and external storage is writable by other apps. The
 * STAGING directory is deliberately outside it, because a directory `adb push` can write to is by
 * definition not private — and that is safe here for exactly one reason, which is the whole design:
 * **the staged bytes are never used.** They are copied in and then the copy in `filesDir` is hashed.
 * A file substituted in the staging directory between the push and the tap fails the check exactly as
 * a substituted download would.
 *
 * ── WHAT IS PROVEN AND WHAT IS NOT ────────────────────────────────────────────────────────────
 *
 * The staged route has been run on the fleet's SM-M325F. The download route has never executed
 * against a server, because there is no server serving a readable container — the same honest state
 * `DwAsrRuntimeUi.kt` records for the engine's fetch, and for the same reason.
 */

// ---------------------------------------------------------------------------------------------
// Where the model lives, and where a cable may leave it
// ---------------------------------------------------------------------------------------------

/** The models' directory inside `filesDir`. The same path `DwAsrEngineProbeTest` already installs to. */
private const val DW_ASR_MODEL_DIR = "asr-model"

/** Where a container is written while it is being fetched, and from where it is unpacked. */
private const val DW_ASR_MODEL_INCOMING = "incoming"

/**
 * The staging directory's name, under each of the two places a cable can reach.
 *
 * The same name in both so that one `adb push` command works whichever route the handset allows, and
 * so docs/ASR-MODEL-SIDELOAD.md has one path to print rather than a decision tree.
 */
private const val DW_ASR_STAGING = "dwasr"

/**
 * One model's directory inside `filesDir`.
 *
 * [modelId] comes from [DwAsrModel.modelId], whose constructor has already refused anything with a
 * separator or `..` in it — so no string that reaches this function can address a path outside the one
 * directory this feature owns. Every file name likewise comes from [DwAsrModel.files], checked the
 * same way. **No name from the wire or from the staging directory is ever used to build a path here.**
 */
internal fun dwAsrModelDir(context: Context, modelId: String): File =
    File(File(context.filesDir, DW_ASR_MODEL_DIR), modelId)

/**
 * Every directory a cable might have left the model in, in the order they are tried.
 *
 * ── TWO, BECAUSE ONE OF THEM DOES NOT WORK ON EVERY HANDSET ───────────────────────────────────
 *
 *  * **The app's own external files directory** is the reliable one: `adb push` can write it with no
 *    root and no `run-as`, this app can read it with no permission at all, and it is removed when the
 *    app is uninstalled. It is `getExternalFilesDir(null)`, which is null on a handset with no
 *    external volume mounted — hence the `listOfNotNull`.
 *  * **`/data/local/tmp/dwasr`** is where `DwAsrEngineProbeTest` already stages, and where the bytes
 *    on this fleet's handset already are, so it is tried too rather than making somebody push 350 MB
 *    twice. **It is not readable by an ordinary app on every build** — `/data/local/tmp` is commonly
 *    `0771 root:shell`, and an app that is not in the `shell` group cannot traverse it. That is why it
 *    is second and why its failure is not an error: [stagedModelFiles] simply finds nothing there.
 *
 * A DIRECTORY THAT CANNOT BE READ IS NOT AN ERROR AND IS NOT A REFUSAL. It is the absence of a staged
 * copy, which is the ordinary state of every handset in the fleet.
 */
internal fun dwAsrStagingDirs(context: Context): List<File> = listOfNotNull(
    context.getExternalFilesDir(null)?.let { File(it, DW_ASR_STAGING) },
    File("/data/local/tmp/$DW_ASR_STAGING"),
)

/**
 * The staged files for [model], keyed by pinned file name — or null when the set is incomplete.
 *
 * **ALL OR NOTHING, AND FROM ONE DIRECTORY.** Half a staged model is not most of one: a graph without
 * its vocabulary decodes to confident nonsense rather than to an error. Mixing files from two staging
 * directories is refused for the same reason a `containsAll` is refused in the verifier — it would
 * make "which bytes did we install" depend on the order two directories happened to be listed in.
 *
 * The SIZE is checked here and the DIGEST is not, and that split is deliberate: this function answers
 * "is there something worth copying", which is a cheap question asked every time the card appears.
 * The expensive answer is taken after the copy, off the file in `filesDir`, which is the one that
 * decides anything.
 */
internal fun stagedModelFiles(context: Context, model: DwAsrModel): Map<String, File>? {
    dwAsrStagingDirs(context).forEach { dir ->
        val found = runCatching {
            model.files.associate { pinned ->
                pinned.fileName to File(dir, pinned.fileName)
            }
        }.getOrNull() ?: return@forEach
        val complete = runCatching {
            found.all { (name, file) ->
                val pinned = model.files.first { it.fileName == name }
                file.isFile && file.length() == pinned.bytes
            }
        }.getOrDefault(false)
        if (complete) return found
    }
    return null
}

// ---------------------------------------------------------------------------------------------
// The verification — ONE copy, called by the settings card and by the dictation ladder alike
// ---------------------------------------------------------------------------------------------

/**
 * **HASH EVERY FILE THIS MODEL PINS, OFF DISK, AND DECIDE WHAT IS ACTUALLY ON THIS PHONE.**
 *
 * ONE COPY, DELIBERATELY, AND IT IS THE REASON THIS IS A TOP-LEVEL FUNCTION RATHER THAN A METHOD.
 * Two things need this answer and they are not on the same screen: the settings card, which shows it,
 * and [DwAsrModelRun], which the dictation ladder asks before offering the local rung. A second
 * implementation would be a second place for the digest check to be got wrong, and the one that
 * shipped would be whichever the ladder happened to call — which is the one that decides whether
 * unverified bytes reach a native graph executor.
 *
 * ALL OR NOTHING. A missing file is [DwAsrModelState.NOT_INSTALLED] and the offer stands; a file that
 * is PRESENT and does not match is **deleted with a sentence**, because it is a file this app did not
 * put there in the state it is in, and leaving it beside a good one invites a future run to open it.
 *
 * **BLOCKING, AND IT READS 365 MB.** Call it on an IO dispatcher. Both callers do.
 */
internal fun dwAsrReadInstalledModel(
    context: Context,
    model: DwAsrModel? = DW_ASR_MODELS.firstOrNull(),
    /**
     * Called with the running total of bytes hashed **across all of this model's files**, so a
     * surface can draw the VERIFYING phase from its own bytes rather than from the meter the copy or
     * the fetch left behind. Null for the two callers that draw nothing — `refresh` and the
     * dictation ladder — which is why it defaults to null and costs them nothing.
     */
    onBytesHashed: ((Long) -> Unit)? = null,
): DwAsrModelStatus {
    if (model == null) return DwAsrModelStatus(DwAsrModelState.NOT_INSTALLED)
    val dir = dwAsrModelDir(context, model.modelId)
    val verified = mutableListOf<String>()
    // Bytes finished in EARLIER files, so the per-file counter below reads as a figure about the
    // whole model. Without it the readout would restart at zero for `tokens.txt` and the bar would
    // jump backwards from 100% to nothing on the last file.
    var hashedInEarlierFiles = 0L
    model.files.forEach { pinned ->
        val file = File(dir, pinned.fileName)
        if (!file.isFile) return DwAsrModelStatus(DwAsrModelState.NOT_INSTALLED, model)
        /*
         * The length first, purely as a cheap exit: a file of the wrong size cannot hash to the right
         * digest, and skipping a SHA-256 over 365 MB on an obviously truncated copy is worth two
         * lines. IT IS NOT A SUBSTITUTE FOR THE HASH, and nothing below treats a matching length as
         * evidence of anything.
         */
        if (file.length() != pinned.bytes) {
            runCatching { file.delete() }
            // Not the mismatch sentence: a truncated file is the ordinary result of an install that
            // was interrupted, and telling a designer the file may have been substituted would send
            // them to an administrator over a dropped copy.
            return DwAsrModelStatus(DwAsrModelState.NOT_INSTALLED, model)
        }
        // Shifted by what earlier files already accounted for, so what the surface receives is a
        // figure about the whole model rather than about whichever file is being read.
        val progress: ((Long) -> Unit)? = onBytesHashed?.let { sink ->
            { inThisFile: Long -> sink(hashedInEarlierFiles + inThisFile) }
        }
        val digest = dwAsrSha256OfFile(file, progress)
            ?: return DwAsrModelStatus(DwAsrModelState.NOT_INSTALLED, model)
        hashedInEarlierFiles += pinned.bytes
        if (dwAsrVerify(pinned.sha256, digest) != DwAsrVerification.VERIFIED) {
            runCatching { file.delete() }
            return DwAsrModelStatus(
                DwAsrModelState.FAILED,
                model,
                failure = DW_ASR_MODEL_MISMATCH_SENTENCE,
            )
        }
        verified += digest
    }
    // The constructor refuses this unless `verified` accounts for every file — the invariant rather
    // than a formality. See DwAsrModelStatus's init.
    return DwAsrModelStatus(DwAsrModelState.INSTALLED, model, verifiedSha256 = verified)
}

// ---------------------------------------------------------------------------------------------
// The controller
// ---------------------------------------------------------------------------------------------

/**
 * One reading of this handset's speech model, and the one place an install can be started.
 *
 * Alive only while a surface showing the model is on screen ([rememberDwAsrModel]), for
 * `DwAsrRuntimeController`'s reason: the work it does on appearing is real — a device probe and a
 * SHA-256 over 365 MB — and doing it on a cold start would spend it on every designer who never opens
 * this card.
 *
 * **THE 365 MB HASH IS THE ONE COST WORTH NAMING.** It is roughly a second and a half of reading on
 * this handset's flash, taken every time the card appears, and it is not cached: "it matched when we
 * installed it" is a claim about a file as it was before the phone rebooted, before the app updated,
 * and before anybody with a cable rewrote it. The alternative is decoding with a graph nobody looked
 * at, which for a substituted `tokens.txt` produces fluent output in the wrong alphabet.
 */
@Stable
internal class DwAsrModelController(
    private val context: Context,
    /** The composition's own scope, so an install dies with the surface that started it. */
    private val scope: CoroutineScope,
    /**
     * What this build pins, **overridable so that the download route can be executed at all.**
     *
     * ── WHY A SEAM, WHEN SEAMS ARE USUALLY A SMELL ────────────────────────────────────────────
     *
     * [downloadAndInstall]'s own docstring says, in as many words, *"this function has never run
     * against a server"*. It could not: the pinned container is a `.tar.bz2`, so `dwAsrModelOffer`
     * step 4 answers `CONTAINER_NOT_READABLE_IN_THIS_BUILD` and `dwAsrModelSourceFor` never resolves
     * to `PINNED_CONTAINER` for an offer that may install — and `DwAsrModelArtifact`'s constructor
     * requires `https://`, correctly, so a local plaintext server cannot stand in for one either.
     * Two hundred and ninety-two megabytes of somebody's prepaid bundle were riding on code nothing
     * had ever run.
     *
     * NOT "before any control is drawn", which is what this said and which [DwAsrEndpointState]
     * falsified: on a deployment that PUBLISHES, a control is drawn and works — it just dispatches
     * to `downloadFromEndpoint`. The container half stays unexecuted because no source selects it,
     * not because no button exists.
     *
     * These two parameters are what let `DwAsrModelTransferProbeTest` point the SAME loop at a real
     * https host that honours `Range`, watch the readout against bytes it counts itself, interrupt it,
     * resume it, and check the digest of the assembled file. **Production passes nothing**: the
     * defaults are the shipped catalogues, and there is no code path outside `androidTest` that
     * supplies anything else. The alternative — a second copy of the fetch loop in a test — would have
     * measured the copy rather than the app, which is the failure this repository keeps writing down.
     */
    private val models: List<DwAsrModel> = DW_ASR_MODELS,
    private val artifacts: List<DwAsrModelArtifact> = DW_ASR_MODEL_ARTIFACTS,
    /**
     * Where this deployment's own per-file route lives, **overridable for the same reason the two
     * catalogues above are.**
     *
     * `DwAsrModelTransferProbeTest` is the only thing that has ever executed the fetch loop against a
     * real server, and it can only reach `downloadFromEndpoint` if it can point it somewhere — a
     * handset with `adb reverse` gives it `http://127.0.0.1:8000/api/`, which
     * `network_security_config.xml` already permits cleartext for (10.0.2.2, 127.0.0.1, localhost and
     * nothing else). **Production passes nothing**: the default is the build constant, and there is
     * no code path outside `androidTest` that supplies anything else.
     */
    private val apiBaseUrl: String = BuildConfig.DEFAULT_API_BASE_URL,
) {

    /** What the model is doing on this phone. Starts as the honest UNKNOWN, never as "not there". */
    var status by mutableStateOf(DwAsrModelStatus())
        private set

    /** The reading the offer is decided from — free storage. Null until taken. */
    var measurement by mutableStateOf<DwDeviceMeasurement?>(null)
        private set

    var connection by mutableStateOf(DwConnection.NONE)
        private set

    /** True when a cable has left a complete set of the pinned files somewhere this app can read. */
    var stagedFilesPresent by mutableStateOf(false)
        private set

    /**
     * What THIS DEPLOYMENT last said about serving the model. **Starts UNKNOWN and never as "no".**
     *
     * Read by [refresh] only when there is a connection to read it over, so an offline refresh costs
     * no socket and no timeout wait. Until then it is [DwAsrEndpointState.UNKNOWN], which
     * [dwAsrModelOffer] falls through rather than treating as a refusal — this codebase's standing
     * not-yet-looked rule.
     */
    var endpoint by mutableStateOf(DwAsrEndpointState.UNKNOWN)
        private set

    /**
     * WHY the manifest was refused, when it was. Null until one has been read at all.
     *
     * Kept beside [endpoint] rather than folded into it because
     * [DwAsrManifestVerdict.DISAGREES_ON_DIGEST] is the one an administrator has to be told about —
     * it means this deployment is serving a DIFFERENT file under the name this APK pins — while the
     * card's state for it is the same [DwAsrEndpointState.NOT_PUBLISHED] as an empty origin's.
     */
    var manifestVerdict by mutableStateOf<DwAsrManifestVerdict?>(null)
        private set

    /**
     * Which route left the part-files that are on this phone right now, or null when there are none.
     *
     * **READ OFF THE DISK, BECAUSE A PAUSE OUTRANKS EVERY GATE AND IS DECIDED BEFORE [endpoint] IS.**
     * `dwAsrModelOffer` answers RESUME at step 2 from [DwAsrModelState.PAUSED], before the endpoint
     * state is consulted at all — deliberately, since bytes already paid for beat every question
     * below. So a resume cannot be attributed to a route by asking what the deployment says today:
     * the prefix was written by whichever route ran yesterday, and the deployment may have been
     * provisioned or emptied since. See [dwAsrModelSourceFor], whose `pausedSource` this is.
     */
    var pausedSource by mutableStateOf<DwAsrModelSource?>(null)
        private set

    /**
     * Bytes sitting in the part-file, as of the last reading. **The durable half of a pause.**
     *
     * Read off the disk rather than remembered, so a designer who force-stopped the app mid-download
     * is told the true figure rather than one from a flag that outlived the file it described.
     */
    var partialOnDiskBytes by mutableStateOf(0L)
        private set

    /** True while the disk is being read and hashed, or an install is running. */
    var busy by mutableStateOf(false)
        private set

    /**
     * **THE NUMBERS THE OWNER ASKED FOR, WHILE BYTES ARE ACTUALLY MOVING.** Null when none are.
     *
     * *"the loading bar should show speed, expected duration, and how much of it has been
     * downloaded"*. Every one of those comes off [DwTransferMeter], which is fed here and read by
     * `DwAsrModelBody`; nothing on this screen computes a rate of its own.
     */
    var readout by mutableStateOf<DwTransferReadout?>(null)
        private set

    /** Which measurable stage [readout] is about, so the heading above it is not guessed. */
    var phase by mutableStateOf<DwTransferPhase?>(null)
        private set

    /**
     * The meter itself, kept so the surface can ask it on ITS OWN clock rather than only when bytes
     * land — which is the only way a stall ever becomes visible. See [DwTransferMeter.readAt].
     */
    private var meter: DwTransferMeter? = null

    /**
     * Bytes moved by a producer that **cannot publish for itself**, read by [tick] once a second.
     *
     * Hashing and unpacking are loops deep inside blocking code with no suspend point to spare: the
     * hash calls back once per 64 KiB, 5,570 times for the pinned model, and a dispatcher hop each
     * time would cost more than the SHA-256. So those loops only bump this counter, and the
     * main-thread tick is what turns it into a meter sample. `@Volatile` because it is written on the
     * IO dispatcher and read on the main thread.
     */
    @Volatile
    private var countedBytes: Long = 0L

    /**
     * True while the live phase's only producer is [countedBytes], which changes what [tick] does.
     *
     * A FETCH and a COPY publish their own samples as they write, so the tick must only READ the
     * meter — sampling there would fabricate progress and hide a stall. Hashing and unpacking are the
     * other way round: nothing samples for them, so the tick is the only thing that can.
     */
    private var meterFedByTick: Boolean = false

    /**
     * **WHY THE JOB WAS CANCELLED, WHICH DECIDES WHETHER THE PART-FILE SURVIVES.**
     *
     * A coroutine cancellation looks identical whichever button caused it, and the two buttons mean
     * opposite things about 292 MB of somebody's data: Pause keeps the prefix, Cancel reclaims the
     * space. Reading the intent off this field in the `catch` is what tells them apart — without it
     * the cleanup path would have to guess, and either answer is wrong half the time.
     */
    private var stopIntent: DwTransferControlState = DwTransferControlState.RUNNING

    private var job: Job? = null

    /**
     * Built only if a download actually starts, so reading the card costs no connection pool.
     *
     * ── IT CARRIES THE BEARER TOKEN NOW, AND WITHOUT THAT THE ENDPOINT ROUTE IS 401 EVERY TIME ───
     *
     * This was a bare builder with no auth, and correctly so: it was written for an anonymous GitHub
     * release asset. This deployment's own route runs `get_current_user` before anything else, so
     * every request from an unauthenticated client answers 401 — and the existing code would surface
     * that as `"the server answered HTTP 401"`, which tells a designer nothing they can act on. The
     * interceptor is the same six lines `ApiClient` uses, reading the same [TokenStore].
     *
     * ── AND IT IS **NOT** `ApiClient`'s CLIENT, WHICH IS THE ONE PLACE THAT RULE DOES NOT APPLY ──
     *
     * `ApiClient.retrofit`'s docstring says a feature must not stand up a second HTTP stack, because
     * it would opt out of the 504 retry this origin needs. That is right for the manifest — see
     * [DwAsrModelEndpointApi], which does go through it — and it is wrong for the stream:
     * `ApiClient.isSafelyRetriable` treats every GET as replayable and would re-issue a 365 MB
     * download up to four times with backoff, fighting the resume logic below, which asks for the
     * REMAINDER rather than the whole file. So this client has the auth interceptor and none of the
     * retry policy, and that composition is deliberate rather than an oversight.
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // An hour, because this is 365 MB over a district-town connection — but not unbounded: a
            // stalled fetch has to end in a sentence rather than in a spinner that outlives the day.
            .callTimeout(60, TimeUnit.MINUTES)
            .addInterceptor(
                Interceptor { chain ->
                    val token = TokenStore(context).getToken()
                    val request = if (token.isNullOrBlank()) {
                        // The GitHub route needs no token and must not be broken by this: an empty
                        // Authorization header is worse than none, and some CDNs reject it outright.
                        chain.request()
                    } else {
                        chain.request().newBuilder()
                            .header("Authorization", "Bearer $token")
                            .build()
                    }
                    chain.proceed(request)
                }
            )
            .build()
    }

    /** The model this build pins, or null. One row today; `firstOrNull` is the whole selection. */
    private val model: DwAsrModel? get() = models.firstOrNull()

    /** What this controller decides from, so the surface drawing it cannot read a different list. */
    val modelCatalogue: List<DwAsrModel> get() = models

    /** As [modelCatalogue], for the containers. */
    val artifactCatalogue: List<DwAsrModelArtifact> get() = artifacts

    /**
     * Take a fresh reading: probe the device, read the connection, look for staged files, and hash
     * whatever is installed. Safe to call again; "Check again" does exactly that.
     */
    fun refresh() {
        // A refresh must never interrupt an install in flight — the reading it would produce is worth
        // far less than the work it would throw away.
        if (status.state == DwAsrModelState.INSTALLING) return
        job?.cancel()
        busy = true
        job = scope.launch {
            val reading = withContext(Dispatchers.IO) { dwProbeDevice(context) }
            val here = dwConnection(context)
            val pinned = model
            val next = withContext(Dispatchers.IO) {
                // The part-download nobody would otherwise find, swept for `sweepIncoming`'s reason
                // one file over: this is the first moment after a process death when anything looks
                // in this directory at all. It now spares the `.part` file — see that function.
                sweepIncoming()
                val read = if (pinned == null) {
                    DwAsrModelStatus(DwAsrModelState.NOT_INSTALLED)
                } else {
                    readInstalled(pinned)
                }
                /*
                 * A PREFIX ON DISK IS A PAUSED DOWNLOAD, AND IT IS FOUND HERE RATHER THAN REMEMBERED.
                 *
                 * `DwAsrModelState.PAUSED` is deliberately not persisted — the part-file IS the
                 * durable record, and a flag that outlived the process would come back as "paused" on
                 * a phone whose file an OS storage cleaner had since removed. So every reading asks
                 * the disk, and a designer who force-stopped the app mid-download finds their bytes
                 * still there and a Resume button over them, which is the outcome that keeps the
                 * bytes already paid for honest — the 292 MB the container route costs, or the
                 * 365 MB the endpoint route does. Neither figure is a constant any more, which is
                 * why the sentence names the route rather than a number.
                 *
                 * Only from NOT_INSTALLED: a verified install outranks any leftover, and a FAILED
                 * read carries a sentence about a digest that a "Paused" label would bury.
                 */
                if (read.state == DwAsrModelState.NOT_INSTALLED && partialBytes() > 0L) {
                    DwAsrModelStatus(DwAsrModelState.PAUSED, read.model)
                } else {
                    read
                }
            }
            val staged = withContext(Dispatchers.IO) {
                pinned != null && stagedModelFiles(context, pinned) != null
            }
            /*
             * ASKED ONLY WHEN THERE IS SOMETHING TO ASK OVER, AND ONLY WHEN IT COULD CHANGE THE
             * ANSWER. With no connection this costs no socket, no DNS and no timeout wait — a
             * designer refreshing this card in a courtyard gets it back instantly and the card says
             * "no connection", which is true, rather than spinning for thirty seconds to discover it.
             *
             * The state is left at whatever it was rather than reset to UNKNOWN: a deployment that
             * said PUBLISHES five minutes ago has not stopped publishing because the phone walked
             * into a shed, and [dwAsrModelOffer] checks the connection itself before offering a
             * fetch. What is NOT done is caching that across a process death and offering a button
             * on the strength of it — [endpoint] starts UNKNOWN on every launch, so a designer who
             * has never been online since installing is never promised a fetch that would 503.
             */
            val deployment = if (pinned == null || here == DwConnection.NONE) {
                endpoint
            } else {
                readManifest(pinned)
            }
            val onDisk = withContext(Dispatchers.IO) { partialBytesAndSource() }
            measurement = reading
            connection = here
            stagedFilesPresent = staged
            endpoint = deployment
            partialOnDiskBytes = onDisk.first
            pausedSource = onDisk.second
            status = next
            // ONE READING, TWO READERS. The dictation ladder asks [DwAsrModelRun] on every tap and
            // must not take its own — 365 MB of SHA-256 per prose field is minutes per stage screen.
            // Publishing here means the card's reading IS the ladder's, so the two surfaces cannot
            // come to different conclusions about one phone, which is the failure this repository
            // has already shipped once with a pack that had since arrived.
            DwAsrModelRun.publish(next)
            busy = false
        }
    }

    /**
     * Clear abandoned downloads — **but not the part-file, which is the whole point of resuming.**
     *
     * ── WHAT CHANGED HERE, AND WHY THE OLD LINE WAS A BUG THE MOMENT RESUME EXISTED ───────────
     *
     * This used to be `incoming.listFiles()?.forEach { it.delete() }` — everything, unconditionally —
     * and it runs on **every** [refresh], which is every time the card appears. With a resumable
     * download that would delete 200 MB of somebody's prepaid bundle between the pause and the tap on
     * Resume, and the readout would cheerfully start again from zero having said "Paused with 200 MB
     * kept on this phone" one frame earlier.
     *
     * So the sweep now tests the NAME: [dwIsPartialFileName] survives, anything else goes. That is
     * exactly what the `.part` suffix was introduced for — a leftover can be told from a prefix worth
     * keeping without opening either or guessing at a length. A part-file that is genuinely stale is
     * not leaked: [dwResumePlan] refuses one at or past the pinned length, and the digest of the
     * assembled file refuses one that is a prefix of something else.
     */
    private fun sweepIncoming() {
        val incoming = File(File(context.filesDir, DW_ASR_MODEL_DIR), DW_ASR_MODEL_INCOMING)
        runCatching {
            incoming.listFiles()?.forEach { file ->
                if (!dwIsPartialFileName(file.name)) file.delete()
            }
        }
    }

    /** The one directory a partial transfer of any route is allowed to live in. See [sweepIncoming]. */
    private fun incomingDir(): File =
        File(File(context.filesDir, DW_ASR_MODEL_DIR), DW_ASR_MODEL_INCOMING)

    /** The part-file the CONTAINER route would resume from, whether or not it exists yet. */
    private fun partialFor(model: DwAsrModel, artifact: DwAsrModelArtifact): File =
        File(incomingDir(), dwPartialFileName("model-${model.modelId}${artifact.container.extension}"))

    /**
     * The part-file the ENDPOINT route would resume ONE pinned file from.
     *
     * **IN `incoming/`, NOT IN THE MODEL'S TARGET DIRECTORY, AND THE DIFFERENCE IS THE 300 MB A
     * PAUSE IS PRESSED TO KEEP.** Writing `model.int8.onnx.part` into `dwAsrModelDir` would look
     * tidier and would destroy the feature: every failure and cancellation arm on both fetch routes
     * runs `target.listFiles()?.forEach { it.delete() }`, and `installFromStaged` empties the same
     * directory on purpose, so a Pause would delete exactly the prefix it was pressed to preserve.
     * The container route survives a pause only because it lives here, and this one lives here for
     * the identical reason. [sweepIncoming] spares `.part` names specifically.
     *
     * The model id is in the name as well as the file name so that two models pinned in some future
     * build cannot collide on `tokens.txt.part` — a 47 KB vocabulary from the wrong artifact would
     * fail its digest rather than corrupt anything, but it would fail it after 365 MB.
     */
    private fun endpointPartFor(model: DwAsrModel, pinned: DwAsrModelFile): File =
        File(incomingDir(), dwPartialFileName("${model.modelId}-${pinned.fileName}"))

    /**
     * What is sitting in part-files right now and **which route put it there**.
     *
     * ── WHY THIS IS ONE FUNCTION AND NOT TWO ──────────────────────────────────────────────────
     *
     * It used to be `partialBytes()` alone and it was keyed on the container: it began
     * `dwAsrModelArtifactFor(pinned.modelId, artifacts) ?: return 0L`, so a paused ENDPOINT download
     * would have reported 0 bytes, never become [DwAsrModelState.PAUSED] in [refresh], and never
     * been offered as a RESUME — while `downloadFromEndpoint` would silently have carried on from
     * the surviving prefixes. The card would have named the full 365,438,543 bytes for a fetch that
     * was about to move rather less, which is a lie in the designer's favour and still a lie.
     *
     * THE ENDPOINT'S PARTS WIN WHEN BOTH EXIST. They are the route a resume would now take, and the
     * container's leftover is from a route that cannot run in this build at all.
     */
    private fun partialBytesAndSource(): Pair<Long, DwAsrModelSource?> {
        val pinned = model ?: return 0L to null
        val fromEndpoint = pinned.files.sumOf { file ->
            val part = endpointPartFor(pinned, file)
            if (part.isFile) part.length() else 0L
        }
        if (fromEndpoint > 0L) return fromEndpoint to DwAsrModelSource.DEPLOYMENT_ENDPOINT
        val artifact = dwAsrModelArtifactFor(pinned.modelId, artifacts) ?: return 0L to null
        val container = partialFor(pinned, artifact)
        val bytes = if (container.isFile) container.length() else 0L
        return bytes to (if (bytes > 0L) DwAsrModelSource.PINNED_CONTAINER else null)
    }

    /** Bytes sitting in a part-file right now, whichever route left them, or 0 when there are none. */
    private fun partialBytes(): Long = partialBytesAndSource().first

    /**
     * The manifest service, **built once for the life of this controller and not once per read.**
     *
     * [readManifest] used to call `ApiClient.retrofit(TokenStore(context)).create(...)` inside
     * itself, and [refresh] calls it on every card appearance and every "Check again" tap. Each call
     * stood up a whole `OkHttpClient` — its own `ConnectionPool` and its own `Dispatcher`
     * `ExecutorService` — plus a Retrofit and a dynamic proxy, none of which is ever shut down. Idle
     * threads and pooled sockets do time out, so it was bounded garbage rather than a leak, but
     * `WorkshopRepository`'s own rejected-alternative note names this exact construction as a reason
     * NOT to do something — "it would stand up an OkHttp client per field on a screen that draws
     * hundreds of them" — and a file cannot contradict the rule the repository argues from. It also
     * meant the manifest never reused the connection the previous check had just opened, on a card
     * whose whole purpose is a connection.
     *
     * `by lazy` and not eager, because a controller is created for every appearance of the card and
     * a designer with no connection never reaches this at all.
     *
     * A FRESH TOKEN IS STILL PICKED UP: `ApiClient.retrofit`'s auth interceptor calls
     * `tokenStore.getToken()` inside `intercept`, per request, so caching the service caches no
     * credential. What it DOES pin is the base URL — `ApiClient.retrofit` builds from
     * `BuildConfig.DEFAULT_API_BASE_URL` and not from [apiBaseUrl], so a probe that overrides
     * [apiBaseUrl] moves the BYTE routes and not this one. That was already true before the hoist;
     * it is written down here because the two now sit beside each other.
     */
    private val endpointApi: DwAsrModelEndpointApi by lazy {
        ApiClient.retrofit(TokenStore(context)).create(DwAsrModelEndpointApi::class.java)
    }

    /**
     * **ASK THIS DEPLOYMENT WHETHER IT SERVES THE MODEL.** One small JSON GET, and it may only ever
     * REFUSE a fetch.
     *
     * ── THE FOUR STATUS CODES ARE FOUR DIFFERENT NEXT MOVES ───────────────────────────────────
     *
     * 401 is a lapsed session and signing in again fixes it; 403 is an account that will never be
     * allowed and only whoever manages accounts can change it; 404 is this deployment's BUILD not
     * knowing the artifact id this APK pins, which is a version skew an update fixes; anything else,
     * and every `IOException`, is "could not ask". Collapsing any pair of them produces a designer
     * doing the one thing that cannot help. `WorkshopRepository` and `WorkshopSync` already keep the
     * 401 apart from everything else and say why in as many words.
     *
     * A 200 IS NOT A YES. The route answers 200 with `available: false` when the origin has not been
     * given the bytes, deliberately, so that a phone can tell "not published" from "your token
     * expired" — so the body goes through [dwAsrManifestVerdict], which is refuse-only. **Nothing
     * this function returns may be read as permission to trust a byte.** The digest that decides
     * that is taken off this phone's own disk, after the transfer, against the constant in the APK.
     *
     * ── THROUGH `ApiClient`, WHICH THE 365 MB STREAM DELIBERATELY IS NOT ──────────────────────
     *
     * See [DwAsrModelEndpointApi] for the argument. In short: this is exactly the small authenticated
     * GET the CloudFront 504 retry was written for, and the stream is exactly the request replaying
     * it would ruin.
     */
    private suspend fun readManifest(pinned: DwAsrModel): DwAsrEndpointState =
        withContext(Dispatchers.IO) {
            try {
                val manifest = endpointApi.asrModel(pinned.modelId)
                val verdict = dwAsrManifestVerdict(manifest, pinned)
                withContext(Dispatchers.Main) { manifestVerdict = verdict }
                dwAsrEndpointStateOf(verdict)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                // First, and rethrown: a refresh cancelled by the surface leaving must not be
                // recorded as this deployment answering anything at all.
                throw cancellation
            } catch (http: HttpException) {
                withContext(Dispatchers.Main) { manifestVerdict = null }
                when (http.code()) {
                    401 -> DwAsrEndpointState.SESSION_LAPSED
                    403 -> DwAsrEndpointState.FORBIDDEN
                    404 -> DwAsrEndpointState.VERSION_SKEW
                    else -> DwAsrEndpointState.UNREACHABLE
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { manifestVerdict = null }
                DwAsrEndpointState.UNREACHABLE
            }
        }

    /** The one verification, shared with [DwAsrModelRun]. See [dwAsrReadInstalledModel]. */
    private fun readInstalled(
        model: DwAsrModel,
        onBytesHashed: ((Long) -> Unit)? = null,
    ): DwAsrModelStatus = dwAsrReadInstalledModel(context, model, onBytesHashed)

    /**
     * Install it. **Whichever route the offer chose, and the gate is asked again here.**
     *
     * Asked again at the point of action rather than trusted from the composable that drew the button,
     * for `DwAsrRuntimeController.install`'s reason: a control can outlive the reading it was drawn
     * from by a whole workshop day, and by then the storage may be gone.
     */
    fun install() {
        val reading = measurement ?: return
        val pinned = model ?: return
        /*
         * THE OFFER IS ASKED WITH [endpoint], AND IT IS THIS LINE THAT MAKES THE NEW BUTTON DO
         * ANYTHING. Left at the default UNKNOWN here, the offer would fall through to the container
         * branch, answer CONTAINER_NOT_READABLE_IN_THIS_BUILD, fail `dwAsrModelMayInstall` and return
         * one line later having done nothing — a control that draws and cannot work, which is the
         * exact defect every comment in this file is written against.
         */
        val offer = dwAsrModelOffer(
            status, reading, connection, stagedFilesPresent, models, artifacts, endpoint,
        )
        if (!dwAsrModelMayInstall(offer)) return
        // WHICH ROUTE, decided once and in the pure layer. Deriving it a second time here — "if the
        // endpoint publishes, fetch from the endpoint" — is how a card that says one thing starts the
        // other. See [dwAsrModelSourceFor], and [pausedSource] for why a RESUME needs the disk asked.
        val source = dwAsrModelSourceFor(offer, endpoint, stagedFilesPresent, pausedSource)
        stopIntent = DwTransferControlState.RUNNING
        status = DwAsrModelStatus(DwAsrModelState.INSTALLING, pinned)
        busy = true
        job = scope.launch {
            val outcome = when (source) {
                DwAsrModelSource.STAGED_FILES -> installFromStaged(pinned)
                DwAsrModelSource.DEPLOYMENT_ENDPOINT -> downloadFromEndpoint(pinned)
                // PINNED_CONTAINER, and null — which `dwAsrModelMayInstall` has already made
                // unreachable, and which is answered here rather than with `error(...)` because a
                // crash on the install button is a worse outcome than one honest sentence.
                else -> {
                    val artifact = dwAsrModelArtifactFor(pinned.modelId, artifacts)
                    if (artifact == null) {
                        failed(pinned, "This app pins no download for ${pinned.modelId}.")
                    } else {
                        downloadAndInstall(pinned, artifact)
                    }
                }
            }
            status = outcome
            phase = null
            readout = null
            meter = null
            // The install just changed the answer the ladder reads. Published rather than left for
            // the next refresh, so a designer who installs the model and immediately dictates gets
            // the rung they just paid for.
            DwAsrModelRun.publish(outcome)
            busy = false
        }
    }

    /**
     * **STOP, AND KEEP WHAT HAS ARRIVED.** The half of the pair that does not re-spend the bytes
     * already paid for — the 292 MB the container route costs, or the 365 MB the endpoint route
     * does. Two figures rather than one since [DwAsrModelSource.DEPLOYMENT_ENDPOINT] exists: the
     * endpoint serves the files unpacked, so it is the dearer of the two on the wire.
     *
     * The intent is recorded BEFORE the job is cancelled, because the cleanup that runs inside the
     * cancellation is what reads it — reversed, the `catch` would delete the part-file a fraction of
     * a second before being told to keep it.
     *
     * Only meaningful for a fetch. A staged COPY has nothing to save: the source files are still on
     * this phone, so restarting costs seconds rather than megabytes, and offering "Pause" over a
     * three-second copy would be a control whose value nobody could tell from Cancel's.
     */
    fun pause() {
        if (status.state != DwAsrModelState.INSTALLING) return
        if (phase != DwTransferPhase.FETCHING) return
        stopIntent = DwTransferControlState.PAUSED
        job?.cancel()
        job = null
        phase = null
        meter = null
        /*
         * **AND THE READOUT GOES WITH IT.** This line was missing, and what stayed on screen was the
         * last live reading of a transfer that had stopped: "184 MB of 365 MB · 50% · 2.4 MB/s · about
         * 1 min left", frozen, beside the word Paused and a Resume button. The speed was a measurement
         * of a connection nothing was being asked of, and the minute never came — [tick] returns early
         * once the state is no longer INSTALLING, so nothing would ever have corrected it.
         *
         * Nothing true is lost by clearing it: the byte count a designer needs in order to decide
         * between Resume and Cancel is [dwPausedSentence], which is drawn from [partialOnDiskBytes] —
         * read off the disk, so it survives even a force-stop, which a remembered readout does not.
         * `cancel()` has always cleared this; only `pause()` did not.
         */
        readout = null
        busy = false
        status = DwAsrModelStatus(DwAsrModelState.PAUSED, status.model ?: model)
        DwAsrModelRun.publish(status)
    }

    /**
     * **STOP, AND GIVE THE SPACE BACK.** The brief's requirement in as many words: *"cancel reclaims
     * the space"*.
     *
     * It deletes the part-file and anything half-written into the model directory, so the next
     * reading of this phone finds NOT_INSTALLED rather than a prefix that would be offered as a
     * resume for a download the designer deliberately abandoned.
     */
    fun cancel() {
        val pinned = status.model ?: model
        stopIntent = DwTransferControlState.CANCELLED
        job?.cancel()
        job = null
        phase = null
        readout = null
        meter = null
        busy = true
        job = scope.launch {
            withContext(Dispatchers.IO) {
                pinned?.let { m ->
                    dwAsrModelArtifactFor(m.modelId, artifacts)?.let {
                        runCatching { partialFor(m, it).delete() }
                    }
                    // BOTH ROUTES' PREFIXES, because Cancel means "give the space back" and a
                    // designer who abandons a fetch does not care which shape it was arriving in.
                    // Missing this would leave up to 365 MB in `incoming/` that nothing ever opens
                    // and that the next refresh would offer as a Resume for a download they
                    // deliberately abandoned.
                    m.files.forEach { file -> runCatching { endpointPartFor(m, file).delete() } }
                    runCatching { dwAsrModelDir(context, m.modelId).listFiles()?.forEach { it.delete() } }
                }
            }
            val next = withContext(Dispatchers.IO) {
                if (pinned == null) DwAsrModelStatus(DwAsrModelState.NOT_INSTALLED) else readInstalled(pinned)
            }
            status = next
            DwAsrModelRun.publish(next)
            busy = false
        }
    }

    /**
     * The readout as of [nowMillis], **asked by the surface on its own clock.**
     *
     * This is what makes a stall visible. A stalled connection stops delivering bytes, so nothing
     * calls `observe` and the last readout would sit there for ever claiming 2.4 MB/s. The card ticks
     * once a second and asks this instead; [DwTransferMeter.readAt] records no sample, so it cannot
     * fabricate progress that did not happen.
     *
     * **AND FOR THE TWO PHASES NOBODY SAMPLES FOR, THIS IS WHERE THE SAMPLE COMES FROM.** Hashing and
     * unpacking bump [countedBytes] and nothing else; without the branch below the meter would still
     * be holding the FETCH's or the COPY's last reading, so the card printed that phase's speed and a
     * full bar under the heading "Checking the fingerprint", and ten seconds later called a phone
     * hashing at 300 MB/s *stalled*. The count fed here is a real one taken in the real loop — see
     * [dwAsrSha256OfFile]'s `onBytesHashed`.
     */
    fun tick(nowMillis: Long) {
        val live = meter ?: return
        if (status.state != DwAsrModelState.INSTALLING) return
        readout = if (meterFedByTick) live.observe(countedBytes, nowMillis) else live.readAt(nowMillis)
    }

    /**
     * **THE CABLE ROUTE. Copy the staged files into `filesDir`, then verify them there.**
     *
     * ── WHY IT COPIES RATHER THAN READING THEM WHERE THEY LIE ─────────────────────────────────
     *
     * Reading from the staging directory would be faster and would save 365 MB, and it is refused for
     * two reasons that are not stylistic. The staging directory is writable by something other than
     * this app — that is what makes it reachable by a cable — so a file verified there could be
     * different by the time the recogniser opened it, which is the exact gap between "what was
     * received" and "what is stored" that [DwAsrVerification]'s docstring is about. And `filesDir` is
     * where the shipped design says the model lives; a probe that measured a different arrangement
     * from the one that ships would have measured the wrong thing.
     *
     * EVERY FAILURE PATH DELETES WHAT IT WROTE. A half-copied model is worth nothing — the digest of a
     * prefix is not the digest of the file — and leaving one behind on a phone with a storage gate is
     * how a designer loses room to a file nothing will ever look at again.
     */
    private suspend fun installFromStaged(model: DwAsrModel): DwAsrModelStatus {
        val target = dwAsrModelDir(context, model.modelId)
        return try {
            withContext(Dispatchers.IO) {
                val staged = stagedModelFiles(context, model)
                    ?: return@withContext failed(
                        model,
                        "The model files are no longer where they were staged, so nothing has been " +
                            "installed. Push them again and tap “Check again”.",
                    )
                target.mkdirs()
                /*
                 * THE DIRECTORY IS EMPTIED, NOT ADDED TO — `installNow`'s argument in the engine's
                 * controller, and it bites harder here. A file left by an earlier pinned version
                 * would sit in the directory the recogniser is pointed at, match no pinned digest, and
                 * therefore never be hashed and never be deleted. Emptying first costs nothing (this
                 * is only reached when there is no verified install) and means the directory holds
                 * only files this run wrote and checked.
                 */
                runCatching { target.listFiles()?.forEach { it.delete() } }

                /*
                 * THE SAME METER THE DOWNLOAD USES, OVER A COPY. **The speed here is equally real.**
                 * One component, both routes, as the brief requires: two implementations diverge and
                 * the divergent one is the one nobody looks at.
                 *
                 * HOW LONG IT ACTUALLY TAKES, MEASURED ON THE FLEET'S SM-M325F ON 2026-08-13: the
                 * pinned 365,352,120 bytes copied inside /data in **1,256 ms including the sync** —
                 * about 290 MB/s. This comment used to say "roughly a minute", which was nobody's
                 * measurement and is out by a factor of forty-six. A copy that finishes in a second is
                 * still worth a readout (the phone it fails slowly on is not this one), but it is
                 * barely longer than [DW_RATE_MIN_WINDOW_MILLIS], so on THIS handset the honest thing
                 * the card shows for most of the copy is "measuring…" and then it is done.
                 */
                val total = model.files.sumOf { it.bytes }.coerceAtLeast(1L)
                startMeter(DwTransferPhase.COPYING, total, resumedFrom = 0L)
                var written = 0L
                model.files.forEach { pinned ->
                    val source = staged.getValue(pinned.fileName)
                    // The pinned name, never the staged file's own name, for the path — the same rule
                    // the engine's unpack keeps about archive entries.
                    val out = File(target, pinned.fileName)
                    source.inputStream().use { input ->
                        FileOutputStream(out).use { output ->
                            val buffer = ByteArray(DW_ASR_MODEL_BUFFER)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                written += read
                                publishProgress(written, total)
                            }
                            output.flush()
                            // On the platter before it is hashed. Hashing a file still in a buffer
                            // measures something that does not exist yet in the state it will be read
                            // in, which is the whole distinction this feature turns on.
                            output.fd.sync()
                        }
                    }
                }
                // The hash is 365 MB of reading, so it gets its own phase AND ITS OWN METER — a phase
                // change alone left the copy's numbers on screen under the fingerprint heading, which
                // is the defect [startMeter] and [tick] are now written against.
                startMeter(
                    DwTransferPhase.VERIFYING,
                    model.onDiskBytes,
                    resumedFrom = 0L,
                    fedByTick = true,
                )
                finishOrCleanUp(model, target, onBytesHashed = { countedBytes = it })
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // The surface left. Clean up on the way out and let the cancellation propagate.
            runCatching { target.listFiles()?.forEach { it.delete() } }
            throw cancellation
        } catch (error: Exception) {
            runCatching { target.listFiles()?.forEach { it.delete() } }
            failed(
                model,
                // The copy route can fill the volume too — it writes 365 MB — and its sentence is the
                // one that does NOT promise a resume, because a copy has nothing to resume from.
                if (dwIsDiskFull(error.message)) {
                    dwTransferDiskFullSentence(DwTransferPhase.COPYING)
                } else {
                    "The model could not be copied onto this phone: " +
                        "${error.message ?: "the copy failed"}. Nothing has been installed and " +
                        "nothing has been kept."
                },
            )
        }
    }

    /**
     * **THE CONTAINER ROUTE. Written, and unreachable in this build.**
     *
     * ── AND THE REASON IS NO LONGER "no control is drawn" ─────────────────────────────────────
     *
     * That is what this block used to say, and [DwAsrEndpointState] made it false: with
     * `endpoint == PUBLISHES` the offer answers [DwAsrModelOffer.DOWNLOAD], a control IS drawn, and
     * [install] dispatches it through [dwAsrModelSourceFor] to
     * [DwAsrModelSource.DEPLOYMENT_ENDPOINT] — `downloadFromEndpoint`, not this function. Left as it
     * was, the next reader would conclude the container route becomes live the day somebody sets
     * `ASR_MODEL_DIR`, when the truth is the opposite: provisioning the origin is what routes every
     * fetch AWAY from here.
     *
     * **What is still true is the conclusion.** This function has never run against a server,
     * because nothing can select it: [dwAsrModelSourceFor] only answers
     * [DwAsrModelSource.PINNED_CONTAINER] for an offer that reached step 4 of [dwAsrModelOffer],
     * and step 4 returns [DwAsrModelOffer.CONTAINER_NOT_READABLE_IN_THIS_BUILD] (or an endpoint
     * refusal) for a `.tar.bz2` — which [dwAsrModelMayInstall] refuses. The one other way in is a
     * RESUME attributed to the container, and `partialBytesAndSource` can only attribute one when a
     * container part-file exists, which only this function writes. So the route is closed on itself.
     *
     * It is here, and the archive is checked at the top rather than assumed, so that republishing
     * the same model as a `.zip` is two constants and no new code.
     *
     * THE REQUIREMENTS IT MEETS, EACH ON PURPOSE:
     *
     *  * **the digest is checked before the file is used** — the container's when it lands, then every
     *    unpacked file's, off disk, before anything opens them;
     *  * **a failed digest deletes the file and says so** — [DW_ASR_MODEL_MISMATCH_SENTENCE];
     *  * **a partial download cannot masquerade as complete** — the byte cap below stops a host that
     *    over-serves, the final length check refuses a short one, and the digest catches both anyway;
     *  * **IT RESUMES**, which it did not before. The old comment here read *"it is RESTARTABLE rather
     *    than RESUMABLE, and that is a choice with a cost … there is nothing to resume FROM without a
     *    range request the host may not honour"*. That cost is 292 MB of somebody's prepaid bundle
     *    every time a district-town connection drops, which on the link this app runs over is most
     *    times. So the range request is now made, and the case the old comment worried about — a host
     *    that will not honour it — is **checked on the response rather than hoped for**:
     *    [dwRangeHonoured] demands a 206 whose `Content-Range` starts exactly where we asked, and
     *    anything else truncates the part-file and starts from zero **without the word "resuming"
     *    ever appearing on screen**. The digest is still taken over the ASSEMBLED file, so a resume
     *    that stitched the wrong bytes together fails the same check a corrupt fresh download would.
     */
    private suspend fun downloadAndInstall(
        model: DwAsrModel,
        artifact: DwAsrModelArtifact,
    ): DwAsrModelStatus {
        if (!artifact.container.readableInThisBuild) {
            return failed(
                model,
                "This app cannot open a ${artifact.container.extension} archive, so there is no " +
                    "point spending ${artifact.downloadBytes} bytes fetching one. Nothing was " +
                    "fetched. The model can be put on this phone with a cable instead.",
            )
        }
        val incoming = File(File(context.filesDir, DW_ASR_MODEL_DIR), DW_ASR_MODEL_INCOMING)
        // THE ASSEMBLED BYTES LIVE UNDER A `.part` NAME UNTIL THE DIGEST HAS PASSED — the brief's
        // "a partial kept where it cannot be mistaken for a finished file". Nothing reads this
        // directory looking for model files (they go to `target`, elsewhere), and the sweep spares
        // this name specifically. See dwPartialFileName.
        val container = partialFor(model, artifact)
        val target = dwAsrModelDir(context, model.modelId)
        return try {
            withContext(Dispatchers.IO) {
                incoming.mkdirs()
                target.mkdirs()
                // The MODEL directory is emptied — a half-unpacked earlier attempt is worth nothing.
                // The CONTAINER is deliberately NOT deleted: it may be the prefix we are resuming.
                runCatching { target.listFiles()?.forEach { it.delete() } }

                val spaceRefusal = dwTransferSpaceRefusal(
                    measurement?.freeStorageBytes,
                    // Only what is still MISSING has to fit: on a resume the prefix is already
                    // written, and charging the designer's free space for bytes that are on the disk
                    // in front of them would refuse an install that would have completed.
                    (artifact.downloadBytes - (if (container.isFile) container.length() else 0L))
                        .coerceAtLeast(0L) + model.onDiskBytes,
                )
                if (spaceRefusal != null) return@withContext failed(model, spaceRefusal)

                download(artifact, container)
                // Its own meter over the container's own length — 292 MB of reading is not the 292 MB
                // of arriving that just finished, and drawing the second from the first's meter is
                // what made this card claim a download speed for a hash.
                startMeter(
                    DwTransferPhase.VERIFYING,
                    artifact.downloadBytes,
                    resumedFrom = 0L,
                    fedByTick = true,
                )
                val containerDigest = dwAsrSha256OfFile(container, onBytesHashed = { countedBytes = it })
                    ?: return@withContext failedAfterCleanUp(
                        model, container, target,
                        "The download could not be read back from this phone's storage after it " +
                            "finished, so nothing has been installed and what arrived has been " +
                            "deleted. That usually means the storage filled up while it was " +
                            "arriving. Free some space and try again.",
                    )
                if (dwAsrVerify(artifact.sha256, containerDigest) != DwAsrVerification.VERIFIED) {
                    cleanUp(container, target)
                    return@withContext failed(model, DW_ASR_MODEL_MISMATCH_SENTENCE)
                }
                /*
                 * UNPACKED BY NAME, FROM OUR OWN CONSTANTS, NOT BY WALKING THE ARCHIVE. `getEntry` is
                 * asked for each pinned name in turn and anything else the archive contains is
                 * ignored, which is what makes a traversal check unnecessary rather than merely
                 * unwritten: no attacker-controlled string reaches a path here.
                 */
                startMeter(
                    DwTransferPhase.UNPACKING,
                    model.onDiskBytes,
                    resumedFrom = 0L,
                    fedByTick = true,
                )
                var unpacked = 0L
                ZipFile(container).use { zip ->
                    model.files.forEach { pinned ->
                        val entry = zip.getEntry(pinned.fileName)
                            ?: return@withContext failedAfterCleanUp(
                                model, container, target,
                                "What arrived does not contain ${pinned.fileName}, which this app " +
                                    "needs, so nothing has been installed. The file being served is " +
                                    "not the file this app expects — tell whoever administers this " +
                                    "deployment.",
                            )
                        val out = File(target, pinned.fileName)
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(out).use { output ->
                                // A counted loop rather than `copyTo`, for one reason: `copyTo`
                                // reports nothing, and UNPACKING is 365 MB of writing that a designer
                                // watches. The count is the loop's own and the buffer is the same one.
                                val buffer = ByteArray(DW_ASR_MODEL_BUFFER)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                    unpacked += read
                                    countedBytes = unpacked
                                }
                                output.flush()
                                output.fd.sync()
                            }
                        }
                    }
                }
                // The archive has done its job. Deleted before the per-file checks so the phone is
                // not holding 657 MB longer than it must; if a check below fails the files go too.
                runCatching { container.delete() }
                // The SECOND hash — the unpacked files, off disk. A third meter, over a third length.
                startMeter(
                    DwTransferPhase.VERIFYING,
                    model.onDiskBytes,
                    resumedFrom = 0L,
                    fedByTick = true,
                )
                finishOrCleanUp(model, target, onBytesHashed = { countedBytes = it })
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            /*
             * **PAUSE AND CANCEL ARRIVE HERE AS THE SAME EXCEPTION, AND THEY MEAN OPPOSITE THINGS.**
             *
             * [stopIntent] is the only thing that tells them apart, and it is set before the job is
             * cancelled precisely so it is readable here. PAUSED keeps the part-file so the next tap
             * asks the server only for the remainder; everything else — Cancel, and the surface simply
             * leaving the composition — clears it, because bytes nobody chose to keep are bytes on a
             * district-office handset that nothing will ever open.
             *
             * The half-unpacked MODEL directory goes in both cases: it is not resumable in any state,
             * and a partial `model.int8.onnx` sitting beside a pinned name is exactly what
             * `installFromStaged`'s "empty the directory" argument is about.
             */
            runCatching { target.listFiles()?.forEach { it.delete() } }
            if (stopIntent != DwTransferControlState.PAUSED) {
                runCatching { container.delete() }
            }
            throw cancellation
        } catch (error: Exception) {
            /*
             * A FAILURE KEEPS THE PREFIX. The connection dropped; the bytes that arrived are good
             * (the digest has not been taken yet, and will be, over the assembled whole) and throwing
             * them away would charge the designer for them twice. The model directory still goes.
             */
            runCatching { target.listFiles()?.forEach { it.delete() } }
            val kept = runCatching { if (container.isFile) container.length() else 0L }.getOrDefault(0L)
            failed(
                model,
                when {
                    /*
                     * **THE VOLUME FILLING UP MID-TRANSFER GETS ITS OWN SENTENCE, AND IT ALREADY HAD
                     * ONE.** [DW_TRANSFER_DISK_FULL_SENTENCE] was written for exactly this moment and
                     * was drawn nowhere at all — the branch below printed the platform's own text, so
                     * what a designer read on a full phone was "The download stopped: write failed:
                     * ENOSPC (No space left on device). 184 MB is kept on this phone." `ENOSPC` is not
                     * a word anybody outside this file knows, and the sentence never said the one thing
                     * they could act on, which is that freeing space and resuming does not re-spend the
                     * 184 MB.
                     *
                     * The storage gate before the first byte is a different check for a different
                     * moment: it asks whether it fits. This is the answer when it fitted and a
                     * workshop's photographs landed during the hour the download was running.
                     */
                    dwIsDiskFull(error.message) && kept > 0L ->
                        "${dwTransferDiskFullSentence(DwTransferPhase.FETCHING)} " +
                            "${dwBytesLabel(kept)} is on this phone already."
                    dwIsDiskFull(error.message) ->
                        dwTransferDiskFullSentence(DwTransferPhase.FETCHING)
                    kept > 0L ->
                        "The download stopped: ${error.message ?: "the connection failed"}. " +
                            "${dwBytesLabel(kept)} is kept on this phone — Resume carries on from there."
                    else ->
                        "The download stopped: ${error.message ?: "the connection failed"}. " +
                            "Nothing was kept."
                },
            )
        }
    }

    /**
     * **THE DEPLOYMENT ROUTE. Fetch the two pinned files from this deployment, one at a time, and
     * verify them where they will be read.**
     *
     * ── WHY IT IS WRITTEN BESIDE [downloadAndInstall] AND NOT INSIDE IT ───────────────────────
     *
     * The container route's shape has no meaning here: one stream, one container digest, an unpack
     * keyed by `ZipFile.getEntry`. **There is no container, so there is no container digest and no
     * unpacking phase at all** — the endpoint serves the two files already unpacked, which is the
     * fact that deletes the bzip2 blocker rather than working around it. Folding both routes into one
     * function behind a flag would put a branch in the middle of the digest check, which is the one
     * place in this app that feeds unchecked bytes to a native graph executor.
     *
     * What IS reused is every pure helper the container route uses — [dwResumePlan],
     * [dwRangeHonoured], [dwParseContentRangeStart], [serverAcceptsRanges] — because reusing the
     * DECISIONS rather than the function body is what keeps their guarantees. A host that ignores
     * `Range` answers 200 with the whole file, and appending that to a prefix produces a corrupt
     * model that fails its digest an hour and a bundle later.
     *
     * ── ONE RANGE PER REQUEST, AND NEVER SEVERAL ──────────────────────────────────────────────
     *
     * `docs/ASR-MODEL-HOSTING.md` flags multipart ranges as a TRAP rather than a feature: several
     * ranges in one request answer **206** `multipart/byteranges`, so [dwRangeHonoured] would accept
     * the status while the body carries MIME boundaries nothing here parses — a corrupt file that
     * fails its digest. The request below asks for exactly one open range, `bytes=N-`, and nothing
     * else. A later "optimisation" to parallel ranges is the way this gets broken.
     *
     * ── THE PHASES ───────────────────────────────────────────────────────────────────────────
     *
     * FETCHING is one meter totalled over `model.onDiskBytes` across BOTH files, the shape
     * [installFromStaged] already uses for its cross-file copy, so the bar does not restart at zero
     * for `tokens.txt`. Then one VERIFYING pass, which is [readInstalled] and needs no new code: its
     * `onBytesHashed` is already documented as "the running total of bytes hashed across all of this
     * model's files".
     */
    private suspend fun downloadFromEndpoint(model: DwAsrModel): DwAsrModelStatus {
        val target = dwAsrModelDir(context, model.modelId)
        val parts = model.files.map { it to endpointPartFor(model, it) }
        return try {
            withContext(Dispatchers.IO) {
                incomingDir().mkdirs()
                target.mkdirs()
                // The MODEL directory is emptied — a half-written earlier attempt is worth nothing.
                // The PART-FILES are in `incoming/` and are deliberately untouched by this: they may
                // be the prefixes we are resuming. That separation is the whole of why they live
                // there; see [endpointPartFor].
                runCatching { target.listFiles()?.forEach { it.delete() } }

                val alreadyHere = parts.sumOf { (_, part) -> if (part.isFile) part.length() else 0L }
                /*
                 * THE SAME GATE THE OFFER USED, INCLUDING THE MARGIN. `dwAsrModelStorageNeededBytes`
                 * adds [DW_MODEL_FREE_STORAGE_MARGIN_BYTES] and the offer asked with it; a tap-time
                 * check that dropped it would allow an install the card had just refused, or refuse
                 * one it had just offered, thirty seconds apart and read by the same person. The
                 * margin exists because a phone filled to the last megabyte by a speech model is a
                 * phone that cannot record the workshop the model was installed for.
                 *
                 * Only what is still MISSING has to fit: the prefix is already written, and charging
                 * the designer's free space for bytes on the disk in front of them would refuse an
                 * install that would have completed.
                 */
                val refusal = dwTransferSpaceRefusal(
                    measurement?.freeStorageBytes,
                    (model.onDiskBytes - alreadyHere).coerceAtLeast(0L) +
                        DW_MODEL_FREE_STORAGE_MARGIN_BYTES,
                )
                if (refusal != null) return@withContext failed(model, refusal)

                startMeter(
                    DwTransferPhase.FETCHING,
                    model.onDiskBytes,
                    resumedFrom = alreadyHere,
                )
                /*
                 * What THIS attempt moves, across both files — the currency [publishProgress]'s
                 * last-frame escape has to be measured in, or it never fires on a resume.
                 *
                 * ONE KNOWN INACCURACY, AND IT IS IN THE READOUT ONLY. [alreadyHere] is counted
                 * before the per-file resume decisions are made, so if a host turns out NOT to
                 * honour `Range` a part-file is discarded and re-fetched from zero while this total
                 * still counts it. The bar then reads ahead of the truth until it catches up;
                 * `DwTransferMeter.readAt` clamps the percentage to 0–100 so nothing throws and no
                 * byte count is wrong. Asking the server about ranges before opening the meter would
                 * fix it at the cost of a round trip before anything is drawn, which is a worse
                 * trade on a district-town connection.
                 */
                val attemptTotal = (model.onDiskBytes - alreadyHere).coerceAtLeast(1L)
                var movedThisAttempt = 0L
                parts.forEach { (pinned, part) ->
                    movedThisAttempt += fetchEndpointFile(model, pinned, part) { moved ->
                        publishProgress(movedThisAttempt + moved, attemptTotal)
                    }
                }

                /*
                 * PROMOTED ONLY WHEN EVERY FILE IS COMPLETE, and only into the directory the
                 * recogniser reads. Until this moment nothing under a pinned name exists in `target`,
                 * so `dwAsrReadInstalledModel` — which looks up PINNED names and requires an exact
                 * length — cannot mistake a half-written fetch for an install.
                 */
                parts.forEach { (pinned, part) ->
                    val out = File(target, pinned.fileName)
                    runCatching { out.delete() }
                    if (!part.renameTo(out)) {
                        /*
                         * A RENAME INSIDE `filesDir` IS A METADATA OPERATION AND COSTS NO SPACE, so
                         * this is not the disk-full case and must not be worded as one — both paths
                         * are on the same volume by construction. It empties the model directory
                         * (a half-promoted model is worth nothing) and KEEPS whatever part-files are
                         * still in `incoming/`, so a retry re-fetches only what had already been
                         * moved rather than all 365 MB.
                         */
                        runCatching { target.listFiles()?.forEach { it.delete() } }
                        return@withContext failed(
                            model,
                            "The fetched files could not be moved into place inside this app's own " +
                                "storage, so nothing has been installed. What is still on the phone " +
                                "is kept. Tap “Check again” and try once more.",
                        )
                    }
                }

                // ONE VERIFYING PASS, over the files where they will actually be read — its own
                // meter over its own length, because a hash is not the transfer that just finished.
                startMeter(
                    DwTransferPhase.VERIFYING,
                    model.onDiskBytes,
                    resumedFrom = 0L,
                    fedByTick = true,
                )
                finishOrCleanUp(model, target, onBytesHashed = { countedBytes = it })
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            /*
             * COPIED VERBATIM FROM THE CONTAINER ROUTE, AND IT IS ONLY TRUE HERE BECAUSE THE PARTS
             * LIVE IN `incoming/`. Pause keeps the prefixes so the next tap asks the server only for
             * the remainder; Cancel, and the surface simply leaving, clear them. The half-written
             * MODEL directory goes in both cases: it is not resumable in any state.
             */
            runCatching { target.listFiles()?.forEach { it.delete() } }
            if (stopIntent != DwTransferControlState.PAUSED) {
                parts.forEach { (_, part) -> runCatching { part.delete() } }
            }
            throw cancellation
        } catch (error: Exception) {
            /*
             * A FAILURE KEEPS THE PREFIXES, for the container route's reason: the bytes that arrived
             * are good, the digest has not been taken yet and will be over the whole file, and
             * throwing them away would charge the designer for them twice.
             *
             * PER FILE THIS IS STRICTLY BETTER THAN THE CONTAINER ROUTE. `tokens.txt` is 86,423
             * bytes and will almost always be complete, so a drop during `model.int8.onnx` costs
             * nothing of the other file at all.
             */
            runCatching { target.listFiles()?.forEach { it.delete() } }
            val kept = runCatching {
                parts.sumOf { (_, part) -> if (part.isFile) part.length() else 0L }
            }.getOrDefault(0L)
            failed(
                model,
                when {
                    /*
                     * THE LAPSED SESSION GETS ITS OWN SENTENCE **AND KEEPS THE BYTES.** This is 365
                     * MB with a sixty-minute call timeout: a token can expire inside that window, and
                     * what a designer would otherwise read is "The download stopped: the server
                     * answered HTTP 401", which names nothing they can do. Signing in again and
                     * tapping Resume carries on from the prefix rather than re-spending it.
                     */
                    error is DwAsrEndpointSignedOut && kept > 0L ->
                        "This phone was signed out of the deployment while the model was arriving. " +
                            "${dwBytesLabel(kept)} is kept on this phone — sign in again and " +
                            "Resume carries on from there."
                    error is DwAsrEndpointSignedOut ->
                        "This phone was signed out of the deployment, so nothing could be fetched. " +
                            "Sign in again and try once more."
                    dwIsDiskFull(error.message) && kept > 0L ->
                        "${dwTransferDiskFullSentence(DwTransferPhase.FETCHING)} " +
                            "${dwBytesLabel(kept)} is on this phone already."
                    dwIsDiskFull(error.message) ->
                        dwTransferDiskFullSentence(DwTransferPhase.FETCHING)
                    kept > 0L ->
                        "The download stopped: ${error.message ?: "the connection failed"}. " +
                            "${dwBytesLabel(kept)} is kept on this phone — Resume carries on from there."
                    else ->
                        "The download stopped: ${error.message ?: "the connection failed"}. " +
                            "Nothing was kept."
                },
            )
        }
    }

    /**
     * Stream ONE pinned file into its part-file and return how many bytes THIS attempt moved.
     *
     * A COMPLETE PART-FILE IS LEFT ALONE RATHER THAN DISCARDED, and this is the one place the
     * per-file route may NOT simply hand [dwResumePlan] what it has. That function answers
     * `DISCARD_AND_RESTART` for a partial at or past the total, correctly, on the reasoning that "the
     * digest was never taken or it failed" — which is true of a single-file container, where the only
     * reason to be holding a full-length part-file is a failed check. **Here it is the ordinary
     * case**: `tokens.txt` is 86,423 bytes and finishes in the first second, so every resume of
     * `model.int8.onnx` would otherwise re-fetch a file that was already complete. The digest is
     * still taken, over the assembled file, after the promotion — nothing is trusted because it is
     * the right LENGTH.
     *
     * A part-file LONGER than pinned goes through [dwResumePlan] and is discarded, which is the
     * behaviour that guard actually exists for.
     */
    private suspend fun fetchEndpointFile(
        model: DwAsrModel,
        pinned: DwAsrModelFile,
        part: File,
        /** Called with the bytes moved for THIS FILE so far, so the caller can add its own offset. */
        onMoved: suspend (Long) -> Unit,
    ): Long {
        val onDisk = if (part.isFile) part.length() else 0L
        if (onDisk == pinned.bytes) return 0L

        val url = dwAsrModelFileUrl(apiBaseUrl, model.modelId, pinned.fileName)
        val acceptsRanges = onDisk > 0L && serverAcceptsRanges(url)
        var startFrom = when (dwResumePlan(onDisk, pinned.bytes, acceptsRanges)) {
            DwResumeDecision.RESUME_FROM_PARTIAL -> onDisk
            DwResumeDecision.START_FRESH, DwResumeDecision.DISCARD_AND_RESTART -> {
                runCatching { part.delete() }
                0L
            }
        }

        val builder = Request.Builder().url(url).get()
        // ONE OPEN RANGE AND NOTHING ELSE — see [downloadFromEndpoint] on multipart/byteranges.
        if (startFrom > 0L) builder.header("Range", "bytes=$startFrom-")
        client.newCall(builder.build()).execute().use { response ->
            // 401 BEFORE `isSuccessful`, so the sentence a designer reads names the session rather
            // than a status code. See the failure arm in [downloadFromEndpoint].
            if (response.code == 401) throw DwAsrEndpointSignedOut()
            if (!response.isSuccessful) {
                throw IllegalStateException("the server answered HTTP ${response.code}")
            }
            if (startFrom > 0L) {
                val honoured = dwRangeHonoured(
                    response.code,
                    dwParseContentRangeStart(response.header("Content-Range")),
                    startFrom,
                )
                // Not honoured: drop back to zero and truncate. A fresh start, correctly described
                // as one — the word "resuming" is never used for a fetch that is not resuming.
                if (!honoured) startFrom = 0L
            }
            val body = response.body ?: throw IllegalStateException("the server sent no file")

            var written = 0L
            body.byteStream().use { input ->
                // `append = startFrom > 0`: FileOutputStream truncates by default, which is precisely
                // the behaviour a resume must not have.
                FileOutputStream(part, startFrom > 0L).use { output ->
                    val buffer = ByteArray(DW_ASR_MODEL_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        // THE BYTE CAP, per file. Without it a host answering this URL with an
                        // endless stream fills the phone that holds a fortnight of unsynced
                        // fieldwork, and the digest check comes after the write so it would never be
                        // reached to notice.
                        if (startFrom + written > pinned.bytes) {
                            throw IllegalStateException(
                                "the server sent more than the ${pinned.bytes} bytes this app " +
                                    "expects for ${pinned.fileName}, which means it is not serving " +
                                    "the file this app pins"
                            )
                        }
                        output.write(buffer, 0, read)
                        onMoved(written)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (startFrom + written != pinned.bytes) {
                throw IllegalStateException(
                    "${pinned.fileName} stopped after ${startFrom + written} of ${pinned.bytes} bytes"
                )
            }
            return written
        }
    }

    /**
     * Stream the artifact to [container], **stopping the moment more bytes arrive than were pinned.**
     *
     * THE BYTE CAP IS NOT A NICETY. Without it, a host answering this URL with an endless stream fills
     * the phone that holds a fortnight of unsynced fieldwork — and the digest check would never be
     * reached to notice, because it comes after the write.
     */
    private suspend fun download(artifact: DwAsrModelArtifact, container: File) {
        /*
         * ── STEP 1: DECIDE, BEFORE A SOCKET IS OPENED, WHETHER THERE IS A PREFIX WORTH KEEPING ────
         *
         * The decision is [dwResumePlan], which is pure and tested — including the two cases that
         * would otherwise corrupt a 292 MB file silently: a part-file already at or past the pinned
         * length (append past the end) and a total nobody knows (no way to tell a prefix from a whole
         * file). Both answer DISCARD_AND_RESTART.
         */
        val onDisk = if (container.isFile) container.length() else 0L
        // Asked of the server rather than assumed. A HEAD is one round trip against an hour of
        // downloading, and its answer decides whether the word "resuming" may be used at all.
        val acceptsRanges = onDisk > 0L && serverAcceptsRanges(artifact.url)
        val plan = dwResumePlan(onDisk, artifact.downloadBytes, acceptsRanges)
        var startFrom = when (plan) {
            DwResumeDecision.RESUME_FROM_PARTIAL -> onDisk
            DwResumeDecision.START_FRESH, DwResumeDecision.DISCARD_AND_RESTART -> {
                runCatching { container.delete() }
                0L
            }
        }

        val builder = Request.Builder().url(artifact.url).get()
        if (startFrom > 0L) builder.header("Range", "bytes=$startFrom-")
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("the server answered HTTP ${response.code}")
            }
            /*
             * ── STEP 2: DID IT ACTUALLY HONOUR THE RANGE? ────────────────────────────────────────
             *
             * **A host that ignores `Range` answers 200 with the whole file, not an error.** Appending
             * that to a 200 MB prefix produces a 492 MB file that fails its digest an hour later,
             * having spent the entire bundle. So the response is inspected and, where the range was
             * not honoured, the offset is dropped back to zero and the part-file truncated — a fresh
             * start, correctly described as one, which is the brief's *"if the server will not honour
             * Range, restart and do not claim to resume"*.
             */
            if (startFrom > 0L) {
                val honoured = dwRangeHonoured(
                    response.code,
                    dwParseContentRangeStart(response.header("Content-Range")),
                    startFrom,
                )
                if (!honoured) startFrom = 0L
            }
            val body = response.body ?: throw IllegalStateException("the server sent no file")

            startMeter(DwTransferPhase.FETCHING, artifact.downloadBytes, resumedFrom = startFrom)
            var written = 0L
            body.byteStream().use { input ->
                // `append = startFrom > 0`: the one flag that decides whether the prefix survives the
                // stream being opened. FileOutputStream truncates by default, which is precisely the
                // behaviour a resume must not have.
                FileOutputStream(container, startFrom > 0L).use { output ->
                    val buffer = ByteArray(DW_ASR_MODEL_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (startFrom + written > artifact.downloadBytes) {
                            throw IllegalStateException(
                                "the server sent more than the ${artifact.downloadBytes} bytes this " +
                                    "app expects, which means it is not serving the file this app pins"
                            )
                        }
                        output.write(buffer, 0, read)
                        // WHAT THIS ATTEMPT WILL MOVE, not what the file is. `written` counts this
                        // attempt, so handing the whole file's length as the second argument meant the
                        // "always publish the last frame" escape in [publishProgress] could never fire
                        // on a resume — the final reading was thrown away by the throttle and the bar
                        // stopped at whatever the previous quarter-second had said.
                        publishProgress(written, artifact.downloadBytes - startFrom)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (startFrom + written != artifact.downloadBytes) {
                throw IllegalStateException(
                    "the download stopped after ${startFrom + written} of " +
                        "${artifact.downloadBytes} bytes"
                )
            }
        }
    }

    /**
     * Whether the host will serve a byte range. **Asked, never assumed** — see [dwResumePlan].
     *
     * A HEAD costs one round trip against an hour of downloading, and a wrong guess here costs the
     * whole 292 MB. A host that fails the HEAD entirely (some CDNs refuse the method) is treated as
     * "will not honour ranges", which is the fail-closed direction: the download restarts, which is
     * merely expensive, rather than appending to a prefix that will not line up, which is expensive
     * AND ends in a digest failure the designer cannot diagnose.
     *
     * **A URL AND NOT A [DwAsrModelArtifact]**, because the per-file route has no artifact — it has
     * a path built from two constants. The container route passes `artifact.url` and behaves exactly
     * as it did; `DwAsrModelTransferProbeTest`, which is the only thing that has ever driven this
     * against a real host, goes through that call site unchanged.
     *
     * NOTE FOR THE DEPLOYMENT ROUTE: this is a SECOND authenticated round trip through CloudFront,
     * and whether that distribution forwards HEAD for this route is unverified here — Docker is down
     * and nothing could be exercised end to end. If it does not, the answer is "no ranges", the
     * download restarts rather than corrupting anything, and the word "resuming" is correctly never
     * used. Expensive and honest is the direction this function is written to fail in.
     */
    private fun serverAcceptsRanges(url: String): Boolean = runCatching {
        client.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
            response.isSuccessful &&
                response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
        }
    }.getOrDefault(false)

    /**
     * Open a meter for one phase and publish its first reading, so the card never starts blank.
     *
     * **EVERY PHASE GETS ITS OWN METER, AND THAT IS THE POINT.** Setting `phase` alone used to be the
     * whole of the VERIFYING and UNPACKING transitions, which left the previous phase's meter in place
     * and its numbers on screen under the new heading. A phase is a different quantity of a different
     * thing moving at a different speed; it gets its own total, its own clock and its own zero.
     *
     * @param fedByTick true when the phase's producer only bumps [countedBytes] — see [tick].
     */
    private suspend fun startMeter(
        phase: DwTransferPhase,
        total: Long?,
        resumedFrom: Long,
        fedByTick: Boolean = false,
    ) {
        val opened = DwTransferMeter(totalBytes = total, resumedFromBytes = resumedFrom)
        countedBytes = 0L
        withContext(Dispatchers.Main) {
            meter = opened
            meterFedByTick = fedByTick
            this@DwAsrModelController.phase = phase
            readout = opened.observe(0L, SystemClock.elapsedRealtime())
        }
    }

    /**
     * Feed the meter and publish, **at most [DW_PROGRESS_MIN_INTERVAL_MS] apart.**
     *
     * The throttle is not about the meter, which is cheap — it is about composition. At 64 KiB a
     * buffer a fast link calls this a thousand times a second, and a `mutableStateOf` write per call
     * would recompose the settings screen a thousand times a second and slow the very download it is
     * reporting on. A quarter-second is faster than a person reads a changing number.
     *
     * `SystemClock.elapsedRealtime` and NOT `currentTimeMillis`: it is monotonic. A wall clock that
     * an NTP sync steps backwards mid-download would hand the meter a negative span and produce a
     * speed nobody could explain.
     */
    private suspend fun publishProgress(movedThisAttempt: Long, attemptTotal: Long) {
        val now = SystemClock.elapsedRealtime()
        // The second condition is the escape hatch that guarantees the LAST frame is drawn however
        // close to the previous one it falls. It has to be measured in the same currency as the first
        // argument — bytes THIS attempt moves — or it never fires on a resume. See the call site.
        if (now - lastPublishedAt < DW_PROGRESS_MIN_INTERVAL_MS && movedThisAttempt < attemptTotal) return
        lastPublishedAt = now
        val live = meter ?: return
        val next = live.observe(movedThisAttempt, now)
        withContext(Dispatchers.Main) {
            if (status.state == DwAsrModelState.INSTALLING) {
                readout = next
                // The status's own percent is kept in step so anything reading the STATUS rather than
                // the readout — the dictation ladder's published copy, for one — is not left stale.
                status = status.copy(percent = next.percent)
            }
        }
    }

    /** When [publishProgress] last wrote, on the monotonic clock. Read only from the IO coroutine. */
    private var lastPublishedAt: Long = 0L

    /** Verify what was written; keep it if every file matched, delete all of it if any did not. */
    private fun finishOrCleanUp(
        model: DwAsrModel,
        target: File,
        /** Fed with bytes hashed so far, so the VERIFYING readout is about the hashing. */
        onBytesHashed: ((Long) -> Unit)? = null,
    ): DwAsrModelStatus {
        val installed = readInstalled(model, onBytesHashed)
        if (installed.state == DwAsrModelState.INSTALLED) return installed
        runCatching { target.listFiles()?.forEach { it.delete() } }
        return failed(
            model,
            installed.failure ?: "The model was written to this phone but the files did not match " +
                "what this app expects, so they have been deleted and nothing was installed.",
        )
    }

    private fun cleanUp(container: File, target: File) {
        runCatching { container.delete() }
        runCatching { target.listFiles()?.forEach { it.delete() } }
    }

    private fun failed(model: DwAsrModel, sentence: String): DwAsrModelStatus =
        DwAsrModelStatus(DwAsrModelState.FAILED, model, failure = sentence)

    private fun failedAfterCleanUp(
        model: DwAsrModel,
        container: File,
        target: File,
        sentence: String,
    ): DwAsrModelStatus {
        cleanUp(container, target)
        return failed(model, sentence)
    }

    /**
     * Remove the model again. **The card promises this, so it exists.**
     *
     * 365 MB is a great deal of a district-office handset, and a designer who installed it before a
     * field trip must be able to have the space back without uninstalling the app and losing
     * everything unsynced with it.
     */
    fun remove() {
        val pinned = status.model ?: model ?: return
        job?.cancel()
        busy = true
        job = scope.launch {
            val next = withContext(Dispatchers.IO) {
                // The recogniser holds this file open. Closed first, or the delete succeeds on some
                // filesystems and the mapping survives, leaving a decode running against bytes that
                // no longer have a name.
                DwAsrSpeechModel.release()
                val dir = dwAsrModelDir(context, pinned.modelId)
                runCatching { dir.listFiles()?.forEach { it.delete() } }
                runCatching { dir.delete() }
                // Read the disk again rather than assuming the delete worked: a file this app could
                // not remove is still a file the recogniser could open, and the card must say so.
                readInstalled(pinned)
            }
            status = next
            DwAsrModelRun.publish(next)
            busy = false
        }
    }

    /**
     * Drop the work when the surface showing the model leaves.
     *
     * **LEAVING IS A PAUSE, NOT A CANCEL, AND THAT IS A CHANGE.** The old card said in as many words:
     * *"Stay on this screen while it finishes — leaving stops it, and a part-finished install is
     * thrown away."* That sentence was true and the behaviour behind it was indefensible once a
     * resume existed: a designer who backed out to answer a message lost 200 MB of a prepaid bundle.
     * The prefix now survives, the next reading of this phone finds it, and the card offers Resume.
     * Cancel is still one tap away and still reclaims the space.
     */
    fun release() {
        if (status.state == DwAsrModelState.INSTALLING && phase == DwTransferPhase.FETCHING) {
            stopIntent = DwTransferControlState.PAUSED
        }
        job?.cancel()
        job = null
        busy = false
    }
}

/**
 * The transfer stopped because this phone is signed out of the deployment. **HTTP 401, and its own
 * type.**
 *
 * A type rather than a message match, because the sentence a designer reads has to differ from every
 * other transport failure and matching on `"HTTP 401"` in a string is how that stops working the day
 * somebody rewords the generic sentence. It is a failure that KEEPS the part-files: the bytes that
 * arrived are good, and a fresh sign-in resumes from them rather than re-spending 365 MB.
 */
private class DwAsrEndpointSignedOut : IllegalStateException("the deployment rejected this session")

/** How many bytes are read at a time. 64 KiB, as everywhere else in this app. */
private const val DW_ASR_MODEL_BUFFER = 64 * 1024

/**
 * The shortest gap between two progress writes into composition. **250 ms.**
 *
 * At 64 KiB a buffer, a fast link would otherwise recompose the settings screen a thousand times a
 * second and slow the download it is reporting on. A quarter-second is already faster than a person
 * can read a changing number.
 */
private const val DW_PROGRESS_MIN_INTERVAL_MS = 250L

/** A controller that exists only while [active], and reads the phone once when it becomes so. */
@Composable
internal fun rememberDwAsrModel(active: Boolean): DwAsrModelController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope) { DwAsrModelController(context, scope) }
    DisposableEffect(active) {
        if (active) controller.refresh()
        onDispose { if (active) controller.release() }
    }
    return controller
}

// ---------------------------------------------------------------------------------------------
// The surface
// ---------------------------------------------------------------------------------------------

/**
 * The model: **name, size, memory needed, this device's verdict, one action** — and, while bytes are
 * moving, the readout the owner asked for first.
 *
 * EVERY SENTENCE COMES FROM `data/`, and none is written here. This composable decides which of them
 * is drawn and in what order, and nothing else.
 *
 * ── WHAT WAS TAKEN OFF THIS CARD, AND WHY IT WAS NOT STATE ────────────────────────────────────
 *
 * `DW_ASR_VERIFY_SENTENCE` — a paragraph explaining what a fingerprint is — is gone. It is the app
 * describing its own mechanism, which is the definition of narration: nothing a designer reads there
 * changes anything they do, and the guarantee it describes is kept whether or not they read it. What
 * replaced it is [DW_ASR_MODEL_MISMATCH_SENTENCE], printed **only when a check actually fails**,
 * which is the moment the fact is worth a sentence and the moment they can act on it.
 *
 * @param support the platform's own pack answer, threaded in so this card can say what the model
 *   would ADD to this handset rather than what it serves in the abstract — see
 *   [dwAsrModelWhatItBuysSentence], where the defect that made this parameter necessary is written up.
 */
@Composable
internal fun DwAsrModelBody(
    controller: DwAsrModelController,
    languageLabels: Map<String, String>,
    support: DwRecognitionSupport?,
    modifier: Modifier = Modifier,
) {
    val reading = controller.measurement
    /*
     * ONE TICK A SECOND WHILE, AND ONLY WHILE, BYTES ARE MOVING. **This is what makes a stall
     * visible**: a stalled connection stops delivering, so nothing feeds the meter and the last
     * readout would sit there claiming 2.4 MB/s for ever. `readAt` records no sample, so asking on
     * this clock cannot fabricate progress that did not happen.
     *
     * Keyed on the state so the loop does not exist at all on the ordinary card — a settings screen
     * that recomposed once a second for nothing would be a battery cost paid by every designer who
     * never installs anything.
     */
    if (controller.status.state == DwAsrModelState.INSTALLING) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(1_000)
                controller.tick(SystemClock.elapsedRealtime())
            }
        }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (reading == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Looking at what this phone has…", color = MaterialTheme.field.muted, fontSize = 12.sp)
            }
            return@Column
        }

        // THE CONTROLLER'S OWN CATALOGUES, not the module constants. In production they are the same
        // two lists; asking the controller means the card cannot decide from one catalogue while the
        // install it starts decides from another, which is the only way the seam above could bite.
        val offer = dwAsrModelOffer(
            controller.status,
            reading,
            controller.connection,
            controller.stagedFilesPresent,
            controller.modelCatalogue,
            controller.artifactCatalogue,
            controller.endpoint,
        )
        // WHERE THE BYTES WOULD COME FROM, asked once and used for the wording. The endpoint route
        // is 365,438,543 bytes on the wire against the container's 292,571,207 — about 25% MORE —
        // and it is `dwAsrModelOfferSentence`'s DOWNLOAD arm that prints the figure a designer reads
        // before spending a prepaid bundle. See [dwAsrModelSourceFor].
        val source = dwAsrModelSourceFor(
            offer,
            controller.endpoint,
            controller.stagedFilesPresent,
            controller.pausedSource,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Speech model",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                dwAsrModelStateLabel(controller.status.state),
                color = if (controller.status.state == DwAsrModelState.INSTALLED) {
                    MaterialTheme.field.success
                } else {
                    MaterialTheme.field.muted
                },
                fontSize = 11.sp
            )
        }
        Text(
            dwAsrModelOfferSentence(
                offer,
                reading,
                controller.modelCatalogue,
                controller.artifactCatalogue,
                source,
            ),
            color = MaterialTheme.field.body,
            fontSize = 13.sp,
        )

        /*
         * THE ONE THING THE OFFER SENTENCE CANNOT SAY, because it is about a deployment rather than
         * about this phone: this deployment is serving a file under the name this app pins, and it
         * is NOT the file this app pins.
         *
         * It renders as NOT_PUBLISHED like an empty origin — from the handset's point of view a
         * deployment serving a different model is a deployment with no model it can use — but an
         * empty origin is somebody's to fill and this is somebody's to investigate, and only one of
         * them is worth phoning about. Nothing was fetched: the whole point of reading the manifest
         * is that this is discovered at one JSON read rather than at 365 MB.
         */
        if (controller.manifestVerdict == DwAsrManifestVerdict.DISAGREES_ON_DIGEST ||
            controller.manifestVerdict == DwAsrManifestVerdict.DISAGREES_ON_SIZE
        ) {
            Text(
                "This deployment is offering a different file under the name this app expects, so " +
                    "nothing was fetched. Tell whoever administers it.",
                color = MaterialTheme.field.warning,
                fontSize = 12.sp,
            )
        }

        /*
         * WHAT IT WOULD ADD **TO THIS PHONE** — printed in every state, including the ones where
         * nothing can be installed.
         *
         * "Does it hear the language I am going to a village to record" is the question a designer
         * asks BEFORE a field trip, and the answer decides whether they plan around it. A card that
         * withheld it until an install was possible would answer it with silence.
         *
         * `support` IS THE FIX FOR A LIVE DEFECT, not a tidy-up: without it this line told the
         * fleet's own handset that the model buys offline Hindi, which that phone already has from
         * Google's pack. See dwAsrModelWhatItBuysSentence.
         */
        Text(
            dwAsrModelWhatItBuysSentence(languageLabels, support),
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )

        controller.status.failure?.let { failure ->
            Text(failure, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }

        // What a pause actually left on the phone, in bytes, above the two buttons that decide its
        // fate. Read off the disk, so it survives the process that wrote it.
        if (offer == DwAsrModelOffer.RESUME && controller.partialOnDiskBytes > 0L) {
            Text(
                dwPausedSentence(controller.partialOnDiskBytes),
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
        }

        /*
         * ---- THE READOUT. The owner's first sentence, and the thing this lane was opened for. ----
         *
         *     "the loading bar should show speed, expected duration, and how much of it has been
         *      downloaded"
         *
         * The bar stays — it is a real fraction of a real total, both known before the first byte
         * moves — but it is no longer alone. `dwTransferLine` is the one composer of these numbers,
         * shared with the copy route and with whatever fetches a language model next, and every
         * clause in it drops out rather than being faked when the number behind it is unknown.
         */
        controller.readout?.let { live ->
            val stage = controller.phase
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                stage?.let {
                    Text(dwTransferHeading(it), color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                }
                live.percent?.let { percent ->
                    LinearProgressIndicator(
                        progress = { percent.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(dwTransferLine(live), color = MaterialTheme.field.body, fontSize = 12.sp)
                stage?.let { dwStalledSentence(live, it) }?.let { note ->
                    Text(note, color = MaterialTheme.field.warning, fontSize = 11.sp)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            dwAsrModelActionLabel(offer)?.let { label ->
                if (dwAsrModelMayInstall(offer)) {
                    Button(
                        onClick = { controller.install() },
                        enabled = !controller.busy,
                        modifier = Modifier.weight(1f)
                    ) { Text(label) }
                }
            }
            /*
             * PAUSE AND CANCEL, SIDE BY SIDE AND LABELLED APART, while a FETCH is running. The
             * difference between them is 292 MB of somebody's prepaid bundle, so they may not be one
             * button whose meaning has to be guessed — the same argument the dictation panel makes
             * about Stop and Discard, where the cost of guessing wrong is eighty seconds of speech.
             *
             * A COPY GETS CANCEL ONLY: its source files are still on this phone, so there is nothing
             * to save and restarting costs seconds. A "Pause" there would be a control whose value
             * nobody could tell from Cancel's.
             */
            if (controller.status.state == DwAsrModelState.INSTALLING) {
                if (controller.phase == DwTransferPhase.FETCHING) {
                    // The word comes from [dwPauseLabel], which exists so that no surface invents its
                    // own — and which had no caller at all: this button said "Pause" in a string
                    // literal here while the function saying "Pause" sat in `data/` with only a test
                    // looking at it. One of the two would have drifted, and it would have been this
                    // one, because it is the one a designer sees.
                    OutlinedButton(onClick = { controller.pause() }) {
                        Text(dwPauseLabel(DwTransferControlState.RUNNING))
                    }
                }
                OutlinedButton(onClick = { controller.cancel() }) { Text("Cancel") }
            }
            // A paused fetch can be abandoned without resuming it, and that is the tap that gives the
            // space back — see DwTransferControlState, where the pair is argued.
            if (offer == DwAsrModelOffer.RESUME) {
                OutlinedButton(onClick = { controller.cancel() }, enabled = !controller.busy) {
                    Text("Cancel")
                }
            }
            if (offer == DwAsrModelOffer.ALREADY_INSTALLED) {
                OutlinedButton(onClick = { controller.remove() }, enabled = !controller.busy) {
                    Text("Remove it")
                }
            }
            OutlinedButton(
                onClick = { controller.refresh() },
                enabled = !controller.busy && controller.status.state != DwAsrModelState.INSTALLING
            ) { Text("Check again") }
        }
    }
}
