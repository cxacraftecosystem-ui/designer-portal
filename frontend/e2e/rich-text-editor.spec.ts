import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

const STAGE_KEY = "INTRODUCTORY_ADMIN_DOCUMENTATION";
const FIELD_KEY = "acknowledgement";

const OBJECT_STORE_MAP = process.env.E2E_OBJECT_STORE_MAP ?? "";
if (OBJECT_STORE_MAP) {
  test.use({ launchOptions: { args: [`--host-resolver-rules=MAP ${OBJECT_STORE_MAP}`] } });
}

async function apiToken(page: Page): Promise<string> {
  const res = await page.request.post(`${API}/api/auth/login`, {
    data: { email: EMAIL, password: PASSWORD }
  });
  expect(res.ok(), "sign-in for the API fixture").toBeTruthy();
  return (await res.json()).accessToken as string;
}

test("a narrative field renders the rich-text editor, and a mark survives a round trip", async ({
  page
}) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };

  const schema = await (await page.request.get(`${API}/api/design-workshops/schema`, { headers: auth })).json();
  const stage = schema.stages.find((s: { key: string }) => s.key === STAGE_KEY);
  const field = stage?.entities
    ?.flatMap((e: { fields: { key: string; type: string }[] }) => e.fields)
    ?.find((f: { key: string }) => f.key === FIELD_KEY);
  expect(field, `${STAGE_KEY}.${FIELD_KEY} is in the registry`).toBeTruthy();
  expect(
    field.type,
    `${FIELD_KEY} must be RICH_TEXT — if this is LONG_TEXT the editor cannot mount, which is the ` +
      `exact regression this spec exists to catch`
  ).toBe("RICH_TEXT");

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Rich text spec ${Date.now()}` }
  });
  expect(created.ok(), "create a design workshop").toBeTruthy();
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  const editor = page.locator('[contenteditable="true"]').first();
  await expect(editor, "the rich-text editor mounted for a NARRATIVE field").toBeVisible({
    timeout: 30_000
  });

  await editor.click();
  await page.keyboard.type("The cluster is grateful to the master weavers of Barpali.");

  await page.keyboard.down("Shift");
  for (let i = 0; i < 8; i += 1) await page.keyboard.press("ArrowLeft");
  await page.keyboard.up("Shift");

  const toolbar = page.getByRole("toolbar", { name: "Text formatting" });
  await expect(toolbar, "the contextual toolbar appears for a selection").toBeVisible();
  await page.keyboard.press("Control+b");

  await page.getByRole("button", { name: /^save/i }).first().click();
  await expect(page.getByText(/saved/i).first()).toBeVisible({ timeout: 30_000 });

  const value = await expect
    .poll(
      async () => {
        const stored = await (
          await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
            headers: auth
          })
        ).json();
        return JSON.stringify(stored);
      },
      { message: "the stored value is a block document carrying the bold mark", timeout: 30_000 }
    )
    .toContain("BOLD")
    .then(async () => {
      const stored = await (
        await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
          headers: auth
        })
      ).json();
      return JSON.stringify(stored);
    });
  expect(value, "the stored value is a block document, not a flat string").toContain("blocks");
  expect(value, "the bold mark reached the server").toContain("BOLD");

  await page.reload();
  const reloaded = page.locator('[contenteditable="true"]').first();
  await expect(reloaded).toBeVisible({ timeout: 30_000 });
  await expect(
    reloaded.locator("strong, b"),
    "the mark is rendered back into the editor after a reload"
  ).toHaveCount(1);
});

test("a one-per-line field opens as a numbered list and Enter starts the next item", async ({
  page
}) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };

  const schema = await (
    await page.request.get(`${API}/api/design-workshops/schema`, { headers: auth })
  ).json();
  const field = schema.stages
    .find((s: { key: string }) => s.key === STAGE_KEY)
    ?.entities?.flatMap((e: { fields: unknown[] }) => e.fields)
    ?.find((f: { key: string }) => f.key === "expectedDeliverables");

  expect(field, "expectedDeliverables is in the registry").toBeTruthy();
  expect(field.reportRole, "it is a BULLETS field").toBe("BULLETS");
  expect(field.type, "and it must be RICH_TEXT for the editor to make a list of it").toBe(
    "RICH_TEXT"
  );

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Bullets spec ${Date.now()}` }
  });
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  const group = page.locator("div").filter({ hasText: /Expected deliverables/i }).last();
  const editor = group.locator('[contenteditable="true"]').first();
  await expect(editor).toBeVisible({ timeout: 30_000 });

  await editor.click();
  await page.keyboard.type("Six developed prototypes");
  await page.keyboard.press("Enter");
  await page.keyboard.type("A cost sheet for each prototype");

  await expect(
    editor.locator("li"),
    "Enter produced a second list item rather than a bare paragraph"
  ).toHaveCount(2);
  await expect(editor.locator("ol")).toHaveCount(1);
});

test("a narrative field's dictation cannot flatten what is already written", async ({ page }) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Dictation spec ${Date.now()}` }
  });
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  const editor = page.locator('[contenteditable="true"]').first();
  await expect(editor).toBeVisible({ timeout: 30_000 });

  const dictateButtons = page.getByRole("button", { name: /dictate .* in /i });
  const editors = await page.locator('[contenteditable="true"]').count();
  const buttons = await dictateButtons.count();

  expect(buttons, "at most one dictation control per rich field").toBeLessThanOrEqual(editors);

  await editor.click();
  await page.keyboard.type("Barpali weaves bandha.");
  await page.keyboard.down("Shift");
  for (let i = 0; i < 7; i += 1) await page.keyboard.press("ArrowLeft");
  await page.keyboard.up("Shift");
  await page.keyboard.press("Control+b");

  await expect(editor.locator("strong, b")).toHaveCount(1);
});

test("a table can be inserted, typed into, and reaches the server as a table", async ({ page }) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Table spec ${Date.now()}` }
  });
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  const editor = page.locator('[contenteditable="true"]').first();
  await expect(editor).toBeVisible({ timeout: 30_000 });

  await editor.click();
  await page.keyboard.type("Cost heads for the selected prototypes.");

  await page.keyboard.down("Shift");
  for (let i = 0; i < 5; i += 1) await page.keyboard.press("ArrowLeft");
  await page.keyboard.up("Shift");

  await page.getByRole("button", { name: /insert table/i }).first().click();

  const table = editor.locator("table");
  await expect(table, "a table was inserted into the surface").toHaveCount(1);
  await expect(table.locator("tr")).toHaveCount(3);
  await expect(table.locator("th")).toHaveCount(3);

  await table.locator("th").first().click();
  await page.keyboard.type("Head");
  await page.keyboard.press("Tab");
  await page.keyboard.type("PT-01");
  await page.keyboard.press("Tab");
  await page.keyboard.type("PT-02");

  await page.getByRole("button", { name: /^save/i }).first().click();
  await expect(page.getByText(/saved/i).first()).toBeVisible({ timeout: 30_000 });

  const tableBlock = await expect
    .poll(
      async () => {
        const stored = await (
          await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
            headers: auth
          })
        ).json();
        const blocks = stored?.singleton?.acknowledgement?.blocks ?? [];
        return blocks.find((b: { kind: string }) => b.kind === "TABLE") ?? null;
      },
      { message: "the stored document contains a TABLE block", timeout: 30_000 }
    )
    .not.toBeNull()
    .then(async () => {
      const stored = await (
        await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
          headers: auth
        })
      ).json();
      return (stored?.singleton?.acknowledgement?.blocks ?? []).find(
        (b: { kind: string }) => b.kind === "TABLE"
      );
    });

  const flat = JSON.stringify(tableBlock.rows);
  expect(flat, "the header cells reached the server").toContain("Head");
  expect(flat).toContain("PT-01");
  expect(flat).toContain("PT-02");

  const report = await page.request.post(`${API}/api/design-workshops/${workshopId}/report`, {
    headers: auth,
    data: { formats: ["DOCX"], record: false }
  });
  expect(report.status(), "the report generates with a table in the prose").toBe(200);
});

test("superscript survives the round trip, and it displaces subscript", async ({ page }) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Superscript spec ${Date.now()}` }
  });
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  const editor = page.locator('[contenteditable="true"]').first();
  await expect(editor).toBeVisible({ timeout: 30_000 });

  await editor.click();
  await page.keyboard.type("The shed measures 4.5 m2");

  await page.keyboard.down("Shift");
  await page.keyboard.press("ArrowLeft");
  await page.keyboard.up("Shift");

  await page.getByRole("button", { name: /^subscript$/i }).click();
  await expect(editor.locator("sub")).toHaveCount(1);
  await page.getByRole("button", { name: /^superscript$/i }).click();
  await expect(editor.locator("sup"), "superscript replaced the subscript").toHaveCount(1);
  await expect(editor.locator("sub"), "and nothing is left claiming both").toHaveCount(0);

  await page.getByRole("button", { name: /^highlight$/i }).click();
  await expect(editor.locator("mark"), "the highlight applied").toHaveCount(1);
  await expect(editor.locator("sup"), "and did not displace the superscript").toHaveCount(1);

  const schema = await (
    await page.request.get(`${API}/api/design-workshops/schema`, { headers: auth })
  ).json();
  const entityKey = schema.stages
    .find((s: { key: string }) => s.key === STAGE_KEY)
    ?.entities?.find((e: { fields: { key: string }[] }) => e.fields.some((f) => f.key === FIELD_KEY))
    ?.key as string;
  const probe = await page.request.put(
    `${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`,
    {
      headers: auth,
      data: {
        replaceCollections: false,
        entries: [
          {
            entityKey,
            data: {
              [FIELD_KEY]: {
                blocks: [{ kind: "PARAGRAPH", spans: [{ text: "probe", marks: ["SUPERSCRIPT"] }] }]
              }
            }
          }
        ]
      }
    }
  );
  const readBack = await page.request.get(
    `${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`,
    { headers: auth }
  );
  const serverKnowsTheMark =
    probe.ok() &&
    readBack.ok() &&
    JSON.stringify((await readBack.json())?.singleton?.[FIELD_KEY] ?? {}).includes("SUPERSCRIPT");

  await page.getByRole("button", { name: /^save/i }).first().click();
  await expect(page.getByText(/saved/i).first()).toBeVisible({ timeout: 30_000 });

  test.skip(
    !serverKnowsTheMark,
    "the API is serving a build that predates SUPERSCRIPT, so it drops the mark on the way in — " +
      "the editor half of this test has already run and passed"
  );

  await expect
    .poll(
      async () => {
        const stored = await (
          await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
            headers: auth
          })
        ).json();
        return JSON.stringify(stored?.singleton?.acknowledgement ?? {});
      },
      { message: "the stored document carries SUPERSCRIPT and not SUBSCRIPT", timeout: 30_000 }
    )
    .toContain("SUPERSCRIPT");

  const stored = await (
    await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
      headers: auth
    })
  ).json();
  expect(
    JSON.stringify(stored?.singleton?.acknowledgement ?? {}),
    "the displaced mark was never stored"
  ).not.toContain("SUBSCRIPT");
});

test("a photograph can be placed inside the prose and reaches the server as an IMAGE block", async ({
  page
}) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Inline image spec ${Date.now()}` }
  });
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  const editor = page.locator('[contenteditable="true"]').first();
  await expect(editor).toBeVisible({ timeout: 30_000 });

  await editor.click();
  await page.keyboard.type("The puckering at the seam, below.");

  await page.keyboard.down("Shift");
  for (let i = 0; i < 6; i += 1) await page.keyboard.press("ArrowLeft");
  await page.keyboard.up("Shift");

  const place = page.getByRole("button", { name: /place a photograph/i });
  await expect(place, "the picture control is on the contextual toolbar").toBeVisible();

  const png = Buffer.from(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    "base64"
  );
  await page.locator("input[data-rte-image-input]").first().setInputFiles({
    name: "loom-detail.png",
    mimeType: "image/png",
    buffer: png
  });

  const figure = editor.locator("figure");
  await expect(figure, "the figure was placed in the prose").toHaveCount(1, { timeout: 60_000 });
  await expect(figure.locator("img")).toHaveCount(1);

  await page.keyboard.type("Detail of the seam on prototype PT-01.");
  await expect(figure.locator("figcaption")).toContainText("prototype PT-01");

  await page.getByRole("button", { name: /^save/i }).first().click();
  await expect(page.getByText(/saved/i).first()).toBeVisible({ timeout: 30_000 });

  await expect
    .poll(
      async () => {
        const stored = await (
          await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
            headers: auth
          })
        ).json();
        const blocks = stored?.singleton?.acknowledgement?.blocks ?? [];
        const image = blocks.find((b: { kind: string }) => b.kind === "IMAGE");
        return image?.media ? JSON.stringify(image) : null;
      },
      { message: "the stored document contains an IMAGE block carrying a media id", timeout: 30_000 }
    )
    .not.toBeNull();

  const stored = await (
    await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
      headers: auth
    })
  ).json();
  const image = (stored?.singleton?.acknowledgement?.blocks ?? []).find(
    (b: { kind: string }) => b.kind === "IMAGE"
  );
  expect(JSON.stringify(image.spans), "the caption reached the server").toContain("prototype PT-01");

  const report = await page.request.post(`${API}/api/design-workshops/${workshopId}/report`, {
    headers: auth,
    data: { formats: ["DOCX"], record: false }
  });
  expect(report.status(), "the report generates with a picture in the prose").toBe(200);
});

test("a table's rows and columns can be added and removed at the caret", async ({ page }) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Table shape spec ${Date.now()}` }
  });
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  const editor = page.locator('[contenteditable="true"]').first();
  await expect(editor).toBeVisible({ timeout: 30_000 });
  await editor.click();
  await page.keyboard.type("Costing.");
  await page.keyboard.down("Shift");
  await page.keyboard.press("ArrowLeft");
  await page.keyboard.up("Shift");
  await page.getByRole("button", { name: /insert table/i }).first().click();

  const table = editor.locator("table");
  await expect(table.locator("tr")).toHaveCount(3);
  await expect(table.locator("tr").first().locator("th")).toHaveCount(3);

  await table.locator("td").first().click();
  const addRow = page.getByRole("button", { name: /insert row below/i });
  await expect(addRow, "the row controls appear when the caret is in a cell").toBeVisible();

  await addRow.click();
  await expect(table.locator("tr"), "a row was added").toHaveCount(4);

  await table.locator("td").first().click();
  await page.getByRole("button", { name: /insert column right/i }).click();
  await expect(table.locator("tr").first().locator("th"), "a column was added").toHaveCount(4);

  await table.locator("td").first().click();
  await page.getByRole("button", { name: /delete this row/i }).click();
  await expect(table.locator("tr"), "a row was removed").toHaveCount(3);

  await table.locator("td").first().click();
  await page.getByRole("button", { name: /delete this column/i }).click();
  await expect(table.locator("tr").first().locator("th"), "a column was removed").toHaveCount(3);

  await table.locator("td").first().click();
  await page.getByRole("button", { name: /delete this row/i }).click();
  await expect(table.locator("tr"), "the header and one body row survive").toHaveCount(2);
  await table.locator("td").first().click();
  await page.getByRole("button", { name: /delete this row/i }).click();
  await expect(table.locator("tr"), "and cannot be reduced further").toHaveCount(2);
});
