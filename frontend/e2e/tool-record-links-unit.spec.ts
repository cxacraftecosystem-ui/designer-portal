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

/**
 * THE TOOL RECORD FORM'S THREE NEW CONTRACTS — the toolkit→English mirror, the multi-craft link, and
 * the artisan list's canonical order.
 *
 * WHY HALF OF THIS IS A SOURCE READ. This repository has no React renderer in its devDependencies —
 * Playwright is the whole of it — so a judgement written inside a component can only be exercised by
 * somebody looking at a screen. The rules that CAN be pure are pure and are CALLED below
 * (`sortArtisansByCraft`, `craftNameForArtisan`, `joinCraftNames`, `artisanPickerOptions`); the rest
 * is read out of the file, exactly as `record-number-bounds-unit.spec.ts` and
 * `record-photo-measure-unit.spec.ts` read theirs. Where a rule could have been pure and is not, the
 * assertion says so rather than pretending the source read is equivalent.
 */

const TOOL_FORM = readFileSync(join(__dirname, "..", "components", "forms", "ToolForm.tsx"), "utf8");

/** Strip block and line comments, so a rule QUOTED in a retirement note is not read as code. */
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

/* ────────────────────────────────────────────────────────────────────────────
 * (A) Toolkit name → English name, and the one-way door
 * ──────────────────────────────────────────────────────────────────────────── */

test("the mirror is armed from the RECORD, once, and an edit that would clobber saved data opens divorced", () => {
  /*
    THE EDGE CASE THAT DECIDES THE WHOLE FEATURE. A stored record whose English name DIFFERS from its
    toolkit name was filled in by somebody on purpose, so the form must open with mirroring OFF —
    otherwise correcting a typo in the toolkit name of an existing tool silently replaces a saved
    English name, and the designer finds out when the report is printed.

    Empty (or whitespace-only) and exactly-equal both open ARMED: there is nothing to protect.
    RAW equality, no trim and no case fold — both columns run through the same server-side
    title-casing, so anything that differs before it differs after it, and a "close enough" test is
    how a name somebody typed comes to be overwritten.
  */
  expect(CODE, "the armed flag is computed from `initial`, at construction").toContain(
    'const mirrorArmed = useRef(\n    !initial || (initial.englishName ?? "").trim() === "" || (initial.englishName ?? "") === (initial.toolkitName ?? "")\n  );'
  );
});

test("the mirror is written at the toolkit box's write site and NEVER in an effect", () => {
  /*
    A `useEffect(() => { if (armed) setEnglishName(toolkitName) }, [toolkitName])` FIRES ON MOUNT. On
    an edit whose stored English name is empty — armed, legitimately — it would write the toolkit
    name into the English box before anybody typed, making the form dirty and changing a saved record
    by merely opening it. That is the exact failure the seed-completion effect already forbids in as
    many words ("silently rewriting it here would make merely OPENING a record change it").
  */
  expect(CODE).toContain("function applyToolkitName(next: string, { user }: { user: boolean }) {");
  expect(CODE).toContain("if (mirrorArmed.current) setEnglishName(next);");
  const effects = CODE.match(/useEffect\([\s\S]*?\n {2}\}, \[[^\]]*\]\);/g) ?? [];
  for (const effect of effects) {
    expect(effect, "an effect mirrors the toolkit name").not.toContain("setEnglishName");
  }
});

test("every writer of the toolkit name goes through the one helper", () => {
  // A second writer that set the state directly would be a path the mirror does not run on, which is
  // how "it works when I type but not when the form fills it in" gets shipped. Today there is
  // exactly one caller — the box itself — and the helper is what a future carry node or seed must
  // use, with `user: false` so a programmatic write does not arm the unsaved-changes guard.
  expect(CODE).toContain("onChange={(next) => applyToolkitName(next, { user: true })}");
  const directWrites = CODE.match(/setToolkitName\(/g) ?? [];
  expect(directWrites.length, "setToolkitName is called outside applyToolkitName").toBe(1);
  expect(CODE).toMatch(/function applyToolkitName[\s\S]{0,200}setToolkitName\(next\);/);
});

test("touching the English box ends the mirror, and clearing it by hand counts as touching it", () => {
  /*
    CLEARING IS AN EDIT. There is deliberately no `if (next)` guard on the divorce: a box somebody
    emptied on purpose must stay empty, and refilling it on the next toolkit keystroke would be the
    form arguing with them. Dictation reaches the same handler, so speaking into the box divorces it
    exactly as typing does — which is why the English box keeps its microphone.
  */
  const english = CODE.slice(CODE.indexOf('name="englishName"'), CODE.indexOf('name="craftName"'));
  expect(english).toContain("setEnglishName(next);");
  expect(english).toContain("mirrorArmed.current = false;");
  expect(english, "the divorce must not be conditional on the new value").not.toMatch(/if \(next\)[\s\S]{0,80}mirrorArmed/);

  // And a PROGRAMMATIC write of the English name — the mirror itself — must never disarm it.
  expect(CODE, "the mirror write must not disarm the mirror").not.toMatch(
    /if \(mirrorArmed\.current\) \{[\s\S]{0,120}mirrorArmed\.current = false/
  );
});

test('"Change" on the carry banner does not touch the English name', () => {
  // The banner carries no name-like field of this record's own, so there is nothing of its doing to
  // undo. Clearing the English box there would be a write the designer did not ask for, on the one
  // box whose whole contract is that the form stops writing it the moment they do.
  const clear = CODE.slice(CODE.indexOf("function clearCarriedContext()"), CODE.indexOf("const artisansForCraft"));
  expect(clear).not.toContain("setEnglishName");
  expect(clear, "the carried links are cleared to empty selections, not to empty strings").toContain("setCraftIds([]);");
  expect(clear).toContain("setArtisanIds([]);");
});

/* ────────────────────────────────────────────────────────────────────────────
 * (B) + (C) The two link pickers
 * ──────────────────────────────────────────────────────────────────────────── */

test("both link pickers are multi-selects, in FieldBlock, and both are searchable", () => {
  /*
    `FieldBlock` AND NOT `Field`. `Field` is a `<label>`, which folds every named descendant into the
    accessible NAME of the control it wraps — the cap notice and the failed-read sentence under these
    pickers would be read out as part of the picker's name — and forwards a stray click to the first
    labelable thing inside it. `FormControls`' own header says a multi-select wants `FieldBlock`.

    `searchable` on both, and it may NOT be left to the option count: crafts and artisans are
    records, and `SEARCH_THRESHOLD` would give one deployment a filter box and another none for the
    same control. That is the standing rule in `SearchableSelectProps.searchable`.
  */
  expect(CODE).toContain('<FieldBlock label="Linked crafts (fills craft name)">');
  expect(CODE).toContain('<FieldBlock label="Linked artisans (fills artisan + place)">');
  const pickers = CODE.match(/<MultiSelectDropdown[\s\S]*?\/>/g) ?? [];
  expect(pickers.length, "two link pickers, and only two").toBe(2);
  for (const picker of pickers) expect(picker, "a record-backed picker with no filter box").toContain("searchable");
  // The single-selects are gone: a `<Select name="craftId">` left behind would be a second control
  // writing the same link.
  expect(CODE).not.toContain('name="craftId"');
  expect(CODE).not.toContain('name="artisanId"');
});

test("the scalar columns are DERIVED from the first of each selection, never stored beside it", () => {
  // `tool.craftId` / `tool.artisanId` keep holding the first of each selection for every filter,
  // index, report and carry-forward that reads them. A `useState` beside the arrays would be a
  // second register for one fact, and the two would drift the first time one path forgot the other.
  expect(CODE).toContain('const craftId = craftIds[0] ?? "";');
  expect(CODE).toContain('const artisanId = artisanIds[0] ?? "";');
  expect(CODE, "no setter for the derived scalars may survive").not.toContain("setCraftId(");
  expect(CODE, "no setter for the derived scalars may survive").not.toContain("setArtisanId(");
});

test("an edit opens with the record's OWN scalar first, then its links", () => {
  /*
    THE ORDER OF THE SEED IS THE WHOLE OF IT, because the server derives `artisanId := artisanIds[0]`
    and REPLACES the link set from the list. `ToolArtisan` predates this multi-select — its rows were
    written by the "assign a tool to multiple artisans" panel, which never included the tool's own
    `artisanId`, and `_order_links` returns them `createdAt asc`. So a tool documented under A and
    assigned to B and C comes back `artisanLinks = [B, C]` with `artisanId = A`; seeded from the
    links alone, the first save would set `artisanId := B` while `artisanName` and `place` (which the
    server does NOT derive) went on naming A. A record that names one person in its id and another in
    its text, written by opening it and pressing Save.

    The craft side is safe by an invariant with a moving part in it — `craftLinks` is sorted by each
    name's position in `craftName`, and a craft RENAMED since the save is appended LAST — so leading
    with the scalar costs nothing when the invariant holds and repairs the record when it does not.
  */
  expect(CODE).toContain("const ids = [...(fallback ? [fallback] : []), ...(linked ?? [])];");
  // Duplicates collapse keeping FIRST occurrence, which is the scalar's position, and blanks go.
  expect(CODE).toContain("all.indexOf(id) === index");
  expect(CODE).toContain("initialLinkIds(initial?.craftLinks?.map((link) => link.craftId), initial?.craftId ?? searchParams.get(\"craftId\"))");
  expect(CODE).toContain("initial?.artisanLinks?.map((link) => link.artisanId),");
  // And the links are NOT re-sorted after it: their order is part of what a save writes back, so
  // re-sorting here would rewrite the record by merely opening it.
  expect(CODE, "the seed must not re-sort the server's order").not.toMatch(/initialLinkIds[\s\S]{0,400}\.sort\(/);
});

test("ticking a SECOND artisan does not rewrite the name and place boxes", () => {
  /*
    `artisanName` and `place` are the two boxes the server deliberately does not derive, so a
    correction typed into either has to survive the save. The single-select could rewrite them on
    every pick because picking anything was picking a different person; a multi-select cannot make
    that assumption — ticking a second artisan leaves `artisanId` where it was, and rewriting from it
    would silently undo a correction, triggered by an unrelated tick.

    ── THE ANCHOR MOVED OUT OF `onArtisansChanged`, AND THE MOVE IS THE FIX FOR A SECOND DEFECT ────
    This used to slice `onArtisansChanged` and read the rule inline there. It is in `applyFirstArtisan`
    now because a CRAFT deselection can move `artisanIds[0]` too — dropping the first artisan changed
    which person the record linked while both boxes went on naming the old one — and one rule written
    in two handlers is two rules waiting to disagree. Both callers are asserted below.
  */
  const rule = CODE.slice(CODE.indexOf("function applyFirstArtisan("), CODE.indexOf("function sittingCraftName("));
  expect(rule).toContain("if (artisan && first !== artisanId) {");
  expect(rule).toContain("setArtisanName(artisan.name);");
  // An emptied selection leaves both boxes alone — they are REQUIRED, and blanking one because a
  // link was removed refuses the save with a bubble on a field nobody touched.
  expect(rule, "an emptied selection must not blank a required box").not.toMatch(/setArtisanName\(""\)/);
  expect(rule, "an emptied selection must not blank a required box").not.toMatch(/setPlace\(""\)/);

  // BOTH handlers route through it, and NEITHER re-derives it. A copy in either would be the drift
  // this move exists to make impossible.
  const handlers = CODE.slice(CODE.indexOf("function onCraftsChanged("), CODE.indexOf("<form ref={formRef}"));
  expect(handlers).toContain("applyFirstArtisan(remaining);");
  expect(handlers).toContain("const artisan = applyFirstArtisan(next);");
  expect(handlers, "the first-changed rule must not be re-derived in a handler").not.toContain("setArtisanName(artisan.name);");
});

test("the save body always states both link lists, and does not sort them", () => {
  /*
    ABSENT MEANS "LEAVE THE LINKS ALONE" AND `[]` MEANS "NO LINKS". This form always knows its full
    selection, so it always states it — otherwise the single edit that could never be saved would be
    the one that REMOVES the last link, which is the argument `heightInches` already carries one
    field along. The scalars are sent beside them and the server derives them from element 0.

    THE ORDER IS THE WIRE CONTRACT: the server preserves `craftIds` order, derives `craftName` by
    joining the names in it, and returns `craftLinks` ordered to match. Sorting here would put a box
    on screen that disagrees with the record the moment it is reopened.
  */
  const payload = CODE.slice(CODE.indexOf("const payload = {"), CODE.indexOf("const outcome = await saveOrQueue"));
  expect(payload).toMatch(/^\s*craftIds,$/m);
  expect(payload).toMatch(/^\s*artisanIds,$/m);
  expect(payload).toContain("artisanId: artisanId || null,");
  expect(payload).toContain("craftId: craftId || null,");
  expect(payload, "the lists must not be re-sorted on the way out").not.toMatch(/craftIds: \[\.\.\.craftIds\]/);
  // The queued path is the same body — `saveOrQueue` serialises whatever it is handed — so a list
  // that reaches the outbox replays as the same list. Nothing in `lib/offline` has to know about it.
  expect(CODE).toContain("body: payload,");
});

test("the carry bag stays singular, and banks the FIRST craft's own name rather than the joined one", () => {
  /*
    `craftName` on this form is the joined string once several crafts are linked, and the banner reads
    its value out as ONE craft — the labelled chip is "Craft — Bandhani, Block Printing"
    (`carryChainItems`, `lib/carryContext.ts`), and `ProductForm` prefills its REQUIRED single "Craft
    name" box with it and then SAVES it against a `craftId` pointing at Bandhani alone. `craftId` in
    the bag is already the first of the selection, so the pair stays consistent only if the name does
    too.

    ── THIS USED TO SLICE THE `const sitting = {` BLOCK AND NOTHING ELSE, AND THAT WAS THE HOLE ────
    The expression was inline in the save path, so the test passed while `onArtisansChanged` — which
    banks on EVERY artisan tick, long before any save — put the joined string in the bag. The window
    was "tick two crafts, tick an artisan, open another form before saving", during which the six
    forms that apply the bag prefilled a required craft name with two crafts' names joined. So the
    assertion is now over EVERY `carry.remember(` in the file: one hoisted helper, every caller.
  */
  const banks = [...CODE.matchAll(/carry\.remember\(/g)].map((match) =>
    CODE.slice(match.index ?? 0, (match.index ?? 0) + 400)
  );
  expect(banks.length, "the banks are read from the file; if they moved, find them").toBeGreaterThanOrEqual(3);

  // The rule lives in ONE function, so a third writer cannot reintroduce the divergence.
  expect(CODE).toContain("function sittingCraftName(fallback: string): string {");

  /*
    ── AND THE FALLBACK IS REFUSED WHEN IT WOULD BE THE JOINED STRING ─────────────────────────────

    This asserted the helper's whole body as one line — `?? fallback` — which pinned the HOISTING and
    nothing else. Hoisting only stopped the two call sites from disagreeing; both of them pass the
    joined box as the fallback, so whenever `craftOptions` could not NAME `craftIds[0]` the bag got
    "Bandhani, Block Printing" anyway, through the lookup missing rather than through the expression
    being inline. That is not exotic: `craftOptions` is the loaded page merged with a NETWORK-only
    by-id recovery, so offline every craft past `/crafts`' 100-row page is unnameable for the life of
    the form. (`embeddedCrafts` — the payload's own hydrated `ToolCraft.craft` rows — is the root fix
    for an EDIT; this arm is what has to hold when even that is absent, which is a create seeded with
    a `craftId` from the query string or the bag, with no signal.)

    So the body is three statements and each is asserted: the lookup, the arm that returns it, and
    the refusal. With nothing ticked, or exactly one craft ticked, the box IS about one craft and the
    fallback is both correct and the useful thing — it is only TWO OR MORE with an unnameable first
    that has no singular answer, and there the honest bank is none. `mergeCarryContext` overwrites a
    field only when the incoming value is truthy and a contradicting `craftId` clears the old name
    first, so an ABSENT craft name is recoverable; a wrong one is stored and exported.
  */
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
    // A bank either says nothing about the craft name (the `sitting` spread does, once, through the
    // object it spreads) or gets it from the one helper. What it may never do is pass the box.
    expect(bank, "the joined string must not be banked as one craft").not.toMatch(/craftName: (payload\.)?craftName\b/);
    if (/craftName:/.test(bank)) expect(bank).toContain("sittingCraftName(");
  }

  // And the save path still routes through it too, so the two sites cannot drift back apart.
  const sitting = CODE.slice(CODE.indexOf("const sitting = {"), CODE.indexOf("if (outcome.queued)"));
  expect(sitting).toContain("craftName: sittingCraftName(payload.craftName)");
});

test("a craft change drops artisans only for the crafts it REMOVED, and re-derives the boxes if the first goes", () => {
  const handler = CODE.slice(CODE.indexOf("function onCraftsChanged("), CODE.indexOf("function onArtisansChanged("));

  /*
    THE FIRST HALF. The rule is handed what the change REMOVED, not merely what is left — "their
    craft is not in the next list" is also true of an artisan whose craft was never ticked, which
    `POST /tools/{id}/artisans` makes an ordinary state (it links an artisan to a tool with no craft
    check at all). Under the wider condition, ADDING a craft condemned every such artisan and the
    save deleted their `ToolArtisan` row. The pure cases are in `capped-lists-unit.spec.ts`.
  */
  expect(handler).toContain("const removed = craftIds.filter((id) => !next.includes(id));");
  expect(handler).toContain("removedCraftIds: removed");

  /*
    THE SECOND HALF, AND IT IS A DIFFERENT DEFECT IN THE SAME HANDLER. `artisanId` is `artisanIds[0]`
    and the server derives neither `artisanName` nor `place` from the links — it stores the body's
    values, so a hand correction survives a save. A deselection that drops the FIRST artisan
    therefore changed which person the record links while both boxes went on reading the old one, and
    the stored row named one person in its id and another in its text.

    The same first-changed rule as `onArtisansChanged`, called from both, so a third writer cannot
    invent a second one — and it must be given the SURVIVING list, not `next`.
  */
  expect(handler).toContain("const remaining = artisanIds.filter((id) => !dropped.includes(id));");
  expect(handler).toContain("applyFirstArtisan(remaining);");
  expect(CODE).toContain("const artisan = applyFirstArtisan(next);");

  const rule = CODE.slice(CODE.indexOf("function applyFirstArtisan("), CODE.indexOf("function sittingCraftName("));
  // Only on a CHANGE of first — a second tick must not undo a correction typed over the first.
  expect(rule).toContain("if (artisan && first !== artisanId) {");
  // And an emptied selection leaves both required boxes alone: `first` is "", so `artisan` is
  // undefined and neither setter runs. Nothing in the rule may write off an empty list.
  expect(rule).toContain('const first = next[0] ?? "";');
  expect(rule, "a required box must not be blanked because a link was removed").not.toContain('setArtisanName("")');
  expect(rule, "a required box must not be blanked because a link was removed").not.toContain('setPlace("")');
});

test("the craft picker refuses a selection whose joined name the wire would reject", () => {
  /*
    `ToolCreate.craftName` / `ToolUpdate.craftName` are `max_length=180` and this form echoes the box
    verbatim in the body, so ~12 ticks — or one press of "Select all 100" — builds a required box the
    server refuses by name. `saveOrQueue` will not queue a 4xx, so online the save is lost; offline
    the body queues and can never drain, and `craftName` is in neither `isDanglingReference` nor
    `REPICK_SOURCES`, so the entry has no control but Discard.

    REFUSED AND NOT TRUNCATED. A truncated box would disagree with the links it was derived from,
    which is the one property `_resolve_craft_links` exists to guarantee (its docstring forbids
    truncating server-side for the same reason), and dropping `craftName` from the body is not
    available: it is REQUIRED on `ToolCreate`.
  */
  expect(CODE).toContain(
    "const verdict = craftSelectionVerdict({ nextCraftIds: next, currentCraftIds: craftIds, crafts: craftOptions });"
  );
  expect(CODE).toContain("setCraftLimitNotice(verdict.notice);");
  // The early return is what leaves the selection alone — without it the sentence would print over a
  // selection that had already been applied.
  expect(CODE).toContain("if (verdict.refuse) return;");
  // SAID, on the control it is about as well as under it.
  expect(CODE).toContain('describedBy={craftLimitNotice ? craftLimitId : undefined}');
  expect(TOOL_FORM).toContain('<p id={craftLimitId} role="alert"');

  // The bound itself is the server's, named once, and measured on the JOINED NAMES rather than on a
  // count of crafts — two 120-character craft names already cross it, and `CraftCreate.name` is
  // itself bounded at 180, so "about a dozen ticks" is the typical case and not the floor.
  expect(CRAFT_NAME_MAX_LENGTH).toBe(180);
  const crafts = [craft("c1", "A".repeat(120)), craft("c2", "B".repeat(120)), craft("c3", "Short")];
  const verdict = (next: string[], current: string[] = []) =>
    craftSelectionVerdict({ nextCraftIds: next, currentCraftIds: current, crafts });

  expect(verdict(["c1"])).toEqual({ refuse: false, notice: "" });
  expect(verdict(["c1", "c3"])).toEqual({ refuse: false, notice: "" });

  // 120 + ", " + 120 = 242, added to a selection that fitted: refused, and said.
  const refused = verdict(["c1", "c2"], ["c1"]);
  expect(refused.refuse).toBe(true);
  expect(refused.notice).toContain("242 characters");
  expect(refused.notice, "the sentence must say nothing was lost").toContain("The selection is unchanged");
  // THE SENTENCE COUNTS CHARACTERS AND NOT CRAFTS, because a ticked craft whose row has not loaded
  // contributes no name — a count of crafts would be measured off a different quantity.
  expect(refused.notice, "a count of crafts is not what was measured").not.toMatch(/Those \d+ crafts/);

  /*
    THE DEAD END THE `currentCraftIds` ARGUMENT EXISTS TO STOP. An edit can open ALREADY over the
    bound — the handset writes the same joined string with no limit of its own, and every build older
    than this one did too — so a rule that looked only at the new selection would refuse the designer's
    unticks as well and leave them holding the one record they cannot repair.

    A change that SHORTENS the name is applied whatever it still measures, and the sentence turns from
    a refusal into an instruction.
  */
  const shrinking = verdict(["c1", "c2"], ["c1", "c2", "c3"]);
  expect(shrinking.refuse, "an untick that improves an over-long selection must go through").toBe(false);
  expect(shrinking.notice).toContain("Still 242 characters");
  expect(shrinking.notice).toContain("Keep unticking");
  // …and once it fits, the sentence goes.
  expect(verdict(["c1"], ["c1", "c2"])).toEqual({ refuse: false, notice: "" });
  // A sideways move that is no shorter is still refused: it is not progress.
  expect(verdict(["c2", "c1"], ["c1", "c2"]).refuse).toBe(true);

  // THE RESIDUAL, PINNED RATHER THAN PAPERED OVER: a craft whose row has not loaded is skipped by
  // `joinCraftNames`, so the measurement can only ever come out SHORT — it never refuses a selection
  // the wire would have taken, and the gap closes when `useRecordsOffPage` fills the row in.
  expect(verdict(["c1", "c2", "not-loaded"], ["c1"])).toEqual(refused);
});

test("one craft selection's roster cache document cannot be another's", () => {
  /*
    `referenceCacheKey` cuts each segment of the document name at 80 characters and a cuid is 25, so
    raw ids put every four-or-more-craft selection sharing its three lexicographically smallest ids
    into ONE document — and cuids sort roughly by creation time, so "select all 178" and any 177 of
    them collide by construction. The form then narrows the rows again client-side, so the extra
    craft's artisans are filtered back out and the MISSING craft's were never fetched: the picker
    goes silently short while `artisansLoadedForCraft` is stamped as this selection's answer.
  */
  /*
    REAL CUIDS, NOT `id1`/`id2`, BECAUSE THE SHAPE IS THE WHOLE POINT. A cuid is 25 characters —
    'c' + a base36 millisecond timestamp + counter + fingerprint + random — so three of them plus
    their separators are 77 and the 80-character cut keeps exactly the first TWO characters of the
    fourth. Those two are 'c' and the most significant digit of the timestamp, which every id in a
    deployment shares: the collision is not a contrived pair, it is every selection of four or more.
  */
  const cuid = (tail: string) => `clx8k2p9a${tail}`.padEnd(25, "0");
  const a = cuid("aaaa");
  const b = cuid("bbbb");
  const c = cuid("cccc");
  const d = cuid("dddd");
  const e = cuid("eeee");

  // The collision, at the shape that used to reach the store: identical for 80 characters.
  const collided = (ids: string[]) => ids.join(",").replace(/,/g, "_").slice(0, 80);
  expect(a).toHaveLength(25);
  expect(collided([a, b, c, d])).toBe(collided([a, b, c, e]));

  // …and separated by the digest, which is what is actually passed as `filterValue`.
  expect(craftRosterCacheKey([a, b, c, d].join(","))).not.toBe(craftRosterCacheKey([a, b, c, e].join(",")));

  // FIXED LENGTH, whatever the selection — the property the whole fix rests on. `safeName` leaves
  // every character of the token alone, so nothing can be cut off the end of it again.
  const long = craftRosterCacheKey(Array.from({ length: 178 }, (_, i) => `c${i}`.padEnd(25, "x")).join(","));
  expect(long.length).toBeLessThan(40);
  expect(long).toMatch(/^crafts178-[0-9a-f]{16}$/);

  // Stable and pure: the same selection is the same document on the next mount, or the cache is a
  // write-only store. The roster key is already sorted by the hook, so this is the whole of it.
  expect(craftRosterCacheKey(`${a},${b}`)).toBe(craftRosterCacheKey(`${a},${b}`));
  expect(craftRosterCacheKey("")).toBe("");

  // Both copies of the picker read the SAME helper, or the two panels drift into two key shapes.
  for (const file of ["recordPickers.ts", "ToolAssignmentSection.tsx"]) {
    const source = readFileSync(join(__dirname, "..", "components", "forms", file), "utf8");
    expect(codeOf(source), file).toContain("filterValue: craftRosterCacheKey(craftRosterKey)");
  }
});

test("the craft-name box follows the selection while there IS one, and is left alone when there is not", () => {
  /*
    The server derives `craftName` from `craftIds` the moment any craft is linked — a queued body
    replayed a fortnight later must still produce a name that agrees with its links — so a hand
    correction cannot survive a craft being ticked. The honest thing is to show that rather than
    leave the box saying something else.

    WITH NOTHING TICKED IT IS LEFT ALONE, which is the same rule and not an exception: the server
    derives nothing from an empty list, so the body's own value stands. Blanking it would empty a
    REQUIRED box because a link was removed, and refuse the save with a bubble on a field the
    designer never touched.

    AND THE GUARD IS ON THE JOINED NAME, NOT ON THE COUNT. `joinCraftNames` skips every id
    `craftOptions` cannot name, so it answers "" for a selection that is not empty — the ordinary
    state while an off-page craft's by-id lookup is in flight, and the permanent state when that
    lookup 403s. `if (next.length)` therefore blanked the REQUIRED box for exactly the tools whose
    craft sorts past the 100-row cut, which is the failure the arm above exists to prevent, reached
    by the other door. Asserted on the SOURCE because this cannot be pure: it is two `setState`
    calls inside a handler, and there is no React renderer in this repository to drive it.
  */
  expect(CODE).toContain("const joined = joinCraftNames(next, craftOptions);\n    if (joined) setCraftName(joined);");
  expect(CODE, "the count guard is the defect, not the fix").not.toContain(
    "if (next.length) setCraftName(joinCraftNames(next, craftOptions));"
  );
});

test("an edit opens showing the craft name that will be STORED, and only once, and not over typing", () => {
  /*
    The body always states `craftIds`, so a PATCH of a tool that has a craft re-derives `craftName`
    server-side and overrides whatever the body carried. Nothing reconciled the box at MOUNT, so a
    tool whose name had been hand-corrected to "Bandhani (Kutch variant)" showed that throughout,
    and a remarks-only save silently rewrote the stored column to "Bandhani".

    THE THREE GUARDS ARE THE TEST. Once (a ref, armed only for an edit), never after the designer
    has typed, and never before every ticked craft can be NAMED — reconciling mid-load would replace
    a two-craft name with a one-craft one and call it the truth.
  */
  expect(CODE, "armed for an edit only").toContain("const craftNameReconciled = useRef(!initial);");
  expect(CODE, "it runs at most once").toContain("if (craftNameReconciled.current) return;");
  expect(CODE, "an empty selection derives nothing server-side, so there is nothing to reconcile").toContain(
    "if (!craftIds.length || typedSinceMount) {"
  );
  expect(CODE, "it waits until every ticked craft can be named").toContain(
    "if (!craftIds.every((id) => craftOptions.some((craft) => craft.id === id))) return;"
  );
  // NOT `markDirty()`: this is the app showing what the record already says, and a form that
  // announces unsaved work before anybody typed is what trains people to click through the guard.
  const effect = CODE.slice(CODE.indexOf("const craftNameReconciled"));
  expect(effect.slice(0, effect.indexOf("}, [craftIds, craftOptions, typedSinceMount]);"))).not.toContain("markDirty");
});

/* ────────────────────────────────────────────────────────────────────────────
 * (C) The canonical artisan order — the pure half
 * ──────────────────────────────────────────────────────────────────────────── */

const CRAFTS = [craft("c-warli", "Warli"), craft("c-ajrakh", "Ajrakh"), craft("c-bandhani", "bandhani")];

test("artisans are ordered by craft name A→Z, then by artisan name A→Z", () => {
  const rows = [
    artisan("a1", "Zubeda", "c-warli"),
    artisan("a2", "Amina", "c-warli"),
    artisan("a3", "Kishan", "c-ajrakh"),
    artisan("a4", "bhavna", "c-bandhani")
  ];
  // "Ajrakh" < "bandhani" < "Warli" under a case-FOLDED comparison, which is the whole point of
  // folding: a lower-case craft name must not sort after every capitalised one.
  expect(sortArtisansByCraft(rows, CRAFTS).map((row) => row.name)).toEqual(["Kishan", "bhavna", "Amina", "Zubeda"]);
});

test("an artisan whose craft this form cannot name sorts LAST, never first", () => {
  // "" sorts before every letter, so the naive key would put every row the form could not explain at
  // the top of a list headed by the craft it was narrowed to.
  const rows = [artisan("a9", "Unknown", null), artisan("a1", "Amina", "c-warli")];
  expect(sortArtisansByCraft(rows, CRAFTS).map((row) => row.id)).toEqual(["a1", "a9"]);
  expect(craftNameForArtisan(rows[0], CRAFTS)).toBe("");
});

test("the order is TOTAL, so neither client depends on its sort being stable", () => {
  // The key ends in the artisan's id, a cuid, which is unique. Two artisans of one craft with the
  // same name still have a settled order, and the two clients cannot disagree about it.
  const rows = [artisan("b", "Amina", "c-warli"), artisan("a", "Amina", "c-warli")];
  expect(sortArtisansByCraft(rows, CRAFTS).map((row) => row.id)).toEqual(["a", "b"]);
  expect(sortArtisansByCraft([...rows].reverse(), CRAFTS).map((row) => row.id)).toEqual(["a", "b"]);
});

test("the craft name comes from the artisan's own record first, then from the ticked crafts", () => {
  /*
    A row rebuilt out of `lib/referenceCache` keeps `craftId` and cannot keep `craft.name` —
    `optionToArtisan` says so — so the ticked crafts are the second answer and the reason this is not
    simply `artisan.craft?.name`. The SELECTED crafts, not the whole register: an artisan of an
    unticked craft cannot be in this list at all, so a name found there would head a craft nobody
    chose.
  */
  expect(craftNameForArtisan(artisan("a", "Amina", "c-warli", "Bagru", "Warli (hydrated)"), CRAFTS)).toBe("Warli (hydrated)");
  expect(craftNameForArtisan(artisan("a", "Amina", "c-warli"), CRAFTS)).toBe("Warli");
  expect(craftNameForArtisan(artisan("a", "Amina", "c-elsewhere"), CRAFTS)).toBe("");
});

test("the picker's rows carry the craft as a GROUP and the place as a searched hint", () => {
  /*
    `groupRows` buckets in FIRST-APPEARANCE order and only moves the ungrouped bucket to the front, so
    handing it this already-sorted array is what makes the headings read A→Z with "Unlinked craft"
    last. The ordering is not restated there and must not be.

    The place is the `hint` and not part of the label: `SelectOption.hint` is SEARCHED as well as
    drawn, so typing a village narrows the list — and with the place in the label as well it would be
    printed twice on one 36px row.
  */
  const rows = [artisan("a2", "Amina", "c-warli"), artisan("a3", "Kishan", "c-ajrakh", "Dhamadka"), artisan("a9", "Nobody", null)];
  const options = artisanPickerOptions(rows, CRAFTS);
  expect(options.map((option) => option.group)).toEqual(["Ajrakh", "Warli", UNLINKED_CRAFT_GROUP]);
  expect(options.map((option) => option.value)).toEqual(["a3", "a2", "a9"]);
  expect(options[0]).toEqual({ value: "a3", label: "Kishan", hint: "Dhamadka", group: "Ajrakh" });
  // A blank place is `undefined` rather than "", so `OptionText` draws no empty second column and
  // `fold()` is not handed a label with two spaces in it.
  expect(artisanPickerOptions([artisan("a", "Amina", "c-warli", "")], CRAFTS)[0].hint).toBeUndefined();
  // Never an id: a cuid on a picker row is unreadable, and `SelectOption.value` is deliberately not
  // searched for exactly that reason.
  expect(artisanPickerOptions([artisan("a", "", "c-warli")], CRAFTS)[0].label).toBe("Unnamed artisan");
});

test("the craft names are joined in TICK order, and an unnameable craft is skipped rather than printed", () => {
  expect(joinCraftNames(["c-warli", "c-ajrakh"], CRAFTS)).toBe("Warli, Ajrakh");
  expect(joinCraftNames(["c-ajrakh", "c-warli"], CRAFTS)).toBe("Ajrakh, Warli");
  expect(joinCraftNames(["c-warli", "c-nowhere"], CRAFTS)).toBe("Warli");
  expect(joinCraftNames([], CRAFTS)).toBe("");
});

/* ────────────────────────────────────────────────────────────────────────────
 * (D) The outbox re-pick, now that one link has TWO spellings in the body
 *
 * A tool queued with no signal carries `craftId`/`artisanId` AND `craftIds`/`artisanIds`. The server
 * validates the LISTS (`_resolve_craft_links` / `_resolve_artisan_links` 404 "Record not found" for
 * any unknown id in them) and then derives the scalar from element 0, so a remedy that rewrites only
 * the scalar is not a remedy at all: the same 404 comes back for ever and the only other control is
 * Discard, which destroys the record and every photograph staged against it.
 * ──────────────────────────────────────────────────────────────────────────── */

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
  // IN PLACE: the list is ordered on the wire — element 0 becomes the column and `craftName` is
  // derived in this sequence — so the replacement stands exactly where the dangling id stood.
  expect(parsed.craftIds).toEqual(["crf_y", "crf_z"]);
  // Nothing else is touched. The record is complete fieldwork and one key in it was wrong.
  expect(parsed.toolkitName).toBe("Chisel set");
  expect(parsed.craftName).toBe("Bandhani");
  expect(parsed.artisanIds).toEqual(["art_a"]);
});

test('"none of them" DROPS the id from the list rather than nulling the list', () => {
  /*
    `"craftIds": null` is refused by `ToolCreate._no_explicit_null_link_list` ("send [] to clear the
    links, or omit the key to leave them alone"), and `saveOrQueue` will not re-queue a 4xx — so
    writing one would destroy the correction by the act of applying it. An emptied list is the
    coherent partner of the explicit `null` written into the column: no links, which is what the
    researcher said.
  */
  const body = bodyWithRepickedReference(queuedTool({ craftIds: ["crf_x"] }), "craftId", null);
  const parsed = JSON.parse(body as string);
  expect(parsed.craftId).toBeNull();
  expect(parsed.craftIds).toEqual([]);

  const artisan = JSON.parse(bodyWithRepickedReference(queuedTool(), "artisanId", null) as string);
  expect(artisan.artisanId).toBeNull();
  expect(artisan.artisanIds).toEqual([]);
  // The craft side of the same body is none of this re-pick's business.
  expect(artisan.craftIds).toEqual(["crf_x", "crf_z"]);
});

test("re-pointing at an id the list already holds does not put it in twice", () => {
  // "This is the same craft as that one" is a legitimate answer, and a body carrying one id twice
  // would make `craftIds[0]`'s derivation depend on a duplicate the designer cannot see.
  const parsed = JSON.parse(bodyWithRepickedReference(queuedTool(), "craftId", "crf_z") as string);
  expect(parsed.craftIds).toEqual(["crf_z"]);
  expect(parsed.craftId).toBe("crf_z");
});

test("a key with no plural sibling rewrites the column alone, and an unparseable body is refused", () => {
  const parsed = JSON.parse(bodyWithRepickedReference(queuedTool({ workshopId: "wsp_1" }), "workshopId", "wsp_2") as string);
  expect(parsed.workshopId).toBe("wsp_2");
  expect(parsed.craftIds).toEqual(["crf_x", "crf_z"]);
  // A body this build did not write is left exactly as it is: a queue entry is fieldwork, and the
  // only door out that is not a successful send is the person-confirmed Discard.
  expect(bodyWithRepickedReference("not json", "craftId", "crf_y")).toBeNull();
  expect(bodyWithRepickedReference("[1,2]", "craftId", "crf_y")).toBeNull();
  // …and so is a list of anything but strings, which nothing here can safely reason about.
  const odd = JSON.parse(bodyWithRepickedReference(queuedTool({ craftIds: [{ id: "crf_x" }] }), "craftId", "crf_y") as string);
  expect(odd.craftIds).toEqual([{ id: "crf_x" }]);
  expect(odd.craftId).toBe("crf_y");
});

test("the plural keys stay OUT of the re-pick registry, and the rewrite is what covers them", () => {
  /*
    `REFERENCE_FIELD_NOUNS` is `services/records.CLEARABLE_KEYS` minus the identity numbers, and
    `bodyWithClearances` writes an explicit `null` into every key it finds there. `craftIds` are not
    columns, so naming one as a candidate would be one refactor away from a replay sending
    `"craftIds": null` — a 422 on the whole save that this outbox will not re-queue. The scalar rides
    beside the list holding element 0 of it, so the sentence the researcher reads is already right;
    only the REWRITE was missing.
  */
  expect(REFERENCE_FIELD_NOUNS.map(([key]) => key)).not.toContain("craftIds");
  expect(REFERENCE_FIELD_NOUNS.map(([key]) => key)).not.toContain("artisanIds");
  expect(danglingCandidates(queuedTool())).toEqual(["artisanId", "craftId"]);
  expect(Object.keys(REFERENCE_LIST_SIBLINGS).every((key) => REFERENCE_FIELD_NOUNS.some(([name]) => name === key))).toBe(
    true
  );
  // The store writer goes through the pure rewrite, or the two drift and only the untested one ships.
  const OFFLINE = codeOf(readFileSync(join(__dirname, "..", "lib", "offline.ts"), "utf8"));
  expect(OFFLINE).toContain("const body = bodyWithRepickedReference(row.body, field, chosen);");
});
