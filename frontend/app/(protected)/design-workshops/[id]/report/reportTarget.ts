/**
 * The two things the report screens must resolve BEFORE they ask the server anything.
 *
 * WHY THIS IS A MODULE AND NOT TWO LINES INSIDE `page.tsx`. Both decisions are made twice — the
 * report page and its history view — and both were made wrongly in the same way: each screen used
 * the value nearest to hand (the route param, the workshop's header column) instead of the value
 * the SERVER will resolve. Two copies of a rule are two chances to fix only one of them, and the
 * history view is the copy nobody opens while testing the report.
 *
 * ── 1. THE ROUTE PARAM IS NOT ALWAYS A SERVER ID ─────────────────────────────────────────────
 *
 * A workshop created with no signal is banked in IndexedDB under a `dwlocal-…` id and the list page
 * navigates the browser to `/design-workshops/dwlocal-…`; the server id only exists once the sync
 * pass has created the record, and it is written back onto the draft as `remoteId`. `load_workshop_or_404`
 * is a `find_unique` on the primary key, so a `dwlocal-…` id is a hard 404 reading, verbatim, "Record
 * not found" — the one sentence that sends a designer hunting for lost fieldwork.
 *
 * The stage index, the readiness screen, the codes screen and `CustomSectionsEditor` all resolve it
 * the same way and this is deliberately a fourth copy of THAT rule rather than a third convention:
 * the draft's `remoteId` when there is one, the route param when the route param is itself a server
 * id, and otherwise NOTHING — which is a state with a date on it ("this workshop has not reached the
 * repository yet") and not a fault. The report screens carried the raw param into five server calls
 * and 404'd on every one of them, from the URL the application itself had navigated to.
 *
 * ⚠ ROUTES ARE NOT API CALLS. Every `<Link href={`/design-workshops/${id}/…`}>` must keep the ROUTE
 * param: the local id is what the browser is on, it is what the drafts are filed under, and
 * rewriting a link to the server id would drop the tab out of the offline copy it is reading.
 *
 * ── 2. THE HEADER COLUMN IS THE SERVER'S LAST RESORT, NOT ITS FIRST ──────────────────────────
 *
 * `resolve_template_id` reads three candidates in order: the one the request asked for, stage 20's
 * saved `templateId` answer, and only then `DesignWorkshop.templateId` — the column the create form
 * defaulted to `DCH_STANDARD` on the day the workshop was made. Nothing promotes stage 20's answer
 * into that column (`PROMOTED_COLUMNS` maps `workshopSetup.*` only), so the two genuinely disagree
 * the moment a designer answers 'Report template', which is a REQUIRED field the form insisted on.
 *
 * The report page seeded its dropdown from the column and then SENT that value as `requested` on
 * every preview and every download, which short-circuits the loop at its first rung and skips the
 * stage-20 answer entirely — so the web produced a different document from the handset and from a
 * bare `GET /report/preview`, and the dropdown named the template that had been replaced. Seeding in
 * the server's own order is what makes the screen agree with the file; sending nothing until the
 * designer touches the dropdown is what keeps it agreeing (see the `templateTouched` note on the
 * page — it is the same argument the download already applies to `themeAccent`).
 */

import type { DwDraft } from "@/lib/designWorkshopStore";
import { isLocalWorkshopId } from "@/lib/designWorkshopStore";

/**
 * The id the SERVER knows this workshop by, or null when it does not know it yet.
 *
 * Null is an ANSWER, not a failure: the workshop exists, entirely, in this browser, and it will be
 * created on the next connection. Callers must render that sentence rather than issue a request
 * whose 404 says the record does not exist.
 */
export function reportServerId(routeId: string, draft: Pick<DwDraft, "remoteId"> | null): string | null {
  return draft?.remoteId ?? (isLocalWorkshopId(routeId) ? null : routeId);
}

/**
 * The template the report screen must OPEN on, resolved the way `resolve_template_id` resolves it.
 *
 * Both arguments are already-read strings rather than the workshop detail, so this module needs no
 * import from `ReportSettingsPanel` (a component module, and a spec that imports it drags the whole
 * field-renderer tree in behind it). The caller reads stage 20's answer with `settingText`, which is
 * the page's one convention for reading a settings value, so there is no second copy of that read.
 *
 * The order is the whole content of this function: the stage answer FIRST, the header column only as
 * a fallback. Swap them and the web silently re-runs the defect this exists to close.
 */
export function reportTemplateId(stageAnswer: string, headerTemplateId: string): string {
  return stageAnswer || headerTemplateId;
}
