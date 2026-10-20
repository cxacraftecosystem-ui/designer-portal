/**
 * The Designer Repository's half of the top-bar scanner: the DPW decoders and the network calls that
 * `lib/scanDispatch.ts` (shared byte for byte with the Field Repository) is handed. Nothing here
 * decides an outcome or writes copy; it only answers the dispatcher's questions and runs the one
 * action the person pressed.
 */

import { ApiError, apiFetch, buildQuery } from "@/lib/api";
import { resolveArtisanCard } from "@/lib/artisanCardApi";
import {
  JOIN_CARD_REDEEM_OFFLINE_MESSAGE,
  joinCardFailure,
  joinOutcomeIsMembership,
  joinOutcomeSentence,
  redeemJoinCard
} from "@/lib/joinCards";
import { isUnreachable } from "@/lib/offline";
import { SCAN_CODECS } from "@/lib/scanCodecs";
import {
  SCAN_COPY,
  SCAN_HREF,
  SCAN_PORTAL,
  type CardAnswer,
  type RecordAnswer,
  type ScanAction,
  type ScanOutcome,
  type ScanServices
} from "@/lib/scanDispatch";
import { findWorkshopCodeRecord, requestDesignWorkshopAccess } from "@/lib/workshopCodeLookup";

const CARD_FAILED = "That card couldn't be looked up. Try again.";

async function resolveCard(raw: string): Promise<CardAnswer> {
  try {
    const answer = await resolveArtisanCard(raw);
    if (answer.status === "FOUND") {
      const a = answer.artisan;
      const detail = [a.craft, a.cluster ?? a.place].filter(Boolean).join(" · ");
      return { status: "found", artisanId: a.id, name: a.name, detail: detail || undefined };
    }
    if (answer.status === "REPLACED") return { status: "replaced", artisanId: answer.artisanId };
    return { status: "peer" };
  } catch (error) {
    if (isUnreachable(error)) return { status: "offline" };
    if (error instanceof ApiError) {
      const detail = (error.payload as { detail?: unknown } | undefined)?.detail;
      if (typeof detail === "string" && detail.trim()) return { status: "refused", message: detail };
    }
    return { status: "refused", message: CARD_FAILED };
  }
}

/**
 * The title of a design workshop the reader cannot open yet, for "Ask to join {title}"
 * (`GET /design-workshop-access/preview`, owner decision D34). Null when the server does not name it.
 */
async function scannedWorkshopTitle(code: string): Promise<string | null> {
  try {
    const answer = await apiFetch<{ title?: string }>(`/design-workshop-access/preview${buildQuery({ code })}`);
    return answer?.title?.trim() || null;
  } catch {
    return null;
  }
}

async function lookUpRecord(
  recordType: Parameters<ScanServices["lookUpRecord"]>[0],
  id: string,
  code: string
): Promise<RecordAnswer> {
  if (recordType === "prototype") return { status: "missing" };
  const answer = await findWorkshopCodeRecord({ recordType, id });
  if (answer.status === "missing" && recordType === "designWorkshop") {
    const title = await scannedWorkshopTitle(code);
    return title ? { status: "missing", title } : answer;
  }
  if (answer.status !== "found") return answer;
  return { status: "found", label: answer.hit.label, detail: answer.hit.detail, href: answer.hit.href, external: answer.hit.external };
}

/** The live services for this portal. */
export function scanServices(): ScanServices {
  return { portal: SCAN_PORTAL, codecs: SCAN_CODECS, resolveCard, lookUpRecord };
}

/** What pressing an outcome's button led to: a place to go, or a new outcome to show. */
export type ScanFollowUp = { navigate: string; external?: boolean } | { outcome: ScanOutcome };

/** Run the action the person pressed. Never throws: every failure is an outcome with a sentence. */
export async function runScanAction(action: ScanAction): Promise<ScanFollowUp> {
  switch (action.type) {
    case "open":
      return { navigate: action.href, external: action.external };
    case "add":
      return { navigate: action.href };
    case "redeem": {
      try {
        const answer = await redeemJoinCard(action.code);
        const workshopId = answer.workshopId || action.workshopId;
        const member = joinOutcomeIsMembership(answer.outcome);
        return {
          outcome: {
            kind: "OPEN",
            title: member ? SCAN_COPY.joined : joinOutcomeSentence(answer),
            detail: member ? joinOutcomeSentence(answer) : undefined,
            action: { type: "open", label: SCAN_COPY.openWorkshop, href: SCAN_HREF.designWorkshop(workshopId) }
          }
        };
      } catch (error) {
        const failure = joinCardFailure(error, JOIN_CARD_REDEEM_OFFLINE_MESSAGE);
        return { outcome: { kind: failure.kind === "offline" ? "OFFLINE" : "REFUSED", title: failure.message } };
      }
    }
    case "requestAccess": {
      try {
        const sentence = await requestDesignWorkshopAccess(action.workshopId, action.scannedCode);
        return { outcome: { kind: "JOIN_WORKSHOP", title: sentence } };
      } catch (error) {
        if (isUnreachable(error)) return { outcome: { kind: "OFFLINE", title: SCAN_COPY.offline } };
        const detail = error instanceof ApiError ? (error.payload as { detail?: unknown } | undefined)?.detail : undefined;
        return { outcome: { kind: "REFUSED", title: typeof detail === "string" && detail.trim() ? detail : SCAN_COPY.notFound } };
      }
    }
    case "joinSession":
      // A Field Repository session; the dispatcher refuses it here before an action exists.
      return { outcome: { kind: "REFUSED", title: SCAN_COPY.sessionElsewhere } };
  }
}
