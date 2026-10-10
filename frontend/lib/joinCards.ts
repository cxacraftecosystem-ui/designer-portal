/**
 * THE JOIN CARD ON THE WEB: reading one, printing one, listing and cancelling them, and redeeming one.
 *
 * The handset has had this since `ui/DwJoinCard.kt`; this is the same client over the same four
 * routes (`backend/app/api/routes/design_workshop_access.py`), and every rule that decides anything —
 * single use for a non-admin, the three-outstanding cap, expiry and its 30-day sync grace, the
 * provisional foothold for a late-comer or an ineligible account, a post-holder refused from printing,
 * one seat per person per card — is enforced by `services/design_workshop_grants.py` and is NOT
 * re-decided here. What this module adds is what a client owes those rules:
 *
 *  1. THE SERVER'S SENTENCE IS SHOWN AS GIVEN. Full, provisional and already-a-member are deliberately
 *     distinguishable; unknown, cancelled and long-expired are deliberately one sentence. Rewording
 *     either half would invent or erase a distinction the server makes on purpose.
 *  2. PROVISIONAL IS NOT MEMBERSHIP. {@link joinOutcomeIsMembership} answers false for it and for any
 *     word this build does not know.
 *  3. A CARD IS NEVER ADMISSION OFFLINE. There is no signature on a card; authority is the 110-bit
 *     secret looked up online. With no connection nothing is claimed, and — unlike the handset, which
 *     writes the scan to its own queue — the browser does NOT keep the card: a live credential in
 *     browser storage outlives the tab and is readable by anything else on the origin. The person is
 *     told to scan it again when they are back online, and the card is unspent until then.
 *  4. THE SECRET IS NEVER STORED, LOGGED OR ECHOED. A minted card's `code` lives in component state for
 *     as long as it is on screen; the list can only ever show the last four characters.
 *
 * `decodeJoinCard` is a port of `design_workshop_grants.decode_join_code` (and of the handset's
 * `decodeWorkshopJoinCard`) that agrees on the CANONICAL string, because that string is what is posted
 * and the server recomputes the check over it. `e2e/join-cards-unit.spec.ts` pins both.
 */

import { apiFetch } from "@/lib/api";
import { serverSentence, triageFailure } from "@/lib/failureTriage";
import { WORKSHOP_CODE_NAMESPACE, workshopCodeCheck } from "@/lib/workshopCodes";

/** The payload version a join card is printed at. `JOIN_CODE_VERSION` on the server. */
export const JOIN_CODE_VERSION = 2;

/** The letter that marks a join card. Reserved: no record type may ever use it. */
export const JOIN_LETTER = "J";

/** Crockford base32 — no I, L, O or U — so a secret has one character set and no look-alikes. */
const SECRET_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/** 22 characters of that alphabet is 110 bits. */
const SECRET_LENGTH = 22;

const SECRET_PATTERN = new RegExp(`^[${SECRET_ALPHABET}]{${SECRET_LENGTH}}$`);

/** The shape of an id this repository issues — `_ID_PATTERN` on the server. */
const ID_PATTERN = /^[a-z0-9][a-z0-9-]{7,63}$/;

/** A draft that has never left one device — both clients' spellings. */
const DEVICE_LOCAL_PREFIXES = ["dwlocal-", "local-"];

/** Folded on the SECRET and the check, never on the id (a cuid legitimately holds 0 and o). */
const CONFUSABLES: Record<string, string> = { I: "1", L: "1", O: "0" };

const CHECK_LENGTH = 4;

export type JoinCardDecode =
  | {
      ok: true;
      /** The workshop the card names, lower-cased. A label for the screen, never authority. */
      workshopId: string;
      /** The canonical card INCLUDING ITS SECRET. Posted to the redemption and never shown or kept. */
      code: string;
    }
  | { ok: false; message: string };

function fold(text: string): string {
  return text
    .split("")
    .map((character) => CONFUSABLES[character] ?? character)
    .join("");
}

/** Does this string CLAIM to be a join card? Read from the letter alone; it reads no secret. */
export function looksLikeJoinCard(input: string | null | undefined): boolean {
  const parts = (input ?? "").replace(/\s+/g, "").toUpperCase().split(":");
  return parts.length === 4 && parts[0].startsWith(WORKSHOP_CODE_NAMESPACE) && parts[1] === JOIN_LETTER;
}

/** Read a scanned or typed join card. PURE — every refusal is about the string, never about a record. */
export function decodeJoinCard(input: string | null | undefined): JoinCardDecode {
  const raw = (input ?? "").replace(/\s+/g, "").toUpperCase();
  if (!raw) return { ok: false, message: "Nothing was scanned or typed." };

  const parts = raw.split(":");
  if (parts.length !== 4 || !parts[0].startsWith(WORKSHOP_CODE_NAMESPACE)) {
    return {
      ok: false,
      message:
        "That is not a workshop join card. Join cards begin “DPW”; a shop barcode, a payment code or a web address will not let you into a workshop."
    };
  }
  const versionText = parts[0].slice(WORKSHOP_CODE_NAMESPACE.length);
  if (!/^\d+$/.test(versionText)) {
    return {
      ok: false,
      message: "That is not a workshop join card. Join cards begin “DPW” followed by a version number."
    };
  }
  if (parts[1] !== JOIN_LETTER) {
    return {
      ok: false,
      message:
        "That code belongs to this application but is not a join card — it names a record. Scan the join card you were handed, the one printed to let somebody in."
    };
  }
  if (Number(versionText) !== JOIN_CODE_VERSION) {
    return {
      ok: false,
      message:
        "That join card was printed in a newer format. Reload the page to update the app, or ask an administrator to add you from the workshop's viewers screen."
    };
  }

  const body = parts[2];
  if (body.split(".").length !== 2) {
    return {
      ok: false,
      message: "This join card is damaged or was typed incompletely. Check it against the card, character by character."
    };
  }
  const [idText, secretText] = body.split(".");
  const workshopId = idText.toLowerCase();
  if (DEVICE_LOCAL_PREFIXES.some((prefix) => workshopId.startsWith(prefix))) {
    return {
      ok: false,
      message:
        "That card names a workshop that had not been shared yet when it was printed — it only ever meant anything on the device that made it. Ask whoever created the workshop to sync their device and print a fresh card."
    };
  }
  if (!ID_PATTERN.test(workshopId)) {
    return {
      ok: false,
      message: "This join card is damaged or was typed incompletely — the identifier in it is not a whole one. Check it against the card."
    };
  }
  const secret = fold(secretText);
  if (!SECRET_PATTERN.test(secret)) {
    return {
      ok: false,
      message:
        "This join card is damaged or was typed incompletely — the part after the full stop is not a whole one. Read it off the card again, character by character."
    };
  }
  const typedCheck = fold(parts[3]);
  const prefix = `${WORKSHOP_CODE_NAMESPACE}${JOIN_CODE_VERSION}:${JOIN_LETTER}:${idText}.${secret}`;
  if (typedCheck.length !== CHECK_LENGTH || typedCheck !== workshopCodeCheck(prefix)) {
    return {
      ok: false,
      message: "This join card does not check out, so one of its characters is wrong. Read it off the card again, character by character."
    };
  }
  return { ok: true, workshopId, code: `${prefix}:${typedCheck}` };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The wire
 * ──────────────────────────────────────────────────────────────────────────── */

export type JoinCard = {
  id: string;
  recordType: string;
  recordId: string;
  secretLast4: string;
  /** Null means any number of people — admin-only. */
  maxUses: number | null;
  usesConsumed: number;
  expiresAt: string | null;
  revokedAt: string | null;
  label: string | null;
  createdAt: string | null;
  issuedBy: { id: string | null; name: string; email: string } | null;
  /** Present on the mint's answer ONLY — the one moment the secret exists anywhere but on paper. */
  code?: string | null;
};

export type JoinCardList = { grants: JoinCard[]; truncated: boolean };

export type JoinRedemption = { outcome: string; reason?: string | null; workshopId?: string | null; detail?: string | null };

/** Said when there is no connection, by every card action alike. */
export const JOIN_CARD_OFFLINE_MESSAGE =
  "There is no connection, so this cannot be done right now. A join card is made and checked online — it is a key, not something this browser can invent — so try again when you are back online.";

/** Said for a redemption with no connection. Nothing was spent: the card is still good. */
export const JOIN_CARD_REDEEM_OFFLINE_MESSAGE =
  "There is no connection, so the card was not used and you are not on the workshop yet. Keep the card and scan it again when you are back online — it has not been spent.";

/** Shown only when a refusal arrives with no sentence of its own. Names no workshop. */
const FALLBACK_REFUSAL =
  "That join card cannot be used. It may have been cancelled, or it may have run out of date. Ask whoever runs the workshop for a fresh card, or ask an administrator to add you from the workshop's viewers screen.";

export type JoinCardFailure = { kind: "refused"; message: string } | { kind: "offline"; message: string };

/**
 * One reading of every failure, asked of `lib/failureTriage.ts` rather than decided here: an answer
 * that refused is shown in the server's words; "not now" says to try again; nothing reached is offline.
 */
export function joinCardFailure(error: unknown, offlineMessage = JOIN_CARD_OFFLINE_MESSAGE): JoinCardFailure {
  const verdict = triageFailure(error);
  if (verdict.kind === "unreachable") return { kind: "offline", message: offlineMessage };
  if (verdict.kind === "transient") {
    // Not the card's fault and not a verdict on it: a seat taken mid-failure is given back, so the
    // same card works on the next try.
    return { kind: "refused", message: "That could not be done just now. Try again in a moment — nothing has been used up." };
  }
  return { kind: "refused", message: serverSentence(error) ?? FALLBACK_REFUSAL };
}

const BASE = "/design-workshop-access";

/** Print one card. Non-admins are given single-use cards whatever is asked; the server enforces it. */
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

/**
 * Present a card. The browser has no monotonic clock to report, so the only evidence sent is the wall
 * clock at the moment of the scan — the server stores it beside its own arrival time and decides
 * nothing by it.
 */
export function redeemJoinCard(code: string, scannedAt: Date = new Date()): Promise<JoinRedemption> {
  return apiFetch<JoinRedemption>(`${BASE}/redemptions`, {
    method: "POST",
    body: JSON.stringify({ code, scannedAt: scannedAt.toISOString() })
  });
}

/* ────────────────────────────────────────────────────────────────────────────
 * What the answers mean
 * ──────────────────────────────────────────────────────────────────────────── */

/** FULL and ALREADY_A_MEMBER put the person on the workshop. PROVISIONAL, and any unknown word, do not. */
export function joinOutcomeIsMembership(outcome: string | null | undefined): boolean {
  return outcome === "FULL" || outcome === "ALREADY_A_MEMBER";
}

/** The server's sentence, or — if a proxy ate the body — a plainer one per outcome that claims no more. */
export function joinOutcomeSentence(answer: JoinRedemption): string {
  if (answer.detail && answer.detail.trim()) return answer.detail;
  switch (answer.outcome) {
    case "FULL":
      return "You are on this workshop. The card has been used up, so it will not let anybody else in.";
    case "ALREADY_A_MEMBER":
      return "You are already on this workshop, so the card was not used up. Somebody else can still use it.";
    case "PROVISIONAL":
      return "You are not on the workshop yet, but nothing you record is lost — an administrator can see that you scanned the card, and once they confirm you everything you have recorded is already in place. Until then you will not see anybody else's stages.";
    default:
      return "The card was read, but the answer could not be understood. Check your workshop list, and ask an administrator if you cannot see the workshop.";
  }
}

/**
 * One card's state in the words somebody acts on. A port of the handset's `dwJoinCardState`, in the
 * same order: cancelled beats everything, then used up, then the date. PURE.
 */
export function joinCardState(card: JoinCard, now: number = Date.now()): string {
  const issuer = card.issuedBy?.name?.trim();
  const by = issuer ? ` Printed by ${issuer}.` : "";
  const label = card.label?.trim() ? ` ${card.label.trim()}.` : "";
  if (card.revokedAt) {
    return `Cancelled. It will not let anybody else in — anybody it already admitted is still on the workshop, and taking that away is the viewers screen.${by}${label}`;
  }
  const ceiling = card.maxUses;
  if (ceiling !== null && card.usesConsumed >= ceiling) {
    return `Used up. Somebody has already joined with it, so it will not let anybody else in.${by}${label}`;
  }
  const expires = card.expiresAt ? Date.parse(card.expiresAt) : Number.NaN;
  if (Number.isFinite(expires) && expires <= now) {
    return `Out of date. Somebody scanning it now will not be let straight in, though a scan taken before it lapsed can still reach an administrator.${by}${label}`;
  }
  const remaining = ceiling === null ? "any number of people" : `${ceiling - card.usesConsumed} more ${ceiling - card.usesConsumed === 1 ? "person" : "people"}`;
  return `Still good for ${remaining}.${by}${label}`;
}

/** The longest a card may be valid for, and the default — `MAX_GRANT_DAYS` / `DEFAULT_GRANT_DAYS`. */
export const JOIN_CARD_MAX_DAYS = 120;
export const JOIN_CARD_DEFAULT_DAYS = 14;
