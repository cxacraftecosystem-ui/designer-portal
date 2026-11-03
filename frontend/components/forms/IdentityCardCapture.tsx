"use client";

import { Toggle } from "@/components/ui/Toggle";
import { useEffect, useId, useRef, useState } from "react";
import { AlertTriangle, Camera, Check, ImageIcon, Loader2, MonitorCheck, X } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import {
  DW_OCR_IDENTITY_PATH,
  identityChoices,
  photographWasNotStored,
  readIdentityCard,
  serverOffersRoute,
  type DwIdentityChoice
} from "@/lib/designWorkshops";
import { browserCanReadCards, readCardTextInBrowser } from "@/lib/identityCardLocal";
import { identityCandidatesFromText } from "@/lib/identityCardText";
import { canRunDesignWorkshops } from "@/lib/permissions";
import { ActionGroup } from "@/components/ui/ActionGroup";

function display(choice: DwIdentityChoice): string {
  return choice.kind === "AADHAAR" ? choice.value.replace(/(\d{4})(?=\d)/g, "$1 ") : choice.value;
}

export function IdentityCardCapture({
  kind,
  targetLabel,
  currentValue,
  aadhaarProblem,
  onUse,
  onKeepPhotograph,
  disabled
}: {
  kind: "AADHAAR" | "PEHCHAN";
  targetLabel: string;
  aadhaarProblem: (digits: string) => string | null;
  currentValue: string;
  onUse: (value: string) => void;
  onKeepPhotograph?: (file: File) => Promise<void> | void;
  disabled?: boolean;
}) {
  const baseId = useId();
  const cameraRef = useRef<HTMLInputElement>(null);
  const pickerRef = useRef<HTMLInputElement>(null);
  const [offered, setOffered] = useState<boolean | null>(null);
  const [reading, setReading] = useState(false);
  const [choices, setChoices] = useState<DwIdentityChoice[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const [browserReads, setBrowserReads] = useState<boolean | null>(null);
  const [readHere, setReadHere] = useState(true);
  const [readLocally, setReadLocally] = useState(false);
  const [keepPhotograph, setKeepPhotograph] = useState(false);
  const [kept, setKept] = useState<string | null>(null);
  const { user } = useAuth();
  const permitted = canRunDesignWorkshops(user);

  useEffect(() => {
    if (!permitted) return;
    let cancelled = false;
    serverOffersRoute(DW_OCR_IDENTITY_PATH)
      .then((available) => {
        if (!cancelled) setOffered(available);
      })
      .catch(() => {
        if (!cancelled) setOffered(false);
      });
    return () => {
      cancelled = true;
    };
  }, [permitted]);

  useEffect(() => {
    let cancelled = false;
    browserCanReadCards()
      .then((available) => {
        if (!cancelled) setBrowserReads(available);
      })
      .catch(() => {
        if (!cancelled) setBrowserReads(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const localUsable = browserReads === true && kind === "AADHAAR";
  const useLocal = localUsable && readHere;
  const canKeep = typeof onKeepPhotograph === "function";
  const willKeep = canKeep && keepPhotograph;

  if (!permitted || offered === null || browserReads === null) return null;
  if (offered !== true && !localUsable) return null;

  function offer(found: DwIdentityChoice[], locally: boolean, rejected: number) {
    if (found.length) {
      setReadLocally(locally);
      setChoices(found);
      return;
    }
    if (rejected > 0) {
      setProblem(
        "The number read failed its check. Retake in better light, or type it."
      );
      return;
    }
    setProblem(
      `No ${kind === "AADHAAR" ? "Aadhaar" : "Pehchan"} number found. Fill the frame with the card, or type it.` +
        (locally && offered === true
          ? " Or untick “Read it on this computer” to read online."
          : "")
    );
  }

  async function read(input: HTMLInputElement | null) {
    const file = input?.files?.[0];
    if (input) input.value = "";
    if (!file) return;
    setReading(true);
    setProblem(null);
    setChoices([]);
    setKept(null);
    if (willKeep && onKeepPhotograph) {
      try {
        await onKeepPhotograph(file);
        setKept("Photo kept on the record.");
      } catch (error) {
        setKept(
          error instanceof Error
            ? `Couldn't keep the photo: ${error.message}`
            : "Couldn't keep the photo."
        );
      }
    }
    try {
      if (useLocal) {
        const outcome = await readCardTextInBrowser(file);
        if (!outcome.ok) {
          setProblem(
            outcome.reason === "undecodable"
              ?
                "Can't open that picture (HEIC?). Save it as JPEG or PNG, or type the number."
              : "Couldn't read that photo. Type the number instead."
          );
          return;
        }
        const reading = identityCandidatesFromText(outcome.text, aadhaarProblem);
        offer(
          reading.aadhaar.map((value) => ({ value, kind: "AADHAAR" as const, confidence: null })),
          true,
          reading.rejectedCount
        );
        return;
      }
      const result = await readIdentityCard(file, willKeep ? "STORE" : "DISCARD");
      if (!willKeep && photographWasNotStored(result)) {
        setKept("Photo read and discarded.");
      }
      offer(identityChoices(result, kind, aadhaarProblem), false, result.rejectedAadhaarCount ?? 0);
    } catch (error) {
      setProblem(
        typeof navigator !== "undefined" && !navigator.onLine
          ? "Connection lost — nothing was sent or kept. Type the number, or try again."
          : error instanceof Error
            ? `Couldn't read the card: ${error.message}`
            : "Couldn't read the card. Type the number instead."
      );
    } finally {
      setReading(false);
    }
  }

  function open(ref: React.RefObject<HTMLInputElement | null>) {
    if (!useLocal && typeof navigator !== "undefined" && !navigator.onLine) {
      setProblem(
        "Reading a card needs a connection. Type the number for now."
      );
      return;
    }
    setProblem(null);
    ref.current?.click();
  }

  return (
    <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
      <p className="text-xs leading-5 text-ink-500">
        Photograph the card to read the number. Confirm it before it goes into {targetLabel}.{" "}
        {willKeep
          ? "The photo will be kept on this record, unmasked."
          : useLocal
            ? "Read on this computer; the photo isn't sent or stored."
            : "The photo isn't stored."}
      </p>

      {canKeep ? (
        <Toggle
          label="Keep the photo on this record"
          description="Off by default."
          checked={keepPhotograph}
          disabled={disabled || reading}
          onChange={setKeepPhotograph}
          testId="identity-keep-photograph"
        />
      ) : null}

      <ActionGroup>
        <button type="button" className="field-button-secondary" disabled={disabled || reading} onClick={() => open(cameraRef)}>
          {reading ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Camera className="h-4 w-4" aria-hidden />}
          Photograph the card
        </button>
        <button type="button" className="field-button-secondary" disabled={disabled || reading} onClick={() => open(pickerRef)}>
          <ImageIcon className="h-4 w-4" aria-hidden />
          Choose a photo
        </button>
      </ActionGroup>

      {localUsable && offered === true ? (
        <Toggle
          label="Read on this computer"
          description="Offline, photo not sent. Switch off to read online — better for worn cards."
          checked={readHere}
          disabled={disabled || reading}
          onChange={setReadHere}
          testId="identity-read-here"
        />
      ) : null}

      {localUsable && offered !== true ? (
        <p className="flex items-start gap-2 text-xs leading-5 text-ink-500">
          <MonitorCheck className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>Read on this computer (offline, photo not sent).</span>
        </p>
      ) : null}

      <input
        ref={cameraRef}
        id={`${baseId}-camera`}
        type="file"
        accept="image/*"
        capture="environment"
        className="sr-only"
        tabIndex={-1}
        aria-hidden="true"
        onChange={(event) => void read(event.currentTarget)}
      />
      <input
        ref={pickerRef}
        id={`${baseId}-picker`}
        type="file"
        accept="image/*"
        className="sr-only"
        tabIndex={-1}
        aria-hidden="true"
        onChange={(event) => void read(event.currentTarget)}
      />

      {choices.length ? (
        <div className="grid gap-2 rounded-md border border-purple-300 bg-card p-3">
          <p className="text-xs font-medium uppercase tracking-wide text-ink-500">
            {choices.length === 1 ? "Candidate — not saved yet" : `${choices.length} candidates — none saved yet`}
          </p>
          <p className="flex items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-2 py-1.5 text-xs leading-5 text-amber-800">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
            <span>
              Check it against the card. One wrong digit creates a duplicate artisan.
            </span>
          </p>
          {currentValue ? (
            <p className="text-xs leading-5 text-amber-800 dark:text-amber-100">
              {targetLabel} already holds “{currentValue}”. Confirming replaces it.
            </p>
          ) : null}
          <div className="grid gap-2">
            {choices.map((choice) => (
              <div key={choice.value} className="grid gap-1">
                <button
                  type="button"
                  className="field-button justify-self-start"
                  disabled={disabled}
                  onClick={() => {
                    onUse(choice.value);
                    setChoices([]);
                  }}
                >
                  <Check className="h-4 w-4" aria-hidden />
                  This matches the card — use {display(choice)}
                </button>
                <p className="text-xs leading-5 text-ink-500">
                  {choice.kind === "AADHAAR" ? "Aadhaar" : "Pehchan card"}
                  {choice.confidence !== null ? ` · ${choice.confidence >= 0.85 ? "read clearly" : "read with difficulty"}` : ""}
                  {readLocally ? " · read on this computer" : ""}
                </p>
              </div>
            ))}
          </div>
          <button
            type="button"
            className="inline-flex items-center gap-1 justify-self-start text-xs font-medium text-ink-500 underline"
            onClick={() => setChoices([])}
          >
            <X className="h-3 w-3" aria-hidden />
            None of these — discard
          </button>
        </div>
      ) : null}

      {kept ? (
        <p role="status" className="text-xs leading-5 text-ink-500">
          {kept}
        </p>
      ) : null}

      {problem ? <p className="text-xs font-medium leading-5 text-error-600">{problem}</p> : null}
    </div>
  );
}
