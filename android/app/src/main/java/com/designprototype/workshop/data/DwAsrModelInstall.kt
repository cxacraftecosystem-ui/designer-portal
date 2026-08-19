package com.designprototype.workshop.data

import java.util.Locale

/**
 * **PUTTING THE SPEECH MODEL ON THIS PHONE — THE THREE ROUTES IN, AND THE ONE GATE THEY ALL GO
 * THROUGH.**
 *
 * It said TWO until this deployment's own per-file endpoint was wired up; the third is
 * [DwAsrEndpointState], declared in `DwAsrModelEndpoint.kt` beside the paths and the manifest
 * verdict. It changes nothing about the gate below, which is the point of saying so here.
 *
 * ── WHY THIS IS A SEPARATE FILE FROM `DwAsrRuntime.kt` ────────────────────────────────────────
 *
 * `DwAsrRuntime.kt` is about the ENGINE, and after the 2026-08-12 finding it is about an engine that
 * **cannot be installed at all**: every entry class in `com.k2fsa.sherpa.onnx` static-inits
 * `System.loadLibrary`, which resolves through `ClassLoader.findLibrary`, which never looks in
 * `filesDir` — so the `.so` has to be in the APK and it is (23,646,824 bytes at
 * `lib/arm64-v8a/libsherpa-onnx-jni.so`). [DW_ASR_ARTIFACTS] stays empty for that reason and
 * [DW_TIER1_RUNTIME_PRESENT] is now `true`.
 *
 * **THE MODEL IS THE OPPOSITE CASE IN EVERY RESPECT, WHICH IS WHY IT NEEDED ITS OWN FILE RATHER
 * THAN A ROW IN THE ENGINE'S CATALOGUE.**
 *
 *  | | the engine | the model |
 *  |---|---|---|
 *  | what it is | executable code, run inside the process holding Aadhaar numbers | **data**, fed to a graph executor |
 *  | where it must live | the APK, by a property of Android | **`filesDir`**, by absolute path — no loader constraint at all |
 *  | how big | 23.6 MB | **365 MB installed, 292 MB to fetch** |
 *  | shipped in the APK? | yes, necessarily | **no** — it is 14× the whole of the rest of the app, and this app's updater fetches the WHOLE APK on every release behind a dialog with no "Later" button |
 *
 * A `DwAsrArtifact` could not have carried it in any case: that type's constructor demands every file
 * be named `lib….so`, which is the check that stops a release builder pointing the engine installer
 * at something that is not a library. Widening it to hold a 365 MB ONNX graph would have deleted that
 * check for the one caller that needs it.
 *
 * ── THE GATE, WHICH IS THE SAME GATE, AND THAT IS THE POINT ───────────────────────────────────
 *
 * All three routes end in the same three lines: hash every pinned file **on disk, in this run**,
 * compare with [dwAsrVerify] against a digest compiled into the APK, and refuse to load anything that
 * did not match. There is no debug backdoor and no "skip verification" flag, because a sideload that
 * skipped the check would be the only path in this app that fed unchecked bytes to a native graph
 * executor — and it would be the path a developer uses every day, which is exactly the one that must
 * not be the weak one. **A shorter corridor, the same door.**
 *
 * ── WHY THE DECISION IS PURE ──────────────────────────────────────────────────────────────────
 *
 * Plain Kotlin over plain numbers and strings: no Context, no okhttp, no `java.io`, no Compose. The
 * platform half — one HTTP GET, one directory copy, one digest of a file — is
 * `ui/designworkshop/DwAsrModelInstallUi.kt`, and it decides nothing. Same split as
 * `DwAsrRuntime.kt` / `DwAsrRuntimeUi.kt`, same reason: the untestable half is the half that is wrong.
 */

// ---------------------------------------------------------------------------------------------
// What the published bytes arrive in — and whether this build can open it
// ---------------------------------------------------------------------------------------------

/**
 * The shape the published model arrives in, **and whether anything in this build can unpack it.**
 *
 * ── [readableInThisBuild] IS A FACT ABOUT THIS APK, NOT A POLICY ──────────────────────────────
 *
 * Android's class library carries `java.util.zip` — Deflate and GZIP — and **no bzip2 decoder at
 * all**. The model upstream publishes is a `.tar.bz2`, so this build genuinely cannot open it, and
 * the honest thing to do with that is say so on the card rather than offer a 292 MB fetch that dies
 * at the unpack having spent somebody's prepaid bundle to get there.
 *
 * **WHAT WAS DELIBERATELY NOT DONE ABOUT IT, WITH THE REASONING, SO NOBODY REDOES THE THINKING.**
 *
 *  * **Adding a bzip2 library** (`commons-compress` and friends) is a dependency on a compulsory
 *    download for the whole fleet — the same argument `DwAsrRuntime.kt` makes at length about the
 *    engine's 24 MB, and it belongs to whoever owns that trade rather than to this lane.
 *  * **Hand-rolling a bzip2 decoder** is several hundred lines of bit-twiddling whose output is fed
 *    straight to a native graph executor. A decompressor nobody has reviewed is a worse supply-chain
 *    decision than the one `ASR-RUNTIME-MEASUREMENT.md` §1 already declined.
 *  * **Republishing the model as a `.zip` on this deployment's own storage** was the move that would
 *    make [DOWNLOAD][DwAsrModelOffer.DOWNLOAD] live on THIS route — a release task rather than a code
 *    one, and docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md is where it is written up. Set
 *    [DwAsrModelArtifact.container] to [ZIP], pin the new digest, and every line below starts
 *    working with no other change. **SUPERSEDED RATHER THAN WRONG, AND KEPT SO NOBODY REDOES THE
 *    INVESTIGATION**: the deployment's own endpoint (`DwAsrModelEndpoint.kt`) serves the two files
 *    ALREADY UNPACKED, so it needs no archive and no republish, and it is the route that actually
 *    made a download button drawable. This paragraph still applies to anybody who wants the pinned
 *    GitHub container itself to become fetchable.
 */
enum class DwAsrContainerFormat(
    /** The published file's extension, used only to name the file on disk while it is being fetched. */
    val extension: String,
    /** Whether this build has a reader for it. See the class doc — a fact, not a preference. */
    val readableInThisBuild: Boolean,
) {
    /** `java.util.zip.ZipFile`, which is in the platform. The format a republished model should use. */
    ZIP(".zip", true),

    /**
     * What `k2-fsa/sherpa-onnx` actually publishes. **Nothing in this build can open one.**
     *
     * Kept as a value rather than left out so that the pinned row can describe the real artifact
     * truthfully and the card can say precisely what is missing. An enum that only held formats we
     * can read would have forced the catalogue to lie about the file it points at.
     */
    TAR_BZ2(".tar.bz2", false),
}

/**
 * ONE PUBLISHED MODEL CONTAINER, PINNED. **The record of where the bytes on this phone came from.**
 *
 * Every field is required and checked, for [DwAsrArtifact]'s reason: "fetch the model from here" must
 * not be expressible without also saying what the bytes have to hash to.
 *
 * ── THE ONE RULE THIS RELAXES AGAINST [DwAsrArtifact], AND WHY IT IS SAFE TO ──────────────────
 *
 * [DwAsrArtifact] insists the URL be on **this deployment's own storage** and says why: the engine is
 * executable code, and a third-party host is a party that can change the bytes without this
 * deployment knowing. **This row points at a GitHub release asset**, and the difference that makes it
 * defensible is not that GitHub is trustworthy — it is that **the digest is pinned in the APK and
 * checked before the file is used**, so a host that changed the bytes changes them into a file this
 * app deletes. What the deployment-storage rule buys the engine on top of that is availability and
 * IPv6 reachability, which are real, and are the reason a republished copy is still the better answer
 * (see [DwAsrContainerFormat]).
 *
 * **THE URL IS NOT INVENTED, WHICH IS THE WHOLE REASON IT IS ALLOWED TO BE HERE.** It is the exact
 * release asset the bytes on the fleet's handset were downloaded from, and both file digests taken off
 * that download have since answered [DwAsrVerification.VERIFIED] on the phone, twice.
 */
data class DwAsrModelArtifact(
    /** Which [DwAsrModel] this container carries. Matched on [DwAsrModel.modelId]. */
    val modelId: String,
    /** Absolute `https://` URL of the published container. */
    val url: String,
    /** Lower-case hex SHA-256 of the file at [url], as the release builder measured it. */
    val sha256: String,
    /** What the fetch costs a data bundle: the size of the file served at [url], in bytes. */
    val downloadBytes: Long,
    /** What shape it arrives in — and therefore whether this build can open it. */
    val container: DwAsrContainerFormat,
    /** The upstream release it came out of. Audit trail. */
    val upstreamVersion: String,
    /** How the release builder obtained it, in a sentence. The only record of what they believed. */
    val provenance: String,
) {
    init {
        require(modelId.isNotBlank() && '/' !in modelId && '\\' !in modelId && ".." !in modelId) {
            "A model artifact names the model it carries, and that name is also a directory inside " +
                "this app's internal storage. “$modelId” is not a bare name."
        }
        require(url.startsWith("https://")) {
            "A model artifact must be served over TLS. This is 292 MB fed to a native graph " +
                "executor inside the process holding Aadhaar numbers; the digest below is what makes " +
                "it safe, and plaintext would let somebody watch which model a district office is " +
                "fetching even where they could not change it. Do not relax this to make a test pass."
        }
        require(dwAsrIsSha256(sha256)) {
            "A model artifact needs the full 64-character hex SHA-256 of the exact file being " +
                "served. Take it with `sha256sum` on the published file. Nothing is unpacked without " +
                "it — dwAsrVerify answers NO_PINNED_DIGEST for a blank, and that refusal is the guard."
        }
        require(downloadBytes > 0L) {
            "A model artifact needs the real size of the file at its URL. This screen states the " +
                "cost before the tap, and a zero would print as “0 MB” beside a prepaid data bundle " +
                "for a fetch that is nearly three hundred megabytes."
        }
        require(upstreamVersion.isNotBlank()) {
            "A model artifact needs the upstream release it came out of, so a defect found later " +
                "can be traced to a version rather than to “the model”."
        }
        require(provenance.isNotBlank()) {
            "A model artifact needs a sentence saying how the release builder obtained it. The " +
                "digest proves only that the file is the one they pinned; this is the only record " +
                "of what they believed they were pinning."
        }
    }
}

/**
 * THE MODEL CONTAINERS THIS BUILD PINS. **One row, and its URL is a fact rather than a guess.**
 *
 * The lane that wrote `DwAsrRuntime.kt` left [DW_ASR_ARTIFACTS] empty and was right to: nothing had
 * been published and inventing a URL is what those constructors exist to prevent. **This row is the
 * other case.** The bytes were downloaded from exactly this URL on 2026-08-12, unpacked, pushed to the
 * fleet's SM-M325F, hashed on the phone, and used to transcribe Odia and Hindi — and the two file
 * digests in [DW_ASR_MODELS] came off that same download. Nothing here is a plan; it is a record of
 * something that happened.
 *
 * **THE CONTAINER'S SIZE AND DIGEST WERE RE-CONFIRMED AGAINST THE LIVE URL WHILE THIS ROW WAS
 * WRITTEN**: a `HEAD` through GitHub's redirect answered `Content-Length: 292571207`, byte for byte
 * the figure docs/DEVICE-TIER-MEASUREMENT.md recorded.
 *
 * **AND IT IS STILL NOT FETCHABLE BY THIS BUILD**, because it is a `.tar.bz2` and nothing here can
 * open one — see [DwAsrContainerFormat]. That is why the row is pinned anyway rather than left out:
 * the provenance of the bytes on the phone is a thing this app should be able to state whether or not
 * it can re-fetch them, and the day somebody republishes the same model as a `.zip` the only edit is
 * two constants.
 */
val DW_ASR_MODEL_ARTIFACTS: List<DwAsrModelArtifact> = listOf(
    DwAsrModelArtifact(
        modelId = "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2",
        sha256 = "cdcd0559c7c73efed54209a926e321afc914d046c5fdbf3665f00dc78180e5ed",
        downloadBytes = 292_571_207L,
        container = DwAsrContainerFormat.TAR_BZ2,
        upstreamVersion = "sherpa-onnx asr-models, asset " +
            "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2",
        provenance = "Downloaded 2026-08-12 from the k2-fsa/sherpa-onnx GitHub release tag " +
            "`asr-models` — the same project and the same release index the engine AAR comes from — " +
            "and unpacked locally. 292,571,207 bytes, hashing to the digest above; the two files " +
            "pinned in DW_ASR_MODELS came out of this exact archive and have both since answered " +
            "VERIFIED off the fleet handset's own storage, twice. Nothing upstream publishes a " +
            "signature for it, so this chain establishes what was pinned and not who made it.",
    ),
)

/** The container pinned for [modelId], or null when this build pins none for it. */
fun dwAsrModelArtifactFor(
    modelId: String,
    catalogue: List<DwAsrModelArtifact> = DW_ASR_MODEL_ARTIFACTS,
): DwAsrModelArtifact? = catalogue.firstOrNull { it.modelId == modelId }

// ---------------------------------------------------------------------------------------------
// What is on this handset right now
// ---------------------------------------------------------------------------------------------

/**
 * The state of the speech MODEL on this phone. The engine's five values, for the engine's reasons.
 *
 * [UNKNOWN] is first and is never rendered as "not installed": guessing "not there" offers a designer
 * a 292 MB fetch they may already have paid for, and guessing "there" promises offline dictation that
 * cannot happen. The same rule [DwAsrRuntimeState.UNKNOWN] and [DwPackState.UNKNOWN] follow.
 */
enum class DwAsrModelState {
    /** The app has not looked at its own files yet, or could not. Never rendered as "not installed". */
    UNKNOWN,

    /** Looked, and it is not there — or what is there did not hash to the pinned digests. */
    NOT_INSTALLED,

    /**
     * A copy or a fetch this app started is in flight, in this process, right now.
     *
     * NOT PERSISTED, for [DwAsrRuntimeState.DOWNLOADING]'s reason: a flag that outlived the process
     * comes back as a permanent spinner on a phone whose install died with a force-stop.
     */
    INSTALLING,

    /** On the phone, in `filesDir`, **and every file verified against its pinned digest this run.** */
    INSTALLED,

    /**
     * **THE DESIGNER STOPPED A FETCH AND WHAT HAD ARRIVED IS STILL ON THE PHONE.**
     *
     * Its own state rather than [FAILED], and the difference is 292 MB. FAILED means "nothing was
     * kept, start again"; this means "a prefix is sitting in a `.part` file and the next attempt asks
     * the server for the rest". Folding them together would either delete a prefix somebody chose to
     * keep, or promise a resume where nothing survives — and a designer on a district-town connection
     * pays for that mistake by the megabyte.
     *
     * NOT PERSISTED, exactly like [INSTALLING]: the part-file on disk is the durable half and is
     * found again by [dwResumePlan] on the next reading. A flag that outlived the process would come
     * back as "paused" on a phone whose part-file an OS cleaner had since removed.
     */
    PAUSED,

    /** The last attempt failed: the copy broke, or the bytes did not match. Retryable, with a reason. */
    FAILED,
}

/**
 * What the model is doing on this phone, **with the evidence attached**.
 *
 * The `init` invariant is the mechanism: an INSTALLED status cannot exist without the model it is
 * about and a digest for **every** file taken in this run. There is therefore no way to write
 * "installed" from a code path that only checked whether a file exists — which is precisely the
 * shortcut somebody adding a fast start-up check will reach for, and the one that would let a
 * substituted 365 MB graph be handed to a native executor.
 */
data class DwAsrModelStatus(
    val state: DwAsrModelState = DwAsrModelState.UNKNOWN,
    /** The model this state is about, or null when this build pins none. */
    val model: DwAsrModel? = null,
    /** Digests actually taken off the files on disk **in THIS RUN**. Compared as a multiset. */
    val verifiedSha256: List<String> = emptyList(),
    /** 0–100 while an install is in flight and the total is known; null when it is not. */
    val percent: Int? = null,
    /** Why the last attempt failed, as a sentence a designer can act on. Null unless FAILED. */
    val failure: String? = null,
) {
    init {
        require(state != DwAsrModelState.INSTALLED || model != null) {
            "An installed model has to say WHICH model is installed. A status claiming a model is " +
                "present without naming the artifact it was verified against cannot be checked by " +
                "anything, and this app does not decode with a graph it cannot check."
        }
        require(state != DwAsrModelState.INSTALLED || dwAsrModelAllVerified(model, verifiedSha256)) {
            "A model may only be called installed when EVERY file on disk has been hashed IN THIS " +
                "RUN and matched the digest pinned in this APK. If you are here having found the " +
                "files present and wanting to skip the hashing: a graph with a substituted vocabulary " +
                "decodes to confident nonsense rather than to an error, and nobody would ever check " +
                "the output against the audio. Hash them. One of two matching is not most of a yes."
        }
        require(percent == null || percent in 0..100) {
            "Install progress is a percentage or nothing at all. A figure outside 0–100 came from " +
                "arithmetic on a length nobody supplied — pass null instead, and the surfaces say " +
                "“installing” without a bar."
        }
    }
}

/**
 * Whether [verified] accounts for **every** file in [model]. Null model is false.
 *
 * A multiset comparison and not a `containsAll`, for [dwAsrAllVerified]'s reason: a list holding one
 * digest twice would pass `containsAll` while a second file went unhashed — and for this model the
 * unhashed one could be `tokens.txt`, whose substitution produces a fluent transcript in the wrong
 * alphabet rather than an error.
 */
fun dwAsrModelAllVerified(model: DwAsrModel?, verified: List<String>): Boolean {
    if (model == null) return false
    val want = model.files.map { it.sha256.trim().lowercase(Locale.ROOT) }.sorted()
    val got = verified.map { it.trim().lowercase(Locale.ROOT) }.sorted()
    if (want.size != got.size) return false
    // Each pair through the real verifier rather than `==`, so the fail-closed rules in
    // [dwAsrVerify] — a blank expectation never passes, a malformed digest never passes — apply here
    // too and cannot be sidestepped by comparing strings directly.
    return want.zip(got).all { (expected, actual) ->
        dwAsrVerify(expected, actual) == DwAsrVerification.VERIFIED
    }
}

/**
 * **THE ONLY PERMISSION TO DECODE WITH THIS MODEL.** Belt to [DwAsrModelStatus]'s braces.
 *
 * Asked again here rather than trusted from the constructor, for [dwAsrMayLoad]'s reason: the
 * constructor cannot be bypassed today, but it is one `copy()` on a status built for a different model
 * away from being weakened, and the cost of asking twice is a handful of string comparisons against a
 * decode that takes minutes.
 */
fun dwAsrModelMayLoad(status: DwAsrModelStatus): Boolean =
    status.state == DwAsrModelState.INSTALLED &&
        dwAsrModelAllVerified(status.model, status.verifiedSha256)

/** The ids verified on this phone in this run — what `dwLanguageCoverages` means by installed. */
fun dwAsrInstalledModelIds(status: DwAsrModelStatus): Set<String> =
    if (dwAsrModelMayLoad(status)) setOfNotNull(status.model?.modelId) else emptySet()

// ---------------------------------------------------------------------------------------------
// What it costs to have on the phone
// ---------------------------------------------------------------------------------------------

/**
 * Free storage that must remain after the model is installed. **1 GiB, the same as a Tier 2 model's.**
 *
 * [DW_MODEL_FREE_STORAGE_MARGIN_BYTES] rather than the engine's 256 MiB, and the difference is the
 * one that constant already argues: the engine's smaller margin is defensible because 24 MB cannot be
 * the thing that fills a phone. **365 MB can**, and a workshop day of photographs and audio on top of
 * it is exactly what this margin is for. Reusing the constant rather than choosing a third number
 * means there is one place to change when somebody finally measures what a workshop day costs.
 */
fun dwAsrModelStorageNeededBytes(
    model: DwAsrModel,
    /**
     * The container, when the bytes are coming down a wire **as an archive**. Both are on the phone
     * at once while it is unpacked, exactly as [dwAsrStorageNeededBytes] argues for the engine.
     *
     * NULL FOR THE OTHER TWO ROUTES, AND FOR THE SAME REASON IN BOTH: neither stages anything to
     * disk twice. A sideload copies somebody else's bytes out of somebody else's directory, and the
     * deployment endpoint serves the two files already unpacked — so each writes the model once and
     * peak disk is ~365 MB rather than the container route's ~658 MB.
     */
    artifact: DwAsrModelArtifact? = null,
): Long = model.onDiskBytes + (artifact?.downloadBytes ?: 0L) + DW_MODEL_FREE_STORAGE_MARGIN_BYTES

// ---------------------------------------------------------------------------------------------
// The offer — the predicate between a designer and 292 megabytes
// ---------------------------------------------------------------------------------------------

/**
 * What may be offered for the model right now. **Exactly two values draw a control.**
 *
 * The same shape as [DwAsrOffer] and [DwPackOffer], read the same way. Nothing here ever downloads or
 * copies by itself.
 */
enum class DwAsrModelOffer {
    /**
     * **THE BYTES ARE ALREADY ON THIS HANDSET, STAGED BY somebody WITH A CABLE.** Copy and verify.
     *
     * Ranked above [DOWNLOAD] wherever both are possible, and it is not a close call: the staged copy
     * costs nothing, takes seconds rather than an hour, and goes through the identical digest check.
     * A designer whose administrator has already put the model on the phone must never be charged 292
     * MB to fetch the same bytes back.
     */
    INSTALL_FROM_STAGED_FILES,

    /** Fetch it. **The value that spends a designer's data**, and today it is unreachable. */
    DOWNLOAD,

    /**
     * **A PART-FILE IS ON THE PHONE AND THE REST CAN BE ASKED FOR.** Spends data, but less of it.
     *
     * Ranked with [DOWNLOAD] rather than under it because the tap does the same thing to a data
     * bundle — it is the wording and the byte count that differ, and both come from the part-file's
     * own length rather than from this enum.
     */
    RESUME,

    /** The last attempt failed and may be tried again. Draws the control, worded as a retry. */
    RETRY,

    /** It could be fetched, but there is no connection. Say so; draw no button. */
    NO_CONNECTION,

    /** Already on the phone and verified in this run. "Install" would be a lie. */
    ALREADY_INSTALLED,

    /** A copy or a fetch is in flight. Asking again would do one job twice. */
    IN_PROGRESS,

    /** This build pins no model at all. Nothing to install and nothing to say about a size. */
    NOTHING_PINNED,

    /**
     * **A CONTAINER THIS BUILD HAS NO READER FOR.** The pinned container is a `.tar.bz2` and nothing
     * in this APK can open one.
     *
     * IT USED TO SAY "TODAY'S ANSWER ON EVERY HANDSET WITH NO STAGED COPY", AND THAT IS NO LONGER
     * WHAT IT MEANS. There is now a third route in — [DwAsrEndpointState], this deployment's own
     * per-file copy — which has no archive at all, so the bzip2 blocker does not apply to it. This
     * value is the answer only when the deployment has NOT been given the bytes either (or has not
     * been asked yet), which on the fleet today is still every handset, because `ASR_MODEL_DIR` is
     * unset by default on the origin. It stops being the usual answer the day somebody provisions it.
     *
     * Its own value rather than folded into [NOTHING_PINNED], because they are different missing
     * things with different owners and different next moves: nothing-pinned waits on a release builder
     * taking a digest, this waits on the same file being republished in a format the platform can
     * read (or on somebody deciding to carry a bzip2 decoder). A designer reading "nothing is
     * published" about a model whose URL is printed two lines up would rightly conclude the card is
     * confused.
     *
     * **DISABLED WITH THE REASON RATHER THAN ABSENT**, on [DwAsrOffer.NO_MODEL_TO_FEED_IT]'s argument:
     * the question "can this app dictate with no signal at all" is asked BEFORE a field trip, and
     * silence answers it wrongly. No button is drawn, so the failure that rule guards against — a tap
     * that does nothing — does not arise.
     */
    CONTAINER_NOT_READABLE_IN_THIS_BUILD,

    /**
     * **THE DEPLOYMENT WOULD SERVE IT AND THIS ACCOUNT MAY NOT ASK.** HTTP 403.
     *
     * Its own value, and never merged with [SESSION_LAPSED], because the two have different owners
     * and opposite next moves. `can_run_design_workshops` is a SET and not a rank threshold — a
     * PROFESSOR outranks a designer and is still refused — so a designer who reads "sign in again"
     * here signs in again, gets the identical 403, and concludes the app is broken. The card prints
     * the SERVER's own 403 sentence rather than a second phrasing of the rule; see
     * [DwAsrEndpointState.FORBIDDEN] for why two wordings of a counter-intuitive rule will drift.
     */
    NOT_ENTITLED,

    /**
     * **THE SESSION LAPSED.** HTTP 401. Signing in again fixes it, and this app already draws that
     * distinction twice elsewhere — see [DwAsrEndpointState.SESSION_LAPSED].
     */
    SESSION_LAPSED,

    /**
     * **THE DEPLOYMENT DOES NOT KNOW THE MODEL THIS APK PINS.** HTTP 404 on the manifest route.
     *
     * Not [NOTHING_PINNED] (this app pins one) and not
     * [CONTAINER_NOT_READABLE_IN_THIS_BUILD] (nothing was fetched): it is an app/server version
     * skew, and the only move that helps is updating the app. Rolled into "the deployment has not
     * been given the model" it would send a designer to an administrator who will find the bytes
     * present and correct.
     */
    DEPLOYMENT_DOES_NOT_KNOW_THIS_MODEL,

    /** `StatFs` would not answer, so whether it fits is a question. It will not be guessed. */
    STORAGE_UNMEASURED,

    /** It would not fit with room left for a day's photographs. Actionable, and temporary. */
    NOT_ENOUGH_STORAGE,

    /** The app has not looked at its own files yet, or could not. Claims nothing either way. */
    UNKNOWN,
}

/**
 * Whether the model may be offered right now. **Pure, and ordered most-durable-first.**
 *
 * THE ORDER IS [dwAsrOffer]'s and [dwPlanFits]'s, for their reason: the first refusal found is the one
 * rendered, and the useful sentence is the one that says whether anything the designer could do would
 * change the answer.
 *
 * **THE STAGED-FILES CHECK SITS ABOVE THE STORAGE GATE AND THAT IS DELIBERATE.** A staged install
 * still writes 365 MB into `filesDir` and still has to fit — but it does NOT need the container's 292
 * MB free at the same time, so its requirement is smaller and asking the download's question of it
 * would refuse an install that would have fitted. Each route is sized against what it actually writes.
 */
fun dwAsrModelOffer(
    status: DwAsrModelStatus,
    measurement: DwDeviceMeasurement,
    connection: DwConnection,
    /** True when this handset already has the model's files staged where the app can read them. */
    stagedFilesPresent: Boolean,
    catalogue: List<DwAsrModel> = DW_ASR_MODELS,
    artifacts: List<DwAsrModelArtifact> = DW_ASR_MODEL_ARTIFACTS,
    /**
     * What THIS DEPLOYMENT answered when last asked whether it serves the model.
     *
     * Defaulted to [DwAsrEndpointState.UNKNOWN] so every existing call site keeps its behaviour
     * exactly — and because UNKNOWN is the honest value for a caller that has not asked. It falls
     * through to the container branch; it is never read as "not published". See [DwAsrEndpointState].
     */
    endpoint: DwAsrEndpointState = DwAsrEndpointState.UNKNOWN,
): DwAsrModelOffer {
    // 1. Is there anything to install at all? A fact about this BUILD, checked before anything about
    //    the handset, because no reading of a phone can change the answer.
    val model = catalogue.firstOrNull() ?: return DwAsrModelOffer.NOTHING_PINNED

    // 2. What this phone is already doing beats everything below it.
    when (status.state) {
        DwAsrModelState.INSTALLED -> return DwAsrModelOffer.ALREADY_INSTALLED
        DwAsrModelState.INSTALLING -> return DwAsrModelOffer.IN_PROGRESS
        /*
         * A PAUSED FETCH IS OFFERED AS A RESUME **ABOVE THE STORAGE AND CONNECTION GATES BELOW**, and
         * that ordering is deliberate: the bytes already on the phone are the designer's, they cost
         * money to get, and a card that answered "not enough storage" would invite them to cancel —
         * throwing the prefix away — when freeing space and resuming was the cheaper move. The
         * controller checks room again for the REMAINDER before it opens a socket, which is the check
         * that actually protects the volume.
         */
        DwAsrModelState.PAUSED -> return DwAsrModelOffer.RESUME
        // Not "not installed". The app has not looked, and a 292 MB offer made to a designer who
        // already has the model is the wrong claim this state exists to prevent.
        DwAsrModelState.UNKNOWN -> return DwAsrModelOffer.UNKNOWN
        DwAsrModelState.NOT_INSTALLED, DwAsrModelState.FAILED -> Unit
    }

    val free = measurement.freeStorageBytes ?: return DwAsrModelOffer.STORAGE_UNMEASURED

    /*
     * 3. THE STAGED COPY, FIRST, BECAUSE IT IS FREE AND INSTANT. Sized against what it writes — the
     *    unpacked model and nothing else — rather than against a download that is not happening.
     */
    if (stagedFilesPresent) {
        return if (free < dwAsrModelStorageNeededBytes(model)) {
            DwAsrModelOffer.NOT_ENOUGH_STORAGE
        } else {
            DwAsrModelOffer.INSTALL_FROM_STAGED_FILES
        }
    }

    /*
     * 3b. THIS DEPLOYMENT'S OWN PER-FILE COPY, **BELOW THE STAGED BRANCH AND ABOVE THE CONTAINER.**
     *
     * BELOW STAGED, and that ordering is the one a later refactor reverses without noticing because
     * both branches "work": a designer whose administrator already pushed the files over a cable
     * would be offered a 365 MB fetch of bytes sitting on the phone in front of them, which is the
     * exact failure the staged-first comment above was written for.
     *
     * ABOVE THE CONTAINER, because this route has no container: the endpoint serves the two files
     * unpacked, so [DwAsrContainerFormat] and the bzip2 refusal do not apply to it at all.
     *
     * SIZED AGAINST WHAT IT WRITES — [dwAsrModelStorageNeededBytes] with NO artifact — for the same
     * reason the staged branch is: nothing is staged to disk twice on this route, so peak disk is
     * the model alone (~365 MB) rather than the container plus the unpacked files (~658 MB).
     * Charging it the container's question would refuse an install that would have fitted.
     */
    when (endpoint) {
        DwAsrEndpointState.PUBLISHES -> {
            if (free < dwAsrModelStorageNeededBytes(model)) return DwAsrModelOffer.NOT_ENOUGH_STORAGE
            // The connection last, because it is the one that changes while a designer stands there.
            if (connection == DwConnection.NONE) return DwAsrModelOffer.NO_CONNECTION
            return if (status.state == DwAsrModelState.FAILED) {
                DwAsrModelOffer.RETRY
            } else {
                DwAsrModelOffer.DOWNLOAD
            }
        }
        /*
         * EVERY OTHER ANSWER FALLS THROUGH, INCLUDING THE REFUSALS, and that is deliberate. A 401 or
         * a 403 is a fact about THIS route and says nothing about the container route beside it, so
         * refusing here would suppress an install that would have worked the day the model is
         * republished as a `.zip`. What the refusals do instead is REPLACE the container branch's
         * own dead end below, where they are strictly more actionable than "this app cannot open a
         * .tar.bz2" — see [dwAsrModelEndpointRefusal].
         *
         * UNKNOWN falls through for this codebase's standing rule ([DwAsrModelState.UNKNOWN],
         * [DwAsrRuntimeState.UNKNOWN], [DwPackState.UNKNOWN]): the app has not asked yet, and
         * not-yet-looked is never rendered as not-there.
         */
        DwAsrEndpointState.UNKNOWN,
        DwAsrEndpointState.UNREACHABLE,
        DwAsrEndpointState.SESSION_LAPSED,
        DwAsrEndpointState.FORBIDDEN,
        DwAsrEndpointState.VERSION_SKEW,
        DwAsrEndpointState.NOT_PUBLISHED -> Unit
    }

    // 4. Nothing staged and this deployment cannot serve it, so it would have to come from the
    //    pinned container. Is there one, and can this build open it? Both are facts about the build
    //    and neither is changed by anything on the phone.
    val artifact = artifacts.firstOrNull { it.modelId == model.modelId }
        ?: return dwAsrModelEndpointRefusal(endpoint) ?: DwAsrModelOffer.NOTHING_PINNED
    if (!artifact.container.readableInThisBuild) {
        return dwAsrModelEndpointRefusal(endpoint)
            ?: DwAsrModelOffer.CONTAINER_NOT_READABLE_IN_THIS_BUILD
    }

    // 5. Storage for the download route: the container and the unpacked files at once.
    if (free < dwAsrModelStorageNeededBytes(model, artifact)) return DwAsrModelOffer.NOT_ENOUGH_STORAGE

    // 6. The connection, last, because it is the one that changes while a designer stands there.
    if (connection == DwConnection.NONE) return DwAsrModelOffer.NO_CONNECTION

    return if (status.state == DwAsrModelState.FAILED) DwAsrModelOffer.RETRY else DwAsrModelOffer.DOWNLOAD
}

/**
 * The endpoint's own refusal, where it is more use than the container's — or null.
 *
 * **ASKED ONLY WHERE THE CONTAINER ROUTE HAS ALREADY DEAD-ENDED.** Its two call sites in
 * [dwAsrModelOffer] are the `NOTHING_PINNED` and `CONTAINER_NOT_READABLE_IN_THIS_BUILD` returns, and
 * nowhere else. That is what keeps an account problem on ONE route from suppressing a working
 * install on the other: when the container can actually be fetched, this function is never reached.
 *
 * When neither route can serve, "your session lapsed" and "this account is not allowed" are things a
 * designer can act on within the minute, and "published as a .tar.bz2, which this app cannot open"
 * is a sentence about a release builder's decision that they can do nothing whatsoever about.
 *
 * UNREACHABLE and NOT_PUBLISHED return null on purpose: on today's fleet those ARE the ordinary
 * answers (`ASR_MODEL_DIR` is unset), and the container sentence is the honest one for them — it
 * names the cable route, which is what actually works.
 */
private fun dwAsrModelEndpointRefusal(endpoint: DwAsrEndpointState): DwAsrModelOffer? =
    when (endpoint) {
        DwAsrEndpointState.SESSION_LAPSED -> DwAsrModelOffer.SESSION_LAPSED
        DwAsrEndpointState.FORBIDDEN -> DwAsrModelOffer.NOT_ENTITLED
        DwAsrEndpointState.VERSION_SKEW -> DwAsrModelOffer.DEPLOYMENT_DOES_NOT_KNOW_THIS_MODEL
        DwAsrEndpointState.UNKNOWN,
        DwAsrEndpointState.UNREACHABLE,
        DwAsrEndpointState.NOT_PUBLISHED,
        DwAsrEndpointState.PUBLISHES -> null
    }

/**
 * WHERE THE BYTES WOULD COME FROM. Three routes, and the tap has to dispatch on one of them.
 *
 * Derived by [dwAsrModelSourceFor] rather than re-worked out at the call site, because the offer and
 * the tap are two different moments and the controller asks the offer again at the second one. Two
 * independent derivations of "which route is this" is how a card that says "already on this phone"
 * starts a 365 MB download.
 */
enum class DwAsrModelSource {
    /** A cable put the files on this phone. A copy between two directories; no network at all. */
    STAGED_FILES,

    /** This deployment's own `GET /api/asr-models/{id}/files/{name}` — per file, unpacked, authenticated. */
    DEPLOYMENT_ENDPOINT,

    /** [DW_ASR_MODEL_ARTIFACTS]' single container URL. One stream, one archive, one container digest. */
    PINNED_CONTAINER,
}

/**
 * Which route an offer would actually pull from, or null when the offer moves no bytes.
 *
 * ── WHY [DwAsrModelOffer.RESUME] NEEDS AN ARGUMENT THE OTHERS DO NOT ──────────────────────────
 *
 * [dwAsrModelOffer] returns RESUME at step 2, from [DwAsrModelState.PAUSED], **before `endpoint` is
 * ever consulted** — deliberately, because bytes already paid for outrank every gate below. So a
 * resume cannot be attributed to a route by asking what the deployment says today: the part-files on
 * disk were left by whichever route was running yesterday, and the deployment may since have been
 * provisioned or emptied. The controller reads which part-files exist and passes the answer in.
 *
 * A null [pausedSource] on a RESUME means the disk was not asked, or answered nothing recognisable;
 * it falls back to the same rule as a fresh DOWNLOAD, which is the safe direction — the fetch loop
 * checks `Content-Range` against what it asked for and starts fresh if the two disagree, so a
 * mis-attributed resume costs bytes and never a corrupt file.
 */
fun dwAsrModelSourceFor(
    offer: DwAsrModelOffer,
    endpoint: DwAsrEndpointState,
    /**
     * Mirrors [dwAsrModelOffer]'s own argument, so a REFUSAL can still name the route it was refused
     * ON — which is what lets the NOT_ENOUGH_STORAGE sentence quote the right figure. Without it a
     * staged install refused for room would be described with the container's arithmetic.
     */
    stagedFilesPresent: Boolean = false,
    /** Which route left the part-files on this phone, read off the disk. Null when unasked. */
    pausedSource: DwAsrModelSource? = null,
): DwAsrModelSource? {
    val fresh = if (endpoint == DwAsrEndpointState.PUBLISHES) {
        DwAsrModelSource.DEPLOYMENT_ENDPOINT
    } else {
        DwAsrModelSource.PINNED_CONTAINER
    }
    return when (offer) {
        DwAsrModelOffer.INSTALL_FROM_STAGED_FILES -> DwAsrModelSource.STAGED_FILES
        DwAsrModelOffer.RESUME -> pausedSource ?: fresh
        DwAsrModelOffer.DOWNLOAD, DwAsrModelOffer.RETRY -> fresh
        // The route this refusal is ABOUT. [dwAsrModelOffer]'s staged branch is the only one that can
        // answer NOT_ENOUGH_STORAGE with files already on the phone, and it returns before the
        // connection is ever read — so NO_CONNECTION can only ever be about a fetch.
        DwAsrModelOffer.NOT_ENOUGH_STORAGE ->
            if (stagedFilesPresent) DwAsrModelSource.STAGED_FILES else fresh
        DwAsrModelOffer.NO_CONNECTION -> fresh
        else -> null
    }
}

/**
 * Whether a control may be drawn. **The only gate.**
 *
 * [DwAsrModelOffer.INSTALL_FROM_STAGED_FILES] is in it and spends no data at all, which is the one
 * asymmetry with [dwAsrMayInstall]: it is a copy between two directories on the same phone.
 */
fun dwAsrModelMayInstall(offer: DwAsrModelOffer): Boolean =
    offer == DwAsrModelOffer.DOWNLOAD ||
        offer == DwAsrModelOffer.RESUME ||
        offer == DwAsrModelOffer.RETRY ||
        offer == DwAsrModelOffer.INSTALL_FROM_STAGED_FILES

/** Whether this offer spends a designer's data allowance. Decides the wording, not the gate. */
fun dwAsrModelOfferSpendsData(offer: DwAsrModelOffer): Boolean =
    offer == DwAsrModelOffer.DOWNLOAD ||
        offer == DwAsrModelOffer.RESUME ||
        offer == DwAsrModelOffer.RETRY

// ---------------------------------------------------------------------------------------------
// The words. One state cannot read differently in two places, so there is one copy of each
// ---------------------------------------------------------------------------------------------

/** The short state, for the end of a row. The sentence carries the rest. */
fun dwAsrModelStateLabel(state: DwAsrModelState): String = when (state) {
    DwAsrModelState.UNKNOWN -> "Not checked"
    DwAsrModelState.NOT_INSTALLED -> "Not installed"
    DwAsrModelState.INSTALLING -> "Installing"
    DwAsrModelState.INSTALLED -> "Installed"
    DwAsrModelState.PAUSED -> "Paused"
    DwAsrModelState.FAILED -> "Last attempt failed"
}

/** The words on the button, which have to match the sentence above them. Null when none is drawn. */
fun dwAsrModelActionLabel(offer: DwAsrModelOffer): String? = when (offer) {
    DwAsrModelOffer.INSTALL_FROM_STAGED_FILES -> "Install from this phone"
    DwAsrModelOffer.DOWNLOAD -> "Download"
    DwAsrModelOffer.RESUME -> "Resume"
    DwAsrModelOffer.RETRY -> "Try again"
    else -> null
}

/**
 * The sentence for one offer state. **One line each, and a refusal is one plain sentence.**
 *
 * ── WHY THESE ARE A TENTH OF WHAT THEY WERE ───────────────────────────────────────────────────
 *
 * The owner's words, three times: *"I do not need to know it about each and every language in three
 * paragraphs"*, and *"the app works, it does not narrate"*. The old copy on this card ran to ~190
 * words: it explained what a fingerprint is, argued for the cable route in three clauses, and told a
 * designer to stay on the screen. What a person reading this card needs is the state, the cost, and
 * the one thing they can do — the numbers are on the row above and the button is underneath.
 *
 * **WHAT DID NOT GET CUT, BECAUSE CUTTING IT WOULD BE THE WORSE DEFECT**: every arm that costs money
 * still names the money ([DwAsrModelOffer.DOWNLOAD] prints both figures before the tap), and every
 * refusal still says what would change it. A refusal shortened to nothing is a control that appears
 * broken; that is the failure this file's own comments have been guarding against since it was
 * written, and shortness is not a licence to reintroduce it.
 */
fun dwAsrModelOfferSentence(
    offer: DwAsrModelOffer,
    measurement: DwDeviceMeasurement,
    catalogue: List<DwAsrModel> = DW_ASR_MODELS,
    artifacts: List<DwAsrModelArtifact> = DW_ASR_MODEL_ARTIFACTS,
    /**
     * Which route the offer would pull from — [dwAsrModelSourceFor]. Null keeps the container's
     * figures, which is what every caller got before the endpoint route existed.
     *
     * **IT IS HERE BECAUSE THE MONEY IS DIFFERENT ON THE TWO ROUTES AND THE CARD PRINTS THE MONEY.**
     * See the DOWNLOAD arm.
     */
    source: DwAsrModelSource? = null,
): String {
    val model = catalogue.firstOrNull()
    val artifact = model?.let { m -> artifacts.firstOrNull { it.modelId == m.modelId } }
    val fromEndpoint = source == DwAsrModelSource.DEPLOYMENT_ENDPOINT
    return when (offer) {
        // It still names where the bytes came from: a designer told their phone holds a 365 MB model
        // they did not download is entitled to know who put it there.
        DwAsrModelOffer.INSTALL_FROM_STAGED_FILES ->
            "Already on this phone, put here with a cable. Installing costs no data."

        /*
         * THE COST, BEFORE THE TAP — AND THE TWO ROUTES COST DIFFERENT AMOUNTS, IN OPPOSITE
         * DIRECTIONS.
         *
         * THE CONTAINER: 292,571,207 compressed to fetch and 365,438,543 to keep, and both figures
         * are printed because both have to fit AT ONCE while it unpacks — peak disk is about 658 MB.
         *
         * THE ENDPOINT: the same two files, unpacked, so the wire cost is 365,438,543 —
         * **+72,867,336, about 25% MORE than the container** — and the storage cost falls to the
         * same 365,438,543 because nothing is staged to disk twice. Printing the container's smaller
         * number here would understate a designer's prepaid bundle by 73 MB, which is precisely what
         * this app's rule about `dwDownloadCostSentence` ("it is the money") exists to prevent. Note
         * that this is the arm that actually prints the figure: a separate "source sentence" helper
         * beside the button would leave the wrong number on the line a designer reads.
         */
        DwAsrModelOffer.DOWNLOAD ->
            if (fromEndpoint) {
                "${dwBytesLabel(model?.onDiskBytes)} to fetch from this deployment, and the same " +
                    "again is what it takes on the phone. Nothing is fetched until you tap."
            } else {
                "${dwBytesLabel(artifact?.downloadBytes)} to fetch, " +
                    "${dwBytesLabel(model?.onDiskBytes)} to keep. Nothing is fetched until you tap."
            }

        DwAsrModelOffer.RESUME ->
            "Paused. Resuming asks the server only for the part that is missing."

        DwAsrModelOffer.RETRY ->
            "The last attempt did not finish and nothing was kept."

        DwAsrModelOffer.NO_CONNECTION ->
            "No connection, so it cannot be fetched now."

        DwAsrModelOffer.ALREADY_INSTALLED ->
            "On this phone, every file checked against its fingerprint when this screen opened."

        DwAsrModelOffer.IN_PROGRESS ->
            "Installing now."

        DwAsrModelOffer.NOTHING_PINNED ->
            "This app pins no speech model."

        /*
         * THE ANSWER EVERY HANDSET WITHOUT A STAGED COPY GETS TODAY, and it stays two sentences
         * rather than one: without the second, a designer reads "cannot be fetched" beside a card
         * that plainly knows which file it wants and concludes the app is broken. The second names
         * the route that works and whose job the fix is.
         *
         * **IT NO LONGER PRINTS A PATH IN THIS REPOSITORY.** It used to end "docs/ASR-MODEL-SIDELOAD.md",
         * which is a maintainer's filename on a designer's card, and `DwSpeechCardProseTest`'s rule —
         * "no sentence this app shows a designer names a file in this repository" — fails any string
         * matching `\bdocs/[A-Za-z0-9._-]+`. That test happens not to walk this function today, which
         * made this a latent violation rather than a licence. The document path belongs in the KDoc,
         * where the maintainer reads it: docs/ASR-MODEL-SIDELOAD.md is the cable route.
         */
        DwAsrModelOffer.CONTAINER_NOT_READABLE_IN_THIS_BUILD ->
            "Published as a ${artifact?.container?.extension ?: "compressed archive"}, which this " +
                "app cannot open, and this deployment does not have its own copy. Somebody with a " +
                "cable can still put it on this phone — ask whoever set up your account."

        /*
         * THE SERVER'S OWN 403 SENTENCE, QUOTED, and not a second phrasing of the rule. The gate is
         * a SET and not a rank threshold — a PROFESSOR outranks a designer and is still refused — so
         * a card that improvised "you need a higher role" would be telling somebody who already has
         * a higher role that they qualify. `_require_entitlement`'s `detail` is worded around exactly
         * that trap and deliberately does not say "or above".
         */
        DwAsrModelOffer.NOT_ENTITLED ->
            "The offline speech model is part of running a design workshop, so it needs a " +
                "Designer, Admin or Master Admin account. Ask whoever manages accounts here."

        DwAsrModelOffer.SESSION_LAPSED ->
            "This phone is signed out of the deployment, so it could not ask for the model. Sign " +
                "in again and tap “Check again”."

        // NOT "the deployment has no model": it has one, and it is not the one this app knows how to
        // ask for. Sending this designer to an administrator wastes both their time — the bytes will
        // be present and correct — so the sentence names the move that helps.
        DwAsrModelOffer.DEPLOYMENT_DOES_NOT_KNOW_THIS_MODEL ->
            "This deployment does not recognise the speech model this version of the app asks for. " +
                "Updating the app is what fixes it; a cable still works in the meantime."

        DwAsrModelOffer.STORAGE_UNMEASURED ->
            "This phone would not say how much storage is free. Tap “Check again”."

        /*
         * THE FIGURE IS THE ONE THE GATE ACTUALLY USED, and until the endpoint route existed there
         * was only one number to print so nothing said which. There are two now, and they differ by
         * 292 MB: [dwAsrModelStorageNeededBytes] adds the container's own length on the container
         * route, because the archive and the unpacked files are on the phone AT ONCE while it
         * unpacks — peak disk ~658 MB — and adds nothing on the staged and endpoint routes, which
         * write the model once. A refusal quoting the smaller figure while the gate used the larger
         * one tells a designer to free 365 MB and then refuses them again.
         */
        DwAsrModelOffer.NOT_ENOUGH_STORAGE -> {
            val staging = if (source == DwAsrModelSource.PINNED_CONTAINER) {
                artifact?.downloadBytes ?: 0L
            } else {
                0L
            }
            "Not enough room: needs ${dwBytesLabel((model?.onDiskBytes ?: 0L) + staging)} plus " +
                "${dwBytesLabel(DW_MODEL_FREE_STORAGE_MARGIN_BYTES)} kept back, and this phone has " +
                "${dwBytesLabel(measurement.freeStorageBytes)} free."
        }

        DwAsrModelOffer.UNKNOWN ->
            "Not looked at yet. Tap “Check again”."
    }
}

/** Said when a file does not match its pinned digest. It is deleted, and this is why. */
const val DW_ASR_MODEL_MISMATCH_SENTENCE: String =
    "One of the speech model's files did not match the fingerprint built into this app, so it has " +
        "been deleted and nothing was installed. A model whose vocabulary file has been swapped does " +
        "not fail — it writes fluent nonsense in the wrong alphabet, which nobody would catch by " +
        "reading the field — so this app refuses it rather than trying to work around it. If it was " +
        "downloaded, the transfer probably broke; try again. If it happens twice, or if it was put " +
        "on the phone with a cable, tell whoever supplied the file: it is not the file this app pins."

/**
 * **WHAT THIS MODEL WOULD ADD TO *THIS* PHONE — WHICH IS NOT THE SAME AS WHAT IT SERVES.**
 *
 * ── THE DEFECT THIS SIGNATURE EXISTS TO FIX, MEASURED ON THE FLEET'S OWN HANDSET ──────────────
 *
 * This function used to print, verbatim: *"With this model on the phone, dictation works with no
 * signal at all in Hindi"*. On the SM-M325F that sentence is **true and useless**. The device's own
 * `checkRecognitionSupport` answer (2026-08-12) lists `hi-IN` as INSTALLED — Google's pack is already
 * there — and [DW_TIER1_CATALOGUE]'s single model serves `hi-IN` **and nothing else of ours**. So the
 * card offered 365 MB of storage and a 292 MB fetch in exchange for a capability the phone already
 * had, and `dwLanguageCoverages` returned the identical two green rows whether the model was
 * installed or absent.
 *
 * The arithmetic to say so already existed one file over — [dwOfflineCoverage] composes the two
 * sources precisely so neither is double-counted — and this sentence simply was not asking it.
 * **It asks now: what it serves, MINUS what this handset already covers without it.**
 *
 * The accuracy and speed clauses are gone with the paragraph they were appended to: they are
 * provenance about a measurement, not state a designer acts on, and the model row on this same card
 * already carries size, memory and the verdict as numbers.
 *
 * @param support the platform's own answer, or null when this Android version cannot be asked. Null
 *   is NOT read as "the platform has nothing" — that would restore the overclaim in its other
 *   direction, promising an addition that may already be present. It says what it serves and states
 *   that the subtraction could not be made, which is the honest-unknown rule this feature is built on.
 */
fun dwAsrModelWhatItBuysSentence(
    labels: Map<String, String>,
    support: DwRecognitionSupport?,
    plans: List<DwModelPlan> = DW_TIER1_CATALOGUE,
): String {
    val plan = plans.firstOrNull() ?: return "No speech model is measured in this build."
    val served = labels.keys.filter { plan.servesLanguage(it) }
    if (served.isEmpty()) {
        return "Serves none of this app's dictation languages, so it would add nothing here."
    }
    val servedLabels = served.map { labels[it] ?: it }
    if (support == null) {
        return "Serves ${servedLabels.joinToString(", ")}. This Android version cannot be asked " +
            "which packs it already has, so how much of that is new here is unknown."
    }
    val adds = served.filter { dwPackState(it, support) != DwPackState.INSTALLED }
    if (adds.isEmpty()) {
        /*
         * THE FLEET HANDSET'S OWN ANSWER, AND THE WHOLE POINT OF THE SUBTRACTION. It names what
         * covers them instead, because "adds nothing" with no reason reads as a fault rather than as
         * a phone that is already equipped.
         *
         * ── AND IT AGREES WITH ITS OWN SUBJECT, WHICH IT DID NOT UNTIL 2026-08-13 ─────────────
         *
         * This arm read "…already work offline through Android's own pack" with the verb and the noun
         * both fixed plural. **One language is the only case that can currently reach it**:
         * [DW_TIER1_CATALOGUE] holds a single plan and it serves `hi-IN` alone, so the sentence on
         * the Speech & AI card of every handset in the fleet read *"Hindi already work offline
         * through Android's own pack."* Grammar in the one branch that is always taken is not a
         * detail — it is the sentence a designer judges the whole screen by.
         */
        // "ALREADY" IS KEPT AND IS NOT PADDING: it is the word that turns "adds nothing" from a fault
        // into a phone that is equipped, which is this arm's entire job. Dropping it was the first
        // attempt at this rewrite, and the tests above refused it.
        return "Adds nothing on this phone: ${servedLabels.joinToString(", ")} already " +
            "${dwPluralVerb(servedLabels.size, "works", "work")} offline through Android's own " +
            "${dwPluralNoun(servedLabels.size, "pack", "packs")}."
    }
    return "Adds offline dictation in ${adds.map { labels[it] ?: it }.joinToString(", ")}."
}

/**
 * Subject–verb agreement for a list whose length is a MEASUREMENT rather than a constant.
 *
 * ── WHY THIS IS A FUNCTION AND NOT AN INLINE `if` ─────────────────────────────────────────────
 *
 * Because the inline version is what shipped the defect. Every sentence in this feature is composed
 * from a list whose size depends on what a particular handset answered, and a writer looking at the
 * fleet's own reading — where the list has one entry — writes the singular, while a writer thinking
 * about the catalogue writes the plural. Both are right about one case and wrong about the other, and
 * neither is visible without the device in the room. Naming the choice makes it a thing a test can
 * ask about, which is the only way this class of defect gets caught on a desktop.
 *
 * Zero takes the PLURAL ("0 packs"), which is English rather than an oversight — and in any case no
 * caller here reaches it, since an empty list is answered by its own sentence before this is asked.
 */
internal fun dwPluralVerb(count: Int, singular: String, plural: String): String =
    if (count == 1) singular else plural

/** The noun half of the same agreement. Separate so a caller can inflect one without the other. */
internal fun dwPluralNoun(count: Int, singular: String, plural: String): String =
    if (count == 1) singular else plural
