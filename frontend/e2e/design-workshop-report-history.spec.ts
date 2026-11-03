import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

import { signIn } from "./support/session";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const CHANGED_STAGE = "TRADITIONAL_PROCESS_BASELINE";
const UNCHANGED_STAGE = "WORKSHOP_SETUP";

async function pickExport(page: Page, picker: "Earlier report" | "Later report", option: RegExp) {
  await page.getByTestId("report-diff").getByRole("button", { name: picker }).click();
  await page.getByRole("listbox", { name: new RegExp(picker) }).getByRole("option", { name: option }).click();
}

async function token(request: APIRequestContext): Promise<string> {
  const response = await request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } });
  expect(response.status(), "the API must sign the fixture in").toBe(200);
  return (await response.json()).accessToken;
}

async function seed(request: APIRequestContext): Promise<string> {
  const bearer = await token(request);
  const headers = { Authorization: `Bearer ${bearer}` };

  const created = await request.post(`${API}/api/design-workshops`, {
    headers,
    data: { title: `Report history spec ${Date.now()}` }
  });
  expect(created.status(), await created.text()).toBe(201);
  const id = (await created.json()).id as string;

  const setup = await request.put(`${API}/api/design-workshops/${id}/stages/${UNCHANGED_STAGE}`, {
    headers,
    data: { entries: [{ entityKey: "workshopSetup", data: { workshopTitle: "Bagru revival" } }] }
  });
  expect(setup.status(), await setup.text()).toBe(200);

  const first = await request.put(`${API}/api/design-workshops/${id}/stages/${CHANGED_STAGE}`, {
    headers,
    data: {
      entries: [
        { entityKey: "processStep", ordinal: 0, data: { name: "Scouring", description: "Wash the greige" } },
        { entityKey: "processStep", ordinal: 1, data: { name: "Dabu printing", description: "Mud resist" } }
      ],
      replaceCollections: true
    }
  });
  expect(first.status(), await first.text()).toBe(200);

  const generate = async () => {
    const response = await request.post(`${API}/api/design-workshops/${id}/report`, {
      headers,
      data: { formats: ["DOCX"], record: true }
    });
    expect(response.status(), "the API itself generates the report").toBe(200);
  };

  await generate();

  await new Promise((resolve) => setTimeout(resolve, 1_200));

  const stage = await request.get(`${API}/api/design-workshops/${id}/stages/${CHANGED_STAGE}`, { headers });
  expect(stage.status(), await stage.text()).toBe(200);
  const rows = (await stage.json()).collections.processStep as { _entryId: string; name: string }[];
  const byName = new Map(rows.map((row) => [row.name, row._entryId]));
  expect(byName.get("Scouring"), "the first save must have stored the row this test edits").toBeTruthy();
  expect(byName.get("Dabu printing"), "the first save must have stored both rows").toBeTruthy();

  const second = await request.put(`${API}/api/design-workshops/${id}/stages/${CHANGED_STAGE}`, {
    headers,
    data: {
      entries: [
        {
          entityKey: "processStep",
          entryId: byName.get("Scouring"),
          ordinal: 0,
          data: { name: "Scouring", description: "Wash the greige twice — the first pass left size in the cloth" }
        },
        {
          entityKey: "processStep",
          entryId: byName.get("Dabu printing"),
          ordinal: 1,
          data: { name: "Dabu printing", description: "Mud resist" }
        },
        { entityKey: "processStep", ordinal: 2, data: { name: "Indigo dyeing", description: "Three dips" } }
      ],
      replaceCollections: false
    }
  });
  expect(second.status(), await second.text()).toBe(200);

  await generate();

  const device = await request.post(`${API}/api/design-workshops/${id}/exports`, {
    headers,
    data: {
      format: "PDF",
      templateId: "dch-standard",
      fileName: "report-from-the-field.pdf",
      generatedAt: new Date().toISOString(),
      fileSizeBytes: 1_234_567,
      pageCount: 21,
      checksumSha256: "f".repeat(64)
    }
  });
  expect(device.status(), await device.text()).toBe(201);

  return id;
}

test.describe.configure({ mode: "serial" });

test.describe("design workshop report history", () => {
  let workshopId = "";

  test.beforeAll(async ({ request }) => {
    test.setTimeout(180_000);
    workshopId = await seed(request);
  });

  test("the history names every file, where it came from and its checksum", async ({ page }) => {
    const pageErrors: string[] = [];
    page.on("pageerror", (e) => pageErrors.push(String(e)));

    await signIn(page);
    await page.goto(`/design-workshops/${workshopId}/report/history`);

    const history = page.getByTestId("export-history");
    await expect(history).toBeVisible({ timeout: 30_000 });
    await expect(history.getByText("3 files recorded")).toBeVisible();

    await expect(history.getByText(/^Generation 1$/)).toBeVisible();
    await expect(history.getByText(/^Generation 3$/)).toBeVisible();

    await expect(history.getByText("Made on a phone, offline")).toHaveCount(1);
    await expect(history.getByText("Made online")).toHaveCount(2);

    await expect(history.getByText("Account no longer exists")).toHaveCount(0);

    const checksums = history.getByTestId("export-checksum");
    await expect(checksums).toHaveCount(3);
    expect((await checksums.first().innerText()).trim()).toMatch(/^[0-9a-f]{64}$/);

    expect(pageErrors, "the history screen threw").toEqual([]);
  });

  test("the diff separates the stage that changed from the stage that provably did not", async ({ page }) => {
    const pageErrors: string[] = [];
    page.on("pageerror", (e) => pageErrors.push(String(e)));

    await signIn(page);
    await page.goto(`/design-workshops/${workshopId}/report/history`);

    const diff = page.getByTestId("report-diff");
    await expect(diff).toBeVisible({ timeout: 30_000 });

    await pickExport(page, "Earlier report", /Generation 1/);
    await pickExport(page, "Later report", /Generation 2/);

    await expect(diff.getByTestId("diff-summary")).toContainText("Generation 1 → generation 2");
    await expect(diff.getByTestId("diff-summary")).toContainText("1 stage was written to");

    const touched = diff.getByTestId("touched-stages");
    await expect(touched).toContainText("Traditional Process, Tools & Raw Materials");
    await expect(touched).toContainText("2 rewritten");
    await expect(touched).toContainText("1 added");

    await expect(diff.getByText(/counts entries saved, not answers that differ/)).toBeVisible();

    await expect(diff.getByRole("heading", { name: "Identical in both files" })).toBeVisible();
    const untouched = diff.getByTestId("untouched-stages");
    await expect(untouched).toContainText("Workshop Setup & Cover Information");
    await expect(touched).not.toContainText("Workshop Setup & Cover Information");

    await expect(untouched).toContainText(/\d+% of its required fields, in both files and still today/);
    await expect(touched).not.toContainText("in both files and still today");

    await expect(diff.getByText(/Which field changed, or what it changed from/)).toBeVisible();

    expect(pageErrors, "the diff threw").toEqual([]);
  });

  test("comparing a file with itself is refused rather than answered", async ({ page }) => {
    await signIn(page);
    await page.goto(`/design-workshops/${workshopId}/report/history`);

    const diff = page.getByTestId("report-diff");
    await expect(diff).toBeVisible({ timeout: 30_000 });

    await pickExport(page, "Earlier report", /Generation 3/);

    await expect(diff.getByText("Choose two different files.")).toBeVisible();
    await expect(diff.getByTestId("diff-summary")).toHaveCount(0);
  });
});
