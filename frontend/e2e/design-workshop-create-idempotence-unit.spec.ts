import { expect, test } from "@playwright/test";

import {
  createWorkshopOrKeepItHere,
  type DwCreateHeader,
  type DwCreateIo
} from "@/lib/designWorkshopCreate";
import { newLocalDraft, setDraftSessionUser, type DwDraft } from "@/lib/designWorkshopStore";

/**
 * ONE SUBMIT MUST NOT BECOME TWO GOVERNMENT RECORDS — AND THE WHOLE OF THAT IS ONE ASYMMETRY.
 *
 * `POST /design-workshops` carries no client key and the create route de-duplicates nothing, so a
 * workshop sent twice is two rows under one title in a ministry index, one of them empty for ever.
 * The compensating machinery lives in `lib/designWorkshopStore.ts` — `DwDraft.createSentAt` records
 * that a create is unaccounted for, and the sync pass reads it and asks `resolveInterruptedCreate`
 * ("is my workshop already up there?") before it posts again. It is armed by the STAMP and by
 * nothing else: an unstamped draft is posted without a look.
 *
 * THE CREATE FORM HAS TWO WAYS TO END WITH A LOCAL DRAFT AND THEY MUST BE STAMPED DIFFERENTLY:
 *
 *   the browser was offline        nothing was ever sent   → NOT stamped
 *   the POST failed transiently    the request went out     → STAMPED
 *
 * Both halves are load-bearing and each one's absence is a different disaster. Without the stamp on
 * the transient arm, a 504 on a create that COMMITTED files the workshop a second time on the next
 * sync pass — the defect these tests were written for. With a stamp on the offline arm, every
 * workshop ever started in a courtyard would arm the resolver, whose single-candidate arm ADOPTS:
 * an admin who already had a workshop of this exact title on the server would have this draft
 * pointed at it silently and a fortnight of stages pushed into the wrong record under a 200.
 * Android argues the same two paragraphs at `couldHaveReachedServer` in `WorkshopListScreen.kt`.
 *
 * WHY THESE ASSERTIONS CAN RUN AT ALL. There is no React renderer in this project's
 * devDependencies, so a decision written inside the create form's `submit` is a decision no test can
 * reach — which is exactly how the transient arm went unstamped through review. The decision now
 * lives in `lib/designWorkshopCreate.ts` behind `DwCreateIo`, and the record it produces comes from
 * `newLocalDraft`, which is pure. Nothing here needs a network, IndexedDB or a browser.
 *
 * Run: `npx playwright test e2e/design-workshop-create-idempotence-unit.spec.ts --reporter=line`
 */

/** A fixed instant, so a stamp can be compared rather than merely observed to exist. */
const SENT_AT = 1_767_225_600_000;

/** What the form builds: the title an admin typed, plus the workshop they picked to link it to. */
const HEADER: DwCreateHeader = { title: "Ikat, Barpali", templateId: "DCH_STANDARD", workshopId: "wsp-7" };

/** A 504 — the shape of failure this whole mechanism exists for: sent, committed, answer lost. */
function gatewayTimeout(): Error {
  return new Error("504 Gateway Timeout");
}

/**
 * A create form wired to fakes, recording what was sent and what was kept.
 *
 * `keepHere` runs the REAL {@link newLocalDraft}, so the tests below assert the stamp on the draft
 * record itself rather than on an argument that a store might have ignored.
 */
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
  // `newLocalDraft` stamps the owner from the session; an unowned draft is what let one designer's
  // workshop be drained under the next designer to sign in on the same laptop.
  setDraftSessionUser("admin-1", "ADMIN");
});

/* ── The two arms, and the difference between them ─────────────────────────────────────────── */

test("OFFLINE: nothing is sent, and the draft carries NO createSentAt", async () => {
  const { io, posted, kept } = form({ couldReachServer: () => false });

  const created = await createWorkshopOrKeepItHere(HEADER, io);

  expect(posted).toHaveLength(0);
  expect(kept).toHaveLength(1);
  // Null is the honest answer: no request left this device, so no answer is outstanding, so the
  // sync pass creates the workshop normally instead of hunting the server for one that cannot exist.
  expect(kept[0].createSentAt ?? null).toBeNull();
  // The navigation goes to the LOCAL id, which every design-workshop route resolves.
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
  // THE ASSERTION THE DEFECT WAS: this was `null` in the shipped build, so the next sync pass read
  // "never created", skipped `resolveInterruptedCreate` and posted the workshop a second time.
  expect(kept[0].createSentAt).toBe(SENT_AT);
  expect(created.id).toBe(kept[0].localId);
});

test("the two arms are ASYMMETRIC — a tidy-up that stamps both, or neither, breaks one of them", async () => {
  /*
    Stated as one assertion because it is one rule. "Always stamp" and "never stamp" are both single
    edits away, both look like simplifications, and each is a different way to lose a record.
  */
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
  /*
    A 422 naming a designer whose empanelment has lapsed is not "try again later": no draft is
    minted, so there is nothing to stamp and nothing sitting in the list offering to sync for ever.
    The error reaches the form's own catch, which renders the server's sentence.
  */
  const { io, kept } = form({ transient: () => false });
  io.post = async () => {
    throw new Error("That designer's empanelment has lapsed");
  };

  await expect(createWorkshopOrKeepItHere(HEADER, io)).rejects.toThrow(/empanelment/);
  expect(kept).toHaveLength(0);
});

test("the stamp is the moment the request was SENT, not the moment this browser gave up", async () => {
  /*
    READ BEFORE THE POST, which is the same instant Android reads `couldHaveReachedServer` at. The
    two differ by a whole request timeout on the connections this feature exists for, and the field
    says on the tin that it holds when the create was sent.
  */
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

/* ── What the stamp is FOR: the gate the sync pass reads ───────────────────────────────────── */

test("the stamp is exactly what arms the look-before-you-send, and its absence is what skips it", () => {
  /*
    `runSync` writes this test's predicate as
    `(draft.createSentAt ?? null) === null ? null : await resolveInterruptedCreate(draft)`.
    Repeated here so the two facts meet in one place: an offline draft is skipped (nothing to find),
    and an interrupted one is looked up before anything is posted.
  */
  const armsTheResolver = (draft: DwDraft) => (draft.createSentAt ?? null) !== null;

  expect(armsTheResolver(newLocalDraft({ title: "Ikat, Barpali" }))).toBe(false);
  expect(armsTheResolver(newLocalDraft({ title: "Ikat, Barpali" }, { createSentAt: SENT_AT }))).toBe(true);
});

test("a draft written by an older build, with no key at all, reads as 'nothing outstanding'", () => {
  /*
    `createSentAt` is optional and spends no schema rung, so drafts already on a designer's laptop
    carry no memory of an interrupted create. `?? null` is what makes absent and null the same fact,
    and every reader in the store is written that way.
  */
  const older = { ...newLocalDraft({ title: "Ikat, Barpali" }) } as DwDraft;
  delete older.createSentAt;

  expect(older.createSentAt).toBeUndefined();
  expect((older.createSentAt ?? null) === null).toBe(true);
});

/* ── The header itself, which one of these arms has already lost once ──────────────────────── */

test("the WHOLE header reaches the local draft — including the workshop it was linked to", async () => {
  /*
    `workshopId` was dropped by a hand-copied field list on exactly this fallback, and the loss only
    surfaces a fortnight later: a stage with `refScope: "WORKSHOP"` falls back to the whole table and
    the designer picks participants out of the entire repository. Asserted on both arms, because
    both of them mint a draft.
  */
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
