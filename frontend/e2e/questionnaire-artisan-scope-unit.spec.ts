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

/**
 * THE QUESTIONNAIRE'S ARTISAN PICKER: one control, one workshop scope, and nobody unticked.
 *
 * ── THE TWO DEFECTS THIS FILE STANDS IN FRONT OF ────────────────────────────────────────────────
 *
 *   (1) The picker offered artisans from EVERY workshop under a workshop named in the box above it.
 *       `loadMeta()` ran one `listResource<Artisan>("/artisans", { pageSize: RENDER_CAP })` at mount
 *       with no workshop parameter of any kind and never asked again when the workshop moved. The
 *       endpoint has accepted `workshopIds` and `designWorkshopId` the entire time.
 *   (3) "Primary artisan" and "Additional artisans" were two controls over a join with no rank
 *       column — `QuestionnaireInterviewArtisan` is `@@id([interviewId, artisanId])` plus
 *       `createdAt` — de-duplicated into one array before every request the page made.
 *
 * ── WHY THE RULES ARE UNIT-TESTABLE AT ALL, AND WHY THAT IS THE POINT ───────────────────────────
 *
 * They live in `components/questionnaires/interviewArtisans.ts` as exported functions rather than
 * inside the page component, because the handset owes every one of them and a rule nobody can name
 * is a rule the other client will not copy. Being nameable is what makes them testable here; the
 * test and the portability are the same property.
 *
 * WHAT IS NOT ASSERTED HERE. `useWorkshopArtisans` itself — the hook's invalidate-before-await,
 * its paging and its failure flag — needs a React renderer, and this repository has none in its
 * devDependencies (Playwright is the whole of it). Its ordering half is read as SOURCE at the foot
 * of this file, which is the shape `derived-fields-unit.spec.ts` and
 * `questionnaire-workshop-filter-unit.spec.ts` already use and for the same reason. The structural
 * half — that the page fetches artisans through this module and nothing else — is
 * `backend/tests/test_questionnaire_form_contract.py`, which can see both files at once.
 */

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

// ── (1) THE SCOPE GOES ON THE WIRE, AND EXACTLY ONE OF IT ──────────────────────────────────────

test("a named ordinary workshop is sent as the PLURAL workshopIds and nothing else", () => {
  const params = workshopArtisanParams({ workshopId: "w1", designWorkshopId: "" }, 1);

  expect(params.workshopIds, "the scope must reach the server").toBe("w1");
  // The singular is a NARROWER filter on this route, not an equivalent one: it reads the column OR
  // the WorkshopArtisan roster, while the plural goes through `artisan_workshop_clause` and also
  // counts having sat in an interview taken at the workshop. `list_artisans` ANDs everything it is
  // given, so sending both would silently intersect down to the singular's answer — and Android
  // sends the plural, so that is the one spelling that breaks parity while looking careful.
  expect(params.workshopId, "the singular must never go with the plural").toBeUndefined();
});

test("both workshops named sends ONE scope, and it is the ordinary workshop's", () => {
  const params = workshopArtisanParams({ workshopId: "w1", designWorkshopId: "dw1" }, 1);

  expect(params.workshopIds).toBe("w1");
  // Measured on the local corpus 2026-09-16: of 774 artisans, 204 carry a `workshopId` and 4 carry a
  // `designWorkshopId`. Since `list_artisans` ANDs its filters, sending both narrows to the handful
  // linked BOTH ways — and an empty picker under a named workshop is this repository's most repeated
  // bug class. The ordinary workshop wins because it is the reading with three ways in.
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

  // An empty picker under "choose a workshop first" would make an interview linked to no workshop
  // unfileable, and that record is legal: both columns are nullable and the owner's ruling keeps
  // "Not linked to a workshop". `resolve_workshop_ids` already spells absent-means-all on the server.
  expect(params.workshopIds).toBeUndefined();
  expect(params.designWorkshopId).toBeUndefined();
  expect(interviewScopeIsNarrowed({ workshopId: "", designWorkshopId: "" })).toBe(false);
});

test("the capped-list noun names the SCOPE, not just the record type", () => {
  // "Showing 100 of 240 artisans at this workshop" answers a different question from "…of 240
  // artisans": under a workshop the reader needs to know that this WORKSHOP has more people than are
  // listed, not that the repository does.
  expect(artisanScopeNoun({ workshopId: "w1", designWorkshopId: "" })).toBe("artisans at this workshop");
  expect(artisanScopeNoun({ workshopId: "", designWorkshopId: "dw1" })).toBe("artisans at this workshop");
  expect(artisanScopeNoun({ workshopId: "", designWorkshopId: "" })).toBe("artisans");
});

// ── THE FIRST REQUEST IS HELD UNTIL THE WORKSHOP PICKER HAS CHOSEN FOR ITSELF ───────────────────

test("the roster request waits while the workshop picker is still finding its own default", () => {
  // `useWorkshopSelection` opens a create form on the most recent workshop the user may submit to
  // and finds it asynchronously. Between mount and the end of that walk `workshopId` is "" — which
  // is indistinguishable, to anything reading the value, from a deliberate "no workshop". Firing
  // during that window shows the repository-wide list under a workshop name that has just appeared
  // in the box above: the reported defect, briefly, on every single load.
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
  // THE ARM THAT MATTERS AND IS EASIEST TO DROP: on a deployment with no workshops at all the probe
  // never runs and `workshopId` stays "" for ever, so a guard that only asked "is workshopId empty"
  // would hold the artisan list hostage for the whole session.
  expect(workshopScopeSettling({ loading: false, touched: false, workshopId: "", workshops: [] })).toBe(false);
});

// ── (3) ONE CONTROL, ONE ORDERED ARRAY, AND WHAT STILL NEEDS A SINGLE ARTISAN ───────────────────

test("anything needing one artisan takes element 0, in the researcher's own tick order", () => {
  // The RESP respondent block and the carry-forward bag. This repository's existing rule —
  // `tools.py` derives a tool's scalar `artisanId` from `artisan_ids[0]`.
  expect(primaryInterviewArtisanId(["a2", "a1", "a3"])).toBe("a2");
  expect(primaryInterviewArtisanId([])).toBe("");
  // Blanks are skipped rather than returned: an empty string at the head would blank a RESP block
  // over a selection that names somebody.
  expect(primaryInterviewArtisanId(["", "a1"])).toBe("a1");
});

/** "Filed under no workshop" — the state 31 of the corpus's 44 interviews are in. */
const UNFILED = { workshopId: "", designWorkshopId: "" };

test("the SET key sorts and the SENT order does not — they are two different facts", () => {
  // The interview is stored once per exact SET of artisans (`artisanSetKey` is `@unique`), and the
  // server's own `artisan_set_key` sorts, so ticking A then B is the same interview as B then A. The
  // ORDER the ids are sent in is the researcher's and is what `primaryInterviewArtisanId` reads, so
  // collapsing the two into one array would make the head of the selection alphabetical.
  expect(artisanSetKey(["a3", "a1", "a2"], UNFILED)).toBe("||a1,a2,a3");
  expect(artisanSetKey(["a1", "a3", "a2"], UNFILED)).toBe(artisanSetKey(["a3", "a2", "a1"], UNFILED));
  expect(artisanSetKey(["a1", "", "a1"], UNFILED), "de-duplicated and blank-free").toBe("||a1");
  // Empty stays `""` and NOT `"||"`: an interview with no artisans is not deduped at all, so there
  // is no key to look up, and the falsy sentinel is what the page's early return reads.
  expect(artisanSetKey([], UNFILED)).toBe("");
  expect(primaryInterviewArtisanId(["a3", "a1"]), "the head is not re-sorted").toBe("a3");
});

test("the WORKSHOP is part of the key, so two workshops are two sittings", () => {
  // Migration `20260920120000_questionnaire_artisan_set_key_scoped` put the scope inside the server's
  // key: one artisan set used to hold one interview across the WHOLE repository, so the same family
  // interviewed at a second workshop folded into the first workshop's row and wrote the second
  // sitting's answers onto it. This client computes the key to decide "is there already an entry for
  // this set?", so if it kept the old spelling it would ask about the wrong row — silently, with the
  // researcher shown no existing entry and their save folding into one they never saw.
  const ids = ["a2", "a1"];
  const atW1 = artisanSetKey(ids, { workshopId: "w1", designWorkshopId: "" });
  const atW2 = artisanSetKey(ids, { workshopId: "w2", designWorkshopId: "" });
  expect(atW1).toBe("w1||a1,a2");
  expect(atW2).toBe("w2||a1,a2");
  expect(atW1, "the same people at two workshops are two different keys").not.toBe(atW2);

  // The two workshop tables are different populations and an id from one must never be read as an id
  // from the other: `designWorkshopId` sits in its own slot, never merged into the first.
  expect(artisanSetKey(ids, { workshopId: "", designWorkshopId: "w1" })).toBe("|w1|a1,a2");
  expect(artisanSetKey(ids, { workshopId: "", designWorkshopId: "w1" })).not.toBe(atW1);

  // Unfiled is a SCOPE and not a missing one — it groups with itself, which is the case the majority
  // of this corpus is in and the one a composite index over two nullable columns would have stopped
  // constraining altogether (NULLs are distinct in a Postgres unique index).
  expect(artisanSetKey(ids, UNFILED)).toBe("||a1,a2");
  expect(artisanSetKey(ids, UNFILED)).toBe(artisanSetKey(["a1", "a2"], UNFILED));

  // The key is built out of `interviewScopeKey`, so "which workshop is this" has one spelling in the
  // file. If that ever stops being true this assertion is the one that says so.
  expect(atW1.startsWith(`${interviewScopeKey({ workshopId: "w1", designWorkshopId: "" })}|`)).toBe(
    true
  );
});

// ── RULE 6: A WORKSHOP CHANGE NEVER UNTICKS ANYBODY ────────────────────────────────────────────

test("the offer is the workshop's roster, and a ticked artisan it does not hold is still drawn", () => {
  const options = artisanPickerOptions({
    scoped: [RAMESH, SITA],
    known: [RAMESH, SITA, ARJUN],
    selectedIds: ["a3"]
  });

  // A multi-select renders its trigger from the labels of the options matching its values, so a
  // ticked id with no option says "Nothing selected" over a selection that is about to be submitted.
  expect(options.map((o) => o.value)).toEqual(["a1", "a2", "a3"]);
  expect(options[2].label).toBe("Arjun Lal - No craft - Bagru");
  // The label is `name - craft - place` because two artisans in a district routinely share a name,
  // and it is what the researcher searches the list by.
  expect(options[0].label).toBe("Ramesh Kumar - Block printing - Bagru");
});

test("nobody unticked is not the same as nobody offered: the roster is never merged into", () => {
  // `known` grows for ever (labels); `scoped` is ASSIGNED from each answer. Merging the two would
  // leave the previous workshop's artisans on offer at the next workshop, which is the reported
  // defect reintroduced by a helper written to prevent a different one.
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

  // Certain: the roster for THIS scope has landed whole and does not hold them.
  expect(artisansNotAtWorkshop({ ...base, loadedForScope: "w1|" })).toEqual(["a3"]);

  // Not landed (also covers a failed request, where `loadedForScope` stays null on purpose). A
  // warning that is usually wrong is a warning nobody reads.
  expect(artisansNotAtWorkshop({ ...base, loadedForScope: null })).toEqual([]);
  expect(artisansNotAtWorkshop({ ...base, loadedForScope: "w9|" })).toEqual([]);
  // Cut: an artisan absent from a truncated list may be perfectly well linked and simply past the
  // cut. The capped-list notice is already on screen saying the list is short; this one must not
  // contradict it.
  expect(
    artisansNotAtWorkshop({ ...base, loadedForScope: "w1|", cut: { noun: "artisans", loaded: 100, total: 240 } })
  ).toEqual([]);
  // No workshop named at all: there is no "this workshop" for anybody to be absent from.
  expect(artisansNotAtWorkshop({ ...base, loadedForScope: "|", scopeKey: "|", narrowed: false })).toEqual([]);
});

test("the sentence names people, states what saving does, and does not read as a warning", () => {
  const one = outOfWorkshopNotice(["Ramesh Kumar"]);
  expect(one).toContain("Ramesh Kumar is not recorded at this workshop yet");
  // The half that stops a researcher unticking a good selection to make a message go away: filing
  // this interview here is precisely what creates the link that would have put them on the roster.
  expect(one).toContain("They stay ticked");
  expect(one).toContain("saving this interview here is what links them to it");

  expect(outOfWorkshopNotice(["Ramesh Kumar", "Sita Devi"])).toContain("Ramesh Kumar, Sita Devi are");
  // Capped at four with a remainder, because a selection of thirty would otherwise print a paragraph.
  expect(outOfWorkshopNotice(["A", "B", "C", "D", "E", "F"])).toContain("A, B, C, D and 2 more are");
  expect(outOfWorkshopNotice([]), "nothing to say is an empty string, not an empty sentence").toBe("");
  expect(outOfWorkshopNotice(["  "])).toBe("");
});

// ── THE FOUR-SENTENCE EMPTY STATE ──────────────────────────────────────────────────────────────

test("an empty roster means one of four things and the picker must not pick the wrong one", () => {
  // Only two of the four are facts about the repository. Printing "No artisans are recorded at this
  // workshop yet" off an empty array while the answer is still coming makes a claim before it
  // exists, and the researcher who believes it goes and creates a duplicate of somebody already there.
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
  // The hook's effect depends on this string rather than on the object, so a re-render that rebuilds
  // an identical scope does not re-fetch the same roster.
  expect(interviewScopeKey({ workshopId: "w1", designWorkshopId: "" })).toBe("w1|");
  expect(interviewScopeKey({ workshopId: "", designWorkshopId: "w1" })).toBe("|w1");
  expect(
    interviewScopeKey({ workshopId: "w1", designWorkshopId: "" }) ===
      interviewScopeKey({ workshopId: "", designWorkshopId: "w1" }),
    "an ordinary workshop and a design workshop with the same id are different scopes"
  ).toBe(false);
});

// ── THE HALVES THAT NEED A RENDERER, READ AS SOURCE ────────────────────────────────────────────
//
// WHY A SOURCE READ. The rules above are pure functions and are called; the two below are lines
// inside a hook, and mounting a hook needs a React renderer this repository does not have. Reading
// the source is what `questionnaire-workshop-filter-unit.spec.ts` and `discarded-work-unit.spec.ts`
// already do, for the same reason, and the limitation is the same: this proves the line is there,
// not that the browser behaves.

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");
const RULES = ["components", "questionnaires", "interviewArtisans.ts"];

test("the offer is invalidated BEFORE the await, not when the replacement lands", () => {
  const source = read(...RULES);
  const effect = source.slice(source.indexOf("// (B) The offer."), source.indexOf("return useMemo("));

  const clear = effect.indexOf("setScoped([]);");
  const fetch = effect.indexOf("await listResource<Artisan>");
  expect(clear, "the roster must be cleared when the workshop changes").toBeGreaterThan(-1);
  expect(fetch).toBeGreaterThan(-1);
  // Assigning the replacement on success and leaving the old rows up until then looks like caution
  // and is not: for the length of a field connection's round trip the picker lists the PREVIOUS
  // workshop's people under the new workshop's name. That is the defect, on every workshop change.
  expect(clear, "invalidate first — a stale roster under a new name is a lie that type-checks").toBeLessThan(fetch);
  expect(
    effect.slice(clear, fetch),
    "the flag every consumer reads to tell 'this roster is about the workshop on screen' from 'some other workshop'"
  ).toContain("setLoadedForScope(null);");
});

test("a failed roster request is said, not swallowed, and never falls back to everybody", () => {
  const source = read(...RULES);
  const effect = source.slice(source.indexOf("// (B) The offer."), source.indexOf("return useMemo("));
  const rescue = effect.slice(effect.indexOf("} catch {"));

  expect(rescue, "an empty offer with no explanation reads as 'this workshop has nobody in it'").toContain(
    "setFailed(true)"
  );
  // Guarded so a request superseded by the next workshop cannot paint a failure over the answer that
  // replaced it.
  expect(rescue).toContain("if (!cancelled)");
  expect(rescue, "the previous workshop's rows must not come back on a failure").not.toContain("setScoped(");
});

test("the page hands the hook a scope built from BOTH of the record's workshop columns", () => {
  // The scope has to be the workshop the interview is FILED UNDER, and the record has two nullable
  // columns for that (R1 keeps both tables). BOTH are read, and reading only `workshopId` would
  // leave the picker unscoped for an interview filed under a design & prototype workshop alone —
  // which is the reported defect, in the one case the owner's ruling made reachable by choosing a
  // type.
  //
  // THE TWO IDS NOW COME OFF ONE CONTROL, and this test used to read `designWorkshop.workshopId`
  // from a second hook. The two-dropdown ruling (2026-09-16) replaced `WorkshopSelect` plus the kind
  // cascade with `useWorkshopPicker`, which exposes both columns and guarantees that AT MOST ONE of
  // them is ever set. So this assertion is unchanged in what it demands — both columns reach the
  // roster's scope — and changed in where they come from. What it can no longer catch is a page
  // that reads one hook and forgets the other, because there is only one hook; what it still
  // catches is a page that drops a column on the way to `workshopArtisanParams`, which is the
  // defect that matters and the one that was actually shipped.
  const page = read("app", "(protected)", "questionnaire", "page.tsx");
  const scope = page.slice(
    page.indexOf("const artisanWorkshopScope"),
    page.indexOf("const knownArtisans")
  );

  expect(scope, "the scope is assembled on this page and handed down").toContain(
    "workshopId: workshop.workshopId"
  );
  expect(scope).toContain("designWorkshopId: workshop.designWorkshopId");
  expect(scope).toContain("useWorkshopArtisans({");
  // The settling guard is passed here rather than computed inside the hook, because it is a fact
  // about the workshop PICKER's own state and the hook is handed only ids.
  expect(scope).toContain("workshopScopeSettling(workshop)");
});

/**
 * THE "ONE FETCH" HALF IS ASSERTED IN `backend/tests/test_questionnaire_form_contract.py` AND NOT
 * HERE, deliberately: the claim is that `/artisans` appears nowhere in `page.tsx`, and this page's
 * own comments quote the defective call verbatim in order to explain it. A raw-text assertion
 * therefore fails on the paragraph describing the fix — which was the first thing this test did. The
 * contract test strips TypeScript comments before it looks, which is the only way to ask that
 * question honestly, and it is also the file that can see the page and the rules module at once.
 */
