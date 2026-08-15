import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * THE WORKSHOP DROPDOWN OVER THE INTERVIEWS TABLE THAT NARROWED NOTHING.
 *
 * `FunnelFilters` sits directly above the interviews list on the questionnaire screen, and picking a
 * workshop in it displays that workshop as the active filter. Underneath, `loadInterviews` sent
 * `page`, `pageSize`, `artisanId` and `search` — and no workshop, ever. So the table stayed the whole
 * repository, newest first, including other designers' interviews at other workshops, with the
 * pager's `total` counting them all. The endpoint had supported the parameter the entire time:
 * `list_interviews` declares `workshopId` and applies `where["workshopId"] = workshopId`
 * (backend/app/api/routes/questionnaire.py), and a backend test pins that it really narrows
 * (backend/tests/test_questionnaire_interview_filters.py).
 *
 * Nothing was hidden — the list is a SUPERSET — which is what made it survive so long: no record
 * ever looked deleted. The harm is that a reader takes the rows below a filter control as that
 * workshop's interviews and acts on rows that are not, and that the same dropdown genuinely does
 * narrow /products, /tools and /processes server-side, so the researcher has every reason to trust
 * it here.
 *
 * TWO SEPARATE ASSERTIONS, BECAUSE THERE WERE TWO SEPARATE HALVES AND EITHER ALONE IS STILL BROKEN.
 * The param was absent from the request; `funnel.workshopId` was absent from the refetch effect's
 * dependency array. With the param but not the dependency, picking a workshop with no artisan
 * selected does not re-run the fetch at all (`selectWorkshop` clears an already-empty `artisanId`,
 * and `setPage(1)` is a no-op on page 1) — the control stays dead. With the dependency but not the
 * param, the fetch re-runs and asks for everything, which is the original defect with a wasted round
 * trip attached.
 *
 * WHY THIS IS A SOURCE READ. The params object is built inline inside the page component, and this
 * repository has no React renderer in its devDependencies — Playwright is the whole of it — so
 * mounting the page is not available at all. `discarded-work-unit.spec.ts` and
 * `derived-fields-unit.spec.ts` read their subjects the same way and for the same reason. Both
 * assertions below fail against the file as it was, and neither proves the browser PAINTS a narrowed
 * table; the browser half belongs in a signed-in spec when one exists for this screen.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const PAGE = ["app", "(protected)", "questionnaire", "page.tsx"];

/** The text between two markers, so an assertion cannot drift into a neighbouring call. */
function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

test("the interviews request carries the funnel's workshop", () => {
  const source = read(...PAGE);
  const request = between(source, 'listResource<QuestionnaireInterview>("/questionnaire/interviews"', "});");

  expect(request, "the workshop pick must reach the server").toContain("workshopId: funnel.workshopId");
  // The artisan param is the control: it was always sent, so its presence proves the marker above
  // really did land on the interviews call and not on some other list on this screen.
  expect(request).toContain("artisanId: funnel.artisanId");
});

test("picking a workshop re-runs the interviews fetch", () => {
  const source = read(...PAGE);
  const effect = between(source, "loadInterviews();", "]);");

  expect(effect, "a param nothing re-fetches on is a param nobody sends").toContain(
    "funnel.workshopId"
  );
  expect(effect).toContain("funnel.artisanId");
});

test("the craft dropdown is still not sent, and the comment says why", () => {
  const source = read(...PAGE);
  const request = between(source, 'listResource<QuestionnaireInterview>("/questionnaire/interviews"', "});");

  // NOT a tidiness assertion. `list_interviews` has no craft parameter, and FastAPI ignores an
  // unknown query parameter silently — so sending `craftId` would produce a second control that
  // claims a narrowing the server never performs, which is the defect this file exists to close,
  // reintroduced by somebody "restoring parity". The craft dropdown's real job on this screen is to
  // cascade into the artisan picker, and that already works.
  expect(request).not.toContain("craftId");
});
