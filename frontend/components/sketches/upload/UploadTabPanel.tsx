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
 */

import { useState } from "react";
import { Layers, PencilRuler } from "lucide-react";

import { PrototypeModelField } from "./PrototypeModelField";
import { SketchTraceField } from "./SketchTraceField";

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
  onAttachTurntable
}: UploadTabPanelProps) {
  const [section, setSection] = useState<Section>("sketch");

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
          <p className="mt-1 max-w-prose text-sm leading-6 text-ink-500">
            The panel below is where a sketch is attached, in both forms. Choose the photograph of the sheet
            and it can be filed exactly as it is — “Attach the photograph only” does nothing else. If the
            drawing is hard to read in it, which a phone photograph on a courtyard table usually is, the same
            panel traces clean line art from it on this device and files that alongside, in
            “{sketchTargetLabel}”. The photograph is never altered and never replaced.
          </p>
          <SketchTraceField
            targetLabel={sketchTargetLabel}
            disabled={disabled}
            onAttach={onAttachSketch}
            onAttachSource={onAttachSketchSource}
          />
        </div>
      ) : (
        <div className="panel p-4">
          <h3 className="font-display text-base font-semibold text-ink-900">Prototypes</h3>
          <p className="mt-1 max-w-prose text-sm leading-6 text-ink-500">
            A prototype reaches a reviewer two ways, and they are not equal. Photographs of it turning are
            placed in the ministry document as pictures; a 3D model file is listed there only as a count. Both
            are worth attaching — the panel below says which does what.
          </p>
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
        </div>
      )}
    </div>
  );
}
