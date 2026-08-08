import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * Report history, and the diff between two generated reports.
 *
 * THE QUESTION UNDER TEST is the one a ministry reviewer asks months after a resubmission: "did
 * you update the cost sheet before you sent it again?". Four files exist, each already recorded
 * with a checksum, a size, a template and a timestamp, and until this screen there was nowhere to
 * read any of it — `listDesignWorkshopExports` had no call site in the whole web app.
 *
 * SO THE SPEC BUILDS THE SITUATION RATHER THAN ASSUMING IT. It creates its own workshop, fills two
 * stages, generates a real report, EDITS ONE OF THE TWO STAGES IN PLACE, generates a second real
 * report, and records a third as though a phone had made it offline. That shape is what makes the
 * assertions worth anything: one stage must come back as WRITTEN TO and the OTHER as provably
 * identical in both files. A spec run against a workshop where everything moved could not tell a
 * working diff from a screen that lists every stage — which is exactly what a mutation of
 * `reportDiff`'s `touched` flag confirmed while this was written: it turned "1 stage was written
 * to" into "2 stages were written to" and the assertion below caught it.
 *
 * It deliberately does not reuse E2E_WORKSHOP_ID. That workshop's export history is whatever
 * previous runs and previous agents left behind, and the interesting assertion here — "this stage
 * did not move between these two files" — is only meaningful when the test knows what moved.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/** The stage that gets edited between the two reports, and the one that must not. */
const CHANGED_STAGE = "TRADITIONAL_PROCESS_BASELINE";
const UNCHANGED_STAGE = "WORKSHOP_SETUP";

/**
 * Choose one export in one of the two pickers.
 *
 * The option is scoped to ITS OWN listbox rather than looked up on the page. `AnchoredPopover`
 * keeps a closed panel mounted for its exit animation, so both dropdowns' option lists can be in
 * the DOM at once and a bare `getByRole("option")` is ambiguous — it matched the same label in both
 * pickers and Playwright refused it, correctly.
 */
async function pickExport(page: Page, picker: "Earlier report" | "Later report", option: RegExp) {
  await page.getByTestId("report-diff").getByRole("button", { name: picker }).click();
  await page.getByRole("listbox", { name: new RegExp(picker) }).getByRole("option", { name: option }).click();
}

async function token(request: APIRequestContext): Promise<string> {
  const response = await request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } });
  expect(response.status(), "the API must sign the fixture in").toBe(200);
  return (await response.json()).accessToken;
}

/**
 * One workshop, two stages, three recorded exports — with a real edit between the first two.
 *
 * The two report generations are genuine `POST /report` calls, so the export rows carry checksums
 * of bytes that actually existed rather than fixture strings. The third is `POST /exports`, the
 * route a phone uses when it made a file with no network, because "where did this file come from"
 * is one of the facts the history has to show and it is a column no other screen reads.
 */
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

  // A full second, because both timestamps land in the same window otherwise and the diff would be
  // asked to separate two edits it genuinely cannot separate — which is a real limitation of
  // timestamp evidence, not something to paper over inside the assertion.
  await new Promise((resolve) => setTimeout(resolve, 1_200));

  // The edit, carrying `entryId` for BOTH rows that already exist — which is what the web form
  // does, and what makes this an edit IN PLACE rather than a delete-and-recreate. Sending a row
  // without its id would create a duplicate and the diff would legitimately report removals and
  // additions that the designer never made.
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

    // Three generations, numbered oldest-first so "generation 1" means the same thing on screen as
    // it does in the sentence a designer says out loud.
    await expect(history.getByText(/^Generation 1$/)).toBeVisible();
    await expect(history.getByText(/^Generation 3$/)).toBeVisible();

    // WHERE THE FILE CAME FROM. An export a phone made offline exists on exactly one device until
    // somebody copies it off; the server's is in the repository. Both must be distinguishable.
    await expect(history.getByText("Made on a phone, offline")).toHaveCount(1);
    await expect(history.getByText("Made by the repository")).toHaveCount(2);

    // WHO GENERATED IT. `GET /{id}/exports` never returned this, so before the new endpoint every
    // row fell through to the "no account" wording — this assertion fails against the old payload.
    await expect(history.getByText("Account no longer exists")).toHaveCount(0);

    // THE CHECKSUM, IN FULL. Sixty-four characters, because an abbreviated hash cannot be compared
    // against `sha256sum` on a laptop in an office, which is the only way anyone ever checks one.
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

    // Compare the two SERVER reports — generations 1 and 2 — which is the window the edit sits in.
    await pickExport(page, "Earlier report", /Generation 1/);
    await pickExport(page, "Later report", /Generation 2/);

    await expect(diff.getByTestId("diff-summary")).toContainText("Generation 1 → generation 2");
    await expect(diff.getByTestId("diff-summary")).toContainText("1 stage was written to");

    // THE STAGE THAT MOVED: the two existing rows re-saved in place, one new row added. The counts
    // are asserted because a save recorded as delete-and-recreate would read "2 removed / 3 added",
    // which describes work the designer did not do.
    const touched = diff.getByTestId("touched-stages");
    await expect(touched).toContainText("Traditional Process, Tools & Raw Materials");
    await expect(touched).toContainText("2 rewritten");
    await expect(touched).toContainText("1 added");

    // AND THE CAVEAT THAT MAKES "2 rewritten" HONEST: only one of those two rows actually has a
    // different description. A stage is saved whole, so the count is rows saved, not answers that
    // differ, and the screen has to say so or the number is a claim it cannot support.
    await expect(diff.getByText(/counts rows saved, not answers that differ/)).toBeVisible();

    // THE STAGE THAT DID NOT MOVE, AND THE CLAIM MADE ABOUT IT. This is the answer to "did you
    // change the cost sheet?" — not "we found no evidence" but "both files carried the same data",
    // which the timestamps genuinely support because nothing wrote to it.
    // By role: the same words also appear in the limits panel below, where they explain the claim
    // rather than make it.
    await expect(diff.getByRole("heading", { name: "Identical in both files" })).toBeVisible();
    const untouched = diff.getByTestId("untouched-stages");
    await expect(untouched).toContainText("Workshop Setup & Cover Information");
    await expect(touched).not.toContainText("Workshop Setup & Cover Information");

    // THE COMPLETENESS FIGURE, AND THE ONLY PLACE IT MAY APPEAR. Nothing records what a stage
    // scored when a past report was made, so today's percentage describes a past file only where
    // the stage has not been written to since — which is exactly this stage and not the one above.
    await expect(untouched).toContainText(/\d+% of its required fields, in both files and still today/);
    await expect(touched).not.toContainText("in both files and still today");

    // THE LIMITS ARE ON SCREEN, not in a comment. A reader is about to answer a ministry from this
    // panel, and a limit nobody is told about is indistinguishable from a fact.
    await expect(diff.getByText(/Which field changed, or what it changed from/)).toBeVisible();

    expect(pageErrors, "the diff threw").toEqual([]);
  });

  test("comparing a file with itself is refused rather than answered", async ({ page }) => {
    await signIn(page);
    await page.goto(`/design-workshops/${workshopId}/report/history`);

    const diff = page.getByTestId("report-diff");
    await expect(diff).toBeVisible({ timeout: 30_000 });

    await pickExport(page, "Earlier report", /Generation 3/);

    // Both dropdowns now name generation 3. A window of zero length would report "nothing changed",
    // which is true and useless — and reads as a verdict about two different files.
    await expect(diff.getByText("Choose two different files.")).toBeVisible();
    await expect(diff.getByTestId("diff-summary")).toHaveCount(0);
  });
});
