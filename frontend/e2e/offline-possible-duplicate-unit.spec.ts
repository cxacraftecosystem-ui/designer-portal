import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import type { DuplicateCandidate } from "@/lib/duplicates";
import {
  bodyWithAcknowledged,
  differentPersonEntry,
  possibleDuplicateSentence,
  samePersonEntry,
  type OutboxEntry
} from "@/lib/offline";

/*
  A QUEUED ARTISAN THE SERVER HELD AS A LIKELY DUPLICATE. Its own arm, never read as a clash: the row
  says "May already be recorded", "Same person" uploads the files to the existing record and creates
  nothing, "Different person" sends it again with the candidates acknowledged.
*/

const ROOT = join(__dirname, "..");
const read = (rel: string) => readFileSync(join(ROOT, rel), "utf8");

const candidate: DuplicateCandidate = {
  artisan: { id: "existing-1", name: "Giriraj Prasaad", place: "Bhuj" },
  score: 0.86,
  band: "LIKELY",
  reasons: [{ code: "NAME", label: "Names 92% alike" }]
};

function entry(): OutboxEntry {
  return {
    id: 7,
    label: "Artisan · Giriraj Prasad",
    createdAt: 0,
    endpoint: "/artisans",
    method: "POST",
    body: JSON.stringify({ name: "Giriraj Prasad", duplicateAcknowledged: [] }),
    media: [],
    attempts: 1,
    failure: possibleDuplicateSentence([candidate]),
    possibleDuplicate: { candidates: [candidate] }
  };
}

test("the row names the person it may be", () => {
  expect(possibleDuplicateSentence([candidate])).toBe("Not saved yet. This looks like Giriraj Prasaad (Bhuj).");
});

test("Same person points the entry at the existing record and clears the question", () => {
  const next = samePersonEntry(entry(), "existing-1");
  expect(next.created).toBe(true);
  expect(next.createdId).toBe("existing-1");
  expect(next.attachedToExisting).toBe(true);
  expect(next.failure).toBeNull();
  expect(next.possibleDuplicate).toBeNull();
  // The queued record's fields are not copied anywhere: the body is untouched.
  expect(next.body).toBe(entry().body);
});

test("Different person re-sends with the candidates acknowledged", () => {
  const next = differentPersonEntry(entry());
  expect(JSON.parse(next.body).duplicateAcknowledged).toEqual(["existing-1"]);
  expect(next.failure).toBeNull();
  expect(next.created).toBeUndefined();
  expect(JSON.parse(bodyWithAcknowledged(next.body, ["existing-1", "b"])).duplicateAcknowledged).toEqual(["existing-1", "b"]);
});

test("the drain reads the hold before the clash arm, and the banner offers both answers", () => {
  const offline = read("lib/offline.ts");
  const hold = offline.indexOf("const hold = possibleDuplicateHold(error);");
  const clash = offline.indexOf("Not saved — it clashes with a record that already exists.");
  expect(hold).toBeGreaterThan(0);
  expect(hold).toBeLessThan(clash);
  const banner = read("components/OutboxBanner.tsx");
  expect(banner).toContain("May already be recorded");
  expect(banner).toContain("Same person");
  expect(banner).toContain("Different person");
});
