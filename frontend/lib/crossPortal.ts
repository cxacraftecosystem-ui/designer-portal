/**
 * C27 — the artisan lookup in the Field Repository (documentation portal), behind a server flag.
 *
 * Nothing here draws anything until `GET /cross-portal/status` says the feature is on; while it is
 * off every other route answers 404 and the artisan form is exactly what it was. The Aadhaar number
 * never leaves this server for the other one: the API sends an HMAC of it. The Field Repository is
 * the source of truth for `identityFields`; the rest is a one-time suggestion the designer may edit.
 */
import { apiFetch } from "@/lib/api";

export type CrossPortalFields = {
  name?: string | null;
  localName?: string | null;
  gender?: string | null;
  dateOfBirth?: string | null;
  pehchanCardAvailable?: boolean | null;
  pehchanCardNumber?: string | null;
  craftStartDate?: string | null;
  experienceYears?: number | null;
  experienceMonths?: number | null;
  phone?: string | null;
  place?: string | null;
};

export type CrossPortalMatch = {
  remoteArtisanId: string;
  matchedBy: "PEHCHAN" | "AADHAAR" | string | null;
  fields: CrossPortalFields;
  identityFields: string[];
  craft: { code: string | null; name: string; craftId: string | null; craftName: string } | null;
  statedAddress: { state: string | null; district: string | null; village: string | null } | null;
};

export type CrossPortalLink =
  | { linked: false }
  | {
      linked: true;
      remoteArtisanId: string;
      matchedBy: string;
      consentRecordedAt: string;
      remoteAcknowledged: boolean;
      lastRefreshedAt: string | null;
      identityFields: string[];
    };

let statusPromise: Promise<boolean> | null = null;

/** Whether to draw the lookup at all. Asked once per page load; a failure reads as "off". */
export function crossPortalEnabled(): Promise<boolean> {
  if (!statusPromise) {
    statusPromise = apiFetch<{ enabled: boolean }>("/cross-portal/status", {}, { redirectOn401: false })
      .then((answer) => Boolean(answer?.enabled))
      .catch(() => {
        statusPromise = null;
        return false;
      });
  }
  return statusPromise;
}

export function lookupInFieldRepository(input: {
  aadhaarNumber?: string | null;
  pehchanCardNumber?: string | null;
}): Promise<{ match: CrossPortalMatch | null }> {
  return apiFetch("/cross-portal/artisans/lookup", {
    method: "POST",
    body: JSON.stringify({
      aadhaarNumber: input.aadhaarNumber || null,
      pehchanCardNumber: input.pehchanCardNumber || null,
      consent: true
    })
  });
}

export function getCrossPortalLink(artisanId: string): Promise<CrossPortalLink> {
  return apiFetch(`/cross-portal/artisans/${encodeURIComponent(artisanId)}/link`);
}

export function linkToFieldRepository(artisanId: string, expectedRemoteArtisanId?: string | null): Promise<CrossPortalLink> {
  return apiFetch(`/cross-portal/artisans/${encodeURIComponent(artisanId)}/link`, {
    method: "POST",
    body: JSON.stringify({ consent: true, expectedRemoteArtisanId: expectedRemoteArtisanId ?? null })
  });
}

export function refreshFromFieldRepository(
  artisanId: string
): Promise<{ link: CrossPortalLink; changed: string[] }> {
  return apiFetch(`/cross-portal/artisans/${encodeURIComponent(artisanId)}/link/refresh`, { method: "POST" });
}

export function unlinkFromFieldRepository(artisanId: string): Promise<CrossPortalLink> {
  return apiFetch(`/cross-portal/artisans/${encodeURIComponent(artisanId)}/link`, { method: "DELETE" });
}

/** The form values a match fills, keyed by the artisan form's own field names. Pure. */
export type ArtisanPrefill = {
  name?: string;
  localName?: string;
  gender?: string;
  dateOfBirth?: string;
  craftStartDate?: string;
  experienceYears?: number;
  experienceMonths?: number;
  phone?: string;
  place?: string;
  pehchanCardAvailable?: boolean;
  pehchanCardNumber?: string;
  craftId?: string;
};

const day = (value: string | null | undefined): string | undefined =>
  value && /^\d{4}-\d{2}-\d{2}/.test(value) ? value.slice(0, 10) : undefined;

export function prefillFromMatch(match: CrossPortalMatch): ArtisanPrefill {
  const f = match.fields ?? {};
  const out: ArtisanPrefill = {};
  if (f.name?.trim()) out.name = f.name.trim();
  if (f.localName?.trim()) out.localName = f.localName.trim();
  if (f.gender?.trim()) out.gender = f.gender.trim();
  const dob = day(f.dateOfBirth);
  if (dob) out.dateOfBirth = dob;
  const start = day(f.craftStartDate);
  if (start) out.craftStartDate = start;
  if (typeof f.experienceYears === "number") out.experienceYears = f.experienceYears;
  if (typeof f.experienceMonths === "number") out.experienceMonths = f.experienceMonths;
  if (f.phone?.trim()) out.phone = f.phone.trim();
  if (f.place?.trim()) out.place = f.place.trim();
  if (typeof f.pehchanCardAvailable === "boolean") out.pehchanCardAvailable = f.pehchanCardAvailable;
  if (f.pehchanCardNumber?.trim()) out.pehchanCardNumber = f.pehchanCardNumber.trim();
  if (match.craft?.craftId) out.craftId = match.craft.craftId;
  return out;
}

/** "Meena Devi · Bandhani" — the one line that says who was found. */
export function matchSummary(match: CrossPortalMatch): string {
  const parts = [match.fields?.name?.trim() || "Unnamed artisan"];
  if (match.craft?.name) parts.push(match.craft.name);
  const place = match.statedAddress?.district || match.fields?.place;
  if (place) parts.push(place);
  return parts.join(" · ");
}
