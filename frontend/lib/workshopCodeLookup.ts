import { ApiError, apiFetch } from "@/lib/api";
import { getDesignWorkshop } from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { RECORD_VIEW_HREF } from "@/lib/recordView";
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
import { workshopDisplayTitle } from "@/lib/workshopTitle";

type ProcessSummary = { id: string; name: string; product?: { productName?: string | null } | null };

export type WorkshopCodeHit = {
  label: string;
  detail?: string;
  href: string;
  external?: boolean;
};

export type WorkshopCodeLookup = { ok: true; hit: WorkshopCodeHit } | { ok: false; message: string };

const OPEN_HREF: Record<WorkshopRecordType, (id: string) => string> = {
  artisan: RECORD_VIEW_HREF.artisan,
  craft: RECORD_VIEW_HREF.craft,
  workshop: RECORD_VIEW_HREF.workshop,
  product: RECORD_VIEW_HREF.product,
  process: RECORD_VIEW_HREF.process,
  tool: RECORD_VIEW_HREF.tool,
  questionnaire: RECORD_VIEW_HREF.interview,
  media: RECORD_VIEW_HREF.media,
  designWorkshop: (id) => `/design-workshops/${id}`,
  prototype: () => "/design-workshops"
};

export function workshopCodeOpenHref(ref: WorkshopCodeRef): string {
  return OPEN_HREF[ref.recordType](ref.id);
}

function line(...parts: Array<string | null | undefined>): string | undefined {
  const kept = parts.map((part) => part?.trim()).filter(Boolean);
  return kept.length ? kept.join(" · ") : undefined;
}

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
        detail: line(record.place, record.language),
        href
      };
    }
    case "media": {
      const record = await apiFetch<MediaFile>(`/media/${ref.id}`);
      return {
        label: record.caption?.trim() || record.originalFilename,
        detail: line(record.mediaType, record.mimeType),
        href: record.url ?? href,
        external: Boolean(record.url)
      };
    }
    case "designWorkshop": {
      const record = await getDesignWorkshop(ref.id);
      return {
        label: workshopDisplayTitle(record),
        detail: line(
          record.craftName,
          record.venue ?? record.district,
          record.startDate ? formatDate(record.startDate) : undefined
        ),
        href
      };
    }
    case "prototype":
      throw new Error("A prototype tag is resolved against its own design workshop, not the repository.");
  }
}

export async function lookUpWorkshopCode(ref: WorkshopCodeRef): Promise<WorkshopCodeLookup> {
  if (ref.recordType === "prototype") {
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
          "There is no connection, so that code couldn't be looked up. The code itself is valid, so the card is fine. Try again when you have signal."
      };
    }
    if (isAnswerAboutTheCode(error)) return { ok: false, message: unresolvedWorkshopCodeMessage(ref.recordType) };
    if (error instanceof ApiError) return { ok: false, message: "Couldn't look that up. Try again." };
    throw error;
  }
}

export async function findWorkshopCodeRecord(
  ref: WorkshopCodeRef
): Promise<{ status: "found"; hit: WorkshopCodeHit } | { status: "missing" } | { status: "offline" }> {
  if (ref.recordType === "prototype") return { status: "missing" };
  try {
    return { status: "found", hit: await fetchHit(ref) };
  } catch (error) {
    if (isUnreachable(error)) return { status: "offline" };
    if (isAnswerAboutTheCode(error)) return { status: "missing" };
    throw error;
  }
}

function isAnswerAboutTheCode(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 404 || error.status === 403);
}

export async function requestDesignWorkshopAccess(
  workshopId: string,
  scannedCode: string,
  scannedAt: Date = new Date()
): Promise<string> {
  const answer = await apiFetch<{ received?: boolean; detail?: string }>("/design-workshop-access/requests", {
    method: "POST",
    body: JSON.stringify({ workshopId, scannedCode, scannedAt: scannedAt.toISOString() })
  });
  return answer?.detail?.trim() || "Asked. An administrator can now see your request.";
}

export type DesignWorkshopScanState =
  | "ALREADY_ON_THIS_DEVICE"
  | "ACCESS_CONFIRMED"
  | "ACCESS_NEEDED"
  | "UNKNOWN_NO_SIGNAL";

export type DesignWorkshopScanFacts = {
  onThisDevice: boolean;
  resolution: "resolved" | "refused" | "offline";
};

export function designWorkshopScanState(facts: DesignWorkshopScanFacts): DesignWorkshopScanState {
  if (facts.onThisDevice) return "ALREADY_ON_THIS_DEVICE";
  if (facts.resolution === "resolved") return "ACCESS_CONFIRMED";
  if (facts.resolution === "refused") return "ACCESS_NEEDED";
  return "UNKNOWN_NO_SIGNAL";
}

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
    headline: "You have not been added to this workshop yet",
    detail:
      "Show this code to an admin and ask to be added. Your work here is kept.",
    canOpen: false
  },
  UNKNOWN_NO_SIGNAL: {
    headline: "Scanned — checking it needs a connection",
    detail:
      "There is no connection, so it can't be checked whether you are on this workshop. The code is valid and was read correctly. Try again when you have signal.",
    canOpen: false
  }
};
