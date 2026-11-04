import { apiFetch } from "@/lib/api";
import { serverSentence, triageFailure } from "@/lib/failureTriage";
import { WORKSHOP_CODE_NAMESPACE, workshopCodeCheck } from "@/lib/workshopCodes";

export const JOIN_CODE_VERSION = 2;

export const JOIN_LETTER = "J";

const SECRET_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

const SECRET_LENGTH = 22;

const SECRET_PATTERN = new RegExp(`^[${SECRET_ALPHABET}]{${SECRET_LENGTH}}$`);

const ID_PATTERN = /^[a-z0-9][a-z0-9-]{7,63}$/;

const DEVICE_LOCAL_PREFIXES = ["dwlocal-", "local-"];

const CONFUSABLES: Record<string, string> = { I: "1", L: "1", O: "0" };

const CHECK_LENGTH = 4;

export type JoinCardDecode =
  | {
      ok: true;
      workshopId: string;
      code: string;
    }
  | { ok: false; message: string };

function fold(text: string): string {
  return text
    .split("")
    .map((character) => CONFUSABLES[character] ?? character)
    .join("");
}

export function looksLikeJoinCard(input: string | null | undefined): boolean {
  const parts = (input ?? "").replace(/\s+/g, "").toUpperCase().split(":");
  return parts.length === 4 && parts[0].startsWith(WORKSHOP_CODE_NAMESPACE) && parts[1] === JOIN_LETTER;
}

export function decodeJoinCard(input: string | null | undefined): JoinCardDecode {
  const raw = (input ?? "").replace(/\s+/g, "").toUpperCase();
  if (!raw) return { ok: false, message: "Nothing was scanned or typed." };

  const parts = raw.split(":");
  if (parts.length !== 4 || !parts[0].startsWith(WORKSHOP_CODE_NAMESPACE)) {
    return {
      ok: false,
      message:
        "Not a join card. Join cards begin “DPW”."
    };
  }
  const versionText = parts[0].slice(WORKSHOP_CODE_NAMESPACE.length);
  if (!/^\d+$/.test(versionText)) {
    return {
      ok: false,
      message: "Not a join card. Join cards begin “DPW”."
    };
  }
  if (parts[1] !== JOIN_LETTER) {
    return {
      ok: false,
      message:
        "That's a record code, not a join card."
    };
  }
  if (Number(versionText) !== JOIN_CODE_VERSION) {
    return {
      ok: false,
      message:
        "Printed in a newer format. Reload to update the app."
    };
  }

  const body = parts[2];
  if (body.split(".").length !== 2) {
    return {
      ok: false,
      message: "Incomplete or damaged. Check each character."
    };
  }
  const [idText, secretText] = body.split(".");
  const workshopId = idText.toLowerCase();
  if (DEVICE_LOCAL_PREFIXES.some((prefix) => workshopId.startsWith(prefix))) {
    return {
      ok: false,
      message:
        "Printed before the workshop synced. Ask for a fresh card."
    };
  }
  if (!ID_PATTERN.test(workshopId)) {
    return {
      ok: false,
      message: "Incomplete or damaged. Check each character."
    };
  }
  const secret = fold(secretText);
  if (!SECRET_PATTERN.test(secret)) {
    return {
      ok: false,
      message:
        "Incomplete or damaged. Check each character."
    };
  }
  const typedCheck = fold(parts[3]);
  const prefix = `${WORKSHOP_CODE_NAMESPACE}${JOIN_CODE_VERSION}:${JOIN_LETTER}:${idText}.${secret}`;
  if (typedCheck.length !== CHECK_LENGTH || typedCheck !== workshopCodeCheck(prefix)) {
    return {
      ok: false,
      message: "This card does not check out — a character is wrong. Check each one."
    };
  }
  return { ok: true, workshopId, code: `${prefix}:${typedCheck}` };
}

export type JoinCard = {
  id: string;
  recordType: string;
  recordId: string;
  secretLast4: string;
  maxUses: number | null;
  usesConsumed: number;
  expiresAt: string | null;
  revokedAt: string | null;
  label: string | null;
  createdAt: string | null;
  issuedBy: { id: string | null; name: string; email: string } | null;
  code?: string | null;
};

export type JoinCardList = { grants: JoinCard[]; truncated: boolean };

export type JoinRedemption = { outcome: string; reason?: string | null; workshopId?: string | null; detail?: string | null };

export const JOIN_CARD_OFFLINE_MESSAGE =
  "Join cards need a connection. Try again online.";

export const JOIN_CARD_REDEEM_OFFLINE_MESSAGE =
  "No connection — the card wasn't used. Scan it again online.";

const FALLBACK_REFUSAL =
  "This card can't be used — it may be cancelled or expired. Ask for a fresh one.";

export type JoinCardFailure = { kind: "refused"; message: string } | { kind: "offline"; message: string };

export function joinCardFailure(error: unknown, offlineMessage = JOIN_CARD_OFFLINE_MESSAGE): JoinCardFailure {
  const verdict = triageFailure(error);
  if (verdict.kind === "unreachable") return { kind: "offline", message: offlineMessage };
  if (verdict.kind === "transient") {
    return { kind: "refused", message: "Try again in a moment." };
  }
  return { kind: "refused", message: serverSentence(error) ?? FALLBACK_REFUSAL };
}

const BASE = "/design-workshop-access";

export function mintJoinCard(
  workshopId: string,
  options: { maxUses?: number | null; daysValid?: number | null; label?: string | null } = {}
): Promise<JoinCard> {
  return apiFetch<JoinCard>(`${BASE}/grants`, {
    method: "POST",
    body: JSON.stringify({
      recordType: "DESIGN_WORKSHOP",
      recordId: workshopId,
      maxUses: options.maxUses === undefined ? 1 : options.maxUses,
      daysValid: options.daysValid ?? null,
      label: options.label?.trim() ? options.label.trim() : null
    })
  });
}

export function listJoinCards(workshopId: string): Promise<JoinCardList> {
  return apiFetch<JoinCardList>(`${BASE}/grants/${encodeURIComponent(workshopId)}`);
}

export function revokeJoinCard(tokenId: string): Promise<JoinCard> {
  return apiFetch<JoinCard>(`${BASE}/grants/${encodeURIComponent(tokenId)}/revoke`, { method: "POST" });
}

export function redeemJoinCard(code: string, scannedAt: Date = new Date()): Promise<JoinRedemption> {
  return apiFetch<JoinRedemption>(`${BASE}/redemptions`, {
    method: "POST",
    body: JSON.stringify({ code, scannedAt: scannedAt.toISOString() })
  });
}

export function joinOutcomeIsMembership(outcome: string | null | undefined): boolean {
  return outcome === "FULL" || outcome === "ALREADY_A_MEMBER";
}

export function joinOutcomeSentence(answer: JoinRedemption): string {
  if (answer.detail && answer.detail.trim()) return answer.detail;
  switch (answer.outcome) {
    case "FULL":
      return "You're on this workshop. The card is now used up.";
    case "ALREADY_A_MEMBER":
      return "You're already on this workshop. The card wasn't used.";
    case "PROVISIONAL":
      return "Waiting for an admin to confirm you. Anything you record is kept.";
    default:
      return "Card read, but the reply was unclear. Check your workshop list.";
  }
}

export function joinCardState(card: JoinCard, now: number = Date.now()): string {
  const issuer = card.issuedBy?.name?.trim();
  const by = issuer ? ` Printed by ${issuer}.` : "";
  const label = card.label?.trim() ? ` ${card.label.trim()}.` : "";
  if (card.revokedAt) {
    return `Cancelled.${by}${label}`;
  }
  const ceiling = card.maxUses;
  if (ceiling !== null && card.usesConsumed >= ceiling) {
    return `Used up.${by}${label}`;
  }
  const expires = card.expiresAt ? Date.parse(card.expiresAt) : Number.NaN;
  if (Number.isFinite(expires) && expires <= now) {
    return `Expired.${by}${label}`;
  }
  const remaining = ceiling === null ? "any number of people" : `${ceiling - card.usesConsumed} more ${ceiling - card.usesConsumed === 1 ? "person" : "people"}`;
  return `Still good for ${remaining}.${by}${label}`;
}

export const JOIN_CARD_MAX_DAYS = 120;
export const JOIN_CARD_DEFAULT_DAYS = 14;
