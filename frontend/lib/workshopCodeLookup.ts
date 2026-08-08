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
