import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  DW_LOCAL_DRAFT_LINK_PROMPT,
  DW_LOCAL_DRAFT_UNLINKED,
  DW_LOCAL_START_ACTION,
  DW_LOCAL_START_NOTE,
  DwCreateNotPermittedError,
  classifyDraftStart,
  createLocalDraft,
  createMustBeDeclined,
  setDraftSessionUser
} from "@/lib/designWorkshopStore";

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const KOTLIN = "../android/app/src/main/java/com/designprototype/workshop/data/DwWorkshopCreation.kt";

test("an account that may create is answered CREATE, connection or not", () => {
  expect(classifyDraftStart({ mayCreate: true, mayRunWorkshops: true, reachable: true })).toBe("create");
  expect(classifyDraftStart({ mayCreate: true, mayRunWorkshops: true, reachable: false })).toBe("create");
});

test("a designer WITH a connection is refused — the create rule is untouched online", () => {
  expect(classifyDraftStart({ mayCreate: false, mayRunWorkshops: true, reachable: true })).toBe("refused");
});

test("a designer with NO connection is answered LINK-LATER — the owner's clause", () => {
  expect(classifyDraftStart({ mayCreate: false, mayRunWorkshops: true, reachable: false })).toBe("link-later");
});

test("an account that may not run a workshop at all is refused, reachable or not", () => {
  expect(classifyDraftStart({ mayCreate: false, mayRunWorkshops: false, reachable: false })).toBe("refused");
  expect(classifyDraftStart({ mayCreate: false, mayRunWorkshops: false, reachable: true })).toBe("refused");
});

test("createLocalDraft still refuses a designer on a device that can reach the repository", async () => {
  setDraftSessionUser("designer-1", "DESIGNER");
  await expect(createLocalDraft({ title: "Ikat, Barpali" })).rejects.toThrow(DwCreateNotPermittedError);
});

test("with the caller's own unreachable evidence, the same designer gets PAST the gate", async () => {
  setDraftSessionUser("designer-1", "DESIGNER");
  const thrown = await createLocalDraft({ title: "Ikat, Barpali" }, { serverUnreachable: true }).catch(
    (err: unknown) => err
  );
  expect(thrown).toBeInstanceOf(Error);
  expect(thrown).not.toBeInstanceOf(DwCreateNotPermittedError);
});

test("`serverUnreachable` cannot let an account outside the workshop set through", async () => {
  setDraftSessionUser("researcher-1", "RESEARCHER");
  await expect(
    createLocalDraft({ title: "Ikat, Barpali" }, { serverUnreachable: true })
  ).rejects.toThrow(DwCreateNotPermittedError);
});

test("a signed-out browser is refused even with no connection", async () => {
  setDraftSessionUser(null, null);
  await expect(
    createLocalDraft({ title: "Ikat, Barpali" }, { serverUnreachable: true })
  ).rejects.toThrow(DwCreateNotPermittedError);
});

test("a designer's draft is never posted, whoever minted it", () => {
  expect(createMustBeDeclined({ alreadyOnServer: false, sessionMayCreate: false })).toBe(true);
  expect(createMustBeDeclined({ alreadyOnServer: true, sessionMayCreate: false })).toBe(false);
});

test("the four link-later strings are byte-for-byte the handset's", () => {
  const kotlin = read(KOTLIN);
  for (const line of [
    DW_LOCAL_START_ACTION,
    DW_LOCAL_START_NOTE,
    DW_LOCAL_DRAFT_UNLINKED,
    DW_LOCAL_DRAFT_LINK_PROMPT
  ]) {
    expect(kotlin, `the handset must carry: ${line}`).toContain(line);
  }
});

test("the note says it is not a workshop yet, which is the fact a fortnight rests on", () => {
  expect(DW_LOCAL_START_NOTE).toContain("Not a workshop yet");
  expect(DW_LOCAL_DRAFT_UNLINKED).toContain("Not a workshop yet");
  expect(DW_LOCAL_DRAFT_LINK_PROMPT).toContain("workshops");
});

test("the handset's tri-state is the same three lines as the browser's", () => {
  const kotlin = read(KOTLIN);
  expect(kotlin).toContain("fun dwClassifyDraftStart(");
  expect(kotlin).toContain("mayCreate -> DwDraftStart.CREATE");
  expect(kotlin).toContain("mayRunWorkshops && !reachable -> DwDraftStart.LINK_LATER");
  expect(kotlin).toContain("else -> DwDraftStart.REFUSED");
});
