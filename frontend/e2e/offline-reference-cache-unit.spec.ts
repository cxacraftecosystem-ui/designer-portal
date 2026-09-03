import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { scopeNoticeLines } from "@/components/designworkshop/StageReferenceField";
import { ApiError } from "@/lib/api";
import {
  clearedLinkKeys,
  danglingCandidates,
  danglingKeys,
  emptyPickerKeys,
  isDanglingReference,
  outboxDanglingSentence,
  outboxSentUnfiledMessage,
  referenceFieldNoun,
  repickEmptyLine,
  UNFILED_BY_CHOICE,
  UNFILED_NO_OPTIONS,
  unfiledLinkReason,
  workshopUnfiledReasons
} from "@/lib/offline";
import {
  DW_CACHEABLE_REFERENCE_MODELS,
  isDwCacheableReferenceModel,
  isWorkshopInternalReferenceModel,
  loadStageReferences,
  narrowedTo,
  referenceCacheKey,
  referenceRecordIsReadable,
  REFERENCE_CACHE_VERSION,
  stageReferenceCacheKey,
  stageReferenceCacheOwner,
  stageReferenceRecordIsReadable
} from "@/lib/referenceCache";
import { cachedListLine } from "@/lib/workshopOptions";

/** Read for the two rules that can only be pinned on the source — see the stage-picker block below. */
const CACHE_SOURCE = readFileSync(join(__dirname, "..", "lib", "referenceCache.ts"), "utf8");

/**
 * THE OFFLINE REFERENCE CONTRACT'S PURE HALF, PINNED WHERE IT CAN ACTUALLY BE CHECKED.
 *
 * ── WHY THESE PARTICULAR FUNCTIONS GET A SPEC AND THE COMPONENTS DO NOT ────────────────────────
 *
 * Every judgement below is only ever REACHED in conditions nobody develops in: a laptop that has
 * never had signal, a laptop that had signal nine days ago, a workshop an administrator deleted at
 * the office between a courtyard save and the next drain. On a desk with a working connection the
 * cached branch, the offline branch and the dangling branch are all unreachable, so a sentence
 * chosen inside a component is a sentence only somebody standing in a village ever sees — which is
 * the same argument `components/ui/selectFilter.ts` and `components/data/cappedList.ts` make for
 * their own split, and the reason `DROPDOWN_DESIGN.md` §3.5 writes the strings as functions.
 *
 * There is no React renderer in devDependencies, so these call the real exports directly.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The cache key — `model__owner__filter`, mirroring `DwReferenceStore.cacheKey`
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("the register cache key", () => {
  test("the whole register and one narrowing are different documents", () => {
    // `unnamed` AND NOT `_`, AND THAT IS INHERITED ON PURPOSE. `DwReferenceStore.cacheKey` uses `_`
    // as the blank-filter placeholder and then passes every segment through a `safeName` that trims
    // `_` off both ends and falls back to `unnamed` — so the placeholder is eaten and the Kotlin key
    // is `artisan__ALL__unnamed` too. It is asserted here rather than "corrected" because a key that
    // differs between the two clients is a rule proved about one that has stopped being a rule about
    // the other; what the key has to be is stable and unambiguous, and it is both.
    expect(referenceCacheKey("artisan")).toBe("artisan__ALL__unnamed");
    expect(referenceCacheKey("artisan", "craft-7")).toBe("artisan__ALL__craft-7");
    // The two must never collide: a craft's roster is a different answer from the whole register,
    // and serving one for the other is how a picker offers artisans of the wrong craft.
    expect(referenceCacheKey("artisan")).not.toBe(referenceCacheKey("artisan", "craft-7"));
  });

  test("the owner segment is written out even though it is always ALL", () => {
    // Kept so this client's key and `DwReferenceStore`'s are the same shape. A rule proved about one
    // stops being a rule about the other the moment they differ by a field.
    for (const model of ["craft", "artisan", "product", "tool"] as const) {
      expect(referenceCacheKey(model).split("__")).toHaveLength(3);
      expect(referenceCacheKey(model).split("__")[1]).toBe("ALL");
    }
  });

  test("a segment can never end in an underscore, so a prefix cannot match half a segment", () => {
    // The Kotlin twin's `safeName` trims for exactly this reason, and its KDoc names the
    // cross-workshop leak that comes back if a segment is allowed to. Nothing reads by prefix on
    // this client today; the key is built to survive the day something does.
    const key = referenceCacheKey("artisan", "craft/7 ");
    expect(key).toBe("artisan__ALL__craft_7");
    for (const segment of key.split("__")) {
      expect(segment.startsWith("_")).toBe(false);
      expect(segment.endsWith("_")).toBe(false);
    }
  });

  test("a blank filter is spelled and does not collapse into the separator", () => {
    // `_` is the placeholder, so `model__ALL___` still parses back as three segments rather than
    // two with a trailing separator.
    expect(referenceCacheKey("tool", "   ")).toBe(referenceCacheKey("tool"));
  });
});

test.describe("narrowing a cached list on the device", () => {
  const rows = [
    { id: "p1", label: "Bandhani stole", hint: "Ram", filterValue: "a1" },
    { id: "p2", label: "Block-printed kurta", hint: "Sita", filterValue: "a2" },
    { id: "p3", label: "Unattributed motif", hint: "", filterValue: "" }
  ];

  test("a parent value narrows to its own children", () => {
    expect(narrowedTo(rows, "a1").map((row) => row.id)).toEqual(["p1", "p3"]);
  });

  test("AN OPTION CARRYING NO PARENT AT ALL IS KEPT, never dropped", () => {
    // A server that stops populating the cascade key would otherwise empty every cascading dropdown
    // in the app, which is a far worse failure than showing a few options too many. Same decision,
    // same words, as `DwReferenceList.narrowedTo`.
    expect(narrowedTo(rows, "a2").map((row) => row.id)).toContain("p3");
  });

  test("no parent value means the whole list", () => {
    expect(narrowedTo(rows, "")).toHaveLength(3);
    expect(narrowedTo(rows, "   ")).toHaveLength(3);
  });
});

test.describe("what may be served out of the register store", () => {
  const record = {
    key: referenceCacheKey("craft"),
    schemaVersion: REFERENCE_CACHE_VERSION,
    model: "craft" as const,
    filteredBy: "",
    fetchedAt: "2026-08-22T09:00:00.000Z",
    items: []
  };

  test("a record from a FUTURE build is refused rather than half-understood", () => {
    // A build that half-decodes a newer record renders a picker with rows silently missing, and a
    // picker missing rows is indistinguishable from a register that never had them.
    expect(referenceRecordIsReadable({ ...record, schemaVersion: REFERENCE_CACHE_VERSION + 1 })).toBe(false);
    expect(referenceRecordIsReadable(record)).toBe(true);
  });

  test("an empty document is READABLE — it is the server's answer, not an absence", () => {
    // Null means "this browser has never asked" (§3.5's empty-because-offline, whose next move is a
    // connection); an empty document means "the server said there are none" (genuinely-empty, whose
    // next move is to create one). Collapsing them is the bug this whole area is about.
    expect(referenceRecordIsReadable({ ...record, items: [] })).toBe(true);
    expect(referenceRecordIsReadable(null)).toBe(false);
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * The DESIGN-WORKSHOP stage pickers — A30-03's residual half, landed 2026-09-03
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * WHY THIS SECTION IS PURE FUNCTIONS AND ONE FAKE STORE RATHER THAN A BROWSER.
 *
 * Same argument as the header: the branch that matters is only ever reached on a laptop with no
 * signal. The KEY, the ALLOW-LIST and the READ-THROUGH's order of operations are the three rules
 * that decide whether a designer in a courtyard gets a roster or an empty panel, and all three are
 * expressible without IndexedDB — `loadStageReferences` takes its `fetch` from the caller, so the
 * network half can be a function that throws.
 *
 * The IndexedDB half (`getCachedStageReferences` / `putCachedStageReferences`) is NOT exercised here
 * and that is stated rather than hidden: this spec process has no `indexedDB`, so both functions
 * take their catch and answer null, and a behavioural test built on that would prove the catch and
 * nothing else. Their one load-bearing rule is pinned on the source instead, below.
 */

const STAGE_FIELD = { key: "artisanRef", label: "Artisan", type: "REF", refModel: "Artisan" } as const;

test.describe("the stage-picker cache key", () => {
  test("an ALL-scoped list is one document for the whole device", () => {
    // The sharing is the FEATURE and not a space saving: the artisan register is the same register
    // in every workshop, so a workshop created offline — one with no server id at all — still picks
    // from the copy some earlier workshop on this laptop downloaded. `DwReferenceStore.cacheOwner`.
    expect(stageReferenceCacheKey("Artisan", "ALL", "ws-1")).toBe("dw__Artisan__ALL");
    expect(stageReferenceCacheKey("Artisan", "ALL", "ws-2")).toBe(stageReferenceCacheKey("Artisan", "ALL", "ws-1"));
    // A blank or absent scope is not WORKSHOP, so it shares too — the registry's own default.
    expect(stageReferenceCacheKey("Artisan", "", "ws-1")).toBe("dw__Artisan__ALL");
    expect(stageReferenceCacheKey("Artisan", null, "ws-1")).toBe("dw__Artisan__ALL");
  });

  test("a WORKSHOP-scoped list is one document PER WORKSHOP", () => {
    // This workshop's sketches are meaningless in another one, and serving one workshop's roster
    // for another is the cross-workshop leak `DwReferenceStore.cacheKey`'s KDoc is about.
    //
    // `DwSketch` now reaches this answer by the STRONGER route below — it is workshop-internal, so
    // the declared scope is not consulted at all — and the declared-scope arm is pinned on a
    // repository model in "a REPOSITORY register still shares one document across workshops".
    expect(stageReferenceCacheKey("DwSketch", "WORKSHOP", "ws-1")).toBe("dw__DwSketch__ws-1");
    expect(stageReferenceCacheKey("DwSketch", "WORKSHOP", "ws-1")).not.toBe(
      stageReferenceCacheKey("DwSketch", "WORKSHOP", "ws-2")
    );
    // Case-insensitively, because `refScope` arrives off the wire and the Kotlin twin compares the
    // same way (`scope.equals("WORKSHOP", ignoreCase = true)`).
    expect(stageReferenceCacheKey("DwSketch", "workshop", "ws-1")).toBe("dw__DwSketch__ws-1");
  });

  test("A WORKSHOP-INTERNAL MODEL KEYS UNDER THE WORKSHOP WHATEVER ITS SCOPE SAYS", () => {
    /*
      THE CROSS-WORKSHOP DISJOINTNESS PIN, and the defect it is made of (2026-09-03). These five
      entities are declared `ref_scope=ALL_SCOPE` in `stage_definitions.py`, or with no scope at all,
      so an owner read off `refScope` filed EVERY workshop's roster, sketches, prototypes, cost
      sheets and final products under one `ALL` document — and served the first workshop's people
      into the second workshop's picker. The server never consults the scope for these either:
      `reference_options` dispatches on `_dw_entity(model)` and `_in_record_options` is "always
      scoped to the workshop whatever the field's declared scope says".
    */
    const internal = ["DwParticipant", "DwPrototype", "DwSketch", "DwCostSheet", "DwFinalProduct"];
    for (const model of internal) {
      for (const scope of ["ALL", "", "WORKSHOP", "workshop", null, undefined]) {
        expect(stageReferenceCacheKey(model, scope, "ws-1"), `${model}/${String(scope)}`).toBe(`dw__${model}__ws-1`);
        expect(stageReferenceCacheKey(model, scope, "ws-1")).not.toBe(stageReferenceCacheKey(model, scope, "ws-2"));
      }
      // The stored record's own `owner` reads through the same rule, so a document keyed per
      // workshop can never describe itself as shared.
      expect(stageReferenceCacheOwner(model, "ALL", "ws-1")).toBe("ws-1");
      // Every one of them is on the allow-list, which is what makes the leak reachable at all.
      expect(isDwCacheableReferenceModel(model)).toBe(true);
    }
  });

  test("a REPOSITORY register still shares one document across workshops", () => {
    // `ALL` is reserved for these, and the sharing is the whole feature: a brand-new workshop in a
    // village picks from the copy some earlier workshop on this laptop downloaded.
    for (const model of ["Artisan", "Craft", "Process", "ProductDocumentation", "ToolDocumentation"]) {
      expect(stageReferenceCacheKey(model, "ALL", "ws-1")).toBe(`dw__${model}__ALL`);
      expect(stageReferenceCacheKey(model, "ALL", "ws-1")).toBe(stageReferenceCacheKey(model, "ALL", "ws-2"));
    }
    // A repository model whose FIELD is WORKSHOP-scoped still keys per workshop. That is the
    // registry's own narrowing (`ref_scope=W_SCOPE` on `interviewRef`, on stage 6's `artisanRef`)
    // and it is untouched by the rule above.
    expect(stageReferenceCacheKey("QuestionnaireInterview", "WORKSHOP", "ws-1")).toBe(
      "dw__QuestionnaireInterview__ws-1"
    );
  });

  test("the internal predicate mirrors the registry's naming and nothing looser", () => {
    // `_dw_entity` matches the model against the registry's entity NAMES, and every `single(…)` /
    // `many(…)` in `stage_definitions.py` names one `Dw` + PascalCase.
    expect(isWorkshopInternalReferenceModel("DwSketch")).toBe(true);
    expect(isWorkshopInternalReferenceModel("DwPrototypeIteration")).toBe(true);
    // `DesignWorkshop` is `De`, not `Dw`. The grant model is refused by the allow-list long before
    // this is asked, and it must not be read as an in-workshop entity on the way there.
    expect(isWorkshopInternalReferenceModel("DesignWorkshop")).toBe(false);
    for (const model of ["Artisan", "Craft", "Process", "QuestionnaireInterview", "Questionnaire"]) {
      expect(isWorkshopInternalReferenceModel(model)).toBe(false);
    }
    // PascalCase after the prefix, so an ordinary word beginning "Dw" is not swept in.
    expect(isWorkshopInternalReferenceModel("Dwelling")).toBe(false);
    expect(isWorkshopInternalReferenceModel("")).toBe(false);
    expect(isWorkshopInternalReferenceModel(null)).toBe(false);
    expect(isWorkshopInternalReferenceModel(undefined)).toBe(false);
  });

  test("the namespace is disjoint from the record-form register keys, by construction", () => {
    // Same object store, same database, no DB_VERSION bump — which is only safe because no register
    // key can begin with a literal `dw` segment. THE COUNT IS NOT THE PROOF and never was: both
    // shapes are three segments, and the file's own comments claimed four until 2026-09-03. What
    // separates them is the leading NAME, against `ReferenceRegister`'s closed union of four.
    expect(stageReferenceCacheKey("Artisan", "ALL", "ws-1").split("__")).toHaveLength(3);
    expect(stageReferenceCacheKey("Artisan", "ALL", "ws-1").split("__")[0]).toBe("dw");
    for (const model of ["craft", "artisan", "product", "tool"] as const) {
      expect(referenceCacheKey(model).split("__")).toHaveLength(3);
      expect(referenceCacheKey(model).split("__")[0]).not.toBe("dw");
      expect(referenceCacheKey(model)).not.toBe(stageReferenceCacheKey(model, "ALL", "ws-1"));
    }
  });

  test("a workshop id with punctuation in it still yields whole, parseable segments", () => {
    // The Kotlin twin's `safeName` trims for exactly this reason: a segment that could end in `_`
    // lets a prefix ending in `__` match HALF a segment, and the cross-workshop leak comes back.
    const key = stageReferenceCacheKey("DwSketch", "WORKSHOP", "ws/1 ");
    expect(key.split("__")).toHaveLength(3);
    for (const segment of key.split("__")) expect(segment.endsWith("_")).toBe(false);
  });
});

test.describe("R6 — which models a stage picker may keep a copy of", () => {
  test("the two grant lists are not on the allow-list and cannot be cached", () => {
    /*
      A stale ACCESS list is wrong in the PERMISSIVE direction: a stored copy of "which workshops may
      I file under" reads a revoked grant as a grant. The register half enforces this with a closed
      union; here the models arrive off the wire as strings, so the enforcement is a closed
      allow-list and everything absent from it is live-only.
    */
    expect(isDwCacheableReferenceModel("Workshop")).toBe(false);
    expect(isDwCacheableReferenceModel("DesignWorkshop")).toBe(false);
    expect(DW_CACHEABLE_REFERENCE_MODELS as readonly string[]).not.toContain("Workshop");
    expect(DW_CACHEABLE_REFERENCE_MODELS as readonly string[]).not.toContain("DesignWorkshop");
  });

  test("a model the registry adds later is live-only until somebody names it", () => {
    // The safe direction: a new REF model behaves exactly as every picker did before this cache
    // existed, rather than being cached on a guess about what it holds.
    expect(isDwCacheableReferenceModel("DwSomethingNew")).toBe(false);
    expect(isDwCacheableReferenceModel("")).toBe(false);
    expect(isDwCacheableReferenceModel(null)).toBe(false);
    expect(isDwCacheableReferenceModel(undefined)).toBe(false);
    // …and the registers a stage picker really does draw are on it.
    for (const model of ["Artisan", "Craft", "DwSketch", "DwParticipant"]) {
      expect(isDwCacheableReferenceModel(model)).toBe(true);
    }
  });
});

test.describe("the read-through, and the rule it must never break", () => {
  /** A payload just real enough for the two things `loadStageReferences` reads: nothing, and `isEmpty`. */
  type FakePayload = { options: { id: string }[] };
  const answer = (count: number): FakePayload => ({
    options: Array.from({ length: count }, (_, index) => ({ id: `a${index}` }))
  });
  const isEmpty = (payload: FakePayload) => payload.options.length === 0;

  test("a model that cannot be cached still loads, live, exactly as it always did", async () => {
    const seen: Array<string | null> = [];
    const outcome = await loadStageReferences<FakePayload>({
      workshopId: "ws-1",
      model: "DesignWorkshop",
      scope: "ALL",
      isEmpty,
      fetch: async () => answer(3),
      onPayload: (_payload, cachedAt) => seen.push(cachedAt)
    });
    // Once, with a live answer. No storage was consulted and none was written — see R6 above.
    expect(seen).toEqual([null]);
    expect(outcome.source).toBe("live");
    expect(outcome.cachedAt).toBeNull();
  });

  test("a failed fetch with nothing stored answers `none` AND CARRIES THE ERROR", async () => {
    /*
      THE ERROR IS CARRIED RATHER THAN SWALLOWED, which is where this differs from
      `loadCachedRegister`. The picker prints the server's own sentence when a load fails outright,
      and a deleted design workshop and a village with no signal are different refusals — flattening
      them into one generic line is the thing this whole area exists to stop a control from doing.
    */
    const refusal = new ApiError(404, "This design workshop no longer exists.", null);
    const outcome = await loadStageReferences<FakePayload>({
      workshopId: "ws-1",
      model: "Artisan",
      scope: "ALL",
      isEmpty,
      fetch: async () => {
        throw refusal;
      },
      onPayload: () => undefined
    });
    expect(outcome.source).toBe("none");
    expect(outcome.error).toBe(refusal);
  });

  test("a picker with no workshop id does not consult storage, and still fetches", async () => {
    // A workshop that exists only on this device has no id the references endpoint would recognise,
    // and an empty owner segment would file every such picker under one shared key.
    const seen: Array<string | null> = [];
    const outcome = await loadStageReferences<FakePayload>({
      workshopId: "",
      model: "Artisan",
      scope: "WORKSHOP",
      isEmpty,
      fetch: async () => answer(2),
      onPayload: (_payload, cachedAt) => seen.push(cachedAt)
    });
    expect(seen).toEqual([null]);
    expect(outcome.source).toBe("live");
  });

  test("the never-overwrite-with-empty rule is the WRITE's, and the write is not awaited", () => {
    /*
      THE RULE ITSELF IS `putCachedStageReferences`', and it is the one rule in this file that can
      empty a picker if it is ever dropped: a server answering `[]` because a permission check
      quietly failed would wipe the roster off a laptop about to lose signal for three days. It is
      pinned on the source because this process has no IndexedDB — the function's own catch would
      make any behavioural assertion here pass for the wrong reason.

      `[]` STILL WRITES WHERE THERE IS NOTHING TO PROTECT: a genuinely empty roster on day one is a
      real answer, and refusing to record it would leave the picker unable to tell "the server said
      there are none" from "this device has never asked" for ever.
    */
    const put = CACHE_SOURCE.slice(CACHE_SOURCE.indexOf("export async function putCachedStageReferences"));
    expect(put.slice(0, 1400), "an empty answer must not overwrite a populated document").toContain(
      "if (isEmpty(payload) && existing && !isEmpty(existing.payload)) return existing;"
    );
    // Not awaited by the read-through: a picker must not wait on storage to draw a list it is
    // already showing, and a refused write is not a failed read.
    const read = CACHE_SOURCE.slice(CACHE_SOURCE.indexOf("export async function loadStageReferences"));
    expect(read.slice(0, 2600)).toContain(
      "void putCachedStageReferences(model, scope, workshopId, landed.value, isEmpty);"
    );
  });

  test("the stored document is refused when it comes from a future build", () => {
    const record = {
      key: stageReferenceCacheKey("Artisan", "ALL", "ws-1"),
      schemaVersion: REFERENCE_CACHE_VERSION,
      model: "Artisan",
      owner: "ALL",
      fetchedAt: "2026-08-22T09:00:00.000Z",
      payload: answer(0)
    };
    expect(stageReferenceRecordIsReadable(record)).toBe(true);
    expect(stageReferenceRecordIsReadable({ ...record, schemaVersion: REFERENCE_CACHE_VERSION + 1 })).toBe(false);
    expect(stageReferenceRecordIsReadable(null)).toBe(false);
  });
});

test.describe("what a cached stage list says about itself", () => {
  test("a cached answer leads with §3.5's sentence, and a live one says nothing extra", () => {
    /*
      THE STAMP IS THE WHOLE POINT OF STORING ONE. Without it a designer cannot tell "this artisan
      has no record" from "this copy is nine days old", which is the judgement the cache exists to
      let them make — and it is FIRST in the list because every other line here is a claim about an
      answer and this one says WHICH answer.
    */
    const payload = { scopedToWorkshop: true, truncated: false, filtered: false, options: [{ id: "a1" }, { id: "a2" }] };
    const cached = scopeNoticeLines(STAGE_FIELD as never, payload as never, "2026-08-22T09:00:00.000Z");
    expect(cached[0]).toContain("2 artisan on this device, last refreshed");
    expect(cached[0]).toContain("before concluding it is not on record");
    // Absent means live, which is what keeps every pre-existing caller and assertion unchanged.
    expect(scopeNoticeLines(STAGE_FIELD as never, payload as never)).toEqual([]);
    expect(scopeNoticeLines(STAGE_FIELD as never, payload as never, null)).toEqual([]);
  });

  test("the stamp does not replace the truncation sentence, it precedes it", () => {
    // A cached page of fifty is still a page of fifty. Dropping the cap sentence offline would draw
    // a truncated list as if it were the whole register — rule 10, in the place nobody can check it.
    const lines = scopeNoticeLines(
      STAGE_FIELD as never,
      { scopedToWorkshop: true, truncated: true, filtered: false, options: [{ id: "a1" }] } as never,
      "2026-08-22T09:00:00.000Z"
    );
    expect(lines).toHaveLength(2);
    expect(lines[0]).toContain("on this device");
    expect(lines[1]).toContain("Only the first 50 matches are listed");
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * §3.5's fifth sentence
 * ──────────────────────────────────────────────────────────────────────────── */

test("the cached-and-stale sentence is §3.5's, and it carries the date", () => {
  // Byte for byte the Kotlin `cachedListLine`. §3.5 says both clients print these word for word, and
  // a second wording of one fact is a second fact as far as a reader is concerned.
  expect(cachedListLine(42, "artisans", "22 Aug 2026")).toBe(
    "42 artisans on this device, last refreshed 22 Aug 2026. If the one you want is missing, " +
      "refresh with a connection before concluding it is not on record."
  );
  // The date is the whole sentence: without it a designer cannot tell "this person has no record"
  // from "this copy is nine days old", which is the judgement the cache exists to let them make.
  expect(cachedListLine(1, "crafts", "3 Mar 2026")).toContain("3 Mar 2026");
});

/* ────────────────────────────────────────────────────────────────────────────
 * R1's sign flipped for a form field — the unfile sentinel
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("which of the two absences an empty link box is in", () => {
  test("a box that HELD a workshop and does not now is a decision, whatever the list looks like", () => {
    // Only a person can produce this combination, and it has to be read as a decision even when the
    // list is empty right now: a record filed under a workshop this browser cannot list still draws
    // its recovered off-page row, so the "none" row stays reachable with the page empty.
    expect(unfiledLinkReason("", "dw-1", false)).toBe(UNFILED_BY_CHOICE);
    expect(unfiledLinkReason("", "dw-1", true)).toBe(UNFILED_BY_CHOICE);
  });

  test("never held one, but there was a list to pick from — also a decision", () => {
    expect(unfiledLinkReason("", "", true)).toBe(UNFILED_BY_CHOICE);
  });

  test("never held one and there was nothing to hold — NOT a decision", () => {
    // The drain says so when the record lands. Reading this as a choice is how a correction composed
    // on the bus home silently strips a link nobody was ever shown.
    expect(unfiledLinkReason("", "", false)).toBe(UNFILED_NO_OPTIONS);
  });

  test("a box holding something has no absence to explain", () => {
    expect(unfiledLinkReason("dw-2", "dw-1", true)).toBeNull();
  });
});

test.describe("what the replay does with that evidence", () => {
  test("only a DECISION becomes an explicit null on the wire", () => {
    const entry = {
      unfiled: workshopUnfiledReasons({ designWorkshop: UNFILED_BY_CHOICE, workshop: UNFILED_NO_OPTIONS })
    };
    expect(clearedLinkKeys(entry)).toEqual(["designWorkshopId"]);
    expect(emptyPickerKeys(entry)).toEqual(["workshopId"]);
  });

  test("a form that mounted only one picker cannot clear the other", () => {
    // Nothing is not a clearance: the column is absent from the map, absent from the replayed body,
    // and the stored value stands.
    expect(workshopUnfiledReasons({ designWorkshop: UNFILED_BY_CHOICE })).toEqual({
      designWorkshopId: UNFILED_BY_CHOICE
    });
  });

  test("an entry from before this existed clears nothing", () => {
    // The whole compatibility story: no evidence, no clearance, and the replay behaves exactly as it
    // did before the field existed.
    expect(clearedLinkKeys({})).toEqual([]);
    expect(emptyPickerKeys({})).toEqual([]);
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * R7's other half — a queued record pointing at an id the server does not have
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("recognising a dangling reference", () => {
  test("a 404 on a create or a correction is one", () => {
    expect(isDanglingReference(new ApiError(404, "Record not found", null))).toBe(true);
  });

  test("a 422 is one only when the server's own words say so", () => {
    // The alternative — every 422 — would offer a re-pick for a refusal about a missing product
    // name, sending the researcher to a dropdown that cannot fix it.
    expect(isDanglingReference(new ApiError(422, "Workshop not found", null))).toBe(true);
    expect(isDanglingReference(new ApiError(422, "productName must not be blank", null))).toBe(false);
  });

  test("a 409 and a plain Error are not", () => {
    // A 409 is a collision with somebody else's record and has its own arm; a bare Error is the
    // network, which the triage table owns.
    expect(isDanglingReference(new ApiError(409, "Aadhaar already recorded", null))).toBe(false);
    expect(isDanglingReference(new Error("Failed to fetch"))).toBe(false);
  });
});

test.describe("which id could be the missing one", () => {
  test("the candidates come back in REFERENCE_FIELD_NOUNS order", () => {
    // Order is load-bearing: two entries refused the same way must never word the same ambiguity two
    // different ways.
    const body = JSON.stringify({ name: "Ram", workshopId: "w1", designWorkshopId: "d1", artisanId: "a1" });
    expect(danglingCandidates(body)).toEqual(["designWorkshopId", "workshopId", "artisanId"]);
  });

  test("a body naming no reference has nothing to re-pick", () => {
    // Such an entry takes the ordinary refusal arm. A re-pick panel with no field in it would be the
    // dead end this whole arm exists to remove, wearing a new costume.
    expect(danglingCandidates(JSON.stringify({ name: "Ram" }))).toEqual([]);
    expect(danglingCandidates("not json")).toEqual([]);
  });

  test("a blank id is not a candidate", () => {
    expect(danglingCandidates(JSON.stringify({ workshopId: "", designWorkshopId: "d1" }))).toEqual([
      "designWorkshopId"
    ]);
  });

  test("the stored marker reads back as the list it is", () => {
    expect(danglingKeys({ danglingField: "designWorkshopId, workshopId" })).toEqual([
      "designWorkshopId",
      "workshopId"
    ]);
    expect(danglingKeys({})).toEqual([]);
  });
});

test.describe("what the researcher is told", () => {
  test("one candidate is named in the words of the box they will reopen", () => {
    const said = outboxDanglingSentence("Record not found", [referenceFieldNoun("designWorkshopId")], 0, false);
    expect(said).toContain("This record points at a design & prototype workshop that is not on the server.");
    // Nothing-is-lost comes BEFORE the server's words, because the control beside this sentence is
    // Discard and a person who has read "the server rejected this" reaches for it.
    expect(said.indexOf("Nothing is lost")).toBeLessThan(said.indexOf("The server said"));
    // Tersened on 2026-09-03 with Android's `outboxDanglingSentence`. The FACT is pinned, not the
    // wording of its justification: "because what is missing is missing on the server" moved into
    // the KDoc, and what a row read standing up beside a Discard button still has to say is that a
    // bare retry is pointless. Nothing was deleted is pinned beside it for the same reason.
    expect(said).toContain("Retrying unchanged gets the same answer");
    expect(said).toContain("nothing was deleted");
  });

  test("more than one candidate is an honest ambiguity, never a guess", () => {
    const said = outboxDanglingSentence("Record not found", ["design & prototype workshop", "workshop"], 2, true);
    expect(said).toContain("This correction points at something that is not on the server.");
    expect(said).toContain("the server's answer does not say which");
    expect(said).toContain("2 file(s)");
  });

  test("zero files omits the clause rather than printing a zero", () => {
    // "and 0 files saved with it" reads as an accusation that something went missing.
    expect(outboxDanglingSentence("Record not found", ["workshop"], 0, false)).not.toContain("0 file");
  });
});

test.describe("the re-pick panel with nothing to offer", () => {
  test("a failed read and an empty scope are different sentences", () => {
    // Their next moves are a connection and an administrator, and somebody sent to the wrong one
    // loses a day. This is the one surface whose whole job is to be a way out.
    expect(repickEmptyLine("workshop", false)).toContain("could not be read just now");
    expect(repickEmptyLine("workshop", true)).toContain("An administrator can give you access to one");
  });

  test("neither promises what §3.5 promises on a form", () => {
    // §3.5's sentences end "this record can be saved without it". Here the record IS saved and it is
    // stuck, so that promise would be true and useless; what is owed instead is the standing fact
    // that nothing goes away while this panel cannot help. Both arms say it, in their own words.
    for (const listed of [true, false]) {
      expect(repickEmptyLine("workshop", listed)).not.toContain("can be saved without it");
    }
    expect(repickEmptyLine("workshop", false)).toContain("Nothing has been lost");
    expect(repickEmptyLine("workshop", true)).toContain("nothing is deleted");
  });
});

test("a record that went up filed under nothing says so, and it is not a failure", () => {
  const said = outboxSentUnfiledMessage("Artisan · Giriraj Prasad", ["design & prototype workshop", "workshop"]);
  expect(said).toContain("was sent, filed under nothing");
  // NO ARTICLES: "no" already carries the determiner.
  expect(said).toContain("there was no design & prototype workshop or workshop to choose from");
  // What the researcher does next, and it is the whole of the remedy.
  expect(said).toContain("Open the record and file it now.");
  /*
    AND THE DISCLAIMER IS GONE, DELIBERATELY (2026-09-03). It read "That was never a claim that none
    exist." — a denial of a claim the app had not made, aimed at a misreading nobody has reported, on
    the one notification here that follows a SUCCESS. Android cut the identical clause the same day.
    Asserted as an ABSENCE so a well-meaning restoration has to delete this line to land.
  */
  expect(said).not.toContain("never a claim");
  // It must not borrow the dangling sentence's remedy: nothing is refused and there is no button.
  expect(said).not.toContain("Re-pick");
});
