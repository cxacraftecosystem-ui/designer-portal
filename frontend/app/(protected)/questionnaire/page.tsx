"use client";

import { Fragment, Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { ArrowDown, ArrowUp, ClipboardCheck, ClipboardList, GripVertical, ListFilter, Lock, Mic, Pencil, Plus, QrCode, Save, Square, Trash2, Wrench } from "lucide-react";

import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { useEditDeepLink } from "@/components/hooks/useEditDeepLink";
import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";
import { EmptyState } from "@/components/EmptyState";
import { Field, MultiNoteField, Select, TextArea, TextInput } from "@/components/FormControls";
import { CarryContextBanner, carryScope, useCarryContext } from "@/components/forms/CarryContextBanner";
import { LocationFields } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { QuestionnaireCaptureControls, useCapturePrefs } from "@/components/forms/QuestionnaireCaptureControls";
import { useWorkshopPicker, WorkshopPicker } from "@/components/forms/WorkshopPicker";
import { appendDictatedPhrase } from "@/components/richtext/dictatedValue";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { EditedFlag, MarkdownDocument } from "@/components/richtext/MarkdownDocument";
import { RichTextField } from "@/components/richtext/RichTextField";
import { appendDictatedToStored, plainFromStoredRichText } from "@/components/richtext/storedRichText";
import { readableError } from "@/components/review/reviewErrors";
import {
  artisanPickerEmptyLabel,
  artisanPickerOptions,
  artisansNotAtWorkshop,
  artisanSetKey,
  interviewScopeIsNarrowed,
  interviewScopeKey,
  outOfWorkshopNotice,
  primaryInterviewArtisanId,
  useWorkshopArtisans,
  workshopScopeSettling,
  type InterviewWorkshopScope
} from "@/components/questionnaires/interviewArtisans";
import { dictateAudio, dictationAnswerSentence } from "@/lib/designWorkshops";
import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { UploadProgress } from "@/components/media/UploadProgress";
import { UploadTray } from "@/components/media/UploadTray";
import { RecordingStrip } from "@/components/media/Waveform";
import { MegaCard } from "@/components/dashboard/MegaCard";
import { useMegaCards } from "@/components/dashboard/useMegaCards";
import { PageHeader } from "@/components/PageHeader";
import { InstrumentPicker, SHARED_INSTRUMENT } from "@/components/questionnaire/InstrumentPicker";
import { Pagination } from "@/components/Pagination";
import { RecordCodeCard } from "@/components/RecordCode";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { EMPTY_FUNNEL, FunnelFilters, type FunnelValue } from "@/components/FunnelFilters";
import { StatusBadge } from "@/components/StatusBadge";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { MultiSelectDropdown } from "@/components/ui/Dropdown";
import { useWorkshopScope, WorkshopScopeSelect } from "@/components/WorkshopScopeSelect";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { ApiError, apiFetch, buildQuery, listResource } from "@/lib/api";
import { formatDate, formatDateTime } from "@/lib/format";
import { locationFromForm, recordedAtFromForm, recordedTimezoneFromForm, textValue } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { INTERVIEW_LANGUAGE_PLACEHOLDER, interviewLanguageOptions } from "@/lib/interviewLanguages";
import {
  audioExtensionForMimeType,
  MediaBatchError,
  pickAudioRecorderMimeType,
  SPEECH_AUDIO_CONSTRAINTS,
  uploadMediaBatch,
  type BatchProgress,
  type BatchResult
} from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { cappedListNotice } from "@/components/data/cappedList";
import { canManageQuestionnaire, canRunDesignWorkshops, hasRank, isAdmin } from "@/lib/permissions";
import { UploadsProvider, useEagerStaging, useUploads } from "@/lib/uploads";
import type { Artisan, MediaFile, PageResult, QuestionnaireInterview, QuestionnaireQuestion, QuestionnaireSection } from "@/lib/types";

/** Section ids the two questionnaire upload paths publish under, for the page-level tray. */
const INTERVIEW_SECTION = "interview-audio";
const INTERVIEW_SECTION_LABEL = "Interview audio";

/**
 * The two sentences under the artisan picker, as ids a screen reader can be pointed at.
 *
 * Module constants and not `useId()`, because they are referenced from a `describedBy` string that
 * is assembled beside the control rather than inside it, and a hook value threaded through two
 * props is one rename away from naming nothing. Only ever one capture form on this page, so a fixed
 * id cannot collide — the questionnaire BUILDER further down draws no artisan picker.
 */
const ARTISAN_CUT_HINT = "questionnaire-artisan-cut";
const ARTISAN_SCOPE_HINT = "questionnaire-artisan-scope";

/**
 * Recorded clips are keyed by what they answer: a question id, or `section:<id>` for one take that
 * covers a whole section. Same keying as the Android form, so both clients think about a section
 * recording the same way and the same caption reaches the server from either.
 */
const SECTION_CLIP_PREFIX = "section:";
const sectionClipKey = (sectionId: string) => `${SECTION_CLIP_PREFIX}${sectionId}`;
const isSectionClipKey = (key: string) => key.startsWith(SECTION_CLIP_PREFIX);
/** Tray section id for a clip key — ":" is stripped so the id stays a plain slug. */
const clipTraySectionId = (key: string) => `question-audio-${key.replace(SECTION_CLIP_PREFIX, "section-")}`;

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT AN ALREADY-SAVED RECORDING BELONGS TO — the read-only half of this form
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * THE DEFECT, in the owner's words (2026-09-20): *"when edit page is opened, already existing
 * entries and media do not show up in the respective sections, those should show up while editing as
 * well, on both android and web"*. The handset has drawn a sitting's saved clips under the section
 * they belong to for as long as it has had an edit form — `savedMedia` plus
 * `captionBelongsToSection` in `MainActivity.kt`, per section and then an "Other saved recordings &
 * media" catch-all. THIS form drew none of them at all, and it is the one edit form in the
 * repository without such a panel. A researcher correcting a typo therefore opened a sitting that was
 * nothing but recordings, saw an empty upload tray, and had every reason to conclude the clips were
 * gone.
 *
 * ⚠ READ-ONLY, AND THAT IS NOT A SIMPLIFICATION — it is the whole constraint. `seedFromInterview`
 * deliberately leaves `mediaFiles` and `questionAudioFiles` empty, and its own comment says why: an
 * edit form is not a re-capture, so seeding the tray with the stored clips would offer every one of
 * them for upload a second time on the next Save and duplicate the sitting's audio. Nothing below
 * writes to either of those, nothing offers a remove, and the only gesture available is opening the
 * page's own lightbox.
 *
 * ── HOW A CLIP IS PLACED, AND WHY THE METADATA IS TRIED BEFORE THE CAPTION ──────────────────────
 *
 * `clipBatch` stamps `extraMetadata` on every clip THIS form uploads — `sectionId` for a whole-
 * section take, `questionId` for a per-question one — so for anything the web recorded the placement
 * is a field lookup, and it cannot be defeated by somebody editing a prompt or a section title
 * afterwards. The CAPTION fallback is what places the rest: the handset writes the same two caption
 * prefixes and no metadata, and the backend's own `_derived_completed_sections` reads section
 * coverage off these captions, so they are a real contract rather than decoration. A clip matching
 * neither is NOT guessed at — it goes to the catch-all, exactly as Android's does.
 *
 * ⚠ THE LONGEST MATCHING SECTION CODE WINS, which the handset does not do. `QuestionnaireSection.code`
 * is free text, so on a deployment carrying both `A` and `AB` a plain `startsWith` lets section `A`
 * claim "Question audio: AB1 …" — the clip files under the wrong section with nothing on screen to
 * say so. Ranking by code length costs one sort over a handful of rows and removes the ambiguity.
 */
const SECTION_CAPTION_PREFIX = "Section audio:";
const QUESTION_CAPTION_PREFIX = "Question audio:";

/**
 * A media row's `extraMetadata` as a bag of unknowns, or null.
 *
 * Narrowed HERE, at the reader, because the column is free Json that several clients write different
 * keys into — `lib/types.ts` types it `unknown` for that reason and says so.
 */
function clipMetadata(media: MediaFile): Record<string, unknown> | null {
  const meta = media.extraMetadata;
  return meta && typeof meta === "object" && !Array.isArray(meta) ? (meta as Record<string, unknown>) : null;
}

/** One string key out of such a bag, or null for anything that is not a non-blank string. */
function metaString(bag: Record<string, unknown> | null, key: string): string | null {
  const value = bag?.[key];
  return typeof value === "string" && value.trim() ? value : null;
}

/** Sections longest-code-first, so `AB1` cannot be claimed by section `A`. See the block above. */
function sectionsByCodeLength(sections: QuestionnaireSection[]): QuestionnaireSection[] {
  return [...sections].sort((a, b) => (b.code ?? "").length - (a.code ?? "").length);
}

/** Which section a saved clip belongs to, or null for the catch-all. */
function savedClipSectionId(media: MediaFile, sections: QuestionnaireSection[]): string | null {
  const meta = clipMetadata(media);
  const sectionId = metaString(meta, "sectionId");
  if (sectionId && sections.some((section) => section.id === sectionId)) return sectionId;
  const questionId = metaString(meta, "questionId");
  if (questionId) {
    const owner = sections.find((section) => section.questions.some((question) => question.id === questionId));
    if (owner) return owner.id;
  }
  const metaCode = metaString(meta, "sectionCode");
  if (metaCode) {
    const owner = sections.find((section) => section.code === metaCode);
    if (owner) return owner.id;
  }
  const caption = (media.caption ?? "").trim();
  if (!caption) return null;
  const ranked = sectionsByCodeLength(sections);
  if (caption.startsWith(SECTION_CAPTION_PREFIX)) {
    const rest = caption.slice(SECTION_CAPTION_PREFIX.length).trim();
    return ranked.find((section) => rest === section.code || rest.startsWith(`${section.code} `))?.id ?? null;
  }
  if (caption.startsWith(QUESTION_CAPTION_PREFIX)) {
    const rest = caption.slice(QUESTION_CAPTION_PREFIX.length).trim();
    // A digit has to follow the code, because the question token is `<code><sortOrder>` — otherwise
    // any caption beginning with the section's letters would be read as one of its questions.
    return (
      ranked.find((section) => rest.startsWith(section.code) && /^\d/.test(rest.slice(section.code.length)))?.id ?? null
    );
  }
  return null;
}

/**
 * A take that covers a WHOLE section rather than one question.
 *
 * It marks every question in that section answered, because on this instrument that take IS the
 * answer to all of them — the rule the handset's section header already counts by.
 */
function isWholeSectionTake(media: MediaFile): boolean {
  if (metaString(clipMetadata(media), "sectionId")) return true;
  return (media.caption ?? "").trim().startsWith(SECTION_CAPTION_PREFIX);
}

/** Whether a saved clip was recorded against this one question. */
function savedClipAnswers(media: MediaFile, question: QuestionnaireQuestion): boolean {
  if (metaString(clipMetadata(media), "questionId") === question.id) return true;
  const caption = (media.caption ?? "").trim();
  if (!caption.startsWith(QUESTION_CAPTION_PREFIX)) return false;
  const rest = caption.slice(QUESTION_CAPTION_PREFIX.length).trim();
  const token = `${question.sectionCode}${question.sortOrder}`;
  // Equality or the token followed by a space, never a bare prefix: `D1` must not claim `D10`'s clip.
  // The web writes "D1 - prompt" and the handset writes "D1 prompt", and both start with "D1 ".
  return rest === token || rest.startsWith(`${token} `);
}

/**
 * Whether a stored answer actually holds words.
 *
 * ⚠ NOT A BARE `.trim()`: an answer box holds a stored rich-text column, and an emptied DOCUMENT
 * still serialises to a non-blank string (`{"blocks":[…]}` with one empty paragraph in it), so a raw
 * test reports every cleared answer as answered.
 *
 * AND NOT WRITTEN INLINE AS `plainFromStoredRichText(x).trim()` EITHER, which is the shape it started
 * as. `questionnaire-voice-note-unit.spec.ts` counts the call sites that flatten a stored answer FOR
 * DISPLAY — "every surface that renders an answer as prose flattens it" — and an emptiness test
 * spelled identically is indistinguishable from a third render site, so it both broke that count and
 * would have gone on weakening the assertion if the count had simply been raised. Two different
 * questions, two different names.
 */
function answerHasWords(answerText: string | null | undefined): boolean {
  return plainFromStoredRichText(answerText).trim().length > 0;
}

type SectionProgress = { questions: number; answered: number; recordings: number };

/**
 * ══ THE COUNTS ON A SECTION'S SUMMARY ══════════════════════════════════════════════════════════
 *
 * "N questions · M answered · K saved recording(s)" is what the HANDSET's section header has always
 * printed and what this `<summary>` printed nothing of: it drew "D. RAW MATERIALS" and stopped. On an
 * instrument captured as whole-section audio with the answer boxes hidden — which is the DEFAULT, see
 * `DEFAULT_CAPTURE_PREFS` — a fully recorded sitting and an untouched one therefore looked identical:
 * a shut row carrying a code and a title. This line is the only thing on the screen that would have
 * told the researcher their work was there.
 *
 * THE RULE IS ANDROID'S, ported rather than reinvented: a question counts as answered if it has typed
 * words, a clip recorded in this sitting, or a clip already saved against it, and a whole-section take
 * marks every question in its section. LIVE clips count as well as saved ones, so the number moves the
 * moment a recording is made rather than waiting for a save.
 */
function sectionProgress(
  questions: QuestionnaireQuestion[],
  input: {
    answers: Record<string, string>;
    liveSectionClips: number;
    liveQuestionClips: Record<string, File[]>;
    savedClips: MediaFile[];
  }
): SectionProgress {
  const wholeSectionRecorded = input.liveSectionClips > 0 || input.savedClips.some(isWholeSectionTake);
  const answered = wholeSectionRecorded
    ? questions.length
    : questions.filter((question) => {
        if (answerHasWords(input.answers[question.id])) return true;
        if ((input.liveQuestionClips[question.id] ?? []).length) return true;
        return input.savedClips.some((media) => savedClipAnswers(media, question));
      }).length;
  return { questions: questions.length, answered, recordings: input.savedClips.length };
}

/**
 * Android's own sentence, word for word.
 *
 * A field team that hears one thing described two ways ends up with two mental models of it, so the
 * handset owns this wording and the browser copies it — including the "(s)", which is how the
 * original is written.
 */
function sectionProgressLabel(progress: SectionProgress): string {
  const head = `${progress.questions} questions · ${progress.answered} answered`;
  return progress.recordings ? `${head} · ${progress.recordings} saved recording(s)` : head;
}

/**
 * Which `<details>` start open.
 *
 * On a CREATE that is the first section and only the first — `questionnaire-capture.spec.ts` asserts
 * the first instrument section is visible with no clicks at all, and a work surface that opens fully
 * shut reads as a page that has not finished loading.
 *
 * On an EDIT it is every section the record actually holds something in. The complaint is that
 * existing entries "do not show up in the respective sections", and a section whose answers sit
 * behind a shut disclosure has not shown up. A record with nothing placeable falls back to the first
 * section, for the create form's reason.
 */
function sectionOpensOnLoad(
  sectionId: string,
  index: number,
  withContent: ReadonlySet<string>,
  editing: boolean
): boolean {
  if (!editing || withContent.size === 0) return index === 0;
  return withContent.has(sectionId);
}

/**
 * ══ WHAT THE SHARED ENTRY ACTUALLY HOLDS ═══════════════════════════════════════════════════════
 *
 * THE DEFECT: this banner said "No questions answered yet" about a sitting a researcher had fully
 * recorded. It measured `existingEntry.responses` and nothing else — and on an instrument whose
 * sections are captured as whole-section AUDIO with the answer boxes hidden, a complete sitting has
 * ZERO response rows and many media rows. The banner was reading the one column that is empty BY
 * DESIGN and reporting it as an empty interview, to the person about to record it again.
 *
 * `existingEntry.media` has been on the wire the whole time: `RELATIONS` in
 * `backend/app/api/routes/questionnaire.py` declares it, `by-artisans` hydrates it, and that route's
 * own comment says it is "the first place the existing entry's recordings are drawn". Counting it
 * needed no request and no new field.
 *
 * IT SAYS WHAT IT FOUND AND NEVER "EMPTY". "3 recordings, no typed answers yet" is a true and useful
 * sentence about a sitting; "No questions answered yet" about that same sitting is not merely terse,
 * it is the claim that started this.
 */
function sharedEntryContents(responses: number, media: number): string {
  const clips = `${media} recording${media === 1 ? "" : "s"}`;
  const typed = `${responses} typed answer${responses === 1 ? "" : "s"}`;
  if (responses && media) return `${typed} and ${clips} are already on it.`;
  if (media) return `${clips}, no typed answers yet.`;
  if (responses) return `${typed}, and no recordings yet.`;
  return "No answers and no recordings on it yet — you can be the first to add to it.";
}

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE VOICE NOTE'S QUICK TRANSCRIPT: the caps, and why there are caps at all
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Owner, 2026-08-30: *"whenever the conversation is recorded even using the voice note, that voice
 * note is then to be streamed to the same api through which the dictate button is facilitated as
 * well, until the elevenlabs, deepgram, or whisper api transcription and translation comes in"*.
 *
 * That makes this page a caller of `POST /design-workshops/{id}/dictate`, which 413s over six
 * megabytes — and until today NOTHING bounded a questionnaire recording. `startRecording` ran until
 * somebody pressed Stop, so a recorder left running through a lunch break produced a file whose only
 * ceiling was the disk. Adding the upload without adding a ceiling would have made the feature's
 * FIRST REAL USE a 413, on a village connection, after the bytes had already been sent.
 *
 * ── THE TWO NUMBERS ARE ONE NUMBER, DERIVED ─────────────────────────────────────────────────────
 * `CLIP_BITS_PER_SECOND` pins the encoder instead of accepting the browser's default, which is the
 * only way the duration cap can be turned into a size guarantee: Chrome's Opus default is around
 * 128 kbps and Safari's differs again, so "fifteen minutes" would mean 14 MB on one browser and
 * 3 MB on another. 32 kbps is the rate Android already ships for its own dictations
 * (`DwDictationUpload.kt`), so the two clients hand the providers audio of the same quality.
 *
 * At 32 kbps, fifteen minutes is 3.6 MB — comfortably inside the six-megabyte ceiling with room for
 * container overhead and for a browser that treats the bitrate as the hint it formally is. The
 * duration is what a researcher sees and the byte count is what the route enforces, so BOTH are
 * checked: the timer stops the recorder, and `DICTATE_MAX_BYTES` below is re-checked against the
 * actual blob before anything is posted. A cap that is only a timer trusts an encoder hint with an
 * artisan's interview.
 *
 * ── AND THE CAP STOPS THE RECORDER, IT DOES NOT DISCARD THE CLIP ────────────────────────────────
 * Hitting fifteen minutes ends the take and keeps every second of it — the clip uploads and is
 * transcribed by the queue exactly as before. Non-negotiable 10: it is announced on screen rather
 * than left to look like a crash, because a recorder that stops by itself with nothing said is
 * indistinguishable from one that failed.
 */
const CLIP_MAX_MS = 15 * 60 * 1000;
const CLIP_BITS_PER_SECOND = 32000;
/**
 * `DICTATION_MAX_BYTES` in `backend/app/api/routes/design_workshops.py`, mirrored.
 *
 * Mirrored rather than fetched: `GET /design-workshops/dictate` reports it as `maxBytes`, but that
 * probe is a round trip this page would have to make before it could decide whether to spend a
 * SECOND round trip, and the answer is a deployment constant. The server stays the authority — a
 * clip that slips past this still meets a 413, and `dictationAnswerSentence` has a sentence for it.
 */
const DICTATE_MAX_BYTES = 6 * 1024 * 1024;

/**
 * Why a batch was refused, in one clause — WITHOUT the advice `MediaBatchError`'s own message ends
 * with.
 *
 * That message is two clauses: the per-file reason, then what to do about it. The second clause is
 * written for a record with an edit screen — every variant of it ends "re-open it and re-attach the
 * media" — and an interview has none: the row actions here are its code and delete, and the clips
 * exist only in this form's memory. So only the reason is taken and the banner below says what to do
 * on THIS screen, which is to press Save again.
 *
 * THE ADVICE CLAUSE IS NO LONGER ONE FIXED SENTENCE, and this function is why it could become
 * several. It used to be the constant "Check your internet connection and try again — …" whatever
 * had happened, so a 413 or a 415 sent a researcher out to look for signal they already had;
 * `adviceForALostBatch` in `lib/media.ts` now picks it from the triage verdict. Nothing here depends
 * on which one it is: this reads `failures[0].error`, the server's own words about the file, and
 * falls back to the whole message only when there are no per-file failures to read — which cannot
 * happen for a batch this page sent, since a `MediaBatchError` is raised only with one per file.
 */
function batchCause(err: unknown): string {
  if (err instanceof MediaBatchError) return err.failures[0]?.error ?? err.message;
  return err instanceof Error ? err.message : String(err);
}

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * READING THE TWO REFUSALS THIS SCREEN CAN NOW ACT ON
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Both codes are constants on the server so that a client branches on a tag rather than on prose —
 * the same discipline `records.assert_unchanged` and `artisans._identity_conflict` already keep — and
 * both are read verbatim off `backend/app/api/routes/questionnaire.py`:
 *
 *   `_DUPLICATE_SET_CODE = "duplicate_artisan_set"` → `duplicate_set_detail` answers
 *      `{code, message, existingInterviewId, existingInterviewTitle}`.
 *   `_MERGE_CONFLICT_CODE = "merge_answer_conflict"` → the merge route answers
 *      `{code, message, conflicts: [{questionId, sectionCode, prompt, fields, …}]}`.
 *
 * ⚠ THE HOLDER KEYS ARE FLAT, AND THEY ARE SPELLED `existingInterviewId` /
 * `existingInterviewTitle`. There is no `holder` object on this route. `duplicate_set_detail`'s own
 * docstring calls them "STABLE KEYS, present whether or not the holder was found" — a holder deleted
 * by a concurrent request between the unique violation and the lookup yields NULL ids rather than a
 * second failure — so a null id means "there is no offer to make", never "malformed", and the reader
 * falls back to `message`, which is exactly what this page did before the keys existed.
 *
 * `apiFetch` already flattens `detail.message` into `ApiError.message`, so nothing here has to
 * re-derive the SENTENCE. What it cannot flatten is the half a client could not otherwise have: WHICH
 * interview is in the way, and WHICH questions the two sittings disagree about.
 */
const DUPLICATE_SET_CODE = "duplicate_artisan_set";
const MERGE_CONFLICT_CODE = "merge_answer_conflict";

/** A refusal's `detail` object, or null for a bare-string detail, a 422 list, or a non-API failure. */
function refusalDetail(error: unknown): Record<string, unknown> | null {
  if (!(error instanceof ApiError)) return null;
  const payload = error.payload;
  if (!payload || typeof payload !== "object") return null;
  const detail = (payload as { detail?: unknown }).detail;
  return detail && typeof detail === "object" && !Array.isArray(detail) ? (detail as Record<string, unknown>) : null;
}

/**
 * The interview already holding this artisan set, when the server named one.
 *
 * Null for every other refusal AND for a holder that vanished — see the block above for why those
 * two must land on the same answer here.
 */
function duplicateSetHolder(error: unknown): { id: string; title: string } | null {
  const detail = refusalDetail(error);
  if (!detail || detail.code !== DUPLICATE_SET_CODE) return null;
  const id = metaString(detail, "existingInterviewId");
  if (!id) return null;
  return { id, title: metaString(detail, "existingInterviewTitle") ?? "the interview that already covers this set" };
}

/**
 * Every question a refused merge named, as the reader sees them.
 *
 * NEVER SWALLOWED, and this is the one refusal in the fold that a person can actually act on: the
 * server refuses outright when both sittings answer one question with different words, because
 * picking a winner would destroy somebody's words under a 200 and the losing row goes with the
 * deleted source. A banner that said only "conflict" would hand them the problem and withhold the
 * list of what to go and fix.
 */
function mergeConflictQuestions(error: unknown): string[] {
  const detail = refusalDetail(error);
  if (!detail || detail.code !== MERGE_CONFLICT_CODE) return [];
  const rows = Array.isArray(detail.conflicts) ? detail.conflicts : [];
  return rows.flatMap((row) => {
    if (!row || typeof row !== "object") return [];
    const bag = row as Record<string, unknown>;
    // The prompt, or the id when a question was retired after the answer was recorded — never a bare
    // "a question", which names nothing a researcher can open.
    const prompt = metaString(bag, "prompt") ?? metaString(bag, "questionId") ?? "This question";
    const code = metaString(bag, "sectionCode");
    return [code ? `${code} — ${prompt}` : prompt];
  });
}

export default function QuestionnairePage() {
  return (
    <UploadsProvider>
      {/*
        THE SUSPENSE BOUNDARY `useSearchParams` OWES, AND `UploadsProvider` STAYS OUTSIDE IT.

        Both halves are the frontend contract's rule, and the second is the one that bites: the
        provider owns the upload tray's state, so suspending it would tear down in-flight uploads —
        recorder-produced bytes that exist nowhere else — every time the boundary re-suspended.

        The body has called `useSearchParams` since long before the edit path, and Next 16 wants it
        wrapped; `useEditDeepLink` is now a second consumer of the same hook. A boundary was owed
        either way, and the fallback is the same "Loading…" the body itself renders while its first
        read is in flight, so nothing flickers between the two.
      */}
      <Suspense fallback={<div className="panel p-4 text-sm text-ink-700">Loading…</div>}>
        <QuestionnairePageBody />
      </Suspense>
      <UploadTray />
    </UploadsProvider>
  );
}

function QuestionnairePageBody() {
  const confirm = useConfirm();
  const { user } = useAuth();
  const { adminMode } = useAdminView();
  const { addCompleted } = useUploads();
  const searchParams = useSearchParams();
  const router = useRouter();
  const [sections, setSections] = useState<QuestionnaireSection[]>([]);
  const [data, setData] = useState<PageResult<QuestionnaireInterview> | null>(null);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  /**
   * Title, place and language — the three header fields this page now OWNS the value of, where the
   * rest of the form is still uncontrolled `FormData`.
   *
   * WHY THEY HAD TO MOVE INTO STATE. Title and Place grew a microphone, and a dictated phrase is a
   * React state write rather than a typed `input` event, so the box has to be controlled for the
   * committed text to appear in it at all. Language became a themed dropdown, which is a `<button>`
   * with no value of its own. All three still submit through `FormData` — `DictatedTextInput`
   * renders a real `name`, and `Select` renders its zero-size mirror input (SKILL.md §12.2) — so
   * `submit`'s `textValue(form, ...)` reads them exactly as before and nothing downstream changed.
   *
   * AND THEY MUST BE CLEARED BY HAND BESIDE EVERY `formElement.reset()`. `reset()` rewrites the DOM
   * node and tells React nothing: the next render paints the state value straight back, so a second
   * interview would open carrying the first one's title. Both reset sites in `submit` (the queued
   * branch and the saved branch) clear these three, and the form's `key` does not change between two
   * consecutive new interviews, so a remount cannot be relied on to do it instead.
   */
  const [title, setTitle] = useState("");
  const [place, setPlace] = useState("");
  const [language, setLanguage] = useState("");
  const [questionAudioFiles, setQuestionAudioFiles] = useState<Record<string, File[]>>({});
  /**
   * EVERYBODY THIS INTERVIEW IS WITH — ONE ordered array, and one control that draws it.
   *
   * IT WAS TWO CONTROLS AND TWO PIECES OF STATE: a single-select "Primary artisan" and a
   * multi-select "Additional artisans", de-duplicated into one set by `selectedArtisanIds` before
   * every request the page made. `QuestionnaireInterviewArtisan` is `@@id([interviewId, artisanId])`
   * plus `createdAt` — no rank column, no ordinal, nothing that could store which of them was
   * "primary" — and `artisan_set_key` sorts the ids on the server before they reach the unique
   * index, so the split could not survive a round trip even in principle. What it DID do was let a
   * researcher tick somebody under "Additional" with "Primary" left blank and get the RESP block
   * prefilled from nobody.
   *
   * THE ORDER IS THE RESEARCHER'S AND IT IS LOAD-BEARING, which is why this is an array and not a
   * `Set`. Two things still need exactly one artisan — the RESP respondent block and the carry-
   * forward bag — and both take element 0 (`primaryInterviewArtisanId`). The SET key that decides
   * which interview a save folds into is sorted separately (`artisanSetKey`), so the two orderings
   * never have to be the same array. See `components/questionnaires/interviewArtisans.ts`.
   *
   * The deep link (`/questionnaire?artisanId=…`) seeds it with one id, exactly as it seeded the old
   * "Primary artisan" box.
   */
  const [selectedArtisanIds, setSelectedArtisanIds] = useState<string[]>(() => {
    const deepLinked = searchParams.get("artisanId") ?? "";
    return deepLinked ? [deepLinked] : [];
  });
  const [existingEntry, setExistingEntry] = useState<QuestionnaireInterview | null>(null);
  // Whole-section vs per-question capture, and whether the written-answer boxes are on screen.
  // Remembered across sections and across visits — see useCapturePrefs.
  const { prefs: capture, update: updateCapture } = useCapturePrefs();
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [activePreview, setActivePreview] = useState<PreviewMedia | null>(null);
  const [questionAudioPreviews, setQuestionAudioPreviews] = useState<Record<string, PreviewMedia[]>>({});
  const [recordingKey, setRecordingKey] = useState<string | null>(null);
  // The live stream is state (not just a ref) because <RecordingStrip>'s waveform re-renders on it.
  const [questionStream, setQuestionStream] = useState<MediaStream | null>(null);
  const [questionElapsedMs, setQuestionElapsedMs] = useState(0);
  const [interviewProgress, setInterviewProgress] = useState<BatchProgress | null>(null);
  const [questionProgress, setQuestionProgress] = useState<Record<string, BatchProgress | null>>({});
  const [page, setPage] = useState(1);
  const [funnel, setFunnel] = useState<FunnelValue>(EMPTY_FUNNEL);
  const [searchInput, setSearchInput] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /**
   * Audio that did not reach the repository after the interview itself was saved — kept apart from
   * `error` ON PURPOSE.
   *
   * `error` belongs to the list underneath: `loadInterviews` writes it on failure and clears it on
   * success, and every save ends by refreshing that list. Putting this warning there meant it was
   * wiped one round trip later, leaving a form quietly repopulated with the only copy of a
   * recording and nothing on screen to say the clips had not been sent. Nothing but `submit` writes
   * this one, and it is cleared when the next save begins.
   */
  const [uploadError, setUploadError] = useState<string | null>(null);
  /**
   * What a fold into the holding interview did, once the researcher asked for one.
   *
   * ITS OWN PANEL AND NOT `error`, for `uploadError`'s reason one screen up: `error` belongs to the
   * list underneath — `loadInterviews` writes it on failure and clears it on success — and this flow
   * ENDS by refreshing that list, so the one sentence saying where a researcher's answers went would
   * be wiped a round trip after it appeared. It also has to be able to LIST the questions a refusal
   * names, which a bare string cannot.
   *
   * `moved` rather than a colour: the panel prints "Moved." or "Nothing was moved." as its first
   * words, so the verdict survives a reader who never gets the amber.
   */
  const [mergeOutcome, setMergeOutcome] = useState<{ moved: boolean; message: string; questions: string[] } | null>(
    null
  );
  /**
   * Which interview has its code open, or null.
   *
   * ONE AT A TIME, and by id rather than a flag per row: a page of twenty codes is twenty QR symbols
   * drawn at once for a screen where at most one is being scanned or printed, and the row a designer
   * opened is the row they are working on.
   */
  const [codeFor, setCodeFor] = useState<string | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const elapsedTimerRef = useRef<number | null>(null);
  /** Stops a take that has reached `CLIP_MAX_MS`. See that constant for why the ceiling exists. */
  const clipCapTimerRef = useRef<number | null>(null);

  /*
    ── THE QUICK TRANSCRIPT'S FOUR PIECES OF STATE, AND WHY THE FIRST ONE IS NOT DERIVABLE ────────

    `machineText` is the machine's own words per clip key, exactly as they were handed to the box.
    THE EDITED FLAG IS THE COMPARISON BETWEEN THIS AND WHAT IS IN THE BOX NOW, and there is no other
    way to compute it: "did a human change this" cannot be answered from the answer alone, because a
    researcher who types an answer by hand and one who accepts a transcript verbatim both end up
    holding a string. Keeping the machine's copy is what makes the two distinguishable, and it is
    also what lets a second take be offered rather than imposed.

    `offeredTranscript` holds a transcript that arrived while the box already held EDITED text. The
    owner's rule is that a later transcript replaces the earlier one *"unless the designer has edited
    the text, in which case it must not silently overwrite their words"* — so an unedited box takes
    the new words directly and an edited one gets an offer with the words visible in it. This is the
    two-stage rule applied at the point on this page where it can actually happen: a researcher
    recording a second take against the same question, which the per-question recorder has always
    allowed. The refined, translated pass lands hours later from the media queue and cannot reach a
    create-only form that has been submitted and reset; see this file's note at `quickTranscribe`.

    `transcribing` and `quickProblem` are the two halves of saying what happened. A round trip to a
    provider takes seconds on a village connection and silence in the box would read as a recorder
    that ate the take; a refusal must name itself for the same reason.
  */
  const [machineText, setMachineText] = useState<Record<string, string>>({});
  const [offeredTranscript, setOfferedTranscript] = useState<Record<string, string>>({});
  const [transcribing, setTranscribing] = useState<Record<string, boolean>>({});
  const [quickProblem, setQuickProblem] = useState<Record<string, string>>({});
  /**
   * Bumped to remount a `RichTextField` after the page writes into its box.
   *
   * `RichTextField` parses `defaultValue` exactly ONCE and never reads it again — deliberately, and
   * its own comment explains the caret that feeding it back would throw to position zero. So the
   * only honest way to put new words into a mounted editor is to give it a new `key`, which is the
   * same remount the record forms already do with `key={editing?.id ?? "new"}`.
   */
  const [answerSeed, setAnswerSeed] = useState<Record<string, number>>({});

  /* ────────────────────────────────────────────────────────────────────────────
   * EDITING A RECORDED INTERVIEW — added 2026-09-20.
   *
   * ── THE BROWSER NEVER HAD AN EDIT FORM. NOT "LOST ONE" — NEVER HAD ONE. ──────────────────────
   *
   * What DID exist was a button. `app/(protected)/data/page.tsx`'s questionnaire browse arm declared
   * `editHref: () => "/questionnaire"` — the only one of eight arms discarding the id its own
   * signature is handed — so "Edit record" on an interview a researcher had just drilled into landed
   * on the blank CREATE form. Filling that in filed a SECOND sitting, which under one-entry-per-
   * artisan-set either folded the answers into a shared entry nobody asked for or came back 409.
   *
   * That is the defect `useEditDeepLink` was written for on /crafts, /workshops and /processes; its
   * own header says so. This page is the FOURTH inline form and was simply never wired to it.
   *
   * ── `editingInterview` AND NOT `editing` ─────────────────────────────────────────────────────
   *
   * `editing` is already taken further down this file by `QuestionnaireAdminEditor`, a different
   * component editing a different thing (a section or a question of the instrument). Two `editing`s
   * in one 3200-line file is how the wrong one gets read.
   */
  const [editingInterview, setEditingInterview] = useState<QuestionnaireInterview | null>(null);

  /**
   * The capture form's element, for the deep link to scroll to.
   *
   * `targetRef` and NOT `window.scrollTo(0, 0)`: this page draws the funnel, the carry banner and —
   * for a professor — a completion matrix above the form, so the top of the document is not the top
   * of the thing the reader asked to edit. The same reason `/workshops` passes its own ref.
   */
  const captureFormRef = useRef<HTMLFormElement | null>(null);

  /**
   * ── THE FOUR MEGA CARDS ON THIS SCREEN ────────────────────────────────────────────────────
   *
   * Owner ruling, 2026-09-20: *"this functionality should be there in the record questionnaire page
   * as well"* — the same collapsible, colour-coded cards the dashboard grew in the same change.
   *
   * ⚠ `"capture"` IS OPEN ON A FIRST VISIT AND THE OTHER THREE ARE NOT, and that is not a softening
   * of "stay minimized unless it is clicked upon". This page is a WORK surface rather than a menu:
   * `/questionnaire?new=1` is what the dashboard tile and `guide/steps.ts` both link to and it
   * expects to land on the create form, and `questionnaire-capture.spec.ts` asserts the first
   * instrument section is visible with no clicks at all. A collapsed form would send both to a shut
   * card with nothing on screen to say the page had finished loading. `useMegaCards`' own header
   * carries the rest of that argument, including why a card shut by the reader stays shut.
   */
  const megaCards = useMegaCards("questionnaire", ["capture"]);


  function stopElapsedTimer() {
    if (elapsedTimerRef.current !== null) {
      window.clearInterval(elapsedTimerRef.current);
      elapsedTimerRef.current = null;
    }
    if (clipCapTimerRef.current !== null) {
      window.clearTimeout(clipCapTimerRef.current);
      clipCapTimerRef.current = null;
    }
  }

  // Professors and above may pick a record's status; everyone below is forced to PENDING
  // (mirrors the backend, which silently drops an unauthorized status on create).
  const canPickStatus = hasRank(user, "PROFESSOR");

  /**
   * Put a recorded interview into the capture form.
   *
   * ── THE UNCONTROLLED HALF IS RE-SEEDED BY A REMOUNT AND NOT BY THIS FUNCTION ─────────────────
   *
   * Most of this form is uncontrolled `FormData` — status, the notes, the location and capture rows —
   * so there is nothing here to assign them to. The `<form>` carries `key={editingInterview?.id ??
   * "new"}`, which is the same remount every record form in this repository performs, and it is what
   * makes their `defaultValue`s re-read. Without it the previous occupant's values stay on screen
   * under the new record's title, which is the defect `useEditDeepLink`'s own header describes.
   *
   * ── THE ANSWERS ARE SEEDED BY QUESTION ID AND NEVER BY POSITION ──────────────────────────────
   *
   * A sitting's responses are keyed to the questions that were asked; the instrument's sections can
   * be reordered, retired and superseded between the sitting and the correction. Matching by index
   * would put last month's answer against this month's question — a wrong answer under a real
   * person's name, saved without anybody typing it.
   *
   * `answerSeed` is bumped alongside, because `RichTextField` parses `defaultValue` exactly once and
   * only a new `key` puts new words into a mounted editor. Its declaration says so.
   */
  const seedFromInterview = useCallback((record: QuestionnaireInterview) => {
    setEditingInterview(record);
    setTitle(record.title ?? "");
    setPlace(record.place ?? "");
    setLanguage(record.language ?? "");
    setSelectedArtisanIds((record.artisans ?? []).map((link) => link.artisan.id));
    const seeded: Record<string, string> = {};
    for (const response of record.responses ?? []) {
      if (response.questionId) seeded[response.questionId] = response.answerText ?? "";
    }
    setAnswers(seeded);
    setAnswerSeed((current) => {
      const next = { ...current };
      for (const questionId of Object.keys(seeded)) next[questionId] = (next[questionId] ?? 0) + 1;
      return next;
    });
    // The media of a recorded sitting stay where they are. An edit form is not a re-capture, and
    // pre-loading the existing clips into the upload tray would offer to send them a second time.
    setMediaFiles([]);
    setQuestionAudioFiles({});
  }, []);

  /**
   * Leave edit mode and hand the form back as a blank capture form.
   *
   * The URL is stripped as well as the state, because `?edit=` surviving a cancel would re-apply the
   * intent on the next read and put the abandoned record back under the Back button —
   * `useEditDeepLink`'s one-shot rule, kept by its callers.
   */
  const resetToCreate = useCallback(() => {
    setEditingInterview(null);
    setTitle("");
    setPlace("");
    setLanguage("");
    setAnswers({});
    setSelectedArtisanIds([]);
    setMediaFiles([]);
    setQuestionAudioFiles({});
    if (typeof window !== "undefined" && window.location.search.includes("edit=")) {
      router.replace("/questionnaire");
    }
  }, [router]);

  /**
   * Come out of edit mode WITHOUT blanking the form — the half of {@link resetToCreate} a successful
   * save wants and the other half it does not.
   *
   * The two reset paths below already decide, carefully, what stays on the form after a save: the
   * head of the artisan tick order is kept because the researcher is still sitting with the same
   * person, and a partially-uploaded batch keeps whatever did not land. Calling `resetToCreate`
   * there would throw all of that away in order to clear two variables.
   */
  const leaveEditMode = useCallback(() => {
    setEditingInterview(null);
    if (typeof window !== "undefined" && window.location.search.includes("edit=")) {
      router.replace("/questionnaire");
    }
  }, [router]);

  /**
   * `?edit=<id>` — THE SHARED HOOK, and not a fourth hand-rolled deep link.
   *
   * Every obligation in it is one this page would otherwise have had to get right by itself: the
   * record is fetched BY ID rather than looked up in the page of rows on screen (the reader arrives
   * from the data browser and the row is usually not on page one), the parameter is one-shot and is
   * stripped with `router.replace`, the scroll is deferred one frame so the remounted form has its
   * final height, and the guard released in cleanup is what stops React's StrictMode double-mount
   * deadlocking the load in development.
   *
   * `onNew` IS OMITTED DELIBERATELY. `?new=1` is a render MODE on this page rather than a one-shot
   * intent — the dashboard's Questionnaire tile links to `/questionnaire?new=1` and the page reads it
   * on every render — so consuming it here would close the create form as it opened. `/processes`
   * carries the identical omission for the identical reason, and the hook's header names it.
   */
  const { loading: loadingEdit } = useEditDeepLink<QuestionnaireInterview>({
    endpoint: "/questionnaire/interviews",
    basePath: "/questionnaire",
    targetRef: captureFormRef,
    onEdit: seedFromInterview,
    onError: setError,
    /*
      `!!user` AND NOT A CAPABILITY, and the distinction is the whole of this page's edit rule.

      Taking an interview is open to every signed-in account — that is how a volunteer contributes —
      so there is no client-side predicate that decides who may open this form. Whether THIS account
      may change THIS sitting is the server's call and is made per record by `guard_record_edit`
      inside the PATCH's transaction, which is the only place that can see who recorded it and what
      grants stand. A client-side guess here would either hide an Edit link from somebody entitled to
      it or offer one that 403s, and the hook's own note says what this flag is for: "identity
      resolved", so the deep link does not fire against an unresolved account.
    */
    allowed: !!user,
    errorMessage: "Unable to load that interview"
  });

  /*
    ── THE WHOLE WORKSHOP QUESTION, IN ONE CONTROL WITH TWO BOXES ────────────────────────

    `forms/WorkshopPicker.tsx` owns all of it: "Type of workshop", the "Workshop" box below it whose
    list comes from whichever table that type routes at, the most-recent defaulting, the
    late-submission gate, and the rule that decides whether the chosen workshop is written to
    `workshopId` or to `designWorkshopId`.

    IT REPLACED THREE CONTROLS WITH TWO, and this page was the last web surface still drawing all
    three: `<WorkshopSelect>` over the 378-row `Workshop` table, then `<DesignWorkshopCascade>`,
    which was a KIND box that saved nothing and said so in its own hint plus a second workshop box
    under it. The owner's ruling: "we do not need one separately for each of the type of the
    workshops". The four record forms were converted first; this one and the misc-media form were
    left behind because they were being edited in another lane on the same day.

    WHAT THE CONVERSION CHANGED ABOUT WHAT IS SAVED, said plainly because it is not nothing: those
    two controls could each hold an answer, so an interview could be filed under an ordinary
    workshop AND a design workshop at once. One control gives one answer, and the picker blanks the
    column it is not filing in. Measured on the local corpus on 2026-09-16 before the change:
    of 207 `QuestionnaireInterview` rows, 84 carry a `workshopId`, 0 carry a `designWorkshopId`
    and 0 carry both — so this consolidates nothing that exists here either.

    IT IS ALSO WHAT MAKES THE ARTISAN ROSTER'S SCOPE UNAMBIGUOUS. `artisanScope.singular` in
    `shared/questionnaire-form-contract.json` already said so: `list_artisans` ANDs its filters, and
    while two boxes could both be full the roster had to CHOOSE which of the two ids to scope by.
    Now at most one of them is ever set, so `workshopArtisanParams` is picking between an id and
    nothing rather than between two rival answers.

    CREATE-ONLY, so `isEdit` is left false and the picker may always prefill: an interview is edited
    through the review panel rather than re-opened in this form.
  */
  /*
    SEEDED FROM THE RECORD ON AN EDIT, AND `useWorkshopPicker` WAS BUILT FOR EXACTLY THIS.

    `isEdit` is what stops the picker doing what it does on a create — reaching for the most recent
    workshop this account can see and filling itself in. On an edit that default would silently
    re-file a sitting recorded weeks ago under whatever workshop happens to be newest today, on a
    form the researcher opened to correct a typo. `initialDesignWorkshopId` passes `null` rather than
    `undefined` deliberately: the hook reads `undefined` as "new record, choose for me" and `null` as
    "this record is stored with no workshop, leave it alone", which is the honest reading of a
    sitting that was never filed under one.

    `resetKey` is the record id, so moving from one edit straight to another re-seeds rather than
    keeping the first one's workshop under the second one's title.
  */
  const workshop = useWorkshopPicker(
    editingInterview
      ? {
          initialWorkshopId: editingInterview.workshopId ?? null,
          initialDesignWorkshopId: editingInterview.designWorkshopId ?? null,
          isEdit: true,
          resetKey: editingInterview.id
        }
      : {}
  );

  /**
   * THE WORKSHOP THIS INTERVIEW IS FILED UNDER, as the one thing the artisan roster is scoped by.
   *
   * BOTH IDS ARE STILL READ, AND AT MOST ONE OF THEM IS EVER SET. The record keeps two nullable
   * columns (R1: both tables stay) and the one picker above writes to exactly one of them, chosen
   * by the type box. `workshopArtisanParams` therefore no longer arbitrates between two rival
   * answers — it picks between an id and nothing — but it is kept exactly as it is: it is the one
   * place that knows the plural `workshopIds` goes with a `Workshop` and the singular
   * `designWorkshopId` with a `DesignWorkshop`, and the argument for never sending both spellings
   * is in `components/questionnaires/interviewArtisans.ts`.
   */
  const artisanWorkshopScope: InterviewWorkshopScope = useMemo(
    () => ({ workshopId: workshop.workshopId, designWorkshopId: workshop.designWorkshopId }),
    [workshop.workshopId, workshop.designWorkshopId]
  );
  /**
   * THE ARTISANS THIS WORKSHOP'S INTERVIEWS MAY BE WITH, re-fetched whenever the workshop moves.
   *
   * This page used to run ONE request at mount — `listResource<Artisan>("/artisans", …)` inside
   * `loadMeta()`, with no workshop parameter of any kind — and feed the whole repository to the
   * artisan pickers under a workshop name that was sitting in the box directly above them. The
   * endpoint has accepted `workshopIds` and `designWorkshopId` the entire time; nothing was blocking
   * the filter but the absence of the parameter. The whole rule set — which spelling and why, what
   * an unselected workshop means, why the roster REPLACES rather than merges, why the request is
   * held until the workshop picker has settled, and why a workshop change never unticks anybody —
   * lives in `components/questionnaires/interviewArtisans.ts`. It is a separate file because a rule
   * the handset cannot read is a rule the handset will not match.
   */
  const artisanScope = useWorkshopArtisans({
    scope: artisanWorkshopScope,
    settling: workshopScopeSettling(workshop)
  });
  /**
   * Every artisan row this page has loaded, from the repository-wide reachability probe and from
   * every workshop's roster. Used for LABELS and for the RESP block — never as the picker's offer,
   * which is `artisanScope.scoped` and only that.
   */
  const knownArtisans = artisanScope.known;

  /**
   * The four values the transcript path must read as they are NOW, not as they were at Record.
   *
   * ── THE CLOSURE THIS EXISTS TO ESCAPE ──────────────────────────────────────────────────────────
   * `quickTranscribe` is reached from `recorder.onstop`, and that handler was built inside the
   * `startRecording` call that began the take. So every value it closes over is the value from the
   * render in which the researcher pressed Record — which is minutes and a whole conversation before
   * the transcript comes back. Three of the four go wrong in a way somebody would actually hit:
   *
   *   * `answers` — a researcher who types while the artisan is still speaking would have their
   *     words silently replaced, because the stale copy says the box was empty. That is exactly the
   *     overwrite the owner's "offer it, do not impose it" rule forbids, arriving through the back
   *     door of a closure rather than through the rule.
   *   * `machineText` — the edited flag is computed against it, so a stale copy mis-states the flag.
   *   * `workshop.designWorkshopId` — the picker sits at the top of a long form and is very often
   *     filled in AFTER the first clip. A stale empty id would refuse a transcript this interview is
   *     entitled to, and say the workshop was not named while it is on screen.
   *
   * A ref written in an EFFECT rather than during render: a render can be discarded under concurrent
   * rendering, and this repository already records what a ref written on a discarded render costs
   * (`useLeaveGuard`). By the time an upload has crossed the network every render before it has
   * committed, so the effect has always run.
   */
  const liveRef = useRef({
    answers,
    machineText,
    workshopId: workshop.designWorkshopId,
    language
  });
  useEffect(() => {
    liveRef.current = {
      answers,
      machineText,
      workshopId: workshop.designWorkshopId,
      language
    };
  }, [answers, machineText, workshop.designWorkshopId, language]);

  const questions = useMemo(() => sections.flatMap((section) => section.questions), [sections]);
  const questionsById = useMemo(() => new Map(questions.map((question) => [question.id, question])), [questions]);
  const sectionsById = useMemo(() => new Map(sections.map((section) => [section.id, section])), [sections]);

  const orderedGroups = useMemo(() => {
    return sections.map((section) => [section.code, { section, title: section.title, items: section.questions }] as const);
  }, [sections]);

  /**
   * The saved clips of the sitting being edited, filed under the section each belongs to, plus the
   * ones that belong nowhere.
   *
   * `editingInterview.media` AND NOT A FETCH. `RELATIONS` hydrates `media` on
   * `GET /questionnaire/interviews/{id}`, which is the read `useEditDeepLink` has already made — so
   * the rows are in hand before this form paints. The repository's own `ExistingMedia` panel would
   * open a second request for the same rows; `SavedClips` below says at length why it is not mounted
   * here at all.
   *
   * ONE ROW AND NOT THE ARTISAN GROUP, which is where this deliberately differs from the handset.
   * Android gathers the media of every interview sharing an artisan set, because it has to: it walks
   * `interviewGroupKey` over the whole list. Here `artisanSetKey` is `@unique` repository-wide and
   * `by-artisans` returns exactly ONE row for a set, so "the group" IS this record and a second read
   * would list the same clips twice under two headings.
   */
  const savedClips = useMemo(() => {
    const bySection = new Map<string, MediaFile[]>();
    const other: MediaFile[] = [];
    for (const media of editingInterview?.media ?? []) {
      const sectionId = savedClipSectionId(media, sections);
      if (!sectionId) {
        other.push(media);
        continue;
      }
      const bucket = bySection.get(sectionId);
      if (bucket) bucket.push(media);
      else bySection.set(sectionId, [media]);
    }
    return { bySection, other };
  }, [editingInterview, sections]);

  /**
   * The questions this EDIT arrived carrying typed words for.
   *
   * ⚠ READ OFF THE RECORD AND NEVER OFF `answers`. `answers` is live state, so a box drawn because it
   * currently holds text would disappear the instant the researcher cleared it — the one keystroke
   * after which they most need the box. The stored record does not change while the form is open, so
   * this set is stable for the life of the edit and the box cannot flicker.
   */
  const recordedAnswerIds = useMemo(() => {
    const ids = new Set<string>();
    for (const response of editingInterview?.responses ?? []) {
      if (response.questionId && answerHasWords(response.answerText)) ids.add(response.questionId);
    }
    return ids;
  }, [editingInterview]);

  /**
   * Sections this edit has something in — an answer with words, or a saved clip. Drives which
   * disclosures start open; `sectionOpensOnLoad` carries the rule and the reason.
   */
  const sectionsWithContent = useMemo(() => {
    const ids = new Set<string>(savedClips.bySection.keys());
    for (const section of sections) {
      if (section.questions.some((question) => recordedAnswerIds.has(question.id))) ids.add(section.id);
    }
    return ids;
  }, [savedClips, sections, recordedAnswerIds]);

  /**
   * The Language dropdown's rows, rebuilt whenever the value changes — the same `remember(language)`
   * dependency Android's `languageOptions` carries, and for its reason: the list is the constant
   * vocabulary PLUS whatever free text the record already holds, so it cannot be hoisted to module
   * scope. See `lib/interviewLanguages.ts` for the preserve rule and the one place it deliberately
   * diverges from the handset.
   *
   * On THIS page the extra row can only ever come from an interview being edited elsewhere and
   * handed back — the form here is create-only, so `language` starts "" and the helper returns the
   * plain twenty-four. It is still called rather than inlined, because the rule has exactly one
   * implementation and the next surface that edits an interview gets it for free.
   */
  const languageOptions = useMemo(() => interviewLanguageOptions(language), [language]);

  /**
   * THE ONE ARTISAN THE RESP BLOCK IS ABOUT: the head of the selection, in the researcher's own tick
   * order. `primaryInterviewArtisanId` carries the argument for that rule and its in-repo precedent
   * (`tools.py` derives a tool's scalar `artisanId` from `artisan_ids[0]`, and the handset's tool
   * sheet mirrors it).
   *
   * Looked up in `knownArtisans` and NOT in the workshop's roster: the RESP details must draw for
   * whoever is actually ticked, including the row rescued for a deep-linked artisan the roster does
   * not hold. An empty respondent block over a ticked name is a form that looks broken.
   */
  const primaryArtisanId = primaryInterviewArtisanId(selectedArtisanIds);
  const selectedArtisan = useMemo(
    () => knownArtisans.find((artisan) => artisan.id === primaryArtisanId),
    [knownArtisans, primaryArtisanId]
  );

  // There is a single shared questionnaire entry per exact SET of artisans — we look it up so the
  // researcher sees that it has already been started and which sections others have answered,
  // instead of making a duplicate. `artisanSetKey` sorts, because ticking A then B is the same
  // interview as ticking B then A and the server's own `artisan_set_key` sorts for that reason; the
  // SENT order stays the researcher's, which is what `primaryArtisanId` above reads.
  const selectedSetKey = artisanSetKey(selectedArtisanIds);

  useEffect(() => {
    if (selectedArtisanIds.length === 0) {
      setExistingEntry(null);
      return;
    }
    let active = true;
    const query = selectedArtisanIds.map((id) => `artisanIds=${encodeURIComponent(id)}`).join("&");
    apiFetch<QuestionnaireInterview | null>(`/questionnaire/interviews/by-artisans?${query}`)
      .then((result) => {
        if (active) setExistingEntry(result ?? null);
      })
      .catch(() => {
        if (active) setExistingEntry(null);
      });
    return () => {
      active = false;
    };
  }, [selectedSetKey, selectedArtisanIds]);

  /**
   * What this effect last wrote into `answers`, per question. It is how a stale prefill is told apart
   * from a typed answer, which is the whole difficulty: both are just strings in the same map.
   */
  const prefilled = useRef<Record<string, string>>({});

  /**
   * Which interview-list fetch is the current one. The search box is debounced and the funnel and
   * the pager fire the same effect, so more than one request is routinely in flight; without this a
   * slow answer for an abandoned filter could land last and win. Counted rather than aborted because
   * `listResource` takes no signal, and ignoring the late answer is the part that matters.
   */
  const currentInterviewLoad = useRef(0);

  useEffect(() => {
    if (!selectedArtisan || questions.length === 0) return;
    const respondentAnswers: Record<string, string> = {};
    questions
      .filter((question) => question.sectionCode === "RESP")
      .forEach((question) => {
        const prompt = question.prompt.toLowerCase();
        if (prompt.includes("name")) respondentAnswers[question.id] = selectedArtisan.name;
        else if (prompt.includes("craft")) respondentAnswers[question.id] = selectedArtisan.craft?.name ?? "";
        else if (prompt.includes("state") || prompt.includes("district") || prompt.includes("village")) respondentAnswers[question.id] = selectedArtisan.place;
        else if (prompt.includes("gender")) respondentAnswers[question.id] = selectedArtisan.gender ?? "";
        else if (prompt.includes("contact")) respondentAnswers[question.id] = selectedArtisan.phone ?? selectedArtisan.email ?? "";
        else if (prompt.includes("date")) respondentAnswers[question.id] = new Date().toLocaleDateString("en-IN");
        else if (prompt.includes("interviewer")) respondentAnswers[question.id] = user?.name ?? user?.email ?? "";
      });
    // Overwrite only what is still blank or still exactly what this effect put there last time.
    // The old rule kept every existing answer ahead of the new prefill, which read as "never clobber
    // the researcher" but really meant the RESP block froze on the FIRST artisan picked: change the
    // primary artisan and the interview kept the previous artisan's name, craft, place, gender and
    // phone, and saved them against the new one.
    const previous = prefilled.current;
    prefilled.current = respondentAnswers;
    setAnswers((current) => {
      const next = { ...current };
      let changed = false;
      Object.entries(respondentAnswers).forEach(([questionId, value]) => {
        const existing = next[questionId] ?? "";
        if (existing && existing !== (previous[questionId] ?? "")) return;
        if (existing === value) return;
        next[questionId] = value;
        changed = true;
      });
      return changed ? next : current;
    });
  }, [questions, selectedArtisan, user]);

  /**
   * The instrument — the sections and questions the rest of this screen draws. They change only when
   * an admin edits the questionnaire, so they load once (and again on `onChanged`) rather than per
   * list page or filter.
   *
   * THE ARTISAN LIST USED TO COME DOWN HERE TOO, in the same `Promise.all`, unscoped, once, at mount.
   * That was the whole of defect (1): one repository-wide page offered to two artisan pickers under
   * whichever workshop the boxes above them happened to be showing, never asked again when the
   * workshop moved. It now belongs to `useWorkshopArtisans`, which is keyed on the workshop and is
   * the only thing on this page that fetches artisans. Nothing about the instrument depends on the
   * roster, so splitting them costs no round trip that was not already being made.
   */
  async function loadMeta() {
    try {
      const sectionList = await apiFetch<QuestionnaireSection[]>("/questionnaire/sections");
      setSections(sectionList);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load questionnaire");
    }
  }

  /**
   * Open on the artisan this researcher was last documenting.
   *
   * An interview is the record most often taken straight after a product or a tool — the artisan is
   * sitting right there — so the artisan is the one thing worth carrying in. Nothing narrower
   * transfers: an interview covers a person, not their products, and this form has no field for one.
   *
   * THE SCOPE IS THE REPOSITORY-WIDE PROBE (`artisanScope.referenceState` / `knownArtisans`) AND NOT
   * THE WORKSHOP'S ROSTER, which is the whole reason `useWorkshopArtisans` keeps two loads. This list
   * answers "is the carried artisan still REACHABLE" — an artisan who is alive, visible and simply
   * documented at another workshop must not be pruned as though they had been deleted, and the
   * carried bag also carries a WORKSHOP that `onApply` is about to write into the picker.
   */
  const carry = useCarryContext({
    scopes: [carryScope("artisan", artisanScope.referenceState, knownArtisans)],
    applies: ["artisan", "workshop"],
    onApply: (context) => {
      // Seeds the SET, and only when nothing is ticked yet — a carried suggestion must not push a
      // name into a selection the researcher has already started building.
      if (context.artisanId) {
        setSelectedArtisanIds((current) => (current.length > 0 ? current : [context.artisanId as string]));
      }
      if (context.workshopId && !workshop.touched) workshop.setWorkshopId(context.workshopId);
    }
  });
  /** "Change": drop the carried artisans so the researcher picks from scratch. */
  function clearCarriedContext() {
    carry.change();
    setSelectedArtisanIds([]);
  }

  async function loadInterviews() {
    const generation = (currentInterviewLoad.current += 1);
    try {
      const result = await listResource<QuestionnaireInterview>("/questionnaire/interviews", {
        page,
        pageSize: 20,
        artisanId: funnel.artisanId || undefined,
        workshopId: funnel.workshopId || undefined,
        search: searchQuery || undefined
      });
      // The answer to a question already moved on from must not land last and win — see the ref.
      if (generation !== currentInterviewLoad.current) return;
      setData(result);
      setError(null);
    } catch (err) {
      if (generation !== currentInterviewLoad.current) return;
      setError(err instanceof Error ? err.message : "Unable to load questionnaire interviews");
    }
  }

  useEffect(() => {
    loadMeta();
  }, []);

  // Backend already returns interviews most-recent-first (createdAt desc); the funnel narrows by
  // artisan AND by workshop, and the search box by text.
  //
  // WORKSHOP IS IN BOTH THE PARAMS AND THIS DEPENDENCY ARRAY BECAUSE IT USED TO BE IN NEITHER, and
  // the comment that stood here — "artisan is the only list param the interviews endpoint supports"
  // — was simply wrong: ``list_interviews`` has declared ``workshopId`` and applied
  // ``where["workshopId"]`` all along (backend/app/api/routes/questionnaire.py). The dropdown above
  // the table rendered the workshop as the active filter and no byte about it ever left the browser,
  // so the list underneath stayed the whole repository — every other designer's interviews at every
  // other workshop — with the pager's ``total`` counting them all. Nothing was hidden, which is what
  // made it so hard to see: the reader takes the rows below a filter control as that workshop's
  // interviews and acts on rows that are not.
  //
  // Both halves are needed. Without the dependency the fetch does not re-run at all when a workshop
  // is picked with no artisan selected (``selectWorkshop`` clears an already-empty ``artisanId`` and
  // ``setPage(1)`` is a no-op on page 1); without the param it re-runs and asks for everything.
  //
  // CRAFT IS DELIBERATELY ABSENT and that is not an oversight: this endpoint has no craft parameter,
  // so the craft dropdown legitimately does nothing here but cascade into the artisan picker
  // (FunnelFilters narrows the artisans it offers). Do not "restore parity" by adding craftId — it
  // would be silently ignored by the server, which is the same defect in the other direction.
  useEffect(() => {
    loadInterviews();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, funnel.artisanId, funnel.workshopId, searchQuery]);

  // Leaving the page mid-recording must release the microphone and the clock, not leak either.
  useEffect(() => {
    return () => {
      stopElapsedTimer();
      recorderRef.current?.stream.getTracks().forEach((track) => track.stop());
      streamRef.current?.getTracks().forEach((track) => track.stop());
    };
  }, []);

  // Eager upload for the per-question recordings, matching every other capture surface. An
  // interview is the longest form in the app — a researcher can spend half an hour working down
  // the sections — and each answer is an audio clip. Waiting until Save to start the transfer
  // means the whole interview's audio goes up in one blocking burst at the end, on whatever
  // connection the field site has; staging each clip when the recorder stops spreads it across
  // the time already being spent. Saving then only links the objects. One owner for all of them
  // (rather than per question) keeps this a single row in the tray instead of thirty.
  const stagedQuestionAudio = useMemo(() => Object.values(questionAudioFiles).flat(), [questionAudioFiles]);
  const questionAudioStaging = useEagerStaging(stagedQuestionAudio, "Question recordings");
  const stagedByFile = useMemo(
    () => new Map(stagedQuestionAudio.map((file, index) => [file, questionAudioStaging.entries[index]])),
    [stagedQuestionAudio, questionAudioStaging.entries]
  );

  /** "3 clips · 2 uploaded" — the chip under a recorder, so "ready" is a fact not a hope. */
  function clipCountLabel(key: string): string {
    const files = questionAudioFiles[key] ?? [];
    const entries = files.map((file) => stagedByFile.get(file) ?? null);
    const ready = entries.filter((entry) => entry?.status === "ready").length;
    const failed = entries.filter((entry) => entry?.status === "error").length;
    const clips = `${files.length} clip${files.length === 1 ? "" : "s"}`;
    if (failed) return `${clips} · ${failed} failed to upload, will retry on save`;
    if (ready === files.length) return `${clips} · uploaded`;
    return `${clips} · ${ready} of ${files.length} uploaded`;
  }

  useEffect(() => {
    const nextPreviews: Record<string, PreviewMedia[]> = {};
    Object.entries(questionAudioFiles).forEach(([questionId, files]) => {
      nextPreviews[questionId] = files.map((file, index) => ({
        key: `${questionId}-${file.name}-${file.size}-${file.lastModified}-${index}`,
        name: file.name,
        mediaType: "AUDIO",
        mimeType: file.type || "audio/webm",
        sizeBytes: file.size,
        url: URL.createObjectURL(file)
      }));
    });
    setQuestionAudioPreviews(nextPreviews);
    return () => {
      Object.values(nextPreviews).flat().forEach((item) => {
        if (item.url) URL.revokeObjectURL(item.url);
      });
    };
  }, [questionAudioFiles]);

  function handleSearchChange(value: string) {
    setSearchInput(value);
    // Clearing the box (the red X) immediately drops the filter, matching the funnel's live feel.
    if (value === "") {
      setSearchQuery("");
      setPage(1);
    }
  }

  function applySearch() {
    setSearchQuery(searchInput.trim());
    setPage(1);
  }

  /**
   * Record against one clip key — a question id, or a section's key for a single whole-section take.
   * `filenameBase` is the human part of the object name; the extension follows the codec the browser
   * actually gave us.
   */
  async function startRecording(key: string, filenameBase: string) {
    try {
      if (recordingKey) recorderRef.current?.stop();
      const stream = await navigator.mediaDevices.getUserMedia({ audio: SPEECH_AUDIO_CONSTRAINTS });
      streamRef.current = stream;
      chunksRef.current = [];
      // Ask the browser what it can actually record: Safari/iOS produces audio/mp4, so a hardcoded
      // "audio/webm" name and type would lie about the bytes and break playback and transcription.
      const preferredType = pickAudioRecorderMimeType();
      // `audioBitsPerSecond` PINNED, not left to the browser — see `CLIP_BITS_PER_SECOND`. It is what
      // turns the duration ceiling below into a size guarantee the dictation route's 6 MB limit can
      // be reasoned about against; without it "fifteen minutes" is 3 MB on one browser and 14 on
      // another. Spread beside the mime type rather than replacing that branch, because a browser
      // that reports no supported type must still get `undefined` for it.
      const recorder = new MediaRecorder(stream, {
        ...(preferredType ? { mimeType: preferredType } : {}),
        audioBitsPerSecond: CLIP_BITS_PER_SECOND
      });
      recorderRef.current = recorder;
      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) chunksRef.current.push(event.data);
      };
      recorder.onstop = () => {
        const mimeType = recorder.mimeType || preferredType || "audio/webm";
        const extension = audioExtensionForMimeType(mimeType);
        const blob = new Blob(chunksRef.current, { type: mimeType });
        const file = new File([blob], `${filenameBase}.${extension}`, { type: mimeType });
        setQuestionAudioFiles((current) => ({
          ...current,
          [key]: [...(current[key] ?? []), file]
        }));
        // THE SAME BYTES GO TWO WAYS, and they are two different acts on two different clocks. The
        // clip above is evidence: it uploads with the interview and the media queue transcribes it
        // into refined, translated, speaker-labelled Markdown whenever the drain next runs. This
        // call is the immediate one — plain text, seconds, straight into the box the researcher is
        // looking at. Fired and not awaited, because `onstop` must return for the recorder teardown
        // below to run; `quickTranscribe` owns its own failures and never rejects.
        void quickTranscribe(key, file);
        stream.getTracks().forEach((track) => track.stop());
        // Tapping "Record this question" on ANOTHER question stops this recorder while the next one
        // is already running — only tear down the shared recording UI if this is still the live one.
        if (recorderRef.current !== recorder) return;
        streamRef.current = null;
        recorderRef.current = null;
        stopElapsedTimer();
        setQuestionStream(null);
        setQuestionElapsedMs(0);
        setRecordingKey(null);
      };
      // The clock starts when the recorder really starts; only it needs a timer, because the bars
      // run on <Waveform>'s own requestAnimationFrame loop.
      recorder.onstart = () => {
        const startedAt = Date.now();
        setQuestionElapsedMs(0);
        elapsedTimerRef.current = window.setInterval(() => setQuestionElapsedMs(Date.now() - startedAt), 250);
        // THE CEILING. It stops the take and keeps every second of it — see `CLIP_MAX_MS`. The
        // sentence is written before `stop()` because `onstop` clears `recordingKey`, and a message
        // keyed on a clip whose recorder has already been torn down would have nowhere to render.
        clipCapTimerRef.current = window.setTimeout(() => {
          if (recorderRef.current !== recorder || recorder.state === "inactive") return;
          setQuickProblem((current) => ({
            ...current,
            [key]: `Recording stopped at ${CLIP_MAX_MS / 60000} minutes. The take is kept — record again to continue.`
          }));
          recorder.stop();
        }, CLIP_MAX_MS);
      };
      recorder.start();
      setQuestionStream(stream);
      setRecordingKey(key);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to start recording");
    }
  }

  /**
   * Forget everything the transcription of the LAST interview left behind.
   *
   * Called from both reset paths beside `setAnswers({})`, and it is not tidiness. `machineText` is
   * what the edited flag is computed against, so a surviving entry would flag the NEXT interview's
   * question — a fresh, empty box — as differing from a previous sitting's machine words, which is a
   * chip saying "Edited" on an answer nobody has typed yet. A surviving `offeredTranscript` would be
   * worse: it would offer one artisan's words as a replacement for another's answer.
   *
   * `answerSeed` is deliberately NOT cleared. It only ever increments, and it exists solely to force
   * a remount; resetting it to zero for a key whose editor is still mounted at seed 3 would produce
   * the same key twice across two different documents, which is the one thing a remount key must
   * never do.
   */
  function clearQuickTranscripts() {
    setMachineText({});
    setOfferedTranscript({});
    setQuickProblem({});
  }

  function stopRecording() {
    recorderRef.current?.stop();
  }

  /**
   * Put the machine's words where they belong, and never over a person's own.
   *
   * A QUESTION KEY has a box, so the words go into it — and they are APPENDED, never substituted.
   * Two clips against one question are two parts of one answer: a researcher stops the recorder when
   * the artisan pauses and starts it again when she resumes, so the second take is the rest of the
   * sentence and not a better version of the first. This is the rule `appendDictatedPhrase` already
   * exists for on every dictated box in the repository — *"a commit that replaced the box would
   * delete everything already in it at the first pause for breath"* — and it is reused rather than
   * re-decided, so the microphone in the editor's toolbar and the voice note behave identically.
   *
   * WHERE THE BOX HAS BEEN EDITED, NOTHING IS WRITTEN AT ALL. The owner's rule is that a later
   * transcript *"must not silently overwrite their words. Offer it, do not impose it"*, so an edited
   * box gets an offer with the text visible in it. An empty box, and a box still holding exactly what
   * this page last put there, both count as untouched — there is nothing of anybody's to lose.
   *
   * `machineText` IS SET TO THE WHOLE MERGED VALUE, not to the new fragment, because it is what the
   * edited flag compares the box against. Storing only the last take would make the box differ from
   * it the instant a second clip landed and flag an untouched answer as edited.
   *
   * A SECTION KEY has no box, and that is not an oversight to fix later. A whole-section take covers
   * a dozen questions, so there is no single answer it is the answer to; writing it into the first
   * question would file a section's worth of conversation under one prompt, and splitting it across
   * the section's boxes would be the page guessing at attribution only the researcher can make. It is
   * rendered under the section recorder instead, where it can be read, copied and downloaded.
   */
  function applyTranscript(key: string, text: string) {
    if (isSectionClipKey(key)) {
      // A section's takes accumulate the same way, so a second take does not erase the first.
      setMachineText((current) => ({
        ...current,
        [key]: appendDictatedPhrase(current[key] ?? "", text)
      }));
      return;
    }
    // Read live, never from this function's closure — see `liveRef` for the three ways the closure
    // is wrong by the time a transcript comes back.
    const inBox = liveRef.current.answers[key] ?? "";
    const previous = liveRef.current.machineText[key] ?? "";
    if (inBox.trim() && inBox.trim() !== previous.trim()) {
      setOfferedTranscript((current) => ({ ...current, [key]: text }));
      return;
    }
    // `appendDictatedToStored` AND NOT `appendDictatedPhrase`, and this is the CORRUPTING half of the
    // rich-text read boundary rather than the merely-ugly half. `inBox` is the stored column value,
    // which the answer box being a `RichTextField` makes prose for most answers and `{"blocks":[…]}`
    // for one somebody bolded. A bare append concatenates the machine's words onto the end of a JSON
    // string, producing a value that is neither valid JSON nor readable prose: the editor cannot parse
    // it back, so the researcher's formatted answer is REPLACED on screen by braces with the
    // transcript stuck on the end, and it saves in that state. The shared function appends INTO the
    // document when there is one, and is byte-for-byte `appendDictatedPhrase` when there is not — the
    // single-space joiner, and the sentence continued rather than broken, both survive.
    const merged = appendDictatedToStored(inBox, text);
    setAnswers((current) => ({ ...current, [key]: merged }));
    setMachineText((current) => ({ ...current, [key]: merged }));
    // The editor seeds from `defaultValue` once and never re-reads it, so a new key is the only way
    setAnswerSeed((current) => ({ ...current, [key]: (current[key] ?? 0) + 1 }));
  }

  /**
   * Post one just-recorded clip to the dictation API and put the words in the box.
   *
   * ══════════════════════════════════════════════════════════════════════════════════════════════
   * THE CONSENT DECISION, WRITTEN DOWN — read this before changing anything here
   * ══════════════════════════════════════════════════════════════════════════════════════════════
   *
   * This is the point at which a named artisan's recorded voice leaves the device for ElevenLabs,
   * Deepgram or OpenAI, synchronously, while she is still sitting there. The route
   * `POST /design-workshops/{id}/dictate` is the only one in this application that sends audio to a
   * provider under a gate, and its gate is `DesignWorkshop.dictationConsent` — per workshop, because
   * (`services/dictation_consent.py`) "a consent given for one cluster would silently cover the next
   * one, and the artisan whose voice it is changes between them".
   *
   * SO THE ANSWER IS: THE INTERVIEW'S OWN DESIGN WORKSHOP, OR NOTHING IS SENT.
   *
   * This form already asks which design and prototype workshop an interview is filed under — since
   * 2026-09-16 through `useWorkshopPicker`'s second box, reached by choosing the Design & Prototype
   * type in the first one — and where it names one there is an id whose consent column governs
   * exactly this artisan, this cluster, this week. That id is what goes in the URL. Where the picker
   * is empty — which is legitimate, an interview is often taken outside any design workshop —
   * NO REQUEST IS MADE AT ALL, and the box says so in one line. The clip still uploads and the queue
   * still transcribes it, exactly as before this feature existed.
   *
   * THE TWO-DROPDOWN RULING MADE THAT EMPTY STATE REACHABLE BY CHOOSING A TYPE, which it was not
   * when two workshop boxes stood side by side: a researcher who picks "Skill Upgradation" is
   * filing at a `Workshop`, so `designWorkshopId` is blank and there is no consent column to read.
   * That is the same state an interview filed under nothing has always been in, it is covered by
   * the same one-line notice, and it is NOT closed by falling back to some other workshop's
   * consent — which is the whole argument above, one door along. Android's `QuestionnaireForm`
   * reaches the identical state through `link.designWorkshopId()` and says so at its own call site.
   *
   * ── THE TWO ALTERNATIVES, AND WHY BOTH ARE WORSE ───────────────────────────────────────────────
   * A WORKSHOP-LESS DICTATION ROUTE WITH ITS OWN GATE would be a second consent regime to keep in
   * step with the first, and the only thing it could gate on is the account or the interview — an
   * account-level switch is the one this repository has already refused by name, and an interview has
   * no artisan-signed answer to carry. The id-less `POST /design-workshops/dictate` already exists
   * and answers 410, retired precisely because it enforced nothing.
   *
   * MAKING THE WORKSHOP PICKER REQUIRED would buy the gate by breaking the record: interviews are
   * taken by researchers who are not running a design workshop at all, and a required picker would
   * either block those sittings or teach everyone to pick an unrelated workshop to get past it, which
   * is a consent answer with the wrong artisan's name on it.
   *
   * ── AND THE GATE IS NOT THEATRE, WHICH TOOK A BACKEND CHANGE ───────────────────────────────────
   * The same bytes also go to the media queue, and until 2026-08-31 `transcription_verdict` read a
   * questionnaire clip as NOT_WORKSHOP_MATERIAL and sent it ungated. So a clip on a REFUSED workshop
   * would have been refused here and handed to a provider by the drain two hours later — one voice,
   * one consent answer, two opposite outcomes, and the second one silent.
   * `dictation_consent.interview_workshop_id` closes that: where an interview names a workshop, both
   * paths now ask the same column. Where it names none, both behave exactly as they always have.
   *
   * ══════════════════════════════════════════════════════════════════════════════════════════════
   *
   * IT NEVER REJECTS. It is called un-awaited from `recorder.onstop`, and an unhandled rejection
   * there would surface as a console error and nothing on screen — the researcher would be left
   * watching a box that never fills, with no idea whether to wait or to type.
   */
  async function quickTranscribe(key: string, file: File) {
    // Live, because the workshop picker is at the top of a long form and is very often filled in
    // after the first clip is recorded. See `liveRef`.
    const workshopId = liveRef.current.workshopId;
    const refuse = (message: string) => setQuickProblem((current) => ({ ...current, [key]: message }));
    setQuickProblem((current) => {
      const next = { ...current };
      delete next[key];
      return next;
    });
    if (!workshopId) {
      refuse("Instant transcript needs a design workshop named above. The clip is saved and transcribed later.");
      return;
    }
    if (file.size > DICTATE_MAX_BYTES) {
      // Checked against the ACTUAL blob and not against the elapsed time: `audioBitsPerSecond` is a
      // hint the encoder may miss. Saying it plainly beats spending the upload to be told.
      refuse("Too long for an instant transcript. The clip is saved and transcribed later.");
      return;
    }
    setTranscribing((current) => ({ ...current, [key]: true }));
    try {
      const result = await dictateAudio(file, liveRef.current.language, workshopId);
      const text = (result.text ?? "").trim();
      // `dictationAnswerSentence` owns every non-COMPLETED outcome — EMPTY, FAILED, RATE_LIMITED and
      // a status this build has not heard of — so the sentences a designer meets here are the same
      // ones the workshop microphone gives them, worded once.
      if (!text) refuse(dictationAnswerSentence(result));
      else applyTranscript(key, text);
    } catch (err) {
      // A 409 here IS the consent refusal, and `gate_refusal` writes a sentence naming the next move
      // (ask the artisan, or record the answer instead). `readableError` surfaces the server's own
      // words rather than replacing them with a generic failure, which is the whole point of that
      // sentence having been written where the consent state is known.
      refuse(readableError(err, "That recording could not be transcribed just now."));
    } finally {
      setTranscribing((current) => {
        const next = { ...current };
        delete next[key];
        return next;
      });
    }
  }

  /**
   * How one clip key is described on the way out: the caption, the metadata that files it, and the
   * tray label. `null` for a key whose question or section is no longer in the form.
   *
   * A whole-section take carries the section, not a question — "Section audio: D RAW MATERIALS…",
   * which is exactly what Android writes and what the backend already parses
   * (`_CAPTION_SECTION` in services/media_naming.py, and `sectionCode` in the completion matrix).
   * Attribution to artisans is not carried here and must not be: the clip links to the interview,
   * and the interview links to every artisan in the set, so a group sitting's one section recording
   * counts for all of them — which is what a group sitting means.
   */
  function clipBatch(key: string): { caption: string; extraMetadata: Record<string, unknown>; trayLabel: string } | null {
    if (isSectionClipKey(key)) {
      const section = sectionsById.get(key.slice(SECTION_CLIP_PREFIX.length));
      if (!section) return null;
      return {
        caption: `Section audio: ${section.code} ${section.title}`.trim(),
        extraMetadata: { sectionId: section.id, sectionCode: section.code, sectionTitle: section.title },
        trayLabel: `Section ${section.code} audio`
      };
    }
    const question = questionsById.get(key);
    if (!question) return null;
    return {
      caption: `Question audio: ${question.sectionCode}${question.sortOrder} - ${question.prompt}`,
      extraMetadata: { questionId: question.id, questionPrompt: question.prompt, sectionCode: question.sectionCode },
      trayLabel: `Q${question.sectionCode}${question.sortOrder} audio`
    };
  }

  /**
   * ══ THE FOLD, ON THE RESEARCHER'S EXPLICIT INSTRUCTION ═══════════════════════════════════════
   *
   * THE FLOW THIS SERVES. Two researchers recorded ONE artisan set as TWO sittings, each titled by
   * the sections it covered — "D Black Pottery" and an "F" one — and the F sitting omitted an
   * artisan. Adding that artisan makes F's artisan-set key EQUAL D's, `replace_interview_artisans`
   * hits the unique index, and the correction died as a 409 with nowhere to go: a CREATE for a taken
   * set has always folded, an EDIT could not fold at all.
   *
   * THE RULING (owner, 2026-09-20) is that an edit may fold as a create already does — explicitly,
   * never silently. So the refusal still refuses, and the move happens only because a person answered
   * a dialog that NAMES the interview their work is going into.
   * `POST /questionnaire/interviews/{id}/merge-into/{targetId}` is a second, differently-named
   * endpoint, called from here and from nowhere else.
   *
   * ⚠ AN EDIT ONLY, and the caller enforces it. A create never reaches this: `create_interview` folds
   * a colliding POST into the holder by itself, so there is no source row to move and no id to put in
   * the path.
   *
   * THE CONFIRM IS THE REPOSITORY'S OWN `useConfirm`, `tone: "warning"` rather than `"danger"`: this
   * is not a delete. Every answer and every clip lands on the surviving interview, which is precisely
   * what the note under the question says — and what makes a reflex Enter here recoverable.
   *
   * RETURNS whether the refusal has been DEALT WITH. `false` means the researcher declined and the
   * caller still owes them a sentence saying why nothing was saved.
   */
  async function offerMergeIntoHolder(sourceId: string, holder: { id: string; title: string }): Promise<boolean> {
    const ok = await confirm({
      title: `“${holder.title}” already covers this set of artisans`,
      body: `There is one questionnaire entry per set of artisans, so this edit cannot be saved as a second one. Move this interview's answers and recordings into “${holder.title}” instead?`,
      note: "Nothing is thrown away: every answer and every recording moves onto that interview, and this one is removed once they have. Where the two sittings answer the same question differently the move is refused rather than guessed at, and you will be told which questions they are.",
      confirmLabel: "Move into that interview",
      tone: "warning"
    });
    if (!ok) return false;
    try {
      await apiFetch<QuestionnaireInterview>(`/questionnaire/interviews/${sourceId}/merge-into/${holder.id}`, {
        method: "POST"
      });
    } catch (err) {
      /*
        THE REFUSAL IS SURFACED WITH ITS LIST, NOT SUMMARISED AWAY. `merge_answer_conflict` fires when
        both sittings answer one question with DIFFERENT words: the server moves nothing and names
        every such question, because silently picking a winner would destroy a researcher's words
        under a 200 and the losing row goes with the deleted source. The two other refusals this
        route can raise — a cross-workshop merge, and an artisan only the source covers — carry no
        list and land here as their own sentence, which is the right amount of screen for them.
      */
      setMergeOutcome({
        moved: false,
        message: readableError(err, "That move was refused, and nothing on either interview was changed."),
        questions: mergeConflictQuestions(err)
      });
      return true;
    }
    setMergeOutcome({
      moved: true,
      message: `This interview's answers and recordings are now on “${holder.title}”, which is in the list below. This form has been cleared.`,
      questions: []
    });
    /*
      THE SOURCE ROW IS GONE, so the form must not go on claiming to be editing it — the next Save
      would PATCH a deleted id. `resetToCreate` rather than `leaveEditMode` because the fields on
      screen describe a record that no longer exists, and it also strips `?edit=`, which would
      otherwise re-apply on the next read and re-open the deleted record under the Back button.
    */
    resetToCreate();
    if (page !== 1) setPage(1);
    else await loadInterviews();
    return true;
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // React nulls event.currentTarget after the first await — capture it before any async work.
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    // A workshop that has already ended makes this a late submission needing admin approval — say so
    // before anything is written. Resolves true immediately when there is nothing to warn about.
    if (!(await workshop.confirmSubmission())) return;
    setSaving(true);
    setError(null);
    // This press IS the retry the previous warning asked for, so the warning goes before it runs.
    setUploadError(null);
    // Same rule for the fold's verdict: a new save is a new outcome, and a stale "Moved." over a
    // fresh refusal is the reassurance this panel exists to make true.
    setMergeOutcome(null);
    const artisanIds = selectedArtisanIds;
    const responses = Object.entries(answers)
      .filter(([, answerText]) => answerText.trim())
      .map(([questionId, answerText]) => ({ questionId, answerText: answerText.trim() }));
    // HOISTED OUT OF THE `try` because the CATCH needs it: whether this press was an edit is what
    // decides whether a colliding artisan set can be offered a fold at all, and a create must never
    // be — see `offerMergeIntoHolder`. Declared here rather than read off `editingInterview` again in
    // the catch so both branches are answering the same question about the same press.
    const editingId = editingInterview?.id ?? null;
    try {
      const location = locationFromForm(form);
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      const interviewTitle = textValue(form, "title") || `Interview ${new Date().toLocaleDateString()}`;
      /*
        ── THE KEYS BOTH BODIES SHARE ────────────────────────────────────────────────────────────
        `interviewDate` is deliberately not sent by either: the server derives it from `recordedAt`.

        `workshopId` and `designWorkshopId` ARE sent on an edit, on the owner's ruling of 2026-09-20,
        and that is safe only because `useWorkshopPicker` above is seeded from the record with
        `isEdit: true`. A picker the reader never touched therefore hands back the workshop the
        sitting was already filed under, rather than the "most recent workshop I can see" a blank
        create form would have defaulted to. Take that seeding away and these two keys become a
        silent re-filing on every correction.
      */
      const commonPayload = {
          title: interviewTitle,
          place: textValue(form, "place"),
          language: textValue(form, "language"),
          notes: textValue(form, "notes"),
          status: canPickStatus ? textValue(form, "status") || "APPROVED" : "PENDING",
          workshopId: workshop.workshopId || null,
          designWorkshopId: workshop.designWorkshopId || null,
          artisanIds,
          responses
      };
      /*
        ── THREE KEYS THE EDIT BODY LEAVES OUT, AND EACH OMISSION IS AN API RULE ─────────────────

        · `recordedAt` / `recordedTimezone` — ACCEPTED by the update schema, which is exactly why
          omitting them had to be a decision rather than an oversight. The "Captured at" row is
          re-read from a form that has just remounted in an office, so sending them would restamp
          last week's fieldwork every time somebody corrects a typo.

        · `location` WHEN THERE IS NONE — `forbid_clearing_location` is "omit to keep, send to
          replace, never null", so an edit typed indoors with no fix must leave the key OFF rather
          than send an empty one. Spread conditionally for that reason and not for tidiness.

        · `questionnaireId` — not sent by either body, and not because of a rule this page could
          break: designer-portal has ONE global capture instrument and `QuestionnaireInterview`
          carries no such column at all. Said here because the sister repository's version of this
          form does send one, and a reader comparing the two should know the difference is a schema
          difference rather than a missing feature.
      */
      const interviewPayload = editingId
        ? { ...commonPayload, ...(location ? { location } : {}) }
        : { ...commonPayload, recordedAt, recordedTimezone, location };
      // Offline this queues the whole interview — answers, the interview audio and every
      // per-question or whole-section clip — to the outbox. An interview is the one record that
      // cannot be reconstructed later: the artisan has gone home.
      const outcome = await saveOrQueue<QuestionnaireInterview>({
        label: `Interview · ${interviewTitle}`,
        // AN EDIT PATCHES THE RECORD'S OWN PATH; A CAPTURE POSTS TO THE COLLECTION. `saveOrQueue`
        // already took a method, so an edit made with no signal is banked and replays as the PATCH
        // it was — rather than as a second sitting, which under one-entry-per-artisan-set is the 409
        // (or the silent fold) this whole edit path exists to stop.
        endpoint: editingId ? `/questionnaire/interviews/${editingId}` : "/questionnaire/interviews",
        method: editingId ? "PATCH" : "POST",
        body: interviewPayload,
        media: [
          {
            files: mediaFiles,
            linkedRecordType: "questionnaire",
            caption: `Interview audio for ${interviewTitle}`,
            location,
            recordedAt,
            recordedTimezone,
            transcribeAudio: true
          },
          // One batch per clip key so the caption and the metadata that place a clip under its
          // answer — or under its whole section — survive the round trip through IndexedDB.
          ...Object.entries(questionAudioFiles).flatMap(([key, files]) => {
            const batch = clipBatch(key);
            if (!batch || !files.length) return [];
            return [
              {
                files,
                linkedRecordType: "questionnaire",
                caption: batch.caption,
                location,
                recordedAt,
                recordedTimezone,
                transcribeAudio: true,
                extraMetadata: batch.extraMetadata
              }
            ];
          })
        ]
      });
      if (outcome.queued) {
        // OutboxBanner at the top of the page names the entry and says where it lives.
        formElement.reset();
        // `reset()` cannot reach these three: they are React state, and it rewrites the DOM without
        // telling React. Left out, the queued interview's title, place and language would still be
        // on screen and would be saved again with the next one. See their declaration.
        setTitle("");
        setPlace("");
        setLanguage("");
        setAnswers({});
        clearQuickTranscripts();
        setMediaFiles([]);
        setQuestionAudioFiles({});
        // KEEP THE HEAD, DROP THE REST — the behaviour the two old controls had between them, now
        // that they are one array. "Primary artisan" was never cleared here and "Additional
        // artisans" always was, and the asymmetry was deliberate: the researcher is still sitting
        // with the same person, so the next interview opens on them, while the others in a group
        // sitting are not carried into a sitting they were not part of.
        setSelectedArtisanIds((current) => current.slice(0, 1));
        /*
          LEAVING EDIT MODE IS PART OF THE RESET, on BOTH save branches.
        
          A banked PATCH the server has not seen yet must not leave the form still claiming to be editing
          that record: the next Save would be a second PATCH of a sitting whose first is still in the
          outbox, and the "Editing interview" banner would assert a state the researcher has finished with.
          The URL is stripped for the same reason `resetToCreate` strips it — a surviving `?edit=`
          re-applies on the next read and puts the finished record back under the Back button.
        */
        leaveEditMode();
        setSaving(false);
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        return;
      }
      const saved = outcome.saved;
      /*
        ── ONLY WHAT LANDED LEAVES THE FORM; EVERYTHING ELSE STAYS ON IT ─────────────────────
        Almost every file on this screen is a RECORDER-PRODUCED `File`: bytes that exist nowhere
        else, made once, in front of an artisan who has since gone home. This handler used to read
        only the `uploaded` half of each batch result and then clear `mediaFiles` and
        `questionAudioFiles` regardless — so any batch that half landed took the other half with it,
        with nothing on screen to say a clip had ever been there. The crafts and workshops pages
        already read what did NOT land and return before their reset, off `outcomes`; this was the one
        that did not.

        ARTISANFORM, TOOLFORM AND PRODUCTFORM STILL READ `failed`, AND THAT IS NOT YET ENOUGH FOR THEM
        — said here because this comment used to claim it was. On their own pages nothing unmounts and
        the card does keep the whole batch. HOSTED, it does not survive: their partial-failure branch
        sets the banner and then calls `onCreated`, and `InlineRecordDialog.finish` closes the dialog
        over it while `StageRecordEmbed` re-keys the form into edit mode (its own T-REMOUNT paragraph
        says so) — either way the form unmounts and the stranded bytes go with it, on the hosted path
        the whole embedded-record feature exists for. So those three still want the `outcomes`
        migration AND a way to hand the stranded files to whoever replaces them; the migration alone
        would only make the banner truthful for the seconds before it disappears.

        WHICH file failed is read off `outcomes`, never off `failed[].name`: the names are not unique
        — two takes of one question are both `question-audio-...webm` — so a name match would put back
        the wrong take. This used to zip `uploadedByIndex` against the page's own `mediaFiles`/`files`
        array, which was correct but was two arrays that had to stay the same length by agreement;
        `outcomes` carries the `File` inside the entry, so there is nothing left to line up. See
        BatchResult in lib/media.ts, where that zip is documented as a shipped bug twice over.

        AND THE FORM IS PRUNED, NOT REPLACED. The pass records the `File` objects that DID land and
        the guard below removes exactly those from state with a functional updater. Two reasons, and
        the first is a data-loss one: nothing disables the record buttons while a save is in flight
        (`disabled={saving}` is on the submit button alone, and `ClipRecorder`'s `onStart` stays
        live), so a clip recorded during a slow upload appends itself through `startRecording`'s own
        functional update — and writing back a list captured before the awaits would delete it. The
        second is the mirror of it: `uploadMediaBatch` claims the staged objects for the whole batch
        synchronously (`takeStagedFor`), so keeping a file that already landed would re-upload and
        re-link it.
      */
      let attempted = 0;
      // Clips filed under a question or section the editor on this page has since removed:
      // `clipBatch` cannot caption them, so they cannot be uploaded at all. Counted so the banner
      // can name them — the prune below leaves them sitting on the form, and a banner that claims
      // to account for everything still on the form must not pass over them in silence.
      let orphanedClips = 0;
      // Filenames alone cannot tell "you are offline" from "the server refused this file type", and
      // that sentence only exists inside the error `uploadMediaBatch` raises (MediaBatchError) or in
      // `failed[].error`. Carried to the banner rather than swallowed by a bare catch.
      let firstCause: string | null = null;
      const failedNames: string[] = [];
      const landedInterviewAudio = new Set<File>();
      const landedQuestionAudio = new Map<string, Set<File>>();
      if (mediaFiles.length) {
        attempted += mediaFiles.length;
        // ONLY the upload sits inside the try. A throw out of the tray bookkeeping underneath it is
        // not an upload failure, and treating it as one would put landed files back for a second,
        // duplicating upload.
        let result: BatchResult | undefined;
        try {
          result = await uploadMediaBatch({
            files: mediaFiles,
            linkedRecordType: "questionnaire",
            linkedRecordId: saved.id,
            caption: `Interview audio for ${saved.title}`,
            location,
            recordedAt,
            recordedTimezone,
            transcribeAudio: true,
            onProgress: setInterviewProgress
          });
        } catch (err) {
          // `uploadMediaBatch` only throws when NOTHING in the batch landed, so nothing is marked as
          // having landed here. Caught rather than left to the handler's catch because the
          // per-question clips below are separate recordings on a separate request each: one refused
          // batch is no reason to stop trying to save the other thirty, and the old code stopped.
          firstCause ??= batchCause(err);
        }
        setInterviewProgress(null);
        if (result) {
          // Uploaded clips surface twice: as chips under this section and in the page-level tray.
          addCompleted(
            INTERVIEW_SECTION,
            INTERVIEW_SECTION_LABEL,
            result.outcomes.flatMap((outcome) => (outcome.media ? [outcome.media] : []))
          );
          for (const outcome of result.outcomes) {
            if (outcome.media) landedInterviewAudio.add(outcome.file);
            else {
              failedNames.push(outcome.file.name);
              firstCause ??= outcome.failure?.error ?? null;
            }
          }
        } else {
          failedNames.push(...mediaFiles.map((file) => file.name));
        }
      }
      for (const [key, files] of Object.entries(questionAudioFiles)) {
        const batch = clipBatch(key);
        if (!batch) {
          orphanedClips += files.length;
          continue;
        }
        if (files.length === 0) continue;
        attempted += files.length;
        let result: BatchResult | undefined;
        try {
          result = await uploadMediaBatch({
            files,
            linkedRecordType: "questionnaire",
            linkedRecordId: saved.id,
            caption: batch.caption,
            location,
            recordedAt,
            recordedTimezone,
            transcribeAudio: true,
            extraMetadata: batch.extraMetadata,
            onProgress: (progress) => setQuestionProgress((current) => ({ ...current, [key]: progress }))
          });
        } catch (err) {
          // This question's whole batch was refused. Nothing of it landed, so carry on down the
          // sections: the questions after it have their own recordings and have not been offered yet.
          firstCause ??= batchCause(err);
        }
        if (result) {
          addCompleted(
            clipTraySectionId(key),
            batch.trayLabel,
            result.outcomes.flatMap((outcome) => (outcome.media ? [outcome.media] : []))
          );
          const landed = new Set<File>();
          for (const outcome of result.outcomes) {
            if (outcome.media) landed.add(outcome.file);
            else {
              failedNames.push(outcome.file.name);
              firstCause ??= outcome.failure?.error ?? null;
            }
          }
          if (landed.size) landedQuestionAudio.set(key, landed);
        } else {
          failedNames.push(...files.map((file) => file.name));
        }
      }
      // Cleared only once every question has been pushed, so the tray's page-level total counts the
      // whole run rather than shrinking back to whichever question is uploading right now.
      setQuestionProgress({});
      // Orphaned clips alone do not hold the form back: they can never be uploaded while their
      // question is gone, so blocking on them would make the form unclearable. They are reported
      // only when something else already keeps the researcher here.
      if (failedNames.length) {
        // THE MESSAGE AND THE KEPT FILES COME FIRST, THE REFRESH LAST AND UNAWAITED, AND THE MESSAGE
        // IS NOT `error`. Setting `error` here and then refreshing lost the warning outright: on the
        // `page !== 1` branch `setPage(1)` re-runs the list effect, and `loadInterviews` ends by
        // clearing `error` on success and writing its own sentence on failure — so one round trip later the
        // researcher had a form silently repopulated with the only copy of the audio and nothing on
        // screen saying so. `uploadError` is a separate banner the list loader never touches.
        setMediaFiles((current) => current.filter((file) => !landedInterviewAudio.has(file)));
        setQuestionAudioFiles((current) => {
          const next: Record<string, File[]> = {};
          for (const [key, files] of Object.entries(current)) {
            const landed = landedQuestionAudio.get(key);
            const remaining = landed ? files.filter((file) => !landed.has(file)) : files;
            if (remaining.length) next[key] = remaining;
          }
          return next;
        });
        // PRESSING SAVE AGAIN IS A REAL RETRY, AND SAYING OTHERWISE WOULD BE THE WORSE MISTAKE. The
        // first draft of this banner said only that the clips were the only copy and that leaving
        // would lose them — framing a recoverable state as hopeless. `create_interview`
        // (backend/app/api/routes/questionnaire.py) keys on `artisan_set_key(payload.artisanIds)`
        // and folds a second submission for the same artisan set into the interview that already
        // exists, and the amber panel higher up this page tells the researcher exactly that. The
        // form still holds the same artisans, so a second press re-sends only what is left on it and
        // it lands on the same entry. (Same-text answers are skipped server-side by
        // `upsert_responses`.)
        setUploadError(
          `${failedNames.length} of ${attempted} audio file(s) failed to upload: ${failedNames.join(", ")}.` +
            (firstCause ? ` Reason given: ${firstCause}` : "") +
            " The interview was saved and those clips are still on this form — they are the only copy." +
            " Press Save again to send just them: it adds them to the same interview rather than" +
            " creating a second one. Leaving this page is what would lose them." +
            (orphanedClips
              ? ` A further ${orphanedClips} clip(s) belong to a question that is no longer in this form; ` +
                "they cannot be sent until it is put back."
              : "")
        );
        setSaving(false);
        // The interview IS in the repository, so refresh the table — a researcher who cannot see the
        // sitting in the list below has every reason to press Save again and record it twice. (The
        // media page returns on a partial failure the same way, and reloads its table first for the
        // same reason.) Not awaited: this guard fires on a dead connection, and awaiting a fetch
        // that is going to time out would hold the button on "Saving..." and the warning off screen
        // for the whole timeout.
        if (page !== 1) setPage(1);
        else void loadInterviews();
        return;
      }
      // Bank the sitting: the interview does not become part of the carried context (nothing else
      // links to one) but the artisan it was taken with is exactly where the researcher still is.
      // ONE artisan, and it is element 0 of the selection — see `primaryInterviewArtisanId`. Read
      // out of `knownArtisans` rather than the workshop's roster so a deep-linked or carried artisan
      // the roster does not hold is still banked with their name, craft and place.
      const interviewed = knownArtisans.find((artisan) => artisan.id === primaryArtisanId);
      if (interviewed) {
        carry.remember({
          artisanId: interviewed.id,
          artisanName: interviewed.name,
          place: interviewed.place,
          craftId: interviewed.craftId,
          craftName: interviewed.craft?.name ?? null,
          workshopId: workshop.workshopId,
          workshopName: workshop.workshops.find((w) => w.id === workshop.workshopId)?.title ?? null
        });
      }
      formElement.reset();
      // Same reason as the queued branch above: `reset()` does not clear React state, so without
      // these three the next interview opens carrying the last one's title, place and language.
      setTitle("");
      setPlace("");
      setLanguage("");
      setAnswers({});
      clearQuickTranscripts();
      setMediaFiles([]);
      setQuestionAudioFiles({});
      // Keep the head, drop the rest — same rule and same reason as the queued branch above.
      setSelectedArtisanIds((current) => current.slice(0, 1));
      /*
        LEAVING EDIT MODE IS PART OF THE RESET, on BOTH save branches.
      
        A banked PATCH the server has not seen yet must not leave the form still claiming to be editing
        that record: the next Save would be a second PATCH of a sitting whose first is still in the
        outbox, and the "Editing interview" banner would assert a state the researcher has finished with.
        The URL is stripped for the same reason `resetToCreate` strips it — a surviving `?edit=`
        re-applies on the next read and puts the finished record back under the Back button.
      */
      leaveEditMode();
      // Show the freshly saved (most recent) interview at the top of page one.
      if (page !== 1) setPage(1);
      else await loadInterviews();
    } catch (err) {
      /*
        ── A COLLIDING ARTISAN SET IS NOW ANSWERABLE, AND ONLY ON AN EDIT ───────────────────────

        `offerMergeIntoHolder` carries the whole contract and the ruling behind it. A holder the
        server could NAME is an offer worth making; a null id (the holder was deleted between the
        unique violation and the lookup), a different refusal code, or a create all fall straight
        through to the banner with the server's own sentence, which is what this screen did before
        any of these keys existed.
      */
      const holder = duplicateSetHolder(err);
      if (editingId && holder) {
        if (await offerMergeIntoHolder(editingId, holder)) return;
        /*
          DECLINED, and the generic sentence is not good enough here. `_DUPLICATE_SET_DETAIL` says
          "an interview already exists for this exact set of artisans" and cannot say WHICH — naming
          it is the whole reason the holder keys were added, and a researcher who has just said "no"
          to the fold needs the name in order to do the other thing.
        */
        setError(
          `Nothing was saved. “${holder.title}” already covers this exact set of artisans. Put the original artisans back, or open “${holder.title}” and add to it instead.`
        );
        return;
      }
      setError(err instanceof Error ? err.message : "Unable to save interview");
    } finally {
      setSaving(false);
      setInterviewProgress(null);
      setQuestionProgress({});
    }
  }

  async function remove(id: string) {
    const ok = await confirm(
      deleteConfirm(
        "Delete this interview?",
        "This permanently deletes the interview and every answer in it. This action cannot be undone.",
        "The recordings made during the interview, and their transcripts, are deleted with it."
      )
    );
    if (!ok) return;
    try {
      await apiFetch(`/questionnaire/interviews/${id}`, { method: "DELETE" });
      await loadInterviews();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete interview");
    }
  }

  /**
   * WHAT THE ONE ARTISAN CONTROL OFFERS: this workshop's roster, plus any ticked row the roster does
   * not hold so a selection is never drawn as a blank chip. Built in
   * `components/questionnaires/interviewArtisans.ts` so the handset has one rule to copy rather than
   * a `.map()` to re-derive.
   */
  const artisanScopeKey = interviewScopeKey(artisanWorkshopScope);
  const artisanNarrowed = interviewScopeIsNarrowed(artisanWorkshopScope);
  const artisanOptions = useMemo(
    () =>
      artisanPickerOptions({
        scoped: artisanScope.scoped,
        known: knownArtisans,
        selectedIds: selectedArtisanIds
      }),
    [artisanScope.scoped, knownArtisans, selectedArtisanIds]
  );
  /**
   * The ticked artisans this workshop's roster does not account for, as a SENTENCE and never as a
   * deletion. A workshop change unticks nobody: the roster is the offer, and it cannot prove a tick
   * wrong — filing this very interview is one of the three ways an artisan comes to belong to a
   * workshop (`artisan_workshop_clause`). See `artisansNotAtWorkshop` for the three cases in which
   * this deliberately stays silent.
   */
  const outOfWorkshopIds = artisansNotAtWorkshop({
    selectedIds: selectedArtisanIds,
    offeredIds: artisanScope.scoped.map((artisan) => artisan.id),
    loadedForScope: artisanScope.loadedForScope,
    scopeKey: artisanScopeKey,
    narrowed: artisanNarrowed,
    cut: artisanScope.cut
  });
  const outOfWorkshopSentence = outOfWorkshopNotice(
    outOfWorkshopIds.map((id) => knownArtisans.find((artisan) => artisan.id === id)?.name ?? "")
  );

  return (
    <>
      <PageHeader
        title="Questionnaire"
        description="Interview artisans section by section, answer only the questions asked, and link the interview to one or more artisans."
        icon={<ClipboardList className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {/* Its own banner, not `error`: the list loader owns that one and clears it on every refresh.
          `role="alert"` because this one appears after a press that looked like it succeeded. */}
      {uploadError ? (
        <div role="alert" className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {uploadError}
        </div>
      ) : null}
      {/*
        WHAT THE FOLD DID. Amber, because the theme defines exactly three amber tokens (100/500/800)
        and a refusal-adjacent notice is what they are for — and the VERDICT IS THE FIRST WORD of the
        panel rather than the colour, so a reader who never gets the wash still reads "Moved." or
        "Nothing was moved." before anything else. `role="alert"` because this appears after a press
        the researcher is waiting on.
      */}
      {mergeOutcome ? (
        <section role="alert" className="mb-4 rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          <p>
            <span className="font-semibold">{mergeOutcome.moved ? "Moved." : "Nothing was moved."}</span>{" "}
            {mergeOutcome.message}
          </p>
          {mergeOutcome.questions.length ? (
            <div className="mt-2 grid gap-2">
              <div className="text-xs font-semibold uppercase tracking-wide text-amber-800">
                Answered differently in both ({mergeOutcome.questions.length})
              </div>
              <ul className="grid gap-1">
                {mergeOutcome.questions.map((question) => (
                  <li key={question} className="rounded-md border border-amber-500 bg-card/70 p-2 text-xs text-ink">
                    {question}
                  </li>
                ))}
              </ul>
              <p className="text-xs text-amber-800">
                Open each one, keep the wording that is right, then try the move again.
              </p>
            </div>
          ) : null}
        </section>
      ) : null}

      {/*
        ══ THE RAIL ══════════════════════════════════════════════════════════════════════════════

        Two columns from `lg`, one below — the owner's geometry, the same as the dashboard's. Only
        the two ADMIN panels take half of it. The capture form and the recorded-interviews table are
        `span="full"` because their content genuinely cannot be halved rather than because it would
        prefer not to be: the form is ONE `<form>` element by contract (two parsers slice from its
        opening tag to the first `</form>`), and the table carries `min-w-[980px]`, so either one in
        half a page is a horizontal scrollbar inside a card inside a rail.

        `items-start` stops a shut card stretching to the height of an open one beside it.

        ⚠ THE COMPLETION MATRIX MOVED DOWN THE PAGE, and that is a deliberate trade rather than an
        accident. It was "top of the page, collapsed by default" — but a half-width card cannot sit
        above a full-width one without leaving a hole beside it, and the two things a reader comes to
        this page to DO are record an interview and find one they recorded. It is still collapsed by
        default, still one click, and now sits beside the builder, which is the other panel an
        administrator rather than an interviewer opens.
      */}
      <div className="grid items-start gap-5 lg:grid-cols-2">

      {/*
        ══ WHICH FIELDS ON THIS FORM GOT A MICROPHONE, AND WHICH DID NOT (req 13) ═══════════════
        The owner's instruction was "identify all fields where typing can reasonably be replaced or
        supplemented by microphone dictation … enable the existing mic dictation functionality
        wherever appropriate", and the exclusions are the half a later reader will question, so they
        are written down here rather than left to be re-derived.

        GOT ONE — every free-text box on the screen:
          · Interview title  — named in the requirement; typed standing up beside the artisan.
          · Place            — free text, a place name spoken far faster than it is thumbed in.
          · Every answer box — the longest prose on the page; see the button beside each one.

        DID NOT, AND WHY:
          · Workshop, Type of workshop, Design & prototype workshop, Status, Artisans interviewed,
            Language — CLOSED VOCABULARIES and record pickers. A recogniser returns a sentence, not a
            row of a list; the value it produced would then have to be matched back against options,
            and a near-miss would select the WRONG record silently. On this page in particular the
            set chosen in "Artisans interviewed" decides `artisanSetKey` — which interview a
            submission folds into — so a mis-heard name does not produce a typo, it merges an
            interview into another artisan's set. Language became a dropdown in this same change
            precisely so it would STOP being free text.
            (This list named "Primary artisan" and "Additional artisans" until those two controls
            were collapsed into the one multi-select above. The exclusion is unchanged and the
            reason is the same; only the number of boxes it covers moved.)
          · Recorded-at date/time and the location fields (`LocationFields`) — dates, coordinates and
            a device GPS fix. "Twenty-third of the eighth" is not a date the parser takes, digits
            come back spelled as words, and a captured fix is a measurement rather than an answer.
          · Section and question CODES in the admin editor below — `A`, `RESP`, short identifiers
            with no prose in them, where the microphone would reliably produce a value the field
            then refuses. This matches `/artisans/new`, where the address and the notes get a
            microphone and the name, phone and e-mail do not.
          · Interview notes (`MultiNoteField`) — WANTED and NOT DONE HERE. It is prose and it should
            have one, but the control lives in `components/FormControls.tsx`, which this change does
            not own; its several textareas would each need a button, exactly as `ProcessForm`'s note
            rows already do. Reported as a handoff rather than worked around by replacing the
            control, because collapsing several notes into one box would lose a feature Android's
            `MultiNoteInput` still splits back out on the handset.
      */}
      {/*
        `key` AND `ref`, BOTH ADDED WITH THE EDIT PATH.

        `key={editingInterview?.id ?? "new"}` is the remount every record form in this repository
        performs, and it is what re-reads the UNCONTROLLED half of this form — status, the notes, the
        location and capture rows. Without it the previous occupant's values stay on screen under the
        new record's title: `useEditDeepLink`'s own header names that as the defect the remount
        exists for. `"new"` rather than `undefined` so leaving edit mode is also a remount and the
        form comes back genuinely blank.

        `ref` is where the deep link scrolls. This page draws the funnel, the carry banner and — for a
        professor — a completion matrix above the form, so the top of the document is not the top of
        the thing the reader asked to edit.
      */}
      <MegaCard
        title={editingInterview ? "Correct this interview" : "Take interview"}
        note="Interview artisans section by section, answer only the questions asked, and link the interview to one or more artisans."
        icon={ClipboardList}
        tone="purple"
        count={orderedGroups.length}
        countLabel="section"
        span="full"
        expanded={megaCards.isOpen("capture")}
        onToggle={() => megaCards.toggle("capture")}
      >
      {/*
        ══ WHICH INSTRUMENT AM I ANSWERING ═══════════════════════════════════════════════════════

        Owner, 2026-09-20: *"there is no way to pick between multiple questionnaires like there is in
        field repo app."* It was exactly true: this screen records against the ONE global artisan
        instrument and had no way of naming that fact, let alone of reaching a designer's own forms.

        IT SITS ABOVE THE `<form>` AND NOT INSIDE IT, and that placement is load-bearing three times
        over. `shared/questionnaire-form-contract.json` holds this form's eight fields to an ORDER,
        and `backend/tests/test_questionnaire_form_contract.py` slices the region from
        `<form … onSubmit={submit}>` to the first `</form>` and asserts an EQUALITY over the
        `label="…"` literals inside it — so a ninth control in there is a red build whatever it is,
        the Android half would owe a matching box, and the walkthrough registers would owe a row. It
        is also not a field of the record: nothing about the chosen instrument is submitted.

        CHOOSING ONE NAVIGATES rather than re-pointing this form. The reason is in the schema and is
        argued in full in `InstrumentPicker`'s header — an interview can only answer GLOBAL questions
        (`QuestionnaireResponse.questionId` is `Restrict`-FK'd to `QuestionnaireQuestion`), and
        `artisanSetKey` is `@unique` repository-wide, so one artisan set answering two instruments
        would fold both onto one row.

        THE PICKER IS ONLY DRAWN FOR ACCOUNTS THAT MAY LIST QUESTIONNAIRES. Every route under
        `/api/questionnaires` begins with `_require_designer`, while THIS page is open to every
        signed-in account — so an ungated picker would 403 for exactly the volunteers and field
        contributors this screen exists to serve.

        NO LEAVE GUARD TO GO THROUGH, verified: this form has no dirty tracking at all (the note
        above the Language field records that grep and its result). Every other navigation off this
        page already leaves without asking; this one is not a new hole. If a guard is ever added
        here, this `router.push` is one of the calls that must move behind it.
      */}
      <div className="mb-4 max-w-xl">
        <InstrumentPicker
          value={SHARED_INSTRUMENT}
          allowed={canRunDesignWorkshops(user)}
          hint="Your own .xlsx-derived forms open on their own screen, which is where their answers are recorded."
          onChange={(next) => {
            if (next === SHARED_INSTRUMENT) return;
            router.push(`/questionnaires/${next}/answer`);
          }}
        />
      </div>

      {/*
        ⚠ THE `panel` CLASS STAYS ON THIS FORM AND IS THEN NEUTRALISED, WHICH LOOKS LIKE A MISTAKE
        AND IS NOT. `e2e/questionnaire-capture.spec.ts` locates the instrument's sections as
        `form.panel details` — the class is a SELECTOR that a signed-in browser spec depends on, not
        a decoration. Dropping it would break that spec; leaving it as-is would draw a bordered card
        inside a bordered card. So the recipe stays and the utilities that follow it undo its border,
        ground, shadow, padding and margin — utilities beat a component-layer recipe, which is what
        makes this work at all.
      */}
      <form
        key={editingInterview?.id ?? "new"}
        ref={captureFormRef}
        onSubmit={submit}
        onKeyDown={handleFormEnter}
        className="panel grid gap-4 border-0 bg-transparent p-0 shadow-none"
      >
        {/*
          EDIT MODE SAYS SO, AND SAYS WHAT IT WILL NOT TOUCH.

          Two facts a researcher needs before typing, neither of which is visible from the form
          itself: recordings already attached to the sitting are kept (this form is not a
          re-capture, and the upload tray opens empty), and when the sitting was recorded is not
          re-stamped by a correction typed in an office a week later.

          A third is deliberately NOT promised here: whether this account may change a field somebody
          else recorded. That is `guard_record_edit`'s answer, made per record inside the PATCH, and a
          client-side claim about it would be a guess printed as a fact.
        */}
        {editingInterview ? (
          <div className="rounded-md border border-purple-300 bg-purple-50 px-3 py-2 text-sm text-purple-800">
            <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
              <span>
                Editing <span className="font-medium">{editingInterview.title?.trim() || "this interview"}</span>.
                Saving corrects this sitting rather than filing another.
              </span>
              <button type="button" className="field-button-secondary h-8 min-h-0 px-3 text-xs" onClick={resetToCreate}>
                Cancel edit
              </button>
            </div>
            <p className="mt-1 text-xs leading-5">
              Recordings already attached to it are kept, and when it was recorded is not changed by a
              correction made now.
            </p>
          </div>
        ) : null}
        {/* The carry-forward offer is a CREATE affordance: it prefills a NEW record from the last
            one. Offering it over an open edit would invite overwriting a recorded sitting with
            another record's values. */}
        {editingInterview ? null : <CarryContextBanner offer={carry.applied} onChange={clearCarriedContext} />}
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
          {/*
            ── THE "this browser cannot dictate" SENTENCE, ONCE FOR THE WHOLE FORM ──────────────
            This screen now carries a microphone on Interview title, on Place and on EVERY answer
            box — a full questionnaire is dozens of them. `OnDeviceDictationButton` prints its own
            explanatory sentence where the browser has no `SpeechRecognition` (Firefox has none),
            which is right for a form with one or two microphones and is forty copies of one grey
            paragraph here; the reader learns to skip it and then skips the one place it mattered.
            So every button on this page passes `explainWhenUnavailable={false}` and this component
            discharges the obligation the prop names — it self-detects, so nothing on this page
            holds a second opinion about what the browser can do. The button is still never drawn
            dead: absent a recogniser there is no control at all, not a disabled one.
          */}
          <DictationUnavailableNotice className="md:col-span-2 lg:col-span-4" />
          {/*
            ── DICTATION ON THE TWO FREE-TEXT HEADER FIELDS (req 13) ────────────────────────────
            The owner's requirement: "reduce unnecessary typing and user friction", with Interview
            title named explicitly. Both of these are typed one-handed, standing up, beside the
            person about to be interviewed — the exact circumstance the microphone exists for. A
            single-line field gets a single-line control (`DictatedTextInput`), not a textarea
            squeezed into a one-line slot: this row is `md:grid-cols-2 lg:grid-cols-4` and a
            `min-h-24` box in it would be three times the height of Place, Workshop and Status
            beside it.
          */}
          {/*
            `titleCased` ON BOTH, AND IT CLOSES AN ANDROID-PARITY GAP THAT PREDATES THIS CHANGE.
            The server title-cases these two columns on write — `title` and `place` are both in
            `TITLE_CASE_FIELDS` (`backend/app/services/records.py`) and `create_interview` calls
            `clean_data(...)` with the default `title_case=True` — so "bagru village" is stored as
            "Bagru Village". Android has always said so up front (`RequiredInput("Interview title",
            …, titleCased = true)` and `TextInput("Place", …, titleCased = true)`,
            MainActivity.kt); the web drew plain boxes, so the value changed silently AFTER saving
            and the form disagreed with the record. Verified 2026-08-28:
              grep -n "TITLE_CASE_FIELDS" -A 16 backend/app/services/records.py
              grep -n "clean_data" backend/app/api/routes/questionnaire.py
            The hint is quiet by construction — `TitleCasedInput` renders nothing when the
            normalised value matches what was typed — and it matters MORE now than it did before,
            because a recogniser hands back its own casing and the researcher never typed it.
          */}
          <DictatedTextInput
            name="title"
            label="Interview title"
            required
            titleCased
            value={title}
            onChange={setTitle}
            explainWhenUnavailable={false}
          />
          {/* No date field: the server derives interviewDate from recordedAt, which is when the
              interview was actually captured. Asking a researcher to confirm today's date was a
              field to tab past that could only ever be wrong. */}
          <DictatedTextInput
            name="place"
            label="Place"
            titleCased
            value={place}
            onChange={setPlace}
            explainWhenUnavailable={false}
          />
          {/*
            ── LANGUAGE IS A DROPDOWN, NOT A TEXT BOX (req 14) ──────────────────────────────────
            Android has shipped the picker since this form was written and the web had a free
            `TextInput placeholder="Bangla, Hindi, English..."`, so the same fact arrived as
            "Bangla", "Bengali" and "bengali" in a column /data, the consolidated questionnaire and
            every export group by. The list and the preserve-what-is-stored rule live in
            `lib/interviewLanguages.ts` so neither client transcribes them twice.

            `FieldBlock`, NOT `Field`: `Field` is a `<label>`, and a `<label>` cannot name a
            `<button>` — which is what a themed dropdown is — so the trigger would announce its
            VALUE and never its question (SKILL.md §12.3). `FieldBlock` is a `<div role="group">`
            that publishes its label id for `SearchableSelect` to compose a name from.

            NO `searchable` PROP, DELIBERATELY — SKILL.md §11.5. The rule is: options from a fetched
            list pass it, options from a CONSTANT leave it alone and let `SEARCH_THRESHOLD` (8)
            decide. This vocabulary is written in the repository and cannot grow behind our backs,
            and at twenty-four rows the threshold answers "yes, long enough to hunt through" — which
            is the right answer and the same one Android reaches, since
            `android/.../ui/SearchableSelect.kt` computes `options.size >= SEARCH_THRESHOLD` off the
            identical threshold of 8. Passing `searchable` explicitly would be a second opinion
            about a question the primitive already answers; passing `searchable={false}` would need
            a `capHint` and would take the filter box away for no reason. Well under `RENDER_CAP`
            (80), so there is no truncation to state on screen either.

            NO `markDirty` CALL — and this page is the reason that reads as an omission. SKILL.md
            §12.1 requires themed dropdowns to raise the dirty flag by hand because they fire no
            native input event, but this form HAS no dirty tracking to raise: it is
            `<form onSubmit onKeyDown>` with no `onInput`, no `setDirty` and no `useLeaveGuard`.
            Verified 2026-08-28 — this grep, run against this file, matches nothing but the three
            occurrences inside THIS comment:
              grep -n "setDirty\|markDirty\|useLeaveGuard" "app/(protected)/questionnaire/page.tsx"
            If a leave guard is ever added here, this control and the `Select` /
            `MultiSelectDropdown` above and below it all need the call, and so do the two
            `DictatedTextInput`s (a committed phrase is a state write, not an input event).
          */}
          <FieldBlock label="Language">
            <Select
              name="language"
              value={language}
              onChange={(event) => setLanguage(event.target.value)}
              placeholder={INTERVIEW_LANGUAGE_PLACEHOLDER}
            >
              {languageOptions.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </Select>
          </FieldBlock>
          {/*
            THE WORKSHOP QUESTION, TWO BOXES, ONE ANSWER — "Type of workshop" and then the workshop
            of that type. The type decides which of the record's two columns the answer lands in
            (R3): Design & Prototype writes `designWorkshopId`, every other type writes `workshopId`.

            FOURTH ON THIS FORM AND FIRST ON THE OTHER FOUR, which is a declared order and not an
            accident. This used to read "it leads every other dropdown", which was never true here —
            Language is a `<Select>` and sits above it — and the sequence is now written down once,
            in `shared/questionnaire-form-contract.json`, with this page as its authority.
            `backend/tests/test_questionnaire_form_contract.py` holds this form AND the handset's
            `QuestionnaireForm` to it as an equality, so moving a box here without moving it there is
            a red build rather than a drift.

            NO `onDirty`, and that is not an omission — see the note on the Language box above: this
            form has no dirty tracking to raise. The grep there covers this control too.
          */}
          <WorkshopPicker state={workshop} saving={saving} />
          <Field label="Status">
            {canPickStatus ? (
              <Select name="status" defaultValue="APPROVED">
                {["DRAFT", "PENDING", "APPROVED", "REJECTED"].map((status) => (
                  <option key={status}>{status}</option>
                ))}
              </Select>
            ) : (
              <span
                className="inline-flex min-h-10 items-center gap-2 self-start rounded-md border border-line-200 bg-field-100 px-3 py-2 text-sm font-medium text-ink-muted"
                title="New interviews are submitted for review as Pending."
              >
                <Lock className="h-4 w-4" aria-hidden />
                Pending
              </span>
            )}
          </Field>
          {/*
            ── ONE CONTROL FOR THE WHOLE SET, AND THE TWO IT REPLACED ───────────────────────────
            This was a single-select "Primary artisan" and a multi-select "Additional artisans",
            for a join that has no rank column: `QuestionnaireInterviewArtisan` is
            `@@id([interviewId, artisanId])` plus `createdAt`, and `artisan_set_key` sorts the ids
            on the server before they reach the `@unique` index, so nothing downstream could have
            told the two apart even if the form had wanted it to. The page already de-duplicated
            both controls into one array before every request it sent. What the split actually did
            was let a researcher tick somebody under "Additional" with "Primary" left blank and get
            the RESP block prefilled from nobody.

            `searchable`, forced rather than left to an option count: this is the picker in the app
            where it matters most — the SET chosen here is what decides `artisanSetKey`, WHICH
            INTERVIEW a submission folds into — and the labels are `name - craft - place` triples
            that differ by one word. One workshop on this deployment, forty on the next, and a
            control that changed shape between two workshops for reasons the researcher cannot see
            would be a second thing to learn.

            THE EMPTY MESSAGE IS FOUR SENTENCES OFF THREE FACTS and must survive any widget swap:
            an empty roster means the request is in flight, the request failed, nobody is recorded
            at this workshop, or nobody is recorded at all — and only two of those are facts about
            the repository. `artisanPickerEmptyLabel` is where the choice is made, beside the rules
            the handset has to copy.

            `FieldBlock`, NOT `Field` — the same ruling the Language box above makes and for the
            same reason (SKILL.md §12.3): `Field` is a `<label>`, a `<label>` cannot name a
            `<button>`, and a themed dropdown is a button, so the trigger would announce its VALUE
            and never its question. The control this replaced used `Field`, which was already wrong
            and is not worth carrying across a rewrite of the same box. `FieldBlock` also owns the
            `hint` slot, which is where the two sentences under the control belong: `describedBy`
            joins them to the trigger, so a reader who tabs onto it hears that the list is cut and
            that somebody ticked is not on this workshop's roster — the two facts most likely to
            change what they do next, and the two a visual-only paragraph never reaches.
          */}
          <FieldBlock
            label="Artisans interviewed"
            hint={
              <>
                {/* What the roster could not reach, in the repository's standing wording. `"none"`
                    because this control filters the array it was handed and the term never goes to
                    the server — typing cannot reach past the cut — so the sentence must not tell
                    somebody to do something impossible. */}
                {artisanScope.cut ? (
                  <p id={ARTISAN_CUT_HINT} className="text-xs text-ink-700">
                    {cappedListNotice(artisanScope.cut, "none")}
                  </p>
                ) : null}
                {outOfWorkshopSentence ? (
                  <p id={ARTISAN_SCOPE_HINT} className="text-xs text-ink-700">
                    {outOfWorkshopSentence}
                  </p>
                ) : null}
              </>
            }
          >
            <MultiSelectDropdown
              describedBy={
                [artisanScope.cut ? ARTISAN_CUT_HINT : null, outOfWorkshopSentence ? ARTISAN_SCOPE_HINT : null]
                  .filter(Boolean)
                  .join(" ") || undefined
              }
              values={selectedArtisanIds}
              onChange={(next) => {
                const added = next.find((id) => !selectedArtisanIds.includes(id));
                setSelectedArtisanIds(next);
                // An explicit pick replaces the remembered context and retires the banner: from here
                // on the selection is the researcher's own choice, not a suggestion. Only on the way
                // IN — unticking says who the interview is not about, which is no statement about
                // where the researcher is sitting.
                const artisan = added ? knownArtisans.find((candidate) => candidate.id === added) : undefined;
                if (artisan) {
                  carry.remember(
                    {
                      artisanId: artisan.id,
                      artisanName: artisan.name,
                      place: artisan.place,
                      craftId: artisan.craftId,
                      craftName: artisan.craft?.name ?? null
                    },
                    { explicit: true }
                  );
                }
              }}
              options={artisanOptions}
              searchable
              placeholder="Select the artisans this interview is with"
              emptyLabel={artisanPickerEmptyLabel({
                loadedForScope: artisanScope.loadedForScope,
                scopeKey: artisanScopeKey,
                failed: artisanScope.failed,
                narrowed: artisanNarrowed
              })}
            />
          </FieldBlock>
        </div>
        {/* The theme defines exactly three amber tokens (100/500/800); 50/200/300/700 are not
            Tailwind classes here and silently render as nothing. */}
        {/*
          NOT SHOWN AGAINST THE RECORD BEING EDITED. The banner keys on the artisan SET, which an open
          edit normally still has — so it announced the record to itself and promised that saving
          would "add to" it, which is the opposite of what an edit does. A DIFFERENT entry is still
          worth warning about, and harder: editing INTO an occupied set cannot fold the way a create
          does, so the save is refused rather than merged. The wording below forks on exactly that.
        */}
        {existingEntry && existingEntry.id !== editingInterview?.id ? (
          <section className="rounded-lg border border-amber-500 bg-amber-100 p-4">
            <h3 className="font-display font-bold text-lg text-amber-800">A shared entry already exists for this set of artisans</h3>
            {/*
              ⚠ THE FORK WAS CLAIMED IN THE COMMENT ABOVE AND MISSING FROM HERE, AND THAT MADE THIS
              PARAGRAPH A LIE ON SCREEN RATHER THAN A WORDING NIT.

              The banner is suppressed against the record being edited, so it only ever draws over a
              DIFFERENT entry — and in edit mode "saving below adds your answers and media to X — it
              will not create a duplicate" is the OPPOSITE of what happens. A create folds into the
              holder (`create_interview` keys on `artisan_set_key`); an edit into an occupied set
              CANNOT fold by itself and is refused on the unique index. The researcher was promised a
              merge and got a 409.

              The sister repository forks correctly and this is that fork, ported. It is worth saying
              that the refusal is no longer the end of the road either: `offerMergeIntoHolder` turns
              it into an offer to move this sitting into the holder — but only after the save has
              actually been refused and only on a confirmation, so what this paragraph promises before
              the press is still "it will be refused", which is the truth.
            */}
            <p className="mt-1 text-sm text-amber-800">
              {editingInterview ? (
                <>
                  Another interview — <span className="font-medium">{existingEntry.title}</span> — already covers this
                  exact set of artisans. There is one entry per set, so saving this edit with these artisans ticked will
                  be refused. Put the original artisans back, cancel and edit that interview instead, or accept the
                  offer to move this interview into it when the refusal comes back.
                </>
              ) : (
                <>
                  There is one questionnaire entry per set of artisans. Saving below adds your answers and media to{" "}
                  <span className="font-medium">{existingEntry.title}</span> — it will not create a duplicate. Questions
                  already answered by someone else are shown here and can only be changed by that contributor or an admin.
                </>
              )}
            </p>
            {/*
              WHAT IS ON IT, COUNTED FROM BOTH COLUMNS. `sharedEntryContents` carries the whole
              argument: this sentence used to be derived from `responses` alone and therefore called a
              fully recorded audio sitting empty.
            */}
            <p className="mt-2 text-xs text-amber-800">
              {sharedEntryContents(existingEntry.responses?.length ?? 0, existingEntry.media?.length ?? 0)}
              {existingEntry.responses?.length || existingEntry.media?.length ? null : " You can be the first to fill it in."}
            </p>
            {existingEntry.responses && existingEntry.responses.length > 0 ? (
              <div className="mt-3 grid gap-2">
                <div className="text-xs font-semibold uppercase tracking-wide text-amber-800">
                  Already recorded ({existingEntry.responses.length})
                </div>
                {existingEntry.responses.map((response) => (
                  <div key={response.id} className="rounded-md border border-amber-500 bg-card/70 p-2 text-xs">
                    <div className="font-semibold text-ink">
                      {response.question?.sectionCode ? `[${response.question.sectionCode}] ` : ""}
                      {response.question?.prompt ?? "Question"}
                    </div>
                    <div className="mt-1 whitespace-pre-wrap text-ink-muted">{plainFromStoredRichText(response.answerText)}</div>
                    {response.answeredBy?.name ? (
                      <div className="mt-1 text-amber-800">Recorded by {response.answeredBy.name}</div>
                    ) : null}
                  </div>
                ))}
              </div>
            ) : null}
          </section>
        ) : null}
        {selectedArtisan ? (
          <section className="rounded-lg border border-line-200 bg-field-100 p-4">
            <h3 className="font-display font-bold text-lg text-ink">RESP. Respondent Information</h3>
            <div className="mt-2 grid gap-2 text-sm text-ink-muted sm:grid-cols-2 lg:grid-cols-3">
              <div><span className="font-medium text-ink">Name:</span> {selectedArtisan.name}</div>
              <div><span className="font-medium text-ink">Craft:</span> {selectedArtisan.craft?.name ?? "-"}</div>
              <div><span className="font-medium text-ink">Place:</span> {selectedArtisan.place}</div>
              <div><span className="font-medium text-ink">Gender:</span> {selectedArtisan.gender ?? "-"}</div>
              <div><span className="font-medium text-ink">Contact:</span> {selectedArtisan.phone || selectedArtisan.email || "-"}</div>
            </div>
          </section>
        ) : null}
        {/* Replaces the old "Record audio answers / Type answers manually" pair: that switch governed
            the same text boxes as the toggle below, under a different name than Android uses for it. */}
        <QuestionnaireCaptureControls prefs={capture} onChange={updateCapture} />
        {/* Always offered, as on Android: audio for the interview as a whole, distinct from the
            per-section and per-question takes and never hidden by the answer-box toggle. */}
        <MediaCaptureField
          files={mediaFiles}
          onFilesChange={setMediaFiles}
          title="Interview audio"
          description="Record or upload interview audio. The backend will transcribe it when a transcription provider API key (ElevenLabs, Deepgram, or OpenAI) is configured; otherwise the audio is still saved."
          allowDocuments={false}
          allowedTypes={["AUDIO"]}
        />
        <UploadProgress progress={interviewProgress} sectionId={INTERVIEW_SECTION} label={INTERVIEW_SECTION_LABEL} />
        <LocationFields />
        <div className="grid gap-3">
          {orderedGroups.map(([code, group], index) => {
            const sectionClips = savedClips.bySection.get(group.section.id) ?? [];
            const progress = sectionProgress(group.items, {
              answers,
              liveSectionClips: (questionAudioFiles[sectionClipKey(group.section.id)] ?? []).length,
              liveQuestionClips: questionAudioFiles,
              savedClips: sectionClips
            });
            return (
            <details
              key={code}
              className="rounded-md border border-line-200 bg-field-100 p-3"
              /*
                OPEN WHERE THERE IS SOMETHING INSIDE, on an edit. `sectionOpensOnLoad` carries the
                rule. React only writes `open` when the VALUE changes, so a reader who shuts a
                section keeps it shut — the value here moves exactly once, when the record arrives.
              */
              open={sectionOpensOnLoad(group.section.id, index, sectionsWithContent, !!editingInterview)}
            >
              <summary className="cursor-pointer font-display font-bold text-lg text-ink">
                {code}. {group.title}
                {/*
                  WORDS, NOT A DOT OR A TINT. This is the only thing on a shut row that says whether
                  there is anything inside it, and on the default capture settings — whole-section
                  audio, answer boxes hidden — it is the only thing on the whole screen that does.
                  `font-sans font-normal` because it sits inside a display-face bold `<summary>` and
                  a count set in the heading face reads as a second heading.
                */}
                <span className="mt-0.5 block font-sans text-xs font-normal text-ink-muted">
                  {sectionProgressLabel(progress)}
                </span>
              </summary>
              <div className="mt-3 grid gap-3">
                {/* One take for the whole section. Rendered whenever such a take EXISTS, not only in
                    section mode, so switching to individual mode part-way never hides audio that is
                    still going to be saved. */}
                {capture.recordingMode === "SECTION" || questionAudioFiles[sectionClipKey(group.section.id)]?.length ? (
                  <div className="rounded-md border border-line-200 bg-card p-3">
                    <ClipRecorder
                      hint={
                        capture.recordingMode === "SECTION"
                          ? "Record this entire section in one take."
                          : "A whole-section take from earlier. It still uploads and saves with this interview."
                      }
                      showRecordButton={capture.recordingMode === "SECTION"}
                      recording={recordingKey === sectionClipKey(group.section.id)}
                      recordLabel="Record section"
                      stopLabel="Stop section recording"
                      onStart={() =>
                        startRecording(
                          sectionClipKey(group.section.id),
                          `${safeFileName(group.section.code)}-sec-${safeFileName(group.section.title)}`
                        )
                      }
                      onStop={stopRecording}
                      files={questionAudioFiles[sectionClipKey(group.section.id)] ?? []}
                      previews={questionAudioPreviews[sectionClipKey(group.section.id)] ?? []}
                      countLabel={clipCountLabel(sectionClipKey(group.section.id))}
                      onClear={() => setQuestionAudioFiles((current) => ({ ...current, [sectionClipKey(group.section.id)]: [] }))}
                      onRemove={(index) =>
                        setQuestionAudioFiles((current) => ({
                          ...current,
                          [sectionClipKey(group.section.id)]: (current[sectionClipKey(group.section.id)] ?? []).filter((_, i) => i !== index)
                        }))
                      }
                      onOpenPreview={setActivePreview}
                      stream={questionStream}
                      elapsedMs={questionElapsedMs}
                    />
                    <UploadProgress
                      progress={questionProgress[sectionClipKey(group.section.id)] ?? null}
                      sectionId={clipTraySectionId(sectionClipKey(group.section.id))}
                      label={`Section ${group.section.code} audio`}
                      className="mt-2"
                    />
                    {/*
                      ── A WHOLE-SECTION TAKE'S TRANSCRIPT HAS NO BOX, AND IS SHOWN ANYWAY ────────
                      It covers a dozen questions, so there is no single answer it is the answer to
                      — `applyTranscript` says at length why writing it into one of them, or
                      splitting it across all of them, would be the page inventing an attribution
                      only the researcher can make. So it is rendered here to be read, copied and
                      saved, and the researcher moves what belongs where. No flag: nothing on screen
                      can be edited, so "edited or not" has nothing to be about.
                    */}
                    <QuickTranscript
                      busy={!!transcribing[sectionClipKey(group.section.id)]}
                      problem={quickProblem[sectionClipKey(group.section.id)]}
                      current=""
                      filenameBase={`Section-${group.section.code}-transcript`}
                      onAccept={() => undefined}
                      onDismiss={() => undefined}
                    />
                    {machineText[sectionClipKey(group.section.id)] ? (
                      <MarkdownDocument
                        className="mt-2"
                        text={machineText[sectionClipKey(group.section.id)] ?? ""}
                        filenameBase={`Section-${group.section.code}-${group.section.title}-transcript`}
                      />
                    ) : null}
                  </div>
                ) : null}
                {group.items.map((question) => (
                  // Not <Field>: that wraps its children in a <label>, and a <label> names its first
                  // labelable descendant — which here is the record button, not the answer box. The
                  // button ended up announced as the whole prompt and the textarea as nothing at
                  // all. A plain heading plus aria-labelledby names both correctly.
                  <div key={question.id} className="grid gap-1">
                    <span className="field-label" id={`question-label-${question.id}`}>
                      {question.sortOrder}. {question.prompt}
                    </span>
                    {/* Same rule as the section recorder above: the button belongs to individual
                        mode, but clips already recorded stay visible and stay saved in either. */}
                    {capture.recordingMode === "INDIVIDUAL" || questionAudioFiles[question.id]?.length ? (
                      <ClipRecorder
                        hint={
                          capture.recordingMode === "INDIVIDUAL"
                            ? undefined
                            : "Recorded against this question earlier. It still uploads and saves with this interview."
                        }
                        showRecordButton={capture.recordingMode === "INDIVIDUAL"}
                        recording={recordingKey === question.id}
                        recordLabel="Record this question"
                        stopLabel="Stop question recording"
                        onStart={() =>
                          startRecording(
                            question.id,
                            `${safeFileName(question.sectionCode)}-${question.sortOrder}-${safeFileName(question.prompt)}`
                          )
                        }
                        onStop={stopRecording}
                        files={questionAudioFiles[question.id] ?? []}
                        previews={questionAudioPreviews[question.id] ?? []}
                        countLabel={clipCountLabel(question.id)}
                        onClear={() => setQuestionAudioFiles((current) => ({ ...current, [question.id]: [] }))}
                        onRemove={(index) =>
                          setQuestionAudioFiles((current) => ({
                            ...current,
                            [question.id]: (current[question.id] ?? []).filter((_, i) => i !== index)
                          }))
                        }
                        onOpenPreview={setActivePreview}
                        stream={questionStream}
                        elapsedMs={questionElapsedMs}
                      />
                    ) : null}
                    {/*
                      ══ AN EDIT THAT CARRIES WORDS DRAWS THE BOX, AND THE PREFERENCE IS UNTOUCHED ══

                      THE DEFECT. `DEFAULT_CAPTURE_PREFS` sets `hideAnswers: true`, and it is right to
                      — one take for a whole section is how these interviews are actually conducted.
                      But the gate was the preference and nothing else, so a sitting whose answers
                      were TYPED opened for correction with every one of them off screen:
                      `seedFromInterview` had loaded them into `answers`, `submit` would have sent
                      them back, and the researcher could see none of them. That is the owner's "already
                      existing entries … do not show up" for the typed half.

                      WHY THE BOX AND NOT READ-ONLY TEXT BESIDE THE RECORDER, which was the other way
                      to satisfy it. An edit form exists to CORRECT. Printing the answer read-only
                      shows the words and refuses the very act the researcher opened the form for —
                      their only route to fixing a typo would be flipping a stored preference, which
                      is the one thing this change must not touch, and which would then unhide the
                      boxes for every future capture as a side effect of one correction.

                      WHY IT DOES NOT AMOUNT TO FLIPPING THE PREFERENCE ANYWAY. This is per QUESTION
                      and keyed on the RECORD: a box appears only where the stored sitting actually has
                      words. Every unanswered question on the same edit stays exactly as the reader's
                      choice says, the capture form is unchanged, and `capture.hideAnswers` is neither
                      read differently nor written here — `updateCapture` is still only ever called by
                      `QuestionnaireCaptureControls`.
                    */}
                    {capture.hideAnswers && !recordedAnswerIds.has(question.id) ? null : (
                      <>
                        {/*
                          ── THE ANSWER BOX IS A RICH TEXT BOX, AND THE MICROPHONE MOVED INTO IT ──

                          Owner, 2026-08-30: the transcript *"should appear in the rich text box"*.
                          This was a plain `<TextArea>` with an `OnDeviceDictationButton` under it
                          until 2026-08-31. Both halves of that arrangement survive inside
                          `RichTextField` — the editor carries its own dictation button, so the
                          researcher who was pressing "Dictate" under the box is now pressing it in
                          the box's own toolbar, and `explainWhenUnavailable={false}` still defers to
                          the single `DictationUnavailableNotice` at the top of the form. What the
                          separate button could not do is hold the words a provider sends back with
                          any formatting at all, and a refined transcript's speaker labels ARE
                          formatting.

                          NOTHING ABOUT STORAGE CHANGES. `onValueChange` reports the same plain
                          string the textarea reported, because `encodeStoredRichText` writes prose
                          for an unformatted document and only stringifies a document once somebody
                          actually applies a mark — see `components/richtext/storedRichText.ts`. So
                          `answers[question.id]` is still a string, the payload is unchanged, and
                          every existing reader of `QuestionnaireResponse.answerText` sees exactly
                          what it saw before for every answer nobody formatted.

                          `labelledBy` RATHER THAN A SECOND LABEL: the question is already printed
                          above as the heading for this whole block — the recorder and the upload
                          readout sit under it too — so the box is named by that heading and the
                          short `label` is left to name the ACT for the microphone ("Dictate Answer
                          in English (India)"). Printing the prompt twice, or moving a two-thousand
                          character prompt into a button's accessible name, are the two things that
                          prop exists to avoid.

                          `key` IS LOAD-BEARING. The editor parses `defaultValue` once and never
                          re-reads it, so a transcript arriving from `applyTranscript` reaches a
                          mounted box only through a remount. See `answerSeed`.
                        */}
                        <RichTextField
                          key={`answer-${question.id}-${answerSeed[question.id] ?? 0}`}
                          // Read by nothing: this page builds its payload from the `answers` state,
                          // not from `FormData`. The prop is required because every other mount of
                          // this component IS read through the form, and a name that names the
                          // question is the one that would be least surprising if that ever changed.
                          name={`answer-${question.id}`}
                          label="Answer"
                          labelledBy={`question-label-${question.id}`}
                          defaultValue={answers[question.id] ?? ""}
                          explainWhenUnavailable={false}
                          onValueChange={(value) =>
                            setAnswers((current) => ({ ...current, [question.id]: value }))
                          }
                        />
                        <QuickTranscript
                          busy={!!transcribing[question.id]}
                          problem={quickProblem[question.id]}
                          machine={machineText[question.id]}
                          current={answers[question.id] ?? ""}
                          offered={offeredTranscript[question.id]}
                          filenameBase={`Q${question.sectionCode}${question.sortOrder}-transcript`}
                          onAccept={() => {
                            const text = offeredTranscript[question.id];
                            if (!text) return;
                            /*
                              ADDED TO THE ANSWER, NOT SUBSTITUTED FOR IT. The offer only exists
                              because the box holds words a person wrote, so the button that accepts
                              it must not be the one thing on this page that deletes them — "offer it,
                              do not impose it" is not satisfied by asking first and then overwriting.
                              Appending means neither branch of the whole feature can lose a syllable.

                              AND `machineText` IS DELIBERATELY LEFT ALONE. The box now holds the
                              researcher's words AND the machine's, so it IS edited and the flag must
                              go on saying so. Updating it here would relabel a mixed answer as
                              untouched machine output, which is the claim the flag exists to prevent.
                            */
                            /*
                              `appendDictatedToStored`, for the reason spelled out at
                              `applyTranscript`: the box holds a stored rich-text column, and this
                              branch is reached PRECISELY when a person wrote in it — which is the
                              case most likely to carry a mark, and the one where a bare append
                              destroys their words rather than merely printing badly.
                            */
                            const merged = appendDictatedToStored(answers[question.id] ?? "", text);
                            setAnswers((current) => ({ ...current, [question.id]: merged }));
                            setAnswerSeed((current) => ({ ...current, [question.id]: (current[question.id] ?? 0) + 1 }));
                            setOfferedTranscript((current) => {
                              const next = { ...current };
                              delete next[question.id];
                              return next;
                            });
                          }}
                          onDismiss={() =>
                            setOfferedTranscript((current) => {
                              const next = { ...current };
                              delete next[question.id];
                              return next;
                            })
                          }
                        />
                      </>
                    )}
                    <UploadProgress
                      progress={questionProgress[question.id] ?? null}
                      sectionId={clipTraySectionId(question.id)}
                      label={`Q${question.sectionCode}${question.sortOrder} audio`}
                      className="mt-2"
                    />
                  </div>
                ))}
                {/*
                  WHAT IS ALREADY SAVED AGAINST THIS SECTION. Android draws exactly this, under the
                  section it belongs to and after the questions; the web drew nothing at all, which is
                  half of the owner's first complaint. Read-only — `SavedClips` says why, and why the
                  upload tray must stay empty.
                */}
                <SavedClips
                  heading="Saved recordings & media for this section"
                  items={sectionClips}
                  onOpenPreview={setActivePreview}
                />
              </div>
            </details>
            );
          })}
        </div>
        {/*
          ANYTHING THAT BELONGS TO NO SECTION: the whole-interview audio, photographs, and clips whose
          question or section title was edited after they were recorded. The handset's "Other saved
          recordings & media" block, and it exists for the reason its own comment gives — so that
          nothing a sitting holds is invisible. Outside the sections loop because that is what "no
          section" means.
        */}
        <SavedClips heading="Other saved recordings & media" items={savedClips.other} onOpenPreview={setActivePreview} />
        <MultiNoteField name="notes" label="Interview notes" />
        <div>
          <button className="field-button" disabled={saving}>
            <Plus className="h-4 w-4" aria-hidden />
            {saving
              ? "Saving..."
              : editingInterview
                ? "Save changes"
                : existingEntry
                  ? "Add to shared entry"
                  : "Save interview"}
          </button>
        </div>
      </form>
      </MegaCard>

      <MegaCard
        title="Recorded interviews"
        note="Everything filed from this instrument, with the funnel and the search that narrow it."
        icon={ListFilter}
        tone="archive"
        /*
          THE COUNT IS THE SERVER'S TOTAL AND NOT THE LOADED PAGE'S LENGTH. `data.items` is one page
          of at most `pageSize`, so counting it would tell a reader with four hundred interviews that
          they have twenty. `data.total` is what the pager already prints. A page that has not loaded
          yet counts zero rather than guessing — and the card still opens, so the loading line inside
          it is what says so.
        */
        count={data?.total ?? 0}
        countLabel="interview"
        span="full"
        expanded={megaCards.isOpen("recorded")}
        onToggle={() => megaCards.toggle("recorded")}
      >
      <div className="mb-4 grid gap-3">
        <FunnelFilters value={funnel} onChange={(next) => { setFunnel(next); setPage(1); }} showArtisan />
        <SearchInput
          value={searchInput}
          onChange={handleSearchChange}
          onSubmit={applySearch}
          placeholder="Search interviews by title, place, or notes"
        />
      </div>

      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No questionnaire interviews yet" />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[980px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <th className="px-4 py-3">Interview</th>
                  <th className="px-4 py-3">Artisans</th>
                  <th className="px-4 py-3">Responses</th>
                  <th className="px-4 py-3">Researcher</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3">Date</th>
                  <th className="px-4 py-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {data.items.map((interview) => (
                  <Fragment key={interview.id}>
                    <tr>
                      <td className="px-4 py-3">
                        <div className="font-medium text-ink-900">{interview.title}</div>
                        <div className="text-xs text-ink-500">{interview.place ?? "-"}</div>
                      </td>
                      <td className="px-4 py-3 text-ink-700">
                        {interview.artisans?.map((link) => link.artisan.name).join(", ") || "-"}
                      </td>
                      <td className="px-4 py-3 text-ink-700">
                        <details>
                          <summary className="cursor-pointer font-semibold text-field-700">{interview.responses?.length ?? 0} answers</summary>
                          <div className="mt-2 grid max-w-lg gap-2">
                            {interview.responses?.map((response) => (
                              <div key={response.id} className="rounded-md bg-field-100 p-2 text-xs">
                                <div className="font-semibold text-ink">{response.question?.prompt}</div>
                                <div className="mt-1 whitespace-pre-wrap text-ink-muted">{plainFromStoredRichText(response.answerText)}</div>
                              </div>
                            ))}
                          </div>
                        </details>
                      </td>
                      <td className="px-4 py-3 text-ink-700">{interview.createdBy?.email ?? "-"}</td>
                      <td className="px-4 py-3">
                        <StatusBadge status={interview.status} />
                      </td>
                      {/* interviewDate is server-derived; recordedAt is what it is derived FROM, so it
                          is the right fallback for a row saved before the derivation existed. */}
                      <td className="px-4 py-3 text-ink-700">
                        {formatDate(interview.interviewDate ?? interview.recordedAt ?? interview.createdAt)}
                      </td>
                      <td className="px-4 py-3 text-right">
                        {/* The code is NOT gated on the admin view, and Delete still is. They are two
                            different kinds of action: deleting an interview destroys a sitting nobody
                            can retake, while showing its code reveals an opaque reference to a row
                            this person is already reading. Gating the code would mean a researcher
                            could see the interview and not the tag that opens it — which is the whole
                            point of the tag. The "Admin view only" line this cell used to fall back to
                            is gone because the cell is no longer ever empty. */}
                        <RowActions>
                          <button
                            className={rowAction("neutral", codeFor === interview.id ? "bg-surface-50" : undefined)}
                            onClick={() => setCodeFor(codeFor === interview.id ? null : interview.id)}
                            aria-expanded={codeFor === interview.id}
                          >
                            <QrCode className="h-3.5 w-3.5" aria-hidden />
                            {codeFor === interview.id ? "Hide code" : "Code"}
                          </button>
                          {/*
                            A `Link` AND NOT A BUTTON, so it is the same `?edit=` navigation the data
                            browser's row uses and the same one a colleague can paste — one route into
                            the edit form rather than two mechanisms that have to be kept agreeing.

                            OUTSIDE THE `adminMode` GATE BELOW, deliberately. Admin view is a
                            NARROWING switch over admin CHROME; correcting an interview is ordinary
                            work, and whether this account may correct THIS sitting is
                            `guard_record_edit`'s answer per record rather than a tier's. Hiding the
                            link behind a toggle would take it from every non-admin who is entitled to
                            it — which is almost everybody who records one.
                          */}
                          <Link className={rowAction()} href={`/questionnaire?edit=${interview.id}`}>
                            <Pencil className="h-3.5 w-3.5" aria-hidden />
                            Edit
                          </Link>
                          {adminMode ? (
                            <button className={rowAction("danger")} onClick={() => remove(interview.id)}>
                              Delete
                            </button>
                          ) : null}
                        </RowActions>
                      </td>
                    </tr>
                    {codeFor === interview.id ? (
                      /* An expanded row and not a route, because an interview has no per-record page
                         on the web — `lib/workshopCodeLookup.ts` says so in as many words and lands a
                         scanned Q code on this list. This is the closest thing a designer opens for
                         ONE interview, so it is where the code for one interview belongs; putting it
                         anywhere else would mean a scan and a print disagreed about where an
                         interview lives. Android shows the same card on its interview edit screen
                         (`RecordCodeSection(..., QUESTIONNAIRE, ...)` in MainActivity). */
                      <tr className="bg-surface-50">
                        <td className="px-4 py-3" colSpan={7}>
                          <RecordCodeCard recordType="questionnaire" id={interview.id} title={interview.title} />
                        </td>
                      </tr>
                    ) : null}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>
      </MegaCard>

      {/* Collapsed by default, and half the rail — see the rail's header for why it is no longer at
          the top of the page. */}
      <CompletionMatrixPanel
        canOverride={adminMode && isAdmin(user)}
        expanded={megaCards.isOpen("completion")}
        onToggle={() => megaCards.toggle("completion")}
      />

      {canManageQuestionnaire(user) ? (
        <QuestionnaireAdminEditor
          sections={sections}
          onChanged={loadMeta}
          expanded={megaCards.isOpen("builder")}
          onToggle={() => megaCards.toggle("builder")}
        />
      ) : null}
      </div>

      {activePreview ? <MediaLightbox item={activePreview} onClose={() => setActivePreview(null)} /> : null}
    </>
  );
}

function safeFileName(value: string) {
  return value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "")
    .slice(0, 80) || "question-audio";
}

/**
 * Recordings and media already saved with this interview, drawn to be read and never to be re-sent.
 *
 * ══ WHY THIS IS NOT `ExistingMedia`, THE REPOSITORY'S OWN PANEL ════════════════════════════════
 *
 * `components/media/ExistingMedia.tsx` is this repository's one existing "what is already attached"
 * surface, and it is mounted on artisans, products, tools, crafts and workshops —
 * `<ExistingMedia linkedRecordType="artisan" linkedRecordId={editing.id} />` in `ArtisanForm`, and
 * the same two props in `ToolForm`. It was the first thing tried here. Three things about it are
 * wrong for this form, and none of them is cosmetic:
 *
 *  1. IT CANNOT GROUP, and grouping is the whole request. It fetches one flat `/media` page for a
 *     record and draws one gallery; it has no notion of this instrument's sections and nothing to
 *     group by. Its two props are a linked type and a linked id — there is no third that could say
 *     "and split this by section". The complaint is that media "do not show up in the RESPECTIVE
 *     SECTIONS", which is the one thing that panel does not do.
 *  2. IT IS A DELETE SURFACE. Every tile carries a remove control that calls `DELETE /media/{id}`
 *     and permanently destroys the file. On an interview the clips ARE the record — the backend
 *     derives section coverage from them — and this form's contract for them is read-only.
 *  3. IT WOULD RE-FETCH WHAT IS ALREADY IN HAND and mount a second `MediaLightbox` beside the one
 *     this page already owns, so one gesture would have two dialogs able to answer it.
 *
 * So this is the smallest thing that does the job, and it REUSES rather than replaces: the tiles are
 * `MediaPreviewTile`, and opening one calls this page's own `setActivePreview`, so a clip saved last
 * month and a clip recorded a moment ago open in the same lightbox.
 *
 * NO `onRemove` IS PASSED, and that is what makes the tile read-only — `MediaPreviewTile` draws its
 * remove button only when handed one. Nothing here touches `mediaFiles` or `questionAudioFiles`, so
 * nothing here can reach the upload path on the next Save.
 *
 * DRAWS NOTHING WHEN THERE IS NOTHING, deliberately: an "0 files" heading under every section of a
 * create form is furniture, and the counts on each `<summary>` already say what a section holds.
 */
function SavedClips({
  heading,
  items,
  onOpenPreview
}: {
  heading: string;
  items: MediaFile[];
  onOpenPreview: (item: PreviewMedia) => void;
}) {
  if (!items.length) return null;
  return (
    <div className="rounded-md border border-line-200 bg-card p-3">
      <h4 className="font-display text-sm font-bold text-ink">{heading}</h4>
      <p className="mt-0.5 text-xs text-ink-muted">
        Already saved with this interview and still attached to it. This form does not send them again, so there is
        nothing here to re-upload.
      </p>
      <ul className="mt-2 grid gap-2 sm:grid-cols-2">
        {items.map((media) => {
          const preview: PreviewMedia = {
            key: media.id,
            id: media.id,
            name: media.originalFilename,
            mediaType: media.mediaType,
            mimeType: media.mimeType,
            sizeBytes: media.sizeBytes,
            url: media.url,
            caption: media.caption,
            transcriptStatus: media.transcriptStatus,
            transcriptText: media.transcriptText,
            transcriptError: media.transcriptError
          };
          return (
            // `content-start` because the wrapping <li> stretches to its row's height so neighbours
            // line up; without it the rows inside stretch with it and the caption floats off the tile.
            <li key={media.id} className="grid content-start gap-1 rounded-md border border-line-200 bg-field-100 p-2">
              <MediaPreviewTile item={preview} onOpen={() => onOpenPreview(preview)} />
              <div className="min-w-0">
                <div className="truncate text-xs font-medium text-ink" title={media.originalFilename}>
                  {media.caption || media.originalFilename}
                </div>
                <div className="text-xs text-ink-muted">
                  {media.uploadedBy?.name ?? "Unknown"} · {formatDateTime(media.createdAt)}
                </div>
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/**
 * The recorder for one clip key — a whole section, or a single question. One component for both so
 * the two modes cannot drift into two slightly different recorders, and so a clip list is rendered
 * identically whether or not its recorder's button is currently on offer.
 */
/**
 * What happened to the voice note, and what the box holds because of it.
 *
 * FOUR STATES, AND EVERY ONE OF THEM IS SAID IN WORDS. A round trip to a transcription provider
 * takes seconds on a village connection, so silence under the box would read as a recorder that ate
 * the take; a refusal that says nothing reads the same way. Non-negotiable 10 is the rule here as
 * everywhere: a thing that quietly did not happen is indistinguishable from a thing that was never
 * offered.
 *
 * THE FLAG IS DERIVED, NOT STORED, and it can only be derived because the page keeps the machine's
 * own copy (`machineText`). "Has a human changed this" is unanswerable from the answer alone — a
 * researcher who typed the answer out and one who accepted a transcript verbatim both end up holding
 * a string. `machine === undefined` therefore draws NO flag at all rather than "Not edited": an
 * answer nobody dictated has no machine text to have departed from, and stamping "Not edited" on a
 * hand-typed sentence would credit a provider with a researcher's words.
 *
 * THE OFFER IS THE OWNER'S RULE MADE LITERAL: *"unless the designer has edited the text, in which
 * case it must not silently overwrite their words. Offer it, do not impose it."* The offered text is
 * rendered in full inside a `MarkdownDocument` rather than hidden behind a button, because a person
 * deciding whether to replace their own writing needs to read what would replace it — and having it
 * on screen means it can be copied or saved even if the answer is kept.
 */
function QuickTranscript({
  busy,
  problem,
  machine,
  current,
  offered,
  filenameBase,
  onAccept,
  onDismiss
}: {
  busy: boolean;
  problem?: string;
  /** The machine's words as last written into the box, or undefined if it never wrote any. */
  machine?: string;
  current: string;
  /** A newer transcript held back because the box had been edited. */
  offered?: string;
  filenameBase: string;
  onAccept: () => void;
  onDismiss: () => void;
}) {
  const edited = machine === undefined ? undefined : current.trim() !== machine.trim();
  if (!busy && !problem && !offered && edited === undefined) return null;
  return (
    <div className="grid gap-2">
      <div className="flex flex-wrap items-center gap-2">
        {busy ? (
          <span className="inline-flex items-center gap-1.5 text-xs text-ink-muted">
            {/* A static ring plus the word, never a spinner alone: the CSS reduced-motion rules zero
                the animation, and a signal that exists only as motion is one a reduced-motion reader
                never gets. */}
            <span className="h-3 w-3 animate-spin rounded-full border-2 border-purple-300 border-t-purple-700" aria-hidden />
            Transcribing…
          </span>
        ) : null}
        <EditedFlag edited={edited} />
      </div>
      {offered ? (
        <div className="rounded-md border border-amber-500 bg-amber-100 p-3">
          {/* Terse, and it states the one thing a person needs before pressing either button: their
              own words survive whichever they choose. */}
          <p className="mb-2 text-xs font-semibold text-amber-800">
            Another take was transcribed. Your words are kept either way.
          </p>
          <MarkdownDocument text={offered} filenameBase={filenameBase} />
          <div className="mt-2 flex flex-wrap gap-2">
            {/* "Add to answer", not "Use this": the accept path appends, and a button whose word
                implies replacement would have a person expecting to lose their edits — or, worse,
                pressing Discard to protect words that were never at risk. */}
            <button type="button" className="field-button !min-h-8 !px-2.5 !py-1 text-xs" onClick={onAccept}>
              Add to answer
            </button>
            <button type="button" className="field-button-secondary !min-h-8 !px-2.5 !py-1 text-xs" onClick={onDismiss}>
              Discard
            </button>
          </div>
        </div>
      ) : null}
      {problem ? <p className="text-xs text-ink-muted">{problem}</p> : null}
    </div>
  );
}

function ClipRecorder({
  hint,
  showRecordButton,
  recording,
  recordLabel,
  stopLabel,
  onStart,
  onStop,
  files,
  previews,
  countLabel,
  onClear,
  onRemove,
  onOpenPreview,
  stream,
  elapsedMs
}: {
  hint?: string;
  showRecordButton: boolean;
  recording: boolean;
  recordLabel: string;
  stopLabel: string;
  onStart: () => void;
  onStop: () => void;
  files: File[];
  previews: PreviewMedia[];
  countLabel: string;
  onClear: () => void;
  onRemove: (index: number) => void;
  onOpenPreview: (item: PreviewMedia) => void;
  stream: MediaStream | null;
  elapsedMs: number;
}) {
  return (
    <>
      {hint ? <p className="mb-2 text-xs text-ink-muted">{hint}</p> : null}
      <div className="mb-2 flex flex-wrap items-center gap-2">
        {showRecordButton ? (
          <button type="button" className="field-button-secondary" onClick={() => (recording ? onStop() : onStart())}>
            {recording ? <Square className="h-4 w-4" aria-hidden /> : <Mic className="h-4 w-4" aria-hidden />}
            {recording ? stopLabel : recordLabel}
          </button>
        ) : null}
        {files.length ? (
          <>
            <span className="text-xs text-ink-muted">{countLabel}</span>
            <button type="button" className="text-xs font-semibold text-red-700" onClick={onClear}>
              Clear clips
            </button>
          </>
        ) : null}
      </div>
      {recording ? (
        <div className="mb-3">
          <RecordingStrip stream={stream} elapsedMs={elapsedMs} />
        </div>
      ) : null}
      {previews.length ? (
        <div className="mb-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
          {previews.map((item, index) => (
            <MediaPreviewTile
              key={item.key}
              item={item}
              onOpen={() => onOpenPreview(item)}
              action={
                <button type="button" className="text-xs font-semibold text-red-700" onClick={() => onRemove(index)}>
                  Remove
                </button>
              }
            />
          ))}
        </div>
      ) : null}
    </>
  );
}

// --- "Check completion" cellular matrix (Android parity) ---

type CompletionMatrix = {
  sections: Array<{ id: string; code: string; title: string; sortOrder: number }>;
  artisans: Array<{ id: string; name: string }>;
  cells: Array<{ artisanId: string; sectionId: string; derived: boolean; status: string | null; setByName?: string | null }>;
  /**
   * How many interviews the CHOSEN WORKSHOP SCOPE cannot see, because they name no workshop at all.
   *
   * The number exists so the failure mode can never be silent again. An interview with an empty
   * `workshopId` counts towards no workshop scope, which is correct — it genuinely does not say where it
   * was taken — but it is also exactly the shape of the bug that had this matrix reporting "nothing was
   * covered" while twenty-five interviews sat in the repository unlinked. Zero whenever the scope cannot
   * hide anything (no workshop chosen, or unassigned records explicitly included). Absent on an API that
   * predates the field.
   */
  unassignedInterviews?: number;
  /** True while an admin's mark is keyed on (artisan, section) alone, i.e. is not per workshop. */
  overridesAreRepositoryWide?: boolean;
};

/**
 * Artisans down the rows, questionnaire sections across the columns. A cell is green when the
 * section is covered — either derived from recorded answers/audio or forced by an admin override
 * (overrides carry an amber ring). In admin view, admins click a cell to cycle the override:
 * complete -> not complete -> clear (back to the derived state).
 */
function CompletionMatrixPanel({
  canOverride,
  expanded,
  onToggle
}: {
  canOverride: boolean;
  /**
   * Open/closed is owned by the PAGE now rather than by this panel, because all four mega cards on
   * this screen share one remembered set. The lazy load below follows `expanded` instead of an
   * `onOpenChange` callback — same guard, same `opened` ref, one less thing to keep in step.
   */
  expanded: boolean;
  onToggle: () => void;
}) {
  const [matrix, setMatrix] = useState<CompletionMatrix | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busyCell, setBusyCell] = useState<string | null>(null);
  const opened = useRef(false);

  /**
   * WHICH WORKSHOPS THIS MATRIX IS ABOUT, defaulting to the most recent one.
   *
   * The scope is held HERE rather than inside the accordion because the accordion unmounts its
   * children when it closes, and a selection that reset every time somebody collapsed the panel would
   * be worse than no control at all.
   *
   * It also scopes BOTH halves of the matrix on the server — which artisans are rows, and which
   * interviews count towards a cell. A matrix that scoped only one of the two would show a workshop's
   * artisans against every interview they have ever sat in, which is precisely the confusion this
   * control exists to remove.
   */
  const scope = useWorkshopScope();

  const refresh = useCallback(async () => {
    try {
      setMatrix(
        await apiFetch<CompletionMatrix>(
          `/questionnaire/completion${buildQuery({ workshopIds: scope.queryValue })}`
        )
      );
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load the completion matrix");
    }
  }, [scope.queryValue]);

  /**
   * Load once the panel is open, and RELOAD whenever the scope moves — but never before the picker has
   * settled on its default, or the first request would go out scoped to "all" and be replaced a
   * moment later by the scoped one. Two requests, and for a second the matrix shows the wrong answer.
   */
  useEffect(() => {
    if (!opened.current || scope.settling) return;
    setLoading(true);
    refresh().finally(() => setLoading(false));
  }, [refresh, scope.settling]);

  /**
   * THE FIRST OPEN IS WHAT FETCHES, and it used to arrive as `Accordion`'s `onOpenChange`. The mega
   * card is CONTROLLED by the page, so there is no callback to hang this on any more — the signal is
   * the `expanded` prop going true, and the `opened` ref is what keeps it a FIRST open rather than
   * every open. Without that ref this would re-fetch the whole matrix on every collapse and expand.
   *
   * `scope.settling` is still the early return it always was: the workshop scope has not resolved
   * its default yet, and the effect above fires the moment it does. Fetching here as well would send
   * two requests for the same matrix and render whichever answered last.
   */
  useEffect(() => {
    if (!expanded || opened.current) return;
    opened.current = true;
    if (scope.settling) return;
    setLoading(true);
    refresh().finally(() => setLoading(false));
    // `refresh` is stable per scope and `scope.settling` is read, not watched: this must run on the
    // first open and never again, which is what `opened` enforces.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expanded]);

  const cellByKey = useMemo(() => {
    const map = new Map<string, CompletionMatrix["cells"][number]>();
    matrix?.cells.forEach((cell) => map.set(`${cell.artisanId}:${cell.sectionId}`, cell));
    return map;
  }, [matrix]);

  async function cycle(artisanId: string, sectionId: string) {
    if (!canOverride) return;
    const key = `${artisanId}:${sectionId}`;
    const cell = cellByKey.get(key);
    // No override yet -> force complete; forced complete -> force not complete; any other override -> clear.
    const next = !cell?.status ? "COMPLETED" : cell.status === "COMPLETED" ? "NEEDS_REDO" : null;
    setBusyCell(key);
    try {
      await apiFetch("/questionnaire/completion", {
        method: "PUT",
        body: JSON.stringify({ artisanId, sectionId, status: next })
      });
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to update the completion cell");
    } finally {
      setBusyCell(null);
    }
  }

  return (
    <MegaCard
      title="Check completion"
      note={`Which questionnaire sections are covered for each artisan, derived from recorded answers and audio.${
        canOverride ? " Click a cell to cycle an admin override: complete, not complete, clear." : ""
      }`}
      icon={ClipboardCheck}
      tone="errand"
      /*
        THE ROWS, NOT THE CELLS. A matrix is artisans × sections and either number could be called
        "how much is in here"; the artisan count is the one a reader is looking for, because the
        question this panel answers is "who has not been interviewed yet". Null until it loads, and
        zero is then honest rather than a guess — the panel's own empty state says the rest.
      */
      count={matrix?.artisans?.length ?? 0}
      countLabel="artisan"
      expanded={expanded}
      onToggle={onToggle}
    >
      {/* The scope sits ABOVE the matrix, because it changes what every cell means. */}
      <div className="mb-4 max-w-xl">
        <WorkshopScopeSelect scope={scope} label="Workshops in this matrix" />
      </div>
      {error ? <div className="mb-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {loading ? <div className="text-sm text-ink-muted">Loading completion matrix...</div> : null}

      {/* THE SHORTFALL, NAMED. An interview that names no workshop is excluded from this matrix under any
          workshop scope, and the exclusion used to be invisible: the matrix simply showed less green, which
          reads as fieldwork that never happened. Saying the number turns that into something an admin can
          act on — and the action is one page away. */}
      {matrix && (matrix.unassignedInterviews ?? 0) > 0 ? (
        <div className="mb-3 rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
          <span className="font-semibold">
            {matrix.unassignedInterviews} interview{matrix.unassignedInterviews === 1 ? "" : "s"} in the
            repository name no workshop
          </span>
          , so {matrix.unassignedInterviews === 1 ? "it counts" : "they count"} towards no workshop scope and
          nothing {matrix.unassignedInterviews === 1 ? "it holds" : "they hold"} turns a cell green here.
          Choose <span className="font-semibold">All records</span> above to include{" "}
          {matrix.unassignedInterviews === 1 ? "it" : "them"}
          {canOverride ? (
            <>
              , or file {matrix.unassignedInterviews === 1 ? "it" : "them"} under a workshop from{" "}
              <Link href="/workshops" className="font-semibold underline">
                Workshops
              </Link>
            </>
          ) : null}
          .
        </div>
      ) : null}
      {matrix && matrix.artisans.length === 0 && !loading ? (
        <p className="text-sm text-ink-muted">
          {scope.workshopIds.length
            ? "No artisans in the chosen workshops. Widen the scope, or choose All records."
            : "No artisans yet — the matrix appears once artisans are recorded."}
        </p>
      ) : null}
      {matrix && matrix.artisans.length > 0 ? (
        <>
          <div className="overflow-x-auto">
            <table className="text-left text-sm">
              <thead>
                <tr>
                  <th className="py-2 pr-4 text-xs font-semibold uppercase text-ink-500">Artisan</th>
                  {matrix.sections.map((section) => (
                    <th
                      key={section.id}
                      className="px-1 pb-2 text-center align-bottom text-xs font-semibold text-ink-500"
                      title={`${section.code}. ${section.title}`}
                    >
                      {section.code}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {matrix.artisans.map((artisan) => (
                  <tr key={artisan.id}>
                    <td className="whitespace-nowrap py-1 pr-4 font-medium text-ink">{artisan.name}</td>
                    {matrix.sections.map((section) => {
                      const key = `${artisan.id}:${section.id}`;
                      const cell = cellByKey.get(key);
                      const overridden = Boolean(cell?.status);
                      const complete = cell ? (cell.status ? cell.status === "COMPLETED" : cell.derived) : false;
                      const state = overridden
                        ? `override ${cell?.status === "COMPLETED" ? "complete" : "not complete"}${cell?.setByName ? ` by ${cell.setByName}` : ""}`
                        : complete
                          ? "complete"
                          : "not complete";
                      const title = `${artisan.name} — ${section.code}: ${state}`;
                      const square = `h-6 w-6 rounded ${complete ? "bg-success-600" : "bg-line-200"}${overridden ? " ring-2 ring-amber-500" : ""}`;
                      return (
                        <td key={section.id} className="px-1 py-1 text-center">
                          {canOverride ? (
                            <button
                              type="button"
                              className={`${square} align-middle disabled:opacity-60`}
                              title={title}
                              aria-label={title}
                              disabled={busyCell === key}
                              onClick={() => cycle(artisan.id, section.id)}
                            />
                          ) : (
                            <div className={`mx-auto ${square}`} title={title} role="img" aria-label={title} />
                          )}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-ink-muted">
            <span className="inline-flex items-center gap-1.5">
              <span className="h-3.5 w-3.5 rounded bg-success-600" aria-hidden /> Complete
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="h-3.5 w-3.5 rounded bg-line-200" aria-hidden /> Not complete
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="h-3.5 w-3.5 rounded bg-line-200 ring-2 ring-amber-500" aria-hidden /> Admin override
            </span>
          </div>
          {/* WHAT THE WORKSHOP SCOPE DOES AND DOES NOT NARROW. The green derived from recordings is scoped
              to the chosen workshops; an admin's mark is not, because the override table is keyed on
              (artisan, section) with no workshop column — a mark is a judgement about that artisan's
              section across the repository. Said here rather than left for a reader to deduce from a cell
              that stays green when the scope moves. */}
          {matrix.overridesAreRepositoryWide ? (
            <p className="mt-2 text-[11px] leading-4 text-ink-500">
              The workshop scope narrows the green derived from recordings. An admin override is a judgement
              about that artisan&rsquo;s section across the whole repository, so a marked cell keeps its
              colour under every scope.
            </p>
          ) : null}
        </>
      ) : null}
    </MegaCard>
  );
}

// --- Questionnaire builder (admin) — sections + drag-and-drop question tiles ---

type DragQuestion = { questionId: string; sectionId: string };
type DropIndicator = { sectionId: string; index: number };

/** Renumber a section's questions after an optimistic move and stamp them with the section identity. */
function reindexQuestions(list: QuestionnaireQuestion[], section: { id: string; code: string; title: string }): QuestionnaireQuestion[] {
  return list.map((question, index) => ({
    ...question,
    sortOrder: index + 1,
    sectionId: section.id,
    sectionCode: section.code,
    sectionTitle: section.title
  }));
}

/**
 * Pure optimistic move: takes the current sections and returns a new array with `questionId` moved
 * from its section to `gapIndex` within `toSectionId` (a drop-gap index, 0..length). Returns the
 * SAME reference when the move is a no-op so callers can skip the network round-trip.
 */
function moveQuestionInSections(
  sections: QuestionnaireSection[],
  questionId: string,
  fromSectionId: string,
  toSectionId: string,
  gapIndex: number
): QuestionnaireSection[] {
  const clone = sections.map((section) => ({ ...section, questions: [...section.questions] }));
  const from = clone.find((section) => section.id === fromSectionId);
  const to = clone.find((section) => section.id === toSectionId);
  if (!from || !to) return sections;
  const srcIndex = from.questions.findIndex((question) => question.id === questionId);
  if (srcIndex < 0) return sections;

  // A drop-gap counts the dragged tile; removing it first shifts every later gap left by one.
  let insertAt = from === to && srcIndex < gapIndex ? gapIndex - 1 : gapIndex;
  const [moved] = from.questions.splice(srcIndex, 1);
  insertAt = Math.max(0, Math.min(insertAt, to.questions.length));
  if (from === to && insertAt === srcIndex) return sections; // dropped back onto itself
  to.questions.splice(insertAt, 0, moved);
  from.questions = reindexQuestions(from.questions, from);
  if (to !== from) to.questions = reindexQuestions(to.questions, to);
  return clone;
}

/**
 * One section-title box on the admin editor's per-section row, dictated.
 *
 * WHY A COMPONENT AND NOT `DictatedTextInput` INLINE. That control is controlled by its caller and
 * has exactly one mode — its header refuses a second, because "a control that is sometimes
 * controlled is a control whose reset behaviour has to be re-derived at every call site". These rows
 * are drawn inside a `.map` over the sections and their form reads `FormData` at submit, so hoisting
 * a value per row into the editor would mean a keyed map of drafts and a reconciliation with every
 * reload. Seeding once here is the same thing `DictatedTextArea` does internally, scoped to one row.
 *
 * THE `key` AT THE CALL SITE IS WHAT RE-SEEDS IT — `${section.id}-${section.title}` — so a title
 * changed anywhere else and reloaded remounts this box, exactly as the `defaultValue` it replaces
 * would have been re-read. Without it the box would keep showing a stale title after a save.
 */
function SeededSectionTitle({ initial }: { initial: string }) {
  const [value, setValue] = useState(initial);
  return (
    <DictatedTextInput
      name="title"
      label="Title"
      required
      value={value}
      onChange={setValue}
      explainWhenUnavailable={false}
    />
  );
}

function QuestionnaireAdminEditor({
  sections,
  onChanged,
  expanded,
  onToggle
}: {
  sections: QuestionnaireSection[];
  onChanged: () => Promise<void>;
  expanded: boolean;
  onToggle: () => void;
}) {
  const confirm = useConfirm();
  const [localSections, setLocalSections] = useState<QuestionnaireSection[]>(sections);
  const [newCode, setNewCode] = useState("");
  const [newTitle, setNewTitle] = useState("");
  const [dragSectionId, setDragSectionId] = useState<string | null>(null);
  const [dragQuestion, setDragQuestion] = useState<DragQuestion | null>(null);
  const [dropIndicator, setDropIndicator] = useState<DropIndicator | null>(null);
  const [busy, setBusy] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  // Server state is authoritative: whenever the parent reloads sections, replace the local copy
  // (this also lands the canonical result after an optimistic drag persists).
  useEffect(() => {
    setLocalSections(sections);
  }, [sections]);

  async function addSection(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setMessage(null);
    try {
      await apiFetch("/questionnaire/sections", {
        method: "POST",
        body: JSON.stringify({ code: newCode.trim(), title: newTitle.trim() })
      });
      setNewCode("");
      setNewTitle("");
      await onChanged();
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "Unable to add section");
    } finally {
      setSaving(false);
    }
  }

  async function updateSection(section: QuestionnaireSection, form: FormData) {
    await apiFetch(`/questionnaire/sections/${section.id}`, {
      method: "PATCH",
      body: JSON.stringify({
        code: String(form.get("code") ?? "").trim(),
        title: String(form.get("title") ?? "").trim(),
        isActive: form.get("isActive") === "on"
      })
    });
    await onChanged();
  }

  async function removeSection(section: QuestionnaireSection) {
    // Amber, not red: this is a deactivation. Nothing already collected is lost, and saying so is the
    // difference between a manager pruning the form and a manager afraid to touch it.
    const ok = await confirm({
      title: `Remove section ${section.code}?`,
      body: (
        <>
          <span className="font-medium text-ink-900">{section.title}</span> and its questions stop appearing in new
          interviews.
        </>
      ),
      note: "Questions are deactivated, not erased: answers already recorded against them stay on the interviews that hold them.",
      tone: "warning",
      confirmLabel: "Remove section"
    });
    if (!ok) return;
    setMessage(null);
    try {
      await apiFetch(`/questionnaire/sections/${section.id}`, { method: "DELETE" });
      await onChanged();
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "Unable to remove section");
    }
  }

  // Optimistically reorder sections, persist, and roll back if the server rejects it.
  async function performSectionReorder(fromId: string, toId: string) {
    if (!fromId || fromId === toId) return;
    const fromIndex = localSections.findIndex((section) => section.id === fromId);
    const toIndex = localSections.findIndex((section) => section.id === toId);
    if (fromIndex < 0 || toIndex < 0) return;
    const next = [...localSections];
    const [moved] = next.splice(fromIndex, 1);
    next.splice(toIndex, 0, moved);
    const reindexed = next.map((section, index) => ({ ...section, sortOrder: index + 1 }));
    const snapshot = localSections;
    setLocalSections(reindexed);
    setBusy(true);
    setMessage(null);
    try {
      await apiFetch("/questionnaire/sections/reorder", {
        method: "POST",
        body: JSON.stringify({ sectionIds: reindexed.map((section) => section.id) })
      });
      await onChanged();
    } catch (err) {
      setLocalSections(snapshot);
      setMessage(err instanceof Error ? err.message : "Unable to reorder sections");
    } finally {
      setBusy(false);
    }
  }

  async function addQuestion(section: QuestionnaireSection, form: FormData) {
    const prompt = String(form.get("prompt") ?? "").trim();
    if (!prompt) return;
    await apiFetch("/questionnaire/questions", {
      method: "POST",
      body: JSON.stringify({ sectionId: section.id, prompt })
    });
    await onChanged();
  }

  async function saveQuestion(question: QuestionnaireQuestion, prompt: string, isActive: boolean): Promise<boolean> {
    setBusy(true);
    setMessage(null);
    try {
      await apiFetch(`/questionnaire/questions/${question.id}`, {
        method: "PATCH",
        body: JSON.stringify({ prompt: prompt.trim(), isActive })
      });
      await onChanged();
      return true;
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "Unable to update question");
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function removeQuestion(question: QuestionnaireQuestion) {
    const ok = await confirm({
      title: "Remove this question?",
      body: (
        <>
          <span className="font-medium text-ink-900">{question.prompt}</span> stops appearing in new interviews.
        </>
      ),
      note: "Answers already recorded against it stay linked to the interviews that hold them.",
      tone: "warning",
      confirmLabel: "Remove question"
    });
    if (!ok) return;
    setMessage(null);
    try {
      await apiFetch(`/questionnaire/questions/${question.id}`, { method: "DELETE" });
      await onChanged();
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "Unable to remove question");
    }
  }

  /**
   * Optimistically apply a question move, then persist. Within a section this is a single reorder;
   * across sections we first re-parent the question (updateQuestion{sectionId}) and then reorder the
   * TARGET section — exactly the two existing API calls.
   *
   * ── A PURE REORDER ROLLS BACK. A CROSS-SECTION MOVE RESYNCS. ────────────────────────────────────
   *
   * This said "Rolls back on any error" and did exactly that, which was wrong for one of the two
   * shapes — corrected 2026-09-03. A cross-section move is TWO writes, and the first of them lands
   * on its own: once the PATCH has returned, the question belongs to the target section on the
   * server whatever the reorder that follows does. Restoring the pre-move snapshot after a failed
   * reorder therefore redrew the question in the section it had LEFT, under a sentence saying the
   * move had failed — a screen and a server disagreeing about where a question lives, with the
   * screen the more confident of the two. The next reader to open the page saw it in its new home
   * with no explanation, and any edit made in the meantime was aimed at the wrong row.
   *
   * A single-section reorder has no such half-state: one POST either lands or does not, so the
   * snapshot is the truth and restoring it is right.
   *
   * So the catch splits on `reparent`. Where one was sent it asks the server what actually happened
   * — `onChanged()` refetches and replaces the guess with canonical state — and where it was not,
   * it restores the snapshot as before. The resync is BEST-EFFORT and wrapped in its own try: the
   * commonest reason the reorder failed is that the network is gone, in which case the refetch will
   * fail too, and a throw there would replace the move's own message with the refetch's and lose the
   * only sentence that names what the designer just did. The optimistic array is then left standing,
   * which is the closer of the two guesses — the re-parent it shows really did happen; only the
   * position within the target section is unproven. The message says the move is unconfirmed either
   * way, which is what makes leaving it honest rather than silent (§1.10).
   */
  async function persistMove(next: QuestionnaireSection[], reorderSectionId: string, reparent?: { questionId: string; toSectionId: string }) {
    const snapshot = localSections;
    setLocalSections(next);
    setBusy(true);
    setMessage(null);
    /** Flipped the moment the PATCH returns: from here on the server has moved the question. */
    let reparented = false;
    try {
      if (reparent) {
        await apiFetch(`/questionnaire/questions/${reparent.questionId}`, {
          method: "PATCH",
          body: JSON.stringify({ sectionId: reparent.toSectionId })
        });
        reparented = true;
      }
      const target = next.find((section) => section.id === reorderSectionId);
      if (target) {
        await apiFetch("/questionnaire/questions/reorder", {
          method: "POST",
          body: JSON.stringify({ sectionId: reorderSectionId, questionIds: target.questions.map((question) => question.id) })
        });
      }
      await onChanged();
    } catch (err) {
      if (reparented) {
        // The write that already landed is not undone here — there is no "move it back" call that
        // would not be a second guess — so the screen is re-read from the server instead.
        try {
          await onChanged();
        } catch {
          // Offline, almost always. The optimistic array stays; the message below says so.
        }
        setMessage(
          err instanceof Error
            ? `Moved, but the order was not saved. ${err.message}`
            : "Moved, but the order was not saved."
        );
      } else {
        setLocalSections(snapshot);
        setMessage(err instanceof Error ? err.message : "Unable to move question");
      }
    } finally {
      setBusy(false);
    }
  }

  function performQuestionMove(drag: DragQuestion, toSectionId: string, gapIndex: number) {
    const next = moveQuestionInSections(localSections, drag.questionId, drag.sectionId, toSectionId, gapIndex);
    setDragQuestion(null);
    setDropIndicator(null);
    if (next === localSections) return; // no-op
    persistMove(next, toSectionId, drag.sectionId !== toSectionId ? { questionId: drag.questionId, toSectionId } : undefined);
  }

  // Keyboard fallback: swap a question with its neighbour inside the same section.
  function keyboardMove(section: QuestionnaireSection, question: QuestionnaireQuestion, direction: -1 | 1) {
    const current = localSections.find((item) => item.id === section.id);
    if (!current) return;
    const index = current.questions.findIndex((item) => item.id === question.id);
    const target = index + direction;
    if (index < 0 || target < 0 || target >= current.questions.length) return;
    const nextQuestions = [...current.questions];
    [nextQuestions[index], nextQuestions[target]] = [nextQuestions[target], nextQuestions[index]];
    const next = localSections.map((item) =>
      item.id === section.id ? { ...item, questions: reindexQuestions(nextQuestions, item) } : item
    );
    persistMove(next, section.id);
  }

  function moveQuestionToSection(question: QuestionnaireQuestion, fromSectionId: string, toSectionId: string) {
    if (!toSectionId || toSectionId === fromSectionId) return;
    const target = localSections.find((section) => section.id === toSectionId);
    performQuestionMove({ questionId: question.id, sectionId: fromSectionId }, toSectionId, target ? target.questions.length : 0);
  }

  const dragActive = Boolean(dragQuestion);

  return (
    <MegaCard
      title="Questionnaire Builder"
      note="Master admin controls for sections, ordering, question text, moves and removals."
      icon={Wrench}
      tone="steward"
      count={localSections.length}
      countLabel="section"
      expanded={expanded}
      onToggle={onToggle}
      // Outside the toggle, so reading the failure does not also collapse the editor you were in.
      headerRight={message ? <span className="text-sm text-red-700">{message}</span> : null}
    >
      <div className="grid gap-4">
        <form onSubmit={addSection} className="grid gap-3 rounded-md border border-line-200 bg-field-100 p-3 md:grid-cols-[160px_1fr_auto]">
          {/* THE CODE STAYS BARE AND THE TITLE DOES NOT, which is the same split Android makes on
              this very screen: `TextInput("New section code", newCode, dictate = false)` beside
              `TextInput("Title", sectionTitle)` with no opt-out (MainActivity.kt). A code is "A" or
              "RESP" and a recogniser returns the nearest DICTIONARY word for both; a section title
              is a phrase somebody composes. */}
          <Field label="Section code">
            <TextInput value={newCode} onChange={(event) => setNewCode(event.target.value)} placeholder="A, RESP, FIELD..." required />
          </Field>
          <DictatedTextInput
            label="Section title"
            required
            value={newTitle}
            onChange={setNewTitle}
            placeholder="Section title"
            explainWhenUnavailable={false}
          />
          <div className="flex items-end">
            <button className="field-button" disabled={saving}>
              <Plus className="h-4 w-4" aria-hidden />
              Add section
            </button>
          </div>
        </form>

        <p className="text-xs text-ink-muted">
          Drag a question by its grip to reorder it within a section or drop it into another section. Keyboard users can use the
          up/down buttons on each tile, or the &ldquo;Move to&rdquo; menu to change section.
        </p>

        <div className="grid gap-4">
          {localSections.map((section) => (
            <div
              key={section.id}
              className={`rounded-md border bg-card p-3 transition ${dragSectionId === section.id ? "border-field-600 ring-2 ring-field-200" : "border-line-200"}`}
              onDragOver={(event) => {
                if (dragSectionId) event.preventDefault();
              }}
              onDrop={(event) => {
                if (!dragSectionId) return;
                event.preventDefault();
                performSectionReorder(dragSectionId, section.id);
                setDragSectionId(null);
              }}
            >
              <div className="flex items-center gap-2 font-display font-bold text-xl text-ink">
                <button
                  type="button"
                  className="grid h-9 w-9 cursor-grab place-items-center rounded-md border border-line-200 bg-field-50 text-ink-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-field-600 active:cursor-grabbing disabled:opacity-60"
                  draggable={!busy}
                  disabled={busy}
                  aria-label={`Drag section ${section.code}`}
                  onDragStart={(event) => {
                    setDragSectionId(section.id);
                    event.dataTransfer.effectAllowed = "move";
                    event.dataTransfer.setData("text/plain", section.id);
                  }}
                  onDragEnd={() => setDragSectionId(null)}
                >
                  <GripVertical className="h-4 w-4" aria-hidden />
                </button>
                <span>{section.sortOrder}. {section.code} - {section.title}</span>
              </div>

              <form
                className="mt-3 grid gap-3 md:grid-cols-[120px_1fr_auto_auto]"
                onSubmit={async (event) => {
                  event.preventDefault();
                  const formData = new FormData(event.currentTarget);
                  setMessage(null);
                  try {
                    await updateSection(section, formData);
                  } catch (err) {
                    setMessage(err instanceof Error ? err.message : "Unable to update section");
                  }
                }}
              >
                <Field label="Code">
                  <TextInput name="code" defaultValue={section.code} required />
                </Field>
                {/* `SeededSectionTitle` and not `DictatedTextInput` directly: this row is
                    uncontrolled — it lives inside a `.map` and its form reads `FormData` — while
                    `DictatedTextInput` is controlled-only on purpose. See the component above. */}
                <SeededSectionTitle key={`${section.id}-${section.title}`} initial={section.title} />
                <label className="flex items-end gap-2 pb-2 text-sm text-ink-muted">
                  <input name="isActive" type="checkbox" defaultChecked={section.isActive} />
                  Active
                </label>
                <div className="flex flex-wrap items-end gap-2">
                  <button className="field-button-secondary" type="submit">
                    <Save className="h-4 w-4" aria-hidden />
                    Save
                  </button>
                  <button type="button" className="text-sm font-semibold text-red-700" onClick={() => removeSection(section)}>
                    <Trash2 className="inline h-4 w-4" aria-hidden /> Remove
                  </button>
                </div>
              </form>

              <div
                className="mt-4 grid gap-1"
                onDragOver={(event) => {
                  if (dragActive && section.questions.length === 0) {
                    event.preventDefault();
                    setDropIndicator({ sectionId: section.id, index: 0 });
                  }
                }}
                onDrop={(event) => {
                  if (dragQuestion && section.questions.length === 0) {
                    event.preventDefault();
                    performQuestionMove(dragQuestion, section.id, 0);
                  }
                }}
              >
                {section.questions.map((question, index) => (
                  <div key={question.id}>
                    <DropLine active={dragActive && dropIndicator?.sectionId === section.id && dropIndicator.index === index} />
                    <QuestionTile
                      question={question}
                      index={index}
                      total={section.questions.length}
                      sections={localSections}
                      currentSectionId={section.id}
                      dragActive={dragActive}
                      dragging={dragQuestion?.questionId === question.id}
                      disabled={busy}
                      onDragStart={() => setDragQuestion({ questionId: question.id, sectionId: section.id })}
                      onDragEnd={() => {
                        setDragQuestion(null);
                        setDropIndicator(null);
                      }}
                      onHover={(after) => setDropIndicator({ sectionId: section.id, index: index + (after ? 1 : 0) })}
                      onDropTile={(after) => performQuestionMove(dragQuestion as DragQuestion, section.id, index + (after ? 1 : 0))}
                      onMoveUp={() => keyboardMove(section, question, -1)}
                      onMoveDown={() => keyboardMove(section, question, 1)}
                      onMoveToSection={(toSectionId) => moveQuestionToSection(question, section.id, toSectionId)}
                      onSave={(prompt, isActive) => saveQuestion(question, prompt, isActive)}
                      onRemove={() => removeQuestion(question)}
                    />
                  </div>
                ))}
                <DropLine active={dragActive && dropIndicator?.sectionId === section.id && dropIndicator.index === section.questions.length} />
                {section.questions.length === 0 ? (
                  <div
                    className={`rounded-md border border-dashed p-3 text-center text-xs font-semibold ${
                      // `border-line-200` and not the literal `#d7c7bc` it was until 2026-09-03: an
                      // arbitrary hex does not invert, so this drop target kept a warm light-mode
                      // rule under `data-theme="dark"` while every other dashed border on the page
                      // turned. Non-negotiable 2 — every neutral goes through the token ladder.
                      dragActive ? "border-field-600 bg-field-100 text-field-700" : "border-line-200 text-ink-muted"
                    }`}
                  >
                    {dragActive ? `Drop a question here to move it into ${section.code}` : "No questions yet — add one below."}
                  </div>
                ) : null}

                <form
                  // Themed, for the reason given on the drop target above — 2026-09-03.
                  className="mt-2 grid gap-3 rounded-md border border-dashed border-line-200 p-3 md:grid-cols-[1fr_auto]"
                  onSubmit={async (event) => {
                    event.preventDefault();
                    // Capture before the await: React nulls event.currentTarget afterwards.
                    const formElement = event.currentTarget;
                    setMessage(null);
                    try {
                      await addQuestion(section, new FormData(formElement));
                      formElement.reset();
                    } catch (err) {
                      setMessage(err instanceof Error ? err.message : "Unable to add question");
                    }
                  }}
                >
                  {/*
                    A QUESTION PROMPT IS PROSE SOMEBODY COMPOSES, so it takes a microphone like every
                    other prose box in this app. It is written at a desk more often than in a
                    courtyard, which is an argument for the mic mattering less here — not for its
                    absence: the sweep's rule is "wherever applicable", and this is applicable.

                    `DictatedTextArea` is uncontrolled and carries `name`, exactly like the
                    `<TextArea>` it replaces, so the surrounding form reads it through `FormData`
                    unchanged and `required` keeps working.
                  */}
                  <DictatedTextArea
                    name="prompt"
                    label={`New question in ${section.code}`}
                    required
                    explainWhenUnavailable={false}
                  />
                  <div className="flex items-end">
                    <button className="field-button-secondary">
                      <Plus className="h-4 w-4" aria-hidden />
                      Add question
                    </button>
                  </div>
                </form>
              </div>
            </div>
          ))}
        </div>
      </div>
    </MegaCard>
  );
}

/** Purple insertion line shown between question tiles while dragging. */
function DropLine({ active }: { active: boolean }) {
  return <div className={`h-0.5 rounded-full transition-colors ${active ? "bg-purple-700" : "bg-transparent"}`} aria-hidden />;
}

/**
 * A single draggable question tile: grip handle drags it, up/down buttons are the keyboard fallback for
 * within-section reordering, the "Move to" menu changes section, and Edit toggles inline prompt/active
 * editing.
 */
function QuestionTile({
  question,
  index,
  total,
  sections,
  currentSectionId,
  dragActive,
  dragging,
  disabled,
  onDragStart,
  onDragEnd,
  onHover,
  onDropTile,
  onMoveUp,
  onMoveDown,
  onMoveToSection,
  onSave,
  onRemove
}: {
  question: QuestionnaireQuestion;
  index: number;
  total: number;
  sections: QuestionnaireSection[];
  currentSectionId: string;
  dragActive: boolean;
  dragging: boolean;
  disabled: boolean;
  onDragStart: () => void;
  onDragEnd: () => void;
  onHover: (after: boolean) => void;
  onDropTile: (after: boolean) => void;
  onMoveUp: () => void;
  onMoveDown: () => void;
  onMoveToSection: (toSectionId: string) => void;
  onSave: (prompt: string, isActive: boolean) => Promise<boolean>;
  onRemove: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [prompt, setPrompt] = useState(question.prompt);
  const [isActive, setIsActive] = useState(question.isActive);
  const [savingTile, setSavingTile] = useState(false);

  useEffect(() => {
    setPrompt(question.prompt);
    setIsActive(question.isActive);
  }, [question.id, question.prompt, question.isActive]);

  function pointerAfter(event: React.DragEvent) {
    const rect = event.currentTarget.getBoundingClientRect();
    return event.clientY > rect.top + rect.height / 2;
  }

  const otherSections = sections.filter((section) => section.id !== currentSectionId);

  async function handleSave() {
    setSavingTile(true);
    const ok = await onSave(prompt, isActive);
    setSavingTile(false);
    if (ok) setEditing(false);
  }

  return (
    <div
      className={`flex items-start gap-2 rounded-md border border-line-200 bg-field-50 p-2.5 transition ${dragging ? "opacity-50" : ""}`}
      onDragOver={(event) => {
        if (!dragActive) return;
        event.preventDefault();
        onHover(pointerAfter(event));
      }}
      onDrop={(event) => {
        if (!dragActive) return;
        event.preventDefault();
        onDropTile(pointerAfter(event));
      }}
    >
      <button
        type="button"
        className="mt-0.5 grid h-8 w-8 shrink-0 cursor-grab place-items-center rounded-md border border-line-200 bg-card text-ink-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-field-600 active:cursor-grabbing disabled:opacity-60"
        draggable={!disabled}
        disabled={disabled}
        aria-label={`Drag question ${question.sortOrder}`}
        onDragStart={(event) => {
          onDragStart();
          event.dataTransfer.effectAllowed = "move";
          event.dataTransfer.setData("text/plain", question.id);
        }}
        onDragEnd={onDragEnd}
      >
        <GripVertical className="h-4 w-4" aria-hidden />
      </button>

      <div className="min-w-0 flex-1">
        {editing ? (
          <div className="grid gap-1">
            <TextArea value={prompt} onChange={(event) => setPrompt(event.target.value)} rows={2} />
            {/*
              A BUTTON BESIDE THE BOX RATHER THAN `DictatedTextArea` AROUND IT, and the reason is the
              shape of this one: it is CONTROLLED — the row holds `prompt` in its own state because a
              drag reorders these questions while one is being edited — and `DictatedTextArea` is
              uncontrolled by design. `ProcessForm`'s note rows are the same case and take the same
              shape: the shared button, appending through `appendDictatedPhrase`.
            */}
            <div className="justify-self-start">
              <OnDeviceDictationButton
                fieldLabel="the question"
                explainWhenUnavailable={false}
                onCommit={(phrase) => setPrompt((current) => appendDictatedPhrase(current, phrase))}
              />
            </div>
          </div>
        ) : (
          <p className="text-sm text-ink-900">
            <span className="mr-1 font-semibold text-ink-500">{question.sortOrder}.</span>
            {question.prompt}
            {!question.isActive ? <span className="ml-2 rounded bg-line-200 px-1.5 py-0.5 text-xs text-ink-500">inactive</span> : null}
          </p>
        )}

        <div className="mt-2 flex flex-wrap items-center gap-2">
          {editing ? (
            <>
              <label className="flex items-center gap-2 text-sm text-ink-muted">
                <input type="checkbox" checked={isActive} onChange={(event) => setIsActive(event.target.checked)} />
                Active
              </label>
              <button type="button" className="field-button-secondary" onClick={handleSave} disabled={savingTile || disabled}>
                <Save className="h-4 w-4" aria-hidden />
                {savingTile ? "Saving..." : "Save"}
              </button>
              <button
                type="button"
                className="text-sm font-semibold text-ink-muted"
                onClick={() => {
                  setPrompt(question.prompt);
                  setIsActive(question.isActive);
                  setEditing(false);
                }}
              >
                Cancel
              </button>
            </>
          ) : (
            <>
              <button type="button" className="field-button-secondary" onClick={() => setEditing(true)} disabled={disabled}>
                <Pencil className="h-4 w-4" aria-hidden />
                Edit
              </button>
              {otherSections.length ? (
                <label className="flex items-center gap-1.5 text-xs text-ink-muted">
                  <span>Move to</span>
                  <span className="w-32">
                    {/* `searchable`: sections are authored rows, so how many there are is a fact
                        about this questionnaire rather than about the control, and a questionnaire
                        that grows past eight sections would otherwise gain a filter box here on its
                        own. The anchor is narrow (`w-32`) and the panel matches it, which is enough
                        for a list of section codes. */}
                    <Select
                      value=""
                      searchable
                      onChange={(event) => onMoveToSection(event.target.value)}
                      aria-label={`Move question ${question.sortOrder} to another section`}
                    >
                      <option value="">Section...</option>
                      {otherSections.map((section) => (
                        <option key={section.id} value={section.id}>
                          {section.code}
                        </option>
                      ))}
                    </Select>
                  </span>
                </label>
              ) : null}
              <button type="button" className="text-sm font-semibold text-red-700" onClick={onRemove} disabled={disabled}>
                Remove
              </button>
            </>
          )}
        </div>
      </div>

      <div className="flex shrink-0 flex-col gap-1">
        <button
          type="button"
          className="grid h-7 w-7 place-items-center rounded-md border border-line-200 bg-card text-ink-muted transition hover:border-purple-300 hover:bg-purple-50 disabled:opacity-40"
          aria-label={`Move question ${question.sortOrder} up`}
          disabled={disabled || index === 0}
          onClick={onMoveUp}
        >
          <ArrowUp className="h-4 w-4" aria-hidden />
        </button>
        <button
          type="button"
          className="grid h-7 w-7 place-items-center rounded-md border border-line-200 bg-card text-ink-muted transition hover:border-purple-300 hover:bg-purple-50 disabled:opacity-40"
          aria-label={`Move question ${question.sortOrder} down`}
          disabled={disabled || index === total - 1}
          onClick={onMoveDown}
        >
          <ArrowDown className="h-4 w-4" aria-hidden />
        </button>
      </div>
    </div>
  );
}
