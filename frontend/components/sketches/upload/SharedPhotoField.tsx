"use client";

/**
 * The ONE photograph the Sketches half of the UPLOAD tab works from.
 *
 * ── THE DEFECT THIS CLOSES, IN THE WORDS OF THE PERSON WHO HIT IT ───────────────────────────────
 *
 * "A designer photographs a sketch once and then has to upload it twice." That was true, and it was
 * worse than it sounds. There was never a second picker to blame: the tracing panel owned the ONLY
 * `<input type="file">` on this half (`SketchTraceField`'s own `DropCard`), and the measuring card
 * owned none at all — it could see a photograph only after it had been written into the chosen row
 * and read back out of IndexedDB. So the two "uploads" were the SAME bytes taking two different
 * routes: once into the trace panel's decoder, and again — minutes later, after an attach, a
 * `putDraftStage`, a sync pass and a reload — into `useMeasurablePhotos`. A designer who wanted to
 * measure before filing had to file first, and a designer who filed to the wrong row could not see
 * their photograph in the measuring card at all.
 *
 * This card is the single entry point that makes both panels below work from one pick. It CHOOSES
 * and it FILES NOTHING, which is the whole of its contract and is stated on screen as well as here:
 * the panels underneath own the attach buttons, they own the sentences about what lands where, and
 * `UploadTabHost` owns the two-phase "it is on this device / the repository has it" report. Nothing
 * about that plumbing moved, because moving it would have meant a second upload path in an
 * application whose whole point is working offline (`UploadTabPanel`'s header).
 *
 * ── WHY IT IS A `DropCard` AND NOT A BARE INPUT OR A STYLED DIV ────────────────────────────────
 *
 * `DropCard` is this directory's file control and it already answers the accessibility question this
 * card would otherwise have to answer again: a real `<button>` as the tab stop, the input `sr-only`
 * and `tabIndex={-1}` so it is neither a keyboard trap nor a second unlabelled stop, a `field-label`
 * naming the card, an accept sentence wired to the button through `aria-describedby`, the drop
 * announced as WORDS as well as by a border colour, and `event.target.value` cleared so re-choosing
 * a refused file fires `change` again. A drop zone that is only a `<div>` is not a control, and this
 * card does not re-litigate any of that — it passes the same `validate` the tracing panel used to
 * pass, so exactly the same files are refused with exactly the same sentences as before the picker
 * moved out.
 */

import { useRef } from "react";
import { X } from "lucide-react";

import { DropCard } from "./DropCard";
import { DECODE_MAX_EDGE_PX, TRACEABLE_ACCEPT, TRACEABLE_IMAGE_TYPES } from "./decodeToPixels";
import type { ChosenPhotograph } from "./UploadTabPanel";

export interface SharedPhotoFieldProps {
  /**
   * What the host has chosen, with its object URL and — once the decode has settled — its pixels.
   *
   * NULL IS "NOTHING CHOSEN YET" AND IS AN ORDINARY STATE, not an error: the tab opens in it. The
   * card says what a photograph would be for rather than showing an empty frame.
   */
  photograph: ChosenPhotograph | null;
  /** The registry label of the field a photograph is filed into, quoted so the copy is checkable. */
  imageLabel: string;
  /** Which sketch row the panels below would file it to, as the designer sees it named. */
  rowName: string | null;
  /**
   * Whether {@link photograph} is already on {@link rowName}.
   *
   * THE HOST ANSWERS THIS BECAUSE IT IS A CLAIM ABOUT A ROW. "Filed" is true of the sketch the
   * photograph was attached to and false of the next one down the picker, so the answer changes when
   * the designer moves the row picker and nothing about the photograph changed — and the same fact
   * decides whether the measuring card below lists this picture as one it is holding or as one the
   * row already has. One expression owns it (`UploadTabHost.sketchPhotoOnRow`) so the two cards
   * cannot disagree; deriving it again here is how they would.
   */
  filed: boolean;
  disabled?: boolean;
  /**
   * A new photograph, or `null` to put the current one away.
   *
   * THE HOST DECIDES WHAT AN IDENTITY IS. This card never holds the file itself, because the object
   * URL that the measuring card draws from and the decode that the tracing panel traces from both
   * have to be created and released by ONE owner — the rule `UploadTabHost` states beside
   * `useMeasurablePhotos` and pays for when it is broken ("released while an `<img>` is still
   * reading them … looks like a photograph that vanished").
   */
  onChoose: (file: File | null) => void;
}

/** What a decoded photograph measures, in the words the tracing panel used before the picker moved. */
function sizeSentence(photograph: ChosenPhotograph): string {
  if (photograph.problem !== null) return photograph.problem;
  const pixels = photograph.pixels;
  if (pixels === null) return "Reading…";
  if (pixels.sourceWidth !== pixels.width || pixels.sourceHeight !== pixels.height) {
    return `Read at ${pixels.width}x${pixels.height}, reduced from ${pixels.sourceWidth}x${pixels.sourceHeight}.`;
  }
  return `${pixels.width}x${pixels.height}.`;
}

/**
 * What has and has not happened to the photograph on screen, in one sentence.
 *
 * ── THE STATE THIS CARD USED TO GET WRONG, AND IT GOT IT WRONG OUT LOUD ─────────────────────────
 *
 * There were three sentences here and none of them covered a filed photograph, so the card went on
 * printing "Nothing has been filed yet" after the designer had pressed the button that filed it.
 * Both of the tracing panel's buttons do — "Attach the photograph only" and "Add the line art to …"
 * each route the same bytes through `onAttachSource` (`SketchTraceField.fileSourceOnce`) — and the
 * measuring card at the foot of the section changes its own wording at that instant, from "the
 * photograph chosen above, which is not attached to anything yet" to "Measuring against the
 * photograph attached to “…”". So the section showed two cards making opposite claims about one
 * picture, on a tab whose entire subject is whether a designer's file is safe. That is the failure
 * the two-phase notices under the row pickers exist to prevent, arriving one card higher up.
 *
 * ── WHY A FUNCTION AND NOT A NESTED TERNARY IN THE JSX ─────────────────────────────────────────
 *
 * It was already a two-deep ternary at three states; at five it is the shape nobody reads, and the
 * one that shipped the missing state in the first place. A function names each case, which is what
 * lets a reader check that every one of them is covered — the same move `sizeSentence` above makes
 * for the same reason.
 *
 * `rowName` IS NULL-CHECKED IN THE FILED ARM TOO, WHICH LOOKS IMPOSSIBLE AND IS NOT. A photograph
 * can only be filed onto a row that was chosen, but the row can go away underneath it: deleting the
 * sketch that was filed to leaves the host holding a row key with no row to name. Naming a row this
 * card cannot see would be worse than not naming one, so the fact survives and the name drops.
 */
function stateSentence(hasPhotograph: boolean, filed: boolean, imageLabel: string, rowName: string | null): string {
  if (!hasPhotograph) {
    return "Choose it once. The tracing panel and the measuring panel below both work from the same photograph, and neither of them files anything until you press a button in it.";
  }
  if (filed) {
    /*
      WHAT IS SAID AFTER A FILE, AND WHY IT IS TWO FACTS RATHER THAN A TICK. A tick would answer the
      question the designer just asked and leave the one they ask next — "do I have to choose it
      again to trace it?" — which is exactly the second upload this whole arrangement was raised to
      end. `SketchTraceField` keeps its `file` and its decode through `setOpen(false)` on purpose, so
      the honest answer is that the work left is line art and that filing it does not write the
      photograph a second time (`fileSourceOnce` returns `"already"`, calls no host).

      IT NAMES THE TRACING PANEL AND NOT "BOTH PANELS BELOW", which the unfiled sentence above can
      safely say and this one cannot. Once the photograph is on the row the measuring card stops
      being handed it and reads the ROW's copy instead (`UploadTabHost.sketchWorking` returns null the
      moment `sketchPhotoOnRow` is true) — the same picture, by a different route, and a route that
      can fail on its own and say so. Claiming here that it is still working from this photograph
      would be this card asserting something about a read it did not perform.
    */
    return rowName
      ? `This photograph is filed — “${imageLabel}” on “${rowName}” holds it, exactly as it was taken. The tracing panel below is still holding it, so line art can be traced from it without choosing it again, and filing that drawing does not write the photograph a second time.`
      : `This photograph is filed, exactly as it was taken. The tracing panel below is still holding it, so line art can be traced from it without choosing it again, and filing that drawing does not write the photograph a second time.`;
  }
  return rowName
    ? `Both panels below work from this photograph. Nothing has been filed yet — “${imageLabel}” on “${rowName}” is written only when a button in one of them is pressed.`
    : `Both panels below work from this photograph. Nothing has been filed yet, and nothing can be until a sketch is chosen in the picker above.`;
}

export function SharedPhotoField({
  photograph,
  imageLabel,
  rowName,
  filed,
  disabled,
  onChoose
}: SharedPhotoFieldProps) {
  /*
    WHERE FOCUS GOES WHEN THE CONTROL UNDER IT DESTROYS ITSELF.

    "Put this photograph away" is rendered only while there IS a photograph, so pressing it unmounts
    the very button the press landed on and the browser drops focus to `<body>` — a keyboard reader
    then tabs from the top of the document to get back to a card they were standing in, and a screen
    reader is told nothing at all. `MeasureFromPhotoCard` moves focus deliberately on both its own
    open and close for exactly this, and so does `SketchTraceField`; this control had neither half.

    THE PICKER'S BUTTON AND NOT A TARGET INVENTED HERE. It survives the press, it is the only
    focusable thing left in this card, and it is what the designer would reach for next — having put
    a photograph away, the next act is choosing another. Focusing it inside the handler is safe and
    needs no effect: `onChoose` is the host's state setter, so the unmount happens on a later render
    and this call lands on an element that is still in the document and still will be.
  */
  const pickerRef = useRef<HTMLButtonElement | null>(null);

  return (
    <DropCard
      buttonRef={pickerRef}
      label="Photograph of the sketch"
      /*
        THE BUTTON SAYS WHICH OF THE TWO ACTS IT IS. "Choose a photograph" over a card that already
        holds one reads as "add another", and this card holds exactly one — pressing it REPLACES what
        both panels below are working from, which is a thing to be told before the dialog opens
        rather than after. The host's own "already holds a Sketch image … attaching another REPLACES
        it" notice makes the same move one level up.
      */
      buttonLabel={photograph ? "Choose a different photograph" : "Choose a photograph"}
      accept={TRACEABLE_ACCEPT}
      acceptSentence={`${TRACEABLE_IMAGE_TYPES}, wherever this browser can read them. Anything longer than ${DECODE_MAX_EDGE_PX}px on its long edge is reduced to that before tracing — the trace was never going to run above it.`}
      disabled={disabled}
      /*
        THE SAME RULE THE TRACING PANEL USED TO CARRY, MOVED RATHER THAN REWRITTEN, so the files this
        half refuses and the sentences it refuses them with did not change when the picker did.
        `accept="image/*"` is what the dialog offers and a drop ignores it entirely, so this is what
        actually decides — and it decides PERMISSIVELY on purpose: a phone camera roll hands over
        HEIC and AVIF with an EMPTY `type` on several platforms, so refusing anything without an
        image MIME type would refuse the commonest file on an iPhone. `decodeToPixels` answers "this
        browser cannot read that image" in a sentence of its own, which is the honest place for that
        judgement — it is the code that tried, and its answer is printed under the preview below.

        What IS refused here is the file that is definitely not a photograph of a sheet — a video, a
        PDF, a document — and the SVG, for the reason `decodeToPixels`'s own header gives at length:
        tracing vector art is a round trip that can only lose.
      */
      validate={(candidate) => {
        if (candidate.type === "image/svg+xml") {
          return "an SVG is already vector art, and rasterising it to trace it back can only lose detail. Attach it with the ordinary picker instead.";
        }
        if (candidate.type === "" || candidate.type.startsWith("image/")) return null;
        return `this is ${candidate.type}, not a photograph. A drawing has to be traced from an image.`;
      }}
      onFiles={(files) => {
        const chosen = files[0];
        if (chosen) onChoose(chosen);
      }}
    >
      {photograph ? (
        /*
          ── ONE PREVIEW, NAMED, AND IT IS NOT DECORATION ──────────────────────────────────────────

          Two panels now work from one pick, and the failure that arrangement invites is a designer
          tracing one photograph while measuring another without either card saying so. The picture
          is the cheapest possible answer to "which one am I working from" — it costs nothing,
          because the object URL beside it is already alive for the measuring card — and the file
          name under it is the half a reader who cannot see the picture gets.

          `alt=""` DELIBERATELY. The file name sits immediately beside it in text, so a description
          on the image would be the same fact announced twice; §1.5's rule is that the signal must
          exist as words, not that every image must repeat them.
        */
        <div className="flex items-start gap-3 rounded-md border border-line-200 bg-card p-2">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={photograph.url}
            alt=""
            className="h-16 w-16 shrink-0 rounded-sm border border-line-200 object-cover"
          />
          <div className="min-w-0 flex-1">
            <p className="truncate text-xs font-medium text-ink-900">{photograph.file.name}</p>
            <p className="mt-0.5 text-xs leading-4 text-ink-500">{sizeSentence(photograph)}</p>
            {/*
              PUTTING IT AWAY IS A CONTROL, NOT A RE-PICK. Without this the only way out of a wrong
              photograph is to choose another one — which is fine when there IS another one and is a
              dead end when the designer simply wants both panels back to their empty state before
              walking away from the tab. The panels below reset with it: this clears the same host
              state the pick set, so the tracing panel loses its drawing and the measuring card loses
              its marks together rather than one of them keeping a stale answer.

              WITH ONE EXCEPTION, WHICH IS THE POINT OF THE EXCEPTION AND NOT A HOLE IN THIS ONE. A
              measuring card that has been pointed at its OWN photograph — `MeasureFromPhotoCard`'s
              "Measure a different photograph", the ruler shot that was never the sheet being traced
              — keeps it, because that photograph was never this card's to hold and putting away
              something the designer did not pick here would be this control reaching into a panel
              that has its own. That panel says on screen which picture it is on and carries its own
              way back ("Go back to the photograph chosen above"). The sentence above is therefore
              about the SHARED photograph and everything derived from it, which is all this card owns.
            */}
            <button
              type="button"
              className="mt-1 inline-flex items-center gap-1.5 text-xs font-medium text-ink-500 underline disabled:cursor-not-allowed disabled:opacity-60"
              disabled={disabled}
              onClick={() => {
                onChoose(null);
                pickerRef.current?.focus();
              }}
            >
              <X className="h-3.5 w-3.5" aria-hidden />
              Put this photograph away
            </button>
          </div>
        </div>
      ) : null}

      {/*
        WHAT THE PICK DOES AND — LOUDER — WHAT IT DOES NOT DO. This card writes nothing anywhere:
        every route from here to the record runs through a button in one of the two panels below,
        each of which names the field it writes. Saying so here is what stops "I uploaded it" meaning
        two different things to the designer and to the repository, which is the confusion the
        two-phase notices under the row pickers exist to prevent one level up.

        AND — SINCE THE CARD KEEPS THE PHOTOGRAPH AFTER IT HAS BEEN FILED — WHAT HAS ALREADY
        HAPPENED TO IT. The pick deliberately survives the attach (`UploadTabHost.sketchPhotoFiled`
        says why: the tracing panel is still holding its decode and its drawing), so this line is on
        screen in a state where "nothing has been filed" is false. See `stateSentence`.

        `aria-live="polite"` BECAUSE THE SENTENCE CHANGES UNDER A PRESS THAT HAPPENS ELSEWHERE. The
        button that files the photograph is in the panel BELOW this card, so a designer using a
        screen reader presses "Attach the photograph only", hears that panel's own answer, and would
        never learn that the card above them now says something different — the text they are being
        told about is off in another region they have already walked past. `atomic` because the two
        halves of it only mean anything together: "filed" read without "the panels are still working
        from it" is the half that sends somebody back to the picker.
      */}
      <p aria-live="polite" aria-atomic="true" className="text-xs leading-5 text-ink-500">
        {stateSentence(photograph !== null, filed, imageLabel, rowName)}
      </p>
    </DropCard>
  );
}
