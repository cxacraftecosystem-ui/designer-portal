import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * "What still stops this workshop being submitted?" — asked once, answered with somewhere to click.
 *
 * WHAT THIS IS DEFENDING. Completeness was computed per stage and shown per stage, so the only way to
 * find the four Basic fields standing between a fortnight's work and a submission was to open all 22
 * stages and read 22 progress bars. The readiness screen assembles that same computation — it must
 * not re-derive it — into one ranked list. Two properties are what make it worth having, and both are
 * asserted here:
 *
 *   1. **It names the exact field and goes there.** A list that said "stage 2 is incomplete" would be
 *      the progress bar again with more words. The spec therefore does not merely check that a link
 *      exists: it checks the link is named after the missing field, that its href carries the
 *      `?find=entity.field` focus contract, and that following it lands on a stage page where that
 *      exact box is on screen and highlighted.
 *   2. **A finished workshop SAYS so.** The dangerous failure of a checklist is an empty list, which
 *      is indistinguishable from a list that failed to load — and this screen exists precisely so a
 *      designer does not have to go and check by hand.
 *
 * WHY BOTH FIXTURES ARE BUILT THROUGH THE API. The incomplete one is built by filling two of stage
 * 2's three required fields, so the assertion can be exact in both directions: "Purpose of the
 * workshop" must be listed and "Acknowledgement" must not. Asserting only the presence of an item
 * would pass against a page that listed every required field in the registry regardless of what is
 * filled in.
 *
 * WHY THE COMPLETE FIXTURE ONLY FILLS SINGLETONS. `stage_completeness` scores a COLLECTION entity
 * once per EXISTING row and contributes nothing when it is empty — an empty sketch list is a
 * legitimate state on day one, not an error. So a workshop with no collection rows and all 36
 * required singleton fields answered is genuinely complete, which is what makes this fixture 36
 * writes instead of several hundred.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/**
 * Stage 2 carries three required fields on one singleton entity and NO collections, so "two of three
 * filled" is a state the spec can create in one write and reason about exactly. It is also reachable
 * on a brand-new workshop without stage 1 having been touched.
 */
const STAGE_KEY = "INTRODUCTORY_ADMIN_DOCUMENTATION";
const ENTITY_KEY = "introduction";

type Field = {
  key: string;
  label: string;
  type: string;
  required?: boolean;
  deprecated?: boolean;
  options?: { value: string; label: string }[];
};
type Entity = { key: string; cardinality: string; fields: Field[] };
type Stage = { key: string; number: number; title: string; entities: Entity[] };
type Schema = { stages: Stage[] };

async function apiToken(page: Page): Promise<string> {
  const res = await page.request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } });
  expect(res.ok(), "sign-in for the API fixture").toBeTruthy();
  return (await res.json()).accessToken as string;
}

async function createWorkshop(request: APIRequestContext, headers: Record<string, string>, title: string): Promise<string> {
  const created = await request.post(`${API}/api/design-workshops`, { headers, data: { title } });
  expect(created.ok(), "create a design workshop").toBeTruthy();
  return (await created.json()).id as string;
}

async function saveStage(
  request: APIRequestContext,
  headers: Record<string, string>,
  workshopId: string,
  stageKey: string,
  entityKey: string,
  data: Record<string, unknown>
) {
  const res = await request.put(`${API}/api/design-workshops/${workshopId}/stages/${stageKey}`, {
    headers,
    // `replaceCollections` is irrelevant here (nothing but a singleton is ever sent) and `submit`
    // stays false: these fixtures are deliberately half-filled, and a submitting save would 422.
    data: { entries: [{ entityKey, data }] }
  });
  expect(res.ok(), `save ${stageKey}`).toBeTruthy();
  const body = await res.json();
  expect(body.errors ?? {}, `${stageKey} accepted every value the fixture sent`).toEqual({});
}

/**
 * A value the registry will accept for one field, chosen by declared type.
 *
 * RICH_TEXT takes a plain string on purpose — `coerce_value` normalises one into an unformatted
 * document, which is the documented path for a field promoted from LONG_TEXT — so the fixture does
 * not have to build the stored block JSON and drift from it.
 */
function sampleValue(field: Field): unknown {
  switch (field.type) {
    case "RICH_TEXT":
    case "TEXT":
    case "LONG_TEXT":
      return `Recorded by the readiness spec for "${field.label}".`;
    case "DATE":
      return "2026-01-05";
    case "TIME":
      return "10:30";
    case "BOOL":
      return true;
    case "INT":
      return 4;
    case "DECIMAL":
    case "PERCENT":
    case "MONEY":
      return 12;
    case "ENUM":
      return field.options?.[0]?.value ?? "";
    case "MULTI_ENUM":
    case "TAGS":
      return field.options?.length ? [field.options[0].value] : ["readiness-spec"];
    default:
      return `Recorded by the readiness spec.`;
  }
}

test("the readiness list names a missing required field and goes straight to it", async ({ page }) => {
  const token = await apiToken(page);
  const headers = { Authorization: `Bearer ${token}` };

  // The three field keys are read from the REGISTRY, never hardcoded: the registry is the single
  // declaration all three clients render from, and a spec that hardcoded labels would fail with a
  // mystery mismatch the day one was reworded rather than pointing at the rename.
  const schema: Schema = await (await page.request.get(`${API}/api/design-workshops/schema`, { headers })).json();
  const stage = schema.stages.find((candidate) => candidate.key === STAGE_KEY);
  expect(stage, `${STAGE_KEY} is in the registry`).toBeTruthy();
  const entity = stage!.entities.find((candidate) => candidate.key === ENTITY_KEY);
  expect(entity, `${ENTITY_KEY} is in ${STAGE_KEY}`).toBeTruthy();
  const required = entity!.fields.filter((field) => field.required && !field.deprecated);
  expect(required.length, "stage 2 has at least two required fields to tell apart").toBeGreaterThanOrEqual(2);
  expect(
    stage!.entities.filter((candidate) => candidate.cardinality !== "SINGLETON"),
    "stage 2 has no collections, so nothing but the singleton can contribute a missing field"
  ).toHaveLength(0);

  // The last required field is the one deliberately left empty; every other one is answered.
  const outstanding = required[required.length - 1];
  const answered = required.slice(0, -1);

  const workshopId = await createWorkshop(page.request, headers, `Readiness spec (incomplete) ${Date.now()}`);
  await saveStage(
    page.request,
    headers,
    workshopId,
    STAGE_KEY,
    ENTITY_KEY,
    Object.fromEntries(answered.map((field) => [field.key, sampleValue(field)]))
  );

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/readiness`);

  // The honest progress sentence, not a bare percentage. Its exact wording is the feature's promise:
  // counting the stages AND the fields inside them is what distinguishes "one date left" from "a
  // stage nobody has opened", which a single percentage cannot.
  await expect(
    page.getByText(/\d+ of \d+ stages complete; \d+ required fields? remains? in \d+ stages?\./),
    "the summary states stages and fields rather than only a percentage"
  ).toBeVisible({ timeout: 30_000 });

  // THE ITEM NAMES THE EXACT FIELD. Addressed by its accessible name so a row that merely said
  // "stage 2 is incomplete" could not satisfy it.
  const item = page.getByRole("link", { name: new RegExp(outstanding.label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")) });
  await expect(item, `"${outstanding.label}" is listed as outstanding`).toBeVisible();

  // …and the link carries the focus contract, not just the stage. This is the half that makes the
  // item actionable in ONE click; a plain stage link would drop the designer at the top of a form
  // with a dozen boxes and no indication which one it meant.
  await expect(item, "the item deep-links to the field, not merely to the stage").toHaveAttribute(
    "href",
    `/design-workshops/${workshopId}/stages/${STAGE_KEY}?find=${ENTITY_KEY}.${outstanding.key}`
  );

  // A FILLED FIELD IS NOT LISTED. Without this the spec would pass against a page that printed every
  // required field in the registry and never consulted the draft at all.
  for (const field of answered) {
    await expect(
      page.getByRole("link", { name: new RegExp(`^${field.label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}`) }),
      `"${field.label}" is filled in and must not be listed as outstanding`
    ).toHaveCount(0);
  }

  // Follow it, and prove the arrival: the anchor the stage form stamps on that exact box must be on
  // screen. Asserting only the URL would pass for a link that navigated somewhere the box does not
  // render — an unfilled Advanced-tier field behind a closed disclosure, for instance.
  await item.click();
  await page.waitForURL(new RegExp(`/stages/${STAGE_KEY}\\?find=`));
  await expect(
    page.locator(`[data-dw-field="${ENTITY_KEY}.${outstanding.key}"]`),
    "the stage opened with the named field's own wrapper on screen"
  ).toBeVisible({ timeout: 30_000 });
  await expect(
    page.getByRole("textbox", { name: outstanding.label }),
    `the box for "${outstanding.label}" is the one that was reached`
  ).toBeVisible();
});

test("the list is still answered with the workshop endpoints blocked", async ({ page }) => {
  /*
    THE PROMISE THIS SCREEN IS ACTUALLY FOR. A designer chasing a submission is not at a desk with a
    connection — they are in a courtyard on the last afternoon of the workshop, and the question
    "what is left?" only changes what happens next if it can be answered THERE. Every other
    assertion in this file would pass against a screen that quietly needed the server, so without
    this one the edge-first claim in `lib/submissionReadiness.ts` is a comment nobody checks.

    BLOCKED RATHER THAN `context.setOffline`, and only the workshop endpoints — the same choice
    `market-findings-panel.spec.ts` makes. Cutting the whole network would also cut the Next dev
    server that serves the document and the session check that keeps the browser signed in, and the
    test would then be measuring the auth shell rather than this list. What is proved here is the
    thing in doubt: that the ranking, the field names and the deep links come off the local draft.
  */
  const token = await apiToken(page);
  const headers = { Authorization: `Bearer ${token}` };

  const schema: Schema = await (await page.request.get(`${API}/api/design-workshops/schema`, { headers })).json();
  const stage = schema.stages.find((candidate) => candidate.key === STAGE_KEY);
  const entity = stage!.entities.find((candidate) => candidate.key === ENTITY_KEY);
  const required = entity!.fields.filter((field) => field.required && !field.deprecated);
  const outstanding = required[required.length - 1];
  const answered = required.slice(0, -1);

  const workshopId = await createWorkshop(page.request, headers, `Readiness spec (offline) ${Date.now()}`);
  await saveStage(
    page.request,
    headers,
    workshopId,
    STAGE_KEY,
    ENTITY_KEY,
    Object.fromEntries(answered.map((field) => [field.key, sampleValue(field)]))
  );

  await signIn(page);

  // ONE VISIT WITH THE NETWORK, which is the setup and also the ordinary order of work: it is what
  // puts the registry and this workshop's draft into IndexedDB. Asserted rather than assumed, so a
  // seeding failure reports itself here instead of masquerading below as an offline bug.
  await page.goto(`/design-workshops/${workshopId}/readiness`);
  const named = new RegExp(outstanding.label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
  await expect(page.getByRole("link", { name: named }), "the draft is in this browser").toBeVisible({
    timeout: 30_000
  });

  let blocked = 0;
  await page.route("**/api/design-workshops/**", async (route) => {
    blocked += 1;
    await route.abort("failed");
  });

  await page.reload();

  // The same item, named the same way, still deep-linking to the same box — computed from the copy
  // on disk. This is the assertion the feature lives or dies by.
  const item = page.getByRole("link", { name: named });
  await expect(item, `"${outstanding.label}" is still named with no server to ask`).toBeVisible({ timeout: 30_000 });
  await expect(item, "and is still one click from the box, not merely from the stage").toHaveAttribute(
    "href",
    `/design-workshops/${workshopId}/stages/${STAGE_KEY}?find=${ENTITY_KEY}.${outstanding.key}`
  );
  await expect(
    page.getByText(/\d+ of \d+ stages complete; \d+ required fields? remains? in \d+ stages?\./),
    "the progress sentence is arithmetic this device can do by itself"
  ).toBeVisible();

  // The screen SAYS it is working from the local copy. Silence would leave a designer unable to tell
  // a confident list from a stale one, which is the fabricated confidence this repository refuses.
  await expect(page.getByText(/There is no connection/), "the page admits it is offline").toBeVisible();

  // Proof the block was real: without this the test would pass just as well against a run where the
  // route pattern never matched and the server answered every request as usual.
  expect(blocked, "the workshop endpoints were actually blocked").toBeGreaterThan(0);
});

test("a workshop with nothing outstanding says so instead of showing an empty list", async ({ page }) => {
  // 22 stage writes plus two page loads; the default 90s is not enough on a cold dev server.
  test.setTimeout(240_000);

  const token = await apiToken(page);
  const headers = { Authorization: `Bearer ${token}` };
  const schema: Schema = await (await page.request.get(`${API}/api/design-workshops/schema`, { headers })).json();

  const workshopId = await createWorkshop(page.request, headers, `Readiness spec (complete) ${Date.now()}`);

  // Every required SINGLETON field, answered. Collections are left empty on purpose — see the header.
  for (const stage of schema.stages) {
    for (const entity of stage.entities) {
      if (entity.cardinality !== "SINGLETON") continue;
      const required = entity.fields.filter((field) => field.required && !field.deprecated);
      if (!required.length) continue;
      await saveStage(
        page.request,
        headers,
        workshopId,
        stage.key,
        entity.key,
        Object.fromEntries(required.map((field) => [field.key, sampleValue(field)]))
      );
    }
  }

  // THE FIXTURE CHECKS ITSELF AGAINST THE SERVER before the page is opened. If the fill routine
  // missed something, this fails naming the stage — rather than the page assertion below failing and
  // being read as a bug in the screen.
  const detail = await (await page.request.get(`${API}/api/design-workshops/${workshopId}`, { headers })).json();
  const incomplete = Object.values(detail.completeness as Record<string, { title: string; isComplete: boolean; missing: string[] }>)
    .filter((score) => !score.isComplete)
    .map((score) => `${score.title}: ${score.missing.join(", ")}`);
  expect(incomplete, "the fixture is genuinely complete by the server's own scorer").toEqual([]);

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/readiness`);

  // The complete case is a STATEMENT. This is the assertion the feature exists for: an empty list
  // with no sentence beside it is indistinguishable from a list that failed to load.
  await expect(
    page.getByText(/Every required field across all \d+ stages is filled in\./),
    "a finished workshop is told so in words"
  ).toBeVisible({ timeout: 30_000 });
  await expect(
    page.getByText(/\d+ of \d+ stages complete; no required fields remain\./),
    "the summary agrees with it"
  ).toBeVisible();

  // And nothing is listed as blocking — the sentence must not sit above a list that contradicts it.
  await expect(
    page.getByText(/required fields? outstanding/),
    "no outstanding-field list is drawn for a complete workshop"
  ).toHaveCount(0);
});
