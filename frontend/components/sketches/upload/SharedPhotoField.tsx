"use client";

import { useRef } from "react";
import { X } from "lucide-react";

import { DropCard } from "./DropCard";
import { DECODE_MAX_EDGE_PX, TRACEABLE_ACCEPT, TRACEABLE_IMAGE_TYPES } from "./decodeToPixels";
import type { ChosenPhotograph } from "./UploadTabPanel";

export interface SharedPhotoFieldProps {
  photograph: ChosenPhotograph | null;
  imageLabel: string;
  rowName: string | null;
  filed: boolean;
  disabled?: boolean;
  onChoose: (file: File | null) => void;
}

function sizeSentence(photograph: ChosenPhotograph): string {
  if (photograph.problem !== null) return photograph.problem;
  const pixels = photograph.pixels;
  if (pixels === null) return "Reading…";
  if (pixels.sourceWidth !== pixels.width || pixels.sourceHeight !== pixels.height) {
    return `Read at ${pixels.width}x${pixels.height}, reduced from ${pixels.sourceWidth}x${pixels.sourceHeight}.`;
  }
  return `${pixels.width}x${pixels.height}.`;
}

function stateSentence(hasPhotograph: boolean, filed: boolean, imageLabel: string, rowName: string | null): string {
  if (!hasPhotograph) {
    return "Choose it once. Both panels below work from it; neither files anything until you press a button there.";
  }
  if (filed) {
    return rowName
      ? `Filed to “${imageLabel}” on “${rowName}”.`
      : `Filed.`;
  }
  return rowName
    ? `Not filed yet.`
    : `Choose a sketch above first.`;
}

export function SharedPhotoField({
  photograph,
  imageLabel,
  rowName,
  filed,
  disabled,
  onChoose
}: SharedPhotoFieldProps) {
  const pickerRef = useRef<HTMLButtonElement | null>(null);

  return (
    <DropCard
      buttonRef={pickerRef}
      label="Photograph of the sketch"
      buttonLabel={photograph ? "Choose a different photograph" : "Choose a photograph"}
      accept={TRACEABLE_ACCEPT}
      acceptSentence={`${TRACEABLE_IMAGE_TYPES}. Larger than ${DECODE_MAX_EDGE_PX}px is reduced.`}
      disabled={disabled}
      validate={(candidate) => {
        if (candidate.type === "image/svg+xml") {
          return "an SVG is already vector art — attach it directly.";
        }
        if (candidate.type === "" || candidate.type.startsWith("image/")) return null;
        return `this is ${candidate.type}, not an image.`;
      }}
      onFiles={(files) => {
        const chosen = files[0];
        if (chosen) onChoose(chosen);
      }}
    >
      {photograph ? (
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

      <p aria-live="polite" aria-atomic="true" className="text-xs leading-5 text-ink-500">
        {stateSentence(photograph !== null, filed, imageLabel, rowName)}
      </p>
    </DropCard>
  );
}
