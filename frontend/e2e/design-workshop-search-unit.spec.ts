import { expect, test } from "@playwright/test";

import type { DwDraft } from "@/lib/designWorkshopStore";
import type { DwRegistry } from "@/lib/designWorkshops";
import {
  MAX_HITS,
  buildWorkshopSearchIndex,
  foldForSearch,
  readStageFocus,
  searchWorkshop,
  tokeniseForSearch,
  workshopHitHref,
  type WorkshopSearchHit
} from "@/lib/workshopSearch";

/**
 * `lib/workshopSearch.ts` on its own — no browser, no server, no IndexedDB.
 *
 * WHY THIS IS A NODE SPEC AND NOT A BROWSER ONE. The module is deliberately pure, the same shape as
 * `lib/marketAnalysis.ts`, so that it can be reasoned about here and ported to Kotlin later. Playwright
 * resolves the `@/` alias in the node process (see `zzzz-bd-probe.spec.ts`, which exists to prove
 * exactly that), so these assertions cost no dev server and run in milliseconds.
 *
 * THE ODIA CASE IS THE POINT OF THE FILE. `test("an Odia query finds an Odia value")` fails outright
 * — zero hits — if the tokeniser's character class is written `[\p{L}]+` instead of `[\p{L}\p{M}]+`,
 * because the virama in ତନ୍ତ is category M and shatters the word into two tokens that no prefix
 * probe can reach. That is the bug `backend/app/services/market_analysis.py` was found to have, and
 * the whole reason this spec exists rather than a smoke test of the English path, which passes in
 * both worlds.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * A miniature registry and draft
 *
 * Hand-built rather than fetched: the point of a pure module is that it can be tested without the
 * 496-field registry being up, and a fixture that changes when somebody edits stage 5 would make
 * every failure here ambiguous.
 * ──────────────────────────────────────────────────────────────────────────── */

const REGISTRY: DwRegistry = {
  version: "spec",
  enums: {
    fabricFamily: [
      { value: "COTTON", label: "Cotton" },
      // The token and the label share no word ON PURPOSE — see the ENUM test.
      { value: "MUGA_TUSSAR", label: "Wild silk" }
    ]
  },
  stages: [
    {
      number: 1,
      key: "SETUP",
      title: "Workshop setup",
      purpose: "",
      notes: "",
      optionalStage: false,
      entities: [
        {
          key: "workshopSetup",
          name: "DwWorkshopSetup",
          cardinality: "SINGLETON",
          title: "Workshop setup",
          description: "",
          parent: "",
          labelField: "",
          fields: [
            { key: "craftName", label: "Craft name", type: "TEXT", tier: "BASIC", required: false },
            { key: "craftRef", label: "Craft", type: "REF", tier: "BASIC", required: false, refModel: "Craft" },
            {
              key: "fabricFamily",
              label: "Fabric family",
              type: "ENUM",
              tier: "STANDARD",
              required: false,
              enum: "fabricFamily"
            },
            { key: "background", label: "Background", type: "RICH_TEXT", tier: "BASIC", required: false },
            { key: "retiredNote", label: "Retired note", type: "TEXT", tier: "BASIC", required: false, deprecated: true }
          ]
        }
      ]
    },
    {
      number: 5,
      key: "PROCESS",
      title: "Traditional process",
      purpose: "",
      notes: "",
      optionalStage: false,
      entities: [
        {
          key: "tool",
          name: "DwTool",
          cardinality: "COLLECTION",
          title: "Tools",
          description: "",
          parent: "",
          labelField: "name",
          fields: [
            { key: "name", label: "Tool name", type: "TEXT", tier: "BASIC", required: false },
            { key: "localName", label: "Local name", type: "TEXT", tier: "BASIC", required: false },
            { key: "tags", label: "Tags", type: "TAGS", tier: "STANDARD", required: false },
            { key: "photo", label: "Photograph", type: "IMAGE", tier: "STANDARD", required: false },
            {
              key: "photoCaption",
              label: "Photograph caption",
              type: "TEXT",
              tier: "STANDARD",
              required: false,
              captionFor: "photo"
            }
          ]
        }
      ]
    },
    {
      number: 13,
      key: "PROTOTYPE",
      title: "Prototype development",
      purpose: "",
      notes: "",
      optionalStage: false,
      entities: [
        {
          key: "prototype",
          name: "DwPrototype",
          cardinality: "COLLECTION",
          title: "Prototypes",
          description: "",
          parent: "",
          labelField: "name",
          fields: [{ key: "name", label: "Prototype name", type: "TEXT", tier: "BASIC", required: false }]
        },
        {
          key: "prototypeStageLog",
          name: "DwPrototypeStageLog",
          cardinality: "COLLECTION",
          title: "Stage log",
          description: "",
          parent: "prototype",
          labelField: "stageName",
          fields: [
            { key: "stageName", label: "Stage", type: "TEXT", tier: "BASIC", required: false },
            {
              key: "prototypeRef",
              label: "Prototype",
              type: "REF",
              tier: "BASIC",
              required: false,
              refModel: "DwPrototype"
            }
          ]
        }
      ]
    }
  ]
};

/** A stage of the draft in the shape `designWorkshopStore` holds it. */
function stage(
  stageKey: string,
  singletons: Record<string, Record<string, unknown>>,
  collections: Record<string, Record<string, unknown>[]>
) {
  return {
    stageKey,
    singletons,
    collections,
    removedFrom: [],
    updatedAt: 0,
    dirtyAt: null,
    lastPushedAt: null,
    serverLoadedAt: 0,
    completeness: null,
    failure: null
  };
}

function draftWith(stages: Record<string, ReturnType<typeof stage>>): DwDraft {
  return {
    schemaVersion: 2,
    localId: "dwlocal-spec",
    remoteId: null,
    header: {
      title: "Spec workshop",
      templateId: "DCH_STANDARD",
      status: "DRAFT",
      craftName: null,
      clusterName: null,
      state: null,
      district: null,
      startDate: null,
      endDate: null,
      workshopId: null,
      notes: null,
      workshopCode: null,
      venue: null,
      designerName: null
    },
    headerDirtyAt: null,
    stages,
    createdAt: 0,
    updatedAt: 0,
    registryVersion: "spec",
    ownerUserId: null,
    lastSyncedAt: null,
    failure: null
  } as unknown as DwDraft;
}

const DRAFT = draftWith({
  SETUP: stage(
    "SETUP",
    {
      workshopSetup: {
        // Diacritics a designer will not type: the fold is what makes "sambalpuri" reach it.
        craftName: "Sāmbalpurī bandha",
        craftRef: "cmcraft000000000000000001",
        fabricFamily: "MUGA_TUSSAR",
        background: {
          blocks: [
            { kind: "PARAGRAPH", spans: [{ text: "The ground was left undyed rather than the usual indigo." }] },
            { kind: "BULLET_ITEM", spans: [{ text: "Vat dyeing is done in the shared dye house." }] }
          ]
        },
        retiredNote: "This field is deprecated and no form draws it."
      }
    },
    {}
  ),
  PROCESS: stage("PROCESS", {}, {
    tool: [
      {
        _clientKey: "tool-1",
        name: "Four-treadle pit loom",
        // "gada tanta" — the virama in ତନ୍ତ is the character this whole feature turns on.
        localName: "Gada tanta (ଗାଡ଼ ତନ୍ତ)",
        tags: ["Loom", "Pit"],
        photo: "cmmedia00000000000000001",
        photoCaption: "Pit loom in the weaving room at Barpali."
      }
    ]
  }),
  PROTOTYPE: stage("PROTOTYPE", {}, {
    prototype: [{ _entryId: "cmproto00000000000000001", _clientKey: "proto-1", name: "Phoda kumbha table runner" }],
    prototypeStageLog: [
      { _clientKey: "log-1", stageName: "Warping and beam dressing", prototypeRef: "cmproto00000000000000001" }
    ]
  })
});

const INDEX = buildWorkshopSearchIndex(REGISTRY, DRAFT);

/** Every hit for `query`, so an assertion can name the one it means rather than an array position. */
function hitsFor(query: string): WorkshopSearchHit[] {
  return searchWorkshop(INDEX, query).hits;
}

function only(query: string): WorkshopSearchHit {
  const hits = hitsFor(query);
  expect(hits, `"${query}" matched exactly one field`).toHaveLength(1);
  return hits[0];
}

/** The snippet as one string, with the highlighted runs wrapped, so an assertion can read it. */
function marked(hit: WorkshopSearchHit): string {
  return hit.snippet.segments.map((segment) => (segment.match ? `[${segment.text}]` : segment.text)).join("");
}

/* ────────────────────────────────────────────────────────────────────────────
 * Unicode
 * ──────────────────────────────────────────────────────────────────────────── */

test("an Odia word is ONE token — the virama and the matra are not word boundaries", () => {
  // ଗାଡ଼ ତନ୍ତ = "gada tanta". Two words. With `[\p{L}]+` this is four fragments and the assertion
  // below is what fails first.
  expect(tokeniseForSearch("ଗାଡ଼ ତନ୍ତ")).toEqual(["ଗାଡ଼", "ତନ୍ତ"]);
  // ରଙ୍ଗ = "ranga", colour: one token, four code points, one of them a virama.
  expect(tokeniseForSearch("ରଙ୍ଗ")).toEqual(["ରଙ୍ଗ"]);
});

test("an Odia query finds an Odia value, and says which field it is in", () => {
  const hit = only("ତନ୍ତ");
  expect(hit.stageNumber).toBe(5);
  expect(hit.stageTitle).toBe("Traditional process");
  expect(hit.entityTitle).toBe("Tools");
  expect(hit.recordTitle).toBe("Four-treadle pit loom");
  expect(hit.fieldLabel).toBe("Local name");
  // The highlight covers the WHOLE Odia word, not a fragment of it.
  expect(marked(hit)).toContain("[ତନ୍ତ]");
});

test("an Odia word that merely shares consonants is NOT a hit", () => {
  // "ତନ ଓ ତାର" — "tana o tara", warp and wire. It shares the consonants ତ and ନ with ତନ୍ତ and is a
  // different phrase. With `[\p{L}]+` both sides shatter into the same fragments and this returns a
  // hit a designer cannot tell from a real one, which is the corrosive half of that bug.
  const noise = draftWith({
    PROCESS: stage("PROCESS", {}, { tool: [{ _clientKey: "noise-1", name: "ତନ ଓ ତାର" }] })
  });
  expect(searchWorkshop(buildWorkshopSearchIndex(REGISTRY, noise), "ତନ୍ତ").hits).toHaveLength(0);
});

test("folding never touches an Indic combining mark", () => {
  // If the fold stripped category M rather than U+0300–U+036F, ରଙ୍ଗ would become ରଗ — a word that
  // exists in no language, and one no query could ever reach.
  expect(foldForSearch("ରଙ୍ଗ")).toBe("ରଙ୍ଗ");
  expect(foldForSearch("ଘନଶ୍ୟାମ ମେହେର")).toBe("ଘନଶ୍ୟାମ ମେହେର");
});

test("folding does tolerate the diacritics of transliterated Odia", () => {
  expect(foldForSearch("Sāmbalpurī")).toBe("sambalpuri");
  // Two hits, not one: the craft NAME holds the word, and the craft REF resolves to the same name
  // through the row the picker hydrated. Both are honest answers to "where did I write this".
  const hits = hitsFor("sambalpuri");
  expect(hits.map((hit) => hit.fieldLabel).sort()).toEqual(["Craft", "Craft name"]);
  expect(marked(hits[0])).toContain("[Sāmbalpurī]");
});

/* ────────────────────────────────────────────────────────────────────────────
 * What is indexed, and how
 * ──────────────────────────────────────────────────────────────────────────── */

test("a REF is searched by the label it resolves to, never by its cuid", () => {
  // The stage log names a prototype by id. The word a designer knows is the prototype's NAME, which
  // lives on a row in a different collection of the same stage — and on this device, not the server.
  const hits = hitsFor("phoda kumbha");
  const viaRef = hits.find((hit) => hit.fieldKey === "prototypeRef");
  expect(viaRef, "the reference field itself is a hit for the referenced row's name").toBeTruthy();
  expect(viaRef?.entityTitle).toBe("Stage log");
  expect(viaRef?.recordTitle).toBe("Warping and beam dressing");
  expect(viaRef?.rowKey).toBe("log-1");
  expect(marked(viaRef as WorkshopSearchHit)).toContain("[Phoda]");

  // And the cuid is not searchable, because it is not text anybody wrote.
  expect(hitsFor("cmproto00000000000000001").length).toBe(0);
});

test("a REF to a record outside the workshop resolves through the name already on the row", () => {
  // `Craft` is not an entity of this registry — there is nothing offline to look it up in — so the
  // label comes from `referenceDisplayHint`, which reads the name the picker hydrated onto the row.
  const hits = hitsFor("bandha");
  expect(hits.map((hit) => hit.fieldKey).sort()).toEqual(["craftName", "craftRef"]);
});

test("RICH_TEXT is indexed as prose, not as its stored JSON", () => {
  const hit = only("indigo");
  expect(hit.fieldLabel).toBe("Background");
  expect(marked(hit)).toContain("[indigo]");
  // The document's own vocabulary must not be searchable, or every narrative in the workshop
  // matches "blocks", "spans" and "PARAGRAPH".
  expect(hitsFor("blocks")).toHaveLength(0);
  expect(hitsFor("paragraph")).toHaveLength(0);
  // …and a bullet is still reachable, which is what proves the second block was flattened at all.
  expect(hitsFor("dye house")).toHaveLength(1);
});

test("an ENUM is searched by the label a human sees, not by the stored token", () => {
  const hit = only("wild silk");
  expect(hit.fieldLabel).toBe("Fabric family");
  expect(hitsFor("MUGA_TUSSAR")).toHaveLength(0);
});

test("TAGS are searched", () => {
  // "Pit" is one of the tool's two tags, and it is also a word in its name and in its caption. All
  // three are hits; the tag is the one this test is about.
  expect(hitsFor("pit").map((hit) => hit.fieldKey).sort()).toEqual(["name", "photoCaption", "tags"]);
  expect(hitsFor("pit").find((hit) => hit.fieldKey === "tags")?.fieldLabel).toBe("Tags");
});

test("a caption is searched, and navigates to the media field it is drawn under", () => {
  const hit = only("weaving room");
  expect(hit.fieldKey).toBe("photoCaption");
  expect(hit.fieldLabel).toBe("Photograph caption");
  // The caption input has no wrapper of its own — `FieldGrid` hands it to the media field to draw.
  expect(hit.anchorFieldKey).toBe("photo");
});

test("a deprecated field is not indexed — there is no control to send a designer to", () => {
  expect(hitsFor("deprecated")).toHaveLength(0);
});

test("media ids and numbers are not indexed", () => {
  expect(hitsFor("cmmedia00000000000000001")).toHaveLength(0);
});

/* ────────────────────────────────────────────────────────────────────────────
 * Querying
 * ──────────────────────────────────────────────────────────────────────────── */

test("an empty query shows nothing, not everything", () => {
  const result = searchWorkshop(INDEX, "");
  expect(result.hits).toHaveLength(0);
  expect(result.total).toBe(0);
  expect(result.status).toBe("IDLE");
  // …and the index it was asked against is emphatically not empty.
  expect(INDEX.fieldCount).toBeGreaterThan(5);
  expect(INDEX.stageCount).toBe(3);
});

test("a one-character query is refused rather than answered with the whole workshop", () => {
  expect(searchWorkshop(INDEX, "ତ").status).toBe("TOO_SHORT");
});

test("a query with no letters or digits in it says so", () => {
  const result = searchWorkshop(INDEX, "₹₹₹");
  expect(result.status).toBe("NO_TERMS");
  expect(result.hits).toHaveLength(0);
});

test("terms are ANDed, so typing more narrows", () => {
  expect(hitsFor("loom").length).toBeGreaterThan(1);
  expect(hitsFor("phoda").length).toBeGreaterThan(0);
  expect(hitsFor("loom phoda")).toHaveLength(0);
});

test("a prefix matches, an infix does not", () => {
  expect(hitsFor("indi")).toHaveLength(1);
  // "digo" is inside "indigo". A substring search would return it and then highlight a fragment of
  // a word the designer did not type, which is not what a token index promises.
  expect(hitsFor("digo")).toHaveLength(0);
});

test("an exact token outranks a merely-prefixed one", () => {
  const hits = hitsFor("loom");
  // "Four-treadle pit loom" holds the exact token; "Pit loom in the weaving room…" holds it too, so
  // rank the assertion on the score rather than on which row happens to be first.
  expect(hits[0].score).toBeGreaterThanOrEqual(hits[hits.length - 1].score);
});

test("a snippet quotes the surrounding sentence and marks only what matched", () => {
  const hit = only("indigo");
  expect(hit.snippet.segments.filter((segment) => segment.match)).toHaveLength(1);
  // The bullet that follows is carried too, and its "•" marker with it: `toPlain` keeps list markers
  // because a bulleted list flattened to bare lines reads as one run-on sentence. The newline
  // between the two blocks is collapsed to a space so a result row stays a row.
  expect(marked(hit)).toBe(
    "The ground was left undyed rather than the usual [indigo]. • Vat dyeing is done in the shared dye house."
  );
  expect(hit.snippet.clippedStart).toBe(false);
  expect(hit.snippet.clippedEnd).toBe(false);
});

test("a long value is windowed around the match and says it was clipped", () => {
  const filler = "the loom was dressed and the warp was sized ".repeat(20);
  const long = draftWith({
    SETUP: stage("SETUP", { workshopSetup: { craftName: `${filler} indigo ${filler}` } }, {})
  });
  const hit = searchWorkshop(buildWorkshopSearchIndex(REGISTRY, long), "indigo").hits[0];
  expect(hit.snippet.clippedStart).toBe(true);
  expect(hit.snippet.clippedEnd).toBe(true);
  expect(marked(hit)).toContain("[indigo]");
  // Windowed, not the whole field: a result row must stay one or two lines.
  expect(marked(hit).length).toBeLessThan(300);
});

test("the cap is applied AND announced", () => {
  const many = draftWith({
    PROCESS: stage("PROCESS", {}, {
      tool: Array.from({ length: MAX_HITS + 25 }, (_, index) => ({
        _clientKey: `tool-${index}`,
        name: `Pit loom number ${index}`
      }))
    })
  });
  const result = searchWorkshop(buildWorkshopSearchIndex(REGISTRY, many), "loom");
  expect(result.hits).toHaveLength(MAX_HITS);
  expect(result.truncated).toBe(true);
  expect(result.total).toBe(MAX_HITS + 25);
});

/* ────────────────────────────────────────────────────────────────────────────
 * Getting there
 * ──────────────────────────────────────────────────────────────────────────── */

test("a hit's link carries the stage, the field and the row, and reads back identically", () => {
  const hit = only("ତନ୍ତ");
  const href = workshopHitHref("cmsik2jg8000eh8xc1lcy661a", hit);
  expect(href.startsWith("/design-workshops/cmsik2jg8000eh8xc1lcy661a/stages/PROCESS?")).toBe(true);

  const focus = readStageFocus(new URL(href, "http://localhost").searchParams);
  expect(focus).toEqual({ entityKey: "tool", fieldKey: "localName", rowKey: "tool-1" });
});

test("a singleton hit carries no row, and a malformed link focuses nothing", () => {
  const hit = only("indigo");
  const focus = readStageFocus(new URL(workshopHitHref("w1", hit), "http://localhost").searchParams);
  expect(focus).toEqual({ entityKey: "workshopSetup", fieldKey: "background", rowKey: null });

  // A hand-edited or truncated parameter must never be able to stop a designer reaching a form.
  expect(readStageFocus(new URLSearchParams("find=nodot"))).toBeNull();
  expect(readStageFocus(new URLSearchParams(""))).toBeNull();
  expect(readStageFocus(null)).toBeNull();
});
