/**
 * A scanned or typed artisan card, taken as far as one answer: who it is and where to open them.
 * Shared by every scanner on the web so a card reads the same wherever it is scanned.
 */
import { ApiError } from "@/lib/api";
import { maskedLast4, newArtisanFromCardHref, resolveArtisanCard } from "@/lib/artisanCardApi";

export type CardScanOutcome =
  | { ok: true; artisanId: string; label: string; detail?: string; href: string; hrefLabel: string }
  | { ok: false; message: string; href?: string; hrefLabel?: string; artisanId?: string };

export const CARD_REPLACED_MESSAGE = "This card was replaced.";
export const CARD_REMOTE_MESSAGE = "This artisan is in the Field Repository and not here yet.";
const CARD_FAILED = "That card couldn't be looked up. Try again.";

export function artisanHref(id: string): string {
  return `/artisans/${encodeURIComponent(id)}`;
}

export async function resolveCardForScan(raw: string): Promise<CardScanOutcome> {
  try {
    const answer = await resolveArtisanCard(raw);
    if (answer.status === "FOUND") {
      const a = answer.artisan;
      const detail = [a.craft, a.cluster ?? a.place, maskedLast4(a.aadhaarLast4) ? `Aadhaar ${maskedLast4(a.aadhaarLast4)}` : null]
        .filter(Boolean)
        .join(" · ");
      return { ok: true, artisanId: a.id, label: a.name, detail: detail || undefined, href: artisanHref(a.id), hrefLabel: "Open" };
    }
    if (answer.status === "REPLACED") {
      return {
        ok: false,
        message: CARD_REPLACED_MESSAGE,
        href: artisanHref(answer.artisanId),
        hrefLabel: "Open the artisan",
        artisanId: answer.artisanId
      };
    }
    return { ok: false, message: CARD_REMOTE_MESSAGE, href: newArtisanFromCardHref(raw.trim()), hrefLabel: "Add from the Field Repository" };
  } catch (error) {
    if (error instanceof ApiError) {
      const detail = (error.payload as { detail?: unknown } | undefined)?.detail;
      if (typeof detail === "string" && detail.trim()) return { ok: false, message: detail };
    }
    return { ok: false, message: CARD_FAILED };
  }
}
