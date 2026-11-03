import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

const DIALOG = ["components", "questionnaires", "ReuseDialog.tsx"];
const REPORT = ["components", "questionnaires", "UploadReport.tsx"];
const LIST = ["app", "(protected)", "questionnaires", "page.tsx"];
const DETAIL = ["app", "(protected)", "questionnaires", "[id]", "page.tsx"];
const LIB = ["lib", "questionnaireForms.ts"];

function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

function code(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .map((line) => line.replace(/(^|[^:])\/\/.*$/, "$1"))
    .join("\n");
}

test("the dialog is handed the page's scoped workshop list and fetches none of its own", () => {
  const dialog = code(read(...DIALOG));

  expect(dialog, "the dialog must not fetch workshops itself").not.toContain("listDesignWorkshops");
  expect(dialog, "and it must not reach for the unscoped questionnaire options either").not.toContain(
    "listQuestionnaireOptions"
  );
  expect(dialog).toContain("workshops: ReuseTarget[]");

  for (const [label, page] of [
    ["the list page", read(...LIST)],
    ["the detail page", read(...DETAIL)]
  ] as const) {
    const mount = between(page, "<ReuseDialog", "/>");
    expect(mount, `${label} must pass its own workshop list into the dialog`).toContain(
      "workshops={workshops.map("
    );
  }
});

test("an unpicked workshop is omitted from the body, never sent as an empty string", () => {
  const body = between(code(read(...DIALOG)), "await reuseQuestionnaire(questionnaireId, {", "});");

  expect(body, "an unattached copy is the ordinary case and must not send a blank id").toContain(
    "...(designWorkshopId ? { designWorkshopId } : {})"
  );
  expect(body).toContain("...(title.trim() ? { title: title.trim() } : {})");

  expect(body).not.toContain("sourceWorkshopId");
  expect(code(read(...DIALOG))).toContain('useState("")');
});

test("the report panel names the reuse for what it is and never as an import", () => {
  const report = code(read(...REPORT));

  expect(report, "the third provenance action needs its own arm").toContain(
    'provenance.action === "reused"'
  );
  const importedArm = between(report, 'if (provenance.action === "answersImported")', "}");
  expect(importedArm).toContain("The answers in this workbook were recorded under your name");
  expect(report).toContain('title: "The answers in this workbook were not recorded against your copy"');
  expect(report).toContain("border-amber-500/30 bg-amber-100");

  expect(report, "a reuse has no spreadsheet behind it").toContain('"What the upload did"');
  expect(report, "a reuse is a copy, not an upload").toContain("What was copied");

  expect(read(...LIB)).toContain('action: "answersImported" | "answersNotImported" | "reused";');
});

test("the reuse control is offered to every designer, as the server offers it", () => {
  const list = read(...LIST);
  const detail = read(...DETAIL);

  const rowActions = between(list, "<RowActions>", "</RowActions>");
  expect(rowActions, "the row action is missing").toContain("Reuse at another workshop");
  expect(rowActions, "and it must not be conditioned on anything").not.toContain("mayEdit");
  expect(rowActions).toContain("setReuseRow(row)");

  const header = between(detail, "<PageHeader", "/>\n\n      {error ?");
  const ungated = between(header, "Download question set", "{mayEdit ?");
  expect(ungated, "the reuse button belongs with the ungated controls").toContain(
    "Reuse at another workshop"
  );

  const detailsPanel = between(detail, "onSubmit={renameQuestionnaire}", "</form>");
  expect(detailsPanel).not.toContain("Reuse at another workshop");
});

test("the reuse report names the copy rather than the page it is drawn on", () => {
  const report = code(read(...REPORT));

  expect(report, "the panel needs the copy's title to talk about it").toContain("subject?: string | null;");
  expect(report).toContain("is a copy, and it carries no recorded answers");
  expect(report, "the subject must reach the heading").toContain("skinFor(provenance, subject)");
  expect(report).toContain('copied ? "questions copied" : "questions added"');
  expect(report).toContain('copied ? "sections copied" : "sections added"');

  const detail = read(...DETAIL);
  expect(between(detail, "{report ? <UploadReport", "/>"), "the detail page must name the copy").toContain(
    "subject={reused?.title ?? null}"
  );
  const list = read(...LIST);
  expect(between(list, "{report ? <UploadReport", "/>"), "the list page must name the copy").toContain(
    "subject={reportSubject}"
  );
  expect(code(detail), "an upload must drop the reuse subject").toContain("setReused(null)");
  expect(code(list), "an upload must drop the reuse subject").toContain("setReportSubject(null)");
});

test("the alternative route is offered whatever the length of the workshop list", () => {
  const dialog = code(read(...DIALOG));
  const block = between(dialog, 'FieldBlock label="Design workshop for the copy"', "</FieldBlock>");

  expect(block).toContain("workshops.length ?");
  const withList = between(block, "workshops.length ?", ") : (");
  expect(withList, "a full dropdown needs the sentence too — the list is one page").toContain(
    "attach the copy from its own page"
  );
  expect(withList, "and it must say why the list is not the whole truth").toContain("shows the newest workshops only");
});

test("the copy's previewed name agrees with the name the server will give it", () => {
  const dialog = code(read(...DIALOG));

  expect(dialog, "the client must count up as reuse_title does").toContain(
    "candidate = `${base} (${REUSED} ${n})`"
  );
  expect(dialog).toContain("reusedTitle(sourceTitle, atTarget ?? [])");

  const collides = between(dialog, "const collides =", "const numbered");
  expect(collides, "an empty box cannot collide — the server numbers it").toContain("Boolean(typed) &&");

  expect(dialog, "a deactivated form still owns its title on the server").toContain("activeOnly: false");

  const look = between(dialog, "const look = designWorkshopId", ".then((result)");
  expect(look, "the unattached case must be looked up too").toContain("mineOnly: true");
  expect(dialog, "and filtered to the rows that are attached to nothing").toContain(
    "rows.filter((row) => row.designWorkshopId === null)"
  );
  expect(dialog).not.toContain("if (!open || !designWorkshopId)");
});

test("the client's reuse suffix is the server's own literal", () => {
  const service = readFileSync(
    join(__dirname, "..", "..", "backend", "app", "services", "questionnaire_forms.py"),
    "utf8"
  );
  const server = /REUSE_TITLE_SUFFIX = "([^"]+)"/.exec(service);
  expect(server, "REUSE_TITLE_SUFFIX has moved or been renamed — the client mirrors it").not.toBeNull();

  const client = /const REUSED = "([^"]+)"/.exec(read(...DIALOG));
  expect(client, "REUSED has moved or been renamed").not.toBeNull();

  expect(client?.[1], "the placeholder would preview a name the row will not get").toBe(server?.[1]);

  expect(service).toContain('f"{base} ({REUSE_TITLE_SUFFIX})"');
  expect(service).toContain('f"{base} ({REUSE_TITLE_SUFFIX} {n})"');
});
