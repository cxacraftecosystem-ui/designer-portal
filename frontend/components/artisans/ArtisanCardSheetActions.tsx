"use client";

/**
 * Bulk printing of artisan QR cards: one PDF from the server (`POST /artisan-cards/sheet.pdf`),
 * ID-1 cards ten to an A4 sheet with crop marks, or one A6 card per page. Used by the artisans list
 * (the ticked rows) and a design workshop's Cards & tags page (its roster).
 *
 * Draws nothing while cards are off here. All or nothing: the server refuses a request naming any
 * artisan this account can't print, and that sentence is shown as it comes.
 */
import { useEffect, useState } from "react";

import { ActionGroup } from "@/components/ui/ActionGroup";
import { Toggle } from "@/components/ui/Toggle";
import { ApiError } from "@/lib/api";
import { artisanCardStatus, downloadArtisanCardSheet, type CardSheetRequest, type CardSize } from "@/lib/artisanCardApi";

/** The most cards one request prints (the server's SHEET_LIMIT). */
export const CARD_SHEET_LIMIT = 200;

/** Whether cards are on here: null until `GET /artisan-cards/status` answers. */
export function useArtisanCardsStatus(): boolean | null {
  const [enabled, setEnabled] = useState<boolean | null>(null);
  useEffect(() => {
    let cancelled = false;
    void artisanCardStatus().then((status) => {
      if (!cancelled) setEnabled(status.enabled);
    });
    return () => {
      cancelled = true;
    };
  }, []);
  return enabled;
}

export function useArtisanCardsEnabled(): boolean {
  return useArtisanCardsStatus() === true;
}

export function ArtisanCardSheetActions({
  source,
  primaryLabel = "Print cards",
  count
}: {
  /** What to print; null while there is nothing chosen. */
  source: CardSheetRequest | null;
  primaryLabel?: string;
  /** How many cards the source holds, when known: past the limit the buttons hold and say why. */
  count?: number;
}) {
  const enabled = useArtisanCardsEnabled();
  const [photo, setPhoto] = useState(true);
  const [busy, setBusy] = useState<CardSize | null>(null);
  const [note, setNote] = useState<string | null>(null);

  if (!enabled) return null;

  const tooMany = typeof count === "number" && count > CARD_SHEET_LIMIT;
  const held = !source || busy !== null || tooMany || count === 0;

  async function print(size: CardSize) {
    if (!source) return;
    setBusy(size);
    setNote(null);
    try {
      await downloadArtisanCardSheet(source, size, photo);
    } catch (error) {
      const detail = error instanceof ApiError ? (error.payload as { detail?: unknown } | undefined)?.detail : null;
      setNote(typeof detail === "string" && detail.trim() ? detail : "The cards couldn't be printed. Try again.");
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="grid gap-2" data-testid="artisan-card-sheet-actions">
      <Toggle label="Include photos" checked={photo} onChange={setPhoto} disabled={busy !== null} />
      <ActionGroup align="start">
        <button type="button" className="field-button" disabled={held} onClick={() => void print("id1")}>
          {busy === "id1" ? "Preparing…" : primaryLabel}
        </button>
        <button type="button" className="field-button-secondary" disabled={held} onClick={() => void print("a6")}>
          {busy === "a6" ? "Preparing…" : "Print A6"}
        </button>
      </ActionGroup>
      {tooMany ? <p className="text-sm text-ink-muted">Print at most {CARD_SHEET_LIMIT} cards at a time.</p> : null}
      {note ? (
        <p role="status" className="text-sm text-ink-muted">
          {note}
        </p>
      ) : null}
    </div>
  );
}
