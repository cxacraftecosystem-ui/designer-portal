import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

import { signIn } from "./support/session";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

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
    data: { entries: [{ entityKey, data }] }
  });
  expect(res.ok(), `save ${stageKey}`).toBeTruthy();
  const body = await res.json();
  expect(body.errors ?? {}, `${stageKey} accepted every value the fixture sent`).toEqual({});
}

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

  await expect(
    page.getByText(/\d+ of \d+ stages complete; \d+ required fields? remains? in \d+ stages?\./),
    "the summary states stages and fields rather than only a percentage"
  ).toBeVisible({ timeout: 30_000 });

  const item = page.getByRole("link", { name: new RegExp(outstanding.label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")) });
  await expect(item, `"${outstanding.label}" is listed as outstanding`).toBeVisible();

  await expect(item, "the item deep-links to the field, not merely to the stage").toHaveAttribute(
    "href",
    `/design-workshops/${workshopId}/stages/${STAGE_KEY}?find=${ENTITY_KEY}.${outstanding.key}`
  );

  for (const field of answered) {
    await expect(
      page.getByRole("link", { name: new RegExp(`^${field.label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}`) }),
      `"${field.label}" is filled in and must not be listed as outstanding`
    ).toHaveCount(0);
  }

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

  await expect(page.getByText(/offline, so this is worked out from the copy saved/), "the page admits it is offline").toBeVisible();

  expect(blocked, "the workshop endpoints were actually blocked").toBeGreaterThan(0);
});

test("a workshop with nothing outstanding says so instead of showing an empty list", async ({ page }) => {
  test.setTimeout(240_000);

  const token = await apiToken(page);
  const headers = { Authorization: `Bearer ${token}` };
  const schema: Schema = await (await page.request.get(`${API}/api/design-workshops/schema`, { headers })).json();

  const workshopId = await createWorkshop(page.request, headers, `Readiness spec (complete) ${Date.now()}`);

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

  const detail = await (await page.request.get(`${API}/api/design-workshops/${workshopId}`, { headers })).json();
  const incomplete = Object.values(detail.completeness as Record<string, { title: string; isComplete: boolean; missing: string[] }>)
    .filter((score) => !score.isComplete)
    .map((score) => `${score.title}: ${score.missing.join(", ")}`);
  expect(incomplete, "the fixture is genuinely complete by the server's own scorer").toEqual([]);

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/readiness`);

  await expect(
    page.getByText(/Every required field across all \d+ stages is filled in\./),
    "a finished workshop is told so in words"
  ).toBeVisible({ timeout: 30_000 });
  await expect(
    page.getByText(/\d+ of \d+ stages complete; no required fields remain\./),
    "the summary agrees with it"
  ).toBeVisible();

  await expect(
    page.getByText(/required fields? outstanding/),
    "no outstanding-field list is drawn for a complete workshop"
  ).toHaveCount(0);
});
