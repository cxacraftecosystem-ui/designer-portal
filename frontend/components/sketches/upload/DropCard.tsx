"use client";

/**
 * An upload card that takes a drop AND opens a file picker — the owner's third request.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * WHAT "REPLACE THE UPLOAD BUTTON WITH A DRAG-AND-DROP CARD" HAD TO MEAN HERE
 * ────────────────────────────────────────────────────────────────────────────
 *
 * Not a swap. The card is the button PLUS a drop target, and that is the house answer rather than a
 * softening of the request: `components/forms/MediaCaptureField.tsx:470-484` is the repository's
 * canonical file drop and it WRAPS its `.file-trigger` buttons in the drop zone, closing with the
 * sentence "Drag and drop files here, or use the buttons above."
 *
 * The reason is stated in this directory's own sibling, `components/sketches/RankableList.tsx:14-25`,
 * about the drag API generally: **`dragstart`/`dragover` do not fire for touch at all on Android
 * Chrome**, and "a gesture that works on a laptop and silently does nothing on the phone the fieldwork
 * is done on is worse than no gesture." For a *file* drop that fact cannot even be worked around —
 * there is no touch equivalent of dropping an operating-system file onto a page, because on a handset
 * there is no second window to drag it out of. So the tap/click path is the primary one and the drop
 * is an accelerator for the laptop. A card that had only the drop would be a regression on the button
 * it replaced, on every device the fieldwork actually happens on.
 *
 * ── THE KEYBOARD PATH IS A REAL BUTTON, AND THAT IS NOT THE OBVIOUS CHOICE ──────────────────────
 *
 * Four treatments already exist in this repository and two of them are keyboard traps in one direction
 * or the other. `.file-trigger` (`globals.css:398`) is a `<label>` wrapping a hidden input, and
 * `MediaCaptureField` gives that input `className="hidden"` — `display: none`, which is not focusable,
 * and a `<label>` is not focusable either, so those triggers cannot be reached by Tab at all.
 * `UploadDialog` uses `sr-only` instead, which is focusable but draws its focus ring somewhere nobody
 * can see it.
 *
 * So this uses the third pattern: `components/designworkshop/WorkshopCodeScanner.tsx:437-455` — a real
 * `<button className="field-button">` that clicks a hidden input through a ref. One tab stop, the
 * global purple focus ring drawn where the eye is, an accessible name this file controls, and a name a
 * screen reader reads as a button rather than as "file, blank". The input carries
 * `className="sr-only" tabIndex={-1} aria-hidden="true"` for `IdentityCardCapture.tsx:473-493`'s
 * recorded reason: `sr-only` rather than `hidden` because "a `display: none` input cannot be clicked
 * programmatically in every browser", and `tabIndex={-1}` because a second tab stop on an input that
 * looks like nothing is a keyboard trap with no label to read out.
 *
 * ── WHY THE CARD ITSELF IS NOT A `[role="button"]` ──────────────────────────────────────────────
 *
 * It would double every announcement (the card and the button inside it), and it would make the whole
 * area a click target — including the chosen-file list and the refusal sentence, so a reader clicking
 * to select the name of a file that was refused would reopen the dialog. The drop zone is a passive
 * surface; the button is the control.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * REFUSING THE WRONG FILE
 * ────────────────────────────────────────────────────────────────────────────
 *
 * `accept` is a filter on the dialog and NOT a rule — every designer can switch it to "All files", and
 * a drop bypasses it entirely. So {@link DropCardProps.validate} is what actually decides, it runs on
 * the files that really arrived, and a refusal is a sentence naming the file rather than silence. This
 * is `PrototypeModelField.chooseFrames`'s rule applied to every card: the good files still go, the
 * refused ones are named, and nothing is dropped without being mentioned — §1.10 of the frontend
 * guide, "truncation, caps and skipped work must be stated on screen".
 */

import { useCallback, useId, useRef, useState, type ReactNode, type RefObject } from "react";
import { AlertTriangle, Upload } from "lucide-react";

export interface DropCardProps {
  /** The `field-label` above the card. Also what the button's own name is built from. */
  label: string;
  /** The button's words. Android-style plain verb phrases — "Choose a photograph". */
  buttonLabel: string;
  /**
   * The `accept` attribute for the dialog.
   *
   * A FILTER, NOT THE RULE. See {@link validate}, which is the rule. Passing one without the other is
   * how a .mov reaches an IMAGE_LIST.
   */
  accept: string;
  /** What this card takes, in words, shown under the button and read out as the button's description. */
  acceptSentence: string;
  multiple?: boolean;
  disabled?: boolean;
  /**
   * @returns a sentence explaining why this file cannot be taken, or `null` to take it.
   *
   * Called for every file from both routes — the dialog and the drop.
   */
  validate?: (file: File) => string | null;
  /** Handed only the files that passed {@link validate}. Never called with an empty array. */
  onFiles: (files: File[]) => void;
  /** Anything the caller wants inside the card under the button — the chosen file, a warning, a tick. */
  children?: ReactNode;
  /**
   * The card's own button, for a caller that has to put focus back on it.
   *
   * ── WHY A CALLER EVER NEEDS THIS, WHICH IS NOT OBVIOUS FROM HERE ────────────────────────────
   *
   * Every caller that renders a control INSIDE this card through {@link children} — "Put this
   * photograph away" on `SharedPhotoField`, "Go back to the photograph chosen above" on
   * `MeasureFromPhotoCard` — has written a button that destroys itself: pressing it clears the
   * held photograph, and the block those buttons live in is rendered only while there IS one. The
   * browser then drops focus to `<body>`, so a keyboard reader has to tab from the top of the
   * document back to where they were, and a screen reader is told nothing happened at all. That is
   * the same failure `MeasureFromPhotoCard.collapse` and `SketchTraceField`'s open/close effect
   * each move focus deliberately to avoid, and §7.8's rule about `closeSheet()` returning focus to
   * the hamburger.
   *
   * THE BUTTON ABOVE IS THE RIGHT LANDING PLACE rather than the card, the label or the accept
   * sentence: it is the only element in this card that is focusable, it survives every one of those
   * presses, and it is the control the designer would reach for next — having put a photograph
   * away, the next act is choosing another. A `tabIndex={-1}` target invented on the card root would
   * be a second focusable thing that means nothing to a reader who lands on it.
   *
   * OPTIONAL, AND THE CARD DOES NOT MOVE FOCUS ITSELF. Only a caller knows whether the control it
   * put inside is about to disappear; a card that grabbed focus on every `onFiles` would take it
   * away from a designer who is still working further down the panel.
   */
  buttonRef?: RefObject<HTMLButtonElement | null>;
}

export function DropCard({
  label,
  buttonLabel,
  accept,
  acceptSentence,
  multiple,
  disabled,
  validate,
  onFiles,
  children,
  buttonRef
}: DropCardProps) {
  const cardId = useId();
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [dragging, setDragging] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  /**
   * How deep the pointer is inside the card, so `dragleave` from a CHILD does not un-highlight it.
   *
   * `dragenter`/`dragleave` fire per element, not per zone: crossing from the card onto the button
   * inside it is a `dragleave` on the card followed by a `dragenter` on the button. Tracking a boolean
   * — which `MediaCaptureField` does — makes the highlight flicker off every time the pointer passes
   * over the button, which is the middle of the card and exactly where somebody aims. A counter is the
   * standard fix and costs one ref.
   */
  const depth = useRef(0);

  const take = useCallback(
    (files: File[]) => {
      if (files.length === 0) {
        // A drop can carry no files at all — dragged text, a link, a folder on some platforms. Saying
        // so is the difference between a card that refused and a card that is broken.
        setRefusal(
          "Nothing that was dropped here is a file. Drag a file out of a folder, or use the button to " +
            "choose one."
        );
        return;
      }
      const accepted: File[] = [];
      const refused: string[] = [];
      for (const file of files) {
        const why = validate ? validate(file) : null;
        if (why === null) accepted.push(file);
        else refused.push(`${file.name} — ${why}`);
      }
      // SINGLE-FILE CARDS TAKE THE FIRST AND SAY THEY DID. Dropping three files on a card that holds
      // one is an ordinary mistake, and quietly using one of them is the version of this that looks
      // like it worked.
      const extra = !multiple && accepted.length > 1 ? accepted.length - 1 : 0;
      const taken = multiple ? accepted : accepted.slice(0, 1);

      const notes: string[] = [];
      if (refused.length > 0) {
        notes.push(
          refused.length === 1
            ? `One file was not taken: ${refused[0]}.`
            : `${refused.length} files were not taken: ${refused.join("; ")}.`
        );
      }
      if (extra > 0) {
        notes.push(
          `This holds one file, so the first was used and ${
            extra === 1 ? "the other one was" : `the other ${extra} were`
          } left out.`
        );
      }
      setRefusal(notes.length > 0 ? notes.join(" ") : null);
      if (taken.length > 0) onFiles(taken);
    },
    [multiple, onFiles, validate]
  );

  return (
    <div className="grid gap-1">
      <span className="field-label" id={`${cardId}-label`}>
        {label}
      </span>
      {/*
        NOT `overflow-hidden`, and that is load-bearing rather than an omission. The global focus ring
        is an `outline` at `outline-offset: 2px` drawn OUTSIDE the border box (§5 of the frontend
        guide), so a card that clipped its overflow would erase the ring of the full-width button
        inside it on three sides. The guide's own step card carries the same note for the same reason.

        `border-2 border-dashed border-line-200` and never a bare `border`: preflight's `border` is the
        literal `#e5e7eb`, which does not invert under `data-theme="dark"`. The dragging state names its
        colour too — `ring-2` alone would be preflight's blue.

        The transition is CSS, so `globals.css`'s two reduced-motion sources neutralise it without this
        component knowing anything about the preference.
      */}
      <div
        className={
          dragging
            ? "grid gap-2 rounded-lg border-2 border-dashed border-purple-600 bg-purple-50 p-3 transition"
            : "grid gap-2 rounded-lg border-2 border-dashed border-line-200 bg-surface-50 p-3 transition"
        }
        onDragEnter={(event) => {
          if (disabled) return;
          event.preventDefault();
          depth.current += 1;
          setDragging(true);
        }}
        onDragOver={(event) => {
          if (disabled) return;
          // BOTH `dragover` AND `dragenter` MUST `preventDefault()`, or the browser refuses the drop
          // and animates the file flying back to where it came from. `dropEffect` is what makes the
          // cursor say "copy" rather than "move" — a file dropped on a web page is never moved.
          event.preventDefault();
          if (event.dataTransfer) event.dataTransfer.dropEffect = "copy";
        }}
        onDragLeave={() => {
          if (disabled) return;
          depth.current = Math.max(0, depth.current - 1);
          if (depth.current === 0) setDragging(false);
        }}
        onDrop={(event) => {
          if (disabled) return;
          event.preventDefault();
          depth.current = 0;
          setDragging(false);
          take(Array.from(event.dataTransfer?.files ?? []));
        }}
      >
        <div className="flex flex-wrap items-center gap-2">
          <button
            ref={buttonRef}
            type="button"
            className="field-button"
            disabled={disabled}
            aria-describedby={`${cardId}-accept`}
            onClick={() => inputRef.current?.click()}
          >
            <Upload className="h-4 w-4" aria-hidden />
            {buttonLabel}
          </button>
          {/*
            THE DROP IS ANNOUNCED AS TEXT, NOT ONLY AS A HIGHLIGHT. §1.5 of the frontend guide: a
            signal that exists only as motion or colour is a signal a reduced-motion or colour-blind
            reader never gets. The sentence changes when a drag is over the card, so the state is
            readable as words as well as by the border.
          */}
          <span className="text-xs text-ink-500">
            {dragging ? "Let go to use this file." : "…or drag a file onto this card."}
          </span>
        </div>

        <input
          ref={inputRef}
          type="file"
          accept={accept}
          multiple={multiple}
          /*
            `sr-only` RATHER THAN `hidden`, AND `tabIndex={-1}` RATHER THAN A TAB STOP. Both are
            recorded decisions elsewhere in this repository: `IdentityCardCapture.tsx` uses `sr-only`
            because "a `display: none` input cannot be clicked programmatically in every browser", and
            `RichTextEditor.tsx` gives its hidden input `tabIndex={-1}` because a second tab stop for
            "an input that looks like nothing would be a keyboard trap with no label a screen reader
            could read out". The button above is the tab stop and the accessible control.
          */
          className="sr-only"
          tabIndex={-1}
          aria-hidden="true"
          disabled={disabled}
          onChange={(event) => {
            const files = Array.from(event.target.files ?? []);
            // CLEARED BEFORE HANDLING, so choosing the same file again fires `change` again.
            // `onChange` does not fire for an unchanged value, and a designer whose first attempt was
            // refused presses the same button and expects something to happen.
            // `WorkshopCodeScanner.tsx:452` and `PrototypeModelField.tsx:345` both do this and both
            // say why; `SketchTraceField` did not, which is the inconsistency this card closes.
            event.target.value = "";
            take(files);
          }}
        />

        <p id={`${cardId}-accept`} className="text-xs leading-4 text-ink-500">
          {acceptSentence}
        </p>

        {children}
      </div>

      {refusal ? (
        <p
          role="alert"
          className="flex items-start gap-2 rounded-md border border-red-200 bg-error-100 px-2 py-1.5 text-xs leading-4 text-error-600"
        >
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>{refusal}</span>
        </p>
      ) : null}
    </div>
  );
}
