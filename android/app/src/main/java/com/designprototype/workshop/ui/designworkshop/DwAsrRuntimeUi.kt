package com.designprototype.workshop.ui.designworkshop

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.designprototype.workshop.data.DW_ASR_CARD_BLURB
import com.designprototype.workshop.data.DW_ASR_MISMATCH_SENTENCE
import com.designprototype.workshop.data.DW_ASR_OFFER_BLURB
import com.designprototype.workshop.data.DW_ASR_VERIFY_SENTENCE
import com.designprototype.workshop.data.DW_ASR_WHAT_IT_BUYS
import com.designprototype.workshop.data.DwAsrArtifact
import com.designprototype.workshop.data.DwAsrOffer
import com.designprototype.workshop.data.DwAsrRuntimeState
import com.designprototype.workshop.data.DwAsrRuntimeStatus
import com.designprototype.workshop.data.DwAsrVerification
import com.designprototype.workshop.data.DwConnection
import com.designprototype.workshop.data.DwDeviceMeasurement
import com.designprototype.workshop.data.dwAsrArtifactFor
import com.designprototype.workshop.data.dwAsrCostSentence
import com.designprototype.workshop.data.dwAsrMayInstall
import com.designprototype.workshop.data.dwAsrOffer
import com.designprototype.workshop.data.dwAsrOfferSentence
import com.designprototype.workshop.data.dwAsrStateLabel
import com.designprototype.workshop.data.dwAsrMeasuredEngineBytes
import com.designprototype.workshop.data.dwAsrVerify
import com.designprototype.workshop.data.dwBytesLabel
import com.designprototype.workshop.data.dwProbeDevice
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * INSTALLING THIS APP'S OWN SPEECH ENGINE, IF AND ONLY IF A DESIGNER ASKS FOR IT.
 *
 * ── THE ANDROID HALF OF `data/DwAsrRuntime.kt` ────────────────────────────────────────────────
 *
 * The decision — may it be offered, what does it cost, what does every state say — is pure Kotlin
 * over in `data/`, where the desktop JVM tests it. Everything here is the part that cannot be: one
 * HTTP GET, one digest of a file on disk, one unpack, two SharedPreferences flags, and the two
 * surfaces that draw the answer. Same split as `DwLanguagePacks.kt` / `DwLanguagePackUi.kt`, same
 * reason — the untestable half is the half that is wrong, so it is kept small and dull.
 *
 * ── WHAT MAKES THIS DIFFERENT FROM THE PACK LANE, AND IT IS NOT THE SHAPE ─────────────────────
 *
 * The shape is copied wholesale and deliberately: a dismissible first-run CARD (never a modal — see
 * [DwLanguagePackOfferCard] at the top of `DwLanguagePackUi.kt` for why), a permanent card in
 * Settings, nothing selected to begin with, and the cost printed above the button rather than after
 * the tap.
 *
 * What is different is what is being fetched. A language pack is DATA and the platform fetches it;
 * this app never sees a byte. **This is a native library — CODE — which would run inside the process
 * that holds Aadhaar numbers, designer credentials and up to two weeks of unsynced fieldwork.** So
 * this file does five things the pack lane never has to:
 *
 *  1. **It fetches only over TLS from this deployment's own storage.** Enforced in the type:
 *     [DwAsrArtifact] will not hold a `http://` URL, so there is no code path here that could use one.
 *  2. **It writes into `filesDir` — internal storage — and nowhere else.** Not `cacheDir`, which the
 *     platform reclaims without warning: a half-reclaimed native library is not a miss, it is a crash
 *     on load. Not external storage, which other apps can rewrite.
 *  3. **It hashes the file ON DISK, after writing, and compares against a digest compiled into this
 *     APK.** Never a digest accumulated from the stream while writing: see [DwAsrVerification], which
 *     names the three ordinary ways those two answers differ.
 *  4. **It refuses to keep anything that does not match**, and says so in a sentence
 *     ([DW_ASR_MISMATCH_SENTENCE]) rather than retrying in silence on somebody's prepaid bundle.
 *  5. **It re-verifies on every run.** [readInstalled] hashes every library each time this surface
 *     appears, because "it matched when we installed it" is a claim about a file as it was before the
 *     phone rebooted, before the app updated, and before anybody with a cable rewrote it.
 *
 * **AND HERE IS WHAT NONE OF THAT ESTABLISHES.** The digest binds the bytes to what the RELEASE
 * BUILDER of this APK pinned, and to nothing else. It is not a signature, it proves nothing about
 * upstream provenance, and it cannot tell anybody that the file really is k2-fsa's build of
 * sherpa-onnx. docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md carries that chain in full; the honest summary is
 * that trusting this engine means trusting this app's release process.
 *
 * ── NOTHING IS LOADED HERE. THERE IS NO BINDING, AND THAT IS SAID PLAINLY ─────────────────────
 *
 * **THIS FILE CONTAINS NO CALL TO `System.load` OR `System.loadLibrary`, AND NO REFERENCE TO ANY
 * `com.k2fsa.sherpa.onnx` CLASS.** That is a deliberate stopping point rather than an unfinished
 * thought, for two measured reasons:
 *
 *  - **There is nothing to bind to.** docs/ASR-RUNTIME-MEASUREMENT.md §1 proved through the Gradle
 *    resolver that the sherpa-onnx AAR resolves from neither `google()` nor `mavenCentral()` under any
 *    of six spellings — six live 404s, not a network failure. Its Kotlin API is not on this compile
 *    classpath, so a binding cannot be written here at all today.
 *  - **Loading libraries nothing calls would prove nothing and could cost the process.** A
 *    `JNI_OnLoad` that fails can abort the process rather than throw, and whether these libraries load
 *    on the fleet's Galaxy M32 is listed as **unmeasured** in that document's §6. Calling `System.load`
 *    speculatively, on a screen a designer opened to read a settings card, would be finding that out
 *    the most expensive way available.
 *
 * **WHAT THE NEXT LANE MUST ADD, IN THIS ORDER**, and what breaks if it does not:
 *
 *  1. The Kotlin binding, by whichever of the three routes docs/ASR-RUNTIME-MEASUREMENT.md §1 lists
 *     (vendored AAR, build-time fetch, or upstream publishing to Maven Central) — each of which is
 *     somebody's decision and none of which is this lane's.
 *  2. `System.load` over [DwAsrArtifact.libraries] **in list order** — `libonnxruntime.so` before
 *     `libsherpa-onnx-jni.so` with the default AAR, or the second cannot resolve against the first —
 *     and gated on [dwAsrMayLoad] and nothing else.
 *  3. **KEEP RULES IN `proguard-rules.pro`.** R8 removed **all 123** `com.k2fsa.sherpa.onnx` classes
 *     whole in that measurement — zero kept, zero in `mapping.txt` — so a release build of any binding
 *     added in step 1 will fail with `ClassNotFoundException` or `UnsatisfiedLinkError` exactly where
 *     the debug build worked, in a courtyard, on a handset that cannot be attached to a debugger. Two
 *     rules are needed and neither can be inferred by R8: the binding's classes by name, and the JNI
 *     entry points the `.so` looks up by string. **No rule is added today**, because a keep rule for
 *     classes that are not in the build keeps nothing and would rot quietly until somebody trusted it.
 *
 * ── WHAT IS UNREACHABLE TODAY, SO NOBODY MISTAKES IT FOR TESTED ───────────────────────────────
 *
 * `DW_ASR_ARTIFACTS` is empty, so [DwAsrRuntimeController.install] cannot be reached from any surface:
 * the offer resolves to [DwAsrOffer.NOTHING_PUBLISHED_TO_INSTALL] before any control is drawn, and the
 * button is not drawn at all. **The download, unpack and verify path in this file has therefore never
 * been executed against a real server**, and that is the honest state of it. The pure half is tested
 * on the desktop JVM; this half will need a first run against a staging URL, which is the first thing
 * whoever publishes an artifact should do.
 */

// ---------------------------------------------------------------------------------------------
// Where the engine lives on disk
// ---------------------------------------------------------------------------------------------

/**
 * The engine's directory inside `filesDir`. **Internal storage, and the choice is load-bearing.**
 *
 * NOT `cacheDir`: the platform empties it whenever it wants space and does not tell the app, and a
 * native library that is half there is an `UnsatisfiedLinkError` in the middle of a workshop rather
 * than a miss that could be re-fetched. NOT external storage: other applications can write there.
 * `filesDir` is private to this app, is backed up by nothing, and survives until the app is
 * uninstalled or the designer removes the engine from the card.
 */
private const val DW_ASR_DIR = "asr-engine"

/** Where the container is written while it is being fetched, and from where it is unpacked. */
private const val DW_ASR_INCOMING = "incoming"

/**
 * The engine's directory for one ABI.
 *
 * [abi] is taken from [DwAsrArtifact.abi] and every file name below from
 * [com.designprototype.workshop.data.DwAsrLibrary.fileName]. **No name from the wire is ever used to
 * build a path in this file**, which is what makes the unpack below safe without a traversal check of
 * its own — the archive's own entry names are never read.
 *
 * BEING A COMPILED CONSTANT IS NOT ON ITS OWN A GUARANTEE THAT A STRING IS A BARE PATH SEGMENT, and an
 * earlier version of this comment argued that it was. Both types check it: each constructor rejects
 * `/`, `\` and `..`, so a release builder who writes `abi = "../databases"` gets a refusal naming the
 * mistake rather than an install that writes over this app's own database.
 */
private fun dwAsrAbiDir(context: Context, abi: String): File =
    File(File(context.filesDir, DW_ASR_DIR), abi)

/** How many bytes are read at a time, when hashing and when downloading. 64 KiB, as elsewhere. */
private const val DW_ASR_BUFFER = 64 * 1024

/**
 * SHA-256 of a file **as it is on disk**, lower-case hex — or null if it could not be read.
 *
 * THE WHOLE POINT IS THE "ON DISK" PART. A digest accumulated while writing the file proves what
 * arrived down the socket; this proves what a loader would open, which is a different fact whenever
 * the write was short, the volume filled, the process was killed mid-`close()`, or somebody replaced
 * the file afterwards. It costs one pass over ~24 MB, which is the price of the feature.
 *
 * Null rather than an exception, and null is never treated as a match: an unreadable file is not a
 * verified one, and the caller turns it into [DwAsrRuntimeState.NOT_INSTALLED] or a failure.
 *
 * `internal` RATHER THAN `private`, AND THE WIDENING IS DELIBERATE. `DwAsrEngineProbeTest` — the
 * instrumented probe that first ran a speech model on the fleet's handset — hashes the model files
 * before it will let anything load them, and it has to do that with **this** function rather than a
 * copy of it. A second eight-line read-and-digest loop in a test would be a second thing to keep
 * right, and the one that measured the engine would not be the one that ships. `internal` reaches
 * `androidTest` (it compiles as a friend of `main`) without reaching anything outside this module.
 */
internal fun dwAsrSha256OfFile(
    file: File,
    /**
     * Called with the RUNNING TOTAL of bytes hashed so far, after each buffer. Null for every caller
     * that does not draw a readout, which is most of them.
     *
     * **THIS EXISTS BECAUSE 365 MB OF HASHING IS A PHASE A DESIGNER WATCHES.** `DwTransferPhase`
     * insists every phase it carries is measurable in bytes; VERIFYING was drawn from the meter the
     * PREVIOUS phase had left behind, so the card reported the download's speed and a full bar while
     * the hash had not read a byte, and after ten seconds called itself stalled. The count is real
     * and it is right here in the loop, so it is reported rather than inferred.
     *
     * Deliberately NOT a suspend function: this runs once per 64 KiB — 5,570 times for the pinned
     * model — and the counter it feeds is turned into meter samples once a second on the main thread
     * by `DwAsrModelController.tick`. A dispatcher hop per buffer would cost more than the hash.
     */
    onBytesHashed: ((Long) -> Unit)? = null,
): String? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    var hashed = 0L
    file.inputStream().use { stream ->
        val buffer = ByteArray(DW_ASR_BUFFER)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
            hashed += read
            onBytesHashed?.invoke(hashed)
        }
    }
    digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}.getOrNull()

// ---------------------------------------------------------------------------------------------
// The controller
// ---------------------------------------------------------------------------------------------

/**
 * One reading of this handset's engine, and the one place a download can be started.
 *
 * Alive only while a surface that shows the engine is on screen ([rememberDwAsrRuntime]), for the same
 * reason the pack controller is: the work it does on appearing is real — a device probe and a hash of
 * every installed library — and doing it on a cold start would spend it on a designer who never opens
 * this card.
 *
 * Every method is called from the main thread, which is where composition runs; the blocking work
 * happens inside `withContext(Dispatchers.IO)` and the state is assigned after it returns, so
 * [status] is only ever written from the main dispatcher.
 */
@Stable
internal class DwAsrRuntimeController(
    private val context: Context,
    /** The composition's own scope, so a fetch dies with the surface that started it. */
    private val scope: CoroutineScope,
) {

    /** What the engine is doing on this phone. Starts as the honest UNKNOWN, never as "not there". */
    var status by mutableStateOf(DwAsrRuntimeStatus())
        private set

    /** The reading the offer is decided from — free storage and the ABI list. Null until taken. */
    var measurement by mutableStateOf<DwDeviceMeasurement?>(null)
        private set

    var connection by mutableStateOf(DwConnection.NONE)
        private set

    /** True while the disk is being read and hashed, or a fetch is in flight. */
    var busy by mutableStateOf(false)
        private set

    private var job: Job? = null

    /**
     * The HTTP client, built only if a download actually starts.
     *
     * `by lazy` rather than a field, so a designer who opens Settings and reads the card pays for no
     * connection pool at all. Its own client rather than `WorkshopRepository`'s (which is private to
     * that class) — and deliberately NOT the API client: this fetch carries no auth header and must
     * not, because it goes to object storage rather than to the API, and sending a session token to a
     * storage host is how tokens end up in somebody else's access logs.
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            // Generous, because this is tens of megabytes over a district-town connection — but not
            // unbounded: a stalled fetch has to end in a sentence rather than in a spinner that
            // outlives the workshop.
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.MINUTES)
            .build()
    }

    /**
     * Take a fresh reading: probe the device, read the connection, and hash whatever is installed.
     *
     * Safe to call again, and the "Check again" control does exactly that. Nothing is cached for the
     * life of the process: free storage moves through a working day, and a file that verified an hour
     * ago is not a file that verifies now.
     */
    fun refresh() {
        // A refresh must never interrupt a download in flight — the reading it would produce is worth
        // far less than the megabytes it would throw away.
        if (status.state == DwAsrRuntimeState.DOWNLOADING) return
        job?.cancel()
        busy = true
        job = scope.launch {
            val reading = withContext(Dispatchers.IO) { dwProbeDevice(context) }
            val here = dwConnection(context)
            val artifact = dwAsrArtifactFor(reading.abis)
            val next = withContext(Dispatchers.IO) {
                /*
                 * THE PART-DOWNLOAD NOBODY WOULD EVER HAVE FOUND. A fetch killed by a force-stop, a
                 * low-memory kill or a battery dying leaves the container in `incoming/` — and nothing
                 * else in this file would ever look there again: `readInstalled` reads the ABI
                 * directory, and `installNow` only deletes the one name it is about to write. So up to
                 * the whole download sat in this app's internal storage for good, invisible, on a
                 * handset this feature has a storage gate for precisely because it may be short of
                 * space — while `DwAsrOffer.RETRY`'s own sentence told the designer "nothing was kept.
                 * Whatever arrived has been deleted."
                 *
                 * Swept here rather than at install time because this is the first moment after a
                 * process death when the app looks at its own engine directory at all. It cannot race a
                 * live fetch: `refresh` returns above without launching anything while the state is
                 * DOWNLOADING, and only one surface showing the engine exists at a time.
                 */
                sweepIncoming()
                if (artifact == null) DwAsrRuntimeStatus(DwAsrRuntimeState.NOT_INSTALLED) else readInstalled(artifact)
            }
            measurement = reading
            connection = here
            status = next
            busy = false
        }
    }

    /**
     * Delete anything left in `incoming/`. **Called only when no fetch is in flight.**
     *
     * The directory holds exactly one thing — the container being downloaded — and it is worth nothing
     * the moment the fetch that was writing it stopped: the digest of a prefix is not the digest of the
     * file, so there is no resuming from it and nothing in this app tries to. What is left is bytes to
     * reclaim, and the only way to reclaim them is to look for them here, because no other code path in
     * this file reads this directory.
     */
    private fun sweepIncoming() {
        val incoming = File(File(context.filesDir, DW_ASR_DIR), DW_ASR_INCOMING)
        runCatching { incoming.listFiles()?.forEach { it.delete() } }
    }

    /**
     * Hash every library this artifact pins and decide what is actually on this phone.
     *
     * ALL OR NOTHING, AND THE "ALL" IS NOT PEDANTRY. If one of four libraries is missing the engine
     * cannot load, so the answer is NOT_INSTALLED and the offer stands; if one is PRESENT and does not
     * match its pinned digest, that is a file this app did not put there in the state it is in, and it
     * is deleted with a sentence rather than left beside three good ones for a future run to load.
     */
    private fun readInstalled(artifact: DwAsrArtifact): DwAsrRuntimeStatus {
        val dir = dwAsrAbiDir(context, artifact.abi)
        val verified = mutableListOf<String>()
        artifact.libraries.forEach { library ->
            val file = File(dir, library.fileName)
            if (!file.isFile) return DwAsrRuntimeStatus(DwAsrRuntimeState.NOT_INSTALLED, artifact)
            /*
             * The length is checked first purely as a cheap exit — a file of the wrong size cannot
             * hash to the right digest, and skipping 24 MB of SHA-256 on an obviously truncated
             * download is worth the two lines. IT IS NOT A SUBSTITUTE FOR THE HASH and no path below
             * treats a matching length as evidence of anything.
             */
            if (file.length() != library.bytes) {
                runCatching { file.delete() }
                return DwAsrRuntimeStatus(
                    DwAsrRuntimeState.NOT_INSTALLED,
                    artifact,
                    // Not a mismatch sentence: a truncated file is the ordinary result of a fetch that
                    // was interrupted, and telling a designer the server may be serving the wrong
                    // file would send them to an administrator over a dropped connection.
                    failure = null,
                )
            }
            val digest = dwAsrSha256OfFile(file)
                ?: return DwAsrRuntimeStatus(DwAsrRuntimeState.NOT_INSTALLED, artifact)
            if (dwAsrVerify(library.sha256, digest) != DwAsrVerification.VERIFIED) {
                runCatching { file.delete() }
                return DwAsrRuntimeStatus(
                    DwAsrRuntimeState.FAILED,
                    artifact,
                    failure = DW_ASR_MISMATCH_SENTENCE,
                )
            }
            verified += digest
        }
        // The constructor will refuse this if `verified` does not account for every library, which is
        // the invariant rather than a formality — see DwAsrRuntimeStatus's init.
        return DwAsrRuntimeStatus(DwAsrRuntimeState.INSTALLED, artifact, verifiedSha256 = verified)
    }

    /**
     * Fetch, verify and install the engine. **The only download in this feature, and it needs a tap.**
     *
     * The gate is asked again here, at the point of action, rather than trusted from the composable
     * that drew the button: a control can outlive the reading it was drawn from by a whole workshop
     * day if a designer leaves the screen open, and by then the storage may be gone.
     */
    fun install() {
        val reading = measurement ?: return
        val offer = dwAsrOffer(status, reading, connection)
        if (!dwAsrMayInstall(offer)) return
        val artifact = dwAsrArtifactFor(reading.abis) ?: return
        status = DwAsrRuntimeStatus(DwAsrRuntimeState.DOWNLOADING, artifact)
        busy = true
        job = scope.launch {
            val outcome = installNow(artifact)
            status = outcome
            busy = false
        }
    }

    /**
     * The fetch itself. Returns the status to publish; **writes nothing outside `filesDir`.**
     *
     * Every failure path deletes what it produced. A part-finished download is worth nothing — the
     * digest of a prefix is not the digest of the file — so keeping one to "resume later" would be
     * keeping megabytes that can only ever be thrown away, on a phone that is short of space often
     * enough for this feature to have a storage gate at all.
     */
    private suspend fun installNow(artifact: DwAsrArtifact): DwAsrRuntimeStatus {
        val incoming = File(File(context.filesDir, DW_ASR_DIR), DW_ASR_INCOMING)
        val container = File(incoming, "engine-${artifact.abi}.zip")
        val target = dwAsrAbiDir(context, artifact.abi)
        return try {
            withContext(Dispatchers.IO) {
                incoming.mkdirs()
                target.mkdirs()
                runCatching { container.delete() }
                /*
                 * THE DIRECTORY IS EMPTIED, NOT ADDED TO. An earlier release of this app may have
                 * pinned a library this one does not — a different upstream version, or the static-link
                 * variant after the dynamic one — and a `.so` left over from that pin would sit in the
                 * directory the loader is pointed at while matching no pinned digest, so nothing would
                 * ever hash it and nothing would ever delete it. `readInstalled` would still report a
                 * correct INSTALLED, because it hashes the names this build pins and finds them right.
                 * Emptying first costs nothing (this is only reached when there is no install: the
                 * offer is INSTALL or RETRY, never ALREADY_INSTALLED) and it means the directory holds
                 * only files this run wrote and verified.
                 */
                runCatching { target.listFiles()?.forEach { it.delete() } }
                download(artifact, container) { percent ->
                    // Back to the main dispatcher for the state write, so the progress bar is updated
                    // from the thread composition runs on.
                    withContext(Dispatchers.Main) {
                        if (status.state == DwAsrRuntimeState.DOWNLOADING) {
                            status = status.copy(percent = percent)
                        }
                    }
                }
                /*
                 * THE CONTAINER'S DIGEST, TAKEN OFF THE FILE ON DISK. This is the first of two checks
                 * and the only one that can be made while the archive still exists; the per-library
                 * digests below are the ones that can be re-made on every future run.
                 */
                val containerDigest = dwAsrSha256OfFile(container)
                    // CLEANED UP LIKE EVERY OTHER FAILURE, and it was not. This branch used to return
                    // without deleting anything, which left the whole download in `incoming/` under a
                    // sentence telling the designer to free some space — the one piece of advice the
                    // leak makes harder to follow.
                    ?: return@withContext failedAfterCleanUp(
                        artifact, container, target,
                        "The download could not be read back from this phone's storage after it " +
                            "finished, so nothing has been installed and what arrived has been " +
                            "deleted. That usually means the storage filled up while it was arriving. " +
                            "Free some space and try again.",
                    )
                if (dwAsrVerify(artifact.sha256, containerDigest) != DwAsrVerification.VERIFIED) {
                    cleanUp(container, target)
                    return@withContext failed(artifact, DW_ASR_MISMATCH_SENTENCE)
                }
                /*
                 * UNPACKED BY NAME, FROM OUR OWN CONSTANTS, AND NOT BY WALKING THE ARCHIVE. The names
                 * inside the zip are never read: `getEntry` is asked for each pinned name in turn, and
                 * anything else the archive happens to contain is ignored. That is what makes a
                 * traversal check unnecessary rather than merely unwritten — no attacker-controlled
                 * string reaches a path here. An archive missing one of the pinned names fails.
                 */
                ZipFile(container).use { zip ->
                    artifact.libraries.forEach { library ->
                        val entry = zip.getEntry(library.fileName)
                            ?: return@withContext failedAfterCleanUp(
                                artifact, container, target,
                                "What arrived does not contain ${library.fileName}, which this app " +
                                    "needs, so nothing has been installed. The file being served is " +
                                    "not the file this app expects — tell whoever administers this " +
                                    "deployment.",
                            )
                        val out = File(target, library.fileName)
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(out).use { output -> input.copyTo(output, DW_ASR_BUFFER) }
                        }
                    }
                }
                // The archive has done its job and is deleted BEFORE the per-file checks, so the
                // phone is not holding both copies any longer than it must — and if a check below
                // fails, the libraries go too and the next attempt is a clean fetch.
                runCatching { container.delete() }
                val installed = readInstalled(artifact)
                if (installed.state == DwAsrRuntimeState.INSTALLED) {
                    installed
                } else {
                    cleanUp(container, target)
                    failed(
                        artifact,
                        installed.failure ?: "The engine was fetched but the files written to this " +
                            "phone did not match what this app expects, so they have been deleted " +
                            "and nothing was installed. Try again on a better connection.",
                    )
                }
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            /*
             * The surface left, so the fetch is over. Clean up on the way out and let the cancellation
             * propagate — swallowing it would leave a coroutine believing it is still alive.
             *
             * THE LIBRARIES GO TOO, NOT ONLY THE ARCHIVE. Cancellation can land in the middle of the
             * unpack, which leaves a half-written `.so` beside whole ones — and the sentence a designer
             * read while it was running says in those words that "a part-finished download is thrown
             * away rather than kept, so nothing is left half-installed"
             * ([DwAsrOffer.IN_PROGRESS]'s own copy). Deleting only the container kept that promise for
             * the archive and broke it for the thing that actually gets loaded.
             */
            runCatching { cleanUp(container, target) }
            throw cancellation
        } catch (error: Exception) {
            runCatching { cleanUp(container, target) }
            failed(
                artifact,
                "The engine could not be fetched: ${error.message ?: "the connection failed"}. " +
                    "Nothing has been installed and nothing has been kept. Check the connection and " +
                    "try again.",
            )
        }
    }

    /**
     * Stream the artifact to [container], stopping the moment more bytes arrive than were pinned.
     *
     * THE BYTE CAP IS NOT A NICETY. Without it, a host that answers this URL with an endless stream
     * fills the phone that holds a fortnight of unsynced fieldwork — and the digest check would never
     * be reached to notice, because it comes after the write. The pinned size is known, so anything
     * beyond it is already wrong; failing at that byte is both cheaper and safer than failing later.
     */
    private suspend fun download(
        artifact: DwAsrArtifact,
        container: File,
        onPercent: suspend (Int?) -> Unit,
    ) {
        val request = Request.Builder().url(artifact.url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("the server answered HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("the server sent no file")
            var written = 0L
            var lastPercent = -1
            body.byteStream().use { input ->
                FileOutputStream(container).use { output ->
                    val buffer = ByteArray(DW_ASR_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (written > artifact.downloadBytes) {
                            throw IllegalStateException(
                                "the server sent more than the ${artifact.downloadBytes} bytes this " +
                                    "app expects, which means it is not serving the file this app pins"
                            )
                        }
                        output.write(buffer, 0, read)
                        // Whole percents only: a state write per 64 KiB would recompose the card four
                        // hundred times for one download.
                        val percent = (written * 100L / artifact.downloadBytes).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onPercent(percent)
                        }
                    }
                    output.flush()
                    // The bytes have to be on the platter, not in a buffer, before they are hashed —
                    // otherwise the hash is of a file that does not exist yet in the state it will be
                    // read in, which is the whole distinction this feature turns on.
                    output.fd.sync()
                }
            }
            if (written != artifact.downloadBytes) {
                throw IllegalStateException(
                    "the download stopped after $written of ${artifact.downloadBytes} bytes"
                )
            }
        }
    }

    /** Delete the archive and every library, so a failed attempt leaves nothing behind. */
    private fun cleanUp(container: File, target: File) {
        runCatching { container.delete() }
        runCatching { target.listFiles()?.forEach { it.delete() } }
    }

    private fun failed(artifact: DwAsrArtifact, sentence: String): DwAsrRuntimeStatus =
        DwAsrRuntimeStatus(DwAsrRuntimeState.FAILED, artifact, failure = sentence)

    private fun failedAfterCleanUp(
        artifact: DwAsrArtifact,
        container: File,
        target: File,
        sentence: String,
    ): DwAsrRuntimeStatus {
        cleanUp(container, target)
        return failed(artifact, sentence)
    }

    /**
     * Remove the engine again. **The card promises this, so it exists.**
     *
     * A designer who installed it before a field trip and needs the space back in September must be
     * able to have it, without uninstalling the app and losing everything unsynced with it.
     */
    fun remove() {
        val artifact = status.artifact ?: return
        job?.cancel()
        busy = true
        job = scope.launch {
            /*
             * THE RE-READ IS INLINE RATHER THAN A CALL TO [refresh], AND THAT IS NOT A STYLE CHOICE.
             * `refresh` begins by cancelling `job` — which, called from inside the coroutine that IS
             * `job`, would have this coroutine cancel itself half way through a delete. The state it
             * left behind would then depend on where the cancellation happened to take effect, which
             * is the sort of intermittent nonsense that gets blamed on the handset.
             */
            val next = withContext(Dispatchers.IO) {
                val dir = dwAsrAbiDir(context, artifact.abi)
                runCatching { dir.listFiles()?.forEach { it.delete() } }
                runCatching { dir.delete() }
                // Read the disk again rather than assuming the delete worked: a file this app could
                // not remove is still a file `System.load` could open, and the card must say so.
                readInstalled(artifact)
            }
            status = next
            busy = false
        }
    }

    /** Drop the work when the surface showing the engine leaves. */
    fun release() {
        job?.cancel()
        job = null
        busy = false
    }
}

/**
 * A controller that exists only while [active], and reads the phone once when it becomes so.
 *
 * [active] keeps a device probe off any screen that does not need it. The only caller left is the
 * Speech & AI screen, which needs the engine's status to answer for Tier 1 — see the note at the
 * bottom of this file about the two card surfaces that used to exist here.
 */
@Composable
internal fun rememberDwAsrRuntime(active: Boolean): DwAsrRuntimeController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope) { DwAsrRuntimeController(context, scope) }
    DisposableEffect(active) {
        if (active) controller.refresh()
        onDispose { if (active) controller.release() }
    }
    return controller
}

// ---------------------------------------------------------------------------------------------
// THE ENGINE'S TWO SURFACES ARE GONE: the first-run dashboard card, and the settings card.
//
// WHAT THEY WERE. `DwAsrRuntimeOfferCard` — a dashboard card headed "Dictation with no signal at
// all" — and `DwAsrRuntimeSettings`, a whole settings card measured at 176 words on the device.
//
// WHY THEY WENT, AND IT IS THE SAME REASON TWICE. **The engine is inside the APK.** It has been
// since 2026-08-12, when `DW_TIER1_RUNTIME_PRESENT` became true and `DW_ASR_ARTIFACTS` was left
// deliberately empty: every entry class in `com.k2fsa.sherpa.onnx` static-inits `System.loadLibrary`,
// which resolves through `ClassLoader.findLibrary`, which never looks in `filesDir` — so the `.so`
// has to be in the package and it is. A card about it therefore had **no state a designer can
// change and exactly one control, "Check again"**, which re-read a fact that cannot vary. That is
// principle 1's definition of narration: 176 words that are not state they need, not a number they
// need, and not a control they can press.
//
// WHAT SURVIVES, AND WHY THE CONTROLLER IS STILL HERE. `DwAsrRuntimeController` and
// `rememberDwAsrRuntime` are untouched, because `dwRecommendTiers` genuinely needs the engine's
// status to answer for Tier 1 — "is there machinery to feed a model" is a real input to a real
// decision. The reading is still taken; it is no longer read aloud. The single true fact it carried
// that a designer might want — that the engine ships signed with the app — now appears where it is
// actionable, on the model row, as part of the verdict.
// ---------------------------------------------------------------------------------------------
