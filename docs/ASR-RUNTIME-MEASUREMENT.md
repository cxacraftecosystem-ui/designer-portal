# The offline ASR runtime, weighed — and the reason it is not in the build

Measured 2026-08-12, for step 4 of `docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md` §2.2 — *"ship the
runtime with no models"*. Eight real `:app:assembleRelease` runs, sizes read off the packaged APK
with `os.path.getsize` and the entries read out of each APK's own central directory with `zipfile`,
exactly as `docs/R8-MEASUREMENT.md` requires and for the same reason: **an intermediate directory is
not evidence, and `abiFilters` is applied at packaging time.**

**NOTHING WAS COMMITTED. `android/` IS BYTE-FOR-BYTE AS IT WAS FOUND, and that is the finding**, not
an accident of running out of time. The two headline results are independent:

1. **The official sherpa-onnx AAR cannot be resolved from the repositories this build declares.** It
   is on neither `google()` nor `mavenCentral()`, under any spelling. Gradle's exact words are below.
2. **Even if it could be, it costs 39.8 MB of packaged APK for zero capability** until a model lands
   — and it lands on a delivery chain whose update prompt has no "Later" button.

So `DW_TIER1_RUNTIME_PRESENT` in
`android/app/src/main/java/com/designprototype/workshop/data/DwDeviceTier.kt` is **still `false`** —
nothing was bundled, which is what that constant means and all it means.

> **THE SENTENCE THAT FOLLOWED IT HERE IS NOW STALE, and it is corrected rather than deleted because
> the staleness is instructive.** This paragraph went on: *"Tier 1 still answers
> `NO_RUNTIME_IN_THIS_BUILD` on every handset, and `docs/DEVICE-TIER-MEASUREMENT.md` still describes
> the code that exists. None of the three needed changing, because no runtime shipped."* True when
> written, and false within hours — because the measurement in this document is precisely what
> persuaded the user to make the runtime **an install the designer chooses** rather than a bundled
> dependency, so the engine can now be present on a handset without being present in a build. Those
> are two different facts and `dwTier1Offer` now distinguishes them; it used to be a one-line constant
> return and is not any more. See `docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md`.
>
> The shape of this error is the one this repository keeps paying for: **a document asserting what
> some other part of the system does, written from a reading taken at one moment.** It has been found
> five separate times in two days, always in that shape. A measurement — the eight APK figures below —
> does not rot, because it records what happened. A claim about present behaviour rots by default.

---

## 1. The dependency does not resolve. The exact error

Asked through the Gradle resolver itself rather than through a web search, in a throwaway project
declaring **byte-identically** the repository block of `android/settings.gradle.kts` — `google()` and
`mavenCentral()`, `FAIL_ON_PROJECT_REPOS` — and nothing else. Six plausible coordinates, all six
failed. The first, verbatim:

```
Could not resolve all files for configuration ':detachedConfiguration1'.
  caused by: Could not find com.k2-fsa.sherpa.onnx:sherpa-onnx:1.13.5.
Searched in the following locations:
  - https://dl.google.com/dl/android/maven2/com/k2-fsa/sherpa/onnx/sherpa-onnx/1.13.5/sherpa-onnx-1.13.5.pom
  - https://repo.maven.apache.org/maven2/com/k2-fsa/sherpa/onnx/sherpa-onnx/1.13.5/sherpa-onnx-1.13.5.pom
```

and the same shape for `com.k2fsa.sherpa.onnx:sherpa-onnx`, `com.k2-fsa:sherpa-onnx`,
`com.k2fsa:sherpa-onnx`, `k2-fsa:sherpa-onnx` and `com.k2-fsa.sherpa.onnx:sherpa-onnx-android`.

**THIS IS "NOT PUBLISHED", NOT "NO NETWORK", and the distinction is the whole of this section.** Both
repositories were reached and both answered: `https://repo1.maven.org/maven2/` returns 200 from this
machine, and the failures above are 404s from live servers rather than connection errors. Confirmed a
third way, from the other direction, through Maven Central's own search index: `a:sherpa-onnx` returns
`numFound: 0`, and `/maven2/com/k2-fsa/` is 404. Google's Maven has no `com/k2-fsa/group-index.xml`.

**The one thing on Maven Central bearing the name is not the project's own artifact.** A search for
"sherpa-onnx" returns exactly one row: `com.bihe0832.android:lib-sherpa-onnx:6.25.21`, an individual
developer's repackage inside a personal Android library collection. Substituting a third party's
rebuild of a speech engine for the upstream one, in an application that carries government craft
records, is a supply-chain decision belonging to a person and not to this lane. **It was not taken and
should not be taken quietly.** It is written down here so the next reader who finds it in a search
knows it was seen and declined rather than missed.

### Where the artifact actually lives

`k2-fsa/sherpa-onnx` publishes its Android build as a **GitHub release asset**, not to a Maven
repository. The official Android sample in that repository confirms it from the other side: the sample
app's Gradle file, at `SherpaOnnx/app/build.gradle` **within the upstream `k2-fsa/sherpa-onnx` tree
and not within this one**, declares `androidx`, `material` and `constraintlayout` and **no sherpa
dependency at all**; the libraries are copied in by hand.

(The path is written without its leading `android/` deliberately. Spelled in full it is indistinguishable
from a file in *this* checkout, and `docs/tools/check-docs.mjs` resolves every path-shaped string it
finds and reported it as missing — correctly, by its own rules. A checker that reports a path this
repository was never meant to contain is a checker somebody silences, and it is the only mechanical
guard these documents have.)

| release asset (`v1.13.5`) | bytes |
|---|---|
| `sherpa-onnx-1.13.5.aar` (onnxruntime as a separate `.so`) | 49,095,090 |
| `sherpa-onnx-static-link-onnxruntime-1.13.5.aar` | 37,749,854 |

Both were fetched to a scratch directory and measured. **Neither is in `android/` and neither is in
git.** JitPack will serve `com.github.k2-fsa:sherpa-onnx` coordinates, but it is not a repository this
build declares, it builds from source on demand, and whether it can drive this project's CMake/NDK
build **is unmeasured** — it was not tried, because the answer would not change what a committed
dependency has to be.

**Three routes exist and each is somebody's decision, not this lane's:** vendor a 49 MB binary into
the repository; add a build-time download of an unsigned third-party asset (in the one repository
whose entire premise is working without a network — `android/app/build.gradle.kts` already refuses a
JUnit 5 plugin on exactly that ground); or wait for upstream to publish to Maven Central.

---

## 2. What it would cost, measured on the packaged APK

Since the numbers can be had without committing anything, they were.

**How, so the method can be attacked.** `android/` was copied to a scratch directory once, verified
byte-identical with `diff -r` and `cmp`, and **frozen**. Every APK below comes from that one frozen
source, differing from its neighbour in exactly one thing. Re-verified at the end: the copy's
`app/src` is **still** identical to the repository's, so these are measurements of the tree as it
stands at `5ac0ff0`, not of a tree that drifted underneath them. The repository's own
`build.gradle.kts` and `settings.gradle.kts` were never edited — the AAR was reached through a
`flatDir` in the *copy's* settings file — so there is no half-flipped build file to restore and
nothing to trust me about.

`local.properties` carried `debugSignRelease=true` throughout, so signature overhead is identical on
every row. `lintVital*` is excluded — it produces no bytes. Machine was idle with 220 GB free on the
build volume, which is the guard `docs/R8-MEASUREMENT.md` opens with: a Gradle build starved of disk
prints `BUILD SUCCESSFUL` having compiled nothing. **`:app:packageRelease` shows as executed rather
than `UP-TO-DATE` on every one of the eight runs**, which is the thing that actually had to be true.

### The table

| | configuration | APK bytes | size | vs baseline | multiple |
|---|---|---|---|---|---|
| **A** | **today's tree, ARM pair — what ships** | **26,244,416** | 25.03 MiB | — | 1.00× |
| B | today's tree, all four ABIs (`releaseAllAbis=true`) | 49,471,792 | 47.18 MiB | — | — |
| **C** | **+ `sherpa-onnx-1.13.5`, ARM pair** | **79,552,612** | 75.87 MiB | **+53,308,196** | **3.03×** |
| D | + `sherpa-onnx-1.13.5`, all four ABIs | 175,049,828 | 166.94 MiB | +125,578,036 (vs B) | 3.54× |
| **E** | **+ `sherpa-onnx-static-link-onnxruntime-1.13.5`, ARM pair** | **66,056,244** | 63.00 MiB | **+39,811,828** | **2.52×** |
| F | as C, with the unused C/C++ API libraries excluded | 71,163,664 | 67.87 MiB | +44,919,248 | 2.71× |
| G | as E, with `useLegacyPackaging = true` | 32,932,144 | 31.41 MiB | — | — |
| H | today's tree, `useLegacyPackaging = true`, no runtime | 16,276,492 | 15.52 MiB | — | — |

**E is the cheapest honest shape of "the runtime, no models": +39,811,828 bytes, and the app more
than doubles.** For an APK downloaded over prepaid mobile data behind an update dialog with no
"Later" (`MainActivity.kt`, *"required update: cannot be dismissed"*), that is the number the plan's
sequencing argument has to be weighed against.

The baseline A differs from the 26,211,648 recorded in `docs/R8-MEASUREMENT.md` by 32,768 bytes,
because the tree has moved since. **What moved, checked rather than assumed:** the step-1 and step-7
work in the working tree the frozen copy was taken from — `DwDictation.kt` (+659 lines),
`AppearanceScreen.kt`, `WorkshopRepository.kt`, `WorkshopRepositoryApi.kt`, `RichTextEditor.kt`,
`ReportSettings.kt`, `ReportTemplates.kt`, `ReportScreen.kt`, and the six new files
(`DwDictationLadder.kt`, `DwDictationUpload.kt`, `DwDeviceTier.kt`, `DwDeviceProbe.kt` and their
tests) — about +963/−78 lines across eight tracked files plus six untracked ones.

> **`AND A VERSION BUMP` WAS HERE AND WAS FALSE.** An earlier draft of this paragraph named one, and
> there was none: `appVersionName` is `1.1.19` at `979d205` — the commit that recorded 26,211,648 —
> and was still `1.1.19` when this correction was written, with `appVersionCode` derived from it, and
> (the version has since been set to `0.0.1` for the first published release, which is a renumbering
> and not a bump — see `android/app/build.gradle.kts`), and
> `git log 979d205..HEAD -- android/app/build.gradle.kts` shows no change to the version at all. It is
> corrected in place rather than quietly deleted because of where it sat: **this is the one document in
> this repository whose entire subject is not inventing figures**, and it had invented a cause. The
> consequence was not hypothetical either — a reader re-measuring after a *real* version bump would
> have attributed the new drift to the wrong thing, and the 32,768-byte step is exactly the kind of
> number somebody chases for an afternoon.

That is the same staleness `R8-MEASUREMENT.md` predicted of its own 6.09 MB. Every delta above
is internally consistent regardless, because all eight rows share one frozen source. Cross-checked
against it in the one way that cannot drift: the ML Kit libraries in A carry CRC-32 `5a02f1b7` and
`264f5dd0` at 11,064,544 and 6,781,940 bytes — **byte-identical to what that document recorded**, so
this is the same build producing the same artifact.

### The delta per ABI, off the APKs' central directories

| `lib/` | A → C (dynamic onnxruntime) | A → E (static-linked) |
|---|---|---|
| `arm64-v8a` | 11,074,640 → 42,411,296 · **+31,336,656** | 11,074,640 → 34,721,464 · **+23,646,824** |
| `armeabi-v7a` | 6,789,192 → 28,708,884 · **+21,919,692** | 6,789,192 → 22,941,324 · **+16,152,132** |
| `x86` (B → D) | 11,570,332 → 48,257,136 · +36,686,804 | unmeasured |
| `x86_64` (B → D) | 11,636,888 → 47,163,656 · +35,526,768 | unmeasured |
| `*.dex` | 13,324,236 → 13,324,396 · **+160** | 13,324,236 → 13,324,396 · **+160** |
| `assets/` | 1,707,994 → 1,708,024 · +30 | 1,707,994 → 1,708,024 · +30 |
| `resources.arsc` + `res/` | 1,596,336 → 1,596,336 · **+0** | +0 |

Every `lib/` entry reads **STORED**, at `minSdk = 26` and `extractNativeLibs="false"` — read out of
the merged manifest, not recalled — so **its bytes in the APK are also its bytes on the phone.**

### Both branches of the ABI filter, on the packaged APK

The previous lane's whole finding was that this `if` has an `else` and both must be measured off the
real artifact. With the runtime in:

| `local.properties` | APK bytes | ABIs in the built APK |
|---|---|---|
| `releaseAllAbis` absent (clean checkout, CI) | **79,552,612** | `arm64-v8a`, `armeabi-v7a` |
| `releaseAllAbis=true` | **175,049,828** | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |

**The filter is worth 95,497,216 bytes once this runtime is in the build** — up from 23,227,376 with
only ML Kit. The escape hatch is not merely still correct; it becomes the difference between a large
APK and an unshippable one. The same two rows on the baseline (A and B, 26,244,416 and 49,471,792)
show the filter still behaves exactly as `docs/R8-MEASUREMENT.md` recorded, on today's tree.

---

## 3. THE AAR CONTAINS NO MODELS. Read off the artifact, not off the plan

§2.2's design is "ship the runtime with no models", and it holds — but the reason it holds is worth
having in writing, because it is a property of the artifact rather than of anything we would do:

    sherpa-onnx-1.13.5.aar
      jni/arm64-v8a/     libonnxruntime.so 21,684,880 · libsherpa-onnx-jni.so 4,757,872
                         libsherpa-onnx-c-api.so 4,453,168 · libsherpa-onnx-cxx-api.so 440,744
      jni/armeabi-v7a/   libonnxruntime.so 15,027,384 · libsherpa-onnx-jni.so 3,417,220
                         libsherpa-onnx-c-api.so 3,193,060 · libsherpa-onnx-cxx-api.so 282,044
      jni/x86/ jni/x86_64/  … the same four, larger
      classes.jar        238,043
      AndroidManifest.xml 203 · R.txt 0 · proguard.txt 0

**There is no `assets/` entry at all.** Contrast ML Kit, where the surprise recorded in
`docs/R8-MEASUREMENT.md` was 1,272,325 bytes of `assets/mlkit-google-ocr-models/` that the AAR
arithmetic had missed entirely. Here the whole 53 MB is engine. An IndicConformer artifact is a
separate download that step 5 must weigh on its own, and this document says nothing about its size —
**unmeasured**, deliberately, because it is step 5's job to measure it and not this one's to guess.

### Only `libonnxruntime.so` had anything to strip, and it was 24 bytes

CRC-32s of the sherpa libraries in the packaged APK are byte-identical to the AAR (`786f38ef`,
`7f32a32c`, `f0d25ea5`, `03ec13a5`, `487fa68d`, `9152df9f`). `libonnxruntime.so` alone differs —
21,684,880 → 21,684,872 on arm64 and 15,027,384 → 15,027,368 on armeabi-v7a, so
`:app:stripReleaseDebugSymbols` found 8 and 16 bytes and nothing more. As with ML Kit, **there is no
version of this build that makes these bytes smaller.**

---

## 4. R8 DELETED THE ENTIRE KOTLIN BINDING, and that is both the proof and the trap

The dex grew by **160 bytes** for a 238,043-byte `classes.jar`. Read out of
`build/outputs/mapping/release/r8-removed.txt` rather than inferred, using that document's own rule
(a line with no trailing colon is a class removed **entirely**):

- **123 `com.k2fsa.sherpa.onnx` classes removed whole.** `OfflineRecognizer`, `OnlineRecognizer`,
  `KeywordSpotter`, `AudioTagging`, `OfflinePunctuation`, every `Offline*ModelConfig`, all of it.
- **Zero kept with members trimmed** — not one line ending in a colon.
- **Zero occurrences of `k2fsa` in `mapping.txt`**, so nothing survived under a renamed identity
  either.

**This is the dark-code detector `docs/R8-MEASUREMENT.md` recommends keeping as a technique, pointed
at a dependency instead of at our own code, and it gives the cleanest possible confirmation that the
runtime would ship inert:** nothing in the application reaches the binding, so nothing ever calls
`System.loadLibrary`, so the engine cannot start by accident. Inert is not a claim about intent here;
it is a mechanical fact about reachability.

**AND IT IS THE TRAP THE NEXT LANE WALKS INTO.** The shape §2.2 asks for — a runtime present, no
model, no screen offering a language — is exactly the shape in which R8 removes the whole API and
leaves 39.8 MB of native code that no code path can reach. Whoever wires step 5 must expect
`ClassNotFoundException` or `UnsatisfiedLinkError` from a *release* build that a debug build ran
perfectly, which is the same failure mode that section of `R8-MEASUREMENT.md` exists for, and must add
a keep rule to `proguard-rules.pro` for the binding **and** for the JNI entry points the `.so` looks
up by name. A thin "is a runtime present" wrapper does not save them: it either names a sherpa class,
in which case the binding returns to the dex and the answer is a compile-time constant anyway, or it
does not, in which case R8 removes exactly as much as it did here.

---

## 5. `useLegacyPackaging` stops being a footnote at this size

`docs/R8-MEASUREMENT.md` measured this lever at −9.52 MB with ML Kit and left the choice explicitly
to somebody else. With an ASR runtime in the build it is measured again, on the same tree, both ways:

| | download, every install **and every update** | permanent on-device footprint |
|---|---|---|
| E — static-link, as the build packages today | 66,056,244 | **66,056,244** |
| G — the same content, `useLegacyPackaging = true` | **32,932,144** (−33,124,100) | 90,594,932 (+24,538,688) |
| H — no runtime, `useLegacyPackaging = true` | 16,276,492 | 34,140,324 |

`extractNativeLibs` flips to `"true"` in the merged manifest (verified there, not assumed) and every
`lib/` entry becomes DEFLATED: 57,662,788 stored bytes become 24,581,961 compressed. The resident
figure is the APK kept on disk **plus** the libraries the installer unpacks beside it.

**Trading 24.5 MB of storage for 33.1 MB off every single download changes character at this scale**,
on an updater that fetches the whole APK every release over a prepaid bundle. It remains, as that
document says, a decision this lane does not own — and it now has a second, larger measurement behind
it. What is **unmeasured** is whether a legacy-packaged build installs and runs on the M32; nothing
here has been on a handset.

---

## 6. What is unmeasured, in that word

Everything that decides whether this is worth 39.8 MB. Listed so the table's emptiness is not mistaken
for a table with numbers in it.

> **SEVEN OF THESE ELEVEN ROWS WERE ANSWERED ON THE EVENING OF 2026-08-12** and are struck through
> below rather than deleted, because the shape of what was still missing is worth keeping. The
> readings are in `docs/DEVICE-TIER-MEASUREMENT.md`, *"MEASURED 2026-08-12, EVENING — THE ENGINE
> RUNS"*; **not one figure from them is copied into this document**, which is the rule §2 opens with
> and applies to itself. What this section got RIGHT and should be credited for: it refused to guess
> any of them, and every one that came back came back different from what a guess would have been —
> the peak RSS is 1.24 GB where the whole model is 365 MB on disk, and the handset decodes *slower
> than real time*.

| | |
|---|---|
| Does the runtime load on a Galaxy M32? | ~~**unmeasured** — no APK from this lane was installed on anything~~ **YES.** Vendored from the release asset, loaded on the fleet's SM-M325F, transcribed six real utterances in two languages |
| IndicConformer artifact id, quantisation, on-disk size | ~~**STILL unmeasured, and now for a better reason: there is no IndicConformer artifact this app may ship.**~~ **THAT REASON WAS NOT A BETTER ONE — IT WAS A FALSE ONE, RETRACTED 2026-08-13.** The three claims in this cell were each checked and the conclusion drawn from them does not follow: the official sherpa index indeed carries no Indic model, and AI4Bharat do indeed publish the *old per-language* checkpoints as `.nemo` — but **`ai4bharat/indic-conformer-600m-multilingual` publishes ONNX under MIT for all 22 scheduled languages**, and nothing in the 2026-08-12 search opened it. **id** `ai4bharat/indic-conformer-600m-multilingual`; **on-disk, measured:** shared fp32 encoder **2,428,824,576 bytes** in 366 external files, merged graph **4,030,572 bytes** for one language or **26,072,246 bytes** for all 22. **Quantisation: still unmeasured** — see the row below for why. It **loads on the sherpa-onnx vendored in this APK and decodes** |
| Does IndicConformer load on sherpa-onnx 1.13.5, the version in this APK? | **YES — measured by doing it, which is the only way this question was ever going to be settled.** The published export is `encoder.onnx` (`audio_signal[B,80,T]`, `length[B]` → `outputs[B,1024,T']`) plus a **two-node** `ctc_decoder.onnx` (Conv 1×1, weight `[5633,1024,1]`, then Transpose → `logprobs[B,T',5633]`). Appending those two nodes to the encoder graph yields **exactly** the NeMo-CTC signature, and `OfflineRecognizer.from_nemo_ctc` opens it. The graph edit materialises none of the weights: it leaves all 366 external tensors referenced in place |
| How the 22 languages are selected | **A mask, not a model — and this reshapes the download.** `assets/language_masks.json` carries a boolean mask per language over the shared 5633-class space selecting **exactly 257** columns: a contiguous 256-wide block plus the single shared blank at index 5632. 22 × 256 + 1 = 5633 exactly, blocks disjoint. **Odia is block 14** (3584–3839), Hindi block 6, Malayalam block 10. Slicing the Conv head to those 257 rows costs **22,041,674 bytes less** than keeping all 22 — so **one artifact serves every language and per-language downloads would re-send the same 2.43 GB encoder 22 times** |
| Is the language mask optional? | **NO, and this is the finding most worth having.** Decoded over the full unmasked 5633 space the model hears correctly but spells the answer in **mixed scripts**: a Malayalam clip came back `হाாய় ನमस्स्କାରారంം இது ஒரு ডెमो ടెెस्റ്റ് റൺ ஆண் நন্ंदી` — Bengali, Tamil, Kannada, Devanagari, Odia, Telugu and Malayalam tokens in one sentence. Verbatim, from the run. **With the mask applied the same engine returns clean single-script text** — Odia in: `ହାତୀ ଓ ଜିରାଫ ଭଳି ପଶୁମାନଙ୍କର କାର ଏବଂ ଭଲ ଦେଖାଯାଉଥିବା ମାନକ ସରଞ୍ଜାମରୁ ନିକଟରୁ ଦେଖିବା ପାଇଁ ପାଖକୁ ଆସିବାର ପ୍ରଭୃତି ଥାଏ` against the reference `…ମାନକ ସରଞ୍ଜାମକୁ … ଆସିବାର ପ୍ରବୃତ୍ତି ଥାଏ।` Two words wrong out of nine, and **not one character from another script**. That pair — same model, same audio, mask off then on — is the evidence |
| IndicConformer fp32 decode speed | ~~**RTF ≈ 7.9** on a desktop CPU~~ **RETRACTED BY THIS LANE, WITHIN THIS LANE — that figure was an artefact of the HDD, not a property of the model.** It came from the first decode after a cold load off `D:`, where the timer was really measuring page faults. Re-measured from the SSD: the first decode still reads **1.361** while caches warm, and the five after it read **0.218, 0.202, 0.230, 0.235, 0.215 — steady-state RTF ≈ 0.22, five times faster than real time**, fp32, 2 threads, greedy CTC. **A 36× correction, and it inverts the conclusion: speed is not what blocks this model.** Cold load 44.0 s, warm load 11.4 s |
| **IndicConformer accuracy — the entire justification for the switch** | **Odia CER 5.1%, WER 16.7%** and **Hindi CER 6.9%, WER 20.9%**, greedy CTC, fp32, on the same FLEURS studio utterances the Omnilingual measurement used. Compared **on identical references through a single normaliser** (because comparing across two normalisers is not comparing): on the 2 Odia references common to both runs, **WER 52.8% → 13.9%** and **CER 14.2% → 4.5%**; on all 3 Hindi references, **WER 24.4% → 20.9%**, CER 7.1% → 6.9%. The previous lane's 53.3% Odia figure reproduces at 52.8% under this normaliser, so the baseline is sound and the gap is real. **Odia error falls by a factor of ~3.8; Hindi barely moves** — exactly the shape expected when an Indic-specialised model replaces a 1,600-language one, and Odia is the language these workshops are run in |
| The bar Plan §2.2 set for Odia | **Omnilingual's 53.3% cleared no bar. 16.7% WER on studio read speech is a different category of result.** It is still **studio** read speech and therefore still a ceiling — the row insisting on the word *courtyard* remains unanswered, and 16.7% on FLEURS does not license a claim about a courtyard in Odisha |
| IndicConformer int8 size and accuracy | ~~**UNMEASURED — attempted, then deliberately abandoned**~~ **MEASURED 2026-08-13, TWICE, AND IT DOES NOT WORK. This row was the last open question on this model and the answer closes it against shipping the 600M.** The abandoned run above was a resource decision and it was the right one; re-run on a quiet box (`quantize_dynamic`, per-channel, QInt8, `use_external_data_format=True`, peak ~5.0 GB WS, **152 s**) it completes. Both products load on sherpa-onnx and both decode nothing usable, on the same three FLEURS Odia utterances the fp32 graph scores **CER 5.1 / WER 16.7** on: <br>• **default op set** — `654,790,526` bytes (3.72× smaller than fp32) — decodes the **EMPTY STRING** on all three. CER/WER 100% <br>• **`op_types_to_quantize=["MatMul"]`**, added because the first run logged `Inference failed or unsupported type to quantize` for every depthwise-conv slice in the Conformer's convolution modules — `883,021,360` bytes, i.e. **larger**, and it decodes a single character, `ପ`, on all three. CER 99.4 / WER 100% <br>Decode also got **slower**, not faster: RTF 0.256–0.334 against 0.202–0.235 at fp32, which is what `MatMulInteger` on a CPU EP does to a Conformer. **Dynamic int8 is not the route to making this model fit**, and the next thing to try is not another `extra_options` — it is a 120M export |
| Loading 366 separate external weight files | **~9 minutes wall for one load from `D:`, and eventually near-zero progress** — 5 s of CPU in 35 minutes, working set creeping 620→760 MB while `ReadTransferCount` stayed flat at 8 MB. **The flat read counter with a growing working set is the tell: onnxruntime `mmap`s external weights, so the cost is page faults, not `read()` calls, and it bills to neither user CPU nor read I/O.** Root cause, and it is a measurement about this box and not about the model: **`D:` is a 932 GB spinning HDD (RAID) and `C:` is a 238 GB NVMe SSD** (`Get-PhysicalDisk`). Page-faulting 2.43 GB scattered across 366 files off a spinning disk, while five sibling lanes ran the backend suite, is the whole of it. **A sequential `robocopy` of the same bytes to the SSD took 52 seconds, and the load that would not finish in 35 minutes on the HDD then took 44.0 seconds.** Same files, same code, same model: **a 12× improvement, and the difference between "measurable" and "not".** Any shippable form must consolidate the weights into a single blob regardless, which quantisation does anyway. **The lesson for the next lane: stage model weights on `C:` — putting them on `D:` will read as the model being unusable** |
| Can the 600M fp32 model load on the fleet's SM-M325F? | **NO — and as of 2026-08-13 05:46 this is no longer arithmetic, it was TRIED.** The arithmetic said so first: `/proc/meminfo` reports `MemTotal 5,789,032 kB` and `MemAvailable` **1,340,412 kB** (1,121,764 kB on the day of the attempt), against **2,428,824,576 bytes** of weights — short by ~1.8×. Storage was never the constraint; `/data/user/0` has 37 GB free. **The attempt:** all **404 files** (graph `sherpa_or.onnx` 4,030,572 bytes + 366 shared fp32 weight files, 2.5 GB) pushed to `/data/local/tmp/dwic/or600` — which took **thirty-three retries of `adb push --sync`**, the adb-tls link dropping every ~30 s — then `DwAsrIndicProbeTest` run against them. Both files hashed on the phone: **VERIFIED**. `VmHWM` before load 235,773,952. Then **the process disappears**: no `LOADED`, no exception, no tombstone — `libsherpa-onnx-jni.so` loads fine and the log simply ends, while `lmkd` runs a reclaim cascade killing **eleven background apps in 1.4 s** (`com.android.vending:background`, `com.samsung.android.scs`, `com.google.process.gservices`, `com.Slack`, `com.instagram.barcelona`, …) with `reason: low watermark is breached and swap is low`. **Reproduced on every one of four attempts.** So the honest sentence is not "it would not fit" but **"it takes the phone's memory down with it and the kernel kills the process before the recogniser is constructed"** |
| **The encoder weight figure itself is off by one file, and the correction makes it worse rather than better** | Re-derived 2026-08-13 06:30 by asking the graph instead of the directory — `onnx.load(..., load_external_data=False)` on `sherpa_or.onnx` and on `encoder.onnx`, then summing exactly the `location` values each references. **Both reference the identical set: 367 files, 2,469,780,480 bytes** — not the **366 / 2,428,824,576** carried in this row, in `DEVICE-TIER-MEASUREMENT.md`, `PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md`, `DwAsrModel.kt`, `DwDeviceTier.kt`, `DwModelLanguages.kt`, `DwAsrIndicProbeTest.kt` and `asr_artifacts.py`. **The missing file is `Constant_1970_attr__value`, 40,955,904 bytes** — one external tensor, 1.7% of the total. **No conclusion moves and the direction is the safe one:** the shortfall against `MemAvailable` 1,340,412 kB goes from ~1.8× to ~1.84×, and against the 1,058,148 kB read the next morning to ~2.3×. Recorded rather than swept through nine files by a reviewer mid-flight; whoever sweeps them should use the graph's own reference list, since **that** is what onnxruntime `mmap`s and a directory listing is not. Confirmed in passing: `ctc_decoder.onnx` references **zero** external files, which is the byte-level proof of *"the merge materialises none of the weights"* |
| Would the 600M model fit at int8? | **The question is now moot and the prediction was right anyway.** The arithmetic said ~600 MB on disk implying ~2 GB resident against 1.28 GB available, so probably not — and the int8 artifacts that now exist are **654,790,526** and **883,021,360** bytes, so the size estimate was sound in the first case and low in the second. It is moot because **neither artifact transcribes anything** (row above): there is nothing to weigh on a handset. **The 600M multilingual model is the wrong one to ship, and it is the wrong one for two independent reasons rather than one** — fp32 does not fit, int8 does not work |
| One subtlety in the merge, so nobody re-derives it | The tensor the export calls `logprobs` is **raw logits** — AI4Bharat's own `model_onnx.py` applies `log_softmax` in Python *after* masking. The merged graph does not, and for **greedy** CTC that is harmless: `argmax` is invariant under softmax, which is why the decodes come out right. It would matter for anything reading the values as probabilities — beam search with a threshold, or confidence scores. Worth knowing before either is added |
| What was built, and how to rebuild it | Source pinned at **`ai4bharat/indic-conformer-600m-multilingual` revision `e9b71b369c048e2c6b634d4c131061c34e441179`** (MIT; `gated: auto`, so a HuggingFace account that has accepted the terms is needed to fetch it — an unauthenticated `HEAD` returns 401). The merge is `D:/icbuild/build_v2.py`, `--lang LL` for a masked head or no flag for all 22; it appends two nodes and rewrites the graph outputs, materialising none of the weights. Generated token tables were validated against `vocab.json` — per-language files are exactly `vocab[lang][0..255] + <blk>` with contiguous ids `0..256`, and the multilingual table places all 22 blocks with **zero** mismatches. Digests: `sherpa_multi.onnx` `886ce505…f127b5`, `sherpa_or.onnx` `18b34495…ee104d`, `sherpa_hi.onnx` `2384f18e…65eee7`, `sherpa_ml.onnx` `e06854a0…3c9e28`. `D:/icbuild/quantise.py` is the unrun int8 step |
| Which IndicConformer is likely shippable, then? | **A 120M at the right size class — but the one measured here is NOT "per-language", it is ONE language, and the cell that said otherwise is corrected below.** What is measured and stands: a 120M IndicConformer CTC export loads on **sherpa-onnx 1.13.5** from a **493,060,445-byte** fp32 `model.onnx` in **5.2–5.6 s** and decodes at **RTF 0.17–0.24**, and upstream publishes an int8 Hindi 120M at **137,700,000 bytes**, which is the size class a field download actually wants. Its output space has the **same shape** as the 600M's — 5633 classes, `<blk>` last, consistent with 22 × 256 + 1 — but it is **NOT the same table**: **4,934 of 5,633 entries differ** and the two disagree even as sets (344 tokens in one and not the other). **So the block map above is the 600M's and must not be reused for a 120M export.** |
| ~~"it produced clean in-script Malayalam, so the artifact serves all 22"~~ | **WRONG, AND MEASURED WRONG THE SAME DAY. The artifact is `jeswinjestin/sherpa-onnx-nemo-ctc-indicconformer-malayalam` and it is Malayalam-only in effect.** The earlier reading fed it a Malayalam clip, got Malayalam, saw a 5,633-line `tokens.txt`, and concluded the head was multilingual. Fed the **Odia and Hindi** FLEURS clips instead — the same six utterances the 600M scores 16.7% and 20.9% on — it answers in **fluent Malayalam script every time**, at **CER 99–100 / WER 100** on all six: Odia `ହାତୀ ଓ ଜିରାଫ ଭଳି ପଶୁମାନଙ୍କର` comes back as `ഹത്തിയോ ജിരഹ് ഭളി പശുമാനം കൊറ`, Hindi `मेजरकेन खाना, भूमध्य सागर में` as `മേജർ കെൻഖാന ഭൂമധ്യസാഗർ മെ`. **The mechanism of the error is the one this repository already has a rule for and it caught it out anyway:** a vocabulary that can spell a script is necessary for emitting it and nowhere near sufficient — and a token table read as a language list is the same mistake one level up. The only way to find out what a model hears is to give it audio in the language you are asking about |
| **Does an IndicConformer load and decode ON THE FLEET'S HANDSET, and what does it cost?** | **YES. MEASURED 2026-08-13 05:27 on the attached SM-M325F (Android 13, arm64-v8a) by `DwAsrIndicProbeTest`, through `OfflineNemoEncDecCtcModelConfig` on the sherpa-onnx inside the APK** — the first time any IndicConformer has run on this fleet. 493,060,445-byte fp32 graph, both files hashed on the phone and both **VERIFIED** against the digests measured on the box (`ea283766…f55f87`, `ee609676…72dfb2`; 493 MB hashed in **2.33 s**). <br>• **load 6,017 ms** (6,717 ms on the preceding attempt) <br>• **`VmHWM` 236,015,616 → 861,810,688 after load → 884,117,504 peak.** `nativePss` 598,474 kB, `totalPss` 793,327 kB <br>• 5,015 ms of Malayalam decoded in **2,193 ms, RTF 0.437** <br>• `MemAvailable` 1,231,796 kB before, **774,848 kB** after <br>• transcript **byte-identical to the desktop's** for the same clip: `ഹായ് നമസ്കാരം ഇത് ഒരു ഡെ` (both truncate the 5 s clip after 15 tokens — a property of this artifact, not of the phone) |
| ~~**The number that changes the recommendation: file-to-RSS is 1.72×, not 3.4×**~~ | The two measured ratios stand: the pinned Omnilingual int8 is 365,352,120 bytes on disk and peaks at **1.24 GB** (3.4×), and **an fp32 IndicConformer measures 884,117,504 / 493,060,445 = 1.79×** (1.72× counting only the load). ~~Dynamic-int8 weights get dequantised into fp32 working buffers; fp32 weights are used where they lie. **So an int8 120M IndicConformer at ~138 MB should land near 250–350 MB resident on this handset, not 470 MB** — comfortably inside `MemAvailable` even at the 774 MB low-water mark observed above, and inside `DW_MODEL_FREE_RAM_MARGIN_BYTES` too. That is the arithmetic that says the 120M route is worth the export work.~~ **THE PREDICTION IS RETRACTED, 2026-08-13 06:15, BY MEASURING IT — see the row below. It was wrong by 1.6×, and wrong in the direction that flattered the recommendation.** Two errors, both visible without the handset: (1) it scaled a **whole-process** `VmHWM` by file size, and the process floor — `VmHWM` **231,321,600 bytes before any load**, in this document's own readings — does not shrink when the file does. 1.79 × 137,677,431 = **246,442,601**, which is only **15 MB above the empty process** for an artifact whose weights alone are 138 MB; floor + weights is already **368,999,031** before one activation buffer, i.e. past the middle of the predicted band and **19 MB from its top**. (2) It applied a ratio measured at **fp32** to an artifact at **int8**, in the same sentence that states the mechanism (int8 weights dequantised into fp32 buffers) which says int8 ratios are *worse*. The int8 ratio measured on this handset is **3.91×**, higher than the Omnilingual int8's 3.44×, not lower |
| **What an int8 IndicConformer actually costs on the fleet's handset — MEASURED, 2026-08-13 06:15** | **538,144,768 bytes peak `VmHWM`** for a **137,677,431-byte** int8 graph on the SM-M325F, by `DwAsrIndicProbeTest` through `OfflineNemoEncDecCtcModelConfig` on the sherpa-onnx **inside the APK**, both files hashed on the phone and **VERIFIED** (`d4251edb…14a6f5`, `152e58dc…10c981`). <br>• `VmHWM` **231,321,600 before → 453,337,088 after load → 538,144,768 peak** (= **513 MiB**); `nativePss` 289,194 kB, `totalPss` 485,596 kB <br>• **load 3,378 ms** <br>• 12,240 ms of Hindi decoded in **6,922 ms, RTF 0.566**, 84 tokens, transcript byte-identical to the desktop's <br>• `MemAvailable` 1,603,916 kB before, 1,351,304 kB after <br>**What this does to the fit verdict, which is the part that matters:** `dwModelFit` adds `DW_MODEL_FREE_RAM_MARGIN_BYTES` (536,870,912) to the peak, so this artifact needs **1,075,015,680 bytes of `MemAvailable`** to come back `COMFORTABLE`. It had that at 06:15 (1.38 GB). **At the 774,848 kB low-water mark this document records two rows above it is `TIGHT`** — `LITTLE_FREE_MEMORY_RIGHT_NOW` — so *"the first artifact that would be COMFORTABLE rather than TIGHT"* is not established: it is COMFORTABLE on an idle phone and TIGHT on the phone this document measured five minutes earlier. Model-attributable cost, baseline subtracted: 306,823,168 bytes, **2.23× the file** |
| **And an int8 IndicConformer DOES transcribe, which the row above about `quantize_dynamic` must not be read as denying** | **MEASURED 2026-08-13 06:12.** `OpenVoiceOS/ai4bharat-indicconformer-hi-onnx` `model.int8.onnx` — **137,677,313 bytes**, `sha256 c2044177…6011cd`, an int8 export of `ai4bharat/indicconformer_stt_hi_hybrid_ctc_rnnt_large`, MIT — loads on sherpa-onnx 1.13.5 in **3.0 s** and scores **CER 5.6 / WER 19.8** on the same three Hindi FLEURS utterances the 600M fp32 scores **CER 6.9 / WER 20.9** on. **Slightly better than the 600M, at 1/18th the file.** RTF 0.615–0.731, i.e. int8 is slower, exactly as the `quantize_dynamic` row found. **So "int8 does not work" is true of *`quantize_dynamic` applied to the merged 600M graph* and false of *int8 IndicConformer*** — the failing thing is this repository's own dynamic quantisation of a 600M Conformer, not the quantisation of this architecture. Two mechanical prerequisites, both cheap: sherpa refuses the published file with `'vocab_size' does not exist in the metadata`, so four `metadata_props` (`vocab_size=256`, `normalize_type=per_feature`, `subsampling_factor=4` — **this export's own `config.json` says 4, not the 600M's 8** — `model_type=EncDecHybridRNNTCTCBPEModel`) must be added, a 1.0 s edit that adds 118 bytes; and its `vocab.txt` is **already** a 257-line sherpa token table ending `<blk> 256`, which is independent confirmation of the 256+1 block structure from a different exporter. **It is not a row to ship** — third-party repackage, one language, and that language is the one the Omnilingual row already serves — but it was **downloadable in four minutes** and it was the measurement the prediction above needed |
| So what IS the route? | **Export the official per-language 120M `.nemo` ourselves** — `ai4bharat/indicconformer_stt_<lang>_hybrid_ctc_rnnt_large`, 523,192,320 bytes for Odia, downloadable with the terms accepted. That needs NeMo's own exporter, which is a torch-sized dependency nobody here has run; it is the next lane's task and it is a **task, not a search**. What is ruled out, each with a measurement rather than an opinion: the 600M at fp32 (does not fit), the 600M at int8 (does not transcribe), and this third-party 120M (one language, and not one of Odisha's) |
| Peak RSS with an Odia model loaded, at any configured cap | ~~**unmeasured**~~ **1.24 GB**, two methods agreeing to 0.1% |
| WER for Odia on real courtyard audio | **STILL unmeasured — and this row was right to insist on the word "courtyard".** What exists now is WER on *studio read speech*, which is a ceiling and not a field result. Plan §2.2's bar is not cleared: the studio figure alone is 53.3% |
| WER for Hindi | ~~**unmeasured**~~ 24.2% on the same studio audio, same caveat |
| Latency / real-time factor on the M32 | ~~**unmeasured**~~ **slower than the audio plays — every one of twelve utterances across four runs.** The *band* is wider than first reported and is **not** a stable property of the handset: 1.12–1.27 on the first two runs, **1.08–2.97** on an adversarial re-run of the same audio on the same phone. Take the range, not the low end — `docs/DEVICE-TIER-MEASUREMENT.md`, *THE TIMINGS ARE NOT A PROPERTY OF THE HANDSET*. What moved is **unmeasured**; thermal state is the suspect and was not isolated |
| Does the app survive being backgrounded with a model loaded? | **PARTLY.** It survived Home and decoded again; but the probe's process keeps `oom_score_adj = 0` under instrumentation, so the question as the low-memory killer would answer it is **still unmeasured** |
| Thermal behaviour under sustained decode | **unmeasured** |
| Whether the `armeabi-v7a` build actually runs on any field handset | **unmeasured** — and now 16.2 MB of the delta |
| Whether JitPack can build these coordinates | **unmeasured** — not tried |
| Whether `com.bihe0832.android:lib-sherpa-onnx` is a faithful repackage | **unmeasured** — and not a question this lane should answer |

**A published figure is not a measurement**, which is the correction plan §2.1 carries in its own
words — *"a download size is not a resident set, and an Ollama artifact is not the deployment
target"*. Every number above came off an artifact built on this machine, except the two AAR sizes,
which came off the files themselves after downloading them.

---

## 7. What this lane changed: nothing in `android/`

| file | change |
|---|---|
| `android/**` | **none.** Verified with `diff -r` after the last build |
| `android/app/src/main/java/com/designprototype/workshop/data/DwDeviceTier.kt` | **untouched.** `DW_TIER1_RUNTIME_PRESENT` is still `false` |
| `docs/DEVICE-TIER-MEASUREMENT.md` | one added cross-reference to this file; every claim in it still true |
| `docs/ASR-RUNTIME-MEASUREMENT.md` | this file |

`dwTier1Offer` still answers `NO_RUNTIME_IN_THIS_BUILD` on every handset, and it is still the true
sentence rather than the convenient one: there is no speech engine in this APK. Had one shipped, that
refusal would have had to become `NO_MEASURED_MODEL` in the same pass — *"there is no engine"* and
*"there is an engine and nothing to feed it"* send a designer to different places — and this section
would be recording that edit instead.

Verified after all of it: `./gradlew testDebugUnitTest` → **tests=791 skipped=0 failures=0 errors=0**,
unchanged, which is what a lane that changed no code should produce.

## 8. Recommendation

1. **Do not commit a sherpa-onnx dependency yet.** It cannot be resolved from the declared
   repositories, and the three ways of forcing it — a vendored 49 MB binary, a build-time download, or
   a stranger's repackage — are each a decision above this lane.
2. **If it goes ahead, it is the static-link AAR**, measured at +39,811,828 bytes against +53,308,196
   for the default one and +44,919,248 for the default one stripped of the C/C++ API libraries it does
   not use. Row E, not row C, and the difference is 13,496,368 bytes.
3. **Weigh a model before weighing the runtime again.** The runtime alone doubles an APK that is
   already 25 MB and buys nothing a designer can use. §2.2's sequencing argument — runtime first,
   languages one or two at a time — is right about the *download list*; what these numbers add is that
   **the runtime is not the free half of that trade.** A first IndicConformer measurement (step 5)
   decides whether the pair is worth 40 MB plus its own weight, and it should be taken on the M32
   before the runtime is committed rather than after.
4. **`releaseAllAbis=true` must never be published once this lands.** It is 95.5 MB of x86 the field
   cannot load, up from 22 MB. The build already prints a warning; it now understates the case.
5. **Re-open `useLegacyPackaging` deliberately**, with §5's numbers, rather than letting it default.

---

## 9. What was done with these numbers, 2026-08-12 — the engine became an opt-in download

Recommendation 1 above was followed to the letter and then answered from a direction it did not
consider: **the runtime is not committed and is now never going into the APK.** Instead the app offers
it as an install a designer chooses, once on the dashboard at first run and permanently in Settings,
because only some of these designers work where there is no signal and only sometimes.

That design, the contract the server must satisfy, and the release-builder procedure that produces the
pinned digests are in **`docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md`**. Which figures from this document
ended up compiled into the app, and where:

| this document's figure | where it landed |
|---|---|
| arm64-v8a +23,646,824 (row A → E) | `DW_ASR_ENGINE_BYTES_ARM64`, printed on both cards as "24 MB" |
| armeabi-v7a +16,152,132 (row A → E) | `DW_ASR_ENGINE_BYTES_ARM32`, printed as "16 MB" |
| static-link is the cheaper shape (recommendation 2) | the contract tells the release builder to publish that AAR's libraries |
| §3, no `assets/` entry in the AAR | the sentence saying the engine arrives **without a voice**, and the deliberate disable of the whole offer until a model is measured |
| §4, R8 removed all 123 classes | the keep rules the next lane must add, written down rather than added — **no binding exists yet, so no rule was added** |
| x86 / x86_64 static-linked, unmeasured | those handsets are told the word "unmeasured" rather than an ARM figure |

### What the feature itself weighs, measured the same way as everything above

The one number this document's method can add to that lane: `:app:assembleRelease`, ARM pair,
`debugSignRelease=true`, `packageRelease` executed, size off the packaged APK.

| | bytes |
|---|---|
| **row A above** — the same tree, before the opt-in install | 26,244,416 |
| the same tree with the whole feature in it | **26,260,800** |
| **cost of offering the engine instead of bundling it** | **+16,384** (`.dex` +38,236, one 16 KiB alignment step in the APK) |
| **row C — bundling the engine** | +53,308,196 |

**Offering it costs 0.03% of what shipping it would.** `k2fsa` appears zero times in the new
`mapping.txt`, confirming that no binding was added and no keep rule is needed yet — the same reading
that found all 123 classes removed in §4, pointed at our own code. Two of the new classes are absent
from the shipped dex entirely, because with an empty catalogue R8 can prove them unreachable; the full
reading, and the trap it leaves for whoever pins the first artifact, is in
`docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md` §6.

**§7 of this document remains accurate about the lane that wrote it** — that lane changed nothing in
`android/`. The 2026-08-12 lane changed `DwDeviceTier.kt`: `dwTier1Offer` no longer returns a constant,
and `DW_TIER1_RUNTIME_PRESENT` is still `false` but now means "baked into the APK" rather than "not
available at all". **Every handset still answers `NO_RUNTIME_IN_THIS_BUILD` for Tier 1**, because
nothing has been published to install — so the sentence this document's §7 predicted would have to
change has changed for a subtler reason than a runtime shipping: it is now *reached* rather than
assumed. `docs/DEVICE-TIER-MEASUREMENT.md` carries the full table of the states that became
expressible, and the tree was verified green at tests=828 afterwards.

**An adversarial review of that lane the same day took it to tests=831**, correcting six defects — four
of them in the download path's cleanup and two in sentences a designer reads. None was a hole in the
integrity path. They are listed one by one in `docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md` §9, along with the
two documents' claims that had to move with them; the figures in this document were re-checked against
the code and none of them changed.

---

## How this document is kept true

This file mixes two kinds of claim, and they age completely differently. **The eight APK figures are a
RECORD** — they say what eight builds produced on 2026-08-12 from a frozen copy of the tree at
`5ac0ff0`, and nothing that happens later can make them untrue. **The paragraphs about what the code
currently does are CLAIMS**, and this document has already had two of them rot inside a single day: a
version bump that never happened, and a "Tier 1 still answers `NO_RUNTIME_IN_THIS_BUILD` on every
handset" that the measurement itself caused to stop being true. Both corrections are left visible
above.

So the rule for anyone editing this file: **add measurements freely, and treat every sentence about
present behaviour as a liability.** If it can be checked with a command, put the command next to it.

| Claim class | Kept true by |
|---|---|
| The eight APK sizes, the per-ABI deltas, the entry counts | Nothing, and nothing should. They are a dated record of a frozen source, cross-checked against `docs/R8-MEASUREMENT.md` through the ML Kit CRC-32s. To re-measure, repeat §2's method on a fresh frozen copy and add a row — **do not edit these**. |
| That the dependency does not resolve | Re-run the resolver, not a web search: a throwaway Gradle project declaring only `google()` and `mavenCentral()`. The 404s are quoted verbatim in §1 so the failure mode can be told from a connection error. **This is the row most likely to change** — upstream publishing to Maven Central would invalidate the whole of §1, and nothing here would notice. |
| That the AAR carries no `assets/` | `zipfile` over the fetched AAR's central directory. The artifact is not in git, so this needs the fetch repeating. |
| `DW_TIER1_RUNTIME_PRESENT` is `false` | `grep -n "DW_TIER1_RUNTIME_PRESENT" android/app/src/main/java/com/designprototype/workshop/data/DwDeviceTier.kt`. It means "nothing is bundled" and **only** that — since the opt-in lane landed, an engine can be present on a handset without being present in a build. Do not read the constant as "no engine anywhere". |
| What `dwTier1Offer` answers | Read the function. It was a one-line constant return until 2026-08-12 and is not any more; any summary of it here is a claim about code and should be checked before it is trusted. |
| That R8 removes the binding whole | `mapping.txt` after `assembleRelease`: `k2fsa` appears zero times. This is the trap for whoever pins the first artifact — a release build will fail at `UnsatisfiedLinkError` where a debug build worked, unless keep rules are added first. |
| The delivery consequence (no "Later" button) | `MainActivity.kt`'s update dialog. If that ever gains a dismiss, the "compulsory triple-sized download" argument weakens and §2's conclusion should be re-argued rather than re-stated. |
