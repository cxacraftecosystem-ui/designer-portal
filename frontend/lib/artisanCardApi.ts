/**
 * The artisan QR card routes (`backend/app/api/routes/artisan_cards.py`). Nothing is drawn until
 * `GET /artisan-cards/status` says cards are on; while off every other route answers 404.
 */
import { apiFetch } from "@/lib/api";
import { downloadFile, fetchFile, saveBlobToDisk } from "@/lib/fileDownload";
import type { CrossPortalMatch } from "@/lib/crossPortal";

export type ArtisanCardFace = {
  name: string;
  localName: string | null;
  craft: string | null;
  cluster: string | null;
  aadhaarLast4: string | null;
  photoMediaId: string | null;
};

export type ArtisanCard = {
  artisanId: string;
  code: string;
  shortCode: string;
  token: string;
  issuedAt: string;
  replacedCards: number;
  face: ArtisanCardFace;
  qrSvg: string;
};

export type CardSize = "id1" | "a6";
export type ReissueReason = "LOST" | "DAMAGED" | "OTHER";

export type CardArtisanSummary = {
  id: string;
  name: string;
  craft: string | null;
  cluster: string | null;
  place: string | null;
  aadhaarLast4: string | null;
};

export type CardResolution =
  | { status: "FOUND"; via: "LOCAL" | "LINK"; artisan: CardArtisanSummary }
  | { status: "REPLACED"; artisanId: string }
  | { status: "REMOTE"; issuer: string; needsConsent: true }
  | { status: "REMOTE"; issuer: string; needsConsent: false; match: CrossPortalMatch & { cardCode?: string | null } };

let statusPromise: Promise<{ enabled: boolean; issuer: string }> | null = null;

/** Whether cards are on here. Asked once per page load; a failure reads as "off". */
export function artisanCardStatus(): Promise<{ enabled: boolean; issuer: string }> {
  if (!statusPromise) {
    statusPromise = apiFetch<{ enabled?: boolean; issuer?: string }>("/artisan-cards/status", {}, { redirectOn401: false })
      .then((answer) => ({ enabled: Boolean(answer?.enabled), issuer: answer?.issuer || "D" }))
      .catch(() => {
        statusPromise = null;
        return { enabled: false, issuer: "D" };
      });
  }
  return statusPromise;
}

export function getArtisanCard(artisanId: string): Promise<ArtisanCard> {
  return apiFetch(`/artisans/${encodeURIComponent(artisanId)}/card?source=WEB`);
}

export function reissueArtisanCard(artisanId: string, reason: ReissueReason): Promise<ArtisanCard> {
  return apiFetch(`/artisans/${encodeURIComponent(artisanId)}/card/reissue`, {
    method: "POST",
    body: JSON.stringify({ reason, source: "WEB" })
  });
}

/** The card PDF path, for one artisan. */
export function artisanCardPdfPath(artisanId: string, size: CardSize, photo: boolean): string {
  return `/artisans/${encodeURIComponent(artisanId)}/card.pdf?size=${size}&photo=${photo ? 1 : 0}&source=WEB`;
}

export function downloadArtisanCard(artisanId: string, size: CardSize, photo: boolean): Promise<string> {
  return downloadFile(artisanCardPdfPath(artisanId, size, photo), "artisan-card.pdf");
}

export type CardSheetRequest =
  | { artisanIds: string[]; designWorkshopId?: undefined }
  | { designWorkshopId: string; artisanIds?: undefined };

/** A sheet of cards: A4 2×5 ID-1 cards, or one A6 card per page. */
export async function downloadArtisanCardSheet(
  source: CardSheetRequest,
  size: CardSize = "id1",
  photo = true
): Promise<string> {
  const body = {
    artisanIds: source.artisanIds ?? null,
    designWorkshopId: source.designWorkshopId ?? null,
    size,
    photo,
    source: "WEB"
  };
  const file = await fetchFile("/artisan-cards/sheet.pdf", "artisan-cards.pdf", { json: body });
  saveBlobToDisk(file.blob, file.fileName);
  return file.fileName;
}

export function resolveArtisanCard(input: string, consent = false): Promise<CardResolution> {
  return apiFetch("/artisan-cards/resolve", {
    method: "POST",
    body: JSON.stringify({ input, source: "WEB", consent })
  });
}

/** The reasons a card is reissued, as the server takes them. */
export const REISSUE_REASONS: { value: ReissueReason; label: string }[] = [
  { value: "LOST", label: "Lost" },
  { value: "DAMAGED", label: "Damaged" },
  { value: "OTHER", label: "Other" }
];

/** The server's QR SVG as an `<img>` source, so no markup from the wire is put into the page. */
export function qrImageSrc(svg: string): string {
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}

/** "XXXX XXXX 9012", or null when the last four are unknown. */
export function maskedLast4(last4: string | null | undefined): string | null {
  return last4 ? `XXXX XXXX ${last4}` : null;
}

/** The new-artisan page, filled from a Field Repository card after the consent tick. */
export function newArtisanFromCardHref(shortCodeOrPayload: string): string {
  return `/artisans/new?card=${encodeURIComponent(shortCodeOrPayload)}`;
}
