"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { AlertTriangle, Camera, ClipboardPaste, ImageUp, Keyboard, Loader2, ScanLine, Square } from "lucide-react";

import { decodeQrFromFile, decodeQrFromVideoFrame, type QrImageRefusal } from "@/lib/qrImageDecode";
import {
  JOIN_CARD_REDEEM_OFFLINE_MESSAGE,
  decodeJoinCard,
  joinCardFailure,
  joinOutcomeIsMembership,
  joinOutcomeSentence,
  looksLikeJoinCard,
  redeemJoinCard
} from "@/lib/joinCards";
import { type WorkshopCodeRef } from "@/lib/workshopCodes";
import { CARD_PLACEHOLDER } from "@/lib/artisanCard";
import { resolveCardForScan, type CardScanOutcome } from "@/lib/artisanCardScan";
import { classifyScanInput } from "@/lib/scanInput";
import { ActionGroup } from "@/components/ui/ActionGroup";

const TRANSIENT_FRAME_REFUSALS: readonly QrImageRefusal[] = ["NO_SYMBOL", "UNREADABLE", "DATA_UNRECOVERABLE"];

const NEAR_MISSES_BEFORE_ADVICE = 6;

export type ScanResolution = { ok: true; label: string; detail?: string } | { ok: false; message: string };

const FRAME_INTERVAL_MS = 220;

export type ScannerLabels = {
  heading: string;
  camera: string;
  stopCamera: string;
  photo: string;
  photoPhrase: string;
  typed: string;
};

const DEFAULT_SCANNER_LABELS: ScannerLabels = {
  heading: "Scan a card or tag",
  camera: "Scan with the camera",
  stopCamera: "Stop the camera",
  photo: "Upload a picture",
  photoPhrase: "upload a picture",
  typed: "Or type the code printed under the QR"
};

function capitalise(text: string): string {
  return text ? text.charAt(0).toUpperCase() + text.slice(1) : text;
}

export function WorkshopCodeScanner({
  resolve,
  onResolved,
  onJoined,
  resolveCard = resolveCardForScan,
  onCardResolved,
  takeRaw,
  labels: labelOverrides,
  description
}: {
  labels?: Partial<ScannerLabels>;
  takeRaw?: (raw: string) => Promise<void>;
  resolveCard?: (raw: string) => Promise<CardScanOutcome>;
  onCardResolved?: (outcome: CardScanOutcome) => void;
  onJoined?: (workshopId: string) => void;
  resolve: (ref: WorkshopCodeRef) => Promise<ScanResolution>;
  onResolved?: (ref: WorkshopCodeRef, resolution: ScanResolution) => void;
  description?: string;
}) {
  const labels: ScannerLabels = { ...DEFAULT_SCANNER_LABELS, ...labelOverrides };
  const [scanning, setScanning] = useState(false);
  const [busy, setBusy] = useState(false);
  const [starting, setStarting] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<{
    tone: "found" | "refused";
    text: string;
    detail?: string;
    lead?: string;
    href?: string;
    hrefLabel?: string;
  } | null>(null);
  const [typed, setTyped] = useState("");
  const [dragging, setDragging] = useState(false);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const handlingRef = useRef(false);
  const nearMissRef = useRef(0);
  const cameraGenerationRef = useRef(0);
  const startingRef = useRef(false);

  const stopCamera = useCallback(() => {
    cameraGenerationRef.current += 1;
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;
    setScanning(false);
  }, []);

  useEffect(() => stopCamera, [stopCamera]);

  useEffect(() => {
    const onHidden = () => {
      if (document.visibilityState === "hidden") stopCamera();
    };
    document.addEventListener("visibilitychange", onHidden);
    return () => document.removeEventListener("visibilitychange", onHidden);
  }, [stopCamera]);

  const handleReference = useCallback(
    async (ref: WorkshopCodeRef) => {
      setBusy(true);
      try {
        const resolution = await resolve(ref);
        setOutcome(
          resolution.ok
            ? { tone: "found", text: resolution.label, detail: resolution.detail }
            : { tone: "refused", text: resolution.message }
        );
        onResolved?.(ref, resolution);
      } catch (error) {
        setOutcome({
          tone: "refused",
          text:
            error instanceof Error
              ? `That code could not be looked up: ${error.message}`
              : "That code could not be looked up."
        });
      } finally {
        setBusy(false);
      }
    },
    [resolve, onResolved]
  );

  const handleJoinCard = useCallback(
    async (raw: string) => {
      setTyped("");
      const card = decodeJoinCard(raw);
      if (!card.ok) {
        setOutcome({ tone: "refused", text: card.message });
        return;
      }
      setBusy(true);
      setOutcome({ tone: "found", lead: "", text: "Join card — adding you…" });
      try {
        const answer = await redeemJoinCard(card.code);
        const member = joinOutcomeIsMembership(answer.outcome);
        const workshopId = answer.workshopId || card.workshopId;
        setOutcome(
          member
            ? {
                tone: "found",
                lead: "",
                text: joinOutcomeSentence(answer),
                href: `/design-workshops/${encodeURIComponent(workshopId)}`,
                hrefLabel: "Open the workshop"
              }
            :
              { tone: "refused", text: joinOutcomeSentence(answer) }
        );
        if (member) onJoined?.(workshopId);
      } catch (error) {
        setOutcome({ tone: "refused", text: joinCardFailure(error, JOIN_CARD_REDEEM_OFFLINE_MESSAGE).message });
      } finally {
        setBusy(false);
      }
    },
    [onJoined]
  );

  const handleRawValue = useCallback(
    async (raw: string) => {
      if (takeRaw) {
        if (looksLikeJoinCard(raw)) setTyped("");
        setOutcome(null);
        setBusy(true);
        try {
          await takeRaw(raw);
        } finally {
          setBusy(false);
        }
        return;
      }
      if (looksLikeJoinCard(raw)) {
        await handleJoinCard(raw);
        return;
      }
      const scan = classifyScanInput(raw);
      if (scan.kind === "refused") {
        setOutcome({ tone: "refused", text: scan.message });
        return;
      }
      if (scan.kind === "card") {
        setBusy(true);
        try {
          const answer = await resolveCard(scan.raw);
          setTyped("");
          setOutcome(
            answer.ok
              ? { tone: "found", text: answer.label, detail: answer.detail, href: answer.href, hrefLabel: answer.hrefLabel }
              : { tone: "refused", text: answer.message, href: answer.href, hrefLabel: answer.hrefLabel }
          );
          onCardResolved?.(answer);
        } finally {
          setBusy(false);
        }
        return;
      }
      await handleReference(scan.ref);
    },
    [handleReference, handleJoinCard, resolveCard, onCardResolved, takeRaw]
  );

  const readFromFile = useCallback(
    async (file: File | Blob, name?: string) => {
      setProblem(null);
      setOutcome(null);
      stopCamera();
      setBusy(true);
      try {
        const read = await decodeQrFromFile(file, name ?? (file instanceof File ? file.name : undefined));
        if (!read.ok) {
          setProblem(read.message);
          return;
        }
        await handleRawValue(read.text);
      } catch (error) {
        setProblem(
          error instanceof Error ? `That picture could not be read: ${error.message}` : "That picture could not be read."
        );
      } finally {
        setBusy(false);
      }
    },
    [handleRawValue, stopCamera]
  );

  useEffect(() => {
    const onPaste = (event: ClipboardEvent) => {
      if (busy || scanning) return;
      const item = Array.from(event.clipboardData?.items ?? []).find((entry) => entry.type.startsWith("image/"));
      const file = item?.getAsFile();
      if (!file) return;
      event.preventDefault();
      void readFromFile(file, "the pasted picture");
    };
    window.addEventListener("paste", onPaste);
    return () => window.removeEventListener("paste", onPaste);
  }, [busy, scanning, readFromFile]);

  async function startCamera() {
    if (startingRef.current) return;
    setProblem(null);
    setOutcome(null);
    handlingRef.current = false;
    nearMissRef.current = 0;

    if (!navigator.mediaDevices?.getUserMedia) {
      setProblem(
        `No camera here. ${capitalise(labels.photoPhrase)} or type the code.`
      );
      return;
    }

    let stream: MediaStream;
    stopCamera();
    const generation = cameraGenerationRef.current;
    startingRef.current = true;
    setStarting(true);
    try {
      stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: "environment" } } });
    } catch (error) {
      const name = error instanceof Error ? error.name : "";
      setProblem(
        name === "NotAllowedError" || name === "SecurityError"
          ? `Camera blocked. Allow it in site settings, or ${labels.photoPhrase} or type the code.`
          : name === "NotFoundError" || name === "OverconstrainedError"
            ? `No camera found. ${capitalise(labels.photoPhrase)} or type the code.`
            : `Couldn't open the camera. Close other apps using it, or ${labels.photoPhrase} or type the code.`
      );
      return;
    } finally {
      startingRef.current = false;
      setStarting(false);
    }

    if (generation !== cameraGenerationRef.current || document.visibilityState === "hidden") {
      stream.getTracks().forEach((track) => track.stop());
      return;
    }

    streamRef.current = stream;
    const video = videoRef.current;
    if (!video) {
      stopCamera();
      return;
    }
    video.srcObject = stream;
    try {
      await video.play();
    } catch {
    }

    setScanning(true);

    const readFrame = async () => {
      timerRef.current = null;
      const element = videoRef.current;
      if (!element || !streamRef.current || handlingRef.current) return;

      let read: Awaited<ReturnType<typeof decodeQrFromVideoFrame>> = null;
      try {
        read = await decodeQrFromVideoFrame(element);
      } catch {
        read = null;
      }

      const again = () => {
        if (streamRef.current) timerRef.current = setTimeout(() => void readFrame(), FRAME_INTERVAL_MS);
      };

      if (!read) {
        again();
        return;
      }

      if (!read.ok && TRANSIENT_FRAME_REFUSALS.includes(read.reason)) {
        if (read.reason === "NO_SYMBOL") {
          nearMissRef.current = 0;
        } else if (++nearMissRef.current >= NEAR_MISSES_BEFORE_ADVICE) {
          setProblem(read.message);
        }
        again();
        return;
      }

      handlingRef.current = true;
      stopCamera();
      if (!read.ok) {
        setProblem(read.message);
        return;
      }
      setProblem(null);
      await handleRawValue(read.text);
    };

    timerRef.current = setTimeout(() => void readFrame(), FRAME_INTERVAL_MS);
  }

  return (
    <section
      className="panel grid gap-3 p-4"
      aria-label={labels.heading || "Scan a code"}
      data-testid="workshop-code-scanner"
      onDragOver={(event) => {
        if (busy || scanning) return;
        event.preventDefault();
        setDragging(true);
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={(event) => {
        event.preventDefault();
        setDragging(false);
        if (busy || scanning) return;
        const file = event.dataTransfer.files?.[0];
        if (file) void readFromFile(file);
      }}
    >
      {labels.heading ? (
        <div className="flex flex-wrap items-center gap-2">
          <ScanLine className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <h2 className="text-sm font-medium text-ink-900">{labels.heading}</h2>
        </div>
      ) : null}
      {description ? <p className="text-xs leading-5 text-ink-500">{description}</p> : null}

      <ActionGroup align="start">
        <button
          type="button"
          className="field-button"
          onClick={() => (scanning ? stopCamera() : void startCamera())}
          disabled={busy || starting}
          data-testid="workshop-code-scan"
        >
          {scanning ? <Square className="h-4 w-4" aria-hidden /> : <Camera className="h-4 w-4" aria-hidden />}
          {scanning ? labels.stopCamera : labels.camera}
        </button>
        <button
          type="button"
          className="field-button"
          onClick={() => fileInputRef.current?.click()}
          disabled={busy || scanning}
          data-testid="workshop-code-upload"
        >
          <ImageUp className="h-4 w-4" aria-hidden />
          {labels.photo}
        </button>
      </ActionGroup>
      <div className="flex flex-wrap items-center gap-2 empty:hidden">
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*"
          className="hidden"
          data-testid="workshop-code-file"
          onChange={(event) => {
            const file = event.target.files?.[0];
            event.target.value = "";
            if (file) void readFromFile(file);
          }}
        />
        {busy ? (
          <span className="flex items-center gap-1.5 text-xs text-ink-500">
            <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden />
            Reading the code…
          </span>
        ) : scanning ? (
          <span className="text-xs text-ink-500">Hold the card 10–15 cm away.</span>
        ) : null}
      </div>

      <p className="flex items-start gap-2 text-xs leading-5 text-ink-500">
        <ClipboardPaste className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
        <span>
          Or drop or paste (Ctrl+V) a picture of the code.
        </span>
      </p>

      {dragging ? (
        <p className="rounded-md border border-dashed border-purple-300 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-700">
          Drop the picture to read the code in it.
        </p>
      ) : null}

      <video
        ref={videoRef}
        className={
          scanning
            ? "aspect-video w-full max-w-sm rounded-md border border-line-200 bg-ink-900/80 object-cover"
            : "hidden"
        }
        muted
        playsInline
        aria-label="Camera preview"
        data-testid="workshop-code-preview"
      />

      {problem ? (
        <p
          className="flex items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800"
          data-testid="workshop-code-problem"
          role="status"
        >
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>{problem}</span>
        </p>
      ) : null}

      <div className="grid gap-1.5" onInput={(event) => event.stopPropagation()} onKeyDown={(event) => event.stopPropagation()}>
        <label htmlFor="workshop-code-manual" className="field-label flex items-center gap-1.5">
          <Keyboard className="h-3.5 w-3.5" aria-hidden />
          {labels.typed}
        </label>
        <div className="flex flex-wrap gap-2">
          <input
            id="workshop-code-manual"
            className="field-input min-w-0 flex-1 font-mono uppercase"
            placeholder={CARD_PLACEHOLDER}
            autoComplete="off"
            autoCapitalize="characters"
            spellCheck={false}
            value={typed}
            onChange={(event) => setTyped(event.target.value)}
            onKeyDown={(event) => {
              if (event.key !== "Enter") return;
              event.preventDefault();
              if (typed.trim() && !busy) void handleRawValue(typed);
            }}
          />
          <button
            type="button"
            className="field-button-secondary"
            disabled={!typed.trim() || busy}
            onClick={() => void handleRawValue(typed)}
            data-testid="workshop-code-lookup"
          >
            {busy ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : null}
            Look up
          </button>
        </div>
        <p className="text-xs leading-5 text-ink-500">
          Spaces and capitals don&apos;t matter.
        </p>
      </div>

      <div role="status" aria-live="polite" className="min-h-0">
        {outcome ? (
          <p
            className={
              outcome.tone === "found"
                ? "rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-900"
                : "rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800"
            }
            data-testid="workshop-code-outcome"
          >
            <span className="font-medium">{outcome.lead ?? (outcome.tone === "found" ? "Found: " : "")}</span>
            {outcome.text}
            {outcome.detail ? <span className="block text-xs text-ink-500">{outcome.detail}</span> : null}
            {outcome.href ? (
              <Link href={outcome.href} className="mt-1 block text-sm font-medium underline">
                {outcome.hrefLabel ?? "Open"}
              </Link>
            ) : null}
          </p>
        ) : null}
      </div>
    </section>
  );
}
