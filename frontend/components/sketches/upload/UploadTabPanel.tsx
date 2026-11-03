"use client";

import type { ReactNode } from "react";
import { useState } from "react";
import { Layers, PencilRuler } from "lucide-react";

import { PrototypeModelField } from "./PrototypeModelField";
import { SharedPhotoField } from "./SharedPhotoField";
import { SketchTraceField } from "./SketchTraceField";
import type { DecodedPixels } from "./decodeToPixels";

export interface ChosenPhotograph {
  readonly id: number;
  readonly file: File;
  readonly url: string;
  readonly pixels: DecodedPixels | null;
  readonly problem: string | null;
}

export interface UploadTabPanelProps {
  sketchTargetLabel: string;
  modelLabel: string;
  turntableLabel: string;
  turntableCount?: number;
  disabled?: boolean;
  onAttachSketch: (file: File) => AttachAnswer;
  onAttachSketchSource?: (file: File) => AttachAnswer;
  onAttachModel: (file: File) => AttachAnswer;
  onAttachTurntable?: (files: File[]) => AttachAnswer;
  sketchMeasure?: ReactNode;
  prototypeMeasure?: ReactNode;
  sketchPhotograph?: ChosenPhotograph | null;
  sketchImageLabel?: string;
  sketchRowName?: string | null;
  sketchPhotoFiled?: boolean;
  onChooseSketchPhoto?: (file: File | null) => void;
}

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
  const photoOwnedAbove = onChooseSketchPhoto !== undefined;

  return (
    <div className="grid gap-4">
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
            {photoOwnedAbove
              ? "One photo of the sheet, used to trace line art and to measure. Nothing is filed until you choose."
              : "Attach the photo as it is, or trace clean line art from it too."}
          </p>
          <p className="mt-1 max-w-prose text-sm leading-6 text-ink-500">
            The traced drawing is added to “{sketchTargetLabel}”, beside the photograph and never over it.
          </p>
          {photoOwnedAbove ? (
            <div className="mt-3">
              <SharedPhotoField
                photograph={sketchPhotograph ?? null}
                imageLabel={sketchImageLabel ?? "the sketch image field"}
                rowName={sketchRowName ?? null}
                filed={sketchPhotoFiled ?? false}
                disabled={disabled}
                onChoose={onChooseSketchPhoto}
              />
            </div>
          ) : null}
          <SketchTraceField
            targetLabel={sketchTargetLabel}
            disabled={disabled}
            onAttach={onAttachSketch}
            onAttachSource={onAttachSketchSource}
            photograph={photoOwnedAbove ? (sketchPhotograph ?? null) : undefined}
            photographFiled={photoOwnedAbove ? sketchPhotoFiled : undefined}
          />
          {sketchMeasure ? <div className="mt-3">{sketchMeasure}</div> : null}
        </div>
      ) : (
        <div className="panel p-4">
          <h3 className="font-display text-base font-semibold text-ink-900">Prototypes</h3>
          <p className="mt-1 max-w-prose text-sm leading-6 text-ink-500">
            Turntable photos appear in the report; 3D models are listed.
          </p>
          <p className="mt-1 max-w-prose text-sm leading-6 text-ink-500">
            Photos go to “{turntableLabel}”; 3D files go to “{modelLabel}”.
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
          {prototypeMeasure ? <div className="mt-3">{prototypeMeasure}</div> : null}
        </div>
      )}
    </div>
  );
}
