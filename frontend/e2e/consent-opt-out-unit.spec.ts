import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ARTISAN_CONSENT_DESCRIPTION, artisanConsentStatus } from "@/components/forms/ArtisanConsentToggle";
import { consentRefused } from "@/lib/aiVerbs";
import { DW_CONSENT_QUESTION, DW_CONSENT_SWITCH_LABEL, dictationConsentSentence } from "@/lib/designWorkshops";

/** Consent is assumed by default and can be revoked (owner decision, 19 Oct 2026). */

test("only a refusal on record is a refusal", () => {
  expect(consentRefused("REFUSED")).toBe(true);
  expect(consentRefused("NOT_RECORDED")).toBe(false);
  expect(consentRefused("GRANTED")).toBe(false);
  expect(consentRefused("")).toBe(false);
});

test("the artisan switch is on by default and off only after a refusal", () => {
  const src = readFileSync(join(__dirname, "..", "components", "forms", "ArtisanConsentToggle.tsx"), "utf8");
  expect(src).toContain('checked={consent.state !== "REFUSED"}');
  expect(ARTISAN_CONSENT_DESCRIPTION).toMatch(/^On by default\. Switch off/);
  expect(artisanConsentStatus({ state: "NOT_RECORDED", source: null, recordedAt: null })).toBe("On by default");
  expect(artisanConsentStatus({ state: "REFUSED", source: null, recordedAt: null })).toBe("Declined");
});

test("the workshop switch is on by default and says so", () => {
  const src = readFileSync(join(__dirname, "..", "components", "designworkshop", "DictationConsentCard.tsx"), "utf8");
  expect(src).toContain('const on = token !== "REFUSED";');
  expect(DW_CONSENT_SWITCH_LABEL).toBe("Recordings may be transcribed");
  expect(DW_CONSENT_QUESTION).toMatch(/^On by default/);
  expect(dictationConsentSentence("NOT_RECORDED")).toMatch(/^On by default/);
  expect(dictationConsentSentence("REFUSED")).toMatch(/off/);
});
