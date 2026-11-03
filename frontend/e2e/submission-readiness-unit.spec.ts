import { expect, test } from "@playwright/test";

import { localCompleteness, type DwDraft } from "@/lib/designWorkshopStore";
import { stageProgress, stageProgressPercent, type DwRegistry, type DwStageCompleteness } from "@/lib/designWorkshops";
import { itemFieldName, readinessSummary, workshopReadiness } from "@/lib/submissionReadiness";

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
            { key: "weather", label: "Weather", type: "TEXT", tier: "STANDARD", required: false },
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

test("an unfilled required field blocks and an unfilled optional one does not", () => {
  expect(labels()).toContain("Venue");
  expect(labels()).not.toContain("Weather");
  expect(READINESS.advisory.map((gap) => gap.stageKey)).toContain("SETUP");
});

test("a filled field is not listed, and a deprecated one is never listed at all", () => {
  expect(labels()).not.toContain("Craft name");
  expect(labels()).not.toContain("Old code");
});

test("a collection's missing field is one entry that says how many rows it covers", () => {
  expect(labels().filter((label) => label === "Prototypes: Photograph caption")).toHaveLength(1);

  const caption = READINESS.blocking.find((item) => item.label === "Prototypes: Photograph caption");
  expect(caption?.address?.occurrences, "both rows are missing a caption").toBe(2);
  expect(caption?.address?.rowCount).toBe(2);

  const material = READINESS.blocking.find((item) => item.label === "Prototypes: Material");
  expect(material?.address?.occurrences, "only the first row is missing its material").toBe(1);
  expect(material?.address?.rowCount).toBe(2);
});

test("every entry deep-links to its own stage, and a collection entry to its own row", () => {
  for (const item of READINESS.blocking) {
    expect(item.href, `${item.label} links into its stage`).toContain(`/design-workshops/${WORKSHOP_ID}/stages/${item.stageKey}`);
    expect(item.address, `${item.label} was placed`).not.toBeNull();
  }

  const material = READINESS.blocking.find((item) => item.label === "Prototypes: Material");
  expect(material?.href).toBe(`/design-workshops/${WORKSHOP_ID}/stages/PROTOTYPE?find=prototype.material&row=proto-1`);
  expect(material?.address?.rowTitle, "the row is named the way a designer recognises it").toBe(
    "Phoda kumbha table runner"
  );
  expect(itemFieldName(material!)).toBe("Material · Phoda kumbha table runner");
});

test("a caption anchors to the media field it is drawn under, not to itself", () => {
  const caption = READINESS.blocking.find((item) => item.label === "Prototypes: Photograph caption");
  expect(caption?.address?.anchorFieldKey).toBe("photo");
  expect(caption?.href).toContain("find=prototype.photo");
});

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
  expect(done.isSubmittable).toBe(true);
  expect(readinessSummary(done)).toBe("3 of 3 stages complete; no required fields remain.");
});

test("the overview bar, the readiness count and the stage rule are one number", () => {
  const scores = Object.values(localCompleteness(REGISTRY, DRAFT));
  const header = stageProgress(scores, REGISTRY.stages.length);
  expect(header.stagesComplete).toBe(READINESS.stagesComplete);
  expect(header.stagesTotal).toBe(READINESS.stagesTotal);
  expect(header.requiredLeft).toBe(READINESS.blocking.length);
  expect(header.percent).toBe(stageProgressPercent(READINESS.stagesComplete, READINESS.stagesTotal));
});

test("nine empty stages complete and nothing else filled reads 41%, never 0% (the 0.0.19 smoke)", () => {
  const score = (missing: string[]): Pick<DwStageCompleteness, "isComplete" | "missing"> => ({
    isComplete: missing.length === 0,
    missing
  });
  const smoke = [...Array.from({ length: 9 }, () => score([])), ...Array.from({ length: 13 }, () => score(["A", "B", "C"]))];
  expect(stageProgress(smoke)).toEqual({ stagesComplete: 9, stagesTotal: 22, percent: 41, requiredLeft: 39 });
  expect(stageProgressPercent(0, 0)).toBe(0);
  expect(stageProgressPercent(5, 4)).toBe(100);
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

test("the blocking list agrees with the scorer, entry for entry", () => {
  const drafts: Record<string, DwDraft> = {
    "the half-filled fixture": DRAFT,
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
    expect(readiness.requiredFilled, `${name}: filled count`).toBe(
      Object.values(scores).reduce((sum, score) => sum + score.requiredFilled, 0)
    );
    expect(readiness.requiredTotal, `${name}: required total`).toBe(
      Object.values(scores).reduce((sum, score) => sum + score.requiredTotal, 0)
    );
  }
});
