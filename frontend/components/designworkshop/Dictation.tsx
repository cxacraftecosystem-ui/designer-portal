"use client";

/**
 * Speak into a text field.
 *
 * A design workshop is a conversation. A designer standing at a loom with a phone in one hand and a
 * swatch in the other is not going to type four hundred words about the warp preparation, and the
 * fields that most need those four hundred words — the narrative ones the report actually prints —
 * are exactly the ones that come back empty from the field. So every TEXT and LONG_TEXT field gets a
 * microphone.
 *
 * FOUR THINGS THIS FILE IS CAREFUL ABOUT, each of which is a way a dictation button fails silently:
 *
 * 1. **Feature detection, not optimism.** Firefox implements no `SpeechRecognition` at all. Safari
 *    implements `webkitSpeechRecognition` but refuses it outside a user gesture on some versions.
 *    A button that appears and then does nothing when pressed is indistinguishable, to the person
 *    pressing it, from a broken microphone — and they will spend the afternoon blaming the handset.
 *    So the control is not rendered until something is known to work, and where the browser cannot
 *    do it the server is asked whether IT can (see {@link serverOffersRoute}).
 *
 * 2. **Interim results are drawn, and drawn differently.** The Web Speech API emits a running guess
 *    that it revises as the sentence lands ("the warp is" → "the wharf is" → "the warp is sized").
 *    Hiding it makes the button look dead for the first three seconds of every use. Showing it as
 *    ordinary text makes the designer stop and correct a word the recogniser was about to correct
 *    itself. So it is shown in italic, dimmed, with a live region — visibly provisional.
 *
 * 3. **The four failures are told apart.** Permission refused, no speech heard, the network
 *    (Chromium streams the audio to a Google endpoint, and a cluster behind a filtering proxy gets
 *    exactly this), and no microphone at all. Each has a different next move, and "dictation failed"
 *    tells a designer which of the four to try. There is no fifth catch-all sentence that means
 *    nothing.
 *
 * 4. **Committing appends, never replaces.** The recogniser is stopped and started many times across
 *    a long answer, and a commit that overwrote the box would delete the previous three sentences
 *    the moment somebody paused for breath.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Loader2, Mic, Square } from "lucide-react";

import { Dropdown } from "@/components/ui/Dropdown";
import { ApiError } from "@/lib/api";
import { DW_DICTATE_PATH, dictateAudio, dictationAnswerSentence, serverOffersRoute } from "@/lib/designWorkshops";
import { isLocalWorkshopId } from "@/lib/designWorkshopStore";
import { pickAudioRecorderMimeType, SPEECH_AUDIO_CONSTRAINTS } from "@/lib/media";

/* ────────────────────────────────────────────────────────────────────────────
 * The Web Speech API, typed
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `lib.dom` has no `SpeechRecognition` — it is not in any published standard, only in a W3C
 * community-group note — so the shape is declared here rather than reached for through `any`.
 *
 * Narrow on purpose: only the members this file touches. A wider transcription of the note would be
 * a second, unverifiable specification sitting in the repository, and the compiler cannot check any
 * of it against the browser that will actually run it.
 */
type SpeechAlternative = { transcript: string; confidence: number };
type SpeechResult = { isFinal: boolean; length: number; 0: SpeechAlternative };
type SpeechResultList = { length: number; [index: number]: SpeechResult };
type SpeechRecognitionEventLike = { resultIndex: number; results: SpeechResultList };
type SpeechRecognitionErrorLike = { error: string; message?: string };

type SpeechRecognitionLike = {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  maxAlternatives: number;
  start: () => void;
  stop: () => void;
  abort: () => void;
  onresult: ((event: SpeechRecognitionEventLike) => void) | null;
  onerror: ((event: SpeechRecognitionErrorLike) => void) | null;
  onend: (() => void) | null;
  onaudiostart: (() => void) | null;
};

type SpeechRecognitionConstructor = new () => SpeechRecognitionLike;

function speechRecognitionConstructor(): SpeechRecognitionConstructor | null {
  if (typeof window === "undefined") return null;
  const scope = window as unknown as {
    SpeechRecognition?: SpeechRecognitionConstructor;
    webkitSpeechRecognition?: SpeechRecognitionConstructor;
  };
  // The unprefixed name first: Chromium has been shipping it alongside the prefix, and a build that
  // eventually drops the prefix must not lose dictation on the day it does.
  return scope.SpeechRecognition ?? scope.webkitSpeechRecognition ?? null;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Languages
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The languages a workshop is actually held in.
 *
 * NOT the recogniser's whole list, which runs to a hundred-odd locales and would make the picker a
 * scrolling exercise on a phone. These eleven are the languages this repository's clusters work in,
 * and the tags are the `BCP 47` forms the Web Speech API wants — `hi-IN` and not `hi`, because the
 * bare subtag falls back to a generic model that mangles Indian place names.
 *
 * English is first and is the default because it is what a designer's field notes are usually in
 * even where the interview is not. The rest are in the order a speaker of each would expect to find
 * their own: by speaker population, which is the only ordering that is not somebody's ranking.
 */
export const DICTATION_LANGUAGES: Array<{ value: string; label: string }> = [
  { value: "en-IN", label: "English (India)" },
  { value: "hi-IN", label: "हिन्दी — Hindi" },
  { value: "bn-IN", label: "বাংলা — Bengali" },
  { value: "mr-IN", label: "मराठी — Marathi" },
  { value: "te-IN", label: "తెలుగు — Telugu" },
  { value: "ta-IN", label: "தமிழ் — Tamil" },
  { value: "gu-IN", label: "ગુજરાતી — Gujarati" },
  { value: "kn-IN", label: "ಕನ್ನಡ — Kannada" },
  { value: "ml-IN", label: "മലയാളം — Malayalam" },
  { value: "or-IN", label: "ଓଡ଼ିଆ — Odia" },
  { value: "pa-IN", label: "ਪੰਜਾਬੀ — Punjabi" }
];

/**
 * The chosen language, remembered.
 *
 * A workshop is one language for a week. Making a designer re-pick Odia on every field of every
 * stage is the kind of friction that ends with everything dictated in English-India and the Odia
 * words transliterated wrong.
 *
 * `localStorage` THROWS rather than returning null when storage is blocked (Safari's private mode,
 * a locked-down kiosk profile), so both halves are wrapped — an exception here would take the whole
 * field down over a preference.
 */
const LANGUAGE_STORAGE_KEY = "field_repo_dictation_language";

function readStoredLanguage(): string {
  try {
    const stored = window.localStorage.getItem(LANGUAGE_STORAGE_KEY);
    return DICTATION_LANGUAGES.some((entry) => entry.value === stored) ? (stored as string) : "en-IN";
  } catch {
    return "en-IN";
  }
}

function storeLanguage(value: string): void {
  try {
    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, value);
  } catch {
    /* A preference that cannot be saved is still a preference that works for this session. */
  }
}

/* ────────────────────────────────────────────────────────────────────────────
 * Failure wording
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One sentence per way this goes wrong, naming the NEXT MOVE rather than the error code.
 *
 * `not-allowed` and `service-not-allowed` are genuinely different — the first is the site being
 * refused the microphone, the second is the speech service being refused by policy — but the person
 * standing there does the same thing about both, so they share a sentence that covers both doors.
 */
function describeSpeechError(code: string): string {
  switch (code) {
    case "not-allowed":
    case "service-not-allowed":
      return "The browser refused access to the microphone. Allow it for this site in the address-bar permissions, then press the microphone again — or type the answer in.";
    case "no-speech":
      return "Nothing was heard. Hold the handset closer, or check that the right microphone is selected, and try again.";
    case "audio-capture":
      return "No microphone was found on this device. Plug one in, or type the answer in.";
    case "network":
      return "This browser sends dictation to a speech service over the internet and could not reach it. Dictation needs a connection even though the rest of this form does not.";
    case "aborted":
      return "";
    default:
      return `Dictation stopped unexpectedly (${code}). Press the microphone to try again, or type the answer in.`;
  }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The control
 * ──────────────────────────────────────────────────────────────────────────── */

type Mode = "browser" | "server" | "none" | "unknown";

export function DictationButton({
  /** Called with each finished phrase. The caller appends — see rule 4 in the file header. */
  onCommit,
  disabled,
  /** Names the field in the button's accessible label, so a screen reader hears which box. */
  fieldLabel,
  /**
   * The workshop whose recorded consent governs a clip sent from this button.
   *
   * REQUIRED, and threaded from every call site rather than defaulted, because the alternative is a
   * button that silently posts to the un-gated route. `POST /design-workshops/dictate` takes no
   * workshop id and therefore consults no `dictationConsent` column: it hands the clip to
   * ElevenLabs/Deepgram/Whisper exactly as it did before consent existed. A default here would make
   * "the call site that forgot" indistinguishable from "the call site that meant it", on the one
   * control whose whole subject is a named artisan's voice leaving the device.
   */
  workshopId
}: {
  onCommit: (text: string) => void;
  disabled?: boolean;
  fieldLabel: string;
  workshopId: string;
}) {
  const [mode, setMode] = useState<Mode>("unknown");
  const [language, setLanguage] = useState("en-IN");
  const [listening, setListening] = useState(false);
  const [interim, setInterim] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  /** Set while the server fallback is transcribing a finished recording. */
  const [transcribing, setTranscribing] = useState(false);
  const [showLanguages, setShowLanguages] = useState(false);

  const recognitionRef = useRef<SpeechRecognitionLike | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  /**
   * The commit callback, read through a ref by the recogniser's own handlers.
   *
   * Those handlers are installed once, when `start` runs, and close over whatever existed then. The
   * caller's `onCommit` closes over the row being edited and changes identity on every keystroke in
   * a sibling field, so without this a phrase spoken thirty seconds into a session would be appended
   * to the value the box held when the microphone was pressed — silently discarding everything typed
   * in between.
   */
  const commitRef = useRef(onCommit);
  useEffect(() => {
    commitRef.current = onCommit;
  }, [onCommit]);

  const languageRef = useRef(language);
  useEffect(() => {
    languageRef.current = language;
  }, [language]);

  // What this browser and this server can actually do, decided once.
  useEffect(() => {
    let cancelled = false;
    setLanguage(readStoredLanguage());
    if (speechRecognitionConstructor()) {
      setMode("browser");
      return;
    }
    /*
      A WORKSHOP THAT EXISTS ONLY ON THIS LAPTOP HAS NO SERVER ROUTE TO OFFER, AND THE CLIP MUST NOT
      BE RECORDED AT ALL.

      The server rung posts to `/design-workshops/{id}/dictate`, and for an unsynced workshop the id
      in the URL is the LOCAL draft id (`dwlocal-…`). `load_workshop_or_404` finds no row and answers
      404 "Record not found" — **after** the designer has spoken forty words about the dye baths,
      after the whole clip has been uploaded over mobile data, and with a sentence that names no next
      move because it was written about a missing record rather than about an unsent workshop. The
      passage is gone.

      IT MUST BE DECIDED HERE, IN `mode`, RATHER THAN CAUGHT AT THE UPLOAD. Deciding late means the
      microphone opens, the recorder runs, and the failure arrives at the one moment nothing can be
      recovered — which is exactly the shape of defect this control's own header calls "a button that
      appears and then does nothing when pressed". Deciding here means the button is simply not
      drawn, for the same reason it is not drawn on a browser with no recogniser at all.

      THE HANDSET ALREADY DOES THIS, and this is the browser catching up rather than a new idea:
      `DwDictationLadder.kt` ANDs `workshopOnServer` into its server rung and withholds it with ZERO
      bytes uploaded, naming the button that fixes it. The asymmetry was introduced when the web was
      moved onto the consent-gated route — the required `workshopId` closed the ungated door and
      opened this one, because a local id is a perfectly good string.

      AND IT MUST NOT FALL BACK TO `DW_DICTATE_PATH`. That is the id-less route, which consults no
      workshop's consent at all; reaching for it here would send an artisan's voice to a third-party
      provider precisely when nobody has been able to ask about consent.
    */
    if (isLocalWorkshopId(workshopId)) {
      setMode("none");
      return;
    }
    // No browser recogniser. Rather than hiding the feature outright, ask whether this deployment
    // offers the server route — the fallback exists precisely for Firefox, which is a normal
    // browser for a designer to be using and not an edge case worth writing off.
    //
    // The PROBE still asks the id-less path, deliberately: it is a question about the DEPLOYMENT
    // ("is a transcription service configured here at all"), and the per-workshop URL cannot answer
    // it — a 404 there would mean "this workshop is not on the server", which is a different fact.
    serverOffersRoute(DW_DICTATE_PATH)
      .then((offered) => {
        if (!cancelled) setMode(offered && typeof MediaRecorder !== "undefined" ? "server" : "none");
      })
      .catch(() => {
        if (!cancelled) setMode("none");
      });
    return () => {
      cancelled = true;
    };
    // `workshopId` IS IN THE DEPENDENCIES and the empty array it replaces was part of the defect:
    // this screen keeps its component across the sync that gives a workshop its server id, so a mode
    // decided once at mount would go on refusing a workshop that has since been sent up.
  }, [workshopId]);

  const stopEverything = useCallback(() => {
    recognitionRef.current?.abort();
    recognitionRef.current = null;
    if (recorderRef.current && recorderRef.current.state !== "inactive") recorderRef.current.stop();
    recorderRef.current = null;
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
  }, []);

  // An unmount mid-sentence must release the microphone. A browser tab that keeps the recording
  // indicator lit after the form has gone is the single most alarming thing this feature can do.
  useEffect(() => stopEverything, [stopEverything]);

  function startBrowserRecognition() {
    const Recognition = speechRecognitionConstructor();
    if (!Recognition) return;
    const recognition = new Recognition();
    recognition.lang = languageRef.current;
    // `continuous` keeps the session open across the pauses in a spoken paragraph; without it the
    // recogniser stops after the first sentence and a designer describing a five-step process has to
    // press the button five times.
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.maxAlternatives = 1;

    recognition.onresult = (event) => {
      let pending = "";
      for (let index = event.resultIndex; index < event.results.length; index += 1) {
        const result = event.results[index];
        const text = result[0]?.transcript ?? "";
        if (result.isFinal) {
          const finished = text.trim();
          if (finished) commitRef.current(finished);
        } else {
          pending += text;
        }
      }
      setInterim(pending.trim());
    };

    recognition.onerror = (event) => {
      const sentence = describeSpeechError(event.error);
      // "aborted" is what the API reports when the page stopped it deliberately, which is every
      // normal end of a dictation. Narrating it back as a failure would train designers to ignore
      // the one line that matters when something is genuinely wrong.
      if (sentence) setProblem(sentence);
      setListening(false);
      setInterim("");
    };

    recognition.onend = () => {
      setListening(false);
      setInterim("");
      recognitionRef.current = null;
    };

    recognitionRef.current = recognition;
    setProblem(null);
    setInterim("");
    setListening(true);
    try {
      recognition.start();
    } catch {
      // Safari throws InvalidStateError when start() is called on an instance that is already
      // running — which happens on a double tap, and must not leave the button stuck reading "Stop".
      setListening(false);
      recognitionRef.current = null;
    }
  }

  async function startServerRecording() {
    setProblem(null);
    setInterim("");
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: SPEECH_AUDIO_CONSTRAINTS });
      streamRef.current = stream;
      chunksRef.current = [];
      // Never a hardcoded "audio/webm": Safari and iOS produce audio/mp4, and a wrong container name
      // makes the server's decoder reject bytes that were perfectly good.
      const preferred = pickAudioRecorderMimeType();
      const recorder = new MediaRecorder(stream, preferred ? { mimeType: preferred } : undefined);
      recorderRef.current = recorder;
      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) chunksRef.current.push(event.data);
      };
      recorder.onstop = async () => {
        stream.getTracks().forEach((track) => track.stop());
        streamRef.current = null;
        const type = recorder.mimeType || preferred || "audio/webm";
        const blob = new Blob(chunksRef.current, { type });
        chunksRef.current = [];
        if (!blob.size) {
          setProblem("Nothing was recorded. Hold the handset closer and try again.");
          return;
        }
        setTranscribing(true);
        try {
          const result = await dictateAudio(blob, languageRef.current, workshopId);
          const text = (result.text ?? "").trim();
          if (text) commitRef.current(text);
          // NOT `result.message`, which is what this line used to be. The provider chain composes
          // "Transcription rate-limited (HTTP 429); will retry automatically." — true of the
          // transcription QUEUE and false of this endpoint, which is synchronous and stores
          // nothing. Passing it through promised a designer a transcript that was never coming, for
          // a recording that was already gone. `dictationAnswerSentence` owns all three answers, and
          // Android has had the same function since its ladder was built.
          else setProblem(dictationAnswerSentence(result));
        } catch (error) {
          /*
            THE SERVER'S OWN SENTENCE WINS, and for the consent and cap refusals it is the only thing
            that can be right.

            A 409 from the per-workshop route means the artisan's consent is NOT_RECORDED or REFUSED,
            and a 429 means this designer's daily allowance for the transcription service is spent.
            Both arrive as a FastAPI `detail` that already names the next move — and for consent that
            move is a person deciding, never a retry, which is precisely what the old wording
            ("The server could not transcribe that recording: …") got wrong: it framed a deliberate
            refusal as a transcription failure and invited another tap that cannot succeed.

            `ApiError.message` is `describeApiDetail`'s output, so a FastAPI detail arrives as a real
            sentence rather than "[object Object]". Prefixing it would produce two sentences arguing
            with each other, so where the server spoke, only the server speaks.
          */
          const detail = error instanceof ApiError ? error.message.trim() : "";
          setProblem(
            detail ||
              (error instanceof Error
                ? `The server could not transcribe that recording: ${error.message}`
                : "The server could not transcribe that recording.")
          );
        } finally {
          setTranscribing(false);
        }
      };
      recorder.start();
      setListening(true);
    } catch {
      // getUserMedia rejects for a refused permission and for a device that has no microphone, and
      // the browser does not reliably distinguish the two — so the sentence covers both doors rather
      // than asserting which one is shut.
      setProblem(
        "The microphone could not be opened. Allow microphone access for this site, check that a microphone is connected, then try again."
      );
      setListening(false);
    }
  }

  function stop() {
    if (mode === "browser") {
      // `stop()` and not `abort()`: stop lets the recogniser deliver the phrase it is holding, abort
      // throws it away — which loses the last sentence of every dictation.
      recognitionRef.current?.stop();
      setListening(false);
      setInterim("");
      return;
    }
    if (recorderRef.current && recorderRef.current.state !== "inactive") recorderRef.current.stop();
    recorderRef.current = null;
    setListening(false);
  }

  const languageOptions = useMemo(() => DICTATION_LANGUAGES.map(({ value, label }) => ({ value, label })), []);
  const languageName = DICTATION_LANGUAGES.find((entry) => entry.value === language)?.label ?? language;

  // Rule 1 in the file header: nothing is drawn until something is known to work.
  if (mode === "unknown" || mode === "none") return null;

  return (
    <div className="grid gap-1.5">
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          className={
            listening
              ? "inline-flex min-h-8 items-center justify-center gap-1.5 rounded-md bg-purple-700 px-2.5 py-1 text-xs font-medium text-white transition"
              : "inline-flex min-h-8 items-center justify-center gap-1.5 rounded-md border border-line-200 bg-card px-2.5 py-1 text-xs font-medium text-ink-900 transition hover:border-purple-300 hover:bg-purple-50 disabled:opacity-60"
          }
          disabled={disabled || transcribing}
          aria-pressed={listening}
          aria-label={listening ? `Stop dictating ${fieldLabel}` : `Dictate ${fieldLabel} in ${languageName}`}
          onClick={() => {
            if (listening) {
              stop();
              return;
            }
            if (mode === "browser") startBrowserRecognition();
            else void startServerRecording();
          }}
        >
          {transcribing ? (
            <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden />
          ) : listening ? (
            <Square className="h-3.5 w-3.5" aria-hidden />
          ) : (
            <Mic className="h-3.5 w-3.5" aria-hidden />
          )}
          {transcribing ? "Transcribing…" : listening ? "Stop" : "Dictate"}
        </button>

        <button
          type="button"
          className="text-xs font-medium text-ink-500 underline"
          aria-expanded={showLanguages}
          disabled={disabled || listening}
          onClick={() => setShowLanguages((current) => !current)}
        >
          {languageName}
        </button>

        {mode === "server" ? (
          // Said rather than hidden. On this path there is no live readout at all, and a designer who
          // has used dictation in Chrome will otherwise stand there waiting for words that only
          // arrive when they press Stop.
          <span className="text-xs text-ink-500">
            This browser has no built-in dictation, so the recording is transcribed after you press Stop.
          </span>
        ) : null}
      </div>

      {showLanguages ? (
        <div className="max-w-xs">
          <Dropdown
            value={language}
            onChange={(next) => {
              setLanguage(next);
              storeLanguage(next);
              setShowLanguages(false);
            }}
            options={languageOptions}
            placeholder="Dictation language"
            ariaLabel="Dictation language"
            disabled={disabled || listening}
          />
        </div>
      ) : null}

      {listening || interim ? (
        // Rule 2: provisional text has to LOOK provisional. Italic, dimmed, and inside a polite live
        // region so a screen-reader user hears it accumulate rather than being told at the end that
        // a paragraph appeared from nowhere.
        <p
          aria-live="polite"
          className="min-h-5 rounded-md border border-dashed border-purple-300 bg-purple-50 px-2 py-1 text-xs italic leading-5 text-purple-800"
        >
          {interim || "Listening… speak now."}
        </p>
      ) : null}

      {problem ? <p className="text-xs font-medium leading-5 text-error-600">{problem}</p> : null}
    </div>
  );
}
