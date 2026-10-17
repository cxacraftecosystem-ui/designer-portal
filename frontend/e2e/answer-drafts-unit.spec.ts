import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { isMachineSuggested, MACHINE_SUGGESTED_LABEL, pendingFor, type AnswerDraft } from "@/components/questionnaire/AnswerDraftsPanel";

/**
 * C28 on the designer portal's web: drafted answers are labelled, only a section's pending drafts
 * are offered, and the panel is drawn only behind the server flag and for an editor. No browser.
 */
const draft = (over: Partial<AnswerDraft>): AnswerDraft => ({
  id: "d1",
  questionId: "q1",
  sectionId: "s1",
  draftText: "x",
  status: "PENDING",
  provider: "anthropic",
  model: "m",
  ...over
});

test("both machine origins are labelled, a typed answer is not", () => {
  expect(MACHINE_SUGGESTED_LABEL).toBe("Machine-suggested");
  expect(isMachineSuggested("MACHINE_SUGGESTED")).toBe(true);
  expect(isMachineSuggested("MACHINE_SUGGESTED_EDITED")).toBe(true);
  expect(isMachineSuggested(null)).toBe(false);
});

test("only this section's pending drafts on screen are offered", () => {
  const items = [
    draft({}),
    draft({ id: "d2", questionId: "q2", status: "ACCEPTED" }),
    draft({ id: "d3", questionId: "q3", sectionId: "s2" }),
    draft({ id: "d4", questionId: "q9" })
  ];
  expect(pendingFor(items, "s1", new Set(["q1", "q2", "q3"])).map((d) => d.id)).toEqual(["d1"]);
});

test("the panel is drawn only behind its flag, for an editor, on the questionnaire page", () => {
  const panel = readFileSync(join(__dirname, "../components/questionnaire/AnswerDraftsPanel.tsx"), "utf8");
  expect(panel).toContain('"/questionnaire/answer-drafts/status"');
  expect(panel).toContain("if (!enabled || !list) return null;");
  expect(panel).toContain("answer.mayDraft === false");
  const page = readFileSync(join(__dirname, "../app/(protected)/questionnaire/page.tsx"), "utf8");
  expect(page).toContain("<AnswerDraftsPanel");
  expect(page).toContain("<MachineSuggestedBadge");
});
