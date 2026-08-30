"use client";

/**
 * The body of the UPLOAD tab on "Sketches and Prototypes".
 *
 * WHAT THIS IS AND IS NOT. It is the composition of this unit's two panels and nothing else — no
 * page, no tab strip, no header, no routing. The page and its UPLOAD/REVIEW tabs belong to another
 * unit; this is the thing that unit mounts inside the UPLOAD one:
 *
 *     <UploadTabPanel
 *       sketchTargetLabel="Line art"
 *       modelLabel="3D model"
 *       turntableLabel="360° capture"
 *       onAttachSketch={...}
 *       onAttachSketchSource={...}
 *       onAttachModel={...}
 *       onAttachTurntable={...}
 *     />
 *
 * `onAttachSketchSource` IS OPTIONAL IN THE TYPE AND NECESSARY ON THIS TAB, which is a distinction
 * worth stating rather than leaving to be discovered. It is what files the PHOTOGRAPH, and the tracing
 * panel below is the only picker for it here — so a host that omits it gives a designer no way to
 * upload the photograph of the sheet at all, and the panel changes its own copy to match (see
 * `SketchTraceField`'s `onAttachSource`). A record form, which has its own image field, is the host
 * that rightly leaves it out.
 *
 * THE ATTACH CALLBACKS ARE THE WHOLE INTERFACE, AND THAT IS DELIBERATE. Nothing in this directory
 * uploads anything, presigns anything, or knows what a media id is. Each panel produces a `File` and
 * hands it over, exactly as `components/designworkshop/SketchRectifyField.tsx` hands its plate to
 * `MediaField`'s `attach`. An extra that uploaded its own file would be a second upload path to keep
 * working offline, in an application whose whole point is working offline.
 *
 * WHAT THE HOST DOES WITH THE FILE IS THE HOST'S BUSINESS, AND THE TWO HOSTS DO IT DIFFERENTLY. This
 * paragraph used to promise that a callback "inherits eager pre-upload, multipart, per-file retry and
 * the offline draft store", which described `components/designworkshop/FieldInput.tsx` — it stages
 * the bytes locally AND calls `uploadMediaBatch` in the same breath. `UploadTabHost` does not: it
 * writes the file into the local draft and then asks `syncDesignWorkshopDrafts` to carry the draft up,
 * which is the only path that moves the bytes and the row that points at them in the right order.
 * The designer-visible outcome is the same and the mechanism is not, and for a while the difference
 * was a whole missing hop — the host staged and never synced, so a file could sit in IndexedDB under
 * a notice saying it had been uploaded. See that file's header.
 *
 * THE FOUR REGISTRY FIELDS THIS SURFACES, AND WHY THEIR LABELS ARE PROPS RATHER THAN CONSTANTS.
 * `sketch.image` and `sketch.lineArtFile` on stage 11; `prototype.turntablePhotos` and
 * `prototype.modelFile` on stage 13. Their labels live in the registry, the registry is served over
 * the wire, and a label hardcoded here would be a second copy that drifts the first time somebody
 * renames a field in `stage_definitions.py`. The host reads them from the schema it already has.
 *
 * ALL FOUR ARE NOW WRITABLE FROM HERE. For a while only three were: `turntablePhotos` had a label
 * prop, a count prop and two paragraphs of advice about how much it mattered, and no callback — so
 * the one field on this surface that reaches the printed report AS PICTURES was the one field this
 * tab could not fill. It was closed rather than the advice removed, because the advice is right.
 *
 * ── AND ONE SLOT, WHICH IS NOT A CALLBACK AND IS NOT A FIFTH FIELD (2026-08-28) ─────────────────
 *
 * {@link UploadTabPanelProps.sketchMeasure} and {@link UploadTabPanelProps.prototypeMeasure} take
 * rendered nodes rather than data, and that is what keeps the paragraph above true. The measuring
 * card needs a DISPLAYABLE URL for every photograph already attached to the chosen row, which means
 * resolving media ids and `dwlocal:` references out of the draft store — precisely the knowledge
 * this directory is built not to have. So the host, which already owns all of it, builds the card
 * and this file only says WHERE it goes: at the foot of the section it belongs to, so it follows the
 * designer between Sketches and Prototypes instead of sitting under both.
 *
 * A slot rather than four more props is the arrangement `FieldInput`'s `MediaField` already uses for
 * its `extra` render prop, and for the same reason: what the extra needs is the host's business, and
 * a panel that typed it would have to be edited every time the extra learned something new.
 *
 * ── AND ONE PHOTOGRAPH, CHOSEN ONCE, THAT BOTH PANELS IN THE SKETCH HALF WORK FROM (2026-08-29) ──
 *
 * {@link UploadTabPanelProps.sketchPhotograph} is the other half of the change `SharedPhotoField`'s
 * header describes. Before it, the tracing panel owned the only picker on this half and the
 * measuring card owned none, so the same photograph reached the two of them by two different routes
 * — a decode here, and an attach + `putDraftStage` + sync + reload + `useMeasurablePhotos` there —
 * and a designer had to file before they could measure. The picker is now ONE card at the top of
 * this section, the host owns what it produced, and both panels are handed the same photograph.
 *
 * THE PICKER MOVED; IT WAS NOT DUPLICATED. `SketchTraceField` still owns its own picker on the host
 * that has no other one (a record form's stage field, `FieldInput.tsx`), because there the
 * photograph belongs to that form's own image field and this section does not exist. The panel
 * chooses between the two by whether it was HANDED a photograph — see its `photograph` prop, which
 * distinguishes "absent" from "null" for exactly this reason.
 */

import type { ReactNode } from "react";
import { useState } from "react";
import { Layers, PencilRuler } from "lucide-react";

import { PrototypeModelField } from "./PrototypeModelField";
import { SharedPhotoField } from "./SharedPhotoField";
import { SketchTraceField } from "./SketchTraceField";
import type { DecodedPixels } from "./decodeToPixels";

/**
 * A photograph the host has chosen on behalf of both panels in the Sketches half.
 *
 * ── ONE FILE, TWO DERIVATIONS, AND NEITHER PANEL CAN CONSUME THE OTHER'S ────────────────────────
 *
 * The tracing panel needs {@link DecodedPixels} — `decodeToPixels` resizes and calls `getImageData`,
 * which on a handset is hundreds of milliseconds for a 4096px photograph, and every stage below it
 * (`transferableFrom`, `buildComparisonPlates`, `FramePanel`) reads pixels and never the `File`. The
 * measuring panel needs a displayable URL and NOTHING else — `PhotoMeasureField`'s own header says
 * so under "NO NETWORK, NO CANVAS READBACK, NO RE-ENCODING", which is why it works on a cross-origin
 * presigned URL with no bucket CORS rule at all. So "decode once and give both cards the result" is
 * one decode and one `URL.createObjectURL`, from one `File`, in one owner.
 *
 * `id` IS THE IDENTITY EVERYTHING DOWNSTREAM MEMOISES ON, and it is a number minted by the host at
 * the moment of the pick rather than the `File` object or this record. A consumer keyed on this
 * object would re-run every time the decode settled — which is a second, identical object with the
 * pixels filled in — and a consumer keyed on nothing would re-decode on every keystroke anywhere in
 * the panel. `id` changes when, and only when, a different photograph was chosen.
 *
 * `pixels` IS NULL WHILE THE DECODE IS STILL RUNNING, and null again when it failed — the two are
 * told apart by `problem`, which carries the sentence `decodeToPixels` wrote. A panel that read a
 * null as "no photograph" would print its empty state over a photograph that is on screen.
 */
export interface ChosenPhotograph {
  /** Monotonic, minted at the pick. See above: this is what identity means here. */
  readonly id: number;
  readonly file: File;
  /** Created and revoked by the host, in one effect. Never created by a panel. */
  readonly url: string;
  /** The decode, once it has settled. Null while it is running AND when it failed. */
  readonly pixels: DecodedPixels | null;
  /** Why this photograph could not be decoded, in `decodeToPixels`'s own words, or null. */
  readonly problem: string | null;
}

export interface UploadTabPanelProps {
  /** Registry label of the field a traced drawing lands in — `sketch.lineArtFile`. */
  sketchTargetLabel: string;
  /** Registry label of `prototype.modelFile`. */
  modelLabel: string;
  /** Registry label of `prototype.turntablePhotos`. */
  turntableLabel: string;
  /** How many turntable frames the record already holds, when the host can say. */
  turntableCount?: number;
  disabled?: boolean;
  /** The traced line art. */
  onAttachSketch: (file: File) => AttachAnswer;
  /**
   * The photograph the designer chose in the tracing panel.
   *
   * Separate from {@link onAttachSketch} because they land in DIFFERENT registry fields and must:
   * `sketch.image` holds the photograph and `sketch.lineArtFile` holds the drawing. Writing a derived
   * plate into the image field would REPLACE the photograph — a single IMAGE field replaces its value
   * when a file is attached to it — which is the outcome `docs/MEDIA_PIPELINE.md` §5 refuses in the
   * words "the original file *is* the artifact". Keeping two callbacks is what makes that
   * impossible here rather than merely discouraged.
   */
  onAttachSketchSource?: (file: File) => AttachAnswer;
  /** The 3D model file. */
  onAttachModel: (file: File) => AttachAnswer;
  /**
   * The turntable frames — `prototype.turntablePhotos`, an IMAGE_LIST, so a LIST and not a file.
   *
   * OPTIONAL FOR THE SAME REASON {@link onAttachSketchSource} IS, and necessary on this tab for a
   * stronger one. A host with its own image field (a record form) rightly leaves it out; this tab is
   * the only screen where the panel below advises filling this field, and until this prop existed it
   * advised a field nothing on the screen could write. The panel changes its own copy to match —
   * without the callback it says where the frames have to be added instead of offering a picker,
   * rather than silently dropping the advice.
   *
   * A LIST because a turn is one action: twelve frames chosen in one file dialog are one thing the
   * designer did, and splitting them into twelve attaches would write the stage twelve times and
   * print twelve notices. See `UploadTabHost.attach`.
   */
  onAttachTurntable?: (files: File[]) => AttachAnswer;
  /**
   * The "Measure a dimension from a photograph" card for the SKETCH the host has chosen.
   *
   * Rendered at the foot of the Sketches section. Optional because a host with no way to resolve a
   * photograph to a URL — a record form mounting this panel into its own image field — has nothing
   * to put here, and an absent slot renders nothing rather than an empty box. See the header for why
   * this is a node and not four typed props.
   */
  sketchMeasure?: ReactNode;
  /** The same card for the chosen PROTOTYPE, at the foot of the Prototypes section. */
  prototypeMeasure?: ReactNode;
  /**
   * The one photograph both panels in the Sketches section work from, or null for none yet.
   *
   * OPTIONAL, AND ITS ABSENCE IS A MODE RATHER THAN A DEFAULT — the same distinction
   * `onAttachSketchSource` carries. A host that passes neither this nor {@link onChooseSketchPhoto}
   * gets the arrangement that shipped before this prop existed: no card at the top of the section,
   * and the tracing panel owning its own picker. Passing both moves the picker up here.
   */
  sketchPhotograph?: ChosenPhotograph | null;
  /** The registry label of `sketch.image`, quoted by the shared card's copy. */
  sketchImageLabel?: string;
  /** The chosen sketch row, as the designer sees it named — for the same copy. */
  sketchRowName?: string | null;
  /**
   * Whether {@link sketchPhotograph} is already on the row named by {@link sketchRowName}.
   *
   * A CLAIM ABOUT ONE ROW, NOT ABOUT THE PHOTOGRAPH, which is why the host answers it rather than
   * this panel deriving it from anything here. The same picture is filed for the sketch it was
   * attached to and filed nowhere at all for the next one down the picker, so the answer changes
   * when the designer moves the row picker and nothing about the photograph changed. `UploadTabHost`
   * keeps the pairing (`sketchPhotoOnRow`) and hands down the resolved fact.
   *
   * IT EXISTS BECAUSE THE CARD ABOVE WAS THE LAST THING ON THE SCREEN STILL SAYING "not yet". Both
   * of the tracing panel's buttons file the photograph through {@link onAttachSketchSource}, and the
   * measuring card switches its own sentence the instant they do — so without this the section
   * showed a card claiming nothing had been written directly above a card measuring the thing that
   * had been.
   */
  sketchPhotoFiled?: boolean;
  /** A new photograph for the whole section, or `null` to put the current one away. */
  onChooseSketchPhoto?: (file: File | null) => void;
}

/**
 * WHAT A HOST SAYS BACK WHEN IT IS HANDED A FILE, AND WHY THE PANELS HAVE TO ASK.
 *
 * ── THE DEFECT THIS TYPE ENDS ────────────────────────────────────────────────────────────────────
 *
 * These callbacks used to return `void`, so a panel had no way to learn that the file it had just
 * handed over went nowhere — and every panel in this directory printed its green "N photographs were
 * added to …" line unconditionally, immediately after the call. `UploadTabHost` can refuse
 * SYNCHRONOUSLY (`refuse("prototype")` when no row is chosen, or when the repository's copy of the
 * stage could not be read) and can fail its IndexedDB write a moment later. Either way the host's red
 * sentence rendered directly above the panel's green tick, one of them a lie, on the surface whose
 * whole job is telling a designer whether their file is safe.
 *
 * ── WHY `false` AND NOT A THROWN ERROR ───────────────────────────────────────────────────────────
 *
 * The host has already SAID what went wrong, in its own words, next to the picker the designer used.
 * A throw would make each panel invent a second sentence about a failure it cannot describe — and
 * this repository has the scar for that: `ReviewPanel.persist` printing "this could not be saved on
 * this device" over a write that had already landed. `false` means "I have told them; do not claim
 * this worked".
 *
 * ── AND WHY `void` IS STILL IN THE UNION ─────────────────────────────────────────────────────────
 *
 * A record form that mounts one of these panels into its own image field has nothing to report: the
 * attach is a state update that cannot fail. Requiring it to return `true` would be ceremony, and the
 * panels therefore suppress their claim ONLY on an explicit `false` — `undefined` is the unchanged
 * behaviour of every host that has no answer to give.
 */
export type AttachAnswer = void | boolean | Promise<void | boolean>;

type Section = "sketch" | "prototype";

export function UploadTabPanel({
  sketchTargetLabel,
  modelLabel,
  turntableLabel,
  turntableCount,
  disabled,
  onAttachSketch,
  onAttachSketchSource,
  onAttachModel,
  onAttachTurntable,
  sketchMeasure,
  prototypeMeasure,
  sketchPhotograph,
  sketchImageLabel,
  sketchRowName,
  sketchPhotoFiled,
  onChooseSketchPhoto
}: UploadTabPanelProps) {
  const [section, setSection] = useState<Section>("sketch");
  /*
    THE HOST OWNS THE PICKER ONLY IF IT SAID IT WOULD, and "said" means handing over the callback.
    Reading `sketchPhotograph` alone would be wrong twice: it is legitimately null on a host that
    owns the picker and has not been given a photograph yet, and it is undefined on a host that never
    intended to. The callback is the half that cannot be ambiguous.
  */
  const photoOwnedAbove = onChooseSketchPhoto !== undefined;

  return (
    <div className="grid gap-4">
      {/* Two sections rather than one long column, because a designer arrives holding either a sheet
          of paper or an object, never both, and the half they did not come for is noise. Real buttons
          with `aria-pressed`, so the choice is reachable by keyboard and announced. */}
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          className={
            section === "sketch"
              ? "inline-flex items-center gap-2 rounded-md border border-purple-600 bg-purple-50 px-3 py-2 text-sm font-medium text-purple-800"
              : "inline-flex items-center gap-2 rounded-md border border-line-200 bg-card px-3 py-2 text-sm font-medium text-ink-700 transition hover:border-purple-300"
          }
          aria-pressed={section === "sketch"}
          onClick={() => setSection("sketch")}
        >
          <PencilRuler className="h-4 w-4" aria-hidden />
          Sketches
        </button>
        <button
          type="button"
          className={
            section === "prototype"
              ? "inline-flex items-center gap-2 rounded-md border border-purple-600 bg-purple-50 px-3 py-2 text-sm font-medium text-purple-800"
              : "inline-flex items-center gap-2 rounded-md border border-line-200 bg-card px-3 py-2 text-sm font-medium text-ink-700 transition hover:border-purple-300"
          }
          aria-pressed={section === "prototype"}
          onClick={() => setSection("prototype")}
        >
          <Layers className="h-4 w-4" aria-hidden />
          Prototypes
        </button>
      </div>

      {section === "sketch" ? (
        <div className="panel p-4">
          <h3 className="font-display text-base font-semibold text-ink-900">Sketches</h3>
          {/*
            THE COPY FOLLOWS THE CONTROLS, WHICH IS NOT AUTOMATIC AND HAS BEEN WRONG HERE BEFORE.
            This paragraph used to say "the panel below is where a sketch is attached" and "choose the
            photograph" in the same breath, which was true while the tracing panel owned the only
            picker. The photograph is chosen ONCE at the top of this section now, and a sentence
            pointing at the wrong control is the failure this repository files under copy written from
            copy: the designer follows the words on the screen they are on.
          */}
          <p className="mt-1 max-w-prose text-sm leading-6 text-ink-500">
            {photoOwnedAbove
              ? "Photograph the sheet once. Both panels below work from that one photograph — the tracing panel turns it into clean line art on this device, which a phone photograph on a courtyard table usually needs, and the measuring panel takes a dimension off it. Nothing is filed until a button in one of them is pressed, and the photograph itself is never altered."
              : "The panel below is where a sketch is attached, in both forms. Choose the photograph of the sheet and it can be filed exactly as it is — “Attach the photograph only” does nothing else. If the drawing is hard to read in it, the same panel traces clean line art from it on this device and files that alongside. The photograph is never altered and never replaced."}
          </p>
          {/*
            WHERE THE DRAWING LANDS, ON ITS OWN LINE, because it is the one fact in the paragraph
            above that comes off the REGISTRY rather than out of this file — and a label the schema
            renames tomorrow must not be buried mid-sentence where nobody checks it.
          */}
          <p className="mt-1 max-w-prose text-sm leading-6 text-ink-500">
            The traced drawing is added to “{sketchTargetLabel}”, beside the photograph and never over it.
          </p>
          {/*
            ── THE PICKER, ABOVE BOTH PANELS, BECAUSE BOTH PANELS READ IT ────────────────────────

            First in the section and outside either card, which is the position the arrangement
            argues for: a control that belongs to two things underneath it cannot live inside one of
            them without the other having to reach in. It used to live inside the tracing panel —
            two clicks deep, behind a disclosure AND behind the trace engine finishing its load — so
            on a device where that engine could not load there was no way to attach the photograph of
            the sheet from this tab at all. Up here it is reachable whatever the engine did.
          */}
          {photoOwnedAbove ? (
            <div className="mt-3">
              <SharedPhotoField
                photograph={sketchPhotograph ?? null}
                imageLabel={sketchImageLabel ?? "the sketch image field"}
                rowName={sketchRowName ?? null}
                /*
                  FALSE IS THE HONEST DEFAULT AND `undefined` MUST NOT REACH THE CARD. A host that
                  owns the picker but does not track what it filed knows less than the card would be
                  claiming either way; of the two claims it could make, "not filed yet" is the one
                  that sends a designer to press the button again, and "filed" is the one that sends
                  them away from a photograph that is still only in their hand.
                */
                filed={sketchPhotoFiled ?? false}
                disabled={disabled}
                onChoose={onChooseSketchPhoto}
              />
            </div>
          ) : null}
          {/*
            `mt-3` ON THE SLOT AND `mt-3` INSIDE THE TRACING PANEL, which is one token with two
            owners rather than two tokens. The tracing panel self-applies its own top margin because
            its OTHER host drops it straight into a column of registry fields with no wrapper to hang
            one on (`FieldInput.tsx`); the measuring card is a slot this file positions. Both are the
            3-step, and a reader comparing the two cards on screen sees one gap.
          */}
          <SketchTraceField
            targetLabel={sketchTargetLabel}
            disabled={disabled}
            onAttach={onAttachSketch}
            onAttachSource={onAttachSketchSource}
            /*
              ABSENT ON A HOST THAT DID NOT TAKE THE PICKER, and the panel reads the absence rather
              than a flag: `undefined` means "you own your picker", `null` means "I own it and
              nothing is chosen". Spreading a prop bag that carries an explicit `undefined` here
              would silently give this tab two pickers again, which is why the panel's own prop
              documentation says the same thing in the same words.
            */
            photograph={photoOwnedAbove ? (sketchPhotograph ?? null) : undefined}
            /*
              THE SAME ANSWER THE CARD ABOVE PRINTS, HANDED TO THE PANEL THAT ACTS ON IT. The card
              says whether the photograph is on the chosen row; this panel's "Attach the photograph
              only" is one of the two buttons that put it there, and its own duplicate-offer guard
              remembers a file rather than a file-and-a-row — so on a host with a row picker it needs
              telling when the row moved out from under a photograph that stayed. See that panel's
              `photographFiled`. Absent on a host that owns its own picker, for the same reason
              `photograph` is: there is no row picker there to move.

              PASSED THROUGH RATHER THAN COERCED, and the difference is a real one this line got
              wrong once: `sketchPhotoFiled === true` would turn "this host does not answer that
              question" into "no, it is not filed", and the panel's guard treats those two
              differently on purpose — an unanswered question falls back to the duplicate-offer ref
              alone, while a `false` invites the panel to offer the photograph again. The card above
              coerces (it must print SOMETHING, and "not filed yet" is the safe claim); the panel
              must not, because its answer decides whether bytes are sent a second time.
            */
            photographFiled={photoOwnedAbove ? sketchPhotoFiled : undefined}
          />
          {/*
            BELOW THE TRACING PANEL, NOT ABOVE IT, and that order is the reading order of the tab:
            the photograph is chosen at the top, traced in the middle and measured at the foot, and a
            designer who has just filed a drawing is already at the bottom of the panel when the
            measuring card comes into view.
          */}
          {sketchMeasure ? <div className="mt-3">{sketchMeasure}</div> : null}
        </div>
      ) : (
        <div className="panel p-4">
          <h3 className="font-display text-base font-semibold text-ink-900">Prototypes</h3>
          <p className="mt-1 max-w-prose text-sm leading-6 text-ink-500">
            A prototype reaches a reviewer two ways, and they are not equal. Photographs of it turning are
            placed in the ministry document as pictures; a 3D model file is listed there only as a count. Both
            are worth attaching — the panel below says which does what.
          </p>
          {/*
            ── WHAT THIS HALF TAKES THAT THE OTHER ONE DOES NOT, SAID AT THE TOP ──────────────────

            The section intros are the two paragraphs a designer reads before they form an
            expectation about the controls underneath, and the Sketches one gained a line naming the
            registry field its work lands in. This is the pair of that line, and it carries one fact
            the other half has no equivalent for: this section accepts a kind of file the Sketches
            section refuses outright.

            THE FORMATS ARE NAMED WHERE THEY ARE KNOWN AND NOWHERE ELSE. `MODEL_FORMATS` lives in the
            card below, its `acceptSentence` lists every label, and the disclosure beside it says
            what each format costs — so this line points at that list rather than repeating it or
            counting it. A second copy of an accept list is a second copy that drifts, and a COUNT of
            one ("eight formats") is worse still: it is a number this file would be asserting without
            reading, which is the same defect as printing a cap nobody looked up.
          */}
          <p className="mt-1 max-w-prose text-sm leading-6 text-ink-500">
            Photographs go to “{turntableLabel}”, a whole turn at a time — the same kind of file the Sketches
            section takes. “{modelLabel}” is the one box on this tab that takes something else entirely: a 3D
            model, in the formats the card below names, and never a photograph.
          </p>
          {/*
            ── NO SHARED PICKER ON THIS HALF, AND THAT IS AN ANSWER RATHER THAN AN OMISSION ───────

            The Sketches section puts one `SharedPhotoField` above both of its panels because two of
            them read the same photograph and neither can file it — the tracing panel wants pixels,
            the measuring card wants a displayable URL, and `sketch.image` is a single IMAGE field
            that an attach REPLACES, so the designer has to be able to look before they commit.

            Nothing on this half has that shape. The two pickers below write two DIFFERENT registry
            fields of one entity — `turntablePhotos`, an append-only IMAGE_LIST, and `modelFile`, a
            FILE — so there are no shared bytes to hold; and the turntable files its frames at the
            moment of the pick, so there is no unfiled window for a shared card to cover. Hoisting a
            picker up here would mean holding files back from a field that wants them immediately, in
            a control that could not say which of the two boxes it was for. `PrototypeModelField`'s
            header carries the long form of this, and `UploadTabHost`'s `prototypeMeasure` mount
            carries the consequence: the measuring card on this half is given no held photograph.
          */}
          <div className="mt-3">
            <PrototypeModelField
              modelLabel={modelLabel}
              turntableLabel={turntableLabel}
              turntableCount={turntableCount}
              disabled={disabled}
              onAttachModel={onAttachModel}
              onAttachTurntable={onAttachTurntable}
            />
          </div>
          {/* See the note on the sketch half — same order, same gap. `PrototypeModelField` spaces its
              own two cards with `grid gap-3` where the sketch half uses per-child `mt-3`; that is one
              token drawn two ways, for the reason `MeasureFromPhotoCard` states about its own body. */}
          {prototypeMeasure ? <div className="mt-3">{prototypeMeasure}</div> : null}
        </div>
      )}
    </div>
  );
}
