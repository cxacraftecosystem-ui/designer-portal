/**
 * The layering law on the web: what a model produced, what it was produced FROM, and who signed it.
 *
 * The law itself is `backend/app/services/ai_layers.py` and `docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md`
 * §3. Nothing is re-decided here. This module is the browser's half of five routes and the plain
 * words the screen says them in.
 *
 * WHY THIS IS ITS OWN MODULE AND NOT A SECTION OF `designWorkshops.ts`, where `dwTranscripts` /
 * `DwTranscriptList` live. That file's own header states what it is: the registry client — 22 stages,
 * 43 entities, 496 typed fields — plus the cache that keeps the registry off the wire. The transcript
 * list belongs there because it is ONE GET with no vocabulary of its own. This is not that: it is five
 * calls, a seven-member kind vocabulary, a three-member tier vocabulary, a chain-builder and the
 * plain-English rendering of every one of them, and every screen that touches provenance has to agree
 * about all of it. The server keeps the law in one module for the same reason and says so in its
 * docstring; a mirror scattered through a 1,500-line registry client is a mirror that drifts. The
 * fetch functions live here WITH their types rather than being split across two files, because the
 * refusals these five routes produce are part of the vocabulary — see {@link aiLayerProblem}.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * EVERY KEY BELOW WAS READ OFF `ai_layers.layer_payload` AND `ai_layers.decision_payload`, NOT
 * REMEMBERED. This file exists downstream of a defect that must not happen twice: `DwIdentityOcrResult`
 * once declared `number`, `documentType`, `name`, `confidence` and `message`, and the endpoint had
 * never sent one of them. Because decoding JSON ignores unknown keys, nothing threw — a PERFECT read
 * of an identity card was reported to the designer as "No number could be read from that photograph",
 * and Android's DTO carried the identical bug against the identical payload. So: `modelId` not
 * `model`, `textChars` not `chars`, `preview` not `snippet`, `textWithheld` not `withheld`,
 * `acceptedById` not `acceptedBy`, `source` is an OBJECT `{kind, id}` and not a pair of flat ids, and
 * the decision log's actor key is `actorId` while a layer's are `createdById` / `acceptedById`.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * THREE RULES THIS MODULE IS WRITTEN TO KEEP, each with the failure it prevents.
 *
 * 1. **"UNRECORDED" IS A REAL VALUE AND IS NEVER DRESSED UP.** `provider` and `modelId` are NOT NULL
 *    on the server and legitimately hold the literal string `UNRECORDED`, because
 *    `media_queue._transcript_write` has never stored which of the four providers in `services/ai.py`
 *    produced a transcript. That is the COMMON case on day one, not an edge one. Printing it as blank
 *    would read as "there is nothing here"; printing a guessed provider name would be a fabricated
 *    provenance record, which is the one thing this whole table exists to prevent. It is rendered as
 *    the words "not recorded" — see {@link layerProvenance} — and never as an error.
 * 2. **THE TIER IS SAID AS WHAT IT MEANS, ALWAYS, NEVER BEHIND A HOVER.** Plan §2.1: without the tier
 *    on the page a cloud-diarized interview and a device-guessed one look alike, and a fleet running
 *    different tiers produces two classes of record nobody can tell apart. `TIER_3` is not a number to
 *    compare — Tier 1 is the only tier that works with no signal and Tier 3 is the only one with the
 *    craft keyterm list, so "higher is better" is false in both directions. Hence
 *    {@link tierLabel} / {@link tierSentence} and no numeral anywhere on screen.
 * 3. **NO AI-PRODUCED VALUE MAY FEED A FORM FIELD.** There is deliberately NO exported helper here
 *    that writes a layer's text or payload into a `DwEntryData`, a stage entry or any field, and
 *    nothing that returns one shaped to be assigned into one. Plan §3: AI output cannot have the
 *    cross-surface agreement property the rest of this record has — the same audio through Tier 1 on a
 *    phone and Tier 3 in the cloud differs legitimately and for ever — so it is annexure content and a
 *    suggestion, never a value compared between the handset and the server and never a derived field.
 *    The server makes this true by construction (`LayerWritePlan` refuses to name `DwStageEntry`);
 *    this side keeps it true by having nothing to call. **Do not add an "apply to the stage" or "copy
 *    into this field" helper here or in the panel.** If one is ever genuinely wanted it is a change to
 *    the plan first, not a convenience function.
 */

import { ApiError, ApiUnconfiguredError, apiFetch, buildQuery } from "@/lib/api";
import { isUnreachable } from "@/lib/offline";

/* ────────────────────────────────────────────────────────────────────────────
 * The vocabulary — mirrors the enums in app/services/ai_layers.py
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Which rung of which chain a layer occupies:
 *
 * ```
 * audio ──▶ RAW_TRANSCRIPT ──▶ CLEANED_TRANSCRIPT ──▶ SUMMARY
 * photo ──▶ OCR_TEXT       ──▶ STRUCTURED_TEXT
 * (either text rung) ──▶ TAGS, METADATA
 * ```
 */
export type DwAiLayerKind =
  | "RAW_TRANSCRIPT"
  | "CLEANED_TRANSCRIPT"
  | "SUMMARY"
  | "OCR_TEXT"
  | "STRUCTURED_TEXT"
  | "TAGS"
  | "METADATA";

/** Which MACHINE produced it. Not a ranking — see rule 2 in the header. */
export type DwAiTier = "TIER_1" | "TIER_2" | "TIER_3";

/** The two things a layer can derive from, and there is no third. */
export type DwAiSourceKind = "MEDIA" | "LAYER";

export type DwAiDecisionKind = "ACCEPTED" | "WITHDRAWN";

/**
 * What `provider` and `modelId` hold when nobody wrote them down, spelled out in that word.
 *
 * Compared case-insensitively by {@link isUnrecorded} because both spellings mean the same thing and
 * the cost of getting it wrong is asymmetric: a stray case difference would print the bare token
 * `UNRECORDED` to a designer, which reads like a code and not like an answer.
 */
export const UNRECORDED = "UNRECORDED";

/** `MAX_LAYER_NOTE_CHARS` in `backend/app/schemas/design_workshops.py`. Kept in step by hand. */
export const MAX_AI_LAYER_NOTE_CHARS = 500;

/* ────────────────────────────────────────────────────────────────────────────
 * The wire
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Where a layer came from: EITHER a `MediaFile` OR another layer, never both and never neither.
 *
 * NULL IS A REAL ANSWER AND MEANS SOMETHING SPECIFIC. `layer_payload` catches `LayerRuleViolation`
 * from `source_of`, logs it and serialises `source: null` rather than dropping the row, precisely so
 * that one malformed row (a hand-run backfill, a database restored from before the CHECK constraint)
 * does not empty a screen holding twenty-four other transcripts. A client that assumed this was
 * always present would crash on exactly that row.
 */
export type DwAiLayerSource = {
  /** Widened with `string` deliberately — see the note on {@link DwAiLayer.kind}. */
  kind: DwAiSourceKind | string;
  id: string;
};

/**
 * One layer, exactly as `ai_layers.layer_payload` serialises it.
 *
 * `kind`, `tier` and a decision's `decision` are typed as `<union> | string` — the same shape
 * `DwSummary.status` uses for `DwStatus` — because they arrive through `_enum_str`, which stringifies
 * whatever the column holds. A server one release ahead of this client can legitimately send a kind
 * this build has never heard of, and the honest response is an unfamiliar row rendered with a note
 * saying so (Android's `UNSUPPORTED_SECTIONS` precedent), never a blank and never a crash. Every
 * label function here has that fallback.
 */
export type DwAiLayer = {
  id: string;
  designWorkshopId: string;
  kind: DwAiLayerKind | string;
  tier: DwAiTier | string;
  source: DwAiLayerSource | null;
  /** NOT NULL on the server; may be the literal {@link UNRECORDED}. Never render it raw. */
  provider: string;
  /** NOT NULL on the server; may be the literal {@link UNRECORDED}. Never render it raw. */
  modelId: string;
  /** The checkpoint or dated version where the provider publishes one. Null = nobody wrote it down. */
  modelVersion: string | null;
  /** May be the literal `multi`, which is a real answer and not a placeholder — see `tierSentence`. */
  language: string | null;
  /**
   * When the MODEL ran, if anybody recorded it. Deliberately NOT defaulted to the row's own creation
   * time on the server: a transcript the queue produced last March and registered today would
   * otherwise carry today's date as a statement about when the model ran.
   */
  producedAt: string | null;
  /** When the ROW appeared. A different question from `producedAt`, and always answerable. */
  createdAt: string | null;
  createdById: string | null;
  /**
   * Rule 3 on the wire, as an explicit boolean beside the nullable timestamp — so a client renders
   * "not accepted" from a stated fact rather than inferring it from a null that could equally mean
   * "accepted, timestamp missing". Only one of those two may be printed in a report.
   */
  accepted: boolean;
  acceptedAt: string | null;
  /** A user id and NOT a name; the endpoint resolves no accounts. See `acceptorText` in the panel. */
  acceptedById: string | null;
  /** Null rather than 0 when the text is withheld: 0 would say "there is nothing to read". */
  textChars: number | null;
  /** Up to `PREVIEW_CHARS` (280) of the opening, whitespace collapsed onto one line. */
  preview: string | null;
  /** The principal content of TAGS / METADATA / STRUCTURED_TEXT. See {@link readAiPayload}. */
  payload: unknown;
  /**
   * This account may not read the CONTENT of the recording at the foot of this layer's chain, so
   * `preview`, `textChars`, `payload` and `text` are all withheld — the provenance is not.
   *
   * ON EVERY PAYLOAD RATHER THAN ONLY THE WITHHELD ONES, deliberately, so the screen says "you cannot
   * read this one" from a stated fact instead of inferring it from an empty preview. Who may read a
   * transcript is decided PER MEDIA FILE (`owned_or_granted_where(user, owner_field="uploadedById")`)
   * and not by who may open the workshop — a `DesignWorkshopViewer` grant carries read and stage
   * writes and says nothing about media, so the two sets genuinely differ.
   */
  textWithheld: boolean;
  /** A soft delete: a declined suggestion stays on record as a declined suggestion. */
  deletedAt: string | null;
  /** Present only when the list was asked for `includeText` AND the text is not withheld. */
  text?: string | null;
};

export type DwAiLayerList = {
  items: DwAiLayer[];
  total: number;
  /**
   * How many of the rows in `items` are accepted AND NOT DECLINED — which is not the same as
   * counting the accepted rows on screen. The two differ the moment `includeDeleted` is on: a layer
   * accepted and afterwards declined still carries its `acceptedAt` (deletion clears no acceptance,
   * because the acceptance a document already printed is not rewritten by a later change of mind)
   * and the report will not print it, so it is in `items` and out of this count.
   *
   * ⚠ IT IS NARROWED BY `kind` LIKE THE LIST ITSELF, which an earlier draft of this comment denied
   * by calling it "what `accepted_layers` would return for this workshop". The route computes it
   * from the same filtered rows it serialises (`design_workshops.list_ai_layers`), so asking for one
   * kind gives the accepted count OF THAT KIND. It equals the workshop's total only when no `kind`
   * is sent — which is what the panel does, and why the label beside it says "accepted" flat.
   */
  accepted: number;
};

export type DwAiDecisionRecord = {
  id: string;
  layerId: string;
  decision: DwAiDecisionKind | string;
  note: string | null;
  /** A user id, not a name. */
  actorId: string;
  createdAt: string | null;
};

/** The response of accept and of withdraw: the layer's new state, and its whole history. */
export type DwAiDecisionResult = { layer: DwAiLayer; decisions: DwAiDecisionRecord[] };

/**
 * The body of `POST /design-workshops/{id}/ai-layers`.
 *
 * **THERE IS NO `text` FIELD AND THERE MUST NEVER BE ONE.** The content is read by the server from
 * `MediaFile.transcriptText` — the transcript its own provider chain produced — and never from the
 * request. A `text` field would turn this endpoint into a way for any client to post model prose into
 * a workshop record under a provenance of its own choosing, which is the exact inverse of what the
 * layering law is for. `kind` and `tier` are absent for the same reason and are fixed by the route: a
 * body that could claim TIER_1 for cloud output would make the tier column worthless to the reviewer
 * it exists for.
 *
 * `provider` and `modelId` are optional here and REQUIRED in the service — not a contradiction: the
 * route substitutes the literal {@link UNRECORDED} in one place when they are absent. The other three
 * (`modelVersion`, `language`, `producedAt`) are optional on both sides and are stored as null when
 * they are missing, because a version nobody recorded is not a version. See the note in the panel for
 * why the surface offers none of the five to a designer.
 */
export type DwAiLayerRegisterBody = {
  sourceMediaId: string;
  provider?: string;
  modelId?: string;
  modelVersion?: string;
  language?: string;
  /** ISO 8601. A value that cannot be parsed is REFUSED, not quietly dropped. */
  producedAt?: string;
};

/**
 * One register call may return TWO rows, and that is the ordinary case rather than the exception.
 *
 * `AppSetting.transcriptionMode` defaults to REFINED_TRANSLATED, under which `media_queue` passes the
 * provider's text through `ai.refine_transcript_text` — a rewrite, and a translation into English —
 * storing the rewritten form in `transcriptText` and the provider's own words in `transcriptSummary`.
 * `ai_layers.transcript_rungs` splits the two apart on the evidence in the row itself, so a default
 * deployment produces a RAW_TRANSCRIPT and a CLEANED_TRANSCRIPT derived from it.
 */
export type DwAiLayerRegisterResult = { items: DwAiLayer[]; total: number };

/* ────────────────────────────────────────────────────────────────────────────
 * The five calls
 * ──────────────────────────────────────────────────────────────────────────── */

export type DwAiLayerListParams = {
  kind?: DwAiLayerKind | null;
  /**
   * Carry every layer's full text. OFF by default and it must stay that way for a list: a workshop
   * can hold twenty-five interviews, an hour of speech is tens of kilobytes, and the whole list would
   * be megabytes on one bar of signal — re-sent every time the screen opened, and unread, because a
   * list is scanned by its titles.
   *
   * ⚠ IT IS ALL-OR-NOTHING. There is no single-layer read on this server, so asking for one layer's
   * text asks for every layer's. The panel therefore fetches it once, on an explicit press, rather
   * than per row.
   */
  includeText?: boolean;
  /** Include layers a designer declined. Off by default — a declined suggestion re-offered is the
   *  same suggestion — but they are kept, so "a model proposed this and a person said no" survives. */
  includeDeleted?: boolean;
};

export function listDesignWorkshopAiLayers(id: string, params: DwAiLayerListParams = {}) {
  return apiFetch<DwAiLayerList>(
    // `buildQuery` takes no booleans and drops "" exactly as it drops null, so both flags are spelled
    // out as the literal "true" and omitted entirely when they are off.
    `/design-workshops/${id}/ai-layers${buildQuery({
      kind: params.kind ?? undefined,
      includeText: params.includeText ? "true" : undefined,
      includeDeleted: params.includeDeleted ? "true" : undefined
    })}`
  );
}

export function registerDesignWorkshopAiLayer(id: string, body: DwAiLayerRegisterBody) {
  return apiFetch<DwAiLayerRegisterResult>(`/design-workshops/${id}/ai-layers`, {
    method: "POST",
    body: JSON.stringify(body)
  });
}

/**
 * A person puts their name to this layer.
 *
 * REFUSED FOR A LAYER ALREADY ACCEPTED (409), because a second acceptance would overwrite the first
 * acceptor's name — the one fact about an accepted layer a reader of a submitted report may need to
 * trace. The surface must therefore not offer this action for an accepted layer at all; it offers
 * withdraw instead. Refused with a 403 for an account that may not read the recording the layer
 * stands on, because an acceptance is a statement that somebody read this text and the report prints
 * their name beside it.
 */
export function acceptDesignWorkshopAiLayer(id: string, layerId: string, note?: string | null) {
  return apiFetch<DwAiDecisionResult>(`/design-workshops/${id}/ai-layers/${layerId}/accept`, {
    method: "POST",
    body: JSON.stringify({ note: (note ?? "").trim() || null })
  });
}

/**
 * A person takes their name off. The layer itself is untouched and stays readable, and the decision
 * LOG is not cleared: a report generated while the layer was accepted named it as accepted, and that
 * document does not change because somebody changed their mind afterwards.
 *
 * DELIBERATELY NOT GATED ON THE MEDIA PERMISSION, unlike accept. Taking a name back off states
 * nothing about the text and must stay reachable whatever has happened to the recording's permissions
 * since — otherwise a grant withdrawn between the acceptance and the doubt traps an accepted layer in
 * a report with nobody able to unaccept it.
 */
export function unacceptDesignWorkshopAiLayer(id: string, layerId: string, note?: string | null) {
  return apiFetch<DwAiDecisionResult>(`/design-workshops/${id}/ai-layers/${layerId}/unaccept`, {
    method: "POST",
    body: JSON.stringify({ note: (note ?? "").trim() || null })
  });
}

/**
 * Decline a layer. Soft, and it does not touch what the layer was made from (rule 5).
 *
 * A 409 WHEN LIVE LAYERS DERIVE FROM THIS ONE — deleting a raw transcript out from under a cleaned
 * one would leave the cleaned one describing something no screen will show. The server's refusal is a
 * sentence naming the kinds; show it, never the status code.
 *
 * Answers 204, so `apiFetch` resolves `undefined`. A repeated DELETE 404s rather than resurrecting:
 * the second call cannot find the row, which is what stops a second deletion stamp being written over
 * the first and losing when it was actually declined.
 */
export function deleteDesignWorkshopAiLayer(id: string, layerId: string) {
  return apiFetch<void>(`/design-workshops/${id}/ai-layers/${layerId}`, { method: "DELETE" });
}

/* ────────────────────────────────────────────────────────────────────────────
 * Plain words — the whole point of the screen
 * ──────────────────────────────────────────────────────────────────────────── */

/** True when a provenance column holds the honest-unknown token rather than a real answer. */
export function isUnrecorded(value: string | null | undefined): boolean {
  return (value ?? "").trim().toUpperCase() === UNRECORDED;
}

const KIND_LABELS: Record<DwAiLayerKind, string> = {
  RAW_TRANSCRIPT: "Machine transcript",
  CLEANED_TRANSCRIPT: "AI-cleaned transcript",
  SUMMARY: "AI summary",
  OCR_TEXT: "Text read off a photograph",
  STRUCTURED_TEXT: "Fields read off a photograph",
  TAGS: "Suggested tags",
  METADATA: "Extracted details"
};

/**
 * What each rung actually IS, in the words a designer would use.
 *
 * "Machine transcript" rather than the bare "Transcript" for RAW_TRANSCRIPT: a workshop also holds
 * transcripts a person typed or corrected through `POST /media/{id}/transcript`, and a heading that
 * did not distinguish them would let model output be read as somebody's own words — which is exactly
 * the failure rule 4 exists to prevent, one heading earlier than the report.
 */
export function layerKindLabel(kind: string | null | undefined): string {
  const known = KIND_LABELS[(kind ?? "") as DwAiLayerKind];
  if (known) return known;
  // A kind this build has never heard of degrades to an honest note carrying the server's own word,
  // never to a blank. The row still shows its tier, its model and its acceptance — all of which are
  // readable without knowing what the kind means.
  return kind ? `A layer kind this screen does not know (${kind})` : "A layer with no kind recorded";
}

/**
 * A layer's kind as a NOUN PHRASE that can sit inside a sentence — "Accept this machine transcript in
 * your name?", "Produced from the machine transcript above it."
 *
 * SEPARATE FROM {@link layerKindLabel} BECAUSE THAT ONE DEGRADES TO A WHOLE SENTENCE, and a sentence
 * cannot be embedded in another one. `layerKindLabel("DIARIZATION")` is "A layer kind this screen does
 * not know (DIARIZATION)", which a caller interpolating it produced as "Accept this a layer kind this
 * screen does not know (DIARIZATION) in your name?" — a confirm dialog asking somebody to sign, in
 * broken English, which is exactly the moment to be readable. An unfamiliar kind is called a "layer"
 * here: true of every row in this table, and the row's own heading already carries the server's word
 * for it, so nothing is hidden by declining to guess twice.
 */
export function layerKindNoun(kind: string | null | undefined): string {
  const known = KIND_LABELS[(kind ?? "") as DwAiLayerKind];
  return known ? known.toLowerCase() : "layer";
}

const KIND_NOTES: Record<DwAiLayerKind, string> = {
  RAW_TRANSCRIPT: "The words a transcription model produced from the recording, as it produced them.",
  CLEANED_TRANSCRIPT:
    "Something rewrote the transcript into a readable dialogue. On a default deployment that rewrite " +
    "also translates it into English, so this may not be the language the artisan spoke — and the row " +
    "records no author, because a transcript a person corrected by hand arrives in exactly this shape.",
  SUMMARY: "Prose a model wrote about the transcript. It is the model's words, not the artisan's.",
  OCR_TEXT: "The text a model read off the photograph.",
  STRUCTURED_TEXT: "Named fields a model picked out of the text read off the photograph.",
  TAGS: "Tags a model suggested from the text — a suggestion to accept or to decline.",
  METADATA: "Details a model picked out of the text."
};

/** The sentence under a kind's heading, or null for a kind this build does not know. */
export function layerKindNote(kind: string | null | undefined): string | null {
  return KIND_NOTES[(kind ?? "") as DwAiLayerKind] ?? null;
}

/**
 * THE TIER, SAID AS WHAT IT MEANS. Short enough for a chip that is always on screen.
 *
 * No numeral, deliberately. `AiTier.number` exists on the server "for prose only, never for a
 * comparison", and a chip reading "Tier 3" invites exactly the comparison the enum was chosen to
 * prevent: Tier 1 is the only tier that works in a courtyard with no signal and Tier 3 is the only one
 * carrying the craft keyterm list, so neither direction is "better".
 */
const TIER_LABELS: Record<DwAiTier, string> = {
  TIER_1: "On the handset",
  TIER_2: "On the handset, small model",
  TIER_3: "In the cloud"
};

const TIER_SENTENCES: Record<DwAiTier, string> = {
  TIER_1:
    "Produced by a model running on the device itself. This is the only tier that works in a courtyard " +
    "with no signal, and the recording never left the handset.",
  TIER_2:
    "Produced by a small language model running on the handset. Nothing left the device, and what a " +
    "given handset can run depends on the handset.",
  TIER_3:
    "Produced by a provider in the cloud. This is the only tier that carries the craft vocabulary — " +
    "the list that stops “dabu” being written as “double” — and the audio left the device to reach it."
};

export function tierLabel(tier: string | null | undefined): string {
  const known = TIER_LABELS[(tier ?? "") as DwAiTier];
  if (known) return known;
  return tier ? `An unfamiliar tier (${tier})` : "Tier not recorded";
}

export function tierSentence(tier: string | null | undefined): string {
  const known = TIER_SENTENCES[(tier ?? "") as DwAiTier];
  if (known) return known;
  return (
    "This server recorded a tier this screen does not know, so where the model ran cannot be stated " +
    "in words here. The stored value is shown as it was sent."
  );
}

export function decisionLabel(decision: string | null | undefined): string {
  if (decision === "ACCEPTED") return "Accepted";
  if (decision === "WITHDRAWN") return "Acceptance withdrawn";
  return decision ? `An unfamiliar decision (${decision})` : "A decision with nothing recorded";
}

/**
 * The provenance of one layer as four honest strings.
 *
 * `UNRECORDED` becomes the words "not recorded" and never a blank, an em dash or a guess. `unrecorded`
 * is returned beside them so the surface can style the whole line as a quiet statement of fact rather
 * than as a fault — a row whose provider nobody stored is the ordinary case for every transcript this
 * system has ever produced, and drawing it in an error colour would tell a designer that their archive
 * is broken.
 */
export type DwAiProvenance = {
  provider: string;
  /** The model id, with its version appended where one was recorded. */
  model: string;
  language: string;
  /** True when NEITHER the provider nor the model was recorded — i.e. nothing about the run is known. */
  unrecorded: boolean;
};

export function layerProvenance(layer: DwAiLayer): DwAiProvenance {
  const providerMissing = isUnrecorded(layer.provider) || !(layer.provider ?? "").trim();
  const modelMissing = isUnrecorded(layer.modelId) || !(layer.modelId ?? "").trim();
  const version = (layer.modelVersion ?? "").trim();
  const language = (layer.language ?? "").trim();
  return {
    provider: providerMissing ? "not recorded" : layer.provider.trim(),
    model: modelMissing ? "not recorded" : version ? `${layer.modelId.trim()} · ${version}` : layer.modelId.trim(),
    // "multi" is a real answer and not a placeholder: Deepgram Nova-3 is called with `language=multi`
    // precisely because a workshop is Hindi code-switched with English mid-sentence. Printing it as
    // the bare token would read like a missing value, which is the opposite of what it says.
    language: !language ? "not recorded" : language === "multi" ? "multi — mixed, code-switched speech" : language,
    unrecorded: providerMissing && modelMissing
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The chain — a cleaned transcript is shown BENEATH the raw one it was made from
 * ──────────────────────────────────────────────────────────────────────────── */

/** One layer and everything produced from it. `depth` 0 stands on the recording itself. */
export type DwAiLayerNode = { layer: DwAiLayer; depth: number; children: DwAiLayerNode[] };

/** Every chain standing on one recording. Two chains share a group when two models read one clip. */
export type DwAiLayerGroup = { mediaId: string; roots: DwAiLayerNode[] };

/**
 * Why a layer could not be placed in a chain. Each gets its own sentence on screen.
 *
 * `UNKNOWN_SOURCE` is the forward-compatibility one and it is not hypothetical: {@link DwAiLayerSource}
 * widens `kind` with `string` deliberately, so a server one release ahead can send a third source kind
 * this build has never heard of. Without a reason of its own such a row fell through to `CIRCULAR` and
 * the screen told a designer "this layer's chain leads back to itself" — a diagnosis of a defect that
 * does not exist, about a row that is perfectly well formed, which is the fabricated-fact failure this
 * whole feature is built to prevent, committed by the client instead of by a model.
 */
export type DwAiUnrootedReason = "NO_SOURCE" | "SOURCE_NOT_LISTED" | "CIRCULAR" | "UNKNOWN_SOURCE";

export type DwAiUnrooted = { node: DwAiLayerNode; reason: DwAiUnrootedReason };

export type DwAiLayerGrouping = {
  recordings: DwAiLayerGroup[];
  /**
   * Layers that could not be attached to a recording. NEVER DROPPED, and that is the point: a list
   * that quietly stops is indistinguishable from a workshop with nothing in it, which is the single
   * most repeated defect class in this repository. Each one carries the reason it could not be placed.
   */
  unrooted: DwAiUnrooted[];
};

/** Oldest first, so a chain reads downward in the order it was produced. Nulls sort last. */
function byCreatedAtAsc(a: DwAiLayer, b: DwAiLayer): number {
  if (a.createdAt === b.createdAt) return a.id < b.id ? -1 : 1;
  if (!a.createdAt) return 1;
  if (!b.createdAt) return -1;
  return a.createdAt < b.createdAt ? -1 : 1;
}

function newestCreatedAt(node: DwAiLayerNode): string {
  const own = node.layer.createdAt ?? "";
  return node.children.reduce((latest, child) => {
    const childLatest = newestCreatedAt(child);
    return childLatest > latest ? childLatest : latest;
  }, own);
}

/**
 * Group a workshop's layers so the CHAIN is visible: a cleaned transcript is a CHILD of the raw one
 * it was made from, not a sibling in a flat list.
 *
 * WHY A FLAT LIST WOULD BE WRONG RATHER THAN MERELY PLAINER. The chain is the evidence: an annexure
 * that prints a conclusion has to be able to show what it was drawn from, and the person deciding
 * whether to accept a summary needs the transcript it was summarised FROM in front of them. Sorted by
 * date alone, one recording's raw and cleaned rungs land beside a different recording's, and the
 * reader has to reconstruct the chain from a column of cuids.
 *
 * TWO RECORDINGS' WORTH OF CHAINS ARE NOT MERGED, BUT TWO CHAINS OVER ONE RECORDING ARE GROUPED. That
 * grouping is the whole point of plan §2.1: a phone-produced and a cloud-produced transcript of the
 * same clip must both exist and be tellable apart ON THE PAGE, which means side by side under one
 * heading rather than scattered through a date-ordered list.
 *
 * TERMINATES ON A CYCLIC ROW. `sourceLayerId` is a self-referential foreign key and Postgres is
 * perfectly happy with a row naming itself, or with two rows naming each other. Nothing in the API can
 * write one, but a hand-run backfill can — the server bounds its own walk at `MAX_CHAIN_HOPS` for the
 * same reason. Here a `visited` set does it, and anything left unvisited is reported rather than lost.
 */
export function groupAiLayers(items: DwAiLayer[]): DwAiLayerGrouping {
  const byId = new Map(items.map((layer) => [layer.id, layer]));
  const childrenOf = new Map<string, DwAiLayer[]>();
  const mediaRooted: DwAiLayer[] = [];

  for (const layer of items) {
    const source = layer.source;
    if (source && source.kind === "LAYER" && byId.has(source.id)) {
      const siblings = childrenOf.get(source.id);
      if (siblings) siblings.push(layer);
      else childrenOf.set(source.id, [layer]);
    } else if (source && source.kind === "MEDIA") {
      mediaRooted.push(layer);
    }
  }
  for (const siblings of childrenOf.values()) siblings.sort(byCreatedAtAsc);

  const visited = new Set<string>();
  const build = (layer: DwAiLayer, depth: number): DwAiLayerNode => {
    visited.add(layer.id);
    const children: DwAiLayerNode[] = [];
    for (const child of childrenOf.get(layer.id) ?? []) {
      if (visited.has(child.id)) continue;
      children.push(build(child, depth + 1));
    }
    return { layer, depth, children };
  };

  const groups = new Map<string, DwAiLayerNode[]>();
  for (const layer of mediaRooted.slice().sort(byCreatedAtAsc)) {
    if (visited.has(layer.id)) continue;
    const mediaId = layer.source?.id ?? "";
    const roots = groups.get(mediaId);
    const node = build(layer, 0);
    if (roots) roots.push(node);
    else groups.set(mediaId, [node]);
  }

  const unrooted: DwAiUnrooted[] = [];
  for (const layer of items) {
    if (visited.has(layer.id)) continue;
    const source = layer.source;
    // Ordered so that the only rows reaching CIRCULAR are the ones that genuinely are: a source kind
    // outside the vocabulary is caught first, because "the chain leads back to itself" would be a
    // confident wrong answer about a row a newer server sent in good faith.
    const reason: DwAiUnrootedReason = !source
      ? "NO_SOURCE"
      : source.kind !== "MEDIA" && source.kind !== "LAYER"
        ? "UNKNOWN_SOURCE"
        : source.kind === "LAYER" && !byId.has(source.id)
          ? "SOURCE_NOT_LISTED"
          : "CIRCULAR";
    unrooted.push({ node: build(layer, 0), reason });
  }

  // Newest recording first, matching the order the endpoint returns rows in — a designer opening this
  // screen has just made a recording, and it should not be at the bottom.
  const recordings = [...groups.entries()]
    .map(([mediaId, roots]) => ({
      mediaId,
      roots: roots.slice().sort((a, b) => (newestCreatedAt(a) > newestCreatedAt(b) ? -1 : 1))
    }))
    .sort((a, b) => {
      const left = a.roots.length ? newestCreatedAt(a.roots[0]) : "";
      const right = b.roots.length ? newestCreatedAt(b.roots[0]) : "";
      if (left === right) return a.mediaId < b.mediaId ? -1 : 1;
      return left > right ? -1 : 1;
    });

  return { recordings, unrooted };
}

/** Every layer in a chain, parents before children — for counting and for flat rendering. */
export function flattenAiLayerNodes(nodes: DwAiLayerNode[]): DwAiLayerNode[] {
  const out: DwAiLayerNode[] = [];
  const walk = (node: DwAiLayerNode) => {
    out.push(node);
    node.children.forEach(walk);
  };
  nodes.forEach(walk);
  return out;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The structured payload
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `payload` in a shape a panel can draw, without guessing at what a future model will put there.
 *
 * TAGS and METADATA carry their principal content here rather than in `text`, so a row of them
 * rendered as nothing would look empty in exactly the list a designer is scanning to decide what to
 * accept. Nothing in this repository writes a payload YET — generation is steps 7 and 8 of the plan —
 * so this deliberately handles the three shapes an extractive verb can plausibly produce and prints
 * anything else as the stored JSON rather than pretending to understand it.
 */
export type DwAiPayloadView =
  | { shape: "TAGS"; tags: string[] }
  | { shape: "ROWS"; rows: { key: string; value: string }[] }
  | { shape: "JSON"; json: string; truncated: boolean }
  | null;

/** Beyond this, the raw JSON is cut — and the surface must SAY that it was cut. */
export const AI_PAYLOAD_JSON_CHARS = 4000;

export function readAiPayload(payload: unknown): DwAiPayloadView {
  if (payload === null || payload === undefined) return null;
  if (Array.isArray(payload) && payload.length && payload.every((entry) => typeof entry === "string")) {
    return { shape: "TAGS", tags: payload as string[] };
  }
  if (!Array.isArray(payload) && typeof payload === "object") {
    const entries = Object.entries(payload as Record<string, unknown>);
    const scalar = entries.every(
      ([, value]) => value === null || ["string", "number", "boolean"].includes(typeof value)
    );
    if (entries.length && scalar) {
      return {
        shape: "ROWS",
        // A null VALUE inside a payload is the model having nothing for that field, which is the same
        // honest-unknown as an unrecorded provider and gets the same words rather than an empty cell.
        rows: entries.map(([key, value]) => ({ key, value: value === null ? "not recorded" : String(value) }))
      };
    }
  }
  let json: string;
  try {
    json = JSON.stringify(payload, null, 2) ?? String(payload);
  } catch {
    // A payload with a cycle in it cannot come off the wire, but it can be handed here by a caller
    // holding something else. A crash in a renderer is worse than a coarse description.
    return { shape: "JSON", json: "This payload could not be shown as text.", truncated: false };
  }
  if (json.length <= AI_PAYLOAD_JSON_CHARS) return { shape: "JSON", json, truncated: false };
  return { shape: "JSON", json: json.slice(0, AI_PAYLOAD_JSON_CHARS), truncated: true };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Refusals
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * There is nothing on this device to read or to queue, and both halves of that have to be said.
 *
 * The reassurance alone ("nothing has been lost") is what an earlier draft said, and it is the half a
 * designer will misread: every other write in this app banks itself in the outbox and drains later, so
 * "nothing has been lost" invites the reading that the acceptance is waiting to be sent. It is not, and
 * deliberately — an acceptance is a signature, the server refuses a second one, and a signature
 * replayed three days later against a layer somebody has since declined would report success for an act
 * that did not happen. So the sentence says what did NOT happen, and then what to do about it.
 */
const NO_CONNECTION =
  "These layers are held on the server, so they cannot be read or changed without a connection. " +
  "Nothing has been queued for later — an acceptance is a signature and this app deliberately does " +
  "not bank one offline — so reconnect and press Reload to try again. The workshop's own stages are " +
  "on this device and are untouched.";

/**
 * Did the SERVER put a sentence in this response, or is `ApiError.message` `apiFetch`'s last resort?
 *
 * THE TWO ARE INDISTINGUISHABLE IN THE MESSAGE AND ARE NOT THE SAME THING. `apiFetch` builds the
 * message as `describeApiDetail(detail, response.statusText || "The server refused the request (HTTP
 * ${status}).")`, and `statusText` is empty over HTTP/2 — which every deployed request is. So a reply
 * that carried no `detail` at all (a proxy's 502 page, a gateway timeout, an nginx error) arrived here
 * as the literal string "The server refused the request (HTTP 502)." and was shown to a designer as
 * the answer, under a docstring and a panel header that both promise a status code is never shown.
 * The body is the only place the difference is visible, so it is read here.
 */
function serverSaidSomething(error: ApiError): boolean {
  const body = error.payload;
  if (!body || typeof body !== "object") return false;
  const detail = (body as { detail?: unknown }).detail;
  if (typeof detail === "string") return detail.trim().length > 0;
  return detail !== undefined && detail !== null;
}

/**
 * The sentence to show when one of these five calls fails.
 *
 * THE SERVER'S OWN WORDS WIN WHEREVER IT SPOKE, and that is not laziness. Every refusal these routes
 * raise is already a sentence naming the next move — "This layer has already been accepted. Withdraw
 * the acceptance first if it should stand in somebody else's name", "N layer(s) were produced from
 * this one (…) and would be left describing something no longer on screen. Delete those first" — and
 * rewording them here would give one rule two voices, which is how a client and a server come to
 * disagree about what a refusal means. `ApiError.message` is already `describeApiDetail`'s output, so
 * a FastAPI 422 arrives as a real sentence rather than as "[object Object]".
 *
 * A STATUS CODE IS NEVER SHOWN, and that is now true of the code rather than only of this paragraph —
 * see {@link serverSaidSomething} for the reply that used to reach the screen as "HTTP 502". Where the
 * server answered and said nothing usable, this says so and names the next move, WITHOUT claiming the
 * action did not land: a 502 from a proxy can perfectly well sit in front of a write that succeeded,
 * and "nothing was changed" would be a fabricated fact in a screen whose whole subject is provenance.
 *
 * `isUnreachable` rather than `isTransient`, because the latter counts every 5xx as "try again later"
 * and telling a designer their connection is at fault when the server ANSWERED sends them to look at
 * their signal while the real fault sits in a response nobody sees.
 */
export function aiLayerProblem(error: unknown, fallback: string): string {
  // Checked before the ApiError branch below and not inside it: this one is a 503 that no server ever
  // sent — the request was never made — and its message names the administrator action that fixes it,
  // which nothing here could reconstruct from a status.
  if (error instanceof ApiUnconfiguredError) return error.message;
  if (error instanceof ApiError) {
    if (serverSaidSomething(error) && error.message.trim()) return error.message;
    if (error.status === 408) return NO_CONNECTION;
    return (
      "The server answered this request without saying why it refused, so there is nothing to pass " +
      "on here. Press Reload to see where these layers actually stand before trying again — this " +
      "screen cannot tell whether the change was recorded."
    );
  }
  if (isUnreachable(error)) return NO_CONNECTION;
  return error instanceof Error && error.message.trim() ? error.message : fallback;
}
