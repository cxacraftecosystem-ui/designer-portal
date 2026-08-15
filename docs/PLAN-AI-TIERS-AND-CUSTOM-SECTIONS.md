# Plan: three-tier AI, and designer-defined sections

Status: **agreed in principle, not started.** Written 2026-08-09 so the reasoning survives the
conversation it came from. Everything below is grounded in what the repository does today, with
file:line, because half of this plan is "stop building what already exists".

---

## 0. What is already built, and must not be rebuilt

The cloud tier is not a future addition. It is the oldest part of this system.

`backend/app/services/ai.py` already runs a **provider chain** with fallbacks: OpenAI
transcription, **ElevenLabs Scribe v2**, **Deepgram Nova-3**, and Gemini with multi-key rotation
(`_next_gemini_start`). Two details matter for the plan:

- **Deepgram Nova-3 is called with `language=multi`** (`ai.py:360`) — chosen precisely because it
  transcribes mixed-language speech, which is what a workshop actually sounds like.
- **Scribe v2 diarizes up to 32 speakers** (`ai.py:262-269`), and it is the long-form model because
  "interviews run past an hour".

So **Tier 3 diarization already exists and works.** What we are deferring is *offline* diarization,
not diarization. That distinction should be in the UI, not just in this document.

There is also `refine_transcript_text` and `analyze_measurement_image` (`api/routes/media.py:27`),
and server-side identity-card OCR (`services/identity_ocr.py`). Several Tier 2 verbs — proofreading,
transcript cleanup — have a cloud implementation today. Tier 2 is therefore not "add a capability";
it is **"move an existing capability offline, and keep the two agreeing about what they mean".**

### The craft vocabulary is the hidden asset

`ai.py:118-138` boosts a craft-specific keyterm list, because a general model writes **"dabu"** —
a mud-resist printing technique — as **"double"**. That list is passed to Deepgram as keyterms and
shapes every server transcription.

**Nothing on the handset has it.** This is the strongest argument in the whole plan, and it drives
Decision 1.

---

## 1. Decision: Android online must use the same service as the web

### The gap, precisely

The web's dictation ladder (`frontend/components/designworkshop/Dictation.tsx`) is:

1. `SpeechRecognition` / `webkitSpeechRecognition` if the browser has it;
2. otherwise **`MediaRecorder` → `DW_DICTATE_PATH` → `ai.transcribe_audio_bytes`** (`Dictation.tsx:42,241`).

The server endpoint says why (`api/routes/design_workshops.py:254`): a dictated sentence and a
transcribed interview "are produced by the same provider chain with the same craft vocabulary and
cannot drift apart."

**Android's ladder has no second rung.** There is no `dictate` call anywhere in
`android/app/src/main/java/com/designprototype/workshop/data/*.kt`, no `MediaRecorder` upload in
`android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwDictation.kt`. It is
`SpeechRecognizer`,
and on error 13 it falls back to *Google's* network recogniser.

### Why this is worse than it looks

Measured on the fleet's Galaxy M32 (`docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md`): Google's
on-device catalogue holds **two** of our nineteen languages. For the other seventeen — **including
Odia, the language of the state these workshops run in** — Android dictation today goes to Google's
*generic* network recogniser: no craft keyterms, no `language=multi`, no control over the model.

So on the same audio, in the same app: the web produces a craft-aware transcript, and Android
produces "double" where the artisan said "dabu". That is exactly the cross-surface divergence this
repository has spent its whole history removing.

### The ladder to build

```
1. Installed on-device pack        → free, instant, streaming, works with no signal.       KEEP FIRST.
2. Server /dictate  (online)       → the web's own path: craft keyterms, language=multi.   ADD.
3. Google network recogniser       → last resort only.                                     DEMOTE.
4. Honest failure                  → "type it in", which today is said far too early.
```

Rung 1 stays first on purpose: it is free, instant and offline, and spending provider credit per
sentence when the phone can already do it would be a bill for nothing.

**The ordering change that matters:** where rung 1 cannot serve the language at all — the seventeen
— rung 2 must come **before** Google's network engine, not after it. Today those languages skip
straight to the generic recogniser.

### Constraints to honour

- `DICTATION_MAX_BYTES` is enforced server-side; the handset must check it before spending an upload.
- The endpoint **stores nothing** and is synchronous. Android must not queue dictation into the
  offline outbox: the designer is standing there waiting for words to appear in a field. If there is
  no signal, rung 2 is simply unavailable and rung 4 is the honest answer.
- The 503 `UNAVAILABLE` path already exists and must be shown as "not configured", never as silence.

---

## 2. The three tiers, with the parts I would change

The tiering is right. Three notes where I would build it differently from the sketch.

### 2.1 Tier 2 on the fleet handset is unproven and at risk. What to measure, and why the table cannot answer it.

> **Correction, recorded deliberately.** An earlier draft of this section was headed "Tier 2 does not
> fit the fleet handset — the published sizes settle it." That was wrong, and wrong in a way this
> document is supposed to catch. **A download size is not a resident set, and an Ollama artifact is
> not the deployment target.** The table below is a desktop/server distribution listing; the Android
> path is a mobile runtime (LiteRT / MediaPipe AI Edge) with a quantization prepared for it, and the
> two are different artifacts with different memory behaviour. The conclusion below is now
> "unmeasured, here is the measurement", which is what the evidence actually supports.

The Gemma 4 distribution sizes, as published:

| tag | size | context |
|---|---|---|
| `gemma4:e2b` | **7.2 GB** | 128K |
| `gemma4:e4b` (latest) | **9.6 GB** | 128K |
| `gemma4:12b` | 7.6 GB | 256K |
| `gemma4:26b` | 18 GB | 256K |
| `gemma4:31b` | 20 GB | 256K |
| `e2b-mlx` / `e4b-mlx` | 6.5 GB / 8.8 GB | 128K |

> **CORRECTED 2026-08-12 BY THE HANDSET ITSELF. THE FLEET DEVICE IS NOT A 4 GB PHONE.** This
> paragraph read: *"The fleet device is a 4 GB Galaxy M32. The smallest Gemma 4 build is 7.2 GB —
> nearly twice the phone's entire RAM … it is off by a factor of four."* The M32 was attached over
> adb and asked. `ActivityManager.MemoryInfo.totalMem` answers **5,927,968,768 bytes — 5,653.4 MiB,
> 5.521 GiB** — byte-for-byte identical to `/proc/meminfo`'s `MemTotal` of 5,789,032 kB, measured
> twice and re-verified independently. **A 4 GB phone cannot report 5.52 GiB**, so the premise was
> simply wrong, and with it "twice the phone's entire RAM" and "off by a factor of four".
>
> **THE CONCLUSION SURVIVES; THE ARGUMENT FOR IT DOES NOT.** 7.2 GB still does not fit in 5.52 GiB,
> so no Gemma 4 build in that table runs on this handset — but it is off by about a third, not by a
> factor of four, and every sentence that reasoned from "4 GB" was reasoning from a number nobody had
> taken. That is precisely the failure this plan's own §2.1 correction was written about: **a
> conclusion that happens to be right, resting on a figure that was never measured.**
>
> **AND IT MOVES THE DEVICE INTO A DIFFERENT ROW OF THIS PLAN'S OWN TABLE.** 5.52 GiB is above the
> `SMALL_4GB` ceiling, so `dwDeviceClass` returns **`MID_6_TO_8GB`** — the row this document
> describes as "6–8 GB … small SLM, measured cap", not the row headed "4 GB (the M32 fleet)". The
> fleet handset has never been in the row named after it. It cleared the edge by 153 MiB, or 2.8%,
> which is close enough that a variant with a larger firmware reservation would land in the row
> below — so **the class of this fleet is measured on n = 1 and is not settled.**
>
> Full measurement, method and the corrections it forced: `docs/DEVICE-TIER-MEASUREMENT.md`.

**No Gemma 4 build in the table above fits the fleet handset. The smallest is 7.2 GB against a
measured 5.52 GiB of total RAM** — before Android, Compose, CameraX or an ONNX ASR session get any,
and the total is not the budget: what a foreground app may hold before the low-memory killer takes it
is smaller again, and **how much smaller is unmeasured on this device.** This is not a tuning problem
or a quantization problem.

Two further facts from that table are worth reading carefully:

- **`12b` (7.6 GB) is barely larger than `e2b` (7.2 GB), and smaller than `e4b` (9.6 GB).** The "E"
  names are *effective* parameter counts; the weights that must be resident are not. Sizing a
  deployment from the letter in the name is how this ends up on a phone that cannot hold it.
- Even the MLX builds — Apple-silicon, not Android — bottom out at 6.5 GB.

A mobile export (int4, MediaPipe/AI-Edge `.task`) is smaller than an Ollama GGUF, and if Google
ships one for Gemma 4 the number will be well below 7.2 GB. **It will not be below ~1.5 GB**, which
is what a 4 GB phone could actually spare, and that estimate is mine, not a measurement.

**And there is a second ceiling that has nothing to do with RAM:** a 7 GB download, or even a 3 GB
one, on prepaid mobile data in a district town is not a feature. The language-pack screen already
refuses to invent a download size next to somebody's data bundle; this is the same problem an order
of magnitude larger.

#### The device decides the tier and the model. DECIDED.

Not one global answer — **the app probes the handset and recommends**, the way the language-pack
screen already asks the phone what it can do instead of assuming. A 4 GB handset and a 12 GB flagship
are different products for this purpose, and pretending otherwise means either shipping nothing or
shipping a crash.

The safeguard that makes this safe is already in this plan: **§3 requires every layer to record the
tier and model that produced it.** Without that, device-dependent tiers would silently produce two
classes of workshop record with no way to tell them apart on the page. With it, a reviewer can see
that one transcript came from a phone and another from the cloud. **Device-based tiering and
provenance ship together or not at all.**

##### What to probe, and what not to

| signal | source | why |
|---|---|---|
| total RAM | `ActivityManager.MemoryInfo.totalMem` | the coarse device class |
| available RAM now | `MemoryInfo.availMem` / `/proc/meminfo` `MemAvailable` | what is actually free at the moment of the job |
| low-RAM flag | `ActivityManager.isLowRamDevice()` | Android's own verdict; an immediate no |
| free storage | `StatFs` on the app's files dir | a 3 GB model needs somewhere to live |
| ABI | `Build.SUPPORTED_ABIS` | which runtime build to fetch |
| charging / thermal | `BatteryManager`, `PowerManager.thermalStatus` | sustained inference on a mid-range phone throttles |

**Do NOT gate on `ActivityManager.getMemoryClass()`.** That is the *Dalvik heap* cap for Java
objects. A LiteRT or ONNX model allocates natively — outside that cap and outside its accounting —
so `getMemoryClass()` will happily report 192 MB on a device that can hold a 2 GB model, and report
nothing useful about the one that cannot. Gating on it would be measuring the wrong thing
confidently, which is the failure mode this repository keeps finding.

##### Context length is a memory dial, not a model property

The table above lists 128K and 256K context windows. **Nobody runs those on a phone.** KV-cache
grows with context length and can exceed the weights themselves; a 128K window is a desktop
affordance. The recommendation must therefore name **model _and_ context cap** together — a 2–4K cap
is ample for "summarize this transcript passage" and changes the memory arithmetic completely. A
recommendation that names only the model has not actually said what will be run.

##### The recommendation table is produced by measurement, not by this document

The shape is fixed now; **every cell is filled by measuring a real handset** and recorded in a
measurement doc, exactly like `docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md`:

| device class | Tier 1 (ASR) | Tier 2 (SLM) | Tier 3 |
|---|---|---|---|
| low-RAM flag set, or < 3 GB | smallest ASR model only | **none** — say so plainly | when online |
| 4 GB (the M32 fleet) | ASR, 1–2 languages | *to be measured* — expect none, or a ≤1 GB-class SLM at a 2K cap | when online |
| 6–8 GB | ASR, several languages | small SLM, measured cap | when online |
| 12 GB+ | ASR, many languages | larger SLM | when online |

Per model, the measurement must record: **mobile artifact size on disk; whether weights are memory-
mapped and the actual resident set if so; peak RSS at the configured context cap; time-to-first-token
and tokens/sec on that handset; and whether the app survives being backgrounded with the model
loaded.** The last one is not a nicety — a designer takes a photograph mid-summary, and if that kills
the process the summary and possibly the draft go with it.

##### Rules for the recommendation itself

- **Recommend; never auto-download.** The same rule the language packs already follow, for the same
  reason: a multi-gigabyte fetch on a prepaid bundle in a district town is a bill, not a feature.
- **Show the real size.** The pack screen refuses to print a size because Android reports none. Our
  own models have a known size, so this screen can and must state it before the tap.
- **A device that cannot run a tier says so, in words, once** — not a greyed-out control with no
  explanation, and not silence. `DwPackState`'s honest-unknown discipline is the model to copy.
- **Re-probe, do not cache forever.** Free RAM and free storage change; a recommendation made at
  install time is stale by the first workshop.
- **If a load fails on a device the table said was fine, that is data.** Record it, fall back a tier,
  and tell the designer what changed rather than failing the job silently.

#### Rules that survive whichever fork is chosen

- **Tier 2 never runs concurrently with capture.** A queued job on a foreground service, one at a
  time, ideally while charging. A summarizer that kills the camera mid-workshop is a data-loss bug
  wearing a feature's clothes.
- **Gate on measured free memory at run time**, via `ActivityManager.MemoryInfo`, plus a per-handset
  measurement — the same way the language packs were measured rather than assumed.
- **Pin model id, quantization, on-disk size and peak RSS in a measurement document** before building
  on them, like `docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md`. The table above is a distribution
  listing, not a measurement of anything running on Android.

**Recommended first Tier 2 verb: none of the generative ones.** Start with *extractive* work —
tag/category suggestion and metadata extraction — where a wrong answer is a wrong suggestion the
designer declines. Summarization and "transcript cleanup" put model prose next to an artisan's
words, which is the highest-stakes thing in the app and should not be the first thing shipped.

### 2.2 Tier 1 ASR: the sequencing is the risk, not the model

sherpa-onnx + AI4Bharat IndicConformer is the right call and it is the only route to offline Odia.
The risk is that it arrives as a 19-language download list that does not fit on the phone.

> **OVERTAKEN BY MEASUREMENT, 2026-08-12. READ THIS BEFORE ACTING ON THE PARAGRAPH ABOVE — IT WILL
> OTHERWISE COST YOU AN AFTERNOON THAT HAS ALREADY BEEN SPENT.**
>
> Both halves of that sentence turned out to be wrong, and the search that established it is written
> up in `android/app/src/main/java/com/designprototype/workshop/data/DwAsrModel.kt` so nobody
> repeats it:
>
> - ~~**IndicConformer is not obtainable in a form this app may ship.**~~ **THIS BULLET IS FALSE AND WAS
>   RETRACTED 2026-08-13 — see the correction block below.** The official `k2-fsa/sherpa-onnx`
>   `asr-models` release has **498 assets and no Indic model**; AI4Bharat publish Odia only as a
>   523,192,320-byte `.nemo` training checkpoint, which sherpa-onnx cannot open; and all three
>   third-party ONNX conversions on Hugging Face were declined — none serves Odia, and one claims eight
>   languages while naming a Hindi-only model as its source.
> - **It is therefore not "the only route".** sherpa-onnx's own export of Meta's Omnilingual ASR CTC
>   300M (int8) was pinned, run on the fleet's M32, and **does hear Odia** — at **53.3% WER** on studio
>   read speech, which clears no bar this section would set. Nothing was switched on;
>   `DW_TIER1_CATALOGUE` is still empty.

> **CORRECTED AGAIN, 2026-08-13. THE 2026-08-12 RETRACTION ABOVE OVERSHOT: it declared the plan's model
> unobtainable, and the plan was right about the model.** The 2026-08-12 search read the older
> per-language `.nemo` checkpoints and three third-party conversions. It never opened
> **`ai4bharat/indic-conformer-600m-multilingual`** — MIT, 404 files, 2,556,502,676 bytes, which
> publishes **ONNX exports** of a 600M Conformer for all 22 scheduled languages. Measured by building
> and running it, not by reading about it:
>
> - **sherpa-onnx 1.13.5 — the version already vendored in this APK — loads it and decodes with it.**
>   `encoder.onnx` (`audio_signal[B,80,T]`, `length[B]` → `outputs[B,1024,T']`) merged with the two-node
>   `ctc_decoder.onnx` (Conv 1×1, weight `[5633,1024,1]`, then Transpose → `logprobs[B,T',5633]`) **is**
>   the NeMo-CTC contract `from_nemo_ctc` expects. No `.nemo` export pipeline, no third-party repackage.
> - **"None serves Odia" was wrong about the OFFICIAL model and right about the conversions.** The
>   official model serves all 22 — that half stands. ~~and the conversion dismissed as "Malayalam-only"
>   emits **5633** classes with a **5633-line** `tokens.txt` covering all 22 — it was read as monolingual
>   because its card names one language.~~ **That half is retracted the same day, 2026-08-13, and this
>   one WAS checked by giving the model audio:** `jeswinjestin/sherpa-onnx-nemo-ctc-indicconformer-
>   malayalam` answers the Odia and Hindi FLEURS clips in **fluent Malayalam script**, CER 99–100 and
>   **WER 100 on all six utterances** — Odia `ହାତୀ ଓ ଜିରାଫ` returns as `ഹത്തിയോ ജിരഹ്`. Its head is
>   Malayalam-locked whatever its token table spans, so the original *"none of the third-party
>   conversions serves Odia"* was correct about it. **The error was inferring a capability from a token
>   table** — the same necessary-versus-sufficient trap this plan already names about vocabularies, one
>   level up.
> - **The 22 languages are ONE artifact, and this rewrites the download shape this section worries
>   about.** `assets/language_masks.json` gives each language a boolean mask over a shared 5633-class
>   space selecting exactly 257 columns — a contiguous 256-block plus the one shared blank at 5632
>   (22 × 256 + 1 = 5633, disjoint). **Odia is block 14, columns 3584–3839.** The encoder is
>   **2,428,824,576 bytes** and is *shared*; the merged graph is **4,030,572 bytes** for one language and
>   **26,072,246** for all twenty-two. **The 22nd language costs 22,041,674 bytes, not another download.**
>   So "one or two languages at a time" is the wrong axis: **per-language downloads would re-send the
>   same encoder 22 times.** The artifact is one file and the language is a 1 MB head.
> - **The mask is mandatory.** Unmasked over all 5633 classes the model is acoustically right but spells
>   the answer in mixed scripts — a Malayalam clip returned `হाாய় ನमस्स्କାରారంം இது ஒரு ডెमो…`, pulling
>   tokens from six scripts at once.
> - **It is more accurate than what is pinned, and by a lot where it matters most.** **Odia CER 5.1%,
>   WER 16.7%; Hindi CER 6.9%, WER 20.9%**, greedy CTC, fp32, on the same FLEURS utterances. Scored on
>   identical references through one normaliser: Odia **WER 52.8% → 13.9%**, Hindi **24.4% → 20.9%**.
>   **Odia error falls ~3.8×.** This section's own sequencing argument is what earned that comparison.
> - **It is also fast: RTF ≈ 0.22**, five times faster than real time, 2 threads. (An earlier figure of
>   7.9 recorded during the same lane was an HDD artefact and is retracted.)
> - **What actually blocks it is memory, and only memory:** the fp32 weights are **2.43 GB** against
>   **1.28 GB of `MemAvailable`** on the fleet's SM-M325F (re-read at 04:35 the next morning: **1.06 GB**
>   — it moves), so they cannot load on the handset at all. ~~That is a quantisation question, and int8
>   is **unmeasured**.~~ **int8 WAS MEASURED, 2026-08-13, and it does not work.** `quantize_dynamic` on a
>   quiet box completes in 152 s and both products load and decode nothing usable on the three Odia
>   utterances the fp32 graph scores WER 16.7 on: the default op set gives **654,790,526 bytes** and the
>   **empty string** every time, and `op_types_to_quantize=["MatMul"]` — tried because the first run
>   logged `Inference failed or unsupported type to quantize` for every depthwise-conv slice — gives
>   **883,021,360 bytes**, i.e. *larger*, and a single character `ପ` every time. Decoding also got
>   slower, RTF 0.26–0.33 against 0.20–0.24. **So the 600M is out on two independent measured grounds,
>   not one**, `DW_TIER1_CATALOGUE` keeps the Omnilingual row, and the route to offline Odia is the
>   official **120M** per-language checkpoint exported through NeMo's own exporter — a task, no longer a
>   search. Everything needed to start it is named in `docs/ASR-RUNTIME-MEASUREMENT.md` §6.
>
> **The last bullet of this list — measure WER and latency on the M32 before offering it — is the one
> that held.** It was carried out, and it is what stopped a bad model reaching a designer in Odisha.
> Results: `docs/DEVICE-TIER-MEASUREMENT.md`, *THE ENGINE RUNS*.

- Ship the runtime with **no models**. Reuse the pack UI that exists — but note it currently
  describes *Google's* packs, and the two lists must not be conflated on one screen.
- **One or two languages at a time, deletable**, with a real size shown. The current pack screen
  deliberately prints no size because the platform reports none (`dwDownloadCostSentence`); our own
  models *do* have a known size, so this screen can finally tell the truth about cost.
- Measure **WER and latency on the M32** for Odia and Hindi before offering it. "Better than nothing"
  is the honest bar in a courtyard, but it has to actually clear it.

### 2.3 Diarization: split the decision

Defer *offline* diarization; **surface the Tier 3 diarization that already exists.** Scribe v2 gives
up to 32 speakers today and the report has a transcript annexure to carry it. And whenever speaker
labels are shown, they carry the tier that produced them — a cloud-diarized interview and a
device-guessed one must never look alike on a page.

---

## 3. The layering law

> The SLM must not silently overwrite source evidence.

This is the most important sentence in the proposal, and the codebase already has a law shaped
exactly like it. `REFERENCE_HYDRATION` copies display fields at **save** time and the report **never
re-resolves** them (`services/report_builder.py:110-115`, `ReferencedRecord`), because a record
edited after submission must not silently change a document already handed to a ministry officer.

AI layers get the same treatment, stated as a rule:

```
audio ──▶ raw transcript ──▶ cleaned transcript ──▶ summary
photo ──▶ OCR text      ──▶ structured text
```

1. **Every layer is a row, never an edit.** No AI output overwrites its input, ever.
2. **Every layer carries provenance**: source layer id, tier (1/2/3), provider or model id, model
   version, timestamp, and language. Without the model id, a systematic error found in six months
   cannot be traced to the material it damaged.
3. **A layer is inert until a person accepts it**, and acceptance is recorded with who and when.
4. **The report prints the accepted layer and names it as such.** An AI-cleaned passage in a
   government document must be identifiable as one. The `SpecialSection` annexure machinery
   (transcripts, media, completeness, questionnaires) is the right place, and the fourth of those
   was built today — the pattern is fresh and proven.
5. **Deleting a derived layer never touches the source.**

### The property that AI breaks, which must be written down

This project's core invariant is that the handset and the server agree — the compensated-sum work,
the `DwPy` helpers, the hydration mirror all exist to enforce it. **AI output cannot have that
property.** The same audio through Tier 1 on a phone and Tier 3 in the cloud produces different
text, legitimately and forever.

So: **no AI-produced value may feed a field that is compared across surfaces, or any derived or
computed field.** AI layers are annexure content and suggestions. If that line is not drawn now, the
first cross-surface divergence test to fail will be blamed on a bug that is actually the design.

---

## 4. Designer-defined sections and fields

The biggest change on the list, and the one with the least room to improvise. Four hard constraints,
all of them load-bearing:

1. **The 22 stages are Python, not data.** `STAGE_1 … STAGE_22` are `StageSpec` literals in
   `services/stage_definitions.py`. Custom sections cannot be added there — that file is a
   deployment, and designers cannot deploy.
2. **`registry_version()` is a digest of every key, type, tier, derivation and hydration mapping**
   (`services/stage_schema.py:1445`), and the handset bundles a 119 KB
   `assets/design-workshop-schema.json` it runs off before it has ever reached the network. If a
   designer's custom field moved that digest, **every handset in the fleet would treat its bundled
   schema as stale** the moment anyone anywhere added a field. Custom definitions must therefore be
   versioned **separately** and must not enter the core digest.
3. ~~**Stage entries are `extra="forbid"`.**~~ **CORRECTED 2026-08-12 — this was wrong, and the
   error inverts the design.** The claim was that a stray key posted alongside core keys would be
   *rejected*. It is not. `extra="forbid"` (`schemas/common.py:13`) applies to the **envelope**;
   `StageEntryIn.data` is declared an open `dict[str, Any]` with no sub-model
   (`schemas/design_workshops.py:145`), and that module's own docstring states the departure in
   full. `validate_entry` builds its result by iterating `entity.fields` only
   (`stage_schema.py:1120-1152`), so a key the registry does not know is never read and never
   written — **it is dropped, in silence.** The `merge: Extra inputs are not permitted` refusal that
   this paragraph cited as evidence was an *envelope* field (`StageEntryIn.merge`), which says
   nothing about the payload.

   The consequence is the opposite of what was planned for. The danger is **not** that strictness
   has to be relaxed to make room for designer keys; it is that designer answers will be **eaten
   without a word** unless they are given an explicit home. A reserved container is still the right
   answer — but it is now load-bearing rather than a courtesy to a validator.
4. **It must work offline**, like everything else: definition cached on the handset, answers stored
   in the local draft, synced later.

### And a fifth constraint, found by surveying rather than by remembering

5. **Eight of the twenty-two stages declare no SINGLETON entity at all.** Stages 6, 11, 12, 13, 14,
   15, 16 and 17 — existing products, sketch development, sketch review, prototype development,
   prototype iteration, prototype validation, final prototype documentation, costing and market
   linkage — are collections only. Dumped from the bundled registry, and
   `validate_registry` forbids a stage having more than one singleton
   (`stage_schema.py:758-760`). So "hang the container on the stage's singleton row" **cannot serve
   a third of the stages, and it is the third a designer is most likely to want to extend.**

### The design that satisfies all five

- **A reserved container, and it is its OWN ROW rather than a nested object.** Custom answers are a
  `DwStageEntry` whose `entityKey` is the reserved literal `_custom` — one row per (workshop,
  stage), whose whole `data` is the container. The answers still live inside `DwStageEntry.data`,
  as this plan required; they simply do not share a row with core keys.

  **This was chosen over the nested `custom` object on the stage entry, and the reason is the
  installed fleet.** `save_stage`'s default is `merge=false`, which writes the incoming `data`
  wholesale; so a client one release behind — one that has never heard of custom sections and sends
  no `custom` key — would have **deleted every custom answer on the stage, silently, with nothing in
  `droppedKeys` to say so.** Nesting would have needed a bespoke "the entry carried no `custom` key,
  therefore preserve the stored one" rule keyed on the raw payload, and getting that rule wrong once
  costs the fleet its data. With a separate row there is nothing to get wrong: an old client sends
  no `_custom` entry, no `_custom` row is touched, and the collection sweep cannot reach it because
  `collection_keys` is derived from the registry's own entities and `_custom` is not one.

  Three further things fall out of the same choice rather than needing their own code: the shallow
  `{**previous, **clean}` merge is already correct because the row's keys are top level; the
  `MAX_FIELD_KEYS` cap already bounds it for the same reason; and the existing rejected-value
  preservation loop works verbatim.

  The price, stated plainly: **four places in the server derive everything from the registry and
  must learn one reserved entity key** — `save_stage`'s entity lookup, `_stages_payload`'s
  cardinality lookup, `workshop_completeness`'s cardinality lookup, and the invariant that the
  collection sweep never widens to include it.
- **Custom drift NEVER enters `droppedKeys`.** That field is the only client/server registry-drift
  signal this repository has, and both clients render it as "this phone is running a newer field
  registry than the server". A custom key the server's definition does not carry is a different
  fact and gets its own `droppedCustomKeys` and its own sentence — otherwise every save of every
  workshop with a custom section cries wolf, and the one signal that matters gets ignored.
- **A duplicate LABEL is refused at definition time, not just a duplicate key.** This plan asked
  only for the key check, and the key check is not the one that bites. `StageCompleteness.missing`
  holds **labels** and is de-duplicated with `dict.fromkeys` (`stage_schema.py:1297`), so two
  fields sharing a label collapse into one row on the readiness screen and in the report's
  "Outstanding" column while `required_total` still counts two — a document disagreeing with itself
  about its own arithmetic, which is a defect this repository has already shipped once and written
  up (the 144/144-versus-"Not recorded"-thirty-six-times case).
- **Custom definitions are their own versioned resource**, attached to a workshop or a template and
  delivered with it, carrying `customSchemaVersion` alongside — never inside — `registry_version()`.
- **No REF fields in v1.** Reference hydration is a server contract with an ordering rule
  (copy at save, never re-resolve) and a mapping in the core digest. Designer-defined references
  would put user data into that contract. Decisively: `ref_resolves` is supplied by the REPORT and
  by nothing else, so a dangling custom REF would read *filled* on every form and *unfilled* in the
  document — which is the 144/144-versus-"Not recorded" defect, verbatim.
- **AND NO MEDIA IN v1 EITHER — a correction to this plan, not a restatement of it.** The sentence
  here used to end "scalars, enums, text, dates, numbers, media first", and media was wrong. There
  are **five** separate walkers that translate a local media reference into a server id, and every
  one of them enumerates the media-typed fields **of the row's registry entity** and reads them at
  the **top level of the row**: the server's `_media_ids` (in `backend/app/services/design_workshops.py`,
  with `workshop_media_ids` as its public name),
  Android's `wireData` and the web's `unresolvedMediaRefs`, draft-resolve and `rewriteMediaRefs`.
  None of them can see a value that is not a registry field. A custom media answer would therefore
  sync as a `dwlocal:` reference that resolves to nothing: the save reports success, and the
  photograph is simply absent from the .docx — which the designer discovers from the officer. This
  is not hypothetical; it is the `RICH_TEXT` scar tissue in `WorkshopSync.kt`, the same bug already
  shipped once. Media (and `RICH_TEXT`, which carries media several levels down inside its document
  JSON) re-open in v1.1 by teaching all five walkers the custom row — **not before.**

  So v1 is: text, long text, integer, decimal, money, percent, date, time, bool, enum, multi-enum,
  tags. Scalars and lists of scalars, and nothing that points at a file or at another record.
- **Reserved-key collision is a validation error at definition time**, with the core key named. Not
  at answer time, and never a silent shadow.
- **Completeness scoring must be told explicitly** whether a custom required field counts toward the
  percentage. My recommendation: it does, but only for the workshop that defines it, and the
  submission-readiness copy has to say which.
- **Report placement is part of the definition**, not a guess: a custom section names the stage
  section it belongs after, and its own annexure if it belongs nowhere.
- **Android renders custom fields with the existing `FieldRenderer` types only.** A custom field type
  the handset does not know must degrade to read-only with an honest note — the
  `UNSUPPORTED_SECTIONS` precedent — never to a blank.

---

## 5. Sequence

Ordered so each step is verifiable before the next depends on it.

| # | Step | Done when | Status, 2026-08-12 |
|---|---|---|---|
| 1 | Android → server `/dictate`, ladder reordered | Same clip, same craft terms, on both surfaces | **BUILT.** `DwDictationLadder.kt` (pure, 24 JVM tests), `DwDictationUpload.kt`, the control rewritten around the plan walk. **Not run on a handset.** |
| 2 | Provenance/layer model + migration, no AI writing yet | A transcript carries source, tier, model, timestamp | **BUILT.** `DwAiLayer` + `DwAiLayerDecision`, migration, `ai_layers.py`, five routes, 80 tests. **Migration not executed** — no Postgres here. |
| 3 | Report prints layers and names them | An AI-cleaned passage is identifiable in a .docx | **BUILT.** `report_ai_layers.py`, `ANNEXURE_AI_LAYERS`, the builder branch, the loader, `includeAiLayers`, 21 tests. Handset says honestly that it cannot carry it. |
| 4 | sherpa-onnx runtime, no models | APK size delta measured against `docs/R8-MEASUREMENT.md` | **MEASURED, AND THE DESIGN CHANGED BECAUSE OF IT — see below.** The official AAR is on neither `google()` nor `mavenCentral()` under any spelling tried, so no build-time dependency could be added at all; the cost was measured off eight packaged APKs anyway. `docs/ASR-RUNTIME-MEASUREMENT.md`. |
| 5 | One ASR model (Odia), measured | WER + latency on the M32, written up | **BLOCKED — needs the handset.** |
| 6 | Custom sections: definition, validation, offline cache | A designer's field survives a round trip and a report | **BUILT ON THE SERVER AND IN THE BROWSER; NOT ON THE HANDSET.** Server: `services/custom_sections.py`, `report_custom_sections.py`, migration `20260812090000_dw_custom_sections` (**applied to a live database**), `GET`/`PUT /{id}/custom-sections`, the stage bucket's third key, the scorer. Browser: `lib/customSections.ts`, the answering wrapper, the authoring editor, the route, the draft/sync/scoring changes. ~~**Android has none of it** — `grep` for `customSection` / `custom-sections` / `CUSTOM_SECTION` over `android/app/src/main/java/` returns nothing, so a field copy exported from the handset carries none of the designer's own questions.~~ **SUPERSEDED 2026-08-15: the handset has it.** The same grep now returns **12 files**, among them `data/DwCustomSections.kt`, `data/DwCustomSectionStore.kt` and `ui/designworkshop/DwCustomSectionForm.kt`, with the answers carried through `StageSchema.kt`, `WorkshopSync.kt` and `DwSubmissionReadiness.kt` and printed by `ReportScreen.kt`. The claim above was true when it was written and was left standing while the work landed — **re-run the grep rather than trusting either sentence.** |
| 7 | Tier 2, extractive verbs only, gated on memory | Peak RSS measured; capture never dies | **HALF BUILT.** The device probe and the recommender are done (`DwDeviceTier.kt`, 44 JVM tests) and correctly recommend *nothing*; there is no model to run. See `docs/DEVICE-TIER-MEASUREMENT.md`. |
| 8 | Tier 2 generative verbs, behind acceptance | Nothing reaches a report unaccepted | **NOT STARTED**, and the acceptance gate it depends on is built and enforced. |

### Step 4 changed shape on 2026-08-12, by the user's decision, and the measurement is why

**THE RUNTIME IS NOT IN THE APK. IT IS AN OFFER THE DESIGNER ACCEPTS.** Posed once on first install,
and standing permanently in Settings. In the user's own words: *"the app is for designers empanelled
by the government, they just go in field for the workshops at times."* Only some of them need an
offline engine, and only sometimes.

The measurement is what forced it. Bundling was costed off eight real packaged APKs
(`docs/ASR-RUNTIME-MEASUREMENT.md`): the ARM-pair APK goes **26,244,416 → 79,552,612 bytes, +53.3 MB,
3.03×** — and **that is engine only, because the AAR carries no `assets/` entry at all.** The
static-linked variant is +39.8 MB. Per ABI: arm64-v8a +23.6 MB, armeabi-v7a +16.2 MB. Tripling the
download for every designer in the fleet, to serve the ones who go to the field, on prepaid mobile
data in a district town, is not a trade this app should make on their behalf.

**AND IT IS THE ONLY AVAILABLE DESIGN, WHICH NOBODY EXPECTED.** The official artifact is published
on neither `google()` nor `mavenCentral()` — proved through the Gradle resolver, twelve spellings,
every one a 404, with `repo1.maven.org` answering 200 from the same machine, so this is "not
published" and not "no network". Upstream ships the AAR as a GitHub release asset. **A build-time
dependency was never on the table**, whatever anybody decided about size.

Two things that follow, and both are recorded here because they are traps rather than details:

- **THE ONE MAVEN CENTRAL HIT IS A STRANGER'S REPACKAGE** (`com.bihe0832.android:lib-sherpa-onnx`).
  It was found, examined and declined. Do not reach for it because a search returns it: it would put
  an individual's rebuild of a speech engine inside an application that carries Aadhaar numbers and
  government craft records.
- **A DOWNLOADED `.so` IS EXECUTABLE CODE, NOT DATA**, and that is the whole difference between this
  and the language packs it otherwise copies. A pack is fetched by the platform into the platform; a
  native library is loaded into *this* process — the one holding national identity numbers, designer
  credentials and a fortnight of unsynced fieldwork. So the SHA-256 of every artifact is **pinned in
  the APK** and verified against the file **on disk after writing** before anything is loaded, from
  internal storage only. That digest pins the bytes to what the release builder intended and **is not
  a signature**: it establishes no upstream provenance, and the code says so rather than implying
  otherwise.

Steps 1–3 are worth doing regardless of whether Tiers 1 and 2 are ever built: they fix a live
divergence and they put provenance in before there is a backlog of unattributed AI text. **All three
are built.** The AI chain is now closed end to end without a single model having been added:
something produces a layer → it is recorded with its provenance → a person accepts it, by name → the
report prints it and says a machine wrote it. What is missing from that sentence is only the first
verb, which is steps 4 to 8.

### What was NOT verified, stated once so it is not mistaken for done

- **Nothing ran on a handset.** No recording, no upload, no 503, no code-13 hand-over, no device
  probe. The JVM suites cover the pure decisions and the wording; they cannot cover the platform.
- ~~**No migration was executed and no route round-tripped through Prisma.**~~ **SUPERSEDED
  2026-08-12: the database was started and all three migrations were applied.** `prisma migrate
  deploy` applied `20260811090000_dw_ai_layers`, `20260812090000_dw_custom_sections` and
  `20260812120000_dw_dictation_consent_and_cap` against PostgreSQL 16; `migrate status` now reports
  "Database schema is up to date"; `DwAiLayer`, `DwAiLayerDecision`, `DwCustomField`,
  `DwCustomSection` and `DwWorkshopConsentDecision` all exist. **The two `CHECK` constraints — the
  only SQL Prisma cannot generate and therefore the only statements the offline `migrate diff` could
  not vouch for — are live and were verified by querying `pg_constraint`:**
  `DwAiLayer_source_is_exactly_one CHECK (num_nonnulls("sourceMediaId", "sourceLayerId") = 1)` and
  `DwAiLayer_has_content CHECK (text IS NOT NULL OR payload IS NOT NULL)`. And **the whole backend
  suite ran green against that live database: 2163 passed, 2 skipped** — including the 199 tests that
  had errored at fixture setup for the entire build, among them `test_stage_sync.py` (the
  merge/replace semantics this work depends on), `test_workshop_audio.py` (the dictation cap),
  `test_design_workshop_viewers.py` and `test_media_entitlement.py` (the grant machinery the
  annexure's entitlement gate rests on).
- **The report annexure has never been rendered to a real .docx by a real workshop's data.** It is
  covered end to end through `build_report` against constructed layers, which is where the branch
  and the wiring live, but not against a database.

---

## 6. Open questions — settled 2026-08-11, before building

Four of the five were put to the user and answered. The answers are recorded here rather than only
in the code, because each of them is a decision somebody will want to re-open, and a decision whose
reasoning is not written down gets re-opened from scratch.

1. **Cost ceiling for Tier 3 dictation. ANSWERED: a per-designer daily cap, named in words when it
   is hit.** Rung 2 spends provider credit per dictated sentence, and a fleet of designers dictating
   into 496 fields is not a rounding error. The cap is configurable, counted per designer per local
   day, and when it is reached the control says so and falls back to rung 3 and then rung 4 — it
   does not fail silently, and it does not go quiet in a way that reads as a broken microphone. The
   rejected alternative was "ship uncapped and measure", whose measurement is the first bill.

   **SCOPE, SETTLED 2026-08-12 — THE CAP IS A MONEY CONTROL, NOT A RATE LIMIT, and that decides
   exactly what it may count.** In the user's words: it *"should only apply to the global `/dictate`
   when it is utilizing the ElevenLabs / Deepgram / Whisper API, and not the browser / internet one,
   or the one through sherpa-onnx or from the local SLM."*

   So the ceiling exists to bound a **bill**, and anything that costs nothing must be outside it:

   | path | costs money? | counted? |
   |---|---|---|
   | `POST /design-workshops/dictate` → `ai.transcribe_audio_bytes` (ElevenLabs Scribe v2, Deepgram Nova-3, OpenAI/Whisper, Gemini) | **yes, per sentence** | **YES — and this is the only thing that is** |
   | the browser's own `SpeechRecognition` / `webkitSpeechRecognition` | no | no |
   | Android's `SpeechRecognizer`, on-device pack **or** Google's network engine | no | no |
   | an installed sherpa-onnx engine (Tier 1, once it exists) | no | no |
   | a local SLM on the handset (Tier 2, once it exists) | no | no |

   Four consequences, each of which is a way to get this wrong:

   - **It suppresses rung 2 and NOTHING ELSE.** A spent allowance must never remove rung 1 or rung 3.
     A designer whose credit is gone still has a free engine and must still be able to dictate with
     it; taking that away would turn a spending limit into a work stoppage.
   - **A request that never reached a paid provider must not be counted.** A clip refused for
     exceeding `DICTATION_MAX_BYTES`, a 503 because no provider is configured, a request refused for
     want of consent — none of them spent anything, and charging a designer's allowance for a refusal
     is the same defect as charging them for a failed download.
   - **The free rungs must not be made to look capped.** They do not touch `/dictate` at all, so this
     falls out of the architecture rather than needing a guard — but it needs saying, because the
     obvious shorthand ("a daily dictation cap") describes a limit on *dictation*, which is not what
     this is.
   - **THEREFORE THE WORDING IS PART OF THE DECISION.** Every sentence a designer reads must name
     *the transcription service*, never dictation in general: "you have used all N of today's
     dictations **that go to the transcription service**". A message reading "you have used today's
     dictations" is false on any handset with a pack installed, and it is false in the direction that
     makes a designer stop using a control that still works.
2. **Does a custom field belong to a workshop, a template, or an organisation? ANSWERED: the
   workshop.** A definition attaches to one design workshop. A bad definition then damages one
   record rather than every record sharing a template; completeness scoring has one unambiguous
   owner, which is what §4's completeness recommendation already assumed; and editing a definition
   cannot retroactively change a workshop that has already been submitted. Template-scoped
   definitions remain a possible later addition — the resource is versioned separately precisely so
   that a second scope can be added without moving `registry_version()` — but they are not v1.
3. **Consent for Tier 3. ANSWERED: per workshop, recorded, and it gates rung 2.** A workshop carries
   an explicit answer to "may recordings and dictation from this workshop leave the device for a
   third-party provider", with who set it and when. Until it is answered yes, rung 2 is unavailable
   and says why — an account-level setting was rejected because a consent given for one cluster
   would silently cover the next one, and the artisan whose voice it is changes between them.
4. **Accepted AI layers when a better model arrives. ANSWERED: freeze; a re-run is a NEW layer.**
   Exactly the `REFERENCE_HYDRATION` rule, for exactly its reason: a document already handed to a
   ministry officer must not change under them. A better model produces a new, unaccepted layer
   beside the accepted one, and a person chooses. This is what makes a report reproducible a year
   later, and it is why §3's rule 1 ("every layer is a row, never an edit") is enforced in the
   service rather than left to callers.
5. **Gemma model ids and real footprint on the M32** — STILL OPEN, and unmeasurable from this
   machine: it needs the handset. §2.1 says what to measure and the measurement document
   `docs/DEVICE-TIER-MEASUREMENT.md` carries the empty table with every cell marked unmeasured.

---

## How this document is kept true

**A plan is true until it is executed, and then it is history.** This one is explicitly *"agreed in
principle, not started"*, and the danger is not that it decays into misinformation about the code —
it is that it is read as a description of the system when it is a description of an intention. Two
of its sections have already been overtaken by measurement, and both say so in the body rather than
being edited to match. That is the pattern to follow: **overtake a section with a dated block, do not
rewrite it.**

| Claim class | Kept true by |
|---|---|
| §0, *What is already built* | **The most perishable part of this file, and the part a reader most needs to be right**, because its whole purpose is "stop building what already exists". It is grounded in `backend/app/services/ai.py`, `backend/app/api/routes/media.py` and `backend/app/services/identity_ocr.py`. Re-read those before acting on any "already exists" claim here. |
| The `file:line` citations throughout | **Do not trust them and do not add more.** They were accurate on 2026-08-09 against files that have moved since; `docs/tools/check-docs.mjs` only verifies that a cited line is *inside* the file, which a rotted citation usually still is. The symbol names beside them (`_next_gemini_start`, `refine_transcript_text`, `analyze_measurement_image`, `REFERENCE_HYDRATION`) are the durable half — grep for those. |
| §1, "Android online must use the same service as the web" | A decision, not a description. Whether it has been acted on is settled by `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwDictation.kt` — if a `dictate` call appears there, this section is done and should be marked so. |
| §2.1, the Tier 2 handset measurements | **Owned by [DEVICE-TIER-MEASUREMENT.md](DEVICE-TIER-MEASUREMENT.md)**, which carries the table with every unmeasured cell marked. This file states what to measure and why the table cannot be answered from a spec sheet; it must not grow its own numbers. |
| §2.2, the ASR sequencing | Already overtaken once, by the block dated 2026-08-12, and one of its bullets retracted on 2026-08-13. Read the retractions before the bullets. |
| §3, the layering law, and §4's five constraints | These are the parts most likely to have *become* code. `REFERENCE_HYDRATION`'s freeze rule and "every layer is a row, never an edit" are enforced in the service; the custom-sections design in §4 has an implementation (`android/app/src/main/java/com/designprototype/workshop/data/DwCustomSections.kt`, the custom-section endpoints in `backend/app/api/routes/design_workshops.py`, and `backend/tests/test_custom_sections.py` / `backend/tests/test_custom_sections_endpoints.py`). Where a constraint has shipped, the test that pins it is more authoritative than this paragraph. |
| §5, the sequence, and §6, the open questions | Human bookkeeping. Question 5 (Gemma model ids and footprint on the M32) is marked STILL OPEN and unmeasurable from this machine; it closes on a handset, not here. |

**Review triggers:** a step in §5 being started or abandoned; a measurement landing in
`DEVICE-TIER-MEASUREMENT.md` that answers §2.1; anything in §0 being rebuilt, which is the failure
this document exists to prevent.

**The honest summary for a reader in a hurry:** everything here was true of the repository on
2026-08-09, amended 2026-08-11 through 2026-08-13 where measurement contradicted it. Nothing in it is
checked by a test, because a plan cannot be. Where it disagrees with the code, the code wins and this
file needs a dated block saying so.
