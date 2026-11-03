export function legacyQuestionnaireTarget(params: URLSearchParams): string | null {
  const artisans = [...params.getAll("artisanId"), ...params.getAll("artisanIds").flatMap((v) => v.split(","))].filter(Boolean);
  if (params.get("new") === "1" || artisans.length) {
    const query = new URLSearchParams();
    for (const id of artisans) query.append("artisanId", id);
    const rest = query.toString();
    return `/questionnaire/sessions/new${rest ? `?${rest}` : ""}`;
  }
  if (params.has("edit")) return "/questionnaire";
  return null;
}

export function interviewTarget(id: string): string {
  return `/questionnaire/sessions/${encodeURIComponent(id)}`;
}

export function consolidatedTarget(artisanId: string | null | undefined): string {
  return artisanId ? `/artisans/${encodeURIComponent(artisanId)}?tab=questionnaire` : "/questionnaire";
}

export function answerFormTarget(formId: string): string {
  return `/questionnaire/sessions/new?questionnaireId=${encodeURIComponent(formId)}`;
}

export function inTab(
  row: { ownerId: string | null; isShared: boolean; designWorkshopId: string | null; deletedAt?: string | null },
  tab: "mine" | "shared" | "workshop" | "deleted",
  me: string | null
): boolean {
  if (tab === "deleted") return false;
  if (row.deletedAt) return false;
  if (tab === "mine") return row.ownerId === me;
  if (tab === "shared") return row.isShared;
  return Boolean(row.designWorkshopId) && row.ownerId !== me && !row.isShared;
}
