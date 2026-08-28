"use client";

/**
 * "Measure a dimension from a photograph", on Sketches & Prototypes → UPLOAD.
 *
 * ── WHAT WAS ACTUALLY MISSING, ESTABLISHED RATHER THAN ASSUMED (2026-08-28) ─────────────────────
 *
 * The report was "the card is completely missing from the web view". Two thirds of that is not so,
 * and the difference is what decides what this file is allowed to contain — a third copy of the
 * geometry would have been the wrong answer to a complaint about a missing MOUNT.
 *
 *   * `components/designworkshop/PhotoMeasureField.tsx` EXISTS, and is mounted on every stage form
 *     whose entity has an image field and a length field:
 *         grep -n "PhotoMeasureField" frontend/components/designworkshop/FieldInput.tsx
 *     answered :82 (import) and :1669 (mount) on 2026-08-28.
 *   * `components/media/RecordPhotoMeasure.tsx` EXISTS, and is mounted by both record forms:
 *         grep -rn "RecordPhotoMeasure" frontend/components/forms/
 *     answered ProductForm.tsx:27,:927 and ToolForm.tsx:27,:1090 the same day.
 *   * What genuinely had no measuring surface is THIS one. Before this file,
 *         grep -rn "PhotoMeasure" frontend/components/sketches/
 *     answered exactly one line — a comment in `upload/FramePanel.tsx:196` citing the module for how
 *     it holds a reference length — and no mount anywhere. The UPLOAD tab is where a designer works
 *     on a sketch or a prototype WITHOUT opening a stage form, which on a day in a courtyard is most
 *     of the time, and from there the feature did not exist.
 *
 * The handset has never had that gap, which is the parity argument for closing it here rather than
 * sending the designer to a stage form: `DwPhotoMeasurePanel` is mounted from the stage form
 * (`FieldRenderer.kt:1853`) AND from `ui/RecordMeasureField.kt:408` — measure from where the
 * designer already is. This file is that second mount on this client.
 *
 * ── IT NEVER WRITES A DIMENSION BY ITSELF ──────────────────────────────────────────────────────
 *
 * Both clients state that rule under that heading — `PhotoMeasureField.tsx:13` ("IT NEVER WRITES A
 * DIMENSION") and `DwPhotoMeasureField.kt:115` ("IT NEVER WRITES A DIMENSION BY ITSELF") — and it
 * survives this mount unchanged, because this file adds no write of its own: every path to
 * {@link MeasureFromPhotoCardProps.onPropose} runs through a button inside `PhotoMeasureField` that
 * the designer pressed, and the sentence is repeated ON SCREEN as well as here. The reason is worth
 * carrying: the number is a proposal with an error bar, the registry has a column for the dimension
 * and none for the doubt, and `merge_entry_provenance` stamps whoever pressed Save with their name —
 * so a number that arrived without a press would be recorded as a named person's measurement.
 *
 * ── WHY THIS IS AN ADAPTER AND NOT A PANEL ─────────────────────────────────────────────────────
 *
 * The geometry, the marks, the pinch-zoom, the error bar and the propose buttons are
 * `PhotoMeasureField`'s and are not reimplemented, copied or wrapped in a fork. What this file adds
 * is only what that component cannot know about from a stage form:
 *
 *   1. an ACCORDION with a collapse control at the BOTTOM as well as the top (see below);
 *   2. the "there is no photograph on this row yet" sentence, because `PhotoMeasureField` answers
 *      that case with `return null` (`PhotoMeasureField.tsx:532`) — correct on a stage form, where
 *      the panel sits underneath the very picker that would fill it, and wrong here, where the
 *      photograph is on a row chosen in a dropdown somewhere else on the page. A control that
 *      vanishes is indistinguishable from a feature this build does not have, which is precisely the
 *      complaint that produced this file;
 *   3. the difference between "this row has no photograph" and "the photographs on this row could
 *      not be read", which are two states with two remedies and must never share a sentence.
 *
 * ── THE HEADING APPEARS TWICE WHEN THIS CARD IS EXPANDED, AND THAT IS THE PROP BEING ASKED FOR ──
 *
 * `PhotoMeasureField` owns its own disclosure — `const [open, setOpen] = useState(false)` at
 * `PhotoMeasureField.tsx:229` — and exposes no way to set it. So while this card is expanded, its
 * own header and the panel's own collapsed card both read "Measure a dimension from a photograph",
 * and the designer presses twice to reach the marks. That is a real cost and it is stated here
 * rather than hidden, because the remedy is one prop in a file this change may not edit; it is
 * written up under HANDOFF and the copy below turns the second press into a stated step rather than
 * a surprise ("Press *Measure from a photograph* below"). Re-checkable:
 *
 *     grep -n "useState(false)" frontend/components/designworkshop/PhotoMeasureField.tsx
 *
 * ── WHY NOT `components/ui/Accordion.tsx` ──────────────────────────────────────────────────────
 *
 * That primitive keeps `open` in its own state and renders no control at the foot of its panel, so a
 * caller cannot add one: the bottom control has to be able to close the card, and nothing inside
 * `children` can reach the state that would. It also carries `mb-5` and `p-4` for the page-level
 * panels it was lifted for, which is the vertical air this card was explicitly asked not to have.
 * Hand-rolling the disclosure here is therefore about the collapse control, not about styling, and
 * the semantics are copied from it verbatim — `aria-expanded` on the trigger, a rotating chevron on
 * a CSS transition (which both reduced-motion sources zero, so no JS branch is owed), and
 * `aria-controls` set ONLY while the panel is mounted (§17: pointing at a missing id is worse than
 * not pointing).
 */

import { useCallback, useId, useRef, useState } from "react";
import { AlertTriangle, ChevronDown, ChevronUp, ImageOff, Ruler } from "lucide-react";

import {
  PhotoMeasureField,
  type MeasurablePhoto,
  type MeasureTarget
} from "@/components/designworkshop/PhotoMeasureField";
import type { DwEntryData, DwValue } from "@/lib/designWorkshops";
import type { MeasurementMarker } from "@/lib/photoMeasure";

/**
 * The card's name, in the ONE spelling all three surfaces use.
 *
 * A constant rather than a literal typed twice below, and a constant that is deliberately NOT
 * imported from `PhotoMeasureField` — that file hardcodes the same words at :539 and :567 and
 * exports nothing for them, and Android hardcodes them again at `DwPhotoMeasureField.kt:371` and
 * :612. Three copies is what §1 of the frontend contract means by "copy Android's words verbatim":
 * the words are the shared thing, not the symbol. A rename is therefore a four-place edit, and this
 * is the grep that finds all of them:
 *
 *     grep -rn "Measure a dimension from a photograph" frontend/ android/
 */
const CARD_TITLE = "Measure a dimension from a photograph";

/**
 * The photographs on the chosen row, or why there are none to show.
 *
 * A UNION AND NOT A LIST PLUS TWO BOOLEANS, because the house rule that a failure and an empty
 * answer are different states is only enforceable if the type makes "empty because it failed"
 * unrepresentable. `failed` carries the sentence the host wrote — the host is the half that knows
 * whether the block was a released local copy, an entitlement, or a store that would not open, and
 * a sentence invented here could only be vaguer than the one it already has.
 */
export type MeasurePhotos =
  | { status: "loading" }
  | { status: "failed"; reason: string }
  | {
      status: "ready";
      photos: MeasurablePhoto[];
      /**
       * How many references on the row could NOT be turned into something displayable.
       *
       * Stated on screen whenever it is above zero. A media row whose `url` is withheld by
       * entitlement and a `dwlocal:` reference whose blob has already been released both land here,
       * and both would otherwise make the row look as though it held fewer photographs than it does
       * — the silent-emptiness class this repository has paid for more than any other.
       */
      unreadable: number;
    };

export interface MeasureFromPhotoCardProps {
  /** The half of the tab this card sits in. Used verbatim in every sentence below. */
  what: "sketch" | "prototype";
  /**
   * The row the picker above chose, as the designer sees it named, or null when none is chosen.
   *
   * Quoted in every sentence on purpose: this card measures against ONE row's photograph and the
   * control that picks that row is a long way up the page, so a sentence that says "the chosen
   * sketch" without naming it is a sentence a designer cannot check.
   */
  rowName: string | null;
  photos: MeasurePhotos;
  /** The registry's length fields on this entity — `stageFieldRoles.measurableLengthFields`. */
  targets: MeasureTarget[];
  /** The chosen row's current values; the panel prints what a field already holds before replacing it. */
  row: DwEntryData;
  /** Registry labels of the image fields a photograph would be attached to, for the empty sentence. */
  photoFieldLabels: string[];
  disabled?: boolean;
  /**
   * One field, one press. See the same prop on `PhotoMeasureField` — this only forwards it.
   *
   * The third argument is carried rather than dropped HERE so that the day the stage save gains
   * somewhere to put a measurement marker, nothing in this file changes. What the host currently
   * does with it, and why, is documented at the call site in `UploadTabHost.tsx`.
   */
  onPropose: (key: string, value: DwValue, method: MeasurementMarker) => void;
}

/** A plain explanatory sentence — the state where nothing is wrong and nothing can be done yet. */
function Note({ children }: { children: React.ReactNode }) {
  return <p className="max-w-prose text-sm leading-6 text-ink-700">{children}</p>;
}

export function MeasureFromPhotoCard({
  what,
  rowName,
  photos,
  targets,
  row,
  photoFieldLabels,
  disabled,
  onPropose
}: MeasureFromPhotoCardProps) {
  const panelId = useId();
  const [expanded, setExpanded] = useState(false);
  const triggerRef = useRef<HTMLButtonElement | null>(null);

  /**
   * Close, and put focus back on the header.
   *
   * THE FOCUS MOVE IS THE WHOLE REASON THE BOTTOM CONTROL CAN EXIST SAFELY. The panel is unmounted
   * on collapse, so a button inside it that closes the card destroys the element focus is sitting
   * on, and the browser drops focus to `<body>` — a keyboard reader would have to tab from the top
   * of the document to get back to where they were. `DynamicIslandNav.closeSheet()` returns focus to
   * the hamburger for exactly this reason (§7.8 of the frontend contract); this is the same move.
   *
   * Harmless when called from the header control, which already holds focus.
   */
  const collapse = useCallback(() => {
    setExpanded(false);
    triggerRef.current?.focus();
  }, []);

  const fieldsPhrase = photoFieldLabels.length
    ? photoFieldLabels.map((label) => `“${label}”`).join(" or ")
    : `this ${what}’s image field`;

  return (
    /*
      NOT `.panel`: this card sits INSIDE the section panel `UploadTabPanel` already draws, and a
      second white card-on-card is the visual noise the tab was already carrying too much of. The
      tinted, bordered box is the shape `PhotoMeasureField` itself uses for its collapsed state, so
      the card and the surface inside it read as one thing.
    */
    <section className="rounded-md border border-line-200 bg-surface-50">
      <h4>
        <button
          ref={triggerRef}
          type="button"
          className="flex w-full items-start gap-2 rounded-md p-3 text-left"
          aria-expanded={expanded}
          // ONLY WHILE THE PANEL IS MOUNTED — §17. It is unmounted on collapse (below), and an
          // `aria-controls` pointing at an id that is not in the document is worse than none.
          aria-controls={expanded ? panelId : undefined}
          onClick={() => (expanded ? collapse() : setExpanded(true))}
        >
          <Ruler className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <span className="min-w-0 flex-1">
            <span className="block text-sm font-medium text-ink-900">{CARD_TITLE}</span>
            {/*
              THE ONE LINE A COLLAPSED CARD OWES ITS READER. It says what the card is for and states
              the rule in the same breath, because "it never writes by itself" is the fact that
              decides whether somebody is willing to open a measuring tool over a record at all.
            */}
            <span className="mt-0.5 block text-xs leading-5 text-ink-500">
              Take a dimension off a photograph already attached to the chosen {what}. It never writes a dimension by
              itself.
            </span>
          </span>
          {/*
            The chevron is DECORATION over `aria-expanded`, never the state itself — and its rotation
            is a CSS transition, which both reduced-motion sources zero (§5), so the open and closed
            states are still told apart by the arrow's direction with no motion at all.
          */}
          <ChevronDown
            className={`mt-0.5 h-4 w-4 shrink-0 text-ink-500 transition-transform ${expanded ? "rotate-180" : ""}`}
            aria-hidden
          />
        </button>
      </h4>

      {expanded ? (
        /*
          `gap-3` and `p-3` and nothing else — no `mt-*` on the first child and no `mb-*` on the last,
          which is where the vertical air this card was asked not to have would otherwise come from.
          The border replaces the gap that would normally separate the header from the body.
        */
        <div id={panelId} className="grid gap-3 border-t border-line-200 p-3">
          <Body
            what={what}
            rowName={rowName}
            photos={photos}
            targets={targets}
            row={row}
            fieldsPhrase={fieldsPhrase}
            disabled={disabled}
            onPropose={onPropose}
            onCollapse={collapse}
          />

          {/*
            ── THE COLLAPSE AT THE FOOT, WHICH IS HALF THE POINT OF THIS CARD ────────────────────

            The card's contents are tall — a photograph viewport, a method chooser, a reference
            length, an error bar and a row of propose buttons — so a designer who has just pressed
            the last button is at the BOTTOM of all of it, and a control only at the top means
            scrolling back up past everything they have finished with to put it away. The same
            complaint was raised about the handset's panel, which also closes only from its header
            (`DwPhotoMeasureField.kt:618`), and both clients get the same control.

            A REAL BUTTON WITH THE CARD'S NAME IN IT, not a bare "Close": at the foot of a long
            panel there is no heading in view to say what would be closing, and this card is one of
            several stacked disclosures on the tab.
          */}
          <div>
            <button
              type="button"
              className="inline-flex items-center gap-1.5 text-xs font-medium text-ink-500 underline"
              onClick={collapse}
            >
              <ChevronUp className="h-3.5 w-3.5" aria-hidden />
              Collapse “{CARD_TITLE}”
            </button>
          </div>
        </div>
      ) : null}
    </section>
  );
}

/**
 * What the expanded card shows, which is one of six states and never a blank.
 *
 * THE ORDER OF THE BRANCHES IS THE ORDER A DESIGNER CAN ACT ON THEM. Nothing chosen comes before
 * anything about photographs, because the row decides which photographs there are; a read failure
 * comes before an empty answer, because "none" would be a lie about a list nobody could read; and
 * "no dimension to propose into" comes before "no photograph", because attaching a photograph would
 * not help a build whose registry declares nowhere to put the answer.
 */
function Body({
  what,
  rowName,
  photos,
  targets,
  row,
  fieldsPhrase,
  disabled,
  onPropose,
  onCollapse
}: {
  what: "sketch" | "prototype";
  rowName: string | null;
  photos: MeasurePhotos;
  targets: MeasureTarget[];
  row: DwEntryData;
  fieldsPhrase: string;
  disabled?: boolean;
  onPropose: (key: string, value: DwValue, method: MeasurementMarker) => void;
  /**
   * Shut the whole card. Handed down because `PhotoMeasureField` is CONTROLLED here and its own
   * Close must collapse the card rather than the panel inside it — see the mount below.
   */
  onCollapse: () => void;
}) {
  if (!rowName) {
    return (
      <Note>
        A dimension is measured against the photograph on ONE row, so choose which {what} this is about in the picker
        above. Nothing here can be measured until one is chosen.
      </Note>
    );
  }

  if (photos.status === "loading") {
    return <Note>Reading the photographs attached to “{rowName}”…</Note>;
  }

  if (photos.status === "failed") {
    /*
      A FAILED LOAD IS NOT AN EMPTY ROW, and it must not be dressed as one. The amber is paired with
      an icon and with words that say which of the two states this is, because colour alone carries
      no meaning for a reader who cannot see it (§1.4 of the house rules).
    */
    return (
      <p className="flex max-w-prose items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
        <AlertTriangle className="mt-1 h-4 w-4 shrink-0" aria-hidden />
        <span>
          The photographs attached to “{rowName}” could not be read, so this cannot say whether there is one to measure
          against. {photos.reason}
        </span>
      </p>
    );
  }

  if (targets.length === 0) {
    return (
      <Note>
        The field registry this browser holds declares no dimension on a {what} that a photograph could be measured
        into, so there is nothing here to propose a figure to. That is a difference in the schema, not a permission —
        open a stage form, which renders whatever the registry does declare.
      </Note>
    );
  }

  if (photos.photos.length === 0) {
    /*
      THE ONE SENTENCE, AND NOTHING ELSE. Not a disabled viewport, not a greyed method chooser, not a
      "0 photographs" chip: an empty measuring surface invites a designer to place marks on nothing
      and then explains itself only after they have tried. `PhotoMeasureField` renders nothing at all
      in this case, which is right on a stage form and wrong here — see this file's header.
    */
    return (
      <p className="flex max-w-prose items-start gap-2 text-sm leading-6 text-ink-700">
        <ImageOff className="mt-1 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
        <span>
          No photograph has been attached to “{rowName}” yet, so there is nothing to measure against. Attach one in{" "}
          {fieldsPhrase} above — a photograph with a ruler, a scale card or a sheet of paper beside the object in it —
          then open this again.
        </span>
      </p>
    );
  }

  return (
    <>
      <p className="max-w-prose text-sm leading-6 text-ink-700">
        Measuring against {photos.photos.length === 1 ? "the photograph" : `the ${photos.photos.length} photographs`}{" "}
        attached to “{rowName}”. Place the marks below.
      </p>

      {photos.unreadable > 0 ? (
        /*
          SAID, NEVER SWALLOWED. `unreadable` is the count of references this browser could not turn
          into a picture — a media file whose url the account is not entitled to, or a `dwlocal:`
          blob already released after its upload was confirmed. Without this line the chooser inside
          the panel simply shows fewer photographs than the row holds, which reads as the row having
          fewer, and the one photograph with the ruler in it may be exactly the missing one.
        */
        <p className="flex max-w-prose items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
          <AlertTriangle className="mt-1 h-4 w-4 shrink-0" aria-hidden />
          <span>
            {photos.unreadable === 1
              ? "One more file on this row could not be opened here, so it is not in the list below."
              : `${photos.unreadable} more files on this row could not be opened here, so they are not in the list below.`}{" "}
            That is usually a file uploaded from another device that this account cannot fetch back; it can be measured
            on the stage form, or on the handset that took it.
          </span>
        </p>
      ) : null}

      {/*
        THE RULE, ON SCREEN AND NOT ONLY IN THE HEADER. Both clients state it in their own source and
        the designer never reads source. It sits ABOVE the panel rather than below it because it is
        the thing somebody needs before they start placing marks, not after.
      */}
      <p className="max-w-prose text-xs leading-5 text-ink-500">
        Nothing here writes a dimension by itself. Every figure is written by a button you press, and that button says
        which field it will write and exactly what it will put there.
      </p>

      {/*
        CONTROLLED, SINCE 2026-08-28 — `open` and `onOpenChange` landed on `PhotoMeasureField` for
        this one call site.

        Without them the two disclosures stacked: this card's own header said "Measure a dimension
        from a photograph", and opening it revealed the panel's COLLAPSED state, which says exactly
        the same words over a second button. A designer met the same heading twice and had to press
        twice to reach a control they had already asked for.

        `open` is a literal TRUE because this subtree is only rendered while the card is expanded —
        the card unmounts it on collapse, for the focus reason at `collapse`. `onOpenChange` maps the
        panel's own Close (both of them, header and foot) onto the card's collapse, so the two
        controls are one act rather than two that can disagree about what is open.
      */}
      <PhotoMeasureField
        photos={photos.photos}
        targets={targets}
        row={row}
        disabled={disabled}
        onPropose={onPropose}
        open
        onOpenChange={(next) => {
          if (!next) onCollapse();
        }}
      />
    </>
  );
}
