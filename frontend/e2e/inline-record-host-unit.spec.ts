import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { inlineSeed, refusedByUnsavedWork, scopeNoticeLines } from "@/components/designworkshop/StageReferenceField";
import { seedHasArtisan } from "@/components/forms/inlineRecordHost";
import { MEASUREMENT_GRID_PURPOSE } from "@/components/media/GridMeasurement";
import type { DwEntity, DwField, DwReferencePayload } from "@/lib/designWorkshops";

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const ARTISAN_FORM = "components/forms/ArtisanForm.tsx";
const PRODUCT_FORM = "components/forms/ProductForm.tsx";
const TOOL_FORM = "components/forms/ToolForm.tsx";
const PROCESS_FORM = "components/forms/ProcessForm.tsx";
const DIALOG = "components/designworkshop/InlineRecordDialog.tsx";
const PICKER = "components/designworkshop/StageReferenceField.tsx";

function field(overrides: Partial<DwField> & { key: string }): DwField {
  return { label: overrides.key, type: "REF", tier: "BASIC", required: false, ...overrides } as DwField;
}

const EXISTING_PRODUCT: DwEntity = {
  key: "existingProduct",
  name: "DwExistingProduct",
  cardinality: "COLLECTION",
  title: "Existing products",
  description: "",
  parent: "",
  labelField: "name",
  fields: [
    field({ key: "artisanRef", refModel: "Artisan", refScope: "WORKSHOP" }),
    field({ key: "productRef", refModel: "ProductDocumentation", refScope: "WORKSHOP", refFilterBy: "artisanRef" }),
    field({ key: "artisanName", type: "TEXT" })
  ]
};

const PROTOTYPE: DwEntity = {
  key: "prototype",
  name: "DwPrototype",
  cardinality: "COLLECTION",
  title: "Prototypes",
  description: "",
  parent: "",
  labelField: "name",
  fields: [
    field({ key: "artisanRef", refModel: "DwParticipant", refScope: "ALL" }),
    field({ key: "productRef", refModel: "ProductDocumentation", refScope: "WORKSHOP", refFilterBy: "artisanRef" }),
    field({ key: "productName", type: "TEXT" })
  ]
};

const productRefOf = (entity: DwEntity) => entity.fields.find((f) => f.key === "productRef") as DwField;

test("a product created from stage 6's cascade is seeded with the artisan the row names", () => {
  const seed = inlineSeed({
    entity: EXISTING_PRODUCT,
    field: productRefOf(EXISTING_PRODUCT),
    row: { artisanRef: "artisan_kamla", artisanName: "Kamla Devi" },
    filterValue: "artisan_kamla",
    linkedWorkshopId: null
  });
  expect(seed.artisanId).toBe("artisan_kamla");
  expect(seed.artisanName).toBe("Kamla Devi");
});

test("a product created from stage 13's roster cascade is NOT seeded with the participant id", () => {
  const seed = inlineSeed({
    entity: PROTOTYPE,
    field: productRefOf(PROTOTYPE),
    row: { artisanRef: "dwentry_participant_7" },
    filterValue: "dwentry_participant_7",
    linkedWorkshopId: null
  });
  expect(seed.artisanId).toBeUndefined();
  expect(seed.artisanName).toBeUndefined();
});

test("an uncascaded picker seeds no artisan at all", () => {
  const seed = inlineSeed({ field: field({ key: "artisanRef", refModel: "Artisan" }), linkedWorkshopId: "ws_1" });
  expect(seed.artisanId).toBeUndefined();
  expect(seed.workshopId).toBe("ws_1");
});

test("nothing is guessed when the design workshop has no linked workshop", () => {
  const seed = inlineSeed({
    entity: EXISTING_PRODUCT,
    field: productRefOf(EXISTING_PRODUCT),
    row: { artisanRef: "artisan_kamla", artisanName: "Kamla Devi" },
    filterValue: "artisan_kamla",
    linkedWorkshopId: null
  });
  expect(seed.workshopId).toBeUndefined();
  expect(Object.keys(seed).sort()).toEqual(["artisanId", "artisanName"]);
});

test("a cascade with nothing chosen above it seeds no artisan", () => {
  const seed = inlineSeed({
    entity: EXISTING_PRODUCT,
    field: productRefOf(EXISTING_PRODUCT),
    row: {},
    filterValue: "",
    linkedWorkshopId: "ws_1"
  });
  expect(seed.artisanId).toBeUndefined();
});

test("seedHasArtisan is what decides whether the carry banner may still claim the artisan", () => {
  expect(seedHasArtisan(undefined)).toBe(false);
  expect(seedHasArtisan({ workshopId: "ws_1" })).toBe(false);
  expect(seedHasArtisan({ artisanId: "a1" })).toBe(true);
  expect(seedHasArtisan({ artisanName: "Kamla Devi" })).toBe(true);
});

test("the seed is create-only, and the DIALOG is what enforces it", () => {
  const dialog = read(DIALOG);

  const mount = dialog.slice(
    dialog.indexOf("export function InlineRecordForm"),
    dialog.indexOf("export function InlineRecordDialog")
  );
  expect(mount.length, "InlineRecordForm should be declared above InlineRecordDialog").toBeGreaterThan(500);

  expect(mount, "one gate, computed once from the edit flag it already has").toContain(
    "const seedForForm = editing ? undefined : seed;"
  );
  expect(mount.match(/seed=\{seedForForm\}/g)?.length, "Artisan, Product and Tool").toBe(3);
  expect(mount).not.toMatch(/^\s+seed=\{seed\}$/m);
  for (const form of ["<ArtisanForm", "<ProductForm", "<ToolForm", "<ProcessForm"]) {
    expect(mount, `${form} must be mounted by the shared host`).toContain(form);
  }

  const picker = read(PICKER);
  expect(picker).toContain('seed={inlineDialog.mode === "create" ? seed : undefined}');
});

function payload(overrides: Partial<DwReferencePayload> = {}): DwReferencePayload {
  return {
    model: "ProductDocumentation",
    scope: "WORKSHOP",
    scopedToWorkshop: true,
    filtered: false,
    truncated: false,
    options: [{ id: "p1", label: "Sambalpuri saree", sublabel: "" }],
    ...overrides
  } as DwReferencePayload;
}

const WORKSHOP_SCOPED = field({ key: "productRef", refModel: "ProductDocumentation", refScope: "WORKSHOP" });

test("a LINKED workshop with nothing under it says the list is narrowed, not that the repository is empty", () => {
  const lines = scopeNoticeLines(WORKSHOP_SCOPED, payload({ options: [] }));
  expect(lines.length).toBe(1);
  expect(lines[0]).toContain("linked workshop yet");
  expect(lines[0]).toContain("Create one here");
});

test("a list that came back with records says nothing at all", () => {
  expect(scopeNoticeLines(WORKSHOP_SCOPED, payload())).toEqual([]);
});

test("the cascade's own empty list is left to the cascade to explain", () => {
  expect(scopeNoticeLines(WORKSHOP_SCOPED, payload({ options: [], filtered: true }))).toEqual([]);
});

test("an UNLINKED workshop still gets the sentence it always got, and only that one", () => {
  const lines = scopeNoticeLines(WORKSHOP_SCOPED, payload({ scopedToWorkshop: false, options: [] }));
  expect(lines.length).toBe(1);
  expect(lines[0]).toContain("Not linked to a workshop yet");
});

test("an ALL-scoped picker is never told anything about workshops", () => {
  const all = field({ key: "artisanRef", refModel: "DwParticipant", refScope: "ALL" });
  expect(scopeNoticeLines(all, payload({ scope: "ALL", options: [] }))).toEqual([]);
});

test("the truncation sentence still comes through, and can share the line", () => {
  const lines = scopeNoticeLines(WORKSHOP_SCOPED, payload({ scopedToWorkshop: false, truncated: true }));
  expect(lines.length).toBe(2);
  expect(lines[1]).toContain("First 50 matches");
});

test("the stage page puts the linked workshop within the pickers' reach", () => {
  const page = read("app/(protected)/design-workshops/[id]/stages/[stageKey]/page.tsx");
  expect(page).toContain("LinkedWorkshopProvider");
  expect(page).toContain("setLinkedWorkshopId(draft.header.workshopId ?? null)");
});

for (const [name, path] of [
  ["ArtisanForm", ARTISAN_FORM],
  ["ProductForm", PRODUCT_FORM],
  ["ToolForm", TOOL_FORM]
] as const) {
  test(`${name}'s Cancel asks the host to leave rather than popping history`, () => {
    const source = read(path);
    expect(source).toContain("function leave() {\n    if (onCancel) onCancel();\n    else router.back();\n  }");
    expect(source).toContain("onClick={handleBack}");
  });

  test(`${name} tells its own Cancel apart from the page's back arrow before "Discard" answers`, () => {
    const source = read(path);
    expect(source).toContain("    if (dirty) {\n      setPromptFromCancel(true);\n      setBackPromptOpen(true);\n    } else leave();");
    expect(source).toContain("useLeaveGuard(dirty, () => setBackPromptOpen(true));");
    expect(source.match(/setPromptFromCancel\(false\);/g)?.length, "keep editing, discard, save").toBe(3);
  });

  test(`${name}'s "Discard" completes the exit that asked, and neither one navigates`, () => {
    const source = read(path);
    const discard = source.slice(source.indexOf("onDiscard={() => {"), source.indexOf("onSave={() => {"));
    expect(discard, "the form's own Cancel: empty the form, stay put").toContain("if (promptFromCancel) leave();");
    expect(discard, "the host's back arrow: finish what it started").toContain("else leaveAfterDiscard();");
    expect(discard).not.toContain("router.back();");
    expect(source.match(/router\.back\(\);/g)?.length).toBe(1);
  });

  test(`${name} falls back to the ordinary exit when no host supplies the new one`, () => {
    const source = read(path);
    expect(source).toContain(
      "function leaveAfterDiscard() {\n    if (onDiscardAndLeave) onDiscardAndLeave();\n    else leave();\n  }"
    );
  });
}

test("the second exit is ONE contract member, declared once and consumed by all four forms", () => {
  const contract = read("components/forms/inlineRecordHost.ts");
  expect(contract).toContain("onDiscardAndLeave?: () => void;");
  expect(contract).toContain(
    'export type InlineRecordSurfaceProps = Pick<InlineRecordHostProps<unknown>, "footerFields" | "onDiscardAndLeave">;'
  );

  const process = read(PROCESS_FORM);
  expect(process).toContain(
    "function leaveAfterDiscard() {\n    if (onDiscardAndLeave) onDiscardAndLeave();\n    else onCancel();\n  }"
  );
  expect(process).toContain("if (promptFromCancel) onCancel();");
  expect(process).toContain("else leaveAfterDiscard();");
  expect(process).toContain("useLeaveGuard(dirty, () => setGuardOpen(true));");
  expect(process.match(/setPromptFromCancel\(false\);/g)?.length, "keep editing, discard and save").toBe(3);

  for (const path of [ARTISAN_FORM, PRODUCT_FORM, TOOL_FORM, PROCESS_FORM]) {
    const source = read(path);
    expect(source, `${path} must destructure the shared callback`).toContain("\n  onDiscardAndLeave,\n");
    expect(source, `${path} must not re-declare it`).not.toContain("onDiscardAndLeave?: () => void;");
    expect(source, `${path} must intersect the shared surface type`).toContain("} & InlineRecordSurfaceProps)");
  }
});

test("the stage embed answers its two exits with two different callbacks", () => {
  const embed = read("components/designworkshop/StageRecordEmbed.tsx");
  expect(embed, "StageRecordEmbed must pass onDiscardAndLeave= to InlineRecordForm").toContain(
    "onDiscardAndLeave={handleDiscardAndLeave}"
  );
  expect(embed).not.toContain("onDiscardAndLeave={handleCancel}");
  const cancelText = embed.slice(embed.indexOf("const handleCancel"), embed.indexOf("const handleDiscardAndLeave"));
  const leaveText = embed.slice(embed.indexOf("const handleDiscardAndLeave"), embed.indexOf("const [asked,"));
  const sentence = (slice: string, what: string) => {
    const found = /text: `([^`]+)`/.exec(slice);
    if (!found) throw new Error(`${what} writes no notice of its own`);
    return found[1];
  };
  expect(sentence(cancelText, "the form's own Cancel"), "the two exits must not read alike").not.toBe(
    sentence(leaveText, "the blocked exit")
  );
  expect(cancelText, "the form's own Cancel says nothing about leaving").not.toContain("press the same control again");

  const dialog = read("components/designworkshop/InlineRecordDialog.tsx");
  expect(dialog.match(/onDiscardAndLeave=\{onDiscardAndLeave\}/g)?.length, "one per mounted form").toBe(4);
  const mount = dialog.slice(dialog.indexOf("<InlineRecordForm"));
  expect(mount, "the dialog's own mount takes the fallback").not.toContain("onDiscardAndLeave=");
});

test("the refused exit is carried, and every control that is refused hands one over", () => {
  const guard = read("components/UnsavedChangesGuard.tsx");

  expect(guard, "LeaveInterceptor must carry the pending act").not.toContain(
    "export type LeaveInterceptor = () => boolean;"
  );
  expect(guard).toContain("export type LeaveInterceptor = (pending: PendingLeave) => boolean;");
  expect(guard).toContain("intercept: (pending: PendingLeave) => boolean;");
  expect(guard, "the hook a control calls takes the act too").toContain(
    "export function useLeaveInterceptor(): (pending: PendingLeave) => boolean {"
  );

  expect(guard).toContain("const held = useRef<{ act: PendingLeave; blockedBy: LeaveInterceptor } | null>(null);");
  expect(guard, "an answer from anyone but the blocker is ignored").toContain(
    "if (!pending || pending.blockedBy !== answeredBy) return;"
  );
  expect(guard, "an exit held for a form that no longer exists can never be answered").toContain(
    "if (held.current?.blockedBy === next) held.current = null;"
  );
  expect(guard).toContain("if (entry === skip) continue;");
  expect(guard).toContain("if (!ask(pending.act, answeredBy)) pending.act();");

  const back = read("components/BackButton.tsx");
  expect(back, "the arrow's two destinations are one act, chosen where they are known").toContain(
    "const go = href ? () => router.push(href) : () => router.back();"
  );
  expect(back).toContain("if (interceptLeave(go)) return;");

  const stage = read("app/(protected)/design-workshops/[id]/stages/[stageKey]/page.tsx");
  expect(stage).toContain("if (interceptLeave(() => void go())) return;");
  expect(stage, "the banked act performs the navigation whatever the flush did").toMatch(
    /const go = async \(\) => \{\s*try \{\s*await flushLocal\(\);\s*\} catch \{[\s\S]*?\}\s*action\(\);\s*\};/
  );

  const entity = read("components/designworkshop/EntityForm.tsx");
  expect(entity).toContain("const open = () => setOpenKey((current) => (current === rowKey ? null : rowKey));");
  expect(entity).toContain("if (closing !== null && interceptLeave(open)) return;");

  const picker = read("components/designworkshop/StageReferenceField.tsx");
  expect(picker, "the picker consults the same guard as the back arrow").toContain(
    'import { useLeaveInterceptor } from "@/components/UnsavedChangesGuard";'
  );
  expect(picker, "a pick is asked about only when the pointer would really move").toContain(
    "if (option.id !== selectedId && interceptLeave(() => commitChoice(option))) {"
  );
  expect(picker, "clearing the link remounts the form too").toContain("if (interceptLeave(unlink)) {");
  expect(picker).toContain("if (decision.commit && !chooseNow(decision.commit)) {");
  for (const retry of ["choose the record again", "scan the card again", "press “Clear the link” again"]) {
    expect(picker, `the ${retry} refusal is worded by the shared helper`).toContain(
      `refusedByUnsavedWork("${retry}")`
    );
    const line = refusedByUnsavedWork(retry);
    expect(line.tone, "a refusal is not a confirmation").toBe("warn");
    expect(line.text).toBe(
      `Unsaved work on this stage. Answer the prompt, then ${retry}.`
    );
  }
  expect(picker, "no notice may promise an exit no form performs yet").not.toContain(
    "the scanned record is chosen"
  );
  expect(picker, "the picker cannot know which form raised the prompt").not.toContain(
    "the record form below this picker has unsaved work"
  );
});

test.fixme("the record forms answer the prompt with the act the guard is holding", () => {
  const guard = read("components/UnsavedChangesGuard.tsx");
  const embed = read("components/designworkshop/StageRecordEmbed.tsx");
  expect(guard).toContain("return { completeLeave, abandonLeave };");
  for (const path of [ARTISAN_FORM, PRODUCT_FORM, TOOL_FORM, PROCESS_FORM]) {
    const source = read(path);
    expect(source, `${path} must finish the exit in the branch that means "leave"`).toMatch(
      /else \{\s*(completeLeave\(\);\s*leaveAfterDiscard\(\);|leaveAfterDiscard\(\);\s*completeLeave\(\);)\s*\}/
    );
    const discard = source.slice(source.indexOf("onDiscard={() => {"));
    const beforeTheBranch = discard.slice(0, discard.indexOf("if (promptFromCancel)"));
    expect(beforeTheBranch, `${path} must not complete the exit on its own Cancel`).not.toContain("completeLeave()");
    const keepEditing = source.slice(source.indexOf("onKeepEditing={() => {"));
    expect(keepEditing.slice(0, keepEditing.indexOf("}}")), `${path} must forget the act on "Keep editing"`).toContain(
      "abandonLeave()"
    );
  }
  expect(embed).not.toContain("press the same control again");
  expect(read(PICKER), "the picker no longer tells a designer to press its control again").not.toContain(
    "Answer the prompt on screen, then"
  );
});

test("no record form titles a screen its host has already titled", () => {
  for (const path of [ARTISAN_FORM, PRODUCT_FORM, TOOL_FORM, PROCESS_FORM]) {
    expect(read(path), `${path} has grown a heading its host already draws`).not.toContain("<h2 ");
  }
  const source = read(PROCESS_FORM);
  expect(source).not.toContain("const hosted = Boolean(onCreated);");
  expect(source).toContain("How a product is made, step by step.");
});

test("the one capture card that outlives its own mount names a staging owner that does too", () => {
  const source = read(PROCESS_FORM);
  expect(source).toContain("stagingOwnerId={`${formId}:pre-process`}");
  expect(source.match(/stagingOwnerId=/g)?.length, "the pre-process card and nothing else").toBe(1);
  for (const path of [ARTISAN_FORM, PRODUCT_FORM, TOOL_FORM]) {
    expect(read(path), `${path} must not pin a staging owner without hoisting its file list`).not.toContain(
      "stagingOwnerId="
    );
  }
});

const EMBED = "components/designworkshop/StageRecordEmbed.tsx";
const ENTITY_FORM = "components/designworkshop/EntityForm.tsx";
const MEDIA_LIB = "lib/media.ts";

test("premise 1: attaching a file is unsaved work, so a collapsing row asks before it unmounts", () => {
  for (const path of [ARTISAN_FORM, PRODUCT_FORM, TOOL_FORM]) {
    const source = read(path);
    const handlers = source.split("onFilesChange={(files) => {").slice(1);
    expect(handlers.length, `${path} draws at least one capture card`).toBeGreaterThan(0);
    handlers.forEach((handler, index) => {
      expect(handler.slice(0, 200), `${path} capture card ${index + 1} must mark the form dirty`).toContain(
        "markDirty();"
      );
    });
  }

  const process = read(PROCESS_FORM);
  expect(process, "ProcessForm counts pre-process files into its dirty signature").toContain("pre: preFiles.length");
  expect(process, "ProcessForm counts each step's files into its dirty signature").toContain("files: step.files.length");

  expect(read(ENTITY_FORM), "a collapse consults the forms mounted inside the row").toContain(
    "if (closing !== null && interceptLeave(open)) return;"
  );
});

test("premise 2: a save leaves the attached files in place, so hoisting them would link them twice", () => {
  for (const [path, writes] of [
    [ARTISAN_FORM, ["setMediaFiles("]],
    [PRODUCT_FORM, ["setMediaFiles(", "setGridFiles("]],
    [TOOL_FORM, ["setMediaFiles(", "setStageFiles(", "setGridFiles("]],
    [PROCESS_FORM, ["setPreFiles(", "setSteps(", "files: []"]]
  ] as const) {
    const source = read(path);
    const start = source.indexOf("if (outcome.queued) {");
    const savePath = source.slice(start, source.indexOf("} catch (err) {", start));
    expect(start, `${path} has a queued branch to read`).toBeGreaterThan(-1);
    expect(savePath, `${path}'s slice must reach the online branch as well`).toContain(
      "const saved = outcome.saved;"
    );
    expect(savePath.length, `${path} has a save path to read`).toBeGreaterThan(200);
    for (const write of writes) {
      expect(savePath, `${path} must not clear its file list on the save path`).not.toContain(write);
    }
  }

  const embed = read(EMBED);
  expect(embed, "the form is keyed on the generation as well as the linked record").toContain(":${formGeneration}`}");
  expect(embed).toContain("const remountForm = useCallback(() => setFormGeneration((generation) => generation + 1), []);");
  expect(embed.match(/remountForm\(\);/g)?.length, "the edit path twice, and the two discards").toBe(4);
});

test("premise 3: the eagerly-uploaded objects are claimed before any remount can release them", () => {
  const media = read(MEDIA_LIB);

  function bodyOf(signatureClose: string, from: number): string {
    const open = media.indexOf(signatureClose, from);
    expect(open, `lib/media.ts still declares ${signatureClose}`).toBeGreaterThan(-1);
    const close = media.indexOf("\n}\n", open);
    expect(close, `${signatureClose}'s body still ends at a brace in column 0`).toBeGreaterThan(open);
    return media
      .slice(open, close)
      .replace(/\/\*[\s\S]*?\*\//g, (run) => run.replace(/[^\n]/g, " "))
      .replace(/\/\/[^\n]*/g, (run) => " ".repeat(run.length));
  }

  const batchAt = media.indexOf("export async function uploadMediaBatch({");
  const body = bodyOf("}): Promise<BatchResult> {", batchAt);
  const claim = body.indexOf("const records = takeStagedFor(files);");
  const firstAwait = body.search(/\bawait\b/);
  expect(claim, "uploadMediaBatch claims the staged objects").toBeGreaterThan(-1);
  expect(firstAwait, "uploadMediaBatch does await something").toBeGreaterThan(-1);
  expect(claim, "the claim must come before the first await").toBeLessThan(firstAwait);

  const singleAt = media.indexOf("export async function uploadMediaFile({");
  const singleBody = bodyOf("}) {", singleAt);
  const firstStatement = singleBody.split("\n").find((line) => line.trim().length > 0 && !line.startsWith("}") );
  expect(firstStatement?.trim(), "uploadMediaFile claims the staged object first, above everything").toBe(
    "const [record] = takeStagedFor([file]);"
  );
  expect(singleBody.search(/\bawait\b/), "uploadMediaFile still has no await of its own to race").toBe(-1);
});

test("the contract no longer describes a handoff none of the four forms implements", () => {
  const contract = read("components/forms/inlineRecordHost.ts");
  expect(contract).not.toContain("call this from a button beside it, once the message has been read");

  for (const [path, callback] of [
    [ARTISAN_FORM, "if (onCreated) onCreated(saved);"],
    [PRODUCT_FORM, "if (onCreated) onCreated(saved);"],
    [TOOL_FORM, "if (onCreated) onCreated(saved);"]
  ] as const) {
    expect(read(path), `${path} reports the record from the partial-failure branch`).toContain(callback);
  }
});

for (const [name, path] of [
  ["ArtisanForm", ARTISAN_FORM],
  ["ProductForm", PRODUCT_FORM],
  ["ToolForm", TOOL_FORM],
  ["ProcessForm", PROCESS_FORM]
] as const) {
  test(`${name} reports a queued save to its host instead of returning silently`, () => {
    const source = read(path);
    expect(source).toContain("onQueued");
    const queued = source.slice(source.indexOf("outcome.queued"));
    expect(queued.slice(0, 2000)).toMatch(/if \(onQueued\)/);
  });
}

test("the picker says a queued record was saved, is unlinked, and what to do next", () => {
  const source = read(PICKER);
  const notice = source.slice(source.indexOf("const QUEUED_OFFLINE_NOTICE"), source.indexOf("Single select"));
  expect(notice).toContain("Saved on this device");
  expect(notice).toContain("Once it uploads");
  expect(notice).toContain("choose it");
  expect(source.match(/QUEUED_OFFLINE_NOTICE/g)?.length).toBeGreaterThanOrEqual(3);
});

test("no placeholder id is ever written into the REF field", () => {
  const source = read(PICKER);
  const handler = source.slice(source.indexOf("onQueued={() => {"));
  expect(handler.slice(0, 200)).toContain("supersede();");
  expect(handler.slice(0, 200)).not.toContain("onChange(");
});

test("the duplicate prompt hands the existing artisan back when there is a host to hand it to", () => {
  const source = read(ARTISAN_FORM);
  const handler = source.slice(source.indexOf("onOpenExisting={() => {"), source.indexOf("onDiscard={discardEntry}"));
  expect(handler).toContain("if (onUseExisting) {\n            onUseExisting(conflict.existingArtisan);\n            return;\n          }");
  expect(handler).toContain("router.push(`/artisans/${conflict.existingArtisan.id}/edit`)");
});

test("nothing from the conflict payload reaches the row", () => {
  const source = read(PICKER);
  const handler = source.slice(source.indexOf("onUseExisting={(artisan) => {"));
  expect(handler.slice(0, 300)).toContain("{ id: artisan.id, name: artisan.name }");
  expect(handler.slice(0, 300)).not.toContain("maskedValue");
  expect(handler.slice(0, 300)).not.toContain("conflict");
});

test("every form gets a way out that is not a navigation, for all four models", () => {
  const source = read(DIALOG);
  expect(source.match(/onCancel=\{onCancel\}/g)?.length, "one per form").toBe(4);
  expect(source.match(/onDone=\{onCancel\}/g)?.length, "ProcessForm's second way out").toBe(1);
  expect(source.match(/onQueued=\{onQueued\}/g)?.length, "one per form").toBe(4);
  expect(source.match(/onCreated=\{onCreated\}/g)?.length, "one per form").toBe(4);
  expect(source.match(/seed=\{seedForForm\}/g)?.length).toBe(3);
  expect(source).toContain("onUseExisting={onUseExisting}");
  expect(source.match(/footerFields=\{footerFields\}/g)?.length, "one per form").toBe(4);
});

test("the DIALOG still turns the host callbacks into ones that close it", () => {
  const source = read(DIALOG);
  expect(source).toContain("onCreated={finish}");
  expect(source).toContain("onCancel={onClose}");
  expect(source).toContain("onQueued={reportQueued}");
  expect(source).toContain("onUseExisting={onUseExisting ? adoptExisting : undefined}");
  for (const closing of ["onCreated(record);\n      onClose();", "onQueued?.();\n    onClose();"]) {
    expect(source, "a host callback that no longer closes the dialog").toContain(closing);
  }
  expect(source).not.toContain("footerFields={<");
});

test("the DIALOG's own two exits ask before they discard the form inside it", () => {
  const source = read(DIALOG);
  expect(source, "the dialog asks the guard the same way every other guarded control does").toContain(
    'import { useLeaveInterceptor } from "@/components/UnsavedChangesGuard";'
  );
  expect(source, "the close is refused when a form has taken responsibility for it").toMatch(
    /const closeUnlessAsked = useCallback\(\(\) => \{\s*if \(interceptLeave\(onClose\)\) return;\s*onClose\(\);\s*\}/
  );
  expect(source, "FieldDialog's own two exits go through the guarded close").toContain("onClose={closeUnlessAsked}");
  expect(source).toContain("onCancel={onClose}");
  expect(source).toContain("onCreated={finish}");
  expect(source).toContain("onQueued={reportQueued}");
  expect(source, "a post-write callback must not be routed through the guard").not.toContain(
    "onCancel={closeUnlessAsked}"
  );
  expect(source).toContain("dismissOnBackdrop={false}");

  expect(read("components/dialogs/FieldDialog.tsx"), "FieldDialog stays unopinionated about guards").not.toContain(
    "useLeaveInterceptor"
  );
});

test("the grid marker is the exact string the server and the handset agree on", () => {
  expect(MEASUREMENT_GRID_PURPOSE).toBe("MEASUREMENT_GRID");
});

for (const [name, path] of [
  ["ProductForm", PRODUCT_FORM],
  ["ToolForm", TOOL_FORM]
] as const) {
  test(`${name} marks its grid photographs on both the online and the queued path, and marks nothing else`, () => {
    const source = read(path);
    const marker = /extraMetadata: \{ purpose: MEASUREMENT_GRID_PURPOSE \}/;

    const queuedGrid = source.slice(
      source.indexOf("...(Object.entries(gridFiles) as [GridGroup, File][]).map("),
      source.indexOf("files: mediaFiles,")
    );
    expect(queuedGrid, "the queued grid entry carries the marker").toMatch(marker);

    const onlineGrid = source.slice(
      source.indexOf("for (const [group, file] of Object.entries(gridFiles)"),
      source.indexOf("if (mediaFiles.length) {")
    );
    expect(onlineGrid, "the online grid upload carries the marker").toMatch(marker);

    const elsewhere = source.split(queuedGrid).join("").split(onlineGrid).join("");
    expect(elsewhere, "no other upload on this form is marked as a measurement grid").not.toMatch(marker);
  });
}

test("the unsaved-changes guard holds a stack, and a form only ever disarms itself", () => {
  const guard = read("components/UnsavedChangesGuard.tsx");
  expect(guard).toContain("register: (interceptor: LeaveInterceptor) => () => void;");
  expect(guard).toContain("const interceptors = useRef<LeaveInterceptor[]>([]);");
  expect(guard).toContain("for (let index = stack.length - 1; index >= 0; index -= 1) {");
  expect(guard, "the walk records the blocker and stops at it").toMatch(
    /if \(entry\(pending\)\) \{\s*held\.current = \{ act: pending, blockedBy: entry \};\s*return true;\s*\}/
  );
  expect(guard).not.toContain("const top = interceptors.current[interceptors.current.length - 1];");
  expect(guard).toContain("interceptors.current.filter((entry) => entry !== next)");
  expect(guard).toContain("unregister();");
  expect(guard).not.toContain("interceptors.current = [];");
  expect(guard).not.toContain("only one form is ever on screen at a time so a single slot is enough");
});

test("Craft is still not inline-creatable", () => {
  const dialog = read(DIALOG);
  expect(dialog).toContain('export const INLINE_CREATABLE = ["Artisan", "ProductDocumentation", "ToolDocumentation", "Process"] as const;');
});

test("the craft picker offers the crafts page in a new tab, and only to somebody who can act on it", () => {
  const source = read(PICKER);
  const branch = source.slice(source.indexOf('{field.refModel === "Craft" && !disabled ? ('));
  const head = branch.slice(0, 2400);
  expect(head).toContain("`/crafts?new=${encodeURIComponent(query.trim())}`");
  expect(head).toContain('target="_blank"');
  expect(head).toContain('rel="noopener"');
  expect(head).toContain("{CRAFT_REGISTER_LINK}");
  expect(source).toContain('const CRAFT_REGISTER_LINK = "Add it to the list (new tab)";');

  expect(head).toContain("craftManager ? (");
  expect(source).toContain('import { canManageCrafts } from "@/lib/permissions";');
  expect(source).toContain("const craftManager = canManageCrafts(user);");

  expect(head).toContain("{CRAFT_REGISTER_BLOCKED}");
  expect(source).toContain('const CRAFT_REGISTER_BLOCKED = "Not on the list? Ask an admin to add it.";');
});
