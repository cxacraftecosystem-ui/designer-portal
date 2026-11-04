import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

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

  await page.getByRole("button", { name: "Start an empty one" }).click();
  await formWith(page, "Create questionnaire").locator('input[name="title"]').fill(title);
  await page.getByRole("button", { name: "Create questionnaire" }).click();

  await page.waitForURL(/\/questionnaires\/[^/]+$/, { timeout: 30_000 });
  const questionnaireId = new URL(page.url()).pathname.split("/").pop() as string;
  expect(questionnaireId, "the URL names the questionnaire that was just created").toBeTruthy();

  await page.getByRole("button", { name: "Add a section" }).click();
  await formWith(page, "Add section").locator('input[name="title"]').fill("Background");
  await page.getByRole("button", { name: "Add section" }).click();
  await expect(page.getByText("Code BACKGROUND")).toBeVisible();

  await page.getByRole("button", { name: "Add a question" }).click();
  await formWith(page, "Add question").locator('input[name="prompt"]').fill(prompt);
  await page.getByRole("button", { name: "Add question" }).click();
  await expect(page.getByText(prompt)).toBeVisible();

  await page.getByRole("link", { name: "Record answers" }).first().click();
  await page.waitForURL(/\/questionnaires\/[^/]+\/answer/, { timeout: 30_000 });

  await page.getByRole("button", { name: "Start a new sitting" }).click();
  await formWith(page, "Start recording answers").locator('input[name="respondentName"]').fill("Ramesh Kumar");
  await page.getByRole("button", { name: "Start recording answers" }).click();

  await page.waitForURL(/\/questionnaires\/[^/]+\/answer\?entry=/, { timeout: 30_000 });

  const answerBox = page.locator("section.panel ol li textarea").first();
  await expect(answerBox).toBeVisible();
  await answerBox.fill(answerText);

  await page.getByRole("button", { name: "Finish this sitting" }).click();

  await page.waitForURL(/\/questionnaires\/[^/]+$/, { timeout: 30_000 });
  await expect(page.getByText("1 of 1 answered")).toBeVisible();

  const response = await page.request.get(`${API}/api/questionnaires/${questionnaireId}`, { headers: auth });
  expect(response.ok(), "the questionnaire reads back from the API").toBeTruthy();
  const stored = await response.json();

  expect(stored.title).toBe(title);
  expect(stored.sections).toHaveLength(1);
  expect(stored.sections[0].title).toBe("Background");
  expect(stored.sections[0].questions).toHaveLength(1);

  const storedQuestion = stored.sections[0].questions[0];
  expect(storedQuestion.prompt).toBe(prompt);
  expect(storedQuestion.hasAnswers, "the question now reports that it has been answered").toBe(true);

  expect(stored.entries).toHaveLength(1);
  const storedEntry = stored.entries[0];
  expect(storedEntry.respondentName).toBe("Ramesh Kumar");
  expect(storedEntry.source).toBe("APP");

  const storedAnswer = storedEntry.answers.find((a: { questionId: string }) => a.questionId === storedQuestion.id);
  expect(storedAnswer, "an answer row exists against that question").toBeTruthy();
  expect(storedAnswer.answerText).toBe(answerText);
});

test("a question with answers offers Retire, not Delete, and the server agrees", async ({ page }) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };
  const stamp = Date.now();

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

  await expect(page.getByRole("button", { name: "Retire" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Delete" })).toHaveCount(0);
  await expect(page.getByText("Answered — its wording is fixed from here")).toBeVisible();

  await page.getByRole("button", { name: "Retire" }).click();
  await page.getByRole("button", { name: "Retire question" }).click();

  await expect(page.getByText(/Retired\. It is no longer asked/)).toBeVisible();

  const after = await page.request.get(`${API}/api/questionnaires/${questionnaireId}?includeRetired=true`, {
    headers: auth
  });
  const stored = await after.json();
  const storedQuestion = stored.sections[0].questions.find((q: { id: string }) => q.id === questionId);
  expect(storedQuestion, "the question is still in the record — retired, not deleted").toBeTruthy();
  expect(storedQuestion.isActive).toBe(false);
  expect(storedQuestion.retiredAt).toBeTruthy();

  const storedAnswer = stored.entries[0].answers.find((a: { questionId: string }) => a.questionId === questionId);
  expect(storedAnswer.answerText).toBe("Four");
});
