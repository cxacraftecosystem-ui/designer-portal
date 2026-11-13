import type { DwCreateBody, DwSaveBody } from "@/lib/designWorkshops";
import { writeBody } from "@/lib/writeBody";

export const MAX_NAMED_DESIGNERS = 100;

export function namedDesignerTeam({ chosen, lead }: { chosen: readonly string[]; lead: string }): {
  lead: string;
  team: string[];
} {
  const ticked: string[] = [];
  const seen = new Set<string>();
  for (const raw of chosen) {
    const id = (raw ?? "").trim();
    if (!id || seen.has(id)) continue;
    seen.add(id);
    ticked.push(id);
  }
  const preferred = (lead ?? "").trim();
  if (ticked.length === 0) return preferred ? { lead: preferred, team: [preferred] } : { lead: "", team: [] };
  const resolved = preferred && seen.has(preferred) ? preferred : ticked[0];
  return { lead: resolved, team: [resolved, ...ticked.filter((id) => id !== resolved)] };
}

export function designerCreateFields({ chosen, lead }: { chosen: readonly string[]; lead: string }): {
  designerUserId?: string;
  designerUserIds?: string[];
} {
  const resolved = namedDesignerTeam({ chosen, lead });
  if (resolved.team.length === 0) return {};
  if (resolved.team.length === 1) return { designerUserId: resolved.lead };
  return { designerUserId: resolved.lead, designerUserIds: resolved.team };
}

export function designWorkshopCreateBody(body: DwCreateBody) {
  const { designerUserId, designerUserIds, ...rest } = body;
  return writeBody(
    { ...rest, ...designerCreateFields({ chosen: designerUserIds ?? [], lead: designerUserId ?? "" }) },
    "POST"
  );
}

export function stageSaveBody(body: DwSaveBody) {
  return { replaceCollections: false, submit: false, ...body };
}
