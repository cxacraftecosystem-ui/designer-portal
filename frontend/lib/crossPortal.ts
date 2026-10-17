/**
 * C27 — the artisan lookup in the Field Repository (documentation portal), behind a server flag.
 *
 * Nothing here draws anything until `GET /cross-portal/status` says the feature is on; while it is
 * off every other route answers 404 and the artisan form is exactly what it was. The Aadhaar number
 * never leaves this server for the other one: the API sends an HMAC of it. The Field Repository is
 * the source of truth for `identityFields`; the rest is a one-time suggestion the designer may edit.
 */
import { apiFetch } from "@/lib/api";
import type { Artisan } from "@/lib/types";

/**
 * What a lookup shares, in words, as of this build. The live list comes from the server
 * (`/cross-portal/status` → `sharedFields`, built from the same list the answer is); this is the
 * fallback, held to the server's phrase by `backend/tests/test_cross_portal_identity.py`.
 */
export const SHARED_FIELDS_FALLBACK = "name, local name, gender, date of birth, Pehchan card, phone number, address, place, craft and craft experience";

/** The consent tick: every item the lookup shares, so the artisan never agrees to less. */
export function consentLabel(sharedFields: string): string {
  return `The artisan agrees to share their ${sharedFields} between the two portals`;
}

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

export type CrossPortalStatus = { enabled: boolean; sharedFields: string };

let statusPromise: Promise<CrossPortalStatus> | null = null;

/** Whether to draw the lookup at all, and what it shares. Asked once per page load; a failure reads as "off". */
export function crossPortalStatus(): Promise<CrossPortalStatus> {
  if (!statusPromise) {
    statusPromise = apiFetch<{ enabled: boolean; sharedFields?: string }>("/cross-portal/status", {}, { redirectOn401: false })
      .then((answer) => ({
        enabled: Boolean(answer?.enabled),
        sharedFields: answer?.sharedFields?.trim() || SHARED_FIELDS_FALLBACK
      }))
      .catch(() => {
        statusPromise = null;
        return { enabled: false, sharedFields: SHARED_FIELDS_FALLBACK };
      });
  }
  return statusPromise;
}

export function crossPortalEnabled(): Promise<boolean> {
  return crossPortalStatus().then((status) => status.enabled);
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

export function linkToFieldRepository(
  artisanId: string,
  expectedRemoteArtisanId?: string | null,
  /** A Field Repository card the form was filled from: the server links from the card (matchedBy CARD). */
  cardInput?: string | null
): Promise<CrossPortalLink> {
  return apiFetch(`/cross-portal/artisans/${encodeURIComponent(artisanId)}/link`, {
    method: "POST",
    body: JSON.stringify({
      consent: true,
      expectedRemoteArtisanId: expectedRemoteArtisanId ?? null,
      ...(cardInput ? { cardInput } : {})
    })
  });
}

/**
 * A Field Repository artisan card (scanned or typed), after the consent tick: the same prefill the
 * number lookup returns, or null when that portal does not share the artisan. A card whose artisan is
 * already here answers `existingArtisanId` instead, so the caller opens it rather than adding twice.
 */
export async function lookupByCard(
  cardInput: string
): Promise<{ match: CrossPortalMatch | null; existingArtisanId?: string }> {
  const answer = await apiFetch<{
    status: string;
    needsConsent?: boolean;
    match?: CrossPortalMatch;
    artisan?: { id: string };
    artisanId?: string;
  }>("/artisan-cards/resolve", {
    method: "POST",
    body: JSON.stringify({ input: cardInput, source: "WEB", consent: true })
  });
  if (answer.status === "FOUND" && answer.artisan?.id) return { match: null, existingArtisanId: answer.artisan.id };
  if (answer.status === "REPLACED" && answer.artisanId) return { match: null, existingArtisanId: answer.artisanId };
  return { match: answer.status === "REMOTE" && answer.match ? answer.match : null };
}

export function refreshFromFieldRepository(
  artisanId: string
): Promise<{ link: CrossPortalLink; changed: string[]; artisan?: Artisan }> {
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

/**
 * The values a refresh changed, as the form's prefill: the identity fields only (the Field
 * Repository is their source of truth), read off the artisan the server returned after writing.
 */
export function prefillFromRefreshedArtisan(artisan: Artisan, changed: string[]): ArtisanPrefill {
  const a = artisan as Artisan & Record<string, unknown>;
  const out: ArtisanPrefill = {};
  const text = (key: string) => (typeof a[key] === "string" ? (a[key] as string).trim() : "");
  if (changed.includes("name") && text("name")) out.name = text("name");
  if (changed.includes("localName") && text("localName")) out.localName = text("localName");
  if (changed.includes("gender") && text("gender")) out.gender = text("gender");
  if (changed.includes("dateOfBirth")) {
    const dob = day(typeof a.dateOfBirth === "string" ? a.dateOfBirth : null);
    if (dob) out.dateOfBirth = dob;
  }
  if (changed.includes("pehchanCardAvailable") && typeof a.pehchanCardAvailable === "boolean") {
    out.pehchanCardAvailable = a.pehchanCardAvailable;
  }
  if (changed.includes("pehchanCardNumber") && text("pehchanCardNumber")) {
    out.pehchanCardNumber = text("pehchanCardNumber");
  }
  return out;
}

/**
 * The match's Pehchan pair, unless the match has no number and the designer typed one: then the
 * typed number stands (with the card marked held) rather than being wiped by the fill.
 */
export function keepTypedPehchan(values: ArtisanPrefill, typed: string | null | undefined): ArtisanPrefill {
  const number = typed?.trim();
  if (values.pehchanCardNumber || !number) return values;
  return { ...values, pehchanCardAvailable: true, pehchanCardNumber: number };
}

/** "Meena Devi · Bandhani" — the one line that says who was found. */
export function matchSummary(match: CrossPortalMatch): string {
  const parts = [match.fields?.name?.trim() || "Unnamed artisan"];
  if (match.craft?.name) parts.push(match.craft.name);
  const place = match.statedAddress?.district || match.fields?.place;
  if (place) parts.push(place);
  return parts.join(" · ");
}
