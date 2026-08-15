# Tier 2 — the on-device language model, measured

**Everything below was measured on 2026-08-13 unless it says otherwise in the same sentence. Where a
number is Google's, the row says so; where nothing has been measured, the row says the word
*unmeasured*.** The two rules this document keeps are the two `DEVICE-TIER-MEASUREMENT.md` was written
to keep: *an absence is a claim, and a claim needs a command beside it*, and *a published figure may not
be printed in a measurement's voice.*

Its companion documents: `DEVICE-TIER-MEASUREMENT.md` (the handset, the probe, the Tier 1 model),
`ASR-RUNTIME-MEASUREMENT.md` (the speech engine). The code is
`android/app/src/main/java/com/designprototype/workshop/data/DwTier2Models.kt` and
`…/data/DwTier2Layer.kt`.

---

## 1. What exists, weighed

Four mobile exports exist. `hf download <repo> --dry-run`, authenticated:

| repo | gate | licence |
|---|---|---|
| `litert-community/gemma-4-E2B-it-litert-lm` | **none** (`gated=false`) | apache-2.0 |
| `litert-community/gemma-4-E4B-it-litert-lm` | **none** (`gated=false`) | apache-2.0 |
| `google/gemma-3n-E2B-it-litert-lm` | `gated=manual` → `Error: Access denied. This repository requires approval.` | gemma |
| `google/gemma-3n-E4B-it-litert-lm` | `gated=manual` → same | gemma |

The gate text on the Google cards says *"Requests are processed immediately"*: the whole action is a
person accepting the licence on the model page. **Gemma 4 needs no approval at all.**

Naming, checked rather than assumed: `hf models list --author google --search gemma-3` returns
270m/1B/4B/12B/27B and no E-variant, and `google/gemma-3-E2B-it` is a 404 — **E2B/E4B in that generation
exist only as Gemma *3n***. `litert-community/gemma-3n-*` is a 404 in the other direction: the 3n LiteRT
artifacts live only under `google/`, gated.

### The files

| artifact | bytes | sha256 | who took the digest |
|---|---|---|---|
| `gemma-4-E2B-it.litertlm` | 2,588,147,712 | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` | **MEASURED** — downloaded here, hashed here |
| `gemma-4-E4B-it.litertlm` | 3,659,530,240 | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` | **MEASURED** |
| `gemma-3n-E2B-it-int4.litertlm` | 3,655,827,456 | `2ed7bc3a0026c93d5b8a4544b352d9d00cd66ff0bac3ef6a20ac3d2cba4010d6` | PUBLISHED — LFS `oid`; repo gated, bytes never held here |
| `gemma-3n-E4B-it-int4.litertlm` | 4,919,541,760 | `2e67a6cd51dfe0f793431e6bd4ed8d029c88e10f52ca0469ad38445e3cd3c1f4` | PUBLISHED — same |

Reproduce the first two:

```bash
hf download litert-community/gemma-4-E2B-it-litert-lm gemma-4-E2B-it.litertlm --local-dir .
sha256sum gemma-4-E2B-it.litertlm
```

Local copies (11.2 GB including both `-gpu` variants): `D:\apkprobe\models\`.

**Google's own "Model size (MB)" column reads 2583 and 3654** for these two files. The byte counts above
are what `ls` and `sha256sum` agree on and are what the app states.

**That discrepancy is now reconciled rather than merely noted, and it matters more than it looks.** The
column counts **decimal MB**, proved on the row whose file has not moved: the Web row reads `2008` and
`gemma-4-E2B-it-web.litertlm` is 2,008,432,640 bytes — exactly `bytes / 1,000,000`, where MiB would have
read 1915. The two main files are then ~5 MB *larger* than their own benchmark row (2,588,147,712 B =
2588 decimal MB against 2583; 3,659,530,240 B = 3660 against 3654) and the card says why in its own
speculative-decoding note: *"if you download this model before May 5, 2026, you should re-download the
model if you want to use speculative decoding."* The re-published file carries the extra
`tf_lite_mtp_drafter` section listed below; the size in the benchmark row predates it.

**Why that is not idle:** `DwTier2Models.kt` reads the *memory* column of the same table as **MiB**, on
the argument that `ru_maxrss` reports kilobytes. The evidence above shows at least one column labelled
"(MB)" on this card is decimal, so that reading is a choice under ambiguity — kept because it is the
pessimistic one, not because the card settles it. **Google are inconsistent with themselves inside one
repository, which is why it stays ambiguous rather than resolving:** the benchmark table's size column is
decimal MB (2008), while `gemini4_2b_sizes.png` in the same repo plots the *same file* at **2468 with
axes labelled MiB**. Both readings are Google's, for one artifact, 553 units apart. The consequence is on screen: the app prints "1.8 GB …
that figure is Google's" where the card prints `1733`, and "They publish 709 MB" where it publishes
`676`. See that file's header for the full record and for what fixing it properly would cost.

### Which file a phone wants: the plain `<model>.litertlm`

Proved by reading the container rather than inferred. The `LITERTLM` section names in the main files
include `tf_lite_vision_encoder`, `tf_lite_vision_adapter`, `tf_lite_audio_encoder_hw`,
`tf_lite_audio_adapter`, `tf_lite_mtp_drafter` and `tf_lite_per_layer_embedder` — ten sections. **Both
`-gpu` files list exactly one: `tf_lite_artisan_text_decoder`, so they are text-only** and are not the
file to ship if a caption is ever wanted.

* `gemma-4-E2B-it-gpu.litertlm` — 2,008,432,640 B, `a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5` (MEASURED)
* `gemma-4-E4B-it-gpu.litertlm` — 2,969,059,328 B, `4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff` (MEASURED)

`-web.litertlm` / `-web.task` are WebGPU builds (the README: *"Currently the model is text-only"*). The
`_qualcomm_sm8750`, `_qualcomm_qcs8275`, `_Google_Tensor_G5` and Intel builds are per-SoC NPU
pre-compilations and **none matches the fleet handset's MT6769V/CT (Helio G85)**.

---

## 2. The memory figures — Google's, and read in the units they were taken in

Both `litert-community` cards, Android table, **Galaxy S26 Ultra**, `rusage::ru_maxrss`, 1,024 prefill
and 256 decode tokens at a 2,048-token context, CPU via XNNPACK with 4 threads:

| | CPU | GPU | decode CPU | decode GPU |
|---|---|---|---|---|
| Gemma 4 E2B | **1733** | 676 | 46.9 t/s | 52.1 t/s |
| Gemma 4 E4B | **3283** | 710 | 17.7 t/s | 22.1 t/s |
| Gemma 3n E2B | **no memory figure published anywhere** | — | 16.1 t/s (S24U) | 15.6 |
| Gemma 3n E4B | **none** | — | not tabulated | — |

Three decisions come out of that table and each is in the code with its reason:

1. **The column labelled MB is read as MiB.** `ru_maxrss` on Linux/Android reports kilobytes; a tool
   dividing it by 1,024 for a column headed "MB" has produced MiB. 1733 MiB = 1,817,182,208 bytes. It is
   also the conservative direction.
2. **The CPU figure is what the verdict is computed from, not the GPU figure.** `ru_maxrss` does not
   count GPU or dmabuf allocations, so 676 MiB is a floor and not a cost on a phone with unified memory;
   and whether LiteRT-LM's ML Drift GPU path initialises at all on this fleet's Mali-G52 is
   **unmeasured**. Both `/vendor/lib64/libOpenCL.so` and `/system/lib64/libvndksupport.so` are present on
   the handset (necessary, not sufficient). The GPU number is printed beside the verdict, as a claim.
3. **No `DwModelPlan` can be built for either Gemma 3n artifact.** The only memory number for that family
   is a Google Developers Blog sentence — *"as little as 2GB (E2B) and 3GB (E4B)"* — which is a claim
   about a family on no named handset. `DwModelPlan.peakRssBytes` is required and `measuredOn` names a
   phone, so those two are carried as `DW_TIER2_UNJUDGED`: listed on every device, with size and digest,
   and with the word **unknown** where the verdict would be.

One published in-memory breakdown is worth keeping: `gemini4_2b_sizes.png` in the E2B repo, axes in MiB
— **file 2468 MiB** (= 2,588,147,712 B / 1 MiB exactly, so Google's chart and the byte count agree),
**in-memory multimodal 1144 MiB**, **in-memory text-only 841 MiB**. The ~1225 MiB embedding block is in
the file and not resident (the README: *"the runtime uses memory mapping to support the 1.12GB of
embedding parameters"*), and the vision (~205 MiB) and audio (~100 MiB) encoders load on demand.

---

## 3. What this fleet's handset makes of them

Read off the attached SM-M325F at 03:00 on 2026-08-13 (`adb shell`, not inferred):

```
MemTotal:        5789032 kB   = 5,927,968,768 B
MemAvailable:    1285164 kB   = 1,315,607,936 B
/data available: 39034012 kB  = 39,970,828,288 B
abilist:         arm64-v8a,armeabi-v7a,armeabi        API 33      ro.config.low_ram unset
```

Through `dwModelFit` — the same function, the same 512 MiB memory and 1 GiB storage margins, as the
speech model:

| row | verdict on SM-M325F | the number behind it |
|---|---|---|
| Gemma 4 E2B | **Tight** | 1.32 GB free right now against a 1.82 GB peak — the one figure that moves minute to minute |
| Gemma 4 E4B | **Tight** | same note, 3.44 GB peak |
| Gemma 3n E2B / E4B | **Cannot be judged** | no published memory figure, no local run |

**Neither Gemma 4 row is refused.** Both peaks are far under the phone's total (so no
`LARGER_THAN_THIS_PHONE_S_MEMORY`) and both files are far under 39.97 GB free (so no storage refusal).
Tight is the overridable state by design — memory frees.

By class, on total RAM and storage only:

| class | E2B | E4B |
|---|---|---|
| `armeabi-v7a`-only, any size | **Will not fit** — the runtime AAR has no 32-bit build | **Will not fit** |
| low-RAM flag set, < 3 GiB | too small by arithmetic once a runtime exists | too small |
| 4 GB-class (~3.6 GiB) | comfortable | tight-to-refused, depending on the reading |
| 6 GB-class and up | comfortable | comfortable |

---

## 4. The runtime — it exists, it is multimodal, and it does not compile here

`com.google.ai.edge.litertlm:litertlm-android:0.16.0`, Google's Maven (already declared in
`settings.gradle.kts`, so unlike sherpa-onnx nothing needs vendoring):

* AAR **20,192,711 bytes**, `minSdkVersion 24`, Apache-2.0.
* `jni/arm64-v8a/liblitertlm_jni.so` 21,529,648 B; `jni/x86_64/…` 25,665,928 B. **Two ABIs. There is no
  `armeabi-v7a` build** — which is exactly `DwFitNote.NO_BUILD_FOR_THIS_PROCESSOR`.
* `NEEDED` on the `.so` is platform-only (libandroid, libz, libGLESv2/v3, libEGL, libdl, liblog, libm,
  libc): no NNAPI, no hard OpenCL or Vulkan link. CPU is XNNPACK in-process; GPU is ML Drift `dlopen`ed
  at runtime, so a device without OpenCL degrades rather than failing to load.
* Multimodal is reachable from Android, read off the AAR with `javap`:
  `EngineConfig(modelPath, backend, visionBackend, audioBackend, maxNumTokens, maxNumImages, cacheDir)`,
  `Content.ImageFile/ImageBytes/AudioFile/AudioBytes`, `Session.generateContent(List<InputData>)`,
  `Backend = CPU/GPU/NPU/GOOGLE_TENSOR`. `maxNumTokens` is the field `DwModelPlan.contextCapTokens`
  needs, and `Engine.initialize()` being separate from construction is what makes a
  `DwLoadFailureNote` recordable.

### The blocker, measured, and undocumented upstream

```
javap -v com.google.ai.edge.litertlm.Engine   →   kotlin.Metadata(mv=[2,3,0])
android/build.gradle.kts                      →   org.jetbrains.kotlin.android 2.0.21
```

and the compile says so:

```
e: …litertlm-android-0.16.0-api.jar!/META-INF/…kotlin_module Module was compiled with an
   incompatible version of Kotlin. The binary version of its metadata is 2.3.0, expected version is 2.0.0.
… > Internal compiler error.  BUILD FAILED
```

**No published version compiles against Kotlin 2.0.21**, and "no published version" was checked against
`maven-metadata.xml` rather than assumed. That file lists **twenty** versions and the oldest is
`0.0.0-alpha06`, **not** `0.8.0` — an earlier draft of this section said 0.8.0 was the oldest and was
wrong. Reading `Engine`'s `@Metadata` across the range: `mv=[2,3,0]` for 0.10.0, 0.11.0, 0.13.1, 0.15.0
and 0.16.0, and `mv=[2,2,0]` for the two oldest, `0.0.0-alpha06` and `0.9.0-alpha01`. The floor across
the whole catalogue is therefore 2.2.0, still above the 2.0.0 this compiler expects, so **there is no
version to pin back to.** `runtimeOnly(…)` does not dodge it either — conflict resolution lifts
kotlin-stdlib to 2.2.21 on the compile classpath and the same error fires. Google's own Kotlin guide
states no minSdk, Kotlin or AGP requirement at all.

**So the first cost of Tier 2 is upgrading `org.jetbrains.kotlin.android`, `.plugin.compose` and
`.plugin.serialization` from 2.0.21 to ≥ 2.3.x, project-wide.** Nobody had priced that.

The alternative, priced for comparison: `com.google.mediapipe:tasks-genai:0.10.35` — AAR 42,371,846 B,
`minSdkVersion 21`, four ABIs, **pure Java with no Kotlin metadata, so it compiles here unchanged** and
it has `AudioModelOptions`/`VisionModelOptions`. Against it: Google's README calls that LLM route *"in
maintenance mode"*, and it takes `.task` bundles — Gemma 4 publishes only `-web.task` (text-only), and
the Gemma 3n `.task` bundles are behind the same manual gate. **For Gemma 4 there is exactly one route
and it costs a Kotlin upgrade.**

### APK cost, measured off two packaged APKs

Measured in a throwaway copy of `android/` — the repo tree was never edited:

```
BASELINE  :app:assembleDebug            169,049,818 bytes   (byte-identical to the repo's own app-debug.apk)
+ litertlm-android 0.16.0 packaged      221,567,542 bytes
DELTA                                   +52,517,724
   lib/x86_64/liblitertlm_jni.so         25,665,920  stored uncompressed
   lib/arm64-v8a/liblitertlm_jni.so      21,529,640  stored uncompressed
   dex growth                               +155,144
   zipalign padding shuffle               +5,160,194
```

Read honestly: to get a packaging measurement at all, the probe used `runtimeOnly` +
`exclude(kotlin-reflect)` + `force(kotlin-stdlib:2.0.21)`. **That is not a shippable configuration and it
compiles no call into the runtime**, so kotlin-reflect's dex is not in that +155,144. The release
ARM-pair figure the repo compares against (sherpa = +39,811,828) **cannot be measured until the Kotlin
upgrade lands**, because `assembleRelease` fails at `compileReleaseKotlin` for the same reason. From
measured bytes: the release ABI filter drops the x86_64 lib, leaving 21,529,640 bytes of native, so the
field delta lands around +22 MB before R8 and padding — **derived, not measured.**

**ProGuard/R8 keeps are therefore not written yet, and that is deliberate**: a keep rule for
`com.google.ai.edge.litertlm.**` and its JNI entry points is meaningless until a release build can be
produced to measure it against, and a rule nobody has verified is a rule that silently stops working.

---

## 5. Getting the file onto a phone

### The route that works is the one the ASR lane already built

`GET /api/asr-models/{artifactId}/files/{fileName}`
(`backend/app/api/routes/asr_models.py`) — hashes the file in-process before a byte leaves, serves
`Range` through Starlette's `FileResponse`, sets `ETag` to the content digest so a resume across
replicas is not told to start again, and is behind `can_run_design_workshops` and **neither the daily cap
nor the dictation consent gate** (its own docstring argues why, and a test asserts it imports neither).
A handset is authenticated against this deployment and nothing else, so this is the only route that can
work in the field: Hugging Face needs credentials no field phone has.

**What is missing, precisely:** that route's catalogue is `asr_artifacts.ASR_MODEL_ARTIFACTS` and its
whole vocabulary is "speech model". A language model needs either a sibling prefix
(`/api/language-models/…`) over the same `verify_file` machinery, or a generic artifact catalogue that
both read. `DW_TIER2_ARTIFACTS` in `DwTier2Models.kt` is deliberately in that catalogue's shape — id,
file name, byte count, digest, provenance — so publishing these is a catalogue entry plus a prefix, not
a second endpoint. **This is what Tier 2's download is waiting on, and it is a small change owned by
whoever owns `asr_artifacts.py`.**

Progress reporting needs nothing new either: `DwTransferMeter` in `data/DwDownload.kt` already produces
bytes-of-total, percent, current speed and time remaining, with a stall detector and a resume plan
(`dwResumePlan`, `dwRangeHonoured`). **Do not write a second one.**

### Sideloading, through the same check

Same shape as the speech model's staging (`getExternalFilesDir(null)`, which `adb push` can write with
no root and which the app reads with no permission):

```bash
ADB="C:/Users/hp/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DIR=/sdcard/Android/data/com.designprototype.workshop/files/dwtier2

$ADB shell mkdir -p $DIR
$ADB push D:/apkprobe/models/gemma-4-E2B-it.litertlm $DIR/

# The same two questions the download path asks, asked on the phone:
$ADB shell stat -c %s $DIR/gemma-4-E2B-it.litertlm     # must be 2588147712
$ADB shell sha256sum $DIR/gemma-4-E2B-it.litertlm      # must be 181938105e0e…39a63c
```

**That is not a backdoor and it is not allowed to become one.** `dwTier2VerifyFile(modelId, bytes,
sha256)` is the single predicate both routes pass through; a present file with the right size and no
digest taken answers `WRONG_DIGEST`, not "probably fine". A model with a substituted set of weights
produces confident prose rather than an error, and nobody would check it against the source.

---

## 6. Which verbs are real on a handset

| verb | on-device with a Gemma 4 E-variant | why |
|---|---|---|
| **PROOFREAD** | **yes** | text in, text out; `ai_verbs`' own docstring says its planner "would serve an on-device run unchanged" |
| **TRANSLATION** | **yes**, with a caveat | which languages this artifact writes is **unmeasured**; Google's "35+ out of the box, 140+ pre-trained" is a claim and `DwModelPlan.languages` is `null` accordingly |
| **CAPTION** | **yes** — the finding that changes the design | the vision encoder is physically in the main `.litertlm` (measured section list), the runtime exposes `visionBackend` + `Content.ImageFile`, and the server's `MEDIA_ROOTED_KINDS` already admits CAPTION with the photograph as its evidence rung |
| **EXPANDED** | technically yes; **ship it last** | `ai_verbs.expand` calls itself "the most dangerous verb in this system" |
| **SUBTITLES** | **no, and correctly not** | needs timed fragments; nothing in the LiteRT-LM API returns a timing, and `fit_cues` cannot place a cue without one |

### The real blocker for Tier 2 is not the model, it is the write path

`backend/app/api/routes/design_workshops.py` sets `_SERVER_TIER = ai_layers.AiTier.TIER_3` as a module
constant and passes it to all five verb routes; `AiLayerRegisterIn` refuses a `text` field **on purpose**
— *"a `text` field would turn this endpoint into a way for any client to post model prose into a workshop
record under a provenance of its own choosing"*. **There is therefore no route that accepts a layer a
phone produced.**

That reasoning does not evaporate when the model moves onto the handset: the server still cannot tell a
phone that ran Gemma from a browser claiming to have. What the device side can do — and what
`DwTier2Layer.kt` does — is fix the contract so the decision is the only thing left:

* the body is `kind`, `text`, `provider`, `modelId`, `modelVersion`, `language`, `producedAt` and exactly
  one source key (`sourceLayerId` | `sourceMediaId` | `suppliedText`);
* **no `tier`** — the route fixes it, exactly as today's routes fix TIER_3, because a body that states
  its own tier makes the tier column worthless;
* **no `accepted` / `acceptedAt`** — acceptance stays a separate act by a person, so a device cannot put
  model prose into a report unattended;
* **nothing that addresses a stage entry.** A test reads the file's own source and fails if
  `DwStageEntry` appears in it;
* **neither money gate.** An on-device run sends nothing off the phone, so there is nothing to consent
  to, and it spends nothing at a provider, so the daily cap does not apply — which is not an inference
  but the repository owner's explicit scoping of that cap to the paid providers. A test asserts this file
  calls neither.

**What is undecided, and is not decided in this lane: how a device-produced layer's tier is
established.** This app has no device identity to offer today. Naming that gap is the contribution;
closing it is an API decision.

---

## 7. What could not be established

| wanted | state |
|---|---|
| peak RSS on a fleet handset | **unmeasured** — no runtime in this APK can load the file, and the artifacts are already local so the measurement costs no bandwidth once the Kotlin upgrade lands |
| whether LiteRT-LM's GPU path starts on Mali-G52 | **unmeasured** — the two `uses-native-library` entries Google require are present on the handset, which is necessary and not sufficient |
| whether the app survives backgrounding with a model resident | **unmeasured** — nothing has been loaded |
| which of this app's nineteen languages either Gemma 4 artifact can write | **unmeasured** — nobody has read the tokeniser or scored it |
| Gemma 3n peak RSS at any cap | **unpublished and unmeasured** — the reason those rows carry no verdict |
| a release APK delta for the ARM pair | **unmeasured** — `assembleRelease` cannot compile with the dependency; ~+22 MB is derived |
| tokens/sec on a Helio G85 | **unmeasured** — Google's 46.9 / 17.7 are S26 Ultra figures |
| the E2B card's NPU row (2967 MB) | **unexplained** — it matches no blob currently in the repository |

---

## How this document is kept true

| claim | what checks it |
|---|---|
| The two Gemma 4 byte counts and digests | `hf download litert-community/gemma-4-E2B-it-litert-lm gemma-4-E2B-it.litertlm --local-dir .` then `sha256sum`. **If it disagrees, do not edit the number to match — find out what upstream changed.** `DwTier2ModelsTest` re-states the size independently of the plan row, so an edit to one and not the other goes red. |
| That the two Gemma 3n digests are the host's word and not ours | `curl -s "https://huggingface.co/api/models/google/gemma-3n-E2B-it-litert-lm?blobs=true"`. They must stay labelled `PUBLISHED BY THE HOST` while the repo is gated; a test asserts the string. |
| Those two repositories are still gated | `hf download google/gemma-3n-E2B-it-litert-lm --dry-run` — `Access denied` means the row is still correct. **The day it succeeds, the row can become a plan only if a memory figure comes with it.** |
| The memory figures are Google's | Their model cards' Android tables. Nothing in this repository may re-print them without the S26 Ultra beside them; `DwTier2ModelsTest` asserts the attribution is in the field the card prints. |
| The runtime does not compile here | `javap -v -cp <unpacked classes.jar> com.google.ai.edge.litertlm.Engine \| grep -A2 "kotlin.Metadata("` → `mv=[2,3,0]`, against `org.jetbrains.kotlin.android` in `android/build.gradle.kts`. Adding the dependency and running `:app:assembleDebug` reproduces the compiler error in full. |
| `DW_TIER2_RUNTIME_PRESENT` is still `false` | `grep -n DW_TIER2_RUNTIME_PRESENT android/app/src/main/java/com/designprototype/workshop/data/DwDeviceTier.kt`. It is read by `dwTier2InstallMayBeOffered`, and `DwTier2ModelsTest` goes red if that function stops refusing. **Flipping it turns no control on, because there is no control** — see the row below. |
| **No handset is offered a download, and the reason is an omission rather than a gate** | Measured, not read: `dwRecommendTiers` run over five handsets × three connections against the shipped catalogue gives `tier2 = None(NO_RUNTIME_IN_THIS_BUILD)` everywhere, so `dwTierDownloadMayBeOffered` is false. **But the per-choice gate `dwModelDownloadMayBeOffered` answers `true`** for a Tier 2 row on the fleet handset (TIGHT), a 12 GB phone (COMFORTABLE) and a 2 GB Go-edition phone (TIGHT) on any connection but `NONE` — it is `fit.mayInstall && connection != NONE` and knows nothing about a runtime. It is also the gate `DwModelChoiceList` uses to draw "Install this model". `dwTier2InstallMayBeOffered`, which ANDs the runtime in, **has no caller in `src/main` at all** (`grep -rn dwTier2InstallMayBeOffered android/app/src/main`). So the only thing keeping a 2.6 GB fetch off the fleet is that `DwTier2ModelList` draws no action and takes no `onInstall`. `DwTier2GateTest` pins that: it fails if `DwTier2ModelUi.kt` gains `onInstall`/`Button`/`clickable`, and if any screen hands `tier2Choices` to `DwModelChoiceList`. **Whoever wires an install control must use `dwTier2InstallMayBeOffered` and never `dwModelDownloadMayBeOffered`.** |
| Nothing on this screen can be installed | `DwTier2ModelsTest.no handset and no connection may be offered a download while there is no runtime`, over every device fixture × every connection. |
| The verdicts come from the existing rules | `DwTier2ModelsTest` re-computes each row's fit with `dwModelFit` and asserts equality with what the list shows. A second set of rules in the Tier 2 file would fail it. |
| Neither money gate is in the device write path | `DwTier2LayerTest` reads `DwTier2Layer.kt`'s comment-stripped source and fails on any of the **fifteen real** consent/cap symbol names (`DwTier3Consent`, `dwTier3ConsentOf`, `dwDictationCapView`, `dwDictationAllowanceOf`, …), and a companion test asserts each of those fifteen still exists elsewhere in the app, so a rename cannot make the rule vacuous. **The earlier version of this row was false**: it named five spellings, four of which existed nowhere in the repository, and an "import of either module" clause that could never fire because `DwDictationConsent.kt`, `DwDictationAllowance.kt` and `DwTier2Layer.kt` are all in `com.designprototype.workshop.data` and a same-package reference needs no import. Proved by wiring a working `dwTier3ConsentOf(…) != GRANTED` + `dwDictationCapView(…).spent` gate into `DwTier2Layer.kt` and watching all nine tests pass. |
| Neither money gate is in the server path the download will use | `pytest tests/test_tier2_gates.py`. Behavioural, not an import scan: every public callable of `dictation_consent`, `dictation_cap` and `ai_verb_cap` is replaced with a tripwire that fails the test by name, and `GET /api/asr-models/{id}/files/{name}` is then driven over ASGI — full body and a resumed `Range` — and asserted to return the exact bytes with nothing tripped. One test fires a tripwire on purpose, because an "assert nothing happened" test that has never been seen to fail is an assumption. A separate test first proves a fully spent allowance really is refused for a dictation, so "not refused here" means something. |
| Neither money gate hides in a dependency | Same file: the file route's `Dependant` tree is walked and each `call`'s module and source checked for the three gate names — an import scan cannot see a gate wired in as `Depends(...)`, and the tripwire only sees gates the two requests actually reach. |
| The handset readings in §3 | `adb shell "grep -E 'MemTotal\|MemAvailable' /proc/meminfo"`, `adb shell df /data`, `adb shell getprop ro.product.cpu.abilist`. **Free memory moves minute to minute** — a verdict quoted from §3 is a verdict at 03:00 on 2026-08-13 and nothing more. |
| The `.litertlm` section lists (which file is multimodal) | Read the container header: the section names are ASCII in the first ~96 KB, so `head -c 98304 <file> \| strings \| grep tf_lite` reproduces both lists. |

---

## Two stale claims this lane could not correct, and one it did

**Corrected here.** `dwTier2Offer` used to answer `DEVICE_TOO_SMALL` for any handset in the low-RAM
class *before* asking whether a model or a runtime existed. That ordering was argued for in its own
comment and the argument was sound while the catalogue was empty. With a 1,733 MiB row in it, a 3 GB
handset would have been told "this phone does not have the memory" directly above a row `dwModelFit`
had marked comfortable — two accounts of one fact on one card. The class check now runs after the
build-level ones and only when Android's own `isLowRamDevice` agrees, so the refusal comes from the
phone's numbers rather than from a band this file computed.

**Not corrected: `ui/designworkshop/DwModelChoiceUi.kt`'s header** still says *"`DW_TIER2_CATALOGUE` is
still empty, and its list still draws nothing"*. Both halves are now false — the catalogue has two rows,
and that composable no longer draws the Tier 2 list at all (`DwTier2ModelUi.kt` does). That file belongs
to another lane working on the speech UI in the same tree tonight and editing it would have clobbered
live work. **One paragraph, one file, and whoever next opens it should fix it.**

**Not corrected: `docs/REPO_FACTS.md` is out of date** (`node docs/tools/check-docs.mjs` says so). It was
already failing that check before this lane started, for reasons belonging to several lanes at once, and
regenerating a shared generated file mid-flight would bake in whatever half-state the tree is in.

---

## The R8 keeps this needs, written before the dependency lands rather than after

Not added to `android/app/proguard-rules.pro` today, deliberately: a keep rule for classes that are not
on the classpath protects nothing, cannot be verified (`assembleRelease` does not compile with the
dependency — §4), and would sit in a shared file another lane is editing tonight. They are recorded here
so that whoever adds the dependency adds them in the same commit, which is the order that matters —
R8 strips a JNI entry point silently, and the failure shows up as a native crash in a release build only.

```proguard
# LiteRT-LM: the JNI boundary. Every one of these is reached from liblitertlm_jni.so by name.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames class com.google.ai.edge.litertlm.** { native <methods>; }
# Callback/config types the native side constructs or reads reflectively.
-keep class com.google.ai.edge.litertlm.EngineConfig { *; }
-keep class com.google.ai.edge.litertlm.Backend { *; }
-keep class com.google.ai.edge.litertlm.InputData** { *; }
-keep class com.google.ai.edge.litertlm.Content** { *; }
# gson comes in transitively and is reflective over its own model types.
-keepattributes Signature,*Annotation*
```

**Every line above is derived from the AAR's contents (the `javap` surface and the `.so`'s symbol table)
and none of it has been exercised against R8**, because the release build cannot be produced yet. Treat
it as a starting point to verify, not as a measured configuration — the honest state is *unmeasured*, and
the way to settle it is `:app:assembleRelease` plus a load on a handset once the Kotlin upgrade lands.
