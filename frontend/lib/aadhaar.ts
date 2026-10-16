/**
 * Aadhaar verification on the web — what the server switched on, the calls, the consent words, and
 * reading the Secure QR out of a picture.
 *
 * ── NOTHING HERE DRAWS UNLESS THE SERVER SAYS SO ──────────────────────────────────────────────
 *
 * Both features are server-side flags that default OFF. The answer arrives on the session payload
 * as `features` (`auth.serialize_user`), and every component in `components/aadhaar/` returns null
 * unless `aadhaarFeaturesOf(user)` says its method is on. An account cached from an older server has
 * no `features` key at all, and that reads as "off".
 *
 * ── THE BROWSER NEVER CHECKS A SIGNATURE ───────────────────────────────────────────────────────
 *
 * UIDAI's signature is verified on the server, against the certificates pinned there. The browser's
 * only work is to turn a photograph of the QR into its text, and a ZIP file into base64 — both are
 * sent over TLS and neither is kept: not in localStorage, not in IndexedDB, not in the outbox. A
 * verification is an online act by design (it needs the server's certificates and writes an audit
 * row), so `saveOrQueue` is deliberately not used here.
 */

import { ApiError, apiFetch, describeApiDetail } from "@/lib/api";

export type AadhaarFeatures = {
  offline: boolean;
  online: boolean;
  ekyc: boolean;
};

const OFF: AadhaarFeatures = { offline: false, online: false, ekyc: false };

/** The switches, read defensively off whatever account object the caller holds. */
export function aadhaarFeaturesOf(user: unknown): AadhaarFeatures {
  if (!user || typeof user !== "object") return OFF;
  const features = (user as { features?: unknown }).features;
  if (!features || typeof features !== "object") return OFF;
  const flags = features as Record<string, unknown>;
  return {
    offline: flags.aadhaarOfflineVerification === true,
    online: flags.aadhaarOnlineAuth === true,
    ekyc: flags.aadhaarOnlineEkyc === true
  };
}

export function aadhaarEnabled(features: AadhaarFeatures): boolean {
  return features.offline || features.online;
}

export type AadhaarStatus = "VERIFIED" | "NAME_MISMATCH" | "REJECTED";
export type AadhaarMethod = "SECURE_QR" | "OFFLINE_XML" | "ONLINE_OTP" | "ONLINE_EKYC";

/** `records.summary` on the server. Fields beyond the first four are withheld from readers outside
 *  an artisan's circle, so every one of them is optional here. */
export type AadhaarSummary = {
  status: AadhaarStatus | null;
  verified: boolean;
  method: AadhaarMethod | null;
  verifiedAt: string | null;
  last4?: string | null;
  name?: string | null;
  recordName?: string | null;
  nameMatch?: boolean | null;
  birthYear?: number | null;
  dateOfBirth?: string | null;
  gender?: string | null;
  signedAt?: string | null;
  decidedAt?: string | null;
};

/** The columns an account row carries when a feature is on (`/me`, `GET /users`). */
export type AadhaarAccountColumns = {
  aadhaarVerifyStatus?: AadhaarStatus | null;
  aadhaarVerifyMethod?: AadhaarMethod | null;
  aadhaarVerifiedAt?: string | null;
  aadhaarVerifyLast4?: string | null;
  aadhaarVerifyName?: string | null;
  aadhaarVerifyNameMatch?: boolean | null;
};

export function accountColumnsOf(user: unknown): AadhaarAccountColumns {
  return user && typeof user === "object" ? (user as AadhaarAccountColumns) : {};
}

export const METHOD_LABEL: Record<AadhaarMethod, string> = {
  SECURE_QR: "Aadhaar QR code",
  OFFLINE_XML: "Offline e-KYC file",
  ONLINE_OTP: "Aadhaar OTP",
  ONLINE_EKYC: "Aadhaar e-KYC"
};

export const STATUS_LABEL: Record<AadhaarStatus, string> = {
  VERIFIED: "Aadhaar verified",
  NAME_MISMATCH: "Aadhaar name differs",
  REJECTED: "Aadhaar not accepted"
};

/** Who the verification is about. */
export type AadhaarSubject = { kind: "self" } | { kind: "artisan"; id: string };

function base(subject: AadhaarSubject): string {
  return subject.kind === "self" ? "/aadhaar/self" : `/aadhaar/artisans/${encodeURIComponent(subject.id)}`;
}

export function readAadhaar(subject: AadhaarSubject): Promise<AadhaarSummary> {
  return apiFetch<AadhaarSummary>(base(subject));
}

export function verifySecureQr(subject: AadhaarSubject, qrText: string): Promise<AadhaarSummary> {
  return apiFetch<AadhaarSummary>(`${base(subject)}/secure-qr`, {
    method: "POST",
    body: JSON.stringify({ consent: true, qrText })
  });
}

export function verifyOfflineXml(
  subject: AadhaarSubject,
  zipBase64: string,
  shareCode: string,
  contact: { mobile?: string; email?: string }
): Promise<AadhaarSummary> {
  return apiFetch<AadhaarSummary>(`${base(subject)}/offline-xml`, {
    method: "POST",
    body: JSON.stringify({
      consent: true,
      zipBase64,
      shareCode,
      mobile: contact.mobile?.trim() || undefined,
      email: contact.email?.trim() || undefined
    })
  });
}

export type OtpIssued = { txn: string; token: string; expiresAt: string; uidType: "AADHAAR" | "VID" };

export function requestOtp(subject: AadhaarSubject, uid: string): Promise<OtpIssued> {
  return apiFetch<OtpIssued>(`${base(subject)}/online/otp`, {
    method: "POST",
    body: JSON.stringify({ consent: true, uid })
  });
}

export function verifyOtp(
  subject: AadhaarSubject,
  uid: string,
  otp: string,
  issued: OtpIssued
): Promise<AadhaarSummary> {
  return apiFetch<AadhaarSummary>(`${base(subject)}/online/verify`, {
    method: "POST",
    body: JSON.stringify({ consent: true, uid, otp, txn: issued.txn, token: issued.token })
  });
}

export function withdrawAadhaar(subject: AadhaarSubject): Promise<AadhaarSummary> {
  return apiFetch<AadhaarSummary>(base(subject), { method: "DELETE" });
}

export function decideAadhaar(
  target: { kind: "user" | "artisan"; id: string },
  accept: boolean
): Promise<AadhaarSummary> {
  const path =
    target.kind === "user"
      ? `/aadhaar/users/${encodeURIComponent(target.id)}/decision`
      : `/aadhaar/artisans/${encodeURIComponent(target.id)}/decision`;
  return apiFetch<AadhaarSummary>(path, { method: "POST", body: JSON.stringify({ accept }) });
}

/** The server's refusal sentence, or a fallback. Refusals arrive as `{detail: {code, message}}`. */
export function aadhaarErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    const payload = error.payload;
    const detail = payload && typeof payload === "object" ? (payload as { detail?: unknown }).detail : undefined;
    return describeApiDetail(detail, fallback);
  }
  return fallback;
}

/**
 * ── THE CONSENT NOTICE ────────────────────────────────────────────────────────────────────────
 *
 * Shown, in full, before any scan, upload or number is accepted; the server refuses a request whose
 * `consent` is not true. Plain words, and the same sentences the handset shows
 * (`android/.../ui/aadhaar/AadhaarCopy.kt`) — change them together.
 */
export function consentPoints(subject: AadhaarSubject): string[] {
  const whose = subject.kind === "self" ? "your" : "the artisan's";
  return [
    `This checks ${whose} identity against UIDAI, the authority that issues Aadhaar. It is voluntary — nothing else in this app depends on it.`,
    "What is read: the name, date of birth and gender UIDAI signed, and the last four digits of the Aadhaar number.",
    "What is kept: only that the check passed, how and when, the name as UIDAI holds it, the year of birth (the full date for an artisan record), the gender and those last four digits.",
    "What is never kept: the full Aadhaar number, the photograph, the address, the QR code or file itself, the share code or any one-time code.",
    "You can remove the result at any time from this screen."
  ];
}

export function consentStatement(subject: AadhaarSubject): string {
  return subject.kind === "self"
    ? "I agree to verify my identity with Aadhaar as described above."
    : "The artisan has heard this read out and agrees to verify their identity with Aadhaar.";
}

/** A File to base64, for the offline e-KYC ZIP. The bytes stay in memory. */
export async function fileToBase64(file: Blob): Promise<string> {
  const bytes = new Uint8Array(await file.arrayBuffer());
  let binary = "";
  const chunk = 0x8000;
  for (let index = 0; index < bytes.length; index += chunk) {
    binary += String.fromCharCode(...bytes.subarray(index, index + chunk));
  }
  return btoa(binary);
}

/** A Secure QR's text is a long decimal integer. Anything else is not one, said before upload. */
export function looksLikeSecureQr(text: string): boolean {
  const digits = text.replace(/\s+/g, "");
  return digits.length > 200 && /^\d+$/.test(digits);
}
