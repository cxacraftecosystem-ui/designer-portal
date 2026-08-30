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
 * ── AND SINCE 2026-08-29, IT CAN SEE A PHOTOGRAPH THAT IS NOT ON THE ROW YET ────────────────────
 *
 * The card used to be able to measure only what had ALREADY been written into the chosen row and
 * read back out of the draft store. So a designer who had just photographed a sketch had to attach
 * it, wait for `putDraftStage` and a sync pass and a reload, and only then could they measure it —
 * and if they had attached it to a different row than the one selected, the card could not see it at
 * all. That is why the same photograph felt like two uploads: one into the tracing panel's decoder,
 * and a second, long way round, into this one.
 *
 * {@link MeasureFromPhotoCardProps.working} closes it. The host now owns one photograph for the whole
 * Sketches section — `SharedPhotoField` chooses it, `UploadTabHost` decodes it once and mints its one
 * object URL — and hands it to the tracing panel and to this card together. It is offered here FIRST
 * in the list and named as not-yet-attached, because a photograph a designer is holding is the one
 * they mean, and a card that silently preferred last week's filed photograph over it would be a
 * quieter version of the bug this replaced.
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
 * Choosing a photograph here writes nothing either, and that is the same rule wearing another coat:
 * {@link MeasureFromPhotoCardProps.onUseDifferentPhoto} hands a `File` to the host to display and
 * nothing else. Nothing on this card can put a file on a record.
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
 *      not be read", which are two states with two remedies and must never share a sentence;
 *   4. the photograph this tab is holding but has not filed, and the escape hatch that lets it be a
 *      DIFFERENT one from the sketch being traced.
 *
 * ── AND THE SAME CARD, MOUNTED TWICE, IS WHY REQUIREMENT 7 IS MOSTLY ALREADY TRUE HERE ─────────
 *
 * `UploadTabHost` mounts this file for both halves of the tab — `what="sketch"` under the tracing
 * panel and `what="prototype"` under the two prototype uploaders — so everything the Sketches half
 * gained on 2026-08-29 landed on the Prototypes half in the same commit: the card shell, the focus
 * moves, the spinner in a live region, the bordered amber with `role="alert"`, the collapse control
 * at the foot. `what` has never been a branch in this component's LOGIC and still is not; it is
 * interpolated into sentences, and the two places it now decides between two whole sentences rather
 * than filling in a noun are marked and argued where they sit ({@link measuringSentence} and
 * `DifferentPhoto`).
 *
 * The ONE thing the two mounts differ in is what can arrive in {@link
 * MeasureFromPhotoCardProps.working}: a `"shared"` photograph exists only where a shared picker does,
 * which is the Sketches half and — see `PrototypeModelField`'s header — only ever will be.
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
 *
 * ── AND WHY THE CHROME IS DRAWN HERE AND DRAWN AGAIN IN `SketchTraceField` ─────────────────────
 *
 * These two cards sit one above the other in the same section and used to have four different shapes
 * between them — collapsed, that one was a 40px inline button and this one a full-width card; open,
 * `rounded-lg … p-3` against `rounded-md` with the padding on its children; a description line here
 * always and there only after opening; `mt-2` there against `mt-3` here. They are one grammar now.
 *
 * They are NOT one component, for two reasons. A shell that covered both would need a flag per
 * difference — the icon, the focus target, the close control's wording, the busy treatment, the foot
 * control — and six mode flags on one component is what two clear components beat. And
 * `SketchTraceField` may not gain a runtime import at all: `e2e/sketch-trace-panel.spec.ts` compiles
 * that module alone through a registry that throws on any specifier it was not handed, and that file
 * is not this change's to edit. So the classes and the words are the shared thing, not the symbol —
 * the same answer {@link CARD_TITLE} records below for the four copies of this card's name. The grep
 * that finds the pair:
 *
 *     grep -n "rounded-md border border-line-200 bg-surface-50" frontend/components/sketches/upload/
 */

import { useCallback, useEffect, useId, useRef, useState } from "react";
import { AlertTriangle, ChevronDown, ChevronUp, ImageOff, ImagePlus, Loader2, Ruler, X } from "lucide-react";

import {
  PhotoMeasureField,
  type MeasurablePhoto,
  type MeasureTarget
} from "@/components/designworkshop/PhotoMeasureField";
import type { DwEntryData, DwValue } from "@/lib/designWorkshops";
import type { MeasurementMarker } from "@/lib/photoMeasure";

import { DropCard } from "./DropCard";
import { DECODE_MAX_EDGE_PX, TRACEABLE_ACCEPT, TRACEABLE_IMAGE_TYPES } from "./decodeToPixels";

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
 *
 * IT STILL DESCRIBES THE ROW AND ONLY THE ROW. The photograph this tab is holding but has not filed
 * arrives by its own prop ({@link MeasureFromPhotoCardProps.working}) rather than being folded in
 * here, because a `loading` or a `failed` answer about the row must not be able to hide it: the one
 * photograph a designer is certain about is the one they just chose, and it needs no store to read.
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

/**
 * A photograph this tab is holding that is NOT on the record — the one both panels work from, or the
 * different one this card was told to use instead.
 *
 * `source` IS THE DIFFERENCE BETWEEN TWO SENTENCES THAT MUST NOT BE THE SAME. "The photograph you
 * chose above, which the tracing panel is also using" and "a photograph chosen for this panel only"
 * are two different claims about what the designer is looking at, and getting them the wrong way
 * round is precisely the confusion one shared upload can cause. There is no third value on purpose:
 * a photograph that IS on the record is not a `WorkingPhoto` at all, it is a member of
 * {@link MeasurePhotos}.
 */
export interface WorkingPhoto {
  photo: MeasurablePhoto;
  /** "shared" — the one chosen above, which the tracing panel has too. "own" — this card's own. */
  source: "shared" | "own";
}

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
  /**
   * The photograph this tab is holding and has not filed, or null for none.
   *
   * OFFERED FIRST, AND SAID TO BE UNFILED. See the header: it is what makes one upload serve both
   * panels.
   *
   * BOTH HALVES PASS IT, AND ONLY ONE OF THEM CAN CARRY A `"shared"` ONE. The Sketches half has a
   * picker above both of its panels, so its held photograph is usually the one the tracing panel is
   * working from; the Prototypes half has no such picker and never will (see
   * `PrototypeModelField`'s header for why), so the only thing that can arrive here on that half is
   * a `"own"` photograph chosen on this card. That is a difference in what the value can BE, not in
   * whether the prop is passed, which is why the sentences below branch on `source` and not on
   * `what`.
   */
  working?: WorkingPhoto | null;
  /**
   * ── THE ESCAPE HATCH, AND WHY IT IS A CONTROL RATHER THAN A DELETION ──────────────────────────
   *
   * The default is one upload: a designer photographs the sheet once and both panels work from it.
   * But the two panels genuinely want different things some of the time, and the case is ordinary
   * rather than exotic — the sheet worth TRACING is the drawing itself, flat and filling the frame,
   * and the photograph worth MEASURING is the one with a ruler or a scale card lying beside the
   * object, which is a different photograph of a different subject. Forcing them to be the same
   * would make the tab worse at one of its two jobs, and the honest way to find that out is not to
   * remove the second picker and wait for the complaint.
   *
   * So: the shared photograph is what this card uses unless the designer says otherwise, saying
   * otherwise is an explicit press, and what they chose is named on screen so the two panels can
   * never quietly diverge. `null` puts it away and returns the card to the shared one.
   *
   * ── AND IT IS OFFERED ON BOTH HALVES SINCE 2026-08-29, WHICH IT WAS NOT ───────────────────────
   *
   * This doc used to say the Prototypes half "simply does not offer the choice — there would be
   * nothing for a different photograph to be different FROM". The first clause of that is true and
   * the conclusion does not follow. What the control actually offers is a photograph THAT IS NOT ON
   * THE ROW, and the shared one it can differ from is a convenience of the other half rather than
   * the point: on this half it differs from the prototype's own filed photographs, which is a real
   * and ordinary want. The photographs a prototype is judged by are shot as a clean turn — same
   * light, same background, nothing else in frame — and the photograph a dimension comes off has a
   * ruler or a scale card lying beside the object in it. Making a designer file the second one onto
   * the record to measure it is exactly the "attach it before you can look at it" round trip this
   * whole change removed on the other half.
   *
   * Still OPTIONAL, because a host that cannot display a `File` at all has nothing to offer here —
   * and the card must not draw a picker it cannot honour. Both mounts on the UPLOAD tab pass it; the
   * copy underneath differs by {@link MeasureFromPhotoCardProps.what}, because "not the one being
   * traced" is a sentence only one half has a tracing panel for.
   */
  onUseDifferentPhoto?: (file: File | null) => void;
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
  onPropose,
  working,
  onUseDifferentPhoto
}: MeasureFromPhotoCardProps) {
  const panelId = useId();
  const [expanded, setExpanded] = useState(false);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const headingRef = useRef<HTMLHeadingElement | null>(null);
  /** Whether the card was open on the previous render, so focus is returned only on a real close. */
  const wasOpenRef = useRef(false);

  /**
   * Close, and put focus back on the header.
   *
   * THE FOCUS MOVE IS THE WHOLE REASON THE BOTTOM CONTROL CAN EXIST SAFELY. The panel is unmounted
   * on collapse, so a button inside it that closes the card destroys the element focus is sitting
   * on, and the browser drops focus to `<body>` — a keyboard reader would have to tab from the top
   * of the document to get back to where they were. `DynamicIslandNav.closeSheet()` returns focus to
   * the hamburger for exactly this reason (§7.8 of the frontend contract); this is the same move.
   *
   * IT IS THE EFFECT BELOW THAT DOES IT NOW, not this callback. The trigger is unmounted while the
   * card is open — the shape both cards in this section share since 2026-08-29 — so `triggerRef` is
   * null at the moment this runs and focusing it here would silently do nothing. Setting the state
   * and letting the effect focus what has just come back is the only order that works.
   */
  const collapse = useCallback(() => {
    setExpanded(false);
  }, []);

  /**
   * Move focus deliberately, in both directions.
   *
   * OPENING UNMOUNTS THE TRIGGER AND CLOSING UNMOUNTS EVERY CONTROL INSIDE, so without this the
   * focused element simply disappears and focus falls to `<body>`: a keyboard user loses their place
   * mid-page and a screen-reader user is told nothing happened at all. The heading rather than the
   * first control, so a reader hears this card's NAME on arrival instead of "photograph, button".
   *
   * COPIED FROM `SketchTraceField`, DELIBERATELY AND VISIBLY — that panel has had this since it
   * shipped and this card had only half of it, which is one of the inconsistencies the pair was
   * reported for. `wasOpenRef` keeps the closing half from firing on first mount, when nothing was
   * ever opened and the page's own focus is not this component's to take.
   */
  useEffect(() => {
    if (expanded) {
      wasOpenRef.current = true;
      headingRef.current?.focus();
      return;
    }
    if (!wasOpenRef.current) return;
    wasOpenRef.current = false;
    triggerRef.current?.focus();
  }, [expanded]);

  const fieldsPhrase = photoFieldLabels.length
    ? photoFieldLabels.map((label) => `“${label}”`).join(" or ")
    : `this ${what}’s image field`;

  /**
   * The one line under the card's name, in both states.
   *
   * ONE STRING FOR COLLAPSED AND OPEN, and it is the line a collapsed card owes its reader: it says
   * what the card is for and states the rule in the same breath, because "it never writes by itself"
   * is the fact that decides whether somebody is willing to open a measuring tool over a record at
   * all. `SketchTraceField` carries the same pair for the same reason.
   */
  const description = `Take a dimension off a photograph of the chosen ${what}. It never writes a dimension by itself.`;

  return (
    /*
      NOT `.panel`: this card sits INSIDE the section panel `UploadTabPanel` already draws, and a
      second white card-on-card is the visual noise the tab was already carrying too much of. The
      tinted, bordered box is the shape `PhotoMeasureField` itself uses for its collapsed state, so
      the card and the surface inside it read as one thing — and it is the shape the tracing panel
      above now uses too. Sizes were normalised across the pair; TINTS WERE NOT. `.panel` is
      `bg-card`, these two cards are `bg-surface-50` inside it, and `PrototypeModelField`'s inner
      cards are `bg-card` again on the other half of the tab. That layering is deliberate.
    */
    <section className="rounded-md border border-line-200 bg-surface-50">
      {expanded ? (
        /*
          THE HEADER CARRIES ITS OWN PADDING AND SO DOES THE BODY; THE ROOT CARRIES NONE. The border
          between them replaces the gap that would otherwise separate the two, which is where the
          vertical air this card was asked not to have would come from.
        */
        <div className="flex items-start gap-2 p-3">
          <Ruler className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <div className="min-w-0 flex-1">
            {/* `tabIndex={-1}` makes the heading focusable by script and not by Tab, which is what a
                deliberate focus move needs and what a tab stop on a heading would get wrong. */}
            <h4
              ref={headingRef}
              tabIndex={-1}
              className="text-sm font-medium text-ink-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-purple-600/40"
            >
              {CARD_TITLE}
            </h4>
            <p className="mt-0.5 text-xs leading-5 text-ink-500">{description}</p>
          </div>
          <button
            type="button"
            className="rounded-md p-1 text-ink-500 transition hover:bg-field-100 hover:text-ink-900"
            onClick={collapse}
            aria-label="Close the measuring panel"
          >
            <X className="h-4 w-4" aria-hidden />
          </button>
        </div>
      ) : (
        <h4>
          <button
            ref={triggerRef}
            type="button"
            className="flex w-full items-start gap-2 rounded-md p-3 text-left disabled:cursor-not-allowed disabled:opacity-60"
            disabled={disabled}
            aria-expanded={false}
            /*
              NO `aria-controls` — §17, "only while the panel is mounted". This button is replaced
              outright when the card opens, so there is no state in which it can honestly carry one:
              while it exists the panel does not, and an `aria-controls` pointing at an id that is
              not in the document offers a reader a jump that goes nowhere.

              `disabled={disabled}` IS THE OTHER HALF OF MATCHING `SketchTraceField`'s TRIGGER. The
              two cards share this section (see the comment above `description`) and are documented
              as behaving the same when the section around them is disabled, but only the tracing
              card's collapsed trigger ever greyed out and refused a click — this one stayed fully
              clickable-looking and opened anyway. Everything the open panel does is separately
              gated on `disabled` (`Body`'s own props), so nothing inside could act while disabled —
              but a trigger that visibly invites a click it is about to make pointless is a real,
              on-screen inconsistency between two cards whose whole point is reading as one pair.
            */
            onClick={() => setExpanded(true)}
          >
            <Ruler className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
            <span className="min-w-0 flex-1">
              <span className="block text-sm font-medium text-ink-900">{CARD_TITLE}</span>
              <span className="mt-0.5 block text-xs leading-5 text-ink-500">{description}</span>
            </span>
            {/*
              The chevron is DECORATION over `aria-expanded`, never the state itself, and its rotation
              is a CSS transition, which both reduced-motion sources zero (§5) — so the open and
              closed states are still told apart with no motion at all.
            */}
            <ChevronDown className="mt-0.5 h-4 w-4 shrink-0 text-ink-500 transition-transform" aria-hidden />
          </button>
        </h4>
      )}

      {expanded ? (
        /*
          `gap-3` and `p-3` and nothing else — no `mt-*` on the first child and no `mb-*` on the last.
          The tracing panel above spaces its own body with per-child `mt-3` instead, which is the SAME
          token drawn a different way: that body is fifteen conditional blocks deep and converting it
          to a gap would have meant editing every one of them for no visible change.
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
            working={working ?? null}
            onUseDifferentPhoto={onUseDifferentPhoto}
          />

          {/*
            ── THE COLLAPSE AT THE FOOT, WHICH IS HALF THE POINT OF THIS CARD ────────────────────

            The card's contents are tall — a photograph viewport, a method chooser, a reference
            length, an error bar and a row of propose buttons — so a designer who has just pressed
            the last button is at the BOTTOM of all of it, and a control only at the top means
            scrolling back up past everything they have finished with to put it away. The same
            complaint was raised about the handset's panel, which also closes only from its header
            (`DwPhotoMeasureField.kt:618`), and both clients get the same control. The tracing panel
            above carries the pair of it for the same reason.

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
 * What the expanded card shows, which is one of seven states and never a blank.
 *
 * THE ORDER OF THE BRANCHES IS THE ORDER A DESIGNER CAN ACT ON THEM. Nothing chosen comes before
 * anything about photographs, because the row decides which photographs there are; a read failure
 * comes before an empty answer, because "none" would be a lie about a list nobody could read; and
 * "no dimension to propose into" comes before "no photograph", because attaching a photograph would
 * not help a build whose registry declares nowhere to put the answer.
 *
 * A PHOTOGRAPH THIS TAB IS HOLDING TURNS TWO OF THOSE BRANCHES INTO NOTES RATHER THAN DEAD ENDS, and
 * that is the seventh state. `working` needs no store to read and no connection to fetch, so while
 * the row's own photographs are still loading — or could not be read at all — there is something
 * here that CAN be measured, and stopping at the row's sentence would be refusing a photograph that
 * is on screen. Both facts are still said; they are said above the panel instead of instead of it.
 *
 * ONE CONSEQUENCE OF THAT, STATED RATHER THAN DISCOVERED: with a photograph in hand, a read failure
 * now loses to "the registry declares nowhere to put a dimension" instead of beating it. That is the
 * right way round — a build with no length field cannot measure anything from any photograph, so the
 * schema sentence is the one a designer can act on, and the failure it hides is about a list they
 * would have no use for.
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
  onCollapse,
  working,
  onUseDifferentPhoto
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
  working: WorkingPhoto | null;
  onUseDifferentPhoto?: (file: File | null) => void;
}) {
  /** The row's own photographs, and nothing about the one this tab is holding. */
  const filed = photos.status === "ready" ? photos.photos : [];
  /**
   * What the panel below can actually measure, unfiled photograph FIRST.
   *
   * FIRST BECAUSE `PhotoMeasureField` SELECTS `photos[0]` UNTIL SOMEBODY CHOOSES OTHERWISE, and the
   * photograph a designer is holding is the one they mean. Ordering the row's filed photographs
   * ahead of it would open the panel on last week's picture with this morning's one two clicks away,
   * which is the quiet version of the failure this whole arrangement was raised about.
   */
  const usable = working ? [working.photo, ...filed] : filed;
  /**
   * The escape hatch, built once and rendered by every branch that a photograph could rescue.
   *
   * ── IT WAS UNREACHABLE IN THE TWO STATES THAT NEEDED IT MOST ──────────────────────────────────
   *
   * "Measure a different photograph" used to be rendered in exactly two places: under the measuring
   * panel, and under the "nothing has been attached to this row yet" sentence. Both of those are
   * reached only AFTER the row's own photographs have been read successfully — so on a row whose
   * photographs could not be read at all, the card returned {@link ReadFailure} and stopped, and the
   * one control that does not need the store to work was not on the screen. That is backwards twice
   * over: the read failure is the state where measuring something that is NOT on the record is the
   * only measuring left, and this control needs no connection, no entitlement and no store to open —
   * it displays a file the designer is holding. A control that vanishes is indistinguishable from a
   * feature this build does not have, which is the complaint this whole file was written for.
   *
   * IT BIT THE PROTOTYPES HALF HARDEST, AND ONLY THAT HALF HAD NO WAY ROUND IT. On the Sketches half
   * a designer could reach the hatch by choosing a photograph in the shared card at the top of the
   * section — that makes `working` non-null, which skips both early returns. The Prototypes half has
   * no shared card and never will (`PrototypeModelField`'s header argues why), so `working` there is
   * null until this control produces one: the only door was behind the door.
   *
   * `targets.length` IS PART OF THE CONDITION AND NOT AN AFTERTHOUGHT. Below these two branches sits
   * "the registry declares no dimension a photograph could be measured into", and offering a picker
   * above a build that has nowhere to put the answer would be a control whose whole job cannot be
   * done — the same "advises a field it cannot write" defect the turntable card records. Where there
   * is nothing to propose into, the schema sentence is the only honest thing on the card.
   */
  const escape =
    onUseDifferentPhoto && targets.length > 0 ? (
      <DifferentPhoto what={what} working={working} disabled={disabled} onChoose={onUseDifferentPhoto} />
    ) : null;

  if (!rowName) {
    return (
      <Note>
        A dimension is measured against the photograph on ONE row, so choose which {what} this is about in the picker
        above. Nothing here can be measured until one is chosen.
      </Note>
    );
  }

  if (photos.status === "loading" && !working) {
    /*
      A SPINNER AND A LIVE REGION, WHICH THIS SENTENCE USED TO BE WITHOUT. It was a plain paragraph:
      nothing moved, and a screen reader was told nothing at all, so "reading" and "there is nothing
      here" looked and sounded identical. The tracing panel above has always said its own waiting
      state this way — `aria-live="polite"` and an `animate-spin` `Loader2` — and this is the same
      treatment, not a second invention of one.
    */
    return (
      <>
        <p aria-live="polite" className="flex max-w-prose items-center gap-2 text-sm leading-6 text-ink-700">
          <Loader2 className="h-4 w-4 shrink-0 animate-spin" aria-hidden />
          Reading the photographs attached to “{rowName}”…
        </p>
        {escape}
      </>
    );
  }

  if (photos.status === "failed" && !working) {
    return (
      <>
        <ReadFailure rowName={rowName} reason={photos.reason} />
        {escape}
      </>
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

  if (usable.length === 0) {
    /*
      THE ONE SENTENCE, AND THE ONE CONTROL THAT CAN ANSWER IT. Not a disabled viewport, not a greyed
      method chooser, not a "0 photographs" chip: an empty measuring surface invites a designer to
      place marks on nothing and then explains itself only after they have tried. `PhotoMeasureField`
      renders nothing at all in this case, which is right on a stage form and wrong here — see this
      file's header.

      AND IT NO LONGER SAYS "ABOVE" ABOUT FIELDS THAT ARE NOT. This sentence read "Attach one in
      {fieldsPhrase} above", which was true while the card was only ever read on the Sketches half:
      `photoFieldLabels` there is `sketch.image` and there really is a picker for it at the top of the
      section. It is false on the Prototypes half, where the same list is `prototypePhotos` AND
      `turntablePhotos` and only the second of those has a picker on this tab — so a designer was sent
      to look for a control that is on the stage form. A sentence pointing at the wrong place is the
      defect this tab has already paid for once, in the section intro that named the tracing panel as
      where a photograph is chosen; both destinations are named now and neither is claimed to be here.
    */
    return (
      <>
        <p className="flex max-w-prose items-start gap-2 text-sm leading-6 text-ink-700">
          <ImageOff className="mt-1 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <span>
            No photograph has been attached to “{rowName}” yet, so there is nothing to measure against. Attach one in{" "}
            {fieldsPhrase} — from a panel above, where this tab offers that field, or on the {what}’s own stage form,
            which offers all of them — a photograph with a ruler, a scale card or a sheet of paper beside the object in
            it.
          </span>
        </p>
        {/* The same element every other branch renders — see `escape`. `targets.length > 0` is
            already settled by the branch above this one, so the condition inside it costs nothing
            here and is what makes the two early returns above safe. */}
        {escape}
      </>
    );
  }

  return (
    <>
      {/* The row's own answer is still owed even when there is something to measure without it. */}
      {photos.status === "loading" ? (
        <p aria-live="polite" className="flex max-w-prose items-center gap-2 text-sm leading-6 text-ink-700">
          <Loader2 className="h-4 w-4 shrink-0 animate-spin" aria-hidden />
          Still reading the photographs attached to “{rowName}”…
        </p>
      ) : null}
      {photos.status === "failed" ? <ReadFailure rowName={rowName} reason={photos.reason} /> : null}

      <p className="max-w-prose text-sm leading-6 text-ink-700">
        {measuringSentence(what, working, filed.length, rowName)} Place the marks below.
      </p>

      {photos.status === "ready" && photos.unreadable > 0 ? (
        /*
          SAID, NEVER SWALLOWED. `unreadable` is the count of references this browser could not turn
          into a picture — a media file whose url the account is not entitled to, or a `dwlocal:`
          blob already released after its upload was confirmed. Without this line the chooser inside
          the panel simply shows fewer photographs than the row holds, which reads as the row having
          fewer, and the one photograph with the ruler in it may be exactly the missing one.
        */
        <p
          role="alert"
          className="flex max-w-prose items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800"
        >
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
        the thing somebody needs before they start placing marks, not after — which is the one place
        this card's ordering differs from the tracing panel's, where the same kind of sentence sits
        UNDER the attach buttons it is about. The buttons this sentence is about are inside a
        component this file does not own and cannot annotate from underneath.
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

        ── AND THE `key` IS THE OTHER HALF OF "REPLACING THE PHOTOGRAPH RESETS BOTH CARDS" ─────────

        Marks, a reference length, a zoom and a pan are all measurements OF one photograph. Swap the
        photograph under them and every one of them is a claim about a picture that is no longer
        there — an R1/R2 pair sitting where a ruler used to be, and a proposed figure computed from
        it that a designer has no way to tell is nonsense. `PhotoMeasureField` clears its own marks
        when the SELECTED photograph disappears from the list, but not when `photos[0]` is quietly
        replaced under a selection nobody made explicitly, which is exactly what happens when the
        shared photograph above is replaced. Keying the mount on the working photograph's identity
        makes the reset unconditional and total: a new photograph gets a new panel. The tracing card
        does the same thing by another route — `adoptPhotograph` clears its drawing, its frame and
        its saved sentence — so the two cards forget the old photograph together.
      */}
      <PhotoMeasureField
        key={working ? working.photo.key : "row"}
        photos={usable}
        targets={targets}
        row={row}
        disabled={disabled}
        onPropose={onPropose}
        open
        onOpenChange={(next) => {
          if (!next) onCollapse();
        }}
      />

      {escape}
    </>
  );
}

/**
 * "The photographs on this row could not be read", in the one treatment both branches use.
 *
 * A FAILED LOAD IS NOT AN EMPTY ROW, and it must not be dressed as one. The amber is paired with an
 * icon and with words that say which of the two states this is, because colour alone carries no
 * meaning for a reader who cannot see it (§1.4 of the house rules).
 *
 * `role="alert"`, WHICH IT DID NOT USED TO CARRY. This box appears asynchronously — the read is
 * already in flight when the card is opened — so a reader who has moved on is never told, and the
 * card simply looks as though it holds nothing. The tracing panel announces its own failures the
 * same way. THE COLOUR IS STILL AMBER AND NOT THAT PANEL'S RED, deliberately: red is "what you just
 * asked for did not happen" and this is "something you did not ask about could not be read", which
 * has a different remedy — find a connection, or measure on the device that took it. §12.11's rule
 * is that the treatment is chosen by MEANING, so matching the colour here would have been the wrong
 * kind of consistency.
 */
function ReadFailure({ rowName, reason }: { rowName: string; reason: string }) {
  return (
    <p
      role="alert"
      className="flex max-w-prose items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800"
    >
      <AlertTriangle className="mt-1 h-4 w-4 shrink-0" aria-hidden />
      <span>
        The photographs attached to “{rowName}” could not be read, so this cannot say whether there is one to measure
        against. {reason}
      </span>
    </p>
  );
}

/**
 * What this panel is measuring against, naming the unfiled photograph as unfiled.
 *
 * THE WORD "ATTACHED" WAS DOING TWO JOBS AND ONLY ONE OF THEM HONESTLY. The sentence used to read
 * "Measuring against the 2 photographs attached to “X”", which was true while every photograph here
 * had come off the record. One of them has not, now, and a count that swept it in would tell a
 * designer their photograph was filed when nothing had been written anywhere — the receipt-that-
 * overstates failure this tab has paid for once already, on the attach path.
 *
 * `what` IS HERE FOR ONE CLAUSE, AND IT IS A CLAUSE THAT WOULD BE FALSE ON THE OTHER HALF. "…and is
 * not the one being traced" tells a designer on the Sketches half that the two panels are looking at
 * two different pictures, which is the whole reason the escape hatch is allowed to exist. There is no
 * tracing panel on the Prototypes half — `offersSketchRectify` refuses every FILE field a prototype
 * declares — so the same words there would name a control that is not on the screen.
 */
function measuringSentence(
  what: "sketch" | "prototype",
  working: WorkingPhoto | null,
  filed: number,
  rowName: string
): string {
  const filedPhrase =
    filed === 0
      ? null
      : filed === 1
        ? `the photograph attached to “${rowName}”`
        : `the ${filed} photographs attached to “${rowName}”`;
  if (!working) {
    return `Measuring against ${filedPhrase ?? `the photographs attached to “${rowName}”`}.`;
  }
  const workingPhrase =
    working.source === "shared"
      ? "the photograph chosen above, which is not attached to anything yet"
      : what === "sketch"
        ? "a photograph chosen for this panel only, which is not attached to anything and is not the one being traced"
        : "a photograph chosen for this panel only, which is not attached to anything";
  return filedPhrase
    ? `Measuring against ${workingPhrase}, and ${filedPhrase}.`
    : `Measuring against ${workingPhrase}.`;
}

/**
 * The escape hatch: a photograph for THIS panel that is not on the record and is not the shared one.
 *
 * ── WHY THIS IS NOT SIMPLY A SECOND UPLOADER ──────────────────────────────────────────────────
 *
 * It is folded away, it is named for what it is, and it says what it does not do. Left open beside
 * the shared card above it, two pickers of equal weight is what the designer was complaining about;
 * hidden behind a press, it is a way out of the default rather than a rival to it. And the default
 * is stated in the same breath, so a designer who opens it by accident learns that they did not need
 * to.
 *
 * NOTHING HERE FILES ANYTHING. The photograph is displayed and measured and then forgotten; the
 * controls that put a file on a record are the tracing panel's attach buttons and the stage form's
 * own pickers, both of which name the field they write. A picker on this card that quietly attached
 * would make "measure" mean "upload", on a card whose whole contract is that it writes nothing by
 * itself.
 *
 * ── `what` DECIDES THE WORDS, AND ONLY THE WORDS ───────────────────────────────────────────────
 *
 * The control, its shape, its refusals and its promise are identical on both halves of the tab —
 * which is the point: a designer who found this on Sketches must not have to find it again on
 * Prototypes. What differs is what it is different FROM, and saying that wrong is worse than not
 * saying it. On the Sketches half the default is the shared photograph at the top of the section and
 * the tracing panel keeps using it. On the Prototypes half there is no shared photograph and no
 * tracing panel, so the default is the prototype's own filed photographs, and a sentence about "the
 * photograph chosen above" would point at a control that is not on the screen.
 *
 * IT IS BRANCHED ON `what` RATHER THAN ON "IS THERE A SHARED PICKER", which is the fact the sentences
 * actually turn on, because there is no honest way to ask that here: `working` is null before anyone
 * has chosen anything, and the absence of a shared photograph is exactly the state the copy has to be
 * right about. Today the Sketches half is precisely the half with a picker above it. If a third half
 * ever appears, or if this one loses its picker, this branch is the line to split — and
 * `PrototypeModelField`'s header is the argument for why the Prototypes half will not gain one.
 */
function DifferentPhoto({
  what,
  working,
  disabled,
  onChoose
}: {
  what: "sketch" | "prototype";
  working: WorkingPhoto | null;
  disabled?: boolean;
  onChoose: (file: File | null) => void;
}) {
  const [open, setOpen] = useState(false);
  const sectionId = useId();
  /** An override in force is never folded away — a control that is doing something must be visible. */
  const inForce = working?.source === "own";
  const showing = open || inForce;
  const summaryRef = useRef<HTMLButtonElement | null>(null);
  /** Whether an override was in force on the previous render, so focus moves only on a real exit. */
  const wasInForceRef = useRef(false);

  /*
    PUTTING THE OVERRIDE AWAY UNMOUNTS THE BUTTON THAT DID IT, SO FOCUS HAS TO BE PLACED.

    "Go back to the photograph chosen above" lives in the block that is rendered only WHILE there is
    an override, so the press destroys the element focus is sitting on and the browser drops it to
    `<body>`: a keyboard reader tabs from the top of the document to return to a card they were
    already inside, and a screen reader is told nothing happened. This is the same failure the card
    around it moves focus on both its open and its close to avoid, and this control shipped without
    the pair of it.

    THE SUMMARY BUTTON IS THE TARGET because it is what REPLACES the block — the same element, in
    the same place, saying what the card is now doing. The `DropCard` inside is not a safe landing
    place: `showing` is `open || inForce`, so on a card whose override survived a collapse and
    reopen the picker unmounts in the same commit.

    AN EFFECT AND NOT A CALL IN THE HANDLER, WHICH IS THE OPPOSITE OF `SharedPhotoField`'s ANSWER
    for the same shape. There the landing place already exists when the press happens, so focusing
    it inline is enough. Here it does not exist yet — it is mounted by the very render the press
    causes — and `summaryRef.current` is null at that moment, so an inline call would silently do
    nothing. `wasInForceRef` keeps this from firing on first mount, when no override was ever put
    away and the page's own focus is not this component's to take.
  */
  useEffect(() => {
    if (inForce) {
      wasInForceRef.current = true;
      return;
    }
    if (!wasInForceRef.current) return;
    wasInForceRef.current = false;
    summaryRef.current?.focus();
  }, [inForce]);

  return (
    <div className="rounded-md border border-line-200 bg-card p-3">
      {inForce ? (
        <div className="flex items-start gap-2">
          <ImagePlus className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <div className="min-w-0 flex-1">
            <p className="text-xs font-medium text-ink-900">Measuring a different photograph</p>
            <p className="mt-0.5 text-xs leading-5 text-ink-500">
              <span className="font-medium text-ink-900">{working.photo.name}</span> is being measured here and nowhere
              else.{" "}
              {what === "sketch"
                ? "The tracing panel above is still working from the photograph chosen at the top of this section, and neither of them has been filed."
                : `It is on no record — nothing on this card files anything — and the photographs attached to this ${what} are untouched.`}
            </p>
            <button
              type="button"
              className="mt-2 inline-flex items-center gap-1.5 text-xs font-medium text-ink-500 underline disabled:cursor-not-allowed disabled:opacity-60"
              disabled={disabled}
              onClick={() => onChoose(null)}
            >
              <X className="h-3.5 w-3.5" aria-hidden />
              {what === "sketch"
                ? "Go back to the photograph chosen above"
                : `Go back to the photographs attached to this ${what}`}
            </button>
          </div>
        </div>
      ) : (
        <button
          ref={summaryRef}
          type="button"
          className="flex w-full items-start gap-2 text-left"
          aria-expanded={showing}
          aria-controls={showing ? sectionId : undefined}
          onClick={() => setOpen((current) => !current)}
        >
          <ImagePlus className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <span className="min-w-0 flex-1">
            {/*
              THE SAME NAME ON BOTH HALVES, WHICH IS THE HALF OF THIS THAT MUST NOT VARY. It is one
              act — measure a picture that is not the one this card would otherwise use — and a
              designer who learned it in one section has to find it under the same words in the
              other. Only the reason underneath changes, for the reason this function's own note
              gives.
            */}
            <span className="block text-xs font-medium text-ink-900">Measure a different photograph</span>
            <span className="mt-0.5 block text-xs leading-5 text-ink-500">
              {what === "sketch"
                ? "The sheet worth tracing and the photograph with a ruler beside the object are often two different pictures. This measures one that is not the one being traced, and files neither."
                : "The photographs a prototype is judged by and the photograph with a ruler beside it are rarely the same picture — one is a clean turn against a plain background, the other has a scale card lying in the frame. This measures one that is on no record at all, and files it nowhere."}
            </span>
          </span>
          <ChevronDown
            className={`mt-0.5 h-4 w-4 shrink-0 text-ink-500 transition-transform ${showing ? "rotate-180" : ""}`}
            aria-hidden
          />
        </button>
      )}

      {showing ? (
        <div id={sectionId} className="mt-3">
          <DropCard
            label={inForce ? "A different photograph to measure" : "Photograph to measure"}
            buttonLabel={inForce ? "Choose another one" : "Choose a photograph to measure"}
            accept={TRACEABLE_ACCEPT}
            acceptSentence={`${TRACEABLE_IMAGE_TYPES}, wherever this browser can read them. It is only displayed and measured — nothing here reads its pixels, re-encodes it or sends it anywhere, so nothing is reduced and there is no ${DECODE_MAX_EDGE_PX}px ceiling on this one.`}
            disabled={disabled}
            /*
              THE SAME REFUSALS AS THE SHARED CARD, MINUS THE ONE THAT DOES NOT APPLY. An SVG is
              refused there because tracing vector art is a round trip that can only lose; here there
              is no trace, so the reason is different and smaller — `PhotoMeasureField` draws into an
              `<img>` and measures in its natural pixels, and an SVG has no natural pixel size to
              measure in. Everything else is permissive for the reason the shared card gives: a phone
              camera roll hands over HEIC and AVIF with an EMPTY `type` on several platforms.
            */
            validate={(candidate) => {
              if (candidate.type === "image/svg+xml") {
                return "an SVG has no fixed pixel size, so a mark placed on it cannot be turned into a length. Use the photograph itself.";
              }
              if (candidate.type === "" || candidate.type.startsWith("image/")) return null;
              return `this is ${candidate.type}, not a photograph. A dimension is measured off an image.`;
            }}
            onFiles={(files) => {
              const chosen = files[0];
              if (chosen) onChoose(chosen);
            }}
          />
        </div>
      ) : null}
    </div>
  );
}
