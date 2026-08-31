# Decision — sending a questionnaire voice note to a transcription provider

**Date:** 2026-08-31
**Status:** implemented
**Applies to:** `/questionnaire` (SINGULAR — the ministry instrument). Not `/questionnaires`, the
designer-owned form builder, which is a separate feature with a separate model.

## The requirement

Owner, 2026-08-30:

> in questionnaire page, whenever the conversation is recorded even using the voice note, that voice
> note is then to be streamed to the same api through which the dictate button is facilitated as
> well, until the elevenlabs, deepgram, or whisper api transcription and translation comes in […]

That makes the interview form a caller of `POST /design-workshops/{id}/dictate`. It is the point at
which a named artisan's recorded voice leaves the device for a third-party provider, synchronously,
while she is sitting in the room. Every other send in this application is either gated by
`DesignWorkshop.dictationConsent` or is not a recording of anybody's voice.

## The question

`POST /design-workshops/{id}/dictate` requires a workshop id **and** a GRANTED consent on that
workshop. A questionnaire interview's `designWorkshopId` is nullable and the picker may be left
empty, so there is not always an id to send. What governs the send?

## The decision

**The interview's own design workshop, or nothing is sent.**

* Where the interview names a design workshop, that id goes in the URL and the existing gate applies
  verbatim — the consent column governs exactly this artisan, this cluster, this week.
* Where the picker is empty, **no request is made at all**, and the box says so in one line. The clip
  still uploads with the interview and the media queue still transcribes it, exactly as before this
  feature existed.

No new route, no new gate, no new consent vocabulary.

### Why not a workshop-less dictation route with its own gate

It would be a second consent regime to keep in step with the first, and the only thing it could gate
on is the account or the interview. An account-level switch is the one this repository has already
refused by name — `services/dictation_consent.py`: *"a consent given for one cluster would silently
cover the next one, and the artisan whose voice it is changes between them. That is not consent, it
is a checkbox with somebody else's name on it."* An interview carries no artisan-signed answer at
all. The id-less `POST /design-workshops/dictate` already exists and answers **410**, retired
precisely because it enforced nothing.

### Why not require the interview to name a workshop first

It would buy the gate by breaking the record. Interviews are taken by researchers who are running no
design workshop at all, and a required picker would either block those sittings outright or teach
everyone to pick an unrelated workshop to get past it — which is a consent answer with the wrong
artisan's name on it.

## The half that had to change on the server

The same bytes also go to the media queue. Until this change `dictation_consent.transcription_verdict`
resolved a questionnaire clip through the upload tag alone, which only recognises `designWorkshop`,
so the clip came back `NOT_WORKSHOP_MATERIAL` and was sent **ungated**.

That means a clip on a REFUSED workshop would have been refused at the microphone and handed to
ElevenLabs by the drain two hours later. One artisan's voice, one consent answer, two opposite
outcomes, and the second one silent. **A gate that only one of two paths honours is not a gate.**

`dictation_consent.interview_workshop_id` closes it: a questionnaire clip resolves the design
workshop its own interview names, and that workshop joins the candidate list under the rule the
module already had — every workshop that names a file has to permit the send, and one refusal is the
answer.

### It changes nothing already in the repository

The rule is deliberately narrow: **a questionnaire clip is a workshop's material exactly when its own
interview says it is.** Measured on this deployment before the change:

```sql
SELECT count(*) FILTER (WHERE "designWorkshopId" IS NOT NULL) AS with_dw,
       count(*) AS total
  FROM "QuestionnaireInterview";
-- with_dw = 0, total = 99
```

So no existing interview is affected, and the 279 archived questionnaire recordings that
`stage_attached_workshop_ids` records as *"material this consent says nothing about"* keep exactly the
verdict they have always had. Only interviews created from now on that explicitly name a workshop are
gated — which are precisely the ones where an artisan has been asked about that workshop.

## The ceiling that had to come with it

There was no size or duration cap on questionnaire recording — `startRecording` ran until Stop — and
the dictation route 413s over 6 MB. A cap was added *before* the upload, or the feature's first real
use would have been a 413 paid for on a village connection.

* `CLIP_BITS_PER_SECOND = 32000` pins the encoder (Android already ships this rate), which is what
  turns a duration ceiling into a size guarantee. Left to the browser, "fifteen minutes" is ~3 MB on
  one and ~14 MB on another.
* `CLIP_MAX_MS = 15 min` → 3.6 MB at that rate, comfortably inside the route's 6 MB.
* The **actual blob** is re-checked against `DICTATE_MAX_BYTES` before anything is posted, because
  `audioBitsPerSecond` is formally a hint.
* Reaching the ceiling **stops the take and keeps every second of it**, and says so on screen.

## Where the words go, and the edited flag

* The quick transcript is **appended** to the answer box, never substituted — two clips against one
  question are two parts of one answer. This reuses `appendDictatedPhrase`, the joiner every dictated
  box in the repository already shares.
* A transcript arriving against a box a person has **edited** is offered, not imposed; accepting the
  offer also appends, so neither branch of the feature can lose a syllable.
* The box carries **Edited / Not edited**, derived by comparing it against the machine's own copy the
  page keeps. A hand-typed answer carries no flag at all — it has no machine text to have departed
  from.
* For stored transcripts the flag is `MediaFile.transcriptEditedAt` / `transcriptEditedById`, written
  only by `POST /media/{id}/transcript` (migration
  `20260831090000_transcript_edited_by_a_human`). NULL means **not stated**, never "never edited", so
  the read-only surfaces render "Edited" or nothing and never claim "Not edited".

## What was deliberately not built

The refined, translated, speaker-labelled Markdown is produced by the queue path only, and the drain
runs in the off-peak window or when the server is idle. The interview form is **create-only** and
uploads its clips at submit, so no `MediaFile` row exists during the sitting and the refined
transcript cannot reach the form that recorded it. Making it possible would mean uploading
questionnaire clips eagerly at Stop, the way `MediaCaptureField` already does — a change to the
questionnaire's upload and offline-outbox model, and not one to make as a side effect of this.

The two-stage rule is therefore implemented where both texts genuinely coexist: within the sitting,
between successive takes; and afterwards on the stored clip, where `TranscriptBlock` shows the queue's
refined transcript with the edited flag on it.

## How this document is kept true

**This is a decision record: the argument in it is frozen and is not rewritten to agree with later
code.** What has to stay true is the status banner and the table below.

| Claim | How to check |
|---|---|
| The clip is posted only through the gated per-workshop route | `dictateAudio`'s third argument is a required `workshopId`; `dwDictatePathFor` builds the URL. Pinned by `the clip is posted through the workshop route, which is the only gated one` |
| No workshop named means no request at all | `quickTranscribe`'s `if (!workshopId)` branch returns before `dictateAudio`. Pinned by `no workshop named means NO REQUEST, not a request with a guessed id` |
| The refusal says the clip is still saved | `frontend/e2e/questionnaire-voice-note-unit.spec.ts` counts both refusal sentences |
| The queue asks the same column as the microphone | `dictation_consent.interview_workshop_id`, joined into `transcription_verdict`'s candidate list. Pinned by `backend/tests/test_questionnaire_clip_consent.py` |
| An interview naming no workshop is exactly as ungated as before | `test_an_interview_that_names_no_workshop_is_exactly_as_ungated_as_before` |
| A non-questionnaire row pays for no extra query | `test_a_row_that_is_not_questionnaire_material_never_pays_for_the_lookup` — asserts the call log, not the result |
| A failed interview read refuses rather than permitting | `test_a_failed_read_raises_rather_than_reading_as_no_workshop` |
| The duration cap really fits inside the byte cap at the pinned rate | `the duration ceiling really does fit inside the byte ceiling at the pinned rate` reads all three constants off the source and multiplies them |
| Neither transcript path can lose a person's words | `NEITHER branch can lose a syllable — both paths append through the shared joiner` |
| No read-only surface ever claims "Not edited" | `the two read-only surfaces pass TRUE or nothing, never false` |
| `transcriptEditedAt` is written by exactly one route | `grep -rn "transcriptEditedAt" backend/app` — `set_media_transcript` and the schema comment, nowhere else |

**Review triggers:** any proposal to make the design-workshop picker required on `/questionnaire`
(it must argue against the second rejected option above); any change that would let the queue
transcribe a questionnaire clip the dictation route would refuse, or the reverse; raising
`CLIP_MAX_MS` or lowering `CLIP_BITS_PER_SECOND`, either of which can push a full-length take past
the route's 6 MB; and any move to upload questionnaire clips eagerly at Stop, which is what would let
the refined transcript reach the form that recorded it and is the one piece of the owner's sentence
deliberately not built here.
