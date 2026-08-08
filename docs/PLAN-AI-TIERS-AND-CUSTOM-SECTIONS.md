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
`android/.../data/*.kt`, no `MediaRecorder` upload in `DwDictation.kt`. It is `SpeechRecognizer`,
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

### 2.1 Tier 2 is memory-bound before it is quality-bound

The fleet device is a **4 GB Galaxy M32**, and this session has already watched *this workstation*
fail a Gradle build at 612 MB free. A 4 GB Android phone gives a foreground app well under 2 GB
before the low-memory killer takes it, and Compose + CameraX + an ONNX ASR session are already in
that budget.

Therefore:

- **Tier 2 never runs concurrently with capture.** It is a queued job on a foreground service, one
  at a time, ideally while charging. A summarizer that kills the camera mid-workshop is a data-loss
  bug wearing a feature's clothes.
- **Gate on measured free memory and a device allowlist, not on a model name.** `ActivityManager
  .MemoryInfo` at run time, plus a real measurement per handset, the same way the language packs
  were just measured rather than assumed.
- **Model IDs and sizes get pinned after measurement, not from a spec sheet.** I could not verify
  the "Gemma 4 E2B/E4B" naming — my knowledge covers Gemma 3n E2B/E4B — so before this is built,
  the exact model id, quantization, on-disk size and peak RSS need to be recorded in a measurement
  doc like the language-pack one. If E4B does not fit the M32, that is a finding, not a failure.

**Recommended first Tier 2 verb: none of the generative ones.** Start with *extractive* work —
tag/category suggestion and metadata extraction — where a wrong answer is a wrong suggestion the
designer declines. Summarization and "transcript cleanup" put model prose next to an artisan's
words, which is the highest-stakes thing in the app and should not be the first thing shipped.

### 2.2 Tier 1 ASR: the sequencing is the risk, not the model

sherpa-onnx + AI4Bharat IndicConformer is the right call and it is the only route to offline Odia.
The risk is that it arrives as a 19-language download list that does not fit on the phone.

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
3. **Stage entries are `extra="forbid"`.** This is not incidental strictness — a stray key is
   rejected, which is what produced the `merge: Extra inputs are not permitted` refusal earlier
   today. Arbitrary designer keys posted alongside core keys will be refused by design, and turning
   the strictness off to make room for them would give up the guarantee that a typo in a core key is
   caught rather than silently stored.
4. **It must work offline**, like everything else: definition cached on the handset, answers stored
   in the local draft, synced later.

### The design that satisfies all four

- **A reserved container, not loose keys.** Custom answers live under one namespaced object on the
  stage entry (`custom`), so `extra="forbid"` stays on for every core key. Inside it, keys are
  validated against the workshop's own custom-section definition — strict, but against a different
  contract.
- **Custom definitions are their own versioned resource**, attached to a workshop or a template and
  delivered with it, carrying `customSchemaVersion` alongside — never inside — `registry_version()`.
- **No REF fields in v1.** Reference hydration is a server contract with an ordering rule
  (copy at save, never re-resolve) and a mapping in the core digest. Designer-defined references
  would put user data into that contract. Scalars, enums, text, dates, numbers, media first.
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

| # | Step | Done when |
|---|---|---|
| 1 | Android → server `/dictate`, ladder reordered | Same clip, same craft terms, on both surfaces |
| 2 | Provenance/layer model + migration, no AI writing yet | A transcript carries source, tier, model, timestamp |
| 3 | Report prints layers and names them | An AI-cleaned passage is identifiable in a .docx |
| 4 | sherpa-onnx runtime, no models | APK size delta measured against `docs/R8-MEASUREMENT.md` |
| 5 | One ASR model (Odia), measured | WER + latency on the M32, written up |
| 6 | Custom sections: definition, validation, offline cache | A designer's field survives a round trip and a report |
| 7 | Tier 2, extractive verbs only, gated on memory | Peak RSS measured; capture never dies |
| 8 | Tier 2 generative verbs, behind acceptance | Nothing reaches a report unaccepted |

Steps 1–3 are worth doing regardless of whether Tiers 1 and 2 are ever built: they fix a live
divergence and they put provenance in before there is a backlog of unattributed AI text.

---

## 6. Open questions — to settle before building, not during

1. **Cost ceiling for Tier 3 dictation.** Per-sentence provider credit across a fleet is a real bill.
   Does rung 2 need a per-designer daily cap, and what does the app say when it is hit?
2. **Does a custom field belong to a workshop, a template, or an organisation?** Templates make them
   reusable; workshops make them safe. This decides the whole data model.
3. **Consent for Tier 3.** Audio of a named artisan leaving the device for a third-party provider is
   a consent question the app should answer explicitly, per workshop, and record.
4. **What happens to accepted AI layers when a better model arrives?** Re-derive, or freeze? The
   hydration precedent says freeze — but a summary is not a name.
5. **Gemma model ids and real footprint on the M32** — unverified, must be measured (§2.1).
