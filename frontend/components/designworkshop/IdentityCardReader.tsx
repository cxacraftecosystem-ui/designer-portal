"use client";

import { Toggle } from "@/components/ui/Toggle";
import { useEffect, useState } from "react";
import { AlertTriangle, Check, Loader2, MonitorCheck, ScanLine, ShieldAlert, Trash2, X } from "lucide-react";

import { aadhaarValidationError } from "@/components/forms/AadhaarField";
import { DW_OCR_IDENTITY_PATH, identityChoices, readIdentityCard, serverOffersRoute } from "@/lib/designWorkshops";
import { browserCanReadCards, readCardTextInBrowser } from "@/lib/identityCardLocal";
import { identityCandidatesFromText } from "@/lib/identityCardText";
import {
  DW_PHOTO_UNDECIDED,
  decideIdentityPhotograph,
  retentionOutcomeSentence,
  type DwPhotoRetention
} from "@/lib/identityPhotoRetention";
import type { MediaFile } from "@/lib/types";
import { ActionGroup } from "@/components/ui/ActionGroup";

type Candidate = {
  number: string;
  kind: string;
  confidence: number | null;
  mediaId: string;
  source: string;
  local: boolean;
};

export function IdentityCardReader({
  files,
  originals,
  targetLabel,
  currentValue,
  onConfirm,
  onDiscard,
  disabled
}: {
  files: MediaFile[];
  originals: Record<string, File>;
  targetLabel: string;
  currentValue: string;
  onConfirm: (digits: string) => void;
  onDiscard?: (mediaId: string) => void;
  disabled?: boolean;
}) {
  const [offered, setOffered] = useState<boolean | null>(null);
  const [reading, setReading] = useState(false);
  const [candidate, setCandidate] = useState<Candidate | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [browserReads, setBrowserReads] = useState<boolean | null>(null);
  const [readHere, setReadHere] = useState(true);
  const [decidable, setDecidable] = useState<{ mediaId: string; name: string }[]>([]);
  const [deciding, setDeciding] = useState<{ mediaId: string; choice: DwPhotoRetention } | null>(null);
  const [retentionOutcome, setRetentionOutcome] = useState<string[]>([]);
  const [decisionProblem, setDecisionProblem] = useState<string | null>(null);

  useEffect(() => {
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
  }, []);

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

  const readable = files.filter((file) => file.mediaType === "IMAGE");
  const useLocal = browserReads === true && readHere;
  if (offered === null || browserReads === null) return null;
  if ((offered !== true && browserReads !== true) || !readable.length) return null;

  async function decide(mediaId: string, choice: DwPhotoRetention) {
    setDeciding({ mediaId, choice });
    setDecisionProblem(null);
    try {
      const result = await decideIdentityPhotograph(mediaId, choice);
      setRetentionOutcome((current) => [...current, retentionOutcomeSentence(result)]);
      if (result.deleted) {
        onDiscard?.(mediaId);
        setCandidate((current) => (current && current.mediaId === mediaId ? null : current));
      }
      setDecidable((current) => current.filter((entry) => entry.mediaId !== mediaId));
    } catch (error) {
      setDecisionProblem(
        error instanceof Error
          ?
            `Couldn't record that — the photo is still stored. ${error.message}`
          : "Couldn't record that — the photo is still stored. Try again."
      );
    } finally {
      setDeciding(null);
    }
  }

  async function read(media: MediaFile) {
    setReading(true);
    setProblem(null);
    setCandidate(null);
    setDecisionProblem(null);
    setDecidable((current) =>
      current.some((entry) => entry.mediaId === media.id)
        ? current
        : [...current, { mediaId: media.id, name: media.originalFilename }]
    );
    try {
      const source = originals[media.id];
      if (!source) {
        setProblem(
          "Re-attach the photo to read it, or type the number."
        );
        return;
      }
      if (useLocal) {
        const outcome = await readCardTextInBrowser(source);
        if (!outcome.ok) {
          setProblem(
            outcome.reason === "undecodable"
              ?
                "Can't open that picture (HEIC?). Save it as JPEG or PNG, or type the number."
              : "Couldn't read that photo. Type the number instead."
          );
          return;
        }
        const found = identityCandidatesFromText(outcome.text, aadhaarValidationError);
        const [first] = found.aadhaar;
        if (!first) {
          setProblem(
            found.rejectedCount > 0
              ? "The number read failed its check. Retake in better light, or type it."
              :
                offered === true
                ? "No number found. Untick “Read it on this computer” to read online, or type it."
                : "No number found. Retake with the whole card in frame, or type it."
          );
          return;
        }
        setCandidate({
          number: first,
          kind: "AADHAAR",
          mediaId: media.id,
          confidence: null,
          source: media.originalFilename,
          local: true
        });
        return;
      }

      const result = await readIdentityCard(source);
      const [best] = identityChoices(result, "ANY", aadhaarValidationError);
      if (!best) {
        const rejected = result.rejectedAadhaarCount ?? 0;
        setProblem(
          rejected > 0
            ?
              "The number read failed its check. Retake in better light, or type it."
            : "No number found. Retake with the whole card in frame, or type it."
        );
        return;
      }
      setCandidate({
        number: best.value,
        kind: best.kind,
        mediaId: media.id,
        confidence: best.confidence,
        source: media.originalFilename,
        local: false
      });
    } catch (error) {
      setProblem(
        error instanceof Error ? `The card could not be read: ${error.message}` : "The card could not be read."
      );
    } finally {
      setReading(false);
    }
  }

  const grouped = candidate ? (candidate.kind === "AADHAAR" ? candidate.number.replace(/(\d{4})(?=\d)/g, "$1 ") : candidate.number) : "";

  return (
    <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
      <div className="flex flex-wrap items-center gap-2">
        <ScanLine className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
        <span className="text-sm font-medium text-ink-900">Read the number from this card</span>
      </div>
      <p className="text-xs leading-5 text-ink-500">
        Check the number against the card. Nothing is written into {targetLabel} until you confirm.
      </p>

      {browserReads === true && offered === true ? (
        <Toggle
          label="Read on this computer"
          description="Offline, photo not sent. Switch off to read online — better for worn cards and Pehchan."
          checked={readHere}
          disabled={disabled || reading}
          onChange={setReadHere}
          testId="identity-reader-read-here"
        />
      ) : null}

      {browserReads === true && offered !== true ? (
        <p className="flex items-start gap-2 text-xs leading-5 text-ink-500">
          <MonitorCheck className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>
            Read on this computer (offline, photo not sent). Aadhaar only.
          </span>
        </p>
      ) : null}

      <div className="flex flex-wrap gap-2">
        {readable.map((media) => (
          <button
            key={media.id}
            type="button"
            className="field-button-secondary"
            disabled={disabled || reading}
            onClick={() => void read(media)}
          >
            {reading ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <ScanLine className="h-4 w-4" aria-hidden />}
            {readable.length === 1 ? "Read this card" : `Read ${media.originalFilename}`}
          </button>
        ))}
      </div>

      {candidate ? (
        <div className="grid gap-2 rounded-md border border-purple-300 bg-card p-3">
          <p className="text-xs font-medium uppercase tracking-wide text-ink-500">Candidate — not saved yet</p>
          <p className="font-mono text-lg tracking-wider text-ink-900">{grouped}</p>
          <p className="text-xs leading-5 text-ink-500">
            Read from {candidate.source}
            {candidate.kind ? ` · looks like a ${candidate.kind.toLowerCase()} card` : ""}
            {candidate.confidence !== null
              ? ` · ${candidate.confidence >= 0.85 ? "read clearly" : "read with difficulty"}`
              : ""}
            {candidate.local ? " · read on this computer" : ""}.
          </p>

          <p className="flex items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-2 py-1.5 text-xs leading-5 text-amber-800">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
            <span>
              Check it against the card, not this screen.
            </span>
          </p>

          {currentValue ? (
            <p className="text-xs leading-5 text-amber-800 dark:text-amber-100">
              {targetLabel} already holds “{currentValue}”. Confirming replaces it.
            </p>
          ) : null}

          <ActionGroup>
            <button
              type="button"
              className="field-button"
              disabled={disabled}
              onClick={() => {
                onConfirm(candidate.number);
                setCandidate(null);
              }}
            >
              <Check className="h-4 w-4" aria-hidden />
              This matches the card — use it
            </button>
            <button
              type="button"
              className="inline-flex items-center gap-1 text-xs font-medium text-ink-500 underline"
              onClick={() => setCandidate(null)}
            >
              <X className="h-3 w-3" aria-hidden />
              Discard this reading
            </button>
          </ActionGroup>
        </div>
      ) : null}

      {decidable.map((entry) => {
        const busy = deciding?.mediaId === entry.mediaId ? deciding.choice : null;
        return (
          <div key={entry.mediaId} className="grid gap-2 rounded-md border border-amber-500 bg-amber-100 p-3">
            <p className="flex items-start gap-2 text-xs font-medium leading-5 text-amber-800 dark:text-amber-100">
              <ShieldAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
              <span>
                What happens to “{entry.name}”? {DW_PHOTO_UNDECIDED}
              </span>
            </p>
            <ActionGroup>
              <button
                type="button"
                className="field-button"
                disabled={disabled || busy !== null}
                onClick={() => void decide(entry.mediaId, "DISCARD")}
                data-testid="identity-photo-discard"
              >
                {busy === "DISCARD" ? (
                  <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                ) : (
                  <Trash2 className="h-4 w-4" aria-hidden />
                )}
                Delete this photograph
              </button>
              <button
                type="button"
                className="field-button-secondary"
                disabled={disabled || busy !== null}
                onClick={() => void decide(entry.mediaId, "STORE")}
                data-testid="identity-photo-store"
              >
                {busy === "STORE" ? (
                  <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                ) : (
                  <Check className="h-4 w-4" aria-hidden />
                )}
                Keep it on this record
              </button>
            </ActionGroup>
            <p className="text-xs leading-5 text-amber-800 dark:text-amber-100">
              Deleting can&apos;t be undone. The number is unaffected.
            </p>
          </div>
        );
      })}

      {retentionOutcome.map((sentence, index) => (
        <p key={`${index}-${sentence}`} role="status" className="text-xs leading-5 text-ink-500">
          {sentence}
        </p>
      ))}
      {decisionProblem ? (
        <p role="alert" className="text-xs font-medium leading-5 text-error-600">
          {decisionProblem}
        </p>
      ) : null}

      {problem ? <p className="text-xs font-medium leading-5 text-error-600">{problem}</p> : null}
    </div>
  );
}
