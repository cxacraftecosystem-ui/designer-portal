import type { DwDraft } from "@/lib/designWorkshopStore";
import { isLocalWorkshopId } from "@/lib/designWorkshopStore";

export function reportServerId(routeId: string, draft: Pick<DwDraft, "remoteId"> | null): string | null {
  return draft?.remoteId ?? (isLocalWorkshopId(routeId) ? null : routeId);
}

export function reportTemplateId(stageAnswer: string, headerTemplateId: string): string {
  return stageAnswer || headerTemplateId;
}
