import { API_BASE, ApiError, ApiUnconfiguredError, apiFetch, assertApiConfigured, describeApiDetail } from "@/lib/api";
import { fileNameFromDisposition } from "@/lib/designWorkshops";
import { blockText, orderedRange, sliceSpans, type DocRange, type RichDoc } from "@/lib/richText";
import { isUnreachable } from "@/lib/offline";
import type { DwAiLayer } from "@/lib/aiLayers";

export type DwAiVerb = "PROOFREAD" | "EXPAND" | "TRANSLATE" | "CAPTION" | "SUBTITLES";

const VERB_PATHS: Record<DwAiVerb, string> = {
  PROOFREAD: "proofread",
  EXPAND: "expand",
  TRANSLATE: "translate",
  CAPTION: "caption",
  SUBTITLES: "subtitles"
};

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

export const MAX_VERB_TEXT_CHARS = 20_000;

export const MAX_VERB_LANGUAGE_CHARS = 40;

export type DwAiVerbAllowance = {
  aiVerbsLimit: number | null;
  aiVerbsUsed: number;
  aiVerbsRemaining: number | null;
  aiVerbDay: string;
  aiVerbsByVerb: Record<string, number>;
};

export type DwAiVerbAllowanceState = DwAiVerbAllowance & {
  refusal?: string | null;
};

export type DwAiVerbResult = DwAiVerbAllowance & {
  layer: DwAiLayer;
  accepted: false;
  acceptanceRequired: true;
};

let verbAllowanceInFlight: Promise<DwAiVerbAllowanceState | null> | null = null;

export function dwAiVerbAllowance(): Promise<DwAiVerbAllowanceState | null> {
  if (verbAllowanceInFlight) return verbAllowanceInFlight;
  const asking: Promise<DwAiVerbAllowanceState | null> = apiFetch<DwAiVerbAllowanceState>(
    "/design-workshops/ai-verb-allowance"
  )
    .catch(() => null)
    .finally(() => {
      if (verbAllowanceInFlight === asking) verbAllowanceInFlight = null;
    });
  verbAllowanceInFlight = asking;
  return asking;
}

export function forgetAiVerbAllowanceInFlight(): void {
  verbAllowanceInFlight = null;
}

export function allowanceAfterRun(result: DwAiVerbAllowance): DwAiVerbAllowance {
  return {
    aiVerbsLimit: result.aiVerbsLimit,
    aiVerbsUsed: result.aiVerbsUsed,
    aiVerbsRemaining: result.aiVerbsRemaining,
    aiVerbDay: result.aiVerbDay,
    aiVerbsByVerb: result.aiVerbsByVerb ?? {}
  };
}

export function aiVerbsSpent(allowance: DwAiVerbAllowance | null | undefined): boolean {
  const remaining = allowance?.aiVerbsRemaining;
  return remaining !== null && remaining !== undefined && remaining <= 0;
}

export const AI_VERB_COUNTDOWN_FROM = 3;

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

export function captionDesignWorkshopMedia(
  workshopId: string,
  sourceMediaId: string,
  language?: string | null
): Promise<DwAiVerbResult> {
  return verbPost(workshopId, "CAPTION", {
    sourceMediaId,
    ...(language?.trim() ? { language: language.trim() } : {})
  });
}

export function subtitleDesignWorkshopMedia(workshopId: string, sourceMediaId: string): Promise<DwAiVerbResult> {
  return verbPost(workshopId, "SUBTITLES", { sourceMediaId });
}

export const SUBTITLES_SECOND_UPLOAD_NOTE =
  "Uploads the recording again and uses one AI run.";

export type DwSubtitleFormat = "srt" | "vtt";

export const SUBTITLE_FORMAT_LABELS: Record<DwSubtitleFormat, string> = {
  srt: "SubRip (.srt) — most players",
  vtt: "WebVTT (.vtt) — web players"
};

export type DwSubtitleFile = { blob: Blob; fileName: string };

export async function downloadDesignWorkshopSubtitles(
  workshopId: string,
  layerId: string,
  format: DwSubtitleFormat,
  speakers = false
): Promise<DwSubtitleFile> {
  assertApiConfigured();

  const headers = new Headers();

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
    throw new ApiError(
      response.status,
      describeApiDetail(detail, response.statusText || "Something went wrong. Please try again."),
      payload,
      response.headers
    );
  }

  return {
    blob: await response.blob(),
    fileName:
      fileNameFromDisposition(response.headers.get("content-disposition")) ??
      `subtitles-${layerId}${speakers ? ".speakers" : ""}.${format}`
  };
}

export type DwSubtitleCue = {
  start: number;
  end: number;
  text: string;
  speaker?: string;
  estimated?: boolean;
};

export type DwSubtitleSummary = {
  count: number;
  estimatedCues: number;
  durationSeconds: number | null;
  language: string | null;
  hasSpeakers: boolean;
  cues: DwSubtitleCue[];
  readable: boolean;
};

const SUBTITLE_PAYLOAD_SCHEMA = "dw.subtitles/1";

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
    readable: cues.length > 0 || (wrapper?.schema === SUBTITLE_PAYLOAD_SCHEMA)
  };
}

export function subtitleTimecode(seconds: number): string {
  const whole = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(whole / 3600);
  const minutes = Math.floor((whole % 3600) / 60);
  const secs = whole % 60;
  const mm = hours ? String(minutes).padStart(2, "0") : String(minutes);
  return `${hours ? `${hours}:` : ""}${mm}:${String(secs).padStart(2, "0")}`;
}

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
  return lines.join("\n").replace(/^\n+/, "").replace(/\n+$/, "");
}

export const NOTHING_SELECTED =
  "Select some text first.";

export function passageTooLong(chars: number): string {
  return (
    `That selection is ${chars.toLocaleString()} characters and at most ` +
    `${MAX_VERB_TEXT_CHARS.toLocaleString()} can be sent. Select a shorter passage.`
  );
}

export const VERBS_NEED_A_CONNECTION =
  "Needs a connection. Nothing was sent; your text is safe.";

export function consentNotGranted(_consent?: string | null): string {
  return "The artisan declined sharing, so AI tools are off.";
}

export function consentRefused(consent: string | null | undefined): boolean {
  return (consent ?? "").trim().toUpperCase() === "REFUSED";
}

export const CONSENT_LINK_LABEL = "Change consent";

export const MEDIA_NOT_UPLOADED_YET =
  "Available once this file uploads.";

export const WORKSHOP_NOT_ON_SERVER_YET =
  "Available once this workshop uploads.";

export function verbWorkshopRefusal(state: {
  ready: boolean;
  serverId: string | null;
  decision: string;
}): string | null {
  if (!state.ready) return "";
  if (consentRefused(state.decision)) return consentNotGranted(state.decision);
  if (!state.serverId) return WORKSHOP_NOT_ON_SERVER_YET;
  return null;
}

export function verbAllowanceRefusal(allowance: DwAiVerbAllowanceState | null | undefined): string | null {
  if (!aiVerbsSpent(allowance)) return null;
  return (
    allowance?.refusal ??
    "Today's runs of the writing and captioning models are used up on this account. Dictation has " +
      "its own separate allowance and is unaffected."
  );
}

export function translationTargetRefusal(value: string): string | null {
  const token = value.trim();
  if (!token) return "Name the language, e.g. “Odia” or “English”.";
  if (token.toLowerCase() === "multi") {
    return (
      "“multi” can describe the mixed language a passage was spoken in, but not the language to " +
        "translate into. Name the one language you want it in."
    );
  }
  if (token.length > MAX_VERB_LANGUAGE_CHARS) {
    return `A language name is at most ${MAX_VERB_LANGUAGE_CHARS} characters.`;
  }
  return null;
}

export { aiLayerProblem } from "@/lib/aiLayers";

export function isVerbOffline(error: unknown): boolean {
  if (error instanceof ApiUnconfiguredError) return false;
  return isUnreachable(error);
}
