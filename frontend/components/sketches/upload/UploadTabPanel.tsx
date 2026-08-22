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
 * `MediaField`'s `attach` — so whatever the host wires these to inherits eager pre-upload, multipart,
 * per-file retry and the offline draft store, none of which this unit reimplements. An extra that
 * uploaded its own file would be a second upload path to keep working offline, in an application
 * whose whole point is working offline.
 *
 * THE FOUR REGISTRY FIELDS THIS SURFACES, AND WHY THEIR LABELS ARE PROPS RATHER THAN CONSTANTS.
 * `sketch.image` and `sketch.lineArtFile` on stage 11; `prototype.turntablePhotos` and
 * `prototype.modelFile` on stage 13. Their labels live in the registry, the registry is served over
 * the wire, and a label hardcoded here would be a second copy that drifts the first time somebody
 * renames a field in `stage_definitions.py`. The host reads them from the schema it already has.
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
  onAttachSketch: (file: File) => void;
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
  onAttachSketchSource?: (file: File) => void;
  /** The 3D model file. */
  onAttachModel: (file: File) => void;
}

type Section = "sketch" | "prototype";

export function UploadTabPanel({
  sketchTargetLabel,
  modelLabel,
  turntableLabel,
  turntableCount,
  disabled,
  onAttachSketch,
  onAttachSketchSource,
  onAttachModel
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
            />
          </div>
        </div>
      )}
    </div>
  );
}
