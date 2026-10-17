import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { matchSummary, prefillFromMatch, type CrossPortalMatch } from "@/lib/crossPortal";

/**
 * C27 on the web: a Field Repository match fills the artisan form's own fields, says who was found
 * in one line, and the form draws nothing until the server says the feature is on. No browser.
 */
const MATCH: CrossPortalMatch = {
  remoteArtisanId: "fr1",
  matchedBy: "AADHAAR",
  fields: {
    name: " Meena Devi ",
    gender: "Female",
    dateOfBirth: "1980-05-01T00:00:00",
    pehchanCardAvailable: true,
    pehchanCardNumber: "PEH12345",
    experienceYears: 0,
    phone: "",
    place: "Bhuj"
  },
  identityFields: ["name", "localName", "gender", "dateOfBirth", "pehchanCardAvailable", "pehchanCardNumber"],
  craft: { code: "bandhani", name: "Bandhani", craftId: "c-local", craftName: "Bandhej" },
  statedAddress: { state: "Gujarat", district: "Kachchh", village: null }
};

test("a match fills the form's fields and leaves blanks alone", () => {
  const prefill = prefillFromMatch(MATCH);
  expect(prefill.name).toBe("Meena Devi");
  expect(prefill.dateOfBirth).toBe("1980-05-01");
  expect(prefill.experienceYears).toBe(0); // a stated 0 is an answer
  expect(prefill.craftId).toBe("c-local");
  expect(prefill.phone).toBeUndefined();
  expect(prefill).not.toHaveProperty("aadhaarNumber");
});

test("the summary names the person, the craft and the place", () => {
  expect(matchSummary(MATCH)).toBe("Meena Devi · Bandhani · Kachchh");
});

test("the form mounts the lookup only through the server-gated components", () => {
  const form = readFileSync(join(__dirname, "../components/forms/ArtisanForm.tsx"), "utf8");
  expect(form).toContain("<CrossPortalFind");
  expect(form).toContain("<CrossPortalLinkPanel");
  const panel = readFileSync(join(__dirname, "../components/forms/CrossPortalLookup.tsx"), "utf8");
  // Both components return nothing until the status endpoint says the feature is on.
  expect(panel.match(/if \(!enabled\) return null;|if \(!enabled \|\| !link\) return null;/g)?.length).toBe(2);
  // The lookup body sends the agreement; the server refuses one without it.
  const lib = readFileSync(join(__dirname, "../lib/crossPortal.ts"), "utf8");
  expect(lib).toContain("consent: true");
});
