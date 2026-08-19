"use client";

import { useState } from "react";

import { analyzeMeasurementImage } from "@/lib/media";

export type GridDimension = "length" | "breadth" | "height";
export type GridGroup = "lengthBreadth" | "height";
export type GridFiles = Partial<Record<GridGroup, File>>;

/**
 * What every measurement-grid photograph writes into its media row's `extraMetadata.purpose`.
 *
 * ── THE DEFECT THIS ENDS ──────────────────────────────────────────────────────────────────────
 * A grid shot is the FIRST photograph most products and toolkits ever get: a designer opens the
 * form, checks "Document using grid", lays the object on the ruled sheet and photographs it before
 * they have taken a single presentation picture. The server's `_reference_photos` picks a record's
 * photograph deterministically by `createdAt ASC, id ASC` — oldest first — so the oldest image is
 * the one that becomes `photo` in the reference payload, gets hydrated onto a stage entry, and is
 * printed in the .docx handed to a Development Commissioner's office. The ministry report showed a
 * sheet of graph paper captioned as the tool.
 *
 * ── WHY A MARKER AND NOT A RULE ABOUT CAPTIONS ────────────────────────────────────────────────
 * The captions these uploads carry ("Height grid (measurement) for …") do identify a grid shot, and
 * the server keeps a transitional clause matching them so the photographs ALREADY in the bucket
 * sort last too. But a caption is prose: it is translated, edited by a reviewer, and re-typed on the
 * handset, and a report that starts printing graph paper again because somebody tidied a sentence
 * is not a failure anybody would connect back to this. A machine-readable purpose written by the
 * uploading client is the durable half; the caption clause is the backfill for what predates it.
 *
 * ── THE STRING IS A THREE-SURFACE CONTRACT ────────────────────────────────────────────────────
 * The web writes it here, Android writes the identical string on its own grid captures, and the
 * server SORTS any candidate carrying it LAST. Change it in one place and the other two stop
 * agreeing silently — nothing errors, the report just starts printing ruled paper again.
 *
 * A SORT KEY AND NOT A `WHERE`, which is worth reading off `_reference_photos` rather than assumed:
 * the statement computes an `is_grid` boolean in a CTE and orders by
 * `parent, is_grid ASC, created_at ASC, id ASC`. Nothing is ever excluded into a blank and there is
 * no fallback branch — sorting last is itself why a product whose ONLY image is a grid shot still
 * gets that picture instead of an empty gallery. The backend docstring says so in those words
 * ("NOTHING IS EVER EXCLUDED INTO A BLANK"); this comment claimed a WHERE clause and an
 * every-candidate-excluded fallback, and a reader sent looking for either would find neither and
 * would be left believing there is a second code path to maintain.
 */
export const MEASUREMENT_GRID_PURPOSE = "MEASUREMENT_GRID";

/**
 * "Document using grid": length and breadth are read from a single top-down photo of the object on a
 * 1-inch grid sheet; height (optional) is read from its own side-on photo. The vision model's estimate
 * auto-fills the matching field(s). Captured files are reported up so the parent can also store them as
 * media on save. The numeric values are always editable afterwards.
 */
export function GridMeasurement({
  includeHeight = true,
  onLengthBreadth,
  onHeight,
  onFilesChange
}: {
  includeHeight?: boolean;
  onLengthBreadth: (length: string | null, breadth: string | null) => void;
  onHeight: (inches: string) => void;
  onFilesChange: (files: GridFiles) => void;
}) {
  const [enabled, setEnabled] = useState<Set<GridGroup>>(new Set());
  const [files, setFiles] = useState<GridFiles>({});
  const [status, setStatus] = useState<Partial<Record<GridGroup, string>>>({});

  function toggle(group: GridGroup, on: boolean) {
    setEnabled((prev) => {
      const next = new Set(prev);
      if (on) next.add(group);
      else next.delete(group);
      return next;
    });
    if (!on) {
      // Unchecking a group discards its captured photo (and tells the parent) so a disabled grid
      // photo is never uploaded on save.
      if (files[group]) {
        const nextFiles = { ...files };
        delete nextFiles[group];
        setFiles(nextFiles);
        onFilesChange(nextFiles);
      }
      setStatus((prev) => ({ ...prev, [group]: undefined }));
    }
  }

  const isNum = (value: unknown) => value !== null && value !== undefined && Number.isFinite(Number(value)) && Number(value) > 0;

  async function pick(group: GridGroup, file: File | null) {
    if (!file) return;
    const nextFiles = { ...files, [group]: file };
    setFiles(nextFiles);
    onFilesChange(nextFiles);
    setStatus((prev) => ({ ...prev, [group]: "Analyzing…" }));
    try {
      if (group === "lengthBreadth") {
        const result = await analyzeMeasurementImage(file);
        const length = result.analysis?.lengthInches;
        const breadth = result.analysis?.breadthInches;
        onLengthBreadth(isNum(length) ? String(length) : null, isNum(breadth) ? String(breadth) : null);
        const parts: string[] = [];
        if (isNum(length)) parts.push(`L ${length}"`);
        if (isNum(breadth)) parts.push(`B ${breadth}"`);
        setStatus((prev) => ({
          ...prev,
          [group]: parts.length ? `Measured ${parts.join(" · ")} — fields filled` : "Couldn't read a value — enter it manually"
        }));
      } else {
        const result = await analyzeMeasurementImage(file, "height");
        const value = result.analysis?.valueInches;
        if (isNum(value)) {
          onHeight(String(value));
          setStatus((prev) => ({ ...prev, [group]: `Measured ${value}" — field filled` }));
        } else {
          setStatus((prev) => ({
            ...prev,
            [group]: result.available ? "Couldn't read a value — enter it manually" : result.message ?? "Measurement unavailable"
          }));
        }
      }
    } catch {
      setStatus((prev) => ({ ...prev, [group]: "Analysis failed — enter it manually" }));
    }
  }

  function renderGroup(group: GridGroup, label: string, hint: string) {
    return (
      <div className="grid gap-2">
        <label className="flex items-center gap-2 text-sm text-ink">
          <input type="checkbox" checked={enabled.has(group)} onChange={(event) => toggle(group, event.target.checked)} />
          {label}
        </label>
        {enabled.has(group) ? (
          <div className="grid gap-1 pl-6">
            <p className="text-xs text-ink-muted">{hint}</p>
            <input className="field-input" type="file" accept="image/*" capture="environment" onChange={(event) => pick(group, event.target.files?.[0] ?? null)} />
            {status[group] ? <p className="text-xs text-ink-muted">{status[group]}</p> : null}
          </div>
        ) : null}
      </div>
    );
  }

  return (
    <section className="grid gap-3 rounded-lg border border-line-200 bg-field-100 p-4">
      <div>
        <h3 className="font-display font-bold text-lg text-ink">Document using grid</h3>
        <p className="mt-1 text-sm text-ink-muted">
          Place the object on a 1-inch grid sheet. Length and breadth are read from a single top-down photo; height needs its
          own side-on photo. The measured inches auto-fill the matching field(s) (still editable).
        </p>
      </div>
      {renderGroup("lengthBreadth", "Length & breadth (one photo)", "Top-down photo of the object on the grid — fills both length and breadth.")}
      {includeHeight ? renderGroup("height", "Height (one photo)", "Side-on photo of the object against the grid — fills height.") : null}
    </section>
  );
}
