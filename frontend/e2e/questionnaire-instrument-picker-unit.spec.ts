import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8");

function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
}

const PICKER = read("components/questionnaires/InstrumentPicker.tsx");
const PICKER_CODE = stripComments(PICKER);
const ANSWER = read("app/(protected)/questionnaires/[id]/answer/page.tsx");
const ANSWER_CODE = stripComments(ANSWER);

test.describe("the instrument picker", () => {
  test("offers the shared artisan instrument under a value that is not an id", () => {
    expect(PICKER_CODE).toContain('export const SHARED_INSTRUMENT = "shared"');
    expect(PICKER_CODE).toContain("export const SHARED_INSTRUMENT_LABEL");
  });

  test("renders nothing at all for an account that may not list questionnaires", () => {
    expect(PICKER_CODE).toContain("if (!allowed) return null;");
  });

  test("walks pages to exhaustion and says so when it stops early", () => {
    expect(PICKER_CODE).toContain("const PAGE_SIZE = 100");
    expect(PICKER_CODE).toMatch(/page <= pages && page <= MAX_PAGES/);
    expect(PICKER_CODE).toContain("setTruncated(pages > MAX_PAGES)");
    expect(PICKER_CODE).toContain("{truncated ?");
  });

  test("a failed list is not a list of none", () => {
    expect(PICKER_CODE).toMatch(/catch \(err\)/);
    expect(PICKER_CODE).toContain('role="alert"');
    expect(PICKER_CODE).toContain("only the shared artisan questionnaire is listed");
  });

  test("it does not steal focus, because it switches screens rather than filling a field", () => {
    expect(PICKER_CODE).toContain("advanceOnSelect={false}");
  });

  test("the options are searchable, because they are fetched records", () => {
    expect(PICKER_CODE).toMatch(/^\s*searchable$/m);
  });

  test("a published standard form says whose it is", () => {
    expect(PICKER_CODE).toContain("Standard form");
    expect(PICKER_CODE).toContain("questionnaireKindLabel(form.kind)");
  });

  test("the shared instrument is drawn apart from the designer's own forms", () => {
    expect(PICKER_CODE).toContain('group: "Your questionnaires"');
    const sharedRow = /value: SHARED_INSTRUMENT,[\s\S]{0,400}?\},/.exec(PICKER_CODE);
    expect(sharedRow).not.toBeNull();
    expect(sharedRow![0], "the shared instrument was filed under the designer's own forms").not.toContain("group:");
  });
});


test.describe("the answer screen's picker", () => {
  test("switching forms is its own kind of exit", () => {
    expect(ANSWER_CODE).toContain('| { kind: "instrument"; target: string }');
  });

  test("it goes through `requestExit` and never straight to the router", () => {
    expect(ANSWER_CODE).toContain('requestExit({ kind: "instrument", target: next })');
    const picker = /<InstrumentPicker[\s\S]*?\/>/.exec(ANSWER_CODE);
    expect(picker).not.toBeNull();
    expect(picker![0], "the picker navigates without asking about unsaved answers").not.toContain("router.");
  });

  test("the shared instrument goes to the singular route, which is where it lives", () => {
    expect(ANSWER_CODE).toContain(
      'router.push(exit.target === SHARED_INSTRUMENT ? "/questionnaire" : `/questionnaires/${exit.target}/answer`)'
    );
    expect(ANSWER_CODE).not.toContain("/questionnaires/shared");
  });

  test("picking the form you are already in does nothing", () => {
    expect(ANSWER_CODE).toContain("if (next === id) return;");
  });

  test("the sitting dropdown still asks too — this did not replace that fix", () => {
    expect(ANSWER_CODE).toContain('requestExit({ kind: "sitting", entryId: next })');
  });
});
