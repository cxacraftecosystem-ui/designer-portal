/**
 * THE PLATFORM ALLOW-LIST — who may sign in to this application at all — and the queue of people
 * asking to.
 *
 * NOT `lib/designers.ts`, WHICH IS A DIFFERENT LIST WITH A CONFUSINGLY SIMILAR JOB. `DesignerRoster`
 * says who the institution recognises as a DESIGNER; this one says who may reach the product at all,
 * whatever their tier. They are two tables on the server (`app/services/designers.py` and
 * `app/services/access_roster.py`), two endpoints, and two refusals with two different remedies —
 * and a screen that merged them would tell a suspended crowdsource volunteer that their "designer
 * access" ended. Everything here carries the word `access`; keep it that way.
 *
 * ── THE FOUR STATES ──────────────────────────────────────────────────────────────────────────────
 *
 * `ACTIVE` admits. `PENDING` is somebody who proved an identity and was turned away, recorded so an
 * administrator can decide; it is the queue this screen exists for. `REJECTED` is an administrator's
 * answer to that request and it does NOT reopen when the person tries again — the next attempt bumps
 * `attemptCount` and leaves the status alone, which is the only version of this that leaves the
 * queue workable (see the server's `record_refused_attempt`). `SUSPENDED` is access ended after it
 * was granted. Nothing here deletes: the row is the record that the address was seen, admitted or
 * refused, and it outlives the access.
 *
 * ── WHAT AN UNAUTHENTICATED STRANGER CAN PUT IN THIS TABLE ───────────────────────────────────────
 *
 * The email address, and nothing else. A PENDING row is written by the login endpoint AFTER a
 * password or a Google token has verified, carries no display name from the identity provider and
 * no free text at all, and one address is one row however many times it is tried. So the queue an
 * admin reads below is a list of addresses, dates and integers — there is nowhere in it for somebody
 * to write a sentence pretending to be from the product. Do not add a column that renders text an
 * unauthenticated caller supplied; the server does not store one, and this file must not invent one.
 *
 * ── PAGING IS THE SERVER'S AND THE SEARCH IS TOO ─────────────────────────────────────────────────
 *
 * `docs/OPEN_FINDINGS.md` records four closed defects from the design-workshop viewer picker, and
 * three of them are the same mistake: a list read with ONE request, filtered in the browser, whose
 * missing rows were invisible because nothing on the wire said the answer was a prefix. So this
 * module never fetches "the roster" — it fetches a PAGE of it, passes `search` to the server, and
 * every caller renders {@link AccessRosterPage.total} and `pages` rather than counting what it holds.
 * A client-side `.filter()` over one page is the defect, spelled slightly differently.
 */

import { apiFetch, buildQuery } from "@/lib/api";
import type { PageResult, UserRole } from "@/lib/types";

/* ────────────────────────────────────────────────────────────────────────────
 * The wire
 * ──────────────────────────────────────────────────────────────────────────── */

/** The four states a row can be in, exactly as `app/services/access_roster.py` spells them. */
export type AccessStatus = "ACTIVE" | "PENDING" | "REJECTED" | "SUSPENDED";

/**
 * One allow-list row, as `access_payload` serves it.
 *
 * `joinedAt` IS THE REQUIREMENT'S "date of joining the platform" and it is written once: a person
 * admitted in 2024, suspended, and restored this morning still joined in 2024. Do not render it as
 * "last approved" — an admin reading a joining date of last Tuesday draws the wrong conclusion about
 * every record that person created.
 *
 * `requestedAt`, `attemptCount` and `lastAttemptAt` are the queue's own columns: when they first
 * asked, how many times they have tried since, and when they last did. All three are null/zero on a
 * row an admin created by hand, because nobody ever asked — that row IS the approval.
 */
export type AccessRosterEntry = {
  id: string;
  /** Lower-cased on the server. The join key to `User.email`, and the only attacker-supplied value here. */
  email: string;
  /** Admin-typed only. Never a display name from Google — see the header. */
  fullName: string | null;
  status: AccessStatus;
  /** The tier an account is created at (or lifted to) on first sign-in. Null = the platform default. */
  admitRole: UserRole | null;
  joinedAt: string | null;
  requestedAt: string | null;
  attemptCount: number;
  lastAttemptAt: string | null;
  decidedAt: string | null;
  decidedById: string | null;
  /** Stamped the first time an admitted address actually got in. Null = admitted, never arrived. */
  firstSeenAt: string | null;
  notes: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  addedById: string | null;
};

export type AccessRosterPage = PageResult<AccessRosterEntry>;

/**
 * `GET /access/roster/pending-count` — THE NOTIFICATION, in the only channel either application has.
 *
 * There is no email sender and no push transport in this codebase, so "tell the admins somebody is
 * waiting" is a number on a screen they already open. It is its own endpoint precisely so a badge
 * can render without fetching a page of rows.
 *
 * `capReached` is not decoration. Past `capacity` the server stops recording new requests and tells
 * the person to contact an administrator directly — so a queue that has stopped growing means either
 * nobody is asking or the product has stopped listening, and an admin cannot tell which without this
 * flag.
 */
export type PendingAccessCount = {
  pending: number;
  capacity: number;
  capReached: boolean;
};

/* ────────────────────────────────────────────────────────────────────────────
 * Reads
 * ──────────────────────────────────────────────────────────────────────────── */

export async function listAccessRoster(params: {
  page?: number;
  pageSize?: number;
  search?: string;
  /** Omitted means EVERY status, including rejected and suspended — see the page's own note. */
  status?: AccessStatus;
}): Promise<AccessRosterPage> {
  return apiFetch<AccessRosterPage>(
    `/access/roster${buildQuery({
      page: params.page,
      pageSize: params.pageSize,
      search: params.search,
      status: params.status
    })}`
  );
}

export async function fetchPendingAccessCount(): Promise<PendingAccessCount> {
  return apiFetch<PendingAccessCount>("/access/roster/pending-count");
}

/* ────────────────────────────────────────────────────────────────────────────
 * Writes
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `POST /access/roster` — admit an address by hand, before it has an account.
 *
 * ACTIVE immediately, not pending: an admin typing an address into the box IS the approval, and
 * there is nobody else for the request to be routed to. A duplicate answers 409 naming the existing
 * row rather than overwriting it, because the row it would overwrite is usually the pending request
 * that records how long the person has been waiting.
 */
export async function addToAccessRoster(body: {
  email: string;
  fullName?: string | null;
  role?: UserRole | null;
  notes?: string | null;
}): Promise<AccessRosterEntry> {
  return apiFetch<AccessRosterEntry>("/access/roster", {
    method: "POST",
    body: JSON.stringify(body)
  });
}

/**
 * `POST /access/roster/{id}/decision` — the action the queue exists for.
 *
 * APPROVE also lifts an existing account to `role` when that is higher than the account already
 * holds (never lower). REJECT is final until an admin says otherwise: the person's next attempt
 * bumps their attempt count and they are told they were not approved, rather than quietly rejoining
 * the queue an admin has just cleared. Re-opening a rejection is this same call with APPROVE.
 */
export async function decideAccessRequest(
  id: string,
  body: { decision: "APPROVE" | "REJECT"; role?: UserRole | null; notes?: string | null }
): Promise<AccessRosterEntry> {
  return apiFetch<AccessRosterEntry>(`/access/roster/${id}/decision`, {
    method: "POST",
    body: JSON.stringify(body)
  });
}

/** `PATCH /access/roster/{id}` — correct the admin-typed columns. Cannot move `status`; that is the decision endpoint's job alone. */
export async function updateAccessEntry(
  id: string,
  body: { fullName?: string | null; role?: UserRole | null; notes?: string | null }
): Promise<AccessRosterEntry> {
  return apiFetch<AccessRosterEntry>(`/access/roster/${id}`, {
    method: "PATCH",
    body: JSON.stringify(body)
  });
}

/**
 * `DELETE /access/roster/{id}` — A SUSPENSION, answering 200 with the suspended row.
 *
 * Named for what it does rather than for its verb, exactly as `suspendDesignerRosterEntry` is. The
 * row survives, because it holds the joining date, the attempt history and the name of the admin who
 * admitted them — and because the gate reads a MISSING row as PENDING, so a real delete would put
 * the person straight back into the queue they were removed from.
 */
export async function suspendAccessEntry(id: string): Promise<AccessRosterEntry> {
  return apiFetch<AccessRosterEntry>(`/access/roster/${id}`, { method: "DELETE" });
}

/* ────────────────────────────────────────────────────────────────────────────
 * The two refusals, on the sign-in screen
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What a refused sign-in was refused FOR, when the server was able to say.
 *
 * `UNCLASSIFIED` is not a failure mode to design around — it is the honest state for a refusal this
 * build cannot categorise, and every caller must degrade to neutral chrome plus the server's own
 * sentence rather than guessing. It happens when the header is absent: an older API, or a proxy that
 * strips unknown headers, or a browser that was not allowed to read it because somebody removed
 * `X-Access-Status` from `expose_headers` in the backend's CORS configuration.
 */
export type AccessRefusalKind =
  | "PENDING"
  | "REJECTED"
  | "SUSPENDED"
  | "DESIGNER_SUSPENDED"
  | "QUEUE_FULL"
  | "BAD_CREDENTIAL"
  | "UNCLASSIFIED";

/** The header the API classifies a refused sign-in with. Spelled once; see `auth.ACCESS_STATUS_HEADER`. */
export const ACCESS_STATUS_HEADER = "x-access-status";

/**
 * Classify a failed sign-in from its status code and the server's header.
 *
 * NEVER FROM THE MESSAGE TEXT. The sentences are English written for the person reading them and
 * they will be reworded; a client that matched on prose would silently stop distinguishing "awaiting
 * approval" from "wrong password" the first time somebody fixed a comma, and the screen would go on
 * looking correct.
 *
 * 401 is `BAD_CREDENTIAL` and is deliberately NOT collapsed into the rest. That is the whole ruling
 * this feature was built under: a mistyped password reads as a mistyped password, and a person
 * waiting on an administrator is told so instead of being sent to reset a password that was never
 * wrong.
 */
export function accessRefusalKind(status: number, header: string | null | undefined): AccessRefusalKind {
  if (status === 401) return "BAD_CREDENTIAL";
  const flag = (header ?? "").trim().toUpperCase();
  switch (flag) {
    case "PENDING":
      return "PENDING";
    case "REJECTED":
      return "REJECTED";
    case "SUSPENDED":
      return "SUSPENDED";
    case "DESIGNER_SUSPENDED":
      return "DESIGNER_SUSPENDED";
    case "NOT_RECORDED":
      return "QUEUE_FULL";
    default:
      return "UNCLASSIFIED";
  }
}

/**
 * The heading and the follow-up the sign-in page draws AROUND the server's sentence — never instead
 * of it.
 *
 * The server's `detail` is the only text that knows why THIS attempt was refused, so it is always
 * rendered verbatim; these two lines exist because a sentence on its own, in a red box, above a
 * "Sign In" button, is read as a validation error and dismissed. `null` means "say nothing extra",
 * which is what an unclassified refusal gets: neutral chrome around a sentence we did not write.
 */
export function accessRefusalChrome(
  kind: AccessRefusalKind
): { heading: string; advice: string; tone: "waiting" | "refused" } | null {
  switch (kind) {
    case "PENDING":
      return {
        heading: "You are on the list, waiting for an administrator",
        advice:
          "Your password is not the problem and resetting it will not help. An administrator has been " +
          "shown your request and has to approve it before you can sign in. Trying again does not move " +
          "you up the queue — it is recorded as another attempt on the same request.",
        tone: "waiting"
      };
    case "REJECTED":
      return {
        heading: "Your request was reviewed and not approved",
        advice:
          "Signing in again will not reopen it — an administrator has to. Contact them directly if you " +
          "believe this is a mistake.",
        tone: "refused"
      };
    case "SUSPENDED":
      return {
        heading: "Your access to this application has been suspended",
        advice:
          "This is not a password problem. An administrator ended this address's access and only an " +
          "administrator can restore it.",
        tone: "refused"
      };
    case "DESIGNER_SUSPENDED":
      return {
        heading: "Your designer empanelment has ended",
        advice:
          "Your account itself is not barred — the institution's designer roster no longer carries this " +
          "address. Ask an administrator to restore you on the designer roster.",
        tone: "refused"
      };
    case "QUEUE_FULL":
      return {
        heading: "Requests to join are temporarily closed",
        advice:
          "Nothing about you was refused and nothing was recorded, so waiting will not help: an " +
          "administrator has to clear the approval queue before new requests can be accepted. Contact " +
          "one directly.",
        tone: "refused"
      };
    default:
      // BAD_CREDENTIAL and UNCLASSIFIED. The first is an ordinary field error and must stay one —
      // dressing "invalid email or password" in a panel would make every typo look like an account
      // problem, which is this feature's own mistake made backwards.
      return null;
  }
}
