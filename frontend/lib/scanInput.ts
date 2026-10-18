/**
 * What a scanned or typed string is, in the order it is tried: an artisan QR card first, then an
 * older DPW1 code (`lib/workshopCodes`). Old DPW1:A cards keep resolving; nothing prints them now.
 */
import { CARD_CHECK_MESSAGE, looksLikeArtisanCard, parseArtisanCard, type ParsedCard } from "@/lib/artisanCard";
import { decodeWorkshopCode, type WorkshopCodeRef } from "@/lib/workshopCodes";

export type ScanInput =
  | { kind: "card"; raw: string; card: ParsedCard }
  | { kind: "workshop"; ref: WorkshopCodeRef }
  | { kind: "refused"; message: string };

export function classifyScanInput(raw: string): ScanInput {
  const card = parseArtisanCard(raw);
  if (card.ok) return { kind: "card", raw: raw.trim(), card: card.card };
  if (looksLikeArtisanCard(raw)) return { kind: "refused", message: CARD_CHECK_MESSAGE };
  const decoded = decodeWorkshopCode(raw);
  if (decoded.ok) return { kind: "workshop", ref: decoded.ref };
  return { kind: "refused", message: decoded.message };
}
