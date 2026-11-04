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

const CACHE_SOURCE = readFileSync(join(__dirname, "..", "lib", "referenceCache.ts"), "utf8");

test.describe("the register cache key", () => {
  test("the whole register and one narrowing are different documents", () => {
    expect(referenceCacheKey("artisan")).toBe("artisan__ALL__unnamed");
    expect(referenceCacheKey("artisan", "craft-7")).toBe("artisan__ALL__craft-7");
    expect(referenceCacheKey("artisan")).not.toBe(referenceCacheKey("artisan", "craft-7"));
  });

  test("the owner segment is written out even though it is always ALL", () => {
    for (const model of ["craft", "artisan", "product", "tool"] as const) {
      expect(referenceCacheKey(model).split("__")).toHaveLength(3);
      expect(referenceCacheKey(model).split("__")[1]).toBe("ALL");
    }
  });

  test("a segment can never end in an underscore, so a prefix cannot match half a segment", () => {
    const key = referenceCacheKey("artisan", "craft/7 ");
    expect(key).toBe("artisan__ALL__craft_7");
    for (const segment of key.split("__")) {
      expect(segment.startsWith("_")).toBe(false);
      expect(segment.endsWith("_")).toBe(false);
    }
  });

  test("a blank filter is spelled and does not collapse into the separator", () => {
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
    expect(referenceRecordIsReadable({ ...record, schemaVersion: REFERENCE_CACHE_VERSION + 1 })).toBe(false);
    expect(referenceRecordIsReadable(record)).toBe(true);
  });

  test("an empty document is READABLE — it is the server's answer, not an absence", () => {
    expect(referenceRecordIsReadable({ ...record, items: [] })).toBe(true);
    expect(referenceRecordIsReadable(null)).toBe(false);
  });
});

const STAGE_FIELD = { key: "artisanRef", label: "Artisan", type: "REF", refModel: "Artisan" } as const;

test.describe("the stage-picker cache key", () => {
  test("an ALL-scoped list is one document for the whole device", () => {
    expect(stageReferenceCacheKey("Artisan", "ALL", "ws-1")).toBe("dw__Artisan__ALL");
    expect(stageReferenceCacheKey("Artisan", "ALL", "ws-2")).toBe(stageReferenceCacheKey("Artisan", "ALL", "ws-1"));
    expect(stageReferenceCacheKey("Artisan", "", "ws-1")).toBe("dw__Artisan__ALL");
    expect(stageReferenceCacheKey("Artisan", null, "ws-1")).toBe("dw__Artisan__ALL");
  });

  test("a WORKSHOP-scoped list is one document PER WORKSHOP", () => {
    expect(stageReferenceCacheKey("DwSketch", "WORKSHOP", "ws-1")).toBe("dw__DwSketch__ws-1");
    expect(stageReferenceCacheKey("DwSketch", "WORKSHOP", "ws-1")).not.toBe(
      stageReferenceCacheKey("DwSketch", "WORKSHOP", "ws-2")
    );
    expect(stageReferenceCacheKey("DwSketch", "workshop", "ws-1")).toBe("dw__DwSketch__ws-1");
  });

  test("A WORKSHOP-INTERNAL MODEL KEYS UNDER THE WORKSHOP WHATEVER ITS SCOPE SAYS", () => {
    const internal = ["DwParticipant", "DwPrototype", "DwSketch", "DwCostSheet", "DwFinalProduct"];
    for (const model of internal) {
      for (const scope of ["ALL", "", "WORKSHOP", "workshop", null, undefined]) {
        expect(stageReferenceCacheKey(model, scope, "ws-1"), `${model}/${String(scope)}`).toBe(`dw__${model}__ws-1`);
        expect(stageReferenceCacheKey(model, scope, "ws-1")).not.toBe(stageReferenceCacheKey(model, scope, "ws-2"));
      }
      expect(stageReferenceCacheOwner(model, "ALL", "ws-1")).toBe("ws-1");
      expect(isDwCacheableReferenceModel(model)).toBe(true);
    }
  });

  test("a REPOSITORY register still shares one document across workshops", () => {
    for (const model of ["Artisan", "Craft", "Process", "ProductDocumentation", "ToolDocumentation"]) {
      expect(stageReferenceCacheKey(model, "ALL", "ws-1")).toBe(`dw__${model}__ALL`);
      expect(stageReferenceCacheKey(model, "ALL", "ws-1")).toBe(stageReferenceCacheKey(model, "ALL", "ws-2"));
    }
    expect(stageReferenceCacheKey("QuestionnaireInterview", "WORKSHOP", "ws-1")).toBe(
      "dw__QuestionnaireInterview__ws-1"
    );
  });

  test("the internal predicate mirrors the registry's naming and nothing looser", () => {
    expect(isWorkshopInternalReferenceModel("DwSketch")).toBe(true);
    expect(isWorkshopInternalReferenceModel("DwPrototypeIteration")).toBe(true);
    expect(isWorkshopInternalReferenceModel("DesignWorkshop")).toBe(false);
    for (const model of ["Artisan", "Craft", "Process", "QuestionnaireInterview", "Questionnaire"]) {
      expect(isWorkshopInternalReferenceModel(model)).toBe(false);
    }
    expect(isWorkshopInternalReferenceModel("Dwelling")).toBe(false);
    expect(isWorkshopInternalReferenceModel("")).toBe(false);
    expect(isWorkshopInternalReferenceModel(null)).toBe(false);
    expect(isWorkshopInternalReferenceModel(undefined)).toBe(false);
  });

  test("the namespace is disjoint from the record-form register keys, by construction", () => {
    expect(stageReferenceCacheKey("Artisan", "ALL", "ws-1").split("__")).toHaveLength(3);
    expect(stageReferenceCacheKey("Artisan", "ALL", "ws-1").split("__")[0]).toBe("dw");
    for (const model of ["craft", "artisan", "product", "tool"] as const) {
      expect(referenceCacheKey(model).split("__")).toHaveLength(3);
      expect(referenceCacheKey(model).split("__")[0]).not.toBe("dw");
      expect(referenceCacheKey(model)).not.toBe(stageReferenceCacheKey(model, "ALL", "ws-1"));
    }
  });

  test("a workshop id with punctuation in it still yields whole, parseable segments", () => {
    const key = stageReferenceCacheKey("DwSketch", "WORKSHOP", "ws/1 ");
    expect(key.split("__")).toHaveLength(3);
    for (const segment of key.split("__")) expect(segment.endsWith("_")).toBe(false);
  });
});

test.describe("R6 — which models a stage picker may keep a copy of", () => {
  test("the two grant lists are not on the allow-list and cannot be cached", () => {
    expect(isDwCacheableReferenceModel("Workshop")).toBe(false);
    expect(isDwCacheableReferenceModel("DesignWorkshop")).toBe(false);
    expect(DW_CACHEABLE_REFERENCE_MODELS as readonly string[]).not.toContain("Workshop");
    expect(DW_CACHEABLE_REFERENCE_MODELS as readonly string[]).not.toContain("DesignWorkshop");
  });

  test("a model the registry adds later is live-only until somebody names it", () => {
    expect(isDwCacheableReferenceModel("DwSomethingNew")).toBe(false);
    expect(isDwCacheableReferenceModel("")).toBe(false);
    expect(isDwCacheableReferenceModel(null)).toBe(false);
    expect(isDwCacheableReferenceModel(undefined)).toBe(false);
    for (const model of ["Artisan", "Craft", "DwSketch", "DwParticipant"]) {
      expect(isDwCacheableReferenceModel(model)).toBe(true);
    }
  });
});

test.describe("the read-through, and the rule it must never break", () => {
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
    expect(seen).toEqual([null]);
    expect(outcome.source).toBe("live");
    expect(outcome.cachedAt).toBeNull();
  });

  test("a failed fetch with nothing stored answers `none` AND CARRIES THE ERROR", async () => {
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
    const put = CACHE_SOURCE.slice(CACHE_SOURCE.indexOf("export async function putCachedStageReferences"));
    expect(put.slice(0, 1400), "an empty answer must not overwrite a populated document").toContain(
      "if (isEmpty(payload) && existing && !isEmpty(existing.payload)) return existing;"
    );
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
    const payload = { scopedToWorkshop: true, truncated: false, filtered: false, options: [{ id: "a1" }, { id: "a2" }] };
    const cached = scopeNoticeLines(STAGE_FIELD as never, payload as never, "2026-08-22T09:00:00.000Z");
    expect(cached[0]).toContain("2 artisan on this device, last refreshed");
    expect(cached[0]).toContain("before concluding it is not on record");
    expect(scopeNoticeLines(STAGE_FIELD as never, payload as never)).toEqual([]);
    expect(scopeNoticeLines(STAGE_FIELD as never, payload as never, null)).toEqual([]);
  });

  test("the stamp does not replace the truncation sentence, it precedes it", () => {
    const lines = scopeNoticeLines(
      STAGE_FIELD as never,
      { scopedToWorkshop: true, truncated: true, filtered: false, options: [{ id: "a1" }] } as never,
      "2026-08-22T09:00:00.000Z"
    );
    expect(lines).toHaveLength(2);
    expect(lines[0]).toContain("on this device");
    expect(lines[1]).toContain("First 50 matches");
  });
});

test("the cached-and-stale sentence is §3.5's, and it carries the date", () => {
  expect(cachedListLine(42, "artisans", "22 Aug 2026")).toBe(
    "42 artisans on this device, last refreshed 22 Aug 2026. If the one you want is missing, " +
      "refresh with a connection before concluding it is not on record."
  );
  expect(cachedListLine(1, "crafts", "3 Mar 2026")).toContain("3 Mar 2026");
});

test.describe("which of the two absences an empty link box is in", () => {
  test("a box that HELD a workshop and does not now is a decision, whatever the list looks like", () => {
    expect(unfiledLinkReason("", "dw-1", false)).toBe(UNFILED_BY_CHOICE);
    expect(unfiledLinkReason("", "dw-1", true)).toBe(UNFILED_BY_CHOICE);
  });

  test("never held one, but there was a list to pick from — also a decision", () => {
    expect(unfiledLinkReason("", "", true)).toBe(UNFILED_BY_CHOICE);
  });

  test("never held one and there was nothing to hold — NOT a decision", () => {
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
    expect(workshopUnfiledReasons({ designWorkshop: UNFILED_BY_CHOICE })).toEqual({
      designWorkshopId: UNFILED_BY_CHOICE
    });
  });

  test("an entry from before this existed clears nothing", () => {
    expect(clearedLinkKeys({})).toEqual([]);
    expect(emptyPickerKeys({})).toEqual([]);
  });
});

test.describe("recognising a dangling reference", () => {
  test("a 404 on a create or a correction is one", () => {
    expect(isDanglingReference(new ApiError(404, "Record not found", null))).toBe(true);
  });

  test("a 422 is one only when the server's own words say so", () => {
    expect(isDanglingReference(new ApiError(422, "Workshop not found", null))).toBe(true);
    expect(isDanglingReference(new ApiError(422, "productName must not be blank", null))).toBe(false);
  });

  test("a queued save naming a workshop the designer is not on, or one that is gone, still offers a re-pick", () => {
    const gate = readFileSync(join(__dirname, "..", "..", "backend", "app", "services", "record_design_workshop.py"), "utf8");
    const said = /NOT_ON_WORKSHOP = \(\s*"([^"]+)"\s*\)/.exec(gate)?.[1];
    expect(said, "NOT_ON_WORKSHOP is one string literal").toBeTruthy();
    expect(gate).toContain("status_code=status.HTTP_404_NOT_FOUND, detail=NOT_ON_WORKSHOP");
    expect(gate).not.toContain("HTTP_403_FORBIDDEN, detail=NOT_ON_WORKSHOP");

    const refusal = new ApiError(404, said as string, null);
    expect(isDanglingReference(refusal)).toBe(true);
    const queued = JSON.stringify({ name: "Giriraj Prasad", place: "Bhuj", designWorkshopId: "dw_9" });
    expect(danglingCandidates(queued)).toEqual(["designWorkshopId"]);
    const line = outboxDanglingSentence(refusal.message, [referenceFieldNoun("designWorkshopId")], 2, false);
    expect(line).toContain("design & prototype workshop");
    expect(line).toContain(said as string);
    expect(isDanglingReference(new ApiError(403, said as string, null))).toBe(false);
  });

  test("a 409 and a plain Error are not", () => {
    expect(isDanglingReference(new ApiError(409, "Aadhaar already recorded", null))).toBe(false);
    expect(isDanglingReference(new Error("Failed to fetch"))).toBe(false);
  });
});

test.describe("which id could be the missing one", () => {
  test("the candidates come back in REFERENCE_FIELD_NOUNS order", () => {
    const body = JSON.stringify({ name: "Ram", workshopId: "w1", designWorkshopId: "d1", artisanId: "a1" });
    expect(danglingCandidates(body)).toEqual(["designWorkshopId", "workshopId", "artisanId"]);
  });

  test("a body naming no reference has nothing to re-pick", () => {
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
    expect(said).toContain("This record points at a design & prototype workshop that no longer exists.");
    expect(said.indexOf("Nothing is lost")).toBeLessThan(said.indexOf("Reason:"));
    expect(said).toContain("Trying again without a change won't help");
    expect(said).toContain("nothing was deleted");
  });

  test("more than one candidate is an honest ambiguity, never a guess", () => {
    const said = outboxDanglingSentence("Record not found", ["design & prototype workshop", "workshop"], 2, true);
    expect(said).toContain("This correction points at something that no longer exists:");
    expect(said).toContain("a design & prototype workshop or a workshop");
    expect(said).toContain("2 files");
  });

  test("zero files omits the clause rather than printing a zero", () => {
    expect(outboxDanglingSentence("Record not found", ["workshop"], 0, false)).not.toContain("0 file");
  });
});

test.describe("the re-pick panel with nothing to offer", () => {
  test("a failed read and an empty scope are different sentences", () => {
    expect(repickEmptyLine("workshop", false)).toContain("couldn't be loaded just now");
    expect(repickEmptyLine("workshop", true)).toContain("An administrator can give you access to one");
  });

  test("neither promises what §3.5 promises on a form", () => {
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
  expect(said).toContain("there was no design & prototype workshop or workshop to choose from");
  expect(said).toContain("Open the record and file it now.");
  expect(said).not.toContain("never a claim");
  expect(said).not.toContain("Re-pick");
});
