"use client";

/**
 * Stage 1's venue prompt after a save with no venue location (C12) — the web twin of Android's
 * `DwVenuePrompt`: the same heading, the same line, and the same "From a photo" action, which reads
 * the position a photograph carries (`lib/photoPosition`, as `StageGeoField` does) and applies it on
 * a tap. "Add the location" takes the designer to the box itself.
 */

import { useRef, useState } from "react";
import { ImageIcon, MapPin } from "lucide-react";

import { ActionGroup } from "@/components/ui/ActionGroup";
import type { DwGeoValue } from "@/lib/designWorkshops";
import { photoPosition, type PhotoPosition } from "@/lib/photoPosition";

export function VenuePrompt({
  onUse,
  onShowField,
  disabled = false
}: {
  onUse: (value: DwGeoValue) => void;
  onShowField: () => void;
  disabled?: boolean;
}) {
  const input = useRef<HTMLInputElement | null>(null);
  const [found, setFound] = useState<PhotoPosition | null>(null);
  const [none, setNone] = useState(false);

  async function read(file: File | undefined) {
    if (!file) return;
    const position = await photoPosition(file);
    if (input.current) input.current.value = "";
    setFound(position);
    setNone(position === null);
  }

  return (
    <section className="panel grid gap-3 p-4" role="status" aria-labelledby="venue-prompt-heading">
      <div>
        <h2 id="venue-prompt-heading" className="flex items-center gap-2 text-sm font-semibold text-ink-900">
          <MapPin className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
          Add the venue location
        </h2>
        <p className="mt-1 text-sm text-ink-700">The artisan list can&rsquo;t be imported without it.</p>
      </div>
      <ActionGroup align="start">
        <button type="button" className="field-button" disabled={disabled} onClick={() => input.current?.click()}>
          <ImageIcon className="h-4 w-4" aria-hidden />
          From a photo
        </button>
        <button type="button" className="field-button-secondary" onClick={onShowField}>
          Add the location
        </button>
      </ActionGroup>
      <input
        ref={input}
        type="file"
        accept="image/*"
        className="hidden"
        aria-hidden
        tabIndex={-1}
        onChange={(event) => void read(event.target.files?.[0])}
      />
      {none ? <p className="text-xs text-ink-500">That photo has no location.</p> : null}
      {found ? (
        <div className="flex flex-col gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 sm:flex-row sm:items-center">
          <p className="min-w-0 flex-1 text-xs leading-5 text-ink-700">
            {found.lat.toFixed(5)}, {found.lon.toFixed(5)}
            {found.takenAt ? ` · taken ${found.takenAt}` : ""}
          </p>
          <ActionGroup inline align="end" className="max-sm:w-full">
            <button
              type="button"
              className="field-button-secondary"
              disabled={disabled}
              onClick={() => {
                onUse({ lat: found.lat, lon: found.lon });
                setFound(null);
              }}
            >
              Use this location
            </button>
          </ActionGroup>
        </div>
      ) : null}
    </section>
  );
}
