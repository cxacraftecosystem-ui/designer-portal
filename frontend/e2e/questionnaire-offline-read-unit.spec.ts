import { expect, test } from "@playwright/test";

import {
  QUESTIONNAIRE_FORM_CACHE_VERSION,
  cacheRecordIsReadable,
  cachedQuestionnaireNotice,
  questionnaireCacheKey,
  type CachedQuestionnaireForm
} from "@/lib/questionnaireFormCache";
import type { QForm } from "@/lib/questionnaireForms";

/**
 * WHICH STORED COPY OF A CUSTOM QUESTIONNAIRE MAY BE SERVED, AND TO WHOM.
 *
 * The read cache exists so a designer with no signal can open a colleague's instrument instead of a
 * red line. Every rule below is one whose failure is SILENT: a retired wording offered for a new
 * answer looks like an ordinary question, another account's copy looks like your own, and a
 * half-decoded record looks like a questionnaire that simply has fewer sections.
 *
 * Asserted here rather than in a browser because none of it is reachable without a laptop that has
 * actually lost its connection — which is exactly the condition nobody tests under.
 */

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

/**
 * THE TRAP, PINNED.
 *
 * Android's two screens disagree about `includeRetired`, and one cache file for both hands the
 * answer screen a retired question the server then refuses with a 422 naming it. Both WEB callers
 * ask for the long list today, so this key currently has one live bucket — which is precisely why
 * the assertion is worth having: the day a caller asks for the shorter list, an id-only key would
 * answer it out of the longer one and nothing on screen would say so.
 */
test("the cache key is the PAIR, so the two lists can never share a record", () => {
  expect(questionnaireCacheKey("q1", true)).toBe("q1::all");
  expect(questionnaireCacheKey("q1", false)).toBe("q1::active");
  expect(questionnaireCacheKey("q1", true)).not.toBe(questionnaireCacheKey("q1", false));
});

test("a copy is served to the account that took it and to no other", () => {
  expect(cacheRecordIsReadable(cachedForm(), "designer-1")).toBe(true);
  // A shared field laptop. `QForm.entries` carry `respondentName` and every answer given, and the
  // web's `logout` clears the token and nothing else — so the record's own stamp is the boundary.
  expect(cacheRecordIsReadable(cachedForm(), "designer-2")).toBe(false);
  // "We do not know whose this is" must not resolve to "anyone's", on either side of the comparison.
  expect(cacheRecordIsReadable(cachedForm({ ownerUserId: null }), "designer-1")).toBe(false);
  expect(cacheRecordIsReadable(cachedForm(), null)).toBe(false);
});

test("a record from a future build is ignored rather than half-decoded", () => {
  expect(cacheRecordIsReadable(cachedForm({ schemaVersion: QUESTIONNAIRE_FORM_CACHE_VERSION + 1 }), "designer-1")).toBe(
    false
  );
  // A form with no sections array is indistinguishable, on screen, from a questionnaire that never
  // had any — so it is refused rather than rendered.
  expect(cacheRecordIsReadable(cachedForm({ form: { id: "q1", version: 4 } as unknown as QForm }), "designer-1")).toBe(
    false
  );
  expect(cacheRecordIsReadable(null, "designer-1")).toBe(false);
  expect(cacheRecordIsReadable(undefined, "designer-1")).toBe(false);
});

test("the notice says it is a copy, when it was taken, its version, and that saving is refused", () => {
  const notice = cachedQuestionnaireNotice("27 Aug 2026, 03:30 pm", 4);
  expect(notice).toContain("copy this browser downloaded on 27 Aug 2026, 03:30 pm");
  // The version is the one number a designer can act on: it is how they find out this copy predates
  // the four questions a colleague says they added this morning.
  expect(notice).toContain("version 4");
  expect(notice).toContain("ANSWERS CANNOT BE SAVED");
});

test("with no readable stamp the sentence drops the clause instead of printing a placeholder", () => {
  // `formatDateTime` answers "-" for a missing value, and "downloaded on -" is worse than silence.
  for (const empty of [null, "", "-"]) {
    const notice = cachedQuestionnaireNotice(empty, 2);
    expect(notice).not.toContain("downloaded on");
    expect(notice).toContain("downloaded (version 2)");
    expect(notice).toContain("ANSWERS CANNOT BE SAVED");
  }
});
