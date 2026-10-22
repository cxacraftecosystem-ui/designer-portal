import { createHash, randomBytes } from "node:crypto";
import { readFileSync } from "node:fs";

import { expect, test } from "@playwright/test";

import { ApiError, describeApiDetail } from "@/lib/api";
import { mintKey, questionnaireClient, type JsonFetcher } from "@/lib/questionnaire/api";
import {
  enqueueStep,
  existingSessionUnit,
  fetchTransport,
  memoryQueueStore,
  runUnit,
  type ClipStep,
  type QueueOptions
} from "@/lib/questionnaire/queue";

/**
 * DESIGNER-PORTAL'S SHARED QUESTIONNAIRE CLIENT AGAINST A REAL BACKEND AND A REAL BUCKET (stream U1).
 *
 * No fake API and no fake transport: the browser's own client (`questionnaireClient`, the object
 * `questionnaireApi` is) is pointed at designer-portal's FastAPI with QUESTIONNAIRE_V2 on, a migrated
 * Postgres and a MinIO bucket, and the browser's own byte transport PUTs a take to the URL the server
 * signs. The seed (`scripts/ci/seed_qv2_stack.py`) is a design workshop with a form in the shared
 * tables bound to it as its primary form, three artisans and four accounts.
 *
 * CI only: the `web-v2-stack` job starts the stack and sets QV2_STACK_API and QV2_STACK_SEED. It
 * refuses any host but loopback, because it writes.
 */

const API = process.env.QV2_STACK_API ?? "";
const SEED_PATH = process.env.QV2_STACK_SEED ?? "";
const LOCAL = /^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\/?$/.test(API);

test.skip(!API || !SEED_PATH, "Set QV2_STACK_API and QV2_STACK_SEED (the web-v2-stack CI job does).");
test.skip(Boolean(API) && !LOCAL, "This spec writes; it runs only against a local stack.");
test.describe.configure({ mode: "serial" });

type Seed = {
  questionnaireId: string;
  designWorkshopId: string;
  sections: Record<string, string>;
  questions: Record<string, string>;
  artisans: string[];
  users: Record<"lead" | "other" | "admin" | "stranger", string>;
  tokens: Record<"lead" | "other" | "admin" | "stranger", string>;
};
const seed = (): Seed => JSON.parse(readFileSync(SEED_PATH, "utf8")) as Seed;

function stackFetch(token: string): JsonFetcher {
  return async <T>(path: string, init: RequestInit = {}): Promise<T> => {
    const headers = new Headers(init.headers);
    headers.set("Authorization", `Bearer ${token}`);
    if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
    headers.set("X-Client-Time", new Date().toISOString());
    headers.set("X-App-Version", "web-ci");
    headers.set("X-App-Platform", "web");
    const response = await fetch(`${API.replace(/\/$/, "")}/api${path}`, { ...init, headers, redirect: "manual" });
    if (response.status === 204) return undefined as T;
    const type = response.headers.get("content-type") ?? "";
    const body = type.includes("application/json") ? await response.json() : await response.text();
    if (!response.ok) {
      const detail = typeof body === "object" && body && "detail" in body ? (body as { detail: unknown }).detail : undefined;
      throw new ApiError(response.status, describeApiDetail(detail, `HTTP ${response.status}`), body, response.headers);
    }
    return body as T;
  };
}

const as = (who: keyof Seed["tokens"]) => questionnaireClient(stackFetch(seed().tokens[who]));
const today = () => new Date().toISOString().slice(0, 10);
const sectionOf = (s: Seed, code: string) => s.sections[code];
const lineage = (s: Seed, prefix: string) => Object.entries(s.questions).find(([code]) => code.startsWith(prefix))![1];

async function guestSession(name = "Shopper at Johari Bazaar") {
  const s = seed();
  return as("lead").createSession({
    clientKey: mintKey("stack"),
    questionnaireId: s.questionnaireId,
    workshopId: null,
    designWorkshopId: s.designWorkshopId,
    sessionDate: today(),
    startedAt: new Date().toISOString(),
    purpose: "FIRST_SITTING",
    place: "Bagru",
    languageCode: null,
    languageOther: null,
    note: "At the stall",
    participants: [{ artisanId: s.artisans[0] }],
    respondents: [{ name, kind: "GUEST" }]
  });
}

test("@qf:B36 @qf:B35 the stack is designer-portal's, v2 is on, and the web's build headers are taken", async () => {
  const config = await as("lead").config();
  expect(config.v2Enabled).toBe(true);
  expect(config.portal).toBe("designer-portal");
  expect(config.minVersionCode).toEqual({ android: null, web: null });
});

test("@qf:B1 @qf:A18 @qf:B3 @qf:B31 a session with an artisan and a guest on the workshop's primary form; the title is generated", async () => {
  const s = seed();
  const resolved = await as("lead").formFor({ designWorkshopId: s.designWorkshopId });
  expect(resolved).toMatchObject({ questionnaireId: s.questionnaireId, reason: "PRIMARY_BINDING" });
  const session = await guestSession();
  expect(session.respondents?.map((r) => r.name)).toEqual(["Shopper at Johari Bazaar"]);
  expect(session.designWorkshopId).toBe(s.designWorkshopId);
  expect(session.title).toMatch(/Bagru/);
  expect(session.title).toMatch(/Hand-Block-Printing/);
  const structure = await as("lead").formStructure(s.questionnaireId);
  expect(structure.sections.map((x) => x.questions.filter((q) => q.isRequired).length)).toEqual([1, 1, 1]);
});

test("@qf:B4 @qf:A11 @qf:B19 @qf:B21 @qf:A20 @qf:B20 the lock: a second researcher is refused per item, an admin is not; revisions and notes are kept", async () => {
  const s = seed();
  const session = await guestSession("Visitor");
  const guest = session.respondents![0];
  const l = lineage(s, "A");
  const lead = as("lead");
  const saved = await lead.putAnswers([
    { artisanId: s.artisans[0], questionLineageId: l, text: "Wooden blocks", origin: "TYPED", attribution: "GROUP_SHARED", sessionId: session.id, sourceRecordingId: null, note: "Asked twice" },
    { respondentId: guest.id, questionLineageId: l, text: "Wooden blocks", origin: "TYPED", attribution: "GROUP_SHARED", sessionId: session.id, sourceRecordingId: null }
  ]);
  expect(saved.results.map((r) => r.status)).toEqual(["SAVED", "SAVED"]);
  // B4: the second researcher joins by the session's code (online).
  const other = as("other");
  await other.join(session.joinCode!);
  const refused = await other.putAnswers([
    { artisanId: s.artisans[0], questionLineageId: l, text: "Metal blocks", origin: "TYPED", attribution: "INDIVIDUAL", sessionId: session.id, sourceRecordingId: null, baseVersion: 1 },
    { artisanId: s.artisans[0], questionLineageId: lineage(s, "B"), text: "Indigo", origin: "TYPED", attribution: "INDIVIDUAL", sessionId: session.id, sourceRecordingId: null }
  ]);
  expect(refused.results.map((r) => [r.status, r.code ?? null])).toEqual([["FORBIDDEN", "ANSWER_LOCKED"], ["SAVED", null]]);
  const admin = as("admin");
  const changed = await admin.putAnswers([
    { artisanId: s.artisans[0], questionLineageId: l, text: "Teak blocks", origin: "TYPED", attribution: "INDIVIDUAL", sessionId: session.id, sourceRecordingId: null, baseVersion: 1 }
  ]);
  expect(changed.results[0].status).toBe("SAVED");
  const detail = await lead.getSession(session.id);
  const answer = detail.answers.find((a) => a.artisanId === s.artisans[0] && a.questionLineageId === l)!;
  expect(answer.text).toBe("Teak blocks");
  expect(detail.answers.find((a) => a.respondentId === guest.id)?.text).toBe("Wooden blocks");
  const history = await lead.answerRevisions(answer.id);
  expect(history.revisions.map((r) => r.text)).toEqual(["Teak blocks", "Wooden blocks"]);
});

test("@qf:A17 @qf:B5 @qf:B6 @qf:B9 a guest's name is retracted, an artisan and a researcher added, the place corrected without touching the note", async () => {
  const s = seed();
  const lead = as("lead");
  const session = await guestSession("Mobile number given");
  const guest = session.respondents![0];
  const retracted = await lead.patchRespondent(session.id, guest.id, { name: "" });
  expect(retracted.name).toBe("Respondent");
  await lead.addParticipant(session.id, s.artisans[1]);
  await lead.addResearcher(session.id, { userId: s.users.other, role: "CONTRIBUTOR" });
  const before = await lead.getSession(session.id);
  const patched = await lead.patchSession(session.id, { version: before.version, place: "Sanganer" });
  expect(patched.place).toBe("Sanganer");
  expect(patched.note).toBe("At the stall");
  const after = await lead.getSession(session.id);
  expect(after.participants.map((p) => p.artisanId).sort()).toEqual([s.artisans[0], s.artisans[1]].sort());
  expect(after.researchers.map((r) => r.userId)).toContain(s.users.other);
});

test("@qf:A14 a session is deleted only by a content admin, restorably, and is gone from every list meanwhile", async () => {
  const lead = as("lead");
  const admin = as("admin");
  const session = await guestSession("To delete");
  await expect(lead.deleteSession(session.id)).rejects.toThrow();
  await admin.deleteSession(session.id);
  expect((await lead.listSessions({ limit: 100 })).items.map((x) => x.id)).not.toContain(session.id);
  expect((await admin.deletedSessions({ limit: 100 })).items.map((x) => x.id)).toContain(session.id);
  await expect(lead.deletedSessions({})).rejects.toThrow();
  await admin.restoreSession(session.id);
  expect((await lead.listSessions({ limit: 100 })).items.map((x) => x.id)).toContain(session.id);
});

test("@qf:A12 @qf:B26 the annexure and the artisan's record read the sessions", async () => {
  const s = seed();
  const lead = as("lead");
  const annexure = await lead.annexure({ designWorkshopId: s.designWorkshopId });
  const forms = annexure.groups.flatMap((g) => g.forms);
  expect(annexure.groups.map((g) => g.kind)).toContain("WORKSHOP_INTERVIEW");
  expect(forms.find((f) => f.questionnaireId === s.questionnaireId)?.sittings.length).toBeGreaterThan(0);
  const record = await lead.artisanRecord(s.artisans[0]);
  expect(record.instruments.find((i) => i.questionnaireId === s.questionnaireId)?.sessions.length).toBeGreaterThan(0);
});

test("@qf:A9 @qf:D2 an admin rebinds the form and sets the workshop kind's default; a researcher may not", async () => {
  const s = seed();
  const admin = as("admin");
  await expect(as("lead").putBindings(s.questionnaireId, [{ workshopId: null, designWorkshopId: s.designWorkshopId, isPrimary: false }])).rejects.toThrow();
  const out = await admin.putBindings(s.questionnaireId, [{ workshopId: null, designWorkshopId: s.designWorkshopId, isPrimary: false }]);
  expect(out.bindings).toEqual([{ workshopId: null, designWorkshopId: s.designWorkshopId, isPrimary: false }]);
  expect((await admin.formFor({ designWorkshopId: s.designWorkshopId })).reason).toBe("ONLY_BINDING");
  await admin.putTypeDefault({ questionnaireId: s.questionnaireId, designWorkshopKind: "BASELINE" });
  await admin.putBindings(s.questionnaireId, [{ workshopId: null, designWorkshopId: s.designWorkshopId, isPrimary: true }]);
  expect((await admin.formFor({ designWorkshopId: s.designWorkshopId })).reason).toBe("PRIMARY_BINDING");
});

async function options(api: ReturnType<typeof questionnaireClient>): Promise<QueueOptions> {
  const config = await api.config();
  return { multipartThresholdBytes: config.multipartThresholdBytes, partSizeBytes: config.partSizeBytes };
}

test("@qf:B14 @qf:B16 @qf:B17 @qf:B10 @qf:C1 @qf:B34 a take goes up through the outbox to the bucket and lands as a recording, paused for transcription", async () => {
  const s = seed();
  const api = as("lead");
  const session = await guestSession("Take test");
  const store = memoryQueueStore();
  const bytes = new Uint8Array(randomBytes(4096));
  const hash = createHash("sha256").update(bytes).digest("hex");
  const at = new Date().toISOString();
  const step: ClipStep = {
    kind: "clip",
    status: "PENDING",
    clientClipKey: mintKey("clip"),
    blobKey: "blob-1",
    label: "Section A",
    sizeBytes: bytes.byteLength,
    contentType: "audio/webm",
    extension: "webm",
    presign: { contentSha256: hash, targets: [{ sectionId: sectionOf(s, "A") }], subjectArtisanIds: [s.artisans[0]], deviceCapturedAt: at, clockOffsetMs: 0 },
    recording: {
      targets: [{ sectionId: sectionOf(s, "A") }],
      subjects: [
        { artisanId: s.artisans[0], role: "ANSWERED", named: true, repeatReason: null },
        { respondentId: session.respondents![0].id, role: "PRESENT", named: true, repeatReason: null }
      ],
      deviceCapturedAt: at,
      clockOffsetMs: 0,
      capturedAtSource: "DEVICE",
      durationMs: 1000,
      rosterCount: 2,
      contentSha256: hash,
      continuationOfId: null,
      replacesRecordingId: null,
      ackedRecordingIds: []
    }
  };
  await store.putBlob(step.blobKey, new Blob([bytes]));
  await enqueueStep(store, existingSessionUnit(session), step);
  const out = await runUnit((await store.load())[0], api, store, fetchTransport, await options(api));
  expect((out.steps[0] as ClipStep).status).toBe("DONE");
  const detail = await api.getSession(session.id);
  const take = detail.recordings.find((r) => r.clientClipKey === step.clientClipKey)!;
  expect(take).toBeTruthy();
  expect(take.subjects.map((x) => x.artisanId)).toEqual([s.artisans[0]]);
  expect(take.respondents?.map((g) => g.respondentId)).toEqual([session.respondents![0].id]);
  expect(take.transcript.paused).toBe(true);
  expect(take.consent.verdict).toBe("ALLOW");
  const grid = detail.grid;
  const cell = grid.sections.find((x) => x.sectionId === sectionOf(s, "A"))!.cells.find((c) => c.artisanId === s.artisans[0])!;
  expect(cell.state).toBe("RECORDED");
});

test("@qf:C10 the record readers count a session's answers: completeness reads the shared answers", async () => {
  const s = seed();
  const session = await guestSession("Reader check");
  const read = () => stackFetch(seed().tokens.lead)<{ completeness: { missing: { key: string }[] } }>(`/questionnaire/interviews/${session.id}`);
  expect((await read()).completeness.missing.map((m) => m.key)).toContain("answers");
  await as("lead").putAnswers([
    { artisanId: s.artisans[0], questionLineageId: lineage(s, "C"), text: "Sold at the haat", origin: "TYPED", attribution: "INDIVIDUAL", sessionId: session.id, sourceRecordingId: null }
  ]);
  expect((await read()).completeness.missing.map((m) => m.key)).not.toContain("answers");
});

test("@qf:B32 an admin makes a form the global default; a session with nothing bound is offered it", async () => {
  const s = seed();
  const put = (who: keyof Seed["tokens"], isDefault: boolean) =>
    stackFetch(seed().tokens[who])<{ isDefault: boolean }>(`/questionnaires/${s.questionnaireId}/default`, { method: "PUT", body: JSON.stringify({ isDefault }) });
  await expect(put("lead", true)).rejects.toThrow();
  expect((await put("admin", true)).isDefault).toBe(true);
  try {
    expect(await as("lead").formFor({})).toMatchObject({ questionnaireId: s.questionnaireId, reason: "GLOBAL_DEFAULT" });
  } finally {
    expect((await put("admin", false)).isDefault).toBe(false);
  }
});
