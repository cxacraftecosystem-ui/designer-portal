/**
 * THE MINISTRY'S SANCTION REGISTER — the web client's half of `/api/sanction-orders`.
 *
 * A sanction order is the document that authorises a design & prototype workshop and names its
 * budget. A ministry officer records five facts and the server writes seven rows in one
 * transaction: an allow-list admission, an empanelment, an account (only where the mailbox has
 * none), a designer profile, a workshop, the designer's viewer row on it, and the order itself.
 * The argument for all of that lives in `backend/app/services/sanction_orders.py`; this file is
 * only the wire.
 *
 * ── THE AMOUNT IS A STRING, AND THE FIRMNESS OF THAT LINE IS EARNED ──────────────────────────────
 *
 * `sanctionAmount` is a DECIMAL STRING on the wire and must never be turned back into a number on
 * the way to the server. `fastapi.encoders.jsonable_encoder` converts a Python `Decimal` to a
 * `float` — measured — which is how `ProductDocumentation.sellingPrice` came to be typed
 * `string | number | null` a few hundred lines away in `lib/types.ts` (a union that exists because
 * nobody could say which arrives) and how a rupee amount came to be decoded as a `Double` on every
 * handset in the field. The sanction route builds its payload BY HAND with `str(...)`, so here we
 * can say which it is: always a string. Format it with {@link formatSanctionAmount} at RENDER time
 * and never round-trip the result. `frontend/e2e` and
 * `backend/tests/test_sanction_orders.py::test_the_amount_never_reaches_the_wire_as_a_float` are
 * what keep both halves of that true.
 *
 * ── PAGING AND FILTERING ARE THE SERVER'S ───────────────────────────────────────────────────────
 *
 * Nothing here fetches "the register" — it fetches a PAGE of it and passes every narrowing as a
 * query parameter. A client-side `.filter()` over one page reports a list that is the right SIZE
 * while having silently dropped whatever it excluded, on a screen whose entire job is to be
 * complete. `docs/OPEN_FINDINGS.md` records four closed defects from the design-workshop viewer
 * picker and three of them are that same mistake.
 *
 * ── NOTHING IS EMAILED, AND THE SCREEN MUST NOT IMPLY OTHERWISE ─────────────────────────────────
 *
 * There is no mailer in this product. `credential_links.delivery()` hard-returns `CopyLinkDelivery`,
 * which logs one line WITHOUT the link and answers `"COPY_LINK"`. The sign-in link comes back in
 * the 201 body ONCE — the table stores only a SHA-256 digest, so nothing can show it again — and
 * the officer is the transport. {@link sanctionMessageFor} is the prewritten message they paste
 * into WhatsApp or their own mail client, so that the transport is a paste rather than a
 * composition. Any copy on this feature that says "we have emailed them" would be false.
 */

import { apiFetch, buildQuery } from "@/lib/api";
import { hasRank } from "@/lib/permissions";
import type { DwStatus } from "@/lib/designWorkshops";
import type { User } from "@/lib/types";

/**
 * One sanction order, exactly as `sanction_payload` builds it.
 *
 * DECLARED HERE AND NOT IN `lib/types.ts`, which is where every other wire type in this client
 * lives. That is a file-ownership accident of the change that landed this feature rather than a
 * design opinion, and moving these three types into `lib/types.ts` — re-exporting them from here so
 * no import breaks — is a welcome follow-up.
 */
export type SanctionOrder = {
  id: string;
  /** The ministry's own spelling, as the officer typed it. */
  sanctionOrderNo: string;
  /** ISO date, "YYYY-MM-DD". */
  sanctionOrderDate: string | null;
  /**
   * A DECIMAL STRING AND NOT A NUMBER — see the module header. Format with
   * {@link formatSanctionAmount}; never `parseFloat` it back onto a request.
   */
  sanctionAmount: string;
  designerUserId: string;
  designerName: string;
  /**
   * The CANONICAL mailbox the order was recorded against, which for a dotted or `+tagged` Gmail is
   * NOT the address the designer signs in with. That asymmetry is deliberate on the server — both
   * sign-in doors look `User.email` up literally, so canonicalising it would lock the designer out
   * — and it is confusing enough that {@link sanctionMessageFor} names the sign-in address
   * explicitly rather than this one.
   */
  designerEmail: string;
  /** Did this order mint the account? Decides whether a sign-in link is offered at all. */
  accountCreated: boolean;
  notes: string | null;
  designWorkshopId: string;
  workshopTitle: string;
  /** Widened with `string` for the reason `DwSummary.status` is: it arrives through the server's
   * `_enum_str`, and a client that narrowed it would break the day a status is added. */
  workshopStatus: DwStatus | string;
  createdById: string;
  createdByName: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  /** null while the workshop has no stage-1 entry. False means the cover disagrees with the register. */
  reportCopyMatches: boolean | null;
  readyForWork: boolean;
  /**
   * Labels of the stage-1 fields still missing, from the server's `stage_completeness(...).missing`.
   * NEVER RECOUNTED HERE. Two arithmetics inside one product is two answers neither of which is
   * wrong enough to fail, and this list is already shortfall-aware in a way a client count is not.
   */
  missingMandatory: string[];
};

export type SanctionOrderCredentialLink = {
  id: string;
  link: string;
  expiresAt: string;
  purpose: "INVITE" | "RESET";
  /** `"COPY_LINK"` today, always. There is no mailer — see the module header. */
  deliveredBy: string;
};

export type SanctionOrderCreated = {
  sanctionOrder: SanctionOrder;
  credentialLink: SanctionOrderCredentialLink | null;
  /** Set when the link could not be minted (the 4-per-hour budget). The order still stands. */
  credentialLinkProblem: string | null;
};

export type SanctionOrderPage = {
  items: SanctionOrder[];
  total: number;
  page: number;
  pageSize: number;
  pages: number;
};

export type SanctionOrderQuery = {
  page?: number;
  pageSize?: number;
  search?: string;
  mine?: boolean;
  dateFrom?: string;
  dateTo?: string;
  sort?: string;
  dir?: string;
};

/**
 * Record a ministry sanction order — Assistant Director (42) and above.
 *
 * A RANK FLOOR, NOT A SET, and the only rank floor in the design-workshop family. Every sibling
 * rule (`canRunDesignWorkshops`, `canInspectDesignWorkshops`) is set membership, so §2's ladder in
 * `docs/PERMISSIONS.md` gives the wrong answer for those and the RIGHT answer for this one. The
 * three ministry tiers are a chain of seniority within one office: an order an assistant director
 * may record is obviously recordable by the regional director above them, and spelling that as a
 * set would mean re-listing five tokens in every mirror on every future insert.
 *
 * Mirrors `can_record_sanction_orders` in `backend/app/services/sanction_orders.py`.
 *
 * THE SAME THRESHOLD IS ALSO WRITTEN INLINE IN THE `/sanction-orders` ROUTE_GUARDS ROW in
 * `lib/permissions.ts`, and the duplication is deliberate rather than lazy: this module imports
 * `hasRank` FROM that one, so a `can:` there pointing back at this function would be an import
 * cycle whose `const` half would land in the temporal dead zone.
 * `backend/tests/test_sanction_order_gate.py` reads both files off disk and fails if the two ever
 * say different things.
 */
export function canRecordSanctionOrders(user: User | null | undefined) {
  return hasRank(user, "ASSISTANT_DIRECTOR");
}

/**
 * Byte-for-byte `sanction_orders.SANCTION_ORDER_REFUSAL` on the server, and byte-for-byte the
 * `message` of the `/sanction-orders` ROUTE_GUARDS row. Three surfaces say this sentence — the
 * API's 403, the route guard's lock panel and the absent nav entry — and a refusal naming a
 * different next move depending on where you met it is not a rule, it is three rumours.
 */
export const SANCTION_ORDER_REFUSAL =
  "Recording a sanction order is a ministry officer's act — Assistant Director and above. It " +
  "opens a workshop, creates the designer's account and issues their sign-in link, so it is not " +
  "something a designer or a professor can do for themselves. Ask the officer who holds the " +
  "order to record it; the workshop will appear in your list as soon as they do.";

/**
 * A decimal string as Indian rupees, with the lakh/crore grouping.
 *
 * `Number(...)` IS APPLIED HERE AND NOWHERE ELSE — at render, once, on the way to the screen, and
 * the result is never sent back. The whole reason `sanctionAmount` is a string on the wire is that
 * a binary float cannot hold 0.10 exactly; converting it to render is harmless because the pixels
 * are the end of the road, and converting it to SEND would silently re-introduce the defect the
 * string exists to prevent. There is deliberately no `parseSanctionAmount` in this file and
 * `frontend/e2e/sanction-order-amount-unit.spec.ts` reads it as text to be sure `parseFloat` never
 * appears in it.
 */
export function formatSanctionAmount(amount: string | null | undefined): string {
  const raw = String(amount ?? "").trim();
  if (!raw) return "";
  const value = Number(raw);
  if (!Number.isFinite(value)) return raw;
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format(value);
}

/**
 * THE PREWRITTEN MESSAGE, worded once, here.
 *
 * The officer is the transport — there is no mailer — so the smallest honest thing this product can
 * do is hand them a sentence to paste rather than asking them to compose one at nine in the
 * morning, fifteen times. It names four things on purpose:
 *
 *  1. the sanction order, so the designer can tell which of their projects this is;
 *  2. that the link works ONCE and when it expires, because a link they sit on is a link they lose;
 *  3. **the address they sign in with**, which for a dotted Gmail is NOT the address on the
 *     register — see `SanctionOrder.designerEmail`;
 *  4. that the starred fields on Workshop Setup — State, District and the rest — come before any
 *     real work, because the officer's list will otherwise show this sanction as stalled and
 *     neither of them will know why.
 */
export const MESSAGE_TEMPLATE =
  "{designerName}, sanction order {sanctionOrderNo} dated {date} has been recorded and a workshop " +
  "has been opened in your name on the Design Prototype Workshop portal.\n\n" +
  "Set your password here — the link works once and expires on {expires}:\n{link}\n\n" +
  "Sign in with {signInEmail}. After signing in, open the workshop and fill in the starred fields " +
  "on Workshop Setup (State, District, Craft, Cluster, Venue, the dates and the rest) before you " +
  "begin.";

export function sanctionMessageFor(
  order: SanctionOrder,
  link: SanctionOrderCredentialLink,
  signInEmail: string
): string {
  return MESSAGE_TEMPLATE.replace("{designerName}", order.designerName || "Hello")
    .replace("{sanctionOrderNo}", order.sanctionOrderNo)
    .replace("{date}", order.sanctionOrderDate ?? "")
    .replace("{expires}", formatLinkExpiry(link.expiresAt))
    .replace("{link}", link.link)
    .replace("{signInEmail}", signInEmail || order.designerEmail);
}

/** The expiry as a person reads it. Falls back to the raw ISO string rather than to "Invalid Date". */
export function formatLinkExpiry(iso: string): string {
  const when = new Date(iso);
  if (Number.isNaN(when.getTime())) return iso;
  return when.toLocaleString("en-IN", {
    dateStyle: "medium",
    timeStyle: "short"
  });
}

/** The readiness pill's sentence. One scorer — the server's — and never a count made here. */
export function readinessSentence(order: SanctionOrder): string {
  if (order.readyForWork) return "Ready";
  const outstanding = order.missingMandatory.length;
  if (!outstanding) return "Awaiting designer details";
  return `Awaiting designer details — ${outstanding} still needed`;
}

// ── The calls ───────────────────────────────────────────────────────────────────────────────────

export async function listSanctionOrders(query: SanctionOrderQuery = {}) {
  return apiFetch<SanctionOrderPage>(
    `/sanction-orders${buildQuery({
      page: query.page,
      pageSize: query.pageSize,
      search: query.search,
      mine: query.mine ? "true" : undefined,
      dateFrom: query.dateFrom,
      dateTo: query.dateTo,
      sort: query.sort,
      dir: query.dir
    })}`
  );
}

/**
 * The badge. Its own endpoint rather than a field on the list, for the reason
 * `GET /access/roster/pending-count` is: a nav item that had to fetch fifty rows to render a number
 * would either not render it or fetch them on every paint.
 */
export async function fetchAwaitingSanctionCount() {
  return apiFetch<{ awaiting: number }>("/sanction-orders/awaiting-count");
}

export async function getSanctionOrder(id: string) {
  return apiFetch<SanctionOrder>(`/sanction-orders/${encodeURIComponent(id)}`);
}

/**
 * Record one. The 201 carries the sign-in link ONCE — see the module header; there is no second
 * chance to read it and no email behind it.
 */
export async function recordSanctionOrder(body: {
  sanctionOrderNo: string;
  sanctionOrderDate: string;
  sanctionAmount: string;
  designerName: string;
  designerEmail: string;
  notes?: string | null;
}) {
  return apiFetch<SanctionOrderCreated>("/sanction-orders", {
    method: "POST",
    body: JSON.stringify(body)
  });
}

/** `notes` and nothing else — the number, the date and the amount are what the ministry issued. */
export async function updateSanctionNotes(id: string, notes: string | null) {
  return apiFetch<SanctionOrder>(`/sanction-orders/${encodeURIComponent(id)}`, {
    method: "PATCH",
    body: JSON.stringify({ notes })
  });
}

/** Re-mint. 429 when the 4-per-hour-per-designer budget is spent. */
export async function issueSanctionCredentialLink(id: string) {
  return apiFetch<SanctionOrderCredentialLink>(
    `/sanction-orders/${encodeURIComponent(id)}/credential-link`,
    { method: "POST" }
  );
}

/** Withdraw an outstanding link. Idempotent — pressing it twice is not a mistake. */
export async function revokeSanctionCredentialLink(id: string, tokenId: string) {
  return apiFetch<{ ok: boolean }>(
    `/sanction-orders/${encodeURIComponent(id)}/credential-link/revoke${buildQuery({ tokenId })}`,
    { method: "POST" }
  );
}
