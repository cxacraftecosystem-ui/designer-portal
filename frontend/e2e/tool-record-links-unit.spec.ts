import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  CRAFT_NAME_MAX_LENGTH,
  UNLINKED_CRAFT_GROUP,
  artisanPickerOptions,
  craftNameForArtisan,
  craftRosterCacheKey,
  craftSelectionVerdict,
  joinCraftNames,
  sortArtisansByCraft
} from "@/components/forms/recordPickers";
import {
  bodyWithRepickedReference,
  danglingCandidates,
  REFERENCE_FIELD_NOUNS,
  REFERENCE_LIST_SIBLINGS
} from "@/lib/offline";
import type { Artisan, Craft } from "@/lib/types";

const TOOL_FORM = readFileSync(join(__dirname, "..", "components", "forms", "ToolForm.tsx"), "utf8");

function codeOf(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split(/\r?\n/)
    .filter((line) => !line.trim().startsWith("//"))
    .join("\n");
}

const CODE = codeOf(TOOL_FORM);

const craft = (id: string, name: string): Craft => ({ id, name });
const artisan = (id: string, name: string, craftId: string | null, place = "Bagru", craftName?: string): Artisan =>
  ({
    id,
    name,
    place,
    craftId,
    craft: craftName ? { id: craftId ?? "", name: craftName } : null
  }) as unknown as Artisan;

test("the mirror is armed from the RECORD, once, and an edit that would clobber saved data opens divorced", () => {
  expect(CODE, "the armed flag is computed from `initial`, at construction").toContain(
    'const mirrorArmed = useRef(\n    !initial || (initial.englishName ?? "").trim() === "" || (initial.englishName ?? "") === (initial.toolkitName ?? "")\n  );'
  );
});

test("the mirror is written at the toolkit box's write site and NEVER in an effect", () => {
  expect(CODE).toContain("function applyToolkitName(next: string, { user }: { user: boolean }) {");
  expect(CODE).toContain("if (mirrorArmed.current) setEnglishName(next);");
  const effects = CODE.match(/useEffect\([\s\S]*?\n {2}\}, \[[^\]]*\]\);/g) ?? [];
  for (const effect of effects) {
    expect(effect, "an effect mirrors the toolkit name").not.toContain("setEnglishName");
  }
});

test("every writer of the toolkit name goes through the one helper", () => {
  expect(CODE).toContain("onChange={(next) => applyToolkitName(next, { user: true })}");
  const directWrites = CODE.match(/setToolkitName\(/g) ?? [];
  expect(directWrites.length, "setToolkitName is called outside applyToolkitName").toBe(1);
  expect(CODE).toMatch(/function applyToolkitName[\s\S]{0,200}setToolkitName\(next\);/);
});

test("touching the English box ends the mirror, and clearing it by hand counts as touching it", () => {
  const english = CODE.slice(CODE.indexOf('name="englishName"'), CODE.indexOf('name="craftName"'));
  expect(english).toContain("setEnglishName(next);");
  expect(english).toContain("mirrorArmed.current = false;");
  expect(english, "the divorce must not be conditional on the new value").not.toMatch(/if \(next\)[\s\S]{0,80}mirrorArmed/);

  expect(CODE, "the mirror write must not disarm the mirror").not.toMatch(
    /if \(mirrorArmed\.current\) \{[\s\S]{0,120}mirrorArmed\.current = false/
  );
});

test('"Change" on the carry banner does not touch the English name', () => {
  const clear = CODE.slice(CODE.indexOf("function clearCarriedContext()"), CODE.indexOf("const artisansForCraft"));
  expect(clear).not.toContain("setEnglishName");
  expect(clear, "the carried links are cleared to empty selections, not to empty strings").toContain("setCraftIds([]);");
  expect(clear).toContain("setArtisanIds([]);");
});

test("both link pickers are multi-selects, in FieldBlock, and both are searchable", () => {
  expect(CODE).toContain('<FieldBlock label="Linked crafts" required>');
  expect(CODE).toContain('<FieldBlock label="Linked artisans (fills artisan + place)" fieldKey="artisan">');
  expect(CODE).toMatch(/<CraftSelect\s+multiple/);
  const pickers = CODE.match(/<MultiSelectDropdown[\s\S]*?\/>/g) ?? [];
  expect(pickers.length, "one artisan picker, and only one").toBe(1);
  for (const picker of pickers) expect(picker, "a record-backed picker with no filter box").toContain("searchable");
  expect(CODE).not.toContain('name="craftId"');
  expect(CODE).not.toContain('name="artisanId"');
});

test("the scalar columns are DERIVED from the first of each selection, never stored beside it", () => {
  expect(CODE).toContain('const craftId = craftIds[0] ?? "";');
  expect(CODE).toContain('const artisanId = artisanIds[0] ?? "";');
  expect(CODE, "no setter for the derived scalars may survive").not.toContain("setCraftId(");
  expect(CODE, "no setter for the derived scalars may survive").not.toContain("setArtisanId(");
});

test("an edit opens with the record's OWN scalar first, then its links", () => {
  expect(CODE).toContain("const ids = [...(fallback ? [fallback] : []), ...(linked ?? [])];");
  expect(CODE).toContain("all.indexOf(id) === index");
  expect(CODE).toContain("initialLinkIds(initial?.craftLinks?.map((link) => link.craftId), initial?.craftId ?? searchParams.get(\"craftId\"))");
  expect(CODE).toContain("initial?.artisanLinks?.map((link) => link.artisanId),");
  expect(CODE, "the seed must not re-sort the server's order").not.toMatch(/initialLinkIds[\s\S]{0,400}\.sort\(/);
});

test("ticking a SECOND artisan does not rewrite the name and place boxes", () => {
  const rule = CODE.slice(CODE.indexOf("function applyFirstArtisan("), CODE.indexOf("function sittingCraftName("));
  expect(rule).toContain("if (artisan && first !== artisanId) {");
  expect(rule).toContain("setArtisanName(artisan.name);");
  expect(rule, "an emptied selection must not blank a required box").not.toMatch(/setArtisanName\(""\)/);
  expect(rule, "an emptied selection must not blank a required box").not.toMatch(/setPlace\(""\)/);

  const handlers = CODE.slice(CODE.indexOf("function onCraftsChanged("), CODE.indexOf("<form ref={formRef}"));
  expect(handlers).toContain("applyFirstArtisan(remaining);");
  expect(handlers).toContain("const artisan = applyFirstArtisan(next);");
  expect(handlers, "the first-changed rule must not be re-derived in a handler").not.toContain("setArtisanName(artisan.name);");
});

test("the save body always states both link lists, and does not sort them", () => {
  const payload = CODE.slice(CODE.indexOf("const payload = {"), CODE.indexOf("const outcome = await saveOrQueue"));
  expect(payload).toMatch(/^\s*craftIds,$/m);
  expect(payload).toMatch(/^\s*artisanIds,$/m);
  expect(payload).toContain("artisanId: artisanId || null,");
  expect(payload).toContain("craftId: craftId || null,");
  expect(payload, "the lists must not be re-sorted on the way out").not.toMatch(/craftIds: \[\.\.\.craftIds\]/);
  expect(CODE).toContain("body: payload,");
});

test("the carry bag stays singular, and banks the FIRST craft's own name rather than the joined one", () => {
  const banks = [...CODE.matchAll(/carry\.remember\(/g)].map((match) =>
    CODE.slice(match.index ?? 0, (match.index ?? 0) + 400)
  );
  expect(banks.length, "the banks are read from the file; if they moved, find them").toBeGreaterThanOrEqual(3);

  expect(CODE).toContain("function sittingCraftName(fallback: string): string {");

  const helper = CODE.slice(
    CODE.indexOf("function sittingCraftName(fallback: string): string {"),
    CODE.indexOf("function onCraftsChanged(")
  );
  expect(helper).toContain(
    "const named = craftId ? craftOptions.find((craft) => craft.id === craftId)?.name : null;"
  );
  expect(helper).toContain("if (named) return named;");
  expect(helper, "two or more ticked with an unnameable first must bank NO craft name").toContain(
    'return craftIds.length > 1 ? "" : fallback;'
  );

  for (const bank of banks) {
    expect(bank, "the joined string must not be banked as one craft").not.toMatch(/craftName: (payload\.)?craftName\b/);
    if (/craftName:/.test(bank)) expect(bank).toContain("sittingCraftName(");
  }

  const sitting = CODE.slice(CODE.indexOf("const sitting = {"), CODE.indexOf("if (outcome.queued)"));
  expect(sitting).toContain("craftName: sittingCraftName(payload.craftName)");
});

test("a craft change drops artisans only for the crafts it REMOVED, and re-derives the boxes if the first goes", () => {
  const handler = CODE.slice(CODE.indexOf("function onCraftsChanged("), CODE.indexOf("function onArtisansChanged("));

  expect(handler).toContain("const removed = craftIds.filter((id) => !next.includes(id));");
  expect(handler).toContain("removedCraftIds: removed");

  expect(handler).toContain("const remaining = artisanIds.filter((id) => !dropped.includes(id));");
  expect(handler).toContain("applyFirstArtisan(remaining);");
  expect(CODE).toContain("const artisan = applyFirstArtisan(next);");

  const rule = CODE.slice(CODE.indexOf("function applyFirstArtisan("), CODE.indexOf("function sittingCraftName("));
  expect(rule).toContain("if (artisan && first !== artisanId) {");
  expect(rule).toContain('const first = next[0] ?? "";');
  expect(rule, "a required box must not be blanked because a link was removed").not.toContain('setArtisanName("")');
  expect(rule, "a required box must not be blanked because a link was removed").not.toContain('setPlace("")');
});

test("the craft picker refuses a selection whose joined name the wire would reject", () => {
  expect(CODE).toContain(
    "const verdict = craftSelectionVerdict({ nextCraftIds: next, currentCraftIds: craftIds, crafts: craftOptions });"
  );
  expect(CODE).toContain("setCraftLimitNotice(verdict.notice);");
  expect(CODE).toContain("if (verdict.refuse) return;");
  expect(CODE).toContain('describedBy={craftLimitNotice ? craftLimitId : undefined}');
  expect(TOOL_FORM).toContain('<p id={craftLimitId} role="alert"');

  expect(CRAFT_NAME_MAX_LENGTH).toBe(180);
  const crafts = [craft("c1", "A".repeat(120)), craft("c2", "B".repeat(120)), craft("c3", "Short")];
  const verdict = (next: string[], current: string[] = []) =>
    craftSelectionVerdict({ nextCraftIds: next, currentCraftIds: current, crafts });

  expect(verdict(["c1"])).toEqual({ refuse: false, notice: "" });
  expect(verdict(["c1", "c3"])).toEqual({ refuse: false, notice: "" });

  const refused = verdict(["c1", "c2"], ["c1"]);
  expect(refused.refuse).toBe(true);
  expect(refused.notice).toContain("242 characters");
  expect(refused.notice, "the sentence must say nothing was lost").toContain("The selection is unchanged");
  expect(refused.notice, "a count of crafts is not what was measured").not.toMatch(/Those \d+ crafts/);

  const shrinking = verdict(["c1", "c2"], ["c1", "c2", "c3"]);
  expect(shrinking.refuse, "an untick that improves an over-long selection must go through").toBe(false);
  expect(shrinking.notice).toContain("Still 242 characters");
  expect(shrinking.notice).toContain("Keep unticking");
  expect(verdict(["c1"], ["c1", "c2"])).toEqual({ refuse: false, notice: "" });
  expect(verdict(["c2", "c1"], ["c1", "c2"]).refuse).toBe(true);

  expect(verdict(["c1", "c2", "not-loaded"], ["c1"])).toEqual(refused);
});

test("one craft selection's roster cache document cannot be another's", () => {
  const cuid = (tail: string) => `clx8k2p9a${tail}`.padEnd(25, "0");
  const a = cuid("aaaa");
  const b = cuid("bbbb");
  const c = cuid("cccc");
  const d = cuid("dddd");
  const e = cuid("eeee");

  const collided = (ids: string[]) => ids.join(",").replace(/,/g, "_").slice(0, 80);
  expect(a).toHaveLength(25);
  expect(collided([a, b, c, d])).toBe(collided([a, b, c, e]));

  expect(craftRosterCacheKey([a, b, c, d].join(","))).not.toBe(craftRosterCacheKey([a, b, c, e].join(",")));

  const long = craftRosterCacheKey(Array.from({ length: 178 }, (_, i) => `c${i}`.padEnd(25, "x")).join(","));
  expect(long.length).toBeLessThan(40);
  expect(long).toMatch(/^crafts178-[0-9a-f]{16}$/);

  expect(craftRosterCacheKey(`${a},${b}`)).toBe(craftRosterCacheKey(`${a},${b}`));
  expect(craftRosterCacheKey("")).toBe("");

  for (const file of ["recordPickers.ts", "ToolAssignmentSection.tsx"]) {
    const source = readFileSync(join(__dirname, "..", "components", "forms", file), "utf8");
    expect(codeOf(source), file).toContain("filterValue: craftRosterCacheKey(craftRosterKey)");
  }
});

test("the craft-name box follows the selection while there IS one, and is left alone when there is not", () => {
  expect(CODE).toContain("const joined = joinCraftNames(next, craftOptions);\n    if (joined) setCraftName(joined);");
  expect(CODE, "the count guard is the defect, not the fix").not.toContain(
    "if (next.length) setCraftName(joinCraftNames(next, craftOptions));"
  );
});

test("an edit opens showing the craft name that will be STORED, and only once, and not over typing", () => {
  expect(CODE, "armed for an edit only").toContain("const craftNameReconciled = useRef(!initial);");
  expect(CODE, "it runs at most once").toContain("if (craftNameReconciled.current) return;");
  expect(CODE, "an empty selection derives nothing server-side, so there is nothing to reconcile").toContain(
    "if (!craftIds.length || typedSinceMount) {"
  );
  expect(CODE, "it waits until every ticked craft can be named").toContain(
    "if (!craftIds.every((id) => craftOptions.some((craft) => craft.id === id))) return;"
  );
  const effect = CODE.slice(CODE.indexOf("const craftNameReconciled"));
  expect(effect.slice(0, effect.indexOf("}, [craftIds, craftOptions, typedSinceMount]);"))).not.toContain("markDirty");
});

const CRAFTS = [craft("c-warli", "Warli"), craft("c-ajrakh", "Ajrakh"), craft("c-bandhani", "bandhani")];

test("artisans are ordered by craft name A→Z, then by artisan name A→Z", () => {
  const rows = [
    artisan("a1", "Zubeda", "c-warli"),
    artisan("a2", "Amina", "c-warli"),
    artisan("a3", "Kishan", "c-ajrakh"),
    artisan("a4", "bhavna", "c-bandhani")
  ];
  expect(sortArtisansByCraft(rows, CRAFTS).map((row) => row.name)).toEqual(["Kishan", "bhavna", "Amina", "Zubeda"]);
});

test("an artisan whose craft this form cannot name sorts LAST, never first", () => {
  const rows = [artisan("a9", "Unknown", null), artisan("a1", "Amina", "c-warli")];
  expect(sortArtisansByCraft(rows, CRAFTS).map((row) => row.id)).toEqual(["a1", "a9"]);
  expect(craftNameForArtisan(rows[0], CRAFTS)).toBe("");
});

test("the order is TOTAL, so neither client depends on its sort being stable", () => {
  const rows = [artisan("b", "Amina", "c-warli"), artisan("a", "Amina", "c-warli")];
  expect(sortArtisansByCraft(rows, CRAFTS).map((row) => row.id)).toEqual(["a", "b"]);
  expect(sortArtisansByCraft([...rows].reverse(), CRAFTS).map((row) => row.id)).toEqual(["a", "b"]);
});

test("the craft name comes from the artisan's own record first, then from the ticked crafts", () => {
  expect(craftNameForArtisan(artisan("a", "Amina", "c-warli", "Bagru", "Warli (hydrated)"), CRAFTS)).toBe("Warli (hydrated)");
  expect(craftNameForArtisan(artisan("a", "Amina", "c-warli"), CRAFTS)).toBe("Warli");
  expect(craftNameForArtisan(artisan("a", "Amina", "c-elsewhere"), CRAFTS)).toBe("");
});

test("the picker's rows carry the craft as a GROUP and the place as a searched hint", () => {
  const rows = [artisan("a2", "Amina", "c-warli"), artisan("a3", "Kishan", "c-ajrakh", "Dhamadka"), artisan("a9", "Nobody", null)];
  const options = artisanPickerOptions(rows, CRAFTS);
  expect(options.map((option) => option.group)).toEqual(["Ajrakh", "Warli", UNLINKED_CRAFT_GROUP]);
  expect(options.map((option) => option.value)).toEqual(["a3", "a2", "a9"]);
  expect(options[0]).toEqual({ value: "a3", label: "Kishan", hint: "Dhamadka", group: "Ajrakh" });
  expect(artisanPickerOptions([artisan("a", "Amina", "c-warli", "")], CRAFTS)[0].hint).toBeUndefined();
  expect(artisanPickerOptions([artisan("a", "", "c-warli")], CRAFTS)[0].label).toBe("Unnamed artisan");
});

test("the craft names are joined in TICK order, and an unnameable craft is skipped rather than printed", () => {
  expect(joinCraftNames(["c-warli", "c-ajrakh"], CRAFTS)).toBe("Warli, Ajrakh");
  expect(joinCraftNames(["c-ajrakh", "c-warli"], CRAFTS)).toBe("Ajrakh, Warli");
  expect(joinCraftNames(["c-warli", "c-nowhere"], CRAFTS)).toBe("Warli");
  expect(joinCraftNames([], CRAFTS)).toBe("");
});

const queuedTool = (extra: Record<string, unknown> = {}) =>
  JSON.stringify({
    toolkitName: "Chisel set",
    craftName: "Bandhani",
    craftId: "crf_x",
    craftIds: ["crf_x", "crf_z"],
    artisanId: "art_a",
    artisanIds: ["art_a"],
    ...extra
  });

test("re-pointing a queued tool's craft rewrites the LIST as well as the column", () => {
  const body = bodyWithRepickedReference(queuedTool(), "craftId", "crf_y");
  expect(body).not.toBeNull();
  const parsed = JSON.parse(body as string);
  expect(parsed.craftId).toBe("crf_y");
  expect(parsed.craftIds).toEqual(["crf_y", "crf_z"]);
  expect(parsed.toolkitName).toBe("Chisel set");
  expect(parsed.craftName).toBe("Bandhani");
  expect(parsed.artisanIds).toEqual(["art_a"]);
});

test('"none of them" DROPS the id from the list rather than nulling the list', () => {
  const body = bodyWithRepickedReference(queuedTool({ craftIds: ["crf_x"] }), "craftId", null);
  const parsed = JSON.parse(body as string);
  expect(parsed.craftId).toBeNull();
  expect(parsed.craftIds).toEqual([]);

  const artisan = JSON.parse(bodyWithRepickedReference(queuedTool(), "artisanId", null) as string);
  expect(artisan.artisanId).toBeNull();
  expect(artisan.artisanIds).toEqual([]);
  expect(artisan.craftIds).toEqual(["crf_x", "crf_z"]);
});

test("re-pointing at an id the list already holds does not put it in twice", () => {
  const parsed = JSON.parse(bodyWithRepickedReference(queuedTool(), "craftId", "crf_z") as string);
  expect(parsed.craftIds).toEqual(["crf_z"]);
  expect(parsed.craftId).toBe("crf_z");
});

test("a key with no plural sibling rewrites the column alone, and an unparseable body is refused", () => {
  const parsed = JSON.parse(bodyWithRepickedReference(queuedTool({ workshopId: "wsp_1" }), "workshopId", "wsp_2") as string);
  expect(parsed.workshopId).toBe("wsp_2");
  expect(parsed.craftIds).toEqual(["crf_x", "crf_z"]);
  expect(bodyWithRepickedReference("not json", "craftId", "crf_y")).toBeNull();
  expect(bodyWithRepickedReference("[1,2]", "craftId", "crf_y")).toBeNull();
  const odd = JSON.parse(bodyWithRepickedReference(queuedTool({ craftIds: [{ id: "crf_x" }] }), "craftId", "crf_y") as string);
  expect(odd.craftIds).toEqual([{ id: "crf_x" }]);
  expect(odd.craftId).toBe("crf_y");
});

test("the plural keys stay OUT of the re-pick registry, and the rewrite is what covers them", () => {
  expect(REFERENCE_FIELD_NOUNS.map(([key]) => key)).not.toContain("craftIds");
  expect(REFERENCE_FIELD_NOUNS.map(([key]) => key)).not.toContain("artisanIds");
  expect(danglingCandidates(queuedTool())).toEqual(["artisanId", "craftId"]);
  expect(Object.keys(REFERENCE_LIST_SIBLINGS).every((key) => REFERENCE_FIELD_NOUNS.some(([name]) => name === key))).toBe(
    true
  );
  const OFFLINE = codeOf(readFileSync(join(__dirname, "..", "lib", "offline.ts"), "utf8"));
  expect(OFFLINE).toContain("const body = bodyWithRepickedReference(row.body, field, chosen);");
});
