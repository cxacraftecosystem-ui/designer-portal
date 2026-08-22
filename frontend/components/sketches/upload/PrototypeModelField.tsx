"use client";

/**
 * The 3D half of the UPLOAD tab: the model file, and the honest account of what becomes of it.
 *
 * THERE IS NO RIVAL FIELD HERE AND THERE MUST NOT BE. The registry already declares both halves of
 * this on the prototype entity, and this panel is a surface over them, never a second place to keep a
 * prototype:
 *
 *     f("turntablePhotos", "360° capture", IMGS, A, phase_note="Reviewer: “Kumar da team”."),
 *     f("modelFile",       "3D model",     FILE, A, phase_note="Reviewer: “Kumar da team”."),
 *
 * (`backend/app/services/stage_definitions.py`, the prototype block — read on 2026-08-22.) Both were
 * asked for by the same reviewer and both already exist. Adding a third field for "the 3D thing"
 * would give one prototype two answers to one question.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * WHY THIS PANEL LEADS WITH THE TURNTABLE AND NOT WITH THE MODEL
 * ────────────────────────────────────────────────────────────────────────────
 *
 * Because of what the ministry document can carry, which is not a matter of opinion. In
 * `backend/app/services/report_builder.py`, `_images` — "the only placement path there is" — filters
 * on `FieldType.IMAGE` and `FieldType.IMAGE_LIST` (lines 1122–1123). Every other media type falls to
 * the branch above it, whose comment is unusually blunt about the bug that produced it:
 *
 *     "FILE, AUDIO AND VIDEO HAVE NO IMAGE PATH TO BE PLACED BY … A designer attached the ministry's
 *      sanction order at stage 1 and the .docx the officer received did not mention that a sanction
 *      order existed."
 *
 * That is now fixed as far as it can be, and the fix is a sentence: a FILE field prints
 * `"1 document attached"` — a count and a noun, deliberately not even a filename, because the module
 * is also the on-device report builder and may not query for one.
 *
 * So: **`turntablePhotos` is an IMAGE_LIST and reaches the printed page as pictures. `modelFile` is a
 * FILE and reaches it as the words "1 document attached" — and no viewer built into this application
 * can change that**, because the constraint is in the document generator, not in the browser. A
 * designer who uploads only a .glb has, from the officer's point of view, uploaded nothing they can
 * see. That is worth one paragraph on screen at the moment they are choosing, and it is the reason
 * this panel exists at all rather than a bare file input.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * WHY THERE IS NO IN-BROWSER VIEWER IN THIS WAVE
 * ────────────────────────────────────────────────────────────────────────────
 *
 * Not because it would be hard, and not because it is unwanted — but this is the honest accounting:
 *
 *  - `frontend/package.json` has 29 dependencies and **none of them can render a 3D model** (checked
 *    for three, babylon, model-viewer, gltf and webgl on 2026-08-22). A viewer therefore means a new
 *    dependency, and `package.json` is not this unit's file to change.
 *  - The unit's own brief permits a viewer only against a **real measured gzipped cost**. A figure
 *    cannot be measured for a library that has not been installed, and this repository's rule is that
 *    a number is quoted only by whoever ran the command that produced it. Quoting three.js's
 *    advertised size here would be exactly the fabricated citation the last audit wave was full of.
 *  - And it would buy less than it appears to. A viewer helps the designer standing at this screen; it
 *    does nothing for the officer reading the .docx, who is the person the turntable is for.
 *
 * So this wave makes the model file **uploadable and retrievable** — which is what makes it useful to
 * the next designer, who opens it in the CAD tool they already have — and spends its screen space on
 * getting the turntable filled in. If a viewer is added later, dynamic-import it, and measure it.
 */

import { useId, useState } from "react";
import { AlertTriangle, Box, Camera, Check, FileBox } from "lucide-react";

/**
 * The model formats worth naming, and the one that travels best.
 *
 * GLB IS NAMED FIRST DELIBERATELY. It is a single self-contained file, where OBJ arrives as a mesh
 * plus an .mtl plus a folder of textures — three of which a designer will attach and one of which
 * they will forget, producing an untextured grey blob for whoever opens it next. A field that holds
 * ONE file rewards a format that is one file, and saying so at the moment of choosing is cheaper than
 * discovering it a month later.
 */
const MODEL_FORMATS = [
  { ext: "glb", label: "GLB", note: "One self-contained file — mesh, materials and textures together. The best choice for this field." },
  { ext: "gltf", label: "glTF", note: "Usually a mesh plus separate texture files. Attach the .glb version where you have one." },
  { ext: "stl", label: "STL", note: "Geometry only, no colour. What most 3D printers expect." },
  { ext: "obj", label: "OBJ", note: "Geometry plus a separate .mtl and textures, which this single field cannot hold together." },
  { ext: "ply", label: "PLY", note: "Common output of a photogrammetry or scanning app." },
  { ext: "3mf", label: "3MF", note: "A printing format that does carry colour and materials in one file." },
  { ext: "fbx", label: "FBX", note: "Common from animation tools; large, and not every CAD package reads it." },
  { ext: "usdz", label: "USDZ", note: "What an iPhone's own scanner produces." }
] as const;

const MODEL_ACCEPT = MODEL_FORMATS.map((f) => `.${f.ext}`).join(",");

/**
 * Above this a model file is worth a word before it is attached.
 *
 * Not a refusal — the upload path handles large files properly, with multipart and per-file retry, and
 * a genuinely dense scan legitimately runs to hundreds of megabytes. But the designer standing in a
 * courtyard on a shared hotspot is the person who most needs to be told BEFORE the transfer starts,
 * not after it has been running for ten minutes.
 */
const LARGE_MODEL_BYTES = 64 * 1024 * 1024;

/**
 * How many frames a turntable wants.
 *
 * Twelve is one photograph every thirty degrees, which is the coarsest capture that still reads as
 * rotation rather than as a handful of unrelated views. Twenty-four is every fifteen degrees and is
 * what a reviewer can actually judge a form from. Both numbers are stated on screen rather than
 * enforced: a prototype photographed eight times is still better than one photographed never.
 */
const TURNTABLE_MINIMUM = 12;
const TURNTABLE_COMFORTABLE = 24;

export interface PrototypeModelFieldProps {
  /** The registry label of the FILE field the model lands in — "3D model" on the prototype entity. */
  modelLabel: string;
  /** The registry label of the IMAGE_LIST beside it — "360° capture". Named so the two are unambiguous. */
  turntableLabel: string;
  /** How many turntable frames the record already holds, when the host can say. */
  turntableCount?: number;
  disabled?: boolean;
  /** Hands the model file to the host — the ordinary door, exactly as the tracing panel does. */
  onAttachModel: (file: File) => void;
}

export function PrototypeModelField({
  modelLabel,
  turntableLabel,
  turntableCount,
  disabled,
  onAttachModel
}: PrototypeModelFieldProps) {
  /**
   * The prefix every DOM id on this panel is built from.
   *
   * NOT A CONSTANT STRING, BECAUSE THIS PANEL IS NOT A SINGLETON. One prototype row per prototype and
   * a dialog copy over the top of it are both ordinary, and two `id={`${fieldId}-model-file`}` inputs in
   * one document make the label point at whichever came first — so the label beside the second input
   * focuses the first one, and a screen reader names the wrong control. `SketchTraceField` derives
   * every id the same way for the same reason.
   */
  const fieldId = useId();
  const [chosen, setChosen] = useState<File | null>(null);
  const [warning, setWarning] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  function choose(file: File) {
    setDone(null);
    setChosen(file);
    const extension = file.name.split(".").pop()?.toLowerCase() ?? "";
    const known = MODEL_FORMATS.find((f) => f.ext === extension);
    const notes: string[] = [];
    if (!known) {
      notes.push(
        `“.${extension}” is not one of the model formats this field expects. It will still be stored and can ` +
          "still be downloaded, but the next designer may not have anything that opens it."
      );
    } else if (known.ext === "obj" || known.ext === "gltf") {
      notes.push(`${known.label}: ${known.note}`);
    }
    if (file.size > LARGE_MODEL_BYTES) {
      notes.push(
        `This file is ${formatBytes(file.size)}. It will upload in parts and will resume if the connection ` +
          "drops, but on a shared hotspot that is a long transfer — worth starting somewhere with signal."
      );
    }
    setWarning(notes.length > 0 ? notes.join(" ") : null);
  }

  function attach() {
    if (chosen === null) return;
    onAttachModel(chosen);
    setDone(`${chosen.name} was added to “${modelLabel}”.`);
    setChosen(null);
    setWarning(null);
  }

  const frames = turntableCount ?? 0;
  const turntableTone =
    frames >= TURNTABLE_COMFORTABLE ? "good" : frames >= TURNTABLE_MINIMUM ? "fair" : "thin";

  return (
    <div className="grid gap-3">
      {/* ── The turntable comes first, because it is the half that prints ─────────────── */}
      <div className="rounded-lg border border-line-200 bg-card p-3">
        <div className="flex items-start gap-2">
          <span className="mt-0.5 grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-field-200 text-field-600">
            <Camera className="h-4 w-4" aria-hidden />
          </span>
          <div className="min-w-0">
            <h4 className="font-display text-sm font-semibold text-ink-900">
              “{turntableLabel}” is the 3D view a reviewer will actually see
            </h4>
            <p className="mt-1 text-xs leading-5 text-ink-500">
              The ministry document places image fields as pictures and prints every other kind of attachment
              as a count — a 3D model appears in it as the words “1 document attached”, and no viewer built
              into this application can change that, because the limit is in the document generator rather
              than in the browser. A turn of photographs is the only form of this prototype that reaches the
              printed page.
            </p>
            <p className="mt-2 text-xs leading-5 text-ink-500">
              Stand the piece still and move around it, one photograph every 30° for {TURNTABLE_MINIMUM} frames, or
              every 15° for {TURNTABLE_COMFORTABLE} — enough that a reviewer can read the form rather than guess it.
              Keep the light and the background the same for all of them.
            </p>
            {turntableCount !== undefined ? (
              <p
                className={
                  turntableTone === "good"
                    ? "mt-2 inline-flex items-center gap-1.5 rounded-md bg-success-100 px-2 py-1 text-xs font-medium text-success-600"
                    : turntableTone === "fair"
                      ? "mt-2 inline-flex items-center gap-1.5 rounded-md bg-amber-100 px-2 py-1 text-xs font-medium text-amber-800"
                      : "mt-2 inline-flex items-center gap-1.5 rounded-md bg-field-100 px-2 py-1 text-xs font-medium text-ink-700"
                }
              >
                {turntableTone === "good" ? <Check className="h-3.5 w-3.5" aria-hidden /> : null}
                {frames === 0
                  ? "No frames yet"
                  : frames === 1
                    ? "1 frame so far"
                    : `${frames} frames so far`}
                {turntableTone === "good"
                  ? " — a full turn"
                  : turntableTone === "fair"
                    ? ` — readable; ${TURNTABLE_COMFORTABLE} is a full turn`
                    : ` — ${TURNTABLE_MINIMUM} is the fewest that reads as a turn`}
              </p>
            ) : null}
          </div>
        </div>
      </div>

      {/* ── The model file ───────────────────────────────────────────────────────────── */}
      <div className="rounded-lg border border-line-200 bg-card p-3">
        <div className="flex items-start gap-2">
          <span className="mt-0.5 grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-field-200 text-field-600">
            <Box className="h-4 w-4" aria-hidden />
          </span>
          <div className="min-w-0 flex-1">
            <h4 className="font-display text-sm font-semibold text-ink-900">“{modelLabel}”</h4>
            <p className="mt-1 text-xs leading-5 text-ink-500">
              Worth attaching even though it does not print: it is the only form another designer can measure,
              re-scale, section or send to a printer. They download it and open it in the tool they already
              have — which is why the format matters more than any viewer would.
            </p>

            <div className="mt-2">
              <label className="field-label" htmlFor={`${fieldId}-model-file`}>
                Model file
              </label>
              <input
                id="prototype-model-file"
                type="file"
                accept={MODEL_ACCEPT}
                className="field-input mt-1 file:mr-3 file:rounded-md file:border-0 file:bg-purple-700 file:px-3 file:py-1.5 file:text-xs file:font-medium file:text-white"
                disabled={disabled}
                onChange={(event) => {
                  const file = event.target.files?.[0];
                  if (file) choose(file);
                }}
              />
            </div>

            <details className="mt-2">
              <summary className="cursor-pointer text-xs font-medium text-purple-700">
                Which format should I attach?
              </summary>
              <dl className="mt-2 grid gap-1.5">
                {MODEL_FORMATS.map((entry) => (
                  <div key={entry.ext} className="text-xs leading-4">
                    <dt className="inline font-medium text-ink-900">{entry.label} </dt>
                    <dd className="inline text-ink-500">— {entry.note}</dd>
                  </div>
                ))}
              </dl>
            </details>

            {chosen ? (
              <div className="mt-3 rounded-md border border-line-200 bg-surface-50 p-2">
                <p className="flex items-center gap-2 text-xs font-medium text-ink-900">
                  <FileBox className="h-3.5 w-3.5 shrink-0" aria-hidden />
                  <span className="truncate">{chosen.name}</span>
                  <span className="shrink-0 text-ink-500">{formatBytes(chosen.size)}</span>
                </p>
                {warning ? (
                  <p className="mt-2 flex items-start gap-2 rounded-md bg-amber-100 px-2 py-1.5 text-xs leading-4 text-amber-800">
                    <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
                    <span>{warning}</span>
                  </p>
                ) : null}
                <div className="mt-2 flex flex-wrap gap-2">
                  <button type="button" className="field-button" onClick={attach} disabled={disabled}>
                    Add to “{modelLabel}”
                  </button>
                  <button
                    type="button"
                    className="field-button-secondary"
                    onClick={() => {
                      setChosen(null);
                      setWarning(null);
                    }}
                  >
                    Choose another
                  </button>
                </div>
              </div>
            ) : null}

            {done ? (
              <p className="mt-2 flex items-start gap-2 text-xs text-ink-500">
                <Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-success-600" aria-hidden />
                <span>{done}</span>
              </p>
            ) : null}
          </div>
        </div>
      </div>
    </div>
  );
}

/**
 * @returns a size a person reads, in the units a phone shows.
 *
 * Binary units against decimal prefixes, which is technically the wrong pairing and is what every
 * file manager on every device these designers own does — a card that disagreed with the operating
 * system about how big a file is would be read as a bug in the card.
 */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "unknown size";
  if (bytes < 1024) return `${Math.round(bytes)} B`;
  const units = ["KB", "MB", "GB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`;
}
