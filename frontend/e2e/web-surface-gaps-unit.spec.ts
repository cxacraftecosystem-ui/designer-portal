import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { DICTATION_ALLOWANCE_TTL_MS, dwDictationAllowance, forgetDictationAllowanceInFlight, rememberDictationAllowance } from "@/lib/designWorkshops";

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const STAGE_PAGE = "app/(protected)/design-workshops/[id]/stages/[stageKey]/page.tsx";
const REPORT_PAGE = "app/(protected)/design-workshops/[id]/report/page.tsx";

test("stage 17 mounts a cost panel the way stage 9 mounts the market one", () => {
  const page = read(STAGE_PAGE);
  expect(page).toContain("COSTING_STAGE, CostFindingsPanel");
  expect(page).toContain("{stageKey === COSTING_STAGE ? (");
  expect(page).toMatch(/<CostFindingsPanel\s+workshopId=\{id\}\s+collections=\{collections\}/);
});

test("the panel names the stage the server names", () => {
  expect(read("components/designworkshop/CostFindingsPanel.tsx")).toContain(
    'export const COSTING_STAGE = "COSTING_MARKET_LINKAGE";'
  );
});

test("the endpoint finally has a caller", () => {
  const lib = read("lib/designWorkshops.ts");
  expect(lib).toContain("export function dwCostIntegrity(id: string)");
  expect(lib).toContain("`/design-workshops/${id}/cost-integrity`");
  expect(read("components/designworkshop/CostFindingsPanel.tsx")).toContain("dwCostIntegrity(workshopId)");
});

test("the panel writes nothing back into stage 17", () => {
  const panel = read("components/designworkshop/CostFindingsPanel.tsx");
  expect(panel).not.toContain("onChange");
  expect(panel).not.toContain("onPatch");
  expect(panel).not.toContain("saveStage");
  expect(panel).toContain("workshopId: string;");
  expect(panel).toContain("collections: Record<string, DwRow[]>;");
  expect(panel).toContain("onUse?: (sheetEntryId: string, key: string, value: string) => void;");
  expect(panel).toContain("onClick={() => onUse(sheet.entryId, check.key,");
});

test("an unsynced sheet suppresses the deleted-sheet caution but never a warning", () => {
  const panel = read("components/designworkshop/CostFindingsPanel.tsx");
  expect(panel).toContain("{findings.warnings.length ? (");
  expect(panel).toContain("{!unsynced && findings.cautions.length ? (");
});

function interceptFetch(answer: () => Promise<Response>) {
  const calls: string[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = ((input: RequestInfo | URL) => {
    calls.push(String(input));
    return answer();
  }) as typeof fetch;
  return { calls, restore: () => { globalThis.fetch = original; } };
}

const jsonResponse = (body: unknown) =>
  new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });

const ALLOWANCE = { dictationsLimit: 20, dictationsUsed: 3, dictationsRemaining: 17, dictationDay: "2026-08-19" };

test("eleven microphones on one stage ask the server ONCE between them", async () => {
  forgetDictationAllowanceInFlight();
  const net = interceptFetch(() => Promise.resolve(jsonResponse(ALLOWANCE)));
  try {
    const answers = await Promise.all(Array.from({ length: 11 }, () => dwDictationAllowance()));
    expect(net.calls.length, "eleven callers, one round trip").toBe(1);
    expect(net.calls[0]).toContain("/design-workshops/dictation-allowance");
    for (const answer of answers) expect(answer).toEqual(ALLOWANCE);
  } finally {
    net.restore();
    forgetDictationAllowanceInFlight();
  }
});

test("a later stage within ten minutes reuses the count, and asks again after", async () => {
  forgetDictationAllowanceInFlight();
  const net = interceptFetch(() => Promise.resolve(jsonResponse(ALLOWANCE)));
  try {
    const start = 1_000_000;
    await dwDictationAllowance(start);
    await dwDictationAllowance(start + DICTATION_ALLOWANCE_TTL_MS - 1);
    expect(net.calls.length, "two mounts inside ten minutes, one request").toBe(1);
    await dwDictationAllowance(start + DICTATION_ALLOWANCE_TTL_MS);
    expect(net.calls.length, "ten minutes later, a fresh count").toBe(2);
  } finally {
    net.restore();
    forgetDictationAllowanceInFlight();
  }
});

test("a dictation updates the held count, so no stage asks the server for it", async () => {
  forgetDictationAllowanceInFlight();
  const net = interceptFetch(() => Promise.resolve(jsonResponse(ALLOWANCE)));
  try {
    rememberDictationAllowance({ ...ALLOWANCE, dictationsUsed: 4, dictationsRemaining: 16 });
    const answer = await dwDictationAllowance();
    expect(answer?.dictationsRemaining).toBe(16);
    expect(net.calls.length).toBe(0);
    expect(read("components/designworkshop/Dictation.tsx")).toContain("rememberDictationAllowance(counted)");
  } finally {
    net.restore();
    forgetDictationAllowanceInFlight();
  }
});

test("a courtesy pre-flight that fails resolves null and never throws", async () => {
  forgetDictationAllowanceInFlight();
  const offline = interceptFetch(() => Promise.reject(new TypeError("Failed to fetch")));
  try {
    await expect(dwDictationAllowance()).resolves.toBeNull();
  } finally {
    offline.restore();
  }
  const notDeployed = interceptFetch(() =>
    Promise.resolve(new Response(JSON.stringify({ detail: "Not Found" }), {
      status: 404,
      headers: { "content-type": "application/json" }
    }))
  );
  try {
    await expect(dwDictationAllowance()).resolves.toBeNull();
    expect(notDeployed.calls.length, "the failure is not cached as an answer").toBe(1);
  } finally {
    notDeployed.restore();
    forgetDictationAllowanceInFlight();
  }
});

test("the pre-flight is gated on the rung the cap governs, and nothing else is", () => {
  const dictation = read("components/designworkshop/Dictation.tsx");
  expect(dictation).toContain('if (mode !== "server") return;');
  expect(dictation).toContain("void dwDictationAllowance().then");
  expect(dictation).toContain('mode === "server" && allowance');
});

test("the readout prints a remaining count and the day it resets, and prints nothing when uncapped", () => {
  const dictation = read("components/designworkshop/Dictation.tsx");
  expect(dictation).toContain('mode === "server" && allowance && allowance.dictationsRemaining !== null');
  expect(dictation).toContain("allowance.dictationDay");
  expect(dictation).toContain('if (typeof result.dictationDay === "string")');
});

test("the delete route has a client and is offered only where the server would allow it", () => {
  const panel = read("components/CollabPanel.tsx");
  expect(panel).toContain('apiFetch(`/data-access/comments/${comment.id}`, { method: "DELETE" })');
  expect(panel).toContain("user && canDeleteRecords ?");
  expect(panel).toContain('method: "PATCH"');
  expect(panel).toContain("deleteConfirm(");
  expect(panel).toContain("await load();");
});

test("the preview links every stage, not only the unfinished ones", () => {
  const page = read(REPORT_PAGE);
  const completeness = page.slice(page.indexOf("Required fields across all 22 stages"));
  expect(completeness).toContain("Open a stage to correct something the preview shows");
  expect(completeness).toContain("{(registry?.stages ?? []).map((stage) => (");
  expect(completeness).toContain("href={`/design-workshops/${id}/stages/${stage.key}`}");
});

test("the way into a stage is a link, and the preview says what it is showing", () => {
  const page = read(REPORT_PAGE);
  const completeness = page.slice(page.indexOf("Required fields across all 22 stages"));
  expect(completeness).toContain("not the artisan or product records they were");
  expect(completeness).toContain("href={`/design-workshops/${id}/stages/${stage.key}`}");
});

test("STANDING TRIPWIRE: nothing on the report screen is directly editable", () => {
  expect(read("components/designworkshop/report/ReportSheet.tsx")).not.toContain("contentEditable");
  const page = read(REPORT_PAGE);
  expect(page).not.toContain("contentEditable");
  const mount = page.slice(page.indexOf("<ReportSheets"), page.indexOf("<ReportSheets") + 400);
  expect(mount).not.toMatch(/on[A-Z]/);
});

const FIELD_INPUT = "components/designworkshop/FieldInput.tsx";

test("the record form mounts the tracing panel, not only the Sketches workspace", () => {
  const form = read(FIELD_INPUT);
  expect(form).toContain('import("@/components/sketches/upload/SketchTraceField").then((m) => m.SketchTraceField)');
  expect(form).toContain("<SketchTraceField targetLabel={field.label} disabled={disabled} onAttach={attach} />");
});

test("both derived-drawing panels are gated by ONE predicate, as they are on the handset", () => {
  const form = read(FIELD_INPUT);
  expect(form).toContain("const offersDerivedDrawing = offersSketchRectify(entity, field);");
  expect(form).toContain("const sketchSources: SketchSource[] = offersDerivedDrawing");
  expect(form).not.toContain("offersSketchTrace");
});

test("the tracing panel is NOT gated on there being a sibling photograph, and rectify still is", () => {
  const form = read(FIELD_INPUT);
  const rectify = form.indexOf("<SketchRectifyField");
  const trace = form.indexOf("<SketchTraceField targetLabel");
  expect(rectify).toBeGreaterThan(-1);
  expect(trace).toBeGreaterThan(rectify);
  expect(form.slice(0, rectify)).toContain("{sketchSources.length ? (");
  expect(form.slice(rectify, trace)).toContain("{offersDerivedDrawing ? (");
});

test("the record form does NOT hand the panel the photograph as well", () => {
  const form = read(FIELD_INPUT);
  const start = form.indexOf("<SketchTraceField");
  const mount = form.slice(start, form.indexOf("/>", start) + 2);
  expect(mount).not.toContain("onAttachSource");
  expect(mount).toContain("onAttach={attach}");
  expect(read("components/sketches/upload/UploadTabPanel.tsx")).toContain("onAttachSource");
});
