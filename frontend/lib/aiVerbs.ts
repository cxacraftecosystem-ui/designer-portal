/**
 * THE FIVE THINGS A DESIGNER CAN ASK A MODEL TO DO, and the words the browser says them in.
 *
 * The layering law and the five layer routes are `lib/aiLayers.ts`. This is the other half: six
 * calls the browser has never made, a verb vocabulary, the daily allowance, and the four sentences
 * a client has to author itself because no server ever sends them.
 *
 * WHY THIS IS A SIBLING OF `lib/aiLayers.ts` AND NOT A SECTION OF IT, which is the same question
 * that file answers about `designWorkshops.ts`. That module is five calls, a kind vocabulary and the
 * plain-English rendering of both — a screen's worth. This is six MORE calls, a separate verb
 * vocabulary (`ai_verbs.Verb` is deliberately NOT `ai_layers.LayerKind`: a kind describes what a
 * stored row IS and is printed as a heading in a government document, a verb describes what somebody
 * ASKED for and is what the daily meter counts), an allowance with its own arithmetic, and a
 * hand-built file download. Putting them together would make one 1,400-line module that three
 * unrelated screens all import for one function each.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * EVERY KEY BELOW WAS READ OFF THE PYTHON, NOT REMEMBERED — the bodies off
 * `schemas/design_workshops.AiProofreadIn` / `AiExpandIn` / `AiTranslateIn` / `AiMediaVerbIn`, the
 * 201 off `design_workshops._finish_verb`, the allowance off `ai_verb_cap.allowance_payload`, the
 * cue payload off `subtitles.cues_payload`. `lib/aiLayers.ts`'s header records what it costs to get
 * this wrong: `DwIdentityOcrResult` declared five keys the endpoint had never sent, JSON decoding
 * ignores unknown keys so nothing threw, and a PERFECT read of an identity card was reported to a
 * designer as unreadable.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * TWO RULES THIS MODULE IS WRITTEN TO KEEP.
 *
 * 1. **NOTHING HERE RETURNS A VALUE SHAPED TO BE ASSIGNED INTO A FIELD.** A verb's output is a
 *    LAYER over the designer's text — accepted or declined by a named person — and never a
 *    replacement for it. The server makes that true by construction (`LayerWritePlan` may only name
 *    a table in `WRITABLE_TABLES`, and `DwStageEntry` is deliberately absent), and this side keeps it
 *    true by having nothing to call. There is no `applyVerbResult`, no "text to paste", and
 *    `expandDesignWorkshopNote` deliberately has no layer parameter at all, mirroring `AiExpandIn`:
 *    *"this body means a client cannot even ask"*.
 * 2. **THE REFUSALS ARE THE SERVER'S WORDS.** Consent (`dictation_consent.SENDS`), the cap
 *    (`ai_verb_cap.cap_refusal`), the placement law (`ai_layers.check_placement`) and the 503 for a
 *    server with no key are all sentences that already name the next move, and `aiLayerProblem` is
 *    reused rather than re-implemented so a client and a server cannot come to disagree about what a
 *    refusal means. The only strings this file authors are the four PRE-PRESS states, which no
 *    server ever sees because the point of them is that the request is not made.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * NOTHING HERE WORKS OFFLINE AND NOTHING HERE MAY BE QUEUED. Every verb is a provider round trip the
 * server makes on this designer's behalf — `_SERVER_TIER` is a module constant and all five routes
 * pass it, so there is no on-device runner to fall back to. And a verb SPENDS MONEY:
 * `ai_verb_cap.spend` counts every run that reached a provider INCLUDING a failure, so a run banked
 * in `lib/offline.ts` and replayed three days later would be charged against a day the designer is
 * not having, over a workshop whose consent may have been withdrawn in between. `saveOrQueue` would
 * refuse most of these anyway (it queues only `isTransient(error) && !(error instanceof ApiError)`,
 * and a consent 409 or a cap 429 is an `ApiError` the server actually answered) — but the reason not
 * to is the money and the meter, not the plumbing.
 */

import { API_BASE, ApiError, ApiUnconfiguredError, apiFetch, assertApiConfigured, describeApiDetail, getToken } from "@/lib/api";
import { fileNameFromDisposition } from "@/lib/designWorkshops";
import { blockText, orderedRange, sliceSpans, type DocRange, type RichDoc } from "@/lib/richText";
import { isUnreachable } from "@/lib/offline";
import type { DwAiLayer } from "@/lib/aiLayers";

/* ────────────────────────────────────────────────────────────────────────────
 * The vocabulary
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `ai_verbs.Verb` — what somebody ASKED for, which is the meter's label and the route's last path
 * segment. Four of the five line up with a `DwAiLayerKind` and one does not: the verb is `EXPAND`
 * and the kind it produces is `EXPANDED`. Do not use one where the other belongs.
 */
export type DwAiVerb = "PROOFREAD" | "EXPAND" | "TRANSLATE" | "CAPTION" | "SUBTITLES";

/** The path segment each verb posts to, so a caller cannot lower-case a token by hand and miss. */
const VERB_PATHS: Record<DwAiVerb, string> = {
  PROOFREAD: "proofread",
  EXPAND: "expand",
  TRANSLATE: "translate",
  CAPTION: "caption",
  SUBTITLES: "subtitles"
};

/**
 * The verb in the words a refusal uses, copied from `ai_verbs.Verb.human` so the sentence a designer
 * reads before the press and the one the server sends after it call one act one name.
 */
const VERB_HUMAN: Record<DwAiVerb, string> = {
  PROOFREAD: "proofreading",
  EXPAND: "expanding a note",
  TRANSLATE: "translation",
  CAPTION: "describing a photograph",
  SUBTITLES: "subtitling"
};

export function aiVerbLabel(verb: DwAiVerb): string {
  return VERB_HUMAN[verb] ?? "this";
}

/**
 * The longest passage a verb may be asked to work on.
 *
 * `ai_layers.MAX_SOURCE_TEXT_CHARS`, which `schemas.MAX_VERB_TEXT_CHARS` imports rather than
 * repeats. **Kept in step BY HAND here, which is the one thing this file cannot import**, so it is
 * named once and read everywhere rather than being written 20000 at three call sites.
 *
 * Checked before the press and not after it: the service's refusal has the argument in it — *a
 * proofread of the first ten pages of a twelve-page note, recorded as a proofread of the note, is a
 * layer whose source text is not what it says* — and a designer who selects a stage-13 narrative and
 * gets a bare 422 after the round trip learns only that the button is broken.
 */
export const MAX_VERB_TEXT_CHARS = 20_000;

/**
 * The longest a language name may be — `ai_verbs.MAX_LANGUAGE_CHARS`, and the `max_length` on both
 * language fields of `AiTranslateIn`.
 */
export const MAX_VERB_LANGUAGE_CHARS = 40;

/* ────────────────────────────────────────────────────────────────────────────
 * The allowance
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * How many runs of the writing and captioning models this account has left today.
 *
 * The five keys are `ai_verb_cap.allowance_payload`, which is the same object the 201 and the 429
 * both carry, so one shape describes the allowance wherever a client meets it.
 *
 * `aiVerbsLimit` AND `aiVerbsRemaining` ARE BOTH NULL WHEN THERE IS NO CAP, deliberately: "0
 * remaining" and "no ceiling" must not look alike, and the obvious `?? 0` an implementer reaches for
 * turns an uncapped deployment into one that refuses everything. `aiVerbDay` is the SERVER's
 * India-time date, which is what makes a held copy safe — a copy whose day no longer matches is
 * stale rather than authoritative.
 *
 * `refusal` is NOT on the 201. It exists only on the pre-flight (see {@link dwAiVerbAllowance}),
 * where it carries `cap_refusal`'s own sentence so that neither client has to author the wording of
 * a ceiling.
 */
export type DwAiVerbAllowance = {
  /** The daily ceiling, or null when this deployment sets none. 0 is a real setting: verbs are off. */
  aiVerbsLimit: number | null;
  /** Runs that reached a provider today, across every verb — including the ones that then failed. */
  aiVerbsUsed: number;
  /** What is left today, or null when uncapped. Never conflate a null with a zero. */
  aiVerbsRemaining: number | null;
  /** The server's India-time date the count belongs to, e.g. "2026-08-19". */
  aiVerbDay: string;
  /** `{VERB: count}` — the breakdown, ordered by the server as count then name. */
  aiVerbsByVerb: Record<string, number>;
};

/** The pre-flight's answer: the allowance, plus the sentence to show if it is already spent. */
export type DwAiVerbAllowanceState = DwAiVerbAllowance & {
  /** `ai_verb_cap.cap_refusal(allowance)` — null while there is room. Never re-worded here. */
  refusal: string | null;
};

/**
 * What all five routes answer with, from `_finish_verb`.
 *
 * `accepted: false` and `acceptanceRequired: true` are on the wire rather than in documentation, and
 * the route says why: *"the client that just asked for this has words on screen and is one tap from
 * putting them in a report"*. They are typed as the literals they are so a screen cannot branch on
 * them wrongly — there is no shape of this response in which a fresh layer is already accepted.
 */
export type DwAiVerbResult = DwAiVerbAllowance & {
  layer: DwAiLayer;
  accepted: false;
  acceptanceRequired: true;
};

/**
 * The request in the air right now, if there is one, so a stage full of controls asks once.
 *
 * DELIBERATELY NOT A RETAINED ANSWER, which is `dwDictationAllowance`'s argued position one module
 * over and it transfers unchanged: a designer who spends four runs on stage 13 and opens stage 14
 * would be shown the count from before those four, and a FIELD LAPTOP IS SHARED —
 * `AuthProvider.logout` clears the token and deliberately nothing else, so a retained count would
 * follow the previous designer's account into the next one's session. Releasing the promise on
 * settle costs one request per screen and buys the property that every control on one screen shows
 * the SAME number.
 */
let verbAllowanceInFlight: Promise<DwAiVerbAllowanceState | null> | null = null;

/**
 * Ask what today's ceiling is, before spending a run to find out.
 *
 * **IT NEVER THROWS AND NULL IS AN ORDINARY ANSWER.** Two different things produce it and a caller
 * has nothing different to do about either: no connection, or a server that does not offer this
 * route. The second is not hypothetical today — `GET /design-workshops/ai-verb-allowance` DOES NOT
 * EXIST YET; it is the one backend change this feature needs and it is owned by another lane (see
 * the note in the module that consumes this). Until it lands, every deployment answers 404, this
 * resolves null, and the surfaces degrade exactly as they do with no signal: no countdown is drawn,
 * nothing is disabled on a ceiling nobody can see, and a cap that is genuinely reached arrives as
 * the server's own 429 sentence after the press. That is a worse experience than the pre-flight and
 * it is an honest one, which is the pair of properties a missing route has to have.
 *
 * `ai_verb_cap.allowance_payload`'s own docstring makes the argument for the route: *"a client that
 * can learn the ceiling only by being refused has to spend a run to learn it"* — and a run is a
 * provider call somebody pays for.
 */
export function dwAiVerbAllowance(): Promise<DwAiVerbAllowanceState | null> {
  if (verbAllowanceInFlight) return verbAllowanceInFlight;
  const asking: Promise<DwAiVerbAllowanceState | null> = apiFetch<DwAiVerbAllowanceState>(
    "/design-workshops/ai-verb-allowance"
  )
    .catch(() => null)
    .finally(() => {
      // Identity-checked rather than blanked: a later call may already have replaced this one, and
      // clearing that one instead would leave a promise nothing can ever release.
      if (verbAllowanceInFlight === asking) verbAllowanceInFlight = null;
    });
  verbAllowanceInFlight = asking;
  return asking;
}

/**
 * Forget any answer in the air, so the next caller asks again. FOR TESTS, and named so.
 *
 * Nothing in the app calls it — the promise releases itself the moment it settles, which is why
 * there is no stale answer for production code to have to clear.
 */
export function forgetAiVerbAllowanceInFlight(): void {
  verbAllowanceInFlight = null;
}

/**
 * The allowance as it stands after a run, read off the 201 the run itself answered with.
 *
 * A SEPARATE FUNCTION RATHER THAN A SPREAD AT THE CALL SITE, because the 201 carries no `refusal`
 * and the pre-flight does: composing them by hand is how a screen ends up showing yesterday's
 * refusal beside today's count. The refusal is recomputed from the numbers rather than kept, and
 * only in the one case a client can decide with certainty — nothing left, on a real ceiling.
 * Everything else is left null, because `cap_refusal`'s zero-cap sentence is the server's to write
 * and a client that guessed at it would be the second voice on one rule.
 */
export function allowanceAfterRun(result: DwAiVerbAllowance): DwAiVerbAllowance {
  return {
    aiVerbsLimit: result.aiVerbsLimit,
    aiVerbsUsed: result.aiVerbsUsed,
    aiVerbsRemaining: result.aiVerbsRemaining,
    aiVerbDay: result.aiVerbDay,
    aiVerbsByVerb: result.aiVerbsByVerb ?? {}
  };
}

/**
 * Is the ceiling reached, as far as this client can tell WITHOUT asking?
 *
 * `aiVerbsRemaining !== null && aiVerbsRemaining <= 0` and nothing else. A null allowance — no
 * pre-flight route, no connection — answers false: a client that withheld a capability because it
 * could not confirm the ceiling would take the feature away on exactly the deployments that have no
 * ceiling at all. The guard is `Dictation.tsx`'s (`dictationsRemaining !== null`) applied to this
 * meter, and it is the one an implementer gets wrong by writing `?? 0`.
 */
export function aiVerbsSpent(allowance: DwAiVerbAllowance | null | undefined): boolean {
  const remaining = allowance?.aiVerbsRemaining;
  return remaining !== null && remaining !== undefined && remaining <= 0;
}

/**
 * How near the ceiling a countdown should be drawn at.
 *
 * Three, matching the point at which a designer can still change what they do about it — with ten
 * left the number is noise, and with none left it is a refusal rather than a warning.
 */
export const AI_VERB_COUNTDOWN_FROM = 3;

/* ────────────────────────────────────────────────────────────────────────────
 * The five verbs
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What a text verb is being run over: EITHER a passage the caller is holding, OR a stored layer.
 *
 * ONE OF THE TWO AND NEVER BOTH, which is what `_require_exactly_one_source` refuses on the server
 * with the reason in it: *"a result whose source nobody can determine cannot be printed or checked"*.
 * Modelled as a union rather than as two optional fields so that the 422 is unreachable from this
 * client by construction rather than by a check somebody has to remember to write.
 */
export type DwVerbSource = { text: string } | { sourceLayerId: string };

function verbBody(source: DwVerbSource): Record<string, string> {
  return "text" in source ? { text: source.text } : { sourceLayerId: source.sourceLayerId };
}

function verbPost(workshopId: string, verb: DwAiVerb, body: Record<string, unknown>) {
  return apiFetch<DwAiVerbResult>(`/design-workshops/${workshopId}/ai-layers/${VERB_PATHS[verb]}`, {
    method: "POST",
    body: JSON.stringify(body)
  });
}

/**
 * Correct the spelling, grammar and punctuation of a passage, and change nothing else.
 *
 * `_PROOFREAD_SYSTEM` refuses the model permission to translate, to restructure or to shorten, and
 * passes `craft_keyterms()` as a do-not-touch list so that "dabu" is not "corrected" to "double".
 * That is the promise the heading makes and it is why this is a separate kind from
 * CLEANED_TRANSCRIPT, which restructures a conversation into speaker turns and — on this
 * deployment's default `REFINED_TRANSLATED` — translates it into English.
 *
 * `language` is optional and is a HINT to the model, not a claim about the text.
 */
export function proofreadDesignWorkshopText(
  workshopId: string,
  source: DwVerbSource,
  language?: string | null
): Promise<DwAiVerbResult> {
  return verbPost(workshopId, "PROOFREAD", {
    ...verbBody(source),
    ...(language?.trim() ? { language: language.trim() } : {})
  });
}

/**
 * Write a designer's terse note out into prose.
 *
 * **THIS SIGNATURE HAS NO LAYER PARAMETER AND MUST NEVER GAIN ONE.** It is not an omission and it is
 * not symmetry lost: expanding is the one verb that INVENTS sentences. Over the designer's own
 * shorthand it turns their note into their prose and they are standing there to judge it; over an
 * artisan's transcript it would put invented words in a named person's mouth, in a document a
 * ministry officer reads, and no acceptance screen can make that safe because the person accepting
 * it is not the person being quoted. The server refuses it three independent ways —
 * `ai_layers.TEXT_ROOTED_KINDS`, `ai_verbs.expand`, and `AiExpandIn` having no such field at all,
 * *"so a client cannot even ask"* — and this is the fourth. Adding a parameter here is a change to
 * the plan, not a convenience.
 */
export function expandDesignWorkshopNote(
  workshopId: string,
  text: string,
  language?: string | null
): Promise<DwAiVerbResult> {
  return verbPost(workshopId, "EXPAND", {
    text,
    ...(language?.trim() ? { language: language.trim() } : {})
  });
}

/**
 * Translate a passage, producing a SIBLING that stands beside the original.
 *
 * THE ORIGINAL IS NEVER TOUCHED, and the failure that shape is written against is already in this
 * database: `AppSetting.transcriptionMode` defaults to `REFINED_TRANSLATED`, under which the media
 * queue stores an English rewrite in `MediaFile.transcriptText` — the column an annexure prints, and
 * where a raw transcript is expected.
 *
 * `targetLanguage` is required and `sourceLanguage` is not, which is not an inconsistency: the
 * target is a CHOICE only the caller can make, while the source is an OBSERVATION the run may
 * already have made. See {@link translationTargetRefusal} for the one target the server will not
 * accept.
 */
export function translateDesignWorkshopText(
  workshopId: string,
  source: DwVerbSource,
  targetLanguage: string,
  sourceLanguage?: string | null
): Promise<DwAiVerbResult> {
  return verbPost(workshopId, "TRANSLATE", {
    ...verbBody(source),
    targetLanguage: targetLanguage.trim(),
    ...(sourceLanguage?.trim() ? { sourceLanguage: sourceLanguage.trim() } : {})
  });
}

/** Describe a photograph or a video in one sentence — for the annexure, and for a screen reader. */
export function captionDesignWorkshopMedia(
  workshopId: string,
  sourceMediaId: string,
  language?: string | null
): Promise<DwAiVerbResult> {
  return verbPost(workshopId, "CAPTION", {
    sourceMediaId,
    // "multi" is DROPPED by the server rather than refused for a caption — it is something a
    // recording can BE, not something one sentence can be written in — so nothing here has to
    // special-case it.
    ...(language?.trim() ? { language: language.trim() } : {})
  });
}

/**
 * Produce timed captions for a recording or a video.
 *
 * **THIS IS A SECOND PAID UPLOAD OF AUDIO THIS SYSTEM HAS ALREADY TRANSCRIBED**, and the route's own
 * docstring calls that "a defect rather than a design": ElevenLabs Scribe v2 and Deepgram Nova-3
 * both already return timings and both discard them one line after parsing, so nothing already in
 * the archive can be subtitled without sending the audio again. Say so before the press — see
 * {@link SUBTITLES_SECOND_UPLOAD_NOTE} — because a designer subtitling twenty recordings in an
 * afternoon will spend twenty uploads and twenty cap increments over material already in the
 * database.
 *
 * There is deliberately no `language` argument: `AiMediaVerbIn.language` documents that subtitles
 * ignore the field entirely, because a cue list is in whatever language was spoken.
 */
export function subtitleDesignWorkshopMedia(workshopId: string, sourceMediaId: string): Promise<DwAiVerbResult> {
  return verbPost(workshopId, "SUBTITLES", { sourceMediaId });
}

/**
 * THE ONE VERB THAT NEVER RUNS ON A DESIGNER'S OWN KEY, said out loud because no client can tell.
 *
 * Four of the five verbs pass `user_id=current_user.id` into `user_ai_keys.resolve`, which hands
 * back the designer's own key when they have one that can do the task and falls back to the
 * deployment's otherwise — so bring-your-own-key is invisible and needs no branch. `subtitle_ai_layer`
 * is different: it calls `ai.transcribe_timed_bytes(content, filename, mime, get_settings())`, and
 * that function's signature HAS NO `user_id` PARAMETER AT ALL. Subtitles therefore always run on the
 * deployment's key, even for a designer who supplied one that could transcribe.
 *
 * A backend asymmetry rather than a client one, and nothing here changes it — but a designer who
 * supplied a key expecting to pay for their own work is silently on the organisation's bill for this
 * verb alone, and the review sheet's provenance line will name whatever provider the deployment's
 * chain picked. Saying so is the alternative to a fabricated impression of provenance, which is the
 * failure this whole feature exists to prevent.
 */
export const SUBTITLES_DEPLOYMENT_KEY_NOTE =
  "Subtitles always run on this server's own transcription key, even if you have supplied one of " +
  "your own — the other four verbs use yours when you have one. That is a limitation of the server " +
  "rather than a choice made here, and it means the cost of this run falls on the organisation.";

/** Said before the press on the one verb that costs an upload of bytes the archive already holds. */
export const SUBTITLES_SECOND_UPLOAD_NOTE =
  "Subtitling sends this recording to a transcription engine again. The timings are the whole point " +
  "of it and nothing already stored has them, so even a recording this workshop has already " +
  "transcribed has to go up a second time — which costs an upload and one run of today's allowance.";

/* ────────────────────────────────────────────────────────────────────────────
 * The subtitle file
 * ──────────────────────────────────────────────────────────────────────────── */

export type DwSubtitleFormat = "srt" | "vtt";

/** What each format is actually for, so a designer picks by the player rather than by the extension. */
export const SUBTITLE_FORMAT_LABELS: Record<DwSubtitleFormat, string> = {
  srt: "SubRip (.srt) — VLC, a phone gallery, an email attachment",
  vtt: "WebVTT (.vtt) — a browser video player"
};

export type DwSubtitleFile = { blob: Blob; fileName: string };

/**
 * One SUBTITLES layer as a file a player can open.
 *
 * **IT CANNOT BE AN `<a href>` AND IT CANNOT GO THROUGH `apiFetch`.** Every media route on this
 * server is bearer-authenticated and an anchor sends no `Authorization` header, so a link would
 * 401; and `apiFetch` reads every response as JSON or as text, which for a subtitle file would hand
 * back a string cast to the caller's type. So the fetch is hand-built with the same three
 * obligations `downloadDesignWorkshopReport` discharges — refuse when this build has no usable API
 * address, attach the token, and turn a failure body into the sentence the server actually sent.
 *
 * **THE FILE NAME IS THE SERVER'S AND IS NEVER INVENTED.** `download_subtitles` writes
 * `subtitles-{layer}.speakers.srt` for the labelled file and `subtitles-{layer}.srt` for the
 * anonymised one, precisely so a designer with both in a downloads folder can tell them apart —
 * and confusing those two is how a ministry is emailed the version that attributes an artisan's
 * words to a machine's guess.
 *
 * NOT GATED ON ACCEPTANCE, matching the route, which is deliberate and says so: *"requiring
 * acceptance first would mean accepting subtitles nobody has watched, which is the opposite of what
 * acceptance is for."* This is the designer looking at what the model produced, in the only form in
 * which subtitles can be judged, which is played against the video.
 */
export async function downloadDesignWorkshopSubtitles(
  workshopId: string,
  layerId: string,
  format: DwSubtitleFormat,
  speakers = false
): Promise<DwSubtitleFile> {
  assertApiConfigured();

  const headers = new Headers();
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const response = await fetch(
    `${API_BASE}/api/design-workshops/${workshopId}/ai-layers/${layerId}/subtitles.${format}` +
      (speakers ? "?speakers=true" : ""),
    { headers, cache: "no-store" }
  );

  if (!response.ok) {
    const contentType = response.headers.get("content-type") ?? "";
    const payload = contentType.includes("application/json") ? await response.json() : await response.text();
    const detail =
      typeof payload === "object" && payload && "detail" in payload ? (payload as { detail: unknown }).detail : undefined;
    // `statusText` is empty over HTTP/2 — which every deployed request is — so it can never be the
    // last resort on its own, or a body-less failure reaches the screen as a blank error box.
    throw new ApiError(
      response.status,
      describeApiDetail(detail, response.statusText || `The server refused the request (HTTP ${response.status}).`),
      payload
    );
  }

  return {
    blob: await response.blob(),
    // The fallback is reached only if a proxy stripped the header. It keeps the two files
    // distinguishable for the reason above rather than naming them both "subtitles".
    fileName:
      fileNameFromDisposition(response.headers.get("content-disposition")) ??
      `subtitles-${layerId}${speakers ? ".speakers" : ""}.${format}`
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Reading a stored cue list
 * ──────────────────────────────────────────────────────────────────────────── */

/** One cue as `subtitles.Cue.payload` writes it. `speaker` and `estimated` are absent when false. */
export type DwSubtitleCue = {
  start: number;
  end: number;
  text: string;
  /** The ENGINE'S GUESS at who was speaking, present only when it made one. Never a person's name. */
  speaker?: string;
  /** True when this cue's boundary was interpolated rather than reported by the provider. */
  estimated?: boolean;
};

export type DwSubtitleSummary = {
  /** Cues in the list. Read from the stored `count` where there is one — see below. */
  count: number;
  /**
   * How many boundaries were INVENTED rather than reported. Usually zero, and zero is a measured
   * statement rather than a default.
   *
   * ⚠ IT IS NOT RETROACTIVE, and `cues_payload` says so: a cue list stored before the key existed
   * carries no `estimated` markers and reads as zero whether or not its boundaries were invented.
   * Nothing on either side can tell the two apart, so a surface should print it as a count and never
   * as a guarantee that the rest are exact.
   */
  estimatedCues: number;
  /** The longest end time, in seconds, or null when the payload did not carry one. */
  durationSeconds: number | null;
  /** The language the cues are in, as the run reported it. Null is "nobody detected one". */
  language: string | null;
  /**
   * Whether ANY cue carries a speaker label — and therefore whether `?speakers=` may be offered at
   * all. Asking for labels that do not exist is a 422 by design: *"These subtitles carry no speaker
   * labels, so a file with them in would be the same file without."*
   */
  hasSpeakers: boolean;
  cues: DwSubtitleCue[];
  /** False when this payload is not a `dw.subtitles/1` cue list at all — a client must say so. */
  readable: boolean;
};

const SUBTITLE_PAYLOAD_SCHEMA = "dw.subtitles/1";

/**
 * A stored SUBTITLES payload, as a screen reads it.
 *
 * TOLERATES THE BARE LIST AS WELL AS THE WRAPPED OBJECT, exactly as `subtitles.cues_of_payload`
 * does and for its stated reason: a payload written by an on-device Tier 1 or Tier 2 runner may not
 * have gone through `cues_payload`, and the tiers are allowed to differ in how they produce a thing
 * and are not allowed to differ in what it means.
 *
 * `count` and `estimatedCues` are read from the WRAPPER where there is one rather than recomputed:
 * the server stores them so that a list screen can say "142 cues, 11 of them approximate" without
 * carrying every cue, and a client that recomputed would silently disagree with the annexure the
 * moment a payload was truncated anywhere.
 */
export function subtitleCueSummary(payload: unknown): DwSubtitleSummary {
  const empty: DwSubtitleSummary = {
    count: 0,
    estimatedCues: 0,
    durationSeconds: null,
    language: null,
    hasSpeakers: false,
    cues: [],
    readable: false
  };
  if (payload === null || payload === undefined) return empty;

  const wrapper = !Array.isArray(payload) && typeof payload === "object" ? (payload as Record<string, unknown>) : null;
  const raw = Array.isArray(payload) ? payload : wrapper?.cues;
  if (!Array.isArray(raw)) return empty;

  const cues: DwSubtitleCue[] = [];
  for (const entry of raw) {
    if (!entry || typeof entry !== "object" || Array.isArray(entry)) continue;
    const cue = entry as Record<string, unknown>;
    const start = Number(cue.start);
    const end = Number(cue.end);
    if (!Number.isFinite(start) || !Number.isFinite(end)) continue;
    const speaker = typeof cue.speaker === "string" && cue.speaker.trim() ? cue.speaker.trim() : undefined;
    cues.push({
      start,
      end,
      text: typeof cue.text === "string" ? cue.text : "",
      ...(speaker ? { speaker } : {}),
      ...(cue.estimated === true ? { estimated: true as const } : {})
    });
  }

  const storedCount = Number(wrapper?.count);
  const storedEstimated = Number(wrapper?.estimatedCues);
  const storedDuration = Number(wrapper?.durationSeconds);
  return {
    count: Number.isFinite(storedCount) ? storedCount : cues.length,
    estimatedCues: Number.isFinite(storedEstimated) ? storedEstimated : cues.filter((cue) => cue.estimated).length,
    durationSeconds: Number.isFinite(storedDuration) ? storedDuration : null,
    language: typeof wrapper?.language === "string" && wrapper.language.trim() ? wrapper.language.trim() : null,
    hasSpeakers: cues.some((cue) => Boolean(cue.speaker)),
    cues,
    // A wrapper naming a schema this build does not know is still read for its cues — the shape has
    // one version and a newer one would still be a cue list — but the flag records that this was a
    // recognisable payload at all, which is what a screen needs to say "there is nothing to show".
    readable: cues.length > 0 || (wrapper?.schema === SUBTITLE_PAYLOAD_SCHEMA)
  };
}

/** `01:04:09.480` -> a caption reader's `1:04:09`, or `4:09` under an hour. Seconds are truncated. */
export function subtitleTimecode(seconds: number): string {
  const whole = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(whole / 3600);
  const minutes = Math.floor((whole % 3600) / 60);
  const secs = whole % 60;
  const mm = hours ? String(minutes).padStart(2, "0") : String(minutes);
  return `${hours ? `${hours}:` : ""}${mm}:${String(secs).padStart(2, "0")}`;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The passage under the caret
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The selected text of a rich document, as plain prose.
 *
 * COMPOSED FROM `lib/richText.ts`'S EXISTING EXPORTS AND ADDING NOTHING TO IT. That file is the
 * shared document model three surfaces read and another lane is editing it; a helper added there for
 * one caller here is a merge conflict at best and a second definition of "what is selected" at worst.
 *
 * SELECTION-SCOPED AND NOT FIELD-SCOPED, which is the design decision worth defending. Dictation is
 * field-scoped because you speak into a whole field. A verb cannot be: `MAX_DOCUMENT_CHARS` for a
 * RICH_TEXT field is 200,000 and {@link MAX_VERB_TEXT_CHARS} is 20,000, so a field-level control
 * would routinely be refused on a stage-13 narrative and a designer would learn that the button is
 * broken. A selection cannot do that.
 *
 * A TABLE'S SELECTED BLOCK CONTRIBUTES ITS WHOLE TEXT, via `blockText`. The caret model addresses
 * table cells by block only, so there is no offset within one to slice by; taking the whole block is
 * the honest reading and it is what `blockText` was written for. An IMAGE block contributes nothing,
 * which is right — there are no words in it.
 */
export function selectedPassage(doc: RichDoc, range: DocRange): string {
  const { start, end } = orderedRange(range);
  const lines: string[] = [];
  for (let index = start.block; index <= end.block; index += 1) {
    const block = doc.blocks[index];
    if (!block) continue;
    if (block.kind === "TABLE") {
      lines.push(blockText(block));
      continue;
    }
    if (block.kind === "IMAGE") continue;
    const from = index === start.block ? start.offset : 0;
    const to = index === end.block ? end.offset : blockText(block).length;
    lines.push(
      sliceSpans(block.spans, from, to)
        .map((span) => span.text)
        .join("")
    );
  }
  // Blocks join with a newline, so a selection spanning three paragraphs reaches the model as three
  // paragraphs. Trailing and leading blank lines are trimmed because a selection that starts at the
  // end of one block routinely picks up an empty first line, and a passage beginning with a blank
  // line is a passage whose stored source text is not quite what the designer chose.
  return lines.join("\n").replace(/^\n+/, "").replace(/\n+$/, "");
}

/* ────────────────────────────────────────────────────────────────────────────
 * The four sentences this client authors, because no server ever sends them
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * THE PRE-PRESS STATES. Written ONCE here and read by all three call sites, because the alternative
 * is three paraphrases of one rule — and the cross-surface rule for this feature is that the only
 * strings either client authors are these, and they are transliterated rather than reworded.
 *
 * Every one of them replaces the control's own explanation rather than sitting beside it as a
 * tooltip: `AiLayersPanel`'s rule 3 — *"a control offered into a certain refusal teaches designers
 * that refusals are noise, after which the one that matters is clicked through too"* — applied one
 * screen earlier than that panel applies it.
 */
export const NOTHING_SELECTED =
  "Select the words you want worked on first. These verbs run over a passage rather than over the " +
  "whole field, so what is selected is what is sent — and is what the layer records as its source.";

export function passageTooLong(chars: number): string {
  return (
    `That selection is ${chars.toLocaleString()} characters and at most ` +
    `${MAX_VERB_TEXT_CHARS.toLocaleString()} can be sent. This is a bound on the evidence rather ` +
    `than on the verb: a proofread of the first ten pages of a twelve-page note, recorded as a ` +
    `proofread of the note, is a layer whose source is not what it says. Select a shorter passage.`
  );
}

/**
 * There is no connection, so the verb genuinely cannot happen — AND NOTHING HAS BEEN QUEUED.
 *
 * The second half is the half a designer will otherwise get wrong: every other write in this app
 * banks itself in the outbox and drains later, so silence here invites the reading that the run is
 * waiting to be sent. It is not, and deliberately — see this module's header for the two independent
 * reasons. The last clause exists to stop somebody retyping a passage that is perfectly safe: the
 * words are in the stage draft in IndexedDB and are untouched.
 */
export const VERBS_NEED_A_CONNECTION =
  "These run on the server, so they need a connection. Nothing has been queued for later — a run " +
  "spends real provider credit and counts against today's allowance, so one replayed in three days' " +
  "time would be charged against a day you are not having. Your words are on this device and are " +
  "untouched; reconnect and select the passage again.";

/**
 * The workshop has not been cleared to send anything, so no verb can run — the third pre-press state.
 *
 * **CLIENT-AUTHORED, AND THAT IS THE RULE RATHER THAN AN EXCEPTION TO IT.** Every refusal a designer
 * reads AFTER a press is the server's own words, because a rule with two voices is how a client and
 * a server come to disagree. The four pre-press states are different: the request is never made, so
 * no server ever composes a sentence for them, and the alternative to writing one here is a round
 * trip whose only outcome is a refusal. Written ONCE and read by all three call sites, for the same
 * reason — three paraphrases of one rule is the drift this file exists to avoid.
 *
 * **NOT_RECORDED AND REFUSED GET DIFFERENT SENTENCES, and collapsing them would be the defect
 * `dictation_consent.gate_refusal` keeps two strings apart to avoid**: one is answered by asking the
 * artisan, and telling somebody to go and ask again when the answer is already on record is the sort
 * of instruction that teaches a designer to stop reading these messages.
 *
 * It names the fix rather than "ask an administrator", which would be wrong twice over — the 409 is
 * a 409 and not a 403 precisely because *"what is not in a state to permit the send is the
 * WORKSHOP"*, and the designer reading this is the person who can put it right.
 */
export function consentNotGranted(consent: string | null | undefined): string {
  const token = (consent ?? "").trim().toUpperCase();
  if (token === "REFUSED") {
    return (
      "This workshop's material may not be sent out — that is the answer on record — so nothing " +
      "here can be proofread, written out, translated, described or subtitled. Nothing was sent, " +
      "and your words are exactly as you left them. If the artisan has since agreed, change that " +
      "answer on the workshop's own screen."
    );
  }
  return (
    "Nobody has recorded yet whether material from this workshop may be sent out, so nothing here " +
    "can be proofread, written out, translated, described or subtitled. Open the workshop's own " +
    "screen and record the artisan's answer to that question — until somebody does, this stays " +
    "unavailable."
  );
}

/** A photograph still only on this device has no server id to send, which is a different refusal. */
export const MEDIA_NOT_UPLOADED_YET =
  "This file has not reached the server yet, so there is nothing to send — the verb runs on the " +
  "server's copy. It will go up with the next sync, and you can describe it then.";

/**
 * THE FIFTH PRE-PRESS STATE: the WORKSHOP itself has never reached the server, so no verb has a
 * record to run over. The refusal every other rung in the ladder is structurally unable to make.
 *
 * ── THE DEFECT, WHICH SHIPPED ───────────────────────────────────────────────────────────────────
 * Every verb route is `/design-workshops/{id}/…` and `load_workshop_or_404` finds no row for a
 * `dwlocal-…` id, so each press answered a bare 404 — "Record not found", a sentence written about a
 * missing record rather than about an unsent workshop, naming no next move. The four states above
 * cannot catch it, and the consent rung actively hides it: `useWorkshopConsent` reads the LOCAL
 * draft, and `DictationConsentCard` deliberately supports recording GRANTED on a workshop that has
 * never been up ("Recorded on this device. This workshop has not reached the repository yet, so the
 * answer goes up with it"). So consent reads GRANTED, `blocked` is null, and the control is offered
 * into a certain failure — which is `AiLayersPanel`'s rule 3 broken in the worst available way,
 * because the designer cannot tell a broken feature from something they did wrong.
 *
 * This is the door `DictationButton` closed in this same toolbar, with a comment naming exactly how
 * it was opened: *"the required `workshopId` closed the ungated door and opened this one, because a
 * local id is a perfectly good string."* The verbs walked through it one lane later.
 *
 * ── WHY A SENTENCE AND NOT A HIDDEN CONTROL ─────────────────────────────────────────────────────
 * Same reason as the other four: a control that vanishes teaches nothing, and this state ends on its
 * own within one sync. The designer needs to know the capability exists and what makes it available.
 *
 * ── AND WHY IT SITS UNDER THE CONSENT RUNG RATHER THAN OVER IT ──────────────────────────────────
 * Because it promises that the verbs become available after the next sync, which is true only where
 * sync is the one thing missing. See {@link verbWorkshopRefusal}, which is where the order lives.
 *
 * ── AND WHY IT IS NOT ALSO THE ANSWER FOR A SYNCED WORKSHOP UNDER ITS LOCAL URL ─────────────────
 * A workshop that HAS been up keeps its `dwlocal-…` URL for the rest of the session — the stage page
 * does not redirect — so a gate on the route id alone would withhold working verbs and say something
 * false about a workshop the server holds. The call sites resolve `draft.remoteId` first, through
 * `useWorkshopConsent`'s `serverId`, and reach this sentence only when there genuinely is no server
 * copy.
 */
export const WORKSHOP_NOT_ON_SERVER_YET =
  "This workshop is still only on this device, so there is nothing for a model to read — these run " +
  "on the server's copy and the server has never seen this one. It goes up with the next sync and " +
  "they become available then. Nothing you have written is at risk: it is in the draft here, and " +
  "no run has been queued, because a run spends provider credit against the day it is made.";

/**
 * THE PRE-PRESS LADDER AS A FUNCTION, so the thing that was wrong can be asserted about.
 *
 * ── WHY IT EXISTS ───────────────────────────────────────────────────────────────────────────────
 * Three surfaces computed this ladder inline, in three nested ternaries, and the two properties that
 * matter about it were therefore only checkable by reading all three: that the rungs are in the same
 * ORDER (a workshop-level refusal must not be reported as a consent problem, or vice versa) and that
 * "still reading" is DISTINGUISHABLE from "go ahead". They had already come apart twice — the
 * not-on-the-server rung was missing from all three, and `AiLayersPanel` fed the reading state into
 * a truthiness ternary, where "" is falsy, and rendered live buttons during the IndexedDB read. The
 * mounts are structural and no unit test could reach them; this is the half of the decision that can
 * be a pure function, so it is one.
 *
 * It also stops the ceiling fallback below being a fourth copy of itself. This module's own header
 * states the rule the copies broke: *"the only strings either client authors are these, and they are
 * transliterated rather than reworded"*.
 *
 * ── THE THREE-VALUED ANSWER, WHICH IS THE PART TO GET RIGHT ─────────────────────────────────────
 *   `null` — nothing at the workshop level stands in the way. A caller may go on to its own rungs.
 *   `""`   — the draft is still being read. INERT AND SILENT: the control must be disabled and no
 *            sentence drawn, because the floor answer is NOT_RECORDED and drawing its sentence would
 *            flash "nobody has been asked" on every workshop that has been asked.
 *   a sentence — a refusal to render in place of the control's silence.
 *
 * **A CALLER MUST BRANCH ON `!== null` AND NEVER ON TRUTHINESS.** The empty string is a REFUSAL to
 * proceed that happens to have nothing to say; treating it as "no refusal" is the exact defect
 * described above. Disabling on `refusal !== null` and rendering `{refusal}` gives both properties
 * at once, because React draws an empty string as nothing.
 */
export function verbWorkshopRefusal(state: {
  /** `useWorkshopConsent`'s `ready` — false while the local draft is still being read. */
  ready: boolean;
  /** `useWorkshopConsent`'s `serverId` — null when this workshop has no copy on the server. */
  serverId: string | null;
  /** The consent token off the draft. Never null; the floor is "NOT_RECORDED". */
  decision: string;
}): string | null {
  if (!state.ready) return "";
  if (state.decision.trim().toUpperCase() !== "GRANTED") return consentNotGranted(state.decision);
  /*
    THE NOT-YET-SYNCED RUNG IS *BELOW* THE CONSENT RUNG, WHICH IS THE ORDERING TO THINK ABOUT.

    It still catches the defect, because the defect IS the granted case: consent is read from the
    LOCAL draft and `DictationConsentCard` deliberately supports recording GRANTED on a workshop that
    has never reached the repository, so the consent rung passes and — before this rung existed —
    every press went out as `/design-workshops/dwlocal-…/proofread` and came back a bare 404.

    Above the consent rung it would ALSO catch it, but it would then be shown on workshops where it
    is not the whole truth: this sentence promises that the verbs "become available" after the next
    sync, and on a workshop whose recorded answer is REFUSED they will not. Underneath it, every one
    of the four combinations gets a sentence that is unconditionally true — REFUSED says refused,
    NOT_RECORDED says go and ask, and the sync sentence is drawn only where sync really is the one
    thing missing.
  */
  if (!state.serverId) return WORKSHOP_NOT_ON_SERVER_YET;
  return null;
}

/**
 * Today's ceiling, in the SERVER's words wherever it supplied them — the last rung on all three.
 *
 * The fallback is reached only when a 201 or a 429 moved the numbers without carrying a `refusal`,
 * and it deliberately does not invent the zero-cap case, which is the server's to word. Written once
 * here because it was written three times: `verbsBlocked`, `AiVerbSelectionMenu` and `MediaAiVerbs`
 * each carried their own copy of the same forty words, which is how one rule acquires three voices.
 */
export function verbAllowanceRefusal(allowance: DwAiVerbAllowanceState | null | undefined): string | null {
  if (!aiVerbsSpent(allowance)) return null;
  return (
    allowance?.refusal ??
    "Today's runs of the writing and captioning models are used up on this account. Dictation has " +
      "its own separate allowance and is unaffected."
  );
}

/**
 * The one target language the server refuses, with its own reasoning rather than a regex.
 *
 * `_check_languages` refuses a translation INTO `multi` because a target language is a CHOICE the
 * caller makes and not an observation; `multi` remains a perfectly real SOURCE language, since these
 * interviews code-switch mid-sentence. Returned as null when the value is acceptable, so a caller
 * can use it directly as the field's refusal.
 */
export function translationTargetRefusal(value: string): string | null {
  const token = value.trim();
  if (!token) return "Name the language to translate into — a name or a code, such as “Odia”, “or” or “English”.";
  if (token.toLowerCase() === "multi") {
    return (
      "“multi” is something a recording can BE, not something a translation can be INTO. It is a " +
      "real answer for the language a passage came FROM — these interviews code-switch mid-sentence " +
      "— but a target is a choice somebody makes. Name the one language you want it in."
    );
  }
  if (token.length > MAX_VERB_LANGUAGE_CHARS) {
    return `A language name here is at most ${MAX_VERB_LANGUAGE_CHARS} characters — “Odia (Kalahandi dialect)” fits comfortably.`;
  }
  return null;
}

/**
 * The sentence to show when one of these six calls fails.
 *
 * DELIBERATELY `aiLayerProblem` AND NOT A SECOND IMPLEMENTATION. It already handles the three cases
 * that matter and each was a defect once: `ApiUnconfiguredError` (a 503 no server sent, whose
 * message names the administrator action), a body-less 502 from a proxy that must never surface as
 * "HTTP 502", and the offline sentence. Re-exported rather than wrapped so there is one function and
 * one set of words — see `lib/aiLayers.ts`'s note on giving one rule two voices.
 */
export { aiLayerProblem } from "@/lib/aiLayers";

/**
 * Is this failure "there is no connection", as opposed to a refusal the server actually made?
 *
 * `isUnreachable` and NOT `isTransient`, which is `aiLayerProblem`'s own distinction: the latter
 * counts every 5xx as "try again later", so telling a designer their connection is at fault when the
 * SERVER answered sends them to look at their signal while the real fault sits in a response nobody
 * sees. `ApiUnconfiguredError` is excluded first because it is a 503 no server ever sent — the
 * request was never made, and its own message names the administrator action that fixes it.
 */
export function isVerbOffline(error: unknown): boolean {
  if (error instanceof ApiUnconfiguredError) return false;
  if (error instanceof ApiError) return error.status === 408;
  return isUnreachable(error);
}
