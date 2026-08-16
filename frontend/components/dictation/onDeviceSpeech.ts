/**
 * The browser's own speech recogniser, and nothing else.
 *
 * WHY THIS FILE EXISTS AT ALL. `components/designworkshop/Dictation.tsx` had all of this inside it:
 * the typed `SpeechRecognition` shape, the language list, the remembered preference and the fifty
 * lines that wire a recogniser's four callbacks up correctly. That was fine while dictation existed
 * on exactly one screen. It stopped being fine the moment the artisan, product, tool and process
 * forms wanted a microphone too, because those forms must NOT reach into a module named after
 * design workshops — and, far more importantly, must not reach the server rung that lives in it.
 *
 * SO THE SPLIT IS BY TRANSPORT POLICY, NOT BY WIDGET. There are two dictation components in this
 * repository and there are deliberately two:
 *
 *   - `DictationButton` (components/designworkshop/Dictation.tsx) — browser recogniser, and where
 *     the browser has none, a clip posted to `POST /design-workshops/{id}/dictate`, which is gated
 *     on that workshop's recorded `dictationConsent`. It keeps its required `workshopId`.
 *   - `OnDeviceDictationButton` (./OnDeviceDictationButton.tsx) — browser recogniser ONLY. It has
 *     no fetch, no MediaRecorder, no upload and no id of any kind, because a record form has no
 *     workshop whose consent could govern an artisan's voice leaving the device. Nothing it hears
 *     ever leaves the handset.
 *
 * The alternative was one component with a `serverRung={false}` flag. It was rejected: a flag makes
 * "this button may upload a voice recording to a third-party transcription provider" a runtime
 * property of a prop somebody can forget, on the one control in this codebase whose whole subject is
 * consent. Two components make it a property of the IMPORT, which is visible in review and cannot be
 * lost in a spread. The cost of two components is a second copy of the recogniser lifecycle — and
 * that cost is what this file removes: the lifecycle is written ONCE, here, and both components call
 * it. If you are tempted to merge the two components back together, you would be trading a
 * compile-time guarantee for a runtime one to save the ~80 lines of chrome that differ.
 *
 * DO NOT ADD A NETWORK CALL TO THIS FILE. The id-less `POST /design-workshops/dictate` is 410 GONE
 * on purpose: it consulted no consent column and handed clips to ElevenLabs/Deepgram/Whisper
 * regardless. Any upload from a record form would rebuild that door under a new name.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The Web Speech API, typed
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `lib.dom` has no `SpeechRecognition` — it is not in any published standard, only in a W3C
 * community-group note — so the shape is declared here rather than reached for through `any`.
 *
 * Narrow on purpose: only the members this repository touches. A wider transcription of the note
 * would be a second, unverifiable specification sitting in the tree, and the compiler cannot check
 * any of it against the browser that will actually run it.
 */
export type SpeechAlternative = { transcript: string; confidence: number };
export type SpeechResult = { isFinal: boolean; length: number; 0: SpeechAlternative };
export type SpeechResultList = { length: number; [index: number]: SpeechResult };
export type SpeechRecognitionEventLike = { resultIndex: number; results: SpeechResultList };
export type SpeechRecognitionErrorLike = { error: string; message?: string };

export type SpeechRecognitionLike = {
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

export type SpeechRecognitionConstructor = new () => SpeechRecognitionLike;

export function speechRecognitionConstructor(): SpeechRecognitionConstructor | null {
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
 * The languages this repository's fieldwork is actually conducted in.
 *
 * NOT the recogniser's whole list, which runs to a hundred-odd locales and would make the picker a
 * scrolling exercise on a phone. These eleven are the languages this repository's clusters work in,
 * and the tags are the `BCP 47` forms the Web Speech API wants — `hi-IN` and not `hi`, because the
 * bare subtag falls back to a generic model that mangles Indian place names.
 *
 * English is first and is the default because it is what a designer's field notes are usually in
 * even where the interview is not. The rest are in the order a speaker of each would expect to find
 * their own: by speaker population, which is the only ordering that is not somebody's ranking.
 *
 * MOVED HERE FROM `Dictation.tsx`, deliberately. An artisan form importing its language list from a
 * module named after design workshops is an import path that lies about scope, and the list is the
 * same list — the researcher speaks Odia on the artisan form for the same reason they speak it on
 * the stage form.
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
 * The chosen language, remembered — and remembered under the SAME KEY as before.
 *
 * A workshop is one language for a week, and the artisan sitting in front of the researcher on the
 * record form is the same artisan who was on the stage form ten minutes ago. Making them re-pick
 * Odia per screen is the kind of friction that ends with everything dictated in English-India and
 * the Odia words transliterated wrong. One key, both surfaces.
 *
 * `localStorage` THROWS rather than returning null when storage is blocked (Safari's private mode,
 * a locked-down kiosk profile), so both halves are wrapped — an exception here would take the whole
 * field down over a preference.
 */
export const DICTATION_LANGUAGE_STORAGE_KEY = "field_repo_dictation_language";

export function readStoredLanguage(): string {
  try {
    const stored = window.localStorage.getItem(DICTATION_LANGUAGE_STORAGE_KEY);
    return DICTATION_LANGUAGES.some((entry) => entry.value === stored) ? (stored as string) : "en-IN";
  } catch {
    return "en-IN";
  }
}

export function storeLanguage(value: string): void {
  try {
    window.localStorage.setItem(DICTATION_LANGUAGE_STORAGE_KEY, value);
  } catch {
    /* A preference that cannot be saved is still a preference that works for this session. */
  }
}

export function dictationLanguageLabel(value: string): string {
  return DICTATION_LANGUAGES.find((entry) => entry.value === value)?.label ?? value;
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
 *
 * `aborted` returns the empty string, and callers MUST treat that as "say nothing". It is what the
 * API reports when the page stopped the recogniser deliberately, which is the end of every normal
 * dictation; narrating it back as a failure would train researchers to ignore the one line that
 * matters when something is genuinely wrong.
 */
export function describeSpeechError(code: string): string {
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

/**
 * The sentence shown where the browser has no recogniser AND no server rung is on offer.
 *
 * SAID, NOT HIDDEN, and that is the whole point of exporting it. On the stage form a missing
 * recogniser falls through to the server, so the control can simply not appear. On a record form
 * there is no server rung by design, and a microphone that is present in Chrome and absent in
 * Firefox with no explanation reads as a broken build — the researcher retries, reloads, and files a
 * bug. One sentence turns "broken" into "not here, and here is what does work".
 */
export const NO_RECOGNISER_SENTENCE =
  "Dictation is not available in this browser. Chrome, Edge and Safari have built-in speech recognition; Firefox does not. Type the answer in, or open this page in one of those.";

/* ────────────────────────────────────────────────────────────────────────────
 * The lifecycle — written once, used by both dictation components
 * ──────────────────────────────────────────────────────────────────────────── */

export type BrowserRecognitionHooks = {
  /** BCP 47, from {@link DICTATION_LANGUAGES}. Read once, at construction — see `start`. */
  language: string;
  /** A finished phrase. Callers APPEND it; see the note on `onStopped` for why never replace. */
  onPhrase: (text: string) => void;
  /** The running guess, revised as the sentence lands. Empty string clears it. */
  onInterim: (text: string) => void;
  /** A sentence from {@link describeSpeechError}. Never called with the empty string. */
  onProblem: (sentence: string) => void;
  /**
   * The recogniser has stopped listening.
   *
   * `reason` is "error" when it stopped because something went wrong and "end" when the session
   * closed normally. They are told apart because the two callers dispose of their handle at
   * different moments: `onend` reliably follows `onerror` in every implementation this repository
   * has met, so releasing the reference on "error" as well would drop it twice.
   */
  onStopped: (reason: "error" | "end") => void;
};

/**
 * A configured recogniser, or null where the browser has none.
 *
 * EVERY LINE HERE IS A DEFECT THAT WAS FIXED ONCE ALREADY, which is why it is shared rather than
 * retyped per component:
 *
 *  - `continuous` keeps the session open across the pauses in a spoken paragraph. Without it the
 *    recogniser stops after the first sentence and a researcher describing a five-step process has
 *    to press the button five times.
 *  - `interimResults` is what makes the button look alive in the first three seconds. Hiding the
 *    running guess makes a working microphone look dead.
 *  - `resultIndex` is the start of the NEW results, not zero. Iterating from zero re-commits every
 *    phrase already spoken, so a paragraph comes out with each sentence repeated n times.
 *  - Only `isFinal` results are committed. Committing an interim writes the recogniser's first guess
 *    ("the wharf is") and then its correction ("the warp is sized") as two separate phrases.
 *
 * The caller keeps the returned object and calls `stop()` on it — see {@link stopRecognition}.
 */
export function createBrowserRecognition(hooks: BrowserRecognitionHooks): SpeechRecognitionLike | null {
  const Recognition = speechRecognitionConstructor();
  if (!Recognition) return null;
  const recognition = new Recognition();
  recognition.lang = hooks.language;
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
        if (finished) hooks.onPhrase(finished);
      } else {
        pending += text;
      }
    }
    hooks.onInterim(pending.trim());
  };

  recognition.onerror = (event) => {
    const sentence = describeSpeechError(event.error);
    if (sentence) hooks.onProblem(sentence);
    hooks.onInterim("");
    hooks.onStopped("error");
  };

  recognition.onend = () => {
    hooks.onInterim("");
    hooks.onStopped("end");
  };

  return recognition;
}

/**
 * Start it, and say whether it started.
 *
 * Safari throws `InvalidStateError` when `start()` is called on an instance that is already running,
 * which happens on a double tap. Unguarded that leaves the button stuck reading "Stop" with nothing
 * listening behind it — a dead control that claims to be live, which is the exact failure mode this
 * whole feature is written around. `false` means the caller must put the button back.
 */
export function startRecognition(recognition: SpeechRecognitionLike): boolean {
  try {
    recognition.start();
    return true;
  } catch {
    return false;
  }
}

/**
 * `stop()` and NOT `abort()`.
 *
 * Stop lets the recogniser deliver the phrase it is still holding; abort throws it away, which loses
 * the last sentence of every dictation. `abort()` is correct in exactly one place — tearing down on
 * unmount, where there is no longer a field for the phrase to land in.
 */
export function stopRecognition(recognition: SpeechRecognitionLike | null): void {
  recognition?.stop();
}
