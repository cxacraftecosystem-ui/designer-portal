import { expect, test } from "@playwright/test";

import {
  QUESTIONNAIRE_FORM_CACHE_VERSION,
  cacheRecordIsReadable,
  cachedQuestionnaireNotice,
  questionnaireCacheKey,
  type CachedQuestionnaireForm
} from "@/lib/questionnaireFormCache";
import type { QForm } from "@/lib/questionnaireForms";

function cachedForm(over: Partial<CachedQuestionnaireForm> = {}): CachedQuestionnaireForm {
  const form = { id: "q1", version: 4, sections: [] } as unknown as QForm;
  return {
    key: questionnaireCacheKey("q1", true),
    schemaVersion: QUESTIONNAIRE_FORM_CACHE_VERSION,
    fetchedAt: "2026-08-27T10:00:00.000Z",
    includeRetired: true,
    version: 4,
    ownerUserId: "designer-1",
    form,
    ...over
  };
}

test("the cache key is the PAIR, so the two lists can never share a record", () => {
  expect(questionnaireCacheKey("q1", true)).toBe("q1::all");
  expect(questionnaireCacheKey("q1", false)).toBe("q1::active");
  expect(questionnaireCacheKey("q1", true)).not.toBe(questionnaireCacheKey("q1", false));
});

test("a copy is served to the account that took it and to no other", () => {
  expect(cacheRecordIsReadable(cachedForm(), "designer-1")).toBe(true);
  expect(cacheRecordIsReadable(cachedForm(), "designer-2")).toBe(false);
  expect(cacheRecordIsReadable(cachedForm({ ownerUserId: null }), "designer-1")).toBe(false);
  expect(cacheRecordIsReadable(cachedForm(), null)).toBe(false);
});

test("a record from a future build is ignored rather than half-decoded", () => {
  expect(cacheRecordIsReadable(cachedForm({ schemaVersion: QUESTIONNAIRE_FORM_CACHE_VERSION + 1 }), "designer-1")).toBe(
    false
  );
  expect(cacheRecordIsReadable(cachedForm({ form: { id: "q1", version: 4 } as unknown as QForm }), "designer-1")).toBe(
    false
  );
  expect(cacheRecordIsReadable(null, "designer-1")).toBe(false);
  expect(cacheRecordIsReadable(undefined, "designer-1")).toBe(false);
});

test("the notice says it is a copy, when it was taken, its version, and that saving is refused", () => {
  const notice = cachedQuestionnaireNotice("27 Aug 2026, 03:30 pm", 4);
  expect(notice).toContain("copy this browser downloaded on 27 Aug 2026, 03:30 pm");
  expect(notice).toContain("version 4");
  expect(notice).toContain("Connect to the internet to save answers");
});

test("with no readable stamp the sentence drops the clause instead of printing a placeholder", () => {
  for (const empty of [null, "", "-"]) {
    const notice = cachedQuestionnaireNotice(empty, 2);
    expect(notice).not.toContain("downloaded on");
    expect(notice).toContain("downloaded (version 2)");
    expect(notice).toContain("Connect to the internet to save answers");
  }
});
