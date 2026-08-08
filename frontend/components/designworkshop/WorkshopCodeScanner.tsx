"use client";

/**
 * Point the camera at a card or a tag, and open the record it names — or say plainly why not.
 *
 * THE FEATURE DETECTION IS THE DESIGN, not a preamble to it. `BarcodeDetector` is a browser API
 * that Chrome and Edge on Android and desktop have, that Firefox does not have at all, and that
 * Safari has only behind a flag. The handsets and laptops this application runs on in a village are
 * whatever was cheapest that year, so "the scanner is missing" is a NORMAL state and not an error —
 * and the honest way to handle it is the one thing this component refuses to compromise on:
 *
 *   - it asks whether the API exists AND whether it lists `qr_code` among its formats (Chrome on
 *     some desktop Linux builds has the constructor and supports nothing), and it asks once;
 *   - where the answer is no, it says so in one sentence and puts the manual box in front of the
 *     designer instead of hiding a broken button behind a spinner;
 *   - the manual box is ALWAYS there, on every device, because it is also the keyboard route and
 *     the answer for a cracked lens, a dark courtyard and a laminated card with a glare across it.
 *
 * A polyfill was considered and refused. Every JavaScript QR *decoder* worth shipping is tens of
 * kilobytes of image processing that would sit in the bundle of every device — including the ones
 * that have the native detector — to serve the browser share that does not. The typed code is a
 * shorter path to the same record and it works everywhere, today, with nothing added.
 *
 * THE CAMERA IS TURNED OFF, EVERY WAY OUT. Stopping the tracks on unmount is the obvious one and
 * the least likely to be needed; the ones that actually bite are the designer who scans a card and
 * navigates away in the same second, and the one who switches to WhatsApp mid-scan and leaves the
 * handset in a pocket with the camera light on and the battery draining. So: the tracks are held in
 * a ref (state would not survive the unmount path), {@link stopCamera} is idempotent, and it is
 * called from the effect cleanup, from a successful read, from every failure, and from
 * `visibilitychange`.
 *
 * A CODE THAT DOES NOT RESOLVE IS NOT TOLD IT ALMOST DID. The API answers 404 rather than 403 for a
 * record the caller may not see, precisely so that a caller cannot learn which records exist by
 * asking about them — and a scanner that said "that record exists but is not yours" would undo that
 * one photographed card at a time. Resolution is the caller's job (this component takes a `resolve`
 * function), and `unresolvedWorkshopCodeMessage` in `lib/workshopCodes.ts` is the one sentence both
 * outcomes share.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { AlertTriangle, Camera, CameraOff, Keyboard, Loader2, ScanLine, Square } from "lucide-react";

import { decodeWorkshopCode, type WorkshopCodeRef } from "@/lib/workshopCodes";

/**
 * The slice of the Barcode Detection API this uses. Declared here rather than in a global `.d.ts`
 * because it is not in TypeScript's DOM library and this is its only consumer — a global
 * declaration would assert to every other file that the API is always present, which is the exact
 * belief this component exists to avoid holding.
 */
type BarcodeDetectorLike = {
  detect(source: CanvasImageSource): Promise<{ rawValue: string }[]>;
};
type BarcodeDetectorConstructor = {
  new (options?: { formats?: string[] }): BarcodeDetectorLike;
  getSupportedFormats?: () => Promise<string[]>;
};

/** What the host made of a scanned reference. `ok: false` must never confirm that the id exists. */
export type ScanResolution = { ok: true; label: string; detail?: string } | { ok: false; message: string };

/** Whether this browser can scan at all, resolved once and never guessed at. */
type Support = "unknown" | "yes" | "no";

/** How often a frame is handed to the detector while the camera is open. */
const FRAME_INTERVAL_MS = 220;

export function WorkshopCodeScanner({
  resolve,
  onResolved,
  description
}: {
  /** Turn a decoded reference into an answer. Called for both the camera and the typed path. */
  resolve: (ref: WorkshopCodeRef) => Promise<ScanResolution>;
  /** Fired only for a reference the host resolved, so a caller can navigate or select a row. */
  onResolved?: (ref: WorkshopCodeRef, resolution: ScanResolution) => void;
  description?: string;
}) {
  const [support, setSupport] = useState<Support>("unknown");
  const [scanning, setScanning] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<{ tone: "found" | "refused"; text: string; detail?: string } | null>(null);
  const [typed, setTyped] = useState("");

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const detectorRef = useRef<BarcodeDetectorLike | null>(null);
  /** Guards the read path: a detector can return the same card in three consecutive frames. */
  const handlingRef = useRef(false);

  /**
   * Release the camera. Safe to call from anywhere, any number of times — which is what lets the
   * unmount cleanup, the stop button, the success path and the error paths all use one function
   * rather than four almost-identical ones that drift apart.
   */
  const stopCamera = useCallback(() => {
    if (timerRef.current !== null) {
      clearInterval(timerRef.current);
      timerRef.current = null;
    }
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;
    setScanning(false);
  }, []);

  useEffect(() => {
    let cancelled = false;
    const constructor = (globalThis as unknown as { BarcodeDetector?: BarcodeDetectorConstructor }).BarcodeDetector;
    if (!constructor) {
      setSupport("no");
      return;
    }
    // Having the constructor is not having the format. Some builds ship the API with an empty
    // format list, and a scanner that opened the camera and then never saw anything would look
    // like a broken camera rather than an unsupported browser.
    const formats = constructor.getSupportedFormats?.();
    if (!formats) {
      setSupport("yes");
      return;
    }
    formats
      .then((list) => {
        if (!cancelled) setSupport(list.includes("qr_code") ? "yes" : "no");
      })
      .catch(() => {
        if (!cancelled) setSupport("no");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // The one cleanup that is not optional. Its dependency is `stopCamera`, which is a stable
  // useCallback with no dependencies — so this registers once and tears down exactly on unmount.
  useEffect(() => stopCamera, [stopCamera]);

  // A handset that switches away mid-scan leaves the camera live, the indicator lit and the
  // battery draining, and nothing brings it back because the page is not unmounted.
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

  /** One decoded string, from either path, taken as far as it can honestly go. */
  const handleRawValue = useCallback(
    async (raw: string) => {
      const decoded = decodeWorkshopCode(raw);
      if (!decoded.ok) {
        setOutcome({ tone: "refused", text: decoded.message });
        return;
      }
      await handleReference(decoded.ref);
    },
    [handleReference]
  );

  async function startCamera() {
    setProblem(null);
    setOutcome(null);
    handlingRef.current = false;

    const constructor = (globalThis as unknown as { BarcodeDetector?: BarcodeDetectorConstructor }).BarcodeDetector;
    if (!constructor) {
      setSupport("no");
      return;
    }

    let stream: MediaStream;
    try {
      // `environment` is a REQUEST, not a guarantee — a laptop has only a front camera and will
      // hand one over anyway, which is correct: a designer holding a card up to a laptop lid is a
      // real way this gets used.
      stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: "environment" } } });
    } catch (error) {
      // The browser distinguishes these two and the advice is completely different, so unlike the
      // dictation recorder (which cannot tell them apart) this one branches on the name.
      const name = error instanceof Error ? error.name : "";
      setProblem(
        name === "NotAllowedError" || name === "SecurityError"
          ? "This site is not allowed to use the camera. Open the site settings — the padlock or the camera icon at the left of the address bar — allow the camera, then press Scan again. You can also type the code printed under the QR instead."
          : name === "NotFoundError" || name === "OverconstrainedError"
            ? "No camera was found on this device. Type the code printed under the QR instead."
            : "The camera could not be opened. Check that nothing else is using it, then try again — or type the code printed under the QR instead."
      );
      return;
    }

    streamRef.current = stream;
    const video = videoRef.current;
    if (!video) {
      // The component unmounted between the permission prompt and the answer. The stream is real
      // and already open, so it has to be closed here or it stays on with nothing pointing at it.
      stopCamera();
      return;
    }
    video.srcObject = stream;
    // `playsInline` is set on the element too; iOS Safari otherwise takes a playing <video> full
    // screen, which hides the whole page behind the camera.
    try {
      await video.play();
    } catch {
      // Autoplay refusal is not fatal — the frames still arrive — so this is deliberately not
      // surfaced. A visible error over a working preview would be the worse outcome.
    }

    detectorRef.current = detectorRef.current ?? new constructor({ formats: ["qr_code"] });
    setScanning(true);
    timerRef.current = setInterval(async () => {
      const detector = detectorRef.current;
      const element = videoRef.current;
      if (!detector || !element || element.readyState < 2 || handlingRef.current) return;
      let codes: { rawValue: string }[];
      try {
        codes = await detector.detect(element);
      } catch {
        // A single frame the detector could not read is normal (motion blur, a frame arriving
        // mid-resize). Failing the whole scan on one of them would make the scanner unusable in
        // exactly the handheld conditions it is for.
        return;
      }
      const raw = codes.find((code) => code.rawValue)?.rawValue;
      if (!raw) return;
      handlingRef.current = true;
      // The camera goes off the moment something is read: the designer's next act is to look at
      // the answer, and a live preview under it is a battery drain and a distraction.
      stopCamera();
      await handleRawValue(raw);
    }, FRAME_INTERVAL_MS);
  }

  const cameraOffered = support === "yes";

  return (
    <section className="panel grid gap-3 p-4" aria-label="Scan a card or tag">
      <div className="flex flex-wrap items-center gap-2">
        <ScanLine className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
        <h2 className="text-sm font-medium text-ink-900">Scan a card or tag</h2>
      </div>
      {description ? <p className="text-xs leading-5 text-ink-500">{description}</p> : null}

      {support === "no" ? (
        <p className="flex items-start gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-700">
          <CameraOff className="mt-0.5 h-3.5 w-3.5 shrink-0 text-ink-500" aria-hidden />
          <span>
            This browser cannot read a QR code from the camera. Type the code printed under the QR instead — it does the same
            thing, and it is what the code is printed for.
          </span>
        </p>
      ) : null}

      {cameraOffered ? (
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" className="field-button" onClick={() => (scanning ? stopCamera() : void startCamera())} disabled={busy}>
            {scanning ? <Square className="h-4 w-4" aria-hidden /> : <Camera className="h-4 w-4" aria-hidden />}
            {scanning ? "Stop the camera" : "Scan a code"}
          </button>
          {scanning ? <span className="text-xs text-ink-500">Hold the card 10–15 cm from the lens, with the whole square in view.</span> : null}
        </div>
      ) : null}

      {/*
        ALWAYS MOUNTED, hidden rather than unmounted. `startCamera` has to attach the stream to this
        element in the same tick it receives it, and it runs while `scanning` is still false — so a
        `{scanning ? <video/> : null}` left `videoRef.current` null at exactly that moment, the
        function took its "the component went away" branch, and pressing Scan opened the camera,
        closed it again and showed nothing at all. Hidden with `hidden` and not with an off-screen
        position, so a <video> holding no stream is not a black rectangle that reads as a broken
        camera.
      */}
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
        <p className="flex items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>{problem}</span>
        </p>
      ) : null}

      {/*
        ALWAYS PRESENT, on every device — see the file header. It is also the reason this block
        stops `input` and `keydown` from propagating: the scanner is meant to be droppable into a
        stage form, and every record form in this app is `<form onInput={markDirty}
        onKeyDown={handleFormEnter}>`. Without the firewall, typing a code to look one up would arm
        the unsaved-changes prompt, and Enter would walk the form's focus instead of searching.
      */}
      <div className="grid gap-1.5" onInput={(event) => event.stopPropagation()} onKeyDown={(event) => event.stopPropagation()}>
        <label htmlFor="workshop-code-manual" className="field-label flex items-center gap-1.5">
          <Keyboard className="h-3.5 w-3.5" aria-hidden />
          Or type the code printed under the QR
        </label>
        <div className="flex flex-wrap gap-2">
          <input
            id="workshop-code-manual"
            className="field-input min-w-0 flex-1 font-mono uppercase"
            placeholder="DPW1 :A: …"
            autoComplete="off"
            autoCapitalize="characters"
            spellCheck={false}
            value={typed}
            onChange={(event) => setTyped(event.target.value)}
            onKeyDown={(event) => {
              if (event.key !== "Enter") return;
              // Explicit, because this input is not in a form of its own and may be inside one it
              // must never submit.
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
          Spaces and capitals do not matter. The four characters at the end are a check — if they do not match, the app will
          say so rather than open the wrong record.
        </p>
      </div>

      {/*
        `role="status"` and not a toast. A toast is `aria-live="polite"` in a corner that vanishes
        after five seconds, and both outcomes here are things the designer has to act on: open the
        record, or go and read the card again.
      */}
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
            <span className="font-medium">{outcome.tone === "found" ? "Found: " : ""}</span>
            {outcome.text}
            {outcome.detail ? <span className="block text-xs text-ink-500">{outcome.detail}</span> : null}
          </p>
        ) : null}
      </div>
    </section>
  );
}
