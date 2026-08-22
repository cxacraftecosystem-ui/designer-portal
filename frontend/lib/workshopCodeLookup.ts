/**
 * What a scanned code opens: one lookup, for every record type the grammar can carry.
 *
 * ── WHY THIS IS ONE MODULE AND NOT ONE `switch` PER SCANNER ──────────────────────────────────
 *
 * There are two scanners already (the workshop's Cards & tags page and the repository-wide panel on
 * `/search`) and there will be more. Each of them has to answer the same three questions about a
 * decoded reference — which endpoint holds it, what to call it on screen, and where pressing "Open"
 * lands — and a second copy of those answers is how one scanner comes to open a tool at
 * `/tools/{id}/edit` while another drops it on the bare list, which is exactly the defect
 * `components/map/types.ts` documents having shipped in six places at once.
 *
 * ── A REFUSAL NEVER SAYS THE RECORD IS REAL ──────────────────────────────────────────────────
 *
 * Every detail endpoint here is `Depends(get_current_user)` + `require_record`, and `require_record`
 * raises **404** — never 403 — for a record the caller cannot have (`backend/app/services/records.py`).
 * That is deliberate: a 403 confirms the identifier names something, and confirming that from a code
 * printed on a card is a way to enumerate the repository one photograph at a time. Measured against
 * the running API on 2026-08-08: `GET /api/tools/cmzzzzzzz000000000000000z` and
 * `GET /api/crafts/cmzzzzzzz000000000000000z` both answer 401 with no token and neither route
 * distinguishes "absent" from "not yours" with one.
 *
 * So EVERY `ApiError` collapses to one sentence here. Do not add a `status === 404` branch, do not
 * report "you do not have access to this tool", and do not let a caller pass the status through: the
 * moment the scanner tells the two apart, the API's careful 404 has been undone one card at a time.
 * The one distinction that IS made is "the server never answered" (no signal), because waiting for
 * signal and reading the card again are completely different next actions.
 *
 * PURE OF UI. No React, no navigation — it returns an href and lets the caller decide whether to
 * follow it, so the same answer serves a page that navigates and a panel that only reports.
 */

import { ApiError, apiFetch } from "@/lib/api";
import { getDesignWorkshop } from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import type {
  Artisan,
  Craft,
  MediaFile,
  ProductDocumentation,
  QuestionnaireInterview,
  ToolDocumentation,
  Workshop
} from "@/lib/types";
import { unresolvedWorkshopCodeMessage, type WorkshopCodeRef, type WorkshopRecordType } from "@/lib/workshopCodes";

/** The process record as this lookup reads it — the two fields it needs, and no more. */
type ProcessSummary = { id: string; name: string; product?: { productName?: string | null } | null };

/** A record a scanned code resolved to, and where to open it. */
export type WorkshopCodeHit = {
  /** The line a human reads to confirm they scanned the thing they meant to. */
  label: string;
  /** One supporting line. Never an identity number — see `workshopCodes.ts`. */
  detail?: string;
  /** Where "Open" goes. */
  href: string;
  /** True when `href` leaves the app (a stored object), so it opens in a new tab. */
  external?: boolean;
};

export type WorkshopCodeLookup = { ok: true; hit: WorkshopCodeHit } | { ok: false; message: string };

/**
 * Where a record of each type is OPENED — the same destinations `/search` and the map use.
 *
 * Crafts, workshops and processes have no `/[id]/edit` route: they are edited inline on their list
 * page, so the id travels as `?edit=`, which `useEditDeepLink` reads on arrival. Linking to the bare
 * list instead discards the id and opens a blank CREATE form, which is the bug
 * `components/map/types.ts` records having shipped in six places.
 *
 * An interview and a media file genuinely have no per-record web route, so they land on their list —
 * stated here rather than papered over, because a caller that assumed every href is a detail view
 * would present a list as though it were the record.
 */
const OPEN_HREF: Record<WorkshopRecordType, (id: string) => string> = {
  artisan: (id) => `/artisans/${id}/edit`,
  craft: (id) => `/crafts?edit=${id}`,
  workshop: (id) => `/workshops?edit=${id}`,
  product: (id) => `/products/${id}/edit`,
  process: (id) => `/processes?edit=${id}`,
  tool: (id) => `/tools/${id}/edit`,
  questionnaire: () => "/questionnaire",
  media: () => "/media",
  // The workshop's own page, which is a real route (`/design-workshops/[id]`) and takes the id
  // directly — unlike `workshop` two lines up, which is a DIFFERENT record on a list page and travels
  // as `?edit=`. Landing here is what "joining" looks like once the grant exists; until it does the
  // page's own load refuses, which is the server's decision to make and not this table's.
  designWorkshop: (id) => `/design-workshops/${id}`,
  // A prototype is a row inside one design workshop's draft, not a repository record. It is resolved
  // against that workshop's own data by the Cards & tags page; this function is never reached for
  // one (see `lookUpWorkshopCode`), and the workshop list is the only honest fallback.
  prototype: () => "/design-workshops"
};

/**
 * Where "Open" goes for a reference — {@link OPEN_HREF} with the table kept private.
 *
 * Exported so that a caller which resolved a record WITHOUT this module (the workshop's Cards & tags
 * page answers artisans out of its own roster, offline, before it ever reaches the network) still
 * sends the reader to the same place. Two callers deciding independently where an artisan is opened
 * is how one of them comes to drop the id.
 */
export function workshopCodeOpenHref(ref: WorkshopCodeRef): string {
  return OPEN_HREF[ref.recordType](ref.id);
}

/** Join the parts of a supporting line, dropping the ones this record does not carry. */
function line(...parts: Array<string | null | undefined>): string | undefined {
  const kept = parts.map((part) => part?.trim()).filter(Boolean);
  return kept.length ? kept.join(" · ") : undefined;
}

/**
 * Fetch the record a reference names and describe it.
 *
 * Split out from {@link lookUpWorkshopCode} so that the error handling below is written ONCE for
 * every record type. A per-type try/catch is how one type comes to explain a dead network as a
 * missing record.
 */
async function fetchHit(ref: WorkshopCodeRef): Promise<WorkshopCodeHit> {
  const href = OPEN_HREF[ref.recordType](ref.id);
  switch (ref.recordType) {
    case "artisan": {
      const record = await apiFetch<Artisan>(`/artisans/${ref.id}`);
      return { label: record.name, detail: line(record.place, record.craft?.name), href };
    }
    case "craft": {
      const record = await apiFetch<Craft>(`/crafts/${ref.id}`);
      return { label: record.name, detail: line(record.category, record.place), href };
    }
    case "workshop": {
      const record = await apiFetch<Workshop>(`/workshops/${ref.id}`);
      return { label: record.title, detail: line(record.place), href };
    }
    case "product": {
      const record = await apiFetch<ProductDocumentation>(`/products/${ref.id}`);
      return { label: record.productName, detail: line(record.craftName, record.artisanName, record.place), href };
    }
    case "process": {
      const record = await apiFetch<ProcessSummary>(`/processes/${ref.id}`);
      return { label: record.name, detail: line(record.product?.productName), href };
    }
    case "tool": {
      const record = await apiFetch<ToolDocumentation>(`/tools/${ref.id}`);
      return { label: record.toolkitName, detail: line(record.craftName, record.artisanName, record.place), href };
    }
    case "questionnaire": {
      const record = await apiFetch<QuestionnaireInterview>(`/questionnaire/interviews/${ref.id}`);
      return {
        label: record.title,
        // The artisans a sitting covered are NOT named here. The interview's own title and place are
        // what confirm the right sitting was scanned, and a list of people is the sort of thing that
        // ends up on a screen held up in a room the people are standing in.
        detail: line(record.place, record.language),
        href
      };
    }
    case "media": {
      const record = await apiFetch<MediaFile>(`/media/${ref.id}`);
      return {
        label: record.caption?.trim() || record.originalFilename,
        detail: line(record.mediaType, record.mimeType),
        // `url` is gated server-side at the encoder, so a caller not entitled to the bytes gets no
        // link to them and lands on the media list instead — the entitlement is the API's to decide
        // and this must not guess at it.
        href: record.url ?? href,
        external: Boolean(record.url)
      };
    }
    case "designWorkshop": {
      /*
        THE ONE BRANCH THAT CALLS A HELPER RATHER THAN `apiFetch` DIRECTLY, because for this type one
        already exists: `lib/designWorkshops.ts::getDesignWorkshop`. The other eight have no such
        helper, which is why they are inline; writing the URL out a second time here would be a
        second place for `/design-workshops/{id}` to be wrong.

        IT IS THE HEAVIEST FETCH IN THIS SWITCH and that is worth knowing at the call site. The route
        assembles every stage's data, per-stage completeness, transcripts and a `resolve_display_names`
        pass — all of it discarded here to print two lines. Nothing leaks (the caller is entitled or
        gets a 404), but on the poor courtyard link this scanner is written for it is real weight. A
        summary-only read would need a backend route this group does not own; noted, not smuggled in.

        A 404 HERE IS THE JOIN CASE far more often than it is a missing workshop, and it is handled
        exactly like every other type's on purpose: the caller below turns it into
        `unresolvedWorkshopCodeMessage("designWorkshop")`, which names the next move. Do not add a
        status branch — see this file's header for why the API refuses to tell the two apart.
      */
      const record = await getDesignWorkshop(ref.id);
      return {
        label: record.title?.trim() || "Untitled design workshop",
        /*
          THE SAME WORDS THE LIST ROW SHOWS, so a designer confirming they scanned the right workshop
          reads it the way they already know it. The date goes through `formatDate` for that reason —
          the list renders `formatDate(workshop.startDate)` ("22 Aug 2026"), and a raw ISO slice here
          would have the scan and the row naming the same day two different ways. Guarded rather than
          passed straight in: `formatDate` answers "-" for an absent date, and a bare hyphen would
          survive `line`'s filter and be printed as though it meant something.

          The designer's NAME is deliberately not in this line although the summary carries it. This
          is read off a screen held up in a room, and the workshop is identified perfectly well by
          where and when it ran. `venue ?? district` rather than the list's "district, state": one
          place-word is what fits on a scan result, and the venue is the more specific of the two.
        */
        detail: line(
          record.craftName,
          record.venue ?? record.district,
          record.startDate ? formatDate(record.startDate) : undefined
        ),
        href
      };
    }
    case "prototype":
      // Unreachable: `lookUpWorkshopCode` answers prototypes before it gets here. Written out rather
      // than left to a `default`, so that adding a record type to the grammar fails this switch's
      // exhaustiveness check instead of silently falling into somebody else's branch.
      throw new Error("A prototype tag is resolved against its own design workshop, not the repository.");
  }
}

/**
 * Resolve a decoded reference against the repository.
 *
 * A RESULT AND NEVER A THROW for anything the API said, because every caller is a scanner panel that
 * must keep working after a refusal — the next card is usually the one that matters.
 */
export async function lookUpWorkshopCode(ref: WorkshopCodeRef): Promise<WorkshopCodeLookup> {
  if (ref.recordType === "prototype") {
    // Prototypes live in one design workshop's draft — often only on the device that made them, in a
    // village with no signal — so there is no endpoint to ask. Saying which screen DOES resolve it is
    // the useful answer; "no prototype matches" would be a claim this scanner cannot support.
    return {
      ok: false,
      message:
        "That is a prototype tag. A prototype belongs to one design workshop and is looked up inside it — open that workshop and use its Cards & tags screen."
    };
  }

  try {
    return { ok: true, hit: await fetchHit(ref) };
  } catch (error) {
    if (isUnreachable(error)) {
      return {
        ok: false,
        message:
          "There is no connection, so the repository could not be asked about that code. Try again when there is signal — the code itself checked out, so the card is fine."
      };
    }
    // ONE sentence for "no such record" and "not yours". See the file header: the API answers 404 for
    // both on purpose, and branching on the status here would hand back the fact it withholds.
    if (error instanceof ApiError) return { ok: false, message: unresolvedWorkshopCodeMessage(ref.recordType) };
    throw error;
  }
}

/* ────────────────────────────────────────────────────────────────────────────
 * Joining a design workshop by code
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * WHAT A SCANNED DESIGN-WORKSHOP CODE ACTUALLY MEANS FOR THE DEVICE THAT SCANNED IT.
 *
 * ── THE ASK, AND THE PART OF IT THAT CANNOT BE BUILT ────────────────────────────────────────────
 *
 * The requirement is that one person creates a workshop and the others scan a code to join THE SAME
 * ONE, so the group does not diverge into rival copies. The second half of that — not diverging — is
 * fully deliverable and is delivered, in {@link DEVICE_LOCAL_ID_PREFIX}'s refusal over in
 * `workshopCodes.ts`. The first half, "join", is where the honest answer differs from the obvious
 * one, and this type exists so that no screen can quietly paper over the difference.
 *
 * **A SCAN CANNOT GRANT ACCESS, AND NOT ONLY BECAUSE OF THE NETWORK.** Access to a design workshop is
 * a `DesignWorkshopViewer` row, and the only route that writes one is
 * `PUT /design-workshops/{workshop_id}/viewers` — `Depends(require_admin)`, as are the two GETs
 * beside it in `backend/app/api/routes/design_workshop_viewers.py`. A DESIGNER therefore cannot put
 * themselves onto a workshop with a perfect connection, a valid code and every good intention. There
 * is no self-service join to defer to a sync queue, because there is no self-service join at all.
 *
 * So an offline scan cannot record an "intent that syncs later" into an access request, because
 * there is no endpoint for one to sync TO. Inventing a queue that drains into a route that does not
 * exist would produce the worst outcome available here: a designer told their request is pending,
 * waiting on an admin who will never be shown anything. Until such a route exists, the truthful
 * next move is the one a room can actually perform — ask the admin who created the workshop — and
 * every state below is worded to say exactly that and nothing warmer.
 *
 * ── WHAT THE SCAN IS GENUINELY WORTH ANYWAY ─────────────────────────────────────────────────────
 *
 * It removes the typing, which is the whole of what the code grammar was built for. The colleague
 * does not read a 25-character cuid aloud across a courtyard; the id arrives exactly right, and the
 * admin is handed a code that names one workshop unambiguously instead of a title that three
 * workshops in the cluster share. And when the grant is already there — the ordinary case for the
 * second designer on a workshop an admin set up that morning — the scan opens it directly.
 *
 * PURE, and separate from {@link lookUpWorkshopCode} for that reason: the caller does the fetch and
 * the IndexedDB read, and hands the two facts here. That keeps the decision testable with no
 * network and no browser, which matters because the state this most needs to get right — no signal
 * — is the one hardest to stand up in a test.
 */
export type DesignWorkshopScanState =
  /**
   * The workshop is already on this device: a draft points at this server id, so the designer is on
   * it and can open it right now, offline included. Scanning was a shortcut, not a join.
   */
  | "ALREADY_ON_THIS_DEVICE"
  /**
   * The server answered with the workshop, so the grant already exists. This is as close to "joined"
   * as any scan gets, and it is still not a join — nothing was granted, something was confirmed.
   */
  | "ACCESS_CONFIRMED"
  /**
   * The server refused it. The code is well formed and checked out, so the honest reading is "you
   * are not on this workshop" — and the fix is an admin, not a retry.
   */
  | "ACCESS_NEEDED"
  /**
   * No signal, and no draft for it here. THE STATE THIS TYPE EXISTS FOR: the device cannot tell
   * whether the scanner is on this workshop or not, and must not guess in either direction. Saying
   * "joined" here is the lie that ends in two rival copies; saying "no access" would send a designer
   * to bother an admin about a grant they may already hold.
   */
  | "UNKNOWN_NO_SIGNAL";

/** The facts a caller gathers before asking. Both are things only the caller can know. */
export type DesignWorkshopScanFacts = {
  /**
   * Does this device already hold a draft whose `remoteId` is the scanned id?
   * Read from `lib/designWorkshopStore.ts`; deliberately passed in rather than read here, so this
   * module stays free of IndexedDB.
   */
  onThisDevice: boolean;
  /**
   * What the network said. "offline" means it was never asked — `isUnreachable`, not a refusal —
   * and those two must never be collapsed, because one of them is about to change on its own and
   * the other never will.
   */
  resolution: "resolved" | "refused" | "offline";
};

/**
 * Which state a scan is in. Ordered so the cheapest and most certain fact wins.
 *
 * `onThisDevice` is checked FIRST and beats every network answer, including a refusal. A designer
 * halfway through a fortnight on a workshop whose grant an admin has since changed still holds
 * twenty-two stages of their own work on this device; answering "you have no access" over the top of
 * that would be false about the thing they can see on their screen. What the server thinks about the
 * grant is the server's business at sync time, where it is enforced, and it is enforced there
 * whatever this function says.
 */
export function designWorkshopScanState(facts: DesignWorkshopScanFacts): DesignWorkshopScanState {
  if (facts.onThisDevice) return "ALREADY_ON_THIS_DEVICE";
  if (facts.resolution === "resolved") return "ACCESS_CONFIRMED";
  if (facts.resolution === "refused") return "ACCESS_NEEDED";
  return "UNKNOWN_NO_SIGNAL";
}

/**
 * What each state says on screen, and whether an "Open" control belongs beside it.
 *
 * ONE TABLE, because the four sentences are only correct as a set: each is written to be
 * unmistakable against the other three, and a screen that composed its own wording for one of them
 * would be the screen that says "joined" for `UNKNOWN_NO_SIGNAL`. `canOpen` is part of the same
 * decision — offering "Open" for a workshop this device cannot load is how a designer concludes the
 * app is broken rather than that they are not on the workshop yet.
 */
export const DESIGN_WORKSHOP_SCAN_COPY: Record<
  DesignWorkshopScanState,
  { headline: string; detail: string; canOpen: boolean }
> = {
  ALREADY_ON_THIS_DEVICE: {
    headline: "You are on this workshop",
    detail:
      "It is already on this device, so you can open it and keep working whether or not there is any signal.",
    canOpen: true
  },
  ACCESS_CONFIRMED: {
    headline: "You are on this workshop",
    detail: "Opening it will bring it onto this device so it stays available when the signal goes.",
    canOpen: true
  },
  ACCESS_NEEDED: {
    // NOT "request sent" and NOT "request pending". Nothing was sent; there is no route to send it
    // to. See this type's header.
    headline: "You have not been added to this workshop yet",
    detail:
      "Only an admin can add somebody to a workshop. Show them this code — it names the right one exactly — and ask to be put on it. Nothing you have recorded on this device is affected.",
    canOpen: false
  },
  UNKNOWN_NO_SIGNAL: {
    headline: "Scanned — but this device cannot check it yet",
    detail:
      "There is no connection, so whether you are on this workshop is not something this device can answer. The code is valid and has been read correctly. Try again where there is signal.",
    canOpen: false
  }
};
