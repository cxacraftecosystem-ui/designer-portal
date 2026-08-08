import { expect, test } from "@playwright/test";

import { localCompleteness, type DwDraft } from "@/lib/designWorkshopStore";
import type { DwRegistry } from "@/lib/designWorkshops";
import { itemFieldName, readinessSummary, workshopReadiness } from "@/lib/submissionReadiness";

/**
 * `lib/submissionReadiness.ts` on its own — no browser, no server, no IndexedDB.
 *
 * THE ASSERTION THIS FILE EXISTS FOR is the last one: the blocking list must be exactly
 * `DwStageCompleteness.missing`, entry for entry. That module claims in its header that it assembles
 * the existing scorer rather than re-deriving completeness, and a claim like that decays the moment
 * nobody checks it — someone adds "…and also warn when a collection is empty", the readiness screen
 * starts naming obstacles the Save button is perfectly happy with, and the designer it was written
 * for learns to distrust it. Everything above that test is the behaviour a reader would expect;
 * `agrees with the scorer, entry for entry` is the invariant.
 *
 * WHY A NODE SPEC. The module is pure by design — the same shape as `lib/marketAnalysis.ts` — so it
 * can be reasoned about here in milliseconds and ported to Kotlin later, which is what Android will
 * need to draw this screen offline. Playwright resolves the `@/` alias in the node process.
 *
 * The fixture is hand-built rather than fetched from the live registry for the reason
 * `design-workshop-search-unit.spec.ts` gives: a fixture that changed when somebody edited stage 5
 * would make every failure here ambiguous.
 */

const WORKSHOP_ID = "cmspecworkshop00000000001";

const REGISTRY: DwRegistry = {
  version: "spec",
  enums: {
    REPORT_TEMPLATE: [
      { value: "DCH_STANDARD", label: "DCH standard workshop report" },
      { value: "PHOTO_CATALOGUE", label: "Photo catalogue" }
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
            { key: "craftName", label: "Craft name", type: "TEXT", tier: "BASIC", required: true },
            { key: "venue", label: "Venue", type: "TEXT", tier: "BASIC", required: true },
            // Optional, and unfilled: it must reach `advisory` and never the blocking list.
            { key: "weather", label: "Weather", type: "TEXT", tier: "STANDARD", required: false },
            // Deprecated AND required: a dead input no form draws, so it must be skipped entirely —
            // listing it would send a designer hunting for a box that does not exist.
            { key: "oldCode", label: "Old code", type: "TEXT", tier: "BASIC", required: true, deprecated: true }
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
          fields: [
            { key: "name", label: "Prototype name", type: "TEXT", tier: "BASIC", required: true },
            { key: "material", label: "Material", type: "TEXT", tier: "BASIC", required: true },
            { key: "photo", label: "Photograph", type: "IMAGE", tier: "STANDARD", required: false },
            {
              key: "photoCaption",
              label: "Photograph caption",
              type: "TEXT",
              tier: "BASIC",
              required: true,
              captionFor: "photo"
            }
          ]
        }
      ]
    },
    {
      number: 20,
      key: "REPORT_GENERATION",
      title: "Report Generation & Submission",
      purpose: "",
      notes: "",
      optionalStage: false,
      entities: [
        {
          key: "reportSettings",
          name: "DwReportSettings",
          cardinality: "SINGLETON",
          title: "Report settings",
          description: "",
          parent: "",
          labelField: "",
          fields: [
            {
              key: "templateId",
              label: "Report template",
              type: "ENUM",
              tier: "BASIC",
              required: true,
              enum: "REPORT_TEMPLATE"
            },
            { key: "excludedStages", label: "Stages to leave out", type: "TAGS", tier: "STANDARD", required: false }
          ]
        }
      ]
    }
  ]
};

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

/**
 * Stage 1 half-answered; two prototype rows, the first missing its material, both missing captions;
 * stage 20 answered with a template this registry does offer.
 */
const DRAFT = draftWith({
  SETUP: stage("SETUP", { workshopSetup: { craftName: "Sambalpuri bandha" } }, {}),
  PROTOTYPE: stage(
    "PROTOTYPE",
    {},
    {
      prototype: [
        { _clientKey: "proto-1", name: "Phoda kumbha table runner" },
        { _clientKey: "proto-2", name: "Saptapar stole", material: "Tussar silk" }
      ]
    }
  ),
  REPORT_GENERATION: stage("REPORT_GENERATION", { reportSettings: { templateId: "DCH_STANDARD" } }, {})
});

const READINESS = workshopReadiness(REGISTRY, DRAFT, WORKSHOP_ID);

function labels(): string[] {
  return READINESS.blocking.map((item) => item.label);
}

/* ────────────────────────────────────────────────────────────────────────────
 * What blocks, and what does not
 * ──────────────────────────────────────────────────────────────────────────── */

test("an unfilled required field blocks and an unfilled optional one does not", () => {
  expect(labels()).toContain("Venue");
  // "Weather" is STANDARD tier. It has to be absent from the blocking list entirely — a Standard gap
  // that appeared beside a Basic one would tell a designer to do work no submit is waiting on.
  expect(labels()).not.toContain("Weather");
  expect(READINESS.advisory.map((gap) => gap.stageKey)).toContain("SETUP");
});

test("a filled field is not listed, and a deprecated one is never listed at all", () => {
  expect(labels()).not.toContain("Craft name");
  // The dead input. `scoreStageData` skips it, so this module must never surface it — a designer sent
  // to find "Old code" would search a form that has not drawn that box since it was retired.
  expect(labels()).not.toContain("Old code");
});

test("a collection's missing field is one entry that says how many rows it covers", () => {
  // De-duplicated by label exactly as the server de-duplicates it, so eleven prototypes missing a
  // material are one item and not eleven.
  expect(labels().filter((label) => label === "Prototypes: Photograph caption")).toHaveLength(1);

  const caption = READINESS.blocking.find((item) => item.label === "Prototypes: Photograph caption");
  expect(caption?.address?.occurrences, "both rows are missing a caption").toBe(2);
  expect(caption?.address?.rowCount).toBe(2);

  const material = READINESS.blocking.find((item) => item.label === "Prototypes: Material");
  expect(material?.address?.occurrences, "only the first row is missing its material").toBe(1);
  expect(material?.address?.rowCount).toBe(2);
});

/* ────────────────────────────────────────────────────────────────────────────
 * Being able to act on an entry
 * ──────────────────────────────────────────────────────────────────────────── */

test("every entry deep-links to its own stage, and a collection entry to its own row", () => {
  for (const item of READINESS.blocking) {
    expect(item.href, `${item.label} links into its stage`).toContain(`/design-workshops/${WORKSHOP_ID}/stages/${item.stageKey}`);
    expect(item.address, `${item.label} was placed`).not.toBeNull();
  }

  const material = READINESS.blocking.find((item) => item.label === "Prototypes: Material");
  // The FIRST offending row keeps the link — proto-1 is the one without a material, and proto-2 has
  // one, so a link naming proto-2 would land the designer on a row that is already answered.
  expect(material?.href).toBe(`/design-workshops/${WORKSHOP_ID}/stages/PROTOTYPE?find=prototype.material&row=proto-1`);
  expect(material?.address?.rowTitle, "the row is named the way a designer recognises it").toBe(
    "Phoda kumbha table runner"
  );
  expect(itemFieldName(material!)).toBe("Material · Phoda kumbha table runner");
});

test("a caption anchors to the media field it is drawn under, not to itself", () => {
  const caption = READINESS.blocking.find((item) => item.label === "Prototypes: Photograph caption");
  // A caption input has no wrapper of its own — it is drawn beneath its media field — so focusing
  // `photoCaption` would scroll to nothing and the item would land on the stage with no highlight.
  expect(caption?.address?.anchorFieldKey).toBe("photo");
  expect(caption?.href).toContain("find=prototype.photo");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The report's checks, beyond the fields
 * ──────────────────────────────────────────────────────────────────────────── */

test("a stage-20 template this build does not offer is reported, and a known one is not", () => {
  expect(READINESS.checks.map((check) => check.id), "DCH_STANDARD is a template this registry offers").not.toContain(
    "template-unknown"
  );

  const stale = workshopReadiness(
    REGISTRY,
    draftWith({ REPORT_GENERATION: stage("REPORT_GENERATION", { reportSettings: { templateId: "MINISTRY_2031" } }, {}) }),
    WORKSHOP_ID
  );
  const check = stale.checks.find((candidate) => candidate.id === "template-unknown");
  expect(check, "an unrecognised template is what `resolve_template_id` silently ignores").toBeTruthy();
  expect(check?.href).toContain("find=reportSettings.templateId");
});

test("a stage excluded from the report is reported only when it holds answers", () => {
  const withAnswers = workshopReadiness(
    REGISTRY,
    draftWith({
      SETUP: stage("SETUP", { workshopSetup: { craftName: "Sambalpuri bandha" } }, {}),
      REPORT_GENERATION: stage(
        "REPORT_GENERATION",
        { reportSettings: { templateId: "DCH_STANDARD", excludedStages: ["SETUP"] } },
        {}
      )
    }),
    WORKSHOP_ID
  );
  expect(withAnswers.checks.map((check) => check.id)).toContain("stage-excluded:SETUP");

  // Excluding an EMPTY stage is exactly what the setting is for. Warning about it would train a
  // designer to skim this section, and then the one warning that mattered would go unread too.
  const empty = workshopReadiness(
    REGISTRY,
    draftWith({
      REPORT_GENERATION: stage(
        "REPORT_GENERATION",
        { reportSettings: { templateId: "DCH_STANDARD", excludedStages: ["SETUP"] } },
        {}
      )
    }),
    WORKSHOP_ID
  );
  expect(empty.checks.map((check) => check.id)).not.toContain("stage-excluded:SETUP");
});

/* ────────────────────────────────────────────────────────────────────────────
 * Saying it honestly
 * ──────────────────────────────────────────────────────────────────────────── */

test("the summary counts stages and fields rather than printing a percentage", () => {
  expect(readinessSummary(READINESS)).toBe(
    `${READINESS.stagesComplete} of 3 stages complete; ${READINESS.blocking.length} required fields remain in ${READINESS.blockedStages} stages.`
  );

  const done = workshopReadiness(
    REGISTRY,
    draftWith({
      SETUP: stage("SETUP", { workshopSetup: { craftName: "Sambalpuri bandha", venue: "Barpali" } }, {}),
      REPORT_GENERATION: stage("REPORT_GENERATION", { reportSettings: { templateId: "DCH_STANDARD" } }, {})
    }),
    WORKSHOP_ID
  );
  // No prototype rows at all: an empty collection contributes nothing, which is a legitimate state
  // and NOT an obstacle — the same rule `stage_completeness` applies.
  expect(done.isSubmittable).toBe(true);
  expect(readinessSummary(done)).toBe("3 of 3 stages complete; no required fields remain.");
});

test("one outstanding field is worded in the singular", () => {
  const nearly = workshopReadiness(
    REGISTRY,
    draftWith({
      SETUP: stage("SETUP", { workshopSetup: { craftName: "Sambalpuri bandha" } }, {}),
      REPORT_GENERATION: stage("REPORT_GENERATION", { reportSettings: { templateId: "DCH_STANDARD" } }, {})
    }),
    WORKSHOP_ID
  );
  expect(readinessSummary(nearly)).toBe("2 of 3 stages complete; 1 required field remains in 1 stage.");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The invariant
 * ──────────────────────────────────────────────────────────────────────────── */

test("the blocking list agrees with the scorer, entry for entry", () => {
  /*
    THE WHOLE CONTRACT OF THIS MODULE. If these two ever disagree, a second completeness algorithm has
    grown here — and the readiness screen and the Save button would then be giving a designer two
    different answers to "may I submit this?".

    ASSERTED OVER SEVERAL DRAFTS, and that is not thoroughness for its own sake. Written against the
    main fixture alone this test PASSED against a deliberately planted drift ("an empty collection is
    also an obstacle"), because that fixture happens to have rows in its only collection — the one
    shape the bug needed to show itself was the one shape the fixture did not have. An empty draft, a
    draft with populated collections and a finished draft are the three states a real workshop passes
    through, and the invariant has to hold in all of them or it is not an invariant.
  */
  const drafts: Record<string, DwDraft> = {
    "the half-filled fixture": DRAFT,
    // Nothing recorded at all — every collection empty, which is a legitimate day-one state and the
    // case the planted drift got wrong.
    "an untouched workshop": draftWith({}),
    "stage 1 only": draftWith({ SETUP: stage("SETUP", { workshopSetup: { craftName: "Sambalpuri bandha" } }, {}) }),
    "a finished workshop": draftWith({
      SETUP: stage("SETUP", { workshopSetup: { craftName: "Sambalpuri bandha", venue: "Barpali" } }, {}),
      REPORT_GENERATION: stage("REPORT_GENERATION", { reportSettings: { templateId: "DCH_STANDARD" } }, {})
    })
  };

  for (const [name, draft] of Object.entries(drafts)) {
    const scores = localCompleteness(REGISTRY, draft);
    const fromScorer = REGISTRY.stages.flatMap((spec) =>
      (scores[spec.key]?.missing ?? []).map((label) => `${spec.key}:${label}`)
    );
    const readiness = workshopReadiness(REGISTRY, draft, WORKSHOP_ID);
    const fromReadiness = readiness.blocking.map((item) => `${item.stageKey}:${item.label}`);

    expect(fromReadiness, `${name}: the blocking list is the scorer's \`missing\`, in its order`).toEqual(fromScorer);
    expect(readiness.isSubmittable, `${name}: submittability follows the scorer`).toBe(fromScorer.length === 0);
    // The counts are the scorer's too, not re-totalled from the item list.
    expect(readiness.requiredFilled, `${name}: filled count`).toBe(
      Object.values(scores).reduce((sum, score) => sum + score.requiredFilled, 0)
    );
    expect(readiness.requiredTotal, `${name}: required total`).toBe(
      Object.values(scores).reduce((sum, score) => sum + score.requiredTotal, 0)
    );
  }
});
