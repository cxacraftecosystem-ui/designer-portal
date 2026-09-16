/**
 * THE MINISTRY'S SANCTION REGISTER — the web client's half of `/api/sanction-orders`.
 *
 * A sanction order is the document that authorises a design & prototype workshop and names its
 * budget. A ministry officer records the three facts printed on it — number, date, amount — and the
 * designer or designers it was issued to, and the server writes seven rows plus four per named
 * designer in ONE transaction: an allow-list admission, an empanelment, an account (only where the
 * mailbox has none), a designer profile and a viewer row for each of them, plus the workshop, the
 * order itself and one join row per name. The argument for all of that lives in
 * `backend/app/services/sanction_orders.py`; this file is only the wire.
 *
 * ── IT NAMED EXACTLY ONE DESIGNER UNTIL 0.0.12 ──────────────────────────────────────────────────
 *
 * This paragraph said "records five facts and the server writes seven rows". A sanction order is
 * routinely issued for a TEAM, and while the register could name one designer the second and third
 * were either left off the instrument entirely — no account, no empanelment, unable to open the
 * workshop their own order paid for — or recorded as a second order under a number the ministry
 * never issued.
 *
 * The three designer scalars on {@link SanctionOrder} are unchanged and still mean THE LEAD, beside
 * a new {@link SanctionOrder.designers} collection that holds everybody INCLUDING the lead. That the
 * lead appears twice on the wire is deliberate: the scalars are what every stored report and every
 * deployed client reads, and a list whose first element silently meant something different from the
 * rest would be worse than one redundant name. Exactly one name reaches the report cover, because
 * `<dc:creator>` is a single-author field the .docx format cannot express as a list.
 *
 * ── AND A SHEET OF THEM CAN BE UPLOADED, IN TWO POSTS ───────────────────────────────────────────
 *
 * {@link uploadSanctionOrders} reads a workbook and writes NOTHING — it answers what it will record,
 * what it needs the officer to settle, and what it refuses whatever they say.
 * {@link confirmSanctionImport} carries the answers back. The confirmation is STATELESS: there is no
 * token and no parse held on the server between the two requests, so this client holds the resolved
 * rows and posts them. Every refusal runs again on the way in, which is what makes that safe — a
 * stale tab can be refused and cannot record anything the form would not.
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

import {
  API_BASE,
  ApiError,
  apiFetch,
  assertApiConfigured,
  buildQuery,
  describeApiDetail,
  getToken
} from "@/lib/api";
// The anchor dance and the Content-Disposition parse both live in `lib/designWorkshops` and are
// imported rather than written again — `saveBlobToDisk`'s own docstring records why.
import { fileNameFromDisposition, saveBlobToDisk } from "@/lib/designWorkshops";
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
  /**
   * EVERY designer this order names, the lead first — see {@link SanctionOrderDesigner}.
   *
   * The three scalars above are the lead and are also `designers[0]`. Render from THIS list: an
   * order naming three designers and a row drawing one scalar is a screen that is quietly wrong
   * about who a ministry instrument was issued to.
   */
  designers: SanctionOrderDesigner[];
  /** The workbook this order was imported from, or `null` for one typed on the form. Permanently. */
  sourceFilename: string | null;
  /** The 1-based Excel gutter row, so an officer holding the sheet can find the line. */
  sheetRow: number | null;
};

/**
 * One designer named on one order, from the server's `SanctionOrderDesigner` join.
 *
 * ORDERED BY `position`, LEAD FIRST, AND THE ORDER IS THE SERVER'S. It sorts on
 * `(position, createdAt, designerUserId)` — a total order — precisely so that two identical reads
 * cannot render the team differently. **Never re-sort this array**: `designers[0]` is the lead, the
 * person whose profile seeds stage 1 and whose name reaches the report cover, and a `.sort()` here
 * would move the name on a ministry document.
 */
export type SanctionOrderDesigner = {
  designerUserId: string;
  /** The account's OWN name, read live rather than frozen at sanction time. */
  designerName: string;
  /**
   * The CANONICAL mailbox the order was recorded against — the key both rosters were written under.
   * For a dotted or `+tagged` Gmail this is NOT the address they sign in with; that is
   * {@link signInEmail}, and the distinction is why {@link sanctionMessageFor} takes it separately.
   */
  designerEmail: string;
  /** The literal `User.email` — the ONLY address the sign-in door accepts. */
  signInEmail: string;
  /** Did this order mint THIS account? Per person: with several designers the answer differs. */
  accountCreated: boolean;
  position: number;
};

export type SanctionOrderCredentialLink = {
  id: string;
  link: string;
  expiresAt: string;
  purpose: "INVITE" | "RESET";
  /** `"COPY_LINK"` today, always. There is no mailer — see the module header. */
  deliveredBy: string;
};

/**
 * One designer's first sign-in link, or the honest reason there is none.
 *
 * ONE ENTRY PER ACCOUNT THIS ORDER MINTED, and none at all for a designer who already had one — see
 * the server's `_issue_first_credential` for the three cases. A designer with an existing account
 * signs in as they always do; minting them a link here would sign them out of every device the
 * moment they redeemed it.
 */
export type SanctionOrderCredential = {
  designerUserId: string;
  designerName: string;
  /** The canonical register address. */
  designerEmail: string;
  /** The address they sign in with — the one the prewritten message must name. */
  signInEmail: string;
  link: SanctionOrderCredentialLink | null;
  /** Set when the link could not be minted (the 4-per-hour budget). The order still stands. */
  problem: string | null;
};

export type SanctionOrderCreated = {
  sanctionOrder: SanctionOrder;
  /**
   * THE LEAD'S LINK, and redundant with `credentialLinks[0]` on purpose.
   *
   * These two keys are what this route has always answered with and what
   * `backend/tests/test_sanction_orders.py` asserts by name on the raw response. They are kept so
   * that landing multi-designer did not also change the shape every deployed client reads. **Draw
   * from `credentialLinks`**, which is the complete list; retiring these two is a follow-up for the
   * day that test moves with them.
   */
  credentialLink: SanctionOrderCredentialLink | null;
  credentialLinkProblem: string | null;
  /**
   * One entry per designer whose account THIS order created.
   *
   * A link is shown ONCE and nothing can show it again — the server stores only a SHA-256 digest —
   * so an order that mints four accounts and surfaces one link leaves three designers unable to sign
   * in, with no screen in the product able to say so.
   */
  credentialLinks: SanctionOrderCredential[];
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
  /** THE LEAD's name — the one that reaches stage 1 and the report cover. */
  designerName: string;
  /** THE LEAD's address. */
  designerEmail: string;
  notes?: string | null;
  /**
   * Positions 1..N. Omit for a one-designer order and the body is byte-for-byte the one this
   * form has always sent — which is why the lead stayed two scalars rather than becoming element
   * 0 of a list. **Do not repeat the lead here**: the server collapses a repeat rather than
   * refusing it (the rule `namedDesignerTeam` already applies on the two other create doors), but
   * a client that sends it twice is a client whose author believed something untrue about it.
   */
  coDesigners?: { name: string; email: string }[];
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


// ── The designer directory, and the bulk import ─────────────────────────────────────────────────

/**
 * The accounts an officer may name on an order — **the fifth door, and it had to exist**.
 *
 * Every older designer list refuses an Assistant Director, which is the FLOOR of this feature's own
 * gate: `/designers/roster` and `/designers/directory` are admin-access-or-above,
 * `/design-workshops/eligible-viewers` is `require_admin` (the SET {ADMIN, MASTER_ADMIN}), and
 * `/design-workshop-oversight/designers` is `require_workshop_assigner` ({MINISTRY_ADMIN, ADMIN,
 * MASTER_ADMIN}). So ranks 42 and 45 could record a sanction order and reach no list of the people
 * they were naming on it — the trap `PromoteDialog.tsx` documents at length, one tier down.
 *
 * A FIFTH DOOR AND NOT A WIDENED GATE: `can_manage_designer_roster` is what stands in front of the
 * empanelment table, and `OVERSIGHT_ASSIGNER_ROLES` excludes a Regional Director on purpose ("the
 * supervised must not choose the supervisor").
 *
 * ⚠ THE RETURN SHAPE IS `EligibleDesignerPage`'s, AND THAT IS A CONTRACT RATHER THAN A COINCIDENCE.
 * `WorkshopDesignerPicker`'s `fetchEligible` prop takes
 * `(search: string) => Promise<{users, truncated}>` with four keys per row, and three endpoints now
 * answer it. A fifth key here would not be a feature, it would be a second control.
 */
export async function listSanctionDesigners(search?: string) {
  return apiFetch<{
    users: { id: string; name: string; email: string; role: string }[];
    truncated: boolean;
  }>(`/sanction-orders/designers${buildQuery({ search: search || undefined })}`);
}

/** One thing the parser or the reconciliation could not do cleanly — `xlsx_table.ParseProblem`. */
export type SanctionParseProblem = {
  sheet: string;
  /** The 1-based Excel gutter row. `null` means the problem is about the WORKBOOK, not a row. */
  row: number | null;
  /** `"error"` = nothing recorded for that row; `"warning"` = it was, and something was assumed. */
  severity: string;
  reason: string;
  value: string | null;
};

/** One person a reviewed row proposes to name. */
export type SanctionImportDesigner = {
  name: string;
  email: string;
  canonicalEmail: string;
  /** `null` is not a missing value: it means this order will MINT the account, which is ordinary. */
  userId: string | null;
  accountExists: boolean;
  /** Never having been empanelled is ordinary; an empanelment somebody ENDED is refused, not shown. */
  empanelled: boolean;
  /** `"record"` when the name was filled in from a stored record rather than given on the sheet. */
  nameSource: string;
};

/** One thing about a row that only the officer can settle. */
export type SanctionImportQuestion = {
  code: string;
  /** Rendered VERBATIM. It was written on the server to be read by a person. */
  question: string;
  /** Empty is not an empty dropdown — it means the answer has to be typed. */
  candidates: { name: string; email: string; source: string }[];
};

export type SanctionImportRow = {
  sheetRow: number;
  sanctionOrderNo: string;
  sanctionOrderDate: string | null;
  /** A DECIMAL STRING, at this layer as at every other — see the module header. */
  sanctionAmount: string | null;
  notes: string | null;
  verdict: string;
  /** Set only on a refused row, and rendered verbatim. */
  reason: string | null;
  designers: SanctionImportDesigner[];
  questions: SanctionImportQuestion[];
  unpairedNames: string[];
  unpairedEmails: string[];
};

export type SanctionImportPreview = {
  sheet: string | null;
  sourceFilename: string | null;
  /**
   * THE SHEET'S OWN COUNT, not `ready + needsReview + refused`. A row the parser could not read at
   * all appears in none of the three lists and is still counted here, so the report's arithmetic
   * describes the file the officer is holding rather than the part of it that parsed.
   */
  rowsRead: number;
  ready: SanctionImportRow[];
  needsReview: SanctionImportRow[];
  refused: SanctionImportRow[];
  problems: SanctionParseProblem[];
};

export type SanctionImportReport = {
  sheet: string | null;
  sourceFilename: string | null;
  rowsRead: number;
  recorded: number;
  skipped: number;
  refused: number;
  /** How many people this press let in. Every one of them needs a sign-in link re-issued by hand. */
  accountsCreated: number;
  /** Always 0 — an import mints no credentials. See {@link confirmSanctionImport}. */
  credentialLinksIssued: number;
  created: {
    sheetRow: number;
    sanctionOrderId: string;
    sanctionOrderNo: string;
    designWorkshopId: string;
    designers: SanctionOrderDesigner[];
    accountsCreated: number;
  }[];
  /** `null` when the ledger row could not be written — the orders still stand. */
  importId: string | null;
  problems: SanctionParseProblem[];
};

/**
 * STEP ONE: read the sheet and WRITE NOTHING.
 *
 * `apiFetch` leaves `Content-Type` unset for a `FormData` body so the browser writes the multipart
 * boundary itself — **adding a header breaks the upload**. There is deliberately no scalar on this
 * body: the annual plan's has two, and each is typed `str | None` and hand-validated for a reason
 * (a bare default is read off the QUERY STRING by FastAPI, so a client that put it in the body has
 * it silently ignored under a 201). This route has nothing to put there — the date is on every row
 * and there is no destructive flag to guard.
 */
export async function uploadSanctionOrders(file: File) {
  const body = new FormData();
  body.append("file", file);
  return apiFetch<SanctionImportPreview>("/sanction-orders/upload", { method: "POST", body });
}

/**
 * STEP TWO: record what the officer agreed to. **No file, and no token.**
 *
 * The whole resolved set travels because the confirmation is STATELESS — this repository holds no
 * server-side inter-request state anywhere, and its only short-TTL-token precedent stores a digest
 * rather than the token. The cost is stated rather than hidden: a stale tab can post a resolution
 * built against a register that has moved on. It is not silent, because every refusal runs again per
 * row on the way in, so a stale row comes back REFUSED with its own sentence rather than recorded
 * wrongly.
 *
 * **NO SIGN-IN LINKS COME BACK.** Two hundred one-time credentials on one screen changes the
 * security posture of the feature rather than its ergonomics: a link is shown once, cannot be shown
 * again, and the officer's clipboard is the only transport there is. `accountsCreated` says how many
 * people were let in, and each link is re-issued from that order's own row on the register.
 */
export async function confirmSanctionImport(body: {
  sheet?: string | null;
  sourceFilename?: string | null;
  rowsRead: number;
  /**
   * How many rows the preview refused that CANNOT be sent back.
   *
   * A row whose date or amount could not be read has no legal shape in a confirm row —
   * `sanctionOrderDate` is a required date — so it is not that this client chose to leave it out,
   * it cannot describe it. Without this the report's `rowsRead = recorded + skipped + refused`
   * silently stops summing, on a panel whose whole value is that an officer can check it.
   */
  refusedBeforeConfirm: number;
  rows: {
    sheetRow: number;
    action: "record" | "skip";
    sanctionOrderNo: string;
    sanctionOrderDate: string;
    sanctionAmount: string;
    notes?: string | null;
    /** THE LEAD IS ELEMENT 0. The server splits this back into its lead pair plus `coDesigners`. */
    designers: { name: string; email: string }[];
  }[];
}) {
  return apiFetch<SanctionImportReport>("/sanction-orders/upload/confirm", {
    method: "POST",
    body: JSON.stringify(body)
  });
}

/**
 * The blank workbook an office types its sanction orders into.
 *
 * ⚠ **A BEARER FETCH AND NOT AN `<a href>`, AND THE DIFFERENCE IS A 401.** Every arm of
 * `/api/sanction-orders` is gated — the pro-forma included, even though it carries no register data
 * at all — so a plain link would send no `Authorization` header and the officer would get a refusal
 * where they expected a download. `apiFetch` cannot be used either: it parses JSON, and this is a
 * zip.
 *
 * The shape is `annualPlan.fetchWorkbook`'s, including the error branch that rebuilds the `ApiError`
 * that `apiFetch` would otherwise have thrown — without it a 403 on a download reaches the screen as
 * a blank box. `statusText` is empty over HTTP/2, which every deployed request is, so it can never
 * be the last resort on its own.
 *
 * **IT IS NOT AN EXPORT AND MUST NOT GROW INTO ONE.** There is no sanction export in this release.
 * The annual plan's round-trip hazard — a filtered export re-uploaded destructively — does not
 * transfer, because there is no `withdrawAbsent` equivalent and no delete on this register. If an
 * export is built later, say that in its own header so nobody copies the filter machinery for a
 * round trip that cannot lose anything.
 */
export async function downloadSanctionProForma(): Promise<void> {
  assertApiConfigured();
  const headers = new Headers();
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const response = await fetch(`${API_BASE}/api/sanction-orders/pro-forma.xlsx`, {
    headers,
    cache: "no-store"
  });
  if (!response.ok) {
    const contentType = response.headers.get("content-type") ?? "";
    const payload = contentType.includes("application/json")
      ? await response.json()
      : await response.text();
    const detail =
      typeof payload === "object" && payload && "detail" in payload
        ? (payload as { detail: unknown }).detail
        : undefined;
    throw new ApiError(
      response.status,
      describeApiDetail(
        detail,
        response.statusText || `The server refused the request (HTTP ${response.status}).`
      ),
      payload
    );
  }
  saveBlobToDisk(
    await response.blob(),
    fileNameFromDisposition(response.headers.get("content-disposition")) ??
      "sanction-orders-pro-forma.xlsx"
  );
}
