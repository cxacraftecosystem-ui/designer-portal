import { createHash } from "node:crypto";

import { expect, test } from "@playwright/test";

import { answerItems, answerLocked, sessionSubjects } from "@/components/questionnaire/AnswerBox";
import { cellMark } from "@/components/questionnaire/CoverageGrid";
import { requiredUnanswered } from "@/components/questionnaire/instrumentQuestions";
import { formOptions, sessionCreateBody, startBlocked } from "@/components/questionnaire/SessionStartSheet";
import { canOfferAnswers } from "@/components/questionnaire/TakeSheet";
import { withRequired } from "@/components/questionnaire/SessionWorkspace";
import { recordClockOffset, clockOffset } from "@/lib/api";
import { ownerKey, toGridView, type CoverageGrid, type Recording } from "@/lib/questionnaire/api";
import { buildBundle, readBundleFile, validateBundle, bundleBlob, BundleError } from "@/lib/questionnaire/bundle";
import { Sha256, sha256Hex } from "@/lib/questionnaire/hashFile";
import { prompts } from "@/lib/questionnaire/prompts";
import { rosterArtisans, takeSubjects } from "@/lib/questionnaire/view";
import { consolidatedTarget, inTab, interviewTarget, legacyQuestionnaireTarget } from "@/lib/questionnaireLinks";
import { mostRecentWorkshop, titleWorkshop, workshopScope } from "@/lib/questionnairePortal";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { answerParts, answerEditorValue } from "@/components/questionnaires/AnswerTextEditor";
import { questionnaireStrings } from "@/lib/questionnaire/strings";
import { qs, WEB_ONLY_STRINGS } from "@/lib/questionnaire/qs";
import { assembleTake, recoverable, recoveredMinutes, TIMESLICE_MS, type TakeDraft } from "@/lib/questionnaire/recorderStore";
import { prefillItems, prefillText } from "@/lib/questionnaire/prefill";
import { discardStep, memoryQueueStore, sendStepAgain, sendWithoutConflicts, trayItems, type SessionUnit } from "@/lib/questionnaire/queue";
import { bundleFileName } from "@/lib/questionnaire/bundle";
import { cacheRecordIsReadable, type CachedQuestionnaireForm } from "@/lib/questionnaireFormCache";
import {
  authoringState,
  drainAuthoring,
  queueForm,
  queueQuestion,
  queueSection,
  resetAuthoringCache,
  stepsForForm
} from "@/lib/questionnaireAuthoringOutbox";
import { ApiError } from "@/lib/api";

/**
 * Designer-portal's shared questionnaire client, the pure rules (stream U1). No server and no
 * browser: the subject keys a guest needs, the lock, group answers, required questions, the coverage
 * marks (✎ typed, three overrides), the start sheet's guest and form order, the handoff bundle, the
 * SHA-256 every take is checked with, and where every retired global-form link lands now.
 */

const vectors: [string, string][] = [
  ["", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"],
  ["abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"],
  ["abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq", "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"]
];

test("SHA-256 matches the published vectors, whole and fed in odd pieces", async () => {
  for (const [text, hex] of vectors) {
    expect(new Sha256().update(new TextEncoder().encode(text)).hex()).toBe(hex);
  }
  const big = new Uint8Array(1_000_003).map((_, i) => (i * 31) % 251);
  const expected = createHash("sha256").update(big).digest("hex");
  const pieces = new Sha256();
  for (let at = 0; at < big.length; at += 777) pieces.update(big.subarray(at, at + 777));
  expect(pieces.hex()).toBe(expected);
  expect(await sha256Hex(new Blob([big]))).toBe(expected);
});

test("a guest is a subject of its own, keyed R:, beside the present artisans", () => {
  const subjects = sessionSubjects({
    participants: [
      { artisanId: "a1", name: "Giriraj", joinedAt: null, leftAt: null, consent: null },
      { artisanId: "a2", name: "Meena", joinedAt: null, leftAt: "2026-10-22T10:00:00Z", consent: null }
    ],
    respondents: [
      { id: "r1", sessionId: "s1", name: "Shopper", kind: "GUEST", notes: null, userId: null },
      { id: "r2", sessionId: "s1", name: "Registered", kind: "GUEST", notes: null, userId: null, artisanId: "a9" }
    ]
  });
  expect(subjects.map((s) => s.key)).toEqual(["A:a1", "R:r1"]);
  expect(ownerKey({ respondentId: "r1" })).toBe("R:r1");
  expect(ownerKey({ artisanId: "a1" })).toBe("A:a1");
});

test("a group answer is one GROUP_SHARED item per person, guests included; a single one is INDIVIDUAL", () => {
  const subjects = [
    { key: "A:a1", artisanId: "a1", respondentId: null },
    { key: "R:r1", artisanId: null, respondentId: "r1" }
  ];
  const existing = [{ id: "x", version: 3, text: "old", origin: "TYPED" as const, attribution: "INDIVIDUAL" as const, pinned: false, sourceRecordingId: null, respondentId: "r1" }];
  const all = answerItems({ sessionId: "s1", lineageId: "l1", text: "Indigo", forWhom: "__all__", subjects, existing, note: "asked twice" });
  expect(all).toEqual([
    { artisanId: "a1", questionLineageId: "l1", text: "Indigo", origin: "TYPED", attribution: "GROUP_SHARED", sessionId: "s1", sourceRecordingId: null, note: "asked twice" },
    { respondentId: "r1", questionLineageId: "l1", text: "Indigo", origin: "TYPED", attribution: "GROUP_SHARED", sessionId: "s1", sourceRecordingId: null, note: "asked twice", baseVersion: 3 }
  ]);
  const one = answerItems({ sessionId: "s1", lineageId: "l1", text: "Madder", forWhom: "R:r1", subjects, existing, origin: "TRANSCRIPT", sourceRecordingId: "rec1" });
  expect(one).toHaveLength(1);
  expect(one[0]).toMatchObject({ respondentId: "r1", attribution: "INDIVIDUAL", origin: "TRANSCRIPT", sourceRecordingId: "rec1" });
});

test("the one lock rule: someone else's answer with text, unless a lead or an admin", () => {
  const theirs = { text: "Wool from Bikaner", actorId: "nandini" };
  expect(answerLocked(theirs, "me", false)).toBe(true);
  expect(answerLocked(theirs, "nandini", false)).toBe(false);
  expect(answerLocked(theirs, "me", true)).toBe(false);
  expect(answerLocked({ text: "  ", actorId: "nandini" }, "me", false)).toBe(false);
  expect(answerLocked(null, "me", false)).toBe(false);
  expect(prompts.p15({ name: "Nandini" }).body).toBe("Answered by Nandini. Only they or a lead can change it.");
});

test("required questions: typed or recorded, either counts; Finish reports and never blocks", () => {
  const byId = {
    s1: [
      { lineageId: "l1", displayCode: "A1", prompt: "One", required: true, helpText: null, artisanField: null },
      { lineageId: "l2", displayCode: "A2", prompt: "Two", required: true, helpText: null, artisanField: null },
      { lineageId: "l3", displayCode: "A3", prompt: "Three", required: false, helpText: null, artisanField: null }
    ],
    s2: [{ lineageId: "l4", displayCode: "B1", prompt: "Four", required: true, helpText: null, artisanField: null }]
  };
  const answers = [{ questionLineageId: "l1", text: "yes", artisanId: "a1" }];
  const takes = [{ removedAt: null, targets: [{ sectionId: "s2", questionLineageId: null }], subjects: [{ artisanId: "a1", role: "ANSWERED" }] }];
  expect(requiredUnanswered(byId, answers, ["A:a1"], takes)).toBe(1);
  expect(requiredUnanswered(byId, answers, ["A:a1", "R:r1"], takes)).toBe(3);
  const spec = withRequired(prompts.finish({ sections: 2, artisans: 1, pending: 0 }), 1);
  expect(spec.body).toContain("1 required questions unanswered.");
  expect(spec.buttons.map((b) => b.id)).toEqual(["finish"]);
});

test("coverage marks: ● recorded, ✎ typed with Add a recording?, and the three overrides", () => {
  const grid: CoverageGrid = {
    questionnaireId: "q",
    artisans: [{ artisanId: "a1", name: "Giriraj" }],
    respondents: [{ respondentId: "r1", name: "Shopper", subjectKey: "R:r1" }],
    sections: [
      {
        sectionId: "s1",
        code: "A",
        title: "Tools",
        cells: [{ artisanId: "a1", state: "PARTIAL", takes: 0, covered: 1, total: 3, needsDecision: false, override: null, source: "TYPED", addRecording: true }],
        guestCells: [{ respondentId: "r1", state: "RECORDED", takes: 1, covered: 3, total: 3, needsDecision: false, override: null, source: "RECORDED" }]
      },
      {
        sectionId: "s2",
        code: "B",
        title: "Dyes",
        cells: [{ artisanId: "a1", state: "NOT_RECORDED", takes: 0, covered: 0, total: 2, needsDecision: false, override: "NEEDS_REVIEW" }]
      }
    ]
  };
  const view = toGridView(grid);
  const typed = view.cells.find((c) => c.artisanId === "a1" && c.targetKey === "S:s1")!;
  expect(cellMark(typed)).toMatchObject({ mark: "✎ 1/3", label: "Typed · Add a recording?" });
  const guest = view.cells.find((c) => c.artisanId === "R:r1")!;
  expect(cellMark(guest).mark).toBe("●");
  const review = view.cells.find((c) => c.targetKey === "S:s2")!;
  expect(review.state).toBe("NEEDS_REVIEW");
  expect(cellMark(review).label).toBe("Needs review");
  for (const [state, label] of [["MARKED_COMPLETE", "Marked complete"], ["NEEDS_REDO", "Needs redo"]] as const) {
    expect(cellMark({ state, count: 0, partial: null }).label).toBe(label);
  }
});

test("the start sheet: Someone else needs a name, sends a guest and no artisans, filed under a design workshop", () => {
  const base = {
    workshopId: "dw1",
    questionnaireId: "q1",
    sessionDate: "2026-10-22",
    mode: "GUEST" as const,
    guestName: "",
    artisanIds: ["a1"],
    purpose: "FIRST_SITTING" as const,
    place: "",
    languageCode: "",
    languageOther: "",
    note: ""
  };
  expect(startBlocked(base)).toBe(true);
  expect(startBlocked({ ...base, guestName: "Shopper at Johari Bazaar" })).toBe(false);
  const body = sessionCreateBody({ ...base, guestName: " Shopper at Johari Bazaar " }, "ck1", new Date("2026-10-22T05:00:00Z"));
  expect(body).toMatchObject({ clientKey: "ck1", designWorkshopId: "dw1", workshopId: null, participants: [], respondents: [{ name: "Shopper at Johari Bazaar", kind: "GUEST" }] });
  expect(body).not.toHaveProperty("title");
  expect(sessionCreateBody({ ...base, mode: "INDIVIDUAL" }, "ck2").participants).toEqual([{ artisanId: "a1" }]);
  expect(workshopScope(null)).toEqual({ workshopId: null, designWorkshopId: null });
});

test("the form picker lists the workshop's forms first, grouped by kind", () => {
  const forms = [
    { id: "f1", title: "Baseline", kind: "WORKSHOP_INTERVIEW", isDefault: false, isShared: true, ownerId: "x" },
    { id: "f2", title: "Market", kind: "MARKET_SURVEY", isDefault: false, isShared: false, ownerId: "me" },
    { id: "f3", title: "Other", kind: null, isDefault: true, isShared: false, ownerId: "me" }
  ];
  const options = formOptions(forms, {
    questionnaireId: null,
    reason: "PICKER",
    bound: [
      { questionnaireId: "f2", isPrimary: false, kind: "MARKET_SURVEY" },
      { questionnaireId: "f1", isPrimary: false, kind: "WORKSHOP_INTERVIEW" }
    ],
    boundByKind: []
  });
  expect(options.map((o) => [o.value, o.group])).toEqual([
    ["f1", "This workshop’s forms · Workshop interview"],
    ["f2", "This workshop’s forms · Market survey"],
    ["f3", "Other forms"]
  ]);
});

test("a take's guests become respondent subjects, and presign asks about the artisans only", () => {
  const roster = ["a1", "R:r1"];
  expect(rosterArtisans(roster)).toEqual(["a1"]);
  expect(takeSubjects(roster, new Set(["R:r1"]), {})).toEqual([
    { artisanId: "a1", role: "PRESENT", named: true, repeatReason: null },
    { respondentId: "r1", role: "ANSWERED", named: true, repeatReason: null }
  ]);
});

test("Use as answer is offered only for a take whose transcript is in", () => {
  const take = (status: string, text: string | null, removedAt: string | null = null) =>
    ({ transcript: { status, paused: false, text, language: null }, removedAt }) as unknown as Recording;
  expect(canOfferAnswers(take("COMPLETED", "Mostly chisels"))).toBe(true);
  expect(canOfferAnswers(take("QUEUED", null))).toBe(false);
  expect(canOfferAnswers(take("COMPLETED", "x", "2026-10-22T00:00:00Z"))).toBe(false);
});

test("a bundle round-trips, carries no ids, and a newer major version is refused", async () => {
  const bundle = buildBundle(
    {
      title: "2nd Craft Toolkit",
      kind: "WORKSHOP_INTERVIEW",
      sections: [
        { code: "A", title: "Tools", questions: [{ prompt: "Which tools?", isRequired: true }, { prompt: "Retired", isActive: false }] },
        { code: "B", title: "Dyes", questions: [{ prompt: "Which dyes?" }] }
      ]
    },
    "designer-portal"
  );
  expect(bundle.v).toBe(2);
  expect(bundle.bundleId).toMatch(/^[0-9a-f]{32}$/);
  expect(bundle.questions.map((q) => q.prompt)).toEqual(["Which tools?", "Which dyes?"]);
  expect(JSON.stringify(bundle)).not.toMatch(/"(id|ownerId|createdById)"/);
  const back = await readBundleFile(await bundleBlob(bundle));
  expect(back).toEqual(bundle);
  expect(() => validateBundle({ ...bundle, v: 3 })).toThrow(BundleError);
  expect(() => validateBundle({ ...bundle, questions: [{ sectionCode: "Z", prompt: "x" }] })).toThrow(/names a section/);
});

test("the library tabs: Mine, Shared, Workshop; a deleted form is on Deleted only", () => {
  const row = (o: Partial<{ ownerId: string | null; isShared: boolean; designWorkshopId: string | null; deletedAt: string | null }>) => ({
    ownerId: null,
    isShared: false,
    designWorkshopId: null,
    deletedAt: null,
    ...o
  });
  expect(inTab(row({ ownerId: "me" }), "mine", "me")).toBe(true);
  expect(inTab(row({ isShared: true, ownerId: "x" }), "shared", "me")).toBe(true);
  expect(inTab(row({ designWorkshopId: "dw1", ownerId: "x" }), "workshop", "me")).toBe(true);
  expect(inTab(row({ designWorkshopId: "dw1", ownerId: "me" }), "workshop", "me")).toBe(false);
  expect(inTab(row({ ownerId: "me", deletedAt: "2026-10-22" }), "mine", "me")).toBe(false);
});

test("every retired global-form link lands somewhere real", () => {
  expect(legacyQuestionnaireTarget(new URLSearchParams("new=1"))).toBe("/questionnaire/sessions/new");
  expect(legacyQuestionnaireTarget(new URLSearchParams("artisanId=a1&artisanName=Giriraj"))).toBe("/questionnaire/sessions/new?artisanId=a1");
  expect(legacyQuestionnaireTarget(new URLSearchParams("edit=int1"))).toBe("/questionnaire");
  expect(legacyQuestionnaireTarget(new URLSearchParams(""))).toBeNull();
  expect(interviewTarget("int 1")).toBe("/questionnaire/sessions/int%201");
  expect(consolidatedTarget("a1")).toBe("/artisans/a1?tab=questionnaire");
});

test("the design workshop default and title half", () => {
  const ws = [
    { id: "old", title: "Old", startDate: "2026-01-01", createdAt: "2026-01-01", kind: null, locality: null, district: null, state: null, craft: null, canRecord: true },
    { id: "new", title: "New", startDate: "2026-10-01", createdAt: "2026-10-01", kind: "BASELINE", locality: "Bagru", district: "Jaipur", state: "Rajasthan", craft: "Hand Block Printing", canRecord: true },
    { id: "future", title: "Future", startDate: "2099-01-01", createdAt: "2026-10-02", kind: null, locality: null, district: null, state: null, craft: null, canRecord: true }
  ];
  expect(mostRecentWorkshop(ws)?.id).toBe("new");
  expect(titleWorkshop(ws[1])).toEqual({ locality: "Bagru", district: "Jaipur", state: "Rajasthan", craft: "Hand Block Printing" });
});

test("the clock offset is read off the Date header", () => {
  recordClockOffset("Wed, 22 Oct 2026 06:00:10 GMT", Date.parse("2026-10-22T06:00:00Z"), Date.parse("2026-10-22T06:00:00Z"));
  expect(clockOffset()?.offsetMs).toBe(10_000);
});


const ROOT = join(__dirname, "..", "..");

test("@qf:B37 the generated strings module holds every shared key with the shared words, and the screens use only known keys", () => {
  const shared = (JSON.parse(readFileSync(join(ROOT, "shared", "strings", "questionnaire.json"), "utf8")) as { strings: Record<string, string> }).strings;
  expect(Object.keys(questionnaireStrings).sort()).toEqual(Object.keys(shared).sort());
  for (const [key, text] of Object.entries(shared)) {
    const names = [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]);
    const vars = Object.fromEntries(names.map((n) => [n, `<${n}>`]));
    expect(qs(key as keyof typeof questionnaireStrings, vars), key).toBe(text.replace(/\{(\w+)\}/g, (_w, n: string) => `<${n}>`));
  }
  expect(Object.keys(WEB_ONLY_STRINGS).filter((k) => k in questionnaireStrings)).toEqual([]);
});

test("@qf:B15 a take cut short reassembles header first and is offered back by P13, with its length", async () => {
  const draft: TakeDraft = {
    takeKey: "t1", sessionKey: "ck-s-1", clientClipKey: "k1", deviceCapturedAt: "2026-10-22T05:00:00Z", mimeType: "audio/webm",
    targetLabel: "Section A", targets: [{ sectionId: "s1" }], roster: ["a1"], startedAt: 1_000_000, finishedAt: null
  };
  const chunks = [
    { takeKey: "t1", index: 1, blob: new Blob(["B"]), at: draft.startedAt + 12 * 60_000 },
    { takeKey: "t1", index: 0, blob: new Blob(["HEAD"]), at: draft.startedAt }
  ];
  expect(await assembleTake(chunks, "audio/webm").text()).toBe("HEADB");
  expect(recoverable([draft, { ...draft, takeKey: "t2", finishedAt: 5 }], chunks).map((r) => r.draft.takeKey)).toEqual(["t1"]);
  expect(recoveredMinutes(draft, chunks)).toBe(12);
  expect(TIMESLICE_MS).toBe(10_000);
  const recorder = readFileSync(join(__dirname, "..", "components", "questionnaire", "Recorder.tsx"), "utf8");
  expect(recorder.indexOf("await recorderStore.saveDraft(draft)")).toBeLessThan(recorder.indexOf("recorder.start(TIMESLICE_MS)"));
});

test("@qf:A16 a cached form is read only by the account that downloaded it", () => {
  const record = { key: "f1::all", schemaVersion: 1, fetchedAt: "2026-10-22T05:00:00Z", includeRetired: true, version: 3, ownerUserId: "u-lead", form: { sections: [] } } as unknown as CachedQuestionnaireForm;
  expect(cacheRecordIsReadable(record, "u-lead")).toBe(true);
  expect(cacheRecordIsReadable(record, "u-other")).toBe(false);
  expect(cacheRecordIsReadable(record, null)).toBe(false);
  expect(cacheRecordIsReadable({ ...record, ownerUserId: null }, "u-lead")).toBe(false);
});

test("@qf:C8 a formatted answer is sent as plain text with the document beside it; prose as text alone", () => {
  const doc = JSON.stringify({ blocks: [{ kind: "BULLET_ITEM", level: 0, align: "LEFT", spans: [{ text: "Indigo", marks: ["BOLD"] }], rows: [] }] });
  const parts = answerParts(doc);
  expect(parts.richText).toBe(doc);
  expect(parts.text).toContain("Indigo");
  expect(parts.text).not.toContain("{");
  expect(answerParts("Wooden blocks")).toEqual({ text: "Wooden blocks", richText: null });
  expect(answerEditorValue({ text: "Indigo", richText: doc })).toBe(doc);
  expect(answerEditorValue({ text: "Wooden blocks", richText: null })).toBe("Wooden blocks");
  expect(answerEditorValue(null)).toBe("");
});

test("@qf:B24 prefill reads the artisan's column as the server does, and never over an answer", () => {
  const artisan = { id: "a1", name: "Giriraj", phone: "9829000000", phoneCountryCode: "+91", craft: { name: "Hand Block Printing" }, dateOfBirth: "1970-05-01T00:00:00Z" };
  expect(prefillText(artisan, "phone")).toBe("+91 98290 00000");
  expect(prefillText({ ...artisan, phone: "" }, "phone")).toBeNull();
  expect(prefillText(artisan, "craft")).toBe("Hand Block Printing");
  expect(prefillText(artisan, "dateOfBirth")).toBe("1970-05-01");
  expect(prefillText(artisan, "sessionDate", { sessionDate: "2026-10-22" })).toBe("2026-10-22");
  expect(prefillText(artisan, "interviewer", { interviewer: "Nandini Rao" })).toBe("Nandini Rao");
  const items = prefillItems({
    sessionId: "s-1",
    questions: [{ lineageId: "l1", artisanField: "craft" }, { lineageId: "l2", artisanField: "phone" }, { lineageId: "l3", artisanField: null }],
    artisans: [artisan],
    answered: new Set(["a1|l2"])
  });
  expect(items).toEqual([
    { sessionId: "s-1", artisanId: "a1", questionLineageId: "l1", text: "Hand Block Printing", origin: "PREFILL", attribution: "INDIVIDUAL", sourceRecordingId: null }
  ]);
});

test("@qf:B39 the fix tray: Send again, Discard and send without the conflicting answers", async () => {
  const store = memoryQueueStore();
  const conflictItem = { artisanId: "a1", questionLineageId: "l1", text: "Mine", origin: "TYPED" as const, attribution: "INDIVIDUAL" as const, sourceRecordingId: null };
  const cleanItem = { ...conflictItem, questionLineageId: "l2", text: "Fine" };
  const unit: SessionUnit = {
    id: "ck-s-1",
    sessionId: "s-1",
    createdAt: "2026-10-22T05:00:00Z",
    steps: [
      { kind: "participant", status: "FAILED", op: "join", artisanId: "a2", at: "2026-10-22T05:00:00Z", idempotencyKey: "p1", error: "Refused" },
      {
        kind: "answers", status: "FAILED", error: "CONFLICT", key: "ans1", items: [conflictItem, cleanItem],
        conflicts: [{ artisanId: "a1", questionLineageId: "l1", status: "CONFLICT", answer: null, current: null }]
      }
    ]
  };
  await store.save(unit);
  const tray = trayItems([unit]);
  expect(tray.map((t) => t.state)).toEqual(["failed", "conflict"]);
  await sendStepAgain(store, unit.id, "participant:p1");
  expect((await store.get(unit.id))!.steps[0]).toMatchObject({ status: "PENDING", error: undefined });
  await sendWithoutConflicts(store, unit.id, "answers:ans1");
  const answers = (await store.get(unit.id))!.steps[1];
  expect(answers).toMatchObject({ status: "PENDING", error: undefined, items: [cleanItem] });
  await discardStep(store, unit.id, "answers:ans1");
  await discardStep(store, unit.id, "participant:p1");
  expect(await store.get(unit.id)).toBeNull();
});

test("@qf:A15 a form, its section and its question made offline land in order, each under the real id", async () => {
  const memory = new Map<string, string>();
  (globalThis as unknown as { localStorage: Storage }).localStorage = {
    getItem: (k: string) => memory.get(k) ?? null,
    setItem: (k: string, v: string) => void memory.set(k, v),
    removeItem: (k: string) => void memory.delete(k),
    clear: () => memory.clear(),
    key: () => null,
    length: 0
  } as Storage;
  resetAuthoringCache();
  const owner = "u-lead";
  const form = queueForm(owner, { title: "Dyers of Bagru" });
  const section = queueSection(owner, form, { title: "Dyes" });
  queueQuestion(owner, form, section, { prompt: "Which dyes?" });
  expect(stepsForForm(owner, form).map((s) => s.kind)).toEqual(["form", "section", "question"]);
  expect(authoringState("u-other").steps).toEqual([]);

  const sent: [string, unknown, string][] = [];
  let refuseQuestion = true;
  const send = async <T,>(path: string, body: unknown, key: string): Promise<T> => {
    sent.push([path, body, key]);
    if (path === "/questionnaires") return { id: "fnew1", sections: [] } as T;
    if (path.endsWith("/sections")) return { id: "fnew1", sections: [{ id: "sec-real", title: "Dyes", code: "B", questions: [] }] } as T;
    if (refuseQuestion) throw new ApiError(422, "That question is too long.", { detail: "That question is too long." });
    return { id: "fnew1", sections: [{ id: "sec-real", title: "Dyes", code: "B", questions: [{ id: "q-real", prompt: "Which dyes?" }] }] } as T;
  };
  expect(await drainAuthoring(owner, send)).toBe(2);
  expect(sent.map((x) => x[0])).toEqual(["/questionnaires", "/questionnaires/fnew1/sections", "/questionnaires/fnew1/sections/sec-real/questions"]);
  expect(new Set(sent.map((x) => x[2])).size).toBe(3);
  const left = authoringState(owner).steps;
  expect(left).toHaveLength(1);
  expect(left[0]).toMatchObject({ kind: "question", status: "FAILED", error: "That question is too long." });
  expect(authoringState(owner).remap).toMatchObject({ [form]: "fnew1", [section]: "sec-real" });
  refuseQuestion = false;
  const { retryAuthoringStep } = await import("@/lib/questionnaireAuthoringOutbox");
  retryAuthoringStep(owner, left[0].id);
  expect(await drainAuthoring(owner, send)).toBe(1);
  expect(authoringState(owner).steps).toEqual([]);
});

test("the handoff bundle's name is the golden table's", () => {
  const golden = JSON.parse(readFileSync(join(ROOT, "shared", "naming-golden.json"), "utf8")) as { fileNames: { input: { what: string; ext: string }; name: string }[] };
  const row = golden.fileNames.find((r) => r.input.what === "Handoff" && r.input.ext === "dpwq");
  expect(row?.name).toBe("DesignWorkshop_Questionnaire_2026-10-22_2nd-Craft-Toolkit_Handoff.dpwq");
  expect(bundleFileName({ form: { title: "2nd Craft Toolkit" } as never, createdAt: "2026-10-22T04:30:00Z" }, ".dpwq")).toBe(row!.name);
});
