package com.designprototype.workshop.data

/**
 * **THE TIER 2 LANGUAGE MODELS — FOUR REAL ARTIFACTS, WEIGHED, WITH THE PROVENANCE OF EVERY NUMBER
 * ATTACHED. TWO OF THEM CAN BE JUDGED AGAINST A HANDSET AND TWO OF THEM CANNOT.**
 *
 * `DwDeviceTier.kt`'s [DW_TIER2_CATALOGUE] used to be `emptyList()` and its comment said why: nobody
 * knew whether a mobile export of a Gemma model existed for Android at all. **That is no longer true
 * and this file is the correction.** `hf download --dry-run` as an authenticated user, the four files
 * downloaded to this machine, weighed and hashed:
 *
 *  | artifact | repo | gate | bytes |
 *  |---|---|---|---|
 *  | `gemma-4-E2B-it.litertlm` | `litert-community/gemma-4-E2B-it-litert-lm` | none, Apache-2.0 | 2,588,147,712 |
 *  | `gemma-4-E4B-it.litertlm` | `litert-community/gemma-4-E4B-it-litert-lm` | none, Apache-2.0 | 3,659,530,240 |
 *  | `gemma-3n-E2B-it-int4.litertlm` | `google/gemma-3n-E2B-it-litert-lm` | **manual approval** | 3,655,827,456 |
 *  | `gemma-3n-E4B-it-int4.litertlm` | `google/gemma-3n-E4B-it-litert-lm` | **manual approval** | 4,919,541,760 |
 *
 * ── WHY ONLY TWO OF THE FOUR ARE [DwModelPlan]s, AND WHY THAT IS NOT SCOPE BEING NARROWED ─────
 *
 * [DwModelPlan.peakRssBytes] is required and non-null, and [DwModelPlan.measuredOn] is the name of the
 * handset the figure came off. **The two Gemma 3n model cards publish no memory figure at all** — they
 * tabulate prefill and decode and stop. The only memory number that exists for that family is a
 * sentence in a Google Developers Blog post ("as little as 2GB (E2B) and 3GB (E4B) of memory"), which
 * is a claim about a FAMILY and not a reading of THIS artifact on ANY named phone. Writing
 * `peakRssBytes = 2 GB, measuredOn = "?"` to get past the constructor is the single thing that class
 * exists to make impossible, so those two rows were carried as unjudged rows instead (removed 2026-10-10): listed on
 * every device, with their real size and their real digest, and with the word **unmeasured** where the
 * memory figure would be. A row that cannot be judged is still a row a designer may read; what it may
 * not do is carry a verdict computed from a number nobody took.
 *
 * ── THE MEMORY FIGURES THAT DO EXIST ARE GOOGLE'S, NOT OURS, AND THE ROWS SAY SO IN THOSE WORDS ──
 *
 * `1733` and `3283` come off the two `litert-community` model cards' Android tables: **S26 Ultra**,
 * `rusage::ru_maxrss`, 1,024 prefill and 256 decode tokens at a 2,048-token context. That is a
 * measurement — somebody ran it — but it was not run on any handset in this fleet, and
 * docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md exists because this repository once shipped capabilities
 * claimed for a named handset that the handset did not have. So [DwModelPlan.measuredOn] carries the
 * S26 Ultra AND the fact that it is published rather than local, every sentence built from it repeats
 * that, and `docs/TIER2-LANGUAGE-MODEL-MEASUREMENT.md` records what a fleet measurement would cost
 * (nothing in bandwidth — the artifacts are already on the release machine's disk).
 *
 * **THE `MB` IN GOOGLE'S COLUMN IS READ AS MiB HERE, AND THE REASON IS TECHNICAL RATHER THAN
 * CAUTIOUS.** The card states the figure was taken with `rusage::ru_maxrss`, which on Linux and Android
 * is reported in KILOBYTES (1,024-byte units); a tool dividing it by 1,024 to label a column "MB" has
 * produced MiB. 1733 MiB is 1,817,182,208 bytes and 1733 MB would be 1,733,000,000 — the difference is
 * 84 MB, and it lands in the direction this file errs everywhere else: a model refused that would have
 * run costs a designer a capability, and a model offered that will not run costs them the workshop.
 *
 * **BUT IT IS A CHOICE UNDER AMBIGUITY AND NOT A SETTLED FACT, AND THE EVIDENCE AGAINST IT IS RECORDED
 * HERE RATHER THAN LEFT FOR SOMEBODY TO FIND.** The same table's *other* megabyte column is provably
 * DECIMAL MB: its Web row reads `2008` for `gemma-4-E2B-it-web.litertlm`, and that file is
 * 2,008,432,640 bytes — 2,008,432,640 / 1,000,000 to the unit, where MiB would have read 1915. So at
 * least one column labelled "(MB)" on this card counts decimal megabytes, and the argument above rests
 * entirely on the memory column coming from a different code path (`ru_maxrss`) than the size column
 * (a `stat`). The MiB reading is kept because it is the PESSIMISTIC one and a verdict may not be tuned
 * towards installability — not because the card settles it.
 *
 * **THE CONSEQUENCE IS A REAL ONE AND IS NOT HIDDEN: [dwTier2RowSentence] PRINTS THE CONVERTED FIGURE
 * IN A CLAUSE THAT ATTRIBUTES IT TO GOOGLE.** A designer reads "It needs 1.8 GB of memory … that figure
 * is Google's"; Google's card prints `1733` under "(MB)", which in the card's own decimal convention is
 * 1.7 GB. The same applies harder to the graphics-backend clause, which says "They publish 709 MB"
 * where the card publishes `676`. Fixing that honestly means printing a published figure exactly as
 * published while a verdict is computed from a separate pessimistic bound — which needs a second field
 * on [DwModelPlan] and is therefore not done here. Until it is, the numbers above are the ones on
 * screen and this paragraph is the record of how they differ from the source.
 *
 * **THE CPU FIGURE IS THE ONE IN THE ROW, NOT THE GPU FIGURE, AND THAT IS DELIBERATE.** Google publish
 * `676` (E2B) and `710` (E4B) for the GPU backend — under the same ambiguous "(MB)" heading as the
 * paragraph above, and less than half. Two reasons the smaller number
 * is not what a verdict is computed from: `ru_maxrss` does not count GPU or dmabuf allocations, so on a
 * phone with unified memory it is a floor rather than a cost; and whether LiteRT-LM's ML Drift GPU path
 * initialises at all on this fleet's Mali-G52 is **unmeasured** — `libOpenCL.so` and
 * `libvndksupport.so` are both present on the handset, which is a necessary condition and not a
 * sufficient one. Choosing the GPU number would make both models look comfortable on a phone nobody
 * has run them on, which is precisely the tuning the brief forbids. The GPU figure is printed BESIDE
 * the verdict, as a claim, because it is the number that would change the answer if it held.
 *
 * ── WHAT THIS FILE DOES NOT DO ────────────────────────────────────────────────────────────────
 *
 * It computes NO fit of its own. [dwModelFit] in `DwModelChoice.kt` judges these plans exactly as it
 * judges the speech model — same margins, same order of durability, same four answers — and the
 * sentences below take a [DwModelChoice] that has already been judged. A second set of rules that
 * happened to be kinder to a 2.6 GB download is the failure mode this note exists to name.
 *
 * **AND SINCE 2026-10-10 THEY RUN.** LiteRT-LM 0.18.0 is in the APK (`DwTier2Engine.kt`), the two
 * Gemma 4 files are fetched on demand from the pinned URLs below and verified against the pinned
 * SHA-256 before anything opens them (`DwTier2Install.kt`, `DwTier2ModelInstallUi.kt`), and a phone
 * whose own reading rules a row out is simply not offered it. The two Gemma 3n artifacts that used
 * to be listed beside them with no verdict are gone from this file: their repositories need a
 * licence accepted by a person before anyone can be given the bytes, so no phone could ever be
 * offered them, and a row that can never be acted on is not a row a designer needs to read.
 */

// ---------------------------------------------------------------------------------------------
// The runtime
// ---------------------------------------------------------------------------------------------

/*
 * `com.google.ai.edge.litertlm:litertlm-android:0.18.0`, Google's Maven, Apache-2.0, `minSdk 24`,
 * `liblitertlm_jni.so` for arm64-v8a and x86_64. It did not compile here while the project was on
 * Kotlin 2.0.21 — `Engine` carries Kotlin metadata newer than that compiler could read, across every
 * published version — and the Kotlin 2.4.21 toolchain of 2026-10-09 removed that obstacle. 0.18.0
 * (metadata 2.4) is the newest release on 2026-10-10. MediaPipe's `tasks-genai` was the priced
 * alternative and is not used: Google calls that LLM route "in maintenance mode", and it takes
 * `.task` bundles that Gemma 4 does not publish for phones.
 */

// ---------------------------------------------------------------------------------------------
// The two artifacts a phone could be given, as files with digests
// ---------------------------------------------------------------------------------------------

/**
 * Where a Tier 2 artifact's bytes come from, and what they must hash to.
 *
 * **CHANGED 2026-10-10: THE BYTES COME FROM THE UPSTREAM REPOSITORY, PINNED TO A REVISION.** This said
 * "never a Hugging Face URL from a handset", on the ground that `huggingface.co` needs credentials for
 * a gated repository. That is true of the Gemma 3n repositories and false of the two Gemma 4 ones this
 * file now carries: both are ungated, so a field handset fetches them with no credential at all —
 * exactly as the speech model's pinned GitHub release asset is fetched — and the SHA-256 pinned here is
 * what makes the host irrelevant to safety: a host that changed the bytes changes them into a file
 * [dwTier2VerifyFile] refuses and the controller deletes.
 */
data class DwTier2Artifact(
    /** Matches [DwModelPlan.modelId] of the row it carries, so a row and its bytes cannot drift. */
    val modelId: String,
    /** The upstream repository, named so a reader can check the file for themselves. */
    val repo: String,
    /** The file as it is published and as it would sit on the phone. A bare name, never a path. */
    val fileName: String,
    /**
     * Where the bytes are fetched from: an `https://` URL **pinned to a repository revision**, so the
     * file behind it cannot change under the digest below. A Hugging Face URL, and safe for the reason
     * the speech model's GitHub URL is: [sha256] is pinned in the APK and checked before the file is
     * opened, so a host that changed the bytes changes them into a file this app deletes. The two
     * repositories are ungated (Apache-2.0), so no credential is needed and none is sent.
     */
    val url: String,
    /** Its size, in bytes, **weighed on this machine's copy** rather than read off a model card. */
    val bytes: Long,
    /** Lower-case hex SHA-256 of that file. See [digestProvenance] for who took it. */
    val sha256: String,
    /**
     * **WHO TOOK THE DIGEST — MEASURED HERE, OR PUBLISHED BY THE HOST.**
     *
     * The distinction is not pedantry for a file that is fed to a native executor: a digest computed
     * on bytes that sat on this disk proves what was weighed; an LFS `oid` read out of a repository's
     * own API is the host's claim about bytes nobody here has held, and it is all there is for a
     * repository that will not release the file without approval.
     */
    val digestProvenance: String,
    /** Whether the upstream repository releases the file without a human accepting a licence. */
    val needsUpstreamApproval: Boolean,
) {
    init {
        require(modelId.isNotBlank()) { "A Tier 2 artifact names the model row it carries." }
        require(repo.isNotBlank()) {
            "A Tier 2 artifact names the repository it came out of. A file with no upstream behind " +
                "it cannot be re-fetched, re-checked or reported as wrong."
        }
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName && ".." !in fileName) {
            "A Tier 2 artifact needs a bare file name: “$fileName” is not one. These names address " +
                "files inside this app's own storage, and a separator would put multi-gigabyte bytes " +
                "somewhere this feature does not own."
        }
        require(url.startsWith("https://") && "/resolve/" in url && url.endsWith("/$fileName")) {
            "A Tier 2 artifact is fetched over TLS from a URL pinned to a revision and ending in its " +
                "own file name: “$url” is not one."
        }
        require(bytes > 0L) {
            "A Tier 2 artifact needs its real size. This is the figure a designer reads before " +
                "spending a data bundle on it, and a zero would print as “0 MB” for 2.6 GB."
        }
        require(sha256.length == 64 && sha256.all { it in "0123456789abcdef" }) {
            "A Tier 2 artifact needs the full 64-character lower-case hex SHA-256 of the exact file. " +
                "Nothing this size is opened without one — a substituted language model produces " +
                "confident prose rather than an error, and nobody would check it against the source."
        }
        require(digestProvenance.isNotBlank()) {
            "A digest has to say who took it. “Measured here” and “published by the host” are " +
                "different degrees of evidence and this app may not print them in one voice."
        }
    }
}

/**
 * **THE TWO ARTIFACTS, AS FILES, BOTH WEIGHED AND HASHED HERE.** `hf download --dry-run` reports both
 * repositories `gated=false`, `license:apache-2.0`. Re-checked 2026-10-10 against
 * `/api/models/<repo>?blobs=true`: the LFS `sha256` at each pinned revision equals the digest below,
 * and a ranged GET of the E2B URL answered `206` with `Content-Range: bytes 0-15/2588147712`.
 */
val DW_TIER2_ARTIFACTS: List<DwTier2Artifact> = listOf(
    DwTier2Artifact(
        modelId = "gemma-4-E2B-it.litertlm",
        repo = "litert-community/gemma-4-E2B-it-litert-lm",
        fileName = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
            "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm",
        bytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        digestProvenance = "MEASURED — the file was downloaded on 2026-08-13 and hashed on the " +
            "release machine. Google's own “Model size (MB)” column reads 2583 for the same " +
            "artifact; the byte count above is what `ls` and `sha256sum` agree on, and it is the " +
            "figure this app states.",
        needsUpstreamApproval = false,
    ),
    DwTier2Artifact(
        modelId = "gemma-4-E4B-it.litertlm",
        repo = "litert-community/gemma-4-E4B-it-litert-lm",
        fileName = "gemma-4-E4B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/" +
            "2eee7ac325f20eb8c9ac1d0e972f7c84663062da/gemma-4-E4B-it.litertlm",
        bytes = 3_659_530_240L,
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        digestProvenance = "MEASURED — downloaded 2026-08-13 and hashed on the release machine. " +
            "Google's “Model size (MB)” column reads 3654 for it.",
        needsUpstreamApproval = false,
    ),

)

/** The artifact carrying [modelId], or null when this build pins none for it. */
fun dwTier2ArtifactFor(
    modelId: String,
    catalogue: List<DwTier2Artifact> = DW_TIER2_ARTIFACTS,
): DwTier2Artifact? = catalogue.firstOrNull { it.modelId == modelId }

// ---------------------------------------------------------------------------------------------
// The rows that can be judged
// ---------------------------------------------------------------------------------------------

/**
 * The context cap the published memory figures were taken at. **2,048 tokens, and it is theirs.**
 *
 * Both model cards: *"All benchmarks were taken using 1024 prefill tokens and 256 decode tokens with a
 * context length of 2048 tokens via LiteRT-LM."* The artifact supports up to 32k — the cards say so —
 * and what the resident set does at 32k is **unmeasured** by anybody, which matters more here than for
 * a speech model because KV-cache grows with context and can exceed the weights. A row pinned at 2,048
 * is a row whose memory figure and whose envelope came out of the same run.
 */
const val DW_TIER2_MEASURED_CONTEXT_TOKENS: Int = 2048

/** One MiB, for reading Google's `ru_maxrss` column in the units it was actually taken in. */
private const val DW_TIER2_MIB: Long = 1024L * 1024L

/**
 * **THE TIER 2 ROWS. TWO OF THEM, AND [DW_TIER2_CATALOGUE] DELEGATES TO THIS LIST.**
 *
 * Both are judged on every handset by [dwModelFit], which is this feature's whole rule: *we suggest, they choose* — so every model is visible
 * everywhere with its size, its memory requirement and this device's verdict, and the verdict comes
 * from [dwModelFit] rather than from anything written here.
 *
 * On the fleet's SM-M325F, read at 03:00 on 2026-08-13 (`MemTotal` 5,789,032 kB, `MemAvailable`
 * 1,285,164 kB, `/data` 39,034,012 kB free, `arm64-v8a,armeabi-v7a,armeabi`), both rows come out
 * [DwModelFit.TIGHT] on one note — [DwFitNote.LITTLE_FREE_MEMORY_RIGHT_NOW] — which is the overridable
 * kind, because memory frees. Neither is refused: both peaks are far under the phone's total and both
 * files are far under its free storage. An `armeabi-v7a`-only handset gets
 * [DwFitNote.NO_BUILD_FOR_THIS_PROCESSOR] and [DwModelFit.WILL_NOT_FIT], and that is not arithmetic
 * about memory — the runtime AAR has no 32-bit build at all.
 */
val DW_TIER2_PLANS: List<DwModelPlan> = listOf(
    DwModelPlan(
        modelId = "gemma-4-E2B-it.litertlm",
        /*
         * NOT "int4". Google's quantisation-aware-training scheme for Gemma 4 mixes 2-, 4- and 8-bit
         * weights, and the E2B card is explicit that the text-only weight footprint can be as low as
         * 0.8 GB while 1.12 GB of embedding parameters are memory-mapped rather than resident. Calling
         * it int4 would be borrowing the 3n family's word for a different artifact.
         */
        quantisation = "mixed 2/4/8-bit QAT (Gemma 4 mobile scheme)",
        /*
         * arm64-v8a, AND THIS IS A FACT ABOUT THE RUNTIME RATHER THAN ABOUT THE WEIGHTS. The
         * `.litertlm` container is architecture-independent; what has only one ARM build is
         * `liblitertlm_jni.so` inside `litertlm-android-0.16.0.aar`, which carries arm64-v8a and
         * x86_64 and no armeabi-v7a. Read out of the AAR, not off a page.
         */
        abi = "arm64-v8a",
        /*
         * NULL, WHICH IS THE WORD "unmeasured", AND THE ALTERNATIVE WOULD HAVE BEEN A README'S COUNT.
         * Google claim 35+ languages out of the box and pre-training in 140+. `DwModelPlan.languages`
         * forbids exactly that: it is a claim about a family, this field is a claim about this
         * artifact, and nobody has read this file's tokeniser or scored it against any of this app's
         * nineteen. Null contributes nothing to any language's coverage, which is correct — an
         * unchecked model is not evidence that a language is served.
         */
        languages = null,
        contextCapTokens = DW_TIER2_MEASURED_CONTEXT_TOKENS,
        runBound = "one answer at a 2,048-token context cap (1,024 tokens in, 256 out), which is the " +
            "envelope Google's memory figure was taken at; the artifact supports up to 32k and " +
            "nobody has measured what that costs.",
        onDiskBytes = 2_588_147_712L,
        /*
         * 1733 MiB, CPU BACKEND. See this file's header for both halves of why: the column labelled
         * "MB" was taken with `ru_maxrss`, which reports kilobytes, so MiB is the honest reading; and
         * the CPU figure rather than the GPU's 676 MiB is what a verdict may be computed from, because
         * `ru_maxrss` does not count GPU allocations and the GPU path is unmeasured on this fleet.
         */
        peakRssBytes = 1733L * DW_TIER2_MIB,
        measuredOn = "Galaxy S26 Ultra — Google's published figure, not a reading from this fleet",
        /*
         * NULL, and for this row null is stronger than it looks: nobody has loaded this model on ANY
         * handset in this fleet, so the question of whether the app survives backgrounding with it
         * resident has not merely gone unanswered, it has not been askable. `false` would invent a
         * failure; `true` would invent a survival.
         */
        survivesBackgrounding = null,
        unmeasuredLanguagesNote = "Google CLAIM 35+ languages out of the box and pre-training in " +
            "140+. None of that has been checked against this app's dictation languages, so what " +
            "this artifact can actually write is unmeasured.",
    ),
    DwModelPlan(
        modelId = "gemma-4-E4B-it.litertlm",
        quantisation = "mixed 2/4/8-bit QAT (Gemma 4 mobile scheme)",
        abi = "arm64-v8a",
        languages = null,
        contextCapTokens = DW_TIER2_MEASURED_CONTEXT_TOKENS,
        runBound = "one answer at a 2,048-token context cap (1,024 tokens in, 256 out), the envelope " +
            "Google's memory figure was taken at; up to 32k is supported and unmeasured.",
        onDiskBytes = 3_659_530_240L,
        // 3283 MiB, CPU backend, same table and same caveats as E2B above. The card's separate
        // speculative-decoding table reports 2800–3116 MiB for the same phone over a different prompt
        // mix; the higher headline figure is the one a verdict is computed from.
        peakRssBytes = 3283L * DW_TIER2_MIB,
        measuredOn = "Galaxy S26 Ultra — Google's published figure, not a reading from this fleet",
        survivesBackgrounding = null,
        unmeasuredLanguagesNote = "Google CLAIM 35+ languages out of the box and pre-training in " +
            "140+. Nothing has been checked against this app's dictation languages.",
    ),
)

/** Google's published GPU-backend figure for [modelId], in bytes, or null if there is none. */
fun dwTier2GpuClaimBytes(modelId: String): Long? = when (modelId) {
    "gemma-4-E2B-it.litertlm" -> 676L * DW_TIER2_MIB
    "gemma-4-E4B-it.litertlm" -> 710L * DW_TIER2_MIB
    else -> null
}

// ---------------------------------------------------------------------------------------------
// The gate
// ---------------------------------------------------------------------------------------------

/**
 * Whether this phone is in a device tier that runs a Tier 2 model at all. **The plan's table, read
 * off the phone's own numbers.**
 *
 * docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md §2.1: a "low-RAM flag set, or < 3 GB" handset gets
 * "**none** — say so plainly", and the rule this app adds is that "plainly" means not offering it. So
 * on top of [DwModelFit.mayInstall] — which lets a designer override a TIGHT verdict — the two DURABLE
 * tight notes rule a row out: Android's own low-memory flag, and a phone whose total memory cannot
 * hold the model with the app's margin however many apps are closed. Those are exactly the two
 * conditions [dwPlanFits] calls [DwTierRefusal.DEVICE_TOO_SMALL]. What stays overridable is the
 * one figure that moves — free memory right now — and a tight storage margin.
 */
fun dwTier2Eligible(choice: DwModelChoice): Boolean =
    choice.fit.mayInstall &&
        DwFitNote.ANDROID_CALLS_THIS_A_LOW_MEMORY_DEVICE !in choice.notes &&
        DwFitNote.LITTLE_MEMORY_LEFT_OVER !in choice.notes

/**
 * Whether a control that would fetch [choice] may be drawn. **The one gate for a Tier 2 download.**
 *
 * [dwTier2Eligible] (the fit verdict and the device tier), a connection, and LiteRT-LM in this
 * package. A phone the measured reading rules out is never offered a 2.6 GB download it could not use.
 */
fun dwTier2InstallMayBeOffered(
    choice: DwModelChoice,
    connection: DwConnection,
    runtimePresent: Boolean = DW_TIER2_RUNTIME_PRESENT,
): Boolean = runtimePresent && dwTier2Eligible(choice) && dwModelDownloadMayBeOffered(choice, connection)

// ---------------------------------------------------------------------------------------------
// The words. Terse: name, size, memory, verdict, one action
// ---------------------------------------------------------------------------------------------

/** The one line above the Tier 2 list. Says what the models are for, and that nothing downloads by itself. */
const val DW_TIER2_LIST_INTRO: String =
    "Language models that run on this phone, for proofreading and translating what is already " +
        "written. Nothing is downloaded unless you ask for it."

/**
 * One judged row, in one sentence: **what it costs, whose figure that is, and what this phone makes
 * of it.**
 *
 * ── WHY THIS IS NOT [dwModelChoiceSentence] ───────────────────────────────────────────────────
 *
 * Two of that function's clauses are about a speech model and would be FALSE under a summariser:
 * [dwModelAccuracyClause] prints *"How accurately it transcribes ANY language is UNMEASURED"* for a
 * plan with no accuracy rows, and [dwModelSpeedClause] prints *"How long it takes to transcribe a
 * recording on this phone is UNMEASURED"* for one with no real-time band. Both are true of the speech
 * model they were written for and neither is a sentence about proofreading. Printing them here would
 * tell a designer this app expects a language model to transcribe.
 *
 * **THE PARTS THAT DECIDE ANYTHING ARE STILL SHARED**, which is the rule this file follows rather than
 * bends: the verdict comes from [dwModelFit], the verdict WORD from [dwModelFitLabel], and the sizes
 * from [dwBytesLabel]. What is local is the length.
 *
 * **THE OVERRIDE CONFIRMATION IS [dwModelOverrideSentence] AND NOT A SHORTER LOCAL COPY.** A TIGHT
 * row's Download asks for it before a byte moves (`DwTier2ModelUi.kt`): the sentence a designer
 * accepts is the one place brevity is the wrong instinct, and it already exists, tested, one file over.
 */
fun dwTier2RowSentence(choice: DwModelChoice, measurement: DwDeviceMeasurement): String = buildString {
    val plan = choice.plan
    /*
     * WORD COUNT IS A CONSTRAINT ON THIS FUNCTION AND NOT AN AESTHETIC. `DwTier2ModelsTest` fails the
     * build over 60 words for a single-note row. The size, the memory figure the verdict was computed
     * from, and this phone's verdict — nothing about who measured what, which is in the catalogue for
     * whoever chooses which model to ship.
     */
    append(dwBytesLabel(plan.onDiskBytes))
    append(" to download. It needs about ")
    append(dwBytesLabel(plan.peakRssBytes))
    append(" of memory while it runs. ")
    append(dwTier2FitClause(choice, measurement))
}

/**
 * This phone's verdict, in one short clause per note. **The numbers, not adjectives.**
 *
 * Deliberately shorter than [dwModelFitSentence], which is 90-odd words on its TIGHT arm and names the
 * camera. That length is right where a designer is about to spend a gigabyte and tap through a
 * confirmation, and it is drawn there — in the confirmation a TIGHT row's Download opens. The row's
 * job is to let somebody scan the models and see which their phone could take.
 */
fun dwTier2FitClause(choice: DwModelChoice, measurement: DwDeviceMeasurement): String = when (choice.fit) {
    DwModelFit.COMFORTABLE -> buildString {
        append("This phone is comfortable with it")
        choice.freeRamHeadroomBytes?.let {
            append(" — ")
            append(dwBytesLabel(it))
            append(" of memory would be spare")
        }
        append(".")
    }

    DwModelFit.TIGHT -> buildString {
        append("Tight on this phone: ")
        append(choice.notes.joinToString(" ") { dwTier2NoteClause(it, choice, measurement) })
        append(" It would still run; the choice is yours.")
    }

    DwModelFit.WILL_NOT_FIT -> buildString {
        append("It cannot run on this phone: ")
        append(choice.notes.joinToString(" ") { dwTier2NoteClause(it, choice, measurement) })
    }

    DwModelFit.UNMEASURED -> buildString {
        append(choice.notes.joinToString(" ") { dwTier2NoteClause(it, choice, measurement) })
        append(" So this phone cannot be judged against it — tap “Check again”.")
    }
}

/** One note, one clause, each naming the figure behind it. */
private fun dwTier2NoteClause(
    note: DwFitNote,
    choice: DwModelChoice,
    measurement: DwDeviceMeasurement,
): String = when (note) {
    DwFitNote.LARGER_THAN_THIS_PHONE_S_MEMORY ->
        "it needs more memory than this phone has in total (${dwBytesLabel(measurement.totalRamBytes)})."

    DwFitNote.LARGER_THAN_THE_FREE_STORAGE ->
        "the file is larger than the free storage, so the download could not finish."

    DwFitNote.NO_BUILD_FOR_THIS_PROCESSOR ->
        "there is no build of the runtime for this phone's processor (it is ${choice.plan.abi} only)."

    DwFitNote.LOAD_FAILED_HERE_BEFORE ->
        "it was tried on this phone and would not load."

    DwFitNote.PROCESSOR_UNMEASURED -> "this phone would not say what processor it has."
    DwFitNote.TOTAL_MEMORY_UNMEASURED -> "this phone would not say how much memory it has."
    DwFitNote.FREE_STORAGE_UNMEASURED -> "this phone would not say how much storage is free."
    DwFitNote.FREE_MEMORY_UNMEASURED -> "this phone would not say how much memory is free."

    DwFitNote.ANDROID_CALLS_THIS_A_LOW_MEMORY_DEVICE ->
        "Android flags this handset as a low-memory device."

    DwFitNote.LITTLE_MEMORY_LEFT_OVER ->
        "this phone has ${dwBytesLabel(measurement.totalRamBytes)} in total, so even with " +
            "everything closed there is less than the ${dwBytesLabel(DW_MODEL_FREE_RAM_MARGIN_BYTES)} " +
            "spare this app keeps."

    DwFitNote.LITTLE_FREE_MEMORY_RIGHT_NOW ->
        "${dwBytesLabel(measurement.availableRamBytes)} is free right now; closing apps changes that, " +
            "and it is the one figure here that moves."

    DwFitNote.LITTLE_STORAGE_LEFT_OVER ->
        "it would leave ${dwBytesLabel(choice.freeStorageHeadroomBytes)} of storage free, under the " +
            "${dwBytesLabel(DW_MODEL_FREE_STORAGE_MARGIN_BYTES)} this app keeps for a workshop day."
}

// ---------------------------------------------------------------------------------------------
// Sideloading, through the same check and not around it
// ---------------------------------------------------------------------------------------------

/** What a file on the phone turned out to be, compared with what this build pinned for it. */
enum class DwTier2FileVerdict {
    /** Right size, right digest. The only value that may be followed by a load. */
    VERIFIED,

    /** The file is not there. Not a failure — nothing has been installed. */
    ABSENT,

    /** It is there and the wrong size, so the digest was not even computed. */
    WRONG_SIZE,

    /** Right size, wrong bytes. **The one that matters**: a substituted model of the same length. */
    WRONG_DIGEST,

    /** This build pins no artifact under that id, so there is nothing to compare against. */
    NOT_PINNED,
}

/**
 * Judge a file that arrived on the phone — **whether it came down the wire or off an adb cable.**
 *
 * ONE CHECK FOR BOTH ROUTES, which is the point of the function existing at all. The brief's rule for
 * the sideload path is that it must go *"through the SAME verify — not a backdoor that skips checks"*,
 * and the way to guarantee that is to have one predicate that neither route can get past without a
 * matching size and digest. `docs/TIER2-LANGUAGE-MODEL-MEASUREMENT.md` carries the `adb push`
 * commands; they land the file where the download would have landed it, and this is what looks at it.
 *
 * [actualSha256] is compared case-insensitively because `sha256sum` on a handset and `Get-FileHash` on
 * Windows disagree about case and agree about bytes.
 */
fun dwTier2VerifyFile(
    modelId: String,
    actualBytes: Long?,
    actualSha256: String?,
    catalogue: List<DwTier2Artifact> = DW_TIER2_ARTIFACTS,
): DwTier2FileVerdict {
    val pinned = dwTier2ArtifactFor(modelId, catalogue) ?: return DwTier2FileVerdict.NOT_PINNED
    if (actualBytes == null) return DwTier2FileVerdict.ABSENT
    if (actualBytes != pinned.bytes) return DwTier2FileVerdict.WRONG_SIZE
    // A missing digest on a present file is NOT a pass. The size matching is the cheap half and the
    // one a substitution defeats trivially; there is no arrangement in which this app opens a
    // multi-gigabyte model because hashing it was inconvenient.
    if (actualSha256.isNullOrBlank()) return DwTier2FileVerdict.WRONG_DIGEST
    return if (actualSha256.equals(pinned.sha256, ignoreCase = true)) {
        DwTier2FileVerdict.VERIFIED
    } else {
        DwTier2FileVerdict.WRONG_DIGEST
    }
}
