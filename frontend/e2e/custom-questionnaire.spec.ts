import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * The whole loop the owner asked for, driven through the browser against the real API:
 * build a questionnaire, give it a section and a question, start a sitting, record an answer —
 * and then READ THAT ANSWER BACK OFF THE SERVER.
 *
 * WHY THE READ-BACK IS THE POINT. Every screen in this feature is optimistic in the ordinary way:
 * it re-reads the form after a save and draws what came back. So a spec that only asserted what is
 * on screen would pass against a build whose PUT never left the browser, or one that posted to the
 * SINGULAR `/api/questionnaire` — the global artisan questionnaire, a different instrument with a
 * near-identical path — and simply redrew its own local state. Asking the API directly, with its own
 * token, is what distinguishes "the app says it saved" from "it is saved".
 *
 * AND WHY IT STARTS FROM AN EMPTY QUESTIONNAIRE. The uploaded spreadsheet may carry answers or none
 * at all, and the second case is the ordinary one — a designer building an instrument for interviews
 * they have not run yet. A questionnaire with no answers anywhere in it is therefore the state
 * answering has to work from, so that is the state this spec builds.
 *
 * LOCATORS ARE STRUCTURAL, NOT BY ACCESSIBLE NAME, wherever a control's label is assembled from data
 * — the section-title box is labelled `Title of section ${code}` and the answer boxes are labelled
 * with the question text and its ordinal. Matching those by name is how this spec would start
 * failing on a copy change that broke nothing.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/**
 * The one `<form>` on the page whose submit button reads `label`.
 *
 * Several screens here carry two forms at once — the questionnaire's details beside the add-section
 * form, both holding an `input[name="title"]` — so a field is addressed through the form it will be
 * submitted with rather than by a name that is only unique by accident.
 */
function formWith(page: Page, label: string) {
  return page.locator("form").filter({ has: page.getByRole("button", { name: label, exact: true }) });
}

async function apiToken(page: Page): Promise<string> {
  const res = await page.request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } });
  expect(res.ok(), "sign-in for the API fixture").toBeTruthy();
  return (await res.json()).accessToken as string;
}

test("a designer builds a questionnaire, answers it, and the answer is on the server", async ({ page }) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };
  const stamp = Date.now();
  const title = `E2E loom survey ${stamp}`;
  const prompt = `How many looms do you own? (${stamp})`;
  const answerText = `12 looms, counted on ${stamp}`;

  await signIn(page);
  await page.goto("/questionnaires");

  // --- Build the questionnaire ---------------------------------------------------------------
  //
  // The empty-questionnaire door rather than the .xlsx upload, because a Playwright file chooser
  // would need a binary fixture checked into the repo and the upload path has its own backend tests.
  // What this spec is here to prove is the part no backend test can reach: that the WEB client's
  // sections, questions, sittings and answers land in the right tables through the right routes.
  await page.getByRole("button", { name: "Start an empty one" }).click();
  // Each fill is scoped to the form its submit button lives in. The detail page carries a details
  // form and an add-section form that both hold an `input[name="title"]`, so a page-wide locator is
  // ambiguous there — and would be ambiguous here the day this page grows a second form.
  await formWith(page, "Create questionnaire").locator('input[name="title"]').fill(title);
  await page.getByRole("button", { name: "Create questionnaire" }).click();

  // Creating one navigates straight into it — a brand-new questionnaire has no sections, so the list
  // is not a useful place to be left standing.
  await page.waitForURL(/\/questionnaires\/[^/]+$/, { timeout: 30_000 });
  const questionnaireId = new URL(page.url()).pathname.split("/").pop() as string;
  expect(questionnaireId, "the URL names the questionnaire that was just created").toBeTruthy();

  // --- A section ------------------------------------------------------------------------------
  await page.getByRole("button", { name: "Add a section" }).click();
  await formWith(page, "Add section").locator('input[name="title"]').fill("Background");
  await page.getByRole("button", { name: "Add section" }).click();
  await expect(page.getByText("Code BACKGROUND")).toBeVisible();

  // --- A question -----------------------------------------------------------------------------
  await page.getByRole("button", { name: "Add a question" }).click();
  await formWith(page, "Add question").locator('input[name="prompt"]').fill(prompt);
  await page.getByRole("button", { name: "Add question" }).click();
  await expect(page.getByText(prompt)).toBeVisible();

  // --- Start a sitting -------------------------------------------------------------------------
  await page.getByRole("link", { name: "Record answers" }).first().click();
  await page.waitForURL(/\/questionnaires\/[^/]+\/answer/, { timeout: 30_000 });

  await page.getByRole("button", { name: "Start a new sitting" }).click();
  await formWith(page, "Start recording answers").locator('input[name="respondentName"]').fill("Ramesh Kumar");
  await page.getByRole("button", { name: "Start recording answers" }).click();

  // The sitting becomes the one in the URL, so a reload returns to the interview in progress rather
  // than to an unchosen picker. Waiting on it also serialises the rest of the spec behind the
  // create + re-read that the button kicks off.
  await page.waitForURL(/\/questionnaires\/[^/]+\/answer\?entry=/, { timeout: 30_000 });

  // --- Record an answer ------------------------------------------------------------------------
  //
  // Located structurally: the box's accessible name is built from the question text and its ordinal,
  // and the note box beside it is a sibling inside a <details>. `textarea` picks out the answer box
  // specifically — the note is an <input>.
  const answerBox = page.locator("section.panel ol li textarea").first();
  await expect(answerBox).toBeVisible();
  await answerBox.fill(answerText);

  await page.getByRole("button", { name: "Finish this sitting" }).click();

  // Finishing saves the section and returns to the questionnaire, where the sitting is now listed
  // with its progress — 1 of 1, because the form has exactly one answerable question.
  await page.waitForURL(/\/questionnaires\/[^/]+$/, { timeout: 30_000 });
  await expect(page.getByText("1 of 1 answered")).toBeVisible();

  // --- Read it back off the server -------------------------------------------------------------
  //
  // The assertion this whole spec exists for. Nothing below reads the browser's state.
  const response = await page.request.get(`${API}/api/questionnaires/${questionnaireId}`, { headers: auth });
  expect(response.ok(), "the questionnaire reads back from the API").toBeTruthy();
  const stored = await response.json();

  expect(stored.title).toBe(title);
  expect(stored.sections).toHaveLength(1);
  expect(stored.sections[0].title).toBe("Background");
  expect(stored.sections[0].questions).toHaveLength(1);

  const storedQuestion = stored.sections[0].questions[0];
  expect(storedQuestion.prompt).toBe(prompt);
  // `hasAnswers` is what the editor reads to decide whether the wording may still be changed, so it
  // flipping is part of the answer having landed — not a separate nicety.
  expect(storedQuestion.hasAnswers, "the question now reports that it has been answered").toBe(true);

  expect(stored.entries).toHaveLength(1);
  const storedEntry = stored.entries[0];
  expect(storedEntry.respondentName).toBe("Ramesh Kumar");
  // "APP" rather than "UPLOAD": this sitting was typed here, not carried in on a spreadsheet's
  // answer columns. Both end up in the same table, and this is the field that tells them apart.
  expect(storedEntry.source).toBe("APP");

  const storedAnswer = storedEntry.answers.find((a: { questionId: string }) => a.questionId === storedQuestion.id);
  expect(storedAnswer, "an answer row exists against that question").toBeTruthy();
  expect(storedAnswer.answerText).toBe(answerText);
});

/**
 * The rule the editor's controls are shaped by, checked end to end: once a question has been
 * answered, DELETE retires it rather than deleting it, and the button must have said so first.
 *
 * This is the honesty requirement in the brief, and it is worth a browser test rather than trusting
 * the backend's own: the server converts rather than refuses, so a UI that offered "Delete" would be
 * overruled silently and the spec would still pass on the API side. What can only be checked here is
 * that the control was LABELLED "Retire" before it was pressed.
 */
test("a question with answers offers Retire, not Delete, and the server agrees", async ({ page }) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };
  const stamp = Date.now();

  // Built through the API rather than the UI: the previous test already proves the browser can build
  // one, and re-driving six forms to reach the state under test would make this spec fail for
  // reasons that have nothing to do with what it is asserting.
  const made = await page.request.post(`${API}/api/questionnaires`, {
    headers: auth,
    data: { title: `E2E retire rule ${stamp}` }
  });
  expect(made.ok()).toBeTruthy();
  const questionnaireId = (await made.json()).id as string;

  const sectioned = await page.request.post(`${API}/api/questionnaires/${questionnaireId}/sections`, {
    headers: auth,
    data: { title: "Livelihood" }
  });
  expect(sectioned.ok()).toBeTruthy();
  const sectionId = (await sectioned.json()).sections[0].id as string;

  const questioned = await page.request.post(
    `${API}/api/questionnaires/${questionnaireId}/sections/${sectionId}/questions`,
    { headers: auth, data: { prompt: "How many weavers work with you?" } }
  );
  expect(questioned.ok()).toBeTruthy();
  const questionId = (await questioned.json()).sections[0].questions[0].id as string;

  const sitting = await page.request.post(`${API}/api/questionnaires/${questionnaireId}/entries`, {
    headers: auth,
    data: { respondentName: "Sunita" }
  });
  expect(sitting.ok()).toBeTruthy();
  const entryId = (await sitting.json()).id as string;

  const answered = await page.request.put(
    `${API}/api/questionnaires/${questionnaireId}/entries/${entryId}/answers`,
    { headers: auth, data: { answers: [{ questionId, answerText: "Four" }] } }
  );
  expect(answered.ok()).toBeTruthy();

  await signIn(page);
  await page.goto(`/questionnaires/${questionnaireId}`);

  // The label is decided by `hasAnswers`, which the server sends on every question precisely so the
  // affordance can change BEFORE the designer commits to it.
  await expect(page.getByRole("button", { name: "Retire" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Delete" })).toHaveCount(0);
  await expect(page.getByText("Answered — its wording is fixed from here")).toBeVisible();

  await page.getByRole("button", { name: "Retire" }).click();
  await page.getByRole("button", { name: "Retire question" }).click();

  // The retired question stays on screen with its explanation. A form that hid it would show a
  // designer fewer questions than there are answers, with nothing saying where the rest went.
  await expect(page.getByText(/Retired\. It is no longer asked/)).toBeVisible();

  const after = await page.request.get(`${API}/api/questionnaires/${questionnaireId}?includeRetired=true`, {
    headers: auth
  });
  const stored = await after.json();
  const storedQuestion = stored.sections[0].questions.find((q: { id: string }) => q.id === questionId);
  expect(storedQuestion, "the question is still in the record — retired, not deleted").toBeTruthy();
  expect(storedQuestion.isActive).toBe(false);
  expect(storedQuestion.retiredAt).toBeTruthy();

  // And the answer it was given is untouched, which is the entire reason the rule exists.
  const storedAnswer = stored.entries[0].answers.find((a: { questionId: string }) => a.questionId === questionId);
  expect(storedAnswer.answerText).toBe("Four");
});
