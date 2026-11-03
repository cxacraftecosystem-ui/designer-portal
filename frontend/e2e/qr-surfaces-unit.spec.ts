import { readFileSync, readdirSync } from "node:fs";
import { join, relative, resolve, sep } from "node:path";

import { expect, test } from "@playwright/test";

import {
  pickerTakesScans,
  scanCommitDecision,
  scanLookupOutcome,
  scanTypeRefusal,
  type ScanHeld
} from "@/components/designworkshop/StageReferenceField";
import type { DwField, DwReferenceOption, DwReferencePayload } from "@/lib/designWorkshops";
import { WORKSHOP_RECORD_TYPES, type WorkshopCodeRef } from "@/lib/workshopCodes";

const ROOT = resolve(__dirname, "..");

const SHARED_CONTROL = "components/designworkshop/WorkshopCodeScanner.tsx";

const IMAGE_DECODER = "lib/qrImageDecode.ts";

const PURE_DECODER = "lib/qrDecode.ts";

const PAYLOAD_GRAMMAR = "lib/workshopCodes.ts";

const RECORD_CARD = "components/RecordCode.tsx";

const PRINT_SHEET = "components/designworkshop/WorkshopCodeSheet.tsx";

const NO_CARD: readonly string[] = ["prototype"];

function sourceFiles(): string[] {
  const found: string[] = [];
  const walk = (directory: string) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      if (entry.name === "node_modules" || entry.name.startsWith(".")) continue;
      const path = join(directory, entry.name);
      if (entry.isDirectory()) walk(path);
      else if (/\.tsx?$/.test(entry.name)) found.push(relative(ROOT, path).split(sep).join("/"));
    }
  };
  for (const top of ["app", "components", "lib"]) walk(join(ROOT, top));
  return found.sort();
}

const files = sourceFiles();
const sourceOf = new Map(files.map((path) => [path, readFileSync(join(ROOT, path), "utf8")]));

function codeOf(path: string): string {
  const source = sourceOf.get(path) as string;
  let out = "";
  let quote: string | null = null;
  let index = 0;

  while (index < source.length) {
    const character = source[index];
    const next = source[index + 1];

    if (quote) {
      if (character === "\\") {
        out += character + (next ?? "");
        index += 2;
        continue;
      }
      if (character === quote || (character === "\n" && quote !== "`")) quote = null;
      out += character;
      index++;
      continue;
    }

    if (character === '"' || character === "'" || character === "`") {
      quote = character;
      out += character;
      index++;
      continue;
    }

    if (character === "/" && next === "*") {
      const end = source.indexOf("*/", index + 2);
      index = end < 0 ? source.length : end + 2;
      out += " ";
      continue;
    }

    if (character === "/" && next === "/") {
      const end = source.indexOf("\n", index);
      index = end < 0 ? source.length : end;
      continue;
    }

    out += character;
    index++;
  }

  return out;
}

function filesUsing(pattern: RegExp): string[] {
  return files.filter((path) => pattern.test(codeOf(path)));
}

function filesCalling(name: string): string[] {
  return filesUsing(new RegExp(`(?<!function\\s)\\b${name}\\s*\\(`));
}

test("exactly one component turns a scanned or typed code into a record reference", () => {
  expect(filesCalling("decodeWorkshopCode").sort()).toEqual(["lib/scanCodecs.ts", "lib/scanInput.ts"]);
  expect(filesCalling("classifyScanInput")).toEqual([SHARED_CONTROL]);
  expect(sourceOf.get(PAYLOAD_GRAMMAR)).toContain("export function decodeWorkshopCode");
});

test("the decoding layers stay stacked: one surface, one image decoder, one pure decoder", () => {
  expect(filesUsing(/\bBarcodeDetector\b/)).toEqual([IMAGE_DECODER]);

  expect(filesCalling("decodeQrFromFile")).toEqual([SHARED_CONTROL]);
  expect(filesCalling("decodeQrFromVideoFrame")).toEqual([SHARED_CONTROL]);
  expect(filesCalling("decodeQrFromGrey")).toEqual([IMAGE_DECODER]);
  expect(sourceOf.get(PURE_DECODER)).toContain("export function decodeQrFromGrey");
});

test("the shared control offers the camera AND an uploaded picture AND the keyboard, none of them gated", () => {
  const source = codeOf(SHARED_CONTROL);

  expect(source).toContain("getUserMedia");
  expect(source).toContain('data-testid="workshop-code-scan"');
  expect(source).toContain('data-testid="workshop-code-upload"');
  expect(source).toContain('type="file"');
  expect(source).toContain('accept="image/*"');
  expect(source).toContain("onDrop");
  expect(source).toContain('"paste"');
  expect(source).toContain('id="workshop-code-manual"');

  expect(source).not.toMatch(/cameraOffered|support\s*===\s*"(yes|no)"/);

  expect(source).toContain("handleRawValue");
});

test("every surface that reads a code mounts the shared control, and there are no others", () => {
  const mounts = filesUsing(/<WorkshopCodeScanner\b/);
  expect(mounts.sort()).toEqual(
    [
      "app/(protected)/design-workshops/[id]/codes/page.tsx",
      "components/scan/ScanSheet.tsx",
      "components/designworkshop/StageReferenceField.tsx"
    ].sort()
  );

  expect(sourceOf.get("app/(protected)/search/page.tsx")).toContain("<RecordCodeScanPanel");
  expect(sourceOf.get("components/RecordCodeScanPanel.tsx")).toContain("<ScanPanel");
  expect(sourceOf.get("app/(protected)/scan/page.tsx")).toContain("<ScanPanel");
  expect(sourceOf.get("components/scan/TopBarScanButton.tsx")).toContain("<ScanSheet");

  for (const path of filesUsing(/getUserMedia/)) {
    if (path === SHARED_CONTROL) continue;
    expect(
      /decodeWorkshopCode|qrImageDecode|BarcodeDetector/.test(codeOf(path)),
      `${path} opens a camera and reads codes without going through ${SHARED_CONTROL}`
    ).toBe(false);
  }
});

function recordCardMounts(): Map<string, string[]> {
  const found = new Map<string, string[]>();
  for (const path of filesUsing(/<RecordCodeCard\b/)) {
    const types = [...codeOf(path).matchAll(/<RecordCodeCard\b[^>]*?recordType="([a-zA-Z]+)"/g)].map(
      (match) => match[1]
    );
    found.set(path, [...new Set(types)].sort());
  }
  return found;
}

test("every record type that has a screen shows its code there, and every mount names the type", () => {
  const mounts = recordCardMounts();
  const files = [...mounts.keys()];
  const types = [...new Set([...mounts.values()].flat())].sort();

  expect(files.sort()).toEqual(
    [
      "app/(protected)/artisans/[id]/edit/page.tsx",
      "app/(protected)/crafts/page.tsx",
      "app/(protected)/design-workshops/page.tsx",
      "app/(protected)/media/page.tsx",
      "app/(protected)/processes/page.tsx",
      "app/(protected)/products/[id]/edit/page.tsx",
      "app/(protected)/questionnaire/sessions/[id]/view/page.tsx",
      "app/(protected)/tools/[id]/edit/page.tsx",
      "app/(protected)/workshops/page.tsx"
    ].sort()
  );

  expect(types).toEqual(WORKSHOP_RECORD_TYPES.filter((type) => !NO_CARD.includes(type)).slice().sort());

  expect([...mounts].filter(([, named]) => named.length === 0).map(([path]) => path)).toEqual([]);

  expect(sourceOf.get(RECORD_CARD)).toContain("export function RecordCodeCard");
});

test("an artisan's code is the QR card, on its page, its edit page and straight after a create", () => {
  expect(filesUsing(/<ArtisanCardPanel\b/).sort()).toEqual(
    [
      "app/(protected)/artisans/[id]/edit/page.tsx",
      "app/(protected)/artisans/[id]/page.tsx",
      "components/forms/ArtisanForm.tsx"
    ].sort()
  );
  expect(filesUsing(/<RecordCodeCard\b[^>]*recordType="artisan"/)).toEqual(["app/(protected)/artisans/[id]/edit/page.tsx"]);
  expect(codeOf("app/(protected)/artisans/[id]/edit/page.tsx")).toMatch(
    /cardsStatus === false \? \(\s*<RecordCodeCard recordType="artisan"/
  );
});

test("a CXA1 card reaches the server's resolve route through the shared control", () => {
  const control = sourceOf.get(SHARED_CONTROL) as string;
  expect(control).toContain("classifyScanInput(raw)");
  expect(control).toMatch(/scan\.kind === "card"[\s\S]*resolveCard\(scan\.raw\)/);
  expect(control).toContain("resolveCard = resolveCardForScan");
  expect(sourceOf.get("lib/artisanCardApi.ts")).toContain('"/artisan-cards/resolve"');
});

test("the screen and the paper draw one record as ONE symbol", () => {
  for (const path of [RECORD_CARD, PRINT_SHEET]) {
    expect(codeOf(path), `${path} no longer declares its QR error-correction level as Q`).toContain(
      'const ECC_LEVEL = "Q" as const;'
    );
  }

  expect(filesUsing(/<WorkshopCodeSheet\b/).sort()).toEqual(
    ["app/(protected)/design-workshops/[id]/codes/page.tsx", PRINT_SHEET].sort()
  );
  expect(codeOf(RECORD_CARD)).toContain("<WorkshopCodePrintout");
});

test("the pure decoder stays free of the DOM, so it can be tested and ported without a browser", () => {
  const code = codeOf(PURE_DECODER);
  const globals: [string, RegExp][] = [
    ["document", /(^|[^.\w$])document\s*[.[]/],
    ["window", /(^|[^.\w$])window\s*[.[]/],
    ["createImageBitmap", /(^|[^.\w$])createImageBitmap\s*\(/],
    ["a canvas", /(^|[^.\w$])(OffscreenCanvas|HTMLCanvasElement|CanvasRenderingContext2D)\b/],
    ["fetch", /(^|[^.\w$])fetch\s*\(/]
  ];
  for (const [name, pattern] of globals) {
    expect(pattern.test(code), `${PURE_DECODER} has grown a dependency on ${name}`).toBe(false);
  }
});

function refField(overrides: Partial<DwField> & { key: string; label: string }): DwField {
  return { type: "REF", tier: "BASIC", required: false, ...overrides } as DwField;
}

const PRODUCT_BOX = refField({
  key: "productRef",
  label: "Product",
  refModel: "ProductDocumentation",
  refScope: "WORKSHOP",
  refFilterBy: "artisanRef"
});

const ARTISAN_BOX = refField({ key: "artisanRef", label: "Artisan", refModel: "Artisan", refScope: "WORKSHOP" });

const SKETCH_BOX = refField({ key: "sketchRef", label: "Sketch", refModel: "DwSketch", refScope: "WORKSHOP" });

function answer(overrides: Partial<DwReferencePayload>): DwReferencePayload {
  return {
    model: "ProductDocumentation",
    scope: "WORKSHOP",
    scopedToWorkshop: true,
    filtered: false,
    truncated: false,
    outOfScope: false,
    options: [],
    ...overrides
  };
}

const option = (id: string, label: string): DwReferenceOption => ({ id, label, sublabel: "", data: { name: label } });

const ARTISAN_CODE: WorkshopCodeRef = { recordType: "artisan", id: "cmsik2jg8000eh8xc1lcy661a" };
const PRODUCT_CODE: WorkshopCodeRef = { recordType: "product", id: "cmsik2jg8000eh8xc1lcy662b" };

test("a code for the wrong kind of record is refused before the network, naming both types", () => {
  const refusal = scanTypeRefusal(PRODUCT_BOX, ARTISAN_CODE);
  expect(refusal).not.toBeNull();
  expect(refusal).toContain("artisan");
  expect(refusal).toContain("product");
  expect(refusal).toContain("Product");

  expect(scanTypeRefusal(PRODUCT_BOX, PRODUCT_CODE)).toBeNull();
  expect(scanTypeRefusal(ARTISAN_BOX, ARTISAN_CODE)).toBeNull();
  expect(scanTypeRefusal(ARTISAN_BOX, PRODUCT_CODE)).not.toBeNull();
});

test("a picker whose records carry no code is offered no reader, and refuses one if it is given", () => {
  expect(pickerTakesScans(SKETCH_BOX)).toBe(false);
  expect(pickerTakesScans(PRODUCT_BOX)).toBe(true);
  expect(pickerTakesScans(ARTISAN_BOX)).toBe(true);
  expect(pickerTakesScans(refField({ key: "x", label: "Prototype", refModel: "DwPrototype" }))).toBe(true);

  expect(scanTypeRefusal(SKETCH_BOX, ARTISAN_CODE)).toContain("no printed code");
});

test("an out-of-scope record is SAID and never returned as a choice", () => {
  const outcome = scanLookupOutcome({
    field: PRODUCT_BOX,
    ref: PRODUCT_CODE,
    payload: answer({ outOfScope: true, outOfScopeOption: option(PRODUCT_CODE.id, "Blue block-printed bag") }),
    cascadeLabel: "Artisan"
  });

  expect(outcome.ok).toBe(false);
  if (outcome.ok) return;
  expect(outcome.message).toContain("Blue block-printed bag");
  expect(outcome.message).toContain("different workshop");
  expect(outcome.message).toContain("Link that record to this workshop");
  expect(outcome.message).toContain("Nothing on this row has been changed");
});

test("a code that resolves to nothing gets one sentence that cannot be read as an existence check", () => {
  const missing = scanLookupOutcome({
    field: ARTISAN_BOX,
    ref: ARTISAN_CODE,
    payload: answer({ model: "Artisan", options: [] }),
    cascadeLabel: ""
  });
  expect(missing.ok).toBe(false);
  if (missing.ok) return;

  expect(missing.message).toContain("may not be in the records");
  expect(missing.message).toContain("don't have access to");
  expect(missing.message).toContain("artisan");

  const cascaded = scanLookupOutcome({
    field: PRODUCT_BOX,
    ref: PRODUCT_CODE,
    payload: answer({ options: [] }),
    cascadeLabel: "Artisan"
  });
  expect(cascaded.ok).toBe(false);
  if (cascaded.ok) return;
  expect(cascaded.message).toContain("chosen on this row");
});

test("a resolved record is handed back as the row to choose, and only when the id agrees", () => {
  const hit = scanLookupOutcome({
    field: PRODUCT_BOX,
    ref: PRODUCT_CODE,
    payload: answer({ options: [option(PRODUCT_CODE.id, "Blue block-printed bag")] }),
    cascadeLabel: "Artisan"
  });
  expect(hit.ok).toBe(true);
  if (!hit.ok) return;
  expect(hit.option.id).toBe(PRODUCT_CODE.id);

  const wrongRow = scanLookupOutcome({
    field: PRODUCT_BOX,
    ref: PRODUCT_CODE,
    payload: answer({ options: [option("cmsik2jg8000eh8xc1lcy999z", "Another artisan's bag")] }),
    cascadeLabel: "Artisan"
  });
  expect(wrongRow.ok).toBe(false);

  const prototypeBox = refField({ key: "prototypeRef", label: "Prototype", refModel: "DwPrototype" });
  const byClientKey: WorkshopCodeRef = { recordType: "prototype", id: "b7b1f6e0-0a54-4f0e-9d5f-2c1f3a4b5c6d" };
  const local = scanLookupOutcome({
    field: prototypeBox,
    ref: byClientKey,
    payload: answer({ model: "DwPrototype", options: [option("cmsik2jg8000eh8xc1lcy663c", "Bag, second try")] }),
    cascadeLabel: ""
  });
  expect(local.ok).toBe(true);

  const unnarrowed = scanLookupOutcome({
    field: prototypeBox,
    ref: byClientKey,
    payload: answer({
      model: "DwPrototype",
      truncated: true,
      options: [option("cmsik2jg8000eh8xc1lcy663c", "Bag, second try")]
    }),
    cascadeLabel: ""
  });
  expect(unnarrowed.ok).toBe(false);
});

const HELD = (filter: string, id = PRODUCT_CODE.id): ScanHeld => ({
  id,
  filter,
  option: option(id, "Blue block-printed bag")
});

const FOUND = { ok: true, label: "Blue block-printed bag" } as const;

test("a scan answered after the row's artisan changed is refused, and nothing is written", () => {
  const stale = scanCommitDecision({
    held: HELD("artisan-kamla"),
    ref: PRODUCT_CODE,
    resolution: FOUND,
    filterValue: "artisan-rekha",
    field: PRODUCT_BOX
  });
  expect(stale.commit).toBeNull();
  expect(stale.notice).toContain("changed while that code was being looked up");
  expect(stale.notice).toContain("Nothing on this row has been changed");

  const fresh = scanCommitDecision({
    held: HELD("artisan-kamla"),
    ref: PRODUCT_CODE,
    resolution: FOUND,
    filterValue: "artisan-kamla",
    field: PRODUCT_BOX
  });
  expect(fresh.commit?.id).toBe(PRODUCT_CODE.id);

  const uncascaded = scanCommitDecision({
    held: { ...HELD("", ARTISAN_CODE.id), option: option(ARTISAN_CODE.id, "Kamla Devi") },
    ref: ARTISAN_CODE,
    resolution: { ok: true, label: "Kamla Devi" },
    filterValue: "",
    field: ARTISAN_BOX
  });
  expect(uncascaded.commit?.id).toBe(ARTISAN_CODE.id);
});

test("the commit is what says the row was filled in, because the lookup cannot", () => {
  const written = scanCommitDecision({
    held: HELD("artisan-kamla"),
    ref: PRODUCT_CODE,
    resolution: FOUND,
    filterValue: "artisan-kamla",
    field: PRODUCT_BOX
  });
  expect(written.notice).toContain("Blue block-printed bag");
  expect(written.notice).toContain("Product");
  expect(written.notice).toContain("is now chosen");

  const refused = scanCommitDecision({
    held: null,
    ref: PRODUCT_CODE,
    resolution: { ok: false, message: "That code names an artisan…" },
    filterValue: "artisan-kamla",
    field: PRODUCT_BOX
  });
  expect(refused.commit).toBeNull();
  expect(refused.notice).toBeNull();

  const overtaken = scanCommitDecision({
    held: HELD("artisan-kamla", "cmsik2jg8000eh8xc1lcy999z"),
    ref: PRODUCT_CODE,
    resolution: FOUND,
    filterValue: "artisan-kamla",
    field: PRODUCT_BOX
  });
  expect(overtaken.commit).toBeNull();
  expect(overtaken.notice).toBeNull();
});

test("the lookup's own announcement claims nothing about the row", () => {
  expect(codeOf("components/designworkshop/StageReferenceField.tsx")).not.toContain("Chosen for");
});
