package com.designprototype.workshop.data

import java.util.Locale

/**
 * THE SPEECH MODEL — THE FILE THAT GIVES THE ENGINE A VOICE, PINNED BY DIGEST.
 *
 * `DwAsrRuntime.kt` describes an engine that arrives **without a voice**: `ASR-RUNTIME-MEASUREMENT.md`
 * §3 read the AAR's own central directory and found no `assets/` entry at all, so 39.8 MB of
 * sherpa-onnx can transcribe precisely nothing until a model sits beside it. This file is that model,
 * and it is the thing [DwAsrOffer.NO_MODEL_TO_FEED_IT] has been waiting for since that offer was
 * written.
 *
 * ── WHICH MODEL, AND WHY NOT THE ONE THE PLAN NAMED ───────────────────────────────────────────
 *
 * Plan §2.2 named **AI4Bharat IndicConformer**, because it is the only family that promises offline
 * Odia — the language of the state these workshops are run in.
 *
 * **CORRECTED 2026-08-13. THE PARAGRAPH THAT USED TO STAND HERE SAID INDICCONFORMER WAS "NOT
 * AVAILABLE IN A FORM THIS APP MAY SHIP". THAT WAS WRONG, AND IT WAS WRONG BY OMISSION.** The search
 * it recorded looked at the older per-language `.nemo` checkpoints and at the `k2-fsa` release index,
 * and it never found `ai4bharat/indic-conformer-600m-multilingual` — an MIT-licensed repo that
 * publishes **ONNX exports** of a 600M Conformer covering all 22 scheduled languages. What is
 * measured, by building it and running it rather than by reasoning about it:
 *
 *  * **sherpa-onnx loads it.** The published export is `encoder.onnx` (`audio_signal[B,80,T]`,
 *    `length[B]` → `outputs[B,1024,T']`) plus a two-node `ctc_decoder.onnx` (Conv 1×1 with weight
 *    `[5633,1024,1]`, then Transpose → `logprobs[B,T',5633]`). Concatenated, that is **exactly** the
 *    NeMo-CTC contract `OfflineRecognizer.from_nemo_ctc` expects. Merged and loaded on sherpa-onnx
 *    **1.13.5 — the same version vendored in this APK** — it decoded real audio. No re-export from a
 *    `.nemo` checkpoint was needed, and no third-party repackage was involved.
 *  * ~~**The old note's "one Malayalam-only" conversion was never Malayalam-only.** That artifact emits
 *    **5633** classes and ships a **5633-line** `tokens.txt` spanning all 22 languages; it was read as
 *    monolingual because its model card names one.~~ **THAT CORRECTION WAS ITSELF WRONG, AND IT IS
 *    RETRACTED HERE THE SAME DAY IT WAS WRITTEN.** It compared two token tables and inferred a
 *    capability from the wider one. Handed the same Odia and Hindi FLEURS clips the official model
 *    scores 16.7% and 20.9% WER on, `jeswinjestin/sherpa-onnx-nemo-ctc-indicconformer-malayalam`
 *    returns fluent **Malayalam script** — `ହାତୀ ଓ ଜିରାଫ` came back as `ഹത്തിയോ ജിരഹ്` — at **100%
 *    WER on all six utterances.** Its CTC head is Malayalam-locked whatever its `tokens.txt` spans, so
 *    the original note's *"none of the third-party conversions serves Odia"* was RIGHT about this file
 *    and the retraction of it was wrong. **The mechanism of the second error is the same one this file
 *    already has a rule for**: a vocabulary that can spell a script is NECESSARY for emitting it and
 *    not SUFFICIENT — and the same trap catches a token table read as a language list. The only way to
 *    find out what a model hears is to give it audio.
 *  * **Language selection is a mask, not a model.** `assets/language_masks.json` gives each language a
 *    boolean mask over the shared 5633-class space selecting **exactly 257** columns: a contiguous
 *    256-wide block plus the one shared blank at index 5632. 22 × 256 + 1 = 5633 exactly, disjoint.
 *    So the 22 languages are **one artifact**, and the whole per-language cost is the sliced head.
 *
 * **Odia is block 14** (columns 3584–3839), which settles the row the old note called impossible.
 *
 * **IndicConformer is also the more accurate model, measured on the same audio as the one pinned
 * below.** Greedy CTC, fp32: **Odia CER 5.1%, WER 16.7%**; **Hindi CER 6.9%, WER 20.9%**. Scored on
 * identical references through one normaliser, Odia goes **WER 52.8% → 13.9%** and Hindi
 * **24.4% → 20.9%** — so **Odia error falls by a factor of ~3.8**, in the language of the state these
 * workshops are run in. It is not slow either: **RTF ≈ 0.22**, five times faster than real time.
 *
 * The model pinned below is **still** Meta's Omnilingual ASR CTC 300M, and the reason is now a
 * MEASURED one, is narrower than "availability", and is **not accuracy or speed — it is memory.** The
 * IndicConformer encoder is **2,428,824,576 bytes of fp32 external weight data**, and the fleet's own
 * SM-M325F reports **`MemAvailable` 1,340,412 kB** (re-read the next morning: 1,058,148 kB — it moves,
 * and it moves the wrong way). The weights do not fit in that by a factor of ~1.8, so at fp32 this model
 * **cannot load on the handset at all** — storage is not the issue, the phone has 37 GB free.
 *
 * **AND `quantize_dynamic` OF THIS 600M GRAPH WAS MEASURED ON 2026-08-13 AND IT DOES NOT WORK.** (This
 * heading read *"int8 does not work"* until 06:20 the same day, unscoped, and that is a claim about a
 * quantisation format where the measurement is about one script applied to one graph. An int8
 * IndicConformer transcribes fine — see the end of this block. Corrected rather than softened, because
 * the unscoped version is the sentence that would stop the next lane trying the thing that works.) The
 * paragraph before it called int8 "the one remaining question", which was right, and guessed at
 * ~600 MB and ~2 GB resident. `quantize_dynamic` was run twice over the same Odia-sliced graph, on a
 * quiet box, and both products load and then decode nothing usable — against the fp32 graph's CER 5.1 /
 * WER 16.7 on the same three utterances:
 *
 *  * the default op set gives **654,790,526 bytes** and decodes the **empty string**, all three;
 *  * `op_types_to_quantize = ["MatMul"]` — tried because the first run logged *"Inference failed or
 *    unsupported type to quantize"* for every depthwise-conv slice in the Conformer's convolution
 *    modules — gives **883,021,360 bytes**, which is **larger**, and decodes one character, `ପ`, all
 *    three. Decoding also got slower: RTF 0.26–0.33 against 0.20–0.24 at fp32.
 *
 * **So the 600M is refused on two independent measurements rather than one**, and no `DwAsrModel` row
 * for it exists because there is no artifact for a row to describe. The route to offline Odia is the
 * official **120M** per-language checkpoint put through NeMo's own exporter — which is a task now, not a
 * search. See `docs/ASR-RUNTIME-MEASUREMENT.md` for every byte-exact number.
 *
 * ── AND THE 120M SHAPE IS PROVEN ON THE HANDSET, WHICH IS WHY THAT ROUTE IS WORTH THE WORK ─────
 *
 * `DwAsrIndicProbeTest` loaded a 120M IndicConformer on the fleet's own SM-M325F on 2026-08-13 through
 * [DwAsrModelFamily.NEMO_ENC_DEC_CTC] — the branch this file's `family` field now selects — and decoded
 * real speech with it. **493,060,445 bytes fp32, load 6,017 ms, peak `VmHWM` 884,117,504, RTF 0.437**,
 * transcript byte-identical to the desktop's. Reproduced 2026-08-13 06:05 by a second lane: load 5,598 ms,
 * peak `VmHWM` 878,559,232, RTF 0.442, same transcript. **What that artifact was NOT is multilingual** —
 * its head is Malayalam-locked, as the retraction above records — so it proves the shape and cannot be
 * pinned here.
 *
 * ~~The ratio is the useful part: **1.79× file-to-RSS against the Omnilingual int8 row's 3.4×**, so an
 * int8 120M at the ~138 MB upstream publishes should sit near 250–350 MB resident.~~ **THAT PREDICTION
 * WAS RETRACTED AT 06:15 THE SAME DAY BY MEASURING IT, AND AN int8 IndicConformer DOES TRANSCRIBE.**
 * `OpenVoiceOS/ai4bharat-indicconformer-hi-onnx` `model.int8.onnx` — **137,677,313 bytes**, an int8
 * export of `ai4bharat/indicconformer_stt_hi_hybrid_ctc_rnnt_large` — loads on sherpa-onnx 1.13.5 and
 * scores **CER 5.6 / WER 19.8** on the three Hindi FLEURS utterances the 600M fp32 scores 6.9 / 20.9 on,
 * i.e. **better, at 1/18th the file**. Run through this same probe on the same handset it costs
 * **538,144,768 bytes peak `VmHWM` (513 MiB)**, load 3,378 ms, RTF 0.566 — **not 250–350 MB.** The
 * prediction failed for a reason worth carrying: `VmHWM` is a **whole-process** figure and the process
 * floor is **231,321,600 bytes before any model is opened**, so a ratio taken over the file size cannot
 * be rescaled to a smaller file — 1.79 × 137,677,431 = 246,442,601, which leaves **15 MB above an empty
 * process** to hold 138 MB of weights. Floor plus weights alone is **368,999,031**, past the middle of
 * the band that was predicted. Measured int8 ratio on this handset: **3.91×** of file (**2.23×** with the
 * floor subtracted), worse than the Omnilingual int8's 3.44×, which is the direction the dequantisation
 * argument predicted all along. **Nothing here becomes a row**: that artifact is a
 * third-party repackage serving one language, and it is the language the pinned model already serves.
 * What it establishes is the SIZE a 120M costs on this phone, and that number is now measured.
 *
 * ── WHAT IS MEASURED ABOUT ITS LANGUAGES, AND WHAT IS ONLY CLAIMED ───────────────────────────
 *
 * The distinction matters more here than anywhere else in this file, because a designer in Odisha who
 * installs an engine that cannot hear them is the exact failure the whole feature exists to prevent.
 *
 *  * **CLAIMED, by Meta, and not verified here:** 1,600+ languages. That is a statement about a
 *    family and this app does not repeat it as a property of this artifact.
 *  * **MEASURED, off this artifact's own `tokens.txt`:** 9,812 tokens, of which **70 contain
 *    characters in the Odia block (U+0B00–U+0B7F)**, 87 Devanagari, 72 Bengali, 73 Malayalam,
 *    73 Gujarati, 66 Telugu, 62 Kannada, 55 Gurmukhi, 49 Tamil and 155 Arabic. **Re-counted off the
 *    published `tokens.txt` on 2026-08-13: every one of those ten figures is exact.** Two more, for
 *    the two scripts in this app's nineteen that nobody had counted: **32 Ol Chiki** and **ZERO Meetei
 *    Mayek**. A vocabulary that can spell a script is a NECESSARY condition for emitting it and not a
 *    sufficient one — it says the model is able to write Odia, not that it can hear it.
 *  * **AND THE OTHER DIRECTION IS A PROOF RATHER THAN AN ARGUMENT, MEASURED 2026-08-13.** The
 *    necessary-not-sufficient sentence above used to be reasoning; it is now a reading of this file.
 *    Handed three Urdu FLEURS utterances on the fleet's own handset, this artifact emitted **no Arabic
 *    character at all** and answered in fluent **Devanagari** — `آپ اہرام کو تاریکی میں دیکھ سکتے ہیں`
 *    came back as `आप अहराम को तारीखी में देख सकते हैं`, three times out of three, **WER 100%**. It
 *    HEARD the sentence and wrote it in the other language's alphabet. So the 155 Arabic tokens buy
 *    Urdu nothing, and the zero Meetei Mayek tokens let Manipuri be denied without audio: a script the
 *    vocabulary cannot spell is the one case where the weaker fact settles the question, and it settles
 *    it as a NO.
 *  * **MEASURED, on the fleet's own Galaxy M32, by `DwAsrEngineProbeTest`:** what it actually
 *    transcribed from real speech in **eleven** of this app's nineteen languages — Hindi, Odia,
 *    Bengali, Gujarati, Kannada, Malayalam, Nepali, Punjabi, Tamil, Telugu and Urdu — printed verbatim
 *    beside the reference transcripts in `docs/DEVICE-TIER-MEASUREMENT.md`. That reading — and only
 *    that reading — is what [DwModelPlan.languages] is filled from, and it is why ten of those eleven
 *    are scored in [DwModelPlan.accuracy] and absent from it.
 *
 * ── WHY THE DIGESTS ARE HERE AND WHAT THEY DO AND DO NOT ESTABLISH ───────────────────────────
 *
 * Same rule, same reason, same limits as [DwAsrArtifact]: the digest is a constant compiled into the
 * APK and signed with it, it is checked against the file **on disk** before anything opens it, and it
 * **is not a signature**. It binds these bytes to what the release builder pinned and says nothing
 * about upstream provenance. An ONNX file is data rather than code, so the exposure is narrower than
 * a `.so`'s — but it is data fed to a native graph executor, which is not a safe place to put bytes
 * nobody checked either.
 */

// ---------------------------------------------------------------------------------------------
// Which kind of graph it is, and which head answers for which language
// ---------------------------------------------------------------------------------------------

/**
 * **WHICH SHERPA-ONNX MODEL CONTRACT OPENS THIS GRAPH.** Not a label — the loader branches on it.
 *
 * `OfflineModelConfig` carries seventeen mutually exclusive sub-configs and the engine picks its
 * decoder from whichever one is populated. `OfflineOmnilingualAsrCtcModelConfig` and
 * `OfflineNemoEncDecCtcModelConfig` are two of them, both are CTC, and **they are not
 * interchangeable**: handing an IndicConformer graph to the omnilingual config produces a load
 * failure at best and a wrong feature pipeline at worst.
 *
 * The loader used to name one of them as a literal, which was correct while exactly one model was
 * pinned and becomes a silent mismatch the moment a second family is. So the catalogue says which,
 * and `DwAsrSpeechModel` reads it rather than deciding.
 */
enum class DwAsrModelFamily {
    /**
     * Meta's Omnilingual ASR CTC export — `OfflineOmnilingualAsrCtcModelConfig`. What is pinned today.
     */
    OMNILINGUAL_ASR_CTC,

    /**
     * A NeMo `EncDecCTC`-shaped graph — `OfflineNemoEncDecCtcModelConfig`. **What IndicConformer is.**
     *
     * `audio_signal[B,80,T]` + `length[B]` → `logprobs[B,T',V]`, which is what
     * `ai4bharat/indic-conformer-600m-multilingual`'s `encoder.onnx` becomes once its two-node
     * `ctc_decoder.onnx` is appended. Verified against the vendored AAR: the constructor
     * `OfflineNemoEncDecCtcModelConfig(String)` and the `OfflineModelConfig.nemo` property are both
     * present in `sherpa-onnx-static-link-onnxruntime-1.13.5.aar`'s `classes.jar`.
     */
    NEMO_ENC_DEC_CTC,
}

/**
 * **ONE (GRAPH, VOCABULARY) PAIR AND THE LANGUAGE IT ANSWERS FOR. THE MECHANISM OF LANGUAGE
 * SELECTION, AND IT IS NOT A RUNTIME PARAMETER.**
 *
 * ── WHY A MODEL NEEDS MORE THAN ONE OF THESE ──────────────────────────────────────────────────
 *
 * IndicConformer's 22 languages share **one** encoder and differ only in a CTC head: the reference
 * implementation keeps all 5,633 classes in the graph and applies `assets/language_masks.json` — a
 * boolean mask selecting exactly 257 columns, a contiguous 256-wide block plus the one shared blank
 * at 5632 — at decode time. **sherpa-onnx has no such mask.** `OfflineRecognizer` reads a graph and a
 * `tokens.txt` and takes the argmax over everything the graph emits.
 *
 * **UNMASKED IT DOES NOT FAIL, WHICH IS WHY THIS IS A TYPE AND NOT A COMMENT.** Decoded over all
 * 5,633 classes the model is acoustically right and spells the answer in *mixed scripts* — a Malayalam
 * clip returned `হाாய় ನमस्स्କାରారంം இது ஒரு ডెमो…`, drawing tokens from six scripts at once. That is a
 * transcript a designer would read as gibberish and a field would store as data.
 *
 * So the mask is applied **when the artifact is built**, by slicing the head's Conv weight to that
 * language's 257 rows and writing the matching 257-line vocabulary — one small graph per language over
 * one shared weight blob. Selecting a language is therefore **choosing which file to open**, and that
 * is the fact this class exists to make expressible.
 *
 * ── [languageTag] NULL, AND WHY IT IS NOT A SPECIAL CASE BOLTED ON ────────────────────────────
 *
 * A single-head model — the Omnilingual export pinned today — has one graph that answers for every
 * language the model serves. `null` says exactly that, and it is the same distinction
 * [DwModelPlan.languages] draws between `null` and `emptyList()`: the absence of a per-language split
 * is a fact about the artifact, not a missing value. [DwAsrModel.headFor] falls back to it, so a
 * single-head model needs no per-language rows and a per-language model needs no catch-all.
 */
data class DwAsrModelHead(
    /**
     * The BCP-47 tag this head was sliced for, or null when one head answers for every language.
     *
     * Compared through [dwTagCovers], as [DwModelPlan.languages] is, so a head written `or` serves
     * `or-IN` and a head written `en-US` does not serve `en-IN`.
     */
    val languageTag: String?,
    /** The graph file for this head. A bare name inside the model's own directory. */
    val graphFileName: String,
    /**
     * The vocabulary file for this head. **A bare name, and it must be this head's own.**
     *
     * Not shared with another head unless the tables are genuinely identical: a 257-line sliced table
     * against a 5,633-class graph decodes to confident nonsense rather than to an error, which is the
     * failure [DwAsrModelFile]'s per-file digest already exists to catch one level down.
     */
    val tokensFileName: String,
) {
    init {
        require(languageTag == null || languageTag.isNotBlank()) {
            "A head's language tag is a real tag or null. Null means “this one head answers for " +
                "every language the model serves”; a blank string is neither and would match nothing " +
                "while looking like an answer."
        }
        listOf(graphFileName, tokensFileName).forEach { name ->
            require(name.isNotBlank() && '/' !in name && '\\' !in name && ".." !in name) {
                "A head names files inside this app's own internal storage, so “$name” has to be a " +
                    "bare file name. The check is [DwAsrModelFile]'s and it is here for the same " +
                    "reason: a row copied carelessly from somewhere could otherwise address " +
                    "../databases/workshop.db."
            }
        }
        require(graphFileName != tokensFileName) {
            "A head's graph and vocabulary are two different files. One name for both means whichever " +
                "the loader opened second would be parsed as the wrong format."
        }
    }
}

// ---------------------------------------------------------------------------------------------
// One file of a model, pinned
// ---------------------------------------------------------------------------------------------

/**
 * ONE FILE A MODEL IS MADE OF, WITH ITS OWN DIGEST AND ITS OWN SIZE.
 *
 * A model is not one file — this one is a graph and a vocabulary, and a wrong vocabulary against a
 * right graph produces confident nonsense rather than an error. So each is pinned separately and each
 * is hashed before the model is opened, for the same reason [DwAsrLibrary] pins each `.so`.
 *
 * [fileName] is a BARE NAME and is checked to be one. It addresses a file inside this app's own
 * internal storage, and the check is the same one [DwAsrLibrary] carries: a name containing a
 * separator or `..` would let a catalogue row — or a future row copied carelessly from somewhere —
 * put bytes outside the one directory this feature owns.
 */
data class DwAsrModelFile(
    /** e.g. `model.int8.onnx`. A file name, never a path. */
    val fileName: String,
    /** Lower-case hex SHA-256 of this exact file, as the release builder measured it. */
    val sha256: String,
    /** Its size on disk, for the storage arithmetic and for the sentence a designer reads. */
    val bytes: Long,
) {
    init {
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName && ".." !in fileName) {
            "A model file needs a bare file name: “$fileName” is not one. These names address files " +
                "inside this app's own internal storage, and an entry like ../databases/workshop.db " +
                "would put fetched bytes over this app's own database."
        }
        require(dwAsrIsSha256(sha256)) {
            "Every model file needs the full 64-character hex SHA-256 of the exact file that will " +
                "sit on the phone. Take it with `sha256sum` on the file you published, not on the " +
                "one you built from — and never leave it blank, because dwAsrVerify answers " +
                "NO_PINNED_DIGEST for a blank and that refusal is the guard, not a nuisance."
        }
        require(bytes > 0L) {
            "Every model file needs its real size on disk. The figures are added up to decide " +
                "whether the model fits with a workshop day's photographs left over, and a zero " +
                "would let an install be offered on a phone with no room for it."
        }
    }
}

/**
 * ONE PUBLISHED SPEECH MODEL, PINNED. **The second trust boundary of this feature.**
 *
 * Deliberately NOT carrying an ABI, and the absence is a fact rather than an oversight: an ONNX graph
 * is architecture-independent — the same `model.int8.onnx` runs under the arm64 engine and the
 * armeabi-v7a one. What is per-ABI is the ENGINE, and that is [DW_ASR_ENGINE_ABIS]. Giving this class
 * an `abi` field would have invited a future reader to publish the same 365 MB file twice.
 */
data class DwAsrModel(
    /** The exact artifact, not the family: the directory name of the upstream export. */
    val modelId: String,
    /** `int8`, `fp32`, … — the thing that actually decides the footprint. */
    val quantisation: String,
    /** Which sherpa-onnx contract opens the graph. **The loader branches on this.** */
    val family: DwAsrModelFamily,
    /**
     * Every file the model is made of, each pinned. All of them are hashed before any is opened.
     *
     * **A per-language model lists every language's graph and vocabulary here, plus the shared weight
     * blob**, and all of them are hashed before any is opened. That is deliberately more work than
     * hashing only the head about to be used: the digest gate is a check on the INSTALL, and an
     * install that wrote 22 heads of which 21 are wrong is a broken install whichever one is opened
     * first. See [DwAsrModelStatus] — "installed" means every pinned file matched.
     */
    val files: List<DwAsrModelFile>,
    /**
     * **WHICH GRAPH AND VOCABULARY ANSWER FOR WHICH LANGUAGE.** At least one, and see [DwAsrModelHead]
     * for why language selection is a file and not a parameter.
     */
    val heads: List<DwAsrModelHead>,
    /** The upstream release these files came out of. Audit trail, as [DwAsrArtifact.upstreamVersion]. */
    val upstreamVersion: String,
    /** How the release builder obtained them, in a sentence. The only record of what they believed. */
    val provenance: String,
    /**
     * WHAT IS KNOWN ABOUT THIS ARTIFACT'S LANGUAGES, **separating the claimed from the measured**.
     *
     * A sentence rather than a list, because the list of tags this app will act on lives in
     * [DwModelPlan.languages] and is filled only from a handset reading. This field is the prose a
     * human reads when they want to know how much of that list is evidence.
     */
    val languageNote: String,
) {
    /** What the model costs the phone permanently. Derived, so it cannot disagree with the list. */
    val onDiskBytes: Long get() = files.sumOf { it.bytes }

    /**
     * The head that answers for [tag], or null when nothing in this artifact does.
     *
     * A per-language head wins over the catch-all, and the comparison is [dwTagCovers] rather than
     * `==` for [DwModelPlan.languages]'s reason. **Null is a refusal and not a fallback**: opening some
     * other language's head because this one is absent would hand a designer a transcript in a script
     * they did not speak, which is exactly what the unmasked decode does and exactly what the
     * per-language split exists to prevent.
     */
    fun headFor(tag: String): DwAsrModelHead? =
        heads.firstOrNull { it.languageTag != null && dwTagCovers(it.languageTag, tag) }
            ?: heads.firstOrNull { it.languageTag == null }

    init {
        require(modelId.isNotBlank() && '/' !in modelId && '\\' !in modelId && ".." !in modelId) {
            "A model needs an id that is also a safe directory name — it names a directory inside " +
                "this app's internal storage. “$modelId” is not one."
        }
        require(quantisation.isNotBlank()) {
            "A model needs its quantisation. int8 and fp32 of the same weights are different " +
                "artifacts with different footprints, and this one is 365 MB against 1.2 GB."
        }
        require(files.isNotEmpty()) {
            "A model needs the files it is made of, each with its own digest. A graph with no " +
                "vocabulary decodes to confident nonsense rather than to an error, so both are pinned."
        }
        require(files.map { it.fileName }.distinct().size == files.size) {
            "Two files in one model cannot share a name: they would be written to the same path, " +
                "one would silently replace the other, and the digest check would then fail on " +
                "whichever lost — reporting a substituted file where the fault is a duplicated row."
        }
        require(upstreamVersion.isNotBlank()) {
            "A model needs the upstream release it came out of, so a defect found later can be " +
                "traced to a version rather than to “the model”."
        }
        require(provenance.isNotBlank()) {
            "A model needs a sentence saying how the release builder obtained it. The digest proves " +
                "only that the file is the one they pinned; this is the only record of what they " +
                "believed they were pinning."
        }
        require(languageNote.isNotBlank()) {
            "A model needs its language note. A model is not a capability, it is a capability in " +
                "some languages, and the one thing this app must never do is let “offline " +
                "dictation” imply nineteen when the artifact serves fewer."
        }
        require(heads.isNotEmpty()) {
            "A model needs at least one head — the graph and the vocabulary that get opened. A " +
                "single-head export takes one row with languageTag = null; a model whose languages " +
                "are separate sliced heads takes one row per language."
        }
        require(heads.count { it.languageTag == null } <= 1) {
            "A model has at most ONE catch-all head. Two rows both claiming to answer for every " +
                "language means dwAsrModel.headFor returns whichever was written first, and which " +
                "graph a designer's dictation opened would be decided by the order of two lines."
        }
        require(
            heads.mapNotNull { it.languageTag }.map { dwNormalizeLanguageTag(it) }.distinct().size ==
                heads.count { it.languageTag != null }
        ) {
            "Each language gets one head. Two heads for one tag (or-IN and or_IN are the same tag to " +
                "every comparison in this app) would make the graph a designer decodes with a coin toss."
        }
        /*
         * EVERY FILE A HEAD NAMES HAS TO BE A PINNED FILE, and this is the check that makes the digest
         * gate cover the thing that actually gets opened. Without it a head could name
         * `model-or.onnx` while `files` pinned only `model-hi.onnx`: the install would verify, report
         * INSTALLED, and the loader would then open an unpinned path — or no path at all — with the
         * app's own status saying every byte had been checked.
         */
        val pinned = files.map { it.fileName }.toSet()
        heads.forEach { head ->
            require(head.graphFileName in pinned) {
                "The head for ${head.languageTag ?: "every language"} opens " +
                    "“${head.graphFileName}”, which is not one of this model's pinned files. Add it " +
                    "to `files` with its own digest and size: a file the loader opens and the " +
                    "verifier never hashed is the one hole this whole arrangement exists to close."
            }
            require(head.tokensFileName in pinned) {
                "The head for ${head.languageTag ?: "every language"} reads " +
                    "“${head.tokensFileName}”, which is not one of this model's pinned files. A " +
                    "substituted vocabulary against a correct graph produces a fluent transcript in " +
                    "the wrong alphabet rather than an error, and nobody would check it against the " +
                    "audio."
            }
        }
    }
}

/**
 * THE SPEECH MODELS THIS BUILD PINS. **One row, and it is the first this repository has ever had.**
 *
 * Every field below came off bytes that were downloaded and hashed on 2026-08-12, not off a model
 * card. The digests are reproducible from the published artifact:
 *
 *     curl -L -O https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/\
 *     sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2
 *     sha256sum sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2
 *     # cdcd0559c7c73efed54209a926e321afc914d046c5fdbf3665f00dc78180e5ed  (292,571,207 bytes)
 *     tar xjf … && sha256sum model.int8.onnx tokens.txt
 *
 * **THE TARBALL'S OWN DIGEST IS RECORDED IN THAT COMMENT AND NOT IN A FIELD**, deliberately. It is the
 * container, and the container is not what gets opened — the two files unpacked out of it are, and
 * those are what [DwAsrModelFile] pins. It is written down because it is the one digest a reader can
 * check against the published artifact without unpacking anything, which makes it the fastest way to
 * find out that the row below describes a different file from the one upstream is serving today.
 */
val DW_ASR_MODELS: List<DwAsrModel> = listOf(
    DwAsrModel(
        modelId = "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
        quantisation = "int8",
        family = DwAsrModelFamily.OMNILINGUAL_ASR_CTC,
        files = listOf(
            DwAsrModelFile(
                fileName = "model.int8.onnx",
                sha256 = "e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c",
                bytes = 365_352_120L,
            ),
            DwAsrModelFile(
                fileName = "tokens.txt",
                sha256 = "a7a044c52cb29cbe8b0dc1953e92cefd4ca16b0ed968177b6beab21f9a7d0b31",
                bytes = 86_423L,
            ),
        ),
        /*
         * ONE HEAD, ANSWERING FOR EVERY LANGUAGE THIS ARTIFACT SERVES. `languageTag = null` is the
         * honest description of a single-graph export and not a placeholder: there is no per-language
         * file here to choose between, because the vocabulary is one flat table over every language the
         * upstream model was trained on. Which of them it can actually HEAR is a separate question,
         * answered in `DwModelPlan.languages` off a handset reading and nowhere else.
         */
        heads = listOf(
            DwAsrModelHead(
                languageTag = null,
                graphFileName = "model.int8.onnx",
                tokensFileName = "tokens.txt",
            ),
        ),
        upstreamVersion = "sherpa-onnx asr-models, asset " +
            "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2",
        provenance = "Downloaded 2026-08-12 from the k2-fsa/sherpa-onnx GitHub release tag " +
            "`asr-models` — the same project and the same release index the engine AAR comes from — " +
            "and unpacked locally. The tarball was 292,571,207 bytes and hashed to " +
            "cdcd0559c7c73efed54209a926e321afc914d046c5fdbf3665f00dc78180e5ed. It is sherpa-onnx's " +
            "own ONNX export of Meta's Omnilingual ASR CTC 300M; it was NOT re-quantised, re-exported " +
            "or repacked here. Nothing upstream publishes a signature for it, so this chain " +
            "establishes what was pinned and not who made it.",
        languageNote = "Meta CLAIM 1,600+ languages for this family, which is not verified here and " +
            "is not a property of this artifact. What was measured off this artifact's own " +
            "tokens.txt: 9,812 tokens, of which 70 carry Odia-script characters (U+0B00–U+0B7F), 87 " +
            "Devanagari, 72 Bengali, 73 Malayalam, 73 Gujarati, 66 Telugu, 62 Kannada, 55 Gurmukhi, " +
            "49 Tamil, 155 Arabic, 32 Ol Chiki and ZERO Meetei Mayek — so the model is ABLE to write " +
            "those scripts, which is a necessary condition for serving those languages and not a " +
            "sufficient one. Measured 2026-08-13, that stopped being a caution and became a reading: " +
            "handed Urdu speech this artifact writes DEVANAGARI and not one Arabic character, at 100% " +
            "WER, so the 155 Arabic tokens buy Urdu nothing. The zero works the other way and is the " +
            "one case where the vocabulary settles a language on its own: Manipuri in its own script " +
            "cannot be produced at all. What it can actually hear was measured on a handset for eleven " +
            "of this app's nineteen languages and is recorded in DwModelPlan.languages, " +
            "DwModelPlan.accuracy and docs/DEVICE-TIER-MEASUREMENT.md; nothing else in this app may " +
            "fill that list.",
    ),
)

/**
 * The ABIs the engine in THIS APK was built for. **Read off the AAR, not off a wish.**
 *
 * `sherpa-onnx-static-link-onnxruntime-1.13.5.aar` carries `libsherpa-onnx-jni.so` for all four
 * Android ABIs; the release build's `abiFilters` narrows the packaged set to these two, which is the
 * same pair every other native library in this app ships for and is measured in
 * `docs/ASR-RUNTIME-MEASUREMENT.md`. The two `.so` sizes in that AAR — **23,646,824** for arm64-v8a
 * and **16,152,132** for armeabi-v7a — are byte-for-byte the figures [DW_ASR_ENGINE_BYTES_ARM64] and
 * [DW_ASR_ENGINE_BYTES_ARM32] were derived from by subtracting two packaged APKs, which is a pleasing
 * cross-check of that document's arithmetic from a completely different direction.
 *
 * **ONLY THE FIRST OF THEM HAS BEEN RUN.** `DwAsrEngineProbeTest` was executed on an arm64-v8a
 * handset. Whether the armeabi-v7a build loads, and what it costs, is **unmeasured** — the same word
 * `ASR-RUNTIME-MEASUREMENT.md` §6 uses for it, and for the same reason: nobody has had one in the room.
 */
val DW_ASR_ENGINE_ABIS: List<String> = listOf("arm64-v8a", "armeabi-v7a")

/**
 * Which of this handset's ABIs the engine in this APK was built for, or null if none of them is.
 *
 * The loop is over the HANDSET'S list and not over ours, for the reason [dwAsrArtifactFor] spells
 * out at length: `Build.SUPPORTED_ABIS` is primary-first, so walking it gives a 64-bit phone the
 * 64-bit engine, while walking our list would hand it whichever row happened to be typed first.
 *
 * An empty [abis] returns null and the caller must NOT read that as "no build for this processor" —
 * it is [DwAsrOffer.PROCESSOR_UNMEASURED], a separate answer for a separate fact.
 */
fun dwAsrEngineAbiFor(abis: List<String>): String? {
    abis.forEach { reported ->
        val normalised = reported.trim().lowercase(Locale.ROOT)
        if (DW_ASR_ENGINE_ABIS.any { it.equals(normalised, ignoreCase = true) }) return normalised
    }
    return null
}

/**
 * The model this build would use, or null if it pins none.
 *
 * Takes no ABI on purpose — see [DwAsrModel] for why an ONNX graph has none. A caller that also needs
 * to know whether the engine exists for the handset asks [dwAsrEngineAbiFor], and the two questions
 * stay separate because their answers change for different reasons.
 */
fun dwAsrModel(catalogue: List<DwAsrModel> = DW_ASR_MODELS): DwAsrModel? = catalogue.firstOrNull()
