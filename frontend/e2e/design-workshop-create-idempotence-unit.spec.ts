import { expect, test } from "@playwright/test";

import {
  createWorkshopOrKeepItHere,
  type DwCreateHeader,
  type DwCreateIo
} from "@/lib/designWorkshopCreate";
import { newLocalDraft, setDraftSessionUser, type DwDraft } from "@/lib/designWorkshopStore";

const SENT_AT = 1_767_225_600_000;

const HEADER: DwCreateHeader = { title: "Ikat, Barpali", templateId: "DCH_STANDARD", workshopId: "wsp-7" };

function gatewayTimeout(): Error {
  return new Error("504 Gateway Timeout");
}

function form(overrides: Partial<DwCreateIo> = {}) {
  const posted: DwCreateHeader[] = [];
  const kept: DwDraft[] = [];
  const io: DwCreateIo = {
    couldReachServer: () => true,
    post: async (header) => {
      posted.push(header);
      return { id: "dw-server-1" };
    },
    keepHere: async (header, options) => {
      const draft = newLocalDraft(header, options);
      kept.push(draft);
      return { id: draft.localId };
    },
    transient: () => true,
    now: () => SENT_AT,
    ...overrides
  };
  return { io, posted, kept };
}

test.beforeEach(() => {
  setDraftSessionUser("admin-1", "ADMIN");
});

test("OFFLINE: nothing is sent, and the draft carries NO createSentAt", async () => {
  const { io, posted, kept } = form({ couldReachServer: () => false });

  const created = await createWorkshopOrKeepItHere(HEADER, io);

  expect(posted).toHaveLength(0);
  expect(kept).toHaveLength(1);
  expect(kept[0].createSentAt ?? null).toBeNull();
  expect(created.id).toBe(kept[0].localId);
});

test("A TRANSIENT FAILURE: the POST went out, so the draft IS stamped", async () => {
  const { io, posted, kept } = form();
  io.post = async (header) => {
    posted.push(header);
    throw gatewayTimeout();
  };

  const created = await createWorkshopOrKeepItHere(HEADER, io);

  expect(posted).toHaveLength(1);
  expect(kept).toHaveLength(1);
  expect(kept[0].createSentAt).toBe(SENT_AT);
  expect(created.id).toBe(kept[0].localId);
});

test("the two arms are ASYMMETRIC — a tidy-up that stamps both, or neither, breaks one of them", async () => {
  const offline = form({ couldReachServer: () => false });
  await createWorkshopOrKeepItHere(HEADER, offline.io);

  const failed = form();
  failed.io.post = async () => {
    throw gatewayTimeout();
  };
  await createWorkshopOrKeepItHere(HEADER, failed.io);

  expect([offline.kept[0].createSentAt ?? null, failed.kept[0].createSentAt ?? null]).toEqual([null, SENT_AT]);
});

test("A REFUSAL IS NOT KEPT HERE AT ALL — it is shown, and no draft is written", async () => {
  const { io, kept } = form({ transient: () => false });
  io.post = async () => {
    throw new Error("That designer's empanelment has lapsed");
  };

  await expect(createWorkshopOrKeepItHere(HEADER, io)).rejects.toThrow(/empanelment/);
  expect(kept).toHaveLength(0);
});

test("the stamp is the moment the request was SENT, not the moment this browser gave up", async () => {
  const ticks = [SENT_AT, SENT_AT + 30_000];
  const { io, kept } = form({ now: () => ticks.shift() ?? -1 });
  let failedAt = -1;
  io.post = async () => {
    failedAt = io.now();
    throw gatewayTimeout();
  };

  await createWorkshopOrKeepItHere(HEADER, io);

  expect(failedAt).toBe(SENT_AT + 30_000);
  expect(kept[0].createSentAt).toBe(SENT_AT);
});

test("the stamp is exactly what arms the look-before-you-send, and its absence is what skips it", () => {
  const armsTheResolver = (draft: DwDraft) => (draft.createSentAt ?? null) !== null;

  expect(armsTheResolver(newLocalDraft({ title: "Ikat, Barpali" }))).toBe(false);
  expect(armsTheResolver(newLocalDraft({ title: "Ikat, Barpali" }, { createSentAt: SENT_AT }))).toBe(true);
});

test("a draft written by an older build, with no key at all, reads as 'nothing outstanding'", () => {
  const older = { ...newLocalDraft({ title: "Ikat, Barpali" }) } as DwDraft;
  delete older.createSentAt;

  expect(older.createSentAt).toBeUndefined();
  expect((older.createSentAt ?? null) === null).toBe(true);
});

test("the WHOLE header reaches the local draft — including the workshop it was linked to", async () => {
  const offline = form({ couldReachServer: () => false });
  await createWorkshopOrKeepItHere(HEADER, offline.io);

  const failed = form();
  failed.io.post = async () => {
    throw gatewayTimeout();
  };
  await createWorkshopOrKeepItHere(HEADER, failed.io);

  for (const draft of [offline.kept[0], failed.kept[0]]) {
    expect(draft.header.workshopId).toBe("wsp-7");
    expect(draft.header.title).toBe("Ikat, Barpali");
    expect(draft.remoteId).toBeNull();
  }
});
