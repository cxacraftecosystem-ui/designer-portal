/**
 * The questionnaire audit of 21 Oct 2026 (AUDIT_DR_2026-10-21.md), web half — one regression test per
 * item. Pure rules are called; wiring the page has no renderer for is read from source with the
 * comments stripped, as `questionnaire-edit-unit.spec.ts` does.
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  answersByQuestion,
  interviewDictationFields,
  mayPrefill,
  notesPatch,
  ownedAnswerRefusal,
  ownedAnswerSentence,
  responsesToSend,
  statusPatch
} from "@/lib/interviewEdit";
import { instrumentRecordIsReadable, INSTRUMENT_CACHE_VERSION } from "@/lib/questionnaireInstrumentCache";
import { outboxSendsIdempotencyHeader, replayHeaders } from "@/lib/offline";
import {
  blankRequiredIds,
  lockedQuestionIds,
  mayEditSitting,
  rejectionsByQuestion
} from "@/lib/sittingAnswers";
import { interviewRestoreRefusal, restoredInterviewNotice } from "@/components/questionnaires/DeletedInterviewsCard";

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

/** Comments out, strings kept — the helper `questionnaire-edit-unit.spec.ts` uses. */
function code(source: string): string {
  let out = "";
  let index = 0;
  let stringChar: string | null = null;

  while (index < source.length) {
    const char = source[index];
    const next = source[index + 1];

    if (stringChar) {
      out += char;
      if (char === "\\") {
        out += next ?? "";
        index += 2;
        continue;
      }
      if (char === stringChar) stringChar = null;
      index += 1;
      continue;
    }
    if (char === '"' || char === "'" || char === "`") {
      stringChar = char;
      out += char;
      index += 1;
      continue;
    }
    if (char === "/" && next === "/") {
      while (index < source.length && source[index] !== "\n") index += 1;
      continue;
    }
    if (char === "/" && next === "*") {
      index += 2;
      while (index < source.length && !(source[index] === "*" && source[index + 1] === "/")) index += 1;
      index += 2;
      continue;
    }
    out += char;
    index += 1;
  }
  return out;
}

const PAGE = code(read("app/(protected)/questionnaire/page.tsx"));
const ANSWER = code(read("app/(protected)/questionnaires/[id]/answer/page.tsx"));
const OFFLINE = code(read("lib/offline.ts"));
const DICTATE = code(read("lib/designWorkshops.ts"));

test.describe("C1 — an edit never wipes the interview's notes", () => {
  test("unchanged notes are left out of the edit body", () => {
    expect(notesPatch("Met at the kiln", "Met at the kiln")).toEqual({});
    expect(notesPatch("  Met at the kiln ", "Met at the kiln")).toEqual({});
    expect(notesPatch(null, null)).toEqual({});
  });
  test("changed notes are sent, and an emptied box clears them deliberately", () => {
    expect(notesPatch("New note", "Old note")).toEqual({ notes: "New note" });
    expect(notesPatch("", "Old note")).toEqual({ notes: null });
  });
  test("the notes box is seeded from the record and the edit body goes through notesPatch", () => {
    expect(PAGE).toContain('<MultiNoteField name="notes" label="Interview notes" defaultValue={editingInterview?.notes} />');
    expect(PAGE).toContain('...notesPatch(textValue(form, "notes"), editingInterview?.notes)');
    expect(PAGE).not.toMatch(/commonPayload = \{[^}]*notes:/);
  });
});

test.describe("H1 — an edit keeps the record's location", () => {
  test("LocationFields gets the record's location as `initial` on an edit", () => {
    expect(PAGE).toMatch(/<LocationFields\s*\{\.\.\.\(editingInterview\s*\?\s*\{\s*initial:/);
    expect(PAGE).not.toContain("<LocationFields />");
  });
});

test.describe("H2 — nobody approves an interview by accident", () => {
  test("status is sent only on an edit, only when picked and different", () => {
    expect(statusPatch({ canPick: true, editing: false, picked: "APPROVED", stored: null })).toEqual({});
    expect(statusPatch({ canPick: false, editing: true, picked: "APPROVED", stored: "PENDING" })).toEqual({});
    expect(statusPatch({ canPick: true, editing: true, picked: "PENDING", stored: "PENDING" })).toEqual({});
    expect(statusPatch({ canPick: true, editing: true, picked: "APPROVED", stored: "PENDING" })).toEqual({ status: "APPROVED" });
  });
  test("the Status select is seeded from the record and has no APPROVED default", () => {
    expect(PAGE).not.toContain('defaultValue="APPROVED"');
    expect(PAGE).not.toContain('|| "APPROVED"');
    expect(PAGE).toContain('<Select name="status" defaultValue={editingInterview?.status ?? ""}>');
  });
});

test.describe("H3 — instant transcription asks the interview's own artisans", () => {
  test("the interview id and the selected artisans travel as form fields", () => {
    expect(interviewDictationFields("int-1", ["a1", "a2"])).toEqual({ interviewId: "int-1", artisanIds: "a1,a2" });
    expect(interviewDictationFields(null, [])).toEqual({});
  });
  test("dictateAudio appends them and the interview form passes them", () => {
    expect(DICTATE).toContain("for (const [key, value] of Object.entries(extraFields)) form.append(key, value);");
    expect(PAGE).toContain("interviewDictationFields(liveRef.current.interviewId, liveRef.current.artisanIds)");
  });
});

test.describe("H4 — only changed answers are sent, and the prefill never overwrites", () => {
  const stored = answersByQuestion([
    { questionId: "q1", answerText: "Block printing" },
    { questionId: "q2", answerText: "21/10/2026" }
  ]);
  test("identical and blank answers are not sent", () => {
    expect(responsesToSend({ q1: "Block printing", q2: " 21/10/2026 ", q3: "  " }, stored)).toEqual([]);
  });
  test("a changed or new answer is sent, trimmed", () => {
    expect(responsesToSend({ q1: "Dabu printing ", q4: "Yes" }, stored)).toEqual([
      { questionId: "q1", answerText: "Dabu printing" },
      { questionId: "q4", answerText: "Yes" }
    ]);
  });
  test("a create with no shared entry sends every non-blank answer", () => {
    expect(responsesToSend({ q1: "x", q2: "" }, null)).toEqual([{ questionId: "q1", answerText: "x" }]);
  });
  test("the prefill fills only blank boxes the shared entry has not answered", () => {
    expect(mayPrefill("q2", "", undefined, stored)).toBe(false);
    expect(mayPrefill("q9", "", undefined, stored)).toBe(true);
    expect(mayPrefill("q9", "typed", "prefilled", null)).toBe(false);
    expect(mayPrefill("q9", "prefilled", "prefilled", null)).toBe(true);
  });
  test("the 403 for another person's answers is read and named", () => {
    const refusal = ownedAnswerRefusal({
      detail: { code: "ANSWER_OWNED_BY_OTHER", message: "x", questionIds: ["q1", 3] }
    });
    expect(refusal?.questionIds).toEqual(["q1"]);
    expect(ownedAnswerRefusal({ detail: "Forbidden" })).toBeNull();
    expect(ownedAnswerSentence(["Craft"])).toBe("Someone else answered “Craft”. Only they or an admin can change it.");
  });
  test("the page sends responsesToSend against the record or the shared entry", () => {
    expect(PAGE).toContain("const responses = responsesToSend(answers, answerBaseline);");
    expect(PAGE).toContain("if (!mayPrefill(questionId, existing, previous[questionId], shared)) return;");
    expect(PAGE).toContain("ownedAnswerRefusal(err.payload)");
  });
});

test.describe("H5 — a deleted interview can be restored", () => {
  test("the confirm copy no longer says recordings are deleted permanently", () => {
    expect(PAGE).not.toContain("deleted permanently");
    expect(PAGE).toContain("Its answers and recordings are kept, and an admin can restore it.");
  });
  test("the trash is drawn for content admins in admin view", () => {
    expect(PAGE).toContain("{adminMode && canDeleteRecords ? <DeletedInterviewsCard");
  });
  test("a set another interview now holds is said in the server's words", () => {
    expect(interviewRestoreRefusal({ detail: { code: "INTERVIEW_SET_TAKEN", message: "Held by X." } })).toBe("Held by X.");
    expect(interviewRestoreRefusal({ detail: "nope" })).toBeNull();
    expect(restoredInterviewNotice("Sitting 1")).toBe("“Sitting 1” is restored.");
  });
});

test.describe("H7 — the interview form opens offline", () => {
  const record = {
    key: "u1",
    schemaVersion: INSTRUMENT_CACHE_VERSION,
    fetchedAt: "2026-10-21T00:00:00Z",
    ownerUserId: "u1",
    sections: []
  };
  test("a stored copy is served only to the account that stored it", () => {
    expect(instrumentRecordIsReadable(record, "u1")).toBe(true);
    expect(instrumentRecordIsReadable(record, "u2")).toBe(false);
    expect(instrumentRecordIsReadable(record, null)).toBe(false);
    expect(instrumentRecordIsReadable({ ...record, schemaVersion: INSTRUMENT_CACHE_VERSION + 1 }, "u1")).toBe(false);
  });
  test("the page loads the instrument through the cache", () => {
    expect(PAGE).toContain("await loadInstrumentWithCache(user?.id ?? null)");
    expect(PAGE).not.toContain('apiFetch<QuestionnaireSection[]>("/questionnaire/sections")');
  });
});

test.describe("M5 — locks and per-answer refusals on the answer page", () => {
  const answers = [
    { questionId: "q1", answerText: "Priya's answer", answeredById: "priya" },
    { questionId: "q2", answerText: "Mine", answeredById: "me" },
    { questionId: "q3", answerText: "  ", answeredById: "priya" }
  ];
  test("an answer with text by somebody else is locked unless the reader is a content admin", () => {
    expect([...lockedQuestionIds(answers, "me", false)]).toEqual(["q1"]);
    expect(lockedQuestionIds(answers, "me", true).size).toBe(0);
  });
  test("each refused answer carries its own sentence", () => {
    expect(
      rejectionsByQuestion([
        { questionId: "q1", code: "QUESTION_RETIRED", message: "" },
        { questionId: "q2", code: "ANSWER_OWNED_BY_OTHER", message: "Only Priya can change this." }
      ])
    ).toEqual({
      q1: "This question was retired, so it can no longer be answered.",
      q2: "Only Priya can change this."
    });
  });
  test("the page skips locked answers and keeps refused ones unsaved", () => {
    expect(ANSWER).toContain("dirty.current.has(question.id) && !locked.has(question.id)");
    expect(ANSWER).toContain("if (refused[answer.questionId]) continue;
          dirty.current.delete(answer.questionId);");
    expect(ANSWER).toContain("readOnly={isLocked}");
  });
});

test.describe("M13 — queued creates carry an Idempotency-Key header", () => {
  test("the four questionnaire create routes, POST only", () => {
    for (const endpoint of ["/questionnaire/interviews", "/questionnaires", "/questionnaires/abc/entries", "/questionnaires/abc/reuse"]) {
      expect(outboxSendsIdempotencyHeader({ endpoint, method: "POST" }), endpoint).toBe(true);
    }
    expect(outboxSendsIdempotencyHeader({ endpoint: "/questionnaire/interviews/abc", method: "PATCH" })).toBe(false);
    expect(outboxSendsIdempotencyHeader({ endpoint: "/workshops", method: "POST" })).toBe(false);
  });
  test("the header is sent only when a key exists", () => {
    expect(replayHeaders({ idempotencyKey: "k-123" })).toEqual({ "Idempotency-Key": "k-123" });
    expect(replayHeaders({ idempotencyKey: null })).toEqual({});
  });
  test("the key is minted once and sent by the first attempt and every replay", () => {
    expect(OFFLINE).toContain("headers: replayHeaders(progress)");
    expect(OFFLINE).toContain("headers: replayHeaders({ idempotencyKey })");
    expect(OFFLINE).toContain("idempotencyKey: outboxSendsIdempotencyHeader(entry) ? (entry.idempotencyKey ?? mintClientKey()) : null");
  });
});

test.describe("L7 — required questions are enforced", () => {
  test("a blank required active question is named; others are not", () => {
    const questions = [
      { id: "a", isActive: true, isRequired: true },
      { id: "b", isActive: true, isRequired: false },
      { id: "c", isActive: false, isRequired: true },
      { id: "d", isActive: true, isRequired: true }
    ];
    expect(blankRequiredIds(questions, { a: " ", d: "answered" })).toEqual(["a"]);
  });
  test("Save, Next and Finish ask for a complete section", () => {
    expect(ANSWER).toContain("saveSection(section, { requireComplete: true })");
    expect(ANSWER).toContain("requireComplete: nextIndex > sectionIndex");
    expect(ANSWER).toContain("saveSection(section, { silent: true, requireComplete: true })");
  });
});

test.describe("L8 — a sitting's details can be corrected on the web", () => {
  test("the same people the server allows", () => {
    const base = { viewerId: "me", entryCreatedById: "x", formOwnerId: "y", isContentAdmin: false };
    expect(mayEditSitting(base)).toBe(false);
    expect(mayEditSitting({ ...base, entryCreatedById: "me" })).toBe(true);
    expect(mayEditSitting({ ...base, formOwnerId: "me" })).toBe(true);
    expect(mayEditSitting({ ...base, isContentAdmin: true })).toBe(true);
  });
  test("patchEntry has a caller", () => {
    expect(ANSWER).toContain("await patchEntry(id, entry.id, {");
  });
});
