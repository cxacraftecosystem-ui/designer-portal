import { expect, test } from "@playwright/test";

import { outstanding, syncOutcome } from "@/components/designworkshop/DraftSyncBanner";
import { ApiError } from "@/lib/api";
import {
  buildStageEntries,
  emptyStage,
  foldStageInto,
  mediaRefusal,
  splitSingletons,
  stageRefusalIsPassLevel,
  stageSweep,
  stagesUnreadAfterCreate,
  storeIsAnswering,
  unknownSingletonKeys,
  type DwDraft,
  type DwDraftStage
} from "@/lib/designWorkshopStore";
import { MediaBatchError } from "@/lib/media";
import { APP_RUN_ID, blocksRetry } from "@/lib/offline";
import type { DwStage, DwStageData } from "@/lib/designWorkshops";

function field(key: string) {
  return {
    key,
    label: key,
    type: "TEXT",
    required: false,
    tier: "BASIC",
    help: "",
    unit: "",
    options: [],
    refModel: "",
    refScope: "",
    deprecated: false,
    reportRole: "",
    derivedFrom: [],
    hydrateFrom: {},
    max: null,
    min: null,
    maxLength: null,
    pattern: ""
  } as unknown as DwStage["entities"][number]["fields"][number];
}

const STALE_STAGE: DwStage = {
  number: 3,
  key: "WORKSHOP_PLAN",
  title: "Workshop plan",
  purpose: "",
  notes: "",
  optionalStage: false,
  entities: [
    {
      key: "workshopPlan",
      title: "Workshop plan",
      cardinality: "SINGLETON",
      fields: [field("designerName"), field("designerProfile")]
    },
    {
      key: "session",
      title: "Session",
      cardinality: "COLLECTION",
      fields: [field("topic")]
    }
  ]
} as unknown as DwStage;

const FROM_SERVER: DwStageData = {
  singleton: {
    designerName: "R. Iyer",
    designerProfile: "Twenty years in the Kutch cluster.",
    designerExperience: "Led the 2024 Bhujodi revival."
  },
  collections: {}
};

function readStage(): DwDraftStage {
  return {
    ...emptyStage("WORKSHOP_PLAN"),
    singletons: { workshopPlan: { designerName: "R. Iyer", designerProfile: "Twenty years in the Kutch cluster." } },
    unknownSingleton: { workshopPlan: { designerExperience: "Led the 2024 Bhujodi revival." } },
    serverLoadedAt: 1_700_000_000_000,
    dirtyAt: 1_700_000_100_000,
    updatedAt: 1_700_000_100_000
  };
}

test("splitSingletons still takes only what the registry declares — that half was never wrong", () => {
  expect(splitSingletons(STALE_STAGE, FROM_SERVER.singleton ?? {})).toEqual({
    workshopPlan: { designerName: "R. Iyer", designerProfile: "Twenty years in the Kutch cluster." }
  });
});

test("the keys it leaves behind are collected rather than dropped on the floor", () => {
  expect(unknownSingletonKeys(STALE_STAGE, FROM_SERVER.singleton ?? {})).toEqual({
    workshopPlan: { designerExperience: "Led the 2024 Bhujodi revival." }
  });
});

test("the protocol's own underscore keys are NOT collected — a reserved key 422s the whole stage", () => {
  expect(
    unknownSingletonKeys(STALE_STAGE, { _entryId: "cuid", _ordinal: 0, whatever: "x" })
  ).toEqual({ workshopPlan: { whatever: "x" } });
});

test("a stage declaring no singleton collects nothing, because nothing will be sent to lose it", () => {
  const collectionsOnly = { ...STALE_STAGE, entities: [STALE_STAGE.entities[1]] } as DwStage;
  expect(unknownSingletonKeys(collectionsOnly, FROM_SERVER.singleton ?? {})).toEqual({});
});

test("the fold carries the undeclared key through, so the draft stays a SUPERSET of the server", () => {
  const dirty: DwDraftStage = {
    ...emptyStage("WORKSHOP_PLAN"),
    singletons: { workshopPlan: { designerName: "Typed here" } },
    dirtyAt: 1_700_000_200_000
  };
  const fold = foldStageInto(STALE_STAGE, dirty, FROM_SERVER);
  expect(fold.stage.singletons.workshopPlan).toEqual({
    designerName: "Typed here",
    designerProfile: "Twenty years in the Kutch cluster."
  });
  expect(fold.stage.unknownSingleton).toEqual({
    workshopPlan: { designerExperience: "Led the 2024 Bhujodi revival." }
  });
  expect(fold.added).toEqual(["designerProfile"]);
});

test("the payload carries the undeclared key back up, which is what stops the wholesale write deleting it", () => {
  const { entries } = buildStageEntries(STALE_STAGE, readStage());
  const singleton = entries.find((entry) => entry.entityKey === "workshopPlan");
  expect(singleton?.data).toEqual({
    designerExperience: "Led the 2024 Bhujodi revival.",
    designerName: "R. Iyer",
    designerProfile: "Twenty years in the Kutch cluster."
  });
  expect(Object.keys(singleton?.data ?? {})).toContain("designerExperience");
});

test("what the designer typed still wins over a carried key of the same name", () => {
  const stage = readStage();
  stage.singletons.workshopPlan.designerExperience = "Corrected here";
  stage.unknownSingleton = { workshopPlan: { designerExperience: "Stale carried copy" } };
  const { entries } = buildStageEntries(STALE_STAGE, stage);
  expect(entries.find((entry) => entry.entityKey === "workshopPlan")?.data.designerExperience).toBe("Corrected here");
});

test("before the create, an offline stage is authoritative — nothing above it can be harmed", () => {
  const local: DwDraftStage = {
    ...emptyStage("WORKSHOP_PLAN"),
    singletons: { workshopPlan: { designerName: "Typed in a courtyard" } },
    removedFrom: ["session"],
    serverLoadedAt: 1_700_000_000_000,
    dirtyAt: 1_700_000_000_000
  };
  expect(buildStageEntries(STALE_STAGE, local).merged).toBe(false);
  expect(stageSweep(STALE_STAGE, local).replaceCollections).toBe(true);
});

test("after the create it is not, so the first push merges and the prefill survives", () => {
  const local: DwDraftStage = {
    ...emptyStage("WORKSHOP_PLAN"),
    singletons: { workshopPlan: { designerName: "Typed in a courtyard" } },
    removedFrom: ["session"],
    serverLoadedAt: 1_700_000_000_000,
    dirtyAt: 1_700_000_000_000
  };
  const after = stagesUnreadAfterCreate({ WORKSHOP_PLAN: local }).WORKSHOP_PLAN;
  expect(after.serverLoadedAt).toBeNull();
  const built = buildStageEntries(STALE_STAGE, after);
  expect(built.merged).toBe(true);
  expect(built.entries.every((entry) => entry.merge !== false)).toBe(true);
});

test("and the pending sweep goes with it, because the create seeds no collection row anywhere", () => {
  const local: DwDraftStage = { ...emptyStage("WORKSHOP_PLAN"), removedFrom: ["session"], dirtyAt: 1 };
  const after = stagesUnreadAfterCreate({ WORKSHOP_PLAN: local }).WORKSHOP_PLAN;
  expect(after.removedFrom).toEqual([]);
  expect(stageSweep(STALE_STAGE, after)).toEqual({
    replaceCollections: false,
    emptiedEntities: [],
    withheld: []
  });
});

test("everything else on the stage is left exactly as it was", () => {
  const local: DwDraftStage = {
    ...emptyStage("WORKSHOP_PLAN"),
    singletons: { workshopPlan: { designerName: "Typed in a courtyard" } },
    collections: { session: [{ _clientKey: "k1", topic: "Dyeing" }] },
    custom: { ownQuestion: "answered" },
    dirtyAt: 42,
    lastPushedAt: null
  };
  const after = stagesUnreadAfterCreate({ WORKSHOP_PLAN: local }).WORKSHOP_PLAN;
  expect(after.singletons).toEqual(local.singletons);
  expect(after.collections).toEqual(local.collections);
  expect(after.custom).toEqual(local.custom);
  expect(after.dirtyAt).toBe(42);
});

test("a 404 is a whole-workshop fact and is rethrown, not stamped on the stage", () => {
  expect(stageRefusalIsPassLevel(new ApiError(404, "Record not found", null))).toBe(true);
  expect(stageRefusalIsPassLevel(new ApiError(409, "This workshop is deleted.", null))).toBe(true);
});

test("the refusals that really are about one stage still stay on that stage", () => {
  expect(stageRefusalIsPassLevel(new ApiError(422, "amount: not a valid number", null))).toBe(false);
  expect(stageRefusalIsPassLevel(new ApiError(500, "lone surrogate", null))).toBe(false);
  expect(stageRefusalIsPassLevel(new ApiError(403, "not yours", null))).toBe(false);
});

test("a request the server never answered stops the pass, as it always did", () => {
  expect(stageRefusalIsPassLevel(new TypeError("Failed to fetch"))).toBe(true);
  expect(stageRefusalIsPassLevel(new ApiError(408, "timeout", null))).toBe(true);
  expect(stageRefusalIsPassLevel(new ApiError(429, "slow down", null))).toBe(true);
});

test("a 415 is permanent, so the same video is not re-uploaded on every connectivity flap", () => {
  const refusal = mediaRefusal("loom.mkv", new ApiError(415, "Unsupported media type", null), 1);
  expect(refusal.permanent).toBe(true);
  expect(refusal.skewRun).toBeNull();
  expect(blocksRetry(refusal)).toBe(true);
  expect(refusal.message).toContain("loom.mkv");
});

test("a 429 is not permanent — the server asking for time is not a refusal of the file", () => {
  const refusal = mediaRefusal("loom.jpg", new ApiError(429, "Too many requests", null), 3);
  expect(refusal.permanent).toBe(false);
  expect(blocksRetry(refusal)).toBe(false);
});

test("a dialect mismatch clears itself on the next app run instead of blaming the photograph", () => {
  const refusal = mediaRefusal(
    "loom.jpg",
    new ApiError(422, "caption: Extra inputs are not permitted", {
      detail: [{ type: "extra_forbidden", loc: ["body", "caption"], msg: "Extra inputs are not permitted" }]
    }),
    1
  );
  expect(refusal.skewRun).toBe(APP_RUN_ID);
  expect(refusal.message).toContain("Reload the page to update the app");
  expect(blocksRetry(refusal)).toBe(true);
  expect(blocksRetry({ ...refusal, skewRun: "a-previous-app-run" })).toBe(false);
});

test("a dialect mismatch met WHILE UPLOADING quotes the server's key, not the batch wrapper's advice", () => {
  const answered = new ApiError(422, "merge: Extra inputs are not permitted", {
    detail: [{ type: "extra_forbidden", loc: ["body", "merge"], msg: "Extra inputs are not permitted" }]
  });
  const wrapped = new MediaBatchError(
    "The media file failed to upload (merge: Extra inputs are not permitted). Check your internet connection " +
      "and try again — the record was saved, so re-open it and re-attach the media",
    [{ name: "loom.jpg", error: "merge: Extra inputs are not permitted", cause: answered }]
  );
  const refusal = mediaRefusal("loom.jpg", wrapped, 1);

  expect(refusal.skewRun, "the wrapper carries no opinion; the 422 inside it decides").toBe(APP_RUN_ID);
  expect(refusal.message, "the server's own sentence, naming the key").toContain(
    "merge: Extra inputs are not permitted"
  );
  expect(refusal.message, "and NOT the batch wrapper's connection advice").not.toContain("Check your internet");
  expect(refusal.message).not.toContain("re-attach the media");
});

const IDLE = {
  workshopsCreated: 0,
  stagesSent: 0,
  mediaUploaded: 0,
  failed: 0,
  pending: 0,
  stoppedOffline: false
};

test("a pass that refused six things does not answer 'Nothing to send'", () => {
  const outcome = syncOutcome({ ...IDLE, failed: 6, pending: 1 });
  expect(outcome.kind).toBe("refused");
  expect(outcome.tone).toBe("error");
  expect(outcome.title).toBe("6 items couldn't be saved");
});

test("one refusal is singular, because a banner that cannot count is a banner nobody trusts", () => {
  expect(syncOutcome({ ...IDLE, failed: 1 }).title).toBe("1 item couldn't be saved");
});

test("a refusal outranks a lost connection: it is the only one of the two a person can act on", () => {
  expect(syncOutcome({ ...IDLE, failed: 2, stoppedOffline: true }).kind).toBe("refused");
});

test("the two outcomes that were already right are unchanged", () => {
  expect(syncOutcome({ ...IDLE, stagesSent: 3 })).toMatchObject({ kind: "sent", title: "3 saved changes sent" });
  expect(syncOutcome({ ...IDLE, stoppedOffline: true }).kind).toBe("offline");
  expect(syncOutcome(IDLE).kind).toBe("idle");
});

function draftWith(stages: Record<string, DwDraftStage>): DwDraft {
  return {
    schemaVersion: 4,
    localId: "dwlocal-1",
    remoteId: "srv-1",
    header: {
      title: "Bhujodi",
      templateId: "DCH_STANDARD",
      status: "DRAFT",
      workshopKind: null,
      designerUserId: null,
      designerUserIds: [],
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
    createdAt: 1,
    updatedAt: 2,
    registryVersion: "v1",
    ownerUserId: "u1",
    lastSyncedAt: null,
    failure: null
  };
}

test("the fold's warning reaches a screen — it was written to disk and read by nothing", () => {
  const note =
    "This stage has now been read from the server. You had deleted everything in prototype in this browser, so 6 rows " +
    "the server still holds there have NOT been added back, and the next save will delete them on the server.";
  const state = outstanding(
    draftWith({ PROTOTYPE_ONE: { ...emptyStage("PROTOTYPE_ONE"), dirtyAt: 5, foldNote: note } })
  );
  expect(state.folds).toEqual([{ stageKey: "PROTOTYPE_ONE", note }]);
});

test("a stage with no note, or a blank one, adds nothing — an unchanged re-read must stay silent", () => {
  const state = outstanding(
    draftWith({
      A: { ...emptyStage("A"), dirtyAt: 5, foldNote: null },
      B: { ...emptyStage("B"), dirtyAt: 5, foldNote: "   " },
      C: { ...emptyStage("C"), dirtyAt: 5 }
    })
  );
  expect(state.folds).toEqual([]);
});

test("a store that has answered every time is reported as answering", () => {
  expect(storeIsAnswering({ readFailedAt: null, writeFailedAt: null })).toBe(true);
});

test("a failed read and a failed write are both trouble, and a full disk reads perfectly well", () => {
  expect(storeIsAnswering({ readFailedAt: 1, writeFailedAt: null })).toBe(false);
  expect(storeIsAnswering({ readFailedAt: null, writeFailedAt: 1 })).toBe(false);
});
