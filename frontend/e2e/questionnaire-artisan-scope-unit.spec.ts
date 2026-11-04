import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  artisanPickerEmptyLabel,
  artisanPickerOptions,
  artisansNotAtWorkshop,
  artisanScopeNoun,
  artisanSetKey,
  interviewScopeIsNarrowed,
  interviewScopeKey,
  outOfWorkshopNotice,
  primaryInterviewArtisanId,
  workshopArtisanParams,
  workshopScopeSettling
} from "@/components/questionnaires/interviewArtisans";
import type { Artisan } from "@/lib/types";

const artisan = (id: string, name: string, craft: string | null, place: string): Artisan =>
  ({
    id,
    name,
    place,
    craft: craft ? { id: `c-${id}`, name: craft } : null,
    craftId: craft ? `c-${id}` : null
  }) as unknown as Artisan;

const RAMESH = artisan("a1", "Ramesh Kumar", "Block printing", "Bagru");
const SITA = artisan("a2", "Sita Devi", "Block printing", "Sanganer");
const ARJUN = artisan("a3", "Arjun Lal", null, "Bagru");

test("a named ordinary workshop is sent as the PLURAL workshopIds and nothing else", () => {
  const params = workshopArtisanParams({ workshopId: "w1", designWorkshopId: "" }, 1);

  expect(params.workshopIds, "the scope must reach the server").toBe("w1");
  expect(params.workshopId, "the singular must never go with the plural").toBeUndefined();
});

test("both workshops named sends ONE scope, and it is the ordinary workshop's", () => {
  const params = workshopArtisanParams({ workshopId: "w1", designWorkshopId: "dw1" }, 1);

  expect(params.workshopIds).toBe("w1");
  expect(params.designWorkshopId, "two scopes AND together into almost nobody").toBeUndefined();
});

test("a design & prototype workshop alone is the scope when no ordinary workshop is named", () => {
  const params = workshopArtisanParams({ workshopId: "", designWorkshopId: "dw1" }, 2);

  expect(params.designWorkshopId).toBe("dw1");
  expect(params.workshopIds).toBeUndefined();
  expect(params.page, "pages 2..N carry the same scope").toBe(2);
});

test("no workshop at all sends no scope — absent means every artisan, everywhere in this stack", () => {
  const params = workshopArtisanParams({ workshopId: "", designWorkshopId: "" }, 1);

  expect(params.workshopIds).toBeUndefined();
  expect(params.designWorkshopId).toBeUndefined();
  expect(interviewScopeIsNarrowed({ workshopId: "", designWorkshopId: "" })).toBe(false);
});

test("the capped-list noun names the SCOPE, not just the record type", () => {
  expect(artisanScopeNoun({ workshopId: "w1", designWorkshopId: "" })).toBe("artisans at this workshop");
  expect(artisanScopeNoun({ workshopId: "", designWorkshopId: "dw1" })).toBe("artisans at this workshop");
  expect(artisanScopeNoun({ workshopId: "", designWorkshopId: "" })).toBe("artisans");
});

test("the roster request waits while the workshop picker is still finding its own default", () => {
  expect(workshopScopeSettling({ loading: true, touched: false, workshopId: "", workshops: [] })).toBe(true);
  expect(
    workshopScopeSettling({ loading: false, touched: false, workshopId: "", workshops: [{}, {}] }),
    "the list has landed and the submission-check walk has not finished"
  ).toBe(true);

  expect(workshopScopeSettling({ loading: false, touched: false, workshopId: "w1", workshops: [{}] })).toBe(false);
  expect(
    workshopScopeSettling({ loading: false, touched: true, workshopId: "", workshops: [{}] }),
    "a person chose 'not linked to a workshop' — that is an answer, not a pending default"
  ).toBe(false);
  expect(workshopScopeSettling({ loading: false, touched: false, workshopId: "", workshops: [] })).toBe(false);
});

test("anything needing one artisan takes element 0, in the researcher's own tick order", () => {
  expect(primaryInterviewArtisanId(["a2", "a1", "a3"])).toBe("a2");
  expect(primaryInterviewArtisanId([])).toBe("");
  expect(primaryInterviewArtisanId(["", "a1"])).toBe("a1");
});

const UNFILED = { workshopId: "", designWorkshopId: "" };

test("the SET key sorts and the SENT order does not — they are two different facts", () => {
  expect(artisanSetKey(["a3", "a1", "a2"], UNFILED)).toBe("||a1,a2,a3");
  expect(artisanSetKey(["a1", "a3", "a2"], UNFILED)).toBe(artisanSetKey(["a3", "a2", "a1"], UNFILED));
  expect(artisanSetKey(["a1", "", "a1"], UNFILED), "de-duplicated and blank-free").toBe("||a1");
  expect(artisanSetKey([], UNFILED)).toBe("");
  expect(primaryInterviewArtisanId(["a3", "a1"]), "the head is not re-sorted").toBe("a3");
});

test("the WORKSHOP is part of the key, so two workshops are two sittings", () => {
  const ids = ["a2", "a1"];
  const atW1 = artisanSetKey(ids, { workshopId: "w1", designWorkshopId: "" });
  const atW2 = artisanSetKey(ids, { workshopId: "w2", designWorkshopId: "" });
  expect(atW1).toBe("w1||a1,a2");
  expect(atW2).toBe("w2||a1,a2");
  expect(atW1, "the same people at two workshops are two different keys").not.toBe(atW2);

  expect(artisanSetKey(ids, { workshopId: "", designWorkshopId: "w1" })).toBe("|w1|a1,a2");
  expect(artisanSetKey(ids, { workshopId: "", designWorkshopId: "w1" })).not.toBe(atW1);

  expect(artisanSetKey(ids, UNFILED)).toBe("||a1,a2");
  expect(artisanSetKey(ids, UNFILED)).toBe(artisanSetKey(["a1", "a2"], UNFILED));

  expect(atW1.startsWith(`${interviewScopeKey({ workshopId: "w1", designWorkshopId: "" })}|`)).toBe(
    true
  );
});

test("the offer is the workshop's roster, and a ticked artisan it does not hold is still drawn", () => {
  const options = artisanPickerOptions({
    scoped: [RAMESH, SITA],
    known: [RAMESH, SITA, ARJUN],
    selectedIds: ["a3"]
  });

  expect(options.map((o) => o.value)).toEqual(["a1", "a2", "a3"]);
  expect(options[2].label).toBe("Arjun Lal - No craft - Bagru");
  expect(options[0].label).toBe("Ramesh Kumar - Block printing - Bagru");
});

test("nobody unticked is not the same as nobody offered: the roster is never merged into", () => {
  const options = artisanPickerOptions({ scoped: [], known: [RAMESH, SITA, ARJUN], selectedIds: [] });
  expect(options, "an unticked artisan from another workshop is not offered").toEqual([]);
});

test("the out-of-workshop sentence stays silent unless it is certain", () => {
  const base = {
    selectedIds: ["a3"],
    offeredIds: ["a1", "a2"],
    scopeKey: "w1|",
    narrowed: true,
    cut: null
  };

  expect(artisansNotAtWorkshop({ ...base, loadedForScope: "w1|" })).toEqual(["a3"]);

  expect(artisansNotAtWorkshop({ ...base, loadedForScope: null })).toEqual([]);
  expect(artisansNotAtWorkshop({ ...base, loadedForScope: "w9|" })).toEqual([]);
  expect(
    artisansNotAtWorkshop({ ...base, loadedForScope: "w1|", cut: { noun: "artisans", loaded: 100, total: 240 } })
  ).toEqual([]);
  expect(artisansNotAtWorkshop({ ...base, loadedForScope: "|", scopeKey: "|", narrowed: false })).toEqual([]);
});

test("the sentence names people, states what saving does, and does not read as a warning", () => {
  const one = outOfWorkshopNotice(["Ramesh Kumar"]);
  expect(one).toContain("Ramesh Kumar is not recorded at this workshop yet");
  expect(one).toContain("They stay ticked");
  expect(one).toContain("saving this interview here is what links them to it");

  expect(outOfWorkshopNotice(["Ramesh Kumar", "Sita Devi"])).toContain("Ramesh Kumar, Sita Devi are");
  expect(outOfWorkshopNotice(["A", "B", "C", "D", "E", "F"])).toContain("A, B, C, D and 2 more are");
  expect(outOfWorkshopNotice([]), "nothing to say is an empty string, not an empty sentence").toBe("");
  expect(outOfWorkshopNotice(["  "])).toBe("");
});

test("an empty roster means one of four things and the picker must not pick the wrong one", () => {
  const at = { scopeKey: "w1|", narrowed: true };

  expect(artisanPickerEmptyLabel({ ...at, loadedForScope: null, failed: true })).toContain("could not be loaded");
  expect(artisanPickerEmptyLabel({ ...at, loadedForScope: null, failed: false })).toBe("Loading artisans…");
  expect(artisanPickerEmptyLabel({ ...at, loadedForScope: "w9|", failed: false })).toBe("Loading artisans…");
  expect(artisanPickerEmptyLabel({ ...at, loadedForScope: "w1|", failed: false })).toBe(
    "No artisans are recorded at this workshop yet"
  );
  expect(
    artisanPickerEmptyLabel({ scopeKey: "|", narrowed: false, loadedForScope: "|", failed: false }),
    "with no workshop named, 'at this workshop' is a claim about nothing"
  ).toBe("No artisans are recorded yet");
});

test("the scope key tells two workshops apart, including the empty one", () => {
  expect(interviewScopeKey({ workshopId: "w1", designWorkshopId: "" })).toBe("w1|");
  expect(interviewScopeKey({ workshopId: "", designWorkshopId: "w1" })).toBe("|w1");
  expect(
    interviewScopeKey({ workshopId: "w1", designWorkshopId: "" }) ===
      interviewScopeKey({ workshopId: "", designWorkshopId: "w1" }),
    "an ordinary workshop and a design workshop with the same id are different scopes"
  ).toBe(false);
});

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");
const RULES = ["components", "questionnaires", "interviewArtisans.ts"];

test("the offer is invalidated BEFORE the await, not when the replacement lands", () => {
  const source = read(...RULES);
  const effect = source.slice(source.indexOf("const scopeKey = interviewScopeKey(scope);"), source.indexOf("return useMemo("));

  const clear = effect.indexOf("setScoped([]);");
  const fetch = effect.indexOf("await listResource<Artisan>");
  expect(clear, "the roster must be cleared when the workshop changes").toBeGreaterThan(-1);
  expect(fetch).toBeGreaterThan(-1);
  expect(clear, "invalidate first — a stale roster under a new name is a lie that type-checks").toBeLessThan(fetch);
  expect(
    effect.slice(clear, fetch),
    "the flag every consumer reads to tell 'this roster is about the workshop on screen' from 'some other workshop'"
  ).toContain("setLoadedForScope(null);");
});

test("a failed roster request is said, not swallowed, and never falls back to everybody", () => {
  const source = read(...RULES);
  const effect = source.slice(source.indexOf("const scopeKey = interviewScopeKey(scope);"), source.indexOf("return useMemo("));
  const rescue = effect.slice(effect.indexOf("} catch {"));

  expect(rescue, "an empty offer with no explanation reads as 'this workshop has nobody in it'").toContain(
    "setFailed(true)"
  );
  expect(rescue).toContain("if (!cancelled)");
  expect(rescue, "the previous workshop's rows must not come back on a failure").not.toContain("setScoped(");
});
