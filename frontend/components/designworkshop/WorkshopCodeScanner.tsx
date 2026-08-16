"use client";

/**
 * The one control that reads a workshop code, however the designer has it: point the camera at it,
 * upload a picture of it, drop or paste a picture of it, or type it.
 *
 * ── THREE WAYS IN, AND NONE OF THEM IS GATED ON A BROWSER API ────────────────────────────────
 *
 * This component used to gate BOTH the camera and the upload on `BarcodeDetector`, in writing, and
 * the refusal was reasoned: "every JavaScript QR decoder worth shipping is tens of kilobytes of
 * image processing that would sit in the bundle of every device — including the ones that have the
 * native detector — to serve the browser share that does not."
 *
 * THAT ARGUMENT RESTED ON A MEASUREMENT THAT HAD NEVER BEEN TAKEN, and when it was taken it said the
 * opposite. `lib/identityCardLocal.ts` records the probe, run on this machine on 2026-08-09:
 *
 * | browser                                                                   | `BarcodeDetector` |
 * |---------------------------------------------------------------------------|-------------------|
 * | Chrome 151, headed                                                        | **absent**        |
 * | Chrome 151, headed, `--enable-experimental-web-platform-features`         | **absent**        |
 * | Edge 151, headed and headless                                             | **absent**        |
 * | Chromium 151 (Playwright), incl. `--enable-blink-features=ShapeDetection` | **absent**        |
 *
 * Chrome's own documentation says barcode detection ships "on macOS, ChromeOS, and Android". The
 * machines this web client is actually used on are Windows laptops in a district office. So "the
 * browser share that does not have it" was not a minority being traded away — on this surface it was
 * EVERYBODY, and the cost was not a slower path but the TOTAL ABSENCE of both the camera and the
 * upload, on every laptop, every time. The two buttons were rendered behind `support === "yes"` and
 * a designer never saw either of them; what was left was a component whose only working input was
 * the keyboard, above a paragraph explaining that the browser could not read a QR code.
 *
 * `lib/qrDecode.ts` is now that decoder, hand-written against ISO 18004 and sharing every table with
 * `lib/qrEncode.ts` so the two halves cannot disagree, and `lib/qrImageDecode.ts` is the browser glue
 * that feeds it a file or a video frame. The native detector is still tried first where it exists,
 * because where it exists it is better — but nothing is offered or withheld on the answer.
 *
 * ── WHY UPLOAD IS NOT A NICETY ───────────────────────────────────────────────────────────────
 *
 * A camera scan needs a working camera, a granted permission, enough light, and a second device in
 * the room holding the code up. An upload needs a file: a screenshot, a photograph taken in the
 * morning before the artisan went home, a WhatsApp forward, a printed sheet photographed last week,
 * a laptop with no camera at all. In this application's field conditions the upload is frequently the
 * only route that exists — which is also why the same path accepts a DROPPED file and a PASTED
 * image. A code arriving over WhatsApp is screenshotted and pasted; making that person save the
 * screenshot to disk first is a step invented by the software.
 *
 * ── ALL FOUR ROUTES CONVERGE ON ONE FUNCTION ─────────────────────────────────────────────────
 *
 * {@link handleRawValue}. A decoded string is treated identically however it arrived, because the
 * alternative is four subtly different ideas of what a valid code is. The picture routes converge
 * one step earlier still, on `readFromFile`, so the camera, the button, the drop and the paste
 * cannot drift in how they explain a failure.
 *
 * ── THE CAMERA IS TURNED OFF, EVERY WAY OUT ──────────────────────────────────────────────────
 *
 * Stopping the tracks on unmount is the obvious one and the least likely to be needed; the ones that
 * actually bite are the designer who scans a card and navigates away in the same second, and the one
 * who switches to WhatsApp mid-scan and leaves the handset in a pocket with the camera light on and
 * the battery draining. So: the tracks are held in a ref (state would not survive the unmount path),
 * {@link stopCamera} is idempotent, and it is called from the effect cleanup, from a successful read,
 * from every failure, and from `visibilitychange`.
 *
 * ── A CODE THAT DOES NOT RESOLVE IS NOT TOLD IT ALMOST DID ───────────────────────────────────
 *
 * The API answers 404 rather than 403 for a record the caller may not see, precisely so that a caller
 * cannot learn which records exist by asking about them — and a scanner that said "that record exists
 * but is not yours" would undo that one photographed card at a time. Resolution is the caller's job
 * (this component takes a `resolve` function), and `unresolvedWorkshopCodeMessage` in
 * `lib/workshopCodes.ts` is the one sentence both outcomes share.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { AlertTriangle, Camera, ClipboardPaste, ImageUp, Keyboard, Loader2, ScanLine, Square } from "lucide-react";

import { decodeQrFromFile, decodeQrFromVideoFrame, type QrImageRefusal } from "@/lib/qrImageDecode";
import { decodeWorkshopCode, type WorkshopCodeRef } from "@/lib/workshopCodes";

/**
 * Refusals that mean "not from THIS frame" rather than "not from this card".
 *
 * WHY THE CAMERA MUST TREAT THESE DIFFERENTLY FROM AN UPLOAD, which is the same decoder answering
 * the same question. An upload is one deliberate act on one chosen picture, so every refusal is
 * final and must be shown. A camera produces five frames a second at a card the designer is still
 * bringing into position, and on the way there it will produce a great many frames where a symbol
 * is visible but half of it is motion-blurred (`UNREADABLE`) or sampled too coarsely to repair
 * (`DATA_UNRECOVERABLE`). Those are not answers about the card. Stopping the camera on the first one
 * would mean the scanner gave up, with "too damaged to repair", on a card that reads perfectly a
 * quarter of a second later — and the designer would be told their tag was ruined.
 *
 * The refusals NOT in this list are stable properties of the symbol itself: it is a payment code, or
 * a shipping label, or a multi-part code this app does not print. Another frame of the same thing
 * gives the same answer, so those stop the camera and are said out loud.
 */
const TRANSIENT_FRAME_REFUSALS: readonly QrImageRefusal[] = ["NO_SYMBOL", "UNREADABLE", "DATA_UNRECOVERABLE"];

/**
 * How many consecutive frames may locate a symbol and fail to read it before the scanner says so.
 *
 * Not zero, for the reason above; and not infinite, because a designer holding a card that genuinely
 * cannot be read — a folded tag, a courtyard too dark to focus in — would otherwise stare at a live
 * preview that never says anything, which is indistinguishable from a scanner that is not working.
 * Six frames is about a second and a half at {@link FRAME_INTERVAL_MS}: long enough that steadying
 * one's hand beats it, short enough that it arrives while the card is still in front of the lens.
 *
 * The message is shown WITHOUT stopping the camera, so it is advice rather than a verdict and the
 * very next frame can still succeed.
 */
const NEAR_MISSES_BEFORE_ADVICE = 6;

/** What the host made of a scanned reference. `ok: false` must never confirm that the id exists. */
export type ScanResolution = { ok: true; label: string; detail?: string } | { ok: false; message: string };

/**
 * How often a frame is handed to the decoder while the camera is open.
 *
 * The loop re-schedules itself AFTER each frame finishes rather than firing on a fixed interval, so
 * a slow frame on a cheap handset delays the next one instead of stacking a queue of decodes behind
 * it — which is how a preview goes from laggy to frozen.
 */
const FRAME_INTERVAL_MS = 220;

export function WorkshopCodeScanner({
  resolve,
  onResolved,
  description
}: {
  /** Turn a decoded reference into an answer. Called for every route, camera and keyboard alike. */
  resolve: (ref: WorkshopCodeRef) => Promise<ScanResolution>;
  /** Fired only for a reference the host resolved, so a caller can navigate or select a row. */
  onResolved?: (ref: WorkshopCodeRef, resolution: ScanResolution) => void;
  description?: string;
}) {
  const [scanning, setScanning] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<{ tone: "found" | "refused"; text: string; detail?: string } | null>(null);
  const [typed, setTyped] = useState("");
  const [dragging, setDragging] = useState(false);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  /** The hidden file input the "Upload a picture" button drives — see `readFromFile`. */
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  /** Guards the read path: a decoder can return the same card in three consecutive frames. */
  const handlingRef = useRef(false);
  /** Consecutive frames that located a symbol and could not read it. See {@link NEAR_MISSES_BEFORE_ADVICE}. */
  const nearMissRef = useRef(0);

  /**
   * Release the camera. Safe to call from anywhere, any number of times — which is what lets the
   * unmount cleanup, the stop button, the success path and the error paths all use one function
   * rather than four almost-identical ones that drift apart.
   */
  const stopCamera = useCallback(() => {
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;
    setScanning(false);
  }, []);

  // The one cleanup that is not optional. Its dependency is `stopCamera`, which is a stable
  // useCallback with no dependencies — so this registers once and tears down exactly on unmount.
  useEffect(() => stopCamera, [stopCamera]);

  // A handset that switches away mid-scan leaves the camera live, the indicator lit and the battery
  // draining, and nothing brings it back because the page is not unmounted.
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

  /** One decoded string, from any route, taken as far as it can honestly go. */
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

  /**
   * Read a code out of a PICTURE the designer already has — the shared destination of the upload
   * button, a dropped file and a pasted image.
   *
   * EVERY REFUSAL IS A SENTENCE, NEVER A SILENT NO-OP. `lib/qrImageDecode.ts` distinguishes a file
   * this browser cannot open (an iPhone HEIC, almost always) from a picture with no code in it from
   * a code that is real but not ours, and each of those leads somewhere different: re-save the file,
   * photograph it again with the whole square in frame, or stop trying. A single "could not read it"
   * would send all three people to do the same wrong thing, and a silent failure would have them
   * press the button again with the same file and then conclude the feature is broken.
   */
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

  // A screenshot pasted straight from the clipboard — the WhatsApp case, and the one where saving
  // the picture to disk first is a step the software invented. Bound to the window rather than to a
  // box the designer has to focus first, because a paste with nothing focused is what actually
  // happens; it is ignored while the camera is running or a read is already in flight.
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

  /**
   * Open the camera and read frames from it until one holds a code.
   *
   * NOT GATED ON A CAPABILITY PROBE. The browser distinguishes "you refused permission" from "there
   * is no camera" from "something else has it", and those three lead to completely different next
   * actions — so the honest design is to TRY and report what came back, rather than to hide the
   * button behind a guess. The one thing checked up front is `navigator.mediaDevices`, whose absence
   * means the page is not on a secure origin and no amount of pressing will help.
   */
  async function startCamera() {
    setProblem(null);
    setOutcome(null);
    handlingRef.current = false;
    nearMissRef.current = 0;

    if (!navigator.mediaDevices?.getUserMedia) {
      setProblem(
        "This browser will not open a camera on this page — that usually means the site is not being served over https. " +
          "Upload a picture of the code instead, or type the code printed under the QR."
      );
      return;
    }

    let stream: MediaStream;
    try {
      // `environment` is a REQUEST, not a guarantee — a laptop has only a front camera and will hand
      // one over anyway, which is correct: a designer holding a card up to a laptop lid is a real way
      // this gets used.
      stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: "environment" } } });
    } catch (error) {
      const name = error instanceof Error ? error.name : "";
      setProblem(
        name === "NotAllowedError" || name === "SecurityError"
          ? "This site is not allowed to use the camera. Open the site settings — the padlock or the camera icon at the left of the address bar — allow the camera, then press Scan again. You can also upload a picture of the code, or type the code printed under the QR."
          : name === "NotFoundError" || name === "OverconstrainedError"
            ? "No camera was found on this device. Upload a picture of the code instead, or type the code printed under the QR."
            : "The camera could not be opened. Check that nothing else is using it, then try again — or upload a picture of the code, or type the code printed under the QR."
      );
      return;
    }

    streamRef.current = stream;
    const video = videoRef.current;
    if (!video) {
      // The component unmounted between the permission prompt and the answer. The stream is real and
      // already open, so it has to be closed here or it stays on with nothing pointing at it.
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

    setScanning(true);

    const readFrame = async () => {
      timerRef.current = null;
      const element = videoRef.current;
      // `streamRef` and not `scanning`: this closure was created before the state update landed, so
      // it would capture `false` forever. The ref is the live answer to "is the camera still on".
      if (!element || !streamRef.current || handlingRef.current) return;

      let read: Awaited<ReturnType<typeof decodeQrFromVideoFrame>> = null;
      try {
        read = await decodeQrFromVideoFrame(element);
      } catch {
        // A single frame the decoder could not handle is normal (motion blur, a frame arriving
        // mid-resize). Failing the whole scan on one of them would make the scanner unusable in
        // exactly the handheld conditions it is for.
        read = null;
      }

      const again = () => {
        if (streamRef.current) timerRef.current = setTimeout(() => void readFrame(), FRAME_INTERVAL_MS);
      };

      // No frame yet. Not an answer about anything.
      if (!read) {
        again();
        return;
      }

      if (!read.ok && TRANSIENT_FRAME_REFUSALS.includes(read.reason)) {
        if (read.reason === "NO_SYMBOL") {
          // Nothing in view at all — the ordinary state of a camera pointed at a room. Say nothing,
          // and forget any near misses: the card has been moved out of frame, so advice about
          // holding it steadier is now advice about nothing.
          nearMissRef.current = 0;
        } else if (++nearMissRef.current >= NEAR_MISSES_BEFORE_ADVICE) {
          // A symbol has been in view for a second and a half and still cannot be read. The
          // decoder's own sentence already names the next action, so it is shown as it stands — and
          // the camera keeps running underneath it, because the next frame may well succeed.
          setProblem(read.message);
        }
        again();
        return;
      }

      handlingRef.current = true;
      // The camera goes off the moment there is a real answer: the designer's next act is to look at
      // it, and a live preview under it is a battery drain and a distraction.
      stopCamera();
      if (!read.ok) {
        // A stable property of the symbol rather than of the frame — somebody's payment code, a
        // shipping label. Another frame gives the same answer, so stop and say so.
        setProblem(read.message);
        return;
      }
      // Any near-miss advice from the frames on the way here is now stale and would sit above the
      // answer contradicting it.
      setProblem(null);
      await handleRawValue(read.text);
    };

    timerRef.current = setTimeout(() => void readFrame(), FRAME_INTERVAL_MS);
  }

  return (
    <section
      className="panel grid gap-3 p-4"
      aria-label="Scan a card or tag"
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
      <div className="flex flex-wrap items-center gap-2">
        <ScanLine className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
        <h2 className="text-sm font-medium text-ink-900">Scan a card or tag</h2>
      </div>
      {description ? <p className="text-xs leading-5 text-ink-500">{description}</p> : null}

      {/*
        BOTH BUTTONS, ALWAYS, ON EVERY DEVICE. Neither is behind a capability probe — see the file
        header for the measurement that removed the last reason to hide them. The camera reports what
        the browser said when it is pressed; the upload works on every browser that can open a
        picture at all.
      */}
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          className="field-button"
          onClick={() => (scanning ? stopCamera() : void startCamera())}
          disabled={busy}
          data-testid="workshop-code-scan"
        >
          {scanning ? <Square className="h-4 w-4" aria-hidden /> : <Camera className="h-4 w-4" aria-hidden />}
          {scanning ? "Stop the camera" : "Scan with the camera"}
        </button>
        <button
          type="button"
          className="field-button"
          onClick={() => fileInputRef.current?.click()}
          disabled={busy || scanning}
          data-testid="workshop-code-upload"
        >
          <ImageUp className="h-4 w-4" aria-hidden />
          Upload a picture
        </button>
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*"
          className="hidden"
          data-testid="workshop-code-file"
          onChange={(event) => {
            const file = event.target.files?.[0];
            // Cleared BEFORE the read, so choosing the same file twice fires `change` again — a
            // designer who re-picks the photo after fixing the framing expects a second attempt, and
            // an input still holding the value is silent.
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
          <span className="text-xs text-ink-500">Hold the card 10–15 cm from the lens, with the whole square in view.</span>
        ) : null}
      </div>

      <p className="flex items-start gap-2 text-xs leading-5 text-ink-500">
        <ClipboardPaste className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
        <span>
          A picture works as well as the card itself — a screenshot, a photo taken earlier, or one forwarded to you. You can
          also drop it here or paste it with Ctrl+V.
        </span>
      </p>

      {dragging ? (
        <p className="rounded-md border border-dashed border-purple-300 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-700">
          Drop the picture to read the code in it.
        </p>
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
        <p
          className="flex items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800"
          data-testid="workshop-code-problem"
          role="status"
        >
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>{problem}</span>
        </p>
      ) : null}

      {/*
        ALWAYS PRESENT, on every device. It is the keyboard route, the answer for a cracked lens and a
        laminated card with a glare across it, and the shortest path of all when the card is in the
        hand. It is also the reason this block stops `input` and `keydown` from propagating: the
        scanner is meant to be droppable into a stage form, and every record form in this app is
        `<form onInput={markDirty} onKeyDown={handleFormEnter}>`. Without the firewall, typing a code
        to look one up would arm the unsaved-changes prompt, and Enter would walk the form's focus
        instead of searching.
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
