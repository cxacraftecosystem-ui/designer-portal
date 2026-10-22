/**
 * Old questionnaire links, after the retired global form (UNIFY 4.4): every one still lands somewhere
 * real. The pages call these; `e2e/questionnaire-dr-web-unit.spec.ts` holds them.
 */

/** Where an old `/questionnaire?...` link belongs now, or null to stay on the list. */
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

/** `/questionnaire/interviews/<id>`: an interview is a session (the shim keeps the id). */
export function interviewTarget(id: string): string {
  return `/questionnaire/sessions/${encodeURIComponent(id)}`;
}

/** `/questionnaire/consolidated/<artisanId>`: the artisan's Questionnaire tab (fixes DR audit M10). */
export function consolidatedTarget(artisanId: string | null | undefined): string {
  return artisanId ? `/artisans/${encodeURIComponent(artisanId)}?tab=questionnaire` : "/questionnaire";
}

/** `/questionnaires/<id>/answer`: a new session on this form (the start sheet, the form picked). */
export function answerFormTarget(formId: string): string {
  return `/questionnaire/sessions/new?questionnaireId=${encodeURIComponent(formId)}`;
}

/** Which library tab a form belongs on (UNIFY 4.2). A deleted form is only ever on Deleted. */
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
