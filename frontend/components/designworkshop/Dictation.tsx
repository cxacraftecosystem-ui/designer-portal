"use client";

import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from "react";
import { Check, ChevronDown, Loader2, Mic, Square } from "lucide-react";

import {
  createBrowserRecognition,
  DICTATION_LANGUAGES,
  readStoredLanguage,
  speechRecognitionConstructor,
  startRecognition,
  storeLanguage,
  type SpeechRecognitionLike
} from "@/components/dictation/onDeviceSpeech";
import {
  adornmentPosition,
  DICTATE_BUTTON_BASE,
  DICTATE_BUTTON_IDLE,
  DICTATE_BUTTON_RECORDING,
  dictateButtonDescription,
  dictateButtonLabel,
  DICTATION_BESIDE_ROW,
  DICTATION_FIELD_PADDED,
  DICTATION_FIELD_WRAPPER,
  DICTATION_LANGUAGE_BUTTON,
  dictationLanguageButtonLabel
} from "@/components/dictation/dictationAdornment";
import { DownloadMediaButton } from "@/components/media/DownloadMediaButton";
import { TranscriptViewer } from "@/components/transcript/TranscriptViewer";
import { AnchoredPopover } from "@/components/ui/AnchoredPopover";
import { ApiError } from "@/lib/api";
import {
  DW_DICTATE_PATH,
  dictateAudio,
  dictationAnswerSentence,
  dwDictationAllowance,
  serverOffersRoute,
  type DwDictationAllowance
} from "@/lib/designWorkshops";
import { isLocalWorkshopId } from "@/lib/designWorkshopStore";
import { downloadLocalMedia } from "@/lib/localMediaDownload";
import { pickAudioRecorderMimeType, SPEECH_AUDIO_CONSTRAINTS } from "@/lib/media";

export { DICTATION_LANGUAGES };

type Mode = "browser" | "server" | "none" | "unknown";

export function DictationButton({
  onCommit,
  disabled,
  fieldLabel,
  workshopId,
  children,
  multiline = false,
  beside = false
}: {
  onCommit: (text: string) => void;
  disabled?: boolean;
  fieldLabel: string;
  workshopId: string;
  children?: ReactNode;
  multiline?: boolean;
  beside?: boolean;
}) {
  const describedById = useId();
  const menuAnchorRef = useRef<HTMLDivElement | null>(null);
  const caretRef = useRef<HTMLButtonElement | null>(null);
  const [mode, setMode] = useState<Mode>("unknown");
  const [language, setLanguage] = useState("en-IN");
  const [listening, setListening] = useState(false);
  const [interim, setInterim] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [transcribing, setTranscribing] = useState(false);
  const [showLanguages, setShowLanguages] = useState(false);
  const [allowance, setAllowance] = useState<DwDictationAllowance | null>(null);
  const [lastMediaId, setLastMediaId] = useState<string | null>(null);

  const recognitionRef = useRef<SpeechRecognitionLike | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const [lastRecording, setLastRecording] = useState<{ blob: Blob; at: number } | null>(null);
  const commitRef = useRef(onCommit);
  useEffect(() => {
    commitRef.current = onCommit;
  }, [onCommit]);

  const languageRef = useRef(language);
  useEffect(() => {
    languageRef.current = language;
  }, [language]);

  useEffect(() => {
    let cancelled = false;
    setLanguage(readStoredLanguage());
    if (speechRecognitionConstructor()) {
      setMode("browser");
      return;
    }
    if (isLocalWorkshopId(workshopId)) {
      setMode("none");
      return;
    }
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
  }, [workshopId]);

  useEffect(() => {
    if (mode !== "server") return;
    let cancelled = false;
    void dwDictationAllowance().then((answer) => {
      if (!cancelled) setAllowance(answer);
    });
    return () => {
      cancelled = true;
    };
  }, [mode]);

  const stopEverything = useCallback(() => {
    recognitionRef.current?.abort();
    recognitionRef.current = null;
    if (recorderRef.current && recorderRef.current.state !== "inactive") recorderRef.current.stop();
    recorderRef.current = null;
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
  }, []);

  useEffect(() => stopEverything, [stopEverything]);

  function startBrowserRecognition() {
    const recognition = createBrowserRecognition({
      language: languageRef.current,
      onPhrase: (text) => commitRef.current(text),
      onInterim: setInterim,
      onProblem: setProblem,
      onStopped: (reason) => {
        setListening(false);
        if (reason === "end") recognitionRef.current = null;
      }
    });
    if (!recognition) return;

    recognitionRef.current = recognition;
    setProblem(null);
    setInterim("");
    setListening(true);
    if (!startRecognition(recognition)) {
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
        setLastRecording({ blob, at: Date.now() });
        setTranscribing(true);
        try {
          const result = await dictateAudio(blob, languageRef.current, workshopId);
          if (typeof result.dictationDay === "string") {
            setAllowance({
              dictationsLimit: result.dictationsLimit ?? null,
              dictationsUsed: result.dictationsUsed ?? 0,
              dictationsRemaining: result.dictationsRemaining ?? null,
              dictationDay: result.dictationDay
            });
          }
          setLastMediaId(result.mediaId ?? null);
          const text = (result.text ?? "").trim();
          if (text) commitRef.current(text);
          else setProblem(dictationAnswerSentence(result));
        } catch (error) {
          const detail = error instanceof ApiError ? error.message.trim() : "";
          setProblem(
            detail ||
              (error instanceof Error
                ? `That recording couldn't be transcribed: ${error.message}`
                : "That recording couldn't be transcribed. Try again.")
          );
        } finally {
          setTranscribing(false);
          setLastRecording(null);
        }
      };
      recorder.start();
      setListening(true);
    } catch {
      setProblem(
        "Couldn't open the microphone. Allow access for this site and try again."
      );
      setListening(false);
    }
  }

  function stop() {
    if (mode === "browser") {
      recognitionRef.current?.stop();
      setListening(false);
      setInterim("");
      return;
    }
    if (recorderRef.current && recorderRef.current.state !== "inactive") recorderRef.current.stop();
    recorderRef.current = null;
    setListening(false);
  }

  const languageName = DICTATION_LANGUAGES.find((entry) => entry.value === language)?.label ?? language;
  const ready = mode === "browser" || mode === "server";

  function chooseLanguage(next: string) {
    setLanguage(next);
    storeLanguage(next);
    setShowLanguages(false);
    caretRef.current?.focus();
  }

  if (!ready && !children) return null;

  const adornment = ready ? (
    <div ref={menuAnchorRef} className="flex items-center gap-0.5" data-dictation-adornment="">
      <button
        type="button"
        className={`${DICTATE_BUTTON_BASE} ${listening ? DICTATE_BUTTON_RECORDING : DICTATE_BUTTON_IDLE}`}
        disabled={disabled || transcribing}
        aria-pressed={listening}
        aria-label={dictateButtonLabel(listening, transcribing)}
        aria-describedby={describedById}
        data-dictate-button=""
        onContextMenu={(event) => {
          if (listening || disabled) return;
          event.preventDefault();
          setShowLanguages(true);
        }}
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
          <Loader2 className="h-5 w-5 animate-spin" aria-hidden />
        ) : listening ? (
          <Square className="h-4 w-4 fill-current" aria-hidden />
        ) : (
          <Mic className="h-5 w-5" aria-hidden />
        )}
      </button>
      <span id={describedById} className="sr-only">
        {dictateButtonDescription(fieldLabel, languageName)}
      </span>
      <button
        ref={caretRef}
        type="button"
        className={DICTATION_LANGUAGE_BUTTON}
        aria-label={dictationLanguageButtonLabel(languageName)}
        aria-haspopup="menu"
        aria-expanded={showLanguages}
        disabled={disabled || listening || transcribing}
        data-dictation-language=""
        onClick={() => setShowLanguages((current) => !current)}
      >
        <ChevronDown className="h-3.5 w-3.5" aria-hidden />
      </button>
      <AnchoredPopover
        open={showLanguages}
        onClose={() => setShowLanguages(false)}
        anchorRef={menuAnchorRef}
        label="Dictation language"
        restoreFocusRef={caretRef}
        className="w-60"
      >
        <LanguageMenu value={language} onChoose={chooseLanguage} />
      </AnchoredPopover>
    </div>
  ) : null;

  const status = (
    <>
      {listening || interim ? (
        <p
          aria-live="polite"
          className="min-h-5 rounded-md border border-dashed border-purple-300 bg-purple-50 px-2 py-1 text-xs italic leading-5 text-purple-800"
        >
          {interim || (mode === "server" ? "Recording… press stop to transcribe." : "Listening… speak now.")}
        </p>
      ) : null}

      {mode === "server" && transcribing && lastRecording ? (
        <div className="flex flex-wrap items-center gap-2">
          <DownloadMediaButton
            name="voice note"
            onDownload={() => downloadLocalMedia(lastRecording.blob, { capturedAt: lastRecording.at })}
          />
        </div>
      ) : null}

      {mode === "server" && allowance && allowance.dictationsRemaining !== null ? (
        <p className="text-xs text-ink-500">
          {allowance.dictationsRemaining} dictation
          {allowance.dictationsRemaining === 1 ? "" : "s"} left today ({allowance.dictationDay}).
        </p>
      ) : null}

      {problem ? <p className="text-xs font-medium leading-5 text-error-600">{problem}</p> : null}
      {lastMediaId ? <TranscriptViewer mediaId={lastMediaId} initiallyOpen={false} /> : null}
    </>
  );

  if (!children) {
    return (
      <div className="grid gap-1.5">
        <div className="flex justify-end">{adornment}</div>
        {status}
      </div>
    );
  }

  if (beside) {
    return (
      <div className="grid min-w-0 gap-1.5">
        <div className={DICTATION_BESIDE_ROW}>
          <div className="min-w-0 flex-1">{children}</div>
          {adornment}
        </div>
        {status}
      </div>
    );
  }

  return (
    <div className="grid min-w-0 gap-1.5">
      <div className={ready ? `${DICTATION_FIELD_WRAPPER} ${DICTATION_FIELD_PADDED}` : DICTATION_FIELD_WRAPPER}>
        {children}
        {adornment ? <div className={adornmentPosition(multiline)}>{adornment}</div> : null}
      </div>
      {status}
    </div>
  );
}

function LanguageMenu({ value, onChoose }: { value: string; onChoose: (next: string) => void }) {
  const listRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const frame = requestAnimationFrame(() => {
      listRef.current?.querySelector<HTMLButtonElement>('[aria-checked="true"]')?.focus();
    });
    return () => cancelAnimationFrame(frame);
  }, []);

  function move(event: React.KeyboardEvent<HTMLDivElement>) {
    const items = Array.from(listRef.current?.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]') ?? []);
    const at = items.indexOf(document.activeElement as HTMLButtonElement);
    let next = -1;
    if (event.key === "ArrowDown") next = (at + 1) % items.length;
    else if (event.key === "ArrowUp") next = (at - 1 + items.length) % items.length;
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = items.length - 1;
    if (next < 0) return;
    event.preventDefault();
    items[next]?.focus();
  }

  return (
    <div ref={listRef} role="menu" aria-label="Dictation language" className="grid gap-0.5" onKeyDown={move}>
      {DICTATION_LANGUAGES.map((entry) => {
        const checked = entry.value === value;
        return (
          <button
            key={entry.value}
            type="button"
            role="menuitemradio"
            aria-checked={checked}
            tabIndex={checked ? 0 : -1}
            onClick={() => onChoose(entry.value)}
            className={
              checked
                ? "flex min-h-11 items-center gap-2 rounded-md bg-purple-50 px-3 text-left text-sm font-medium text-purple-800 dark:bg-purple-900/60 dark:text-purple-200"
                : "flex min-h-11 items-center gap-2 rounded-md px-3 text-left text-sm text-ink-900 hover:bg-surface-50"
            }
          >
            <Check className={checked ? "h-4 w-4 shrink-0" : "h-4 w-4 shrink-0 opacity-0"} aria-hidden />
            {entry.label}
          </button>
        );
      })}
    </div>
  );
}
