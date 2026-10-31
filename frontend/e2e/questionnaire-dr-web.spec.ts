import { gzipSync } from "node:zlib";

import { expect, test, type Page } from "@playwright/test";

import { ME, OTHER, QuestionnaireFake } from "./support/qv2Fake";

/**
 * DESIGNER-PORTAL'S SHARED QUESTIONNAIRE, IN A REAL BROWSER (UNIFY 5.4, Web; stream U1).
 *
 * Every screen runs for real over a production build (`next build && next start`, the
 * `questionnaire-web` job in `.github/workflows/checks.yml`); every `/api` call is answered by the
 * in-memory fake in `e2e/support/qv2Fake.ts`, which keeps what each screen sent. Each test is
 * tagged with the feature-registry rows it exercises (`@qf:<id>`, `shared/questionnaire-features.json`).
 * The server's own rules (the lock, the soft delete, the shims) are held by the backend suites and
 * by the real-stack spec, `e2e/questionnaire-dr-v2-stack.spec.ts`.
 */

const SHOTS = process.env.E2E_SHOT_DIR ?? "test-results/questionnaire-dr-web";

async function open(page: Page, fake: QuestionnaireFake, path: string) {
  await fake.install(page);
  await page.goto(path);
}

test.describe.configure({ mode: "serial" });

test("@qf:B28 @qf:A14 the sessions list shows guests, and a content admin restores a deleted session", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire");
  const list = page.locator("[data-sessions-list]");
  await expect(list).toContainText("Interview_2026-10-22_Bagru-Jaipur_Hand-Block-Printing_s-1");
  await expect(list.locator("[data-guest-chip]")).toContainText("Shopper at Johari Bazaar · Guest");
  await page.locator("[data-deleted-toggle]").click();
  await expect(list).toContainText("Interview_2026-10-20_Bagru_Deleted");
  await page.locator("[data-restore-session='s-gone']").click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaire/sessions/s-gone/restore").length).toBe(1);
});

test("@qf:B36 with the switch off the session screens send the reader to the forms", async ({ page }) => {
  const fake = new QuestionnaireFake();
  fake.v2 = false;
  await open(page, fake, "/questionnaire?edit=int1");
  await expect(page).toHaveURL(/\/questionnaires$/);
});

test("@qf:A18 @qf:A8 @qf:B1 @qf:B3 @qf:B30 @qf:B31 @qf:D2 @qf:D3 @qf:B38 a session with only a guest, on the workshop's primary form", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/new");
  const sheet = page.locator("[data-start-sheet]");
  // B30: the design workshop most recently given; B31: its primary form, resolved by the server.
  await expect.poll(() => fake.calledWith("GET", "/questionnaire/form-for").some((c) => c.query.get("designWorkshopId") === "dw1")).toBe(true);
  // A8: the workshop's forms are offered first, under their kind.
  await sheet.locator("[data-searchable-select]").nth(1).click();
  await expect(page.getByText("This workshop’s forms · Workshop interview")).toBeVisible();
  await page.keyboard.press("Escape");
  await sheet.getByRole("button", { name: "Someone else" }).click();
  await sheet.locator("[data-guest-name]").fill("Shopper at Johari Bazaar");
  await expect(sheet.locator("[data-title-preview]")).toContainText("Bagru");
  await sheet.getByRole("button", { name: "Start session" }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaire/sessions").length, { timeout: 15_000 }).toBe(1);
  const body = fake.calledWith("POST", "/questionnaire/sessions")[0].body as Record<string, unknown>;
  expect(body).toMatchObject({ questionnaireId: "f1", designWorkshopId: "dw1", participants: [], respondents: [{ name: "Shopper at Johari Bazaar", kind: "GUEST" }] });
  expect(body).not.toHaveProperty("title");
  await expect(page).toHaveURL(/\/questionnaire\/sessions\/s-new/, { timeout: 15_000 });
});

test("@qf:B11 @qf:D4 @qf:B13 @qf:D1 @qf:C5 the grid marks recorded, typed and overridden cells, and an admin sets an override", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/s-1");
  const grid = page.locator("[data-coverage-grid]");
  await expect(grid.getByRole("button", { name: "A Giriraj Prasad Chhipa: Recorded" })).toHaveText("●");
  await expect(grid.getByRole("button", { name: "A Shopper at Johari Bazaar: Typed · Add a recording?" })).toHaveText("✎ 1/2");
  await expect(grid.getByRole("button", { name: "B Giriraj Prasad Chhipa: Needs review" })).toHaveText("?");
  await grid.getByRole("button", { name: "B Giriraj Prasad Chhipa: Needs review" }).click();
  const override = page.locator("[data-section-override]");
  await override.getByRole("button", { name: "Marked complete" }).click();
  await expect.poll(() => fake.calledWith("PUT", "/questionnaire/completion").length).toBe(1);
  expect(fake.calledWith("PUT", "/questionnaire/completion")[0].body).toEqual({ artisanId: "a1", sectionId: "s2", status: "COMPLETED" });
});

test("@qf:A11 @qf:B19 @qf:B20 @qf:A20 @qf:B21 someone else's answer is locked with who answered; a group answer and its note save", async ({ page }) => {
  const fake = new QuestionnaireFake();
  fake.role = "DESIGNER";
  fake.sessions[0].researchers = [
    { userId: OTHER.id, name: OTHER.name, role: "LEAD" },
    { userId: ME.id, name: ME.name, role: "CONTRIBUTOR" }
  ];
  await open(page, fake, "/questionnaire/sessions/s-1");
  await page.getByRole("button", { name: "A", exact: true }).click();
  await page.getByRole("switch", { name: "Type answers" }).click();
  const a1 = page.locator("[data-answer-box='l1']");
  // P15 for Giriraj: Priya answered, and only she or a lead can change it.
  await a1.locator("[data-searchable-select]").first().click();
  await page.getByRole("option", { name: "Giriraj Prasad Chhipa" }).click();
  await expect(a1.locator("[role=textbox]")).toHaveAttribute("aria-readonly", "true");
  await expect(a1.locator("[data-p15]")).toHaveText("Answered by Priya. Only they or a lead can change it.");
  await a1.locator("[data-history]").click();
  await expect(page.locator("[data-answer-history]")).toContainText("Wooden blocks");
  await expect(page.locator("[data-answer-history]")).toContainText("Blocks");
  await page.getByRole("button", { name: "OK" }).click();
  // B20: "All 2" — one GROUP_SHARED item for Giriraj and one for the guest, with the note (A20).
  const a2 = page.locator("[data-answer-box='l2']");
  await a2.getByRole("button", { name: "Add a note" }).click();
  await a2.locator("[data-answer-note]").fill("Asked twice");
  await a2.locator("[role=textbox]").fill("Carved locally");
  await a2.locator("[role=textbox]").blur();
  await expect.poll(() => fake.calledWith("PUT", "/questionnaire/answers").length).toBeGreaterThan(0);
  const items = (fake.calledWith("PUT", "/questionnaire/answers").at(-1)!.body as { items: Record<string, unknown>[] }).items;
  expect(items).toEqual([
    expect.objectContaining({ artisanId: "a1", questionLineageId: "l2", text: "Carved locally", attribution: "GROUP_SHARED", note: "Asked twice" }),
    expect.objectContaining({ respondentId: "r1", questionLineageId: "l2", text: "Carved locally", attribution: "GROUP_SHARED", note: "Asked twice" })
  ]);
});

test("@qf:B22 @qf:C2 @qf:B23 @qf:C3 @qf:B18 @qf:B40 a take's transcript is offered as an answer; removed takes restore; a take has a history", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/s-1");
  await page.locator("[data-coverage-grid]").getByRole("button", { name: "A Giriraj Prasad Chhipa: Recorded" }).click();
  const sheet = page.locator("[data-take-sheet]");
  await expect(sheet).toContainText("Nandini");
  await page.getByRole("button", { name: "History" }).first().click();
  await expect(page.locator("[data-take-history]")).toContainText("moved");
  await page.getByRole("button", { name: "Show removed takes" }).click();
  await page.locator("[data-removed-takes]").getByRole("button", { name: "Restore" }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaire/recordings/rec-gone/restore").length).toBe(1);
  await page.locator("[data-use-take]").click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaire/recordings/rec-1/answer-proposals").length).toBe(1);
  await expect(page.locator("[data-proposal-note]")).toContainText("1 answers suggested");
  const a2 = page.locator("[data-answer-box='l2']");
  await expect(a2.locator("[data-use-as-answer]")).toContainText("My uncle carves them in Pethapur.");
  await a2.locator("[data-use-as-answer]").getByRole("button", { name: "Use as answer" }).click();
  await a2.locator("[role=textbox]").focus();
  await a2.locator("[role=textbox]").blur();
  await expect.poll(() => fake.calledWith("PUT", "/questionnaire/answers").length).toBeGreaterThan(0);
  const items = (fake.calledWith("PUT", "/questionnaire/answers").at(-1)!.body as { items: Record<string, unknown>[] }).items;
  expect(items[0]).toMatchObject({ questionLineageId: "l2", text: "My uncle carves them in Pethapur.", origin: "TRANSCRIPT", sourceRecordingId: "rec-1" });
});

test("@qf:A19 @qf:B7 Finish reports the required questions unanswered and still finishes", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/s-1");
  await page.locator("[data-session-header]").getByRole("button", { name: "Finish" }).click();
  const prompt = page.locator("[data-prompt='FINISH']");
  await expect(page.getByRole("dialog")).toContainText("required questions unanswered.");
  await prompt.getByRole("button", { name: "Finish" }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaire/sessions/s-1/finish").length).toBe(1);
});

test("@qf:B6 @qf:A17 @qf:B9 a researcher is added, a guest's name corrected, and a content admin deletes the session", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/s-1");
  const actions = page.locator("[data-session-actions]");
  await actions.getByRole("button", { name: "Add researcher" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.locator("[data-searchable-select]").first().click();
  await page.getByRole("option", { name: "Ravi Kumar" }).click();
  await dialog.getByRole("button", { name: "Add", exact: true }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaire/sessions/s-1/researchers").length).toBe(1);
  expect(fake.calledWith("POST", "/questionnaire/sessions/s-1/researchers")[0].body).toEqual({ userId: "u-third", role: "CONTRIBUTOR" });

  await actions.locator("[data-guest='r1']").getByRole("button", { name: "Edit" }).click();
  await page.locator("[data-guest-name-edit]").fill("");
  await page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect.poll(() => fake.calledWith("PATCH", "/questionnaire/sessions/s-1/respondents/r1").length).toBe(1);
  expect(fake.calledWith("PATCH", "/questionnaire/sessions/s-1/respondents/r1")[0].body).toEqual({ name: "", notes: "" });

  await actions.getByRole("button", { name: "Delete session" }).click();
  await expect(page.getByRole("alertdialog")).toContainText("Admins can restore it from Deleted.");
  await page.locator("[data-confirm-delete]").click();
  await expect.poll(() => fake.calledWith("DELETE", "/questionnaire/sessions/s-1").length).toBe(1);
  await expect(page).toHaveURL(/\/questionnaire$/);
});

test("@qf:B8 continue another day opens the next session", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/s-1");
  await page.locator("[data-session-actions]").getByRole("button", { name: "Continue another day" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Start" }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaire/sessions/continue").length).toBe(1);
  expect(fake.calledWith("POST", "/questionnaire/sessions/continue")[0].body).toMatchObject({ fromSessionId: "s-1", artisanIds: ["a1"], split: "ONE" });
  await expect(page).toHaveURL(/\/questionnaire\/sessions\/s-next$/);
});

test("@qf:B26 @qf:C6 the artisan's Questionnaire tab: timeline across researchers, by question, CSV", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/artisans/a1?tab=questionnaire");
  const record = page.locator("[data-artisan-record]");
  await expect(record).toContainText("2nd Craft Toolkit");
  await expect(record).toContainText("1 of 2 sections");
  await expect(record.locator("[data-timeline]")).toContainText("Priya Shah");
  await expect(record.locator("[data-timeline]")).toContainText("Nandini Rao");
  await record.getByRole("button", { name: "By question" }).click();
  await expect(record).toContainText("Wooden blocks");
  await expect(record.getByRole("link", { name: "Export CSV" })).toHaveAttribute("href", /\/api\/artisans\/a1\/record\.csv\?questionnaireId=f1/);
});

function bundleFile(): Buffer {
  return gzipSync(
    Buffer.from(
      JSON.stringify({
        v: 2,
        bundleId: "0123456789abcdef0123456789abcdef",
        createdAt: "2026-10-22T05:00:00+05:30",
        portal: "documentation-portal",
        form: { title: "2nd Craft Toolkit (copy)", description: null, kind: "WORKSHOP_INTERVIEW" },
        sections: [{ code: "A", title: "Tools", sortOrder: 1 }],
        questions: [{ sectionCode: "A", prompt: "Which tools do you use?", helpText: null, isRequired: true, artisanField: null, questionCode: null, sortOrder: 1 }]
      })
    )
  );
}

test("@qf:A10 Open bundle reads a Field Repository bundle and Add makes it a new form", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaires");
  await page.locator("[data-bundle-input]").setInputFiles({ name: "FieldRepository_Questionnaire_2026-10-22_Toolkit_Handoff.frwq", mimeType: "application/octet-stream", buffer: bundleFile() });
  const prompt = page.locator("[data-prompt='P17']");
  await expect(page.getByRole("dialog")).toContainText("A Field Repository colleague sent ‘2nd Craft Toolkit (copy)’ (1 sections).");
  await prompt.getByRole("button", { name: "Add" }).click();
  await expect.poll(() => fake.calledWith("POST", /^\/questionnaires\/fnew1\/sections\/[^/]+\/questions$/).length).toBe(1);
  expect(fake.calledWith("POST", "/questionnaires")[0].body).toMatchObject({ title: "2nd Craft Toolkit (copy)", kind: "WORKSHOP_INTERVIEW" });
});

for (const theme of ["light", "dark"] as const) {
  test(`@qf:A10 @qf:A11 P15 and P17 at 360 px in ${theme}: two buttons on one line`, async ({ page }) => {
    const fake = new QuestionnaireFake();
    await page.setViewportSize({ width: 360, height: 780 });
    await page.emulateMedia({ colorScheme: theme });
    await open(page, fake, "/questionnaires");
    await page.locator("[data-bundle-input]").setInputFiles({ name: "x.frwq", mimeType: "application/octet-stream", buffer: bundleFile() });
    const prompt = page.locator("[data-prompt='P17']");
    await expect(prompt.getByRole("button", { name: "Not now" })).toBeVisible();
    await expect(prompt.getByRole("button", { name: "Add" })).toBeVisible();
    // The dialog panel springs in (y 10 -> 0, scale 0.98 -> 1), so two separate boundingBox() calls
    // can land on different frames of that motion and disagree by several px with the buttons on one
    // line. Read both tops in one layout pass, once the fonts the labels are set in have loaded.
    await page.evaluate(() => document.fonts.ready.then(() => undefined));
    const tops = await prompt
      .getByRole("button", { name: /^(Not now|Add)$/ })
      .evaluateAll((buttons) => buttons.map((button) => button.getBoundingClientRect().top));
    expect(tops).toHaveLength(2);
    expect(Math.abs(tops[0] - tops[1])).toBeLessThan(2);
    await page.screenshot({ path: `${SHOTS}/p17-360-${theme}.png` });
  });
}

test("@qf:A13 the forms library: Mine, Shared and Workshop tabs, and an admin restores a deleted form", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaires");
  const tabs = page.locator("[data-forms-tabs]");
  await expect(page.getByRole("button", { name: "New form" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Open bundle" })).toBeVisible();
  await expect(page.getByText("2nd Craft Toolkit").first()).toBeVisible();
  await tabs.getByRole("button", { name: "Shared" }).click();
  await expect(page.getByText("Market survey 2026").first()).toBeVisible();
  await tabs.getByRole("button", { name: "Deleted" }).click();
  await expect(page.getByText("Old baseline").first()).toBeVisible();
  await page.getByRole("button", { name: /Restore/ }).first().click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaires/fold/restore").length).toBe(1);
});

test("@qf:A9 @qf:D2 an admin makes a binding not primary and sets the workshop kind's default form", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaires/f1");
  const panel = page.locator("[data-form-admin]");
  await expect(panel.locator("[data-binding='dw1']")).toContainText("Bagru block printing 2026");
  await panel.locator("[data-binding='dw1']").getByRole("switch", { name: "Primary" }).click();
  await expect.poll(() => fake.calledWith("PUT", "/questionnaire/forms/f1/bindings").length).toBe(1);
  expect(fake.calledWith("PUT", "/questionnaire/forms/f1/bindings")[0].body).toEqual({ bindings: [{ workshopId: null, designWorkshopId: "dw1", isPrimary: false }] });
});

test("@qf:B25 the inbox lists what needs sorting out", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/inbox");
  await expect(page.getByText("Empty session").first()).toBeVisible();
});

test("@qf:B12 workshop coverage reads the design workshop's artisans", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/coverage");
  await expect(page.locator("[data-coverage-grid]")).toBeVisible();
  await expect.poll(() => fake.calledWith("GET", "/questionnaire/coverage").some((c) => (c.query.get("artisanIds") ?? "").includes("a1"))).toBe(true);
});

test("@qf:A1 @qf:A6 the pro-forma downloads under its generated name, and a designer starts their own form", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaires");
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download pro-forma" }).click();
  expect((await download).suggestedFilename()).toBe("DesignWorkshop_Questionnaire_Pro-Forma.xlsx");
  await page.locator("[data-new-form]").click();
  await page.locator("input[name='title']").fill("Dyers of Bagru");
  await page.getByRole("button", { name: "Create questionnaire" }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaires").length).toBe(1);
  expect(fake.calledWith("POST", "/questionnaires")[0].body).toMatchObject({ title: "Dyers of Bagru" });
  await expect(page).toHaveURL(/\/questionnaires\/fnew1$/);
});

test("@qf:A3 @qf:A7 @qf:B29 a form's workbook and question set download, an admin publishes it to everyone, and the corpus is linked", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaires/f1");
  let download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download .xlsx" }).click();
  expect((await download).suggestedFilename()).toBe("DesignWorkshop_Questionnaire_2026-10-22_2nd-Craft-Toolkit_Export.xlsx");
  download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download question set" }).click();
  expect((await download).suggestedFilename()).toBe("DesignWorkshop_Questionnaire_2026-10-22_2nd-Craft-Toolkit_Questions.xlsx");
  await page.getByRole("button", { name: "Publish as the standard form" }).click();
  await expect.poll(() => fake.calledWith("PATCH", "/questionnaires/f1").length).toBe(1);
  expect(fake.calledWith("PATCH", "/questionnaires/f1")[0].body).toEqual({ isShared: true });
  await expect(page.locator("[data-corpus='answers']")).toHaveAttribute("href", "/api/questionnaire/corpus.csv?questionnaireId=f1&kind=answers");
  await expect(page.locator("[data-corpus='recordings']")).toHaveAttribute("href", "/api/questionnaire/corpus.csv?questionnaireId=f1&kind=recordings");
});

test("@qf:C7 a content admin merges one sitting into another session", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/s-1");
  await page.locator("[data-session-actions]").getByRole("button", { name: "Merge into…" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.locator("[data-searchable-select]").first().click();
  await page.getByRole("option", { name: "Interview_2026-10-21_Bagru_Second-Sitting" }).click();
  await dialog.getByRole("button", { name: "Merge", exact: true }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaire/sessions/s-1/merge-into/s-2").length).toBe(1);
  await expect(page).toHaveURL(/\/questionnaire\/sessions\/s-2$/);
});

test("@qf:B2 an open session with the same artisan today is offered to join instead (P1)", async ({ page }) => {
  const fake = new QuestionnaireFake();
  fake.openMatch = { id: "s-1", title: "Interview_2026-10-22_Bagru", startedAt: "2026-10-22T05:00:00Z", researcher: { id: OTHER.id, name: OTHER.name }, artisanNames: ["Giriraj"] };
  await open(page, fake, "/questionnaire/sessions/new?artisanId=a1");
  await page.locator("[data-start-sheet]").getByRole("button", { name: "Start session" }).click();
  await expect(page.locator("[data-prompt='P1']")).toBeVisible();
  await expect(page.getByRole("dialog")).toContainText("Priya Shah started one with Giriraj");
  await page.locator("[data-prompt='P1']").getByRole("button", { name: "Join" }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaire/sessions/s-1/researchers").length).toBe(1);
  await expect(page).toHaveURL(/\/questionnaire\/sessions\/s-1$/);
  expect(fake.calledWith("POST", "/questionnaire/sessions").length).toBe(0);
});

test("@qf:B33 @qf:C4 a refused artisan's consent shows on the start sheet and can be switched back on", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/new?artisanId=a2");
  await expect(page.locator("[data-start-sheet]")).toContainText("Recordings of Meena are not sent to outside services: consent was refused.");
  await page.getByRole("button", { name: "Switch back on" }).click();
  await expect.poll(() => fake.calledWith("POST", "/artisans/a2/consent").length).toBe(1);
  expect(fake.calledWith("POST", "/artisans/a2/consent")[0].body).toMatchObject({ state: "GRANTED" });
});

test("@qf:B27 a reviewer approves a session from its read view", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/s-1/view");
  await page.getByRole("button", { name: "Approve" }).click();
  await expect.poll(() => fake.calledWith("POST", "/review/questionnaire/s-1/approve").length).toBe(1);
});


const WORKBOOK = { name: "Bagru_dyers.xlsx", mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", buffer: Buffer.from("PK workbook") };

test("@qf:A2 a hand-filled workbook becomes a form, and its answers come in as sittings", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaires");
  await page.getByRole("button", { name: "Upload workbook" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.locator("input[type=file]").setInputFiles(WORKBOOK);
  await dialog.getByRole("button", { name: "Upload questionnaire" }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaires/upload").length).toBe(1);
  await expect(page.getByText("answers imported")).toBeVisible();
  await expect(page.getByText("sittings recorded from the sheet")).toBeVisible();
});

test("@qf:A4 a re-upload edits the form under the keep-old-wording rule and says what it did", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaires/f1");
  await page.getByRole("button", { name: "Re-upload" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.locator("input[type=file]").setInputFiles(WORKBOOK);
  await dialog.getByRole("button", { name: "Upload and apply" }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaires/f1/upload").length).toBe(1);
  await expect(page.getByText("reworded into new questions")).toBeVisible();
});

test("@qf:A5 a form is reused as a template: its questions, no answers", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaires/f1");
  await page.getByRole("button", { name: "Reuse at another workshop" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Create the reuse" }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaires/f1/reuse").length).toBe(1);
  await expect(page.getByText("questions copied")).toBeVisible();
});

test("@qf:A16 a form read once opens again with no signal, from this account's copy", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaires/f1");
  await expect(page.getByRole("heading", { name: "2nd Craft Toolkit" })).toBeVisible();
  await expect(page.getByText("Which tools do you use?")).toBeVisible();
  fake.unreachable = [/^\/questionnaires\/f1$/];
  await page.reload();
  await expect(page.getByText("You are reading the copy this browser downloaded")).toBeVisible();
  await expect(page.getByText("Which tools do you use?")).toBeVisible();
});

test("@qf:A15 a form, a section and a question made offline are sent in order once online", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await page.addInitScript(() => {
    Object.defineProperty(Navigator.prototype, "onLine", { configurable: true, get: () => !(window as unknown as { __qOffline?: boolean }).__qOffline });
  });
  await open(page, fake, "/questionnaires");
  await page.evaluate(() => ((window as unknown as { __qOffline: boolean }).__qOffline = true));
  await page.locator("[data-new-form]").click();
  await page.locator("input[name='title']").fill("Dyers of Bagru");
  await page.getByRole("button", { name: "Create questionnaire" }).click();
  await expect(page).toHaveURL(/\/questionnaires\/tmp-form-/);
  await expect(page.locator("[data-offline-form]")).toContainText("Dyers of Bagru");
  await page.getByRole("textbox", { name: "Section title" }).fill("Dyes");
  await page.getByRole("button", { name: "Add section" }).click();
  await expect(page.locator("[data-pending-section]")).toContainText("Dyes");
  await page.getByRole("textbox", { name: "Question" }).fill("Which dyes?");
  await page.getByRole("button", { name: "Add question" }).click();
  await expect(page.locator("[data-pending-question]")).toContainText("Which dyes?");
  expect(fake.calledWith("POST", /^\/questionnaires/)).toHaveLength(0);
  await page.evaluate(() => {
    (window as unknown as { __qOffline: boolean }).__qOffline = false;
    window.dispatchEvent(new Event("online"));
  });
  await expect(page).toHaveURL(/\/questionnaires\/fnew1$/);
  expect(fake.calledWith("POST", /^\/questionnaires/).map((c) => c.path)).toEqual([
    "/questionnaires",
    "/questionnaires/fnew1/sections",
    "/questionnaires/fnew1/sections/fnew1-s1/questions"
  ]);
  expect(fake.calledWith("POST", "/questionnaires/fnew1/sections/fnew1-s1/questions")[0].body).toMatchObject({ prompt: "Which dyes?" });
});

test("@qf:B32 an admin makes a form the default for workshops with none bound", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaires/f1");
  await page.locator("[data-form-admin]").getByRole("switch", { name: "Default form when nothing is bound" }).click();
  await expect.poll(() => fake.calledWith("PUT", "/questionnaires/f1/default").length).toBe(1);
  expect(fake.calledWith("PUT", "/questionnaires/f1/default")[0].body).toEqual({ isDefault: true });
  await expect(page.locator("[data-form-admin]").getByRole("switch", { name: "Default form when nothing is bound" })).toHaveAttribute("aria-checked", "true");
});

test("@qf:C9 the start sheet takes the device's location beside the typed place", async ({ page, context }) => {
  const fake = new QuestionnaireFake();
  await context.grantPermissions(["geolocation"]);
  await context.setGeolocation({ latitude: 26.82, longitude: 75.55, accuracy: 12 });
  await open(page, fake, "/questionnaire/sessions/new?artisanId=a1");
  const sheet = page.locator("[data-start-sheet]");
  await sheet.getByRole("button", { name: "Use my location" }).click();
  await expect(sheet.locator("[data-device-location='saved']")).toContainText("Location saved (within 12 m)");
  await sheet.getByRole("button", { name: "Start session" }).click();
  await expect.poll(() => fake.calledWith("POST", "/questionnaire/sessions").length).toBe(1);
  expect(fake.calledWith("POST", "/questionnaire/sessions")[0].body).toMatchObject({ location: { latitude: 26.82, longitude: 75.55, accuracy: 12 } });
});

test("@qf:B24 a question mapped to an artisan column is prefilled from the artisan's record", async ({ page }) => {
  const fake = new QuestionnaireFake();
  fake.prefill = { l2: "craft" };
  await open(page, fake, "/questionnaire/sessions/s-1");
  await expect.poll(() => fake.calledWith("PUT", "/questionnaire/answers").length).toBe(1);
  const items = (fake.calledWith("PUT", "/questionnaire/answers")[0].body as { items: Record<string, unknown>[] }).items;
  expect(items).toEqual([expect.objectContaining({ artisanId: "a1", questionLineageId: "l2", text: "Hand Block Printing", origin: "PREFILL" })]);
});

test("@qf:C8 a formatted answer is written as the document and its plain text", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/s-1");
  await page.getByRole("button", { name: "A", exact: true }).click();
  await page.getByRole("switch", { name: "Type answers" }).click();
  const box = page.locator("[data-answer-box='l2'] [role=textbox]");
  await box.click();
  await page.keyboard.type("Indigo");
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.press("ControlOrMeta+B");
  await box.blur();
  await expect.poll(() => fake.calledWith("PUT", "/questionnaire/answers").length).toBeGreaterThan(0);
  const item = (fake.calledWith("PUT", "/questionnaire/answers").at(-1)!.body as { items: Record<string, unknown>[] }).items[0];
  expect(item.text).toBe("Indigo");
  expect(String(item.richText)).toContain("BOLD");
  expect(String(item.richText)).toContain("Indigo");
});

/** Writes into the shared client's IndexedDB once the page has opened it. */
async function seedQuestionnaireDb(page: Page, store: string, rows: unknown[]) {
  await page.evaluate(
    async ({ store, rows }) => {
      const db = await new Promise<IDBDatabase>((resolve, reject) => {
        const request = indexedDB.open("field-repo-questionnaire");
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      });
      await new Promise<void>((resolve, reject) => {
        const tx = db.transaction(store, "readwrite");
        for (const row of rows as Record<string, unknown>[]) {
          const value = { ...row };
          if (typeof value.blobText === "string") {
            value.blob = new Blob([value.blobText as string], { type: "audio/webm" });
            delete value.blobText;
          }
          tx.objectStore(store).put(value);
        }
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error);
      });
      db.close();
    },
    { store, rows }
  );
}

test("@qf:B15 a take cut short by a closed tab is offered back by P13 when the session opens again", async ({ page }) => {
  const fake = new QuestionnaireFake();
  await open(page, fake, "/questionnaire/sessions/s-1");
  await expect(page.locator("[data-session-workspace]")).toBeVisible();
  const startedAt = Date.now() - 12 * 60_000;
  await seedQuestionnaireDb(page, "takes", [
    { takeKey: "t-crash", sessionKey: "ck-s-1", clientClipKey: "clip-crash", deviceCapturedAt: new Date(startedAt).toISOString(), mimeType: "audio/webm", targetLabel: "Section A", targets: [{ sectionId: "s1" }], roster: ["a1"], startedAt, finishedAt: null }
  ]);
  await seedQuestionnaireDb(page, "chunks", [
    { takeKey: "t-crash", index: 0, blobText: "HEAD", at: startedAt },
    { takeKey: "t-crash", index: 1, blobText: "B", at: startedAt + 12 * 60_000 }
  ]);
  await page.reload();
  const p13 = page.getByRole("dialog");
  await expect(p13).toContainText("Recovered a recording");
  await expect(p13).toContainText("A 12-minute take of Section A was saved before the app closed.");
  await p13.getByRole("button", { name: "Discard" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

test("@qf:B39 the fix tray sends a refused step again, sends without the conflicting answers, and discards", async ({ page }) => {
  const fake = new QuestionnaireFake();
  // The page drains the outbox as soon as it loads, and that drain re-sends the refused participant
  // step. The server must refuse it the way it was refused before, or the reason in the tray is
  // replaced by whatever the re-send happens to get, depending on which lands first.
  fake.removedArtisans.add("a2");
  await open(page, fake, "/questionnaire");
  await expect(page.locator("[data-sessions-list]")).toBeVisible();
  const item = { artisanId: "a1", questionLineageId: "l1", text: "Mine", origin: "TYPED", attribution: "INDIVIDUAL", sourceRecordingId: null };
  await seedQuestionnaireDb(page, "units", [
    {
      id: "ck-s-1",
      sessionId: "s-1",
      createdAt: "2026-10-22T05:00:00Z",
      steps: [
        { kind: "participant", status: "FAILED", op: "join", artisanId: "a2", at: "2026-10-22T05:00:00Z", idempotencyKey: "p-tray", error: "That artisan was removed." },
        {
          kind: "answers", status: "FAILED", error: "CONFLICT", key: "ans-tray",
          items: [item, { ...item, questionLineageId: "l2", text: "Fine" }],
          conflicts: [{ artisanId: "a1", questionLineageId: "l1", status: "CONFLICT", answer: null, current: { id: "ans-1", version: 2, text: "Wooden blocks" } }]
        }
      ]
    }
  ]);
  await page.reload();
  const tray = page.locator("[data-outbox-tray]");
  await expect(tray).toContainText("Not sent: That artisan was removed.");
  await expect(tray).toContainText("1 changed by someone else");
  await tray.locator("[data-tray-item='conflict']").getByRole("button", { name: "Send without the conflicting answers" }).click();
  await expect.poll(() => fake.calledWith("PUT", "/questionnaire/answers").length).toBe(1);
  expect((fake.calledWith("PUT", "/questionnaire/answers")[0].body as { items: Record<string, unknown>[] }).items).toEqual([
    expect.objectContaining({ artisanId: "a1", questionLineageId: "l2", text: "Fine" })
  ]);
  await tray.locator("[data-tray-item='failed']").getByRole("button", { name: "Send again" }).click();
  await expect.poll(() => fake.calls.filter((c) => /\/questionnaire\/sessions\/s-1\/participants/.test(c.path)).length).toBeGreaterThan(0);
  await expect(tray.locator("[data-tray-item='failed']")).toBeVisible();
  await tray.locator("[data-tray-item='failed']").getByRole("button", { name: "Discard" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Discard" }).click();
  await expect(page.locator("[data-outbox-tray]")).toHaveCount(0);
});
