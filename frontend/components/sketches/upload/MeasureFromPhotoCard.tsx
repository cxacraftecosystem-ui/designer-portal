"use client";

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
import { TRACEABLE_ACCEPT, TRACEABLE_IMAGE_TYPES } from "./decodeToPixels";

const CARD_TITLE = "Measure a dimension from a photograph";

export type MeasurePhotos =
  | { status: "loading" }
  | { status: "failed"; reason: string }
  | {
      status: "ready";
      photos: MeasurablePhoto[];
      unreadable: number;
    };

export interface WorkingPhoto {
  photo: MeasurablePhoto;
  source: "shared" | "own";
}

export interface MeasureFromPhotoCardProps {
  what: "sketch" | "prototype";
  rowName: string | null;
  photos: MeasurePhotos;
  targets: MeasureTarget[];
  row: DwEntryData;
  photoFieldLabels: string[];
  disabled?: boolean;
  onPropose: (key: string, value: DwValue, method: MeasurementMarker) => void;
  working?: WorkingPhoto | null;
  onUseDifferentPhoto?: (file: File | null) => void;
}

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
  const wasOpenRef = useRef(false);

  const collapse = useCallback(() => {
    setExpanded(false);
  }, []);

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

  const description = `Measure a dimension from a photo of the ${what}.`;

  return (
    <section className="rounded-md border border-line-200 bg-surface-50">
      {expanded ? (
        <div className="flex items-start gap-2 p-3">
          <Ruler className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <div className="min-w-0 flex-1">
            <h4
              ref={headingRef}
              tabIndex={-1}
              className="text-sm font-medium text-ink-900 focus:outline-hidden focus-visible:ring-2 focus-visible:ring-purple-600/40"
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
            onClick={() => setExpanded(true)}
          >
            <Ruler className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
            <span className="min-w-0 flex-1">
              <span className="block text-sm font-medium text-ink-900">{CARD_TITLE}</span>
              <span className="mt-0.5 block text-xs leading-5 text-ink-500">{description}</span>
            </span>
            <ChevronDown className="mt-0.5 h-4 w-4 shrink-0 text-ink-500 transition-transform" aria-hidden />
          </button>
        </h4>
      )}

      {expanded ? (
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
  onCollapse: () => void;
  working: WorkingPhoto | null;
  onUseDifferentPhoto?: (file: File | null) => void;
}) {
  const filed = photos.status === "ready" ? photos.photos : [];
  const usable = working ? [working.photo, ...filed] : filed;
  const escape =
    onUseDifferentPhoto && targets.length > 0 ? (
      <DifferentPhoto what={what} working={working} disabled={disabled} onChoose={onUseDifferentPhoto} />
    ) : null;

  if (!rowName) {
    return (
      <Note>
        Choose a {what} above first.
      </Note>
    );
  }

  if (photos.status === "loading" && !working) {
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
        Nothing to measure. Reload to update the app.
      </Note>
    );
  }

  if (usable.length === 0) {
    return (
      <>
        <p className="flex max-w-prose items-start gap-2 text-sm leading-6 text-ink-700">
          <ImageOff className="mt-1 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <span>
            No photo on “{rowName}” yet. Attach one in {fieldsPhrase}, with a ruler or scale card in the frame.
          </span>
        </p>
        {escape}
      </>
    );
  }

  return (
    <>
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
        <p
          role="alert"
          className="flex max-w-prose items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800"
        >
          <AlertTriangle className="mt-1 h-4 w-4 shrink-0" aria-hidden />
          <span>
            {photos.unreadable === 1
              ? "One file couldn't be opened here."
              : `${photos.unreadable} files couldn't be opened here.`}{" "}
            You may not have access.
          </span>
        </p>
      ) : null}

      <p className="max-w-prose text-xs leading-5 text-ink-500">
        Nothing is written until you press a button.
      </p>

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

function ReadFailure({ rowName, reason }: { rowName: string; reason: string }) {
  return (
    <p
      role="alert"
      className="flex max-w-prose items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800"
    >
      <AlertTriangle className="mt-1 h-4 w-4 shrink-0" aria-hidden />
      <span>
        Couldn&apos;t read the photos on “{rowName}”. {reason}
      </span>
    </p>
  );
}

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
      ? "the photo chosen above (not attached)"
      : "a photo for this panel only (not attached)";
  return filedPhrase
    ? `Measuring against ${workingPhrase}, and ${filedPhrase}.`
    : `Measuring against ${workingPhrase}.`;
}

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
  const inForce = working?.source === "own";
  const showing = open || inForce;
  const summaryRef = useRef<HTMLButtonElement | null>(null);
  const wasInForceRef = useRef(false);

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
                ? "Not filed. The tracing panel still uses the photo chosen above."
                : "Not filed."}
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
            <span className="block text-xs font-medium text-ink-900">Measure a different photograph</span>
            <span className="mt-0.5 block text-xs leading-5 text-ink-500">
              Use a photo with a ruler in it. It isn&apos;t filed.
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
            acceptSentence={`${TRACEABLE_IMAGE_TYPES}. Measured here only — never uploaded.`}
            disabled={disabled}
            validate={(candidate) => {
              if (candidate.type === "image/svg+xml") {
                return "an SVG can't be measured. Use the photo.";
              }
              if (candidate.type === "" || candidate.type.startsWith("image/")) return null;
              return `this is ${candidate.type}, not an image.`;
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
