"use client";

import { useState } from "react";
import { AlertTriangle, Box, Camera, Check, FileBox, Loader2 } from "lucide-react";

import { DropCard } from "./DropCard";

import type { AttachAnswer } from "@/components/sketches/upload/UploadTabPanel";
import { ActionGroup } from "@/components/ui/ActionGroup";

const MODEL_FORMATS = [
  { ext: "glb", label: "GLB", note: "One file with materials and textures. Best choice." },
  { ext: "gltf", label: "glTF", note: "Textures are separate files. Prefer .glb." },
  { ext: "stl", label: "STL", note: "Geometry only. For 3D printers." },
  { ext: "obj", label: "OBJ", note: "Materials are separate files. Prefer .glb." },
  { ext: "ply", label: "PLY", note: "From scanning apps." },
  { ext: "3mf", label: "3MF", note: "Printing, with colour." },
  { ext: "fbx", label: "FBX", note: "From animation tools. Large." },
  { ext: "usdz", label: "USDZ", note: "From iPhone scans." }
] as const;

const MODEL_ACCEPT = MODEL_FORMATS.map((f) => `.${f.ext}`).join(",");

const LARGE_MODEL_BYTES = 64 * 1024 * 1024;

const TURNTABLE_MINIMUM = 12;
const TURNTABLE_COMFORTABLE = 24;

export interface PrototypeModelFieldProps {
  modelLabel: string;
  turntableLabel: string;
  turntableCount?: number;
  disabled?: boolean;
  onAttachModel: (file: File) => AttachAnswer;
  onAttachTurntable?: (files: File[]) => AttachAnswer;
}

export function PrototypeModelField({
  modelLabel,
  turntableLabel,
  turntableCount,
  disabled,
  onAttachModel,
  onAttachTurntable
}: PrototypeModelFieldProps) {
  const [chosen, setChosen] = useState<File | null>(null);
  const [warning, setWarning] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [turntableDone, setTurntableDone] = useState<string | null>(null);
  const [turntableProblem, setTurntableProblem] = useState<string | null>(null);
  const [turntableBusy, setTurntableBusy] = useState<string | null>(null);
  const [modelBusy, setModelBusy] = useState<string | null>(null);

  function choose(file: File) {
    setDone(null);
    setChosen(file);
    const extension = file.name.split(".").pop()?.toLowerCase() ?? "";
    const known = MODEL_FORMATS.find((f) => f.ext === extension);
    const notes: string[] = [];
    if (!known) {
      notes.push(
        `“.${extension}” isn't a usual model format. It's stored, but others may not be able to open it.`
      );
    } else if (known.ext === "obj" || known.ext === "gltf") {
      notes.push(`${known.label}: ${known.note}`);
    }
    if (file.size > LARGE_MODEL_BYTES) {
      notes.push(
        `${formatBytes(file.size)} — a long upload. Start where the signal is good.`
      );
    }
    setWarning(notes.length > 0 ? notes.join(" ") : null);
  }

  async function attach() {
    if (chosen === null) return;
    setDone(null);
    setModelBusy(`Adding ${chosen.name} to “${modelLabel}” on this device…`);
    try {
      const handed = await onAttachModel(chosen);
      if (handed === false) return;
      setDone(`${chosen.name} was added to “${modelLabel}”.`);
      setChosen(null);
      setWarning(null);
    } finally {
      setModelBusy(null);
    }
  }

  async function chooseFrames(files: File[]) {
    setTurntableProblem(null);
    setTurntableDone(null);
    if (!onAttachTurntable || files.length === 0) return;
    const frames = files.filter((file) => file.type.startsWith("image/"));
    const rejected = files.filter((file) => !file.type.startsWith("image/"));
    if (frames.length === 0) {
      setTurntableProblem(
        `“${turntableLabel}” takes photos only. Attach videos to “Process video” on the stage.`
      );
      return;
    }
    setTurntableBusy(
      `${frames.length === 1 ? "Adding 1 photograph" : `Adding ${frames.length} photographs`} to “${turntableLabel}” on this device…`
    );
    try {
      const handed = await onAttachTurntable(frames);
      if (handed === false) return;
      setTurntableDone(
        `${frames.length === 1 ? "1 photograph was" : `${frames.length} photographs were`} added to “${turntableLabel}”.` +
          (rejected.length > 0
            ? ` ${rejected.length === 1 ? "One other file was" : `${rejected.length} other files were`} left out because ` +
              `${rejected.length === 1 ? "it is" : "they are"} not photographs: ${rejected.map((file) => file.name).join(", ")}.`
            : "")
      );
    } finally {
      setTurntableBusy(null);
    }
  }

  const frames = turntableCount ?? 0;
  const turntableTone =
    frames >= TURNTABLE_COMFORTABLE ? "good" : frames >= TURNTABLE_MINIMUM ? "fair" : "thin";

  return (
    <div className="grid gap-3">
      <section className="rounded-md border border-line-200 bg-card">
        <div className="flex items-start gap-2 p-3">
          <Camera className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <div className="min-w-0 flex-1">
            <h4 className="text-sm font-medium text-ink-900">
              “{turntableLabel}” is what reviewers see
            </h4>
            <p className="mt-0.5 text-xs leading-5 text-ink-500">
              The printed report shows these photos; 3D models are only listed.
            </p>
          </div>
        </div>

        <div className="border-t border-line-200 p-3">
          <p className="text-xs leading-5 text-ink-500">
            Walk around the piece: one photo every 30° ({TURNTABLE_MINIMUM} frames) or 15° ({TURNTABLE_COMFORTABLE}).
            Keep light and background the same.
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

          {onAttachTurntable ? (
            <div className="mt-3">
              <DropCard
                label={`Add photographs to “${turntableLabel}”`}
                buttonLabel="Choose the whole turn"
                accept="image/*"
                acceptSentence="Photos, added in order."
                multiple
                disabled={disabled}
                onFiles={(files) => chooseFrames(files)}
              />
            </div>
          ) : (
            <p className="mt-3 text-xs leading-5 text-ink-500">
              Add frames on the prototype&apos;s stage.
            </p>
          )}
          {turntableProblem ? (
            <p
              role="alert"
              className="mt-2 flex items-start gap-2 rounded-md border border-red-200 bg-error-100 px-2 py-1.5 text-xs leading-4 text-red-800"
            >
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
              <span>{turntableProblem}</span>
            </p>
          ) : null}
          <div aria-live="polite" aria-atomic="true">
            {turntableBusy ? (
              <p className="mt-2 flex items-start gap-2 text-xs text-ink-500">
                <Loader2 className="mt-0.5 h-3.5 w-3.5 shrink-0 animate-spin" aria-hidden />
                <span>{turntableBusy}</span>
              </p>
            ) : turntableDone ? (
              <p className="mt-2 flex items-start gap-2 text-xs text-ink-500">
                <Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-success-600 dark:text-emerald-400" aria-hidden />
                <span>{turntableDone}</span>
              </p>
            ) : null}
          </div>
        </div>
      </section>

      <section className="rounded-md border border-line-200 bg-card">
        <div className="flex items-start gap-2 p-3">
          <Box className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <div className="min-w-0 flex-1">
            <h4 className="text-sm font-medium text-ink-900">“{modelLabel}”</h4>
            <p className="mt-0.5 text-xs leading-5 text-ink-500">
              Listed in the report; others can download it to measure or print.
            </p>
          </div>
        </div>

        <div className="border-t border-line-200 p-3">
          <DropCard
            label="Model file"
            buttonLabel="Choose a model file"
            accept={MODEL_ACCEPT}
            acceptSentence={`${MODEL_FORMATS.map((format) => format.label).join(", ")}. GLB is best.`}
            disabled={disabled}
            onFiles={(files) => {
              const file = files[0];
              if (file) choose(file);
            }}
          />

          <details className="mt-3">
            <summary className="cursor-pointer text-xs font-medium text-purple-700 dark:text-purple-300">
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
                <p
                  role="alert"
                  className="mt-2 flex items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-2 py-1.5 text-xs leading-4 text-amber-800"
                >
                  <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
                  <span>{warning}</span>
                </p>
              ) : null}
              <ActionGroup className="mt-2">
                <button type="button" className="field-button" onClick={attach} disabled={disabled}>
                  Add to “{modelLabel}”
                </button>
                <button
                  type="button"
                  className="field-button-secondary"
                  disabled={disabled}
                  onClick={() => {
                    setChosen(null);
                    setWarning(null);
                  }}
                >
                  Put this file away
                </button>
              </ActionGroup>
            </div>
          ) : null}

          <div aria-live="polite" aria-atomic="true">
            {modelBusy ? (
              <p className="mt-2 flex items-start gap-2 text-xs text-ink-500">
                <Loader2 className="mt-0.5 h-3.5 w-3.5 shrink-0 animate-spin" aria-hidden />
                <span>{modelBusy}</span>
              </p>
            ) : done ? (
              <p className="mt-2 flex items-start gap-2 text-xs text-ink-500">
                <Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-success-600 dark:text-emerald-400" aria-hidden />
                <span>{done}</span>
              </p>
            ) : null}
          </div>
        </div>
      </section>
    </div>
  );
}

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
