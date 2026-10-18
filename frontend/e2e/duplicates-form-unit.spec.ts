import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import {
  acknowledgedIds,
  candidateLine,
  checkKey,
  checkReady,
  mergedSurvivorId,
  possibleDuplicateHold,
  visibleCandidates,
  type DuplicateCandidate
} from "@/lib/duplicates";

/*
  THE ARTISAN FORM'S "ALREADY RECORDED?" CARD. The server scores; the form shows at most three rows,
  sets them aside on "Different person", and every create carries the set-aside ids as
  `duplicateAcknowledged` ([] when nothing was shown) — the list is what lets the server hold.
*/

const ROOT = join(__dirname, "..");
const read = (rel: string) => readFileSync(join(ROOT, rel), "utf8");

function candidate(id: string, score: number, extra: Partial<DuplicateCandidate["artisan"]> = {}): DuplicateCandidate {
  return {
    artisan: { id, name: `Artisan ${id}`, place: "Bagru", craft: "Dabu", birthYear: 1971, ...extra },
    score,
    band: score >= 0.9 ? "VERY_LIKELY" : score >= 0.75 ? "LIKELY" : "POSSIBLE",
    reasons: [{ code: "NAME", label: "Names 92% alike" }]
  };
}

test("at most three rows, best first, and set-aside rows stay hidden", () => {
  const all = [candidate("a", 0.61), candidate("b", 0.95), candidate("c", 0.8), candidate("d", 0.7)];
  expect(visibleCandidates(all, new Set()).map((c) => c.artisan.id)).toEqual(["b", "c", "d"]);
  expect(visibleCandidates(all, new Set(["b", "c"])).map((c) => c.artisan.id)).toEqual(["d", "a"]);
});

test("a create always sends a list: [] when nothing was set aside, never more than 20", () => {
  expect(acknowledgedIds(new Set())).toEqual([]);
  expect(acknowledgedIds(new Set(["x", "y"]))).toEqual(["x", "y"]);
  expect(acknowledgedIds(new Set(Array.from({ length: 30 }, (_, i) => `id${i}`)))).toHaveLength(20);
});

test("a blur re-checks only when the fields the check reads moved", () => {
  const base = { name: "Giriraj  Prasad", phone: "+91 98290 12345", state: "Rajasthan", district: "Jaipur" };
  expect(checkKey(base)).toBe(checkKey({ ...base, name: "giriraj prasad", phone: "9829012345" }));
  expect(checkKey(base)).not.toBe(checkKey({ ...base, district: "Ajmer" }));
  expect(checkReady({ name: "G" })).toBe(false);
  expect(checkReady({ name: "Gi" })).toBe(true);
});

test("a row reads place · craft · born, and never a date of birth", () => {
  expect(candidateLine(candidate("a", 0.8))).toBe("Bagru · Dabu · born 1971");
  expect(candidateLine(candidate("a", 0.8, { place: null, district: "Jaipur", craft: null, birthYear: null }))).toBe("Jaipur");
});

test("the hold and the merged redirect are read off the server's codes", () => {
  const hold = new ApiError(409, "held", { detail: { code: "artisan_possible_duplicate", message: "This may be X.", candidates: [candidate("a", 0.9)] } });
  expect(possibleDuplicateHold(hold)?.candidates[0].artisan.id).toBe("a");
  const clash = new ApiError(409, "clash", { detail: { code: "artisan_identity_conflict", message: "m" } });
  expect(possibleDuplicateHold(clash)).toBeNull();
  const merged = new ApiError(404, "gone", { detail: { code: "artisan_merged", survivorId: "s1" } });
  expect(mergedSurvivorId(merged)).toBe("s1");
  expect(mergedSurvivorId(new ApiError(404, "Record not found", { detail: "Record not found" }))).toBeNull();
});

test("the form wires the card, the check, the hold and the acknowledged ids — on creates only", () => {
  const form = read("components/forms/ArtisanForm.tsx");
  expect(form).toContain("<PossibleDuplicates");
  expect(form).toContain("<PossibleDuplicateDialog");
  expect(form).toContain("onBlur={scheduleDuplicateCheck}");
  expect(form).toContain("...(initial ? {} : { duplicateAcknowledged: acknowledgedIds(duplicateDismissed) })");
  const card = read("components/duplicates/PossibleDuplicates.tsx");
  expect(card).toContain("Already recorded?");
  expect(card).toContain("Different person");
});
