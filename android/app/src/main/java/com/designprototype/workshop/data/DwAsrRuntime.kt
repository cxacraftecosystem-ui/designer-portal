package com.designprototype.workshop.data

import java.util.Locale

/**
 * THE OFFLINE SPEECH ENGINE AS SOMETHING A DESIGNER CHOOSES TO INSTALL — NOT SOMETHING THE APK
 * CARRIES, AND NOT SOMETHING THIS APP DECIDES FOR THEM.
 *
 * ── WHY IT IS AN OFFER AND NOT A BUILD DEPENDENCY. THE MEASUREMENT DECIDED IT ──────────────────
 *
 * docs/ASR-RUNTIME-MEASUREMENT.md weighed sherpa-onnx off eight real packaged APKs. Two numbers
 * settle the shape of this file and neither is re-derived here:
 *
 *  - **The ARM-pair APK goes 26,244,416 → 79,552,612 bytes — +53,308,196, 3.03×** with the default
 *    AAR, and +39,811,828 (2.52×) with the static-linked one, which is the cheaper honest shape
 *    (that document's recommendation 2). Per ABI, static-linked: **arm64-v8a +23,646,824**,
 *    **armeabi-v7a +16,152,132**. This app's own updater fetches the WHOLE APK on every release
 *    behind a dialog with no "Later" button (`MainActivity.kt`, *"required update: cannot be
 *    dismissed"*), so bundling it would triple a compulsory download for the whole fleet.
 *  - **The dependency is not on Maven Central at all** — six coordinates, six 404s, proved through
 *    the Gradle resolver against the repositories this build actually declares. Upstream ships the
 *    Android build as a GitHub release asset. **So a build-time dependency was never on the table**,
 *    whatever anybody decided about the size.
 *
 * And the reason it is the DESIGNER's choice rather than a global one is the fleet: these are
 * designers empanelled by the government, and **only some of them go to the field, and only
 * sometimes.** A workshop run at the office over Wi-Fi never needs an offline engine; a courtyard in
 * a district village with no bars needs nothing else. Charging the whole fleet 24 MB and a permanent
 * 24 MB of storage for a capability most of them will never open is the wrong default in both
 * directions, so nobody is charged it and everybody is offered it — once at first run, and
 * permanently in Settings.
 *
 * ── THE ONE THING THAT MAKES THIS DIFFERENT FROM A LANGUAGE PACK: THIS IS EXECUTABLE CODE ──────
 *
 * `DwLanguagePacks.kt` is the precedent for the SHAPE of this feature and it is followed closely,
 * but there is one difference and it is not cosmetic. A Google language pack is DATA, fetched by the
 * platform's own speech service into the platform's own storage; this app never sees the bytes. A
 * native `.so` reached with `System.load` is **CODE running inside this app's process** — the process
 * holding Aadhaar numbers, designer credentials, and up to two weeks of unsynced fieldwork. **A
 * download path with no integrity check is a remote-code-execution vector in an application that
 * handles national identity numbers.**
 *
 * So the rules below are not defensive habits, they are the price of having this feature at all:
 *
 *  1. **The expected SHA-256 of every artifact is pinned IN THE APK**, as [DwAsrArtifact.sha256], and
 *     the downloaded bytes are checked against it BEFORE anything is loaded. A mismatch deletes the
 *     file and says so in a sentence.
 *  2. **The digest is taken of the file ON DISK after writing**, never accumulated from the stream
 *     while writing. See [DwAsrVerification] for why the two are different facts.
 *  3. **TLS to the deployment's own storage**, enforced by [DwAsrArtifact]'s constructor, which will
 *     not hold a `http://` URL at all.
 *  4. **Loaded from internal storage (`filesDir`) only** — never external, never `cacheDir`. That
 *     rule is enforced in the Android half (`ui/designworkshop/DwAsrRuntimeUi.kt`), because it is
 *     about a path this file cannot see; the reason is that `cacheDir` is reclaimed by the platform
 *     without warning and a half-reclaimed native library is a crash on load rather than a miss.
 *  5. **Nothing is loaded that was not verified in this same run**, which [DwAsrRuntimeStatus]
 *     enforces by construction: the INSTALLED state cannot be built without a digest for **every**
 *     library, taken off disk in that run and matched. Half a verification is not most of a yes — the
 *     library nobody looked at is the one that would be loaded.
 *
 * **WHAT THIS DESIGN DOES NOT ESTABLISH, SAID PLAINLY.** A pinned digest binds the bytes to what the
 * RELEASE BUILDER of this APK intended, and to nothing more. **It is not a signature and it says
 * nothing about upstream provenance** — it cannot tell you the file is really k2-fsa's build of
 * sherpa-onnx, only that it is the same file whoever cut this release pinned. Between here and
 * upstream sit whatever the release builder downloaded and whatever the deployment's storage serves.
 * docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md is where that chain is written down, and the honest summary
 * is that trusting this engine means trusting this app's release process.
 *
 * ── WHY THE DECISION IS PURE ──────────────────────────────────────────────────────────────────
 *
 * Everything here is plain Kotlin over plain numbers and strings: no Context, no okhttp, no Compose,
 * no `java.io`. That is what lets the desktop JVM test it (`DwAsrRuntimeTest`), and the predicate
 * that stands between a designer and 24 MB of their prepaid data allowance has to be as testable as
 * `dwPackOffer`. The platform half — one HTTP GET, one digest of a file, two SharedPreferences flags
 * — is `ui/designworkshop/DwAsrRuntimeUi.kt`, kept as small as the split allows for the reason this
 * repository keeps rediscovering: the untestable half is the half that is wrong.
 *
 * ── AND TODAY THE OFFER IS DISABLED, ON EVERY HANDSET, ON PURPOSE ──────────────────────────────
 *
 * [DW_ASR_ARTIFACTS] is EMPTY and there is no measured ASR model in this system at all
 * (docs/DEVICE-TIER-MEASUREMENT.md, plan step 5, which needs a handset). So there is nothing to
 * fetch and, if there were, nothing to feed it: **the engine arrives without a voice.** A designer
 * who spends 24 MB and finds dictation unchanged has been misled by this app, so the control is
 * DISABLED WITH THE REASON ON IT rather than absent — see [DwAsrOffer.NO_MODEL_TO_FEED_IT], where
 * that call is argued in full.
 */

// ---------------------------------------------------------------------------------------------
// What is on this handset right now
// ---------------------------------------------------------------------------------------------

/**
 * The state of this app's own speech engine ON THIS PHONE. Five values, and the fifth is honesty.
 *
 * Deliberately about the HANDSET and not about the build: whether an engine is in the APK is a
 * compile-time constant ([DW_TIER1_RUNTIME_PRESENT], permanently `false` now that this is an opt-in
 * install), whereas everything below can differ between two designers running the same release.
 */
enum class DwAsrRuntimeState {
    /**
     * THE APP HAS NOT LOOKED YET, OR COULD NOT LOOK. Never rendered as "not installed".
     *
     * Reachable in two ways, and both are real: before the first read of the app's own files lands
     * (the card draws a frame before that), and when that read throws — a `SecurityException` on a
     * hardened OEM build, an I/O error on a failing flash chip. Guessing "not installed" here would
     * offer a 24 MB download to a designer who already has the engine; guessing "installed" would
     * promise offline dictation that cannot happen. The same rule [DwPackState.UNKNOWN] follows.
     */
    UNKNOWN,

    /** Looked, and it is not there. The state every handset in the fleet is in today. */
    NOT_INSTALLED,

    /**
     * A fetch this app started is in flight, in this process, right now.
     *
     * NOT PERSISTED ANYWHERE, and that is deliberate: a "downloading" flag that outlived the process
     * would come back as a permanent spinner on a phone whose download died with a force-stop, and a
     * spinner that cannot resolve is the control this repository's own rules call worse than absent.
     * A killed download leaves a part-file, which the next disk read treats as [NOT_INSTALLED].
     */
    DOWNLOADING,

    /**
     * On the phone, in internal storage, **and verified against the pinned digest in this same run.**
     *
     * The second half is not a footnote. [DwAsrRuntimeStatus] cannot be constructed in this state
     * without the digest that was checked to reach it, so there is no path through this app in which
     * something unverified is called installed.
     */
    INSTALLED,

    /**
     * The last attempt failed: the download broke, or the bytes did not match the pinned digest.
     *
     * A separate value from [NOT_INSTALLED] because the SENTENCE differs and so does the control — a
     * failed attempt fetched nothing to pay for twice, so it may be retried, whereas a mismatch is
     * worth saying out loud rather than quietly retrying for ever.
     */
    FAILED,
}

// ---------------------------------------------------------------------------------------------
// One artifact, pinned — the whole trust boundary of this feature
// ---------------------------------------------------------------------------------------------

/**
 * ONE NATIVE LIBRARY INSIDE AN ARTIFACT, WITH ITS OWN PINNED DIGEST AND ITS OWN SIZE.
 *
 * ── WHY EVERY LIBRARY IS PINNED SEPARATELY, AND NOT ONLY THE CONTAINER ────────────────────────
 *
 * The container's digest can only be checked while the container exists — which is during the
 * download and no longer. Once it is unpacked and deleted, the thing this app would actually load is a
 * `.so` file on disk, and **"nothing is loaded that was not verified in this same run" has to hold on
 * every run, not only on the run that installed it.** A digest of a container that is gone verifies
 * nothing on the fourth launch.
 *
 * The alternative — keeping the container for ever so it can be re-verified — costs the download's
 * bytes permanently on top of the install's, and still would not answer the question that matters,
 * because it is the extracted file that gets loaded and not the archive.
 *
 * So each library is pinned, each is hashed on disk before it may be used, and the container's digest
 * is an additional check taken once, at the moment the bytes arrive from the network.
 *
 * [fileName] IS A BARE NAME AND IS CHECKED TO BE ONE. It addresses a file inside this app's own
 * internal storage; an entry like `../databases/workshop.db` would put fetched bytes over this app's
 * database. It is also the ONLY name used when unpacking — the names inside the archive are never
 * read, so a malicious archive cannot choose a path.
 */
data class DwAsrLibrary(
    /** e.g. `libsherpa-onnx-jni.so`. A file name, never a path. */
    val fileName: String,
    /** Lower-case hex SHA-256 of this exact `.so`, as the release builder measured it. */
    val sha256: String,
    /** Its size on disk, for the storage arithmetic and for the sentence. */
    val bytes: Long,
) {
    init {
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName && ".." !in fileName) {
            "A library name must be a bare file name: “$fileName” is not. These names address files " +
                "written into this app's own internal storage, and an entry like " +
                "../databases/workshop.db would put fetched bytes over this app's own database."
        }
        require(fileName.startsWith("lib") && fileName.endsWith(".so")) {
            "A native library is called lib….so on Android; “$fileName” is not, and `System.load` " +
                "will not open it. If the deployment is serving something else, this catalogue is " +
                "describing the wrong artifact."
        }
        require(dwAsrIsSha256(sha256)) {
            "Every library needs the full 64-character hex SHA-256 of the exact file that will sit " +
                "on the phone — not of the archive it arrives in. The archive is deleted after " +
                "unpacking, and what gets loaded afterwards is this file, so this is the digest that " +
                "has to be checkable on every launch."
        }
        require(bytes > 0L) {
            "Every library needs its real size on disk. The figures are added up to decide whether " +
                "the engine fits with a workshop day's photographs left over, and a zero would let " +
                "an install be offered on a phone with no room for it."
        }
    }
}

/**
 * ONE PUBLISHED ENGINE ARTIFACT FOR ONE ABI, WITH THE DIGEST THAT PINS IT. **The trust boundary.**
 *
 * Every field is required and the constructor refuses anything it cannot check, for the same reason
 * [DwModelPlan] refuses to hold a model without the cap it was measured at: the sentence "fetch the
 * engine from here" must not be expressible without also saying what the bytes have to hash to.
 *
 * THE DIGEST IS A CONSTANT IN THE APK. It arrives as source, is compiled into the release, and is
 * signed along with everything else by the APK signature — so replacing it means replacing the app.
 * Nothing about it is fetched, configured, or read from a preference, because a digest fetched from
 * the same place as the file it verifies verifies nothing at all.
 */
data class DwAsrArtifact(
    /** Exactly one `Build.SUPPORTED_ABIS` value, e.g. `arm64-v8a`. Never a list, never a wildcard. */
    val abi: String,

    /**
     * Absolute `https://` URL on the DEPLOYMENT'S OWN storage — the same CloudFront distribution the
     * app already fetches its own APK from.
     *
     * NOT a GitHub release asset, and not any other third-party host. Two reasons, both concrete: a
     * third-party host is a party that can change the bytes at that URL without this deployment
     * knowing, and it is one this app cannot reach at all on the IPv6-only mobile networks the
     * CloudFront default in `build.gradle.kts` exists for.
     */
    val url: String,

    /**
     * Lower-case hex SHA-256 of the file at [url], **as the release builder measured it**.
     *
     * 64 characters. Not truncated, not a CRC, not the size: a CRC-32 is a transmission check that
     * anybody can forge in seconds, and this value is the only thing standing between the app's
     * process and somebody else's code.
     *
     * This one pins the CONTAINER, checked once when the bytes arrive. What is loaded afterwards is
     * checked against [DwAsrLibrary.sha256] on every run — see that class for why one digest is not
     * enough.
     */
    val sha256: String,

    /** What the fetch costs a data bundle: the size of the file served at [url], in bytes. */
    val downloadBytes: Long,

    /**
     * The `.so` files this artifact carries, each with its own digest, **in the order they load**.
     *
     * Order is load order and not decoration: with the default (non-static) upstream AAR,
     * `libonnxruntime.so` has to be resident before `libsherpa-onnx-jni.so` can resolve against it,
     * and getting that backwards is an `UnsatisfiedLinkError` at the first dictation rather than at
     * install time.
     */
    val libraries: List<DwAsrLibrary>,

    /** The upstream release these libraries came out of, e.g. `sherpa-onnx v1.13.5`. Audit trail. */
    val upstreamVersion: String,

    /**
     * How the release builder obtained them, in a sentence, e.g. "extracted from
     * sherpa-onnx-static-link-onnxruntime-1.13.5.aar, downloaded from the k2-fsa GitHub release".
     *
     * Required, because the digest establishes only that the file is the one the release builder
     * pinned — this field is the only place the app records WHAT they believed they were pinning.
     */
    val provenance: String,
) {
    /**
     * What the engine costs the phone permanently: the libraries' own bytes, added up.
     *
     * DERIVED RATHER THAN DECLARED, so it cannot disagree with the list it describes. An earlier draft
     * of this class carried it as a constructor parameter beside [downloadBytes], which meant a
     * release builder could pin four libraries totalling 34 MB and write 24 MB in the size field, and
     * nothing anywhere would notice — the storage gate would pass on a phone that the install then
     * filled. It is a different number from [downloadBytes] whenever the deployment serves a
     * compressed container, which docs/ASR-RUNTIME-MEASUREMENT.md §5 measured on the packaged APK
     * (57,662,788 stored bytes of libraries compressed to 24,581,961), and both have to be free at
     * once while the download is unpacked — see [dwAsrStorageNeededBytes].
     */
    val installedBytes: Long get() = libraries.sumOf { it.bytes }

    init {
        // Sentences naming the next move, because every one of these fires at whoever is adding the
        // first real row to the catalogue, and a code would send them to a search engine.
        /*
         * CHECKED THE SAME WAY [DwAsrLibrary.fileName] IS, AND FOR THE SAME REASON. This value is not
         * a label: the Android half builds `filesDir/asr-engine/<abi>/` from it and names the
         * downloaded container `engine-<abi>.zip`, so an `abi` of `../databases` would put fetched
         * bytes beside this app's own database. That it is a constant compiled into the APK rather
         * than a string off the wire makes it a release builder's typo rather than an attack — which
         * is exactly what the check on the library names is for too, and there is no argument for
         * guarding one of the two path fragments and trusting the other.
         */
        require(abi.isNotBlank() && '/' !in abi && '\\' !in abi && ".." !in abi) {
            "An engine artifact needs the exact ABI it was built for as a bare name, e.g. arm64-v8a — " +
                "“$abi” is not one. Read it off the directory the libraries came out of; one artifact " +
                "per ABI, never a list. It also names a directory inside this app's internal storage " +
                "and the file the container is downloaded as, so a separator or “..” in it would write " +
                "fetched bytes outside the one directory this feature owns."
        }
        require(url.startsWith("https://")) {
            "An engine artifact must be served over TLS from this deployment's own storage. This is " +
                "executable code that will run inside the process holding Aadhaar numbers and two " +
                "weeks of unsynced fieldwork — publish the file to the deployment's storage and use " +
                "its https URL. Do not relax this check to make a test pass."
        }
        require(dwAsrIsSha256(sha256)) {
            "An engine artifact needs the full 64-character lower-case hex SHA-256 of the exact file " +
                "being served, taken by the release builder. Compute it with `sha256sum` on the file " +
                "you uploaded, not on the one you built from. Nothing is loaded without it."
        }
        require(downloadBytes > 0L) {
            "An engine artifact needs the real size of the file at its URL. This screen states the " +
                "cost before the tap — unlike the language packs, where Android reports no size — " +
                "and a zero would print as “0 MB” beside somebody's prepaid data bundle."
        }
        require(libraries.isNotEmpty()) {
            "An engine artifact needs the .so files it carries, in the order they must be loaded — " +
                "libonnxruntime.so before libsherpa-onnx-jni.so with the default AAR, or the second " +
                "cannot resolve against the first. Each one carries its own digest, because the " +
                "archive is deleted after unpacking and these are the files that get loaded."
        }
        require(libraries.map { it.fileName }.distinct().size == libraries.size) {
            "Two libraries in one artifact cannot share a file name: they would be written to the " +
                "same path, one would silently replace the other, and the digest check would then " +
                "fail on whichever lost — reporting a substituted file where the real fault is a " +
                "duplicated row in this catalogue."
        }
        require(upstreamVersion.isNotBlank()) {
            "An engine artifact needs the upstream release it came out of, so a defect found later " +
                "can be traced to a version rather than to “the engine”."
        }
        require(provenance.isNotBlank()) {
            "An engine artifact needs a sentence saying how the release builder obtained it. The " +
                "digest proves only that the file is the one they pinned; this is the only record " +
                "of what they believed they were pinning."
        }
    }
}

/**
 * THE ENGINE ARTIFACTS THIS APP WOULD INSTALL. **IT IS EMPTY, AND THE EMPTINESS IS TODAY'S ANSWER.**
 *
 * Nothing has been published to this deployment's storage and no digest has therefore been taken, so
 * there is no row that could be written here without inventing one. Exactly the shape
 * [DW_TIER1_CATALOGUE] is in, for exactly the same discipline: the arithmetic is built and tested
 * against an openly invented fixture, and the real list is empty until somebody measures something.
 *
 * **THREE THINGS MUST HAPPEN BEFORE A ROW BELONGS HERE**, and they are in
 * docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md in full:
 *
 *  1. The deployment serves the per-ABI engine files over TLS at a stable URL (the server side of
 *     this lane, which is a backend change this lane may not make).
 *  2. The release builder takes the SHA-256 of each served file and pins it here.
 *  3. A Tier 1 model has been measured, so the engine has something to say — see
 *     [DwAsrOffer.NO_MODEL_TO_FEED_IT]. Until then the offer is disabled even if 1 and 2 are done,
 *     because an engine with no voice changes nothing a designer can see.
 *
 * WHILE THIS IS EMPTY, R8 CAN PROVE MOST OF THE INSTALL PATH UNREACHABLE and may delete it — which
 * is the same phenomenon docs/ASR-RUNTIME-MEASUREMENT.md §4 recorded when R8 removed all 123
 * `com.k2fsa.sherpa.onnx` classes whole. **That has NOT been measured on this code**; the technique
 * for checking is that document's own (read `r8-removed.txt`, a line with no trailing colon is a
 * class removed entirely), and it costs nothing to run when the first row lands.
 */
val DW_ASR_ARTIFACTS: List<DwAsrArtifact> = emptyList()

/**
 * Which artifact this handset needs, in the platform's OWN preference order — or null if none fits.
 *
 * [abis] is `Build.SUPPORTED_ABIS`, which is ordered primary-first: a 64-bit handset that can also
 * run 32-bit code lists `arm64-v8a` before `armeabi-v7a` and must be given the 64-bit engine. THE
 * LOOP IS OVER THE HANDSET'S LIST AND NOT OVER THE CATALOGUE, which is the whole care in this
 * function: `catalogue.firstOrNull { it.abi in abis }` would compile, read almost the same, and let
 * the order rows happen to be written in decide what a phone downloads — handing 16 MB of 32-bit
 * engine to a 64-bit phone because the `armeabi-v7a` row was typed first.
 *
 * An empty [abis] returns null, and the caller must NOT read that as "no build for this processor":
 * see [DwAsrOffer.PROCESSOR_UNMEASURED], which is a separate answer for a separate fact.
 */
fun dwAsrArtifactFor(
    abis: List<String>,
    catalogue: List<DwAsrArtifact> = DW_ASR_ARTIFACTS,
): DwAsrArtifact? {
    abis.forEach { reported ->
        val match = catalogue.firstOrNull { it.abi.equals(reported.trim(), ignoreCase = true) }
        if (match != null) return match
    }
    return null
}

// ---------------------------------------------------------------------------------------------
// The digest check — the gate everything else in this file exists to protect
// ---------------------------------------------------------------------------------------------

/** 64 hex characters, in either case. Anything else is not a SHA-256 and is not treated as one. */
fun dwAsrIsSha256(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

/**
 * The outcome of comparing a pinned digest with one taken off the file on disk.
 *
 * **THE DIGEST MUST BE TAKEN OF THE FILE ON DISK, AFTER WRITING — NOT ACCUMULATED FROM THE STREAM
 * WHILE WRITING**, and the difference is not pedantry. A streaming digest proves what was RECEIVED;
 * this app needs to know what was STORED, and the two differ in cases that are ordinary rather than
 * exotic:
 *
 *  - the write was short — the volume filled, the process was killed, `close()` failed and its
 *    exception was swallowed — so the file on disk is a prefix of a stream that hashed correctly;
 *  - the file was replaced between the download finishing and the load happening, which on a rooted
 *    or debuggable handset is a thing somebody can do deliberately;
 *  - the bytes never reached the platter at all and a later reader gets whatever the page cache was
 *    dropped in favour of.
 *
 * Loading is done from the FILE, so the FILE is what has to be verified. There is exactly one
 * value in this enum that permits a load.
 */
enum class DwAsrVerification {
    /** The file on disk hashes to the digest pinned in the APK. **The only value that may load.** */
    VERIFIED,

    /**
     * Both are digests and they differ. **Delete the file and say so** — do not retry silently.
     *
     * This is either a corrupted transfer or a substituted file, and this app cannot tell which. It
     * is worth a sentence in either case: a designer whose engine quietly re-downloads three times a
     * day is a designer whose data bundle is being spent on a defect nobody has noticed.
     */
    MISMATCH,

    /**
     * THIS BUILD PINS NO DIGEST FOR THIS ARTIFACT, so nothing may be loaded. **Fail closed.**
     *
     * Unreachable through [DwAsrArtifact], whose constructor will not hold a blank digest — which is
     * the point of putting the check there. It exists so that the verifier itself cannot be talked
     * into passing by an empty expectation: `expected == actual` with both empty is `true`, and that
     * one line of plausible code would turn the whole of this file into decoration.
     */
    NO_PINNED_DIGEST,

    /**
     * One of the two is not a SHA-256 at all — a truncated hex string, a CRC, an error page's text.
     *
     * Separate from [MISMATCH] because it means something different happened: a mismatch is a file
     * that is not the right file, this is a comparison that was never a comparison.
     */
    MALFORMED,
}

/**
 * Compare a pinned digest with one taken off the file on disk. **The only permission to load.**
 *
 * Case-insensitive, because `sha256sum`, `certutil` and `MessageDigest` are not consistent about it
 * and a designer's engine must not be rejected over the case of a hex string. Whitespace is trimmed
 * for the same reason: a digest pasted out of a build log arrives with a newline on it.
 *
 * NOT constant-time, and it does not need to be: both values are public, neither is a secret, and
 * there is nothing here for a timing side channel to leak. Said explicitly because the next reader
 * will wonder, and because a `MessageDigest.isEqual` here would imply a threat model this is not.
 */
fun dwAsrVerify(expected: String, actual: String): DwAsrVerification {
    val want = expected.trim()
    val got = actual.trim()
    if (want.isEmpty()) return DwAsrVerification.NO_PINNED_DIGEST
    if (!dwAsrIsSha256(want) || !dwAsrIsSha256(got)) return DwAsrVerification.MALFORMED
    return if (want.equals(got, ignoreCase = true)) {
        DwAsrVerification.VERIFIED
    } else {
        DwAsrVerification.MISMATCH
    }
}

// ---------------------------------------------------------------------------------------------
// The status — one reading of this handset's engine, carrying what was verified
// ---------------------------------------------------------------------------------------------

/**
 * What this app's engine is doing on this phone, at one moment, **with the evidence attached**.
 *
 * The invariant in `init` is the mechanism behind rule 5 at the top of this file: an INSTALLED status
 * cannot exist without the artifact it is about and the digest that was checked to reach it. There is
 * therefore no way to write "installed" from a code path that only looked at whether a file exists —
 * which is precisely the shortcut a future reader will reach for when adding a fast start-up check,
 * and it is the shortcut that would let unverified code run.
 */
data class DwAsrRuntimeStatus(
    val state: DwAsrRuntimeState = DwAsrRuntimeState.UNKNOWN,

    /**
     * The artifact this state is about, or null when this build pins none for this handset.
     *
     * Null with state [DwAsrRuntimeState.NOT_INSTALLED] is today's answer on every phone: nothing is
     * published, so there is nothing this state could be about.
     */
    val artifact: DwAsrArtifact? = null,

    /**
     * The digests actually taken off the library files on disk **in THIS RUN of the app.**
     *
     * "In this run" is load-bearing, and it is why this is a reading rather than a remembered flag. A
     * digest recorded at install time and trusted afterwards would be a claim about a file as it was
     * before the phone was rebooted, before the app was updated, and before anybody with a cable and
     * a debuggable build rewrote it. Re-verifying costs one pass over ~24 MB when the engine is first
     * needed; the alternative is loading native code on the strength of a note the app wrote to
     * itself.
     *
     * Compared as a multiset against [DwAsrArtifact.libraries] — see [dwAsrAllVerified]. Order is not
     * significant here: the artifact's order is LOAD order, and the order in which files happened to
     * be hashed says nothing about anything.
     */
    val verifiedSha256: List<String> = emptyList(),

    /** 0–100 while a download is in flight AND the server gave a length; null when it did not. */
    val percent: Int? = null,

    /**
     * Why the last attempt failed, as a sentence a designer can act on. Null unless FAILED.
     *
     * Kept out of the enum because the same [DwAsrRuntimeState.FAILED] covers a broken connection, a
     * refused request and a digest mismatch, and those three send a designer to three different next
     * moves.
     */
    val failure: String? = null,
) {
    init {
        require(state != DwAsrRuntimeState.INSTALLED || artifact != null) {
            "An installed engine has to say WHICH artifact is installed. A status that claims an " +
                "engine is present without naming the artifact it was verified against cannot be " +
                "checked by anything, and this app does not load code it cannot check."
        }
        require(
            state != DwAsrRuntimeState.INSTALLED || dwAsrAllVerified(artifact, verifiedSha256)
        ) {
            "An engine may only be called installed when EVERY library on disk has been hashed IN " +
                "THIS RUN and matched the digest pinned in this APK. If you are here having found " +
                "the files present and wanting to skip the hashing: that check is the only thing " +
                "standing between this app's process and somebody else's code. Hash them. And if one " +
                "of several matched, that is not most of a yes — the one that did not match is the " +
                "one that would be loaded."
        }
        require(percent == null || percent in 0..100) {
            "Download progress is a percentage or nothing at all. A figure outside 0–100 came from " +
                "arithmetic on a content length the server did not give — pass null instead, and " +
                "the surfaces will say “downloading” without a bar."
        }
    }
}

/**
 * Whether [verified] — digests taken off disk in this run — accounts for **every** library in
 * [artifact]. Null artifact is false, because nothing may be verified against nothing.
 *
 * A MULTISET COMPARISON AND NOT A `containsAll`, and the difference is the defect it prevents. Two
 * libraries in one artifact can legitimately be different files, and `containsAll` on a list holding
 * one digest twice would pass while a second library went unhashed — reporting a verified install of
 * an engine half of which nobody looked at. Sorted-list equality demands the same number of matching
 * digests as there are libraries.
 *
 * It does not check FILE NAMES against digests, and that is deliberate rather than sloppy: which name
 * a given digest came off does not matter, because the loader hashes the file at each pinned name and
 * a name whose contents do not hash to any pinned digest cannot contribute one. What matters is that
 * the number of matches equals the number of libraries, which is what this asserts.
 */
fun dwAsrAllVerified(artifact: DwAsrArtifact?, verified: List<String>): Boolean {
    if (artifact == null) return false
    val want = artifact.libraries.map { it.sha256.trim().lowercase(Locale.ROOT) }.sorted()
    val got = verified.map { it.trim().lowercase(Locale.ROOT) }.sorted()
    if (want.size != got.size) return false
    // Each pair through the real verifier rather than a plain `==`, so the fail-closed rules in
    // [dwAsrVerify] — a blank expectation is never a pass, a malformed digest is never a pass —
    // apply here too and cannot be bypassed by comparing strings directly.
    return want.zip(got).all { (expected, actual) ->
        dwAsrVerify(expected, actual) == DwAsrVerification.VERIFIED
    }
}

/**
 * Whether this status permits the engine to be loaded. **The only permission, and it is belt to the
 * braces of [DwAsrRuntimeStatus]'s own constructor.**
 *
 * Two checks of one rule, on purpose. The constructor cannot be bypassed today, but it is one
 * `@Suppress`, one `copy()` on a status built for a different artifact, or one future refactor away
 * from being weakened by somebody who does not read this far — and the cost of asking twice is a
 * handful of string comparisons on a screen that is already doing I/O.
 */
fun dwAsrMayLoad(status: DwAsrRuntimeStatus): Boolean =
    status.state == DwAsrRuntimeState.INSTALLED &&
        dwAsrAllVerified(status.artifact, status.verifiedSha256)

// ---------------------------------------------------------------------------------------------
// What it weighs — the one place in this app that CAN name a size
// ---------------------------------------------------------------------------------------------

/**
 * The static-linked engine's own bytes for `arm64-v8a`: **23,646,824**. MEASURED, not published.
 *
 * docs/ASR-RUNTIME-MEASUREMENT.md, "the delta per ABI, off the APKs' central directories": row A →
 * row E takes `lib/arm64-v8a/` from 11,074,640 to 34,721,464 bytes. Every `lib/` entry in that APK
 * reads STORED — `minSdk = 26`, `extractNativeLibs="false"`, read out of the merged manifest rather
 * than recalled — **so these are the bytes on the phone as well as the bytes in the archive.**
 *
 * WHY THE STATIC-LINKED FIGURE AND NOT THE OTHER ONE. That document's recommendation 2: if this goes
 * ahead it is `sherpa-onnx-static-link-onnxruntime`, measured at +39,811,828 for the ARM pair against
 * +53,308,196 for the default AAR. The default AAR's per-ABI figures, for whoever publishes that
 * instead, are +31,336,656 (arm64-v8a) and +21,919,692 (armeabi-v7a) — **and if the deployment
 * serves those, these two constants are wrong and must be changed with the artifact.**
 *
 * WHAT THIS FIGURE IS NOT. It is the weight of the libraries, measured inside a packaged APK. The
 * file this app would DOWNLOAD does not exist yet, so its size is unmeasured; if the deployment
 * serves it compressed, the download is smaller than this and this figure overstates the cost. That
 * is the safe direction, and it is the same argument [dwBytesLabel] makes for dividing by 1000:
 * overstating a bill beside somebody's prepaid bundle is survivable, understating it is not. Once a
 * real artifact is pinned, [DwAsrArtifact.downloadBytes] is the number that gets printed and these
 * constants go back to being what the disabled card says.
 */
const val DW_ASR_ENGINE_BYTES_ARM64: Long = 23_646_824L

/**
 * The same figure for `armeabi-v7a`: **16,152,132** — 6,789,192 → 22,941,324 in the same table.
 *
 * A DIFFERENT NUMBER IS SHOWN TO A DIFFERENT DESIGNER, which is why this is per-ABI rather than one
 * rounded figure for everybody. 16 MB and 24 MB are different decisions on a prepaid bundle in a
 * district town, and the phones that report only `armeabi-v7a` are exactly the oldest and cheapest
 * handsets in the fleet — the ones whose owners are least able to shrug at eight megabytes.
 */
const val DW_ASR_ENGINE_BYTES_ARM32: Long = 16_152_132L

/**
 * WHICH of this handset's ABIs the measurement actually covers, or null when none of them is.
 *
 * Split out from [dwAsrMeasuredEngineBytes] because **the size and the processor NAMED BESIDE IT have
 * to be the same fact**, and a sentence built from `abis.first()` is not that fact on every handset.
 * `Build.SUPPORTED_ABIS` is primary-first but it is not homogeneous: an ARC / Houdini device reports
 * `[x86_64, x86, armeabi-v7a]` and runs this app's 32-bit code, so the figure it is owed is the
 * armeabi-v7a one — while its FIRST ABI is `x86_64`, which is the one processor
 * docs/ASR-RUNTIME-MEASUREMENT.md marks "unmeasured". Printing "on this phone's processor (x86_64)
 * the engine is 16 MB" attaches an ARM measurement to an x86 name, which is precisely the invention
 * [dwAsrMeasuredEngineBytes]'s own comment promises this app does not commit.
 *
 * Lower-cased, because it is compared and printed and the two must agree.
 */
fun dwAsrMeasuredAbi(abis: List<String>): String? {
    abis.forEach { reported ->
        val normalised = reported.trim().lowercase(Locale.ROOT)
        if (normalised == "arm64-v8a" || normalised == "armeabi-v7a") return normalised
    }
    return null
}

/**
 * What the engine weighs on this handset, from the measurement, or null when it cannot be said.
 *
 * Null in two cases and both are honest: an empty ABI list (the read failed — [DwDeviceMeasurement]
 * documents why that is not "no ABIs"), and an ABI nobody has measured. **x86 and x86_64 are NOT
 * given a figure**, deliberately: docs/ASR-RUNTIME-MEASUREMENT.md marks the static-linked x86
 * measurements "unmeasured" in that word, and an emulator asking what the engine weighs gets the
 * word rather than a number invented from the ARM figures.
 */
fun dwAsrMeasuredEngineBytes(abis: List<String>): Long? = when (dwAsrMeasuredAbi(abis)) {
    "arm64-v8a" -> DW_ASR_ENGINE_BYTES_ARM64
    "armeabi-v7a" -> DW_ASR_ENGINE_BYTES_ARM32
    else -> null
}

/**
 * Free storage that must remain after the engine is installed. **256 MiB, and it is CHOSEN.**
 *
 * SMALLER THAN [DW_MODEL_FREE_STORAGE_MARGIN_BYTES] (1 GiB) ON PURPOSE, and the difference is not an
 * oversight to be tidied away. That margin guards a multi-gigabyte MODEL download, where the download
 * itself can be the thing that fills the phone. This artifact is 24 MB: the margin here is for the
 * working day — a courtyard afternoon of photographs and audio — rather than for the download, and a
 * 1 GiB bar would refuse a 24 MB file on a handset reporting 900 MB free. That refusal reads as a
 * lie to the designer looking at it, and a screen that lies about small numbers is not believed about
 * large ones.
 *
 * NOTHING HAS BEEN MEASURED ABOUT IT. How much a workshop day actually consumes on the fleet's M32 is
 * unmeasured; when somebody measures it, the number belongs in docs/DEVICE-TIER-MEASUREMENT.md and
 * this constant should be revisited against it rather than defended.
 */
const val DW_ASR_FREE_STORAGE_MARGIN_BYTES: Long = 256L * 1024L * 1024L

/**
 * Free storage needed before an install may be offered: **download + installed + margin.**
 *
 * BOTH SIZES AT ONCE, because the container and the libraries unpacked out of it are on the phone
 * together while the unpack runs: `installNow` writes every `.so` before it deletes the archive, so
 * the peak this has to clear is the sum of the two and not the larger of them.
 *
 * THE ARCHIVE IS DELETED AFTER THE UNPACK AND BEFORE THE PER-LIBRARY DIGESTS ARE TAKEN, and that is a
 * choice with a cost worth stating rather than a detail. Keeping it until after verification would let
 * a failed digest retry the unpack instead of the whole fetch — but it would also hold the download's
 * bytes on a handset that has this storage gate precisely because it may be short of them, and the
 * failure it would save from is a corrupted transfer, which is far more likely to have corrupted the
 * archive than the copy out of it. So a designer whose check fails pays for the fetch again, which is
 * 24 MB rather than a model's gigabytes, and every failure path deletes what it produced instead.
 *
 * Where the deployment serves the library uncompressed and the download IS the library, the release
 * builder sets the two sizes equal and this asks for one copy more than the install strictly needs.
 * That overstates the requirement, which refuses an install that would just about have fitted; the
 * other direction fails a download at 98% having spent the whole bundle to get there.
 */
fun dwAsrStorageNeededBytes(artifact: DwAsrArtifact): Long =
    artifact.downloadBytes + artifact.installedBytes + DW_ASR_FREE_STORAGE_MARGIN_BYTES

// ---------------------------------------------------------------------------------------------
// The offer — the predicate standing between a designer and their data allowance
// ---------------------------------------------------------------------------------------------

/**
 * What may be offered for the engine right now. **The decision behind the only control that spends
 * a designer's data in this feature.**
 *
 * The same shape as [DwPackOffer] and read the same way: exactly one value draws a button, every
 * other value is a sentence. Nothing here ever downloads by itself.
 */
enum class DwAsrOffer {
    /**
     * **THE ENGINE IS PART OF THE APP. Nothing to install, nothing to fetch, nothing to remove.**
     *
     * ── WHY THIS VALUE HAD TO EXIST, AND WHAT WAS ON SCREEN WITHOUT IT ────────────────────────
     *
     * This file was written when the engine was an opt-in download, and every value below describes
     * some state of that download. Then docs/DEVICE-TIER-MEASUREMENT.md found that
     * `System.loadLibrary` cannot be made to look in `filesDir` under any arrangement, so an engine
     * installed there could never be loaded and the AAR was vendored into the package instead.
     * [DW_TIER1_RUNTIME_PRESENT] became `true`.
     *
     * **AND THE ENGINE CARD WENT ON SAYING "Not installed".** Read off the running app on the fleet's
     * SM-M325F, two cards apart on one screen:
     *
     *  > **Speech engine — Not installed.** *"There is nothing published for this app to install
     *  > yet…"* … *"On this phone's processor (arm64-v8a) the engine is 24 MB to fetch"*
     *  >
     *  > **AI on this phone — "Tier 1 would run sherpa-onnx-omnilingual-asr-…-int8 on this phone."**
     *
     * Both were produced from one reading of one handset, and they cannot both be true. That is the
     * two-accounts-of-one-fact failure this repository has already shipped once and written up, and a
     * designer who spots it stops believing the card that is right as well as the one that is wrong.
     *
     * It is FIRST in the enum and first in [dwAsrOffer] because it outranks every question below:
     * there is no install to be in progress, no storage to be short of, and no processor question —
     * if the engine were not built for this handset the app would not have installed at all.
     */
    BUNDLED_IN_THIS_BUILD,

    /** Offer the install. **The only value that may draw a control that spends data.** */
    INSTALL,

    /**
     * The last attempt failed and may be tried again. Draws the control, worded as a retry.
     *
     * Its own value rather than folded into [INSTALL] because the button's words have to match the
     * failure sentence above it — the pack lane learned that the hard way, where a row saying "try
     * again" with no control left to do it with taught a designer the whole feature was broken.
     */
    RETRY,

    /**
     * It could be fetched, but there is no connection. Say so; **draw no button.**
     *
     * The rule this repository already keeps for [DwPackOffer.NO_CONNECTION]: a control that cannot
     * work is worse than an absent one, because the designer in a courtyard taps it, nothing
     * happens, and they conclude the app is broken rather than that they are offline.
     */
    NO_CONNECTION,

    /** Already on the phone and verified. Nothing to offer; "install" would be a lie. */
    ALREADY_INSTALLED,

    /** A fetch is in flight. Asking again would pay twice for one file. */
    IN_PROGRESS,

    /**
     * **THE STATE EVERY HANDSET IS IN TODAY, AND THE CALL THIS FILE WAS ASKED TO MAKE EXPLICITLY.**
     *
     * Installing the engine is NOT installing a model. docs/ASR-RUNTIME-MEASUREMENT.md §3 read the
     * AAR's own central directory: **there is no `assets/` entry at all** — the whole 53 MB is
     * engine, and an IndicConformer artifact is a separate download whose size that document marks
     * "unmeasured" in that word, deliberately, because it is step 5's job to measure it. Step 5
     * needs a handset and has not happened. [DW_TIER1_CATALOGUE] is correspondingly empty.
     *
     * So the engine would arrive **without a voice**: 24 MB spent, dictation unchanged, and a
     * designer who cannot tell whether they were charged for nothing or the app is broken. That is
     * the misleading this offer must not do.
     *
     * **IT IS DISABLED-WITH-A-SENTENCE RATHER THAN ABSENT, AND THAT IS THE ARGUED CALL.** This
     * repository's own rule — [DwPackOffer.NO_CONNECTION], and `AppearanceScreen`'s note on why the
     * tier card has no download button — is that a control which cannot work is worse than an absent
     * one. Both are true here and they point opposite ways, so the tie is broken on which failure is
     * worse for the designer:
     *
     *  - An ABSENT card cannot be found later. The whole point of this lane is a standing offer, and
     *    a card that materialises one release later is one nobody knows to look for. Worse, the
     *    question it answers ("can this app dictate with no signal at all?") is one a designer asks
     *    *before* a field trip, and silence answers it wrongly.
     *  - A DISABLED card with the reason on it answers the question in the place the designer looked,
     *    and the reason is not a shrug: it names what is missing, that it is about this app rather
     *    than their phone, and that nothing they can do would change it today.
     *
     * The failure the rule guards against — a tap that silently does nothing — does not arise,
     * because there is no tap: [dwAsrMayInstall] is false for this value and no button is drawn.
     */
    NO_MODEL_TO_FEED_IT,

    /**
     * This build pins no artifact at all, for any processor. **Also true of every handset today.**
     *
     * Separate from [NO_MODEL_TO_FEED_IT] because they are different missing things with different
     * owners: this one is waiting on a file being published and a digest being pinned (a release
     * task, docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md), that one is waiting on a measurement taken on a
     * handset (plan step 5). Collapsing them would leave whoever finishes one of the two unable to
     * tell from the screen that the other is still outstanding.
     */
    NOTHING_PUBLISHED_TO_INSTALL,

    /** An artifact exists, but not for any processor this handset reports. Nothing can change it. */
    NO_BUILD_FOR_THIS_PROCESSOR,

    /** `Build.SUPPORTED_ABIS` came back empty, so which engine to fetch is unknown, not absent. */
    PROCESSOR_UNMEASURED,

    /** `StatFs` would not answer, so whether it fits is a question. It will not be guessed. */
    STORAGE_UNMEASURED,

    /** It would not fit with room left for a day's photographs. Actionable, and temporary. */
    NOT_ENOUGH_STORAGE,

    /** The app has not looked at its own files yet, or could not. Claims nothing either way. */
    UNKNOWN,
}

/**
 * Whether the engine may be offered right now. **Pure, and ordered most-durable-first.**
 *
 * THE ORDER IS THE ORDER OF [dwPlanFits] AND FOR THE SAME REASON: the first refusal found is the one
 * that gets rendered, and the useful sentence is the one that tells a designer whether anything they
 * could do would change the answer.
 *
 * THE ONE ORDERING THAT WAS ARGUED OVER is [DwAsrOffer.NO_MODEL_TO_FEED_IT] against the phone's own
 * limits. It sits AFTER the processor check and BEFORE the storage check, and both halves of that
 * follow [dwTier2Offer]'s precedent — where a low-memory handset is told it is too small *before* it
 * is told nothing has been measured, because "nothing has been measured yet" invites it back after
 * the next update and for that phone the answer then is still no:
 *
 *  - A handset with no engine build for its processor will still have none after step 5 lands, so it
 *    hears the durable truth about itself rather than a roadmap it is not on.
 *  - A handset that is merely short of storage COULD take the engine, so telling it to delete a
 *    day's photographs to make room for something that would not work anyway is the worse sentence.
 *    It hears about the model instead.
 *
 * [modelCatalogue] defaults to the real (empty) Tier 1 catalogue rather than a boolean, so the two
 * facts cannot drift: there is one list of measured ASR models in this app, and this function reads
 * it rather than keeping its own opinion about whether one exists.
 */
fun dwAsrOffer(
    status: DwAsrRuntimeStatus,
    measurement: DwDeviceMeasurement,
    connection: DwConnection,
    catalogue: List<DwAsrArtifact> = DW_ASR_ARTIFACTS,
    modelCatalogue: List<DwModelPlan> = DW_TIER1_CATALOGUE,
    /** See [dwTier1Offer]. Production passes nothing; a caller that passes it is writing a test. */
    runtimeInApk: Boolean = DW_TIER1_RUNTIME_PRESENT,
): DwAsrOffer {
    /*
     * 0. IS THE ENGINE ALREADY PART OF THE APP? Checked before everything, because it makes every
     *    question below moot — see [DwAsrOffer.BUNDLED_IN_THIS_BUILD] for the screen that was wrong
     *    while this check was missing.
     */
    if (runtimeInApk) return DwAsrOffer.BUNDLED_IN_THIS_BUILD

    /*
     * 1. IS THERE ANYTHING TO INSTALL AT ALL? A fact about this BUILD, checked before anything about
     *    the handset, because no reading of a phone can change the answer — the most durable refusal
     *    there is, and today's answer everywhere.
     *
     *    IT ALSO OUTRANKS AN UNREAD DISK ON PURPOSE, and the ordering was got wrong once on the way
     *    here. Checking [DwAsrRuntimeState.UNKNOWN] first meant that a build with nothing published
     *    told every designer "this app could not look at its own files" — a true sentence about an
     *    irrelevant question, printed instead of the one that explains why the card is disabled.
     *
     *    AND IT IS THE FAIL-CLOSED DIRECTION. With no pinned artifact there is no digest, so
     *    [DwAsrRuntimeStatus] cannot be constructed in the INSTALLED state at all (its `init` demands
     *    a matching one), which means an engine left on disk by some previous release cannot be
     *    reported as installed here — and, since nothing may load without a digest to check it
     *    against, must not be.
     */
    if (catalogue.isEmpty()) return DwAsrOffer.NOTHING_PUBLISHED_TO_INSTALL

    // 2. What this phone is already doing beats everything below it. An installed engine is not
    //    offered, and one that is already coming is not paid for twice.
    when (status.state) {
        DwAsrRuntimeState.INSTALLED -> return DwAsrOffer.ALREADY_INSTALLED
        DwAsrRuntimeState.DOWNLOADING -> return DwAsrOffer.IN_PROGRESS
        // Not "not installed". The app has not looked, and a 24 MB offer made to a designer who
        // already has the engine is the wrong claim this state exists to prevent.
        DwAsrRuntimeState.UNKNOWN -> return DwAsrOffer.UNKNOWN
        DwAsrRuntimeState.NOT_INSTALLED, DwAsrRuntimeState.FAILED -> Unit
    }

    // 3. The processor, which decides whether an engine for this handset exists. Empty is unknown
    //    and NOT "matches nothing" — the same distinction `dwPlanFits` draws for ABI_UNMEASURED.
    if (measurement.abis.isEmpty()) return DwAsrOffer.PROCESSOR_UNMEASURED
    val artifact = dwAsrArtifactFor(measurement.abis, catalogue)
        ?: return DwAsrOffer.NO_BUILD_FOR_THIS_PROCESSOR

    // 4. THE ENGINE WITHOUT A VOICE. Disabled, with the reason, rather than absent — argued in full
    //    on [DwAsrOffer.NO_MODEL_TO_FEED_IT].
    if (modelCatalogue.isEmpty()) return DwAsrOffer.NO_MODEL_TO_FEED_IT

    // 5. Storage. Unmeasured refuses rather than passing: a download started on a question nobody
    //    answered is the one that dies at 98% having spent the bundle to get there.
    val free = measurement.freeStorageBytes ?: return DwAsrOffer.STORAGE_UNMEASURED
    if (free < dwAsrStorageNeededBytes(artifact)) return DwAsrOffer.NOT_ENOUGH_STORAGE

    // 6. The connection, last, because it is the one that changes while a designer stands there.
    if (connection == DwConnection.NONE) return DwAsrOffer.NO_CONNECTION

    // A failed attempt fetched nothing, so there is nothing to pay for twice — and the note beside
    // the control tells the designer to try again, which needs a control to do it with.
    return if (status.state == DwAsrRuntimeState.FAILED) DwAsrOffer.RETRY else DwAsrOffer.INSTALL
}

/**
 * Whether a control that spends a designer's data may be drawn. **The only gate, and today it is
 * false on every handset in existence.**
 *
 * `DwAsrRuntimeTest` asserts that across every device shape and every connection, so a future row in
 * [DW_ASR_ARTIFACTS] cannot quietly turn a download loose on the fleet without a test going red.
 */
fun dwAsrMayInstall(offer: DwAsrOffer): Boolean =
    offer == DwAsrOffer.INSTALL || offer == DwAsrOffer.RETRY

// ---------------------------------------------------------------------------------------------
// The words. One state cannot read differently on the first-run card and in Settings, so there is
// exactly one copy of every sentence and both surfaces read it from here.
// ---------------------------------------------------------------------------------------------

/** The short state, for the end of a row or the corner of a card. The sentence carries the rest. */
fun dwAsrStateLabel(state: DwAsrRuntimeState): String = when (state) {
    DwAsrRuntimeState.UNKNOWN -> "Not checked"
    DwAsrRuntimeState.NOT_INSTALLED -> "Not installed"
    DwAsrRuntimeState.DOWNLOADING -> "Downloading"
    DwAsrRuntimeState.INSTALLED -> "Installed"
    DwAsrRuntimeState.FAILED -> "Last attempt failed"
}

/**
 * The one-paragraph pitch on the first-run card. **The settings card has its own
 * ([DW_ASR_CARD_BLURB]), because the two are read in different situations.**
 *
 * ── THE LAST CLAUSE EXISTS BECAUSE OF WHERE THIS TEXT IS READ ─────────────────────────────────
 *
 * IT IS THE WHOLE OF THE COLLAPSED CARD. [DwAsrRuntimeOfferCard] draws a heading, this paragraph and
 * two buttons; the state, the size and [DW_ASR_ENGINE_WITHOUT_A_VOICE] appear only once "What would it
 * cost?" is tapped, because probing the phone and hashing 24 MB on the dashboard's first frame is the
 * thing the collapsed shape exists to avoid. So for a designer who reads the card and taps "Not now",
 * this paragraph is everything this app ever said to them about its speech engine — and an earlier
 * draft of it ended at *"This app can install a speech engine of its own to close that gap"*, which
 * told them, with nothing beside it, that offline Odia dictation was a download away. It is not:
 * nothing is published to install and no speech model has been measured
 * ([DwAsrOffer.NO_MODEL_TO_FEED_IT]).
 *
 * The last clause therefore names the two things an engine needs and sends them to the expanded card
 * for today's answer, **which is worded so that it does not rot**: it states a standing precondition
 * rather than today's state, so it stays true on the day a row is pinned and a model measured, when
 * the expanded card starts saying yes.
 *
 * Same two clauses the deleted `DW_PACK_OFFER_BLURB` had — what it buys and what it costs —
 * except that this one can name what the packs cannot: the languages. **The two-of-nineteen figure
 * is MEASURED**, off the fleet's own Galaxy M32 (docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md is raw
 * logcat: `checkRecognitionSupport` returned thirty languages, of which exactly `hi-IN` and `en-IN`
 * are ours). It is said as a reading from one handset, because that is what it is.
 *
 * IT DOES NOT PROMISE ODIA WORKS. It says Google's packs do not carry it, which is measured, and
 * leaves what this engine would do about it to [DW_ASR_ENGINE_WITHOUT_A_VOICE] — because the word
 * for how well an IndicConformer model transcribes an Odia courtyard is "unmeasured", and plan §2.2
 * makes that measurement the bar before offering it.
 */
const val DW_ASR_OFFER_BLURB: String =
    "Dictation normally uses Android's own speech packs when the language is on the phone, and a " +
        "server when it is not. On the handset this was measured on, Android carries offline packs " +
        "for two of this app's nineteen languages — Odia, the language of the state these " +
        "workshops are run in, is not one of them. This app can carry a speech engine of its own to " +
        "close that gap. It is not part of the app: it is a download you choose, because most " +
        "designers never work anywhere without signal and should not pay for it. An engine also " +
        "needs a speech model to go with it, and whether either can be installed on this phone " +
        "today is the first thing this card says when you open it — read that before planning a " +
        "field trip around it."

/**
 * **WHAT THE ENGINE DOES NOT YET BUY, SAID EVERY TIME THE ENGINE IS MENTIONED.**
 *
 * This is the sentence that stops this feature from misleading somebody. An engine is not a voice:
 * docs/ASR-RUNTIME-MEASUREMENT.md §3 read the AAR's own entries and found no `assets/` at all, so
 * the whole download is machinery. Until a model is measured on a handset (plan step 5), installing
 * it changes nothing a designer can see or hear.
 *
 * It names both halves of what would complete it — the model, and the accuracy measurement that
 * plan §2.2 requires before any of it is offered — so a designer reading the disabled card knows
 * what they are waiting for rather than only that they are waiting.
 */
const val DW_ASR_ENGINE_WITHOUT_A_VOICE: String =
    "The engine arrives without a voice, and that is why nothing can be installed yet. It is the " +
        "machinery for listening; the language model that does the actual recognising is a separate " +
        "download, and no such model has been run on a real handset and measured — not its size, " +
        "not how much memory it needs, and not how well it transcribes an Odia courtyard. Until " +
        "that measurement is taken, installing the engine would spend your data and change nothing " +
        "you could hear, so this app does not offer it."

/**
 * What the designer's own bytes buy them, once there IS something to install. Said above the button.
 *
 * Deliberately modest. It promises dictation with no connection, which is what the engine is for,
 * and it does not promise a quality of transcription — "better than nothing is the honest bar in a
 * courtyard, but it has to actually clear it" is plan §2.2's own wording, and clearing it is
 * unmeasured.
 */
const val DW_ASR_WHAT_IT_BUYS: String =
    "With the engine and a language model on the phone, dictation into the workshop's fields works " +
        "with no signal at all — the phone does the listening, so a courtyard with no bars is the " +
        "same as the office. Everything already saved on the phone stays exactly as it is."

/**
 * **THE SIZE, NAMED BEFORE THE TAP. The one place in this app that can do this.**
 *
 * [dwDownloadCostSentence] deliberately refuses to print a size for a Google pack, and is right to:
 * `triggerModelDownload` reports none, anywhere in the API, so any figure would be invented. OUR
 * artifact has been weighed — docs/ASR-RUNTIME-MEASUREMENT.md, off the packaged APK's own central
 * directory — so this screen states it, per processor, because a designer on `armeabi-v7a` fetches a
 * genuinely different number from one on `arm64`.
 *
 * The metered warning is above the button rather than after the tap, exactly as in the pack lane.
 * Where the figure comes from the measurement rather than from a published artifact it is a size for
 * the same libraries, and if the deployment ever serves them compressed the real download is smaller
 * — overstating a bill beside a prepaid bundle is the survivable direction.
 */
fun dwAsrCostSentence(
    measurement: DwDeviceMeasurement,
    connection: DwConnection,
    artifact: DwAsrArtifact? = null,
): String {
    val bytes = artifact?.downloadBytes ?: dwAsrMeasuredEngineBytes(measurement.abis)
    /*
     * THE PROCESSOR NAMED IS THE ONE THE FIGURE IS FOR, WHICH IS NOT ALWAYS THE FIRST ONE REPORTED.
     * A pinned artifact knows its own ABI; a measurement knows which of this handset's ABIs it covers
     * ([dwAsrMeasuredAbi]). Only when there is no figure at all does the primary ABI get named, and
     * then it is named as the processor whose size is unmeasured, which is true of it.
     */
    val processor = artifact?.abi
        ?: dwAsrMeasuredAbi(measurement.abis)
        ?: measurement.abis.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    val size = when {
        // The word, not a number. An emulator on x86, or a handset whose ABI list would not be read,
        // gets told the size is unmeasured rather than given the ARM figure with a straight face.
        bytes == null && processor == null ->
            "This phone would not say what processor it has, so how much the engine weighs on it is " +
                "unmeasured."
        bytes == null ->
            "How much the engine weighs on this phone's processor ($processor) is unmeasured — the " +
                "measurement covers arm64-v8a and armeabi-v7a, which is what the field fleet runs."
        /*
         * TWO NUMBERS WHERE THE PUBLISHED FILE IS COMPRESSED, because they are two different costs to
         * a designer: the download is what the prepaid bundle pays for and the install is what stays
         * on the phone until they remove it. Saying "and about the same to keep" would be wrong by the
         * whole compression ratio, which ASR-RUNTIME-MEASUREMENT.md §5 measured once at better than
         * two to one (57,662,788 stored bytes to 24,581,961 compressed).
         */
        artifact != null && artifact.installedBytes != bytes ->
            "On this phone's processor ($processor) the engine is ${dwBytesLabel(bytes)} to fetch and " +
                "${dwBytesLabel(artifact.installedBytes)} to keep on the phone once it is unpacked. " +
                "Both have to fit while it is installing."
        else ->
            "On this phone's processor ($processor) the engine is ${dwBytesLabel(bytes)} to fetch, " +
                "and those same ${dwBytesLabel(bytes)} stay on the phone until you remove it."
    }
    val meter = when (connection) {
        DwConnection.NONE ->
            "There is no connection now, so nothing can be fetched."
        DwConnection.METERED ->
            "This phone is on mobile data, so it would be paid for by the megabyte. On a prepaid " +
                "bundle, wait for Wi-Fi — nothing here expires."
        DwConnection.UNMETERED ->
            "This phone is on Wi-Fi, so nothing here is charged by the megabyte."
    }
    return "$size $meter"
}

/**
 * The full sentence for one offer state. **Every state, including the ones nobody can act on.**
 *
 * Takes the same inputs as [dwAsrOffer] so that a surface cannot describe a different world from the
 * one it decided in — the pack lane's own scar is two surfaces disagreeing about one pack, and this
 * lane has two surfaces from the first day.
 */
fun dwAsrOfferSentence(
    offer: DwAsrOffer,
    measurement: DwDeviceMeasurement,
    catalogue: List<DwAsrArtifact> = DW_ASR_ARTIFACTS,
): String = when (offer) {
    /*
     * THE ANSWER EVERY HANDSET GETS TODAY. It says the engine is here, says what it is, and says the
     * one thing a designer reading a card headed "Offline speech engine" actually needs next — that
     * the engine alone does nothing and the MODEL card below it is where the capability comes from.
     * Without that last clause this reads as "offline dictation is ready", which it is not.
     */
    DwAsrOffer.BUNDLED_IN_THIS_BUILD ->
        "This app's speech engine is part of the app itself, so there is nothing to install, " +
            "nothing to download and nothing that can go missing. It came with the app and it goes " +
            "when the app does. On its own it does nothing you can hear: the engine is the machinery " +
            "for listening, and what it listens WITH is the speech model on the card below — that is " +
            "the one that decides which languages this phone can hear with no signal."

    DwAsrOffer.INSTALL ->
        "The engine can be installed on this phone now. Nothing is fetched until you tap the button " +
            "below, and it can be removed again afterwards."

    DwAsrOffer.RETRY ->
        "The last attempt did not finish, so nothing was installed and nothing was kept. Whatever " +
            "arrived has been deleted. You can try again — a part-finished download costs nothing " +
            "to abandon."

    DwAsrOffer.NO_CONNECTION ->
        "The engine cannot be fetched now, because there is no connection. Dictation goes on working " +
            "exactly as it does today: the phone's own packs where it has them, the server where it " +
            "does not, and typing where there is neither."

    DwAsrOffer.ALREADY_INSTALLED ->
        "This app's speech engine is installed on this phone and was checked against the fingerprint " +
            "built into this app when it started. It stays until you remove it, and it is a setting " +
            "of THIS PHONE — it does not follow your account to another handset."

    /*
     * IT SAYS "STAY ON THIS SCREEN", BECAUSE THAT IS WHAT THE CODE DOES. The fetch belongs to the
     * screen that started it (`rememberDwAsrRuntime`) and is cancelled when that screen leaves, so a
     * sentence promising it continues in the background would be a promise this app does not keep —
     * and a designer who wandered off and came back to nothing would have spent the data for it. The
     * next lane's move, if this becomes a real irritation, is written down in `DwAsrRuntimeUi.kt`.
     */
    DwAsrOffer.IN_PROGRESS ->
        "The engine is being fetched now. Stay on this screen while it finishes — leaving stops it, " +
            "and a part-finished download is thrown away rather than kept, so nothing is left " +
            "half-installed. There is nothing more to tap: a second tap would pay for one file twice."

    // The two that are true of every handset today. Both name whose job the missing thing is.
    DwAsrOffer.NO_MODEL_TO_FEED_IT -> DW_ASR_ENGINE_WITHOUT_A_VOICE

    DwAsrOffer.NOTHING_PUBLISHED_TO_INSTALL ->
        "There is nothing published for this app to install yet. The engine is not part of the app " +
            "and is not fetched from a stranger's website either: it has to be published to this " +
            "deployment's own storage first, with a fingerprint of the exact file recorded inside " +
            "this app, so that what arrives can be checked before it is ever run. That has not been " +
            "done, so this app has nothing to offer and will not pretend otherwise."

    DwAsrOffer.NO_BUILD_FOR_THIS_PROCESSOR -> buildString {
        append("There is no build of this app's speech engine for this phone's processor")
        measurement.abis.firstOrNull()?.let { append(" ($it)") }
        append(
            ". Nothing on this screen can change that. Dictation carries on as it does today — the " +
                "phone's own packs where it has them, and the server where it does not."
        )
        // Named, so a designer reading it down a phone line can say which builds DO exist rather
        // than only that theirs is not among them.
        val built = catalogue.map { it.abi }.distinct()
        if (built.isNotEmpty()) append(" The engine is built for ${built.joinToString(", ")}.")
    }

    /*
     * IT NAMES NO SIZE, AND THAT IS THE DISCIPLINE THIS FILE KEEPS EVERYWHERE ELSE. The size of the
     * engine is per-processor, and this is the one handset whose processor could not be read — so "24
     * MB" here would be the arm64 figure printed at a phone that may well be the 16 MB one, or
     * neither. An earlier draft did exactly that.
     */
    DwAsrOffer.PROCESSOR_UNMEASURED ->
        "This phone would not say what kind of processor it has, so which build of the engine it " +
            "would need is unknown — and this app will not fetch tens of megabytes of program code " +
            "on a guess about which one runs here. Tap “Check again”; if it keeps saying this, " +
            "dictation stays exactly as it is."

    DwAsrOffer.STORAGE_UNMEASURED ->
        "This phone would not say how much storage is free, so whether the engine would fit is " +
            "unknown, and nothing will be fetched on a guess. Tap “Check again”."

    DwAsrOffer.NOT_ENOUGH_STORAGE -> buildString {
        append("There is not enough free storage on this phone for the engine, with room left over ")
        append("for a day of photographs and recordings. This phone reports ")
        append(dwBytesLabel(measurement.freeStorageBytes))
        append(" free. Delete what you can spare and tap “Check again” — the size is stated above ")
        append("the button before anything is fetched.")
    }

    DwAsrOffer.UNKNOWN ->
        "This app has not yet been able to look at its own files to see whether the engine is " +
            "installed, so it will not claim either way. Tap “Check again”."
}

/**
 * What the fingerprint check does and does not establish, in a designer's words. Said on both cards.
 *
 * A DESIGNER IS ENTITLED TO KNOW THIS. They are being asked to let this app fetch executable code
 * onto a handset that holds artisans' Aadhaar numbers, and "we check it" is the reason that is
 * defensible. It is one sentence and it claims exactly what is true: the file is compared against a
 * fingerprint that is part of the app, so it cannot be swapped for another file — and not that
 * anybody has audited what the engine itself does.
 */
const val DW_ASR_VERIFY_SENTENCE: String =
    "Whatever is fetched is checked against a fingerprint built into this app before any of it is " +
        "used, and thrown away if it does not match. The engine is program code that runs inside " +
        "this app, alongside the records on this phone, which is why it is fetched only from this " +
        "deployment's own storage and never from anywhere else."

/** Said when a fetched file does not match the pinned digest. It is deleted, and this is why. */
const val DW_ASR_MISMATCH_SENTENCE: String =
    "What arrived did not match the fingerprint built into this app, so it has been deleted and " +
        "nothing was installed. That is usually a download that broke in transit and trying again " +
        "on a better connection is the next move. If it happens twice, stop and tell whoever " +
        "administers this deployment: it means the file being served is not the file this app expects."

/**
 * The one-line pitch for the settings card's heading area.
 *
 * It promises nothing, because today the card underneath it is an explanation of why there is
 * nothing to install — and a designer should be able to tell that from the first line rather than
 * after reading three paragraphs.
 *
 * **NOTHING DRAWS THIS TODAY.** Its sibling `DW_TIER_CARD_BLURB` was deleted on 2026-08-13 for having
 * no caller; this one is one step behind that — `DwAsrRuntimeUi.kt` still IMPORTS it and never uses
 * it, so the 46 words reach no screen. It is kept only because `DwAsrRuntimeTest` asserts its
 * wording. If the engine card does not come back, delete this and those assertions together.
 */
const val DW_ASR_CARD_BLURB: String =
    "This app can carry a speech engine of its own, so that dictation works where there is no " +
        "signal and in languages the phone's own packs do not cover. It is optional, it is a " +
        "setting of this phone rather than of your account, and nothing here downloads anything by " +
        "itself."
