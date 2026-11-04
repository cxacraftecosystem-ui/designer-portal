import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { photographWasNotStored, type DwIdentityOcrResult } from "@/lib/designWorkshops";
import {
  DW_IDENTITY_RETENTION_PATH,
  DW_PHOTO_RETENTION_DEFAULT,
  retentionOutcomeSentence,
  type DwPhotoRetentionResult
} from "@/lib/identityPhotoRetention";

const BACKEND = join(__dirname, "..", "..", "backend");
const IDENTITY_OCR_PY = readFileSync(join(BACKEND, "app", "services", "identity_ocr.py"), "utf8");
const DESIGN_WORKSHOP_ROUTES_PY = readFileSync(
  join(BACKEND, "app", "api", "routes", "design_workshops.py"),
  "utf8"
);

test("the safe default is the same word on both sides of the wire", () => {
  expect(IDENTITY_OCR_PY).toContain('DISCARD = "DISCARD"');
  expect(IDENTITY_OCR_PY).toContain('STORE = "STORE"');
  expect(DW_PHOTO_RETENTION_DEFAULT).toBe("DISCARD");
});

test("the route that deletes the photograph is the route the server declares", () => {
  expect(DESIGN_WORKSHOP_ROUTES_PY).toContain('@router.post("/ocr/identity/retention")');
  expect(DESIGN_WORKSHOP_ROUTES_PY).toContain('router = APIRouter(prefix="/design-workshops"');
  expect(DW_IDENTITY_RETENTION_PATH).toBe("/design-workshops/ocr/identity/retention");
});

test("the read route says in its own payload that it kept nothing", () => {
  expect(DESIGN_WORKSHOP_ROUTES_PY).toContain('"stored": False,');
});

test("only an explicit false means the photograph was not stored", () => {
  expect(photographWasNotStored({ photograph: { stored: false } })).toBe(true);

  expect(photographWasNotStored({} as DwIdentityOcrResult)).toBe(false);
  expect(photographWasNotStored({ photograph: null })).toBe(false);
  expect(photographWasNotStored({ photograph: {} })).toBe(false);
  expect(photographWasNotStored({ photograph: { stored: null } })).toBe(false);
  expect(photographWasNotStored({ photograph: { stored: true } })).toBe(false);
});

test("a deletion is described as a deletion, not as a removal from a list", () => {
  const sentence = retentionOutcomeSentence({ deleted: true, decision: "DISCARD" });
  expect(sentence).toContain("deleted");
  expect(sentence).toContain("the file and the record of it are both gone");
  expect(sentence).toContain("number you confirmed is unaffected");
});

test("a kept photograph is described as kept, in the name of whoever kept it", () => {
  const kept: DwPhotoRetentionResult = {
    deleted: false,
    decision: "STORE",
    retention: { decision: "STORE", decidedById: "usr_1", decidedByName: "Asha Menon", decidedAt: "2026-08-15T11:30:00+00:00" }
  };
  const sentence = retentionOutcomeSentence(kept);
  expect(sentence).toContain("Asha Menon");
  expect(sentence).toContain("kept on this record");
  expect(sentence).toContain("unmasked");
});

test("a kept photograph with no name attached still says it was kept", () => {
  const sentence = retentionOutcomeSentence({ deleted: false, retention: { decidedByName: "   " } });
  expect(sentence).toContain("kept on this record");
  expect(sentence).toContain("unmasked");

  expect(retentionOutcomeSentence({ deleted: false })).toContain("kept on this record");
  expect(retentionOutcomeSentence({})).not.toContain("deleted");
});
