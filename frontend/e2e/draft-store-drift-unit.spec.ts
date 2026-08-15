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
import { APP_RUN_ID, blocksRetry } from "@/lib/offline";
import type { DwStage, DwStageData } from "@/lib/designWorkshops";

/**
 * THE OFFLINE DRAFT STORE'S SIX WAYS OF LOSING WORK QUIETLY, EACH PINNED AT THE DECISION.
 *
 * Every case below is one finding from `docs/AUDIT-2026-08-15.md`, and every one of them was
 * reachable only through IndexedDB, a live API and a Postgres row — which is exactly why they
 * survived review. The decisions themselves are pure: what the payload carries, what the pass
 * rethrows, what a refused photograph is recorded as, what a completed pass says. So they are
 * asserted here, with no browser, no store and no server anywhere near them, in the style of
 * `stage-fold-unit.spec.ts` and `stage-sweep-authority-unit.spec.ts` next door.
 *
 * Where a fix could be satisfied by simply refusing to do anything, the case asserts BOTH halves —
 * what is now withheld AND what must still travel. A store that stopped sending would pass a
 * one-sided test and lose a fortnight of fieldwork.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Fixtures — the same shape as the fold and sweep specs, deliberately
 * ──────────────────────────────────────────────────────────────────────────── */

function field(key: string) {
  // Written out rather than cast so a field gaining a REQUIRED member fails here loudly instead of
  // being silently absent. Same fixture as `stage-fold-unit.spec.ts`.
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

/** Stage 3 as a browser holding a registry from BEFORE `designerExperience` was added sees it. */
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

/** What `GET /stages/WORKSHOP_PLAN` sends: the row verbatim, including the key this build lacks. */
const FROM_SERVER: DwStageData = {
  singleton: {
    designerName: "R. Iyer",
    designerProfile: "Twenty years in the Kutch cluster.",
    // The key this browser's stale registry does not declare. A colleague answered it in the office.
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

/* ────────────────────────────────────────────────────────────────────────────
 * A STALE REGISTRY MUST NOT DELETE A KEY IT CANNOT DRAW
 *
 * `splitSingletons` copied across only the keys this build declares and dropped the rest. Because
 * the stage HAS been read, `neverRead` is false, the singleton entry goes up with no `merge`, and
 * `save_stage` writes the payload's `data` over the row WHOLESALE with no `RecordRevision`. The
 * colleague's answer was gone in place, and `droppedKeys` could not report it because the server
 * derives that list from the keys the CLIENT SENT — and this client had removed them first.
 * ──────────────────────────────────────────────────────────────────────────── */

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
  // Local wins for the declared key; the server fills the one this browser had no opinion about.
  expect(fold.stage.singletons.workshopPlan).toEqual({
    designerName: "Typed here",
    designerProfile: "Twenty years in the Kutch cluster."
  });
  expect(fold.stage.unknownSingleton).toEqual({
    workshopPlan: { designerExperience: "Led the 2024 Bhujodi revival." }
  });
  // Not counted as an "answer that appeared": this build cannot draw a box for it, so announcing it
  // would be a sentence the designer has no way to check.
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
  // WITHOUT the fix this was `{designerName, designerProfile}` and `save_stage` wrote exactly that
  // over the row. The assertion that the key is PRESENT is the whole test.
  expect(Object.keys(singleton?.data ?? {})).toContain("designerExperience");
});

test("what the designer typed still wins over a carried key of the same name", () => {
  const stage = readStage();
  stage.singletons.workshopPlan.designerExperience = "Corrected here";
  stage.unknownSingleton = { workshopPlan: { designerExperience: "Stale carried copy" } };
  const { entries } = buildStageEntries(STALE_STAGE, stage);
  expect(entries.find((entry) => entry.entityKey === "workshopPlan")?.data.designerExperience).toBe("Corrected here");
});

/* ────────────────────────────────────────────────────────────────────────────
 * THE CREATE RETIRES A CLAIM THAT WAS TRUE WHEN IT WAS MADE
 *
 * `putDraftStage` stamps `serverLoadedAt` while `remoteId` is null, on the true premise that a
 * workshop the server has never heard of has nothing up there to overwrite. `POST
 * /design-workshops` then seeds `workshopSetup` and `workshopPlan` with the designer's profile, and
 * the very next PUT — built with `neverRead` false, so no `merge` — replaced both rows wholesale.
 * ──────────────────────────────────────────────────────────────────────────── */

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
  // `seed_designer_prefill` skips every entity whose cardinality is not SINGLETON, so a deletion
  // recorded before the create can only ever have been of a row that never left this device. Left
  // standing it would make `stageSweep` report `withheld` about rows that exist nowhere but here.
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

/* ────────────────────────────────────────────────────────────────────────────
 * A WORKSHOP THAT IS NO LONGER YOURS IS NOT TWENTY-TWO BAD ANSWERS
 * ──────────────────────────────────────────────────────────────────────────── */

test("a 404 is a whole-workshop fact and is rethrown, not stamped on the stage", () => {
  // `load_workshop_or_404` takes its non-admin deleted branch FIRST, so a designer — the only user
  // whose stages sit in this store — always gets 404 and never the 409 the pass was written for.
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

/* ────────────────────────────────────────────────────────────────────────────
 * A PHOTOGRAPH THE SERVER WILL NEVER ACCEPT
 *
 * The media loop's only skip test was `remoteMediaId || !blob`, neither of which a refusal sets, so
 * the same bytes went up on every pass for ever while the stage behind them was held back under
 * "It sends itself as soon as they upload".
 * ──────────────────────────────────────────────────────────────────────────── */

test("a 415 is permanent, so the same video is not re-uploaded on every connectivity flap", () => {
  const refusal = mediaRefusal("loom.mkv", new ApiError(415, "Unsupported media type", null), 1);
  expect(refusal.permanent).toBe(true);
  expect(refusal.skewRun).toBeNull();
  expect(blocksRetry(refusal)).toBe(true);
  // It has to NAME the file: the banner lists it, and "a file was refused" is not something anybody
  // can act on when a stage carries eleven photographs.
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
  expect(refusal.message).toContain("out of step");
  // Recorded and shown exactly as a permanent one is, and skipped for the REST OF THIS RUN so the
  // pass does not spin on it…
  expect(blocksRetry(refusal)).toBe(true);
  // …and re-attempted by itself on the next one, which is the whole of `blocksRetry`'s policy and
  // the reason `skewRun` is an app run rather than a build number. Without this the app could not
  // recover from a skew after the skew had gone: the designer would be told to correct a file that
  // was never wrong, for ever, with no button that would help.
  expect(blocksRetry({ ...refusal, skewRun: "a-previous-app-run" })).toBe(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * WHAT THE BANNER SAYS
 * ──────────────────────────────────────────────────────────────────────────── */

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
  expect(outcome.title).toBe("6 items were refused");
});

test("one refusal is singular, because a banner that cannot count is a banner nobody trusts", () => {
  expect(syncOutcome({ ...IDLE, failed: 1 }).title).toBe("1 item was refused");
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
    schemaVersion: 3,
    localId: "dwlocal-1",
    remoteId: "srv-1",
    header: {
      title: "Bhujodi",
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

/* ────────────────────────────────────────────────────────────────────────────
 * "THIS DEVICE CANNOT TELL YOU" IS NOT "THERE IS NOTHING HERE"
 * ──────────────────────────────────────────────────────────────────────────── */

test("a store that has answered every time is reported as answering", () => {
  expect(storeIsAnswering({ readFailedAt: null, writeFailedAt: null })).toBe(true);
});

test("a failed read and a failed write are both trouble, and a full disk reads perfectly well", () => {
  expect(storeIsAnswering({ readFailedAt: 1, writeFailedAt: null })).toBe(false);
  expect(storeIsAnswering({ readFailedAt: null, writeFailedAt: 1 })).toBe(false);
});
